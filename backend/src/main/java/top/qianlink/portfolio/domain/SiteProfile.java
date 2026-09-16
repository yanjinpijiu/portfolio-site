package top.qianlink.portfolio.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 个人资料。单例，库里永远只有 id=1 这一行。 */
@Data
@TableName("site_profile")
public class SiteProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 姓名 */
    private String name;

    /** 英文名 */
    private String nameEn;

    /** 一句话头衔，如「Java 后端开发 · Agent 开发 · AI 接入」 */
    private String title;

    /** 学校 */
    private String school;

    /** 届别 */
    private String graduation;

    /** 现居城市 */
    private String city;

    /** 邮箱 */
    private String email;

    /** 电话，留空则前台不显示这一项 */
    private String phone;

    /** 头像在存储里的 key，形如 2026/09/xxx.jpg */
    private String avatarKey;

    /** 自我介绍，一行一条 */
    private String intro;

    /** 求职状态那一行，如「可全职实习 · 期待北京 / 上海」 */
    private String availability;

    private LocalDateTime updatedAt;
}
