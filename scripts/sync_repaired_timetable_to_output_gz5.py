#!/usr/bin/env python3
"""Synchronize repaired real-route departures into a MATSim output package.

Departure IDs in output_gz5 retain ``<source route id>_<source index>``.  This
allows an exact update without recreating vehicles.  Bus routes are split into
peak/offpeak/night speed profiles, so departures crossing a period boundary are
moved to the matching profile.  A missing target profile is cloned from an
existing profile and its running offsets are conservatively rescaled using the
route speeds recorded in ``transitSchedule_report.json``.

Dry-run is the default.  ``--apply`` backs up every changed artifact first.
"""

from __future__ import annotations

import argparse
import copy
import csv
import datetime as dt
import gzip
import json
import math
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

from apply_real_route_user_updates import timetable_updates


DEFAULT_PACKAGE = Path("/Users/a../模型算法/shp转matsim/output_gz5")
DEFAULT_DEPARTURES = Path(
    "/Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点/线路/routes_departures.csv"
)
DOCTYPE = '<!DOCTYPE transitSchedule SYSTEM "http://www.matsim.org/files/dtd/transitSchedule_v2.dtd">'
PERIODS = ("peak", "offpeak", "night")
MODIFIED_FILES = (
    "transitSchedule.xml",
    "transitSchedule.xml.gz",
    "routes_clean.json",
    "transitSchedule_report.json",
    "timetable_match_report.json",
    "run_summary.json",
    "speed_consistency_audit.json",
    "final_quality_audit.json",
    "v0_compatibility_report.json",
    "v0_compatibility_report.md",
)


def parse_hhmm(value: str) -> int:
    hour, minute = map(int, value.split(":", 1))
    return hour * 3600 + minute * 60


def parse_hms(value: str) -> int:
    hour, minute, second = map(int, value.split(":"))
    return hour * 3600 + minute * 60 + second


def format_hms(value: int) -> str:
    return f"{value // 3600:02d}:{value % 3600 // 60:02d}:{value % 60:02d}"


def bus_period(value: int) -> str:
    clock = value % (24 * 3600)
    if clock >= 22 * 3600 or clock < 6 * 3600:
        return "night"
    if 7 * 3600 <= clock < 9 * 3600 or 17 * 3600 <= clock < 19 * 3600:
        return "peak"
    return "offpeak"


def load_source(path: Path) -> tuple[dict[str, list[int]], dict[str, dict[str, str]]]:
    departures: dict[str, list[int]] = {}
    rows: dict[str, dict[str, str]] = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            route_id = str(row["line_id"])
            values = [parse_hhmm(token) for token in str(row["departures"]).split(";")]
            if not values or values != sorted(set(values)):
                raise ValueError(f"source departures are not strictly increasing: {route_id}")
            departures[route_id] = values
            rows[route_id] = row
    return departures, rows


def profile_period(route_id: str) -> tuple[str, str]:
    if "__" not in route_id:
        return route_id, "fixed"
    base, period = route_id.rsplit("__", 1)
    return base, period


def departure_index(base: str, departure_id: str) -> int:
    match = re.fullmatch(re.escape(base) + r"_(\d+)", departure_id)
    if not match:
        raise ValueError(f"departure ID is not source-indexed: {departure_id}")
    return int(match.group(1))


def speed_details(report: dict[str, object]) -> dict[str, dict[str, object]]:
    details = report.get("bus_speed_profiles", {}).get("route_details", [])
    return {str(item["route_id"]): item for item in details}


