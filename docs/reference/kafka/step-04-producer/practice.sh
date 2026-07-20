#!/usr/bin/env bash
#
# Step 04 — 프로듀서 : 본문(4-0 ~ 4-14)의 모든 명령
#
# 실행법:
#   bash practice.sh              # 통째로 실행 (10~15분)
#   bash -x practice.sh           # 한 줄씩 확인하며 실행 (권장)
#
# 주의:
#   - [4-4] 구간은 터미널 2개가 필요합니다. 스크립트가 read 로 멈춥니다.
#   - [4-3] [4-9] [4-10] 은 100만 건씩 보내므로 시간이 걸립니다.
#     빠르게 훑고 싶으면 아래 NUM_RECORDS 를 300000 으로 낮추십시오.
#     절대값은 달라지지만 비율은 유지됩니다.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

NUM_RECORDS=${NUM_RECORDS:-1000000}
REC_SIZE=100

hr() { echo; echo "=================== $* ==================="; }

# ---------------------------------------------------------------------------
# [4-0] 실습 준비 — s04_ 토픽 3개
# ---------------------------------------------------------------------------
hr "[4-0] 실습 준비"

# 이미 있으면 지우고 다시 만듭니다 (여러 번 돌려도 안전하게)
for T in s04_acks s04_order s04_perf; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_acks  \
  --partitions 3 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_order \
  --partitions 1 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_perf  \
  --partitions 6 --replication-factor 3 \
  --config segment.bytes=268435456 --config retention.ms=3600000

# s04_order 만 파티션 1개입니다. 순서 역전은 한 파티션 안의 현상이라,
# 파티션이 여러 개면 "원래 순서가 안 맞는 것"과 구별되지 않습니다.
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s04_acks

# ---------------------------------------------------------------------------
# [4-3] acks=0 / 1 / all 실측
# ---------------------------------------------------------------------------
hr "[4-3] acks 실측 — ${NUM_RECORDS} 건 x 3회"

run_perf() {
  local label="$1"; shift
  echo "--- $label ---"
  K kafka-producer-perf-test.sh \
    --topic s04_perf --num-records "$NUM_RECORDS" --record-size "$REC_SIZE" \
    --throughput -1 --producer-props bootstrap.servers="$BS" "$@" | tail -1
}

run_perf "acks=0"   acks=0
run_perf "acks=1"   acks=1
run_perf "acks=all" acks=all

# 마지막 줄의 "N records/sec" 가 처리량,
# "M ms 99th" 가 p99 지연입니다. 세 값을 표로 옮겨 적으십시오.

# ---------------------------------------------------------------------------
# [4-4] 핵심 함정 A — acks=0 은 브로커가 죽어도 성공한다
# ---------------------------------------------------------------------------
hr "[4-4] 함정 A — acks=0 유실 재현"

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s04_acks | grep 'Partition: 0'

echo
echo "  이제 [터미널 B] 를 열고 아래 명령을 준비해 두십시오."
echo "      docker compose stop kafka-2 kafka-3"
echo
echo "  Enter 를 누르면 120000건 전송(60초)이 시작됩니다."
echo "  전송이 시작되고 10초쯤 뒤에 터미널 B 에서 위 명령을 실행하십시오."
read -r -p "  준비되면 Enter: " _

K kafka-producer-perf-test.sh \
  --topic s04_acks --num-records 120000 --record-size 100 --throughput 2000 \
  --producer-props bootstrap.servers="$BS" acks=0

echo
echo "  프로듀서는 에러 한 줄 없이 '120000 records sent' 를 보고했습니다."
echo "  이제 브로커를 되살리고 실제 저장 건수를 셉니다."
read -r -p "  터미널 B 에서 'docker compose start kafka-2 kafka-3' 실행 후 Enter: " _
sleep 30

K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s04_acks

# 세 파티션 오프셋의 합이 120000 보다 작으면 그 차이가 그대로 유실입니다.
# acks=0 은 응답을 기다리지 않으므로 끊긴 연결에 실린 배치를 재시도하지 않습니다.

# ---------------------------------------------------------------------------
# [4-7] 키 라우팅 — 같은 키는 항상 같은 파티션
# ---------------------------------------------------------------------------
hr "[4-7] 키 라우팅"

# 앞 절에서 데이터가 섞였으므로 토픽을 재생성합니다
K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s04_acks
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_acks \
  --partitions 3 --replication-factor 3

Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s04_acks \
  --property parse.key=true --property key.separator=: <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
