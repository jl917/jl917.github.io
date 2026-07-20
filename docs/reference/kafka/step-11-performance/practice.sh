#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Step 11 — 성능 튜닝 : 본문 실습 스크립트
#
# 실행법:
#   bash step-11-performance/practice.sh          # 통째로 (20~30분)
#   RECORDS=200000 bash step-11-performance/practice.sh   # 빠르게
#   bash -x step-11-performance/practice.sh       # 한 줄씩 확인하며
#
# ⚠️ 이 스크립트는 100만 건 측정을 열 번 넘게 돌립니다. 시간이 없으면
#    RECORDS 를 200000 으로 낮추십시오. 절대값은 달라지지만 배율은 유지됩니다.
# ---------------------------------------------------------------------------
set -euo pipefail

BS=kafka-1:9092
TOPIC=${TOPIC:-orders-perf}
RECORDS=${RECORDS:-1000000}
SIZE=${SIZE:-1024}

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }

# 프로듀서 측정 헬퍼: 마지막 요약 줄만 남깁니다.
#   run_producer acks=all linger.ms=20 batch.size=65536
run_producer() {
  K kafka-producer-perf-test.sh \
    --topic "$TOPIC" --num-records "$RECORDS" --record-size "$SIZE" --throughput -1 \
    --producer-props bootstrap.servers=$BS "$@" | tail -1
}

# 목표 처리량을 고정한 측정 (11-9 용)
run_producer_at() {
  local thr="$1"; shift
  K kafka-producer-perf-test.sh \
    --topic "$TOPIC" --num-records $((RECORDS * 6 / 10)) --record-size "$SIZE" --throughput "$thr" \
    --producer-props bootstrap.servers=$BS "$@" | tail -1
}

# ===========================================================================
# [11-1] 측정 전용 토픽 — segment.bytes 를 반드시 덮어씁니다
# ===========================================================================
echo "### [11-1] orders-perf 생성 (6 파티션 / RF 3 / segment 256MiB / retention 1h)"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic "$TOPIC" --partitions 6 --replication-factor 3 \
  --config segment.bytes=268435456 \
  --config retention.ms=3600000 || true

# 학습용 축소값(log.segment.bytes=1MiB)을 그대로 두면 세그먼트가 수천 개 생겨
# "Kafka 처리량" 이 아니라 "파일 생성 비용" 을 재게 됩니다. 실측 1.4배 차이.
K kafka-topics.sh --bootstrap-server $BS --describe --topic "$TOPIC"

# ===========================================================================
# [11-2] 기본 측정 — 출력 읽는 법
# ===========================================================================
echo "### [11-2] 기본 측정 (acks=1). 첫 진행 줄은 워밍업이라 버립니다"
K kafka-producer-perf-test.sh \
  --topic "$TOPIC" --num-records "$RECORDS" --record-size "$SIZE" --throughput -1 \
  --producer-props bootstrap.servers=$BS acks=1

# ===========================================================================
# [11-3] 매트릭스 ① acks : 0 / 1 / all
#   고정: linger.ms=0, batch.size=16384, compression=none
# ===========================================================================
echo "### [11-3] acks 매트릭스"
for A in 0 1 all; do
  echo -n "acks=$A  → "
  run_producer acks=$A linger.ms=0 batch.size=16384 compression.type=none
done
# 해석: acks=0 이 all 의 약 2.8배. 단 acks=0 은 "사라져도 모르는" 세계입니다(Step 04·08).
#      acks=1 과 all 의 차이는 1.5배뿐이며, linger 를 올리면 거의 사라집니다.

# ===========================================================================
# [11-4] 매트릭스 ② linger.ms : 0 / 5 / 20 / 100
#   고정: acks=all, batch.size=65536, compression=none
# ===========================================================================
echo "### [11-4] linger.ms 매트릭스"
for L in 0 5 20 100; do
  echo -n "linger.ms=$L  → "
  run_producer acks=all linger.ms=$L batch.size=65536 compression.type=none
done
# 해석: 0 → 20 은 처리량 3배 + p99 개선(782→168ms). 트레이드오프가 아니라 그냥 이득.
#      20 → 100 부터가 진짜 선택: 처리량 11% 얻고 p99 3배 손해.

