package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 证书。挂在某个技能分组下面，点技能卡时弹出来。 */
@Data
@TableName("certificate")
public class Certificate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 证书名称 */
    private String title;

    /** 发证机构 */
    private String org;

    /** 取得时间，如「2025.07 — 2025.08」 */
    private String certDate;

    /** 一句话说明，如「2025 暑期研学结业证书」 */
    private String summary;

    /** 证书图在存储里的 key */
    private String imageKey;

    /** 缩略图宽高比，形如 "4/3"，前端占位防跳动 */
    private String ratio;

    /** 归属技能分组 id */
    private Long groupId;

    private Integer sortOrder;

    /** 是否在前台显示 */
    private Boolean visible;
}
