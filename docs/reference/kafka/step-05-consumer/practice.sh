#!/usr/bin/env bash
#
# Step 05 — 컨슈머와 컨슈머 그룹 : 본문(5-0 ~ 5-14)의 모든 명령
#
# 실행법:
#   bash practice.sh              # 통째로 실행 (8~12분)
#   bash -x practice.sh           # 한 줄씩 확인하며 실행 (권장)
#
# 이 스크립트는 본문에서 "터미널 A~D 를 여세요" 라고 한 부분을
# 백그라운드 실행(start_consumer)으로 대체합니다.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "=================== $* ==================="; }

CONSUMER_PIDS=()

# start_consumer <group> [추가 옵션...]
start_consumer() {
  local group="$1"; shift
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$BS" --topic s05_orders --group "$group" "$@" \
    > /dev/null 2>&1 &
  CONSUMER_PIDS+=("$!")
  sleep 2   # 그룹 합류를 기다립니다 (group.initial.rebalance.delay.ms=0 이라 빠릅니다)
}

# start_multi <group> <토픽정규식> [추가 옵션...]
start_multi() {
  local group="$1" pattern="$2"; shift 2
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$BS" --include "$pattern" --group "$group" "$@" \
    > /dev/null 2>&1 &
  CONSUMER_PIDS+=("$!")
  sleep 2
}

stop_all() {
  for p in "${CONSUMER_PIDS[@]:-}"; do
    kill -TERM "$p" 2>/dev/null || true
  done
  CONSUMER_PIDS=()
  # docker exec 로 띄운 자식 프로세스도 정리합니다
  docker exec kafka-1 pkill -f kafka-console-consumer 2>/dev/null || true
  sleep 3
}
trap stop_all EXIT

# ---------------------------------------------------------------------------
# [5-0] 실습 준비
# ---------------------------------------------------------------------------
hr "[5-0] 실습 준비"

for T in s05_orders s05_payments; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_orders \
  --partitions 3 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_payments \
  --partitions 3 --replication-factor 3

seed() {  # $1=토픽 $2=건수
  docker exec kafka-1 bash -c "
    for i in \$(seq 1 $2); do
      k=\"C\$(printf '%03d' \$((RANDOM % 10 + 1)))\"
      echo \"\$k:{\\\"order_id\\\":\\\"O-\$((1000+i))\\\",\\\"customer_id\\\":\\\"\$k\\\",\\\"amount\\\":\$((RANDOM % 90000 + 1000)),\\\"status\\\":\\\"CREATED\\\"}\"
    done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
      --topic $1 --property parse.key=true --property key.separator=:"
}

seed s05_orders 300
seed s05_payments 300

K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s05_orders

# ---------------------------------------------------------------------------
# [5-4] 컨슈머 그룹 만들고 랙 읽기
# ---------------------------------------------------------------------------
hr "[5-4] 랙 확인"

# 120건만 읽고 종료합니다
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s05_orders \
  --group s05-demo --from-beginning --max-messages 120 > /dev/null

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-demo

# CURRENT-OFFSET  : 그룹이 커밋한 오프셋 (다음에 읽을 위치)
# LOG-END-OFFSET  : 파티션의 마지막 오프셋 + 1
# LAG             : 아직 처리하지 못한 건수 (= LOG-END - CURRENT)
# 컨슈머가 없으면 CONSUMER-ID / HOST / CLIENT-ID 가 '-' 이고
# 맨 위에 "has no active members." 가 붙습니다.

echo "--- 끝까지 읽으면 LAG 이 0 이 됩니다 ---"
set +e
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s05_orders \
  --group s05-demo --timeout-ms 10000 > /dev/null 2>&1
set -e
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-demo

# ⚠️ --from-beginning 을 줘도 커밋된 오프셋이 있으면 그것이 우선합니다.
#    "분명 --from-beginning 을 줬는데 예전 메시지가 안 나온다" 의 원인입니다.

# ---------------------------------------------------------------------------
# [5-5] --list / --members / --state
# ---------------------------------------------------------------------------
hr "[5-5] 세 가지 관점"

start_consumer s05-demo
start_consumer s05-demo
start_consumer s05-demo

echo "--- --list ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --list

echo "--- --describe --members ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-demo --members

echo "--- --describe --members --verbose  (ASSIGNMENT 컬럼이 핵심) ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-demo --members --verbose

echo "--- --describe --state ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-demo --state

stop_all

# ---------------------------------------------------------------------------
# [5-6] 핵심 함정 A — 컨슈머 수 > 파티션 수
# ---------------------------------------------------------------------------
hr "[5-6] 함정 A — 남는 컨슈머는 논다"

