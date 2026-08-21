# 新公交平台：Docker 镜像文件部署手册

本文采用“Mac 从源码编译 → 导出 Docker 镜像文件 → 上传服务器 → 加载镜像 → 启动容器”的部署方式。

不使用阿里云 ACR、Docker Hub 或其他业务镜像仓库，也不把项目源码上传服务器。

```text
Mac 上只有源码
  ↓ Docker 内部 Maven/Node 编译
后端 app.jar + 前端静态文件
  ↓ 封装成 linux/amd64 运行镜像
前端镜像 + 后端镜像
  ↓ docker image save + gzip
一个版本化 .tar.gz 镜像包
  ↓ rsync 上传服务器
服务器 docker image load
  ↓ docker-compose up -d --no-build
浏览器访问 http://8.134.88.9:8090
```

---

# 1. 已核实的服务器实际情况

本手册已经按目标服务器的实际状态调整：

| 项目 | 实际值 |
|---|---|
| SSH 地址 | `root@8.134.88.9` |
| 系统 | Alibaba Cloud Linux 3（OpenAnolis Edition） |
| CPU 架构 | `x86_64`，对应 Docker 的 `linux/amd64` |
| 服务器默认 Shell | `/bin/bash` |
| 服务器 fish | 未安装 |
| Docker | 26.1.4 |
| Compose 命令 | `docker-compose` 2.27.1 |
| `docker compose` | 不可用，禁止使用 |
| 平台部署目录 | `/test/pt`，目录已存在 |
| 数据盘 | `/test`，总计约 98 GB，当前约 91 GB 可用 |
| 根盘 | 总计约 79 GB，当前约 14 GB 可用 |
| Docker 数据目录 | `/var/lib/docker`，位于根盘 |
| 内存 | 约 14 GiB，检查时可用约 6.4 GiB |
| 平台公网端口 | `8090`，检查时未占用 |

服务器还在运行 Dify、GeoServer 和其他 Java 服务。不要为了部署本平台随意停止或删除这些现有服务。

## 1.1 关于 fish 和 Bash

本文默认命令语法是 fish：

- 所有在 Mac 上执行的命令都使用 `fish` 代码块和 fish 变量语法。
- 服务器实际没有安装 fish，且 root 默认 Shell 是 Bash。
- 登录服务器后执行的命令会明确标为 `bash`。

这不是混用错误，而是严格匹配两台机器的实际 Shell。

## 1.2 密码安全

SSH 命令只写：

```fish
ssh root@8.134.88.9
```

看到 `password:` 后再交互输入密码。不要把密码写入：

- Markdown 文档；
- Shell 脚本；
- Dockerfile；
- `.env`；
- Git 仓库；
- `sshpass` 命令参数。

密码已经在聊天中出现过，正式部署完成后建议修改 root 密码并配置 SSH 密钥登录。

## 1.3 当前服务器容量的重要限制

当前服务器不是平台原设计的 32 GB 独占服务器：

- 总内存约 14 GiB；
- 已有服务占用了较多内存；
- `/test` 当前约 91 GB 可用；
- 根盘只有约 14 GB 可用，Docker 镜像加载后会继续占用根盘。

因此本文首次启动默认使用“轻量验证配置”：

```text
BACKEND_HEAP=3g
OPTIMIZATION_RUNNER_XMX=1g
BACKEND_MEM_LIMIT=5g
```

该配置适合验证部署、登录、较小数据和基本页面，不保证广州模型 V6、大缓存构建或线网优化稳定运行。

要完整运行大模型和线网优化，建议至少：

| 资源 | 建议 |
|---|---|
| 内存 | 32 GB 或以上 |
| `/test` 数据盘 | 200 GB 或以上 |
| 根盘/Docker 空间 | 至少再预留 20 GB |

现有 `pt_data` 和 `pt_cache` 如果仍分别约 43 GB 和 44 GB，两者加上建筑物数据、镜像包及缓存增长会超过当前 91 GB 可用空间。完整迁移前应先扩容 `/test`，或者只上传测试所需的数据子集。

---

# 2. 最终服务器目录

```text
/test/pt/
├── docker-compose.yml
├── .env
├── images/
│   ├── gjcxfzksh-images-20260803-153012.tar.gz
│   └── gjcxfzksh-images-20260803-153012.tar.gz.sha256
├── logs/
│   └── backend/
└── data/
    ├── pt_data/
    ├── pt_cache/
    └── geo/
        └── buildings/
```

服务器不会保存：

```text
/test/pt/backend
/test/pt/frontend
/test/pt/.git
```

说明：

- `docker-compose.yml` 是运行编排配置，不是业务源码。
- `.env` 保存服务器路径、端口、内存和镜像版本。
- `images` 保存已经编译好的 Docker 镜像压缩包。
- `data` 保存业务数据，升级镜像不会覆盖它。
- Docker 加载后的镜像层仍位于服务器 `/var/lib/docker`。

---

# 3. 第一次部署：环境检查

## 步骤 1：启动 Mac Docker Desktop

**执行位置：Mac，fish**

打开 Docker Desktop，等待 Docker 图标显示运行正常，然后执行：

```fish
docker version
docker buildx version
```

成功标准：

- `docker version` 同时显示 Client 和 Server；
- `docker buildx version` 显示版本号；
- 不出现 `Cannot connect to the Docker daemon`。

## 步骤 2：测试 SSH

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9
```

看到密码提示后交互输入密码。登录成功后执行：

```bash
uname -m
cat /etc/os-release | head
docker version
docker-compose version
```

确认：

```text
x86_64
Docker 26.1.4（或后续兼容版本）
Docker Compose version v2.27.1（或后续兼容版本）
```

不要使用下面这条，因为服务器实际不支持：

```text
docker compose
```

退出服务器：

```bash
exit
```

## 步骤 3：重新检查服务器资源和 8090 端口

资源会变化，每次正式部署前都重新检查。

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9 'free -h; df -h / /test; ss -lntp | grep ":8090 " || true'
```