def rescale_profile_offsets(
    route: ET.Element,
    source_speed: float,
    target_speed: float,
) -> None:
    stops = route.findall("./routeProfile/stop")
    if not stops or source_speed <= 0 or target_speed <= 0:
        raise ValueError(f"cannot rescale route profile {route.attrib.get('id')}")
    source_arrivals = [parse_hms(stop.attrib["arrivalOffset"]) for stop in stops]
    source_departures = [parse_hms(stop.attrib["departureOffset"]) for stop in stops]
    target_arrivals = [source_arrivals[0]]
    target_departures = [source_departures[0]]
    for index in range(1, len(stops)):
        running = max(1, source_arrivals[index] - source_departures[index - 1])
        # Ceil is intentionally conservative: never make a cloned target profile
        # faster than its configured speed because of integer rounding.
        target_running = max(1, math.ceil(running * source_speed / target_speed))
        arrival = target_departures[-1] + target_running
        dwell = max(0, source_departures[index] - source_arrivals[index])
        target_arrivals.append(arrival)
        target_departures.append(arrival + dwell)
    for stop, arrival, departure in zip(stops, target_arrivals, target_departures):
        stop.attrib["arrivalOffset"] = format_hms(arrival)
        stop.attrib["departureOffset"] = format_hms(departure)


def indent(element: ET.Element, level: int = 0) -> None:
    prefix = "\n" + "  " * level
    child_prefix = "\n" + "  " * (level + 1)
    if len(element):
        if not element.text or not element.text.strip():
            element.text = child_prefix
        for child in element:
            indent(child, level + 1)
            if not child.tail or not child.tail.strip():
                child.tail = child_prefix
        element[-1].tail = prefix
    elif level and (not element.tail or not element.tail.strip()):
        element.tail = prefix


def atomic_write_text(path: Path, content: str) -> None:
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", prefix=f".{path.name}.", suffix=".tmp",
        dir=path.parent, delete=False
    ) as output:
        temp = Path(output.name)
        output.write(content)
    temp.replace(path)


