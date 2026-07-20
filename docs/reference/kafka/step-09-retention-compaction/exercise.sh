#!/usr/bin/env bash
#
# Step 09 — 보존과 로그 압축 : 연습문제 (7문제)
#
# 실행법:
#   cd kafka/docker && bash ../step-09-retention-compaction/exercise.sh
#   각 문제의 "# 여기에 작성:" 아래를 직접 채우고 다시 실행하세요.
#
# 브로커를 죽이지 않으므로 안전하지만 sleep 이 많습니다.
# 파일 끝의 정리 블록은 꼭 실행하세요 (압축 토픽을 남기면 클리너가 계속 돕니다).
#
set -euo pipefail

K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
BS=kafka-1:9092
DATA=/var/lib/kafka/data

hr() { echo; echo "---------------------------------------------------------"; echo " $*"; echo "---------------------------------------------------------"; }


# ══════════════════════════════════════════════════════════════════════
hr "문제 1 — retention.ms 만 걸면 왜 안 지워지는가"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_ret --partitions 1 --replication-factor 3 --config retention.ms=5000

K kafka-producer-perf-test.sh --topic s09ex_ret \
  --num-records 300 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all

echo "-- BEFORE"
docker exec kafka-1 ls -la "$DATA/s09ex_ret-0"

# Q1-a. 90초를 기다린 뒤 같은 ls -la 를 다시 찍고, earliest 오프셋도 확인하세요.
#       파일이 지워졌습니까?
#
# 여기에 작성:


# Q1-b. 지워지지 않았다면 그 이유를 한 줄로 설명하는 echo 를 작성하세요.
#       힌트: 세그먼트가 몇 개입니까? 그 세그먼트의 상태는?
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 2 — 실제로 지워지게 만드는 최소한의 추가 설정"
# ══════════════════════════════════════════════════════════════════════
# Q2-a. s09ex_ret 이 실제로 삭제되도록 설정을 하나만 추가하세요.
#       (kafka-configs.sh --alter --add-config ...)
#
# 여기에 작성:


# Q2-b. 설정을 바꾼 직후 ls -la 를 찍어 보세요. 변화가 있습니까?
#       없다면 무엇이 더 필요합니까? 그것을 실행하세요.
#
# 여기에 작성:


# Q2-c. 30초 대기 후 ls -la 와 earliest 오프셋을 확인하고,
#       브로커 로그에서 롤링/삭제 메시지를 grep 하세요.
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 3 — retention.bytes 로 클러스터 디스크 사용량 계산"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_bytes --partitions 12 --replication-factor 3 \
  --config retention.bytes=536870912          # 512 MiB

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s09ex_bytes | head -1

# Q3. 이 토픽이 클러스터 전체에서 최대 몇 GiB 를 쓸 수 있습니까?
#     계산 과정을 echo 로 출력하세요.
#     힌트: retention.bytes 의 단위는 무엇입니까?
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 4 — 압축으로 오프셋에 구멍이 생기는 것 확인"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_compact --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000 \
  --config min.compaction.lag.ms=0

# Q4-a. 키 5개(K-1 ~ K-5)로 총 20건을 쓰세요.
#       각 키가 4번씩 나오도록, 값은 {"k":"K-N","v":M} 형태로 하세요.
#       (--property parse.key=true --property key.separator=: 를 씁니다)
#
# 여기에 작성:


# Q4-b. 압축 전 레코드 목록을 kafka-dump-log.sh 로 출력하세요.
#       세그먼트 파일 이름은 "첫 오프셋을 20자리로 채운 것" 입니다.
#
# 여기에 작성:


# Q4-c. 트리거 메시지 1건으로 롤링을 유도하고 40초 기다린 뒤,
#       압축 후 레코드 목록을 다시 출력하세요.
#       몇 건이 남았고 오프셋은 몇 번부터입니까?
#
# 여기에 작성:


# Q4-d. 클리너 로그에서 "size reduction" 이 포함된 줄을 찾아 출력하세요.
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 5 — tombstone(valueSize: -1) 과 빈 문자열(valueSize: 0) 구별"
# ══════════════════════════════════════════════════════════════════════
# Q5-a. s09ex_compact 에 다음 두 건을 쓰세요.
#         - K-1 에 대한 진짜 tombstone (값이 null)
#         - K-2 에 대한 빈 문자열 값
#       한 번의 console-producer 호출로 둘 다 보내려면
#       --property null.marker=NULL 을 쓰고, 빈 문자열은 그냥 비워 둡니다.
#
# 여기에 작성:


# Q5-b. 롤링을 유도한 뒤 dump-log 로 두 레코드를 확인하세요.
#       valueSize: -1 과 valueSize: 0 이 나란히 보여야 정답입니다.
#
# 여기에 작성:


# Q5-c. 압축 후 컨슈머로 읽었을 때 K-1 과 K-2 중 무엇이 남습니까?
#       확인하고, 왜 그런지 echo 로 설명하세요.
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 6 — 압축 토픽에 키 없는 레코드가 들어가면"
# ══════════════════════════════════════════════════════════════════════
K kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists \
  --topic s09ex_nullkey --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000

# Q6-a. 키 있는 레코드 5건을 먼저 넣으세요. (키 A, B 를 섞어서)
#
# 여기에 작성:


# Q6-b. 이번에는 parse.key 를 쓰지 않고(= 키 없이) 3건을 넣으세요.
#
# 여기에 작성:


# Q6-c. 롤링을 유도하고 40초 기다린 뒤, 브로커 로그에서
#       클리너의 경고 메시지를 찾아 출력하세요.
#       힌트: grep -i 'skipping compaction'
#       이 경고는 에러입니까, 경고입니까? 어떤 결과를 낳습니까?
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "문제 7 — 압축 적합성 판정 (명령이 아니라 판정 문제)"
# ══════════════════════════════════════════════════════════════════════
# 설계 기준 4가지:
#   ① 키가 반드시 있어야 한다
#   ② 키 카디널리티가 유한해야 한다
#   ③ 값이 "전체 상태" 여야 한다 (델타 금지)
#   ④ 순서 의존 처리를 하면 안 된다
#
# 아래 네 토픽이 cleanup.policy=compact 에 적합한지 판정하고,
# 부적합하면 어느 조건을 위반하는지 주석으로 채우세요.
#
# (A) user-profile-snapshot
#     키 = user_id, 값 = 사용자 프로필 전체 JSON.
#     "현재 프로필" 을 조회하는 캐시 구축용.
#     판정:  # 여기에 작성:
#
# (B) page-view-log
#     키 = 없음, 값 = {"url":..., "at":...}.
#     시간대별 페이지뷰 집계에 사용.
#     판정:  # 여기에 작성:
#
# (C) account-balance-delta
#     키 = account_id, 값 = {"delta": +10000} 형태의 증감액.
#     컨슈머가 delta 를 누적해 잔액을 계산.
#     판정:  # 여기에 작성:
#
# (D) session-heartbeat
#     키 = session_id, 값 = {"last_seen": ...} 전체 상태.
#     세션은 계속 새로 생기고 만료된다.
#     판정:  # 여기에 작성:
#
# Q7. 위 네 판정을 echo 로 출력하세요.
#
# 여기에 작성:



# ══════════════════════════════════════════════════════════════════════
hr "뒷정리 — s09ex_ 토픽 삭제"
# ══════════════════════════════════════════════════════════════════════
for t in s09ex_ret s09ex_bytes s09ex_compact s09ex_nullkey; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" 2>/dev/null || true
done
sleep 3
K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s09ex_' \
  && { echo 'FAIL: s09ex_ 토픽이 남아 있습니다'; exit 1; } || echo 'OK: s09ex_ 토픽 없음'

docker compose ps --format 'table {{.Name}}\t{{.Status}}'
echo "뒷정리 완료."
