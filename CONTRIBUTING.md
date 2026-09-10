# 参与贡献

感谢你有兴趣改进 travel-screen。这是一个个人项目，维护精力有限，但**认真的 Issue 和 PR 都会被看到**。

## 在此之前

- 提问、想法、使用心得 → 优先开 [Discussion](https://github.com/Makezx/travel-screen/discussions)，不要开 Issue
- 明确的 Bug、可复现的异常 → 用 [Bug 报告模板](https://github.com/Makezx/travel-screen/issues/new?template=bug_report.yml)
- 新功能 → 先开 [功能请求](https://github.com/Makezx/travel-screen/issues/new?template=feature_request.yml) 对齐一下，**避免你写完 PR 才发现方向不对**
- 安全漏洞 → **不要开公开 Issue**，见 [SECURITY.md](SECURITY.md)

## 本地开发

### 环境

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Node（可选） | 22+，仅跑前端冒烟测试时需要 |

### 跑起来

```bash
git clone https://github.com/Makezx/travel-screen.git
cd travel-screen

# 构建（跳过测试，快）
mvn -DskipTests package

# 运行：默认 local profile，用本地 H2 文件库，零配置
java -jar target/travel-screen.jar
# 打开 http://localhost:8388/
```

首次启动会自动建库并写入示例数据。演示账号 `demo@travel.cn / Demo@2026`，文件管理员默认 `admin / admin123`（可用环境变量 `ADMIN_USER` / `ADMIN_PASS` 覆盖）。

> **local profile 用 H2 文件库**。如果你本机 `.env` 里写了 `SPRING_PROFILES_ACTIVE=prod`，**务必显式指定** `--spring.profiles.active=local`，否则可能连到生产库。

### 跑测试

```bash
mvn -B test
```

测试集中在 `SettleServiceTest`（账单 AA 分摊的分位取整与最小转账数）。

## 改代码时要守的约定

这几条是历史踩坑换来的，改动时请一起遵守：

1. **列名不能直接用 Java 属性名。** `year`、`avg`、`name`、`role` 在 H2/MySQL 里是保留字，实体列名已映射为 `trip_year` / `trip_avg` / `item_name` / `mem_role`。加字段前先查一下是不是保留字。
2. **前端保持零外链。** `index.html` 是单文件前端，ECharts、echarts-gl、地图数据全部本地内嵌。**不要引入 CDN**，这是这个项目的核心卖点之一。
3. **地图数据要合规。** 底图来自阿里云 DataV GeoAtlas，必须包含台湾省（`710000`）与南海诸岛十段线（`100000_JD`）。改动地图资源后请确认这两项还在。
4. **`place2adcode` 不要对外暴露。** 这张表（千岛湖→杭州 之类）是由真实行程反推出来的，属于个人轨迹信息，只允许在服务端内部使用。`/api/geo` 已经刻意不返回它，不要加回去。
5. **不要提交运行时产物。** `traveldb.mv.db`、`travel-auth.json`、`travel-secret.key`、`.env`、`photos/` 都已在 `.gitignore` 里，别用 `-f` 强推。

## PR 流程

1. 从 `main` 切分支：`fix/xxx` 或 `feat/xxx`
2. **保持改动聚焦**，一个 PR 只做一件事
3. 提交信息用**祈使句**，能看懂"改了什么"；有踩坑背景的写在正文里（这个仓库的 commit 历史就是这个风格，可以参考）
4. 本地跑过 `mvn -B test`，CI 必须绿
5. 如果改了前端单文件 HTML，**附一张截图**，说明改动前后的差异
6. PR 描述里关联对应 Issue（`Closes #123`）

## 代码风格

- Java：跟随现有文件的风格，不引入新的格式化配置。改动尽量小，别顺手重排整个文件
- 前端：同 `index.html` 的原生 JS 风格（ES5 语法为主，函数式，无构建步骤）。**不要引入打包器**
- 注释：解释**为什么**，不是**做了什么**。这个仓库有相当多"为什么不那样写"的注释，欢迎延续
- 语言：代码注释与 commit 用中文；README 中英双份，改动功能时**两份都要同步**

## 关于 AI 辅助

用 AI 写代码完全没问题，但请**自己读懂再提**。不接受看不懂逻辑的大段生成代码——维护成本会落到后面的人身上。如果 PR 是 AI 生成且未经你验证，请直接说明。

## 许可

提交贡献即表示你同意以本项目的 [MIT License](LICENSE) 分发你的代码。