# 파티션 3개짜리 토픽에 컨슈머 4개
start_consumer s05-four
start_consumer s05-four
start_consumer s05-four
start_consumer s05-four
sleep 3

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-four --members --verbose

echo
echo ">>> #PARTITIONS 가 0 인 컨슈머 (아무것도 할당받지 못한 컨슈머):"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-four --members --verbose \
  | awk 'NR>1 && $5 == 0 {print "    " $2 "  → 놀고 있습니다"}'

echo
echo ">>> --describe (파티션 기준) 에는 이 컨슈머가 아예 나오지 않습니다:"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-four

# 파티션이 병렬성의 상한입니다. 컨슈머를 10개 띄워도 7개는 유휴입니다.
# 에러도 안 나고 경고도 없습니다. 증상은 "인스턴스를 늘렸는데 랙이 그대로" 입니다.

# ---------------------------------------------------------------------------
# [5-7] 리밸런싱 관찰
# ---------------------------------------------------------------------------
hr "[5-7] 리밸런싱 — 컨슈머 하나를 죽인다"

VICTIM="${CONSUMER_PIDS[0]}"
echo "  죽일 컨슈머 PID = $VICTIM"
kill -TERM "$VICTIM" 2>/dev/null || true

for i in $(seq 1 8); do
  date '+%H:%M:%S'
  K kafka-consumer-groups.sh --bootstrap-server "$BS" \
    --describe --group s05-four --members --verbose 2>/dev/null | tail -n +2
  echo '---'
  sleep 1
done

# "Warning: Consumer group 's05-four' is rebalancing." 가 잠깐 뜨고
# 2초쯤 뒤 재배분이 끝납니다.
# ★ 놀고 있던 네 번째 컨슈머가 파티션을 이어받는 것을 확인하십시오.
#
# Ctrl+C / SIGTERM → LeaveGroup 을 보내므로 즉시 리밸런싱
# kill -9          → 하트비트가 끊긴 것을 감지할 때까지 session.timeout.ms(45초) 대기

stop_all

# ---------------------------------------------------------------------------
# [5-8] 할당 전략 — Range vs RoundRobin
# ---------------------------------------------------------------------------
hr "[5-8] Range vs RoundRobin"

echo "--- RangeAssignor (기본) — 두 토픽 구독, 컨슈머 2개 ---"
start_multi s05-range 's05_(orders|payments)'
start_multi s05-range 's05_(orders|payments)'
sleep 3
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-range --members --verbose
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-range --state
stop_all

# Range 는 토픽마다 독립적으로 계산하므로 앞쪽 컨슈머에 쏠립니다 → 4 : 2

echo "--- RoundRobinAssignor — 같은 조건 ---"
RR=org.apache.kafka.clients.consumer.RoundRobinAssignor
start_multi s05-rr 's05_(orders|payments)' --consumer-property partition.assignment.strategy=$RR
start_multi s05-rr 's05_(orders|payments)' --consumer-property partition.assignment.strategy=$RR
sleep 3
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-rr --members --verbose
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-rr --state
stop_all

# RoundRobin 은 모든 토픽의 파티션을 한 줄로 세워 배분하므로 3 : 3 으로 균등합니다.
# ⚠️ 같은 그룹에 서로 다른 전략을 섞으면 InconsistentGroupProtocolException 이 납니다.
#    그래서 그룹 이름을 s05-range / s05-rr 로 나눴습니다.

# ---------------------------------------------------------------------------
# [5-9] 핵심 함정 B — Eager vs Cooperative
# ---------------------------------------------------------------------------
hr "[5-9] 함정 B — Cooperative 리밸런싱"

COOP=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
start_consumer s05-coop --consumer-property partition.assignment.strategy=$COOP
start_consumer s05-coop --consumer-property partition.assignment.strategy=$COOP
start_consumer s05-coop --consumer-property partition.assignment.strategy=$COOP
sleep 3

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-coop --state
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-coop --members --verbose

echo "--- 하나를 죽여 점진적 리밸런싱을 봅니다 ---"
kill -TERM "${CONSUMER_PIDS[2]}" 2>/dev/null || true
for i in 1 2 3 4; do
  date '+%H:%M:%S'
  K kafka-consumer-groups.sh --bootstrap-server "$BS" \
    --describe --group s05-coop --members --verbose 2>/dev/null | tail -n +2
  echo '---'
  sleep 1
done
stop_all

# Cooperative 는 이동하는 파티션만 반납합니다.
# 컨슈머 로그에 "Revoked partitions (owned - assigned): []" 가 찍히는 것이 증거입니다.
# ⚠️ Eager → Cooperative 전환은 반드시 2단계 롤링으로:
#    1단계: partition.assignment.strategy=CooperativeSticky,Range  (전체 배포)
#    2단계: partition.assignment.strategy=CooperativeSticky        (전체 배포)

