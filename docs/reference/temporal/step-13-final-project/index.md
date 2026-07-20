# Step 13 — 최종 프로젝트: 주문 처리 Saga

> **학습 목표**
> - 기능·비기능 요구사항을 표로 정리하고, 그중 무엇이 Temporal 로 자동 해결되고 무엇이 코드로 남는지 구분한다
> - 결제 → 재고 → 배송 → 알림의 정상 경로와 역순 보상 경로를 하나의 워크플로우로 구현한다
> - 액티비티마다 **다른 타임아웃·재시도 정책**을 근거와 함께 설계하고, 보상만 무한 재시도로 분리한다
> - Signal · Query · Update · Timer · Saga · 버저닝 · Search Attribute 를 한 워크플로우에 통합한다
> - **네 가지 시나리오(정상 / 재고 부족 / 배송 중 취소 / 결제 확인 타임아웃)의 전체 히스토리를 직접 읽는다**
> - JUnit 5 로 네 시나리오를 시간 스킵으로 재현하고, `InOrder` 로 **보상 실행 순서**를 검증한다
> - 배포 전 체크리스트 15항목으로 "운영에서 조용히 깨지는" 구멍을 미리 막는다
>
> **선행 스텝**: Step 12 — 운영
> **예상 소요**: 180분

---

## 13-0. 요구사항 명세

지금까지 12개 스텝에서 배운 것을 하나의 완결된 워크플로우로 종합합니다. 먼저 무엇을 만들지 못 박습니다.

**기능 요구사항**

| # | 요구사항 | 관련 스텝 |
|---|---|---|
| F-1 | 주문 접수 시 결제 → 재고 차감 → 배송 요청 → 알림 순으로 처리한다 | 03, 04 |
| F-2 | 어느 단계에서 실패하든 **이미 성공한 단계를 역순으로 되돌린다** | 09 |
| F-3 | 결제 후 30분 안에 PG사 결제 확인 시그널이 오지 않으면 자동 취소한다 | 06, 07 |
| F-4 | 고객이 언제든 취소 시그널을 보낼 수 있다. 단 **배송이 출발한 뒤에는 거절한다** | 07 |
| F-5 | 주문 상태를 외부에서 조회할 수 있다(Query). 워크플로우를 깨우지 않는다 | 07 |
| F-6 | 배송 전까지 배송지를 변경할 수 있다(Update + Validator) | 07 |
| F-7 | 재고 부족·잘못된 배송지처럼 재시도가 무의미한 실패는 즉시 보상으로 넘어간다 | 05 |
| F-8 | 알림 전송이 실패해도 주문 전체를 실패시키지 않는다 | 04, 05 |
| F-9 | 주문 상태와 고객 ID 를 Search Attribute 로 노출해 목록 조회가 가능해야 한다 | 12 |
| F-10 | 배포 중 코드가 바뀌어도 진행 중인 주문이 깨지지 않아야 한다 | 10 |

**비기능 요구사항**

| # | 요구사항 | 어떻게 만족시키는가 |
|---|---|---|
| N-1 | **멱등성** — 액티비티가 재시도로 두 번 실행돼도 부수효과는 한 번 | 모든 액티비티 첫 인자에 멱등키. 구현체가 키로 중복 판정 |
| N-2 | **무중단 배포** — 워커를 새 코드로 교체해도 진행 중 워크플로우가 살아 있어야 함 | `Workflow.getVersion` 으로 분기, 리플레이 테스트를 CI 에 |
| N-3 | **관측 가능성** — 어느 주문이 어느 단계에서 멈췄는지 즉시 알 수 있어야 함 | `Workflow.getLogger` + Search Attribute upsert + Query |

> 💡 **Temporal 이 공짜로 주는 것과 여전히 내가 짜야 하는 것**
> 재시도, 타임아웃 감시, 상태 지속, 워커 크래시 후 재개는 Temporal 이 해 줍니다.
> 반면 **보상 로직, 멱등키 설계, 취소 정책, 버저닝 분기**는 여전히 내가 짜야 합니다.
> 이 스텝은 후자만 다룹니다.

---

## 13-1. 전체 설계

정상 경로와 보상 경로를 한 장에 그립니다.

```
 Client                Temporal Server            Worker (OrderWorkflowImpl)        외부 시스템
   │                          │                              │                          │
   │ start order-2001 ────────▶                              │                          │
   │                          │──── WorkflowTask ───────────▶│                          │
   │                          │                        upsert SA (RECEIVED)             │
   │                          │                              │                          │
   │  ┌──────────────────────── 정상 경로 (forward path) ─────────────────────────────┐ │
   │  │                       │                              │                        │ │
   │  │                       │◀─ ScheduleActivity charge ───│                        │ │
   │  │                       │──── ActivityTask ───────────▶│─── PG 결제 ───────────▶│ │
   │  │                       │◀─── Completed pay-2001 ──────│                        │ │
   │  │                    [ saga.addCompensation(refund) ]   ← 성공 "직후"에 등록      │ │
   │  │                       │                              │                        │ │
   │  │  ✂ cancelOrder 시그널 진입 지점 ①  (아직 결제만 됨 → refund 만 보상)           │ │
   │  │                       │                              │                        │ │
   │  │            Workflow.await(30m, paymentConfirmed 도착?)                          │ │
   │  │                       │                              │                        │ │
   │  │                       │◀─ ScheduleActivity reserve ──│                        │ │
   │  │                       │──── ActivityTask ───────────▶│─── 재고 차감 ─────────▶│ │
   │  │                       │◀─── Completed rsv-2001 ──────│                        │ │
   │  │                    [ saga.addCompensation(release) ]                           │ │
   │  │                       │                              │                        │ │
   │  │  ✂ cancelOrder 시그널 진입 지점 ②  (결제+재고 → release, refund 보상)          │ │
   │  │                       │                              │                        │ │
   │  │                       │◀─ ScheduleActivity ship ─────│                        │ │
   │  │                       │──── ActivityTask ───────────▶│─── 배송사 API ────────▶│ │
   │  │                       │◀─── Completed shp-2001 ──────│                        │ │
   │  │                    [ saga.addCompensation(cancelShipment) ]                    │ │
   │  │                       │                              │                        │ │
   │  │  ✂ cancelOrder 시그널 진입 지점 ③  (셋 다 → cancelShipment, release, refund)   │ │
   │  │                       │                              │                        │ │
   │  │                       │◀─ ScheduleActivity notify ───│                        │ │
   │  │                       │   (실패해도 삼킨다 — 주문은 성공)                       │ │
   │  │                       │                              │                        │ │
   │  │  ✂ 이 지점 이후의 cancelOrder 는 거절 (status = SHIPPED)                       │ │
   │  └────────────────────────────────────────────────────────────────────────────────┘ │
   │                          │                        upsert SA (COMPLETED)             │
   │                          │◀── WorkflowExecutionCompleted                            │

 보상 경로 (compensation path) — 등록의 역순으로 실행됩니다

     cancelShipment (shp-2001)  ──▶  release (rsv-2001)  ──▶  refund (pay-2001)
            ▲                              ▲                        ▲
            └── 3단계까지 갔을 때           └── 2단계까지            └── 1단계까지
     전부 Workflow.newDetachedCancellationScope 안에서 실행 · 재시도 무제한
```

읽는 요령은 세 가지입니다.

1. **보상 등록은 액티비티 성공 직후**입니다. 호출 전에 등록하면 하지도 않은 결제를 환불하려 듭니다.
2. **취소 시그널은 아무 때나 들어옵니다.** 언제 들어왔느냐에 따라 되돌릴 것이 달라집니다(13-7).
3. **보상은 역순**입니다. Saga 가 등록의 역순으로 자동 실행하므로 순서를 손으로 관리하지 않습니다.

---

## 13-2. 도메인 타입

먼저 데이터부터 정합니다. 전부 Java 21 record 와 enum 입니다.

```java
public record OrderRequest(
        String orderId,
        String customerId,
        String sku,
        int qty,
        long amount,
        String address) {
}
```

상태는 enum 으로 못 박습니다. 문자열로 두면 오타가 조용히 통과합니다.

```java
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
```

`COMPENSATING` 을 별도 상태로 둔 이유가 있습니다. 보상은 몇 초에 끝날 수도 있지만, 결제사가 장애면 몇 시간이 걸릴 수 있습니다. 그 사이 Query 를 하면 "실패했고 되돌리는 중"인지 "이미 다 되돌렸는지" 구분되어야 합니다.

Query 로 돌려줄 스냅샷입니다.

```java
public record OrderState(
        String orderId,
        OrderStatus status,
        String paymentId,
        String reservationId,
        String shipmentId,
        String address,
        String failureReason) {
}
```

> ⚠️ **함정 — Query 가 워크플로우 내부 객체를 그대로 돌려주면 안 됩니다**
> Query 핸들러가 워크플로우의 가변 필드를 참조하는 컬렉션이나 빌더를 그대로 반환하면,
> 직렬화 시점과 워크플로우 스레드의 상태 변경이 겹쳐 **일관성 없는 스냅샷**이 나갑니다.
> 위처럼 불변 record 로 **복사해서** 넘기십시오. Query 안에서 상태를 변경하는 것은 더 나쁩니다 —
> 그 변경은 히스토리에 남지 않으므로, 리플레이하면 사라져서 워커마다 다른 상태를 갖게 됩니다.

---

## 13-3. Activity 인터페이스 4종과 구현

인터페이스입니다. **모든 메서드의 첫 인자가 멱등키**라는 점에 주목하십시오.

```java
@ActivityInterface
public interface PaymentActivity {
    @ActivityMethod String charge(String idempotencyKey, String orderId, long amount);
    @ActivityMethod void   refund(String idempotencyKey, String paymentId);
}

@ActivityInterface
public interface InventoryActivity {
    @ActivityMethod String reserve(String idempotencyKey, String orderId, String sku, int qty);
    @ActivityMethod void   release(String idempotencyKey, String reservationId);
}

@ActivityInterface
public interface ShippingActivity {
    @ActivityMethod String requestShipment(String idempotencyKey, String orderId, String address);
    @ActivityMethod void   cancelShipment(String idempotencyKey, String shipmentId);
}

@ActivityInterface
public interface NotificationActivity {
    @ActivityMethod void notifyCustomer(String idempotencyKey, String orderId, String message);
}
```

