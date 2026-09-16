package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 技能分组，如「后端开发」「AI 应用 / Agent」。 */
@Data
@TableName("skill_group")
public class SkillGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组名 */
    private String category;

    private Integer sortOrder;

    /** 是否在前台显示 */
    private Boolean visible;
}