C002:{"order_id":"O-1002","customer_id":"C002","amount":12000,"status":"CREATED"}
C003:{"order_id":"O-1003","customer_id":"C003","amount":58000,"status":"CREATED"}
C001:{"order_id":"O-1004","customer_id":"C001","amount":7000,"status":"CREATED"}
C002:{"order_id":"O-1005","customer_id":"C002","amount":31000,"status":"CREATED"}
C001:{"order_id":"O-1006","customer_id":"C001","amount":25000,"status":"CREATED"}
EOF

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_acks --from-beginning \
  --max-messages 6 --timeout-ms 15000 \
  --property print.key=true --property print.partition=true --property print.value=false

# C001 세 건이 전부 같은 파티션으로 갑니다. murmur2(key) % 3 이 결정적이기 때문입니다.

# ---------------------------------------------------------------------------
# [4-8] 핵심 함정 C — 키 쏠림 (hot partition)
# ---------------------------------------------------------------------------
hr "[4-8] 함정 C — hot partition"

# C001 이 40%, 나머지 9명이 60% 를 나눠 갖는 1만 건.
# 생성과 소비를 같은 컨테이너 안에서 합니다(호스트에서 파이프로 넘기면 훨씬 느립니다).
docker exec kafka-1 bash -c '
for i in $(seq 1 10000); do
  if [ $((i % 10)) -lt 4 ]; then k="C001"; else k="C00$((RANDOM % 9 + 2))"; fi
  echo "$k:{\"order_id\":\"O-$((1000+i))\",\"customer_id\":\"$k\",\"amount\":10000,\"status\":\"CREATED\"}"
done | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 \
  --topic s04_acks --property parse.key=true --property key.separator=:'

K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s04_acks

# 한 파티션이 60% 이상을 차지합니다. 에러는 나지 않습니다.
# 증상은 "그 파티션의 LAG 만 계속 증가" 로만 나타납니다 (Step 05).

# ---------------------------------------------------------------------------
# [4-9] batch.size / linger.ms 트레이드오프
# ---------------------------------------------------------------------------
hr "[4-9] linger.ms 0 vs 20"

run_perf "linger.ms=0  batch.size=16KiB" acks=all linger.ms=0  batch.size=16384
run_perf "linger.ms=20 batch.size=64KiB" acks=all linger.ms=20 batch.size=65536

# 20ms 를 더 기다렸는데 평균 지연이 오히려 줄어듭니다.
# 요청 수가 줄어 브로커 앞의 큐잉이 사라지기 때문입니다.

# ---------------------------------------------------------------------------
# [4-9b] buffer.memory 가 차면 send() 가 블로킹한다
# ---------------------------------------------------------------------------
hr "[4-9b] buffer.memory + max.block.ms"

set +e
K kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 500000 --record-size 1000 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all \
    buffer.memory=1048576 max.block.ms=2000 2>&1 | tail -5
set -e

# TimeoutException: Failed to allocate memory within the configured max blocking
# time 2000 ms  → 예외가 나는 것이 정상입니다.

# ---------------------------------------------------------------------------
# [4-10] 압축 5종 비교
# ---------------------------------------------------------------------------
hr "[4-10] 압축 비교"

for C in none gzip snappy lz4 zstd; do
  # 코덱마다 같은 조건에서 재려면 토픽을 비워야 합니다
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s04_perf
  sleep 3
  K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_perf \
    --partitions 6 --replication-factor 3 \
    --config segment.bytes=268435456 --config retention.ms=3600000

  echo "--- compression.type=$C ---"
  K kafka-producer-perf-test.sh \
    --topic s04_perf --num-records "$NUM_RECORDS" --record-size 200 --throughput -1 \
    --producer-props bootstrap.servers="$BS" acks=all \
      linger.ms=20 batch.size=65536 compression.type="$C" | tail -1

  docker exec kafka-1 sh -c 'du -sc /var/lib/kafka/data/s04_perf-* | tail -1'
done

# 압축을 켜면 처리량이 오히려 늘어납니다(lz4 가 none 대비 +28%).
# 네트워크 바이트가 1/3 이 되어 절약된 I/O 가 CPU 비용보다 크기 때문입니다.
# gzip 만 예외입니다 — 압축률은 최고지만 CPU 비용이 과합니다.

