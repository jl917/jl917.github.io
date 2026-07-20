#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 13 — Kafka Streams / solution.sh   (정답 + 해설)
#
# 실행법:
#   cd docs/reference/kafka/step-13-streams
#   bash solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
# ============================================================================

BS=kafka-1:9092
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
KT() { K kafka-topics.sh --bootstrap-server "$BS" "$@"; }
KCG(){ K kafka-consumer-groups.sh --bootstrap-server "$BS" "$@"; }
hr() { echo; echo "============================================================"; echo "  $*"; echo "============================================================"; }

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
# 정답 1 — 어느 연산 뒤에 내부 토픽이 생기는가
# ============================================================================
hr "정답 1"

KT --list | grep -v '^__' | sort > /tmp/sol-before.txt 2>/dev/null || true
docker exec kafka-1 sh -c "true"   # noop

echo "--- ② stateless 앱 ---"
run_app stateless
produce s13_orders <<'EOF'
C001:{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
EOF
sleep 5
echo "s13-stateless-app 로 시작하는 토픽:"
KT --list | grep 's13-stateless-app' || echo "  (없음 — 이것이 정답의 절반입니다)"
stop_apps

echo "--- ③ selectkey 앱 ---"
run_app selectkey
produce s13_orders <<'EOF'
C001:{"order_id":"O-1002","customer_id":"C001","amount":11000}
EOF
sleep 6
echo "s13-selectkey-app 로 시작하는 토픽:"
KT --list | grep 's13-selectkey-app' || true
stop_apps

# 해설:
#   (a) 늘어난 토픽 두 개:
#         s13-selectkey-app-order-counts-repartition
#         s13-selectkey-app-order-counts-changelog
#
#   (b) 이름은 세 조각입니다:
#         s13-selectkey-app  |  order-counts  |  repartition
#         ─────────────────     ────────────     ───────────
#         application.id        상태 저장소 이름   토픽 종류
#                               (Materialized.as())
#
#       repartition — 키가 바뀐 뒤 같은 키를 같은 파티션으로 다시 모으려고 생깁니다.
#                     selectKey/map/groupBy 뒤에 집계나 조인이 오면 생깁니다.
#                     cleanup.policy=delete. Streams 가 소비 직후 스스로 purge 합니다.
#       changelog   — 상태 저장소(RocksDB)의 모든 변경을 Kafka 에 백업합니다.
#                     count/reduce/aggregate/table() 등 상태를 쓰면 생깁니다.
#                     cleanup.policy=compact. 이것이 상태의 "진짜 원본"입니다.
#
#   (c) stateless 앱은 왜 아무것도 안 만듭니까:
#       filter / mapValues / branch / merge / peek 은 전부 "레코드 하나만 보고" 처리합니다.
#       - 상태를 안 쓰므로 changelog 가 필요 없고
#       - 키를 안 바꾸므로(mapValues 를 썼습니다) 재분배가 필요 없어 repartition 도 없습니다
#       그래서 서브토폴로지도 1개이고, 데이터가 Kafka 를 왕복하지 않습니다.
#
#       ★ 만약 stateless 앱에서 mapValues 대신 map 을 썼다면?
#         map 은 "키가 바뀌었을 수 있다"고 표시됩니다. 실제로 키를 안 바꿔도 그렇습니다.
#         그 뒤에 집계가 오면 repartition 이 생깁니다. 처리량이 대략 절반이 됩니다.
#         → 항상 mapValues / flatMapValues / transformValues 를 먼저 고려하세요.


# ============================================================================
# 정답 2 — 토픽 이름으로 소유 앱과 종류 판별하기
# ============================================================================
hr "정답 2"
cat <<'ANSWER'
  1) payment-agg-app-daily-total-changelog
     앱: payment-agg-app / 종류: changelog
     원인: "daily-total" 이라는 이름의 상태 저장소를 쓰는 집계
           (count / reduce / aggregate 중 하나. Materialized.as("daily-total"))

  2) payment-agg-app-daily-total-repartition
     앱: payment-agg-app / 종류: repartition
     원인: 위 집계 앞에 키를 바꾸는 연산이 있음 (selectKey / map / groupBy)

  3) order-join-app-KSTREAM-JOINTHIS-0000000012-store-changelog
     앱: order-join-app / 종류: changelog
     원인: KStream-KStream 조인. 조인은 양쪽 각각에 윈도우 스토어를 만들므로
           JOINTHIS(왼쪽) 와 JOINOTHER(오른쪽) 가 쌍으로 생깁니다.
           이 토픽이 있으면 반드시 ...JOINOTHER-...-store-changelog 도 있습니다.

  4) fraud-app-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
     앱: fraud-app / 종류: changelog
     원인: 집계. 단 상태 저장소에 "이름을 안 줬습니다."

  5) fraud-app-KSTREAM-KEY-SELECT-0000000002-repartition
     앱: fraud-app / 종류: repartition
     원인: selectKey. 역시 이름 없음.
