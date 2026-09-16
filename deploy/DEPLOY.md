# 部署到 qianlink.top

服务器 `<你的服务器公网IP>`（阿里云轻量）。
架构：nginx 发前端静态文件、直接发图片，并把 `/api` 反代到本机后端（单个 Java 进程，systemd 管）。

```
浏览器 ──► nginx :80/443 ──┬─► /var/www/qianlink        前端静态文件（Vue 构建产物）
                           ├─► /files/* ──► /opt/portfolio/data/files/   图片（nginx 直接发）
                           └─► /api/* ──► 127.0.0.1:8090   Spring Boot
                                             └─► /opt/portfolio/data/   H2 数据库 + 上传的简历
                                             └─► /opt/portfolio/backups/  每日自动打包的 zip
```

> **后端端口是 8090，不是 8080/8081/8082。** 这台服务器上已经跑着 RocketMQ 5.x
> （broker 以 `--enable-proxy` 启动），它的 proxy 一次占了 8080-8082 三个端口。
> 用这几个部署会直接失败。端口在 `portfolio.env` 的 `SERVER_PORT` 里，
> nginx 的 `proxy_pass` 必须跟着改。

## 服务器上已经跑着什么

部署前实测过一遍，这台机器不是空的：

| 端口 | 服务 |
|---|---|
| 22 | SSH |
| 3306 | MySQL 5.7.36，**对公网开放** |
| 6379 | Redis |
| 8080 / 8081 | RocketMQ proxy（HTTP / gRPC） |
| 10911 | RocketMQ broker |
| 8000 / 8001 / 8002 | 空着（短链接项目的 gateway / project / admin 用的就是这三个） |

两个安全上的事，顺手提一下，跟本次部署无关但建议处理：

1. **MySQL 3306 对公网开着。** 从外网能直接收到握手包，配置里还写着 root 密码。
   建议在阿里云防火墙里删掉 3306 这条规则，只留本机访问。
2. **Redis 6379 也开着**，密码写在 `shortlink` 的 `application.yml` 里。
   同样建议收到防火墙后面。

---

## 0. 先确认三件事，不然后面全是白忙

**① 域名备案。** 大陆服务器的 80/443 只对已备案域名放行。`qianlink.top` 如果没备案，
访问会被拦成备案提示页，跟服务器配置没关系。去阿里云控制台「备案」看状态，
或查 <https://beian.miit.gov.cn>。没备案的话，先用 IP 访问验证功能是否正常。

**② 防火墙放行 80/443。** 阿里云轻量控制台 → 你的实例 → 防火墙 → 确认有
`TCP 80` 和 `TCP 443` 两条规则。轻量服务器默认规则里通常有，但被删过就得补。
（这是云平台那一层的防火墙，和服务器里的 ufw/iptables 不是一回事。）

**③ DNS 加 A 记录。** 域名在阿里云（DNS 是 `dns11.hichina.com`），到「云解析 DNS」里加两条：

| 记录类型 | 主机记录 | 记录值 |
|---|---|---|
| A | `@` | `<你的服务器公网IP>` |
| A | `www` | `<你的服务器公网IP>` |
| A | `me` | `<你的服务器公网IP>` |

加完验证：`ping qianlink.top` 能出 `<你的服务器公网IP>` 就对了。

> `me.qianlink.top` 这条已经有了（简历上的「通过Web了解我」就指向它）。
> `@` 和 `www` 目前还没有记录，不补的话主域名打不开。
> `me` 一定要留着，否则简历上的链接会失效。

---

## 1. 第一次部署

### 1.1 初始化服务器（只做一次）

```bash
scp -r deploy root@<你的服务器公网IP>:/root/
ssh root@<你的服务器公网IP> 'bash /root/deploy/setup-server.sh'
```

脚本干这些事：装 JDK 21 + nginx → 建目录 → **生成一个随机后台密钥** →
装 systemd 服务和 nginx 配置 → 停用 nginx 自带的 `default.conf`。

跑完会在屏幕上打印这次生成的后台密钥，**记下来**（忘了就 `cat /opt/portfolio/portfolio.env`）。
这个密钥只存在于服务器上，不进仓库、不进构建产物、也不经过聊天记录。

### 1.2 构建 + 上传 + 重启

在本地 Git Bash 里：

```bash
bash deploy/deploy.sh
```

它会依次：构建后端 jar（顺便检查 jar 里没夹带本地开发密钥）→ 构建前端 →
scp 上传 jar → tar 上传 `dist/` → 重启后端 → 自检三个接口。

结尾应该看到：

```
  后端 /api/projects  -> HTTP 200
  nginx 首页          -> HTTP 200
  nginx 转发 /api     -> HTTP 200
```

### 1.3 验证

