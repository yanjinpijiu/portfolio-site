package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目成果图。原来写死在 galleries.js 里，现在按 slug 存在库里。 */
@Data
@TableName("project_image")
public class ProjectImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目 slug */
    private String projectSlug;

    /** 图片在存储里的 key */
    private String imageKey;

    /** 图注 */
    private String caption;

    private Integer sortOrder;

    /** 是否在前台显示 */
    private Boolean visible;
}
