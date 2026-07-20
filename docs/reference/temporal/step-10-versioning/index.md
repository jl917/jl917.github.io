# Step 10 — 버저닝과 무중단 배포

> **학습 목표**
> - 실행 중인 워크플로우가 있는 상태에서 코드를 바꿔 `NonDeterministicException` 을 **직접 재현한다**
> - 깨진 워크플로우가 `Pending Workflow Task` 의 Attempt 를 올리며 영원히 멈추는 것을 `describe` 로 확인한다
> - 어떤 코드 변경이 안전하고 어떤 것이 위험한지, "히스토리의 무엇과 어긋나는가"로 판정한다
> - `Workflow.getVersion` 으로 안전하게 분기하고, `MarkerRecorded` 이벤트가 히스토리에 남는 것을 JSON 으로 확인한다
> - 타입명 버저닝 · Task Queue 분리 · Worker Versioning(Build ID) 세 가지 대안을 비교하고 상황별로 고른다
> - 깨진 워크플로우를 `temporal workflow reset` 으로 되돌리고, 안전한 배포 체크리스트를 세운다
>
> **선행 스텝**: Step 09 — Saga 보상 트랜잭션
> **예상 소요**: 120분

---

## 10-0. 이 스텝이 이 코스에서 가장 중요한 이유

Step 03 에서 "워크플로우 코드는 결정적이어야 한다"를 배웠습니다. `System.currentTimeMillis()` 를 쓰면 안 되고 `Workflow.currentTimeMillis()` 를 써야 한다는 것 말입니다.

그런데 그건 **한 벌의 코드 안에서의 결정성**이었습니다. 이 스텝의 주제는 다릅니다.

> **오늘 배포한 코드가, 3주 전에 시작된 워크플로우의 히스토리를 리플레이할 수 있는가?**

이건 훨씬 어려운 문제입니다. 왜냐하면 **로컬 테스트로는 절대 발견되지 않기 때문입니다.** 로컬에서는 항상 새 워크플로우를 새 코드로 실행합니다. 100% 통과합니다. 문제는 운영에만 존재합니다.

```
  로컬 테스트           운영
  ──────────           ──────────────────────────────────
  새 워크플로우          3주 전 시작된 워크플로우 4,200개
       ↓                     ↓  (히스토리는 옛 코드로 만들어짐)
  새 코드 실행            새 코드로 리플레이
       ↓                     ↓
     ✅ 통과              💥 NonDeterministicException
```

---

## 10-1. 문제 재현 — 30일 sleep 하는 워크플로우

가장 단순한 형태로 재현합니다. 주문 후 30일 뒤에 리뷰 요청 알림을 보내는 워크플로우입니다.

**v1 코드**

```java
@Override
public String processOrder(OrderRequest req) {
    String paymentId = payment.charge(req.orderId(), req.amount());   // 액티비티 ①

    Workflow.sleep(Duration.ofDays(30));                              // 타이머

    notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");   // 액티비티 ②
    return req.orderId() + " COMPLETED";
}
```

띄웁니다.

```bash
temporal workflow start --task-queue ORDER_TASK_QUEUE --type OrderWorkflow \
  --workflow-id order-2001 \
  --input '{"orderId":"2001","customerId":"C-11","sku":"SKU-1","qty":1,"amount":39000,"address":"서울시 강남구"}'
```

**결과**
```
Running execution:
  WorkflowId  order-2001
  RunId       4b71ce20-9f83-4a16-b0d5-7e2c4a8f1103
  Type        OrderWorkflow
  TaskQueue   ORDER_TASK_QUEUE
```

몇 초 뒤 히스토리를 봅니다.

```bash
temporal workflow show -w order-2001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-18T06:10:02Z  WorkflowExecutionStarted
   2  2026-03-18T06:10:02Z  WorkflowTaskScheduled
   3  2026-03-18T06:10:02Z  WorkflowTaskStarted
   4  2026-03-18T06:10:02Z  WorkflowTaskCompleted
   5  2026-03-18T06:10:02Z  ActivityTaskScheduled     [charge]
   6  2026-03-18T06:10:02Z  ActivityTaskStarted
   7  2026-03-18T06:10:03Z  ActivityTaskCompleted
   8  2026-03-18T06:10:03Z  WorkflowTaskScheduled
   9  2026-03-18T06:10:03Z  WorkflowTaskStarted
  10  2026-03-18T06:10:03Z  WorkflowTaskCompleted
  11  2026-03-18T06:10:03Z  TimerStarted              [30일]

Result:
  Status: RUNNING
```

이벤트 11 `TimerStarted` 에서 멈춰 있습니다. 30일 뒤에 깨어납니다.

### 이제 코드를 바꿉니다

요구사항이 추가됐습니다. "결제 전에 이상거래 탐지(fraud check)를 넣어 주세요."

**v2 코드**

```java
@Override
public String processOrder(OrderRequest req) {
    fraudActivity.check(req.customerId(), req.amount());               // ← 액티비티 추가!
    String paymentId = payment.charge(req.orderId(), req.amount());

    Workflow.sleep(Duration.ofDays(30));

    notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
    return req.orderId() + " COMPLETED";
}
```

Worker 를 재배포합니다. 그러면 Temporal 이 `order-2001` 의 다음 Workflow Task 를 이 새 Worker 에게 줍니다. 새 Worker 는 히스토리를 처음부터 리플레이합니다.

```
  히스토리가 말하는 것              새 코드가 만드는 Command
  ───────────────────────          ─────────────────────────
  이벤트 5: ActivityTaskScheduled   ① ScheduleActivityTask(Check)   ← 어긋남!
            activityType=Charge
  이벤트 11: TimerStarted           ② ScheduleActivityTask(Charge)  ← 어긋남!
                                    ③ StartTimer(30d)
```

**결과** (Worker 콘솔)
```
06:22:41.883 [Workflow Executor taskQueue="ORDER_TASK_QUEUE", workflowId=order-2001] ERROR i.t.i.replay.ReplayWorkflowTaskHandler - Workflow task failure. startedEventId=13, WorkflowId=order-2001, RunId=4b71ce20-9f83-4a16-b0d5-7e2c4a8f1103. If seen continuously the workflow might be stuck.
io.temporal.worker.NonDeterministicException: Failure handling event 5 of type 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay. Event 5 of type EVENT_TYPE_ACTIVITY_TASK_SCHEDULED does not match command type COMMAND_TYPE_START_TIMER
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleCommandEvent(WorkflowStateMachines.java:498)
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleEventImpl(WorkflowStateMachines.java:325)
	at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler.handleEvent(ReplayWorkflowRunTaskHandler.java:150)
	at io.temporal.internal.replay.ReplayWorkflowTaskHandler.handleWorkflowTask(ReplayWorkflowTaskHandler.java:98)
	at io.temporal.internal.worker.WorkflowWorker$TaskHandlerImpl.handle(WorkflowWorker.java:319)
	at io.temporal.internal.worker.PollTaskExecutor.lambda$process$0(PollTaskExecutor.java:105)
	at java.base/java.lang.Thread.run(Thread.java:1583)
```

메시지를 정확히 읽어야 합니다.

