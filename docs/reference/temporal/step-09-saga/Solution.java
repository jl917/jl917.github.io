package com.example.order;

/*
 * Step 09 — Saga 보상 트랜잭션 / Solution.java
 *
 * Exercise.java 6문제의 정답과 해설입니다.
 * 반드시 먼저 풀어 본 뒤에 여세요.
 *
 * 실행: ./gradlew run -PmainClass=com.example.order.Solution
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Solution {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {
    }

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
        @ActivityMethod void refund(String paymentId);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
        @ActivityMethod void release(String reservationId);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String requestShipment(String orderId, String address);
        @ActivityMethod void cancelShipment(String shipmentId);
    }

    @ActivityInterface
    public interface OpsAlertActivity {
        @ActivityMethod void raiseManualIntervention(String orderId, String code, String detail);
    }

    /* ==================================================================
     * 정답 1 — Saga 클래스 없이 List<Runnable> 로 보상 구현
     * ==================================================================
     *
     * 왜 이 답인가
     * ------------
     * Saga 클래스가 하는 일은 정확히 아래 세 가지입니다.
     *
     *   ① Deque 에 보상을 쌓고 LIFO 로 꺼낸다        → Collections.reverse + for 루프
     *   ② 실패한 보상을 suppressed 로 모은다          → addSuppressed
     *   ③ 직렬/병렬, 중단/계속 4가지 조합을 처리한다   → 여기서는 "직렬 + 계속" 하나만
     *
     * 즉 Saga 는 마법이 아니라 이 20줄의 대체품입니다.
     *
     * 그런데 여기서 반드시 구분해야 할 것이 있습니다.
     * ─────────────────────────────────────────────────────────────
     *  "Worker 가 보상 도중에 죽어도, 재기동한 Worker 가 히스토리를 리플레이해서
     *   'release 는 이미 완료됐고 refund 부터 하면 된다' 를 정확히 알아낸다"
     *  ─ 이 능력은 Saga 클래스가 주는 것이 아니라 Temporal 런타임이 주는 것입니다.
     * ─────────────────────────────────────────────────────────────
     * 그래서 아래 손코딩 버전도 그 마법은 그대로 누립니다.
     * compensations 라는 평범한 ArrayList 조차, 리플레이 때 처음부터 다시 채워지므로
     * 결과적으로 "죽기 직전 상태" 가 완벽히 복원됩니다.
     *
     * 반대로 말하면, Saga 클래스를 쓴다고 해서 얻는 내구성은 없습니다.
     * 보상이 두 개뿐이고 조건 분기가 복잡하다면 손코딩이 더 읽기 좋을 수 있습니다.
     * 중요한 건 클래스 이름이 아니라 세 가지 원칙입니다:
     *   "성공한 것만 역순으로" + "보상은 멱등" + "마지막에 예외를 다시 던진다"
     */
    @WorkflowInterface
    public interface Q1ManualSagaWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q1Solution implements Q1ManualSagaWorkflow {

        private static final Logger log = Workflow.getLogger(Q1Solution.class);

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            List<Runnable> compensations = new ArrayList<>();
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                // (a) 성공한 직후에만 등록한다. charge 가 실패하면 이 줄에 도달하지 않는다.
                compensations.add(() -> payment.refund(paymentId));

                String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
                compensations.add(() -> inventory.release(reservationId));

                String shipmentId = shipping.requestShipment(req.orderId(), req.address());
                compensations.add(() -> shipping.cancelShipment(shipmentId));

                return req.orderId() + " COMPLETED";

            } catch (Exception e) {
                // (b) 역순으로 뒤집는다. 배송취소 → 재고해제 → 결제환불 순이 되어야 한다.
                Collections.reverse(compensations);

                RuntimeException collected = null;
                for (Runnable c : compensations) {
                    try {
                        c.run();
                    } catch (RuntimeException ce) {
                        // (c) 하나 실패해도 나머지는 계속. 여기서 break 하면
                        //     "재고는 풀렸는데 결제는 환불 안 된" 상태로 멈춘다.
                        log.error("[{}] 보상 실패 — 계속 진행", req.orderId(), ce);
                        // (d) suppressed 로 모은다
                        if (collected == null) {
                            collected = new RuntimeException("Compensation failed", ce);
                        } else {
                            collected.addSuppressed(ce);
                        }
                    }
                }
                if (collected != null) {
                    e.addSuppressed(collected);
                }
                // (e) 원래 예외를 다시 던진다. 이 줄이 없으면 워크플로우가 COMPLETED 로 끝난다.
                throw e;
            }
        }
    }

    /* ==================================================================
     * 정답 2 — 멱등한 refund
     * ==================================================================
     *
     * 왜 조기 반환이 두 개인가
     * ------------------------
     * 두 개의 서로 다른 시나리오를 각각 막습니다. 하나라도 빠지면 다음이 터집니다.
     *
     *  ┌──────────────────┬──────────────────────────────────────────────────┐
     *  │ 빠뜨린 체크       │ 언제 터지나                                       │
     *  ├──────────────────┼──────────────────────────────────────────────────┤
     *  │ null 체크 없음    │ charge 가 정말 실패했는데(승인 0건) 보상이 등록된    │
     *  │                  │ 경우. 예컨대 "호출 전 등록" 방식으로 바꿨을 때.      │
     *  │                  │ → IllegalArgumentException → 보상 단계가 통째로 막힘 │
     *  ├──────────────────┼──────────────────────────────────────────────────┤
     *  │ REFUNDED 체크 없음│ 보상이 무한 재시도로 설정돼 있는데 PG 가 환불을      │
     *  │                  │ 처리한 직후 응답이 타임아웃된 경우. 재시도가 발생.    │
     *  │                  │ → DuplicateRefundException, 또는 PG 가 막지 않으면   │
     *  │                  │   실제 이중 환불(고객에게 78,000원)                 │
     *  └──────────────────┴──────────────────────────────────────────────────┘
     *
     * 두 번째가 특히 위험한 이유: 이중 환불은 히스토리에도 예외 로그에도 남지 않습니다.
     * refund 는 두 번 다 "성공" 으로 기록됩니다. 정산 데이터를 대조해야만 발견됩니다.
     *
     * 그리고 9-4 의 "호출 전에 등록? 후에 등록?" 딜레마가 여기서 해소됩니다.
     * 보상이 멱등하면 등록 시점은 더 이상 정답이 하나로 정해지는 문제가 아닙니다.
     * 실무 표준 조합: "호출 후 등록 + 멱등 보상 + 별도의 미아(orphan) 정리 배치".
     */
    public static class FakePgClient {
        public enum PgStatus { APPROVED, REFUNDED }
        public record PgPayment(String paymentId, long amount, PgStatus status) {}

        private final Map<String, PgPayment> store = new ConcurrentHashMap<>();

        public PgPayment approve(String idempotencyKey, long amount) {
            // 멱등 키가 같으면 같은 paymentId. at-least-once 재시도를 이 한 줄이 막는다.
            return store.computeIfAbsent(idempotencyKey,
                    k -> new PgPayment("pay_" + Integer.toHexString(k.hashCode() & 0xffff),
                            amount, PgStatus.APPROVED));
        }

        public PgPayment findByPaymentId(String paymentId) {
            return store.values().stream()
                    .filter(p -> p.paymentId().equals(paymentId))
                    .findFirst().orElse(null);
        }

        public void refund(String paymentId) {
            PgPayment p = findByPaymentId(paymentId);
            if (p == null) throw new IllegalArgumentException("존재하지 않는 결제 건: " + paymentId);
            if (p.status() == PgStatus.REFUNDED) throw new RuntimeException("이미 환불됨: " + paymentId);
            store.replaceAll((k, v) -> v.paymentId().equals(paymentId)
                    ? new PgPayment(v.paymentId(), v.amount(), PgStatus.REFUNDED) : v);
        }
    }

    public static class Q2PaymentActivitySolution implements PaymentActivity {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(Q2PaymentActivitySolution.class);
        private final FakePgClient pg = new FakePgClient();

        @Override
        public String charge(String orderId, long amount) {
            // orderId 를 멱등 키로. UUID.randomUUID() 는 재시도마다 달라져서 쓸 수 없다.
            // 실무에서는 Activity.getExecutionContext().getInfo().getWorkflowId() 를 쓴다.
            return pg.approve(orderId, amount).paymentId();
        }

        @Override
        public void refund(String paymentId) {
            FakePgClient.PgPayment found = pg.findByPaymentId(paymentId);

            // 규칙 ①: 대상이 없으면 조용히 성공.
            // "되돌릴 게 없다" 는 실패가 아니라 이미 목표 상태에 도달한 것이다.
            if (found == null) {
                log.info("[refund] paymentId={} 승인 건 없음 — 보상 불필요", paymentId);
                return;
            }

            // 규칙 ②: 이미 처리됐으면 조용히 성공.
            // 최종 상태가 원하는 것과 같으므로 성공이다.
            if (found.status() == FakePgClient.PgStatus.REFUNDED) {
                log.info("[refund] paymentId={} 이미 환불됨 — 스킵", paymentId);
                return;
            }

            pg.refund(paymentId);
            log.info("[refund] paymentId={} 환불 완료", paymentId);
        }
    }

    /* ==================================================================
     * 정답 3 — 정방향 / 보상에 서로 다른 ActivityOptions
     * ==================================================================
     *
     * 핵심: 액티비티 인터페이스를 두 벌 만들 필요가 없습니다.
     *       Workflow.newActivityStub 은 "인터페이스 + 옵션" 조합으로 스텁을 만들므로,
     *       같은 인터페이스에 다른 옵션을 주면 별개의 스텁이 나옵니다.
     *
     *   private final PaymentActivity payment  = newActivityStub(PaymentActivity.class, FORWARD);
     *   private final PaymentActivity paymentC = newActivityStub(PaymentActivity.class, COMPENSATION);
     *
     * 왜 정책을 다르게 하나
     *  - 정방향은 "빨리 포기" 해야 합니다. 3번 시도하고 실패해야 보상 단계로 넘어가
     *    고객 돈을 빨리 돌려줄 수 있습니다. 여기서 무한 재시도를 걸면 고객은 하염없이
     *    "결제 처리 중" 화면을 보게 되고 재고도 계속 묶여 있습니다.
     *  - 보상은 "절대 포기 금지" 입니다. 환불 API 가 3일 죽어 있어도 살아나는 순간
     *    자동으로 성공해야 합니다. 개발자가 할 일이 없어야 합니다.
     *
     * 검증:
     *   temporal workflow show -w order-2003 --output json \
     *     | jq '[.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_SCHEDULED")
     *            | {t: .activityTaskScheduledEventAttributes.activityType.name,
     *               a: .activityTaskScheduledEventAttributes.retryPolicy.maximumAttempts}]'
     *
     * 기대 출력:
     *   [ {"t":"Charge","a":3}, {"t":"Reserve","a":3}, {"t":"RequestShipment","a":3},
     *     {"t":"Release","a":0}, {"t":"Refund","a":0} ]
     */
    public static class Q3OptionsSolution {
        static final ActivityOptions FORWARD = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(3)
                        .build())
                .build();

        static final ActivityOptions COMPENSATION = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofDays(7))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(10))
                        .setMaximumAttempts(0)   // 0 = 무제한
                        .build())
                .build();
    }

    /* ==================================================================
     * 정답 4 — throw 누락의 결과
     * ==================================================================
     *
     * (1) Status 는?
     *     temporal workflow describe -w order-2004
     *       Status            COMPLETED
     *       Result            ["2004 COMPLETED"]
     *
     *     결제는 환불됐고 배송은 실패했는데 워크플로우는 성공입니다.
     *
     * (2) 부모 워크플로우는 어떻게 판단하나?
     *     자식 워크플로우로 호출했다면 ChildWorkflowExecutionCompleted 이벤트를 받고
     *     반환값 "2004 COMPLETED" 를 그대로 씁니다. 부모는 다음 단계로 진행합니다.
     *     API 로 호출했다면 클라이언트가 200 OK 와 성공 응답을 받습니다.
     *     → 주문 완료 메일이 나가고, 고객은 "주문 완료" 화면을 봅니다.
     *       배송은 없고 돈은 환불됐는데도.
     *
     * (3) 왜 대시보드에 안 잡히나?
     *     히스토리의 마지막 이벤트가 WorkflowExecutionFailed 가 아니라
     *     WorkflowExecutionCompleted 이기 때문입니다.
     *     - workflow_failed 메트릭이 증가하지 않습니다
     *     - workflow_endtoend_latency 는 정상 범위로 기록됩니다
     *     - 실패율 대시보드는 0% 로 아름답습니다
     *     - temporal workflow list --query 'ExecutionStatus="Failed"' 에도 안 나옵니다
     *     - 재처리 배치의 대상에서도 제외됩니다
     *
     *     즉 시스템 어디에도 이상 신호가 없습니다. 고객 CS 로만 발견됩니다.
     *
     * 방어책 두 가지
     * ─────────────
     *  ① 반환문을 try 블록 안으로 넣으세요.
     *     아래 Q4Solution 처럼 return 이 try 안에 있으면, catch 에서 throw 를 빼먹었을 때
     *     "return 문이 없다" 는 컴파일 에러가 납니다. 실수 자체가 불가능해집니다.
     *  ② Step 11 의 리플레이 테스트에서 실패 시나리오의 최종 상태를 반드시 단언하세요.
     *     assertThrows(WorkflowFailedException.class, () -> wf.processOrder(req));
     */
    @WorkflowInterface
    public interface Q4Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q4Solution implements Q4Workflow {
        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            Saga saga = new Saga(new Saga.Options.Builder().build());
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                saga.addCompensation(payment::refund, paymentId);
                shipping.requestShipment(req.orderId(), req.address());
                return req.orderId() + " COMPLETED";   // ← return 이 try 안에 있다
            } catch (Exception e) {
                saga.compensate();
                throw e;   // ← 이걸 지우면 "return 문 없음" 컴파일 에러
            }
        }
    }

    /* ==================================================================
     * 정답 5 — 병렬 보상
     * ==================================================================
     *
     * 판정 기준: ActivityTaskScheduled 3개 사이에 WorkflowTaskScheduled 가
     *            "끼어 있지 않다" 는 것.
     *
     * 직렬 모드 (setParallelCompensation(false), 기본값)
     * ────────────────────────────────────────────────
     *   23  ActivityTaskScheduled  [cancelShipment]
     *   24  ActivityTaskStarted
     *   25  ActivityTaskCompleted
     *   26  WorkflowTaskScheduled     ← 워크플로우가 깨어나 다음 명령을 낸다
     *   27  WorkflowTaskStarted
     *   28  WorkflowTaskCompleted
     *   29  ActivityTaskScheduled  [release]
     *   ...
     *   보상 3개에 Workflow Task 왕복이 3번. 총 2.1초.
     *
     * 병렬 모드 (setParallelCompensation(true))
     * ────────────────────────────────────────
     *   22  WorkflowTaskCompleted
     *   23  ActivityTaskScheduled  [cancelShipment]
     *   24  ActivityTaskScheduled  [release]      ← 같은 Workflow Task 에서
     *   25  ActivityTaskScheduled  [refund]       ← 명령 3개를 한 번에 발행
     *   26  ActivityTaskStarted
     *   27  ActivityTaskStarted
     *   28  ActivityTaskStarted
     *   ...
     *   Workflow Task 왕복이 1번. 총 0.8초.
     *
     * ⚠️ 그런데 역순 보장이 사라집니다.
     *    재고 해제가 배송 취소보다 먼저 완료될 수 있습니다.
     *    "배송 취소가 실패했는데 재고는 이미 풀린" 상태가 가능해집니다.
     *    보상들이 서로 완전히 독립일 때만 쓰세요.
     *
     * ⚠️ 추가 함정: 병렬 모드는 setContinueWithError 를 무시합니다.
     *    모든 보상이 이미 시작됐으므로 "실패 시 나머지 중단" 이라는 개념이 성립하지 않습니다.
     *    직렬 전제로 setContinueWithError(false) 를 걸어 뒀다가 병렬로 바꾸면
     *    동작이 조용히 달라집니다.
     */
    @WorkflowInterface
    public interface Q5Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q5Solution implements Q5Workflow {
        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10)).build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            Saga.Options sagaOptions = new Saga.Options.Builder()
                    .setParallelCompensation(true)
                    .build();
            Saga saga = new Saga(sagaOptions);
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                saga.addCompensation(payment::refund, paymentId);
                String rid = inventory.reserve(req.orderId(), req.sku(), req.qty());
                saga.addCompensation(inventory::release, rid);
                String sid = shipping.requestShipment(req.orderId(), req.address());
                saga.addCompensation(shipping::cancelShipment, sid);
                throw ApplicationFailure.newNonRetryableFailure(
                        "보상을 관찰하기 위한 강제 실패", "ObserveCompensation");
            } catch (Exception e) {
                saga.compensate();
                throw e;
            }
        }
    }

    /* ==================================================================
     * 정답 6 — CompensationException 처리 + 원인 보존
     * ==================================================================
     *
     * 이중 try 구조가 핵심입니다.
     *
     *   catch (Exception e) {              ← 원래 실패
     *       try { saga.compensate(); }
     *       catch (CompensationException ce) { 알림만 보낸다 }
     *       throw e;                       ← 원래 실패를 던진다 (ce 가 아니라!)
     *   }
     *
     * 순서를 뒤집어 ce 를 던지면 어떻게 되나
     * ─────────────────────────────────────
     * 히스토리의 마지막 이벤트가 이렇게 바뀝니다.
     *
     *   WorkflowExecutionFailed
     *     failure.message: "Compensation failed"
     *     failure.cause.message: "Connection refused: pg-api:443"
     *
     * "왜 이 주문이 실패했는가?" 라는 질문에 "환불 서버 연결 실패" 라고 답하게 됩니다.
     * 진짜 원인인 "배송 불가 지역" 은 어디에도 없습니다.
     * 원인 분석이 불가능해지고, 같은 주소로 들어오는 주문이 계속 실패하는데도
     * 개발자는 PG 서버만 들여다보게 됩니다.
     *
     * 반면 e 를 던지면:
     *   WorkflowExecutionFailed
     *     failure.message: "Activity task failed"
     *     failure.activityFailureInfo.activityType.name: "RequestShipment"
     *     failure.cause.message: "배송 불가 지역: 제주특별자치도 서귀포시 도서산간"
     *
     * 보상 실패는 별도의 알림 채널로 갑니다. 두 정보가 섞이지 않습니다.
     *
     * 그리고 opsAlert 스텁에는 반드시 무한 재시도를 걸어야 합니다.
     * "보상이 실패했다는 알림" 자체가 실패하면 아무도 모르게 됩니다.
     */
    @WorkflowInterface
    public interface Q6Workflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q6Solution implements Q6Workflow {

        private static final Logger log = Workflow.getLogger(Q6Solution.class);

        private final ActivityOptions forward = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build();
        // 알림은 절대 실패하면 안 되므로 무한 재시도
        private final ActivityOptions mustSucceed = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofDays(7))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(0).build())
                .build();

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, forward);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, forward);
        private final OpsAlertActivity opsAlert =
                Workflow.newActivityStub(OpsAlertActivity.class, mustSucceed);

        @Override
        public String processOrder(OrderRequest req) {
            Saga saga = new Saga(new Saga.Options.Builder()
                    .setContinueWithError(true)   // 하나 실패해도 나머지 보상은 계속
                    .build());
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                saga.addCompensation(payment::refund, paymentId);
                shipping.requestShipment(req.orderId(), req.address());
                return req.orderId() + " COMPLETED";

            } catch (Exception e) {
                try {
                    saga.compensate();
                } catch (Saga.CompensationException ce) {
                    log.error("[{}] 보상 실패 — 수동 개입 필요", req.orderId(), ce);
                    opsAlert.raiseManualIntervention(
                            req.orderId(),
                            "SAGA_COMPENSATION_FAILED",
                            ce.getCause() == null ? ce.toString() : ce.getCause().toString());
                    // 여기서 ce 를 던지면 안 된다. 원인이 뒤바뀐다.
                }
                throw e;   // 원래 실패 원인을 워크플로우 결과로 전파
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("Solution.java — 정답 6개. "
                + "Practice.java 의 main 을 참고해 Worker 에 등록하고 실행하세요.");
    }
}
