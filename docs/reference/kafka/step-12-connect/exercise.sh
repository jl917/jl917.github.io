#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Step 12 — Kafka Connect / exercise.sh   (문제 6개)
#
# 실행법:
#   cd kafka/docker && docker compose --profile connect up -d
#   bash ../step-12-connect/exercise.sh
#
# 각 문제의 "# 여기에 작성:" 아래를 직접 채우세요.
# 정답은 solution.sh 에 있습니다. 먼저 풀어 본 뒤에 여세요.
#
# 문제 1·5·6 = 커넥터를 직접 설계하는 문제
# 문제 2·3·4 = 이미 만들어진 상태를 관찰·진단하는 문제
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
hr() { echo; echo "------------------------------------------------------------"; echo "  $*"; echo "------------------------------------------------------------"; }

for i in $(seq 1 60); do curl -sf "$CONNECT/" >/dev/null 2>&1 && break; sleep 2; done


# ============================================================================
# 문제 1. FileStreamSource 커넥터 등록 + 상태 한 줄 확인
#
#   /data/q1.txt 를 읽어 s12ex_q1 토픽으로 보내는 커넥터 "q1-source" 를 만드세요.
#   그다음, 커넥터 상태와 "모든 태스크의 상태"를 쉼표로 이어 한 줄로 출력하세요.
#   기대 출력: RUNNING,RUNNING
#
#   힌트: jq 로 두 배열을 이어 붙입니다.
# ============================================================================
hr "문제 1"
KT --create --topic s12ex_q1 --partitions 3 --replication-factor 3 --if-not-exists
cat > "$DATA_DIR/q1.txt" <<'EOF'
{"order_id":"O-9001","customer_id":"C001","amount":11000}
{"order_id":"O-9002","customer_id":"C002","amount":22000}
EOF

# 여기에 작성: q1-source 커넥터를 등록하는 curl 명령


# 여기에 작성: 커넥터 + 모든 태스크 상태를 "RUNNING,RUNNING" 형태로 출력하는 명령



# ============================================================================
# 문제 2. "커넥터를 다시 만들었는데 데이터가 안 들어옵니다"
#
#   아래 준비 블록이 q2-source 커넥터를 만들고 → 데이터를 흘리고 → 삭제하고
#   → 파일을 완전히 새 내용으로 갈아엎고 → 같은 이름으로 재생성합니다.
#   그런데 새 파일의 3줄이 토픽에 들어오지 않습니다.
#
#   (a) 원인을 확인하는 명령 한 줄
#   (b) 고치는 명령 세 줄
#   (c) 고쳐졌는지 검증하는 명령 한 줄
#   을 쓰세요.
# ============================================================================
hr "문제 2 — 준비 (여기는 손대지 마세요)"
KT --create --topic s12ex_q2 --partitions 1 --replication-factor 3 --if-not-exists
printf '%s\n' 'A-1' 'A-2' 'A-3' 'A-4' 'A-5' > "$DATA_DIR/q2.txt"
curl -s -X PUT "$CONNECT/connectors/q2-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1", "file": "/data/q2.txt", "topic": "s12ex_q2",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 6
echo "5건 들어왔는지:"; KGO --topic s12ex_q2
curl -s -X DELETE "$CONNECT/connectors/q2-source" >/dev/null; sleep 3
printf '%s\n' 'B-1' 'B-2' 'B-3' > "$DATA_DIR/q2.txt"      # 파일을 통째로 교체
curl -s -X PUT "$CONNECT/connectors/q2-source/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1", "file": "/data/q2.txt", "topic": "s12ex_q2",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}' >/dev/null
sleep 6
echo "재생성 후 (여전히 5? 8이 되어야 정상 아닙니까?):"; KGO --topic s12ex_q2
echo "커넥터 상태 (멀쩡합니다. 에러가 없습니다.):"
curl -s "$CONNECT/connectors/q2-source/status" | jq -r '[.connector.state] + [.tasks[].state] | join(",")'

hr "문제 2 — 여기부터 여러분 차례"

