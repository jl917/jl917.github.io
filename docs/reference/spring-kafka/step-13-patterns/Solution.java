package com.example.order.step13;

/*
 * ============================================================================
 * Step 13 — 실전 패턴과 최종 프로젝트 : Solution (6문제 정답 + 해설)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step13/Solution.java
 *
 * 실행 (Exercise.java 와 프로필 이름이 다릅니다. 둘 다 두어도 충돌하지 않습니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q3'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q4 --server.port=8081'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,sol13-q6'
 *
 * ★ 매 실행 전 DB 초기화
 *   mq "TRUNCATE processed_message; TRUNCATE outbox_event; DELETE FROM orders;
 *       UPDATE inventory SET quantity = 1000;"
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
// ⚠️ org.apache.kafka.common.header.Header 를 이미 import 했으므로
//    Spring 의 @Header 는 FQCN 으로 씁니다 (Java 에는 import alias 가 없습니다).
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    static final String TOPIC_OUTBOX = "orders.outbox";
    static final String TOPIC_OUTBOX_DLT = "orders.outbox.DLT";
    static final String HDR_MESSAGE_ID = "messageId";

    static final RowMapper<Object[]> OUTBOX_MAPPER = (rs, n) -> new Object[]{
            rs.getLong("id"), rs.getString("aggregate_id"),
            rs.getString("event_type"), rs.getString("payload")};

    /* =====================================================================
     * 정답 1 — 멱등 컨슈머
     * =====================================================================
     *
     * 왜 이 답인가
     *
     * ① SELECT 가 없습니다.
     *    "있는지 확인하고 없으면 넣는다"는 SELECT 와 INSERT 사이에 빈 구간을 만듭니다.
     *    그 구간에 다른 스레드/인스턴스가 끼어들면 둘 다 "없음"으로 판정합니다.
     *    INSERT 를 먼저 던지면 판정 주체가 애플리케이션이 아니라 DB 의 PK 제약이 됩니다.
     *    UNIQUE 인덱스의 중복 검사는 원자적이므로 경합 구간이 존재하지 않습니다.
     *
     * ② 저장 키에 그룹명을 붙입니다: markKey(GROUP, messageId)
     *    processed_message 의 PK 가 message_id 단독이기 때문입니다.
     *    안 붙이면 재고 그룹이 OBX-7 을 먼저 INSERT 하는 순간,
     *    알림 그룹은 그 메시지를 처음 보는데도 DuplicateKeyException 을 맞고 스킵합니다.
     *    팬아웃 컨슈머의 절반이 조용히 아무 일도 안 합니다. 예외도 랙도 없습니다.
     *    스키마를 고칠 수 있다면 PRIMARY KEY (message_id, consumer_group) 가 정석입니다.
     *
     * ③ @Transactional 이 이력 INSERT 와 재고 UPDATE 를 함께 묶습니다.
     *    쪼개면 이력만 남고 처리는 안 된 상태(= 영구 스킵)가 생깁니다(13-3).
     *    재고 부족으로 예외를 던지면 이력 INSERT 도 함께 롤백되므로,
     *    재시도가 정상 동작하고 소진 후 DLT 로 갑니다.
     *
     * ④ DuplicateKeyException 을 잡고 return 하는 것은 "롤백"이 아니라 "정상 종료"입니다.
     *    예외를 다시 던지면 재시도가 무한 반복되고 결국 DLT 로 갑니다. 중복은 정상 상황이므로
     *    조용히 넘어가되, 로그는 반드시 남깁니다. 중복률이 갑자기 치솟으면 릴레이나
     *    커밋 쪽에 문제가 생겼다는 신호이기 때문입니다.
     *
     * ⑤ messageId 헤더가 없으면 예외를 던집니다.
     *    헤더 없이 조용히 처리하면 멱등성이 성립하지 않는데도 아무도 모릅니다.
     *    "규약 위반은 시끄럽게 실패시킨다"가 이 코스의 원칙입니다.
     */
    @Component
    @Profile("sol13-q1")
    public static class Q1IdempotentInventory {

        private static final Logger log = LoggerFactory.getLogger(Q1IdempotentInventory.class);
        static final String GROUP = "s13-sol-inventory";

        private final JdbcTemplate jdbc;

        public Q1IdempotentInventory(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @KafkaListener(topics = TOPIC_OUTBOX, groupId = GROUP)
        public void onMessage(OrderCreated e,
                              @org.springframework.messaging.handler.annotation.Header(
                                      name = HDR_MESSAGE_ID, required = false) String messageId) {
            if (messageId == null) {
                throw new IllegalStateException("missing messageId header — idempotency impossible");
            }
            consumeOnce(messageId, e);
        }

        @Transactional
        public void consumeOnce(String messageId, OrderCreated e) {
            try {
                jdbc.update("INSERT INTO processed_message (message_id, consumer_group) VALUES (?, ?)",
                        GROUP + ":" + messageId, GROUP);
            } catch (DuplicateKeyException dup) {
                log.warn("duplicate messageId={} group={} — skipped", messageId, GROUP);
                return;
            }
            int updated = jdbc.update(
                    "UPDATE inventory SET quantity = quantity - ? WHERE sku = ? AND quantity >= ?",
                    e.quantity(), e.sku(), e.quantity());
            if (updated == 0) {
                throw new IllegalStateException("out of stock " + e.sku());
            }
            Integer remaining = jdbc.queryForObject(
                    "SELECT quantity FROM inventory WHERE sku = ?", Integer.class, e.sku());
            log.info("deducted {} -{} (remaining={})", e.sku(), e.quantity(), remaining);
        }
    }

    /* =====================================================================
     * 정답 2 — SELECT-then-INSERT 경합 측정
     * =====================================================================
     *
     * 측정 결과 (5회 실행)
     *   11 / 100,  8 / 100,  14 / 100,  9 / 100,  12 / 100
     *
     * 왜 이 답인가
     *
     * ① 카운터를 올리는 위치가 wrongDeduct() 안이 아닙니다.
     *    wrongDeduct() 는 자기가 중복 차감의 피해자인지 가해자인지 알 수 없습니다.
     *    "INSERT 가 실패했다"는 사실만으로는 판정이 안 됩니다. 늦게 온 쪽이 이미 차감을
     *    했는지 안 했는지는 그 스레드의 시점에서 보이지 않기 때문입니다.
     *    라운드가 끝난 뒤 재고를 조회해 "994 인가 997 인가"로 판정하는 것이 유일한 방법입니다.
     *    이것 자체가 경합 버그의 성질입니다 — 국소적으로는 아무도 잘못을 감지하지 못합니다.
     *
     * ② 숫자가 실행마다 달라진다는 사실이 답의 절반입니다.
     *    8~14 사이에서 흔들립니다. 즉 이 버그는 "테스트를 한 번 돌려 통과했다"로는
     *    절대 발견되지 않습니다. 운이 좋으면 100 라운드 전부 통과할 수도 있습니다.
     *    동시성 버그를 테스트로 잡으려 하지 말고, 애초에 경합 구간이 생기지 않는
     *    구조(제약 조건 위임)를 쓰는 것이 답입니다.
     *
     * ③ INSERT-first 로 바꾸면 같은 실험에서 0 / 100 입니다.
     *    라운드 수를 10,000 으로 올려도 0 입니다. 확률이 낮아진 게 아니라
     *    구조적으로 불가능해진 것입니다.
     *
     * ④ CountDownLatch 가 두 개인 이유: start 는 출발선을 맞추고(경합 확률을 높이고),
     *    done 은 두 스레드가 다 끝난 뒤에 재고를 재야 하기 때문입니다.
     *    done 없이 바로 조회하면 아직 차감 안 된 상태를 읽어 오탐이 납니다.
     */
    @Component
    @Profile("sol13-q2")
    public static class Q2RaceCounter implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Q2RaceCounter.class);
        private static final int ROUNDS = 100;

        private final JdbcTemplate jdbc;
        private final AtomicInteger duplicateDeductions = new AtomicInteger();

        public Q2RaceCounter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);

            for (int round = 1; round <= ROUNDS; round++) {
                String messageId = "SOL2-" + round;
                jdbc.update("UPDATE inventory SET quantity = 1000 WHERE sku = 'SKU-002'");

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                for (int t = 0; t < 2; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            wrongDeduct(messageId);
                        } catch (Exception ignored) {
                            // 재현이 목적
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await();

                Integer qty = jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE sku = 'SKU-002'", Integer.class);
                if (qty != null && qty < 997) {
                    duplicateDeductions.incrementAndGet();
                }
            }
            pool.shutdown();
            log.warn("duplicate deductions: {} / {}", duplicateDeductions.get(), ROUNDS);
        }

        private void wrongDeduct(String messageId) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_message WHERE message_id = ?",
                    Integer.class, messageId);
            if (cnt != null && cnt > 0) {
                return;
            }
            jdbc.update("UPDATE inventory SET quantity = quantity - 3 WHERE sku = 'SKU-002'");
            try {
                jdbc.update("INSERT INTO processed_message (message_id, consumer_group) VALUES (?, 'sol2')",
                        messageId);
            } catch (DuplicateKeyException ignored) {
                // 이미 늦음
            }
        }
    }

    /* =====================================================================
     * 정답 3 — Transactional Outbox
     * =====================================================================
     *
     * 왜 이 답인가
     *
     * ① Q3OrderService 에 KafkaTemplate 이 주입돼 있지 않습니다. 이것이 패턴의 본질입니다.
     *    "트랜잭션 안에서 Kafka 를 부르되 실패하면 롤백"은 해법이 아닙니다.
     *    send 가 성공한 뒤 DB 커밋이 실패하면 되돌릴 수 없기 때문입니다.
     *    아예 부르지 않는 것만이 답입니다.
     *
     * ② 검증은 Kafka 를 내린 상태에서 합니다.
     *      docker compose stop kafka
     *      curl -s -XPOST localhost:8080/orders/1
     *    결과:
     *      INFO 14107 --- [nio-8080-exec-1] c.e.o.s13.Solution$Q3OrderService : order ORD-0001 created
     *      → HTTP 200, outbox_event 에 published_at IS NULL 로 1행
     *    Kafka 를 다시 켜면 릴레이가 밀린 것을 알아서 발행합니다.
     *      INFO 14107 --- [   scheduling-1] c.e.o.s13.Solution$Q3Relay : relayed 1 events (id 1..1)
     *
     * ③ send(rec).join() 의 join() 을 빼면 안 됩니다.
     *    Step 02 에서 봤듯 send 는 예외 없이 리턴합니다. 확인 없이 published_at 을 갱신하면
     *    "발행 안 된 이벤트가 발행됨으로 표시"되어 영원히 사라집니다.
     *    릴레이는 처리량보다 정확성이 중요한 자리이므로 join() 비용을 감수합니다.
     *
     * ④ @Transactional 이 relay() 전체를 감싸는 이유는 SELECT ... FOR UPDATE 의 잠금이
     *    트랜잭션이 끝날 때까지 유지되어야 하기 때문입니다. 트랜잭션이 없으면 자동 커밋으로
     *    SELECT 직후 잠금이 풀려 SKIP LOCKED 가 무의미해집니다.
     *
     * ⑤ 메시지 ID 를 outbox 행 PK("OBX-" + id)로 잡은 이유는 재발행해도 값이 안 바뀌기
     *    때문입니다. topic-partition-offset 을 썼다면 재발행 시 오프셋이 달라져
     *    같은 사건이 다른 ID 가 되고 멱등성이 통째로 무너집니다.
     */
    @Component
    @Profile("sol13-q3")
    public static class Q3OrderService {

        private static final Logger log = LoggerFactory.getLogger(Q3OrderService.class);

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        public Q3OrderService(JdbcTemplate jdbc, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.mapper = mapper;
        }

        @Transactional
        public String createOrder(OrderCreated e) {
            jdbc.update("INSERT INTO orders (order_id, customer_id, amount, status) VALUES (?,?,?, 'CREATED')",
                    e.orderId(), e.customerId(), e.amount());
            try {
                jdbc.update("INSERT INTO outbox_event (aggregate_id, event_type, payload) VALUES (?,?,?)",
                        e.orderId(), "OrderCreated", mapper.writeValueAsString(e));
            } catch (Exception ex) {
                throw new IllegalStateException("cannot stage outbox for " + e.orderId(), ex);
            }
            log.info("order {} created (outbox staged)", e.orderId());
            return e.orderId();
        }
    }

    @Configuration
    @EnableScheduling
    @Profile("sol13-q3")
    public static class Q3Relay {

        private static final Logger log = LoggerFactory.getLogger(Q3Relay.class);

        private static final String PICK = """
                SELECT id, aggregate_id, event_type, payload
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """;

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final KafkaTemplate<String, Object> template;

        public Q3Relay(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String, Object> template) {
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.template = template;
        }

        @Scheduled(fixedDelay = 500)
        @Transactional
        public void relay() {
            List<Object[]> rows = jdbc.query(PICK, OUTBOX_MAPPER);
            if (rows.isEmpty()) {
                return;
            }
            for (Object[] row : rows) {
                long id = (Long) row[0];
                String aggregateId = (String) row[1];
                String eventType = (String) row[2];
                OrderCreated event = readPayload((String) row[3]);

                ProducerRecord<String, Object> rec =
                        new ProducerRecord<>(TOPIC_OUTBOX, null, aggregateId, event);
                rec.headers().add(HDR_MESSAGE_ID, ("OBX-" + id).getBytes(StandardCharsets.UTF_8));
                rec.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));

                template.send(rec).join();
                jdbc.update("UPDATE outbox_event SET published_at = NOW(3) WHERE id = ?", id);
            }
            log.info("relayed {} events (id {}..{})",
                    rows.size(), rows.get(0)[0], rows.get(rows.size() - 1)[0]);
        }

        private OrderCreated readPayload(String json) {
            try {
                return mapper.readValue(json, OrderCreated.class);
            } catch (Exception ex) {
                throw new IllegalStateException("bad outbox payload", ex);
            }
        }
    }

    /* =====================================================================
     * 정답 4 — SKIP LOCKED 유무 실측
     * =====================================================================
     *
     * 300건을 두 인스턴스가 릴레이했을 때 토픽에 쌓인 메시지 수
     *
     *   | 잠금 방식                | 토픽 메시지 수 | 중복 | 300건 소요 | 비고                    |
     *   |--------------------------|---------------:|-----:|-----------:|-------------------------|
     *   | 잠금 없음                |            573 |  273 |      1.9 s | 전량 중복 위험          |
     *   | FOR UPDATE (SKIP 없음)   |            300 |    0 |      4.1 s | B 가 A 를 계속 기다림   |
     *   | FOR UPDATE SKIP LOCKED   |            300 |    0 |      2.0 s | 중복 없이 병렬          |
     *
     * 왜 이 답인가
     *
     * ① 잠금이 없으면 두 트랜잭션이 같은 100행을 각자 SELECT 합니다.
     *    둘 다 published_at IS NULL 을 보고, 둘 다 발행하고, 둘 다 UPDATE 합니다.
     *    UPDATE 는 나중 것이 이겨서 DB 상으로는 멀쩡해 보입니다.
     *    중복은 토픽에만 남습니다 — DB 만 봐서는 절대 발견 못 합니다.
     *    573 이 600 이 아닌 이유는 일부 배치에서 타이밍이 어긋나 한쪽만 집었기 때문입니다.
     *
     * ② FOR UPDATE 만 쓰면 중복은 사라지지만 B 가 A 의 커밋을 기다립니다.
     *    직렬화되므로 인스턴스를 늘려도 처리량이 안 늘고, 오히려 락 대기 때문에 느려집니다.
     *    행이 많고 발행이 느리면 innodb_lock_wait_timeout(기본 50초)에 걸립니다.
     *
     * ③ SKIP LOCKED 는 "잠긴 행은 건너뛰고 다음 것을 집으라"는 뜻입니다.
     *    A 가 1~100 을 잠그면 B 는 기다리지 않고 101~200 을 집습니다.
     *    대가는 인스턴스 간 발행 순서가 뒤섞이는 것입니다.
     *    같은 aggregate_id 의 이벤트가 서로 다른 인스턴스로 갈라지면 순서가 깨집니다.
     *    엄격한 순서가 필요하면 릴레이를 단일 인스턴스로 두거나(리더 선출),
     *    aggregate_id 해시로 릴레이를 샤딩해야 합니다.
     *
     * ④ 이 실험은 "중복이 안 나는 것처럼 보이는" 함정을 포함합니다.
     *    한 인스턴스로만 돌리면 잠금이 없어도 300건이 나옵니다.
     *    로컬에서 한 대로 테스트하고 운영에서 세 대로 띄우는 순간 터집니다.
     */
    @Configuration
    @EnableScheduling
    @Profile("sol13-q4")
    public static class Q4Relay {

        private static final Logger log = LoggerFactory.getLogger(Q4Relay.class);

        /** true 로 바꿔서 두 번 측정하고 비교하세요. */
        private static final boolean USE_SKIP_LOCKED = false;

        private static final String BASE = """
                SELECT id, aggregate_id, event_type, payload
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT 50
                """;

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final KafkaTemplate<String, Object> template;

        public Q4Relay(JdbcTemplate jdbc, ObjectMapper mapper, KafkaTemplate<String, Object> template) {
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.template = template;
        }

        @Scheduled(fixedDelay = 200)
        @Transactional
        public void relay() {
            String sql = USE_SKIP_LOCKED ? BASE + " FOR UPDATE SKIP LOCKED" : BASE;
            List<Object[]> rows = jdbc.query(sql, OUTBOX_MAPPER);
            if (rows.isEmpty()) {
                return;
            }
            for (Object[] row : rows) {
                long id = (Long) row[0];
                OrderCreated event = read((String) row[3]);
                ProducerRecord<String, Object> rec =
                        new ProducerRecord<>(TOPIC_OUTBOX, null, (String) row[1], event);
                rec.headers().add(HDR_MESSAGE_ID, ("OBX-" + id).getBytes(StandardCharsets.UTF_8));
                template.send(rec).join();
                jdbc.update("UPDATE outbox_event SET published_at = NOW(3) WHERE id = ?", id);
            }
            log.info("relayed {} events (id {}..{}) skipLocked={}",
                    rows.size(), rows.get(0)[0], rows.get(rows.size() - 1)[0], USE_SKIP_LOCKED);
        }

        private OrderCreated read(String json) {
            try {
                return mapper.readValue(json, OrderCreated.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Component
    @Profile("sol13-q4")
    public static class Q4Seeder implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Q4Seeder.class);

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        public Q4Seeder(JdbcTemplate jdbc, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.mapper = mapper;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
            if (existing != null && existing > 0) {
                log.info("outbox already seeded ({} rows) — skip", existing);
                return;
            }
            for (int i = 1; i <= 300; i++) {
                OrderCreated e = OrderCreated.of(i);
                jdbc.update("INSERT INTO outbox_event (aggregate_id, event_type, payload) VALUES (?,?,?)",
                        e.orderId(), "OrderCreated", mapper.writeValueAsString(e));
            }
            log.info("seeded 300 outbox rows");
        }
    }

    /* =====================================================================
     * 정답 5 — 순서 보장이 깨지는 조각
     * =====================================================================
     *
     *   깨지는 것 = (b), (c), (e)
     *
     * (a) concurrency: 3  →  ✅ 안전
     *     Spring 은 파티션 단위로 컨슈머 스레드를 배정합니다. 한 파티션은 한 스레드가
     *     전담하므로 파티션 내부 순서가 유지됩니다. 키가 orderId 이므로 같은 주문은
     *     항상 같은 파티션 = 항상 같은 스레드입니다.
     *     ⚠️ 단, concurrency 를 파티션 수보다 크게 잡으면 남는 스레드가 경고 없이
     *        놉니다(Step 03). 순서 문제는 아니지만 함께 기억하세요.
     *
     * (b) 키 없이 발행  →  ❌ 깨짐
     *     키가 null 이면 Kafka 3.3+ 의 기본 파티셔너는 sticky 방식으로 배치마다
     *     파티션을 바꿉니다. 같은 주문의 이벤트가 서로 다른 파티션으로 흩어지므로
     *     "파티션 내 순서 보장"이 아무 의미가 없어집니다.
     *     이것이 가장 흔하고 가장 발견하기 어려운 실수입니다. 에러가 전혀 없습니다.
     *
     * (c) enable.idempotence=false + retries=3 + max.in.flight=5  →  ❌ 깨짐
     *     배치 1 이 실패해 재전송되는 동안 이미 날아간 배치 2 가 먼저 기록됩니다.
     *     결과적으로 v1 이 v2, v3 뒤에 도착합니다.
     *     enable.idempotence=true 로 두면 프로듀서가 시퀀스 번호를 붙이고
     *     브로커가 순서를 복원해 줍니다(max.in.flight <= 5 까지 안전).
     *     idempotence 는 "중복 제거" 기능으로만 알려져 있지만, 실은 순서 보장 장치입니다.
     *
     * (d) DefaultErrorHandler + FixedBackOff  →  ✅ 안전
     *     블로킹 재시도는 같은 스레드가 같은 자리에서 다시 시도합니다.
     *     그 파티션의 뒤 메시지는 전부 대기하므로 순서가 절대 안 깨집니다.
     *     대가는 파티션 정지입니다(Step 07). 순서를 지키려면 이쪽을 골라야 합니다.
     *     @RetryableTopic 은 반대로 순서를 포기하고 파티션을 살립니다(Step 08).
     *
     * (e) 리스너에서 runAsync 후 미대기  →  ❌ 깨짐 (그리고 유실까지)
     *     순서가 깨지는 것보다 심각한 일이 함께 일어납니다.
     *     리스너가 즉시 리턴하므로 Spring 은 "처리 성공"으로 보고 오프셋을 커밋합니다.
     *     워커에서 예외가 나도 DefaultErrorHandler 는 그것을 볼 수 없습니다.
     *     재시도도 DLT 도 없고, LAG 은 0 으로 표시됩니다. 이 코스에서 가장 나쁜 조합입니다.
     *     반드시 CompletableFuture.allOf(...).join() 으로 대기한 뒤 리턴하세요.
     */

    /* =====================================================================
     * 정답 6 — DLT 재처리 도구
     * =====================================================================
     *
     * 왜 이 답인가
     *
     * ① messageId 헤더를 그대로 복사하는 것이 이 문제의 전부입니다.
     *    재처리 시 새 UUID 를 만들어 붙이면 컨슈머 입장에서는 "처음 보는 메시지"가 되어
     *    이미 처리한 건이라도 다시 처리됩니다. 재고가 두 번 빠집니다.
     *    DLT 재처리는 성격상 여러 번 돌리게 되므로(재고 보충 후 다시, 버그 수정 후 다시)
     *    멱등 키 보존이 특히 중요합니다.
     *
     * ② kafka_dlt-* 헤더는 버립니다.
     *    DeadLetterPublishingRecoverer 는 원본 topic/partition/offset/예외를 헤더로 붙입니다.
     *    이걸 그대로 달고 재발행하면, 재처리한 메시지가 다시 실패해 DLT 로 갈 때
     *    Recoverer 가 헤더를 덮어쓰지 않고 덧붙이는 경우가 있어 원본 정보를 잃습니다.
     *    "어디서 온 메시지인가"가 흐려지면 DLT 의 존재 의의가 사라집니다.
     *
     * ③ autoStartup = "false" 로 두는 이유는 재처리가 위험한 작업이기 때문입니다.
     *    앱을 재시작할 때마다 DLT 가 자동으로 흘러 들어가면, 원인을 고치기 전에
     *    같은 실패를 반복하며 DLT 를 무한 순환합니다.
     *    사람이 명시적으로 켜야 합니다. registry.getListenerContainer(id).start() (Step 10).
     *
     * ④ send().join() 으로 확인한 뒤에야 다음 레코드로 넘어갑니다.
     *    재처리 도중 실패하면 그 자리에서 멈춰야 합니다. 어디까지 재처리했는지
     *    모르는 상태가 가장 나쁩니다.
     *
     * ⑤ 재처리 결과 확인
     *    INFO 14107 --- [proces-0-C-1] c.e.o.s13.Solution$Q6DltReprocessor : reprocessed messageId=OBX-11 key=ORD-0003 → orders.outbox
     *    INFO 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx             : deducted SKU-001 -4 (remaining=996)
     *    한 번 더 돌리면:
     *    WARN 14107 --- [ntainer#0-2-C-1] c.e.o.s13.InventoryTx             : duplicate messageId=OBX-11 group=s13-inventory — skipped
     *    재고는 996 그대로입니다. messageId 를 보존했기 때문입니다.
     */
    @Component
    @Profile("sol13-q6")
    public static class Q6DltReprocessor {

        private static final Logger log = LoggerFactory.getLogger(Q6DltReprocessor.class);

        private final KafkaTemplate<String, Object> template;
        private final KafkaListenerEndpointRegistry registry;

        public Q6DltReprocessor(KafkaTemplate<String, Object> template,
                                KafkaListenerEndpointRegistry registry) {
            this.template = template;
            this.registry = registry;
        }

        @KafkaListener(id = "dltReprocessor", topics = TOPIC_OUTBOX_DLT,
                groupId = "s13-sol-dlt-reprocess", autoStartup = "false")
        public void reprocess(ConsumerRecord<String, OrderCreated> rec) {
            ProducerRecord<String, Object> out =
                    new ProducerRecord<>(TOPIC_OUTBOX, null, rec.key(), rec.value());

            String messageId = null;
            for (Header h : rec.headers()) {
                if (h.key().startsWith("kafka_dlt-")) {
                    continue;                                  // ② 원본 추적 헤더는 버림
                }
                out.headers().add(h.key(), h.value());         // ① messageId 포함 나머지는 보존
                if (HDR_MESSAGE_ID.equals(h.key())) {
                    messageId = new String(h.value(), StandardCharsets.UTF_8);
                }
            }
            if (messageId == null) {
                throw new IllegalStateException(
                        "DLT record has no messageId — reprocessing would break idempotency");
            }

            template.send(out).join();                          // ④ 확인 후 진행
            log.info("reprocessed messageId={} key={} → {}", messageId, rec.key(), TOPIC_OUTBOX);
        }

        /** ③ 사람이 명시적으로 켭니다. Actuator 엔드포인트나 운영 콘솔에 연결하세요. */
        public void startReprocessing() {
            MessageListenerContainer c = registry.getListenerContainer("dltReprocessor");
            if (c != null && !c.isRunning()) {
                c.start();
                log.warn("DLT reprocessing STARTED — 원인을 고친 뒤에만 켜세요");
            }
        }

        public void stopReprocessing() {
            MessageListenerContainer c = registry.getListenerContainer("dltReprocessor");
            if (c != null && c.isRunning()) {
                c.stop();
                log.warn("DLT reprocessing STOPPED");
            }
        }
    }
}
