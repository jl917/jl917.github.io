# Step 03 — 워크플로우 정의와 결정성

> **학습 목표**
> - `@WorkflowInterface` / `@WorkflowMethod` 의 규칙과 직렬화 제약을 정확히 안다
> - **결정성(determinism)** 을 "같은 히스토리 → 같은 Command 열"이라는 한 문장으로 정의하고, 리플레이가 왜 여기에 전적으로 의존하는지 설명한다
> - 워크플로우 코드에서 금지된 9가지 API 를 각각 **리플레이 시나리오로** 왜 안 되는지 설명한다
> - `Workflow.currentTimeMillis()` · `Workflow.randomUUID()` · `Workflow.sideEffect()` 등 대체제를 쓰고, `sideEffect` 의 결과가 `MarkerRecorded` 로 히스토리에 남는 것을 눈으로 확인한다
> - **`System.currentTimeMillis()` 로 결정성을 깨뜨려 `NonDeterministicException` 을 직접 재현하고**, 워크플로우가 Running 인 채 Workflow Task 가 무한 재시도되는 상태를 `temporal workflow describe` 로 관측한다
> - 워크플로우가 단일 스레드처럼 동작하는 이유(협력적 스케줄러)와 `Workflow.newThread` 로만 병렬화해야 하는 이유를 안다
>
> **선행 스텝**: Step 02 — 핵심 개념과 실행 모델
> **예상 소요**: 120분

---

## 3-0. 실습 준비

Step 01 에서 만든 프로젝트와 Temporal 서버가 떠 있어야 합니다.

```bash
docker compose ps
temporal operator namespace describe default
```

**결과**
```
NAME                IMAGE                            STATUS         PORTS
temporal            temporalio/auto-setup:1.22.4     Up 3 minutes   0.0.0.0:7233->7233/tcp
temporal-ui         temporalio/ui:2.21.3             Up 3 minutes   0.0.0.0:8233->8080/tcp
temporal-postgres   postgres:15                      Up 3 minutes   5432/tcp

Name                 default
Id                   32049b68-7872-4094-8e7c-3ee7c78f8909
Description          Default namespace for Temporal Server.
State                Registered
Retention            72h0m0s
```

이 스텝의 모든 코드는 `com.example.order` 패키지에 있고, Task Queue 는 `ORDER_TASK_QUEUE` 입니다.

---

## 3-1. `@WorkflowInterface` 와 `@WorkflowMethod`

워크플로우는 **인터페이스 + 구현체** 한 쌍으로 정의합니다. 인터페이스가 존재하는 이유는 클라이언트가 이 인터페이스를 타입 세이프한 스텁으로 만들어 호출하기 때문입니다.

```java
package com.example.order;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String processOrder(OrderRequest req);
}
```

```java
package com.example.order;

import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    // 반드시 no-arg 생성자. SDK 가 리플렉션으로 매 실행마다 새 인스턴스를 만든다.
    public OrderWorkflowImpl() {}

    @Override
    public String processOrder(OrderRequest req) {
        log.info("[{}] 주문 처리 시작 amount={}", req.orderId(), req.amount());
        return req.orderId() + " COMPLETED";
    }
}
```

규칙을 정리합니다.

| 규칙 | 내용 | 어기면 |
|---|---|---|
| `@WorkflowInterface` | 워크플로우 인터페이스에 필수 | 등록 시 `IllegalArgumentException` |
| `@WorkflowMethod` | 인터페이스에 **정확히 하나** | 0개 또는 2개 이상이면 등록 실패 |
| 구현체 생성자 | **no-arg public 생성자** 필요 | `WorkflowException: no no-arg constructor` |
| 인자·반환값 | Jackson 으로 직렬화 가능해야 함 | `DataConverterException` |
| 인자 개수 | 1개를 권장 (DTO 로 묶기) | 시그니처 변경 시 호환성 파괴 |

`@WorkflowMethod` 가 두 개면 Worker 등록 시점에 바로 터집니다.

```java
@WorkflowInterface
public interface BadWorkflow {
    @WorkflowMethod String a(String s);
    @WorkflowMethod String b(String s);   // 두 번째
}
```

**결과** (Worker 기동 시)
```
Exception in thread "main" java.lang.IllegalArgumentException: Duplicated @WorkflowMethod: 
public abstract java.lang.String com.example.order.BadWorkflow.b(java.lang.String)
	at io.temporal.common.metadata.POJOWorkflowInterfaceMetadata.validateAndCreateMethodMetadata(POJOWorkflowInterfaceMetadata.java:283)
	at io.temporal.common.metadata.POJOWorkflowImplMetadata.newInstance(POJOWorkflowImplMetadata.java:118)
	at io.temporal.internal.sync.POJOWorkflowImplementationFactory.registerWorkflowImplementationTypes(POJOWorkflowImplementationFactory.java:150)
	at io.temporal.worker.Worker.registerWorkflowImplementationTypes(Worker.java:200)
	at com.example.order.OrderWorker.main(OrderWorker.java:24)
```

### 인자와 반환값의 직렬화

기본 DataConverter 는 Jackson 기반 JSON 입니다. `OrderRequest` 는 Java 21 record 로 정의합니다.

```java
public record OrderRequest(
        String orderId,
        String customerId,
        String sku,
        int qty,
        long amount,
        String address
) {}
```

record 는 Jackson 2.12 이상에서 별도 애너테이션 없이 직렬화/역직렬화됩니다. Temporal Java SDK 1.22.3 이 쓰는 Jackson 은 2.15.x 이므로 문제없습니다.

> ⚠️ **함정 — record 를 쓸 때 주의할 세 가지**
> 1. **컴포넌트 이름이 JSON 키가 됩니다.** 필드명을 바꾸면 이미 실행 중인 워크플로우의 히스토리에 저장된 JSON 과 키가 안 맞습니다. 없는 키는 `null`/`0` 으로 역직렬화되어 **에러 없이 조용히 값이 사라집니다.**
> 2. **compact 생성자에서 검증 예외를 던지면 역직렬화 자체가 실패합니다.** 히스토리에 이미 들어간 낡은 데이터가 새 검증 규칙을 통과하지 못해 리플레이가 깨질 수 있습니다.
> 3. **record 안에 `Instant` / `LocalDateTime` 을 넣으려면** `jackson-datatype-jsr310` 모듈이 필요합니다. SDK 기본 컨버터에는 등록돼 있지만, 커스텀 DataConverter 를 만들 때 빠뜨리기 쉽습니다.

정상 실행 결과를 먼저 확인합니다.

