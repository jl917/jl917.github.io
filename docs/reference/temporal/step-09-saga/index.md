# Step 09 — Saga 보상 트랜잭션

> **학습 목표**
> - 서비스마다 DB 가 따로인 환경에서 `BEGIN ... COMMIT` 이 왜 불가능한지, 2PC 가 왜 답이 아닌지 설명한다
> - Saga 패턴의 Choreography / Orchestration 을 비교하고, Temporal 워크플로우가 곧 조정자임을 이해한다
> - `io.temporal.workflow.Saga` 로 보상 액션을 등록하고 `saga.compensate()` 로 역순 실행되는 것을 **히스토리로 확인한다**
> - 보상 등록 시점(액티비티 호출 전/후)의 경계 케이스를 타임라인으로 분석하고, **멱등한 보상**이 유일한 해답임을 확인한다
> - 보상 액티비티 자체가 실패할 때의 세 가지 전략을 비교하고 `Saga.CompensationException` 을 다룬다
> - 배송 단계에서 실패하는 주문을 실제로 실행해 `temporal workflow show -w order-1004` 로 보상 순서를 눈으로 검증한다
>
> **선행 스텝**: Step 08 — 자식 워크플로우와 Continue-As-New
> **예상 소요**: 90분

---

## 9-0. 실습 준비

Step 04 에서 만든 액티비티 4종을 그대로 씁니다. 이번 스텝에서는 각 액티비티의 **취소/환불 짝**을 본격적으로 사용합니다.

| 정방향 액티비티 | 보상 액티비티 | 반환값 |
|---|---|---|
| `PaymentActivity.charge(orderId, amount)` | `PaymentActivity.refund(paymentId)` | `paymentId` |
| `InventoryActivity.reserve(orderId, sku, qty)` | `InventoryActivity.release(reservationId)` | `reservationId` |
| `ShippingActivity.requestShipment(orderId, address)` | `ShippingActivity.cancelShipment(shipmentId)` | `shipmentId` |

Worker 와 Temporal Server 가 떠 있는지 먼저 확인합니다.

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Workflow Poller Options:
  Identity                          92311@mac-julong
  Last Access Time                  2026-03-18T04:21:07Z
Activity Poller Options:
  Identity                          92311@mac-julong
  Last Access Time                  2026-03-18T04:21:07Z
```

Worker 가 두 종류(Workflow / Activity) 폴러로 붙어 있으면 준비 완료입니다.

---

## 9-1. 분산 트랜잭션이 왜 어려운가

모놀리스에서는 결제·재고·배송이 **같은 DB** 에 있었습니다. `BEGIN; INSERT INTO payments ...; UPDATE inventory ...; INSERT INTO shipments ...; COMMIT;` — 셋 중 하나라도 실패하면 전부 없던 일이 됩니다. DB 하나가 원자성을 보장해 주었습니다.

그런데 서비스를 쪼개면 이 그림이 무너집니다.

```
            [ 주문 서비스 ]
                  │
      ┌───────────┼───────────┐
      ▼           ▼           ▼
 [결제 서비스] [재고 서비스] [배송 서비스]
      │           │           │
  ┌───┴───┐   ┌───┴───┐   ┌───┴───┐
  │ pay_db│   │ inv_db│   │ ship_ │      ← DB 가 각각. 심지어 결제는 외부 PG API
  └───────┘   └───────┘   └───────┘
```

`pay_db` 의 트랜잭션은 `inv_db` 를 모릅니다. 결제 서비스가 COMMIT 한 뒤에 재고 서비스가 실패해도, **결제를 되돌릴 방법이 프로토콜 차원에 없습니다.** 게다가 결제는 대개 외부 PG(토스페이먼츠, 나이스페이 등)의 HTTP API 라서 애초에 트랜잭션이라는 개념 자체가 없습니다.

문제를 그림으로 보면 이렇습니다.

```
 시간 →

  ① 결제 charge()        ✅ 성공  →  paymentId = pay_88f1  (PG 에 39,000원 승인 완료)
  ② 재고 reserve()       ✅ 성공  →  reservationId = rsv_2c9  (inv_db 에 1개 홀드)
  ③ 배송 requestShipment() ❌ 실패  →  "배송 불가 지역" 예외

                              ↓

  이제 ①과 ②를 어떻게 되돌리나?
  - ① 은 이미 고객 카드에서 돈이 빠졌다. ROLLBACK 할 대상이 없다.
  - ② 는 inv_db 에서 홀드가 걸린 채 방치되면 그 재고는 영원히 팔리지 않는다.
```

### 2PC 는 왜 답이 아닌가

교과서적 해법은 **2단계 커밋(Two-Phase Commit)** 입니다. 코디네이터가 모든 참여자에게 "준비됐나(prepare)" 를 묻고, 전원이 yes 면 "커밋해라(commit)" 를 보냅니다.

| 2PC 의 문제 | 내용 |
|---|---|
| **코디네이터 SPOF** | 코디네이터가 prepare 와 commit 사이에 죽으면, 참여자들은 락을 쥔 채 무한정 대기합니다(in-doubt 상태). |
| **락 유지 시간** | prepare 부터 commit 까지 각 참여자가 락을 잡고 있습니다. 결제 API 가 3초 걸리면 재고 행 락도 3초입니다. 처리량이 무너집니다. |
| **참여자 지원 필요** | 모든 참여자가 XA 프로토콜을 구현해야 합니다. 외부 PG 사, S3, 메일 발송 API 는 XA 를 지원하지 않습니다. |
| **가용성 저하** | 참여자 N 개 중 하나만 느려도 전체가 그 속도에 묶입니다. 가용성이 각 서비스 가용성의 **곱**이 됩니다. |

즉 2PC 는 "락을 오래 잡는 대가로 강한 일관성"을 사는 방식인데, 마이크로서비스 환경에서는 지불할 수 없는 대가입니다.

> 💡 **비유 — 결혼식 주례**
> 2PC 는 주례가 "신랑 준비됐습니까? 신부 준비됐습니까?" 를 다 물은 뒤 "성혼합니다"를 선언하는 방식입니다. 주례가 질문 직후 쓰러지면 두 사람은 손을 든 채 굳어 버립니다.
> Saga 는 "일단 각자 진행하고, 틀어지면 되돌린다"는 방식입니다. 되돌리는 절차를 미리 정해 두는 것이 핵심입니다.

---

## 9-2. Saga 패턴 — 되돌리기를 미리 짝지어 둔다

Saga 는 긴 트랜잭션을 **여러 개의 로컬 트랜잭션**으로 쪼개고, 각 로컬 트랜잭션마다 그것을 되돌리는 **보상 액션(compensating action)** 을 짝지어 두는 패턴입니다.

```
  정방향:  T1 ──→ T2 ──→ T3 ──→ T4
           결제   재고   배송   알림

  T3 에서 실패하면?

  보상:            C2 ←── C1
                재고해제  결제환불          ← 성공한 것만, 역순으로
