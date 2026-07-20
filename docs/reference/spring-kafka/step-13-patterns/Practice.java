package com.example.order.step13;

/*
 * ============================================================================
 * Step 13 — 실전 패턴과 최종 프로젝트 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step13/Practice.java
 *
 * 사전 준비
 *   1) build.gradle 에 웹 스타터 추가 (13-8 의 REST 엔드포인트에 필요)
 *        implementation 'org.springframework.boot:spring-boot-starter-web'
 *   2) 토픽 생성
 *        kt --create --topic orders.outbox     --partitions 3 --replication-factor 1
 *        kt --create --topic orders.outbox.DLT --partitions 3 --replication-factor 1
 *   3) MySQL 별칭
 *        alias mq='docker exec -i learn-kafka-mysql mysql -ulearner -plearn1234 orderdb -t -e'
 *
 * 실행 (보조 프로필을 하나만 함께 켭니다. 예제끼리 서로 간섭합니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,step13-idem'
 *       → [13-2] 멱등 컨슈머 + SELECT-then-INSERT 경합 재현(RaceDemo)
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,step13-split'
 *       → [13-3] ★함정★ 이력과 비즈니스가 다른 트랜잭션이면 메시지가 영구 스킵된다
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,step13-outbox'
 *       → [13-5] Transactional Outbox 전체 (OrderService + OutboxRelay)
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,step13-async'
 *       → [13-7] ★함정★ @Async 리스너가 처리 전에 커밋한다 vs 레인 워커
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,step13-order'
 *       → [13-8] 최종 프로젝트. REST + Outbox + 멱등 재고 + DLT + 알림 팬아웃
 *
 * 실행 전 DB 초기화 (매번 하세요. 안 하면 이력이 남아 전부 스킵됩니다)
 *   mq "TRUNCATE processed_message; TRUNCATE outbox_event; DELETE FROM orders;
 *       UPDATE inventory SET quantity = 1000;"
 *
 * 실행 중 확인할 CLI
 *   mq  "SELECT sku, quantity FROM inventory ORDER BY sku;"
 *   mq  "SELECT id, aggregate_id, created_at, published_at FROM outbox_event ORDER BY id;"
 *   mq  "SELECT message_id, consumer_group FROM processed_message ORDER BY processed_at;"
 *   kcg --describe --group s13-inventory
 *   kcc --topic orders.outbox --from-beginning --property print.key=true --property print.headers=true
 *
 * 커밋 시점을 눈으로 보려면 (13-7 에 필수)
 *   logging.level.org.springframework.kafka.listener=DEBUG
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class Practice {

    private Practice() {
    }

    /* =====================================================================
     * 공통 — 상수, SQL, 예외, 행 매퍼
     * ===================================================================== */

    /** 프로듀서가 만들어 실어 보내는 메시지 식별자 헤더. 13-2 의 후보 ③. */
    public static final String HDR_MESSAGE_ID = "messageId";
    public static final String HDR_EVENT_TYPE = "eventType";

    public static final String TOPIC_OUTBOX = "orders.outbox";
    public static final String TOPIC_OUTBOX_DLT = "orders.outbox.DLT";

    public static final String GROUP_INVENTORY = "s13-inventory";
    public static final String GROUP_NOTIFICATION = "s13-notification";

    /** 이 스텝의 모든 SQL. 먼저 읽고 나머지 코드를 보면 빠릅니다. */
    public static final class Sql {
        private Sql() {
        }

        /** 멱등 판정을 DB 제약에 위임한다. SELECT 가 없다는 점이 핵심. */
        public static final String INSERT_MARK = """
                INSERT INTO processed_message (message_id, consumer_group)
                VALUES (?, ?)
                """;

        /** ⚠️ 13-2 의 틀린 방식. 경합 재현용으로만 씁니다. */
        public static final String COUNT_MARK = """
                SELECT COUNT(*) FROM processed_message WHERE message_id = ?
                """;

        /**
         * quantity >= ? 조건이 재고 부족 검출의 전부입니다.
         * 조건에 안 맞으면 updated == 0 이 되고, 거기서 예외를 던져 트랜잭션을 롤백합니다.
         */
        public static final String DEDUCT = """
                UPDATE inventory SET quantity = quantity - ?
                WHERE sku = ? AND quantity >= ?
                """;

        public static final String INSERT_ORDER = """
                INSERT INTO orders (order_id, customer_id, amount, status)
                VALUES (?, ?, ?, 'CREATED')
                """;

        public static final String INSERT_OUTBOX = """
                INSERT INTO outbox_event (aggregate_id, event_type, payload)
                VALUES (?, ?, ?)
                """;

        /**
         * SKIP LOCKED — 다른 트랜잭션이 잠근 행은 기다리지 말고 건너뛴다.
         * 이것이 없으면 릴레이 인스턴스 2대가 같은 행을 집어 전량 중복 발행합니다.
         */
        public static final String PICK_OUTBOX = """
                SELECT id, aggregate_id, event_type, payload
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """;

        public static final String MARK_PUBLISHED = """
                UPDATE outbox_event SET published_at = NOW(3) WHERE id = ?
                """;

        public static final String PURGE_MARKS = """
                DELETE FROM processed_message WHERE processed_at < ? LIMIT 5000
                """;

        public static final String OUTBOX_BACKLOG = """
                SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL
                """;
    }

    /** 재고 부족. 재시도해도 소용없지만, 재고 보충 가능성이 있으므로 재시도 대상으로 둡니다. */
    public static class OutOfStockException extends RuntimeException {
        public OutOfStockException(String sku, int need, int have) {
            super("out of stock %s need=%d have=%d".formatted(sku, need, have));
        }
    }

    public record OutboxRow(long id, String aggregateId, String eventType, String payload) {
    }

    public static final RowMapper<OutboxRow> OUTBOX_ROW_MAPPER = (rs, n) -> new OutboxRow(
            rs.getLong("id"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"));

    /**
     * 멱등 키를 만드는 유일한 자리.
     * ⚠️ processed_message 의 PK 가 message_id 단독이므로 그룹명을 접두사로 붙입니다.
     *    안 붙이면 재고 그룹이 먼저 INSERT 한 순간 알림 그룹이 전부 스킵됩니다(13-2 함정).
     */
    public static String markKey(String group, String messageId) {
        return group + ":" + messageId;
    }

    /* =====================================================================
     * [13-2] 멱등 컨슈머 — 처리 이력 테이블
     * ===================================================================== */

    /**
     * ★ 이력 INSERT 와 재고 UPDATE 가 반드시 같은 트랜잭션이어야 합니다(13-3).
     * 그래서 리스너와 분리된 별도 빈으로 둡니다. 자기 호출(this.method())은 프록시를 안 타서
     * @Transactional 이 통째로 무시됩니다.
     */
    @Component
    public static class InventoryTx {

        private static final Logger log = LoggerFactory.getLogger(InventoryTx.class);

        private final JdbcTemplate jdbc;

        public InventoryTx(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        public void consumeOnce(String messageId, String group, OrderCreated e) {
            try {
                jdbc.update(Sql.INSERT_MARK, markKey(group, messageId), group);
                log.info("marked {} (tx started)", messageId);
            } catch (DuplicateKeyException dup) {
                // 롤백이 아니라 정상 종료입니다. 이미 처리한 메시지이므로 할 일이 없습니다.
                log.warn("duplicate messageId={} group={} — skipped", messageId, group);
                return;
            }

            int updated = jdbc.update(Sql.DEDUCT, e.quantity(), e.sku(), e.quantity());
            if (updated == 0) {
                Integer have = jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE sku = ?", Integer.class, e.sku());
                log.warn("out of stock {} need={} have={} → rollback", e.sku(), e.quantity(), have);
                // ★ 여기서 던지면 위의 INSERT_MARK 도 함께 롤백됩니다.
                //   그래야 재시도가 정상 동작하고, 소진 후 DLT 로 갑니다.
                throw new OutOfStockException(e.sku(), e.quantity(), have == null ? -1 : have);
            }

            Integer remaining = jdbc.queryForObject(
                    "SELECT quantity FROM inventory WHERE sku = ?", Integer.class, e.sku());
            log.info("deducted {} -{} (remaining={})", e.sku(), e.quantity(), remaining);
        }
    }

    @Configuration
    @Profile("step13-idem")
    public static class IdempotentDemo {

        private static final Logger log = LoggerFactory.getLogger(IdempotentDemo.class);

        private final InventoryTx tx;

        public IdempotentDemo(InventoryTx tx) {
            this.tx = tx;
        }

        // [13-2] 멱등 컨슈머. 리스너는 메시지를 풀어 넘기기만 하고, 트랜잭션은 InventoryTx 가 엽니다.
        @KafkaListener(topics = TOPIC_OUTBOX, groupId = GROUP_INVENTORY)
        public void onMessage(OrderCreated e,
                              @Header(name = HDR_MESSAGE_ID, required = false) String messageId,
                              @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                              @Header(KafkaHeaders.OFFSET) long offset) {
            // 헤더가 없으면 멱등성이 성립하지 않습니다. 조용히 넘어가지 말고 실패시킵니다.
            if (messageId == null) {
                throw new IllegalStateException(
                        "missing %s header at %s@%d".formatted(HDR_MESSAGE_ID, topic, offset));
            }
            log.info("consume messageId={} order={} sku={} qty={}",
                    messageId, e.orderId(), e.sku(), e.quantity());
            tx.consumeOnce(messageId, GROUP_INVENTORY, e);
        }
    }

    /**
     * [13-2] ⚠️ SELECT-then-INSERT 의 경합 재현.
     * Kafka 를 전혀 쓰지 않습니다. 두 스레드를 CountDownLatch 로 동시에 출발시켜
     * "확인하고 하기" 패턴의 빈 구간만 순수하게 드러냅니다.
     */
    @Component
    @Profile("step13-idem")
    public static class RaceDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(RaceDemo.class);
        private static final int ROUNDS = 100;

        private final JdbcTemplate jdbc;

        public RaceDemo(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicInteger doubleDeduct = new AtomicInteger();

            for (int round = 1; round <= ROUNDS; round++) {
                String messageId = "RACE-" + round;
                jdbc.update("UPDATE inventory SET quantity = 1000 WHERE sku = 'SKU-002'");

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                for (String tag : new String[]{"A", "B"}) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            wrongIdempotentDeduct(tag, messageId);
                        } catch (Exception ignored) {
                            // 재현이 목적이므로 예외는 무시합니다
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await();

                Integer qty = jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE sku = 'SKU-002'", Integer.class);
                if (qty != null && qty < 997) {          // 정상이면 997 (1회 차감)
                    doubleDeduct.incrementAndGet();
                }
            }
            pool.shutdown();

            log.warn("SELECT-then-INSERT: {} / {} rounds double-deducted", doubleDeduct.get(), ROUNDS);
            log.warn("→ 실행할 때마다 숫자가 달라집니다. 그 사실 자체가 이 버그의 성질입니다.");
        }

        /** ❌ 틀린 코드. SELECT 와 INSERT 사이가 비어 있습니다. */
        private void wrongIdempotentDeduct(String tag, String messageId) {
            Integer cnt = jdbc.queryForObject(Sql.COUNT_MARK, Integer.class, messageId);
            log.info("[{}] select count={}", tag, cnt);
            if (cnt != null && cnt > 0) {
                log.warn("[{}] duplicate — skipped", tag);
                return;
            }
            jdbc.update(Sql.DEDUCT, 3, "SKU-002", 3);
            log.info("[{}] deducted SKU-002 -3", tag);
            try {
                jdbc.update(Sql.INSERT_MARK, messageId, "race");
                log.info("[{}] insert ok", tag);
            } catch (DuplicateKeyException dup) {
                log.warn("[{}] insert duplicate — but stock already deducted twice", tag);
            }
        }
    }

    /* =====================================================================
     * [13-3] ⚠️ 함정 — 이력과 비즈니스가 다른 트랜잭션이면 소용없다
     * ===================================================================== */

    @Component
    @Profile("step13-split")
    public static class SplitTxDemo {

        private static final Logger log = LoggerFactory.getLogger(SplitTxDemo.class);

        /** 이 오프셋을 처리할 때 차감 직전에 죽입니다. -1 이면 비활성. */
        private static final long CRASH_AT_OFFSET = 0L;

        private final JdbcTemplate jdbc;

        public SplitTxDemo(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @KafkaListener(topics = TOPIC_OUTBOX, groupId = "s13-split")
        public void onMessage(OrderCreated e,
                              @Header(HDR_MESSAGE_ID) String messageId,
                              @Header(KafkaHeaders.OFFSET) long offset) {
            // ❌ 트랜잭션 #1 — 여기서 커밋됩니다
            if (!markInSeparateTx(messageId)) {
                log.warn("duplicate messageId={} group=s13-split — skipped", messageId);
                return;
            }
            log.info("marked {} (tx#1 committed)", messageId);

            if (offset == CRASH_AT_OFFSET) {
                log.error("simulated crash before deduct");
                Runtime.getRuntime().halt(1);            // 셧다운 훅 없이 즉사
            }

            // ❌ 트랜잭션 #2 — 여기 도달 못 하면 이력만 남습니다
            deductInSeparateTx(e);
        }

        @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        public boolean markInSeparateTx(String messageId) {
            try {
                jdbc.update(Sql.INSERT_MARK, markKey("s13-split", messageId), "s13-split");
                return true;
            } catch (DuplicateKeyException dup) {
                return false;
            }
        }

        @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        public void deductInSeparateTx(OrderCreated e) {
            jdbc.update(Sql.DEDUCT, e.quantity(), e.sku(), e.quantity());
            log.info("deducted {} -{}", e.sku(), e.quantity());
        }
    }

    /* =====================================================================
     * [13-4] 처리 이력 테이블 청소
     * ===================================================================== */

    @Component
    @Profile({"step13-idem", "step13-order"})
    public static class ProcessedMessagePurger {

        private static final Logger log = LoggerFactory.getLogger(ProcessedMessagePurger.class);

        /** 보관 기간 ≥ 토픽 retention(7일). 재발행·DLT 재처리 여유까지 감안해 2배로 잡습니다. */
        private static final int RETENTION_DAYS = 14;

        private final JdbcTemplate jdbc;

        public ProcessedMessagePurger(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Scheduled(cron = "0 10 3 * * *")
        public void purge() {
            Timestamp cutoff = Timestamp.from(
                    Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            int total = 0;
            int deleted;
            do {
                // LIMIT 로 쪼갭니다. 한 번에 수천만 행을 지우면 undo 로그가 폭발하고
                // 복제 지연이 생깁니다.
                deleted = jdbc.update(Sql.PURGE_MARKS, cutoff);
                total += deleted;
            } while (deleted == 5000);
            log.info("purged {} rows from processed_message", total);
        }
    }

    /* =====================================================================
     * [13-5] Transactional Outbox
     * ===================================================================== */

    /**
     * ★ 이 클래스에 KafkaTemplate 이 주입되지 않은 것이 패턴의 전부입니다.
     * Kafka 가 죽어 있어도 주문 생성은 성공합니다.
     */
    @Component
    public static class OrderService {

        private static final Logger log = LoggerFactory.getLogger(OrderService.class);

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        public OrderService(JdbcTemplate jdbc, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.mapper = mapper;
        }

        @Transactional
        public String createOrder(OrderCreated e) {
            jdbc.update(Sql.INSERT_ORDER, e.orderId(), e.customerId(), e.amount());
            jdbc.update(Sql.INSERT_OUTBOX, e.orderId(), "OrderCreated", toJson(e));
            log.info("order {} created (outbox staged)", e.orderId());
            return e.orderId();
        }

        private String toJson(OrderCreated e) {
            try {
                return mapper.writeValueAsString(e);
            } catch (Exception ex) {
                throw new IllegalStateException("cannot serialize " + e.orderId(), ex);
            }
        }
    }

    /** 릴레이를 켜고 끄는 플래그. 검증 시나리오 ④ 에서 씁니다. */
    @Component
    public static class RelayAdmin {

        private static final Logger log = LoggerFactory.getLogger(RelayAdmin.class);
        private final AtomicBoolean running = new AtomicBoolean(true);

        public boolean isRunning() {
            return running.get();
        }

        public void stop() {
            running.set(false);
            log.info("relay STOPPED");
        }

        public void start() {
            running.set(true);
            log.info("relay STARTED");
        }
    }

    @Configuration
    @EnableScheduling
    @Profile({"step13-outbox", "step13-order"})
    public static class OutboxRelay {

        private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

        /**
         * ★ 실험용. 이 id 를 발행한 직후, published_at 갱신 전에 프로세스를 죽입니다.
         * 재시작하면 그 이벤트가 다시 발행됩니다 = Outbox 는 at-least-once.
         * 평소에는 -1 로 두세요.
         */
        private static final long CRASH_AT_ID = -1L;

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final KafkaTemplate<String, Object> kafkaTemplate;
        private final RelayAdmin admin;

        public OutboxRelay(JdbcTemplate jdbc, ObjectMapper mapper,
                           KafkaTemplate<String, Object> kafkaTemplate, RelayAdmin admin) {
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.kafkaTemplate = kafkaTemplate;
            this.admin = admin;
        }

        @Scheduled(fixedDelay = 500)
        @Transactional                       // SELECT ... FOR UPDATE 부터 UPDATE 까지가 한 트랜잭션
        public void relay() {
            if (!admin.isRunning()) {
                return;
            }
            List<OutboxRow> rows = jdbc.query(Sql.PICK_OUTBOX, OUTBOX_ROW_MAPPER);
            if (rows.isEmpty()) {
                return;
            }

            for (OutboxRow row : rows) {
                OrderCreated event = fromJson(row.payload());

                ProducerRecord<String, Object> rec =
                        new ProducerRecord<>(TOPIC_OUTBOX, null, row.aggregateId(), event);
                // 메시지 ID = outbox 행 PK. 재발행해도 값이 안 바뀌므로 멱등 키로 적합합니다.
                rec.headers().add(HDR_MESSAGE_ID,
                        ("OBX-" + row.id()).getBytes(StandardCharsets.UTF_8));
                rec.headers().add(HDR_EVENT_TYPE,
                        row.eventType().getBytes(StandardCharsets.UTF_8));

                // ★ join() 필수. send 는 예외 없이 리턴하고 실패는 Future 안에만 있습니다(Step 02).
                //   확인 없이 published_at 을 갱신하면 발행 안 된 이벤트가 발행됨으로 표시됩니다.
                kafkaTemplate.send(rec).join();

                if (row.id() == CRASH_AT_ID) {
                    log.error("simulated crash after send, before published_at update (id={})", row.id());
                    Runtime.getRuntime().halt(1);
                }

                jdbc.update(Sql.MARK_PUBLISHED, row.id());
            }
            log.info("relayed {} events (id {}..{})",
                    rows.size(), rows.get(0).id(), rows.get(rows.size() - 1).id());
        }

        /**
         * Kafka 랙으로는 릴레이 장애가 안 보입니다. 이 게이지가 유일한 신호입니다(13-8).
         */
        @Scheduled(fixedDelay = 5000)
        public void reportBacklog() {
            Integer backlog = jdbc.queryForObject(Sql.OUTBOX_BACKLOG, Integer.class);
            if (backlog != null && backlog > 0) {
                log.info("outbox.backlog={}", backlog);
            }
        }

        private OrderCreated fromJson(String payload) {
            try {
                return mapper.readValue(payload, OrderCreated.class);
            } catch (Exception ex) {
                throw new IllegalStateException("cannot deserialize outbox payload", ex);
            }
        }
    }

    /* =====================================================================
     * [13-6] 순서 보장 관측
     * ===================================================================== */

    @Component
    @Profile("step13-outbox")
    public static class OrderingProbe {

        private static final Logger log = LoggerFactory.getLogger(OrderingProbe.class);

        @KafkaListener(topics = TOPIC_OUTBOX, groupId = "s13-ordering", concurrency = "1")
        public void onMessage(ConsumerRecord<String, OrderCreated> rec) {
            // 같은 키는 항상 같은 파티션이므로, 여기 도착 순서가 곧 발행 순서여야 합니다.
            log.info("received {} createdAt={} partition={} offset={}",
                    rec.key(), rec.value().createdAt(), rec.partition(), rec.offset());
        }
    }

    /* =====================================================================
     * [13-7] ⚠️ 함정 — @Async / 스레드 풀은 순서와 커밋을 동시에 깨뜨린다
     * ===================================================================== */

    @Component
    @Profile("step13-async")
    public static class AsyncBadListener {

        private static final Logger log = LoggerFactory.getLogger(AsyncBadListener.class);
        private final ExecutorService pool = Executors.newFixedThreadPool(4);

        // ❌ 절대 하면 안 되는 코드
        @KafkaListener(topics = TOPIC_OUTBOX, groupId = "s13-async-bad")
        public void onMessage(ConsumerRecord<String, OrderCreated> rec) {
            log.info("submitted {} (offset={})", rec.key(), rec.offset());
            pool.submit(() -> {
                try {
                    Thread.sleep((rec.offset() % 3) * 40L);   // 스케줄링 편차를 크게
                    if (rec.offset() % 7 == 2) {
                        throw new RuntimeException("downstream timeout");
                    }
                    log.info("processed {}", rec.key());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException re) {
                    // ★ 이 예외는 리스너 밖입니다. DefaultErrorHandler 가 절대 못 봅니다.
                    //   재시도도 DLT 도 안 일어나고, 오프셋은 이미 커밋됐습니다.
                    log.error("failed {}", rec.key(), re);
                }
            });
            // 여기서 즉시 리턴 → Spring 은 "처리 성공"으로 보고 커밋합니다
        }
    }

    /** ✅ 올바른 병렬화. 키 단위 레인 + 전원 완료 대기. */
    @Configuration
    @Profile("step13-async")
    public static class LaneWorkerConfig {

        private static final Logger log = LoggerFactory.getLogger(LaneWorkerConfig.class);
        private static final int WORKERS = 4;

        private final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);

        @KafkaListener(topics = TOPIC_OUTBOX, groupId = "s13-async-good",
                containerFactory = "batchAckFactory")
        public void onBatch(List<ConsumerRecord<String, OrderCreated>> records, Acknowledgment ack) {
            long t0 = System.currentTimeMillis();

            // 같은 키는 항상 같은 레인 → 키 안에서는 순서가 유지됩니다
            Map<Integer, List<ConsumerRecord<String, OrderCreated>>> lanes = records.stream()
                    .collect(Collectors.groupingBy(r -> Math.abs(r.key().hashCode()) % WORKERS));

            List<CompletableFuture<Void>> futures = lanes.values().stream()
                    .map(lane -> CompletableFuture.runAsync(() -> lane.forEach(this::handleOne), pool))
                    .toList();

            // ★ 이 join() 이 없으면 위의 AsyncBadListener 와 완전히 똑같아집니다
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            ack.acknowledge();
            log.info("batch={} lanes={} elapsed={}ms",
                    records.size(), lanes.size(), System.currentTimeMillis() - t0);
        }

        private void handleOne(ConsumerRecord<String, OrderCreated> rec) {
            try {
                Thread.sleep(10);                      // 처리 시간 흉내
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> batchAckFactory(
                ConsumerFactory<String, OrderCreated> cf) {
            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setBatchListener(true);
            f.setConcurrency(3);
            // 레인 하나가 느리면 배치 전체가 대기합니다. max.poll.interval.ms 를 넘기지 않도록
            // 배치 크기를 줄여 둡니다.
            f.getContainerProperties().setAckMode(
                    org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL);
            return f;
        }
    }

    /* =====================================================================
     * [13-8] 최종 프로젝트 — 주문 서비스 이벤트 연동
     * ===================================================================== */

    @RestController
    @Profile("step13-order")
    public static class OrderController {

        private final OrderService orderService;
        private final RelayAdmin relayAdmin;

        public OrderController(OrderService orderService, RelayAdmin relayAdmin) {
            this.orderService = orderService;
            this.relayAdmin = relayAdmin;
        }

        // Kafka 가 죽어 있어도 200 을 반환해야 합니다. 그게 Outbox 를 쓰는 이유입니다.
        @PostMapping("/orders/{seq}")
        public String create(@PathVariable int seq) {
            return orderService.createOrder(OrderCreated.of(seq));
        }

        @PostMapping("/admin/relay/stop")
        public String stopRelay() {
            relayAdmin.stop();
            return "stopped";
        }

        @PostMapping("/admin/relay/start")
        public String startRelay() {
            relayAdmin.start();
            return "started";
        }
    }

    @Configuration
    @Profile("step13-order")
    public static class FinalWiring {

        private static final Logger log = LoggerFactory.getLogger(FinalWiring.class);

        private final InventoryTx tx;

        public FinalWiring(InventoryTx tx) {
            this.tx = tx;
        }

        // 체크리스트 7 — 멱등 재고 컨슈머
        @KafkaListener(topics = TOPIC_OUTBOX, groupId = GROUP_INVENTORY)
        public void inventory(OrderCreated e, @Header(HDR_MESSAGE_ID) String messageId) {
            tx.consumeOnce(messageId, GROUP_INVENTORY, e);
        }

        // 체크리스트 9 — 팬아웃. groupId 가 다르므로 같은 메시지를 각자 받습니다.
        @KafkaListener(topics = TOPIC_OUTBOX, groupId = GROUP_NOTIFICATION)
        public void notifyCustomer(OrderCreated e, @Header(HDR_MESSAGE_ID) String messageId) {
            // 알림도 멱등해야 합니다. 그룹명이 다르므로 markKey 가 달라져 서로 간섭하지 않습니다.
            log.info("notify customer={} order={} messageId={}",
                    e.customerId(), e.orderId(), messageId);
        }

        // 체크리스트 8 — 3회 재시도 후 DLT
        @Bean
        public DefaultErrorHandler finalErrorHandler(KafkaTemplate<String, Object> template) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
            return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        }

        // DLT 관측. 재처리 도구는 연습문제 6번입니다.
        @KafkaListener(topics = TOPIC_OUTBOX_DLT, groupId = "s13-dlt-monitor")
        public void dlt(ConsumerRecord<String, OrderCreated> rec,
                        @Header(name = HDR_MESSAGE_ID, required = false) String messageId) {
            log.error("DLT messageId={} key={} partition={} offset={}",
                    messageId, rec.key(), rec.partition(), rec.offset());
        }
    }

    /** 최종 프로젝트를 CLI 없이 한 번에 돌려 보고 싶을 때. */
    @Component
    @Profile("step13-order")
    public static class SmokeRunner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(SmokeRunner.class);

        private final OrderService orderService;

        @Value("${app.smoke.orders:0}")
        private int howMany;

        public SmokeRunner(OrderService orderService) {
            this.orderService = orderService;
        }

        @Override
        public void run(ApplicationArguments args) {
            if (howMany <= 0) {
                log.info("smoke disabled. use: --app.smoke.orders=10");
                return;
            }
            for (int i = 1; i <= howMany; i++) {
                orderService.createOrder(OrderCreated.of(i));
            }
            log.info("smoke: created {} orders", howMany);
        }
    }
}