멱등키는 워크플로우가 만듭니다. `orderId + ":" + 연산이름` 이면 충분합니다. 워크플로우가 리플레이되어도 같은 값이 나오고, 재시도 5번이 전부 같은 키를 들고 갑니다.

```java
private static String key(OrderRequest req, String op) {
    return req.orderId() + ":" + op;   // "order-2001:charge"
}
```

구현체는 키로 중복을 걸러냅니다. 실습에서는 `ConcurrentHashMap` 이지만, 실제로는 DB 의 유니크 인덱스나 Redis `SETNX` 입니다.

```java
public static class PaymentActivityImpl implements PaymentActivity {
    @Override
    public String charge(String key, String orderId, long amount) {
        String cached = IDEMPOTENCY_STORE.get(key);
        if (cached != null) {
            System.out.println("[payment] 멱등 히트 key=" + key + " → " + cached);
            return cached;                      // 두 번째 호출은 결제하지 않는다
        }
        String paymentId = "pay-" + orderId.replace("order-", "");
        IDEMPOTENCY_STORE.put(key, paymentId);
        System.out.println("[payment] charge orderId=" + orderId + " amount=" + amount);
        return paymentId;
    }

    @Override
    public void refund(String key, String paymentId) {
        if (IDEMPOTENCY_STORE.putIfAbsent(key, "refunded") != null) {
            System.out.println("[payment] 이미 환불됨 key=" + key + " — 건너뜁니다");
            return;
        }
        System.out.println("[payment] refund paymentId=" + paymentId);
    }
}
```

실패를 재현하기 위해 두 곳에 의도적인 분기를 넣습니다.

```java
// InventoryActivityImpl.reserve
if ("OUT-OF-STOCK".equals(sku)) {
    throw ApplicationFailure.newNonRetryableFailure(
            "재고 부족 sku=" + sku + " qty=" + qty, "OutOfStock");
}

// ShippingActivityImpl.requestShipment
if (address == null || address.isBlank()) {
    throw ApplicationFailure.newNonRetryableFailure(
            "배송지가 비어 있습니다 orderId=" + orderId, "InvalidAddress");
}
```

`newNonRetryableFailure` 로 던지면 재시도 정책을 무시하고 즉시 워크플로우로 올라옵니다. 재고 부족은 3초 뒤에 다시 시도해도 여전히 재고 부족이므로, 5번 재시도할 이유가 없습니다.

> ⚠️ **함정 — 보상 액티비티의 멱등성을 빼먹는 것**
> 보상은 **무한 재시도**입니다(13-4). 그 말은 두 번, 열 번 실행될 수 있다는 뜻입니다.
> `refund` 가 멱등하지 않으면 결제사 장애로 타임아웃이 세 번 난 뒤 **삼중 환불**이 됩니다.
> 게다가 이건 예외가 안 납니다. 워크플로우는 `COMPLETED` 로 끝나고, 며칠 뒤 정산에서 발견됩니다.
> 정방향 액티비티의 멱등성은 흔히 챙기지만 보상의 멱등성은 잊습니다. 보상이 더 위험합니다.

---

## 13-4. ActivityOptions 설계

액티비티 네 개에 같은 옵션을 쓰면 안 됩니다. 성질이 전부 다릅니다.

| 액티비티 | StartToClose | ScheduleToClose | maximumAttempts | doNotRetry | 근거 |
|---|---|---|---|---|---|
| `charge` | 10s | 60s | 3 | `InsufficientFunds`, `CardDeclined` | 사용자가 결제창 앞에서 기다린다. 총 대기 60초를 넘기면 안 되고, 잔액 부족은 재시도해도 결과가 같다 |
| `reserve` | 30s | 3m | 5 | `OutOfStock` | 재고 DB 는 락 경합으로 몇 초 밀릴 수 있어 재시도 가치가 있다. 다만 재고 자체가 없으면 재시도 무의미 |
| `requestShipment` | 2m | 30m | 10 | `InvalidAddress` | 외부 배송사 API. 점검·5xx 가 일상이라 넉넉히 재시도. 주소 형식 오류만 즉시 포기 |
| `notifyCustomer` | 5s | 30s | 2 | — | 실패해도 주문을 망치면 안 되므로 짧게 끊고 **호출부에서 예외를 삼킨다** |
| **보상 전체** | 30s | 24h | **무제한** | — | 되돌리지 못한 상태가 남는 것이 가장 나쁘다. 결제사가 6시간 장애여도 결국 성공해야 한다 |

코드입니다.

```java
static final ActivityOptions PAYMENT_OPTS = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setScheduleToCloseTimeout(Duration.ofSeconds(60))
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setDoNotRetry("InsufficientFunds", "CardDeclined")
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

// 보상 — maximumAttempts 를 "부르지 않는다" = 무제한
static final ActivityOptions COMPENSATION_OPTS = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setScheduleToCloseTimeout(Duration.ofHours(24))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofMinutes(5))
                .build())
        .build();
```

같은 액티비티 인터페이스를 **두 번 스텁**으로 만듭니다. 정방향용과 보상용의 정책이 다르기 때문입니다.

```java
private final PaymentActivity payment  = Workflow.newActivityStub(PaymentActivity.class, PAYMENT_OPTS);
private final PaymentActivity paymentC = Workflow.newActivityStub(PaymentActivity.class, COMPENSATION_OPTS);
```

> ⚠️ **함정 — 보상에 `setMaximumAttempts(3)` 을 습관적으로 붙이는 것**
> `RetryOptions.maximumAttempts` 의 기본값은 `0` 이고, 0 이 곧 **무제한**입니다.
> 습관적으로 3을 넣으면 보상이 세 번 만에 포기하고, 그 시점에 **결제는 됐는데 환불은 안 된** 상태로 워크플로우가 종료됩니다.
> 이때 히스토리에는 예외가 안 남습니다. `Saga.Options.setContinueWithError(true)` 를 켜 두었다면 더더욱 조용히 넘어갑니다.
> "돈은 빠져나갔는데 주문은 취소됨"이 며칠 뒤 CS 로 들어옵니다. **보상에는 상한을 두지 마십시오.**

---

## 13-5. Workflow 인터페이스

Signal 둘, Query 하나, Update 하나(Validator 포함)를 한 인터페이스에 모읍니다.

```java
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
```

넷의 차이를 다시 확인합니다.

| 종류 | 히스토리에 남는가 | 응답이 있는가 | 거절할 수 있는가 | 이 프로젝트에서의 용도 |
|---|---|---|---|---|
| Signal | 남는다 (`WorkflowExecutionSignaled`) | 없음 | 없음 (조용히 무시만) | 취소 요청, PG 결제 확인 |
| Query | **안 남는다** | 있음 | — | 주문 상태 조회 |
| Update | 남는다 (`WorkflowExecutionUpdateAccepted`) | 있음 | **Validator 로 거절 가능** | 배송지 변경 |

`changeAddress` 를 Signal 이 아니라 Update 로 만든 이유가 여기 있습니다. "배송이 이미 시작돼서 변경할 수 없다"는 사실을 **호출자에게 알려야** 하기 때문입니다.

```java
@Override
public void validateChangeAddress(String newAddress) {
    if (newAddress == null || newAddress.isBlank()) {
        throw new IllegalArgumentException("배송지는 비어 있을 수 없습니다");
    }
    if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
        throw new IllegalArgumentException("배송이 시작되어 변경할 수 없습니다");
    }
}
```

Validator 에서 던진 예외는 호출자에게 그대로 전달되고, **히스토리에는 아무것도 기록되지 않습니다**. 거절된 요청으로 히스토리가 부풀지 않는다는 뜻입니다.

```bash
temporal workflow update -w order-2001 --name changeAddress -i '""'
```

**결과**
```
Error: unable to update workflow: 배송지는 비어 있을 수 없습니다
  type: IllegalArgumentException
```

> ⚠️ **함정 — Validator 안에서 상태를 바꾸는 것**
> Validator 는 히스토리에 아무것도 남기지 않은 채 실행됩니다. 여기서 필드를 하나라도 건드리면
> 리플레이 때 그 변경이 재현되지 않아 **워커마다 다른 상태**를 갖게 됩니다. Validator 는 순수 검증만 하십시오.

---

## 13-6. Workflow 구현

전체를 절 단위로 쪼개 봅니다. 실제 코드는 `Practice.java` 에 이어져 있습니다.

**① 필드와 스텁**

```java
public static class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    private final PaymentActivity      payment      = Workflow.newActivityStub(PaymentActivity.class, PAYMENT_OPTS);
    private final InventoryActivity    inventory    = Workflow.newActivityStub(InventoryActivity.class, INVENTORY_OPTS);
    private final ShippingActivity     shipping     = Workflow.newActivityStub(ShippingActivity.class, SHIPPING_OPTS);
    private final NotificationActivity notification = Workflow.newActivityStub(NotificationActivity.class, NOTIFY_OPTS);

    private final PaymentActivity   paymentC   = Workflow.newActivityStub(PaymentActivity.class,   COMPENSATION_OPTS);
    private final InventoryActivity inventoryC = Workflow.newActivityStub(InventoryActivity.class, COMPENSATION_OPTS);
    private final ShippingActivity  shippingC  = Workflow.newActivityStub(ShippingActivity.class,  COMPENSATION_OPTS);

    private OrderStatus status = OrderStatus.RECEIVED;
    private String paymentId, reservationId, shipmentId, address, failureReason;
    private boolean cancelRequested;
    private String  cancelReason, paymentTxId;
```

`Workflow.getLogger` 를 쓰는 이유는 하나입니다. **리플레이 중에는 로그를 찍지 않기** 때문입니다. 평범한 `LoggerFactory.getLogger` 를 쓰면 워커가 재시작될 때마다 과거 로그가 통째로 다시 쏟아져 나옵니다.

