package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 页面区块文案。每个区块的大标题和副标题，原来散在各个 .vue 文件里。 */
@Data
@TableName("site_section")
public class SiteSection {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 区块标识，如 home.skills / projects.header */
    private String sectionKey;

    /** 小字英文眉标，如「Skills」 */
    private String eyebrow;

    /** 中文标题 */
    private String title;

    /** 标题下面的说明文字 */
    private String description;
}
