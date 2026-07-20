#!/usr/bin/env bash
# =============================================================================
# Step 01 — 연습문제 정답과 해설 (6문제)
#
# 실행법:
#   bash step-01-setup/solution.sh
#
# 각 정답 아래의 "# 해설:" 블록이 "왜 그 답인가"를 설명합니다.
# exercise.sh 를 직접 풀어 본 뒤에 여세요.
# =============================================================================
set -euo pipefail

BS=kafka-1:9092
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
Ki() { docker exec -i kafka-1 /opt/kafka/bin/"$@"; }

hr() { echo; echo "=== $* ==================================================="; }

# =============================================================================
# 정답 1. s01_practice 토픽 생성
# =============================================================================
hr "정답 1 — s01_practice 토픽 생성"

K kafka-topics.sh --bootstrap-server "$BS" \
  --create --topic s01_practice \
  --partitions 4 \
  --replication-factor 2 \
  --config retention.ms=3600000 || true

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s01_practice

# 해설:
#   retention.ms 는 밀리초 단위입니다. 1시간 = 60 * 60 * 1000 = 3600000.
#   자주 쓰는 값을 외워 두면 편합니다.
#       1시간   =     3600000
#       1일     =    86400000
#       7일     =   604800000   ← orders / payments 의 기본
#       30일    =  2592000000   ← dlq
#       무제한  =          -1
#
#   --config 는 몇 번이든 반복할 수 있습니다.
#       --config retention.ms=3600000 --config segment.bytes=1048576 ...
#
#   RF 2 의 의미:
#     Replicas 에 브로커가 2개만 나오고 Isr 도 2개입니다.
#     브로커 1대가 죽으면 Isr 이 1이 되어 데이터는 살아 있지만,
#     min.insync.replicas=2 인 토픽이라면 그 순간 acks=all 쓰기가 전부 실패합니다.
#     "RF 3 + min.insync.replicas=2" 조합이 표준인 이유입니다.
#     브로커 1대 손실을 견디면서도 쓰기가 계속되기 때문입니다 (Step 08).
#
#   참고로 이 토픽은 --config min.insync.replicas 를 주지 않았으므로
#   브로커 기본값 2가 적용됩니다. --describe 의 Configs 에는 나오지 않습니다.
#   Configs 는 "토픽 레벨로 명시된 것"만 보여 주기 때문입니다.

# =============================================================================
# 정답 2. 브로커별 리더 파티션 수
# =============================================================================
hr "정답 2 — 브로커별 리더 파티션 수"

K kafka-topics.sh --bootstrap-server "$BS" --describe \
  | grep -oE 'Leader: [0-9]+' | sort | uniq -c | sort -rn

# 해설:
#   기대 출력 (토픽 4개 = 파티션 3+3+3+1 = 10개 + s01_practice 4개 = 14개):
#       5 Leader: 1
#       5 Leader: 2
#       4 Leader: 3
#   숫자는 파티션 배치에 따라 조금씩 다를 수 있습니다.
#
#   왜 세어 봅니까:
#     모든 읽기·쓰기는 리더가 처리합니다. 팔로워는 복제만 합니다.
#     따라서 리더가 한 브로커에 몰리면 그 브로커만 CPU·네트워크·디스크를 쓰고
#     나머지 둘은 놉니다. 클러스터 전체 처리량이 1/3 로 떨어질 수 있습니다.
#
#   언제 쏠립니까:
#     브로커를 재시작하면 그 브로커가 리더였던 파티션들이 다른 브로커로 넘어가고,
#     되살아나도 리더가 자동으로 돌아오지 않습니다(팔로워로 복귀합니다).
#     롤링 재시작을 3대 다 하고 나면 리더가 마지막에 산 브로커를 피해 몰려 있습니다.
#
#   되돌리는 법 (Step 08, 14):
#       kafka-leader-election.sh --bootstrap-server kafka-1:9092 \
#         --election-type preferred --all-topic-partitions
#     "preferred leader" = --describe 의 Replicas 목록 맨 앞 브로커입니다.
#     브로커 설정 auto.leader.rebalance.enable=true(기본값) 면 주기적으로
#     자동 수행되지만, 즉시 필요하면 위 명령을 칩니다.

# =============================================================================
# 정답 3. --from-beginning 유무 비교
# =============================================================================
hr "정답 3 — --from-beginning 유무 비교"

echo "--- (A) --from-beginning 있음 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --from-beginning --timeout-ms 5000 2>&1 | grep 'Processed a total' || true

echo "--- (B) --from-beginning 없음 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --timeout-ms 5000 2>&1 | grep 'Processed a total' || true

