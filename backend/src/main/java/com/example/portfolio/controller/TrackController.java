package com.example.portfolio.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.example.portfolio.common.ApiResponse;
import com.example.portfolio.stats.StatsCollector;

/**
 * 前端埋点接口。SPA 每次路由切换后调一次，用来统计「谁在什么时候看了哪一页」；
 * 点外链（项目仓库链接、首页 GitHub 入口）时也走这里，多带一个 {@code target}。
 *
 * <p>这是个公开接口，属于额外暴露的攻击面，所以特意做窄：
 * <ul>
 *   <li><b>请求体只收三个字段</b>（路径 + 来源页 + 点击目标），页面类型和项目 slug
 *       由服务端从路径推导，不采信客户端；{@code target} 只收 http/https；</li>
 *   <li><b>IP 和 UA 服务端自己取</b>，请求体里就算传了也不看；</li>
 *   <li>长度全部截断，路由名只放行字母数字中划线，避免任意串落到看板上；</li>
 *   <li>同一个访客对同一路径（或同一目标链接）数秒内的重复上报会被去重
 *       （页面可见性变化 + 路由跳转会各发一次，点击还会 click/auxclick 双触发）；</li>
 *   <li>带 pv_skip 标记的浏览器（也就是自己登录过后台的那台）直接不记。</li>
 * </ul>
 *
 * <p>写库是异步的（{@link StatsCollector} → 单线程有界队列），
 * 所以这个接口的响应时间不取决于磁盘。统计丢几条可以接受，拖慢站点不行。
 */
@RestController
@RequiredArgsConstructor
public class TrackController {

    private final StatsCollector statsCollector;

    /**
     * 只收这三个字段。用 record 而不是 Map，多余字段会被 Jackson 直接忽略。
     * {@code target} 非空 = 一次外链点击，否则 = 一次页面访问。
     */
    public record TrackRequest(String path, String route, String target) {
    }

    @PostMapping("/api/track")
    public ApiResponse<Void> track(@RequestBody(required = false) TrackRequest body,
                                   HttpServletRequest request) {
        if (body != null) {
            if (body.target() != null && !body.target().isBlank()) {
                statsCollector.recordLinkClick(request, body.path(), body.target());
            } else {
                statsCollector.recordVisit(request, body.path(), body.route());
            }
        }
        return ApiResponse.ok();
    }
}
