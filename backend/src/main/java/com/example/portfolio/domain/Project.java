package com.example.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** URL 标识 */
    private String slug;

    private String name;

    /** 实习项目 / 学院项目 / 后端项目 / 课程项目 */
    private String type;

    private String period;

    private String role;

    /** 一句话概述 */
    private String summary;

    /** 详细描述 */
    private String description;

    /** 技术标签，换行分隔 */
    private String tags;

    /** 个人产出要点，换行分隔 */
    private String highlights;

    private String repoUrl;

    private String repoLabel;

    private Integer sortOrder;

    private Boolean visible;

    /**
     * 软删除标记。删项目改成置 1，行留在库里——访问/点击统计按主键关联，
     * 行没了统计就成孤儿。@TableLogic 只标在这个实体上（不做全局配置，
     * 其他内容表没有这列），select 自动过滤、deleteById 自动变成置位。
     * 要看含已删除的全量（看板关注度表）走 ProjectMapper 里的手写 SQL。
     */
    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 成果图。不属于 project 表的列，查详情时另外从 project_image 表拼进来。
     * 用 exist=false 挂在这里，是为了让详情接口能保持「项目字段都在同一层」的扁平结构，
     * 免得前端要去 data.project.xxx 里再掏一层。
     */
    @TableField(exist = false)
    private List<Map<String, Object>> images;
}
