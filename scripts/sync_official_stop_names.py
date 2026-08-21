#!/usr/bin/env python3
"""Normalize the shared stop names used by the official Nansha 65 alignment."""

from __future__ import annotations

import csv
import datetime as dt
import json
import os
import shutil
import tempfile
from pathlib import Path

import shapefile


ROOT = Path("/Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点")
STOPS = ROOT / "站点/stops.shp"
SEQUENCE = ROOT / "站点/line_stop_sequence.csv"

# West-gate wording follows the user's requested normalized display name;
# the other two names follow the official 2026-06-14 Nansha 65 notice.
STOP_NAMES = {
    "PF15918": "市桥汽车站东门(番禺人才市场)站",
    "PF15952": "市桥汽车站西门站",
    "PF17655": "潭洲车站总站",
    "PF17656": "潭洲车站总站",
}


def write_updated_stops(temp_dir: Path) -> tuple[Path, dict[str, str]]:
    reader = shapefile.Reader(str(STOPS), encoding="utf-8")
    output = temp_dir / STOPS.name
    writer = shapefile.Writer(str(output), shapeType=reader.shapeType, encoding="utf-8")
    fields = list(reader.fields[1:])
    for field in fields:
        writer.field(field.name, field.field_type, field.size, field.decimal)
    field_names = [field.name for field in fields]
    found: dict[str, str] = {}
    for shape_record in reader.iterShapeRecords():
        values = shape_record.record.as_dict()
        stop_id = str(values.get("stop_id") or "")
        if stop_id in STOP_NAMES:
            found[stop_id] = str(values.get("stop_name") or "")
            values["stop_name"] = STOP_NAMES[stop_id]
        writer.shape(shape_record.shape)
        writer.record(*[values.get(name, "") for name in field_names])
    writer.close()
    reader.close()
    missing = sorted(set(STOP_NAMES) - set(found))
    if missing:
        raise ValueError(f"stop ids missing from stops.shp: {missing}")
    for suffix in (".prj", ".cpg"):
        source = STOPS.with_suffix(suffix)
        if source.exists():
            shutil.copy2(source, output.with_suffix(suffix))
    return output, found


def write_updated_sequence(temp_dir: Path) -> tuple[Path, dict[str, int]]:
    with SEQUENCE.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        field_names = list(reader.fieldnames or [])
        rows = list(reader)
    counts = {stop_id: 0 for stop_id in STOP_NAMES}
    for row in rows:
        stop_id = str(row.get("stop_id") or "")
        if stop_id in STOP_NAMES:
            row["stop_name"] = STOP_NAMES[stop_id]
            counts[stop_id] += 1
    missing = sorted(stop_id for stop_id, count in counts.items() if not count)
    if missing:
        raise ValueError(f"stop ids missing from line_stop_sequence.csv: {missing}")
    output = temp_dir / SEQUENCE.name
    with output.open("w", encoding="utf-8-sig", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=field_names)
        writer.writeheader()
        writer.writerows(rows)
    return output, counts


def replace_file(source: Path, destination: Path) -> None:
    mode = destination.stat().st_mode if destination.exists() else None
    fd, staged = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent
    )
    try:
        with os.fdopen(fd, "wb") as target, source.open("rb") as input_stream:
            shutil.copyfileobj(input_stream, target)
            target.flush()
            os.fsync(target.fileno())
        if mode is not None:
            os.chmod(staged, mode)
        os.replace(staged, destination)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)


def main() -> int:
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = ROOT / f"备份_65路官方站名_{stamp}"
    backup.mkdir(parents=True, exist_ok=False)
    for source in list(STOPS.parent.glob("stops.*")) + [SEQUENCE]:
        if source.is_file():
            shutil.copy2(source, backup / source.name)

    with tempfile.TemporaryDirectory(prefix="official_stop_names_") as temp_name:
        temp_dir = Path(temp_name)
        generated_stops, before_names = write_updated_stops(temp_dir)
        generated_sequence, sequence_counts = write_updated_sequence(temp_dir)
        for suffix in (".shp", ".shx", ".dbf", ".prj", ".cpg"):
            generated = generated_stops.with_suffix(suffix)
            if generated.exists():
                replace_file(generated, STOPS.with_suffix(suffix))
        replace_file(generated_sequence, SEQUENCE)

    report = {
        "timestamp": stamp,
        "backup": str(backup),
        "stops_shp": str(STOPS),
        "line_stop_sequence": str(SEQUENCE),
        "renamed_stops": {
            stop_id: {
                "before": before_names[stop_id],
                "after": STOP_NAMES[stop_id],
                "sequence_rows": sequence_counts[stop_id],
            }
            for stop_id in STOP_NAMES
        },
    }
    report_path = ROOT / f"65路官方站名调整审计_{stamp}.json"
    with report_path.open("w", encoding="utf-8") as output:
        json.dump({**report, "report": str(report_path)}, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({**report, "report": str(report_path)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
