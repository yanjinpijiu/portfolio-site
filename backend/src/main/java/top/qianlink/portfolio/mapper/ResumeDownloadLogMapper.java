package top.qianlink.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.qianlink.portfolio.domain.LogQuery;
import top.qianlink.portfolio.domain.ResumeDownloadLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 简历下载明细 + 看板聚合。
 *
 * <p>这张表同时是「免验证码额度」的计数器（见 ResumeDownloadGate），
 * 所以下面那些按天/按简历的统计都只统计<b>成功下载</b>的行——验证码输错不会留记录。
 */
public interface ResumeDownloadLogMapper extends BaseMapper<ResumeDownloadLog> {

    /** 每份简历的下载量排行。标题取快照的最大值，改了名也不会把同一份简历拆成两行 */
    @Select("""
            SELECT resume_id AS resume_id,
                   MAX(resume_title) AS title,
                   MAX(direction) AS direction,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT visitor_id) AS uv,
                   SUM(CASE WHEN free_pass = 1 THEN 1 ELSE 0 END) AS free_cnt
            FROM resume_download_log
            WHERE created_at >= #{from}
            GROUP BY resume_id
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> byResume(@Param("from") LocalDateTime from);

    /** 全部简历合计的按天趋势 */
    @Select("""
            SELECT visit_date AS d, COUNT(*) AS cnt, COUNT(DISTINCT visitor_id) AS uv
            FROM resume_download_log
            WHERE created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> dailyTrend(@Param("from") LocalDateTime from);

    /** 单份简历的按天趋势 */
    @Select("""
            SELECT visit_date AS d, COUNT(*) AS cnt, COUNT(DISTINCT visitor_id) AS uv
            FROM resume_download_log
            WHERE resume_id = #{resumeId} AND created_at >= #{from}
            GROUP BY visit_date
            ORDER BY visit_date
            """)
    List<Map<String, Object>> trendByResume(@Param("resumeId") Long resumeId,
                                            @Param("from") LocalDateTime from);

    /**
     * 数某个 IP 在某个时间点之后下过几次同一份简历。
     *
     * <p>下载接口拿它判断要不要出验证码：少于 3 次免验证。
     * 走 idx_download_ip_resume 索引，是这个表上唯一在请求链路上同步跑的查询，
     * 所以别在这个方法里加聚合。
     */
    @Select("""
            SELECT COUNT(*) FROM resume_download_log
            WHERE ip = #{ip} AND resume_id = #{resumeId} AND created_at >= #{since}
            """)
    long countRecentByIpAndResume(@Param("ip") String ip,
                                  @Param("resumeId") Long resumeId,
                                  @Param("since") LocalDateTime since);

    /** 窗口内的下载总量 */
    @Select("""
            SELECT COUNT(*) AS cnt, COUNT(DISTINCT visitor_id) AS uv
            FROM resume_download_log
            WHERE created_at >= #{from}
            """)
    Map<String, Object> summary(@Param("from") LocalDateTime from);

    /** 下载明细，最新的在前 */
    @Select("""
            SELECT * FROM resume_download_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<ResumeDownloadLog> recent(@Param("limit") int limit);

    /**
     * 按 IP 聚合的下载来源。
     *
     * <p>这是「是哪个 IP 下的、在哪儿、下了几次」的直接答案。三个数字各有用处：
     * 下载次数看这个人有多执着，独立访客数区分「一个人下了 5 次」和「五个人各下 1 次」
     * （公司、学校那种出口 IP 很常见），简历份数看出他是对着某个岗位来的还是广撒网。
     *
     * <p>归属地用 {@code MAX()} 取一行代表：IP 决定归属地，同一个 IP 的各行本来就一样，
     * 真出现不一致（IP 库更新过）时也要合并成一行，而不是让同一个 IP 出现两次。
     */
    @Select("""
            SELECT ip AS ip,
                   MAX(country) AS country,
                   MAX(province) AS province,
                   MAX(city) AS city,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT visitor_id) AS uv,
                   COUNT(DISTINCT resume_id) AS resumes,
                   MAX(created_at) AS last_at
            FROM resume_download_log
            WHERE created_at >= #{from} AND ip IS NOT NULL
            GROUP BY ip
            ORDER BY cnt DESC, last_at DESC
            """)
    List<Map<String, Object>> byIp(@Param("from") LocalDateTime from);

    /**
     * 下载来源按省市聚合。省市一起分组，重名城市才不会混成一行。
     *
     * <p>原来只有一条 {@code byProvince}，看板那张图也只能画到省。
     * 现在一次查出两级，前端按档位取用，不用发两次请求。
     */
    @Select("""
            SELECT province AS province,
                   city AS city,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT visitor_id) AS uv
            FROM resume_download_log
            WHERE created_at >= #{from} AND (province IS NOT NULL OR city IS NOT NULL)
            GROUP BY province, city
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> byCity(@Param("from") LocalDateTime from);

    /* ================= 日志页 ================= */

    /**
     * 日志页的过滤条件，和 {@link VisitLogMapper#LOG_WHERE} 一个套路：
     * 分页和计数共用一份，免得两边条件飘了。
     *
     * <p>关键词多匹一个简历标题：这一页最常问的是「这份简历都被谁下过」。
     */
    String LOG_WHERE = """
            WHERE visit_date BETWEEN #{from} AND #{to}
            <if test="q != null"> AND (ip LIKE CONCAT('%', #{q}, '%') OR visitor_id LIKE CONCAT('%', #{q}, '%') OR resume_title LIKE CONCAT('%', #{q}, '%'))</if>
            <if test="resumeId != null"> AND resume_id = #{resumeId}</if>
            <if test="country != null"> AND country = #{country}</if>
            <if test="province != null"> AND province = #{province}</if>
            <if test="city != null"> AND city = #{city}</if>
            """;

    @Select("<script>SELECT * FROM resume_download_log " + LOG_WHERE
            + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ResumeDownloadLog> pageLogs(LogQuery query);

    @Select("<script>SELECT COUNT(*) FROM resume_download_log " + LOG_WHERE + "</script>")
    long countLogs(LogQuery query);

    /** 地区三级联动的候选值 */
    @Select("""
            SELECT DISTINCT country, province, city FROM resume_download_log
            WHERE visit_date BETWEEN #{from} AND #{to}
              AND (country IS NOT NULL OR province IS NOT NULL OR city IS NOT NULL)
            ORDER BY country, province, city
            LIMIT 200
            """)
    List<Map<String, Object>> distinctRegions(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
