package top.qianlink.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.qianlink.portfolio.domain.ResumeDownloadLog;

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

    /** 下载来源省份 */
    @Select("""
            SELECT province AS name, COUNT(*) AS cnt
            FROM resume_download_log
            WHERE created_at >= #{from} AND province IS NOT NULL
            GROUP BY province
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> byProvince(@Param("from") LocalDateTime from);

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
}
