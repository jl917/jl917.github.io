# Step 07 — Signal · Query · Update

> **학습 목표**
> - Signal / Query / Update 세 가지 상호작용의 차이를 히스토리 기록 여부·동기성·반환값 기준으로 구분한다
> - `@SignalMethod` 로 실행 중인 워크플로우에 "주문 취소" 를 주입하고, `Workflow.await` 가 깨어나는 것을 히스토리로 확인한다
> - Java 클라이언트 · `WorkflowStub.signal` · `temporal workflow signal` CLI 세 경로로 시그널을 보내고 `WorkflowExecutionSignaled` 이벤트를 눈으로 본다
> - `signalWithStart` 로 "없으면 시작, 있으면 시그널" 경쟁 조건을 제거한다
> - **Query 핸들러에서 상태를 바꾸면 Worker 재시작 후 값이 되돌아가는 것을 실측으로 재현한다**
> - `@UpdateMethod` / `@UpdateValidatorMethod` 로 거절된 요청이 히스토리에 남지 않는 것을 확인한다
> - 워크플로우 종료 직전 시그널 유실을 재현하고 `Workflow.isEveryHandlerFinished()` 드레인 패턴으로 막는다
>
> **선행 스텝**: Step 06 — 타이머와 대기
> **예상 소요**: 100분

---

## 7-0. 실습 준비

Step 01 의 환경을 그대로 쓰고, 이 스텝의 워커를 별도 터미널에 띄워 둡니다.

```bash
docker compose ps
./gradlew run -PmainClass=com.example.order.step07.Practice
```

**결과**
```
NAME                IMAGE                              STATUS         PORTS
temporal            temporalio/auto-setup:1.22.4       Up 3 minutes   0.0.0.0:7233->7233/tcp
temporal-ui         temporalio/ui:2.21.3               Up 3 minutes   0.0.0.0:8233->8080/tcp

09:41:02.512 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
09:41:02.520 [main] INFO  c.e.order.step07.Practice - Worker started. ORDER_TASK_QUEUE
```

---

## 7-1. 세 가지 상호작용 — 무엇을 언제 쓰는가

워크플로우는 한 번 시작하면 끝날 때까지 **바깥과 완전히 단절된 채** 돌지 않습니다. 외부에서 개입할 통로가 세 개 있습니다.

| | **Signal** | **Query** | **Update** |
|---|---|---|---|
| 방향 | 쓰기 (입력 주입) | 읽기 | 쓰기 + 읽기 |
| 동기성 | 비동기 (fire-and-forget) | 동기 | 동기 |
| 반환값 | **없음** (`void` 강제) | 있음 | 있음 |
| 히스토리 기록 | **남는다** (`WorkflowExecutionSignaled`) | **안 남는다** | **남는다** (`WorkflowExecutionUpdateAccepted/Completed`) |
| 상태 변경 | 허용 | **금지** | 허용 |
| 액티비티 호출 | 가능 (단, 7-8 주의) | **불가** | 가능 |
| 검증 후 거절 | 불가 (이미 히스토리에 기록됨) | — | 가능 (`@UpdateValidatorMethod`) |
| Worker 필요 | 아니오 (서버가 받아 적음) | **예** (리플레이해야 답함) | 예 |
| 최소 버전 | 1.0 | 1.0 | **1.21+** (Java SDK 1.22.3 에서 정식) |

판단 기준은 단순합니다.

- **결과를 안 기다려도 되면 Signal.** "취소해 주세요" 를 던지고, 실제 취소 여부는 워크플로우가 알아서 판단합니다.
- **상태를 들여다보기만 하면 Query.** 진행률, 현재 단계, 누적 금액.
- **보내고 나서 "받아들여졌는지" 를 그 자리에서 알아야 하면 Update.** "배송지를 바꿔 주고, 바꿔진 최종 주소를 돌려 달라."

> 💡 **왜 Signal 은 반환값이 없는가**
> 시그널은 서버가 히스토리에 적는 순간 성공입니다. 워크플로우 코드는 아직 실행되지도 않았을 수 있습니다.
> 워커가 전부 죽어 있어도 시그널 전송은 성공합니다 — 워커가 살아나면 그때 처리됩니다.
> "적어 두는 것" 과 "처리하는 것" 이 분리돼 있어서 반환값이 원리적으로 불가능합니다. Update 는 이 분리를 없앤 대신 워커가 살아 있어야 합니다.

---

## 7-2. `@SignalMethod` — 실행 중인 워크플로우에 입력을 주입한다

주문 워크플로우가 배송 준비 중일 때 고객이 취소를 요청하는 시나리오입니다.

```java
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String processOrder(OrderRequest req);

    // [7-2] 외부에서 취소를 요청한다. 반환값은 반드시 void.
    @SignalMethod
    void cancelRequested(String reason);
}
```

구현부입니다. **시그널 핸들러는 필드만 바꾸고 즉시 끝냅니다.**

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    private boolean cancelled = false;
    private String cancelReason = null;
    private String stage = "RECEIVED";

    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build());

    @Override
    public String processOrder(OrderRequest req) {
        stage = "PAYMENT";
        String paymentId = payment.charge(req.orderId(), req.amount());

        stage = "WAITING_WAREHOUSE";

        // [7-2] 시그널이 오거나 30분이 지나면 깨어난다
        boolean signalled = Workflow.await(Duration.ofMinutes(30), () -> cancelled);

        if (signalled) {
            stage = "CANCELLING";
            payment.refund(paymentId);
            stage = "CANCELLED";
            return req.orderId() + " CANCELLED (" + cancelReason + ")";
        }

        stage = "SHIPPED";
        return req.orderId() + " COMPLETED";
    }

    // [7-2] 시그널 핸들러 — 필드 두 개만 바꾸고 끝. 논블로킹.
    @Override
    public void cancelRequested(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
    }
}
```

```
  1. 워크플로우가 Workflow.await(...) 에서 멈춘다 (워커 스레드는 반환됨, 아무 자원도 안 씀)
  2. 서버가 시그널을 받아 히스토리에 WorkflowExecutionSignaled 를 적는다
  3. 서버가 WorkflowTaskScheduled 를 붙여 워커를 깨운다
  4. 워커가 히스토리를 리플레이 → cancelRequested("...") 를 재실행 → cancelled = true
  5. await 의 조건식이 재평가된다 → true → 워크플로우가 다음 줄로 진행
