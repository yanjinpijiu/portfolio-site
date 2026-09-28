# 裸机部署（备选路径）

> **优先用 Docker Compose**（根目录的 `docker-compose.yml`），三步就能起。
> 这份文档是给「不用 Docker、直接在服务器上跑」的场景：nginx 发前端静态文件、直接发图片，
> 把 `/api` 反代到本机后端（单个 Java 进程，systemd 管）。

```
浏览器 ──► nginx :80/443 ──┬─► /var/www/portfolio        前端静态文件（Vue 构建产物）
                           ├─► /files/* ──► /opt/portfolio/data/files/   图片（nginx 直接发）
                           └─► /api/* ──► 127.0.0.1:8080   Spring Boot
                                             └─► /opt/portfolio/data/   H2 数据库 + 上传的简历
                                             └─► /opt/portfolio/backups/  每日自动打包的 zip
```

> **端口**：默认 `8080`，由 `/opt/portfolio/portfolio.env` 里的 `SERVER_PORT` 决定。
> 改端口要同时改 nginx 的 `proxy_pass`（`setup-server.sh` 会自动对齐）。
> 如果这台机器上已经跑着别的东西占了 8080，部署前先换一个，比如 `APP_PORT=8090`。

---

## 0. 先确认三件事，不然后面全是白忙

**① 域名备案（仅中国大陆的服务器需要）。** 大陆服务器的 80/443 只对已备案域名放行，
没备案会被拦成备案提示页，跟服务器配置没关系。没备案的话先用 IP 访问验证功能。

**② 防火墙放行 80/443。** 云平台控制台的安全组里要有 `TCP 80` 和 `TCP 443` 两条规则
（这是云平台那一层，和服务器里的 ufw/iptables 不是一回事）。

**③ DNS 加 A 记录。** 你要用的域名（或子域）指向服务器公网 IP，加完 `ping` 一下验证。

---

## 1. 第一次部署

### 1.1 初始化服务器（只做一次）

```bash
scp -r deploy root@<你的服务器公网IP>:/root/
ssh root@<你的服务器公网IP> 'bash /root/deploy/setup-server.sh'
```

脚本干这些事：装 JDK 21 + nginx + 字体 → 建目录 → **生成一个随机后台密钥** →
装 systemd 服务和 nginx 配置 → 停用 nginx 自带的 `default.conf`。

跑完会在屏幕上打印这次生成的后台密钥，**记下来**（忘了就 `cat /opt/portfolio/portfolio.env`）。
这个密钥只存在于服务器上，不进仓库、不进构建产物。

### 1.2 构建 + 上传 + 重启

在本地（Windows 上用 Git Bash）：

```bash
SERVER=root@<你的服务器公网IP> bash deploy/deploy.sh
```

它会依次：构建后端 jar（顺便检查 jar 里没夹带本地开发密钥）→ 构建前端 →
上传 jar 与 `dist/` → 重启后端 → 自检。

### 1.3 验证

```bash
# 服务器上（不依赖域名和备案）
curl -I -H 'Host: 你的域名' http://127.0.0.1/            # 前端
curl -s -H 'Host: 你的域名' http://127.0.0.1/api/site     # 内容接口
```

浏览器打开站点，按这个顺序过一遍：

1. 首页能开、图片不破图（说明 `/api` 和 `/files/` 都通）
2. 后台能登录（说明 POST 没被 CORS 挡）
3. 随便改一句话保存，回前台刷新能看到（说明整条内容链路通）
4. 打开看板，概览里「验证码」显示「正常」（说明服务器字体没问题）

### 1.4 配 HTTPS

```bash
ssh root@<你的服务器公网IP>
certbot --nginx -d 你的域名 --non-interactive --agree-tos --register-unsafely-without-email --redirect
```

certbot 自己改 nginx 配置、签证书、加自动续期。装证书走 80 端口验证，所以**必须等 DNS 生效**再跑。

> 跑完 certbot 之后，服务器上那份 `/etc/nginx/conf.d/portfolio.conf` 会被改写
> （多出 443 的 server 块和 80→443 跳转）。之后以服务器上的为准，
> 仓库里这份是最初的 HTTP 版本，别拿它覆盖回去——覆盖回去的后果不只是丢证书：
> 443 的 server 块没了而用户还在用 https 地址时，页面和接口都会 403。

---

## 2. 以后每次更新

```bash
SERVER=root@<你的服务器公网IP> bash deploy/deploy.sh
```

就这一条。改服务器/端口：`SERVER=root@1.2.3.4 APP_PORT=8090 bash deploy/deploy.sh`。

---

## 3. 常用运维

```bash
ssh root@<你的服务器公网IP> 'journalctl -u portfolio -f'      # 实时日志
ssh root@<你的服务器公网IP> 'portfolio-reload'                 # 重启（会自动修属主）
ssh root@<你的服务器公网IP> 'cat /opt/portfolio/portfolio.env' # 看密钥 / 环境变量
```

**备份。** 后端每天凌晨 3 点自己打包一次，产物在 `/opt/portfolio/backups/`，保留最近 7 份
（`app.backup.*` 可调：`APP_BACKUP_ENABLED` / `dir` / `keep`）。包里是 `db.zip`
（H2 用自带 BACKUP 命令导的一致快照）和 `files/`（图片与简历原件）。

```bash
ssh root@<你的服务器公网IP> 'ls -lh /opt/portfolio/backups/ | tail -3'
scp root@<你的服务器公网IP>:/opt/portfolio/backups/portfolio-YYYYMMDD-HHMM.zip .
```

