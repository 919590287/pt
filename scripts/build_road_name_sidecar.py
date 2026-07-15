#!/usr/bin/env python3
"""生成走廊模块的路名边车表 backend/src/main/resources/geo/gz_road_link_names.csv.gz。

背景（2026-07 排查结论，勿凭 id 猜映射）：
- 部署模型 network.xml 的 link 无任何名称属性，id 形如 road_{base}_{seg}_{e2s|s2e}；
- base 的最大值 = 源路网 shp 行数-1，但 base **不是**任何现存 shp 的行号（构建时行序被打乱）；
- 坐标是源 shp（WGS84）直投 EPSG:3857，未做 GCJ 纠偏——可以按几何精确锚定；
- 约 39% 的 link 端点对在源 shp 中找不到同几何线段（网络简化把度-2 节点链合并成长直链），
  这些用「两端点分别落在同一命名道路折线上（±2m）」的名称交集法补配。

匹配管线：
  阶段1 端点对 1m 取整哈希精确命中 → 行 PathName；
  阶段2 两端点近邻（±2m 点到线段距离）行名交集唯一 → 该名称；
  其余 → 无名（不写入边车表）。
实测（广州市抽样模型2 + Rgaungzhou0119_cleaned）：公交经过 base 命名覆盖率 78.5%，
系数 Top12 道路全部为真实主走廊（黄埔大道西/机场路/解放北路…）。

用法：
  python3 scripts/build_road_name_sidecar.py \
      --network "/Volumes/USB DISK/pt_data/广州市/仿真数据/public/广州市抽样模型2/output/output_network.xml.gz" \
      --shp "/Volumes/USB DISK/数据/四维路网/hmx处理-全联通路网/Rgaungzhou0119_cleaned.shp" \
      --out backend/src/main/resources/geo/gz_road_link_names.csv.gz

模型网络重建后必须用新 network 重新生成（并会随资源 sha 自动使 corridor 缓存失效重建）。
新版建网脚本（build_siwei_matsim_network_v3.py）已在 link 属性内嵌 siwei_path_name，
其产出的网络无需边车表（后端名称链属性优先）。
"""
import argparse
import csv
import gzip
import math
import re
from collections import defaultdict

R = 6378137.0


def merc(lon, lat):
    return (R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)))


def key1(x, y):
    return (round(x), round(y))


def pt_seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--network", required=True, help="模型 network.xml.gz（road_* id 版）")
    parser.add_argument("--shp", required=True, help="源路网 shp（WGS84，含 PathName 字段）")
    parser.add_argument("--out", required=True, help="输出 csv.gz（base,路名）")
    parser.add_argument("--tol", type=float, default=2.0, help="端点到道路折线的容差（米）")
    parser.add_argument("--grid", type=float, default=60.0, help="空间网格边长（米）")
    args = parser.parse_args()

    import shapefile  # pyshp

    reader = shapefile.Reader(args.shp)
    row_name = [str(rec["PathName"]).strip() for rec in reader.iterRecords(fields=["PathName"])]
    seg_exact = defaultdict(list)
    pt_grid = defaultdict(list)
    segs_flat = []
    for idx, shape in enumerate(reader.iterShapes()):
        pts = [merc(*p) for p in shape.points]
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]
            seg_exact[(key1(*a), key1(*b))].append(idx)
            seg_exact[(key1(*b), key1(*a))].append(idx)
            si = len(segs_flat)
            segs_flat.append((idx, a[0], a[1], b[0], b[1]))
            for gx in range(int(min(a[0], b[0]) // args.grid), int(max(a[0], b[0]) // args.grid) + 1):
                for gy in range(int(min(a[1], b[1]) // args.grid), int(max(a[1], b[1]) // args.grid) + 1):
                    pt_grid[(gx, gy)].append(si)
    print(f"shp rows={len(row_name)} segs={len(segs_flat)}")

    def rows_near(p):
        gx, gy = int(p[0] // args.grid), int(p[1] // args.grid)
        rows = set()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for si in pt_grid.get((gx + dx, gy + dy), ()):
                    idx, ax, ay, bx, by = segs_flat[si]
                    if idx in rows:
                        continue
                    if pt_seg_dist(p[0], p[1], ax, ay, bx, by) <= args.tol:
                        rows.add(idx)
        return rows

    node_re = re.compile(r'<node id="(road_node_\d+)" x="([\d.E]+)" y="([\d.E]+)"')
    link_re = re.compile(r'<link id="road_(\d+)_\d+_(?:e2s|s2e)" from="(road_node_\d+)" to="(road_node_\d+)"')
    nodes = {}
    base_segs = defaultdict(list)
    with gzip.open(args.network, "rt") as f:
        for line in f:
            m = node_re.search(line)
            if m:
                nodes[m.group(1)] = (float(m.group(2)), float(m.group(3)))
                continue
            m = link_re.search(line)
            if m:
                b = int(m.group(1))
                if len(base_segs[b]) < 4:
                    base_segs[b].append((m.group(2), m.group(3)))
    print(f"network road bases={len(base_segs)}")

    names = {}
    stage = defaultdict(int)
    for base, segs in base_segs.items():
        got = None
        for fid, tid in segs:
            rows = seg_exact.get((key1(*nodes[fid]), key1(*nodes[tid])))
            if rows:
                vals = {row_name[i] for i in rows if row_name[i]}
                if vals:
                    got = sorted(vals)[0]
                    stage["exact"] += 1
                break
        if got:
            names[base] = got
            continue
        f, t = segs[0]
        na = {row_name[i] for i in rows_near(nodes[f]) if row_name[i]}
        nb = {row_name[i] for i in rows_near(nodes[t]) if row_name[i]}
        common = na & nb
        if len(common) == 1:
            names[base] = next(iter(common))
            stage["endpoint"] += 1
        elif len(common) > 1:
            stage["ambiguous"] += 1
    print(f"stages={dict(stage)} named={len(names)}")

    with gzip.open(args.out, "wt", newline="") as f:
        writer = csv.writer(f)
        for base in sorted(names):
            writer.writerow([base, names[base]])
    print(f"written: {args.out}")


if __name__ == "__main__":
    main()