```

핵심은 **4번의 "재실행"** 입니다. 시그널 핸들러는 리플레이 때마다 히스토리에 적힌 순서 그대로 다시 호출됩니다. 그래서 시그널의 효과는 완벽히 재현됩니다. (이 성질이 없는 Query 가 7-6 의 함정입니다.)

> ⚠️ **함정 — 시그널 핸들러에서 블로킹하지 마십시오**
> `cancelRequested` 안에서 액티비티를 호출하거나 `Workflow.sleep` 을 하면, 그 핸들러가 끝날 때까지
> **같은 워크플로우의 다른 시그널 처리가 전부 밀립니다.** 워크플로우는 단일 스레드로 돌기 때문입니다.
> 시그널 100개가 몰려 있고 각 핸들러가 2초짜리 액티비티를 호출하면 200초 동안 워크플로우 전체가 멈춥니다.
> **원칙: 핸들러는 필드 대입만 한다. 무거운 일은 메인 워크플로우 메서드가 `await` 로 깨어나 처리한다.**

---

## 7-3. 시그널을 보내는 세 가지 방법

먼저 워크플로우를 하나 띄웁니다.

```bash
temporal workflow start \
  --task-queue ORDER_TASK_QUEUE \
  --type OrderWorkflow \
  --workflow-id order-1001 \
  --input '{"orderId":"1001","customerId":"C-77","sku":"SKU-A","qty":1,"amount":39000,"address":"서울시 강남구 테헤란로 1"}'
```

**결과**
```
Running execution:
  WorkflowId  order-1001
  RunId       5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
  Type        OrderWorkflow
  Namespace   default
  TaskQueue   ORDER_TASK_QUEUE
```

### (1) 타입 세이프 스텁 — 가장 권장

```java
// 이미 실행 중인 워크플로우를 workflowId 로 붙잡는다
OrderWorkflow stub = client.newWorkflowStub(OrderWorkflow.class, "order-1001");
stub.cancelRequested("customer changed mind");
```

인터페이스 메서드를 그대로 호출하므로 **컴파일 타임에 시그널 이름과 인자 타입이 검증됩니다.** 오타가 나면 빌드가 깨집니다.

### (2) 이름 기반 `WorkflowStub` — 시그널 이름이 런타임에 정해질 때

```java
WorkflowStub untyped = client.newUntypedWorkflowStub("order-1001");
untyped.signal("cancelRequested", "customer changed mind");
```

컴파일 검증이 없습니다. **이름을 틀려도 예외가 나지 않고**, 서버는 그 이름의 시그널을 히스토리에 그냥 적습니다. 워크플로우 쪽에는 해당 핸들러가 없으니 조용히 무시됩니다(`UnhandledSignal` 로 로그만 남습니다). 인터페이스가 있으면 (1)을 쓰십시오.

### (3) CLI — 운영 중 급할 때

```bash
temporal workflow signal \
  --workflow-id order-1001 \
  --name cancelRequested \
  --input '"customer changed mind"'
```

**결과**
```
Signal workflow succeeded

  WorkflowId  order-1001
  RunId       5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
```

`--input` 은 **JSON** 입니다. 문자열 하나를 보낼 때 `--input 'customer changed mind'` 라고 쓰면 JSON 파싱 에러가 납니다. 반드시 따옴표를 이중으로 감싼 `'"customer changed mind"'` 형태여야 합니다.

**결과** (워커 콘솔)
```
09:44:31.207 [workflow-method-order-1001-5c2f8e1a] INFO  c.e.o.step07.OrderWorkflowImpl - [1001] 취소 요청 수신: customer changed mind
09:44:31.318 [Activity Executor taskQueue="ORDER_TASK_QUEUE"] INFO  c.e.o.step07.PaymentActivityImpl - [1001] 환불 pay-8f21a3
```

### 히스토리 확인

```bash
temporal workflow show -w order-1001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:42:18Z  WorkflowExecutionStarted
   ...
  10  2026-03-11T09:42:19Z  WorkflowTaskCompleted
  11  2026-03-11T09:42:19Z  TimerStarted
  12  2026-03-11T09:44:31Z  WorkflowExecutionSignaled       ← 시그널이 히스토리에 남았다
  13  2026-03-11T09:44:31Z  WorkflowTaskScheduled
  14  2026-03-11T09:44:31Z  WorkflowTaskStarted
  15  2026-03-11T09:44:31Z  WorkflowTaskCompleted
  16  2026-03-11T09:44:31Z  TimerCanceled                   ← await 가 조건으로 깨어나 타이머 정리
  17  2026-03-11T09:44:31Z  ActivityTaskScheduled           ← 환불 액티비티
  ...
  23  2026-03-11T09:44:32Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["1001 CANCELLED (customer changed mind)"]
