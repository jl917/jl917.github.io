# Step 01 — 환경 구축과 첫 워크플로우

> **학습 목표**
> - `docker compose` 로 Temporal Server 1.22.4 를 띄우고 `temporal operator namespace list` 로 연결을 확인한다
> - Temporal Server / Worker / Client 의 3자 관계를 이해하고, **Server 가 내 코드를 실행하지 않는다**는 사실을 확인한다
> - `@WorkflowInterface` / `@WorkflowMethod` 로 첫 워크플로우를 정의하고 Worker 를 기동한다
> - `WorkflowClient` 로 워크플로우를 동기·비동기로 실행하고 WorkflowId 를 직접 지정한다
> - **Web UI 와 `temporal workflow show` 로 이벤트 히스토리를 직접 읽는다**
> - Activity 를 하나 붙여 히스토리가 **8개 → 11개**로 늘어나는 것을 before/after 로 실측한다
> - Worker 미기동·Task Queue 오타라는 두 함정이 **에러 없이 조용히** 워크플로우를 매달아 두는 것을 재현한다
>
> **선행 스텝**: 없음 (실습 프로젝트 셋업 → [project/](../project/))
> **예상 소요**: 90분

---

## 1-0. 실습 준비

Temporal Server 를 먼저 띄웁니다. 이 코스는 `temporalio/auto-setup:1.22.4` + PostgreSQL 15 조합을 씁니다.

```bash
cd temporal-course
docker compose up -d
```

**결과**
```
[+] Running 4/4
 ✔ Network temporal-course_default        Created            0.1s
 ✔ Container temporal-postgresql          Started            0.6s
 ✔ Container temporal                     Started            1.2s
 ✔ Container temporal-ui                  Started            1.4s
```

컨테이너 세 개가 뜹니다. `temporal-postgresql` 은 히스토리 저장소, `temporal` 은 gRPC 서버(7233), `temporal-ui` 는 Web UI(8233) 입니다.

기동에는 15~30초쯤 걸립니다. auto-setup 이미지가 첫 부팅 때 DB 스키마를 만들기 때문입니다. `docker compose logs temporal | tail -1` 에 `Temporal server started.` 가 뜨면 준비 완료입니다. CLI 로 연결을 확인합니다.

```bash
temporal operator namespace list
```

**결과**
```
Name                  UUID                                  State       Retention
default               a7f3b1c2-9e4d-4a8b-b1c6-3f5e7d9a2c40  Registered  72h0m0s
temporal-system       32049b68-7872-4094-8e63-d0dd59896a83  Registered  168h0m0s
```

`default` 네임스페이스가 `Registered` 로 보이면 서버와 CLI 가 정상 연결된 것입니다. `temporal-system` 은 Temporal 내부용이므로 건드리지 않습니다. `temporal --version` 은 `temporal version 0.11.0 (server 1.22.4, ui 2.21.3)` 을 출력해야 합니다.

> 💡 **실무 팁 — CLI 가 서버를 못 찾을 때**
> `temporal` CLI 는 기본으로 `127.0.0.1:7233` 을 봅니다. 다른 주소면 `--address` 를 매번 붙이는 대신
> `temporal env set local.address 127.0.0.1:7233` 으로 환경을 등록하고 `temporal --env local ...` 로 쓰세요.
> 운영 클러스터를 다룰 때 `--env prod` / `--env stg` 로 분리해 두면 "실수로 운영에 명령을 날리는" 사고를 줄일 수 있습니다.

Web UI 도 열어 둡니다. 이후 절에서 계속 씁니다.

```
http://localhost:8233
```

---

## 1-1. 구성 요소 한눈에 — Server 는 내 코드를 실행하지 않는다

Temporal 을 처음 접하면 대부분 이렇게 오해합니다. "워크플로우 코드를 서버에 올리면 서버가 실행해 주는 거겠지." **틀렸습니다.**

Temporal Server 는 여러분의 코드를 **한 줄도 실행하지 않습니다.** 서버가 하는 일은 세 가지뿐입니다.

1. 이벤트 히스토리를 저장한다
2. 할 일(Task)을 Task Queue 에 얹어 둔다
3. 누군가 가져가면 준다

실제로 코드를 실행하는 것은 **여러분이 띄운 Worker 프로세스**입니다. 그림으로 보면 이렇습니다.

```
   ┌──────────────────────────────────────────────────────────────┐
   │                     Temporal Server (남의 코드)               │
   │   ┌────────────┐   ┌──────────────┐   ┌──────────────────┐   │
   │   │  Frontend  │   │   History    │   │     Matching     │   │
   │   │  (gRPC)    │   │  (이벤트 저장) │   │  (Task Queue)    │   │
   │   └─────┬──────┘   └──────┬───────┘   └────────┬─────────┘   │
   │         └─────────────────┴────────────────────┘             │
   │                    [ PostgreSQL 15 ]                         │
   │              워크플로우별 이벤트 히스토리                        │
   └───────────────┬──────────────────────────┬───────────────────┘
                   │                          │
      ① StartWorkflowExecution      ② long-poll 로 Task 를 가져감
         (워크플로우 시작 요청)          (ORDER_TASK_QUEUE)
                   ▼                          ▼
        ┌────────────────────┐    ┌──────────────────────────────┐
        │   Client (내 코드)   │    │      Worker (내 코드)         │
        │  WorkflowClient     │    │  OrderWorkflowImpl.java  ←실행│
        │  .newWorkflowStub() │    │  PaymentActivityImpl.java←실행│
        │  .processOrder()    │    │  WorkerFactory / Worker       │
        │  (Spring 컨트롤러 등)│    │                              │
        └────────────────────┘    └──────────────────────────────┘
             프로세스 A                      프로세스 B
```