**② 시작과 Search Attribute**

```java
@Override
public String processOrder(OrderRequest req) {
    this.address = req.address();
    log.info("[{}] 주문 접수 customer={} sku={} qty={} amount={}",
            req.orderId(), req.customerId(), req.sku(), req.qty(), req.amount());

    Workflow.upsertTypedSearchAttributes(
            CUSTOMER_ID_SA.valueSet(req.customerId()),
            ORDER_STATUS_SA.valueSet(status.name()));
```

Search Attribute 는 상태가 바뀔 때마다 갱신합니다. 이걸 해 두면 운영에서 이런 질의가 가능해집니다.

```bash
temporal workflow list --query 'OrderStatus = "COMPENSATING"'
```

**결과**
```
  WorkflowId   Name           Status   StartTime             OrderStatus
  order-2019   OrderWorkflow  Running  2026-03-11 09:41:02   COMPENSATING
  order-2027   OrderWorkflow  Running  2026-03-11 09:44:55   COMPENSATING
```

보상 중에 매달려 있는 주문만 골라 볼 수 있습니다. 이게 없으면 워커 로그를 grep 해야 합니다.

**③ Saga 초기화**

```java
    Saga.Options sagaOpts = new Saga.Options.Builder()
            .setParallelCompensation(false)   // 역순 직렬 실행
            .setContinueWithError(true)       // 하나 실패해도 나머지는 계속
            .build();
    Saga saga = new Saga(sagaOpts);
```

`setParallelCompensation(false)` 는 기본값이지만 명시합니다. 배송 취소보다 환불이 먼저 나가면 안 되기 때문입니다. `setContinueWithError(true)` 는 "가능한 것은 전부 되돌린다"는 정책입니다.

**④ 정방향 3단계 + 보상 등록**

```java
    try {
        // 1단계: 결제
        checkCancelled(saga);
        paymentId = payment.charge(key(req, "charge"), req.orderId(), req.amount());
        saga.addCompensation(() -> paymentC.refund(key(req, "refund"), paymentId));
        setStatus(OrderStatus.PAID);
        log.info("[{}] 결제 완료 paymentId={}", req.orderId(), paymentId);

        // 1.5단계: PG 결제 확인 시그널 대기 (최대 30분)
        boolean confirmed = Workflow.await(Duration.ofMinutes(30),
                () -> paymentTxId != null || cancelRequested);
        if (!confirmed) {
            log.warn("[{}] 결제 확인 시그널 30분 타임아웃 — 자동 취소", req.orderId());
            cancelRequested = true;
            cancelReason = "PAYMENT_CONFIRM_TIMEOUT";
        }

        // 2단계: 재고 차감
        checkCancelled(saga);
        reservationId = inventory.reserve(key(req, "reserve"), req.orderId(), req.sku(), req.qty());
        saga.addCompensation(() -> inventoryC.release(key(req, "release"), reservationId));
        setStatus(OrderStatus.RESERVED);

        // 3단계: 배송 요청
        checkCancelled(saga);
        shipmentId = shipping.requestShipment(key(req, "ship"), req.orderId(), address);
        saga.addCompensation(() -> shippingC.cancelShipment(key(req, "cancelShip"), shipmentId));
        setStatus(OrderStatus.SHIPPED);

        // 배송 요청이 나가는 동안 도착한 취소를 여기서 잡는다
        checkCancelled(saga);

        // 4단계: 알림 — 실패해도 주문은 성공
        notifyQuietly(req.orderId(), "주문이 발송되었습니다. 운송장 " + shipmentId);

        setStatus(OrderStatus.COMPLETED);
        return req.orderId() + " COMPLETED";
```

`Workflow.await(Duration, condition)` 은 조건이 참이 되면 `true`, 시간이 다 되면 `false` 를 돌려줍니다. **반환값을 버리면** 타임아웃과 정상 도착을 구분할 수 없습니다.

> ⚠️ **함정 — Saga 등록을 액티비티 호출 "전"에 하는 것**
> ```java
> saga.addCompensation(() -> paymentC.refund(k, paymentId));   // ← 잘못
> paymentId = payment.charge(k, req.orderId(), req.amount());
> ```
> `charge` 가 타임아웃으로 실패하면 `paymentId` 는 `null` 인데 보상은 이미 등록돼 있습니다.
> `refund(null)` 이 호출되고, 결제사는 "그런 결제 없음"으로 응답하며, 보상은 **무한 재시도**이므로
> 영원히 실패를 반복하다가 24시간 뒤 ScheduleToClose 로 죽습니다.
> 반드시 **성공 직후에 등록하고, 액티비티가 리턴한 값을 캡처**하십시오.

**⑤ 실패·취소 경로**

```java
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
        compensate(saga, req);
        setStatus(OrderStatus.CANCELLED);
        notifyQuietly(req.orderId(), "주문이 취소되었습니다: " + cancelReason);
        return req.orderId() + " CANCELLED";
    }
}
```

실패는 `throw` 로 끝내고(→ `WorkflowExecutionFailed`), 취소는 `return` 으로 끝냅니다(→ `WorkflowExecutionCompleted`). 취소는 "의도된 결말"이므로 실패로 기록하지 않습니다. 이렇게 하면 대시보드의 실패율 지표가 고객 취소로 오염되지 않습니다.

**⑥ 보상 실행 — detached scope**

```java
private void compensate(Saga saga, OrderRequest req) {
    Workflow.newDetachedCancellationScope(() -> {
        Workflow.upsertTypedSearchAttributes(
                ORDER_STATUS_SA.valueSet(OrderStatus.COMPENSATING.name()));
        saga.compensate();
        log.info("[{}] 보상 완료", req.orderId());
    }).run();
}
```

> ⚠️ **함정 — detached scope 없이 보상을 호출하는 것 (이 스텝에서 가장 위험)**
> 누군가 `temporal workflow cancel -w order-2003` 을 실행하면 워크플로우의 **모든 CancellationScope 가 취소 상태**가 됩니다.
> 취소된 스코프 안에서 액티비티를 스케줄하면 시작하기도 전에 `CanceledFailure` 로 끝납니다.
> 즉 `saga.compensate()` 를 그냥 호출하면 **보상 액티비티가 한 줄도 실행되지 않고** 조용히 리턴합니다.
> 로그에는 에러가 없습니다. 히스토리에는 `WorkflowExecutionCanceled` 만 남습니다.
> 결제만 되고 환불은 안 된 채로 "정상 취소"로 보입니다.
> `Workflow.newDetachedCancellationScope(...)` 로 감싸야 부모의 취소를 상속하지 않습니다.
> `.run()` 을 빼먹으면 스코프가 아예 실행되지 않는다는 것도 함께 기억하십시오.

**⑦ 알림 격리**

```java
private void notifyQuietly(String orderId, String message) {
    try {
        notification.notifyCustomer(
                orderId + ":notify:" + Workflow.currentTimeMillis(), orderId, message);
    } catch (ActivityFailure e) {
        log.warn("[{}] 알림 실패(무시): {}", orderId, rootMessage(e));
    }
}
```

> ⚠️ **함정 — 알림 실패가 주문 전체를 실패시키는 것**
> 결제·재고·배송이 전부 성공했는데 SMS 게이트웨이가 5xx 를 뱉었다고 합시다.
> `notifyCustomer` 를 그냥 호출하면 `ActivityFailure` 가 올라오고, 바깥 `catch (ActivityFailure e)` 가
> 그것을 "주문 실패"로 해석해 **보상 세 개를 전부 돌립니다**.
> 결과: 환불되고 재고가 풀리고 배송이 취소됩니다. 고객은 물건을 못 받고, 원인은 "문자가 안 갔다"입니다.
> 정말로 알림이 중요하다면 삼키지 말고 **별도 워크플로우로 분리**하십시오.
> 주문의 성패와 알림의 성패를 같은 트랜잭션에 묶지 않는 것이 핵심입니다.

**⑧ 버저닝 지점**

배송 요청 전에 사기 탐지 단계를 추가한다고 합시다. 진행 중인 주문을 깨지 않으려면 `Workflow.getVersion` 이 필요합니다.

```java
int v = Workflow.getVersion("add-fraud-check", Workflow.DEFAULT_VERSION, 1);
if (v >= 1) {
    fraud.screen(key(req, "fraud"), req.orderId(), req.customerId(), req.amount());
}
shipmentId = shipping.requestShipment(key(req, "ship"), req.orderId(), address);
```

배포 전에 시작된 주문은 히스토리에 `add-fraud-check` 마커가 없으므로 `DEFAULT_VERSION`(-1)이 나오고 사기 탐지를 건너뜁니다. 배포 후에 시작된 주문은 `1` 을 받아 새 단계를 탑니다. **모든 주문이 v1 이 될 때까지 이 분기를 지우면 안 됩니다.** 판단 기준은 `temporal workflow list --query 'ExecutionStatus="Running"'` 의 결과가 전부 배포 시각 이후인지입니다.

---

## 13-7. 취소 처리 설계

취소 시그널이 **어느 단계에서 왔느냐**에 따라 되돌릴 것이 다릅니다.

| 시그널 도착 시점 | `status` | 등록된 보상 | 실행되는 보상 | 결과 |
|---|---|---|---|---|
| 결제 전 | `RECEIVED` | 없음 | 없음 | 즉시 `CANCELLED` |
| 결제 후 / 재고 전 | `PAID` | refund | refund | `CANCELLED` (환불 1건) |
| 재고 후 / 배송 전 | `RESERVED` | refund, release | release → refund | `CANCELLED` (역순 2건) |
| 배송 요청 진행 중 | `RESERVED` | refund, release | cancelShipment → release → refund | 배송 요청은 완료된 뒤 취소됨. 역순 3건 |
| 배송 완료 후 | `SHIPPED` | 3건 | **없음** | **취소 거절** — 반품 프로세스로 넘김 |
| 종료 후 | `COMPLETED` | — | — | 시그널 자체가 유실 |

핵심 패턴은 **각 단계 사이에 `checkCancelled()` 를 넣는 것**입니다.

