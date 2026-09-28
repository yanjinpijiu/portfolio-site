package com.example.portfolio.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.example.portfolio.common.ApiResponse;
import com.example.portfolio.config.AdminAuthInterceptor;
import com.example.portfolio.config.AppProperties;
import com.example.portfolio.service.AuthService;
import com.example.portfolio.stats.VisitorIdFilter;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppProperties props;

    public record LoginRequest(String key) {
    }

    @PostMapping("/api/admin/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        String token = authService.login(body == null ? null : body.key());
        // 种一个标记：这台浏览器之后看站点不计入访客统计。
        // 不这么做的话，自己每次进后台看数据都会把自己的访问记进去，看板永远被自己污染
        VisitorIdFilter.markAdmin(request, response);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresInHours", props.getTokenTtlHours());
        return ApiResponse.ok(data);
    }

    /** 前端刷新页面时校验令牌是否还有效 */
    @GetMapping("/api/admin/session")
    public ApiResponse<Map<String, Object>> session() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", true);
        return ApiResponse.ok(data);
    }

    @PostMapping("/api/admin/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request.getHeader(AdminAuthInterceptor.TOKEN_HEADER));
        VisitorIdFilter.unmarkAdmin(request, response);
        return ApiResponse.ok();
    }
}
