#!/usr/bin/env bash
#
# Step 05 — 컨슈머와 컨슈머 그룹 : 연습문제 정답과 해설
#
# 실행법:
#   bash solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여십시오.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
hr() { echo; echo "======== $* ========"; }

PIDS=()
start_consumer() {   # start_consumer <group> [옵션...]
  local g="$1"; shift
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$BS" --topic s05_ex_orders --group "$g" "$@" >/dev/null 2>&1 &
  PIDS+=("$!")
  sleep 2
}
start_multi() {      # start_multi <group> <pattern> [옵션...]
  local g="$1" p="$2"; shift 2
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$BS" --include "$p" --group "$g" "$@" >/dev/null 2>&1 &
  PIDS+=("$!")
  sleep 2
}
stop_all() {
  for p in "${PIDS[@]:-}"; do kill -TERM "$p" 2>/dev/null || true; done
  PIDS=()
  docker exec kafka-1 pkill -f kafka-console-consumer 2>/dev/null || true
  sleep 3
}
trap stop_all EXIT

members() { K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group "$1" --members --verbose; }

seed() {
  docker exec kafka-1 bash -c "
    for i in \$(seq 1 $2); do
      k=\"C\$(printf '%03d' \$((RANDOM % 10 + 1)))\"
      echo \"\$k:{\\\"order_id\\\":\\\"O-\$((1000+i))\\\",\\\"customer_id\\\":\\\"\$k\\\",\\\"amount\\\":10000,\\\"status\\\":\\\"CREATED\\\"}\"
    done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
      --topic $1 --property parse.key=true --property key.separator=:"
}

# 준비
for T in s05_ex_orders s05_ex_payments; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_ex_orders   --partitions 3 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_ex_payments --partitions 3 --replication-factor 3
seed s05_ex_orders 200
seed s05_ex_payments 200


# ===========================================================================
# 정답 1. 컨슈머 1 → 4 개
# ===========================================================================
hr "정답 1 — 할당 변화"

for n in 1 2 3 4; do
  start_consumer s05-ex-scale
  sleep 2
  echo "=== 컨슈머 ${n}개 ==="
  members s05-ex-scale | tail -n +2 | awk '{printf "   %-30s #PARTITIONS=%s  %s\n", substr($2,1,28), $5, $6}'
done
stop_all
sleep 3
K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group s05-ex-scale 2>/dev/null || true

# 정답 (할당 분포):
#   컨슈머 1개 → [3]           한 컨슈머가 P0,P1,P2 전부
#   컨슈머 2개 → [2, 1]        Range 는 앞쪽에 더 줍니다
#   컨슈머 3개 → [1, 1, 1]     완전 균등
#   컨슈머 4개 → [1, 1, 1, 0]  ★ 네 번째는 0개
#
# 해설:
#   ★ 이 문제의 핵심은 "네 번째가 논다" 보다
#     "--describe 화면에는 네 번째 컨슈머가 아예 나오지 않는다" 는 점입니다.
#     --describe 는 "파티션" 기준으로 줄을 만들기 때문에,
#     파티션을 하나도 안 가진 컨슈머는 출력에 등장할 자리가 없습니다.
#     --members 를 봐야만 존재를 알 수 있습니다.
#
#   ★ 파티션이 병렬성의 상한입니다.
#     "인스턴스를 2배로 늘렸는데 랙이 그대로다" 의 정체가 이것이고,
#     에러도 경고도 나지 않습니다.
#
#   ★ 유휴 컨슈머가 완전히 무의미하지는 않습니다.
#     다른 컨슈머가 죽으면 즉시 이어받는 핫 스탠바이가 됩니다.
#     의도한 것이면 괜찮지만, 처리량을 늘리려고 띄웠다면 잘못된 것입니다.
#
#   ★ 컨슈머 수는 파티션 수의 약수로 두십시오.
#     파티션 6에 컨슈머 4면 2,2,1,1 로 불균등합니다.
#     오토스케일링을 쓴다면 파티션을 12나 24처럼 약수가 많은 수로 잡으십시오.


