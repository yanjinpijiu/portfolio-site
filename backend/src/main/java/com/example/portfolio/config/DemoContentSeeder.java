package com.example.portfolio.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.example.portfolio.domain.Contact;
import com.example.portfolio.domain.Project;
import com.example.portfolio.domain.ProjectImage;
import com.example.portfolio.domain.Resume;
import com.example.portfolio.domain.SiteProfile;
import com.example.portfolio.domain.SiteSection;
import com.example.portfolio.domain.SiteSetting;
import com.example.portfolio.domain.SkillGroup;
import com.example.portfolio.domain.SkillGroupProject;
import com.example.portfolio.domain.SkillItem;
import com.example.portfolio.mapper.ContactMapper;
import com.example.portfolio.mapper.ProjectImageMapper;
import com.example.portfolio.mapper.ProjectMapper;
import com.example.portfolio.mapper.ResumeMapper;
import com.example.portfolio.mapper.SiteProfileMapper;
import com.example.portfolio.mapper.SiteSectionMapper;
import com.example.portfolio.mapper.SiteSettingMapper;
import com.example.portfolio.mapper.SkillGroupMapper;
import com.example.portfolio.mapper.SkillGroupProjectMapper;
import com.example.portfolio.mapper.SkillItemMapper;
import com.example.portfolio.storage.StorageService;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 示例数据：首次启动（各表为空）时灌一份**虚构**的简历站内容，让人克隆下来就能看到完整效果。
 *
 * <p>和「仓库不放任何真实内容」这条规矩并不冲突：这里的每个字都是编的——人设叫李明，
 * 电话邮箱用的是 `example.com` 与 138 段保留号，项目是四个通用练手项目，
 * 头像与配图都是程序画出来的几何图形，简历 PDF 上也写着「示例」。
 *
 * <p>想换成自己的内容，两条路：后台直接改（推荐），或者把这个类删掉、写你自己的 Seeder。
 * 各表独立判空，所以已经改过的内容不会被重新灌一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoContentSeeder implements ApplicationRunner {

    private final SiteProfileMapper profileMapper;
    private final ContactMapper contactMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillItemMapper skillItemMapper;
    private final SkillGroupProjectMapper skillGroupProjectMapper;
    private final ProjectMapper projectMapper;
    private final ProjectImageMapper projectImageMapper;
    private final SiteSectionMapper sectionMapper;
    private final SiteSettingMapper settingMapper;
    private final ResumeMapper resumeMapper;
    private final StorageService storageService;

    private static final String AVATAR_KEY = "seed/avatar-demo.png";
    private static final String RESUME_KEY = "seed/resume-example.pdf";

    @Override
    public void run(ApplicationArguments args) {
        seedProfile();
        seedContacts();
        seedSkills();
        seedProjects();
        seedProjectImages();
        seedSections();
        seedSettings();
        seedResume();
    }

    private boolean isEmpty(com.baomidou.mybatisplus.core.mapper.BaseMapper<?> mapper) {
        Long c = mapper.selectCount(null);
        return c == null || c == 0;
    }

    /* ------------------------------------------------------------------ 人设 */

    private void seedProfile() {
        if (!isEmpty(profileMapper)) {
            return;
        }
        SiteProfile p = new SiteProfile();
        p.setId(1L);
        p.setName("李明");
        p.setNameEn("Li Ming");
        p.setTitle("Java 后端开发 · 全栈");
        p.setSchool("示例大学 · 计算机科学与技术 · 本科");
        p.setGraduation("2027 届");
        p.setCity("杭州");
        p.setEmail("liming@example.com");
        p.setPhone("13800138000");
        p.setAvatarKey(generateAvatar());
        p.setIntro("""
                示例大学计算机科学与技术专业 2027 届本科生，主攻 Java 后端，顺手能写点前端。
                做过商城、博客、任务看板与短链服务四个练手项目，习惯先把一件事做到能跑通、能被别人用，再回头补原理。
                这个站是「简历 PDF 之外的补充」：PDF 只能列要点，这里把每个项目要解决什么问题、我具体做了什么、最后拿到什么结果铺开写。
                （本站是开源模板的演示实例，页面上的资料与项目均为虚构示例数据。）""");
        p.setAvailability("可实习 · 每周 4 天以上 · 杭州 / 上海");
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.insert(p);
        log.info("已灌入示例个人资料（虚构）");
    }

    private void seedContacts() {
        if (!isEmpty(contactMapper)) {
            return;
        }
        contact("邮箱", "liming@example.com", "mailto:liming@example.com", "mail", 10);
        contact("电话", "13800138000", "tel:13800138000", "phone", 20);
        contact("源码仓库", "本模板的 GitHub 仓库", "https://github.com/yanjinpijiu/portfolio-site", "github", 30);
        log.info("已灌入示例联系方式（虚构）");
    }

    private void contact(String label, String value, String href, String icon, int sort) {
        Contact c = new Contact();
        c.setLabel(label);
        c.setValueText(value);
        c.setHref(href);
        c.setIcon(icon);
        c.setSortOrder(sort);
        c.setVisible(true);
        contactMapper.insert(c);
    }

    /* ------------------------------------------------------------------ 技能 */

    private void seedSkills() {
        if (!isEmpty(skillGroupMapper)) {
            return;
        }

        Long backend = group("Java 后端", 10);
        item(backend, "Java（SpringBoot / MyBatis-Plus）");
        item(backend, "RESTful 接口设计与参数校验");
        item(backend, "事务、异常处理与统一响应结构");
        related(backend, "demo-mall", "demo-blog", "demo-shortlink");

        Long db = group("数据库与缓存", 20);
        item(db, "MySQL：索引、慢查询分析与表结构设计");
        item(db, "Redis：缓存、分布式锁与限流");
        item(db, "消息队列：异步解耦与重复消费处理");
        related(db, "demo-mall", "demo-shortlink");

        Long front = group("前端", 30);
        item(front, "Vue 3 + Vite");
        item(front, "Axios 封装与接口层设计");
        item(front, "Element Plus / 手写样式");
        related(front, "demo-blog", "demo-task");

        Long ops = group("部署与工具", 40);
        item(ops, "Linux 常用命令与服务排查");
        item(ops, "Docker 容器化与 docker compose");
        item(ops, "Nginx 反向代理与静态资源");
        item(ops, "Git 分支协作与代码评审");
        related(ops, "demo-shortlink", "demo-task");

        log.info("已灌入示例技能分组（虚构）");
    }

    private Long group(String category, int sort) {
        SkillGroup g = new SkillGroup();
        g.setCategory(category);
        g.setSortOrder(sort);
        g.setVisible(true);
        skillGroupMapper.insert(g);
        return g.getId();
    }

    private void item(Long groupId, String text) {
        SkillItem i = new SkillItem();
        i.setGroupId(groupId);
        i.setText(text);
        i.setSortOrder(nextItemSort(groupId));
        skillItemMapper.insert(i);
    }

    private int nextItemSort(Long groupId) {
        Long n = skillItemMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SkillItem>()
                        .eq(SkillItem::getGroupId, groupId));
        return (int) ((n == null ? 0 : n) + 1) * 10;
    }

    private void related(Long groupId, String... slugs) {
        int sort = 10;
        for (String slug : slugs) {
            SkillGroupProject r = new SkillGroupProject();
            r.setGroupId(groupId);
            r.setProjectSlug(slug);
            r.setSortOrder(sort);
            skillGroupProjectMapper.insert(r);
            sort += 10;
        }
    }

    /* ------------------------------------------------------------------ 项目 */

    private void seedProjects() {
        if (!isEmpty(projectMapper)) {
            return;
        }

        project("demo-mall", "在线商城系统", "后端项目", "2025.09 — 2025.12", "个人项目 · 后端为主",
                "从商品浏览到下单支付的完整交易链路，重点做了库存扣减的并发安全与订单状态流转。",
                """
                练手用的电商后端：商品、购物车、订单、支付回调、退款与后台管理。
                前端只做了必要页面，主要精力放在后端——尤其是「下单扣库存」这个经典并发问题上。
                （示例项目，描述为演示用途编写。）""",
                """
                SpringBoot
                MyBatis-Plus
                MySQL
                Redis
                RabbitMQ""",
                """
                库存并发：把扣减放到 Redis 预扣 + 数据库乐观锁两段，超卖在压测中降到 0，重复下单被幂等键拦住
                订单状态机：用状态枚举加合法流转校验，把「已支付又退款」「已取消又发货」这类脏状态挡在业务层
                缓存设计：商品详情走「先更新库再删缓存」，热点商品加空值缓存防穿透
                接口规范：统一响应结构与全局异常处理，参数校验放在 Controller 层，业务异常用错误码区分
                压测：对下单接口做 500 并发压测，定位到数据库连接池是瓶颈后调整了池大小与超时""",
                null,
                "示例项目（无仓库）",
                10);

        project("demo-blog", "个人博客系统", "全栈项目", "2025.06 — 2025.08", "个人项目 · 前后端",
                "Markdown 写作 + 评论 + 后台管理的个人博客，前端 Vue 3，后端 SpringBoot。",
                """
                自己写东西的地方：文章用 Markdown 写，前台渲染成 HTML，支持标签分类、全文检索与评论。
                后端提供接口与后台管理页，前端是 Vue 3 单页应用。
                （示例项目，描述为演示用途编写。）""",
                """
                Vue 3
                Vite
                SpringBoot
                MyBatis-Plus
                MySQL""",
                """
                内容渲染：Markdown 在服务端渲染并做 XSS 过滤，图片统一走图床接口上传
                全文检索：先用 MySQL LIKE 起步，数据量上来后换成倒排索引思路，搜索耗时从 800ms 降到 60ms
                评论防刷：同 IP 限流 + 敏感词过滤 + 先审后发，后台能看到待审队列
                部署：Nginx 反代 + 静态资源长缓存，发布脚本一条命令完成构建与上线""",
                null,
                "示例项目（无仓库）",
                20);

        project("demo-task", "任务管理工具", "全栈项目", "2026.03 — 2026.05", "小组项目 · 前端 + 接口",
                "看板式任务管理：拖拽改状态、多人协作、操作记录，前端 Vue 3 + 后端接口。",
                """
                小组协作工具：任务按「待办 / 进行中 / 已完成」三列看板展示，支持拖拽改状态、指派负责人、
                加标签与截止时间，并记录每次变更的操作日志。
                （示例项目，描述为演示用途编写。）""",
                """
                Vue 3
                Element Plus
                SpringBoot
                MySQL""",
                """
                拖拽交互：用 HTML5 拖放 API 实现跨列拖拽，落位时先本地更新再发请求，失败自动回滚
                协作冲突：同一条任务被两个人同时改时用版本号做乐观锁，后提交的人会看到「已被他人修改」提示
                操作日志：每次变更写一条记录（谁、什么时候、改了什么），按任务维度倒序展示
                接口设计：按资源拆接口，列表查询支持按状态、负责人、标签组合筛选与分页""",
                null,
                "示例项目（无仓库）",
                30);

        project("demo-shortlink", "短链接服务", "后端项目", "2026.01 — 2026.02", "个人项目 · 后端",
                "把长链接压成短链并统计点击，重点解决缓存穿透、热点 key 与跳转性能。",
                """
                短链服务：长链接转短链、短链跳转、点击统计。
                核心是跳转接口——它会被高频访问，所以缓存与防穿透是这块的重点。
                （示例项目，描述为演示用途编写。）""",
                """
                SpringBoot
                Redis
                MySQL
                Nginx""",
                """
                发号策略：用「号段模式」批量取 ID 再转 62 进制，避免每次生成都查库
                缓存防护：布隆过滤器拦掉不存在的短码（约 90% 的非法请求）+ 空值缓存兜底
                热点 key：跳转接口对同一短码加本地缓存 + 分布式锁双检，减少回源
                统计：点击事件异步落库（队列 + 批量写），跳转响应时间不受统计影响
                部署：Nginx 层做跳转重定向，后端只处理生成与统计，单机压测 QPS 提升明显""",
                null,
                "示例项目（无仓库）",
                40);

        log.info("已灌入 4 个示例项目（虚构）");
    }

    private void project(String slug, String name, String type, String period, String role,
                         String summary, String description, String tags, String highlights,
                         String repoUrl, String repoLabel, int sortOrder) {
        Project p = new Project();
        p.setSlug(slug);
        p.setName(name);
        p.setType(type);
        p.setPeriod(period);
        p.setRole(role);
        p.setSummary(summary);
        p.setDescription(description.trim());
        p.setTags(tags.trim());
        p.setHighlights(highlights.trim());
        p.setRepoUrl(repoUrl);
        p.setRepoLabel(repoLabel);
        p.setSortOrder(sortOrder);
        p.setVisible(true);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(p);
    }

    private void seedProjectImages() {
        if (!isEmpty(projectImageMapper)) {
            return;
        }
        gallery("demo-mall", "示例配图：程序生成的占位图（商品与订单示意）", 210);
        gallery("demo-blog", "示例配图：程序生成的占位图（文章与评论示意）", 150);
        gallery("demo-task", "示例配图：程序生成的占位图（看板与任务示意）", 90);
        gallery("demo-shortlink", "示例配图：程序生成的占位图（跳转与统计示意）", 30);
        log.info("已灌入示例项目配图（程序生成的占位图）");
    }

    private void gallery(String slug, String caption, int hue) {
        String key = "seed/project-" + slug + ".png";
        generateProjectImage(key, hue);
        ProjectImage img = new ProjectImage();
        img.setProjectSlug(slug);
        img.setImageKey(key);
        img.setCaption(caption);
        img.setSortOrder(10);
        img.setVisible(true);
        projectImageMapper.insert(img);
    }

    /* ------------------------------------------------------------- 页面文案 */

    private void seedSections() {
        if (!isEmpty(sectionMapper)) {
            return;
        }
        section("home.skills", "Skills", "专业技能", "点击任意一项，可以看到它具体用在哪些项目里。");
        section("home.projects", "Projects", "项目经历",
                "四个示例项目覆盖了后端并发、全栈协作与缓存优化这几类常见场景，每一项都写了具体做法与结果。");
        section("home.practice", null, "其他实践", null);
        section("home.contact", "Contact", "联系方式",
                "本站是开源模板的演示实例，联系方式均为虚构示例；想换成自己的内容，登录后台改就行。");
        section("projects.header", "Projects", "项目经历",
                "每个项目都写了它要解决的问题、我具体做了什么，以及最后拿到了什么结果。点击卡片可以看完整说明。");
        section("projects.practice", null, "其他实践", "课程实验与动手实践，偏底层与工程基础。");
        section("resume.header", "Resume", "简历下载",
                "这里的简历是一份虚构的示例文件（用来演示下载与验证码流程），换成你自己的 PDF 只需在后台重新上传。");
        log.info("已灌入示例页面文案");
    }

    private void section(String key, String eyebrow, String title, String description) {
        SiteSection s = new SiteSection();
        s.setSectionKey(key);
        s.setEyebrow(eyebrow);
        s.setTitle(title);
        s.setDescription(description);
        sectionMapper.insert(s);
    }

    /**
     * 站点设置。按 key 逐个补齐（理由同生产站的种子：设置项是陆续加的，
     * 只在整表为空时灌会让新键永远进不了已在跑的库），已有行只补标签与说明、不动值。
     */
    private void seedSettings() {
        Map<String, String[]> items = new LinkedHashMap<>();
        items.put("seo.title", new String[]{"李明 · 示例简历站（demo）", "浏览器标题", null});
        items.put("seo.description", new String[]{
                "一个演示用的个人简历站模板：项目展示、简历下载、访问统计与内容后台。示例数据均为虚构。",
                "搜索引擎描述",
                "改这里不会自动生效：爬虫读的是 index.html 里的静态内容，改完要把 frontend/index.html 的 description 和 og 标签一起改掉"});
        items.put("seo.ogTitle", new String[]{"李明 · 示例简历站（demo）", "分享卡片标题", null});
        items.put("seo.ogDescription", new String[]{
                "演示用的个人简历站模板，示例数据均为虚构。",
                "分享卡片描述", null});
        items.put("footer.copyright", new String[]{"© 2026 李明（演示数据）", "页脚版权",
                "只写权利人，学校等信息另外放，别塞在 © 后面"});
        items.put("footer.notice", new String[]{
                "本站是个人简历站开源模板的演示实例，页面内容均为虚构示例；访问日志（含 IP）仅用于访问统计。",
                "页脚说明",
                "写清楚网站性质与日志用途。若以后加了广告或收费，这句要相应改掉"});
        items.put("footer.icp", new String[]{"", "ICP 备案号", "例如 浙ICP备2024xxxxxx号-1；填了才显示，并自动链到 beian.miit.gov.cn"});
        items.put("nav.settings", new String[]{"设置", "页脚设置入口文案", null});

        Map<String, SiteSetting> existing = settingMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(SiteSetting::getSettingKey, x -> x,
                        (a, b) -> a, LinkedHashMap::new));

        int sort = 10;
        for (Map.Entry<String, String[]> e : items.entrySet()) {
            String key = e.getKey();
            SiteSetting exists = existing.get(key);
            if (exists != null) {
                if (key.equals(exists.getLabel())) {
                    exists.setLabel(e.getValue()[1]);
                    exists.setHint(e.getValue()[2]);
                    exists.setSortOrder(sort);
                    settingMapper.updateById(exists);
                }
                sort += 10;
                continue;
            }
            SiteSetting s = new SiteSetting();
            s.setSettingKey(key);
            s.setSettingValue(e.getValue()[0]);
            s.setLabel(e.getValue()[1]);
            s.setHint(e.getValue()[2]);
            s.setSortOrder(sort);
            settingMapper.insert(s);
            sort += 10;
        }
        log.info("示例站点设置已按 key 补齐");
    }

    /* ------------------------------------------------------------------ 简历 */

    private void seedResume() {
        if (!isEmpty(resumeMapper)) {
            return;
        }
        ClassPathResource res = new ClassPathResource("seed-demo/resume-example.pdf");
        if (!res.exists()) {
            log.warn("示例简历 PDF 缺失（classpath:seed-demo/resume-example.pdf），跳过简历初始化");
            return;
        }
        long size;
        try (InputStream in = res.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            size = bytes.length;
            try (InputStream again = new ByteArrayInputStream(bytes)) {
                storageService.put(again, RESUME_KEY, "application/pdf");
            }
        } catch (Exception e) {
            log.warn("写入示例简历失败：{}", e.toString());
            return;
        }

        Resume r = new Resume();
        r.setTitle("示例简历（虚构数据）");
        r.setDirection("Java 后端");
        r.setFileName("resume-example-liming.pdf");
        r.setObjectKey(RESUME_KEY);
        r.setFileSize(size);
        r.setContentType("application/pdf");
        r.setActive(true);
        r.setDownloadCount(0);
        r.setSortOrder(10);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        resumeMapper.insert(r);
        log.info("已灌入示例简历（虚构，{} 字节）", size);
    }

    /* ------------------------------------------------------- 程序生成的图片 */

    /**
     * 头像：纯几何图形，不画文字。
     *
     * <p>为什么不画字：Linux 上的 JDK 自身不带中文字体，服务器上画中文只会得到一排方块，
     * 而头像/配图是启动时生成的——出问题也是「静默出问题」，所以干脆只用形状。
     */
    private String generateAvatar() {
        int size = 320;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0xEEF4FB), size, size, new Color(0xD8E6F5)));
            g.fillRect(0, 0, size, size);
            g.setColor(new Color(0x9FB8D0));
            g.fill(new Ellipse2D.Double(size * 0.33, size * 0.20, size * 0.34, size * 0.34));
            g.fill(new RoundRectangle2D.Double(size * 0.20, size * 0.58, size * 0.60, size * 0.42, size * 0.30, size * 0.30));
        } finally {
            g.dispose();
        }
        return writeImage(img, AVATAR_KEY);
    }

    /** 项目配图：一块渐变底 + 几个几何形状，用色相区分项目。 */
    private void generateProjectImage(String key, int hue) {
        int w = 1200;
        int h = 675;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color light = Color.getHSBColor(hue / 360f, 0.18f, 0.97f);
            Color deep = Color.getHSBColor(hue / 360f, 0.42f, 0.78f);
            g.setPaint(new GradientPaint(0, 0, light, w, h, deep));
            g.fillRect(0, 0, w, h);

            g.setColor(new Color(255, 255, 255, 70));
            g.fill(new RoundRectangle2D.Double(w * 0.08, h * 0.18, w * 0.36, h * 0.26, 24, 24));
            g.fill(new RoundRectangle2D.Double(w * 0.08, h * 0.52, w * 0.52, h * 0.20, 24, 24));

            g.setColor(new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(6f));
            g.draw(new RoundRectangle2D.Double(w * 0.56, h * 0.18, w * 0.36, h * 0.54, 28, 28));
            g.draw(new Ellipse2D.Double(w * 0.66, h * 0.32, w * 0.16, h * 0.16));
            g.fill(new Ellipse2D.Double(w * 0.70, h * 0.36, w * 0.08, h * 0.08));
        } finally {
            g.dispose();
        }
        writeImage(img, key);
    }

    private String writeImage(BufferedImage img, String key) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            try (InputStream in = new ByteArrayInputStream(out.toByteArray())) {
                storageService.put(in, key, "image/png");
            }
            return key;
        } catch (Exception e) {
            log.warn("生成示例图片失败：{}（{}）", key, e.toString());
            return null;
        }
    }

    /** 供日志确认种子是否灌入（列表按 sortOrder 排，这里只是拿数量） */
    @SuppressWarnings("unused")
    private List<Project> seededProjects() {
        return projectMapper.selectList(null);
    }
}
