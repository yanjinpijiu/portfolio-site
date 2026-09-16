package top.qianlink.portfolio.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.guard.CaptchaService;
import top.qianlink.portfolio.guard.DownloadGate;
import top.qianlink.portfolio.stats.IpUtil;

/**
 * 领一张验证码。公开接口，所以有两层保护：
 * 一是按 IP 限流（一分钟 20 张），二是题目池有上限并会清理过期项。
 *
 * <p>返回的是 {@code data:image/png;base64,...}，前端直接塞进 img 的 src，
 * 不用再发一次图片请求，也就没有图片 URL 被缓存 / 被预取的问题。
 *
 * <p>答案是服务端内存里存的，客户端只有 id。校验时先删后比（见 CaptchaService.redeem），
 * 所以一张图只能用一次。
 */
@RestController
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;
    private final DownloadGate gate;

    @GetMapping("/api/captcha")
    public ApiResponse<CaptchaService.Challenge> captcha(HttpServletRequest request) {
        gate.checkCaptchaRate(IpUtil.clientIp(request));
        CaptchaService.Challenge challenge = captchaService.generate();
        if (challenge == null) {
            // 服务器没装字体（画不出图）时的降级路径：告诉前端「这次不需要验证码」，
            // 下载接口那边同样会跳过这道闸，两边判断依据是同一个 isAvailable()
            return ApiResponse.fail(503, "验证码服务暂不可用，可直接下载");
        }
        return ApiResponse.ok(challenge);
    }
}