```

핵심은 두 가지입니다.

1. **ROLLBACK 이 아니라 보정(semantic compensation) 입니다.** `refund()` 는 결제 기록을 지우는 게 아니라 **환불이라는 새로운 트랜잭션을 추가**합니다. 원장에는 승인 39,000원과 환불 -39,000원이 둘 다 남습니다.
2. **중간 상태가 외부에 노출됩니다.** T1 과 T3 사이에 고객이 결제 내역을 조회하면 "결제 완료"가 보입니다. 잠깐 뒤에 환불됩니다. 이것을 감수하는 대신 락을 없앤 것입니다(격리성 포기).

### Choreography vs Orchestration

Saga 를 구현하는 방식은 크게 둘입니다.

| 항목 | Choreography (안무) | Orchestration (지휘) |
|---|---|---|
| 방식 | 각 서비스가 이벤트를 발행/구독해 다음 단계를 스스로 촉발 | 중앙 조정자가 각 서비스를 순서대로 호출 |
| 흐름 파악 | 코드가 여러 서비스에 흩어져 **전체 흐름을 볼 수 없다** | 조정자 코드 한 곳에 흐름이 다 있다 |
| 보상 로직 | 각 서비스가 보상 이벤트를 구독해 처리. 순서 보장이 어렵다 | 조정자가 역순으로 호출. 순서가 명확하다 |
| 결합도 | 낮음(서비스 간 직접 호출 없음) | 조정자가 모든 서비스를 안다 |
| 디버깅 | Kafka 토픽을 뒤져 가며 추적 | 워크플로우 히스토리 하나만 보면 됨 |
| 순환 의존 | 이벤트가 이벤트를 낳아 무한 루프 위험 | 없음 |

**Temporal 은 Orchestration 입니다.** 그런데 보통의 Orchestration 구현(별도 Saga 오케스트레이터 서비스 + 상태 테이블)과 다른 점이 있습니다.

> **워크플로우 코드 자체가 조정자입니다.**
> 상태 머신을 표로 정의하거나 JSON DSL 을 짤 필요가 없습니다. `try { ... } catch { ... }` 라는 평범한 Java 제어 흐름이 그대로 Saga 조정자가 됩니다. 조정자의 상태(어디까지 성공했는지)는 Temporal 히스토리가 대신 기억하므로, 조정자용 DB 테이블도 필요 없습니다.

---

## 9-3. Temporal 의 `Saga` 클래스

Temporal Java SDK 는 `io.temporal.workflow.Saga` 를 제공합니다. 보상 액션을 스택에 쌓아 두었다가 한 번에 역순 실행해 주는 아주 작은 유틸리티입니다.

```java
package com.example.order;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class OrderWorkflowImpl implements OrderWorkflow {

    private static final ActivityOptions OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10)).build();

    private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, OPTS);
    private final InventoryActivity inventory = Workflow.newActivityStub(InventoryActivity.class, OPTS);
    private final ShippingActivity shipping = Workflow.newActivityStub(ShippingActivity.class, OPTS);
    private final NotificationActivity notification = Workflow.newActivityStub(NotificationActivity.class, OPTS);

    @Override
    public String processOrder(OrderRequest req) {
        Saga.Options sagaOptions = new Saga.Options.Builder()
                .setParallelCompensation(false)   // 역순 직렬 보상 (기본값)
                .setContinueWithError(false)      // 보상 중 예외 나면 즉시 중단 (기본값)
                .build();
        Saga saga = new Saga(sagaOptions);

        try {
            // ① 결제
            String paymentId = payment.charge(req.orderId(), req.amount());
            saga.addCompensation(payment::refund, paymentId);

            // ② 재고
            String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
            saga.addCompensation(inventory::release, reservationId);

            // ③ 배송
            String shipmentId = shipping.requestShipment(req.orderId(), req.address());
            saga.addCompensation(shipping::cancelShipment, shipmentId);

            notification.notifyCustomer(req.orderId(), "주문이 완료되었습니다");
            return req.orderId() + " COMPLETED";

        } catch (Exception e) {
            Workflow.getLogger(OrderWorkflowImpl.class)
                    .error("[{}] 실패 — 보상 시작", req.orderId(), e);
            saga.compensate();   // 등록의 역순으로 실행
            throw e;             // ← 이 줄이 없으면 워크플로우가 성공으로 끝난다 (9-8 함정)
        }
    }
}
```

`addCompensation` 은 `Functions.Proc` ~ `Proc6` 를 받는 오버로드 7개로 되어 있어, `saga.addCompensation(payment::refund, paymentId)` 처럼 **메서드 참조 + 인자**로 쓸 수 있습니다. 이때 `payment` 는 액티비티 **스텁**이므로, 나중에 호출되면 그 시점에 액티비티가 정상적으로 스케줄됩니다.

| `Saga.Options` | 기본값 | 의미 |
|---|---|---|
| `setParallelCompensation(boolean)` | `false` | `true` 면 모든 보상을 동시에 시작하고 전부 끝날 때까지 대기. 순서 보장이 사라짐 |
| `setContinueWithError(boolean)` | `false` | `true` 면 보상 하나가 실패해도 나머지 보상을 계속 시도 (직렬 모드에서만 유효) |

> 💡 **실무 팁 — 람다 대신 메서드 참조를 쓸 것**
> `saga.addCompensation(() -> payment.refund(paymentId))` 도 동작하지만, 람다가 캡처하는 변수는 워크플로우 인스턴스 필드가 아니라 **지역 변수의 복사본**입니다. 워크플로우 코드에서는 문제되지 않지만, 메서드 참조 + 인자 형태가 "무엇을 어떤 값으로 보상하는가"를 명시적으로 드러내므로 히스토리와 대조하기 쉽습니다.

---

## 9-4. 보상 등록 시점이 결정적으로 중요하다

여기가 이 스텝에서 가장 조용히 틀리기 쉬운 부분입니다. 질문은 단순합니다.

> `saga.addCompensation(...)` 을 액티비티 호출 **전**에 써야 하나, **후**에 써야 하나?

### 후에 등록해야 하는 이유

보상에는 대상 식별자가 필요합니다. `refund(paymentId)` 의 `paymentId` 는 `charge()` 가 성공적으로 반환해야만 알 수 있습니다. 그러므로 문법적으로 **호출 후에 등록할 수밖에 없습니다.**

```java
String paymentId = payment.charge(req.orderId(), req.amount());  // 여기서 paymentId 획득
saga.addCompensation(payment::refund, paymentId);                // 그다음에 등록
```

### 그런데 여기에 구멍이 있습니다

`charge()` 가 `ActivityFailure` 로 실패하면 등록 줄에 도달하지 못합니다. 대부분의 경우 이는 옳습니다 — 결제가 안 됐으니 환불할 것도 없습니다. 문제는 **실패했는데 실제로는 성공한 경우**입니다.

액티비티는 **at-least-once** 로 실행됩니다. Temporal 은 "액티비티가 반드시 한 번 이상 실행됨"은 보장하지만 "정확히 한 번"은 보장하지 않습니다. 타임아웃과 재시도가 결합되면 다음 타임라인이 나옵니다.

```
 t=0.0s   워크플로우 → ActivityTaskScheduled (charge)
 t=0.1s   Worker 가 태스크 수령 → ActivityTaskStarted
 t=0.2s   Activity 구현체가 PG API 호출 시작
          │
 t=10.0s  StartToCloseTimeout(10s) 만료
          → 서버가 ActivityTaskTimedOut 기록, 재시도 스케줄
          │                                    ⚠️ 하지만 Activity 워커 스레드는 아직 살아 있다
 t=10.4s  PG 가 늦게 200 OK 응답 → 카드 승인 실제로 완료됨 (pay_88f1)
          → Activity 가 complete 를 보내지만 서버는 "이미 타임아웃된 태스크"라며 거부
          │
 t=10.5s  재시도 attempt=2 로 charge 재실행
 t=12.0s  PG 가 중복 요청 감지해 409 CONFLICT → attempt=3 ...
 t=45.0s  MaximumAttempts 소진 → ActivityFailure 로 워크플로우에 전파

          결과: 워크플로우 입장에서 charge 는 "실패".
                하지만 PG 에는 pay_88f1 승인 건이 남아 있다.
                addCompensation 은 실행되지 않았으므로 환불도 안 된다.
                → 고객 돈이 묶인다.
