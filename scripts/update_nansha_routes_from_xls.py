#!/usr/bin/env python3
"""Update original Nansha route DBF attributes from the supplied route table."""

from __future__ import annotations

import argparse
import datetime as dt
import difflib
import json
import math
import os
import re
import struct
import tempfile
from pathlib import Path

import pandas as pd


DEFAULT_XLS = Path("/Users/a../Downloads/线路总表.xls")
DEFAULT_DBF = Path(
    "/Volumes/USB DISK/pt_data/广州市/真实数据/"
    "公交线路站点/线路/routes.dbf"
)


def clean_text(value: object) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    return str(value).strip()


def canonical_route_code(value: object) -> str:
    text = clean_text(value).upper()
    text = re.sub(r"[\s（）()·\-—_]", "", text)
    if text.startswith("南沙"):
        text = text[2:]
    elif re.match(r"^南(?=\d|[GKWT夜学旅游])", text):
        text = text[1:]
    text = text.replace("快线", "快").replace("支线", "支")
    text = text.replace("A线", "A").replace("B线", "B")
    text = text.replace("路", "").replace("线", "")
    return text


def normalize_endpoint(value: object) -> str:
    text = clean_text(value)
    text = re.sub(r"[\s（）()\[\]【】·,，。]", "", text)
    replacements = {
        "公交总站": "",
        "公交站场": "",
        "公交站": "",
        "总站": "",
        "客运站": "",
        "地铁": "",
        "地铁站": "",
        "站": "",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


def split_endpoints(value: object) -> tuple[str, str]:
    text = clean_text(value)
    text = text.replace("－", "-").replace("—", "-").replace("–", "-")
    parts = re.split(r"\s*(?:--|→|至)\s*", text, maxsplit=1)
    if len(parts) == 1:
        parts = re.split(r"\s*-\s*|\s*~\s*", text, maxsplit=1)
    if len(parts) != 2:
        return "", ""
    return normalize_endpoint(parts[0]), normalize_endpoint(parts[1])


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
        if "--" in content or "—" in content or "－" in content:
            first, last = split_endpoints(content)
            return name[:start].strip(), first, last
    return name.strip(), "", ""


def is_nansha_label(label: str, xls_codes: set[str]) -> bool:
    compact = re.sub(r"\s+", "", label)
    return (
        compact in xls_codes
        or compact.startswith("南沙")
        or bool(re.match(r"^南(?=\d|[GKWT夜学旅游])", compact))
    )


def similarity(left: str, right: str) -> float:
    if not left or not right:
        return 0.0
    if left == right:
        return 1.0
    if left in right or right in left:
        return min(len(left), len(right)) / max(len(left), len(right))
    return difflib.SequenceMatcher(None, left, right).ratio()


def endpoint_score(
    shp_start: str, shp_end: str, xls_start: str, xls_end: str
) -> tuple[float, float]:
    forward = (similarity(shp_start, xls_start) + similarity(shp_end, xls_end)) / 2
    reverse = (similarity(shp_start, xls_end) + similarity(shp_end, xls_start)) / 2
    return forward, reverse


def normalize_time(value: object) -> tuple[str, str] | None:
    text = clean_text(value).replace("：", ":")
    if not text:
        return None
    text = re.sub(r"\s+", "", text)
    parts = re.split(r"[~～\-—至]", text, maxsplit=1)
    if len(parts) != 2:
        return None

    def one(part: str) -> str | None:
        match = re.fullmatch(r"(\d{1,2}):?(\d{2})(?::?(\d{2}))?", part)
        if not match:
            return None
        hour = int(match.group(1))
        minute = int(match.group(2))
        second = int(match.group(3) or 0)
        if hour > 23 or minute > 59 or second > 59:
            return None
        return f"{hour:02d}:{minute:02d}:{second:02d}"

    first, last = one(parts[0]), one(parts[1])
    return (first, last) if first and last else None


def normalize_price(value: object) -> str:
    text = clean_text(value)
    if not text:
        return ""
    try:
        number = float(text)
    except ValueError:
        return text
    return str(int(number)) if number.is_integer() else f"{number:g}"


def load_xls(path: Path) -> list[dict[str, str]]:
    table = pd.read_html(path)[0]
    table.columns = table.iloc[0]
    table = table.iloc[1:].reset_index(drop=True)
    rows: list[dict[str, str]] = []
    for source_index, row in table.iterrows():
        code = clean_text(row.get("线路编码"))
        start, end = split_endpoints(row.get("发班起止"))
        if not code or not start or not end:
            continue
        times = normalize_time(row.get("发班时间"))
        rows.append(
            {
                "source_row": str(source_index + 2),
                "code": code,
                "canonical_code": canonical_route_code(code),
                "start": start,
                "end": end,
                "first": times[0] if times else "",
                "last": times[1] if times else "",
                "price": normalize_price(row.get("全程票价")),
                "company": clean_text(row.get("所属公司")),
            }
        )
    return rows


def read_dbf(path: Path) -> tuple[bytearray, int, int, list[dict[str, object]]]:
    data = bytearray(path.read_bytes())
    record_count = struct.unpack("<I", data[4:8])[0]
    header_length = struct.unpack("<H", data[8:10])[0]
    record_length = struct.unpack("<H", data[10:12])[0]
    fields: list[dict[str, object]] = []
    offset = 32
    field_offset = 1
    while data[offset] != 0x0D:
        name = bytes(data[offset : offset + 11]).split(b"\0", 1)[0].decode("ascii")
        length = data[offset + 16]
        fields.append({"name": name, "length": length, "offset": field_offset})
        field_offset += length
        offset += 32
    if field_offset != record_length:
        raise ValueError(
            f"DBF record length mismatch: fields={field_offset}, header={record_length}"
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


def encoded_field(value: str, length: int, field_name: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > length:
        raise ValueError(
            f"value too long for {field_name}: {len(encoded)} bytes > {length}"
        )
    return encoded.ljust(length, b" ")


def values_signature(row: dict[str, str]) -> tuple[str, str, str, str]:
    return row["first"], row["last"], row["price"], row["company"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xls", type=Path, default=DEFAULT_XLS)
    parser.add_argument("--dbf", type=Path, default=DEFAULT_DBF)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--min-score", type=float, default=0.90)
    args = parser.parse_args()

    xls_rows = load_xls(args.xls)
    xls_by_code: dict[str, list[dict[str, str]]] = {}
    raw_xls_codes = {re.sub(r"\s+", "", row["code"]) for row in xls_rows}
    for row in xls_rows:
        xls_by_code.setdefault(row["canonical_code"], []).append(row)

    data, header_length, record_length, fields = read_dbf(args.dbf)
    field_by_name = {str(field["name"]): field for field in fields}
    required = {"name", "first", "last", "price", "company"}
    missing = required - field_by_name.keys()
    if missing:
        raise ValueError(f"DBF missing required fields: {sorted(missing)}")

    matched: list[dict[str, object]] = []
    skipped: list[dict[str, object]] = []
    staged_updates: list[tuple[int, dict[str, str]]] = []
    considered = 0

    record_count = struct.unpack("<I", data[4:8])[0]
    for record_index in range(record_count):
        record_start = header_length + record_index * record_length
        if data[record_start] == 0x2A:
            continue
        record = decode_record(data, record_start, fields)
        label, shp_start, shp_end = route_label_and_endpoints(record["name"])
        if not is_nansha_label(label, raw_xls_codes):
            continue
        route_code = canonical_route_code(label)
        candidates = xls_by_code.get(route_code, [])
        if not candidates:
            continue
        considered += 1
        scored = []
        for candidate in candidates:
            forward, reverse = endpoint_score(
                shp_start,
                shp_end,
                candidate["start"],
                candidate["end"],
            )
            scored.append((forward, reverse, candidate))
        scored.sort(key=lambda item: item[0], reverse=True)
        best_score = scored[0][0]
        best = [item for item in scored if abs(item[0] - best_score) < 1e-9]
        best_signatures = {values_signature(item[2]) for item in best}
        next_score = scored[len(best)][0] if len(scored) > len(best) else -1.0
        reason = ""
        if not shp_start or not shp_end:
            reason = "SHP name has no parseable direction"
        elif best_score < args.min_score:
            reason = "direction score below threshold"
        elif len(best_signatures) > 1:
            reason = "equally ranked XLS rows have conflicting values"
        elif next_score >= args.min_score and best_score - next_score < 0.04:
            reason = "multiple XLS directions are too close"
        if reason:
            skipped.append(
                {
                    "record_index": record_index,
                    "route_name": record["name"],
                    "canonical_code": route_code,
                    "reason": reason,
                    "best_score": round(best_score, 4),
                    "candidates": [
                        {
                            "source_row": item[2]["source_row"],
                            "code": item[2]["code"],
                            "start": item[2]["start"],
                            "end": item[2]["end"],
                            "score": round(item[0], 4),
                            "reverse_score": round(item[1], 4),
                        }
                        for item in scored[:4]
                    ],
                }
            )
            continue

        selected = best[0][2]
        update = {
            key: selected[key]
            for key in ("first", "last", "price", "company")
            if selected[key]
        }
        changes = {
            key: {"before": record[key], "after": value}
            for key, value in update.items()
            if record[key] != value
        }
        matched.append(
            {
                "record_index": record_index,
                "route_name": record["name"],
                "canonical_code": route_code,
                "source_row": selected["source_row"],
                "xls_code": selected["code"],
                "score": round(best_score, 4),
                "changes": changes,
            }
        )
        if changes:
            staged_updates.append((record_start, update))

    if args.apply:
        for record_start, update in staged_updates:
            for field_name, value in update.items():
                field = field_by_name[field_name]
                start = record_start + int(field["offset"])
                end = start + int(field["length"])
                data[start:end] = encoded_field(
                    value, int(field["length"]), field_name
                )
        today = dt.date.today()
        data[1:4] = bytes((today.year - 1900, today.month, today.day))
        fd, temp_name = tempfile.mkstemp(
            prefix=f".{args.dbf.name}.", suffix=".tmp", dir=args.dbf.parent
        )
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp_name, args.dbf)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)

    report = {
        "mode": "apply" if args.apply else "dry-run",
        "source": str(args.xls),
        "dbf": str(args.dbf),
        "threshold": args.min_score,
        "xls_rows": len(xls_rows),
        "xls_route_codes": len(xls_by_code),
        "dbf_records": record_count,
        "considered_records": considered,
        "matched_records": len(matched),
        "changed_records": len(staged_updates),
        "skipped_records": len(skipped),
        "changed_field_counts": {
            field: sum(field in row["changes"] for row in matched)
            for field in ("first", "last", "price", "company")
        },
        "matched": matched,
        "skipped": skipped,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({key: report[key] for key in (
        "mode",
        "xls_rows",
        "xls_route_codes",
        "dbf_records",
        "considered_records",
        "matched_records",
        "changed_records",
        "skipped_records",
        "changed_field_counts",
    )}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
