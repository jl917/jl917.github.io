#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 11 — 성능 튜닝 : 연습문제 (7문제)
#
# 실행법:
#   bash step-11-performance/exercise.sh
#   (권장) 편집기로 열어 `# 여기에 작성:` 을 채우고 한 문제씩 실행
#
# ⚠️ 이 스텝의 문제는 "정답 명령" 뿐 아니라 **여러분 환경의 숫자를 표에 적는 것**
#    까지가 문제입니다. 적지 않으면 문제 3 의 무릎 지점 계산을 할 수 없습니다.
#    교재의 숫자와 다른 것이 정상입니다.
#
# 정답: solution.sh
# ---------------------------------------------------------------------------
set -uo pipefail

BS=kafka-1:9092
TOPIC=s11_ex
RECORDS=${RECORDS:-300000}
SIZE=1024

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }

# ===========================================================================
# 준비 — 문제지가 만들어 주는 것
#   본문의 orders-perf 는 6 파티션이지만, 여기는 3 파티션입니다.
#   문제 6 에서 "컨슈머 > 파티션" 상황을 컨슈머 4개만으로 만들기 위해서입니다.
# ===========================================================================
echo "### 준비: s11_ex 토픽 (3 파티션 / RF 3 / segment 256MiB / retention 1h)"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic $TOPIC --partitions 3 --replication-factor 3 \
  --config segment.bytes=268435456 --config retention.ms=3600000 2>/dev/null || true


# ===========================================================================
# 문제 1.
#   같은 조건에서 acks=1 과 acks=all 의 처리량과 p99 를 각각 측정하고,
#   몇 배 차이인지 계산하십시오.
#   고정 조건: --record-size 1024, --throughput -1, linger.ms=5, batch.size=65536
# ===========================================================================
echo "### 문제 1"
# (a) acks=1
# 여기에 작성:


# (b) acks=all
# 여기에 작성:


# 측정값 기록:
#   acks=1   : ______ msg/s , p99 ______ ms
#   acks=all : ______ msg/s , p99 ______ ms
#   배율     : ______ 배
#   (1.3~1.6배가 정상 범위입니다. 3배 이상이면 다른 문제를 의심하십시오)


# ===========================================================================
# 문제 2.
#   linger.ms 를 0 / 10 / 50 으로 바꿔가며 --print-metrics 로
#   records-per-request-avg 와 batch-size-avg 가 어떻게 변하는지 확인하십시오.
#
#   힌트: --print-metrics 는 200줄 넘게 쏟아냅니다. grep 필수.
#         grep -E 'records-per-request-avg|batch-size-avg'
# ===========================================================================
echo "### 문제 2"
for L in 0 10 50; do
  echo "=== linger.ms=$L ==="
  # 여기에 작성:

done

# 측정값 기록:
#   linger.ms=0  : records-per-request-avg ______ , batch-size-avg ______
#   linger.ms=10 : records-per-request-avg ______ , batch-size-avg ______
#   linger.ms=50 : records-per-request-avg ______ , batch-size-avg ______
#   → records-per-request-avg 가 몇 이하면 "배치가 사실상 없다" 고 봐야 합니까?
#     답:


# ===========================================================================
# 문제 3.
#   --throughput 을 단계적으로 올려 **무릎 지점**을 찾으십시오.
#   무릎 = "목표 처리량을 더 이상 달성하지 못하고 p99 가 급격히 꺾이는 지점"
#   찾은 뒤, 운영 용량으로 얼마를 제안하겠습니까?
#
#   힌트: 값의 범위는 여러분이 정합니다. 아래 배열을 채우십시오.
# ===========================================================================
echo "### 문제 3"
for T in 10000 ; do   # ← 여기에 단계를 추가하십시오 (예: 10000 20000 40000 60000 -1)
  echo "=== throughput=$T ==="
  # 여기에 작성:

done

# 측정값 기록:
#   목표 10000 : 달성 ______ , p99 ______ ms
#   목표 _____ : 달성 ______ , p99 ______ ms
#   목표 _____ : 달성 ______ , p99 ______ ms
#   목표 -1    : 달성 ______ , p99 ______ ms   ← 상한
#   무릎 지점      : ______ msg/s
#   제안 운영 용량 : ______ msg/s   (근거는?)


# ===========================================================================
# 문제 4.
#   compression.type 을 none / lz4 / gzip 으로 바꿔 처리량과
#   **브로커 CPU** 를 함께 재십시오.
#
#   ⚠️ 랜덤 바이트(--record-size)로 압축을 재면 압축률이 1.0 으로 나옵니다.
#      아래에서 문제지가 /tmp/ex_payload.txt 를 만들어 둡니다. --payload-file 을 쓰십시오.
#
#   # [터미널 B] 측정이 도는 동안 다른 창에서 계속 찍으십시오:
#   #   watch -n 1 'docker stats --no-stream --format "{{.Name}} {{.CPUPerc}}" kafka-1'
# ===========================================================================
echo "### 문제 4"
docker exec kafka-1 sh -c 'for i in $(seq 1 1000); do
  echo "{\"order_id\":\"O-$i\",\"customer_id\":\"C00$((i%10))\",\"amount\":39000,\"status\":\"CREATED\"}"
