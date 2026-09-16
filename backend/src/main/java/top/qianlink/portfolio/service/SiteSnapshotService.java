package top.qianlink.portfolio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.config.AppProperties;
import top.qianlink.portfolio.domain.Project;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.domain.SiteSetting;
import top.qianlink.portfolio.mapper.ResumeMapper;
import top.qianlink.portfolio.mapper.SiteSettingMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点的「静态 / 动态」两种模式。
 *
 * <p><b>为什么要两种模式</b>：动态模式的内容全部实时查库，改完后台刷新就生效，
 * 但首屏要多等一次 `/api/site` 往返（公网实测约 60ms）。静态模式把内容预先固化成
 * 一个 JSON 文件放在站点目录里，首屏取的是静态文件、不查库，也少一次往返；
 * 代价是**内容被冻结**——后台改了内容要点一下「重新生成快照」才会反映到前台。
 *
 * <p>实现上只动两个文件，不重新构建前端：
 * <ul>
 *   <li>`snapshot.json`：从数据库生成的内容快照，动态模式下这个文件不存在；</li>
 *   <li>`index.html` 里的一行 `window.__SITE_MODE__ = "static"`：切到静态就插进去，
 *       切回动态就删掉。**改同一个文件而不是生成第二个入口页**，是为了让 nginx 完全不用改——
 *       首页和 SPA 回退都指向 index.html，插了标记就整站都走静态模式，
 *       否则还得再配一条「优先命中静态入口」的规则，多一处会写错的地方。</li>
 * </ul>
 *
 * <p>前端只认一个开关：`window.__SITE_MODE__`。静态模式去取 `/snapshot.json`
 * （静态文件、可缓存），动态模式照旧取 `/api/site`。
 *
 * <p><b>本地开发没有 nginx 站点目录</b>（`app.web-root` 为空），这时切换只记录设置、
 * 不动文件，并在返回信息里说清楚，免得以为是坏了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteSnapshotService {

    public static final String MODE_STATIC = "static";
    public static final String MODE_DYNAMIC = "dynamic";

    /** 模式存在站点设置里，和别的设置一起显示在后台 */
    private static final String MODE_KEY = "site.mode";
    private static final String MODE_LABEL = "前台内容模式";
    private static final String MODE_HINT =
            "dynamic = 每次访问实时查库，后台改完立刻生效；static = 内容取预生成的快照，首屏快一次往返，改完要点「重新生成快照」";

    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String INDEX_FILE = "index.html";

    /** 注入到 index.html 的那行标记；前端靠它决定去哪儿取内容 */
    private static final String FLAG_LINE = "<script>window.__SITE_MODE__=\"static\"</script>";

    /** 判断标记在不在，用这个片段匹配（改 FLAG_LINE 时两边一起看） */
    private static final String FLAG_MARKER = "__SITE_MODE__";

    private final AppProperties props;
    private final SiteContentService siteContentService;
    private final ProjectService projectService;
    private final ResumeMapper resumeMapper;
    private final ResumeService resumeService;
    private final SiteSettingMapper settingMapper;
    private final ObjectMapper objectMapper;

    /* ==================== 对外 ==================== */

    public String currentMode() {
        SiteSetting setting = settingMapper.selectOne(new LambdaQueryWrapper<SiteSetting>()
                .eq(SiteSetting::getSettingKey, MODE_KEY)
                .last("limit 1"));
        if (setting == null || !StringUtils.hasText(setting.getSettingValue())) {
            return MODE_DYNAMIC;
        }
        return MODE_STATIC.equals(setting.getSettingValue()) ? MODE_STATIC : MODE_DYNAMIC;
    }

    /** 当前状态，给后台显示用 */
    public Map<String, Object> state() {
        String mode = currentMode();
        Path root = webRoot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("webRoot", root == null ? null : root.toString());
        out.put("available", root != null && Files.isDirectory(root));

        Path snapshot = root == null ? null : root.resolve(SNAPSHOT_FILE);
        boolean snapshotExists = snapshot != null && Files.isRegularFile(snapshot);
        out.put("snapshotExists", snapshotExists);
        out.put("snapshotSize", snapshotExists ? sizeOf(snapshot) : 0);
        out.put("snapshotAt", snapshotExists ? readGeneratedAt(snapshot) : null);
        out.put("entryPatched", root != null && indexHasFlag(root));

        // 状态对不上就说明是脏的（比如部署把 index.html 覆盖回去了），提示一句而不是装没事
        String note = null;
        if (root == null || !Files.isDirectory(root)) {
            note = "本机没有配置 nginx 站点目录（app.web-root），切换只在服务器上生效";
        } else if (MODE_STATIC.equals(mode) && !snapshotExists) {
            note = "静态模式但快照文件不存在，点「切到静态」或「重新生成快照」修复";
        } else if (MODE_STATIC.equals(mode) && !indexHasFlag(root)) {
            note = "静态模式但入口页没打上标记（多半是刚部署覆盖过），点「重新生成快照」修复";
        }
        out.put("note", note);
        return out;
    }

    /** 切换模式：先记设置，再动文件 */
    public Map<String, Object> setMode(String mode) {
        if (!MODE_STATIC.equals(mode) && !MODE_DYNAMIC.equals(mode)) {
            throw new BizException(400, "模式只能是 static 或 dynamic");
        }
        saveSetting(mode);
        apply(mode);
        return state();
    }

    /** 重新生成快照（内容改了之后用），模式不变 */
    public Map<String, Object> rebuildSnapshot() {
        apply(currentMode());
        return state();
    }

    /* ==================== 内部 ==================== */

    private void apply(String mode) {
        Path root = webRoot();
        if (root == null || !Files.isDirectory(root)) {
            log.info("没有站点目录（app.web-root），模式已记为 {}，文件未改动", mode);
            return;
        }
        try {
            if (MODE_STATIC.equals(mode)) {
                writeSnapshot(root.resolve(SNAPSHOT_FILE));
                patchIndex(root, true);
                log.info("已切到静态模式：生成 {} 并在 {} 里打上模式标记", SNAPSHOT_FILE, INDEX_FILE);
            } else {
                Files.deleteIfExists(root.resolve(SNAPSHOT_FILE));
                patchIndex(root, false);
                log.info("已切到动态模式：移除快照，并清掉 {} 里的模式标记", INDEX_FILE);
            }
        } catch (IOException e) {
            log.error("切换站点模式失败", e);
            throw new BizException(500, "文件操作失败：" + e.getMessage()
                    + "（服务进程需要有站点目录的写权限）");
        }
    }

    /**
     * 生成内容快照。
     *
     * <p>项目连同成果图一起进去（前台列表和详情都从这一份里取），
     * 所以详情页在静态模式下也不需要再请求接口。
     */
    public Map<String, Object> buildSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("format", "portfolio-snapshot");
        snapshot.put("version", 1);
        snapshot.put("generatedAt", LocalDateTime.now().toString());
        snapshot.put("site", siteContentService.publicSite());

        List<Map<String, Object>> projects = new ArrayList<>();
        for (Project p : projectService.listVisible()) {
            Map<String, Object> item = objectMapper.convertValue(p, new LinkedHashMap<String, Object>().getClass());
            // 列表接口不拼成果图，这里补上，静态模式下详情页才有图可看
            item.put("images", siteContentService.projectImages(p.getSlug()));
            item.remove("id");
            projects.add(item);
        }
        snapshot.put("projects", projects);

        // 用对外视图：存储路径不进快照（快照是公开的静态文件）
        snapshot.put("resumes", resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                        .eq(Resume::getActive, true)
                        .orderByAsc(Resume::getSortOrder)
                        .orderByAsc(Resume::getId))
                .stream()
                .map(resumeService::publicView)
                .toList());
        return snapshot;
    }

    private void writeSnapshot(Path target) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(buildSnapshot());
        // 先写临时文件再原子替换：nginx 可能正好在读，不能让它读到写了一半的 JSON
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, json);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 往 index.html 里插/删那一行模式标记。
     *
     * <p>插入位置必须在模块脚本之前——标记晚于 bundle 执行的话，前端读到的是 undefined，
     * 等于没切。写文件用「先写临时文件再原子替换」，避免 nginx 正好读到写了一半的 HTML。
     */
    private void patchIndex(Path root, boolean wantStatic) throws IOException {
        Path index = root.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            throw new BizException(500, "站点目录里找不到 index.html");
        }
        String html = Files.readString(index, StandardCharsets.UTF_8);
        boolean has = html.contains(FLAG_MARKER);

        if (wantStatic == has) {
            return;   // 已经是目标状态，不白写一遍（也会改 mtime）
        }

        String patched;
        if (wantStatic) {
            // 插在第一个 module 脚本之前
            int at = html.indexOf("<script type=\"module\"");
            if (at < 0) {
                throw new BizException(500, "index.html 里找不到 module 脚本，无法注入静态标记"
                        + "（前端构建产物结构变了？）");
            }
            patched = html.substring(0, at) + FLAG_LINE + System.lineSeparator() + "    "
                    + html.substring(at);
        } else {
            // 删掉整行（连同前面的空白），恢复成构建产物原样
            patched = html.lines()
                    .filter(line -> !line.contains(FLAG_MARKER))
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        }

        Path tmp = index.resolveSibling(INDEX_FILE + ".tmp");
        Files.writeString(tmp, patched, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** index.html 里现在有没有那行标记 */
    private boolean indexHasFlag(Path root) {
        try {
            return Files.readString(root.resolve(INDEX_FILE), StandardCharsets.UTF_8)
                    .contains(FLAG_MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    private void saveSetting(String mode) {
        SiteSetting setting = settingMapper.selectOne(new LambdaQueryWrapper<SiteSetting>()
                .eq(SiteSetting::getSettingKey, MODE_KEY)
                .last("limit 1"));
        if (setting == null) {
            setting = new SiteSetting();
            setting.setSettingKey(MODE_KEY);
            setting.setLabel(MODE_LABEL);
            setting.setHint(MODE_HINT);
            setting.setSortOrder(90);
            setting.setSettingValue(mode);
            settingMapper.insert(setting);
        } else {
            setting.setSettingValue(mode);
            settingMapper.updateById(setting);
        }
    }

    /** 站点目录。没配置或不存在都返回 null，调用方按「本机不适用」处理 */
    private Path webRoot() {
        String dir = props.getWebRoot();
        if (!StringUtils.hasText(dir)) {
            return null;
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 快照里的生成时间，用来在后台显示「快照是什么时候生成的」 */
    private String readGeneratedAt(Path snapshot) {
        try {
            Map<?, ?> map = objectMapper.readValue(Files.readAllBytes(snapshot), Map.class);
            Object at = map.get("generatedAt");
            return at == null ? null : at.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
