# Step 04 — 액티비티

> **학습 목표**
> - `@ActivityInterface` / `@ActivityMethod` 로 액티비티를 정의하고, 워크플로우와 달리 **아무 제약이 없다**는 것을 확인한다
> - `ActivityOptions` 를 워크플로우 코드 안에서 만들고 `Workflow.newActivityStub` 으로 스텁을 얻는다
> - **타임아웃 4종**(ScheduleToStart / StartToClose / ScheduleToClose / Heartbeat)이 각각 어느 구간을 재고 어떤 장애를 잡는지 타임라인으로 구분하고, 터졌을 때의 히스토리 이벤트를 JSON 으로 확인한다
> - Heartbeat 로 진행 상황을 저장해, **10만 건 배치를 5만 건에서 죽였다가 5만 번째부터 재개**한다
> - Temporal 의 at-least-once 보장 때문에 **결제가 두 번 되는 시나리오**를 재현하고 멱등키로 막는다
> - Local Activity 와 일반 Activity 의 히스토리 비용 차이를 실측한다
>
> **선행 스텝**: Step 03 — 워크플로우 정의와 결정성
> **예상 소요**: 120분

---

## 4-0. 실습 준비

```bash
docker compose ps
temporal workflow list --limit 3
```

**결과**
```
NAME                IMAGE                            STATUS         PORTS
temporal            temporalio/auto-setup:1.22.4     Up 12 minutes  0.0.0.0:7233->7233/tcp
temporal-ui         temporalio/ui:2.21.3             Up 12 minutes  0.0.0.0:8233->8080/tcp
temporal-postgres   postgres:15                      Up 12 minutes  5432/tcp

  Status     WorkflowId   Type            StartTime
  COMPLETED  order-1001   OrderWorkflow   2026-03-11T09:12:04Z
  COMPLETED  order-1003   OrderWorkflow   2026-03-11T09:41:02Z
  RUNNING    order-2002   OrderWorkflow   2026-03-11T19:04:11Z
```

---

## 4-1. `@ActivityInterface` — 제약이 없는 세계

Step 03 에서 워크플로우 코드가 할 수 없는 일의 목록을 봤습니다. 액티비티는 그 정반대입니다. **평범한 Java 코드입니다.**

```java
package com.example.order;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivity {

    @ActivityMethod
    String charge(String orderId, long amount);

    @ActivityMethod
    void refund(String paymentId);
}
```

```java
public class PaymentActivityImpl implements PaymentActivity {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
    private final HttpClient http = HttpClient.newHttpClient();   // 필드에 커넥션 풀 ─ 괜찮습니다

    @Override
    public String charge(String orderId, long amount) {
        String requestId = UUID.randomUUID().toString();          // 랜덤 ─ 괜찮습니다
        long start = System.currentTimeMillis();                   // 벽시계 ─ 괜찮습니다

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://pg.example.com/v1/charges"))
                .header("Idempotency-Key", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body(orderId, amount)))
                .build();

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());  // 네트워크 ─ 괜찮습니다
            log.info("PG 응답 {}ms status={}", System.currentTimeMillis() - start, res.statusCode());
            return parsePaymentId(res.body());
        } catch (Exception e) {
            throw ApplicationFailure.newFailure("PG 호출 실패", "PaymentGatewayError", e.getMessage());
        }
    }
}
```

| 항목 | 워크플로우 | 액티비티 |
|---|---|---|
| `@Xxx Interface` 애너테이션 | `@WorkflowInterface` | `@ActivityInterface` |
| 메서드 개수 | `@WorkflowMethod` **정확히 하나** | `@ActivityMethod` **여러 개 가능** |
| 등록 방식 | **클래스**를 등록 (`registerWorkflowImplementationTypes`) | **인스턴스**를 등록 (`registerActivitiesImplementations`) |
| 인스턴스 생명주기 | 실행마다 새로 생성 | Worker 당 하나를 공유 |
| 벽시계·랜덤·스레드 | 금지 | **허용** |
| 네트워크·파일·DB | 금지 | **허용** |
| Spring 의존성 주입 | 사실상 금지 | **권장** |
| 재실행 | 리플레이로 몇 번이든 | 실패 시 재시도 (기본 무한) |

`@ActivityMethod` 는 사실 **생략 가능합니다.** `@ActivityInterface` 가 붙은 인터페이스의 모든 public 메서드가 자동으로 액티비티가 됩니다. 이름을 바꾸고 싶을 때만 붙입니다.

```java
@ActivityInterface
public interface InventoryActivity {

    @ActivityMethod(name = "ReserveStock")   // 기본값은 "Reserve" (메서드명 첫 글자 대문자)
    String reserve(String orderId, String sku, int qty);

    void release(String reservationId);       // 애너테이션 없어도 액티비티. 타입명은 "Release"
}
```

> ⚠️ **함정 — 액티비티 메서드명을 바꾸면 실행 중인 워크플로우가 깨집니다**
> 액티비티 타입 이름은 **히스토리에 문자열로 저장됩니다.**
> ```json
> "activityType": { "name": "Charge" }
> ```
> `charge` 를 `chargeWithFee` 로 리팩터링하고 배포하면, 히스토리에 `Charge` 가 적힌 워크플로우를 리플레이할 때 SDK 가 그 타입을 찾지 못합니다.
> ```
> io.temporal.failure.ApplicationFailure: Activity Type "Charge" is not registered
>   with a worker. Known types are: ChargeWithFee, Refund, Reserve, Release
> ```
> 해결은 `@ActivityMethod(name = "Charge")` 로 **옛 이름을 못 박는 것**입니다. 자바 메서드명은 자유롭게 바꾸되 액티비티 타입명은 고정하세요. 본격적인 대응은 Step 10(버저닝)에서 다룹니다.

---

## 4-2. `ActivityOptions` 와 스텁 생성

