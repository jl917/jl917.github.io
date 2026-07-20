#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 12 — Kafka Connect / solution.sh   (정답 + 해설)
#
# 실행법:
#   cd kafka/docker && docker compose --profile connect up -d
#   bash ../step-12-connect/solution.sh
#
# exercise.sh 를 먼저 풀어 본 뒤에 여세요.
# ============================================================================

BS=kafka-1:9092
CONNECT=localhost:8083
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
DATA_DIR="$DOCKER_DIR/connect-data"
mkdir -p "$DATA_DIR"

K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
KT() { K kafka-topics.sh --bootstrap-server "$BS" "$@"; }
KCG(){ K kafka-consumer-groups.sh --bootstrap-server "$BS" "$@"; }
KGO(){ K kafka-get-offsets.sh --bootstrap-server "$BS" "$@"; }
hr() { echo; echo "============================================================"; echo "  $*"; echo "============================================================"; }

for i in $(seq 1 60); do curl -sf "$CONNECT/" >/dev/null 2>&1 && break; sleep 2; done


# ============================================================================
# 정답 1 — 커넥터 등록 + "모든 태스크" 상태 확인
# ============================================================================
hr "정답 1"
KT --create --topic s12ex_q1 --partitions 3 --replication-factor 3 --if-not-exists
cat > "$DATA_DIR/q1.txt" <<'EOF'
{"order_id":"O-9001","customer_id":"C001","amount":11000}
{"order_id":"O-9002","customer_id":"C002","amount":22000}
EOF

curl -s -X PUT "$CONNECT/connectors/q1-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/q1.txt",
  "topic": "s12ex_q1",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' | jq -r '.name'
sleep 6

curl -s "$CONNECT/connectors/q1-source/status" \
  | jq -r '[.connector.state] + [.tasks[].state] | join(",")'

# 해설:
#   POST /connectors 대신 PUT /connectors/{name}/config 를 썼습니다. PUT 은 멱등이라
#   이미 있으면 갱신하고 없으면 생성합니다. POST 는 중복이면 409 Conflict 를 내므로
#   CI/CD 나 반복 실행되는 스크립트에서는 항상 PUT /config 를 씁니다.
#
#   ★ 이 문제의 핵심은 jq 표현식입니다.
#       jq -r '.connector.state'                     ← 이것만 보면 안 됩니다
#       jq -r '[.connector.state] + [.tasks[].state] | join(",")'   ← 이게 맞습니다
#
#   커넥터는 "일을 몇 조각으로 나눌지" 계획만 세우고, 실제 데이터는 태스크가 옮깁니다.
#   그래서 태스크가 전부 죽어도 커넥터는 RUNNING 일 수 있습니다(본문 함정 C).
#   .connector.state 만 보는 모니터링은 이런 장애에서 절대 알람을 울리지 않습니다.
#
#   운영에서는 전 커넥터를 순회하며 FAILED 를 찾는 형태로 씁니다:
for c in $(curl -s "$CONNECT/connectors" | jq -r '.[]'); do
  s=$(curl -s "$CONNECT/connectors/$c/status")
  line="$c $(echo "$s" | jq -r '.connector.state') tasks=$(echo "$s" | jq -r '[.tasks[].state] | join(",")')"
  echo "$line"
  case "$line" in *FAILED*) echo "  ↑ 알람 대상" ;; esac
done


# ============================================================================
# 정답 2 — 커넥터를 지웠는데 오프셋이 남아 이어서 읽는 문제
# ============================================================================
hr "정답 2 — 준비 (문제 상황 재현)"
KT --create --topic s12ex_q2 --partitions 1 --replication-factor 3 --if-not-exists
printf '%s\n' 'A-1' 'A-2' 'A-3' 'A-4' 'A-5' > "$DATA_DIR/q2.txt"
curl -s -X PUT "$CONNECT/connectors/q2-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1", "file": "/data/q2.txt", "topic": "s12ex_q2",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 6
curl -s -X DELETE "$CONNECT/connectors/q2-source" >/dev/null; sleep 3
printf '%s\n' 'B-1' 'B-2' 'B-3' > "$DATA_DIR/q2.txt"
curl -s -X PUT "$CONNECT/connectors/q2-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1", "file": "/data/q2.txt", "topic": "s12ex_q2",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 6
echo "재생성 후 오프셋 (5 그대로):"; KGO --topic s12ex_q2

