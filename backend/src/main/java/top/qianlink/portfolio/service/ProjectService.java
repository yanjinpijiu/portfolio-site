package top.qianlink.portfolio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.qianlink.portfolio.common.BizException;
import top.qianlink.portfolio.domain.Project;
import top.qianlink.portfolio.mapper.ProjectMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final SiteContentService siteContentService;

    /** 对外可见的项目列表 */
    public List<Project> listVisible() {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getVisible, true)
                .orderByAsc(Project::getSortOrder)
                .orderByAsc(Project::getId));
    }

    public Project getBySlug(String slug) {
        Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getSlug, slug)
                .eq(Project::getVisible, true)
                .last("limit 1"));
        if (project == null) {
            throw new BizException(404, "项目不存在");
        }
        // 成果图另外从 project_image 表拼上来
        project.setImages(siteContentService.projectImages(slug));
        return project;
    }
}