判断：

- 8090 没有输出才表示端口空闲；
- `/test` 必须能放下计划上传的数据和镜像压缩包；
- 根盘要给 Docker 新镜像留出空间；
- 可用内存低于约 5 GiB 时不要启动本平台。

如果端口被占用，不要杀死未知进程，应先确认占用者并重新选择端口。

## 步骤 4：创建服务器目录

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9 '
mkdir -p /test/pt/images
mkdir -p /test/pt/logs/backend
mkdir -p /test/pt/data/pt_data
mkdir -p /test/pt/data/pt_cache
mkdir -p /test/pt/data/geo/buildings
'
```

检查：

```fish
ssh root@8.134.88.9 'find /test/pt -maxdepth 3 -type d | sort'
```

这里只创建普通目录，不格式化或重新挂载服务器磁盘。

## 步骤 5：确认服务器上传工具

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9 'command -v rsync; command -v gzip; command -v sha256sum'
```

如果 `rsync` 没有输出，服务器是 Alibaba Cloud Linux 3，可以执行：

```fish
ssh root@8.134.88.9 'dnf install -y rsync gzip coreutils'
```

---

# 4. 步骤 6：只有源码时，完整编译出关键文件和镜像

这一节从“没有任何预编译文件，只有源码”开始。

不要求 Mac 预先安装 Java、Maven、Node.js 或 npm，因为这些构建工具已经写在多阶段 Dockerfile 中：

- `backend/Dockerfile` 的构建阶段使用 Maven 3.9 + JDK 21；
- `frontend/Dockerfile` 的构建阶段使用 Node 20；
- 后端最终阶段只复制 `/build/app.jar`；
- 前端最终阶段只复制 `gjcxfzksh_web_dist`。

本项目的 `.dockerignore` 还会排除已有的 `target`、`node_modules` 和前端旧构建目录，因此构建结果来自当前源码，而不是误用本地旧产物。

## 步骤 6.1：进入源码根目录

**执行位置：Mac，fish**

```fish
cd "/Users/a../模型算法/新公交平台"
pwd
```

必须输出：

```text
/Users/a../模型算法/新公交平台
```

## 步骤 6.2：确认纯源码构建所需文件

**执行位置：Mac，fish**

检查后端：

```fish
test -f backend/pom.xml; and echo "正确：找到 backend/pom.xml"
test -d backend/src/main; and echo "正确：找到后端源码"
test -f backend/Dockerfile; and echo "正确：找到后端 Dockerfile"
```

检查前端：

```fish
test -f frontend/package.json; and echo "正确：找到 package.json"
test -f frontend/package-lock.json; and echo "正确：找到 package-lock.json"
test -d frontend/src; and echo "正确：找到前端源码"
test -f frontend/Dockerfile; and echo "正确：找到前端 Dockerfile"
```

检查运行配置：

```fish
test -f frontend/docker/nginx.conf; and echo "正确：找到 Nginx 配置"
test -f frontend/docker/40-runtime-config.sh; and echo "正确：找到运行时配置脚本"
test -f docker-compose.yml; and echo "正确：找到 Compose 文件"
```

任何一项没有输出都应先解决，不能继续构建。

## 步骤 6.3：生成本次唯一版本号

**执行位置：Mac，fish**

```fish
set -gx IMAGE_TAG (date +%Y%m%d-%H%M%S)
set -gx IMAGE_PREFIX gjcxfzksh
set -gx IMAGE_ARCHIVE "gjcxfzksh-images-$IMAGE_TAG.tar.gz"
```

检查：

```fish
printf 'IMAGE_TAG=%s\nIMAGE_PREFIX=%s\nIMAGE_ARCHIVE=%s\n' \
  "$IMAGE_TAG" \
  "$IMAGE_PREFIX" \
  "$IMAGE_ARCHIVE"
```

示例：

```text
IMAGE_TAG=20260803-153012
IMAGE_PREFIX=gjcxfzksh
IMAGE_ARCHIVE=gjcxfzksh-images-20260803-153012.tar.gz
```

把 `IMAGE_TAG` 记下来，服务器必须使用同一个版本。

## 步骤 6.4：创建 Buildx 构建器

**执行位置：Mac，fish**

```fish
docker buildx inspect gjcxfzksh-builder >/dev/null 2>&1
or docker buildx create \
  --name gjcxfzksh-builder \
  --driver docker-container
```

选择并启动：

```fish
docker buildx use gjcxfzksh-builder
docker buildx inspect --bootstrap
```

输出的 Platforms 必须包含 `linux/amd64`。

## 步骤 6.5：从 Java 源码编译后端镜像

**执行位置：Mac，fish**

```fish
docker buildx build \
  --platform linux/amd64 \
  --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public \
  --tag "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG" \
  --load \
  ./backend
```

Docker 在内部依次执行：

1. 读取 `backend/pom.xml`；
2. 下载 Maven 依赖；
3. 复制 `backend/src` 到临时 Maven 构建阶段；
4. 执行 `mvn -B -DskipTests clean package`；
5. 从 `target` 中找到 `gjcxfzksh-*.jar`；
6. 复制并固定为 `/build/app.jar`；
7. 创建 JRE 21 最终运行镜像；
8. 最终镜像只复制 `/app/app.jar`，不复制 `src`；
9. `--load` 把最终 `linux/amd64` 镜像加载到 Mac Docker。

第一次需要下载大量依赖，可能需要 10～30 分钟。成功时最后没有 `ERROR`。

## 步骤 6.6：从 Vue 源码编译前端镜像

**执行位置：Mac，fish**

```fish
docker buildx build \
  --platform linux/amd64 \
  --build-arg NPM_REGISTRY=https://registry.npmmirror.com \
  --tag "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG" \
  --load \
  ./frontend
```