ANSWER

# 해설 (보너스):
#   4)·5) 의 앱에는 Materialized.as("이름") 이 빠져 있습니다.
#   이름을 안 주면 Streams 가 노드 이름을 씁니다:
#       KSTREAM-AGGREGATE-STATE-STORE-0000000003
#                                     ~~~~~~~~~~
#   ★ 이 숫자는 "DSL 호출 순서"입니다.
#
#   왜 위험합니까:
#     코드에 filter 를 하나 추가하면 그 뒤의 모든 노드 번호가 밀립니다.
#       0000000003 → 0000000004
#     토픽 이름이 바뀝니다. 배포하면
#       - 새 이름의 changelog 토픽이 새로 만들어지고
#       - 기존 상태가 담긴 옛 토픽은 아무도 안 읽는 고아가 되고
#       - 집계가 0 부터 다시 시작합니다
#     "필터 한 줄 추가했는데 집계가 리셋됐다"는 사고가 이렇게 납니다.
#     에러는 없습니다. 로그도 안 나옵니다. 숫자만 이상해집니다.
#
#   해결: 상태 저장소에는 예외 없이 이름을 주세요.
#       .count(Materialized.as("order-counts"))
#       .join(..., StreamJoined.as("order-payment-join"))
#       .repartition(Repartitioned.as("by-order-id"))
#   그러면 코드를 아무리 고쳐도 토픽 이름이 안 바뀝니다.


# ============================================================================
# 정답 3 — changelog 의 cleanup.policy
# ============================================================================
hr "정답 3"
run_app count
produce s13_orders <<'EOF'
C001:{"order_id":"O-2001","amount":1000}
C002:{"order_id":"O-2002","amount":2000}
EOF
sleep 6

# (a) 확인 명령
KT --describe --topic s13-count-app-order-counts-changelog
stop_apps

# 기대 출력:
#   Topic: s13-count-app-order-counts-changelog  ... Configs: cleanup.policy=compact,...
#
# 해설:
#   (b) delete 였다면?
#     changelog 는 상태 저장소의 "진짜 원본"입니다. 앱을 재기동하면(컨테이너 재생성,
#     새 인스턴스 투입, 로컬 디스크 유실) 이 토픽을 처음부터 끝까지 읽어 상태를 복원합니다.
#     delete 정책이면 retention.ms(기본 7일)가 지난 레코드가 삭제됩니다.
#     → 복원할 때 "7일 전에 마지막으로 갱신된 키"의 값이 사라져 있습니다.
#     → 그 키의 카운트가 0 부터 다시 시작합니다.
#     ★ 에러 없이, 조용히, 일부 키의 집계만 틀립니다. 발견하기 매우 어렵습니다.
#
#     compact 는 "키별 최신 값은 영원히 보존"이므로 이 문제가 없습니다.
#     C001 → 1, 2, 3 중 3 만 남아도 상태 복원에는 아무 지장이 없습니다.
#     Step 09 의 압축 토픽 설계 기준이 그대로 적용된 사례입니다.
#     Connect 의 connect-configs/offsets/status 도 같은 이유로 compact 입니다.
#
#   (c) repartition 은 왜 delete 여도 됩니까?
#     repartition 토픽은 "잠깐 거쳐 가는 통로"일 뿐입니다. 상태가 아닙니다.
#     Streams 가 소비 직후 스스로 purge(deleteRecords)합니다.
#     repartition.purge.interval.ms(기본 30초)마다 도는 이 로직 덕분에
#     retention.ms=-1(무한)로 설정돼 있어도 데이터가 안 쌓입니다.
#     지워져도 문제없습니다. 원본 입력 토픽에서 다시 만들 수 있으니까요.
#
#     비교:
#       repartition 을 지우면 → Streams 가 다시 만들고 데이터도 재생성됩니다 (무해)
#       changelog 를 지우면   → 상태를 영구히 잃습니다 (치명적)


