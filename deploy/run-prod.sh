#!/usr/bin/env bash
# 服务器端快速启动脚本（nohup 兜底方案）
set -euo pipefail

APP_DIR=${APP_DIR:-/opt/travel-screen}
mkdir -p "$APP_DIR"
cd "$APP_DIR"

# 加载本地凭据（若存在）：AI_API_KEY / AI_MODEL 等
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source ./.env
  set +a
fi

# 自动选 profile：显式指定优先；否则探测本机 MySQL，有则用 prod，无则 local(H2 文件库)
if [[ -z "${SPRING_PROFILES_ACTIVE:-}" ]]; then
  if (command -v mysqladmin >/dev/null 2>&1 && mysqladmin ping >/dev/null 2>&1) \
     || (command -v lsof >/dev/null 2>&1 && lsof -i:3306 >/dev/null 2>&1); then
    SPRING_PROFILES_ACTIVE=prod
    echo "[profile] 检测到 MySQL，使用 prod"
  else
    SPRING_PROFILES_ACTIVE=local
    echo "[profile] 未检测到 MySQL，使用 local(H2 文件库)"
  fi
fi
export SPRING_PROFILES_ACTIVE

# ====== 按需修改这三项（MySQL）======
export DB_URL=${DB_URL:-jdbc:mysql://127.0.0.1:3306/travel?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
export DB_USER=${DB_USER:-travel}
export DB_PASS=${DB_PASS:-changeme}

# ====== 默认管理员账号；首次启动后请修改（开源仓库不含真实密码）======
export ADMIN_USER=${ADMIN_USER:-admin}
export ADMIN_PASS=${ADMIN_PASS:-admin123}

# 智谱 GLM（可选，不配则 AI 规划返回提示文案）
# export AI_API_KEY=sk-...

# 停掉旧进程
pkill -f travel-screen.jar || true

# 如需从 Node 切换，先停掉占用 8388 的 Node（非交互/管道输入时自动杀，便于远程部署）
if lsof -i:8388 >/dev/null 2>&1; then
  echo "发现 8388 端口占用进程："
  lsof -i:8388
  if [ -t 0 ]; then
    read -r -p "是否强制杀掉？y/n: " ans
    [[ "$ans" == "y" ]] && kill -9 $(lsof -t -i:8388) || true
  else
    echo "（非交互模式）自动杀掉占用 8388 的进程"
    kill -9 $(lsof -t -i:8388) 2>/dev/null || true
    sleep 2
  fi
fi

setsid java -jar "$APP_DIR/travel-screen.jar" > "$APP_DIR/app.log" 2>&1 < /dev/null &
echo "travel-screen started, PID=$!, tail -f $APP_DIR/app.log"
