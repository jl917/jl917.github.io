# Step 05 — 재시도와 실패 처리

> **학습 목표**
> - `RetryOptions` 의 5개 필드와 **기본값**을 정확히 외우고, 기본값이 **무한 재시도**임을 실측으로 확인한다
> - 지수 백오프 간격(1s → 2s → 4s → ... → 100s 상한)을 계산하고, 시도 횟수별 누적 대기 시간을 표로 이해한다
> - 항상 실패하는 액티비티를 돌려 `temporal workflow describe` 의 Pending Activities 를 읽고, **재시도가 히스토리 이벤트를 만들지 않는다**는 것을 히스토리 길이로 증명한다
> - `TemporalFailure` 예외 계층을 그리고, 액티비티 예외가 `ActivityFailure` 로 **한 겹 감싸여** 온다는 것을 스택트레이스로 확인한다
> - `ApplicationFailure.newNonRetryableFailure()` 와 `RetryOptions.setDoNotRetry()` 로 재시도하면 안 되는 실패를 즉시 중단시킨다
> - 워크플로우가 실패를 다루는 3가지 방식(전파 / 대안 경로 / 보상)을 구분해서 쓴다
>
> **선행 스텝**: Step 04 — 액티비티
> **예상 소요**: 90분

---

## 5-0. 실습 준비

Step 04 의 프로젝트를 그대로 씁니다. 서버와 Worker 가 떠 있는지 확인합니다.

```bash
temporal operator cluster health
./gradlew runWorker
```

**결과**
```
temporal.api.workflowservice.v1.WorkflowService: SERVING
09:41:02.512 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
09:41:02.514 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Activity Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
09:41:02.520 [main] INFO  c.e.order.OrderWorker - Worker started. taskQueue=ORDER_TASK_QUEUE
```

버전은 코스 전체와 동일합니다: Temporal Server 1.22.4, Java SDK 1.22.3, temporal CLI 0.11.0, Java 21.

---

## 5-1. RetryOptions — 5개 필드와 기본값

Step 04 에서 액티비티는 실패하면 **자동으로 다시 실행된다**고만 말했습니다. 그 "자동"의 규칙이 `RetryOptions` 입니다.

```java
RetryOptions retry = RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))     // 첫 재시도까지 대기
        .setBackoffCoefficient(2.0)                    // 매 재시도마다 간격에 곱할 배수
        .setMaximumInterval(Duration.ofSeconds(100))   // 간격 상한
        .setMaximumAttempts(5)                         // 총 시도 횟수 (첫 시도 포함)
        .setDoNotRetry("InvalidCardError")             // 이 타입은 재시도하지 않음
        .build();
```

**아무것도 설정하지 않았을 때의 기본값**은 다음과 같습니다.

| 필드 | 기본값 | 의미 |
|---|---|---|
| `initialInterval` | **1초** | 1차 시도 실패 후 1초 뒤 2차 시도 |
| `backoffCoefficient` | **2.0** | 간격이 매번 2배 (지수 백오프) |
| `maximumInterval` | **`initialInterval` × 100 = 100초** | 간격이 아무리 커져도 100초를 넘지 않음 |
| `maximumAttempts` | **0** | **0 = 무제한.** 성공할 때까지 영원히 재시도 |
| `doNotRetry` | **`[]`** (빈 배열) | 예외 타입 기반 제외 없음 |

> ⚠️ **함정 — 기본값은 "무한 재시도"입니다**
> `maximumAttempts` 의 기본값 `0` 은 "0번 시도"가 아니라 **"제한 없음"** 입니다.
> 즉 `ActivityOptions` 에 `setRetryOptions()` 를 안 쓰면, 그 액티비티는 **성공하거나 워크플로우가 죽을 때까지 영원히 재시도합니다.**
> 존재하지 않는 API 를 호출하는 오타 하나가 몇 달 동안 초당 한 번씩 외부 서버를 두드릴 수 있습니다.
> 이것을 막는 방법은 두 가지뿐입니다 — `maximumAttempts` 를 설정하거나, `ScheduleToCloseTimeout` 으로 **재시도 전체의 총 시간**에 상한을 두는 것입니다.

```java
// 권장 형태 — 둘 중 하나는 반드시 있어야 합니다
ActivityOptions opts = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))   // 1회 실행의 상한
        .setScheduleToCloseTimeout(Duration.ofMinutes(5)) // 재시도 전체의 상한  ← 안전망
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(5)                    // 또는 이것            ← 안전망
                .build())
        .build();
```

> 💡 **`maximumAttempts` 와 `ScheduleToCloseTimeout` 중 무엇을 쓸까**
> "몇 번까지 봐줄 것인가"가 기준이면 `maximumAttempts`, "언제까지 기다릴 것인가"가 기준이면 `ScheduleToCloseTimeout` 입니다.
> 사용자가 화면에서 기다리는 동기 흐름이면 시간 기준이 자연스럽고, 배치성 작업이면 횟수 기준이 자연스럽습니다.
> **둘 다 거는 것도 정상이며, 먼저 걸리는 쪽이 이깁니다.**

---

## 5-2. 재시도 간격 계산 — 지수 백오프

n번째 시도가 실패한 뒤 다음 시도까지의 대기 시간은 다음과 같습니다.

```
interval(n) = min( initialInterval × backoffCoefficient^(n-1), maximumInterval )
```

