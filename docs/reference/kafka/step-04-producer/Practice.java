/*
 * Step 04 — 프로듀서 : CLI 로는 재현할 수 없는 실습
 *
 * Java 21 단일 파일 소스 실행. 별도 빌드 도구 없이 Kafka 배포판의 라이브러리만 씁니다.
 *
 * 실행법:
 *   docker cp Practice.java kafka-1:/tmp/
 *   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오> [토픽]'
 *
 * 시나리오:
 *   acks-compare   acks=0/1/all 을 같은 코드로 각각 20000건 보내 처리량 비교
 *   order-break    enable.idempotence=false + max.in.flight=5 → 순서 역전 재현
 *   order-safe     order-break 와 같은 코드에 enable.idempotence=true 만 다름
 *   key-route      murmur2 계산값과 실제 파티션이 일치하는지 대조
 *   callback       콜백이 어느 스레드에서 실행되는지 확인
 *   sync-vs-async  fire-and-forget / callback / sync(.get()) 처리량 비교
 *
 * order-break / order-safe 는 전송 중에 다른 창에서 브로커를 흔들어야 합니다:
 *   docker compose restart kafka-2
 */
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class Practice {

    static final String BOOTSTRAP = System.getenv().getOrDefault("BOOTSTRAP", "kafka-1:9092");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String scenario = args[0];
        String topic = args.length > 1 ? args[1] : null;

        switch (scenario) {
            case "acks-compare"  -> AcksCompare.run(topic == null ? "s04_acks" : topic);
            case "order-break"   -> OrderBreak.run(topic == null ? "s04_order" : topic, false);
            case "order-safe"    -> OrderBreak.run(topic == null ? "s04_order" : topic, true);
            case "key-route"     -> KeyRoute.run(topic == null ? "s04_acks" : topic);
            case "callback"      -> CallbackDemo.run(topic == null ? "s04_acks" : topic);
            case "sync-vs-async" -> SyncVsAsync.run(topic == null ? "s04_acks" : topic);
            default -> {
                System.out.println("알 수 없는 시나리오: " + scenario);
                usage();
            }
        }
    }

    static void usage() {
        System.out.println("""
            사용법: java -cp "/opt/kafka/libs/*" Practice.java <시나리오> [토픽]

              acks-compare    acks=0/1/all 처리량 비교
              order-break     순서 역전 재현 (enable.idempotence=false)
              order-safe      멱등 프로듀서로 순서 유지
              key-route       murmur2 계산 vs 실제 파티션
              callback        콜백 실행 스레드 확인
              sync-vs-async   전송 3패턴 처리량 비교

            전송 중 브로커 흔들기: docker compose restart kafka-2
            """);
    }

    /** 모든 시나리오가 공유하는 기본 설정. */
    static Properties baseProps() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return p;
    }

    static String payload(String orderId, String customerId, int amount) {
        return "{\"order_id\":\"" + orderId + "\",\"customer_id\":\"" + customerId
             + "\",\"amount\":" + amount + ",\"status\":\"CREATED\"}";
    }

    // =======================================================================
    // [4-3] acks 비교 — perf-test 와 달리 "같은 애플리케이션 코드" 에서 잽니다
    // =======================================================================
    static final class AcksCompare {
        static final int N = 20_000;

        static void run(String topic) {
            System.out.printf("[acks-compare] topic=%s, 각 acks 마다 %,d건%n%n", topic, N);
            Map<String, Long> result = new LinkedHashMap<>();

            for (String acks : List.of("0", "1", "all")) {
                Properties p = baseProps();
                p.put(ProducerConfig.ACKS_CONFIG, acks);
                // acks=0/1 을 명시하면 멱등이 조용히 꺼지므로, 공정한 비교를 위해
                // 세 경우 모두 명시적으로 끕니다.
                p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
                p.put(ProducerConfig.LINGER_MS_CONFIG, "5");

                long start = System.nanoTime();
                try (Producer<String, String> producer = new KafkaProducer<>(p)) {
                    for (int i = 0; i < N; i++) {
                        String key = "C%03d".formatted(i % 10 + 1);
                        producer.send(new ProducerRecord<>(topic, key,
                                payload("O-%d".formatted(2000 + i), key, 10_000)));
                    }
                    producer.flush();   // ★ flush 까지 포함해야 공정한 측정입니다
                }
                long ms = (System.nanoTime() - start) / 1_000_000;
                result.put(acks, ms);
                System.out.printf("  acks=%-4s  %,6d ms   %,8d msg/s%n",
                        acks, ms, ms == 0 ? 0 : N * 1000L / ms);
            }

            long base = result.get("0");
            System.out.printf("%n  acks=all 은 acks=0 의 %.0f%% 처리량입니다.%n",
                    base * 100.0 / result.get("all"));
            System.out.println("  ※ acks=0 의 '지연' 은 브로커 왕복이 아니라 소켓 쓰기까지만 잽니다.");
            System.out.println("     빠른 게 아니라 재는 대상이 다릅니다. (4-4 의 함정과 같은 뿌리)");
        }
    }

    // =======================================================================
    // [4-11] 최대 함정 D — max.in.flight > 1 + retries = 순서 뒤바뀜
    // =======================================================================
    static final class OrderBreak {
        static final int N = 200;

        static void run(String topic, boolean idempotent) {
            String label = idempotent ? "order-safe" : "order-break";

            Properties p = baseProps();
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, String.valueOf(idempotent));
            if (idempotent) {
                // 멱등을 켜면 acks=all, retries=MAX, max.in.flight<=5 가 강제됩니다.
                // 여기서 acks=1 을 명시하면 ConfigException 이 납니다.
                p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
            } else {
                p.put(ProducerConfig.ACKS_CONFIG, "1");
                p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
                p.put(ProducerConfig.RETRIES_CONFIG, "10");
            }
            // 배치를 극단적으로 잘게 쪼개 in-flight 요청 수를 늘립니다.
            p.put(ProducerConfig.BATCH_SIZE_CONFIG, "64");
            p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
            p.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "5");
            // 짧은 request.timeout.ms 로 타임아웃 → 재시도를 유발합니다.
            p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "300");
            p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");

            System.out.printf("[%s] enable.idempotence=%s, max.in.flight=5, batch.size=64%n",
                    label, idempotent);
            System.out.printf("[%s] %s 에 seq-000 ~ seq-%03d 를 같은 키(K)로 전송합니다.%n",
                    label, topic, N - 1);
            System.out.printf("[%s] 전송 중 브로커를 흔들어 재시도를 유발하십시오:%n", label);
            System.out.printf("              docker compose restart kafka-2%n");

            AtomicInteger errors = new AtomicInteger();
            AtomicInteger retries = new AtomicInteger();

            try (Producer<String, String> producer = new KafkaProducer<>(p)) {
                for (int i = 0; i < N; i++) {
                    String v = "seq-%03d".formatted(i);
                    producer.send(new ProducerRecord<>(topic, "K", v), (md, ex) -> {
                        if (ex != null) errors.incrementAndGet();
                    });
                    // 전송을 60초 정도로 늘려 브로커를 흔들 시간을 줍니다.
                    sleep(300);
                }
                producer.flush();
                // 재시도 횟수는 프로듀서 메트릭에서 읽습니다.
                producer.metrics().forEach((name, metric) -> {
                    if ("record-retry-total".equals(name.name())) {
                        Object v = metric.metricValue();
                        if (v instanceof Double d) retries.set((int) (double) d);
                    }
                });
            }

            System.out.printf("[%s] sent seq-000 .. seq-%03d (%d건), 재시도 발생: %d회, 실패: %d건%n",
                    label, N - 1, N, retries.get(), errors.get());
            if (retries.get() == 0) {
                System.out.printf("[%s] ⚠️ 재시도가 0회였습니다. 함정이 재현되지 않습니다.%n", label);
                System.out.printf("[%s]    토픽을 재생성하고, 전송 중에 브로커를 재시작한 뒤 다시 실행하십시오.%n", label);
            }
            System.out.printf("[%s] 완료. 아래 명령으로 로그 순서를 확인하십시오.%n", label);
            System.out.printf("""
                  docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \\
                    --bootstrap-server %s --topic %s --from-beginning \\
                    --max-messages %d --timeout-ms 20000 --property print.offset=true
                %n""", BOOTSTRAP, topic, N);
        }
    }

    // =======================================================================
    // [4-7] 키 라우팅 — murmur2 계산값과 실제 파티션 대조
    // =======================================================================
    static final class KeyRoute {
        static void run(String topic) throws Exception {
            Properties p = baseProps();
            p.put(ProducerConfig.ACKS_CONFIG, "all");

            try (Producer<String, String> producer = new KafkaProducer<>(p)) {
                int numPartitions = producer.partitionsFor(topic).size();
                System.out.printf("[key-route] topic=%s, partitions=%d%n%n", topic, numPartitions);
                System.out.println("  키      murmur2 계산   실제    일치");
                System.out.println("  ------  ------------   ----    ----");

                boolean allMatch = true;
                for (int i = 1; i <= 10; i++) {
                    String key = "C%03d".formatted(i);
                    byte[] kb = key.getBytes("UTF-8");
                    // 프로듀서 내부와 동일한 계산식입니다.
                    int expected = Utils.toPositive(Utils.murmur2(kb)) % numPartitions;

                    RecordMetadata md = producer
                            .send(new ProducerRecord<>(topic, key, payload("O-9%03d".formatted(i), key, 1000)))
                            .get();

                    boolean match = expected == md.partition();
                    allMatch &= match;
                    System.out.printf("  %-6s  %12d   %4d    %s%n",
                            key, expected, md.partition(), match ? "O" : "X");
                }
                System.out.printf("%n  전부 일치: %s%n", allMatch ? "예" : "아니오");
                System.out.println("  → 파티셔너는 결정적입니다. 같은 키는 언제나 같은 파티션으로 갑니다.");
                System.out.println("  → 단, 파티션 수가 바뀌면 이 표 전체가 바뀝니다 (Step 03 의 함정).");
            }
        }
    }

    // =======================================================================
    // [4-13] 콜백이 실행되는 스레드
    // =======================================================================
    static final class CallbackDemo {
        static void run(String topic) {
            Properties p = baseProps();
            p.put(ProducerConfig.ACKS_CONFIG, "all");
            p.put(ProducerConfig.LINGER_MS_CONFIG, "5");

            System.out.printf("[callback] main 스레드 = %s%n", Thread.currentThread().getName());

            try (Producer<String, String> producer = new KafkaProducer<>(p)) {
                for (int i = 0; i < 3; i++) {
                    String key = "C%03d".formatted(i + 1);
                    producer.send(new ProducerRecord<>(topic, key, payload("O-8%03d".formatted(i), key, 5000)),
                            (md, ex) -> {
                                if (ex != null) {
                                    System.out.println("  전송 실패: " + ex);
                                    return;
                                }
                                System.out.printf("  콜백 스레드 = %-38s  p=%d off=%d%n",
                                        Thread.currentThread().getName(), md.partition(), md.offset());
                            });
                }
                producer.flush();
            }
            System.out.println();
            System.out.println("  콜백은 Sender 스레드(kafka-producer-network-thread) 하나에서 실행됩니다.");
            System.out.println("  ⚠️ 여기서 DB 조회나 HTTP 호출을 하면 모든 파티션의 전송이 멈춥니다.");
            System.out.println("  ⚠️ 콜백 안에서 producer.close() 를 부르면 데드락입니다.");
            System.out.println("     (Sender 스레드가 자기 자신의 종료를 기다리게 됩니다)");
        }
    }

    // =======================================================================
    // [4-13] 전송 3패턴 처리량 비교
    // =======================================================================
    static final class SyncVsAsync {
        static final int N = 10_000;

        static void run(String topic) throws Exception {
            System.out.printf("[sync-vs-async] topic=%s, 각 패턴 %,d건, acks=all, linger.ms=5%n%n", topic, N);

            long ff = fireAndForget(topic);
            long cb = withCallback(topic);
            long sy = synchronous(topic);

            System.out.printf("  fire-and-forget : %6d ms  %6d msg/s   (실패를 감지할 수 없음)%n",
                    ff, N * 1000L / Math.max(ff, 1));
            System.out.printf("  callback        : %6d ms  %6d msg/s   (실패 감지 가능)%n",
                    cb, N * 1000L / Math.max(cb, 1));
            System.out.printf("  sync (.get())   : %6d ms  %6d msg/s   (실패 감지 가능)%n",
                    sy, N * 1000L / Math.max(sy, 1));
            System.out.printf("%n  → sync 는 callback 대비 %.1f배 느립니다.%n", (double) sy / cb);
            System.out.println("  → 실무 기본값은 callback 입니다. .get() 은 초기화·마이그레이션 등 소량 전송에만.");
        }

        static Properties props() {
            Properties p = baseProps();
            p.put(ProducerConfig.ACKS_CONFIG, "all");
            p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
            return p;
        }

        static long fireAndForget(String topic) {
            long start = System.nanoTime();
            try (Producer<String, String> producer = new KafkaProducer<>(props())) {
                for (int i = 0; i < N; i++) {
                    String key = "C%03d".formatted(i % 10 + 1);
                    // 반환값을 버립니다 — 브로커 거절을 영영 모릅니다.
                    producer.send(new ProducerRecord<>(topic, key, payload("O-A%05d".formatted(i), key, 1000)));
                }
                producer.flush();
            }
            return (System.nanoTime() - start) / 1_000_000;
        }

        static long withCallback(String topic) {
            AtomicInteger failed = new AtomicInteger();
            long start = System.nanoTime();
            try (Producer<String, String> producer = new KafkaProducer<>(props())) {
                for (int i = 0; i < N; i++) {
                    String key = "C%03d".formatted(i % 10 + 1);
                    producer.send(new ProducerRecord<>(topic, key, payload("O-B%05d".formatted(i), key, 1000)),
                            (md, ex) -> { if (ex != null) failed.incrementAndGet(); });
                }
                producer.flush();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("    (callback 실패 감지: %d건)%n", failed.get());
            return ms;
        }

        static long synchronous(String topic) throws Exception {
            long start = System.nanoTime();
            try (Producer<String, String> producer = new KafkaProducer<>(props())) {
                for (int i = 0; i < N; i++) {
                    String key = "C%03d".formatted(i % 10 + 1);
                    // .get() 이 응답을 기다립니다 → 배치가 항상 1건짜리가 됩니다.
                    producer.send(new ProducerRecord<>(topic, key, payload("O-C%05d".formatted(i), key, 1000)))
                            .get();
                }
            }
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
