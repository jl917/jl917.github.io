#!/usr/bin/env bash
#
# Step 08 — 복제와 내구성 : 연습문제 (7문제)
#
# 실행법:
#   cd kafka/docker && bash ../step-08-replication/exercise.sh
#   각 문제의 "# 여기에 작성:" 아래를 직접 채우고 다시 실행하세요.
#
# ⚠️ 브로커를 실제로 죽입니다. 중간에 나가더라도 파일 맨 끝의
#    "뒷정리" 블록만은 반드시 실행하세요.
#
set -euo pipefail

K() { docker exec "$1" /opt/kafka/bin/"${@:2}"; }
BS1=kafka-1:9092
BS3=kafka-3:9092
WAIT_ISR=35

hr() { echo; echo "---------------------------------------------------------"; echo " $*"; echo "---------------------------------------------------------"; }

# 문제용 토픽 준비
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_place --partitions 6 --replication-factor 3
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_isr1 --partitions 1 --replication-factor 3 --config min.insync.replicas=1
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --create --if-not-exists \
  --topic s08ex_isr2 --partitions 1 --replication-factor 3 --config min.insync.replicas=2


# ══════════════════════════════════════════════════════════════════════
hr "문제 1 — preferred leader 와 현재 리더가 어긋난 파티션 찾기"
# ══════════════════════════════════════════════════════════════════════
# 아래 명령으로 kafka-2 를 잠깐 재시작해 리더를 일부러 어긋나게 만듭니다.
docker compose restart kafka-2 >/dev/null
sleep 25

K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_place

# Q1. 위 출력에서 "Leader 가 Replicas 목록의 첫 번째와 다른" 파티션만 골라 출력하세요.
#     힌트: awk 로 Leader 값과 Replicas 의 첫 숫자를 비교합니다.
#     (Replicas: 2,3,1 이면 첫 숫자는 2)
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 2 — ISR 축소에 걸리는 시간을 초 단위로 실측"
# ══════════════════════════════════════════════════════════════════════
# Q2. kafka-3 을 정지시킨 뒤, s08ex_isr2 의 Isr 원소 개수가 3 → 2 로
#     바뀌는 순간까지 1초 간격으로 폴링해 "N초 걸렸습니다" 를 출력하세요.
#
#     힌트:
#       START=$(date +%s)
#       while : ; do ... ; sleep 1 ; done
#       Isr 필드는 --describe 출력의 마지막 컬럼입니다.
#     기대값: 30초 근처. 크게 다르면 replica.lag.time.max.ms 가
#             기본값(30000)이 아닌지 확인하세요.
#
docker compose stop kafka-3 >/dev/null
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 3 — min.insync.replicas=1 토픽에 acks=all 로 쓰기"
# ══════════════════════════════════════════════════════════════════════
# 지금 kafka-3 이 죽어 있습니다. kafka-2 도 죽여 ISR 을 1개로 만듭니다.
docker compose stop kafka-2 >/dev/null
sleep "$WAIT_ISR"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_isr1
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --topic s08ex_isr2

# Q3. s08ex_isr1 (min.insync.replicas=1) 에 acks=all 로 메시지 1건을 보내세요.
#     성공합니까, 실패합니까? 왜 그렇습니까?
#     메시지: C005:{"order_id":"O-1105","customer_id":"C005","amount":18000,"status":"CREATED"}
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 4 — NotEnoughReplicasException 을 의도적으로 발생시키기"
# ══════════════════════════════════════════════════════════════════════
# Q4-a. 같은 메시지를 s08ex_isr2 (min.insync.replicas=2) 에 acks=all 로 보내세요.
#       어떤 예외가 납니까? 예외 메시지의 "ISR Set(N)" 과 "min.isr requirement of M"
#       숫자를 확인하세요.
#
# 여기에 작성:


# Q4-b. 같은 토픽(s08ex_isr2)에 이번에는 acks=1 로 보내세요.
#       결과가 왜 달라집니까? 이것이 min.insync.replicas 에 대해 말해 주는 것은?
#
# 여기에 작성:


docker compose start kafka-2 kafka-3 >/dev/null
sleep 25


# ══════════════════════════════════════════════════════════════════════
hr "문제 5 — under-replicated 목록에서 문제 브로커 판정"
# ══════════════════════════════════════════════════════════════════════
docker compose stop kafka-3 >/dev/null
sleep "$WAIT_ISR"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions

# Q5. 위 출력에서 "Replicas 에는 있는데 Isr 에는 없는" 브로커 ID 를 뽑아
#     빈도순으로 세는 한 줄 파이프라인을 작성하세요.
#     모든 줄에서 같은 ID 가 빠져 있으면 그 브로커가 범인입니다.
#
# 여기에 작성:


docker compose start kafka-3 >/dev/null
sleep 25


# ══════════════════════════════════════════════════════════════════════
hr "문제 6 — 롤링 재시작 후 리더 쏠림 해소"
# ══════════════════════════════════════════════════════════════════════
echo "-- 롤링 재시작 흉내"
docker compose restart kafka-2 >/dev/null
sleep 20
docker compose restart kafka-3 >/dev/null
sleep 20

# Q6-a. s08ex_place 의 브로커별 리더 개수를 세어 출력하세요. (BEFORE)
#       힌트: grep -o 'Leader: [0-9]*' | sort | uniq -c
#
# 여기에 작성:


# Q6-b. preferred leader election 을 실행해 리더를 되돌리고,
#       브로커별 리더 개수를 다시 세어 출력하세요. (AFTER — 2,2,2 여야 정상)
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 7 — unclean 리더 선출로 유실 건수를 숫자로 재현"
# ══════════════════════════════════════════════════════════════════════
# practice.sh 의 8-8 과 같은 절차를 200건 + 25건으로 다시 합니다.
# 최종 유실 건수가 정확히 25 여야 합니다.
#
# Q7-a. s08ex_unclean 토픽을 만드세요.
#       조건: 파티션 1, RF 2, kafka-1 과 kafka-2 에만 배치(--replica-assignment),
#             min.insync.replicas=1, unclean.leader.election.enable=true
#
# 여기에 작성:


# Q7-b. 200건을 넣고, kafka-2 를 정지시킨 뒤 ISR 축소를 기다리고,
#       25건을 더 넣고 BEFORE_LEO 를 변수에 담으세요.
#
# 여기에 작성:


# Q7-c. kafka-1 을 정지하고 kafka-2 를 기동해 unclean 선출을 유발한 뒤,
#       AFTER_LEO 를 구하고 "유실: N건" 을 출력하세요.
#       (kafka-1 이 죽어 있으므로 kafka-3 을 경유해야 합니다)
#
# 여기에 작성:


# Q7-d. 컨트롤러 로그에서 unclean 선출 메시지를 찾아 출력하세요.
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "뒷정리 — 문제를 다 못 풀었어도 이 블록은 반드시 실행하세요"
# ══════════════════════════════════════════════════════════════════════
docker compose up -d
sleep 20

for t in s08ex_place s08ex_isr1 s08ex_isr2 s08ex_unclean; do
  K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --delete --topic "$t" 2>/dev/null || true
done

docker compose ps --format 'table {{.Name}}\t{{.Status}}'
ALIVE=$(K kafka-1 kafka-broker-api-versions.sh --bootstrap-server "$BS1" | grep -cE '^kafka-[0-9]')
echo "살아 있는 브로커: ${ALIVE} (3 이어야 정상)"
[ "$ALIVE" -eq 3 ] || { echo 'FAIL: 브로커를 복구하세요'; exit 1; }

echo "-- under-replicated 파티션 (빈 출력이 정상)"
K kafka-1 kafka-topics.sh --bootstrap-server "$BS1" --describe --under-replicated-partitions
echo "뒷정리 완료."