워크플로우는 액티비티를 직접 호출하지 않습니다. **스텁(stub)** 을 통해 호출하고, 스텁은 실제로는 "이 액티비티를 스케줄해 달라"는 Command 를 만듭니다.

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    // ActivityOptions 는 워크플로우 코드 안에서 만든다.
    // Worker 설정이 아니라 "이 워크플로우가 이 액티비티를 어떻게 부를 것인가"의 선언이다.
    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToStartTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(5)
                            .build())
                    .build());
    // ...
}
```

**옵션이 워크플로우 코드 안에 있다는 사실이 중요합니다.** 같은 액티비티라도 워크플로우마다 다른 타임아웃을 줄 수 있고, 옵션을 바꾸면 그것도 히스토리에 기록됩니다.

```bash
temporal workflow show -w order-1001 --output json | jq '.events[4].activityTaskScheduledEventAttributes'
```

**결과**
```json
{
  "activityId": "5",
  "activityType": { "name": "Charge" },
  "taskQueue": { "name": "ORDER_TASK_QUEUE", "kind": "TASK_QUEUE_KIND_NORMAL" },
  "input": { "payloads": [ { "data": "IjEwMDEi" }, { "data": "MzkwMDA=" } ] },
  "scheduleToCloseTimeout": "0s",
  "scheduleToStartTimeout": "300s",
  "startToCloseTimeout": "30s",
  "heartbeatTimeout": "0s",
  "workflowTaskCompletedEventId": "4",
  "retryPolicy": {
    "initialInterval": "1s",
    "backoffCoefficient": 2,
    "maximumInterval": "100s",
    "maximumAttempts": 5
  }
}
```

`"0s"` 는 "설정하지 않음 = 무제한"이라는 뜻입니다.

액티비티별로 다른 옵션이 필요하면 메서드 단위로 지정합니다.

```java
private final PaymentActivity payment = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build(),
        Map.of(
                // refund 는 더 느려도 되고, 더 오래 재시도해야 한다
                "Refund", ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(2))
                        .setScheduleToCloseTimeout(Duration.ofHours(24))
                        .build()));
```

---

## 4-3. 타임아웃 4종 완전 비교

이 절이 Step 04 의 하이라이트입니다. 액티비티 타임아웃은 네 가지이고, **재는 구간이 전부 다릅니다.**

### 타임라인

```
  워크플로우가 액티비티를 호출
        │
        ▼
  t0 ─── ActivityTaskScheduled ─────────────────────────────────────────────────►
        │
        │  ◄─── ScheduleToStart ───►│
        │      (Task Queue 에서     │
        │       대기하는 시간)      │
        │                           │
        │                     t1 ─── ActivityTaskStarted ─────────────────────────►
        │                           │
        │                           │◄──────── StartToClose ────────►│
        │                           │      (한 번의 시도가 도는 시간) │
        │                           │                                │
        │                           │  ♥    ♥    ♥    ♥              │
        │                           │  ◄─►                            ← Heartbeat 간격
        │                           │                            t2 ─── Completed / TimedOut
        │                                                             │
        │◄──────────────────── ScheduleToClose ──────────────────────►│
        │   (재시도까지 전부 포함한 총 시간. 시도 1 + 대기 + 시도 2 + ... )
        │
        │   재시도가 일어나면 이렇게 반복된다:
        │   [대기][시도1][백오프][대기][시도2][백오프][대기][시도3] ...
        │    ↑    ↑                                              ↑
        │    S2S  S2C  ─────────────────────────────────────────►│
        │         StartToClose 는 매 시도마다 새로 잰다
```

### 비교표

| 타임아웃 | 재는 구간 | 기본값 | 잡아내는 장애 | 재시도 | 필수 여부 |
|---|---|---|---|---|---|
| **ScheduleToStart** | 큐에 들어간 순간 ~ Worker 가 집어간 순간 | 무한 | Worker 부족, Worker 전부 다운, **잘못된 Task Queue 이름** | **재시도 안 됨** (즉시 실패) | 선택 |
| **StartToClose** | 한 번의 시도가 시작 ~ 끝 | 무한 | 액티비티가 매달림, 외부 API 무응답, 무한 루프 | **재시도됨** | 이것 또는 S2C 필수 |
| **ScheduleToClose** | 첫 스케줄 ~ 최종 완료 (**모든 재시도 포함**) | 무한 | 전체가 너무 오래 걸림. 재시도 총량 제한 | (총량이므로 해당 없음) | 이것 또는 S2C 필수 |
| **Heartbeat** | 하트비트 사이의 간격 | 없음 | **조용히 죽은 Worker** (프로세스 kill, OOM, 네트워크 단절) | 재시도됨 | 장기 액티비티에 강력 권장 |

### 규칙: ScheduleToClose 또는 StartToClose 중 하나는 반드시

둘 다 지정하지 않으면 워크플로우가 실행되는 순간 실패합니다.

```java
// ✘ 타임아웃을 하나도 안 줬다
private final PaymentActivity payment = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder().build());
```

**결과** (Worker 콘솔)
```
09:55:12.331 [workflow-method-order-3001-...] WARN  i.t.i.s.WorkflowExecutionHandler - Workflow execution failure
io.temporal.failure.ApplicationFailure: message='Either ScheduleToCloseTimeout or StartToCloseTimeout is required', type='java.lang.IllegalStateException', nonRetryable=false
	at io.temporal.internal.sync.ActivityStubBase.execute(ActivityStubBase.java:47)
	at io.temporal.internal.sync.ActivityInvocationHandler.lambda$getActivityFunc$0(ActivityInvocationHandler.java:82)
	at io.temporal.internal.sync.ActivityInvocationHandlerBase$ActivityStubInvocationHandler.invoke(ActivityInvocationHandlerBase.java:73)
	at jdk.proxy2/jdk.proxy2.$Proxy14.charge(Unknown Source)
	at com.example.order.OrderWorkflowImpl.processOrder(OrderWorkflowImpl.java:41)
Caused by: java.lang.IllegalStateException: Either ScheduleToCloseTimeout or StartToCloseTimeout is required
	at io.temporal.common.interceptors.ActivityOptionsUtils.validateAndBuildOptions(ActivityOptionsUtils.java:61)
	at io.temporal.internal.sync.SyncWorkflowContext.executeActivity(SyncWorkflowContext.java:265)
	... 4 common frames omitted
```

```bash
temporal workflow describe -w order-3001
```

**결과**
```
Execution Info:
  Workflow Id       order-3001
  Type              OrderWorkflow
  Status            FAILED
  History Length    5

Failure:
  Message           Either ScheduleToCloseTimeout or StartToCloseTimeout is required
  Type              java.lang.IllegalStateException
