#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 12 — Kafka Connect / practice.sh
#
# 실행법:
#   cd kafka/docker && mkdir -p connect-data && docker compose --profile connect up -d
#   bash ../step-12-connect/practice.sh
#
#   한 단계씩 보려면:  bash -x practice.sh
#
# 요구사항: jq, curl
#   macOS:  brew install jq
#   Debian: apt-get install -y jq
#
# 이 스크립트는 kafka/docker 디렉터리를 기준으로 connect-data/ 에 파일을 씁니다.
# 스크립트 어디서 실행해도 되도록 DOCKER_DIR 을 자동으로 찾습니다.
# ============================================================================

BS=kafka-1:9092
CONNECT=localhost:8083
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
DATA_DIR="$DOCKER_DIR/connect-data"
mkdir -p "$DATA_DIR"

# 컨테이너 안 CLI 헬퍼
K()  { docker exec kafka-1 /opt/kafka/bin/"$@"; }
KT() { K kafka-topics.sh --bootstrap-server "$BS" "$@"; }
KCG(){ K kafka-consumer-groups.sh --bootstrap-server "$BS" "$@"; }
KGO(){ K kafka-get-offsets.sh --bootstrap-server "$BS" "$@"; }

hr()  { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }

# 커넥터 + 모든 태스크 상태를 한 줄로. 함정 C 대응 헬퍼.
all_states() {
  curl -s "$CONNECT/connectors/$1/status" \
    | jq -r '[.connector.state] + [.tasks[].state] | join(",")'
}

# ----------------------------------------------------------------------------
# [12-0] 실습 준비 — Connect 워커가 뜰 때까지 대기
#   워커 기동에 40~70초가 걸립니다. 이 대기 없이 바로 등록하면 Connection refused.
# ----------------------------------------------------------------------------
wait_connect() {
  echo "Connect 워커 대기 중..."
  for i in $(seq 1 60); do
    if curl -sf "$CONNECT/" >/dev/null 2>&1; then
      echo "  → 준비 완료 (${i}회 시도)"
      return 0
    fi
    sleep 2
  done
  echo "Connect 워커가 뜨지 않았습니다. docker logs kafka-connect 를 확인하세요." >&2
  exit 1
}

hr "[12-0] 실습 준비"
wait_connect
curl -s "$CONNECT/" | jq
# kafka_cluster_id 가 MkU3OEVBNTcwNTJENDM2Qk 여야 우리 클러스터에 붙은 것입니다.

# ----------------------------------------------------------------------------
# [12-3] 내부 토픽 3종
# ----------------------------------------------------------------------------
hr "[12-3] 내부 토픽 3종"
KT --list | grep '^connect-' || true
echo "--- connect-configs (파티션 1 강제, compact) ---"
KT --describe --topic connect-configs
echo "--- connect-offsets (25 파티션, compact) ---"
KT --describe --topic connect-offsets | head -4
echo "--- connect-status (5 파티션, compact) ---"
KT --describe --topic connect-status | head -3

# ----------------------------------------------------------------------------
# [12-4] REST API — 플러그인 목록
#   JDBC 가 없다는 것을 확인합니다. 기본 이미지에는 FileStream + MirrorMaker2 뿐.
# ----------------------------------------------------------------------------
hr "[12-4] 설치된 커넥터 플러그인"
curl -s "$CONNECT/connector-plugins" | jq -r '.[] | "\(.type)\t\(.class)"'
echo "--- 현재 커넥터 목록 ---"
curl -s "$CONNECT/connectors" | jq

# ----------------------------------------------------------------------------
# [12-5] FileStreamSource — 파일을 토픽으로
# ----------------------------------------------------------------------------
hr "[12-5] FileStreamSource"
KT --create --topic s12_file --partitions 3 --replication-factor 3 --if-not-exists

cat > "$DATA_DIR/orders.txt" <<'EOF'
{"order_id":"O-1001","customer_id":"C001","amount":39000,"status":"CREATED"}
{"order_id":"O-1002","customer_id":"C002","amount":12500,"status":"CREATED"}
{"order_id":"O-1003","customer_id":"C001","amount":88000,"status":"CREATED"}
EOF
docker exec kafka-connect cat /data/orders.txt

curl -s -X POST "$CONNECT/connectors" -H 'Content-Type: application/json' -d '{
  "name": "file-source",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
    "tasks.max": "1",
    "file": "/data/orders.txt",
    "topic": "s12_file",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}' | jq

# 등록 응답의 "tasks": [] 는 정상입니다. 태스크 배정 전에 응답이 돌아옵니다.
sleep 4
echo "--- status ---"
curl -s "$CONNECT/connectors/file-source/status" | jq

echo "--- s12_file 내용 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s12_file \
  --from-beginning --property print.offset=true --timeout-ms 5000 || true

