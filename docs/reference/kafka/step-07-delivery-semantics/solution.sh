#!/usr/bin/env bash
#
# Step 07 — 전달 보장 : 연습문제 정답과 해설
#
# 실행법:
#   bash solution.sh
#
# 각 정답 뒤에 "왜 그 답인가" 를 설명하는 # 해설: 블록이 붙어 있습니다.
# 문제를 풀어 본 뒤에 여세요.

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

hr() { echo; echo "=============== $* ==============="; echo; }

setup() {
  hr "setup — s07x_orders / s07x_payments, 주문 60건"
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
  docker cp Practice.java kafka-1:/tmp/ 2>/dev/null || true
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
}

setup

# ===========================================================================
# 정답 1
# ===========================================================================
hr "정답 1 — at-most-once 는 유실, at-least-once 는 중복. 둘 다 정상 동작"

docker exec -e S07_TOPIC="$IN" -e S07_GROUP=s07x-amo -e S07_DIE_AT=22 kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once' || true
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07x-amo
docker exec -e S07_TOPIC="$IN" -e S07_GROUP=s07x-amo kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-most-once-resume'

docker exec -e S07_TOPIC="$IN" -e S07_GROUP=s07x-alo -e S07_DIE_AT=48 kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once' || true
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07x-alo
docker exec -e S07_TOPIC="$IN" -e S07_GROUP=s07x-alo kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java at-least-once-resume'

# 해설:
#   표 (배치 크기 40, S07_DIE_AT 값에 따라 숫자는 조금씩 달라집니다):
#
#     보장            처리 총합   발행 건수   유실   중복
#     at-most-once      42          60         18     0
#     at-least-once     68          60         0      8
#
#   at-most-once 는 커밋(40)이 처리(22)보다 앞서 있었으므로
#   오프셋 22~39 가 처리되지 않은 채 커밋됐습니다. 그 구간은 영영 안 읽힙니다.
#
#   at-least-once 는 처리(48)가 커밋(40)보다 앞서 있었으므로
#   오프셋 40~47 이 처리됐지만 커밋되지 않았습니다. 재시작하면 다시 읽습니다.
#
#   질문의 답: 둘 다 버그가 아닙니다.
#   두 코드의 유일한 차이는 consumer.commitSync() 가 for 루프 앞에 있느냐 뒤에 있느냐입니다.
#   각각의 결과는 그 배치 순서의 필연적 귀결이며, 정확히 설계대로 동작한 것입니다.
#
#   버그는 오히려 Step 06 의 enable.auto.commit=true 쪽입니다.
#   그건 어느 순서가 될지 코드로 알 수 없고, 죽는 시점에 따라 유실도 중복도 되기 때문입니다.
#   "선택지를 고르지 않은 것" 이 버그입니다.

# ===========================================================================
# 정답 2
# ===========================================================================
hr "정답 2 — ConfigException. 프로듀서 생성 시점에 즉시 실패합니다"

docker exec -e S07_ACKS=1 -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent' 2>&1 \
  | grep -E 'ConfigException|Must set acks|at org.apache.kafka.clients.producer' | head -3 || true

