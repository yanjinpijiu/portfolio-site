package top.qianlink.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.qianlink.portfolio.domain.VisitLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 访客访问明细 + 看板用的聚合查询。
 *
 * <p>聚合全部走「明细表 GROUP BY 预计算好的时间列」。<b>不在 SQL 里调时间函数</b>
 * （H2 的 MySQL 兼容模式函数不全，DATE_FORMAT / DATE() / DAYOFWEEK 这类是最容易翻车的地方），
 * 所以 date / hour / weekday 三列在埋点入库时就算好了。
 *
 * <p>返回 {@code List<Map>} 而不是实体：聚合结果的列是算出来的，没有对应的实体字段。
 * 列的键名直接用 SQL 里的别名，服务层会统一转成小写再取值（H2 返回的列标签大小写不保证）。
 */
public interface VisitLogMapper extends BaseMapper<VisitLog> {

    /** 按天：PV / UV / UIP。只返回有数据的天，空档由服务层补齐 */
    @Select("""
            SELECT visit_date AS d,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv,
                   COUNT(DISTINCT ip) AS uip
            FROM visit_log
            WHERE created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> dailyTrend(@Param("from") LocalDateTime from);

    /** 24 小时分布 */
    @Select("""
            SELECT visit_hour AS h,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from}
            GROUP BY visit_hour
            ORDER BY visit_hour
            """)
    List<Map<String, Object>> hourly(@Param("from") LocalDateTime from);

    /** 星期分布，1 = 周一 */
    @Select("""
            SELECT visit_weekday AS w,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from}
            GROUP BY visit_weekday
            ORDER BY visit_weekday
            """)
    List<Map<String, Object>> weekday(@Param("from") LocalDateTime from);

    /** 省份分布，前端拿这个画中国地图 */
    @Select("""
            SELECT province AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND province IS NOT NULL
            GROUP BY province
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byProvince(@Param("from") LocalDateTime from);

    /** 城市分布。省市一起分组，重名城市（如两个「城区」）才不会混成一行 */
    @Select("""
            SELECT province AS province,
                   city AS city,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND city IS NOT NULL
            GROUP BY province, city
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byCity(@Param("from") LocalDateTime from);

    /** 浏览器占比 */
    @Select("""
            SELECT browser AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND browser IS NOT NULL
            GROUP BY browser
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byBrowser(@Param("from") LocalDateTime from);

    /** 操作系统占比 */
    @Select("""
            SELECT os AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND os IS NOT NULL
            GROUP BY os
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byOs(@Param("from") LocalDateTime from);

    /** 设备占比 */
    @Select("""
            SELECT device AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND device IS NOT NULL
            GROUP BY device
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byDevice(@Param("from") LocalDateTime from);

    /** 页面分布，看哪一页最有人看 */
    @Select("""
            SELECT page_type AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND page_type IS NOT NULL
            GROUP BY page_type
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byPageType(@Param("from") LocalDateTime from);

    /** 单个项目的浏览情况 */
    @Select("""
            SELECT project_slug AS name,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM visit_log
            WHERE created_at >= #{from} AND project_slug IS NOT NULL
            GROUP BY project_slug
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> byProject(@Param("from") LocalDateTime from);

    /** 高频 IP，归属地在服务层补（内存查表，不占 SQL） */
    @Select("""
            SELECT ip AS ip,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv,
                   COUNT(DISTINCT path) AS pages
            FROM visit_log
            WHERE created_at >= #{from} AND ip IS NOT NULL
            GROUP BY ip
            ORDER BY pv DESC
            """)
    List<Map<String, Object>> topIps(@Param("from") LocalDateTime from);

    /**
     * 每天的新访客数。
     *
     * <p>内层扫全表取每个访客的首次访问日（<b>不能</b>按时间窗过滤，
     * 否则在窗口之前就来过的人会被当成新访客重复计入），外层再按天聚合。
     * 老访客 = 当天 UV - 当天新访客，在服务层算。
     */
    @Select("""
            SELECT visit_date AS d, COUNT(*) AS fresh
            FROM (
                SELECT visitor_id, MIN(visit_date) AS visit_date
                FROM visit_log
                WHERE visitor_id IS NOT NULL
                GROUP BY visitor_id
            ) t
            WHERE visit_date >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> newVisitorsByDay(@Param("from") java.time.LocalDate from);

    /** 窗口内的总量 */
    @Select("""
            SELECT COUNT(*) AS pv,
                   COUNT(DISTINCT visitor_id) AS uv,
                   COUNT(DISTINCT ip) AS uip
            FROM visit_log
            WHERE created_at >= #{from}
            """)
    Map<String, Object> summary(@Param("from") LocalDateTime from);

    /** 访问明细，最新的在前 */
    @Select("""
            SELECT * FROM visit_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<VisitLog> recent(@Param("limit") int limit);
}
