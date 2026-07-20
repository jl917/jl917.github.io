#!/usr/bin/env bash
#
# Step 09 — 보존과 로그 압축 : 실습 스크립트
#
# 실행법:
#   cd kafka/docker && bash ../step-09-retention-compaction/practice.sh
#   (한 단계씩 보려면)  bash -x ../step-09-retention-compaction/practice.sh
#
# 이 스크립트는 브로커를 죽이지 않습니다. 대신 sleep 이 많습니다.
# 보존 삭제와 로그 압축은 브로커의 백그라운드 스레드가 하는 일이라
# "명령을 치면 즉시 결과" 가 나오지 않기 때문입니다.
# 통째로 돌리면 5~8분쯤 걸립니다. sleep 을 줄이면 관찰 대상이 아직 안 일어납니다.
#
set -euo pipefail

# ── 공통 헬퍼 ────────────────────────────────────────────────────────────
K() { docker exec kafka-1 /opt/kafka/bin/"$@"; }
BS=kafka-1:9092
DATA=/var/lib/kafka/data

# 대기 시간. 노트북이 느려 관찰이 안 되면 1.5배씩 올리세요.
WAIT_RET=90     # 보존 삭제 관찰용
WAIT_CLEAN=40   # 로그 클리너 관찰용

hr() { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }

# ── [9-0] 실습 준비 ─────────────────────────────────────────────────────
hr "[9-0] 실습 준비 — Step 08 에서 죽인 브로커가 다 살아 있는지 확인"

ALIVE=$(K kafka-broker-api-versions.sh --bootstrap-server "$BS" | grep -cE '^kafka-[0-9]')
echo "살아 있는 브로커: ${ALIVE} (3 이어야 정상)"
[ "$ALIVE" -eq 3 ] || { echo 'FAIL: docker compose up -d 로 복구하세요'; exit 1; }

# ── [9-1] 대전제 ────────────────────────────────────────────────────────
hr "[9-1] Kafka 는 컨슈머가 읽어도 지우지 않는다"
echo "컨슈머가 100개든 0개든 디스크 사용량은 같습니다."
echo "지우는 것은 오직 cleanup.policy = delete | compact | compact,delete 입니다."

# ── [9-3] 액티브 세그먼트는 절대 안 지운다 ─────────────────────────────
hr "[9-3] 핵심 — 액티브 세그먼트는 절대 삭제되지 않는다"

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s09_ret \
  --partitions 1 --replication-factor 3 --config retention.ms=10000

echo "-- 512건 (1KB × 512 = 약 512KB. segment.bytes=1MiB 에 못 미쳐 롤링 안 됨)"
K kafka-producer-perf-test.sh --topic s09_ret \
  --num-records 512 --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers="$BS" acks=all

echo
echo "-- 리더 확인 (리더 브로커의 디스크를 봐야 합니다)"
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s09_ret

echo
echo "########## ① BEFORE — 세그먼트 하나뿐 = 액티브 ##########"
docker exec kafka-1 ls -la "$DATA/s09_ret-0"

echo
echo "-- retention.ms=10000 인데 ${WAIT_RET}초를 기다립니다 (보존 시간의 9배)"
sleep "$WAIT_RET"

echo
echo "########## ② AFTER(${WAIT_RET}초) — 하나도 안 바뀝니다 ##########"
docker exec kafka-1 ls -la "$DATA/s09_ret-0"
echo "-- earliest 오프셋 (0 이면 한 건도 안 지워진 것)"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s09_ret --time -2

echo
echo "-- segment.ms=10000 을 추가해 롤링을 유도합니다"
K kafka-configs.sh --bootstrap-server "$BS" --alter \
  --entity-type topics --entity-name s09_ret --add-config segment.ms=10000

echo "-- ★ 설정만으로는 롤링이 안 됩니다. 새 레코드가 들어와야 롤링 판정이 일어납니다."
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_ret <<'EOF'
{"trigger":"roll"}
EOF

sleep 30

echo
echo "########## ③ AFTER(segment.ms 적용) — 00000000000000000000.log 소멸 ##########"
docker exec kafka-1 ls -la "$DATA/s09_ret-0"

echo
echo "-- earliest / latest"
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s09_ret --time -2
K kafka-get-offsets.sh --bootstrap-server "$BS" --topic s09_ret --time -1