# 해설:
#   기대 출력:
#       (A) Processed a total of 8 messages
#       (B) Processed a total of 0 messages
#
#   두 실행은 서로 다른 컨슈머 그룹을 씁니다. 이것이 이 문제의 핵심입니다.
#   kafka-console-consumer.sh 는 --group 을 안 주면 실행할 때마다
#   console-consumer-XXXXX (XXXXX 는 난수) 라는 새 그룹을 만듭니다.
#   실제로 쌓여 있는지 확인해 보세요:
#       kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --list | grep console-consumer
#
#   새 그룹에는 커밋된 오프셋이 없습니다. 그럴 때 어디서부터 읽을지는
#   auto.offset.reset 이 정하고, 기본값은 latest 입니다.
#   latest = "지금 이 순간 이후에 도착하는 것부터". 이미 있는 8건은 건너뜁니다.
#
#   --from-beginning 은 내부적으로 auto.offset.reset=earliest 를 설정합니다.
#
#   왜 위험한가:
#     증상이 "아무 일도 안 일어남"입니다. 에러도, 경고도, 로그도 없습니다.
#     토픽 이름을 의심하고 방화벽을 의심하다가 마지막에야 이 옵션을 떠올립니다.
#
#   더 큰 함정 (Step 06):
#     같은 --group 을 지정해 두 번째로 실행하면 --from-beginning 을 줘도
#     아무것도 안 읽힙니다. 커밋된 오프셋이 이미 있으면 auto.offset.reset 은
#     아예 참조되지 않기 때문입니다. 그때는 --reset-offsets 를 써야 합니다.

# =============================================================================
# 정답 4. C007 키 3건은 모두 같은 파티션
# =============================================================================
hr "정답 4 — C007 키 3건의 파티션"

printf '%s\n' \
  'C007:{"order_id":"O-2001","customer_id":"C007","amount":11000,"status":"CREATED"}' \
  'C007:{"order_id":"O-2002","customer_id":"C007","amount":22000,"status":"PAID"}' \
  'C007:{"order_id":"O-2003","customer_id":"C007","amount":33000,"status":"SHIPPED"}' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic orders \
    --property parse.key=true --property key.separator=:

K kafka-console-consumer.sh --bootstrap-server "$BS" --topic orders \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true \
  2>&1 | grep C007 || true

# 해설:
#   기대 출력 — 세 줄 모두 Partition:1 입니다.
#       Partition:1	Offset:2	C007	{"order_id":"O-2001",...}
#       Partition:1	Offset:3	C007	{"order_id":"O-2002",...}
#       Partition:1	Offset:4	C007	{"order_id":"O-2003",...}
#
#   왜 하필 1번입니까? 우연이 아니라 계산 결과입니다.
#   Kafka 의 기본 파티셔너(키가 있을 때)는 이렇게 동작합니다.
#
#       partition = (murmur2(keyBytes) & 0x7fffffff) % numPartitions
#
#   "C007" 의 실제 값:
#       murmur2("C007")            = 642592957
#       & 0x7fffffff (양수화)      = 642592957
#       % 3                        = 1        ← 파티션 1
#
#   & 0x7fffffff 는 최상위 부호 비트를 지워 음수를 양수로 만듭니다.
#   음수에 % 를 하면 Java 에서도 음수가 나와 파티션 번호로 쓸 수 없기 때문입니다.
#
#   여기서 나오는 결론 두 가지:
#     (1) 같은 키는 항상 같은 파티션 → 그 키의 메시지 순서가 보장됩니다.
#     (2) 계산식에 numPartitions 가 들어갑니다. 파티션 수가 바뀌면 결과도 바뀝니다.
#         이것이 Step 03 의 핵심 함정입니다. 3 → 6 으로 늘리면
#         642592957 % 6 = 1 이라 C007 은 마침 그대로지만, C001 같은 키는
#         파티션 2 에서 5 로 이동합니다. 그 순간부터 순서 보장이 깨집니다.

# =============================================================================
# 정답 5. 없는 토픽 — 프로듀서와 컨슈머의 차이
# =============================================================================
hr "정답 5 — 없는 토픽: 프로듀서 vs 컨슈머"

echo "--- 프로듀서 (max.block.ms=5000 으로 대기 단축) ---"
printf '%s\n' 'hello' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic oders \
    --producer-property max.block.ms=5000 2>&1 | tail -4 || true

echo "--- 컨슈머 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic oders \
  --from-beginning --timeout-ms 5000 \
  --consumer-property default.api.timeout.ms=5000 2>&1 | tail -4 || true