- **Server**: 남이 만든 것. 내가 코드를 배포하지 않습니다. 히스토리 저장 + Task 중개만 합니다.
- **Worker**: 내가 만든 것. 워크플로우 구현체와 액티비티 구현체가 **여기에** 등록되고 **여기서** 실행됩니다.
- **Client**: 내가 만든 것. 워크플로우를 시작하거나 결과를 조회합니다. 보통 API 서버 안에 있습니다.

이 구조에서 곧바로 따라 나오는 결론이 하나 있습니다. **Worker 가 없으면 워크플로우는 시작만 되고 아무것도 진행되지 않습니다.** 에러도 나지 않습니다. 서버는 "할 일을 큐에 얹어 뒀는데 아무도 안 가져가네" 상태로 조용히 기다립니다. 이것이 이 스텝의 첫 번째 함정이며, 1-8 에서 재현합니다.

> 💡 **비유 — Temporal Server 는 항공 관제탑입니다**
> 관제탑은 비행기를 조종하지 않습니다. 어디로 갈지 지시하고 기록을 남길 뿐, 실제로 조종간을 잡는 것은 조종사(Worker)입니다.
> 관제탑만 있고 조종사가 없으면 비행기는 활주로에 그대로 서 있습니다. 사고가 난 것도 아니고, 그냥 아무 일도 안 일어납니다.

---

## 1-2. 첫 Workflow 인터페이스와 구현

가장 단순한 형태로 시작합니다. **액티비티 없이 문자열만 반환**하는 워크플로우입니다.

Temporal Java SDK 1.22.3 에서 워크플로우는 **인터페이스 + 구현 클래스** 쌍으로 정의합니다.

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

- `@WorkflowInterface` — 이 인터페이스가 워크플로우 계약임을 표시합니다. 이게 없으면 Worker 등록 시점에 예외가 납니다.
- `@WorkflowMethod` — 워크플로우의 **진입점**입니다. 인터페이스당 **정확히 하나**만 있을 수 있습니다.

DTO 는 Java 21 record 로 정의합니다.

```java
public record OrderRequest(String orderId, String customerId, String sku,
                           int qty, long amount, String address) {}
```

구현 클래스입니다.

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    @Override
    public String processOrder(OrderRequest req) {
        log.info("[{}] 워크플로우 시작 sku={} qty={}", req.orderId(), req.sku(), req.qty());
        return "order-" + req.orderId() + " COMPLETED";
    }
}
```

로거를 `LoggerFactory.getLogger` 가 아니라 **`Workflow.getLogger`** 로 가져온 것에 주목하세요. 이유는 Step 02 에서 리플레이를 다루며 자세히 설명합니다. 지금은 "워크플로우 코드 안에서는 `Workflow.getLogger` 를 쓴다"고만 기억하면 됩니다.

> ⚠️ 워크플로우 구현 클래스에는 **기본 생성자(인자 없는 생성자)** 가 있어야 합니다.
> Worker 가 워크플로우 인스턴스를 리플렉션으로 만들기 때문입니다. 생성자에 의존성을 주입하려 하면 실행 시점에 실패합니다.
> 액티비티는 반대로 인스턴스를 직접 만들어 등록하므로 생성자 주입이 자유롭습니다(1-7).

Task Queue 이름은 **상수로 뽑습니다.** 이유는 1-9 에서 아플 정도로 설명합니다.

```java
public final class Constants {
    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";
    private Constants() {}
}
```

---

## 1-3. Worker 기동

Worker 는 별도의 `main` 을 가진 독립 프로세스입니다.

```java
public class OrderWorker {

    public static void main(String[] args) {
        // ① 서버(127.0.0.1:7233)로의 gRPC 연결
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        // ② 그 연결 위의 클라이언트
        WorkflowClient client = WorkflowClient.newInstance(service);
        // ③ Worker 를 만들어 낼 팩토리
        WorkerFactory factory = WorkerFactory.newInstance(client);
        // ④ 특정 Task Queue 를 폴링할 Worker
        Worker worker = factory.newWorker(Constants.ORDER_TASK_QUEUE);
        // ⑤ 실행 가능한 워크플로우 타입 등록 (구현 "클래스"를 넘긴다)
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        // ⑥ 폴링 시작. 이 호출은 블로킹하지 않는다
        factory.start();

        System.out.println("Worker started. Task Queue = " + Constants.ORDER_TASK_QUEUE);
    }
}
```

각 호출이 무엇인지 정리합니다.

| 호출 | 역할 |
|---|---|
| `WorkflowServiceStubs.newLocalServiceStubs()` | `127.0.0.1:7233` 로 gRPC 채널 생성. 운영에서는 `newServiceStubs(options)` 로 주소·TLS 지정 |
| `WorkflowClient.newInstance(service)` | 채널 위에 네임스페이스·데이터 컨버터를 얹은 클라이언트 |
| `WorkerFactory.newInstance(client)` | Worker 들이 공유하는 스레드풀·캐시를 관리 |
| `factory.newWorker(queue)` | 그 Task Queue 를 long-poll 할 Worker 하나 |
| `registerWorkflowImplementationTypes(...)` | **클래스**를 등록. Worker 가 매 실행마다 새 인스턴스를 만든다 |
| `factory.start()` | 폴러 스레드 기동 |

실행합니다.

```bash
./gradlew runWorker
```

**결과** (Worker 콘솔 로그 전문)
```
09:12:01.845 [main] INFO  i.t.s.WorkflowServiceStubsImpl - Created GRPC client for channel: ManagedChannelOrphanWrapper{delegate=ManagedChannelImpl{logId=1, target=127.0.0.1:7233}}
09:12:02.311 [main] INFO  i.t.s.WorkflowServiceStubsImpl - Channel 127.0.0.1:7233 is READY
09:12:02.402 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default", identity=41233@macbook}
09:12:02.404 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Activity Poller taskQueue="ORDER_TASK_QUEUE", namespace="default", identity=41233@macbook}
09:12:02.406 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Local Activity Poller taskQueue="ORDER_TASK_QUEUE", namespace="default", identity=41233@macbook}
09:12:02.411 [main] INFO  i.t.i.w.WorkerFactoryImpl - Started Worker{namespace=default, taskQueue=ORDER_TASK_QUEUE}
Worker started. Task Queue = ORDER_TASK_QUEUE
```

`Poller` 가 세 개 뜬 것에 주목하세요. Workflow Task, Activity Task, Local Activity 를 각각 별도 폴러가 long-poll 합니다. 아직 아무것도 안 했는데도 Worker 는 이미 서버에 "일 없나요?" 하고 물어보며 대기 중입니다.

Worker 가 정말 붙었는지 서버 쪽에서도 확인합니다.

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Workflow Poller Info:
  Identity          41233@macbook
  Last Access Time  10 seconds ago
  Rate Per Second   100000

Activity Poller Info:
  Identity          41233@macbook
  Last Access Time  10 seconds ago
  Rate Per Second   100000
```

