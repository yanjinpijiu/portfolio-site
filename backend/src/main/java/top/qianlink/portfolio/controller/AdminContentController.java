package top.qianlink.portfolio.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Certificate;
import top.qianlink.portfolio.domain.ProjectImage;
import top.qianlink.portfolio.domain.SkillGroup;
import top.qianlink.portfolio.domain.SkillGroupProject;
import top.qianlink.portfolio.domain.SkillItem;
import top.qianlink.portfolio.mapper.CertificateMapper;
import top.qianlink.portfolio.mapper.ProjectImageMapper;
import top.qianlink.portfolio.mapper.SkillGroupMapper;
import top.qianlink.portfolio.mapper.SkillGroupProjectMapper;
import top.qianlink.portfolio.mapper.SkillItemMapper;
import top.qianlink.portfolio.service.SiteContentService;
import top.qianlink.portfolio.storage.StorageService;

import java.util.List;

/**
 * 技能分组 / 技能点 / 证书 / 项目成果图的后台管理。
 *
 * 切分方式：分组、条目、关联项目、证书都是独立资源，各自增删改，
 * 前端按 groupId 过滤。比搞一个「一次提交整棵树」的接口好写也好调。
 */
@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

    private final SiteContentService siteContentService;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillItemMapper skillItemMapper;
    private final SkillGroupProjectMapper skillGroupProjectMapper;
    private final CertificateMapper certificateMapper;
    private final ProjectImageMapper projectImageMapper;
    private final StorageService storageService;

    /* ---------------- 技能分组 ---------------- */

    /** 后台要看到隐藏的，所以不复用前台那个带 visible 过滤的方法 */
    @GetMapping("/skill-groups")
    public ApiResponse<List<SkillGroup>> skillGroups() {
        return ApiResponse.ok(skillGroupMapper.selectList(new LambdaQueryWrapper<SkillGroup>()
                .orderByAsc(SkillGroup::getSortOrder)
                .orderByAsc(SkillGroup::getId)));
    }

    @PostMapping("/skill-groups")
    public ApiResponse<SkillGroup> createSkillGroup(@RequestBody SkillGroup g) {
        if (g.getCategory() == null || g.getCategory().isBlank()) {
            throw new BizException(400, "分组名不能为空");
        }
        g.setId(null);
        g.setSortOrder(SiteContentService.sortOrZero(g.getSortOrder()));
        if (g.getVisible() == null) {
            g.setVisible(true);
        }
        skillGroupMapper.insert(g);
        return ApiResponse.ok(g);
    }

    @PutMapping("/skill-groups/{id}")
    public ApiResponse<SkillGroup> updateSkillGroup(@PathVariable Long id, @RequestBody SkillGroup in) {
        SkillGroup g = siteContentService.require(skillGroupMapper.selectById(id), "技能分组");
        if (in.getCategory() != null) {
            if (in.getCategory().isBlank()) {
                throw new BizException(400, "分组名不能为空");
            }
            g.setCategory(in.getCategory().trim());
        }
        if (in.getSortOrder() != null) g.setSortOrder(in.getSortOrder());
        if (in.getVisible() != null) g.setVisible(in.getVisible());
        skillGroupMapper.updateById(g);
        return ApiResponse.ok(g);
    }

    /** 删分组会连带删掉它下面的条目、关联和证书归属，避免留下孤儿数据 */
    @DeleteMapping("/skill-groups/{id}")
    public ApiResponse<Void> deleteSkillGroup(@PathVariable Long id) {
        skillItemMapper.delete(new LambdaQueryWrapper<SkillItem>().eq(SkillItem::getGroupId, id));
        skillGroupProjectMapper.delete(new LambdaQueryWrapper<SkillGroupProject>()
                .eq(SkillGroupProject::getGroupId, id));
        certificateMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Certificate>()
                .eq(Certificate::getGroupId, id)
                .set(Certificate::getGroupId, null));
        skillGroupMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /* ---------------- 技能点 ---------------- */

    @GetMapping("/skill-items")
    public ApiResponse<List<SkillItem>> skillItems(@RequestParam("groupId") Long groupId) {
        return ApiResponse.ok(skillItemMapper.selectList(new LambdaQueryWrapper<SkillItem>()
                .eq(SkillItem::getGroupId, groupId)
                .orderByAsc(SkillItem::getSortOrder)
                .orderByAsc(SkillItem::getId)));
    }

    @PostMapping("/skill-items")
    public ApiResponse<SkillItem> createSkillItem(@RequestBody SkillItem item) {
        if (item.getGroupId() == null) {
            throw new BizException(400, "缺少 groupId");
        }
        if (item.getText() == null || item.getText().isBlank()) {
            throw new BizException(400, "技能内容不能为空");
        }
        item.setId(null);
        item.setText(item.getText().trim());
        item.setSortOrder(SiteContentService.sortOrZero(item.getSortOrder()));
        skillItemMapper.insert(item);
        return ApiResponse.ok(item);
    }

    @PutMapping("/skill-items/{id}")
    public ApiResponse<SkillItem> updateSkillItem(@PathVariable Long id, @RequestBody SkillItem in) {
        SkillItem item = siteContentService.require(skillItemMapper.selectById(id), "技能条目");
        if (in.getText() != null) {
            if (in.getText().isBlank()) {
                throw new BizException(400, "技能内容不能为空");
            }
            item.setText(in.getText().trim());
        }
        if (in.getSortOrder() != null) item.setSortOrder(in.getSortOrder());
        skillItemMapper.updateById(item);
        return ApiResponse.ok(item);
    }

    @DeleteMapping("/skill-items/{id}")
    public ApiResponse<Void> deleteSkillItem(@PathVariable Long id) {
        skillItemMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /* ---------------- 技能分组关联的项目 ---------------- */

    @GetMapping("/skill-related")
    public ApiResponse<List<SkillGroupProject>> skillRelated(@RequestParam("groupId") Long groupId) {
        return ApiResponse.ok(skillGroupProjectMapper.selectList(
                new LambdaQueryWrapper<SkillGroupProject>()
                        .eq(SkillGroupProject::getGroupId, groupId)
                        .orderByAsc(SkillGroupProject::getSortOrder)
                        .orderByAsc(SkillGroupProject::getId)));
    }

    /** 关联项目用 slug 而不是 id：项目删了这里会查不到，前台静默忽略，不会报错 */
    @PostMapping("/skill-related")
    public ApiResponse<SkillGroupProject> createSkillRelated(@RequestBody SkillGroupProject r) {
        if (r.getGroupId() == null || r.getProjectSlug() == null || r.getProjectSlug().isBlank()) {
            throw new BizException(400, "缺少 groupId 或 projectSlug");
        }
        long exists = skillGroupProjectMapper.selectCount(new LambdaQueryWrapper<SkillGroupProject>()
                .eq(SkillGroupProject::getGroupId, r.getGroupId())
                .eq(SkillGroupProject::getProjectSlug, r.getProjectSlug().trim()));
        if (exists > 0) {
            throw new BizException(400, "这个项目已经关联过了");
        }
        r.setId(null);
        r.setProjectSlug(r.getProjectSlug().trim());
        r.setSortOrder(SiteContentService.sortOrZero(r.getSortOrder()));
        skillGroupProjectMapper.insert(r);
        return ApiResponse.ok(r);
    }

    @DeleteMapping("/skill-related/{id}")
    public ApiResponse<Void> deleteSkillRelated(@PathVariable Long id) {
        skillGroupProjectMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /* ---------------- 证书 ---------------- */

    @GetMapping("/certificates")
    public ApiResponse<List<Certificate>> certificates() {
        return ApiResponse.ok(certificateMapper.selectList(new LambdaQueryWrapper<Certificate>()
                .orderByAsc(Certificate::getSortOrder)
                .orderByAsc(Certificate::getId)));
    }

    @PostMapping("/certificates")
    public ApiResponse<Certificate> createCertificate(@RequestBody Certificate c) {
        if (c.getTitle() == null || c.getTitle().isBlank()) {
            throw new BizException(400, "证书名称不能为空");
        }
        c.setId(null);
        c.setTitle(c.getTitle().trim());
        c.setSortOrder(SiteContentService.sortOrZero(c.getSortOrder()));
        if (c.getVisible() == null) {
            c.setVisible(true);
        }
        certificateMapper.insert(c);
        return ApiResponse.ok(c);
    }

    @PutMapping("/certificates/{id}")
    public ApiResponse<Certificate> updateCertificate(@PathVariable Long id, @RequestBody Certificate in) {
        Certificate c = siteContentService.require(certificateMapper.selectById(id), "证书");
        String oldImage = c.getImageKey();
        if (in.getTitle() != null) {
            if (in.getTitle().isBlank()) {
                throw new BizException(400, "证书名称不能为空");
            }
            c.setTitle(in.getTitle().trim());
        }
        if (in.getOrg() != null) c.setOrg(in.getOrg().trim());
        if (in.getCertDate() != null) c.setCertDate(in.getCertDate().trim());
        if (in.getSummary() != null) c.setSummary(in.getSummary().trim());
        if (in.getImageKey() != null) c.setImageKey(SiteContentService.blankToNull(in.getImageKey()));
        if (in.getRatio() != null) c.setRatio(in.getRatio().trim());
        if (in.getGroupId() != null) c.setGroupId(in.getGroupId());
        if (in.getSortOrder() != null) c.setSortOrder(in.getSortOrder());
        if (in.getVisible() != null) c.setVisible(in.getVisible());
        certificateMapper.updateById(c);

        // 换了图片就把旧文件删掉，不然存储目录会越攒越多
        if (oldImage != null && !oldImage.equals(c.getImageKey())) {
            storageService.delete(oldImage);
        }
        return ApiResponse.ok(c);
    }

    @DeleteMapping("/certificates/{id}")
    public ApiResponse<Void> deleteCertificate(@PathVariable Long id) {
        Certificate c = certificateMapper.selectById(id);
        if (c != null) {
            if (c.getImageKey() != null) {
                storageService.delete(c.getImageKey());
            }
            certificateMapper.deleteById(id);
        }
        return ApiResponse.ok();
    }

    /* ---------------- 项目成果图 ---------------- */

    @GetMapping("/project-images")
    public ApiResponse<List<ProjectImage>> projectImages(@RequestParam("slug") String slug) {
        return ApiResponse.ok(projectImageMapper.selectList(new LambdaQueryWrapper<ProjectImage>()
                .eq(ProjectImage::getProjectSlug, slug)
                .orderByAsc(ProjectImage::getSortOrder)
                .orderByAsc(ProjectImage::getId)));
    }

    @PostMapping("/project-images")
    public ApiResponse<ProjectImage> createProjectImage(@RequestBody ProjectImage img) {
        if (img.getProjectSlug() == null || img.getProjectSlug().isBlank()) {
            throw new BizException(400, "缺少 projectSlug");
        }
        if (img.getImageKey() == null || img.getImageKey().isBlank()) {
            throw new BizException(400, "请先上传图片");
        }
        img.setId(null);
        img.setProjectSlug(img.getProjectSlug().trim());
        img.setSortOrder(SiteContentService.sortOrZero(img.getSortOrder()));
        if (img.getVisible() == null) {
            img.setVisible(true);
        }
        projectImageMapper.insert(img);
        return ApiResponse.ok(img);
    }

    @PutMapping("/project-images/{id}")
    public ApiResponse<ProjectImage> updateProjectImage(@PathVariable Long id, @RequestBody ProjectImage in) {
        ProjectImage img = siteContentService.require(projectImageMapper.selectById(id), "项目图片");
        String oldImage = img.getImageKey();
        if (in.getImageKey() != null) img.setImageKey(SiteContentService.blankToNull(in.getImageKey()));
        if (in.getCaption() != null) img.setCaption(in.getCaption().trim());
        if (in.getSortOrder() != null) img.setSortOrder(in.getSortOrder());
        if (in.getVisible() != null) img.setVisible(in.getVisible());
        projectImageMapper.updateById(img);

        if (oldImage != null && !oldImage.equals(img.getImageKey())) {
            storageService.delete(oldImage);
        }
        return ApiResponse.ok(img);
    }

    @DeleteMapping("/project-images/{id}")
    public ApiResponse<Void> deleteProjectImage(@PathVariable Long id) {
        ProjectImage img = projectImageMapper.selectById(id);
        if (img != null) {
            if (img.getImageKey() != null) {
                storageService.delete(img.getImageKey());
            }
            projectImageMapper.deleteById(id);
        }
        return ApiResponse.ok();
    }
}