# 해설:
#   (a) org.apache.kafka.common.config.ConfigException
#       메시지: Must set acks to all in order to use the idempotent producer.
#               Otherwise we cannot guarantee idempotence.
#
#   (b) 프로듀서 생성 시점입니다.
#       스택트레이스가 KafkaProducer.configureAcks(KafkaProducer.java:562) →
#       KafkaProducer.<init>(KafkaProducer.java:401) 을 가리킵니다.
#       send() 를 한 번도 호출하지 않아도 new KafkaProducer<>(props) 에서 터집니다.
#
#       이것이 좋은 설계입니다. 잘못된 설정이 애플리케이션 기동 자체를 막으므로
#       배포 직후 즉시 알 수 있습니다. 조용히 틀리지 않습니다.
#
#   (c) 시퀀스 상태가 "파티션 리더" 에 있기 때문입니다.
#
#       브로커는 파티션마다 (producerId → 마지막 시퀀스) 맵을 유지합니다.
#       이 상태는 리더의 메모리와 로그(스냅샷)에 있고, 팔로워는 복제를 통해서만 얻습니다.
#
#       acks=1 이면 리더만 기록하고 성공을 돌려줍니다.
#       그 직후 리더가 죽고 팔로워가 새 리더가 되면,
#       새 리더는 그 배치도, 그 시퀀스 번호도 모릅니다.
#       프로듀서가 재시도해서 같은 배치를 보내면 새 리더는 "처음 보는 시퀀스" 로 판단하고
#       그대로 기록합니다. 중복입니다.
#
#       즉 acks=1 에서 멱등성은 "리더가 안 죽으면 동작" 하는 것이고,
#       그건 보장이 아닙니다. 그래서 클라이언트가 아예 조합을 금지합니다.
#
#       같은 이유로 retries=0 도 금지입니다. 재시도를 안 하면
#       중복을 걸러낼 일 자체가 없는 대신 유실이 생기므로 멱등성이 무의미합니다.

# ===========================================================================
# 정답 3
# ===========================================================================
hr "정답 3 — 재시작하면 PID 가 바뀌고 시퀀스가 0 으로 리셋됩니다"

echo "--- 1회차 ---"
docker exec -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent' >/dev/null
DUMP "${OUT}-0" | grep -oE 'producerId: [0-9]+ producerEpoch: [0-9]+' | sort -u

echo "--- 2회차 (프로세스 재시작) ---"
docker exec -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java idempotent' >/dev/null
DUMP "${OUT}-0" | grep -oE 'producerId: [0-9]+ .*baseSequence: [0-9-]+' \
  | grep -oE 'producerId: [0-9]+|baseSequence: [0-9-]+' | paste - - | sort -u

# 해설:
#   출력 예:
#     producerId: 1000 producerEpoch: 0      ← 1회차
#     producerId: 1001 producerEpoch: 0      ← 2회차
#
#   그리고 baseSequence 는 두 실행 모두 0 부터 시작합니다.
#
#     baseOffset: 0  ... baseSequence: 0 lastSequence: 4 producerId: 1000
#     baseOffset: 10 ... baseSequence: 0 lastSequence: 4 producerId: 1001   ← 다시 0!
#
#   이것이 "멱등성은 단일 프로듀서 세션 한정" 의 직접적 증거입니다.
#
#   브로커는 (producerId, 파티션) 별로 시퀀스를 추적합니다.
#   producerId 가 1000 에서 1001 로 바뀌는 순간, 브로커에게 이건 완전히 다른 프로듀서입니다.
#   1001 의 seq=0 은 "처음 보는 것" 이므로 중복 검사에 걸리지 않고 그대로 기록됩니다.
#
#   실무 시나리오로 옮기면:
#     1. 프로듀서가 결제 요청을 send() 한다
#     2. 브로커가 기록한다
#     3. ack 가 돌아오기 전에 프로듀서 파드가 OOM 으로 죽는다
#     4. 파드가 재시작하고, 미전송으로 판단한 요청을 다시 보낸다
#     5. 새 PID 이므로 브로커가 걸러내지 못한다 → 결제 2건
#
#   이 구간을 막으려면 transactional.id 를 고정해야 합니다.
#   transactional.id 가 같으면 initTransactions() 가 이전 세션의 PID 를 이어받고
#   epoch 만 +1 하므로, 브로커가 "같은 정체성의 프로듀서" 로 인식합니다.
#   이것이 트랜잭션이 존재하는 이유입니다.

# ===========================================================================
# 정답 4
# ===========================================================================
hr "정답 4 — 사라진 건수 = abort 된 데이터. 마커는 어느 쪽에도 안 세어집니다"

docker exec -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-commit'
docker exec -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-abort'

echo "--- read_uncommitted ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --from-beginning --timeout-ms 6000 2>&1 | grep Processed || true

echo "--- read_committed ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 6000 2>&1 | grep Processed || true

echo "--- 컨트롤 레코드 개수 ---"
DUMP "${OUT}-0" | grep -c 'isControl: true' || true
DUMP "${OUT}-0" | grep endTxnMarker || true