**폴러가 등록되어 있습니다.** 이 출력이 비어 있으면 Worker 가 안 떴거나 큐 이름이 다르다는 뜻입니다 — 진단 방법으로 계속 쓰게 됩니다.

---

## 1-4. Workflow 실행

Client 는 별도 프로세스입니다. Worker 를 띄운 터미널은 그대로 두고 새 터미널을 엽니다.

```java
public class OrderStarter {

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        OrderRequest req = new OrderRequest(
                "1001", "cust-77", "SKU-BLACK-TEE", 2, 39000L, "서울시 강남구 테헤란로 1");

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(Constants.ORDER_TASK_QUEUE)
                .setWorkflowId("order-" + req.orderId())    // ← 비즈니스 키를 그대로
                .build();

        OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);
        String result = workflow.processOrder(req);   // 동기 실행: 끝날 때까지 블로킹
        System.out.println("결과: " + result);
    }
}
```

`client.newWorkflowStub(OrderWorkflow.class, options)` 가 돌려주는 것은 **워크플로우 구현체가 아니라 동적 프록시**입니다. `workflow.processOrder(req)` 를 호출하면 로컬에서 메서드가 실행되는 게 아니라, gRPC 로 `StartWorkflowExecution` 요청이 서버에 전송됩니다.

`./gradlew runStarter` 를 실행합니다.

**결과** (Client 콘솔 / 같은 시각 Worker 콘솔)
```
# Client
09:12:04.118 [main] INFO  i.t.s.WorkflowServiceStubsImpl - Channel 127.0.0.1:7233 is READY
결과: order-1001 COMPLETED

# Worker
09:12:04.640 [workflow-method-order-1001-8f2a1c] INFO  c.e.order.OrderWorkflowImpl - [1001] 워크플로우 시작 sku=SKU-BLACK-TEE qty=2
```

스레드 이름이 `workflow-method-order-1001-...` 입니다. **Worker 프로세스에서 실행됐다는 증거**입니다. Client 프로세스에는 워크플로우 로그가 한 줄도 없습니다.

### 비동기 실행

동기 호출은 워크플로우가 끝날 때까지 블로킹합니다. 워크플로우가 며칠씩 도는 것이 Temporal 의 정상적인 용법이므로, 실무에서는 대개 비동기로 시작합니다.

```java
OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);

// 시작만 하고 즉시 반환. WorkflowExecution(workflowId + runId)을 돌려준다
WorkflowExecution exec = WorkflowClient.start(workflow::processOrder, req);
System.out.println("started workflowId=" + exec.getWorkflowId() + " runId=" + exec.getRunId());

// 나중에 결과가 필요해지면 다시 붙어서 기다린다
OrderWorkflow attached = client.newWorkflowStub(OrderWorkflow.class, exec.getWorkflowId());
String result = WorkflowStub.fromTyped(attached).getResult(String.class);
System.out.println("결과: " + result);
```

**결과**
```
started workflowId=order-1001 runId=5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
결과: order-1001 COMPLETED
```

`WorkflowClient.start` 는 메서드 참조(`workflow::processOrder`)를 받습니다. 스텁이 프록시이기 때문에 가능한 트릭입니다.

> 💡 **실무 팁 — WorkflowId 는 비즈니스 키로 지정하세요**
> 지정하지 않으면 SDK 가 UUID 를 붙입니다. 그러면 "주문 1001 의 워크플로우가 어떻게 됐지?" 를 조회할 방법이 없어져, 별도 매핑 테이블을 만들게 됩니다.
> `order-1001` 처럼 비즈니스 키를 쓰면 조회가 `temporal workflow describe -w order-1001` 한 줄로 끝나고,
> 덤으로 **중복 실행 방지**가 공짜로 따라옵니다. Temporal 은 같은 WorkflowId 로 **동시에 두 개가 Running 일 수 없도록** 보장하기 때문입니다.
> 이 정책은 `WorkflowIdReusePolicy` 로 조절합니다(Step 12 에서 상세히). 결제·주문처럼 멱등성이 중요한 도메인에서는 이것만으로도 큰 이득입니다.

`order-1001` 이 아직 Running 인 상태에서 `./gradlew runStarter` 를 한 번 더 실행하면 바로 확인됩니다.

**결과**
```
Exception in thread "main" io.temporal.client.WorkflowExecutionAlreadyStarted:
  workflowId=order-1001, runId=5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
```

---

