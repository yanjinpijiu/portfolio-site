# SPEC：外链点击埋点（项目仓库链接 / 首页 GitHub 入口）

> 这份文档回答三件事：现在能记到什么、要补什么、怎么算做完了。
> 只写设计和验收标准，不贴实现代码。所有「现状」都经过对代码的核对。
> 与 `SPEC-STATS.md` 的关系：那份管的是访问日志的查询与展示，这份是新增一类「页内行为」数据，互不影响。

## 1. 背景

### 1.1 现在的埋点能力：页面级

前端 `track.js` 只在两个时机上报：路由切换、页面从后台切回前台。上报内容只有 `path` 和路由名，其余全部由服务端采集推导。落库到 `visit_log` 的字段：

| 维度 | 字段 |
|---|---|
| 谁 | `visitor_id`（匿名 cookie）、`ip`、`country/province/city` |
| 什么设备 | `browser`、`os`、`device`（桌面/手机） |
| 看了哪 | `path`、`route_name`、`page_type`（home/projects/project/resume/other）、`project_slug` |
| 从哪来 | `referer`（上一跳 URL） |
| 什么时候 | `visit_date`、`visit_hour`、`visit_weekday`、`created_at` |

**能回答**：「谁、用什么设备、从哪来、在几点、打开了哪个项目页」。
**不能回答**：页面里的任何行为——滚动、停留时长、点了什么。简历下载是唯一的例外，它有独立表（服务端在下载接口里记录），但那是服务端动作，不是页面行为。

### 1.2 问题：外链点击 0 记录

站内对外链接有两处：

| 位置 | 元素 | 现状 |
|---|---|---|
| 项目详情页 `ProjectDetailView.vue` | `<a :href="project.repoUrl" target="_blank">` 显示完整 URL | 点击**不产生路由变化**，埋点不触发 |
| 首页 `HomeView.vue` hero 区 | GitHub 按钮（`contact` 里 label=GitHub 的那条） | 同上 |

用户跳到 GitHub/Gitee 之后就是外站，我们没有任何采集手段；`referer` 字段记的是「我们这个页面是从哪来的」，不是「用户点了什么出去」。

**结论：现在想知道「谁点了项目仓库链接」，一条都拿不到。**

### 1.3 数据量

`visit_log` 34 行（2026-09-15 起，5 天）。点击数据只会更少（一次访问里点击是少数动作）。所有设计继续选简单做法，不引入预聚合、队列、缓存。

## 2. 需求

- **R1**：记录外链点击——谁、什么时候、在哪个页面、点了哪个链接，并把地区/终端/访客维度一并记上（与访问统计口径一致）。
- **R2**：点击数据能在后台查看——日志页按日期/关键字筛选、可导出 CSV。
- **R3**：看板加「项目关注度表」：每个项目一行 = 项目页 UV / 仓库点击 / 点击率。
- **R4**（已确认）：覆盖**项目详情页仓库链接 + 首页 hero 区 GitHub 按钮**。
- **R5**（已确认）：链接呈现**保持原样**（裸链接），只加埋点，不动 UI。
- **R6**（已确认，新增）：项目统计**按数据库主键 id 关联**（不用 slug 当关联键）；**项目删除改为软删除**——删掉的项目其历史访问/点击数据仍然保留、仍能在关注度表里看到并标注。

## 3. 设计

### 3.1 数据模型：新表 `link_click_log`，不复用 `visit_log`

**这是本 spec 唯一有分量的选型，理由是「不能污染现有口径」。** `visit_log` 的现有查询有一整套：PV/UV 折线、24 小时、星期、地区分布、省份 TOP、高频 IP、汇总、日志页、导出、未定位计数。如果点击也往 `visit_log` 写（加两列区分），上面**每一个查询**都得补一个 `event IS NULL` 过滤，漏一个就出脏数据（PV 虚高、地区分布多出点击），而且将来新增查询时同样容易漏。独立表则现有查询一行不用改，语义也干净：**点击不是访问**。

表结构（沿用 `visit_log` 的列命名和类型）：

