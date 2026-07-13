#!/usr/bin/env bash
# =====================================================================
# Step 23 — 논리 백업(mysqldump)과 복구 실습
# ---------------------------------------------------------------------
#  대상: 학습용 컨테이너 learn-mysql8 (127.0.0.1:3307) 의 shop DB
#  결과물: mysql8/backup/ 에 저장 (덤프 파일은 .gitignore 로 커밋 제외)
#
#  ※ 원본 shop 은 절대 건드리지 않습니다.
#     복구는 별도 DB shop_restore_test 로만 하고, 끝나면 지웁니다.
#
#  실행: mysql8/step-23-backup-replication/backup-restore.sh
# =====================================================================
set -euo pipefail

HOST=127.0.0.1
PORT=3307
USER=root
PASS=root1234
DB=shop

# 이 스크립트 기준 상대경로로 backup 디렉터리 결정
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${SCRIPT_DIR}/../backup"
mkdir -p "${BACKUP_DIR}"
STAMP="$(date +%Y%m%d_%H%M%S)"
DUMP="${BACKUP_DIR}/shop_${STAMP}.sql"

MYSQL="mysql -h${HOST} -P${PORT} -u${USER} -p${PASS}"
DUMP_CMD="mysqldump -h${HOST} -P${PORT} -u${USER} -p${PASS}"

echo "### 1) 논리 백업 — mysqldump 주요 옵션 총동원"
# 옵션 해설:
#   --single-transaction : InnoDB 를 잠그지 않고 "일관된 스냅샷"을 뜬다.
#                          내부적으로 REPEATABLE READ 트랜잭션을 열고 덤프한다.
#                          → 서비스 무중단 백업의 핵심. (MyISAM 에는 효과 없음)
#   --routines           : 저장 프로시저/함수(Step 20)도 포함
#   --triggers           : 트리거 포함 (기본 ON 이지만 명시)
#   --events             : 이벤트 스케줄러(Step 20)도 포함
#   --set-gtid-purged=AUTO: GTID 환경이면 복구 시 필요한 SET @@GTID_PURGED 를 넣는다.
#                          (복제 레플리카를 백업으로 초기 구성할 때 필수)
#   --hex-blob           : 바이너리/JSON 을 16진수로 안전하게 덤프
#   --default-character-set=utf8mb4 : 한글 깨짐 방지
#   --databases ${DB}    : USE/CREATE DATABASE 문까지 포함(단일 테이블만 뜰 땐 생략)
${DUMP_CMD} \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=AUTO \
  --hex-blob \
  --default-character-set=utf8mb4 \
  --databases ${DB} > "${DUMP}"

echo "  백업 파일: ${DUMP}"
echo "  크기: $(du -h "${DUMP}" | cut -f1)"
echo "  머리 부분(메타/GTID):"
grep -E "^-- (Host|Server version)|SET @@GLOBAL.GTID_PURGED|^CREATE DATABASE" "${DUMP}" | head -5 || true

echo
echo "### 2) 복구 — 별도 DB(shop_restore_test)로 안전하게 복구"
# 덤프에 'USE shop' 이 들어있으므로, 이름을 바꿔 복구하려면 sed 로 DB명을 치환하거나
# --databases 없이 뜬 덤프를 새 DB에 넣는다. 여기서는 치환 방식으로 시연.
RESTORE_DB=shop_restore_test
${MYSQL} -e "DROP DATABASE IF EXISTS ${RESTORE_DB}; CREATE DATABASE ${RESTORE_DB} CHARACTER SET utf8mb4;"
# CREATE/USE shop → shop_restore_test 로 치환하고, GTID_PURGED 라인은 복구 대상이 아니므로 제거
sed -e "s/\`${DB}\`/\`${RESTORE_DB}\`/g" \
    -e "/SET @@GLOBAL.GTID_PURGED/d" \
    -e "/SET @@SESSION.SQL_LOG_BIN/d" \
    "${DUMP}" | ${MYSQL} ${RESTORE_DB}

echo "  복구 검증 — 행수 비교(원본 vs 복구본):"
${MYSQL} -t -e "
SELECT 'orders' t, (SELECT COUNT(*) FROM ${DB}.orders) src, (SELECT COUNT(*) FROM ${RESTORE_DB}.orders) restored
UNION ALL SELECT 'customers', (SELECT COUNT(*) FROM ${DB}.customers), (SELECT COUNT(*) FROM ${RESTORE_DB}.customers)
UNION ALL SELECT 'products',  (SELECT COUNT(*) FROM ${DB}.products),  (SELECT COUNT(*) FROM ${RESTORE_DB}.products)
UNION ALL SELECT 'access_logs',(SELECT COUNT(*) FROM ${DB}.access_logs),(SELECT COUNT(*) FROM ${RESTORE_DB}.access_logs);"

echo
echo "### 3) 단일 테이블만 백업/복구하는 예 (부분 복구)"
SINGLE="${BACKUP_DIR}/customers_${STAMP}.sql"
${DUMP_CMD} --single-transaction --default-character-set=utf8mb4 ${DB} customers > "${SINGLE}"
echo "  customers 만 덤프: ${SINGLE} ($(du -h "${SINGLE}" | cut -f1))"

echo
echo "### 4) 뒷정리 — 복구 테스트 DB 삭제 (백업 파일은 backup/ 에 남김)"
${MYSQL} -e "DROP DATABASE IF EXISTS ${RESTORE_DB};"
echo "  ${RESTORE_DB} 삭제 완료"

echo
echo "=== 백업/복구 실습 완료 ==="
echo "생성된 백업 파일 (git 에는 커밋되지 않음 → mysql8/.gitignore):"
ls -lh "${BACKUP_DIR}"/*.sql 2>/dev/null | awk '{print "  "$5"\t"$NF}'
