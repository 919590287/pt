#!/usr/bin/env python3
"""Apply the 2026-07-16 user-supplied timetable, fare, and company updates.

The live data-management page reads ``routes.shp``/``routes.dbf`` directly.
Full departure lists are also mirrored to ``routes_departures.csv`` and the
derived GeoJSON.  Long DBF timetable values use the existing reversible v1z
encoding because dBASE character fields are limited to 254 bytes.
"""

from __future__ import annotations

import argparse
import base64
import csv
import datetime as dt
import json
import os
import shutil
import struct
import tempfile
import zlib
from collections import defaultdict
from pathlib import Path

from update_nansha_routes_from_xls import (
    DEFAULT_DBF,
    DEFAULT_XLS,
    canonical_route_code,
    clean_text,
    is_nansha_label,
    load_xls,
    route_label_and_endpoints,
)


FIGURE_1_ROUTE_IDS = {
    "440100018014",  # 南1路B：广州船坞 -> 海力花园（已有时刻）
    "440100018015",  # 南1路B：海力花园 -> 广州船坞（已有时刻）
    "440100013937",  # 南沙31路A线
    "440100012786",  # 南沙31路B线
    "440100018029",  # 南沙37路：东涌地铁站 -> 东涌湖
    "440100018028",  # 南沙37路：东涌湖 -> 东涌地铁站
    "900000146751",  # 九王庙接驳线内环
    "900000151565",  # 九王庙接驳线外环
    "900000122274",  # 南沙K6：富民商务中心 -> 蕉门
    "900000122275",  # 南沙K6：蕉门 -> 富民商务中心
}


USER_TIMETABLES = {
    "440100013937": (
        "06:00;06:20;06:30;06:40;06:50;07:00;07:10;07:20;07:30;07:40;07:45;"
        "08:00;08:15;08:30;08:40;08:50;09:00;09:10;09:20;09:40;09:59;10:00;"
        "10:20;10:40;11:00;11:20;11:40;12:00;12:20;12:40;13:00;13:20;13:40;"
        "14:00;14:20;14:40;15:00;15:20;15:40;16:00;16:20;16:35;16:50;17:05;"
        "17:20;17:35;17:50;18:10;18:25;18:40;19:00;19:20;19:40;20:00;20:20;"
        "20:40;21:00;21:20;21:40;22:00"
    ),
    "440100012786": (
        "06:00;06:20;06:35;06:50;07:05;07:20;07:40;08:00;08:20;08:40;08:55;"
        "09:05;09:30;09:55;10:15;10:35;10:55;11:15;11:35;11:55;12:10;12:30;"
        "12:50;13:10;13:30;13:50;14:10;14:30;14:50;15:10;15:20;15:30;15:40;"
        "15:45;16:00;16:10;16:25;16:40;16:50;17:00;17:10;17:25;17:55;18:20;"
        "18:30;18:50;19:00;19:20;19:40;20:00;20:20;20:40;21:00;21:20;21:40;22:00"
    ),
    "440100018029": (
        "07:00;07:20;07:30;07:40;08:00;08:20;08:30;08:45;09:00;09:20;09:40;"
        "10:00;10:20;10:40;11:00;11:20;11:45;12:00;12:30;12:40;13:00;13:30;"
        "13:40;14:05;14:15;14:30;14:40;15:00;15:10;15:30;15:40;16:00;16:15;"
        "16:30;16:45;17:00;17:15;17:30;17:45;18:00;18:15;18:30;18:45;19:00;"
        "19:20;19:40;20:00"
    ),
    "440100018028": (
        "06:30;06:45;07:00;07:20;07:30;07:40;08:00;08:20;08:30;08:40;09:00;"
        "09:20;09:40;10:00;10:20;10:40;10:55;11:20;11:40;12:00;12:30;12:40;"
        "13:00;13:35;13:40;13:55;14:10;14:25;14:40;14:55;15:10;15:30;15:40;"
        "15:55;16:10;16:25;16:40;16:55;17:10;17:25;17:40;17:55;18:10;18:25;"
        "18:40;18:55;19:10"
    ),
    # The source merge audit identifies these IDs as inner then outer loop; the
    # user supplied the two same-terminal schedules in that same order.
    "900000146751": (
        "06:30;07:00;07:30;08:00;08:30;09:00;09:30;10:30;11:30;12:30;13:30;"
        "14:30;15:30;16:30;17:00;17:30;18:00;19:05;19:30;20:30"
    ),
    "900000151565": (
        "06:00;06:45;07:15;07:45;08:15;08:45;09:45;10:00;11:00;12:00;13:00;"
        "14:00;15:00;16:00;16:45;17:15;17:45;17:50;18:15;18:45;19:45;20:45"
    ),
    "900000122275": (
        "05:30;06:15;07:00;07:45;08:30;09:15;10:00;10:45;11:30;12:15;13:00;"
        "13:45;14:30;15:15;16:00;16:45;17:30;18:15;19:00;19:45;20:30"
    ),
    "900000122274": (
        "06:45;07:30;08:15;09:00;09:45;10:30;11:15;12:00;12:45;13:30;14:15;"
        "15:00;15:45;16:30;17:25;18:05;18:50;19:35;20:20;21:05;21:45"
    ),
}


