/*
 * ============================================================================
 *  Step 02 — KafkaTemplate 과 프로듀서 : Exercise (문제지)
 * ============================================================================
 *
 *  배치 위치 : src/main/java/com/example/order/step02/Exercise.java
 *  실행      : ./gradlew bootRun --args='--spring.profiles.active=step02-ex'
 *
 *  각 문제의 "여기에 작성:" 자리를 채우세요. 뼈대는 그대로 컴파일됩니다.
 *  정답은 Solution.java 에 있습니다. 반드시 먼저 직접 풀어 보세요.
 *
 *  ⚠️ 사전 준비
 *   - 문제 3 은 "존재하지 않는 토픽"이 정말 존재하지 않아야 성립합니다.
 *     docker-compose.yml 의 KAFKA_AUTO_CREATE_TOPICS_ENABLE 이 "true" 이면
 *     send 하는 순간 토픽이 생겨 버립니다. 아래로 잠시 끄세요.
 *
 *       docker exec -it learn-kafka /opt/kafka/bin/kafka-configs.sh \
 *         --bootstrap-server localhost:9092 --entity-type brokers --entity-name 1 \
 *         --alter --add-config auto.create.topics.enable=false
 *
 *     실습 후 되돌리기:
 *       docker exec -it learn-kafka /opt/kafka/bin/kafka-configs.sh \
 *         --bootstrap-server localhost:9092 --entity-type brokers --entity-name 1 \
 *         --alter --delete-config auto.create.topics.enable
 *
 *   - 문제 2 의 벤치마크는 orders 토픽에 약 40,000건(4방식 × 10,000)을 밀어 넣습니다.
 *     뒤 스텝 실습에 방해되면 docker compose down -v && docker compose up -d 로 초기화하세요.
 *
 *   - 문제 6 은 "일부러 기동을 실패시키는" 문제입니다. @Profile("step02-ex6") 로 격리했습니다.
 *     다른 문제를 풀 때는 이 프로필을 켜지 마세요.
 * ============================================================================
 */
