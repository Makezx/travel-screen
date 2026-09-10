# syntax=docker/dockerfile:1
# 多阶段构建：Maven 构建 -> 精简 JRE 运行
# 构建产物：target/travel-screen.jar（由 pom.xml 的 <finalName> 决定）

# ---------- Stage 1: 构建 ----------
# --platform=$BUILDPLATFORM：构建阶段固定跑在"原生"平台（CI 上即 amd64），
# 不跟随目标平台。产物是纯字节码 jar，与架构无关，可以原样拷进任意平台的运行阶段。
#
# 不写这一行的后果：多架构构建时 arm64 那一路会在 QEMU 模拟下把整个 Maven 构建
# 再跑一遍，耗时从 1~2 分钟膨胀到十几分钟，且容易触发超时和随机失败。
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先只复制 pom.xml 解析依赖，最大化利用 Docker 层缓存（源码变动不会重新下载依赖）
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

# 再复制源码打包
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package

# ---------- Stage 2: 运行 ----------
FROM eclipse-temurin:21-jre

# 时区：影响行程日期与日志时间戳
ENV TZ=Asia/Shanghai

# 安装 tzdata（时区数据）与 curl（供 healthcheck 使用）
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 用户运行（uid/gid 10001；避开基础镜像里已存在的 1000）
RUN groupadd --gid 10001 appuser \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin appuser

WORKDIR /app

# 从构建阶段拷贝可执行 jar（finalName = travel-screen）
COPY --from=build /build/target/travel-screen.jar /app/travel-screen.jar

# 持久化目录：
#   /app/data   -> H2 数据库文件 traveldb.mv.db、账号文件 travel-auth.json、密钥 travel-secret.key
#   /app/photos -> 上传的照片（PHOTO_DIR）
# 两个目录在镜像内先创建并赋予 appuser 权限；具名卷首次挂载时会继承该权限。
RUN mkdir -p /app/data /app/photos \
    && chown -R appuser:appuser /app

USER appuser

# 数据目录 / 照片目录挂载点（docker-compose.yml 中分别挂载具名卷）
VOLUME ["/app/data", "/app/photos"]

EXPOSE 8388

# 默认 JVM 参数：容器感知（Java 21 默认已启用），可用环境变量 JAVA_OPTS 覆盖
ENV JAVA_OPTS=""

# 应用运行的默认路径配置（可被 docker-compose / docker run -e 覆盖）
ENV SPRING_PROFILES_ACTIVE="local" \
    SPRING_DATASOURCE_URL="jdbc:h2:file:/app/data/traveldb;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE" \
    AUTH_FILE="/app/data/travel-auth.json" \
    SECRET_FILE="/app/data/travel-secret.key" \
    PHOTO_DIR="/app/photos"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8388/ >/dev/null || exit 1

# exec 形式启动，支持通过 JAVA_OPTS 追加 JVM 参数（如 -Xmx512m）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/travel-screen.jar"]