# 여기에 작성: (a) 원인을 확인하는 명령


# 여기에 작성: (b) 고치는 명령 세 줄


# 여기에 작성: (c) 검증 — s12ex_q2 오프셋이 8이 되어야 합니다



# ============================================================================
# 문제 3. connect-configs 토픽의 cleanup.policy 와 파티션 수
#
#   (a) 두 값을 확인하는 명령을 쓰세요.
#   (b) 그 값이어야 하는 이유를 각각 한 문장으로 주석에 쓰세요.
#       - cleanup.policy 가 delete 라면 무슨 일이 생깁니까?
#       - 파티션이 3개라면 무슨 일이 생깁니까?
# ============================================================================
hr "문제 3"

# 여기에 작성: (a) 확인 명령


# 여기에 작성: (b) 이유 두 줄 (주석으로)
#   cleanup.policy=compact 여야 하는 이유:
#   PartitionCount=1 이어야 하는 이유:



# ============================================================================
# 문제 4. 커넥터가 얼마나 밀렸는지 확인하기
#
#   아래 준비 블록이 q4-sink(싱크)와 q4-source(소스)를 만듭니다.
#   각각 "얼마나 밀렸는지"를 확인하는 명령을 쓰세요.
#   두 명령은 완전히 다릅니다. 왜 다른지도 주석으로 쓰세요.
# ============================================================================
hr "문제 4 — 준비"
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

hr "문제 4 — 여기부터"

# 여기에 작성: (a) 싱크 커넥터 q4-sink 가 얼마나 밀렸는지


# 여기에 작성: (b) 소스 커넥터 q4-source 는 어디까지 읽었는지


# 여기에 작성: (c) 왜 두 명령이 다릅니까? (주석)



# ============================================================================
# 문제 5. SMT 로 키를 만들어 파티션 분산시키기
#
#   /data/q5.txt 를 s12ex_q5(3파티션)로 보내되, SMT 로 값을 키로 승격시켜
#   메시지가 여러 파티션에 흩어지게 만드세요.
#
#   정답 조건: kafka-get-offsets.sh 출력의 세 줄이 전부 0 이 아니어야 합니다.
#   힌트: FileStreamSource 는 값을 "문자열"로 내보냅니다. ValueToKey 를 바로 걸면
#         DataException 이 납니다. 한 단계가 더 필요합니다.
# ============================================================================
hr "문제 5"
KT --create --topic s12ex_q5 --partitions 3 --replication-factor 3 --if-not-exists
cat > "$DATA_DIR/q5.txt" <<'EOF'
C001
C002
C003
C001
C002
C003
EOF

# 여기에 작성: q5-source 커넥터 (SMT 포함)


# 여기에 작성: 파티션이 분산됐는지 확인



# ============================================================================
# 문제 6. DLQ 로 깨진 레코드를 격리하고 원본 위치를 추적하기
#
#   s12ex_q6_src 에는 정상 JSON 2건과 깨진 값 2건이 들어 있습니다.
#   - 정상 2건은 /data/q6-out.txt 로 나가고
#   - 깨진 2건은 s12ex_q6_dlq 토픽으로 가고
#   - DLQ 메시지의 헤더로 원본 토픽/파티션/오프셋을 알 수 있게
#   커넥터 "q6-sink" 를 만드세요.
# ============================================================================
hr "문제 6"
KT --create --topic s12ex_q6_src --partitions 1 --replication-factor 3 --if-not-exists
KT --create --topic s12ex_q6_dlq --partitions 1 --replication-factor 3 --if-not-exists
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s12ex_q6_src <<'EOF'
{"id":"A","v":1}
OOPS
{"id":"B","v":2}
{"unclosed":
EOF

# 여기에 작성: q6-sink 커넥터


# 여기에 작성: 정상 2건이 파일로 나갔는지 확인


# 여기에 작성: DLQ 2건과 헤더 확인



# ============================================================================
# 정리 — 문제를 안 풀고 정리만 돌려도 에러 없이 끝납니다.
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
