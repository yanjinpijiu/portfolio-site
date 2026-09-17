package top.qianlink.portfolio.common;

import java.util.List;

/**
 * CSV 拼装。只做一件事：按 RFC 4180 转义字段。
 *
 * <p>规则就两条：字段里出现逗号、引号、回车或换行就用双引号包起来；
 * 包起来的时候字段内部的引号要写成两个。
 *
 * <p><b>还要写 BOM。</b> Excel 打开不带 BOM 的 UTF-8 CSV 会把中文显示成乱码，
 * 用户第一反应是「导出的文件坏了」。BOM 由调用方写在最前面（见 {@link #BOM}）。
 */
public final class Csv {

    /** UTF-8 BOM。名义上 CSV 不该有它，但 Excel 认这个 */
    public static final String BOM = "\uFEFF";

    /** 换行统一用 CRLF：Excel 对 LF 的兼容性时好时坏 */
    private static final String CRLF = "\r\n";

    private Csv() {
    }

    /** 一个字段。null 当空串 */
    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** 一行（自带 CRLF 结尾） */
    public static String row(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        return sb.append(CRLF).toString();
    }
}