# ===========================================================================
# [11-5] 매트릭스 ③ batch.size : 16K / 64K / 256K
#   고정: acks=all, linger.ms=5
# ===========================================================================
echo "### [11-5] batch.size 매트릭스"
for B in 16384 65536 262144; do
  echo -n "batch.size=$B  → "
  run_producer acks=all linger.ms=5 batch.size=$B compression.type=none
done
# 해석: batch.size 와 linger.ms 는 "둘 중 먼저 도달하는 쪽" 이 발송을 트리거하므로
#      따로 튜닝하면 안 됩니다. batch 를 키워도 linger=0 이면 차기 전에 나갑니다.

# ===========================================================================
# [11-6] 매트릭스 ④ compression.type : none / lz4 / zstd / gzip
#   ⚠️ --record-size 는 랜덤 바이트라 압축률이 1.0 으로 나옵니다.
#      현실적인 압축률을 재려면 --payload-file 로 실제 데이터를 써야 합니다.
# ===========================================================================
echo "### [11-6] 압축 측정용 현실적인 페이로드 생성"
docker exec kafka-1 sh -c 'for i in $(seq 1 1000); do
  echo "{\"order_id\":\"O-$i\",\"customer_id\":\"C00$((i%10))\",\"amount\":39000,\"status\":\"CREATED\"}"
done > /tmp/payload.txt; wc -l /tmp/payload.txt'

echo "### [11-6] compression.type 매트릭스"
for C in none lz4 zstd gzip; do
  echo -n "compression=$C  → "
  K kafka-producer-perf-test.sh \
    --topic "$TOPIC" --num-records "$RECORDS" --throughput -1 \
    --payload-file /tmp/payload.txt \
    --producer-props bootstrap.servers=$BS acks=all linger.ms=20 \
                     batch.size=65536 compression.type=$C | tail -1
done
# 해석: lz4 는 처리량까지 1.33배 올립니다(네트워크 바이트가 1/3 로 줄어서).
#      gzip 은 CPU 3배 쓰고 처리량 절반. 쓸 이유가 거의 없습니다.

echo "### [11-6] 토픽의 compression.type 확인 — 'producer' 여야 재압축이 안 일어납니다"
K kafka-configs.sh --bootstrap-server $BS \
  --describe --entity-type topics --entity-name "$TOPIC"

# ===========================================================================
# [11-7] 매트릭스 ⑤ 레코드 크기 : 100B / 1KB / 10KB (총 전송량 고정)
# ===========================================================================
echo "### [11-7] 레코드 크기 매트릭스 — msg/s 와 MB/s 가 반대로 움직입니다"
for pair in "100 $((RECORDS * 10))" "1024 $RECORDS" "10240 $((RECORDS / 10))"; do
  set -- $pair
  echo -n "record-size=$1 (n=$2)  → "
  K kafka-producer-perf-test.sh \
    --topic "$TOPIC" --num-records "$2" --record-size "$1" --throughput -1 \
    --producer-props bootstrap.servers=$BS acks=all linger.ms=20 \
                     batch.size=65536 compression.type=lz4 | tail -1
done
# 해석: "초당 40만 건" 은 레코드 크기 없이는 아무 의미가 없습니다.
#      용량 산정은 반드시 MB/s 로 환산해서 네트워크·디스크 한계와 비교하십시오.

# ===========================================================================
# [11-9] 함정 A — 목표 처리량을 고정해야 지연이 지연이다
#   이 스텝에서 가장 중요한 측정입니다. 시간이 없어도 여기만은 돌리십시오.
# ===========================================================================
echo "### [11-9] 목표 처리량을 단계적으로 올려 '무릎 지점' 찾기"
for T in 20000 40000 60000 80000 -1; do
  echo "=== throughput=$T ==="
  run_producer_at "$T" acks=all linger.ms=5 batch.size=65536
done
# 해석: 목표 20000 에서 p99 11ms, 무제한에서 168ms. 같은 클러스터인데 15배 차이.
#      무제한의 지연은 대부분 "버퍼에서 순서를 기다린 시간" 입니다.
#      절차: ① -1 로 상한을 찾고 ② 상한의 50~70% 로 지연을 재고
#            ③ SLO 를 만족하는지 보고 ④ 무릎 지점을 찾아 그 절반을 용량으로.