```bash
temporal workflow start \
  --task-queue ORDER_TASK_QUEUE \
  --type OrderWorkflow \
  --workflow-id order-1001 \
  --input '{"orderId":"1001","customerId":"C-77","sku":"SKU-A","qty":2,"amount":39000,"address":"서울시 강남구"}'
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

---

## 3-2. 결정성이란 무엇인가

Temporal 워크플로우는 **저장되지 않습니다.** 저장되는 것은 오직 **이벤트 히스토리**입니다. 워크플로우의 지역 변수, 반복문의 인덱스, 어디까지 실행했는지 — 아무것도 저장되지 않습니다.

그럼 Worker 가 죽었다가 살아났을 때 워크플로우는 어떻게 이어질까요? **처음부터 다시 실행합니다.** 이것이 리플레이입니다.

```
        ┌──────────────────── 첫 실행 (2026-03-11 09:12) ────────────────────┐

  워크플로우 코드          SDK            서버 히스토리
  ──────────────          ───            ─────────────
  processOrder() 시작                    1 WorkflowExecutionStarted
  charge(...)      →  ScheduleActivity → 5 ActivityTaskScheduled
                                          6 ActivityTaskStarted
                   ←   결과 "PAY-88"   ← 7 ActivityTaskCompleted
  reserve(...)     →  ScheduleActivity → 9 ActivityTaskScheduled
                            :

        ┌──── Worker 재배포. 프로세스 죽음. 메모리 전부 소실 ────┐

        ┌──────────────────── 리플레이 (09:40, 새 Worker) ────────────────────┐

  워크플로우 코드          SDK            서버 히스토리 (읽기만)
  ──────────────          ───            ─────────────
  processOrder() 시작  ←  히스토리 1~9 를 순서대로 먹임
  charge(...)      →  Command 생성    ↔ 5 ActivityTaskScheduled  ✔ 일치
                   ←   "PAY-88" 주입  ← 7 ActivityTaskCompleted (히스토리에서 꺼냄)
  reserve(...)     →  Command 생성    ↔ 9 ActivityTaskScheduled  ✔ 일치
                        ↓
                   히스토리 소진 → 여기서부터가 진짜 "새로운" 실행
```

리플레이 중에는 액티비티가 **실제로 호출되지 않습니다.** SDK 가 히스토리에 기록된 과거의 결과를 그대로 되돌려 줍니다. 워크플로우 코드는 자기가 리플레이 중인지도 모릅니다.

여기서 **결정성**의 정의가 나옵니다.

> **같은 이벤트 히스토리를 입력하면, 워크플로우 코드는 항상 같은 Command 열을 같은 순서로 출력해야 한다.**

Command 는 워크플로우 코드가 서버에 요청하는 행위입니다. `ScheduleActivityTask`, `StartTimer`, `StartChildWorkflowExecution`, `CompleteWorkflowExecution` 같은 것들입니다.

리플레이할 때 SDK 는 **코드가 만들어 내는 Command** 와 **히스토리에 이미 적힌 이벤트** 를 한 칸씩 대조합니다. 어긋나면 그 자리에서 `NonDeterministicException` 을 던집니다.

```
     코드가 만든 Command 열              히스토리 이벤트 열
     ────────────────────              ─────────────────
  1  ScheduleActivityTask(charge)  ↔   ActivityTaskScheduled(charge)     ✔
  2  ScheduleActivityTask(reserve) ↔   ActivityTaskScheduled(reserve)    ✔
  3  StartTimer(30s)               ↔   ActivityTaskScheduled(ship)       ✘  ← 여기서 폭발