```

### 그럼 전에 등록하면 되지 않나?

```java
saga.addCompensation(payment::refundByOrderId, req.orderId());  // 호출 전에 등록
String paymentId = payment.charge(req.orderId(), req.amount());
```

이러면 위 케이스는 잡힙니다. 대신 **정말로 결제가 한 번도 성공하지 않은 경우에도 환불을 시도**하게 됩니다. PG 에 "존재하지 않는 결제 건 환불" 요청이 날아가고, PG 는 404 를 반환하며, 보상 액티비티가 실패하고, 그것이 다시 워크플로우를 막습니다.

### 결론 — 멱등한 보상

두 선택지 모두 단독으로는 완결되지 않습니다. 실제 해답은 **보상 액티비티를 멱등하게 만드는 것**입니다.

> ⚠️ **함정 — 보상은 "대상이 없어도 성공"해야 하고, "두 번 불려도 한 번만 효력"이 있어야 한다**
> 보상 액티비티가 지켜야 할 두 가지 규칙입니다.
> 1. **대상이 없으면 조용히 성공한다.** 환불할 결제 건이 없다? 그건 이미 원하는 상태(돈이 안 빠진 상태)이므로 `return` 하면 됩니다. 예외를 던지면 안 됩니다.
> 2. **이미 처리됐으면 조용히 성공한다.** 이미 환불된 건에 환불 요청? 최종 상태가 같으므로 성공입니다.
>
> 이 두 규칙을 지키면 등록 시점 논쟁이 무의미해집니다. "호출 후 등록 + 멱등 보상 + 별도의 미아 정리 잡" 이 실무 표준 조합입니다.

멱등한 `refund` 구현은 이렇게 생겼습니다.

```java
@Override
public void refund(String paymentId) {
    PgPayment found = pgClient.findByPaymentId(paymentId);

    if (found == null) {
        // 승인된 게 없다 = 되돌릴 것도 없다. 이것은 정상이다.
        log.info("[refund] paymentId={} 승인 건 없음 — 보상 불필요", paymentId);
        return;
    }
    if (found.status() == PgStatus.REFUNDED) {
        // 이미 환불됨. 무한 재시도로 두 번째 호출된 경우.
        log.info("[refund] paymentId={} 이미 환불됨 — 스킵", paymentId);
        return;
    }
    pgClient.refund(paymentId);
    log.info("[refund] paymentId={} 환불 완료", paymentId);
}
```

`charge` 쪽도 마찬가지로 멱등해야 합니다. `orderId` 를 **멱등 키(idempotency key)** 로 PG 에 넘기면, 재시도 시 PG 가 "이미 승인된 건"이라며 같은 `paymentId` 를 돌려줍니다. 그러면 위 타임라인의 attempt=2 가 409 가 아니라 성공으로 끝나고, 문제 자체가 사라집니다.

> 💡 **실무 팁 — 멱등 키는 `Activity.getExecutionContext().getInfo().getWorkflowId()` 로**
> 액티비티 안에서 `ActivityExecutionContext` 를 통해 워크플로우 ID(`order-1004`)와 액티비티 ID 를 얻을 수 있습니다. 이 조합은 재시도해도 변하지 않으므로 완벽한 멱등 키입니다. `UUID.randomUUID()` 는 재시도마다 달라지므로 절대 쓰면 안 됩니다.

---

## 9-5. 보상 순서는 역순 — 히스토리로 증명

결제 → 재고 → 배송 순으로 성공했다면, 보상은 **배송취소 → 재고해제 → 결제환불** 순이어야 합니다. 왜 역순인가?

- 의존 관계가 역방향이기 때문입니다. 배송 요청은 재고 예약을 전제로 하고, 재고 예약은 결제를 전제로 합니다. 전제를 먼저 없애면 그 위에 얹힌 것을 취소할 근거가 사라집니다.
- 재고를 먼저 풀어 버리면 배송 취소가 실패했을 때 "재고는 풀렸는데 배송은 나가는" 최악의 상태가 됩니다.

`Saga` 클래스는 내부적으로 `Deque` 에 보상을 쌓고 **LIFO** 로 꺼냅니다. 말로만 믿지 말고 히스토리로 확인합니다. 배송 단계에서 실패하도록 만든 주문을 실행합니다.

```bash
temporal workflow start --task-queue ORDER_TASK_QUEUE --type OrderWorkflow \
  --workflow-id order-1004 \
  --input '{"orderId":"1004","customerId":"C-77","sku":"SKU-77","qty":1,"amount":39000,"address":"제주특별자치도 서귀포시 도서산간"}'
