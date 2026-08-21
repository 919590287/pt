#!/usr/bin/env python3
"""Calculate and backfill direction-level real-route fleet counts.

The scheduler mirrors the production vehicle-calculation page defaults:
25-minute turnaround, 3-minute lateness tolerance, 400 km large-bus range,
and 20 km per direction when the SHP does not carry a manual length input.
Only two-direction route families with complete operating time and timetable
data are written; incomplete or one-direction families remain blank.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import shutil
import struct
from collections import defaultdict
from pathlib import Path


DEFAULT_SHP = Path("/Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点/线路/routes.shp")
DEFAULT_DEPARTURES = DEFAULT_SHP.parent / "routes_departures.csv"


def dbf_read(path: Path):
    data = bytearray(path.read_bytes())
    count = struct.unpack("<I", data[4:8])[0]
    header = struct.unpack("<H", data[8:10])[0]
    length = struct.unpack("<H", data[10:12])[0]
    fields = []
    pos = 32
    offset = 1
    while data[pos] != 0x0D:
        name = bytes(data[pos : pos + 11]).split(b"\0", 1)[0].decode("ascii")
        fields.append((name, chr(data[pos + 11]), data[pos + 16], offset))
        offset += data[pos + 16]
        pos += 32
    records = []
    for index in range(count):
        start = header + index * length
        if data[start] == 0x2A:
            records.append(None)
            continue
        record = {}
        for name, _kind, size, field_offset in fields:
            raw = bytes(data[start + field_offset : start + field_offset + size])
            value = raw.rstrip(b" \0").decode("utf-8", "replace")
            record[name] = "" if value and set(value) == {"*"} else value
        record["_index"] = index
        records.append(record)
    return data, header, length, fields, records


def dbf_write_number(data, header, length, fields, record_index, name, value):
    field = next(field for field in fields if field[0] == name)
    _name, kind, size, offset = field
    if kind not in {"N", "F"}:
        raise ValueError(f"{name} is not numeric")
    encoded = str(int(value)).encode("ascii")
    if len(encoded) > size:
        raise ValueError(f"{name} value does not fit: {value}")
    start = header + record_index * length + offset
    data[start : start + size] = encoded.rjust(size, b" ")


def parse_clock(value: str) -> int | None:
    parts = str(value or "").strip().split(":")
    if len(parts) < 2:
        return None
    try:
        hour, minute = int(parts[0]), int(parts[1])
    except ValueError:
        return None
    if hour < 0 or hour > 47 or minute < 0 or minute > 59:
        return None
    return hour * 60 + minute


def parse_times(value: str) -> list[int]:
    values = []
    for token in str(value or "").split(";"):
        parsed = parse_clock(token)
        if parsed is not None:
            values.append(parsed)
    return sorted(set(values))


def parse_duration(value: str) -> float | None:
    text = str(value or "").strip().replace("～", "-").replace("~", "-").replace("至", "-")
    text = text.replace("分钟", "").replace("分", "")
    parts = [part.strip() for part in text.split("-") if part.strip()]
    try:
        numbers = [float(part) for part in parts]
    except ValueError:
        return None
    return sum(numbers) / len(numbers) if numbers and all(number > 0 for number in numbers) else None


def route_family(name: str) -> str:
    text = str(name or "").strip()
    depth = 0
    start = -1
    for index in range(len(text) - 1, -1, -1):
        char = text[index]
        if char in ")）":
            depth += 1
        elif char in "(（":
            depth -= 1
            if depth == 0:
                inner = text[index + 1 : -1]
                if any(separator in inner for separator in ("--", "—", "－", "至", "到")):
                    start = index
                    break
    family = text[:start].strip() if start > 0 else text
    return family.removesuffix("上行").removesuffix("下行").strip()


def schedule_vehicle_count(up_times, down_times, up_duration, down_duration):
    tasks = [("A", "B", time, up_duration) for time in up_times]
    tasks += [("B", "A", time, down_duration) for time in down_times]
    tasks.sort(key=lambda task: task[2])
    vehicles = []
    for source, target, start, duration in tasks:
        candidates = []
        for index, vehicle in enumerate(vehicles):
            if vehicle["available"] <= start + 3 and vehicle["station"] == source and vehicle["mileage"] + 20 <= 400:
                candidates.append((index, (0 if len(vehicle["tasks"]) % 2 else 1, len(vehicle["tasks"]), max(0, start - vehicle["available"]))))
        if candidates:
            index = min(candidates, key=lambda item: item[1])[0]
            vehicle = vehicles[index]
            depart = max(vehicle["available"], start)
            vehicle["available"] = depart + duration + 25
            vehicle["station"] = target
            vehicle["mileage"] += 20
            vehicle["tasks"].append(start)
        else:
            vehicles.append({"available": start + duration + 25, "station": target, "mileage": 20, "tasks": [start]})
    return len(vehicles)


def schedule_single_direction_count(times, duration):
    vehicles = []
    for start in times:
        available = [vehicle for vehicle in vehicles if vehicle <= start + 3]
        if available:
            index = min(range(len(vehicles)), key=lambda item: (0 if vehicles[item] <= start else 1, vehicles[item]))
            vehicles[index] = start + duration + 25
        else:
            vehicles.append(start + duration + 25)
    return len(vehicles)


def calculate(path: Path, departures_path: Path, apply: bool):
    dbf = path.with_suffix(".dbf")
    data, header, length, fields, records = dbf_read(dbf)
    field_names = {field[0] for field in fields}
    required = {"line_id", "name", "op_time", "load_num"}
    missing = required - field_names
    if missing:
        raise ValueError(f"SHP DBF missing fields: {sorted(missing)}")
    departure_by_id = {}
    with departures_path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            departure_by_id[str(row.get("line_id") or "").strip()] = parse_times(row.get("departures"))

    groups = defaultdict(list)
    for record in records:
        if record and record.get("mode", "bus").lower() == "bus":
            groups[route_family(record.get("name", ""))].append(record)
    updates = []
    skipped = []
    for family, group in groups.items():
        if len(group) > 2:
            # A duplicated SHP direction shares the same physical-line fleet;
            # use the first two complete records and write the result to all.
            source_group = group[:2]
        else:
            source_group = group
        durations = [parse_duration(record.get("op_time")) for record in source_group]
        times = [departure_by_id.get(record.get("line_id", ""), parse_times(record.get("timetable"))) for record in source_group]
        if any(duration is None for duration in durations) or any(not value for value in times):
            skipped.append(family)
            continue
        if len(source_group) == 1:
            count = schedule_single_direction_count(times[0], durations[0])
        else:
            count = schedule_vehicle_count(times[0], times[1], durations[0], durations[1])
        if count < 1:
            skipped.append(family)
            continue
        for record in group:
            if not record.get("load_num", "").strip():
                updates.append((record["_index"], record.get("line_id", ""), family, count))
                if apply:
                    dbf_write_number(data, header, length, fields, record["_index"], "load_num", count)

    if apply and updates:
        stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = dbf.parent / f"backup_before_auto_fleet_{stamp}"
        backup.mkdir()
        for suffix in (".shp", ".shx", ".dbf", ".prj", ".cpg"):
            source = path.with_suffix(suffix)
            if source.exists(): shutil.copy2(source, backup / source.name)
        temp = dbf.with_suffix(".dbf.tmp")
        temp.write_bytes(data)
        temp.replace(dbf)
    return updates, skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shp", type=Path, default=DEFAULT_SHP)
    parser.add_argument("--departures", type=Path, default=DEFAULT_DEPARTURES)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    updates, skipped = calculate(args.shp, args.departures, args.apply)
    print({"applied": args.apply, "updated": len(updates), "skippedIncompleteFamilies": len(skipped), "sample": updates[:12]})


if __name__ == "__main__":
    main()
