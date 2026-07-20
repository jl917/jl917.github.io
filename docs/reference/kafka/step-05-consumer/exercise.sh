#!/usr/bin/env bash
#
# Step 05 — 컨슈머와 컨슈머 그룹 : 연습문제 7문제
#
# 실행법:
#   bash exercise.sh            # 준비 구간만 돌고 각 문제에서 멈춥니다
#   (권장) 이 파일을 열어 놓고 "# 여기에 작성:" 자리를 채운 뒤 그 부분만 복사해 실행
#
# 정답은 solution.sh 에 있습니다. 먼저 직접 풀어 보십시오.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "-------- $* --------"; }

PIDS=()

start_consumer() {   # start_consumer <group> [옵션...]
  local g="$1"; shift
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server "$BS" --topic s05_ex_orders --group "$g" "$@" >/dev/null 2>&1 &
  PIDS+=("$!")
  sleep 2
}

stop_all() {
  for p in "${PIDS[@]:-}"; do kill -TERM "$p" 2>/dev/null || true; done
  PIDS=()
  docker exec kafka-1 pkill -f kafka-console-consumer 2>/dev/null || true
  sleep 3
}

seed() {   # seed <토픽> <건수>
  docker exec kafka-1 bash -c "
    for i in \$(seq 1 $2); do
      k=\"C\$(printf '%03d' \$((RANDOM % 10 + 1)))\"
      echo \"\$k:{\\\"order_id\\\":\\\"O-\$((1000+i))\\\",\\\"customer_id\\\":\\\"\$k\\\",\\\"amount\\\":10000,\\\"status\\\":\\\"CREATED\\\"}\"
    done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
      --topic $1 --property parse.key=true --property key.separator=:"
}

cleanup_ex() {
  stop_all
  sleep 5
  for G in $(K kafka-consumer-groups.sh --bootstrap-server "$BS" --list 2>/dev/null | grep '^s05-ex-' || true); do
    K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$G" 2>/dev/null || true
  done
  for T in $(K kafka-topics.sh --bootstrap-server "$BS" --list 2>/dev/null | grep '^s05_ex_' || true); do
    K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
  done
  echo "s05_ex_ 토픽 / s05-ex- 그룹 정리 완료"
}
trap stop_all EXIT

# ---------------------------------------------------------------------------
# 준비
# ---------------------------------------------------------------------------
hr "준비"
cleanup_ex
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_ex_orders \
  --partitions 3 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s05_ex_payments \
  --partitions 3 --replication-factor 3
seed s05_ex_orders 200
seed s05_ex_payments 200
# 데이터가 부족해지면 위 seed 두 줄만 다시 실행하십시오.


# ===========================================================================
# 문제 1. 컨슈머를 1 → 2 → 3 → 4 개로 늘리며 할당 변화 기록
# ===========================================================================
# 그룹 s05-ex-scale 에 컨슈머를 하나씩 늘려 가며
# --members --verbose 로 각 단계의 ASSIGNMENT 를 기록하십시오.
#
# 표를 채우십시오:
#   컨슈머 1개 → 할당 분포 [ ____ ]
#   컨슈머 2개 → 할당 분포 [ ____ ]
#   컨슈머 3개 → 할당 분포 [ ____ ]
#   컨슈머 4개 → 할당 분포 [ ____ ]
#
# 힌트: start_consumer s05-ex-scale
hr "문제 1"

# 여기에 작성:
#   for n in 1 2 3 4; do
#     start_consumer s05-ex-scale
#     echo "=== 컨슈머 ${n}개 ==="
#     K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe \
#        --group ______ --members --verbose
#   done


# ===========================================================================
# 문제 2. kill -9 vs kill -TERM — 리밸런싱까지 걸리는 시간
# ===========================================================================
# 컨슈머 3개를 띄운 뒤 하나를 죽이고, 재배분이 끝날 때까지의 시간을 재십시오.
# kill -TERM 과 kill -9 를 각각 측정해 비교합니다.
#
# 힌트: --members --verbose 의 줄 수가 3 → 2 로 줄고
#       다시 "각 컨슈머가 파티션을 가진 상태" 가 되면 재배분 완료입니다.
# 힌트: date +%s 로 시각을 찍으십시오.
hr "문제 2"

# 여기에 작성: (SIGTERM)
#   start_consumer s05-ex-term; start_consumer s05-ex-term; start_consumer s05-ex-term
#   T0=$(date +%s)
#   kill -____ "${PIDS[0]}"
#   while [ "$(K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe \
#              --group s05-ex-term --members 2>/dev/null | tail -n +2 | wc -l)" -ne 2 ]; do
#     sleep 0.2
#   done
#   echo "SIGTERM: $(( $(date +%s) - T0 ))초"