# ===========================================================================
# [11-10] kafka-consumer-perf-test.sh — CSV 출력
# ===========================================================================
echo "### [11-10] 컨슈머 기본 측정"
K kafka-consumer-perf-test.sh \
  --bootstrap-server $BS --topic "$TOPIC" --messages "$RECORDS" \
  --group s11-perf --timeout 60000
# 해석: MB.sec 이 아니라 fetch.MB.sec 을 보십시오.
#      앞의 것은 rebalance.time.ms 가 섞여 있어 짧은 측정일수록 왜곡됩니다.

echo "### [11-10] fetch 설정 조합"
docker exec kafka-1 sh -c 'cat > /tmp/c1.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=500
EOF
cat > /tmp/c2.properties <<EOF
fetch.min.bytes=100000
fetch.max.wait.ms=50
EOF
cat > /tmp/c3.properties <<EOF
max.partition.fetch.bytes=1048576
EOF
cat > /tmp/c4.properties <<EOF
max.poll.records=50
EOF'

i=0
for CFG in /tmp/c1.properties /tmp/c2.properties /tmp/c3.properties /tmp/c4.properties; do
  i=$((i+1))
  echo "=== $CFG ==="
  K kafka-consumer-perf-test.sh \
    --bootstrap-server $BS --topic "$TOPIC" --messages "$RECORDS" \
    --group "s11-perf-c$i" --consumer.config "$CFG" --timeout 60000 | tail -1
done
# 해석: fetch.min.bytes 1 → 100000 로 소비 속도 약 1.45배.
#      max.poll.records 500 → 50 은 오히려 느려집니다(poll 호출 오버헤드).

# ===========================================================================
# [11-11] 함정 B — fetch.min.bytes 가 만드는 "가끔 느린" 시스템
# ===========================================================================
echo "### [11-11] 한산한 토픽 준비"
K kafka-topics.sh --bootstrap-server $BS \
  --create --topic s11_quiet --partitions 1 --replication-factor 3 || true

# --- 터미널 2개 버전 ---------------------------------------------------------
# [터미널 B] 이 컨슈머를 먼저 띄우십시오:
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s11_quiet \
#     --consumer.config /tmp/c1.properties --property print.timestamp=true
# [터미널 A] 그다음 한 건 쓰고, 터미널 B 에 줄이 뜨기까지의 체감 시간을 보십시오:
#   echo '{"order_id":"O-1001","status":"CREATED"}' | docker exec -i kafka-1 \
#     /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka-1:9092 --topic s11_quiet
# ---------------------------------------------------------------------------

# --- 단일 터미널 자동 측정 버전 ---------------------------------------------
measure_e2e() {
  local cfg="$1" label="$2"
  echo '{"order_id":"O-1001","status":"CREATED"}' | docker exec -i kafka-1 \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BS --topic s11_quiet
  local t0 t1
  t0=$(date +%s%3N)
  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server $BS --topic s11_quiet --from-beginning --max-messages 1 \
    ${cfg:+--consumer.config "$cfg"} >/dev/null 2>&1 || true
  t1=$(date +%s%3N)
  echo "  $label : $((t1 - t0)) ms"
}

echo "### [11-11] 한 건이 도착하기까지의 시간"
measure_e2e ""                   "fetch.min.bytes=1 (기본값)"
measure_e2e /tmp/c1.properties   "fetch.min.bytes=100000, fetch.max.wait.ms=500"
measure_e2e /tmp/c2.properties   "fetch.min.bytes=100000, fetch.max.wait.ms=50"
# 해석: 2ms → 503ms → 51ms. 250배 차이인데 에러는 한 줄도 안 납니다.
#      낮에는 정상, 새벽에만 느립니다. 부하 테스트로는 절대 안 잡힙니다.
#      fetch.min.bytes 를 올릴 때는 fetch.max.wait.ms 를 반드시 함께 낮추십시오.

# ===========================================================================
# [11-12] 컨슈머 랙을 시간순으로 재기
# ===========================================================================
echo "### [11-12] 랙 추이 (10초 × 6회)"
for i in $(seq 1 6); do
  echo -n "$(date '+%H:%M:%S')  "
  K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s11-perf 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print "total-lag=" s+0}'
  sleep 10
done
# 해석: 줄고 있으면 감소율로 소진 시각을 계산할 수 있습니다.
#      늘고 있으면 대응해야 합니다. 그대로 두면 retention.ms 에 걸려
#      "읽지 못한 메시지가 삭제" 됩니다(Step 09). 랙이 무서운 진짜 이유입니다.