> `Failure handling event 5 of type 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay.`
> `Event 5 ... does not match command type COMMAND_TYPE_START_TIMER`

"이벤트 5는 액티비티 스케줄인데, 코드가 만든 5번째 명령은 타이머 시작이다"라는 뜻입니다. **히스토리와 코드가 어긋난 지점의 이벤트 번호**를 알려 주므로, 여기서 시작해 코드를 대조하면 원인을 찾을 수 있습니다.

### 그다음 워크플로우는 어떻게 되나

죽지 않습니다. **영원히 멈춥니다.** 이게 더 나쁩니다.

```bash
temporal workflow describe -w order-2001
```

**결과**
```
Execution Info:
  Workflow Id       order-2001
  Run Id            4b71ce20-9f83-4a16-b0d5-7e2c4a8f1103
  Type              OrderWorkflow
  Namespace         default
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-18 06:10:02 +0000 UTC
  Status            RUNNING
  History Length    12

Pending Workflow Task:
  State                 Started
  Scheduled Time        2026-03-18 06:24:19 +0000 UTC
  Started Time          2026-03-18 06:24:19 +0000 UTC
  Attempt               47
  Last Failure          io.temporal.worker.NonDeterministicException: Failure handling event 5 of type 'EVENT_TYPE_ACTIVITY_TASK_SCHEDULED' during replay...
```

`Attempt 47`. 1분 뒤 다시 보면 `Attempt 63` 입니다. Temporal 은 Workflow Task 실패를 **무한 재시도**합니다(액티비티와 달리 Workflow Task 에는 최대 시도 횟수가 없습니다 — 코드 배포로 고칠 수 있는 문제라고 보기 때문입니다).

주목할 점: **`History Length` 가 12 에서 늘지 않습니다.** Workflow Task 실패는 히스토리에 `WorkflowTaskFailed` 로 기록되지만, 같은 실패가 반복될 때는 이벤트가 계속 쌓이지 않고 Attempt 카운터만 올라갑니다. 그래서 히스토리만 보면 "그냥 대기 중"처럼 보입니다.

Web UI(`http://localhost:8233/namespaces/default/workflows/order-2001`)에서는 상단에 빨간 `⛔ Workflow Task Failed` 배너가 같은 메시지와 `Attempt 47 · Next retry in 10s` 를 달고 뜹니다.

> ⚠️ **함정 1 — 로컬 테스트는 100% 통과한다**
> v2 코드에 대한 단위 테스트, 통합 테스트, `TestWorkflowEnvironment` 테스트 전부 통과합니다. 왜냐하면 **테스트는 언제나 새 워크플로우를 새 코드로 실행**하기 때문입니다. 히스토리가 없으니 어긋날 것도 없습니다.
> CI 가 초록불이고, 스테이징에서도 잘 돌고, 배포 직후 새 주문도 잘 처리됩니다. 그런데 **배포 전에 시작된 워크플로우 4,200개가 조용히 멈춰 있습니다.** 30일 뒤 리뷰 알림이 안 나가고, Saga 보상이 중단된 채 재고가 묶여 있습니다.
> 이걸 배포 전에 잡는 유일한 방법은 **운영 히스토리로 리플레이 테스트를 돌리는 것**입니다(Step 11).

---

## 10-2. 어떤 변경이 안전하고 어떤 변경이 위험한가

판정 기준은 하나뿐입니다.

> **그 변경이 워크플로우가 발행하는 Command 의 종류·순서·개수를 바꾸는가?**

Command 는 히스토리 이벤트와 1:1 로 대응합니다. 액티비티 호출 → `ScheduleActivityTask` → `ActivityTaskScheduled`. 타이머 → `StartTimer` → `TimerStarted`. 이 대응이 어긋나면 예외입니다.

### 안전한 변경

| 변경 | 안전? | 히스토리와의 관계 |
|---|---|---|
| 액티비티 **구현 내부** 로직 변경 | ✅ | 액티비티는 워크플로우 밖에서 실행. 히스토리에는 결과만 남으므로 무관 |
| 액티비티 재시도 옵션 변경 | ✅ | `retryPolicy` 는 `ActivityTaskScheduled` 속성일 뿐. **이미 스케줄된 것에는 적용 안 되고 신규 스케줄부터** 반영 |
| 로깅 추가 (`Workflow.getLogger`) | ✅ | Command 를 만들지 않음. 리플레이 중에는 자동으로 억제됨 |
| 새 워크플로우 **타입** 추가 | ✅ | 기존 타입의 히스토리와 무관 |
| 워크플로우에서 안 쓰이는 필드를 DTO 에 추가 | ✅ | 역직렬화 시 무시. 단 **기본 생성자/기본값**이 있어야 함 |
| 액티비티 타임아웃 값 변경 | ✅ | 신규 스케줄부터 반영 |
| private 헬퍼 메서드 추출 (Command 흐름 동일) | ✅ | 리팩터링만으로는 Command 가 바뀌지 않음 |
| 워크플로우 반환값의 **내용** 변경 | ✅ | `WorkflowExecutionCompleted` 한 번뿐. 리플레이 도중에는 미도달 |

### 위험한 변경

| 변경 | 위험 | 히스토리의 무엇과 어긋나는가 |
|---|---|---|
| 액티비티 **추가** | ⛔ | 없던 `ScheduleActivityTask` 가 끼어들어 그 위치의 이벤트와 타입이 불일치 |
| 액티비티 **삭제** | ⛔ | 히스토리에 `ActivityTaskScheduled` 가 있는데 코드가 Command 를 안 냄 |
| 액티비티 **순서 변경** | ⛔ | `ActivityTaskScheduled` 의 `activityType.name` 이 어긋남 |
| 액티비티 인터페이스 **메서드명 변경** | ⛔ | `activityType.name` 이 `Charge` → `ChargePayment` 로 달라짐 |
| 액티비티 **인자 개수/타입 변경** | ⛔ | `input.payloads` 역직렬화 실패 또는 Command 속성 불일치 |
| `Workflow.sleep` 추가/삭제 | ⛔ | `StartTimer` Command 가 추가/누락되어 `TimerStarted` 와 어긋남 |
| 조건 분기 로직 변경 | ⛔ | 옛 실행은 A 경로를 탔는데 새 코드가 B 경로를 타면 Command 열이 통째로 달라짐 |
| Signal / Query **이름 변경** | ⛔ | `WorkflowExecutionSignaled.signalName` 을 처리할 핸들러가 없어 시그널 유실 |
| 자식 워크플로우 추가/삭제 | ⛔ | `StartChildWorkflowExecution` Command 불일치 |
| `Promise` 대기 순서 변경 | ⛔ | `Promise.allOf` 의 완료 처리 순서가 바뀌면 후속 Command 순서가 달라짐 |
| `Workflow.newRandom()` 호출 추가/삭제 | ⛔ | 사이드이펙트 마커(`MarkerRecorded`) 개수가 어긋남 |
| 액티비티 스텁 생성 위치 변경 | ⚠️ | 스텁 생성 자체는 Command 를 안 내지만, 옵션이 달라지면 신규 스케줄부터 반영 |

