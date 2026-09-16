package top.qianlink.portfolio.stats;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 给每个访客发一个匿名 ID，存在 cookie {@code pv_uid} 里，作为 UV 的统计依据。
 *
 * <p>ID 是纯随机的 32 位十六进制串，不含任何用户信息，也不参与鉴权——
 * 它的唯一用途是「同一个浏览器反复来只算一个 UV」。
 *
 * <p>同时读一个标记 cookie {@code pv_skip}（登录后台时种下）：
 * <b>自己的访问不该计入访客统计</b>，否则每次进后台看数据都会把自己的访问记进去，
 * 看板永远被自己污染。这个标记只影响「记不记这条统计」，不涉及任何权限判断，
 * 伪造它顶多是自己的访问不计入，没有安全含义。
 *
 * <p>过滤器只读不写业务，取到的 ID 放进 request attribute，后续的拦截器和
 * 埋点接口直接取，不用再解析一遍 cookie。
 */
@Component
@Order(0)
public class VisitorIdFilter extends OncePerRequestFilter {

    public static final String VISITOR_COOKIE = "pv_uid";

    /** 登录后台时种下的标记：带它的浏览器访问不计入访客统计 */
    public static final String ADMIN_COOKIE = "pv_skip";

    public static final String ATTR_VISITOR_ID = "pv.visitorId";

    public static final String ATTR_INTERNAL = "pv.internal";

    private static final int COOKIE_MAX_AGE_DAYS = 365;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String visitorId = readCookie(request, VISITOR_COOKIE);
        boolean fresh = false;
        if (!isValidId(visitorId)) {
            visitorId = newId();
            fresh = true;
        }
        request.setAttribute(ATTR_VISITOR_ID, visitorId);
        request.setAttribute(ATTR_INTERNAL, "1".equals(readCookie(request, ADMIN_COOKIE)));

        // 写 cookie 必须在响应提交之前。放在 chain 之前就能保证这一点
        if (fresh) {
            setCookie(request, response, VISITOR_COOKIE, visitorId, COOKIE_MAX_AGE_DAYS);
        }

        chain.doFilter(request, response);
    }

    /** 从 request attribute 取访客 ID，取不到返回 null */
    public static String visitorId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTR_VISITOR_ID);
        return id instanceof String s ? s : null;
    }

    /** 这台浏览器是不是后台自己人 */
    public static boolean isInternal(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTR_INTERNAL));
    }

    /** 登录后台后种标记，之后这台浏览器看站点不计入访客统计 */
    public static void markAdmin(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(ATTR_INTERNAL, true);
        setCookie(request, response, ADMIN_COOKIE, "1", COOKIE_MAX_AGE_DAYS);
    }

    /** 退出后台时清掉标记 */
    public static void unmarkAdmin(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(ATTR_INTERNAL, false);
        setCookie(request, response, ADMIN_COOKIE, "", 0);
    }

    /**
     * 判断 ID 是否合法：只认 {@link #newId()} 生成的那种格式。
     * 形状不对就当没有，重新发一个——不信任客户端传来的任何值。
     */
    private static boolean isValidId(String id) {
        if (id == null || id.length() != 32) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String newId() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static void setCookie(HttpServletRequest request, HttpServletResponse response,
                                  String name, String value, int maxAgeDays) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofDays(maxAgeDays));
        // 线上是 https（nginx 终止 TLS），本地开发是 http。加 Secure 只在 https 下加，
        // 否则本地浏览器会直接丢掉这个 cookie，UV 就永远是 0 了
        if ("https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            builder.secure(true);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }
}
