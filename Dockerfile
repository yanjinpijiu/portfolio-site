# 多阶段构建：Node 打前端 → Maven 打后端 → 只把 jar 和静态产物带进运行镜像
#
# 构建：docker compose build   或   docker build -t portfolio-site .
# 运行：docker compose up -d   （配置见 docker-compose.yml / .env.example）

# ---------------------------------------------------------------- 1. 前端
FROM node:20-alpine AS frontend
WORKDIR /build/frontend
# 先只拷依赖清单：改业务代码时这一层还能命中缓存，不用重装依赖
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------------------------------------------------------------- 2. 后端
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B clean package -DskipTests

# ---------------------------------------------------------------- 3. 运行
FROM eclipse-temurin:21-jre
WORKDIR /app

# 时区：统计按「入库时算好的日期/小时」聚合，容器默认 UTC 的话，
# 中国时间晚上 8 点以后的访问会被算到第二天
ENV TZ=Asia/Shanghai

# curl 只给 compose 的健康检查用（镜像本身不含），装完清掉 apt 列表
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=backend /build/target/portfolio-1.0.0.jar /app/app.jar
COPY --from=frontend /build/frontend/dist /app/static

# 数据（H2 库 + 上传的图片与简历）和每日备份都落在挂载卷里，容器重建不丢
VOLUME ["/app/data", "/app/backups"]

# 后端自己发前端静态文件（容器里没有 nginx）；web-root 指向同一目录，
# 静态/动态内容模式的切换要往这里写 snapshot.json
ENV SERVER_PORT=8080 \
    APP_STORAGE_TYPE=local \
    APP_STATIC_DIR=/app/static \
    APP_WEB_ROOT=/app/static

EXPOSE 8080

# 堆开得偏小：个人站流量很小，小内存机器上也能跑；机器宽裕可以调大
ENTRYPOINT ["java", "-Xms96m", "-Xmx384m", "-XX:MaxMetaspaceSize=160m", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
