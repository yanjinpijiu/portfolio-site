package com.example.portfolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 后台登录密钥，部署时必须通过环境变量 APP_ADMIN_KEY 覆盖 */
    private String adminKey;

    /** 登录令牌有效期（小时） */
    private int tokenTtlHours = 12;

    /** 允许跨域的前端来源，生产同源部署可留空 */
    private List<String> corsOrigins = new ArrayList<>();

    /**
     * nginx 站点目录（静态文件根）。静态/动态模式切换要在这里写 snapshot.json 和
     * index-static.html，所以后端进程需要对它有写权限。
     * 本地开发留空即可——切不动文件时后台会明确说明「只在服务器上生效」。
     */
    private String webRoot;

    /**
     * 前端构建产物目录。填了后端就自己发静态文件（Docker 单容器部署用），
     * 留空则交给外面的 nginx（裸机部署用）。
     */
    private String staticDir;

    /**
     * 演示模式：开启后所有写操作由服务端拒绝（403），后台只能登录、只能看。
     *
     * <p>给「把站当 demo 挂出去给别人点」的场景用（在线演示站），生产自己用的时候保持 false。
     * 安全边界必须在服务端：前端把按钮禁掉只是体验，绕过前端直接发请求也改不了东西。
     */
    private boolean demoMode = false;

    /**
     * 登录页上显示的演示密钥提示。留空就不显示。
     *
     * <p>演示站的密钥本来就是公开的（写在 README 里），直接显示在登录页省得别人猜；
     * 自己正式用的站留空，别把真密钥写在页面上。
     */
    private String demoKeyHint;

    private Storage storage = new Storage();

    private Backup backup = new Backup();

    @Data
    public static class Backup {
        /** 关掉就完全不跑定时备份（本地开发一般不需要） */
        private boolean enabled = true;

        /** 备份产物目录，相对后端进程工作目录 */
        private String dir = "./backups";

        /** 保留份数，超出的按文件名时间戳倒序删 */
        private int keep = 7;
    }

    @Data
    public static class Storage {
        /** local 或 oss */
        private String type = "local";
        private Local local = new Local();
        private Oss oss = new Oss();

        @Data
        public static class Local {
            private String dir = "./data/files";
        }

        @Data
        public static class Oss {
            private String endpoint;
            private String bucket;
            private String accessKeyId;
            private String accessKeySecret;
            private String publicPrefix;
        }
    }
}
