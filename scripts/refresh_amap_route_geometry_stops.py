#!/usr/bin/env python3
"""Refresh reviewed Amap route geometry and stops while preserving route SHP attributes."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import math
import os
import shutil
import tempfile
from pathlib import Path

import requests
import shapefile


AUTHORITY = Path("/Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点")
CRAWLER = Path("/Users/a../模型算法/高德爬虫")
PLATFORM_MAP = CRAWLER / "gz_amap_bus_output/clean/occurrence_platform_map.csv"
AMAP_KEY = "0ccc60fff704e8dc25124f424fc72871"
TARGET_IDS = ("440100013959", "440100013964")
PI = math.pi
A = 6378245.0
EE = 0.00669342162296594323


def gcj02_to_wgs84(lng: float, lat: float) -> tuple[float, float]:
    if not (73.66 < lng < 135.05 and 3.86 < lat < 53.55):
        return lng, lat

    def transform_lat(x: float, y: float) -> float:
        value = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
        value += 0.2 * math.sqrt(abs(x))
        value += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
        value += (20.0 * math.sin(y * PI) + 40.0 * math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        value += (160.0 * math.sin(y / 12.0 * PI) + 320.0 * math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return value

    def transform_lng(x: float, y: float) -> float:
        value = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
        value += 0.1 * math.sqrt(abs(x))
        value += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
        value += (20.0 * math.sin(x * PI) + 40.0 * math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        value += (150.0 * math.sin(x / 12.0 * PI) + 300.0 * math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return value

    dlat = transform_lat(lng - 105.0, lat - 35.0)
    dlng = transform_lng(lng - 105.0, lat - 35.0)
    radlat = lat / 180.0 * PI
    magic = 1 - EE * math.sin(radlat) ** 2
    sqrtmagic = math.sqrt(magic)
    dlat = dlat * 180.0 / ((A * (1 - EE)) / (magic * sqrtmagic) * PI)
    dlng = dlng * 180.0 / (A / sqrtmagic * math.cos(radlat) * PI)
    return lng * 2 - (lng + dlng), lat * 2 - (lat + dlat)


def parse_location(value: str) -> tuple[float, float]:
    lng, lat = (float(item) for item in value.split(","))
    return gcj02_to_wgs84(lng, lat)


def fetch_line(line_id: str) -> dict:
    response = requests.get(
        "https://restapi.amap.com/v3/bus/lineid",
        params={"id": line_id, "extensions": "all", "output": "JSON", "key": AMAP_KEY},
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    lines = [item for item in payload.get("buslines", []) if str(item.get("id") or "") == line_id]
    if payload.get("status") != "1" or len(lines) != 1:
        raise ValueError(f"Amap exact detail failed for {line_id}: {payload.get('info')} count={len(lines)}")
    line = lines[0]
    coordinates = [parse_location(item) for item in str(line.get("polyline") or "").split(";") if item.strip()]
    stops = []
    for sequence, stop in enumerate(line.get("busstops") or [], 1):
        stops.append({
            "seq": sequence,
            "bv_id": str(stop.get("id") or ""),
            "name": str(stop.get("name") or "").strip(),
            "coord": parse_location(str(stop.get("location") or "")),
        })
    if len(coordinates) < 2 or len(stops) < 2 or any(not item["bv_id"] for item in stops):
        raise ValueError(f"Amap detail is incomplete for {line_id}")
    return {"line_id": line_id, "name": str(line.get("name") or ""), "coordinates": coordinates, "stops": stops}


def distance_squared(left: tuple[float, float], right: tuple[float, float]) -> float:
    return (left[0] - right[0]) ** 2 + (left[1] - right[1]) ** 2


def load_platform_candidates() -> dict[str, list[dict]]:
    candidates: dict[str, list[dict]] = {}
    with PLATFORM_MAP.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            bv_id = str(row.get("stop_id") or "").strip()
            platform_id = str(row.get("platform_id") or "").strip()
            if not bv_id or not platform_id:
                continue
            item = {
                "platform_id": platform_id,
                "line_id": str(row.get("line_id") or "").strip(),
                "coord": (float(row["longitude"]), float(row["latitude"])),
            }
            candidates.setdefault(bv_id, []).append(item)
    return candidates


def map_stops(lines: list[dict], candidates: dict[str, list[dict]]) -> None:
    for line in lines:
        for stop in line["stops"]:
            options = candidates.get(stop["bv_id"], [])
            if not options:
                raise ValueError(f"no PF platform mapping for {line['line_id']} {stop['bv_id']} {stop['name']}")
            exact = [item for item in options if item["line_id"] == line["line_id"]]
            pool = exact or options
            selected = min(pool, key=lambda item: distance_squared(item["coord"], stop["coord"]))
            stop["stop_id"] = selected["platform_id"]
            stop["mapping_method"] = "exact_line_occurrence" if exact else "nearest_existing_platform"


def records_by_id(path: Path, id_field: str) -> tuple[list[str], dict[str, list]]:
    reader = shapefile.Reader(str(path), encoding="utf-8")
    names = [field.name for field in reader.fields[1:]]
    result = {str(record[id_field]): list(record) for record in reader.records()}
    reader.close()
    return names, result


def build_routes(source: Path, lines: list[dict], target_dir: Path) -> Path:
    by_id = {line["line_id"]: line for line in lines}
    target_dir.mkdir(parents=True, exist_ok=True)
    reader = shapefile.Reader(str(source), encoding="utf-8")
    output = target_dir / source.name
    writer = shapefile.Writer(str(output), shapeType=reader.shapeType, encoding="utf-8")
    for field in reader.fields[1:]:
        writer.field(field.name, field.field_type, field.size, field.decimal)
    found = set()
    for shape_record in reader.iterShapeRecords():
        values = shape_record.record.as_dict()
        line_id = str(values.get("line_id") or "")
        if line_id in by_id:
            writer.line([by_id[line_id]["coordinates"]])
            found.add(line_id)
        else:
            writer.shape(shape_record.shape)
        writer.record(*list(shape_record.record))
    writer.close()
    reader.close()
    if found != set(by_id):
        raise ValueError(f"route SHP is missing targets: {sorted(set(by_id) - found)}")
    copy_companions(source, output)
    return output


def build_stops(source: Path, lines: list[dict], target_dir: Path) -> Path:
    updates: dict[str, dict] = {}
    for line in lines:
        for stop in line["stops"]:
            updates.setdefault(stop["stop_id"], stop)
    target_dir.mkdir(parents=True, exist_ok=True)
    reader = shapefile.Reader(str(source), encoding="utf-8")
    output = target_dir / source.name
    writer = shapefile.Writer(str(output), shapeType=reader.shapeType, encoding="utf-8")
    fields = list(reader.fields[1:])
    names = [field.name for field in fields]
    for field in fields:
        writer.field(field.name, field.field_type, field.size, field.decimal)
    found = set()
    for shape_record in reader.iterShapeRecords():
        values = shape_record.record.as_dict()
        stop_id = str(values.get("stop_id") or "")
        update = updates.get(stop_id)
        if update:
            lon, lat = update["coord"]
            values.update({"stop_name": update["name"], "lon": lon, "lat": lat})
            writer.point(lon, lat)
            found.add(stop_id)
        else:
            writer.shape(shape_record.shape)
        writer.record(*[values.get(name, "") for name in names])
    writer.close()
    reader.close()
    if found != set(updates):
        raise ValueError(f"stops SHP is missing PF targets: {sorted(set(updates) - found)}")
    copy_companions(source, output)
    return output


def build_sequence(source: Path, lines: list[dict], target_dir: Path) -> Path:
    target_dir.mkdir(parents=True, exist_ok=True)
    with source.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = list(reader.fieldnames or [])
        kept = [row for row in reader if str(row.get("line_id") or "") not in TARGET_IDS]
    replacement = []
    for line in lines:
        for stop in line["stops"]:
            replacement.append({
                "line_id": line["line_id"], "seq": stop["seq"], "stop_id": stop["stop_id"],
                "stop_name": stop["name"], "lon": stop["coord"][0], "lat": stop["coord"][1],
            })
    output = target_dir / source.name
    with output.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(kept + replacement)
    return output


def copy_companions(source: Path, output: Path) -> None:
    for suffix in (".prj", ".cpg"):
        companion = source.with_suffix(suffix)
        if companion.exists():
            shutil.copy2(companion, output.with_suffix(suffix))


def validate(routes_source: Path, routes_output: Path, sequence_output: Path, lines: list[dict]) -> dict:
    source_fields, source_records = records_by_id(routes_source, "line_id")
    output_fields, output_records = records_by_id(routes_output, "line_id")
    if source_fields != output_fields or source_records != output_records:
        raise ValueError("routes.shp DBF schema or attribute values changed")
    counts = {}
    endpoints = {}
    with sequence_output.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for line in lines:
        selected = [row for row in rows if row["line_id"] == line["line_id"]]
        sequences = [int(row["seq"]) for row in selected]
        if sequences != list(range(1, len(line["stops"]) + 1)):
            raise ValueError(f"non-contiguous sequence for {line['line_id']}: {sequences}")
        counts[line["line_id"]] = len(selected)
        endpoints[line["line_id"]] = [selected[0]["stop_name"], selected[-1]["stop_name"]]
    return {
        "route_attribute_fields": source_fields,
        "route_attribute_records_unchanged": len(source_records),
        "refreshed_stop_counts": counts,
        "refreshed_endpoints": endpoints,
    }


def atomic_replace(source: Path, destination: Path) -> None:
    fd, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as target, source.open("rb") as input_stream:
            shutil.copyfileobj(input_stream, target)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish_shapefile(generated: Path, live: Path) -> None:
    for suffix in (".shp", ".shx", ".dbf", ".prj", ".cpg"):
        source = generated.with_suffix(suffix)
        if source.exists():
            atomic_replace(source, live.with_suffix(suffix))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--authority", type=Path, default=AUTHORITY)
    parser.add_argument("--report", type=Path, default=Path("outputs/nansha32_amap_refresh.json"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    routes = args.authority / "线路/routes.shp"
    stops = args.authority / "站点/stops.shp"
    sequence = args.authority / "站点/line_stop_sequence.csv"
    lines = [fetch_line(line_id) for line_id in TARGET_IDS]
    map_stops(lines, load_platform_candidates())
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    with tempfile.TemporaryDirectory(prefix="amap_route_refresh_") as name:
        folder = Path(name)
        generated_routes = build_routes(routes, lines, folder / "routes")
        generated_stops = build_stops(stops, lines, folder / "stops")
        generated_sequence = build_sequence(sequence, lines, folder / "sequence")
        validation = validate(routes, generated_routes, generated_sequence, lines)
        backup = None
        if args.apply:
            backup = args.authority / f"备份_南沙32路高德刷新_{stamp}"
            backup.mkdir(parents=True, exist_ok=False)
            for source in list(routes.parent.glob("routes.*")) + list(stops.parent.glob("stops.*")) + [sequence]:
                if source.is_file():
                    shutil.copy2(source, backup / source.name)
            publish_shapefile(generated_routes, routes)
            publish_shapefile(generated_stops, stops)
            atomic_replace(generated_sequence, sequence)
            validate(routes, routes, sequence, lines)
        report = {
            "mode": "apply" if args.apply else "dry-run", "timestamp": stamp,
            "backup": str(backup) if backup else "", "authority": str(args.authority),
            "amap_lines": [{
                "line_id": line["line_id"], "name": line["name"],
                "geometry_points": len(line["coordinates"]),
                "stops": [{key: value for key, value in stop.items() if key != "coord"} | {
                    "lon": stop["coord"][0], "lat": stop["coord"][1]
                } for stop in line["stops"]],
            } for line in lines],
            "validation": validation,
        }
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
