# Step 06 — 타이머와 대기

> **학습 목표**
> - `Workflow.sleep()` 이 `Thread.sleep()` 과 무엇이 다른지 이해하고, **30일 sleep 워크플로우가 히스토리 5개짜리로 살아 있는 것**을 직접 확인한다
> - Worker 를 전부 죽였다가 시간이 지난 뒤 재기동해도 타이머가 즉시 이어지는 것을 재현한다
> - `Workflow.await(Supplier)` 의 조건이 **폴링이 아니라 Workflow Task 마다 평가된다**는 것을 히스토리로 확인한다
> - `Workflow.await(Duration, Supplier)` 의 반환값으로 "조건 충족"과 "타임아웃"을 구분해 처리한다
> - `Async.function` + `Promise.allOf` 로 액티비티 3개를 병렬 실행하고, **순차 3.0초 → 병렬 1.1초**를 실측한다
> - `Promise.anyOf` 로 "액티비티 vs 타이머" 경주를 붙여 소프트 타임아웃을 구현한다
> - 1초 폴링 루프가 하루에 히스토리 이벤트 몇 개를 만드는지 계산한다
>
> **선행 스텝**: Step 05 — 재시도와 실패 처리
> **예상 소요**: 90분

---

## 6-0. 실습 준비

Step 05 의 프로젝트를 그대로 씁니다. 이 스텝은 **시간**을 다루므로, 서버 시각과 클라이언트 시각이 맞는지 먼저 확인합니다.

```bash
temporal operator cluster health && date -u
```

**결과**
```
temporal.api.workflowservice.v1.WorkflowService: SERVING
Tue Mar 11 11:02:04 UTC 2026
```

버전은 코스 전체와 동일합니다: Temporal Server 1.22.4, Java SDK 1.22.3, temporal CLI 0.11.0, Java 21.

---

## 6-1. Workflow.sleep — 몇 달을 자도 되는 이유

일반 Java 코드에서 30일을 기다리려면 이렇게 씁니다.

```java
Thread.sleep(Duration.ofDays(30).toMillis());   // 스레드 하나가 30일간 묶인다
```

Temporal 에서는 이렇게 씁니다.

```java
Workflow.sleep(Duration.ofDays(30));
```

겉보기엔 같지만 동작은 완전히 다릅니다.

| | `Thread.sleep()` | `Workflow.sleep()` |
|---|---|---|
| 스레드 | 30일간 점유 | **즉시 반환, 점유 없음** |
| 메모리 | 30일간 상주 | **워크플로우 객체가 메모리에서 제거됨** |
| Worker 재배포 | 잠든 스레드가 죽음 | **영향 없음** |
| 서버 재시작 | 소실 | **영향 없음** |
| 최대 길이 | 프로세스 수명까지 | **사실상 무제한** |
| 리플레이 | 다시 30일을 잠 | **즉시 통과** |

`Workflow.sleep()` 을 만나면 SDK 는 **워크플로우 코드를 그 자리에서 멈추고**, 서버에 "이 시각에 깨워 달라"는 타이머를 등록한 뒤, **워크플로우 인스턴스를 메모리에서 통째로 버립니다.** Worker 에는 아무것도 남지 않습니다. 서버에는 `TimerStarted` 이벤트 하나가 기록되어 있을 뿐입니다.

시간이 되면 서버가 `TimerFired` 를 기록하고 Workflow Task 를 만듭니다. 어느 Worker 든 그 태스크를 집어서 **히스토리를 처음부터 리플레이**해 워크플로우를 그 지점까지 복원하고, `Workflow.sleep()` 다음 줄부터 실행을 이어갑니다. 리플레이 중에 만나는 `Workflow.sleep()` 은 이미 `TimerFired` 가 있으므로 **기다리지 않고 즉시 통과**합니다.

30일짜리 워크플로우를 띄워 봅니다.

```java
public class SubscriptionWorkflowImpl implements SubscriptionWorkflow {
    @Override
    public String renew(String customerId) {
        log.info("[{}] 구독 시작. 30일 뒤 갱신합니다.", customerId);
        Workflow.sleep(Duration.ofDays(30));            // ← 여기서 메모리에서 사라진다
        log.info("[{}] 30일 경과. 갱신 처리.", customerId);
        return customerId + " RENEWED";
    }
}
```

```bash
temporal workflow start --type SubscriptionWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id sub-C-1001 --input '"C-1001"'
```

**결과** (Worker 콘솔 — 한 줄 찍고 끝입니다)
```
11:05:12.641 [workflow-method-sub-C-1001-...] INFO  c.e.o.SubscriptionWorkflowImpl - [C-1001] 구독 시작. 30일 뒤 갱신합니다.
```

**결과** (`temporal workflow describe -w sub-C-1001`)
```
Execution Info:
  Workflow Id       sub-C-1001
  Type              SubscriptionWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-11 11:05:12 +0000 UTC
  Status            RUNNING
  History Length    5
  History Size      897
```

**Status RUNNING, History Length 5, History Size 897바이트.** 30일간 실행될 워크플로우가 **1KB 도 안 되는 상태**로 서버에 누워 있습니다. Worker 쪽 메모리 사용량은 0 입니다.

> 💡 **비유 — 알람을 맞춰 두고 자리를 뜨는 것**
> `Thread.sleep` 은 알람이 울릴 때까지 그 방에서 계속 기다리는 것입니다. 방(스레드) 하나가 계속 묶입니다.
> `Workflow.sleep` 은 **알람 시각을 프런트에 맡기고 방을 비우는 것**입니다. 시각이 되면 프런트가 아무 방이나 잡아
> 여러분의 짐(히스토리)을 다시 펼쳐 놓습니다. 방을 몇 개 운영하든 예약은 몇 백만 건이든 가능합니다.