hr "정답 2 — 진단과 해결"

# (a) 원인 확인 — connect-offsets 에 이전 position 이 남아 있습니다.
curl -s "$CONNECT/connectors/q2-source/offsets" | jq

# (b) 해결 — stop → DELETE /offsets → resume
curl -s -X PUT    "$CONNECT/connectors/q2-source/stop"    -w 'stop HTTP %{http_code}\n'
sleep 3
curl -s -X DELETE "$CONNECT/connectors/q2-source/offsets" | jq -r '.message'
curl -s -X PUT    "$CONNECT/connectors/q2-source/resume"  -w 'resume HTTP %{http_code}\n'
sleep 7

# (c) 검증 — 5 + 3 = 8
KGO --topic s12ex_q2

# 해설:
#   DELETE /connectors/{name} 은 connect-configs 에서 "설정"만 지웁니다.
#   connect-offsets 토픽의 레코드는 그대로 남습니다. 키가
#       ["q2-source",{"filename":"/data/q2.txt"}]
#   인데, 같은 이름 + 같은 파일 경로로 재생성하면 키가 정확히 일치하므로
#   Connect 는 "아, 이 커넥터는 20바이트째까지 읽었었지" 하고 그 지점부터 재개합니다.
#   새 파일이 그보다 짧으면 읽을 게 없고, 커넥터는 RUNNING 인 채 아무것도 안 합니다.
#   ★ 에러도 경고도 안 납니다. 이것이 함정 B 입니다.
#
#   왜 stop 이 먼저여야 합니까:
#     RUNNING 상태에서 DELETE /offsets 를 호출하면 이렇게 거부됩니다.
#       HTTP 409 Conflict
#       {"error_code":409,"message":"Connectors must be in the STOPPED state before
#        their offsets can be modified. This can be done for the specified connector
#        by issuing a 'PUT' request to the '/connectors/q2-source/stop' endpoint"}
#     오프셋을 바꾸는 도중에 태스크가 계속 오프셋을 쓰면 경쟁 상태가 되기 때문입니다.
#
#   PUT /pause 가 아니라 PUT /stop 인 것에 주의하세요 (Kafka 3.5+).
#     pause : 태스크를 살려 둔 채 데이터만 안 옮김. 리소스는 계속 씀. → 오프셋 수정 불가
#     stop  : 태스크를 전부 내림. 설정만 남음.                        → 오프셋 수정 가능
#
#   ★ 운영에서 더 권하는 방법은 오프셋 삭제가 아니라 "커넥터 이름 버저닝"입니다.
#       q2-source → q2-source-v2
#     오프셋 키가 달라지므로 자동으로 처음부터 읽고, 문제가 생기면 옛 커넥터를
#     그대로 되살릴 수 있습니다. 오프셋 삭제는 되돌릴 수 없는 파괴적 작업입니다.
#     3.6 미만 버전에는 DELETE /offsets 자체가 없어서 이 방법밖에 없기도 합니다.


# ============================================================================
# 정답 3 — connect-configs 의 cleanup.policy 와 파티션 수
# ============================================================================
hr "정답 3"
KT --describe --topic connect-configs