# ===========================================================================
# 정답 2. kill -TERM vs kill -9
# ===========================================================================
hr "정답 2 — 리밸런싱까지 걸리는 시간"

wait_for_members() {   # wait_for_members <group> <기대 멤버수> <타임아웃초>
  local g="$1" want="$2" limit="$3" t0
  t0=$(date +%s)
  while true; do
    local n
    n=$(K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe \
          --group "$g" --members 2>/dev/null | tail -n +2 | grep -c . || true)
    [ "$n" -eq "$want" ] && break
    [ $(( $(date +%s) - t0 )) -ge "$limit" ] && break
    sleep 1
  done
  echo $(( $(date +%s) - t0 ))
}

echo "--- SIGTERM (Ctrl+C 와 동일) ---"
start_consumer s05-ex-term; start_consumer s05-ex-term; start_consumer s05-ex-term
sleep 3
kill -TERM "${PIDS[0]}" 2>/dev/null || true
echo "  재배분 완료까지: $(wait_for_members s05-ex-term 2 90)초"
stop_all

echo "--- SIGKILL ---"
start_consumer s05-ex-kill; start_consumer s05-ex-kill; start_consumer s05-ex-kill
sleep 3
docker exec kafka-1 pkill -9 -f 'kafka-console-consumer' 2>/dev/null || true
kill -9 "${PIDS[0]}" 2>/dev/null || true
echo "  재배분 완료까지: $(wait_for_members s05-ex-kill 2 90)초"
stop_all

# 참고 결과:
#   SIGTERM :  약 1.2초
#   SIGKILL : 약 46초  (= session.timeout.ms 45000 + 알파)
#
# 해설:
#   ★ SIGTERM 은 컨슈머가 close() → LeaveGroup 을 보내고 죽습니다.
#     코디네이터가 즉시 리밸런싱을 시작하므로 1~2초입니다.
#   ★ SIGKILL 은 아무 인사도 없이 프로세스가 사라집니다.
#     코디네이터는 하트비트가 session.timeout.ms(45초) 동안 안 오는 것을
#     확인한 뒤에야 그 멤버를 제거합니다. 그 45초 동안
#     ★ 죽은 컨슈머가 맡던 파티션은 아무도 소비하지 않습니다. 랙이 쌓입니다.
#
#   ★ 실무 함의:
#     쿠버네티스의 terminationGracePeriodSeconds 가 짧으면
#     SIGTERM 직후 SIGKILL 이 날아갑니다.
#     그러면 매 배포마다 파드 하나당 45초씩 파티션이 방치됩니다.
#     컨슈머 애플리케이션은 SIGTERM 을 받아 consumer.wakeup() → close() 를
#     반드시 수행해야 하고, 그레이스 기간은 그 시간보다 넉넉해야 합니다.


# ===========================================================================
# 정답 3. LAG 최대 파티션 찾기
# ===========================================================================
hr "정답 3 — LAG 최대 파티션"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s05_ex_orders \
  --group s05-ex-lag --from-beginning --max-messages 60 >/dev/null

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-ex-lag \
  | awk 'NR>1 && $6 ~ /^[0-9]+$/ {print $6, $0}' | sort -rn | head -1

# 정답:
#   awk 'NR>1 && $6 ~ /^[0-9]+$/ {print $6, $0}' | sort -rn | head -1
#
# 해설:
#   ★ 컬럼 위치: 1 GROUP / 2 TOPIC / 3 PARTITION / 4 CURRENT-OFFSET /
#                5 LOG-END-OFFSET / 6 LAG / 7 CONSUMER-ID / 8 HOST / 9 CLIENT-ID
#     LAG 은 6번째입니다.
#   ★ NR>1 로 헤더를 걸러야 합니다.
#   ★ $6 ~ /^[0-9]+$/ 로 '-' 를 걸러야 합니다.
#     커밋된 오프셋이 없는 파티션은 CURRENT-OFFSET 과 LAG 이 '-' 로 나옵니다.
#   ★ sort -rn 은 첫 컬럼(우리가 앞에 붙인 LAG)을 숫자로 내림차순 정렬합니다.
#
#   ★ 이 한 줄이 실무에서 hot partition 을 찾는 첫 번째 명령입니다.
#     "특정 고객의 주문만 처리가 늦다" 는 신고가 들어오면
#     이 명령으로 한 파티션의 LAG 만 큰 것을 확인하고,
#     그 파티션에 어떤 키가 몰려 있는지를 봅니다 (Step 04 의 함정 C).
#     전체 LAG 평균만 보면 절대 안 보이는 문제입니다.