> 💡 **실무 팁 — 워크플로우 100만 개가 자고 있어도 Worker 는 놀고 있습니다**
> Temporal 의 확장 모델이 여기서 나옵니다. Worker 수는 **동시에 코드를 실행 중인 워크플로우 수**에만 비례합니다.
> "대기 중인" 워크플로우는 서버의 저장 공간만 쓸 뿐 Worker 자원을 전혀 소비하지 않습니다.
> 그래서 "1년 뒤 갱신", "7일 뒤 자동 확정" 같은 것을 배치 잡이나 스케줄러 없이 워크플로우 안에 그냥 써 넣을 수 있습니다.

---

## 6-2. 히스토리로 본 타이머

```bash
temporal workflow show -w sub-C-1001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T11:05:12Z  WorkflowExecutionStarted
   2  2026-03-11T11:05:12Z  WorkflowTaskScheduled
   3  2026-03-11T11:05:12Z  WorkflowTaskStarted
   4  2026-03-11T11:05:12Z  WorkflowTaskCompleted
   5  2026-03-11T11:05:12Z  TimerStarted

Result:
  Status: RUNNING
```

5번 이벤트를 자세히 봅니다.

```bash
temporal workflow show -w sub-C-1001 --output json | jq '.events[4]'
```

**결과**
```json
{
  "eventId": "5",
  "eventTime": "2026-03-11T11:05:12.688Z",
  "eventType": "EVENT_TYPE_TIMER_STARTED",
  "taskId": "1048704",
  "timerStartedEventAttributes": {
    "timerId": "1",
    "startToFireTimeout": "2592000s",
    "workflowTaskCompletedEventId": "4"
  }
}
```

`startToFireTimeout: "2592000s"` — 30일 × 86,400초 = 2,592,000초입니다. **워크플로우가 자는 게 아니라, 서버가 이 숫자를 들고 있는 것**입니다.

30일을 실습할 수는 없으니 10초 타이머 버전(`sub-C-1002`)으로 전체 흐름을 봅니다.

**결과** (`temporal workflow show -w sub-C-1002`)
```
   4  2026-03-11T11:09:40Z  WorkflowTaskCompleted
   5  2026-03-11T11:09:40Z  TimerStarted
   6  2026-03-11T11:09:50Z  TimerFired            ← 정확히 10초 뒤
   7  2026-03-11T11:09:50Z  WorkflowTaskScheduled ← 깨우기 위한 태스크
   8  2026-03-11T11:09:50Z  WorkflowTaskStarted
   9  2026-03-11T11:09:50Z  WorkflowTaskCompleted
  10  2026-03-11T11:09:50Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["C-1002 RENEWED"]
```

**타이머 하나 = `TimerStarted` + `TimerFired` 2개 이벤트**, 그리고 깨어나기 위한 Workflow Task 3종 세트가 따라붙습니다. 이 숫자를 기억해 두세요 — 6-8 에서 다시 씁니다.

### Worker 를 다 죽여도 타이머는 살아 있습니다

이것이 이 절의 핵심 실험입니다.

```bash
# 1) 60초 타이머 워크플로우 시작
temporal workflow start --type SubscriptionWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id sub-C-1003 --input '"C-1003"'

# 2) Worker 를 전부 죽입니다
pkill -f OrderWorker
temporal task-queue describe --task-queue ORDER_TASK_QUEUE --task-queue-type workflow
```

**결과**
```
Poller Information:
  (없음)
```

Worker 가 하나도 없습니다. 90초를 기다린 뒤 상태를 봅니다.

```bash
sleep 90
temporal workflow describe -w sub-C-1003
```

**결과**
```
Execution Info:
  Workflow Id       sub-C-1003
  Status            RUNNING
  History Length    7

Pending Workflow Task:
  State                 Scheduled
  Scheduled Time        2026-03-11 11:15:52 +0000 UTC
```

**`TimerFired` 는 이미 기록됐고**(History Length 7), Workflow Task 가 **Scheduled 상태로 대기 중**입니다. 처리할 Worker 가 없을 뿐입니다. 이제 Worker 를 다시 띄웁니다.

```bash
./gradlew runWorker
```

**결과**
```
11:17:24.512 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
11:17:24.703 [workflow-method-sub-C-1003-...] INFO  c.e.o.SubscriptionWorkflowImpl - [C-1003] 60초 경과. 갱신 처리.
```

**Worker 가 뜬 지 0.2초 만에 이어졌습니다.** 워크플로우 코드는 "60초를 잤다"고 믿고 있지만, 실제로는 그 60초 동안 이 워크플로우를 실행하던 프로세스가 존재하지도 않았습니다. `Workflow.sleep()` 이전 부분은 리플레이로 복원되었습니다.

```
Result:
  Status: COMPLETED
  Output: ["C-1003 RENEWED"]
```

> 💡 **배포는 자는 워크플로우를 깨우지 않습니다**
> 배포 중에 타이머가 만료되면 Workflow Task 가 큐에 쌓여 있다가 새 Worker 가 뜨면 즉시 처리됩니다.
> 다만 **그 사이 워크플로우 코드를 고쳤다면** 리플레이가 깨질 수 있습니다. 그것이 Step 10(버저닝)의 주제입니다.

---

## 6-3. Workflow.await — 조건이 참이 될 때까지

시간이 아니라 **상태**를 기다려야 할 때가 있습니다. "결제 확인 시그널이 올 때까지", "승인자가 승인할 때까지".

```java
public class ApprovalWorkflowImpl implements ApprovalWorkflow {
    private boolean approved = false;

    @Override
    public String process(String orderId) {
        log.info("[{}] 승인 대기 시작", orderId);
        Workflow.await(() -> approved);              // ← 조건이 참이 될 때까지 멈춤
        log.info("[{}] 승인 확인. 진행합니다.", orderId);
        return orderId + " APPROVED";
    }

    @SignalMethod                                    // 시그널은 Step 07 에서 자세히
    public void approve() {
        this.approved = true;
    }
}
```

