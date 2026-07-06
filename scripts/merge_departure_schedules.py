from __future__ import annotations

import csv
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


BASE_DIR = Path("/Users/a../数据/线路发车时刻表_车来了")

ORIGINAL_CSV = BASE_DIR / "线路时刻汇总.csv"
PARTIAL_CSV = BASE_DIR / "线路发车时刻_部分.csv"
STRICT_CSV = BASE_DIR / "发车时刻表_严格匹配结果.csv"
RING_CSV = BASE_DIR / "环线填充.csv"

OUTPUT_SCHEDULE_CSV = BASE_DIR / "线路发车时刻_补充后.csv"
OUTPUT_MISSING_CSV = BASE_DIR / "未找到线路清单.csv"


@dataclass
class SupplementRow:
    line_name: str
    schedule: str
    source: str
    keyword: str


def clean(value: object) -> str:
    return str(value or "").strip().lstrip("\ufeff").lstrip("'").strip()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = []
        for row in csv.DictReader(handle):
            rows.append({clean(key): clean(value) for key, value in row.items()})
    return rows


def write_csv(path: Path, rows: list[dict[str, str]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def canonical_text(value: str) -> str:
    text = clean(value)
    text = (
        text.replace("（", "(")
        .replace("）", ")")
        .replace("－", "-")
        .replace("—", "-")
    )
    return re.sub(r"\s+", "", text).upper()


def parse_line_name(line_name: str) -> dict[str, str]:
    raw = clean(line_name)
    normalized = raw.replace("（", "(").replace("）", ")")
    stack: list[int] = []
    segments: list[tuple[int, int, str]] = []

    for index, char in enumerate(normalized):
        if char == "(":
            stack.append(index)
        elif char == ")" and stack:
            start = stack.pop()
            if not stack:
                segments.append((start, index, normalized[start + 1 : index]))

    for start, _end, content in reversed(segments):
        if "--" in content:
            dash = content.find("--")
            return {
                "route": normalized[:start],
                "from": content[:dash],
                "to": content[dash + 2 :],
                "raw": raw,
            }

    return {"route": raw, "from": "", "to": "", "raw": raw}


def line_key(line_name: str) -> str:
    return canonical_text(line_name)


def route_key(line_name: str) -> str:
    return canonical_text(parse_line_name(line_name)["route"])


def strip_parenthetical(value: str) -> str:
    text = canonical_text(value)
    previous = None
    while previous != text:
        previous = text
        text = re.sub(r"\([^()]*\)", "", text)
    return text


def station_variants(value: str) -> set[str]:
    base = strip_parenthetical(value)
    variants = {base}
    suffixes = ["下客区", "公交总站", "汽车站总站", "客运站总站", "地铁总站", "总站", "站"]

    changed = True
    while changed:
        changed = False
        for variant in list(variants):
            for suffix in suffixes:
                if variant.endswith(suffix) and len(variant) > len(suffix) + 1:
                    shortened = variant[: -len(suffix)]
                    if shortened not in variants:
                        variants.add(shortened)
                        changed = True

    for variant in list(variants):
        trimmed = re.sub(r"[①②③④⑤⑥⑦⑧⑨⑩]+$", "", variant)
        if trimmed:
            variants.add(trimmed)

    return {variant for variant in variants if variant}


def stations_match(left: str, right: str) -> bool:
    left_variants = station_variants(left)
    right_variants = station_variants(right)
    if left_variants & right_variants:
        return True

    for left_variant in left_variants:
        for right_variant in right_variants:
            if min(len(left_variant), len(right_variant)) >= 3:
                if left_variant in right_variant or right_variant in left_variant:
                    return True
    return False


def source_parts(row: dict[str, str]) -> list[str]:
    return [part.strip() for part in clean(row.get("来源name")).split(" | ") if part.strip()]


def build_supplements() -> list[SupplementRow]:
    supplements: list[SupplementRow] = []

    for row in read_csv(STRICT_CSV):
        schedule = clean(row.get("发车时刻表"))
        if not schedule:
            continue
        line_name = f"{clean(row.get('线路名'))}({clean(row.get('首站'))}--{clean(row.get('末站'))})"
        supplements.append(
            SupplementRow(
                line_name=line_name,
                schedule=schedule,
                source="严格匹配",
                keyword=clean(row.get("关键词")),
            )
        )

    for row in read_csv(RING_CSV):
        parts = source_parts(row)
        keyword = clean(row.get("检索关键字"))

        up_schedule = clean(row.get("上行时刻"))
        if up_schedule:
            line_name = parts[0] if parts else (
                f"{clean(row.get('检索关键字') or row.get('API线路名称'))}"
                f"({clean(row.get('上行起点'))}--{clean(row.get('上行终点'))})"
            )
            supplements.append(
                SupplementRow(line_name=line_name, schedule=up_schedule, source="环线上行", keyword=keyword)
            )

        down_schedule = clean(row.get("下行时刻"))
        if down_schedule:
            line_name = (
                parts[1]
                if len(parts) > 1
                else parts[0]
                if parts
                else (
                    f"{clean(row.get('检索关键字') or row.get('API线路名称'))}"
                    f"({clean(row.get('下行起点'))}--{clean(row.get('下行终点'))})"
                )
            )
            supplements.append(
                SupplementRow(line_name=line_name, schedule=down_schedule, source="环线下行", keyword=keyword)
            )

    supplements.sort(key=lambda item: 0 if item.source == "严格匹配" else 1)
    return supplements


def same_direction(left_name: str, right_name: str) -> bool:
    left = parse_line_name(left_name)
    right = parse_line_name(right_name)

    if canonical_text(left["route"]) != canonical_text(right["route"]):
        return False
    if not left["from"] or not right["from"]:
        return False

    return stations_match(left["from"], right["from"]) and stations_match(left["to"], right["to"])


def fill_and_append(
    partial_rows: list[dict[str, str]],
    supplements: list[SupplementRow],
) -> tuple[list[dict[str, str]], dict[str, int], set[str]]:
    rows = [
        {"线路名称": clean(row.get("线路名称")), "发车时刻": clean(row.get("发车时刻"))}
        for row in partial_rows
    ]

    name_to_indexes: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        name_to_indexes[line_key(row["线路名称"])].append(index)

    used_supplements: set[int] = set()
    used_keywords: set[str] = set()
    stats = Counter()

    for index, supplement in enumerate(supplements):
        blank_indexes = [
            row_index
            for row_index in name_to_indexes.get(line_key(supplement.line_name), [])
            if not rows[row_index]["发车时刻"]
        ]
        if not blank_indexes:
            continue
        for row_index in blank_indexes:
            rows[row_index]["发车时刻"] = supplement.schedule
            stats["精确填充行数"] += 1
        used_supplements.add(index)
        if supplement.keyword:
            used_keywords.add(canonical_text(supplement.keyword))

    for row_index, row in enumerate(rows):
        if row["发车时刻"]:
            continue

        chosen_index = None
        for supplement_index, supplement in enumerate(supplements):
            if supplement_index in used_supplements:
                continue
            if same_direction(row["线路名称"], supplement.line_name):
                chosen_index = supplement_index
                break

        if chosen_index is None:
            continue

        supplement = supplements[chosen_index]
        same_blank_indexes = [
            index
            for index in name_to_indexes.get(line_key(row["线路名称"]), [row_index])
            if not rows[index]["发车时刻"]
        ]
        for same_blank_index in same_blank_indexes:
            rows[same_blank_index]["发车时刻"] = supplement.schedule
            stats["起终点规范匹配填充行数"] += 1
        used_supplements.add(chosen_index)
        if supplement.keyword:
            used_keywords.add(canonical_text(supplement.keyword))

    valid_existing_names = [row["线路名称"] for row in rows if row["发车时刻"]]
    for index, supplement in enumerate(supplements):
        if index in used_supplements:
            continue
        if any(line_key(row["线路名称"]) == line_key(supplement.line_name) for row in rows if row["发车时刻"]):
            continue
        if any(same_direction(existing_name, supplement.line_name) for existing_name in valid_existing_names):
            continue

        rows.append({"线路名称": supplement.line_name, "发车时刻": supplement.schedule})
        valid_existing_names.append(supplement.line_name)
        stats["追加行数"] += 1
        if supplement.keyword:
            used_keywords.add(canonical_text(supplement.keyword))

    return rows, dict(stats), used_keywords


def strict_failure_lookup() -> dict[str, str]:
    failures: dict[str, set[str]] = defaultdict(set)
    seen: set[str] = set()
    for row in read_csv(STRICT_CSV):
        keyword = canonical_text(row.get("关键词"))
        if not keyword:
            continue
        seen.add(keyword)
        if clean(row.get("发车时刻表")):
            failures[keyword].add("已有发车时刻")
        elif clean(row.get("失败原因")):
            failures[keyword].add(clean(row.get("失败原因")))
        else:
            failures[keyword].add("无发车时刻")

    return {keyword: ";".join(sorted(failures.get(keyword, {"未在严格匹配结果中"}))) for keyword in seen}


def ring_status_lookup() -> dict[str, str]:
    statuses: dict[str, set[str]] = defaultdict(set)
    for row in read_csv(RING_CSV):
        keyword = canonical_text(row.get("检索关键字"))
        if not keyword:
            continue
        if clean(row.get("上行时刻")) or clean(row.get("下行时刻")):
            statuses[keyword].add("已有补充时刻")
        else:
            statuses[keyword].add("无有效时刻")
    return {keyword: ";".join(sorted(value)) for keyword, value in statuses.items()}


def find_missing_routes(
    original_rows: list[dict[str, str]],
    merged_rows: list[dict[str, str]],
    supplement_keywords: set[str],
) -> list[dict[str, str]]:
    found_keys = set(supplement_keywords)
    for row in merged_rows:
        if row["发车时刻"]:
            found_keys.add(route_key(row["线路名称"]))

    strict_status = strict_failure_lookup()
    ring_status = ring_status_lookup()
    missing_rows: list[dict[str, str]] = []

    for row in original_rows:
        candidates = {canonical_text(row.get("检索关键字"))}
        for part in source_parts(row):
            candidates.add(route_key(part))

        if any(candidate and candidate in found_keys for candidate in candidates):
            continue

        keyword_key = canonical_text(row.get("检索关键字"))
        missing_rows.append(
            {
                "检索关键字": clean(row.get("检索关键字")),
                "API线路名称": clean(row.get("API线路名称")),
                "lineNo": clean(row.get("lineNo")),
                "来源name": clean(row.get("来源name")),
                "匹配状态": clean(row.get("匹配状态")),
                "原始上行时刻是否为空": "是" if not clean(row.get("上行时刻")) else "否",
                "原始下行时刻是否为空": "是" if not clean(row.get("下行时刻")) else "否",
                "严格匹配结果": strict_status.get(keyword_key, "未在严格匹配结果中"),
                "环线填充结果": ring_status.get(keyword_key, "未在环线填充中"),
            }
        )

    return missing_rows


def dedupe_same_schedule_directions(rows: list[dict[str, str]]) -> tuple[list[dict[str, str]], int]:
    deduped_rows: list[dict[str, str]] = []
    removed_count = 0

    for row in rows:
        duplicate_index = None
        for index, existing_row in enumerate(deduped_rows):
            if row["发车时刻"] != existing_row["发车时刻"]:
                continue
            if line_key(row["线路名称"]) == line_key(existing_row["线路名称"]):
                duplicate_index = index
                break
            if same_direction(row["线路名称"], existing_row["线路名称"]):
                duplicate_index = index
                break

        if duplicate_index is None:
            deduped_rows.append(row)
            continue

        removed_count += 1
        if len(canonical_text(row["线路名称"])) > len(canonical_text(deduped_rows[duplicate_index]["线路名称"])):
            deduped_rows[duplicate_index] = row

    return deduped_rows, removed_count


def main() -> None:
    original_rows = read_csv(ORIGINAL_CSV)
    partial_rows = read_csv(PARTIAL_CSV)
    supplements = build_supplements()
    merged_rows, stats, supplement_keywords = fill_and_append(partial_rows, supplements)

    valid_rows = [row for row in merged_rows if row["发车时刻"]]
    valid_rows, deduped_count = dedupe_same_schedule_directions(valid_rows)
    missing_rows = find_missing_routes(original_rows, merged_rows, supplement_keywords)

    write_csv(OUTPUT_SCHEDULE_CSV, valid_rows, ["线路名称", "发车时刻"])
    write_csv(
        OUTPUT_MISSING_CSV,
        missing_rows,
        [
            "检索关键字",
            "API线路名称",
            "lineNo",
            "来源name",
            "匹配状态",
            "原始上行时刻是否为空",
            "原始下行时刻是否为空",
            "严格匹配结果",
            "环线填充结果",
        ],
    )

    print(f"原始部分表行数: {len(partial_rows)}")
    print(f"原始部分表有效时刻行数: {sum(1 for row in partial_rows if clean(row.get('发车时刻')))}")
    print(f"补充数据有效方向行数: {len(supplements)}")
    print(f"精确填充行数: {stats.get('精确填充行数', 0)}")
    print(f"起终点规范匹配填充行数: {stats.get('起终点规范匹配填充行数', 0)}")
    print(f"追加行数: {stats.get('追加行数', 0)}")
    print(f"同方向同时刻去重行数: {deduped_count}")
    print(f"补充后有效时刻行数: {len(valid_rows)}")
    print(f"仍未找到线路数: {len(missing_rows)}")
    print(f"输出: {OUTPUT_SCHEDULE_CSV}")
    print(f"输出: {OUTPUT_MISSING_CSV}")


if __name__ == "__main__":
    main()
