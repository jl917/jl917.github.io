#!/usr/bin/env bash
#
# Step 08 — 복제와 내구성 : 연습문제 정답과 해설
#
# 실행법:
#   cd kafka/docker && bash ../step-08-replication/solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
#
set -euo pipefail

K() { docker exec "$1" /opt/kafka/bin/"${@:2}"; }
BS1=kafka-1:9092
BS3=kafka-3:9092
WAIT_ISR=35

hr() { echo; echo "========================================================="; echo " $*"; echo "========================================================="; }

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_place --partitions 6 --replication-factor 3
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_isr1 --partitions 1 --replication-factor 3 --config min.insync.replicas=1
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_isr2 --partitions 1 --replication-factor 3 --config min.insync.replicas=2


# ══════════════════════════════════════════════════════════════════════
hr "정답 1 — preferred leader 와 어긋난 파티션 찾기"
# ══════════════════════════════════════════════════════════════════════
docker compose restart kafka-2 >/dev/null
sleep 25

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_place \
  | awk '/Partition:/ {
      leader=""; repl="";
      for (i=1; i<=NF; i++) {
        if ($i == "Leader:")   leader = $(i+1);
        if ($i == "Replicas:") repl   = $(i+1);
      }
      split(repl, a, ",");
      if (leader != a[1])
        printf "불일치  Partition %s : Leader=%s  preferred=%s  (Replicas=%s)\n", $4, leader, a[1], repl;
    }'

# 해설:
#   Replicas 는 "순서 있는 목록" 이고 그 첫 번째가 preferred leader 입니다.
#   따라서 판정 기준은 딱 하나 — Leader == split(Replicas, ",")[1] 인가.
#
#   중요한 것은 이 상태가 --under-replicated-partitions 에는 "안 걸린다"는 점입니다.
#   ISR 은 3개로 멀쩡하기 때문입니다. 즉 클러스터 건강 지표는 전부 초록인데
#   리더만 두 브로커에 쏠려 있는 상태가 됩니다.
#   롤링 재시작 직후의 전형적인 모습이며, 트래픽이 1.5배가 되는 날
#   그 두 대만 먼저 무너집니다. 며칠 전 재시작이 원인이라는 걸 알아내기가 지독히 어렵습니다.
#
#   운영에서는 이 판정을 JMX 의
#     kafka.controller:type=KafkaController,name=PreferredReplicaImbalanceCount
#   로 대신할 수 있습니다. 이 값이 0 이 아니면 리더가 어긋나 있다는 뜻입니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 2 — ISR 축소 시간 실측"
# ══════════════════════════════════════════════════════════════════════
docker compose stop kafka-3 >/dev/null
START=$(date +%s)

while : ; do
  ISR=$(K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_isr2 \
        | awk '/Partition:/ { for (i=1;i<=NF;i++) if ($i=="Isr:") print $(i+1) }')
  CNT=$(echo "$ISR" | tr ',' '\n' | grep -c .)
  NOW=$(date +%s)
  if [ "$CNT" -lt 3 ]; then
    echo "ISR 축소 감지: ${ISR} (원소 ${CNT}개) — $((NOW - START))초 걸렸습니다"
    break
  fi
  if [ $((NOW - START)) -gt 90 ]; then
    echo "90초를 넘겼습니다. replica.lag.time.max.ms 설정을 확인하세요."
    break
  fi
  sleep 1
done

