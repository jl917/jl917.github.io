#!/usr/bin/env bash
#
# Step 04 — 프로듀서 : 연습문제 7문제
#
# 실행법:
#   bash exercise.sh            # 준비 구간만 돌고 각 문제에서 멈춥니다
#   (권장) 이 파일을 열어 놓고 "# 여기에 작성:" 자리를 채운 뒤 그 부분만 복사해 실행
#
# 정답은 solution.sh 에 있습니다. 먼저 직접 풀어 보십시오.
#
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "-------- $* --------"; }

cleanup_ex() {
  for T in $(K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s04_ex_' || true); do
    K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$T" || true
  done
  echo "s04_ex_ 토픽 정리 완료"
}

# ---------------------------------------------------------------------------
# 준비 — 문제용 토픽
# ---------------------------------------------------------------------------
hr "준비"
cleanup_ex
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_ex_perf \
  --partitions 6 --replication-factor 3 --config segment.bytes=268435456
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_ex_key \
  --partitions 3 --replication-factor 3
K kafka-topics.sh --bootstrap-server "$BS" --create --topic s04_ex_order \
  --partitions 1 --replication-factor 3


# ===========================================================================
# 문제 1. acks 실측
# ===========================================================================
# s04_ex_perf 에 30만 건(레코드 100바이트, 스로틀 없음)을 acks=0 / 1 / all 로
# 각각 보내고, 처리량(records/sec)과 p99 지연을 표로 정리하십시오.
#
# 힌트: kafka-producer-perf-test.sh 의 마지막 요약 줄에 둘 다 있습니다.
hr "문제 1"

# 여기에 작성:
#   for A in 0 1 all; do
#     K kafka-producer-perf-test.sh --topic s04_ex_perf \
#        --num-records ______ --record-size ______ --throughput ______ \
#        --producer-props bootstrap.servers="$BS" acks=______ | tail -1
#   done


# ===========================================================================
# 문제 2. 키 → 파티션 매핑 표
# ===========================================================================
# C001 ~ C010 을 한 건씩 s04_ex_key (파티션 3개) 에 넣고,
# 각 키가 어느 파티션으로 갔는지 표를 만드십시오.
#
# 힌트: --property print.partition=true --property print.key=true
#       --property print.value=false
# 힌트: 파티셔너는 결정적입니다. 몇 번을 다시 돌려도 같은 답이 나와야 합니다.
hr "문제 2"

# 여기에 작성: (프로듀서)
#   for i in $(seq -w 1 10); do echo "C0$i:{\"customer_id\":\"C0$i\"}"; done \
#     | Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic s04_ex_key \
#         --property ______ --property ______

# 여기에 작성: (컨슈머 — 파티션과 키를 함께 출력)
#


# ===========================================================================
# 문제 3. linger.ms 변곡점 찾기
# ===========================================================================
# acks=all, batch.size=65536 고정. linger.ms 를 0 / 5 / 20 / 100 으로 바꿔
# 30만 건씩 측정하고, "처리량이 더 이상 늘지 않는 지점"과
# "p99 지연이 나빠지기 시작하는 지점"을 각각 찾으십시오.
hr "문제 3"

# 여기에 작성:
#   for L in 0 5 20 100; do
#     ...
#   done


# ===========================================================================
# 문제 4. 압축 코덱 고르기
# ===========================================================================
# 레코드 200바이트, 30만 건, acks=all, linger.ms=20, batch.size=65536 고정.
# none / gzip / snappy / lz4 / zstd 를 각각 측정하고
#   (a) 처리량이 가장 높은 코덱
#   (b) 저장 용량이 가장 작은 코덱
#   (c) "네트워크 대역폭이 비싼 환경" 에서 고를 코덱
# 세 가지에 답하십시오.
#
# 힌트: 저장 용량은 docker exec kafka-1 du -sc /var/lib/kafka/data/s04_ex_perf-*
# 힌트: 코덱마다 공정하게 재려면 측정 전에 토픽을 재생성해야 합니다.
hr "문제 4"

# 여기에 작성:
#


# ===========================================================================
# 문제 5. 순서 역전 재현
# ===========================================================================
# Practice.java 의 order-break 시나리오로 s04_ex_order 에 200건을 보내면서
# 다른 창에서 브로커를 흔들어 재시도를 유발하고,
# 저장된 로그에서 "역전 지점이 몇 곳인지" 세십시오.
#
# 힌트: Practice.java 는 토픽 이름을 두 번째 인자로 받습니다.
#       java -cp "/opt/kafka/libs/*" Practice.java order-break s04_ex_order
# 힌트: 역전 세기
#       ... | awk -F'-' '{n=$2+0; if (n<prev) c++; prev=n} END{print c+0 " 곳 역전"}'
hr "문제 5"

# 여기에 작성: (전송)
#

# 여기에 작성: (역전 세기)
#


# ===========================================================================
# 문제 6. 멱등 프로듀서로 순서 지키기
# ===========================================================================
# 문제 5 와 완전히 같은 조건에서 enable.idempotence=true 로만 바꿔
# (order-safe 시나리오) 역전이 0곳임을 확인하십시오.
# 그리고 두 경우의 처리량 차이를 kafka-producer-perf-test.sh 로 측정하십시오.
#
# ⚠️ 반드시 s04_ex_order 를 재생성한 뒤에 실행하십시오.
#    안 그러면 문제 5 의 기록이 섞여 결과를 못 읽습니다.
hr "문제 6"

# 여기에 작성: (토픽 재생성)
#

# 여기에 작성: (order-safe 실행 + 역전 세기)
#

# 여기에 작성: (처리량 비교 — 멱등 off/on)
#


# ===========================================================================
# 문제 7. send() 를 블로킹시켜 TimeoutException 내기
# ===========================================================================
# buffer.memory 를 1MiB 로, max.block.ms 를 2000 으로 줄여
# 프로듀서 버퍼가 차서 send() 가 블로킹하다 예외를 던지게 만드십시오.
#
# 힌트: 레코드를 크게(1000바이트) 하고 스로틀을 풀어야 버퍼가 빨리 찹니다.
# 힌트: 예외가 나는 것이 정답입니다. set -e 때문에 스크립트가 죽지 않도록
#       set +e / set -e 로 감싸십시오.
#
# 나온 예외 메시지를 그대로 적어 보십시오:
#   org.apache.kafka.common.errors.____________: ______________________________
hr "문제 7"

# 여기에 작성:
#


# ===========================================================================
# 정리
# ===========================================================================
hr "정리"
# 중간에 멈췄다면 이 함수만 따로 호출하십시오.
cleanup_ex

K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s04_' \
  || echo "s04_ 토픽 없음 — 정리 완료"
