package top.qianlink.portfolio.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.guard.DownloadGate;
import top.qianlink.portfolio.service.ResumeService;
import top.qianlink.portfolio.stats.IpUtil;
import top.qianlink.portfolio.stats.StatsCollector;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final DownloadGate gate;
    private final StatsCollector statsCollector;

    /** 对外可见的简历列表 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(resumeService.listActive().stream()
                .map(resumeService::publicView)
                .toList());
    }

    /** 默认简历，首页下载按钮用 */
    @GetMapping("/default")
    public ApiResponse<Map<String, Object>> defaultResume() {
        Resume resume = resumeService.getDefault();
        return ApiResponse.ok(resume == null ? null : resumeService.publicView(resume));
    }

    /**
     * 下载简历文件。
     *
     * <p>三道闸按「便宜的先判」排列：单 IP 每秒 2 次 → 同 IP 同简历 24 小时前 3 次免验证码
     * （第 4 次起要答题，业务码 428）→ 全局并发 20。细节见 {@link DownloadGate}。
     *
     * <p>交互上刻意<b>不</b>额外加一个「下载前先问服务端要不要验证码」的接口：
     * 前端直接打这个下载接口，服务端该放行就放行、该要验证码就返回 428，
     * 前端见到 428 再弹框、带上 captchaId 重试。这样判断逻辑完全在服务端，
     * 前端改代码也绕不过去；而且正常下载（前 3 次）一次请求就完成，不会多弹一个框。
     *
     * @param captchaId     上一次返回 428 时拿到的验证码 id
     * @param captchaAnswer 用户填的答案
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable Long id,
            @RequestParam(required = false) String captchaId,
            @RequestParam(required = false) String captchaAnswer,
            HttpServletRequest request) {

        String ip = IpUtil.clientIp(request);

        gate.checkRate(ip);

        Resume resume = resumeService.getById(id);
        if (!Boolean.TRUE.equals(resume.getActive())) {
            throw new BizException(404, "简历不存在");
        }

        // 免费额度用完才要验证码。这里只判断、不消耗——输错验证码不该吃掉一次额度
        boolean freeQuota = !gate.needsCaptcha(ip, id);
        if (!freeQuota) {
            gate.verifyCaptcha(captchaId, captchaAnswer);
        }

        // 必须在开流之前拿到许可：流一旦开始推，状态码和响应体都改不了了
        Runnable release = gate.acquireSlot();

        boolean handedOff = false;
        try {
            InputStream file = gate.guardStream(resumeService.openFile(resume), release);

            resumeService.increaseDownload(id);
            statsCollector.recordDownload(request, resume, freeQuota);

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(resume.getFileName(), StandardCharsets.UTF_8)
                    .build();
            ResponseEntity<InputStreamResource> response = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(file));
            // 从这里开始，释放许可的责任交给流的 close()（Spring 写完响应体一定会关它）。
            // handedOff 只是个兜底：构造响应对象期间出异常的话，许可得在这里还回去
            handedOff = true;
            return response;
        } finally {
            if (!handedOff) {
                release.run();
            }
        }
    }
}