echo "--- 로그의 실제 마지막 오프셋 ---"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic "$OUT"

# 해설:
#   숫자 예 (1회차만 돌린 경우):
#     read_uncommitted : Processed a total of 16 messages
#     read_committed   : Processed a total of 13 messages
#     isControl: true  : 2개 (COMMIT 마커 1, ABORT 마커 1)
#     kafka-get-offsets: s07x_payments:0:18
#
#   등식을 확인합니다.
#     16 - 13 = 3   = abort 된 데이터 건수 (O-3014, O-3015, O-3016) ✓
#     18(LEO) - 16(read_uncommitted) = 2 = 컨트롤 레코드 2개 ✓
#
#   즉 컨트롤 레코드는 오프셋을 차지하지만 어느 컨슈머에게도 데이터로 전달되지 않습니다.
#   read_uncommitted 조차 못 봅니다. 클라이언트 라이브러리가 아예 걸러냅니다.
#
#   여기서 두 가지 결론이 나옵니다.
#
#   1. abort 는 "지우기" 가 아닙니다.
#      로그에서 물리적으로 삭제되지 않고, 마커로 "이건 무효" 라고 표시할 뿐입니다.
#      로그 압축이나 보존 정책으로 지워지기 전까지 디스크를 차지합니다.
#      트랜잭션을 자주 abort 하는 파이프라인은 디스크가 예상보다 빨리 찹니다.
#
#   2. 오프셋은 연속하지 않습니다.
#      read_committed 컨슈머의 로그에서 offset=12 다음이 offset=20 처럼 뜁니다.
#      "이전 오프셋 + 1 이 아니면 유실" 같은 로직을 만들면 여기서 오작동합니다.
#      Kafka 는 애초에 오프셋 연속성을 보장하지 않습니다.
#      로그 압축(Step 09)에서도 같은 이유로 구멍이 생깁니다.
#
#      처리 건수는 반드시 애플리케이션 카운터로 세십시오. 오프셋 산술로 세지 마십시오.

# ===========================================================================
# 정답 5
# ===========================================================================
hr "정답 5 — 기본 60초, transaction.timeout.ms=10000 이면 10초"

# [터미널 A] 아래를 별도 창에서 실행합니다. 커밋하지 않고 죽습니다.
#   docker exec -e S07_OUT_TOPIC=s07x_payments -e S07_HANG_SECONDS=999 kafka-1 \
#     sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-hang'
#
# [터미널 B] 그동안 read_committed 컨슈머:
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s07x_payments \
#     --consumer-property isolation.level=read_committed --timeout-ms 15000
#   → Processed a total of 0 messages
#
# transaction.timeout.ms 를 줄여 회복 시간 측정
echo "--- transaction.timeout.ms=10000 으로 붙잡기 ---"
docker exec -e S07_OUT_TOPIC="$OUT" -e S07_TXN_TIMEOUT=10000 -e S07_HANG_SECONDS=25 kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-hang' &
HANG_PID=$!
sleep 3

echo "--- 그동안 read_committed 로 읽기 (0건이어야 합니다) ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 6000 2>&1 | grep Processed || true

echo "--- 코디네이터가 강제 abort 할 때까지 대기 ---"
sleep 12

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 6000 2>&1 | grep Processed || true

wait $HANG_PID 2>/dev/null || true

