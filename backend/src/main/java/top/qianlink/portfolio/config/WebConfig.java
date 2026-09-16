package top.qianlink.portfolio.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.qianlink.portfolio.stats.ApiAccessInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final ApiAccessInterceptor apiAccessInterceptor;
    private final SnapshotRefreshInterceptor snapshotRefreshInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序很重要：埋点必须排在后台鉴权之前。鉴权失败时那个拦截器直接返回 401
        // 不再往下走，而 Spring 只回调「已经通过 preHandle 的」拦截器的 afterCompletion,
        // 排到后面的话越权尝试（最该被看到的那批请求）一条都记不下来
        registry.addInterceptor(apiAccessInterceptor)
                .addPathPatterns("/api/**")
                .order(0);

        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/login")
                .order(1);

        // 静态模式下，后台改完内容自动重建快照（具体见那个类里的说明）
        registry.addInterceptor(snapshotRefreshInterceptor)
                .addPathPatterns("/api/admin/**")
                .order(2);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.getCorsOrigins() == null || props.getCorsOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(props.getCorsOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
