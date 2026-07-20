package com.example.order;

// =====================================================================================
// Step 03 — 워크플로우 정의와 결정성 : Exercise (6문제)
//
// 실행 방법
//   ./gradlew run -PmainClass=com.example.order.Exercise
//
// 규칙
//   - `// TODO: 여기에 작성` 자리를 채우세요.
//   - 정답은 Solution.java 에 있습니다. 먼저 스스로 풀어 보세요.
//   - 문제 6 은 코드를 쓰지 않습니다. 파일 맨 아래 주석을 읽고 답하세요.
// =====================================================================================

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Exercise {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(
            String orderId, String customerId, String sku, int qty, long amount, String address) {}

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String chargeIdempotent(String orderId, long amount, String idempotencyKey);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
    }

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    // =================================================================================
    // 문제 1 — 결정성 위반 지점을 전부 찾으세요.
    //
    // 아래 클래스에는 결정성 위반(또는 리플레이 시 문제가 되는 코드)이 **7군데** 있습니다.
    // 각 줄 위에 `// [위반 N] 이유` 형태로 주석을 다세요.
    // 힌트: 눈에 잘 띄는 것 5개 외에 두 개가 더 숨어 있습니다.
    //       하나는 컬렉션과 관련이 있고, 하나는 로깅과 관련이 있습니다.
    // =================================================================================
    public static class Ex1BrokenWorkflowImpl implements OrderWorkflow {

        private static final Logger log = LoggerFactory.getLogger(Ex1BrokenWorkflowImpl.class);

        private static int TOTAL_ORDERS = 0;

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {

            // TODO: 여기에 작성 — 아래 코드에서 위반 7군데를 찾아 주석으로 표시

            String idemKey = UUID.randomUUID().toString();

            Instant now = Instant.now();
            boolean weekend = now.toString().contains("T00");

            TOTAL_ORDERS++;

            Map<String, Integer> items = new HashMap<>();
            items.put("SKU-A", req.qty());
            items.put("SKU-B", 1);
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                inventory.reserve(req.orderId(), e.getKey(), e.getValue());
            }

            String carrier = Math.random() < 0.5 ? "CJ" : "LOTTE";

            String region = System.getenv("SHIPPING_REGION");

            payment.chargeIdempotent(req.orderId(), req.amount(), idemKey);

            if (!weekend) {
                shipping.requestShipment(req.orderId(), req.address() + "/" + carrier + "/" + region);
            }

            log.info("[{}] 처리 완료 total={}", req.orderId(), TOTAL_ORDERS);
            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 문제 2 — 문제 1 에서 찾은 위반을 전부 고쳐 결정적인 워크플로우로 만드세요.
    //
    // 조건
    //   - 동작(분기 조건의 "의미")은 그대로 유지할 것. 분기 자체를 없애지 말 것.
    //   - idemKey 는 액티비티의 멱등키로 쓰이므로 **리플레이해도 같은 값**이어야 함.
    //   - 주문 건수 카운터는 워크플로우 인스턴스 단위로 세도록 바꿀 것.
    // =================================================================================
    public static class Ex2FixedWorkflowImpl implements OrderWorkflow {

        // TODO: 여기에 작성 — 로거를 올바른 것으로 바꾸세요

        // TODO: 여기에 작성 — static 카운터를 인스턴스 필드로 바꾸세요

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {

            // TODO: 여기에 작성 — 문제 1 의 본문 전체를 결정적으로 다시 쓰세요

            return req.orderId() + " COMPLETED";
        }
    }

    // =================================================================================
    // 문제 3 — @WorkflowMethod 가 세 개입니다. Worker 등록에서 다음 예외가 납니다.
    //
    //   java.lang.IllegalArgumentException: Duplicated @WorkflowMethod: ...
    //
    // 이 인터페이스를 고치세요.
    // 힌트: 세 메서드는 각각 "시작", "외부에서 값을 밀어 넣기", "현재 상태 조회"의 의도입니다.
    //       메서드를 지우는 것이 답이 아닐 수 있습니다.
    // =================================================================================
    @WorkflowInterface
    public interface Ex3MultiMethodWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);

        @WorkflowMethod
        void applyCoupon(String couponCode);      // 외부에서 쿠폰을 적용하고 싶다

        @WorkflowMethod
        String currentStatus();                    // 지금 어느 단계인지 알고 싶다
    }

    // TODO: 여기에 작성 — 고친 인터페이스를 아래에 정의하세요
    //       필요한 import 도 직접 추가하세요.
    //
    // @WorkflowInterface
    // public interface Ex3FixedWorkflow {
    //     ...
    // }

    // =================================================================================
    // 문제 4 — 피처 플래그를 워크플로우에서 안전하게 읽으세요.
    //
    // FeatureFlags.isNewPricingEnabled() 는 외부 설정 서버를 조회하는 정적 메서드입니다.
    // 워크플로우에서 직접 호출하면 결정성 위반입니다.
    //
    // 조건
    //   - 이 플래그는 워크플로우가 도는 며칠 동안 **바뀔 수 있습니다**.
    //   - 아래 반복문에서 매 라운드마다 읽어야 합니다.
    //
    // Workflow.sideEffect 와 Workflow.mutableSideEffect 중 무엇을 쓸지 고르고,
    // 왜 그런지 주석으로 근거를 적으세요.
    // =================================================================================
    public static class FeatureFlags {
        static boolean isNewPricingEnabled() {
            return true;   // 실제로는 외부 조회
        }
    }

    public static class Ex4FlagWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(Ex4FlagWorkflowImpl.class);

        @Override
        public String processOrder(OrderRequest req) {
            for (int i = 0; i < 10; i++) {

                // TODO: 여기에 작성 — 플래그를 결정적으로 읽으세요
                boolean newPricing = false;

                // TODO: 여기에 작성 — sideEffect / mutableSideEffect 중 무엇을 골랐고 왜인지 주석으로

                log.info("[{}] round={} newPricing={}", req.orderId(), i, newPricing);
                Workflow.sleep(Duration.ofHours(6));
            }
            return req.orderId() + " FLAG";
        }
    }

    // =================================================================================
    // 문제 5 — 아래 순차 호출을 병렬화하고, 히스토리 이벤트 수를 예측하세요.
    //
    // 조건
    //   - charge / reserve / requestShipment 세 액티비티는 **서로 완전히 독립**입니다.
    //     (이 문제에 한해 배송이 결제 결과를 필요로 하지 않는다고 가정합니다.)
    //   - Async.function 과 Promise.allOf 를 쓰세요.
    //
    // 예측
    //   순차 버전의 History Length: ____ 개   ← TODO: 여기에 작성
    //   병렬 버전의 History Length: ____ 개   ← TODO: 여기에 작성
    //   계산 근거:                              ← TODO: 여기에 작성
    //
    // 실제로 두 버전을 돌린 뒤 `temporal workflow show -w order-XXXX` 로 확인하세요.
    // =================================================================================
    public static class Ex5SequentialWorkflowImpl implements OrderWorkflow {

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            String key = Workflow.randomUUID().toString();
            payment.chargeIdempotent(req.orderId(), req.amount(), key);
            inventory.reserve(req.orderId(), req.sku(), req.qty());
            shipping.requestShipment(req.orderId(), req.address());
            return req.orderId() + " SEQ";
        }
    }

    public static class Ex5ParallelWorkflowImpl implements OrderWorkflow {

        private final ActivityOptions opts =
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {

            // TODO: 여기에 작성 — 세 액티비티를 병렬로 실행하고 전부 끝날 때까지 대기

            return req.orderId() + " PAR";
        }
    }

    public static void main(String[] args) {
        System.out.println("Exercise — 각 문제의 TODO 를 채운 뒤 Worker 에 등록해 실행하세요.");
        System.out.println("정답은 Solution.java 를 참고하세요.");
    }
}