```

12번 이벤트를 JSON 으로 자세히 봅니다.

```bash
temporal workflow show -w order-1001 --output json | jq '.events[] | select(.eventType=="EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED")'
```

**결과**
```json
{
  "eventId": "12",
  "eventTime": "2026-03-11T09:44:31.184Z",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED",
  "taskId": "1048651",
  "workflowExecutionSignaledEventAttributes": {
    "signalName": "cancelRequested",
    "input": {
      "payloads": [
        {
          "metadata": { "encoding": "anNvbi9wbGFpbg==" },
          "data": "ImN1c3RvbWVyIGNoYW5nZWQgbWluZCI="
        }
      ]
    },
    "identity": "51234@macbook@",
    "header": {}
  }
}
```

`signalName`, `input`(base64 로 인코딩된 `"customer changed mind"`), 보낸 주체(`identity`) 가 **영구히 기록됩니다.** 6개월 뒤에 "누가 언제 이 주문을 취소했나" 를 이 히스토리 하나로 답할 수 있습니다. 이것이 Signal 의 감사(audit) 가치입니다.

---

## 7-4. `signalWithStart` — 없으면 시작하고 있으면 시그널만

고객이 첫 상품을 담을 때는 워크플로우가 없으니 시작해야 하고, 두 번째부터는 이미 있는 워크플로우에 시그널만 보내야 합니다. 순진하게 짜면 이렇습니다.

```java
// ⚠️ 이렇게 하면 안 됩니다
try {
    client.newWorkflowStub(CartWorkflow.class, "cart-C77").addItem(item);   // 있으면 시그널
} catch (WorkflowNotFoundException e) {
    CartWorkflow cw = client.newWorkflowStub(CartWorkflow.class, options);
    WorkflowClient.start(cw::run);                                          // 없으면 시작
    cw.addItem(item);
}
```

두 요청이 동시에 들어오면 둘 다 `WorkflowNotFoundException` 을 받고 둘 다 `start` 를 시도합니다. 하나는 `WorkflowExecutionAlreadyStartedException` 으로 실패하고 그 요청의 `addItem` 은 사라집니다. **경쟁 조건입니다.**

`signalWithStart` 는 이 둘을 **서버 쪽 원자적 연산 하나**로 만듭니다.

```java
// [7-4] 없으면 시작 + 시그널, 있으면 시그널만 — 원자적
CartWorkflow cart = client.newWorkflowStub(CartWorkflow.class,
        WorkflowOptions.newBuilder()
                .setTaskQueue(ORDER_TASK_QUEUE)
                .setWorkflowId("cart-C77")
                .build());

BatchRequest batch = client.newSignalWithStartRequest();
batch.add(cart::run, "C-77");                    // 워크플로우 메서드 + 인자
batch.add(cart::addItem, new Item("SKU-A", 2));  // 시그널 메서드 + 인자
client.signalWithStart(batch);
```

두 번 연달아 실행하고 히스토리를 봅니다.

**결과** (`temporal workflow show -w cart-C77`)
```
Progress:
  ID          Time                     Type
   1  2026-03-11T10:02:11Z  WorkflowExecutionStarted
   2  2026-03-11T10:02:11Z  WorkflowExecutionSignaled       ← 시작과 동시에 시그널이 먼저 기록
   3  2026-03-11T10:02:11Z  WorkflowTaskScheduled
   4  2026-03-11T10:02:11Z  WorkflowTaskStarted
   5  2026-03-11T10:02:11Z  WorkflowTaskCompleted
   6  2026-03-11T10:02:44Z  WorkflowExecutionSignaled       ← 2회차: 새 워크플로우가 아니라 시그널만 추가
   7  2026-03-11T10:02:44Z  WorkflowTaskScheduled
   8  2026-03-11T10:02:44Z  WorkflowTaskStarted
   9  2026-03-11T10:02:44Z  WorkflowTaskCompleted
```

이벤트 2번이 이벤트 1번 **바로 다음**에 있다는 점이 중요합니다. `WorkflowTaskScheduled`(3번) 보다 앞이므로, 워크플로우 코드가 단 한 줄도 실행되기 전에 시그널이 이미 큐에 들어가 있습니다. 첫 아이템이 유실될 여지가 없습니다.

> 💡 **실무 팁 — signalWithStart 가 어울리는 패턴**
> **장바구니 / 세션 집계**(워크플로우 ID = 고객 ID), **배치 윈도우 집계**(ID = `metrics-2026-03-11-10`), **디바이스 상태 머신**(ID = 디바이스 ID).
> 공통점은 **"호출부가 워크플로우 생명주기를 몰라도 되게 만든다"** 는 것입니다.

CLI 에도 같은 기능이 있습니다.

```bash
temporal workflow signal-with-start \
  --workflow-id cart-C77 --type CartWorkflow --task-queue ORDER_TASK_QUEUE \
  --name addItem --input '{"sku":"SKU-B","qty":1}' \
  --workflow-input '"C-77"'
```

---

## 7-5. `@QueryMethod` — 상태를 들여다본다

진행률 조회는 시그널로 못 합니다(반환값이 없으니까). Query 를 씁니다.

```java
// 인터페이스에 추가
@QueryMethod
String getStatus();

@QueryMethod
OrderProgress getProgress();

// 구현 — 필드를 읽기만 한다
@Override
public String getStatus() {
    return stage;
}

@Override
public OrderProgress getProgress() {
    return new OrderProgress(stage, completedSteps, totalSteps, cancelled);
}
```

```bash
temporal workflow query -w order-1002 --type getStatus
temporal workflow query -w order-1002 --type getProgress
```

**결과**
```
Query result:
  QueryResult  "WAITING_WAREHOUSE"

Query result:
  QueryResult  {
    "stage": "WAITING_WAREHOUSE",
    "completedSteps": 2,
    "totalSteps": 5,
    "cancelled": false
  }
