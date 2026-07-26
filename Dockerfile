# ===== 多阶段构建 =====

# 阶段1: 构建前端
FROM node:20-alpine AS frontend-build

WORKDIR /app/vue-code

# 设置 npm 镜像源
RUN npm config set registry https://registry.npmmirror.com

# 先复制依赖文件，利用缓存
COPY vue-code/package.json vue-code/package-lock.json ./
RUN npm ci

# 复制前端源码并构建
COPY vue-code/ ./
RUN npm run build:spring

# 阶段2: 构建后端 JAR
FROM eclipse-temurin:21-jdk-alpine AS backend-build

WORKDIR /app

# 配置阿里云 Maven 镜像
RUN mkdir -p /root/.m2 && echo '<?xml version="1.0" encoding="UTF-8"?><settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd"><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>Aliyun Maven</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 先复制 Maven 配置和 pom.xml，利用缓存
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# 复制前端构建产物到 static 目录
COPY --from=frontend-build /app/vue-code/../src/main/resources/static src/main/resources/static/

# 复制后端源码
COPY src/ src/

# 构建 JAR（跳过测试）
RUN ./mvnw clean package -DskipTests

# 阶段3: 构建 Playwright Chromium 浏览器二进制（glibc 版，版本与 pom 中 playwright 1.40.0 对齐）
# 注意：必须在 glibc 系统（Debian/Ubuntu）上构建，Alpine(musl) 上的 Chromium 无法运行。
FROM node:20-bookworm AS playwright-browser

ENV PLAYWRIGHT_BROWSERS_PATH=/app/ms-playwright
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g playwright@1.40.0 \
    && npx playwright install chromium

# 阶段4: 运行时镜像
# 使用 Ubuntu(jammy) 的 glibc 基础镜像，Playwright Chromium 才能正常运行（Alpine 的 musl 不兼容）
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="IAMLZY"
LABEL description="XianYuAssistant - 闲鱼自动化管理系统（含 Playwright Chromium，支持自动过滑块）"

WORKDIR /app

# 安装 Playwright Chromium 运行所需的系统库（glibc 版）及中文字体（滑块页面渲染需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
        libdbus-1-3 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2 libatspi2.0-0 \
        libxshmfence1 libx11-6 libxcb1 libxext6 libxrender1 libxi6 libxinerama1 \
        libxcursor1 libxtst6 libwoff1 libvulkan1 \
        fonts-liberation fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户运行，避免 Chromium 以 root 启动需要 --no-sandbox
RUN groupadd -r appgroup && useradd -r -g appgroup -u 1000 appuser \
    && mkdir -p /app/dbdata /app/logs /app/ms-playwright \
    && chown -R appuser:appgroup /app

# 从构建阶段复制 JAR
COPY --from=backend-build --chown=appuser:appgroup /app/target/XianYuAssistant-2.0.3.jar app.jar

# 复制预构建的 Chromium 浏览器二进制到 PlaywrightManager 期望的 /app/ms-playwright
COPY --from=playwright-browser --chown=appuser:appgroup /app/ms-playwright /app/ms-playwright

# 暴露端口
EXPOSE 12400

# 环境变量
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV SERVER_PORT=12400
ENV ALI_API_KEY=""
# 明确指向镜像内已装好的浏览器目录，与 PlaywrightManager 默认查找路径一致
ENV PLAYWRIGHT_BROWSERS_PATH=/app/ms-playwright

# 以非 root 用户运行（Chromium 安全性 & 避免 --no-sandbox）
USER appuser

# 启动命令
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT} -jar app.jar"]