> 💡 **판정을 쉽게 하는 습관**
> 워크플로우 메서드를 위에서 아래로 읽으며 **Command 를 내는 줄에만 형광펜**을 칩니다. `Workflow.` 로 시작하는 호출과 액티비티/자식 워크플로우 스텁 호출이 전부입니다. 그 줄들의 목록이 변경 전후로 같으면 안전합니다. `if (amount > 100000)` 같은 조건이 그 목록에 영향을 준다면 위험합니다.

> ⚠️ **함정 — "재시도 옵션 변경은 안전하다"의 정확한 의미**
> 안전한 것은 **컴파일/리플레이가 깨지지 않는다**는 뜻이지, **효과가 즉시 반영된다**는 뜻이 아닙니다.
> `maximumAttempts` 를 3 → 10 으로 바꿔 배포해도, 이미 `ActivityTaskScheduled` 로 스케줄되어 재시도 중인 액티비티는 **옛 정책(3회)으로 끝납니다.** 재시도 정책은 스케줄 시점에 이벤트 속성으로 박제되기 때문입니다.
> "재시도 늘려서 배포했는데 왜 여전히 3번 만에 죽나요?" 의 답이 이것입니다.

---

## 10-3. `Workflow.getVersion` 패턴

Temporal 의 표준 해법입니다. 시그니처는 이렇습니다.

```java
public static int getVersion(String changeId, int minSupported, int maxSupported)
```

- `changeId`: 이 변경을 식별하는 문자열. 워크플로우 안에서 **유일**해야 합니다.
- `minSupported`: 지원하는 최소 버전. 보통 `Workflow.DEFAULT_VERSION`(= `-1`)
- `maxSupported`: 지원하는 최대 버전. 새 실행이 받을 값.

### 최초 도입

10-1 의 fraud check 추가를 안전하게 다시 합니다.

```java
@Override
public String processOrder(OrderRequest req) {

    int version = Workflow.getVersion(
            "addFraudCheck", Workflow.DEFAULT_VERSION, 1);

    if (version == Workflow.DEFAULT_VERSION) {
        // 옛 경로 — 이 변경 이전에 시작된 실행이 지나가는 길
        // 아무것도 하지 않는다
    } else {
        // 새 경로 — 이 변경 이후에 시작된 실행
        fraudActivity.check(req.customerId(), req.amount());
    }

    String paymentId = payment.charge(req.orderId(), req.amount());
    Workflow.sleep(Duration.ofDays(30));
    notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
    return req.orderId() + " COMPLETED";
}
```

동작은 이렇습니다.

| 실행 | `getVersion` 이 반환하는 값 | 이유 |
|---|---|---|
| 배포 **전**에 시작된 `order-2001` | `-1` (`DEFAULT_VERSION`) | 히스토리에 `Version` 마커가 없음 → SDK 가 `DEFAULT_VERSION` 반환 |
| 배포 **후**에 시작된 `order-2002` | `1` | 마커가 없으므로 `maxSupported`(=1)를 반환하고, **그 값을 히스토리에 기록** |
| `order-2002` 를 나중에 리플레이 | `1` | 히스토리에 기록된 마커 값을 그대로 읽음 |

### 히스토리에 무엇이 남는가

이게 이 패턴의 핵심 메커니즘입니다. `getVersion` 은 **`MarkerRecorded` 이벤트를 히스토리에 씁니다.**

```bash
temporal workflow show -w order-2002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-18T07:01:11Z  WorkflowExecutionStarted
   2  2026-03-18T07:01:11Z  WorkflowTaskScheduled
   3  2026-03-18T07:01:11Z  WorkflowTaskStarted
   4  2026-03-18T07:01:11Z  WorkflowTaskCompleted
   5  2026-03-18T07:01:11Z  MarkerRecorded            [Version]     ← 이것!
   6  2026-03-18T07:01:11Z  ActivityTaskScheduled     [check]
   7  2026-03-18T07:01:11Z  ActivityTaskStarted
   8  2026-03-18T07:01:12Z  ActivityTaskCompleted
   9  2026-03-18T07:01:12Z  WorkflowTaskScheduled
  ...
```

JSON 으로 보면 내용이 명확합니다.

```bash
temporal workflow show -w order-2002 --output json | jq '.events[] | select(.eventType=="EVENT_TYPE_MARKER_RECORDED")'
```

**결과**
```json
{
  "eventId": "5",
  "eventType": "EVENT_TYPE_MARKER_RECORDED",
  "markerRecordedEventAttributes": {
    "markerName": "Version",
    "details": {
      "changeId": { "payloads": [{ "data": "ImFkZEZyYXVkQ2hlY2si" }] },
      "version":  { "payloads": [{ "data": "MQ==" }] }
    },
    "workflowTaskCompletedEventId": "4"
  }
}
```

`data` 는 Base64 입니다. `ImFkZEZyYXVkQ2hlY2si` → `"addFraudCheck"`, `MQ==` → `1`.

**즉 "이 실행에서 addFraudCheck 라는 변경은 버전 1이다"가 히스토리에 영구히 박제됩니다.** 나중에 코드가 버전 5까지 올라가도, 이 실행을 리플레이하면 `getVersion("addFraudCheck", ...)` 은 언제나 `1` 을 반환합니다.

옛 실행(`order-2001`)의 히스토리에는 마커가 없습니다. SDK 는 "마커가 없다 = 이 변경 이전의 실행"으로 판단해 `DEFAULT_VERSION` 을 반환합니다. 그리고 **리플레이 중에는 새 마커를 쓰지 않습니다**(리플레이 중 새 Command 발행은 금지이므로).

> 💡 **`getVersion` 은 Command 를 하나 추가한다**
> 새 실행에서는 `MarkerRecorded` 가 하나 늘어납니다. 히스토리 이벤트가 변경 개수만큼 늘어난다는 뜻입니다. 변경을 10개 누적하면 마커도 10개입니다. 그래서 10-3 마지막의 "정리"가 필요합니다.

### 2차 변경

fraud check 에 더해 이번엔 신용 조회도 추가한다고 합시다. 같은 `changeId` 를 확장합니다.

```java
int version = Workflow.getVersion(
        "addFraudCheck", Workflow.DEFAULT_VERSION, 2);   // maxSupported 를 2로

if (version == Workflow.DEFAULT_VERSION) {
    // 아무것도 안 함 (v1 이전 실행)
} else if (version == 1) {
    fraudActivity.check(req.customerId(), req.amount());
} else {
    // version == 2
    fraudActivity.check(req.customerId(), req.amount());
    creditActivity.verify(req.customerId());
}
```

이 시점에 세 종류의 실행이 공존합니다.

```
  order-2001  (마커 없음)     → -1  → 아무것도 안 함
  order-2002  (마커 version=1) →  1  → check 만
  order-2003  (마커 version=2) →  2  → check + verify        ← 새로 시작되는 것들
```

`temporal workflow list` 로 각각의 버전을 세어 볼 수도 있습니다. Search Attribute 를 걸어 두었다면요. 그렇지 않다면 히스토리를 훑어야 합니다.

### 정리 시점 — 분기를 언제 지우나

분기가 계속 쌓이면 코드가 읽을 수 없게 됩니다. **옛 실행이 모두 종료되면** `minSupported` 를 올려 분기를 제거할 수 있습니다.

