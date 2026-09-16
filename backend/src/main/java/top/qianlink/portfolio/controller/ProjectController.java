package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.domain.Project;
import top.qianlink.portfolio.service.ProjectService;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResponse<List<Project>> list() {
        return ApiResponse.ok(projectService.listVisible());
    }

    @GetMapping("/{slug}")
    public ApiResponse<Project> detail(@PathVariable String slug) {
        return ApiResponse.ok(projectService.getBySlug(slug));
    }
}
