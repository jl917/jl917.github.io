package com.example.order.step09;

/*
 * ============================================================================
 * Step 09 — 트랜잭션 : Solution (정답 6개)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step09/Solution.java
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step09sol,step09sol-1'   … -6
 *
 * ⚠️ 정답 4 는 실행 전에:  mq 'DELETE FROM orders WHERE order_id="ORD-9004"'
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class Solution {

    private Solution() { }

    static final String ORDERS = "orders";
    static final String PAYMENTS = "payments";
    static final String BOOTSTRAP = "127.0.0.1:9092";

    public record PaymentRequested(String orderId, BigDecimal amount, Instant requestedAt) {
        public static PaymentRequested from(OrderCreated o) {
            return new PaymentRequested(o.orderId(), o.amount(), o.createdAt());
        }
    }

    @Configuration
    @Profile("step09sol")
    static class CommonConfig {

        @Bean("dbTxManager")
        DataSourceTransactionManager dbTxManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean("plainTemplate")
        KafkaTemplate<String, Object> plainTemplate() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    // ========================================================================
    // 정답 1 — 트랜잭션 밖 send
    // ========================================================================
    /*
     * 답: executeInTransaction 으로 감싼다. allowNonTransactional 은 쓰지 않는다.
     *
     * 왜 예외가 나는가
     *   transaction-id-prefix 를 설정하면 KafkaTemplate 의 transactional 플래그가 true 가 됩니다.
     *   이 상태의 KafkaTemplate 은 doSend 직전에 "지금 진행 중인 트랜잭션이 있는가" 를 확인하고,
     *   없으면 프로듀서를 아예 내주지 않습니다. 그 결과가 IllegalStateException 입니다.
     *   진행 중인 트랜잭션으로 인정되는 것은 세 가지뿐입니다.
     *     ① executeInTransaction 콜백 안
     *     ② @Transactional 로 시작된 트랜잭션 안
     *     ③ 리스너 컨테이너가 레코드 소비 시 시작한 트랜잭션 안
     *   예외 메시지가 이 세 가지를 그대로 나열해 줍니다. 메시지를 끝까지 읽는 것이 가장 빠른 해결입니다.
     *
     * 왜 allowNonTransactional 은 답이 아닌가
     *   이 옵션은 "트랜잭션이 없으면 트랜잭션 없이 보낸다" 입니다. 즉 예외만 사라지고
     *   원자성은 여전히 없습니다. 더 나쁜 것은 동작이 호출 문맥에 의존하게 된다는 점입니다.
     *   같은 send 한 줄이, 나중에 누가 그 메서드를 @Transactional 안으로 옮기면
     *   갑자기 트랜잭션 안에서 돌기 시작합니다. 로그에도 흔적이 없어서 사후 추적이 불가능합니다.
     *   레거시를 단계적으로 이관하는 기간에만 켜고, 끝나면 반드시 끄십시오.
     *
     * 확인 로그
     *   ERROR ... : No transaction is in process; possible solutions: run the template operation
     *               within the scope of a template.executeInTransaction() operation, ...
     */
    @Component
    @Profile("step09sol-1")
    static class Sol1 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol1.class);
        private final KafkaTemplate<String, Object> template;

        Sol1(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated e = OrderCreated.of(9001);

            // (a) 트랜잭션 밖 send
            try {
                template.send(ORDERS, e.orderId(), e);
                log.warn("정답1(a) 예외가 안 났습니다. transaction-id-prefix 설정을 확인하세요");
            } catch (IllegalStateException ex) {
                log.error("정답1(a) {}", ex.getMessage());
            }

            // (b) executeInTransaction 으로 해결
            template.executeInTransaction(t -> t.send(ORDERS, e.orderId(), e));
            log.info("정답1(b) executeInTransaction 으로 발행 성공: {}", e.orderId());

            // (c) allowNonTransactional 은 예외만 끄고 원자성은 주지 않으며,
            //     트랜잭션 유무가 호출 문맥에 따라 조용히 바뀌게 만들기 때문에 답이 아닙니다.
        }
    }

    // ========================================================================
    // 정답 2 — 두 토픽 원자 발행과 abort
    // ========================================================================
    /*
     * 답: executeInTransaction 콜백 안에서 두 번 send 하고, 그 뒤에 예외를 던진다.
     *
     * 핵심은 코드가 아니라 검증 명령입니다.
     *   kcc --topic orders --from-beginning                      ← ORD-9002 가 "보입니다"
     *   kcc --topic orders --from-beginning --isolation-level read_committed  ← 안 보입니다
     *   이 문제를 틀리는 대부분의 이유가 옵션 없이 확인하고
     *   "트랜잭션이 안 걸렸다" 고 오진하는 것입니다. 기본값이 read_uncommitted 이기 때문입니다.
     *
     * 왜 두 토픽이어야 하는가
     *   orders 는 파티션 3, payments 는 파티션 1 입니다. 서로 다른 토픽, 서로 다른 파티션에
     *   걸친 쓰기가 통째로 무효가 되는 것을 봐야 "원자적 쓰기" 를 확인한 것입니다.
     *   한 토픽 한 파티션만으로는 멱등 프로듀서와 구분이 되지 않습니다.
     *
     * 콜백 안에서 하지 말 것
     *   send(...).get() 을 부르지 마세요. 커밋은 콜백이 끝난 뒤이므로 get() 으로 얻는 것은
     *   "브로커가 레코드를 받았다" 까지입니다. 커밋 여부가 아닙니다.
     *   게다가 매 건 get() 은 배치를 무력화해 처리량을 무너뜨립니다(Step 02).
     */
    @Component
    @Profile("step09sol-2")
    static class Sol2 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol2.class);
        private final KafkaTemplate<String, Object> template;

        Sol2(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated e = OrderCreated.of(9002);
            try {
                template.executeInTransaction(t -> {
                    t.send(ORDERS, e.orderId(), e);
                    t.send(PAYMENTS, e.orderId(), PaymentRequested.from(e));
                    throw new IllegalStateException("정답2 의도적 abort");
                });
            } catch (RuntimeException ex) {
                log.error("정답2 abort: {}", ex.getMessage());
            }
            log.info("정답2 확인: kcc --topic orders   --from-beginning --isolation-level read_committed | grep {}", e.orderId());
            log.info("정답2 확인: kcc --topic payments --from-beginning --isolation-level read_committed | grep {}", e.orderId());
        }
    }

    // ========================================================================
    // 정답 3 — 격리 수준 비교
    // ========================================================================
    /*
     * 답: ConsumerConfig.ISOLATION_LEVEL_CONFIG 를 문자열 "read_uncommitted" / "read_committed" 로
     *     달리한 컨슈머 팩토리 두 개 + groupId 가 다른 리스너 두 개.
     *
     * 주의점 두 가지
     *   ① 값은 반드시 소문자 문자열입니다. "READ_COMMITTED" 나 enum 을 넣으면 기동 시
     *      ConfigException 이 납니다. 이건 그나마 즉시 드러나서 다행인 경우입니다.
     *   ② groupId 를 나누지 않으면 두 리스너가 같은 그룹에 들어가 파티션을 나눠 갖습니다.
     *      그러면 한쪽만 메시지를 받아 비교 자체가 성립하지 않습니다. 로그가 조용해서
     *      "read_committed 가 필터링했나?" 로 오해하기 딱 좋습니다.
     *
     * 관측 결과
     *   | 그룹                | ORD-9031 | ORD-9032(abort) | ORD-9033 |
     *   |--------------------|----------|-----------------|----------|
     *   | s09-ex-uncommitted |    수신   |    ★ 수신 ★     |   수신    |
     *   | s09-ex-committed   |    수신   |     안 받음      |   수신    |
     *
     * 이 표의 의미
     *   프로듀서 쪽 트랜잭션은 컨슈머가 read_committed 여야 비로소 의미를 가집니다.
     *   발행 팀과 소비 팀이 다르면, 발행 팀이 아무리 정성껏 트랜잭션을 도입해도
     *   소비 팀이 설정 한 줄을 안 바꾸면 효과가 0 입니다.
     *   트랜잭션 도입 계획의 첫 항목은 코드가 아니라 "컨슈머 목록 작성" 입니다.
     *
     * read_committed 의 대가
     *   컨슈머는 LSO(Last Stable Offset) 까지만 읽습니다. 진행 중인 트랜잭션이 하나 있으면
     *   그 뒤에 커밋된 메시지가 아무리 쌓여도 볼 수 없습니다.
     *   죽은 프로듀서 하나가 transaction.timeout.ms(기본 60초) 동안 파티션을 막습니다.
     */
    @Configuration
    @Profile("step09sol-3")
    static class Sol3Config {

        @Bean("solUncommittedFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> solUncommittedFactory(
                ConsumerFactory<String, Object> defaults) {
            return factory(defaults, "read_uncommitted");
        }

        @Bean("solCommittedFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> solCommittedFactory(
                ConsumerFactory<String, Object> defaults) {
            return factory(defaults, "read_committed");
        }

        private ConcurrentKafkaListenerContainerFactory<String, Object> factory(
                ConsumerFactory<String, Object> defaults, String isolation) {
            Map<String, Object> props = new HashMap<>(defaults.getConfigurationProperties());
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);

            ConcurrentKafkaListenerContainerFactory<String, Object> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step09sol-3")
    static class Sol3 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol3.class);
        private final KafkaTemplate<String, Object> template;

        Sol3(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            publish(9031, true);
            publish(9032, false);
            publish(9033, true);
            Thread.sleep(3000);
        }

        private void publish(int seq, boolean commit) {
            OrderCreated e = OrderCreated.of(seq);
            try {
                template.executeInTransaction(t -> {
                    t.send(ORDERS, e.orderId(), e);
                    if (!commit) {
                        throw new IllegalStateException("정답3 의도적 abort " + e.orderId());
                    }
                    return null;
                });
                log.info("정답3 발행(커밋) {}", e.orderId());
            } catch (RuntimeException ex) {
                log.warn("정답3 발행(abort) {}", e.orderId());
            }
        }

        @KafkaListener(topics = ORDERS, groupId = "s09-ex-uncommitted",
                       containerFactory = "solUncommittedFactory")
        void uncommitted(OrderCreated e) {
            log.info("정답3 read_uncommitted 수신: {}", e.orderId());
        }

        @KafkaListener(topics = ORDERS, groupId = "s09-ex-committed",
                       containerFactory = "solCommittedFactory")
        void committed(OrderCreated e) {
            log.info("정답3 read_committed   수신: {}", e.orderId());
        }
    }

    // ========================================================================
    // 정답 4 — ★ 유령 이벤트
    // ========================================================================
    /*
     * 답: @Transactional("dbTxManager") 메서드 안에서 INSERT → plain.send → 예외.
     *     코드는 세 줄이고, 정답의 본체는 관측 기록 두 줄입니다.
     *
     *   mq 'SELECT * FROM orders WHERE order_id="ORD-9004"'
     *     → Empty set
     *   kcc --topic orders --from-beginning --isolation-level read_committed | grep ORD-9004
     *     → {"orderId":"ORD-9004", ...}   ← 있습니다
     *
     *   존재하지 않는 주문의 이벤트가 이미 발행되었습니다. 하류에서는 재고가 차감되고
     *   알림이 나가고 정산 배치가 이 주문을 집계합니다. DB 에는 없는 주문에 대해서.
     *
     * 왜 send 를 마지막 줄로 옮겨도 안 되는가
     *   @Transactional 메서드 "안" 에는 커밋 시점이 없습니다. 커밋은 메서드가 정상 리턴한 뒤
     *   프록시가 수행합니다. 그러므로 메서드 안 어디에 두든 순서는 항상 send → commit 입니다.
     *   진짜로 뒤집으려면 TransactionSynchronization.afterCommit 콜백이 필요합니다.
     *   그런데 그렇게 하면 이번엔 "DB 는 커밋됐는데 Kafka 발행이 실패" 하는 유실이 생깁니다.
     *   즉 문제가 해결되는 것이 아니라 트레이드오프가 이동할 뿐입니다.
     *
     * 트랜잭션 프로듀서를 쓰면 나아지는가
     *   조금 나아지고, 사라지지는 않습니다. transaction-id-prefix 가 있으면 KafkaTemplate 은
     *   진행 중인 DB 트랜잭션에 동기화로 참여해 DB 커밋 후 Kafka 를 커밋합니다.
     *   그래도 ① DB 커밋 성공 직후 Kafka 커밋 실패 시 유실이 남고,
     *          ② 컨슈머가 read_uncommitted(기본값) 면 abort 된 메시지를 읽어
     *             유령 이벤트가 그대로 부활합니다.
     *   두 시스템에 걸친 원자적 커밋은 2PC 가 필요한데 Kafka 는 XA 리소스가 아닙니다.
     *
     * 결론
     *   Outbox 로 가십시오. 업무 데이터와 이벤트를 같은 DB 트랜잭션에 넣으면
     *   원자성이 필요한 지점이 DB 하나로 줄어듭니다. Step 13 에서 구현합니다.
     */
    @Component
    @Profile("step09sol-4")
    static class Sol4Service {

        private static final Logger log = LoggerFactory.getLogger(Sol4Service.class);
        private final JdbcTemplate jdbc;
        private final KafkaTemplate<String, Object> plain;

        Sol4Service(JdbcTemplate jdbc, @Qualifier("plainTemplate") KafkaTemplate<String, Object> plain) {
            this.jdbc = jdbc;
            this.plain = plain;
        }

        @Transactional("dbTxManager")
        public void createOrder(OrderCreated order) {
            log.info("정답4 INSERT orders {}", order.orderId());
            jdbc.update("INSERT INTO orders(order_id, customer_id, amount, status) VALUES (?,?,?,?)",
                    order.orderId(), order.customerId(), order.amount(), "CREATED");

            log.info("정답4 kafka send → {} (비트랜잭션 프로듀서이므로 즉시 나갑니다)", ORDERS);
            plain.send(ORDERS, order.orderId(), order);

            throw new IllegalStateException("정답4 한도 초과 — DB 롤백");
        }
    }

    @Component
    @Profile("step09sol-4")
    static class Sol4 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol4.class);
        private final Sol4Service service;
        private final JdbcTemplate jdbc;

        Sol4(Sol4Service service, JdbcTemplate jdbc) {
            this.service = service;
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            OrderCreated order = new OrderCreated("ORD-9004", 1004, "SKU-001", 2,
                    new BigDecimal(13_000), Instant.parse("2025-01-01T00:04:00Z"));
            try {
                service.createOrder(order);
            } catch (RuntimeException e) {
                log.error("정답4 롤백: {}", e.getMessage());
            }
            Thread.sleep(1000);

            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE order_id = ?", Integer.class, order.orderId());
            log.info("정답4 DB 행 수 = {}  (0 이면 롤백됨)", rows);
            log.info("정답4 kcc --topic orders --from-beginning --isolation-level read_committed | grep ORD-9004");
            log.info("정답4 DB 0건 + 토픽 1건 = 유령 이벤트");
        }
    }

    // ========================================================================
    // 정답 5 — consume-process-produce
    // ========================================================================
    /*
     * 답: ContainerProperties 에 KafkaTransactionManager 를 설정한다.
     *
     *   Spring Kafka 3.1.x  : f.getContainerProperties().setTransactionManager(ktm);
     *   Spring Kafka 3.2 +  : f.getContainerProperties().setKafkaAwareTransactionManager(ktm);
     *
     * 왜 3.2 에서 메서드가 바뀌었는가
     *   기존 setTransactionManager 는 PlatformTransactionManager 를 받습니다. 그래서
     *   DataSourceTransactionManager 를 넣어도 컴파일되고 기동도 됩니다. 그러면 컨테이너는
     *   DB 트랜잭션만 시작할 뿐 소비 오프셋을 Kafka 트랜잭션에 넣지 않습니다.
     *   여러분은 consume-process-produce 를 켰다고 믿지만 실제로는 안 켜져 있고,
     *   로그에 아무 차이도 없습니다. 이 코스가 말하는 "조용히 잘못 동작하는" 전형입니다.
     *   새 메서드는 KafkaAwareTransactionManager 만 받으므로 이 실수가 컴파일 에러가 됩니다.
     *
     * 켜졌는지 확인하는 방법
     *   logging.level.org.springframework.kafka.transaction=DEBUG 로 올리고
     *   로그에서 아래 줄을 찾으세요.
     *     sendOffsetsToTransaction: {orders-1=OffsetAndMetadata{offset=1}} group=s09-ex-cpp
     *   이 줄이 없으면 트랜잭션 매니저가 컨테이너에 안 붙은 것입니다.
     *   여러분이 코드로 부른 적 없는 sendOffsetsToTransaction 이 호출되는 것이
     *   "발행 + 오프셋 커밋이 하나의 트랜잭션" 이라는 유일한 증거입니다.
     *
     * 부수 효과 두 가지
     *   ① AckMode 설정이 무시됩니다. 오프셋은 항상 트랜잭션 커밋 시점에 나갑니다.
     *   ② 트랜잭션 경계가 poll 단위입니다. 레코드 리스너면 사실상 1건당 1 트랜잭션이라
     *      9-10 표의 840 msg/s 구간입니다. 처리량이 필요하면 배치 리스너로 바꾸세요.
     *      대신 배치 중 한 건이 실패하면 배치 전체가 재처리됩니다(Step 06).
     */
    @Configuration
    @Profile("step09sol-5")
    static class Sol5Config {

        @Bean("solCppFactory")
        ConcurrentKafkaListenerContainerFactory<String, Object> solCppFactory(
                ConsumerFactory<String, Object> cf,
                KafkaTransactionManager<String, Object> ktm) {

            ConcurrentKafkaListenerContainerFactory<String, Object> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(1);
            f.getContainerProperties().setTransactionManager(ktm);        // 3.1.x
            // f.getContainerProperties().setKafkaAwareTransactionManager(ktm);   // 3.2+
            return f;
        }
    }

    @Component
    @Profile("step09sol-5")
    static class Sol5 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol5.class);
        private final KafkaTemplate<String, Object> template;

        Sol5(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            template.executeInTransaction(t -> {
                for (int seq = 9051; seq <= 9053; seq++) {
                    OrderCreated e = OrderCreated.of(seq);
                    t.send(ORDERS, e.orderId(), e);
                }
                return null;
            });
            log.info("정답5 재료 3건 발행 완료");
        }

        @KafkaListener(topics = ORDERS, groupId = "s09-ex-cpp", containerFactory = "solCppFactory")
        void process(ConsumerRecord<String, OrderCreated> rec) {
            log.info("정답5 consume {}@{} {}", rec.topic(), rec.offset(), rec.key());
            template.send(PAYMENTS, rec.key(), PaymentRequested.from(rec.value()));
        }
    }

    // ========================================================================
    // 정답 6 — ProducerFencedException
    // ========================================================================
    /*
     * 답: 같은 접두사를 가진 DefaultKafkaProducerFactory 두 개를 만들고 번갈아 발행한다.
     *
     * 왜 이것이 "파드 2개" 와 같은 상황인가
     *   transactional.id 는 "접두사 + 카운터" 로 만들어지는데, 이 카운터는
     *   DefaultKafkaProducerFactory 인스턴스가 JVM 안에서 매기는 로컬 값입니다.
     *   다른 프로세스와 조율되지 않습니다. 그래서 같은 애플리케이션을 두 인스턴스 띄우면
     *   양쪽 다 tx-order-0 을 쓰고, 파드를 3개로 늘리면 셋 다 tx-order-0 입니다.
     *   팩토리 두 개를 한 JVM 에 만드는 것은 이 상황의 최소 재현입니다.
     *
     * 무슨 일이 일어나는가
     *   B 가 initTransactions 를 부르는 순간 코디네이터가 그 transactional.id 의 epoch 를
     *   0 에서 1 로 올립니다. 이때부터 epoch 0 인 A 의 요청은 전부 거절됩니다.
     *     ProducerId set to 2000 with epoch 0     ← A
     *     ProducerId set to 2000 with epoch 1     ← B (PID 는 같고 epoch 만 다릅니다)
     *     ProducerFencedException: There is a newer producer with the same transactionalId ...
     *   이것이 펜싱이고, 원래 목적은 죽은 줄 알았던 인스턴스가 되살아나
     *   옛 트랜잭션을 커밋하는 좀비 시나리오를 막는 것입니다.
     *
     * 운영에서의 증상
     *   파드 2개를 띄우면 하나는 정상, 하나는 계속 ProducerFencedException 으로 재시작합니다.
     *   스케일아웃할수록 나빠집니다. 예외는 명확히 찍히지만 원인이 "인스턴스 수" 라는 것을
     *   연결짓기까지 시간이 걸립니다.
     *
     * 해결과 그 해결의 새 문제
     *   transaction-id-prefix: tx-order-${HOSTNAME:local}-
     *   Kubernetes 에서 HOSTNAME 은 파드 이름이므로 인스턴스 간 충돌은 사라집니다.
     *   그런데 Deployment 의 파드 이름은 재시작마다 바뀝니다. 그러면 재시작 후
     *   신원이 달라져 펜싱이 동작하지 않고, 죽은 파드의 미완료 트랜잭션은
     *   transaction.timeout.ms 뒤 타임아웃 abort 될 때까지 남아
     *   read_committed 컨슈머를 그만큼 막습니다.
     *   그래서 트랜잭션 프로듀서를 쓰는 애플리케이션은 StatefulSet 이 정석입니다.
     *   파드 이름이 pod-0, pod-1 로 고정되어야 재시작 후에도 같은 신원으로 돌아옵니다.
     *
     * ⚠️ destroy() 를 잊지 마세요
     *   팩토리를 정리하지 않으면 미완료 트랜잭션이 남아 다음 실습의 read_committed
     *   컨슈머를 transaction.timeout.ms(기본 60초) 만큼 막습니다.
     */
    @Component
    @Profile("step09sol-6")
    static class Sol6 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Sol6.class);

        private DefaultKafkaProducerFactory<String, Object> newFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            DefaultKafkaProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(props);
            pf.setTransactionIdPrefix("tx-ex-fence-");   // 두 팩토리가 같은 접두사 → 같은 id
            return pf;
        }

        @Override
        public void run(ApplicationArguments args) {
            DefaultKafkaProducerFactory<String, Object> pfA = newFactory();
            DefaultKafkaProducerFactory<String, Object> pfB = newFactory();
            KafkaTemplate<String, Object> a = new KafkaTemplate<>(pfA);
            KafkaTemplate<String, Object> b = new KafkaTemplate<>(pfB);

            try {
                a.executeInTransaction(t -> t.send(ORDERS, "FENCE-1", OrderCreated.of(9061)));
                log.info("정답6 A 발행 성공 FENCE-1 (epoch 0)");

                b.executeInTransaction(t -> t.send(ORDERS, "FENCE-2", OrderCreated.of(9062)));
                log.info("정답6 B 발행 성공 FENCE-2 (epoch 1 — A 를 펜싱)");

                a.executeInTransaction(t -> t.send(ORDERS, "FENCE-3", OrderCreated.of(9063)));
                log.warn("정답6 A 가 살아남았습니다. 두 팩토리의 접두사가 같은지 확인하세요");
            } catch (Exception e) {
                log.error("정답6 A 사망: {}: {}", e.getClass().getName(), e.getMessage());
            } finally {
                pfA.destroy();
                pfB.destroy();
            }
        }
    }
}
