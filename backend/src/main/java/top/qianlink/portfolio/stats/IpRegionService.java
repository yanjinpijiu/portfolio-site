package top.qianlink.portfolio.stats;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * IP → 归属地。读的是 resources/ip2region.bin，纯内存二分查找。
 *
 * <p>这个 bin 不是官方的 ip2region.xdb，而是我用 {@code tools/build_ipdb.py}
 * 从 ip.merge.txt 源数据编译出来的精简索引（只保留国/省/市三级）。原因见那个脚本的注释：
 * xdb 在 GitHub 上本机拿不到，而且引它还要多一个 Maven 依赖。自己编译的格式更简单，
 * 体积也更小。
 *
 * <p>文件读不到时整体降级：所有查询返回 null，地区维度在看板上就不显示，
 * 其他统计照常。启动时只打一条 warn，不影响服务起来。
 */
@Slf4j
@Service
public class IpRegionService {

    private static final int MAGIC = 0x49503252; // "IP2R"

    /** 区间表：按 startIp 升序、互不重叠。用 int 装 uint32，比较时按无符号走 */
    private int[] starts = new int[0];
    private int[] ends = new int[0];
    private short[] regionIds = new short[0];

    /** 地名表，下标即 regionId */
    private String[] countries = new String[0];
    private String[] provinces = new String[0];
    private String[] cities = new String[0];

    private volatile boolean ready = false;

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource("ip2region.bin");
        if (!resource.exists()) {
            log.warn("没找到 ip2region.bin，地区统计将不可用（其余功能不受影响）");
            return;
        }
        try (InputStream raw = resource.getInputStream();
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {

            if (in.readInt() != MAGIC) {
                log.warn("ip2region.bin 魔数不对，地区统计不可用");
                return;
            }
            int version = in.readUnsignedShort();
            if (version != 1) {
                log.warn("ip2region.bin 版本 {} 不认识，地区统计不可用", version);
                return;
            }
            int regionCount = in.readInt();
            int entryCount = in.readInt();

            countries = new String[regionCount];
            provinces = new String[regionCount];
            cities = new String[regionCount];
            for (int i = 0; i < regionCount; i++) {
                countries[i] = readUtf(in);
                provinces[i] = readUtf(in);
                cities[i] = readUtf(in);
            }

            starts = new int[entryCount];
            ends = new int[entryCount];
            regionIds = new short[entryCount];
            for (int i = 0; i < entryCount; i++) {
                starts[i] = in.readInt();
                ends[i] = in.readInt();
                regionIds[i] = in.readShort();
            }

            ready = true;
            log.info("IP 归属地库已加载：{} 个地名，{} 条区间", regionCount, entryCount);
        } catch (Exception e) {
            log.warn("加载 ip2region.bin 失败，地区统计不可用", e);
        }
    }

    private static String readUtf(DataInputStream in) throws Exception {
        int len = in.readUnsignedShort();
        if (len == 0) {
            return "";
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 归属地三元组。查不到时三个字段都是 null。 */
    public record Region(String country, String province, String city) {
        public static final Region UNKNOWN = new Region(null, null, null);
    }

    /**
     * 查 IP 归属地。IPv6、内网地址、非法值都返回 UNKNOWN。
     * 这个方法是纯内存操作，没有 IO，埋点链路上调用是安全的。
     */
    public Region lookup(String ip) {
        if (!ready || ip == null || ip.isBlank()) {
            return Region.UNKNOWN;
        }
        long value = toUnsigned(ip);
        if (value < 0) {
            return Region.UNKNOWN;
        }
        int v = (int) value;

        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            // 区间是 uint32，可能跨过 2^31，必须按无符号比较
            if (Integer.compareUnsigned(v, starts[mid]) < 0) {
                hi = mid - 1;
            } else if (Integer.compareUnsigned(v, ends[mid]) > 0) {
                lo = mid + 1;
            } else {
                int rid = regionIds[mid] & 0xFFFF;
                if (rid >= countries.length) {
                    return Region.UNKNOWN;
                }
                String country = emptyToNull(countries[rid]);
                String province = emptyToNull(provinces[rid]);
                String city = emptyToNull(cities[rid]);
                return new Region(country, province, city);
            }
        }
        return Region.UNKNOWN;
    }

    /** 把 IPv4 字符串转成 uint32；不是合法的 IPv4 就返回 -1 */
    private static long toUnsigned(String ip) {
        int a = -1, b = 0, c = 0, d = 0, part = 0, value = 0;
        for (int i = 0; i < ip.length(); i++) {
            char ch = ip.charAt(i);
            if (ch == '.') {
                switch (part) {
                    case 0 -> a = value;
                    case 1 -> b = value;
                    case 2 -> c = value;
                    default -> {
                        return -1;
                    }
                }
                part++;
                value = 0;
            } else if (ch >= '0' && ch <= '9') {
                value = value * 10 + (ch - '0');
                if (value > 255) {
                    return -1;
                }
            } else {
                return -1;
            }
        }
        if (part != 3) {
            return -1;
        }
        d = value;
        if (a < 0) {
            return -1;
        }
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