`Workflow.await(Supplier<Boolean>)` 은 **조건 함수가 `true` 를 반환할 때까지** 워크플로우 실행을 멈춥니다. 여기서 가장 중요한 사실은 이것입니다.

> **조건 함수는 폴링되지 않습니다.
> 워크플로우 상태가 바뀔 수 있는 시점 — 즉 새 Workflow Task 가 처리될 때마다 — 딱 한 번씩만 평가됩니다.**

새 Workflow Task 를 만드는 것은 시그널 수신, 타이머 발화, 액티비티 완료 같은 **외부 이벤트**입니다. 아무 일도 일어나지 않으면 조건 함수는 **한 번도 호출되지 않습니다.** CPU 를 전혀 쓰지 않습니다.

조건 함수에 로그를 넣어 몇 번 호출되는지 세어 봅니다.

```java
private int evalCount = 0;

Workflow.await(() -> {
    log.info("조건 평가 #{} approved={}", ++evalCount, approved);
    return approved;
});
```

```bash
temporal workflow start --type ApprovalWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id order-6101 --input '"6101"'
sleep 30                                            # 30초간 아무 일도 안 함
temporal workflow signal -w order-6101 --name approve
```

**결과** (Worker 콘솔)
```
11:24:03.641 [workflow-method-order-6101-...] INFO  c.e.o.ApprovalWorkflowImpl - [6101] 승인 대기 시작
11:24:03.643 [workflow-method-order-6101-...] INFO  c.e.o.ApprovalWorkflowImpl - 조건 평가 #1 approved=false
11:24:33.881 [workflow-method-order-6101-...] INFO  c.e.o.ApprovalWorkflowImpl - 조건 평가 #2 approved=true
11:24:33.882 [workflow-method-order-6101-...] INFO  c.e.o.ApprovalWorkflowImpl - [6101] 승인 확인. 진행합니다.
```

**30초 동안 조건 평가는 2번**입니다. 처음 await 에 진입할 때 1번, 시그널이 도착해 Workflow Task 가 생겼을 때 1번. 1초마다 평가했다면 30번이었을 것입니다.

히스토리도 마찬가지로 조용합니다.

```
   4  2026-03-11T11:24:03Z  WorkflowTaskCompleted
   5  2026-03-11T11:24:33Z  WorkflowExecutionSignaled     ← 30초 동안 이벤트가 없다가 시그널
   6  2026-03-11T11:24:33Z  WorkflowTaskScheduled
   7  2026-03-11T11:24:33Z  WorkflowTaskStarted
   8  2026-03-11T11:24:33Z  WorkflowTaskCompleted
   9  2026-03-11T11:24:33Z  WorkflowExecutionCompleted
```

`await` 자체는 **이벤트를 하나도 만들지 않습니다.** 타이머와 달리 `TimerStarted` 같은 게 없습니다. 순수하게 워크플로우 코드 내부의 상태입니다.

> ⚠️ **함정 — 조건 함수는 순수해야 합니다**
> 조건 함수는 **리플레이 때마다 다시 호출**되고, 언제 몇 번 호출될지 예측할 수 없습니다. 그래서 조건 함수 안에서는
> **액티비티를 호출하면 안 되고**, 워크플로우 상태를 변경해서도 안 되며, `System.currentTimeMillis()` 같은 비결정적 API 도 안 됩니다.
> ```java
> // 절대 하면 안 되는 코드
> Workflow.await(() -> paymentActivity.isConfirmed(orderId));   // ← 액티비티 호출
> Workflow.await(() -> System.currentTimeMillis() > deadline);  // ← 비결정적
> ```
> 첫 번째는 `IllegalStateException: Cannot execute activity in a callback` 으로 즉시 터지지만,
> 두 번째는 **아무 에러 없이 잘 돌다가** 리플레이 시점에 시각이 달라져 결과가 뒤바뀝니다. 조용히 틀리는 쪽이 더 위험합니다.
> 조건 함수는 **필드를 읽어서 boolean 을 만드는 것**만 하세요. 상태 변경은 시그널 핸들러에서 합니다.

> 💡 **`await` 는 워크플로우 코드에만 있고 히스토리에는 없습니다**
> 그래서 `describe` 를 봐도 "이 워크플로우가 무엇을 기다리는지" 알 수 없습니다. 마지막 이벤트가 `WorkflowTaskCompleted` 인 채로
> Status 가 RUNNING 이면 대개 `await` 에 걸려 있는 것입니다. 무엇을 기다리는지 알고 싶으면 **Query**(Step 07)를 만드세요.

---

## 6-4. 타임아웃 있는 조건 대기

영원히 기다리는 것은 대개 곤란합니다. `Workflow.await(Duration, Supplier)` 는 조건과 타임아웃을 함께 겁니다.

```java
// [6-4] 30분 안에 결제 확인이 안 오면 주문을 취소한다
boolean confirmed = Workflow.await(Duration.ofMinutes(30), () -> paymentConfirmed);

if (confirmed) {
    log.info("[{}] 결제 확인됨. 배송 요청.", orderId);
    return shipping.requestShipment(orderId, address);
} else {
    log.warn("[{}] 30분 내 결제 미확인. 주문 취소.", orderId);
    inventory.release(reservationId);
    notification.notifyCustomer(orderId, "결제 시간이 만료되어 주문이 취소되었습니다.");
    return orderId + " CANCELLED_TIMEOUT";
}
```

**반환값의 의미**

| 반환값 | 의미 |
|---|---|
| `true` | 제한 시간 안에 **조건이 참이 되었다** |
| `false` | **타임아웃되었다** (조건은 여전히 거짓) |

> ⚠️ **함정 — 반환값을 무시하면 타임아웃이 성공으로 둔갑합니다**
> ```java
> Workflow.await(Duration.ofMinutes(30), () -> paymentConfirmed);   // 반환값 버림
> shipping.requestShipment(orderId, address);                       // ← 결제 안 됐는데 배송
> ```
> 컴파일도 되고 에러도 안 납니다. 30분 뒤 그냥 다음 줄로 넘어가서 **결제되지 않은 주문을 배송**합니다.
> `Workflow.await` 의 `Duration` 버전은 **반드시 반환값을 분기에 써야** 합니다.
> 반환값을 안 쓸 거면 `Duration` 없는 버전(무기한 대기)이 오히려 안전합니다.