Docker 在内部依次执行：

1. 读取 `package.json` 和 `package-lock.json`；
2. 在临时 Node 20 阶段执行 `npm ci`；
3. 复制 `frontend/src` 和其他前端资源；
4. 执行 `VITE_APP_BASE_API="" npm run build`；
5. 生成 `frontend/gjcxfzksh_web_dist` 对应的编译内容；
6. 创建 Nginx 最终运行镜像；
7. 只把编译后的 HTML、CSS、JavaScript 和静态资源复制到 Nginx；
8. 不把 `src`、Node.js、npm 或 `node_modules` 复制到最终镜像；
9. `--load` 把最终 `linux/amd64` 镜像加载到 Mac Docker。

## 步骤 6.7：检查镜像存在且架构正确

**执行位置：Mac，fish**

```fish
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}' \
  | grep gjcxfzksh
```

应看到同一个版本号的两个镜像：

```text
gjcxfzksh/gjcxfzksh-backend
gjcxfzksh/gjcxfzksh-web
```

检查平台：

```fish
docker image inspect \
  --format '{{.Os}}/{{.Architecture}}' \
  "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG"

docker image inspect \
  --format '{{.Os}}/{{.Architecture}}' \
  "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG"
```

两个命令都必须输出：

```text
linux/amd64
```

## 步骤 6.8：从最终镜像提取关键编译产物进行验证

这一步不是服务器部署所必需，但能证明从源码确实生成了关键文件。

**执行位置：Mac，fish**

创建本地检查目录：

```fish
mkdir -p build-artifacts/backend
mkdir -p build-artifacts/frontend
```

从后端最终镜像提取 `app.jar`：

```fish
set BACKEND_CONTAINER (docker create \
  --platform linux/amd64 \
  "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG")

docker cp "$BACKEND_CONTAINER":/app/app.jar \
  build-artifacts/backend/app.jar

docker rm "$BACKEND_CONTAINER"
set -e BACKEND_CONTAINER
```

从前端最终镜像提取已编译静态文件：

```fish
set WEB_CONTAINER (docker create \
  --platform linux/amd64 \
  "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG")

docker cp "$WEB_CONTAINER":/usr/share/nginx/html/. \
  build-artifacts/frontend/

docker rm "$WEB_CONTAINER"
set -e WEB_CONTAINER
```

检查后端关键文件：

```fish
ls -lh build-artifacts/backend/app.jar
unzip -l build-artifacts/backend/app.jar | head -30
```

检查前端关键文件：

```fish
ls -lh build-artifacts/frontend/index.html
find build-artifacts/frontend/assets -maxdepth 1 -type f | head -30
```

成功标准：

- `build-artifacts/backend/app.jar` 存在；
- 前端 `index.html` 存在；
- 前端 `assets` 中有带内容哈希的 `.js` 和 `.css` 文件；
- 不需要本地预先存在 `backend/target` 或 `frontend/gjcxfzksh_web_dist`。

`build-artifacts/` 和后面的 `docker-images/` 已加入 `.gitignore`，避免误提交编译文件和大镜像包。

---

# 5. 导出、压缩并上传镜像

## 步骤 7：把两个镜像导出成一个文件

**执行位置：Mac，fish**

```fish
mkdir -p docker-images
```

导出：

```fish
docker image save \
  --output "docker-images/gjcxfzksh-images-$IMAGE_TAG.tar" \
  "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG" \
  "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG"
```

检查：

```fish
ls -lh "docker-images/gjcxfzksh-images-$IMAGE_TAG.tar"
```

## 步骤 8：压缩并生成校验文件

**执行位置：Mac，fish**

```fish
gzip -9 "docker-images/gjcxfzksh-images-$IMAGE_TAG.tar"
```

```fish
pushd docker-images
shasum -a 256 "$IMAGE_ARCHIVE" | tee "$IMAGE_ARCHIVE.sha256"
wc -c "$IMAGE_ARCHIVE.sha256"
shasum -a 256 -c "$IMAGE_ARCHIVE.sha256"
popd
```

最后一个命令必须显示 `OK`。`.sha256` 是一行纯文本，通常只有约 100 字节；
如果它与 `.tar.gz` 一样大，说明校验文件被误写成了镜像压缩包，不能上传。

检查：

```fish
ls -lh \
  "docker-images/$IMAGE_ARCHIVE" \
  "docker-images/$IMAGE_ARCHIVE.sha256"
```

## 步骤 9：上传 Compose 和镜像包

**执行位置：Mac，fish**

上传 Compose：

```fish
scp docker-compose.yml \
  root@8.134.88.9:/test/pt/docker-compose.yml
```

上传镜像包，`-P` 支持断点续传：

```fish
rsync -avP \
  "docker-images/$IMAGE_ARCHIVE" \
  "docker-images/$IMAGE_ARCHIVE.sha256" \
  root@8.134.88.9:/test/pt/images/
```

这一步不会上传 `backend`、`frontend` 或 `.git`。

---

# 6. 业务数据迁移

## 步骤 10：先估算本地数据大小

**执行位置：Mac，fish**

```fish
set -gx LOCAL_PT_DATA "/Volumes/USB DISK/pt_data"
set -gx LOCAL_PT_CACHE "/Volumes/USB DISK/pt_cache"
set -gx LOCAL_GEO "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2"
```

检查路径：

```fish
for data_path in "$LOCAL_PT_DATA" "$LOCAL_PT_CACHE" "$LOCAL_GEO"
  if test -d "$data_path"
    du -sh "$data_path"
  else
    echo "不存在：$data_path"
  end
end
```

当前服务器 `/test` 只有约 91 GB 可用。如果三者加上镜像包和至少 10～20 GB 运行余量放不下，不要完整上传，应先扩容数据盘或仅上传测试数据子集。

## 步骤 11：上传 pt_data

