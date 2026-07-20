#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 13 — Kafka Streams / exercise.sh   (문제 6개)
#
# 실행법:
#   cd docs/reference/kafka/step-13-streams
#   bash exercise.sh
#
# 각 문제의 "# 여기에 작성:" 아래를 직접 채우세요.
# 정답은 solution.sh 에 있습니다. 먼저 풀어 본 뒤에 여세요.
#
# 문제 1·2·3 = 내부 토픽을 관찰·판별하는 문제
# 문제 4·5·6 = 직접 조치하는 문제
# ============================================================================

BS=kafka-1:9092
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
KT() { K kafka-topics.sh --bootstrap-server "$BS" "$@"; }
KCG(){ K kafka-consumer-groups.sh --bootstrap-server "$BS" "$@"; }
hr() { echo; echo "------------------------------------------------------------"; echo "  $*"; echo "------------------------------------------------------------"; }

run_app() {
  docker exec kafka-1 sh -c "cd /tmp && java -cp '/opt/kafka/libs/*' Practice.java $*" &
  sleep 20
}
stop_apps() { docker exec kafka-1 pkill -f 'Practice' >/dev/null 2>&1 || true; sleep 6; }
produce() {
  docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server "$BS" --topic "$1" \
    --property parse.key=true --property key.separator=:
}

docker cp "$SCRIPT_DIR/Practice.java" kafka-1:/tmp/
KT --create --topic s13_orders   --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_payments --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_out      --partitions 3 --replication-factor 3 --if-not-exists


# ============================================================================
# 문제 1. 어느 연산 뒤에 내부 토픽이 생깁니까?
#
#   아래 준비 블록이 ① 아무것도 안 띄운 상태 ② stateless 앱 ③ selectkey 앱
#   세 시점의 토픽 목록을 찍어 줍니다.
#
#   (a) 어느 시점에 토픽이 늘었습니까? 늘어난 토픽 이름을 쓰세요.
#   (b) 그 이름을 세 조각으로 나눠 각각 무엇을 뜻하는지 쓰세요.
#   (c) stateless 앱은 왜 아무 토픽도 안 만듭니까?
# ============================================================================
hr "문제 1 — 준비"
echo "=== ① 시작 시점 ==="
KT --list | grep -v '^__' || true

echo "=== ② stateless 앱 기동 후 ==="
run_app stateless
produce s13_orders <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF
sleep 5
KT --list | grep -v '^__' || true
stop_apps

echo "=== ③ selectkey 앱 기동 후 ==="
run_app selectkey
produce s13_orders <<'EOF'
C001:{"order_id":"O-1002","customer_id":"C001","amount":11000}
EOF
sleep 6
KT --list | grep -v '^__' || true
stop_apps

hr "문제 1 — 답"
# 여기에 작성: (a) 늘어난 토픽 이름


# 여기에 작성: (b) 이름의 세 조각과 각각의 의미


# 여기에 작성: (c) stateless 앱이 토픽을 안 만드는 이유



# ============================================================================
# 문제 2. 토픽 이름만 보고 소유 앱과 종류 맞히기
#
#   운영 클러스터에서 아래 다섯 개의 토픽을 발견했습니다.
#   각각 (소유 앱 / repartition·changelog / 어떤 연산 때문에 생겼는지) 를 쓰세요.
#
#     1) payment-agg-app-daily-total-changelog
#     2) payment-agg-app-daily-total-repartition
#     3) order-join-app-KSTREAM-JOINTHIS-0000000012-store-changelog
#     4) fraud-app-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
#     5) fraud-app-KSTREAM-KEY-SELECT-0000000002-repartition
#
#   (보너스) 4)·5) 같은 이름이 나오는 앱은 코드에 무엇이 빠져 있습니까?
#            그것이 배포할 때 왜 위험합니까?
# ============================================================================
hr "문제 2"
# 여기에 작성: 다섯 개 각각의 소유 앱 / 종류 / 원인 연산


# 여기에 작성: (보너스) 4)·5) 의 앱에 빠진 것과 배포 시 위험



# ============================================================================
# 문제 3. changelog 토픽의 cleanup.policy
#
#   (a) count 앱을 띄우고 changelog 토픽의 cleanup.policy 를 확인하는 명령을 쓰세요.
#   (b) 그 값이 delete 였다면 무슨 일이 생깁니까?
#   (c) repartition 토픽은 왜 delete 여도 괜찮습니까?
# ============================================================================
hr "문제 3 — 준비"
run_app count
produce s13_orders <<'EOF'
C001:{"order_id":"O-2001","amount":1000}
C002:{"order_id":"O-2002","amount":2000}
EOF
sleep 6
stop_apps

hr "문제 3 — 답"
# 여기에 작성: (a) 확인 명령


# 여기에 작성: (b) delete 였다면


# 여기에 작성: (c) repartition 이 delete 여도 되는 이유



