#!/usr/bin/env python3
"""Backfill real-route DBF fares from the current AMap crawler cache.

The previously reviewed Nansha fare records are protected and never changed.
For every other active route, ``price`` is set to AMap ``total_price`` by exact
``line_id`` first, then by a unique normalized full route name.  Routes with no
unambiguous AMap price are left blank.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import shutil
import struct
import unicodedata
from collections import defaultdict
from pathlib import Path

from apply_real_route_user_updates import (
    FIGURE_2_PRICES,
    atomic_write_bytes,
    decode_record,
    encode_field,
    lookup_with_alias,
    parse_dbf,
)
from update_nansha_routes_from_xls import (
    canonical_route_code,
    is_nansha_label,
    route_label_and_endpoints,
)


DEFAULT_DBF = Path(
    "/Volumes/USB DISK/pt_data/广州市/真实数据/"
    "公交线路站点/线路/routes.dbf"
)
DEFAULT_AMAP_ROOT = Path("/Users/a../模型算法/高德爬虫")


def clean_scalar(value: object) -> str:
    if value is None or value == []:
        return ""
    text = str(value).strip()
    return "" if text in {"", "[]", "null", "None", "nan"} else text


def normalize_full_name(value: object) -> str:
    text = unicodedata.normalize("NFKC", clean_scalar(value))
    replacements = {
        "（": "(",
        "）": ")",
        "—": "-",
        "–": "-",
        "－": "-",
        "﹣": "-",
    }
    for source, target in replacements.items():
        text = text.replace(source, target)
    return "".join(text.split()).replace("->", "--").replace("→", "--")


def load_active_amap_routes(path: Path) -> dict[str, str]:
    routes: dict[str, str] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            line_id = clean_scalar(row.get("line_id"))
            citycode = clean_scalar(row.get("citycode")).zfill(3)
            if not line_id or citycode != "020":
                continue
            name = clean_scalar(row.get("name"))
            previous = routes.get(line_id)
            if previous is not None and previous != name:
                raise ValueError(
                    f"AMap attributes contain conflicting names for {line_id}: "
                    f"{previous!r} vs {name!r}"
                )
            routes[line_id] = name
    return routes


def load_amap_prices(
    active_routes: dict[str, str], cache_dir: Path
) -> tuple[dict[str, dict[str, str]], list[dict[str, str]]]:
    prices: dict[str, dict[str, str]] = {}
    conflicts: list[dict[str, str]] = []
    for path in sorted(cache_dir.glob("*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(f"Cannot read AMap cache {path}: {exc}") from exc
        for line in payload.get("buslines") or []:
            line_id = clean_scalar(line.get("id"))
            if line_id not in active_routes:
                continue
            if clean_scalar(line.get("citycode")).zfill(3) != "020":
                continue
            candidate = {
                "line_id": line_id,
                "name": clean_scalar(line.get("name")) or active_routes[line_id],
                "price": clean_scalar(line.get("total_price")),
                "basic_price": clean_scalar(line.get("basic_price")),
                "cache": str(path),
            }
            previous = prices.get(line_id)
            if previous and (
                previous["price"] != candidate["price"]
                or normalize_full_name(previous["name"])
                != normalize_full_name(candidate["name"])
            ):
                conflicts.append(
                    {
                        "line_id": line_id,
                        "first_name": previous["name"],
                        "first_price": previous["price"],
                        "second_name": candidate["name"],
                        "second_price": candidate["price"],
                        "first_cache": previous["cache"],
                        "second_cache": candidate["cache"],
                    }
                )
                continue
            prices[line_id] = candidate
    if conflicts:
        raise ValueError(
            "Conflicting AMap cache records found; refusing update: "
            + json.dumps(conflicts[:10], ensure_ascii=False)
        )
    return prices, conflicts


def is_reviewed_nansha_price(record: dict[str, str]) -> bool:
    label, _, _ = route_label_and_endpoints(record["name"])
    code = canonical_route_code(label)
    is_nansha = is_nansha_label(label, set()) or code == "九王庙接驳"
    return bool(is_nansha and lookup_with_alias(FIGURE_2_PRICES, code))


def read_active_records(
    data: bytearray,
    header_length: int,
    record_length: int,
    fields: list[dict[str, object]],
) -> list[dict[str, str]]:
    record_count = struct.unpack("<I", data[4:8])[0]
    records: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    for record_index in range(record_count):
        record_start = header_length + record_index * record_length
        if data[record_start] == 0x2A:
            continue
        record = decode_record(data, record_start, fields)
        line_id = record["line_id"] or record.get("route_id", "")
        if not line_id or line_id in seen_ids:
            raise ValueError(f"Missing or duplicate target line_id: {line_id!r}")
        seen_ids.add(line_id)
        record["_record_index"] = str(record_index)
        record["_record_start"] = str(record_start)
        records.append(record)
    return records


def build_name_prices(
    amap_prices: dict[str, dict[str, str]]
) -> dict[str, set[str]]:
    result: dict[str, set[str]] = defaultdict(set)
    for row in amap_prices.values():
        key = normalize_full_name(row["name"])
        if key:
            result[key].add(row["price"])
    return result


def verify_non_price_fields_unchanged(
    before: bytearray,
    after: bytearray,
    records: list[dict[str, str]],
    fields: list[dict[str, object]],
) -> None:
    for record in records:
        record_start = int(record["_record_start"])
        for field in fields:
            if field["name"] == "price":
                continue
            start = record_start + int(field["offset"])
            end = start + int(field["length"])
            if before[start:end] != after[start:end]:
                raise ValueError(
                    f"Non-price field changed for {record['line_id']}: {field['name']}"
                )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dbf", type=Path, default=DEFAULT_DBF)
    parser.add_argument("--amap-root", type=Path, default=DEFAULT_AMAP_ROOT)
    parser.add_argument("--expected-protected", type=int, default=168)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    attrs_path = args.amap_root / "gz_amap_bus_output/gz_amap_route_attributes.csv"
    cache_dir = args.amap_root / "amap_cache/detail"
    if not attrs_path.exists() or not cache_dir.is_dir():
        raise FileNotFoundError(
            f"AMap source missing: attributes={attrs_path}, cache={cache_dir}"
        )

    data, header_length, record_length, fields = parse_dbf(args.dbf)
    field_by_name = {str(field["name"]): field for field in fields}
    required = {"line_id", "name", "price"}
    missing = required - field_by_name.keys()
    if missing:
        raise ValueError(f"Target DBF missing fields: {sorted(missing)}")
    records = read_active_records(data, header_length, record_length, fields)

    active_amap_routes = load_active_amap_routes(attrs_path)
    amap_prices, conflicts = load_amap_prices(active_amap_routes, cache_dir)
    name_prices = build_name_prices(amap_prices)

    protected = [record for record in records if is_reviewed_nansha_price(record)]
    if len(protected) != args.expected_protected:
        raise ValueError(
            f"Expected {args.expected_protected} protected Nansha records, "
            f"found {len(protected)}"
        )
    protected_before = {record["line_id"]: record["price"] for record in protected}

    match_counts = {"protected": len(protected), "line_id": 0, "name": 0, "blank": 0}
    assignments: list[dict[str, str]] = []
    changes: list[dict[str, str]] = []
    for record in records:
        line_id = record["line_id"]
        if line_id in protected_before:
            assignments.append(
                {
                    "line_id": line_id,
                    "name": record["name"],
                    "match": "protected",
                    "before": record["price"],
                    "after": record["price"],
                }
            )
            continue

        source = amap_prices.get(line_id)
        if source is not None:
            desired = source["price"]
            match = "line_id" if desired else "blank"
            if desired:
                match_counts["line_id"] += 1
            else:
                match_counts["blank"] += 1
        else:
            candidates = name_prices.get(normalize_full_name(record["name"]), set())
            if len(candidates) == 1:
                desired = next(iter(candidates))
                match = "name" if desired else "blank"
                if desired:
                    match_counts["name"] += 1
                else:
                    match_counts["blank"] += 1
            else:
                desired = ""
                match = "blank"
                match_counts["blank"] += 1

        assignment = {
            "line_id": line_id,
            "name": record["name"],
            "match": match,
            "before": record["price"],
            "after": desired,
        }
        assignments.append(assignment)
        if record["price"] != desired:
            changes.append(assignment)
        record["price"] = desired

    before_bytes = bytearray(data)
    price_field = field_by_name["price"]
    for record in records:
        record_start = int(record["_record_start"])
        start = record_start + int(price_field["offset"])
        end = start + int(price_field["length"])
        data[start:end] = encode_field(record["price"], price_field)
    verify_non_price_fields_unchanged(before_bytes, data, records, fields)

    protected_after = {
        record["line_id"]: record["price"]
        for record in records
        if record["line_id"] in protected_before
    }
    if protected_after != protected_before:
        raise ValueError("A protected Nansha price changed during simulation")

    blank_rows = [row for row in assignments if row["match"] == "blank"]
    report: dict[str, object] = {
        "mode": "apply" if args.apply else "dry-run",
        "dbf": str(args.dbf),
        "amap_attributes": str(attrs_path),
        "amap_cache": str(cache_dir),
        "amap_price_field": "total_price",
        "target_records": len(records),
        "protected_nansha_records": len(protected),
        "protected_prices_unchanged": protected_after == protected_before,
        "amap_active_guangzhou_routes": len(active_amap_routes),
        "amap_cached_guangzhou_prices": len(amap_prices),
        "amap_cached_blank_prices": sum(not row["price"] for row in amap_prices.values()),
        "amap_cached_zero_prices": sum(row["price"] == "0" for row in amap_prices.values()),
        "match_counts": match_counts,
        "changed_records": len(changes),
        "blank_nonprotected_records": len(blank_rows),
        "cache_conflicts": conflicts,
        "changes": changes,
        "blank_rows": blank_rows,
        "protected_rows": [
            {
                "line_id": record["line_id"],
                "name": record["name"],
                "price": protected_before[record["line_id"]],
            }
            for record in protected
        ],
    }

    if args.apply:
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        source_root = args.dbf.parent.parent
        backup_dir = source_root / f"备份_高德票价回填_{stamp}"
        backup_dir.mkdir(parents=True, exist_ok=False)
        backup_files = []
        for source in sorted(args.dbf.parent.glob("routes.*")):
            if source.is_file():
                shutil.copy2(source, backup_dir / source.name)
                backup_files.append(source.name)

        today = dt.date.today()
        data[1:4] = bytes((today.year - 1900, today.month, today.day))
        atomic_write_bytes(args.dbf, bytes(data))

        verify_data, verify_header, verify_record_length, verify_fields = parse_dbf(args.dbf)
        verify_records = read_active_records(
            verify_data, verify_header, verify_record_length, verify_fields
        )
        verify_prices = {row["line_id"]: row["price"] for row in verify_records}
        if len(verify_records) != len(records):
            raise ValueError("Record count changed after writing DBF")
        if any(verify_prices[line_id] != price for line_id, price in protected_before.items()):
            raise ValueError("Protected Nansha price changed after writing DBF")
        if any(verify_prices[row["line_id"]] != row["after"] for row in assignments):
            raise ValueError("Written prices do not match the simulated assignments")

        import shapefile

        reader = shapefile.Reader(str(args.dbf.with_suffix(".shp")), encoding="utf-8")
        shape_count = len(reader.shapes())
        dbf_count = len(reader.records())
        reader.close()
        if shape_count != len(records) or dbf_count != len(records):
            raise ValueError(
                f"SHP/DBF count mismatch after write: shapes={shape_count}, "
                f"records={dbf_count}, expected={len(records)}"
            )

        report.update(
            {
                "timestamp": stamp,
                "backup": str(backup_dir),
                "backup_files": backup_files,
                "verified_shape_count": shape_count,
                "verified_dbf_count": dbf_count,
            }
        )
        report_path = args.report or source_root / f"高德票价回填审计_{stamp}.json"
        report["report"] = str(report_path)
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    elif args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    print(
        json.dumps(
            {
                "mode": report["mode"],
                "target_records": report["target_records"],
                "protected_nansha_records": report["protected_nansha_records"],
                "protected_prices_unchanged": report["protected_prices_unchanged"],
                "amap_cached_guangzhou_prices": report["amap_cached_guangzhou_prices"],
                "match_counts": report["match_counts"],
                "changed_records": report["changed_records"],
                "blank_nonprotected_records": report["blank_nonprotected_records"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