```

이 경우는 **워크플로우 자체가 FAILED** 입니다. Step 03 의 `NonDeterministicException` 과 달리 코드 로직 예외로 취급되기 때문입니다.

### StartToClose 가 터졌을 때의 히스토리

```java
ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(3))   // 액티비티는 10초 걸린다
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build();
```

```bash
temporal workflow show -w order-3002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T10:02:11Z  WorkflowExecutionStarted
   2  2026-03-11T10:02:11Z  WorkflowTaskScheduled
   3  2026-03-11T10:02:11Z  WorkflowTaskStarted
   4  2026-03-11T10:02:11Z  WorkflowTaskCompleted
   5  2026-03-11T10:02:11Z  ActivityTaskScheduled
   6  2026-03-11T10:02:11Z  ActivityTaskStarted
   7  2026-03-11T10:02:19Z  ActivityTaskTimedOut       ← 3초 × 2회 시도 후
   8  2026-03-11T10:02:19Z  WorkflowTaskScheduled
   9  2026-03-11T10:02:19Z  WorkflowTaskStarted
  10  2026-03-11T10:02:19Z  WorkflowTaskCompleted
  11  2026-03-11T10:02:19Z  WorkflowExecutionFailed

Result:
  Status: FAILED
```

7번 이벤트의 상세를 봅니다.

```bash
temporal workflow show -w order-3002 --output json \
  | jq '.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT")'
```

**결과**
```json
{
  "eventId": "7",
  "eventTime": "2026-03-11T10:02:19.114Z",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT",
  "activityTaskTimedOutEventAttributes": {
    "failure": {
      "message": "activity StartToClose timeout",
      "timeoutFailureInfo": {
        "timeoutType": "TIMEOUT_TYPE_START_TO_CLOSE"
      }
    },
    "scheduledEventId": "5",
    "startedEventId": "6",
    "retryState": "RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED"
  }
}
```

`TIMEOUT_TYPE_START_TO_CLOSE` 와 `RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED` 두 값이 핵심입니다.

### ScheduleToStart 가 터졌을 때

Worker 를 전부 내린 뒤 워크플로우를 시작합니다.

```java
ActivityOptions.newBuilder()
        .setScheduleToStartTimeout(Duration.ofSeconds(10))
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build();
```

**결과**
```json
{
  "eventId": "6",
  "eventTime": "2026-03-11T10:11:32.508Z",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT",
  "activityTaskTimedOutEventAttributes": {
    "failure": {
      "message": "activity ScheduleToStart timeout",
      "timeoutFailureInfo": {
        "timeoutType": "TIMEOUT_TYPE_SCHEDULE_TO_START"
      }
    },
    "scheduledEventId": "5",
    "startedEventId": "0",
    "retryState": "RETRY_STATE_NON_RETRYABLE_FAILURE"
  }
}
```

두 가지를 주목하세요.

1. `"startedEventId": "0"` — **시작조차 못 했습니다.** `ActivityTaskStarted` 이벤트 자체가 없습니다.
2. `"retryState": "RETRY_STATE_NON_RETRYABLE_FAILURE"` — **재시도되지 않습니다.** 재시도해도 어차피 같은 큐에 들어가 같은 이유로 대기할 것이기 때문입니다.

### ScheduleToClose 가 터졌을 때

```json
{
  "eventId": "14",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT",
  "activityTaskTimedOutEventAttributes": {
    "failure": {
      "message": "activity ScheduleToClose timeout",
      "timeoutFailureInfo": {
        "timeoutType": "TIMEOUT_TYPE_SCHEDULE_TO_CLOSE",
        "lastHeartbeatDetails": null
      },
      "cause": {
        "message": "activity StartToClose timeout",
        "timeoutFailureInfo": { "timeoutType": "TIMEOUT_TYPE_START_TO_CLOSE" }
      }
    },
    "scheduledEventId": "5",
    "startedEventId": "13",
    "retryState": "RETRY_STATE_TIMEOUT"
  }
}
```

`cause` 에 **직전 시도가 왜 실패했는지**가 중첩되어 들어갑니다. 세 번째 시도가 StartToClose 로 터지던 도중 전체 예산(ScheduleToClose)이 소진된 것입니다.

> 💡 **실무 팁 — 어떻게 정하나**
> - **StartToClose**: 액티비티의 p99 응답시간 × 2~3배. 외부 API 라면 그쪽 SLA + 여유.
> - **ScheduleToStart**: "이 정도 큐 대기는 이미 사고다" 싶은 값. 보통 1~5분. **알림용 지표로 쓰기 좋습니다.**
> - **ScheduleToClose**: 비즈니스 데드라인. "결제는 아무리 재시도해도 30분 안에 끝나야 한다."
> - **Heartbeat**: 하트비트 주기의 2~3배. 30초마다 뛴다면 90초.
>
> 셋 다 주는 것이 원칙이지만, 하나만 고르라면 **StartToClose** 입니다.

> ⚠️ **함정 — StartToClose 만 넉넉히 주고 ScheduleToStart 를 안 주면**
> ```java
> ActivityOptions.newBuilder()
>         .setStartToCloseTimeout(Duration.ofMinutes(10))   // 넉넉하게!
>         .build();                                          // ScheduleToStart 없음 = 무한
> ```
> 배포 사고로 Worker 가 전부 죽었다고 합시다. 액티비티 태스크는 Task Queue 에 쌓입니다. StartToClose 는 **시작한 뒤부터** 재는 타이머이므로 아직 작동하지 않습니다. ScheduleToStart 가 무한이니 아무도 알려 주지 않습니다.
> **결과**
> ```
> $ temporal task-queue describe --task-queue ORDER_TASK_QUEUE --task-queue-type activity
> BuildId   TaskQueueType  Pollers  BacklogCount  ApproximateBacklogAge
>           ACTIVITY       0        84291         3h 42m 11s
> ```
> **워크플로우는 전부 Running, 액티비티는 3시간 42분째 큐에서 대기 중입니다.** 에러도 알림도 없습니다. 고객은 "주문했는데 결제가 안 됐다"고 문의합니다.
> ScheduleToStart 를 5분으로 걸어 뒀다면 5분 만에 `ActivityTaskTimedOut` 이 터져 워크플로우가 실패하고, 실패율 알림이 즉시 울렸을 것입니다. **ScheduleToStart 는 성능 옵션이 아니라 관측 장치입니다.**

---

## 4-4. Heartbeat — 조용히 죽은 Worker 를 감지한다

10만 건을 처리하는 배치 액티비티가 있다고 합시다. StartToClose 는 2시간으로 잡았습니다. 30분쯤 지나 Worker 컨테이너가 OOM 으로 죽었습니다.

**Temporal 서버는 이 사실을 모릅니다.** 서버 입장에서 액티비티는 여전히 "시작됨" 상태이고, StartToClose 2시간이 지날 때까지 아무 일도 일어나지 않습니다. **1시간 30분을 낭비합니다.**

Heartbeat 는 이 구멍을 메웁니다.

```java
// [4-4] Heartbeat 를 보내는 액티비티
@Override
public String processBatch(String batchId, int totalCount) {
    ActivityExecutionContext ctx = Activity.getExecutionContext();

    for (int i = 0; i < totalCount; i++) {
        processOne(batchId, i);

        if (i % 1000 == 0) {
            ctx.heartbeat(i);          // 진행 상황을 details 로 함께 보낸다
            log.info("배치 진행 {}/{}", i, totalCount);
        }
    }
    return batchId + " DONE " + totalCount;
}
```

```java
ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofHours(2))
        .setHeartbeatTimeout(Duration.ofSeconds(30))   // 30초 안에 하트비트가 없으면 죽은 것으로 간주
        .build();