# ============================================================================
# 정답 4 — 앱을 완전히 초기화하기
# ============================================================================
hr "정답 4 — 준비"
run_app count
produce s13_orders <<'EOF'
C001:{"order_id":"O-3001","amount":1000}
C001:{"order_id":"O-3002","amount":2000}
C001:{"order_id":"O-3003","amount":3000}
C001:{"order_id":"O-3004","amount":4000}
EOF
sleep 6
stop_apps

hr "정답 4 — 초기화 (세 줄)"

# ① 앱 종료 (이미 했습니다)
docker exec kafka-1 pkill -f 'Practice' >/dev/null 2>&1 || true
sleep 5

# ② Kafka 쪽 정리 — 오프셋 리셋 + 내부 토픽 삭제
K kafka-streams-application-reset.sh --bootstrap-server "$BS" \
  --application-id s13-count-app --input-topics s13_orders

# ③ ★ 로컬 상태 디렉터리 삭제 — reset 도구는 이걸 안 지웁니다
docker exec kafka-1 rm -rf /tmp/kafka-streams/s13-count-app

echo "--- 재기동. 카운트가 1 부터 시작해야 합니다. ---"
run_app count
sleep 5
stop_apps

# 해설:
#   ★ ③ 이 이 문제의 핵심입니다.
#
#   kafka-streams-application-reset.sh 는 "Kafka 쪽"만 정리합니다:
#     - 입력 토픽의 컨슈머 그룹 오프셋을 0 으로
#     - 내부 토픽(repartition/changelog) 삭제
#
#   각 인스턴스의 state.dir(기본 /tmp/kafka-streams/<app-id>)에 있는
#   RocksDB 파일은 그대로 남습니다. reset 도구는 그 머신에 접근할 수 없으니까요.
#
#   ③ 을 빼면 이렇게 됩니다:
#     - 오프셋은 0 → 입력을 처음부터 다시 읽습니다
#     - 로컬 RocksDB 에는 옛 카운트(4)가 그대로 있습니다
#     - 옛 상태 위에 새로 읽은 4건이 얹힙니다 → 카운트가 8 이 됩니다
#   ★ 에러는 없습니다. 숫자만 두 배가 됩니다.
#
#   앱이 살아 있는 상태에서 reset 을 돌리면 이렇게 거부됩니다:
#     ERROR: Java class 'kafka.tools.StreamsResetter' failed:
#       java.lang.IllegalStateException: Consumer group 's13-count-app' is still
#       active and has following members: [s13-count-app-8f2c...-StreamThread-1-consumer].
#       Make sure to stop all running application instances before running the reset tool.
#   그래서 ① 이 먼저입니다. --force 로 강제할 수도 있지만 권하지 않습니다.
#
#   코드로 하는 방법:
#     KafkaStreams.cleanUp()  ← 로컬 state.dir 을 지웁니다
#   단 start() 전에만 호출할 수 있습니다. 그리고 무조건 부르면 재기동마다
#   changelog 전체 복원이 일어나 기동이 수십 분씩 걸립니다.
#   운영 앱에는 --reset 같은 기동 플래그를 만들어 두고 그때만 부르는 패턴이 흔합니다.
#
#   그리고 reset 도구는 컨슈머 그룹 자체를 안 지웁니다. 오프셋만 0 으로 되돌립니다.
#   그룹까지 없애려면: kcg --delete --group s13-count-app


# ============================================================================
# 정답 5 — co-partitioning
# ============================================================================
hr "정답 5"
KT --create --topic s13ex_a --partitions 2 --replication-factor 3 --if-not-exists
KT --create --topic s13ex_b --partitions 4 --replication-factor 3 --if-not-exists
KT --create --topic s13_left  --partitions 3 --replication-factor 3 --if-not-exists
KT --create --topic s13_right --partitions 6 --replication-factor 3 --if-not-exists