**执行位置：Mac，fish**

```fish
rsync -avP \
  --exclude '.DS_Store' \
  --exclude '._*' \
  "$LOCAL_PT_DATA/" \
  root@8.134.88.9:/test/pt/data/pt_data/
```

源路径末尾的 `/` 不能省略。

## 步骤 12：按容量决定是否上传 pt_cache

**执行位置：Mac，fish**

只有确认服务器空间足够时才执行：

```fish
rsync -avP \
  --exclude '.DS_Store' \
  --exclude '._*' \
  "$LOCAL_PT_CACHE/" \
  root@8.134.88.9:/test/pt/data/pt_cache/
```

空间不够时可以先不上传缓存，但平台重新构建缓存也同样需要足够的磁盘余量。

### 缓存完成后人工归档 events/plans（可选）

平台不会自动删除 MATSim 原始文件。当一个大模型（如 V6）在管理界面明确显示
整套缓存为 `已生成/ready` 后，管理员可根据磁盘容量人工归档该模型最终的
`events` 和 `plans` 文件。归档后：

- 已生成的轨迹、人口、出行、换乘、面板和评价缓存仍可正常使用；
- 服务重启后仍保留缓存生成时的大模型加载模式；
- 如果以后把原文件放回，内容指纹必须与缓存生成时一致，否则缓存会被正常判为过期。

归档前必须同时满足：

1. 管理界面缓存状态为 `ready`，不是 `building`、`queued` 或 `failed`；
2. `pt_cache/<区域>/<范围>/<模型>/manifest.json` 中的 `status` 为 `ready`；
3. 已经将原文件备份到其他磁盘或对象存储，并能在缓存损坏或版本升级需要重建时恢复。

仅归档模型 `output` 顶层被平台识别的最终 `events` 和 `plans`。以下文件仍必须
留在原位：

```text
config.xml 或 config_reduced.xml
network.xml(.gz)
transitSchedule.xml(.gz)
transitVehicles.xml(.gz)
desc.json
```

先只列出候选文件，不直接删除：

```bash
MODEL_OUTPUT='/test/pt/data/pt_data/广州/仿真数据/public/V6/output'
find "$MODEL_OUTPUT" -maxdepth 1 -type f \
  \( -iname '*events*' -o -iname '*plans*' \) -print
```

核对文件名、缓存状态和异地备份后，再由管理员手工移走或删除这两个文件。
删除后不要执行“重建缓存”；如果新版本要求新缓存版本，或任一缓存工件损坏，
必须先把对应的 `events/plans` 恢复到原 `output` 目录。

## 步骤 13：上传建筑物数据

**执行位置：Mac，fish**

```fish
rsync -avPz \
  "$LOCAL_GEO/" \
  root@8.134.88.9:/test/pt/data/geo/buildings/
```

要上传整个目录，因为 `.shp` 还需要 `.dbf`、`.shx` 和 `.prj` 等配套文件。

## 步骤 14：服务器检查数据和权限

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9 '
du -sh /test/pt/data/pt_data
du -sh /test/pt/data/pt_cache
du -sh /test/pt/data/geo
df -h /test /
'
```

设置后端容器 UID 10001 的写权限：

```fish
ssh root@8.134.88.9 '
chown -R 10001:10001 /test/pt/data/pt_data
chown -R 10001:10001 /test/pt/data/pt_cache
chown -R 10001:10001 /test/pt/logs/backend
chmod -R a+rX /test/pt/data/geo
'
```

> **为什么属主非改不可，以及漏了会怎样**
>
> rsync 会把 Mac 上的属主一起带过来（uid 501），目录权限又通常是 700。后端容器里的
> Java 进程是 uid 10001，于是整个数据根**既列不了目录也写不了文件**。
>
> 麻烦的是这个故障不报错，只是"什么都没有"：
> `File#listFiles` 返回 null → 模型列表空；`Files#exists` 返回 false → 用户表被当成
> 首次运行，静默起一个空的，登录一律失败（`保存用户数据失败`），而且第一次注册就会
> 把真实的 `.gjcxfzksh-users.json` 覆盖掉。2026-08-03 那次部署就是漏了这一步。
>
> 现在 `docker-compose.yml` 里的 `data-permissions` 初始化容器每次 `up` 都会自动做一遍
> 属主归一，上面的手工 chown 是双保险；后端启动时也会校验目录可读可写，不通过就直接
> 启动失败并在日志里说清怎么修，不会再静默跑起来。
>
> **但注意**：如果平台已经在跑，之后又 rsync 了新数据上去，新文件仍然是 uid 501。
> 必须重跑一次归一，否则新数据读不到：
>
> ```fish
> ssh root@8.134.88.9 'cd /test/pt; docker-compose --env-file .env up -d data-permissions'
> ```

---

# 7. 服务器加载镜像并启动

## 步骤 15：登录服务器并设置版本

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9
```

以下改为服务器 Bash。填写步骤 6.3 记录的真实版本：

```bash
export IMAGE_TAG="20260803-143906"
export IMAGE_ARCHIVE="gjcxfzksh-images-${IMAGE_TAG}.tar.gz"
```

示例：

```bash
export IMAGE_TAG="20260803-143906"
export IMAGE_ARCHIVE="gjcxfzksh-images-${IMAGE_TAG}.tar.gz"
```

## 步骤 16：校验镜像包

**执行位置：服务器，Bash**

```bash
cd /test/pt/images
sha256sum -c "${IMAGE_ARCHIVE}.sha256"
```

必须显示：

```text
gjcxfzksh-images-版本号.tar.gz: OK
```

如果是 `FAILED`，不要加载，回到 Mac 重新 rsync。

## 步骤 17：加载前后端镜像

**执行位置：服务器，Bash**

```bash
gzip -dc "/test/pt/images/${IMAGE_ARCHIVE}" | docker image load
```

成功时应显示：

```text
Loaded image: gjcxfzksh/gjcxfzksh-backend:版本号
Loaded image: gjcxfzksh/gjcxfzksh-web:版本号
```

检查：

```bash
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}' \
  | grep gjcxfzksh
