#!/usr/bin/env python3
"""Repair crawler-predicted bus departure minutes without losing peak trips.

The current Guangzhou route timetable was assembled from predicted departures.
For each route direction this script snaps a departure to the nearest five-minute
clock mark only when that target is unique within the route.  If two or more
departures would share the same target minute, the complete collision group is
left unchanged.  The policy therefore preserves departure counts, ordering and
all dense peak-service observations.

Dry-run is the default.  ``--apply`` creates a timestamped backup before it
atomically updates the route DBF and its readable CSV/GeoJSON mirrors.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import shutil
import struct
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

from apply_real_route_user_updates import (
    DEFAULT_DBF,
    atomic_write_bytes,
    decode_record,
    decode_timetable,
    encode_field,
    parse_dbf,
    timetable_updates,
    write_departure_csv,
    write_geojson,
)


SOURCE_ROOT = DEFAULT_DBF.parent.parent
DEFAULT_DEPARTURES_CSV = DEFAULT_DBF.parent / "routes_departures.csv"
DEFAULT_CANONICAL_CSV = SOURCE_ROOT / "timetable_cleaned.csv"


def parse_readable(value: str) -> list[int]:
    minutes: list[int] = []
    for token in value.split(";"):
        hour_text, minute_text = token.strip().split(":", 1)
        hour, minute = int(hour_text), int(minute_text)
        if hour > 30 or minute > 59:
            raise ValueError(f"invalid departure token: {token!r}")
        minutes.append(hour * 60 + minute)
    if not minutes or minutes != sorted(set(minutes)):
        raise ValueError("departures must be non-empty, unique, and increasing")
    return minutes


def format_minutes(minutes: list[int]) -> str:
    return ";".join(f"{value // 60:02d}:{value % 60:02d}" for value in minutes)


def nearest_five(value: int) -> int:
    remainder = value % 5
    return value - remainder if remainder <= 2 else value + (5 - remainder)


def repair_route(minutes: list[int]) -> tuple[list[int], dict[str, object]]:
    target_groups: dict[int, list[int]] = defaultdict(list)
    for value in minutes:
        target_groups[nearest_five(value)].append(value)

    collision_targets = {
        target: values for target, values in target_groups.items() if len(values) > 1
    }
    repaired = [
        value if nearest_five(value) in collision_targets else nearest_five(value)
        for value in minutes
    ]
    if repaired != sorted(set(repaired)):
        raise AssertionError("collision-safe repair changed ordering or uniqueness")
    if len(repaired) != len(minutes):
        raise AssertionError("collision-safe repair changed departure count")

    changed = [
        {"before": before, "after": after}
        for before, after in zip(minutes, repaired)
        if before != after
    ]
    collision_members = sum(len(values) for values in collision_targets.values())
    return repaired, {
        "changed_departures": len(changed),
        "unchanged_collision_members": collision_members,
        "collision_target_minutes": len(collision_targets),
        "avoided_duplicate_departures": sum(
            len(values) - 1 for values in collision_targets.values()
        ),
        "maximum_shift_minutes": max(
            (abs(item["after"] - item["before"]) for item in changed), default=0
        ),
        "sample_changes": changed[:8],
        "sample_collisions": [
            {"target": target, "members": values}
            for target, values in list(collision_targets.items())[:8]
        ],
    }


def load_departure_rows(path: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            line_id = str(row.get("line_id") or "").strip()
            name = str(row.get("name") or "").strip()
            if not line_id or line_id in seen_ids:
                raise ValueError(f"missing or duplicate line_id: {line_id!r}")
            seen_ids.add(line_id)
            before = parse_readable(str(row.get("departures") or ""))
            after, stats = repair_route(before)
            rows.append(
                {
                    "line_id": line_id,
                    "name": name,
                    "before": before,
                    "after": after,
                    "before_readable": format_minutes(before),
                    "after_readable": format_minutes(after),
                    "stats": stats,
                }
            )
    return rows


def load_dbf(
    path: Path,
) -> tuple[bytearray, int, int, list[dict[str, object]], list[dict[str, str]], dict[str, int]]:
    data, header_length, record_length, fields = parse_dbf(path)
    record_count = struct.unpack("<I", data[4:8])[0]
    records: list[dict[str, str]] = []
    starts: dict[str, int] = {}
    for record_index in range(record_count):
        start = header_length + record_index * record_length
        if data[start] == 0x2A:
            continue
        record = decode_record(data, start, fields)
        line_id = record["line_id"] or record.get("route_id", "")
        if not line_id or line_id in starts:
            raise ValueError(f"missing or duplicate DBF line_id: {line_id!r}")
        starts[line_id] = start
        records.append(record)
    return data, header_length, record_length, fields, records, starts


def write_readable_csv(path: Path, rows: list[dict[str, object]], canonical: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8-sig", newline="", prefix=f".{path.name}.",
        suffix=".tmp", dir=path.parent, delete=False
    ) as output:
        temp_path = Path(output.name)
        writer = csv.writer(output)
        writer.writerow(["线路名称", "发车时刻"] if canonical else ["line_id", "name", "departures"])
        for row in rows:
            if canonical:
                writer.writerow([row["name"], row["after_readable"]])
            else:
                writer.writerow([row["line_id"], row["name"], row["after_readable"]])
    temp_path.replace(path)


def create_backup(source_root: Path, dbf: Path, stamp: str) -> Path:
    backup = source_root / f"备份_预测时刻修复_{stamp}"
    backup.mkdir(parents=True, exist_ok=False)
    candidates = [
        source_root / "timetable_cleaned.csv",
        dbf,
        dbf.with_suffix(".shp"),
        dbf.with_suffix(".shx"),
        dbf.with_suffix(".cpg"),
        dbf.with_suffix(".prj"),
        dbf.parent / "routes_departures.csv",
        dbf.parent / "routes_with_departures.geojson",
    ]
    for source in candidates:
        if source.exists():
            shutil.copy2(source, backup / source.name)
    return backup


def minute_text(value: int) -> str:
    return f"{value // 60:02d}:{value % 60:02d}"


def serializable_route_report(row: dict[str, object]) -> dict[str, object]:
    stats = dict(row["stats"])
    stats["sample_changes"] = [
        {"before": minute_text(item["before"]), "after": minute_text(item["after"])}
        for item in stats["sample_changes"]
    ]
    stats["sample_collisions"] = [
        {
            "target": minute_text(item["target"]),
            "members": [minute_text(value) for value in item["members"]],
        }
        for item in stats["sample_collisions"]
    ]
    return {"line_id": row["line_id"], "name": row["name"], **stats}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dbf", type=Path, default=DEFAULT_DBF)
    parser.add_argument("--departures-csv", type=Path, default=DEFAULT_DEPARTURES_CSV)
    parser.add_argument("--canonical-csv", type=Path, default=DEFAULT_CANONICAL_CSV)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    timetable_rows = load_departure_rows(args.departures_csv)
    rows_by_id = {str(row["line_id"]): row for row in timetable_rows}
    data, _, _, fields, records, record_starts = load_dbf(args.dbf)
    field_by_name = {str(field["name"]): field for field in fields}
    required = {"line_id", "first", "last", "dep_count", "first_dep", "last_dep", "timetable"}
    missing_fields = required - field_by_name.keys()
    if missing_fields:
        raise ValueError(f"DBF missing required fields: {sorted(missing_fields)}")
    dbf_ids = {record["line_id"] or record.get("route_id", "") for record in records}
    if dbf_ids != rows_by_id.keys():
        raise ValueError(
            f"CSV/DBF route mismatch: csv_only={sorted(rows_by_id.keys() - dbf_ids)[:10]} "
            f"dbf_only={sorted(dbf_ids - rows_by_id.keys())[:10]}"
        )

    changed_routes = [row for row in timetable_rows if row["before"] != row["after"]]
    collision_routes = [
        row for row in timetable_rows if int(row["stats"]["collision_target_minutes"]) > 0
    ]
    before_mod = Counter(value % 5 for row in timetable_rows for value in row["before"])
    after_mod = Counter(value % 5 for row in timetable_rows for value in row["after"])
    report: dict[str, object] = {
        "status": "apply_pending" if args.apply else "dry_run",
        "policy": (
            "snap each departure by at most 2 minutes to its nearest five-minute mark; "
            "leave every member of a within-route target collision unchanged"
        ),
        "dbf": str(args.dbf),
        "departures_csv": str(args.departures_csv),
        "canonical_csv": str(args.canonical_csv),
        "route_directions": len(timetable_rows),
        "departures_before": sum(len(row["before"]) for row in timetable_rows),
        "departures_after": sum(len(row["after"]) for row in timetable_rows),
        "changed_routes": len(changed_routes),
        "changed_departures": sum(int(row["stats"]["changed_departures"]) for row in timetable_rows),
        "collision_routes_preserved": len(collision_routes),
        "collision_members_preserved": sum(
            int(row["stats"]["unchanged_collision_members"]) for row in timetable_rows
        ),
        "duplicate_departures_avoided": sum(
            int(row["stats"]["avoided_duplicate_departures"]) for row in timetable_rows
        ),
        "maximum_shift_minutes": max(
            int(row["stats"]["maximum_shift_minutes"]) for row in timetable_rows
        ),
        "modulo_five_before": dict(sorted(before_mod.items())),
        "modulo_five_after": dict(sorted(after_mod.items())),
        "strictly_increasing_unique_after": all(
            row["after"] == sorted(set(row["after"])) for row in timetable_rows
        ),
        "departure_count_preserved": all(
            len(row["before"]) == len(row["after"]) for row in timetable_rows
        ),
        "changed_route_samples": [serializable_route_report(row) for row in changed_routes[:30]],
        "collision_route_samples": [serializable_route_report(row) for row in collision_routes[:30]],
    }

    if args.apply:
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = create_backup(args.dbf.parent.parent, args.dbf, stamp)
        active_records: list[dict[str, str]] = []
        for record in records:
            line_id = record["line_id"] or record.get("route_id", "")
            row = rows_by_id[line_id]
            updates = timetable_updates(str(row["after_readable"]))
            record.update({key: str(value) for key, value in updates.items()})
            active_records.append(record)
            start = record_starts[line_id]
            for field_name in ("first", "last", "dep_count", "first_dep", "last_dep", "timetable"):
                field = field_by_name[field_name]
                field_start = start + int(field["offset"])
                field_end = field_start + int(field["length"])
                data[field_start:field_end] = encode_field(record[field_name], field)

        today = dt.date.today()
        data[1:4] = bytes((today.year - 1900, today.month, today.day))
        atomic_write_bytes(args.dbf, bytes(data))
        write_readable_csv(args.departures_csv, timetable_rows, canonical=False)
        write_readable_csv(args.canonical_csv, timetable_rows, canonical=True)
        # Reuse the production serializers, then verify their row/feature counts.
        write_departure_csv(args.departures_csv, active_records)
        geojson_count = write_geojson(args.dbf.with_suffix(".shp"), args.dbf.parent / "routes_with_departures.geojson")
        report.update(
            {
                "status": "pass",
                "backup": str(backup),
                "timestamp": stamp,
                "geojson_features_updated": geojson_count,
            }
        )

    report_path = args.report
    if report_path is None:
        suffix = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        report_path = args.dbf.parent.parent / f"预测时刻修复审计_{suffix}.json"
    report["report"] = str(report_path)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
