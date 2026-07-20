/*
 * Step 06 — 오프셋 관리 : Java 실습
 *
 * CLI 로는 재현할 수 없는 것 — "처리와 커밋 사이의 타이밍" — 을 다룹니다.
 *
 * 실행법 (Java 21 single-file source 실행. 별도 빌드 도구가 필요 없습니다):
 *
 *   docker cp Practice.java kafka-1:/tmp/
 *   docker exec kafka-1 sh -c 'cd /tmp && java -cp "/opt/kafka/libs/*" Practice.java <시나리오>'
 *
 * 시나리오:
 *   autocommit-loss          자동 커밋이 만드는 유실 (60건 처리 후 halt)
 *   autocommit-loss-resume   재접속해서 유실 건수 집계
 *   autocommit-dup           자동 커밋이 만드는 중복 (100건 처리, 커밋 40에서 halt)
 *   autocommit-dup-resume    재접속해서 중복 건수 집계
 *   manual-sync              enable.auto.commit=false + commitSync()
 *   manual-async             enable.auto.commit=false + commitAsync()
 *   manual-per-record        10건마다 특정 오프셋 커밋 (offset + 1, metadata 포함)
 *   seek                     seek / seekToEnd / seekToBeginning
 *
 * 환경 변수로 덮어쓸 수 있습니다 (연습문제용):
 *   S06_BOOTSTRAP        기본 kafka-1:9092
 *   S06_TOPIC            기본 s06_orders
 *   S06_GROUP            기본은 시나리오별 기본 그룹명
 *   S06_MAX_POLL         autocommit-loss 의 max.poll.records
 *   S06_COMMIT_INTERVAL  autocommit-loss 의 auto.commit.interval.ms
 *
 * 주의:
 *   유실/중복 시나리오는 System.exit() 이 아니라 Runtime.getRuntime().halt() 로 죽습니다.
 *   System.exit() 은 셧다운 훅을 돌려 컨슈머를 정상 close 하고, 그 과정에서
 *   오프셋을 커밋해 버리므로 유실도 중복도 재현되지 않습니다.
 *   halt() 는 kill -9 와 같아서 아무것도 정리하지 않습니다.
 */

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class Practice {

    static final String BOOTSTRAP = env("S06_BOOTSTRAP", "kafka-1:9092");
    static final String TOPIC     = env("S06_TOPIC", "s06_orders");
    static final Path   COUNT_FILE = Path.of("/tmp/s06-count.txt");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "autocommit-loss"        -> AutoCommitLoss.run();
            case "autocommit-loss-resume" -> AutoCommitLoss.resume();
            case "autocommit-dup"         -> AutoCommitDup.run();
            case "autocommit-dup-resume"  -> AutoCommitDup.resume();
            case "manual-sync"            -> ManualCommit.sync();
            case "manual-async"           -> ManualCommit.async();
            case "manual-per-record"      -> ManualCommit.perRecord();
            case "seek"                   -> Seek.run();
            default -> {
                System.out.println("알 수 없는 시나리오: " + args[0]);
                usage();
            }
        }
    }

    static void usage() {
        System.out.println("""
            사용법: java -cp "/opt/kafka/libs/*" Practice.java <시나리오>

              autocommit-loss          자동 커밋 유실 재현
              autocommit-loss-resume   유실 건수 집계
              autocommit-dup           자동 커밋 중복 재현
              autocommit-dup-resume    중복 건수 집계
              manual-sync              commitSync()
              manual-async             commitAsync()
              manual-per-record        특정 오프셋 커밋 (offset + 1)
              seek                     seek / seekToEnd / seekToBeginning
            """);
    }

    // -----------------------------------------------------------------------
    // 공통 유틸
    // -----------------------------------------------------------------------

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    static Properties baseProps(String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, env("S06_GROUP", groupId));
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 리밸런싱을 빠르게 하려고 줄였습니다. 운영 기본값은 45000 / 3000 입니다.
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");
        return p;
    }

    /** "무거운 처리" 를 흉내냅니다. 건당 50ms. */
    static void process(ConsumerRecord<String, String> r) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeCount(int n) {
        try {
            Files.writeString(COUNT_FILE, String.valueOf(n));
        } catch (IOException e) {
            System.out.println("[warn] 카운트 파일 기록 실패: " + e.getMessage());
        }
    }

    static int readCount() {
        try {
            return Integer.parseInt(Files.readString(COUNT_FILE).trim());
        } catch (Exception e) {
            System.out.println("[warn] 1회차 카운트를 읽지 못했습니다. 0 으로 간주합니다.");
            return 0;
        }
    }

    /** kill -9 와 동일하게 즉사합니다. 셧다운 훅도, close() 도 실행되지 않습니다. */
    static void die() {
        System.out.println("=== 여기서 프로세스가 죽습니다 ===");
        System.out.flush();
        Runtime.getRuntime().halt(1);
    }

    // -----------------------------------------------------------------------
    // 함정 B — enable.auto.commit=true 가 만드는 유실
    // -----------------------------------------------------------------------
    static class AutoCommitLoss {

        static final String GROUP = "s06-loss";
        static final int    STOP_AT = 60;    // 60건째까지만 처리하고 죽습니다

        static void run() {
            String maxPoll  = env("S06_MAX_POLL", "10");
            String interval = env("S06_COMMIT_INTERVAL", "1000");

            Properties p = baseProps(GROUP);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            p.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, interval);
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPoll);

            System.out.printf("[setup] 그룹 %s, enable.auto.commit=true, "
                            + "auto.commit.interval.ms=%s, max.poll.records=%s%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG), interval, maxPoll);
            System.out.println("[setup] 건당 처리 시간 50ms. 배치당 "
                    + (Integer.parseInt(maxPoll) * 50) + "ms 가 걸립니다.");

            int processed = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));
                int pollCount = 0;
                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    pollCount++;
                    if (records.isEmpty()) {
                        if (pollCount > 20) break;
                        continue;
                    }
                    System.out.printf("[poll #%d] %d건 수신 "
                                    + "→ 직전 배치의 오프셋이 이 시점에 자동 커밋될 수 있습니다%n",
                            pollCount, records.count());

                    for (ConsumerRecord<String, String> r : records) {
                        process(r);
                        processed++;
                        System.out.printf("처리 %d건, offset=%d%n", processed, r.offset());
                        if (processed >= STOP_AT) {
                            writeCount(processed);
                            System.out.printf("[상태] 처리 완료 %d건. "
                                    + "그런데 커밋은 이미 더 앞서 있습니다.%n", processed);
                            die();
                        }
                    }
                }
            }
            writeCount(processed);
            System.out.println("[끝] 처리 " + processed + "건 (죽지 않고 끝났습니다)");
        }

        static void resume() {
            int first = readCount();

            Properties p = baseProps(GROUP);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

            System.out.println("[resume] 그룹 " + p.get(ConsumerConfig.GROUP_ID_CONFIG)
                    + " 로 재접속합니다.");

            int second = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> r : records) {
                        second++;
                        System.out.printf("추가 처리 %d건, offset=%d%n", second, r.offset());
                    }
                    if (second > 0 && records.isEmpty()) break;
                }
            }

            long total = countTopic();
            System.out.printf("[resume] %d초 동안 %d건 수신.%n", 30, second);
            System.out.printf("[집계] 총 처리 건수: %d / 발행 건수: %d → 유실 %d건%n",
                    first + second, total, total - (first + second));
        }
    }

    // -----------------------------------------------------------------------
    // 함정 C — 같은 auto commit 이 만드는 중복
    // -----------------------------------------------------------------------
    static class AutoCommitDup {

        static final String GROUP = "s06-dup";

        static void run() {
            Properties p = baseProps(GROUP);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            // 30초. 사실상 커밋이 거의 일어나지 않게 만듭니다.
            p.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "30000");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "40");

            System.out.printf("[setup] 그룹 %s, auto.commit.interval.ms=30000%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            int processed = 0;
            long lastCommitted = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));
                long total = countTopic();
                int emptyPolls = 0;
                while (processed < total) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) {
                        if (++emptyPolls > 20) break;
                        continue;
                    }
                    emptyPolls = 0;
                    if (processed > 0) {
                        System.out.println("[poll] 새 배치 요청 → 30초가 안 지나 커밋하지 않음");
                    }
                    for (ConsumerRecord<String, String> r : records) {
                        process(r);
                        processed++;
                        System.out.printf("처리 %d건, offset=%d%n", processed, r.offset());
                    }
                    OffsetAndMetadata om = consumer.committed(
                            Set.of(new TopicPartition(TOPIC, 0))).get(new TopicPartition(TOPIC, 0));
                    lastCommitted = (om == null) ? 0 : om.offset();
                }
                System.out.printf("[상태] 처리 완료 %d건 / 마지막 커밋 오프셋 %d%n",
                        processed, lastCommitted);
                writeCount(processed);
                die();
            }
        }

        static void resume() {
            int first = readCount();

            Properties p = baseProps(GROUP);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

            System.out.println("[resume] 그룹 " + p.get(ConsumerConfig.GROUP_ID_CONFIG)
                    + " 로 재접속합니다. 커밋된 오프셋부터 다시 읽습니다.");

            int second = 0;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));
                int emptyPolls = 0;
                while (emptyPolls < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { emptyPolls++; continue; }
                    emptyPolls = 0;
                    for (ConsumerRecord<String, String> r : records) {
                        second++;
                        System.out.printf("재처리 %d건, offset=%d%n", second, r.offset());
                    }
                    consumer.commitSync();
                }
            }

            long total = countTopic();
            System.out.printf("[집계] 1회차 %d건 + 2회차 %d건 = %d건 처리 / 발행 %d건 → 중복 %d건%n",
                    first, second, first + second, total, (first + second) - total);
        }
    }

    // -----------------------------------------------------------------------
    // 수동 커밋
    // -----------------------------------------------------------------------
    static class ManualCommit {

        /** 6-6-1 : 배치마다 commitSync(). 블로킹하지만 확실합니다. */
        static void sync() {
            Properties p = baseProps("s06-manual");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

            System.out.printf("[setup] 그룹 %s, enable.auto.commit=false%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            int processed = 0, commits = 0;
            long commitNanos = 0;
            long start = System.nanoTime();

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
            try {
                consumer.subscribe(List.of(TOPIC));
                long total = countTopic();
                int emptyPolls = 0;
                while (processed < total && emptyPolls < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { emptyPolls++; continue; }
                    emptyPolls = 0;
                    for (ConsumerRecord<String, String> r : records) {
                        process(r);              // 처리를 먼저
                        processed++;
                    }
                    long t0 = System.nanoTime();
                    consumer.commitSync();       // 다 처리한 뒤 커밋
                    commitNanos += System.nanoTime() - t0;
                    commits++;
                    System.out.printf("[batch] %d건 수신 → 처리 → commitSync() 완료 (offset=%d)%n",
                            records.count(), processed);
                }
                System.out.printf("[집계] 처리 %d건, 커밋 %d. 유실 0, 중복 0%n", processed, processed);
                System.out.printf("[측정] 총 소요 %.2f초 (커밋 %d회, 커밋당 평균 %.1fms)%n",
                        (System.nanoTime() - start) / 1e9, commits,
                        commits == 0 ? 0.0 : commitNanos / 1e6 / commits);
            } catch (WakeupException e) {
                // 정상 종료 요청. 무시합니다.
            } finally {
                try {
                    consumer.commitSync();       // 마지막 한 번은 반드시 동기 커밋
                } finally {
                    consumer.close(Duration.ofSeconds(5));
                }
            }
        }

        /** 6-6-2 : 정상 루프는 commitAsync(), finally 에서만 commitSync(). 정석 패턴입니다. */
        static void async() {
            Properties p = baseProps("s06-async");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

            System.out.printf("[setup] 그룹 %s, commitAsync + 콜백%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            int processed = 0, commits = 0;
            long blockNanos = 0;
            long start = System.nanoTime();

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
            try {
                consumer.subscribe(List.of(TOPIC));
                long total = countTopic();
                int emptyPolls = 0;
                while (processed < total && emptyPolls < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { emptyPolls++; continue; }
                    emptyPolls = 0;
                    for (ConsumerRecord<String, String> r : records) {
                        process(r);
                        processed++;
                    }
                    long t0 = System.nanoTime();
                    consumer.commitAsync((offsets, exception) -> {
                        if (exception != null) {
                            // commitAsync 는 재시도하지 않습니다.
                            // 여기서 다시 commitAsync 를 부르면 오프셋이 뒤로 갈 수 있습니다.
                            System.out.println("[commit 실패] " + offsets + " : " + exception);
                        }
                    });
                    blockNanos += System.nanoTime() - t0;
                    commits++;
                }
                System.out.printf("[집계] 처리 %d건, 커밋 %d. 유실 0, 중복 0%n", processed, processed);
                System.out.printf("[측정] 총 소요 %.2f초 (커밋 %d회, 블로킹 시간 %.1fms)%n",
                        (System.nanoTime() - start) / 1e9, commits, blockNanos / 1e6);
            } catch (WakeupException e) {
                // 정상 종료 요청.
            } finally {
                try {
                    consumer.commitSync();       // 마지막 커밋에는 "다음 커밋" 이 없습니다
                } finally {
                    consumer.close(Duration.ofSeconds(5));
                }
            }
        }

        /** 6-6-3 : 10건마다 특정 오프셋을 명시 커밋. 값은 마지막 처리 오프셋 + 1 입니다. */
        static void perRecord() {
            Properties p = baseProps("s06-per-record");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");

            System.out.printf("[setup] 그룹 %s, 10건마다 특정 오프셋 커밋%n",
                    p.get(ConsumerConfig.GROUP_ID_CONFIG));

            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            int processed = 0;
            long batchStart = -1;

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));
                long total = countTopic();
                int emptyPolls = 0;
                while (processed < total && emptyPolls < 10) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    if (records.isEmpty()) { emptyPolls++; continue; }
                    emptyPolls = 0;
                    for (ConsumerRecord<String, String> r : records) {
                        if (batchStart < 0) batchStart = r.offset();
                        process(r);
                        processed++;
                        // ★ 커밋할 값은 "마지막 처리 오프셋 + 1" 입니다.
                        //    r.offset() 을 그대로 쓰면 LAG 이 항상 1 로 남습니다.
                        offsets.put(new TopicPartition(r.topic(), r.partition()),
                                    new OffsetAndMetadata(r.offset() + 1, "processed-by-worker-3"));
                        if (processed % 10 == 0) {
                            consumer.commitSync(offsets);
                            System.out.printf("처리 offset=%d..%d → commitSync({%s-%d: %d})%n",
                                    batchStart, r.offset(), r.topic(), r.partition(), r.offset() + 1);
                            offsets.clear();
                            batchStart = -1;
                        }
                    }
                }
                if (!offsets.isEmpty()) consumer.commitSync(offsets);
                System.out.printf("[집계] 처리 %d건, 커밋 %d%n", processed, processed);
                System.out.println("[확인] __consumer_offsets 를 조회하면 "
                        + "metadata=processed-by-worker-3 이 보입니다.");
            }
        }
    }

    // -----------------------------------------------------------------------
    // seek / seekToBeginning / seekToEnd
    // -----------------------------------------------------------------------
    static class Seek {

        static void run() {
            Properties p = baseProps("s06-seek");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

            TopicPartition tp = new TopicPartition(TOPIC, 0);

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                consumer.subscribe(List.of(TOPIC));

                // ★ 이 빈 poll 이 없으면 파티션이 아직 할당되지 않아
                //    IllegalStateException: No current assignment for partition ... 이 납니다.
                //    seek 관련 가장 흔한 실수입니다.
                consumer.poll(Duration.ofMillis(0));
                while (consumer.assignment().isEmpty()) {
                    consumer.poll(Duration.ofMillis(200));
                }
                System.out.println("[assign] 할당된 파티션: " + consumer.assignment());

                // (1) 오프셋 30 으로
                consumer.seek(tp, 30);
                System.out.println("[seek] " + tp + " → offset 30");
                readSome(consumer, 10);

                // (2) 끝으로
                consumer.seekToEnd(List.of(tp));
                System.out.println("[seekToEnd] " + tp + " → offset " + consumer.position(tp));
                ConsumerRecords<String, String> none = consumer.poll(Duration.ofMillis(5000));
                System.out.printf("[poll] 5초 동안 %d건. 끝으로 이동했으므로 새 메시지만 옵니다.%n",
                        none.count());

                // (3) 처음으로
                consumer.seekToBeginning(List.of(tp));
                System.out.println("[seekToBeginning] " + tp + " → offset " + consumer.position(tp));
                readSome(consumer, 1);

                System.out.println("[집계] seek 로 위치를 3번 바꿨습니다. 커밋은 하지 않았습니다.");
                System.out.println("[집계] 그래서 --describe 의 CURRENT-OFFSET 은 변하지 않습니다.");
            }
        }

        static void readSome(KafkaConsumer<String, String> consumer, int limit) {
            int n = 0;
            long deadline = System.currentTimeMillis() + 5000;
            while (n < limit && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    System.out.printf("읽음 offset=%d key=%s %s%n",
                            r.offset(), r.key(), orderIdOf(r.value()));
                    if (++n >= limit) break;
                }
            }
        }

        static String orderIdOf(String json) {
            int i = json.indexOf("\"order_id\":\"");
            if (i < 0) return "";
            int s = i + 12;
            int e = json.indexOf('"', s);
            return e < 0 ? "" : json.substring(s, e);
        }
    }

    // -----------------------------------------------------------------------
    // 토픽의 총 건수 (모든 파티션의 endOffset 합)
    // -----------------------------------------------------------------------
    static long countTopic() {
        Properties p = baseProps("s06-counter");
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "s06-counter-" + System.nanoTime());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            List<TopicPartition> tps = c.partitionsFor(TOPIC).stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
            Map<TopicPartition, Long> begin = c.beginningOffsets(tps);
            Map<TopicPartition, Long> end   = c.endOffsets(tps);
            long total = 0;
            for (TopicPartition tp : tps) total += end.get(tp) - begin.get(tp);
            return total;
        }
    }
}