# 해설:
#   측정 결과:
#     transaction.timeout.ms 기본(60000) : LSO 가 전진하기까지 약 60초
#     transaction.timeout.ms=10000       : 약 10초
#
#   원리는 이렇습니다.
#   프로듀서가 커밋도 abort 도 하지 않은 채 사라지면,
#   트랜잭션 코디네이터가 transaction.timeout.ms 후에 그 트랜잭션을 강제 abort 하고
#   해당 파티션에 ABORT 마커를 씁니다. 그제야 LSO 가 마커 뒤로 전진하고,
#   read_committed 컨슈머가 그 뒤의 메시지를 볼 수 있게 됩니다.
#
#   그동안 컨슈머는 에러 없이 빈 poll 만 반복합니다.
#   로그에도 아무것도 안 남습니다. 이것이 이 함정의 지독한 점입니다.
#
#   ⚠️ 타임아웃을 1초로 줄이면 어떻게 됩니까?
#   정상적인 긴 트랜잭션이 강제로 abort 됩니다.
#   배치 하나를 처리하는 데 3초가 걸리는 파이프라인이라면,
#   매 트랜잭션이 완료 직전에 코디네이터에게 죽임을 당합니다.
#   그 결과 프로듀서는 commitTransaction() 에서 이런 예외를 받습니다.
#
#     org.apache.kafka.common.errors.ProducerFencedException:
#       There is a newer producer with the same transactionalId which fences the current one.
#
#   그리고 무한 재시도 루프에 빠집니다. 아무 진전도 없이 CPU 만 씁니다.
#
#   값을 정하는 기준: 도메인의 "최악의 배치 처리 시간" 에 안전 계수를 곱합니다.
#   평소 3초면 30초 정도. 브로커의 transaction.max.timeout.ms(기본 900000 = 15분)가 상한이며
#   그보다 큰 값을 요청하면 프로듀서 생성이 거부됩니다.

# ===========================================================================
# 정답 6
# ===========================================================================
hr "정답 6 — read_committed 로 세야 정확히 입력 건수와 같습니다"

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$OUT" 2>/dev/null || true
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$OUT" --partitions 1 --replication-factor 3

echo "--- (a) 25건 처리 시점에 죽입니다 ---"
docker exec -e S07_TOPIC="$IN" -e S07_OUT_TOPIC="$OUT" -e S07_GROUP=s07x-ctp -e S07_DIE_AT=25 kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-consume-transform-produce' || true

echo "--- (b) 커밋된 오프셋 ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s07x-ctp

echo "--- (c) 재시작해서 끝까지 처리 ---"
docker exec -e S07_TOPIC="$IN" -e S07_OUT_TOPIC="$OUT" -e S07_GROUP=s07x-ctp kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-consume-transform-produce'

echo "--- (d) read_committed 로 세기 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 8000 2>&1 | grep Processed || true

echo "--- (d) read_uncommitted 로 세기 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --from-beginning --timeout-ms 8000 2>&1 | grep Processed || true

# 해설:
#   결과 예:
#     read_committed   : Processed a total of 60 messages    ← 입력과 정확히 같습니다
#     read_uncommitted : Processed a total of 85 messages    ← abort 된 25건이 섞여 있습니다
#
#   무슨 일이 있었느냐면:
#     1회차에서 25건을 변환해 출력 토픽에 쓴 뒤, 커밋 전에 죽었습니다.
#     그 25건은 로그에 물리적으로 존재합니다.
#     코디네이터가 타임아웃 후 그 트랜잭션을 abort 하고 ABORT 마커를 썼습니다.
#     오프셋도 함께 취소됐으므로 --describe 의 CURRENT-OFFSET 은 0 입니다.
#     재시작한 프로세스가 처음부터 60건을 다시 읽어 60건을 썼습니다.
#
#   그래서 로그에는 85건(+ 마커들)이 있지만,
#   read_committed 렌즈로 보면 정확히 60건입니다.
#
#   질문의 답: read_committed 가 "정확히 한 번" 을 말해 줍니다.
#
#   이것이 exactly-once 의 실제 의미입니다.
#   "로그에 한 번만 쓰인다" 가 아니라 "read_committed 컨슈머에게 한 번만 보인다" 입니다.
#   그래서 EOS 파이프라인의 다운스트림 컨슈머는 반드시 read_committed 여야 합니다.
#   한 곳이라도 기본값(read_uncommitted)으로 두면 그 지점에서 보장이 깨집니다.
#
#   그리고 처리량 비용도 확인해 두십시오.
#     at-least-once     : 60건 / 1.38초 → 43.5 msg/s
#     exactly-once(txn) : 60건 / 2.45초 → 24.5 msg/s   (약 1.8배 느림)
#   트랜잭션마다 코디네이터 왕복 + 마커 쓰기가 추가되기 때문입니다.
#   배치를 크게 가져갈수록 이 오버헤드가 희석됩니다.