```

**결과** (Worker 콘솔)
```
04:31:12.204 [workflow-method-order-1004-9a4c1e77] INFO  c.e.order.OrderWorkflowImpl - [order-1004] 결제 시작 amount=39000
04:31:12.418 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] INFO  c.e.o.PaymentActivityImpl - [charge] orderId=1004 amount=39000 → pay_88f1
04:31:12.601 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] INFO  c.e.o.InventoryActivityImpl - [reserve] sku=SKU-77 qty=1 → rsv_2c9
04:31:12.772 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] ERROR c.e.o.ShippingActivityImpl - [requestShipment] 배송 불가 지역: 제주특별자치도 서귀포시 도서산간
04:31:13.905 [workflow-method-order-1004-9a4c1e77] ERROR c.e.order.OrderWorkflowImpl - [order-1004] 실패 — 보상 시작
04:31:14.088 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] INFO  c.e.o.InventoryActivityImpl - [release] reservationId=rsv_2c9 해제 완료
04:31:14.245 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] INFO  c.e.o.PaymentActivityImpl - [refund] paymentId=pay_88f1 환불 완료
```

이제 히스토리를 봅니다.

```bash
temporal workflow show -w order-1004
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-18T04:31:12Z  WorkflowExecutionStarted
   2  2026-03-18T04:31:12Z  WorkflowTaskScheduled
   3  2026-03-18T04:31:12Z  WorkflowTaskStarted
   4  2026-03-18T04:31:12Z  WorkflowTaskCompleted
   5  2026-03-18T04:31:12Z  ActivityTaskScheduled     [charge]
   6  2026-03-18T04:31:12Z  ActivityTaskStarted
   7  2026-03-18T04:31:12Z  ActivityTaskCompleted
   8~10                        WorkflowTaskScheduled / Started / Completed
  11  2026-03-18T04:31:12Z  ActivityTaskScheduled     [reserve]
  12  2026-03-18T04:31:12Z  ActivityTaskStarted
  13  2026-03-18T04:31:12Z  ActivityTaskCompleted
  14~16                       WorkflowTaskScheduled / Started / Completed
  17  2026-03-18T04:31:12Z  ActivityTaskScheduled     [requestShipment]
  18  2026-03-18T04:31:12Z  ActivityTaskStarted
  19  2026-03-18T04:31:13Z  ActivityTaskFailed        [requestShipment]
  20~22                       WorkflowTaskScheduled / Started / Completed
  23  2026-03-18T04:31:14Z  ActivityTaskScheduled     [release]      ← 보상 1
  24  2026-03-18T04:31:14Z  ActivityTaskStarted
  25  2026-03-18T04:31:14Z  ActivityTaskCompleted
  26~28                       WorkflowTaskScheduled / Started / Completed
  29  2026-03-18T04:31:14Z  ActivityTaskScheduled     [refund]       ← 보상 2
  30  2026-03-18T04:31:14Z  ActivityTaskStarted
  31  2026-03-18T04:31:14Z  ActivityTaskCompleted
  32~34                       WorkflowTaskScheduled / Started / Completed
  35  2026-03-18T04:31:14Z  WorkflowExecutionFailed

Result:
  Status: FAILED
  Failure: 배송 불가 지역: 제주특별자치도 서귀포시 도서산간
```

**정방향은 charge(5) → reserve(11) → requestShipment(17), 보상은 release(23) → refund(29).** 정확히 역순입니다. `requestShipment` 는 성공하지 못했으므로 `cancelShipment` 는 등록되지 않았고, 히스토리에도 없습니다.

`activityType` 을 정확히 확인하려면 JSON 으로 봅니다.

```bash
temporal workflow show -w order-1004 --output json | jq '[.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_SCHEDULED") | {id: .eventId, type: .activityTaskScheduledEventAttributes.activityType.name}]'
```

**결과**
```json
[
  { "id": "5",  "type": "Charge" },
  { "id": "11", "type": "Reserve" },
  { "id": "17", "type": "RequestShipment" },
  { "id": "23", "type": "Release" },
  { "id": "29", "type": "Refund" }
]
```

보상이 등록의 역순(Reserve→Release 가 Charge→Refund 보다 먼저)으로 스케줄된 것이 증명되었습니다.

> 💡 **보상도 그냥 액티비티입니다**
> 보상 액티비티는 특별한 종류가 아닙니다. `ActivityTaskScheduled` 이벤트를 똑같이 만들고, 똑같이 재시도되고, 똑같이 타임아웃됩니다. Temporal 이 특별 취급하는 건 아무것도 없습니다. `Saga` 클래스는 그저 "언제 무엇을 호출할지"를 대신 기억해 줄 뿐입니다.

---

## 9-6. ⚠️ 보상 액티비티도 실패할 수 있다

여기가 Saga 의 가장 어려운 지점입니다. 정방향 실패는 보상으로 처리하면 되는데, **보상이 실패하면 처리할 방법이 없습니다.** 되돌리기를 되돌릴 수는 없습니다.

환불 API 가 죽어 있는 상황을 시뮬레이션합니다.

```bash
temporal workflow show -w order-1007
```

**결과** (발췌)
```
  19  2026-03-18T05:02:31Z  ActivityTaskFailed        [requestShipment]
  23  2026-03-18T05:02:32Z  ActivityTaskScheduled     [release]
  25  2026-03-18T05:02:32Z  ActivityTaskCompleted
  29  2026-03-18T05:02:32Z  ActivityTaskScheduled     [refund]
  31  2026-03-18T05:02:34Z  ActivityTaskFailed        [refund]        ← 보상이 실패
  35  2026-03-18T05:02:35Z  WorkflowExecutionFailed

Result:
  Status: FAILED
  Failure: io.temporal.workflow.Saga$CompensationException: Compensation failed
```

재고는 풀렸는데 결제는 환불되지 않은 **부분 보상 상태**로 워크플로우가 끝났습니다. 이건 어떤 의미로는 최초의 실패보다 나쁩니다. 이 상황을 다루는 전략은 세 가지입니다.

| 전략 | 설정 | 장점 | 단점 | 언제 |
|---|---|---|---|---|
| **① 보상에 무한 재시도** | 보상 전용 `ActivityOptions` 에 `setMaximumAttempts(0)`(무제한) + 긴 `ScheduleToCloseTimeout` | 언젠가 반드시 보상됨. 코드가 가장 단순 | 환불 API 가 며칠 죽어 있으면 워크플로우가 며칠 열려 있음 | **가장 흔함. 기본으로 이걸 쓰세요** |
| **② 실패 시 수동 개입 큐로** | `catch (Saga.CompensationException e)` 에서 알림 액티비티 호출 + 운영 DB 에 기록 | 사람이 개입해 처리 가능. 워크플로우는 빨리 종료 | 운영 부담. 큐가 방치되면 무의미 | 보상이 사람의 판단을 요구할 때(부분 배송, 사용 완료된 쿠폰 등) |
| **③ 병렬 보상 + 부분 실패 수집** | `setParallelCompensation(true)` | 보상 총 소요 시간이 가장 긴 하나로 수렴 | **역순 보장이 사라짐**. 의존 관계가 있으면 못 씀 | 보상들이 서로 완전히 독립일 때만 |

### ① 보상 전용 재시도 정책

정방향과 보상의 재시도 정책은 **달라야 합니다.** 정방향은 빨리 포기해야 고객이 기다리지 않습니다. 보상은 절대 포기하면 안 됩니다.

```java
// 정방향: 3번 시도하고 포기 → 빨리 실패해서 보상 단계로 넘어간다
private static final ActivityOptions FORWARD = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
        .build();