# 해설:
#   실측값은 보통 31초 근처입니다.
#   replica.lag.time.max.ms=30000 (30초) 에, KRaft 컨트롤러가
#   AlterPartition 요청을 처리하고 메타데이터가 반영되는 1초 남짓이 더해집니다.
#
#   ★ 이 30초를 "장애 감지 시간" 으로 오해하면 안 됩니다.
#     - ISR 축소  = 30초  (시간 기준 판정이므로)
#     - 리더 선출 = 즉시   (수백 ms. 컨트롤러가 브로커 세션 종료를 감지하는 순간)
#
#   즉 "리더가 죽었을 때" 서비스 중단은 수백 ms 이고,
#   "팔로워가 죽었을 때" 는 애초에 중단이 없습니다.
#   30초는 ISR 목록이라는 장부가 정리되는 데 걸리는 시간일 뿐입니다.
#
#   0.9 이전에는 replica.lag.max.messages (건수 기준) 도 있었는데 제거됐습니다.
#   트래픽이 급증하면 멀쩡한 팔로워가 순식간에 기준을 넘겨 ISR 에서 우수수 빠졌기 때문입니다.
#   트래픽 스파이크가 곧 ISR 붕괴가 되는 구조여서, 지금은 시간 기준 하나만 남았습니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 3 — min.insync.replicas=1 + acks=all → 조용히 성공"
# ══════════════════════════════════════════════════════════════════════
docker compose stop kafka-2 >/dev/null
sleep "$WAIT_ISR"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_isr1

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08ex_isr1 --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF'
C005:{"order_id":"O-1105","customer_id":"C005","amount":18000,"status":"CREATED"}
EOF
echo "(에러 없음 = 성공)"
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08ex_isr1 --time -1

# 해설:
#   성공합니다. 그리고 이것이 이 스텝의 함정 A 입니다.
#
#   acks=all 의 정확한 뜻은 "ISR 의 모든 리플리카가 받았을 때 응답한다" 입니다.
#   ISR 이 몇 개인지는 말하지 않습니다. ISR 이 1개면 "1개 전부가 받았다" 도 all 입니다.
#
#   지금 이 메시지의 복사본은 이 세상에 kafka-1 의 디스크 한 곳뿐입니다.
#   그 디스크가 지금 고장 나면 주문이 사라지고, 프로듀서 로그에는 "성공" 만 남습니다.
#   장애 중이라 그 1대가 죽을 확률이 평소보다 높은 시점이라는 게 특히 나쁩니다.
#
#   min.insync.replicas 는 "acks=all 이 최소 몇 벌을 요구할 것인가" 를 정하는 값이고,
#   설정하지 않으면 그 요구가 사실상 없는 것과 같습니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 4-a — min.insync.replicas=2 → NotEnoughReplicasException"
# ══════════════════════════════════════════════════════════════════════
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08ex_isr2 --producer-property acks=all \
  --property parse.key=true --property key.separator=: <<'EOF' || true
C005:{"order_id":"O-1105","customer_id":"C005","amount":18000,"status":"CREATED"}
EOF

# 해설:
#   org.apache.kafka.common.errors.NotEnoughReplicasException:
#     The size of the current ISR Set(1) is insufficient to satisfy
#     the min.isr requirement of 2 for partition s08ex_isr2-0
#
#   ISR Set(1) = 지금 ISR 크기, min.isr requirement of 2 = 요구치.
#   이 예외가 나는 최소 조건은 3종 세트입니다:
#     ① acks=all                    (없으면 브로커가 min.isr 을 검사조차 안 함)
#     ② min.insync.replicas >= 2    (요구치)
#     ③ 실제 ISR 크기 < 요구치       (브로커를 죽여서 만든 상태)
#
#   ★ 에러가 나는 게 좋은 것입니다.
#     프로듀서는 실패를 알았습니다. 재시도하거나, DLQ 로 보내거나,
#     사용자에게 "잠시 후 다시" 를 띄울 수 있습니다. 선택지가 생겼습니다.
#     정답 3 에서는 선택지 자체가 없었습니다. 잘못됐다는 사실을 아무도 몰랐으니까요.
#
#   NotEnoughReplicasException 은 retriable 예외라, 자바 클라이언트는
#   delivery.timeout.ms 안에서 자동 재시도합니다. 브로커가 그 안에 복구되면
#   애플리케이션은 아무 일도 없었던 것처럼 넘어갑니다. 이것이 올바른 동작입니다.


# ══════════════════════════════════════════════════════════════════════
hr "정답 4-b — 같은 토픽에 acks=1 → 에러가 사라진다"
# ══════════════════════════════════════════════════════════════════════
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS1" --topic s08ex_isr2 --producer-property acks=1 \
  --property parse.key=true --property key.separator=: <<'EOF'
C005:{"order_id":"O-1106","customer_id":"C005","amount":9000,"status":"CREATED"}
EOF
echo "(에러 없음 — min.insync.replicas=2 인데도 성공했습니다)"

