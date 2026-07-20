package com.example.order.step03;

/*
 * ============================================================================
 * Step 03 — @KafkaListener 기초 / Solution (6문제 정답 + 해설)
 * ============================================================================
 *
 * 배치할 위치: src/main/java/com/example/order/step03/Solution.java
 *
 * [실행]
 *   ./gradlew bootRun --args='--spring.profiles.active=step03s'
 *   ./gradlew bootRun --args='--spring.profiles.active=step03s --app.sol.concurrency=5'
 *   ./gradlew bootRun --args='--spring.profiles.active=step03s-slow'      # 정답 6
 *
 * 반드시 Exercise.java 를 먼저 풀어 본 뒤에 열 것.
 * ============================================================================
 */

import com.example.order.domain.OrderCreated;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    @Component
    @Profile({"step03s", "step03s-slow"})
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

    /* ========================================================================
     * 정답 1. concurrency 를 파티션보다 크게 잡았을 때
     *
     * 관찰 기록
     *   1-a. "partitions assigned: []"  → 2줄
     *          #0-3 과 #0-4 가 빈 배열을 받는다. 컨슈머 5개 중 3개만 파티션을 받았기 때문.
     *   1-b. --members 의 #PARTITIONS 0 → 2개
     *   1-c. 경고/에러 로그 → 하나도 없다. 전부 INFO 다.
     *   1-d. 처리 완료 시각 → 사실상 동일. 처리량 개선이 0% 다.
     *
     * 이 문제의 답은 "코드"가 아니라 "로그를 읽는 눈"이다.
     * 파티션이 3개인 토픽에서 한 컨슈머 그룹의 최대 병렬도는 3 이다.
     * concurrency 를 5, 10, 100 으로 올려도 3개만 일하고 나머지는 스레드와
     * KafkaConsumer 객체, TCP 연결, 힙 버퍼만 차지한 채 논다.
     *
     * 가장 나쁜 점은 Spring 도 Kafka 도 이것을 경고하지 않는다는 것이다.
     * 그래서 "랙이 밀린다 → concurrency 를 올린다 → 아무 변화 없다 → 더 올린다"
     * 라는 무의미한 튜닝 루프에 빠지기 쉽다.
     *
     * 발견 수단은 사실상 하나뿐이다:
     *   kcg --describe --group <그룹> --members
     * --members 없이 --describe 만 하면 파티션이 없는 멤버는 표에 아예 나오지 않는다.
     * 즉 "노는 컨슈머"는 기본 출력에서 보이지도 않는다.
     *
     * 해결은 concurrency 가 아니라 파티션 쪽이다. 다만 파티션을 늘리면 키→파티션
     * 매핑이 통째로 바뀌어 같은 주문 ID 의 이벤트가 다른 파티션으로 가고 순서 보장이
     * 깨진다. 그래서 파티션 수는 처음에 넉넉히 정하고, concurrency 는 그 안에서만
     * 조절하는 값이다.
     * ====================================================================== */
    @Configuration
    @Profile("step03s")
    public static class S1Config {

        @Bean("solFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solFactory(
                ConsumerFactory<String, OrderCreated> cf,
                @Value("${app.sol.concurrency:3}") int concurrency) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(concurrency);
            return f;
        }
    }

    @Component
    @Profile("step03s")
    public static class S1Listener {

        private static final Logger log = LoggerFactory.getLogger(S1Listener.class);

        @KafkaListener(id = "sol-inv", groupId = "s03s-inventory",
                topics = "orders", containerFactory = "solFactory")
        public void onOrder(OrderCreated e) {
            log.info("[S1] {}", e.orderId());
        }
    }

    /* ========================================================================
     * 정답 2. 두 컨슈머 그룹으로 팬아웃
     *
     * 핵심은 groupId 를 서로 다르게 주는 것 하나다. 그것만으로 두 리스너가
     * 각자 파티션 3개를 전부 받고, 9건씩 총 18번 호출된다.
     *
     * id 를 함께 붙인 이유는 두 가지다.
     *   ① 스레드 이름이 [sol-a-0-C-1] / [sol-b-0-C-1] 로 찍혀 로그를 읽을 수 있다.
     *      id 가 없으면 [ntainer#0-0-C-1] / [ntainer#1-0-C-1] 이라 어느 리스너인지
     *      번호를 세어 가며 추측해야 한다.
     *   ② KafkaListenerEndpointRegistry 로 컨테이너를 이름으로 꺼내 stop()/start()
     *      할 수 있다(Step 10).
     *
     * id 만 주고 groupId 를 생략해도 동작한다. idIsGroup 기본값이 true 라서 id 가
     * 그룹 이름으로도 쓰이기 때문이다. 그런데 그렇게 하면 "컨테이너 이름"과
     * "컨슈머 그룹 이름"이 한 값으로 묶여 버린다. 나중에 컨테이너 이름만 바꾸고
     * 싶어도 그룹 이름이 함께 바뀌어 오프셋이 초기화되고, 앱이 토픽을 처음부터
     * 다시 읽는다. 둘은 수명이 다른 값이므로 따로 주는 것이 안전하다.
     *
     * ⚠️ 두 리스너에 같은 groupId 를 주면 합계가 18 이 아니라 9 가 된다.
     *    A 가 orders-0/1 을 받아 6건, B 가 orders-2 를 받아 3건. 에러도 없고 랙도 0 이다.
     *    "왜 내 리스너가 3건 중 1건만 받지?" 의 정체가 이것이다.
     * ====================================================================== */
    @Component
    @Profile("step03s")
    public static class S2Listener {

        private static final Logger log = LoggerFactory.getLogger(S2Listener.class);

        public static final AtomicInteger counterA = new AtomicInteger();
        public static final AtomicInteger counterB = new AtomicInteger();

        @KafkaListener(id = "sol-a", groupId = "s03s-fanout-a", topics = "orders")
        public void handleA(OrderCreated e) {
            log.info("[S2-A] {} (누적 {})", e.orderId(), counterA.incrementAndGet());
        }

        @KafkaListener(id = "sol-b", groupId = "s03s-fanout-b", topics = "orders")
        public void handleB(OrderCreated e) {
            log.info("[S2-B] {} (누적 {})", e.orderId(), counterB.incrementAndGet());
        }
    }

    /* ========================================================================
     * 정답 3. topicPartitions 로 파티션 1 만 소비
     *
     * @TopicPartition(topic = "orders", partitions = {"1"}) 하나면 된다.
     * 수신되는 주문은 ORD-0001, ORD-0006, ORD-0008 세 건뿐이다.
     * orders-0 과 orders-2 의 여섯 건은 이 리스너에게 영원히 오지 않는다.
     *
     * --app.sol.concurrency=5 로 실행해도 컨테이너는 1개만 생긴다.
     * 정답 1 과 정반대다. 이유는 명확하다 — 명시 할당에서는 Spring 이 파티션 개수를
     * 미리 알기 때문에 concurrency 를 그 수까지 자동으로 낮춘다. 반면 topics= 로
     * 그룹 관리에 맡기면 Spring 은 파티션이 몇 개인지 모르므로 요청한 만큼 다 만들고,
     * 남는 컨슈머는 빈 할당을 받은 채 논다.
     * 즉 3-4 의 함정은 "그룹 관리 방식일 때만" 발생한다.
     *
     * 전용 groupId("s03s-p1")를 쓰는 이유:
     *   명시 할당은 리밸런스를 우회하지만 오프셋 커밋은 여전히 그 groupId 앞으로 나간다.
     *   같은 그룹을 쓰는 일반 리스너가 어딘가에 있으면 두 컨슈머가 같은 그룹의 커밋
     *   위치를 서로 덮어쓴다. 한쪽이 오프셋 100 을, 다른 쪽이 3 을 커밋하면 97건이
     *   재처리되거나 유실된다.
     *   또한 명시 할당 리스너를 담은 앱을 2대로 늘리면 두 대가 같은 파티션을 동시에
     *   읽는다. 리밸런스가 없으니 아무도 말리지 않는다 — 중복 소비다.
     *   그래서 명시 할당은 상시 서비스가 아니라 인스턴스 1대짜리 일회성 도구로 쓴다.
     * ====================================================================== */
    @Component
    @Profile("step03s")
    public static class S3Listener {

        private static final Logger log = LoggerFactory.getLogger(S3Listener.class);

        @KafkaListener(id = "sol-p1", groupId = "s03s-p1",
                containerFactory = "solFactory",
                topicPartitions = @TopicPartition(topic = "orders", partitions = {"1"}))
        public void onlyPartitionOne(OrderCreated e,
                                     @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
            log.info("[S3] p{} {}", partition, e.orderId());
        }
    }

    /* ========================================================================
     * 정답 4. 이름 붙인 컨테이너 팩토리 두 개
     *
     * @Bean 메서드 이름(또는 @Bean("...") 의 값)이 곧 빈 이름이고,
     * @KafkaListener 의 containerFactory 문자열은 그 빈 이름과 정확히 일치해야 한다.
     * 한 글자라도 틀리면 기동이 실패한다:
     *   IllegalStateException: Could not create message listener container for endpoint
     *     ... No bean named 'solFastFactroy' available
     * 이 스텝에서 다루는 실수 중 유일하게 "시끄럽게 터지는" 종류다. 나머지는 전부
     * 조용히 잘못 동작한다.
     *
     * orderedFactory 의 concurrency 는 반드시 1 이어야 한다.
     * 3 으로 두어도 컴파일되고 기동되고 메시지도 다 처리된다. 하지만 파티션 3개를
     * 스레드 3개가 나눠 가지므로 파티션 간 처리 순서가 뒤섞이고, "순서 보장"이라는
     * 요구사항을 만족하지 못한다. 컴파일 성공이 정답의 근거가 될 수 없는 전형이다.
     *
     * 참고: concurrency=1 이어도 "토픽 전체의 전역 순서"가 보장되는 것은 아니다.
     * Kafka 가 순서를 보장하는 단위는 언제나 파티션 하나다. concurrency=1 은
     * "한 스레드가 모든 파티션을 순차 처리한다"는 뜻일 뿐, 파티션 사이의 상대 순서는
     * 여전히 정의되지 않는다. 전역 순서가 필요하면 파티션을 1개로 두어야 한다.
     * ====================================================================== */
    @Configuration
    @Profile("step03s")
    public static class S4Config {

        @Bean("solFastFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solFastFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);
            return f;
        }

        @Bean("solOrderedFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solOrderedFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step03s")
    public static class S4Listener {

        private static final Logger log = LoggerFactory.getLogger(S4Listener.class);

        @KafkaListener(id = "s4-fast", groupId = "s03s-q4-fast",
                topics = "orders", containerFactory = "solFastFactory")
        public void fast(ConsumerRecord<String, OrderCreated> rec) {
            log.info("[S4-fast] {}-{} {}", rec.topic(), rec.partition(), rec.key());
        }

        @KafkaListener(id = "s4-ordered", groupId = "s03s-q4-ordered",
                topics = "orders", containerFactory = "solOrderedFactory")
        public void ordered(ConsumerRecord<String, OrderCreated> rec) {
            log.info("[S4-ordered] {}-{} {}", rec.topic(), rec.partition(), rec.key());
        }
    }

    /* ========================================================================
     * 정답 5. 배치 리스너로 전환
     *
     * 정답은 두 가지를 "동시에" 하는 것이다.
     *   ① 팩토리에서 f.setBatchListener(true)
     *   ② 리스너 시그니처를 List<OrderCreated> 로
     *
     * 한쪽만 하면 이렇게 된다.
     *   - 팩토리만 배치, 시그니처는 단건 → 기동 실패.
     *       IllegalStateException: A batch listener must return a List of results
     *         or the method must accept a List of ConsumerRecord/Message/payload
     *     이건 다행히 시끄럽게 터진다.
     *   - 시그니처만 List, 팩토리는 레코드 모드 → 기동도 되고 에러도 없다.
     *     그런데 리스트에 원소가 항상 1개씩만 들어온다. "배치로 바꿨는데 왜 안 빨라지지"
     *     라고 몇 시간을 헤매게 되는 무증상 오작동이다. events.size() 를 로그에 찍어
     *     두면 즉시 알 수 있으므로, 배치로 전환할 때는 반드시 크기를 로깅하라.
     *
     * 배치 크기는 spring.kafka.consumer.max-poll-records(기본 500)가 상한이고
     * 하한은 없다. 토픽에 3건뿐이면 3건짜리 배치가 온다. 즉 "배치가 500건 모일 때까지
     * 기다린다"가 아니라 "poll 이 가져온 만큼 한 번에 준다"이다.
     *
     * 한 배치 안의 레코드가 항상 같은 파티션에서 온다는 보장도 없다. 한 스레드가
     * 파티션 2개를 맡으면 두 파티션의 레코드가 한 리스트에 섞여 온다.
     *
     * 배치 리스너의 진짜 난점은 부분 실패다. 5건 중 3번째가 터졌을 때 어디까지
     * 커밋되는가 — 여기서 조용한 유실과 재처리가 발생한다. Step 06 의 주제다.
     * ====================================================================== */
    @Configuration
    @Profile("step03s")
    public static class S5Config {

        @Bean("solBatchFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solBatchFactory(
                ConsumerFactory<String, OrderCreated> cf) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(cf);
            f.setConcurrency(3);
            f.setBatchListener(true);           // ① 팩토리
            return f;
        }
    }

    @Component
    @Profile("step03s")
    public static class S5Listener {

        private static final Logger log = LoggerFactory.getLogger(S5Listener.class);

        @KafkaListener(id = "s5-batch", groupId = "s03s-q5-batch",
                topics = "orders", containerFactory = "solBatchFactory")
        public void onBatch(List<OrderCreated> events) {   // ② 시그니처
            log.info("[S5] 배치 {}건: {}", events.size(),
                    events.stream().map(OrderCreated::orderId).toList());
        }
    }

    /* ========================================================================
     * 정답 6. max.poll.interval.ms 초과와 재처리
     *
     * 관찰 기록
     *   6-a. LeaveGroup 로그를 찍은 스레드 → [ad | s03s-slow-1 ]
     *          리스너 스레드가 아니라 하트비트(백그라운드) 스레드다.
     *   6-b. 로그 레벨 → INFO. WARN 도 ERROR 도 아니다.
     *   6-c. 커밋 실패 예외 → org.apache.kafka.clients.consumer.CommitFailedException
     *   6-d. 재조인 후 처음 다시 처리된 주문 → ORD-0002 (원점)
     *   6-e. session.timeout.ms 로 안 걸린 이유 →
     *          하트비트는 리스너와 무관한 별도 스레드에서 계속 나가기 때문이다.
     *          리스너가 20초를 자든 5분을 자든 브로커는 "이 컨슈머는 살아 있다"고 본다.
     *          그래서 "처리가 너무 오래 걸리는 것"을 감지하는 장치가 따로 필요했고,
     *          그것이 max.poll.interval.ms 다. 이 값은 브로커가 아니라 컨슈머 자신이
     *          검사하고, 초과하면 스스로 LeaveGroup 을 보낸다.
     *
     * 벌어지는 일의 순서
     *   1) ORD-0002, ORD-0004 처리 완료. 그러나 AckMode.BATCH 이므로 커밋은 아직.
     *   2) 세 번째 레코드 처리 중 60초를 넘겨 컨슈머가 스스로 그룹을 나간다.
     *   3) 리스너는 그것도 모르고 ORD-0009 를 끝낸다.
     *   4) 커밋하려는 순간 CommitFailedException — 그룹 멤버가 아니니 커밋 자격이 없다.
     *   5) 재조인 → 커밋되지 않은 ORD-0002 부터 다시 처리.
     *
     * 이것이 무한 루프가 되는 이유는 근본 원인(느린 처리)이 그대로이기 때문이다.
     * 60초마다 리밸런스 → 커밋 실패 → 재처리가 영원히 반복되고, 랙은 줄지 않으며,
     * 애플리케이션은 죽지 않는다. 게다가 이미 성공한 처리를 반복하므로 리스너가
     * 멱등하지 않으면 재고가 세 번 차감된다.
     *
     * 해결 우선순위
     *   1) max-poll-records 를 줄인다.
     *      한 배치가 짧아져 poll 간격이 줄어든다. 부작용이 가장 적어 첫 수단이다.
     *      여기서는 3 → 1 로 낮추면 20초 < 30초 가 되어 바로 해소된다.
     *   2) 리스너를 빠르게 만든다. 외부 API·DB 왕복을 배치화하거나 타임아웃을 건다.
     *   3) max.poll.interval.ms 를 늘린다. 마지막 수단이다.
     *      이 값을 늘리면 "진짜로 죽은 컨슈머"를 감지하는 시간도 함께 늘어나,
     *      장애 시 파티션이 그만큼 오래 방치된다.
     *   4) 처리를 별도 스레드풀로 넘긴다. poll 은 계속 돌지만 커밋 시점이 처리 완료와
     *      어긋나 메시지 유실 위험이 생긴다. Step 06 을 읽고 결정할 것.
     *
     * 적정값 계산식: max.poll.records × (건당 최악 처리시간) × 안전계수 2
     *   건당 200ms, 500건 → 500 × 0.2s × 2 = 200초. 기본값 300초 안이라 안전.
     *   건당 2초라면 2000초가 필요한데, 이때는 interval 을 올릴 게 아니라
     *   max-poll-records 를 50 으로 줄이는 것이 옳다(50 × 2s × 2 = 200초).
     * ====================================================================== */
    @Configuration
    @Profile("step03s-slow")
    public static class S6Config {

        @Bean("solSlowConsumerFactory")
        public ConsumerFactory<String, OrderCreated> solSlowConsumerFactory(KafkaProperties properties) {
            Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 3);
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean("solSlowFactory")
        public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> solSlowFactory(
                ConsumerFactory<String, OrderCreated> solSlowConsumerFactory) {

            ConcurrentKafkaListenerContainerFactory<String, OrderCreated> f =
                    new ConcurrentKafkaListenerContainerFactory<>();
            f.setConsumerFactory(solSlowConsumerFactory);
            f.setConcurrency(1);
            return f;
        }
    }

    @Component
    @Profile("step03s-slow")
    public static class S6Listener {

        private static final Logger log = LoggerFactory.getLogger(S6Listener.class);

        @KafkaListener(id = "s6-slow", groupId = "s03s-slow",
                topics = "orders", containerFactory = "solSlowFactory")
        public void slow(OrderCreated e) throws InterruptedException {
            log.info("[S6] 처리 시작 {}", e.orderId());
            Thread.sleep(20_000L);
            log.info("[S6] 처리 완료 {}", e.orderId());
        }
    }
}