# ---------------------------------------------------------------------------
# [4-10b] 브로커 compression.type 을 다르게 두면 재압축 비용
# ---------------------------------------------------------------------------
hr "[4-10b] 브로커 재압축"

K kafka-configs.sh --bootstrap-server "$BS" --alter \
  --entity-type topics --entity-name s04_perf --add-config compression.type=gzip
K kafka-configs.sh --bootstrap-server "$BS" --describe \
  --entity-type topics --entity-name s04_perf

# 이 상태에서 프로듀서가 lz4 로 보내면 브로커가 풀어서 gzip 으로 다시 압축합니다.
# zero-copy 가 깨지고 브로커 CPU 가 2~3배로 뜁니다.
K kafka-configs.sh --bootstrap-server "$BS" --alter \
  --entity-type topics --entity-name s04_perf --delete-config compression.type

# ---------------------------------------------------------------------------
# [4-11] 최대 함정 D — 순서 뒤바뀜 (Practice.java)
# ---------------------------------------------------------------------------
hr "[4-11] 함정 D — 순서 역전"

docker cp "$(dirname "$0")/Practice.java" kafka-1:/tmp/

echo
echo "  [터미널 B] 에서 아래 명령을 준비하십시오. 전송이 시작되면 실행합니다."
echo "      docker compose restart kafka-2"
echo "  재시도가 한 번도 안 일어나면 순서는 그대로이고 함정이 재현되지 않습니다."
read -r -p "  준비되면 Enter: " _

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java order-break'

echo "--- 저장된 순서에서 역전 지점 찾기 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_order --from-beginning \
  --max-messages 200 --timeout-ms 20000 --property print.value=true 2>/dev/null \
  | awk -F'-' '{n=$2+0; if (n < prev) printf "역전: %s (직전 seq-%03d)\n", $0, prev; prev=n}'

# 역전이 나왔다면 함정 재현 성공입니다.
# 같은 코드에서 enable.idempotence 만 켜면 역전이 사라집니다:
K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s04_order
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_order \
  --partitions 1 --replication-factor 3

read -r -p "  다시 [터미널 B] 에서 브로커를 흔들 준비 후 Enter: " _
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java order-safe'

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_order --from-beginning \
  --max-messages 200 --timeout-ms 20000 --property print.value=true 2>/dev/null \
  | awk -F'-' '{n=$2+0; if (n < prev) printf "역전: %s\n", $0; prev=n}' \
  | { grep . || echo "역전 0건 — 멱등 프로듀서가 순서를 지켰습니다."; }

# ---------------------------------------------------------------------------
# [4-11b] max.in.flight=1 의 대가
# ---------------------------------------------------------------------------
hr "[4-11b] max.in.flight=1 처리량"

K kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 500000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all enable.idempotence=false \
    max.in.flight.requests.per.connection=1 | tail -1

K kafka-producer-perf-test.sh \
  --topic s04_perf --num-records 500000 --record-size 100 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all enable.idempotence=true | tail -1

# in-flight=1 은 왕복이 직렬화되어 처리량이 반토막 납니다.
# 멱등 프로듀서는 in-flight 5 를 유지하면서 순서를 지킵니다. 비교가 성립하지 않습니다.

# ---------------------------------------------------------------------------
# [4-13] 전송 3패턴 성능 비교
# ---------------------------------------------------------------------------
hr "[4-13] fire-and-forget / callback / sync"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java sync-vs-async'
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java callback'
docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java key-route'

# callback 시나리오는 콜백이 실행되는 스레드 이름을 찍습니다.
# kafka-producer-network-thread | producer-1  → Sender 스레드입니다.
# 여기서 무거운 일을 하면 모든 파티션의 전송이 멈춥니다.

# ---------------------------------------------------------------------------
# [4-14] 정리
# ---------------------------------------------------------------------------
hr "[4-14] 정리"

for T in s04_acks s04_order s04_perf; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" 2>/dev/null || true
done
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s04_' \
  || echo "s04_ 토픽 없음 — 정리 완료"

# 4-4 에서 브로커를 내렸으므로 3대가 모두 살아 있는지 반드시 확인합니다.
docker compose ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null \
  || docker ps --filter name=kafka --format 'table {{.Names}}\t{{.Status}}'

echo
echo "Step 04 완료. (healthy) 가 3개가 아니면 Step 05 로 넘어가지 마십시오."
