#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 11 — 성능 튜닝 : 연습문제 정답 + 해설
#
# 실행법:
#   bash step-11-performance/solution.sh
#
# exercise.sh 를 직접 풀어 본 뒤에 여십시오.
# 숫자는 환경마다 다릅니다. 해설은 "배율" 과 "패턴" 에 초점을 맞춥니다.
# ---------------------------------------------------------------------------
set -uo pipefail

BS=kafka-1:9092
TOPIC=s11_ex
RECORDS=${RECORDS:-300000}
SIZE=1024

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }

echo "### 준비"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic $TOPIC --partitions 3 --replication-factor 3 \
  --config segment.bytes=268435456 --config retention.ms=3600000 2>/dev/null || true


# ===========================================================================
# 정답 1 — acks=1 vs acks=all
# ===========================================================================
echo "### 정답 1"
for A in 1 all; do
  echo -n "acks=$A  → "
  K kafka-producer-perf-test.sh \
    --topic $TOPIC --num-records $RECORDS --record-size $SIZE --throughput -1 \
    --producer-props bootstrap.servers=$BS acks=$A linger.ms=5 batch.size=65536 | tail -1
done

# 해설:
#   예시 출력:
#     acks=1   → 300000 records sent, 74812.1 records/sec (73.06 MB/sec), ... 118 ms 99th, ...
#     acks=all → 300000 records sent, 51204.7 records/sec (50.00 MB/sec), ... 186 ms 99th, ...
#     배율: 74812 / 51205 = 약 1.46배
#
#   **1.3~1.6배가 정상 범위입니다.** acks=all 은 리더가 ISR 전원의 복제를 확인한 뒤
#   응답하므로, 브로커 간 왕복 한 번이 추가로 붙습니다. 로컬 도커 3대는 네트워크
#   지연이 거의 0 이라 이 정도로 끝납니다. 리전이 갈린 클러스터라면 훨씬 큽니다.
#
#   ⚠️ 3배 이상 차이가 난다면 acks 때문이 아닙니다. 다음을 의심하십시오:
#     ① ISR 이 축소되어 있다 (팔로워가 못 따라오는 중)
#     ② min.insync.replicas 를 못 채워 프로듀서가 재시도하고 있다
#     ③ 특정 브로커의 디스크가 느리다
#   확인:
#     K kafka-topics.sh --bootstrap-server $BS --describe --topic s11_ex | grep Isr
#   Isr 이 Replicas 보다 짧으면 ①·② 입니다. Step 08 로 돌아가십시오.
#
#   운영에서라면: 이 1.5배는 "유실 없음" 의 가격입니다. 결제·정산 파이프라인에서
#   이걸 아끼려고 acks=1 로 내리는 것은 거의 항상 잘못된 선택입니다.
K kafka-topics.sh --bootstrap-server $BS --describe --topic $TOPIC | grep Isr || true


# ===========================================================================
# 정답 2 — linger.ms 와 배치 메트릭
# ===========================================================================
echo "### 정답 2"
for L in 0 10 50; do
  echo "=== linger.ms=$L ==="
  K kafka-producer-perf-test.sh \
    --topic $TOPIC --num-records $RECORDS --record-size $SIZE --throughput -1 \
    --producer-props bootstrap.servers=$BS acks=all linger.ms=$L batch.size=65536 \
    --print-metrics 2>/dev/null \
    | grep -E 'records-per-request-avg|batch-size-avg|record-send-rate' || true
done

# 해설:
#   예시:
#     linger.ms=0  : records-per-request-avg 3.412  , batch-size-avg 4128.7
#     linger.ms=10 : records-per-request-avg 41.208 , batch-size-avg 42904.1
#     linger.ms=50 : records-per-request-avg 63.874 , batch-size-avg 65193.6
#
#   **records-per-request-avg 가 10 미만이면 배치가 사실상 없는 상태입니다.**
#   linger.ms=0 에서 3.4 라는 것은 요청 하나에 레코드 3~4개만 실려 나간다는 뜻이고,
#   요청 헤더와 왕복 비용을 레코드 3개가 나눠 지고 있다는 뜻입니다.
#
#   batch-size-avg 를 batch.size(65536) 와 나눠 보십시오.
#     linger=0  : 4128 / 65536 = 6%   → 배치가 거의 안 참. linger 를 올릴 여지 큼
#     linger=10 : 42904 / 65536 = 65% → 잘 차고 있음
#     linger=50 : 65193 / 65536 = 99% → 포화. 더 올려도 batch.size 가 상한
#   **이 한 숫자만 보면 "linger 를 올릴 여지가 있는가" 를 즉시 판단할 수 있습니다.**
#   99% 에 도달했다면 그다음은 linger 가 아니라 batch.size 를 키울 차례입니다.
#
#   운영에서라면: 이 메트릭들은 JMX 로도 나옵니다(kafka.producer:type=producer-metrics).
#   대시보드에 batch-size-avg 하나만 걸어 두어도 프로듀서 튜닝의 8할이 보입니다.


