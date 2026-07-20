#!/usr/bin/env bash
#
# Step 07 — 전달 보장 : 연습문제 (7문제)
#
# 실행법:
#   bash exercise.sh              # setup 만 돌고 문제는 직접 채워야 합니다
#
# 규칙:
#   - "# 여기에 작성:" 아래를 직접 채우세요.
#   - 본문의 숫자(100건, 35, 5, 8)를 그대로 옮겨 적으면 틀립니다. 여기는 60건입니다.
#   - 정답은 solution.sh 에 있습니다.

set -euo pipefail

BS=kafka-1:9092
K()  { docker exec    kafka-1 /opt/kafka/bin/"$@"; }
KI() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }
DUMP() {
  K kafka-dump-log.sh \
    --files "/var/lib/kafka/data/$1/00000000000000000000.log" --print-data-log
}

IN=s07x_orders
OUT=s07x_payments

hr() { echo; echo "--------------- $* ---------------"; echo; }

setup() {
  hr "setup — s07x_orders / s07x_payments (각 1 파티션), 주문 60건"

  for t in "$IN" "$OUT"; do
    K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
  done
  sleep 3
  K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$IN"  --partitions 1 --replication-factor 3
  K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$OUT" --partitions 1 --replication-factor 3

  for i in $(seq 4001 4060); do
    c=$(printf "C%03d" $(( (i % 10) + 1 )))
    echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":52000,\"status\":\"CREATED\"}"
  done | KI kafka-console-producer.sh --bootstrap-server "$BS" --topic "$IN" \
          --property parse.key=true --property key.separator=:

  K kafka-get-offsets.sh --bootstrap-server "$BS" --topic "$IN"

  docker cp Practice.java kafka-1:/tmp/ 2>/dev/null || \
    echo "경고: Practice.java 를 찾지 못했습니다. 대부분의 문제를 풀 수 없습니다."
}

cleanup() {
  hr "cleanup"
  for g in s07x-amo s07x-alo s07x-ctp s07x-idem s07x-lso; do
    K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$g" 2>/dev/null || true
  done
  for t in "$IN" "$OUT"; do
    K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
  done
  docker exec kafka-1 rm -f /tmp/s07-count.txt /tmp/s07-side-effect.log 2>/dev/null || true
  # __transaction_state 는 절대 지우지 마십시오.
}

setup

# ===========================================================================
# 문제 1. at-most-once 와 at-least-once 를 같은 데이터로 측정
#
#   Practice.java 의 at-most-once / at-least-once 를 각각 돌리고
#   -resume 으로 집계까지 낸 뒤, 아래 표를 채우십시오.
#
#     보장            처리 총합   발행 건수   유실   중복
#     at-most-once      ?           60         ?      ?
#     at-least-once     ?           60         ?      ?
#
#   질문: 두 실행 중 "버그" 인 것이 있습니까? 없다면 왜 없습니까?
#
#   힌트: 환경변수 S07_TOPIC / S07_GROUP 로 토픽과 그룹을 지정할 수 있습니다.
# ===========================================================================
hr "문제 1"

# 여기에 작성: at-most-once 실행 + --describe + resume


# 여기에 작성: at-least-once 실행 + --describe + resume


# 여기에 작성: 표 채우기 (주석으로)
#     보장            처리 총합   발행 건수   유실   중복
#     at-most-once
#     at-least-once


# ===========================================================================
# 문제 2. enable.idempotence=true 에 acks=1 을 주면?
#
#   S07_ACKS=1 을 주고 Practice.java idempotent 를 실행해 어떤 예외가 나는지
#   전문을 확인하십시오.
#
#   질문: (a) 예외 클래스 이름은 무엇입니까?
#         (b) 예외가 send() 시점에 납니까, 프로듀서 생성 시점에 납니까?
#         (c) acks=1 이면 왜 멱등성을 보장할 수 없습니까?
#             (힌트: 시퀀스 상태가 어디에 저장되는지 생각해 보십시오)
# ===========================================================================
hr "문제 2"

# 여기에 작성: S07_ACKS=1 로 실행


# 여기에 작성: (a)(b)(c) 답을 주석으로


