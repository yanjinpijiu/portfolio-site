#!/usr/bin/env bash
#
# 服务器初始化脚本，一台机器只需要跑一次。以 root 执行：
#
#   scp -r deploy root@<你的服务器公网IP>:/root/
#   ssh root@<你的服务器公网IP> 'bash /root/deploy/setup-server.sh'
#
# 做的事：装 JDK21 + nginx → 建目录 → 生成后台密钥 → 装 systemd 服务和 nginx 配置
# 不会碰数据库，也不会启动后端（后端要等你上传 jar 之后才起得来）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_DIR=/opt/portfolio
WEB_DIR=/var/www/qianlink
SERVICE_NAME=portfolio

# 后端监听端口。这台机器上 RocketMQ 的 broker 容器一次性映射了 8080-8082
# （proxy 用的就是这三个），所以别选那一带。换端口：APP_PORT=9000 bash setup-server.sh
APP_PORT="${APP_PORT:-8090}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[注意] %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m[错误] %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 执行（sudo bash $0）"

# ---------------------------------------------------------------- 1. 装依赖
# dejavu-sans-fonts 不是可选项：下载接口的验证码是用 Java 的 Graphics2D 画的，
# 而 Linux 上的 JDK 自身不含字体，全靠 fontconfig 找系统字体。字体缺失时
# drawString 不会报错，只会画出一张空白图 —— 谁也认不出来，简历就永远下不下来了。
# 后端启动时会渲染一张图自检，画不出来会自动放行并打 ERROR 日志，但不该让它走到那一步。
say "安装 JDK 21 / nginx / openssl / 字体"
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-21-jre-headless nginx openssl curl tar \
        fontconfig fonts-dejavu-core
else
    if command -v dnf >/dev/null 2>&1; then PKG=dnf
    elif command -v yum >/dev/null 2>&1; then PKG=yum
    else die "没识别出包管理器（apt/dnf/yum 都没有），请手动装好 JDK 21 和 nginx 后重跑"
    fi

    # JDK 21 的包名各家不一样：RHEL/CentOS 叫 java-21-openjdk-headless，
    # 阿里的 Alibaba Cloud Linux 3 仓库里没有它，只有自家的 Dragonwell。
    # 挨个探，哪个仓库里有就用哪个，别写死。
    JAVA_PKG=""
    for cand in java-21-openjdk-headless java-21-alibaba-dragonwell-headless java-21-openjdk; do
        if $PKG list --available "$cand" 2>/dev/null | grep -q "^${cand}\.x86_64"; then
            JAVA_PKG="$cand"
            break
        fi
    done
    [ -n "$JAVA_PKG" ] || die "仓库里找不到 JDK 21。手动装一个（Adoptium 或发行版官方源）后重跑"
    say "选用 JDK 包：${JAVA_PKG}"
    $PKG install -y nginx openssl curl tar "$JAVA_PKG" fontconfig dejavu-sans-fonts
fi

command -v java >/dev/null 2>&1 || die "java 没装上。Ubuntu 20.04 及更早版本源里没有 JDK 21，需要手动装（Adoptium 或发行版官方源）"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[ "${JAVA_MAJOR:-0}" -ge 21 ] 2>/dev/null || die "当前 java 版本是 ${JAVA_MAJOR}，本项目需要 21 及以上"
JAVA_BIN="$(command -v java)"
say "JDK 版本检查通过：$(java -version 2>&1 | head -1)  (${JAVA_BIN})"

# ----------------------------------------------------------------- 加 swap
# 内存小的机器上这一步很关键：这台默认没开 swap，而里面可能已经跑着
# MySQL / Redis / RocketMQ。再加一个 JVM 进去，内存一旦吃紧，内核会直接
# 挑一个进程 OOM 掉 —— 未必是这个新来的，也可能把你原来的 MySQL 干掉。
if [ "$(swapon --show 2>/dev/null | wc -l)" -eq 0 ]; then
    say "检测到没有 swap，创建 2G swapfile 兜底"
    fallocate -l 2G /swapfile 2>/dev/null \
        || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
    say "swap 已启用：$(free -h | awk '/Swap/{print $2}')"
else
    say "已有 swap，跳过"
fi

# ------------------------------------------------------------ 2. 运行用户
if id www-data >/dev/null 2>&1; then
    WEB_USER=www-data
elif id nginx >/dev/null 2>&1; then
    WEB_USER=nginx
else
    WEB_USER=root
    warn "没找到 www-data / nginx 用户，后端将以 root 运行"
fi
say "后端运行用户：${WEB_USER}"

# ---------------------------------------------------------------- 3. 建目录
say "创建目录"
mkdir -p "$APP_DIR/data/files" "$WEB_DIR"
chown -R "${WEB_USER}:${WEB_USER}" "$APP_DIR" "$WEB_DIR"
chmod 755 "$APP_DIR" "$WEB_DIR"

# ------------------------------------------------------------ 4. 生成密钥
if [ -f "$APP_DIR/portfolio.env" ]; then
    say "已存在 $APP_DIR/portfolio.env，跳过（不覆盖你现有的密钥）"
else
    say "生成后台登录密钥"
    ADMIN_KEY="$(openssl rand -base64 24 | tr '+/' '-_' | tr -d '=')"
    cat > "$APP_DIR/portfolio.env" <<EOF
# 后端环境变量。本文件权限 600，只有 root 和运行用户可读。
# 修改后执行 portfolio-reload 生效。