// =====================================================================================
// 문제 6 — 스택트레이스를 읽고 원인 줄을 지목하세요. (코드를 쓰지 않는 문제)
//
// 아래 워크플로우가 운영에서 Worker 재배포 후 멈췄습니다.
//
//   01: @Override
//   02: public String processOrder(OrderRequest req) {
//   03:     String key = Workflow.randomUUID().toString();
//   04:     long deadline = Workflow.currentTimeMillis() + 86_400_000L;
//   05:
//   06:     if (LocalDate.now().getDayOfWeek() == DayOfWeek.SATURDAY) {
//   07:         Workflow.sleep(Duration.ofHours(24));
//   08:         payment.chargeIdempotent(req.orderId(), req.amount(), key);
//   09:     } else {
//   10:         payment.chargeIdempotent(req.orderId(), req.amount(), key);
//   11:     }
//   12:
//   13:     Map<String, Integer> items = new LinkedHashMap<>();
//   14:     items.put(req.sku(), req.qty());
//   15:     for (var e : items.entrySet()) {
//   16:         inventory.reserve(req.orderId(), e.getKey(), e.getValue());
//   17:     }
//   18:     return req.orderId() + " COMPLETED";
//   19: }
//
// Worker 콘솔:
//
//   io.temporal.worker.NonDeterministicException: Failure handling event 5 of type
//   'EVENT_TYPE_TIMER_STARTED' during replay. Command
//   CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match
//   Event=EventType=EVENT_TYPE_TIMER_STARTED
//       at io.temporal.internal.statemachines.WorkflowStateMachines
//            .createEventProcessingException(WorkflowStateMachines.java:249)
//       at io.temporal.internal.statemachines.WorkflowStateMachines
//            .handleEventsBatch(WorkflowStateMachines.java:222)
//       at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler
//            .applyServerHistory(ReplayWorkflowRunTaskHandler.java:200)
//       ...
//   Caused by: io.temporal.internal.statemachines.InternalWorkflowTaskException:
//   Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED'. IsReplaying=true,
//   PreviousStartedEventId=3, workflowTaskStartedEventId=8
//
// temporal workflow describe:
//
//   Status            RUNNING
//   History Length    8
//   Pending Workflow Task:
//     Attempt         19
//     Last Failure    NonDeterministicException: ...
//
// 답하세요.
//   (a) 원인이 되는 줄 번호는?                       → TODO: 여기에 작성
//   (b) 첫 실행에서는 어느 분기를 탔는가? 근거는?      → TODO: 여기에 작성
//   (c) 리플레이에서는 어느 분기를 탔는가? 근거는?     → TODO: 여기에 작성
//   (d) Status 가 FAILED 가 아니라 RUNNING 인 이유는? → TODO: 여기에 작성
//   (e) 04 번 줄은 위반인가?                          → TODO: 여기에 작성
// =====================================================================================