```

## 步骤 18：生成当前服务器轻量 `.env`

**执行位置：服务器，Bash**

判断建筑物文件：

```bash
if test -f /test/pt/data/geo/buildings/Buildingguagnzhou84.shp; then
  export BUILDINGS_ENABLED=true
else
  export BUILDINGS_ENABLED=false
fi
```

生成配置：

```bash
cat > /test/pt/.env <<EOF
IMAGE_PREFIX=gjcxfzksh
IMAGE_TAG=${IMAGE_TAG}

PT_DATA_DIR=/test/pt/data/pt_data
PT_CACHE_DIR=/test/pt/data/pt_cache
GEO_DIR=/test/pt/data/geo

HTTP_BIND=0.0.0.0
HTTP_PORT=8090
APP_API_BASE_URL=/
CORS_ALLOWED_ORIGINS=http://8.134.88.9:8090

# 当前 14 GiB 且已有多项服务：仅用于轻量验证
BACKEND_HEAP=3g
OPTIMIZATION_RUNNER_XMX=1g
BACKEND_MEM_LIMIT=5g

MATSIM_CACHE_BUILD_THREADS=1
MATSIM_PROCESSING_THREADS=2
MATSIM_TRAJECTORY_BUILD_THREADS=1
OPTIMIZATION_RUN_CONCURRENCY=1
GJCXFZKSH_EVENTS_PIGZ_THREADS=2
UNDERTOW_WORKER_THREADS=32
UNDERTOW_IO_THREADS=4

MATSIM_LARGE_MODEL_THRESHOLD_BYTES=21474836480
MATSIM_LARGE_MODEL_PLANS_THRESHOLD_BYTES=8589934592
MATSIM_LARGE_MODEL_EVENTS_THRESHOLD_BYTES=8589934592
MATSIM_CACHE_MIN_FREE_BYTES=10737418240

APP_MAP_TILE_URL_TEMPLATE=
APP_MAP_BASEMAP_DEFAULT=esri-dark
APP_NETWORK_LINE_MIN_PIXELS=0.8
APP_NETWORK_LINE_SOFT_EDGE_PIXELS=0.75
APP_MAP_PIXEL_RATIO=
APP_MAP_DISPLAY_SCALE=

APP_CITY_BUILDINGS_SHP_PATH=/data/geo/buildings/Buildingguagnzhou84.shp
APP_CITY_BUILDINGS_ENABLED=${BUILDINGS_ENABLED}
APP_CITY_BUILDINGS_HEIGHT_FIELD=HEIGHT
APP_CITY_BUILDINGS_MAX_FEATURES=20000
EOF
```

> **`APP_CITY_BUILDINGS_ENABLED` 必须和建筑物 shp 的实际存在情况对齐**
>
> 上面的 `BUILDINGS_ENABLED` 是在生成 `.env` 的那一刻探测出来的。如果这一步跑在
> 步骤 13（上传建筑物数据）之前，或者当时上传还没跑完，就会固化成 `false` 并且
> 再也不会自己变回来 —— 2026-08-03 那次部署就是这样。
>
> 这个开关为 false 时，前端连 `CityBuildingsLayer` 都不会创建（见
> `MapLayout.vue` 里的 `if (buildingLayerConfig.enabled !== false)`），
> 所以无论怎么放大、怎么开 3D 都不可能出现建筑，且不报任何错。
>
> 补救只需改 `.env` 并重建 web 容器（不必重新编译镜像）：
>
> ```fish
> ssh root@8.134.88.9 'cd /test/pt
>   test -f data/geo/buildings/Buildingguagnzhou84.shp \
>     && sed -i "s/^APP_CITY_BUILDINGS_ENABLED=.*/APP_CITY_BUILDINGS_ENABLED=true/" .env \
>     && docker-compose --env-file .env up -d --no-build web
>   curl -s localhost:8090/runtime-config.js | grep cityBuildingsEnabled'
> ```
>
> 另外注意：建筑只在**3D 视角且 zoom ≥ 12** 时渲染，并且在
> "人口分布监测""出行分布监测"两个页签下会被刻意抑制（避免遮挡 3D 人口栅格，
> 见 `datavisualization/index.vue` 的 `syncDistributionBuildingSuppression`）。
> 验证时要换到别的页签，否则会误判成没修好。

检查端口和镜像版本：

```bash
grep -E '^(IMAGE_PREFIX|IMAGE_TAG|HTTP_PORT|BACKEND_HEAP|BACKEND_MEM_LIMIT)=' \
  /test/pt/.env
```

应该包含：

```text
HTTP_PORT=8090
CORS_ALLOWED_ORIGINS=http://8.134.88.9:8090
```

`CORS_ALLOWED_ORIGINS` 必须与浏览器地址完全一致，包含 `http://` 和 `:8090`。
浏览器提交登录、注册等 POST 请求时会携带 `Origin`；该值留空会让公网请求在
到达业务接口前被后端拒绝为 `403 Invalid CORS request`，前端显示“当前操作没有权限”。

升级到至少 32 GB 内存并确认其他服务占用后，才考虑改为完整配置：

```text
BACKEND_HEAP=16g
OPTIMIZATION_RUNNER_XMX=8g
BACKEND_MEM_LIMIT=28g
MATSIM_CACHE_BUILD_THREADS=2
MATSIM_PROCESSING_THREADS=0
GJCXFZKSH_EVENTS_PIGZ_THREADS=4
UNDERTOW_WORKER_THREADS=128
UNDERTOW_IO_THREADS=16
MATSIM_CACHE_MIN_FREE_BYTES=21474836480
```

## 步骤 19：验证 Compose 配置

**执行位置：服务器，Bash**

