package top.qianlink.portfolio.stats;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.qianlink.portfolio.domain.ApiAccessLog;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.domain.ResumeDownloadLog;
import top.qianlink.portfolio.domain.VisitLog;
import top.qianlink.portfolio.mapper.ApiAccessLogMapper;
import top.qianlink.portfolio.mapper.ResumeDownloadLogMapper;
import top.qianlink.portfolio.mapper.VisitLogMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 埋点的组装与投递：把请求里的信息变成三条统计表的记录，交给 {@link AsyncLogWriter} 异步落库。
 *
 * <p>三件事都只在这里做一次，避免各处重复：
 * <ol>
 *   <li><b>服务端自己采集</b>——IP 用 {@link IpUtil}（只认 X-Real-IP）、UA 从请求头取、
 *       归属地用 {@link IpRegionService}，客户端传什么都不信；</li>
 *   <li><b>时间维度入库时算好</b>——H2 的 MySQL 兼容模式函数不全，
 *       查询里不调 DATE_FORMAT/DATE()，所以 date/hour/weekday 在这里就写死；</li>
 *   <li><b>同会话同路径去重</b>——页面可见性变化和路由跳转会各发一次埋点，
 *       不去重的话一次浏览能记成两三次 PV。</li>
 * </ol>
 *
 * <p>这里所有方法的失败都不该影响正常请求，所以投递是「投完就算」，
 * 唯一的异常风险（写库）留在写线程里消化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsCollector {

    /** 同一访客同一路径在这个时间内重复上报只算一次 */
    private static final long DEDUP_WINDOW_MS = 5000;

    /** 去重表的清理阈值，超过就顺手清一遍过期项 */
    private static final int DEDUP_PURGE_THRESHOLD = 2000;

    private final AsyncLogWriter writer;
    private final IpRegionService ipRegionService;
    private final VisitLogMapper visitLogMapper;
    private final ApiAccessLogMapper apiAccessLogMapper;
    private final ResumeDownloadLogMapper downloadLogMapper;

    /** key = visitorId|path，value = 上次上报的毫秒时间戳 */
    private final Map<String, Long> recentVisits = new ConcurrentHashMap<>();

    /* ---------------- 访客访问 ---------------- */

    /** 路径推导出来的页面信息，全部由服务端算，不采信客户端传的字段 */
    public record Page(String pageType, String projectSlug) {
    }

    /**
     * 记一条访客访问。
     *
     * @param path  访问路径，前端传 view 那边来的 location.pathname
     * @param route 前端路由名，只允许字母数字下划线中划线，别的一律丢掉
     */
    public void recordVisit(HttpServletRequest request, String path, String route) {
        // 自己进后台看数据不该把自己的访问记进去
        if (VisitorIdFilter.isInternal(request)) {
            return;
        }
        String cleanPath = normalizePath(path);
        String visitorId = VisitorIdFilter.visitorId(request);
        if (isDuplicate(visitorId, cleanPath)) {
            return;
        }

        String ua = request.getHeader("User-Agent");
        String ip = IpUtil.clientIp(request);
        IpRegionService.Region region = ipRegionService.lookup(ip);
        Page page = derivePage(cleanPath);
        LocalDateTime now = LocalDateTime.now();

        VisitLog entity = new VisitLog();
        entity.setVisitorId(visitorId);
        entity.setIp(ip);
        entity.setBrowser(UaUtil.browser(ua));
        entity.setOs(UaUtil.os(ua));
        entity.setDevice(UaUtil.device(ua));
        entity.setCountry(region.country());
        entity.setProvince(region.province());
        entity.setCity(region.city());
        entity.setPath(cleanPath);
        entity.setRouteName(normalizeRoute(route));
        entity.setPageType(page.pageType());
        entity.setProjectSlug(page.projectSlug());
        entity.setReferer(cut(request.getHeader("Referer"), 512));
        entity.setVisitDate(now.toLocalDate());
        entity.setVisitHour(now.getHour());
        entity.setVisitWeekday(now.getDayOfWeek().getValue());
        entity.setCreatedAt(now);

        writer.submit(() -> visitLogMapper.insert(entity));
    }

    /* ---------------- 接口访问 ---------------- */

    /**
     * 记一条接口访问。
     *
     * @param bizCode    业务码，0 = 成功；出错时由 GlobalExceptionHandler 或限流代码给出
     * @param httpStatus HTTP 状态码
     * @param durationMs 处理耗时
     * @param internal   是不是后台自己发的请求
     */
    public void recordApi(HttpServletRequest request, String path, String method,
                          int bizCode, int httpStatus, int durationMs, boolean internal) {
        String ip = IpUtil.clientIp(request);
        LocalDateTime now = LocalDateTime.now();

        ApiAccessLog entity = new ApiAccessLog();
        entity.setPath(cut(path, 256));
        entity.setMethod(cut(method, 8));
        entity.setBizCode(bizCode);
        entity.setHttpStatus(httpStatus);
        entity.setDurationMs(durationMs);
        entity.setIp(ip);
        entity.setVisitorId(VisitorIdFilter.visitorId(request));
        entity.setInternal(internal);
        entity.setVisitDate(now.toLocalDate());
        entity.setVisitHour(now.getHour());
        entity.setCreatedAt(now);

        writer.submit(() -> apiAccessLogMapper.insert(entity));
    }

    /* ---------------- 简历下载 ---------------- */

    /**
     * 记一次<b>成功</b>的简历下载。
     *
     * <p>这个表还兼职当「免验证码额度」的计数器：数一下同 IP 同简历 24 小时内的行数，
     * 少于 3 条就免验证。所以只有真正把文件下出去了才记，验证码输错不能记，
     * 否则输错一次就白白消耗一次免费额度。
     */
    public void recordDownload(HttpServletRequest request, Resume resume, boolean freePass) {
        String ip = IpUtil.clientIp(request);
        IpRegionService.Region region = ipRegionService.lookup(ip);
        LocalDateTime now = LocalDateTime.now();

        ResumeDownloadLog entity = new ResumeDownloadLog();
        entity.setResumeId(resume.getId());
        entity.setResumeTitle(cut(resume.getTitle(), 128));
        entity.setDirection(cut(resume.getDirection(), 64));
        entity.setVisitorId(VisitorIdFilter.visitorId(request));
        entity.setIp(ip);
        entity.setCountry(region.country());
        entity.setProvince(region.province());
        entity.setCity(region.city());
        entity.setFreePass(freePass);
        entity.setVisitDate(now.toLocalDate());
        entity.setVisitHour(now.getHour());
        entity.setCreatedAt(now);

        writer.submit(() -> downloadLogMapper.insert(entity));
    }

    /* ---------------- 内部工具 ---------------- */

    /**
     * 从路径推导页面类型和项目 slug。
     *
     * <p>刻意不让前端传这两个字段：它们直接决定看板上的分组，
     * 由服务端按 URL 规则推导，改前端也造不出别的值。
     */
    public static Page derivePage(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return new Page("home", null);
        }
        String p = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
        if ("/projects".equals(p)) {
            return new Page("projects", null);
        }
        if (p.startsWith("/projects/")) {
            String slug = p.substring("/projects/".length());
            int slash = slug.indexOf('/');
            if (slash > 0) {
                slug = slug.substring(0, slash);
            }
            return new Page("project", cut(slug, 64));
        }
        if ("/resume".equals(p)) {
            return new Page("resume", null);
        }
        return new Page("other", null);
    }

    /** 路径统一成以 / 开头、不含查询串、长度受限的形式 */
    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int hash = p.indexOf('#');
        if (hash >= 0) {
            p = p.substring(0, hash);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return cut(p, 256);
    }

    /** 路由名只允许安全字符，其余丢弃——它最终会出现在看板上 */
    private static String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String r = route.trim();
        if (r.length() > 64) {
            return null;
        }
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return r;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 同一个访客在几秒内对同一路径的重复上报，只算第一条 */
    private boolean isDuplicate(String visitorId, String path) {
        if (visitorId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = visitorId + '|' + path;
        Long prev = recentVisits.put(key, now);
        if (recentVisits.size() > DEDUP_PURGE_THRESHOLD) {
            recentVisits.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
        }
        return prev != null && now - prev < DEDUP_WINDOW_MS;
    }
}
