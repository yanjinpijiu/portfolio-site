package com.example.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.example.portfolio.domain.LinkClickLog;
import com.example.portfolio.domain.LogQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 外链点击明细的查询。
 *
 * <p>和另外三张日志表一样：过滤条件共用一份 {@link #LOG_WHERE}（分页与计数各写一遍
 * 迟早对不上），时间维度全部用入库时算好的列，查询里不调时间函数。
 *
 * <p>点击表没有 internal 列——后台自己人（带 pv_skip）的点击在写入端就丢掉了，
 * 压根不进库，所以这里没有「包含后台自己」的开关。
 */
public interface LinkClickLogMapper extends BaseMapper<LinkClickLog> {

    /**
     * 日志页的过滤条件。
     *
     * <p>语义约定：{@code path} 筛选对点击表指的是「来源页」（source_path），
     * 复用 LogQuery 的同一个字段，前端不用为点击单独换一套查询参数。
     * 关键词同时匹配来源页和目标链接。
     */
    String LOG_WHERE = """
            WHERE visit_date BETWEEN #{from} AND #{to}
            <if test="q != null"> AND (target LIKE CONCAT('%', #{q}, '%') OR source_path LIKE CONCAT('%', #{q}, '%') OR ip LIKE CONCAT('%', #{q}, '%') OR visitor_id LIKE CONCAT('%', #{q}, '%'))</if>
            <if test="path != null"> AND source_path = #{path}</if>
            <if test="pageType != null"> AND page_type = #{pageType}</if>
            <if test="country != null"> AND country = #{country}</if>
            <if test="province != null"> AND province = #{province}</if>
            <if test="city != null"> AND city = #{city}</if>
            <if test="device != null"> AND device = #{device}</if>
            <if test="browser != null"> AND browser = #{browser}</if>
            <if test="os != null"> AND os = #{os}</if>
            """;

    /** 一页日志，按 id 倒序 */
    @Select("<script>SELECT * FROM link_click_log " + LOG_WHERE
            + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<LinkClickLog> pageLogs(LogQuery query);

    /** 符合条件的总行数 */
    @Select("<script>SELECT COUNT(*) FROM link_click_log " + LOG_WHERE + "</script>")
    long countLogs(LogQuery query);

    /** 来源页下拉的候选值 */
    @Select("""
            SELECT DISTINCT source_path FROM link_click_log
            WHERE visit_date BETWEEN #{from} AND #{to} AND source_path IS NOT NULL
            ORDER BY source_path
            LIMIT 200
            """)
    List<String> distinctSourcePaths(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 地区三级联动的候选值 */
    @Select("""
            SELECT DISTINCT country, province, city FROM link_click_log
            WHERE visit_date BETWEEN #{from} AND #{to}
              AND (country IS NOT NULL OR province IS NOT NULL OR city IS NOT NULL)
            ORDER BY country, province, city
            LIMIT 200
            """)
    List<Map<String, Object>> distinctRegions(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 浏览器 / 系统 / 设备下拉的候选值 */
    @Select("""
            SELECT DISTINCT browser, os, device FROM link_click_log
            WHERE visit_date BETWEEN #{from} AND #{to}
            ORDER BY browser, os, device
            LIMIT 200
            """)
    List<Map<String, Object>> distinctTerminals(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 关注度表用：窗口内按项目聚合的点击量。
     *
     * <p>按 {@code project_id} 分组（不是 slug）：项目软删除后 slug 那条数据仍是历史上
     * 写下来的快照，统计口径是「当时点的是哪个项目」。首页入口的点击 project_id 为 null，
     * 单独一组，服务层把它归到「首页入口」那一行。
     */
    @Select("""
            SELECT project_id AS project_id,
                   COUNT(*) AS clicks,
                   COUNT(DISTINCT visitor_id) AS click_uv
            FROM link_click_log
            WHERE created_at >= #{from}
            GROUP BY project_id
            """)
    List<Map<String, Object>> byProject(@Param("from") LocalDateTime from);
}