# 기대 출력:
#   Topic: connect-configs  TopicId: ...  PartitionCount: 1  ReplicationFactor: 3
#     Configs: cleanup.policy=compact
#
# 해설:
#   cleanup.policy=compact 여야 하는 이유
#     이 토픽의 키는 커넥터 이름이고, 값은 그 커넥터의 최신 설정 JSON 입니다.
#     "키별 최신 값"만 의미가 있으므로 compact 가 맞습니다.
#     delete 였다면 retention.ms(기본 7일)가 지나는 순간 설정 레코드가 삭제되고,
#     ★ 워커를 재시작하는 순간 커넥터가 전부 사라집니다. 재시작 전까지는 메모리에
#     설정이 남아 있어 멀쩡히 돌아가므로, 사고는 몇 주 뒤 예정된 재배포 때 터집니다.
#     connect-offsets, connect-status 도 같은 이유로 셋 다 compact 입니다.
#
#   PartitionCount=1 이어야 하는 이유
#     Connect 는 설정 변경 이벤트를 "로그 순서대로 재생"해서 현재 상태를 만듭니다.
#     "A 생성 → A 수정 → A 삭제 → A 재생성" 이 순서대로 읽혀야 최종 상태가 맞습니다.
#     파티션이 2개 이상이면 삭제와 재생성이 다른 파티션에 들어갈 수 있고,
#     병렬로 읽으면 순서가 뒤집혀 "방금 만든 커넥터가 사라지는" 일이 생깁니다.
#     그래서 Connect 는 아예 기동을 거부합니다:
#       org.apache.kafka.connect.errors.ConnectException: Topic 'connect-configs'
#         supplied via the 'config.storage.topic' property is required to have a
#         single partition in order to guarantee consistency, but found 3 partitions.
#
#   ⚠️ 실무 주의: 토픽을 사전 승인 절차로 손수 만드는 조직에서 이 두 값을 빼먹는
#      일이 흔합니다. 손으로 만들 거면 반드시:
#        kt --create --topic connect-configs --partitions 1 --replication-factor 3 \
#           --config cleanup.policy=compact


# ============================================================================
# 정답 4 — 싱크와 소스의 "밀림"을 확인하는 법
# ============================================================================
hr "정답 4 — 준비"
KT --create --topic s12ex_q4 --partitions 3 --replication-factor 3 --if-not-exists
printf '%s\n' 'X-1' 'X-2' 'X-3' 'X-4' > "$DATA_DIR/q4.txt"
curl -s -X PUT "$CONNECT/connectors/q4-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1", "file": "/data/q4.txt", "topic": "s12ex_q4",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
curl -s -X PUT "$CONNECT/connectors/q4-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1", "topics": "s12ex_q4", "file": "/data/q4-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 8

hr "정답 4"

# (a) 싱크 — 컨슈머 그룹의 LAG 컬럼
KCG --describe --group connect-q4-sink

# (b) 소스 — REST 로 소스 시스템 위치(여기서는 파일 바이트 오프셋)
curl -s "$CONNECT/connectors/q4-source/offsets" | jq

# (c) 왜 다릅니까 — 해설:
#   ┌──────────┬─────────────────────────────┬──────────────────────────────────┐
#   │          │ 소스 커넥터                  │ 싱크 커넥터                       │
#   ├──────────┼─────────────────────────────┼──────────────────────────────────┤
#   │ 저장 위치 │ connect-offsets 토픽        │ 컨슈머 그룹 connect-<이름>        │
#   │          │                             │ (= __consumer_offsets)           │
#   │ 조회     │ GET /connectors/{n}/offsets │ kcg --describe --group connect-…  │
#   │ 오프셋 의미│ 소스 시스템의 위치           │ Kafka 토픽의 오프셋               │
#   │          │ (파일 바이트, DB 커서, LSN)  │                                  │
#   │ 리셋     │ DELETE /connectors/{n}/offsets │ kcg --reset-offsets 또는 위와 동일│
#   └──────────┴─────────────────────────────┴──────────────────────────────────┘
#
#   싱크 커넥터는 그냥 컨슈머입니다. Kafka 가 이미 오프셋 관리 메커니즘을 갖고 있으니
#   그걸 그대로 씁니다. 그래서 Step 05·06 에서 배운 랙 모니터링이 그대로 적용됩니다.
#
#   ★ 이 문제의 진짜 답: 소스 커넥터에는 "랙"이라는 개념 자체가 없습니다.
#     소스는 Kafka 밖의 무언가를 읽으므로, "얼마나 뒤처졌는가"를 재려면
#     소스 시스템 쪽 지표가 필요합니다. 예를 들어
#       - JDBC 소스: max(updated_at) - 커넥터가 저장한 timestamp
#       - Debezium:  DB 의 현재 LSN/GTID - 커넥터가 저장한 LSN
#     Connect 가 알려 주는 것은 "내가 어디까지 읽었다" 뿐이고,
#     "소스에 얼마나 더 있는지"는 Connect 가 모릅니다.
#
#   그리고 컨슈머 그룹은 커넥터를 지워도 남습니다:
#     curl -X DELETE .../connectors/q4-sink   → 그룹 connect-q4-sink 는 그대로
#     kcg --delete --group connect-q4-sink    → 이걸 따로 해야 합니다


