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
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Contact;
import top.qianlink.portfolio.domain.SiteProfile;
import top.qianlink.portfolio.domain.SiteSection;
import top.qianlink.portfolio.domain.SiteSetting;
import top.qianlink.portfolio.mapper.ContactMapper;
import top.qianlink.portfolio.mapper.SiteSectionMapper;
import top.qianlink.portfolio.mapper.SiteSettingMapper;
import top.qianlink.portfolio.service.SiteContentService;
import top.qianlink.portfolio.service.SiteSnapshotService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 个人资料 / 联系方式 / 页面文案 / 站点设置。
 *
 * 所有 /api/admin/** 都被 AdminAuthInterceptor 拦着，没带有效 token 直接 401，
 * 不需要在这儿写任何鉴权代码。
 */
@RestController
@RequestMapping("/api/admin/site")
@RequiredArgsConstructor
public class AdminSiteController {

    private final SiteContentService siteContentService;
    private final ContactMapper contactMapper;
    private final SiteSectionMapper sectionMapper;
    private final SiteSettingMapper settingMapper;
    private final SiteSnapshotService snapshotService;

    /* ---------------- 个人资料 ---------------- */

    @GetMapping("/profile")
    public ApiResponse<SiteProfile> profile() {
        return ApiResponse.ok(siteContentService.currentProfile());
    }

    @PutMapping("/profile")
    public ApiResponse<SiteProfile> updateProfile(@RequestBody SiteProfile incoming) {
        return ApiResponse.ok(siteContentService.updateProfile(incoming));
    }

    /** 换头像。传 objectKey 为空字符串表示撤下头像。 */
    @PutMapping("/profile/avatar")
    public ApiResponse<SiteProfile> updateAvatar(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(siteContentService.updateAvatar(
                SiteContentService.blankToNull(body.get("objectKey"))));
    }

    /* ---------------- 联系方式 ---------------- */

    @GetMapping("/contacts")
    public ApiResponse<List<Contact>> contacts() {
        return ApiResponse.ok(contactMapper.selectList(new LambdaQueryWrapper<Contact>()
                .orderByAsc(Contact::getSortOrder)
                .orderByAsc(Contact::getId)));
    }

    @PostMapping("/contacts")
    public ApiResponse<Contact> createContact(@RequestBody Contact c) {
        if (c.getLabel() == null || c.getLabel().isBlank()) {
            throw new BizException(400, "标签不能为空");
        }
        c.setId(null);
        c.setSortOrder(SiteContentService.sortOrZero(c.getSortOrder()));
        if (c.getVisible() == null) {
            c.setVisible(true);
        }
        contactMapper.insert(c);
        return ApiResponse.ok(c);
    }

    @PutMapping("/contacts/{id}")
    public ApiResponse<Contact> updateContact(@PathVariable Long id, @RequestBody Contact in) {
        Contact c = siteContentService.require(contactMapper.selectById(id), "联系方式");
        if (in.getLabel() != null) {
            if (in.getLabel().isBlank()) {
                throw new BizException(400, "标签不能为空");
            }
            c.setLabel(in.getLabel().trim());
        }
        if (in.getValueText() != null) c.setValueText(in.getValueText().trim());
        if (in.getHref() != null) c.setHref(in.getHref().trim());
        if (in.getIcon() != null) c.setIcon(in.getIcon().trim());
        if (in.getSortOrder() != null) c.setSortOrder(in.getSortOrder());
        if (in.getVisible() != null) c.setVisible(in.getVisible());
        contactMapper.updateById(c);
        return ApiResponse.ok(c);
    }

    @DeleteMapping("/contacts/{id}")
    public ApiResponse<Void> deleteContact(@PathVariable Long id) {
        contactMapper.deleteById(id);
        return ApiResponse.ok();
    }

    /* ---------------- 页面区块文案 ---------------- */

    @GetMapping("/sections")
    public ApiResponse<List<SiteSection>> sections() {
        return ApiResponse.ok(sectionMapper.selectList(new LambdaQueryWrapper<SiteSection>()
                .orderByAsc(SiteSection::getId)));
    }

    @PutMapping("/sections/{id}")
    public ApiResponse<SiteSection> updateSection(@PathVariable Long id, @RequestBody SiteSection in) {
        SiteSection s = siteContentService.require(sectionMapper.selectById(id), "页面文案");
        if (in.getEyebrow() != null) s.setEyebrow(in.getEyebrow().trim());
        if (in.getTitle() != null) s.setTitle(in.getTitle().trim());
        if (in.getDescription() != null) s.setDescription(in.getDescription().trim());
        sectionMapper.updateById(s);
        return ApiResponse.ok(s);
    }

    /* ---------------- 站点设置 ---------------- */

    @GetMapping("/settings")
    public ApiResponse<List<SiteSetting>> settings() {
        return ApiResponse.ok(settingMapper.selectList(new LambdaQueryWrapper<SiteSetting>()
                .orderByAsc(SiteSetting::getSortOrder)
                .orderByAsc(SiteSetting::getId)));
    }

    /**
     * 批量保存设置。只更新传进来的 key，没传的保持原值。
     *
     * <p>老库里没有的 key 会新建：设置项是陆续加出来的（比如后来才加的页脚说明和备案号），
     * 只更新已有行的话，这些新键就永远进不了已经在跑的库——而线上库不可能重建。
     * 后台表单提交的都是库里的键，所以新建路径实际只在补新设置项时走到。
     */
    @PutMapping("/settings")
    public ApiResponse<Void> updateSettings(@RequestBody Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return ApiResponse.ok();
        }
        List<SiteSetting> all = settingMapper.selectList(null);
        Set<String> existing = all.stream().map(SiteSetting::getSettingKey).collect(Collectors.toSet());
        int sort = all.stream().mapToInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder()).max().orElse(0);

        for (SiteSetting s : all) {
            if (values.containsKey(s.getSettingKey())) {
                s.setSettingValue(values.get(s.getSettingKey()));
                settingMapper.updateById(s);
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (existing.contains(e.getKey())) {
                continue;
            }
            SiteSetting s = new SiteSetting();
            s.setSettingKey(e.getKey());
            s.setSettingValue(e.getValue());
            s.setLabel(e.getKey());
            s.setHint("后台补建，建议在 ContentSeeder 里补上正式的标签与说明");
            s.setSortOrder(sort + 10);
            settingMapper.insert(s);
        }
        return ApiResponse.ok();
    }

    /* ---------------- 前台内容模式（静态 / 动态） ---------------- */

    /**
     * 当前模式与文件状态。
     *
     * <p>「静态」= 内容取预生成的快照文件，首屏少一次查库往返，但改完内容要点重新生成；
     * 「动态」= 实时查库，改完刷新就生效。两套共用同一份前端产物，只是入口不同。
     */
    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> mode() {
        return ApiResponse.ok(snapshotService.state());
    }

    /** 切换模式。body: {"mode":"static"|"dynamic"} */
    @PostMapping("/mode")
    public ApiResponse<Map<String, Object>> switchMode(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(snapshotService.setMode(body == null ? null : body.get("mode")));
    }

    /** 内容改了之后，把快照重新生成一份（模式不变） */
    @PostMapping("/snapshot")
    public ApiResponse<Map<String, Object>> rebuildSnapshot() {
        return ApiResponse.ok(snapshotService.rebuildSnapshot());
    }
}
