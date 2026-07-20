#!/usr/bin/env bash
#
# Step 08 — 복제와 내구성 : 실습 스크립트
#
# 실행법:
#   cd kafka/docker && bash ../step-08-replication/practice.sh
#   (한 단계씩 보려면)  bash -x ../step-08-replication/practice.sh
#
# ⚠️ 이 스크립트는 브로커를 실제로 stop/start 합니다.
#    다른 스텝의 실습과 동시에 돌리지 마세요.
#    중간에 Ctrl-C 로 멈췄다면 반드시 `docker compose up -d` 로 복구하세요.
#
set -euo pipefail

# ── 공통 헬퍼 ────────────────────────────────────────────────────────────
# 첫 인자로 "경유할 브로커"를 지정합니다.
# 8-8 에서 kafka-1 을 죽인 뒤에는 kafka-3 을 경유해야 하므로 이 형태가 필요합니다.
K() { docker exec "$1" /opt/kafka/bin/"${@:2}"; }

BS1=kafka-1:9092
BS3=kafka-3:9092

# ISR 축소를 기다리는 시간. replica.lag.time.max.ms=30000 + 여유.
# 노트북이 느려 축소가 안 잡히면 45~60 으로 올리세요.
WAIT_ISR=35

hr() { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }

# ── [8-0] 실습 준비 ─────────────────────────────────────────────────────
hr "[8-0] 실습 준비 — 브로커 3대 확인"

docker compose ps --format 'table {{.Name}}\t{{.Status}}'

echo "-- 브로커 3대가 응답하는지 확인 (3 이 나와야 정상)"
K kafka-1 kafka-broker-api-versions.sh --bootstrap-server "$BS1" | grep -cE '^kafka-[0-9]'

echo "-- 실습 토픽 생성 (파티션 1개 = 리더 추적이 쉽다)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --topic s08_dur \
  --partitions 1 --replication-factor 3 --config min.insync.replicas=1

# ── [8-1] 복제 구조 ─────────────────────────────────────────────────────
hr "[8-1] 복제 구조 — 리더 하나, 팔로워 둘"

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur

# ── [8-2] Replicas 목록과 preferred leader ─────────────────────────────
hr "[8-2] 리플리카 배치 규칙 — Replicas 첫 번째가 preferred leader"

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --topic s08_place \
  --partitions 6 --replication-factor 3

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_place
echo "-- Replicas 의 첫 숫자가 2 → 3 → 1 → 2 → 3 → 1 로 로테이션되는지 보세요."

# ── [8-3] HW 와 LEO ────────────────────────────────────────────────────
hr "[8-3] High Watermark 와 Log End Offset"

echo "-- 손으로 1건"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08_dur \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF

echo "-- 나머지 149건"
K kafka-1 kafka-producer-perf-test.sh --topic s08_dur \
  --num-records 149 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers="$BS1" acks=all

echo "-- latest(=HW)"
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08_dur --time -1
echo "-- earliest"
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08_dur --time -2

# ── [8-5] 브로커를 죽인다 — 시간순 스냅샷 3개 ──────────────────────────
hr "[8-5] kafka-2 정지 — ISR 축소를 시간순으로 관찰"

docker compose stop kafka-2

echo
echo "----- 즉시 (0초) -----"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur

echo
echo "----- 10초 후 -----"
sleep 10
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur

echo
echo "----- ${WAIT_ISR}초 후 -----"
sleep $((WAIT_ISR - 10))
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur
echo "-- Isr 가 1,2,3 → 1,3 으로 줄었는지 확인하세요 (replica.lag.time.max.ms=30000)"

echo
echo "-- 브로커 로그의 ISR 축소 메시지"
docker logs kafka-1 --since 2m 2>&1 | grep -i 'ISR' || echo '(로그 없음 — --since 를 늘려 보세요)'

echo
echo "-- under-replicated 파티션 전체 목록"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions

echo "-- kafka-2 가 리더였던 s08_place 파티션들은 이미 리더가 옮겨 갔습니다(리더 선출은 즉시)."

# ── [8-6] 되살리기 — ISR 은 돌아오지만 리더는 안 돌아온다 ──────────────
hr "[8-6] kafka-2 기동 — ISR 복구와 리더 되돌리기"

docker compose start kafka-2
sleep 20

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur
docker logs kafka-1 --since 1m 2>&1 | grep -i 'Expanding ISR' || true

echo
echo "-- s08_place: ISR 은 3개로 복구됐지만 리더는 안 돌아왔습니다"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_place

echo
echo "-- 브로커별 리더 개수 (BEFORE)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_place \
  | grep -o 'Leader: [0-9]*' | sort | uniq -c

echo
echo "-- preferred leader election 실행"
K kafka-1 kafka-leader-election.sh --bootstrap-server "$BS1" \
  --election-type preferred --all-topic-partitions

sleep 3
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_place

echo
echo "-- 브로커별 리더 개수 (AFTER — 2,2,2 여야 정상)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_place \
  | grep -o 'Leader: [0-9]*' | sort | uniq -c

# ── [8-7] 함정 A — acks=all 만으로는 부족하다 ─────────────────────────
hr "[8-7] 함정 A — min.insync.replicas=1 에서는 acks=all 도 ISR 1개로 성공한다"

docker compose stop kafka-2 kafka-3
echo "-- ISR 축소 대기 ${WAIT_ISR}초"
sleep "$WAIT_ISR"

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur
echo "-- Isr: 1 (복제본이 사실상 하나) 인지 확인"

echo
echo "===== ① min.insync.replicas=1 상태로 acks=all 전송 → 조용히 성공 ====="
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08_dur --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF'
C009:{"order_id":"O-1099","customer_id":"C009","amount":51000,"status":"CREATED"}
EOF
echo "(에러 없음 = 성공. 그런데 복사본은 이 세상에 1벌뿐입니다)"

