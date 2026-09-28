package com.example.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.portfolio.common.ApiResponse;
import com.example.portfolio.config.AppProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端启动时要问一次的小接口：这个实例是不是演示模式、登录页要不要显示演示密钥。
 *
 * <p>为什么不塞进 {@code /api/site}：那是「内容」，会被静态模式打进 snapshot.json。
 * 而这两个值是**运行时配置**（同一份前端产物既可能跑在只读演示站上、也可能跑在自己的站上），
 * 跟着快照一起缓存就会出现在线改配置不生效的问题。
 */
@RestController
@RequiredArgsConstructor
public class MetaController {

    private final AppProperties props;

    @GetMapping("/api/meta")
    public ApiResponse<Map<String, Object>> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demoMode", props.isDemoMode());
        data.put("demoKeyHint", props.getDemoKeyHint() == null ? "" : props.getDemoKeyHint());
        return ApiResponse.ok(data);
    }
}
