package top.qianlink.portfolio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.mapper.ResumeMapper;
import top.qianlink.portfolio.storage.StorageService;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final StorageService storageService;

    /* ---------------- 公开接口 ---------------- */

    public List<Resume> listActive() {
        return resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getActive, true)
                .orderByAsc(Resume::getSortOrder)
                .orderByAsc(Resume::getId));
    }

    /**
     * 对外视图：把存储路径（objectKey）挡掉。
     *
     * <p>为什么必须挡：简历文件和图片放在同一个存储目录下，而 /files/ 是公开发图的
     * （线上由 nginx 直接发）。objectKey 一旦公开，任何拿到它的人都能用 /files/{key}
     * 直接把简历拖走，绕过下载接口的限流、免验证额度与验证码三道闸。
     * key 本身是 uuid 猜不到，但「猜不到」不该是唯一防线。
     */
    public Map<String, Object> publicView(Resume r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle());
        m.put("direction", r.getDirection());
        m.put("fileName", r.getFileName());
        m.put("fileSize", r.getFileSize());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    /** 默认简历：排序最靠前的一份 */
    public Resume getDefault() {
        List<Resume> list = listActive();
        return list.isEmpty() ? null : list.get(0);
    }

    public Resume getById(Long id) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BizException(404, "简历不存在");
        }
        return resume;
    }

    public InputStream openFile(Resume resume) {
        return storageService.get(resume.getObjectKey());
    }

    public void increaseDownload(Long id) {
        resumeMapper.update(null, new LambdaUpdateWrapper<Resume>()
                .eq(Resume::getId, id)
                .setSql("download_count = download_count + 1"));
    }

    /* ---------------- 后台接口 ---------------- */

    public List<Resume> listAll() {
        return resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                .orderByAsc(Resume::getSortOrder)
                .orderByDesc(Resume::getId));
    }

    @Transactional
    public Resume upload(MultipartFile file, String title, String direction, Boolean active) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "resume.pdf";

        String key = storageService.newKey(originalName);
        try (InputStream in = file.getInputStream()) {
            storageService.put(in, key, file.getContentType());
        } catch (IOException e) {
            throw new BizException(500, "读取上传文件失败");
        }

        Resume resume = new Resume();
        resume.setTitle(StringUtils.hasText(title) ? title.trim() : stripExtension(originalName));
        resume.setDirection(direction);
        resume.setFileName(originalName);
        resume.setObjectKey(key);
        resume.setFileSize(file.getSize());
        resume.setContentType(file.getContentType());
        resume.setActive(active == null || active);
        resume.setDownloadCount(0);
        resume.setSortOrder(0);
        resume.setCreatedAt(LocalDateTime.now());
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.insert(resume);
        return resume;
    }

    public Resume update(Long id, String title, String direction, Boolean active, Integer sortOrder) {
        Resume resume = getById(id);
        if (title != null) {
            if (!StringUtils.hasText(title)) {
                throw new BizException(400, "名称不能为空");
            }
            resume.setTitle(title.trim());
        }
        if (direction != null) {
            resume.setDirection(direction);
        }
        if (active != null) {
            resume.setActive(active);
        }
        if (sortOrder != null) {
            resume.setSortOrder(sortOrder);
        }
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);
        return resume;
    }

    @Transactional
    public void delete(Long id) {
        Resume resume = getById(id);
        storageService.delete(resume.getObjectKey());
        resumeMapper.deleteById(id);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
