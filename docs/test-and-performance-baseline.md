# 测试与性能基线

## 可运行检查

后端接口/缓存契约：

```bash
cd backend
mvn test
```

前端构建与单测：

```bash
cd frontend
npm run type-check
npm run test:unit
npm run build
```

## 重点覆盖

- `GET /pt/data/trajectory/chunk.bin`：验证强缓存 `ETag`、`Cache-Control: immutable`、文件热读、`If-None-Match` 命中 `304`、冷缓存 `202 no-store`。
- `POST /pt/data/trajectory/chunk.bin`：验证冷缓存仍保持 `202` 空二进制响应。
- `POST /pt/route/routePanelDetail`：验证 `lineId + routeId` 透传，用于区分重复 `routeId`。
- `POST /pt/facility/stationPanel`：验证面板生成中状态按统一 `AjaxResult` 返回。
- `POST /pt/route/tile.bin`：验证瓦片二进制头部、版本、布局、坐标原点和 float32 列式字段。
- 前端 `trajectoryChunkCache`：验证无 IndexedDB 环境下静默降级。
- 前端 `realDataCache`：验证并发去重、版本缓存隔离、按区域失效和空区域列表兜底。

## 性能基线建议

接口响应时间用固定模型、固定请求体重复测量，先跑一次预热，再记录 P50/P95：

```bash
BASE_URL=http://localhost:8090
DATASOURCE=area/public/model

curl -s -o /dev/null -w 'trajectory hot read: %{time_total}s\n' \
  "$BASE_URL/pt/data/trajectory/chunk.bin?datasource=$DATASOURCE&start=0"

curl -s -o /dev/null -w 'route panel: %{time_total}s\n' \
  -H 'Content-Type: application/json' \
  -d "{\"datasource\":\"$DATASOURCE\"}" \
  "$BASE_URL/pt/route/routePanel"

curl -s -o /dev/null -w 'station panel: %{time_total}s\n' \
  -H 'Content-Type: application/json' \
  -d "{\"datasource\":\"$DATASOURCE\"}" \
  "$BASE_URL/pt/facility/stationPanel"

curl -s -o /dev/null -w 'route tile bin: %{time_total}s size=%{size_download}\n' \
  -H 'Content-Type: application/json' \
  -d "{\"datasource\":\"$DATASOURCE\",\"z\":12,\"x\":0,\"y\":0}" \
  "$BASE_URL/pt/route/tile.bin"
```

并发基线可用 `xargs -P` 做最小压测，观察是否出现非 2xx/3xx：

```bash
seq 1 50 | xargs -I{} -P 10 curl -s -o /dev/null -w '%{http_code} %{time_total}\n' \
  "$BASE_URL/pt/data/trajectory/chunk.bin?datasource=$DATASOURCE&start=0"
```

前端 chunk 体积用 Vite 构建输出作为基线。若出现 `chunk size limit` 警告，先记录最大 chunk 文件名和 gzip 后大小，再评估是否需要继续拆分 `manualChunks`：

```bash
cd frontend
npm run build
find gjcxfzksh_web_dist/assets -type f -name '*.js' -print0 \
  | xargs -0 gzip -c \
  | wc -c
```
