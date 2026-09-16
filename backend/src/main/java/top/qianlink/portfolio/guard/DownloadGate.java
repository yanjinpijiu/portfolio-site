package top.qianlink.portfolio.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.mapper.ResumeDownloadLogMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下载接口的三道闸。按顺序生效，代价从低到高排列：
 *
 * <ol>
 *   <li><b>单 IP 每秒 2 次</b>（{@link #checkRate}）——最便宜，先挡住批量脚本；</li>
 *   <li><b>验证码</b>（{@link #verifyCaptcha}）——同一 IP 对同一份简历，
 *       <b>24 小时内前 3 次免验证，第 4 次起必须答题</b>。HR 正常下载根本碰不到这道闸，
 *       而脚本一定会被拦下；</li>
 *   <li><b>全局并发 20</b>（{@link #acquireSlot}）——防止一堆人（或脚本）同时下载
 *       把窄带宽吃干净。注意它限制的是<b>同时正在传输的下载</b>，不是请求数。</li>
 * </ol>
 *
 * <p>免验证的额度<b>复用下载日志表</b>来数（同一 IP 同一简历 24 小时内成功下载了几次），
 * 不额外维护计数器：少一份需要和日志对账的状态，也就少一个会不一致的地方。
 * 代价是这个判断要在请求链路上同步查一次库——走 idx_download_ip_resume 索引，可以接受。
 *
 * <p><b>顺序上的两个硬约束</b>（写错了很难发现）：
 * <ul>
 *   <li>三道闸都必须<b>在响应体开始推流之前</b>判断完。流一旦开始推，
 *       HTTP 状态和响应体都改不了了，那时候再想拒绝只能把连接掐断；</li>
 *   <li>并发许可<b>必须只释放一次</b>。一次异常泄漏一个许可，泄漏 20 次之后
 *       所有下载都会卡在等许可上，而且只有重启才能恢复。所以这里把释放动作
 *       包成一个幂等的 Runnable（{@link #acquireSlot} 的返回值），
 *       正常下载完在流关闭时释放，异常路径在 finally 里释放，两条路都走也不会重复释放。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadGate {

    private static final int PER_IP_PER_SECOND = 2;

    /** 同一 IP 对同一份简历，24 小时内免验证码的次数 */
    private static final int FREE_QUOTA = 3;

    private static final long FREE_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** 同时最多几个下载在传输 */
    private static final int MAX_CONCURRENT = 20;

    /** 拿不到许可就等这么久，还不行就拒绝——绝不无限阻塞，不然请求会一直堆着 */
    private static final long ACQUIRE_TIMEOUT_MS = 2000;

    /** 验证码接口本身的限流：一分钟最多换 20 张图，防止有人拿它烧 CPU */
    private static final int CAPTCHA_PER_MINUTE = 20;

    /** 下载接口需要验证码时的业务码。前端见到它就去弹验证码框 */
    private static final int CODE_CAPTCHA_REQUIRED = 428;

    private final IpRateLimiter rateLimiter;
    private final CaptchaService captchaService;
    private final ResumeDownloadLogMapper downloadLogMapper;

    /** 公平模式：先到先得，避免一直有人在队尾饿死 */
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT, true);

    /* ---------------- 第一道：单 IP 限流 ---------------- */

    public void checkRate(String ip) {
        if (!rateLimiter.allow("download:" + ip, PER_IP_PER_SECOND, 1000)) {
            throw new BizException(429, "请求太快了，请稍后再试");
        }
    }

    /** 验证码接口自己的限流 */
    public void checkCaptchaRate(String ip) {
        if (!rateLimiter.allow("captcha:" + ip, CAPTCHA_PER_MINUTE, 60_000)) {
            throw new BizException(429, "请求太频繁，请稍后再试");
        }
    }

    /* ---------------- 第二道：按次数的验证码 ---------------- */

    /**
     * 这个 IP 现在下载这份简历是否需要验证码。
     *
     * <p>只统计<b>成功下载</b>的行——验证码输错不会往日志里写记录，
     * 否则输错一次就白白消耗一次免费额度（HR 很容易点错，那体验就毁了）。
     */
    public boolean needsCaptcha(String ip, Long resumeId) {
        if (!captchaService.isAvailable()) {
            // 服务器画不出验证码图（没装字体）时放行，不能把人锁死。启动时有 ERROR 日志
            return false;
        }
        long used = downloadLogMapper.countRecentByIpAndResume(ip, resumeId,
                LocalDateTime.now().minusHours(24));
        return used >= FREE_QUOTA;
    }

    /** 需要验证码时校验它。答案不对或已用过，抛业务码 428 让前端弹框 */
    public void verifyCaptcha(String captchaId, String answer) {
        if (!captchaService.redeem(captchaId, answer)) {
            throw new BizException(CODE_CAPTCHA_REQUIRED, "请先完成验证码");
        }
    }

    /* ---------------- 第三道：全局并发 ---------------- */

    /**
     * 占一个并发下载的名额。
     *
     * @return 幂等的释放动作，<b>必须</b>在下载结束（或失败）时调用一次
     */
    public Runnable acquireSlot() {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(429, "请求被中断，请重试");
        }
        if (!acquired) {
            throw new BizException(429, "当前下载人数较多，请稍后重试");
        }

        AtomicBoolean released = new AtomicBoolean(false);
        return () -> {
            // 正常路径在流关闭时释放、异常路径在 finally 释放，两条路都可能走到，
            // 所以必须保证只有一个线程真正把许可还回去，否则信号量会越还越多
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        };
    }

    /**
     * 把释放动作挂到流的关闭上。
     *
     * <p>为什么挂 close 而不是用 try/finally 包住 controller 方法：
     * controller 方法返回 ResponseEntity 的时候文件<b>还没开始推</b>——
     * 真正的写响应体发生在之后（HttpMessageConverter 里）。
     * 在方法里 finally 释放等于「刚拿到许可就还回去」，
     * 并发闸门形同虚设。而 Spring 写完响应体后一定会关闭这个流
     * （包括客户端中途断开的情况），所以挂在 close 上才是正确的时机。
     */
    public InputStream guardStream(InputStream in, Runnable release) {
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    release.run();
                }
            }
        };
    }

    /** 还在传输中的下载数，给看板用 */
    public int activeDownloads() {
        return MAX_CONCURRENT - slots.availablePermits();
    }

    public int maxConcurrent() {
        return MAX_CONCURRENT;
    }
}
