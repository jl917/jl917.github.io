#!/usr/bin/env bash
# =====================================================================
# install.sh : 예제 DB 를 한 번에 세팅합니다.
#
#   ./install.sh          → 스키마 + 마스터 + 주문 데이터 (빠름, ~5초)
#   ./install.sh --big    → 위 + 100만 행 access_logs (Step 15 이후 필요)
#
# 접속 정보는 docker/docker-compose.yml 과 맞춰져 있습니다.
# =====================================================================
set -euo pipefail

HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3307}"
USER="${MYSQL_USER:-learner}"
PASS="${MYSQL_PASSWORD:-learn1234}"

HERE="$(cd "$(dirname "$0")" && pwd)"
MYSQL="mysql -h${HOST} -P${PORT} -u${USER} -p${PASS} --default-character-set=utf8mb4"

echo "▶ MySQL 접속 확인 (${HOST}:${PORT})"
$MYSQL -e "SELECT VERSION() AS mysql_version;"

echo "▶ 01_schema.sql"
$MYSQL < "${HERE}/01_schema.sql"

echo "▶ 02_seed_master.sql"
$MYSQL shop < "${HERE}/02_seed_master.sql"

echo "▶ 03_seed_orders.sql"
$MYSQL shop < "${HERE}/03_seed_orders.sql"

if [[ "${1:-}" == "--big" ]]; then
  echo "▶ 04_seed_big.sql (100만 행 생성 — 20초~1분 소요)"
  $MYSQL shop < "${HERE}/04_seed_big.sql"
fi

echo "✅ 완료. 접속:  mysql -h${HOST} -P${PORT} -u${USER} -p${PASS} shop"