```bash
cd /test/pt
docker-compose config --images
```

必须看到：

```text
gjcxfzksh/gjcxfzksh-backend:本次版本号
gjcxfzksh/gjcxfzksh-web:本次版本号
```

服务器没有源码，因此禁止运行：

```text
docker-compose build
```

也不要执行：

```text
docker-compose pull
```

镜像已经通过 `docker image load` 导入。

## 步骤 20：启动平台

**执行位置：服务器，Bash**

启动前再次检查动态资源：

```bash
free -h
df -h / /test
ss -lntp | grep ':8090 ' || true
```

如果 8090 仍空闲且可用内存足够，启动：

```bash
cd /test/pt

# Compose 将本平台网桥固定命名为 br-gjcxfzksh。
# firewalld 正在运行时，必须允许这个网桥内的前端容器访问后端容器。
if systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --zone=docker --add-interface=br-gjcxfzksh
  firewall-cmd --reload
fi

docker-compose up -d --no-build
```

`--no-build` 表示只运行已加载的镜像，不在服务器编译源码。

上面的防火墙规则不是把后端 8090 直接暴露到公网，而是只允许本平台两个
Docker 容器在 `br-gjcxfzksh` 网桥内通信。缺少这条规则时，首页仍能打开，
但登录、注册等 `/pt/` 请求会由 Nginx 返回 `502 Bad Gateway`。

查看状态：

```bash
docker-compose ps
```

backend 初始可能显示 `health: starting`。等待几分钟后重新检查，最终 backend 和 web 都应为 `healthy`。

当前 Compose 和后端镜像使用无需登录的 `/v3/api-docs` 做健康检查。不要改回
`POST /pt/real-data/areaList`；该业务接口需要登录会话，否则会返回 401，造成后端
已经正常启动却被 Docker 标记为 `unhealthy`。

## 步骤 21：查看日志和内部测试

**执行位置：服务器，Bash**

```bash
cd /test/pt
docker-compose logs --tail=200 backend
docker-compose logs --tail=100 web
```

测试首页：

```bash
curl -I http://127.0.0.1:8090/
```

测试完整链路：

```bash
curl -sS -X POST \
  http://127.0.0.1:8090/pt/real-data/areaList
```

查看资源：

```bash
docker stats --no-stream
free -h
df -h / /test
```

如果系统可用内存快速下降或出现 OOM，立即停止本平台，不要影响现有服务：

```bash
cd /test/pt
docker-compose stop
```

## 步骤 22：确认服务器无源码

**执行位置：服务器，Bash**

```bash
test ! -d /test/pt/backend \
  && echo "正确：没有 backend 源码目录"

test ! -d /test/pt/frontend \
  && echo "正确：没有 frontend 源码目录"

test ! -d /test/pt/.git \
  && echo "正确：没有 Git 仓库"
```

---

# 8. 开放并访问 8090

## 步骤 23：配置阿里云安全组

**执行位置：阿里云控制台**

在该 ECS 实例关联的安全组中添加入方向规则：

| 协议 | 端口 | 来源 | 用途 |
|---|---:|---|---|
| TCP | 22 | 建议限制为你的公网 IP | SSH |
| TCP | 8090 | `0.0.0.0/0` 或实际允许网段 | 新公交平台 |

后端容器内部也使用 8090，但没有直接发布到宿主机。宿主机的 8090 映射到 Web 容器的 80，浏览器仍然只经过 Nginx 访问后端。

## 步骤 24：服务器防火墙开放 8090

**执行位置：服务器，Bash**

```bash
if command -v firewall-cmd >/dev/null 2>&1; then
  systemctl enable --now firewalld
  firewall-cmd --permanent --add-port=8090/tcp
  firewall-cmd --reload
  firewall-cmd --list-ports
fi
```

## 步骤 25：浏览器验收

打开：

```text
http://8.134.88.9:8090
```

依次检查：

1. 首页打开；
2. 登录成功；
3. 数据管理页能读取已上传数据；
4. 模型列表正常；
5. 地图线路和站点正常；
6. 轻量页面不影响服务器现有服务。

当前 14 GiB 服务器不建议直接验收 V6 大模型缓存构建或线网优化。应先升级内存和数据盘，再进行重负载验收。

---

# 9. 源码更新后：傻瓜式重新打包并更新服务器

无论只改前端还是只改后端，最容易维护的做法都是：

1. 生成一个全新版本号；
2. 用相同版本号重新构建两个镜像；
3. 导出一个新镜像包；
4. 上传、校验并加载；
5. 只修改服务器 `.env` 的 `IMAGE_TAG`；
6. 使用 `docker-compose up -d --no-build` 更新；
7. 失败时改回旧标签。

## 更新步骤 1：Mac 进入项目并创建新版本

**执行位置：Mac，fish**

```fish
cd "/Users/a../模型算法/新公交平台"
docker version

set -gx IMAGE_TAG (date +%Y%m%d-%H%M%S)
set -gx IMAGE_PREFIX gjcxfzksh
set -gx IMAGE_ARCHIVE "gjcxfzksh-images-$IMAGE_TAG.tar.gz"

echo "新版本：$IMAGE_TAG"
echo "镜像包：$IMAGE_ARCHIVE"
```

不要复用旧版本标签。

## 更新步骤 2：重新编译后端

**执行位置：Mac，fish**

```fish
docker buildx use gjcxfzksh-builder

docker buildx build \
  --platform linux/amd64 \
  --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public \
  --tag "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG" \
  --load \
  ./backend
```

## 更新步骤 3：重新编译前端

**执行位置：Mac，fish**

```fish
docker buildx build \
  --platform linux/amd64 \
  --build-arg NPM_REGISTRY=https://registry.npmmirror.com \
  --tag "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG" \
  --load \
  ./frontend
```

Docker 会复用未变化的构建缓存，所以后续通常比首次编译快。