# Current full fare after the adjustment shown in Figure 2.  Combined labels
# (58A/B and W1A/B) are expanded to the route codes actually present in SHP.
FIGURE_2_PRICES = {
    "1": "3", "1B": "3", "2": "3", "2支": "2", "3": "3", "4": "2",
    "5": "3", "6": "2", "7": "2", "8": "3", "9": "2", "10": "3",
    "10短": "2", "11": "2", "12": "3", "13": "2", "14": "3", "15": "2",
    "16": "3", "17": "2", "18": "2", "20": "2", "21": "4", "21支": "2",
    "22": "1", "23": "1", "23A": "1", "23B": "1", "24": "3", "25": "3",
    "26": "2", "27": "3", "28": "3", "29": "2", "30": "2", "31": "2",
    "31支": "2", "32": "3", "33": "3", "35": "2", "36": "2", "37": "2",
    "38": "3", "39": "3", "40短": "2", "41": "3", "42": "2", "43": "2",
    "44": "2", "45": "2", "46": "2", "47": "1", "48": "3", "49": "3",
    "50": "3", "51": "2", "52": "2", "53": "3", "54": "3", "55": "3",
    "56": "3", "57": "2", "58": "2", "59": "2", "60": "2", "61": "2",
    "62": "3", "63": "2", "65": "3", "65快": "3", "67": "3", "68": "3",
    "68快": "3", "K1": "17", "K1支": "4", "K5": "3", "K6": "13",
    "K7": "4", "K8": "6", "G1": "3", "G2": "2", "G3": "3", "G4": "3",
    "G5": "2", "W1A": "2", "W1B": "2", "W1支": "2", "夜1": "3",
    "夜2": "3", "夜3": "3", "夜4": "3", "夜5": "3", "夜6": "3",
}


# Cosmetic/family differences between the current SHP labels and the two user
# sources.  Do not use a general fuzzy match here: only reviewed aliases are
# allowed to receive a fare or company.
REVIEWED_CODE_ALIASES = {
    "31A": "31",
    "31B": "31",
    "63A": "63",
    "63B": "63",
    "65大站快": "65快",
    "W1A": "W1",
    "W1B": "W1",
}