```

> 💡 결정성은 "코드가 순수 함수여야 한다"는 뜻이 아닙니다. 워크플로우 코드는 **히스토리라는 입력**에 대해 결정적이면 됩니다. 액티비티 안에서는 무엇을 해도 좋습니다 — 랜덤, 시간, 네트워크, 파일, 스레드 전부 허용됩니다. 비결정적인 모든 것을 **액티비티 안으로 밀어 넣는 것**이 Temporal 설계의 본질입니다.

---

## 3-3. 금지 목록과 그 이유

워크플로우 코드(= `@WorkflowMethod` 에서 시작해 호출되는 모든 코드)에서 아래를 쓰면 안 됩니다.

| 금지 API | 왜 안 되는가 (리플레이 시나리오) |
|---|---|
| `System.currentTimeMillis()` | 첫 실행 09:12 → `if (now % 2 == 0)` 이 true 로 분기. 리플레이는 09:40 → false 로 분기. **다른 Command 를 만든다.** |
| `new Date()` / `LocalDateTime.now()` / `Instant.now()` | 위와 동일. 시간에 따라 분기하거나, 액티비티 인자로 넣으면 값이 달라진다 |
| `Math.random()` | 첫 실행 0.83 → 배송사 A. 리플레이 0.12 → 배송사 B. 액티비티 인자가 달라지면 히스토리의 입력과 불일치 |
| `UUID.randomUUID()` | 매번 새 값. 액티비티 인자로 넘기면 리플레이 때 다른 값이 들어가고, 멱등키로 쓰면 재시도마다 키가 바뀐다 |
| `new Thread()` / `ExecutorService` / `CompletableFuture` | SDK 의 결정적 스케줄러 밖에서 돈다. 스레드 스케줄링 순서는 실행마다 다르므로 Command 순서가 뒤바뀐다 |
| 파일 IO / HTTP 호출 / JDBC | 외부 상태는 리플레이 시점에 달라져 있다. 게다가 리플레이 때마다 부수효과가 **재실행된다**(결제가 여러 번) |
| `static` 가변 상태 | 첫 실행의 Worker 프로세스 메모리에만 있던 값. 다른 Worker 에서 리플레이하면 초기값이거나 다른 워크플로우가 오염시킨 값 |
| `HashMap` / `HashSet` 순회 | Java 의 해시 순서는 명세상 보장되지 않는다. JVM 버전·인스턴스가 바뀌면 순회 순서가 달라져 Command 순서가 바뀐다 |
| `System.getenv()` / `System.getProperty()` | Worker 마다 다른 값일 수 있다. 재배포로 설정이 바뀌면 분기가 달라진다 |
| `Thread.sleep()` | Worker 스레드를 실제로 블로킹한다. 리플레이 때도 진짜로 잔다. Workflow Task 타임아웃을 유발한다 |
| `synchronized` / `ReentrantLock` / `CountDownLatch` | 워크플로우는 단일 스레드처럼 돌기 때문에 데드락이 나거나 영원히 깨어나지 않는다 |

각각의 시나리오를 몇 개 자세히 봅니다.

### `Math.random()` 이 히스토리와 어긋나는 방식

```java
// ✘ 절대 하지 말 것
String carrier = Math.random() < 0.5 ? "CJ" : "LOTTE";
shipping.requestShipment(req.orderId(), carrier);
```

첫 실행에서 `0.31` 이 나와 `"CJ"` 로 액티비티가 스케줄됩니다. 히스토리에는 이렇게 남습니다.

```json
{
  "eventId": "9",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
  "activityTaskScheduledEventAttributes": {
    "activityType": { "name": "RequestShipment" },
    "input": { "payloads": [ { "data": "\"1001\"" }, { "data": "\"CJ\"" } ] }
  }
}
```

리플레이에서 `0.77` 이 나오면 코드는 `"LOTTE"` 로 Command 를 만듭니다. 액티비티 타입은 같아도 **입력이 다르므로** SDK 가 이를 감지합니다. (SDK 1.22 는 activityType 과 순서를 우선 검사하고, 입력 불일치는 액티비티가 잘못된 인자로 실행되는 형태로 드러납니다 — 어느 쪽이든 사고입니다.)

### `static` 가변 상태

```java
// ✘ 절대 하지 말 것
public class OrderWorkflowImpl implements OrderWorkflow {
    private static final Map<String, Integer> RETRY_COUNT = new ConcurrentHashMap<>();
    // ...
}
```

`static` 필드는 **Worker 프로세스에 하나뿐**이며, 그 프로세스에서 동시에 도는 수백 개의 워크플로우가 공유합니다. 리플레이는 다른 Worker, 다른 시각, 다른 프로세스에서 일어납니다. 그때 이 맵은 비어 있거나 전혀 다른 워크플로우의 값이 들어 있습니다.

> ⚠️ **함정 — Spring 의존성 주입도 같은 문제입니다**
> 워크플로우 구현체에 `@Autowired PaymentRepository repo` 를 넣고 싶은 유혹이 큽니다. 하지만 워크플로우 인스턴스는 **SDK 가 no-arg 생성자로 직접 만들기 때문에** Spring 컨테이너를 거치지 않습니다. `temporal-spring-boot-starter` 를 쓰면 주입 자체는 가능하지만, **주입된 빈으로 DB 를 읽는 순간 결정성이 깨집니다.**
> DB 조회는 액티비티 안에서 하세요. 워크플로우가 알아야 할 값이면 액티비티의 **반환값**으로 받아야 하고, 그래야 히스토리에 기록되어 리플레이 때 재사용됩니다.

### `HashMap` 순회 순서

```java
// ✘ 위험 — 순회 순서에 의존
Map<String, Integer> items = new HashMap<>();
items.put("SKU-A", 2);
items.put("SKU-B", 1);
for (var e : items.entrySet()) {
    inventory.reserve(req.orderId(), e.getKey(), e.getValue());   // 순서가 Command 순서
}
```

`HashMap` 의 순회 순서는 해시값과 테이블 크기에 따라 정해지며 **JDK 구현이 바뀌면 달라질 수 있습니다.** 실제로 JDK 7 → 8 에서 바뀐 전례가 있습니다. 해결은 간단합니다: **`LinkedHashMap` 또는 `TreeMap` 을 쓰거나, 정렬한 리스트를 순회하세요.**

```java
// ✔ 안전
Map<String, Integer> items = new LinkedHashMap<>();
```

---

## 3-4. 대체제 — Workflow 클래스가 제공하는 결정적 API

금지된 것들에는 전부 짝이 되는 대체제가 있습니다. 공통점은 **결과가 히스토리에 기록되어 리플레이 때 재사용된다**는 것입니다.

| 금지 | 대체제 | 결정성 확보 방식 |
|---|---|---|
| `System.currentTimeMillis()` | `Workflow.currentTimeMillis()` | 현재 처리 중인 **Workflow Task 의 시각**을 반환. 리플레이 때 같은 값 |
| `Instant.now()` | `Instant.ofEpochMilli(Workflow.currentTimeMillis())` | 위와 동일 |
| `UUID.randomUUID()` | `Workflow.randomUUID()` | Run ID 를 시드로 하는 결정적 UUID |
| `Math.random()` | `Workflow.newRandom()` | Run ID 시드의 `java.util.Random`. 리플레이 때 같은 수열 |
| `Thread.sleep()` | `Workflow.sleep(Duration)` | `TimerStarted` / `TimerFired` 이벤트로 히스토리에 남는다 |
| `new Thread()` | `Async.function()` / `Workflow.newChildThread()` | SDK 의 결정적 스케줄러가 관리 |
| 외부 값 읽기 | `Workflow.sideEffect()` | 결과를 `MarkerRecorded` 로 기록 |
| 변할 수 있는 외부 값 | `Workflow.mutableSideEffect()` | 값이 바뀔 때만 새 Marker 기록 |

```java
@Override
public String processOrder(OrderRequest req) {
    long now = Workflow.currentTimeMillis();          // ✔
    String idemKey = Workflow.randomUUID().toString(); // ✔
    Random rnd = Workflow.newRandom();                 // ✔
    int bucket = rnd.nextInt(100);                     // ✔ 리플레이 때 같은 값

    Workflow.sleep(Duration.ofSeconds(30));            // ✔ 타이머 이벤트로 기록
    return req.orderId() + " COMPLETED";
}
```

### `Workflow.currentTimeMillis()` 는 정확히 무엇을 반환하는가

**현재 처리 중인 Workflow Task 가 시작된 시각**입니다. 서버가 `WorkflowTaskStarted` 이벤트에 기록한 값이라, 리플레이 때 히스토리에서 그대로 읽어 옵니다.

여기서 나오는 결과가 처음에는 이상해 보입니다.

```java
long t1 = Workflow.currentTimeMillis();
// CPU 로 무거운 계산을 1초 동안 수행
long t2 = Workflow.currentTimeMillis();
log.info("경과 = {}ms", t2 - t1);
```

**결과**
```
09:12:04.640 [workflow-method-order-1001-...] INFO  c.e.order.OrderWorkflowImpl - 경과 = 0ms
```

**0ms 입니다.** 같은 Workflow Task 안에서는 시간이 흐르지 않습니다. 액티비티를 호출하거나 타이머를 걸어 Workflow Task 가 한 번 끊긴 뒤에야 시각이 전진합니다. 워크플로우 코드로 실행 시간을 재려는 시도는 의미가 없습니다 — 그건 액티비티에서 하세요.

### `Workflow.sideEffect()` — 결과를 히스토리에 못 박는다

정말로 워크플로우 안에서 비결정적인 값이 필요하고, 그것이 액티비티로 만들 만큼 무겁지 않다면 `sideEffect` 를 씁니다.

```java
// [3-4] sideEffect — 람다는 첫 실행에만 실행되고, 결과가 히스토리에 저장된다
String requestId = Workflow.sideEffect(
        String.class,
        () -> UUID.randomUUID().toString()   // 이 안에서는 비결정적 코드 허용
);
log.info("requestId={}", requestId);
```

첫 실행에서 람다가 실행되고, 그 결과가 `MarkerRecorded` 이벤트로 히스토리에 저장됩니다.

```bash
temporal workflow show -w order-1001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:12:04Z  WorkflowExecutionStarted
   2  2026-03-11T09:12:04Z  WorkflowTaskScheduled
   3  2026-03-11T09:12:04Z  WorkflowTaskStarted
   4  2026-03-11T09:12:04Z  WorkflowTaskCompleted
   5  2026-03-11T09:12:04Z  MarkerRecorded
   6  2026-03-11T09:12:04Z  ActivityTaskScheduled
   7  2026-03-11T09:12:04Z  ActivityTaskStarted
   8  2026-03-11T09:12:05Z  ActivityTaskCompleted
   9  2026-03-11T09:12:05Z  WorkflowTaskScheduled
  10  2026-03-11T09:12:05Z  WorkflowTaskStarted
  11  2026-03-11T09:12:05Z  WorkflowTaskCompleted
  12  2026-03-11T09:12:05Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["1001 COMPLETED"]