```bash
# 服务器上（不依赖域名和备案）
curl -I -H 'Host: me.qianlink.top' http://127.0.0.1/          # 前端
curl -s -H 'Host: me.qianlink.top' http://127.0.0.1/api/site   # 内容接口
curl -s -o /dev/null -w '%{content_type}
'   -H 'Host: me.qianlink.top' http://127.0.0.1/files/seed/avatar.jpg   # 图片

# DNS 生效后本机浏览器直接打开
https://me.qianlink.top
https://me.qianlink.top/admin        # 登录后进后台；密钥见 1.1 或 portfolio.env
```

后台登录时**必须**用带 `Origin` 的正常浏览器访问（`curl -X POST` 不带 Origin 时
会绕过 CORS 判断，测出来的结果没意义）。部署后建议按这个顺序过一遍：

1. 首页能开、头像和项目图不破图（说明 `/api/site` 和 `/files/` 都通）
2. 后台能登录（说明 POST 没被 CORS 挡）
3. 随便改一句话保存，回前台刷新能看到（说明整条内容链路通）
4. 打开看板，概览里「验证码」显示「正常」（说明服务器字体没问题）

### 1.4 配 HTTPS

DNS 生效、80 端口能正常访问之后：

```bash
ssh root@<你的服务器公网IP>
certbot --nginx -d me.qianlink.top --non-interactive --agree-tos --register-unsafely-without-email --redirect
```

（2026-09-15 已对 `me.qianlink.top` 执行过，证书自动续期由 certbot 的定时任务负责。
`qianlink.top` 和 `www.qianlink.top` 的 A 记录还没加，DNS 好了之后补签一次即可：
把这两个域名追加到 `-d` 后面。）

certbot 自己改 nginx 配置、签证书、加自动续期。装证书走的是 80 端口验证，
所以**必须等 DNS 生效**再跑，急不得。

> 跑完 certbot 之后，服务器上那份 `/etc/nginx/conf.d/qianlink.conf` 会被改写
> （多出 443 的 server 块和 80→443 跳转）。之后以服务器上的为准，
> 仓库里这份是最初的 HTTP 版本，别拿它覆盖回去。
> 覆盖回去的后果不只是丢证书：**从 http:// 访问时 `$scheme` 是 http，
> 后端会把浏览器的 http Origin 当成同源**，一切正常；
> 但如果你把 443 的 server 块删了而用户还在用 https 地址，页面和接口都会 403。

---

## 2. 以后每次更新

```bash
bash deploy/deploy.sh
```

就这一条。改服务器 IP：`SERVER=root@1.2.3.4 bash deploy/deploy.sh`。

> 部署脚本会先 `mvn clean package`，如果本机还有一个在跑的后端占着那个 jar，
> 构建会失败（「另一个程序正在使用此文件」）。先杀掉它：
> `powershell -c "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? CommandLine -like '*portfolio-1.0.0.jar*' | % { Stop-Process -Id $_.ProcessId -Force }"`

---

## 3. 常用运维

```bash
# 看后端日志（实时）
ssh root@<你的服务器公网IP> 'journalctl -u portfolio -f'

# 重启后端（会自动修属主）
ssh root@<你的服务器公网IP> 'portfolio-reload'

# 看这次生成的密钥
ssh root@<你的服务器公网IP> 'cat /opt/portfolio/portfolio.env'

# 换一个密钥：编辑 portfolio.env 里的 APP_ADMIN_KEY，然后 restart
ssh root@<你的服务器公网IP>
vi /opt/portfolio/portfolio.env
portfolio-reload
```

**备份。** 后端每天凌晨 3 点自己打包一次，产物在 `/opt/portfolio/backups/`，
保留最近 7 份（`app.backup.*` 可调：`APP_BACKUP_ENABLED` / `dir` / `keep`）。
包里是 `db.zip`（H2 用自带 BACKUP 命令导的一致快照）和 `files/`（图片与简历原件）。

```bash
# 手动补一次
ssh root@<你的服务器公网IP> 'ls -lh /opt/portfolio/backups/ | tail -3'
# 取一份回来
scp root@<你的服务器公网IP>:/opt/portfolio/backups/portfolio-YYYYMMDD-HHMM.zip .
```

**内容导出。** 后台「导出备份」页可以把全部文字内容导成一份 JSON 留底，
它不含图片和统计——图片在每日 zip 里，统计是运行时数据。

**前台内容有两种模式。** 后台「数据看板」顶部那张卡片可以切：

| | 动态（默认） | 静态 |
|---|---|---|
| 前台内容来源 | 每次访问调 `/api/site` 实时查库 | 站点目录下的 `snapshot.json` 静态文件 |
| 首屏 | 多一次接口往返（公网实测约 60ms） | 少这一次往返，但仍是同步等一个文件 |
| 改内容 | 刷新就生效 | 后台保存时**自动重建快照**，所以也是刷新就生效 |
| 项目/简历/页面文案 | 实时 | 都来自快照 |