echo
echo "-- 브로커 로그: ① 롤링 → ② 보존 위반 판정 → ③ 파일 삭제"
docker logs kafka-1 --since 4m 2>&1 | grep -E 'Rolled|Deleting segment|Deleted log' || \
  echo '(로그를 못 찾았습니다 — --since 를 늘려 보세요)'

# ── [9-4] retention.bytes 는 파티션당 ──────────────────────────────────
hr "[9-4] retention.bytes 는 파티션당 크기다"

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s09_bytes \
  --partitions 6 --replication-factor 3 --config retention.bytes=1073741824
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s09_bytes

echo
echo "  파티션당 상한 : 1 GiB"
echo "  파티션 수     : 6"
echo "  토픽 총량     : 6 GiB"
echo "  복제 계수     : 3"
echo "  클러스터 총량 : 18 GiB   ← 설정값의 18배"

# ── [9-5] cleanup.policy=compact ───────────────────────────────────────
hr "[9-5] cleanup.policy=compact — 같은 키의 옛 값이 사라진다"

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s09_compact \
  --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=10000 \
  --config min.compaction.lag.ms=0

echo "-- 키 4개(O-1001 ~ O-1004)로 12건"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-1001:{"order_id":"O-1001","status":"CREATED","at":"2024-03-11T10:00:00Z"}
O-1002:{"order_id":"O-1002","status":"CREATED","at":"2024-03-11T10:01:00Z"}
O-1001:{"order_id":"O-1001","status":"PAID","at":"2024-03-11T10:02:00Z"}
O-1003:{"order_id":"O-1003","status":"CREATED","at":"2024-03-11T10:03:00Z"}
O-1002:{"order_id":"O-1002","status":"PAID","at":"2024-03-11T10:04:00Z"}
O-1001:{"order_id":"O-1001","status":"SHIPPED","at":"2024-03-11T10:05:00Z"}
O-1004:{"order_id":"O-1004","status":"CREATED","at":"2024-03-11T10:06:00Z"}
O-1003:{"order_id":"O-1003","status":"PAID","at":"2024-03-11T10:07:00Z"}
O-1002:{"order_id":"O-1002","status":"SHIPPED","at":"2024-03-11T10:08:00Z"}
O-1001:{"order_id":"O-1001","status":"DELIVERED","at":"2024-03-11T10:09:00Z"}
O-1004:{"order_id":"O-1004","status":"PAID","at":"2024-03-11T10:10:00Z"}
O-1003:{"order_id":"O-1003","status":"SHIPPED","at":"2024-03-11T10:11:00Z"}
EOF

echo
echo "########## 압축 전 — 컨슈머 (12건) ##########"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s09_compact \
  --from-beginning --property print.key=true --property print.offset=true \
  --timeout-ms 5000 2>&1 || true

echo
echo "########## 압축 전 — dump-log ##########"
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files "$DATA/s09_compact-0/00000000000000000000.log" \
  --print-data-log | grep -E '^\| offset' || true

echo
echo "-- 클리너도 액티브 세그먼트는 안 건드립니다. 트리거 1건으로 롤링 유도"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-9999:{"order_id":"O-9999","status":"TRIGGER","at":"2024-03-11T10:12:00Z"}
EOF

sleep "$WAIT_CLEAN"

echo
echo "-- 클리너 스레드 로그"
docker logs kafka-1 --since 2m 2>&1 | grep -iE 'Cleaner|cleaned log|size reduction' || \
  echo '(클리너 로그를 못 찾았습니다 — WAIT_CLEAN 을 늘려 보세요)'

echo
echo "########## 압축 후 — dump-log (오프셋 0~7 이 사라졌는지 보세요) ##########"
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files "$DATA/s09_compact-0/00000000000000000000.log" \
  --print-data-log | grep -E '^\| offset' || true

echo
echo "########## 압축 후 — 컨슈머 (12건 → 5건) ##########"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s09_compact \
  --from-beginning --property print.key=true --property print.offset=true \
  --timeout-ms 5000 2>&1 || true

# ── [9-6] tombstone ────────────────────────────────────────────────────
hr "[9-6] tombstone — 값이 null 인 메시지 = 삭제 표식"

