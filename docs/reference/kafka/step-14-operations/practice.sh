#!/usr/bin/env bash
# =============================================================================
# Step 14 — 운영과 최종 프로젝트 : practice.sh
#
# 실행:
#   bash step-14-operations/practice.sh
#
# ★ 이 스크립트는 브로커를 실제로 정지시킵니다 (14-2, 14-6).
#   중단(Ctrl+C)해도 trap 이 모든 브로커를 되살립니다.
#   그래도 끝난 뒤에는 반드시 'docker compose ps' 로 3대가 healthy 인지 확인하세요.
# =============================================================================
set -uo pipefail

BS=kafka-1:9092
DATA=/var/lib/kafka/data

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
S()  { docker exec kafka-1 sh -c "$1"; }

hr() { echo; echo "=============================================================="; echo "$*"; echo "=============================================================="; }

# --- 안전장치 : 어떤 경로로 끝나든 브로커를 되살립니다 -------------------------
cleanup() {
  echo
  echo ">>> [trap] 브로커 복구 중..."
  docker compose start kafka-1 kafka-2 kafka-3 >/dev/null 2>&1
}
trap cleanup EXIT

# --- 공용 대기 함수 -----------------------------------------------------------

# 컨테이너가 healthy 가 될 때까지 대기
wait_healthy() {
  local c="$1" limit="${2:-120}" waited=0
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" = "healthy" ]; do
    sleep 2; waited=$((waited+2))
    if [ "$waited" -ge "$limit" ]; then
      echo "!! $c 가 ${limit}초 안에 healthy 가 되지 않았습니다"; return 1
    fi
  done
  echo "$c healthy (${waited}s)"
}

# under-replicated 파티션이 0이 될 때까지 대기 — 롤링 재시작 ④단계의 실체
wait_isr_ok() {
  local limit="${1:-300}" waited=0 n
  while :; do
    n=$(K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions 2>/dev/null | wc -l | tr -d ' ')
    [ "$n" -eq 0 ] && { echo "복제 완료 (${waited}s)"; return 0; }
    echo "복제 따라잡는 중... $n 파티션 남음"
    sleep 3; waited=$((waited+3))
    if [ "$waited" -ge "$limit" ]; then
      # ★ 여기서 다음 브로커로 넘어가면 안 됩니다. 스로틀 잔존이나 디스크 포화를 의심하세요.
      echo "!! ${limit}초 안에 복제가 끝나지 않았습니다. 중단합니다."; return 1
    fi
  done
}

# -----------------------------------------------------------------------------
# [14-0] 실습 준비 — 클러스터 건강 확인
# -----------------------------------------------------------------------------
hr "[14-0] 브로커 상태"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'

hr "[14-0] 3종 헬스체크 (전부 0이어야 정상)"
echo -n "under-replicated : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | wc -l
echo -n "under-min-isr    : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --under-min-isr-partitions   | wc -l
echo -n "unavailable      : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --unavailable-partitions     | wc -l

# -----------------------------------------------------------------------------
# [14-1] 파티션 재할당
# -----------------------------------------------------------------------------
hr "[14-1] 불균형 토픽 s14_move 생성 (브로커 1,2 에만 배치)"
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s14_move \
    --partitions 3 --replica-assignment 1:2,1:2,1:2 --if-not-exists

# kafka-3 이 완전히 놀고, 리더가 전부 kafka-1 에 몰린 상태입니다.
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_move

hr "[14-1] 옮길 데이터 넣기 (30,000건)"
K kafka-producer-perf-test.sh --topic s14_move \
    --num-records 30000 --record-size 200 --throughput -1 \
    --producer-props bootstrap.servers="$BS"

