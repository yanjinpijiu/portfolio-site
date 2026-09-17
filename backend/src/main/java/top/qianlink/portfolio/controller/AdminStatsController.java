package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.Csv;
import top.qianlink.portfolio.domain.LogQuery;
import top.qianlink.portfolio.guard.CaptchaService;
import top.qianlink.portfolio.guard.DownloadGate;
import top.qianlink.portfolio.service.StatsQueryService;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 日志检索：按日期和一堆条件翻明细，分页返回。
     *
     * <p>参数直接绑到 {@link LogQuery} 上，字段名就是前端传的参数名。
     * 日志页要能按 IP、访客 ID、路径、省市、终端各种组合去搜，
     * 摊成十几个 {@code @RequestParam} 只会让签名读不下去。
     */
    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(LogQuery query) {
        return ApiResponse.ok(statsQueryService.logs(query));
    }

    /** 日志筛选下拉的候选值，跟着类型和日期走 */
    @GetMapping("/logs/options")
    public ApiResponse<Map<String, Object>> logOptions(LogQuery query) {
        return ApiResponse.ok(statsQueryService.logOptions(query));
    }

    /**
     * 导出当前筛选结果。
     *
     * <p>不返回 {@code ApiResponse}——那是个 JSON 包装，导出的应该是能直接被 Excel 打开的
     * 文件流。前端也不走 {@code request()}（它只认 JSON），和「导出备份」一样自己取 blob。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(LogQuery query) {
        List<List<String>> rows = statsQueryService.logRowsForExport(query);
        // 截断判断要在取完行之后做：prepare 是在 logRowsForExport 里跑的，
        // 这一步之后 query 里的起止日期才补全
        boolean truncated = statsQueryService.logExportTruncated(query);

        StringBuilder sb = new StringBuilder();
        sb.append(Csv.BOM);
        sb.append(Csv.row(statsQueryService.logExportHeader(query)));
        for (List<String> row : rows) {
            sb.append(Csv.row(row));
        }
        if (truncated) {
            sb.append(Csv.row(List.of("# 结果超过 " + rows.size() + " 行，已截断")));
        }

        // 文件名用 ASCII：中文名虽然能靠 RFC 5987 编出去，但不同浏览器、不同解压工具
        // 对它的处理不一致，一个日志文件不值得为这个赌
        String fileName = "logs-" + query.getType() + "-" + query.getFrom() + "_" + query.getTo() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
