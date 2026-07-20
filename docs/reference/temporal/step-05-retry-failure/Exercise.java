package com.example.order;

/*
 * Step 05 — 재시도와 실패 처리 / 연습문제 (6문제)
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Exercise --args="1"
 *   ./gradlew run -PmainClass=com.example.order.Exercise --args="cleanup"
 *
 * 문제 1 만 Temporal 서버 없이 풀 수 있습니다. 나머지는 서버와 Worker 가 필요합니다.
 * 각 문제의 "// TODO: 여기에 작성" 을 채우세요. 정답은 Solution.java.
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

public class Exercise {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {}

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
        @ActivityMethod void refund(String paymentId);
    }

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // ==================================================================
    // 문제 1 — 백오프 계산 (서버 불필요, 자가 채점)
    //
    // RetryOptions:
    //   initialInterval    = 2초
    //   backoffCoefficient = 2.0
    //   maximumInterval    = 30초
    //
    // 8번째 시도가 시작되는 시각(1번째 시도를 t=0 으로 볼 때, 초 단위)은?
    // 손으로 계산해서 아래 상수에 넣고, 검증 루프로 확인하세요.
    // ==================================================================
    static long q1AnswerSeconds() {
        // TODO: 여기에 작성 — 8번째 시도가 시작되는 시각(초)
        return -1;
    }

    static void q1() {
        long expected = 0;
        Duration initial = Duration.ofSeconds(2);
        double coeff = 2.0;
        Duration max = Duration.ofSeconds(30);
        for (int n = 1; n <= 7; n++) {   // 8번째 시도 직전까지의 대기 합
            expected += Math.min((long) (initial.toMillis() * Math.pow(coeff, n - 1)),
                    max.toMillis()) / 1000;
        }
        System.out.println("정답(계산값) = " + expected + "초");
        System.out.println(q1AnswerSeconds() == expected ? "Q1 PASS" : "Q1 FAIL");
    }

    // ==================================================================
    // 문제 2 — 무한 재시도 진단
    //
    // 아래 워크플로우를 실행하면 영원히 끝나지 않습니다.
    //   ./gradlew run -PmainClass=com.example.order.Exercise --args="2"
    // 다른 터미널에서 아래 명령을 2분 간격으로 두 번 실행하고,
    //   temporal workflow describe -w order-6002
    // 관찰한 값을 상수에 채워 넣으세요.
    // ==================================================================
    static final int    Q2_HISTORY_LENGTH = 0;      // TODO: 여기에 작성 — History Length 는 몇인가
    static final String Q2_PENDING_STATE  = "";     // TODO: 여기에 작성 — Pending Activities 의 State
    static final String Q2_FAILURE_TYPE   = "";     // TODO: 여기에 작성 — Last Failure 의 예외 타입

    public static class Q2Activity implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(Q2Activity.class);
        @Override public String charge(String orderId, long amount) {
            log.info("[{}] 결제 시도", orderId);
            throw new IllegalStateException("card network down");
        }
        @Override public void refund(String paymentId) {}
    }

    public static class Q2Workflow implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());
        @Override public String processOrder(OrderRequest req) {
            return payment.charge(req.orderId(), req.amount());
        }
    }

    // ==================================================================
    // 문제 3 — 예외 벗겨 내기
    //
    // Q3Activity 는 details 두 개(재고 수량, 요청 수량)를 담은
    // ApplicationFailure 를 던집니다. 워크플로우에서 이를 잡아
    // "재고 3개, 요청 10개 — 7개 부족" 형태의 문자열을 반환하세요.
    // ==================================================================
    public static class Q3Activity implements PaymentActivity {
        @Override public String charge(String orderId, long amount) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "not enough stock", "OutOfStockError", 3, 10);
        }
        @Override public void refund(String paymentId) {}
    }

    public static class Q3Workflow implements OrderWorkflow {
        private final PaymentActivity payment = Workflow.newActivityStub(
                PaymentActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());

        @Override
        public String processOrder(OrderRequest req) {
            try {
                return payment.charge(req.orderId(), req.amount());
            } catch (ActivityFailure af) {
                // TODO: 여기에 작성
                //  - af 를 한 겹 벗겨 ApplicationFailure 를 얻고
                //  - getType() 이 "OutOfStockError" 인지 확인하고
                //  - getDetails().get(0, Integer.class) / get(1, Integer.class) 로 숫자를 꺼내
                //  - "재고 3개, 요청 10개 — 7개 부족" 을 반환
                return "";
            }
        }
    }

    // ==================================================================
    // 문제 4 — 실패 분류
    //
    // 아래 액티비티는 세 상황을 전부 RuntimeException 으로 던집니다.
    // 각각 알맞은 ApplicationFailure 로 바꾸세요.
    //   - 카드 번호가 유효하지 않다  → 재시도 불가, type "InvalidCardError"
    //   - 잔액이 부족하다            → 재시도 불가, type "InsufficientBalanceError", details 로 잔액/필요금액
    //   - 게이트웨이가 502 를 뱉는다 → 재시도 가능, type "GatewayUnavailableError"
    //
    // 실행 후 확인:
    //   temporal workflow show -w order-6004 --output json | jq '.events[]
    //     | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_FAILED") .activityTaskFailedEventAttributes.retryState'
    // 재시도 불가 케이스에서 RETRY_STATE_NON_RETRYABLE_FAILURE 가 나와야 정답입니다.
    // ==================================================================
    public static class Q4Activity implements PaymentActivity {
        @Override
        public String charge(String orderId, long amount) {
            String cardNumber = "4111-XXXX-BAD";
            long balance = 12_000L;
            boolean gatewayUp = false;

            if (!cardNumber.matches("\\d{4}-\\d{4}-\\d{4}-\\d{4}")) {
                // TODO: 여기에 작성
                throw new RuntimeException("invalid card number");
            }
            if (balance < amount) {
                // TODO: 여기에 작성
                throw new RuntimeException("insufficient balance");
            }
            if (!gatewayUp) {
                // TODO: 여기에 작성
                throw new RuntimeException("gateway 502");
            }
            return "PAY-" + orderId;
        }
        @Override public void refund(String paymentId) {}
    }

    // ==================================================================
    // 문제 5 — doNotRetry 가 동작하지 않는다
    //
    // 아래 옵션은 IllegalArgumentException 을 재시도에서 제외하려는 의도이고,
    // Q5Activity 는 실제로 IllegalArgumentException 을 던집니다.
    // 그런데 실행해 보면 5번 다 재시도됩니다. 왜일까요? 고치세요.
    // (힌트가 두 개 있습니다 — 하나는 이름 표기, 하나는 상속)
    // ==================================================================
    static ActivityOptions q5Options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(200))
                        .setMaximumAttempts(5)
                        .setDoNotRetry("RuntimeException")   // TODO: 여기에 작성 — 이 줄을 고치세요
                        .build())
                .build();
    }

    public static class Q5Activity implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(Q5Activity.class);
        @Override public String charge(String orderId, long amount) {
            log.info("[{}] Q5 시도", orderId);
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        @Override public void refund(String paymentId) {}
    }

    // ==================================================================
    // 문제 6 — 재시도가 한 번도 일어나지 않는다
    //
    // 아래 액티비티를 쓰는 워크플로우는 COMPLETED 로 끝나고 로그도 깨끗합니다.
    // 그런데 결제는 되지 않았습니다. describe 를 봐도 Attempt 는 1 이고
    // 히스토리에는 ActivityTaskCompleted 가 찍혀 있습니다.
    // 무엇이 잘못됐는지 찾아 고치세요. 이 스텝에서 가장 중요한 함정입니다.
    // ==================================================================
    public static class Q6Activity implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(Q6Activity.class);

        @Override
        public String charge(String orderId, long amount) {
            try {
                throw new RuntimeException("payment gateway 500");
            } catch (Exception e) {
                log.error("[{}] 결제 실패", orderId, e);
                // TODO: 여기에 작성 — 아래 한 줄이 문제입니다
                return null;
            }
        }
        @Override public void refund(String paymentId) {}
    }

    // ------------------------------------------------------------------
    // 실행부
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        String q = args.length > 0 ? args[0] : "1";
        if (q.equals("1")) { q1(); return; }
        if (q.equals("cleanup")) { cleanup(); return; }

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        String id = "order-600" + q;
        switch (q) {
            case "2" -> {
                worker.registerWorkflowImplementationTypes(Q2Workflow.class);
                worker.registerActivitiesImplementations(new Q2Activity());
            }
            case "3" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q3Activity());
            }
            case "4" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q4Activity());
            }
            case "5" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q5Activity());
            }
            case "6" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q6Activity());
            }
            default -> throw new IllegalArgumentException("1~6 또는 cleanup");
        }

        factory.start();
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(TASK_QUEUE).build());
        System.out.println("=== Q" + q + " → " + id + " ===");
        System.out.println("확인: temporal workflow describe -w " + id);
        try {
            System.out.println("결과: " + wf.processOrder(
                    new OrderRequest(id.substring(6), "C-1", "SKU-A", 1, 39_000, "서울시 강남구")));
        } catch (Exception e) {
            System.out.println("실패: " + e + (e.getCause() == null ? "" : " / cause=" + e.getCause()));
        }
    }

    /** 이 문제지가 만든 order-6xxx 워크플로우를 정리합니다. */
    static void cleanup() {
        System.out.println("아래 명령으로 남은 워크플로우를 정리하세요.");
        System.out.println("  temporal workflow list --query 'WorkflowId STARTS_WITH \"order-6\"'");
        for (int i = 2; i <= 6; i++) {
            System.out.println("  temporal workflow terminate -w order-600" + i + " --reason \"연습문제 정리\"");
        }
    }
}
