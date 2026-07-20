#!/usr/bin/env bash
#
# Step 04 — 프로듀서 : 연습문제 정답과 해설
#
# 실행법:
#   bash solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여십시오.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

N=300000
hr() { echo; echo "======== $* ========"; }

recreate() {  # $1=토픽 $2=파티션 [$3=추가설정...]
  local t="$1" p="$2"; shift 2
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
  sleep 3
  K kafka-topics.sh --bootstrap-server "$BS" --create --topic "$t" \
    --partitions "$p" --replication-factor 3 "$@"
}

recreate s04_ex_perf  6 --config segment.bytes=268435456
recreate s04_ex_key   3
recreate s04_ex_order 1


# ===========================================================================
# 정답 1. acks 실측
# ===========================================================================
hr "정답 1 — acks=0/1/all"

run_acks() {
  local a="$1"
  local out
  out=$(K kafka-producer-perf-test.sh --topic s04_ex_perf \
        --num-records "$N" --record-size 100 --throughput -1 \
        --producer-props bootstrap.servers="$BS" acks="$a" | tail -1)
  echo "$out" | awk -v A="$a" '{
      for (i=1;i<=NF;i++) {
        if ($i=="records/sec") tp=$(i-1);
        if ($i=="99th,")       p99=$(i-2);
      }
      printf "  acks=%-4s  %10s msg/s   p99 %s ms\n", A, tp, p99;
  }'
}

for A in 0 1 all; do run_acks "$A"; done

# 해설:
#   기준 환경(노트북 3브로커)에서의 참고값입니다.
#     acks=0    118,168 msg/s   p99  28 ms
#     acks=1     92,408 msg/s   p99 447 ms
#     acks=all   51,203 msg/s   p99 806 ms
#
#   ★ 이 문제의 핵심은 절대값이 아니라 "비율" 입니다.
#     노트북 사양·Docker 메모리 할당에 따라 처리량은 3배까지 차이 납니다.
#     그러나 all/0 ≈ 0.43, 1/0 ≈ 0.78 이라는 비율은 어디서 재도 비슷하게 나옵니다.
#     면접에서든 용량 산정에서든 외워야 하는 것은 이 비율입니다.
#
#   ★ 왜 acks=0 의 지연이 이렇게 짧습니까?
#     acks=0 의 "지연" 은 브로커 왕복이 아니라 "버퍼에 넣고 소켓에 쓰기까지" 만 잽니다.
#     즉 acks=0 의 지연 숫자는 다른 두 값과 같은 의미가 아닙니다.
#     acks=0 이 "빠른" 게 아니라 "재는 대상이 다른" 것입니다.
#     이 사실이 4-4 의 함정과 정확히 같은 뿌리입니다.


# ===========================================================================
# 정답 2. 키 → 파티션 매핑
# ===========================================================================
hr "정답 2 — 키 라우팅"

for i in $(seq -w 1 10); do echo "C0$i:{\"customer_id\":\"C0$i\"}"; done \
  | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s04_ex_key \
      --property parse.key=true --property key.separator=:

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_ex_key \
  --from-beginning --max-messages 10 --timeout-ms 15000 \
  --property print.key=true --property print.partition=true \
  --property print.value=false | sort -k2

# 정답 표:
#   | 키    | 파티션 |
#   |-------|--------|
#   | C001  |   2    |
#   | C002  |   0    |
#   | C003  |   1    |
#   | C004  |   0    |
#   | C005  |   2    |
#   | C006  |   1    |
#   | C007  |   1    |
#   | C008  |   2    |
#   | C009  |   0    |
#   | C010  |   2    |
#
# 해설:
#   파티션 2 에 4개, 파티션 0 에 3개, 파티션 1 에 3개입니다.
#   ★ 완전히 균등하지 않다는 점이 이 문제의 포인트입니다.
#     murmur2 는 훌륭한 해시지만, 입력이 10개뿐이면 균등할 수가 없습니다.
#     (10을 3으로 나누면 나누어떨어지지 않습니다.)
#     키 종류가 수천 개면 대수의 법칙으로 균등해집니다.
#     그러나 "키의 개수" 가 아니라 "키별 트래픽 비중" 이 치우쳐 있으면
#     키가 아무리 많아도 쏠립니다 — 그것이 4-8 의 hot partition 입니다.
#
#   ★ 이 매핑은 파티션 수에 종속됩니다.
#     파티션을 3 → 6 으로 늘리면 위 표가 전부 바뀝니다.
#     C001 의 옛 주문은 파티션 2, 새 주문은 다른 파티션으로 갈라지고
#     그 순간 순서 보장이 깨집니다 (Step 03 의 함정).


