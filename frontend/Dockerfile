# 使用当前 BuildKit 内置的 Dockerfile 前端，避免每次构建都依赖外部
# dockerfile:1.7 镜像源（镜像源短暂不可用时会让离线发布无法开始）。

# =============================================================================
# 构建阶段
# 同后端：在构建机原生架构下跑 Vite，产物是纯静态文件，与目标平台无关，
# 因此从 Apple Silicon 交叉构建 linux/amd64 镜像不需要 QEMU 模拟。
# =============================================================================
FROM --platform=$BUILDPLATFORM docker.m.daocloud.io/library/node:20-alpine AS build

ARG NPM_REGISTRY=https://registry.npmmirror.com

WORKDIR /build

# 依赖层单独缓存：package.json / package-lock.json 没变就不重装 542MB 依赖
COPY package.json package-lock.json ./
RUN npm config set registry "$NPM_REGISTRY" \
 && npm ci --no-audit --no-fund

COPY . .

# VITE_APP_BASE_API 置空，让 axios 的 baseURL 落到运行时的
# window.APP_CONFIG.apiBaseUrl（utils/request.js:84），
# 这样同一个镜像换 IP / 换域名不需要重新构建。
RUN VITE_APP_BASE_API="" npm run build


# =============================================================================
# 运行阶段：Nginx 既托管静态资源，也反代后端，是整套系统唯一的对外入口
# =============================================================================
FROM docker.m.daocloud.io/library/nginx:1.27-alpine AS runtime

ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata curl

# dist 里已经包含 public/ 下的 models/vehicles/*.glb 和 city-buildings/ 三维瓦片，
# 不需要额外挂载（deploy.sh 里的软链只在使用外部资源目录时才需要）。
COPY --from=build /build/gjcxfzksh_web_dist /usr/share/nginx/html

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

# 官方 nginx 镜像启动时会依次执行 /docker-entrypoint.d/*.sh，
# 用它在容器启动时按环境变量生成 runtime-config.js，无需覆盖 ENTRYPOINT。
COPY docker/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-config.sh

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1/index.html -o /dev/null || exit 1