echo "--- (a)(b) 예외 재현 ---"
docker exec kafka-1 sh -c "cd /tmp && timeout 45 java -cp '/opt/kafka/libs/*' Practice.java copartition" 2>&1 | grep -A3 'TopologyException' | head -6 || true
stop_apps

echo "--- (c) 수정판 ---"
docker exec kafka-1 sh -c "cd /tmp && timeout 45 java -cp '/opt/kafka/libs/*' Practice.java copartition --fix" 2>&1 | head -12 || true
stop_apps

# 해설:
#   (b) 예외 메시지:
#     org.apache.kafka.streams.errors.TopologyException: Invalid topology:
#       Following topics do not have the same number of partitions:
#       [s13_left(3), s13_right(6)]
#
#     ★ 이 예외는 build() 가 아니라 "파티션 할당 시점"에 납니다.
#       즉 streams.start() 이후 첫 리밸런싱에서 터집니다.
#       StreamsPartitionAssignor.assign() 안의 verifyCopartitioning() 이 던집니다.
#       그래서 로컬에서 토폴로지만 짜 보면 아무 문제가 없어 보입니다.
#
#   (c) 고치는 한 줄:
#     .repartition(Repartitioned.<String,String>as("right-fixed").withNumberOfPartitions(3))
#
#     이러면 s13-copart-app-right-fixed-repartition 이라는 3파티션 토픽이 생기고,
#     조인은 s13_left(3) × right-fixed-repartition(3) 으로 이뤄집니다.
#     대가는 Kafka 왕복 한 번이 추가되는 것입니다.
#
#     대안: 토픽 자체의 파티션 수를 맞춥니다.
#       kt --alter --topic s13_left --partitions 6
#     ⚠️ 단 파티션을 늘리면 Step 03 에서 본 대로 "같은 키가 다른 파티션으로" 갑니다.
#       기존 데이터의 파티셔닝이 깨지므로 운영에서는 신중해야 합니다.
#       repartition 쪽이 대개 안전합니다.
#
#   (d) co-partitioning 의 세 조건 중 Streams 가 검증하는 것은 "1개"뿐입니다.
#
#     1. 파티션 수가 같을 것       ← ★ 검증됨. 위 예외로 즉시 알려 줍니다.
#     2. 키의 타입/직렬화가 같을 것 ← 검증 안 됨
#     3. 파티셔너가 같을 것         ← 검증 안 됨
#
#     ★ 2·3 이 더 위험합니다. 왜?
#       1번은 앱이 아예 안 뜹니다. 배포 즉시 알게 됩니다. 즉 "안전한 실패"입니다.
#       2·3 번은 앱이 정상적으로 뜨고, RUNNING 이고, 랙도 0 이고, 예외도 없습니다.
#       그런데 조인 결과가 비어 있거나 일부만 나옵니다.
#
#       예: 왼쪽 토픽은 키가 String "1001", 오른쪽은 Long 1001L.
#           사람 눈에는 같은 키지만 직렬화 바이트가 달라 murmur2 해시가 다르고,
#           따라서 다른 파티션에 들어갑니다. 어느 태스크도 둘을 함께 볼 수 없습니다.
#
#       예: 한쪽 토픽을 커스텀 파티셔너를 쓰는 Java 프로듀서가 채웠다.
#           파티션 수도 같고 키 타입도 같은데 배치 규칙이 달라 조인이 안 됩니다.
#
#     방어책:
#       - 조인 전에 양쪽 다 repartition() 을 태워 Streams 의 파티셔너로 통일합니다.
#       - 참조 데이터가 작으면 GlobalKTable 을 씁니다. 전체를 복제하므로
#         파티셔닝 자체가 무의미해지고 co-partitioning 요구가 사라집니다.
#       - 키 Serde 를 양쪽에 명시적으로 같게 지정합니다(StreamJoined.with(...)).


# ============================================================================
# 정답 6 — 윈도우 집계가 실제보다 작은 원인
# ============================================================================
hr "정답 6"
docker exec kafka-1 sh -c "cd /tmp && timeout 90 java -cp '/opt/kafka/libs/*' Practice.java window --inject-late" 2>&1 | tail -12 || true
stop_apps

