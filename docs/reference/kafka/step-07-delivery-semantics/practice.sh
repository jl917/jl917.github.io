#!/usr/bin/env bash
#
# Step 07 — 전달 보장 : 본문 예제 모음
#
# 실행법:
#   bash practice.sh              # 통째로 실행
#   bash -x practice.sh           # 한 줄씩 확인하며 실행 (권장)
#
# 사전 조건:
#   - docker compose up -d 로 kafka-1/2/3 이 모두 (healthy)
#   - Practice.java 가 이 스크립트와 같은 디렉터리에 있어야 합니다
#
# 주의:
#   - 7-7 의 LSO 함정은 터미널 3개를 요구하므로 아래에서 주석 처리되어 있습니다.
#   - __transaction_state 는 내부 토픽입니다. 절대 삭제하지 마십시오.

set -euo pipefail

BS=kafka-1:9092
K()  { docker exec    kafka-1 /opt/kafka/bin/"$@"; }
KI() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

# 파티션 로그를 덤프하는 헬퍼. 7-4, 7-6 에서 반복해서 씁니다.
DUMP() {
  K kafka-dump-log.sh \
    --files "/var/lib/kafka/data/$1/00000000000000000000.log" \
    --print-data-log
}

IN=s07_orders
OUT=s07_payments

hr() { echo; echo "=============== $* ==============="; echo; }

# ---------------------------------------------------------------------------
# [7-0] 실습 준비
# ---------------------------------------------------------------------------
hr "[7-0] 토픽 생성 (둘 다 파티션 1개)"

for t in "$IN" "$OUT"; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
done
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$IN"  --partitions 1 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$OUT" --partitions 1 --replication-factor 3

hr "[7-0] s07_orders 에 100건 발행 (O-3001 ~ O-3100)"

for i in $(seq 3001 3100); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":39000,\"status\":\"CREATED\"}"
done | KI kafka-console-producer.sh --bootstrap-server "$BS" --topic "$IN" \
        --property parse.key=true --property key.separator=:

K kafka-get-offsets.sh --bootstrap-server "$BS" --topic "$IN"

hr "[7-0] Practice.java 를 컨테이너로 복사"

docker cp Practice.java kafka-1:/tmp/

# ---------------------------------------------------------------------------
# [7-2] at-most-once — 커밋 먼저, 처리 나중 → 유실
# ---------------------------------------------------------------------------
hr "[7-2] at-most-once : 35건 처리 후 halt. 커밋은 40까지"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once' || true

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07-amo

hr "[7-2] at-most-once-resume : 유실 건수 집계"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once-resume'

# ---------------------------------------------------------------------------
# [7-3] at-least-once — 처리 먼저, 커밋 나중 → 중복
# ---------------------------------------------------------------------------
hr "[7-3] at-least-once : 커밋 전에 halt"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once' || true

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07-alo

hr "[7-3] at-least-once-resume : 중복 건수 집계"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once-resume'

# ---------------------------------------------------------------------------
# [7-4] 멱등 프로듀서
# ---------------------------------------------------------------------------
hr "[7-4] 잘못된 설정 (enable.idempotence=true + acks=1) → ConfigException"

# 의도적으로 실패하는 명령입니다. set -e 때문에 || true 가 필요합니다.
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent-bad-config' 2>&1 \
  | grep -E 'ConfigException|Must set acks' || true

hr "[7-4] 멱등 프로듀서로 10건 발행"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent'

hr "[7-4] 배치 헤더에서 producerId / producerEpoch / baseSequence 확인"

DUMP "${OUT}-0" | head -8

# ---------------------------------------------------------------------------
# [7-5] 트랜잭션 — commit / abort
# ---------------------------------------------------------------------------
hr "[7-5] txn-commit : 3건을 트랜잭션으로 커밋"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-commit'

hr "[7-5] txn-abort : 3건을 보내고 abort"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-abort'

# ---------------------------------------------------------------------------
# [7-6] isolation.level — 같은 토픽을 두 번 읽습니다
#       두 출력의 건수 차이와 빠진 Offset 번호가 관찰 대상입니다.
# ---------------------------------------------------------------------------
hr "[7-6-1] read_uncommitted (기본값) — abort 된 것도 보입니다"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --from-beginning --property print.offset=true --timeout-ms 5000 2>/dev/null | tail -8 || true

hr "[7-6-2] read_committed — abort 된 것이 걸러집니다"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --property print.offset=true --timeout-ms 5000 2>/dev/null | tail -8 || true

hr "[7-6-3] 컨트롤 레코드 — 데이터는 로그에 남아 있습니다"

DUMP "${OUT}-0" | grep -E 'isControl: true|isTransactional: true' | head -4

hr "[7-6-4] 마커 내용 — COMMIT / ABORT"

DUMP "${OUT}-0" | grep endTxnMarker || true

# ---------------------------------------------------------------------------
# [7-7] LSO 함정 — 터미널 3개가 필요합니다
# ---------------------------------------------------------------------------
# 한 창에서 순차 실행하면 트랜잭션이 이미 끝난 뒤라 아무것도 막히지 않습니다.
# 반드시 창을 세 개 열어 아래를 동시에 실행하십시오.
#
# [터미널 A] 트랜잭션을 열어 30초간 커밋하지 않고 붙잡습니다.
#   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-hang'
#
# [터미널 B] read_uncommitted — 즉시 2건이 보입니다.
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s07_payments \
#     --property print.offset=true --timeout-ms 10000
#
# [터미널 C] read_committed — 0건. 에러도 없이 멈춰 있습니다.
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s07_payments \
#     --consumer-property isolation.level=read_committed \
#     --property print.offset=true --timeout-ms 10000
#
# 터미널 A 가 커밋한 뒤 터미널 C 를 다시 띄우면 그제야 2건이 나옵니다.

hr "[7-7] transaction.max.timeout.ms 확인 (브로커 상한, 기본 900000 = 15분)"

K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type brokers --entity-name 1 --all 2>/dev/null \
  | grep transaction.max.timeout || true

# ---------------------------------------------------------------------------
# [7-8] consume-transform-produce
# ---------------------------------------------------------------------------
hr "[7-8] orders → 변환 → payments, 오프셋도 같은 트랜잭션에"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-consume-transform-produce'

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07-ctp

hr "[7-8] 출력 토픽 건수 확인 (read_committed 기준)"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 8000 2>&1 | grep Processed || true

# ---------------------------------------------------------------------------
# [7-11] 정리
# ---------------------------------------------------------------------------
hr "[7-11] 정리 — 그룹과 토픽 삭제"

for g in s07-amo s07-alo s07-ctp s07-idem; do
  K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$g" 2>/dev/null || true
done

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$IN"  2>/dev/null || true
K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$OUT" 2>/dev/null || true

docker exec kafka-1 rm -f /tmp/Practice.java /tmp/s07-count.txt /tmp/s07-side-effect.log 2>/dev/null || true

hr "[7-11] 확인 — __transaction_state 는 남아 있어야 정상입니다"

K kafka-topics.sh --bootstrap-server "$BS" --list | grep -E '^(s07|__transaction)' || echo "정리 완료"

echo
echo "Step 07 실습 완료."
