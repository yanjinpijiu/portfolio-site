# -*- coding: utf-8 -*-
"""把 ip2region 的源数据 ip.merge.txt 编译成后端用的精简索引 ip2region.bin。

为什么不用官方的 ip2region.xdb：
  1. xdb 是二进制格式，得配 org.lionsoul:ip2region 依赖才能解析；
  2. 那个文件在 GitHub 上，本机拿不到（镜像要么 404 要么要过 JS 挑战）。
源数据 ip.merge.txt 反而能通过 npm 包拿到，格式是明文：

    startIP|endIP|国家|区域|省份|城市|ISP
    1.0.1.0|1.0.3.255|中国|0|福建省|福州市|电信

所以这里自己编译一份紧凑索引。好处是零依赖、格式自己说了算、
而且只保留查询真正用得到的三级地名（国家/省/市），体积比 xdb 小。

用法：
    python tools/build_ipdb.py <ip.merge.txt 路径>

源数据获取（npm 上能下到）：
    npm view afeyer-ip2region dist.tarball   # 拿到 tarball 地址
    curl -sL -o p.tgz <tarball>
    tar -xzf p.tgz package/data/ip.merge.txt

产物：backend/src/main/resources/ip2region.bin

二进制格式（全部大端）：
    magic       4 字节 "IP2R"
    version     2 字节 uint16 = 1
    regionCount 4 字节 uint32
    entryCount  4 字节 uint32
    ---- 地名表 regionCount 条 ----
    每条：国名 [len uint16 + bytes]、省名、市名（UTF-8）
    ---- 区间表 entryCount 条，按 startIp 升序、互不重叠 ----
    每条：startIp uint32、endIp uint32、regionId uint16

数据来源 ip2region（Apache-2.0），作者 lionsoul。
"""
import ipaddress
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "backend" / "src" / "main" / "resources" / "ip2region.bin"


def clean(s: str) -> str:
    """源数据里用 '0' 表示「没有这一级」，统一清成空串。"""
    s = s.strip()
    return "" if s in ("0", "") else s


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    src = Path(sys.argv[1])
    if not src.exists():
        print("源文件不存在:", src)
        sys.exit(1)

    regions: dict[tuple, int] = {}
    region_list: list[tuple] = []
    entries: list[tuple] = []          # (start, end, regionId)

    with src.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("|")
            if len(parts) < 6:
                continue
            try:
                start = int(ipaddress.IPv4Address(parts[0]))
                end = int(ipaddress.IPv4Address(parts[1]))
            except ValueError:
                continue
            key = (clean(parts[2]), clean(parts[4]), clean(parts[5]))
            rid = regions.get(key)
            if rid is None:
                rid = len(region_list)
                regions[key] = rid
                region_list.append(key)

            # 相邻且同地名的区间合并——源数据里同一片地区常被切成很多小段
            if entries and entries[-1][2] == rid and entries[-1][1] + 1 == start:
                entries[-1] = (entries[-1][0], end, rid)
            else:
                entries.append((start, end, rid))

    entries.sort(key=lambda e: e[0])

    with OUT.open("wb") as out:
        out.write(b"IP2R")
        out.write(struct.pack(">H", 1))
        out.write(struct.pack(">I", len(region_list)))
        out.write(struct.pack(">I", len(entries)))
        for country, province, city in region_list:
            for s in (country, province, city):
                b = s.encode("utf-8")
                out.write(struct.pack(">H", len(b)))
                out.write(b)
        for start, end, rid in entries:
            out.write(struct.pack(">IIH", start, end, rid))

    size = OUT.stat().st_size
    print(f"地名 {len(region_list)} 条，区间 {len(entries)} 条")
    print(f"输出 {OUT}  {size / 1024 / 1024:.2f} MB")

    # 抽几个已知 IP 自检
    def lookup(ip: str) -> str:
        v = int(ipaddress.IPv4Address(ip))
        lo, hi = 0, len(entries) - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            s, e, rid = entries[mid]
            if v < s:
                hi = mid - 1
            elif v > e:
                lo = mid + 1
            else:
                c, p, ct = region_list[rid]
                return "".join(x for x in (c, p, ct) if x)
        return "(未命中)"

    print("自检：")
    for ip in ("1.0.1.5", "114.86.12.1", "8.8.8.8", "223.5.5.5", "127.0.0.1"):
        print(f"  {ip:16} -> {lookup(ip)}")


if __name__ == "__main__":
    main()