# 해설:
#   min.insync.replicas=2 인 똑같은 토픽인데 이번엔 성공합니다.
#
#   ★ min.insync.replicas 는 acks=all 일 때만 검사됩니다.
#     acks=1 이면 리더가 자기 로그에 쓰는 순간 응답합니다.
#     ISR 이 몇 개든 브로커는 신경 쓰지 않습니다.
#
#   그래서 "min.insync.replicas=2 로 걸어 뒀으니 안전하다" 는 말은 절반만 맞습니다.
#   프로듀서 쪽이 acks=all 이 아니면 그 설정은 장식입니다.
#   ★ 둘은 반드시 세트로 설정해야 합니다.
#
#   실무에서 이 조합이 어긋나는 흔한 경로:
#     - 토픽은 플랫폼 팀이 min.insync.replicas=2 로 만들고
#     - 프로듀서는 애플리케이션 팀이 기본값(3.0 부터 acks=all 이 기본이지만
#       그 이전 버전 클라이언트나 명시적으로 acks=1 을 준 설정)으로 쓰고
#     - 아무도 대조해 보지 않습니다.

docker compose start kafka-2 kafka-3 >/dev/null
sleep 25


# ══════════════════════════════════════════════════════════════════════
hr "정답 5 — under-replicated 목록에서 문제 브로커 판정"
# ══════════════════════════════════════════════════════════════════════
docker compose stop kafka-3 >/dev/null
sleep "$WAIT_ISR"

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions \
  | awk '{
      repl=""; isr="";
      for (i=1;i<=NF;i++) {
        if ($i=="Replicas:") repl = $(i+1);
        if ($i=="Isr:")      isr  = $(i+1);
      }
      n = split(repl, r, ",");
      for (j=1; j<=n; j++) {
        if (index("," isr ",", "," r[j] ",") == 0) print r[j];
      }
    }' | sort | uniq -c | sort -rn

# 해설:
#   출력 예:
#       7 3
#   → 7개 파티션 전부에서 브로커 3 이 빠져 있습니다. 범인은 kafka-3 입니다.
#
#   판정 기준은 "Replicas 에는 있는데 Isr 에는 없는" 브로커 ID 입니다.
#   빈도가 압도적으로 높은 ID 하나가 나오면 그 브로커 문제이고,
#   여러 ID 가 고르게 섞여 나오면 브로커가 아니라 특정 토픽/파티션 문제
#   (예: max.message.bytes 를 올렸는데 replica.fetch.max.bytes 를 안 올린 경우)
#   를 의심해야 합니다.
#
#   운영에서는 이 판정 전에 먼저 볼 지표가 있습니다:
#     kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions   → 정상 0
#     kafka.server:type=ReplicaManager,name=UnderMinIsrPartitionCount   → 정상 0
#   두 번째가 0 이 아니면 이미 acks=all 쓰기가 거부되고 있다는 뜻이라 더 급합니다.

docker compose start kafka-3 >/dev/null
sleep 25


# ══════════════════════════════════════════════════════════════════════
hr "정답 6 — 롤링 재시작 후 리더 쏠림 해소"
# ══════════════════════════════════════════════════════════════════════
docker compose restart kafka-2 >/dev/null
sleep 20
docker compose restart kafka-3 >/dev/null
sleep 20

echo "-- BEFORE: 브로커별 리더 개수"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_place \
  | grep -o 'Leader: [0-9]*' | sort | uniq -c

echo
echo "-- preferred leader election"
K kafka-1 kafka-leader-election.sh --bootstrap-server "$BS1" \
  --election-type preferred --all-topic-partitions
sleep 3

echo
echo "-- AFTER: 브로커별 리더 개수"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_place \
  | grep -o 'Leader: [0-9]*' | sort | uniq -c