실습해 봅니다. 타임아웃을 20초로 줄인 버전입니다.

```bash
temporal workflow start --type OrderWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id order-6102 \
  --input '{"orderId":"6102","customerId":"C-1","sku":"SKU-A","qty":1,"amount":39000,"address":"서울시 강남구"}'
# 시그널을 보내지 않고 기다립니다
```

**결과** (Worker 콘솔)
```
11:31:10.412 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [6102] 재고 예약 RSV-6102
11:31:10.418 [workflow-method-order-6102-...] INFO  c.e.order.OrderWorkflowImpl - [6102] 결제 확인 대기 (최대 20초)
11:31:30.622 [workflow-method-order-6102-...] WARN  c.e.order.OrderWorkflowImpl - [6102] 20초 내 결제 미확인. 주문 취소.
11:31:30.803 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [6102] 재고 해제 RSV-6102
11:31:30.951 [Activity Executor ...] INFO  c.e.o.NotificationActivityImpl - [6102] 알림: 결제 시간이 만료되어 주문이 취소되었습니다.
```

**결과** (`temporal workflow show -w order-6102`)
```
   8  2026-03-11T11:31:10Z  ActivityTaskCompleted        (재고 예약)
  11  2026-03-11T11:31:10Z  WorkflowTaskCompleted
  12  2026-03-11T11:31:10Z  TimerStarted                 ← await 의 타임아웃이 타이머로 구현됨
  13  2026-03-11T11:31:30Z  TimerFired
  24  2026-03-11T11:31:30Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-6102 CANCELLED_TIMEOUT"]
```

12번의 `TimerStarted` 가 보입니다. **`await(Duration, ...)` 는 내부적으로 타이머를 씁니다.** 그래서 시간 없는 `await` 와 달리 히스토리에 흔적을 남깁니다. 시그널이 먼저 왔다면 그 타이머는 `TimerCanceled` 로 정리됩니다.

> 💡 **실무 팁 — 결제 대기는 거의 항상 타임아웃이 필요합니다**
> 재고를 예약해 둔 채로 무기한 결제를 기다리면 그 재고는 영원히 묶입니다.
> "N분 안에 결제 확인 시그널, 아니면 예약 해제"는 이커머스에서 가장 흔한 워크플로우 패턴이고,
> Temporal 에서는 위 10줄이 전부입니다. 스케줄러도, 만료 배치도, 상태 컬럼도 필요 없습니다.

---

## 6-5. newTimer — Promise 로서의 타이머

`Workflow.newTimer(Duration)` 은 타이머를 만들되 **기다리지 않고 `Promise<Void>` 를 돌려줍니다.**

```java
Promise<Void> timer = Workflow.newTimer(Duration.ofSeconds(10));
// 여기서는 아직 안 기다립니다. 다른 일을 할 수 있습니다.
log.info("타이머 등록 완료. 다른 작업 진행.");
timer.get();          // 여기서 비로소 기다립니다
log.info("10초 경과.");
```

두 API 의 관계는 정확히 이렇습니다.

```java
Workflow.sleep(d)  ==  Workflow.newTimer(d).get()
```

`Workflow.sleep()` 은 `newTimer().get()` 의 축약입니다. 히스토리에 남는 이벤트도 동일하게 `TimerStarted` + `TimerFired` 입니다.

그럼 `newTimer` 는 왜 필요할까요? **타이머를 다른 Promise 와 경쟁시키기 위해서**입니다. 6-7 에서 씁니다.

```java
// 타이머를 취소할 수도 있습니다
Promise<Void> timer = Workflow.newTimer(Duration.ofMinutes(30));
CancellationScope scope = Workflow.newCancellationScope(() -> timer.get());
scope.cancel();       // 조건이 만족되면 취소 → TimerCanceled 이벤트가 기록됩니다
```

**결과**
```
  12  2026-03-11T11:38:02Z  TimerStarted
  15  2026-03-11T11:38:07Z  TimerCanceled
```

---

## 6-6. 병렬 액티비티 — Async 와 Promise.allOf

지금까지 액티비티는 전부 순차 실행이었습니다. 재고 확인을 창고 3곳에 해야 한다고 합시다.

```java
// [6-6] 순차 실행 — 각 1초씩 걸립니다
String a = inventory.reserve(orderId, "SKU-A", 1);   // 1.0초
String b = inventory.reserve(orderId, "SKU-B", 1);   // 1.0초
String c = inventory.reserve(orderId, "SKU-C", 1);   // 1.0초
```

**결과** (Worker 콘솔)
```
11:42:01.104 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [6103] 창고 조회 SKU-A
11:42:02.118 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [6103] 창고 조회 SKU-B
11:42:03.131 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [6103] 창고 조회 SKU-C
11:42:04.145 [workflow-method-order-6103-...] INFO  c.e.order.OrderWorkflowImpl - [6103] 재고 확인 완료. 소요 3041ms
```

**3,041ms.** 이제 병렬로 바꿉니다.

```java
// [6-6] 병렬 실행
Promise<String> pa = Async.function(inventory::reserve, orderId, "SKU-A", 1);
Promise<String> pb = Async.function(inventory::reserve, orderId, "SKU-B", 1);
Promise<String> pc = Async.function(inventory::reserve, orderId, "SKU-C", 1);

Promise.allOf(pa, pb, pc).get();      // 셋 다 끝날 때까지 대기

String a = pa.get();                  // 이미 완료됐으므로 즉시 반환
String b = pb.get();
String c = pc.get();
```

