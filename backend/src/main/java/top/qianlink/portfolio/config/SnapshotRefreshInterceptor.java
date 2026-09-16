package top.qianlink.portfolio.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import top.qianlink.portfolio.service.SiteSnapshotService;
import top.qianlink.portfolio.stats.BizCodeContext;

/**
 * 后台改完内容、并且当前是静态模式时，自动把快照重新生成一份。
 *
 * <p><b>为什么需要</b>：静态模式的前台读的是预生成的快照，它自己不会跟着数据库变。
 * 不加这一步的话，用户切到静态模式之后在后台改内容、前台纹丝不动——
 * 这就是个坑，而且很难自己意识到（会以为保存失败或者缓存没刷）。
 * 有了它，静态模式对访客是「静态文件、快」，对后台是「改完就生效」，
 * 唯一的代价是每次保存多写一个几十 KB 的 JSON。
 *
 * <p>用拦截器而不是改每个 Controller：写入接口散在三个 Controller 里、以后还会加，
 * 漏一个就是一个隐蔽的坑。这里统一按「方法 + 路径」判断，一处覆盖全部。
 *
 * <p>判断依据是本次请求的<b>业务码</b>（{@link BizCodeContext}）：只有真正写成功
 * （code == 0）才重建，参数校验失败、权限不足这些不白写一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRefreshInterceptor implements HandlerInterceptor {

    /** 只在这些写操作之后触发；登录登出与模式切换本身不产生内容变化 */
    private static final String[] SKIP_PATHS = {
            "/api/admin/login",
            "/api/admin/logout",
            "/api/admin/site/mode",
            "/api/admin/site/snapshot"
    };

    private final SiteSnapshotService snapshotService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!isContentWrite(request)) {
            return;
        }
        // 只看不取：取走的话接口访问日志就记不到失败码了（那个拦截器在后面跑）
        //
        // 注意「没有业务码」就是成功：项目的约定是出错才由 GlobalExceptionHandler
        // 往 ThreadLocal 里塞码，成功路径上它是空的。把 null 当成失败，会导致永远不重建。
        Integer code = BizCodeContext.peek();
        if (code != null && code != 0) {
            return;
        }
        // 鉴权失败（AdminAuthInterceptor 直接返回 401）不走异常处理器，用状态码兜一下
        if (response.getStatus() >= 400) {
            return;
        }
        if (!SiteSnapshotService.MODE_STATIC.equals(snapshotService.currentMode())) {
            return;
        }
        try {
            snapshotService.rebuildSnapshot();
            log.info("检测到后台内容变更（{} {}），已自动重建静态快照",
                    request.getMethod(), request.getRequestURI());
        } catch (Exception e) {
            // 快照没重建顶多是前台还是旧内容，不能因此让这次保存报错
            log.warn("自动重建快照失败：{}", e.toString());
        }
    }

    private static boolean isContentWrite(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method)
                && !"DELETE".equals(method) && !"PATCH".equals(method)) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/admin/")) {
            return false;
        }
        for (String skip : SKIP_PATHS) {
            if (skip.equals(path)) {
                return false;
            }
        }
        return true;
    }
}
