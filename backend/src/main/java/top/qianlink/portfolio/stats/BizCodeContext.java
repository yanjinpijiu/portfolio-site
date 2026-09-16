package top.qianlink.portfolio.stats;

/**
 * 把「这次请求的业务码」从异常处理器传给出接口访问日志的拦截器。
 *
 * <p>为什么需要它：项目的约定是<b>出错也返回 HTTP 200，业务码放在响应体里</b>。
 * 而拦截器的 afterCompletion 拿不到已经写出去的响应体（除非用
 * ContentCachingResponseWrapper 把整个响应body缓一份，对图片下载这种接口就是白烧内存）。
 * 所以改成让 {@code GlobalExceptionHandler} 顺手把业务码塞进 ThreadLocal，
 * 拦截器在同一个线程里取走——取完立刻清掉，不会串到下一个请求。
 */
public final class BizCodeContext {

    private static final ThreadLocal<Integer> CODE = new ThreadLocal<>();

    private BizCodeContext() {
    }

    public static void set(int code) {
        CODE.set(code);
    }

    /** 取出并清除。没有设置过时返回 null。 */
    public static Integer getAndClear() {
        Integer code = CODE.get();
        CODE.remove();
        return code;
    }

    /**
     * 看一眼不取走。给「顺带做点事」的拦截器用：取走的话，真正负责记接口日志的那个
     * 拦截器就只能记成 0，失败次数全丢了。清除的责任留给 {@link #getAndClear()} 的调用方。
     */
    public static Integer peek() {
        return CODE.get();
    }

    /** 兜底清除，防止请求异常中断时残留 */
    public static void clear() {
        CODE.remove();
    }
}