# ===========================================================================
# 정답 3 — 무릎 지점 찾기
# ===========================================================================
echo "### 정답 3"
for T in 10000 20000 40000 60000 -1; do
  echo -n "throughput=$T  → "
  K kafka-producer-perf-test.sh \
    --topic $TOPIC --num-records $RECORDS --record-size $SIZE --throughput $T \
    --producer-props bootstrap.servers=$BS acks=all linger.ms=5 batch.size=65536 | tail -1
done

# 해설:
#   예시:
#     10000 → 달성 9999.4  , p99 8 ms     ← 여유
#     20000 → 달성 19998.1 , p99 13 ms    ← 여유
#     40000 → 달성 39994.7 , p99 34 ms    ← 슬슬
#     60000 → 달성 52108.3 , p99 241 ms   ← 목표 미달 + p99 급등 = **무릎**
#     -1    → 달성 51204.7 , p99 186 ms   ← 상한
#
#   무릎을 판정하는 두 신호가 **동시에** 나타납니다.
#     ① 목표 처리량을 달성하지 못한다 (60000 요청 → 52108 달성)
#     ② p99 가 이전 단계 대비 한 자리 수 배로 뛴다 (34ms → 241ms)
#   둘 중 하나만 있으면 아직 무릎이 아닙니다.
#
#   제안 운영 용량: **무릎의 절반 이하**, 여기서는 25,000 msg/s 정도.
#   근거는 여유율이 아니라 **비선형성** 입니다. 무릎 근처에서 운영하면
#   트래픽이 20% 튀는 순간 p99 가 10배가 됩니다. 30% 여유는 여유가 아닙니다.
#   무릎 이전 구간은 지연이 완만하게 늘지만, 무릎 이후는 절벽입니다.
#
#   운영에서라면: 이 측정을 **분기마다 반복**하십시오. 파티션이 늘고 토픽이 늘고
#   다른 팀의 트래픽이 붙으면서 무릎은 계속 왼쪽으로 이동합니다.


# ===========================================================================
# 정답 4 — 압축과 브로커 CPU
# ===========================================================================
echo "### 정답 4"
docker exec kafka-1 sh -c 'for i in $(seq 1 1000); do
  echo "{\"order_id\":\"O-$i\",\"customer_id\":\"C00$((i%10))\",\"amount\":39000,\"status\":\"CREATED\"}"
done > /tmp/ex_payload.txt'

for C in none lz4 gzip; do
  echo "=== compression=$C ==="
  K kafka-producer-perf-test.sh \
    --topic $TOPIC --num-records $RECORDS --throughput -1 \
    --payload-file /tmp/ex_payload.txt \
    --producer-props bootstrap.servers=$BS acks=all linger.ms=20 \
                     batch.size=65536 compression.type=$C | tail -1
  docker stats --no-stream --format '  broker CPU: {{.CPUPerc}}' kafka-1
done

