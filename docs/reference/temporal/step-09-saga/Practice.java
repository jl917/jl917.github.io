package com.example.order;

/*
 * Step 09 — Saga 보상 트랜잭션 / Practice.java
 *
 * 실행 방법:
 *   1) Temporal Server 기동:  temporal server start-dev --db-filename temporal.db --ui-port 8233
 *   2) Worker + 클라이언트:    ./gradlew run -PmainClass=com.example.order.Practice
 *   3) 히스토리 확인:          temporal workflow show -w order-1004
 *
 * 환경: Java 21 / temporal-sdk 1.22.3 / Temporal Server 1.22.4 / CLI 0.11.0
 *
 * 이 파일은 본문 9-1 ~ 9-8 의 모든 코드를 절 번호 주석과 함께 담았습니다.
 * 단일 파일에 static nested class 로 워크플로우·액티비티·구현체를 모두 넣어 그대로 컴파일됩니다.
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Practice {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // ------------------------------------------------------------------
    // [9-0] 공통 DTO — Java 21 record
    // ------------------------------------------------------------------
    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address) {
    }

    // ------------------------------------------------------------------
    // [9-0] 액티비티 인터페이스 — 정방향과 보상이 짝을 이룬다
    // ------------------------------------------------------------------
    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod
        String charge(String orderId, long amount);

        @ActivityMethod
        void refund(String paymentId);   // 보상
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod
        String reserve(String orderId, String sku, int qty);

        @ActivityMethod
        void release(String reservationId);   // 보상
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod
        String requestShipment(String orderId, String address);

        @ActivityMethod
        void cancelShipment(String shipmentId);   // 보상
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod
        void notifyCustomer(String orderId, String message);
    }

    @ActivityInterface
    public interface OpsAlertActivity {
        @ActivityMethod
        void raiseManualIntervention(String orderId, String code, String detail);
    }

    // ------------------------------------------------------------------
    // 워크플로우 인터페이스
    // ------------------------------------------------------------------
    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    @WorkflowInterface
    public interface ManualSagaWorkflow {
        @WorkflowMethod
        String processOrder(OrderRequest req);
    }

    // ------------------------------------------------------------------
    // [9-6] 정방향과 보상은 재시도 정책이 달라야 한다
    //  - 정방향: 빨리 포기해야 보상 단계로 넘어간다 (maximumAttempts = 3)
    //  - 보상  : 절대 포기하면 안 된다            (maximumAttempts = 0 = 무제한)
    // ------------------------------------------------------------------
    static final ActivityOptions FORWARD = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(500))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(3)
                    // 배송 불가 지역은 재시도해도 결과가 같으므로 즉시 실패시킨다
                    .setDoNotRetry("ShippingUnavailableException")
                    .build())
            .build();

    static final ActivityOptions COMPENSATION = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofDays(7))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(0)   // 0 = 무제한. 환불 API 가 살아나면 자동 성공
                    .build())
            .build();

    // ==================================================================
    // [9-3] Temporal 의 Saga 클래스를 쓴 표준 구현
    // ==================================================================
    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        // 같은 인터페이스로 스텁을 두 벌 만든다. 옵션만 다르다.
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, FORWARD);
        private final PaymentActivity paymentC =
                Workflow.newActivityStub(PaymentActivity.class, COMPENSATION);

        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, FORWARD);
        private final InventoryActivity inventoryC =
                Workflow.newActivityStub(InventoryActivity.class, COMPENSATION);

        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, FORWARD);
        private final ShippingActivity shippingC =
                Workflow.newActivityStub(ShippingActivity.class, COMPENSATION);

        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, FORWARD);
        private final OpsAlertActivity opsAlert =
                Workflow.newActivityStub(OpsAlertActivity.class, COMPENSATION);

        @Override
        public String processOrder(OrderRequest req) {
            Saga.Options sagaOptions = new Saga.Options.Builder()
                    .setParallelCompensation(false)   // [9-5] 역순 직렬 보상 (기본값)
                    .setContinueWithError(false)      // [9-6] 보상 실패 시 즉시 중단 (기본값)
                    .build();
            Saga saga = new Saga(sagaOptions);

            try {
                // ① 결제
                log.info("[{}] 결제 시작 amount={}", req.orderId(), req.amount());
                String paymentId = payment.charge(req.orderId(), req.amount());
                // [9-4] 반환값(paymentId)이 필요하므로 호출 "후"에 등록한다.
                //       호출 자체가 타임아웃돼도 안전하려면 refund 가 멱등해야 한다.
                saga.addCompensation(paymentC::refund, paymentId);

                // ② 재고
                String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
                saga.addCompensation(inventoryC::release, reservationId);

                // ③ 배송
                String shipmentId = shipping.requestShipment(req.orderId(), req.address());
                saga.addCompensation(shippingC::cancelShipment, shipmentId);

                notification.notifyCustomer(req.orderId(), "주문이 완료되었습니다");
                return req.orderId() + " COMPLETED";

            } catch (Exception e) {
                log.error("[{}] 실패 — 보상 시작", req.orderId(), e);
                try {
                    // [9-5] 등록의 역순으로 실행: cancelShipment → release → refund
                    saga.compensate();
                } catch (Saga.CompensationException ce) {
                    // [9-6] ② 전략: 보상이 실패하면 사람이 개입하도록 알린다
                    log.error("[{}] 보상 실패 — 수동 개입 필요", req.orderId(), ce);
                    opsAlert.raiseManualIntervention(
                            req.orderId(),
                            "SAGA_COMPENSATION_FAILED",
                            ce.getCause() == null ? ce.toString() : ce.getCause().toString());
                }
                // [9-9 함정 3] 이 throw 가 없으면 워크플로우가 COMPLETED 로 끝난다.
                //              원래 실패 원인(e)을 그대로 전파해야 히스토리의 cause 가 보존된다.
                throw e;
            }
        }
    }

    // ==================================================================
    // [9-8] Saga 클래스 없이 List<Runnable> 로 직접 구현하면
    // ==================================================================
    public static class ManualSagaWorkflowImpl implements ManualSagaWorkflow {

        private static final Logger log = Workflow.getLogger(ManualSagaWorkflowImpl.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, FORWARD);
        private final PaymentActivity paymentC =
                Workflow.newActivityStub(PaymentActivity.class, COMPENSATION);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, FORWARD);
        private final InventoryActivity inventoryC =
                Workflow.newActivityStub(InventoryActivity.class, COMPENSATION);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, FORWARD);
        private final ShippingActivity shippingC =
                Workflow.newActivityStub(ShippingActivity.class, COMPENSATION);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, FORWARD);

        @Override
        public String processOrder(OrderRequest req) {
            List<Runnable> compensations = new ArrayList<>();
            try {
                String paymentId = payment.charge(req.orderId(), req.amount());
                compensations.add(() -> paymentC.refund(paymentId));

                String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
                compensations.add(() -> inventoryC.release(reservationId));

                String shipmentId = shipping.requestShipment(req.orderId(), req.address());
                compensations.add(() -> shippingC.cancelShipment(shipmentId));

                notification.notifyCustomer(req.orderId(), "주문이 완료되었습니다");
                return req.orderId() + " COMPLETED";

            } catch (Exception e) {
                // Saga 클래스가 대신해 주던 일을 손으로 한다:
                //  ① 역순 뒤집기  ② 하나 실패해도 계속  ③ suppressed 로 수집
                Collections.reverse(compensations);
                RuntimeException collected = null;
                for (Runnable c : compensations) {
                    try {
                        c.run();
                    } catch (RuntimeException ce) {
                        log.error("[{}] 보상 하나 실패 — 나머지는 계속", req.orderId(), ce);
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
                throw e;
            }
        }
    }

    // ==================================================================
    // 액티비티 구현 — 9-4 의 멱등성이 핵심
    // ==================================================================

    /** [9-4] 외부 PG 를 흉내 낸 가짜 클라이언트. 멱등 키로 승인 건을 관리한다. */
    public static class FakePgClient {
        public enum PgStatus { APPROVED, REFUNDED }

        public record PgPayment(String paymentId, long amount, PgStatus status) {
        }

        private final Map<String, PgPayment> byKey = new ConcurrentHashMap<>();

        /** 같은 멱등 키로 두 번 호출해도 같은 paymentId 를 돌려준다 (at-least-once 방어) */
        public PgPayment approve(String idempotencyKey, long amount) {
            return byKey.computeIfAbsent(idempotencyKey,
                    k -> new PgPayment("pay_" + Integer.toHexString(k.hashCode() & 0xffff),
                            amount, PgStatus.APPROVED));
        }

        public PgPayment findByPaymentId(String paymentId) {
            return byKey.values().stream()
                    .filter(p -> p.paymentId().equals(paymentId))
                    .findFirst()
                    .orElse(null);
        }

        public void refund(String paymentId) {
            byKey.replaceAll((k, v) -> v.paymentId().equals(paymentId)
                    ? new PgPayment(v.paymentId(), v.amount(), PgStatus.REFUNDED)
                    : v);
        }
    }

    public static class PaymentActivityImpl implements PaymentActivity {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(PaymentActivityImpl.class);
        private final FakePgClient pg = new FakePgClient();

        @Override
        public String charge(String orderId, long amount) {
            // [9-4] 멱등 키는 orderId 기반. UUID.randomUUID() 는 재시도마다 달라지므로 절대 금지.
            FakePgClient.PgPayment p = pg.approve(orderId, amount);
            log.info("[charge] orderId={} amount={} -> {}", orderId, amount, p.paymentId());
            return p.paymentId();
        }

        @Override
        public void refund(String paymentId) {
            // [9-4] 멱등 보상의 정본 — 조기 반환 두 개가 핵심이다.
            FakePgClient.PgPayment found = pg.findByPaymentId(paymentId);

            if (found == null) {
                // 규칙 ①: 대상이 없으면 조용히 성공한다.
                // "결제가 아예 안 됐다" = 이미 원하는 상태(돈이 안 빠진 상태)이므로 성공.
                // 여기서 예외를 던지면 보상 단계가 통째로 막힌다.
                log.info("[refund] paymentId={} 승인 건 없음 — 보상 불필요", paymentId);
                return;
            }
            if (found.status() == FakePgClient.PgStatus.REFUNDED) {
                // 규칙 ②: 이미 처리됐으면 조용히 성공한다.
                // 보상은 무한 재시도이므로 두 번 이상 불릴 수 있다. 여기서 안 막으면 이중 환불.
                log.info("[refund] paymentId={} 이미 환불됨 — 스킵", paymentId);
                return;
            }
            pg.refund(paymentId);
            log.info("[refund] paymentId={} 환불 완료", paymentId);
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(InventoryActivityImpl.class);
        private final Map<String, Boolean> reservations = new ConcurrentHashMap<>();

        @Override
        public String reserve(String orderId, String sku, int qty) {
            String rid = "rsv_" + Integer.toHexString((orderId + sku).hashCode() & 0xfff);
            reservations.put(rid, true);   // 같은 orderId+sku 면 같은 id — 멱등
            log.info("[reserve] sku={} qty={} -> {}", sku, qty, rid);
            return rid;
        }

        @Override
        public void release(String reservationId) {
            Boolean held = reservations.get(reservationId);
            if (held == null || !held) {
                log.info("[release] reservationId={} 홀드 없음 — 보상 불필요", reservationId);
                return;   // 멱등
            }
            reservations.put(reservationId, false);
            log.info("[release] reservationId={} 해제 완료", reservationId);
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(ShippingActivityImpl.class);
        private final Map<String, Boolean> shipments = new HashMap<>();

        @Override
        public String requestShipment(String orderId, String address) {
            // [9-7] 도서산간이면 재시도 불가 예외 — order-1004 시나리오를 만든다
            if (address != null && address.contains("도서산간")) {
                log.error("[requestShipment] 배송 불가 지역: {}", address);
                throw ApplicationFailure.newNonRetryableFailure(
                        "배송 불가 지역: " + address, "ShippingUnavailableException");
            }
            String sid = "shp_" + Integer.toHexString(orderId.hashCode() & 0xfff);
            shipments.put(sid, true);
            log.info("[requestShipment] orderId={} -> {}", orderId, sid);
            return sid;
        }

        @Override
        public void cancelShipment(String shipmentId) {
            if (!Boolean.TRUE.equals(shipments.get(shipmentId))) {
                log.info("[cancelShipment] shipmentId={} 배송 건 없음 — 보상 불필요", shipmentId);
                return;   // 멱등
            }
            shipments.put(shipmentId, false);
            log.info("[cancelShipment] shipmentId={} 취소 완료", shipmentId);
        }
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(NotificationActivityImpl.class);

        @Override
        public void notifyCustomer(String orderId, String message) {
            log.info("[notifyCustomer] orderId={} message={}", orderId, message);
        }
    }

    public static class OpsAlertActivityImpl implements OpsAlertActivity {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(OpsAlertActivityImpl.class);

        @Override
        public void raiseManualIntervention(String orderId, String code, String detail) {
            // 실무에서는 PagerDuty / Slack / 운영 DB 의 manual_intervention 테이블로
            log.error("[MANUAL] orderId={} code={} detail={}", orderId, code, detail);
        }
    }

    // ==================================================================
    // main — Worker 기동 + order-1004 실행
    // ==================================================================
    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                OrderWorkflowImpl.class, ManualSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new InventoryActivityImpl(),
                new ShippingActivityImpl(),
                new NotificationActivityImpl(),
                new OpsAlertActivityImpl());
        factory.start();

        // [9-5] 배송 단계에서 실패하는 주문 — 보상 2개가 역순으로 실행된다
        OrderRequest req = new OrderRequest(
                "1004", "C-77", "SKU-77", 1, 39000,
                "제주특별자치도 서귀포시 도서산간");

        OrderWorkflow wf = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("order-" + req.orderId())
                        .build());

        try {
            String result = wf.processOrder(req);
            System.out.println("결과: " + result);
        } catch (WorkflowFailedException e) {
            // 실패로 끝나는 것이 정상 동작이다.
            // 보상이 모두 끝난 뒤 원래 실패 원인이 여기로 전파된다.
            System.out.println("예상된 실패: " + e.getCause().getMessage());
            System.out.println("이제 확인하세요: temporal workflow show -w order-" + req.orderId());
        }

        System.exit(0);
    }
}