기본값(`1s`, `2.0`, `100s`)을 넣으면 `1, 2, 4, 8, 16, 32, 64, 100, 100, 100, ...` 입니다. 8번째부터는 `128s` 가 될 자리를 `maximumInterval` 이 잘라 **100초로 고정**됩니다.

**시도 횟수별 대기 시간과 누적 대기 시간** (기본값 기준)

| 시도 # | 직전 대기 | 누적 대기(= 이 시도가 시작되는 시각) |
|---:|---:|---:|
| 1 | — | 0초 |
| 2 | 1초 | 1초 |
| 3 | 2초 | 3초 |
| 4 | 4초 | 7초 |
| 5 | 8초 | 15초 |
| 6 | 16초 | 31초 |
| 7 | 32초 | 63초 |
| 8 | 64초 | 127초 (2분 7초) |
| 9 | 100초 (상한 적용) | 227초 (3분 47초) |
| 10 | 100초 | **327초 (5분 27초)** |

**10번째 시도가 시작될 때까지 327초**, 약 5분 반입니다. 여기서 계속 실패하면 이후로는 100초씩 늘어납니다.

8번째 이후로는 100초씩 일정하게 늘어납니다. 20번째는 1,327초(22분), 100번째는 9,327초(2시간 35분), 1,000번째는 99,327초(27시간 35분)입니다.

> 💡 **지수 백오프의 목적은 "상대를 살려 주는 것"입니다**
> 외부 API 가 과부하로 500 을 뱉을 때 1초 간격으로 계속 두드리면 그 API 는 절대 회복하지 못합니다.
> 간격을 2배씩 늘리면 우리 워크플로우 1,000개가 동시에 실패하더라도 시간이 지날수록 트래픽이 자연히 줄어듭니다.
> 반대로 `backoffCoefficient = 1.0` 으로 두면 **고정 간격 재시도**가 됩니다. "1초마다 5번만" 같은 짧고 확실한 재시도에만 쓰세요.

이 표를 손으로 검증하는 시뮬레이터(`printBackoff`)를 `Practice.java` 의 `[5-2]` 에 넣어 두었습니다. Temporal 서버 없이 돌아가므로 계수를 바꿔 가며 실험해 보세요.

---

## 5-3. 무한 재시도의 실제 모습

말로만 하면 실감이 안 나니 **항상 실패하는 액티비티**를 만들어 봅니다.

```java
// PaymentActivityImpl 안
@Override
public String charge(String orderId, long amount) {
    log.info("[{}] 결제 시도 amount={}", orderId, amount);
    throw new RuntimeException("payment gateway unreachable: connect timed out");
}
```

워크플로우 쪽은 `RetryOptions` 를 **일부러 지정하지 않습니다.**

```java
private final PaymentActivity payment = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))   // 재시도 상한이 아무것도 없다
                .build());
```

```bash
temporal workflow start \
  --type OrderWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id order-5001 \
  --input '{"orderId":"5001","customerId":"C-1","sku":"SKU-A","qty":1,"amount":39000,"address":"서울시 강남구"}'
```

**결과** (Worker 콘솔)
```
09:44:10.640 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.PaymentActivityImpl - [5001] 결제 시도 amount=39000
09:44:10.658 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] WARN  i.t.i.a.ActivityTaskHandlerImpl - Activity failure. ActivityId=1, activityType=Charge, attempt=1
java.lang.RuntimeException: payment gateway unreachable: connect timed out
	at com.example.order.PaymentActivityImpl.charge(PaymentActivityImpl.java:24)
09:44:11.702 [Activity Executor ...: 2] INFO  c.e.order.PaymentActivityImpl - [5001] 결제 시도 amount=39000
09:44:11.705 [Activity Executor ...: 2] WARN  i.t.i.a.ActivityTaskHandlerImpl - Activity failure. attempt=2
09:44:13.744 [Activity Executor ...: 3] INFO  c.e.order.PaymentActivityImpl - [5001] 결제 시도 amount=39000
09:44:13.746 [Activity Executor ...: 3] WARN  i.t.i.a.ActivityTaskHandlerImpl - Activity failure. attempt=3
09:44:17.781 [Activity Executor ...: 4] INFO  c.e.order.PaymentActivityImpl - [5001] 결제 시도 amount=39000
```

`09:44:10 → :11 → :13 → :17` — 1초, 2초, 4초 간격. 정확히 5-2 의 표대로입니다. 그리고 **멈추지 않습니다.**

### Pending Activities 로 상태 확인

```bash
temporal workflow describe -w order-5001
```

**결과**
```
Execution Info:
  Workflow Id       order-5001
  Run Id            b71c04d9-3e28-4f60-9a15-77c2e08b4a3e
  Type              OrderWorkflow
  Namespace         default
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-11 09:44:10 +0000 UTC
  Status            RUNNING
  History Length    5
  History Size      1102

Pending Activities: 1
  ActivityId                1
  Activity Type             Charge
  Attempt                   7
  State                     BACKOFF
  Last Failure              {"message":"payment gateway unreachable: connect timed out","source":"JavaSDK","stackTrace":"java.lang.RuntimeException: payment gateway unreachable: connect timed out\n\tat com.example.order.PaymentActivityImpl.charge(PaymentActivityImpl.java:24)\n","applicationFailureInfo":{"type":"java.lang.RuntimeException"}}
  Last Started Time         2026-03-11 09:45:13 +0000 UTC
  Last Worker Identity      41802@macbook-pro
  Next Attempt Schedule     2026-03-11 09:45:45 +0000 UTC
```

읽는 법:

| 필드 | 의미 |
|---|---|
| `Attempt` | **현재까지 몇 번째 시도인가.** 7이면 이미 6번 실패했다는 뜻 |
| `State` | `STARTED`(지금 실행 중) / `BACKOFF`(다음 시도를 기다리는 중) / `SCHEDULED`(큐에 있음) |
| `Last Failure` | 마지막 실패의 message · stackTrace · 예외 타입. **디버깅의 1차 자료** |
| `Next Attempt Schedule` | 다음 시도 예정 시각. 여기서 백오프 간격을 눈으로 확인할 수 있음 |

`09:45:13` 에 마지막으로 시작했고 다음 시도가 `09:45:45` 이니 간격이 **32초** — 7번째 시도 뒤의 간격이 맞습니다(5-2 표).

### 재시도는 히스토리에 남지 않습니다

여기가 이 절의 핵심입니다. 위 `describe` 결과에서 **`History Length` 가 5** 입니다. 이미 6번 실패했는데도 5입니다.

```bash
temporal workflow show -w order-5001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:44:10Z  WorkflowExecutionStarted
   2  2026-03-11T09:44:10Z  WorkflowTaskScheduled
   3  2026-03-11T09:44:10Z  WorkflowTaskStarted
   4  2026-03-11T09:44:10Z  WorkflowTaskCompleted
   5  2026-03-11T09:44:10Z  ActivityTaskScheduled

Result:
  Status: RUNNING
```

10분 뒤 다시 실행해도 결과는 같습니다.

```bash
temporal workflow describe -w order-5001 --output json | jq '{len: .workflowExecutionInfo.historyLength, attempt: .pendingActivities[0].attempt}'
```

**결과**
```json
{
  "len": "5",
  "attempt": 13
}
```

**시도는 13회, 히스토리는 여전히 5개입니다.** 재시도는 `ActivityTaskScheduled` **하나**에 붙은 attempt 카운터가 올라갈 뿐이고, 실패할 때마다 이벤트가 생기지 않습니다. 액티비티가 최종적으로 성공하면 `ActivityTaskStarted` + `ActivityTaskCompleted` 두 개가 뒤늦게 붙는데, 이때 `ActivityTaskStarted` 안에 최종 시도 번호와 마지막 실패가 함께 기록됩니다.

```json
{
  "eventId": "6",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_STARTED",
  "activityTaskStartedEventAttributes": {
    "scheduledEventId": "5",
    "attempt": 21,
    "lastFailure": {
      "message": "payment gateway unreachable: connect timed out",
      "applicationFailureInfo": { "type": "java.lang.RuntimeException" }
    }
  }
}
```

> 💡 **설계 의도**
> 재시도가 이벤트를 만들지 않기 때문에, 액티비티를 몇 만 번 재시도해도 히스토리는 커지지 않습니다.
> 며칠 동안 죽어 있는 외부 API 를 계속 두드리는 워크플로우도 히스토리는 이벤트 5개짜리로 가볍게 유지됩니다.
> 대신 **"몇 번 실패했는지"는 히스토리를 봐서는 모릅니다.** 반드시 `describe` 의 Pending Activities 를 보세요.

이 워크플로우는 영원히 끝나지 않으므로 정리합니다.

```bash
temporal workflow terminate -w order-5001 --reason "무한 재시도 실습 종료"
```

**결과**
```
Workflow terminated successfully.
```

---

## 5-4. 예외 계층 — ActivityFailure 라는 포장지

Temporal 의 모든 실패는 `io.temporal.failure.TemporalFailure` 를 뿌리로 하는 계층입니다.

```
RuntimeException
 └── TemporalException
      └── TemporalFailure                    ← 모든 Temporal 실패의 루트
           ├── ActivityFailure               ← 액티비티가 실패했을 때 워크플로우가 받는 것
           ├── ChildWorkflowFailure          ← 자식 워크플로우 실패 (Step 08)
           ├── ApplicationFailure            ← 사용자 코드가 던진 예외의 표현
           ├── TimeoutFailure                ← StartToClose / ScheduleToClose 등 타임아웃
           ├── CanceledFailure               ← 취소됨 (Step 12)
           ├── TerminatedFailure             ← terminate 로 강제 종료됨
           └── ServerFailure                 ← 서버 측 오류
```

핵심 규칙은 이것입니다.

> **워크플로우에서 액티비티 예외를 잡으면 항상 `ActivityFailure` 로 한 겹 감싸여 옵니다.
> 원래 예외를 보려면 `getCause()` 로 벗겨야 합니다.**

액티비티가 `throw new RuntimeException("insufficient balance: 39000 > 12000")` 을 던지면, 워크플로우가 잡는 것은 이렇게 생겼습니다.

```
ActivityFailure
  └─ cause: ApplicationFailure (type="java.lang.RuntimeException",
                                message="insufficient balance: 39000 > 12000")
```

`ApplicationFailure` 한 겹이 더 있다는 데 주의하세요. 액티비티 프로세스와 워크플로우 프로세스는 다른 JVM 일 수 있으므로, 원래 예외 객체가 그대로 전달되는 게 아니라 **직렬화 가능한 `ApplicationFailure` 로 변환되어** 서버를 거쳐 옵니다.