```java
private void checkCancelled(Saga saga) {
    if (cancelRequested) {
        throw new CancelledByUser();
    }
}
```

시그널 핸들러는 플래그만 세우고 즉시 리턴합니다. 실제 처리는 워크플로우 메인 스레드가 다음 `checkCancelled()` 에서 합니다. 이렇게 나누는 이유는, 시그널 핸들러가 액티비티를 호출하며 오래 블로킹하면 Workflow Task 가 타임아웃 나기 때문입니다.

배송 출발 이후의 거절 로직입니다.

```java
@Override
public void cancelOrder(String reason) {
    if (status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED) {
        log.warn("취소 거절 — 이미 배송 단계입니다. status={}", status);
        return;
    }
    this.cancelRequested = true;
    this.cancelReason = reason;
    log.info("취소 시그널 수신: {}", reason);
}
```

> ⚠️ **함정 — 시그널 핸들러에서 예외를 던지는 것**
> 여기서 `throw new IllegalStateException("이미 배송됨")` 을 하고 싶은 유혹이 강합니다. 하면 안 됩니다.
> 시그널 핸들러에서 던진 예외는 **클라이언트에게 전달되지 않고 Workflow Task 를 실패시킵니다.**
> Temporal 은 실패한 Workflow Task 를 무한 재시도하므로, 히스토리에 `WorkflowTaskFailed` 가 몇 초 간격으로 계속 쌓입니다.
> 워크플로우는 목록에서 "Running" 으로 보이지만 한 발짝도 못 나갑니다.
> 거절 사실을 호출자에게 알려야 한다면 Signal 이 아니라 **Update** 를 쓰십시오.

---

## 13-8. 실행 시나리오 4개

워커를 띄웁니다.

```bash
./gradlew run -PmainClass=com.example.order.Practice
```

**결과**
```
09:12:03.812 [main] INFO  i.t.s.WorkflowServiceStubsImpl - Created GRPC client for channel: ManagedChannelOrphanWrapper{...}
09:12:04.118 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
09:12:04.121 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Activity Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
Worker 기동 완료 — taskQueue=ORDER_TASK_QUEUE
```

### 시나리오 ① 정상 완료 — `order-2001`

```bash
./gradlew run -PmainClass=com.example.order.Practice --args="start order-2001"
```

**Worker 콘솔**
```
09:12:04.640 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - [order-2001] 주문 접수 customer=cust-42 sku=SKU-BLACK-TEE qty=2 amount=58000
[payment] charge orderId=order-2001 amount=58000 → pay-2001
09:12:04.905 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - [order-2001] 결제 완료 paymentId=pay-2001
09:12:04.912 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - 결제 확인 시그널 수신: tx-2001
[inventory] reserve sku=SKU-BLACK-TEE qty=2 → rsv-2001
09:12:05.187 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - [order-2001] 재고 확보 reservationId=rsv-2001
[shipping] requestShipment address=서울시 성동구 왕십리로 83 → shp-2001
09:12:05.502 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - [order-2001] 배송 요청 shipmentId=shp-2001
[notification] orderId=order-2001 message=주문이 발송되었습니다. 운송장 shp-2001
09:12:05.731 [workflow-method-order-2001-1] INFO  c.e.order.OrderWorkflowImpl - [order-2001] 주문 완료
```

```bash
temporal workflow show -w order-2001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:12:04Z  WorkflowExecutionStarted
   2  2026-03-11T09:12:04Z  WorkflowTaskScheduled
   3  2026-03-11T09:12:04Z  WorkflowTaskStarted
   4  2026-03-11T09:12:04Z  WorkflowTaskCompleted
   5  2026-03-11T09:12:04Z  UpsertWorkflowSearchAttributes
   6  2026-03-11T09:12:04Z  ActivityTaskScheduled          [charge]
   7  2026-03-11T09:12:04Z  WorkflowExecutionSignaled      [paymentConfirmed]
   8  2026-03-11T09:12:04Z  WorkflowTaskScheduled
   9  2026-03-11T09:12:04Z  ActivityTaskStarted
  10  2026-03-11T09:12:04Z  WorkflowTaskStarted
  11  2026-03-11T09:12:04Z  WorkflowTaskCompleted
  12  2026-03-11T09:12:04Z  ActivityTaskCompleted
  13  2026-03-11T09:12:04Z  WorkflowTaskScheduled
  14  2026-03-11T09:12:04Z  WorkflowTaskStarted
  15  2026-03-11T09:12:04Z  WorkflowTaskCompleted
  16  2026-03-11T09:12:04Z  UpsertWorkflowSearchAttributes [PAID]
  17  2026-03-11T09:12:04Z  ActivityTaskScheduled          [reserve]
  18  2026-03-11T09:12:05Z  ActivityTaskStarted
  19  2026-03-11T09:12:05Z  ActivityTaskCompleted
  20  2026-03-11T09:12:05Z  WorkflowTaskScheduled
  21  2026-03-11T09:12:05Z  WorkflowTaskStarted
  22  2026-03-11T09:12:05Z  WorkflowTaskCompleted
  23  2026-03-11T09:12:05Z  UpsertWorkflowSearchAttributes [RESERVED]
  24  2026-03-11T09:12:05Z  ActivityTaskScheduled          [requestShipment]
  25  2026-03-11T09:12:05Z  ActivityTaskStarted
  26  2026-03-11T09:12:05Z  ActivityTaskCompleted
  27  2026-03-11T09:12:05Z  WorkflowTaskScheduled
  28  2026-03-11T09:12:05Z  WorkflowTaskStarted
  29  2026-03-11T09:12:05Z  WorkflowTaskCompleted
  30  2026-03-11T09:12:05Z  UpsertWorkflowSearchAttributes [SHIPPED]
  31  2026-03-11T09:12:05Z  ActivityTaskScheduled          [notifyCustomer]
  32  2026-03-11T09:12:05Z  ActivityTaskStarted
  33  2026-03-11T09:12:05Z  ActivityTaskCompleted
  34  2026-03-11T09:12:05Z  WorkflowTaskScheduled
  35  2026-03-11T09:12:05Z  WorkflowTaskStarted
  36  2026-03-11T09:12:05Z  WorkflowTaskCompleted
  37  2026-03-11T09:12:05Z  UpsertWorkflowSearchAttributes [COMPLETED]
  38  2026-03-11T09:12:05Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-2001 COMPLETED"]
```

이벤트 38개. 액티비티 4개에 각 3이벤트(Scheduled/Started/Completed) = 12, Workflow Task 6쌍 = 18, Search Attribute 5, 시그널 1, 시작·종료 2 입니다.

```bash
temporal workflow describe -w order-2001
```

**결과**
```
Execution Info:
  Workflow Id       order-2001
  Run Id            5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
  Type              OrderWorkflow
  Namespace         default
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-11 09:12:04 +0000 UTC
  Close Time        2026-03-11 09:12:05 +0000 UTC
  Status            COMPLETED
  History Length    38
  History Size      6184

Search Attributes:
  CustomerId        cust-42
  OrderStatus       COMPLETED
```

### 시나리오 ② 재고 부족으로 보상 — `order-2002`

```bash
./gradlew run -PmainClass=com.example.order.Practice --args="start order-2002"
```

**Worker 콘솔**
```
09:18:11.204 [workflow-method-order-2002-1] INFO  c.e.order.OrderWorkflowImpl - [order-2002] 주문 접수 customer=cust-77 sku=OUT-OF-STOCK qty=1 amount=39000
[payment] charge orderId=order-2002 amount=39000 → pay-2002
09:18:11.489 [workflow-method-order-2002-1] INFO  c.e.order.OrderWorkflowImpl - [order-2002] 결제 완료 paymentId=pay-2002
09:18:11.495 [workflow-method-order-2002-1] INFO  c.e.order.OrderWorkflowImpl - 결제 확인 시그널 수신: tx-2002
09:18:11.702 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] WARN  i.t.i.a.ActivityTaskExecutors - Activity failure. ActivityId=17, activityType=Reserve, attempt=1
io.temporal.failure.ApplicationFailure: message='재고 부족 sku=OUT-OF-STOCK qty=1', type='OutOfStock', nonRetryable=true
09:18:11.744 [workflow-method-order-2002-1] ERROR c.e.order.OrderWorkflowImpl - [order-2002] 실패 — 보상 시작: 재고 부족 sku=OUT-OF-STOCK qty=1
[payment] refund paymentId=pay-2002
09:18:11.981 [workflow-method-order-2002-1] INFO  c.e.order.OrderWorkflowImpl - [order-2002] 보상 완료
[notification] orderId=order-2002 message=주문 처리에 실패했습니다: 재고 부족 sku=OUT-OF-STOCK qty=1
```

`attempt=1` 에서 끝났다는 점을 보십시오. `nonRetryable=true` 라 5회 재시도 정책이 무시됐습니다.

