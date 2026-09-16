package top.qianlink.portfolio.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.storage.StorageService;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 对外发图。数据库里存的是相对 key（形如 seed/projects/jos/code-boot.jpg），
 * 前台直接拿 /files/ + key 访问。
 *
 * <p><b>线上这份代码其实不会被走到</b>：nginx 里有一条 location /files/ 直接
 * alias 到存储目录，静态文件由 nginx 发，比过一遍 Java 快得多。
 * 但开发环境没有 nginx，Vite 会把 /files 代理到这里，所以这条路必须留着。
 * 也正因为线上是 nginx 在发，这里的路径穿越防护是第二道防线——
 * 真实的第一道是 {@link StorageService} 里 resolve() 的归一化校验。
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    private final StorageService storage;

    @GetMapping("/files/**")
    public ResponseEntity<InputStreamResource> serve(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int idx = uri.indexOf("/files/");
        if (idx < 0) {
            throw new BizException(404, "文件不存在");
        }
        String key = URLDecoder.decode(uri.substring(idx + "/files/".length()),
                StandardCharsets.UTF_8);

        // 空 key、带 .. 的一律拒绝。真正的归一化校验在 StorageService.resolve() 里，
        // 这里先挡一道，避免明显恶意的请求走到存储层
        if (key.isBlank() || key.contains("..") || key.startsWith("/")) {
            throw new BizException(400, "非法的文件路径");
        }

        InputStream in = storage.get(key);
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(key))
                // key 是一次性生成或固定的，内容不会变，可以放心让浏览器缓存
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(new InputStreamResource(in));
    }

    private static MediaType mediaTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        // 项目展示页的短片。线上这类文件由 nginx 直接发（它认 mp4/webm，并且支持
        // Range 请求，浏览器才能边下边播、才能拖进度条）；这条是给没有 nginx 的开发环境用的
        if (lower.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }
        if (lower.endsWith(".webm")) {
            return MediaType.parseMediaType("video/webm");
        }
        return MediaType.IMAGE_JPEG;
    }
}