## 1-5. Web UI 에서 이벤트 히스토리 직접 확인 ★

여기가 이 스텝의 핵심입니다. Temporal 을 이해한다는 것은 사실상 **이벤트 히스토리를 읽을 줄 안다**는 뜻입니다.

브라우저에서 http://localhost:8233 을 엽니다. 좌측 상단 네임스페이스가 `default` 인지 확인하고 **Workflows** 메뉴를 클릭하면 실행 목록이 나옵니다.

```
Status      Workflow ID    Type            Start              End                 Run ID
COMPLETED   order-1001     OrderWorkflow   2026-03-11 09:12:04 2026-03-11 09:12:04 5c2f8e1a…
```

`order-1001` 행을 클릭하면 실행 상세로 들어갑니다. 하단 **Event History** 탭을 열고 표시 모드를 **Compact 가 아니라 History(전체)** 로 바꿉니다. Compact 는 요약이라 실제 이벤트가 감춰집니다.

**Event History 탭 내용** (액티비티 없는 최초 버전)

| ID | Time | Type | 요약 |
|---:|---|---|---|
| 1 | 09:12:04.512 | `WorkflowExecutionStarted` | workflowType=OrderWorkflow, taskQueue=ORDER_TASK_QUEUE, input=[OrderRequest{...}] |
| 2 | 09:12:04.512 | `WorkflowTaskScheduled` | taskQueue=ORDER_TASK_QUEUE, startToCloseTimeout=10s |
| 3 | 09:12:04.631 | `WorkflowTaskStarted` | identity=41233@macbook, requestId=... |
| 4 | 09:12:04.688 | `WorkflowTaskCompleted` | scheduledEventId=2, startedEventId=3 |
| 5 | 09:12:04.688 | `WorkflowExecutionCompleted` | result=["order-1001 COMPLETED"] |

**단 5개입니다.** 여기서 곧바로 읽어 낼 것이 몇 가지 있습니다.

- 이벤트 1 은 Client 가 `StartWorkflowExecution` 을 호출한 순간 서버가 기록한 것입니다. **입력값 전체가 히스토리에 저장됩니다.**
- 이벤트 2 는 서버가 "누가 이 워크플로우 코드를 좀 돌려 주세요" 라고 Task Queue 에 얹은 것입니다.
- 이벤트 3 은 Worker 가 그것을 **가져간** 시각입니다. 2번과 3번 사이의 119ms 가 Worker 가 폴링해서 집어 간 시간입니다.
- 이벤트 4 는 Worker 가 코드를 실행하고 결과를 돌려준 것입니다. 그리고 **그 결과로 생긴 것이 이벤트 5** 입니다.

이벤트 1 을 클릭해 펼치면 상세가 나옵니다.

```json
{
  "workflowType": { "name": "OrderWorkflow" },
  "taskQueue": { "name": "ORDER_TASK_QUEUE", "kind": "Normal" },
  "input": [ { "orderId": "1001", "customerId": "cust-77", "sku": "SKU-BLACK-TEE",
               "qty": 2, "amount": 39000, "address": "서울시 강남구 테헤란로 1" } ],
  "workflowExecutionTimeout": "0s",
  "workflowTaskTimeout": "10s",
  "identity": "41255@macbook",
  "attempt": 1
}
```

`workflowExecutionTimeout: 0s` 는 "타임아웃 없음(무제한)" 입니다. Temporal 의 기본은 **워크플로우가 영원히 살아 있어도 된다**입니다.

> 💡 **Web UI 의 Compact 뷰를 믿지 마세요**
> Compact 뷰는 액티비티 하나를 한 줄로 접어 보여줍니다. 편하지만, `WorkflowTaskScheduled/Started/Completed` 3종 세트가 감춰집니다.
> 워크플로우가 멈춘 원인을 찾을 때는 **반드시 전체 History 뷰**로 보세요. 문제는 대개 감춰진 Workflow Task 쪽에 있습니다.

---

## 1-6. CLI 로 같은 것 보기

Web UI 로 본 것을 CLI 로도 봅니다. 운영에서는 CLI 쪽을 훨씬 많이 씁니다.

```bash
temporal workflow list
```

**결과**
```
  Status     WorkflowId    Name           StartTime             CloseTime
  COMPLETED  order-1001    OrderWorkflow  2026-03-11T09:12:04Z  2026-03-11T09:12:04Z
```

```bash
temporal workflow describe -w order-1001
```

**결과**
```
Execution Info:
  Workflow Id       order-1001
  Run Id            5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
  Type              OrderWorkflow
  Namespace         default
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-03-11 09:12:04 +0000 UTC
  Status            COMPLETED
  History Length    5
  History Size      612
```

`History Length 5` — Web UI 에서 센 것과 정확히 같습니다. `History Size 612` 는 바이트입니다.

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
   5  2026-03-11T09:12:04Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-1001 COMPLETED"]
```

이벤트 상세가 필요하면 `temporal workflow show -w order-1001 --output json | jq '.events[1]'` 처럼 JSON 으로 뽑습니다.

**결과**
```json
{
  "eventId": "2",
  "eventType": "WorkflowTaskScheduled",
  "workflowTaskScheduledEventAttributes": {
    "taskQueue": { "name": "ORDER_TASK_QUEUE", "kind": "Normal" },
    "startToCloseTimeout": "10s", "attempt": 1
  }
}
```

> 💡 **실무 팁 — 히스토리를 파일로 떠 두세요**
> `temporal workflow show -w order-1001 --output json > order-1001.json` 으로 저장해 두면
> 나중에 **리플레이 테스트**(Step 11)의 입력으로 그대로 쓸 수 있습니다.
> 운영 장애 때 "이 히스토리로 새 코드가 리플레이되는가"를 검증하는 것이 버저닝의 핵심 안전장치입니다(Step 10).

---

## 1-7. Activity 를 하나 붙여 보기

지금까지는 액티비티가 없었습니다. 결제 액티비티 하나를 붙여 히스토리가 어떻게 달라지는지 봅니다.

액티비티도 인터페이스 + 구현입니다.

```java
@ActivityInterface
public interface PaymentActivity {
    @ActivityMethod String charge(String orderId, long amount);
    @ActivityMethod void refund(String paymentId);
}

