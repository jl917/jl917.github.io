#!/usr/bin/env bash
#
# Step 06 — 오프셋 관리 : 본문 예제 모음
#
# 실행법:
#   bash practice.sh              # 통째로 실행
#   bash -x practice.sh           # 한 줄씩 확인하며 실행 (권장)
#
# 사전 조건:
#   docker compose up -d  로 kafka-1/2/3 이 모두 (healthy) 여야 합니다.
#
# 주의:
#   - 이 스크립트는 s06_orders 토픽과 s06-* 컨슈머 그룹만 건드립니다.
#   - 6-4, 6-5 구간은 Practice.java 가 같은 디렉터리에 있어야 합니다.
#   - [터미널 B] 주석이 붙은 구간은 별도 창에서 실행해야 관찰됩니다.

set -euo pipefail

BS=kafka-1:9092
K()  { docker exec    kafka-1 /opt/kafka/bin/"$@"; }   # 일반 실행
KI() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }   # stdin 을 넘겨야 할 때 (프로듀서)

TOPIC=s06_orders

hr() { echo; echo "=============== $* ==============="; echo; }

# ---------------------------------------------------------------------------
# [6-0] 실습 준비 — 토픽 생성과 메시지 100건
# ---------------------------------------------------------------------------
hr "[6-0] 토픽 생성"

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$TOPIC" 2>/dev/null || true
sleep 2

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic "$TOPIC" --partitions 1 --replication-factor 3

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic "$TOPIC"

hr "[6-0] 메시지 100건 발행 (O-1001 ~ O-1100, 키는 C001~C010 순환)"

# 이 100 이라는 숫자가 이후 모든 절의 기준값입니다. 바꾸지 마세요.
for i in $(seq 1001 1100); do
  c=$(printf "C%03d" $(( (i % 10) + 1 )))
  echo "${c}:{\"order_id\":\"O-${i}\",\"customer_id\":\"${c}\",\"amount\":39000,\"status\":\"CREATED\"}"
done | KI kafka-console-producer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
        --property parse.key=true --property key.separator=:

# 총 건수 확인 — 결과는 s06_orders:0:100
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic "$TOPIC"

# ---------------------------------------------------------------------------
# [6-1] 오프셋 4종 — 60건만 읽고 커밋한 뒤 --describe
# ---------------------------------------------------------------------------
hr "[6-1] s06-basic 그룹으로 60건만 읽습니다"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06-basic --from-beginning --max-messages 60 | tail -1

# CURRENT-OFFSET 60 / LOG-END-OFFSET 100 / LAG 40 이 나와야 합니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-basic

# ---------------------------------------------------------------------------
# [6-2] auto.offset.reset — latest / earliest / none
#       ※ 반드시 서로 다른 새 그룹으로 붙어야 차이가 보입니다.
#          기존 그룹은 커밋된 오프셋이 있어 이 설정이 무시됩니다.
# ---------------------------------------------------------------------------
hr "[6-2-1] latest (기본값) — 0건을 읽습니다"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06-latest --timeout-ms 5000 || true

# LAG 0 인데 처리 건수는 0 입니다. 이것이 함정 A 입니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-latest

hr "[6-2-2] earliest — 100건을 읽습니다"

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06-earliest --consumer-property auto.offset.reset=earliest \
  --timeout-ms 5000 2>&1 | tail -2 || true

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-earliest

hr "[6-2-3] none — NoOffsetForPartitionException 으로 죽습니다"

# 의도적으로 실패하는 명령이므로 || true 로 감쌉니다. set -e 가 걸려 있습니다.
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "$TOPIC" \
  --group s06-none --consumer-property auto.offset.reset=none \
  --timeout-ms 5000 2>&1 | grep -E 'NoOffsetForPartition|Processed' || true

# ---------------------------------------------------------------------------
# [6-3] __consumer_offsets 직접 조회
# ---------------------------------------------------------------------------
hr "[6-3] __consumer_offsets 토픽 구조"

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic __consumer_offsets | head -4

hr "[6-3] 그룹이 어느 파티션에 저장되는지 계산 — abs(groupId.hashCode()) % 50"

docker exec kafka-1 sh -c 'cat > /tmp/H.java <<EOF
public class H { public static void main(String[] a) {
  for (String s : a) System.out.println(s + " -> partition " + (Math.abs(s.hashCode()) % 50)); } }
EOF
cd /tmp && java H.java s06-basic s06-latest s06-earliest'

hr "[6-3] 실제 커밋 레코드 조회"

# GroupMetadataMessageFormatter 가 오프셋 커밋과 그룹 메타데이터를 모두 디코딩합니다.
K kafka-console-consumer.sh --bootstrap-server "$BS" \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataMessageFormatter" \
  --from-beginning --timeout-ms 10000 2>/dev/null | grep s06 || true

hr "[6-3] offsets.retention.minutes 기본값 확인 (10080 = 7일)"

K kafka-configs.sh --bootstrap-server "$BS" \
  --describe --entity-type brokers --entity-name 1 --all 2>/dev/null \
  | grep offsets.retention || true

