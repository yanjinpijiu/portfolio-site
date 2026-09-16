package top.qianlink.portfolio.stats;

/**
 * 从 User-Agent 里扒出浏览器 / 系统 / 设备。
 *
 * <p>手写 substring 匹配，不引 ua-parser 之类的依赖——这是个个人站，
 * 统计只用来大致看「都是什么人在看」，不值得为它多一个几百 KB 的依赖。
 *
 * <p><b>判断顺序很重要</b>，写反了会得到一堆错的结果：
 * <ul>
 *   <li>Edge 的 UA 里同时含 "Edg" 和 "Chrome"，所以必须先判 Edge</li>
 *   <li>Chrome 的 UA 里同时含 "Chrome" 和 "Safari"，所以必须先判 Chrome</li>
 *   <li>iOS 上所有浏览器都带 "Safari"，且 iPhone 的 UA 里也带 "Mac OS X"</li>
 * </ul>
 */
public final class UaUtil {

    private UaUtil() {
    }

    /** 判断结果都截断到 32 字符以内，和库表字段对齐，也防止超长 UA 撑爆字段 */
    private static String cut(String s) {
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    public static String browser(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        String s = ua.toLowerCase();
        if (s.contains("micromessenger")) return "微信";
        if (s.contains("edg/") || s.contains("edgios") || s.contains("edga")) return "Edge";
        if (s.contains("qqbrowser")) return "QQ浏览器";
        if (s.contains("ucbrowser")) return "UC";
        if (s.contains("firefox") || s.contains("fxios")) return "Firefox";
        if (s.contains("opr/") || s.contains("opera")) return "Opera";
        if (s.contains("msie") || s.contains("trident")) return "IE";
        if (s.contains("chrome") || s.contains("crios")) return "Chrome";
        if (s.contains("safari")) return "Safari";
        if (s.contains("curl") || s.contains("wget") || s.contains("python")) return "脚本";
        return "其他";
    }

    public static String os(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        String s = ua.toLowerCase();
        if (s.contains("harmony")) return "HarmonyOS";
        // Android 的 UA 里一定也带 "Linux"，所以必须先判 Android
        if (s.contains("android")) return "Android";
        // iPhone / iPad 的 UA 里带 "Mac OS X"，必须在 macOS 之前判
        if (s.contains("iphone") || s.contains("ipad") || s.contains("ipod")) return "iOS";
        if (s.contains("windows")) return "Windows";
        if (s.contains("mac os x") || s.contains("macintosh")) return "macOS";
        if (s.contains("linux")) return "Linux";
        return "其他";
    }

    public static String device(String ua) {
        if (ua == null || ua.isBlank()) {
            return "未知";
        }
        String s = ua.toLowerCase();
        if (s.contains("ipad") || s.contains("tablet") || s.contains("pad")) return "平板";
        if (s.contains("mobile") || s.contains("android")
                || s.contains("iphone") || s.contains("ipod")) {
            return "手机";
        }
        return "桌面";
    }

    /** 存进库前统一裁一下长度 */
    public static String normalize(String value) {
        return value == null ? "未知" : cut(value);
    }
}