```java
// [5-4] 예외를 제대로 벗겨 내는 catch
try {
    payment.charge(req.orderId(), req.amount());
} catch (ActivityFailure af) {
    log.info("겉껍질  : {} / {}", af.getClass().getSimpleName(), af.getMessage());
    Throwable cause = af.getCause();
    log.info("한 겹 벗김: {} / {}", cause.getClass().getSimpleName(), cause.getMessage());
    if (cause instanceof ApplicationFailure appFailure) {
        log.info("원래 타입 : {} / 재시도 금지: {}",
                appFailure.getType(), appFailure.isNonRetryable());
    }
}
```

**결과** (Worker 콘솔)
```
09:58:41.203 [workflow-method-order-5002-...] INFO  c.e.order.OrderWorkflowImpl - 겉껍질  : ActivityFailure / Activity task failed
09:58:41.204 [workflow-method-order-5002-...] INFO  c.e.order.OrderWorkflowImpl - 한 겹 벗김: ApplicationFailure / message='insufficient balance: 39000 > 12000', type='java.lang.RuntimeException', nonRetryable=false
09:58:41.204 [workflow-method-order-5002-...] INFO  c.e.order.OrderWorkflowImpl - 원래 타입 : java.lang.RuntimeException / 재시도 금지: false
```

> ⚠️ **함정 — `e.getMessage()` 만 보면 아무 정보가 없습니다**
> `ActivityFailure.getMessage()` 는 **언제나 `"Activity task failed"`** 입니다. 원인이 잔액 부족이든 네트워크 오류든 똑같습니다.
> ```java
> catch (Exception e) {
>     log.error("결제 실패: {}", e.getMessage());   // ← "결제 실패: Activity task failed"
> }
> ```
> 이 로그로는 아무것도 알 수 없습니다. 운영 중 장애 대응에서 가장 흔하게 시간을 낭비하는 지점입니다.
> **항상 `getCause()` 로 한 겹 벗기거나, `log.error("결제 실패", e)` 처럼 예외 객체 전체를 넘겨 체인을 다 찍으세요.**

워크플로우가 실패로 끝났을 때 히스토리에 남는 모양도 같은 구조입니다.

```bash
temporal workflow show -w order-5002 --output json | jq '.events[-1].workflowExecutionFailedEventAttributes.failure'
```

**결과**
```json
{
  "message": "Activity task failed",
  "activityFailureInfo": {
    "activityType": { "name": "Charge" },
    "activityId": "1",
    "retryState": "RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED"
  },
  "cause": {
    "message": "insufficient balance: 39000 > 12000",
    "stackTrace": "java.lang.RuntimeException: insufficient balance: 39000 > 12000\n\tat com.example.order.PaymentActivityImpl.charge(PaymentActivityImpl.java:31)\n",
    "applicationFailureInfo": { "type": "java.lang.RuntimeException" }
  }
}
```

`failure.message` 는 껍데기, `failure.cause` 에 진짜 원인이 있습니다. `retryState` 도 유용합니다.

| `retryState` | 의미 |
|---|---|
| `RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED` | `maximumAttempts` 를 다 썼다 |
| `RETRY_STATE_NON_RETRYABLE_FAILURE` | 재시도 불가 실패였다 (5-5) |
| `RETRY_STATE_TIMEOUT` | `ScheduleToCloseTimeout` 에 걸렸다 |
| `RETRY_STATE_IN_PROGRESS` | 아직 재시도 중 |

---

## 5-5. 재시도하면 안 되는 실패 — ApplicationFailure

재시도가 의미 있으려면 **다시 해 보면 성공할 가능성**이 있어야 합니다.

- 네트워크 타임아웃, 502, DB 커넥션 고갈 → **재시도할 가치 있음**
- 잔액 부족, 유효하지 않은 카드 번호, 존재하지 않는 SKU → **100번을 해도 똑같이 실패**

두 번째 부류를 무한 재시도하는 것은 순수한 낭비입니다. `ApplicationFailure` 로 구분합니다.

```java
// [5-5] 재시도되는 실패 vs 재시도되지 않는 실패
@Override
public String charge(String orderId, long amount) {
    Account acct = accounts.get(orderId);

    if (acct == null) {   // 재시도 불가 — 계정이 없는 건 다시 해도 없다
        throw ApplicationFailure.newNonRetryableFailure(
                "account not found for order " + orderId, "AccountNotFoundError",
                orderId);                                    // details (가변 인자)
    }
    if (acct.balance() < amount) {   // 재시도 불가 — 잔액은 재시도로 늘지 않는다
        throw ApplicationFailure.newNonRetryableFailure(
                "insufficient balance", "InsufficientBalanceError",
                acct.balance(), amount);                     // details 2개
    }
    if (!gateway.isHealthy()) {      // 재시도 가치 있음 — 곧 돌아올 수 있다
        throw ApplicationFailure.newFailure(
                "gateway temporarily unavailable", "GatewayUnavailableError");
    }
    return gateway.charge(orderId, amount);
}
```

| 메서드 | 재시도 | 용도 |
|---|---|---|
| `ApplicationFailure.newFailure(msg, type, details...)` | **된다** | 일시적 오류 |
| `ApplicationFailure.newNonRetryableFailure(msg, type, details...)` | **안 된다** | 영구적 오류 |
| 그냥 `throw new RuntimeException(...)` | **된다** | 의도치 않은 버그 — 재시도됨 |

`type` 은 자유 문자열입니다. `doNotRetry` 매칭과 워크플로우 쪽 분기에 쓰이므로 **명시적인 이름**을 지으세요.

