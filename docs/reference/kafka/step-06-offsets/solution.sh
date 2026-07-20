#!/usr/bin/env bash
#
# Step 06 — 오프셋 관리 : 연습문제 정답과 해설
#
# 실행법:
#   bash solution.sh
#
# 이 파일은 exercise.sh 와 같은 setup(50건) 을 쓰며,
# 각 정답 뒤에 "왜 그 답인가" 를 설명하는 # 해설: 블록이 붙어 있습니다.
# 문제를 풀어 본 뒤에 여세요.

set -euo pipefail

BS=kafka-1:9092
K()  { docker exec    kafka-1 /opt/kafka/bin/"$@"; }
KI() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

TOPIC=s06x_orders

hr() { echo; echo "=============== $* ==============="; echo; }

setup() {
  hr "setup — s06x_orders(1 파티션) + 메시지 50건"
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

  docker exec kafka-1 sh -c 'cat > /tmp/H.java <<EOF
public class H { public static void main(String[] a) {
  for (String s : a) System.out.println(s + " -> partition " + (Math.abs(s.hashCode()) % 50)); } }
EOF'
  docker cp Practice.java kafka-1:/tmp/ 2>/dev/null || true
}

cleanup() {
  hr "cleanup"
  for g in s06x-latest s06x-earliest s06x-a s06x-loss s06x-fix s06x-bug s06x-reset s06x-seek; do
    K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$g" 2>/dev/null || true
  done
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$TOPIC" 2>/dev/null || true
  docker exec kafka-1 rm -f /tmp/H.java /tmp/PracticeBug.java /tmp/s06-count.txt 2>/dev/null || true
}

setup

# ===========================================================================
# 정답 1
# ===========================================================================
hr "정답 1 — latest 는 0건, earliest 는 50건. 그런데 LAG 은 둘 다 0"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06x-latest --timeout-ms 5000 2>&1 | grep Processed || true
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-latest

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06x-earliest --consumer-property auto.offset.reset=earliest \
  --timeout-ms 5000 2>&1 | grep Processed || true
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-earliest

# 해설:
#   s06x-latest    → Processed a total of 0 messages   / CURRENT-OFFSET 50, LAG 0
#   s06x-earliest  → Processed a total of 50 messages  / CURRENT-OFFSET 50, LAG 0
#
#   두 그룹의 --describe 출력은 완전히 동일합니다. CURRENT-OFFSET 50, LAG 0.
#   그런데 실제로 처리한 건수는 0 과 50 으로 정반대입니다.
#
#   왜 이렇게 되느냐면, latest 그룹은 "파티션의 끝(50)에서 시작" 했고
#   그 상태 그대로 커밋했기 때문입니다. 읽은 게 없는데 오프셋은 50 입니다.
#
#   즉 LAG 은 "처리했는가" 를 말해 주지 않습니다.
#   LAG 은 오직 "커밋된 오프셋이 로그 끝에 얼마나 가까운가" 만 말합니다.
#   대시보드에서 LAG 만 감시하는 운영자는 이 사고를 절대 발견하지 못합니다.
#
#   발견하려면 애플리케이션 쪽 지표(처리 건수 카운터)가 반드시 있어야 하고,
#   그 값과 브로커의 오프셋 증가분을 대조해야 합니다. 이것이 문제 3 의 주제입니다.

# ===========================================================================
# 정답 2
# ===========================================================================
hr "정답 2 — 그룹 → 파티션 계산 후 그 파티션만 조회"

# (a) 20건 읽고 커밋
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06x-a --from-beginning --max-messages 20 >/dev/null

# (b) 파티션 번호 계산
docker exec kafka-1 sh -c 'cd /tmp && java H.java s06x-a'

# (c) 계산된 파티션만 읽기
P=$(docker exec kafka-1 sh -c 'cd /tmp && java H.java s06x-a' | grep -oE '[0-9]+$')
echo "s06x-a 는 __consumer_offsets 파티션 ${P} 에 저장됩니다."

K kafka-console-consumer.sh --bootstrap-server "$BS" \
  --topic __consumer_offsets --partition "$P" \
  --formatter "kafka.coordinator.group.GroupMetadataMessageFormatter" \
  --from-beginning --timeout-ms 8000 2>/dev/null | grep s06x-a || true

