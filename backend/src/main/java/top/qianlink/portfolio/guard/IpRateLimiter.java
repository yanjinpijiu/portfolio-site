package top.qianlink.portfolio.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 IP 的固定窗口计数限流。
 *
 * <p>为什么用固定窗口而不是令牌桶：这里只需要「一秒钟最多几次」这种粗粒度的保护，
 * 固定窗口实现最简单、没有任何后台线程。固定窗口的边界效应（跨窗口瞬间可以过 2 倍）
 * 对「防批量脚本」这个目标完全无所谓。
 *
 * <p><b>调用方必须传已经归一化过的 IP</b>——也就是 {@link top.qianlink.portfolio.stats.IpUtil#clientIp}
 * 的返回值。拿 X-Forwarded-For 那种客户端可伪造的值当 key，等于没限流：
 * 攻击者每次换一个伪造值就得到一份全新额度。
 *
 * <p>同一个 key 用 synchronized 串行化。锁的粒度是单个窗口对象，
 * 不同 IP 之间没有竞争；每个窗口里只有几次自增，开销可以忽略。
 */
@Slf4j
@Service
public class IpRateLimiter {

    /** 超过这个数量的窗口就顺手清一遍过期项，防止被大量不同 IP 撑爆内存 */
    private static final int PURGE_THRESHOLD = 5000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        long startMs;
        int count;

        Window(long startMs, int count) {
            this.startMs = startMs;
            this.count = count;
        }
    }

    /**
     * 记一次访问并判断是否放行。
     *
     * @param key      限流维度，实际用的都是 IP
     * @param limit    一个窗口内最多几次
     * @param windowMs 窗口长度
     * @return true = 放行
     */
    public boolean allow(String key, int limit, long windowMs) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        // computeIfAbsent 只锁一个桶，比先 get 再自己加锁 put 干净
        Window window = windows.computeIfAbsent(key, k -> new Window(now, 0));
        synchronized (window) {
            if (now - window.startMs >= windowMs) {
                window.startMs = now;
                window.count = 1;
            } else {
                window.count++;
            }
            boolean pass = window.count <= limit;
            if (!pass && window.count == limit + 1) {
                // 只在刚好越界的那一次打日志，避免被刷屏
                log.warn("限流命中：key={} 在 {}ms 内第 {} 次请求（上限 {}）",
                        key, windowMs, window.count, limit);
            }
            if (pass) {
                purgeIfNeeded(now);
            }
            return pass;
        }
    }

    /** 地图太大就清一遍——过期窗口的计数本来也会被重置，删掉不影响判定 */
    private void purgeIfNeeded(long now) {
        if (windows.size() <= PURGE_THRESHOLD) {
            return;
        }
        windows.entrySet().removeIf(e -> now - e.getValue().startMs > 3_600_000);
    }
}