# ===========================================================================
# 정답 3. linger.ms 변곡점
# ===========================================================================
hr "정답 3 — linger.ms 0 / 5 / 20 / 100"

for L in 0 5 20 100; do
  echo -n "  linger.ms=$L  "
  K kafka-producer-perf-test.sh --topic s04_ex_perf \
    --num-records "$N" --record-size 100 --throughput -1 \
    --producer-props bootstrap.servers="$BS" acks=all \
      batch.size=65536 linger.ms="$L" | tail -1
done

# 참고 결과:
#   linger.ms=0     51,203 msg/s   avg 388 ms   p99 806 ms
#   linger.ms=5     92,140 msg/s   avg 214 ms   p99 498 ms
#   linger.ms=20   138,504 msg/s   avg 141 ms   p99 347 ms
#   linger.ms=100  141,027 msg/s   avg 168 ms   p99 612 ms
#
# 정답:
#   - 처리량이 더 이상 늘지 않는 지점: 20ms  (20 → 100 은 +1.8% 뿐)
#   - p99 지연이 나빠지기 시작하는 지점: 100ms (347 → 612 ms)
#   → 실무 권장 구간은 5~20ms 입니다.
#
# 해설:
#   ★ 0 → 20 구간에서 지연이 "줄어드는" 것이 직관에 반합니다.
#     20ms 를 더 기다렸는데 왜 총 지연이 짧아집니까?
#     linger.ms=0 일 때는 작은 요청이 폭주해 브로커 앞에 큐가 쌓이고,
#     그 큐잉 대기가 300ms 였습니다. 배치를 키우면 요청 수가 1/8 이 되어
#     큐가 사라지고, 20ms 의 linger 를 포함해도 총 지연이 훨씬 짧아집니다.
#
#   ★ 100ms 에서 지연만 나빠지는 이유
#     이미 20ms 에 배치가 batch.size(64KiB)로 꽉 찹니다.
#     그래서 처리량은 더 안 늘고, 한산할 때만 100ms 를 꼬박 기다려
#     tail latency 만 늘어납니다.
#     ★ linger.ms 는 "지연을 추가하는 값" 이 아니라 "지연 상한을 정하는 값" 입니다.


# ===========================================================================
# 정답 4. 압축 코덱
# ===========================================================================
hr "정답 4 — 압축 5종"

for C in none gzip snappy lz4 zstd; do
  recreate s04_ex_perf 6 --config segment.bytes=268435456 >/dev/null
  echo -n "  $C  "
  K kafka-producer-perf-test.sh --topic s04_ex_perf \
    --num-records "$N" --record-size 200 --throughput -1 \
    --producer-props bootstrap.servers="$BS" acks=all \
      linger.ms=20 batch.size=65536 compression.type="$C" | tail -1
  docker exec kafka-1 sh -c 'du -sc /var/lib/kafka/data/s04_ex_perf-* | tail -1'
done

# 참고 결과 (100만 건 기준, 원본 200MB):
#   none    121,854 msg/s   200 MB   1.0x
#   gzip     46,219 msg/s    38 MB   5.3x
#   snappy  143,903 msg/s    71 MB   2.8x
#   lz4     156,372 msg/s    66 MB   3.0x
#   zstd    134,083 msg/s    44 MB   4.5x
#
# 정답:
#   (a) 처리량 최고        → lz4
#   (b) 저장 용량 최소      → gzip (38 MB)
#   (c) 네트워크가 비싼 환경 → zstd
#
# 해설:
#   ★ 압축을 켜면 처리량이 "오히려 늘어난다" 는 것이 핵심입니다.
#     lz4 는 none 대비 +28% 입니다. 네트워크로 보낼 바이트가 1/3 이 되어
#     절약된 I/O 가 압축 CPU 비용보다 크기 때문입니다.
#     gzip 만 예외인데, 압축률은 최고지만 CPU 를 너무 먹어
#     처리량이 none 의 38% 로 떨어집니다.
#
#   ★ (c)에서 gzip 이 아니라 zstd 인 이유
#     gzip 과 zstd 의 압축률 차이(5.3x vs 4.5x)는 15% 인데,
#     처리량 차이는 2.9배입니다. 대역폭 15% 를 아끼려고
#     처리량을 1/3 로 줄이는 거래는 거의 항상 손해입니다.
#     zstd 는 "압축률과 속도의 균형점" 이라 이런 상황의 정답이 됩니다.
#
#   ★ 압축은 배치 단위입니다.
#     batch.size 를 16KiB → 64KiB 로 키우면 압축률이 20~30% 더 좋아집니다.
#     "압축이 잘 안 된다" 싶으면 코덱을 바꾸기 전에 배치부터 키우십시오.
#
#   ★ 토픽의 compression.type 은 producer(기본) 로 두십시오.
#     다른 값을 주면 브로커가 풀었다 다시 압축하며 zero-copy 가 깨집니다.