# ===========================================================================
# [11-13] 함정 C — 컨슈머 병렬성의 상한은 파티션 수
# ===========================================================================
echo "### [11-13] 현재 그룹의 파티션 할당 — 줄 수를 세십시오"
K kafka-consumer-groups.sh --bootstrap-server $BS --describe --group s11-perf || true
# 해석: 출력 줄 수 == 파티션 수 이고, 서로 다른 CONSUMER-ID 수도 그와 같다면
#      이미 상한입니다. 컨슈머를 더 띄워도 어느 줄에도 나타나지 않고 놉니다.
#      게다가 띄울 때마다 리밸런싱이 일어나 잠시 더 느려집니다.

# ===========================================================================
# [11-14] 브로커 측 — 페이지 캐시와 파일 디스크립터
# ===========================================================================
echo "### [11-14] 페이지 캐시 (buff/cache 가 클수록 컨슈머가 디스크에 안 갑니다)"
docker exec kafka-1 sh -c 'free -m' || true

echo "### [11-14] 파일 디스크립터 한도와 현재 사용량"
docker exec kafka-1 sh -c 'ulimit -n; ls /proc/1/fd | wc -l'
# 해석: 운영 권장 최소 100000. 부족하면 'Too many open files' 로 브로커가
#      새 커넥션도 못 받고 세그먼트도 못 만듭니다. 파티션 대량 증설 직후에 자주 터집니다.

# ===========================================================================
# [11-15] --print-metrics 해독
# ===========================================================================
echo "### [11-15] 주요 프로듀서 메트릭"
K kafka-producer-perf-test.sh \
  --topic "$TOPIC" --num-records $((RECORDS / 2)) --record-size "$SIZE" --throughput -1 \
  --producer-props bootstrap.servers=$BS acks=all linger.ms=20 batch.size=65536 \
  --print-metrics 2>/dev/null \
  | grep -E 'record-send-rate|batch-size-avg|request-latency-avg|records-per-request-avg|buffer-available-bytes|compression-rate-avg|record-queue-time-avg' || true
# 해석의 핵심:
#   request-latency-avg  = 브로커가 응답하는 데 걸린 순수 시간
#   record-queue-time-avg = 클라이언트 버퍼에서 대기한 시간
#   → 이 둘의 비율을 보십시오. 뒤쪽이 크면 브로커를 증설해도 효과가 없습니다.
#   batch-size-avg 가 batch.size 의 20% 미만이면 linger.ms 를 올릴 여지가 있습니다.
#   records-per-request-avg 가 1에 가까우면 배치가 전혀 안 되고 있다는 뜻입니다.
#   buffer-available-bytes 가 0 에 가까우면 send() 가 블로킹 중 = 11-9 의 함정 상태.

# ===========================================================================
# [11-17] 정리 (실습 마무리)
#   ⚠️ 이 정리를 건너뛰면 Step 12 실습 중 디스크가 부족할 수 있습니다.
# ===========================================================================
echo "### [11-17] 토픽 삭제"
K kafka-topics.sh --bootstrap-server $BS --delete --topic "$TOPIC"  || true
K kafka-topics.sh --bootstrap-server $BS --delete --topic s11_quiet || true

echo "### [11-17] 컨슈머 그룹 삭제"
K kafka-consumer-groups.sh --bootstrap-server $BS \
  --delete --group s11-perf --group s11-perf-c1 --group s11-perf-c2 \
           --group s11-perf-c3 --group s11-perf-c4 || true

echo "### [11-17] 남은 토픽 (공용 4개만 남아야 정상)"
K kafka-topics.sh --bootstrap-server $BS --list

echo "### [11-17] 디스크 회수 확인"
# 삭제 직후에는 -delete 접미사 디렉터리가 남아 있습니다.
# file.delete.delay.ms (기본 60초) 뒤에 실제로 지워집니다.
docker exec kafka-1 sh -c 'du -sh /var/lib/kafka/data | tail -1'
docker exec kafka-1 sh -c 'ls /var/lib/kafka/data | grep -- "-delete" || echo "  (대기 중인 삭제 없음)"'

echo "=== Step 11 practice 완료 ==="