def parse_dbf(path: Path) -> tuple[bytearray, int, int, list[dict[str, object]]]:
    data = bytearray(path.read_bytes())
    record_count = struct.unpack("<I", data[4:8])[0]
    header_length = struct.unpack("<H", data[8:10])[0]
    record_length = struct.unpack("<H", data[10:12])[0]
    fields: list[dict[str, object]] = []
    descriptor_offset = 32
    value_offset = 1
    while data[descriptor_offset] != 0x0D:
        name = (
            bytes(data[descriptor_offset : descriptor_offset + 11])
            .split(b"\0", 1)[0]
            .decode("ascii")
        )
        field_type = chr(data[descriptor_offset + 11])
        length = data[descriptor_offset + 16]
        decimals = data[descriptor_offset + 17]
        fields.append(
            {
                "name": name,
                "type": field_type,
                "length": length,
                "decimals": decimals,
                "offset": value_offset,
            }
        )
        value_offset += length
        descriptor_offset += 32
    if value_offset != record_length:
        raise ValueError(
            f"DBF record length mismatch: fields={value_offset}, header={record_length}"
        )
    if header_length + record_count * record_length > len(data):
        raise ValueError("DBF is truncated")
    return data, header_length, record_length, fields


def decode_record(
    data: bytearray,
    record_start: int,
    fields: list[dict[str, object]],
) -> dict[str, str]:
    result: dict[str, str] = {}
    for field in fields:
        start = record_start + int(field["offset"])
        end = start + int(field["length"])
        result[str(field["name"])] = (
            bytes(data[start:end]).rstrip(b" \0").decode("utf-8", "replace")
        )
    return result


def encode_field(value: object, field: dict[str, object]) -> bytes:
    text = str(value)
    length = int(field["length"])
    if field["type"] in {"N", "F"}:
        encoded = text.encode("ascii")
        if len(encoded) > length:
            raise ValueError(f"value too long for {field['name']}: {text!r}")
        return encoded.rjust(length, b" ")
    encoded = text.encode("utf-8")
    if len(encoded) > length:
        raise ValueError(
            f"value too long for {field['name']}: {len(encoded)} bytes > {length}"
        )
    return encoded.ljust(length, b" ")


def _varint(number: int) -> bytes:
    if number < 0:
        raise ValueError(number)
    output = bytearray()
    while True:
        byte = number & 0x7F
        number >>= 7
        if number:
            output.append(byte | 0x80)
        else:
            output.append(byte)
            return bytes(output)


def _read_varints(payload: bytes) -> list[int]:
    values: list[int] = []
    value = 0
    shift = 0
    for byte in payload:
        value |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
            if shift > 35:
                raise ValueError("invalid timetable varint")
        else:
            values.append(value)
            value = 0
            shift = 0
    if shift:
        raise ValueError("truncated timetable varint")
    return values


def parse_timetable(readable: str) -> list[int]:
    minutes: list[int] = []
    for token in readable.split(";"):
        parts = token.strip().split(":")
        if len(parts) != 2 or not all(part.isdigit() for part in parts):
            raise ValueError(f"invalid departure token: {token!r}")
        hour, minute = map(int, parts)
        if hour > 30 or minute > 59:
            raise ValueError(f"invalid departure token: {token!r}")
        minutes.append(hour * 60 + minute)
    if not minutes or minutes != sorted(set(minutes)):
        raise ValueError("departures must be non-empty, unique, and increasing")
    return minutes


def encode_timetable(readable: str) -> str:
    minutes = parse_timetable(readable)
    if len(readable.encode("utf-8")) <= 254:
        return readable
    deltas = [minutes[0], *[right - left for left, right in zip(minutes, minutes[1:])]]
    packed = b"".join(_varint(value) for value in deltas)
    compressed = zlib.compress(packed, level=9)
    token = base64.urlsafe_b64encode(compressed).decode("ascii").rstrip("=")
    result = f"v1z:{token}"
    if len(result.encode("ascii")) > 254:
        raise ValueError(f"compressed timetable exceeds DBF limit: {len(result)}")
    if decode_timetable(result) != readable:
        raise ValueError("compressed timetable round-trip failed")
    return result