**内容导出。** 后台「导出备份」页可以把全部文字内容导成一份 JSON 留底，
它不含图片和统计——图片在每日 zip 里，统计是运行时数据。

**前台内容有两种模式。** 后台「数据看板」顶部那张卡片可以切：

| | 动态（默认） | 静态 |
|---|---|---|
| 前台内容来源 | 每次访问调 `/api/site` 实时查库 | 站点目录下的 `snapshot.json` 静态文件 |
| 首屏 | 多一次接口往返 | 少这一次往返，但仍是同步等一个文件 |
| 改内容 | 刷新就生效 | 后台保存时**自动重建快照**，所以也是刷新就生效 |

实现上只动两个文件，不改 nginx 也不用重新构建：切静态就在 `index.html` 里插一行
`window.__SITE_MODE__="static"` 并生成 `snapshot.json`；切回动态就删掉快照、清掉那行。
「部署」会覆盖 `index.html`，后端启动时会按数据库里记的模式重新插回去
（`SiteModeInitializer`），模式不会因为一次发布悄悄丢。

**演示模式（只读）。** `portfolio.env` 里设 `APP_DEMO_MODE=true`，后台就能登录、能看，
但所有写操作由服务端直接拒绝（403）。适合把站当 demo 给别人点。

**短片（项目展示页）。** 上传只收 mp4 / webm，服务端不转码，所以压缩放在上传前做：

```bash
# 去掉音轨、压到 720 宽、faststart（边下边播），8 秒的屏幕录制约 200KB
ffmpeg -i 录屏.mp4 -an -vf "scale=720:-2" -c:v libx264 -crf 30 -pix_fmt yuv420p -movflags +faststart out.mp4
```

**别用 GIF**：同一段 8.7 秒的录屏，H.264 720 宽是 207KB，GIF（480 宽、12fps）是 2634KB——
大 12 倍还糊，上传接口也会直接拒掉。线上短片由 nginx 直发并支持 Range 请求（所以能拖进度）。

**字体是必需的。** 下载验证码是用 Java 的 Graphics2D 画的，而 Linux 上的 JDK
自身不含字体、靠 fontconfig 找系统字体。缺字体时 `drawString` 不报错、只画出一张
空白图，谁也认不出来，简历就永远下不了。所以 `setup-server.sh` 里装
`fontconfig` + `dejavu-sans-fonts`；后端启动时会渲染一张图自检，画不出来会打一条 ERROR
并**自动放行**。后台看板概览里有一张「验证码」卡片，显示「正常 / 已降级」。

**换成对象存储。** 默认 `APP_STORAGE_TYPE=local`，简历存在服务器磁盘上。想换阿里云 OSS：
建好 bucket 和 RAM 子账号（只要 `oss:PutObject` / `oss:GetObject` / `oss:DeleteObject`），
编辑 `/opt/portfolio/portfolio.env` 填上那四项并改成 `oss`，然后 `portfolio-reload`。
已经上传过的文件不会自动迁移，需要重新在后台传一次。

---

## 4. 出问题先看这里

| 现象 | 原因 |
|---|---|
| 浏览器打不开，`ping` 通 | 备案没过，或防火墙没放行 80 |
| 首页能开，刷新 `/projects/xxx` 变 404 | nginx 少了 `try_files $uri $uri/ /index.html` |
| 接口 502 | 后端没起来，`journalctl -u portfolio -n 50` 看日志 |
| 接口 502，但后端日志一切正常 | nginx 的 `proxy_pass` 端口和 `portfolio.env` 里的 `SERVER_PORT` 对不上 |
| 后端日志说端口被占用 | 这台机器上别的服务占了 8080，把 `SERVER_PORT` 改成别的 |
| 后端起不来，日志说「未配置后台密钥」 | `/opt/portfolio/portfolio.env` 丢了或里面没有 `APP_ADMIN_KEY` |
| **GET 都正常，所有 POST 返回 403 Invalid CORS request** | 后端不知道自己在反向代理后面（拿 http 的自己和 https 的 Origin 比，判成跨域）。检查 `application.yml` 里的 `server.forward-headers-strategy: framework` 还在不在，以及 nginx 有没有传 `X-Forwarded-Proto` |
| 后台登录提示「登录已失效」但密钥没错 | 大概率同上（POST 被 CORS 挡了，前端只看到 401/403） |
| 后台能登录但保存报 403 | 打开了 `APP_DEMO_MODE=true`（演示模式只读）。要能写就改成 false 再 reload |
| 验证码是空白图 / 下载一直要验证码却看不清 | 服务器缺字体，装 `fontconfig` + 字体包后 `portfolio-reload` |
| 图片 404 | nginx 的 `/files/` alias 指向的目录和后端的 `app.storage.local.dir` 不是同一个 |
| 后台上传大 PDF 失败 | nginx `client_max_body_size` 比后端 `max-file-size` 小 |
| 看板「埋点队列」一直有丢弃数 | 埋点写入跟不上，队列被丢；偶尔几条无所谓，持续增长要查磁盘 |
| 切到静态后前台没变化 | 看后台那张卡片的提示；多半是 `index.html` 被部署覆盖了，点「重新生成快照」 |
| 展示页的短片不播 | 扩展名必须是 mp4/webm（前端按扩展名决定渲染 video 还是 img） |
| certbot 报验证失败 | DNS 没生效，或 80 被防火墙挡着 |
| 发新版后页面还是旧的 | `index.html` 被缓存了，检查它的缓存头是不是 `no-cache` |