```

Java 클라이언트에서는 `client.newWorkflowStub(OrderWorkflow.class, "order-1002").getStatus()` 로 똑같이 호출합니다. 동기 호출이라 즉시 값이 옵니다.

### Query 는 어떻게 답하는가 — 리플레이

**서버는 워크플로우의 필드 값을 모릅니다.** 서버가 가진 것은 이벤트 히스토리뿐입니다. Query 요청이 오면 서버는 이렇게 합니다.

```
  1. 클라이언트 → 서버: "order-1002 의 getStatus 를 알려 줘"
  2. 서버 → 워커: 히스토리 전체 + "이거 리플레이하고 getStatus() 를 호출해서 결과를 줘"
  3. 워커: 워크플로우 객체를 새로 만들어 히스토리를 처음부터 재생 → 필드가 현재 상태로 복원됨
  4. 워커: getStatus() 호출 → "WAITING_WAREHOUSE"
```

여기서 두 가지 결론이 나옵니다.

**(1) 워커가 살아 있어야 Query 가 됩니다.** 워커를 전부 내리고 Query 를 해 보십시오.

```bash
temporal workflow query -w order-1002 --type getStatus   # 워커 종료 후
```

**결과**
```
Error: unable to query workflow: context deadline exceeded
('export TEMPORAL_CLI_SHOW_STACKS=1' to see stack traces)
```

시그널은 워커가 죽어 있어도 성공하지만, Query 는 실패합니다. 운영 대시보드를 Query 로 만들었다면 **워커 배포 중에 대시보드가 죽습니다.**

**(2) Query 는 히스토리에 아무것도 남기지 않습니다.**

```bash
temporal workflow describe -w order-1002
```

**결과**
```
Execution Info:
  Workflow Id       order-1002
  Run Id            9a1b7c33-0e42-4d18-b6f0-71c2ae5d3388
  Type              OrderWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Status            RUNNING
  History Length    11
  History Size      1682
```

Query 를 20번 더 해도 `History Length` 는 11 그대로입니다. **이 성질이 편리함의 원천이자, 다음 절 함정의 원인입니다.**

> 💡 **실무 팁 — Query 는 싸지 않습니다**
> "히스토리에 안 남으니 공짜" 가 아닙니다. Query 한 번마다 워커가 히스토리를 리플레이합니다. 이벤트 4만 개짜리 워크플로우를 초당 100번 Query 하면 워커가 마비됩니다.
> 스티키 캐시(`WorkerOptions.setMaxCachedWorkflows`) 에 살아 있으면 리플레이를 건너뛰지만, 캐시가 밀리면 매번 전체 리플레이입니다.
> 고빈도 상태 조회가 필요하면 Query 대신 **워크플로우가 액티비티로 외부 저장소에 상태를 밀어 넣는** 편이 낫습니다.

---

## 7-6. ⚠️ Query 에서 상태를 바꾸면 안 되는 이유

이 스텝에서 가장 중요한 절입니다. Query 핸들러가 필드를 바꾸는 코드를 실제로 만들어 봅니다.

```java
private int viewCount = 0;

// ⚠️ 절대 이렇게 하지 마십시오
@Override
public String getStatusBad() {
    viewCount++;                       // Query 핸들러에서 상태 변경!
    return stage + " (조회 " + viewCount + "회)";
}
```

"조회수를 세고 싶었을 뿐" 인 코드입니다. 컴파일도 되고, 실행도 되고, 처음에는 잘 동작합니다.

```bash
for i in 1 2 3; do temporal workflow query -w order-1003 --type getStatusBad; done
```

**결과**
```
Query result:
  QueryResult  "WAITING_WAREHOUSE (조회 1회)"
Query result:
  QueryResult  "WAITING_WAREHOUSE (조회 2회)"
Query result:
  QueryResult  "WAITING_WAREHOUSE (조회 3회)"
```

잘 되는 것처럼 보입니다. 여기서 **워커를 Ctrl+C 후 재시작하고** 다시 조회합니다.

```bash
temporal workflow query -w order-1003 --type getStatusBad
```

**결과**
```
Query result:
  QueryResult  "WAITING_WAREHOUSE (조회 1회)"
```

**3 → 1 로 되돌아갔습니다.** 에러는 하나도 없습니다.

`viewCount++` 는 워커 메모리 안의 객체에만 일어난 일이고, **히스토리에는 흔적이 없습니다.** 워커가 재시작되면 워크플로우 객체는 히스토리만 가지고 다시 만들어지므로 `viewCount` 는 초기값 0 에서 출발합니다. 워커 재시작뿐 아니라 스티키 캐시 축출·다른 워커로의 이동 때도 똑같이 되돌아갑니다.

그 필드를 워크플로우 로직이 읽는 순간 **결정성이 붕괴합니다.**

```java
// ⚠️ 최악의 조합 — Query 가 바꾼 값을 워크플로우 로직이 읽는다
if (viewCount > 10) {                     // Query 호출 횟수에 따라 분기가 달라진다
    notification.notifyCustomer(req.orderId(), "관심이 많으시네요");
}
shipping.requestShipment(req.orderId(), req.address());
```

첫 실행 때 `viewCount` 가 12 여서 `notifyCustomer` 가 스케줄되고 히스토리에 남았다고 합시다. 나중에 리플레이하면 `viewCount` 는 0 이므로 그 분기를 타지 않고 바로 `requestShipment` 를 스케줄하려 합니다. 히스토리는 `notifyCustomer` 를 기대하는데 코드는 `requestShipment` 를 내놓습니다.

**결과** (워커 콘솔 — 리플레이 시점)
```
10:31:07.442 [workflow-method-order-1003-...] ERROR i.t.i.s.WorkflowExecutionHandler - Workflow execution failure
io.temporal.worker.NonDeterministicException: Failure handling event 24 of type 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED'
  during replay. Event 24 does not match the command SCHEDULE_ACTIVITY_TASK with activity type
  'RequestShipment'. Expected activity type 'NotifyCustomer'.