```bash
temporal workflow start --type OrderWorkflow --task-queue ORDER_TASK_QUEUE \
  --workflow-id order-5003 \
  --input '{"orderId":"5003","customerId":"C-9","sku":"SKU-A","qty":1,"amount":990000,"address":"서울시 강남구"}'

temporal workflow describe -w order-5003
```

**결과**
```
Execution Info:
  Workflow Id       order-5003
  Status            FAILED
  Start Time        2026-03-11 10:04:22 +0000 UTC
  Close Time        2026-03-11 10:04:22 +0000 UTC
  History Length    9
```

**Start Time 과 Close Time 이 같은 초입니다.** 백오프 없이 첫 시도에서 즉시 끝났습니다. Worker 로그도 `attempt=1` 하나뿐입니다.

```bash
temporal workflow show -w order-5003 --output json \
  | jq '.events[] | select(.eventType=="EVENT_TYPE_ACTIVITY_TASK_FAILED") .activityTaskFailedEventAttributes'
```

**결과**
```json
{
  "failure": {
    "message": "insufficient balance",
    "source": "JavaSDK",
    "applicationFailureInfo": {
      "type": "InsufficientBalanceError",
      "nonRetryable": true,
      "details": { "payloads": [ { "data": "MTIwMDA=" }, { "data": "OTkwMDAw" } ] }
    }
  },
  "retryState": "RETRY_STATE_NON_RETRYABLE_FAILURE",
  "identity": "41802@macbook-pro"
}
```

`nonRetryable: true`, `retryState: RETRY_STATE_NON_RETRYABLE_FAILURE`. `details` 의 페이로드는 base64 로 인코딩된 `12000` 과 `990000` 입니다.

### details 를 워크플로우에서 꺼내 쓰기

`details` 는 "실패했다"는 사실 외에 **구조화된 부가 정보**를 넘기는 통로입니다.

```java
try {
    payment.charge(req.orderId(), req.amount());
} catch (ActivityFailure af) {
    if (af.getCause() instanceof ApplicationFailure appFailure
            && "InsufficientBalanceError".equals(appFailure.getType())) {
        long balance = appFailure.getDetails().get(0, Long.class);
        long needed  = appFailure.getDetails().get(1, Long.class);
        notification.notifyCustomer(req.orderId(),
                "잔액이 %,d원 부족합니다".formatted(needed - balance));
        return req.orderId() + " PAYMENT_DECLINED";
    }
    throw af;
}
```

**결과**
```
10:11:03.882 [workflow-method-order-5003-...] INFO  c.e.o.NotificationActivityImpl - [5003] 알림: 잔액이 978,000원 부족합니다
```

메시지 문자열을 파싱하는 대신 **구조화된 값으로 넘기는 것**이 핵심입니다. 액티비티가 메시지 문구를 바꿔도 워크플로우는 깨지지 않습니다.

### doNotRetry — 타입 이름으로 제외하기

액티비티 구현을 못 고치는 경우(외부 라이브러리가 던지는 예외 등)에는 **호출하는 쪽**에서 제외 목록을 지정합니다.

```java
ActivityOptions opts = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(5)
                .setDoNotRetry(
                        "InvalidCardError",                       // ApplicationFailure 의 type
                        "java.lang.IllegalArgumentException")     // 일반 예외는 FQCN
                .build())
        .build();
```

매칭 대상은 `ApplicationFailure.getType()` 입니다. `ApplicationFailure` 를 명시적으로 던지지 않은 일반 예외는 SDK 가 **FQCN(패키지 포함 클래스명)** 을 type 으로 채워 넣으므로, `java.lang.IllegalArgumentException` 처럼 전체 이름을 써야 합니다. `IllegalArgumentException` 만 쓰면 매칭되지 않습니다.

> ⚠️ **함정 — `doNotRetry` 는 상속을 따지지 않습니다**
> `"java.lang.RuntimeException"` 을 `doNotRetry` 에 넣어도 그 **하위 클래스**인 `IllegalStateException` 은 걸러지지 않습니다.
> 문자열 완전 일치 비교이기 때문입니다. 계층을 기대하고 상위 타입만 적어 두면 조용히 무한 재시도가 계속됩니다.
> 되도록 액티비티 쪽에서 `newNonRetryableFailure()` 로 명시하는 편이 안전합니다.

---

## 5-6. 재시도 대상 판정 규칙

지금까지의 규칙을 한 표로 정리합니다.

| 액티비티가 던진 것 | 재시도되나 | 근거 |
|---|:---:|---|
| `RuntimeException` 등 일반 예외 | ✅ | 기본은 전부 재시도 대상 |
| 체크 예외 (`IOException` 등) | ✅ | 동일 |
| `ApplicationFailure.newFailure(...)` | ✅ | `nonRetryable=false` |
| `ApplicationFailure.newNonRetryableFailure(...)` | ❌ | `nonRetryable=true` |
| `doNotRetry` 에 type 이 정확히 일치하는 예외 | ❌ | 옵션으로 제외 |
| `Error` / `OutOfMemoryError` 등 `Throwable` | ⚠️ | **액티비티 실패로 보고되지 않음** — 아래 함정 참조 |
| `StartToCloseTimeout` 초과 | ✅ | 타임아웃도 재시도 대상 |
| `ScheduleToCloseTimeout` 초과 | ❌ | 재시도 전체의 상한이므로 여기서 끝 |
| `HeartbeatTimeout` 초과 | ✅ | 재시도 대상 |
| `CanceledFailure` | ❌ | 취소는 재시도 대상이 아님 |
| 액티비티가 예외를 **잡아서 정상 반환** | ❌ | 실패가 아니므로 재시도 자체가 없음 |