## 更新步骤 4：检查并打包新镜像

**执行位置：Mac，fish**

```fish
docker image inspect \
  --format '{{.RepoTags}} {{.Os}}/{{.Architecture}}' \
  "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG"

docker image inspect \
  --format '{{.RepoTags}} {{.Os}}/{{.Architecture}}' \
  "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG"
```

必须都是 `linux/amd64`。

```fish
mkdir -p docker-images

docker image save \
  --output "docker-images/gjcxfzksh-images-$IMAGE_TAG.tar" \
  "$IMAGE_PREFIX/gjcxfzksh-backend:$IMAGE_TAG" \
  "$IMAGE_PREFIX/gjcxfzksh-web:$IMAGE_TAG"

gzip -9 "docker-images/gjcxfzksh-images-$IMAGE_TAG.tar"

pushd docker-images
shasum -a 256 "$IMAGE_ARCHIVE" | tee "$IMAGE_ARCHIVE.sha256"
wc -c "$IMAGE_ARCHIVE.sha256"
shasum -a 256 -c "$IMAGE_ARCHIVE.sha256"
popd
```

最后一个命令必须显示 `OK`。`.sha256` 是一行纯文本，通常只有约 100 字节；
如果它与 `.tar.gz` 一样大，说明校验文件被误写成了镜像压缩包，不能上传。

## 更新步骤 5：上传新镜像包

**执行位置：Mac，fish**

```fish
rsync -avP \
  "docker-images/$IMAGE_ARCHIVE" \
  "docker-images/$IMAGE_ARCHIVE.sha256" \
  root@8.134.88.9:/test/pt/images/
```

如果 `docker-compose.yml` 也有修改，再上传它：

```fish
scp docker-compose.yml \
  root@8.134.88.9:/test/pt/docker-compose.yml
```

只修改 Java、Vue、CSS 等业务源码时不需要重复上传 Compose。

## 更新步骤 6：登录服务器并加载新镜像

**执行位置：Mac，fish**

```fish
ssh root@8.134.88.9
```

接下来在服务器 Bash 设置刚刚生成的版本：

```bash
export NEW_IMAGE_TAG="刚生成的新版本号"
export NEW_IMAGE_ARCHIVE="gjcxfzksh-images-${NEW_IMAGE_TAG}.tar.gz"
```

校验：

```bash
cd /test/pt/images
wc -c "${NEW_IMAGE_ARCHIVE}" "${NEW_IMAGE_ARCHIVE}.sha256"
sha256sum -c "${NEW_IMAGE_ARCHIVE}.sha256"
```

`.tar.gz.sha256` 应只有约 100 字节，校验命令必须显示 `OK`。

如果出现 `no properly formatted SHA256 checksum lines found`，表示 `.sha256`
不是标准文本校验文件。回到 Mac 的项目目录重新生成并只重传校验文件：

```fish
cd "/Users/a../模型算法/新公交平台/docker-images"
shasum -a 256 "$IMAGE_ARCHIVE" | tee "$IMAGE_ARCHIVE.sha256"
wc -c "$IMAGE_ARCHIVE.sha256"
shasum -a 256 -c "$IMAGE_ARCHIVE.sha256"

rsync -avP "$IMAGE_ARCHIVE.sha256" \
  root@8.134.88.9:/test/pt/images/
```

然后回到服务器重新执行：

```bash
cd /test/pt/images
wc -c "${NEW_IMAGE_ARCHIVE}.sha256"
sha256sum -c "${NEW_IMAGE_ARCHIVE}.sha256"
```

加载：

```bash
gzip -dc "/test/pt/images/${NEW_IMAGE_ARCHIVE}" \
  | docker image load
```

确认两个镜像存在：

```bash
docker image inspect \
  "gjcxfzksh/gjcxfzksh-backend:${NEW_IMAGE_TAG}" \
  >/dev/null \
  && echo "正确：新后端镜像已加载"

docker image inspect \
  "gjcxfzksh/gjcxfzksh-web:${NEW_IMAGE_TAG}" \
  >/dev/null \
  && echo "正确：新前端镜像已加载"
```

## 更新步骤 7：记录旧版本并切换 `.env`

**执行位置：服务器，Bash**

```bash
cd /test/pt
export OLD_IMAGE_TAG="$(sed -n 's/^IMAGE_TAG=//p' .env)"

echo "旧版本：${OLD_IMAGE_TAG}"
echo "新版本：${NEW_IMAGE_TAG}"

cp .env ".env.backup-$(date +%Y%m%d-%H%M%S)"
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${NEW_IMAGE_TAG}/" .env
```

检查：

```bash
docker-compose config --images
```

必须显示新版本的两个本地镜像。

## 更新步骤 8：更新容器并验证

**执行位置：服务器，Bash**

```bash
cd /test/pt
docker-compose up -d --no-build
```

```bash
docker-compose ps
docker-compose logs --tail=200 backend
docker-compose logs --tail=100 web
curl -I http://127.0.0.1:8090/
curl -sS -X POST http://127.0.0.1:8090/pt/real-data/areaList
```

再打开：

```text
http://8.134.88.9:8090
```

完成登录和核心页面检查。

## 更新步骤 9：更新失败时回滚

**执行位置：服务器，Bash**

如果更新步骤 7 的服务器终端还没有关闭，`OLD_IMAGE_TAG` 仍然存在：

```bash
cd /test/pt
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${OLD_IMAGE_TAG}/" .env
docker-compose up -d --no-build
docker-compose ps
```

如果旧服务器终端已经关闭，先手动填写旧标签：

```bash
export OLD_IMAGE_TAG="上一个正常版本号"
```

如果旧镜像还在 Docker 中：

```bash
cd /test/pt
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${OLD_IMAGE_TAG}/" .env
docker-compose up -d --no-build
```

如果旧镜像已经被删除，但旧压缩包仍在 `/test/pt/images`：