```java
// v1 실행이 전부 끝난 뒤
int version = Workflow.getVersion("addFraudCheck", 2, 2);   // min 을 2로 올림

// 분기 제거 — 이제 항상 version == 2
fraudActivity.check(req.customerId(), req.amount());
creditActivity.verify(req.customerId());
```

`minSupported` 보다 낮은 버전이 히스토리에 있으면 SDK 가 `UnsupportedVersion` 예외를 던집니다. 그러므로 **정말로 옛 실행이 없어야** 합니다.

**언제 안전한가?** `안전 시점 = 배포 시각 + 그 워크플로우 타입의 최장 수명 + Namespace Retention Period` 입니다.

- **최장 수명**: `Workflow.sleep(Duration.ofDays(30))` 이 있으면 최소 30일. 시그널 대기가 있으면 더 길 수 있습니다.
- **Retention Period**: 종료된 워크플로우의 히스토리가 보관되는 기간. 기본 3일이지만 운영에서는 보통 30일입니다. 종료된 워크플로우도 리플레이 테스트 대상이 되므로 포함시킵니다.

```bash
temporal operator namespace describe default
```

**결과**
```
NamespaceInfo.Name                    default
NamespaceInfo.State                   Registered
Config.WorkflowExecutionRetentionTtl  30 days 0h 0m 0s
```

30일 sleep + 30일 retention = **60일 뒤에 분기 제거가 안전**합니다.

실행 중인 옛 실행이 정말 없는지 확인합니다.

```bash
temporal workflow count --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running" AND StartTime < "2026-03-18T00:00:00Z"'
```

**결과**
```
Total: 0
```

0 이면 안전합니다. 이 명령은 배포 체크리스트(10-6)의 필수 항목입니다.

### `changeId` 규칙

> ⚠️ **함정 2 — `getVersion` 을 조건문 안에 넣으면 그 자체가 비결정적이다**
> ```java
> // ⛔ 절대 금지
> if (req.amount() > 100000) {
>     int v = Workflow.getVersion("addFraudCheck", Workflow.DEFAULT_VERSION, 1);
>     ...
> }
> ```
> `getVersion` 은 `MarkerRecorded` **Command 를 발행**합니다. 조건문 안에 있으면 어떤 실행에서는 마커가 생기고 어떤 실행에서는 안 생깁니다. 그런데 그 조건(`req.amount()`)이 나중에 바뀌면? 리플레이 시 마커 유무가 히스토리와 어긋납니다.
> `getVersion` 은 항상 **워크플로우 메서드의 같은 자리에서, 무조건 호출**되어야 합니다. 반환값으로 분기하는 것이지, 분기 안에서 호출하는 것이 아닙니다.
> 같은 이유로 **루프 안**에서도 조심해야 합니다. 루프 반복 횟수가 실행마다 다르면 마커 개수도 달라집니다. 같은 `changeId` 를 루프 안에서 여러 번 호출하면 SDK 가 캐시된 값을 돌려주므로 마커는 하나만 생기지만, 첫 호출 위치가 변하면 위험합니다.

`changeId` 에 관한 규칙 세 가지입니다.

1. **유일해야 합니다.** 같은 워크플로우에서 `"v2"` 같은 이름을 재사용하면, 두 번째 변경이 첫 번째의 마커를 읽어 엉뚱한 버전을 받습니다. `"addFraudCheck"`, `"switchToNewShippingApi"` 처럼 무엇을 바꿨는지 드러나는 이름을 쓰세요.
2. **호출 순서를 바꾸면 안 됩니다.** 같은 워크플로우에 `getVersion` 이 3개 있다면 그 순서가 마커 순서입니다. 순서를 바꾸면 마커 순서가 어긋납니다.
3. **한번 정한 `changeId` 문자열은 절대 바꾸지 마세요.** 오타를 발견해도 그대로 두세요. 문자열을 바꾸면 기존 마커를 못 찾아 옛 실행이 전부 `DEFAULT_VERSION` 으로 떨어집니다.

> ⚠️ **함정 3 — `getVersion` 호출을 통째로 지우면 옛 실행이 다시 깨진다**
> "이제 v1 실행이 없으니 `getVersion` 을 아예 지우자"는 유혹이 있습니다. 그런데 **`getVersion` 호출 자체가 `MarkerRecorded` 이벤트를 만들었다**는 걸 기억해야 합니다.
> `order-2002` 의 히스토리에는 이벤트 5에 `MarkerRecorded` 가 있습니다. `getVersion` 을 지운 코드로 이걸 리플레이하면:
> ```
> io.temporal.worker.NonDeterministicException: Failure handling event 5 of type
> 'EVENT_TYPE_MARKER_RECORDED' during replay. Event 5 of type EVENT_TYPE_MARKER_RECORDED
> does not match command type COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK
> ```
> 옛 실행을 살리려고 만든 코드가, 지우는 순간 그 실행들을 죽입니다.
> 올바른 정리 순서는 이렇습니다:
> ① 분기 제거 (`getVersion(id, 2, 2)` 로 min 을 올리고 `if/else` 삭제 — **호출은 남긴다**)
> ② 마커가 있는 실행이 전부 종료되고 retention 도 지날 때까지 대기
> ③ 그제서야 `getVersion` 호출 자체를 삭제
>
> 실무에서는 ③을 생략하고 `getVersion` 한 줄을 영원히 남겨 두는 팀이 많습니다. 한 줄의 비용이 사고보다 훨씬 쌉니다.

---

## 10-4. 대안 패턴 세 가지

`getVersion` 이 항상 최선은 아닙니다. 세 가지를 비교합니다.

| 패턴 | 방법 | 장점 | 단점 | 적합한 상황 |
|---|---|---|---|---|
| **① `getVersion`** | 코드에 `if (version == ...)` 분기 | 실행 중인 워크플로우가 **그대로 이어짐**. 배포 한 번으로 끝 | 코드에 분기가 쌓임. 정리 시점 관리 필요. 마커가 히스토리를 늘림 | 작은 변경, 옛 실행도 새 로직의 일부를 받아야 할 때 |
| **② 워크플로우 타입명 버저닝** | `OrderWorkflow` 와 `OrderWorkflowV2` 를 **둘 다** 등록. 신규 실행만 V2 로 시작 | 새 코드가 완전히 깨끗함. 분기 없음 | 옛 코드를 계속 유지·배포해야 함. 클라이언트 코드도 바꿔야 함. 자식 워크플로우 참조도 전부 수정 | 로직이 통째로 바뀌는 대규모 개편 |
| **③ Task Queue 분리** | 새 Worker 를 `ORDER_TASK_QUEUE_V2` 로 띄우고, 신규 실행만 그 큐로 | 신구 코드가 **물리적으로 격리**됨. 롤백이 Worker 종료만으로 끝남 | Worker 를 두 벌 운영. 큐가 늘어나면 관리 복잡. 액티비티도 큐를 나눠야 하는지 판단 필요 | 코드뿐 아니라 의존성/JDK 버전까지 바뀔 때 |

### ② 타입명 버저닝 상세