```

워크플로우는 여기서 멈춥니다. Workflow Task 가 계속 실패하며 재시도됩니다.

```bash
temporal workflow describe -w order-1003
```

**결과**
```
Execution Info:
  Workflow Id       order-1003
  Status            RUNNING
  History Length    41

Pending Workflow Task:
  State                  Started
  Attempt                7
  Last Failure           NonDeterministicException: Failure handling event 24 ...
```

`Attempt 7` — 계속 재시도 중입니다. **Status 는 RUNNING 이라 모니터링에는 정상으로 보입니다.** 이것이 이 코스가 말하는 "에러 없이 조용히 멈춘 워크플로우" 입니다.

### SDK 는 막아 주지 않는가

Java SDK 1.22.3 은 Query 핸들러가 **커맨드를 생성하려 하면**(액티비티 호출, 타이머 시작, 자식 워크플로우 시작 등) 즉시 막습니다. Query 안에서 `payment.charge("x", 1)` 을 호출하면:

**결과**
```
Error: unable to query workflow: QueryWorkflow failed:
  io.temporal.internal.statemachines.InvalidStateException:
  Query method getStatusWorse should not modify workflow state:
  attempted to create command SCHEDULE_ACTIVITY_TASK during a query
```

하지만 **단순 필드 대입은 감지하지 못합니다.** `viewCount++` 는 그냥 자바 코드이고 SDK 가 관여할 지점이 없습니다. 위험한 것은 SDK 가 막아 주는 액티비티 호출이 아니라, 아무 소리 없이 통과하는 필드 변경 쪽입니다.

> ⚠️ **함정 — Query 핸들러의 상태 변경은 SDK 가 잡아 주지 못한다**
> 액티비티 호출·타이머·`Workflow.sleep` 은 `InvalidStateException` 으로 막힙니다.
> 그러나 `this.count++`, `list.add(...)`, `map.put(...)` 같은 **순수 자바 필드 조작은 통과합니다.**
> 그 변경은 히스토리에 없으므로 리플레이로 재현되지 않고, 워커 재시작·캐시 축출·다른 워커로의 이동 때마다 값이 되돌아갑니다.
> 그 필드를 워크플로우 로직이 읽으면 곧바로 `NonDeterministicException` 입니다.
> **규칙: Query 핸들러 본문은 `return` 문 하나가 이상적입니다.** 계산이 필요하면 지역 변수만 쓰고, `this.` 로 시작하는 대입은 절대 금지입니다.

> 💡 **조회수를 정말 세고 싶다면**
> Query 로는 불가능합니다. 조회 자체를 기록하려면 히스토리에 남아야 하고, 그러려면 **Signal 이나 Update** 여야 합니다.
> 다만 "조회" 를 히스토리에 남기는 것이 정말 필요한지 먼저 따져 보십시오. 대개는 워크플로우 밖(API 게이트웨이 메트릭)에서 세는 게 맞습니다.

---

## 7-7. `@UpdateMethod` 와 `@UpdateValidatorMethod`

Signal 로는 "배송지를 바꿔 주세요" 를 보낼 수는 있지만 **바뀌었는지** 는 알 수 없습니다. Query 로 다시 확인해야 하고, 그 사이 시간차 때문에 정확하지도 않습니다. Update 가 이 간극을 메웁니다. Temporal 1.21 에서 도입되어 Java SDK 1.22.3 에서 정식 API 입니다.

```java
// [7-7] 배송지 변경 — 검증 후 상태를 바꾸고 최종 주소를 반환한다
@UpdateMethod
String changeAddress(String newAddress);

// [7-7] 검증기 — 메서드 이름을 updateName 으로 연결한다
@UpdateValidatorMethod(updateName = "changeAddress")
void validateChangeAddress(String newAddress);
```

```java
@Override
public void validateChangeAddress(String newAddress) {
    // [7-7] 검증기는 상태를 바꾸지 못한다. 던지면 거절.
    if (newAddress == null || newAddress.isBlank()) {
        throw new IllegalArgumentException("주소가 비어 있습니다");
    }
    if ("SHIPPED".equals(stage) || "CANCELLED".equals(stage)) {
        throw new IllegalArgumentException("이미 " + stage + " 상태라 변경할 수 없습니다");
    }
}

@Override
public String changeAddress(String newAddress) {
    this.address = newAddress;
    return this.address;                       // 최종 값을 돌려준다
}
```

정상 케이스입니다.

```bash
temporal workflow update \
  --workflow-id order-1004 \
  --name changeAddress \
  --input '"서울시 송파구 올림픽로 300"'
```

**결과**
```
Update workflow succeeded

  WorkflowId  order-1004
  RunId       c71e0a94-2b55-4f0d-9e83-14ab7d6f2c05
  Name        changeAddress
  Result      "서울시 송파구 올림픽로 300"
