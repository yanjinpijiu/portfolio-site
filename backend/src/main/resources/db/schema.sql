-- 项目展示数据
CREATE TABLE IF NOT EXISTS project (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    slug         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    type         VARCHAR(32)  NOT NULL,
    period       VARCHAR(64),
    role         VARCHAR(128),
    summary      VARCHAR(512),
    description  VARCHAR(2000),
    tags         VARCHAR(512),
    highlights   VARCHAR(4000),
    repo_url     VARCHAR(512),
    repo_label   VARCHAR(64),
    sort_order   INT DEFAULT 0,
    visible      TINYINT DEFAULT 1,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_slug ON project (slug);

-- 简历文件元数据
CREATE TABLE IF NOT EXISTS resume (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    direction      VARCHAR(64),
    file_name      VARCHAR(256) NOT NULL,
    object_key     VARCHAR(512) NOT NULL,
    file_size      BIGINT DEFAULT 0,
    content_type   VARCHAR(128),
    active         TINYINT DEFAULT 1,
    download_count INT DEFAULT 0,
    sort_order     INT DEFAULT 0,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_resume_active ON resume (active);

-- ============================================================
-- 站点内容（后台可增删改）
-- 这些原来写死在前端 profile.js / certificates.js / galleries.js 里，
-- 搬进数据库后改内容不用重新构建部署。
-- ============================================================

-- 个人资料，单例（永远只有 id=1 这一行）
CREATE TABLE IF NOT EXISTS site_profile (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,
    name_en      VARCHAR(64),
    title        VARCHAR(128),
    school       VARCHAR(128),
    graduation   VARCHAR(32),
    city         VARCHAR(32),
    email        VARCHAR(128),
    phone        VARCHAR(32),
    -- 头像在存储里的 key（形如 2026/09/xxx.jpg），存相对值不存绝对 URL，换域名不用改数据
    avatar_key   VARCHAR(512),
    -- 自我介绍，一行一条
    intro        VARCHAR(2000),
    availability VARCHAR(256),
    updated_at   TIMESTAMP
);

-- 联系方式卡片
-- 注意 value_text 这个列名：value 是 H2 的保留字（SQL 标准里也是），
-- 直接叫 value 建表会报 "Syntax error ... expected identifier"
CREATE TABLE IF NOT EXISTS contact (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    label      VARCHAR(32) NOT NULL,
    value_text VARCHAR(256),
    href       VARCHAR(512),
    icon       VARCHAR(32),
    sort_order INT DEFAULT 0,
    visible    TINYINT DEFAULT 1
);

-- 技能分组
CREATE TABLE IF NOT EXISTS skill_group (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    category   VARCHAR(64) NOT NULL,
    sort_order INT DEFAULT 0,
    visible    TINYINT DEFAULT 1
);

-- 技能点（属于某个分组）
CREATE TABLE IF NOT EXISTS skill_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id   BIGINT       NOT NULL,
    text       VARCHAR(256) NOT NULL,
    sort_order INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_skill_item_group ON skill_item (group_id);

-- 技能分组关联的项目（点技能卡时列出的「用在这些项目里」）
CREATE TABLE IF NOT EXISTS skill_group_project (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id     BIGINT      NOT NULL,
    project_slug VARCHAR(64) NOT NULL,
    sort_order   INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sgp_group ON skill_group_project (group_id);

-- 证书
CREATE TABLE IF NOT EXISTS certificate (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(256) NOT NULL,
    org        VARCHAR(256),
    cert_date  VARCHAR(64),
    summary    VARCHAR(256),
    image_key  VARCHAR(512),
    -- 缩略图宽高比，形如 "4/3"，前端拿来占位防跳动
    ratio      VARCHAR(16),
    -- 归属的技能分组（挂在「语言能力」那组下面）
    group_id   BIGINT,
    sort_order INT DEFAULT 0,
    visible    TINYINT DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_cert_group ON certificate (group_id);

-- 项目成果图
CREATE TABLE IF NOT EXISTS project_image (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_slug VARCHAR(64)  NOT NULL,
    image_key    VARCHAR(512) NOT NULL,
    caption      VARCHAR(512),
    sort_order   INT DEFAULT 0,
    visible      TINYINT DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_proj_img_slug ON project_image (project_slug);

-- 页面区块文案（各页的大标题 / 副标题）
CREATE TABLE IF NOT EXISTS site_section (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    section_key VARCHAR(64) NOT NULL,
    eyebrow     VARCHAR(64),
    title       VARCHAR(128),
    description VARCHAR(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_section_key ON site_section (section_key);

-- 零散的站点设置（SEO、页脚、导航等）。label/hint 是给后台设置页显示用的
CREATE TABLE IF NOT EXISTS site_setting (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    setting_key   VARCHAR(64) NOT NULL,
    setting_value VARCHAR(2000),
    label         VARCHAR(128),
    hint          VARCHAR(512),
    sort_order    INT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_setting_key ON site_setting (setting_key);

-- ============================================================
-- 访客与接口统计
--
-- 短链那套是 Redis Stream 异步 + 8 张按天预聚合表 + 分库分表，
-- 那是为「秒杀级 QPS + 千万行明细」设计的。这里是单实例 + H2 +
-- 日均几十 PV，量级差四个数量级，明细表 + GROUP BY + 索引绰绰有余。
--
-- 时间维度（date/hour/weekday）在入库时就算好写成普通列，查询只做
-- GROUP BY —— H2 的 MySQL 兼容模式函数不全，在 SQL 里调 DATE_FORMAT
-- 或 DATE() 是最容易翻车的地方。
-- ============================================================

CREATE TABLE IF NOT EXISTS visit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 浏览器 cookie 里的匿名 ID，用来算 UV
    visitor_id    VARCHAR(64),
    ip            VARCHAR(64),
    browser       VARCHAR(32),
    os            VARCHAR(32),
    device        VARCHAR(32),
    country       VARCHAR(64),
    province      VARCHAR(64),
    city          VARCHAR(64),
    path          VARCHAR(256),
    route_name    VARCHAR(64),
    -- home / projects / project / resume / other
    page_type     VARCHAR(32),
    project_slug  VARCHAR(64),
    referer       VARCHAR(512),
    visit_date    DATE,
    visit_hour    INT,
    visit_weekday INT,
    created_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_visit_created ON visit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_visit_date ON visit_log (visit_date);
CREATE INDEX IF NOT EXISTS idx_visit_visitor ON visit_log (visitor_id);

-- 简历下载明细。既是统计来源，也是「同 IP 同简历 24 小时内前 3 次免验证码」的判断依据
CREATE TABLE IF NOT EXISTS resume_download_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id    BIGINT,
    -- 快照当时的名称和方向：简历改名或删掉之后，历史统计还看得懂
    resume_title VARCHAR(128),
    direction    VARCHAR(64),
    visitor_id   VARCHAR(64),
    ip           VARCHAR(64),
    country      VARCHAR(64),
    province     VARCHAR(64),
    city         VARCHAR(64),
    -- 1 = 走的免费额度，0 = 过了验证码
    free_pass    TINYINT DEFAULT 0,
    -- 和 visit_log 一样，时间维度入库时算好，查询只做 GROUP BY
    visit_date   DATE,
    visit_hour   INT,
    created_at   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_download_ip_resume ON resume_download_log (ip, resume_id, created_at);
CREATE INDEX IF NOT EXISTS idx_download_created ON resume_download_log (created_at);

-- 接口访问明细。登录接口的爆破痕迹、慢接口、各接口调用量都从这儿看
CREATE TABLE IF NOT EXISTS api_access_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    path        VARCHAR(256),
    method      VARCHAR(8),
    -- 业务码（0 = 成功）；登录失败是 401，被限流是 429
    biz_code    INT,
    http_status INT,
    duration_ms INT,
    ip          VARCHAR(64),
    visitor_id  VARCHAR(64),
    -- 1 = 后台自己（带 X-Admin-Token 或种过 pv_skip cookie）发的请求。
    -- 看板默认只看 0 的，否则自己在后台点几下就把「接口调用量排行」刷满了
    internal    TINYINT DEFAULT 0,
    visit_date  DATE,
    visit_hour  INT,
    created_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_api_created ON api_access_log (created_at);
CREATE INDEX IF NOT EXISTS idx_api_path ON api_access_log (path, created_at);
