package com.example.order;

/*
 * Step 05 — 재시도와 실패 처리 / 실습 코드
 *
 * 실행 방법
 *   1) Temporal 서버 기동:  docker compose up -d        (temporalio/auto-setup:1.22.4)
 *   2) 절 단위 실행:
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="5-2"
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="5-3"
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="5-4"
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="5-5"
 *        ./gradlew run -PmainClass=com.example.order.Practice --args="5-8"
 *   3) 인자를 주지 않으면 [5-2] 백오프 시뮬레이터만 돌고 끝납니다.
 *
 * 환경
 *   Temporal Server 1.22.4 / Java SDK 1.22.3 / temporal CLI 0.11.0 / Java 21
 *   gRPC 127.0.0.1:7233 / Namespace default / Task Queue ORDER_TASK_QUEUE
 *
 * 주의
 *   [5-3] 은 "영원히 실패하는" 워크플로우를 띄웁니다. 관찰이 끝나면 반드시 정리하세요.
 *        temporal workflow terminate -w order-5001 --reason "실습 종료"
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Practice {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // 공통 DTO (코스 규격)
    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {}

    // ------------------------------------------------------------------
    // [5-2] 백오프 시뮬레이터 — Temporal 서버 없이 돌아가는 순수 계산
    //       본문 표의 "10번째 시도 = 누적 327초" 를 손으로 확인하는 용도입니다.
    //       coeff 를 1.0(고정 간격) / 3.0(급격한 백오프)으로 바꿔 보세요.
    // ------------------------------------------------------------------
    static void printBackoff(Duration initial, double coeff, Duration max, int attempts) {
        System.out.printf("initialInterval=%s backoffCoefficient=%.1f maximumInterval=%s%n",
                initial, coeff, max);
        long cumulative = 0;
        for (int n = 1; n <= attempts; n++) {
            long interval = Math.min(
                    (long) (initial.toMillis() * Math.pow(coeff, n - 1)),
                    max.toMillis());
            System.out.printf("attempt %2d 시작 t=%,6ds  (직전 대기 %,4ds)%n",
                    n, cumulative / 1000, n == 1 ? 0 : interval / 1000);
            cumulative += interval;
        }
        System.out.printf("→ %d회 시도까지 누적 대기 %,ds%n%n", attempts, cumulative / 1000);
    }

    // ------------------------------------------------------------------
    // 액티비티 인터페이스 (코스 규격 4종 중 이 스텝에서 쓰는 2종)
    // ------------------------------------------------------------------
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
        @ActivityMethod void refund(String paymentId);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
        @ActivityMethod void cancelShipment(String shipmentId);
    }

    // ------------------------------------------------------------------
    // [5-3] 항상 실패하는 결제 액티비티 — 무한 재시도를 눈으로 보기 위한 것
    // ------------------------------------------------------------------
    public static class AlwaysFailingPaymentActivityImpl implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(AlwaysFailingPaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            log.info("[{}] 결제 시도 amount={}", orderId, amount);
            throw new RuntimeException("payment gateway unreachable: connect timed out");
        }

        @Override
        public void refund(String paymentId) { /* no-op */ }
    }

    // ------------------------------------------------------------------
    // [5-5] 실패 종류를 구분해서 던지는 결제 액티비티
    // ------------------------------------------------------------------
    public record Account(String customerId, long balance) {}

    public static class RealisticPaymentActivityImpl implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(RealisticPaymentActivityImpl.class);

        // orderId → 계정. 5003 은 잔액이 12,000원뿐입니다.
        private final Map<String, Account> accounts = Map.of(
                "5002", new Account("C-1", 12_000L),
                "5003", new Account("C-9", 12_000L),
                "5005", new Account("C-2", 999_000L),
                "5006", new Account("C-3", 999_000L));

        private final AtomicInteger gatewayCalls = new AtomicInteger();

        @Override
        public String charge(String orderId, long amount) {
            log.info("[{}] 결제 시도 amount={}", orderId, amount);
            Account acct = accounts.get(orderId);

            // (a) 재시도 불가 — 계정이 없는 것은 다시 해도 없습니다.
            if (acct == null) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "account not found for order " + orderId,
                        "AccountNotFoundError",
                        orderId);
            }
            // (b) 재시도 불가 — 잔액은 재시도로 늘지 않습니다. details 로 숫자를 넘깁니다.
            if (acct.balance() < amount) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "insufficient balance",
                        "InsufficientBalanceError",
                        acct.balance(), amount);
            }
            // (c) 재시도 가치 있음 — 처음 두 번은 게이트웨이 장애를 흉내 냅니다.
            if (gatewayCalls.incrementAndGet() <= 2) {
                throw ApplicationFailure.newFailure(
                        "gateway temporarily unavailable",
                        "GatewayUnavailableError");
            }
            String paymentId = "PAY-" + orderId;
            log.info("[{}] 결제 완료 {}", orderId, paymentId);
            return paymentId;
        }

        @Override
        public void refund(String paymentId) {
            log.info("결제 취소 {}", paymentId);
        }
    }

    // ------------------------------------------------------------------
    // [5-6] 재시도가 "안 먹는" 잘못된 구현 — 주석을 풀고 직접 확인해 보세요
    // ------------------------------------------------------------------
    public static class BrokenPaymentActivityImpl implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(BrokenPaymentActivityImpl.class);

        @Override
        public String charge(String orderId, long amount) {
            // (1) Error 를 던지면 액티비티 실패로 보고되지 않고
            //     StartToCloseTimeout 이 지날 때까지 아무 일도 일어나지 않습니다.
            //     주석을 풀고 describe 로 Attempt 가 언제 올라가는지 보세요.
            // throw new AssertionError("이러면 안 됩니다");

            // (2) 예외를 삼키고 정상 반환하면 재시도가 한 번도 일어나지 않습니다.
            //     ActivityTaskCompleted 가 기록되고 워크플로우는 결제됐다고 믿습니다.
            //     이 스텝에서 가장 위험한 함정입니다.
            try {
                throw new RuntimeException("payment gateway 500");
            } catch (Exception e) {
                log.error("결제 실패", e);
                return null;                // ← "성공"으로 완료됨. 올바른 코드는 throw e;
            }
        }

        @Override
        public void refund(String paymentId) { /* no-op */ }
    }

    // ------------------------------------------------------------------
    // 배송 액티비티 — 주 배송사는 항상 실패, 대체 배송사는 성공
    // ------------------------------------------------------------------
    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = Workflow.getLogger(ShippingActivityImpl.class);

        @Override
        public String requestShipment(String orderId, String address) {
            log.warn("[{}] 배송 요청 실패", orderId);
            throw ApplicationFailure.newFailure("carrier 503", "CarrierUnavailableError");
        }

        @Override
        public void cancelShipment(String shipmentId) {
            log.info("배송 취소 {}", shipmentId);
        }
    }

    // ------------------------------------------------------------------
    // 워크플로우 인터페이스
    // ------------------------------------------------------------------
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // [5-3] RetryOptions 를 일부러 지정하지 않은 워크플로우 = 무한 재시도
    public static class InfiniteRetryWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        // setRetryOptions 없음 → maximumAttempts=0 = 무제한
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            return req.orderId() + " " + payment.charge(req.orderId(), req.amount());
        }
    }

    // [5-4] 예외를 제대로 벗겨 내는 워크플로우
    public static class UnwrapWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(UnwrapWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(2)
                                .build())
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            try {
                return payment.charge(req.orderId(), req.amount());
            } catch (ActivityFailure af) {
                unwrap(log, af);
                return req.orderId() + " PAYMENT_DECLINED";
            }
        }
    }

    /**
     * [5-4] 실무에서 그대로 복사해 쓸 수 있는 예외 해부 헬퍼.
     * ActivityFailure.getMessage() 는 언제나 "Activity task failed" 이므로
     * 반드시 getCause() 를 한 겹 벗겨야 원인이 보입니다.
     */
    static void unwrap(Logger log, ActivityFailure af) {
        log.info("겉껍질   : {} / {}", af.getClass().getSimpleName(), af.getMessage());

        Throwable cause = af.getCause();
        log.info("한 겹 벗김: {} / {}", cause.getClass().getSimpleName(), cause.getMessage());

        if (cause instanceof ApplicationFailure appFailure) {
            log.info("원래 타입 : {}", appFailure.getType());
            log.info("재시도 금지: {}", appFailure.isNonRetryable());
            if ("InsufficientBalanceError".equals(appFailure.getType())) {
                long balance = appFailure.getDetails().get(0, Long.class);
                long needed = appFailure.getDetails().get(1, Long.class);
                log.info("details   : 잔액 {}원, 필요 {}원, 부족 {}원",
                        balance, needed, needed - balance);
            }
        }
    }

    // ------------------------------------------------------------------
    // [5-8] 실패를 다루는 3가지 방식
    // ------------------------------------------------------------------

    // (1) 전파 — 아무것도 하지 않으면 워크플로우가 FAILED 로 끝납니다.
    public static class PropagateWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class, shortRetry());

        @Override
        public String processOrder(OrderRequest req) {
            String paymentId = payment.charge(req.orderId(), req.amount());
            return req.orderId() + " COMPLETED payment=" + paymentId;
        }
    }

    // (2) 대안 경로 — 주 배송사가 실패하면 대체 배송사로 전환합니다. 결과는 COMPLETED.
    public static class FallbackWorkflowImpl implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(FallbackWorkflowImpl.class);

        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class, shortRetry());
        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class, shortRetry());

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            String shipmentId;
            try {
                shipmentId = shipping.requestShipment(req.orderId(), req.address());
            } catch (ActivityFailure af) {
                log.warn("[{}] 주 배송사 실패, 대체 경로로 전환", req.orderId());
                shipmentId = "SHIP-BK-" + req.orderId();   // 대체 배송사 (여기선 즉시 성공)
            }
            log.info("[{}] 완료 shipmentId={}", req.orderId(), shipmentId);
            return req.orderId() + " COMPLETED shipment=" + shipmentId;
        }
    }

    // (3) 보상 — 이미 한 일을 되돌리고 다시 던집니다. 중첩이 깊어지는 것을 체감하세요.
    //     Step 09 에서 io.temporal.workflow.Saga 로 정식화합니다.
    public static class CompensateWorkflowImpl implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class, shortRetry());
        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class, shortRetry());

        @Override
        public String processOrder(OrderRequest req) {
            String paymentId = payment.charge(req.orderId(), req.amount());
            try {
                return shipping.requestShipment(req.orderId(), req.address());
            } catch (ActivityFailure af) {
                payment.refund(paymentId);   // 되돌리기
                throw af;                    // 그리고 다시 던져 워크플로우를 실패시킵니다
            }
        }
    }

    /** 실습이 오래 걸리지 않도록 재시도를 3회로 제한한 공통 옵션. */
    static ActivityOptions shortRetry() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setScheduleToCloseTimeout(Duration.ofMinutes(1))   // 안전망
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(200))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(3)                      // 안전망
                        .build())
                .build();
    }

    // ------------------------------------------------------------------
    // 실행부
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String section = args.length > 0 ? args[0] : "5-2";

        // [5-2] 는 서버가 필요 없습니다.
        if (section.equals("5-2")) {
            printBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(100), 10);
            printBackoff(Duration.ofSeconds(1), 1.0, Duration.ofSeconds(100), 6);   // 고정 간격
            return;
        }

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        String workflowId;
        OrderRequest req;

        switch (section) {
            case "5-3" -> {
                worker.registerWorkflowImplementationTypes(InfiniteRetryWorkflowImpl.class);
                worker.registerActivitiesImplementations(new AlwaysFailingPaymentActivityImpl());
                workflowId = "order-5001";
                req = new OrderRequest("5001", "C-1", "SKU-A", 1, 39_000, "서울시 강남구");
            }
            case "5-4" -> {
                worker.registerWorkflowImplementationTypes(UnwrapWorkflowImpl.class);
                worker.registerActivitiesImplementations(new RealisticPaymentActivityImpl());
                workflowId = "order-5002";
                req = new OrderRequest("5002", "C-1", "SKU-A", 1, 39_000, "서울시 강남구");
            }
            case "5-5" -> {
                worker.registerWorkflowImplementationTypes(PropagateWorkflowImpl.class);
                worker.registerActivitiesImplementations(new RealisticPaymentActivityImpl());
                workflowId = "order-5003";
                req = new OrderRequest("5003", "C-9", "SKU-A", 1, 990_000, "서울시 강남구");
            }
            case "5-6" -> {
                worker.registerWorkflowImplementationTypes(PropagateWorkflowImpl.class);
                worker.registerActivitiesImplementations(new BrokenPaymentActivityImpl());
                workflowId = "order-5004";
                req = new OrderRequest("5004", "C-1", "SKU-A", 1, 39_000, "서울시 강남구");
            }
            case "5-8" -> {
                // 세 워크플로우를 한 Worker 에 등록하고 연달아 실행합니다.
                worker.registerWorkflowImplementationTypes(
                        PropagateWorkflowImpl.class, FallbackWorkflowImpl.class,
                        CompensateWorkflowImpl.class);
                worker.registerActivitiesImplementations(
                        new RealisticPaymentActivityImpl(), new ShippingActivityImpl());
                factory.start();
                runAll(client);
                return;
            }
            default -> throw new IllegalArgumentException("알 수 없는 절: " + section);
        }

        factory.start();
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());

        System.out.println("=== " + section + " 실행: " + workflowId + " ===");
        System.out.println("다른 터미널에서: temporal workflow describe -w " + workflowId);
        try {
            System.out.println("결과: " + wf.processOrder(req));
        } catch (Exception e) {
            System.out.println("워크플로우 실패: " + e);
            if (e.getCause() != null) System.out.println("  cause: " + e.getCause());
        }
    }

    /** [5-8] 세 방식을 연달아 돌려 Status 가 어떻게 갈리는지 비교합니다. */
    static void runAll(WorkflowClient client) {
        record Case(String id, Class<? extends OrderWorkflow> type) {}
        var cases = new Case[]{
                new Case("order-5004", PropagateWorkflowImpl.class),
                new Case("order-5005", FallbackWorkflowImpl.class),
                new Case("order-5006", CompensateWorkflowImpl.class)};

        for (Case c : cases) {
            OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(c.id())
                            .setTaskQueue(TASK_QUEUE)
                            .build());
            String oid = c.id().substring("order-".length());
            try {
                System.out.printf("%-12s → %s%n", c.id(),
                        wf.processOrder(new OrderRequest(oid, "C-1", "SKU-A", 1, 39_000, "서울시 강남구")));
            } catch (Exception e) {
                System.out.printf("%-12s → FAILED (%s)%n", c.id(),
                        e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
        }
        System.out.println();
        System.out.println("확인: temporal workflow list --query 'WorkflowId STARTS_WITH \"order-500\"'");
    }
}
