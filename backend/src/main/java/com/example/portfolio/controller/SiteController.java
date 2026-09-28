package com.example.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.portfolio.common.ApiResponse;
import com.example.portfolio.service.SiteContentService;

import java.util.Map;

/**
 * 站点内容（公开）。
 *
 * 前台首屏一次性拿走全部内容，不然个人资料、技能、证书、页面文案要发四五个请求。
 * 内容全在后台维护，改了不用重新构建部署。
 */
@RestController
@RequestMapping("/api/site")
@RequiredArgsConstructor
public class SiteController {

    private final SiteContentService siteContentService;

    @GetMapping
    public ApiResponse<Map<String, Object>> site() {
        return ApiResponse.ok(siteContentService.publicSite());
    }
}
