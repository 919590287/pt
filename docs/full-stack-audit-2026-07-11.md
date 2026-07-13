# 全栈代码审计与优化报告（2026-07-11）

## 反模式判定

**结论：部分未通过。** 产品不是典型的无差别 AI 模板，地图是明确的业务主画布，交互与数据状态也有真实深度；但截图与代码仍存在明显的蓝色同质化、右侧重复卡片网格、玻璃/渐变装饰和大量逐文件硬编码颜色。视觉语言与 `PRODUCT.md` 的“克制、避免通用卡片网格”尚未完全一致。

## 审计健康度

| 维度 | 分数 | 关键结论 |
|---|---:|---|
| 可访问性 | 2/4 | 已恢复页面缩放、非地图右键菜单并补充页面语言；地图和固定缩放布局仍缺少完整的小屏/文本放大适配 |
| 性能 | 4/4 | 登录首屏 gzip 资源从约 948 KB 降到 238 KB；真实数据重启冷请求实测由 18.65 秒降到 0.91 秒 |
| 响应式 | 2/4 | 有断点和统一缩放，但 1430×686 设计画布、0.5 最小缩放与 Header 最小列宽会限制窄屏体验 |
| 主题化 | 2/4 | 已有 tokens，但 29 个样式文件仍有约 1,946 处十六进制/RGB(A) 颜色字面量 |
| 反模式 | 2/4 | 业务地图有辨识度；重复卡片、蓝色单调和装饰性渐变仍较明显 |
| **总分** | **12/20** | **关键安全与性能风险已关闭，剩余重点是响应式与结构治理** |

## 执行摘要

- 已修复 1 个 P0，以及多项 P1/P2 安全、正确性、性能和资源生命周期问题。
- 前端类型检查通过，8 个测试文件共 31 个测试通过，生产构建通过。
- 后端 clean test/package 通过，12 个测试类共 63 个测试通过。
- `npm audit`：0 个已知漏洞。
- 后端 fat jar：约 133 MB → 114 MB；嵌套生产库 233 → 195。
- 前端 HTML 初始依赖：约 948 KB gzip → 238 KB gzip，减少约 75%。

## 已修复问题

### [P0] 无凭据密码重置可接管任意账户

- 位置：`AuthController`、`AuthServiceImpl`、`AuthView.vue`
- 类别：安全 / 正确性
- 影响：旧接口仅凭用户名和新密码即可修改任意账户密码。
- 修复：要求原密码校验；错误信息不区分用户是否存在；成功后撤销该用户全部旧会话，只签发一个新会话；增加并发和会话撤销测试。

### [P1] 生产依赖存在 XSS、CRLF 注入和开发服务器漏洞

- 位置：`frontend/package.json`、`package-lock.json`、`yarn.lock`
- 类别：安全 / 供应链
- 影响：ECharts 6.0.x、`form-data` 4.0.5、Vite 8.0.x 等命中公开漏洞；未启用的 Vue DevTools 和未使用的 `npm-run-all2` 扩大攻击面并造成 peer 冲突。
- 修复：升级 ECharts、Axios、Vite 及安全传递依赖，移除两个死依赖；复测 `npm audit` 为 0。

### [P1] 用户可控 SHP 属性可触发 CSV 公式注入

- 位置：`RealDataServiceImpl.safeSpreadsheetCell`
- 类别：安全 / 数据导出
- 影响：以 `=`, `@`, `+`, `-`, tab 或 CR 开头的属性可能在表格软件中作为公式执行。
- 修复：仅对面向用户的 CSV 导出中和危险前缀，合法正负数和科学计数法保持数值格式；增加回归测试。

### [P1] 抽样模型满载率按采样率系统性偏低

- 位置：`TransitMetrics.fullLoadRate` 及 PT/Route/预计算调用方
- 类别：业务正确性
- 影响：10% 抽样模型会把满载率算成真实口径的约 10%。
- 修复：统一在指标层按 `(0,1]` 采样率扩样，非法的 `10/100` 百分数写法安全回退为 1；增加 10% 抽样测试。

### [P1] 登录首屏被地图和图表依赖污染

- 位置：`main.js`、`router/index.js`、`plugins/echarts.js`、`vite.config.js`、`MHeader.vue`
- 类别：加载性能
- 影响：登录页原本强制 modulepreload MapLibre、Deck.gl、ECharts 和所有路由，约 948 KB gzip。
- 修复：ECharts 下沉到实际使用路由；移除全路由空闲预加载；导航悬停/聚焦时预测加载目标路由；恢复 Vite 自动路由拆包。初始资源降至 238 KB gzip。

### [P1] 真实数据冷请求被站点覆盖几何计算阻塞

- 位置：`RealDataServiceImpl.overview`、`coverageFromStationCollection`
- 类别：后端性能 / 可恢复缓存
- 剖析：16,435 个站点下，线路、站点、场站读取与派生字段合计不足 1 秒；300/500 m 覆盖缓冲与行政区相交占 17.50 秒，约为冷请求的 95%。
- 修复：300/500 m 两套独立 JTS 缓冲并行计算；概览结果按源文件签名写入平台派生缓存目录，原子替换、版本校验、损坏时安全回算；增加跨服务重启命中和过期签名回算测试。
- 实测：首次无磁盘缓存 18.57 秒 → 12.64 秒（降低约 32%）；服务重启后磁盘命中 18.65 秒 → 0.91 秒（降低约 95%），指标数值保持一致。