K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08_dur --time -1

echo
echo "===== ② min.insync.replicas=2 로 변경 ====="
K kafka-1 kafka-configs.sh --bootstrap-server "$BS1" --alter \
  --entity-type topics --entity-name s08_dur --add-config min.insync.replicas=2

K kafka-1 kafka-configs.sh --bootstrap-server "$BS1" --describe \
  --entity-type topics --entity-name s08_dur

echo
echo "===== ③ 같은 명령 재실행 → NotEnoughReplicasException (에러가 나는 게 좋은 것) ====="
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08_dur --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF' || true
C009:{"order_id":"O-1100","customer_id":"C009","amount":22000,"status":"CREATED"}
EOF

echo
echo "-- 브로커 복구"
docker compose start kafka-2 kafka-3
sleep 20
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_dur

# ── [8-8] 함정 B — unclean 리더 선출 ──────────────────────────────────
hr "[8-8] 함정 B — unclean.leader.election.enable=true 가 커밋된 데이터를 버린다"

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --topic s08_unclean \
  --partitions 1 --replication-factor 2 --replica-assignment 1:2 \
  --config min.insync.replicas=1 \
  --config unclean.leader.election.enable=true

echo "-- ① 132건 (양쪽 다 갖는 구간)"
K kafka-1 kafka-producer-perf-test.sh --topic s08_unclean \
  --num-records 132 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers="$BS1" acks=all
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08_unclean --time -1

echo
echo "-- ② kafka-2 정지 후 ISR 축소 대기"
docker compose stop kafka-2
sleep "$WAIT_ISR"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08_unclean

echo
echo "-- ③ kafka-1 에만 18건 추가 (ISR={1} 이므로 HW 가 LEO 까지 올라간다)"
K kafka-1 kafka-producer-perf-test.sh --topic s08_unclean \
  --num-records 18 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers="$BS1" acks=all

BEFORE_LEO=$(K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" \
  --topic s08_unclean --time -1 | cut -d: -f3)
echo "BEFORE_LEO = $BEFORE_LEO"

echo "-- 컨슈머가 실제로 다 읽히는지 확인 (= 커밋된 데이터)"
K kafka-1 kafka-console-consumer.sh --bootstrap-server "$BS1" --topic s08_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1 || true

echo
echo "-- ④ kafka-1 정지 + kafka-2 기동 → unclean 선출 발생"
docker compose stop kafka-1
docker compose start kafka-2
sleep 30

K kafka-3 kafka-topics.sh --bootstrap-server "$BS3" --describe --topic s08_unclean
docker logs kafka-3 --since 3m 2>&1 | grep -i 'unclean' || \
  docker logs kafka-2 --since 3m 2>&1 | grep -i 'unclean' || echo '(unclean 로그를 못 찾았습니다)'

AFTER_LEO=$(K kafka-3 kafka-get-offsets.sh --bootstrap-server "$BS3" \
  --topic s08_unclean --time -1 | cut -d: -f3)
echo "AFTER_LEO = $AFTER_LEO"

K kafka-3 kafka-console-consumer.sh --bootstrap-server "$BS3" --topic s08_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1 || true

echo
echo "################################################################"
echo "#   유실: $((BEFORE_LEO - AFTER_LEO))건  (${BEFORE_LEO} → ${AFTER_LEO})"
echo "#   프로듀서는 성공했고, 컨슈머는 이미 읽었고, 에러는 없었습니다."
echo "################################################################"

echo
echo "-- ⑤ kafka-1 을 되살리면? 옛 리더가 자기 로그를 잘라냅니다(truncation)"
docker compose start kafka-1
sleep 30
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08_unclean --time -1
docker logs kafka-1 --since 2m 2>&1 | grep -i 'truncat' || echo '(truncation 로그 없음)'

# ── [8-9] 복제 성능과 rack awareness ──────────────────────────────────
hr "[8-9] 복제 관련 브로커 설정 확인"

K kafka-1 kafka-configs.sh --bootstrap-server "$BS1" --describe \
  --entity-type brokers --entity-name 1 --all 2>/dev/null \
  | grep -E 'num.replica.fetchers|replica.fetch.max.bytes|replica.lag.time.max.ms' || true

echo "-- rack 설정 확인 (이 코스는 rack: null)"
K kafka-1 kafka-broker-api-versions.sh --bootstrap-server "$BS1" | grep -E '^kafka-[0-9]'

# ── [8-11] 정리 ───────────────────────────────────────────────────────
hr "[8-11] 정리 — 토픽 삭제 + 브로커 3대 검증"

for t in s08_dur s08_place s08_unclean; do
  K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --delete --topic "$t" || true
done
sleep 3
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --list | grep '^s08_' \
  && { echo 'FAIL: s08_ 토픽이 남아 있습니다'; exit 1; } \
  || echo 'OK: s08_ 토픽 없음'

echo
echo "-- 모든 브로커 기동 (혹시 죽어 있으면 살립니다)"
docker compose up -d
sleep 15
docker compose ps --format 'table {{.Name}}\t{{.Status}}'

ALIVE=$(K kafka-1 kafka-broker-api-versions.sh --bootstrap-server "$BS1" | grep -cE '^kafka-[0-9]')
echo "살아 있는 브로커: ${ALIVE}"
if [ "$ALIVE" -ne 3 ]; then
  echo "FAIL: 브로커가 3대가 아닙니다. Step 09 로 넘어가기 전에 복구하세요."
  exit 1
fi

echo
echo "-- under-replicated 파티션 (빈 출력이 정상)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions

echo
echo "완료. 클러스터가 건강합니다. → Step 09"