# ===========================================================================
# 정답 5. 순서 역전 재현
# ===========================================================================
hr "정답 5 — 순서 역전"

recreate s04_ex_order 1 >/dev/null
docker cp "$(dirname "$0")/Practice.java" kafka-1:/tmp/

echo "  [터미널 B] 에서 전송 중에 'docker compose restart kafka-2' 를 실행하십시오."
read -r -p "  준비되면 Enter: " _

docker exec kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java order-break s04_ex_order'

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_ex_order \
  --from-beginning --max-messages 200 --timeout-ms 20000 \
  --property print.value=true 2>/dev/null \
  | awk -F'-' '{n=$2+0; if (n<prev) c++; prev=n} END{print "  " c+0 " 곳 역전"}'

# 참고 결과: 3 곳 역전 (재시도 횟수와 대체로 일치)
#
# 해설:
#   ★ 왜 재시도 횟수만큼 역전이 생깁니까?
#     in-flight 배치 5개 중 하나가 실패하면, 그 배치가 재시도되는 동안
#     뒤의 4개가 먼저 브로커에 기록됩니다. 재시도 배치는 그 뒤에 붙습니다.
#     로그 순서는 [배치2..5][배치1] 이 됩니다.
#     재시도 1회 = 역전 1곳입니다.
#
#   ★ 왜 batch.size=64, request.timeout.ms=300 같은 극단적 값을 씁니까?
#     기본값(16KiB, 30초)이면 200건이 배치 1~2개에 다 들어가고
#     타임아웃도 안 나서 재시도가 발생하지 않습니다.
#     즉 함정을 "만들기 위해" 조건을 극단화한 것입니다.
#     운영에서는 이 조합이 저절로 만들어집니다 —
#     트래픽이 많으면 배치가 여러 개가 되고, 브로커가 흔들리면 타임아웃이 납니다.
#     ★ "우리는 이런 설정 안 써" 가 아니라 "부하가 걸리면 저절로 이 상황이 된다" 입니다.
#
#   ★ 에러가 하나도 안 났다는 점에 주목하십시오.
#     프로듀서는 200건 전부 성공 콜백을 받았습니다.
#     Kafka 가 보장한다는 "파티션 내 순서" 는
#     브로커가 받은 순서에 대한 보장이지, send() 호출 순서에 대한 보장이 아닙니다.


# ===========================================================================
# 정답 6. 멱등 프로듀서
# ===========================================================================
hr "정답 6 — enable.idempotence=true"

recreate s04_ex_order 1 >/dev/null

echo "  다시 [터미널 B] 에서 브로커를 흔들 준비를 하십시오."
read -r -p "  준비되면 Enter: " _

docker exec kafka-1 sh -c \
  'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java order-safe s04_ex_order'

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s04_ex_order \
  --from-beginning --max-messages 200 --timeout-ms 20000 \
  --property print.value=true 2>/dev/null \
  | awk -F'-' '{n=$2+0; if (n<prev) c++; prev=n} END{print "  " c+0 " 곳 역전"}'

echo "  --- 처리량 비교 ---"
echo -n "  멱등 off, in-flight 5 : "
K kafka-producer-perf-test.sh --topic s04_ex_perf --num-records "$N" \
  --record-size 100 --throughput -1 --producer-props bootstrap.servers="$BS" \
  acks=all enable.idempotence=false | tail -1

echo -n "  멱등 off, in-flight 1 : "
K kafka-producer-perf-test.sh --topic s04_ex_perf --num-records "$N" \
  --record-size 100 --throughput -1 --producer-props bootstrap.servers="$BS" \
  acks=all enable.idempotence=false max.in.flight.requests.per.connection=1 | tail -1

echo -n "  멱등 on,  in-flight 5 : "
K kafka-producer-perf-test.sh --topic s04_ex_perf --num-records "$N" \
  --record-size 100 --throughput -1 --producer-props bootstrap.servers="$BS" \
  acks=all enable.idempotence=true | tail -1