# ===========================================================================
# 정답 4. 할당 전략을 섞으면
# ===========================================================================
hr "정답 4 — InconsistentGroupProtocolException"

RR=org.apache.kafka.clients.consumer.RoundRobinAssignor

start_consumer s05-ex-mix     # 기본 = RangeAssignor
sleep 2

echo "  같은 그룹에 RoundRobinAssignor 컨슈머를 붙입니다:"
set +e
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server "$BS" --topic s05_ex_orders --group s05-ex-mix \
  --consumer-property partition.assignment.strategy=$RR \
  --timeout-ms 10000 2>&1 | grep -i -m2 'Exception\|incompatible' || echo "  (예외를 못 잡았습니다. 다시 시도하십시오)"
set -e
stop_all

# 정답:
#   org.apache.kafka.common.errors.InconsistentGroupProtocolException:
#     The group member's supported protocols are incompatible with those of existing members.
#
# 해설:
#   ★ 코디네이터는 "모든 멤버가 공통으로 지원하는 전략" 중 하나를 고릅니다.
#     겹치는 것이 하나도 없으면 그룹이 아예 구성되지 않습니다.
#
#   ★ 그래서 할당 전략을 바꾸는 배포는 반드시 2단계 롤링입니다:
#       1단계: partition.assignment.strategy=NewAssignor,OldAssignor   (전체 배포)
#              → 공통 전략이 여전히 OldAssignor 이므로 동작이 안 바뀝니다.
#       2단계: partition.assignment.strategy=NewAssignor               (전체 배포)
#              → 이제 공통 전략이 NewAssignor 로 바뀌며 전환됩니다.
#
#   ★ Eager(Range/RoundRobin/Sticky) → Cooperative 전환에도 똑같이 적용됩니다.
#     Cooperative 는 프로토콜 자체가 달라서, 한 번에 바꾸면
#     배포 도중 그룹이 InconsistentGroupProtocolException 으로 멈춥니다.
#     진행 상황은 --describe --state 의 ASSIGNMENT-STRATEGY 컬럼으로 확인합니다.


# ===========================================================================
# 정답 5. Range vs RoundRobin
# ===========================================================================
hr "정답 5 — 두 토픽 구독 시 불균형"

echo "--- Range (기본) ---"
start_multi s05-ex-range 's05_ex_(orders|payments)'
start_multi s05-ex-range 's05_ex_(orders|payments)'
sleep 3
members s05-ex-range
stop_all

echo "--- RoundRobin ---"
start_multi s05-ex-rr 's05_ex_(orders|payments)' --consumer-property partition.assignment.strategy=$RR
start_multi s05-ex-rr 's05_ex_(orders|payments)' --consumer-property partition.assignment.strategy=$RR
sleep 3
members s05-ex-rr
stop_all