package com.example.order.step02;

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class Exercise {

    private Exercise() {
    }

    private static final String TOPIC = "orders";

    @Component
    @Profile("step02-ex")
    public static class ExerciseRunner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(ExerciseRunner.class);

        private final KafkaTemplate<String, OrderCreated> template;

        public ExerciseRunner(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            problem1();
            problem2();
            problem3();
            problem5();
            // 문제 4 는 Problem4Config / Problem4Demo 를 보세요 (step02-ex4 프로필)
            // 문제 6 은 Problem6Config 를 보세요 (step02-ex6 프로필)
        }

        // ====================================================================
        // 문제 1. 콜백으로 파티션/오프셋 로깅하기
        //
        //  요구사항:
        //   - ORD-0001 ~ ORD-0005 를 orders 토픽에 키를 붙여 발행한다.
        //   - 각 발행의 결과를 콜백으로 받아 다음 형식으로 로깅한다.
        //         key=ORD-0001 → orders-2@0 ts=1735689660000
        //   - 실패한 경우에도 반드시 ERROR 로 남긴다. (성공/실패 둘 다 잡아야 한다)
        //   - 힌트: thenAccept 는 성공만, exceptionally 는 실패만 잡는다.
        //           둘 다 필요하면 어떤 메서드를 써야 하는가?
        //   - 마지막에 flush() 로 버퍼를 비워 콜백이 다 돌 때까지 기다린다.
        //
        //  기대 출력 예:
        //   INFO ... [ad | producer-1] : key=ORD-0001 → orders-2@0 ts=1735689660000
        //   INFO ... [ad | producer-1] : key=ORD-0002 → orders-2@1 ts=1735689720000
        //   INFO ... [ad | producer-1] : key=ORD-0003 → orders-0@0 ts=1735689780000
        // ====================================================================
        void problem1() {
            log.info("===== 문제 1 =====");

            for (int i = 1; i <= 5; i++) {
                OrderCreated event = OrderCreated.of(i);

                // 여기에 작성:

            }

            template.flush();
        }

        // ====================================================================
        // 문제 2. 10,000건 발행 처리량 측정 비교
        //
        //  요구사항:
        //   - 아래 4가지 방식으로 각각 10,000건을 발행하고 소요 ms 와 msg/s 를 로깅한다.
        //       (a) fire-and-forget + flush()
        //       (b) whenComplete 콜백 + flush()
        //       (c) 매 건 send().get()
        //       (d) 전부 모아서 CompletableFuture.allOf(...).join()
        //   - 측정 전에 워밍업 1,000건을 먼저 보낸다.
        //     ★ 워밍업을 빼면 (a) 가 (c) 보다 느리게 나오는 황당한 결과가 나옵니다. 왜일까요?
        //   - ★ flush() 를 측정 구간의 "안"에 둘 것인가 "밖"에 둘 것인가?
        //     둘 다 해 보고 숫자가 어떻게 달라지는지 확인하세요. 어느 쪽이 공정한 측정입니까?
        //   - 마지막에 (a) 대비 (c) 가 몇 배 느린지 출력한다.
        //
        //  기대 출력 예:
        //   INFO ... : (a) fire-and-forget + flush : 10000건 /   543 ms = 18,416 msg/s
        //   INFO ... : (c) 매 건 send().get()      : 10000건 / 10870 ms =    920 msg/s
        //   INFO ... : (a) 대비 (c) 는 20배 느립니다.
        // ====================================================================
        void problem2() throws Exception {
            log.info("===== 문제 2 =====");
            final int N = 10_000;

            // 워밍업
            // 여기에 작성:

            // (a) fire-and-forget
            long elapsedA = 0;
            // 여기에 작성:

            // (b) 콜백
            long elapsedB = 0;
            // 여기에 작성:

            // (c) 매 건 get()
            long elapsedC = 0;
            // 여기에 작성:

            // (d) allOf
            long elapsedD = 0;
            // 여기에 작성:

            log.info("(a)={}ms (b)={}ms (c)={}ms (d)={}ms", elapsedA, elapsedB, elapsedC, elapsedD);
            // 여기에 작성: msg/s 환산과 배율 출력
        }

        // ====================================================================
        // 문제 3. 존재하지 않는 토픽에 send 했을 때 future 상태 관찰
        //
        //  요구사항:
        //   - "no-such-topic-s02" 로 한 건 발행한다. (자동 토픽 생성을 반드시 꺼 둘 것)
        //   - send() 리턴 "직후"의 isDone() 을 로깅한다.
        //   - 그 뒤 1초 간격으로 최대 70초 동안 isDone() / isCompletedExceptionally() 를
        //     폴링해, 상태가 바뀌는 "정확한 시점(초)"을 로깅한다.
        //   - 최종적으로 어떤 예외가 담겼는지 클래스명과 메시지를 출력한다.
        //   - 이 70초 동안 "호출부는 무엇을 믿고 있었는지"를 주석으로 한 줄 적어 보세요.
        //
        //  기대 출력 예:
        //   INFO ... : send() 직후 isDone=false
        //   INFO ... : t=  1s isDone=false
        //   ...
        //   INFO ... : t= 60s isDone=true  exceptionally=true
        //   ERROR... : 담긴 예외 = TimeoutException: Topic no-such-topic-s02 not present in metadata after 60000 ms.
        // ====================================================================
        void problem3() throws Exception {
            log.info("===== 문제 3 =====");

            CompletableFuture<SendResult<String, OrderCreated>> future =
                    template.send("no-such-topic-s02", "K-1", OrderCreated.of(1));

            // 여기에 작성: send() 직후 isDone 로깅

            for (int t = 1; t <= 70; t++) {
                Thread.sleep(1000);
                // 여기에 작성: 상태 폴링 + 변화 시점 로깅 + 완료되면 break

            }

            // 여기에 작성: 담긴 예외 출력
        }

        // ====================================================================
        // 문제 5. 키 → 파티션 매핑을 직접 계산해 예측하고 검증
        //
        //  요구사항:
        //   - ORD-0010 ~ ORD-0015 의 파티션을 "발행하기 전에" 계산해 로깅한다.
        //     계산식은 Kafka 기본 파티셔너와 동일해야 한다.
        //   - ★ Math.abs(hash) % n 을 쓰면 안 됩니다. 왜일까요? (힌트: Integer.MIN_VALUE)
        //     Kafka 는 무엇을 쓰는지 org.apache.kafka.common.utils.Utils 에서 찾아보세요.
        //   - 그 다음 실제로 발행해 콜백에서 실제 파티션을 받아 예측값과 대조한다.
        //   - 파티션 수는 하드코딩하지 말고 template.partitionsFor(TOPIC) 로 조회한다.
        //   - 추가: 파티션이 4개였다면 어디로 갔을지도 함께 출력해,
        //     "파티션 수를 바꾸면 매핑이 통째로 바뀐다"를 눈으로 확인한다.
        //
        //  기대 출력 예:
        //   INFO ... : ORD-0010 예측(3개)=1 예측(4개)=2 | 실제=1  ← 일치
        // ====================================================================
        void problem5() throws Exception {
            log.info("===== 문제 5 =====");

            int numPartitions = 0;
            // 여기에 작성: 파티션 수 조회

            for (int i = 10; i <= 15; i++) {
                String key = "ORD-%04d".formatted(i);

                // 여기에 작성: 예측값 계산 (3개일 때 / 4개일 때)

                // 여기에 작성: 발행 + 콜백에서 예측/실제 대조 로깅

            }
            template.flush();
            Thread.sleep(500);
        }

        /**
         * 문제 5 의 보조 메서드. Kafka 기본 파티셔너와 동일하게 구현하세요.
         */
        static int predictPartition(String key, int numPartitions) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            // 여기에 작성:
            return 0;
        }
    }

    // ========================================================================
    // 문제 4. 서로 다른 설정의 KafkaTemplate 두 개 만들기
    //
    //  요구사항:
    //   - "auditKafkaTemplate": acks=all, enable.idempotence=true, linger.ms=0,
    //                           clientId="audit"
    //   - "bulkKafkaTemplate" : acks=1,  enable.idempotence=false, linger.ms=100,
    //                           batch.size=131072, compression.type="lz4", clientId="bulk"
    //   - 두 템플릿을 @Qualifier 로 골라 주입받아 각각 한 건씩 발행하고,
    //     콜백에서 어느 템플릿인지 알 수 있게 로깅한다.
    //   - ★ 이 두 빈을 등록하면 KafkaTemplate 타입 빈이 3개가 됩니다(자동설정 1 + 여기 2).
    //     이름 없이 주입하면 어떤 예외로 기동이 실패합니까? 일부러 한 번 재현해 보세요.
    //   - ★ 한쪽에 @Primary 를 붙이면 문제가 해결됩니다. 그런데 왜 권장하지 않을까요?
    //     주석으로 한 줄 적어 보세요.
    //   - 기동 로그에서 "Instantiated an idempotent producer." 가 어느 clientId 에만
    //     찍히는지 확인하세요.
    //
    //  실행: ./gradlew bootRun --args='--spring.profiles.active=step02-ex4'
    // ========================================================================
    @Configuration
    @Profile("step02-ex4")
    public static class Problem4Config {

        private Map<String, Object> base(String bootstrap) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return props;
        }

        @Bean
        public KafkaTemplate<String, OrderCreated> auditKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = base(bootstrap);
            // 여기에 작성:

            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        @Bean
        public KafkaTemplate<String, OrderCreated> bulkKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = base(bootstrap);
            // 여기에 작성:

            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    @Component
    @Profile("step02-ex4")
    public static class Problem4Demo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Problem4Demo.class);

        // 여기에 작성: @Qualifier 로 두 템플릿을 주입받는 생성자

        @Override
        public void run(ApplicationArguments args) {
            log.info("===== 문제 4 =====");
            // 여기에 작성: 각 템플릿으로 한 건씩 발행 + 콜백 로깅

        }
    }

    // ========================================================================
    // 문제 6. acks=1 + enable.idempotence=true 조합의 에러 재현
    //
    //  요구사항:
    //   - (A) acks="1" 과 enable.idempotence=true 를 "동시에 명시"한 ProducerFactory 로
    //         KafkaTemplate 빈을 만들고 기동한다.
    //         → 어떤 예외로 기동이 실패합니까? 예외 클래스와 메시지를 그대로 옮겨 적으세요.
    //   - (B) 이번엔 enable.idempotence 를 "아예 설정하지 않고" acks="1" 만 준다.
    //         → 기동이 성공합니까 실패합니까? 기동 로그에
    //           "Instantiated an idempotent producer." 가 찍힙니까?
    //   - ★ (A) 와 (B) 중 운영에서 더 위험한 쪽은 어디이고, 왜 그렇습니까?
    //     주석으로 3줄 이상 적어 보세요.
    //   - 추가로 다음 두 조합도 확인해 보세요.
    //         enable.idempotence=true + retries=0
    //         enable.idempotence=true + max.in.flight.requests.per.connection=6
    //
    //  실행: ./gradlew bootRun --args='--spring.profiles.active=step02-ex6'
    //  ⚠️ 이 프로필은 기동에 실패하는 것이 "정상"입니다.
    // ========================================================================
    @Configuration
    @Profile("step02-ex6")
    public static class Problem6Config {

        @Bean
        public KafkaTemplate<String, OrderCreated> brokenKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "broken");

            // 여기에 작성: (A) acks=1 + enable.idempotence=true 를 동시에 명시

            DefaultKafkaProducerFactory<String, OrderCreated> pf =
                    new DefaultKafkaProducerFactory<>(props);
            KafkaTemplate<String, OrderCreated> template = new KafkaTemplate<>(pf);
            // ★ DefaultKafkaProducerFactory 는 지연 생성이라 빈 생성만으로는 예외가 안 납니다.
            //   실제 KafkaProducer 를 만들게 강제해야 ConfigException 을 볼 수 있습니다.
            //   힌트: pf.createProducer() 를 여기서 한 번 호출해 보세요.
            // 여기에 작성:

            return template;
        }
    }

    /** 문제 5 채점용 참고 상수 — 계산이 맞았는지 대조해 보세요 (파티션 3개 기준). */
    static final class Hint {
        private Hint() {
        }

        static void printMurmur2(String key) {
            int hash = Utils.murmur2(key.getBytes(StandardCharsets.UTF_8));
            System.out.printf("%s murmur2=%d toPositive=%d%n", key, hash, Utils.toPositive(hash));
        }
    }

    /** 문제 2 에서 쓸 수 있는 보조 컨테이너. 필요하면 쓰세요. */
    static final class Bench {
        private Bench() {
        }

        static List<CompletableFuture<SendResult<String, OrderCreated>>> newList(int n) {
            return new ArrayList<>(n);
        }

        static long elapsedMs(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }
}