```

5번 `MarkerRecorded` 의 내용을 봅니다.

```bash
temporal workflow show -w order-1001 --output json | jq '.events[] | select(.eventType=="EVENT_TYPE_MARKER_RECORDED")'
```

**결과**
```json
{
  "eventId": "5",
  "eventTime": "2026-03-11T09:12:04.712Z",
  "eventType": "EVENT_TYPE_MARKER_RECORDED",
  "taskId": "1048603",
  "markerRecordedEventAttributes": {
    "markerName": "SideEffect",
    "details": {
      "data": {
        "payloads": [
          {
            "metadata": { "encoding": "anNvbi9wbGFpbg==" },
            "data": "IjNmMmE5YzE0LTdiMDEtNDhkNC1hZWY2LTkwMGMxZDJmODhhNCI="
          }
        ]
      }
    },
    "workflowTaskCompletedEventId": "4"
  }
}
```

`data` 를 base64 디코딩하면 `"3f2a9c14-7b01-48d4-aef6-900c1d2f88a4"` 입니다. 리플레이 때 SDK 는 **람다를 실행하지 않고** 이 값을 그대로 돌려줍니다. 그래서 결정적입니다.

| API | 람다 실행 시점 | 히스토리 기록 | 용도 |
|---|---|---|---|
| `Workflow.sideEffect` | 첫 실행에만 | 매 호출마다 Marker 1개 | 한 번 정하면 안 바뀌는 값 |
| `Workflow.mutableSideEffect` | 매 실행마다 (리플레이 제외) | **값이 바뀔 때만** Marker 기록 | 자주 읽지만 가끔 바뀌는 설정값 |

```java
// mutableSideEffect — 같은 id 로 반복 호출. 값이 바뀔 때만 히스토리가 늘어난다
int limit = Workflow.mutableSideEffect(
        "rateLimit", Integer.class,
        (a, b) -> a.equals(b),                  // 같으면 새 Marker 를 안 남긴다
        () -> ConfigCache.currentRateLimit()    // 비결정적 조회
);
```

> ⚠️ **함정 — `sideEffect` 안에서 액티비티를 호출하거나 Workflow API 를 쓰면 안 됩니다**
> `sideEffect` 람다는 워크플로우 스케줄러 밖의 "블랙박스"입니다. 그 안에서 `Workflow.sleep()` 이나 액티비티 스텁을 호출하면 SDK 가 상태를 망가뜨립니다. 람다는 **즉시 값을 반환하는 순수 로컬 계산**이어야 합니다.
> 네트워크 호출이 필요하면 `sideEffect` 가 아니라 **액티비티**를 쓰세요. 액티비티는 재시도·타임아웃·가시성을 전부 얻지만, `sideEffect` 는 실패하면 워크플로우 자체가 실패합니다.

---

## 3-5. 위반 재현 — `System.currentTimeMillis()` 로 리플레이를 깨뜨린다

이 절이 Step 03 의 하이라이트입니다. **로컬에서 100% 통과하고, 운영에서 Worker 를 재배포하는 순간에만 터지는** 버그를 직접 만듭니다.

### 문제의 코드

```java
public class BrokenOrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(BrokenOrderWorkflowImpl.class);

    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public String processOrder(OrderRequest req) {
        // ✘ 결정성 위반 — 벽시계 시각으로 분기한다
        long now = System.currentTimeMillis();
        boolean businessHours = (now / 3_600_000) % 24 < 18;   // 대략 UTC 18시 이전인가

        if (businessHours) {
            log.info("[{}] 영업시간 — 즉시 결제", req.orderId());
            payment.charge(req.orderId(), req.amount());       // ScheduleActivityTask
        } else {
            log.info("[{}] 영업시간 외 — 30분 대기", req.orderId());
            Workflow.sleep(Duration.ofMinutes(30));            // StartTimer
            payment.charge(req.orderId(), req.amount());
        }
        return req.orderId() + " COMPLETED";
    }
}
```

문제는 `if` 의 두 분기가 **서로 다른 Command 를 만든다**는 것입니다. 한쪽은 `ScheduleActivityTask`, 다른 쪽은 `StartTimer` 입니다.

### 1단계 — 첫 실행. 아무 문제 없다

영업시간 외(UTC 19:04)에 워크플로우를 시작합니다.

```bash
temporal workflow start \
  --task-queue ORDER_TASK_QUEUE --type OrderWorkflow --workflow-id order-2002 \
  --input '{"orderId":"2002","customerId":"C-31","sku":"SKU-B","qty":1,"amount":52000,"address":"부산시 해운대구"}'
```

**결과** (Worker 콘솔)
```
19:04:11.208 [workflow-method-order-2002-8f21...] INFO  c.e.order.BrokenOrderWorkflowImpl - [2002] 영업시간 외 — 30분 대기
```

```bash
temporal workflow show -w order-2002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T19:04:11Z  WorkflowExecutionStarted
   2  2026-03-11T19:04:11Z  WorkflowTaskScheduled
   3  2026-03-11T19:04:11Z  WorkflowTaskStarted
   4  2026-03-11T19:04:11Z  WorkflowTaskCompleted
   5  2026-03-11T19:04:11Z  TimerStarted          ← 30분 타이머