# 정답:
#   Range      → 4 : 2
#   RoundRobin → 3 : 3
#
# 해설:
#   ★ Range 는 "토픽마다 독립적으로" 계산합니다.
#     s05_ex_orders   3파티션 / 2컨슈머 → 2, 1
#     s05_ex_payments 3파티션 / 2컨슈머 → 2, 1
#     둘 다 앞쪽 컨슈머가 2개씩 가져가므로 합쳐서 4 : 2 입니다.
#     ★ 토픽이 10개면 20 : 10 이 됩니다. 토픽이 많을수록 격차가 벌어집니다.
#
#   ★ RoundRobin 은 모든 토픽의 파티션을 한 줄로 세워 배분하므로 균등합니다.
#     대신 리밸런싱 때 기존 할당을 전혀 고려하지 않아 전부 재배치됩니다.
#
#   ★ 그래서 실무의 정답은 대개 CooperativeStickyAssignor 입니다.
#     균등(Sticky) + 기존 할당 유지 + 점진적 리밸런싱을 모두 갖췄습니다.
#     기본값이 여전히 range 인 것은 하위 호환 때문입니다.


# ===========================================================================
# 정답 6. max.poll.interval.ms 초과와 해결
# ===========================================================================
hr "정답 6 — poll timeout"

docker cp "$(dirname "$0")/Practice.java" kafka-1:/tmp/

echo "--- (a) 기본값: max.poll.records=5, 처리 4초 → 20초 > 10초 ---"
set +e
docker exec -e TOPIC=s05_ex_orders kafka-1 sh -c \
  'cd /tmp && timeout 40 java -cp "/opt/kafka/libs/*" Practice.java slow-consumer'
set -e

echo
echo "--- (b) MAX_POLL_RECORDS=1 → 한 루프 4초 < 10초 ---"
set +e
docker exec -e TOPIC=s05_ex_orders -e MAX_POLL_RECORDS=1 kafka-1 sh -c \
  'cd /tmp && timeout 30 java -cp "/opt/kafka/libs/*" Practice.java slow-consumer'
set -e

# 정답:
#   (a) WARN "consumer poll timeout has expired" →
#       "sending LeaveGroup request ... due to consumer poll timeout has expired" →
#       commitSync() 에서 CommitFailedException →
#       재합류 후 같은 5건을 다시 수신 → 무한 반복
#   (b) MAX_POLL_RECORDS=1 이면 한 루프가 4초라 타임아웃이 나지 않고,
#       커밋이 성공하며 오프셋이 정상적으로 전진합니다.
#   (c) max.poll.interval.ms 를 늘리면 rebalance.timeout.ms 가 함께 늘어나
#       Eager 리밸런싱 시 그룹 전체가 멈춰 있는 시간까지 길어지기 때문입니다.
#
# 해설:
#   ★ 왜 하트비트는 멀쩡한데 쫓겨납니까?
#     하트비트는 백그라운드 스레드가 3초마다 따로 보냅니다.
#     처리가 아무리 오래 걸려도 하트비트는 정상입니다.
#     코디네이터 입장에서 이 컨슈머는 살아 있습니다.
#     그런데 poll() 을 안 부르니 컨슈머 자신이 판단해 LeaveGroup 을 보냅니다.
#       session.timeout.ms    → 하트비트 스레드 / 코디네이터가 감지 / 45초
#       max.poll.interval.ms  → 처리 스레드     / 컨슈머가 감지     / 300초
#
#   ★ 이 문제가 무서운 이유는 "에러로 보이지 않는다" 는 것입니다.
#     프로세스는 안 죽고, 처리 로그는 계속 찍히고, 처리량 메트릭도 정상입니다.
#     같은 메시지를 반복 처리하고 있을 뿐입니다.
#     유일한 단서는 LAG 이 줄지 않는 것과 generationId 가 계속 오르는 것입니다.
#
#   ★ 해결 순서 (위에서부터):
#     1) max.poll.records 축소 — 가장 먼저. 500 → 50 이면 한 루프가 1/10.
#     2) 처리 최적화 — 건당 4초가 걸리는 원인(동기 HTTP, N+1 쿼리)을 없앱니다.
#     3) max.poll.interval.ms 증가 — 마지막 수단. (c)의 이유 때문입니다.
#     4) 처리를 스레드 풀로 넘기고 pause()/resume() — 정석이지만 오프셋 관리가 복잡 (Step 06).
#
#   ★ 계산 공식:
#     max.poll.interval.ms >= 건당 처리시간 × max.poll.records × 2
#     건당 3초 × 500건 = 1500초. 기본값 300초를 훌쩍 넘습니다.
#     이때 300 → 1500 으로 늘리는 게 아니라
#     max.poll.records 를 50 으로 줄여 150초로 맞추는 것이 정답입니다.


