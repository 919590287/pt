#!/usr/bin/env python3
"""Build authoritative passenger-flow CSVs from Nansha CARD/RUN data.

The current route/station SHP files and line_stop_sequence.csv are the authority.
Raw records that cannot be mapped to the authority are excluded from fact tables
and counted in 数据质量报告.csv. Raw card numbers are never written; a stable HMAC
pseudonym is used instead.
"""

from __future__ import annotations

import argparse
import bisect
import collections
import csv
import datetime as dt
import difflib
import hashlib
import hmac
import math
import os
import re
import secrets
import shutil
import sqlite3
import sys
import tempfile
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import shapefile


RAW_DIR = Path("/Volumes/USB DISK/数据/南沙公交数据/南沙区公交刷卡数据")
CARD_CSV = RAW_DIR / "CARD20260310-0410.csv"
RUN_CSV = RAW_DIR / "RUN20260310-0410.csv"
AUTH_DIR = Path("/Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点")
ROUTES_SHP = AUTH_DIR / "线路/routes.shp"
STOPS_SHP = AUTH_DIR / "站点/stops.shp"
SEQUENCE_CSV = AUTH_DIR / "站点/line_stop_sequence.csv"
OUTPUT_DIR = AUTH_DIR.parent / "客流数据"

MAX_BOARD_MATCH_SECONDS = 180
MAX_ALIGHT_MATCH_SECONDS = 4 * 3600
MAX_TRANSFER_MINUTES = 120
MAX_PROJECTION_METERS = 1000.0


PASSENGER_FIELDS = [
    "trip_id", "rider_id", "service_date", "is_weekday", "card_type_raw",
    "passenger_group", "payment_medium", "company_raw", "plate_number",
    "route_name_raw", "route_code_raw", "direction_raw", "authority_line_id",
    "authority_route_id", "authority_route_name", "board_stop_id",
    "board_stop_name", "board_stop_seq", "board_lon", "board_lat", "board_time",
    "board_source", "board_confidence", "alight_stop_id", "alight_stop_name",
    "alight_stop_seq", "alight_lon", "alight_lat", "alight_time",
    "alight_time_source", "alight_source", "alight_confidence",
    "projection_distance_m", "trip_minutes", "fare_yuan", "is_resolved",
    "clean_status",
]

VEHICLE_EVENT_FIELDS = [
    "event_id", "service_date", "authority_line_id", "authority_route_id",
    "authority_route_name", "route_name_raw", "route_code_raw", "direction_raw",
    "company_raw", "plate_number", "stop_id", "stop_name", "stop_seq", "lon",
    "lat", "arrival_time", "leave_time", "station_type", "station_order_raw",
    "mileage_m", "run_time_min", "avg_speed_kmh", "mapping_confidence",
]

ROUTE_MAPPING_FIELDS = [
    "route_name_raw", "direction_raw", "route_code_raw", "start_station_raw",
    "end_station_raw", "raw_event_count", "authority_line_id",
    "authority_route_id", "authority_route_name", "route_code_normalized",
    "station_overlap", "endpoint_score", "total_score", "score_gap",
    "candidate_count", "mapping_status",
]

TRANSFER_FIELDS = [
    "rider_id", "service_date", "from_trip_id", "to_trip_id", "from_line_id",
    "to_line_id", "alight_stop_id", "next_board_stop_id", "alight_time",
    "next_board_time", "transfer_minutes", "walk_distance_m",
]


@dataclass(frozen=True)
class Stop:
    line_id: str
    seq: int
    stop_id: str
    name: str
    lon: float
    lat: float


@dataclass(frozen=True)
class Route:
    line_id: str
    route_id: str
    name: str
    label: str
    code: str
    start: str
    end: str
    company: str


@dataclass
class GroupInfo:
    count: int = 0
    stations: collections.Counter[str] | None = None

    def __post_init__(self) -> None:
        if self.stations is None:
            self.stations = collections.Counter()


