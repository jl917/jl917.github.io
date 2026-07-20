package com.example.order.step09;

/*
 * ============================================================================
 * Step 09 — 트랜잭션 : Exercise (문제 6개)
 * ============================================================================
 *
 * 배치 위치
 *   spring-kafka-lab/src/main/java/com/example/order/step09/Exercise.java
 *
 * application.yml 전제
 *   spring.kafka.producer.transaction-id-prefix: tx-order-
 *
 * 실행
 *   ./gradlew bootRun --args='--spring.profiles.active=step09ex,step09ex-1'   … -6
 *
 * 확인 명령
 *   alias mq='docker exec -i learn-kafka-mysql mysql -ulearner -plearn1234 orderdb -t -e'
 *   kcc --topic orders   --from-beginning                                   ← read_uncommitted
 *   kcc --topic orders   --from-beginning --isolation-level read_committed   ← ★ 문제 2 는 이걸로
 *   kcc --topic payments --from-beginning --isolation-level read_committed
 *   kcg --describe --group s09-ex-cpp
 *
 * ⚠️ 문제 4 는 실행 전에 반드시:
 *   mq 'DELETE FROM orders WHERE order_id="ORD-9004"'
 *   두 번째 실행부터는 PK 중복 예외가 send 보다 먼저 터져서 아무 일도 안 일어납니다.
 *
 * 문제 순서대로 푸세요. 2번이 만든 abort 메시지가 있어야 3번 비교가 성립합니다.
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

public final class Exercise {

    private Exercise() { }

    static final String ORDERS = "orders";
    static final String PAYMENTS = "payments";
    static final String BOOTSTRAP = "127.0.0.1:9092";

    public record PaymentRequested(String orderId, BigDecimal amount, Instant requestedAt) {
        public static PaymentRequested from(OrderCreated o) {
            return new PaymentRequested(o.orderId(), o.amount(), o.createdAt());
        }
    }

    // ------------------------------------------------------------------------
    // 공통 빈 (수정하지 마세요)
    // ------------------------------------------------------------------------
    @Configuration
    @Profile("step09ex")
    static class CommonConfig {

        @Bean("dbTxManager")
        DataSourceTransactionManager dbTxManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        /** transactional.id 가 없는 "평범한" 프로듀서. 문제 4 에서 씁니다. */
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
    // 문제 1. 트랜잭션 밖 send 의 예외를 확인하고 해결하기
    //
    //   요구사항
    //   (a) transaction-id-prefix 가 켜진 상태에서 template.send(...) 를 그대로 호출하고,
    //       발생하는 IllegalStateException 의 메시지 "전문"을 ERROR 로 남길 것.
    //   (b) 그다음 같은 발행을 예외 없이 성공시킬 것. 단 allowNonTransactional 은 쓰지 말 것.
    //   (c) 왜 allowNonTransactional 이 답이 아닌지 주석 한 줄로 적을 것.
    //
    //   확인: 로그에 "No transaction is in process; possible solutions:" 가 찍힐 것
    // ========================================================================
    @Component
    @Profile("step09ex-1")
    static class Ex1 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex1.class);
        private final KafkaTemplate<String, Object> template;

        Ex1(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated e = OrderCreated.of(9001);

            // 여기에 작성: (a) 트랜잭션 밖 send → 예외 메시지 전문 로깅

            // 여기에 작성: (b) executeInTransaction 으로 감싸 성공시키기

            // 여기에 작성: (c) allowNonTransactional 이 답이 아닌 이유 (주석)
        }
    }

    // ========================================================================
    // 문제 2. 두 토픽 원자 발행과 abort
    //
    //   요구사항
    //   (a) executeInTransaction 안에서 orders 와 payments 에 각각 ORD-9002 를 발행할 것.
    //   (b) 세 번째 줄에서 RuntimeException 을 던져 abort 시킬 것.
    //   (c) 예외를 잡아 로그로 남길 것.
    //
    //   확인 (★ 반드시 --isolation-level read_committed 를 붙여서):
    //     kcc --topic orders   --from-beginning --isolation-level read_committed | grep ORD-9002
    //     kcc --topic payments --from-beginning --isolation-level read_committed | grep ORD-9002
    //   둘 다 아무것도 안 나와야 정답입니다.
    // ========================================================================
    @Component
    @Profile("step09ex-2")
    static class Ex2 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex2.class);
        private final KafkaTemplate<String, Object> template;

        Ex2(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            OrderCreated e = OrderCreated.of(9002);

            // 여기에 작성: (a)(b)(c)
        }
    }

    // ========================================================================
    // 문제 3. 격리 수준이 다른 두 컨슈머로 표 완성하기
    //
    //   요구사항
    //   (a) isolation.level 만 다른 컨테이너 팩토리 두 개를 만들 것
    //       - exUncommittedFactory : read_uncommitted
    //       - exCommittedFactory   : read_committed
    //   (b) 각각을 쓰는 @KafkaListener 두 개를 groupId 를 달리해 붙일 것
    //       - s09-ex-uncommitted / s09-ex-committed
    //   (c) ORD-9031(커밋) / ORD-9032(abort) / ORD-9033(커밋) 을 발행할 것
    //
    //   관측 기록 (실행 후 직접 채우세요):
    //     | 그룹                  | ORD-9031 | ORD-9032(abort) | ORD-9033 |
    //     |----------------------|----------|-----------------|----------|
    //     | s09-ex-uncommitted   |          |                 |          |
    //     | s09-ex-committed     |          |                 |          |
    //
    //   확인: read_uncommitted 쪽만 ORD-9032 를 수신할 것
    // ========================================================================
    @Configuration
    @Profile("step09ex-3")
    static class Ex3Config {

        // 여기에 작성: (a) 두 팩토리 빈
        // 힌트: ConsumerConfig.ISOLATION_LEVEL_CONFIG 의 값은 문자열입니다.
        //       defaults.getConfigurationProperties() 를 복사한 뒤 덮어쓰면 편합니다.
    }

    @Component
    @Profile("step09ex-3")
    static class Ex3 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex3.class);
        private final KafkaTemplate<String, Object> template;

        Ex3(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            // 여기에 작성: (c) 9031 커밋 / 9032 abort / 9033 커밋 발행
            Thread.sleep(3000);
        }

        // 여기에 작성: (b) 리스너 두 개
    }

    // ========================================================================
    // 문제 4. ★ 유령 이벤트 재현
    //
    //   ⚠️ 실행 전: mq 'DELETE FROM orders WHERE order_id="ORD-9004"'
    //
    //   요구사항
    //   (a) @Transactional("dbTxManager") 메서드 안에서
    //       ① orders 테이블에 INSERT
    //       ② plainTemplate 으로 orders 토픽에 발행
    //       ③ RuntimeException 을 던져 DB 를 롤백
    //   (b) 롤백 후 DB 행 수와 토픽 내용을 각각 확인할 것
    //
    //   ★ @Transactional 은 프록시로 동작하므로, 같은 클래스 안에서 자기 메서드를 호출하면
    //     트랜잭션이 안 걸립니다. 반드시 별도 빈(Ex4Service)에 두고 주입해서 호출하세요.
    //
    //   관측 기록 (실행 후 직접 채우세요):
    //     mq 'SELECT * FROM orders WHERE order_id="ORD-9004"'  →
    //     kcc --topic orders --from-beginning --isolation-level read_committed | grep ORD-9004  →
    //
    //   확인: DB 는 Empty set 인데 토픽에는 ORD-9004 가 1건 있을 것
    // ========================================================================
    @Component
    @Profile("step09ex-4")
    static class Ex4Service {

        private static final Logger log = LoggerFactory.getLogger(Ex4Service.class);
        private final JdbcTemplate jdbc;
        private final KafkaTemplate<String, Object> plain;

        Ex4Service(JdbcTemplate jdbc, @Qualifier("plainTemplate") KafkaTemplate<String, Object> plain) {
            this.jdbc = jdbc;
            this.plain = plain;
        }

        // 여기에 작성: (a) @Transactional("dbTxManager") createOrder(OrderCreated)
    }

    @Component
    @Profile("step09ex-4")
    static class Ex4 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex4.class);
        private final Ex4Service service;
        private final JdbcTemplate jdbc;

        Ex4(Ex4Service service, JdbcTemplate jdbc) {
            this.service = service;
            this.jdbc = jdbc;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            OrderCreated order = new OrderCreated("ORD-9004", 1004, "SKU-001", 2,
                    new BigDecimal(13_000), Instant.parse("2025-01-01T00:04:00Z"));

            // 여기에 작성: (b) service.createOrder(order) 호출 + 예외 처리 + DB 행 수 확인
        }
    }

    // ========================================================================
    // 문제 5. consume-process-produce 구성
    //
    //   요구사항
    //   (a) 리스너 컨테이너 팩토리에 KafkaTransactionManager 를 설정할 것 (exCppFactory)
    //   (b) orders 를 s09-ex-cpp 그룹으로 소비해 payments 로 발행하는 리스너를 만들 것
    //   (c) 재료로 ORD-9051 ~ ORD-9053 을 미리 발행해 둘 것
    //
    //   확인 방법
    //     logging.level.org.springframework.kafka.transaction=DEBUG 로 올린 뒤
    //     로그에서 "sendOffsetsToTransaction" 을 찾을 것.
    //     이 줄이 없으면 트랜잭션 매니저가 컨테이너에 안 붙은 것입니다.
    // ========================================================================
    @Configuration
    @Profile("step09ex-5")
    static class Ex5Config {

        // 여기에 작성: (a) exCppFactory 빈
        // 힌트: f.getContainerProperties().setTransactionManager(ktm)   ← 3.1.x
        //       Spring Kafka 3.2+ 라면 setKafkaAwareTransactionManager(ktm)
    }

    @Component
    @Profile("step09ex-5")
    static class Ex5 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex5.class);
        private final KafkaTemplate<String, Object> template;

        Ex5(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: (c) 재료 3건 발행
        }

        // 여기에 작성: (b) @KafkaListener(groupId = "s09-ex-cpp", containerFactory = "exCppFactory")
    }

    // ========================================================================
    // 문제 6. ProducerFencedException 재현
    //
    //   요구사항
    //   (a) 같은 transactional.id 접두사("tx-ex-fence-")를 쓰는
    //       DefaultKafkaProducerFactory 두 개를 코드로 만들 것
    //   (b) A 로 한 번 발행 → B 로 한 번 발행 → A 로 다시 발행
    //   (c) A 가 받는 예외의 클래스명과 메시지를 로그로 남길 것
    //   (d) finally 에서 두 팩토리를 destroy() 할 것 (안 하면 다음 실습이 60초 막힙니다)
    //
    //   확인: 로그에 "ProducerId set to <n> with epoch 1" 이 찍히고,
    //         이어서 ProducerFencedException 이 날 것
    // ========================================================================
    @Component
    @Profile("step09ex-6")
    static class Ex6 implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Ex6.class);

        @Override
        public void run(ApplicationArguments args) {
            // 여기에 작성: (a)(b)(c)(d)
        }
    }
}