# ===========================================================================
# 정답 7. 정적 멤버십과 generationId
# ===========================================================================
hr "정답 7 — group.instance.id"

echo "--- 정적 멤버: 두 번 연속 실행 ---"
docker exec -e TOPIC=s05_ex_orders kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java static-member' | grep -E 'generationId|assigned'
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-static --state
docker exec -e TOPIC=s05_ex_orders kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java static-member' | grep -E 'generationId|assigned'

echo "--- 동적 멤버: 두 번 연속 실행 ---"
set +e
docker exec -e TOPIC=s05_ex_orders -e RUN_SECONDS=10 kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java consume' | grep -E 'generationId|assigned'
docker exec -e TOPIC=s05_ex_orders -e RUN_SECONDS=10 kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java consume' | grep -E 'generationId|assigned'
set -e

# 정답:
#   정적 멤버 → generationId 1, 1   (변화 없음)
#   동적 멤버 → generationId 1, 3   (내려갈 때 +1, 올라올 때 +1)
#
# 해설:
#   ★ group.instance.id 가 있으면 컨슈머는 "정적 멤버" 가 됩니다.
#     - 종료 시 LeaveGroup 을 보내지 않습니다.
#       코디네이터는 "잠깐 자리를 비웠다" 고 간주합니다.
#       그래서 종료 직후에도 --state 가 Stable / #MEMBERS=1 로 나옵니다.
#     - session.timeout.ms 안에 같은 group.instance.id 로 다시 합류하면
#       코디네이터가 이전과 똑같은 파티션을 그대로 돌려줍니다.
#       리밸런싱이 일어나지 않으므로 generationId 가 오르지 않습니다.
#
#   ★ 컨슈머 10개를 롤링 재시작하면
#     동적 멤버십: 리밸런싱 20회 (내려갈 때 10 + 올라올 때 10)
#     정적 멤버십: 리밸런싱 0회
#     Eager 프로토콜이라면 20회 × 전원 정지입니다. 차이가 큽니다.
#
#   ★ 대가:
#     진짜 크래시도 session.timeout.ms 동안 감지되지 않습니다.
#     그동안 그 파티션은 아무도 소비하지 않습니다.
#     session.timeout.ms=600000(10분) 으로 크게 잡으면 롤링은 완벽해지지만
#     진짜 장애 때 10분간 방치됩니다.
#     ★ "재시작 시간 + 여유" 정도(컨테이너 30초면 60000ms)로만 잡으십시오.
#
#   ★ group.instance.id 는 인스턴스마다 고유해야 합니다.
#     쿠버네티스라면 StatefulSet 의 파드 서수를 그대로 쓰면 됩니다.
#     Deployment 는 파드 이름이 매번 바뀌어 궁합이 나쁩니다.
#     같은 값이 동시에 두 개 뜨면:
#       org.apache.kafka.common.errors.FencedInstanceIdException


# ===========================================================================
# 정리
# ===========================================================================
hr "정리"
stop_all
sleep 35   # 정적 멤버가 빠질 때까지 대기

for G in $(K kafka-consumer-groups.sh --bootstrap-server "$BS" --list 2>/dev/null | grep -E '^s05-' || true); do
  K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$G" 2>/dev/null \
    || echo "  $G 삭제 실패 (활성 멤버 확인)"
done
for T in $(K kafka-topics.sh --bootstrap-server "$BS" --list 2>/dev/null | grep '^s05_' || true); do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3

K kafka-consumer-groups.sh --bootstrap-server "$BS" --list | grep '^s05-' \
  || echo "s05- 그룹 없음 — 정리 완료"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s05_' \
  || echo "s05_ 토픽 없음 — 정리 완료"