hr "[14-1] ① --generate : 후보안 만들기"
# JSON 은 반드시 '컨테이너 안'에 만들어야 합니다. 호스트에 만들면 CLI 가 못 읽습니다.
S "cat > /tmp/topics-to-move.json <<'EOF'
{\"topics\": [{\"topic\": \"s14_move\"}], \"version\": 1}
EOF"

K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
    --topics-to-move-json-file /tmp/topics-to-move.json \
    --broker-list "1,2,3" --generate

# ★ --generate 는 무작위 시드를 써서 매번 결과가 다릅니다.
#   교재와 대조하기 위해 아래에서는 미리 정해 둔 배치를 씁니다.
#   실무에서는 위 출력의 'Proposed' 블록을 그대로 파일로 저장해 쓰세요.

hr "[14-1] rollback.json 저장  ★ 이것을 빠뜨리면 되돌릴 수 없습니다 ★"
S "cat > /tmp/rollback.json <<'EOF'
{\"version\":1,\"partitions\":[{\"topic\":\"s14_move\",\"partition\":0,\"replicas\":[1,2],\"log_dirs\":[\"any\",\"any\"]},{\"topic\":\"s14_move\",\"partition\":1,\"replicas\":[1,2],\"log_dirs\":[\"any\",\"any\"]},{\"topic\":\"s14_move\",\"partition\":2,\"replicas\":[1,2],\"log_dirs\":[\"any\",\"any\"]}]}
EOF"
S "cat /tmp/rollback.json"

hr "[14-1] 실행할 배치 reassign.json"
S "cat > /tmp/reassign.json <<'EOF'
{\"version\":1,\"partitions\":[{\"topic\":\"s14_move\",\"partition\":0,\"replicas\":[3,1],\"log_dirs\":[\"any\",\"any\"]},{\"topic\":\"s14_move\",\"partition\":1,\"replicas\":[1,2],\"log_dirs\":[\"any\",\"any\"]},{\"topic\":\"s14_move\",\"partition\":2,\"replicas\":[2,3],\"log_dirs\":[\"any\",\"any\"]}]}
EOF"

hr "[14-1] ② --execute (스로틀 1MiB/s)"
K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
    --reassignment-json-file /tmp/reassign.json \
    --throttle 1048576 --execute

hr "[14-1] ③ --verify : 완료될 때까지 반복  ★ 이것이 스로틀을 해제합니다 ★"
for i in $(seq 1 20); do
  out=$(K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
          --reassignment-json-file /tmp/reassign.json --verify 2>&1)
  echo "$out"
  echo "$out" | grep -q "still in progress" || break
  sleep 3
done

hr "[14-1] 재할당 결과 — 리더가 3개 브로커에 분산되었는지"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_move

hr "[14-1] 스로틀 잔존 검사 (0 이어야 정상)"
for b in 1 2 3; do
  echo -n "broker $b throttled configs: "
  K kafka-configs.sh --bootstrap-server "$BS" --describe \
      --entity-type brokers --entity-name "$b" 2>/dev/null | grep -c throttled
done

hr "[14-1] preferred leader 로 복원"
K kafka-leader-election.sh --bootstrap-server "$BS" \
    --election-type preferred --all-topic-partitions 2>&1 | head -3

# -----------------------------------------------------------------------------
# [14-2] 롤링 재시작 (kafka-2 한 대만 시연)
# -----------------------------------------------------------------------------
hr "[14-2] 롤링 재시작"
echo "kafka-2 를 정지했다가 되살립니다. (약 1~2분)"
read -r -p "진행할까요? [y/N] " ans
if [[ "${ans:-N}" =~ ^[Yy]$ ]]; then

  echo "--- ① 사전 점검 : under-replicated 가 0이어야 시작 가능"
  n=$(K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | wc -l | tr -d ' ')
  echo "under-replicated = $n"
  if [ "$n" -ne 0 ]; then
    echo "!! 0이 아닙니다. 롤링 재시작을 시작하면 안 됩니다."
  else
    echo "--- ② kafka-2 정지 (controlled shutdown — SIGTERM)"
    # docker compose stop 은 SIGTERM 을 보냅니다. kill -9 는 절대 쓰지 마세요.
    time docker compose stop kafka-2

    echo "--- 정지 중 상태 : Isr 에서 2가 빠진 것을 확인"
    K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | head -6

    echo "--- ③ 재시작"
    docker compose start kafka-2
    wait_healthy kafka-2

    echo "--- ④ 복제 완료 대기  ★ 이 단계를 건너뛰면 데이터를 잃습니다 ★"
    wait_isr_ok 300

    echo "--- ⑤ preferred leader 복원"
    K kafka-leader-election.sh --bootstrap-server "$BS" \
        --election-type preferred --all-topic-partitions 2>&1 | head -3

    echo "--- kafka-2 완료. 실무에서는 kafka-3, kafka-1 에 같은 절차를 반복합니다."
  fi
else
  echo "건너뜁니다."
fi

# -----------------------------------------------------------------------------
# [14-3] JMX 지표
# -----------------------------------------------------------------------------
JMXURL='service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi'

hr "[14-3] UnderReplicatedPartitions (0 이어야 정상)"
K kafka-run-class.sh kafka.tools.JmxTool \
    --object-name 'kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions' \
    --jmx-url "$JMXURL" --one-time 2>/dev/null

hr "[14-3] OfflinePartitionsCount (0 이어야 정상)"
K kafka-run-class.sh kafka.tools.JmxTool \
    --object-name 'kafka.controller:type=KafkaController,name=OfflinePartitionsCount' \
    --jmx-url "$JMXURL" --one-time 2>/dev/null

hr "[14-3] BytesInPerSec"
K kafka-run-class.sh kafka.tools.JmxTool \
    --object-name 'kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec' \
    --jmx-url "$JMXURL" --one-time 2>/dev/null

hr "[14-3] ActiveControllerCount — 세 브로커의 합이 정확히 1이어야 정상"
total=0
for c in kafka-1 kafka-2 kafka-3; do
  v=$(docker exec "$c" /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
        --object-name 'kafka.controller:type=KafkaController,name=ActiveControllerCount' \
        --jmx-url "$JMXURL" --one-time 2>/dev/null | tail -1 | awk -F, '{print $2}')
  echo "$c : ${v:-?}"
  total=$(( total + ${v:-0} ))
done
echo "합계 = $total  (1이면 정상, 0이면 컨트롤러 없음, 2 이상이면 split-brain)"

hr "[14-3] 디스크 사용량 — kafka-log-dirs.sh"
# offsetLag 이 0이면 그 복제본이 리더를 따라잡았다는 뜻입니다.
K kafka-log-dirs.sh --bootstrap-server "$BS" --describe --topic-list s14_move

# -----------------------------------------------------------------------------
# [14-6] 종합 실습 — 주문 이벤트 파이프라인
# -----------------------------------------------------------------------------
hr "[14-6] 단계 ① 토픽 설계 검증"
for t in orders payments order-events dlq; do
  K kafka-topics.sh --bootstrap-server "$BS" --describe --topic "$t" | head -1
done

hr "[14-6] 단계 ④ 주문 30건 투입 (acks=all + 멱등 + lz4)"
for i in $(seq 1001 1030); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "$c:{\"order_id\":\"O-$i\",\"customer_id\":\"$c\",\"amount\":$(( (i * 137) % 200000 )),\"status\":\"CREATED\"}"
done | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic orders \
  --property parse.key=true --property key.separator=: \
  --producer-property acks=all \
  --producer-property enable.idempotence=true \
  --producer-property compression.type=lz4 \
  --producer-property linger.ms=10

echo "투입 후 오프셋:"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic orders

hr "[14-6] 단계 ⑤ 장애 주입"
echo "브로커를 최대 2대까지 정지시킵니다."
read -r -p "진행할까요? [y/N] " ans2
if [[ "${ans2:-N}" =~ ^[Yy]$ ]]; then

  echo "--- (가-1) 브로커 1대 정지 → 쓰기는 계속 성공해야 합니다 (R6)"
  docker compose stop kafka-2
  sleep 5
  K kafka-topics.sh --bootstrap-server "$BS" --describe --topic orders

  out1=$(echo 'C001:{"order_id":"O-9001","customer_id":"C001","amount":50000,"status":"CREATED"}' \
    | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server "$BS" --topic orders \
      --property parse.key=true --property key.separator=: \
      --producer-property acks=all --producer-property enable.idempotence=true 2>&1)
  if echo "$out1" | grep -q "Exception"; then
    echo "!! 예상과 다릅니다 — 1대만 죽었을 때는 성공해야 합니다"; echo "$out1"
  else
    echo "OK — ISR 2개로 min.insync.replicas=2 를 만족하므로 쓰기 성공 (R6 충족)"
  fi

  echo
  echo "--- (가-2) 브로커 2대 정지 → 쓰기가 '거부되어야' 합니다 (R1)"
  docker compose stop kafka-3
  sleep 5

  # ★ 여기서는 에러가 나는 것이 정답입니다.
  out2=$(echo 'C001:{"order_id":"O-9002","customer_id":"C001","amount":60000,"status":"CREATED"}' \
    | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server "$BS" --topic orders \
      --property parse.key=true --property key.separator=: \
      --producer-property acks=all --producer-property enable.idempotence=true 2>&1) || true
  echo "$out2" | tail -3
  if echo "$out2" | grep -q "NotEnoughReplicas"; then
    echo "OK — NotEnoughReplicasException 으로 거부됨. 유실을 막았습니다 (R1 충족)"
  else
    echo "!! NotEnoughReplicasException 이 안 났습니다. min.insync.replicas 설정을 확인하세요."
  fi

  echo
  echo "--- 복구"
  docker compose start kafka-2 kafka-3
  wait_healthy kafka-2
  wait_healthy kafka-3
  wait_isr_ok 300
else
  echo "건너뜁니다."
fi

hr "[14-6] 단계 ⑥ 검증 체크리스트 V1~V5"
echo -n "V1 브로커 3대 healthy : "; docker compose ps --format '{{.Status}}' | grep -c healthy
echo -n "V2 under-replicated   : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | wc -l
echo -n "V3 under-min-isr      : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --under-min-isr-partitions   | wc -l
echo -n "V4 unavailable        : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --unavailable-partitions     | wc -l
echo -n "V5 스로틀 잔존        : "; K kafka-configs.sh --bootstrap-server "$BS" --describe --entity-type brokers --entity-name 1 2>/dev/null | grep -c throttled

# -----------------------------------------------------------------------------
# [14-8] 정리
# -----------------------------------------------------------------------------
hr "[14-8] s14_ 토픽 삭제"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^s14_' | while read -r t; do
  echo "삭제: $t"
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t"
done

hr "[14-8] 최종 클러스터 상태"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
echo -n "under-replicated : "; K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | wc -l

# 코스를 완전히 마쳤다면 아래로 환경을 정리합니다.
# ★ 기본값은 '아니오' 입니다. 실수로 클러스터를 날리면 코스 전체를 다시 세워야 합니다.
echo
read -r -p "코스를 마쳤습니까? 클러스터를 완전히 삭제할까요 (docker compose down -v)? [y/N] " ans3
if [[ "${ans3:-N}" =~ ^[Yy]$ ]]; then
  trap - EXIT          # 삭제할 것이므로 복구 trap 을 해제합니다
  (cd ../../docker 2>/dev/null || cd kafka/docker 2>/dev/null || true; docker compose --profile all down -v)
else
  echo "클러스터를 유지합니다."
fi

hr "practice.sh 완료"
