package com.example.order;

/*
 * Step 09 — Saga 보상 트랜잭션 / Exercise.java
 *
 * 6문제입니다. `// TODO: 여기에 작성` 자리를 채우세요.
 * 정답은 Solution.java 에 있습니다. 반드시 먼저 풀어 보세요.
 *
 * 실행: ./gradlew run -PmainClass=com.example.order.Exercise
 *
 * ⚠️ 주의 — 문제마다 워크플로우 ID 가 다릅니다(order-2001 ~ order-2006).
 *    같은 ID 로 재실행하면 WorkflowExecutionAlreadyStarted 가 납니다.
 *    다시 돌리려면: temporal workflow terminate -w order-2001 --reason retry
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Exercise {

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

    // ==================================================================
    // 문제 1 — Saga 클래스 없이 List<Runnable> 로 보상을 구현하세요.
    //
    //  요구사항:
    //   (a) 성공한 단계만 보상 목록에 쌓는다
    //   (b) 실패하면 역순으로 실행한다
    //   (c) 보상 하나가 실패해도 나머지는 계속 실행한다
    //   (d) 실패한 보상들은 suppressed 예외로 모아 원래 예외에 붙인다
    //   (e) 마지막에 원래 예외를 다시 던진다
    //
    //  실행 후 확인: temporal workflow show -w order-2001
    //   → ActivityTaskScheduled 순서가 Charge, Reserve, RequestShipment,
    //     Release, Refund 여야 합니다.
    // ==================================================================
    @WorkflowInterface
    public interface Q1ManualSagaWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q1Impl implements Q1ManualSagaWorkflow {

        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build();

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
                // TODO: 여기에 작성 — 결제 보상 등록

                String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
                // TODO: 여기에 작성 — 재고 보상 등록

                String shipmentId = shipping.requestShipment(req.orderId(), req.address());
                // TODO: 여기에 작성 — 배송 보상 등록

                return req.orderId() + " COMPLETED";

            } catch (Exception e) {
                // TODO: 여기에 작성 — (b)(c)(d)(e) 를 모두 만족하는 보상 루프
                throw e;
            }
        }
    }

    // ==================================================================
    // 문제 2 — refund / release 를 멱등하게 고치세요.
    //
    //  아래 FakePgClient 는 일부러 까다롭게 만들어 두었습니다:
    //   - findByPaymentId 는 등록되지 않은 키에 null 을 반환합니다
    //   - refund 를 이미 환불된 건에 호출하면 DuplicateRefundException 을 던집니다
    //  즉 멱등하게 고치지 않으면 반드시 터집니다.
    //
    //  요구사항: "대상 없음" 과 "이미 처리됨" 을 모두 정상 종료(return)로 처리
    // ==================================================================
    public static class DuplicateRefundException extends RuntimeException {
        public DuplicateRefundException(String m) { super(m); }
    }

    public static class FakePgClient {
        public enum PgStatus { APPROVED, REFUNDED }
        public record PgPayment(String paymentId, long amount, PgStatus status) {}

        private final Map<String, PgPayment> store = new ConcurrentHashMap<>();

        public PgPayment approve(String idempotencyKey, long amount) {
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
            if (p == null) {
                throw new IllegalArgumentException("존재하지 않는 결제 건: " + paymentId);
            }
            if (p.status() == PgStatus.REFUNDED) {
                throw new DuplicateRefundException("이미 환불됨: " + paymentId);
            }
            store.replaceAll((k, v) -> v.paymentId().equals(paymentId)
                    ? new PgPayment(v.paymentId(), v.amount(), PgStatus.REFUNDED) : v);
        }
    }

    public static class Q2PaymentActivityImpl implements PaymentActivity {
        private final FakePgClient pg = new FakePgClient();

        @Override
        public String charge(String orderId, long amount) {
            return pg.approve(orderId, amount).paymentId();
        }

        @Override
        public void refund(String paymentId) {
            // TODO: 여기에 작성
            //  힌트 — pg.refund() 를 바로 부르지 말고, 먼저 findByPaymentId 로 상태를 확인하세요.
            //         null 이면? REFUNDED 면? 각각 어떻게 해야 "보상이 성공"인가요?
        }
    }

    // ==================================================================
    // 문제 3 — 정방향과 보상에 서로 다른 ActivityOptions 를 적용하세요.
    //
    //  요구사항:
    //   - 정방향: StartToCloseTimeout 10초, maximumAttempts 3
    //   - 보상  : StartToCloseTimeout 30초, ScheduleToCloseTimeout 7일,
    //             maximumAttempts 0(무제한), maximumInterval 10분
    //
    //  실행 후 확인:
    //   temporal workflow show -w order-2003 --output json \
    //     | jq '[.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_SCHEDULED")
    //            | {t: .activityTaskScheduledEventAttributes.activityType.name,
    //               a: .activityTaskScheduledEventAttributes.retryPolicy.maximumAttempts}]'
    //   → Charge/Reserve 는 3, Release/Refund 는 0 이어야 합니다.
    // ==================================================================
    public static class Q3Options {
        // TODO: 여기에 작성 — FORWARD 상수
        static final ActivityOptions FORWARD = null;

        // TODO: 여기에 작성 — COMPENSATION 상수
        static final ActivityOptions COMPENSATION = null;
    }

    // ==================================================================
    // 문제 4 — 일부러 버그를 만들어 그 결과를 확인하세요.
    //
    //  아래 워크플로우는 saga.compensate() 뒤에 throw 가 없습니다.
    //  이대로 order-2004 를 배송 실패 주소로 실행한 뒤:
    //    temporal workflow describe -w order-2004
    //  Status 가 무엇으로 나오는지 확인하고, 아래 주석에 답을 쓰세요.
    // ==================================================================
    @WorkflowInterface
    public interface Q4BuggyWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q4Impl implements Q4BuggyWorkflow {
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
            } catch (Exception e) {
                saga.compensate();
                // throw e;   ← 일부러 뺐습니다
            }
            return req.orderId() + " COMPLETED";
        }
    }

    // TODO: 여기에 작성 — 아래 세 질문에 답하는 주석을 쓰세요.
    //  (1) temporal workflow describe -w order-2004 의 Status 는?
    //  (2) 이 워크플로우를 자식으로 호출한 부모 워크플로우는 어떻게 판단하나요?
    //  (3) 왜 이 버그는 실패율 대시보드에 잡히지 않나요?
    //
    //  답:
    //

    // ==================================================================
    // 문제 5 — setParallelCompensation(true) 로 바꾸고 히스토리를 관찰하세요.
    //
    //  요구사항: Q5Impl 의 Saga.Options 를 병렬 보상으로 설정
    //
    //  실행 후 확인: temporal workflow show -w order-2005
    //   → 직렬 모드에서는 ActivityTaskScheduled 사이에
    //     WorkflowTaskScheduled/Started/Completed 3개가 끼어 있습니다.
    //     병렬 모드에서는 무엇이 달라지나요?
    // ==================================================================
    @WorkflowInterface
    public interface Q5ParallelWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q5Impl implements Q5ParallelWorkflow {
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
            // TODO: 여기에 작성 — 병렬 보상 옵션
            Saga.Options sagaOptions = null;
            Saga saga = new Saga(sagaOptions);

            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                saga.addCompensation(payment::refund, paymentId);
                String rid = inventory.reserve(req.orderId(), req.sku(), req.qty());
                saga.addCompensation(inventory::release, rid);
                String sid = shipping.requestShipment(req.orderId(), req.address());
                saga.addCompensation(shipping::cancelShipment, sid);
                throw new RuntimeException("보상을 관찰하기 위한 강제 실패");
            } catch (Exception e) {
                saga.compensate();
                throw e;
            }
        }
    }

    // ==================================================================
    // 문제 6 — Saga.CompensationException 을 잡아 운영 알림을 보내되,
    //          워크플로우의 최종 실패 원인은 "원래 실패"로 유지하세요.
    //
    //  요구사항:
    //   - saga.compensate() 를 try 로 감싸 CompensationException 을 잡는다
    //   - opsAlert.raiseManualIntervention(orderId, "SAGA_COMPENSATION_FAILED", 원인) 호출
    //   - 그러고 나서 원래 예외 e 를 던진다 (ce 를 던지면 안 됨)
    //
    //  확인: temporal workflow show -w order-2006 --output json
    //   | jq '.events[-1].workflowExecutionFailedEventAttributes.failure.cause.message'
    //   → 보상 실패가 아니라 원래 실패 메시지가 나와야 합니다.
    // ==================================================================
    @WorkflowInterface
    public interface Q6AlertWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    public static class Q6Impl implements Q6AlertWorkflow {
        private final ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build();
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, opts);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, opts);
        private final OpsAlertActivity opsAlert =
                Workflow.newActivityStub(OpsAlertActivity.class, opts);

        @Override
        public String processOrder(OrderRequest req) {
            Saga saga = new Saga(new Saga.Options.Builder().build());
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                saga.addCompensation(payment::refund, paymentId);
                shipping.requestShipment(req.orderId(), req.address());
                return req.orderId() + " COMPLETED";
            } catch (Exception e) {
                // TODO: 여기에 작성
                throw e;
            }
        }
    }

    // ==================================================================
    // 검증 헬퍼 — 히스토리에서 ActivityTaskScheduled 의 activityType 만 뽑는다.
    // 기대 순서와 assertEquals 로 비교하세요.
    //
    // 사용 예:
    //   List<String> order = verifyCompensationOrder(client, "order-2001");
    //   assert order.equals(List.of("Charge","Reserve","RequestShipment","Release","Refund"));
    // ==================================================================
    public static List<String> verifyCompensationOrder(
            io.temporal.client.WorkflowClient client, String workflowId) {
        List<String> types = new ArrayList<>();
        client.fetchHistory(workflowId).getEvents().forEach(e -> {
            if (e.hasActivityTaskScheduledEventAttributes()) {
                types.add(e.getActivityTaskScheduledEventAttributes()
                        .getActivityType().getName());
            }
        });
        return types;
    }

    public static void main(String[] args) {
        System.out.println("각 문제의 TODO 를 채운 뒤, Practice.java 의 main 을 참고해 "
                + "Worker 를 띄우고 order-2001 ~ order-2006 을 실행하세요.");
    }
}