# 해설:
#   출력 예:
#     [s06x-a,s06x_orders,0]::OffsetAndMetadata(offset=20, leaderEpoch=Optional[0],
#       metadata=, commitTimestamp=1710120233117, expireTimestamp=None)
#
#   키가 [그룹, 토픽, 파티션] 이고 값이 OffsetAndMetadata 입니다.
#   offset=20 은 --describe 의 CURRENT-OFFSET 20 과 같은 숫자입니다.
#   CLI 가 특별한 API 를 쓰는 게 아니라, 이 토픽을 읽어서 보여줄 뿐입니다.
#
#   --partition N 을 지정하는 것이 이 문제의 실전 포인트입니다.
#   __consumer_offsets 는 파티션이 50개이고 운영 클러스터에서는 수백만 건이 쌓여 있습니다.
#   --from-beginning 으로 전부 훑으면 수 분이 걸리고 브로커에 부하도 갑니다.
#   abs(groupId.hashCode()) % 50 으로 목표 파티션을 특정하면 한 파티션만 읽으면 됩니다.
#
#   보너스: 그 파티션의 리더 브로커가 곧 이 그룹의 코디네이터입니다.
#     kafka-consumer-groups.sh --describe --group s06x-a --state
#   의 COORDINATOR (ID) 열과 일치하는지 확인해 보십시오.

# ===========================================================================
# 정답 3
# ===========================================================================
hr "정답 3 — 처리 건수와 커밋 오프셋은 출처가 다릅니다"

docker exec -e S06_TOPIC="$TOPIC" -e S06_GROUP=s06x-loss kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss' || true

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-loss

# 해설:
#   A. 애플리케이션이 실제로 처리한 건수
#      → Practice.java 의 표준 출력에서만 얻을 수 있습니다.
#        "처리 30건, offset=29" 같은 로그의 마지막 값, 또는 halt 직전의 집계 출력입니다.
#        브로커는 이 숫자를 모릅니다. 알 방법이 없습니다.
#
#   B. 브로커에 커밋된 오프셋
#      → --describe 의 CURRENT-OFFSET, 또는 __consumer_offsets 직접 조회입니다.
#
#   즉 --describe 로 알 수 있는 것은 B 뿐입니다.
#   A 는 애플리케이션이 스스로 노출하지 않으면 아무도 모릅니다.
#
#   이것이 이 스텝 전체의 결론입니다.
#   유실은 "브로커가 아는 숫자"와 "애플리케이션이 아는 숫자"의 차이로만 드러나며,
#   둘 중 하나만 보고 있으면 영원히 보이지 않습니다.
#
#   실무에서는 컨슈머가 처리 건수를 메트릭으로 내보내고(Micrometer 등),
#   그 증가분과 kafka.consumer:type=consumer-fetch-manager-metrics 의
#   records-consumed-total 을 함께 그래프로 겹쳐 봅니다.
#   두 선이 벌어지는 순간이 유실입니다.

# ===========================================================================
# 정답 4
# ===========================================================================
hr "정답 4 — max.poll.records 를 줄여 배치를 커밋 주기 안에 끝냅니다"

# (a) 계산 근거
#     건당 처리 시간 = 50ms (고정)
#     배치당 처리 시간 = max.poll.records × 50ms
#     이 값이 auto.commit.interval.ms 보다 작아야 합니다.
#
#     max.poll.records = 5      → 배치당 250ms
#     auto.commit.interval.ms = 5000 (기본)
#     250ms << 5000ms 이므로 배치를 처리하는 동안 커밋이 끼어들 여지가 없습니다.
#
#     반대로 본문의 유실 조합은 max.poll.records=10 × 50ms = 500ms 인데
#     auto.commit.interval.ms 를 1000 으로 줄여서 창을 강제로 열었던 것입니다.

# (b) 실행
docker exec -e S06_TOPIC="$TOPIC" -e S06_GROUP=s06x-fix \
            -e S06_MAX_POLL=5 -e S06_COMMIT_INTERVAL=5000 kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss' || true

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-fix

