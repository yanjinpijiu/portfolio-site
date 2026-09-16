package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 访客访问明细。
 *
 * visitDate / visitHour / visitWeekday 是入库时就算好的冗余列，
 * 查询直接 GROUP BY 这几个字段，不在 SQL 里调时间函数
 * （H2 的 MySQL 兼容模式函数不全，DATE_FORMAT/DATE() 之类很容易翻车）。
 */
@Data
@TableName("visit_log")
public class VisitLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 浏览器 cookie 里的匿名 ID，用来算 UV */
    private String visitorId;

    /** 客户端 IP。取的是 nginx 写进来的 X-Real-IP，不是可伪造的 X-Forwarded-For */
    private String ip;

    private String browser;

    private String os;

    private String device;

    private String country;

    private String province;

    private String city;

    /** 访问的路径，如 /projects/jianzhi */
    private String path;

    /** 前端路由名，如 project-detail */
    private String routeName;

    /** home / projects / project / resume / other */
    private String pageType;

    /** 落在项目详情页时记录的项目 slug */
    private String projectSlug;

    private String referer;

    private LocalDate visitDate;

    /** 0-23 */
    private Integer visitHour;

    /** 1=周一 … 7=周日，跟 MySQL 的 DAYOFWEEK 习惯对齐 */
    private Integer visitWeekday;

    private LocalDateTime createdAt;
}
