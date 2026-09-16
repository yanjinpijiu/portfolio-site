package top.qianlink.portfolio.storage;

import top.qianlink.portfolio.common.BizException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 图片缩放与压缩，只用 JDK 自带的 ImageIO，不引任何图片处理依赖。
 *
 * <p>为什么要压缩：内容改成运行时上传之后，就失去了 Vite 构建时自动生成 WebP 的能力，
 * 原图直接扔上去动辄两三 MB，手机上打开很慢。这里统一等比缩到指定边长内并重新编码成 JPEG。
 *
 * <p><b>为什么不直接 ImageIO.read</b>：一张 10000×10000 的图解码后是 300MB 的位图，
 * 后端堆只有 384MB，直接把进程打死。所以先用 ImageReader 只读文件头拿到宽高，
 * 尺寸离谱的直接拒绝，再决定要不要解码。
 */
public final class ImageUtil {

    /** 单边超过这个像素数就拒绝，防止解码时把堆撑爆 */
    private static final int MAX_SOURCE_DIMENSION = 10000;

    /** JPEG 编码质量。0.82 在肉眼几乎无差的前提下能把体积压掉一大半 */
    private static final float QUALITY = 0.82f;

    private ImageUtil() {
    }

    /**
     * 把图片等比缩到 maxDim 以内并转成 JPEG。
     *
     * @param maxDim 长边最大像素数：头像 400、证书与项目截图 1600
     */
    public static byte[] compress(InputStream in, int maxDim) {
        byte[] source;
        try {
            source = in.readAllBytes();
        } catch (IOException e) {
            throw new BizException(500, "读取图片失败");
        }
        if (source.length == 0) {
            throw new BizException(400, "图片是空的");
        }

        int[] size = readSize(source);
        int srcW = size[0];
        int srcH = size[1];
        if (srcW <= 0 || srcH <= 0) {
            throw new BizException(400, "这个文件不是能识别的图片格式（支持 jpg / png）");
        }
        if (srcW > MAX_SOURCE_DIMENSION || srcH > MAX_SOURCE_DIMENSION) {
            throw new BizException(400, "图片尺寸太大了（" + srcW + "×" + srcH + "），请先缩到 "
                    + MAX_SOURCE_DIMENSION + " 像素以内再传");
        }

        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            throw new BizException(500, "解析图片失败");
        }
        if (src == null) {
            throw new BizException(400, "这个文件不是能识别的图片格式（支持 jpg / png）");
        }

        int targetW = srcW;
        int targetH = srcH;
        if (Math.max(srcW, srcH) > maxDim) {
            double scale = (double) maxDim / Math.max(srcW, srcH);
            targetW = Math.max(1, (int) Math.round(srcW * scale));
            targetH = Math.max(1, (int) Math.round(srcH * scale));
        }

        // JPEG 不支持透明通道。带 alpha 的 PNG 直接转 JPEG 会把透明区域变成黑块，
        // 所以先铺一层白底再画上去。
        BufferedImage dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length / 2);
        try {
            if (!ImageIO.write(dst, "jpg", out)) {
                throw new IOException("no jpeg writer");
            }
        } catch (IOException e) {
            throw new BizException(500, "图片编码失败");
        }
        return out.toByteArray();
    }

    /** 只读文件头拿宽高，不解码像素 */
    private static int[] readSize(byte[] source) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return new int[]{-1, -1};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return new int[]{-1, -1};
        }
    }
}