# 해설 (c) — 이 해법의 한계:
#   이건 미봉책입니다. 세 가지 이유입니다.
#
#   1. 처리 시간이 고정이라는 전제가 틀립니다.
#      외부 API 호출이 평소 50ms 여도 상대 서버가 느려지면 5초가 됩니다.
#      그 순간 배치당 처리 시간이 25초가 되어 창이 활짝 열립니다.
#      "평소에는 괜찮다가 장애 때만 유실된다" 는 최악의 형태입니다.
#
#   2. max.poll.records 를 줄이면 처리량이 떨어집니다.
#      poll 왕복 횟수가 배치 크기에 반비례해서 늘어납니다.
#      500 → 5 로 줄이면 네트워크 왕복이 100배가 됩니다.
#
#   3. 계산이 맞는지 아무도 검증해 주지 않습니다.
#      코드 어디에도 "이 두 값의 관계가 중요하다" 는 흔적이 남지 않습니다.
#      다음 사람이 성능 튜닝한다고 max.poll.records 를 500 으로 올리는 순간
#      아무 경고 없이 유실이 시작됩니다.
#
#   근본 해법은 하나뿐입니다: enable.auto.commit=false + 처리 후 명시적 커밋.
#   이때는 "처리했으니 커밋한다" 는 인과관계가 코드에 직접 드러나므로
#   타이밍 계산이 필요 없고, 처리 시간이 얼마가 되든 안전합니다.

# ===========================================================================
# 정답 5
# ===========================================================================
hr "정답 5 — +1 을 빼먹으면 LAG 이 파티션 수만큼 고정됩니다"

docker exec kafka-1 sh -c \
  'cd /tmp && sed -e "s/class Practice/class PracticeBug/" \
                  -e "s/r.offset() + 1/r.offset()/" \
                  Practice.java > PracticeBug.java'

docker exec -e S06_TOPIC="$TOPIC" -e S06_GROUP=s06x-bug kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" PracticeBug.java manual-per-record'

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-bug

# 해설:
#   결과:
#     GROUP      TOPIC        PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
#     s06x-bug   s06x_orders  0          49              50              1
#
#   LAG 이 정확히 1 입니다.
#
#   왜냐하면 커밋된 오프셋의 의미가 "마지막으로 처리한 레코드" 가 아니라
#   "다음에 읽어야 할 위치" 이기 때문입니다.
#   마지막 레코드의 오프셋이 49 이므로 커밋해야 할 값은 50 인데 49 를 커밋했습니다.
#   브로커 입장에서는 "이 그룹은 아직 49번 레코드를 안 읽었다" 로 보입니다.
#
#   그래서 재시작할 때마다 마지막 한 건을 항상 다시 처리합니다.
#   50건 중 1건이라 로그를 훑어봐서는 절대 눈치채지 못합니다.
#
#   파티션이 3개인 토픽이었다면 LAG 이 3 으로 나옵니다.
#   파티션마다 각각 1씩 밀리기 때문입니다. 파티션 12개면 LAG 12 입니다.
#   "LAG 이 항상 파티션 수와 같은 값에서 안 내려간다" 는 이 버그의 진단 신호입니다.
#
#   인자 없는 commitSync() 는 클라이언트가 알아서 +1 을 계산해 주므로
#   이 실수가 나지 않습니다. 오프셋을 직접 지정할 때만 조심하면 됩니다.

# ===========================================================================
# 정답 6
# ===========================================================================
hr "정답 6 — 컨슈머 종료 후 상태가 Empty 로 굳을 때까지 기다립니다"

# 먼저 그룹을 만들어 둡니다
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06x-reset --from-beginning --max-messages 50 >/dev/null

# [터미널 B] 아래를 별도 창에서 실행해 그룹을 Stable 로 만듭니다.
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s06x_orders --group s06x-reset
#
# [터미널 A] 그 상태에서 리셋을 시도하면:
#   Error: Assignments can only be reset if the group 's06x-reset' is inactive,
#          but the current state is Stable.

# 성공하는 절차 — 상태가 Empty 가 될 때까지 대기하는 루프
wait_until_empty() {
  local g="$1" i
  for i in $(seq 1 60); do
    if K kafka-consumer-groups.sh --bootstrap-server "$BS" \
         --describe --group "$g" --state 2>/dev/null | grep -qE '\bEmpty\b'; then
      echo "그룹 ${g} 가 Empty 상태가 되었습니다 (${i}초 경과)"
      return 0
    fi
    sleep 1
  done
  echo "타임아웃: ${g} 가 여전히 활성 상태입니다."
  return 1
}