实现上只动两个文件，不改 nginx 也不用重新构建：切静态就在 `index.html` 里插一行
`window.__SITE_MODE__="static"` 并生成 `snapshot.json`；切回动态就删掉快照、清掉那行。
所以「部署」会覆盖 `index.html`，后端启动时会按数据库里记的模式重新插回去
（`SiteModeInitializer`），模式不会因为一次发布悄悄丢。

真要是模式状态对不上（比如手动动过文件），后台那张卡片会直接提示，
点「切到静态 / 重新生成快照」即可修复。

**短片（项目展示页）。** 上传只收 mp4 / webm，服务端不转码，所以压缩放在上传前做：

```bash
# 去掉音轨、压到 720 宽、faststart（边下边播），8 秒的屏幕录制约 200KB
ffmpeg -i 录屏.mp4 -an -vf "scale=720:-2" -c:v libx264 -crf 30 -pix_fmt yuv420p -movflags +faststart out.mp4
```

**别用 GIF**：同一段 8.7 秒的录屏，H.264 720 宽是 207KB，GIF（480 宽、12fps）是 2634KB——
大 12 倍还糊，上传接口也会直接拒掉。线上短片由 nginx 直发并支持 Range 请求
（所以能拖动进度条）。

**字体是必需的。** 下载验证码是用 Java 的 Graphics2D 画的，而 Linux 上的 JDK
自身不含字体、靠 fontconfig 找系统字体。缺字体时 `drawString` 不报错、只画出一张
空白图，谁也认不出来，简历就永远下不了。所以 `setup-server.sh` 里装
`fontconfig` + `dejavu-sans-fonts`；后端启动时会渲染一张图自检，
画不出来会打一条 ERROR 并**自动放行**（宁可验证码失效，也不能让人下不了简历）。
后台看板概览里有一张「验证码」卡片，显示「正常 / 已降级」，一眼能看出状态。

目前 `APP_STORAGE_TYPE=local`，简历存在服务器磁盘上。想换阿里云 OSS：
建好 bucket 和 RAM 子账号（只要 `oss:PutObject` / `oss:GetObject` / `oss:DeleteObject`），
编辑 `/opt/portfolio/portfolio.env` 填上那四项并改成 `APP_STORAGE_TYPE=oss`，
然后 `portfolio-reload`。已经上传过的简历不会自动迁移，需要重新在后台传一次。

---

## 4. 出问题先看这里

| 现象 | 原因 |
|---|---|
| 浏览器打不开，`ping` 通 | 备案没过，或防火墙没放行 80 |
| 首页能开，刷新 `/projects/xxx` 变 404 | nginx 少了 `try_files $uri $uri/ /index.html` |
| 接口 502 | 后端没起来，`journalctl -u portfolio -n 50` 看日志 |
| 接口 502，但后端日志一切正常 | nginx 的 `proxy_pass` 端口和 `portfolio.env` 里的 `SERVER_PORT` 对不上 |
| 后端日志说端口被占用 | 撞上 RocketMQ 的 8080/8081 了，把 `SERVER_PORT` 改成别的 |
| 后端起不来，日志说「未配置后台密钥」 | `/opt/portfolio/portfolio.env` 丢了或里面没有 `APP_ADMIN_KEY` |
| **GET 都正常，所有 POST 返回 403 Invalid CORS request** | 后端不知道自己在反向代理后面（拿 http 的自己和 https 的 Origin 比，判成跨域）。检查 `application.yml` 里的 `server.forward-headers-strategy: framework` 还在不在，以及 nginx 有没有传 `X-Forwarded-Proto` |
| 后台登录提示「登录已失效」但密钥没错 | 大概率同上（POST 被 CORS 挡了，前端只看到 401/403） |
| 验证码是空白图 / 下载一直要验证码却看不清 | 服务器缺字体，`dnf install -y dejavu-sans-fonts fontconfig` 后 `portfolio-reload` |
| 图片 404 | nginx 的 `/files/` alias 指向的目录和后端的 `app.storage.local.dir` 不是同一个 |
| 后台上传大 PDF 失败 | nginx `client_max_body_size` 比后端 `max-file-size` 小 |
| 看板「埋点队列」一直有丢弃数 | 埋点写入跟不上，队列被丢；偶尔几条无所谓，持续增长要查磁盘 |
| 切到静态后前台没变化 | 看后台那张卡片的提示；多半是 `index.html` 被部署覆盖了，点「重新生成快照」 |
| 后台保存成功但前台没变 | 静态模式下会自动重建快照，若没生效看日志有没有「自动重建静态快照」；实在不行点一次「重新生成快照」 |
| 展示页的短片不播 | 扩展名必须是 mp4/webm（前端按扩展名决定渲染 video 还是 img） |
| certbot 报验证失败 | DNS 没生效，或 80 被防火墙挡着 |
| 发新版后页面还是旧的 | `index.html` 被缓存了，检查它的缓存头是不是 `no-cache` |