# ============================================================================
# 정답 5 — SMT 로 키를 만들어 파티션 분산시키기
# ============================================================================
hr "정답 5"
KT --create --topic s12ex_q5 --partitions 3 --replication-factor 3 --if-not-exists
printf '%s\n' C001 C002 C003 C001 C002 C003 > "$DATA_DIR/q5.txt"

curl -s -X PUT "$CONNECT/connectors/q5-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/q5.txt",
  "topic": "s12ex_q5",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter",

  "transforms": "hoist,mkKey,extractKey",

  "transforms.hoist.type": "org.apache.kafka.connect.transforms.HoistField$Value",
  "transforms.hoist.field": "cid",

  "transforms.mkKey.type": "org.apache.kafka.connect.transforms.ValueToKey",
  "transforms.mkKey.fields": "cid",

  "transforms.extractKey.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
  "transforms.extractKey.field": "cid"
}' | jq -r '.name'
sleep 8

echo "--- 파티션 분산 확인 (세 줄 전부 0 이 아니어야 정답) ---"
KGO --topic s12ex_q5
echo "--- 키가 붙었는지 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s12ex_q5 \
  --from-beginning --property print.key=true --property print.partition=true \
  --timeout-ms 5000 || true

# 해설:
#   3단 체인이 필요합니다. 왜 3단입니까?
#
#     ① HoistField$Value  — 문자열 "C001" 을 {"cid":"C001"} 이라는 Map 으로 감쌉니다
#     ② ValueToKey        — 값의 cid 필드를 키로 복사 → 키가 {"cid":"C001"}
#     ③ ExtractField$Key  — 키에서 cid 를 꺼내 평문 "C001" 로 만듭니다
#
#   ★ ① 을 빼고 ValueToKey 를 바로 걸면 이렇게 죽습니다:
#       org.apache.kafka.connect.errors.DataException: Only Struct objects supported
#         for [copying fields from value to key], found: java.lang.String
#     ValueToKey / InsertField / ReplaceField 같은 "필드를 다루는" SMT 는 값이
#     Struct 또는 Map 이어야 합니다. FileStreamSource 는 값을 평범한 String 으로
#     내보내므로 필드라는 개념이 없습니다.
#
#   ★ ③ 을 빼면 키는 {"cid":"C001"} 이라는 Map 이 됩니다. StringConverter 로는
#     직렬화가 안 되어 또 DataException 이 납니다. 키가 평문이어야 합니다.
#
#   실무에서는 이 3단이 거의 필요 없습니다. JDBC 소스나 Debezium 은 처음부터
#   스키마 있는 Struct 를 내보내므로 ②③ 두 단계면 끝납니다.
#   파일 소스가 유별난 것입니다.
#
#   왜 키가 필요합니까 — 키가 null 이면 프로듀서가 스티키 파티셔닝으로 한 파티션에
#   몰아 넣습니다(Step 04). 키가 생기면 murmur2(key) % partitions 로 흩어지고,
#   동시에 "같은 고객의 메시지는 항상 같은 파티션"이라는 순서 보장도 얻습니다.


# ============================================================================
# 정답 6 — DLQ + 에러 컨텍스트 헤더
# ============================================================================
hr "정답 6"
KT --create --topic s12ex_q6_src --partitions 1 --replication-factor 3 --if-not-exists
KT --create --topic s12ex_q6_dlq --partitions 1 --replication-factor 3 --if-not-exists
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s12ex_q6_src <<'EOF'
{"id":"A","v":1}
OOPS
{"id":"B","v":2}
{"unclosed":
EOF

curl -s -X PUT "$CONNECT/connectors/q6-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12ex_q6_src",
  "file": "/data/q6-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",

  "errors.tolerance": "all",
  "errors.log.enable": "true",
  "errors.log.include.messages": "true",
  "errors.deadletterqueue.topic.name": "s12ex_q6_dlq",
  "errors.deadletterqueue.topic.replication.factor": "3",
  "errors.deadletterqueue.context.headers.enable": "true"
}' | jq -r '.name'
sleep 10