```sql
CREATE TABLE IF NOT EXISTS link_click_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    visitor_id    VARCHAR(64),
    ip            VARCHAR(64),
    browser       VARCHAR(32),
    os            VARCHAR(32),
    device        VARCHAR(32),
    country       VARCHAR(64),
    province      VARCHAR(64),
    city          VARCHAR(64),
    -- 点击发生在哪个页面（/ 或 /projects/xxx），服务端从 path 推导
    source_path   VARCHAR(256),
    page_type     VARCHAR(32),
    -- 关联用主键 id（R6）；slug 只作现场快照，统计不依赖它
    project_id    BIGINT,
    project_slug  VARCHAR(64),
    -- 被点击的外链（http/https，截断 512）
    target        VARCHAR(512),
    visit_date    DATE,
    visit_hour    INT,
    visit_weekday INT,
    created_at    TIMESTAMP
);
-- 索引：created_at、visit_date、project_id（看板按项目聚合）
```

`visit_log` 同步加一列 `project_id BIGINT`（老库 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`）：

- 项目页 UV 若按 `project_slug` 聚合，项目一改地址历史就断；加 id 之后「项目页 UV」和「仓库点击」用**同一个键**聚合，两边对得上。
- 写入时由下一节的 slug→id 索引补全；历史数据用一条幂等的 `UPDATE ... WHERE project_id IS NULL` 回填（每次启动跑一遍，无副作用）。
- 现有查询（地区、终端、PV/UV 折线等）一行不改，新列只是多挂着。

### 3.6 项目维度：按主键 id 关联 + 软删除（R6）

**slug→id 索引**：新增一个内存索引组件，启动时（种子灌完之后）从 `project` 表全量加载 `slug → id`（**含已删除项目**——历史埋点里的旧 slug 仍要能解析），后台增删改项目时刷新。埋点写入时用它把 slug 解析成 id，解析不到就只存 slug、id 留空（不影响入库）。

**软删除**：`project` 表加 `deleted TINYINT DEFAULT 0`，实体上加 MyBatis-Plus 的 `@TableLogic`（**只标在这一个实体上**，不做全局配置——其他表没有这列）：

- 后台删除项目 = `UPDATE project SET deleted=1`，**不再删成果图文件和记录**（软删除要保留现场）。
- 所有现有查询（前台列表/详情、后台列表、快照生成）经 `@TableLogic` 自动过滤已删除项目，行为与删除前一致——删了就看不到。
- `slug` 唯一索引仍全局占用（已删除项目的 slug 不复用），创建时的占用检查要**包含已删除项目**（手写 SQL），否则 DB 唯一索引会直接报错。
- 看板关注度表是唯一**要看到已删除项目**的地方：项目列表用不受 `@TableLogic` 影响的手写 SQL 取全量，已删除的行在表里标注「已删除」。

### 3.7 关注度表的两个口径

- **项目页 UV**：`visit_log` 按 `project_id` 去重 `visitor_id`；**仓库点击**：`link_click_log` 按 `project_id` 去重 `visitor_id`；**点击率 = 点击人数 ÷ 项目页 UV**（UV 为 0 显示 —）。
- 首页 GitHub 按钮的点击落库时 `project_id` 为空：关注度表末尾单列一行「首页入口」，不混进项目行。
- 表吃看板顶部的 7/30/90 天时间范围横条，与现有聚合图一致。

### 3.2 埋点通道：复用 `POST /api/track`

`TrackRequest` 从 `{path, route}` 扩展为 `{path, route, target}`：

- `target` 为空 → 照旧走 `recordVisit`（页面访问）；
- `target` 非空 → 走新的 `recordLinkClick`（点击），**不产生 `visit_log` 行**。

服务端校验（与现有原则一致：不信客户端）：

1. `target` 必须以 `http://` 或 `https://` 开头，否则整条丢弃（挡掉 `javascript:`、`mailto:`、站内相对路径）；
2. 截断 512，去掉空白字符；
3. 页面维度（`page_type`/`project_slug`）仍从 `path` 用现有 `derivePage()` 推导，客户端说了不算；
4. IP / UA / 归属地 / 时间维度照旧服务端自己取；
5. **带 `pv_skip`（后台自己人的浏览器）不记**，与 `recordVisit` 一致；
6. 去重：同一 `visitor_id` + 同一 `target` 5 秒内只记一条（双击、`click` 与 `auxclick` 双触发都用它兜底）。

关于伪造：`/api/track` 本来就是公开接口，现有的访问统计同样可被脚本注入（这是已接受的信任级别）；伪造点击只是把自己刷进自己的看板，没有安全含义。所以**不做**「target 必须存在于站点配置」的严格校验——那需要缓存内容层链接集合，复杂度不值。

