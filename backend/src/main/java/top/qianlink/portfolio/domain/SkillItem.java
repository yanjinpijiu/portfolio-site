package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 技能点，属于某个技能分组。 */
@Data
@TableName("skill_item")
public class SkillItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分组 id */
    private Long groupId;

    /** 技能描述，如「RAG 全流程：文档解析、分片、向量化」 */
    private String text;

    private Integer sortOrder;
}