```java
// Worker 에 두 구현을 모두 등록
worker.registerWorkflowImplementationTypes(
        OrderWorkflowImpl.class,      // 옛 실행을 처리 — 코드를 절대 건드리지 않음
        OrderWorkflowV2Impl.class);   // 신규 실행만 처리
```

클라이언트는 `client.newWorkflowStub(OrderWorkflowV2.class, ...)` 로 **새 타입만** 시작합니다.

**결과** (`temporal workflow list` 로 두 타입이 공존하는 것 확인)
```
  Status     WorkflowId    Type              StartTime
  Running    order-2001    OrderWorkflow     2026-03-18T06:10:02Z
  Running    order-2002    OrderWorkflow     2026-03-18T07:01:11Z
  Running    order-3001    OrderWorkflowV2   2026-03-19T09:22:40Z
  Running    order-3002    OrderWorkflowV2   2026-03-19T09:23:07Z
```

`OrderWorkflow` 인스턴스가 0 이 되면 그때 `OrderWorkflowImpl` 을 삭제합니다.

```bash
temporal workflow count --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"'
```

**결과**
```
Total: 0
```

> 💡 **`Continue-As-New` 와 조합하면 강력합니다**
> 장수 워크플로우(Step 08)라면, 옛 타입이 `Workflow.continueAsNew` 를 할 때 **새 타입으로** 넘어가게 할 수 있습니다. 그러면 옛 실행들이 자연스럽게 새 코드로 이주합니다. `Workflow.newContinueAsNewStub(OrderWorkflowV2.class)` 를 쓰면 됩니다.

### ③ Task Queue 분리 상세

기존 배포는 `factory.newWorker("ORDER_TASK_QUEUE")` 로 그대로 두고, 새 배포는 `factory.newWorker("ORDER_TASK_QUEUE_V2")` 로 띄웁니다. 신규 실행만 새 큐를 지정합니다.

워크플로우가 자기 Task Queue 로만 태스크를 받으므로, `order-2001` 은 영원히 v1 Worker 만 만납니다. **비결정성이 원천적으로 발생하지 않습니다.**

단점은 명확합니다. v1 실행이 다 끝날 때까지 v1 Worker 를 계속 띄워 둬야 하고, 그 사이 v1 코드에 보안 패치가 필요하면 두 벌을 고쳐야 합니다.

---

## 10-5. Worker Versioning (Build ID)

Temporal 1.21 SDK / Server 1.22 부터 쓸 수 있는 기능입니다. ③ Task Queue 분리의 아이디어를 **서버가 대신 관리**해 줍니다.

핵심: 각 Worker 에 **Build ID** 를 붙이고, 워크플로우 실행마다 "이 실행은 어느 Build ID 의 Worker 가 처리한다"를 서버가 고정합니다.

### Worker 쪽 설정

```java
WorkerOptions options = WorkerOptions.newBuilder()
        .setBuildId("v1.2.0")
        .setUseBuildIdForVersioning(true)
        .build();

Worker worker = factory.newWorker(ORDER_TASK_QUEUE, options);
```

### Task Queue 에 Build ID 등록

Worker 를 띄우기 **전에** 서버에 새 Build ID 를 기본값으로 등록합니다.

```bash
temporal task-queue update-build-ids add-new-default \
  --build-id v1.2.0 --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Successfully added v1.2.0 as the new default build ID for task queue ORDER_TASK_QUEUE
```

현재 상태를 확인합니다.

```bash
temporal task-queue get-build-ids --task-queue ORDER_TASK_QUEUE
```

**결과**
```
  BuildIds                DefaultForSet  IsDefaultSet
  [v1.0.0]                v1.0.0         false
  [v1.1.0]                v1.1.0         false
  [v1.2.0]                v1.2.0         true
```

세 개의 **호환 세트(version set)** 가 있고, `v1.2.0` 이 기본값입니다.

### 라우팅 규칙

| 상황 | 어느 Worker 로 가나 |
|---|---|
| 새 워크플로우 시작 | **기본 Build ID**(`v1.2.0`) Worker |
| 실행 중인 `order-2001`(v1.0.0 으로 시작됨) | **`v1.0.0` Worker 로만** 라우팅 |
| `v1.0.0` Worker 가 전부 내려감 | `order-2001` 의 태스크가 **큐에 쌓인 채 대기**(실패하지 않음) |

이것이 핵심입니다. **실행 중인 워크플로우는 자기를 시작한 Build ID 의 Worker 만 만나므로 리플레이가 깨지지 않습니다.** `getVersion` 분기가 필요 없습니다.

```bash
temporal workflow describe -w order-2001
```

**결과** (발췌)
```
  Workflow Id       order-2001
  Type              OrderWorkflow
  Status            RUNNING
  History Length    12

Worker Versioning:
  Assigned Build Id     v1.0.0
  Inherited Build Id
```

`Assigned Build Id: v1.0.0`. 이 실행은 끝날 때까지 v1.0.0 입니다.

### 호환 세트 — `add-new-compatible`

버그 수정처럼 **리플레이 호환성이 유지되는** 변경이라면, 새 Build ID 를 기존 세트에 **호환 버전으로** 추가합니다.

```bash
temporal task-queue update-build-ids add-new-compatible \
  --build-id v1.0.1 --existing-compatible-build-id v1.0.0 \
  --task-queue ORDER_TASK_QUEUE

temporal task-queue get-build-ids --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Successfully added v1.0.1 to the compatible set containing v1.0.0

  BuildIds                DefaultForSet  IsDefaultSet
  [v1.0.0 v1.0.1]         v1.0.1         false
  [v1.1.0]                v1.1.0         false
  [v1.2.0]                v1.2.0         true
```

`v1.0.0` 으로 시작된 실행이 이제 **`v1.0.1` Worker 로 넘어갑니다.** 액티비티 구현 버그를 고쳐 배포할 때 이 방식을 씁니다. 리플레이 비호환 변경에 이걸 쓰면 당연히 `NonDeterministicException` 이 납니다 — 호환성 판단은 여전히 개발자 몫입니다.

> ⚠️ **실험적 기능입니다**
> Worker Versioning 은 Temporal 1.22 기준으로 **Experimental** 로 표시되어 있습니다. API 가 바뀔 수 있고, Temporal Cloud 와 셀프호스팅에서 활성화 방법이 다릅니다. 셀프호스팅에서는 동적 설정이 필요합니다.
> ```bash
> # docker-compose 의 dynamicconfig/development.yaml
> frontend.workerVersioningDataAPIs:
>   - value: true
> frontend.workerVersioningWorkflowAPIs:
>   - value: true
> worker.buildIdScavengerEnabled:
>   - value: true
> ```
> 이 설정 없이 CLI 를 쓰면 이렇게 나옵니다.
> ```
> Error: worker versioning data APIs are disabled on this cluster
> ```
> 그리고 Build ID 하나당 세트가 쌓이므로, 오래된 Build ID 는 정리해야 합니다(`buildIdScavenger` 가 retention 지난 것을 자동 삭제).

---

## 10-6. 안전한 배포 절차 — 체크리스트

실무에서 그대로 쓸 수 있는 순서입니다.