```bash
temporal workflow show -w order-2002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:18:11Z  WorkflowExecutionStarted
   2  2026-03-11T09:18:11Z  WorkflowTaskScheduled
   3  2026-03-11T09:18:11Z  WorkflowTaskStarted
   4  2026-03-11T09:18:11Z  WorkflowTaskCompleted
   5  2026-03-11T09:18:11Z  UpsertWorkflowSearchAttributes
   6  2026-03-11T09:18:11Z  ActivityTaskScheduled          [charge]
   7  2026-03-11T09:18:11Z  WorkflowExecutionSignaled      [paymentConfirmed]
   8  2026-03-11T09:18:11Z  WorkflowTaskScheduled
   9  2026-03-11T09:18:11Z  ActivityTaskStarted
  10  2026-03-11T09:18:11Z  WorkflowTaskStarted
  11  2026-03-11T09:18:11Z  WorkflowTaskCompleted
  12  2026-03-11T09:18:11Z  ActivityTaskCompleted
  13  2026-03-11T09:18:11Z  WorkflowTaskScheduled
  14  2026-03-11T09:18:11Z  WorkflowTaskStarted
  15  2026-03-11T09:18:11Z  WorkflowTaskCompleted
  16  2026-03-11T09:18:11Z  UpsertWorkflowSearchAttributes [PAID]
  17  2026-03-11T09:18:11Z  ActivityTaskScheduled          [reserve]
  18  2026-03-11T09:18:11Z  ActivityTaskStarted
  19  2026-03-11T09:18:11Z  ActivityTaskFailed             ← OutOfStock (nonRetryable)
  20  2026-03-11T09:18:11Z  WorkflowTaskScheduled
  21  2026-03-11T09:18:11Z  WorkflowTaskStarted
  22  2026-03-11T09:18:11Z  WorkflowTaskCompleted
  23  2026-03-11T09:18:11Z  UpsertWorkflowSearchAttributes [COMPENSATING]
  24  2026-03-11T09:18:11Z  ActivityTaskScheduled          [refund]  ← 보상
  25  2026-03-11T09:18:11Z  ActivityTaskStarted
  26  2026-03-11T09:18:11Z  ActivityTaskCompleted
  27  2026-03-11T09:18:11Z  WorkflowTaskScheduled
  28  2026-03-11T09:18:11Z  WorkflowTaskStarted
  29  2026-03-11T09:18:11Z  WorkflowTaskCompleted
  30  2026-03-11T09:18:11Z  UpsertWorkflowSearchAttributes [FAILED]
  31  2026-03-11T09:18:11Z  ActivityTaskScheduled          [notifyCustomer]
  32  2026-03-11T09:18:12Z  ActivityTaskStarted
  33  2026-03-11T09:18:12Z  ActivityTaskCompleted
  34  2026-03-11T09:18:12Z  WorkflowTaskScheduled
  35  2026-03-11T09:18:12Z  WorkflowTaskStarted
  36  2026-03-11T09:18:12Z  WorkflowTaskCompleted
  37  2026-03-11T09:18:12Z  WorkflowExecutionFailed

Result:
  Status: FAILED
  Failure: 주문 실패 order-2002: 재고 부족 sku=OUT-OF-STOCK qty=1
```

이벤트 19번(`ActivityTaskFailed`)에서 24번(보상 `refund`)으로 넘어가는 다섯 이벤트가 이 스텝의 핵심입니다. 상세를 봅니다.

```bash
temporal workflow show -w order-2002 --output json | jq '.events[18].activityTaskFailedEventAttributes.failure'
```

**결과**
```json
{
  "message": "Activity task failed",
  "cause": {
    "message": "재고 부족 sku=OUT-OF-STOCK qty=1",
    "applicationFailureInfo": {
      "type": "OutOfStock",
      "nonRetryable": true
    }
  },
  "activityType": { "name": "Reserve" },
  "retryState": "RETRY_STATE_NON_RETRYABLE_FAILURE"
}
```

`retryState: RETRY_STATE_NON_RETRYABLE_FAILURE` 가 "재시도 정책이 있었지만 타입 때문에 건너뛰었다"는 증거입니다.

### 시나리오 ③ 배송 중 취소 시그널 — `order-2003`

배송 액티비티가 진행되는 동안 취소 시그널을 보냅니다.

```bash
./gradlew run -PmainClass=com.example.order.Practice --args="start order-2003"
# 배송 요청이 나가는 순간
temporal workflow signal -w order-2003 --name cancelOrder --input '"고객 변심"'
```

**Worker 콘솔**
```
09:25:40.331 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - [order-2003] 주문 접수 customer=cust-42 sku=SKU-BLACK-TEE qty=2 amount=58000
[payment] charge orderId=order-2003 amount=58000 → pay-2003
09:25:40.612 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - [order-2003] 결제 완료 paymentId=pay-2003
[inventory] reserve sku=SKU-BLACK-TEE qty=2 → rsv-2003
09:25:40.901 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - [order-2003] 재고 확보 reservationId=rsv-2003
[shipping] requestShipment address=서울시 성동구 왕십리로 83 → shp-2003
09:25:41.208 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - 취소 시그널 수신: 고객 변심
09:25:41.214 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - [order-2003] 배송 요청 shipmentId=shp-2003
09:25:41.219 [workflow-method-order-2003-1] WARN  c.e.order.OrderWorkflowImpl - [order-2003] 취소 요청 — 보상 시작: 고객 변심
[shipping] cancelShipment shipmentId=shp-2003
[inventory] release reservationId=rsv-2003
[payment] refund paymentId=pay-2003
09:25:41.902 [workflow-method-order-2003-1] INFO  c.e.order.OrderWorkflowImpl - [order-2003] 보상 완료
[notification] orderId=order-2003 message=주문이 취소되었습니다: 고객 변심
```

보상 세 개가 **cancelShipment → release → refund** 순서로 나갔습니다. 등록 순서(refund → release → cancelShipment)의 정확한 역순입니다.

```bash
temporal workflow show -w order-2003
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:25:40Z  WorkflowExecutionStarted
   2  2026-03-11T09:25:40Z  WorkflowTaskScheduled
   3  2026-03-11T09:25:40Z  WorkflowTaskStarted
   4  2026-03-11T09:25:40Z  WorkflowTaskCompleted
   5  2026-03-11T09:25:40Z  UpsertWorkflowSearchAttributes
   6  2026-03-11T09:25:40Z  ActivityTaskScheduled          [charge]
   7  2026-03-11T09:25:40Z  WorkflowExecutionSignaled      [paymentConfirmed]
   8  2026-03-11T09:25:40Z  WorkflowTaskScheduled
   9  2026-03-11T09:25:40Z  ActivityTaskStarted
  10  2026-03-11T09:25:40Z  WorkflowTaskStarted
  11  2026-03-11T09:25:40Z  WorkflowTaskCompleted
  12  2026-03-11T09:25:40Z  ActivityTaskCompleted
  13  2026-03-11T09:25:40Z  WorkflowTaskScheduled
  14  2026-03-11T09:25:40Z  WorkflowTaskStarted
  15  2026-03-11T09:25:40Z  WorkflowTaskCompleted
  16  2026-03-11T09:25:40Z  UpsertWorkflowSearchAttributes [PAID]
  17  2026-03-11T09:25:40Z  ActivityTaskScheduled          [reserve]
  18  2026-03-11T09:25:40Z  ActivityTaskStarted
  19  2026-03-11T09:25:40Z  ActivityTaskCompleted
  20  2026-03-11T09:25:40Z  WorkflowTaskScheduled
  21  2026-03-11T09:25:40Z  WorkflowTaskStarted
  22  2026-03-11T09:25:40Z  WorkflowTaskCompleted
  23  2026-03-11T09:25:40Z  UpsertWorkflowSearchAttributes [RESERVED]
  24  2026-03-11T09:25:40Z  ActivityTaskScheduled          [requestShipment]
  25  2026-03-11T09:25:41Z  ActivityTaskStarted
  26  2026-03-11T09:25:41Z  WorkflowExecutionSignaled      [cancelOrder "고객 변심"]  ← 여기
  27  2026-03-11T09:25:41Z  ActivityTaskCompleted
  28  2026-03-11T09:25:41Z  WorkflowTaskScheduled
  29  2026-03-11T09:25:41Z  WorkflowTaskStarted
  30  2026-03-11T09:25:41Z  WorkflowTaskCompleted
  31  2026-03-11T09:25:41Z  UpsertWorkflowSearchAttributes [COMPENSATING]
  32  2026-03-11T09:25:41Z  ActivityTaskScheduled          [cancelShipment]  ← 보상 1
  33  2026-03-11T09:25:41Z  ActivityTaskStarted
  34  2026-03-11T09:25:41Z  ActivityTaskCompleted
  35  2026-03-11T09:25:41Z  WorkflowTaskScheduled
  36  2026-03-11T09:25:41Z  WorkflowTaskStarted
  37  2026-03-11T09:25:41Z  WorkflowTaskCompleted
  38  2026-03-11T09:25:41Z  ActivityTaskScheduled          [release]         ← 보상 2
  39  2026-03-11T09:25:41Z  ActivityTaskStarted
  40  2026-03-11T09:25:41Z  ActivityTaskCompleted
  41  2026-03-11T09:25:41Z  WorkflowTaskScheduled
  42  2026-03-11T09:25:41Z  WorkflowTaskStarted
  43  2026-03-11T09:25:41Z  WorkflowTaskCompleted
  44  2026-03-11T09:25:41Z  ActivityTaskScheduled          [refund]          ← 보상 3
  45  2026-03-11T09:25:41Z  ActivityTaskStarted
  46  2026-03-11T09:25:41Z  ActivityTaskCompleted
  47  2026-03-11T09:25:41Z  WorkflowTaskScheduled
  48  2026-03-11T09:25:41Z  WorkflowTaskStarted
  49  2026-03-11T09:25:41Z  WorkflowTaskCompleted
  50  2026-03-11T09:25:41Z  UpsertWorkflowSearchAttributes [CANCELLED]
  51  2026-03-11T09:25:41Z  ActivityTaskScheduled          [notifyCustomer]
  52  2026-03-11T09:25:41Z  ActivityTaskStarted
  53  2026-03-11T09:25:41Z  ActivityTaskCompleted
  54  2026-03-11T09:25:41Z  WorkflowTaskScheduled
  55  2026-03-11T09:25:41Z  WorkflowTaskStarted
  56  2026-03-11T09:25:41Z  WorkflowTaskCompleted
  57  2026-03-11T09:25:42Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-2003 CANCELLED"]
```

이벤트 26번(`WorkflowExecutionSignaled`)이 27번(`ActivityTaskCompleted`) **앞에** 있습니다. 배송 요청이 아직 진행 중일 때 시그널이 도착했다는 뜻입니다. 그래도 워크플로우는 배송 요청이 끝나기를 기다린 뒤 `checkCancelled()` 에서 취소를 처리합니다. 그래야 `shipmentId` 를 알고 `cancelShipment` 를 호출할 수 있습니다.

`Status: COMPLETED` 인 점도 확인하십시오. 고객 취소는 실패가 아닙니다.

### 시나리오 ④ 결제 확인 시그널 타임아웃 — `order-2004`

