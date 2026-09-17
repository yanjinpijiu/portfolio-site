package top.qianlink.portfolio.domain;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 日志检索条件。访问日志和接口日志共用一套参数，{@code type} 决定查哪张表。
 *
 * <p>作为 GET 的查询参数对象直接绑定，所以字段名就是前端传的参数名。
 *
 * <p><b>为什么不用 MyBatis-Plus 的分页插件</b>：这个项目没配
 * {@code PaginationInnerInterceptor}，为了一张日志表把它引进来不划算。
 * 三个 mapper 各自手写 {@code LIMIT/OFFSET} 加一条同样 WHERE 的 {@code COUNT(*)}，
 * 量级（日均几百行）下这点重复比多一个全局插件更好维护。
 */
@Data
public class LogQuery {

    /** 查哪张表：visit（访问日志）/ api（接口日志）/ download（下载日志） */
    private String type = "visit";

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private int page = 1;

    private int size = 50;

    /** 关键词，同时匹配 IP / 访客 ID / 路径 */
    private String q;

    private String path;

    /** 页面类型：home / projects / project / resume / other */
    private String pageType;

    /** 国家 / 省 / 市三级，前端联动着填 */
    private String country;

    private String province;

    private String city;

    private String device;

    private String browser;

    private String os;

    /** 只看某份简历，下载日志专有 */
    private Long resumeId;

    /** 只看失败（业务码非 0），接口日志专有 */
    private boolean onlyFail;

    /** 是否连后台自己的请求一起看，接口日志专有 */
    private boolean includeInternal;

    /**
     * LIMIT 的偏移量。
     *
     * <p>page 和 size 必须先被服务层夹到合法范围再读这个值，否则 size=99999999 能算出个负数。
     */
    public int getOffset() {
        return (page - 1) * size;
    }

    /**
     * 空串统一当成「没填」。
     *
     * <p>前端的空输入框会把参数照样发过来（{@code ?q=&path=}），
     * 不归一化的话 SQL 里就变成 {@code path = ''}，查出来永远是空的。
     */
    public void normalize() {
        // 类型只认这三个，别的（包括拼错的、空的）一律当访问日志，
        // 免得拼错一个单词就查出一张空表还不知道为什么
        type = switch (type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "api" -> "api";
            case "download" -> "download";
            default -> "visit";
        };
        q = blankToNull(q);
        path = blankToNull(path);
        pageType = blankToNull(pageType);
        country = blankToNull(country);
        province = blankToNull(province);
        city = blankToNull(city);
        device = blankToNull(device);
        browser = blankToNull(browser);
        os = blankToNull(os);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
