# 使用当前 BuildKit 内置的 Dockerfile 前端，避免每次构建都依赖外部
# dockerfile:1.7 镜像源（镜像源短暂不可用时会让离线发布无法开始）。

# =============================================================================
# 构建阶段
# --platform=$BUILDPLATFORM 让编译在构建机的原生架构下进行（Apple Silicon 上就是
# arm64）。jar 与平台无关，所以从 Mac 交叉构建 linux/amd64 镜像时不需要 QEMU 模拟，
# 全速编译。
# =============================================================================
FROM --platform=$BUILDPLATFORM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build

ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public

WORKDIR /build

# mirrorOf 只能写 central：pom.xml 里的 osgeo 和 matsim 仓库在阿里云没有镜像，
# 写成 * 会把它们也重定向过去，构建直接失败。
RUN mkdir -p /root/.m2 \
 && printf '<settings><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>\n' \
    "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml

# 依赖预热：只要 pom.xml 没变这一层就命中缓存，改 Java 代码不必重下 MATSim/GeoTools。
# cache mount 挂在 repository 子目录，避免盖掉上面写的 settings.xml。
# go-offline 对含 profile 的复杂 pom 偶有误报，这里仅作预热，失败不阻断真正的 package。
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -q dependency:go-offline -DskipTests || true

COPY backend/src ./src
COPY scripts/build_real_passenger_flow_data.py /scripts/build_real_passenger_flow_data.py
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -DskipTests clean package

# 固定产物名，后续阶段不受 pom 版本号变动影响
RUN cp target/gjcxfzksh-*.jar /build/app.jar


# =============================================================================
# 运行阶段
# =============================================================================
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy AS runtime

ARG APP_UID=10001
ARG APP_GID=10001

# pigz：后端读大 events.xml.gz 时会 fork 它做多线程解压（FastEventReader），
#       没有会退回单线程 Java 解压，慢数倍。
# curl：容器健康检查。
# tzdata：时区数据。
RUN apt-get update \
 && apt-get install -y --no-install-recommends pigz curl tzdata python3 python3-gdal \
 && rm -rf /var/lib/apt/lists/*

# 中文路径的命门：sun.jnu.encoding（文件名编码）跟随系统 locale，
# LANG 不是 UTF-8 时 Java 打不开 "广州市/真实数据" 这类目录，报 NoSuchFileException。
# C.UTF-8 在 glibc 镜像里开箱可用，不需要 locale-gen。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai

RUN groupadd -g ${APP_GID} app \
 && useradd -u ${APP_UID} -g ${APP_GID} -M -s /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=${APP_UID}:${APP_GID} /build/app.jar /app/app.jar

# log4j2.xml 里 LOG_HOME=logs，相对工作目录，即 /app/logs
RUN mkdir -p /app/logs && chown ${APP_UID}:${APP_GID} /app/logs

USER app

# 常驻 API 采用低软上限；首次缓存计算由一次性 Builder JVM 使用独立 Xmx。
ENV JAVA_OPTS="-Xms128m -Xmx2g -Xss768k -XX:SoftMaxHeapSize=768m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=300000 -XX:G1PeriodicGCSystemLoadThreshold=0 -XX:+ExplicitGCInvokesConcurrent -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m"

EXPOSE 8090

# 本项目没有 actuator，使用无需登录的 OpenAPI 文档端点检查 Spring MVC 是否就绪。
# 不要使用 /pt/real-data/areaList：它受登录拦截器保护，未携带会话时返回 401，
# 会把已经正常启动的后端误判为 unhealthy。
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8090/v3/api-docs -o /dev/null || exit 1

# 必须是 java -jar。线网优化模块 fork MATSim 子进程时，靠 java.class.path 是否为
# 单个 .jar 来判断自己是不是 Spring Boot fat jar（RunJobManager#buildCommand），
# 据此决定用 PropertiesLauncher 还是裸 -cp 启动。改成分层解压或 -cp 启动会让
# 子进程找不到 BOOT-INF/lib 下的依赖，线网优化直接跑不起来。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
