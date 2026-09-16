package top.qianlink.portfolio.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.config.AppProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(AppProperties props) {
        this.root = Paths.get(props.getStorage().getLocal().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
        log.info("本地文件存储目录: {}", root);
    }

    @Override
    public void put(InputStream in, String key, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(500, "文件写入失败");
        }
    }

    @Override
    public InputStream get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw new BizException(404, "文件不存在");
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new BizException(500, "文件读取失败");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("删除本地文件失败: {}", key, e);
        }
    }

    /** 解析 key 并防止路径穿越 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(400, "非法的文件路径");
        }
        return target;
    }
}