public class PaymentActivityImpl implements PaymentActivity {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

    @Override
    public String charge(String orderId, long amount) {
        log.info("[{}] 결제 요청 amount={}", orderId, amount);
        return "pay-" + orderId;    // 실제로는 PG 사 HTTP 호출
    }

    @Override
    public void refund(String paymentId) {
        log.info("환불 처리 paymentId={}", paymentId);
    }
}
```

액티비티 구현은 `Workflow.getLogger` 가 아니라 **일반 `LoggerFactory`** 를 씁니다. 액티비티는 리플레이되지 않으므로 로그가 중복될 일이 없습니다(Step 02 에서 이유를 설명합니다).

워크플로우에서 호출합니다.

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build());

    @Override
    public String processOrder(OrderRequest req) {
        log.info("[{}] 워크플로우 시작 sku={}", req.orderId(), req.sku());
        String paymentId = payment.charge(req.orderId(), req.amount());
        log.info("[{}] 결제 완료 paymentId={}", req.orderId(), paymentId);
        return "order-" + req.orderId() + " COMPLETED";
    }
}
```

`Workflow.newActivityStub` 도 프록시입니다. `payment.charge(...)` 를 호출해도 **`PaymentActivityImpl.charge` 가 직접 실행되지 않습니다.** 대신 "이 액티비티를 스케줄해 달라"는 Command 가 만들어집니다(Step 02 의 2-5).

`setStartToCloseTimeout` 은 **필수**입니다. 빠뜨리면 Worker 등록 시점이 아니라 워크플로우 실행 시점에 예외가 납니다.

Worker 에 액티비티도 등록합니다. 워크플로우는 **클래스**를 넘기지만 액티비티는 **인스턴스**를 넘깁니다.

```java
worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
worker.registerActivitiesImplementations(new PaymentActivityImpl());   // ← 인스턴스
```

Worker 를 재시작하고, WorkflowId 를 `order-1002` 로 바꿔 실행합니다.

**Worker 콘솔**
```
09:20:11.104 [workflow-method-order-1002-3a91f2] INFO  c.e.order.OrderWorkflowImpl - [1002] 워크플로우 시작 sku=SKU-BLACK-TEE
09:20:11.208 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.o.PaymentActivityImpl - [1002] 결제 요청 amount=39000
09:20:11.331 [workflow-method-order-1002-3a91f2] INFO  c.e.order.OrderWorkflowImpl - [1002] 결제 완료 paymentId=pay-1002
```

스레드 이름이 다릅니다. 워크플로우 코드는 `workflow-method-...` 스레드에서, 액티비티는 `Activity Executor ...` 스레드에서 실행됩니다. **완전히 다른 실행 컨텍스트**입니다.

### before / after 히스토리 비교

**BEFORE — 액티비티 없음 (order-1001)** — 1-6 에서 본 그대로 **5 이벤트**입니다.
```
   1 WorkflowExecutionStarted / 2 WorkflowTaskScheduled / 3 WorkflowTaskStarted
   4 WorkflowTaskCompleted    / 5 WorkflowExecutionCompleted
```

**AFTER — 결제 액티비티 1개 추가 (order-1002)**
```bash
temporal workflow show -w order-1002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T09:20:11Z  WorkflowExecutionStarted
   2  2026-03-11T09:20:11Z  WorkflowTaskScheduled
   3  2026-03-11T09:20:11Z  WorkflowTaskStarted
   4  2026-03-11T09:20:11Z  WorkflowTaskCompleted
   5  2026-03-11T09:20:11Z  ActivityTaskScheduled       ← 추가
   6  2026-03-11T09:20:11Z  ActivityTaskStarted         ← 추가
   7  2026-03-11T09:20:11Z  ActivityTaskCompleted       ← 추가
   8  2026-03-11T09:20:11Z  WorkflowTaskScheduled       ← 추가
   9  2026-03-11T09:20:11Z  WorkflowTaskStarted         ← 추가
  10  2026-03-11T09:20:11Z  WorkflowTaskCompleted       ← 추가
  11  2026-03-11T09:20:11Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-1002 COMPLETED"]
```

**5개 → 11개.** 액티비티 하나를 붙였는데 6개가 늘었습니다. 내역은 이렇습니다.

| 늘어난 이벤트 | 의미 |
|---|---|
| 5. `ActivityTaskScheduled` | 워크플로우가 "결제를 스케줄해 달라"는 Command 를 냈고 서버가 기록 |
| 6. `ActivityTaskStarted` | Worker 의 Activity 폴러가 그것을 가져감 |
| 7. `ActivityTaskCompleted` | 액티비티가 `pay-1002` 를 반환 |
| 8~10. `WorkflowTask*` 3종 | **결제 결과를 워크플로우 코드에 전달하려고** 워크플로우 코드를 한 번 더 돌림 |
| 11. `WorkflowExecutionCompleted` | 그 실행에서 워크플로우가 종료됨 |

여기서 중요한 것은 8~10 입니다. 액티비티가 끝날 때마다 **워크플로우 코드가 다시 한 번 실행됩니다.** 액티비티 결과를 받아 다음 진도를 나가야 하기 때문입니다. "액티비티 N개 = Workflow Task N+1개" 라는 대략의 감각을 여기서 얻어 두세요.

