package top.qianlink.portfolio.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.qianlink.portfolio.stats.BizCodeContext;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        // 顺便把业务码留给 ApiAccessInterceptor 记进接口访问日志：响应体里虽然也有业务码，
        // 但拦截器读不到已经写出去的响应体（要用 ContentCachingResponseWrapper 缓一份，
        // 对图片下载这种接口是白烧内存），所以走 ThreadLocal 这条路
        BizCodeContext.set(e.getCode());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        BizCodeContext.set(500);
        log.error("未处理异常", e);
        return ApiResponse.fail(500, "服务异常，请稍后重试");
    }
}
