package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.guard.CaptchaService;
import top.qianlink.portfolio.guard.DownloadGate;
import top.qianlink.portfolio.service.StatsQueryService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据看板的查询接口。
 *
 * <p>全部挂在 {@code /api/admin/**} 下，由 {@link top.qianlink.portfolio.config.AdminAuthInterceptor}
 * 统一鉴权——访问日志里的 IP、省市、UA 属于访客隐私，不能是公开接口。
 * 这里不写任何登录校验代码，是因为校验已经在拦截器里做了；漏挂路径才会出问题，
 * 所以这个类的路径前缀必须保持 {@code /api/admin/}。
 *
 * <p>按面板分成 4 个接口而不是每个图表一个：看板一屏要画十几张图，
 * 一个图一个请求会让首屏发出十几个并发请求，还得自己处理部分失败。
 */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final StatsQueryService statsQueryService;
    private final DownloadGate downloadGate;
    private final CaptchaService captchaService;

    /** 概览：今日/区间总量，外加几个运行时状态 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> data = new LinkedHashMap<>(statsQueryService.overview(days));
        // 正在传输的下载数 / 上限：正常情况下这个值会回到 0，
        // 如果一直挂在 20 附近不动，说明有下载没结束，或者并发许可泄漏了（要重启）
        data.put("activeDownloads", downloadGate.activeDownloads());
        data.put("maxConcurrentDownloads", downloadGate.maxConcurrent());
        // 服务器画不出验证码图（没装字体）时会是 false，这时候下载接口是直接放行的，
        // 得让人一眼看见，不然会以为验证码在起作用
        data.put("captchaAvailable", captchaService.isAvailable());
        return ApiResponse.ok(data);
    }

    /** 访客面板：折线、小时、星期、地区、终端、高频 IP、访问明细 */
    @GetMapping("/visits")
    public ApiResponse<Map<String, Object>> visits(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(statsQueryService.visits(days));
    }

    /** 接口面板：调用量、慢接口、趋势、登录专项、下载专项 */
    @GetMapping("/api")
    public ApiResponse<Map<String, Object>> api(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean includeInternal) {
        return ApiResponse.ok(statsQueryService.api(days, includeInternal));
    }

    /** 简历面板：每版下载量排行、趋势、来源省份、下载明细 */
    @GetMapping("/resumes")
    public ApiResponse<Map<String, Object>> resumes(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long resumeId) {
        return ApiResponse.ok(statsQueryService.resumes(days, resumeId));
    }
}
