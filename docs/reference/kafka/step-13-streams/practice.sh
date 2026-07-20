#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 13 — Kafka Streams / practice.sh
#
# 실행법:
#   cd docs/reference/kafka/step-13-streams
#   bash practice.sh
#
#   한 단계씩 보려면:  bash -x practice.sh
#
# 이 스크립트는 Practice.java 를 kafka-1 컨테이너로 복사한 뒤
# 시나리오를 백그라운드로 띄우고, 데이터를 넣고, 결과를 확인하고, 정리합니다.
#
# ⚠️ Streams 앱은 기동에 15~20초가 걸립니다(리밸런싱 + 내부 토픽 생성 + 상태 복원).
#    각 run_app 뒤의 sleep 20 을 줄이면 프로듀서가 먼저 데이터를 넣어 버립니다.
# ============================================================================

BS=kafka-1:9092
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
KT() { K kafka-topics.sh --bootstrap-server "$BS" "$@"; }
KCG(){ K kafka-consumer-groups.sh --bootstrap-server "$BS" "$@"; }
KGO(){ K kafka-get-offsets.sh --bootstrap-server "$BS" "$@"; }
KCONF(){ K kafka-configs.sh --bootstrap-server "$BS" "$@"; }
hr() { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }

APP_PIDS=()

run_app() {   # run_app <scenario> [opt]
  echo "--- 앱 기동: $* ---"
  docker exec kafka-1 sh -c "cd /tmp && java -cp '/opt/kafka/libs/*' Practice.java $*" &
  APP_PIDS+=("$!")
  sleep 20
}

stop_apps() {
  docker exec kafka-1 pkill -f 'Practice' >/dev/null 2>&1 || true
  for p in "${APP_PIDS[@]:-}"; do kill "$p" >/dev/null 2>&1 || true; done
  APP_PIDS=()
  sleep 6
}

produce() {   # produce <topic>  ← stdin 으로 key:value 줄
  docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server "$BS" --topic "$1" \
    --property parse.key=true --property key.separator=:
}

consume() {   # consume <topic> [extra args...]
  local t="$1"; shift
  K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$t" \
    --from-beginning --property print.key=true --timeout-ms 5000 "$@" || true
}

# ----------------------------------------------------------------------------
# [13-0] 실습 준비
# ----------------------------------------------------------------------------
hr "[13-0] 실습 준비"

docker cp "$SCRIPT_DIR/Practice.java" kafka-1:/tmp/
echo "--- kafka-streams 가 배포판에 들어 있는지 확인 ---"
docker exec kafka-1 ls /opt/kafka/libs/ | grep -E 'kafka-streams|rocksdb'

echo "--- 사용법 출력 ---"
docker exec kafka-1 sh -c "cd /tmp && java -cp '/opt/kafka/libs/*' Practice.java" || true

echo "--- 실습 토픽 생성 ---"
KT --create --topic s13_orders   --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_payments --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_out      --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_left     --partitions 3 --replication-factor 3 --if-not-exists
# ★ s13_right 만 6파티션입니다. [13-9] 의 co-partitioning 함정을 위한 의도적 설정.
KT --create --topic s13_right    --partitions 6 --replication-factor 3 --if-not-exists

# ★ 시작 시점 토픽 목록을 기록합니다. [13-7] 의 diff 가 이 파일을 씁니다.
#   중간부터 실행하면 before/after 대비가 무의미해집니다.
KT --list | docker exec -i kafka-1 sh -c 'cat > /tmp/topics-before.txt'
echo "--- before ---"
docker exec kafka-1 cat /tmp/topics-before.txt

# ----------------------------------------------------------------------------
# [13-2] Topology — describe() 읽기
# ----------------------------------------------------------------------------
hr "[13-2] Topology"
docker exec kafka-1 sh -c "cd /tmp && java -cp '/opt/kafka/libs/*' Practice.java topology"
# 서브토폴로지가 1개 = 데이터가 네트워크를 안 거칩니다.
# 서브토폴로지 개수 = Kafka 네트워크 왕복 횟수입니다.

# ----------------------------------------------------------------------------
# [13-4] 스테이트리스 연산
#   filter / mapValues / branch / merge / peek. 내부 토픽이 하나도 안 생깁니다.
# ----------------------------------------------------------------------------
hr "[13-4] 스테이트리스"
run_app stateless

