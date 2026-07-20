package com.example.order.step09;

/*
 * ============================================================================
 * Step 09 — 트랜잭션 : Practice
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step09/Practice.java
 *
 * application.yml 에 아래 두 줄을 추가한 상태에서 실행합니다.
 *
 *   spring.kafka.producer.transaction-id-prefix: tx-order-
 *   spring.kafka.consumer.properties.isolation.level: read_committed
 *
 * 실행 (보조 프로필을 하나만 함께 켭니다. 예제끼리 서로 간섭합니다)
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-nontx'
 *       → [9-3] ★ 트랜잭션 밖 send 가 IllegalStateException 으로 죽는 것
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-exec'
 *       → [9-4] executeInTransaction 으로 orders + payments 원자 발행 / abort
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-isolation'
 *       → [9-5] ★ read_uncommitted 가 abort 된 메시지를 받는 것 (두 그룹 나란히)
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-cpp'
 *       → [9-6] consume-process-produce. sendOffsetsToTransaction 로그 확인
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-ghost'
 *       → [9-7A] ★★ 유령 이벤트. DB 는 롤백, Kafka 에는 남음
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-lost'
 *       → [9-7B] afterCommit 발행. DB 는 있는데 이벤트가 없음
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-bench'
 *       → [9-10] 처리량 실측. -Dbench.total=100000 으로 줄여도 배수는 같음
 *   ./gradlew bootRun --args='--spring.profiles.active=step09,step09-fence'
 *       → [9-11] ★ 같은 transactional.id 두 개 → ProducerFencedException
 *
 * ★ 9-7 은 실행 전 주문 행을 지우세요. 두 번째 실행부터는 PK 중복으로
 *   INSERT 가 먼저 터져 시나리오가 성립하지 않습니다.
 *
 *   mq 'DELETE FROM orders WHERE order_id IN ("ORD-0013","ORD-0014")'
 *
 * ★ 9-5 / 9-6 은 실행 전 오프셋을 리셋하세요 (앱을 먼저 종료할 것).
 *
 *   kcg --group s09-uncommitted --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s09-committed   --topic orders --reset-offsets --to-earliest --execute
 *   kcg --group s09-cpp         --topic orders --reset-offsets --to-earliest --execute
 *
 * 실행 중에 확인할 CLI
 *   alias mq='docker exec -i learn-kafka-mysql mysql -ulearner -plearn1234 orderdb -t -e'
 *   mq 'SELECT * FROM orders'                                    ← ★ 9-7 의 절반
 *   kcc --topic orders   --from-beginning                        ← read_uncommitted (기본)
 *   kcc --topic orders   --from-beginning --isolation-level read_committed
 *   kcc --topic payments --from-beginning --isolation-level read_committed
 *   kcg --describe --group s09-cpp
 *
 * 로그 레벨 (트랜잭션 흐름을 보려면 필수)
 *   logging.level.org.springframework.kafka.transaction: DEBUG
 *   logging.level.org.apache.kafka.clients.producer.internals.TransactionManager: INFO
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class Practice {

    private Practice() { }

    static final String ORDERS = "orders";
    static final String PAYMENTS = "payments";
    static final String BOOTSTRAP = "127.0.0.1:9092";

    /** payments 토픽에 실을 이벤트. orders 와 다른 토픽이어야 원자성 실습이 됩니다. */
    public record PaymentRequested(String orderId, BigDecimal amount, Instant requestedAt) {
        public static PaymentRequested from(OrderCreated o) {
            return new PaymentRequested(o.orderId(), o.amount(), o.createdAt());
        }
    }

    // ========================================================================
    // [9-0] 공통 빈
    // ========================================================================
    @Configuration
    @Profile("step09")
    static class CommonConfig {

        /**
         * ⚠️ [9-2] 트랜잭션 매니저는 반드시 이름을 지어 두고, @Transactional 에서 이름으로 지정합니다.
         * transaction-id-prefix 를 켜면 KafkaTransactionManager 가 자동 등록되는데,
         * Boot 의 JPA 자동 설정은 @ConditionalOnMissingBean(TransactionManager.class) 조건이라
         * 등록 순서에 따라 DB 트랜잭션 매니저가 아예 안 만들어질 수 있습니다.
         */
        @Bean("dbTxManager")
        DataSourceTransactionManager dbTxManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        /**
         * [9-7] "평범한" 비트랜잭션 프로듀서.
         * application.yml 의 transaction-id-prefix 와 무관한 팩토리를 따로 만들어,
         * 대부분의 프로젝트가 쓰는 상태를 한 앱 안에서 재현합니다.
         */
        @Bean("plainTemplate")
        KafkaTemplate<String, Object> plainTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
            // transactional.id 없음 → 트랜잭션 개념 자체가 없는 프로듀서
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    // ========================================================================
    // [9-3] 트랜잭션 밖 send — IllegalStateException
    // ========================================================================
    @Component
    @Profile("step09-nontx")
    static class NonTxSendDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(NonTxSendDemo.class);
        private final KafkaTemplate<String, Object> template;

        NonTxSendDemo(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            log.info("[9-3] 트랜잭션 밖에서 send 를 시도합니다");
            try {
                template.send(ORDERS, "ORD-0001", OrderCreated.of(1));
                log.warn("[9-3] 예외가 안 났습니다. transaction-id-prefix 가 켜져 있는지 확인하세요");
            } catch (IllegalStateException e) {
                // 기대 메시지:
                //   No transaction is in process; possible solutions: run the template operation
                //   within the scope of a template.executeInTransaction() operation, start a
                //   transaction with @Transactional before invoking the template method, run in a
                //   transaction started by a listener container when consuming a record
                log.error("[9-3] {}", e.getMessage());
            }

            // 해결책 ① — executeInTransaction 으로 감싼다 (권장)
            template.executeInTransaction(t -> t.send(ORDERS, "ORD-0001", OrderCreated.of(1)));
            log.info("[9-3] executeInTransaction 으로 감싸니 정상 발행되었습니다");

            // 해결책 ② — allowNonTransactional. 예외만 꺼질 뿐 원자성은 없습니다.
            // template.setAllowNonTransactional(true);
            // template.send(ORDERS, "ORD-0001", OrderCreated.of(1));
        }
    }

    // ========================================================================
    // [9-4] executeInTransaction — 두 토픽 원자 발행
    // ========================================================================
    @Component
    @Profile("step09-exec")
    static class ExecInTxDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(ExecInTxDemo.class);
        private final KafkaTemplate<String, Object> template;

        ExecInTxDemo(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            // (1) 커밋되는 트랜잭션 — orders 와 payments 양쪽에 남습니다
            OrderCreated ok = OrderCreated.of(1);
            template.executeInTransaction(t -> {
                t.send(ORDERS, ok.orderId(), ok);
                t.send(PAYMENTS, ok.orderId(), PaymentRequested.from(ok));
                return null;
            });
            log.info("[9-4] 커밋 완료: {}", ok.orderId());

            // (2) abort 되는 트랜잭션 — 양쪽 어디에도 안 남습니다
            OrderCreated bad = OrderCreated.of(2);
            log.info("[9-4] 트랜잭션 시작");
            try {
                template.executeInTransaction(t -> {
                    log.info("[9-4] send orders   {}", bad.orderId());
                    t.send(ORDERS, bad.orderId(), bad);
                    log.info("[9-4] send payments {}", bad.orderId());
                    t.send(PAYMENTS, bad.orderId(), PaymentRequested.from(bad));
                    throw new IllegalStateException("[9-4] 결제 한도 초과 — 트랜잭션을 abort 합니다");
                });
            } catch (RuntimeException e) {
                log.error("[9-4] abort 되었습니다: {}", e.getMessage());
            }

            log.info("[9-4] 확인: kcc --topic orders   --from-beginning --isolation-level read_committed");
            log.info("[9-4] 확인: kcc --topic payments --from-beginning --isolation-level read_committed");
            log.info("[9-4] 두 토픽 모두 {} 가 없어야 정답입니다", bad.orderId());
        }
    }

    // ========================================================================
    // [9-5] isolation.level — read_uncommitted vs read_committed
    // ========================================================================
    @Configuration
    @Profile("step09-isolation")
    static class IsolationConfig {

        private Map<String, Object> base(String isolation) {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);   // 문자열이어야 합니다
            return props;
        }

        @Bean("uncommittedFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> uncommittedFactory(
                ConsumerFactory<String, Object> defaults) {
            return factory(defaults, "read_uncommitted");
        }

        @Bean("committedFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> committedFactory(
                ConsumerFactory<String, Object> defaults) {
            return factory(defaults, "read_committed");
        }

        private ConcurrentKafkaListenerContainerFactory<String, Object> factory(
                ConsumerFactory<String, Object> defaults, String isolation) {
            Map<String, Object> props = new HashMap<>(defaults.getConfigurationProperties());
            props.putAll(base(isolation));
            ConcurrentKafkaListenerContainerFactory<String, Object> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step09-isolation")
    static class IsolationDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(IsolationDemo.class);
        private final KafkaTemplate<String, Object> template;

        IsolationDemo(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            send(11, true);    // 커밋
            send(12, false);   // abort  ← 이 한 건이 이 절의 주인공
            send(13, true);    // 커밋
            Thread.sleep(3000);
            log.info("[9-5] read_uncommitted 는 ORD-0012 를 받고, read_committed 는 안 받습니다");
        }

        private void send(int seq, boolean commit) {
            OrderCreated e = OrderCreated.of(seq);
            try {
                template.executeInTransaction(t -> {
                    t.send(ORDERS, e.orderId(), e);
                    if (!commit) {
                        throw new IllegalStateException("[9-5] 의도적 abort: " + e.orderId());
                    }
                    return null;
                });
                log.info("[9-5] 발행(커밋) {}", e.orderId());
            } catch (RuntimeException ex) {
                log.warn("[9-5] 발행(abort) {}", e.orderId());
            }
        }

        @KafkaListener(topics = ORDERS, groupId = "s09-uncommitted",
                       containerFactory = "uncommittedFactory")
        void readUncommitted(OrderCreated e) {
            log.info("[9-5] read_uncommitted 수신: {}", e.orderId());
        }

        @KafkaListener(topics = ORDERS, groupId = "s09-committed",
                       containerFactory = "committedFactory")
        void readCommitted(OrderCreated e) {
            log.info("[9-5] read_committed   수신: {}", e.orderId());
        }
    }

    // ========================================================================
    // [9-6] consume-process-produce
    // ========================================================================
    @Configuration
    @Profile("step09-cpp")
    static class CppConfig {

        @Bean("cppFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> cppFactory(
                ConsumerFactory<String, Object> cf,
                KafkaTransactionManager<String, Object> ktm) {

            ConcurrentKafkaListenerContainerFactory<String, Object> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(1);

            // Spring Kafka 3.1.x (이 코스)
            f.getContainerProperties().setTransactionManager(ktm);

            // ⚠️ Spring Kafka 3.2+ : 위 메서드가 deprecated 되었습니다. 아래로 교체하세요.
            //    새 시그니처는 KafkaAwareTransactionManager 만 받으므로,
            //    DataSourceTransactionManager 를 잘못 넣는 실수가 컴파일 에러가 됩니다.
            // f.getContainerProperties().setKafkaAwareTransactionManager(ktm);

            return f;
        }
    }

    @Component
    @Profile("step09-cpp")
    static class CppListener implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(CppListener.class);
        private final KafkaTemplate<String, Object> template;

        CppListener(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 소비할 재료를 먼저 넣어 둡니다
            template.executeInTransaction(t -> {
                for (int i = 1; i <= 6; i++) {
                    OrderCreated e = OrderCreated.of(i);
                    t.send(ORDERS, e.orderId(), e);
                }
                return null;
            });
            log.info("[9-6] 재료 6건 발행 완료");
        }

        /**
         * 컨테이너가 레코드 소비 시점에 Kafka 트랜잭션을 시작해 두었으므로,
         * 아래 send 는 9-3 의 IllegalStateException 없이 그 트랜잭션에 올라탑니다.
         * 커밋 직전 컨테이너가 sendOffsetsToTransaction 을 자동 호출합니다.
         */
        @KafkaListener(topics = ORDERS, groupId = "s09-cpp", containerFactory = "cppFactory")
        void process(ConsumerRecord<String, OrderCreated> rec) {
            log.info("[9-6] consume {}@{} {}", rec.topic(), rec.offset(), rec.key());
            template.send(PAYMENTS, rec.key(), PaymentRequested.from(rec.value()));

            // ORD-0005 에서만 실패시켜, 발행과 오프셋 커밋이 함께 무효가 되는 것을 봅니다
            if ("ORD-0005".equals(rec.key())) {
                throw new IllegalStateException("[9-6] 처리 실패 — 트랜잭션 롤백");
            }
        }
    }

    // ========================================================================
    // [9-7A] 유령 이벤트 — DB 는 롤백, Kafka 에는 남음
    // ========================================================================
    @Component
    @Profile("step09-ghost")
    static class GhostOrderService {

        private static final Logger log = LoggerFactory.getLogger(GhostOrderService.class);
        private final JdbcTemplate jdbc;
        private final KafkaTemplate<String, Object> plain;

        GhostOrderService(JdbcTemplate jdbc, @Qualifier("plainTemplate") KafkaTemplate<String, Object> plain) {
            this.jdbc = jdbc;
            this.plain = plain;
        }

        /**
         * 흔히 보는 코드입니다. 그리고 원자적이지 않습니다.
         * plain 은 비트랜잭션 프로듀서이므로 send 는 DB 커밋과 무관하게 즉시 나갑니다.
         */
        @Transactional("dbTxManager")
        public void createOrder(OrderCreated order) {
            log.info("[9-7A] INSERT orders {} amount={}", order.orderId(), order.amount());
            jdbc.update("INSERT INTO orders(order_id, customer_id, amount, status) VALUES (?,?,?,?)",
                    order.orderId(), order.customerId(), order.amount(), "CREATED");

            log.info("[9-7A] kafka send → {}", ORDERS);
            plain.send(ORDERS, order.orderId(), order);

            if (order.amount().intValue() > 12_000) {
                throw new IllegalStateException("[9-7] 한도 초과 — DB 롤백");
            }
        }
    }

    @Component
    @Profile("step09-ghost")
    static class GhostDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(GhostDemo.class);
        private final GhostOrderService service;
        private final JdbcTemplate jdbc;

        GhostDemo(GhostOrderService service, JdbcTemplate jdbc) {
            this.service = service;
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            OrderCreated order = OrderCreated.of(13);   // amount = 13000 → 한도 초과
            try {
                service.createOrder(order);
            } catch (RuntimeException e) {
                log.error("[9-7A] 롤백: {}", e.getMessage());
            }
            Thread.sleep(1000);

            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE order_id = ?", Integer.class, order.orderId());
            log.info("[9-7A] DB 의 {} 행 수 = {}  (0 이면 롤백된 것)", order.orderId(), rows);
            log.info("[9-7A] 이제 토픽을 보세요:");
            log.info("[9-7A]   kcc --topic orders --from-beginning --isolation-level read_committed | grep {}",
                    order.orderId());
            log.info("[9-7A] DB 에 없는 주문의 이벤트가 토픽에 있으면 = 유령 이벤트 재현 성공");
        }
    }

    // ========================================================================
    // [9-7B] 이벤트 유실 — afterCommit 으로 순서를 뒤집었을 때
    // ========================================================================
    @Component
    @Profile("step09-lost")
    static class LostOrderService {

        private static final Logger log = LoggerFactory.getLogger(LostOrderService.class);
        private final JdbcTemplate jdbc;
        private final KafkaTemplate<String, Object> plain;

        LostOrderService(JdbcTemplate jdbc, @Qualifier("plainTemplate") KafkaTemplate<String, Object> plain) {
            this.jdbc = jdbc;
            this.plain = plain;
        }

        /**
         * @Transactional 메서드 "안"의 마지막 줄에 send 를 둬도 커밋 전입니다.
         * 진짜로 순서를 뒤집으려면 afterCommit 콜백이 필요합니다.
         * 그러면 유령 이벤트는 사라지지만, 이번엔 발행 실패 = 유실이 됩니다.
         */
        @Transactional("dbTxManager")
        public void createOrder(OrderCreated order, boolean brokerDown) {
            log.info("[9-7B] INSERT orders {}", order.orderId());
            jdbc.update("INSERT INTO orders(order_id, customer_id, amount, status) VALUES (?,?,?,?)",
                    order.orderId(), order.customerId(), order.amount(), "CREATED");

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("[9-7B] afterCommit — kafka send 시도");
                    try {
                        // brokerDown 이면 존재하지 않는 토픽으로 보내 발행 실패를 흉내 냅니다.
                        // 실제 재현은 docker stop learn-kafka 후 실행하는 쪽이 정확합니다.
                        String topic = brokerDown ? "orders-broker-down-simulation" : ORDERS;
                        plain.send(topic, order.orderId(), order).get();
                        log.info("[9-7B] 발행 성공");
                    } catch (Exception e) {
                        log.error("[9-7B] 발행 실패: {}", e.getMessage());
                        log.error("[9-7B] DB 는 이미 커밋되었습니다. 되돌릴 수 없습니다 = 이벤트 유실");
                    }
                }
            });
        }
    }

    @Component
    @Profile("step09-lost")
    static class LostDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(LostDemo.class);
        private final LostOrderService service;
        private final JdbcTemplate jdbc;

        LostDemo(LostOrderService service, JdbcTemplate jdbc) {
            this.service = service;
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated order = OrderCreated.of(14);
            service.createOrder(order, true);

            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE order_id = ?", Integer.class, order.orderId());
            log.info("[9-7B] DB 의 {} 행 수 = {}  (1 이면 커밋된 것)", order.orderId(), rows);
            log.info("[9-7B] 주문은 있는데 이벤트는 없습니다. 재고도 안 빠지고 알림도 안 갑니다");
            log.info("[9-7B] 다만 DB 가 진실의 원천이므로 나중에 복구할 수 있습니다 → Outbox (9-9)");
        }
    }

    // ========================================================================
    // [9-10] 트랜잭션의 비용
    // ========================================================================
    @Component
    @Profile("step09-bench")
    static class ThroughputBench implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(ThroughputBench.class);
        private final KafkaTemplate<String, Object> tx;
        private final KafkaTemplate<String, Object> plain;
        private final int total;

        ThroughputBench(KafkaTemplate<String, Object> tx,
                        @Qualifier("plainTemplate") KafkaTemplate<String, Object> plain,
                        @Value("${bench.total:1000000}") int total) {
            this.tx = tx;
            this.plain = plain;
            this.total = total;
        }

        @Override
        public void run(ApplicationArguments args) {
            measureNonTx();
            measureTx(1);
            measureTx(100);
            measureTx(1000);
        }

        private void measureNonTx() {
            long t0 = System.nanoTime();
            for (int i = 0; i < total; i++) {
                plain.send(ORDERS, "BENCH-" + i, OrderCreated.of(i));
            }
            plain.flush();
            report("non-tx        ", t0);
        }

        private void measureTx(int batch) {
            long t0 = System.nanoTime();
            for (int i = 0; i < total; i += batch) {
                final int from = i;
                tx.executeInTransaction(t -> {
                    for (int j = from; j < Math.min(from + batch, total); j++) {
                        t.send(ORDERS, "BENCH-" + j, OrderCreated.of(j));
                    }
                    return null;
                });
            }
            report("tx batch=" + batch, t0);
        }

        private void report(String label, long t0) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long perSec = ms == 0 ? 0 : total * 1000L / ms;
            log.info("[9-10] {}  {}건  {}ms  {} msg/s", label, total, ms, perSec);
        }
    }

    // ========================================================================
    // [9-11] transactional.id 충돌 → ProducerFencedException
    // ========================================================================
    @Component
    @Profile("step09-fence")
    static class FencingDemo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(FencingDemo.class);

        /** 두 "인스턴스"를 흉내 냅니다. 같은 접두사 → 같은 transactional.id (tx-fence-0). */
        private DefaultKafkaProducerFactory<String, Object> newFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setTransactionIdPrefix("tx-fence-");
            return pf;
        }

        @Override
        public void run(ApplicationArguments args) {
            DefaultKafkaProducerFactory<String, Object> pfA = newFactory();
            DefaultKafkaProducerFactory<String, Object> pfB = newFactory();
            KafkaTemplate<String, Object> a = new KafkaTemplate<>(pfA);
            KafkaTemplate<String, Object> b = new KafkaTemplate<>(pfB);

            try {
                a.executeInTransaction(t -> t.send(ORDERS, "FENCE-1", OrderCreated.of(101)));
                log.info("[9-11] A 발행 성공 FENCE-1   (epoch 0)");

                b.executeInTransaction(t -> t.send(ORDERS, "FENCE-2", OrderCreated.of(102)));
                log.info("[9-11] B 발행 성공 FENCE-2   (epoch 1 — A 를 펜싱했습니다)");

                a.executeInTransaction(t -> t.send(ORDERS, "FENCE-3", OrderCreated.of(103)));
                log.warn("[9-11] A 가 살아남았습니다. 두 팩토리의 접두사가 실제로 같은지 확인하세요");
            } catch (Exception e) {
                log.error("[9-11] A 사망: {}: {}", e.getClass().getName(), e.getMessage());
                log.error("[9-11] 운영에서는 파드 하나가 계속 재시작하는 모습으로 나타납니다");
            } finally {
                // ⚠️ 정리하지 않으면 미완료 트랜잭션이 남아
                //    다음 실습의 read_committed 컨슈머를 transaction.timeout.ms 만큼 막습니다.
                pfA.destroy();
                pfB.destroy();
            }

            log.info("[9-11] 해결: transaction-id-prefix: tx-order-${{HOSTNAME:local}}-  + StatefulSet");
        }
    }
}