### 3.3 前端挂点

- `track.js` 新增 `trackClick(target)`：用现有 `sendBeacon` 通道发 `{path, route:'', target}`（页面路径取 `location.pathname`，服务端仍从 path 推导页面维度）；接口不可用时静默失败，不重试。
- 项目详情页仓库链接：加 `@click` 调 `trackClick(project.repoUrl)`，链接行为不变（仍是新标签打开）。
- 首页 hero 区 GitHub 按钮：同样一行。
- 备注：站内 `mailto:` / `tel:` 链接不记（不是 http/https，也拿不到点击后的行为）。

### 3.4 日志页

「访问日志 / 接口日志 / 下载日志」之后加第 4 个类型「**外链点击**」，复用现有 `LogQuery` + `StatsQueryService.logs()` 的类型分支：

- 列表列：时间 / 来源页（`source_path`）/ 目标链接（`target`，显示时截断，链接新标签打开、`noopener`）/ 地区 / 终端 / 访客 ID；
- 筛选：日期范围、关键字（匹配 `target` 或 `source_path`）、地区、终端——复用现有控件；
- 导出 CSV：复用现有 `Csv` + 导出通道，表头独立。

### 3.5 看板

**项目关注度表**（推荐形态，待确认 2）：

| 列 | 来源 |
|---|---|
| 项目 | 内容层项目列表（含 slug 和名称） |
| 项目页 UV | `visit_log` 按 `project_slug` 去重 `visitor_id`（数据已有，**这个维度现在看板上没有**） |
| 仓库点击 | `link_click_log` 按 `project_slug` 去重 `visitor_id` |
| 点击率 | 点击人数 ÷ 项目页 UV（人数比人数，比次数公平） |

- 只统计有 slug 的点击；首页入口的点击（slug 为空）不进表，若确认要首页范围则单独一行「首页入口」或只在日志页看（待确认 1 + 2 共同决定）。
- 可选：点击按天折线（待确认 2）。

## 4. 明确不做的事

1. **不做「查看链接 → 中转页 → 跳转」**。用户上一条提的方案目的是「方便记录」，但记录不需要改 UI：点击时异步发一条埋点、立刻放行跳转，用户零感知。做成中转页反而是倒退——多一次跳转、多一个可能打不开的页面（你的服务器成外链的单点）、外链藏在你自己域名下降低可信度。
2. **不做服务端 302 中转**（`/go/xxx` 那种短链式跳转）。前端埋点已覆盖同等信息，302 方案额外引入「服务器/域名挂掉外链就点不动」的风险。
3. **不记「点击后是否真的到达了 GitHub」**。技术上做不到（外站行为），口径就是「点击意图」。
4. **不做滚动深度、停留时长、页内其他按钮**（那是另一个需求，别混进来）。
5. **不改现有 PV/UV/地区等任何统计口径**，点击不进 `visit_log`。
6. **不做点击的独立管理/清理策略**，与现有统计一致：量小，先攒着。

## 5. 影响的文件（预估）

后端：

| 文件 | 改动 |
|---|---|
| `resources/db/schema.sql` | `link_click_log` 建表 + 索引；`visit_log` 加 `project_id`；`project` 加 `deleted`；幂等回填 `visit_log.project_id` |
| `domain/LinkClickLog.java` | 新增实体 |
| `domain/Project.java` | 加 `deleted` 字段 + `@TableLogic`（只标本实体，不做全局配置） |
| `mapper/LinkClickLogMapper.java` | 新增：`insert` / 分页查询与计数 / 按项目聚合 / 导出取行 / 来源页选项 |
| `mapper/ProjectMapper.java` | 加「包含已删除」的全量查询（手写 SQL，供关注度表与 slug 占用检查用） |
| `stats/ProjectSlugIndex.java` | 新增：slug→id 内存索引（含已删除项目），启动加载、增删改刷新 |
| `stats/StatsCollector.java` | 新增 `recordLinkClick()`；`recordVisit()` 补 `project_id` |
| `controller/TrackController.java` | `TrackRequest` 加 `target`，分流两种记录 |
| `controller/AdminProjectController.java` | 删除改软删除（不再删图片）；创建时的 slug 占用检查含已删除项目 |
| `domain/LogQuery.java` | `type` 增加 `click` |
| `service/StatsQueryService.java` | `logs()` 分支、`logOptions()`、导出、看板项目关注度聚合 |