이벤트 7 의 상세를 봅니다.

```bash
temporal workflow show -w order-1002 --output json | jq '.events[6].activityTaskCompletedEventAttributes'
```

**결과**
```json
{
  "result": [ "pay-1002" ],
  "scheduledEventId": "5",
  "startedEventId": "6",
  "identity": "41233@macbook"
}
```

**액티비티의 반환값이 히스토리에 저장되어 있습니다.** 이 사실이 Step 02 의 리플레이를 이해하는 열쇠입니다.

---

## 1-8. 함정 (1) — Worker 를 안 띄우면 에러가 아니라 영원한 Running

Worker 프로세스를 `^C` 로 종료한 뒤, 그 상태에서 워크플로우를 시작합니다.

```bash
./gradlew runStarter   # order-1003
```

**결과** — 아무 일도 안 일어납니다. 예외도 없고, 프로세스가 그냥 매달려 있습니다.

```
09:31:20.118 [main] INFO  i.t.s.WorkflowServiceStubsImpl - Channel 127.0.0.1:7233 is READY
(...무한 대기...)
```

> ⚠️ **함정 — 연결 실패가 아니라 "정상적으로 아무 일도 안 일어나는" 상태입니다**
> 초보자가 가장 많이 겪는 상황입니다. 코드도 맞고 서버도 살아 있고 로그도 깨끗한데 결과가 안 옵니다.
> Temporal 은 "Worker 가 없다"를 **에러로 취급하지 않습니다.** Worker 는 배포 중일 수도, 스케일아웃 중일 수도 있으니
> 서버는 Task 를 큐에 얹어 두고 무한정 기다립니다. **이 설계 덕분에 배포 중에 요청이 유실되지 않지만,
> 반대로 "영원히 안 끝나는 워크플로우"가 조용히 쌓입니다.**

Web UI 로 진단합니다. Workflows 목록에서 `order-1003` 은 **RUNNING** 이고, 실행 상세로 들어가면 Pending Activities 는 **비어 있고** Event History 는 이렇습니다.

| ID | Time | Type | 요약 |
|---:|---|---|---|
| 1 | 09:31:20.118 | `WorkflowExecutionStarted` | taskQueue=ORDER_TASK_QUEUE |
| 2 | 09:31:20.118 | `WorkflowTaskScheduled` | taskQueue=ORDER_TASK_QUEUE |

**2개에서 멈춰 있습니다.** `WorkflowTaskStarted` 가 없다는 것이 결정적 단서입니다. 서버는 Task 를 얹었는데 **아무도 가져가지 않았습니다.**

CLI 로도 같습니다.

```bash
temporal workflow describe -w order-1003
```

**결과**
```
Execution Info:
  Workflow Id       order-1003
  Type              OrderWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Status            RUNNING
  History Length    2

Pending Workflow Task:
  State              Scheduled
  Attempt            1
```

`Pending Workflow Task: State Scheduled` — 스케줄만 되고 시작이 안 됐습니다.

**진단의 결정타는 Task Queue 를 직접 보는 것입니다.**

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Workflow Poller Info:
  (no pollers)

Activity Poller Info:
  (no pollers)
```

**폴러가 하나도 없습니다.** 1-3 에서 봤던 출력과 비교하면 명확합니다. `./gradlew runWorker` 로 Worker 를 다시 띄웁니다.

**Worker 콘솔** — 기동하자마자 매달려 있던 워크플로우가 알아서 진행됩니다.
```
09:33:47.402 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", ...}
09:33:47.611 [workflow-method-order-1003-c02b18] INFO  c.e.order.OrderWorkflowImpl - [1003] 워크플로우 시작 sku=SKU-BLACK-TEE
09:33:47.702 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.o.PaymentActivityImpl - [1003] 결제 요청 amount=39000
```

블로킹돼 있던 Client 도 이제서야 `결과: order-1003 COMPLETED` 를 받습니다.

**2분 27초 동안 매달려 있다가 아무 손실 없이 재개됐습니다.** 이것이 Temporal 의 내구성입니다 — 다만 그 내구성이 "영원한 Running" 이라는 함정과 동전의 양면이라는 점을 기억하세요.

> 💡 **진단 3단계 체크리스트**
> 1. `temporal workflow describe -w <id>` → `History Length 2` 이고 `Pending Workflow Task` 가 `Scheduled` 인가?
> 2. `temporal task-queue describe --task-queue <queue>` → 폴러가 있는가?
> 3. 폴러가 없다면 → Worker 미기동이거나 **큐 이름이 다름**(다음 절).

---

## 1-9. 함정 (2) — Task Queue 이름 오타

Worker 는 정상 기동돼 있습니다. Client 쪽 큐 이름만 살짝 틀려 봅니다.

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
        .setTaskQueue("ORDER_TASK_QEUEU")     // ← 오타. QUEUE → QEUEU
        .setWorkflowId("order-1004")
        .build();
```

`./gradlew runStarter` 를 실행하면 — **컴파일도 되고 실행도 되고 예외도 없이** 그냥 매달립니다. Worker 콘솔에도 아무것도 찍히지 않습니다. Web UI 를 보면 이렇습니다.

| ID | Time | Type | 요약 |
|---:|---|---|---|
| 1 | 09:40:02.118 | `WorkflowExecutionStarted` | **taskQueue=ORDER_TASK_QEUEU** |
| 2 | 09:40:02.118 | `WorkflowTaskScheduled` | **taskQueue=ORDER_TASK_QEUEU** |

증상이 1-8 과 **완전히 똑같습니다.** History Length 2, Pending Workflow Task Scheduled. 그래서 "Worker 를 띄웠는데도 안 되네" 하며 헤매게 됩니다.