```

5번이 `TimerStarted` 입니다. **히스토리에 "이 워크플로우는 타이머를 걸었다"가 영구히 못 박혔습니다.**

### 2단계 — Worker 를 재시작해 리플레이를 강제한다

30분 타이머가 도는 동안 Worker 를 재배포합니다. 운영에서는 흔한 일입니다.

```bash
# Worker 프로세스 종료 후 재기동
kill %1
./gradlew run -PmainClass=com.example.order.OrderWorker
```

타이머가 만료되면(UTC 19:34) 서버가 Workflow Task 를 새 Worker 에게 넘깁니다. 새 Worker 는 히스토리를 처음부터 리플레이합니다.

**그런데 지금은 UTC 19:34 가 아니라 다음 날 오전에 재배포했다고 합시다.** 리플레이 시각의 `System.currentTimeMillis()` 는 UTC 10:20 을 가리킵니다. `businessHours` 가 **true** 로 뒤집힙니다.

### 3단계 — `NonDeterministicException`

**결과** (Worker 콘솔)
```
10:20:37.451 [workflow-method-order-2002-8f21...] INFO  c.e.order.BrokenOrderWorkflowImpl - [2002] 영업시간 — 즉시 결제
10:20:37.488 [Workflow Executor taskQueue="ORDER_TASK_QUEUE", namespace="default": 3] WARN 
  i.t.i.s.WorkflowExecutionHandler - Workflow execution failure WorkflowId=order-2002, RunId=9d1c4b60-2a83-4f17-9c05-6e1b7f22ad48, WorkflowType=OrderWorkflow
io.temporal.worker.NonDeterministicException: Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED' during replay. 
Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match Event=EventType=EVENT_TYPE_TIMER_STARTED
	at io.temporal.internal.statemachines.WorkflowStateMachines.createEventProcessingException(WorkflowStateMachines.java:249)
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleEventsBatch(WorkflowStateMachines.java:222)
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleEvent(WorkflowStateMachines.java:196)
	at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler.applyServerHistory(ReplayWorkflowRunTaskHandler.java:200)
	at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler.handleWorkflowTaskImpl(ReplayWorkflowRunTaskHandler.java:175)
	at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler.handleWorkflowTask(ReplayWorkflowRunTaskHandler.java:145)
	at io.temporal.internal.replay.ReplayWorkflowTaskHandler.handleWorkflowTaskWithQuery(ReplayWorkflowTaskHandler.java:130)
	at io.temporal.internal.replay.ReplayWorkflowTaskHandler.handleWorkflowTask(ReplayWorkflowTaskHandler.java:103)
	at io.temporal.internal.worker.WorkflowWorker$TaskHandlerImpl.handle(WorkflowWorker.java:339)
	at io.temporal.internal.worker.WorkflowWorker$TaskHandlerImpl.handle(WorkflowWorker.java:301)
	at io.temporal.internal.worker.PollTaskExecutor.lambda$process$0(PollTaskExecutor.java:105)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: io.temporal.internal.statemachines.InternalWorkflowTaskException: 
  Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED'. IsReplaying=true, PreviousStartedEventId=3, workflowTaskStartedEventId=8, Currently Processing StartedEventId=3
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleSingleEvent(WorkflowStateMachines.java:319)
	at io.temporal.internal.statemachines.WorkflowStateMachines.handleEventsBatch(WorkflowStateMachines.java:218)
	... 12 common frames omitted
```

핵심 한 줄은 이것입니다.

```
Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match Event=EventType=EVENT_TYPE_TIMER_STARTED
```

**코드는 "액티비티를 스케줄해 달라"고 했는데, 히스토리에는 "타이머를 걸었다"고 적혀 있습니다.**

### 4단계 — 워크플로우는 죽지 않는다. 영원히 멈춘다

여기서 중요한 사실이 있습니다. `NonDeterministicException` 은 **워크플로우를 실패시키지 않습니다.** Workflow Task 만 실패합니다.

```bash
temporal workflow describe -w order-2002
```

**결과**
```
Execution Info:
  Workflow Id       order-2002
  Run Id            9d1c4b60-2a83-4f17-9c05-6e1b7f22ad48
  Type              OrderWorkflow
  Namespace         default
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-11 19:04:11 +0000 UTC
  Close Time        
  Status            RUNNING
  History Length    8
  History Size      1893

Pending Workflow Task:
  State                  Started
  Scheduled Time         2026-03-12 10:24:52 +0000 UTC (13 seconds ago)
  Original Scheduled     2026-03-12 10:20:37 +0000 UTC
  Started Time           2026-03-12 10:24:52 +0000 UTC (13 seconds ago)
  Attempt                7
  Last Failure           NonDeterministicException: Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED' during replay. Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK doesn't match Event=EventType=EVENT_TYPE_TIMER_STARTED
```

`Status: RUNNING`, `Attempt: 7`. 조금 기다렸다 다시 봅니다.

```bash
temporal workflow describe -w order-2002 | grep -A2 Attempt
```

**결과**
```
  Attempt                12
  Last Failure           NonDeterministicException: Failure handling event 5 of type ...
```

Attempt 가 계속 올라갑니다. Workflow Task 는 **기본적으로 무한 재시도**되며(지수 백오프, 최대 간격 60초), 코드를 고칠 때까지 영원히 실패합니다.

이 동작은 의도된 것입니다. 코드 버그로 워크플로우를 실패시켜 버리면 진행 중인 주문·결제가 전부 날아갑니다. 대신 멈춰 두고 **개발자가 코드를 고쳐 배포하면 그 지점부터 이어서 진행**합니다.

Web UI (`http://localhost:8233/namespaces/default/workflows/order-2002`) 의 Pending Activities 탭에도 같은 내용이 뜹니다.

```
Pending Workflow Task
  Attempt          12
  Last Failure     io.temporal.worker.NonDeterministicException
                   Command CommandType=COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK
                   doesn't match Event=EventType=EVENT_TYPE_TIMER_STARTED
  Next Retry       2026-03-12 10:31:04 UTC (in 47s)
```

### 5단계 — 고친다

`System.currentTimeMillis()` 를 `Workflow.currentTimeMillis()` 로 바꾸기만 하면 됩니다.