wait_until_empty s06x-reset
K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06x-reset --topic "$TOPIC" --reset-offsets --to-earliest --execute
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-reset

# 해설:
#   에러 메시지의 "inactive" 는 그룹 상태가 Empty 또는 Dead 라는 뜻입니다.
#   Stable / PreparingRebalance / CompletingRebalance 는 전부 거부됩니다.
#
#   이건 안전장치입니다. 살아 있는 컨슈머가 계속 커밋하는 중에 밖에서 되감으면
#   되감은 값이 즉시 덮어씌워지거나 같은 구간을 두 번 읽는 등
#   예측 불가능한 상태가 되기 때문입니다.
#
#   중요한 것은 "컨슈머 프로세스를 죽였다 = 즉시 Empty" 가 아니라는 점입니다.
#   컨슈머가 close() 를 정상 호출하고 종료하면 LeaveGroup 요청을 보내 바로 빠지지만,
#   kill -9 로 죽으면 브로커는 session.timeout.ms(기본 45000) 동안
#   그 멤버가 살아 있다고 간주합니다. 그래서 종료 직후 리셋은 실패합니다.
#
#   그래서 운영 절차는 반드시 3단계입니다:
#     1) 컨슈머를 전부 내린다 (파드 replicas=0)
#     2) --describe --state 가 Empty 가 될 때까지 폴링한다  ← 위 wait_until_empty
#     3) --reset-offsets --execute
#     4) 컨슈머를 다시 올린다
#
#   이 절차 동안 서비스가 멈춘다는 것이 --reset-offsets 의 실질적 비용입니다.
#   무중단으로 되감아야 한다면 애플리케이션 안에서 seek() 를 써야 합니다 (문제 7).

# ===========================================================================
# 정답 7
# ===========================================================================
hr "정답 7 — --reset-offsets 는 즉시 커밋, seek() 는 커밋하지 않음"

# (a) 50건을 전부 읽어 커밋
K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group s06x-seek 2>/dev/null || true
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06x-seek --from-beginning --max-messages 50 >/dev/null
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-seek

# (b) shift-by -30
wait_until_empty s06x-seek
K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06x-seek --topic "$TOPIC" --reset-offsets --shift-by -30 --execute
echo "--- (b) 이후 ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-seek

# 50 으로 되돌린 뒤 (c)
K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06x-seek --topic "$TOPIC" --reset-offsets --to-latest --execute >/dev/null

# (c) seek 시나리오 (커밋하지 않음)
docker exec -e S06_TOPIC="$TOPIC" -e S06_GROUP=s06x-seek kafka-1 \
  sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java seek'
echo "--- (c) 이후 ---"
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06x-seek

# 해설:
#   (b) --reset-offsets --shift-by -30 --execute
#       → CURRENT-OFFSET 50 → 20. --describe 에 즉시 반영됩니다.
#         이 명령은 __consumer_offsets 에 새 레코드를 직접 씁니다.
#         컨슈머가 없어도 동작하며, 되돌릴 수 없습니다.
#
#   (c) seek(tp, position - 30)
#       → CURRENT-OFFSET 은 50 그대로입니다. 변하지 않습니다.
#         seek() 는 컨슈머 프로세스의 메모리상 position 만 바꿉니다.
#         그 위치에서 읽더라도, 커밋하지 않고 프로세스가 죽으면
#         다음 기동 때는 원래 커밋 지점(50)으로 돌아갑니다.
#
#   (c) 를 (b) 와 같게 만들려면 seek 직후 또는 재처리 후에
#       consumer.commitSync();
#   를 호출해야 합니다. 그때 비로소 __consumer_offsets 가 갱신됩니다.
#
#   실무에서 어느 쪽을 쓰느냐:
#     - 일회성 운영 개입, 전체 재처리     → --reset-offsets (단, 서비스 중단 필요)
#     - 무중단 되감기, 조건부 재처리 로직 → seek() (서비스 안 내려도 됨)
#     - "특정 시각부터" 가 필요하면 seek 쪽은 offsetsForTimes() 로 오프셋을 먼저 구합니다.
#       이때 반환값이 null 일 수 있으니(그 시각 이후 메시지가 없는 경우) 반드시 처리하세요.

# ===========================================================================
cleanup

echo
echo "Step 06 정답 확인 완료."