```

Worker 를 `kill -9` 로 죽입니다.

**결과** (30초 뒤 히스토리)
```json
{
  "eventId": "7",
  "eventTime": "2026-03-11T10:44:03.291Z",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT",
  "activityTaskTimedOutEventAttributes": {
    "failure": {
      "message": "activity Heartbeat timeout",
      "timeoutFailureInfo": {
        "timeoutType": "TIMEOUT_TYPE_HEARTBEAT",
        "lastHeartbeatDetails": {
          "payloads": [ { "metadata": { "encoding": "anNvbi9wbGFpbg==" }, "data": "NTAwMDA=" } ]
        }
      }
    },
    "scheduledEventId": "5",
    "startedEventId": "6",
    "retryState": "RETRY_STATE_IN_PROGRESS"
  }
}
```

**1시간 30분 → 30초.** 그리고 `lastHeartbeatDetails` 에 `NTAwMDA=` (base64 디코딩하면 `50000`)이 들어 있습니다.

### heartbeat details 로 이어서 하기

재시도가 시작될 때, 액티비티는 마지막 하트비트 값을 꺼내 볼 수 있습니다.

```java
// [4-4] 재시도 시 중단 지점부터 재개
@Override
public String processBatch(String batchId, int totalCount) {
    ActivityExecutionContext ctx = Activity.getExecutionContext();

    // 이전 시도가 남긴 진행 상황이 있으면 그 지점부터 시작한다
    int start = ctx.getHeartbeatDetails(Integer.class).orElse(0);
    log.info("배치 시작 batchId={} start={} total={}", batchId, start, totalCount);

    for (int i = start; i < totalCount; i++) {
        processOne(batchId, i);

        if (i % 1000 == 0) {
            ctx.heartbeat(i);
        }
    }
    return batchId + " DONE " + totalCount;
}
```

**결과** (Worker 콘솔 — 첫 시도)
```
10:41:02.118 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 시작 batchId=B-77 start=0 total=100000
10:41:04.220 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 0/100000
10:41:34.881 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 25000/100000
10:42:31.402 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 50000/100000
```

여기서 `kill -9`.

**결과** (Worker 재기동 후 — 두 번째 시도)
```
10:44:33.507 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 시작 batchId=B-77 start=50000 total=100000
10:44:34.610 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 50000/100000
10:45:31.118 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 75000/100000
10:46:29.774 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 완료 100000/100000
```

**5만 번째부터 재개했습니다.** 처음부터 다시 했다면 약 3분 40초가 더 들었을 것을 1분 56초에 끝냈습니다.

> 💡 **하트비트는 스로틀링됩니다**
> `ctx.heartbeat()` 를 호출한다고 매번 서버로 gRPC 가 나가지는 않습니다. SDK 가 **HeartbeatTimeout 의 80%** 간격으로 묶어서 보냅니다. HeartbeatTimeout 이 30초면 실제 전송은 약 24초마다입니다. 그래서 1,000건마다 호출해도 서버 부하가 되지 않습니다.
> 반대로 **HeartbeatTimeout 을 설정하지 않으면 `heartbeat()` 호출은 아무 효과가 없습니다.** details 도 저장되지 않습니다. 이 조합을 자주 실수합니다.

---

## 4-5. 취소 감지 — heartbeat 가 예외를 던진다

워크플로우가 취소되거나(`temporal workflow cancel`) 액티비티 자체가 취소되면, 액티비티는 그것을 어떻게 알까요? **하트비트를 통해 알게 됩니다.**

서버는 취소 요청을 받으면 그것을 기록해 두었다가, 액티비티가 다음 하트비트를 보내는 순간 응답에 "취소됨" 플래그를 실어 보냅니다. SDK 는 이를 받아 `ctx.heartbeat()` 호출 지점에서 `ActivityCanceledException` 을 던집니다.

```java
@Override
public String processBatch(String batchId, int totalCount) {
    ActivityExecutionContext ctx = Activity.getExecutionContext();
    int start = ctx.getHeartbeatDetails(Integer.class).orElse(0);

    for (int i = start; i < totalCount; i++) {
        processOne(batchId, i);

        if (i % 1000 == 0) {
            try {
                ctx.heartbeat(i);
            } catch (ActivityCompletionException e) {
                // ActivityCanceledException 은 ActivityCompletionException 의 하위 타입
                log.info("취소 감지 — {}건 처리 후 정리하고 종료", i);
                cleanupPartialWork(batchId, i);
                throw e;                      // 반드시 다시 던져야 취소가 서버에 반영된다
            }
        }
    }
    return batchId + " DONE";
}
```

```bash
temporal workflow cancel -w order-4001
```

**결과** (Worker 콘솔)
```
11:03:12.408 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 배치 진행 33000/100000
11:03:36.117 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.BatchActivityImpl - 취소 감지 — 34000건 처리 후 정리하고 종료
11:03:36.120 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] WARN  i.t.i.a.ActivityTaskHandlerImpl - Activity failure. ActivityId=5, ActivityType=ProcessBatch, WorkflowId=order-4001
io.temporal.client.ActivityCanceledException: Activity cancelled by the workflow or the service
	at io.temporal.internal.activity.ActivityExecutionContextImpl.doHeartBeat(ActivityExecutionContextImpl.java:141)
	at io.temporal.internal.activity.ActivityExecutionContextImpl.heartbeat(ActivityExecutionContextImpl.java:103)
	at com.example.order.BatchActivityImpl.processBatch(BatchActivityImpl.java:38)
