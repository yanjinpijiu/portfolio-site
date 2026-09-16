package top.qianlink.portfolio.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Project;
import top.qianlink.portfolio.domain.ProjectImage;
import top.qianlink.portfolio.mapper.ProjectImageMapper;
import top.qianlink.portfolio.mapper.ProjectMapper;
import top.qianlink.portfolio.service.SiteContentService;
import top.qianlink.portfolio.storage.StorageService;

import java.time.LocalDateTime;
import java.util.List;

/** 项目的后台增删改查。原来项目数据只能靠 DataSeeder 首次启动时灌，现在可以随时改。 */
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
public class AdminProjectController {

    private final ProjectMapper projectMapper;
    private final ProjectImageMapper projectImageMapper;
    private final SiteContentService siteContentService;
    private final StorageService storageService;

    /** 后台要看到隐藏的项目，所以不复用前台那个带 visible 过滤的查询 */
    @GetMapping
    public ApiResponse<List<Project>> list() {
        return ApiResponse.ok(projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getSortOrder)
                .orderByAsc(Project::getId)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Project> get(@PathVariable Long id) {
        return ApiResponse.ok(siteContentService.require(projectMapper.selectById(id), "项目"));
    }

    @PostMapping
    public ApiResponse<Project> create(@RequestBody Project p) {
        if (p.getSlug() == null || p.getSlug().isBlank()) {
            throw new BizException(400, "slug 不能为空");
        }
        if (p.getName() == null || p.getName().isBlank()) {
            throw new BizException(400, "项目名不能为空");
        }
        String slug = p.getSlug().trim();
        if (!slug.matches("[a-zA-Z0-9-]{1,64}")) {
            throw new BizException(400, "slug 只能用字母、数字和短横线，且不超过 64 位");
        }
        Long exists = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getSlug, slug));
        if (exists != null && exists > 0) {
            throw new BizException(400, "slug「" + slug + "」已经被占用了");
        }

        p.setId(null);
        p.setSlug(slug);
        p.setName(p.getName().trim());
        if (p.getType() == null || p.getType().isBlank()) {
            throw new BizException(400, "项目类型不能为空");
        }
        p.setType(p.getType().trim());
        p.setSortOrder(SiteContentService.sortOrZero(p.getSortOrder()));
        if (p.getVisible() == null) {
            p.setVisible(true);
        }
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(p);
        return ApiResponse.ok(p);
    }

    @PutMapping("/{id}")
    public ApiResponse<Project> update(@PathVariable Long id, @RequestBody Project in) {
        Project p = siteContentService.require(projectMapper.selectById(id), "项目");
        if (in.getSlug() != null && !in.getSlug().equals(p.getSlug())) {
            throw new BizException(400, "slug 是前台地址的一部分，不允许改。要换地址请新建一个项目再把旧的删掉");
        }
        if (in.getName() != null) {
            if (in.getName().isBlank()) {
                throw new BizException(400, "项目名不能为空");
            }
            p.setName(in.getName().trim());
        }
        if (in.getType() != null) p.setType(in.getType().trim());
        if (in.getPeriod() != null) p.setPeriod(in.getPeriod().trim());
        if (in.getRole() != null) p.setRole(in.getRole().trim());
        if (in.getSummary() != null) p.setSummary(in.getSummary().trim());
        if (in.getDescription() != null) p.setDescription(in.getDescription().trim());
        if (in.getTags() != null) p.setTags(in.getTags().trim());
        if (in.getHighlights() != null) p.setHighlights(in.getHighlights().trim());
        if (in.getRepoUrl() != null) p.setRepoUrl(in.getRepoUrl().trim());
        if (in.getRepoLabel() != null) p.setRepoLabel(in.getRepoLabel().trim());
        if (in.getSortOrder() != null) p.setSortOrder(in.getSortOrder());
        if (in.getVisible() != null) p.setVisible(in.getVisible());
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);
        return ApiResponse.ok(p);
    }

    /** 删项目会连带删掉它的成果图和那些图对应的文件，避免留下孤儿数据 */
    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null) {
            return ApiResponse.ok();
        }
        List<ProjectImage> images = projectImageMapper.selectList(
                new LambdaQueryWrapper<ProjectImage>().eq(ProjectImage::getProjectSlug, p.getSlug()));
        for (ProjectImage img : images) {
            if (img.getImageKey() != null) {
                storageService.delete(img.getImageKey());
            }
        }
        projectImageMapper.delete(new LambdaQueryWrapper<ProjectImage>()
                .eq(ProjectImage::getProjectSlug, p.getSlug()));
        projectMapper.deleteById(id);
        return ApiResponse.ok();
    }
}
