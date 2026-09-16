package top.qianlink.portfolio.stats;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端真实 IP。
 *
 * <p><b>只认 X-Real-IP，绝不认 X-Forwarded-For。</b>
 * X-Forwarded-For 是一个列表，客户端可以自己塞值进来（比如伪造
 * {@code X-Forwarded-For: 1.2.3.4}），nginx 默认只是往后面追加，不会丢掉
 * 客户端传的那段。拿它当限流依据，攻击者每换一个伪造值就得到一份全新的
 * 额度，等于没限流。
 *
 * <p>而 X-Real-IP 是我们在 nginx 配置里用 {@code $remote_addr} 覆盖写的，
 * 客户端传什么都会被顶掉。后端又只监听回环地址（SERVER_ADDRESS=127.0.0.1），
 * 请求只可能从本机 nginx 进来，所以这个头可信。
 *
 * <p>本地开发时没有 nginx，取到的是 getRemoteAddr()，即 127.0.0.1。
 */
public final class IpUtil {

    /** 与 deploy/nginx.conf 里 proxy_set_header 的名字保持一致 */
    public static final String REAL_IP_HEADER = "X-Real-IP";

    private IpUtil() {
    }

    /** 取客户端 IP，取不到返回 "unknown"。结果长度截断到 64，和库表字段对齐。 */
    public static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader(REAL_IP_HEADER);
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        ip = ip.trim();

        // IPv6 的环回地址统一成 IPv4 写法，免得本地和线上看起来是两个东西
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            ip = "127.0.0.1";
        }
        // 形如 [::1]:12345 或 1.2.3.4:5678，去掉端口
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) {
                ip = ip.substring(1, end);
            }
        } else {
            int colon = ip.indexOf(':');
            // 冒号下标必须 >= 0 再判断：光秃秃的 127.0.0.1 没有端口，
            // indexOf 和 lastIndexOf 都是 -1，之前没判就 substring(0, -1) 直接崩，
            // 而线上 nginx 传过来的 X-Real-IP 正好就是这种不带端口的写法
            if (colon > 0 && colon == ip.lastIndexOf(':')) {
                // 只有一个冒号，说明是 IPv4:port
                ip = ip.substring(0, colon);
            }
        }

        return ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
