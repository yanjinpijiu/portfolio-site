package com.example.portfolio.stats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.example.portfolio.domain.Project;
import com.example.portfolio.mapper.ProjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * slug → 项目主键 的内存索引，给埋点写入用。
 *
 * <p>为什么需要：访问/点击统计按主键 id 关联项目（slug 只是前台地址，可以改），
 * 而埋点是从 URL 路径里拿到 slug 的，所以入库时要把 slug 翻译成 id。
 * 项目只有十几个且很少变，全量放内存里查，比每次埋点查库便宜。
 *
 * <p>两个时机刷新：①启动完成之后（{@link ApplicationReadyEvent}，保证种子数据已灌完，
 * 比 @PostConstruct 稳妥）；②后台增删改项目之后由控制器显式调用。
 *
 * <p>索引里<b>包含软删除的项目</b>：历史埋点里的旧 slug 仍然要能解析成 id，
 * 否则删掉的项目在统计里就成孤儿了。
 *
 * <p>刷新失败不影响任何业务：解析不到 id 时埋点只存 slug、id 留空，统计少一行归属而已。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectSlugIndex {

    private final ProjectMapper projectMapper;

    /** null = 还没加载过；加载失败或表为空时是空 map，不再反复重试 */
    private volatile Map<String, Long> bySlug = null;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        try {
            Map<String, Long> m = new HashMap<>();
            for (Project p : projectMapper.selectAllIncludingDeleted()) {
                if (p.getSlug() != null && p.getId() != null) {
                    m.put(p.getSlug(), p.getId());
                }
            }
            bySlug = Map.copyOf(m);
        } catch (Exception e) {
            bySlug = Map.of();
            log.warn("项目 slug 索引刷新失败，埋点将只记 slug 不记 id：{}", e.toString());
        }
    }

    /** slug 对应的项目主键；解析不到返回 null（埋点照记，只是不带项目归属） */
    public Long resolve(String slug) {
        if (slug == null) {
            return null;
        }
        // 启动事件之前来的埋点（比如 ready 还没到就有请求）兜一下懒加载
        Map<String, Long> m = bySlug;
        if (m == null) {
            refresh();
            m = bySlug;
        }
        return m == null ? null : m.get(slug);
    }
}