# ============================================================================
# 문제 4. 앱을 완전히 초기화하기
#
#   아래 준비 블록이 count 앱으로 4건을 처리해 카운트를 만들어 둡니다.
#   여러분은 이 앱을 "완전히 처음 상태"로 되돌린 뒤 다시 띄워서
#   카운트가 1부터 시작하게 만들어야 합니다.
#
#   ⚠️ kafka-streams-application-reset.sh 만 돌리고 다시 띄우면
#      카운트가 이어집니다. 왜 그렇습니까? 무엇을 더 해야 합니까?
# ============================================================================
hr "문제 4 — 준비"
run_app count
produce s13_orders <<'EOF'
C001:{"order_id":"O-3001","amount":1000}
C001:{"order_id":"O-3002","amount":2000}
C001:{"order_id":"O-3003","amount":3000}
C001:{"order_id":"O-3004","amount":4000}
EOF
sleep 6
echo "지금 C001 의 카운트가 4 이상이어야 합니다."
stop_apps

hr "문제 4 — 답"
# 여기에 작성: 앱을 완전히 초기화하는 명령 (세 줄)


# 여기에 작성: 재기동해서 카운트가 1부터 시작하는지 확인


# 여기에 작성: reset 도구만으로는 왜 부족합니까? (주석)



# ============================================================================
# 문제 5. co-partitioning 위반 재현과 수정
#
#   s13ex_a(2파티션)과 s13ex_b(4파티션)을 만들어 두었습니다.
#   (a) 이 둘을 조인하면 어떤 예외가 납니까? (Practice.java copartition 을 참고)
#   (b) 예외 메시지를 그대로 쓰세요.
#   (c) 어떻게 고칩니까? 코드 한 줄로 답하세요.
#   (d) co-partitioning 의 조건 세 가지 중 Streams 가 검증해 주는 것은 몇 개입니까?
#       검증 안 되는 것이 왜 더 위험합니까?
# ============================================================================
hr "문제 5 — 준비"
KT --create --topic s13ex_a --partitions 2 --replication-factor 3 --if-not-exists
KT --create --topic s13ex_b --partitions 4 --replication-factor 3 --if-not-exists
KT --describe --topic s13ex_a | head -1
KT --describe --topic s13ex_b | head -1

hr "문제 5 — 답"
# 여기에 작성: (a)(b) 예외를 재현하고 메시지 확인
#   힌트: Practice.java 의 copartition 시나리오가 s13_left(3) × s13_right(6) 을 씁니다.
#         같은 원리입니다.


# 여기에 작성: (c) 고치는 코드 한 줄


# 여기에 작성: (d) 세 조건 중 검증되는 것 / 안 되는 것의 위험



# ============================================================================
# 문제 6. 윈도우 집계 결과가 실제보다 작습니다
#
#   아래 준비 블록이 window --inject-late 를 실행합니다.
#   총 투입 금액은 10000 + 20000 + 7000 + 99000 = 136000 인데
#   최종 합계는 37000 으로 나옵니다.
#
#   (a) 없어진 99000 은 어디로 갔습니까?
#   (b) 어느 지표를 보면 이것을 감지할 수 있습니까? 전체 지표 이름을 쓰세요.
#   (c) 어떻게 고칩니까? 코드에서 무엇을 바꿉니까?
#   (d) 그 값을 얼마로 잡아야 합니까? 판단 기준은?
# ============================================================================
hr "문제 6 — 준비"
docker exec kafka-1 sh -c "cd /tmp && timeout 90 java -cp '/opt/kafka/libs/*' Practice.java window --inject-late" 2>&1 | tail -20 || true
stop_apps

hr "문제 6 — 답"
# 여기에 작성: (a) 99000 의 행방


# 여기에 작성: (b) 감지할 지표 이름


# 여기에 작성: (c) 고치는 방법


# 여기에 작성: (d) 값을 정하는 기준



# ============================================================================
# 정리 — 문제를 안 풀고 정리만 돌려도 에러 없이 끝납니다.
# ============================================================================
hr "정리"
stop_apps
for app in s13-stateless-app s13-selectkey-app s13-count-app s13-window-app \
           s13-suppress-app s13-join-app s13-copart-app s13-eos-app s13ex-app; do
  K kafka-streams-application-reset.sh --bootstrap-server "$BS" \
     --application-id "$app" --input-topics s13_orders >/dev/null 2>&1 || true
done
for g in $(KCG --list | grep -E '^s13' || true); do KCG --delete --group "$g" >/dev/null 2>&1 || true; done
docker exec kafka-1 rm -rf /tmp/kafka-streams
for t in $(KT --list | grep -E '^s13' || true); do KT --delete --topic "$t" >/dev/null 2>&1 || true; done
docker exec kafka-1 rm -f /tmp/Practice.java
echo "정리 완료"