```bash
temporal task-queue describe --task-queue ORDER_TASK_QEUEU   # 오타 난 큐
temporal task-queue describe --task-queue ORDER_TASK_QUEUE   # 정상 큐
```

**결과**
```
# ORDER_TASK_QEUEU
Workflow Poller Info:
  (no pollers)

# ORDER_TASK_QUEUE
Workflow Poller Info:
  Identity          41233@macbook
  Last Access Time  3 seconds ago
```

오타 난 큐에는 폴러가 없고, 정상 큐의 폴러는 멀쩡합니다. **큐가 다를 뿐입니다.**

> ⚠️ **함정 — Task Queue 는 서버에 미리 등록하는 자원이 아닙니다**
> Kafka 토픽처럼 "미리 만들어 둔 것 중에서 고르는" 구조가 아닙니다. Temporal 의 Task Queue 는 **문자열을 던지면 그 순간 존재하는 것으로 취급**됩니다.
> 그래서 오타를 내면 "그런 큐 없음" 에러가 아니라 **`ORDER_TASK_QEUEU` 라는 새 큐가 조용히 생기고**, 거기엔 폴러가 없어서 영원히 대기합니다.
> 큐 이름 불일치는 **컴파일 타임에도, 런타임에도, 로그에서도 잡히지 않습니다.**
> **해결**: Task Queue 이름을 문자열 리터럴로 절대 쓰지 말고 상수 하나로 통일하세요.
> ```java
> public final class Constants {
>     public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";
> }
> ```
> Worker 와 Client 가 **같은 상수를 참조**하면 오타는 컴파일 에러가 되어 미리 잡힙니다.
> Spring 을 쓴다면 `@Value("${temporal.task-queue}")` 로 설정 파일 한 곳에서 주입하는 것도 같은 효과입니다.

매달린 `order-1004` 는 `temporal workflow terminate -w order-1004 --reason "task queue typo"` 로 정리합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Temporal Server | 내 코드를 **실행하지 않는다**. 히스토리 저장 + Task 중개만 |
| Worker | 내가 띄우는 프로세스. 워크플로우·액티비티가 **여기서** 실행됨 |
| Client | 워크플로우를 시작·조회. 보통 API 서버 안에 있음 |
| `@WorkflowInterface` | 워크플로우 계약. `@WorkflowMethod` 는 인터페이스당 정확히 1개 |
| 워크플로우 구현체 | **클래스**로 등록(`registerWorkflowImplementationTypes`). 기본 생성자 필수 |
| 액티비티 구현체 | **인스턴스**로 등록(`registerActivitiesImplementations`). 생성자 주입 자유 |
| `Workflow.newActivityStub` | 프록시. 호출해도 실제 실행이 아니라 Command 생성 |
| `startToCloseTimeout` | 액티비티 옵션 **필수**. 없으면 실행 시점 예외 |
| WorkflowId | 비즈니스 키(`order-1001`)로 지정 → 조회 편의 + 중복 실행 방지 |
| 동기 vs 비동기 | `stub.method()` 는 블로킹, `WorkflowClient.start(...)` 는 즉시 반환 |
| 히스토리 5 → 11 | 액티비티 1개 = `ActivityTask*` 3개 + `WorkflowTask*` 3개 = 6개 증가 |
| 함정 ① Worker 미기동 | 에러가 아니라 **영원한 RUNNING**. `History Length 2` 에서 멈춤 |
| 함정 ② 큐 이름 오타 | 컴파일·실행·로그 어디서도 안 잡힘. **상수로 통일**이 유일한 방어 |
| 진단 도구 | `temporal task-queue describe` 의 **폴러 유무**가 결정적 단서 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **직접 Worker 를 띄우고 히스토리를 확인**하세요.

1. `@WorkflowMethod` 없는 인터페이스를 등록하면 어떤 예외가 나는지 확인하고 고치기
2. `OrderWorkflow` 에 `InventoryActivity.reserve` 를 추가하고 히스토리 이벤트 수를 예측한 뒤 실측으로 검증하기
3. 동기 실행을 `WorkflowClient.start` 기반 비동기로 바꾸고, 나중에 결과를 가져오기
4. WorkflowId 를 지정하지 않았을 때 생성되는 ID 를 확인하고, 비즈니스 키 지정의 이점 서술하기
5. Task Queue 이름을 일부러 틀린 뒤 `task-queue describe` 로 진단하고 상수로 리팩터링하기
6. 액티비티 옵션에서 `setStartToCloseTimeout` 을 제거하면 언제(등록 시점? 실행 시점?) 무슨 예외가 나는지 확인하기

---

## 다음 단계

첫 워크플로우를 돌리고 히스토리를 읽었습니다. 그런데 아직 근본적인 질문이 남아 있습니다. **Temporal 은 어떻게 워크플로우의 "진행 상태"를 기억하는가?** 그 답은 "기억하지 않는다"입니다 — 상태 대신 **일어난 일의 목록**만 저장하고, 필요할 때마다 코드를 처음부터 다시 돌립니다.

다음 스텝에서는 이벤트 소싱과 리플레이를 다룹니다. Worker 를 실행 도중에 죽였다가 살려서 워크플로우가 이어지는 것을, 그리고 **액티비티는 다시 호출되지 않는 것**을 직접 재현합니다.

