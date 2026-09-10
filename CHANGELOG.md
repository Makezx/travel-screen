# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
版本变更记录格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

## [1.0.0] - 2026-09-10

首个公开版本。

### 新增

**数据大屏**
- 2D 中国地图：行程城市点亮、按到访次数热力着色、路线飞线、城市标签
- 3D 地图：echarts-gl `geo3D` 挤出视图，自动旋转 + 城市光柱 + 飞线流光（`geo3D` 按钮切换）
- 核心数据 KPI（出行次数 / 点亮城市 / 累计花费 / 单程均价）
- 花费排行 Top 10、消费结构、年度足迹、同行伙伴、待办行程滚动条
- 照片墙：按城市匹配显示行程照片

**数据维护**
- Excel 式后台：左侧「活动 / 大行程 → 子行程 → 明细」树形展开，右侧行内编辑
- 行程与明细的 xlsx 导入 / 导出（导入为全量重建语义）
- 新建活动三步向导：基本信息 → 参与者与角色 → 记第一笔

**账单与分摊**
- 按明细选择参与人，分摊到分（余数精确分配，不丢不凑）
- 最小转账数贪心清账，输出「谁该给谁多少钱」
- `SettleService` 含 12 个单元测试覆盖分摊与清账边界

**协作与权限**
- 三层模型：团队 → 活动 → 行程
- 活动角色四档 `OWNER > CO_OWNER > EDITOR > MEMBER`，含授予链校验（低角色不能提权、OWNER 不可被改）
- 双轨权限来源：全局角色权限 **或** 活动内身份
- 大屏按活动/团队切换，游客可查看分享出去的大屏

**AI 行程规划**
- 输入目的地、天数、人数、预算与偏好，一键生成行程（需配置 `AI_API_KEY`，默认关闭）

**地图数据**
- 离线内嵌矢量底图，覆盖全国 **369 个地级市** + 34 个省级行政区 + 南海诸岛十段线
- 无地图瓦片、无 API Key、无任何外链资源
- 底图由独立端点 `GET /api/geo` 提供，带一天浏览器缓存，与行程数据接口解耦
- 城市名支持自然写法：填「恩施」「阿坝」「伊犁」即可命中原自治州全称

**部署**
- `Dockerfile` + `docker-compose.yml` 一条命令启动，数据与照片走具名卷持久化
- 本地默认 H2 文件库零配置启动；生产可切 MySQL（`prod` profile）
- `deploy/` 提供 Nginx 反代与 systemd 托管配置，见 [DEPLOY.md](DEPLOY.md)

### 说明

- 仓库内示例种子数据 `src/main/resources/data/seed-trips.json` 为脱敏虚构数据，不含真实个人身份信息
- 默认管理员 `admin` / `admin123` **仅用于本地体验**，公网部署前必须通过 `ADMIN_USER` / `ADMIN_PASS` 覆盖，详见 [SECURITY.md](SECURITY.md)

[未发布]: https://github.com/Makezx/travel-screen/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Makezx/travel-screen/releases/tag/v1.0.0
