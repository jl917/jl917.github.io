package com.example.order;

/*
 * Step 05 — 재시도와 실패 처리 / 정답과 해설
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Solution --args="1"   (1~6)
 *
 * 각 문제마다 "왜 그 답인가" 를 긴 주석으로 설명합니다.
 * 코드만 읽지 말고, 주석에 적힌 temporal 명령을 실제로 돌려 확인하세요.
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

public class Solution {

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
    // 정답 1 — 백오프 계산
    //
    //   initialInterval=2s, backoffCoefficient=2.0, maximumInterval=30s
    //
    //   interval(n) = min(2 × 2^(n-1), 30)
    //     n=1 → 2      n=2 → 4      n=3 → 8
    //     n=4 → 16     n=5 → min(32,30)=30   ← 여기서부터 상한이 개입
    //     n=6 → 30     n=7 → 30
    //
    //   8번째 시도가 시작되는 시각 = 1~7번째 시도 뒤의 대기 합
    //     2 + 4 + 8 + 16 + 30 + 30 + 30 = 120초
    //
    // 핵심은 "maximumInterval 이 언제부터 개입하는가" 입니다.
    // 2 × 2^4 = 32 > 30 이므로 5번째 대기부터 잘립니다.
    // maximumInterval 을 지정하지 않았다면 기본값이 initialInterval × 100 = 200초라
    // 상한이 훨씬 늦게(2×2^6=128 다음인 256 에서) 개입해 답이 달라집니다.
    // "기본 maximumInterval 은 100초 고정"이 아니라 "initialInterval 의 100배"임에
    // 주의하세요. initialInterval 이 1초일 때만 100초입니다.
    // ==================================================================
    static long q1AnswerSeconds() {
        return 120;
    }

    // ==================================================================
    // 정답 2 — 무한 재시도 진단
    //
    //   Q2_HISTORY_LENGTH = 5
    //   Q2_PENDING_STATE  = "BACKOFF"  (또는 관측 순간에 따라 "STARTED")
    //   Q2_FAILURE_TYPE   = "java.lang.IllegalStateException"
    //
    // History Length 가 5 인 이유가 이 문제의 전부입니다.
    //   1 WorkflowExecutionStarted
    //   2 WorkflowTaskScheduled
    //   3 WorkflowTaskStarted
    //   4 WorkflowTaskCompleted
    //   5 ActivityTaskScheduled
    // 이후 몇 번을 재시도해도 이벤트는 늘지 않습니다. 재시도는 5번 이벤트에 붙은
    // attempt 카운터가 올라갈 뿐입니다. 2분 뒤에 다시 describe 해도 History Length 는
    // 그대로 5 이고 Attempt 만 커져 있습니다. 이것을 직접 본 것이 핵심입니다.
    //
    // State 는 관측 시점에 따라 다릅니다. 백오프 간격이 이미 32초, 64초로 길어졌다면
    // 대부분의 순간에 BACKOFF 로 보이고, 아주 짧은 순간에만 STARTED 입니다.
    //
    // Failure 타입이 FQCN 인 것도 중요합니다. ApplicationFailure 를 명시적으로 던지지
    // 않은 일반 예외는 SDK 가 클래스의 전체 이름을 type 으로 채웁니다. 이 사실이
    // 정답 5 의 doNotRetry 문제로 그대로 이어집니다.
    //
    // 검증:
    //   temporal workflow describe -w order-6002 --output json \
    //     | jq '{len: .workflowExecutionInfo.historyLength,
    //            attempt: .pendingActivities[0].attempt,
    //            state: .pendingActivities[0].state}'
    // ==================================================================

    // ==================================================================
    // 정답 3 — 예외 벗겨 내기
    //
    // 규칙은 하나입니다: ActivityFailure 를 getCause() 로 "한 번만" 벗기면
    // ApplicationFailure 가 나옵니다. 두 번 벗기려 하면 null 이라 NPE 가 납니다.
    // ApplicationFailure 는 체인의 끝이고 그 아래에는 아무것도 없습니다.
    // (원래 예외 객체는 다른 JVM 에 있으므로 전달될 수 없습니다. 스택트레이스는
    //  문자열로만 보존됩니다 — appFailure.getOriginalStackTrace() 로 볼 수 있습니다.)
    //
    // details 의 인덱스와 타입은 던진 쪽과 정확히 맞춰야 합니다.
    // newNonRetryableFailure(msg, type, 3, 10) 로 던졌으니
    // get(0, Integer.class) = 3, get(1, Integer.class) = 10 입니다.
    // Long.class 로 꺼내면 역직렬화 예외가 납니다.
    // ==================================================================
    public static class Q3Activity implements PaymentActivity {
        @Override public String charge(String orderId, long amount) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "not enough stock", "OutOfStockError", 3, 10);
        }
        @Override public void refund(String paymentId) {}
    }

    public static class Q3Workflow implements OrderWorkflow {
        private static final Logger log = Workflow.getLogger(Q3Workflow.class);

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
                // af.getMessage() 는 언제나 "Activity task failed" 입니다. 쓸모없습니다.
                log.info("겉껍질: {}", af.getMessage());

                if (af.getCause() instanceof ApplicationFailure appFailure
                        && "OutOfStockError".equals(appFailure.getType())) {
                    int stock = appFailure.getDetails().get(0, Integer.class);
                    int requested = appFailure.getDetails().get(1, Integer.class);
                    return "재고 %d개, 요청 %d개 — %d개 부족"
                            .formatted(stock, requested, requested - stock);
                }
                throw af;   // 예상하지 못한 실패는 삼키지 말고 다시 던집니다
            }
        }
    }

    // ==================================================================
    // 정답 4 — 실패 분류
    //
    // 판단 기준은 단 하나입니다: "다시 해 보면 성공할 가능성이 있는가."
    //   - 카드 번호 형식 오류: 입력이 바뀌지 않는 한 100번을 해도 똑같습니다 → 재시도 불가
    //   - 잔액 부족: 재시도로 잔액이 늘지 않습니다 → 재시도 불가
    //     (계좌에 돈이 들어올 때까지 기다리는 게 목적이라면 그건 재시도가 아니라
    //      Step 06 의 타이머·시그널 대기로 풀어야 할 문제입니다.)
    //   - 게이트웨이 502: 몇 초 뒤면 돌아올 수 있습니다 → 재시도
    //
    // details 에 잔액과 필요 금액을 실어 보내는 이유는, 워크플로우가 "얼마가 부족한지"
    // 를 알아야 고객에게 의미 있는 안내를 할 수 있기 때문입니다. 메시지 문자열을
    // 파싱하는 대신 구조화된 값으로 넘기세요.
    //
    // 검증:
    //   temporal workflow show -w order-6004 --output json | jq '.events[]
    //     | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_FAILED")
    //     .activityTaskFailedEventAttributes.retryState'
    //   → "RETRY_STATE_NON_RETRYABLE_FAILURE"
    // Start Time 과 Close Time 이 같은 초인지도 확인하세요. 백오프 없이 즉시 끝나야 합니다.
    // ==================================================================
    public static class Q4Activity implements PaymentActivity {
        @Override
        public String charge(String orderId, long amount) {
            String cardNumber = "4111-XXXX-BAD";
            long balance = 12_000L;
            boolean gatewayUp = false;

            if (!cardNumber.matches("\\d{4}-\\d{4}-\\d{4}-\\d{4}")) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "invalid card number", "InvalidCardError", cardNumber);
            }
            if (balance < amount) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "insufficient balance", "InsufficientBalanceError", balance, amount);
            }
            if (!gatewayUp) {
                // newFailure = 재시도됩니다. nonRetryable 플래그가 false 입니다.
                throw ApplicationFailure.newFailure(
                        "gateway 502", "GatewayUnavailableError");
            }
            return "PAY-" + orderId;
        }
        @Override public void refund(String paymentId) {}
    }

    // ==================================================================
    // 정답 5 — doNotRetry 가 동작하지 않는 두 가지 이유
    //
    // (1) 이름 표기: doNotRetry 는 ApplicationFailure.getType() 과 "문자열 완전 일치"
    //     로 비교합니다. ApplicationFailure 를 명시적으로 던지지 않은 일반 예외는
    //     SDK 가 FQCN(패키지 포함 클래스명)을 type 으로 채웁니다.
    //       실제 type = "java.lang.IllegalArgumentException"
    //       설정한 값 = "RuntimeException"          → 안 맞음
    //     짧은 이름으로는 절대 매칭되지 않습니다.
    //
    // (2) 상속을 따지지 않음: 설사 "java.lang.RuntimeException" 이라고 정확히 적었어도
    //     IllegalArgumentException 은 걸러지지 않습니다. instanceof 가 아니라
    //     equals 비교이기 때문입니다. 상위 타입을 적어 두고 "하위 클래스도 걸리겠지"
    //     라고 기대하면 조용히 재시도가 계속됩니다.
    //
    // 그래서 정답은 던지는 예외의 FQCN 을 정확히 적는 것입니다.
    // 다만 실무에서는 이 방식 자체가 깨지기 쉽습니다 — 액티비티 구현이 예외 타입을
    // 바꾸면 doNotRetry 목록이 조용히 무효가 되고, 컴파일러도 잡아 주지 않습니다.
    // 가능하면 액티비티 쪽에서 newNonRetryableFailure() 로 명시하는 편이 안전하고,
    // doNotRetry 는 "구현을 고칠 수 없는 외부 라이브러리" 에만 쓰세요.
    //
    // 검증: retryState 가 RETRY_STATE_NON_RETRYABLE_FAILURE 인지,
    //       그리고 Worker 로그에 "Q5 시도" 가 딱 한 번만 찍히는지 보세요.
    // ==================================================================
    static ActivityOptions q5Options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(200))
                        .setMaximumAttempts(5)
                        .setDoNotRetry("java.lang.IllegalArgumentException")   // FQCN
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

    public static class Q5Workflow implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, q5Options());
        @Override public String processOrder(OrderRequest req) {
            return payment.charge(req.orderId(), req.amount());
        }
    }

    // ==================================================================
    // 정답 6 — 재시도가 한 번도 일어나지 않는 이유
    //
    // 고치는 것은 한 줄입니다: return null → throw e.
    // 하지만 원칙이 더 중요합니다.
    //
    //   Temporal 은 액티비티 메서드에서 "예외가 밖으로 나가야만" 실패로 인식합니다.
    //   정상 반환하면 ActivityTaskCompleted 가 기록되고, 재시도는 시작조차 하지 않습니다.
    //
    // 이 코드가 위험한 이유는 조용하기 때문입니다.
    //   - 워크플로우는 COMPLETED 로 끝납니다.
    //   - describe 의 Attempt 는 1 입니다.
    //   - 히스토리는 정상 실행과 완전히 동일합니다.
    //   - 에러 로그는 남지만, 그건 로그일 뿐 흐름에는 아무 영향이 없습니다.
    // 결제가 안 됐는데 워크플로우는 배송을 요청합니다. "에러 없이 조용히 틀리는" 전형입니다.
    //
    // 로그를 남기고 싶다면 남기되, 반드시 다시 던지세요. 아래가 관용구입니다.
    //   catch (Exception e) { log.error("...", e); throw e; }
    // 또는 아예 catch 하지 말고 Temporal 이 기록하게 두는 편이 낫습니다. SDK 가
    // "Activity failure. ActivityId=..., attempt=N" 을 스택트레이스와 함께 찍어 줍니다.
    //
    // 굳이 예외를 잡아야 한다면 — 예컨대 원인을 분류해서 재시도 여부를 정하고 싶다면 —
    // 아래처럼 ApplicationFailure 로 다시 던집니다.
    //   catch (HttpException e) {
    //       if (e.status() == 400) throw ApplicationFailure.newNonRetryableFailure(...);
    //       throw ApplicationFailure.newFailure(...);
    //   }
    // ==================================================================
    public static class Q6Activity implements PaymentActivity {
        private static final Logger log = Workflow.getLogger(Q6Activity.class);

        @Override
        public String charge(String orderId, long amount) {
            try {
                throw new RuntimeException("payment gateway 500");
            } catch (RuntimeException e) {
                log.error("[{}] 결제 실패", orderId, e);
                throw e;                    // ← 반드시 다시 던진다
            }
        }
        @Override public void refund(String paymentId) {}
    }

    // ------------------------------------------------------------------
    // 실행부
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        String q = args.length > 0 ? args[0] : "1";

        if (q.equals("1")) {
            System.out.println("정답 1: 8번째 시도는 t=" + q1AnswerSeconds() + "초에 시작");
            System.out.println("  2 + 4 + 8 + 16 + 30 + 30 + 30 = 120");
            System.out.println("  (5번째 대기부터 maximumInterval=30s 상한이 개입)");
            return;
        }
        if (q.equals("2")) {
            System.out.println("정답 2: History Length=5, State=BACKOFF, "
                    + "Failure Type=java.lang.IllegalStateException");
            System.out.println("  재시도는 이벤트를 만들지 않습니다. attempt 만 올라갑니다.");
            return;
        }

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        String id = "order-700" + q;
        switch (q) {
            case "3" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q3Activity());
            }
            case "4" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q4Activity());
            }
            case "5" -> {
                worker.registerWorkflowImplementationTypes(Q5Workflow.class);
                worker.registerActivitiesImplementations(new Q5Activity());
            }
            case "6" -> {
                worker.registerWorkflowImplementationTypes(Q3Workflow.class);
                worker.registerActivitiesImplementations(new Q6Activity());
            }
            default -> throw new IllegalArgumentException("1~6");
        }

        factory.start();
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(TASK_QUEUE).build());
        System.out.println("=== 정답 " + q + " → " + id + " ===");
        try {
            System.out.println("결과: " + wf.processOrder(
                    new OrderRequest(id.substring(6), "C-1", "SKU-A", 1, 39_000, "서울시 강남구")));
        } catch (Exception e) {
            System.out.println("실패: " + e + (e.getCause() == null ? "" : " / cause=" + e.getCause()));
        }
        System.out.println("검증: temporal workflow show -w " + id + " --output json | jq "
                + "'.events[] | select(.eventType==\"EVENT_TYPE_ACTIVITY_TASK_FAILED\") "
                + ".activityTaskFailedEventAttributes.retryState'");
    }
}