def log(message: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {message}", flush=True)


def clean(value: object) -> str:
    if value is None:
        return ""
    return str(value).strip()


def canonical_route_code(value: object) -> str:
    text = unicodedata.normalize("NFKC", clean(value)).upper()
    text = re.sub(r"[\s（）()·\-—_./]", "", text)
    if text.startswith("南沙"):
        text = text[2:]
    elif re.match(r"^南(?=\d|[GKWT夜学旅游])", text):
        text = text[1:]
    text = text.replace("大站快线", "大站快").replace("快线", "快")
    text = text.replace("支线", "支").replace("A线", "A").replace("B线", "B")
    text = text.replace("路", "").replace("线", "")
    return text


def is_nansha_route_label(value: object) -> bool:
    text = re.sub(r"\s+", "", clean(value))
    return text.startswith("南沙") or bool(re.match(r"^南(?=\d|[GKWT夜学旅游])", text))


def normalize_station(value: object) -> str:
    text = unicodedata.normalize("NFKC", clean(value))
    text = re.sub(r"[\s\[\]【】·,，。;；]", "", text)
    text = re.sub(r"[（(](?:东|西|南|北|东行|西行|南行|北行|上行|下行)[）)]", "", text)
    text = text.replace("地铁站", "地铁")
    for suffix in ("公交总站", "公交站场", "客运站", "总站", "公交站", "站"):
        if text.endswith(suffix) and len(text) > len(suffix):
            text = text[: -len(suffix)]
            break
    return text


def split_endpoints(value: object) -> tuple[str, str]:
    text = clean(value).replace("－", "-").replace("—", "-").replace("–", "-")
    parts = re.split(r"\s*(?:--|→|至)\s*", text, maxsplit=1)
    if len(parts) == 1:
        parts = re.split(r"\s*-\s*|\s*~\s*", text, maxsplit=1)
    if len(parts) != 2:
        return "", ""
    return normalize_station(parts[0]), normalize_station(parts[1])


def route_label_and_endpoints(name: str) -> tuple[str, str, str]:
    stack: list[int] = []
    groups: list[tuple[int, int]] = []
    for index, char in enumerate(name):
        if char in "(（":
            stack.append(index)
        elif char in ")）" and stack:
            start = stack.pop()
            if not stack:
                groups.append((start, index))
    for start, end in groups:
        content = name[start + 1 : end]
        if any(token in content for token in ("--", "—", "－", "→", "至")):
            first, last = split_endpoints(content)
            return name[:start].strip(), first, last
    return name.strip(), "", ""


def similarity(left: str, right: str) -> float:
    if not left or not right:
        return 0.0
    if left == right:
        return 1.0
    if left in right or right in left:
        return min(len(left), len(right)) / max(len(left), len(right))
    return difflib.SequenceMatcher(None, left, right).ratio()


def parse_datetime(value: object) -> dt.datetime | None:
    text = clean(value)
    if not text:
        return None
    match = re.match(
        r"^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?",
        text,
    )
    if not match:
        return None
    parts = [int(item or 0) for item in match.groups()]
    try:
        return dt.datetime(*parts)
    except ValueError:
        return None


def timestamp_text(epoch: int | float | None) -> str:
    if epoch is None or epoch == "":
        return ""
    return dt.datetime.fromtimestamp(float(epoch)).strftime("%Y-%m-%d %H:%M:%S")


def float_or_none(value: object) -> float | None:
    try:
        text = clean(value)
        return float(text) if text else None
    except (TypeError, ValueError):
        return None


def int_or_none(value: object) -> int | None:
    value_float = float_or_none(value)
    return int(value_float) if value_float is not None else None


def round_text(value: float | None, digits: int = 2) -> str:
    return "" if value is None else f"{value:.{digits}f}"


def haversine_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    radius = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def passenger_attributes(card_type: str) -> tuple[str, str]:
    value = clean(card_type)
    if "学生" in value:
        group = "student"
    elif "老人" in value or "老年" in value:
        group = "elderly"
    elif "残疾" in value or "优抚" in value:
        group = "disability_or_concession"
    else:
        group = "general_or_unknown"
    if "二维码" in value or "码" in value:
        medium = "qr_code"
    elif "交通部" in value:
        medium = "national_transit_card"
    elif "羊城通" in value or "票卡" in value or "消费" in value:
        medium = "local_transit_card"
    else:
        medium = "other_or_unknown"
    return group, medium


def open_csv_writer(path: Path, fields: Sequence[str]) -> tuple[object, csv.DictWriter]:
    handle = path.open("w", encoding="utf-8-sig", newline="", buffering=1024 * 1024)
    writer = csv.DictWriter(handle, fieldnames=list(fields), extrasaction="ignore")
    writer.writeheader()
    return handle, writer


def load_authority() -> tuple[dict[str, Route], dict[str, list[Stop]], dict[str, Stop]]:
    routes: dict[str, Route] = {}
    reader = shapefile.Reader(str(ROUTES_SHP), encoding="utf-8")
    for record in reader.iterRecords():
        row = record.as_dict()
        name = clean(row.get("name"))
        label, start, end = route_label_and_endpoints(name)
        line_id = clean(row.get("line_id"))
        routes[line_id] = Route(
            line_id=line_id,
            route_id=clean(row.get("route_id")) or line_id,
            name=name,
            label=label,
            code=canonical_route_code(label),
            start=start,
            end=end,
            company=clean(row.get("company")),
        )
    authority_stops: dict[str, tuple[str, float, float]] = {}
    station_reader = shapefile.Reader(str(STOPS_SHP), encoding="utf-8")
    for record in station_reader.iterRecords():
        row = record.as_dict()
        stop_id = clean(row.get("stop_id"))
        authority_stops[stop_id] = (
            clean(row.get("stop_name")), float(row.get("lon")), float(row.get("lat"))
        )
    stops_by_line: dict[str, list[Stop]] = collections.defaultdict(list)
    stop_by_id: dict[str, Stop] = {}
    with SEQUENCE_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            line_id = clean(row["line_id"])
            stop_id = clean(row["stop_id"])
            authority_stop = authority_stops.get(stop_id)
            if line_id not in routes or authority_stop is None:
                continue
            stop = Stop(
                line_id=line_id,
                seq=int(row["seq"]),
                stop_id=stop_id,
                name=authority_stop[0],
                lon=authority_stop[1],
                lat=authority_stop[2],
            )
            stops_by_line[line_id].append(stop)
            stop_by_id.setdefault(stop.stop_id, stop)
    for values in stops_by_line.values():
        values.sort(key=lambda item: item.seq)
    missing_sequence = set(routes) - set(stops_by_line)
    if missing_sequence:
        log(f"警告：{len(missing_sequence)} 条权威线路没有站序，将不能接入客流事实。")
    return routes, dict(stops_by_line), stop_by_id


def scan_run_groups() -> dict[tuple[str, str, str, str, str], GroupInfo]:
    groups: dict[tuple[str, str, str, str, str], GroupInfo] = {}
    with RUN_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
        for index, row in enumerate(csv.DictReader(handle), 1):
            key = (
                clean(row["ROUTE_NAME"]), clean(row["ROUTE_DIRECTION"]),
                clean(row["ROUTE_CODE"]), clean(row["START_STATION"]),
                clean(row["END_STATION"]),
            )
            info = groups.setdefault(key, GroupInfo())
            info.count += 1
            position = normalize_station(row["POSITION_STATION"])
            if position:
                info.stations[position] += 1
            if index % 500_000 == 0:
                log(f"RUN 第一次扫描 {index:,} 行，发现 {len(groups)} 个线路方向组")
    return groups


def build_route_mapping(
    groups: dict[tuple[str, str, str, str, str], GroupInfo],
    routes: dict[str, Route],
    stops_by_line: dict[str, list[Stop]],
) -> tuple[dict[tuple[str, str, str, str, str], dict[str, object]], list[dict[str, object]]]:
    by_code: dict[str, list[Route]] = collections.defaultdict(list)
    authority_station_sets: dict[str, set[str]] = {}
    for route in routes.values():
        if route.line_id in stops_by_line:
            by_code[route.code].append(route)
            authority_station_sets[route.line_id] = {
                normalize_station(stop.name) for stop in stops_by_line[route.line_id]
            }
    result: dict[tuple[str, str, str, str, str], dict[str, object]] = {}
    report: list[dict[str, object]] = []
    for key, info in groups.items():
        raw_route, direction, route_code_raw, raw_start, raw_end = key
        raw_code = canonical_route_code(raw_route)
        raw_is_nansha = is_nansha_route_label(raw_route)
        candidates = [
            route for code, items in by_code.items()
            if raw_code and (code == raw_code or code.startswith(raw_code) or raw_code.startswith(code))
            for route in items
            if not raw_is_nansha or is_nansha_route_label(route.label)
        ]
        raw_stations = set(info.stations or {})
        start_norm, end_norm = normalize_station(raw_start), normalize_station(raw_end)
        scored: list[tuple[float, float, float, Route]] = []
        for route in candidates:
            authority_stations = authority_station_sets.get(route.line_id, set())
            overlap = len(raw_stations & authority_stations) / max(1, len(raw_stations))
            endpoint = (similarity(start_norm, route.start) + similarity(end_norm, route.end)) / 2
            code_score = 1.0 if route.code == raw_code else 0.72
            total = 0.62 * overlap + 0.33 * endpoint + 0.05 * code_score
            scored.append((total, overlap, endpoint, route))
        scored.sort(key=lambda item: item[0], reverse=True)
        best = scored[0] if scored else None
        second_score = scored[1][0] if len(scored) > 1 else 0.0
        accepted = bool(
            best
            and (best[0] >= 0.42 or (best[1] >= 0.50 and best[0] - second_score >= 0.05))
            and (best[1] >= 0.30 or best[2] >= 0.72)
            and (best[0] - second_score >= 0.015 or best[0] >= 0.76)
        )
        route = best[3] if best and accepted else None
        mapping = {
            "route": route,
            "score": best[0] if best else 0.0,
            "station_overlap": best[1] if best else 0.0,
            "endpoint_score": best[2] if best else 0.0,
            "score_gap": (best[0] - second_score) if best else 0.0,
            "candidate_count": len(candidates),
            "status": "mapped" if accepted else ("ambiguous_or_low_score" if best else "no_candidate"),
        }
        result[key] = mapping
        report.append({
            "route_name_raw": raw_route,
            "direction_raw": direction,
            "route_code_raw": route_code_raw,
            "start_station_raw": raw_start,
            "end_station_raw": raw_end,
            "raw_event_count": info.count,
            "authority_line_id": route.line_id if route else "",
            "authority_route_id": route.route_id if route else "",
            "authority_route_name": route.name if route else "",
            "route_code_normalized": raw_code,
            "station_overlap": round_text(best[1] if best else 0.0, 4),
            "endpoint_score": round_text(best[2] if best else 0.0, 4),
            "total_score": round_text(best[0] if best else 0.0, 4),
            "score_gap": round_text((best[0] - second_score) if best else 0.0, 4),
            "candidate_count": len(candidates),
            "mapping_status": mapping["status"],
        })
    report.sort(key=lambda row: (-int(row["raw_event_count"]), str(row["route_name_raw"])))
    return result, report


class StopMapper:
    def __init__(self, stops_by_line: dict[str, list[Stop]]) -> None:
        self.stops_by_line = stops_by_line
        self.by_name: dict[str, dict[str, list[Stop]]] = {}
        self.cache: dict[tuple[str, str, int | None, int | None], tuple[Stop | None, float]] = {}
        for line_id, stops in stops_by_line.items():
            names: dict[str, list[Stop]] = collections.defaultdict(list)
            for stop in stops:
                names[normalize_station(stop.name)].append(stop)
            self.by_name[line_id] = dict(names)

    def map(
        self,
        line_id: str,
        raw_name: str,
        order_hint: int | None = None,
        after_seq: int | None = None,
    ) -> tuple[Stop | None, float]:
        key = (line_id, clean(raw_name), order_hint, after_seq)
        if key in self.cache:
            return self.cache[key]
        normalized = normalize_station(raw_name)
        if not normalized or line_id not in self.by_name:
            return None, 0.0
        exact = self.by_name[line_id].get(normalized, [])
        candidates = [stop for stop in exact if after_seq is None or stop.seq > after_seq]
        if candidates:
            if order_hint is not None:
                chosen = min(candidates, key=lambda stop: abs(stop.seq - order_hint))
            else:
                chosen = min(candidates, key=lambda stop: stop.seq)
            self.cache[key] = (chosen, 1.0)
            return chosen, 1.0
        best_score = 0.0
        best_stops: list[Stop] = []
        for authority_name, stops in self.by_name[line_id].items():
            eligible = [stop for stop in stops if after_seq is None or stop.seq > after_seq]
            if not eligible:
                continue
            score = similarity(normalized, authority_name)
            if score > best_score + 1e-9:
                best_score, best_stops = score, eligible
            elif abs(score - best_score) < 1e-9:
                best_stops.extend(eligible)
        if best_score >= 0.74 and best_stops:
            if order_hint is not None:
                chosen = min(best_stops, key=lambda stop: abs(stop.seq - order_hint))
            else:
                chosen = min(best_stops, key=lambda stop: stop.seq)
            self.cache[key] = (chosen, best_score)
            return chosen, best_score
        self.cache[key] = (None, best_score)
        return None, best_score

    def project_downstream(
        self, line_id: str, after_seq: int, lon: float, lat: float
    ) -> tuple[Stop | None, float]:
        candidates = [stop for stop in self.stops_by_line.get(line_id, []) if stop.seq > after_seq]
        if not candidates:
            return None, math.inf
        chosen = min(candidates, key=lambda stop: haversine_m(lon, lat, stop.lon, stop.lat))
        distance = haversine_m(lon, lat, chosen.lon, chosen.lat)
        return (chosen, distance) if distance <= MAX_PROJECTION_METERS else (None, distance)


def create_database(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA cache_size=-300000;
        PRAGMA locking_mode=EXCLUSIVE;
        CREATE TABLE run_events (
            service_date TEXT NOT NULL, plate TEXT NOT NULL, raw_route TEXT NOT NULL,
            line_id TEXT NOT NULL, seq INTEGER NOT NULL, arrival_ts INTEGER NOT NULL,
            leave_ts INTEGER, stop_id TEXT NOT NULL, stop_name TEXT NOT NULL,
            lon REAL NOT NULL, lat REAL NOT NULL
        );
        CREATE TABLE boardings (
            id INTEGER PRIMARY KEY, trip_id TEXT NOT NULL, rider_id TEXT NOT NULL,
            service_date TEXT NOT NULL, is_weekday INTEGER NOT NULL,
            card_type TEXT, passenger_group TEXT, payment_medium TEXT, company TEXT,
            plate TEXT, raw_route TEXT, raw_route_code TEXT, raw_direction TEXT,
            line_id TEXT NOT NULL, route_id TEXT NOT NULL, route_name TEXT NOT NULL,
            board_stop_id TEXT NOT NULL, board_stop_name TEXT NOT NULL,
            board_seq INTEGER NOT NULL, board_lon REAL NOT NULL, board_lat REAL NOT NULL,
            board_ts INTEGER NOT NULL, board_source TEXT NOT NULL,
            board_confidence TEXT NOT NULL, direct_down_id TEXT,
            direct_down_name TEXT, direct_down_seq INTEGER, direct_down_lon REAL,
            direct_down_lat REAL, fare REAL
        );
        CREATE TABLE inferences (
            boarding_id INTEGER PRIMARY KEY, down_stop_id TEXT NOT NULL,
            down_stop_name TEXT NOT NULL, down_seq INTEGER NOT NULL,
            down_lon REAL NOT NULL, down_lat REAL NOT NULL, source TEXT NOT NULL,
            confidence TEXT NOT NULL, projection_distance REAL
        );
        CREATE TABLE alight_times (
            boarding_id INTEGER PRIMARY KEY, alight_ts INTEGER NOT NULL,
            source TEXT NOT NULL
        );
        """
    )
    return connection


def build_card_route_indexes(
    groups: dict[tuple[str, str, str, str, str], GroupInfo],
    mapping: dict[tuple[str, str, str, str, str], dict[str, object]],
) -> dict[str, dict[tuple[str, ...], str]]:
    counters: dict[str, dict[tuple[str, ...], collections.Counter[str]]] = {
        "full": collections.defaultdict(collections.Counter),
        "route_direction": collections.defaultdict(collections.Counter),
        "route_code": collections.defaultdict(collections.Counter),
        "route": collections.defaultdict(collections.Counter),
    }
    for key, info in groups.items():
        route_obj = mapping[key]["route"]
        if not isinstance(route_obj, Route):
            continue
        raw_route, direction, route_code, _, _ = key
        weight = info.count
        counters["full"][(raw_route, direction, route_code)][route_obj.line_id] += weight
        counters["route_direction"][(raw_route, direction)][route_obj.line_id] += weight
        counters["route_code"][(raw_route, route_code)][route_obj.line_id] += weight
        counters["route"][(raw_route,)][route_obj.line_id] += weight
    return {
        name: {key: values.most_common(1)[0][0] for key, values in index.items()}
        for name, index in counters.items()
    }


def line_for_card(indexes: dict[str, dict[tuple[str, ...], str]], row: dict[str, str]) -> str:
    route, direction, code = clean(row["ROUTE_NAME"]), clean(row["DIRECTION"]), clean(row["ROUTE_CODE"])
    return (
        indexes["full"].get((route, direction, code))
        or indexes["route_direction"].get((route, direction))
        or indexes["route_code"].get((route, code))
        or (indexes["route"].get((route,)) if direction else "")
        or ""
    )


def process_run(
    connection: sqlite3.Connection,
    staging: Path,
    routes: dict[str, Route],
    route_mapping: dict[tuple[str, str, str, str, str], dict[str, object]],
    stop_mapper: StopMapper,
    quality: collections.Counter[str],
) -> tuple[dict[tuple[str, int], list[float]], dict[tuple[str, str, str], dict[str, object]], dict[tuple[str, str], dict[str, object]]]:
    segment_stats: dict[tuple[str, int], list[float]] = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])
    vehicle_daily: dict[tuple[str, str, str], dict[str, object]] = {}
    line_daily: dict[tuple[str, str], dict[str, object]] = {}
    event_handle, event_writer = open_csv_writer(staging / "车辆到离站明细.csv", VEHICLE_EVENT_FIELDS)
    insert_rows: list[tuple[object, ...]] = []
    try:
        with RUN_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
            for index, row in enumerate(csv.DictReader(handle), 1):
                quality["run_raw_rows"] += 1
                key = (
                    clean(row["ROUTE_NAME"]), clean(row["ROUTE_DIRECTION"]),
                    clean(row["ROUTE_CODE"]), clean(row["START_STATION"]),
                    clean(row["END_STATION"]),
                )
                mapped = route_mapping.get(key)
                route = mapped["route"] if mapped else None
                if not isinstance(route, Route):
                    quality["run_unmapped_route_rows"] += 1
                    continue
                order_hint = int_or_none(row["STATION_ORDER_NO"])
                stop, stop_score = stop_mapper.map(route.line_id, row["POSITION_STATION"], order_hint)
                if stop is None:
                    quality["run_unmapped_stop_rows"] += 1
                    continue
                arrival = parse_datetime(row["ARRIVAL_TIME"])
                if arrival is None:
                    quality["run_invalid_arrival_rows"] += 1
                    continue
                leave = parse_datetime(row["LEAVE_TIME"])
                service_date = arrival.strftime("%Y-%m-%d")
                arrival_ts = int(arrival.timestamp())
                leave_ts = int(leave.timestamp()) if leave else None
                plate = clean(row["PLATE_NUMBER"])
                insert_rows.append((
                    service_date, plate, clean(row["ROUTE_NAME"]), route.line_id,
                    stop.seq, arrival_ts, leave_ts, stop.stop_id, stop.name,
                    stop.lon, stop.lat,
                ))
                if len(insert_rows) >= 25_000:
                    connection.executemany(
                        "INSERT INTO run_events VALUES (?,?,?,?,?,?,?,?,?,?,?)", insert_rows
                    )
                    insert_rows.clear()
                mileage = float_or_none(row["MILEAGE"])
                runtime = float_or_none(row["RUN_TIME"])
                speed = float_or_none(row["AVG_SPEED"])
                event_writer.writerow({
                    "event_id": clean(row["ID"]) or index,
                    "service_date": service_date,
                    "authority_line_id": route.line_id,
                    "authority_route_id": route.route_id,
                    "authority_route_name": route.name,
                    "route_name_raw": clean(row["ROUTE_NAME"]),
                    "route_code_raw": clean(row["ROUTE_CODE"]),
                    "direction_raw": clean(row["ROUTE_DIRECTION"]),
                    "company_raw": clean(row["COMPANY"]),
                    "plate_number": plate,
                    "stop_id": stop.stop_id,
                    "stop_name": stop.name,
                    "stop_seq": stop.seq,
                    "lon": f"{stop.lon:.8f}",
                    "lat": f"{stop.lat:.8f}",
                    "arrival_time": arrival.strftime("%Y-%m-%d %H:%M:%S"),
                    "leave_time": leave.strftime("%Y-%m-%d %H:%M:%S") if leave else "",
                    "station_type": clean(row["STATION_TYPE"]),
                    "station_order_raw": clean(row["STATION_ORDER_NO"]),
                    "mileage_m": round_text(mileage),
                    "run_time_min": round_text(runtime),
                    "avg_speed_kmh": round_text(speed),
                    "mapping_confidence": "high" if stop_score >= 0.999 else "medium",
                })
                quality["run_valid_event_rows"] += 1
                if runtime is not None and 0 < runtime <= 120 and stop.seq > 1:
                    stats = segment_stats[(route.line_id, stop.seq)]
                    stats[0] += runtime
                    stats[1] += 1
                    if mileage is not None and 0 <= mileage <= 100_000:
                        stats[2] += mileage
                        stats[3] += 1
                for daily_key, table in (
                    ((service_date, plate, route.line_id), vehicle_daily),
                    ((service_date, route.line_id), line_daily),
                ):
                    agg = table.setdefault(daily_key, {
                        "event_count": 0, "trip_count": 0, "first_ts": arrival_ts,
                        "last_ts": arrival_ts, "mileage_m": 0.0, "run_time_min": 0.0,
                        "speed_sum": 0.0, "speed_count": 0,
                    })
                    agg["event_count"] += 1
                    if (order_hint or 0) <= 1:
                        agg["trip_count"] += 1
                    agg["first_ts"] = min(int(agg["first_ts"]), arrival_ts)
                    agg["last_ts"] = max(int(agg["last_ts"]), arrival_ts)
                    if mileage is not None and 0 <= mileage <= 100_000:
                        agg["mileage_m"] += mileage
                    if runtime is not None and 0 <= runtime <= 120:
                        agg["run_time_min"] += runtime
                    if speed is not None and 0 <= speed <= 100:
                        agg["speed_sum"] += speed
                        agg["speed_count"] += 1
                if index % 500_000 == 0:
                    connection.commit()
                    log(f"RUN 清洗 {index:,} 行，有效权威到离站 {quality['run_valid_event_rows']:,} 行")
        if insert_rows:
            connection.executemany("INSERT INTO run_events VALUES (?,?,?,?,?,?,?,?,?,?,?)", insert_rows)
        connection.commit()
    finally:
        event_handle.close()
    log("创建 RUN 时空索引")
    connection.executescript(
        """
        CREATE INDEX idx_run_board ON run_events(service_date, plate, arrival_ts);
        CREATE INDEX idx_run_alight ON run_events(service_date, plate, line_id, arrival_ts);
        """
    )
    connection.commit()
    return segment_stats, vehicle_daily, line_daily


def nearest_run_board(
    connection: sqlite3.Connection, service_date: str, plate: str,
    raw_route: str, board_ts: int,
) -> tuple[str, int, str, str, float, float, int] | None:
    row = connection.execute(
        """
        SELECT line_id, seq, stop_id, stop_name, lon, lat, arrival_ts
        FROM run_events
        WHERE service_date=? AND plate=? AND arrival_ts BETWEEN ? AND ?
        ORDER BY (raw_route=?) DESC, ABS(arrival_ts-?) ASC LIMIT 1
        """,
        (service_date, plate, board_ts - MAX_BOARD_MATCH_SECONDS,
         board_ts + MAX_BOARD_MATCH_SECONDS, raw_route, board_ts),
    ).fetchone()
    return tuple(row) if row else None


def process_card_boardings(
    connection: sqlite3.Connection,
    key: bytes,
    routes: dict[str, Route],
    card_route_indexes: dict[str, dict[tuple[str, ...], str]],
    stop_mapper: StopMapper,
    quality: collections.Counter[str],
) -> None:
    insert_rows: list[tuple[object, ...]] = []
    with CARD_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
        for source_index, row in enumerate(csv.DictReader(handle), 1):
            quality["card_raw_rows"] += 1
            board_dt = parse_datetime(row["TRAN_TIME"])
            raw_card = clean(row["CARD"])
            if board_dt is None or not raw_card:
                quality["card_invalid_identity_or_time_rows"] += 1
                continue
            service_date = board_dt.strftime("%Y-%m-%d")
            board_ts = int(board_dt.timestamp())
            raw_route = clean(row["ROUTE_NAME"])
            plate = clean(row["PLATE_NUMBER"])
            line_id = line_for_card(card_route_indexes, row)
            stop: Stop | None = None
            stop_score = 0.0
            board_source = "card_station"
            if line_id and clean(row["UP_STATION"]):
                stop, stop_score = stop_mapper.map(line_id, row["UP_STATION"])
            if stop is None and plate and plate != "无车号":
                nearest = nearest_run_board(connection, service_date, plate, raw_route, board_ts)
                if nearest:
                    line_id, seq, stop_id, stop_name, lon, lat, _ = nearest
                    stop = Stop(line_id, int(seq), stop_id, stop_name, float(lon), float(lat))
                    stop_score = 1.0
                    board_source = "nearest_run_3min"
                    quality["card_board_recovered_by_run_rows"] += 1
            if not line_id:
                quality["card_unmapped_route_rows"] += 1
                continue
            if stop is None:
                quality["card_unmapped_board_stop_rows"] += 1
                continue
            route = routes.get(line_id)
            if route is None:
                quality["card_authority_route_missing_rows"] += 1
                continue
            direct_down, down_score = stop_mapper.map(
                line_id, row["DOWN_STATION"], after_seq=stop.seq
            ) if clean(row["DOWN_STATION"]) else (None, 0.0)
            rider_id = hmac.new(key, raw_card.encode("utf-8"), hashlib.sha256).hexdigest()[:24]
            trip_material = f"{rider_id}|{board_ts}|{raw_route}|{source_index}"
            trip_id = hashlib.sha256(trip_material.encode("utf-8")).hexdigest()[:24]
            card_type = clean(row["CARD_TYPE"])
            passenger_group, payment_medium = passenger_attributes(card_type)
            fare = float_or_none(row["COST"])
            insert_rows.append((
                source_index, trip_id, rider_id, service_date,
                1 if board_dt.weekday() < 5 else 0, card_type, passenger_group,
                payment_medium, clean(row["COMPANY_NAME"]), plate, raw_route,
                clean(row["ROUTE_CODE"]), clean(row["DIRECTION"]), route.line_id,
                route.route_id, route.name, stop.stop_id, stop.name, stop.seq,
                stop.lon, stop.lat, board_ts, board_source,
                "high" if board_source == "card_station" and stop_score >= 0.999 else "medium",
                direct_down.stop_id if direct_down else None,
                direct_down.name if direct_down else None,
                direct_down.seq if direct_down else None,
                direct_down.lon if direct_down else None,
                direct_down.lat if direct_down else None,
                fare,
            ))
            if direct_down:
                quality["card_direct_downstream_rows"] += 1
            elif clean(row["DOWN_STATION"]):
                quality["card_invalid_direct_down_rows"] += 1
            quality["card_valid_boarding_rows"] += 1
            if len(insert_rows) >= 20_000:
                connection.executemany(
                    "INSERT INTO boardings VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    insert_rows,
                )
                insert_rows.clear()
            if source_index % 250_000 == 0:
                connection.commit()
                log(f"CARD 清洗 {source_index:,} 行，有效权威上车 {quality['card_valid_boarding_rows']:,} 行")
    if insert_rows:
        connection.executemany(
            "INSERT INTO boardings VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            insert_rows,
        )
    connection.commit()
    log("创建刷卡行程索引")
    connection.executescript(
        """
        CREATE INDEX idx_board_rider_day ON boardings(rider_id, service_date, board_ts);
        CREATE INDEX idx_board_alight_join ON boardings(service_date, plate, line_id, board_ts);
        CREATE INDEX idx_board_history ON boardings(rider_id, line_id, board_stop_id);
        """
    )
    connection.commit()


BOARDING_SELECT = """
SELECT id, trip_id, rider_id, service_date, line_id, board_stop_id, board_stop_name,
       board_seq, board_lon, board_lat, board_ts, direct_down_id, direct_down_name,
       direct_down_seq, direct_down_lon, direct_down_lat
FROM boardings ORDER BY rider_id, service_date, board_ts, id
"""


def grouped_rows(cursor: Iterable[Sequence[object]], key_indexes: Sequence[int]) -> Iterator[tuple[tuple[object, ...], list[Sequence[object]]]]:
    current_key: tuple[object, ...] | None = None
    bucket: list[Sequence[object]] = []
    for row in cursor:
        key = tuple(row[index] for index in key_indexes)
        if current_key is not None and key != current_key:
            yield current_key, bucket
            bucket = []
        current_key = key
        bucket.append(row)
    if current_key is not None:
        yield current_key, bucket


def infer_alighting(
    connection: sqlite3.Connection,
    stop_mapper: StopMapper,
    quality: collections.Counter[str],
) -> None:
    inserts: list[tuple[object, ...]] = []
    mode_counts: collections.Counter[tuple[str, str, str, str]] = collections.Counter()
    processed = 0
    cursor = connection.execute(BOARDING_SELECT)
    for _, rows in grouped_rows(cursor, (2, 3)):
        for index, row in enumerate(rows):
            (
                boarding_id, _, rider_id, _, line_id, _, _, board_seq, _, _, board_ts,
                direct_down_id, direct_down_name, direct_down_seq, direct_down_lon,
                direct_down_lat,
            ) = row
            chosen: Stop | None = None
            source = ""
            confidence = ""
            distance: float | None = None
            if direct_down_id and direct_down_seq:
                chosen = Stop(
                    str(line_id), int(direct_down_seq), str(direct_down_id),
                    str(direct_down_name), float(direct_down_lon), float(direct_down_lat),
                )
                source, confidence, distance = "direct_card_down", "high", 0.0
            elif index + 1 < len(rows):
                next_row = rows[index + 1]
                chosen, distance = stop_mapper.project_downstream(
                    str(line_id), int(board_seq), float(next_row[8]), float(next_row[9])
                )
                if chosen:
                    source, confidence = "next_board_projection", "medium" if distance <= 500 else "low"
            elif len(rows) >= 2:
                first = rows[0]
                first_hour = dt.datetime.fromtimestamp(int(first[10])).hour
                last_hour = dt.datetime.fromtimestamp(int(board_ts)).hour
                if first_hour < 12 <= last_hour:
                    chosen, distance = stop_mapper.project_downstream(
                        str(line_id), int(board_seq), float(first[8]), float(first[9])
                    )
                    if chosen:
                        source, confidence = "daily_return_projection", "low"
            if chosen:
                inserts.append((
                    int(boarding_id), chosen.stop_id, chosen.name, chosen.seq,
                    chosen.lon, chosen.lat, source, confidence, distance,
                ))
                mode_counts[(str(rider_id), str(line_id), str(row[5]), chosen.stop_id)] += 1
                quality[f"alight_{source}_rows"] += 1
            processed += 1
            if len(inserts) >= 20_000:
                connection.executemany("INSERT OR IGNORE INTO inferences VALUES (?,?,?,?,?,?,?,?,?)", inserts)
                inserts.clear()
            if processed % 500_000 == 0:
                connection.commit()
                log(f"下车链推断 {processed:,} 条上车记录")
    if inserts:
        connection.executemany("INSERT OR IGNORE INTO inferences VALUES (?,?,?,?,?,?,?,?,?)", inserts)
    connection.commit()

    by_origin: dict[tuple[str, str, str], collections.Counter[str]] = collections.defaultdict(collections.Counter)
    for (rider_id, line_id, board_stop_id, down_stop_id), count in mode_counts.items():
        by_origin[(rider_id, line_id, board_stop_id)][down_stop_id] += count
    stable_modes: dict[tuple[str, str, str], tuple[str, int, float]] = {}
    for origin, counts in by_origin.items():
        total = sum(counts.values())
        down_stop_id, support = counts.most_common(1)[0]
        share = support / total
        if support >= 2 and share >= 0.60:
            stable_modes[origin] = (down_stop_id, support, share)
    log(f"形成 {len(stable_modes):,} 个同卡同线路稳定下车模式")
    if stable_modes:
        stop_lookup: dict[tuple[str, str], Stop] = {
            (line_id, stop.stop_id): stop
            for line_id, stops in stop_mapper.stops_by_line.items() for stop in stops
        }
        inserts = []
        unresolved_cursor = connection.execute(
            """
            SELECT b.id, b.rider_id, b.line_id, b.board_stop_id, b.board_seq
            FROM boardings b LEFT JOIN inferences i ON i.boarding_id=b.id
            WHERE i.boarding_id IS NULL
            """
        )
        for row in unresolved_cursor:
            mode = stable_modes.get((str(row[1]), str(row[2]), str(row[3])))
            if not mode:
                continue
            stop = stop_lookup.get((str(row[2]), mode[0]))
            if not stop or stop.seq <= int(row[4]):
                continue
            inserts.append((
                int(row[0]), stop.stop_id, stop.name, stop.seq, stop.lon, stop.lat,
                "historical_mode", "low", None,
            ))
            quality["alight_historical_mode_rows"] += 1
            if len(inserts) >= 20_000:
                connection.executemany("INSERT OR IGNORE INTO inferences VALUES (?,?,?,?,?,?,?,?,?)", inserts)
                inserts.clear()
        if inserts:
            connection.executemany("INSERT OR IGNORE INTO inferences VALUES (?,?,?,?,?,?,?,?,?)", inserts)
        connection.commit()
    quality["alight_resolved_rows"] = connection.execute("SELECT COUNT(*) FROM inferences").fetchone()[0]
    quality["alight_unresolved_rows"] = quality["card_valid_boarding_rows"] - quality["alight_resolved_rows"]


def merge_group_iterators(
    run_cursor: Iterable[Sequence[object]], trip_cursor: Iterable[Sequence[object]]
) -> Iterator[tuple[list[Sequence[object]], list[Sequence[object]]]]:
    run_groups = iter(grouped_rows(run_cursor, (0, 1, 2)))
    trip_groups = iter(grouped_rows(trip_cursor, (0, 1, 2)))
    try:
        run_key, run_rows = next(run_groups)
    except StopIteration:
        return
    try:
        trip_key, trip_rows = next(trip_groups)
    except StopIteration:
        return
    while True:
        if run_key < trip_key:
            try:
                run_key, run_rows = next(run_groups)
            except StopIteration:
                return
        elif trip_key < run_key:
            try:
                trip_key, trip_rows = next(trip_groups)
            except StopIteration:
                return
        else:
            yield run_rows, trip_rows
            try:
                run_key, run_rows = next(run_groups)
                trip_key, trip_rows = next(trip_groups)
            except StopIteration:
                return


def attach_alight_times(connection: sqlite3.Connection, quality: collections.Counter[str]) -> None:
    run_cursor = connection.execute(
        """
        SELECT service_date, plate, line_id, seq, arrival_ts
        FROM run_events WHERE plate<>'' ORDER BY service_date, plate, line_id, arrival_ts
        """
    )
    trip_cursor = connection.execute(
        """
        SELECT b.service_date, b.plate, b.line_id, b.id, b.board_ts, i.down_seq
        FROM boardings b JOIN inferences i ON i.boarding_id=b.id
        WHERE b.plate<>'' AND b.plate<>'无车号'
        ORDER BY b.service_date, b.plate, b.line_id, b.board_ts
        """
    )
    inserts: list[tuple[int, int, str]] = []
    for run_rows, trip_rows in merge_group_iterators(run_cursor, trip_cursor):
        times_by_seq: dict[int, list[int]] = collections.defaultdict(list)
        for row in run_rows:
            times_by_seq[int(row[3])].append(int(row[4]))
        for trip in trip_rows:
            boarding_id, board_ts, down_seq = int(trip[3]), int(trip[4]), int(trip[5])
            times = times_by_seq.get(down_seq, [])
            position = bisect.bisect_left(times, board_ts)
            if position < len(times) and times[position] - board_ts <= MAX_ALIGHT_MATCH_SECONDS:
                inserts.append((boarding_id, times[position], "vehicle_run"))
                quality["alight_time_vehicle_run_rows"] += 1
        if len(inserts) >= 20_000:
            connection.executemany("INSERT OR REPLACE INTO alight_times VALUES (?,?,?)", inserts)
            inserts.clear()
    if inserts:
        connection.executemany("INSERT OR REPLACE INTO alight_times VALUES (?,?,?)", inserts)
    connection.commit()


def write_operations(
    staging: Path,
    routes: dict[str, Route],
    vehicle_daily: dict[tuple[str, str, str], dict[str, object]],
    line_daily: dict[tuple[str, str], dict[str, object]],
    passenger_line_daily: collections.Counter[tuple[str, str, str]],
) -> None:
    vehicle_fields = [
        "service_date", "plate_number", "authority_line_id", "authority_route_name",
        "stop_event_count", "trip_start_count", "first_event_time", "last_event_time",
        "operating_hours", "mileage_km", "run_time_min", "avg_speed_kmh",
    ]
    handle, writer = open_csv_writer(staging / "车辆日运营统计.csv", vehicle_fields)
    try:
        for (date, plate, line_id), agg in sorted(vehicle_daily.items()):
            speed_count = int(agg["speed_count"])
            writer.writerow({
                "service_date": date, "plate_number": plate,
                "authority_line_id": line_id,
                "authority_route_name": routes[line_id].name,
                "stop_event_count": agg["event_count"],
                "trip_start_count": agg["trip_count"],
                "first_event_time": timestamp_text(agg["first_ts"]),
                "last_event_time": timestamp_text(agg["last_ts"]),
                "operating_hours": round_text((int(agg["last_ts"]) - int(agg["first_ts"])) / 3600),
                "mileage_km": round_text(float(agg["mileage_m"]) / 1000),
                "run_time_min": round_text(float(agg["run_time_min"])),
                "avg_speed_kmh": round_text(float(agg["speed_sum"]) / speed_count if speed_count else None),
            })
    finally:
        handle.close()
    line_fields = [
        "service_date", "authority_line_id", "authority_route_name", "vehicle_count",
        "stop_event_count", "trip_start_count", "first_event_time", "last_event_time",
        "mileage_km", "run_time_min", "avg_speed_kmh", "boarding_count",
        "resolved_alighting_count",
    ]
    vehicles: collections.Counter[tuple[str, str]] = collections.Counter()
    for date, _, line_id in vehicle_daily:
        vehicles[(date, line_id)] += 1
    handle, writer = open_csv_writer(staging / "线路日运营统计.csv", line_fields)
    try:
        for (date, line_id), agg in sorted(line_daily.items()):
            speed_count = int(agg["speed_count"])
            writer.writerow({
                "service_date": date, "authority_line_id": line_id,
                "authority_route_name": routes[line_id].name,
                "vehicle_count": vehicles[(date, line_id)],
                "stop_event_count": agg["event_count"],
                "trip_start_count": agg["trip_count"],
                "first_event_time": timestamp_text(agg["first_ts"]),
                "last_event_time": timestamp_text(agg["last_ts"]),
                "mileage_km": round_text(float(agg["mileage_m"]) / 1000),
                "run_time_min": round_text(float(agg["run_time_min"])),
                "avg_speed_kmh": round_text(float(agg["speed_sum"]) / speed_count if speed_count else None),
                "boarding_count": passenger_line_daily[(date, line_id, "board")],
                "resolved_alighting_count": passenger_line_daily[(date, line_id, "alight")],
            })
    finally:
        handle.close()


def write_segment_runtime(
    staging: Path,
    routes: dict[str, Route],
    stops_by_line: dict[str, list[Stop]],
    segment_stats: dict[tuple[str, int], list[float]],
) -> int:
    """Write observed link travel times for station reachability calculations."""
    stop_by_line_seq = {
        (line_id, stop.seq): stop
        for line_id, stops in stops_by_line.items() for stop in stops
    }
    fields = [
        "authority_line_id", "authority_route_name", "from_stop_id",
        "from_stop_name", "to_stop_id", "to_stop_name", "from_seq",
        "sample_count", "avg_run_time_min", "avg_mileage_m",
    ]
    row_count = 0
    handle, writer = open_csv_writer(staging / "区间运行时间统计.csv", fields)
    try:
        for (line_id, to_seq), values in sorted(segment_stats.items()):
            from_stop = stop_by_line_seq.get((line_id, to_seq - 1))
            to_stop = stop_by_line_seq.get((line_id, to_seq))
            if not from_stop or not to_stop or not values[1]:
                continue
            mileage_count = values[3] if len(values) > 3 else values[1]
            writer.writerow({
                "authority_line_id": line_id,
                "authority_route_name": routes[line_id].name,
                "from_stop_id": from_stop.stop_id,
                "from_stop_name": from_stop.name,
                "to_stop_id": to_stop.stop_id,
                "to_stop_name": to_stop.name,
                "from_seq": from_stop.seq,
                "sample_count": int(values[1]),
                "avg_run_time_min": round_text(values[0] / values[1]),
                "avg_mileage_m": round_text(values[2] / mileage_count if mileage_count else None),
            })
            row_count += 1
    finally:
        handle.close()
    return row_count


def write_overall_flow(staging: Path) -> int:
    """Keep all time-valid swipes for network-wide totals, even without a map position."""
    aggregates: dict[tuple[str, int], dict[str, float]] = {}
    with CARD_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            event_time = parse_datetime(row["TRAN_TIME"])
            if event_time is None:
                continue
            key = (event_time.strftime("%Y-%m-%d"), event_time.hour)
            agg = aggregates.setdefault(key, {
                "swipes": 0, "fare": 0.0, "student": 0, "elderly": 0,
                "concession": 0, "general": 0,
            })
            agg["swipes"] += 1
            agg["fare"] += float_or_none(row["COST"]) or 0.0
            group, _ = passenger_attributes(row["CARD_TYPE"])
            if group == "student":
                agg["student"] += 1
            elif group == "elderly":
                agg["elderly"] += 1
            elif group == "disability_or_concession":
                agg["concession"] += 1
            else:
                agg["general"] += 1
    fields = [
        "service_date", "hour", "all_swipe_count", "fare_amount_yuan",
        "student_count", "elderly_count", "concession_count",
        "general_or_unknown_count",
    ]
    handle, writer = open_csv_writer(staging / "总体小时客流.csv", fields)
    try:
        for (date, hour), agg in sorted(aggregates.items()):
            writer.writerow({
                "service_date": date, "hour": hour,
                "all_swipe_count": int(agg["swipes"]),
                "fare_amount_yuan": round_text(agg["fare"]),
                "student_count": int(agg["student"]),
                "elderly_count": int(agg["elderly"]),
                "concession_count": int(agg["concession"]),
                "general_or_unknown_count": int(agg["general"]),
            })
    finally:
        handle.close()
    return len(aggregates)


def write_module_availability(staging: Path) -> int:
    fields = [
        "platform_module", "left_panel_module", "availability", "primary_data",
        "available_statistics", "known_limitations",
    ]
    rows = [
        ("运行监测", "公交出行监测-人口分布监测", "unavailable", "", "", "刷卡数据没有常住人口、居住地、性别、精确年龄和职业，不能当作人口统计"),
        ("运行监测", "公交出行监测-站点OD监测", "partial", "乘客行程明细.csv", "上车出行分布、已解析目的地分布、时段分布", "仅保留74.51%的权威上车行程；目的地解析率54.23%"),
        ("运行监测", "公交出行监测-公交OD监测", "partial", "线路OD日统计.csv", "线路方向OD、站点OD、分时OD", "下车包含链式推断和历史模式，不等同于全量实测下车"),
        ("运行监测", "总体客流监测", "available", "总体小时客流.csv", "全量刷卡人次、票款、票卡客群、小时与日期趋势", "代表刷卡客流，不含现金或其他未入库乘客"),
        ("运行监测", "客流走廊监测-线路重复系数", "available", "公交线路站点/线路/routes.shp", "线路空间重合长度与重复系数", "该指标来自现行线路几何，本身不依赖刷卡数据"),
        ("运行监测", "客流走廊监测-公交客流走廊", "partial", "断面小时客流.csv", "高客流断面、走廊强度、分时客流", "仅使用已解析下车的行程"),
        ("运行监测", "线路客流监测", "available_with_partial_alighting", "线路小时客流.csv", "线路上车、下车、客流趋势和线路排名", "上车为清洗后事实；下车为已解析行程"),
        ("运行监测", "站点客流监测", "available_with_partial_alighting", "站点小时客流.csv", "站点上车、下车、峰值和排名", "上车为清洗后事实；下车为已解析行程"),
        ("运行监测", "车辆运行监测", "available", "车辆到离站明细.csv;车辆日运营统计.csv", "车辆班次、里程、运行时长、速度和到离站事件", "只统计能映射到现行线路和站序的RUN事件"),
        ("运行监测", "体检评估分析", "partial", "线路日运营统计.csv;站点小时客流.csv", "客流强度、运营里程、班次、速度、站线负荷等数据项", "投诉、事故、成本、容量、准点阈值等外部指标仍缺失"),
        ("客流分析", "线路客流监测-断面客流", "partial", "断面小时客流.csv", "线路各相邻站断面客流及峰值", "仅使用已解析下车的行程"),
        ("客流分析", "线路客流监测-站点乘降", "available_with_partial_alighting", "线路小时客流.csv;站点小时客流.csv", "指定线路各站乘降与分时趋势", "下车为已解析行程"),
        ("客流分析", "线路客流监测-客流画像", "partial", "客群小时统计.csv;乘客行程明细.csv", "学生、老人、优抚/残疾、一般/未知及支付介质", "不能得到性别、精确年龄、职业和出行目的"),
        ("客流分析", "线路客流监测-关联线路", "partial", "换乘明细.csv", "关联线路、换乘量、换乘时间和步行距离", "依赖已解析下车与同卡连续乘车链"),
        ("客流分析", "站点客流监测-站点乘降", "available_with_partial_alighting", "站点小时客流.csv", "指定站点乘降、峰值和趋势", "下车为已解析行程"),
        ("客流分析", "站点客流监测-客流OD", "partial", "线路OD日统计.csv;乘客行程明细.csv", "指定站点来源/去向、线路方向OD", "目的地解析率54.23%"),
        ("客流分析", "站点客流监测-客流画像", "partial", "客群小时统计.csv", "站点学生、老人、优抚/残疾、一般/未知客群", "不能得到完整人口学画像和出行目的"),
        ("客流分析", "站点客流监测-可达性", "partial", "区间运行时间统计.csv;线路站点SHP及站序", "基于实测区间时间的公交网络时距可达性", "缺少道路步行网络和门到门接驳时间"),
    ]
    handle, writer = open_csv_writer(staging / "模块可用性说明.csv", fields)
    try:
        for values in rows:
            writer.writerow(dict(zip(fields, values)))
    finally:
        handle.close()
    return len(rows)


def write_passenger_outputs(
    connection: sqlite3.Connection,
    staging: Path,
    routes: dict[str, Route],
    stops_by_line: dict[str, list[Stop]],
    segment_stats: dict[tuple[str, int], list[float]],
    quality: collections.Counter[str],
) -> tuple[collections.Counter[tuple[str, str, str]], dict[str, object]]:
    mean_segment_minutes = {
        key: values[0] / values[1] for key, values in segment_stats.items() if values[1]
    }
    trip_handle, trip_writer = open_csv_writer(staging / "乘客行程明细.csv", PASSENGER_FIELDS)
    transfer_handle, transfer_writer = open_csv_writer(staging / "换乘明细.csv", TRANSFER_FIELDS)
    line_hour: collections.Counter[tuple[str, int, str, str]] = collections.Counter()
    station_hour: collections.Counter[tuple[str, int, str, str, str, str]] = collections.Counter()
    segment_hour: collections.Counter[tuple[str, int, str, int]] = collections.Counter()
    od_daily: collections.Counter[tuple[str, str, str, str]] = collections.Counter()
    profile_hour: collections.Counter[tuple[str, str, str, int, str]] = collections.Counter()
    passenger_line_daily: collections.Counter[tuple[str, str, str]] = collections.Counter()
    transfers = 0
    previous_by_rider_day: dict[tuple[str, str], dict[str, object]] = {}
    stop_by_line_seq = {
        (line_id, stop.seq): stop for line_id, stops in stops_by_line.items() for stop in stops
    }
    query = connection.execute(
        """
        SELECT b.*, i.down_stop_id, i.down_stop_name, i.down_seq, i.down_lon,
               i.down_lat, i.source AS alight_source,
               i.confidence AS alight_confidence,
               i.projection_distance, a.alight_ts,
               a.source AS alight_time_source
        FROM boardings b
        LEFT JOIN inferences i ON i.boarding_id=b.id
        LEFT JOIN alight_times a ON a.boarding_id=b.id
        ORDER BY b.rider_id, b.service_date, b.board_ts, b.id
        """
    )
    columns = [item[0] for item in query.description]
    try:
        for processed, values in enumerate(query, 1):
            row = dict(zip(columns, values))
            board_ts = int(row["board_ts"])
            board_dt = dt.datetime.fromtimestamp(board_ts)
            resolved = row["down_stop_id"] is not None
            alight_ts = int(row["alight_ts"]) if row["alight_ts"] is not None else None
            alight_time_source = clean(row.get("alight_time_source"))
            if resolved and alight_ts is None:
                travel_min = 0.0
                for downstream_seq in range(int(row["board_seq"]) + 1, int(row["down_seq"]) + 1):
                    travel_min += mean_segment_minutes.get((row["line_id"], downstream_seq), 3.0)
                travel_min = min(max(travel_min, 2.0), 240.0)
                alight_ts = board_ts + int(round(travel_min * 60))
                alight_time_source = "segment_mean"
                quality["alight_time_segment_mean_rows"] += 1
            trip_minutes = (alight_ts - board_ts) / 60 if resolved and alight_ts else None
            if trip_minutes is not None and not (0 <= trip_minutes <= 240):
                alight_ts = board_ts + min(max((int(row["down_seq"]) - int(row["board_seq"])) * 180, 120), 14_400)
                trip_minutes = (alight_ts - board_ts) / 60
                alight_time_source = "segment_mean_repair"
                quality["alight_time_repaired_rows"] += 1
            out = {
                "trip_id": row["trip_id"], "rider_id": row["rider_id"],
                "service_date": row["service_date"], "is_weekday": row["is_weekday"],
                "card_type_raw": row["card_type"], "passenger_group": row["passenger_group"],
                "payment_medium": row["payment_medium"], "company_raw": row["company"],
                "plate_number": row["plate"], "route_name_raw": row["raw_route"],
                "route_code_raw": row["raw_route_code"], "direction_raw": row["raw_direction"],
                "authority_line_id": row["line_id"], "authority_route_id": row["route_id"],
                "authority_route_name": row["route_name"],
                "board_stop_id": row["board_stop_id"], "board_stop_name": row["board_stop_name"],
                "board_stop_seq": row["board_seq"], "board_lon": f"{row['board_lon']:.8f}",
                "board_lat": f"{row['board_lat']:.8f}",
                "board_time": timestamp_text(board_ts), "board_source": row["board_source"],
                "board_confidence": row["board_confidence"],
                "alight_stop_id": row["down_stop_id"] or "",
                "alight_stop_name": row["down_stop_name"] or "",
                "alight_stop_seq": row["down_seq"] or "",
                "alight_lon": f"{row['down_lon']:.8f}" if row["down_lon"] is not None else "",
                "alight_lat": f"{row['down_lat']:.8f}" if row["down_lat"] is not None else "",
                "alight_time": timestamp_text(alight_ts),
                "alight_time_source": alight_time_source if resolved else "",
                "alight_source": row["alight_source"] or "",
                "alight_confidence": row["alight_confidence"] or "",
                "projection_distance_m": round_text(row["projection_distance"]),
                "trip_minutes": round_text(trip_minutes), "fare_yuan": round_text(row["fare"]),
                "is_resolved": 1 if resolved else 0,
                "clean_status": "valid_complete" if resolved else "valid_boarding_only",
            }
            trip_writer.writerow(out)
            date, hour, line_id = row["service_date"], board_dt.hour, row["line_id"]
            line_hour[(date, hour, line_id, "board")] += 1
            station_hour[(date, hour, line_id, row["board_stop_id"], row["board_stop_name"], "board")] += 1
            profile_hour[("line", line_id, date, hour, row["passenger_group"])] += 1
            profile_hour[("station", row["board_stop_id"], date, hour, row["passenger_group"])] += 1
            passenger_line_daily[(date, line_id, "board")] += 1
            if resolved and alight_ts is not None:
                alight_dt = dt.datetime.fromtimestamp(alight_ts)
                line_hour[(date, alight_dt.hour, line_id, "alight")] += 1
                station_hour[(date, alight_dt.hour, line_id, row["down_stop_id"], row["down_stop_name"], "alight")] += 1
                passenger_line_daily[(date, line_id, "alight")] += 1
                od_daily[(date, line_id, row["board_stop_id"], row["down_stop_id"])] += 1
                for seq in range(int(row["board_seq"]), int(row["down_seq"])):
                    segment_hour[(date, hour, line_id, seq)] += 1
            rider_day = (row["rider_id"], date)
            previous = previous_by_rider_day.get(rider_day)
            if previous and previous["resolved"] and previous["alight_ts"] is not None:
                transfer_min = (board_ts - int(previous["alight_ts"])) / 60
                if (
                    0 <= transfer_min <= MAX_TRANSFER_MINUTES
                    and previous["line_id"] != line_id
                ):
                    walk = haversine_m(
                        float(previous["down_lon"]), float(previous["down_lat"]),
                        float(row["board_lon"]), float(row["board_lat"]),
                    )
                    if walk <= MAX_PROJECTION_METERS:
                        transfer_writer.writerow({
                            "rider_id": row["rider_id"], "service_date": date,
                            "from_trip_id": previous["trip_id"], "to_trip_id": row["trip_id"],
                            "from_line_id": previous["line_id"], "to_line_id": line_id,
                            "alight_stop_id": previous["down_stop_id"],
                            "next_board_stop_id": row["board_stop_id"],
                            "alight_time": timestamp_text(previous["alight_ts"]),
                            "next_board_time": timestamp_text(board_ts),
                            "transfer_minutes": round_text(transfer_min),
                            "walk_distance_m": round_text(walk),
                        })
                        transfers += 1
            previous_by_rider_day[rider_day] = {
                "trip_id": row["trip_id"], "line_id": line_id, "resolved": resolved,
                "alight_ts": alight_ts, "down_stop_id": row["down_stop_id"],
                "down_lon": row["down_lon"], "down_lat": row["down_lat"],
            }
            if processed % 500_000 == 0:
                log(f"写出乘客行程 {processed:,} 行")
    finally:
        trip_handle.close()
        transfer_handle.close()
    quality["transfer_rows"] = transfers

    line_fields = ["service_date", "hour", "authority_line_id", "authority_route_name", "boarding_count", "alighting_count", "trip_count"]
    handle, writer = open_csv_writer(staging / "线路小时客流.csv", line_fields)
    try:
        keys = {(d, h, line) for d, h, line, _ in line_hour}
        for date, hour, line_id in sorted(keys):
            board = line_hour[(date, hour, line_id, "board")]
            alight = line_hour[(date, hour, line_id, "alight")]
            writer.writerow({"service_date": date, "hour": hour, "authority_line_id": line_id,
                             "authority_route_name": routes[line_id].name, "boarding_count": board,
                             "alighting_count": alight, "trip_count": board})
    finally:
        handle.close()
    station_fields = ["service_date", "hour", "authority_line_id", "authority_route_name", "stop_id", "stop_name", "boarding_count", "alighting_count"]
    handle, writer = open_csv_writer(staging / "站点小时客流.csv", station_fields)
    try:
        keys = {(d, h, line, sid, name) for d, h, line, sid, name, _ in station_hour}
        for date, hour, line_id, stop_id, stop_name in sorted(keys):
            writer.writerow({"service_date": date, "hour": hour, "authority_line_id": line_id,
                             "authority_route_name": routes[line_id].name, "stop_id": stop_id,
                             "stop_name": stop_name,
                             "boarding_count": station_hour[(date, hour, line_id, stop_id, stop_name, "board")],
                             "alighting_count": station_hour[(date, hour, line_id, stop_id, stop_name, "alight")]})
    finally:
        handle.close()
    segment_fields = ["service_date", "hour", "authority_line_id", "authority_route_name", "from_stop_id", "from_stop_name", "to_stop_id", "to_stop_name", "from_seq", "passenger_count"]
    handle, writer = open_csv_writer(staging / "断面小时客流.csv", segment_fields)
    try:
        for (date, hour, line_id, seq), count in sorted(segment_hour.items()):
            from_stop = stop_by_line_seq.get((line_id, seq))
            to_stop = stop_by_line_seq.get((line_id, seq + 1))
            if not from_stop or not to_stop:
                continue
            writer.writerow({"service_date": date, "hour": hour, "authority_line_id": line_id,
                             "authority_route_name": routes[line_id].name,
                             "from_stop_id": from_stop.stop_id, "from_stop_name": from_stop.name,
                             "to_stop_id": to_stop.stop_id, "to_stop_name": to_stop.name,
                             "from_seq": seq, "passenger_count": count})
    finally:
        handle.close()
    od_fields = ["service_date", "authority_line_id", "authority_route_name", "board_stop_id", "board_stop_name", "alight_stop_id", "alight_stop_name", "trip_count"]
    handle, writer = open_csv_writer(staging / "线路OD日统计.csv", od_fields)
    try:
        stop_names = {stop.stop_id: stop.name for stops in stops_by_line.values() for stop in stops}
        for (date, line_id, board_id, down_id), count in sorted(od_daily.items()):
            writer.writerow({"service_date": date, "authority_line_id": line_id,
                             "authority_route_name": routes[line_id].name,
                             "board_stop_id": board_id, "board_stop_name": stop_names.get(board_id, ""),
                             "alight_stop_id": down_id, "alight_stop_name": stop_names.get(down_id, ""),
                             "trip_count": count})
    finally:
        handle.close()
    profile_fields = ["dimension_type", "dimension_id", "service_date", "hour", "passenger_group", "boarding_count"]
    handle, writer = open_csv_writer(staging / "客群小时统计.csv", profile_fields)
    try:
        for key, count in sorted(profile_hour.items()):
            writer.writerow(dict(zip(profile_fields, (*key, count))))
    finally:
        handle.close()
    return passenger_line_daily, {
        "line_hour_rows": len({(d, h, line) for d, h, line, _ in line_hour}),
        "station_hour_rows": len({(d, h, line, sid) for d, h, line, sid, _, _ in station_hour}),
        "segment_hour_rows": len(segment_hour), "od_daily_rows": len(od_daily),
        "profile_hour_rows": len(profile_hour),
    }


def write_route_mapping(staging: Path, rows: list[dict[str, object]]) -> None:
    handle, writer = open_csv_writer(staging / "线路映射.csv", ROUTE_MAPPING_FIELDS)
    try:
        writer.writerows(rows)
    finally:
        handle.close()


def write_dictionary(staging: Path) -> None:
    descriptions = {
        "trip_id": "清洗后行程唯一标识", "rider_id": "原卡号经 HMAC-SHA256 化名后的稳定乘客标识",
        "service_date": "运营日期", "is_weekday": "周一至周五为1，否则为0",
        "authority_line_id": "当前真实线路 SHP 的 line_id，线路方向唯一标识",
        "authority_route_id": "当前真实线路 SHP 的 route_id", "authority_route_name": "当前真实线路 SHP 的线路全名",
        "board_stop_id": "当前真实站点/站序中的上车 stop_id", "alight_stop_id": "推断或直接记录的下车 stop_id",
        "board_time": "刷卡上车时间", "alight_time": "RUN 实测或区间均值估算的下车时间",
        "board_source": "上车站来源：卡记录或3分钟内RUN匹配", "alight_source": "下车站推断来源",
        "alight_time_source": "下车时间来源：vehicle_run/segment_mean/repair",
        "passenger_group": "仅由票卡类型可识别的客群，不代表完整人口画像",
        "payment_medium": "支付介质类别", "projection_distance_m": "换乘链候选点投影到本线路下游站的距离",
        "clean_status": "valid_complete 或 valid_boarding_only", "mapping_status": "权威线路映射状态",
        "passenger_count": "该线路断面在运营日/小时内的推断乘客数",
        "trip_count": "行程数", "boarding_count": "上车人次", "alighting_count": "已解析下车人次",
    }
    file_fields = {
        "乘客行程明细.csv": PASSENGER_FIELDS,
        "车辆到离站明细.csv": VEHICLE_EVENT_FIELDS,
        "线路映射.csv": ROUTE_MAPPING_FIELDS,
        "换乘明细.csv": TRANSFER_FIELDS,
        "线路小时客流.csv": ["service_date", "hour", "authority_line_id", "authority_route_name", "boarding_count", "alighting_count", "trip_count"],
        "站点小时客流.csv": ["service_date", "hour", "authority_line_id", "authority_route_name", "stop_id", "stop_name", "boarding_count", "alighting_count"],
        "断面小时客流.csv": ["service_date", "hour", "authority_line_id", "authority_route_name", "from_stop_id", "from_stop_name", "to_stop_id", "to_stop_name", "from_seq", "passenger_count"],
        "线路OD日统计.csv": ["service_date", "authority_line_id", "authority_route_name", "board_stop_id", "board_stop_name", "alight_stop_id", "alight_stop_name", "trip_count"],
        "客群小时统计.csv": ["dimension_type", "dimension_id", "service_date", "hour", "passenger_group", "boarding_count"],
        "车辆日运营统计.csv": ["service_date", "plate_number", "authority_line_id", "authority_route_name", "stop_event_count", "trip_start_count", "first_event_time", "last_event_time", "operating_hours", "mileage_km", "run_time_min", "avg_speed_kmh"],
        "线路日运营统计.csv": ["service_date", "authority_line_id", "authority_route_name", "vehicle_count", "stop_event_count", "trip_start_count", "first_event_time", "last_event_time", "mileage_km", "run_time_min", "avg_speed_kmh", "boarding_count", "resolved_alighting_count"],
        "区间运行时间统计.csv": ["authority_line_id", "authority_route_name", "from_stop_id", "from_stop_name", "to_stop_id", "to_stop_name", "from_seq", "sample_count", "avg_run_time_min", "avg_mileage_m"],
        "总体小时客流.csv": ["service_date", "hour", "all_swipe_count", "fare_amount_yuan", "student_count", "elderly_count", "concession_count", "general_or_unknown_count"],
        "模块可用性说明.csv": ["platform_module", "left_panel_module", "availability", "primary_data", "available_statistics", "known_limitations"],
    }
    fields = ["file_name", "field_name", "data_type", "description"]
    handle, writer = open_csv_writer(staging / "数据字典.csv", fields)
    try:
        for file_name, columns in file_fields.items():
            for field in columns:
                if field.endswith("_count") or field in {"hour", "is_weekday", "is_resolved", "board_stop_seq", "alight_stop_seq", "from_seq"}:
                    data_type = "integer"
                elif field.endswith(("_lon", "_lat", "_yuan", "_min", "_km", "_kmh", "_m")):
                    data_type = "decimal"
                elif field.endswith("_time"):
                    data_type = "datetime"
                elif field == "service_date":
                    data_type = "date"
                else:
                    data_type = "string"
                writer.writerow({"file_name": file_name, "field_name": field, "data_type": data_type,
                                 "description": descriptions.get(field, "按字段名定义，标识字段均来自权威SHP/站序或清洗后的原始数据")})
    finally:
        handle.close()


def write_quality_report(
    staging: Path, quality: collections.Counter[str], route_rows: list[dict[str, object]],
    extra: dict[str, object], started: float,
) -> None:
    mapped_route_events = sum(int(row["raw_event_count"]) for row in route_rows if row["mapping_status"] == "mapped")
    raw_route_events = sum(int(row["raw_event_count"]) for row in route_rows)
    metrics: list[tuple[str, object, str, str]] = [
        ("generated_at", dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "datetime", "生成时间"),
        ("processing_seconds", round(time.time() - started, 2), "seconds", "全流程耗时"),
        ("run_raw_rows", quality["run_raw_rows"], "rows", "RUN原始行数"),
        ("run_route_mapped_rows", mapped_route_events, "rows", "线路方向映射到当前权威SHP的RUN行数"),
        ("run_route_mapping_rate", round(mapped_route_events / raw_route_events, 6) if raw_route_events else 0, "ratio", "RUN权威线路映射率"),
        ("run_valid_event_rows", quality["run_valid_event_rows"], "rows", "同时映射到权威线路和站点且到站时间有效的RUN行数"),
        ("run_unmapped_route_rows", quality["run_unmapped_route_rows"], "rows", "因线路未映射而清洗掉的RUN行数"),
        ("run_unmapped_stop_rows", quality["run_unmapped_stop_rows"], "rows", "因站点不在当前权威站序而清洗掉的RUN行数"),
        ("card_raw_rows", quality["card_raw_rows"], "rows", "CARD原始行数"),
        ("card_valid_boarding_rows", quality["card_valid_boarding_rows"], "rows", "映射到当前权威线路/站点的有效上车行程"),
        ("card_valid_boarding_rate", round(quality["card_valid_boarding_rows"] / quality["card_raw_rows"], 6) if quality["card_raw_rows"] else 0, "ratio", "CARD有效上车率"),
        ("card_board_recovered_by_run_rows", quality["card_board_recovered_by_run_rows"], "rows", "由3分钟RUN匹配恢复的上车站"),
        ("card_unmapped_route_rows", quality["card_unmapped_route_rows"], "rows", "权威线路无法确定的CARD记录"),
        ("card_unmapped_board_stop_rows", quality["card_unmapped_board_stop_rows"], "rows", "权威上车站无法确定的CARD记录"),
        ("alight_resolved_rows", quality["alight_resolved_rows"], "rows", "已获得下车站的行程"),
        ("alight_resolution_rate", round(quality["alight_resolved_rows"] / quality["card_valid_boarding_rows"], 6) if quality["card_valid_boarding_rows"] else 0, "ratio", "有效上车行程的下车解析率"),
        ("alight_direct_card_down_rows", quality["alight_direct_card_down_rows"], "rows", "CARD直接下车站"),
        ("alight_next_board_projection_rows", quality["alight_next_board_projection_rows"], "rows", "同日下一次上车链推断"),
        ("alight_daily_return_projection_rows", quality["alight_daily_return_projection_rows"], "rows", "当日往返闭环的保守推断"),
        ("alight_historical_mode_rows", quality["alight_historical_mode_rows"], "rows", "同卡同线路稳定历史模式兜底"),
        ("alight_unresolved_rows", quality["alight_unresolved_rows"], "rows", "仅可用于上车统计、不可用于OD/断面的行程"),
        ("alight_time_vehicle_run_rows", quality["alight_time_vehicle_run_rows"], "rows", "由同车同线RUN实际到站事件确定的下车时间"),
        ("alight_time_segment_mean_rows", quality["alight_time_segment_mean_rows"], "rows", "由线路区间平均运行时间估算的下车时间"),
        ("transfer_rows", quality["transfer_rows"], "rows", "有效换乘链记录"),
    ]
    for key, value in sorted(extra.items()):
        metrics.append((key, value, "rows", "生成的统计表行数"))
    handle, writer = open_csv_writer(staging / "数据质量报告.csv", ["metric", "value", "unit", "description"])
    try:
        for metric, value, unit, description in metrics:
            writer.writerow({"metric": metric, "value": value, "unit": unit, "description": description})
    finally:
        handle.close()


def validate_outputs(staging: Path, quality: collections.Counter[str]) -> dict[str, object]:
    required = [
        "乘客行程明细.csv", "车辆到离站明细.csv", "线路小时客流.csv", "站点小时客流.csv",
        "断面小时客流.csv", "线路OD日统计.csv", "换乘明细.csv", "客群小时统计.csv",
        "车辆日运营统计.csv", "线路日运营统计.csv", "线路映射.csv", "数据字典.csv",
        "区间运行时间统计.csv",
        "总体小时客流.csv",
        "模块可用性说明.csv",
    ]
    missing = [name for name in required if not (staging / name).is_file()]
    if missing:
        raise RuntimeError(f"缺少输出文件: {missing}")
    for name in required:
        if (staging / name).stat().st_size == 0:
            raise RuntimeError(f"输出为空: {name}")
    with (staging / "乘客行程明细.csv").open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != PASSENGER_FIELDS:
            raise RuntimeError("乘客行程字段不符合定义")
        sample_count = 0
        for row in reader:
            sample_count += 1
            if not row["authority_line_id"] or not row["board_stop_id"]:
                raise RuntimeError("乘客明细含空权威线路或上车站")
            if row["is_resolved"] == "1" and int(row["alight_stop_seq"]) <= int(row["board_stop_seq"]):
                raise RuntimeError("乘客明细含非下游下车站")
            if sample_count >= 100_000:
                break
    if sample_count == 0 or quality["card_valid_boarding_rows"] == 0:
        raise RuntimeError("没有生成有效乘客行程")
    return {"validation_sample_rows": sample_count, "output_file_count": len(required) + 1}


def read_or_create_hmac_key(path: Path) -> bytes:
    if path.exists():
        key = path.read_bytes()
        if len(key) < 32:
            raise RuntimeError(f"化名密钥异常: {path}")
        return key
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    key = secrets.token_bytes(32)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(key)
    return key


def check_inputs() -> None:
    for path in (CARD_CSV, RUN_CSV, ROUTES_SHP, STOPS_SHP, SEQUENCE_CSV):
        if not path.exists():
            raise FileNotFoundError(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args()
    check_inputs()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError(f"输出目录已存在，为避免覆盖请先确认后再处理: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.build-", dir=output.parent))
    database_path = staging / ".working.sqlite"
    key_path = output.parent / ".客流数据_hmac.key"
    started = time.time()
    quality: collections.Counter[str] = collections.Counter()
    connection: sqlite3.Connection | None = None
    try:
        log("读取当前权威线路、站点 SHP 与站序")
        routes, stops_by_line, _ = load_authority()
        log(f"权威线路方向 {len(routes):,} 条，有站序线路 {len(stops_by_line):,} 条")
        log("第一次扫描 RUN，建立原始线路方向画像")
        groups = scan_run_groups()
        route_mapping, route_rows = build_route_mapping(groups, routes, stops_by_line)
        mapped_groups = sum(1 for value in route_mapping.values() if isinstance(value["route"], Route))
        log(f"原始线路方向组映射 {mapped_groups}/{len(groups)}")
        write_route_mapping(staging, route_rows)
        stop_mapper = StopMapper(stops_by_line)
        connection = create_database(database_path)
        log("第二次扫描 RUN，生成权威到离站明细与运营统计")
        segment_stats, vehicle_daily, line_daily = process_run(
            connection, staging, routes, route_mapping, stop_mapper, quality
        )
        card_route_indexes = build_card_route_indexes(groups, route_mapping)
        hmac_key = read_or_create_hmac_key(key_path)
        log("清洗 CARD，并以 RUN 在3分钟窗口内补充缺失上车站")
        process_card_boardings(
            connection, hmac_key, routes, card_route_indexes, stop_mapper, quality
        )
        log("按同卡同日乘车链推断下车站")
        infer_alighting(connection, stop_mapper, quality)
        log("用同车同线 RUN 到站事件匹配下车时间")
        attach_alight_times(connection, quality)
        log("生成乘客明细、OD、断面、站点、线路、客群与换乘统计")
        passenger_line_daily, extra = write_passenger_outputs(
            connection, staging, routes, stops_by_line, segment_stats, quality
        )
        write_operations(staging, routes, vehicle_daily, line_daily, passenger_line_daily)
        extra["segment_runtime_rows"] = write_segment_runtime(
            staging, routes, stops_by_line, segment_stats
        )
        extra["overall_hour_rows"] = write_overall_flow(staging)
        extra["module_availability_rows"] = write_module_availability(staging)
        write_dictionary(staging)
        validation = validate_outputs(staging, quality)
        extra.update(validation)
        write_quality_report(staging, quality, route_rows, extra, started)
        connection.close()
        connection = None
        database_path.unlink(missing_ok=True)
        os.replace(staging, output)
        log(f"完成：{output}")
        return 0
    except Exception:
        if connection is not None:
            connection.close()
        log(f"处理失败，保留临时目录以便排查：{staging}")
        raise


if __name__ == "__main__":
    sys.exit(main())
