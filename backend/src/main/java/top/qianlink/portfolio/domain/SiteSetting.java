package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 零散的站点设置：SEO、页脚、导航这些。 */
@Data
@TableName("site_setting")
public class SiteSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设置项标识，如 seo.description */
    private String settingKey;

    /** 设置值 */
    private String settingValue;

    /** 后台设置页显示的中文名 */
    private String label;

    /** 后台设置页显示的说明 */
    private String hint;

    private Integer sortOrder;
}
