package com.example.order;

/*
 * Step 06 — 타이머와 대기 / 연습문제 (6문제)
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Exercise --args="1"
 *   ./gradlew run -PmainClass=com.example.order.Exercise --args="cleanup"
 *
 * 각 문제의 "// TODO: 여기에 작성" 을 채우세요. 정답은 Solution.java.
 *
 * ⚠️ 문제 1 은 90일 타이머를 만듭니다. 실습이 끝나면 반드시 terminate 하세요.
 *    방치하면 90일 뒤에 깨어납니다.
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;

public class Exercise {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {}

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String estimateDelivery(String orderId, String address);
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = Workflow.getLogger(InventoryActivityImpl.class);
        @Override public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 창고 조회 {}", orderId, sku);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "RSV-" + orderId + "-" + sku;
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = Workflow.getLogger(ShippingActivityImpl.class);
        @Override public String estimateDelivery(String orderId, String address) {
            log.info("[{}] 배송 예상일 조회 (20초 소요)", orderId);
            try { Thread.sleep(20_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "2026-03-20";
        }
    }

    @WorkflowInterface
    public interface SimpleWorkflow {
        @WorkflowMethod String run(String orderId);
        @SignalMethod void approve();
    }

    // ==================================================================
    // 문제 1 — 90일 타이머
    //
    // Q1Workflow 가 90일을 sleep 하도록 완성하고, TimerStarted 의
    // startToFireTimeout 이 몇 초로 기록될지 상수에 적으세요.
    //
    // 실행 후 확인:
    //   temporal workflow show -w sub-Q1 --output json \
    //     | jq -r '.events[] | select(.eventType=="EVENT_TYPE_TIMER_STARTED")
    //              .timerStartedEventAttributes.startToFireTimeout'
    // ==================================================================
    static final long Q1_EXPECTED_SECONDS = 0;   // TODO: 여기에 작성 — 90일은 몇 초인가

    public static class Q1Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q1Workflow.class);
        @Override
        public String run(String orderId) {
            log.info("[{}] 90일 만료 타이머 시작", orderId);
            // TODO: 여기에 작성 — 90일 대기
            return orderId + " EXPIRED";
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 문제 2 — Worker 를 죽였다 살려도 타이머가 이어진다
    //
    // Q2Workflow(45초 타이머)를 띄운 뒤 아래 절차를 직접 수행하고,
    // 관찰한 값을 주석에 채워 넣으세요. 코드를 고칠 필요는 없습니다.
    //
    //   1) ./gradlew run -PmainClass=com.example.order.Exercise --args="2"
    //   2) 다른 터미널: pkill -f "com.example.order.Exercise"
    //   3) temporal task-queue describe --task-queue ORDER_TASK_QUEUE --task-queue-type workflow
    //      → Poller Information 이 비어 있는지 확인
    //   4) sleep 60
    //   5) temporal workflow describe -w order-6202
    //   6) ./gradlew run -PmainClass=com.example.order.Exercise --args="2"   (다시 띄우기)
    //
    // TODO: 여기에 작성 — 5) 시점의 관찰 결과
    //   Status              = ________
    //   History Length      = ________
    //   마지막 이벤트 타입   = ________
    //   Pending Workflow Task 의 State = ________
    //   6) 이후 워크플로우가 이어지기까지 걸린 시간 = ________
    // ==================================================================
    public static class Q2Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q2Workflow.class);
        @Override
        public String run(String orderId) {
            log.info("[{}] 45초 타이머 시작", orderId);
            Workflow.sleep(Duration.ofSeconds(45));
            log.info("[{}] 45초 경과. 재개됨.", orderId);
            return orderId + " RESUMED";
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 문제 3 — 조건 함수는 몇 번 평가되는가
    //
    // Q3Workflow 는 await 전에 액티비티를 하나 호출합니다.
    // 20초를 기다렸다가 시그널을 보낼 때, 조건 함수는 몇 번 평가될까요?
    // 예상값을 상수에 적고 실행해서 맞춰 보세요.
    //
    // 힌트: 조건은 "새 Workflow Task 가 처리될 때마다" 평가됩니다.
    //       Workflow Task 를 만드는 것은 무엇인지 세어 보세요.
    // ==================================================================
    static final int Q3_EXPECTED_EVALS = 0;   // TODO: 여기에 작성

    public static class Q3Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q3Workflow.class);
        private boolean approved = false;
        private int evalCount = 0;

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());

        @Override
        public String run(String orderId) {
            inventory.reserve(orderId, "SKU-A", 1);      // await 진입 전 액티비티 1개
            Workflow.await(() -> {
                log.info("조건 평가 #{}", ++evalCount);
                return approved;
            });
            return orderId + " evals=" + evalCount;
        }
        @Override public void approve() { this.approved = true; }
    }

    // ==================================================================
    // 문제 4 — 반환값을 버린 await
    //
    // 아래 코드는 "15초 안에 승인 시그널, 아니면 자동 반려" 를 의도했지만
    // 승인하지 않아도 "승인됨" 으로 끝납니다. 먼저 그대로 실행해서
    // 잘못된 결과를 확인한 뒤 고치세요.
    //
    // 실행: ./gradlew run -PmainClass=com.example.order.Exercise --args="4"
    //       (시그널을 보내지 않습니다)
    // ==================================================================
    public static class Q4Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q4Workflow.class);
        private boolean approved = false;

        @Override
        public String run(String orderId) {
            log.info("[{}] 승인 대기 (최대 15초)", orderId);

            // TODO: 여기에 작성 — 아래 한 줄을 고치세요
            Workflow.await(Duration.ofSeconds(15), () -> approved);

            log.info("[{}] 승인됨", orderId);
            return orderId + " APPROVED";
        }
        @Override public void approve() { this.approved = true; }
    }

    // ==================================================================
    // 문제 5 — 액티비티 4개 병렬 실행
    //
    // 각 1.5초짜리 재고 조회 4개를 순차와 병렬로 각각 실행하고
    // 소요 시간을 비교하세요. 순차는 약 6,000ms, 병렬은 약 1,600ms 가 나와야 합니다.
    //
    // 확인: temporal workflow show -w order-6205
    //       → ActivityTaskScheduled 4개가 연달아 붙어 있어야 병렬입니다.
    //         흩어져 있으면 Promise.allOf 를 빠뜨린 것입니다.
    // ==================================================================
    public static class Q5Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q5Workflow.class);

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());

        @Override
        public String run(String orderId) {
            String[] skus = {"SKU-A", "SKU-B", "SKU-C", "SKU-D"};

            long t0 = Workflow.currentTimeMillis();
            for (String sku : skus) {
                inventory.reserve(orderId, sku, 1);
            }
            long sequential = Workflow.currentTimeMillis() - t0;
            log.info("[{}] 순차 {}ms", orderId, sequential);

            long t1 = Workflow.currentTimeMillis();
            // TODO: 여기에 작성
            //  - skus 4개에 대해 Async.function 으로 Promise 4개를 "먼저 전부" 만들고
            //  - Promise.allOf(...).get() 으로 한꺼번에 기다리세요
            long parallel = Workflow.currentTimeMillis() - t1;
            log.info("[{}] 병렬 {}ms", orderId, parallel);

            return "순차 %dms → 병렬 %dms".formatted(sequential, parallel);
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 문제 6 — anyOf 의 승자 판별
    //
    // 아래 코드는 Promise.anyOf 의 반환값으로 승자를 가리려 합니다.
    // estimateDelivery 는 20초, 타이머는 5초라 타이머가 이깁니다.
    // 그런데 타이머의 값은 null 이라 result 가 null 이 되고,
    // 이 코드는 엉뚱하게 동작하거나 NPE 를 냅니다. 고치세요.
    // ==================================================================
    public static class Q6Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q6Workflow.class);

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(60))
                        .build());

        @Override
        public String run(String orderId) {
            Promise<String> lookup = Async.function(shipping::estimateDelivery, orderId, "서울시 강남구");
            Promise<Void> timeout = Workflow.newTimer(Duration.ofSeconds(5));

            Object result = Promise.anyOf(lookup, timeout).get();

            // TODO: 여기에 작성 — 아래 분기를 isCompleted() 기반으로 고치세요
            String eta;
            if (result != null) {
                eta = result.toString();
                log.info("[{}] 조회 성공: {}", orderId, eta);
            } else {
                eta = "3~5일";
                log.warn("[{}] 타임아웃. 기본값 사용.", orderId);
            }
            return orderId + " eta=" + eta;
        }
        @Override public void approve() {}
    }

    static ActivityOptions opts() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                .build();
    }

    // ------------------------------------------------------------------
    // 실행부
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? args[0] : "1";
        if (q.equals("cleanup")) { cleanup(); return; }

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE,
                WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(10).build());
        worker.registerActivitiesImplementations(
                new InventoryActivityImpl(), new ShippingActivityImpl());

        String id;
        switch (q) {
            case "1" -> {
                worker.registerWorkflowImplementationTypes(Q1Workflow.class);
                id = "sub-Q1";
            }
            case "2" -> { worker.registerWorkflowImplementationTypes(Q2Workflow.class); id = "order-6202"; }
            case "3" -> { worker.registerWorkflowImplementationTypes(Q3Workflow.class); id = "order-6203"; }
            case "4" -> { worker.registerWorkflowImplementationTypes(Q4Workflow.class); id = "order-6204"; }
            case "5" -> { worker.registerWorkflowImplementationTypes(Q5Workflow.class); id = "order-6205"; }
            case "6" -> { worker.registerWorkflowImplementationTypes(Q6Workflow.class); id = "order-6206"; }
            default -> throw new IllegalArgumentException("1~6 또는 cleanup");
        }
        factory.start();

        SimpleWorkflow wf = client.newWorkflowStub(SimpleWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(TASK_QUEUE).build());
        System.out.println("=== Q" + q + " → " + id + " ===");

        if (q.equals("1")) {
            WorkflowClient.start(wf::run, "Q1");
            Thread.sleep(2000);
            System.out.println("90일 = " + Q1_EXPECTED_SECONDS + "초 라고 답했습니다.");
            System.out.println("확인: temporal workflow show -w sub-Q1 --output json | jq -r "
                    + "'.events[] | select(.eventType==\"EVENT_TYPE_TIMER_STARTED\") "
                    + ".timerStartedEventAttributes.startToFireTimeout'");
            System.out.println("정리: temporal workflow terminate -w sub-Q1 --reason \"연습문제 정리\"");
            System.exit(0);
        }
        if (q.equals("2")) {
            WorkflowClient.start(wf::run, "6202");
            System.out.println("45초 타이머 시작. 이 프로세스를 죽이고 60초 뒤 다시 띄우세요.");
            Thread.sleep(300_000);
            System.exit(0);
        }
        if (q.equals("3")) {
            WorkflowClient.start(wf::run, "6203");
            System.out.println("20초 대기 후 시그널을 보냅니다. 예상 평가 횟수 = " + Q3_EXPECTED_EVALS);
            Thread.sleep(20_000);
            wf.approve();
            Thread.sleep(3000);
            System.exit(0);
        }

        System.out.println("결과: " + wf.run(id.substring(id.indexOf('-') + 1)));
        System.exit(0);
    }

    static void cleanup() {
        System.out.println("아래 명령으로 남은 워크플로우를 정리하세요.");
        System.out.println("  temporal workflow terminate -w sub-Q1 --reason \"연습문제 정리\"   ← 반드시!");
        for (int i = 2; i <= 6; i++) {
            System.out.println("  temporal workflow terminate -w order-620" + i + " --reason \"연습문제 정리\"");
        }
        System.out.println("  temporal workflow list --query 'ExecutionStatus=\"Running\"'");
    }
}
