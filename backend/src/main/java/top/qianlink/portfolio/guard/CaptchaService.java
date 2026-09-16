package top.qianlink.portfolio.guard;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 下载接口用的图形验证码：出一道算术题，答案存在内存里，一次性核销。
 *
 * <p>用 JDK 自带的 Graphics2D + ImageIO 画图，不引任何依赖、不联网（不依赖
 * Google reCAPTCHA 那种境外服务），这在「本机连不上 GitHub」的环境里是唯一稳妥的选择。
 *
 * <p><b>两个关键点</b>：
 * <ol>
 *   <li><b>核销必须原子</b>——{@link #redeem} 是「先 remove 再比对」，
 *       remove 返回 null 就直接判失败。先查后删的话，并发下同一个验证码能被用很多次；</li>
 *   <li><b>服务器没装字体时不能把人锁死</b>——Linux 上 JDK 不含字体，靠 fontconfig 提供，
 *       字体缺失时 drawString 画出来是空白图，谁也认不出来，等于下载功能全挂。
 *       所以启动时先自己渲染一张图数一下暗色像素，画不出来就整体降级
 *       （{@link #isAvailable()} 返回 false，下载接口跳过验证码那一道），并且打一条 ERROR。
 *       降级后仍有 IP 限流和全局并发上限兜底，比「简历下不了」这种事故轻得多。</li>
 * </ol>
 */
@Slf4j
@Service
public class CaptchaService {

    /** 有效期。人的正常操作是「看到图→算→输入→提交」，三分钟绰绰有余 */
    private static final long TTL_MS = 3 * 60 * 1000L;

    /** 池子上限，超过就清一遍过期的，防止被刷爆内存 */
    private static final int POOL_LIMIT = 20000;

    private static final int WIDTH = 132;
    private static final int HEIGHT = 44;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** id -> 答案。值用 record 装答案和过期时间 */
    private final Map<String, Entry> pool = new ConcurrentHashMap<>();

    private volatile boolean available = true;

    private record Entry(String answer, long expireAt) {
    }

    public record Challenge(String id, String image, int expiresInSeconds) {
    }

    /**
     * 启动自检：渲染一张图，数暗色像素。画不出东西说明这台机器上没有可用字体。
     *
     * <p>这不是「锦上添花的检查」——没有它，字体缺失的服务器上验证码会是全白图，
     * 而下载接口又要求必须答对才放行，结果就是网站上的简历永远下不下来，
     * 而且从日志里完全看不出原因（drawString 不会报错，就是画不出来）。
     */
    @PostConstruct
    void selfCheck() {
        try {
            BufferedImage image = render("8 + 9 = ?");
            int dark = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) < 0x888888) {
                        dark++;
                    }
                }
            }
            available = dark > 100;
            if (available) {
                log.info("验证码渲染自检通过（暗色像素 {}）", dark);
            } else {
                log.error("验证码渲染不出来（暗色像素只有 {}）：这台机器大概率没装字体，"
                        + "验证码那一道闸已自动降级放行。装一下字体重启即可恢复："
                        + "dnf install -y dejavu-sans-fonts fontconfig", dark);
            }
        } catch (Exception e) {
            available = false;
            log.error("验证码渲染自检异常，验证码那一道闸已自动降级放行", e);
        }
    }

    /** 验证码功能是否可用。不可用（没字体）时调用方要跳过验证码这道闸 */
    public boolean isAvailable() {
        return available;
    }

    /** 出一道题，返回 id 和可以直接塞进 &lt;img src&gt; 的 data URI */
    public Challenge generate() {
        if (!available) {
            return null;
        }
        purgeExpired();

        // 加减乘都用上，答案控制在两位数以内，人一眼能算出来
        int a = 1 + RANDOM.nextInt(9);
        int b = 1 + RANDOM.nextInt(9);
        String question;
        int answer;
        switch (RANDOM.nextInt(3)) {
            case 0 -> {
                question = a + " + " + b;
                answer = a + b;
            }
            case 1 -> {
                // 保证不出现负数：把大的放前面
                int big = Math.max(a, b);
                int small = Math.min(a, b);
                question = big + " - " + small;
                answer = big - small;
            }
            default -> {
                question = a + " × " + b;
                answer = a * b;
            }
        }

        String id = newId();
        pool.put(id, new Entry(String.valueOf(answer), System.currentTimeMillis() + TTL_MS));

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try {
            ImageIO.write(render(question + " = ?"), "png", out);
        } catch (Exception e) {
            // 画不出来就当这道题不存在，调用方拿到 null 会放行——不能因为画图失败把人锁死
            pool.remove(id);
            log.error("生成验证码图片失败，本次跳过验证码", e);
            return null;
        }
        return new Challenge(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()),
                (int) (TTL_MS / 1000));
    }

    /**
     * 校验并核销。<b>先删后验</b>，所以同一个 id 只有一次机会，
     * 即使有人拿着同一个 id 并发提交，也只有一个线程能拿到那条记录。
     */
    public boolean redeem(String id, String answer) {
        if (id == null || answer == null || answer.isBlank()) {
            return false;
        }
        Entry entry = pool.remove(id);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() > entry.expireAt()) {
            return false;
        }
        return entry.answer().equals(answer.trim());
    }

    /** 画图。不用任何外部字体文件，字体名走 JDK 的逻辑字体 */
    private static BufferedImage render(String text) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            ThreadLocalRandom random = ThreadLocalRandom.current();

            // 干扰线：画在文字下面，颜色浅一点，别把字盖住
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 5; i++) {
                g.setColor(new Color(150 + random.nextInt(90), 150 + random.nextInt(90),
                        150 + random.nextInt(90)));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            // 逐字符画，每个字单独旋转一点点角度，简单的模板匹配就对不上了
            int x = 8;
            for (char c : text.toCharArray()) {
                Font font = new Font(Font.SANS_SERIF, Font.BOLD, 20 + random.nextInt(4));
                g.setFont(font);
                AffineTransform origin = g.getTransform();
                g.rotate((random.nextDouble() - 0.5) * 0.5, x + 4, HEIGHT / 2.0);
                g.setColor(new Color(random.nextInt(70), random.nextInt(70), random.nextInt(110)));
                g.drawString(String.valueOf(c), x, 30);
                g.setTransform(origin);
                x += g.getFontMetrics(font).charWidth(c) + 1;
            }

            // 噪点
            for (int i = 0; i < 70; i++) {
                g.setColor(new Color(120 + random.nextInt(120), 120 + random.nextInt(120),
                        120 + random.nextInt(120)));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static String newId() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf);
    }

    /** 清掉过期的题目。只在出题时顺手做，不需要后台线程 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        if (pool.size() < POOL_LIMIT) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = pool.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue().expireAt()) {
                it.remove();
            }
        }
    }
}