```

```bash
temporal workflow show -w order-4001
```

**결과**
```
Progress:
  ID          Time                     Type
   ...
   6  2026-03-11T11:02:41Z  ActivityTaskStarted
   7  2026-03-11T11:03:31Z  WorkflowExecutionCancelRequested
   8  2026-03-11T11:03:36Z  ActivityTaskCanceled
   9  2026-03-11T11:03:36Z  WorkflowTaskScheduled
  ...
  12  2026-03-11T11:03:36Z  WorkflowExecutionCanceled

Result:
  Status: CANCELED
```

> ⚠️ **하트비트를 보내지 않는 액티비티는 취소를 감지할 수 없습니다**
> 취소 전달 경로가 하트비트뿐이기 때문입니다. 하트비트 없는 액티비티는 취소 요청이 와도 **끝까지 실행됩니다.** 워크플로우는 `Canceled` 로 닫히는데 액티비티는 뒤에서 계속 도는 상태가 됩니다.
> 오래 걸리는 액티비티에는 무조건 하트비트를 넣으세요. 취소 대응이 필요 없더라도 죽은 Worker 감지만으로 값어치를 합니다.

---

## 4-6. 액티비티 멱등성 — at-least-once 라는 계약

Temporal 이 보장하는 것은 **at-least-once** 입니다. exactly-once 가 아닙니다.

> **액티비티는 최소 한 번 실행됩니다. 두 번 실행될 수도 있습니다.**

가장 무서운 시나리오는 "타임아웃으로 재시도했는데 사실 첫 시도가 성공했던" 경우입니다.

```
  StartToClose = 10초, 결제 게이트웨이는 평소 2초

  t=0s    ActivityTaskScheduled
  t=0s    ActivityTaskStarted        Worker A 가 집어감
  t=0s    Worker A → PG 로 HTTP POST /charges  (39,000원)
  t=2s    PG 내부에서 결제 승인 완료. 카드사 승인 떨어짐. 💳 39,000원 결제됨
  t=2s    PG 가 200 응답을 보냄
          ↓
          ✘ 응답이 네트워크에서 유실됨 (LB 재시작 / TCP RST / Worker GC 정지)
          ↓
  t=10s   StartToClose 타임아웃. ActivityTaskTimedOut
  t=11s   재시도. ActivityTaskScheduled (attempt=2)
  t=11s   Worker B 가 집어감
  t=11s   Worker B → PG 로 HTTP POST /charges  (39,000원)
  t=13s   PG 가 이것을 **새 결제 요청**으로 처리. 💳 39,000원 또 결제됨
  t=13s   200 응답 성공
  t=13s   ActivityTaskCompleted

  워크플로우는 정상 완료. 히스토리에도 아무 이상 없음.
  고객 카드에는 78,000원.
```

**히스토리를 아무리 봐도 이 사고는 보이지 않습니다.** 이것이 "에러 없이 조용히 잘못 동작하는" 전형입니다.

### 해결 — 멱등키를 워크플로우에서 만들어 전달한다

```java
// 워크플로우 코드
@Override
public String processOrder(OrderRequest req) {
    // Run ID 를 시드로 하는 결정적 UUID. 리플레이해도 같은 값. (Step 03 의 3-7)
    String idemKey = Workflow.randomUUID().toString();

    String paymentId = payment.chargeIdempotent(req.orderId(), req.amount(), idemKey);
    return req.orderId() + " COMPLETED";
}
```

```java
// 액티비티 구현
@Override
public String chargeIdempotent(String orderId, long amount, String idempotencyKey) {
    HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://pg.example.com/v1/charges"))
            .header("Idempotency-Key", idempotencyKey)   // ← 여기
            .POST(...)
            .build();
    // ...
}
```

이제 타임라인이 이렇게 바뀝니다.

```
  t=0s    Worker A → PG (Idempotency-Key: aaa-111)   → 💳 39,000원 결제
  t=2s    응답 유실
  t=10s   타임아웃
  t=11s   Worker B → PG (Idempotency-Key: aaa-111)   ← 같은 키!
  t=11s   PG: "이 키는 이미 처리했다" → 저장된 첫 응답을 그대로 반환. 결제 안 함
  t=11s   ActivityTaskCompleted, paymentId = 첫 시도와 동일
```

**멱등키의 세 가지 조건**을 정리합니다.

| 조건 | 이유 |
|---|---|
| **워크플로우에서 만든다** | 액티비티 안에서 만들면 재시도마다 새 키가 된다 |
| **`Workflow.randomUUID()` 를 쓴다** | `UUID.randomUUID()` 는 리플레이 때 값이 바뀐다 |
| **액티비티 인자로 전달한다** | 인자는 히스토리에 기록되어 모든 재시도가 같은 값을 받는다 |

액티비티 인자가 히스토리에 박혀 있으므로 재시도가 같은 키를 받는다는 것을 확인합니다.

```bash
temporal workflow show -w order-5001 --output json \
  | jq '.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_SCHEDULED")
        | .activityTaskScheduledEventAttributes.input.payloads[2].data' | base64 -d
```

**결과**
```
"3f2a9c14-7b01-48d4-aef6-900c1d2f88a4"
```

`ActivityTaskScheduled` 는 재시도할 때 **새로 생기지 않습니다.** 서버가 같은 이벤트의 입력으로 다시 디스패치할 뿐이라, 몇 번을 재시도해도 액티비티는 같은 세 번째 인자를 받습니다.

> ⚠️ **함정 — 멱등하지 않은 액티비티에 재시도를 켜 두면 결제가 두 번 됩니다**
> Temporal 의 기본 RetryOptions 는 **무한 재시도**입니다. 아무것도 설정하지 않으면 켜져 있습니다.
> 액티비티가 멱등하지 않다면 선택지는 셋입니다.
> 1. **멱등하게 만든다** (권장). 외부 API 의 Idempotency-Key, DB 의 `INSERT ... ON CONFLICT DO NOTHING`, 유니크 제약.
> 2. 재시도를 끈다: `RetryOptions.newBuilder().setMaximumAttempts(1).build()`. 대신 일시적 네트워크 오류에도 워크플로우가 실패합니다.
> 3. 실패를 non-retryable 로 분류한다: `setDoNotRetry("PaymentDuplicateError")`.
>
> 실무에서는 1번 외에 답이 없습니다. **"이 액티비티가 두 번 실행되면 무슨 일이 벌어지나"** 를 모든 액티비티에 대해 답할 수 있어야 합니다.

> 💡 **읽기 액티비티는 이미 멱등입니다**
> 조회·계산·검증처럼 부수효과가 없는 액티비티는 신경 쓸 것이 없습니다. 멱등성이 문제가 되는 것은 **쓰기**뿐입니다: 결제, 이메일 발송, 재고 차감, 외부 시스템 등록.

---

## 4-7. Local Activity — 가볍지만 제약이 있다

일반 액티비티는 호출 한 번에 히스토리 이벤트가 **최소 3개**(Scheduled / Started / Completed) 생기고, Task Queue 를 거치는 서버 왕복이 발생합니다. "문자열 포맷팅", "간단한 검증", "로컬 캐시 조회" 같은 밀리초 단위 작업에는 과합니다.

Local Activity 는 **Worker 프로세스 안에서 직접 실행**되고, 히스토리에는 `MarkerRecorded` 하나만 남습니다.

```java
private final ValidationActivity validation = Workflow.newLocalActivityStub(
        ValidationActivity.class,
        LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());
