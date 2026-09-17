# -*- coding: utf-8 -*-
"""把 AreaCity 的 ok_geo.csv 编译成前端用的全国市级 GeoJSON。

为什么要自己编译：ECharts 6 的 npm 包里不含任何地图文件，而现成的全国市级底图
（echarts@4.9.0 的 china-cities.json）数据停在 2016 年、名字还不带「市」后缀，
和 ip2region 解析出来的城市名对不上。AreaCity 这份是 2026-04 采集的省市区三级边界，
仓库 MIT，质量明显更好。

数据来源（约 16.5 MB 的 7z，解出 159 MB 的 CSV）：
    https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases
国内直连 GitHub Releases 不通，用镜像或代理：
    curl -x http://127.0.0.1:7890 -L -o ok_geo.csv.7z \\
      https://gh-proxy.org/https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/download/2025.251231.260403/ok_geo.csv.7z
解压需要 py7zr（pip install py7zr）。

CSV 结构：
    id,pid,deep,name,ext_path,geo,polygon
    11,0,0,"北京市","北京市","116.407387 39.904179","115.42 39.96,115.42 39.97,..."
    deep 0 = 省、1 = 市、2 = 区县
    polygon 里 `;` 分隔多个部件（飞地、岛屿），`,` 分隔点，点是「经度 纬度」

用法：
    python tools/build_city_geo.py <ok_geo.csv 路径> [--level 1] [--precision 5]

产物：frontend/src/assets/china-cities-geo.json（还没简化，简化交给 mapshaper）

为什么要再跑一次 mapshaper：原始市级有 220 万个点、50 MB，直接进仓库不合适。
    npx --yes mapshaper@0.7.61 <输入> -simplify 10% keep-shapes -o <输出>

数据来源 AreaCity-JsSpider-StatsGov（MIT，代码与转换工具）；上游数据为
国家地名信息库 + 高德 / 腾讯行政区划。
"""
import argparse
import csv
import io
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "frontend" / "src" / "assets" / "china-cities-geo.json"

# 159 MB 的 CSV 里有单个超长字段（一个市的全部边界坐标），默认上限 128 KB 会直接报错
csv.field_size_limit(10 ** 9)


def parse_polygon(raw: str, precision: int):
    """把 `lng lat,lng lat;lng lat,...` 解析成 MultiPolygon 的 coordinates。

    每个部件当成一个外环。这份数据里没有内环（洞），所以不用区分
    ——真出现了也只是少画一个洞，不会画错形状。

    小数位要截：源数据给到 6 位（约 0.1 米），而全国地图上一个像素是公里级，
    多出来的位数只是白占体积。默认留 5 位（约 1 米），已经远超肉眼可辨。
    """
    rings = []
    for part in raw.split(";"):
        part = part.strip()
        if not part:
            continue
        ring = []
        for pair in part.split(","):
            pair = pair.strip()
            if not pair:
                continue
            lng, _, lat = pair.partition(" ")
            try:
                ring.append([round(float(lng), precision), round(float(lat), precision)])
            except ValueError:
                # 有的单元没有边界数据，polygon 字段是「EMPTY」而不是空串。
                # 整行丢掉，别让它变成一堆 NaN 坐标
                return []
        # GeoJSON 要求外环首尾闭合，数据本身就是闭合的，这里只兜一下
        if len(ring) >= 4 and ring[0] != ring[-1]:
            ring.append(ring[0])
        if len(ring) >= 4:
            rings.append(ring)
    return rings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", help="ok_geo.csv 的路径")
    ap.add_argument("--level", type=int, default=1, help="要导出的层级：1 = 市级（默认）")
    ap.add_argument("--precision", type=int, default=5, help="坐标保留几位小数，默认 5")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args()

    src = Path(args.csv)
    if not src.exists():
        print("找不到源文件:", src)
        sys.exit(1)

    features = []
    points = 0
    skipped = 0

    with io.open(src, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if row["deep"] != str(args.level):
                continue
            name = (row["name"] or "").strip()
            rings = parse_polygon(row["polygon"] or "", args.precision)
            if not name or not rings:
                skipped += 1
                continue

            # 市级 id 是 4 位「城市码」（北京 1101、石家庄 1301），
            # 补两个 0 就是通用的 6 位行政区划代码，以后要按代码关联不用再改数据
            code = row["id"].strip()
            adcode = int(code + "00") if len(code) == 4 and code.isdigit() else None

            points += sum(len(r) for r in rings)
            features.append({
                "type": "Feature",
                "properties": {
                    "name": name,
                    "adcode": adcode,
                    # 中心点用不上，但留着方便以后做散点或标签
                    "center": (lambda g: [float(x) for x in g.split()] if g else None)(row["geo"]),
                },
                "geometry": {"type": "MultiPolygon", "coordinates": [[r] for r in rings]},
            })

    geo = {"type": "FeatureCollection", "features": features}
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # separators 去掉多余空格：这份文件会进仓库，能省一点是一点
    with out.open("w", encoding="utf-8") as f:
        json.dump(geo, f, ensure_ascii=False, separators=(",", ":"))

    # 再单独导一份「只含名字」的小文件（几 KB）。
    # 前端要拿它把 ip2region 给的城市名对齐到底图上的名字（延边 → 延边朝鲜族自治州），
    # 但为了这个去 import 4 MB 的底图不划算——底图本身交给 ECharts 按需加载
    names_only = Path(out).with_name("china-cities-names.json")
    with names_only.open("w", encoding="utf-8") as f:
        json.dump([f2["properties"]["name"] for f2 in features], f, ensure_ascii=False, separators=(",", ":"))

    size = out.stat().st_size
    print(f"要素 {len(features)} 个（跳过 {skipped}），坐标点 {points} 个")
    print(f"输出 {out}  {size / 1024 / 1024:.2f} MB")
    print(f"输出 {names_only}  {names_only.stat().st_size / 1024:.1f} KB")
    names = [f["properties"]["name"] for f in features]
    for probe in ("北京市", "杭州市", "温州市", "香港特别行政区"):
        print(f"  {probe} 在不在: {probe in names}")


if __name__ == "__main__":
    main()