# 해설:
#   예시:
#     none : 126903 msg/s , 브로커 CPU 18%
#     lz4  : 168412 msg/s , 브로커 CPU 26%
#     gzip :  74208 msg/s , 브로커 CPU 61%
#
#   압축을 켰는데 **처리량이 올라간 것**이 이 문제의 핵심입니다. CPU 를 더 쓰는데도
#   빨라지는 이유는 네트워크로 흘려보낼 바이트가 1/3 로 줄었기 때문입니다.
#   Kafka 에서 병목은 대개 CPU 가 아니라 네트워크와 디스크입니다.
#
#   gzip 만 예외입니다. CPU 를 lz4 의 2배 이상 쓰면서 처리량은 절반이고,
#   압축률조차 zstd 보다 나쁩니다.
#   **결론 한 줄: lz4 아니면 zstd. gzip 은 레거시 호환 외에는 쓸 이유가 없습니다.**
#     - 지연/처리량 우선 → lz4
#     - 저장 공간 우선   → zstd
#
#   ⚠️ 랜덤 바이트(--record-size)로 이 측정을 하면 세 값이 거의 같게 나옵니다.
#      랜덤 데이터는 압축이 안 되기 때문입니다. 압축 벤치마크에서 가장 흔한 실수이며,
#      그래서 이 문제는 --payload-file 을 강제합니다.
#
#   운영에서라면: 토픽의 compression.type 은 'producer' 로 두십시오.
#   구체적 코덱을 지정해 두고 프로듀서가 다른 코덱을 쓰면 브로커가 **재압축**을 하고,
#   제로카피가 깨지면서 CPU 가 튑니다. 그런데 에러는 안 납니다.


# ===========================================================================
# 정답 5 — fetch.min.bytes 가 만드는 지연
# ===========================================================================
echo "### 정답 5"
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

measure() {
  local cfg="$1" label="$2" t0 t1
  echo '{"order_id":"O-9001","status":"CREATED"}' | docker exec -i kafka-1 \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s11_ex_quiet
  t0=$(date +%s%3N)
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server $BS --topic s11_ex_quiet --from-beginning --max-messages 1 \
    ${cfg:+--consumer.config "$cfg"} >/dev/null 2>&1 || true
  t1=$(date +%s%3N)
  echo "  $label : $((t1 - t0)) ms"
}

measure ""                      "기본값 (fetch.min.bytes=1)"
measure /tmp/ex_slow.properties "fetch.min.bytes=100000, wait=500"
measure /tmp/ex_fast.properties "fetch.min.bytes=100000, wait=50"

# 해설:
#   예시:
#     기본값                           :   2 ms  (+ JVM 기동 오버헤드)
#     fetch.min.bytes=100000, wait=500 : 503 ms
#     fetch.min.bytes=100000, wait=50  :  51 ms
#
#   두 번째 숫자가 **fetch.max.wait.ms 와 정확히 일치**합니다. 이것이 진단의 열쇠입니다.
#   브로커는 100 KB 가 모일 때까지 요청을 붙잡고 있다가, 안 모이면 wait 시간이
#   다 됐을 때 있는 만큼만 돌려줍니다. 한산한 토픽에서는 **항상** wait 시간을 다 씁니다.
#
#   현장에서 이렇게 판단하십시오:
#     "지연이 딱 500ms 근처에서 **일정하게** 나온다"
#     → 무작위 변동이 아니라 상수. 상수는 설정값입니다. fetch.max.wait.ms 를 보십시오.
#   지연이 들쭉날쭉하면 부하나 GC 이고, 상수에 붙어 있으면 타임아웃 설정입니다.
#
#   ⚠️ 이 함정이 지독한 이유는 **부하에 따라 증상이 나타났다 사라진다**는 것입니다.
#      낮(트래픽 많음) 정상 → 새벽(트래픽 적음) 느림 → 부하 테스트(트래픽 많음) 정상.
#      재현이 안 되고, 로그에 아무것도 없고, 알림도 안 울립니다. 500ms 는 에러가 아니니까요.
#
#   운영에서라면: fetch.min.bytes 와 fetch.max.wait.ms 는 **항상 세트로** 관리하십시오.
#   하나만 바꾸는 것은 절반만 설정한 것입니다. 최악 지연은 정확히 fetch.max.wait.ms 입니다.


# ===========================================================================
# 정답 6 — 컨슈머 > 파티션
# ===========================================================================
echo "### 정답 6"
K kafka-producer-perf-test.sh --topic $TOPIC --num-records 200000 \
  --record-size 512 --throughput -1 \
  --producer-props bootstrap.servers=$BS acks=1 >/dev/null 2>&1

for n in 1 2 3 4; do
  docker exec -d kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server $BS --topic $TOPIC --group s11-ex-many \
    --consumer-property client.id=ex-consumer-$n
done
sleep 12

K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s11-ex-many || true

