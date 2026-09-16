package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume")
public class Resume {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 简历名称，如「AI Agent / 后端方向」 */
    private String title;

    /** 投递方向，用于分组展示 */
    private String direction;

    /** 展示给用户的文件名 */
    private String fileName;

    /** 对象存储中的 key */
    private String objectKey;

    private Long fileSize;

    private String contentType;

    /** 是否对外可见 */
    private Boolean active;

    private Integer downloadCount;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
