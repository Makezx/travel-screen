# 外部 API 与限流清单

> 本文回答两个问题：**项目现在调了哪些外部 API**、**限流参数在哪、怎么改**。
> 设计目标：换厂商 / 调限流**只改配置，不改业务代码**。
> 行号会随代码漂移，**以方法名为准**。

**一句话**：全部外部调用只有 **2 个**（AI、路线），且**前端零外链**（地图底图是离线矢量，无瓦片、无 SDK、无 Key）。

---

## 0. 总览

| # | 用途 | 现用厂商 | 需要 Key | 调用方（代码） | 限流实现 |
|---|---|---|---|---|---|
| ① | AI 行程规划 + 照片识别 | 智谱 GLM（OpenAI 兼容协议） | **是** `AI_API_KEY` | `AiPlanService` | `AiRateLimiter`（三层） |
| ② | 路线烘焙（大屏详细路径地图） | OSRM 公共实例 / 高德 | osrm **免**，amap **要** | `RouteService` | 段间停顿 + 重试退避 + 回填节流 |

配置入口统一在 `src/main/resources/application.yml`：
- `app.ai.*` —— AI
- `app.route.*` —— 路线

每个键都可用**环境变量**覆盖（见 `.env.example`）。

---

## ① AI：行程规划 / 照片识别

**现用端点**

```
POST https://open.bigmodel.cn/api/paas/v4/chat/completions
Authorization: Bearer ${AI_API_KEY}
```

| 项 | 值 | 配置键 / 环境变量 |
|---|---|---|
| 端点 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `app.ai.base-url` / `AI_BASE_URL` |
| 鉴权 | `Authorization: Bearer <key>` | `app.ai.api-key` / `AI_API_KEY` |
| 文本模型 | `glm-4-air` | `app.ai.model` / `AI_MODEL` |
| 视觉模型（照片识别） | `glm-4.6v` | `app.ai.vision-model` / `AI_VISION_MODEL` |
| 超时 | 60s | `app.ai.timeout-ms` / `AI_TIMEOUT_MS` |
| 总开关 | true | `app.ai.enabled` / `AI_ENABLED` |

**代码位置**

| 功能 | 方法 |
|---|---|
| 行程规划 | `AiPlanService.plan(requirement, context)` —— 构造 messages、发请求 |
| 照片识别（多模态，图片转 base64） | `AiPlanService.analyzePhoto(mimeType, data)` |
| 限流 | `AiRateLimiter.acquire()` / `release()` |
| HTTP 入口 | `ApiController` 的 `/api/ai/plan`、`/api/ai/save-draft`；`PhotoController` 的 `/api/ai/photo` |

**降级**：未配置 Key 或 `AI_ENABLED=false` 时，功能直接返回"未启用/**未配置**"文案，**不抛异常、不影响其余功能**。

### 限流（`AiRateLimiter`）

生产挂的是**真实付费 Key**，且 `demo@travel.cn` 是公开账号，必须防刷。三层：

| 层 | 参数 | 默认 | 拦什么 |
|---|---|---|---|
| 分钟窗 | `app.ai.rate-per-minute` / `AI_RATE_PER_MINUTE` | **5 次/分** | 脚本连点 |
| 天窗 | `app.ai.rate-per-day` / `AI_RATE_PER_DAY` | **100 次/天** | 慢速刷 |
| 并发闸门 | `app.ai.max-concurrency` / `AI_MAX_CONCURRENCY` | **2** | 同步 HTTP 调用很慢，不设会把 Tomcat 线程池打满 |
| 排队等待 | `app.ai.queue-timeout-ms` / `AI_QUEUE_TIMEOUT_MS` | **3000 ms** | 槽位满了快速失败，而不是无限堆积线程 |

- 计数维度：**单 IP 和单用户双计**（`ip:` 与 `u:` 两个 key），**任一超限即拒绝**。
- 顺序：分钟窗 → 天窗 → 并发槽位。
- 实现是**进程内内存**（`ConcurrentHashMap`）。**多实例部署时总量 = 实例数 × 配额**；要严格全局配额就把 `AiRateLimiter` 换成 Redis 实现，**接口 `acquire()/release()` 不变**，调用方零改动。
- 内存保护：key 数超过 20000 自动整体清空。

### 怎么换厂商

1. **同为 OpenAI 兼容协议**（DeepSeek / Kimi / 通义 / 本地 vLLM 等）：
   只改三个环境变量，代码一行不动：
   ```
   AI_BASE_URL=https://api.deepseek.com/chat/completions
   AI_MODEL=deepseek-chat
   AI_API_KEY=sk-xxxx
   ```
2. **协议不同**：只改 `AiPlanService` 里的**请求构造**与**响应解析**（现在是读
   `choices[0].message.content`），其余（限流、超时、降级、Controller）都不用碰。
3. **改成你自己的 agent**：替换 `AiPlanService` 两个 public 方法的实现即可，接口签名不变。

---

## ② 路线烘焙（大屏"详细路径地图"）