1. **변경 분류.** 이번 PR 이 워크플로우 코드를 건드리는가? 건드린다면 10-2 의 표로 안전/위험을 판정합니다. Command 를 내는 줄의 목록이 바뀌었는지가 기준입니다.
2. **위험 변경이면 `getVersion` 으로 감쌉니다.** `changeId` 는 `"addFraudCheck"` 처럼 서술적으로. 항상 메서드 최상위에서 무조건 호출하고, 반환값으로만 분기합니다.
3. **리플레이 테스트를 작성합니다** (Step 11). 운영에서 히스토리를 내려받아 `WorkflowReplayer` 로 검증합니다.
   ```bash
   temporal workflow show -w order-2001 --output json > src/test/resources/histories/order-2001.json
   ```
   ```java
   WorkflowReplayer.replayWorkflowExecutionFromResource(
           "histories/order-2001.json", OrderWorkflowImpl.class);
   ```
   이걸 CI 에 넣으세요. **이 단계가 없으면 나머지 체크리스트는 전부 무의미합니다.**
4. **대표 히스토리를 여러 개 확보합니다.** 성공 경로 하나로는 부족합니다. 실패/보상 경로, 시그널이 온 경로, 재시도가 있었던 경로를 각각 받아 둡니다.
5. **실행 중인 워크플로우 개수를 셉니다.** 영향 범위를 아는 것이 롤백 판단의 근거입니다.
   ```bash
   temporal workflow count --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"'
   ```
   **결과**
   ```
   Total: 4218
   ```
6. **카나리 Worker 를 먼저 띄웁니다.** 새 코드 Worker 를 1대만 올리고 나머지는 옛 코드로 둡니다. Temporal 은 태스크를 아무 Worker 에게나 주므로, 문제가 있으면 **일부 워크플로우에서만** 예외가 발생합니다. 전체 배포보다 폭발 반경이 작습니다.
7. **`NonDeterministicException` 메트릭을 감시합니다.** SDK 메트릭 `temporal_workflow_task_execution_failed` 의 `failure_reason="NonDeterminismError"` 태그를 대시보드에 올려 두세요. 카나리 배포 후 5분간 이 값이 0 이어야 합니다.
   ```
   sum(rate(temporal_workflow_task_execution_failed{failure_reason="NonDeterminismError"}[1m])) by (workflow_type)
   ```
8. **`Pending Workflow Task` 의 Attempt 를 확인합니다.** 메트릭이 늦게 붙었다면 직접 셉니다.
   ```bash
   temporal workflow list --query 'ExecutionStatus="Running"' --fields long | grep -c "Attempt"
   ```
9. **롤백 기준을 미리 정합니다.** "카나리 5분 안에 NonDeterminismError 1건이라도 나오면 즉시 롤백". 애매한 기준은 롤백을 늦춥니다. Task Queue 분리(③)나 Worker Versioning(10-5)을 쓰고 있다면 롤백은 새 Worker 를 내리는 것으로 끝납니다.
10. **전체 배포 후 다시 카운트합니다.** 5번과 같은 명령을 돌려 `Running` 개수가 정상적으로 줄어드는지 봅니다. 깨진 워크플로우는 `Running` 에서 내려오지 않으므로, 숫자가 정체되면 신호입니다.
11. **`getVersion` 분기 제거 일정을 티켓으로 만듭니다.** "최장 수명 + retention" 뒤 날짜로 예약합니다. 안 하면 영원히 안 합니다.

> 💡 **실무 팁 — 배포 창(deployment window)을 좁히지 마세요**
> "새벽에 배포하면 실행 중인 워크플로우가 적으니 안전하다"는 착각이 있습니다. 30일 sleep 하는 워크플로우에게는 새벽이든 낮이든 똑같이 실행 중입니다. 시간대는 아무 도움이 되지 않습니다. 리플레이 테스트만이 유일한 방어선입니다.

---

## 10-7. 이미 깨졌다면 — 복구하는 법

배포해 버렸고 4,218개가 멈췄습니다. 선택지는 세 가지입니다.

### ① 코드를 되돌린다 (최선)

가장 빠르고 안전합니다. 옛 Worker 를 다시 배포하면 멈춰 있던 Workflow Task 가 다음 재시도에서 성공합니다. **아무것도 잃지 않습니다.** Workflow Task 는 무한 재시도되므로, 코드가 고쳐지는 순간 자동으로 이어집니다.

이것이 "Workflow Task 실패는 워크플로우를 죽이지 않는다"는 설계의 이유입니다. Temporal 은 이 상황을 **일시적 장애**로 취급합니다.

### ② `reset` 으로 되감는다

코드를 되돌릴 수 없는 경우(이미 데이터 마이그레이션이 끝났다든가)에 씁니다.

```bash
temporal workflow reset \
  --workflow-id order-2001 \
  --type LastWorkflowTask \
  --reason "v2 배포로 비결정성 발생, 마지막 정상 지점으로 되감기"
```

**결과**
```
Reset workflow successfully.
  WorkflowId  order-2001
  RunId       8e30d1f9-4a72-4b88-9c05-6f1e3d7a2b44   ← 새 RunId
```

`reset` 은 히스토리를 **지정한 지점까지 잘라 내고 그 뒤를 새 Run 으로 다시 실행**합니다. 잘린 뒤의 이벤트들은 새 히스토리에 재생성됩니다.

| `--type` | 되감는 지점 |
|---|---|
| `LastWorkflowTask` | 마지막으로 **성공한** Workflow Task 직후 |
| `FirstWorkflowTask` | 맨 처음. 사실상 처음부터 다시 실행 |
| `LastContinuedAsNew` | 마지막 Continue-As-New 지점 |
| `--event-id N` 지정 | 임의의 `WorkflowTaskCompleted` 이벤트 |

새 Run 의 히스토리를 보면 이렇게 생겼습니다.

```bash
temporal workflow show -w order-2001
```

**결과** (옛 Run)
```
Progress:
  ID          Time                     Type
   1~4                        WorkflowExecutionStarted / TaskScheduled / Started / Completed
   5  2026-03-18T08:44:10Z  ActivityTaskScheduled     [charge]
   6  2026-03-18T08:44:10Z  ActivityTaskStarted
   7  2026-03-18T08:44:10Z  ActivityTaskCompleted      ← 완료된 액티비티 결과는 재사용됨
   8~10                       WorkflowTaskScheduled / Started / Completed
  11  2026-03-18T08:44:10Z  WorkflowExecutionContinuedAsNew   ← reset 지점

Result:
  Status: CONTINUED_AS_NEW
```

> ⚠️ **`reset` 은 액티비티를 다시 실행합니다**
> `FirstWorkflowTask` 로 reset 하면 `charge` 가 **다시 호출됩니다.** 멱등하지 않으면 이중 결제입니다. Step 09 의 멱등성이 여기서도 생명줄입니다.
> `LastWorkflowTask` 는 이미 완료된 액티비티의 결과를 히스토리에서 재사용하므로 상대적으로 안전하지만, 마지막 Workflow Task 이후에 시작된 액티비티는 재실행됩니다.

여러 개를 한 번에 리셋하려면 `reset-batch` 를 씁니다.

```bash
temporal workflow reset-batch \
  --query 'WorkflowType="OrderWorkflow" AND ExecutionStatus="Running"' \
  --reset-type LastWorkflowTask \
  --reason "v2 rollback"
```