```

10회 호출했을 때의 히스토리를 비교합니다.

**일반 액티비티 10회**
```
$ temporal workflow describe -w order-6001
  History Length    36
  History Size      8712
```

**Local Activity 10회**
```
$ temporal workflow describe -w order-6002
  History Length    16
  History Size      3104
```

```bash
temporal workflow show -w order-6002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T11:22:04Z  WorkflowExecutionStarted
   2  2026-03-11T11:22:04Z  WorkflowTaskScheduled
   3  2026-03-11T11:22:04Z  WorkflowTaskStarted
   4  2026-03-11T11:22:04Z  WorkflowTaskCompleted
   5  2026-03-11T11:22:04Z  MarkerRecorded          ← LocalActivity 1
   6  2026-03-11T11:22:04Z  MarkerRecorded          ← LocalActivity 2
   ...                                              (7~13 도 전부 MarkerRecorded)
  14  2026-03-11T11:22:04Z  MarkerRecorded          ← LocalActivity 10
  15  2026-03-11T11:22:04Z  WorkflowTaskCompleted
  16  2026-03-11T11:22:04Z  WorkflowExecutionCompleted
```

**히스토리 36개 → 16개, 크기 8,712 → 3,104 바이트. 실행 시간은 1.84초 → 0.21초.** 서버 왕복 10회가 0회로 줄었기 때문입니다.

### 비교표

| 항목 | Activity | Local Activity |
|---|---|---|
| 실행 위치 | Task Queue 를 거쳐 임의의 Worker | **워크플로우를 실행 중인 Worker 프로세스 안** |
| 히스토리 | Scheduled / Started / Completed (3개+) | `MarkerRecorded` 1개 |
| 서버 왕복 | 있음 (수십 ms) | 없음 |
| Task Queue 분리 | 가능 (GPU 전용 큐 등) | 불가 |
| 재시도 | 서버가 관리. 히스토리에 기록 | Worker 메모리에서. **Workflow Task 타임아웃 안에서만** |
| Heartbeat | 지원 | **미지원** |
| 취소 | 지원 | 제한적 |
| 권장 실행 시간 | 제한 없음 | **수 초 이내** |
| Worker 재시작 시 | 재시도됨 | **처음부터 다시** (진행 상황 없음) |
| 워크플로우 타임아웃 영향 | 없음 | Workflow Task Timeout(기본 10초)에 갇힘 |

> ⚠️ **함정 — Local Activity 를 장기 작업에 쓰면**
> Local Activity 는 Workflow Task 안에서 실행됩니다. Workflow Task Timeout 기본값은 **10초**입니다. Local Activity 가 그보다 오래 걸리면 SDK 가 내부적으로 Workflow Task 를 heartbeat 하며 연장을 시도하지만, 한계가 있습니다.
> **결과**
> ```
> io.temporal.internal.statemachines.LocalActivityStateMachine: 
>   Local activity exceeded workflow task timeout. Rescheduling as a new workflow task.
> ```
> 결국 히스토리가 오히려 지저분해지고, Worker 가 죽으면 진행 상황이 전부 사라집니다.
> **판단 기준**: 1초 이내에 끝나고, 재시도해도 부담 없고, 외부 시스템을 안 건드리면 Local Activity. 그 외에는 전부 일반 Activity.

---

## 4-8. Activity 등록과 Worker 설정

워크플로우는 **클래스**를 등록하지만 액티비티는 **인스턴스**를 등록합니다.

```java
Worker worker = factory.newWorker(TASK_QUEUE);

// 워크플로우: 클래스 — SDK 가 실행마다 새 인스턴스를 만든다
worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

// 액티비티: 인스턴스 — Worker 가 이 하나를 계속 재사용한다
worker.registerActivitiesImplementations(
        new PaymentActivityImpl(paymentClient),
        new InventoryActivityImpl(inventoryRepo),
        new ShippingActivityImpl(shippingClient),
        new NotificationActivityImpl(mailer));

factory.start();
```

여기서 결론이 하나 나옵니다.

> **액티비티 구현체는 스레드 안전해야 합니다.**

한 Worker 는 기본적으로 액티비티를 **동시에 200개까지** 실행합니다(`maxConcurrentActivityExecutionSize`). 그 200개 스레드가 **같은 인스턴스**의 메서드를 호출합니다.

```java
// ✘ 절대 하지 말 것
public class PaymentActivityImpl implements PaymentActivity {
    private String currentOrderId;              // 인스턴스 필드에 요청별 상태!
    private long total;

    @Override
    public String charge(String orderId, long amount) {
        this.currentOrderId = orderId;          // 다른 스레드가 덮어쓴다
        this.total += amount;                    // 경쟁 조건
        // ... 200 스레드가 이 필드를 서로 짓밟는다
        return doCharge(this.currentOrderId, amount);   // 남의 주문을 결제할 수 있다
    }
}
```

```java
// ✔ 무상태로. 공유 필드는 불변이거나 스레드 안전한 것만.
public class PaymentActivityImpl implements PaymentActivity {
    private final HttpClient http;              // HttpClient 는 스레드 안전
    private final PaymentRepository repo;       // 스프링 리포지터리도 스레드 안전

    public PaymentActivityImpl(HttpClient http, PaymentRepository repo) {
        this.http = http;
        this.repo = repo;
    }