前端：

| 文件 | 改动 |
|---|---|
| `src/track.js` | 新增 `trackClick()` |
| `src/views/ProjectDetailView.vue` | 仓库链接加 `@click` |
| `src/views/HomeView.vue` | hero 区 GitHub 按钮加 `@click` |
| `src/views/admin/AdminLogsView.vue` | 第 4 个类型按钮 + 表格列 + 筛选 |
| `src/views/admin/AdminStatsView.vue` | 项目关注度表 |

`ProjectService`、`SiteSnapshotService` 不用改（`@TableLogic` 自动过滤已删除）；`router/index.js` 不动。

## 6. 风险

1. **`/api/track` 可被脚本伪造**：与现有访问统计同等暴露，不新增风险等级；`pv_skip` 不记保证自己刷不进。接受。
2. **移动端点击行为差异**：长按菜单、中键（`auxclick`）可能不触发 `click`；用 `click` + `auxclick` 双监听 + 5 秒去重兜底，仍可能漏非标准交互——接受（统计丢一条无所谓，与埋点原则一致）。
3. **`target` 是用户可控内容落到后台页面**：列表渲染必须走 Vue 文本插值（不 `v-html`），链接 `rel="noopener noreferrer"`；长度已在入库时截断。
4. **点击率的分母口径**：项目页 UV 可能包含「打开页面又没滚动」的人，点击率会偏低。这是真实数字，不做修正。

## 7. 验收

本地（未登录浏览器）：

1. 点项目页仓库链接 → `link_click_log` 出现 **1 行**：`target` 为完整 URL、`source_path=/projects/{slug}`、`project_slug={slug}`、地区/终端/`visitor_id` 非空；`visit_log` **不新增行**。
2. 5 秒内双击 → 仍 **1 行**；隔 5 秒再点 → **第 2 行**。
3. 登录后台（`pv_skip`）后点击 → **0 行**。
4. 用 `curl` 直接 POST `/api/track`：`target=javascript:alert(1)` → 不落库；超长 target → 截断 512 后落库。
5. 日志页「外链点击」类型：按日期筛选、关键字搜索、CSV 导出（BOM、中文正常）都可用。
6. 看板项目关注度表：UV 与 `visit_log` 项目维度一致，点击计数与 `link_click_log` 一致。
7. **回归**：同一时间范围的 PV/UV/地区分布/高频 IP 与改动前一致（点击没有污染 `visit_log`）。
8. **id 关联**：`visit_log` 与 `link_click_log` 的项目页行 `project_id` 都非空，且等于对应项目的主键；`project_slug` 只是快照。
9. **软删除**：后台删一个测试项目 → 前台列表/详情 404、后台列表不再出现；但关注度表里该项目**仍在**（标注「已删除」），其历史 UV/点击数不变；用同 slug 新建项目被拒绝。
10. **首页入口**：首页点 GitHub 按钮 → `link_click_log` 落一行（`project_id` 为空），关注度表末尾出现「首页入口」行；确认前台页面仍显示已删除项目之外的项目（`@TableLogic` 没有误伤现有查询）。

线上：部署后手动点一次，看板与日志页都能看到；刷新页面确认埋点没有拖慢导航。

## 8. 排期

| 阶段 | 内容 |
|---|---|
| 1 | schema + 实体 + Mapper + `StatsCollector.recordLinkClick` + `TrackController` 扩展 + 前端 `trackClick` 与挂点 → 本地端到端验证 |
| 2 | 日志页第 4 类型 + 筛选 + 导出 |
| 3 | 看板项目关注度表（待确认 2 决定做不做折线） |
| 4 | 部署 + 线上实测 |

## 9. 已确认

1. **覆盖范围**：项目详情页仓库链接 + 首页 hero 区 GitHub 按钮（a）。
2. **看板形态**：项目关注度表，每个项目一行 = 项目页 UV / 仓库点击 / 点击率（a）。
3. **链接呈现**：保持完整 URL 裸链接，只加埋点（a）。
4. **项目维度（新增要求）**：统计按项目主键 id 关联；项目删除改软删除，历史统计不丢。