### [P1] 前端缓存失效与强制刷新存在旧响应回写竞态

- 位置：`realDataCache.js`、`modelDataCache.js`、`scenariocomparison/index.vue`
- 类别：并发正确性 / 内存
- 影响：旧请求的 `finally` 可能删除新请求句柄，失效后的旧响应可能回写缓存；快速切换区域/模型时较慢响应会覆盖当前选择；历史真实数据大对象无上限。
- 修复：增加全局/区域失效代次、请求身份校验、AbortController 与响应序号；真实数据缓存改为 8 项 LRU；模型缓存跳过在途项时继续淘汰其他旧项，并避免旧请求移除新控制器；补充竞态和淘汰测试。

### [P1] 全局手势守卫阻断页面缩放与非地图右键菜单

- 位置：`browserGestureGuard.js`、`index.html`
- 类别：可访问性 / 输入交互
- 影响：Safari 捏合缩放被禁止，所有页面区域的右键菜单均被拦截。
- 标准：WCAG 1.4.4（文本缩放）、2.1.1（键盘及替代输入可用性相关）。
- 修复：保留原生页面缩放；右键抑制仅作用于 `#mapRoot`；删除数据管理页重复注册的 6 个捕获阶段监听；补充 `lang="zh-CN"`。

### [P2] 模型并发卸载导致偶发空返回

- 位置：`Datasource.data`
- 类别：并发正确性
- 影响：方法校验本地引用后再次从 Map 读取；两次读取之间若卸载，会返回 null 并在调用方形成偶发 NPE。
- 修复：返回已校验并更新时间戳的同一引用。

### [P2] 后端发布包包含完整测试框架和未使用运行库

- 位置：`backend/pom.xml`
- 类别：构建性能 / 部署体积
- 影响：JUnit、TestNG、Mockito、AssertJ 等进入生产 fat jar，另有 SQLite、Lucene、OSHI 等无直接使用的依赖。
- 修复：测试依赖改为 test scope，移除 TestNG 与无直接使用的声明；升级 Lombok 以支持 JDK 25 clean compile；移除重复 Spring Boot repackage。

### [P2] Undertow 线程固定过量

- 位置：`application.yml`
- 类别：后端运行性能
- 影响：固定 512 worker / 32 IO 在线程数远高于常规 8–16 核部署需求，增加线程栈内存与上下文切换。
- 修复：默认改为 128/16，并开放 `UNDERTOW_WORKER_THREADS`、`UNDERTOW_IO_THREADS` 环境变量供压测调优。

### [P2] Resize 热路径重复执行大量样式写入

- 位置：`App.vue`
- 类别：渲染性能
- 影响：窗口和 VisualViewport resize 可在单帧多次触发约 20 个 CSS 变量写入。
- 修复：用 `requestAnimationFrame` 合并为每帧一次，并在卸载时取消未执行帧。

### [P2] SHP 临时比对结果和后台钩子缺少生命周期上限

- 位置：`RealDataServiceImpl.pendingShpComparisons`、`PTDataServiceImpl`、`Datasource`
- 类别：内存 / 资源释放
- 影响：只上传比对但不提交的操作快照可永久累积；Spring 上下文重建时轨迹线程池和静态预热钩子没有对称注销。
- 修复：SHP 比对增加 30 分钟 TTL 与 100 项上限；轨迹线程池在销毁时中断，预热钩子支持去重注册与注销。

## 尚未关闭的高优先级问题

### [P1] 固定设计画布限制窄屏和大字号

- 位置：`App.vue` 的 1430×686 设计尺寸和 0.5 最小缩放；`MHeader.vue` 的 Header 最小网格列宽。
- 影响：320–480 px 窄屏和 200% 文本缩放可能出现信息密度过高、横向裁切或触控目标缩小。
- 标准：WCAG 1.4.4、1.4.10。
- 建议：在 900 px 以下切换结构布局，Header 收纳为菜单，左右面板改抽屉/底部 sheet，地图控件保持至少 44×44 CSS px。
- 建议命令：`/impeccable adapt`

### [P2] 超大单文件持续放大回归风险

- 位置：`datamanagement/index.vue` 11,276 行；`datavisualization/index.vue` 8,766 行；`RealDataServiceImpl.java` 4,782 行；`MatsimAnalysisCache.java` 3,090 行。
- 影响：难以局部测试、并行开发冲突高、响应式依赖和缓存失效路径难以推理。
- 建议：按“数据获取/缓存、地图图层、面板状态、导出、编辑历史”拆 composable/service；先加契约测试再搬移，不做一次性重写。
- 建议命令：`/impeccable harden`

### [P2] 主题令牌覆盖不足