把「城市中心对中心」的直线，展开成**沿真实道路**的密集坐标，存进 `trip.path_json`，供大屏渲染。
**烘焙 / 浏览分离**：结果入库，大屏渲染零联网；API 只在「新增/修改行程」和「启动回填」时调用。

**策略开关**：`app.route.provider` / 环境变量 `ROUTE_PROVIDER`

| 策略 | 端点 | Key | 说明 |
|---|---|---|---|
| `straight`（默认） | — | 无 | 城市中心对中心直线，零依赖 |
| `osrm` | `https://router.project-osrm.org/route/v1/driving/{lng},{lat};{lng},{lat}?overview=full&geometries=geojson` | **免** | OSM 公共实例，沿真实道路 |
| `amap` | `https://restapi.amap.com/v3/direction/driving?key=&origin=&destination=&extensions=base` | `ROUTE_AMAP_KEY` | 高德 WebService，中国路网最佳精度 |

**代码位置**

| 功能 | 方法 |
|---|---|
| 策略分派 | `RouteService.fetchSegment(a, b)` |
| OSRM 请求 | `RouteService.fetchOsrm(a, b)` |
| 高德请求（含重试退避） | `RouteService.fetchAmap(a, b)` |
| 可重试错误码判定 | `RouteService.isAmapTransient(infocode)` —— `10021`/`10044`/`20003`/`1002x` |
| 对外 API | `RouteService.bakeForTrip(trip)`、`denseForCities(cities)`、`waypointsForCities(cities)` |
| 调用方 | `ApiController.bakeRoute(t)`（createTrip / updateTrip / addItem 保存前触发）、`BootstrapService.backfillRoutes()`（启动后台回填） |

**坐标系**：底图与 `city-coords.json` 是 **GCJ-02**。OSRM 返回 **WGS-84**，所以请求前 `GCJ→WGS`、结果 `WGS→GCJ`（见 `RouteService.fetchOsrm` 与 `wgs84ToGcj02/gcj02ToWgs84`）。**换国外路线厂商时必须保留这一步**，否则路线会整体偏移。

**降级**：无 Key / 请求失败 → 保留 `null`（或原值）→ 前端自动退回直线。**绝不阻断保存**。

### 限流

| 项 | 参数 | 默认 | 说明 |
|---|---|---|---|
| 高德段间停顿 | `app.route.amap-segment-delay-ms` / `ROUTE_AMAP_SEGMENT_DELAY_MS` | **400 ms** | 高德官方 ≤3 QPS，400ms ≈ 2.5 QPS 留余量 |
| 高德重试次数 | `app.route.amap-max-tries` / `ROUTE_AMAP_MAX_TRIES` | **5** | 临时错误按 `400ms × n` 线性退避 |
| 连接超时 | `app.route.connect-timeout-ms` / `ROUTE_CONNECT_TIMEOUT_MS` | **3000 ms** | |
| 读取超时 | `app.route.read-timeout-ms` / `ROUTE_READ_TIMEOUT_MS` | **5000 ms** | 慢 API 拖死烘焙的兜底 |
| 启动回填节流 | `app.route.backfill-delay-ms` / `ROUTE_BACKFILL_DELAY_MS` | **400 ms** | 行程之间停顿，**设 0 = 不限速** |
| 抽稀容差 | `app.route.simplify-tol` / `ROUTE_SIMPLIFY_TOL` | **0.006**（≈700m） | Douglas-Peucker；越大点越少、线越粗 |

**OSRM 公共实例没有公开配额**，靠上面两个超时 + 回填节流兜底，不要把它当生产 SLA 依赖。

### 怎么换厂商

1. **切到高德**：`.env` 里 `ROUTE_PROVIDER=amap` + `ROUTE_AMAP_KEY=<WebService Key>`，重启。
2. **新增厂商**：在 `RouteService.fetchSegment()` 加一个 `else if ("xxx".equals(provider))` 分支，
   再照 `fetchOsrm()` / `fetchAmap()` 写一个 `fetchXxx()` 返回 `List<double[]>` 即可；
   **其余（缓存、抽稀、入库、降级、前端渲染）全部复用**。
3. 若新厂商返回 WGS-84，记得照 `fetchOsrm` 做坐标转换。

---

## 附：没有外部调用的地方（别误改）

- **地图底图**：离线矢量，`src/main/resources/data/geo.json`（369 地级市）+ `/api/geo` 端点，**无瓦片、无 SDK、无 Key**。
- **前端**：`static/*.html` 零外链（无 CDN、无字体、无统计脚本）。
- **数据库**：local=H2 文件库，prod=MySQL，均自建。

---

## 改动检查清单

改完配置后建议按这条走一遍：

1. 改 `.env`（或环境变量）→ 重启。
2. 看启动日志：
   - `[route] 路线烘焙策略=xxx（连接…/读取…/抽稀容差…）` —— 确认策略与参数已生效
   - `[route-backfill] 完成：烘焙=N 跳过=M 失败=K 共=T` —— 确认 `失败=0`
3. 大屏打开「全部路线」，确认路线沿路（而非直线）且无 JS 报错。
4. 调 AI：连续点 6 次应出现"调用过于频繁"提示（验证分钟窗生效）。