# 해설:
#   BEFORE 는 보통 이렇게 나옵니다.
#       4 Leader: 1
#       2 Leader: 3
#   kafka-2 는 리더가 0개입니다. 재시작 순서(2 → 3)를 뒤집어 생각하면 당연합니다.
#   kafka-2 가 내려갈 때 그 리더가 1과 3으로 흩어졌고,
#   kafka-3 이 내려갈 때 그 리더가 1로 몰렸습니다. 되돌려 주는 사람은 없습니다.
#
#   AFTER 는 2,2,2 입니다.
#       2 Leader: 1
#       2 Leader: 2
#       2 Leader: 3
#
#   ★ "auto.leader.rebalance.enable 이 기본 true 인데 기다리면 되지 않나?"
#     안 됩니다. 정확히는 "너무 늦습니다".
#       leader.imbalance.check.interval.seconds = 300  (5분)
#       leader.imbalance.per.broker.percentage  = 10   (불균형 10% 넘어야 발동)
#     즉 최대 5분을 기다려야 하고, 불균형이 임계치 미만이면 아예 발동하지 않습니다.
#     운영에서는 자동 재조정이 예상 못 한 시점에 리더를 옮겨 지연 스파이크를 내는 것을
#     싫어해 이 옵션을 끄고 수동으로 하는 곳도 많습니다.
#
#   ★ 결론: 롤링 재시작 절차의 마지막 단계에
#     kafka-leader-election.sh --election-type preferred --all-topic-partitions
#     를 반드시 넣으세요. Step 14 의 플레이북에 이 절차가 들어갑니다.
#
#   참고로 --election-type 에는 preferred 와 unclean 두 가지가 있습니다.
#   unclean 은 정답 7 에서 다루는 그 unclean 이며, 수동으로 "데이터를 버리고
#   파티션을 살리겠다" 고 선언하는 명령입니다. 절대 습관적으로 쓰지 마세요.


# ══════════════════════════════════════════════════════════════════════
hr "정답 7 — unclean 리더 선출로 유실 25건 재현"
# ══════════════════════════════════════════════════════════════════════
# 7-a
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --topic s08ex_unclean \
  --partitions 1 --replication-factor 2 --replica-assignment 1:2 \
  --config min.insync.replicas=1 \
  --config unclean.leader.election.enable=true

# 7-b
K kafka-1 kafka-producer-perf-test.sh --topic s08ex_unclean \
  --num-records 200 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers="$BS1" acks=all

docker compose stop kafka-2 >/dev/null
sleep "$WAIT_ISR"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_unclean

K kafka-1 kafka-producer-perf-test.sh --topic s08ex_unclean \
  --num-records 25 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers="$BS1" acks=all