```bash
export OLD_IMAGE_ARCHIVE="gjcxfzksh-images-${OLD_IMAGE_TAG}.tar.gz"
cd /test/pt/images
sha256sum -c "${OLD_IMAGE_ARCHIVE}.sha256"
gzip -dc "${OLD_IMAGE_ARCHIVE}" | docker image load

cd /test/pt
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${OLD_IMAGE_TAG}/" .env
docker-compose up -d --no-build
```

至少保留当前正常版本和上一个正常版本的镜像包。

---

# 10. 日常运维命令

以下都在服务器 Bash 执行：

```bash
cd /test/pt
```

查看状态：

```bash
docker-compose ps
```

查看当前版本：

```bash
grep '^IMAGE_TAG=' .env
```

查看日志：

```bash
docker-compose logs --tail=200 backend
docker-compose logs --tail=100 web
```

持续看后端日志：

```bash
docker-compose logs -f backend
```

按 `Ctrl+C` 只退出日志查看，不停止容器。

重启：

```bash
docker-compose restart
```

停止平台但保留容器：

```bash
docker-compose stop
```

重新启动：

```bash
docker-compose start
```

删除容器但保留宿主机数据和镜像：

```bash
docker-compose down
```

不要随意执行：

```text
docker-compose down -v
docker system prune -a --volumes
```

检查资源：

```bash
docker stats --no-stream
free -h
df -h / /test
du -sh /test/pt/images /test/pt/data/*
```

---

# 11. 常见错误

## `docker: 'compose' is not a docker command`

服务器必须使用：

```bash
docker-compose
```

不是：

```text
docker compose
```

## `pull access denied`

本方案不需要拉业务镜像。检查镜像是否已加载以及标签是否一致：

```bash
cd /test/pt
docker-compose config --images
docker images | grep gjcxfzksh
```

禁止执行 `docker-compose pull`。正确启动命令是：

```bash
docker-compose up -d --no-build
```

## `exec format error`

说明镜像架构可能不对。服务器是 `x86_64`，镜像必须是 `linux/amd64`。

Mac 重新构建时确认：

```text
--platform linux/amd64
```

## 镜像校验 `FAILED`

不要加载损坏文件。回到 Mac 重新上传同一个包：

```fish
rsync -avP \
  "docker-images/$IMAGE_ARCHIVE" \
  "docker-images/$IMAGE_ARCHIVE.sha256" \
  root@8.134.88.9:/test/pt/images/
```

## 8090 端口被占用

```bash
ss -lntp | grep ':8090 '
```

不要直接杀掉未知进程。先确认占用服务，再决定新端口，并同步修改 `.env` 和云安全组。

## backend unhealthy

```bash
cd /test/pt
docker-compose logs --tail=300 backend
docker-compose exec backend sh -c 'ls -la /data/pt_data | head -30'
docker-compose exec backend sh -c 'ls -ld /data/pt_cache'
docker-compose exec backend sh -c 'echo "$LANG"; echo "$LC_ALL"'
```

中文环境应为 `C.UTF-8`。

## Permission denied

```bash
chown -R 10001:10001 /test/pt/data/pt_data
chown -R 10001:10001 /test/pt/data/pt_cache
chown -R 10001:10001 /test/pt/logs/backend

cd /test/pt
docker-compose up -d --no-build backend
```

## 首页能打开，但登录、注册显示后端错误或 502

先确认 Nginx 是否因为 Docker 网桥被防火墙拦截而无法连接后端：

```bash
cd /test/pt
docker-compose logs --tail=100 web
docker-compose exec web wget -S -O /dev/null http://backend:8090/v3/api-docs
```

如果日志出现 `Host is unreachable` 或 `502 Bad Gateway`，执行：

```bash
firewall-cmd --permanent --zone=docker --add-interface=br-gjcxfzksh
firewall-cmd --reload

cd /test/pt
docker-compose up -d --no-build

curl -sS -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8090/pt/auth/register \
  -H 'Content-Type: application/json' \
  --data '{"username":"a","password":"123"}'
```

最后一条命令使用故意不合规的用户名，不会创建账号；只要输出 `200`，并返回
“用户名需为 2-32 位……”之类的业务提示，就说明浏览器到后端的链路已恢复。

## 服务器突然很卡或服务被杀

当前机器内存只有约 14 GiB，且已有多个服务。立即检查：

```bash
free -h
docker stats --no-stream
journalctl -k --since '30 minutes ago' | grep -i -E 'oom|out of memory|killed process'
```

必要时只停止本平台：

```bash
cd /test/pt
docker-compose stop
```

不要影响 Dify、GeoServer 和现有 Java 服务。

---

# 12. 最终检查表

- [ ] Mac 命令使用 fish 语法。
- [ ] 服务器命令使用实际 Bash。
- [ ] 服务器所有 Compose 命令都是 `docker-compose`。
- [ ] 服务器路径全部是 `/test/pt`。
- [ ] 公网访问地址是 `http://8.134.88.9:8090`。
- [ ] 后端从纯 Java 源码编译出 `app.jar`。
- [ ] 前端从纯 Vue 源码编译出 `index.html` 和 hashed assets。
- [ ] 最终两个镜像都是 `linux/amd64`。
- [ ] 两个镜像使用同一个唯一版本号。
- [ ] 镜像包 SHA-256 校验为 `OK`。
- [ ] 服务器已经 `docker image load` 两个镜像。
- [ ] 启动使用 `docker-compose up -d --no-build`。
- [ ] 服务器没有 backend/frontend 源码目录。
- [ ] 8090 已在安全组和防火墙放行。
- [ ] backend 和 web 都 healthy。
- [ ] 已确认当前轻量配置没有影响现有服务。
- [ ] 运行 V6 或线网优化前已经升级内存和磁盘。
- [ ] 至少保留一个可回滚的旧镜像包。