APP_ADMIN_KEY=${ADMIN_KEY}
SERVER_PORT=${APP_PORT}
APP_STORAGE_TYPE=local

# 站点静态目录。后端要往里写 snapshot.json / index-static.html（静态/动态模式切换用），
# 所以这个目录必须归运行用户所有
APP_WEB_ROOT=${WEB_DIR}

# 换成阿里云 OSS 时：上面改成 oss，并取消下面四行注释
#OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
#OSS_BUCKET=
#OSS_ACCESS_KEY_ID=
#OSS_ACCESS_KEY_SECRET=
EOF
fi
chown "${WEB_USER}:${WEB_USER}" "$APP_DIR/portfolio.env"
chmod 600 "$APP_DIR/portfolio.env"

# ------------------------------------------------------------ 5. 装服务
say "安装 systemd 服务"
[ -f "$SCRIPT_DIR/portfolio.service" ] || die "找不到 $SCRIPT_DIR/portfolio.service，请确认 deploy 目录已完整上传"
sed -e "s|^User=www-data$|User=${WEB_USER}|" \
    -e "s|^Group=www-data$|Group=${WEB_USER}|" \
    -e "s|^ExecStart=/usr/bin/java |ExecStart=${JAVA_BIN} |" \
    "$SCRIPT_DIR/portfolio.service" > "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true

# 部署后统一走这个命令，避免每次手动 chown + restart
cat > /usr/local/bin/portfolio-reload <<EOF
#!/usr/bin/env bash
# 由 setup-server.sh 生成。重新部署后调用：改属主 + 重启后端 + 等它真的能收请求。
set -e
chown -R ${WEB_USER}:${WEB_USER} ${APP_DIR} ${WEB_DIR}
# 不让 restart 的失败直接中断：真正有用的信息是下面的日志，不是 systemctl 的报错
systemctl restart ${SERVICE_NAME} || true

# Type=simple 的服务，进程一起来 systemd 就报 active，这时候 Spring 往往还没
# 初始化完（首次启动还要建表、灌项目数据）。所以不能只看 is-active，
# 得真的去请求一次接口，否则部署脚本会误报成功。
for i in \$(seq 1 40); do
    if curl -sf -o /dev/null http://127.0.0.1:${APP_PORT}/api/projects; then
        echo "后端已就绪（\${i} 秒）"
        exit 0
    fi
    sleep 1
done

echo "!! 等了 40 秒后端还没起来，最近日志："
journalctl -u ${SERVICE_NAME} -n 40 --no-pager
exit 1
EOF
chmod +x /usr/local/bin/portfolio-reload

# ------------------------------------------------------------ 6. 装 nginx
say "安装 nginx 配置"
if [ -f /etc/nginx/conf.d/default.conf ]; then
    mv /etc/nginx/conf.d/default.conf /etc/nginx/conf.d/default.conf.disabled
    say "已停用 nginx 自带的 default.conf（它监听 80 会抢走请求）"
fi
[ -f "$SCRIPT_DIR/nginx.conf" ] || die "找不到 $SCRIPT_DIR/nginx.conf"
# 把 nginx 里的反代端口对齐到 APP_PORT，免得 nginx 指 8082、后端跑在别的端口上变成 502
sed "s|proxy_pass http://127.0.0.1:[0-9]*;|proxy_pass http://127.0.0.1:${APP_PORT};|g" \
    "$SCRIPT_DIR/nginx.conf" > /etc/nginx/conf.d/qianlink.conf
if ! grep -q "127.0.0.1:${APP_PORT};" /etc/nginx/conf.d/qianlink.conf; then
    die "nginx 配置里的反代端口没替换成功，检查 deploy/nginx.conf 是不是被改过"
fi

# SELinux 开启的系统（CentOS 系）需要放行 nginx 反代
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" = "Enforcing" ]; then
    setsebool -P httpd_can_network_connect 1 || warn "setsebool 失败，nginx 可能无法反代到后端"
fi

nginx -t || die "nginx 配置检查没通过"
systemctl enable nginx >/dev/null 2>&1 || true
systemctl restart nginx

# ---------------------------------------------------------------- 7. 收尾
HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
say "完成"
cat <<EOF

  后端目录    ${APP_DIR}          （现在还没有 jar，等本地 deploy.sh 上传）
  前端目录    ${WEB_DIR}
  密钥文件    ${APP_DIR}/portfolio.env
  运行用户    ${WEB_USER}

  你这次的后台登录密钥是：

      $(grep '^APP_ADMIN_KEY=' "$APP_DIR/portfolio.env" | cut -d= -f2-)

  记下来，忘了就 cat ${APP_DIR}/portfolio.env

  接下来还要做的两件事（脚本做不了，得去控制台点）：

  1. 阿里云轻量控制台 → 防火墙，确认放行 TCP 80 / 443
  2. 域名解析的 A 记录，都指向 <你的服务器公网IP>：
       @     （即 qianlink.top）
       www
       me    （简历上「通过Web了解我」指的就是这个，别删）
     加完验证：  ping qianlink.top

  解析生效后再签 HTTPS 证书（RHEL 系要先有 EPEL 源）：

      ${PKG:-apt-get} install -y certbot python3-certbot-nginx
      certbot --nginx -d qianlink.top -d www.qianlink.top

  本机自检（不依赖域名）：

      curl -I -H 'Host: qianlink.top' http://127.0.0.1/

EOF
