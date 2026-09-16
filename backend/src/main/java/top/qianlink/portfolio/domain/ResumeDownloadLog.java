package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 简历下载明细。
 *
 * 它同时承担两个职责：
 * 1. 统计「每版简历被下载了多少次」；
 * 2. 判断下载接口要不要出验证码——数一下同一 IP 在 24 小时内下过几次同一份简历，
 *    前 3 次免验证。所以这里只记「成功下载」，验证码输错不算数。
 */
@Data
@TableName("resume_download_log")
public class ResumeDownloadLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    /** 下载当时的简历名称快照：之后改名或删掉，历史统计还看得懂 */
    private String resumeTitle;

    /** 下载当时的方向快照 */
    private String direction;

    private String visitorId;

    private String ip;

    private String country;

    private String province;

    private String city;

    /** true = 走的免费额度；false = 过了验证码 */
    private Boolean freePass;

    /** 和 visit_log 一样，时间维度入库时算好，查询直接 GROUP BY */
    private LocalDate visitDate;

    /** 0-23 */
    private Integer visitHour;

    private LocalDateTime createdAt;
}
