#!/usr/bin/env bash
#
# travel-screen 生产备份脚本
# ----------------------------------------------------------------------------
# 备份两样东西，缺一不可：
#   1) MySQL 业务库（travel）—— 用 mysqldump 全量导出并 gzip
#   2) 照片目录（PHOTO_DIR，默认 $APP_DIR/photos）—— tar.gz 打包
#
# 注意：从 /admin 页面「导出 xlsx」只是数据快照，不是备份！
#       导入会 deleteAll 清库，只有这里的 mysqldump 能在误操作后回滚。
#
# 安装 crontab（每日 03:17 执行，避开整点）：
#   crontab -e
#   # m h  dom mon dow   command
#   17 3 * * *  APP_DIR=/home/ubuntu/travel-screen /home/ubuntu/travel-screen/deploy/backup.sh >> /home/ubuntu/travel-screen/backups/cron.log 2>&1
#
# 恢复：
#   gzcat backups/db-YYYYMMDD-HHMMSS.sql.gz | mysql -u<user> -p<pass> travel
#   tar -xzf backups/photos-YYYYMMDD-HHMMSS.tar.gz -C /home/ubuntu/travel-screen
#
set -euo pipefail

APP_DIR=${APP_DIR:-/home/ubuntu/travel-screen}
cd "$APP_DIR"

# 载入生产凭据（DB_URL/DB_USER/DB_PASS/PHOTO_DIR 等）
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source ./.env
  set +a
fi

# --- 解析 DB_URL（jdbc:mysql://host:port/db?...）---
DB_URL=${DB_URL:-jdbc:mysql://127.0.0.1:3333/travel}
if [[ "$DB_URL" =~ jdbc:mysql://([^:/]+)(:([0-9]+))?/([^?]+) ]]; then
  DB_HOST=${BASH_REMATCH[1]:-127.0.0.1}
  DB_PORT=${BASH_REMATCH[3]:-3306}
  DB_NAME=${BASH_REMATCH[4]}
else
  DB_HOST=${DB_HOST:-127.0.0.1}
  DB_PORT=${DB_PORT:-3333}
  DB_NAME=${DB_NAME:-travel}
fi
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-}

BACKUP_DIR="$APP_DIR/backups"
DATE=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

# --- 1) 数据库 ---
if command -v mysqldump >/dev/null 2>&1; then
  echo "[backup] mysqldump $DB_NAME -> db-$DATE.sql.gz"
  mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    ${DB_PASS:+ -p"$DB_PASS"} --single-transaction --routines --triggers --no-tablespaces \
    "$DB_NAME" | gzip > "$BACKUP_DIR/db-$DATE.sql.gz"
else
  echo "[backup] 警告：未找到 mysqldump，跳过数据库备份" >&2
fi

# --- 2) 照片 ---
PHOTO_DIR=${PHOTO_DIR:-$APP_DIR/photos}
if [[ -d "$PHOTO_DIR" ]]; then
  echo "[backup] tar 照片目录 $PHOTO_DIR -> photos-$DATE.tar.gz"
  tar -czf "$BACKUP_DIR/photos-$DATE.tar.gz" -C "$(dirname "$PHOTO_DIR")" "$(basename "$PHOTO_DIR")"
else
  echo "[backup] 提示：照片目录不存在（$PHOTO_DIR），跳过"
fi

# --- 保留最近 7 份，其余清理 ---
if ls -1t "$BACKUP_DIR"/db-*.sql.gz >/dev/null 2>&1; then
  ls -1t "$BACKUP_DIR"/db-*.sql.gz | tail -n +8 | xargs -r rm -f
fi
if ls -1t "$BACKUP_DIR"/photos-*.tar.gz >/dev/null 2>&1; then
  ls -1t "$BACKUP_DIR"/photos-*.tar.gz | tail -n +8 | xargs -r rm -f
fi

echo "[backup] 完成。最新备份在 $BACKUP_DIR (db-$DATE.sql.gz)"
ls -1ht "$BACKUP_DIR" | head
