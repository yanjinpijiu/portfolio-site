package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.domain.Certificate;
import top.qianlink.portfolio.domain.Contact;
import top.qianlink.portfolio.domain.Project;
import top.qianlink.portfolio.domain.ProjectImage;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.domain.SiteProfile;
import top.qianlink.portfolio.domain.SiteSection;
import top.qianlink.portfolio.domain.SiteSetting;
import top.qianlink.portfolio.domain.SkillGroup;
import top.qianlink.portfolio.domain.SkillGroupProject;
import top.qianlink.portfolio.domain.SkillItem;
import top.qianlink.portfolio.mapper.CertificateMapper;
import top.qianlink.portfolio.mapper.ContactMapper;
import top.qianlink.portfolio.mapper.ProjectImageMapper;
import top.qianlink.portfolio.mapper.ProjectMapper;
import top.qianlink.portfolio.mapper.ResumeMapper;
import top.qianlink.portfolio.mapper.SiteProfileMapper;
import top.qianlink.portfolio.mapper.SiteSectionMapper;
import top.qianlink.portfolio.mapper.SiteSettingMapper;
import top.qianlink.portfolio.mapper.SkillGroupMapper;
import top.qianlink.portfolio.mapper.SkillGroupProjectMapper;
import top.qianlink.portfolio.mapper.SkillItemMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导出全部站点内容为一份 JSON。
 *
 * <p><b>只导内容，不导统计。</b>访问日志、下载日志、接口日志加起来是数据库里最大的几块，
 * 而且它们不是「我写的东西」，是运行时产生的数据；备份它们靠服务器上的每日整库 tar。
 * 这份 JSON 的用途是「内容被改坏了想找回某一版」——小而全，直接能看。
 *
 * <p>图片也不进 JSON，只有 key：图片本身是磁盘上的文件，同样归每日 tar 管。
 * 导出的是「有哪些图、顺序怎样」这份索引。
 *
 * <p>注意这个接口返回的是文件流（带 Content-Disposition），前端用 fetch 取 blob 再存盘，
 * 不能直接丢个链接——令牌是走请求头的，链接带不上。
 */
@RestController
@RequestMapping("/api/admin/backup")
@RequiredArgsConstructor
public class AdminBackupController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SiteProfileMapper profileMapper;
    private final ContactMapper contactMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillItemMapper skillItemMapper;
    private final SkillGroupProjectMapper skillGroupProjectMapper;
    private final CertificateMapper certificateMapper;
    private final SiteSectionMapper sectionMapper;
    private final SiteSettingMapper settingMapper;
    private final ProjectMapper projectMapper;
    private final ProjectImageMapper projectImageMapper;
    private final ResumeMapper resumeMapper;

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        Map<String, Object> dump = new LinkedHashMap<>();
        // 版本号和导出时间让这份文件自己说得清来历，将来真要写导入也知道按哪版解析
        dump.put("format", "portfolio-content");
        dump.put("version", 1);
        dump.put("exportedAt", LocalDateTime.now().toString());

        dump.put("profile", profileMapper.selectList(null));
        dump.put("contacts", contactMapper.selectList(null));
        dump.put("skillGroups", skillGroupMapper.selectList(null));
        dump.put("skillItems", skillItemMapper.selectList(null));
        dump.put("skillGroupProjects", skillGroupProjectMapper.selectList(null));
        dump.put("certificates", certificateMapper.selectList(null));
        dump.put("sections", sectionMapper.selectList(null));
        dump.put("settings", settingMapper.selectList(null));
        dump.put("projects", projectMapper.selectList(null));
        dump.put("projectImages", projectImageMapper.selectList(null));
        dump.put("resumes", resumeMapper.selectList(null));

        String fileName = "portfolio-content-" + LocalDateTime.now().format(STAMP) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(dump);
    }
}