**결과**
```
11:44:10.221 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.InventoryActivityImpl - [6104] 창고 조회 SKU-A
11:44:10.223 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 2] INFO  c.e.order.InventoryActivityImpl - [6104] 창고 조회 SKU-B
11:44:10.224 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 3] INFO  c.e.order.InventoryActivityImpl - [6104] 창고 조회 SKU-C
11:44:11.318 [workflow-method-order-6104-...] INFO  c.e.order.OrderWorkflowImpl - [6104] 재고 확인 완료. 소요 1102ms
```

**3,041ms → 1,102ms. 약 2.8배 빨라졌습니다.** 세 액티비티가 서로 다른 Activity Executor 스레드에서 같은 밀리초에 시작한 것이 로그에 보입니다.

히스토리를 보면 무슨 일이 일어났는지 명확합니다.

**결과** (`temporal workflow show -w order-6104`)
```
   4  2026-03-11T11:44:10Z  WorkflowTaskCompleted
   5  2026-03-11T11:44:10Z  ActivityTaskScheduled      ← SKU-A
   6  2026-03-11T11:44:10Z  ActivityTaskScheduled      ← SKU-B   3개가 연달아!
   7  2026-03-11T11:44:10Z  ActivityTaskScheduled      ← SKU-C
   8  2026-03-11T11:44:10Z  ActivityTaskStarted
   9  2026-03-11T11:44:10Z  ActivityTaskStarted
  10  2026-03-11T11:44:10Z  ActivityTaskStarted
  11  2026-03-11T11:44:11Z  ActivityTaskCompleted
  12  2026-03-11T11:44:11Z  ActivityTaskCompleted
  13  2026-03-11T11:44:11Z  ActivityTaskCompleted
  14  2026-03-11T11:44:11Z  WorkflowTaskScheduled
```

**`ActivityTaskScheduled` 3개가 5·6·7번에 연달아 붙어 있습니다.** 하나의 Workflow Task(1~4번) 안에서 워크플로우 코드가 세 개의 스케줄 명령을 한꺼번에 서버에 보낸 것입니다. 순차 실행이었다면 `Scheduled → Started → Completed → WorkflowTaskScheduled → ...` 가 세 번 반복되어 이벤트가 훨씬 많았을 것입니다(순차 18개 vs 병렬 14개).

### Async.function vs Async.procedure

| API | 대상 | 반환 |
|---|---|---|
| `Async.function(stub::method, args...)` | 값을 반환하는 액티비티 | `Promise<R>` |
| `Async.procedure(stub::method, args...)` | `void` 액티비티 | `Promise<Void>` |

```java
// void 메서드는 procedure
Promise<Void> p1 = Async.procedure(notification::notifyCustomer, orderId, "접수되었습니다");
Promise<Void> p2 = Async.procedure(notification::notifyCustomer, orderId, "배송 준비 중");
Promise.allOf(p1, p2).get();
```

> ⚠️ **함정 — `Promise.allOf` 없이 `get()` 만 나열하면 병렬이 아닙니다**
> ```java
> String a = Async.function(inventory::reserve, orderId, "SKU-A", 1).get();   // ← 여기서 기다림
> String b = Async.function(inventory::reserve, orderId, "SKU-B", 1).get();   // 그다음 스케줄됨
> ```
> `Async.function(...)` 을 만들자마자 `.get()` 을 붙이면 그 자리에서 완료를 기다리므로, 다음 액티비티는 **그 뒤에야** 스케줄됩니다.
> 순차 실행과 완전히 동일해집니다. 실제로 히스토리를 보면 `ActivityTaskScheduled` 가 연달아 나오지 않고 흩어져 있습니다.
> **Promise 를 전부 먼저 만들고, 그다음에 기다리세요.**

> 💡 **실무 팁 — 하나라도 실패하면?**
> `Promise.allOf(...).get()` 은 **하나라도 실패하면 즉시 그 예외를 던집니다.** 나머지 액티비티는 계속 실행되지만 결과는 버려집니다.
> "실패한 것만 골라내고 성공한 것은 쓰고 싶다"면 `allOf` 대신 각 Promise 를 개별적으로 `get()` 하며 try-catch 하세요.
> 병렬 액티비티 중 일부가 부수효과를 남긴다면 보상(Step 09)이 필요합니다.

---

## 6-7. Promise.anyOf — 먼저 끝나는 쪽

"배송 조회 API 가 10초 넘게 걸리면 기본값을 쓰고 넘어간다." 액티비티 타임아웃(`StartToCloseTimeout`)으로 하면 액티비티가 **실패**하지만, 여기서는 실패시키지 않고 **그냥 포기**하고 싶습니다.

`Promise.anyOf` 로 액티비티와 타이머를 경주시킵니다.

```java
// [6-7] 소프트 타임아웃
Promise<String> lookup = Async.function(shipping::estimateDelivery, orderId, address);
Promise<Void> timeout = Workflow.newTimer(Duration.ofSeconds(10));

Promise.anyOf(lookup, timeout).get();     // 둘 중 먼저 끝나는 쪽까지만 기다림

String eta;
if (lookup.isCompleted()) {               // ← 누가 이겼는지는 isCompleted() 로 판별
    eta = lookup.get();
    log.info("[{}] 배송 예상일 조회 성공: {}", orderId, eta);
} else {
    eta = "3~5일";                        // 기본값
    log.warn("[{}] 배송 조회 10초 초과. 기본값 사용.", orderId);
}
```

> ⚠️ **함정 — `Promise.anyOf` 는 누가 이겼는지 알려 주지 않습니다**
> `Promise.anyOf(a, b)` 의 반환 타입은 `Promise<Object>` 이고, `.get()` 은 **먼저 완료된 Promise 의 값**을 돌려줍니다.
> 그런데 타이머의 값은 `null` 이고 액티비티가 `null` 을 반환할 수도 있어, 반환값만으로는 구분이 안 됩니다.
> **반드시 각 Promise 의 `isCompleted()` 로 판별하세요.** 이것이 `anyOf` 를 쓸 때 가장 자주 틀리는 부분입니다.

