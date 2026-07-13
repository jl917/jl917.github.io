#!/usr/bin/env bash
# =====================================================================
# Step 23 — 소스-레플리카 복제 구성 스크립트 (GTID 기반)
# ---------------------------------------------------------------------
#  source  : 127.0.0.1:3308  (server_id=1)
#  replica : 127.0.0.1:3309  (server_id=2)
#
#  전제: mysql8/docker/replication/docker-compose.yml 로 두 컨테이너가 떠 있어야 함
#     cd mysql8/docker/replication && docker compose up -d
#
#  실행: mysql8/step-23-backup-replication/setup-replication.sh
#
#  ※ 기존 learn-mysql8(3307)과 완전히 분리된 별도 스택입니다.
# =====================================================================
set -euo pipefail

SRC="mysql -h127.0.0.1 -P3308 -uroot -proot1234"
REP="mysql -h127.0.0.1 -P3309 -uroot -proot1234"
REPL_USER="repl"
REPL_PASS="Repl#Pass123"

echo "### 0) 두 노드가 응답할 때까지 대기"
for port in 3308 3309; do
  for i in $(seq 1 60); do
    if mysql -h127.0.0.1 -P${port} -uroot -proot1234 -e "SELECT 1" >/dev/null 2>&1; then
      echo "  - ${port} ready"; break
    fi
    sleep 2
  done
done

echo "### 1) [source] 복제 전용 계정 생성 (REPLICATION SLAVE 권한만 = 최소권한)"
$SRC <<SQL
CREATE USER IF NOT EXISTS '${REPL_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${REPL_PASS}';
GRANT REPLICATION SLAVE ON *.* TO '${REPL_USER}'@'%';
-- caching_sha2 를 비TLS 로 쓰기 위해(학습 편의) 이 계정만 예외적으로 허용.
-- 운영에서는 TLS(REQUIRE SSL) 또는 RSA 키 교환을 씁니다.
ALTER USER '${REPL_USER}'@'%' REQUIRE NONE;
FLUSH PRIVILEGES;
SQL

echo "### 2) [source] 상태 확인 (GTID, binlog)"
# -E 는 세로 출력(\G 와 동일). 일부 클라이언트는 -e 안의 \G 를 거부하므로 -E 플래그를 쓴다.
$SRC -E -e "SELECT @@server_id, @@gtid_mode, @@log_bin;" 2>/dev/null
$SRC -E -e "SHOW MASTER STATUS;" 2>/dev/null   # 8.0 에서도 여전히 SHOW MASTER STATUS

echo "### 3) [replica] 복제 소스 설정 — CHANGE REPLICATION SOURCE TO (8.0.23+ 신문법)"
# 예전: CHANGE MASTER TO ... MASTER_HOST=...
# 신규: CHANGE REPLICATION SOURCE TO ... SOURCE_HOST=...
# GTID 자동 포지셔닝이라 로그 파일명/포지션을 지정할 필요가 없다.
$REP <<SQL
STOP REPLICA;
RESET REPLICA ALL;
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='repl-source',          -- 같은 docker 네트워크 안의 컨테이너 이름으로 접속
  SOURCE_PORT=3306,                   -- 컨테이너 내부 포트는 3306
  SOURCE_USER='${REPL_USER}',
  SOURCE_PASSWORD='${REPL_PASS}',
  SOURCE_AUTO_POSITION=1,             -- ★ GTID 자동 포지셔닝
  GET_SOURCE_PUBLIC_KEY=1;            -- caching_sha2 공개키 자동 수신(학습 편의)
START REPLICA;
SQL

echo "### 4) [replica] 복제를 켠 뒤 읽기 전용으로 잠근다 (쓰기 실수 방지)"
$REP -e "SET GLOBAL super_read_only = ON;" 2>/dev/null

echo "### 5) [replica] 복제 상태 확인 — SHOW REPLICA STATUS (8.0.22+ 신용어)"
sleep 3
$REP -E -e "SHOW REPLICA STATUS;" 2>/dev/null | \
  grep -E "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Last_[IS].*Error|Retrieved_Gtid_Set|Executed_Gtid_Set|Auto_Position"

echo
echo "### 6) 복제 검증 : source 에 쓰고 replica 에서 읽힌다"
$SRC <<SQL 2>/dev/null
CREATE DATABASE IF NOT EXISTS replrepl;
USE replrepl;
CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, msg VARCHAR(50), at DATETIME);
INSERT INTO t VALUES (1, 'hello from source', NOW())
  ON DUPLICATE KEY UPDATE msg=VALUES(msg), at=NOW();
INSERT INTO t VALUES (2, 'second row', NOW())
  ON DUPLICATE KEY UPDATE msg=VALUES(msg), at=NOW();
SQL

sleep 2
echo "  [source] 행수:"
$SRC -e "SELECT COUNT(*) AS rows_on_source FROM replrepl.t" 2>/dev/null
echo "  [replica] 행수 (복제되어 보여야 함):"
$REP -e "SELECT COUNT(*) AS rows_on_replica FROM replrepl.t" 2>/dev/null
$REP -e "SELECT * FROM replrepl.t" 2>/dev/null

echo
echo "### 7) replica 는 읽기 전용임을 증명 (쓰기 거부)"
$REP -e "INSERT INTO replrepl.t VALUES (99,'should fail',NOW());" 2>&1 | grep -i "read-only\|ERROR" || true

echo
echo "=== 복제 구성 완료 ==="
echo "정리: cd mysql8/docker/replication && docker compose down -v"
