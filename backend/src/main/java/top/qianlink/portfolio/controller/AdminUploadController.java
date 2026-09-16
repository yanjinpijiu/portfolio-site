package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.service.SiteContentService;
import top.qianlink.portfolio.storage.ImageUtil;
import top.qianlink.portfolio.storage.StorageService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 后台上传图片与短片（头像 / 项目成果图 / 证书 / 演示视频）。
 *
 * <p>只收白名单里的类型，且做三重校验：扩展名白名单、Content-Type 白名单、
 * 图片还要真实内容能不能解码。光看扩展名是不够的，改个后缀就能把任意文件传上来。
 *
 * <p>视频单独走一条路：不做任何转码（服务端没有 ffmpeg，也装不动），
 * 上限给得比图片大，但仍然是硬上限。**视频在上传前应该先在本地压好**——
 * 一段 8 秒的屏幕录制，H.264 压到 720 宽只有 200KB 左右，
 * 同样内容做成 GIF 要 2.6MB，差 12 倍还糊。所以这里只收 mp4 / webm。
 */
@RestController
@RequestMapping("/api/admin/upload")
@RequiredArgsConstructor
public class AdminUploadController {

    /** 能处理的图片扩展名。webp 单独说明：JDK 的 ImageIO 读不了它，只能原样存不做压缩 */
    private static final Set<String> OK_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> OK_CONTENT_TYPE = Set.of(
            "image/jpeg", "image/png", "image/webp");

    /** 视频扩展名与类型。gif 不在列：它是图片里最费流量的格式，真要动图就用 mp4 */
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm");
    private static final Set<String> VIDEO_CONTENT_TYPE = Set.of(
            "video/mp4", "video/webm");

    /** 单张图上限。前端那边也会拦一道，但服务端的校验才是算数的 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** 短片上限。个人站用不到更大的，真要更大应该传外链而不是塞进自己服务器 */
    private static final long MAX_VIDEO_BYTES = 16L * 1024 * 1024;

    private static final int AVATAR_MAX_DIM = 400;
    private static final int IMAGE_MAX_DIM = 1600;

    private final StorageService storage;
    private final SiteContentService siteContentService;

    /**
     * @param kind avatar = 头像（缩到 400px）；video = 短片（不压缩）；其他值按 1600px 处理
     */
    @PostMapping("/image")
    public ApiResponse<Map<String, String>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind) {

        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }

        String original = file.getOriginalFilename();
        String ext = extensionOf(original);
        String contentType = file.getContentType();
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        // 短片走单独一条路：不压缩、换一套白名单和上限
        if ("video".equals(kind)) {
            return uploadVideo(file, ext, lowerType);
        }

        if (file.getSize() > MAX_BYTES) {
            throw new BizException(400, "图片不能超过 8MB，请先压缩一下再传");
        }
        if (!OK_EXT.contains(ext)) {
            throw new BizException(400, "只支持 jpg / png / webp 格式");
        }
        if (!OK_CONTENT_TYPE.contains(lowerType)) {
            throw new BizException(400, "文件类型不对，只支持 jpg / png / webp");
        }

        byte[] data;
        try (InputStream in = file.getInputStream()) {
            if ("webp".equals(ext)) {
                // ImageIO 不带 WebP 解码器，压缩不了，只能原样存
                data = in.readAllBytes();
            } else {
                data = ImageUtil.compress(in, "avatar".equals(kind) ? AVATAR_MAX_DIM : IMAGE_MAX_DIM);
            }
        } catch (IOException e) {
            throw new BizException(500, "读取上传图片失败");
        }

        // 压缩后统一是 jpg；webp 保持原扩展名
        String outName = "webp".equals(ext) ? "upload.webp" : "upload.jpg";
        String outType = "webp".equals(ext) ? "image/webp" : "image/jpeg";
        String key = storage.newKey(outName);
        try (InputStream in = new ByteArrayInputStream(data)) {
            storage.put(in, key, outType);
        } catch (IOException e) {
            throw new BizException(500, "图片写入失败");
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("objectKey", key);
        result.put("url", SiteContentService.fileUrl(key));
        result.put("size", String.valueOf(data.length));
        return ApiResponse.ok(result);
    }

    /**
     * 短片：原样存，不做任何处理。
     *
     * <p>服务端没有 ffmpeg，也不打算装——压缩放在上传之前做（本地压一段 8 秒的
     * 屏幕录制只要一条命令，见 DEPLOY.md 或项目页说明）。这里只负责白名单 + 大小上限。
     */
    private ApiResponse<Map<String, String>> uploadVideo(MultipartFile file, String ext, String contentType) {
        if (file.getSize() > MAX_VIDEO_BYTES) {
            throw new BizException(400, "短片不能超过 16MB，先在本地压一下再传（H.264、720 宽，几秒的片子通常只有几百 KB）");
        }
        if (!VIDEO_EXT.contains(ext)) {
            throw new BizException(400, "短片只支持 mp4 / webm。GIF 不要用：同样内容 GIF 比 mp4 大十倍还糊");
        }
        if (!VIDEO_CONTENT_TYPE.contains(contentType)) {
            throw new BizException(400, "文件类型不对，短片只支持 mp4 / webm");
        }

        long size = file.getSize();
        String key = storage.newKey("clip." + ext);
        try (InputStream in = file.getInputStream()) {
            // newKey 里带 uuid，同名不会互相覆盖
            storage.put(in, key, contentType);
        } catch (IOException e) {
            throw new BizException(500, "短片写入失败");
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("objectKey", key);
        result.put("url", SiteContentService.fileUrl(key));
        result.put("size", String.valueOf(size));
        return ApiResponse.ok(result);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