# ---------------------------------------------------------------------------
# [5-10] 핵심 함정 C — max.poll.interval.ms 초과
# ---------------------------------------------------------------------------
hr "[5-10] 함정 C — poll timeout 으로 조용히 쫓겨남"

docker cp "$(dirname "$0")/Practice.java" kafka-1:/tmp/

echo "  이 시나리오는 스스로 끝나지 않습니다(그것이 함정의 본질입니다)."
echo "  60초 동안만 돌리고 끊습니다."
echo "  실행 중에 다른 창에서 아래를 반복하면 STATE 가 요동치는 것이 보입니다:"
echo "      kcg --describe --group s05-slow --state"
echo

set +e
docker exec kafka-1 sh -c \
  'cd /tmp && timeout 60 java -cp "/opt/kafka/libs/*" Practice.java slow-consumer'
set -e

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-slow 2>/dev/null || true

# 일어난 일:
#   poll() 5건 → 처리 20초 → 10초 시점에 코디네이터가 제거
#   → 컨슈머가 스스로 LeaveGroup → commitSync() 가 CommitFailedException
#   → 재합류 → 커밋 안 된 같은 5건을 다시 받음 → 무한 반복
# LAG 이 줄지 않고 generationId 만 계속 오릅니다. 에러처럼 보이지 않습니다.
#
# 해결 순서:
#   1) max.poll.records 축소  ← 가장 먼저
#   2) 처리 최적화
#   3) max.poll.interval.ms 증가  ← 마지막 수단
#      (이 값을 늘리면 rebalance.timeout.ms 도 함께 늘어 Eager 정지 시간이 길어집니다)

echo "--- MAX_POLL_RECORDS=1 로 해결되는지 확인 ---"
set +e
docker exec -e MAX_POLL_RECORDS=1 kafka-1 sh -c \
  'cd /tmp && timeout 30 java -cp "/opt/kafka/libs/*" Practice.java slow-consumer'
set -e

# ---------------------------------------------------------------------------
# [5-12] 정적 멤버십
# ---------------------------------------------------------------------------
hr "[5-12] 정적 멤버십 (group.instance.id)"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java static-member'

echo "--- 종료 직후 상태 (STATE 가 Stable, #MEMBERS 가 1 로 남아 있습니다) ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-static --state
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-static --members --verbose

echo "--- 30초 안에 다시 실행하면 리밸런싱 없이 같은 파티션을 받습니다 ---"
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java static-member'

# ⚠️ 정적 멤버십은 진짜 장애의 감지도 늦춥니다.
#    컨슈머가 진짜 죽어도 session.timeout.ms 동안 그 파티션은 아무도 소비하지 않습니다.
#    session.timeout.ms 는 "재시작 시간 + 여유" 정도로만 잡으십시오.

# ---------------------------------------------------------------------------
# [5-13] --reset-offsets 은 Step 06 에서
# ---------------------------------------------------------------------------
hr "[5-13] --reset-offsets 맛보기"

K kafka-consumer-groups.sh --bootstrap-server "$BS" --reset-offsets \
  --group s05-demo --topic s05_orders --to-earliest --dry-run

# --dry-run 은 계산만 하고 적용하지 않습니다. 적용은 --execute.
# ⚠️ 그룹에 활성 멤버가 있으면 실패합니다:
#    Error: Assignments can only be reset if the group is inactive, but the current state is Stable.
# 오프셋 관리 전체는 Step 06 에서 다룹니다.

# ---------------------------------------------------------------------------
# [5-14] 정리
# ---------------------------------------------------------------------------
hr "[5-14] 정리"

stop_all
echo "  정적 멤버십 그룹은 session.timeout.ms 동안 멤버로 남으므로 잠시 기다립니다..."
sleep 35

for G in s05-demo s05-four s05-range s05-rr s05-coop s05-slow s05-static; do
  K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$G" 2>/dev/null \
    || echo "  $G 삭제 실패 (활성 멤버가 남아 있는지 확인)"
done

for T in s05_orders s05_payments; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3

K kafka-consumer-groups.sh --bootstrap-server "$BS" --list | grep '^s05-' \
  || echo "s05- 그룹 없음 — 정리 완료"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s05_' \
  || echo "s05_ 토픽 없음 — 정리 완료"

echo
echo "Step 05 완료. 그룹을 안 지우고 넘어가면 Step 06 에서 '왜 메시지가 안 읽히지?' 로 헤맵니다."