// 보상: 무한 재시도. 7일 안에는 언젠가 성공한다
private static final ActivityOptions COMPENSATION = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setScheduleToCloseTimeout(Duration.ofDays(7))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofMinutes(10))
                .setMaximumAttempts(0)            // 0 = 무제한
                .build())
        .build();

// 같은 인터페이스, 다른 옵션 → 별개의 스텁이 된다. 인터페이스를 두 벌 만들 필요가 없다.
private final PaymentActivity payment =
        Workflow.newActivityStub(PaymentActivity.class, FORWARD);
private final PaymentActivity paymentC =
        Workflow.newActivityStub(PaymentActivity.class, COMPENSATION);

// ...
String paymentId = payment.charge(req.orderId(), req.amount());
saga.addCompensation(paymentC::refund, paymentId);   // ← 무한 재시도 스텁으로 등록
```

`temporal workflow describe` 로 재시도 중인 보상을 관찰하면 이렇게 보입니다.

```bash
temporal workflow describe -w order-1007
```

**결과**
```
Execution Info:
  Workflow Id       order-1007
  Run Id            c81f3a04-77de-4b2c-9f10-3a5b7e2c1d99
  Type              OrderWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Status            RUNNING
  History Length    31

Pending Activities:
  1  ActivityId          29
     Activity Type       Refund
     Attempt             14
     Maximum Attempts    0
     Last Failure        java.net.ConnectException: Connection refused: pg-api:443
     Scheduled Time      2026-03-18 05:19:12 +0000 UTC
     Expiration Time     2026-03-25 05:02:32 +0000 UTC
```

`Attempt 14`, `Maximum Attempts 0`(무제한), `Expiration Time` 이 7일 뒤. 환불 API 가 살아나는 순간 자동으로 성공합니다. **개발자가 할 일이 없습니다.**

### ② `Saga.CompensationException` 처리

수동 개입 전략을 택한다면 보상 실패를 명시적으로 잡습니다.

```java
} catch (Exception e) {
    try {
        saga.compensate();
    } catch (Saga.CompensationException ce) {
        // ce.getCause() 에 첫 번째 보상 실패의 원인이 들어 있다
        Workflow.getLogger(OrderWorkflowImpl.class)
                .error("[{}] 보상 실패 — 수동 개입 필요", req.orderId(), ce);
        // 이 액티비티는 반드시 성공해야 하므로 별도의 무한 재시도 스텁을 씁니다
        opsAlert.raiseManualIntervention(
                req.orderId(),
                "SAGA_COMPENSATION_FAILED",
                ce.getCause() == null ? ce.toString() : ce.getCause().toString());
    }
    throw e;   // 원래 실패 원인을 워크플로우 결과로 전파
}
```

`setContinueWithError(true)` 로 두면 `compensate()` 가 도중에 멈추지 않고 나머지 보상을 모두 시도한 뒤, 실패한 것들을 `CompensationException` 의 suppressed 예외로 모아서 던집니다.

**결과** (`setContinueWithError(true)` 상태에서 보상 3개 중 2개가 실패했을 때)
```
io.temporal.workflow.Saga$CompensationException: Compensation failed
    at io.temporal.workflow.Saga.compensate(Saga.java:118)
    at com.example.order.OrderWorkflowImpl.processOrder(OrderWorkflowImpl.java:74)
  Suppressed: io.temporal.failure.ActivityFailure: Activity task failed, activityType='Refund'
    Caused by: java.net.ConnectException: Connection refused: pg-api:443
  Suppressed: io.temporal.failure.ActivityFailure: Activity task failed, activityType='CancelShipment'
    Caused by: java.net.SocketTimeoutException: Read timed out
```

### ③ 병렬 보상

`new Saga.Options.Builder().setParallelCompensation(true).build()` 로 두면 `compensate()` 가 모든 보상을 **동시에** 시작하고 전부 끝날 때까지 기다립니다. 히스토리를 보면 차이가 확연합니다.

**결과** (`temporal workflow show -w order-1009` 발췌)
```
  22  2026-03-18T05:44:01Z  WorkflowTaskCompleted
  23  2026-03-18T05:44:01Z  ActivityTaskScheduled     [cancelShipment]
  24  2026-03-18T05:44:01Z  ActivityTaskScheduled     [release]        ← 같은 Workflow Task 에서
  25  2026-03-18T05:44:01Z  ActivityTaskScheduled     [refund]         ← 셋이 한꺼번에 스케줄
  26~28                       ActivityTaskStarted ×3
  ...