# ===========================================================================
# 정답 7
# ===========================================================================
hr "정답 7 — 파일은 롤백되지 않습니다"

docker exec kafka-1 rm -f /tmp/s07-side-effect.log 2>/dev/null || true

docker exec -e S07_OUT_TOPIC="$OUT" kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java txn-abort-with-file'

echo "--- abort 했는데 파일에 남아 있는 줄 ---"
docker exec kafka-1 cat /tmp/s07-side-effect.log

echo "--- 같은 트랜잭션의 메시지는 read_committed 에 안 보입니다 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$OUT" \
  --consumer-property isolation.level=read_committed \
  --from-beginning --timeout-ms 6000 2>&1 | grep -c 'SIDE-EFFECT' || echo "0건 (걸러짐)"

# 해설:
#   출력:
#     /tmp/s07-side-effect.log 내용
#       O-4001 charged
#       O-4002 charged
#       O-4003 charged
#
#   Kafka 메시지는 abort 로 걸러졌는데 파일의 세 줄은 그대로 있습니다.
#
#   당연합니다. Kafka 트랜잭션에 포함되는 것은 두 가지뿐입니다.
#     ① Kafka 토픽에 대한 쓰기
#     ② __consumer_offsets 에 대한 오프셋 커밋
#
#   파일 시스템, DB, REST API, 캐시, 이메일 — 전부 밖입니다.
#   Kafka 트랜잭션은 분산 트랜잭션(2PC)이 아니고, 그럴 의도로 만들어지지도 않았습니다.
#
#   (c) 멱등 처리로 고치는 방법:
#
#     // 트랜잭션에 기대지 말고, 외부 시스템 쪽에서 중복을 판정합니다.
#     for (ConsumerRecord<String,String> r : records) {
#         String orderId = orderIdOf(r.value());
#
#         // ① 먼저 "이미 처리했는가" 를 원자적으로 판정한다
#         //    DB 라면 유니크 제약이 있는 테이블에 INSERT 를 시도하는 것이 가장 견고합니다.
#         try {
#             jdbc.update("INSERT INTO processed_orders (order_id) VALUES (?)", orderId);
#         } catch (DuplicateKeyException e) {
#             log.info("이미 처리됨, 건너뜁니다: {}", orderId);
#             continue;                       // ② 두 번째부터는 아무 일도 하지 않는다
#         }
#
#         // ③ INSERT 에 성공한 경우에만 실제 부수효과를 실행한다
#         paymentGateway.charge(orderId, amount);
#     }
#     consumer.commitSync();                  // at-least-once
#
#   이 구조에서는 메시지가 몇 번 재전달되든 charge() 는 한 번만 실행됩니다.
#   중복 판정의 기준이 Kafka 오프셋이 아니라 order_id 라는 점이 핵심입니다.
#   오프셋은 리셋되고 되감기고 구멍이 생기지만, order_id 는 도메인의 불변 식별자입니다.
#
#   순서에 대한 주의:
#   위 코드는 INSERT 를 먼저 하고 charge() 를 나중에 합니다.
#   그 사이에 죽으면 "기록은 있는데 결제는 안 나간" 상태(유실 방향)가 됩니다.
#   반대로 하면 중복 결제 방향이 됩니다.
#   어느 쪽이 나은지는 도메인이 정합니다. 결제는 중복보다 누락이 낫고,
#   알림 발송은 누락보다 중복이 낫습니다. 이 판단을 코드 리뷰에서 명시적으로 하십시오.
#
#   그리고 상대 API 가 Idempotency-Key 를 지원한다면 그쪽이 더 낫습니다.
#     paymentGateway.charge(orderId, amount, /* Idempotency-Key */ orderId);
#   중복 판정 책임이 상대 시스템으로 넘어가므로 우리 쪽 상태 관리가 필요 없어집니다.

# ===========================================================================
cleanup

echo
echo "Step 07 정답 확인 완료."