- 位置：前端 29 个样式文件约 1,946 处颜色字面量。
- 影响：品牌色、状态色和暗色/高对比主题难以一致调整；同义颜色逐页漂移。
- 建议：先按 surface/text/border/accent/state/data-series 建立语义 token，再机械替换高频值，保留地图数据色的专用调色板。
- 建议命令：`/impeccable colorize`、`/impeccable extract`

### [P2] 路由内地图与图表包仍较重

- 证据：生产构建中地图共享块约 334 KB gzip，ECharts 约 226 KB gzip。
- 影响：首次进入运行监测仍受弱网和低端设备影响。
- 建议：按功能标签延迟加载车辆 3D、Deck 图层和非首屏图表；对长期不可见面板使用动态 import，并在真实设备记录 LCP/INP/内存峰值后继续拆分。
- 建议命令：`/impeccable optimize`

## 系统性问题

- 缓存策略已经较成熟，真实数据概览现已具备可恢复磁盘缓存；但其他高成本派生数据仍需持续检查是否只存在于进程内。
- 测试集中于缓存/认证/真实数据提交与关键性能调度；前端已有 8 个测试文件，但 11k/8k 行核心页面仍缺少路由级和交互级回归。
- 视觉 token 已在部分数据管理模块建立，但全局页面仍大量直接写颜色和尺寸，主题化成本持续上升。

## 正向发现

- 路由已使用动态 import，缓存与大模型任务有单飞、版本键和后台构建意识。
- 轨迹、路网和面板存在二进制/分片缓存与强缓存契约测试。
- 前端有统一错误日志、traceId 串联、AbortController、Worker、RAF 合帧和卸载清理实践。
- 已提供 reduced-motion、focus-visible、语义导航、表单标签和 loading/empty/error 状态。
- 认证哈希使用 PBKDF2，旧哈希支持透明升级，登录校验已移出全局写锁。

## 推荐执行顺序

1. **[P1] `/impeccable adapt`**：为 900 px 以下和 200% 文本缩放建立结构性布局。
2. **[P2] `/impeccable harden`**：为核心路由补 Playwright/组件交互测试，再拆超大文件。
3. **[P2] `/impeccable extract`**：提取全局语义颜色、间距和控件 token。
4. **[P2] `/impeccable optimize`**：在真实设备记录 LCP/INP/内存峰值后继续拆地图与图表重包。
5. **[P3] `/impeccable polish`**：完成最终视觉与交互一致性复核。

修复后应重新运行 `/impeccable audit`，并在真实生产数据与低端设备上补充 LCP、INP、CLS、内存峰值和接口 P50/P95。

## 2026-07-12 缩放性能复核

### [P1] 程序化相机更新吞掉自定义图层事件

- 位置：`MyMap.setZoom`、`setCenter`、`setCenterAndZoom`、`setFitZoomAndCenterByPoints`。
- 原因：包装层在 `jumpTo` 前先写入新 zoom/center，MapLibre 的同步 `move` 回调随后把新值当成旧值，导致 `UPDATE_ZOOM` / `UPDATE_CENTER` 不派发；框选定位还分两次 `jumpTo`，触发两轮相机重绘。
- 影响：工具栏缩放跨过建筑/标签阈值时，自定义图层可能不刷新；定位动作产生额外重排与 WebGL 提交。
- 修复：以 MapLibre 作为相机状态单一事实源，`jumpTo` 后统一同步；无状态变化不广播 `UPDATE_CAMERA_POSITION`；框选中心和 zoom 合并为一次相机提交。

### [P1] 多路网图层在同一缩放帧重复提交 Deck 状态

- 位置：`NetworkLayer.queueDeckUpdate`、`deckOverlayRegistry`。
- 原因：底网、选中线路、反向线路、换乘高亮等实例各自注册 rAF，并在各自回调里重新排序全部 Deck 图层、调用一次 `MapboxOverlay.setProps`。
- 影响：缩放期间单帧提交次数随可见 NetworkLayer 数线性增长，放大主线程调度与 Deck diff 成本。
- 修复：NetworkLayer 使用模块级共享 rAF；注册表提供事务式批处理，同一地图同一帧无论更新多少路网图层，只排序和 `setProps` 一次。图层数据、样式、透明度及 zIndex 顺序不变。
- 可重复验证：新增测试证明 3 次队列请求（2 个图层）仅安排 1 个动画帧；2 个注册表更新仅执行 1 次有序 `setProps`。

### 回归结果

- 前端：8 个测试文件、31 个测试通过；`vue-tsc --build` 通过；Vite 生产构建通过。
- 后端：12 个测试类、71 个测试通过。
- 供应链：`npm audit --omit=dev --registry=https://registry.npmjs.org` 为 0 个已知漏洞。
- 构建：地图共享块 335.27 KB gzip，ECharts 225.97 KB gzip；两者均按路由延迟加载，没有重新污染登录首屏。
- 浏览器：本地登录页无 console warning/error，页面语言和认证语义保留；运行监测页需要有效本地账户和模型数据，完整真实数据缩放 FPS/INP 仍应在部署环境补测。
