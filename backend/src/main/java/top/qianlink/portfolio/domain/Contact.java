package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 联系方式卡片。原来是写死在 HomeView 里的四项（邮箱 / 电话 / GitHub / Gitee）。 */
@Data
@TableName("contact")
public class Contact {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签，如「邮箱」「GitHub」 */
    private String label;

    /**
     * 展示出来的值，如 github.com/yanjinpijiu。
     * 字段名不叫 value 是因为 value 是 H2 的保留字，列名只能退一步叫 value_text。
     */
    private String valueText;

    /** 点击跳转地址，如 mailto:xxx 或 https://... */
    private String href;

    /** 图标名，取值要在 AppIcon 组件里存在 */
    private String icon;

    private Integer sortOrder;

    /** 是否在前台显示 */
    private Boolean visible;
}