`paymentConfirmed` 시그널을 **보내지 않습니다.**

```bash
./gradlew run -PmainClass=com.example.order.Practice --args="start order-2004"
```

**Worker 콘솔** (30분 뒤)
```
09:31:02.117 [workflow-method-order-2004-1] INFO  c.e.order.OrderWorkflowImpl - [order-2004] 주문 접수 customer=cust-99 sku=SKU-BLACK-TEE qty=1 amount=29000
[payment] charge orderId=order-2004 amount=29000 → pay-2004
09:31:02.401 [workflow-method-order-2004-1] INFO  c.e.order.OrderWorkflowImpl - [order-2004] 결제 완료 paymentId=pay-2004
10:01:02.518 [workflow-method-order-2004-1] WARN  c.e.order.OrderWorkflowImpl - [order-2004] 결제 확인 시그널 30분 타임아웃 — 자동 취소
10:01:02.524 [workflow-method-order-2004-1] WARN  c.e.order.OrderWorkflowImpl - [order-2004] 취소 요청 — 보상 시작: PAYMENT_CONFIRM_TIMEOUT
[payment] refund paymentId=pay-2004
10:01:02.760 [workflow-method-order-2004-1] INFO  c.e.order.OrderWorkflowImpl - [order-2004] 보상 완료
[notification] orderId=order-2004 message=주문이 취소되었습니다: PAYMENT_CONFIRM_TIMEOUT
```

30분 동안 워커는 아무 일도 하지 않았습니다. 메모리도 스레드도 점유하지 않습니다. 타이머는 서버가 들고 있습니다.

```bash
temporal workflow show -w order-2004
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:31:02Z  WorkflowExecutionStarted
   2  2026-03-11T09:31:02Z  WorkflowTaskScheduled
   3  2026-03-11T09:31:02Z  WorkflowTaskStarted
   4  2026-03-11T09:31:02Z  WorkflowTaskCompleted
   5  2026-03-11T09:31:02Z  UpsertWorkflowSearchAttributes
   6  2026-03-11T09:31:02Z  ActivityTaskScheduled          [charge]
   7  2026-03-11T09:31:02Z  ActivityTaskStarted
   8  2026-03-11T09:31:02Z  ActivityTaskCompleted
   9  2026-03-11T09:31:02Z  WorkflowTaskScheduled
  10  2026-03-11T09:31:02Z  WorkflowTaskStarted
  11  2026-03-11T09:31:02Z  WorkflowTaskCompleted
  12  2026-03-11T09:31:02Z  UpsertWorkflowSearchAttributes [PAID]
  13  2026-03-11T09:31:02Z  TimerStarted                   [30m — 결제 확인 대기]
  14  2026-03-11T10:01:02Z  TimerFired                     ← 30분 뒤
  15  2026-03-11T10:01:02Z  WorkflowTaskScheduled
  16  2026-03-11T10:01:02Z  WorkflowTaskStarted
  17  2026-03-11T10:01:02Z  WorkflowTaskCompleted
  18  2026-03-11T10:01:02Z  UpsertWorkflowSearchAttributes [COMPENSATING]
  19  2026-03-11T10:01:02Z  ActivityTaskScheduled          [refund]
  20  2026-03-11T10:01:02Z  ActivityTaskStarted
  21  2026-03-11T10:01:02Z  ActivityTaskCompleted
  22  2026-03-11T10:01:02Z  WorkflowTaskScheduled
  23  2026-03-11T10:01:02Z  WorkflowTaskStarted
  24  2026-03-11T10:01:02Z  WorkflowTaskCompleted
  25  2026-03-11T10:01:02Z  UpsertWorkflowSearchAttributes [CANCELLED]
  26  2026-03-11T10:01:02Z  ActivityTaskScheduled          [notifyCustomer]
  27  2026-03-11T10:01:02Z  ActivityTaskStarted
  28  2026-03-11T10:01:02Z  ActivityTaskCompleted
  29  2026-03-11T10:01:02Z  WorkflowTaskScheduled
  30  2026-03-11T10:01:02Z  WorkflowTaskStarted
  31  2026-03-11T10:01:02Z  WorkflowTaskCompleted
  32  2026-03-11T10:01:03Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-2004 CANCELLED"]
```

이벤트 13·14의 `TimerStarted` / `TimerFired` 사이가 정확히 30분입니다. 그동안 히스토리에는 **이벤트가 하나도 추가되지 않았습니다.** 30분 대기의 비용이 이벤트 2개라는 뜻입니다.

기다리는 동안 상태를 조회해 봅니다(Query 는 히스토리를 늘리지 않습니다).

```bash
temporal workflow query -w order-2004 --type getState
```

**결과**
```json
{
  "orderId": "order-2004",
  "status": "PAID",
  "paymentId": "pay-2004",
  "reservationId": null,
  "shipmentId": null,
  "address": "서울시 마포구 월드컵북로 21",
  "failureReason": null
}
```

**네 시나리오 요약**

| 시나리오 | 히스토리 길이 | 최종 Status | 실행된 보상 | 소요 |
|---|---|---|---|---|
| ① order-2001 정상 | 38 | COMPLETED | 없음 | 1.1s |
| ② order-2002 재고 부족 | 37 | **FAILED** | refund | 0.8s |
| ③ order-2003 배송 중 취소 | 57 | COMPLETED (`CANCELLED`) | cancelShipment → release → refund | 1.6s |
| ④ order-2004 결제 타임아웃 | 32 | COMPLETED (`CANCELLED`) | refund | 30m 1s |

---

## 13-9. 테스트

네 시나리오를 그대로 JUnit 5 로 옮깁니다. `TestWorkflowExtension` 이 **시간을 스킵**하므로 시나리오 ④ 의 30분도 밀리초 만에 끝납니다.

```java
@RegisterExtension
static final TestWorkflowExtension ext = TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(OrderWorkflowImpl.class)
        .setDoNotStart(true)
        .build();

private PaymentActivity     payment;
private InventoryActivity   inventory;
private ShippingActivity    shipping;
private NotificationActivity notification;

@BeforeEach
void setUp(TestWorkflowEnvironment env, Worker worker) {
    payment      = mock(PaymentActivity.class);
    inventory    = mock(InventoryActivity.class);
    shipping     = mock(ShippingActivity.class);
    notification = mock(NotificationActivity.class);
    worker.registerActivitiesImplementations(payment, inventory, shipping, notification);
    when(payment.charge(any(), any(), anyLong())).thenReturn("pay-T");
    when(inventory.reserve(any(), any(), any(), anyInt())).thenReturn("rsv-T");
    when(shipping.requestShipment(any(), any(), any())).thenReturn("shp-T");
    env.start();
}
```

**테스트 ① 정상 완료**

```java
@Test
void 정상_완료(TestWorkflowEnvironment env, OrderWorkflow wf) {
    OrderRequest req = new OrderRequest("order-T1", "cust-42", "SKU-BLACK-TEE", 2, 58000, "서울시 성동구");
    WorkflowClient.start(wf::processOrder, req);
    wf.paymentConfirmed("tx-T1");

    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertEquals("order-T1 COMPLETED", result);
    assertEquals(OrderStatus.COMPLETED, wf.getState().status());
    verify(payment, never()).refund(any(), any());
}
```

**테스트 ② 재고 부족 시 환불만 실행**

```java
@Test
void 재고부족_보상(TestWorkflowEnvironment env, OrderWorkflow wf) {
    when(inventory.reserve(any(), any(), eq("OUT-OF-STOCK"), anyInt()))
            .thenThrow(ApplicationFailure.newNonRetryableFailure("재고 부족", "OutOfStock"));

    OrderRequest req = new OrderRequest("order-T2", "cust-77", "OUT-OF-STOCK", 1, 39000, "서울시 성동구");
    WorkflowClient.start(wf::processOrder, req);
    wf.paymentConfirmed("tx-T2");

    WorkflowFailedException e = assertThrows(WorkflowFailedException.class,
            () -> WorkflowStub.fromTyped(wf).getResult(String.class));
    assertTrue(e.getCause().getMessage().contains("재고 부족"));

    verify(payment).refund(eq("order-T2:refund"), eq("pay-T"));
    verify(inventory, never()).release(any(), any());     // 예약 자체가 없었으므로
    verify(shipping,  never()).cancelShipment(any(), any());
}
```

**테스트 ③ 보상 순서 검증 — `InOrder`**

```java
@Test
void 배송중_취소_보상순서(TestWorkflowEnvironment env, OrderWorkflow wf) {
    OrderRequest req = new OrderRequest("order-T3", "cust-42", "SKU-BLACK-TEE", 2, 58000, "서울시 성동구");
    WorkflowClient.start(wf::processOrder, req);
    wf.paymentConfirmed("tx-T3");
    wf.cancelOrder("고객 변심");

    assertEquals("order-T3 CANCELLED", WorkflowStub.fromTyped(wf).getResult(String.class));

    InOrder order = inOrder(shipping, inventory, payment);
    order.verify(shipping).cancelShipment(eq("order-T3:cancelShip"), eq("shp-T"));
    order.verify(inventory).release(eq("order-T3:release"), eq("rsv-T"));
    order.verify(payment).refund(eq("order-T3:refund"), eq("pay-T"));
}
```

`InOrder` 가 이 테스트의 전부입니다. 보상이 "세 개 다 실행됐는지"가 아니라 **"올바른 순서로 실행됐는지"** 를 봅니다. 환불이 배송 취소보다 먼저 나가면 배송사에는 여전히 요청이 살아 있는데 돈은 돌려준 상태가 됩니다.

**테스트 ④ 30분 타임아웃 — 시간 스킵**

```java
@Test
void 결제확인_타임아웃(TestWorkflowEnvironment env, OrderWorkflow wf) {
    OrderRequest req = new OrderRequest("order-T4", "cust-99", "SKU-BLACK-TEE", 1, 29000, "서울시 마포구");
    WorkflowClient.start(wf::processOrder, req);
    // paymentConfirmed 를 보내지 않는다

    env.sleep(Duration.ofMinutes(31));      // 실제로는 밀리초

    assertEquals("order-T4 CANCELLED", WorkflowStub.fromTyped(wf).getResult(String.class));
    verify(payment).refund(any(), eq("pay-T"));
    verify(inventory, never()).reserve(any(), any(), any(), anyInt());
}
```