```

히스토리에는 `WorkflowExecutionUpdateAccepted` 와 `WorkflowExecutionUpdateCompleted` 두 이벤트가 쌍으로 남습니다.

```bash
temporal workflow show -w order-1004 --output json | jq '.events[] | select(.eventId=="12" or .eventId=="13")'
```

**결과**
```json
{
  "eventId": "12",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED",
  "workflowExecutionUpdateAcceptedEventAttributes": {
    "protocolInstanceId": "b4d1e2f0-77aa-4c31-9b02-5e8f31c7d9a4",
    "acceptedRequestSequencingEventId": "11",
    "acceptedRequest": {
      "meta": { "updateId": "b4d1e2f0-77aa-4c31-9b02-5e8f31c7d9a4", "identity": "51234@macbook@" },
      "input": { "name": "changeAddress",
                 "args": { "payloads": [ { "data": "IuyEnOyauOyLnCDshqHtjIzqtazigKYi" } ] } }
    }
  }
}
{
  "eventId": "13",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED",
  "workflowExecutionUpdateCompletedEventAttributes": {
    "acceptedEventId": "12",
    "outcome": { "success": { "payloads": [ { "data": "IuyEnOyauOyLnCDshqHtjIzqtazigKYi" } ] } }
  }
}
```

이제 **거절되는** 케이스입니다. 빈 주소를 보냅니다.

```bash
temporal workflow update -w order-1004 --name changeAddress --input '""'
```

**결과**
```
Error: unable to update workflow: 
  UpdateWorkflowExecution failed: INVALID_ARGUMENT: 
  java.lang.IllegalArgumentException: 주소가 비어 있습니다
```

```bash
temporal workflow describe -w order-1004
```

**결과**
```
Execution Info:
  Workflow Id       order-1004
  Status            RUNNING
  History Length    16
  History Size      3021
```

거절 **전후로 `History Length` 가 16 그대로입니다.** 검증기에서 거절된 Update 는 히스토리에 아무 흔적도 남기지 않습니다. Validator 는 워크플로우 상태를 **읽을 수는 있지만 바꿀 수는 없고**, 커맨드도 만들 수 없어서 "히스토리 오염 없이 거절" 이 가능합니다.

Signal 과 비교하면 차이가 선명합니다.

| | Signal 로 배송지 변경 | Update 로 배송지 변경 |
|---|---|---|
| 잘못된 주소를 보내면 | 히스토리에 남고, 워크플로우가 받아서 무시 | 히스토리에 **안 남고** 호출자가 즉시 에러를 받음 |
| 호출자가 결과를 알려면 | Query 를 따로 해야 함 (시간차 있음) | 반환값으로 즉시 |
| 이미 배송된 주문에 보내면 | 조용히 무시됨 — 호출자는 성공한 줄 앎 | `IllegalArgumentException: 이미 SHIPPED 상태...` |
| 워커가 죽어 있으면 | 성공 (나중에 처리) | 실패 (타임아웃) |

> 💡 **실무 팁 — 언제 Signal 대신 Update 인가**
> "사용자가 버튼을 눌렀고, 화면에 결과를 바로 보여줘야 한다" 면 Update, "이벤트를 흘려보내고 처리는 시스템이 알아서" 면 Signal 입니다.
> 다만 Update 는 **워커가 살아 있어야 하고**, 처리 시간만큼 호출자가 붙잡혀 있습니다.
> 배포 중 워커가 잠깐 없는 시간에도 요청을 잃으면 안 된다면 Signal + Query 조합이 여전히 안전합니다.

> ⚠️ **함정 — Validator 에서 상태를 바꾸면 안 됩니다**
> Validator 는 거절될 수 있고, 거절되면 히스토리에 남지 않습니다. 즉 **Query 와 같은 처지입니다.**
> Validator 안에서 필드를 바꾸면 그 변경은 리플레이로 재현되지 않아 7-6 과 똑같은 결정성 붕괴를 만듭니다.
> Validator 는 "읽고, 판단하고, 던지거나 조용히 리턴" 만 하십시오.

---

## 7-8. 시그널의 순서와 유실

### 순서는 보장된다

같은 워크플로우로 보낸 시그널은 **서버가 받은 순서대로** 히스토리에 기록되고, 그 순서대로 핸들러가 호출됩니다. 시그널 5개를 연달아 보냅니다.

```bash
for i in 1 2 3 4 5; do
  temporal workflow signal -w order-1005 --name addNote --input "\"note-$i\""
done
temporal workflow query -w order-1005 --type getNotes
```

**결과**
```
Query result:
  QueryResult  ["note-1","note-2","note-3","note-4","note-5"]
```

순서가 그대로입니다. 그리고 **워크플로우가 처리하기 전에 이미 히스토리에 기록됩니다.** 12·14·16·18·20 이 전부 적힌 뒤에 워커가 한 번의 Workflow Task 로 다섯 개를 몰아 처리할 수도 있습니다. 처리 타이밍은 몰라도 유실은 없습니다.

### 종료 직전 시그널은 유실된다

워크플로우 메서드가 `return` 하는 순간 워크플로우는 닫힙니다. 그 시점에 **아직 핸들러가 실행되지 않은 시그널이 남아 있으면 조용히 버려집니다.**

```java
// ⚠️ 드레인 없는 버전
Workflow.await(() -> cancelled || paid);
return req.orderId() + " DONE";        // 여기서 즉시 종료
```

워크플로우가 끝나는 순간에 시그널을 던집니다.

```bash
temporal workflow signal -w order-1006 --name addNote --input '"last-minute"'
```

**결과**
```
Signal workflow succeeded

  WorkflowId  order-1006
  RunId       2d9f4b18-a1c7-4e60-8f35-b3ca7e19d442
```

CLI 는 **성공했다고 답합니다.** `temporal workflow show -w order-1006` 을 봅니다.

**결과**
```
Progress:
  ID          Time                     Type
  ...
  15  2026-03-11T11:14:08Z  WorkflowTaskCompleted
  16  2026-03-11T11:14:08Z  WorkflowExecutionSignaled       ← 시그널은 기록됨
  17  2026-03-11T11:14:08Z  WorkflowExecutionCompleted      ← 그런데 바로 종료