BEFORE_LEO=$(K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" \
  --topic s08ex_unclean --time -1 | cut -d: -f3)
echo "BEFORE_LEO = $BEFORE_LEO"

echo "-- 지금 이 225건은 전부 컨슈머에게 보입니다 (= 커밋된 데이터)"
K kafka-1 kafka-console-consumer.sh --bootstrap-server "$BS1" --topic s08ex_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1 || true

# 7-c
docker compose stop kafka-1 >/dev/null
docker compose start kafka-2 >/dev/null
sleep 30

K kafka-3 kafka-topics.sh --bootstrap-server "$BS3" --describe --topic s08ex_unclean
AFTER_LEO=$(K kafka-3 kafka-get-offsets.sh --bootstrap-server "$BS3" \
  --topic s08ex_unclean --time -1 | cut -d: -f3)
echo "AFTER_LEO = $AFTER_LEO"

K kafka-3 kafka-console-consumer.sh --bootstrap-server "$BS3" --topic s08ex_unclean \
  --from-beginning --timeout-ms 5000 2>&1 | tail -1 || true

echo
echo "###############################################################"
echo "#   유실: $((BEFORE_LEO - AFTER_LEO))건   (${BEFORE_LEO} → ${AFTER_LEO})"
echo "###############################################################"

# 7-d
docker logs kafka-3 --since 3m 2>&1 | grep -i 'unclean' || \
  docker logs kafka-2 --since 3m 2>&1 | grep -i 'unclean' || echo '(unclean 로그 못 찾음)'

docker compose start kafka-1 >/dev/null
sleep 30
echo "-- 옛 리더가 돌아오면 자기 로그를 잘라냅니다"
docker logs kafka-1 --since 2m 2>&1 | grep -i 'truncat' || echo '(truncation 로그 없음)'
K kafka-1 kafka-get-offsets.sh --bootstrap-server "$BS1" --topic s08ex_unclean --time -1

# 해설:
#   유실은 정확히 25건입니다. 225 → 200.
#
#   무슨 일이 있었는지 한 줄씩 다시 정리하면:
#     ① 200건까지는 kafka-1, kafka-2 둘 다 갖고 있었습니다.
#     ② kafka-2 가 죽고 30초 뒤 ISR 에서 빠져 ISR={1} 이 됐습니다.
#        ★ ISR 이 리더 하나뿐이므로 HW 가 LEO 를 그대로 따라 올라갑니다.
#        그래서 뒤이어 넣은 25건이 즉시 "커밋된" 데이터가 되어 컨슈머에게 보였습니다.
#     ③ kafka-1 이 죽어 ISR 이 텅 비었습니다.
#     ④ unclean=true 이므로 컨트롤러가 ISR 밖의 kafka-2 를 리더로 세웠습니다.
#        kafka-2 의 로그는 200 에서 끝납니다. 25건은 그 순간 존재하지 않게 됐습니다.
#     ⑤ kafka-1 이 돌아와도 못 되돌립니다. 새 리더의 로그가 진실이므로
#        kafka-1 은 "Truncating to offset 200" 으로 자기 로그를 잘라냅니다.
#
#   ★ 이 사건의 최악인 점은 아무도 에러를 못 본다는 것입니다.
#     프로듀서는 성공 응답을 받았고, 컨슈머는 25건을 정상 처리했고
#     (결제도 했고 메일도 보냈고), 브로커는 살아 있고, 알람도 안 울립니다.
#     그런데 Kafka 에는 그 25건의 기록이 없습니다. 재처리도 대사도 불가능합니다.
#
#   ★ 3.7 의 기본값은 unclean.leader.election.enable=false 입니다. 그대로 두세요.
#     Kafka 는 0.11 까지 true 였고 1.0 부터 false 로 바꿨습니다.
#
#   반대급부를 정직하게 말하면, false 면 ISR 이 빌 때 그 파티션은 완전히 멈춥니다.
#   프로듀서는 TimeoutException, 컨슈머는 무한 대기입니다.
#   "정지가 유실보다 낫다" 는 것이 Kafka 의 기본 판단이고 대부분의 비즈니스에서 옳습니다.
#
#   ★ 그리고 진짜 결론은 이것입니다.
#     unclean 을 끄는 것이 답이 아니라, ISR 이 애초에 비지 않도록
#     RF=3 + min.insync.replicas=2 로 설계하는 것이 답입니다.
#     RF=2 로 만들었기 때문에 브로커 두 대가 죽는 순간 ISR 이 빌 수 있었던 것이고,
#     min.insync.replicas=1 이었기 때문에 ②단계에서 25건이 커밋될 수 있었던 것입니다.
#     이 문제의 토픽은 두 설정 모두 일부러 나쁘게 준 것입니다.


# ══════════════════════════════════════════════════════════════════════
hr "뒷정리 — 토픽 삭제 + 브로커 3대 검증"
# ══════════════════════════════════════════════════════════════════════
docker compose up -d
sleep 20

for t in s08ex_place s08ex_isr1 s08ex_isr2 s08ex_unclean; do
  K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --delete --topic "$t" 2>/dev/null || true
done
sleep 3
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --list | grep '^s08ex_' \
  && { echo 'FAIL: s08ex_ 토픽이 남아 있습니다'; exit 1; } || echo 'OK: s08ex_ 토픽 없음'

docker compose ps --format 'table {{.Name}}\t{{.Status}}'
ALIVE=$(K kafka-1 kafka-broker-api-versions.sh --bootstrap-server "$BS1" | grep -cE '^kafka-[0-9]')
echo "살아 있는 브로커: ${ALIVE} (3 이어야 정상)"
[ "$ALIVE" -eq 3 ] || { echo 'FAIL: 브로커를 복구하세요'; exit 1; }

echo "-- under-replicated 파티션 (빈 출력이 정상)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions
echo "완료. → Step 09"
