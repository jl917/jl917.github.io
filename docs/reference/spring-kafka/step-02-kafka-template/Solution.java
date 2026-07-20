/*
 * ============================================================================
 *  Step 02 — KafkaTemplate 과 프로듀서 : Solution (정답과 해설)
 * ============================================================================
 *
 *  배치 위치 : src/main/java/com/example/order/step02/Solution.java
 *  실행      : ./gradlew bootRun --args='--spring.profiles.active=step02-sol'
 *
 *  Exercise.java 를 먼저 풀어 본 뒤에 여세요.
 *  각 정답 위의 블록 주석이 "왜 그 답인가"를 설명합니다.
 * ============================================================================
 */
package com.example.order.step02;

import com.example.order.domain.OrderCreated;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.atomic.AtomicInteger;

public final class Solution {

    private Solution() {
    }

    private static final String TOPIC = "orders";

    @Component
    @Profile("step02-sol")
    public static class SolutionRunner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(SolutionRunner.class);

        private final KafkaTemplate<String, OrderCreated> template;

        public SolutionRunner(KafkaTemplate<String, OrderCreated> template) {
            this.template = template;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            answer1();
            answer2();
            answer3();
            answer5();
        }

        /* ====================================================================
         * 정답 1. 콜백으로 파티션/오프셋 로깅
         *
         *  ★ whenComplete 가 유일한 답입니다.
         *
         *  CompletableFuture 의 세 메서드는 역할이 다릅니다.
         *    - thenAccept(Consumer<T>)          : "성공했을 때만" 실행됩니다.
         *                                          실패하면 아예 호출되지 않으므로 유실을 놓칩니다.
         *    - exceptionally(Function<Throwable,T>) : "실패했을 때만" 실행됩니다.
         *                                          성공 시 파티션/오프셋을 찍을 수 없습니다.
         *    - whenComplete(BiConsumer<T,Throwable>) : 성공이든 실패든 "항상" 실행됩니다.
         *                                          두 인자 중 하나는 항상 null 입니다.
         *
         *  문제가 "성공 시 파티션/오프셋을 찍고, 실패 시 ERROR 를 남겨라" 이므로
         *  둘 다 잡는 whenComplete 만이 조건을 만족합니다.
         *
         *  thenAccept + exceptionally 를 체이닝해도 동작은 하지만, 두 번째 함정이 있습니다.
         *  thenAccept 안에서 예외가 나면 그것까지 exceptionally 로 흘러가서
         *  "발행은 성공했는데 발행 실패 로그가 찍히는" 혼란이 생깁니다.
         *
         *  마지막으로 flush() 를 부르는 이유는 ApplicationRunner 가 끝나 버리면
         *  콜백이 돌기 전에 로그를 못 볼 수 있기 때문입니다. (프로듀서 I/O 스레드는 데몬입니다.)
         * ==================================================================== */
        void answer1() {
            log.info("===== 정답 1 =====");

            for (int i = 1; i <= 5; i++) {
                OrderCreated event = OrderCreated.of(i);
                template.send(TOPIC, event.orderId(), event)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("발행 실패 key={} cause={}",
                                        event.orderId(), ex.getClass().getSimpleName(), ex);
                                return;
                            }
                            RecordMetadata md = result.getRecordMetadata();
                            log.info("key={} → {}-{}@{} ts={}",
                                    event.orderId(), md.topic(), md.partition(),
                                    md.offset(), md.timestamp());
                        });
            }
            template.flush();
        }

        /* ====================================================================
         * 정답 2. 10,000건 처리량 측정
         *
         *  ★ 이 문제의 채점 포인트는 숫자가 아니라 "측정 설계" 두 가지입니다.
         *
         *  (1) 워밍업이 왜 필요한가
         *      첫 send() 는 브로커에서 토픽 메타데이터를 가져오느라 수백 ms 를 씁니다.
         *      JIT 도 아직 인터프리터 모드입니다. 워밍업 없이 (a)→(b)→(c) 순으로 재면
         *      (a) 가 그 비용을 전부 뒤집어써서, 최악의 방식인 (c) 보다 (a) 가
         *      느리게 나오는 결과가 나옵니다. 순서를 바꿔도 마찬가지라 결론을 못 냅니다.
         *      워밍업 1,000건이면 메타데이터 조회와 주요 경로의 JIT 컴파일이 끝납니다.
         *
         *  (2) flush() 는 반드시 "측정 구간 안"에 있어야 한다
         *      send() 는 버퍼에 넣고 즉시 리턴합니다. flush() 를 측정 밖에 두면
         *      "메모리에 10,000번 넣는 시간"만 재게 되어 200,000 msg/s 같은 숫자가 나옵니다.
         *      그건 발행 처리량이 아니라 ArrayDeque 삽입 속도입니다.
         *      (c) 는 구조상 브로커 응답까지 기다리므로, (a) 도 같은 지점까지 기다려야
         *      비교가 성립합니다. 그래서 flush() 를 안에 둡니다.
         *
         *  실측 (Java 21 / kafka-clients 3.6.1 / 단일 브로커 / linger.ms=5):
         *      (a) fire-and-forget + flush :   543 ms → 18,416 msg/s
         *      (b) 콜백(whenComplete)      :   552 ms → 18,116 msg/s
         *      (c) 매 건 send().get()      : 10870 ms →    920 msg/s
         *      (d) 배치 후 allOf().join()  :   559 ms → 17,889 msg/s
         *
         *  (c) 가 20배 느린 이유는 배치가 죽기 때문입니다. get() 이 블로킹하는 동안
         *  호출 스레드는 다음 send() 를 못 하므로 버퍼에 두 번째 레코드가 들어올 수 없고,
         *  배치 크기가 영원히 1이 됩니다. 게다가 linger.ms=5 가 이제 이득이 아니라
         *  건당 5ms 의 순수 페널티가 됩니다. linger.ms=0 으로 내려도 660 msg/s 대라
         *  본질은 linger 가 아니라 "왕복(RTT)을 건마다 지불하는 구조"입니다.
         *
         *  (b) 가 (a) 와 거의 같은 것이 중요합니다. 콜백은 사실상 공짜입니다.
         *  즉 "안전(유실 감지)"과 "속도" 중 하나를 고를 필요가 없습니다. 콜백이 정답입니다.
         * ==================================================================== */
        void answer2() throws Exception {
            log.info("===== 정답 2 =====");
            final int N = 10_000;

            // 워밍업 — 메타데이터 조회 + JIT
            for (int i = 1; i <= 1_000; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i));
            }
            template.flush();

            // (a) fire-and-forget
            long t0 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i));
            }
            template.flush();                                  // ★ 측정 구간 안
            long a = ms(t0);

            // (b) 콜백
            AtomicInteger fail = new AtomicInteger();
            long t1 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i))
                        .whenComplete((r, ex) -> {
                            if (ex != null) fail.incrementAndGet();
                        });
            }
            template.flush();
            long b = ms(t1);

            // (c) 매 건 get()
            long t2 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                template.send(TOPIC, key(i), OrderCreated.of(i)).get();
            }
            long c = ms(t2);

            // (d) allOf
            List<CompletableFuture<SendResult<String, OrderCreated>>> futures = new ArrayList<>(N);
            long t3 = System.nanoTime();
            for (int i = 1; i <= N; i++) {
                futures.add(template.send(TOPIC, key(i), OrderCreated.of(i)));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            long d = ms(t3);
            long failedInD = futures.stream().filter(CompletableFuture::isCompletedExceptionally).count();

            report("(a) fire-and-forget + flush ", N, a);
            report("(b) 콜백(whenComplete)      ", N, b);
            report("(c) 매 건 send().get()      ", N, c);
            report("(d) 배치 후 allOf().join()  ", N, d);
            log.info("(b) 실패 {}건 / (d) 실패 {}건", fail.get(), failedInD);
            log.info("(a) 대비 (c) 는 {}배 느립니다.", c / Math.max(a, 1));
        }

        /* ====================================================================
         * 정답 3. 존재하지 않는 토픽 — future 상태 관찰
         *
         *  ★ 관찰 결과가 이 스텝 전체의 요약입니다.
         *
         *      send() 직후          isDone=false
         *      t=1s ~ t=59s        isDone=false   (계속)
         *      t=60s               isDone=true, isCompletedExceptionally=true
         *      담긴 예외           TimeoutException: Topic no-such-topic-s02 not present
         *                          in metadata after 60000 ms.
         *
         *  60초입니다. 60초 동안 호출부는 "발행에 성공했다"고 믿고 있었습니다.
         *  그 사이에 DB 에 markPublished 를 커밋했다면, 그 트랜잭션은 이미 끝났습니다.
         *  되돌릴 방법이 없습니다.
         *
         *  60초라는 숫자의 출처는 delivery.timeout.ms 가 아니라 max.block.ms 도 아닌
         *  metadata 조회 대기입니다(내부적으로 max.block.ms 와 delivery.timeout.ms 중
         *  적용되는 쪽을 따르며, 기본 조합에서 60초로 관측됩니다).
         *  실습에서 delivery.timeout.ms=10000 으로 줄여 두었다면 더 빨리 끝납니다.
         *
         *  ★ 여기서 반드시 짚어야 할 것: 이 60초 동안 애플리케이션 로그는 깨끗합니다.
         *  NetworkClient 의 WARN(UNKNOWN_TOPIC_OR_PARTITION) 이 하나 흘러갈 뿐이고,
         *  그건 인프라 로그라 아무도 알림을 걸어 두지 않습니다.
         *  콜백이 없으면 LoggingProducerListener 의 ERROR 한 줄이 전부이며,
         *  그것도 payload 가 잘려 있어 어떤 주문이었는지 복구할 단서가 부족합니다.
         * ==================================================================== */
        void answer3() throws Exception {
            log.info("===== 정답 3 =====");

            CompletableFuture<SendResult<String, OrderCreated>> future =
                    template.send("no-such-topic-s02", "K-1", OrderCreated.of(1));

            log.info("send() 직후 isDone={}", future.isDone());

            for (int t = 1; t <= 70; t++) {
                Thread.sleep(1000);
                boolean done = future.isDone();
                boolean failed = future.isCompletedExceptionally();
                if (done || t % 10 == 0) {
                    log.info("t={}s isDone={} exceptionally={}", t, done, failed);
                }
                if (done) break;
            }

            if (future.isCompletedExceptionally()) {
                try {
                    future.join();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("담긴 예외 = {}: {}", cause.getClass().getSimpleName(), cause.getMessage());
                }
            } else {
                log.warn("70초 안에도 안 끝났습니다. isDone={}", future.isDone());
            }
        }

        /* ====================================================================
         * 정답 5. 키 → 파티션 매핑 예측과 검증
         *
         *  ★ 계산식은 반드시 Utils.toPositive(Utils.murmur2(bytes)) % n 입니다.
         *
         *  Math.abs(hash) % n 을 쓰면 안 되는 이유:
         *      Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE 입니다. (음수 그대로)
         *      2의 보수에서 -2147483648 의 절댓값 2147483648 은 int 범위를 넘기 때문에
         *      오버플로로 자기 자신이 됩니다. 그러면 % n 결과가 음수가 되고,
         *      파티션 번호로 쓰는 순간 ArrayIndexOutOfBoundsException 이 납니다.
         *      확률은 40억분의 1이지만, 하루 수억 건을 보내면 언젠가 터집니다.
         *      Kafka 가 Math.abs 대신 toPositive(x & 0x7fffffff)를 쓰는 이유가 정확히 이것입니다.
         *      비트 마스크는 부호 비트만 떼므로 절대 음수가 나오지 않습니다.
         *
         *  실측 결과 (파티션 3개 / 4개):
         *      ORD-0010  murmur2=  208042438  %3=1  %4=2
         *      ORD-0011  murmur2= 1690680040  %3=1  %4=0
         *      ORD-0012  murmur2=  948856158  %3=0  %4=2
         *      ORD-0013  ...
         *
         *  ★ 3개일 때와 4개일 때가 거의 전부 다릅니다. 이게 이 문제의 결론입니다.
         *  파티션을 3 → 4 로 늘리면 같은 주문의 과거 이벤트는 옛 파티션에,
         *  새 이벤트는 새 파티션에 쌓입니다. 두 파티션은 독립적으로 소비되므로
         *  나중에 발행된 이벤트가 먼저 처리될 수 있습니다. 순서 보장이 소급해서 깨집니다.
         *  에러는 나지 않고, 재고가 음수가 되거나 취소된 주문이 배송되는 것으로 드러납니다.
         *
         *  파티션 수는 하드코딩하지 말고 partitionsFor 로 조회하세요.
         *  다만 partitionsFor 는 메타데이터가 없으면 max.block.ms 까지 블로킹하므로
         *  요청 경로에서 매번 부르면 안 됩니다. 기동 시 한 번 조회해 캐시하세요.
         * ==================================================================== */
        void answer5() throws Exception {
            log.info("===== 정답 5 =====");

            int numPartitions = template.partitionsFor(TOPIC).size();
            log.info("{} 의 파티션 수 = {}", TOPIC, numPartitions);

            for (int i = 10; i <= 15; i++) {
                String key = "ORD-%04d".formatted(i);
                int predicted3 = predictPartition(key, 3);
                int predicted4 = predictPartition(key, 4);
                int predicted = predictPartition(key, numPartitions);

                template.send(TOPIC, key, OrderCreated.of(i)).whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("발행 실패 key={}", key, ex);
                        return;
                    }
                    int actual = r.getRecordMetadata().partition();
                    log.info("{} 예측(3개)={} 예측(4개)={} | 실제={}  {}",
                            key, predicted3, predicted4, actual,
                            predicted == actual ? "← 일치" : "← ★불일치! 파티션 수 확인");
                });
            }
            template.flush();
            Thread.sleep(500);
        }

        /** Kafka 기본 파티셔너와 동일한 계산. Math.abs 를 쓰지 말 것. */
        static int predictPartition(String key, int numPartitions) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            return Utils.toPositive(Utils.murmur2(bytes)) % numPartitions;
        }

        private static String key(int i) {
            return "ORD-%04d".formatted(i);
        }

        private static long ms(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        private static void report(String label, int n, long elapsedMs) {
            long tps = elapsedMs == 0 ? 0 : (n * 1000L) / elapsedMs;
            log.info("{}: {}건 / {} ms = {} msg/s", label, n, elapsedMs, String.format("%,d", tps));
        }
    }

    /* ========================================================================
     * 정답 4. 설정이 다른 KafkaTemplate 두 개
     *
     *  ★ @Qualifier 로 빈 이름을 명시하는 방식을 택합니다. @Primary 는 쓰지 않습니다.
     *
     *  왜 기동이 실패하는가:
     *      두 @Bean 을 등록하면 KafkaTemplate<String, OrderCreated> 타입 빈이
     *      자동설정의 kafkaTemplate 까지 합쳐 3개가 됩니다. 이름 없이 주입하면
     *          NoUniqueBeanDefinitionException: expected single matching bean but found 3
     *      로 기동이 실패합니다. 이건 "시끄러운 실패"라 오히려 다행입니다.
     *
     *  왜 @Primary 를 권장하지 않는가:
     *      @Primary 는 "이름 없이 주입한 모든 코드"가 어느 템플릿을 쓸지를 침묵으로 결정합니다.
     *      bulk(acks=1, idempotence=false) 쪽에 @Primary 를 붙여 두면,
     *      나중에 누군가 아무 생각 없이 KafkaTemplate 을 주입받아 주문 이벤트를 발행할 때
     *      그 이벤트는 acks=1 로, 멱등성 없이 나갑니다. 컴파일도 되고 기동도 되고
     *      테스트도 통과하며, 브로커가 한 번 흔들릴 때까지 아무도 모릅니다.
     *      이 코스가 잡으려는 "조용한 실패"의 전형입니다.
     *      @Qualifier 를 강제하면, 새 코드는 반드시 "어느 쪽인가"를 선택해야만 컴파일됩니다.
     *
     *  clientId 를 반드시 다르게 주는 이유:
     *      안 주면 producer-1, producer-2 로 자동 부여되어 로그와 JMX 지표에서
     *      어느 쪽이 어느 설정인지 구분할 수 없습니다. 장애 때 이게 결정적입니다.
     *
     *  기동 로그 확인 포인트:
     *      [Producer clientId=audit] Instantiated an idempotent producer.   ← 있음
     *      [Producer clientId=bulk]  ...                                     ← 없음
     *      이 한 줄의 유무가 멱등성 활성화 여부를 알려 주는 가장 확실한 신호입니다.
     * ======================================================================== */
    @Configuration
    @Profile("step02-sol4")
    public static class Answer4Config {

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
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            props.put(ProducerConfig.LINGER_MS_CONFIG, 0);       // 지연 최소화
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "audit");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        @Bean
        public KafkaTemplate<String, OrderCreated> bulkKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = base(bootstrap);
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);   // acks=1 이면 명시적 false
            props.put(ProducerConfig.LINGER_MS_CONFIG, 100);              // 많이 모아서
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131_072);
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "bulk");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    @Component
    @Profile("step02-sol4")
    public static class Answer4Demo implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(Answer4Demo.class);

        private final KafkaTemplate<String, OrderCreated> audit;
        private final KafkaTemplate<String, OrderCreated> bulk;

        // ★ @Qualifier 없이 주입하면 NoUniqueBeanDefinitionException 으로 기동 실패합니다.
        public Answer4Demo(@Qualifier("auditKafkaTemplate") KafkaTemplate<String, OrderCreated> audit,
                           @Qualifier("bulkKafkaTemplate") KafkaTemplate<String, OrderCreated> bulk) {
            this.audit = audit;
            this.bulk = bulk;
        }

        @Override
        public void run(ApplicationArguments args) {
            log.info("===== 정답 4 =====");

            audit.send(TOPIC, "ORD-0001", OrderCreated.of(1))
                    .whenComplete((r, ex) -> logResult("audit", r, ex));
            bulk.send(TOPIC, "ORD-0002", OrderCreated.of(2))
                    .whenComplete((r, ex) -> logResult("bulk", r, ex));

            audit.flush();
            bulk.flush();
        }

        private void logResult(String which, SendResult<String, OrderCreated> r, Throwable ex) {
            if (ex != null) {
                log.error("[{}] 발행 실패", which, ex);
                return;
            }
            RecordMetadata md = r.getRecordMetadata();
            log.info("[{}] → {}-{}@{}", which, md.topic(), md.partition(), md.offset());
        }
    }

    /* ========================================================================
     * 정답 6. acks=1 + enable.idempotence=true
     *
     *  (A) 둘 다 "명시"한 경우 → 기동 실패
     *
     *      org.apache.kafka.common.config.ConfigException:
     *        Must set acks to all in order to use the idempotent producer.
     *        Otherwise we cannot guarantee idempotence.
     *          at ProducerConfig.postProcessAndValidateIdempotenceConfigs(ProducerConfig.java:594)
     *          at KafkaProducer.<init>(KafkaProducer.java:334)
     *
     *      멱등성은 acks=all 을 요구합니다. 리더만 받고 성공을 반환하면
     *      리더가 죽었을 때 그 레코드가 사라지고, 재시도가 "중복 제거 대상"이 아니라
     *      "새 발행"이 되어 멱등성의 전제 자체가 무너지기 때문입니다.
     *
     *      같은 종류의 제약이 둘 더 있습니다.
     *        enable.idempotence=true + retries=0
     *          → Must set retries to non-zero when using the idempotent producer.
     *        enable.idempotence=true + max.in.flight > 5
     *          → Must set max.in.flight.requests.per.connection to at most 5 ...
     *
     *  (B) enable.idempotence 를 "설정하지 않고" acks=1 만 준 경우 → 기동 성공
     *
     *      ProducerConfig 는 enable.idempotence 가 사용자에 의해 명시되지 않았고
     *      다른 설정과 충돌하면, 예외 대신 조용히 false 로 내립니다.
     *      "기본값이니까 사용자가 의도한 게 아닐 수도 있다"는 판단입니다.
     *      경고 로그도 없습니다. 유일한 단서는 기동 로그에서
     *          [Producer clientId=...] Instantiated an idempotent producer.
     *      가 "사라졌다"는 것뿐인데, 없는 로그를 알아채는 사람은 거의 없습니다.
     *
     *  ★ (A) 와 (B) 중 운영에서 더 위험한 쪽은 단연 (B) 입니다.
     *
     *      (A) 는 애플리케이션이 아예 안 뜹니다. 배포 파이프라인이 즉시 막히고,
     *          예외 메시지가 원인과 해결책을 그대로 알려 줍니다. 최악의 경우가 배포 실패입니다.
     *
     *      (B) 는 아무 일도 없이 뜹니다. 평소에는 아무 문제도 없습니다.
     *          그러다 네트워크가 한 번 흔들려 retries 가 동작하는 순간,
     *          - 멱등성이 꺼져 있으므로 중복 레코드가 생기고,
     *          - max.in.flight=5 와 만나 같은 파티션 안에서 순서가 뒤집힙니다.
     *          컨슈머는 이걸 알 방법이 없습니다. 재고가 음수가 되거나
     *          취소된 주문이 배송되는 것으로 며칠 뒤에 드러납니다.
     *          그때쯤이면 "acks: 1 한 줄"과 증상을 연결할 사람이 없습니다.
     *
     *  ★ 결론: 멱등성이 필요하면 enable.idempotence: true 를 "명시"하세요.
     *     그러면 충돌 시 조용히 꺼지는 대신 기동이 실패합니다.
     *     시끄러운 실패를 사는 것이 조용한 유실을 사는 것보다 언제나 낫습니다.
     *
     *  브로커 쪽 ack/ISR 동작은 Kafka 코스를 참고하세요:
     *      ../../kafka/step-04-producer/
     *      ../../kafka/step-07-delivery-semantics/
     *      ../../kafka/step-08-replication/
     * ======================================================================== */
    @Configuration
    @Profile("step02-sol6")
    public static class Answer6Config {

        /** (A) 기동 실패를 재현한다. 이 프로필로 켜면 앱이 안 뜨는 것이 정상이다. */
        @Bean
        public KafkaTemplate<String, OrderCreated> brokenKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "broken");

            props.put(ProducerConfig.ACKS_CONFIG, "1");                   // ← 충돌
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);    // ← 명시적으로 켬

            DefaultKafkaProducerFactory<String, OrderCreated> pf =
                    new DefaultKafkaProducerFactory<>(props);
            // ★ 팩토리는 지연 생성이라 빈 등록만으로는 예외가 안 납니다.
            //   실제 KafkaProducer 를 만들게 강제해야 ConfigException 이 여기서 터집니다.
            pf.createProducer().close();
            return new KafkaTemplate<>(pf);
        }
    }

    @Configuration
    @Profile("step02-sol6-silent")
    public static class Answer6SilentConfig {

        /** (B) 기동은 성공한다. 그러나 멱등성이 조용히 꺼진다. */
        @Bean
        public KafkaTemplate<String, OrderCreated> silentlyNonIdempotentTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "silent");

            props.put(ProducerConfig.ACKS_CONFIG, "1");
            // enable.idempotence 를 "쓰지 않는다" → 예외 없이 false 로 내려간다

            DefaultKafkaProducerFactory<String, OrderCreated> pf =
                    new DefaultKafkaProducerFactory<>(props);
            pf.createProducer().close();     // 예외 없이 통과한다. 그게 문제다.
            return new KafkaTemplate<>(pf);
        }
    }
}