echo "--- JMX 로 dropped-records-total 을 직접 읽는 법 (앱이 떠 있어야 합니다) ---"
cat <<'JMX'
  docker exec kafka-1 /opt/kafka/bin/kafka-run-class.sh kafka.tools.JmxTool \
    --object-name 'kafka.streams:type=stream-task-metrics,thread-id=*,task-id=*' \
    --attributes dropped-records-total \
    --jmx-url service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi \
    --one-time true
JMX

# 해설:
#   (a) 99000 은 어디로 갔습니까?
#     버려졌습니다. 윈도우는 [10:22:00 ~ 10:23:00) 이고 grace 가 30초이므로
#     10:23:30 까지만 늦은 레코드를 받습니다. 그 시점에 스트림 시간은 이미
#     10:23:45 였으므로 윈도우가 닫힌 뒤였습니다.
#     ★ 예외도 안 던지고, 기본 로그 레벨에서는 아무것도 안 찍힙니다.
#       결과 토픽의 합계가 그냥 작습니다.
#       "Kafka 집계 결과가 DB 집계와 안 맞는다"의 가장 흔한 원인입니다.
#
#     ⚠️ 스트림 시간은 "벽시계"가 아니라 "지금까지 본 레코드의 최대 타임스탬프"입니다.
#       레코드가 안 들어오면 스트림 시간도 안 흐릅니다. 이것 때문에 저트래픽
#       토픽에서는 오히려 늦은 레코드가 잘 받아들여지기도 합니다.
#
#   (b) 지표 이름:
#     kafka.streams:type=stream-task-metrics,thread-id=<t>,task-id=<task>
#       dropped-records-total   ← 누적 개수
#       dropped-records-rate    ← 초당 비율
#     ★ 이 지표에 알람을 거는 것이 유일한 방어입니다.
#       "dropped-records-total 이 0 보다 크면 즉시 알람" 이 운영 표준입니다.
#       Step 14 의 JMX 절과 이어집니다.
#
#   (c) 고치는 방법:
#     TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30))
#                                                       ~~~~~~~~~~~~~~~~~~~~~~~
#                                                       이 값을 늘립니다
#     예: Duration.ofMinutes(5)
#
#     단 대가가 있습니다:
#       - 최종 결과가 그만큼 늦게 나옵니다 (suppress 를 쓴다면 더 체감됩니다)
#       - 윈도우 상태를 그동안 들고 있어야 해서 메모리/디스크를 더 씁니다
#
#   (d) 값을 정하는 기준:
#     ★ 관측된 최대 지연의 1.5 ~ 2배.
#     지연의 구성 요소:
#       - 프로듀서 재시도 시간 (delivery.timeout.ms, 기본 120초)
#       - 네트워크/브로커 지연
#       - 배치 지연 (linger.ms)
#       - 소스 시스템에서 Kafka 까지의 지연 (Connect 폴링 주기 등)
#     실무 절차: 처음에는 넉넉히(예: 5분) 잡고, dropped-records-total 을
#     며칠간 관찰하며 0 을 유지하는 선에서 줄여 나갑니다.
#
#   ⚠️ 3.0 마이그레이션 사고:
#     TimeWindows.of(size) 는 3.0 에서 deprecated 되었습니다.
#     옛 API 의 기본 grace 는 "24시간"이었습니다.
#     그런데 IDE 가 제안하는 대로 ofSizeWithNoGrace(size) 로 바꾸면
#     ★ grace 가 0 이 됩니다. 24시간 → 0.
#     조금만 늦게 도착해도 전부 버려집니다.
#     반드시 ofSizeAndGrace(size, grace) 를 쓰고 grace 를 명시하세요.


# ============================================================================
# 정리
# ============================================================================
hr "정리"
stop_apps
for app in s13-stateless-app s13-selectkey-app s13-count-app s13-window-app \
           s13-suppress-app s13-join-app s13-copart-app s13-eos-app; do
  K kafka-streams-application-reset.sh --bootstrap-server "$BS" \
     --application-id "$app" --input-topics s13_orders >/dev/null 2>&1 || true
done
for g in $(KCG --list | grep -E '^s13' || true); do KCG --delete --group "$g" >/dev/null 2>&1 || true; done
docker exec kafka-1 rm -rf /tmp/kafka-streams
for t in $(KT --list | grep -E '^s13' || true); do KT --delete --topic "$t" >/dev/null 2>&1 || true; done
docker exec kafka-1 rm -f /tmp/Practice.java
echo "정리 완료"
