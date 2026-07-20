package com.example.order;

// ============================================================================
// Step 13 — 최종 프로젝트: 주문 처리 Saga
//
// 본문 13-0 ~ 13-11 의 모든 코드를 한 파일에 모은 실습 파일입니다.
// 실제 프로젝트에서는 아래 nested type 들을 각각 별도 파일로 분리하십시오.
//
// 실행:
//   Worker  : ./gradlew run -PmainClass=com.example.order.Practice
//   주문 개시: ./gradlew run -PmainClass=com.example.order.Practice --args="start order-2001"
//   시나리오 : --args="start order-2002"  (재고 부족 → 보상)
//              --args="start order-2003"  (배송 중 취소 시그널)
//              --args="start order-2004"  (결제 확인 시그널 타임아웃)
//
// Temporal Java SDK 1.22.3 / Java 21 / Temporal Server 1.22.4
// ============================================================================

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Saga;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Practice {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // ------------------------------------------------------------------
    // [13-2] 도메인 타입
    // ------------------------------------------------------------------

    public record OrderRequest(
            String orderId,
            String customerId,
            String sku,
            int qty,
            long amount,
            String address) {
    }

    public enum OrderStatus {
        RECEIVED,      // 워크플로우 시작 직후
        PAID,          // 결제 완료
        RESERVED,      // 재고 확보 완료
        SHIPPED,       // 배송 요청 완료
        COMPLETED,     // 정상 종료
        COMPENSATING,  // 보상 진행 중
        CANCELLED,     // 취소로 종료 (보상 완료)
        FAILED         // 실패로 종료 (보상 완료)
    }

    // Query 로 반환하는 상태 스냅샷. 워크플로우 내부 가변 필드를 그대로 노출하지 않고
    // 불변 record 로 복사해서 넘깁니다. Query 는 워크플로우 상태를 변경하면 안 되기 때문입니다.
    public record OrderState(
            String orderId,
            OrderStatus status,
            String paymentId,
            String reservationId,
            String shipmentId,
            String address,
            String failureReason) {
    }

    // ------------------------------------------------------------------
    // [13-3] Activity 인터페이스 4종
    //        모든 메서드 첫 인자가 idempotencyKey 입니다.
    // ------------------------------------------------------------------

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod
        String charge(String idempotencyKey, String orderId, long amount);

        @ActivityMethod
        void refund(String idempotencyKey, String paymentId);
    }

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod
        String reserve(String idempotencyKey, String orderId, String sku, int qty);

        @ActivityMethod
        void release(String idempotencyKey, String reservationId);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod
        String requestShipment(String idempotencyKey, String orderId, String address);

        @ActivityMethod
        void cancelShipment(String idempotencyKey, String shipmentId);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod
        void notifyCustomer(String idempotencyKey, String orderId, String message);
    }

    // ------------------------------------------------------------------
    // [13-3] Activity 구현체
    //        멱등키 → 결과 캐시. 재시도로 같은 키가 다시 들어오면 부수효과 없이 캐시를 돌려줍니다.
    //        sku 가 OUT-OF-STOCK 이면 재고 실패, address 가 비면 배송 실패입니다.
    // ------------------------------------------------------------------

    static final Map<String, String> IDEMPOTENCY_STORE = new ConcurrentHashMap<>();

    public static class PaymentActivityImpl implements PaymentActivity {
        @Override
        public String charge(String key, String orderId, long amount) {
            String cached = IDEMPOTENCY_STORE.get(key);
            if (cached != null) {
                System.out.println("[payment] 멱등 히트 key=" + key + " → " + cached);
                return cached;
            }
            String paymentId = "pay-" + orderId.replace("order-", "");
            IDEMPOTENCY_STORE.put(key, paymentId);
            System.out.println("[payment] charge orderId=" + orderId + " amount=" + amount + " → " + paymentId);
            return paymentId;
        }

        @Override
        public void refund(String key, String paymentId) {
            // 보상은 반드시 멱등해야 합니다. 무한 재시도이므로 두 번 이상 실행될 수 있습니다.
            if (IDEMPOTENCY_STORE.putIfAbsent(key, "refunded") != null) {
                System.out.println("[payment] 이미 환불됨 key=" + key + " — 건너뜁니다");
                return;
            }
            System.out.println("[payment] refund paymentId=" + paymentId);
        }
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        @Override
        public String reserve(String key, String orderId, String sku, int qty) {
            String cached = IDEMPOTENCY_STORE.get(key);
            if (cached != null) {
                return cached;
            }
            if ("OUT-OF-STOCK".equals(sku)) {
                // 재고 부족은 재시도해도 소용없습니다. nonRetryable 로 던져 즉시 보상으로 넘깁니다.
                throw ApplicationFailure.newNonRetryableFailure(
                        "재고 부족 sku=" + sku + " qty=" + qty, "OutOfStock");
            }
            String reservationId = "rsv-" + orderId.replace("order-", "");
            IDEMPOTENCY_STORE.put(key, reservationId);
            System.out.println("[inventory] reserve sku=" + sku + " qty=" + qty + " → " + reservationId);
            return reservationId;
        }

        @Override
        public void release(String key, String reservationId) {
            if (IDEMPOTENCY_STORE.putIfAbsent(key, "released") != null) {
                System.out.println("[inventory] 이미 해제됨 key=" + key + " — 건너뜁니다");
                return;
            }
            System.out.println("[inventory] release reservationId=" + reservationId);
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        @Override
        public String requestShipment(String key, String orderId, String address) {
            String cached = IDEMPOTENCY_STORE.get(key);
            if (cached != null) {
                return cached;
            }
            if (address == null || address.isBlank()) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "배송지가 비어 있습니다 orderId=" + orderId, "InvalidAddress");
            }
            String shipmentId = "shp-" + orderId.replace("order-", "");
            IDEMPOTENCY_STORE.put(key, shipmentId);
            System.out.println("[shipping] requestShipment address=" + address + " → " + shipmentId);
            return shipmentId;
        }

        @Override
        public void cancelShipment(String key, String shipmentId) {
            if (IDEMPOTENCY_STORE.putIfAbsent(key, "cancelled") != null) {
                System.out.println("[shipping] 이미 취소됨 key=" + key + " — 건너뜁니다");
                return;
            }
            System.out.println("[shipping] cancelShipment shipmentId=" + shipmentId);
        }
    }

    public static class NotificationActivityImpl implements NotificationActivity {
        @Override
        public void notifyCustomer(String key, String orderId, String message) {
            System.out.println("[notification] orderId=" + orderId + " message=" + message);
        }
    }

    // ------------------------------------------------------------------
    // [13-5] Workflow 인터페이스
    // ------------------------------------------------------------------

    @WorkflowInterface
    public interface OrderWorkflow {

        @WorkflowMethod
        String processOrder(OrderRequest req);

        @SignalMethod
        void cancelOrder(String reason);

        @SignalMethod
        void paymentConfirmed(String txId);

        @QueryMethod
        OrderState getState();

        @UpdateMethod
        String changeAddress(String newAddress);

        @UpdateValidatorMethod(updateName = "changeAddress")
        void validateChangeAddress(String newAddress);
    }

    // ------------------------------------------------------------------
    // [13-4] ActivityOptions — 액티비티마다 다른 정책
    // ------------------------------------------------------------------

    static final ActivityOptions PAYMENT_OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setScheduleToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .setDoNotRetry("InsufficientFunds", "CardDeclined")
                    .build())
            .build();

    static final ActivityOptions INVENTORY_OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofMinutes(3))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(5)
                    .setDoNotRetry("OutOfStock")
                    .build())
            .build();

    static final ActivityOptions SHIPPING_OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(Duration.ofMinutes(30))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(10)
                    .setDoNotRetry("InvalidAddress")
                    .build())
            .build();

    // 알림은 실패해도 주문을 실패시키면 안 됩니다. 짧게 끊고 호출부에서 예외를 삼킵니다.
    static final ActivityOptions NOTIFY_OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build();

    // 보상은 무한 재시도. maximumAttempts 를 지정하지 않으면 기본이 무제한입니다.
    static final ActivityOptions COMPENSATION_OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofHours(24))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(5))
                    .build())
            .build();

    // ------------------------------------------------------------------
    // [13-6] Workflow 구현
    // ------------------------------------------------------------------

    public static final SearchAttributeKey<String> ORDER_STATUS_SA =
            SearchAttributeKey.forKeyword("OrderStatus");
    public static final SearchAttributeKey<String> CUSTOMER_ID_SA =
            SearchAttributeKey.forKeyword("CustomerId");

    public static class OrderWorkflowImpl implements OrderWorkflow {

        private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, PAYMENT_OPTS);
        private final InventoryActivity inventory =
                Workflow.newActivityStub(InventoryActivity.class, INVENTORY_OPTS);
        private final ShippingActivity shipping =
                Workflow.newActivityStub(ShippingActivity.class, SHIPPING_OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, NOTIFY_OPTS);

        // 보상 전용 스텁 — 무한 재시도 정책
        private final PaymentActivity paymentC =
                Workflow.newActivityStub(PaymentActivity.class, COMPENSATION_OPTS);
        private final InventoryActivity inventoryC =
                Workflow.newActivityStub(InventoryActivity.class, COMPENSATION_OPTS);
        private final ShippingActivity shippingC =
                Workflow.newActivityStub(ShippingActivity.class, COMPENSATION_OPTS);

        private OrderStatus status = OrderStatus.RECEIVED;
        private String paymentId;
        private String reservationId;
        private String shipmentId;
        private String address;
        private String failureReason;

        private boolean cancelRequested;
        private String cancelReason;
        private String paymentTxId;

        @Override
        public String processOrder(OrderRequest req) {
            this.address = req.address();
            log.info("[{}] 주문 접수 customer={} sku={} qty={} amount={}",
                    req.orderId(), req.customerId(), req.sku(), req.qty(), req.amount());

            Workflow.upsertTypedSearchAttributes(
                    CUSTOMER_ID_SA.valueSet(req.customerId()),
                    ORDER_STATUS_SA.valueSet(status.name()));

            // setContinueWithError(false) 가 기본입니다. 보상 하나가 실패하면 나머지를 멈추므로
            // true 로 두어 "가능한 것은 모두 되돌린다"는 정책을 택합니다.
            Saga.Options sagaOpts = new Saga.Options.Builder()
                    .setParallelCompensation(false)
                    .setContinueWithError(true)
                    .build();
            Saga saga = new Saga(sagaOpts);

            try {
                // --- 1단계: 결제 ---
                checkCancelled(saga);
                paymentId = payment.charge(key(req, "charge"), req.orderId(), req.amount());
                // 보상 등록은 액티비티 성공 "직후". 호출 전에 등록하면 하지도 않은 결제를 환불합니다.
                saga.addCompensation(() ->
                        paymentC.refund(key(req, "refund"), paymentId));
                setStatus(OrderStatus.PAID);
                log.info("[{}] 결제 완료 paymentId={}", req.orderId(), paymentId);

                // --- 1.5단계: 결제 확인 시그널 대기 (최대 30분) ---
                boolean confirmed = Workflow.await(Duration.ofMinutes(30),
                        () -> paymentTxId != null || cancelRequested);
                if (!confirmed) {
                    log.warn("[{}] 결제 확인 시그널 30분 타임아웃 — 자동 취소", req.orderId());
                    cancelRequested = true;
                    cancelReason = "PAYMENT_CONFIRM_TIMEOUT";
                }

                // --- 2단계: 재고 차감 ---
                checkCancelled(saga);
                reservationId = inventory.reserve(
                        key(req, "reserve"), req.orderId(), req.sku(), req.qty());
                saga.addCompensation(() ->
                        inventoryC.release(key(req, "release"), reservationId));
                setStatus(OrderStatus.RESERVED);
                log.info("[{}] 재고 확보 reservationId={}", req.orderId(), reservationId);

                // --- 3단계: 배송 요청 ---
                checkCancelled(saga);
                shipmentId = shipping.requestShipment(
                        key(req, "ship"), req.orderId(), address);
                saga.addCompensation(() ->
                        shippingC.cancelShipment(key(req, "cancelShip"), shipmentId));
                setStatus(OrderStatus.SHIPPED);
                log.info("[{}] 배송 요청 shipmentId={}", req.orderId(), shipmentId);

                // 배송 요청이 나가는 동안 도착한 취소 시그널을 여기서 잡습니다.
                // 이 시점에는 shipmentId 가 있으므로 cancelShipment 로 되돌릴 수 있습니다.
                checkCancelled(saga);

                // --- 4단계: 알림 (실패해도 주문은 성공) ---
                notifyQuietly(req.orderId(), "주문이 발송되었습니다. 운송장 " + shipmentId);

                setStatus(OrderStatus.COMPLETED);
                log.info("[{}] 주문 완료", req.orderId());
                return req.orderId() + " COMPLETED";

            } catch (ActivityFailure e) {
                failureReason = rootMessage(e);
                setStatus(OrderStatus.COMPENSATING);
                log.error("[{}] 실패 — 보상 시작: {}", req.orderId(), failureReason);
                compensate(saga, req);
                setStatus(OrderStatus.FAILED);
                notifyQuietly(req.orderId(), "주문 처리에 실패했습니다: " + failureReason);
                throw ApplicationFailure.newFailure(
                        "주문 실패 " + req.orderId() + ": " + failureReason, "OrderFailed");

            } catch (CancelledByUser e) {
                failureReason = cancelReason;
                setStatus(OrderStatus.COMPENSATING);
                log.warn("[{}] 취소 요청 — 보상 시작: {}", req.orderId(), cancelReason);
                compensate(saga, req);
                setStatus(OrderStatus.CANCELLED);
                notifyQuietly(req.orderId(), "주문이 취소되었습니다: " + cancelReason);
                return req.orderId() + " CANCELLED";
            }
        }

        // [13-7] 각 단계 사이에서 취소 시그널을 확인합니다.
        private void checkCancelled(Saga saga) {
            if (cancelRequested) {
                throw new CancelledByUser();
            }
        }

        // [13-7] 보상은 반드시 detached scope 안에서. 워크플로우가 외부 취소를 받은 상태에서는
        // 일반 스코프의 액티비티 호출이 즉시 취소되어 보상이 한 줄도 실행되지 않습니다.
        private void compensate(Saga saga, OrderRequest req) {
            Workflow.newDetachedCancellationScope(() -> {
                Workflow.upsertTypedSearchAttributes(
                        ORDER_STATUS_SA.valueSet(OrderStatus.COMPENSATING.name()));
                saga.compensate();
                log.info("[{}] 보상 완료", req.orderId());
            }).run();
        }

        private void notifyQuietly(String orderId, String message) {
            try {
                notification.notifyCustomer(
                        orderId + ":notify:" + Workflow.currentTimeMillis(), orderId, message);
            } catch (ActivityFailure e) {
                // 알림 실패가 주문 전체를 실패시켜서는 안 됩니다.
                log.warn("[{}] 알림 실패(무시): {}", orderId, rootMessage(e));
            }
        }

        private void setStatus(OrderStatus next) {
            this.status = next;
            Workflow.upsertTypedSearchAttributes(ORDER_STATUS_SA.valueSet(next.name()));
        }

        private static String key(OrderRequest req, String op) {
            return req.orderId() + ":" + op;
        }

        private static String rootMessage(Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            return c.getMessage();
        }

        // --- Signal / Query / Update ---

        @Override
        public void cancelOrder(String reason) {
            // [13-7] 배송이 이미 출발했으면 취소를 거절합니다. 시그널 핸들러는 예외를 던져도
            // 워크플로우를 실패시키지 않으므로, 로그만 남기고 무시하는 편이 안전합니다.
            if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
                log.warn("취소 거절 — 이미 배송 단계입니다. status={}", status);
                return;
            }
            this.cancelRequested = true;
            this.cancelReason = reason;
            log.info("취소 시그널 수신: {}", reason);
        }

        @Override
        public void paymentConfirmed(String txId) {
            this.paymentTxId = txId;
            log.info("결제 확인 시그널 수신: {}", txId);
        }

        @Override
        public OrderState getState() {
            return new OrderState(
                    Workflow.getInfo().getWorkflowId().replace("order-", "order-"),
                    status, paymentId, reservationId, shipmentId, address, failureReason);
        }

        @Override
        public String changeAddress(String newAddress) {
            String old = this.address;
            this.address = newAddress;
            log.info("배송지 변경 {} → {}", old, newAddress);
            return "배송지가 " + newAddress + " 로 변경되었습니다";
        }

        @Override
        public void validateChangeAddress(String newAddress) {
            if (newAddress == null || newAddress.isBlank()) {
                throw new IllegalArgumentException("배송지는 비어 있을 수 없습니다");
            }
            if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
                throw new IllegalArgumentException("배송이 시작되어 변경할 수 없습니다");
            }
        }
    }

    // 취소 경로를 실패 경로와 구분하기 위한 내부 신호용 예외
    static class CancelledByUser extends RuntimeException {
    }

    // ------------------------------------------------------------------
    // [13-8] Worker + Starter
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        if (args.length >= 2 && "start".equals(args[0])) {
            startOrder(client, args[1]);
            return;
        }

        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new PaymentActivityImpl(),
                new InventoryActivityImpl(),
                new ShippingActivityImpl(),
                new NotificationActivityImpl());
        factory.start();
        System.out.println("Worker 기동 완료 — taskQueue=" + TASK_QUEUE);
    }

    static void startOrder(WorkflowClient client, String orderId) {
        // 시나리오별 입력을 orderId 로 분기합니다.
        OrderRequest req = switch (orderId) {
            case "order-2002" -> new OrderRequest(
                    "order-2002", "cust-77", "OUT-OF-STOCK", 1, 39000, "서울시 성동구 왕십리로 83");
            case "order-2004" -> new OrderRequest(
                    "order-2004", "cust-99", "SKU-BLACK-TEE", 1, 29000, "서울시 마포구 월드컵북로 21");
            default -> new OrderRequest(
                    orderId, "cust-42", "SKU-BLACK-TEE", 2, 58000, "서울시 성동구 왕십리로 83");
        };

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(orderId)
                .setWorkflowExecutionTimeout(Duration.ofHours(2))
                .setWorkflowTaskTimeout(Duration.ofSeconds(10))
                .build();

        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class, opts);
        WorkflowClient.start(wf::processOrder, req);
        System.out.println("started " + orderId);

        // order-2004 를 제외한 나머지는 결제 확인 시그널을 바로 보냅니다.
        if (!"order-2004".equals(orderId)) {
            wf.paymentConfirmed("tx-" + orderId.replace("order-", ""));
        }
    }
}
