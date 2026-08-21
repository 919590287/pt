#!/usr/bin/env python3
"""Build exact real departure passenger-flow cache from cleaned RUN and card trips."""

from __future__ import annotations

import argparse
import bisect
import collections
import csv
import datetime as dt
import hashlib
import json
import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path


OUTPUT_NAME = "班次客流明细.csv"
QUALITY_NAME = "班次客流匹配质量.csv"
MAX_TRIP_SECONDS = 6 * 3600
ORIGIN_PREBOARD_SECONDS = 20 * 60
START_DEDUPE_SECONDS = 5 * 60
FIELDS = [
    "service_date", "authority_line_id", "departure_id", "departure_time",
    "plate_number", "boarding_count", "resolved_alighting_count",
    "boardings_by_seq", "alightings_by_seq", "segment_flows_by_seq",
    "passenger_groups", "match_method",
]


def clean(value: object) -> str:
    return "" if value is None else str(value).strip()


def parse_datetime(value: object) -> dt.datetime | None:
    text = clean(value)
    if not text:
        return None
    try:
        return dt.datetime.strptime(text[:19], "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return None


def epoch(value: object) -> int | None:
    parsed = parse_datetime(value)
    return int(parsed.timestamp()) if parsed is not None else None


def integer(value: object) -> int | None:
    try:
        return int(float(clean(value)))
    except (TypeError, ValueError):
        return None


def compact_counts(values: collections.Counter[int] | collections.Counter[str]) -> str:
    return json.dumps(
        {str(key): count for key, count in sorted(values.items(), key=lambda item: str(item[0])) if count},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def departure_id(service_date: str, line_id: str, plate: str, start_ts: int) -> str:
    raw = f"{service_date}\x1f{line_id}\x1f{plate}\x1f{start_ts}"
    return "real-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


@dataclass
class Departure:
    service_date: str
    line_id: str
    plate: str
    start_ts: int
    id: str
    end_ts: int = 0
    boardings: collections.Counter[int] = field(default_factory=collections.Counter)
    alightings: collections.Counter[int] = field(default_factory=collections.Counter)
    segments: collections.Counter[int] = field(default_factory=collections.Counter)
    groups: collections.Counter[str] = field(default_factory=collections.Counter)
    matched: int = 0
    resolved: int = 0


def load_origin_sequences(authority: Path) -> dict[str, int]:
    path = authority / "站点/line_stop_sequence.csv"
    origins: dict[str, int] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            line_id = clean(row.get("line_id"))
            seq = integer(row.get("seq"))
            if line_id and seq is not None:
                origins[line_id] = min(origins.get(line_id, seq), seq)
    return origins


def load_departures(
    aggregate: Path, origins: dict[str, int]
) -> tuple[dict[tuple[str, str, str], list[Departure]], collections.Counter[str]]:
    starts: dict[tuple[str, str, str], list[int]] = collections.defaultdict(list)
    quality: collections.Counter[str] = collections.Counter()
    path = aggregate / "车辆到离站明细.csv"
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            quality["run_rows"] += 1
            date = clean(row.get("service_date"))
            line_id = clean(row.get("authority_line_id"))
            plate = clean(row.get("plate_number"))
            seq = integer(row.get("stop_seq"))
            origin_seq = origins.get(line_id)
            if not date or not line_id or not plate or seq is None or origin_seq is None or seq != origin_seq:
                continue
            start_ts = epoch(row.get("leave_time")) or epoch(row.get("arrival_time"))
            if start_ts is None:
                continue
            starts[(date, plate, line_id)].append(start_ts)
            quality["origin_events"] += 1

    result: dict[tuple[str, str, str], list[Departure]] = {}
    for key, values in starts.items():
        deduped: list[int] = []
        for value in sorted(values):
            if not deduped or value - deduped[-1] > START_DEDUPE_SECONDS:
                deduped.append(value)
            else:
                quality["duplicate_origin_events"] += 1
        date, plate, line_id = key
        result[key] = [
            Departure(date, line_id, plate, value, departure_id(date, line_id, plate, value))
            for value in deduped
        ]
        quality["departures"] += len(deduped)
    by_vehicle: dict[tuple[str, str], list[Departure]] = collections.defaultdict(list)
    for values in result.values():
        for departure in values:
            by_vehicle[(departure.service_date, departure.plate)].append(departure)
    for values in by_vehicle.values():
        values.sort(key=lambda item: item.start_ts)
        for index, departure in enumerate(values):
            next_index = index + 1
            while next_index < len(values) and values[next_index].start_ts - departure.start_ts <= START_DEDUPE_SECONDS:
                next_index += 1
            departure.end_ts = values[next_index].start_ts if next_index < len(values) else departure.start_ts + MAX_TRIP_SECONDS
    return result, quality


def match_departure(
    departures: list[Departure], board_ts: int, board_seq: int, origin_seq: int
) -> Departure | None:
    if not departures:
        return None
    start_times = [item.start_ts for item in departures]
    previous_index = bisect.bisect_right(start_times, board_ts) - 1
    next_index = previous_index + 1
    # 首站乘客可能在车辆离站前刷卡，应优先归入即将发出的同车班次。
    if board_seq == origin_seq and 0 <= next_index < len(departures):
        upcoming = departures[next_index]
        if 0 <= upcoming.start_ts - board_ts <= ORIGIN_PREBOARD_SECONDS:
            return upcoming
    if previous_index < 0:
        first = departures[0]
        if board_seq == origin_seq and 0 <= first.start_ts - board_ts <= ORIGIN_PREBOARD_SECONDS:
            return first
        return None
    candidate = departures[previous_index]
    if board_ts - candidate.start_ts > MAX_TRIP_SECONDS or board_ts >= candidate.end_ts:
        return None
    return candidate


def assign_passengers(
    aggregate: Path,
    origins: dict[str, int],
    by_vehicle_route: dict[tuple[str, str, str], list[Departure]],
    quality: collections.Counter[str],
) -> None:
    run_dates = {key[0] for key in by_vehicle_route}
    path = aggregate / "乘客行程明细.csv"
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            quality["passenger_rows"] += 1
            date = clean(row.get("service_date"))
            line_id = clean(row.get("authority_line_id"))
            plate = clean(row.get("plate_number"))
            board_seq = integer(row.get("board_stop_seq"))
            board_ts = epoch(row.get("board_time"))
            origin_seq = origins.get(line_id)
            if date not in run_dates:
                quality["passenger_rows_without_run_date"] += 1
                continue
            quality["passenger_rows_on_run_dates"] += 1
            if not date or not line_id or not plate or board_seq is None or board_ts is None or origin_seq is None:
                quality["invalid_passenger_rows"] += 1
                continue
            departure = match_departure(
                by_vehicle_route.get((date, plate, line_id), []), board_ts, board_seq, origin_seq
            )
            if departure is None:
                if (date, plate, line_id) in by_vehicle_route:
                    quality["unmatched_departure_window_rows"] += 1
                else:
                    quality["unmatched_vehicle_route_rows"] += 1
                quality["unmatched_passenger_rows"] += 1
                continue
            departure.matched += 1
            departure.boardings[board_seq] += 1
            departure.groups[clean(row.get("passenger_group")) or "general_or_unknown"] += 1
            quality["matched_passenger_rows"] += 1
            alight_seq = integer(row.get("alight_stop_seq"))
            if clean(row.get("is_resolved")) == "1" and alight_seq is not None and alight_seq > board_seq:
                departure.resolved += 1
                departure.alightings[alight_seq] += 1
                for seq in range(board_seq, alight_seq):
                    departure.segments[seq] += 1
                quality["resolved_matched_rows"] += 1


def write_outputs(
    output: Path,
    by_vehicle_route: dict[tuple[str, str, str], list[Departure]],
    quality: collections.Counter[str],
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with temporary.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=FIELDS)
            writer.writeheader()
            departures = sorted(
                (item for values in by_vehicle_route.values() for item in values),
                key=lambda item: (item.service_date, item.line_id, item.start_ts, item.plate),
            )
            for item in departures:
                writer.writerow({
                    "service_date": item.service_date,
                    "authority_line_id": item.line_id,
                    "departure_id": item.id,
                    "departure_time": dt.datetime.fromtimestamp(item.start_ts).strftime("%Y-%m-%d %H:%M:%S"),
                    "plate_number": item.plate,
                    "boarding_count": item.matched,
                    "resolved_alighting_count": item.resolved,
                    "boardings_by_seq": compact_counts(item.boardings),
                    "alightings_by_seq": compact_counts(item.alightings),
                    "segment_flows_by_seq": compact_counts(item.segments),
                    "passenger_groups": compact_counts(item.groups),
                    "match_method": "vehicle_route_departure_window_v1",
                })
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

    quality_path = output.parent / QUALITY_NAME
    passenger_rows = quality["passenger_rows"]
    comparable_rows = quality["passenger_rows_on_run_dates"]
    quality["match_rate_ppm"] = round(
        quality["matched_passenger_rows"] * 1_000_000 / passenger_rows
    ) if passenger_rows else 0
    quality["comparable_match_rate_ppm"] = round(
        quality["matched_passenger_rows"] * 1_000_000 / comparable_rows
    ) if comparable_rows else 0
    with quality_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["metric", "value"])
        writer.writeheader()
        for key, value in sorted(quality.items()):
            writer.writerow({"metric": key, "value": value})


def build(aggregate: Path, authority: Path, output: Path | None = None) -> Path:
    output = output or aggregate / OUTPUT_NAME
    origins = load_origin_sequences(authority)
    departures, quality = load_departures(aggregate, origins)
    assign_passengers(aggregate, origins, departures, quality)
    write_outputs(output, departures, quality)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aggregate", type=Path, required=True)
    parser.add_argument("--authority", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = build(args.aggregate.resolve(), args.authority.resolve(), args.output)
    print(result, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