> ⚠️ **함정 — 재시도가 "안 먹는" 두 가지 흔한 실수**
>
> **(1) `Error`(Throwable) 를 던진 경우.** Java SDK 는 `Error` 를 "이 Worker 프로세스가 망가졌다"는 신호로 해석합니다.
> 액티비티 실패로 서버에 보고하지 않고, Worker 가 태스크를 그냥 놓아 버립니다. 그러면 `StartToCloseTimeout` 이 지날 때까지
> **아무 일도 일어나지 않다가** 타임아웃으로 재시도됩니다. 백오프 표대로 움직이지 않는 것처럼 보여 디버깅이 어렵습니다.
> ```java
> throw new AssertionError("이러면 안 됩니다");   // ← Error 계열
> ```
> 액티비티에서는 **반드시 `Exception` 계열**을 던지세요.
>
> **(2) 액티비티 안에서 예외를 잡아 정상 반환하는 경우.** 가장 조용하고 가장 위험한 실수입니다.
> ```java
> @Override
> public String charge(String orderId, long amount) {
>     try {
>         return gateway.charge(orderId, amount);
>     } catch (Exception e) {
>         log.error("결제 실패", e);
>         return null;          // ← 액티비티는 "성공"으로 완료된다
>     }
> }
> ```
> Temporal 은 **예외가 나가야만** 실패로 인식합니다. `null` 을 반환하면 `ActivityTaskCompleted` 가 기록되고
> 재시도는 **한 번도 일어나지 않습니다.** 워크플로우는 결제가 됐다고 믿고 배송을 요청합니다.
> "에러 없이 조용히 잘못 동작하는" 전형이며, 히스토리만 봐서는 정상 실행과 구분되지 않습니다.
> 로그를 남기고 싶다면 **로그를 남기고 다시 던지세요** (`throw e;`).

---

## 5-7. 워크플로우 자체의 재시도

`WorkflowOptions` 에도 `setRetryOptions()` 가 있습니다.

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
        .setWorkflowId("order-5004")
        .setTaskQueue(ORDER_TASK_QUEUE)
        .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
        .build();
```

동작은 액티비티 재시도와 다릅니다. 워크플로우가 실패하면 **완전히 새 Run 으로 처음부터 다시 시작**합니다. Workflow ID 는 같고 Run ID 만 새로 생깁니다.

**결과** (`temporal workflow show -w order-5004`)
```
  11  2026-03-11T10:22:09Z  WorkflowExecutionFailed

Result:
  Status: FAILED
  RunId: 8f21e3c0-...                  (attempt 1)
  New Execution Run Id: c04d7a55-...   ← 재시도로 시작된 새 Run
```

> ⚠️ **대부분의 경우 워크플로우에는 재시도를 걸지 않는 것이 맞습니다**
>
> **(1) 워크플로우는 처음부터 다시 시작합니다.** 이미 성공한 액티비티도 다시 실행됩니다.
> 결제가 성공하고 배송에서 실패했다면, 재시도 Run 에서 **결제가 한 번 더** 일어납니다. 이중 청구입니다.
>
> **(2) 액티비티 재시도가 이미 실패를 흡수합니다.** 워크플로우가 실패까지 갔다는 것은 액티비티 재시도가 다 소진됐거나
> 재시도 불가 실패라는 뜻입니다. 워크플로우를 통째로 다시 돌린다고 달라질 게 없습니다.
>
> **(3) 진짜 필요한 것은 보상입니다.** 중간에 실패했으면 처음부터 다시가 아니라 **이미 한 일을 되돌려야** 합니다.
> 그것이 Saga 이고 Step 09 에서 다룹니다.
>
> 워크플로우 재시도가 타당한 경우는 **부수효과가 전혀 없는 조회·집계형 워크플로우**, 또는 **크론 스케줄 워크플로우**
> (`setCronSchedule` — 이건 성격상 원래 반복 실행) 정도입니다.

---

## 5-8. 워크플로우가 실패를 다루는 3가지 방식

액티비티 재시도가 다 소진되면 예외가 워크플로우 코드로 올라옵니다. 여기서 선택지는 셋입니다.

### (1) 전파 — 워크플로우를 실패시킨다

```java
// 아무것도 안 하면 이것입니다
String paymentId = payment.charge(req.orderId(), req.amount());
```

예외가 워크플로우 메서드 밖으로 나가면 `WorkflowExecutionFailed` 로 종료됩니다.

```
Result:
  Status: FAILED
  Failure: Activity task failed
    cause: insufficient balance
```

**되돌릴 것이 없을 때** 적합합니다. 결제가 첫 단계라면 실패해도 남긴 흔적이 없으니 그냥 실패시키면 됩니다.

### (2) catch 후 대안 경로

```java
// [5-8b] 주 배송사가 실패하면 대체 배송사로
String shipmentId;
try {
    shipmentId = shipping.requestShipment(req.orderId(), req.address());
} catch (ActivityFailure af) {
    log.warn("[{}] 주 배송사 실패, 대체 경로로 전환", req.orderId());
    shipmentId = backupShipping.requestShipment(req.orderId(), req.address());
}
```

**결과**
```
10:35:12.410 [Activity Executor ...] WARN  c.e.o.ShippingActivityImpl - [5005] 배송 요청 실패 attempt=3
10:35:12.688 [workflow-method-order-5005-...] WARN  c.e.order.OrderWorkflowImpl - [5005] 주 배송사 실패, 대체 경로로 전환
10:35:12.913 [Activity Executor ...] INFO  c.e.o.BackupShippingActivityImpl - [5005] 대체 배송 접수 SHIP-BK-5005