```java
long now = Workflow.currentTimeMillis();   // ✔ 히스토리의 WorkflowTaskStarted 시각
```

Worker 를 재배포하면, 이번에는 리플레이 시각이 **첫 실행 때의 19:04** 로 고정되므로 `businessHours == false` 가 그대로 재현되고, Command 는 `StartTimer` 가 되어 히스토리와 일치합니다.

**결과** (재배포 후 Worker 콘솔)
```
10:33:18.902 [workflow-method-order-2002-8f21...] INFO  c.e.order.OrderWorkflowImpl - [2002] 영업시간 외 — 30분 대기
10:33:19.104 [workflow-method-order-2002-8f21...] INFO  c.e.order.OrderWorkflowImpl - [2002] 결제 시작 amount=52000
```

```bash
temporal workflow describe -w order-2002
```

**결과**
```
  Status            COMPLETED
  History Length    17
  Result            "2002 COMPLETED"
```

**멈춰 있던 15시간 동안 워크플로우는 아무것도 잃지 않았습니다.** 이것이 Temporal 이 코드 버그를 다루는 방식입니다.

> ⚠️ **함정 — 이 버그는 테스트로 잡히지 않습니다**
> 단위 테스트에서 워크플로우를 한 번 실행하면 리플레이가 일어나지 않으므로 **100% 통과합니다.** 통합 테스트도 마찬가지입니다. 오직 **Worker 가 죽었다 살아나는 순간에만** 드러납니다.
> 잡는 방법은 두 가지입니다.
> 1. `WorkflowReplayer.replayWorkflowExecution(history, WorkflowImpl.class)` 로 **운영 히스토리를 CI 에서 리플레이**한다 (Step 11).
> 2. 코드 리뷰에서 워크플로우 패키지의 `System.`, `Math.random`, `UUID.randomUUID`, `new Thread` 를 grep 으로 막는다. 실제로 아래 한 줄을 CI 에 넣는 팀이 많습니다.
> ```bash
> ! grep -rnE 'System\.(currentTimeMillis|getenv|getProperty)|Math\.random|UUID\.randomUUID|new Thread\(' src/main/java/com/example/order/workflow/
> ```

---

## 3-6. 안전한 것 / 위험한 것 총정리

| 분류 | 안전 ✔ | 위험 ✘ |
|---|---|---|
| 시간 | `Workflow.currentTimeMillis()`, `Workflow.sleep()`, `Workflow.newTimer()` | `System.currentTimeMillis()`, `new Date()`, `Instant.now()`, `Thread.sleep()` |
| 난수 | `Workflow.newRandom()`, `Workflow.randomUUID()` | `Math.random()`, `new Random()`, `UUID.randomUUID()`, `ThreadLocalRandom` |
| 동시성 | `Async.function()`, `Async.procedure()`, `Promise.allOf()`, `Workflow.newChildThread()`, `Workflow.await()` | `new Thread()`, `ExecutorService`, `CompletableFuture`, `parallelStream()`, `synchronized`, `CountDownLatch` |
| 외부 IO | 액티비티 호출, `Workflow.sideEffect()`(가벼운 로컬 계산만) | 파일 읽기/쓰기, HTTP 클라이언트, JDBC, Redis, Kafka Producer |
| 상태 | 워크플로우 인스턴스 필드(`private` 인스턴스 변수) | `static` 가변 필드, 싱글턴 캐시, 주입된 Spring 빈으로 상태 조회 |
| 컬렉션 | `List`, `LinkedHashMap`, `TreeMap`, `ArrayList` | `HashMap`/`HashSet` **순회**, `ConcurrentHashMap` 순회 |
| 로깅 | `Workflow.getLogger()` (리플레이 중 자동 억제) | `LoggerFactory.getLogger()` (리플레이마다 중복 로그) |
| 설정 | 워크플로우 인자로 전달, 액티비티 반환값 | `System.getenv()`, `System.getProperty()`, `@Value` |
| 순수 계산 | 산술, 문자열 조작, 정렬, 분기, 반복 | — (전부 안전합니다) |

> 💡 **실무 팁 — `Workflow.getLogger()` 를 쓰세요**
> 일반 SLF4J 로거를 워크플로우에서 쓰면 **리플레이할 때마다 같은 로그가 다시 찍힙니다.** 히스토리가 긴 워크플로우를 리플레이하면 수천 줄이 한꺼번에 재출력됩니다.
> `Workflow.getLogger()` 가 반환하는 로거는 `Workflow.isReplaying()` 이 true 인 동안 출력을 억제합니다. 로그가 사라진 게 아니라 **첫 실행 때 이미 찍혔던 것**입니다.

---

## 3-7. 워크플로우는 단일 스레드처럼 동작한다

Temporal Java SDK 는 워크플로우 코드를 전용 스레드에서 실행하되, **결정적 스케줄러(deterministic scheduler)** 로 통제합니다. 핵심은 **협력적(cooperative) 스케줄링**입니다.

- 한 시점에 **워크플로우 스레드 하나만** 실행됩니다.
- 스레드는 OS 가 빼앗지 않습니다. `Promise.get()`, `Workflow.await()`, `Workflow.sleep()` 같은 **양보 지점(yield point)** 에서만 다른 스레드로 넘어갑니다.
- 양보 순서는 결정적입니다 — 스레드 생성 순서와 깨어난 순서로만 정해집니다.

그래서 워크플로우 코드에는 **동기화가 필요 없습니다.** `synchronized` 를 쓰면 오히려 스케줄러를 방해합니다.

```java
// [3-7] 병렬 처리 — Async 로 두 액티비티를 동시에 시작
Promise<String> payFuture = Async.function(payment::charge, req.orderId(), req.amount());
Promise<String> resFuture = Async.function(inventory::reserve, req.orderId(), req.sku(), req.qty());

// 둘 다 끝날 때까지 대기 (여기가 양보 지점)
Promise.allOf(payFuture, resFuture).get();

String paymentId = payFuture.get();
String reservationId = resFuture.get();
log.info("결제={} 예약={}", paymentId, reservationId);
```

**결과** (Worker 콘솔)
```
09:41:02.117 [workflow-method-order-1003-...] INFO  c.e.order.OrderWorkflowImpl - [1003] 병렬 시작
09:41:02.402 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.order.PaymentActivityImpl - charge orderId=1003 amount=39000
09:41:02.404 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 2] INFO  c.e.order.InventoryActivityImpl - reserve orderId=1003 sku=SKU-A qty=2
09:41:03.610 [workflow-method-order-1003-...] INFO  c.e.order.OrderWorkflowImpl - 결제=PAY-88231 예약=RSV-40112
```