# 해설:
#   예시:
#     GROUP        TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG    CONSUMER-ID              ...
#     s11-ex-many  s11_ex   0          21044           66801           45757  consumer-s11-ex-many-1-...
#     s11-ex-many  s11_ex   1          20988           66740           45752  consumer-s11-ex-many-2-...
#     s11-ex-many  s11_ex   2          21107           66852           45745  consumer-s11-ex-many-3-...
#
#   **출력 줄이 3개뿐이고, 서로 다른 CONSUMER-ID 도 3개입니다.**
#   4번째 컨슈머는 어느 줄에도 나타나지 않습니다. 파티션을 하나도 할당받지
#   못했기 때문입니다. 프로세스는 살아 있고, 하트비트를 보내고,
#   로그에는 'Successfully joined group' 까지 찍혀 있습니다. 아무 일도 안 할 뿐입니다.
#
#   진단 절차 두 줄:
#     ① --describe 출력의 **줄 수**를 센다 → 파티션 수와 같으면 상한
#     ② 서로 다른 CONSUMER-ID 수를 센다   → 줄 수보다 적으면 아직 여유
#
#   장애 대응 중에 이걸 모르면 "파드를 계속 늘리는데 랙이 안 줄어든다" 로 30분을 씁니다.
#   게다가 파드를 늘릴 때마다 리밸런싱이 일어나 **오히려 잠시 더 느려집니다.**
#
#   해결은 파티션 증설이지만 대가가 있습니다. 같은 customer_id 가 다른 파티션으로
#   가면서 **키 기반 순서 보장이 그 순간 깨집니다**(Step 03). 그래서 파티션 수는
#   처음 설계할 때 여유 있게 잡는 것이 정석입니다.
#   임시 대응으로 컨슈머 내부 워커 풀 병렬화가 있지만, 오프셋 커밋 관리가
#   어려워지고 순서가 깨집니다(Step 06·07).


# ===========================================================================
# 정답 7 — 랙 추이와 소진 예상
# ===========================================================================
echo "### 정답 7"
for i in $(seq 1 6); do
  echo -n "$(date '+%H:%M:%S')  "
  K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s11-ex-many 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print "total-lag=" s+0}'
  sleep 10
done

# 해설:
#   예시:
#     10:22:01  total-lag=137254
#     10:22:11  total-lag=112038
#     10:22:21  total-lag=86901
#     10:22:31  total-lag=61744
#     10:22:41  total-lag=36612
#     10:22:51  total-lag=11488
#
#   계산:
#     감소량   = 137254 - 11488 = 125766 건
#     경과     = 50 초
#     감소율   = 125766 / 50 = 약 2515 건/초
#     소진까지 = 11488 / 2515 = 약 4.6 초
#
#   awk 정규식 $6 ~ /^[0-9]+$/ 이 중요합니다. LAG 컬럼에 '-' 가 들어간 줄
#   (컨슈머가 없는 파티션)이 섞이면 합계가 어긋납니다.
#
#   ⚠️ 랙이 **늘고** 있으면 이 계산은 성립하지 않습니다. 생산 > 소비이므로
#      소진 시각이라는 것 자체가 없습니다. 그때의 진짜 데드라인은 **retention.ms** 입니다.
#      가장 오래된 미처리 메시지가 보존 기간을 넘기는 순간, 그 메시지는
#      **읽히지 못한 채 삭제됩니다**(Step 09). 컨슈머는 조용히 다음 오프셋으로
#      건너뛰고, 에러는 나지 않습니다. 랙이 무서운 진짜 이유가 이것입니다.
#
#   운영에서라면: 랙 알림은 **절대값이 아니라 추세**로 거십시오.
#   "랙 10만 초과" 는 배치 시작 직후마다 울려서 곧 무시하게 됩니다.
#   "랙이 5분 연속 증가" 가 훨씬 유용한 알림입니다.


# ===========================================================================
# 정리
# ===========================================================================
echo "### 정리"
docker exec kafka-1 sh -c 'pkill -f kafka-console-consumer || true' 2>/dev/null || true
sleep 3
K kafka-topics.sh --bootstrap-server $BS --delete --topic $TOPIC       2>/dev/null || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s11_ex_quiet 2>/dev/null || true
K kafka-consumer-groups.sh --bootstrap-server $BS --delete --group s11-ex-many 2>/dev/null || true
echo "남은 토픽:"; K kafka-topics.sh --bootstrap-server $BS --list
echo "=== Step 11 solution 완료 ==="