Result:
  Status: COMPLETED
  Output: ["order-5005 COMPLETED shipment=SHIP-BK-5005"]
```

워크플로우는 **성공으로 끝났습니다.** 실패가 흡수되어 비즈니스 목적을 달성했습니다.

### (3) catch 후 보상 — Saga

```java
// [5-8c] 이미 한 일을 되돌린다 (Step 09 에서 Saga 클래스로 정식화)
String paymentId = payment.charge(req.orderId(), req.amount());
try {
    String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
    try {
        return shipping.requestShipment(req.orderId(), req.address());
    } catch (ActivityFailure af) {
        inventory.release(reservationId);   // 재고 되돌리기
        throw af;
    }
} catch (ActivityFailure af) {
    payment.refund(paymentId);              // 결제 되돌리기
    throw af;
}
```

**결과**
```
10:41:02.113 [Activity Executor ...] INFO  c.e.order.PaymentActivityImpl   - [5006] 결제 완료 PAY-5006
10:41:02.361 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [5006] 재고 예약 RSV-5006
10:41:07.884 [Activity Executor ...] ERROR c.e.order.ShippingActivityImpl  - [5006] 배송 요청 최종 실패
10:41:07.912 [Activity Executor ...] INFO  c.e.order.InventoryActivityImpl - [5006] 재고 해제 RSV-5006  ← 보상
10:41:08.104 [Activity Executor ...] INFO  c.e.order.PaymentActivityImpl   - [5006] 결제 취소 PAY-5006  ← 보상
```

중첩 try-catch 가 깊어지는 게 보입니다. 단계가 5개면 감당이 안 됩니다. Temporal 은 이 패턴을 위해 `io.temporal.workflow.Saga` 클래스를 제공하며, **Step 09** 에서 다룹니다.

**선택 기준**

| 상황 | 선택 |
|---|---|
| 아직 아무것도 안 바꿨다 | (1) 전파 |
| 같은 목적을 이룰 다른 수단이 있다 | (2) 대안 경로 |
| 이미 외부 시스템 상태를 바꿨다 | (3) 보상 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| `RetryOptions` 기본값 | `1s` / `2.0` / `100s` / **`maximumAttempts=0` = 무제한** / `doNotRetry=[]` |
| 무한 재시도 방지 | `maximumAttempts` 또는 `ScheduleToCloseTimeout` **둘 중 하나는 반드시** |
| 백오프 간격 | `min(initial × coeff^(n-1), max)` → 1,2,4,8,16,32,64,100,100... |
| 10회 시도 누적 대기 | **327초 (5분 27초)** |
| 재시도와 히스토리 | 재시도는 **이벤트를 만들지 않는다.** `ActivityTaskScheduled` 하나에 attempt 만 증가 |
| 재시도 상태 확인 | `temporal workflow describe` 의 Pending Activities (`Attempt`, `Last Failure`, `Next Attempt Schedule`) |
| 예외 계층 | `TemporalFailure` → `ActivityFailure` / `ApplicationFailure` / `TimeoutFailure` / `CanceledFailure` ... |
| 액티비티 예외 | 워크플로우에는 **`ActivityFailure` 로 감싸여** 온다. `getCause()` 로 벗겨야 원본 |
| `getMessage()` 함정 | `ActivityFailure.getMessage()` 는 언제나 `"Activity task failed"` |
| 재시도 금지 | `ApplicationFailure.newNonRetryableFailure(msg, type, details...)` |
| `doNotRetry` | `ApplicationFailure.getType()` **문자열 완전 일치**. 상속 안 따짐. 일반 예외는 FQCN |
| 재시도가 안 먹는 실수 | `Error` 를 던짐 / 액티비티 안에서 catch 후 정상 반환 |
| 워크플로우 재시도 | 처음부터 새 Run. **이미 성공한 액티비티도 재실행** → 대개 쓰지 말 것 |
| 실패 대응 3가지 | 전파 / 대안 경로 / 보상(Saga, Step 09) |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 주어진 `RetryOptions` 로 8번째 시도가 시작되는 시각을 계산하기
2. 무한 재시도 중인 워크플로우를 `describe` 로 진단하고 원인·시도 횟수 읽어 내기
3. 액티비티 예외를 `getCause()` 로 벗겨 원래 타입·메시지·details 를 꺼내기
4. 잔액 부족 / 게이트웨이 장애를 각각 알맞은 `ApplicationFailure` 로 분류해 던지기
5. `doNotRetry` 가 동작하지 않는 코드를 찾아 고치기 (FQCN·상속 함정)
6. 재시도가 한 번도 일어나지 않는 액티비티 구현을 찾아 고치기

---

## 다음 단계

재시도는 "실패했으니 곧 다시"라는 짧은 기다림입니다. 하지만 워크플로우가 다루는 기다림은 그보다 훨씬 깁니다 — "결제 확인을 30분 기다린다", "구독 갱신을 30일 뒤에 한다". Temporal 에서 이 기다림은 스레드를 점유하지 않고, Worker 를 다 꺼도 살아 있습니다. 다음 스텝에서 타이머와 조건 대기, 그리고 병렬 액티비티를 다룹니다.

→ [Step 06 — 타이머와 대기](../step-06-timers-await/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 따라 실행하며 5-1 ~ 5-8 의 모든 관측을 재현하고, `Exercise.java` 의 6문제를 직접 풀어 본 뒤, `Solution.java` 로 정답과 해설을 대조합니다. 세 파일 모두 단일 파일 안에 워크플로우·액티비티·Worker·스타터를 static nested class 로 담고 있어 `./gradlew` 한 줄로 실행됩니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 한 파일에 모아 둔 실습 코드입니다.

- 파일 상단 주석의 `./gradlew run -PmainClass=com.example.order.Practice --args="5-3"` 형태로 **절 단위 실행**을 지원합니다. 인자 없이 실행하면 백오프 시뮬레이터(5-2)만 돌고 끝나므로, 반드시 절 번호를 넘기세요.
- `[5-2]` 의 `printBackoff()` 는 Temporal 서버 없이도 돌아가는 **순수 계산**입니다. 표의 327초를 손으로 확인하는 용도이며, 여기서 `backoffCoefficient` 를 1.0 이나 3.0 으로 바꿔 보면 백오프 성격이 확 달라지는 것을 볼 수 있습니다.
- `[5-3]` 의 `AlwaysFailingPaymentActivityImpl` 은 **의도적으로 영원히 실패**합니다. 이 절을 실행하면 워크플로우가 끝나지 않으므로, 5분쯤 관찰한 뒤 반드시 `temporal workflow terminate -w order-5001` 로 정리하세요. 안 그러면 Worker 를 띄울 때마다 계속 재시도가 이어집니다.
- `[5-4]` 의 `unwrap()` 헬퍼는 `ActivityFailure` → `ApplicationFailure` 를 벗겨 타입·메시지·`nonRetryable`·`details` 를 한 번에 찍습니다. 실무에서 그대로 복사해 쓸 수 있는 형태로 만들어 두었습니다.
- `[5-6]` 구간에는 **잘못된 코드가 주석 처리된 채로** 들어 있습니다(`throw new AssertionError(...)`, `catch (Exception e) { return null; }`). 주석을 풀고 돌려서 "재시도가 아예 안 일어나는" 것을 직접 확인하는 게 이 절의 목적입니다.
- `[5-8]` 의 세 워크플로우(`PropagateWorkflow` / `FallbackWorkflow` / `CompensateWorkflow`)는 같은 실패 상황을 서로 다르게 처리합니다. 셋을 연달아 돌린 뒤 `temporal workflow list` 로 Status 가 FAILED / COMPLETED / FAILED 로 갈리는 것을 비교하세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1** 만 서버 없이 풀 수 있는 계산 문제입니다. `assertEquals` 로 자가 채점하므로 답이 맞으면 콘솔에 `Q1 PASS` 가 찍힙니다.
- **문제 2** 는 파일이 무한 재시도 워크플로우를 띄워 주고, 여러분이 `describe` 결과에서 값을 읽어 상수에 채워 넣는 구조입니다. 실행 시각에 따라 `Attempt` 가 달라지므로 정답은 범위로 채점합니다.
- **문제 4·5** 는 컴파일은 되지만 **동작이 틀린** 코드가 들어 있습니다. 실행해서 히스토리를 보기 전에는 틀린 줄 모르는 형태이니, `temporal workflow describe` 로 `retryState` 를 꼭 확인하세요.
- **문제 6** 의 `SilentPaymentActivityImpl` 이 이 스텝에서 가장 중요한 함정입니다. 워크플로우는 COMPLETED 로 끝나고 로그도 깨끗한데 결제는 안 된 상태입니다. 무엇을 고쳐야 하는지 스스로 찾아보세요.
- 파일 끝의 `cleanup()` 이 이 문제지가 만든 `order-6xxx` 워크플로우들을 terminate 합니다. 중간에 멈췄다면 `temporal workflow list --query 'WorkflowId STARTS_WITH "order-6"'` 로 남은 것을 확인하고 직접 정리하세요.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 8번째 시도가 `1+2+4+8+16+32+64 = 127초` 에 시작한다는 계산과 함께, `maximumInterval` 이 어디서부터 개입하는지(9번째부터)를 주석으로 설명합니다.
- **정답 3** 은 `getCause()` 를 **한 번만** 벗기면 된다는 점을 강조합니다. 두 번 벗기려다 `NullPointerException` 을 내는 것이 흔한 오답이며, `ApplicationFailure` 는 더 이상 cause 가 없습니다.
- **정답 5** 의 핵심은 두 가지입니다 — 일반 예외는 `doNotRetry` 에 **FQCN** 으로 적어야 하고, 상위 타입을 적어도 하위 타입은 걸러지지 않습니다. 주석에 `RETRY_STATE_NON_RETRYABLE_FAILURE` 가 나오는지로 검증하는 방법을 적어 두었습니다.
- **정답 6** 은 `return null` 을 `throw e` 로 바꾸는 한 줄 수정이지만, 주석에서는 그보다 **"액티비티에서 예외를 삼키면 Temporal 의 재시도가 통째로 무력화된다"** 는 원칙과, 로그를 남기면서도 안전하게 다시 던지는 관용구를 함께 설명합니다.
- 모든 정답 블록 끝에는 검증용 `temporal` 명령이 주석으로 붙어 있습니다. 코드만 읽지 말고 그 명령을 실제로 돌려 `retryState` 와 `History Length` 를 눈으로 확인하세요.

```java file="./Solution.java"
```