def atomic_write_json(path: Path, payload: object) -> None:
    atomic_write_text(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def atomic_write_schedule(path: Path, root: ET.Element) -> None:
    indent(root)
    body = ET.tostring(root, encoding="unicode", short_empty_elements=True)
    atomic_write_text(path, f'<?xml version="1.0" encoding="UTF-8"?>\n{DOCTYPE}\n{body}\n')


def atomic_gzip(source: Path, target: Path) -> None:
    with tempfile.NamedTemporaryFile(
        "wb", prefix=f".{target.name}.", suffix=".tmp", dir=target.parent, delete=False
    ) as output:
        temp = Path(output.name)
        with source.open("rb") as input_stream, gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as compressed:
            shutil.copyfileobj(input_stream, compressed)
    temp.replace(target)


def create_backup(package: Path, stamp: str) -> Path:
    backup = package / f"backup_repaired_timetable_{stamp}"
    backup.mkdir(parents=False, exist_ok=False)
    for name in MODIFIED_FILES:
        source = package / name
        if source.exists():
            shutil.copy2(source, backup / name)
    return backup


def build_profiles(
    root: ET.Element,
) -> tuple[
    dict[str, dict[str, tuple[ET.Element, ET.Element]]],
    list[tuple[str, str, ET.Element, ET.Element]],
]:
    profiles: dict[str, dict[str, tuple[ET.Element, ET.Element]]] = defaultdict(dict)
    entries: list[tuple[str, str, ET.Element, ET.Element]] = []
    for line in root.findall("./transitLine"):
        for route in line.findall("./transitRoute"):
            if (route.findtext("transportMode") or "").strip() != "bus":
                continue
            base, period = profile_period(str(route.attrib["id"]))
            if period in profiles[base]:
                raise ValueError(f"duplicate route profile: {base} {period}")
            profiles[base][period] = (line, route)
            departures = route.find("departures")
            if departures is None:
                raise ValueError(f"missing departures element: {route.attrib['id']}")
            for departure in list(departures):
                entries.append((base, period, route, departure))
    return profiles, entries


def ensure_target_profile(
    base: str,
    target_period: str,
    source_period: str,
    profiles: dict[str, dict[str, tuple[ET.Element, ET.Element]]],
    details: dict[str, dict[str, object]],
) -> tuple[ET.Element, ET.Element]:
    if target_period in profiles[base]:
        return profiles[base][target_period]
    line, source_route = profiles[base][source_period]
    detail = details[base]
    speeds = detail["profiles_kmh"]
    clone = copy.deepcopy(source_route)
    clone.attrib["id"] = f"{base}__{target_period}"
    source_speed = float(speeds[source_period])
    target_speed = float(speeds[target_period])
    rescale_profile_offsets(clone, source_speed, target_speed)
    clone_departures = clone.find("departures")
    if clone_departures is None:
        raise ValueError(f"cloned route missing departures: {base}")
    clone_departures.clear()
    line.append(clone)
    profiles[base][target_period] = (line, clone)
    return line, clone


def sync_schedule(
    schedule_path: Path,
    source: dict[str, list[int]],
    report: dict[str, object],
    apply: bool,
) -> tuple[ET.Element, dict[str, object]]:
    root = ET.parse(schedule_path).getroot()
    profiles, entries = build_profiles(root)
    details = speed_details(report)
    scheduled_routes = set(profiles)
    if not scheduled_routes <= source.keys():
        raise ValueError(f"schedule routes absent from source: {sorted(scheduled_routes - source.keys())[:10]}")

    seen: set[tuple[str, int]] = set()
    planned: list[tuple[str, int, str, str, ET.Element, ET.Element, int, int]] = []
    changed = 0
    crossing = 0
    missing_targets = 0
    for base, current_period, route, departure in entries:
        index = departure_index(base, str(departure.attrib["id"]))
        key = (base, index)
        if key in seen:
            raise ValueError(f"duplicate source-indexed departure: {key}")
        seen.add(key)
        if index < 1 or index > len(source[base]):
            raise ValueError(f"departure index outside source timetable: {key}")
        before = parse_hms(str(departure.attrib["departureTime"]))
        after = source[base][index - 1]
        target_period = bus_period(after)
        changed += before != after
        crossing += current_period != target_period
        if current_period != target_period and target_period not in profiles[base]:
            missing_targets += 1
        planned.append(
            (base, index, current_period, target_period, route, departure, before, after)
        )

    expected_keys = {
        (base, index)
        for base in scheduled_routes
        for index in range(1, len(source[base]) + 1)
    }
    if seen != expected_keys:
        raise ValueError(
            f"schedule/source departure mismatch: missing={len(expected_keys - seen)} "
            f"extra={len(seen - expected_keys)}"
        )

    result: dict[str, object] = {
        "bus_source_routes": len(scheduled_routes),
        "bus_departures": len(planned),
        "departure_times_changed": changed,
        "period_crossings": crossing,
        "missing_target_profiles_to_create": missing_targets,
        "departure_ids_preserved": True,
        "vehicle_refs_preserved": True,
    }
    if not apply:
        return root, result

    created: set[tuple[str, str]] = set()
    for base, _index, current_period, target_period, _route, _departure, _before, _after in planned:
        if current_period == target_period or target_period in profiles[base]:
            continue
        ensure_target_profile(base, target_period, current_period, profiles, details)
        created.add((base, target_period))

    for base, _index, current_period, target_period, route, departure, _before, after in planned:
        departure.attrib["departureTime"] = format_hms(after)
        if current_period == target_period:
            continue
        source_departures = route.find("departures")
        target_route = profiles[base][target_period][1]
        target_departures = target_route.find("departures")
        if source_departures is None or target_departures is None:
            raise ValueError(f"missing profile departures during move: {base}")
        source_departures.remove(departure)
        target_departures.append(departure)

    removed: set[tuple[str, str]] = set()
    for base, by_period in list(profiles.items()):
        for period, (line, route) in list(by_period.items()):
            departures = route.find("departures")
            if departures is None:
                raise ValueError(f"missing departures after sync: {base} {period}")
            if len(departures) == 0:
                line.remove(route)
                del by_period[period]
                removed.add((base, period))
                continue
            departures[:] = sorted(
                list(departures),
                key=lambda item: departure_index(base, str(item.attrib["id"])),
            )

    # Full postcondition: IDs map exactly to the repaired source and each bus
    # departure resides in the correct speed-period profile.
    final_profiles, final_entries = build_profiles(root)
    final_seen: set[tuple[str, int]] = set()
    for base, period, _route, departure in final_entries:
        index = departure_index(base, str(departure.attrib["id"]))
        final_seen.add((base, index))
        expected = source[base][index - 1]
        if parse_hms(str(departure.attrib["departureTime"])) != expected:
            raise AssertionError(f"post-sync timetable mismatch: {base} {index}")
        if period != bus_period(expected):
            raise AssertionError(f"post-sync period mismatch: {base} {index} {period}")
    if final_seen != expected_keys:
        raise AssertionError("post-sync departure identity mismatch")

    result.update(
        {
            "profiles_created": len(created),
            "profiles_removed_after_becoming_empty": len(removed),
            "bus_profiles_after": sum(len(value) for value in final_profiles.values()),
            "postconditions_passed": True,
        }
    )
    return root, result


def update_routes_clean(
    path: Path,
    source_rows: dict[str, dict[str, str]],
) -> dict[str, int]:
    routes = json.loads(path.read_text(encoding="utf-8"))
    changed = 0
    for route in routes:
        route_id = str(route["id"])
        readable = str(source_rows[route_id]["departures"])
        updates = timetable_updates(readable)
        values = {
            "timetable": updates["timetable"],
            "start_time": updates["first"],
            "end_time": updates["last"],
        }
        if any(str(route.get(key) or "") != str(value) for key, value in values.items()):
            changed += 1
        route.update(values)
    atomic_write_json(path, routes)
    return {"routes": len(routes), "routes_changed": changed}


def summarize_final_schedule(root: ET.Element) -> dict[str, object]:
    bus_departures: Counter[str] = Counter()
    bus_profiles: Counter[str] = Counter()
    bus_routes: set[str] = set()
    max_departure = 0
    max_arrival = 0
    all_routes = 0
    mode_profiles: Counter[str] = Counter()
    mode_departures: Counter[str] = Counter()
    for route in root.findall(".//transitRoute"):
        all_routes += 1
        mode = (route.findtext("transportMode") or "").strip()
        mode_profiles[mode] += 1
        departures = route.findall("./departures/departure")
        mode_departures[mode] += len(departures)
        values = [parse_hms(item.attrib["departureTime"]) for item in departures]
        if values:
            max_departure = max(max_departure, max(values))
            stops = route.findall("./routeProfile/stop")
            arrival_offset = parse_hms(stops[-1].attrib["arrivalOffset"]) if stops else 0
            max_arrival = max(max_arrival, max(values) + arrival_offset)
        if mode == "bus":
            base, period = profile_period(str(route.attrib["id"]))
            bus_routes.add(base)
            bus_profiles[period] += 1
            bus_departures[period] += len(departures)
    return {
        "all_transit_routes": all_routes,
        "mode_profiles": dict(mode_profiles),
        "mode_departures": dict(mode_departures),
        "bus_source_routes": len(bus_routes),
        "bus_period_profiles": dict(bus_profiles),
        "bus_period_departures": dict(bus_departures),
        "max_departure_time_s": max_departure,
        "max_scheduled_arrival_time_s": max_arrival,
    }


def update_reports(
    package: Path,
    schedule_report: dict[str, object],
    summary: dict[str, object],
    routes_clean: list[dict[str, object]],
) -> None:
    bus_profiles = schedule_report["bus_speed_profiles"]
    bus_profiles["period_departures"] = {
        period: int(summary["bus_period_departures"].get(period, 0)) for period in PERIODS
    }
    bus_profiles["period_transit_routes"] = {
        period: int(summary["bus_period_profiles"].get(period, 0)) for period in PERIODS
    }
    actual_periods: dict[str, list[str]] = defaultdict(list)
    root = ET.parse(package / "transitSchedule.xml").getroot()
    for route in root.findall(".//transitRoute"):
        if (route.findtext("transportMode") or "").strip() != "bus":
            continue
        base, period = profile_period(str(route.attrib["id"]))
        actual_periods[base].append(period)
    for item in bus_profiles.get("route_details", []):
        route_id = str(item["route_id"])
        item["periods_written"] = [period for period in PERIODS if period in actual_periods[route_id]]
    schedule_report["transit_routes_written"] = int(summary["mode_profiles"].get("bus", 0))
    schedule_report["max_departure_time_s"] = int(summary["max_departure_time_s"])
    schedule_report["max_scheduled_arrival_time_s"] = int(summary["max_scheduled_arrival_time_s"])
    schedule_report["recommended_simulation_end_time_s"] = int(
        math.ceil((int(summary["max_scheduled_arrival_time_s"]) + 3600) / 3600) * 3600
    )
    attributes = schedule_report.get("transit_route_attributes") or {}
    if "bus_transit_routes_written" in attributes:
        attributes["bus_transit_routes_written"] = int(summary["mode_profiles"].get("bus", 0))
    atomic_write_json(package / "transitSchedule_report.json", schedule_report)

    timetable_report = json.loads((package / "timetable_match_report.json").read_text(encoding="utf-8"))
    timetable_report.update(
        {
            "timetable_entries": len(routes_clean),
            "routes_total": len(routes_clean),
            "routes_matched": len(routes_clean),
            "encoded_v1z_rows": sum(str(route.get("timetable") or "").startswith("v1z:") for route in routes_clean),
            "plain_rows": sum(not str(route.get("timetable") or "").startswith("v1z:") for route in routes_clean),
            "decoded_departures": sum(
                len(str(route.get("_readable_departures") or "").split(";")) for route in routes_clean
            ),
        }
    )
    atomic_write_json(package / "timetable_match_report.json", timetable_report)

    run_summary_path = package / "run_summary.json"
    run_summary = json.loads(run_summary_path.read_text(encoding="utf-8"))
    run_summary["timetable"] = timetable_report
    run_summary["schedule_summary"] = schedule_report
    atomic_write_json(run_summary_path, run_summary)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--departures", type=Path, default=DEFAULT_DEPARTURES)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    package = args.package.resolve()
    source, source_rows = load_source(args.departures)
    schedule_path = package / "transitSchedule.xml"
    schedule_report_path = package / "transitSchedule_report.json"
    schedule_report = json.loads(schedule_report_path.read_text(encoding="utf-8"))
    root, sync_result = sync_schedule(schedule_path, source, schedule_report, args.apply)
    audit: dict[str, object] = {
        "status": "dry_run" if not args.apply else "apply_pending",
        "package": str(package),
        "source": str(args.departures),
        **sync_result,
    }

    if args.apply:
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = create_backup(package, stamp)
        atomic_write_schedule(schedule_path, root)
        atomic_gzip(schedule_path, package / "transitSchedule.xml.gz")
        routes_result = update_routes_clean(package / "routes_clean.json", source_rows)
        routes_clean = json.loads((package / "routes_clean.json").read_text(encoding="utf-8"))
        for route in routes_clean:
            route["_readable_departures"] = source_rows[str(route["id"])]["departures"]
        final_summary = summarize_final_schedule(root)
        update_reports(package, schedule_report, final_summary, routes_clean)
        audit.update(
            {
                "status": "pass",
                "backup": str(backup),
                "timestamp": stamp,
                "routes_clean": routes_result,
                "final_schedule": final_summary,
            }
        )

    report_path = args.report or package / (
        f"repaired_timetable_sync_{dt.datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    )
    audit["report"] = str(report_path)
    atomic_write_json(report_path, audit)
    print(json.dumps(audit, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
