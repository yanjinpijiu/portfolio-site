# 参与与约定

这个仓库是一个**个人简历站的模板**：代码、配置模板、部署脚本、文档都在这里，站点内容（个人信息、项目资料、简历文件）不在。

## 一、绝对不要提交进来的东西

仓库是公开的，下面这些一律只放本地：

| 类别 | 说明 |
|---|---|
| 真实个人信息 | 姓名、手机号、邮箱、学校、证件号、证件照 |
| 证书 / 成绩单扫描件 | 含证件号、准考证号、成绩的图片 |
| 后台登录密钥 | 只走环境变量 `APP_ADMIN_KEY` 或本地 `application-local.yml`（已 gitignore） |
| 服务器公网 IP | 文档与脚本里一律写占位符；自己的域名可以出现 |
| 真实访客 IP | 文档与截图里只能出现 RFC 5737 文档保留段（`192.0.2.x` / `198.51.100.x` / `203.0.113.x`） |
| 备案号 | 文档与截图里打码 |
| 运行数据 | `backend/data/`、`backend/backups/`（H2 库、上传的文件、备份 zip） |

**为什么这条这么严**：这类站点的截图和日志里天然带着访客信息（IP、地区、路径），而这些不是你的数据，是别人的。所以换 README 配图、贴日志片段之前，先按上表过一遍再提交。

## 二、提交前自检

```bash
# 1. 有没有把本地配置 / 数据带进去
git ls-files | grep -E "application-local\.yml$|portfolio\.env$|^backend/data/|^backend/backups/"

# 2. 有没有手机号 / 邮箱混进跟踪文件
git ls-files -z | xargs -0 grep -nE "1[3-9][0-9]{9}"
git ls-files -z | xargs -0 grep -nE "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"

# 3. 有没有真实 IP（命中的应当只有文档保留段）
git ls-files -z | xargs -0 grep -nE "([0-9]{1,3}\.){3}[0-9]{1,3}"
```

第 2、3 条会命中本文件里的示例。判断标准是：**这句话是在讲规则，还是在讲某个人**——前者留，后者删。

## 三、代码约定

- 后端：Java 21 + Spring Boot，分层是 `controller / service / mapper / domain`，SQL 走 MyBatis-Plus（注解写动态条件，复杂聚合手写 SQL）。
- 前端：Vue 3 组合式 API，无 UI 组件库、无状态库；样式手写，配色与间距集中在 CSS 变量里。
- 注释写「为什么」，不写「做了什么」。踩过的坑值得写清楚，因为它会在下一次改动里再咬人一次。
- 数据库变更直接改 `backend/src/main/resources/db/schema.sql`，语句必须是幂等的（`IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`），因为它在每次启动时都会执行。

## 四、跑起来

```bash
# 后端
cd backend && cp application-local.yml.example application-local.yml && mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
```

首次启动会自动建表；站点内容是空的，进后台录入，或自己写一个 Seeder 灌初始数据。

## 五、许可

Apache License 2.0，见 [LICENSE](LICENSE)。使用、修改、分发都可以，保留版权声明与许可全文即可。