**결과**
```
Started batch reset operation.
  JobId  a3f81c07-52de-4b19-8e30-9d1c4f2a6b78
Use 'temporal batch describe --job-id a3f81c07-52de-4b19-8e30-9d1c4f2a6b78' to check progress.
```

### ③ `terminate` (최후 수단)

되살릴 수 없다고 판단되면 종료시킵니다.

```bash
temporal workflow terminate -w order-2001 --reason "복구 불가 — 수동 처리 큐로 이관"
```

**결과**
```
Workflow terminated.
```

`terminate` 는 **보상 로직을 실행하지 않습니다.** Saga 의 `catch` 블록조차 돌지 않습니다. 결제된 채로, 재고가 묶인 채로 끝납니다. 반드시 수동 처리 목록에 남기고 사람이 정리해야 합니다.

`cancel` 은 다릅니다 — `CancellationException` 을 워크플로우에 던지므로 `catch` 블록이 돌고 보상이 실행됩니다. 하지만 워크플로우가 이미 리플레이에 실패하고 있다면 `cancel` 도 처리되지 못합니다.

| 명령 | 보상 실행 | 복구 가능 | 언제 |
|---|---|---|---|
| 코드 롤백 | — | ✅ 완전 복구 | **항상 이것부터** |
| `reset` | 다시 실행됨 | ✅ 되감기 | 코드 롤백이 불가능할 때 |
| `cancel` | ✅ 실행됨 | ❌ | 정상 취소 (깨진 워크플로우엔 안 통함) |
| `terminate` | ⛔ 실행 안 됨 | ❌ | 최후 수단. 수동 정리 필수 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| 문제의 본질 | 오늘 코드가 3주 전 히스토리를 리플레이할 수 있는가. **로컬 테스트로는 절대 발견 못 함** |
| `NonDeterministicException` | `Failure handling event N ... does not match command type ...` — 어긋난 이벤트 번호를 알려 줌 |
| 깨진 뒤 상태 | 워크플로우는 죽지 않고 `Pending Workflow Task` 의 Attempt 를 올리며 **영원히 정지**. History Length 는 안 늘어남 |
| 판정 기준 | 그 변경이 **Command 의 종류·순서·개수**를 바꾸는가 |
| 안전한 변경 | 액티비티 구현 내부, 재시도/타임아웃 옵션(신규 스케줄부터), 로깅, 새 타입 추가 |
| 위험한 변경 | 액티비티 추가/삭제/순서변경/이름변경/인자변경, `sleep` 추가삭제, 조건분기, 시그널명, 자식 워크플로우, `Promise` 순서 |
| `getVersion` | `getVersion(changeId, DEFAULT_VERSION, 1)`. 옛 실행은 `-1`, 새 실행은 `1` |
| 마커 | 히스토리에 `MarkerRecorded(markerName="Version")` 로 박제 → 리플레이 시 같은 값 반환 |
| `changeId` 규칙 | 유일 · 호출 순서 불변 · 문자열 절대 변경 금지 |
| 분기 정리 시점 | `minSupported` 를 올려 제거. **최장 수명 + Retention Period** 뒤에야 안전 |
| 대안 ② | 워크플로우 타입명 버저닝. 새 코드가 깨끗하지만 옛 코드를 계속 유지해야 함 |
| 대안 ③ | Task Queue 분리. 물리적 격리, 롤백은 Worker 종료 |
| Worker Versioning | Build ID 로 서버가 라우팅. 실행 중인 것은 자기 Build ID Worker 로만 감. **실험적 기능** |
| 호환 세트 | `add-new-compatible` — 리플레이 호환 변경일 때만. 호환성 판단은 여전히 개발자 몫 |
| 배포 체크리스트 | 분류 → getVersion → **리플레이 테스트** → 카운트 → 카나리 → 메트릭 감시 → 롤백 기준 |
| 복구 우선순위 | ① 코드 롤백(완전 복구) ② `reset --type LastWorkflowTask` ③ `terminate`(보상 안 돎) |
| 함정 1 | 로컬 테스트는 100% 통과. 리플레이 테스트 없이는 발견 불가 |
| 함정 2 | `getVersion` 을 조건문/루프 안에 넣으면 그 자체가 비결정적 |
| 함정 3 | `getVersion` 호출을 통째로 지우면 `MarkerRecorded` 와 어긋나 옛 실행이 다시 깨짐 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. v1 워크플로우를 띄운 상태에서 액티비티를 추가해 `NonDeterministicException` 을 재현하고, 예외 메시지의 이벤트 번호가 왜 그 숫자인지 설명하기
2. 주어진 코드 변경 8개를 안전/위험으로 분류하고, 위험한 것은 "히스토리의 무엇과 어긋나는지" 한 줄로 쓰기
3. 1번의 변경을 `Workflow.getVersion` 으로 감싸 옛 실행과 새 실행이 모두 정상 동작하게 만들기
4. 2차 변경(`maxSupported` 를 2로)을 추가하고, 세 종류의 실행이 각각 어떤 경로를 타는지 히스토리로 확인하기
5. `getVersion` 을 `if` 블록 안에 넣은 잘못된 코드를 만들고, 어떤 시나리오에서 깨지는지 서술하기
6. `getVersion` 호출을 삭제한 뒤 옛 실행을 리플레이해 `MARKER_RECORDED` 불일치 예외를 재현하기
7. 깨진 워크플로우를 `temporal workflow reset --type LastWorkflowTask` 로 복구하고, 새 RunId 의 히스토리가 어디서부터 재생성됐는지 확인하기

---

## 다음 단계

이 스텝의 모든 방어책은 하나의 전제 위에 서 있습니다 — **배포 전에 리플레이가 깨지는지 알 수 있어야 한다**는 것입니다. 체크리스트 3번이 없으면 나머지는 사후 대응일 뿐입니다.

다음 스텝은 `WorkflowReplayer` 로 운영 히스토리를 CI 에서 검증하는 법, `TestWorkflowEnvironment` 로 30일 sleep 을 밀리초 안에 끝내는 법, 액티비티를 목으로 대체해 실패 경로를 테스트하는 법을 다룹니다.

