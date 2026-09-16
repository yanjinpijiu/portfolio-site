package top.qianlink.portfolio.storage;

import java.io.InputStream;

/**
 * 文件存储抽象。本地开发用 {@link LocalStorageService}，生产环境切到 {@link OssStorageService}。
 */
public interface StorageService {

    /**
     * 写入文件。
     *
     * @param in          输入流，由调用方负责关闭
     * @param key         对象 key，形如 2026/09/xxx.pdf
     * @param contentType MIME 类型
     */
    void put(InputStream in, String key, String contentType);

    /**
     * 读取文件。
     */
    InputStream get(String key);

    /**
     * 删除文件，不存在时静默返回。
     */
    void delete(String key);

    /**
     * 生成新对象的 key。
     */
    default String newKey(String originalFileName) {
        String ext = "";
        if (originalFileName != null) {
            int dot = originalFileName.lastIndexOf('.');
            if (dot > -1 && dot < originalFileName.length() - 1) {
                ext = originalFileName.substring(dot).toLowerCase();
            }
        }
        java.time.LocalDate now = java.time.LocalDate.now();
        return String.format("%d/%02d/%s%s",
                now.getYear(), now.getMonthValue(),
                java.util.UUID.randomUUID().toString().replace("-", ""), ext);
    }
}
