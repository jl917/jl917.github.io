#!/usr/bin/env bash
# =============================================================================
# Step 14 — 운영과 최종 프로젝트 : solution.sh  (정답 + 해설)
#
# 실행:
#   bash step-14-operations/solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
# ★ 문제 3, 5 는 브로커를 정지시킵니다. trap 이 복구합니다.
# =============================================================================
set -uo pipefail

BS=kafka-1:9092
JMXURL='service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi'

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
S() { docker exec kafka-1 sh -c "$1"; }

hr() { echo; echo "=============================================================="; echo "$*"; echo "=============================================================="; }

cleanup() { echo; echo ">>> [trap] 브로커 복구 중..."; docker compose start kafka-1 kafka-2 kafka-3 >/dev/null 2>&1; }
trap cleanup EXIT

wait_healthy() {
  local c="$1" limit="${2:-120}" waited=0
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" = "healthy" ]; do
    sleep 2; waited=$((waited+2))
    [ "$waited" -ge "$limit" ] && { echo "!! $c healthy 실패"; return 1; }
  done
  echo "$c healthy (${waited}s)"
}

# =============================================================================
# 정답 1 — 불균형 토픽 생성 후 3대에 재할당
# =============================================================================
hr "정답 1"

# (1-a)
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s14_ex \
    --partitions 3 --replica-assignment 1:2,1:2,1:2 --if-not-exists
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex

# (1-b)
S "cat > /tmp/topics-to-move-ex.json <<'EOF'
{\"topics\": [{\"topic\": \"s14_ex\"}], \"version\": 1}
EOF"

GEN=$(K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
        --topics-to-move-json-file /tmp/topics-to-move-ex.json \
        --broker-list "1,2,3" --generate 2>/dev/null)
echo "$GEN"

# (1-c) ★ Current 블록을 롤백 파일로 저장
#   --generate 출력은 두 블록입니다. 'Current' 다음 줄의 JSON 을 뽑습니다.
CUR=$(echo "$GEN" | grep -A2 'Current partition replica assignment' | grep '^{')
S "cat > /tmp/rollback_ex.json <<'EOF'
$CUR
EOF"
echo
echo "저장된 rollback_ex.json:"
S "cat /tmp/rollback_ex.json"

# (1-d) Proposed 블록으로 실행
PRO=$(echo "$GEN" | grep -A2 'Proposed partition reassignment configuration' | grep '^{')
S "cat > /tmp/reassign_ex.json <<'EOF'
$PRO
EOF"

K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
    --reassignment-json-file /tmp/reassign_ex.json --execute

for i in $(seq 1 20); do
  out=$(K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
          --reassignment-json-file /tmp/reassign_ex.json --verify 2>&1)
  echo "$out" | grep -q "still in progress" || { echo "$out"; break; }
  sleep 2
done

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex

# 해설:
#   ★ (1-c) 를 빠뜨리면 되돌릴 수 없습니다. 이것이 이 문제의 핵심입니다.
#
#   왜 "나중에 --generate 를 다시 돌리면 되지 않나?" 가 안 되는가:
#   --generate 는 브로커 목록을 받아 파티션을 배분할 때 무작위 시드를 씁니다.
#   같은 입력으로 두 번 돌려도 결과가 다릅니다. 즉 '원래 배치' 를 재현할 방법이
#   저장해 둔 파일밖에 없습니다.
#
#   그리고 원래 배치를 모르면 왜 곤란한가:
#   재할당 중에 성능 문제나 디스크 부족이 터지면 즉시 되돌려야 하는데,
#   되돌릴 목표 상태를 모르면 손으로 JSON 을 짜야 합니다.
#   파티션이 수백 개인 운영 토픽에서는 사실상 불가능합니다.
#
#   실무 절차로 굳히세요:
#     --generate  →  Current 를 rollback.json 으로 저장  →  --execute  →  --verify


# =============================================================================
# 정답 2 — 스로틀 잔존 확인과 수동 해제   ★ 이 문제지의 핵심 ★
# =============================================================================
hr "정답 2"

# (2-a) 스로틀을 걸고 재할당. --verify 는 일부러 하지 않습니다.
#   원래 배치로 되돌리는 재할당을 스로틀과 함께 실행합니다.
K kafka-reassign-partitions.sh --bootstrap-server "$BS" \
    --reassignment-json-file /tmp/rollback_ex.json \
    --throttle 1024 --execute 2>&1 | tail -3

sleep 3

# (2-b) 브로커에 스로틀이 남아 있는지 확인
echo
echo "--- 브로커 스로틀 설정 ---"
for b in 1 2 3; do
  echo "[broker $b]"
  K kafka-configs.sh --bootstrap-server "$BS" --describe \
      --entity-type brokers --entity-name "$b" 2>/dev/null | grep throttled || echo "  (없음)"
done

# (2-c) 브로커 스로틀 수동 해제
echo
echo "--- 브로커 스로틀 해제 ---"
for b in 1 2 3; do
  K kafka-configs.sh --bootstrap-server "$BS" --alter \
      --entity-type brokers --entity-name "$b" \
      --delete-config leader.replication.throttled.rate,follower.replication.throttled.rate 2>/dev/null \
    && echo "broker $b 해제 완료"
done

# (2-d) 토픽 쪽 스로틀도 남습니다
echo
echo "--- 토픽 스로틀 설정 ---"
K kafka-configs.sh --bootstrap-server "$BS" --describe \
    --entity-type topics --entity-name s14_ex 2>/dev/null | grep throttled || echo "  (없음)"

K kafka-configs.sh --bootstrap-server "$BS" --alter \
    --entity-type topics --entity-name s14_ex \
    --delete-config leader.replication.throttled.replicas,follower.replication.throttled.replicas 2>/dev/null \
  && echo "토픽 스로틀 해제 완료"

echo
echo "--- 최종 확인 (0 이어야 정상) ---"
for b in 1 2 3; do
  echo -n "broker $b: "
  K kafka-configs.sh --bootstrap-server "$BS" --describe --entity-type brokers --entity-name "$b" 2>/dev/null | grep -c throttled
done

# 해설:
#   해제해야 할 설정은 브로커 2개 + 토픽 2개, 총 4개입니다.
#
#     브로커 : leader.replication.throttled.rate
#              follower.replication.throttled.rate
#     토픽   : leader.replication.throttled.replicas
#              follower.replication.throttled.replicas
#
#   토픽 쪽을 잊는 경우가 많습니다. 브로커 rate 를 지워도 토픽의 replicas 목록이
#   남아 있으면 "어떤 복제본이 스로틀 대상인가" 라는 표시가 계속 붙어 있게 됩니다.
#
#   ★ 왜 이 함정이 그렇게 지독한가:
#
#   증상이 '평소에는 전혀 안 나타납니다'.
#   스로틀은 '복제' 대역폭만 제한하므로, 정상 상태에서는 복제할 것이 거의 없어
#   1KiB/s 제한이 걸려 있어도 아무 문제가 없습니다. 지표도 전부 초록입니다.
#
#   드러나는 순간은 정확히 "복제를 몰아쳐야 할 때" 입니다.
#     - 브로커를 재시작한 뒤 따라잡기
#     - 장애 복구 후 ISR 회복
#     - 새 브로커 추가 후 데이터 이동
#
#   이때 under-replicated 가 몇 시간, 며칠씩 안 풀립니다.
#   디스크도 네트워크도 CPU 도 한가한데 복제만 안 됩니다.
#   에러 로그는 한 줄도 없습니다. 원인이 몇 달 전에 누가 --verify 를 빼먹은 것이라
#   추적이 거의 불가능합니다.
#
#   그래서 Kafka 가 --execute 출력에 경고를 박아 둔 것입니다:
#     "Warning: You must run --verify periodically, until the reassignment
#      completes, to ensure the throttle is removed."
#
#   운영 규칙으로 만드세요: 재할당 스크립트는 --verify 루프까지 포함해야 완성입니다.


# =============================================================================
# 정답 3 — 롤링 재시작 + 복제 완료 대기 루프
# =============================================================================
hr "정답 3"

# (3-a) 사전 점검
n=$(K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions | wc -l | tr -d ' ')
echo "사전 under-replicated = $n"

if [ "$n" -ne 0 ]; then
  echo "!! 0이 아니므로 롤링 재시작을 시작하면 안 됩니다. 건너뜁니다."
else
  # (3-b)
  echo "--- kafka-3 정지 (SIGTERM = controlled shutdown)"
  docker compose stop kafka-3

  # (3-c)
  echo "--- kafka-3 재시작"
  docker compose start kafka-3
  wait_healthy kafka-3

  # (3-d) ★ 복제 완료 대기 루프 — 타임아웃 상한 포함
  echo "--- 복제 완료 대기"
  limit=300; waited=0; ok=0
  while :; do
    m=$(K kafka-topics.sh --bootstrap-server "$BS" --describe --under-replicated-partitions 2>/dev/null | wc -l | tr -d ' ')
    if [ "$m" -eq 0 ]; then echo "복제 완료 (${waited}s)"; ok=1; break; fi
    echo "  따라잡는 중... $m 파티션 남음"
    sleep 3; waited=$((waited+3))
    if [ "$waited" -ge "$limit" ]; then
      echo "!! ${limit}초 초과. 다음 브로커로 진행하면 안 됩니다."
      break
    fi
  done

  # (3-e)
  if [ "$ok" -eq 1 ]; then
    K kafka-leader-election.sh --bootstrap-server "$BS" \
        --election-type preferred --all-topic-partitions 2>&1 | head -3
  fi
fi

# 해설:
#   판정을 왜 wc -l 로 하는가:
#
#   --under-replicated-partitions 는 정상일 때 '아무것도 출력하지 않습니다'.
#   그래서 이렇게 쓰면 틀립니다:
#
#       if [ -z "$(... --under-replicated-partitions)" ]; then   # 위험
#
#   명령이 실패하거나(브로커 접속 불가) stderr 로 경고가 나오는 경우
#   빈 문자열이 되어 "정상" 으로 오판할 수 있습니다.
#   wc -l 은 줄 수를 세므로 판정이 명확하고, 숫자 비교라 공백 문제도 없습니다.
#
#   ★ 타임아웃 상한이 왜 필수인가:
#
#   복제가 '영영 안 끝나는' 상황이 실제로 있습니다.
#     - 정답 2 의 스로틀 잔존
#     - 디스크 포화로 로그 디렉터리가 오프라인
#     - 네트워크 문제로 팔로워가 계속 뒤처짐
#
#   상한이 없으면 자동화 스크립트가 여기서 영원히 멈춥니다.
#   그리고 더 중요한 것 — 상한을 넘겼을 때 '다음 브로커로 진행하면 안 됩니다'.
#   진행하면 ISR 이 1로 줄고, min.insync.replicas 설정에 따라
#   서비스가 멈추거나(2) 데이터를 잃습니다(1).
#   반드시 중단하고 사람이 원인을 봐야 합니다.


# =============================================================================
# 정답 4 — JMX 3종 헬스체크
# =============================================================================
hr "정답 4"

jmx_val() {
  local container="$1" mbean="$2"
  docker exec "$container" /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
    --object-name "$mbean" --jmx-url "$JMXURL" --one-time 2>/dev/null \
    | tail -1 | awk -F, '{print $2}'
}

# (4-a)
urp=$(jmx_val kafka-1 'kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions')
echo "UnderReplicatedPartitions = ${urp:-?}   (0 이어야 정상)"

# (4-b)
off=$(jmx_val kafka-1 'kafka.controller:type=KafkaController,name=OfflinePartitionsCount')
echo "OfflinePartitionsCount    = ${off:-?}   (0 이어야 정상)"

# (4-c) ★ 세 브로커의 합
total=0
for c in kafka-1 kafka-2 kafka-3; do
  v=$(jmx_val "$c" 'kafka.controller:type=KafkaController,name=ActiveControllerCount')
  echo "  $c ActiveControllerCount = ${v:-0}"
  total=$(( total + ${v:-0} ))
done
echo "ActiveControllerCount 합계 = $total   (1 이어야 정상)"

echo
if [ "${urp:-1}" -eq 0 ] && [ "${off:-1}" -eq 0 ] && [ "$total" -eq 1 ]; then
  echo "HEALTHY"
else
  echo "UNHEALTHY"
fi

# 해설:
#   ★ ActiveControllerCount 를 한 브로커에서만 재면 안 되는 이유:
#
#   이 지표는 "내가 액티브 컨트롤러인가" 를 나타냅니다.
#   컨트롤러인 노드에서만 1이고, 나머지 노드에서는 0입니다.
#
#   그래서 kafka-1 에서만 쟀는데 0이 나왔다면 두 가지 해석이 가능합니다.
#     (가) 클러스터는 정상이고, 컨트롤러가 kafka-2 나 kafka-3 이다
#     (나) 컨트롤러가 아예 없다 (쿼럼 상실 = 장애)
#
#   한 노드만 봐서는 이 둘을 구분할 수 없습니다.
#   반드시 '전체 노드의 합' 을 봐야 판정이 됩니다.
#
#   판정 기준:
#     합 == 1  → 정상
#     합 == 0  → 컨트롤러 없음. 쿼럼 과반이 깨진 상태. 메타데이터 변경 불가
#                (토픽 생성/삭제, 리더 선출이 전부 멈춤)
#     합 >= 2  → split-brain. 두 노드가 각자 자기가 컨트롤러라고 믿는 중.
#                메타데이터가 갈라지고 복구 시 한쪽 변경이 통째로 버려짐
#
#   KRaft 는 Raft 쿼럼(과반수)으로 split-brain 을 구조적으로 막습니다.
#   그러려면 controller.quorum.voters 가 '홀수' 여야 합니다 (3 또는 5).
#   짝수(예: 4)면 2:2 로 갈렸을 때 어느 쪽도 과반이 아니라
#   컨트롤러가 아예 안 서는 상황이 됩니다. 가용성만 나빠지고 이득이 없습니다.


# =============================================================================
# 정답 5 — min.insync.replicas 1 vs 2
# =============================================================================
hr "정답 5"

# (5-a)
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s14_ex_isr1 \
    --partitions 1 --replication-factor 3 --config min.insync.replicas=1 --if-not-exists
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s14_ex_isr2 \
    --partitions 1 --replication-factor 3 --config min.insync.replicas=2 --if-not-exists

# 두 토픽 모두 리더가 kafka-1 이 되도록 잠시 대기 후 확인
sleep 2
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex_isr1
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex_isr2

# (5-b) 브로커 2대 정지
echo
echo "--- kafka-2, kafka-3 정지 (ISR 이 1개로 줄어듭니다)"
docker compose stop kafka-2 kafka-3
sleep 8

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex_isr1
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s14_ex_isr2

# (5-c) minISR=1 토픽에 쓰기
echo
echo "--- s14_ex_isr1 (min.insync.replicas=1) 에 acks=all 로 쓰기"
r1=$(echo 'k1:{"test":"isr1"}' | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server "$BS" --topic s14_ex_isr1 \
      --property parse.key=true --property key.separator=: \
      --producer-property acks=all 2>&1) || true
if echo "$r1" | grep -q "Exception"; then
  echo "결과: 실패"; echo "$r1" | tail -2
else
  echo "결과: ★ 성공 ★  (ISR 이 1개뿐인데도 acks=all 이 성공했습니다)"
fi

# (5-d) minISR=2 토픽에 쓰기
echo
echo "--- s14_ex_isr2 (min.insync.replicas=2) 에 acks=all 로 쓰기"
r2=$(echo 'k1:{"test":"isr2"}' | docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server "$BS" --topic s14_ex_isr2 \
      --property parse.key=true --property key.separator=: \
      --producer-property acks=all 2>&1) || true
if echo "$r2" | grep -q "NotEnoughReplicas"; then
  echo "결과: 거부됨 (NotEnoughReplicasException)"
  echo "$r2" | grep NotEnoughReplicas | head -1
else
  echo "결과: 예상과 다름"; echo "$r2" | tail -2
fi

# (5-e) 복구
echo
echo "--- 복구"
docker compose start kafka-2 kafka-3
wait_healthy kafka-2
wait_healthy kafka-3

# 해설:
#   결과 대조표
#
#     토픽            min.insync  ISR 상태   acks=all 결과
#     -------------------------------------------------------------
#     s14_ex_isr1     1           1개        ★ 성공 ★
#     s14_ex_isr2     2           1개        거부 (NotEnoughReplicas)
#
#   ★ 답: 성공한 쪽(minISR=1)이 훨씬 위험합니다.
#
#   왜 그런가. acks=all 의 실제 의미를 정확히 보면 답이 나옵니다.
#
#     acks=all 은 "모든 복제본이 받을 때까지" 가 아닙니다.
#     "현재 ISR 에 있는 복제본 전부가 받을 때까지" 입니다.
#
#   ISR 이 1개로 쪼그라든 상태에서는 'ISR 전부' 가 곧 '리더 혼자' 입니다.
#   즉 acks=all 이 사실상 acks=1 과 똑같이 동작합니다.
#   프로듀서는 성공 응답을 받고, 애플리케이션은 커밋을 진행하고,
#   사용자에게는 "주문이 접수되었습니다" 가 나갑니다.
#
#   그리고 그 마지막 한 대가 죽으면 그 메시지는 사라집니다.
#   복제본이 하나도 없었으니까요.
#   에러 로그는 없습니다. 프로듀서는 이미 성공했다고 믿고 떠났습니다.
#
#   min.insync.replicas=2 는 이 구멍을 막습니다.
#   "복제본이 2개 미만이면 아예 받지 않겠다" 고 선언하는 것입니다.
#   서비스는 멈추지만 데이터는 안전합니다.
#
#   ★ 이 코스 전체를 한 줄로 요약하면 이것입니다:
#     "성공하는 쪽이 위험한 쪽이다."
#
#   RF 3 기준 조합표:
#     minISR=1 → 1대만 살아도 쓰기 성공. 가용성 최고, 유실 위험 있음
#     minISR=2 → 2대 필요. 1대 죽어도 서비스 계속, 2대 죽으면 거부. ★ 권장 ★
#     minISR=3 → 3대 전부 필요. 1대만 죽어도 서비스 정지. 과도함


# =============================================================================
# 정답 6 — V1~V5 자동 검증 스크립트
# =============================================================================
hr "정답 6"

run_checks() {
  local failed=()

  # V1 은 형태가 달라 따로 처리 (3이어야 정상)
  local healthy
  healthy=$(docker compose ps --format '{{.Status}}' 2>/dev/null | grep -c healthy)
  [ "$healthy" -ge 3 ] || failed+=("V1 브로커 healthy 수=$healthy (기대 3 이상)")

  # V2~V5 는 전부 "0이어야 정상" 이라는 공통점이 있습니다.
  local checks=(
    "V2 under-replicated|kafka-topics.sh --bootstrap-server $BS --describe --under-replicated-partitions"
    "V3 under-min-isr|kafka-topics.sh --bootstrap-server $BS --describe --under-min-isr-partitions"
    "V4 unavailable|kafka-topics.sh --bootstrap-server $BS --describe --unavailable-partitions"
  )

  local name cmd cnt
  for entry in "${checks[@]}"; do
    name="${entry%%|*}"
    cmd="${entry#*|}"
    cnt=$(docker exec kafka-1 /opt/kafka/bin/$cmd 2>/dev/null | wc -l | tr -d ' ')
    [ "$cnt" -eq 0 ] || failed+=("$name = $cnt (기대 0)")
  done

  # V5 스로틀 잔존 — 브로커 3대 모두 검사
  local tcnt total=0
  for b in 1 2 3; do
    tcnt=$(docker exec kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$BS" \
             --describe --entity-type brokers --entity-name "$b" 2>/dev/null | grep -c throttled)
    total=$(( total + tcnt ))
  done
  [ "$total" -eq 0 ] || failed+=("V5 스로틀 잔존 = $total (기대 0)")

  if [ "${#failed[@]}" -eq 0 ]; then
    echo "ALL PASS"
    return 0
  else
    echo "FAILED:"
    printf '  - %s\n' "${failed[@]}"
    return 1
  fi
}

run_checks

# 해설:
#   설계 포인트 두 가지.
#
#   1) V2~V5 의 정상 조건이 '전부 0' 이라는 규칙성을 이용했습니다.
#      그래서 "이름|명령" 배열 하나로 묶어 루프를 돌 수 있습니다.
#      새 검사를 추가할 때 배열에 한 줄만 넣으면 되므로 확장성이 좋습니다.
#      V1 만 형태가 달라(3 이상이어야 정상) 별도로 처리했습니다.
#
#   2) 실패 항목을 즉시 출력하지 않고 배열에 모았다가 마지막에 한 번에 냅니다.
#      첫 실패에서 멈추면 "다른 것도 깨졌는지" 를 알 수 없기 때문입니다.
#      장애 상황에서는 대개 여러 지표가 동시에 깨지므로 전체 그림이 중요합니다.
#
#   이 함수는 그대로 운영에 붙일 수 있습니다.
#     - cron 에 걸고 종료 코드로 알람
#     - 컨테이너 healthcheck
#     - 배포 파이프라인의 배포 전/후 게이트
#
#   다만 알람으로 쓸 때 주의할 점:
#   under-replicated 는 '정상 운영 중에도 일시적으로' 걸립니다.
#   (브로커 재시작, GC 정지, 순간적인 네트워크 지연)
#   그래서 즉시 알람을 울리면 오탐이 많습니다.
#   "5분 이상 지속될 때" 같은 지속 시간 조건을 반드시 붙이세요.
#   반면 OfflinePartitionsCount 는 일시적일 수 없으므로 즉시 알람이 맞습니다.


# =============================================================================
# 정답 7 — 조용한 실패 3가지 감사
# =============================================================================
hr "정답 7"

echo "고른 3가지: #11 min.insync.replicas=1 / #5 acks=0,1 / #8 enable.auto.commit=true"
echo

echo "--- #11 min.insync.replicas 감사 (브로커에서 확인 가능) ---"
echo "min.insync.replicas 가 2 미만인 토픽:"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topics-with-overrides 2>/dev/null \
  | grep -E '^Topic:' \
  | while read -r line; do
      t=$(echo "$line" | awk '{print $2}')
      if echo "$line" | grep -q 'min.insync.replicas=2'; then
        : # 정상
      else
        echo "  ⚠ $t — min.insync.replicas override 없음 (브로커 기본값 사용)"
      fi
    done
echo "(브로커 기본값 확인)"
K kafka-configs.sh --bootstrap-server "$BS" --describe \
    --entity-type brokers --entity-name 1 2>/dev/null | grep min.insync || \
  echo "  브로커 동적 설정 없음 → docker-compose.yml 의 KAFKA_MIN_INSYNC_REPLICAS=2 적용 중"

echo
echo "--- #3 파티션 증가 위험 감사 (브로커에서 확인 가능) ---"
echo "키 기반 순서에 의존하는 토픽의 현재 파티션 수:"
for t in orders order-events; do
  K kafka-topics.sh --bootstrap-server "$BS" --describe --topic "$t" 2>/dev/null | head -1 \
    | grep -o 'PartitionCount: [0-9]*' | sed "s/^/  $t : /"
done
echo "  → 이 값을 문서에 못 박고, 변경 요청이 오면 3-6 의 함정을 근거로 거절할 것"

echo
echo "--- #5 acks / #8 enable.auto.commit 감사 ---"
echo "  ★ 브로커에서 확인할 수 없습니다."

# 해설:
#   ★ 이 문제의 핵심은 '감사 가능성의 비대칭' 입니다.
#
#   브로커에서 확인 가능한 것 (서버 측 설정):
#     - min.insync.replicas          (#11)
#     - unclean.leader.election      (#10)
#     - cleanup.policy, retention    (#12 관련)
#     - 파티션 수                     (#3)
#     - auto.create.topics.enable    (#2)
#
#   브로커에서 확인 '불가능' 한 것 (클라이언트 측 설정):
#     - acks                         (#5)
#     - enable.idempotence           (#6)
#     - max.in.flight.requests       (#6)
#     - enable.auto.commit           (#8)
#     - auto.offset.reset            (#1)
#     - isolation.level              (#9 관련)
#
#   왜 못 보는가:
#   이 값들은 프로듀서/컨슈머 프로세스의 메모리에만 존재합니다.
#   브로커는 요청이 들어오면 그 요청에 담긴 acks 값을 보고 그때그때 처리할 뿐,
#   "이 클라이언트가 평소에 어떤 설정을 쓰는지" 를 저장하지 않습니다.
#   클라이언트가 접속할 때 설정을 등록하는 절차 자체가 없습니다.
#
#   ★ 그래서 실무적으로 무엇을 해야 하는가:
#
#   1) 클라이언트 설정은 '코드 리뷰' 와 '공용 팩토리' 로만 통제할 수 있습니다.
#      조직 표준 ProducerFactory / ConsumerFactory 를 만들어
#      acks=all, enable.idempotence=true, enable.auto.commit=false 를
#      기본값으로 박아 두고, 개별 서비스가 오버라이드하면 리뷰에서 잡습니다.
#
#   2) 브로커 측에서 강제할 수 있는 것은 최대한 브로커에서 강제하세요.
#      min.insync.replicas 를 2로 두면, 클라이언트가 acks=all 을 썼을 때
#      '진짜로' 2개 복제를 보장받습니다. 이건 서버가 지켜 줍니다.
#      반대로 클라이언트가 acks=1 을 쓰면 서버는 막을 방법이 없습니다.
#
#   3) 간접 탐지는 가능합니다.
#      - 컨슈머 그룹의 오프셋이 '처리량과 무관하게 5초마다 정확히' 갱신되면
#        enable.auto.commit=true 를 의심할 수 있습니다 (기본 interval 이 5초).
#      - 특정 프로듀서의 배치에 producerId 가 -1 이면 (kafka-dump-log.sh)
#        멱등성이 꺼져 있다는 뜻입니다.
#      완벽하진 않지만 감사 단서로는 쓸 만합니다.


# =============================================================================
# 뒷정리
# =============================================================================
hr "뒷정리"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^s14_ex' | while read -r t; do
  echo "삭제: $t"
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t"
done

hr "solution.sh 완료"
echo "★ 브로커 3대 최종 확인:"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