두 액티비티가 **같은 밀리초에 시작**했습니다. 히스토리에도 두 `ActivityTaskScheduled` 가 연달아 나옵니다.

```bash
temporal workflow show -w order-1003
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:41:02Z  WorkflowExecutionStarted
   2  2026-03-11T09:41:02Z  WorkflowTaskScheduled
   3  2026-03-11T09:41:02Z  WorkflowTaskStarted
   4  2026-03-11T09:41:02Z  WorkflowTaskCompleted
   5  2026-03-11T09:41:02Z  ActivityTaskScheduled     ← Charge
   6  2026-03-11T09:41:02Z  ActivityTaskScheduled     ← Reserve  (같은 Workflow Task 에서 둘 다)
   7  2026-03-11T09:41:02Z  ActivityTaskStarted
   8  2026-03-11T09:41:02Z  ActivityTaskStarted
   9  2026-03-11T09:41:03Z  ActivityTaskCompleted
  10  2026-03-11T09:41:03Z  ActivityTaskCompleted
  11  2026-03-11T09:41:03Z  WorkflowTaskScheduled
  ...
  16  2026-03-11T09:41:03Z  WorkflowExecutionCompleted
```

4번 `WorkflowTaskCompleted` 하나가 Command 두 개를 담고 있어 5·6번이 동시에 스케줄됐습니다. 순차로 호출했다면 5→9→10→... 로 왕복이 한 번 더 늘어납니다.

직접 스레드를 만들어야 한다면 `Workflow.newChildThread` 를 씁니다.

```java
// [3-7] 명시적 워크플로우 스레드 — 이름을 주면 스택 덤프에서 식별하기 쉽다
Workflow.newChildThread(true, "audit-thread", () -> {
    Workflow.await(() -> auditRequested);
    notification.notifyCustomer(req.orderId(), "감사 로그 기록됨");
}).start();
```

> ⚠️ **함정 — `CompletableFuture` 는 컴파일도 되고 로컬에서 돌기도 합니다**
> `CompletableFuture.supplyAsync(() -> payment.charge(...))` 를 워크플로우에 쓰면 컴파일 에러도, 즉시 예외도 나지 않습니다. ForkJoinPool 의 스레드에서 액티비티 스텁이 호출되는데, 그 스레드는 SDK 의 워크플로우 컨텍스트를 모릅니다.
> **결과**
> ```
> java.lang.IllegalStateException: Called from non workflow or workflow callback thread
> 	at io.temporal.internal.sync.WorkflowInternal.getRootWorkflowContext(WorkflowInternal.java:508)
> 	at io.temporal.internal.sync.WorkflowInternal.executeActivity(WorkflowInternal.java:410)
> 	at io.temporal.internal.sync.ActivityInvocationHandler.lambda$getActivityFunc$0(ActivityInvocationHandler.java:82)
> ```
> 운이 나쁘면 이 예외가 `CompletableFuture` 안에 삼켜져 **아무 로그도 없이 워크플로우가 영원히 멈춥니다.** `Async.function()` 을 쓰세요.

> ⚠️ **함정 — `Workflow.randomUUID()` 대신 `UUID.randomUUID()` 를 멱등키로 쓰면**
> ```java
> // ✘ 액티비티가 재시도될 때마다 키가 바뀐다
> payment.chargeIdempotent(req.orderId(), req.amount(), UUID.randomUUID().toString());
> ```
> 액티비티가 타임아웃으로 재시도되면 워크플로우 코드는 **리플레이되지 않습니다** — 액티비티 재시도는 서버가 같은 입력으로 다시 스케줄하는 것뿐이라 키는 유지됩니다. 진짜 문제는 **Worker 재시작으로 리플레이가 일어날 때**입니다. 새 UUID 가 생성되어 히스토리에 기록된 액티비티 입력과 달라지고, 최악의 경우 결제 서버가 이를 **새 결제 요청**으로 인식합니다.
> `Workflow.randomUUID()` 는 Run ID 를 시드로 하므로 리플레이해도 같은 값이 나옵니다. 멱등키는 **반드시** 이쪽입니다. (Step 04 의 4-6 에서 이어집니다.)

---

## 정리

| 개념 | 핵심 |
|---|---|
| `@WorkflowInterface` | 인터페이스에 `@WorkflowMethod` **정확히 하나**, 구현체는 no-arg 생성자 |
| 인자·반환값 | Jackson 직렬화 가능해야 함. record 권장, 필드명 변경은 호환성 파괴 |
| 결정성 | **같은 히스토리 → 같은 Command 열.** 리플레이가 전적으로 여기 의존 |
| 리플레이 | Worker 가 죽으면 히스토리를 처음부터 재실행. 액티비티는 실제 호출되지 않고 과거 결과 주입 |
| 금지 | 벽시계·난수·직접 스레드·외부 IO·static 상태·HashMap 순회·환경변수 |
| 대체제 | `Workflow.currentTimeMillis/randomUUID/newRandom/sleep`, `Async.function`, `sideEffect` |
| `currentTimeMillis()` | 현재 **Workflow Task 시작 시각**. 같은 Task 안에서는 0ms 도 안 흐른다 |
| `sideEffect` | 람다는 첫 실행에만. 결과가 `MarkerRecorded` 로 히스토리에 남아 리플레이 때 재사용 |
| `mutableSideEffect` | 매번 실행하되 **값이 바뀔 때만** Marker 기록 |
| `NonDeterministicException` | 워크플로우를 실패시키지 않는다. **Workflow Task 만 무한 재시도** (`Status: RUNNING`, Attempt 증가) |
| 복구 | 코드를 고쳐 재배포하면 멈춰 있던 지점부터 그대로 이어짐 |
| 단일 스레드 모델 | 협력적 스케줄러. 양보 지점에서만 전환 → 동기화 불필요 |
| 병렬화 | `Async.function()` + `Promise.allOf()`. 직접 스레드는 `Workflow.newChildThread` |
| 로깅 | `Workflow.getLogger()` — 리플레이 중 중복 출력 억제 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 결정성을 위반하는 워크플로우 코드에서 위반 지점을 **전부** 찾아 표시하기
2. 찾은 위반을 `Workflow.*` 대체제로 고쳐 결정적으로 만들기
3. `@WorkflowMethod` 가 두 개인 인터페이스를 고치고, 왜 하나여야 하는지 설명하기
4. `Workflow.sideEffect()` 로 외부 설정값을 안전하게 읽고, 언제 `mutableSideEffect` 를 써야 하는지 판단하기
5. 순차 호출 3개를 `Async.function()` + `Promise.allOf()` 로 병렬화하고, 히스토리 이벤트 수가 어떻게 줄어드는지 예측하기
6. 주어진 `NonDeterministicException` 스택트레이스를 읽고 어느 줄이 원인인지 지목하기