# 파일에 한 줄 추가 → tail -f 처럼 따라옵니다
echo '{"order_id":"O-1004","customer_id":"C003","amount":5000,"status":"CREATED"}' >> "$DATA_DIR/orders.txt"
sleep 4
echo "--- 오프셋 (키가 없으므로 전부 파티션 0 에 몰립니다) ---"
KGO --topic s12_file

echo "--- 소스 오프셋 (position 은 '바이트' 위치입니다) ---"
curl -s "$CONNECT/connectors/file-source/offsets" | jq

echo "--- connect-offsets 토픽 원본 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic connect-offsets \
  --from-beginning --property print.key=true --timeout-ms 5000 || true

# ----------------------------------------------------------------------------
# [12-6] FileStreamSink — 토픽을 다시 파일로
#   소스는 "topic"(단수), 싱크는 "topics"(복수). 한 글자 차이입니다.
# ----------------------------------------------------------------------------
hr "[12-6] FileStreamSink"
curl -s -X POST "$CONNECT/connectors" -H 'Content-Type: application/json' -d '{
  "name": "file-sink",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
    "tasks.max": "1",
    "topics": "s12_file",
    "file": "/data/orders-out.txt",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.storage.StringConverter"
  }
}' | jq -r '.name + " → " + .type'
sleep 6
docker exec kafka-connect cat /data/orders-out.txt

# ----------------------------------------------------------------------------
# [12-7-A] 함정 A — 소스와 싱크의 오프셋 저장 위치가 다릅니다
#   싱크만 컨슈머 그룹(connect-<이름>)을 만듭니다. 소스는 안 만듭니다.
# ----------------------------------------------------------------------------
hr "[12-7-A] 함정 A — 오프셋 저장 위치"
echo "--- 컨슈머 그룹 목록 (connect-file-sink 만 있고 connect-file-source 는 없음) ---"
KCG --list
echo "--- 싱크는 완전히 평범한 컨슈머 그룹입니다 ---"
KCG --describe --group connect-file-sink

# ----------------------------------------------------------------------------
# [12-7-B] 함정 B — 커넥터를 삭제해도 오프셋이 남습니다
#   ★ 이 구간은 순서를 건너뛰지 마세요. 중간을 생략하면 함정이 재현되지 않습니다.
# ----------------------------------------------------------------------------
hr "[12-7-B] 함정 B — 삭제해도 남는 오프셋"

echo "--- ① 커넥터 삭제 ---"
curl -s -X DELETE "$CONNECT/connectors/file-source" -w 'HTTP %{http_code}\n'
sleep 2
curl -s "$CONNECT/connectors" | jq

echo "--- ② 파일을 완전히 새로 씁니다 (152바이트, 2줄) ---"
cat > "$DATA_DIR/orders.txt" <<'EOF'
{"order_id":"O-2001","customer_id":"C005","amount":77000,"status":"CREATED"}
{"order_id":"O-2002","customer_id":"C006","amount":31000,"status":"CREATED"}
EOF
wc -c "$DATA_DIR/orders.txt"

echo "--- ③ 같은 이름으로 재생성 (PUT /config 는 멱등) ---"
curl -s -X PUT "$CONNECT/connectors/file-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/orders.txt",
  "topic": "s12_file",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' | jq -r '.name'
sleep 6

echo "--- ④ 오프셋이 4 에서 안 늘어납니다. 에러도 안 납니다. ---"
KGO --topic s12_file
all_states file-source     # RUNNING,RUNNING — 멀쩡합니다

echo "--- ⑤ 범인: position 304 가 남아 있습니다 (새 파일은 152바이트뿐) ---"
curl -s "$CONNECT/connectors/file-source/offsets" | jq

echo "--- ⑥ 해결: stop → DELETE /offsets → resume ---"
curl -s -X PUT "$CONNECT/connectors/file-source/stop" -w 'HTTP %{http_code}\n'
sleep 3
# DELETE /offsets 는 커넥터가 STOPPED 여야 합니다. RUNNING 이면 409 Conflict.
curl -s -X DELETE "$CONNECT/connectors/file-source/offsets" | jq
curl -s -X PUT "$CONNECT/connectors/file-source/resume" -w 'HTTP %{http_code}\n'
sleep 6

echo "--- ⑦ 4 → 6. 새 파일의 2줄이 들어왔습니다. ---"
KGO --topic s12_file

# 대안: 커넥터 이름을 file-source-v2 로 바꾸면 오프셋 키가 달라져 처음부터 읽습니다.
#       운영에서는 이 방법(이름 버저닝)이 더 안전합니다. 되돌리기가 쉽습니다.

