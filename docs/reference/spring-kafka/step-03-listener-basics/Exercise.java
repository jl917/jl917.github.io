package com.example.order.step03;

/*
 * ============================================================================
 * Step 03 — @KafkaListener 기초 / Exercise (6문제)
 * ============================================================================
 *
 * 배치할 위치: src/main/java/com/example/order/step03/Exercise.java
 *
 * [실행]
 *   ./gradlew bootRun --args='--spring.profiles.active=step03e'
 *   ./gradlew bootRun --args='--spring.profiles.active=step03e --app.ex.concurrency=5'
 *   ./gradlew bootRun --args='--spring.profiles.active=step03e-slow'      # 문제 6
 *
 * [규칙]
 *   - 컨슈머 그룹은 전부 s03e- 접두사를 쓴다. Practice 의 s03- 그룹과 오프셋이 섞이지 않게.
 *   - 문제 1 과 6 은 "코드를 쓰는" 문제가 아니라 "로그를 관찰하고 기록하는" 문제다.
 *   - 나머지는 애노테이션과 @Bean 을 직접 작성한다.
 *   - ⚠️ 이 스텝의 오답은 대부분 "컴파일도 되고 기동도 되는" 종류다.
 *        컴파일이 됐다고 정답이 아니다. 반드시 로그를 확인하라.
 *
 * [확인 명령]
 *   kt  --describe --topic orders
 *   kcg --describe --group s03e-inventory --members
 *   kcg --list
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class Exercise {

    private Exercise() {
    }

    // ------------------------------------------------------------------------
    // 공통 — 실습 데이터 9건 발행 (수정하지 말 것)
    // ------------------------------------------------------------------------
    @Component
    @Profile({"step03e", "step03e-slow"})
    public static class Seeder implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Seeder.class);
        private final KafkaTemplate<String, Object> template;

        public Seeder(KafkaTemplate<String, Object> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) {
            for (int seq = 1; seq <= 9; seq++) {
                OrderCreated e = OrderCreated.of(seq);
                template.send("orders", e.orderId(), e);
            }
            template.flush();
            log.info("seed 완료: ORD-0001 ~ ORD-0009 (9건)");
        }
    }

    // ========================================================================
    // 문제 1. concurrency 를 파티션 수보다 크게 잡고 로그를 관찰하라.
    //
    // 요구사항:
    //   - 아래 exFactory 는 app.ex.concurrency 프로퍼티로 concurrency 를 받는다.
    //   - --app.ex.concurrency=5 로 재기동하고, 기동 로그에서
    //     "partitions assigned: []" 가 몇 줄 나오는지 센다.
    //   - kcg --describe --group s03e-inventory --members 를 실행해
    //     #PARTITIONS 가 0 인 멤버가 몇 개인지 센다.
    //   - WARN 이나 ERROR 로그가 하나라도 나오는지 확인한다.
    //
    // 코드는 이미 완성돼 있다. 관찰 결과를 아래에 기록하라.
    //
    // 여기에 작성:
    //   // 관찰 기록 1-a. "partitions assigned: []" 줄 수 =
    //   // 관찰 기록 1-b. --members 의 #PARTITIONS 0 인 멤버 수 =
    //   // 관찰 기록 1-c. 경고/에러 로그가 있었는가? =
    //   // 관찰 기록 1-d. concurrency 3 일 때와 처리 완료 시각이 달라졌는가? =
    // ========================================================================
    @Configuration
    @Profile("step03e")
    public static class Q1Config {

        @Bean("exFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> exFactory(
                ConsumerFactory<String, OrderCreated> cf,
                @Value("${app.ex.concurrency:3}") int concurrency) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(concurrency);
            return f;
        }
    }

    @Component
    @Profile("step03e")
    public static class Q1Listener {

        private static final Logger log = LoggerFactory.getLogger(Q1Listener.class);

        @KafkaListener(id = "ex-inv", groupId = "s03e-inventory",
                topics = "orders", containerFactory = "exFactory")
        public void onOrder(OrderCreated e) {
            log.info("[Q1] {}", e.orderId());
        }
    }

    // ========================================================================
    // 문제 2. 두 컨슈머 그룹으로 팬아웃을 구현하라.
    //
    // 요구사항:
    //   - 아래 두 메서드가 각각 "s03e-fanout-a", "s03e-fanout-b" 그룹으로 orders 를 구독하게 한다.
    //   - 두 리스너 모두 id 를 붙여 로그의 스레드 이름을 읽을 수 있게 한다.
    //   - 실행 후 counterA, counterB 가 각각 9 가 되어야 한다(합계 18).
    //   - 만약 두 리스너에 같은 groupId 를 주면 합이 9 가 되는 것을 반드시 확인해 보라.
    // ========================================================================
    @Component
    @Profile("step03e")
    public static class Q2Listener {

        private static final Logger log = LoggerFactory.getLogger(Q2Listener.class);

        public static final AtomicInteger counterA = new AtomicInteger();
        public static final AtomicInteger counterB = new AtomicInteger();

        // 여기에 작성: @KafkaListener 애노테이션
        public void handleA(OrderCreated e) {
            log.info("[Q2-A] {} (누적 {})", e.orderId(), counterA.incrementAndGet());
        }

        // 여기에 작성: @KafkaListener 애노테이션
        public void handleB(OrderCreated e) {
            log.info("[Q2-B] {} (누적 {})", e.orderId(), counterB.incrementAndGet());
        }
    }

    // ========================================================================
    // 문제 3. topicPartitions 로 파티션 1 만 소비하는 리스너를 만들어라.
    //
    // 요구사항:
    //   - orders 토픽의 파티션 1 만 소비한다.
    //   - 전용 컨슈머 그룹 "s03e-p1" 을 쓴다. (왜 전용 그룹이어야 하는지 생각해 볼 것)
    //   - id 는 "ex-p1".
    //   - 수신되는 주문은 ORD-0001, ORD-0006, ORD-0008 세 건뿐이어야 한다.
    //   - containerFactory = "exFactory" 를 쓰고 --app.ex.concurrency=5 로 실행했을 때
    //     컨테이너가 몇 개 생기는지 로그로 확인하라. (문제 1 과 다르다)
    // ========================================================================
    @Component
    @Profile("step03e")
    public static class Q3Listener {

        private static final Logger log = LoggerFactory.getLogger(Q3Listener.class);

        // 여기에 작성: @KafkaListener 애노테이션 (topicPartitions 사용)
        public void onlyPartitionOne(OrderCreated e,
                                     @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
            log.info("[Q3] p{} {}", partition, e.orderId());
        }
    }

    // ========================================================================
    // 문제 4. 이름 붙인 컨테이너 팩토리를 두 개 만들어 리스너별로 선택하라.
    //
    // 요구사항:
    //   - "q4FastFactory"    : concurrency = 3
    //   - "q4OrderedFactory" : concurrency = 1   ← 순서 보장이 필요한 리스너용. 3 으로 두면 오답이다.
    //   - 아래 두 리스너가 각각의 팩토리를 쓰게 한다.
    //   - 기동 로그에서 fast 는 스레드 3개, ordered 는 1개인 것을 확인한다.
    // ========================================================================
    @Configuration
    @Profile("step03e")
    public static class Q4Config {

        // 여기에 작성: @Bean("q4FastFactory")

        // 여기에 작성: @Bean("q4OrderedFactory")
    }

    @Component
    @Profile("step03e")
    public static class Q4Listener {

        private static final Logger log = LoggerFactory.getLogger(Q4Listener.class);

        // 여기에 작성: @KafkaListener — id="q4-fast", groupId="s03e-q4-fast", q4FastFactory 사용
        public void fast(ConsumerRecord<String, OrderCreated> rec) {
            log.info("[Q4-fast] {}-{} {}", rec.topic(), rec.partition(), rec.key());
        }

        // 여기에 작성: @KafkaListener — id="q4-ordered", groupId="s03e-q4-ordered", q4OrderedFactory 사용
        public void ordered(ConsumerRecord<String, OrderCreated> rec) {
            log.info("[Q4-ordered] {}-{} {}", rec.topic(), rec.partition(), rec.key());
        }
    }

    // ========================================================================
    // 문제 5. 문제 4 의 fast 리스너를 배치 리스너로 전환하라.
    //
    // 요구사항:
    //   - "q5BatchFactory" 를 만들되, 배치 모드를 켠다.
    //   - 아래 리스너가 List 로 한 번에 받게 한다.
    //   - id="q5-batch", groupId="s03e-q5-batch".
    //   - ⚠️ 팩토리와 시그니처 중 하나만 바꾸면 어떤 일이 일어나는지도 각각 시도해 보라.
    //        (하나는 기동 실패, 하나는 조용히 원소 1개짜리 리스트)
    // ========================================================================
    @Configuration
    @Profile("step03e")
    public static class Q5Config {

        // 여기에 작성: @Bean("q5BatchFactory")
    }

    @Component
    @Profile("step03e")
    public static class Q5Listener {

        private static final Logger log = LoggerFactory.getLogger(Q5Listener.class);

        // 여기에 작성: @KafkaListener 애노테이션
        // 여기에 작성: 시그니처를 배치용으로 바꿀 것
        public void onBatch(List<OrderCreated> events) {
            log.info("[Q5] 배치 {}건: {}", events.size(),
                    events.stream().map(OrderCreated::orderId).toList());
        }
    }

    // ========================================================================
    // 문제 6. 느린 리스너로 max.poll.interval.ms 초과를 유발하고 재처리를 관측하라.
    //
    // 요구사항:
    //   - 아래 설정은 이미 완성돼 있다(max-poll-records=3, max.poll.interval.ms=30000).
    //   - 리스너에서 건당 20초를 자게 하여 3건 × 20초 = 60초 > 30초 를 만든다.
    //   - ./gradlew bootRun --args='--spring.profiles.active=step03e-slow' 로 실행한다.
    //   - ⚠️ 실행에 2~3분이 걸린다. LeaveGroup 로그가 뜰 때까지 끊지 말 것.
    //
    // 여기에 작성:
    //   // 관찰 기록 6-a. LeaveGroup 로그를 찍은 스레드 이름 =
    //   // 관찰 기록 6-b. LeaveGroup 로그의 레벨(INFO/WARN/ERROR) =
    //   // 관찰 기록 6-c. 커밋 실패 예외의 클래스명 =
    //   // 관찰 기록 6-d. 재조인 후 처음 다시 처리된 주문 ID =
    //   // 관찰 기록 6-e. session.timeout.ms(45초)로는 왜 안 걸렸는가? =
    // ========================================================================
    @Configuration
    @Profile("step03e-slow")
    public static class Q6Config {

        @Bean("q6SlowFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> q6SlowFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step03e-slow")
    public static class Q6Listener {

        private static final Logger log = LoggerFactory.getLogger(Q6Listener.class);

        @KafkaListener(id = "q6-slow", groupId = "s03e-slow",
                topics = "orders", containerFactory = "q6SlowFactory")
        public void slow(OrderCreated e) throws InterruptedException {
            log.info("[Q6] 처리 시작 {}", e.orderId());
            // 여기에 작성: 건당 20초를 자게 할 것
            log.info("[Q6] 처리 완료 {}", e.orderId());
        }
    }
}

/*
 * 문제 6 을 위해 application.yml 에 아래 프로필 블록을 추가하라(또는 Solution 참고).
 *
 * ---
 * spring:
 *   config:
 *     activate:
 *       on-profile: step03e-slow
 *   kafka:
 *     consumer:
 *       max-poll-records: 3
 *       properties:
 *         max.poll.interval.ms: 30000
 */