def decode_timetable(value: str) -> str:
    if not value.startswith("v1z:"):
        return value
    token = value[4:]
    token += "=" * (-len(token) % 4)
    deltas = _read_varints(zlib.decompress(base64.urlsafe_b64decode(token)))
    minutes: list[int] = []
    for index, delta in enumerate(deltas):
        minutes.append(delta if index == 0 else minutes[-1] + delta)
    return ";".join(f"{value // 60:02d}:{value % 60:02d}" for value in minutes)


def timetable_updates(readable: str) -> dict[str, object]:
    minutes = parse_timetable(readable)
    first = f"{minutes[0] // 60:02d}:{minutes[0] % 60:02d}"
    last = f"{minutes[-1] // 60:02d}:{minutes[-1] % 60:02d}"
    return {
        "first": f"{first}:00",
        "last": f"{last}:00",
        "dep_count": len(minutes),
        "first_dep": first,
        "last_dep": last,
        "timetable": encode_timetable(readable),
    }


def company_by_code(xls_path: Path) -> dict[str, str]:
    grouped: dict[str, set[str]] = defaultdict(set)
    for row in load_xls(xls_path):
        company = clean_text(row.get("company"))
        if company:
            grouped[str(row["canonical_code"])].add(company)
    conflicts = {code: sorted(values) for code, values in grouped.items() if len(values) > 1}
    if conflicts:
        raise ValueError(f"XLS company conflicts: {conflicts}")
    return {code: next(iter(values)) for code, values in grouped.items() if values}


def lookup_with_alias(mapping: dict[str, str], code: str) -> tuple[str, str] | None:
    if code in mapping:
        return code, mapping[code]
    alias = REVIEWED_CODE_ALIASES.get(code)
    if alias and alias in mapping:
        return alias, mapping[alias]
    return None


