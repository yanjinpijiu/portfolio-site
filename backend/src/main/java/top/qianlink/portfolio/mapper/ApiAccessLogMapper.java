package top.qianlink.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.qianlink.portfolio.domain.ApiAccessLog;
import top.qianlink.portfolio.domain.LogQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 接口访问明细 + 看板聚合。
 *
 * <p>绝大多数查询都带 {@code internal = 0}：后台自己调接口也会被记下来，
 * 不排掉的话「接口调用量排行」全是自己在后台点出来的请求。
 * 想看自己那部分就去掉这个条件——{@code internalOnly} 那组查询就是给「含后台请求」用的。
 */
public interface ApiAccessLogMapper extends BaseMapper<ApiAccessLog> {

    /** 调用量排行 + 失败数 + 耗时。Top N 在服务层截断，SQL 里不写 LIMIT */
    @Select("""
            SELECT path AS path,
                   method AS method,
                   COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail,
                   AVG(duration_ms) AS avg_ms,
                   MAX(duration_ms) AS max_ms
            FROM api_access_log
            WHERE created_at >= #{from} AND internal = 0
            GROUP BY path, method
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> topPaths(@Param("from") LocalDateTime from);

    /** 慢接口排行，按平均耗时降序。样本太少的不算（服务层再过滤一次） */
    @Select("""
            SELECT path AS path,
                   method AS method,
                   COUNT(*) AS cnt,
                   AVG(duration_ms) AS avg_ms,
                   MAX(duration_ms) AS max_ms
            FROM api_access_log
            WHERE created_at >= #{from} AND internal = 0 AND duration_ms >= 0
            GROUP BY path, method
            ORDER BY avg_ms DESC
            """)
    List<Map<String, Object>> slowPaths(@Param("from") LocalDateTime from);

    /** 整体趋势：按天的调用量和失败量 */
    @Select("""
            SELECT visit_date AS d,
                   COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail,
                   SUM(CASE WHEN internal = 1 THEN 1 ELSE 0 END) AS inner_cnt
            FROM api_access_log
            WHERE created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> dailyTrend(@Param("from") LocalDateTime from);

    /** 某个接口按天的趋势 */
    @Select("""
            SELECT visit_date AS d,
                   COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail
            FROM api_access_log
            WHERE path = #{path} AND created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> pathTrend(@Param("path") String path, @Param("from") LocalDateTime from);

    /**
     * 登录接口专项：按天看尝试数和失败数。
     * 失败数持续不为 0 就是有人在试密钥。
     */
    @Select("""
            SELECT visit_date AS d,
                   COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail
            FROM api_access_log
            WHERE path = '/api/admin/login' AND created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> loginTrend(@Param("from") LocalDateTime from);

    /** 登录失败按来源 IP 排行——爆破的话会集中在一两个 IP 上 */
    @Select("""
            SELECT ip AS ip,
                   COUNT(*) AS cnt,
                   MAX(created_at) AS last_at
            FROM api_access_log
            WHERE path = '/api/admin/login' AND biz_code <> 0 AND created_at >= #{from}
            GROUP BY ip
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> loginFailByIp(@Param("from") LocalDateTime from);

    /** 登录尝试（含成功）按小时分布，看是不是集中在半夜被扫 */
    @Select("""
            SELECT visit_hour AS h,
                   COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail
            FROM api_access_log
            WHERE path = '/api/admin/login' AND created_at >= #{from}
            GROUP BY visit_hour
            ORDER BY visit_hour
            """)
    List<Map<String, Object>> loginByHour(@Param("from") LocalDateTime from);

    /**
     * 下载接口专项：按业务码分组。
     * 0 = 下载成功、428 = 被验证码拦下、429 = 被限流、404 = 简历不存在。
     */
    @Select("""
            SELECT biz_code AS code, COUNT(*) AS cnt
            FROM api_access_log
            WHERE path LIKE '/api/resumes/%/download' AND created_at >= #{from}
            GROUP BY biz_code
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> downloadByCode(@Param("from") LocalDateTime from);

    /** 接口调用总量 */
    @Select("""
            SELECT COUNT(*) AS cnt,
                   SUM(CASE WHEN biz_code <> 0 THEN 1 ELSE 0 END) AS fail,
                   SUM(CASE WHEN internal = 1 THEN 1 ELSE 0 END) AS inner_cnt
            FROM api_access_log
            WHERE created_at >= #{from}
            """)
    Map<String, Object> summary(@Param("from") LocalDateTime from);

    /* ================= 日志页 ================= */

    /**
     * 日志页的过滤条件，和 {@link VisitLogMapper#LOG_WHERE} 一个套路：
     * 分页和计数共用一份，免得两边的条件飘了。
     *
     * <p>默认排掉后台自己的请求（{@code internal = 0}），要看就传 includeInternal。
     * 这张表<b>没有地区字段</b>（只存了 IP），所以地区筛选在这里是空操作——
     * 归属地在服务层用内存查表补上，只用于显示。
     */
    String LOG_WHERE = """
            WHERE visit_date BETWEEN #{from} AND #{to}
            <if test="q != null"> AND (ip LIKE CONCAT('%', #{q}, '%') OR visitor_id LIKE CONCAT('%', #{q}, '%') OR path LIKE CONCAT('%', #{q}, '%'))</if>
            <if test="path != null"> AND path = #{path}</if>
            <if test="onlyFail"> AND biz_code &lt;&gt; 0</if>
            <if test="!includeInternal"> AND internal = 0</if>
            """;

    @Select("<script>SELECT * FROM api_access_log " + LOG_WHERE
            + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ApiAccessLog> pageLogs(LogQuery query);

    @Select("<script>SELECT COUNT(*) FROM api_access_log " + LOG_WHERE + "</script>")
    long countLogs(LogQuery query);

    /** 接口路径下拉的候选值 */
    @Select("""
            SELECT DISTINCT path FROM api_access_log
            WHERE visit_date BETWEEN #{from} AND #{to} AND path IS NOT NULL
            ORDER BY path
            LIMIT 200
            """)
    List<String> distinctPaths(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