# ----------------------------------------------------------------------------
# [12-7-C] 함정 C — 커넥터는 RUNNING, 태스크는 FAILED
#   /proc 아래는 컨테이너 안에서도 새 파일을 못 만듭니다. 확실히 실패합니다.
# ----------------------------------------------------------------------------
hr "[12-7-C] 함정 C — 태스크만 죽는다"

curl -s -X PUT "$CONNECT/connectors/file-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_file",
  "file": "/proc/nope/orders-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 6

echo "--- .connector.state 만 보면 (이것만 보는 모니터링은 장애를 놓칩니다) ---"
curl -s "$CONNECT/connectors/file-sink/status" | jq -r '.connector.state'
echo "--- 태스크까지 보면 ---"
all_states file-sink       # RUNNING,FAILED

echo "--- trace 필드 (스택트레이스 전문이 들어옵니다) ---"
curl -s "$CONNECT/connectors/file-sink/status" | jq -r '.tasks[0].trace' | head -4

echo "--- 운영용: 전 커넥터 일괄 점검 ---"
for c in $(curl -s "$CONNECT/connectors" | jq -r '.[]'); do
  s=$(curl -s "$CONNECT/connectors/$c/status")
  echo "$c $(echo "$s" | jq -r '.connector.state') tasks=$(echo "$s" | jq -r '[.tasks[].state] | join(",")')"
done

echo "--- 고치고 재시작. 설정만 고쳐서는 죽은 태스크가 안 살아납니다. ---"
curl -s -X PUT "$CONNECT/connectors/file-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_file",
  "file": "/data/orders-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
# includeTasks=true 가 없으면 커넥터만 재시작하고 태스크는 FAILED 로 남습니다.
curl -s -X POST "$CONNECT/connectors/file-sink/restart?includeTasks=true&onlyFailed=true" -w 'HTTP %{http_code}\n'
sleep 6
all_states file-sink       # RUNNING,RUNNING

# ----------------------------------------------------------------------------
# [12-4b] pause / resume / config 조회
# ----------------------------------------------------------------------------
hr "[12-4b] pause / resume"
curl -s -X PUT "$CONNECT/connectors/file-sink/pause" -w 'pause HTTP %{http_code}\n'
sleep 3
curl -s "$CONNECT/connectors/file-sink/status" | jq -r '.connector.state'   # PAUSED
curl -s -X PUT "$CONNECT/connectors/file-sink/resume" -w 'resume HTTP %{http_code}\n'
sleep 3
all_states file-sink

echo "--- 설정만 조회 ---"
curl -s "$CONNECT/connectors/file-sink/config" | jq

# ----------------------------------------------------------------------------
# [12-8] SMT — 키 승격 + 타임스탬프 + 라우팅
#   transforms 의 쉼표 순서가 곧 적용 순서입니다.
#   맨 앞 HoistField$Value 를 빼면 "Only Struct objects supported" 로 죽습니다.
# ----------------------------------------------------------------------------
hr "[12-8] SMT 체인"
KT --create --topic "s12_smt.s12_file" --partitions 3 --replication-factor 3 --if-not-exists

cat > "$DATA_DIR/orders-smt.txt" <<'EOF'
{"order_id":"O-3001","customer_id":"C007","amount":21000,"status":"CREATED"}
{"order_id":"O-3002","customer_id":"C008","amount":45000,"status":"CREATED"}
EOF

curl -s -X PUT "$CONNECT/connectors/file-source-smt/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "/data/orders-smt.txt",
  "topic": "s12_file",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",

  "transforms": "parse,mkKey,extractKey,addTs,route",

  "transforms.parse.type": "org.apache.kafka.connect.transforms.HoistField$Value",
  "transforms.parse.field": "line",

  "transforms.mkKey.type": "org.apache.kafka.connect.transforms.ValueToKey",
  "transforms.mkKey.fields": "line",

  "transforms.extractKey.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
  "transforms.extractKey.field": "line",

  "transforms.addTs.type": "org.apache.kafka.connect.transforms.InsertField$Value",
  "transforms.addTs.timestamp.field": "ingested_at",

  "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
  "transforms.route.regex": "(.*)",
  "transforms.route.replacement": "s12_smt.$1"
}' | jq -r '.name'
sleep 7

echo "--- 변환 후: 키가 생겼고, 파티션이 분산되고, ingested_at 이 붙었습니다 ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic "s12_smt.s12_file" \
  --from-beginning --property print.key=true --property print.partition=true \
  --timeout-ms 5000 || true

# ----------------------------------------------------------------------------
# [12-9] 에러 처리와 DLQ
#   일부러 두 번 설정합니다. 먼저 DLQ 없이(태스크 죽음), 그다음 DLQ 붙여서(통과).
# ----------------------------------------------------------------------------
hr "[12-9] DLQ"
KT --create --topic s12_dlq_src --partitions 1 --replication-factor 3 --if-not-exists
KT --create --topic s12_dlq     --partitions 1 --replication-factor 3 --if-not-exists

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s12_dlq_src <<'EOF'
{"order_id":"O-4001","amount":10000}
NOT-A-JSON
{"order_id":"O-4002","amount":20000}
EOF

