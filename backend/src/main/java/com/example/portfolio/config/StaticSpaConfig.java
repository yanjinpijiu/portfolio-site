package com.example.portfolio.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 让后端自己把前端产物发出去——**给「不用 nginx」的部署方式用**（Docker 单容器最常见）。
 *
 * <p>线上那套是 nginx 发静态文件、只把 `/api` 反代给后端；容器里只有一个进程，
 * 没人替它发 `index.html` 和那些带哈希的 JS/CSS，所以这里补上。
 * `app.static-dir` 留空时这个配置完全不生效（裸机 + nginx 的部署方式不受影响）。
 *
 * <p>两条规则：
 * <ul>
 *   <li>`/api/**`、`/files/**` 仍然走各自的 Controller（注解映射优先于资源处理器，
 *       这里再挡一次是为了防止有人把静态目录指到奇怪的地方）；</li>
 *   <li>路径**带扩展名**却没有对应文件 = 真 404（缺的图片/JS 不该回退成 HTML，
 *       否则浏览器会拿一坨 HTML 当脚本解析，报错信息完全看不懂）；
 *       不带扩展名 = SPA 路由，回退到 `index.html` 交给 vue-router。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class StaticSpaConfig implements WebMvcConfigurer {

    private final AppProperties props;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (!StringUtils.hasText(props.getStaticDir())) {
            return;
        }
        // 首页显式转发：`/` 在资源处理器内部会被归一化成 "."，交给下面的 resolver
        // 去猜很别扭（返回目录会炸成 500）。转发到 /index.html 之后走的就是普通静态文件
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(props.getStaticDir())) {
            return;
        }
        Path root = Path.of(props.getStaticDir()).toAbsolutePath().normalize();
        String location = root.toUri().toString();

        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCachePeriod(3600)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 目录式请求（"/"、"/xxx/"、"."）都给 index.html，别把目录当文件返回
                        if (resourcePath.isEmpty() || ".".equals(resourcePath) || resourcePath.endsWith("/")) {
                            return index(location);
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("files/")) {
                            return null;
                        }
                        // 带扩展名却没文件 = 真 404：缺的图片/JS 不该回退成 HTML，
                        // 否则浏览器会拿一坨 HTML 当脚本解析，报错信息完全看不懂
                        if (resourcePath.contains(".")) {
                            return null;
                        }
                        // 其余（/projects/xxx、/admin/... 这类 SPA 路由）回退给 vue-router
                        return index(location);
                    }

                    private Resource index(Resource location) throws IOException {
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }
}
