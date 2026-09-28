package com.example.portfolio.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 演示模式下的只读闸门：把后台的写操作挡在服务端。
 *
 * <p>为什么要有这个类：演示站把后台开放给任何人看（密钥就写在登录页上），
 * 前端把按钮置灰只是「体验」，改不了的东西必须是服务端说了算——直接 POST 过来照样得被拒。
 *
 * <p>只拦写方法（POST / PUT / PATCH / DELETE），GET 一律放行：
 * 看板、日志、内容列表都要能正常看，这才叫「能看不能改」。
 * 登录接口在注册处被排除掉，否则演示密钥根本登不进来。
 *
 * <p>返回 403 而不是 401：401 的语义是「你还没证明身份」（前端会当成登录失效跳登录页），
 * 这里是身份没问题、但这个动作不允许，用 403 才不会让前端误判成掉登录。
 */
@Component
@RequiredArgsConstructor
public class DemoReadOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AppProperties props;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.isDemoMode()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                "{\"code\":403,\"message\":\"演示模式：这是只读演示站，写操作已被服务端禁用\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