done > /tmp/ex_payload.txt'

for C in none lz4 gzip; do
  echo "=== compression=$C ==="
  # 여기에 작성:

done

# 측정값 기록:
#   none : ______ msg/s , 브로커 CPU ______ %
#   lz4  : ______ msg/s , 브로커 CPU ______ %
#   gzip : ______ msg/s , 브로커 CPU ______ %
#   → gzip 을 써야 할 이유가 하나라도 있습니까?
#     답:


# ===========================================================================
# 문제 5.
#   한산한 토픽에서 fetch.min.bytes=100000 컨슈머가
#   **한 건을 받기까지 몇 ms 가 걸리는지** 측정하고,
#   fetch.max.wait.ms 를 낮춰 개선하십시오.
#
#   힌트: date +%s%3N 으로 앞뒤 시각을 찍어 차이를 냅니다.
#         --max-messages 1 로 한 건만 읽고 종료하게 하면 측정이 쉽습니다.
# ===========================================================================
echo "### 문제 5"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s11_ex_quiet --partitions 1 --replication-factor 3 2>/dev/null || true

docker exec kafka-1 sh -c 'cat > /tmp/ex_slow.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=500
EOF
cat > /tmp/ex_fast.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=50
EOF'

# (a) 메시지 1건을 쓰고, 기본 설정 컨슈머로 읽기까지의 ms 측정
echo '{"order_id":"O-9001","status":"CREATED"}' | docker exec -i kafka-1 \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s11_ex_quiet
T0=$(date +%s%3N)
# 여기에 작성:  (기본 설정으로 --max-messages 1 읽기)

T1=$(date +%s%3N)
echo "  기본값       : $((T1 - T0)) ms"

# (b) /tmp/ex_slow.properties (fetch.max.wait.ms=500) 로 같은 측정
# 여기에 작성:


# (c) /tmp/ex_fast.properties (fetch.max.wait.ms=50) 로 같은 측정
# 여기에 작성:


# 측정값 기록:
#   기본값(fetch.min.bytes=1)          : ______ ms
#   fetch.min.bytes=100000, wait=500  : ______ ms
#   fetch.min.bytes=100000, wait=50   : ______ ms
#   → 두 번째 숫자가 무엇과 정확히 일치합니까? 이게 왜 진단의 열쇠입니까?
#     답:


# ===========================================================================
# 문제 6.
#   s11_ex 는 파티션이 3개입니다. 컨슈머를 4개 띄우고
#   --describe 출력에서 **노는 컨슈머**를 찾아내십시오.
#
#   문제지가 백그라운드로 4개를 띄웁니다. 여러분은 --describe 를 채웁니다.
# ===========================================================================
echo "### 문제 6"
K kafka-producer-perf-test.sh --topic $TOPIC --num-records 200000 \
  --record-size 512 --throughput -1 \
  --producer-props bootstrap.servers=$BS acks=1 >/dev/null 2>&1

for n in 1 2 3 4; do
  docker exec -d kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server $BS --topic $TOPIC --group s11-ex-many \
    --consumer-property client.id=ex-consumer-$n
done
sleep 12   # 리밸런싱이 끝나기를 기다립니다

# (a) 파티션 할당 확인
# 여기에 작성:


# 관찰 기록:
#   출력 줄 수                : ______
#   서로 다른 CONSUMER-ID 수  : ______
#   4번째 컨슈머는 어디에 나옵니까?
#     답:


# ===========================================================================
# 문제 7.
#   컨슈머 그룹 s11-ex-many 의 총 랙을 10초 간격으로 6회 찍고,
#   감소율로 소진 예상 시각을 계산하십시오.
#
#   힌트: --describe 출력의 6번째 컬럼이 LAG 입니다.
#         '-' 가 들어간 줄이 섞이므로 awk 정규식으로 숫자만 골라야 합니다.
# ===========================================================================
echo "### 문제 7"
for i in $(seq 1 6); do
  echo -n "$(date '+%H:%M:%S')  "
  # 여기에 작성:  (총 랙을 한 줄로 출력)

  sleep 10
done

# 측정값 기록:
#   t=0s   total-lag ______
#   t=50s  total-lag ______
#   감소율        : ______ 건/초
#   소진 예상까지 : ______ 초
#   → 만약 랙이 **늘고** 있었다면 이 계산이 성립합니까? 그때의 진짜 데드라인은?
#     답:


# ===========================================================================
# 정리 — 문제를 다 못 풀었어도 이 블록만 실행하면 환경이 깨끗해집니다
#   ⚠️ 문제 6 의 백그라운드 컨슈머를 안 죽이면 계속 돌면서 CPU 를 씁니다.
# ===========================================================================
echo "### 정리"
docker exec kafka-1 sh -c 'pkill -f kafka-console-consumer || true' 2>/dev/null || true
sleep 3
K kafka-topics.sh --bootstrap-server $BS --delete --topic $TOPIC        2>/dev/null || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s11_ex_quiet  2>/dev/null || true
K kafka-consumer-groups.sh --bootstrap-server $BS --delete --group s11-ex-many 2>/dev/null || true
echo "남은 토픽:"; K kafka-topics.sh --bootstrap-server $BS --list