→ [Step 02 — 핵심 개념과 실행 모델](../step-02-concepts/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 먼저 `Practice.java` 의 `main` 을 두 개 터미널에서(Worker → Starter 순으로) 돌려 1-2 ~ 1-9 의 모든 실습을 재현하고, 그다음 `Exercise.java` 의 6문제를 직접 풀어 본 뒤, `Solution.java` 로 정답과 해설을 대조합니다. 세 파일 모두 **하나의 최상위 public 클래스 안에 nested class 로** 워크플로우·액티비티·Worker·Starter 를 전부 담고 있어, 별도 프로젝트 구성 없이 파일 하나만 컴파일하면 돌아갑니다.

### Practice.java

강의 본문(1-2 ~ 1-9)의 모든 코드를 절 번호 주석과 함께 한 파일에 모아 둔 실습 스크립트입니다.

- 진입점이 **두 개**입니다. `Practice$WorkerMain` 을 먼저 띄우고, 별도 터미널에서 `Practice$StarterMain` 을 실행합니다. 파일 상단 주석에 `./gradlew runWorker` / `./gradlew runStarter` 대응 명령이 적혀 있습니다.
- `StarterMain` 은 인자로 시나리오를 받습니다. `sync`(1-4 동기), `async`(1-4 비동기), `dup`(1-4 중복 실행 → `WorkflowExecutionAlreadyStarted`), `typo`(1-9 큐 이름 오타) 네 가지입니다. 인자 없이 실행하면 `sync` 입니다.
- `[1-7]` 구간의 `WITH_ACTIVITY` 상수를 `false` → `true` 로 바꾸면 결제 액티비티가 붙습니다. **BEFORE(5 이벤트) 를 먼저 실행해 보고 나서** `true` 로 바꿔 AFTER(11 이벤트)를 확인하세요. 이 순서를 지켜야 본문의 before/after 비교가 재현됩니다.
- `typo` 시나리오는 **의도적으로 매달립니다.** 30초 뒤 스스로 타임아웃하고 진단 명령(`temporal task-queue describe ...`)을 콘솔에 출력하도록 만들어 뒀습니다. Ctrl+C 로 끊어도 됩니다. 남은 워크플로우는 파일 주석의 `temporal workflow terminate -w order-1004` 로 정리하세요.
- 모든 WorkflowId 가 `order-100X` 로 고정이라 두 번 돌리면 `WorkflowExecutionAlreadyStarted` 가 날 수 있습니다. `StarterMain` 은 실행 전에 같은 ID 의 기존 실행을 `terminate` 하는 정리 로직을 갖고 있어 반복 실행이 안전합니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었고, 여러분이 코드를 채운 뒤 Worker 를 띄워 히스토리로 검증하는 구조입니다.

- **문제 1·6** 은 **일부러 실패하는 코드**로 시작합니다. `@WorkflowMethod` 가 빠져 있고 `setStartToCloseTimeout` 이 없습니다. 먼저 그대로 실행해 예외 메시지를 확인한 다음 고치세요. "무슨 예외가 언제 나는가"가 문제의 절반입니다.
- **문제 2** 는 히스토리 이벤트 수를 **먼저 종이에 예측해 적고** 실행하라고 요구합니다. `EXPECTED_EVENT_COUNT` 상수에 예측값을 적어 두면, 실행 후 실제 값과 비교해 출력해 줍니다.
- **문제 3** 의 `WorkflowClient.start` 는 메서드 참조를 받습니다. 람다(`() -> workflow.processOrder(req)`)로 쓰면 컴파일은 되지만 SDK 가 워크플로우 타입을 추출하지 못해 실행 시 실패합니다. 반드시 `workflow::processOrder` 형태를 쓰세요.
- **문제 5** 는 큐 이름을 `WRONG_TASK_QUEUE` 상수로 미리 틀리게 만들어 뒀습니다. 진단 → 상수 통합 리팩터링까지가 문제 범위이며, 답을 맞히는 것보다 **`task-queue describe` 로 폴러 유무를 확인하는 습관**을 들이는 게 목적입니다.
- 파일 끝의 `cleanup()` 은 이 파일이 만든 `order-2001` ~ `order-2006` 워크플로우를 전부 terminate 합니다. 문제를 반복해서 풀 때 실행하세요.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 의 예외는 `IllegalArgumentException: Missing @WorkflowMethod` 이며, **Worker 등록 시점**(`registerWorkflowImplementationTypes`)에 납니다. 워크플로우를 시작하기도 전에 잡힌다는 점이 중요합니다 — 이 계열의 실수는 Temporal 이 일찍 잡아 줍니다.
- **정답 2** 의 정답은 **17 이벤트**입니다. 액티비티 2개 = (`ActivityTask*` 3 + `WorkflowTask*` 3) × 2 = 12, 여기에 기본 5개를 더해 17. 주석에서 이 산식을 이벤트별로 분해해 설명합니다.
- **정답 3** 은 `WorkflowClient.start` 가 왜 메서드 참조여야 하는지 설명합니다. SDK 는 프록시 위의 메서드 호출을 **가로채서** 워크플로우 타입과 인자를 추출하는데, 람다로 감싸면 그 가로채기가 일어나는 시점이 달라져 `IllegalArgumentException: Only workflow methods can be used` 가 납니다.
- **정답 4** 는 지정하지 않은 WorkflowId 가 `8b3f1e70-...` 형태의 UUID 임을 보여 주고, 비즈니스 키의 이점 세 가지(조회 편의 / 중복 실행 방지 / 운영 중 사람이 읽을 수 있음)를 정리합니다. `WorkflowIdReusePolicy` 의 세 값은 Step 12 예고로만 언급합니다.
- **정답 6** 이 특히 중요합니다. `setStartToCloseTimeout` 누락은 **등록 시점이 아니라 워크플로우 실행 중**에 `IllegalStateException: Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified` 로 터집니다. 즉 **테스트를 안 돌려 보면 배포까지 통과합니다.** 이 코스가 반복해서 말하는 "조용히 틀리는 코드"의 첫 사례입니다.

```java file="./Solution.java"
```
