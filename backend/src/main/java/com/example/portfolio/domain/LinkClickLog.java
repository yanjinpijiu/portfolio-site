package com.example.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 外链点击明细（项目仓库链接 / 首页 GitHub 入口）。
 *
 * <p>单独一张表而不是复用 {@link VisitLog}：点击不是「访问」，塞进 visit_log
 * 会让 PV/UV、地区分布那批现有查询全部要补「排除点击」的过滤，漏一个就出脏数据。
 *
 * <p>项目维度按 {@code projectId} 关联（不是 slug）：slug 只是当时地址的快照，
 * 项目改地址或软删除之后，统计靠 id 仍然对得上。
 *
 * <p>时间维度同样在入库时算好，查询只做 GROUP BY（H2 的 MySQL 兼容模式函数不全）。
 */
@Data
@TableName("link_click_log")
public class LinkClickLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 浏览器 cookie 里的匿名 ID */
    private String visitorId;

    /** 客户端 IP。取的是 nginx 写进来的 X-Real-IP，不是可伪造的 X-Forwarded-For */
    private String ip;

    private String browser;

    private String os;

    private String device;

    private String country;

    private String province;

    private String city;

    /** 点击发生在哪个页面，如 /projects/portfolio-site */
    private String sourcePath;

    /** home / projects / project / resume / other */
    private String pageType;

    /** 项目主键；首页入口的点击没有归属项目，为 null */
    private Long projectId;

    /** 项目 slug 快照，只用来在日志里直接看，统计不依赖它 */
    private String projectSlug;

    /** 被点击的外链，只收 http/https */
    private String target;

    private LocalDate visitDate;

    /** 0-23 */
    private Integer visitHour;

    /** 1=周一 … 7=周日 */
    private Integer visitWeekday;

    private LocalDateTime createdAt;
}
