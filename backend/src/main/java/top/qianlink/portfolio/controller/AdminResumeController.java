package top.qianlink.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.qianlink.portfolio.common.ApiResponse;
import top.qianlink.portfolio.domain.Resume;
import top.qianlink.portfolio.service.ResumeService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 简历管理。只有带上有效 X-Admin-Token 才能访问（见 AdminAuthInterceptor）。
 */
@RestController
@RequestMapping("/api/admin/resumes")
@RequiredArgsConstructor
public class AdminResumeController {

    private final ResumeService resumeService;

    @GetMapping
    public ApiResponse<List<Resume>> list() {
        return ApiResponse.ok(resumeService.listAll());
    }

    @PostMapping
    public ApiResponse<Resume> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "direction", required = false) String direction,
                                     @RequestParam(value = "active", required = false) Boolean active) {
        return ApiResponse.ok(resumeService.upload(file, title, direction, active));
    }

    @PutMapping("/{id}")
    public ApiResponse<Resume> update(@PathVariable Long id,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "direction", required = false) String direction,
                                     @RequestParam(value = "active", required = false) Boolean active,
                                     @RequestParam(value = "sortOrder", required = false) Integer sortOrder) {
        return ApiResponse.ok(resumeService.update(id, title, direction, active, sortOrder));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return ApiResponse.ok();
    }

    /** 后台预览，不计入下载次数 */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> preview(@PathVariable Long id) {
        Resume resume = resumeService.getById(id);
        InputStream in = resumeService.openFile(resume);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(resume.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(in));
    }
}