---

## 다음 단계

결정성 때문에 워크플로우 코드에서 할 수 있는 일이 크게 제한된다는 것을 확인했습니다. 그럼 결제·재고·배송 같은 **진짜 일**은 어디서 할까요. 답은 액티비티입니다. 액티비티는 제약이 하나도 없는 대신, 타임아웃 4종과 멱등성이라는 새로운 고민을 가져옵니다.

→ [Step 04 — 액티비티](../step-04-activities/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 읽으며 3-1 ~ 3-7 의 예제를 확인하고, `Exercise.java` 의 6문제를 직접 고친 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 최상위 클래스 하나 안에 `static nested interface` / `class` 로 워크플로우와 액티비티를 담고 있어 단일 파일로 컴파일됩니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 실습 파일입니다.

- `[3-1]` 은 `OrderWorkflow` 인터페이스와 정상 구현체, 그리고 **`@WorkflowMethod` 가 두 개라 등록에 실패하는** `BadWorkflow` 를 나란히 둡니다. 후자는 주석 처리되어 있으니 직접 풀어서 `IllegalArgumentException` 을 확인하세요.
- `[3-3]` 의 `NonDeterministicWorkflowImpl` 은 **위반 9종을 한 클래스에 몰아넣은** 반면교사입니다. 절대 실행하지 말고 읽기만 하세요. 각 줄 옆에 어떤 리플레이 시나리오에서 깨지는지 주석이 붙어 있습니다.
- `[3-4]` 의 `DeterministicWorkflowImpl` 이 그 대응표입니다. `[3-3]` 과 **줄 단위로 짝을 이루도록** 배치했으니 두 클래스를 좌우로 놓고 비교하세요.
- `[3-5]` 의 `BrokenTimeWorkflowImpl` 이 재현 실습의 주인공입니다. `main()` 의 `runBrokenScenario()` 가 Worker 를 띄우고 워크플로우를 시작한 뒤, **의도적으로 Worker 를 종료했다가 재기동**해서 `NonDeterministicException` 을 유발합니다. `--broken` 인자를 줘야 실행됩니다.
- `[3-7]` 의 `ParallelWorkflowImpl` 은 순차 버전과 `Async` 병렬 버전을 둘 다 담고 있습니다. 각각 실행한 뒤 `temporal workflow show` 로 히스토리 길이를 비교해 보세요.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// TODO: 여기에 작성` 자리를 채우는 구조입니다.

- **문제 1·2** 는 `Ex1BrokenWorkflowImpl` 하나를 대상으로 합니다. 먼저 위반을 주석으로 표시(문제 1)한 뒤, 같은 클래스를 `Ex2FixedWorkflowImpl` 로 고쳐 쓰는(문제 2) 순서입니다. 위반이 **7군데** 숨어 있으니 다 찾으세요.
- **문제 3** 의 `Ex3MultiMethodWorkflow` 는 컴파일은 되지만 Worker 등록에서 터집니다. 어느 메서드를 `@WorkflowMethod` 로 남기고 나머지를 어떻게 처리할지가 핵심입니다 — 지우는 게 답이 아닐 수도 있습니다(Step 07 의 Signal/Query 를 떠올리세요).
- **문제 4** 는 `FeatureFlags.isNewPricingEnabled()` 라는 외부 정적 메서드를 워크플로우에서 읽어야 하는 상황입니다. `sideEffect` 와 `mutableSideEffect` 중 무엇을 쓸지 판단 근거를 주석으로 적으세요.
- **문제 5** 는 액티비티 3개를 순차 호출하는 코드가 주어집니다. 병렬화한 뒤 **히스토리 이벤트 수를 숫자로 예측**해서 주석에 적고, 실제로 돌려 맞는지 확인하는 문제입니다.
- **문제 6** 은 코드를 쓰지 않습니다. 파일 하단 주석에 들어 있는 스택트레이스를 읽고, 함께 주어진 워크플로우 코드에서 **몇 번째 줄이 원인인지** 지목하는 문제입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과, "왜 그런지"를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 위반 7군데를 전부 열거합니다. 놓치기 쉬운 것은 `HashMap` 순회와 `Logger` 선택입니다. `LoggerFactory.getLogger()` 는 결정성을 직접 깨지는 않지만 리플레이 때 로그가 중복되므로 **위반 목록에 포함**시켰습니다.
- **정답 2** 에서 가장 중요한 것은 `UUID.randomUUID()` → `Workflow.randomUUID()` 입니다. 이 값이 액티비티의 멱등키로 흘러가기 때문에, 잘못 고치면 **결제가 두 번 되는 경로**가 열립니다. 주석에서 그 시나리오를 타임라인으로 설명합니다.
- **정답 3** 의 답은 "나머지 두 메서드를 `@SignalMethod` 와 `@QueryMethod` 로 바꾼다"입니다. 지워 버리는 것보다 원래 의도에 맞습니다. `@WorkflowMethod` 는 "이 워크플로우를 시작하는 진입점"이라 하나뿐인 것이 당연하다는 설명이 붙습니다.
- **정답 4** 는 `mutableSideEffect` 를 고릅니다. 피처 플래그는 **워크플로우 실행 도중 바뀔 수 있고**, 반복문 안에서 여러 번 읽히기 때문입니다. `sideEffect` 로 하면 호출할 때마다 Marker 가 쌓여 히스토리가 부풀고, 값도 첫 결과에 영원히 고정됩니다.
- **정답 5** 의 예측치는 **순차 22개 → 병렬 14개**입니다. 액티비티 하나마다 필요한 Workflow Task 왕복(Scheduled/Started/Completed 3개)이 2회분 사라지기 때문입니다. 계산 과정을 이벤트별로 적어 두었습니다.
- **정답 6** 의 원인 줄은 `if (LocalDate.now().getDayOfWeek() == DayOfWeek.SATURDAY)` 입니다. 스택트레이스의 `Failure handling event 5 of type 'EVENT_TYPE_TIMER_STARTED'` 와 `COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK` 를 조합해 **"타이머를 걸었어야 할 자리에서 액티비티를 스케줄했다"** 로 역추적하는 방법을 단계별로 설명합니다.

```java file="./Solution.java"
```