# 참고 결과:
#   역전 0 곳
#   멱등 off, in-flight 5 :  51,203 msg/s   (순서 깨짐)
#   멱등 off, in-flight 1 :  28,742 msg/s   (순서 안전)
#   멱등 on,  in-flight 5 :  50,878 msg/s   (순서 안전)
#
# 해설:
#   ★ 멱등을 켜도 처리량은 0.6% 밖에 안 떨어집니다 (51,203 → 50,878).
#     반면 in-flight 를 1로 낮추면 44% 가 날아갑니다 (51,203 → 28,742).
#     "순서를 지키려면 in-flight 를 1로" 는 2017년의 조언입니다.
#     지금의 답은 언제나 enable.idempotence=true 입니다.
#
#   ★ 원리:
#     멱등 프로듀서는 배치마다 PID + 시퀀스 번호를 붙입니다.
#     브로커는 파티션마다 "이 PID 의 마지막 시퀀스" 를 기억하고 있어서
#       - 더 작은 시퀀스가 오면 → 중복. 버리고 성공 응답. (중복 제거)
#       - 더 큰 시퀀스가 오면   → 중간에 빠진 게 있음. 거절. (순서 보장)
#     즉 배치2가 배치1보다 먼저 도착하면 브로커가 배치2를 거절하고,
#     프로듀서가 배치1 → 배치2 순으로 다시 보냅니다.
#     브로커가 기억하는 시퀀스 윈도우가 5개여서 max.in.flight <= 5 제약이 붙습니다.
#
#   ★ Kafka 3.0 부터 enable.idempotence 의 기본값은 true 입니다.
#     켜지면 acks=all, retries=Integer.MAX_VALUE, max.in.flight<=5 가 함께 강제됩니다.
#     그런데 다음 경우 조용히 꺼집니다:
#       - acks=0 또는 acks=1 을 명시
#       - max.in.flight 를 6 이상으로 설정
#       - retries=0 을 명시
#     "처리량 튜닝한다고 acks=1 로 바꿨더니 순서가 깨지기 시작했다" 의 정체입니다.
#     프로듀서 기동 로그의 ProducerConfig values 에서 네 줄만 확인하면 됩니다:
#       acks = -1 / enable.idempotence = true /
#       max.in.flight.requests.per.connection = 5 / retries = 2147483647


# ===========================================================================
# 정답 7. send() 블로킹 → TimeoutException
# ===========================================================================
hr "정답 7 — buffer.memory 고갈"

set +e
K kafka-producer-perf-test.sh --topic s04_ex_perf \
  --num-records 500000 --record-size 1000 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all \
    buffer.memory=1048576 max.block.ms=2000 2>&1 | tail -6
set -e

# 나오는 예외:
#   org.apache.kafka.common.errors.TimeoutException: Failed to allocate memory
#     within the configured max blocking time 2000 ms.
#
# 해설:
#   ★ 왜 buffer.memory 를 줄여야만 재현됩니까?
#     기본 32MiB 는 어지간한 부하로는 안 찹니다.
#     운영에서 이 예외를 만나는 시점은 "브로커가 이미 느려진 뒤" 입니다.
#     즉 이 예외는 원인이 아니라 증상입니다.
#
#   ★ 그런데 이 예외가 나는 것이 "좋은 일" 입니다.
#     max.block.ms 의 기본값은 60초입니다.
#     HTTP 핸들러에서 producer.send() 를 부르는 흔한 코드가
#     브로커 장애 시 요청 스레드를 60초 동안 통째로 잡아먹습니다.
#     톰캣 스레드 풀이 말라붙고, Kafka 와 무관한 API 까지 전부 타임아웃납니다.
#     ★ max.block.ms 를 짧게 잡는 것은 "장애를 만드는" 설정이 아니라
#       "장애를 빨리 드러내는" 설정입니다.
#     운영 권장: max.block.ms 를 상위 요청 타임아웃보다 짧게 (예: 3000ms).
#     그러면 빠르게 실패하고, 애플리케이션이 폴백(로컬 파일 기록, 5xx 응답)을
#     선택할 수 있습니다. 60초 매달려 있는 것보다 3초 만에 실패하는 편이 낫습니다.
#
#   ★ 관련 설정 정리
#     buffer.memory      32 MiB   전체 배치 버퍼
#     max.block.ms       60000    send()/partitionsFor() 블로킹 상한
#     delivery.timeout.ms 120000  send() 부터 최종 결과까지의 총 상한
#     request.timeout.ms  30000   요청 하나의 응답 대기
#     제약: delivery.timeout.ms >= linger.ms + request.timeout.ms


# ===========================================================================
# 정리
# ===========================================================================
hr "정리"
for T in $(K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s04_ex_' || true); do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" || true
done
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s04_' \
  || echo "s04_ 토픽 없음 — 정리 완료"

docker compose ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null \
  || docker ps --filter name=kafka --format 'table {{.Names}}\t{{.Status}}'
