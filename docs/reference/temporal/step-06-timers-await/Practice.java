package com.example.order;

/*
 * Step 06 — 타이머와 대기 / 실습 코드
 *
 * 실행 방법
 *   1) Temporal 서버 기동:  docker compose up -d        (temporalio/auto-setup:1.22.4)
 *   2) 절 단위 실행:
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-1"   (몇 초)
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-3"   (약 35초)
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-4"   (약 25초)
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-6"   (약 5초)
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-7"   (약 30초)
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="6-8"   (약 60초)
 *
 * [6-2] Worker 재기동 실험은 코드로 재현되지 않습니다. 터미널 두 개를 열고 직접 하세요.
 *   터미널 A:  ./gradlew run -PmainClass=com.example.order.Practice --args="6-2"
 *   터미널 B:  pkill -f "com.example.order.Practice"
 *              temporal task-queue describe --task-queue ORDER_TASK_QUEUE --task-queue-type workflow
 *              sleep 90
 *              temporal workflow describe -w sub-C-1003
 *   터미널 A:  ./gradlew run -PmainClass=com.example.order.Practice --args="6-2-resume"
 *
 * 정리
 *   temporal workflow terminate -w sub-C-1001 --reason "30일 타이머 실습 종료"
 *
 * 환경
 *   Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
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

public class Practice {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {}

    // ------------------------------------------------------------------
    // 액티비티
    // ------------------------------------------------------------------
    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
        @ActivityMethod void release(String reservationId);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String estimateDelivery(String orderId, String address);
        @ActivityMethod String requestShipment(String orderId, String address);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod void notifyCustomer(String orderId, String message);
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = Workflow.getLogger(InventoryActivityImpl.class);

        @Override
        public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 창고 조회 {}", orderId, sku);
            sleepQuietly(1000);                       // 창고 API 1초
            return "RSV-" + orderId + "-" + sku;
        }

        @Override
        public void release(String reservationId) {
            log.info("재고 해제 {}", reservationId);
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = Workflow.getLogger(ShippingActivityImpl.class);

        // [6-7] orderId 끝자리가 홀수면 3초(액티비티 승), 짝수면 25초(타이머 승)
        @Override
        public String estimateDelivery(String orderId, String address) {
            log.info("[{}] 배송 예상일 조회", orderId);
            boolean fast = (orderId.charAt(orderId.length() - 1) - '0') % 2 == 1;
            sleepQuietly(fast ? 3_000 : 25_000);
            return "2026-03-14";
        }

        @Override
        public String requestShipment(String orderId, String address) {
            log.info("[{}] 배송 접수", orderId);
            return "SHIP-" + orderId;
        }
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        private static final Logger log = Workflow.getLogger(NotificationActivityImpl.class);
        @Override public void notifyCustomer(String orderId, String message) {
            log.info("[{}] 알림: {}", orderId, message);
        }
    }

    /** 액티비티 안에서는 Thread.sleep 을 써도 됩니다. 워크플로우 안에서는 절대 금지입니다. */
    static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ==================================================================
    // [6-1] Workflow.sleep — 30일을 자도 되는 이유
    //       이 워크플로우를 띄우고 클라이언트를 종료해도 서버에 RUNNING 으로 남습니다.
    //       temporal workflow describe -w sub-C-1001 → History Length 5
    // ==================================================================
    @WorkflowInterface
    public interface SubscriptionWorkflow {
        @WorkflowMethod String renew(String customerId);
    }

    public static class SubscriptionWorkflowImpl implements SubscriptionWorkflow {
        private static final Logger log = Workflow.getLogger(SubscriptionWorkflowImpl.class);

        @Override
        public String renew(String customerId) {
            log.info("[{}] 구독 시작. 30일 뒤 갱신합니다.", customerId);
            Workflow.sleep(Duration.ofDays(30));      // ← 여기서 메모리에서 사라집니다
            log.info("[{}] 30일 경과. 갱신 처리.", customerId);
            return customerId + " RENEWED";
        }
    }

    // [6-2] Worker 재기동 실험용 — 60초 버전
    public static class ShortSubscriptionWorkflowImpl implements SubscriptionWorkflow {
        private static final Logger log = Workflow.getLogger(ShortSubscriptionWorkflowImpl.class);

        @Override
        public String renew(String customerId) {
            log.info("[{}] 60초 타이머 시작", customerId);
            Workflow.sleep(Duration.ofSeconds(60));
            log.info("[{}] 60초 경과. 갱신 처리.", customerId);
            return customerId + " RENEWED";
        }
    }

    // ==================================================================
    // [6-3] Workflow.await — 조건이 참이 될 때까지. 폴링이 아닙니다.
    //       evalCount 로 조건 함수가 몇 번 호출되는지 셉니다. 30초 대기 후 시그널 → 2번.
    // ==================================================================
    @WorkflowInterface
    public interface ApprovalWorkflow {
        @WorkflowMethod String process(String orderId);
        @SignalMethod void approve();
    }

    public static class ApprovalWorkflowImpl implements ApprovalWorkflow {
        private static final Logger log = Workflow.getLogger(ApprovalWorkflowImpl.class);
        private boolean approved = false;
        private int evalCount = 0;

        @Override
        public String process(String orderId) {
            log.info("[{}] 승인 대기 시작", orderId);
            Workflow.await(() -> {
                log.info("조건 평가 #{} approved={}", ++evalCount, approved);
                return approved;
            });
            log.info("[{}] 승인 확인. 진행합니다. (총 평가 {}회)", orderId, evalCount);
            return orderId + " APPROVED evals=" + evalCount;
        }

        @Override
        public void approve() {
            this.approved = true;
        }
    }

    // ==================================================================
    // [6-4] 타임아웃 있는 조건 대기
    //       반환값 boolean 으로 "조건 충족" vs "타임아웃" 을 반드시 구분합니다.
    // ==================================================================
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
        @SignalMethod void confirmPayment();
    }

    public static class PaymentTimeoutWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(PaymentTimeoutWorkflowImpl.class);
        private boolean paymentConfirmed = false;

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());
        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class, opts());
        private final NotificationActivity notification = Workflow.newActivityStub(
                NotificationActivity.class, opts());

        @Override
        public String processOrder(OrderRequest req) {
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            log.info("[{}] 결제 확인 대기 (최대 20초)", req.orderId());

            // ★ 반환값을 반드시 받아서 분기에 쓸 것
            boolean confirmed = Workflow.await(Duration.ofSeconds(20), () -> paymentConfirmed);

            if (confirmed) {
                log.info("[{}] 결제 확인됨. 배송 요청.", req.orderId());
                return shipping.requestShipment(req.orderId(), req.address());
            }
            log.warn("[{}] 20초 내 결제 미확인. 주문 취소.", req.orderId());
            inventory.release(reservationId);
            notification.notifyCustomer(req.orderId(), "결제 시간이 만료되어 주문이 취소되었습니다.");
            return "order-" + req.orderId() + " CANCELLED_TIMEOUT";
        }

        @Override
        public void confirmPayment() {
            this.paymentConfirmed = true;
        }
    }

    // ==================================================================
    // [6-6] 병렬 액티비티 — 순차 vs 병렬 실측
    // ==================================================================
    public static class ParallelWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(ParallelWorkflowImpl.class);

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());

        @Override
        public String processOrder(OrderRequest req) {
            String id = req.orderId();

            // (a) 순차 — 1초짜리 3개 = 약 3.0초
            long t0 = Workflow.currentTimeMillis();
            inventory.reserve(id, "SKU-A", 1);
            inventory.reserve(id, "SKU-B", 1);
            inventory.reserve(id, "SKU-C", 1);
            long sequential = Workflow.currentTimeMillis() - t0;
            log.info("[{}] 순차 재고 확인 완료. 소요 {}ms", id, sequential);

            // (b) 병렬 — Promise 를 "전부 먼저 만들고" 그다음에 기다립니다.
            //     .get() 을 만들자마자 붙이면 순차와 똑같아집니다(함정).
            long t1 = Workflow.currentTimeMillis();
            Promise<String> pa = Async.function(inventory::reserve, id, "SKU-A", 1);
            Promise<String> pb = Async.function(inventory::reserve, id, "SKU-B", 1);
            Promise<String> pc = Async.function(inventory::reserve, id, "SKU-C", 1);
            Promise.allOf(pa, pb, pc).get();
            long parallel = Workflow.currentTimeMillis() - t1;
            log.info("[{}] 병렬 재고 확인 완료. 소요 {}ms — {}, {}, {}",
                    id, parallel, pa.get(), pb.get(), pc.get());

            return "순차 %dms → 병렬 %dms (%.1f배)"
                    .formatted(sequential, parallel, (double) sequential / parallel);
        }

        @Override public void confirmPayment() {}
    }

    // ==================================================================
    // [6-7] Promise.anyOf — 액티비티 vs 타이머 경주 (소프트 타임아웃)
    //       승자는 반환값이 아니라 isCompleted() 로 판별해야 합니다.
    // ==================================================================
    public static class SoftTimeoutWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(SoftTimeoutWorkflowImpl.class);

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(60))   // 액티비티는 넉넉히
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            Promise<String> lookup =
                    Async.function(shipping::estimateDelivery, req.orderId(), req.address());
            Promise<Void> timeout = Workflow.newTimer(Duration.ofSeconds(10));

            Promise.anyOf(lookup, timeout).get();     // 먼저 끝나는 쪽까지만 대기

            String eta;
            if (lookup.isCompleted()) {               // ★ 반환값이 아니라 isCompleted()
                eta = lookup.get();
                log.info("[{}] 배송 예상일 조회 성공: {}", req.orderId(), eta);
            } else {
                eta = "3~5일";
                log.warn("[{}] 배송 조회 10초 초과. 기본값 사용.", req.orderId());
            }
            return "order-" + req.orderId() + " eta=" + eta;
        }

        @Override public void confirmPayment() {}
    }

    // ==================================================================
    // [6-8] 절대 하면 안 되는 폴링 루프 — 60초분만 돌려 히스토리 폭증을 체감합니다.
    //       하루 환산: 타이머 이벤트만 172,800개, Workflow Task 까지 432,000개.
    //       기본 제한 51,200개에 약 2시간 50분이면 도달해 강제 종료됩니다.
    // ==================================================================
    public static class PollingWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(PollingWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            for (int i = 0; i < 60; i++) {
                Workflow.sleep(Duration.ofSeconds(1));    // ⚠️ 1회당 이벤트 5개
            }
            log.warn("[{}] 60초 폴링 종료. describe 로 History Length 를 확인하세요.", req.orderId());
            return "order-" + req.orderId() + " POLLED_60_TIMES";
        }

        @Override public void confirmPayment() {}
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
        String section = args.length > 0 ? args[0] : "6-1";

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        // 병렬 실습([6-6])을 위해 동시 액티비티 실행 수를 넉넉히 둡니다.
        Worker worker = factory.newWorker(TASK_QUEUE,
                WorkerOptions.newBuilder()
                        .setMaxConcurrentActivityExecutionSize(10)
                        .build());
        worker.registerActivitiesImplementations(
                new InventoryActivityImpl(), new ShippingActivityImpl(),
                new NotificationActivityImpl());

        switch (section) {
            case "6-1" -> {
                worker.registerWorkflowImplementationTypes(SubscriptionWorkflowImpl.class);
                factory.start();
                SubscriptionWorkflow wf = client.newWorkflowStub(SubscriptionWorkflow.class,
                        wfOptions("sub-C-1001"));
                WorkflowClient.start(wf::renew, "C-1001");   // 기다리지 않고 시작만
                System.out.println("30일 타이머 워크플로우를 띄웠습니다. 결과를 기다리지 않고 종료합니다.");
                System.out.println("확인: temporal workflow describe -w sub-C-1001");
                System.out.println("     → Status RUNNING / History Length 5 / History Size ~897");
                System.out.println("정리: temporal workflow terminate -w sub-C-1001 --reason \"실습 종료\"");
                Thread.sleep(2000);
                System.exit(0);
            }
            case "6-2", "6-2-resume" -> {
                worker.registerWorkflowImplementationTypes(ShortSubscriptionWorkflowImpl.class);
                factory.start();
                if (section.equals("6-2")) {
                    SubscriptionWorkflow wf = client.newWorkflowStub(SubscriptionWorkflow.class,
                            wfOptions("sub-C-1003"));
                    WorkflowClient.start(wf::renew, "C-1003");
                    System.out.println("60초 타이머 시작. 이제 이 프로세스를 죽이세요:");
                    System.out.println("  pkill -f \"com.example.order.Practice\"");
                    System.out.println("90초 뒤 --args=\"6-2-resume\" 로 다시 띄우면 즉시 이어집니다.");
                } else {
                    System.out.println("Worker 재기동. 대기 중이던 Workflow Task 를 곧 집어 갑니다.");
                }
                Thread.sleep(120_000);
                System.exit(0);
            }
            case "6-3" -> {
                worker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
                factory.start();
                ApprovalWorkflow wf = client.newWorkflowStub(ApprovalWorkflow.class,
                        wfOptions("order-6101"));
                WorkflowClient.start(wf::process, "6101");
                System.out.println("30초간 아무 일도 하지 않습니다. 조건 평가 로그를 보세요.");
                Thread.sleep(30_000);
                System.out.println("이제 시그널을 보냅니다.");
                wf.approve();
                System.out.println("결과: " + WorkflowStub.fromTyped(wf).getResult(String.class));
                System.exit(0);
            }
            case "6-4" -> {
                worker.registerWorkflowImplementationTypes(PaymentTimeoutWorkflowImpl.class);
                factory.start();
                OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, wfOptions("order-6102"));
                System.out.println("시그널을 보내지 않고 20초 타임아웃을 유도합니다.");
                System.out.println("결과: " + wf.processOrder(req("6102")));
                System.out.println("확인: temporal workflow show -w order-6102   → TimerStarted/TimerFired");
                System.exit(0);
            }
            case "6-6" -> {
                worker.registerWorkflowImplementationTypes(ParallelWorkflowImpl.class);
                factory.start();
                OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, wfOptions("order-6104"));
                System.out.println("결과: " + wf.processOrder(req("6104")));
                System.out.println("확인: temporal workflow show -w order-6104");
                System.out.println("     → ActivityTaskScheduled 3개가 연달아 붙어 있어야 병렬입니다.");
                System.exit(0);
            }
            case "6-7" -> {
                worker.registerWorkflowImplementationTypes(SoftTimeoutWorkflowImpl.class);
                factory.start();
                // 홀수 = 3초(액티비티 승), 짝수 = 25초(타이머 승)
                OrderWorkflow fast = client.newWorkflowStub(OrderWorkflow.class, wfOptions("order-6105"));
                System.out.println("[홀수/3초] " + fast.processOrder(req("6105")));
                OrderWorkflow slow = client.newWorkflowStub(OrderWorkflow.class, wfOptions("order-6106"));
                System.out.println("[짝수/25초] " + slow.processOrder(req("6106")));
                System.out.println("비교: temporal workflow show -w order-6105  → TimerCanceled");
                System.out.println("      temporal workflow show -w order-6106  → TimerFired");
                System.exit(0);
            }
            case "6-8" -> {
                worker.registerWorkflowImplementationTypes(PollingWorkflowImpl.class);
                factory.start();
                OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, wfOptions("order-6107"));
                System.out.println("1초 폴링을 60회 돌립니다. 60초 걸립니다.");
                System.out.println("결과: " + wf.processOrder(req("6107")));
                System.out.println("확인: temporal workflow describe -w order-6107");
                System.out.println("     → History Length 약 305. 하루면 432,000개, 제한은 51,200개입니다.");
                System.exit(0);
            }
            default -> throw new IllegalArgumentException("알 수 없는 절: " + section);
        }
    }

    static WorkflowOptions wfOptions(String id) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(id)
                .setTaskQueue(TASK_QUEUE)
                .build();
    }

    static OrderRequest req(String orderId) {
        return new OrderRequest(orderId, "C-1", "SKU-A", 1, 39_000, "서울시 강남구");
    }
}
