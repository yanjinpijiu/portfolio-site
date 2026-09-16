package top.qianlink.portfolio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Certificate;
import top.qianlink.portfolio.domain.Contact;
import top.qianlink.portfolio.domain.ProjectImage;
import top.qianlink.portfolio.domain.SiteProfile;
import top.qianlink.portfolio.domain.SiteSection;
import top.qianlink.portfolio.domain.SiteSetting;
import top.qianlink.portfolio.domain.SkillGroup;
import top.qianlink.portfolio.domain.SkillGroupProject;
import top.qianlink.portfolio.domain.SkillItem;
import top.qianlink.portfolio.mapper.CertificateMapper;
import top.qianlink.portfolio.mapper.ContactMapper;
import top.qianlink.portfolio.mapper.ProjectImageMapper;
import top.qianlink.portfolio.mapper.SiteProfileMapper;
import top.qianlink.portfolio.mapper.SiteSectionMapper;
import top.qianlink.portfolio.mapper.SiteSettingMapper;
import top.qianlink.portfolio.mapper.SkillGroupMapper;
import top.qianlink.portfolio.mapper.SkillGroupProjectMapper;
import top.qianlink.portfolio.mapper.SkillItemMapper;
import top.qianlink.portfolio.storage.StorageService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 站点内容。这些原来都写死在前端 profile.js / certificates.js / galleries.js 里，
 * 现在全部由后台维护。前台拿一份聚合好的 {@link #publicSite()} 就够渲染整个站。
 */
@Service
@RequiredArgsConstructor
public class SiteContentService {

    /** 图片对外访问的前缀，nginx 直接把这个路径映射到存储目录 */
    public static final String FILE_URL_PREFIX = "/files/";

    private final SiteProfileMapper profileMapper;
    private final ContactMapper contactMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillItemMapper skillItemMapper;
    private final SkillGroupProjectMapper skillGroupProjectMapper;
    private final CertificateMapper certificateMapper;
    private final ProjectImageMapper projectImageMapper;
    private final SiteSectionMapper sectionMapper;
    private final SiteSettingMapper settingMapper;
    private final StorageService storageService;

    /** 把存储 key 拼成前台能直接用的相对地址。key 为空时返回 null。 */
    public static String fileUrl(String key) {
        return (key == null || key.isBlank()) ? null : FILE_URL_PREFIX + key;
    }

    /* ==================== 前台聚合 ==================== */

    /** 前台一次性拿走全部内容，避免首屏发出七八个请求。 */
    public Map<String, Object> publicSite() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", publicProfile());
        out.put("contacts", contactMapper.selectList(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getVisible, true)
                .orderByAsc(Contact::getSortOrder)
                .orderByAsc(Contact::getId)));
        out.put("skillGroups", publicSkillGroups());
        out.put("sections", publicSections());
        out.put("settings", publicSettings());
        return out;
    }

    private Map<String, Object> publicProfile() {
        SiteProfile p = currentProfile();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("nameEn", p.getNameEn());
        m.put("title", p.getTitle());
        m.put("school", p.getSchool());
        m.put("graduation", p.getGraduation());
        m.put("city", p.getCity());
        m.put("email", p.getEmail());
        m.put("phone", p.getPhone());
        m.put("avatarUrl", fileUrl(p.getAvatarKey()));
        m.put("intro", p.getIntro());
        m.put("availability", p.getAvailability());
        return m;
    }

    /** 技能分组，把条目、关联项目、证书都挂上去，前台不用再拼。 */
    public List<Map<String, Object>> publicSkillGroups() {
        List<SkillGroup> groups = skillGroupMapper.selectList(new LambdaQueryWrapper<SkillGroup>()
                .eq(SkillGroup::getVisible, true)
                .orderByAsc(SkillGroup::getSortOrder)
                .orderByAsc(SkillGroup::getId));
        if (groups.isEmpty()) {
            return List.of();
        }
        List<Long> ids = groups.stream().map(SkillGroup::getId).toList();

        Map<Long, List<String>> items = skillItemMapper.selectList(new LambdaQueryWrapper<SkillItem>()
                        .in(SkillItem::getGroupId, ids)
                        .orderByAsc(SkillItem::getSortOrder)
                        .orderByAsc(SkillItem::getId))
                .stream()
                .collect(Collectors.groupingBy(SkillItem::getGroupId, LinkedHashMap::new,
                        Collectors.mapping(SkillItem::getText, Collectors.toList())));

        Map<Long, List<String>> related = skillGroupProjectMapper.selectList(
                        new LambdaQueryWrapper<SkillGroupProject>()
                                .in(SkillGroupProject::getGroupId, ids)
                                .orderByAsc(SkillGroupProject::getSortOrder)
                                .orderByAsc(SkillGroupProject::getId))
                .stream()
                .collect(Collectors.groupingBy(SkillGroupProject::getGroupId, LinkedHashMap::new,
                        Collectors.mapping(SkillGroupProject::getProjectSlug, Collectors.toList())));

        Map<Long, List<Certificate>> certs = certificateMapper.selectList(
                        new LambdaQueryWrapper<Certificate>()
                                .eq(Certificate::getVisible, true)
                                .isNotNull(Certificate::getGroupId)
                                .in(Certificate::getGroupId, ids)
                                .orderByAsc(Certificate::getSortOrder)
                                .orderByAsc(Certificate::getId))
                .stream()
                .collect(Collectors.groupingBy(Certificate::getGroupId, LinkedHashMap::new,
                        Collectors.toList()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (SkillGroup g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("category", g.getCategory());
            m.put("items", items.getOrDefault(g.getId(), List.of()));
            m.put("related", related.getOrDefault(g.getId(), List.of()));
            m.put("certificates", certs.getOrDefault(g.getId(), List.of()).stream()
                    .map(this::certificateView).toList());
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> certificateView(Certificate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("title", c.getTitle());
        m.put("org", c.getOrg());
        m.put("date", c.getCertDate());
        m.put("summary", c.getSummary());
        m.put("imageUrl", fileUrl(c.getImageKey()));
        m.put("ratio", c.getRatio());
        return m;
    }

    private Map<String, Map<String, Object>> publicSections() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (SiteSection s : sectionMapper.selectList(null)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eyebrow", s.getEyebrow());
            m.put("title", s.getTitle());
            m.put("description", s.getDescription());
            out.put(s.getSectionKey(), m);
        }
        return out;
    }

    private Map<String, String> publicSettings() {
        return settingMapper.selectList(null).stream()
                .collect(Collectors.toMap(SiteSetting::getSettingKey,
                        s -> s.getSettingValue() == null ? "" : s.getSettingValue(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 某个项目的成果图，前台项目详情页用 */
    public List<Map<String, Object>> projectImages(String slug) {
        if (slug == null || slug.isBlank()) {
            return List.of();
        }
        return projectImageMapper.selectList(new LambdaQueryWrapper<ProjectImage>()
                        .eq(ProjectImage::getProjectSlug, slug)
                        .eq(ProjectImage::getVisible, true)
                        .orderByAsc(ProjectImage::getSortOrder)
                        .orderByAsc(ProjectImage::getId))
                .stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.getId());
                    m.put("url", fileUrl(i.getImageKey()));
                    m.put("caption", i.getCaption());
                    return m;
                })
                .toList();
    }

    /* ==================== 个人资料 ==================== */

    public SiteProfile currentProfile() {
        SiteProfile p = profileMapper.selectById(1L);
        if (p == null) {
            throw new BizException(404, "个人资料还没初始化");
        }
        return p;
    }

    @Transactional
    public SiteProfile updateProfile(SiteProfile incoming) {
        SiteProfile p = currentProfile();
        if (incoming.getName() != null) {
            if (incoming.getName().isBlank()) {
                throw new BizException(400, "姓名不能为空");
            }
            p.setName(incoming.getName().trim());
        }
        if (incoming.getNameEn() != null) p.setNameEn(incoming.getNameEn().trim());
        if (incoming.getTitle() != null) p.setTitle(incoming.getTitle().trim());
        if (incoming.getSchool() != null) p.setSchool(incoming.getSchool().trim());
        if (incoming.getGraduation() != null) p.setGraduation(incoming.getGraduation().trim());
        if (incoming.getCity() != null) p.setCity(incoming.getCity().trim());
        if (incoming.getEmail() != null) p.setEmail(incoming.getEmail().trim());
        if (incoming.getPhone() != null) p.setPhone(incoming.getPhone().trim());
        if (incoming.getIntro() != null) p.setIntro(incoming.getIntro().trim());
        if (incoming.getAvailability() != null) p.setAvailability(incoming.getAvailability().trim());
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        return p;
    }

    /** 换头像。传 null 表示清掉。 */
    @Transactional
    public SiteProfile updateAvatar(String key) {
        SiteProfile p = currentProfile();
        String old = p.getAvatarKey();
        p.setAvatarKey(key);
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        if (old != null && !old.equals(key)) {
            storageService.delete(old);
        }
        return p;
    }

    /* ==================== 通用小工具 ==================== */

    /** 取一条，不存在就 404 —— 各处的 update/delete 都靠它兜底 */
    public <T> T require(T entity, String what) {
        if (entity == null) {
            throw new BizException(404, what + "不存在");
        }
        return entity;
    }

    /** 空字符串统一当 null 处理，避免库里出现大量空串 */
    public static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static Integer sortOrZero(Integer v) {
        return v == null ? 0 : v;
    }
}