# 여기에 작성: (SIGKILL — 같은 방식, kill -9)
#


# ===========================================================================
# 문제 3. LAG 이 가장 큰 파티션 찾기 (한 줄 명령)
# ===========================================================================
# --describe 출력에서 LAG 이 가장 큰 파티션 한 줄만 뽑는 명령을 만드십시오.
#
# 힌트: LAG 은 6번째 컬럼입니다.
# 힌트: 헤더 줄과 LAG 이 '-' 인 줄을 걸러야 합니다.
hr "문제 3"

# (문제용 랙 만들기 — 절반만 읽습니다)
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s05_ex_orders \
  --group s05-ex-lag --from-beginning --max-messages 60 >/dev/null

# 여기에 작성:
#   K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s05-ex-lag \
#     | awk '______________________________' | ______ | ______


# ===========================================================================
# 문제 4. 같은 그룹에 서로 다른 할당 전략을 섞으면?
# ===========================================================================
# 그룹 s05-ex-mix 에
#   - 컨슈머 1: RangeAssignor (기본)
#   - 컨슈머 2: RoundRobinAssignor
# 를 붙이면 어떻게 됩니까? 나오는 예외 이름을 적으십시오.
#
# 힌트: 콘솔 컨슈머는 이 예외를 stderr 로 뱉고 종료합니다.
#       > /dev/null 로 버리면 안 보입니다. 2>&1 | grep 으로 잡으십시오.
#
# 예외 이름: org.apache.kafka.common.errors.____________________
hr "문제 4"

# 여기에 작성:
#


# ===========================================================================
# 문제 5. Range vs RoundRobin 의 불균형
# ===========================================================================
# s05_ex_orders 와 s05_ex_payments 를 함께 구독하는 컨슈머 2개를
# Range 로 한 번, RoundRobin 으로 한 번 띄워 #PARTITIONS 분포를 비교하십시오.
#
# 힌트: --include 's05_ex_(orders|payments)'
# 힌트: 그룹 이름을 반드시 다르게 하십시오 (문제 4 의 이유).
#
# 답:  Range → ____ : ____ ,   RoundRobin → ____ : ____
hr "문제 5"

# 여기에 작성:
#


# ===========================================================================
# 문제 6. max.poll.interval.ms 초과 재현하고 해결하기
# ===========================================================================
# (a) Practice.java slow-consumer 를 40초간 돌려
#     CommitFailedException 과 "같은 메시지를 다시 받는 것" 을 확인하십시오.
# (b) 환경변수 MAX_POLL_RECORDS=1 만 주고 다시 돌려 문제가 사라지는지 보십시오.
# (c) 왜 max.poll.interval.ms 를 늘리는 것이 마지막 수단입니까? 한 문장으로.
#
# 힌트: docker exec -e TOPIC=s05_ex_orders -e MAX_POLL_RECORDS=1 kafka-1 sh -c '...'
# 힌트: 이 시나리오는 스스로 끝나지 않으므로 timeout 으로 감싸십시오.
# 힌트: Practice.java 는 TOPIC 환경변수를 읽습니다. 안 주면 s05_orders 를 봅니다.
hr "문제 6"

docker cp "$(dirname "$0")/Practice.java" kafka-1:/tmp/

# 여기에 작성: (a)
#

# 여기에 작성: (b)
#

# (c) 답:
#


# ===========================================================================
# 문제 7. 정적 멤버십 — generationId 가 오르지 않는 것 확인
# ===========================================================================
# Practice.java static-member 를 두 번 연속 실행하고,
# 두 번의 generationId 가 같은지 확인하십시오.
# 그다음 group.instance.id 없이(consume 시나리오) 두 번 실행해 비교하십시오.
#
# 힌트: generationId 는 컨슈머 로그의
#       "Successfully joined group with generation Generation{generationId=N" 에 있습니다.
#       프로그램도 마지막에 직접 출력합니다.
#
# 답:  정적 멤버 → generationId ____ , ____   (변화 ____)
#      동적 멤버 → generationId ____ , ____   (변화 ____)
hr "문제 7"

# 여기에 작성:
#


# ===========================================================================
# 정리
# ===========================================================================
hr "정리"
# 중간에 멈췄다면 이 함수만 따로 호출하십시오.
cleanup_ex

K kafka-consumer-groups.sh --bootstrap-server "$BS" --list | grep '^s05-' \
  || echo "s05- 그룹 없음 — 정리 완료"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s05_' \
  || echo "s05_ 토픽 없음 — 정리 완료"