def atomic_write_bytes(path: Path, content: bytes) -> None:
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def write_departure_csv(path: Path, rows: list[dict[str, str]]) -> None:
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8-sig", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(["line_id", "name", "departures"])
            for row in rows:
                writer.writerow([row["line_id"], row["name"], decode_timetable(row["timetable"])])
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def write_geojson(shp_path: Path, path: Path) -> int:
    import shapefile

    reader = shapefile.Reader(str(shp_path), encoding="utf-8")
    features: list[dict[str, object]] = []
    for shape_record in reader.iterShapeRecords():
        properties = shape_record.record.as_dict()
        properties["departures"] = decode_timetable(str(properties.get("timetable") or ""))
        features.append(
            {
                "type": "Feature",
                "properties": properties,
                "geometry": shape_record.shape.__geo_interface__,
            }
        )
    reader.close()
    payload = {
        "type": "FeatureCollection",
        "name": "routes_with_departures",
        "crs": {
            "type": "name",
            "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"},
        },
        "features": features,
    }
    atomic_write_bytes(
        path,
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
    )
    return len(features)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dbf", type=Path, default=DEFAULT_DBF)
    parser.add_argument("--xls", type=Path, default=DEFAULT_XLS)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    data, header_length, record_length, fields = parse_dbf(args.dbf)
    field_by_name = {str(field["name"]): field for field in fields}
    required = {
        "line_id", "name", "first", "last", "price", "company",
        "dep_count", "first_dep", "last_dep", "timetable",
    }
    missing_fields = required - field_by_name.keys()
    if missing_fields:
        raise ValueError(f"DBF missing required fields: {sorted(missing_fields)}")

    companies = company_by_code(args.xls)
    record_count = struct.unpack("<I", data[4:8])[0]
    active_records: list[dict[str, str]] = []
    record_starts: dict[str, int] = {}
    target_matches: dict[str, int] = defaultdict(int)
    blank_before: list[dict[str, str]] = []
    price_matches: list[dict[str, object]] = []
    price_changes: list[dict[str, object]] = []
    company_matches: list[dict[str, object]] = []
    company_changes: list[dict[str, object]] = []
    schedule_changes: list[dict[str, object]] = []
    matched_price_codes: set[str] = set()

    for record_index in range(record_count):
        record_start = header_length + record_index * record_length
        if data[record_start] == 0x2A:
            continue
        record = decode_record(data, record_start, fields)
        record["_record_index"] = str(record_index)
        route_id = record["line_id"] or record.get("route_id", "")
        if route_id in record_starts:
            raise ValueError(f"duplicate line_id in DBF: {route_id}")
        record_starts[route_id] = record_start
        active_records.append(record)

        if not record["timetable"]:
            blank_before.append(
                {"line_id": route_id, "name": record["name"], "record_index": str(record_index)}
            )

        label, _, _ = route_label_and_endpoints(record["name"])
        code = canonical_route_code(label)
        is_nansha_route = is_nansha_label(label, set()) or code == "九王庙接驳"

        price_match = lookup_with_alias(FIGURE_2_PRICES, code) if is_nansha_route else None
        if price_match:
            source_code, price = price_match
            matched_price_codes.add(source_code)
            match_row = {
                "line_id": route_id,
                "name": record["name"],
                "shp_code": code,
                "figure_2_code": source_code,
                "price": price,
            }
            price_matches.append(match_row)
            if record["price"] != price:
                price_changes.append({**match_row, "before": record["price"], "after": price})
                record["price"] = price

        company_match = lookup_with_alias(companies, code) if is_nansha_route else None
        if company_match:
            source_code, company = company_match
            match_row = {
                "line_id": route_id,
                "name": record["name"],
                "shp_code": code,
                "xls_code": source_code,
                "company": company,
            }
            company_matches.append(match_row)
            if record["company"] != company:
                company_changes.append(
                    {**match_row, "before": record["company"], "after": company}
                )
                record["company"] = company

        if route_id in USER_TIMETABLES:
            readable = USER_TIMETABLES[route_id]
            updates = timetable_updates(readable)
            target_matches[route_id] += 1
            changes = {
                field_name: {"before": record[field_name], "after": str(value)}
                for field_name, value in updates.items()
                if record[field_name].strip() != str(value)
            }
            record.update({key: str(value) for key, value in updates.items()})
            schedule_changes.append(
                {
                    "line_id": route_id,
                    "name": record["name"],
                    "departures": len(parse_timetable(readable)),
                    "first_dep": updates["first_dep"],
                    "last_dep": updates["last_dep"],
                    "dbf_timetable_encoding": (
                        "v1z" if str(updates["timetable"]).startswith("v1z:") else "plain"
                    ),
                    "changes": changes,
                }
            )
        elif route_id in FIGURE_1_ROUTE_IDS:
            # The two South 1B directions already have their actual two-trip
            # timetables in DBF.  Recompute the derived fields from that source
            # instead of replacing them with the broader XLS service window.
            readable = decode_timetable(record["timetable"])
            if not readable:
                raise ValueError(f"Figure 1 route has no timetable: {route_id}")
            updates = timetable_updates(readable)
            target_matches[route_id] += 1
            changes = {
                field_name: {"before": record[field_name], "after": str(value)}
                for field_name, value in updates.items()
                if record[field_name].strip() != str(value)
            }
            record.update({key: str(value) for key, value in updates.items()})
            schedule_changes.append(
                {
                    "line_id": route_id,
                    "name": record["name"],
                    "departures": len(parse_timetable(readable)),
                    "first_dep": updates["first_dep"],
                    "last_dep": updates["last_dep"],
                    "dbf_timetable_encoding": (
                        "v1z" if str(updates["timetable"]).startswith("v1z:") else "plain"
                    ),
                    "changes": changes,
                    "source": "existing DBF timetable",
                }
            )

    missing_targets = sorted(route_id for route_id in FIGURE_1_ROUTE_IDS if target_matches[route_id] != 1)
    if missing_targets:
        raise ValueError(f"Figure 1 route IDs not matched exactly once: {missing_targets}")

    blank_non_figure_1 = [
        row for row in blank_before if row["line_id"] not in FIGURE_1_ROUTE_IDS
    ]
    if blank_non_figure_1:
        raise ValueError(
            "Unexpected blank-timetable routes remain outside Figure 1; "
            f"refusing attribute-only update: {blank_non_figure_1}"
        )

    blank_after = [
        {"line_id": row["line_id"], "name": row["name"]}
        for row in active_records
        if not row["timetable"]
    ]
    if blank_after:
        raise ValueError(f"blank timetables remain after simulation: {blank_after}")

    report: dict[str, object] = {
        "mode": "apply" if args.apply else "dry-run",
        "dbf": str(args.dbf),
        "xls": str(args.xls),
        "dbf_records": record_count,
        "active_records": len(active_records),
        "blank_timetable_before": len(blank_before),
        "blank_timetable_outside_figure_1_before": len(blank_non_figure_1),
        "blank_timetable_after": len(blank_after),
        "figure_1_routes_verified": len(FIGURE_1_ROUTE_IDS),
        "user_timetables_filled": len(USER_TIMETABLES),
        "schedule_updates": schedule_changes,
        "figure_2_price_codes": len(FIGURE_2_PRICES),
        "figure_2_price_records_matched": len(price_matches),
        "figure_2_price_records_changed": len(price_changes),
        "figure_2_price_codes_not_in_shp": sorted(set(FIGURE_2_PRICES) - matched_price_codes),
        "price_changes": price_changes,
        "xls_company_codes": len(companies),
        "xls_company_records_matched": len(company_matches),
        "xls_company_records_changed": len(company_changes),
        "company_changes": company_changes,
        "nine_king_temple_assignment": {
            "900000146751": "inner loop; user schedule 06:30-20:30",
            "900000151565": "outer loop; user schedule 06:00-20:45",
        },
    }

    if args.apply:
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        source_root = args.dbf.parent.parent
        backup = source_root / f"备份_线路属性更新_{stamp}"
        backup.mkdir(parents=True, exist_ok=False)
        departure_csv = args.dbf.parent / "routes_departures.csv"
        geojson = args.dbf.parent / "routes_with_departures.geojson"
        for source in (args.dbf, departure_csv, geojson):
            if source.exists():
                shutil.copy2(source, backup / source.name)

        for record in active_records:
            route_id = record["line_id"] or record.get("route_id", "")
            record_start = record_starts[route_id]
            for field_name in (
                "first", "last", "price", "company", "dep_count",
                "first_dep", "last_dep", "timetable",
            ):
                field = field_by_name[field_name]
                start = record_start + int(field["offset"])
                end = start + int(field["length"])
                data[start:end] = encode_field(record[field_name], field)

        today = dt.date.today()
        data[1:4] = bytes((today.year - 1900, today.month, today.day))
        atomic_write_bytes(args.dbf, bytes(data))
        write_departure_csv(departure_csv, active_records)
        geojson_features = write_geojson(args.dbf.with_suffix(".shp"), geojson)

        report.update(
            {
                "backup": str(backup),
                "routes_departures_csv": str(departure_csv),
                "routes_departures_rows": len(active_records),
                "geojson": str(geojson),
                "geojson_features": geojson_features,
                "timestamp": stamp,
            }
        )
        default_report = source_root / f"线路属性更新审计_{stamp}.json"
        report_path = args.report or default_report
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
                key: report[key]
                for key in (
                    "mode",
                    "dbf_records",
                    "blank_timetable_before",
                    "blank_timetable_outside_figure_1_before",
                    "blank_timetable_after",
                    "figure_1_routes_verified",
                    "user_timetables_filled",
                    "figure_2_price_records_matched",
                    "figure_2_price_records_changed",
                    "xls_company_records_matched",
                    "xls_company_records_changed",
                )
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
