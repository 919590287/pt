#!/usr/bin/env python3
"""从街道面资源融合生成区级行政区划 SHP。

读取后端内嵌的 176 街道面资源（gz_streets_wgs84.geojson.gz），按 district
字段融合为 11 个区面，写成平台「行政区范围」目录要求的单一 SHP
（WGS84 经纬度，DBF 为 UTF-8，附 .prj/.cpg）。

语义与曾经的后端运行时融合（GuangzhouDistrictBoundaries）一致：
无效几何 buffer(0) 修复、仅保留面几何、坐标按 1e-6 度网格取整、
区按固定顺序（越秀→增城）排列。

街道面资源更新后必须重跑本脚本，区界/裁剪/覆盖率才会跟着变。

用法：
    python3 scripts/build_admin_district_shp.py [输出目录]
    输出目录缺省为 /Volumes/USB DISK/pt_data/广州市/真实数据/行政区范围
"""

import gzip
import json
import sys
from pathlib import Path

import shapefile  # pyshp
import shapely
from shapely.geometry import MultiPolygon, Polygon, shape
from shapely.ops import unary_union

REPO_ROOT = Path(__file__).resolve().parent.parent
STREETS_RESOURCE = REPO_ROOT / "backend/src/main/resources/geo/gz_streets_wgs84.geojson.gz"
DEFAULT_OUTPUT_DIR = Path("/Volumes/USB DISK/pt_data/广州市/真实数据/行政区范围")
OUTPUT_BASENAME = "行政区划_街道融合"

CANONICAL_DISTRICT_ORDER = [
    "越秀区", "海珠区", "荔湾区", "天河区", "白云区", "黄埔区",
    "番禺区", "花都区", "南沙区", "从化区", "增城区",
]

# 与原 D行政区划.prj 相同的 ESRI WGS84 定义
PRJ_WKT = (
    'GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",'
    'SPHEROID["WGS_1984",6378137.0,298.257223563]],'
    'PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]'
)


def polygonal_only(geometry):
    """展开融合结果，只保留面几何。"""
    if isinstance(geometry, Polygon):
        return MultiPolygon([geometry])
    if isinstance(geometry, MultiPolygon):
        return geometry
    polygons = [g for g in getattr(geometry, "geoms", []) if isinstance(g, Polygon)]
    if not polygons:
        raise ValueError(f"融合结果不含面几何: {geometry.geom_type}")
    return MultiPolygon(polygons)


def main():
    output_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUTPUT_DIR
    if not STREETS_RESOURCE.is_file():
        raise SystemExit(f"街道面资源缺失: {STREETS_RESOURCE}")
    if not output_dir.is_dir():
        raise SystemExit(f"输出目录不存在: {output_dir}")

    existing = [p for p in output_dir.glob("*.shp") if not p.name.startswith("._")]
    conflict = [p for p in existing if p.stem != OUTPUT_BASENAME]
    if conflict:
        raise SystemExit(
            "输出目录已有其他 SHP，平台要求该目录只保留一个 SHP，请先移走: "
            + ", ".join(p.name for p in conflict)
        )

    with gzip.open(STREETS_RESOURCE, "rt", encoding="utf-8") as handle:
        streets = json.load(handle)
    features = streets.get("features") or []
    if not features:
        raise SystemExit("街道面资源不含 features")

    by_district = {}
    for feature in features:
        district = str((feature.get("properties") or {}).get("district", "")).strip()
        if not district:
            raise SystemExit("街道面资源存在缺少 district 的要素")
        geometry = shape(feature["geometry"])
        if geometry.is_empty:
            raise SystemExit(f"街道面资源存在空几何: district={district}")
        if not geometry.is_valid:
            geometry = geometry.buffer(0)
        by_district.setdefault(district, []).append(geometry)

    names = [n for n in CANONICAL_DISTRICT_ORDER if n in by_district]
    names += sorted(set(by_district) - set(names))

    districts = []
    for name in names:
        union = unary_union(by_district[name])
        if not union.is_valid:
            union = union.buffer(0)
        union = polygonal_only(union)
        # 1e-6 度网格取整（≈0.1m），与原运行时融合的 GeoJSON 输出精度一致
        union = polygonal_only(shapely.set_precision(union, 1e-6))
        districts.append((name, union))

    target = output_dir / OUTPUT_BASENAME
    with shapefile.Writer(str(target), shapeType=shapefile.POLYGON, encoding="utf-8") as writer:
        writer.field("Name", "C", size=80)
        writer.field("district", "C", size=80)
        for name, geometry in districts:
            writer.shape(geometry.__geo_interface__)
            writer.record(Name=name, district=name)
    (output_dir / f"{OUTPUT_BASENAME}.prj").write_text(PRJ_WKT, encoding="ascii")
    (output_dir / f"{OUTPUT_BASENAME}.cpg").write_text("UTF-8", encoding="ascii")

    total_streets = len(features)
    print(f"街道面 {total_streets} -> 行政区 {len(districts)}: {', '.join(names)}")
    print(f"已写出: {target}.shp/.shx/.dbf/.prj/.cpg")


if __name__ == "__main__":
    main()