    @Override
    public String charge(String orderId, long amount) {
        // 모든 상태는 지역 변수와 파라미터로만
        String requestId = UUID.randomUUID().toString();
        return doCharge(http, orderId, amount, requestId);
    }
}
```

동시 실행 수와 처리율은 `WorkerOptions` 로 조절합니다.

```java
WorkerOptions options = WorkerOptions.newBuilder()
        .setMaxConcurrentActivityExecutionSize(50)          // 기본 200
        .setMaxConcurrentWorkflowTaskExecutionSize(100)     // 기본 200
        .setMaxWorkerActivitiesPerSecond(20.0)              // Worker 당 초당 액티비티 수 제한
        .setMaxTaskQueueActivitiesPerSecond(100.0)          // Task Queue 전체 초당 제한
        .build();

Worker worker = factory.newWorker(TASK_QUEUE, options);
```

**결과** (`temporal task-queue describe --task-queue ORDER_TASK_QUEUE --task-queue-type activity`)
```
BuildId   TaskQueueType  Pollers  BacklogCount  ApproximateBacklogAge
          ACTIVITY       4        0             0s

Pollers:
  Identity                        LastAccessTime          RatePerSecond
  41822@worker-7d9f4c6b8-xk2mp    2026-03-11T11:31:02Z    100000
  41822@worker-7d9f4c6b8-p4nzq    2026-03-11T11:31:02Z    100000
```

> 💡 **실무 팁 — 무거운 액티비티는 Task Queue 를 분리하세요**
> 이미지 변환이나 ML 추론처럼 CPU·메모리를 많이 먹는 액티비티가 결제 액티비티와 같은 Worker 에서 돌면, 배치 하나가 결제 전체를 굶깁니다.
> ```java
> // 워크플로우 코드에서 액티비티별로 다른 Task Queue 지정
> private final MediaActivity media = Workflow.newActivityStub(
>         MediaActivity.class,
>         ActivityOptions.newBuilder()
>                 .setTaskQueue("MEDIA_TASK_QUEUE")          // ← 전용 큐
>                 .setStartToCloseTimeout(Duration.ofMinutes(30))
>                 .setHeartbeatTimeout(Duration.ofSeconds(30))
>                 .build());
> ```
> 그리고 GPU 인스턴스에 `MEDIA_TASK_QUEUE` 만 폴링하는 Worker 를 따로 띄웁니다. 워크플로우 코드는 한 줄도 안 바뀝니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `@ActivityInterface` | 메서드 **여러 개 가능**. 구현체는 평범한 POJO — 제약 없음 |
| `@ActivityMethod(name=...)` | 액티비티 타입명은 히스토리에 문자열로 저장. **메서드명 변경 시 못 박을 것** |
| `ActivityOptions` | **워크플로우 코드 안에서** 만든다. 히스토리에 기록됨 |
| ScheduleToStart | 큐 대기 시간. 기본 무한. **재시도 안 됨.** Worker 부족·잘못된 큐를 잡는 관측 장치 |
| StartToClose | 한 번의 시도 시간. 재시도됨. 가장 중요한 타임아웃 |
| ScheduleToClose | 재시도 포함 총 시간. 비즈니스 데드라인 |
| Heartbeat | 하트비트 간격. **조용히 죽은 Worker 를 초 단위로 감지** |
| 필수 규칙 | ScheduleToClose 또는 StartToClose 중 **하나는 반드시**. 없으면 워크플로우 FAILED |
| 타임아웃 이벤트 | `ActivityTaskTimedOut` 의 `timeoutType` 으로 어느 타임아웃인지 구분 |
| `heartbeat(details)` | 진행 상황 저장. 재시도 시 `getHeartbeatDetails()` 로 **이어서 실행** |
| 하트비트 스로틀링 | HeartbeatTimeout 의 80% 간격으로 묶어 전송. **HeartbeatTimeout 미설정 시 무효** |
| 취소 감지 | `heartbeat()` 가 `ActivityCanceledException` 을 던진다. 하트비트 없으면 취소를 모른다 |
| at-least-once | 액티비티는 **두 번 실행될 수 있다.** 응답 유실 후 재시도가 전형적 |
| 멱등키 | 워크플로우에서 `Workflow.randomUUID()` 로 만들어 **액티비티 인자로** 전달 |
| Local Activity | `MarkerRecorded` 1개만. 히스토리 36→16, 1.84초→0.21초. 단 하트비트·장기실행 불가 |
| 액티비티 등록 | **인스턴스**를 등록 → Worker 당 하나를 200 스레드가 공유 → **스레드 안전 필수** |
| Task Queue 분리 | 무거운 액티비티는 전용 큐로. `setTaskQueue()` 한 줄 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 네 가지 장애 시나리오에 각각 어떤 타임아웃이 필요한지 고르고 값을 정하기
2. 타임아웃을 하나도 안 준 스텁을 고치고, 안 고쳤을 때의 예외 메시지 적기
3. 10만 건 배치 액티비티에 heartbeat 와 재개 로직 넣기
4. 멱등하지 않은 결제 액티비티를 멱등하게 고치고, 키를 어디서 만들지 정하기
5. 주어진 액티비티 5개를 Activity / Local Activity 로 분류하고 근거 적기
6. 스레드 안전하지 않은 액티비티 구현체를 찾아 고치기

---

## 다음 단계

액티비티가 타임아웃으로 실패하면 Temporal 이 재시도한다는 것을 봤습니다. 그런데 그 재시도는 몇 번까지, 얼마 간격으로 일어날까요. 재시도해도 소용없는 실패(잘못된 카드번호)와 재시도해야 하는 실패(네트워크 오류)는 어떻게 구분할까요. 다음 스텝에서 `RetryOptions`, `ApplicationFailure`, non-retryable 오류 분류를 다룹니다.

→ [Step 05 — 재시도와 실패 처리](../step-05-retry-failure/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 로 4-1 ~ 4-8 의 예제를 재현하고, `Exercise.java` 의 6문제를 푼 뒤, `Solution.java` 로 대조합니다. Practice 의 타임아웃 실습은 **의도적으로 실패하는 워크플로우**를 여러 개 돌리므로, 실행 후 `temporal workflow list` 에 FAILED 가 쌓이는 것이 정상입니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실습 파일입니다.

- `[4-3]` 은 `--timeout` 인자로 실행합니다. `SlowPaymentActivityImpl` 이 10초를 자는 동안 StartToClose 3초가 터지도록 되어 있어, 실행 후 `temporal workflow show -w order-3002 --output json` 으로 `TIMEOUT_TYPE_START_TO_CLOSE` 를 확인할 수 있습니다.
- `[4-3]` 의 ScheduleToStart 실습은 **Worker 를 띄우지 않고** 워크플로우만 시작해야 재현됩니다. `--no-worker` 인자를 주면 클라이언트만 떠서 액티비티가 큐에 쌓이고, 10초 뒤 `TIMEOUT_TYPE_SCHEDULE_TO_START` 가 터집니다.
- `[4-4]` 의 `BatchActivityImpl` 이 10만 건 재개 실습의 핵심입니다. `KILL_AT` 상수(기본 50000)에 도달하면 스스로 `System.exit(137)` 로 죽어 `kill -9` 를 흉내 냅니다. Worker 를 다시 띄우면 `start=50000` 부터 재개되는 로그를 볼 수 있습니다.
- `[4-6]` 의 `NonIdempotentPaymentActivityImpl` 은 **호출될 때마다 잔액을 실제로 깎는** 가짜 원장을 들고 있습니다. `--double-charge` 로 실행하면 응답 유실을 흉내 내 78,000원이 빠지는 것을 콘솔에서 볼 수 있습니다. 같은 파일의 `IdempotentPaymentActivityImpl` 이 이를 막습니다.
- `[4-7]` 은 `LocalActivityWorkflowImpl` 과 `RegularActivityWorkflowImpl` 을 둘 다 담고 있습니다. 각각 실행한 뒤 `temporal workflow describe` 의 `History Length` 와 `History Size` 를 비교하세요. 36 vs 16, 8712 vs 3104 가 나옵니다.
- `[4-8]` 의 `UnsafePaymentActivityImpl` 은 인스턴스 필드에 요청별 상태를 담은 반면교사입니다. `--race` 로 실행하면 20개 워크플로우를 동시에 던져 **다른 주문의 orderId 로 결제되는** 로그를 재현합니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// TODO: 여기에 작성` 자리를 채우는 구조입니다.