echo "--- ① DLQ 없이: 한 건 때문에 전체가 멈춥니다 ---"
curl -s -X PUT "$CONNECT/connectors/dlq-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_dlq_src",
  "file": "/data/dlq-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false"
}' >/dev/null
sleep 8
all_states dlq-sink        # RUNNING,FAILED
curl -s "$CONNECT/connectors/dlq-sink/status" | jq -r '.tasks[0].trace' | head -4

echo "--- ② DLQ 를 붙입니다 ---"
curl -s -X PUT "$CONNECT/connectors/dlq-sink/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "s12_dlq_src",
  "file": "/data/dlq-out.txt",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false",

  "errors.tolerance": "all",
  "errors.log.enable": "true",
  "errors.log.include.messages": "true",
  "errors.deadletterqueue.topic.name": "s12_dlq",
  "errors.deadletterqueue.topic.replication.factor": "3",
  "errors.deadletterqueue.context.headers.enable": "true"
}' >/dev/null
curl -s -X POST "$CONNECT/connectors/dlq-sink/restart?includeTasks=true" -w 'HTTP %{http_code}\n'
sleep 8
all_states dlq-sink        # RUNNING,RUNNING

echo "--- 정상 2건은 통과했습니다 ---"
docker exec kafka-connect cat /data/dlq-out.txt

echo "--- 깨진 1건은 DLQ 로. 헤더에 원인이 붙습니다. ---"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s12_dlq \
  --from-beginning --property print.headers=true --timeout-ms 5000 || true

# ⚠️ errors.tolerance=all 을 DLQ 없이 쓰면 실패 레코드가 어디에도 안 남고 사라집니다.
#    커넥터는 RUNNING, 랙은 0, 알람은 안 울립니다. 반드시 DLQ 와 함께 쓰세요.

# ----------------------------------------------------------------------------
# [12-10] JDBC — 플러그인 설치가 필요하므로 기본 실행에서 제외했습니다.
#         해 보고 싶으면 아래 주석을 푸세요. 수백 MB 를 내려받습니다.
# ----------------------------------------------------------------------------
# docker exec kafka-connect confluent-hub install --no-prompt \
#   confluentinc/kafka-connect-jdbc:10.7.6
# docker restart kafka-connect && wait_connect
# curl -s $CONNECT/connector-plugins | jq -r '.[].class' | grep -i jdbc
#
# JDBC 소스 mode 4종:
#   bulk                    매번 전체 재전송
#   incrementing            INSERT 만. UPDATE/DELETE 못 잡음
#   timestamp               WHERE ts > ?  ← 동일 타임스탬프 행을 건너뜁니다 (함정)
#   timestamp+incrementing  WHERE ts > ? OR (ts = ? AND id > ?)  ← 정답
# 어느 모드든 DELETE 는 못 잡습니다. 필요하면 소프트 삭제 또는 CDC(Debezium).

# ----------------------------------------------------------------------------
# [12-11] 정리
# ----------------------------------------------------------------------------
hr "[12-11] 정리"

echo "--- 커넥터 전부 삭제 ---"
for c in $(curl -s "$CONNECT/connectors" | jq -r '.[]'); do
  curl -s -X DELETE "$CONNECT/connectors/$c" -w "$c deleted HTTP %{http_code}\n"
done
sleep 3

echo "--- s12_ 토픽 삭제 ---"
for t in $(KT --list | grep -E '^s12_' || true); do
  KT --delete --topic "$t" && echo "deleted $t"
done
KT --list | grep -E '^s12_' || echo "s12_ 토픽 없음"

echo "--- 컨슈머 그룹 삭제 (커넥터를 지워도 그룹은 남습니다 — 함정 A 의 연장) ---"
KCG --list
for g in $(KCG --list | grep -E '^connect-' || true); do
  KCG --delete --group "$g" || true
done
KCG --list

echo "--- 실습 파일 정리 ---"
rm -f "$DATA_DIR/orders.txt" "$DATA_DIR/orders-out.txt" \
      "$DATA_DIR/orders-smt.txt" "$DATA_DIR/dlq-out.txt"

# 내부 토픽 3종(connect-configs/offsets/status)은 지우지 않습니다.
# 완전 초기화가 필요할 때만:
#   docker compose stop kafka-connect
#   kt --delete --topic connect-configs
#   kt --delete --topic connect-offsets
#   kt --delete --topic connect-status
#   docker compose --profile connect up -d

hr "practice.sh 완료"
echo "Step 13 은 Connect 가 필요 없습니다. 메모리가 부족하면:"
echo "  docker compose stop kafka-connect"