echo "--- 커넥터 상태 (RUNNING,RUNNING 이어야 합니다) ---"
curl -s "$CONNECT/connectors/q6-sink/status" | jq -r '[.connector.state] + [.tasks[].state] | join(",")'

echo "--- 정상 2건 ---"
docker exec kafka-connect cat /data/q6-out.txt

echo "--- DLQ 2건 + 헤더 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s12ex_q6_dlq \
  --from-beginning --property print.headers=true --timeout-ms 6000 || true

# 해설:
#   3종 세트가 전부 필요합니다.
#     errors.tolerance=all                            ← 없으면 태스크가 즉시 죽습니다
#     errors.deadletterqueue.topic.name=...           ← 없으면 실패 레코드가 증발합니다
#     errors.deadletterqueue.context.headers.enable=true ← 없으면 원인을 알 수 없습니다
#
#   ★ 세 번째가 이 문제의 핵심입니다. 헤더 없이 DLQ 만 켜면 이렇게 나옵니다:
#       OOPS
#       {"unclosed":
#     원본 바이트만 덜렁 있습니다. 어느 토픽에서 왔는지, 어느 오프셋인지,
#     무엇 때문에 실패했는지 알 방법이 전혀 없습니다. 쓰레기통일 뿐입니다.
#
#     headers.enable=true 면 이렇게 나옵니다:
#       __connect.errors.topic:s12ex_q6_src,
#       __connect.errors.partition:0,
#       __connect.errors.offset:1,
#       __connect.errors.connector.name:q6-sink,
#       __connect.errors.task.id:0,
#       __connect.errors.stage:VALUE_CONVERTER,
#       __connect.errors.class.name:org.apache.kafka.connect.json.JsonConverter,
#       __connect.errors.exception.class.name:org.apache.kafka.connect.errors.DataException,
#       __connect.errors.exception.message:Converting byte[] to Kafka Connect data
#         failed due to serialization error:                    OOPS
#
#     stage 가 특히 유용합니다. KEY_CONVERTER / VALUE_CONVERTER / TRANSFORMATION /
#     TASK_PUT 중 어디서 터졌는지 알려 주므로, 문제가 "데이터"인지 "SMT 설정"인지
#     "대상 시스템"인지 즉시 갈립니다.
#     offset 을 알면 재처리도 가능합니다. 원본 토픽의 그 오프셋을 고쳐서 다시 넣으면 됩니다.
#
#   ⚠️ errors.tolerance=all 을 DLQ 없이 쓰는 것이 가장 위험합니다.
#      실패 레코드가 어디에도 안 남고 사라지는데, 커넥터는 RUNNING 이고 랙은 0 이라
#      모니터링이 정상으로 보입니다. 이 코스가 계속 말하는 "조용한 유실"의 Connect 판입니다.
#      원칙: errors.tolerance=all 을 쓸 거면 반드시 DLQ 를 함께 설정하고,
#            DLQ 토픽에 메시지가 들어오는 순간 알람을 겁니다.
#
#   ⚠️ DLQ 는 싱크 커넥터 전용입니다. 소스 커넥터에는 errors.deadletterqueue.* 가
#      없습니다. 소스 쪽 에러는 errors.tolerance 와 로그로만 다룹니다.


# ============================================================================
# 정리
# ============================================================================
hr "정리"
for c in q1-source q2-source q4-source q4-sink q5-source q6-sink; do
  curl -s -X DELETE "$CONNECT/connectors/$c" >/dev/null 2>&1 || true
done
sleep 3
for t in $(KT --list | grep -E '^s12ex_' || true); do KT --delete --topic "$t" || true; done
for g in $(KCG --list | grep -E '^connect-q' || true); do KCG --delete --group "$g" || true; done
rm -f "$DATA_DIR"/q[0-9]*.txt "$DATA_DIR"/q[0-9]*-out.txt
echo "정리 완료"
