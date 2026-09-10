# 旅行大屏 Java 版部署说明

## 当前状态

- 本地 `mvn -DskipTests package` 构建通过
- 本地 `java -jar target/travel-screen.jar` 已跑通（H2 文件库，local profile）
- 前端大屏已渲染验证（Chrome headless 截图）
- 认证、导出/导入/AI 规划等接口已 smoke 测试

## 服务器现状

- 域名：`<你的域名>`
- Nginx 已配置 `443 -> 127.0.0.1:8388`
- 原 Node 版本监听在 `8388`

## 部署要求

- JDK 21+（推荐 Eclipse Temurin）
- MySQL 8.0（可选；无 MySQL 时可用 H2 文件库兜底）
- Nginx 已配置好反向代理

## 部署方式 A：systemd（推荐）

在服务器执行：

```bash
# 1. 创建目录并上传 jar
mkdir -p /opt/travel-screen
# 本机执行：scp target/travel-screen.jar root@<服务器IP>:/opt/travel-screen/

# 2. 上传并安装 systemd 服务
cp deploy/travel-screen.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable travel-screen

# 3. 修改环境变量（特别是管理员密码、MySQL 密码、AI_KEY）
vim /etc/systemd/system/travel-screen.service
systemctl restart travel-screen

# 4. 停掉旧 Node
type pidof && pidof node && kill -9 $(lsof -t -i:8388) || true

# 5. 验证
curl -s https://<你的域名>/api/health
```

## 部署方式 B：nohup 兜底

服务器端直接跑：

```bash
mkdir -p /opt/travel-screen
# 上传 jar 后：
bash deploy/run-prod.sh
```

脚本会自动：
- 读取 `SPRING_PROFILES_ACTIVE=prod` 连接 MySQL
- 或改为 `local` 走 H2 文件库
- 停掉旧 Java 进程、处理 8388 占用

## 环境变量说明

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | `local`=H2 文件库；`prod`=MySQL |
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/travel?...` | prod 模式下必填 |
| `DB_USER` | `travel` | MySQL 用户名 |
| `DB_PASS` | `<你的MySQL密码>` | MySQL 密码 |
| `ADMIN_USER` | `admin` | 默认管理员账号 |
| `ADMIN_PASS` | `<你的管理员密码>` | **首次启动后务必修改** |
| `AI_API_KEY` | 空 | 智谱 GLM API Key；不配时 AI 入口提示未配置 |
| `AI_MODEL` | `glm-4.6` | 智谱模型 ID |

## 数据迁移

新版使用表 `trip` 和 `trip_item`，字段与原 JSON/Excel 不同。已有数据已通过 `data/seed-trips.json` 在本地初始化。服务器部署后如需导入历史 Excel，请使用 `/admin` 页面的「导出/导入」功能。

## 首次启动后必做

1. 修改默认管理员密码（环境变量 `ADMIN_PASS`）后重启服务
2. 如果启用 AI 行程规划，配置 `AI_API_KEY`
3. 打开 `https://<你的域名>/login` 登录
4. 进入 `/admin` 确认数据网格正常