**테스트 ⑤ 배송 후 취소 거절**

```java
@Test
void 배송후_취소는_거절(TestWorkflowEnvironment env, OrderWorkflow wf) {
    when(shipping.requestShipment(any(), any(), any())).thenAnswer(i -> {
        Thread.sleep(50);
        return "shp-T";
    });
    OrderRequest req = new OrderRequest("order-T5", "cust-42", "SKU-BLACK-TEE", 1, 29000, "서울시 성동구");
    WorkflowClient.start(wf::processOrder, req);
    wf.paymentConfirmed("tx-T5");
    WorkflowStub.fromTyped(wf).getResult(String.class);

    wf.cancelOrder("너무 늦은 취소");        // 종료 후 시그널 — 유실
    verify(shipping, never()).cancelShipment(any(), any());
}
```

**테스트 ⑥ 리플레이 테스트**

운영에서 받아 둔 히스토리 JSON 으로 현재 코드가 안전한지 검증합니다.

```java
@Test
void 리플레이_호환성() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
            "history/order-2003-cancelled.json", OrderWorkflowImpl.class);
}
```

히스토리 파일은 이렇게 받습니다.

```bash
temporal workflow show -w order-2003 --output json > src/test/resources/history/order-2003-cancelled.json
```

```bash
./gradlew test --tests 'com.example.order.OrderWorkflowTest'
```

**결과**
```
> Task :test

OrderWorkflowTest > 정상_완료() PASSED
OrderWorkflowTest > 재고부족_보상() PASSED
OrderWorkflowTest > 배송중_취소_보상순서() PASSED
OrderWorkflowTest > 결제확인_타임아웃() PASSED
OrderWorkflowTest > 배송후_취소는_거절() PASSED
OrderWorkflowTest > 리플레이_호환성() PASSED

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.8 s

BUILD SUCCESSFUL in 4s
```

시나리오 ④ 의 30분 대기가 포함돼 있는데도 전체가 **1.8초**입니다. 시간 스킵이 없었다면 30분 이상 걸렸을 테스트입니다.

이제 일부러 리플레이를 깨 봅니다. 결제와 재고 순서를 바꾸고 다시 돌립니다.

**결과**
```
OrderWorkflowTest > 리플레이_호환성() FAILED
    io.temporal.worker.NonDeterministicException:
      Command Activity(Reserve) doesn't match event ActivityTaskScheduled(Charge) with EventId=6

Tests run: 6, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.9 s
```

> 💡 **실무 팁 — 리플레이 테스트는 CI 의 필수 게이트**
> 운영에서 실행 중인 워크플로우의 히스토리 20~30건을 주기적으로 내려받아 `src/test/resources/history/` 에 커밋해 두고,
> 매 PR 마다 리플레이 테스트를 돌리십시오. 이 테스트가 없으면 "로컬에서는 잘 돌지만 배포하면 진행 중 주문이 전부 멈추는"
> 사고를 배포 후에야 알게 됩니다.

---

## 13-10. 운영 준비 체크리스트

배포 전에 15개를 확인합니다. 하나라도 비면 운영에서 조용히 터집니다.

| # | 항목 | 확인 방법 | 이 코스의 근거 |
|---|---|---|---|
| 1 | 모든 액티비티에 `StartToCloseTimeout` 이 있는가 | 코드 리뷰. 없으면 SDK 가 실행을 거부 | Step 04 |
| 2 | `ScheduleToCloseTimeout` 으로 총 상한을 걸었는가 | 무한 대기 방지 | Step 04 |
| 3 | 정방향 액티비티에 `maximumAttempts` 상한이 있는가 | 무한 재시도로 히스토리가 부푸는 것 방지 | Step 05 |
| 4 | **보상 액티비티는 상한이 없는가** | `setMaximumAttempts` 호출이 없어야 정상 | 13-4 |
| 5 | 재시도 무의미한 실패를 `doNotRetry` 에 등록했는가 | 타입 문자열 오타 확인 | Step 05 |
| 6 | 모든 액티비티가 멱등한가 (**보상 포함**) | 같은 멱등키로 두 번 호출해 부수효과 확인 | 13-3 |
| 7 | 보상이 `newDetachedCancellationScope` 안에서 도는가 | `workflow cancel` 을 실제로 실행해 검증 | 13-6 |
| 8 | 알림 등 부수 액티비티의 실패가 격리돼 있는가 | 알림 mock 을 실패시키고 주문이 COMPLETED 인지 | 13-6 |
| 9 | 리플레이 테스트가 CI 에 있는가 | 운영 히스토리 20건 이상을 리소스로 커밋 | Step 11 |
| 10 | Search Attribute 를 서버에 등록했는가 | `temporal operator search-attribute list` | Step 12 |
| 11 | Retention 이 히스토리 보존 요구를 만족하는가 | 기본 72h. 감사 요건이 있으면 늘릴 것 | Step 12 |
| 12 | Worker 슬롯을 액티비티 부하에 맞게 잡았는가 | `setMaxConcurrentActivityExecutionSize` | Step 12 |
| 13 | 히스토리 크기를 추정했는가 | 주문 1건 = 38 이벤트 / 6.2KB. 일 10만 건이면 620MB/일 | 13-8 |
| 14 | 메트릭 대시보드가 있는가 | `workflow_failed`, `activity_schedule_to_start_latency`, `sticky_cache_miss` | Step 12 |
| 15 | 버저닝 마커를 제거할 조건을 문서화했는가 | 구버전 워크플로우가 0 이 될 때까지 삭제 금지 | Step 10 |

Search Attribute 등록은 배포 전에 반드시 해 둡니다. 안 하면 `upsert` 가 런타임에 실패합니다.

```bash
temporal operator search-attribute create --name OrderStatus --type Keyword
temporal operator search-attribute create --name CustomerId --type Keyword
temporal operator search-attribute list
```

**결과**
```
Name              Type
CustomerId        Keyword
OrderStatus       Keyword
BuildIds          KeywordList
ExecutionStatus   Keyword
WorkflowId        Keyword
WorkflowType      Keyword
```

히스토리 크기 추정입니다.

```bash
temporal workflow describe -w order-2001 --output json | jq '.workflowExecutionInfo.historySizeBytes'
```

**결과**
```
6184
```

주문 1건당 6.2KB. 일 10만 건이면 620MB/일, Retention 72시간이면 **약 1.9GB** 가 상시 유지됩니다. 여기에 시나리오 ③ 처럼 취소가 섞이면 건당 9~10KB 로 올라갑니다. 이 계산을 안 해 두면 어느 날 PostgreSQL 디스크가 찹니다.

> 💡 **실무 팁 — 워커를 배포하기 전 반드시 한 번은 `workflow cancel` 을 눌러 보십시오**
> 체크리스트 7번은 코드 리뷰만으로 잡히지 않습니다. detached scope 를 빠뜨려도 컴파일되고, 단위 테스트도 통과하고,
> 정상 경로와 실패 경로도 멀쩡합니다. **외부 취소를 실제로 걸었을 때만** 보상이 통째로 건너뛰어집니다.
> 스테이징에서 주문 하나를 `temporal workflow cancel` 로 죽여 보고, 보상 액티비티 세 개가 히스토리에 남는지 눈으로 확인하십시오.

---

## 13-11. 더 나아가기

이 코스에서 다루지 않았지만 실무에서 곧 만나게 될 것들입니다.

**Schedules (cron)** — `@CronSchedule` 대신 서버 사이드 Schedule 을 쓰십시오. 일시정지·백필·중복 실행 정책을 CLI 로 다룰 수 있습니다.

```bash
temporal schedule create --schedule-id daily-settlement \
  --cron '0 3 * * *' --workflow-id settlement --task-queue ORDER_TASK_QUEUE \
  --type SettlementWorkflow --overlap-policy Skip
```

**결과**
```
Schedule created. ScheduleId: daily-settlement
  NextRunTime: 2026-03-12 03:00:00 +0000 UTC
```

**Nexus** — Temporal 1.22 부터 프리뷰로 들어온, 네임스페이스·팀 경계를 넘어 워크플로우를 호출하는 방식입니다. 지금까지는 다른 팀의 서비스를 부르려면 액티비티로 HTTP 를 때려야 했는데, Nexus 는 그 경계를 Temporal 이 관리하는 계약으로 만듭니다.

**Temporal Cloud** — 서버 운영(Cassandra/Elasticsearch 튜닝, 샤드 관리)을 넘길 수 있습니다. 워커는 여전히 내가 돌립니다. 마이그레이션은 엔드포인트와 mTLS 인증서만 바꾸면 되므로, 코드 변경은 거의 없습니다.

**Spring Boot 통합** — `io.temporal:temporal-spring-boot-starter-alpha:1.22.3` 을 쓰면 워커 등록이 설정으로 내려갑니다.

```groovy
implementation 'io.temporal:temporal-spring-boot-starter-alpha:1.22.3'
```

```yaml
spring:
  temporal:
    connection:
      target: 127.0.0.1:7233
    namespace: default
    workers:
      - task-queue: ORDER_TASK_QUEUE
        name: order-worker
    workers-auto-discovery:
      packages: com.example.order
```

`@WorkflowImpl(taskQueues = "ORDER_TASK_QUEUE")` / `@ActivityImpl(taskQueues = "ORDER_TASK_QUEUE")` 를 붙이면 자동 등록됩니다. 액티비티에 `@Autowired` 로 리포지토리를 주입할 수 있다는 게 가장 큰 이득입니다.

