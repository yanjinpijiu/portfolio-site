package top.qianlink.portfolio.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.qianlink.portfolio.domain.ApiAccessLog;
import top.qianlink.portfolio.domain.LogQuery;
import top.qianlink.portfolio.domain.ResumeDownloadLog;
import top.qianlink.portfolio.domain.VisitLog;
import top.qianlink.portfolio.mapper.ApiAccessLogMapper;
import top.qianlink.portfolio.mapper.ResumeDownloadLogMapper;
import top.qianlink.portfolio.mapper.VisitLogMapper;
import top.qianlink.portfolio.stats.AsyncLogWriter;
import top.qianlink.portfolio.stats.IpRegionService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据看板的聚合查询。
 *
 * <p>指标口径和图表形态照着短链那套来（PV/UV/UIP、24 小时、星期、地区、终端、高频 IP、
 * 新老访客、接口调用量、登录专项、下载排行），但<b>不照搬它的存储架构</b>：
 * 短链是 Redis Stream + 8 张按天预聚合表 + ShardingSphere 16 分表，给秒杀级 QPS
 * 和千万行明细用的；这里单实例 + H2 + 日均几十 PV，量级差四个数量级，
 * 直接查明细表 GROUP BY 加索引就够了。真慢了再上预聚合。
 *
 * <p>两个贯穿全篇的约定：
 * <ul>
 *   <li><b>不在 SQL 里调时间函数</b>——时间维度在埋点入库时就写成了普通列
 *       （visit_date / visit_hour / visit_weekday），这里只做 GROUP BY；</li>
 *   <li><b>返回的行统一转小写键名再取值</b>——H2 返回的列标签大小写不保证，
 *       不归一化的话「本地能跑、线上全是 0」这类最难查的问题就会出现（见 {@link #lower}）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StatsQueryService {

    /** 天数上限，防止 days=999999 把整库扫一遍 */
    private static final int MAX_DAYS = 365;

    /** 日志页每页条数：默认值和上限。日志是给自己翻的，给太大只会拖着库陪跑 */
    private static final int DEFAULT_LOG_SIZE = 50;
    private static final int MAX_LOG_SIZE = 200;

    /** 导出行数上限。超过就截断，文件名上会标出来 */
    private static final int MAX_EXPORT_ROWS = 50000;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VisitLogMapper visitLogMapper;
    private final ApiAccessLogMapper apiAccessLogMapper;
    private final ResumeDownloadLogMapper downloadLogMapper;
    private final IpRegionService ipRegionService;
    private final AsyncLogWriter writer;

    /* ================= 概览 ================= */

    public Map<String, Object> overview(int days) {
        int d = clampDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(d - 1L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("today", lower(visitLogMapper.summary(today.atStartOfDay())));
        result.put("range", lower(visitLogMapper.summary(from.atStartOfDay())));
        result.put("api", lower(apiAccessLogMapper.summary(from.atStartOfDay())));
        result.put("download", lower(downloadLogMapper.summary(from.atStartOfDay())));
        // 埋点写库「队列满了就丢」，所以要把丢弃数露出来，不然少记了都不知道
        result.put("droppedLogs", writer.getDroppedCount());
        result.put("pendingLogs", writer.getPendingCount());
        return result;
    }

    /* ================= 访客 ================= */

    public Map<String, Object> visits(int days) {
        int d = clampDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(d - 1L);
        LocalDateTime fromTime = from.atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("from", from.toString());
        result.put("to", today.toString());

        result.put("daily", dailyWithNewVsReturning(from, today, fromTime));
        result.put("hourly", fillByIndex(visitLogMapper.hourly(fromTime), "h", "hour", 0, 23,
                cols("pv", "pv", "uv", "uv")));
        result.put("weekday", fillByIndex(visitLogMapper.weekday(fromTime), "w", "weekday", 1, 7,
                cols("pv", "pv", "uv", "uv")));
        result.put("province", simplify(visitLogMapper.byProvince(fromTime), "name", "pv", "uv"));
        result.put("city", simplify(visitLogMapper.byCity(fromTime), "province", "city", "pv", "uv"));
        // 地图和省份榜都按 province 分组，解析不出省的行直接消失。
        // 不把这个数露出来，看的人会以为「图上的数加不出总数」是 bug
        result.put("unlocated", visitLogMapper.countUnlocated(fromTime));
        result.put("browser", simplify(visitLogMapper.byBrowser(fromTime), "name", "pv", "uv"));
        result.put("os", simplify(visitLogMapper.byOs(fromTime), "name", "pv", "uv"));
        result.put("device", simplify(visitLogMapper.byDevice(fromTime), "name", "pv", "uv"));
        result.put("pageType", simplify(visitLogMapper.byPageType(fromTime), "name", "pv", "uv"));
        result.put("project", simplify(visitLogMapper.byProject(fromTime), "name", "pv", "uv"));
        result.put("topIps", topIps(fromTime));
        result.put("recent", recentVisits(50));
        return result;
    }

    /**
     * 按天的 PV/UV/UIP + 新老访客。
     *
     * <p>没有数据的天要补 0：不补的话折线图的 X 轴会跳过没人访问的日子，
     * 看起来像是天天都有人来。新访客 = 当天首次到访的人，老访客 = 当天 UV 减去新访客。
     */
    private List<Map<String, Object>> dailyWithNewVsReturning(LocalDate from, LocalDate to,
                                                             LocalDateTime fromTime) {
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        for (Map<String, Object> row : visitLogMapper.dailyTrend(fromTime)) {
            Map<String, Object> r = lower(row);
            String key = dateStr(r.get("d"));
            if (key != null) {
                byDate.put(key, r);
            }
        }
        Map<String, Long> freshByDate = new HashMap<>();
        for (Map<String, Object> row : visitLogMapper.newVisitorsByDay(from)) {
            Map<String, Object> r = lower(row);
            String key = dateStr(r.get("d"));
            if (key != null) {
                freshByDate.put(key, num(r, "fresh"));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String key = day.toString();
            Map<String, Object> row = byDate.get(key);
            long uv = row == null ? 0 : num(row, "uv");
            long fresh = freshByDate.getOrDefault(key, 0L);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", key);
            item.put("pv", row == null ? 0L : num(row, "pv"));
            item.put("uv", uv);
            item.put("uip", row == null ? 0L : num(row, "uip"));
            item.put("fresh", fresh);
            // 理论上 fresh <= uv，但两者来自不同查询，跨零点时可能对不上，
            // 负数会被图表画成怪东西，所以兜一下
            item.put("returning", Math.max(0L, uv - fresh));
            out.add(item);
        }
        return out;
    }

    /** 高频 IP，顺便把归属地补上（内存查表，不用再走一次 SQL） */
    private List<Map<String, Object>> topIps(LocalDateTime fromTime) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : visitLogMapper.topIps(fromTime)) {
            Map<String, Object> r = lower(row);
            String ip = str(r.get("ip"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ip", ip);
            item.put("pv", num(r, "pv"));
            item.put("uv", num(r, "uv"));
            item.put("pages", num(r, "pages"));
            item.put("region", joinRegion(ipRegionService.lookup(ip)));
            out.add(item);
        }
        return out;
    }

    private List<VisitLog> recentVisits(int limit) {
        return visitLogMapper.recent(clampLimit(limit));
    }

    /* ================= 接口 ================= */

    /**
     * @param includeInternal 是否把后台自己调的接口也算进来。默认不算——不然
     *                        「接口调用量排行」全是自己在后台点出来的请求
     */
    public Map<String, Object> api(int days, boolean includeInternal) {
        int d = clampDays(days);
        LocalDate from = LocalDate.now().minusDays(d - 1L);
        LocalDateTime fromTime = from.atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("from", from.toString());
        result.put("includeInternal", includeInternal);
        result.put("summary", lower(apiAccessLogMapper.summary(fromTime)));

        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> row : apiAccessLogMapper.topPaths(fromTime)) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", str(r.get("path")));
            item.put("method", str(r.get("method")));
            item.put("count", num(r, "cnt"));
            item.put("fail", num(r, "fail"));
            item.put("avgMs", dbl(r, "avg_ms"));
            item.put("maxMs", num(r, "max_ms"));
            top.add(item);
            if (top.size() >= 20) {
                break;
            }
        }
        result.put("top", top);

        // 样本太少的接口平均耗时没有意义：一次 3 秒的偶发就能排到第一
        List<Map<String, Object>> slow = new ArrayList<>();
        for (Map<String, Object> row : apiAccessLogMapper.slowPaths(fromTime)) {
            Map<String, Object> r = lower(row);
            long cnt = num(r, "cnt");
            if (cnt < 5) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", str(r.get("path")));
            item.put("method", str(r.get("method")));
            item.put("count", cnt);
            item.put("avgMs", dbl(r, "avg_ms"));
            item.put("maxMs", num(r, "max_ms"));
            slow.add(item);
            if (slow.size() >= 10) {
                break;
            }
        }
        result.put("slow", slow);

        result.put("daily", seriesOf(apiAccessLogMapper.dailyTrend(fromTime), "d",
                cols("cnt", "count", "fail", "fail", "inner_cnt", "internal")));

        // 登录接口专项：有没有人在试密钥，看失败曲线就知道
        Map<String, Object> login = new LinkedHashMap<>();
        login.put("trend", seriesOf(apiAccessLogMapper.loginTrend(fromTime), "d",
                cols("cnt", "count", "fail", "fail")));
        List<Map<String, Object>> failByIp = new ArrayList<>();
        for (Map<String, Object> row : apiAccessLogMapper.loginFailByIp(fromTime)) {
            Map<String, Object> r = lower(row);
            String ip = str(r.get("ip"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ip", ip);
            item.put("count", num(r, "cnt"));
            item.put("lastAt", timeStr(r.get("last_at")));
            item.put("region", joinRegion(ipRegionService.lookup(ip)));
            failByIp.add(item);
            if (failByIp.size() >= 20) {
                break;
            }
        }
        login.put("failByIp", failByIp);
        login.put("byHour", fillByIndex(apiAccessLogMapper.loginByHour(fromTime), "h", "hour", 0, 23,
                cols("cnt", "count", "fail", "fail")));
        result.put("login", login);

        // 下载接口专项：0 成功 / 428 被验证码拦 / 429 被限流
        List<Map<String, Object>> byCode = new ArrayList<>();
        for (Map<String, Object> row : apiAccessLogMapper.downloadByCode(fromTime)) {
            Map<String, Object> r = lower(row);
            int code = (int) num(r, "code");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("label", codeLabel(code));
            item.put("count", num(r, "cnt"));
            byCode.add(item);
        }
        result.put("download", Map.of("byCode", byCode));
        return result;
    }

    /** 业务码 → 人话，看板上直接显示这个，不用记 428 是什么 */
    private static String codeLabel(int code) {
        return switch (code) {
            case 0 -> "下载成功";
            case 400 -> "参数错误";
            case 401 -> "鉴权失败";
            case 404 -> "资源不存在";
            case 428 -> "被验证码拦下";
            case 429 -> "被限流拦下";
            case 500 -> "服务异常";
            default -> "其他（" + code + "）";
        };
    }

    /* ================= 简历下载 ================= */

    public Map<String, Object> resumes(int days, Long resumeId) {
        int d = clampDays(days);
        LocalDate from = LocalDate.now().minusDays(d - 1L);
        LocalDateTime fromTime = from.atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("from", from.toString());
        result.put("summary", lower(downloadLogMapper.summary(fromTime)));

        List<Map<String, Object>> byResume = new ArrayList<>();
        for (Map<String, Object> row : downloadLogMapper.byResume(fromTime)) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resumeId", num(r, "resume_id"));
            item.put("title", str(r.get("title")));
            item.put("direction", str(r.get("direction")));
            item.put("count", num(r, "cnt"));
            item.put("uv", num(r, "uv"));
            item.put("freeCount", num(r, "free_cnt"));
            item.put("captchaCount", Math.max(0L, num(r, "cnt") - num(r, "free_cnt")));
            byResume.add(item);
        }
        result.put("byResume", byResume);

        result.put("daily", seriesOf(downloadLogMapper.dailyTrend(fromTime), "d",
                cols("cnt", "count", "uv", "uv")));
        if (resumeId != null) {
            result.put("trend", seriesOf(downloadLogMapper.trendByResume(resumeId, fromTime), "d",
                    cols("cnt", "count", "uv", "uv")));
        }

        // 下载来源按省市两级一次查出来，前端切省 / 市两档用同一份数据。
        // 原来只有省级，想看「具体是哪个市的」就得再加一条 SQL
        List<Map<String, Object>> region = new ArrayList<>();
        for (Map<String, Object> row : downloadLogMapper.byCity(fromTime)) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("province", str(r.get("province")));
            item.put("city", str(r.get("city")));
            item.put("count", num(r, "cnt"));
            item.put("uv", num(r, "uv"));
            region.add(item);
        }
        result.put("region", region);

        // 是哪个 IP 下的、在哪儿、下了几次。归属地规则和访问日志那套完全一致。
        // 只留前 20：这一块的用途是「看看有哪些人在关注我」，不是做全量审计
        List<Map<String, Object>> byIp = new ArrayList<>();
        for (Map<String, Object> row : downloadLogMapper.byIp(fromTime)) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ip", str(r.get("ip")));
            item.put("region", joinRegion(new IpRegionService.Region(
                    str(r.get("country")), str(r.get("province")), str(r.get("city")))));
            item.put("count", num(r, "cnt"));
            // 同一个出口 IP 下换台机器就是另一个人：区分「一个人下 5 次」和「五个人各下 1 次」
            item.put("uv", num(r, "uv"));
            item.put("resumes", num(r, "resumes"));
            item.put("lastAt", timeStr(r.get("last_at")));
            byIp.add(item);
            if (byIp.size() >= 20) {
                break;
            }
        }
        result.put("byIp", byIp);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (ResumeDownloadLog log : downloadLogMapper.recent(50)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", log.getResumeTitle());
            item.put("direction", log.getDirection());
            item.put("ip", log.getIp());
            item.put("region", joinRegionRaw(log.getProvince(), log.getCity()));
            item.put("freePass", log.getFreePass());
            item.put("at", log.getCreatedAt() == null ? null : log.getCreatedAt().format(TIME_FMT));
            recent.add(item);
        }
        result.put("recent", recent);
        return result;
    }

    /* ================= 日志检索 ================= */

    /**
     * 按条件查一页日志。访问日志和接口日志共用，靠 {@code type} 分流。
     *
     * <p>和上面那些聚合查询的区别：这里<b>不能</b>把整个时间窗的结果捞回内存再切页，
     * 必须让数据库做 {@code LIMIT/OFFSET}。聚合是「每天一行」，日志是「每次访问一行」，
     * 量级差几百倍，拉回内存迟早会出事。
     */
    public Map<String, Object> logs(LogQuery query) {
        prepare(query);

        String type = query.getType();
        long total = switch (type) {
            case "api" -> apiAccessLogMapper.countLogs(query);
            case "download" -> downloadLogMapper.countLogs(query);
            default -> visitLogMapper.countLogs(query);
        };
        int pages = (int) ((total + query.getSize() - 1) / query.getSize());
        // 停在最后一页之外（比如刚删过几行）就退到最后一页，而不是给一张空表
        if (pages > 0 && query.getPage() > pages) {
            query.setPage(pages);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("from", query.getFrom().toString());
        result.put("to", query.getTo().toString());
        result.put("page", query.getPage());
        result.put("size", query.getSize());
        result.put("total", total);
        result.put("pages", pages);
        result.put("rows", switch (type) {
            case "api" -> apiRows(query);
            case "download" -> downloadRows(query);
            default -> visitRows(query);
        });
        if ("visit".equals(type)) {
            // 「这个人明明来过，地图上却没有」是这一块最常见的困惑，直接给出解释
            result.put("unlocated", visitLogMapper.countLogsUnlocated(query));
        }
        return result;
    }

    /**
     * 筛选下拉的候选值。跟着类型和日期走，列的是范围内真实出现过的值，
     * 不是写死的清单，也不是全表 DISTINCT。
     */
    public Map<String, Object> logOptions(LogQuery query) {
        prepare(query);
        String type = query.getType();
        LocalDate from = query.getFrom();
        LocalDate to = query.getTo();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("from", from.toString());
        result.put("to", to.toString());

        if ("api".equals(type)) {
            // api_access_log 只存了 IP，没有地区、终端这些列，对应下拉一律给空
            result.put("paths", apiAccessLogMapper.distinctPaths(from, to));
            result.put("pageTypes", List.of());
            result.put("regions", List.of());
            result.put("devices", List.of());
            result.put("browsers", List.of());
            result.put("oses", List.of());
            return result;
        }

        if ("download".equals(type)) {
            // 下载日志有地区，但没有路径和终端
            result.put("paths", List.of());
            result.put("pageTypes", List.of());
            result.put("regions", regionsOf(downloadLogMapper.distinctRegions(from, to)));
            result.put("devices", List.of());
            result.put("browsers", List.of());
            result.put("oses", List.of());
            return result;
        }

        result.put("paths", visitLogMapper.distinctPaths(from, to));
        result.put("pageTypes", visitLogMapper.distinctPageTypes(from, to));
        result.put("regions", regionsOf(visitLogMapper.distinctRegions(from, to)));

        // 浏览器 / 系统 / 设备一次查回来，在这里拆成三个去重列表
        Set<String> browsers = new LinkedHashSet<>();
        Set<String> oses = new LinkedHashSet<>();
        Set<String> devices = new LinkedHashSet<>();
        for (Map<String, Object> row : visitLogMapper.distinctTerminals(from, to)) {
            Map<String, Object> r = lower(row);
            addIfPresent(browsers, str(r.get("browser")));
            addIfPresent(oses, str(r.get("os")));
            addIfPresent(devices, str(r.get("device")));
        }
        result.put("browsers", new ArrayList<>(browsers));
        result.put("oses", new ArrayList<>(oses));
        result.put("devices", new ArrayList<>(devices));
        return result;
    }

    /** 把 DISTINCT 出来的 country/province/city 三元组统一成前端要的形状 */
    private static List<Map<String, Object>> regionsOf(List<Map<String, Object>> rows) {
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("country", str(r.get("country")));
            item.put("province", str(r.get("province")));
            item.put("city", str(r.get("city")));
            regions.add(item);
        }
        return regions;
    }

    /** 导出的表头（和 {@link #logRowsForExport} 的列一一对应） */
    public List<String> logExportHeader(LogQuery query) {
        return switch (query.getType()) {
            case "api" -> List.of("时间", "路径", "方法", "业务码", "HTTP", "耗时(ms)", "IP",
                    "归属地", "访客ID", "后台自己");
            case "download" -> List.of("时间", "简历", "方向", "IP", "归属地", "访客ID", "放行方式");
            default -> List.of("时间", "路径", "页面类型", "IP", "归属地", "访客ID",
                    "浏览器", "系统", "设备", "来源");
        };
    }

    /**
     * 导出用的整份结果。上限 {@link #MAX_EXPORT_ROWS} 行，超了截断。
     *
     * <p>一次性查出来拼字符串，没有走游标：现在最大的一张表（api_access_log）
     * 也就几百行，5 万行的上限下最坏占几十 MB 内存。真有一天导到上限了再改游标。
     */
    public List<List<String>> logRowsForExport(LogQuery query) {
        prepare(query);
        query.setPage(1);
        query.setSize(MAX_EXPORT_ROWS);

        List<List<String>> rows = new ArrayList<>();
        switch (query.getType()) {
            case "api" -> {
                for (Map<String, Object> r : apiRows(query)) {
                    rows.add(List.of(
                            nz(r.get("at")), nz(r.get("path")), nz(r.get("method")),
                            nz(r.get("bizCode")), nz(r.get("httpStatus")), nz(r.get("durationMs")),
                            nz(r.get("ip")), nz(r.get("region")), nz(r.get("visitorId")),
                            Boolean.TRUE.equals(r.get("internal")) ? "是" : "否"));
                }
            }
            case "download" -> {
                for (Map<String, Object> r : downloadRows(query)) {
                    rows.add(List.of(
                            nz(r.get("at")), nz(r.get("title")), nz(r.get("direction")),
                            nz(r.get("ip")), nz(r.get("region")), nz(r.get("visitorId")),
                            Boolean.TRUE.equals(r.get("freePass")) ? "免验证额度" : "过验证码"));
                }
            }
            default -> {
                for (Map<String, Object> r : visitRows(query)) {
                    rows.add(List.of(
                            nz(r.get("at")), nz(r.get("path")), nz(r.get("pageType")),
                            nz(r.get("ip")), nz(r.get("region")), nz(r.get("visitorId")),
                            nz(r.get("browser")), nz(r.get("os")), nz(r.get("device")),
                            nz(r.get("referer"))));
                }
            }
        }
        return rows;
    }

    /** 导出会截断吗。前端在文件名上标一下，免得拿到一份不完整的表还以为导全了 */
    public boolean logExportTruncated(LogQuery query) {
        long total = switch (query.getType()) {
            case "api" -> apiAccessLogMapper.countLogs(query);
            case "download" -> downloadLogMapper.countLogs(query);
            default -> visitLogMapper.countLogs(query);
        };
        return total > MAX_EXPORT_ROWS;
    }

    private static boolean isApi(LogQuery query) {
        return "api".equals(query.getType());
    }

    /**
     * 把查询条件补全并夹到合法范围。三个入口（分页、候选项、导出）都要先过这里，
     * 否则 {@code size=99999999} 能算出个负的 offset，SQL 直接报错。
     */
    private static void prepare(LogQuery query) {
        query.normalize();

        if (query.getSize() <= 0) {
            query.setSize(DEFAULT_LOG_SIZE);
        } else {
            query.setSize(Math.min(query.getSize(), MAX_LOG_SIZE));
        }
        query.setPage(Math.max(1, query.getPage()));

        LocalDate today = LocalDate.now();
        if (query.getTo() == null) {
            query.setTo(today);
        }
        if (query.getFrom() == null) {
            query.setFrom(query.getTo());
        }
        // 起止写反了就当没写反。翻日志时的手滑不值得弹一个错误出来
        if (query.getFrom().isAfter(query.getTo())) {
            LocalDate swap = query.getFrom();
            query.setFrom(query.getTo());
            query.setTo(swap);
        }
    }

    private List<Map<String, Object>> visitRows(LogQuery query) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VisitLog log : visitLogMapper.pageLogs(query)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("at", timeStr(log.getCreatedAt()));
            item.put("path", log.getPath());
            item.put("pageType", log.getPageType());
            item.put("ip", log.getIp());
            item.put("region", joinRegion(new IpRegionService.Region(
                    log.getCountry(), log.getProvince(), log.getCity())));
            item.put("visitorId", log.getVisitorId());
            item.put("browser", log.getBrowser());
            item.put("os", log.getOs());
            item.put("device", log.getDevice());
            item.put("referer", log.getReferer());
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> apiRows(LogQuery query) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiAccessLog log : apiAccessLogMapper.pageLogs(query)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("at", timeStr(log.getCreatedAt()));
            item.put("path", log.getPath());
            item.put("method", log.getMethod());
            item.put("bizCode", log.getBizCode());
            item.put("httpStatus", log.getHttpStatus());
            item.put("durationMs", log.getDurationMs());
            item.put("ip", log.getIp());
            // 这张表没存地区，现查一次内存表补上（纯内存二分，没有 IO）
            item.put("region", joinRegion(ipRegionService.lookup(log.getIp())));
            item.put("visitorId", log.getVisitorId());
            item.put("internal", Boolean.TRUE.equals(log.getInternal()));
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> downloadRows(LogQuery query) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResumeDownloadLog log : downloadLogMapper.pageLogs(query)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("at", timeStr(log.getCreatedAt()));
            item.put("resumeId", log.getResumeId());
            item.put("title", log.getResumeTitle());
            item.put("direction", log.getDirection());
            item.put("ip", log.getIp());
            item.put("region", joinRegion(new IpRegionService.Region(
                    log.getCountry(), log.getProvince(), log.getCity())));
            item.put("visitorId", log.getVisitorId());
            item.put("freePass", Boolean.TRUE.equals(log.getFreePass()));
            out.add(item);
        }
        return out;
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
    }

    private static String nz(Object value) {
        return value == null ? "" : value.toString();
    }

    /* ================= 工具 ================= */

    private static int clampDays(int days) {
        if (days < 1) {
            return 1;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 500);
    }

    /**
     * 把 H2 返回的行统一转成小写键名。
     *
     * <p>H2 返回的列标签大小写不保证（和 DATABASE_TO_LOWER、别名写法都有关系），
     * 而 MyBatis 往 Map 里放的时候用的就是那个标签。不归一化的话，
     * 「本地能跑、换个环境全是 0」这类最难查的问题就会出现。
     */
    private static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>(Math.max(8, row.size() * 2));
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey() == null ? null : e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    /** 拼「SQL 别名 → 输出名」的对照表，成对出现，长度必须一致 */
    private static String[] cols(String... pairs) {
        return pairs;
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return 0;
        }
        double value;
        if (v instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return Math.round(value * 10) / 10.0;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    /** DATE 列在 Map 结果里可能是 java.sql.Date、LocalDate 或字符串，统一成 yyyy-MM-dd */
    private static String dateStr(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (v instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (v instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate().toString();
        }
        String s = v.toString().trim();
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static String timeStr(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime localDateTime) {
            return localDateTime.format(TIME_FMT);
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(TIME_FMT);
        }
        return v.toString();
    }

    /**
     * 归属地展示。逐级退化：省市 → 省 → 国家 → null。
     *
     * <p>国家的取舍是有讲究的：境内 IP 不重复标「中国」（省市已经说明问题了），
     * 境外 IP 一定要带国名——只写「加利福尼亚 圣克拉拉」看不出是哪个国家。
     *
     * <p>之前这里只拼省市、把 country 整个丢掉，于是只有国家级解析结果的 IP
     * （ip2region 的免费数据对境外基本只到国家）在表里全显示成「—」，
     * 看起来像是没查到，其实库里有。
     */
    private static String joinRegion(IpRegionService.Region region) {
        if (region == null) {
            return null;
        }
        String country = region.country();
        String body = joinRegionRaw(region.province(), region.city());
        if (body == null) {
            return country;
        }
        if (country == null || "中国".equals(country)) {
            return body;
        }
        return country + " " + body;
    }

    private static String joinRegionRaw(String province, String city) {
        if (province == null) {
            return city;
        }
        if (city == null) {
            return province;
        }
        // 直辖市的省市两级在 IP 库里是「北京」和「北京市」，港澳是「香港特别行政区」和「香港」，
        // 照原样拼出来就是「北京 北京市」这种看着像 bug 的重复。
        // 两级互相包含时只留长的那个，其余照常拼
        if (city.startsWith(province) || province.startsWith(city)) {
            return city.length() >= province.length() ? city : province;
        }
        return province + " " + city;
    }

    /** [{name,pv,uv}, ...] 这种形态的通用转换，键名直接照搬 SQL 别名 */
    private static List<Map<String, Object>> simplify(List<Map<String, Object>> rows, String... keys) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            for (String k : keys) {
                Object v = r.get(k);
                item.put(k, v instanceof Number n ? n.longValue() : (v == null ? null : v.toString()));
            }
            out.add(item);
        }
        return out;
    }

    /** 按天序列。{@code pairs} 是「SQL 别名, 输出名」成对排列 */
    private static List<Map<String, Object>> seriesOf(List<Map<String, Object>> rows, String dateKey,
                                                      String[] pairs) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = lower(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", dateStr(r.get(dateKey)));
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                item.put(pairs[i + 1], num(r, pairs[i]));
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 把「0-23 小时」「1-7 星期」这种定长维度的空档补成 0。
     *
     * <p>不补的话图表 X 轴只画有数据的那几格，24 小时分布图会变成「就 3 个点」。
     *
     * @param sqlKey SQL 里的别名；{@code outKey} 输出用的键名（SQL 别名不会叫 hour，
     *               HOUR 在 SQL 里是关键字）
     */
    private static List<Map<String, Object>> fillByIndex(List<Map<String, Object>> rows, String sqlKey,
                                                         String outKey, int min, int max,
                                                         String[] pairs) {
        Map<Long, Map<String, Object>> byIndex = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = lower(row);
            byIndex.put(num(r, sqlKey), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            Map<String, Object> row = byIndex.get((long) i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(outKey, i);
            for (int j = 0; j + 1 < pairs.length; j += 2) {
                item.put(pairs[j + 1], row == null ? 0L : num(row, pairs[j]));
            }
            out.add(item);
        }
        return out;
    }
}
