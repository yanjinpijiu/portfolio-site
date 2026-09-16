package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 技能分组关联的项目。点技能卡时列出的「用在这些项目里」就是它。 */
@Data
@TableName("skill_group_project")
public class SkillGroupProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分组 id */
    private Long groupId;

    /** 项目 slug。项目被删掉后这条会查不到项目，前台静默忽略，不报错 */
    private String projectSlug;

    private Integer sortOrder;
}