echo "-- 3.7 의 console-producer 는 --property null.marker=<문자열> 을 지원합니다"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_compact \
  --property parse.key=true --property key.separator=: \
  --property null.marker=NULL <<'EOF'
O-1001:NULL
EOF

echo "-- 트리거로 롤링 유도"
docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-8888:{"order_id":"O-8888","status":"TRIGGER2","at":"2024-03-11T10:14:00Z"}
EOF
sleep 15

echo
echo "-- tombstone 은 valueSize: -1 이어야 합니다 (빈 문자열은 0)"
SEG=$(docker exec kafka-1 sh -c "ls $DATA/s09_compact-0/*.log | sort | tail -2 | head -1")
docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh \
  --files "$SEG" --print-data-log | grep -E '^\| offset' || true

if docker exec kafka-1 /opt/kafka/bin/kafka-dump-log.sh --files "$SEG" \
     --print-data-log | grep -q 'valueSize: -1'; then
  echo "OK: valueSize: -1 확인 — 진짜 tombstone 입니다"
else
  echo "WARN: valueSize: -1 을 못 찾았습니다. null.marker 가 안 먹었을 수 있습니다."
fi

echo
echo "-- delete.retention.ms 를 10초로 줄여 tombstone 자체가 사라지는 것까지 봅니다"
K kafka-configs.sh --bootstrap-server "$BS" --alter \
  --entity-type topics --entity-name s09_compact --add-config delete.retention.ms=10000

docker exec -i kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$BS" --topic s09_compact \
  --property parse.key=true --property key.separator=: <<'EOF'
O-7777:{"order_id":"O-7777","status":"TRIGGER3","at":"2024-03-11T10:16:00Z"}
EOF

sleep 45

echo
echo "########## O-1001 이 완전히 사라졌는지 확인 ##########"
K kafka-console-consumer.sh --bootstrap-server "$BS" --topic s09_compact \
  --from-beginning --property print.key=true --property print.offset=true \
  --timeout-ms 5000 2>&1 || true

echo "-- O-1001 이 위 출력에 없어야 정상입니다 (값도, tombstone 도 없음)"

# ── [9-8] compact,delete 조합 ─────────────────────────────────────────
hr "[9-8] cleanup.policy=compact,delete 조합"

K kafka-topics.sh --bootstrap-server "$BS" --create --topic s09_both \
  --partitions 1 --replication-factor 3 \
  --config cleanup.policy=compact,delete \
  --config retention.ms=604800000 \
  --config segment.ms=3600000
K kafka-topics.sh --bootstrap-server "$BS" --describe --topic s09_both

# ── [9-9] 압축 토픽 설계 기준 ─────────────────────────────────────────
hr "[9-9] 대표 사례 — __consumer_offsets 는 교과서적 압축 토픽"

K kafka-topics.sh --bootstrap-server "$BS" --describe --topic __consumer_offsets | head -2

echo
echo "  ① 키 필수                 : (그룹, 토픽, 파티션)      ✅"
echo "  ② 카디널리티 유한          : 그룹 × 파티션 수          ✅"
echo "  ③ 값이 전체 상태(델타 금지) : 커밋된 오프셋 절대값       ✅"
echo "  ④ 순서 의존 금지           : 마지막 커밋만 필요         ✅"

# ── [9-10] 정리 ──────────────────────────────────────────────────────
hr "[9-10] 정리 — s09_ 토픽 삭제"

for t in s09_ret s09_bytes s09_compact s09_both; do
  K kafka-topics.sh --bootstrap-server "$BS" --delete --topic "$t" || true
done
sleep 3

K kafka-topics.sh --bootstrap-server "$BS" --list | grep '^s09_' \
  && { echo 'FAIL: s09_ 토픽이 남아 있습니다'; exit 1; } || echo 'OK: s09_ 토픽 없음'

echo
echo "-- 토픽 삭제는 비동기입니다. 디스크에는 -delete 접미사 디렉터리가 잠시 남습니다"
docker exec kafka-1 ls "$DATA/" | grep 's09_' || echo '디스크에서도 정리됨'
echo "  (file.delete.delay.ms 기본 60초 뒤 자동 삭제됩니다)"

echo
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
echo
echo "완료. → Step 10"