**결과** — 조회가 3초 걸린 경우
```
11:52:04.118 [Activity Executor ...] INFO  c.e.o.ShippingActivityImpl - [6105] 배송 예상일 조회
11:52:07.221 [workflow-method-order-6105-...] INFO  c.e.order.OrderWorkflowImpl - [6105] 배송 예상일 조회 성공: 2026-03-14
```

```
   5  2026-03-11T11:52:04Z  ActivityTaskScheduled
   6  2026-03-11T11:52:04Z  TimerStarted            ← 타이머와 액티비티가 함께 시작
   7  2026-03-11T11:52:04Z  ActivityTaskStarted
   8  2026-03-11T11:52:07Z  ActivityTaskCompleted   ← 액티비티가 이김
  13  2026-03-11T11:52:07Z  TimerCanceled           ← 진 타이머는 취소됨
```

**결과** — 조회가 25초 걸리는 경우
```
11:55:12.104 [Activity Executor ...] INFO  c.e.o.ShippingActivityImpl - [6106] 배송 예상일 조회
11:55:22.318 [workflow-method-order-6106-...] WARN  c.e.order.OrderWorkflowImpl - [6106] 배송 조회 10초 초과. 기본값 사용.

   5  2026-03-11T11:55:12Z  ActivityTaskScheduled
   6  2026-03-11T11:55:12Z  TimerStarted
   7  2026-03-11T11:55:12Z  ActivityTaskStarted
   8  2026-03-11T11:55:22Z  TimerFired              ← 타이머가 이김
  14  2026-03-11T11:55:37Z  ActivityTaskCompleted   ← 액티비티는 계속 돌아서 완료됨(결과는 버려짐)
```

> 💡 **진 액티비티는 멈추지 않습니다**
> 타이머가 이겨도 액티비티는 **계속 실행됩니다.** 결과만 버려질 뿐입니다.
> 정말로 중단시키려면 `CancellationScope` 로 감싸 취소해야 하며, 액티비티가 하트비트로 취소를 감지해야 합니다(Step 12).
> 조회처럼 부수효과가 없는 액티비티면 그냥 두어도 되지만, **결제 같은 액티비티에 `anyOf` 타임아웃을 걸면 안 됩니다.**
> 워크플로우는 포기했는데 결제는 성공하는 상황이 생깁니다.

**`allOf` vs `anyOf`**

| | 기다리는 조건 | 실패 시 |
|---|---|---|
| `Promise.allOf(...)` | **전부** 완료 | 하나라도 실패하면 즉시 예외 |
| `Promise.anyOf(...)` | **하나라도** 완료 | 먼저 실패한 것이 있으면 그 예외 |

---

## 6-8. 타이머의 정밀도와 비용

### 정밀도

Temporal 타이머는 **"지정한 시각 이후"** 를 보장하지, 정확히 그 시각을 보장하지 않습니다. 실측하면 보통 수십~수백 ms 늦게 발화합니다.

```java
long before = Workflow.currentTimeMillis();
Workflow.sleep(Duration.ofSeconds(5));
long after = Workflow.currentTimeMillis();
log.info("요청 5000ms, 실제 {}ms", after - before);
```

**결과** (5회 실행)
```
요청 5000ms, 실제 5012ms / 5008ms / 5031ms / 5006ms / 5104ms
```

부하가 높거나 Worker 가 없으면 더 늦어집니다(6-2 의 실험에서는 90초 늦었습니다). **밀리초 단위 정밀도가 필요한 용도에는 쓰면 안 됩니다.** Temporal 타이머는 초 단위 이상의 비즈니스 시간용입니다.

### 비용 — 타이머 하나당 히스토리 이벤트 2개

6-2 에서 확인했듯 타이머 하나는 `TimerStarted` + `TimerFired` = **2개 이벤트**입니다. 그리고 깨어나려면 Workflow Task 3종(`Scheduled`/`Started`/`Completed`)이 따라붙습니다.

이제 흔한 실수를 계산해 봅니다.

```java
// ⚠️ 절대 하지 마세요
while (!done) {
    Workflow.sleep(Duration.ofSeconds(1));
    done = checkStatus();      // 액티비티 호출까지 하면 더 심각합니다
}
```

**하루 동안 돌렸을 때의 이벤트 수**

| 항목 | 반복당 | 하루(86,400회) |
|---|---:|---:|
| `TimerStarted` + `TimerFired` | 2 | **172,800** |
| Workflow Task 3종 | 3 | 259,200 |
| **합계 (액티비티 없이)** | **5** | **432,000** |
| 액티비티까지 호출하면 (+3) | 8 | 691,200 |

**타이머 이벤트만 세도 하루 172,800개**입니다. Temporal 의 기본 제한과 비교하면 즉시 문제가 보입니다.

| 제한 | 기본값 | 위 루프가 도달하는 시점 |
|---|---:|---|
| 히스토리 이벤트 경고 | 10,240개 | **약 34분** |
| 히스토리 이벤트 강제 종료 | 51,200개 | **약 2시간 50분** |
| 히스토리 크기 경고 | 10 MB | 수 시간 |
| 히스토리 크기 강제 종료 | 50 MB | 하루를 못 넘김 |

**결과** (제한에 걸린 워크플로우)
```
Execution Info:
  Workflow Id       order-6107
  Status            TERMINATED
  History Length    51203
  Reason            history size exceeds limit
```

