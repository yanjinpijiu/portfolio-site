package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 接口访问明细。登录接口的爆破痕迹、慢接口、各接口调用量都从这儿看。 */
@Data
@TableName("api_access_log")
public class ApiAccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口路径 */
    private String path;

    private String method;

    /** 业务码，0 = 成功；登录失败是 401，被限流是 429 */
    private Integer bizCode;

    private Integer httpStatus;

    private Integer durationMs;

    private String ip;

    private String visitorId;

    /**
     * true = 后台自己发的请求（带了 X-Admin-Token，或这台浏览器种过 pv_skip cookie）。
     * 看板默认只看 false 的，否则自己在后台点几下就把「接口调用量排行」刷满了。
     */
    private Boolean internal;

    private LocalDate visitDate;

    /** 0-23，登录失败的时间分布要用 */
    private Integer visitHour;

    private LocalDateTime createdAt;
}
