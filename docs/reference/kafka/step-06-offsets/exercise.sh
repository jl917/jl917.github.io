#!/usr/bin/env bash
#
# Step 06 — 오프셋 관리 : 연습문제 (7문제)
#
# 실행법:
#   bash exercise.sh              # setup 만 돌고 문제는 직접 채워야 합니다
#
# 규칙:
#   - "# 여기에 작성:" 아래를 직접 채우세요.
#   - 본문(index.md)의 숫자를 그대로 옮겨 적으면 틀립니다. 여기 데이터는 50건입니다.
#   - 정답은 solution.sh 에 있습니다. 먼저 풀어 보세요.

set -euo pipefail

BS=kafka-1:9092
K()  { docker exec    kafka-1 /opt/kafka/bin/"$@"; }
KI() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

TOPIC=s06x_orders

hr() { echo; echo "--------------- $* ---------------"; echo; }

setup() {
  hr "setup — s06x_orders 토픽(파티션 1개)과 메시지 50건"

  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$TOPIC" 2>/dev/null || true
  sleep 2
  K kafka-topics.sh --bootstrap-server "$BS" \
    --create --topic "$TOPIC" --partitions 1 --replication-factor 3

  for i in $(seq 2001 2050); do
    c=$(printf "C%03d" $(( (i % 10) + 1 )))
    echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":21000,\"status\":\"CREATED\"}"
  done | KI kafka-console-producer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
          --property parse.key=true --property key.separator=:

  K kafka-get-offsets.sh --bootstrap-server "$BS" --topic "$TOPIC"

  # 문제 2 에서 쓸 해시 계산기
  docker exec kafka-1 sh -c 'cat > /tmp/H.java <<EOF
public class H { public static void main(String[] a) {
  for (String s : a) System.out.println(s + " -> partition " + (Math.abs(s.hashCode()) % 50)); } }
EOF'

  docker cp Practice.java kafka-1:/tmp/ 2>/dev/null || \
    echo "경고: Practice.java 를 찾지 못했습니다. 문제 3·4·5 를 풀 수 없습니다."
}

cleanup() {
  hr "cleanup — s06x 그룹과 토픽 삭제"
  for g in s06x-latest s06x-earliest s06x-a s06x-loss s06x-fix s06x-bug s06x-reset s06x-seek; do
    K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$g" 2>/dev/null || true
  done
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$TOPIC" 2>/dev/null || true
  docker exec kafka-1 rm -f /tmp/H.java /tmp/PracticeBug.java /tmp/s06-count.txt 2>/dev/null || true
}

setup

# ===========================================================================
# 문제 1. auto.offset.reset 의 latest 와 earliest 비교
#
#   새 그룹 s06x-latest 를 auto.offset.reset=latest(기본) 로,
#   새 그룹 s06x-earliest 를 earliest 로 붙여 각각 몇 건을 읽는지 확인하고,
#   두 그룹의 --describe LAG 을 비교하십시오.
#
#   질문: 두 그룹의 LAG 이 같습니까? 처리한 건수는 같습니까?
#         같지 않다면 대시보드에서 LAG 만 보는 운영자는 무엇을 놓칩니까?
# ===========================================================================
hr "문제 1"

# 여기에 작성: s06x-latest 로 5초 동안 읽기


# 여기에 작성: s06x-latest 의 --describe


# 여기에 작성: s06x-earliest 로 earliest 설정해서 읽기


# 여기에 작성: s06x-earliest 의 --describe


# ===========================================================================
# 문제 2. __consumer_offsets 에서 특정 그룹의 커밋만 뽑기
#
#   (a) 그룹 s06x-a 를 만들어 20건만 읽고 커밋하십시오.
#   (b) s06x-a 의 커밋이 __consumer_offsets 의 몇 번 파티션에 저장되는지
#       /tmp/H.java 로 계산하십시오.
#   (c) 그 파티션만 읽어서 s06x-a 의 OffsetAndMetadata 레코드를 출력하십시오.
#       (힌트: --partition 옵션. 50개 파티션을 전부 훑을 필요가 없습니다)
# ===========================================================================
hr "문제 2"

# 여기에 작성 (a): s06x-a 로 20건 읽기


# 여기에 작성 (b): 파티션 번호 계산


# 여기에 작성 (c): 해당 파티션만 조회