**다국어 Worker** — 워크플로우는 Java, 액티비티는 Python 이나 Go 로 짜도 됩니다. 같은 Task Queue 를 바라보면 됩니다. ML 추론 액티비티만 Python 워커로 분리하는 식의 구성이 흔합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 정상 경로 | 결제 → 재고 → 배송 → 알림. 각 단계 성공 **직후** 보상 등록 |
| 보상 경로 | Saga 가 등록의 **역순**으로 실행. cancelShipment → release → refund |
| 멱등키 | 모든 액티비티 첫 인자. `orderId:연산명` — 리플레이해도 같은 값 |
| ActivityOptions | 액티비티마다 다르게. 결제는 짧고 3회, 배송은 길고 10회 |
| 보상 재시도 | **상한 없음.** `setMaximumAttempts` 를 부르지 않는 것이 정답 |
| `doNotRetry` | 재시도가 무의미한 타입만. 문자열 오타는 조용히 재시도로 이어짐 |
| Signal | 플래그만 세우고 즉시 리턴. 예외를 던지면 Workflow Task 가 무한 실패 |
| Query | 히스토리에 안 남음. 불변 스냅샷을 복사해서 반환 |
| Update | 거절을 호출자에게 알려야 할 때. Validator 는 순수 검증만 |
| `Workflow.await(Duration, ...)` | **반환값으로** 조건 충족과 타임아웃을 구분 |
| detached scope | 보상은 반드시 이 안에서. 없으면 외부 취소 시 보상이 통째로 건너뛰어짐 |
| 알림 격리 | 부수 액티비티의 실패가 주문을 실패시키면 안 됨. try/catch 로 삼킴 |
| `getVersion` | 진행 중 주문 보호. 구버전이 0 이 될 때까지 분기 삭제 금지 |
| Search Attribute | 상태 전이마다 upsert → `--query 'OrderStatus="COMPENSATING"'` 로 조회 |
| 테스트 | 시간 스킵으로 30분을 밀리초에. `InOrder` 로 보상 **순서** 검증 |
| 리플레이 테스트 | CI 필수. 운영 히스토리를 리소스로 커밋해 두고 매 PR 마다 실행 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. 각 문제는 본문에서 다룬 함정과 1:1로 대응합니다.

1. 보상 전용 `ActivityOptions` 를 만드십시오 — 재시도 상한을 두지 않는 방법이 핵심입니다
2. 결제 `ActivityOptions` 를 만드십시오 — `doNotRetry` 로 무의미한 재시도를 막습니다
3. 알림 호출을 "실패해도 주문을 망치지 않게" 감싸십시오
4. Saga 보상 등록 순서의 버그 두 개를 바로잡으십시오
5. 취소 이후에도 보상이 반드시 실행되도록 감싸십시오
6. 배송 이후 취소를 거절하는 시그널 핸들러를 작성하십시오 — 예외를 던지면 안 됩니다

보너스: Query 핸들러의 액티비티 호출 / Validator 의 상태 변경 / 종료 후 시그널 유실 중 참인 것을 고르십시오.

---

## 다음 단계

코스를 마칩니다.

13개 스텝을 지나오며 한 가지가 반복해서 나왔습니다. **문법 에러는 금방 고치지만, 에러 없이 조용히 잘못 동작하는 코드가 진짜 위험하다**는 것입니다. Worker 를 안 띄운 채 매달린 워크플로우(Step 01), 리플레이 때만 어긋나는 `new Random()`(Step 03), 세 번 만에 포기하는 보상(Step 13) — 전부 로그에 아무것도 안 남습니다. Temporal 이 실행을 대신 책임져 주는 만큼, 남은 실수는 더 조용해집니다.

그래서 이 코스가 끝까지 붙들고 온 도구가 **이벤트 히스토리**였습니다. `temporal workflow show` 한 줄이면 워크플로우가 무엇을 했고 무엇을 안 했는지 전부 나옵니다. 무언가 이상하면 코드를 노려보기 전에 히스토리를 여십시오. 답은 거기 있습니다.

다음으로 할 일을 권한다면 세 가지입니다. ① 이 프로젝트의 리플레이 테스트를 실제 CI 에 붙일 것, ② 담당 서비스에서 "재시도와 보상이 사람 손으로 관리되고 있는 흐름" 하나를 골라 워크플로우로 옮겨 볼 것, ③ 13-11 의 Schedules 와 Spring Boot 통합을 실제 프로젝트 구조에 맞게 적용해 볼 것입니다.

→ [Temporal Workflow 완전 학습 코스 — 개요](../)

---

## 실습 파일

이 스텝은 앞선 12개 스텝의 결과물을 하나로 합친 것이라 파일 구성도 조금 다릅니다. `Practice.java` 는 발췌가 아니라 **그대로 돌아가는 최종 프로젝트 전체**이고, `Exercise.java` 는 그 안에서 실수하기 쉬운 여섯 지점만 도려낸 문제지입니다. 먼저 `Practice.java` 로 네 시나리오를 전부 재현한 뒤, `Exercise.java` 를 채우고, `Solution.java` 로 대조하십시오.

### Practice.java

본문 13-2 ~ 13-8 의 모든 코드를 절 번호 주석과 함께 담은 최종 프로젝트입니다. 실제 서비스라면 아래 nested type 들을 각각 별도 파일로 분리해야 합니다.

- 워커와 스타터가 같은 `main` 에 들어 있습니다. 인자 없이 실행하면 워커가 뜨고, `--args="start order-2001"` 로 실행하면 주문을 개시합니다. **터미널 두 개**가 필요합니다.
- `startOrder` 가 `orderId` 로 시나리오를 분기합니다. `order-2002` 는 `sku` 를 `OUT-OF-STOCK` 으로, `order-2004` 는 `paymentConfirmed` 시그널을 **보내지 않는** 입력을 만듭니다. 시나리오 ③(배송 중 취소)만 직접 `temporal workflow signal` 을 쳐야 합니다.
- `[13-4]` 구간의 `COMPENSATION_OPTS` 에 `setMaximumAttempts` 호출이 **없다는 것**을 확인하십시오. 여기에 `.setMaximumAttempts(3)` 을 넣고 다시 돌려 보면, 결제사 장애를 흉내 냈을 때 워크플로우가 환불 없이 끝나는 것을 재현할 수 있습니다.
- `IDEMPOTENCY_STORE` 는 `static ConcurrentHashMap` 이라 **워커를 재시작하면 초기화됩니다.** 같은 `orderId` 로 두 번 실행할 때 "멱등 히트" 로그를 보고 싶다면 워커를 그대로 둔 채 재실행하십시오.
- `compensate()` 의 `Workflow.newDetachedCancellationScope(...)` 를 지우고 `saga.compensate()` 만 남긴 뒤 `temporal workflow cancel -w order-2003` 을 실행해 보십시오. 보상 액티비티가 히스토리에 **한 개도 안 나타나는** 것이 13-6 의 가장 위험한 함정입니다.
- `notifyQuietly` 의 try/catch 를 지우고 `NotificationActivityImpl` 이 예외를 던지도록 고치면, 결제·재고·배송이 전부 성공한 주문이 보상 세 개를 돌리며 실패하는 것을 볼 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

본문에서 다룬 함정 여섯 개를 문제로 바꾼 문제지입니다. `Practice.java` 의 타입을 그대로 재사용하므로 같은 패키지에 두십시오.

- **문제 1·2** 는 `ActivityOptions` 설계입니다. 정답 코드 자체는 짧지만, 문제 1 의 핵심은 "무엇을 쓰느냐"가 아니라 **"무엇을 쓰지 않느냐"** 입니다.
- **문제 3·5** 는 각각 알림 격리와 detached scope — 13-6 의 두 함정에 정확히 대응합니다. 둘 다 빼먹어도 컴파일되고 단위 테스트도 통과한다는 점이 문제의 포인트입니다.
- **문제 4** 는 주석에 **잘못된 원본 코드**가 그대로 실려 있습니다. 버그가 두 개라고 명시돼 있으니 둘 다 찾으십시오. 하나는 등록 시점, 하나는 캡처 대상입니다.
- **문제 6** 의 `CancelHandler` 는 워크플로우 밖에서도 테스트할 수 있도록 상태를 public 필드로 노출한 축약형입니다. 실제 구현에서는 `OrderWorkflowImpl` 의 private 필드입니다.
- 보너스 문제는 코드를 쓰지 않고 주석으로 답합니다. 세 문장 중 참인 것은 하나뿐입니다.

```java file="./Exercise.java"
```

### Solution.java

여섯 문제의 정답과, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여십시오.

- **정답 1** 의 해설이 이 스텝 전체에서 가장 중요합니다. `RetryOptions.maximumAttempts` 의 기본값 `0` 이 **무제한**이라는 사실과, 습관적으로 `3` 을 넣었을 때 "결제는 됐는데 환불은 안 된" 상태가 **정상 종료로 기록되는** 과정을 단계별로 설명합니다.
- **정답 3** 은 알림을 삼키는 코드를 보여준 뒤, 한 걸음 더 나아가 "알림이 정말 중요하다면 삼키지 말고 별도 워크플로우로 분리하라"는 설계 지침으로 이어집니다. 주문의 성패와 알림의 성패를 같은 트랜잭션에 묶지 않는 것이 요점입니다.
- **정답 4** 는 잘못된 코드가 왜 **무한 재시도로 24시간을 태우는지** 를 추적합니다. `refund(null)` → 결제사 "그런 결제 없음" → 보상 무한 재시도 → ScheduleToClose 24시간 만료의 연쇄입니다.
- **정답 6** 은 "시그널 핸들러에서 예외를 던지면 왜 안 되는가"에 지면을 많이 씁니다. 예외가 클라이언트에 가지 않고 Workflow Task 를 실패시키며, Temporal 이 그것을 무한 재시도하므로 워크플로우가 "Running" 인 채로 영원히 멈춘다는 것이 결론입니다. 거절을 알려야 하면 Update 를 쓰라는 대안도 함께 제시합니다.
- **보너스 정답**은 (c) 하나뿐이며, (a)·(b) 가 왜 거짓인지를 리플레이 관점에서 설명합니다. 종료 직전의 `Workflow.isEveryHandlerFinished()` 대기 패턴(SDK 1.22+)도 여기서 소개합니다.

```java file="./Solution.java"
```
