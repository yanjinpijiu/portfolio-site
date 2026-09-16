package top.qianlink.portfolio.stats;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import top.qianlink.portfolio.config.AdminAuthInterceptor;
import top.qianlink.portfolio.service.AuthService;

/**
 * 接口访问埋点。挂在 /api/** 上，记录每个接口的调用量、业务码和耗时。
 *
 * <p><b>注册顺序必须在 AdminAuthInterceptor 之前</b>：后台鉴权失败时那个拦截器会直接
 * 返回 401 并且不再往下走，Spring 只会回调「已经通过 preHandle 的」拦截器的
 * afterCompletion。排在后面的话，越权尝试（也就是最该被看到的那批请求）一条都记不下来。
 *
 * <p>另外两条容易翻车的：
 * <ul>
 *   <li><b>要排除 /api/track 自己</b>——它每次访问也走 /api/**，不排除就是自己记自己，
 *       埋点量翻倍，而且每上报一次埋点就多一条接口日志；</li>
 *   <li><b>OPTIONS 预检不记</b>——跨域预检数量大、没有业务含义，纯噪音。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ApiAccessInterceptor implements HandlerInterceptor {

    /** 自己不能埋自己 */
    private static final String TRACK_PATH = "/api/track";

    private static final String ATTR_START = "pv.apiStartNanos";

    private final StatsCollector collector;
    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.nanoTime());
        // 上一轮如果留下了没被取走的业务码，先清掉，避免串到这次请求上
        BizCodeContext.clear();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String path = request.getRequestURI();

        // 业务码是 ThreadLocal 传过来的，不管记不记都必须取走清空，否则会串到下一个请求
        Integer bizCode = BizCodeContext.getAndClear();

        if (!shouldRecord(path, request.getMethod())) {
            return;
        }

        Object start = request.getAttribute(ATTR_START);
        int durationMs = start instanceof Long nanos
                ? (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - nanos) / 1_000_000)
                : -1;

        int httpStatus = response.getStatus();
        int code = bizCode != null ? bizCode : (httpStatus >= 400 ? httpStatus : 0);

        collector.recordApi(request, path, request.getMethod(), code, httpStatus, durationMs,
                isInternal(request));
    }

    /**
     * 这条请求是不是「后台自己人」发的：种过 pv_skip 的浏览器，或者带了有效令牌。
     *
     * <p>两个条件都要判。只看 cookie 的话，任何不带 cookie 的脚本调用（比如部署脚本
     * 自检、我自己的 curl）都会被算成外部请求，把接口排行榜刷满；
     * 而令牌无效的越权尝试必须算外部——那正是最该被看见的东西。
     */
    private boolean isInternal(HttpServletRequest request) {
        if (VisitorIdFilter.isInternal(request)) {
            return true;
        }
        return authService.isValid(request.getHeader(AdminAuthInterceptor.TOKEN_HEADER));
    }

    private static boolean shouldRecord(String path, String method) {
        if (path == null || !path.startsWith("/api/")) {
            return false;
        }
        if (TRACK_PATH.equals(path)) {
            return false;
        }
        return !"OPTIONS".equalsIgnoreCase(method);
    }
}