```

직렬 모드에서는 `ActivityTaskScheduled` 사이마다 `WorkflowTaskScheduled/Started/Completed` 3개가 끼어들었는데, 병렬 모드에서는 하나의 Workflow Task 가 명령 3개를 한 번에 발행합니다. 보상 총 소요가 `2.1초 → 0.8초` 로 줄지만, **재고 해제가 배송 취소보다 먼저 완료될 수 있습니다.** 의존 관계가 있으면 절대 쓰면 안 됩니다.

> ⚠️ **함정 — `setParallelCompensation(true)` 는 `setContinueWithError` 를 무시한다**
> 병렬 모드에서는 모든 보상이 이미 시작되었으므로 "실패 시 나머지를 중단"이라는 개념이 성립하지 않습니다. `setContinueWithError` 설정값과 무관하게 항상 전부 실행되고, 실패한 것들이 한꺼번에 보고됩니다. 직렬 모드를 전제로 `setContinueWithError(false)` 를 걸어 뒀다가 나중에 병렬로 바꾸면 동작이 조용히 달라집니다.

---

## 9-7. 실전 — 주문 취소 시나리오 전체

앞의 9-5 에서 본 `order-1004` 를 다시, 이번에는 JSON 히스토리로 자세히 봅니다. 실패 이벤트와 보상 스케줄 사이의 인과 관계를 확인하기 위해서입니다.

```bash
temporal workflow show -w order-1004 --output json | jq '.events[] | select(.eventId=="19" or .eventId=="29" or .eventId=="35")'
```

**결과**
```json
{
  "eventId": "19",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_FAILED",
  "activityTaskFailedEventAttributes": {
    "failure": {
      "message": "배송 불가 지역: 제주특별자치도 서귀포시 도서산간",
      "source": "JavaSDK",
      "applicationFailureInfo": { "type": "ShippingUnavailableException", "nonRetryable": true }
    },
    "scheduledEventId": "17",
    "retryState": "RETRY_STATE_NON_RETRYABLE_FAILURE"
  }
}
{
  "eventId": "29",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
  "activityTaskScheduledEventAttributes": {
    "activityId": "29",
    "activityType": { "name": "Refund" },
    "input": { "payloads": [ { "data": "InBheV84OGYxIg==" } ] },
    "scheduleToCloseTimeout": "604800s",
    "startToCloseTimeout": "30s",
    "retryPolicy": { "initialInterval": "1s", "maximumInterval": "600s", "maximumAttempts": 0 }
  }
}
{
  "eventId": "35",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_FAILED",
  "workflowExecutionFailedEventAttributes": {
    "failure": {
      "message": "Activity task failed",
      "activityFailureInfo": {
        "scheduledEventId": "17",
        "activityType": { "name": "RequestShipment" }
      },
      "cause": {
        "message": "배송 불가 지역: 제주특별자치도 서귀포시 도서산간",
        "applicationFailureInfo": { "type": "ShippingUnavailableException" }
      }
    }
  }
}
```

읽어 낼 것들이 많습니다.

- 이벤트 19 의 `nonRetryable: true` — `ShippingUnavailableException` 을 `setDoNotRetry` 목록에 넣어 두었기 때문에 재시도 없이 즉시 실패했습니다. 배송 불가 지역은 재시도해도 결과가 같으므로 옳은 설정입니다.
- 이벤트 23/29 의 `input.payloads[0].data` 는 Base64 입니다. `InJzdl8yYzki` 를 디코드하면 `"rsv_2c9"`, `InBheV84OGYxIg==` 는 `"pay_88f1"`. **보상 액티비티가 정방향의 반환값을 그대로 인자로 받았다**는 증거입니다.
- 이벤트 23/29 의 `retryPolicy.maximumAttempts: 0` — 9-6 의 보상 전용 스텁이 적용되었습니다. 정방향 이벤트 5/11/17 을 같은 방식으로 열어 보면 `maximumAttempts: 3` 입니다.
- 이벤트 35 의 `cause` 체인 — 워크플로우의 최종 실패 원인이 보상 실패가 아니라 **원래의 배송 실패**로 보존되었습니다. `saga.compensate()` 뒤에 `throw e` 를 했기 때문입니다.

```bash
temporal workflow describe -w order-1004
```

**결과**
```
Execution Info:
  Workflow Id       order-1004
  Run Id            9a4c1e77-3b28-4f01-bd6e-51c0a9e3f882
  Type              OrderWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-18 04:31:12 +0000 UTC
  Close Time        2026-03-18 04:31:14 +0000 UTC
  Status            FAILED
  History Length    35
  History Size      6812