# ===========================================================================
# 문제 3. 프로듀서를 재시작하면 PID 가 바뀝니다
#
#   Practice.java idempotent 를 두 번 연달아 실행하고,
#   kafka-dump-log.sh 로 출력 토픽의 배치 헤더에서
#   producerId 와 baseSequence 만 뽑아 비교하십시오.
#
#   ⚠️ 한 번만 돌리면 문제가 성립하지 않습니다. 반드시 두 번 돌리십시오.
#
#   질문: 두 실행의 producerId 가 같습니까? baseSequence 는 어떻게 됩니까?
#         이 결과가 "멱등성으로 재시작 후 중복을 막을 수 없다" 는 것을
#         어떻게 증명합니까?
# ===========================================================================
hr "문제 3"

# 여기에 작성: 1회차 실행


# 여기에 작성: 2회차 실행


# 여기에 작성: producerId / baseSequence 추출해서 비교


# ===========================================================================
# 문제 4. abort 된 메시지는 어디에 있습니까
#
#   (a) txn-commit 과 txn-abort 를 각각 한 번씩 실행하십시오.
#   (b) read_uncommitted 와 read_committed 로 각각 세어 건수 차이를 확인하십시오.
#   (c) kafka-dump-log.sh 로 isControl: true 인 레코드 개수를 세십시오.
#   (d) 아래 등식이 성립하는지 확인하십시오.
#         (read_uncommitted 건수) - (read_committed 건수) = abort 된 데이터 건수
#       그리고 컨트롤 레코드가 차지한 오프셋은 어느 쪽 건수에도 포함되지 않는다는
#       것을 확인하십시오.
# ===========================================================================
hr "문제 4"

# 여기에 작성 (a)


# 여기에 작성 (b)


# 여기에 작성 (c)


# ===========================================================================
# 문제 5. 미완료 트랜잭션이 컨슈머를 멈춰 세웁니다   [터미널 2개 필요]
#
#   ⚠️ 반드시 창을 두 개 열어야 합니다.
#
#   [터미널 A] Practice.java txn-hang 으로 트랜잭션을 붙잡아 두십시오.
#             (S07_HANG_SECONDS 로 시간을 조절할 수 있습니다)
#   [터미널 B] 그동안 read_committed 컨슈머를 띄워 몇 건이 오는지 보십시오.
#
#   그다음, S07_TXN_TIMEOUT=10000 으로 다시 돌리고
#   컨슈머가 회복될 때까지 걸리는 시간을 time 으로 측정하십시오.
#
#   질문: 기본값에서 몇 초가 걸립니까? 10000 으로 줄이면 몇 초입니까?
#         타임아웃을 1초로 줄이면 어떤 부작용이 생깁니까?
# ===========================================================================
hr "문제 5"

# [터미널 A] 여기에 작성


# [터미널 B] 여기에 작성


# 여기에 작성: S07_TXN_TIMEOUT 을 줄여 회복 시간 측정


# ===========================================================================
# 문제 6. consume-transform-produce 를 중간에 죽이면
#
#   (a) txn-consume-transform-produce 를 S07_DIE_AT=25 로 돌려
#       25건 처리 시점에 죽게 하십시오.
#   (b) --describe 로 커밋된 오프셋을 확인하십시오.
#   (c) 재시작(S07_DIE_AT 없이)해서 끝까지 처리하십시오.
#   (d) 출력 토픽을 read_committed 로 세고, read_uncommitted 로도 세십시오.
#
#   질문: 두 건수가 다릅니까? 어느 쪽이 "정확히 한 번" 을 말해 줍니까?
# ===========================================================================
hr "문제 6"

# 여기에 작성 (a)


# 여기에 작성 (b)


# 여기에 작성 (c)


# 여기에 작성 (d)


# ===========================================================================
# 문제 7. 외부 시스템은 트랜잭션에 들어가지 않습니다
#
#   (a) Practice.java txn-abort-with-file 을 실행하십시오.
#       이 시나리오는 트랜잭션 안에서 /tmp/s07-side-effect.log 에 줄을 추가한 뒤
#       abortTransaction() 을 호출합니다.
#   (b) 트랜잭션이 abort 됐는데 그 파일에 줄이 남아 있는지 cat 으로 확인하십시오.
#   (c) 이 문제를 멱등 처리로 고치는 의사 코드를 주석으로 쓰십시오.
#       (힌트: order_id 로 이미 처리했는지 검사)
# ===========================================================================
hr "문제 7"

# 여기에 작성 (a)


# 여기에 작성 (b): 파일 내용 확인


# 여기에 작성 (c): 멱등 처리 의사 코드


# ===========================================================================
cleanup

echo
echo "연습문제 종료. solution.sh 로 채점하세요."