- **문제 1** 은 코드를 거의 쓰지 않습니다. 네 개의 장애 시나리오(배포 중 Worker 전멸 / 외부 API 무응답 / OOM 으로 죽은 배치 Worker / 재시도가 하루 종일 도는 결제)에 어떤 타임아웃을 **몇 초로** 걸지 표를 채우는 문제입니다. 근거를 함께 적으세요.
- **문제 2** 의 `Ex2NoTimeoutWorkflowImpl` 은 그대로 실행하면 워크플로우가 FAILED 로 끝납니다. 먼저 **고치기 전에 한 번 돌려서** 예외 메시지를 직접 보고 주석에 적은 뒤 고치세요.
- **문제 3** 은 `Ex3BatchActivityImpl` 에 heartbeat 와 재개 로직을 넣는 문제입니다. `getHeartbeatDetails` 를 호출하는 위치가 중요합니다 — 반복문 안에 넣으면 매번 서버를 때립니다.
- **문제 4** 의 `Ex4PaymentActivityImpl` 은 `chargedOrders` 라는 `Set` 으로 중복을 막으려 합니다. 이 접근이 왜 실패하는지(Worker 가 여러 대이고 재시작되면 Set 이 비어 있습니다) 설명하고, 올바른 방법으로 고치세요.
- **문제 5** 는 액티비티 5개(주소 정규화 / 이미지 리사이징 / 쿠폰 코드 형식 검증 / 결제 / 재고 조회)를 Activity 와 Local Activity 로 분류합니다. 하나는 판단이 갈리도록 만들어 두었으니 근거를 적으세요.
- **문제 6** 의 `Ex6UnsafeActivityImpl` 에는 스레드 안전 문제가 **세 군데** 있습니다. 하나는 눈에 잘 띄고, 하나는 `SimpleDateFormat` 이고, 하나는 지연 초기화입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그런지"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 표에서 가장 중요한 항목은 "배포 중 Worker 전멸"입니다. 답은 **ScheduleToStart 5분**이며, StartToClose 를 아무리 짧게 줘도 이 장애는 못 잡는다는 것이 핵심입니다. 액티비티가 시작조차 안 했으므로 StartToClose 타이머가 돌지 않기 때문입니다.
- **정답 2** 의 예외는 `IllegalStateException: Either ScheduleToCloseTimeout or StartToCloseTimeout is required` 입니다. 이 실패가 워크플로우를 FAILED 로 만든다는 점(Step 03 의 NonDeterministicException 과 다름)과, 그래서 **로컬에서 한 번만 돌려 봐도 즉시 발견된다**는 점을 대비해 설명합니다.
- **정답 3** 은 `getHeartbeatDetails()` 를 **반복문 밖에서 딱 한 번** 호출합니다. 이 값은 액티비티 시도가 시작될 때 서버가 함께 내려 주는 것이라 반복 호출할 이유가 없습니다. 하트비트 주기(1,000건마다)를 정하는 계산 근거도 함께 적었습니다.
- **정답 4** 는 `Set` 접근이 왜 틀렸는지를 세 단계로 설명합니다. (1) Worker 가 여러 대면 Set 이 공유되지 않는다 (2) Worker 재시작이면 비어 있다 (3) 애초에 액티비티는 무상태여야 한다. 올바른 답은 **DB 유니크 제약 + `INSERT ... ON CONFLICT DO NOTHING`** 이거나 **PG 의 Idempotency-Key** 이며, 키는 워크플로우가 `Workflow.randomUUID()` 로 만들어 넘깁니다.
- **정답 5** 에서 판단이 갈리는 것은 **"재고 조회"** 입니다. 읽기 전용에 수십 ms 라 Local Activity 가 맞아 보이지만, 재고 서비스가 장애일 때 재시도·타임아웃 관측이 필요하므로 **일반 Activity 를 권장**합니다. "외부 시스템을 건드리면 일반 Activity" 라는 기준을 다시 강조합니다.
- **정답 6** 의 세 군데는 (1) `private String currentOrderId` 요청별 상태 (2) `private final SimpleDateFormat fmt` — `SimpleDateFormat` 은 스레드 안전하지 않아 200 스레드가 쓰면 날짜가 뒤섞입니다. `DateTimeFormatter` 로 교체합니다 (3) `if (client == null) client = new HttpClient()` 지연 초기화 — 동시 진입 시 클라이언트가 여러 개 만들어져 커넥션이 샙니다. 생성자 주입으로 바꿉니다.

```java file="./Solution.java"
```
