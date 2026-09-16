package top.qianlink.portfolio.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.qianlink.portfolio.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台密钥登录。单实例部署，令牌只存在内存里，重启后重新登录即可。
 *
 * <p>密钥值放在 application-local.yml（已 gitignore），或由环境变量 APP_ADMIN_KEY 提供，
 * 二者都没有时启动直接失败，避免线上跑在一个未知密钥上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppProperties props;

    /** token -> 过期时间 */
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(props.getAdminKey())) {
            throw new IllegalStateException(
                    "未配置后台密钥。请设置环境变量 APP_ADMIN_KEY，或在 application-local.yml 中配置 app.admin-key");
        }
        log.info("后台登录密钥已加载");
    }

    public String login(String key) {
        String configured = props.getAdminKey();
        if (!StringUtils.hasText(key) || !constantTimeEquals(configured, key)) {
            throw new top.qianlink.portfolio.common.BizException(401, "密钥不正确");
        }
        purgeExpired();
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        tokens.put(token, Instant.now().plus(props.getTokenTtlHours(), ChronoUnit.HOURS));
        return token;
    }

    public boolean isValid(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        Instant expireAt = tokens.get(token);
        if (expireAt == null) {
            return false;
        }
        if (expireAt.isBefore(Instant.now())) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            tokens.remove(token);
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