# ===========================================================================
# 문제 3. 자동 커밋 유실을 숫자로 증명하기
#
#   Practice.java 의 autocommit-loss 시나리오를 그룹 이름만 s06x-loss 로,
#   토픽을 s06x_orders 로 바꿔 돌리고(환경변수 S06_TOPIC / S06_GROUP 지원),
#   아래 두 숫자를 각각 어디서 얻는지 명령으로 보이십시오.
#
#     A. 애플리케이션이 실제로 처리한 건수
#     B. 브로커에 커밋된 오프셋
#
#   질문: A 와 B 중 --describe 로 알 수 있는 것은 무엇입니까?
#         나머지 하나는 어디서 얻어야 합니까?
# ===========================================================================
hr "문제 3"

# 여기에 작성: Practice.java autocommit-loss 실행 (S06_TOPIC / S06_GROUP 지정)


# 여기에 작성: 커밋된 오프셋 확인


# ===========================================================================
# 문제 4. 자동 커밋을 유지하면서 유실 창 없애기
#
#   enable.auto.commit=true 를 유지한 채,
#   max.poll.records 와 auto.commit.interval.ms 를 조정해
#   "배치 하나의 처리 시간 < auto.commit.interval.ms" 가 되도록 만드십시오.
#   건당 처리 시간은 50ms 로 고정입니다 (Practice.java 의 sleep).
#
#   (a) 어떤 조합을 골랐습니까? 계산 근거를 주석으로 쓰십시오.
#   (b) 그 조합으로 돌려 유실이 0 인지 확인하십시오.
#   (c) 이 해법의 한계는 무엇입니까? (해설에서 다시 다룹니다)
# ===========================================================================
hr "문제 4"

# 여기에 작성 (a): 계산 근거
#   배치당 처리 시간 = max.poll.records × 50ms =
#   auto.commit.interval.ms =
#   따라서 고른 값:

# 여기에 작성 (b): 실행


# ===========================================================================
# 문제 5. 커밋 오프셋에서 "+1" 을 빼먹으면?
#
#   Practice.java 를 /tmp/PracticeBug.java 로 복사한 뒤,
#   manual-per-record 시나리오의 커밋 부분을
#       new OffsetAndMetadata(r.offset() + 1, ...)
#   에서
#       new OffsetAndMetadata(r.offset(), ...)
#   로 고쳐서 그룹 s06x-bug 로 돌리십시오. (클래스명도 PracticeBug 로 바꿔야 합니다)
#
#   질문: --describe 의 LAG 이 얼마입니까? 왜 그 값입니까?
#         파티션이 3개인 토픽이었다면 LAG 이 얼마로 나오겠습니까?
# ===========================================================================
hr "문제 5"

# 여기에 작성: PracticeBug.java 만들기 (sed 로 두 곳을 치환하면 됩니다)


# 여기에 작성: 실행


# 여기에 작성: --describe 로 LAG 확인


# ===========================================================================
# 문제 6. 활성 그룹을 리셋하려고 하면?      [터미널 2개 필요]
#
#   ⚠️ 이 문제는 반드시 창을 두 개 열어야 합니다.
#      한 창에서 순차 실행하면 이미 컨슈머가 종료된 뒤라 에러가 재현되지 않습니다.
#
#   [터미널 B] 컨슈머를 띄워 그룹 s06x-reset 을 Stable 로 만드십시오.
#   [터미널 A] 그 상태에서 --reset-offsets --to-earliest --execute 를 시도해
#             에러 메시지를 확인하십시오.
#
#   그다음, 리셋이 성공하도록 절차를 고치십시오.
#   힌트: 컨슈머를 내린 직후에도 실패할 수 있습니다. --state 를 확인하세요.
# ===========================================================================
hr "문제 6"

# [터미널 B] 여기에 작성: 컨슈머 띄우기


# [터미널 A] 여기에 작성: 리셋 시도 (에러 확인)


# 여기에 작성: 성공하는 절차 (상태가 Empty 가 될 때까지 대기하는 루프 포함)


# ===========================================================================
# 문제 7. --reset-offsets --shift-by 와 seek() 의 차이
#
#   (a) 그룹 s06x-seek 로 50건을 전부 읽어 커밋하십시오. (CURRENT-OFFSET 50)
#   (b) --reset-offsets --shift-by -30 --execute 를 하고 --describe 를 보십시오.
#   (c) 다시 50 으로 되돌린 뒤, Practice.java 의 seek 시나리오로
#       같은 30 만큼 되감고 --describe 를 보십시오.
#
#   질문: (b) 와 (c) 에서 --describe 의 CURRENT-OFFSET 이 어떻게 다릅니까?
#         (c) 의 결과를 (b) 와 같게 만들려면 무엇을 추가해야 합니까?
# ===========================================================================
hr "문제 7"

# 여기에 작성 (a)


# 여기에 작성 (b)


# 여기에 작성 (c)


# ===========================================================================
cleanup

echo
echo "연습문제 종료. solution.sh 로 채점하세요."