> ⚠️ **함정 — 1초 폴링 루프는 워크플로우를 죽입니다**
> 히스토리가 커지면 세 가지가 동시에 나빠집니다.
> **(1)** 리플레이 비용이 커집니다. Worker 가 재기동되어 이 워크플로우를 복원할 때 5만 개 이벤트를 처음부터 다시 읽습니다.
> **(2)** Workflow Task 처리 시간이 늘어납니다. 이벤트 8개짜리 워크플로우는 12ms 면 처리되지만, 5만 개짜리는 3초 넘게 걸립니다.
> **(3)** 결국 서버가 강제 종료시킵니다. 실행 중이던 비즈니스가 통째로 날아갑니다.
>
> **해결책은 폴링을 하지 않는 것입니다.**
> - 외부 시스템이 알려 줄 수 있으면 → **Signal** 로 밀어 넣기 (Step 07)
> - 정말 폴링해야 하면 → **액티비티 안에서** 루프를 돌고 하트비트를 보내기 (액티비티 내부는 히스토리를 안 만듭니다)
> - 긴 주기의 반복이 본질이면 → **Continue-As-New** 로 히스토리를 잘라 내기 (Step 08)
>
> 폴링 주기를 1초에서 1분으로 늘리는 것은 임시방편일 뿐입니다. 1분 폴링도 하루 7,200 이벤트라 **이틀이면 경고선**에 닿습니다.

> 💡 **실무 팁 — 히스토리 길이를 알림으로 감시하세요**
> `temporal workflow list --query 'ExecutionStatus="Running"'` 로 도는 워크플로우를 뽑고 `describe` 로 `History Length` 를 보면
> 폭주하는 워크플로우를 미리 찾을 수 있습니다. 운영 관점의 감시는 Step 12 에서 다룹니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `Workflow.sleep(d)` | 스레드를 점유하지 않음. **워크플로우가 메모리에서 사라지고 서버에 타이머만 남음** |
| 30일 sleep | Status RUNNING, **History Length 5, 897바이트**. Worker 메모리 사용 0 |
| 내구성 | Worker 를 다 죽여도, 재배포해도, 서버가 재시작해도 타이머는 살아 있음 |
| 타이머 히스토리 | `TimerStarted`(`startToFireTimeout`) → `TimerFired` → `WorkflowTaskScheduled` |
| `Workflow.await(cond)` | 조건이 참이 될 때까지 대기. **히스토리 이벤트를 만들지 않음** |
| 조건 평가 시점 | 폴링이 아님. **새 Workflow Task 마다 한 번씩만** 평가 |
| 조건 함수 규칙 | **순수해야 함.** 액티비티 호출·상태 변경·비결정적 API 금지 |
| `await(d, cond)` | `true`=조건 충족, `false`=타임아웃. **반환값을 반드시 분기에 쓸 것** |
| `Workflow.newTimer(d)` | `Promise<Void>` 반환. `sleep(d)` == `newTimer(d).get()` |
| `Async.function/procedure` | 액티비티를 즉시 스케줄하고 `Promise` 반환 |
| `Promise.allOf` | 전부 완료까지 대기. **순차 3,041ms → 병렬 1,102ms (2.8배)** |
| 병렬의 히스토리 흔적 | `ActivityTaskScheduled` 가 **연달아** 붙어 있음 |
| `Promise.anyOf` | 먼저 끝나는 쪽. **누가 이겼는지는 `isCompleted()` 로 판별** |
| 진 액티비티 | 취소되지 않고 계속 실행됨. 부수효과 있는 액티비티에 쓰지 말 것 |
| 타이머 정밀도 | 수십~수백 ms 오차. 초 단위 이상의 비즈니스 시간용 |
| 타이머 비용 | 1개당 이벤트 2개(+Workflow Task 3개). **1초 폴링 = 하루 172,800개** |
| 히스토리 한계 | 경고 10,240개 / 강제 종료 51,200개. 1초 폴링은 **2시간 50분**에 죽음 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 90일 뒤 만료되는 워크플로우를 만들고 `TimerStarted` 의 `startToFireTimeout` 값 확인하기
2. Worker 를 죽였다 살려도 타이머가 이어지는 것을 재현하고 히스토리로 증명하기
3. `Workflow.await` 조건 함수가 몇 번 평가되는지 세어 보고, 폴링이 아님을 설명하기
4. "15초 안에 승인 시그널, 아니면 자동 반려" 를 `await(Duration, ...)` 로 구현하기
5. 액티비티 4개를 병렬로 실행하고 순차 대비 소요 시간 비교하기
6. `Promise.anyOf` 로 소프트 타임아웃을 구현하되, `isCompleted()` 누락 버그를 고치기

---

## 다음 단계

이 스텝에서 `Workflow.await` 를 쓰면서 `approved` 같은 필드를 밖에서 바꾸는 코드가 계속 나왔습니다. 그것이 **Signal** 입니다. 실행 중인 워크플로우에 값을 밀어 넣는 Signal, 상태를 꺼내 읽는 Query, 그리고 둘을 합쳐 응답까지 받는 Update — 워크플로우와 외부 세계가 대화하는 세 가지 통로를 다음 스텝에서 다룹니다.

