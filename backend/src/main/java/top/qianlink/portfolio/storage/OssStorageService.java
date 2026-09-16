package top.qianlink.portfolio.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.config.AppProperties;

import java.io.InputStream;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "oss")
public class OssStorageService implements StorageService {

    private final OSS client;
    private final String bucket;

    public OssStorageService(AppProperties props) {
        AppProperties.Storage.Oss cfg = props.getStorage().getOss();
        if (!StringUtils.hasText(cfg.getEndpoint())
                || !StringUtils.hasText(cfg.getBucket())
                || !StringUtils.hasText(cfg.getAccessKeyId())
                || !StringUtils.hasText(cfg.getAccessKeySecret())) {
            throw new IllegalStateException(
                    "app.storage.type=oss 时，必须配置 OSS_ENDPOINT / OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET");
        }
        this.bucket = cfg.getBucket();
        this.client = new OSSClientBuilder()
                .build(cfg.getEndpoint(), cfg.getAccessKeyId(), cfg.getAccessKeySecret());
        log.info("OSS 存储已启用: endpoint={} bucket={}", cfg.getEndpoint(), bucket);
    }

    @Override
    public void put(InputStream in, String key, String contentType) {
        try {
            ObjectMetadata meta = new ObjectMetadata();
            if (StringUtils.hasText(contentType)) {
                meta.setContentType(contentType);
            }
            client.putObject(bucket, key, in, meta);
        } catch (Exception e) {
            log.error("OSS 上传失败 key={}", key, e);
            throw new BizException(500, "文件上传失败");
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return client.getObject(bucket, key).getObjectContent();
        } catch (Exception e) {
            log.warn("OSS 读取失败 key={}", key);
            throw new BizException(404, "文件不存在");
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(bucket, key);
        } catch (Exception e) {
            log.warn("OSS 删除失败 key={}", key, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }
}
