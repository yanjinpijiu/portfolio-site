package com.example.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.example.portfolio.domain.Project;

import java.util.List;

public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 全量项目（<b>包含软删除的</b>），按 id 升序。
     *
     * <p>手写 SQL 不受 {@code @TableLogic} 影响，所以能看见已删除的行。
     * 两个地方要用：①看板的项目关注度表——删掉的项目历史统计仍然要显示；
     * ②创建项目时的 slug 占用检查——已删除项目的 slug 还在唯一索引里占着，不能复用。
     */
    @Select("SELECT * FROM project ORDER BY id")
    List<Project> selectAllIncludingDeleted();

    /**
     * slug 是否已被占用（<b>含软删除的项目</b>）。slug 上有唯一索引，软删除的行还占着它，
     * 复用会直接撞索引约束——所以创建时的占用检查必须看全量。
     */
    @Select("SELECT COUNT(*) FROM project WHERE slug = #{slug}")
    long countBySlugIncludingDeleted(@Param("slug") String slug);
}