Result:
  Status: COMPLETED
  Output: ["1006 DONE"]
```

16번에 시그널이 분명히 기록되어 있는데, 워크플로우는 그것을 처리하지 않고 17번에서 끝났습니다.

**결과** (워커 콘솔)
```
11:14:08.771 [workflow-method-order-1006-...] WARN  i.t.i.sync.WorkflowExecuteRunnable - Workflow order-1006 finished while update/signal handlers are still running. This may have interrupted the execution of the handler(s): addNote(1)
```

`WARN` 한 줄입니다. `Status: COMPLETED`. 아무도 안 봅니다.

### 드레인 패턴

Java SDK 1.22.3 은 `Workflow.isEveryHandlerFinished()` 를 제공합니다. 워크플로우 메서드가 끝나기 전에 모든 시그널·업데이트 핸들러가 완료되기를 기다립니다.

```java
// [7-8] 드레인 패턴 — 미처리 핸들러가 없을 때까지 기다린 뒤 종료
Workflow.await(() -> cancelled || paid);
Workflow.await(() -> Workflow.isEveryHandlerFinished());   // ← 이 한 줄
return req.orderId() + " DONE";
```

같은 시나리오를 다시 돌립니다.

**결과**
```
Progress:
  ID          Time                     Type
  ...
  16  2026-03-11T11:22:41Z  WorkflowExecutionSignaled
  17  2026-03-11T11:22:41Z  WorkflowTaskScheduled           ← 시그널 처리를 위해 한 번 더 깨어남
  18  2026-03-11T11:22:41Z  WorkflowTaskStarted
  19  2026-03-11T11:22:41Z  WorkflowTaskCompleted
  20  2026-03-11T11:22:41Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["1006 DONE"]
```

```bash
temporal workflow query -w order-1006 --type getNotes
```

**결과**
```
Query result:
  QueryResult  ["last-minute"]
```

시그널이 처리되었습니다. 이벤트가 3개(17·18·19) 늘어난 대가로 유실을 막았습니다.

> ⚠️ **함정 — 워크플로우가 끝나면 미처리 시그널은 조용히 사라진다**
> 시그널 전송은 **성공으로 응답합니다.** 히스토리에도 `WorkflowExecutionSignaled` 가 남습니다.
> 그런데 워크플로우는 그것을 처리하지 않고 `COMPLETED` 로 끝납니다. 로그에 `WARN` 한 줄뿐이고, 상태는 정상입니다.
> "취소 요청이 성공했다는데 주문은 배송됐다" 같은 사고가 여기서 나옵니다.
> **종료 조건이 있는 워크플로우이면서 시그널을 받는다면, 반드시 `Workflow.await(() -> Workflow.isEveryHandlerFinished())` 를 마지막에 두십시오.**
> 이 함정은 Step 08 의 Continue-As-New 에서 한 번 더, 더 나쁜 형태로 나타납니다.

> 💡 **더 근본적인 대비 — 종료 조건에 시그널을 포함시키기**
> 드레인은 "이미 도착한" 시그널만 구제합니다. 워크플로우가 끝난 **뒤에** 온 시그널은 `WorkflowNotFoundException` 입니다.
> 취소 가능 시간을 늘리고 싶다면, 워크플로우를 일찍 끝내지 말고 "취소 유예 기간" 을 `Workflow.await(Duration.ofHours(1), ...)` 로 명시적으로 두는 설계가 낫습니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Signal | 쓰기·비동기·반환값 없음. 히스토리에 남는다. 워커가 죽어 있어도 성공 |
| Query | 읽기·동기·반환값 있음. **히스토리에 안 남는다.** 워커가 살아 있어야 한다 |
| Update | 쓰기+읽기·동기·반환값 있음. 히스토리에 남는다. 1.21+ |
| `@SignalMethod` | 반환 타입은 `void` 강제. 핸들러는 **필드 대입만**, 블로킹 금지 |
| 시그널 → await | 시그널이 필드를 바꾸면 `Workflow.await` 조건이 재평가되어 깨어난다 |
| 보내는 3방법 | 타입 세이프 스텁(권장) / `WorkflowStub.signal(이름)` / `temporal workflow signal` CLI |
| CLI `--input` | **JSON**. 문자열은 `'"값"'` 처럼 이중 인용 |
| `signalWithStart` | "없으면 시작 + 시그널, 있으면 시그널만" 을 원자적으로. 장바구니·배치 집계 |
| Query 의 원리 | 서버는 상태를 모른다 → 워커가 리플레이해서 답한다 |
| **Query 상태 변경** | 히스토리에 안 남으므로 리플레이로 재현 안 됨 → 워커 재시작 시 값이 되돌아감 → 결정성 붕괴 |
| SDK 의 방어 범위 | 액티비티/타이머는 `InvalidStateException` 으로 막지만 **단순 필드 대입은 못 막는다** |
| `@UpdateValidatorMethod` | 거절하면 히스토리에 **아예 안 남는다.** 검증기도 상태 변경 금지 |
| 시그널 순서 | 서버가 받은 순서대로 기록·처리. 처리 전에 이미 기록되므로 유실 없음 |
| 종료 직전 시그널 | 조용히 버려진다. `Workflow.await(() -> Workflow.isEveryHandlerFinished())` 로 드레인 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **직접 CLI 로 시그널을 보내고 히스토리를 확인**하세요.

1. `pauseRequested` / `resumeRequested` 시그널 쌍으로 워크플로우를 일시정지·재개시키기
2. Query 로 진행률(`completed / total`)을 백분율 문자열로 반환하기 — 상태를 바꾸지 않고
3. 주어진 "Query 에서 상태를 바꾸는" 코드의 문제를 찾아 고치고, 왜 되돌아가는지 설명하기
4. `signalWithStart` 로 장바구니 워크플로우를 만들기 — 첫 아이템도 유실되지 않아야 함
5. `@UpdateMethod` + Validator 로 "수량 변경" 을 구현하기 — 재고를 초과하면 히스토리에 안 남게 거절
6. 종료 직전 시그널이 유실되는 워크플로우를 드레인 패턴으로 고치고, 이벤트 개수 차이를 확인하기

---

## 다음 단계

지금까지는 워크플로우 하나가 액티비티들을 부르는 구조였습니다. 이제 워크플로우가 **다른 워크플로우를 자식으로** 부르는 구조와, 시그널을 오래 받는 워크플로우의 히스토리가 무한히 커질 때 벌어지는 일을 다룹니다. 이벤트 51,200개에서 워크플로우가 강제 종료되는 것을 직접 재현하고, `continueAsNew` 로 히스토리를 20,412개에서 8개로 되돌립니다. 7-8 의 "시그널 유실" 함정이 Continue-As-New 에서 어떻게 더 위험해지는지도 이어서 봅니다.

→ [Step 08 — 자식 워크플로우와 Continue-As-New](../step-08-child-continue-as-new/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 의 워커를 띄운 채 본문 7-2 ~ 7-8 의 CLI 명령을 하나씩 실행하며 히스토리를 관찰하고, 그다음 `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 정답과 해설을 대조합니다. 세 파일 모두 단일 최상위 클래스 안에 워크플로우 인터페이스·구현·액티비티를 static nested 로 담고 있어 그대로 컴파일됩니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실행 가능한 워커입니다.

