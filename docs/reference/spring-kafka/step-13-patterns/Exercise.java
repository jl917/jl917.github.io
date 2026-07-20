package com.example.order.step13;

/*
 * ============================================================================
 * Step 13 — 실전 패턴과 최종 프로젝트 : Exercise (6문제)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step13/Exercise.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q1'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q2'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q3'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q4'
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q4 --server.port=8081'   ← 두 번째 인스턴스
 *   ./gradlew bootRun --args='--spring.profiles.active=step13,ex13-q6'
 *   (문제 5 는 코드를 실행하지 않습니다. 주석에 답을 적는 문제입니다.)
 *
 * ★ 매 실행 전 DB 초기화
 *   mq "TRUNCATE processed_message; TRUNCATE outbox_event; DELETE FROM orders;
 *       UPDATE inventory SET quantity = 1000;"
 *
 * ★ 문제 3, 4 는 Practice.java 의 OutboxRelay 빈과 충돌합니다.
 *   ex13-* 프로필만 켜면 step13-outbox / step13-order 는 꺼지므로 그대로 두면 됩니다.
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
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

public final class Exercise {

    private Exercise() {
    }

    static final String TOPIC_OUTBOX = "orders.outbox";
    static final String TOPIC_OUTBOX_DLT = "orders.outbox.DLT";
    static final String HDR_MESSAGE_ID = "messageId";

    /* =====================================================================
     * 문제 1. 멱등 컨슈머를 구현하고 "두 번 발행해도 한 번만 차감"을 검증하세요.
     *
     * 요구사항
     *   - processed_message 에 INSERT 를 먼저 시도하고, DuplicateKeyException 이면 스킵
     *   - 이력 INSERT 와 재고 UPDATE 는 반드시 같은 트랜잭션
     *   - 저장 키는 컨슈머 그룹을 구분할 수 있어야 함 (13-2 함정)
     *   - Publisher 가 같은 이벤트 10건을 두 번(총 20 메시지) 발행합니다.
     *     messageId 헤더는 orderId 기준으로 만들어 두 번 다 같은 값이 되게 하세요.
     * ===================================================================== */
    @Component
    @Profile("ex13-q1")
    public static class Q1IdempotentInventory {

        private static final Logger log = LoggerFactory.getLogger(Q1IdempotentInventory.class);
        static final String GROUP = "s13-ex-inventory";

        private final JdbcTemplate jdbc;

        public Q1IdempotentInventory(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @KafkaListener(topics = TOPIC_OUTBOX, groupId = GROUP)
        public void onMessage(OrderCreated e,
                              @org.springframework.messaging.handler.annotation.Header(HDR_MESSAGE_ID)
                              String messageId) {
            consumeOnce(messageId, e);
        }

        // 여기에 작성: @Transactional 을 붙이고, INSERT-first 멱등 처리 + 재고 차감을 구현하세요.
        //             (자기 호출이라 프록시를 안 타는 문제는 이 연습에서는 무시합니다.
        //              실무에서는 Practice 의 InventoryTx 처럼 별도 빈으로 빼세요.)
        public void consumeOnce(String messageId, OrderCreated e) {

        }

        // 확인:
        //   mq "SELECT sku, quantity FROM inventory ORDER BY sku;"
        //   → SKU-001=989, SKU-002=989, SKU-003=992  (20 메시지가 흘렀지만 30개만 차감)
        //   로그에 "duplicate messageId=... — skipped" 가 정확히 10줄
    }

    @Component
    @Profile("ex13-q1")
    public static class Q1Publisher implements ApplicationRunner {

        private final KafkaTemplate<String, Object> template;

        public Q1Publisher(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int round = 0; round < 2; round++) {          // ★ 같은 것을 두 번 발행
                for (int i = 1; i <= 10; i++) {
                    OrderCreated e = OrderCreated.of(i);
                    ProducerRecord<String, Object> rec =
                            new ProducerRecord<>(TOPIC_OUTBOX, null, e.orderId(), e);
                    rec.headers().add(HDR_MESSAGE_ID,
                            ("EVT-" + e.orderId()).getBytes(StandardCharsets.UTF_8));
                    template.send(rec).join();
                }
            }
        }
    }

    /* =====================================================================
     * 문제 2. SELECT-then-INSERT 방식의 경합을 재현하고 중복 차감 횟수를 세세요.
     *
     * 요구사항
     *   - 라운드마다 재고를 1000 으로 되돌리고, 두 스레드가 같은 messageId 를
     *     동시에 처리하도록 CountDownLatch 로 출발선을 맞춥니다
     *   - wrongDeduct() 는 COUNT(*) 로 먼저 확인한 뒤 차감하고 INSERT 하는 방식입니다
     *   - 100 라운드를 돌고, 재고가 997 미만인 라운드 수를 duplicateDeductions 에 셉니다
     *
     * 힌트: 카운터를 올리는 위치는 wrongDeduct() 안이 아닙니다.
     *       "INSERT 가 실패했는데 그 전에 이미 차감이 일어난" 상태는
     *       라운드가 끝난 뒤 재고를 조회해야만 판정할 수 있습니다.
     * ===================================================================== */
    @Component
    @Profile("ex13-q2")
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
                String messageId = "EX2-" + round;
                jdbc.update("UPDATE inventory SET quantity = 1000 WHERE sku = 'SKU-002'");

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                // 여기에 작성: 두 스레드를 pool 에 제출하고, start.await() 로 출발선을 맞춘 뒤
                //             wrongDeduct(messageId) 를 호출하게 하세요.

                start.countDown();
                done.await();

                // 여기에 작성: 재고를 조회해 997 미만이면 duplicateDeductions 를 올리세요.
            }
            pool.shutdown();
            log.warn("duplicate deductions: {} / {}", duplicateDeductions.get(), ROUNDS);
        }

        /** ❌ 틀린 방식. 손대지 마세요. 이것을 재현하는 것이 문제입니다. */
        private void wrongDeduct(String messageId) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_message WHERE message_id = ?",
                    Integer.class, messageId);
            if (cnt != null && cnt > 0) {
                return;
            }
            jdbc.update("UPDATE inventory SET quantity = quantity - 3 WHERE sku = 'SKU-002'");
            try {
                jdbc.update("INSERT INTO processed_message (message_id, consumer_group) VALUES (?, 'ex2')",
                        messageId);
            } catch (DuplicateKeyException ignored) {
                // 이미 늦었습니다
            }
        }

        // 확인:
        //   WARN ... duplicate deductions: 11 / 100     ← 숫자는 실행마다 달라집니다
        //   그 사실 자체가 답의 일부입니다. "한 번 통과했으니 괜찮다"가 왜 안 되는가?
    }

    /* =====================================================================
     * 문제 3. Transactional Outbox 를 구현하세요.
     *
     * 요구사항
     *   - Q3OrderService.createOrder() 는 @Transactional 안에서
     *     orders INSERT + outbox_event INSERT 만 합니다. Kafka 호출 금지.
     *   - Q3Relay.relay() 는 @Scheduled(fixedDelay = 500) 로
     *     published_at IS NULL 인 행을 ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED 로 집고,
     *     send().join() 후 published_at 을 갱신합니다
     *   - 발행 시 key = aggregate_id, 헤더 messageId = "OBX-" + id
     *
     * 검증
     *   docker compose stop kafka
     *   curl -s -XPOST localhost:8080/orders/1     ← 200 이 나와야 합니다
     *   mq "SELECT id, published_at FROM outbox_event;"   ← published_at IS NULL 로 쌓임
     *   docker compose start kafka                 ← 릴레이가 알아서 밀린 것을 발행
     * ===================================================================== */
    @Component
    @Profile("ex13-q3")
    public static class Q3OrderService {

        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        public Q3OrderService(JdbcTemplate jdbc, ObjectMapper mapper) {
            this.jdbc = jdbc;
            this.mapper = mapper;
        }

        // 여기에 작성: @Transactional 을 붙이고 orders + outbox_event 를 함께 INSERT 하세요.
        public String createOrder(OrderCreated e) {
            return e.orderId();
        }
    }

    @Configuration
    @EnableScheduling
    @Profile("ex13-q3")
    public static class Q3Relay {

        private static final Logger log = LoggerFactory.getLogger(Q3Relay.class);

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
            // 여기에 작성: SKIP LOCKED 로 집고 → send().join() → published_at 갱신
        }

        // 확인:
        //   INFO ... c.e.o.s13.Exercise$Q3Relay : relayed 1 events (id 1..1)
        //   mq "SELECT id, created_at, published_at FROM outbox_event;"  ← 두 시각 차이 300ms 내외
    }

    /* =====================================================================
     * 문제 4. SKIP LOCKED 를 뺀 릴레이를 두 인스턴스로 띄워 중복 발행을 측정하세요.
     *
     * 요구사항
     *   - Q4Relay 는 문제 3 과 같지만 SELECT 에서 FOR UPDATE SKIP LOCKED 를 뺍니다
     *   - 시작 시 outbox_event 에 300건을 미리 넣습니다 (seedIfEmpty)
     *   - 두 번째 인스턴스는 --server.port=8081 로 띄웁니다
     *
     * 측정
     *   kcc --topic orders.outbox --from-beginning | wc -l
     *   → 300 이어야 정상. 몇 건이 나오나요?
     *   그다음 SKIP LOCKED 를 넣고 토픽을 비운 뒤 다시 측정해 비교하세요.
     * ===================================================================== */
    @Configuration
    @EnableScheduling
    @Profile("ex13-q4")
    public static class Q4Relay {

        private static final Logger log = LoggerFactory.getLogger(Q4Relay.class);

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
            // 여기에 작성: FOR UPDATE SKIP LOCKED **없이** 폴링하는 릴레이
        }

        // 확인 (측정 기록):
        //   SKIP LOCKED 없음  → 토픽 메시지 수: ______   (중복 ______건)
        //   FOR UPDATE 만     → 토픽 메시지 수: ______   (락 대기로 처리량 ______)
        //   SKIP LOCKED 있음  → 토픽 메시지 수: ______
    }

    /* =====================================================================
     * 문제 5. 아래 다섯 조각 중 "순서 보장이 깨지는 것"을 모두 고르고 이유를 적으세요.
     *         코드를 실행하지 않습니다. 주석에 답만 적습니다.
     *
     *   (a) spring.kafka.listener.concurrency: 3  (파티션 3개, 키 = orderId)
     *
     *   (b) template.send("orders.outbox", event);            // 키 없이 발행
     *
     *   (c) enable.idempotence: false
     *       retries: 3
     *       max.in.flight.requests.per.connection: 5
     *
     *   (d) new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L))
     *
     *   (e) @KafkaListener(...)
     *       public void on(ConsumerRecord<String, OrderCreated> r) {
     *           CompletableFuture.runAsync(() -> handle(r), pool);
     *       }
     *
     * 답:
     *   깨지는 것 = (   ), (   ), (   )
     *   (a) 이유:
     *   (b) 이유:
     *   (c) 이유:
     *   (d) 이유:
     *   (e) 이유:
     * ===================================================================== */

    /* =====================================================================
     * 문제 6. DLT 재처리 도구를 만드세요.
     *
     * 요구사항
     *   - orders.outbox.DLT 를 읽어 원본 토픽(orders.outbox)으로 재발행합니다
     *   - ★ messageId 헤더를 반드시 보존하세요. 새 UUID 를 붙이면 멱등성이 깨져
     *     재고가 두 번 차감됩니다
     *   - kafka_dlt-* 로 시작하는 헤더는 제거합니다. 그대로 두면 다시 DLT 로 갔을 때
     *     헤더가 중첩돼 원본 정보를 잃습니다
     *   - autoStartup = "false" 로 두고, 필요할 때만 켭니다 (Step 10)
     *
     * 시나리오
     *   1) mq "UPDATE inventory SET quantity = 1 WHERE sku = 'SKU-001';"
     *   2) 주문을 넣어 DLT 로 보냅니다
     *   3) mq "UPDATE inventory SET quantity = 1000 WHERE sku = 'SKU-001';"
     *   4) 재처리 도구를 켜서 성공하는지 확인합니다
     * ===================================================================== */
    @Component
    @Profile("ex13-q6")
    public static class Q6DltReprocessor {

        private static final Logger log = LoggerFactory.getLogger(Q6DltReprocessor.class);

        private final KafkaTemplate<String, Object> template;

        public Q6DltReprocessor(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @KafkaListener(id = "dltReprocessor", topics = TOPIC_OUTBOX_DLT,
                groupId = "s13-ex-dlt-reprocess", autoStartup = "false")
        public void reprocess(ConsumerRecord<String, OrderCreated> rec) {
            // 여기에 작성:
            //   1) 새 ProducerRecord 를 원본 토픽 + 원본 키로 만들고
            //   2) rec.headers() 를 순회하며 messageId 는 복사, kafka_dlt-* 는 버리고
            //   3) send().join() 으로 확인까지 하세요
        }

        /** 컨테이너를 켜는 스위치. KafkaListenerEndpointRegistry 로 시작합니다. */
        public void startReprocessing(
                org.springframework.kafka.config.KafkaListenerEndpointRegistry registry) {
            // 여기에 작성: registry 에서 "dltReprocessor" 컨테이너를 찾아 start() 하세요.
        }

        // 확인:
        //   INFO ... reprocessed messageId=OBX-11 key=ORD-0003 → orders.outbox
        //   INFO ... c.e.o.s13.InventoryTx : deducted SKU-001 -4 (remaining=996)
        //   재처리를 두 번 돌려도 재고가 한 번만 빠져야 합니다. messageId 를 보존했다면 그렇게 됩니다.
    }

    /** 문제 6 의 헤더 필터에 쓸 유틸. 참고용으로 제공합니다. */
    static boolean isDltHeader(Header h) {
        return h.key().startsWith("kafka_dlt-");
    }

    /** 문제 6 참고: 헤더 값을 문자열로 푸는 방법 */
    static String headerAsString(List<Header> headers, String key) {
        for (Header h : headers) {
            if (h.key().equals(key)) {
                return new String(h.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
