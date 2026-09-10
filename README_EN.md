# travel-screen · Travel Footprint Dashboard

**English** | [中文](README.md)

![License: MIT](https://img.shields.io/badge/license-MIT-green) ![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen) ![Release](https://img.shields.io/github/v/release/Makezx/travel-screen?label=release&color=blue) ![Stars](https://img.shields.io/github/stars/Makezx/travel-screen?style=flat&color=yellow) ![Build](https://github.com/Makezx/travel-screen/actions/workflows/ci.yml/badge.svg)

A personal **travel footprint & expense dashboard** built with Spring Boot and ECharts: a 3D flight-line map of China, spending breakdown, yearly footprint, cost rankings, travel companions, photo wall, and AI trip planning.

Single-file frontend, **zero external requests**, fully local rendering. China boundary data comes from Aliyun DataV GeoAtlas (compliant — includes Taiwan Province and the ten-dash line).

> ⚠️ The bundled sample data (`src/main/resources/data/seed-trips.json`) uses **anonymized fictional companion names**; cities, routes and amounts are for demonstration only and contain no real personal information. Never commit your production database (`traveldb.mv.db`) or `.env` to a public repository.

## Screenshots

All screenshots are rendered from a local H2 instance seeded with the anonymized sample data — no real names appear.

### Dashboard (2D / 3D)

**2D dashboard** — China map with lit-up cities, KPIs and spending rankings:

![2D Dashboard](images/dashboard-2d.png)

**3D map** (`geo3D`) auto-rotating, with city light pillars and animated flight lines:

![3D map auto-rotating](images/demo-3d.gif)

![3D map (still)](images/dashboard-3d.png)

### Data Maintenance (Excel-like Admin)

- **Overview**: an `Activity / Trip → Sub-trip → Line item` tree on the left, inline editing on the right, with xlsx import / export.

  ![Admin overview](images/dashboard-admin.png)

- **Create activity**: a 3-step wizard (basic info → participants & roles → first expense), with team ownership.

  ![Create activity](images/dashboard-activity-create.png)

- **Bill splitting (AA settlement)**: automatically computes *who owes whom how much* from the line items — paid / share / net per person, plus a minimized transfer list.

  ![AA settlement](images/dashboard-aa.png)

### AI Trip Planning

- Enter destination, days, people, budget and preferences, then generate an itinerary in one click (requires `AI_API_KEY`).

  ![AI trip planning](images/dashboard-ai.png)

## Features

- **2D + 3D map**: 2D city-level China map (369 prefecture-level cities light up as you record trips) with route flight lines; toggle a 3D extruded province map (echarts-gl `geo3D`) with city light pillars and animated flight lines.
- **Excel-style maintenance**: inline editing of trips and line items, xlsx import / export.
- **AA bill splitting**: per-expense participant selection, cent-exact remainder distribution, and a greedy minimum-transfer settlement.
- **Roles & permissions**: activities with `OWNER / CO_OWNER / EDITOR / MEMBER` roles, plus team and system-level permissions.
- **Photos**: upload and display trip photos on the map.
- **AI planning & vision**: itinerary generation and photo/vision assistance (optional, needs an API key).
- **Zero external dependencies**: all frontend assets (ECharts, GeoJSON) are bundled locally.

## Tech Stack

- Backend: Spring Boot 3.3.5 + Java 21 + JPA/Hibernate
- Frontend: single-file HTML (`src/main/resources/static/index.html`), vanilla JS + ECharts 5.5.1 + echarts-gl 2.0.9
- Map: **bundled offline vector basemap** covering all 369 prefecture-level cities of China, 34 province-level regions and the South China Sea islands / nine-dash line. No map tiles, no API key, no external assets. Served from a dedicated cached endpoint `GET /api/geo`, decoupled from trip data (`GET /api/screen-data`)
- Storage: local H2 file database (default); MySQL available via the `prod` profile
- Build: Maven

## Quick Start (Local)

**Fastest path**: grab the jar from the latest [Release](https://github.com/Makezx/travel-screen/releases/latest) — no build step, nothing beyond a JDK.

```bash
java -jar travel-screen-v1.0.0.jar
```

Or build it yourself:

```bash
# 1. Build
mvn -DskipTests package

# 2. Run (default `local` profile, H2 file DB, zero configuration)
java -jar target/travel-screen.jar

# 3. Open the dashboard
#    http://localhost:8388/
# Demo account (can only operate the "示例·演示" team, never touches real data):
#    demo@travel.cn  /  Demo@2026
# Admin login at /login  (default admin / admin123 — change it after the first start!)
```

AI planning is disabled by default. Set `AI_API_KEY` (Zhipu GLM) to enable it:

```bash
AI_API_KEY=sk-xxx java -jar target/travel-screen.jar
```

### Docker (one command)

No JDK 21 / Maven on your machine? With Docker installed, one command is enough:

```bash
# 1. Build and start in the background (first build downloads deps, ~3-8 min; later starts are seconds)
docker compose up -d

# 2. Open the dashboard:  http://localhost:8388/
# Demo account (can only operate the "示例·演示" team, never touches real data):
#    demo@travel.cn  /  Demo@2026
# Admin login at /login  (default admin / admin123 — change it after the first start!)

# 3. Logs / stop
docker compose logs -f travel-screen
docker compose down          # stop and remove the container; data volumes are kept
```

Notes:

- **Persistence**: the H2 database file plus the auth/secret files live in the named volume `travel-screen-data` (container path `/app/data`); uploaded photos live in `travel-screen-photos` (`/app/photos`). Recreating the container or upgrading the image keeps your data.
  - Inspect: compose prefixes volume names with the project name (the directory name by default, e.g. `travel-java_travel-screen-data` when cloned as `travel-java`). Run `docker volume ls | grep travel` to see the actual name, then `docker volume inspect <name>`.
  - **Wipe everything** (irreversible): `docker compose down -v`
- **Change the admin password**: create a `.env` next to `docker-compose.yml` containing `ADMIN_USER=...` and `ADMIN_PASS=...`, then run `docker compose up -d` again.
- **Enable AI planning**: add `AI_API_KEY=sk-xxx` to `.env` and recreate the container.
- **Switch to MySQL**: set `SPRING_PROFILES_ACTIVE=prod` and provide `DB_URL` / `DB_USER` / `DB_PASS`.
- **Tune the JVM**: set e.g. `JAVA_OPTS=-Xmx512m` in `.env`.
- Without compose:

  ```bash
  docker build -t travel-screen:latest .
  docker run -d -p 8388:8388 \
    -v travel-screen-data:/app/data -v travel-screen-photos:/app/photos \
    --name travel-screen travel-screen:latest
  ```

### Publish your own image to Docker Hub (optional)

The repo ships `.github/workflows/docker.yml`: **publishing a Release builds and pushes a multi-arch image (`linux/amd64` + `linux/arm64`) to Docker Hub.**

Two repository secrets are required (**Settings → Secrets and variables → Actions → New repository secret**):

| Secret | Value |
|---|---|
| `DOCKERHUB_USERNAME` | Your Docker Hub username (**not** your email) |
| `DOCKERHUB_TOKEN` | A Docker Hub personal access token with **Read & Write** permission |

To create the token: sign in to [Docker Hub](https://hub.docker.com) → avatar (top right) → **Account settings** → **Personal access tokens** → **Generate new token** → add a description and expiry, tick `Read & Write` → **Generate**. ⚠️ The token is shown only once — save it immediately.

> **Never paste the token into code, issues or chat.** Put it in the repository secrets; the workflow reads it via `secrets.*` and it never appears in logs.

Without the secrets the workflow skips itself silently (no failure). The first push creates the `travel-screen` repository automatically; if it reports a permission error, create an empty repository with that name on Docker Hub first.

Once published, anyone can run:

```bash
docker run -d -p 8388:8388 \
  -v travel-screen-data:/app/data -v travel-screen-photos:/app/photos \
  --name travel-screen <your-username>/travel-screen:latest
```

## Configuration (environment variables; defaults in `application.yml`)

| Variable | Description |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` = H2 file DB (default); `prod` = MySQL |
| `DB_URL` / `DB_USER` / `DB_PASS` | MySQL connection info (prod only) |
| `ADMIN_USER` / `ADMIN_PASS` | Default admin credentials (**change them**) |
| `AI_API_KEY` / `AI_MODEL` / `AI_VISION_MODEL` | Zhipu GLM text / vision model key and model IDs |
| `PHOTO_DIR` | Photo storage directory (default `./photos`) |

## Deployment

See [DEPLOY.md](DEPLOY.md) (systemd or nohup; replace the placeholders and change the default password first).

## Project Layout

```
src/main/resources/
  application.yml          # config (all credentials via env vars)
  data/seed-trips.json     # anonymized sample seed data
  static/index.html        # single-file dashboard frontend
  static/vendor/           # echarts / echarts-gl / map GeoJSON (vendored locally)
deploy/                    # systemd unit, start/backup scripts (placeholders only)
```

## Contributing

Issues and PRs are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it covers the local setup, the conventions you must keep (reserved-word column names, zero external requests, map compliance), and the things this project deliberately does **not** do.

| I want to… | Go here |
|---|---|
| Ask a question, share ideas, show your dashboard | [Discussions](https://github.com/Makezx/travel-screen/discussions) |
| Report a bug | [Bug report](https://github.com/Makezx/travel-screen/issues/new?template=bug_report.yml) |
| Request a feature | [Feature request](https://github.com/Makezx/travel-screen/issues/new?template=feature_request.yml) |
| Report a security issue | [Private vulnerability report](https://github.com/Makezx/travel-screen/security/advisories/new) (**not** a public issue) |

- Changelog: [CHANGELOG.md](CHANGELOG.md)
- Security and deployment requirements: [SECURITY.md](SECURITY.md)
- Code of conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

## License

[MIT](LICENSE)
