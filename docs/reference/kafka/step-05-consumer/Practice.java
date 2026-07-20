/*
 * Step 05 — 컨슈머와 컨슈머 그룹 : CLI 로는 제어할 수 없는 실습
 *
 * Java 21 단일 파일 소스 실행. 별도 빌드 도구 없이 Kafka 배포판의 라이브러리만 씁니다.
 *
 * 실행법:
 *   docker cp Practice.java kafka-1:/tmp/
 *   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
 *
 * 시나리오:
 *   consume        ConsumerRebalanceListener 로 revoke/assign 콜백을 관찰
 *   slow-consumer  max.poll.interval.ms 초과 → LeaveGroup → CommitFailedException 재현
 *   assignor       컨슈머 3개를 스레드로 띄워 할당 전략별 배분 비교
 *                    Practice.java assignor range|roundrobin|sticky|cooperative-sticky
 *   static-member  group.instance.id 로 롤링 재시작 시 리밸런싱을 없앤다
 *
 * 환경변수:
 *   TOPIC              대상 토픽            (기본 s05_orders)
 *   BOOTSTRAP          부트스트랩 서버       (기본 kafka-1:9092)
 *   MAX_POLL_RECORDS   slow-consumer 전용   (기본 5)
 *   PROCESS_MS         slow-consumer 전용   (기본 4000)
 *   RUN_SECONDS        consume/assignor 실행 시간 (기본 20)
 */
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class Practice {

    static final String BOOTSTRAP = System.getenv().getOrDefault("BOOTSTRAP", "kafka-1:9092");
    static final String TOPIC     = System.getenv().getOrDefault("TOPIC", "s05_orders");
    static final int RUN_SECONDS  = Integer.parseInt(System.getenv().getOrDefault("RUN_SECONDS", "20"));

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        String scenario = args[0];
        String arg1 = args.length > 1 ? args[1] : null;

        switch (scenario) {
            case "consume"       -> Consume.run();
            case "slow-consumer" -> SlowConsumer.run();
            case "assignor"      -> Assignor.run(arg1 == null ? "range" : arg1);
            case "static-member" -> StaticMember.run();
            default -> { System.out.println("알 수 없는 시나리오: " + scenario); usage(); }
        }
    }

    static void usage() {
        System.out.println("""
            사용법: java -cp "/opt/kafka/libs/*" Practice.java <시나리오> [인자]

              consume         리밸런스 콜백 관찰 (두 개 띄우고 하나를 죽여 보십시오)
              slow-consumer   max.poll.interval.ms 초과 재현 (스스로 끝나지 않습니다)
              assignor <전략>  range | roundrobin | sticky | cooperative-sticky
              static-member   group.instance.id 로 리밸런싱 회피

            환경변수: TOPIC, BOOTSTRAP, MAX_POLL_RECORDS, PROCESS_MS, RUN_SECONDS
            """);
    }

    static Properties baseProps(String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // 커밋은 명시적으로 (Step 06 예고)
        return p;
    }

    static String fmt(Collection<TopicPartition> tps) {
        List<String> s = new ArrayList<>();
        for (TopicPartition tp : tps) s.add(tp.topic() + "-" + tp.partition());
        return s.toString();
    }

    /** 리밸런스 콜백을 그대로 찍어 주는 리스너. */
    static final class LoggingListener implements ConsumerRebalanceListener {
        private final String tag;
        LoggingListener(String tag) { this.tag = tag; }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            System.out.printf("  [%s] onPartitionsRevoked  %s%n", tag, fmt(partitions));
        }
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            System.out.printf("  [%s] onPartitionsAssigned %s%n", tag, fmt(partitions));
        }
        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            // Cooperative 에서만 의미 있게 호출됩니다. Eager 는 기본 구현이 Revoked 로 위임합니다.
            System.out.printf("  [%s] onPartitionsLost     %s%n", tag, fmt(partitions));
        }
    }

    // =======================================================================
    // [5-7] 리밸런스 콜백 관찰
    // =======================================================================
    static final class Consume {
        static void run() {
            Properties p = baseProps("s05-consume");
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, "s05-consume-" + (System.nanoTime() % 1000));

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::wakeup));

            System.out.printf("[consume] topic=%s group=s05-consume, %d초 동안 실행합니다.%n", TOPIC, RUN_SECONDS);
            System.out.println("[consume] 이 프로그램을 두 개 띄우고 하나를 Ctrl+C 하면 리밸런스 콜백이 보입니다.");

            long deadline = System.currentTimeMillis() + RUN_SECONDS * 1000L;
            int total = 0;
            try {
                consumer.subscribe(List.of(TOPIC), new LoggingListener("consume"));
                while (System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (!records.isEmpty()) {
                        total += records.count();
                        System.out.printf("  poll() → %d건 (누적 %d), assigned=%s%n",
                                records.count(), total, fmt(consumer.assignment()));
                        consumer.commitSync();
                    }
                }
                System.out.printf("[consume] generationId = %d  (동적 멤버는 재기동마다 오릅니다)%n",
                        consumer.groupMetadata().generationId());
            } catch (WakeupException e) {
                System.out.println("[consume] 종료 신호를 받았습니다.");
            } finally {
                // close() 가 LeaveGroup 을 보냅니다 → 즉시 리밸런싱.
                // kill -9 로 죽이면 이게 안 나가서 session.timeout.ms(45초)를 기다립니다.
                consumer.close();
                System.out.println("[consume] close() 완료 — LeaveGroup 전송됨.");
            }
        }
    }

    // =======================================================================
    // [5-10] 핵심 함정 C — max.poll.interval.ms 초과
    // =======================================================================
    static final class SlowConsumer {
        static void run() {
            int maxPollRecords = Integer.parseInt(System.getenv().getOrDefault("MAX_POLL_RECORDS", "5"));
            long processMs     = Long.parseLong(System.getenv().getOrDefault("PROCESS_MS", "4000"));
            int maxPollInterval = 10_000;

            Properties p = baseProps("s05-slow");
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, "s05-slow-1");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(maxPollRecords));
            p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(maxPollInterval));
            // session.timeout.ms 는 기본(45초)보다 짧게 두어야 max.poll.interval.ms 와의
            // 차이를 명확히 볼 수 있습니다. 하트비트는 계속 정상적으로 나갑니다.
            p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
            p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");

            System.out.printf("[slow-consumer] group=s05-slow, max.poll.interval.ms=%d, max.poll.records=%d%n",
                    maxPollInterval, maxPollRecords);
            System.out.printf("[slow-consumer] 레코드마다 %dms 씩 처리합니다. %d건 × %.1f초 = %.1f초 %s %d초%n",
                    processMs, maxPollRecords, processMs / 1000.0,
                    maxPollRecords * processMs / 1000.0,
                    maxPollRecords * processMs > maxPollInterval ? ">" : "<",
                    maxPollInterval / 1000);
            if (maxPollRecords * processMs > maxPollInterval) {
                System.out.println("[slow-consumer] → 반드시 쫓겨납니다. 이 프로그램은 스스로 끝나지 않습니다.");
                System.out.println("[slow-consumer] → 해결: MAX_POLL_RECORDS=1 환경변수를 주고 다시 실행하십시오.");
            } else {
                System.out.println("[slow-consumer] → 한 루프가 타임아웃보다 짧으므로 정상 동작합니다.");
            }
            System.out.println();

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(baseWith(p));
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::wakeup));

            try {
                consumer.subscribe(List.of(TOPIC), new LoggingListener("slow"));
                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    if (records.isEmpty()) continue;

                    System.out.printf("  poll() → %d건 수신 %s%n", records.count(), fmt(consumer.assignment()));
                    for (ConsumerRecord<String, String> r : records) {
                        String id = extract(r.value(), "order_id");
                        System.out.printf("    처리 %s ... (%dms)%n", id, processMs);
                        sleep(processMs);
                    }
                    try {
                        consumer.commitSync();
                        System.out.println("  commitSync() 성공 — 오프셋이 전진했습니다.");
                    } catch (CommitFailedException e) {
                        System.out.println("  commitSync() 시도...");
                        System.out.println("  X CommitFailedException: " + e.getMessage());
                        System.out.println("    → 이미 그룹 밖이라 커밋할 권한이 없습니다.");
                        System.out.println("    → 재합류 후 커밋되지 않은 같은 메시지를 다시 받습니다. (무한 반복)");
                    }
                }
            } catch (WakeupException e) {
                System.out.println("[slow-consumer] 종료 신호.");
            } finally {
                consumer.close(Duration.ofSeconds(3));
            }
        }

        static Properties baseWith(Properties p) { return p; }
    }

    // =======================================================================
    // [5-8] 할당 전략 비교 — 한 프로세스 안에서 컨슈머 3개
    // =======================================================================
    static final class Assignor {
        static void run(String strategy) throws Exception {
            String clazz = switch (strategy) {
                case "range"              -> "org.apache.kafka.clients.consumer.RangeAssignor";
                case "roundrobin"         -> "org.apache.kafka.clients.consumer.RoundRobinAssignor";
                case "sticky"             -> "org.apache.kafka.clients.consumer.StickyAssignor";
                case "cooperative-sticky" -> "org.apache.kafka.clients.consumer.CooperativeStickyAssignor";
                default -> throw new IllegalArgumentException(
                        "전략은 range|roundrobin|sticky|cooperative-sticky 중 하나입니다: " + strategy);
            };

            String group = "s05-assignor-" + strategy;
            System.out.printf("[assignor] strategy=%s, group=%s, 컨슈머 3개를 스레드로 띄웁니다.%n%n",
                    strategy, group);

            CountDownLatch done = new CountDownLatch(3);
            List<Thread> threads = new ArrayList<>();

            for (int i = 1; i <= 3; i++) {
                final int idx = i;
                Thread t = new Thread(() -> {
                    Properties p = baseProps(group);
                    p.put(ConsumerConfig.CLIENT_ID_CONFIG, "assignor-" + idx);
                    p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, clazz);

                    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
                        c.subscribe(List.of(TOPIC));
                        long deadline = System.currentTimeMillis() + RUN_SECONDS * 1000L;
                        boolean printed = false;
                        while (System.currentTimeMillis() < deadline) {
                            c.poll(Duration.ofMillis(500));
                            if (!printed && !c.assignment().isEmpty()) {
                                System.out.printf("  컨슈머 %d  #PARTITIONS=%d  %s  (generation=%d)%n",
                                        idx, c.assignment().size(), fmt(c.assignment()),
                                        c.groupMetadata().generationId());
                                printed = true;
                            }
                        }
                        if (!printed) {
                            System.out.printf("  컨슈머 %d  #PARTITIONS=0  []   ★ 아무것도 할당받지 못했습니다%n", idx);
                        }
                    } finally {
                        done.countDown();
                    }
                }, "assignor-" + idx);
                threads.add(t);
                t.start();
                sleep(1500);   // 순차적으로 합류시켜 리밸런싱을 관찰합니다
            }

            done.await();
            for (Thread t : threads) t.join();

            System.out.println();
            System.out.println("  ※ 토픽이 하나면 네 전략의 결과가 거의 같습니다.");
            System.out.println("     차이는 (1) 토픽이 여러 개일 때(Range 의 쏠림)와");
            System.out.println("            (2) 리밸런싱이 일어날 때(Sticky 의 유지, Cooperative 의 무정지)");
            System.out.println("     에서 드러납니다. 본문 5-8 / 5-9 를 보십시오.");
        }
    }

    // =======================================================================
    // [5-12] 정적 멤버십
    // =======================================================================
    static final class StaticMember {
        static void run() {
            Properties p = baseProps("s05-static");
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, "s05-static-1");
            p.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "s05-static-1");   // ★ 이 한 줄이 전부입니다
            p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "60000");

            System.out.println("[static-member] group.instance.id=s05-static-1 로 합류합니다.");

            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
                c.subscribe(List.of(TOPIC), new LoggingListener("static"));
                long deadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < deadline) {
                    c.poll(Duration.ofMillis(500));
                    if (!c.assignment().isEmpty()) break;
                }
                // 할당이 잡힌 뒤에도 잠시 더 돌려 상태를 안정화시킵니다
                long until = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < until) c.poll(Duration.ofMillis(500));

                System.out.printf("  assigned = %s%n", fmt(c.assignment()));
                System.out.printf("  generationId = %d%n", c.groupMetadata().generationId());
                System.out.println("  상태 확인: kcg --describe --group s05-static --state");
                System.out.println("  15초 후 종료합니다. 종료 뒤 60초 안에 다시 실행하면");
                System.out.println("  리밸런싱 없이 같은 파티션을 받고 generationId 도 그대로입니다.");
            }
            // ★ 정적 멤버는 close() 해도 LeaveGroup 을 보내지 않습니다.
            //    코디네이터는 "잠깐 자리를 비웠다" 고 간주하고 멤버로 유지합니다.
            //    그래서 종료 직후 --state 가 여전히 Stable / #MEMBERS=1 입니다.
            System.out.println("[static-member] 종료. (LeaveGroup 을 보내지 않았습니다)");
        }
    }

    // ---- 유틸 -------------------------------------------------------------

    static String extract(String json, String field) {
        if (json == null) return "?";
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return "?";
        int s = i + key.length();
        int e = json.indexOf('"', s);
        return e < 0 ? "?" : json.substring(s, e);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