# 해설:
#   프로듀서:
#       org.apache.kafka.common.errors.TimeoutException:
#         Topic oders not present in metadata after 5000 ms.
#     단, 이 예외는 "첫 메시지를 보낼 때" 납니다. 프로듀서를 실행하면
#     ">" 프롬프트가 정상적으로 뜹니다. 에러가 없습니다.
#
#   컨슈머:
#       WARN ... Error while fetching metadata with correlation id 2 :
#         {oders=UNKNOWN_TOPIC_OR_PARTITION}
#       org.apache.kafka.common.errors.TimeoutException:
#         Topic oders not present in metadata after 5000 ms.
#     컨슈머는 붙자마자 메타데이터를 조회하므로 WARN 이 즉시 반복됩니다.
#
#   A1. 프로듀서는 실행 직후가 아니라 "첫 메시지를 보낼 때" 실패합니다.
#       Kafka 프로듀서는 lazy 하게 동작합니다. send() 를 호출해야 그 토픽의
#       메타데이터를 요청하고, 없으면 max.block.ms(기본 60000) 동안 기다립니다.
#
#   A2. 왜 위험한가:
#       (a) 프로듀서를 켰는데 프롬프트가 떠서 "잘 붙었다"고 착각합니다.
#       (b) 애플리케이션이라면 더 나쁩니다. 스프링 부트가 정상 기동하고
#           헬스체크도 통과합니다. 실제 트래픽이 들어오는 첫 순간에야 터집니다.
#           배포는 성공했는데 5분 뒤 장애 알림이 오는 전형적인 패턴입니다.
#       (c) auto.create.topics.enable=true 인 클러스터라면 최악입니다.
#           오타 토픽 oders 가 그 자리에서 생기고, 메시지가 거기 쌓이고,
#           전송은 "성공"합니다. 아무도 에러를 못 봅니다.
#           컨슈머는 orders 를 보고 있으니 영원히 아무것도 못 받습니다.
#
#   대비책:
#     애플리케이션 기동 시 AdminClient.describeTopics() 로 필요한 토픽이
#     존재하는지 검증하고, 없으면 기동을 실패시키세요.
#     "빨리, 시끄럽게 실패하는 것"이 항상 낫습니다.

# =============================================================================
# 정답 6. 설정 오타는 에러가 아니라 WARN 한 줄
# =============================================================================
hr "정답 6 — --producer-property 적용 확인"

printf '%s\n' 'C001:{"order_id":"O-2004","customer_id":"C001","amount":1000,"status":"CREATED"}' \
| Ki kafka-console-producer.sh --bootstrap-server "$BS" --topic orders \
    --property parse.key=true --property key.separator=: \
    --producer-property acks=0 \
    --producer-property compresion.type=gzip 2>&1 \
| grep -E "(^\s+(acks|compression\.type) |isn't a known config)" || true

# 해설:
#   기대 출력:
#       	acks = 0
#       	compression.type = none
#       WARN The configuration 'compresion.type' was supplied but isn't a known config.
#
#   acks 는 0 으로 잘 적용되었습니다. 그런데 compression.type 은 gzip 이 아니라
#   none 입니다. 문제지의 키가 compresion.type (s 하나 빠짐) 이었기 때문입니다.
#
#   여기서 Kafka 가 한 일:
#     - 에러를 내지 않았습니다.
#     - 기동을 멈추지 않았습니다.
#     - WARN 한 줄을 뱉고, 그 설정을 그냥 무시했습니다.
#     - 메시지는 압축 없이 정상 전송되었습니다.
#
#   이것이 이 코스가 다루는 "조용히 틀리는" 사례의 첫 번째입니다.
#   프로듀서 설정 오타는 대부분 이렇게 지나갑니다. 실무에서 흔한 것들:
#       max.in.flight.request.per.connection   (requests 의 s 누락)
#       compression.codec                      (Kafka 0.8 시절 이름)
#       bootstrap.server                       (servers 의 s 누락)
#   전부 WARN 한 줄로 무시되고, 여러분은 "설정했는데 왜 효과가 없지?" 를 헤맵니다.
#
#   대비책:
#     기동 로그의 "ProducerConfig values:" 블록을 실제로 읽으세요.
#     그리고 "isn't a known config" 를 로그 알림의 검색 조건에 넣으세요.
#     한 줄이지만 반드시 잡아야 하는 WARN 입니다.

# =============================================================================
# 뒷정리
# =============================================================================
hr "뒷정리"

K kafka-topics.sh --bootstrap-server "$BS" --delete --topic s01_practice || true

echo "--- 남아 있어야 할 토픽: dlq / order-events / orders / payments ---"
K kafka-topics.sh --bootstrap-server "$BS" --list

echo "--- orders 파티션별 오프셋 (문제 4·6 에서 4건이 추가되었습니다) ---"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic orders

echo
echo "orders 의 메시지는 지우지 마세요. Step 02 에서 세그먼트 파일로 직접 열어 봅니다."