produce s13_orders <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
C002:{"order_id":"O-1002","customer_id":"C002","amount":500,"status":"CREATED"}
C001:{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CANCELLED"}
EOF
sleep 6
echo "--- s13_out (CANCELLED 는 빠지고 2건) ---"
consume s13_out

echo "--- 토픽 목록: 아직 안 늘었습니다 ---"
KT --list
stop_apps

# ----------------------------------------------------------------------------
# [13-5] selectKey → repartition 토픽이 생깁니다
# ----------------------------------------------------------------------------
hr "[13-5] selectKey 와 repartition"

echo "--- 먼저 토폴로지만 봅니다. 서브토폴로지가 둘로 쪼개집니다. ---"
docker exec kafka-1 sh -c "cd /tmp && java -cp '/opt/kafka/libs/*' Practice.java topology selectkey"

run_app selectkey
produce s13_orders <<'EOF'
C001:{"order_id":"O-2001","customer_id":"C001","amount":39000}
C002:{"order_id":"O-2002","customer_id":"C002","amount":12500}
EOF
sleep 6

echo "--- 토픽 목록: 두 개가 생겼습니다 ---"
KT --list | grep 's13-selectkey-app' || true

echo "--- repartition 토픽 (cleanup.policy=delete, RF 는 3 — baseProps 에서 지정했으므로) ---"
KT --describe --topic s13-selectkey-app-order-counts-repartition
# ⚠️ REPLICATION_FACTOR_CONFIG 을 안 줬다면 여기 ReplicationFactor 가 1 입니다.
#    Streams 기본값이 1 이라서입니다. 브로커 기본값(3)을 따르지 않습니다.
stop_apps

# ----------------------------------------------------------------------------
# [13-6] 집계와 상태 저장소
# ----------------------------------------------------------------------------
hr "[13-6] 집계와 상태 저장소"
run_app count

produce s13_orders <<'EOF'
C001:{"order_id":"O-3001","amount":39000}
C002:{"order_id":"O-3002","amount":12500}
C001:{"order_id":"O-3003","amount":88000}
C001:{"order_id":"O-3004","amount":5000}
EOF
sleep 6

echo "--- s13_out: C001 이 1,2,3 으로 세 번 나옵니다 (KTable = 변경로그) ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s13_out --from-beginning \
  --property print.key=true \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer \
  --timeout-ms 5000 || true

echo "--- 로컬 RocksDB 디렉터리 (0_0/0_1/0_2 는 태스크 ID = <서브토폴로지>_<파티션>) ---"
docker exec kafka-1 find /tmp/kafka-streams -maxdepth 4 -type d | head -12

echo "--- changelog 토픽: cleanup.policy=compact 입니다 (repartition 과 다름) ---"
KT --describe --topic s13-count-app-order-counts-changelog

echo "--- changelog 내용 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" \
  --topic s13-count-app-order-counts-changelog --from-beginning \
  --property print.key=true \
  --value-deserializer org.apache.kafka.common.serialization.LongDeserializer \
  --timeout-ms 5000 || true

echo "--- application.id 가 곧 컨슈머 그룹 ID 입니다 ---"
KCG --list
KCG --describe --group s13-count-app
stop_apps

# ----------------------------------------------------------------------------
# [13-8] 윈도우 + grace period (함정 B)
# ----------------------------------------------------------------------------
hr "[13-8] 윈도우와 grace"
echo "--- grace 초과 레코드를 주입합니다. Java 로만 가능합니다(타임스탬프 지정). ---"
docker exec kafka-1 sh -c "cd /tmp && timeout 90 java -cp '/opt/kafka/libs/*' Practice.java window --inject-late" || true
# ★ 마지막 99000 이 합계에 안 들어갑니다. 예외도 로그도 없습니다.
#   dropped-records-total 지표가 1 인 것만이 증거입니다.
stop_apps

hr "[13-8b] suppress — 최종 결과만"
echo "--- suppress 는 '다음 레코드'가 와야 방출합니다. 저트래픽에서 안 나오는 이유입니다. ---"
run_app suppress
produce s13_orders <<'EOF'
C001:{"order_id":"O-4001","amount":10000}
C002:{"order_id":"O-4002","amount":5000}
EOF
sleep 10
echo "(윈도우가 안 닫혀서 아직 아무것도 안 나옵니다 — 이게 정상입니다)"
stop_apps

# ----------------------------------------------------------------------------
# [13-9] 조인
# ----------------------------------------------------------------------------
hr "[13-9] 조인"
run_app join

produce s13_orders <<'EOF'
C001:{"order_id":"O-5001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF
sleep 3
produce s13_payments <<'EOF'
O-5001:{"order_id":"O-5001","method":"CARD","amount":39000,"result":"APPROVED"}
EOF
sleep 6
echo "--- 조인 결과 ---"
consume s13_out
stop_apps

hr "[13-9b] 함정 C — co-partitioning 위반"
echo "--- s13_left(3) × s13_right(6) → TopologyException ---"
docker exec kafka-1 sh -c "cd /tmp && timeout 45 java -cp '/opt/kafka/libs/*' Practice.java copartition" 2>&1 | tail -12 || true
stop_apps

echo "--- --fix: repartition 으로 파티션 수를 맞춥니다 ---"
docker exec kafka-1 sh -c "cd /tmp && timeout 45 java -cp '/opt/kafka/libs/*' Practice.java copartition --fix" 2>&1 | head -30 || true
stop_apps

# ----------------------------------------------------------------------------
# [13-10] EOS
# ----------------------------------------------------------------------------
hr "[13-10] exactly_once_v2"
docker exec kafka-1 sh -c "cd /tmp && timeout 45 java -cp '/opt/kafka/libs/*' Practice.java eos" 2>&1 | head -25 || true
stop_apps

# ----------------------------------------------------------------------------
# [13-7] 함정 A — 내부 토픽이 자동으로 생깁니다 (before/after 비교)
# ----------------------------------------------------------------------------
hr "[13-7] 함정 A — 자동 생성된 내부 토픽"
KT --list | docker exec -i kafka-1 sh -c 'cat > /tmp/topics-after.txt'
echo "--- diff (좌: before / 우: after) ---"
docker exec kafka-1 sh -c 'diff /tmp/topics-before.txt /tmp/topics-after.txt' || true

echo "--- 그런데 자동 토픽 생성은 꺼져 있습니다 ---"
KCONF --describe --entity-type brokers --entity-name 1 | grep auto.create || true
# ★ Streams 는 프로듀서/컨슈머의 자동 생성 경로를 안 씁니다.
#   InternalTopicManager 가 AdminClient 로 CreateTopics API 를 직접 호출합니다.
#   사용자가 손으로 kt --create 하는 것과 완전히 같은 경로라 auto.create 로 못 막습니다.
#   Connect(Step 12)의 내부 토픽도 같은 방식입니다.

# ----------------------------------------------------------------------------
# [13-11] 정리 — 세 단계입니다
#   ① 앱 종료  ② reset 도구(Kafka 쪽)  ③ 로컬 상태 디렉터리(★ 빼먹기 쉬움)
# ----------------------------------------------------------------------------
hr "[13-11] 정리"

echo "--- ① 앱 전부 종료 ---"
stop_apps
KCG --list

echo "--- ② kafka-streams-application-reset.sh ---"
for app in s13-stateless-app s13-selectkey-app s13-count-app s13-window-app \
           s13-suppress-app s13-join-app s13-copart-app s13-eos-app; do
  echo "  reset: $app"
  K kafka-streams-application-reset.sh --bootstrap-server "$BS" \
     --application-id "$app" --input-topics s13_orders 2>&1 | tail -3 || true
done

echo "--- 컨슈머 그룹 삭제 (reset 도구는 오프셋만 되돌리고 그룹은 남깁니다) ---"
for g in $(KCG --list | grep '^s13-' || true); do KCG --delete --group "$g" || true; done
KCG --list

echo "--- ③ 로컬 상태 디렉터리 (★ reset 도구가 안 지웁니다) ---"
docker exec kafka-1 du -sh /tmp/kafka-streams 2>/dev/null || echo "없음"
docker exec kafka-1 rm -rf /tmp/kafka-streams
docker exec kafka-1 ls /tmp/kafka-streams 2>&1 || echo "삭제 완료"
# ⚠️ ③ 을 빼먹으면 다음 실행 때 옛 RocksDB 상태가 되살아나
#    "오프셋은 0 인데 카운트는 이어지는" 상태가 됩니다. 에러는 없습니다.

echo "--- 실습 토픽 삭제 ---"
for t in $(KT --list | grep -E '^s13' || true); do KT --delete --topic "$t" || true; done
KT --list | grep -E '^s13' || echo "s13 토픽 없음"

docker exec kafka-1 rm -f /tmp/Practice.java /tmp/topics-before.txt /tmp/topics-after.txt

hr "practice.sh 완료"