- `main` 이 워커를 띄우고 `ORDER_TASK_QUEUE` 를 폴링합니다. **이 프로세스를 띄워 둔 채로** 다른 터미널에서 `temporal workflow start` / `signal` / `query` / `update` 를 실행하는 구조입니다.
- `[7-2]` `OrderWorkflowImpl.cancelRequested` 는 필드 두 개만 대입합니다. 이 절제가 7-2 함정의 답입니다.
- `[7-6]` `getStatusBad()` 는 **일부러 잘못 만든** Query 입니다. 세 번 호출해 카운터가 오르는 것을 본 뒤 워커를 재시작하고 다시 호출하면 1로 되돌아갑니다. 이 재현이 이 스텝의 핵심 측정이므로 건너뛰지 마세요.
- `[7-6]` `getStatusWorse()` 는 Query 안에서 액티비티를 호출해 `InvalidStateException` 을 유발합니다. SDK 가 **무엇은 막고 무엇은 못 막는지** 를 대비해 보기 위한 짝입니다.
- `[7-8]` `NoteWorkflowImpl` 에는 `DRAIN` 상수가 있습니다. `true` / `false` 로 바꿔 가며 같은 시나리오의 히스토리 이벤트 개수(17개 vs 20개)와 `getNotes()` 결과를 비교하세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1·2·4·5·6** 은 코드를 채우는 문제이고, **문제 3** 은 이미 잘못 짜여진 코드가 주어지고 그것을 진단·수정하는 문제입니다.
- 문제 3 의 `BadQueryWorkflowImpl` 은 컴파일도 되고 실행도 됩니다. **에러가 안 난다는 것 자체가 문제** 라는 점을 알아채는 것이 목표입니다.
- 문제 4 는 `signalWithStart` 를 쓰지 않은 try-catch 버전이 주석으로 함께 들어 있습니다. 두 요청을 동시에 보내는 스레드 두 개짜리 테스트 코드도 들어 있어, 경쟁 조건을 실제로 재현할 수 있습니다.
- 문제 5 의 `Validator` 자리는 비어 있지만 `@UpdateValidatorMethod(updateName = "changeQty")` 애노테이션은 미리 붙어 있습니다. `updateName` 을 오타 내면 **검증기가 그냥 무시되고 Update 가 전부 통과합니다** — 이것도 함정입니다.
- 파일 끝의 `main` 은 문제별 워커를 하나씩 띄우는 스위치입니다. `-Pargs=1` 처럼 문제 번호를 넘겨 해당 문제만 실행합니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 구현과, "왜 그 답인가" 를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `paused` 필드 하나와 `Workflow.await(() -> !paused)` 조합입니다. 각 단계 사이에 이 await 를 넣는 것이 요령이고, 시그널 핸들러 두 개는 각각 한 줄입니다.
- **정답 3** 의 핵심은 "카운터를 지운다" 가 아니라 **"세고 싶으면 Signal 이나 Update 여야 한다"** 는 결론입니다. 주석이 `viewCount++` 가 리플레이에서 사라지는 과정을 이벤트 단위로 추적합니다.
- **정답 4** 는 `BatchRequest` 에 워크플로우 메서드와 시그널 메서드를 각각 `add` 하는 형태입니다. 히스토리에서 `WorkflowExecutionSignaled`(eventId 2)가 `WorkflowTaskScheduled`(eventId 3) **앞** 에 온다는 것이 유실 없음의 증거이고, 주석이 이 순서를 강조합니다.
- **정답 5** 는 Validator 가 `stage` 를 **읽기만** 하고 `qty` 를 바꾸지 않는다는 점을 지킵니다. 거절 전후로 `History Length` 가 변하지 않는 것을 확인하는 CLI 명령이 주석에 함께 있습니다.
- **정답 6** 은 `Workflow.await(() -> Workflow.isEveryHandlerFinished())` 한 줄이지만, 주석은 이것이 **"이미 도착한" 시그널만 구제한다**는 한계와, 그래서 종료 시점 설계 자체를 바꿔야 하는 경우를 함께 설명합니다.

```java file="./Solution.java"
```
