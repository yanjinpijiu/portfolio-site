#!/usr/bin/env bash
#
# 本地执行：构建前后端 → 上传 → 重启 → 自检。
# 在 Git Bash 里跑（Windows 上直接双击不行，得用 Git Bash）：
#
#   SERVER=root@你的服务器IP bash deploy/deploy.sh
#
# 换服务器：SERVER=root@1.2.3.4 bash deploy/deploy.sh
# 换端口：  APP_PORT=9000 SERVER=... bash deploy/deploy.sh
#
# 首次部署前，先在这台服务器上跑过一次 deploy/setup-server.sh。

set -euo pipefail

SERVER="${SERVER:-}"
if [ -z "$SERVER" ]; then
    echo "请先指定服务器地址：SERVER=root@你的服务器IP bash deploy/deploy.sh" >&2
    exit 1
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_NAME=portfolio-1.0.0.jar

# 后端端口，只用于最后那几行自检。必须和 setup-server.sh 跑的时候一致
APP_PORT="${APP_PORT:-8080}"

# 自检时用哪个域名去问 nginx（只发 Host 头，不依赖 DNS）。没配域名就先留 localhost
SITE_DOMAIN="${SITE_DOMAIN:-localhost}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m[错误] %s\033[0m\n' "$*" >&2; exit 1; }

cd "$ROOT"
say "目标服务器：${SERVER}"

# -------------------------------------------------------------- 1. 构建后端
say "[1/4] 构建后端"
command -v mvn >/dev/null 2>&1 || die "找不到 mvn，请先配好 Maven"
( cd backend && mvn -q clean package -DskipTests )
[ -f "backend/target/${JAR_NAME}" ] || die "后端构建产物不存在：backend/target/${JAR_NAME}"

# 确认密钥没有被夹带进构建产物。
# 关键是 resources 目录下不能有 application-local.yml —— 放这儿一定会被打进 jar。
if [ -f backend/src/main/resources/application-local.yml ]; then
    die "backend/src/main/resources/application-local.yml 存在，它会被打进 jar。"$'\n'"密钥文件应该放在 backend/application-local.yml（jar 外面）。"
fi
# 再用 unzip 直接翻一遍 jar 里的 application.yml（没装 unzip 就跳过，不阻塞部署）
if command -v unzip >/dev/null 2>&1; then
    if unzip -p "backend/target/${JAR_NAME}" BOOT-INF/classes/application.yml 2>/dev/null | grep -q 'admin-key'; then
        die "jar 里的 application.yml 含有 admin-key 配置，不该出现"
    fi
    if unzip -l "backend/target/${JAR_NAME}" 2>/dev/null | grep -q 'application-local'; then
        die "jar 里出现了 application-local.yml，密钥会随制品外泄"
    fi
fi
say "后端构建完成（已确认产物不含密钥）"

# -------------------------------------------------------------- 2. 构建前端
say "[2/4] 构建前端"
command -v npm >/dev/null 2>&1 || die "找不到 npm"
( cd frontend && npm run build )
[ -f frontend/dist/index.html ] || die "前端构建产物不存在：frontend/dist/index.html"

# ---------------------------------------------------------------- 3. 上传
say "[3/4] 上传到 ${SERVER}"
ssh "$SERVER" 'mkdir -p /opt/portfolio/data/files /var/www/portfolio'

scp -q "backend/target/${JAR_NAME}" "${SERVER}:/opt/portfolio/${JAR_NAME}"
say "后端 jar 已上传"

# 用 tar over ssh 而不是 rsync：Git Bash 里不一定有 rsync
tar -C frontend/dist -czf - . \
    | ssh "$SERVER" 'rm -rf /var/www/portfolio/* && tar -xzf - -C /var/www/portfolio'
say "前端静态文件已上传"

# ------------------------------------------------------------ 4. 重启自检
say "[4/4] 重启后端并自检"
ssh "$SERVER" '
    set -e
    if ! command -v portfolio-reload >/dev/null 2>&1; then
        echo "!! 服务器上找不到 portfolio-reload，先跑一次 deploy/setup-server.sh"
        exit 1
    fi
    portfolio-reload

    echo -n "  后端直连 /api/projects -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:'"$APP_PORT"'/api/projects

    # 只发 Host 头，不依赖 DNS 和证书；域名还没配好时用 localhost 也能测通
    echo -n "  nginx 首页             -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -H "Host: '"$SITE_DOMAIN"'" http://127.0.0.1/
    echo -n "  nginx 转发 /api        -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -H "Host: '"$SITE_DOMAIN"'" http://127.0.0.1/api/projects

    # index.html 里有没有那行标记 = 前台当前是不是静态模式。
    # 部署会覆盖 index.html，后端启动时按数据库里记的模式重新插回去（SiteModeInitializer）
    if grep -q "__SITE_MODE__" /var/www/portfolio/index.html 2>/dev/null; then
        echo "  前台内容模式           -> 静态（读预生成的快照）"
    else
        echo "  前台内容模式           -> 动态（实时查库）"
    fi
'

say "部署完成"
echo "  打开 http://${SITE_DOMAIN} 看看（DNS 没生效前可以先用 IP）"
echo "  看后端日志：  ssh ${SERVER} 'journalctl -u portfolio -f'"