→ [Step 07 — Signal · Query · Update](../step-07-signal-query-update/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 따라 실행하며 6-1 ~ 6-8 의 모든 측정을 재현하고, `Exercise.java` 의 6문제를 직접 풀어 본 뒤, `Solution.java` 로 정답과 해설을 대조합니다. 이 스텝은 **시간을 다루므로 실행 순서와 대기 시간이 중요합니다.** 로그의 타임스탬프를 반드시 눈으로 확인하세요.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 한 파일에 모아 둔 실습 코드입니다.

- `./gradlew run -PmainClass=com.example.order.Practice --args="6-6"` 형태로 **절 단위 실행**을 지원합니다. `6-1` 은 30일 타이머를 등록하고 바로 종료하므로 몇 초 만에 끝나고, `6-6` 은 약 5초(순차 3초 + 병렬 1.1초), `6-8` 은 60초가 걸립니다.
- `[6-1]` 은 `sub-C-1001`(30일) 을 띄우고 **기다리지 않고 프로세스를 끝냅니다.** 이게 핵심입니다 — 클라이언트도 Worker 도 없는데 워크플로우는 RUNNING 입니다. `temporal workflow describe -w sub-C-1001` 로 History Length 5 를 꼭 확인하세요. 실습이 끝나면 `temporal workflow terminate -w sub-C-1001` 로 정리합니다.
- `[6-2]` 의 Worker 재기동 실험은 코드만으로는 재현되지 않습니다. 파일 주석에 적힌 `pkill -f OrderWorker` → `sleep 90` → `./gradlew runWorker` 순서를 **터미널 두 개를 열어** 직접 실행해야 합니다. 이 스텝에서 가장 인상적인 실험이니 건너뛰지 마세요.
- `[6-3]` 의 `evalCount` 필드는 조건 함수가 몇 번 호출되는지 세는 계수기입니다. 30초를 기다렸다가 시그널을 보내면 **2** 가 나옵니다. 여기에 `Workflow.sleep` 을 끼워 넣어 Workflow Task 를 더 만들면 숫자가 어떻게 변하는지 실험해 보세요.
- `[6-6]` 은 같은 액티비티를 순차·병렬로 각각 돌려 `System.out` 에 소요 시간을 나란히 찍습니다. Worker 의 `maxConcurrentActivityExecutionSize` 가 3보다 작으면 병렬 효과가 안 나므로, 파일 상단에서 `WorkerOptions` 로 10 을 지정해 두었습니다.
- `[6-7]` 의 `ShippingActivityImpl` 은 `orderId` 끝자리가 홀수면 3초, 짝수면 25초를 소비합니다. 두 경우를 다 돌려 액티비티가 이기는 히스토리와 타이머가 이기는 히스토리를 비교하세요.
- `[6-8]` 의 `PollingWorkflowImpl` 은 **의도적으로 나쁜 코드**입니다. 1초 폴링을 60초만 돌려 히스토리가 300개 넘게 불어나는 것을 보여 줍니다. 실제 하루치인 432,000개를 실습할 수는 없으니 60초분을 보고 곱셈으로 감을 잡는 구조입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1** 은 `Duration.ofDays(90)` 을 초로 환산한 값(7,776,000)을 상수에 적고, 실제 `startToFireTimeout` 과 일치하는지 `jq` 로 확인하는 문제입니다. 계산이 맞아야 `Q1 PASS` 가 찍힙니다.
- **문제 2** 는 코드가 아니라 **절차**를 요구합니다. 파일이 워크플로우를 띄워 주면 여러분이 Worker 를 죽이고, 시간을 보내고, 다시 띄운 뒤 관찰한 이벤트 번호와 시각을 주석에 채워 넣습니다.
- **문제 4** 의 `Q4Workflow` 는 `Workflow.await(Duration, ...)` 의 **반환값을 버리는** 코드로 시작합니다. 그대로 실행하면 승인하지 않았는데도 "승인됨"으로 끝납니다. 실행해서 이 잘못된 결과를 먼저 본 뒤에 고치세요.
- **문제 5** 는 액티비티 4개(각 1.5초)를 다룹니다. 순차는 약 6초, 병렬은 약 1.6초가 나와야 정답입니다. `Promise.allOf` 를 빠뜨리고 `.get()` 을 바로 붙이면 병렬이 안 되니 히스토리에서 `ActivityTaskScheduled` 4개가 연달아 있는지 확인하세요.
- **문제 6** 의 `Q6Workflow` 는 `Promise.anyOf(...).get()` 의 **반환값으로 승자를 판별하려 합니다.** 타이머가 이기면 `null` 이 나와 NPE 가 나거나, 액티비티가 이겨도 엉뚱한 분기로 갑니다. `isCompleted()` 로 고치는 것이 정답입니다.
- 파일 끝의 `cleanup()` 이 이 문제지가 만든 `order-62xx` / `sub-Q1` 워크플로우 정리 명령을 출력합니다. 문제 1 의 90일 워크플로우는 반드시 terminate 하세요. 방치하면 90일 뒤에 깨어납니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 90일 = 7,776,000초라는 계산과 함께, 이 값이 워크플로우 코드가 아니라 **서버에 저장된다**는 점을 강조합니다. Worker 를 90일간 켜 둘 필요가 없다는 것이 요점입니다.
- **정답 3** 은 조건 함수가 2번 평가되는 이유를 이벤트 단위로 설명합니다 — `await` 진입 시 1번, `WorkflowExecutionSignaled` 로 생긴 Workflow Task 에서 1번. 여기에 액티비티를 하나 끼워 넣으면 그 완료 시점에도 평가되어 3번이 된다는 것까지 실험으로 보여 줍니다.
- **정답 4** 의 핵심은 한 줄입니다: `boolean approved = Workflow.await(Duration.ofSeconds(15), () -> this.approved);` — 반환값을 받는 것. 주석에서는 이 함정이 왜 리뷰에서 잘 안 잡히는지(컴파일 경고도 없고 정상 경로 테스트는 통과한다) 설명합니다.
- **정답 5** 는 병렬화의 상한을 짚습니다. Worker 의 `maxConcurrentActivityExecutionSize` 가 실질적 병렬도를 결정하므로, 액티비티 100개를 `allOf` 로 던져도 동시 실행 수는 Worker 설정만큼입니다. 무한정 빨라지지 않는다는 점을 수치로 설명합니다.
- **정답 6** 은 `isCompleted()` 로 판별하는 코드와 함께, **진 액티비티가 취소되지 않는다**는 사실을 히스토리로 보여 줍니다. 타이머가 이긴 뒤에도 `ActivityTaskCompleted` 가 나중에 찍히는 것을 확인하고, 그래서 결제 같은 액티비티에는 이 패턴을 쓰면 안 된다는 결론까지 이어집니다.
- 모든 정답 블록 끝에 검증용 `temporal` 명령이 주석으로 붙어 있습니다. 특히 `ActivityTaskScheduled` 의 연속 여부를 세는 `jq` 한 줄은 병렬 실행 여부를 판정하는 실무 도구로 그대로 쓸 수 있습니다.

```java file="./Solution.java"
```