→ [Step 11 — 테스트](../step-11-testing/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 다른 스텝과 달리 **순서를 반드시 지켜야 합니다** — `Practice.java` 는 v1 워크플로우를 띄워 히스토리를 만든 뒤 v2 로 바꿔 예외를 재현하는 구조라, 중간을 건너뛰면 재현 자체가 되지 않습니다. 세 파일 모두 단일 파일 안에 v1/v2/getVersion 세 버전의 구현을 `static nested class` 로 나란히 담아, 파일 하나로 비교하며 읽을 수 있게 했습니다.

### Practice.java

본문 10-1 ~ 10-7 의 모든 코드를 절 번호 주석과 함께 담았습니다.

- **`main` 에 `--phase` 인자가 있습니다.** `phase=1` 은 v1 Worker 를 띄우고 `order-2001` 을 시작해 `TimerStarted` 에서 멈춘 히스토리를 만듭니다. `phase=2` 는 **Worker 를 v2 구현으로 바꿔** 띄웁니다. 이때 `order-2001` 의 Workflow Task 가 v2 Worker 에게 배정되면서 `NonDeterministicException` 이 콘솔에 쏟아집니다. 이 두 단계를 나눈 것이 이 파일의 전부입니다.
- `[10-1]` 의 `V1Impl` 과 `V2Impl` 은 **딱 한 줄 차이**입니다(`fraudActivity.check(...)`). 그 한 줄이 이벤트 5의 타입을 바꾸고, 그것이 예외 메시지의 `event 5` 입니다. 두 클래스를 나란히 놓고 diff 해 보세요.
- `[10-3]` 의 `VersionedImpl` 이 정답 구현입니다. `getVersion` 이 메서드 최상위에서 **무조건** 호출되고 반환값으로만 분기한다는 점을 확인하세요. `phase=3` 으로 띄우면 `order-2001` 이 다시 진행되고 새로 시작한 `order-2002` 에는 `MarkerRecorded` 가 남습니다. 바로 아래 `BadVersionedImpl` 은 함정 2의 재현용으로, `getVersion` 이 `if (req.amount() > 100000)` 안에 있어 **저가 주문에서는 잘 돌고 고가 주문에서만 깨집니다** — 가장 발견하기 어려운 형태의 버그입니다.
- `[10-4]` 의 `OrderWorkflowV2Impl` 은 타입명 버저닝 예시입니다. `V1Impl` 과 함께 같은 Worker 에 등록되어 있어 `temporal workflow list` 로 두 타입의 공존을 확인할 수 있습니다.
- `[10-5]` 의 `buildWorkerOptions` 는 Build ID 설정을 담고 있지만 **기본적으로 비활성**입니다. 셀프호스팅에서는 주석의 `dynamicconfig/development.yaml` 발췌를 먼저 적용해야 하며, 안 켜면 `worker versioning data APIs are disabled on this cluster` 가 납니다. 파일 하단 `printDeploymentChecklist` 는 10-6 의 체크리스트를 CLI 명령 형태로 출력하므로 실제 배포 때 복붙해 쓸 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1·4·6·7** 은 실제로 실행하고 **히스토리를 관찰**하는 문제입니다. 각각 워크플로우를 띄우고 `temporal workflow show` / `describe` 를 돌려 결과를 주석에 기록하세요. 코드만 읽어서는 답이 나오지 않습니다.
- **문제 2** 는 코드를 짜지 않는 유일한 문제입니다. 8개의 변경 시나리오가 주석으로 나열돼 있고, 각각에 `// 안전 / 위험 — 이유` 를 적으면 됩니다. 8개 중 3개가 함정이니 표(10-2)를 그대로 대입하지 말고 "Command 를 내는 줄이 바뀌는가"로 판정하세요.
- **문제 3** 이 이 파일의 핵심입니다. `Q3Impl` 의 뼈대만 주어져 있고 `getVersion` 호출과 분기를 직접 채워야 합니다. `changeId` 문자열도 여러분이 정하는데, **한번 정하면 이후 문제 4·6 에서도 같은 문자열을 써야** 하므로 파일 상단 상수로 빼 두는 것을 권합니다.
- **문제 5** 는 일부러 잘못된 코드를 작성하는 문제입니다. `getVersion` 을 `if` 안에 넣은 뒤, 어떤 두 실행(하나는 조건 만족, 하나는 불만족)이 서로 다른 히스토리를 만드는지 주석으로 서술하세요. 실행까지 해 보면 좋지만 서술만으로도 충분합니다.
- ⚠️ **주의 — 문제 6 은 문제 3·4 를 먼저 풀어야 합니다.** `MarkerRecorded` 가 있는 히스토리가 존재해야 그것을 삭제한 코드로 리플레이할 수 있기 때문입니다. `order-3002` 를 문제 4 에서 만들어 두었다는 전제로 되어 있습니다.
- 파일 맨 아래 `countRunningByType` 과 `dumpHistoryToFile` 은 헬퍼입니다. 전자는 10-6 체크리스트 5번의 카운트를 Java 로 하고, 후자는 히스토리를 `src/test/resources/histories/` 에 떨어뜨려 Step 11 의 리플레이 테스트 입력으로 쓸 수 있게 합니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답 코드와 "왜 그 답인가"를 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 예외 메시지의 `event 5` 가 왜 5인지를 이벤트 번호 하나하나 짚어 설명합니다. 1~4 는 어떤 코드 변경에도 동일하게 생기는 고정 이벤트(Started/TaskScheduled/TaskStarted/TaskCompleted)이고, 코드가 만드는 첫 Command 가 5번 자리에 온다는 것이 요점입니다. 그래서 **워크플로우 첫 줄을 바꾸면 항상 `event 5`** 입니다.
- **정답 2** 의 8개 판정 중 함정은 (c) 재시도 옵션 변경, (f) DTO 필드 추가, (h) 헬퍼 메서드 추출입니다. 셋 다 "안전"이지만 각각 단서가 붙습니다 — (c)는 **이미 스케줄된 액티비티에는 반영 안 됨**, (f)는 **기본값이 있어야 함**, (h)는 **추출 과정에서 호출 순서가 안 바뀌었을 때만**. 이 단서를 놓치면 실무에서 사고가 납니다.
- **정답 3** 은 `getVersion` 의 위치가 왜 메서드 최상위여야 하는지를 반례와 함께 설명합니다. `if (version == Workflow.DEFAULT_VERSION) { }` 처럼 **빈 블록을 명시적으로 쓰는** 스타일을 권하는데, `if (version != DEFAULT_VERSION) { check(); }` 로 쓰면 나중에 버전이 3~4개로 늘었을 때 조건이 뒤엉키기 때문입니다.
- **정답 4** 는 세 종류 실행의 히스토리를 나란히 놓고 비교합니다. 마커 없음 / `version=1` / `version=2` 세 경우의 `MarkerRecorded` JSON 을 모두 실어 두었고, Base64 디코드 결과까지 주석에 적어 두었습니다.
- **정답 5** 의 핵심은 "이 버그는 조건이 참인 실행에서만 나타난다"는 점입니다. 10만원 이하 주문 99%는 정상이고 고액 주문 1%만 깨지므로, **메트릭에서 노이즈로 묻힙니다.** 그리고 나중에 임계값을 10만원 → 5만원으로 바꾸는 순간 그 사이 구간의 실행들이 전부 깨집니다.
- **정답 6** 은 삭제 순서를 3단계로 정리합니다: ① 분기만 제거하고 호출은 남긴다 ② 마커 있는 실행이 전부 종료 + retention 경과 ③ 그제서야 호출 삭제. 그리고 "실무에서는 ③을 영원히 안 하는 게 정답일 수 있다 — `getVersion` 한 줄의 비용이 사고보다 훨씬 싸다"는 결론을 답니다.
- **정답 7** 은 reset 전후 히스토리를 비교하며 `WorkflowExecutionContinuedAsNew` 로 옛 Run 이 닫히고 새 RunId 가 생기는 구조를 설명합니다. 특히 **`FirstWorkflowTask` 로 reset 하면 `charge` 가 재실행된다**는 점을 Step 09 의 멱등성과 연결해 강조합니다.

```java file="./Solution.java"
```