```

전체 2.3초 안에 3단계 시도 + 2단계 보상이 끝났고, 히스토리 35개 이벤트에 **무슨 일이 왜 일어났는지가 전부 기록**되어 있습니다. 별도의 Saga 상태 테이블도, 로그 수집도 필요하지 않았습니다.

---

## 9-8. Saga 없이 손으로 짜면

`Saga` 클래스가 뭘 대신해 주는지 보려면 직접 짜 보는 게 빠릅니다.

```java
List<Runnable> compensations = new ArrayList<>();
try {
    String paymentId = payment.charge(req.orderId(), req.amount());
    compensations.add(() -> paymentC.refund(paymentId));

    String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
    compensations.add(() -> inventoryC.release(reservationId));

    String shipmentId = shipping.requestShipment(req.orderId(), req.address());
    compensations.add(() -> shippingC.cancelShipment(shipmentId));

    return req.orderId() + " COMPLETED";

} catch (Exception e) {
    Collections.reverse(compensations);        // ← 역순으로 뒤집고
    RuntimeException collected = null;
    for (Runnable c : compensations) {
        try {
            c.run();
        } catch (RuntimeException ce) {        // ← 실패해도 나머지는 계속
            if (collected == null) collected = new RuntimeException("Compensation failed", ce);
            else collected.addSuppressed(ce);  // ← suppressed 로 모으고
        }
    }
    if (collected != null) e.addSuppressed(collected);
    throw e;
}
```

동작은 합니다. `Saga` 클래스가 하는 일은 정확히 이것입니다.

| `Saga` 가 대신해 주는 것 | 손코딩 시 |
|---|---|
| LIFO 스택 관리 | `List` + `Collections.reverse` 를 매번 |
| 실패 수집 + suppressed 체이닝 | 위 8줄을 매번 복붙 |
| 병렬 보상 시 `Promise.allOf` 조합 | `Async.procedure` 로 직접 조합 |
| 직렬/병렬, 오류 시 중단/계속의 조합 | 4가지 경우를 직접 분기 |
| `Functions.Proc1~Proc6` 타입 안전 등록 | 람다 캡처(변수를 실수로 놓치기 쉬움) |

즉 `Saga` 는 **마법이 아니라 보일러플레이트 제거기**입니다. Temporal 이 제공하는 진짜 마법은 다른 데 있습니다 — 워크플로우가 프로세스 재시작을 건너서도 `compensations` 리스트를 기억한다는 점, 그리고 그것이 히스토리 리플레이로 자동 복원된다는 점입니다. 손코딩한 위 코드도 **그 마법은 그대로 누립니다.** Worker 가 보상 도중에 죽어도, 재기동한 Worker 가 히스토리를 리플레이해 "release 는 이미 완료, refund 부터"를 정확히 이어서 합니다.

> 💡 **`Saga` 를 안 쓰는 것도 선택지입니다**
> 보상이 두 개뿐이고 조건 분기가 복잡하다면 손코딩이 더 읽기 좋을 수 있습니다. 중요한 건 클래스 이름이 아니라 "성공한 것만 역순으로 되돌린다 + 보상은 멱등하다 + 마지막에 예외를 다시 던진다" 세 가지입니다.

---

## 9-9. 함정 모음

> ⚠️ **함정 1 — 보상이 멱등하지 않으면 이중 환불이 난다**
> `refund()` 가 조회 없이 무조건 `pgClient.refund(paymentId)` 를 호출한다고 합시다. 보상 액티비티는 무한 재시도로 설정되어 있으므로, PG 가 환불을 처리한 직후 응답이 타임아웃되면 재시도가 발생합니다. PG 가 중복 환불을 막아 주지 않으면 **고객에게 78,000원이 환불됩니다.**
> 해결: 환불 전 현재 상태를 조회하고 `REFUNDED` 면 즉시 return. 또는 PG 의 멱등 키 API 를 사용. 이 문제는 히스토리에도 예외 로그에도 남지 않습니다 — **정산 데이터를 대조해야만 발견됩니다.**

> ⚠️ **함정 2 — 등록 시점만 바꿔서는 해결되지 않는다**
> 9-4 에서 본 딜레마를 다시 정리합니다.
> - **호출 전 등록**: `charge` 가 정말 실패했을 때도 환불을 시도 → 존재하지 않는 결제 건에 대한 404 → 보상 실패 → 워크플로우가 보상 단계에서 막힘
> - **호출 후 등록**: `charge` 가 타임아웃됐지만 PG 에는 승인이 남은 경우를 놓침 → 고객 돈이 묶임
>
> 어느 쪽도 정답이 아닙니다. 정답은 **호출 후 등록 + 멱등한 보상**입니다. "대상이 없으면 성공"이라는 규칙이 첫 번째 시나리오를 무해하게 만들고, `charge` 쪽 멱등 키가 두 번째 시나리오를 없앱니다. 등록 시점 논쟁에 시간을 쓰지 말고 액티비티 구현을 고치세요.

> ⚠️ **함정 3 — `saga.compensate()` 뒤에 `throw` 를 빼먹으면 워크플로우가 성공으로 끝난다**
> 가장 자주 보는 실수이자, 가장 조용히 위험한 실수입니다.
> ```java
> } catch (Exception e) {
>     saga.compensate();
>     // throw e;   ← 실수로 지웠거나, 애초에 안 썼다
> }
> return req.orderId() + " COMPLETED";   // ← catch 를 빠져나와 여기로 온다
> ```
> `temporal workflow describe` 결과는 이렇게 나옵니다.
> ```
>   Status            COMPLETED
>   Result            ["1004 COMPLETED"]
> ```
> 결제는 환불됐고 재고도 풀렸는데, **주문 상태는 완료**입니다. 상위 시스템(자식 워크플로우로 호출했다면 부모 워크플로우, API 로 호출했다면 클라이언트)은 주문이 성공한 줄 압니다. 배송은 안 나가고 돈은 환불됐는데 주문 완료 메일이 나갑니다.
> 히스토리를 봐도 `WorkflowExecutionCompleted` 라서 알림이 안 울립니다. 모니터링 대시보드의 실패율은 0% 로 아름답습니다. **고객 CS 로만 발견됩니다.**
> 방어책: 워크플로우의 반환 지점을 `try` 블록 안으로 넣으세요. 위 예시처럼 `return` 이 `try/catch` 바깥에 있으면 이 실수가 가능해집니다. Step 11 의 리플레이 테스트에서 "실패 시나리오의 최종 상태가 FAILED 인가"를 반드시 단언하세요.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 분산 트랜잭션 | 서비스마다 DB 가 다르면 `BEGIN...COMMIT` 불가. 외부 API 는 애초에 트랜잭션 없음 |
| 2PC 가 안 되는 이유 | 코디네이터 SPOF · prepare~commit 사이 락 유지 · 참여자 전원의 XA 지원 필요 |
| Saga | 각 단계에 보상 액션을 짝지어 두고, 실패 시 성공한 것만 역순으로 되돌림 |
| ROLLBACK 아님 | 보상은 "지우기"가 아니라 "상쇄 트랜잭션 추가". 원장에 승인+환불이 둘 다 남음 |
| Choreography vs Orchestration | Temporal 은 Orchestration. **워크플로우 코드 자체가 조정자**, 상태 테이블 불필요 |
| `Saga` 클래스 | `addCompensation(stub::method, arg)` 로 등록, `compensate()` 로 LIFO 실행 |
| 등록 시점 | 반환값이 필요하므로 **호출 후**. 대신 보상을 **멱등**하게 만들어 경계 케이스를 흡수 |
| at-least-once | 액티비티는 "실패로 보이지만 실제로 성공"할 수 있음. 멱등 키는 workflowId 기반으로 |
| 보상 순서 | LIFO. 히스토리의 `ActivityTaskScheduled` 순서로 검증 가능 |
| 보상 재시도 정책 | 정방향은 `maximumAttempts=3`, 보상은 `maximumAttempts=0`(무제한) + 긴 ScheduleToClose |
| 보상 실패 3전략 | ① 무한 재시도(권장) ② 수동 개입 큐 ③ 병렬 보상 + 부분 실패 수집 |
| `setParallelCompensation` | `true` 면 동시 실행 — **역순 보장이 사라짐**. 보상이 독립일 때만 |
| `CompensationException` | `setContinueWithError(true)` 시 실패한 보상들을 suppressed 로 모아 던짐 |
| 가장 위험한 실수 | `compensate()` 후 `throw` 누락 → 워크플로우가 **COMPLETED** 로 끝나 아무도 모름 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `Saga` 없이 `List<Runnable>` 로 보상을 구현하고, 역순 실행과 부분 실패 수집을 직접 처리하기
2. `refund` / `release` 액티비티를 멱등하게 고치기 — "대상 없음"과 "이미 처리됨"을 모두 정상 종료로
3. 정방향과 보상에 서로 다른 `ActivityOptions` 를 적용하고, 히스토리 JSON 의 `retryPolicy` 로 확인하기
4. `saga.compensate()` 후 `throw` 를 뺀 워크플로우를 실행해 `Status: COMPLETED` 를 재현하고, 왜 위험한지 서술하기
5. `setParallelCompensation(true)` 로 바꾼 뒤 히스토리에서 `ActivityTaskScheduled` 3개가 같은 Workflow Task 에 묶이는 것을 확인하기
6. `Saga.CompensationException` 을 잡아 운영 알림 액티비티를 호출하되, 원래 실패 원인은 그대로 전파하기

---

## 다음 단계

Saga 로 실패를 안전하게 되돌리는 워크플로우를 만들었습니다. 그런데 이 워크플로우가 운영에서 **몇 주씩 살아 있는 동안** 여러분은 코드를 계속 배포할 것입니다. 액티비티를 하나 추가하는 순간, 실행 중이던 워크플로우들이 리플레이에 실패해 영원히 멈춥니다.

다음 스텝은 이 코스에서 가장 중요합니다. `NonDeterministicException` 을 직접 재현하고, `Workflow.getVersion` 으로 안전하게 코드를 바꾸는 법을 익힙니다.

→ [Step 10 — 버저닝과 무중단 배포](../step-10-versioning/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 를 위에서부터 읽으며 9-1 ~ 9-8 의 모든 코드를 확인하고, 실제로 Worker 에 등록해 `order-1004`(배송 실패)와 `order-1007`(보상 실패) 두 시나리오를 실행한 뒤 히스토리를 대조합니다. 그다음 `Exercise.java` 의 6문제를 직접 풀고 `Solution.java` 로 답을 맞춰 봅니다. 세 파일 모두 단일 파일 안에 `static nested class` 로 워크플로우·액티비티·구현체를 모두 담아 그대로 컴파일됩니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실행 가능한 파일입니다.

- `[9-3]` 구간이 이 파일의 뼈대입니다. `Saga.Options` 를 만들고 `try/catch` 안에서 `addCompensation` → `compensate()` → `throw` 로 이어지는 표준 구조를 담고 있습니다. 이 순서를 바꾸면 9-9 함정 3이 재현되니 주의하세요.
- `[9-4]` 의 `PaymentActivityImpl.refund` 는 **멱등 구현의 정본**입니다. `findByIdempotencyKey` 가 `null` 이면 `return`, `REFUNDED` 면 `return` 하는 두 개의 조기 반환이 핵심이며, 여기에 `throw` 를 넣는 순간 보상 단계가 막힙니다.
- `[9-6]` 에는 `FORWARD` 와 `COMPENSATION` 두 개의 `ActivityOptions` 상수가 있고, 같은 인터페이스로 **스텁을 두 번** 만듭니다(`payment` / `paymentC`). `maximumAttempts` 가 각각 3 과 0 인 것을 히스토리 JSON 에서 확인할 수 있습니다.
- `[9-7]` 의 `ShippingActivityImpl.requestShipment` 는 주소에 `"도서산간"` 이 포함되면 `ShippingUnavailableException` 을 던집니다. 이 예외는 `setDoNotRetry` 에 등록돼 있어 `retryState: RETRY_STATE_NON_RETRYABLE_FAILURE` 로 즉시 실패하며, 본문의 `order-1004` 시나리오가 그대로 재현됩니다.
- `[9-8]` 의 `ManualSagaWorkflowImpl` 은 `Saga` 없이 `List<Runnable>` 로 같은 동작을 구현한 것입니다. 두 구현이 같은 Worker 에 등록돼 있으니 타입만 바꿔 실행해 히스토리를 비교해 보세요. 파일 하단 `main` 은 `order-1004` 를 시작하고 결과를 기다리는데, **실패로 끝나는 것이 정상 동작**입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1·6** 은 워크플로우 코드를 작성하는 문제(손코딩 Saga / `CompensationException` 처리)이고, **문제 2** 는 액티비티 구현을 고치는 문제, **문제 3·5** 는 옵션을 바꾸고 **히스토리를 관찰**하는 문제, **문제 4** 는 일부러 버그를 만들어 그 결과를 확인하는 문제입니다.
- 문제 2 의 `FakePgClient` 는 파일 안에 이미 구현돼 있습니다. `findByIdempotencyKey` 가 등록되지 않은 키에 `null` 을 반환하고, 두 번째 `refund` 호출에서 `DuplicateRefundException` 을 던지도록 만들어 두었습니다. 즉 **멱등하게 고치지 않으면 반드시 터지는** 스텁입니다.
- 문제 4 는 의도적으로 잘못된 코드를 실행하는 문제입니다. `throw e;` 를 주석 처리한 채 실행하고 `temporal workflow describe -w order-1005` 의 `Status` 가 `COMPLETED` 로 나오는 것을 눈으로 확인한 뒤, 왜 이것이 모니터링에 잡히지 않는지 주석으로 서술하세요.
- ⚠️ **주의** — 문제 3·5 는 실행 후 히스토리를 봐야 하므로, 각 문제마다 **다른 워크플로우 ID**(`order-2001` ~ `order-2005`)를 쓰도록 되어 있습니다. 같은 ID 로 재실행하면 `WorkflowExecutionAlreadyStarted` 가 납니다.
- 파일 맨 아래 `verifyCompensationOrder` 는 뒷정리 겸 검증 헬퍼입니다. 히스토리에서 `ActivityTaskScheduled` 의 `activityType` 만 뽑아 리스트로 반환하므로, 기대 순서와 `assertEquals` 로 비교할 수 있습니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와 "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `Collections.reverse` 후 루프를 돌며 `addSuppressed` 로 실패를 모으는 구현입니다. 핵심 주석은 "`Saga` 클래스가 마법이 아니라 이 20줄의 대체품"이라는 것과, 그럼에도 **Worker 재시작을 건너 보상을 이어서 하는 능력은 `Saga` 가 아니라 Temporal 런타임에서 온다**는 구분입니다.
- **정답 2** 의 두 개의 조기 반환에는 각각 긴 주석이 붙어 있습니다. `null` 반환은 "9-4 의 호출 전/후 등록 딜레마를 무해화하는 장치"이고, `REFUNDED` 체크는 "무한 재시도 정책과 짝을 이루는 필수 안전장치"입니다. 하나라도 빠지면 어떤 시나리오에서 터지는지를 표로 정리해 두었습니다.
- **정답 3** 은 스텁을 두 개 만드는 것이 답입니다. `Workflow.newActivityStub` 은 인터페이스가 같아도 옵션이 다르면 별개의 스텁이며, 액티비티 인터페이스를 두 벌 만들 필요가 없다는 점을 강조합니다. 검증용 `jq` 명령과 기대 출력도 함께 실려 있습니다.
- **정답 4** 의 해설이 이 파일에서 가장 깁니다. `Status: COMPLETED` 가 왜 위험한지를 ① 부모 워크플로우가 성공으로 판단 ② 실패율 메트릭이 0% ③ 알림 워크플로우가 완료 메일 발송 ④ 재처리 배치가 대상에서 제외 — 네 단계로 전개하고, 방어책으로 "반환문을 `try` 안으로" 와 "리플레이 테스트에서 최종 상태 단언"을 제시합니다.
- **정답 5** 는 병렬 보상의 히스토리 발췌를 그대로 담고 있습니다. `ActivityTaskScheduled` 3개 사이에 `WorkflowTaskScheduled` 가 **끼어 있지 않다**는 것이 판정 기준입니다. 더불어 `setContinueWithError` 가 병렬 모드에서 무시된다는 사실도 주석으로 경고합니다.
- **정답 6** 은 `catch (Saga.CompensationException ce)` 안에서 알림을 보내되 바깥에서 원래 예외 `e` 를 던지는 이중 구조입니다. 순서를 뒤집어 `ce` 를 던지면 히스토리의 `WorkflowExecutionFailed.cause` 가 "보상 실패"로 바뀌어 **원인 분석이 불가능해진다**는 점을 설명합니다.

```java file="./Solution.java"
```