# ---------------------------------------------------------------------------
# [6-4] 핵심 함정 B — enable.auto.commit=true 가 만드는 유실
# ---------------------------------------------------------------------------
hr "[6-4] Practice.java 를 컨테이너로 복사"

docker cp Practice.java kafka-1:/tmp/

hr "[6-4] autocommit-loss — 60건 처리 후 halt(). 커밋은 100까지 됩니다"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss' || true

# LAG 0 인데 실제 처리는 60건뿐입니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-loss

hr "[6-4] autocommit-loss-resume — 남은 것을 읽어 유실 건수를 집계"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-loss-resume'

# ---------------------------------------------------------------------------
# [6-5] 핵심 함정 C — 같은 설정이 만드는 중복
# ---------------------------------------------------------------------------
hr "[6-5] autocommit-dup — 100건 처리, 커밋은 40 에서 멈춘 채 halt()"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-dup' || true

# CURRENT-OFFSET 40 / LAG 60 이 나옵니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-dup

hr "[6-5] autocommit-dup-resume — 60건이 재처리됩니다"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java autocommit-dup-resume'

# ---------------------------------------------------------------------------
# [6-6] 수동 커밋 — commitSync / commitAsync / 특정 오프셋
# ---------------------------------------------------------------------------
hr "[6-6-1] manual-sync — 유실 0, 중복 0"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-sync'

hr "[6-6-2] manual-async — 같은 결과, 더 빠름"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-async'

hr "[6-6-3] manual-per-record — 10건마다 offset+1 을 명시 커밋, metadata 도 함께"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java manual-per-record'

# metadata=processed-by-worker-3 이 보여야 합니다.
K kafka-console-consumer.sh --bootstrap-server "$BS" \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataMessageFormatter" \
  --from-beginning --timeout-ms 10000 2>/dev/null | grep s06-per-record || true

# ---------------------------------------------------------------------------
# [6-7] 오프셋 리셋
# ---------------------------------------------------------------------------
hr "[6-7] --dry-run (기본) — 아직 아무것도 바뀌지 않습니다"

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --to-earliest --dry-run

hr "[6-7] --execute — 실제로 적용"

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --to-earliest --execute

# CURRENT-OFFSET 0 / LAG 100 으로 되감겼는지 확인합니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-basic

hr "[6-7] 다양한 리셋 방식 (전부 dry-run 으로만)"

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --to-latest --dry-run

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --to-offset 30 --dry-run

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --shift-by -20 --dry-run

K kafka-consumer-groups.sh --bootstrap-server "$BS" \
  --group s06-basic --topic "$TOPIC" --reset-offsets --by-duration PT1H --dry-run

hr "[6-7] 그룹 상태 확인 — Empty 여야 리셋할 수 있습니다"

K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-basic --state

# ---------------------------------------------------------------------------
# [6-7] 활성 그룹 리셋 에러 재현 — 터미널 2개가 필요합니다
# ---------------------------------------------------------------------------
# [터미널 B] 아래를 별도 창에서 실행해 그룹을 Stable 로 만들어 두십시오.
#
#   docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
#     --bootstrap-server kafka-1:9092 --topic s06_orders --group s06-basic
#
# [터미널 A] 그 상태에서 아래를 실행하면 다음 에러가 납니다.
#
#   Error: Assignments can only be reset if the group 's06-basic' is inactive,
#          but the current state is Stable.
#
# K kafka-consumer-groups.sh --bootstrap-server "$BS" \
#   --group s06-basic --topic "$TOPIC" --reset-offsets --to-earliest --execute

# ---------------------------------------------------------------------------
# [6-8] seek / seekToBeginning / seekToEnd
# ---------------------------------------------------------------------------
hr "[6-8] seek — 애플리케이션 안에서 위치를 바꿉니다 (커밋하지 않음)"

docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java seek'

# seek 은 커밋하지 않으므로 --describe 는 변하지 않습니다.
K kafka-consumer-groups.sh --bootstrap-server "$BS" --describe --group s06-seek || true

# ---------------------------------------------------------------------------
# [6-9] 정리
# ---------------------------------------------------------------------------
hr "[6-9] 정리 — 그룹과 토픽 삭제"

for g in s06-basic s06-latest s06-earliest s06-none s06-loss s06-dup \
         s06-manual s06-async s06-per-record s06-seek; do
  K kafka-consumer-groups.sh --bootstrap-server "$BS" --delete --group "$g" 2>/dev/null || true
done

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$TOPIC" 2>/dev/null || true

docker exec kafka-1 rm -f /tmp/Practice.java /tmp/H.java /tmp/s06-count.txt 2>/dev/null || true

hr "[6-9] 확인"

K kafka-consumer-groups.sh --bootstrap-server "$BS" --list | grep s06 || echo "남은 s06 그룹 없음"
K kafka-topics.sh --bootstrap-server "$BS" --list | grep s06 || echo "남은 s06 토픽 없음"

echo
echo "Step 06 실습 완료."
