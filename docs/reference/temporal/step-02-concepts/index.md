# Step 02 — 핵심 개념과 실행 모델

> **학습 목표**
> - Workflow / Activity / Worker / Task Queue 의 관계를 그림으로 이해하고, Task Queue 가 **큐가 아니라 long-poll 매칭 지점**임을 확인한다
> - Temporal 이 "현재 상태"가 아니라 **일어난 일의 목록**만 저장한다는 사실을 히스토리로 증명한다
> - 워크플로우 실행 중 Worker 를 kill 했다가 재기동해 **리플레이가 실제로 일어나는 것**을 재현한다
> - 리플레이 때 **액티비티가 다시 호출되지 않는 것**을 액티비티 로그로 증명한다
> - Workflow Task 와 Activity Task 의 차이를 표로 구분하고, 워크플로우 코드가 만들어내는 것이 **Command** 임을 히스토리에서 짚는다
> - 이벤트 13종의 의미를 정리하고, Sticky Execution 캐시가 리플레이 비용을 어떻게 줄이는지 측정한다
> - `log.info` 가 리플레이마다 중복 출력되는 것을 재현하고 `Workflow.getLogger` 로 해결한다
>
> **선행 스텝**: Step 01 — 환경 구축과 첫 워크플로우
> **예상 소요**: 90분

---

## 2-0. 실습 준비

Step 01 의 환경을 그대로 씁니다. 서버가 떠 있는지만 확인합니다.

```bash
temporal operator namespace list
```

**결과**
```
Name                  UUID                                  State       Retention
default               a7f3b1c2-9e4d-4a8b-b1c6-3f5e7d9a2c40  Registered  72h0m0s
temporal-system       32049b68-7872-4094-8e63-d0dd59896a83  Registered  168h0m0s
```

이 스텝은 액티비티 세 개(`charge` → `reserve` → `requestShipment`)를 순차 호출하는 워크플로우로 실습합니다. 각 액티비티 사이에 5초 `Workflow.sleep` 을 넣어, **워크플로우가 도는 도중에 Worker 를 죽일 시간**을 확보합니다.

---

## 2-1. Workflow / Activity / Worker / Task Queue 의 관계

Step 01 에서 "Server 는 내 코드를 실행하지 않는다"고 했습니다. 그렇다면 실행은 어떻게 흘러갈까요. 전체 그림입니다.

```
                        ┌─────────────────────────────────────────────┐
                        │            Temporal Server                   │
                        │   ┌──────────────────────────────────────┐   │
   ① StartWorkflow      │   │  History Service                     │   │
  ─────────────────────►│   │   order-1001 의 이벤트 목록           │   │
                        │   │   [1] WorkflowExecutionStarted        │   │
   Client               │   │   [2] WorkflowTaskScheduled  ...      │   │
                        │   └──────────────┬───────────────────────┘   │
                        │   ┌──────────────▼───────────────────────┐   │
                        │   │  Matching Service                    │   │
                        │   │   Task Queue: "ORDER_TASK_QUEUE"     │   │
                        │   │   ┌────────────────┐ ┌─────────────┐ │   │
                        │   │   │ Workflow Task  │ │Activity Task│ │   │
                        │   │   └───────┬────────┘ └──────┬──────┘ │   │
                        │   └───────────┼─────────────────┼────────┘   │
                        └───────────────┼─────────────────┼────────────┘
                       ② long-poll ─────┤                 ├───── ② long-poll
                          (60초 대기)    ▼                 ▼
                  ┌──────────────────────────────────────────────────────┐
                  │                   Worker 프로세스                      │
                  │  Workflow Poller ──► workflow-method 스레드           │
                  │                        └─ OrderWorkflowImpl 실행      │
                  │                             (히스토리 리플레이 + 전진)  │
                  │  Activity Poller ──► Activity Executor 스레드         │
                  │                        └─ PaymentActivityImpl 실행    │
                  │                             (부수효과 발생 지점)        │
                  │  Sticky Cache: { order-1001 → 워크플로우 인스턴스 }     │
                  └──────────────────────────────────────────────────────┘
                       ③ RespondWorkflowTaskCompleted(Commands)
                       ③ RespondActivityTaskCompleted(result) ──► 다시 Server 로
```

### Task Queue 는 큐가 아닙니다

이름 때문에 오해를 삽니다. Kafka 토픽이나 RabbitMQ 큐처럼 "메시지가 쌓였다가 컨슈머가 가져가는 저장소"를 떠올리게 되는데, **Temporal 의 Task Queue 는 저장소가 아니라 매칭 지점**입니다.

동작은 이렇습니다.

1. Worker 가 `PollWorkflowTaskQueue` gRPC 를 호출합니다. 이 호출은 **최대 60초까지 응답하지 않고 매달려 있습니다**(long-poll).
2. 서버에 Task 가 생기면 Matching Service 가 **대기 중인 폴러 중 하나에게 즉시 전달**합니다.
3. 60초 안에 Task 가 안 생기면 빈 응답을 돌려주고, Worker 는 곧바로 다시 폴링합니다.

즉 Task 는 큐에 **쌓였다가** 소비되는 게 아니라, **폴러와 곧바로 매칭**됩니다. 이 차이가 실무에서 세 가지 결과를 낳습니다.

| 결과 | 설명 |
|---|---|
| 지연이 거의 없다 | 폴러가 이미 대기 중이므로 Task 생성 즉시 전달. Step 01 에서 이벤트 2→3 사이가 119ms 였던 이유 |
| 큐 길이를 볼 수 없다 | "몇 건이 밀렸나"를 직접 조회할 수 없습니다. 백로그는 `temporal task-queue describe` 의 `Backlog` 나 메트릭으로 간접 확인합니다 |
| 폴러가 없으면 무한 대기 | Step 01 의 함정 그대로. 쌓아 두고 기다립니다 |

폴러 상태를 다시 봅니다.

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Workflow Poller Info:
  Identity          41233@macbook
  Last Access Time  2 seconds ago
  Rate Per Second   100000

Activity Poller Info:
  Identity          41233@macbook
  Last Access Time  2 seconds ago
  Rate Per Second   100000
```

`Last Access Time 2 seconds ago` — 폴러가 계속 갱신되고 있습니다. Worker 가 60초 long-poll 을 돌고 있다는 뜻입니다.

> 💡 **Workflow Task 와 Activity Task 는 같은 큐 이름을 쓰지만 별개의 매칭 라인입니다**
> 위 출력에서 `Workflow Poller Info` 와 `Activity Poller Info` 가 따로 나오는 것이 그 증거입니다.
> 그래서 "액티비티가 워커를 다 잡아먹어서 워크플로우가 안 돈다"는 문제가 생기면
> **액티비티만 별도 Task Queue 로 분리**하는 처방이 나옵니다(Step 12).

---

## 2-2. 이벤트 소싱 — Temporal 은 "현재 상태"를 저장하지 않습니다

일반적인 배치나 상태 머신은 이렇게 만듭니다.

```sql
UPDATE orders SET status = 'PAID' WHERE order_id = 1001;
```

**현재 상태**를 저장합니다. 이전이 무엇이었는지는 사라집니다.

Temporal 은 정반대입니다. **일어난 일만 순서대로 append 합니다.** 상태 컬럼이 아예 없습니다.

실습용 워크플로우를 실행합니다.

```java
@Override
public String processOrder(OrderRequest req) {
    log.info("[{}] 워크플로우 시작", req.orderId());

    String paymentId = payment.charge(req.orderId(), req.amount());
    Workflow.sleep(Duration.ofSeconds(5));

    String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
    Workflow.sleep(Duration.ofSeconds(5));

    String shipmentId = shipping.requestShipment(req.orderId(), req.address());

    return "order-" + req.orderId() + " COMPLETED";
}
```

```bash
./gradlew runStarter --args="order-2001"
temporal workflow show -w order-2001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T10:04:12Z  WorkflowExecutionStarted
   2  2026-03-11T10:04:12Z  WorkflowTaskScheduled
   3  2026-03-11T10:04:12Z  WorkflowTaskStarted
   4  2026-03-11T10:04:12Z  WorkflowTaskCompleted
   5  2026-03-11T10:04:12Z  ActivityTaskScheduled        (charge)
   6  2026-03-11T10:04:12Z  ActivityTaskStarted
   7  2026-03-11T10:04:12Z  ActivityTaskCompleted
   8- 10                    WorkflowTaskScheduled/Started/Completed
  11  2026-03-11T10:04:12Z  TimerStarted                 (5s)
  12  2026-03-11T10:04:17Z  TimerFired
  13- 15                    WorkflowTaskScheduled/Started/Completed
  16  2026-03-11T10:04:17Z  ActivityTaskScheduled        (reserve)
  17  2026-03-11T10:04:17Z  ActivityTaskStarted
  18  2026-03-11T10:04:17Z  ActivityTaskCompleted
  19- 21                    WorkflowTaskScheduled/Started/Completed
  22  2026-03-11T10:04:17Z  TimerStarted                 (5s)
  23  2026-03-11T10:04:22Z  TimerFired
  24- 26                    WorkflowTaskScheduled/Started/Completed
  27  2026-03-11T10:04:22Z  ActivityTaskScheduled        (requestShipment)
  28  2026-03-11T10:04:22Z  ActivityTaskStarted
  29  2026-03-11T10:04:22Z  ActivityTaskCompleted
  30- 32                    WorkflowTaskScheduled/Started/Completed
  33  2026-03-11T10:04:22Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-2001 COMPLETED"]
```

**33개 이벤트.** 이 목록 어디에도 `status = PAID` 같은 것은 없습니다. 그런데도 이 목록만 있으면 **워크플로우의 현재 상태를 완전히 복원할 수 있습니다.**

- 이벤트 7 이 있으니 → 결제는 끝났고 결과는 `pay-2001`
- 이벤트 12 가 있으니 → 첫 5초 대기는 끝났고
- 이벤트 18 이 있으니 → 재고 예약도 끝났고 결과는 `resv-2001`
- 이벤트 29 가 있으니 → 배송 요청도 끝났고
- 이벤트 33 이 있으니 → 워크플로우 종료

`paymentId` 라는 **지역 변수의 값**조차 히스토리에서 복원됩니다. 이벤트 7 의 `result` 가 그 값이기 때문입니다.

```bash
temporal workflow show -w order-2001 --output json | jq '.events[6].activityTaskCompletedEventAttributes.result'
```

**결과**
```json
[ "pay-2001" ]
```

> 💡 **비유 — 은행 통장**
> 통장에는 "잔액 380,000원"만 적혀 있지 않습니다. 입금·출금 내역이 순서대로 적혀 있고,
> 잔액은 그것을 **다 더한 결과**입니다. 내역이 있으면 잔액은 언제든 다시 계산할 수 있지만,
> 잔액만 있으면 "왜 이 금액인지"를 영영 알 수 없습니다.
> Temporal 의 히스토리는 통장 내역이고, 워크플로우의 지역 변수들은 그 내역을 다 더한 잔액입니다.

이 설계가 주는 것이 바로 다음 절의 리플레이입니다.

---

## 2-3. 리플레이 — Worker 를 죽였다 살려 봅니다 ★

여기가 Temporal 의 심장입니다. 말로 설명하는 대신 직접 재현합니다.

### 실험 절차

1. Worker 를 띄운다
2. 워크플로우를 시작한다 (액티비티 3개 + 5초 sleep 2개 = 약 10초 소요)
3. **첫 액티비티가 끝나고 5초 sleep 중일 때 Worker 를 `kill -9`**
4. Worker 를 다시 띄운다
5. 워크플로우가 이어서 진행되는지, 그리고 **결제 액티비티가 다시 호출되는지** 확인한다

### 실행

**[터미널 1]** `./gradlew runWorker` 로 Worker 를 띄우고, **[터미널 2]** 에서 `./gradlew runStarter --args="order-2002"` 로 워크플로우를 시작합니다.

**[터미널 1] Worker 콘솔**
```
10:11:15.108 [workflow-method-order-2002-7b31a4] INFO  c.e.order.OrderWorkflowImpl - [2002] 워크플로우 시작
10:11:15.219 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.o.PaymentActivityImpl - [2002] 결제 요청 amount=39000  ★
10:11:15.334 [workflow-method-order-2002-7b31a4] INFO  c.e.order.OrderWorkflowImpl - [2002] 결제 완료 paymentId=pay-2002
```

★ 표시한 줄이 **실제 결제가 일어난 시점**입니다. 이제 5초 sleep 중입니다. **지금 Worker 를 죽입니다.**

```bash
kill -9 $(pgrep -f OrderWorker)
```

프로세스가 즉시 사라집니다. `[1] Killed` 외에 아무 로그도 없고 graceful shutdown 조차 없습니다. 그 상태에서 히스토리를 봅니다.

```bash
temporal workflow show -w order-2002
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T10:11:15Z  WorkflowExecutionStarted
   2- 4                     WorkflowTaskScheduled/Started/Completed
   5  2026-03-11T10:11:15Z  ActivityTaskScheduled        (charge)
   6  2026-03-11T10:11:15Z  ActivityTaskStarted
   7  2026-03-11T10:11:15Z  ActivityTaskCompleted        result=["pay-2002"]
   8-10                     WorkflowTaskScheduled/Started/Completed
  11  2026-03-11T10:11:15Z  TimerStarted                 (5s)
  12  2026-03-11T10:11:20Z  TimerFired
  13  2026-03-11T10:11:20Z  WorkflowTaskScheduled        ← 여기서 멈춤

Status: RUNNING
```

**13개에서 멈춰 있습니다.** 타이머는 만료됐고(12), 그래서 서버가 "코드를 이어서 돌려 주세요" 하고 Workflow Task 를 얹었는데(13), **가져갈 Worker 가 없습니다.** Step 01 의 함정과 정확히 같은 상태입니다 — 다만 이번엔 일시적입니다.

`./gradlew runWorker` 로 **Worker 를 다시 띄웁니다.**

**[터미널 1] Worker 콘솔 — 재기동 직후**
```
10:12:41.402 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", identity=42188@macbook}
10:12:41.688 [workflow-method-order-2002-c19d02] INFO  c.e.order.OrderWorkflowImpl - [2002] 재고 예약 완료 resv-2002
10:12:46.912 [workflow-method-order-2002-c19d02] INFO  c.e.order.OrderWorkflowImpl - [2002] 배송 요청 완료 ship-2002
```

**[터미널 2] Client** 는 87초 매달려 있다가 `결과: order-2002 COMPLETED` 를 받습니다.

### 여기서 무슨 일이 벌어졌나

Worker 가 재기동되며 `order-2002` 의 Workflow Task 를 가져왔습니다. 그런데 이 Worker 는 방금 태어났으므로 **`paymentId` 변수에 무엇이 들어 있었는지 전혀 모릅니다.** 메모리에 아무것도 없습니다.

그래서 SDK 는 이렇게 합니다.

```
① 서버에서 order-2002 의 히스토리 전체(13개 이벤트)를 받아온다
② OrderWorkflowImpl.processOrder() 를 "처음부터" 다시 실행한다
③ 코드가 payment.charge(...) 에 도달하면
     → 실제로 액티비티를 호출하지 않는다
     → 히스토리의 이벤트 7 (ActivityTaskCompleted, result="pay-2002") 을 찾아 그 값을 그대로 반환한다
④ 코드가 Workflow.sleep(5초) 에 도달하면
     → 실제로 5초를 자지 않는다
     → 히스토리의 이벤트 12 (TimerFired) 를 보고 "이미 만료됨"으로 처리하고 즉시 통과한다
⑤ 코드가 inventory.reserve(...) 에 도달한다
     → 히스토리에 대응하는 이벤트가 없다 → "여기가 새 지점이다"
     → 여기서부터 진짜로 실행한다
```

②~④를 **리플레이**, ⑤부터를 **신규 실행**이라고 합니다.

### 액티비티 로그가 다시 찍히지 않는다는 증거

재기동 후 Worker 콘솔을 다시 보세요.

```
10:12:41.688 [workflow-method-order-2002-c19d02] INFO  c.e.order.OrderWorkflowImpl - [2002] 재고 예약 완료 resv-2002
10:12:46.912 [workflow-method-order-2002-c19d02] INFO  c.e.order.OrderWorkflowImpl - [2002] 배송 요청 완료 ship-2002
```

**`PaymentActivityImpl` 의 로그가 한 줄도 없습니다.** 첫 기동 때 있었던

```
[Activity Executor ...] INFO  c.e.o.PaymentActivityImpl - [2002] 결제 요청 amount=39000
```

이 줄이 재기동 후에는 **찍히지 않았습니다.** 워크플로우 코드는 `payment.charge(...)` 줄을 분명히 다시 지나갔는데도 말입니다.

**결제는 정확히 한 번만 일어났습니다.** 히스토리로도 확인됩니다.

```bash
temporal workflow show -w order-2002 | grep -c "ActivityTaskStarted"   # → 3
temporal workflow describe -w order-2002
```

액티비티 3개(charge / reserve / requestShipment) 각각 1번씩입니다. `charge` 가 두 번 실행됐다면 4가 나왔을 것입니다.

**결과**
```
Execution Info:
  Workflow Id       order-2002
  Run Id            b47e2f91-8d05-4c63-a1f7-3e9b0d5c8271
  Type              OrderWorkflow
  Task Queue        ORDER_TASK_QUEUE
  Status            COMPLETED
  History Length    33
  History Size      5127
```

**History Length 33** — Worker 를 죽이지 않고 돌린 `order-2001` 과 **정확히 같은 33개**입니다. Worker 가 한 번 죽었다는 사실은 히스토리에 흔적조차 남기지 않았습니다. 걸린 시간만 10초 → 91초로 늘었을 뿐입니다.

> 💡 **리플레이가 요구하는 것 — 결정성(determinism)**
> 리플레이는 "같은 코드에 같은 히스토리를 먹이면 같은 순서의 Command 가 나온다"는 전제 위에 서 있습니다.
> 그래서 워크플로우 코드에 `new Random()`, `System.currentTimeMillis()`, `UUID.randomUUID()`,
> `Thread.sleep()`, HTTP 호출 같은 것을 쓰면 리플레이가 깨집니다.
> 이 제약과 우회 API(`Workflow.currentTimeMillis()`, `Workflow.randomUUID()`, `Workflow.sideEffect()`)는
> **Step 03 의 주제 전체**입니다.

---

## 2-4. Workflow Task vs Activity Task

같은 "Task" 라는 이름이 붙어 있지만 완전히 다른 것입니다.

| 항목 | Workflow Task | Activity Task |
|---|---|---|
| 무엇을 실행하나 | 워크플로우 코드 (`OrderWorkflowImpl`) | 액티비티 코드 (`PaymentActivityImpl`) |
| 실행 스레드 | `workflow-method-...` | `Activity Executor ...` |
| 하는 일 | 히스토리를 리플레이해 **다음 대기 지점까지 코드를 밀어붙이고**, 그 사이 발생한 **Command 목록**을 반환 | 실제 부수효과(HTTP 호출, DB 쓰기)를 일으키고 결과를 반환 |
| 리플레이되나 | **된다** (여러 번 실행됨) | **안 된다** (정확히 한 번만) |
| 기본 타임아웃 | `workflowTaskTimeout` 10초 | 기본값 없음 — **`startToCloseTimeout` 직접 지정 필수** |
| 재시도 방식 | 실패 시 **서버가 무한 재시도**. 백오프하며 영원히 시도 | `RetryOptions` 정책대로 (기본: 지수 백오프, 무한) |
| 실패의 의미 | "코드에 버그가 있거나 배포 중" → 고치면 이어서 진행 | "외부 시스템이 불안정" → 재시도로 극복 |
| 코드 제약 | **있다.** 결정적이어야 함. 랜덤·현재시각·IO 금지 | **없다.** 평범한 Java 코드. 뭘 해도 됨 |
| 결과 크기 제한 | Command 목록. 페이로드 2MB | 반환값 2MB |

### "다음 대기 지점까지 밀어붙인다"의 의미

Workflow Task 하나가 처리하는 범위를 그림으로 보면 이렇습니다.

```
processOrder() {
    log.info("시작");                      ─┐
    payment.charge(...);                    ├─ Workflow Task #1 이 여기까지 실행
}                                          ─┘   → Command: [ScheduleActivityTask(charge)]
                                                → 여기서 코드가 "멈춘다"(코루틴 블록)

  ... 액티비티 실행 (Activity Task) ...

processOrder() {                           ─┐
    log.info("시작");          ← 리플레이     │
    payment.charge(...);       ← 히스토리값   ├─ Workflow Task #2 : 처음부터 다시 실행
    Workflow.sleep(5초);                     │   → Command: [StartTimer(5s)]
}                                          ─┘   → 다시 멈춘다

  ... 5초 경과 (TimerFired) ...

processOrder() {                           ─┐
    log.info("시작");          ← 리플레이     │
    payment.charge(...);       ← 히스토리값   ├─ Workflow Task #3
    Workflow.sleep(5초);       ← 히스토리값   │   → Command: [ScheduleActivityTask(reserve)]
    inventory.reserve(...);                 │
}                                          ─┘
```

**Workflow Task 가 돌 때마다 워크플로우 코드는 처음부터 다시 실행됩니다.** `order-2001` 의 경우 Workflow Task 가 8번 돌았으므로 `processOrder()` 는 **8번 호출됐습니다.** 그런데 액티비티는 각각 1번씩만 실행됐습니다. 이 비대칭이 Temporal 의 전부라고 해도 과언이 아닙니다.

> ⚠️ 위 그림에서 "멈춘다"는 것은 스레드가 블로킹된다는 뜻이 **아닙니다.**
> Temporal Java SDK 는 워크플로우 코드를 **코루틴**(deterministic scheduler 위의 협조적 스레드)으로 돌립니다.
> `payment.charge(...)` 는 코루틴을 yield 시키고, Workflow Task 는 그 지점에서 "더 진행할 게 없음"을 감지해 Command 목록을 반환하고 종료합니다.

---

## 2-5. Command — 워크플로우 코드가 만들어내는 것

Step 01 에서 "`payment.charge(...)` 를 호출해도 실제 실행이 아니라 Command 가 만들어진다"고 했습니다. 이제 그것을 히스토리에서 직접 봅니다.

워크플로우 코드는 **부수효과를 일으키지 않습니다.** 대신 "이걸 해 주세요"라는 **요청 목록(Command)** 을 만들어 서버에 반환합니다.

| 코드 | 만들어지는 Command | 서버가 기록하는 Event |
|---|---|---|
| `payment.charge(...)` | `ScheduleActivityTask` | `ActivityTaskScheduled` |
| `Workflow.sleep(5s)` | `StartTimer` | `TimerStarted` |
| `return "..."` | `CompleteWorkflowExecution` | `WorkflowExecutionCompleted` |
| `throw ApplicationFailure` | `FailWorkflowExecution` | `WorkflowExecutionFailed` |
| `Workflow.newChildWorkflowStub(...)` 호출 | `StartChildWorkflowExecution` | `StartChildWorkflowExecutionInitiated` |
| `Workflow.continueAsNew(...)` | `ContinueAsNewWorkflowExecution` | `WorkflowExecutionContinuedAsNew` |
| `Async.function(...)` 취소 | `RequestCancelActivityTask` | `ActivityTaskCancelRequested` |

**Command 는 Worker → Server 방향, Event 는 Server 가 기록하는 결과**입니다. Command 는 히스토리에 직접 나타나지 않고, 서버가 그것을 Event 로 변환해 append 합니다.

### 히스토리에서 확인하기

규칙은 단순합니다. **`WorkflowTaskCompleted` 바로 다음에 오는 이벤트들이, 그 Workflow Task 가 반환한 Command 의 결과입니다.**

`order-2001` 의 히스토리를 다시 봅니다.

```
   4  WorkflowTaskCompleted  → Command = ScheduleActivityTask(charge)
   5  ActivityTaskScheduled  ← 그 결과
  10  WorkflowTaskCompleted  → Command = StartTimer(5s)
  11  TimerStarted           ← 그 결과
  15  WorkflowTaskCompleted  → Command = ScheduleActivityTask(reserve)
  16  ActivityTaskScheduled
  21  WorkflowTaskCompleted  → Command = StartTimer(5s)
  22  TimerStarted
  26  WorkflowTaskCompleted  → Command = ScheduleActivityTask(requestShipment)
  27  ActivityTaskScheduled
  32  WorkflowTaskCompleted  → Command = CompleteWorkflowExecution
  33  WorkflowExecutionCompleted
```

`WorkflowTaskCompleted` 이벤트의 상세는 이렇습니다.

```bash
temporal workflow show -w order-2001 --output json | jq '.events[3].workflowTaskCompletedEventAttributes'
```

**결과**
```json
{
  "scheduledEventId": "2",
  "startedEventId": "3",
  "identity": "42011@macbook",
  "binaryChecksum": "@temporal_sdk_java_1.22.3"
}
```

`binaryChecksum` 이 보입니다. **어느 버전의 Worker 가 이 Workflow Task 를 처리했는지**가 히스토리에 남습니다. Step 10 의 버저닝에서 이것이 결정적으로 쓰입니다.

Command 를 여러 개 한꺼번에 반환할 수도 있습니다. 액티비티 3개를 `Async.function` 으로 병렬 호출하면:

```
   4  WorkflowTaskCompleted        ← Workflow Task 하나가
   5  ActivityTaskScheduled        ← Command 3개를
   6  ActivityTaskScheduled        ← 한 번에
   7  ActivityTaskScheduled        ← 반환했다
```

이러면 Workflow Task 횟수가 줄어 히스토리도 작아지고 리플레이 비용도 줄어듭니다. 순차 호출 대비 Workflow Task 가 **4회 → 2회**로 줄어듭니다(Step 04).

---

## 2-6. 히스토리 읽는 법 — 이벤트 13종

실무에서 마주치는 이벤트는 사실상 아래 13종입니다. 이것만 읽을 줄 알면 대부분의 장애를 진단할 수 있습니다.

| 이벤트 | 의미 | 이걸 보면 알 수 있는 것 |
|---|---|---|
| `WorkflowExecutionStarted` | 워크플로우가 시작됨. **항상 이벤트 1** | 입력값 전체, task queue, 각종 타임아웃, 부모 워크플로우 |
| `WorkflowTaskScheduled` | 서버가 "코드를 돌려 달라"고 Task 를 얹음 | 여기서 멈춰 있으면 → **Worker 가 없다** |
| `WorkflowTaskStarted` | Worker 가 그 Task 를 가져감 | `identity` 로 어느 Worker 인스턴스인지 |
| `WorkflowTaskCompleted` | Worker 가 Command 목록을 반환함 | `binaryChecksum` 으로 Worker 버전 |
| `WorkflowTaskFailed` | 워크플로우 코드가 예외를 던짐 | **결정성 위반이나 버그.** 서버가 무한 재시도 |
| `WorkflowTaskTimedOut` | Worker 가 10초 안에 응답 못 함 | 워크플로우 코드에서 블로킹 IO 를 했을 가능성 |
| `ActivityTaskScheduled` | 액티비티 실행 요청이 큐에 얹힘 | 액티비티 타입, 입력값, 재시도 정책, 타임아웃 |
| `ActivityTaskStarted` | Worker 가 액티비티를 가져감 | `attempt` 로 몇 번째 시도인지 |
| `ActivityTaskCompleted` | 액티비티 성공 | **반환값**. 리플레이 때 이 값이 재사용됨 |
| `ActivityTaskFailed` | 액티비티가 예외를 던짐 | 실패 원인, 스택 트레이스. 재시도 정책에 따라 재스케줄 |
| `TimerStarted` | `Workflow.sleep` 등으로 타이머 시작 | 타이머 ID, 만료 시각 |
| `TimerFired` | 타이머 만료 | 리플레이 때 "이미 지났음"으로 처리됨 |
| `WorkflowExecutionSignaled` | 외부에서 Signal 이 들어옴 | Signal 이름, 페이로드 (Step 07) |
| `WorkflowExecutionCompleted` | 정상 종료 | 최종 반환값 |
| `WorkflowExecutionContinuedAsNew` | 히스토리를 리셋하고 새 Run 으로 이어감 | 새 Run 의 입력값 (Step 08) |

### 읽는 요령 세 가지

**① `WorkflowTask*` 3종 세트는 "구분선"으로 읽습니다.**
`Scheduled → Started → Completed` 가 한 세트이고, 그 뒤에 나오는 것이 워크플로우 코드가 낸 결정입니다. 히스토리를 볼 때 이 3종 세트를 기준으로 블록을 나누면 훨씬 잘 읽힙니다.

**② `scheduledEventId` 로 짝을 찾습니다.**
`ActivityTaskCompleted` 만 봐서는 어떤 액티비티인지 모릅니다. `scheduledEventId` 를 따라가면 대응하는 `ActivityTaskScheduled` 가 나오고, 거기 액티비티 이름과 입력값이 있습니다.

```bash
temporal workflow show -w order-2001 --output json | jq '.events[4].activityTaskScheduledEventAttributes'
```

**결과**
```json
{
  "activityId": "5",
  "activityType": { "name": "charge" },
  "taskQueue": { "name": "ORDER_TASK_QUEUE", "kind": "Normal" },
  "input": [ "2001", 39000 ],
  "startToCloseTimeout": "10s",
  "retryPolicy": { "initialInterval": "1s", "backoffCoefficient": 1.0,
                   "maximumInterval": "100s", "maximumAttempts": 0 }
}
```

`maximumAttempts: 0` 은 **무한 재시도**입니다. Temporal 액티비티의 기본값입니다 — Step 05 에서 다룹니다.

**③ 마지막 이벤트가 `WorkflowTaskScheduled` 면 무조건 "Worker 문제" 입니다.**
`order-2002` 를 kill 했을 때 마지막이 13번 `WorkflowTaskScheduled` 였던 것을 기억하세요. 장애 진단의 첫 단계는 항상 "마지막 이벤트가 무엇인가"입니다.

---

## 2-7. Sticky Execution 과 캐시

2-4 에서 "Workflow Task 마다 코드를 처음부터 다시 실행한다"고 했습니다. 그대로라면 히스토리가 1만 이벤트로 커졌을 때 Workflow Task 한 번마다 1만 이벤트를 리플레이해야 합니다. 감당이 안 됩니다.

그래서 Temporal 은 **Sticky Execution** 이라는 최적화를 씁니다.

```
[첫 Workflow Task]
   Worker → 서버: "order-2001 처리했습니다. 그리고 제 전용 큐는 <sticky-42011-a7f3> 입니다"
   Worker: 워크플로우 인스턴스를 메모리 캐시에 보관
              { "order-2001" → OrderWorkflowImpl 인스턴스 (코루틴 상태 포함) }

[다음 Workflow Task]
   서버 → <sticky-42011-a7f3> 큐로 전달 (그 Worker 에게만)
   Worker: 캐시에 있네! → 리플레이 없이 "마지막 지점부터 이어서" 실행
              → 히스토리는 "새로 추가된 이벤트만" 받아온다

[캐시 미스 — Worker 재기동 / LRU 축출 / sticky 큐 타임아웃]
   서버 → 원래 ORDER_TASK_QUEUE 로 전달
   Worker: 캐시에 없다 → 히스토리 전체를 받아 full replay
```

즉 **정상 경로에서는 리플레이가 거의 일어나지 않습니다.** 리플레이는 예외 경로(재기동·캐시 축출·워커 교체)에서만 발생합니다.

### 측정

히스토리가 큰 워크플로우로 비교합니다. 액티비티를 200번 순차 호출하는 워크플로우(약 1,205 이벤트)를 만들어 두 조건에서 잽니다.

**캐시 히트 (정상)**
```
14:22:08.114 [workflow-method-order-2010-...] DEBUG i.t.i.r.ReplayWorkflowTaskHandler - Cache hit for order-2010, incremental history: 3 events
14:22:08.126 [workflow-method-order-2010-...] DEBUG i.t.i.r.ReplayWorkflowTaskHandler - WorkflowTask completed in 12ms
```

**캐시 미스 (Worker 재기동 직후)**
```
14:25:31.402 [workflow-method-order-2010-...] DEBUG i.t.i.r.ReplayWorkflowTaskHandler - Cache miss for order-2010, full history: 1205 events
14:25:34.618 [workflow-method-order-2010-...] DEBUG i.t.i.r.ReplayWorkflowTaskHandler - WorkflowTask completed in 3216ms
```

**Workflow Task 처리 시간 12ms → 3,216ms. 268배 차이.** 리플레이해야 할 이벤트가 3개에서 1,205개로 늘었기 때문입니다.

캐시 설정은 `WorkerFactoryOptions` 로 조절합니다.

```java
WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
        .setWorkflowCacheSize(600)                  // 캐시할 워크플로우 인스턴스 수 (기본 600)
        .setMaxWorkflowThreadCount(600)             // 워크플로우 코루틴 스레드 수 (기본 600)
        .build();
WorkerFactory factory = WorkerFactory.newInstance(client, factoryOptions);
```

> 💡 **실무 팁 — 캐시 크기와 스레드 수는 함께 움직입니다**
> 캐시된 워크플로우 인스턴스는 각각 **살아 있는 코루틴 스레드**를 붙들고 있습니다.
> 그래서 `workflowCacheSize` 를 늘리면 `maxWorkflowThreadCount` 도 함께 늘려야 합니다. 안 그러면
> `Timeout expired while waiting for a free workflow thread` 로 Workflow Task 가 타임아웃합니다.
> 반대로 캐시를 너무 크게 잡으면 메모리를 먹습니다. 히스토리가 큰 워크플로우가 많다면
> **Continue-As-New 로 히스토리 자체를 잘라 내는 것**(Step 08)이 근본 해법입니다.
> 히스토리 이벤트 20,412개 → 8개로 줄이면 Workflow Task 처리 시간이 3.2초 → 12ms 가 됩니다.

캐시 상태는 메트릭(`temporal_sticky_cache_hit` / `_miss` / `_total_forced_eviction`)으로 관찰합니다. `miss` 와 `forced_eviction` 이 계속 오르면 캐시가 부족하다는 신호입니다.

---

## 2-8. 함정 (1) — "결제가 두 번 됐다"는 오해

리플레이를 모르면 반드시 이 오해에 빠집니다. 로그가 이렇게 보이기 때문입니다.

Worker 를 세 번 재기동하면서 워크플로우를 돌린 뒤, 워크플로우 로그만 뽑아 봅니다.

```
10:11:15.108 [workflow-method-order-2003-...] INFO  c.e.order.OrderWorkflowImpl - [2003] 결제 시작 amount=39000
10:12:41.688 [workflow-method-order-2003-...] INFO  c.e.order.OrderWorkflowImpl - [2003] 결제 시작 amount=39000
10:13:52.201 [workflow-method-order-2003-...] INFO  c.e.order.OrderWorkflowImpl - [2003] 결제 시작 amount=39000
```

**"결제 시작" 이 세 번 찍혔습니다.** 로그만 보면 39,000원이 세 번 빠져나간 것처럼 보입니다. 결제팀에서 문의가 오고, 장애 대응이 시작됩니다.

**하지만 결제는 한 번만 일어났습니다.** 증명합니다.

**증거 ① 액티비티 로그는 한 번뿐입니다.**
```
10:11:15.219 [Activity Executor taskQueue="ORDER_TASK_QUEUE": 1] INFO  c.e.o.PaymentActivityImpl - [2003] 결제 요청 amount=39000
```
같은 시간대 로그 전체를 뒤져도 `PaymentActivityImpl` 로그는 이 한 줄입니다.

**증거 ② 히스토리에 스케줄된 액티비티가 각각 하나뿐입니다.**
```bash
temporal workflow show -w order-2003 --output json \
  | jq '[.events[] | select(.eventType=="ActivityTaskScheduled")
         | .activityTaskScheduledEventAttributes.activityType.name]'
```

**결과**
```json
[ "charge", "reserve", "requestShipment" ]
```

**증거 ③ 액티비티 시도 횟수가 전부 1 입니다.**
```bash
temporal workflow show -w order-2003 --output json \
  | jq '[.events[] | select(.eventType=="ActivityTaskStarted")
         | .activityTaskStartedEventAttributes.attempt]'
```

**결과**
```json
[ 1, 1, 1 ]
```

재실행이 없었습니다. `attempt` 가 2 이상이면 그것은 리플레이가 아니라 **재시도**이며, 완전히 다른 일입니다.

> ⚠️ **함정 — 워크플로우 로그의 반복은 "코드가 다시 돌았다"이지 "부수효과가 다시 일어났다"가 아닙니다**
> 리플레이 중 `payment.charge(...)` 줄은 분명히 다시 지나갑니다. 하지만 그 호출은
> **액티비티를 실행하지 않고 히스토리의 이벤트 7 에서 결과를 꺼내 반환**합니다.
> 부수효과(PG 사 HTTP 호출)는 `PaymentActivityImpl.charge` 안에 있고, 그 메서드는 재실행되지 않습니다.
>
> **이것이 워크플로우 코드와 액티비티 코드를 반드시 분리해야 하는 이유입니다.**
> 워크플로우 코드 안에 직접 HTTP 호출을 넣으면 — 결정성 위반이기도 하지만 — **리플레이할 때마다 진짜로 호출됩니다.**
> 세 번 재기동했다면 진짜로 세 번 결제됩니다.
>
> **판단 기준**: "이 줄이 100번 반복 실행돼도 괜찮은가?" 를 물어보세요.
> 안 괜찮다면 그것은 액티비티로 빼야 합니다.

---

## 2-9. 함정 (2) — `log.info` 는 리플레이마다 다시 찍힙니다

2-8 의 로그 중복은 사실 **고칠 수 있습니다.** 위 예제의 워크플로우가 이렇게 돼 있었다고 해 봅시다.

```java
public class OrderWorkflowImpl implements OrderWorkflow {

    // ❌ 잘못된 로거
    private static final Logger log = LoggerFactory.getLogger(OrderWorkflowImpl.class);

    @Override
    public String processOrder(OrderRequest req) {
        log.info("[{}] 결제 시작 amount={}", req.orderId(), req.amount());
        String paymentId = payment.charge(req.orderId(), req.amount());
        log.info("[{}] 결제 완료 {}", req.orderId(), paymentId);
        Workflow.sleep(Duration.ofSeconds(5));
        log.info("[{}] 재고 예약 시작", req.orderId());
        String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());
        log.info("[{}] 재고 예약 완료 {}", req.orderId(), reservationId);
        return "order-" + req.orderId() + " COMPLETED";
    }
}
```

Worker 를 두 번 재기동하며 실행한 **콘솔 출력 전문**입니다.

```
--- Worker 1회차 기동 ---
10:20:03.101 [workflow-method-order-2004-1a] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 시작 amount=39000
10:20:03.244 [Activity Executor ...: 1]     INFO  c.e.o.PaymentActivityImpl    - [2004] 결제 요청 amount=39000
10:20:03.388 [workflow-method-order-2004-1a] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 시작 amount=39000     ← 중복 (1회 리플레이)
10:20:03.389 [workflow-method-order-2004-1a] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 완료 pay-2004
--- kill -9 ---

--- Worker 2회차 기동 ---
10:21:47.612 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 시작 amount=39000     ← 중복 (2회)
10:21:47.613 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 완료 pay-2004         ← 중복
10:21:47.614 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 재고 예약 시작
10:21:47.760 [Activity Executor ...: 1]     INFO  c.e.o.InventoryActivityImpl  - [2004] 재고 예약 sku=SKU-BLACK-TEE
10:21:47.902 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 시작 amount=39000     ← 중복 (3회)
10:21:47.903 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 결제 완료 pay-2004         ← 중복
10:21:47.904 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 재고 예약 시작              ← 중복
10:21:47.905 [workflow-method-order-2004-2b] INFO  c.e.order.OrderWorkflowImpl - [2004] 재고 예약 완료 resv-2004
```

**"결제 시작" 이 4번, "결제 완료" 가 3번, "재고 예약 시작" 이 2번** 찍혔습니다. 실제로는 각각 한 번씩 일어난 일입니다.

로거를 바꿉니다. 딱 한 줄입니다.

```java
// ✅ 올바른 로거
private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);
```

같은 시나리오를 다시 돌린 **콘솔 출력 전문**입니다.

```
--- Worker 1회차 기동 ---
10:24:11.101 [workflow-method-order-2005-3c] INFO  c.e.order.OrderWorkflowImpl - [2005] 결제 시작 amount=39000
10:24:11.244 [Activity Executor ...: 1]     INFO  c.e.o.PaymentActivityImpl    - [2005] 결제 요청 amount=39000
10:24:11.389 [workflow-method-order-2005-3c] INFO  c.e.order.OrderWorkflowImpl - [2005] 결제 완료 pay-2005
--- kill -9 ---

--- Worker 2회차 기동 ---
10:25:52.614 [workflow-method-order-2005-4d] INFO  c.e.order.OrderWorkflowImpl - [2005] 재고 예약 시작
10:25:52.760 [Activity Executor ...: 1]     INFO  c.e.o.InventoryActivityImpl  - [2005] 재고 예약 sku=SKU-BLACK-TEE
10:25:52.905 [workflow-method-order-2005-4d] INFO  c.e.order.OrderWorkflowImpl - [2005] 재고 예약 완료 resv-2005
```

**중복이 완전히 사라졌습니다.** 로그 8줄 → 4줄(워크플로우 로그 기준). 각 사건이 정확히 한 번씩만 기록됩니다.

`Workflow.getLogger` 가 돌려주는 것은 `ReplayAwareLogger` 라는 래퍼입니다. 내부적으로 `Workflow.isReplaying()` 을 확인해, **리플레이 중이면 로그를 버립니다.**

```java
// SDK 내부 동작을 흉내 내면 이렇습니다
if (!Workflow.isReplaying()) {
    delegate.info(message, args);
}
```

> ⚠️ **함정 — 워크플로우 코드의 로그·메트릭·알림은 전부 이 문제를 갖습니다**
> 로거만이 아닙니다. 워크플로우 코드 안에서 하는 **모든 관찰 가능성 작업**이 리플레이마다 반복됩니다.
> - `meterRegistry.counter("order.paid").increment()` → 카운터가 뻥튀기됩니다
> - `slackClient.send("주문 완료")` → 슬랙 알림이 여러 번 갑니다 (게다가 결정성 위반이기도 합니다)
> - `System.out.println(...)` → 그대로 중복 출력됩니다
>
> **처방 두 가지:**
> 1. 로그는 `Workflow.getLogger(...)` 를 씁니다. **워크플로우 클래스에서 `LoggerFactory.getLogger` 를 쓰지 않는다**를 팀 규칙으로 만드세요. ArchUnit 이나 Checkstyle 로 강제할 수 있습니다.
> 2. 로그 외의 부수효과(메트릭·알림·외부 호출)는 **전부 액티비티로 뺍니다.** 액티비티는 리플레이되지 않으므로 정확히 한 번 실행됩니다.
>
> 예외적으로 워크플로우 코드 안에서 직접 처리해야 한다면 `Workflow.isReplaying()` 으로 직접 가드합니다.
> ```java
> if (!Workflow.isReplaying()) {
>     meterRegistry.counter("order.started").increment();
> }
> ```
> 다만 이 방식은 캐시 미스 여부에 따라 실행 횟수가 달라질 수 있어 **정확한 카운트를 보장하지 않습니다.**
> 정확도가 필요하면 반드시 액티비티로 빼세요.

반대로 **액티비티 코드에서는 `Workflow.getLogger` 를 쓰면 안 됩니다.** 액티비티는 워크플로우 컨텍스트 밖에서 실행되므로 예외가 납니다.

```java
public class PaymentActivityImpl implements PaymentActivity {
    // ✅ 액티비티는 평범한 로거
    private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);
}
```

| 어디서 | 어떤 로거 | 이유 |
|---|---|---|
| 워크플로우 구현체 | `Workflow.getLogger(...)` | 리플레이 중 출력 억제 |
| 액티비티 구현체 | `LoggerFactory.getLogger(...)` | 리플레이 안 됨. 평범한 코드 |
| Worker / Client `main` | `LoggerFactory.getLogger(...)` | 워크플로우 컨텍스트 밖 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| Task Queue | 큐가 아니라 **long-poll 매칭 지점**. 저장하지 않고 폴러에 즉시 전달 |
| Workflow / Activity 폴러 | 같은 큐 이름이지만 **별개의 매칭 라인**. 분리 배치 가능 |
| 이벤트 소싱 | "현재 상태"를 저장하지 않음. **일어난 일의 목록만** append |
| 상태 복원 | 지역 변수 값까지 히스토리에서 복원 가능(`ActivityTaskCompleted.result`) |
| 리플레이 | Worker 재기동 시 코드를 **처음부터 다시 실행**. 히스토리에 결과가 있는 액티비티는 **재호출하지 않음** |
| 리플레이 증거 | 재기동 후 `PaymentActivityImpl` 로그가 **안 찍힘**. `ActivityTaskStarted` 도 1회뿐 |
| Workflow Task | 코드를 다음 대기 지점까지 밀고 **Command 목록**을 반환. 리플레이됨. 코드 제약 있음 |
| Activity Task | 실제 부수효과. **정확히 한 번**. 코드 제약 없음. 타임아웃 지정 필수 |
| Command → Event | Command 는 Worker→Server, Event 는 서버 기록. **`WorkflowTaskCompleted` 다음 이벤트들이 그 Command 의 결과** |
| 히스토리 읽기 | `WorkflowTask*` 3종을 구분선으로. `scheduledEventId` 로 짝 찾기 |
| 진단 1번 규칙 | 마지막 이벤트가 `WorkflowTaskScheduled` 면 **Worker 문제** |
| Sticky Execution | 워크플로우 인스턴스를 메모리 캐시 → 리플레이 생략. 미스 시 full replay |
| 캐시 미스 비용 | 1,205 이벤트 full replay = **12ms → 3,216ms (268배)** |
| 함정 ① | 워크플로우 로그 반복은 "코드 재실행"이지 "부수효과 재발생"이 아님 |
| 함정 ② | `LoggerFactory` 로거는 리플레이마다 재출력. **`Workflow.getLogger`** 를 쓸 것 |
| 판단 기준 | **"이 줄이 100번 반복돼도 괜찮은가?"** 아니면 액티비티로 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **직접 Worker 를 kill 하고 히스토리를 확인**하세요.

1. 액티비티 3개 + 타이머 2개짜리 워크플로우의 최종 이벤트 수를 예측한 뒤 실측으로 검증하기
2. 실행 중 Worker 를 kill 하고, 재기동 후 **액티비티 로그가 다시 찍히지 않는 것**을 확인하기
3. 워크플로우 로거를 `LoggerFactory` → `Workflow.getLogger` 로 바꾸고 로그 줄 수 변화 세기
4. 주어진 히스토리 발췌를 보고 각 `WorkflowTaskCompleted` 가 어떤 Command 를 반환했는지 맞히기
5. `Workflow.isReplaying()` 으로 리플레이 횟수를 세어 출력하고, 캐시 히트/미스에 따라 값이 달라지는 것 관찰하기
6. 워크플로우 코드 안에 직접 `Thread.sleep` + 카운터 증가를 넣고 **왜 위험한지** 히스토리로 증명하기

---

## 다음 단계

리플레이가 "같은 코드 + 같은 히스토리 = 같은 Command 순서"라는 전제 위에 있다는 것을 확인했습니다. 그렇다면 그 전제를 깨뜨리는 코드는 무엇일까요. `new Random()`, `System.currentTimeMillis()`, `HashMap` 순회, 그리고 우리가 무심코 쓰는 수많은 것들입니다.

다음 스텝에서는 결정성 제약의 전체 목록과 SDK 가 제공하는 우회 API(`Workflow.currentTimeMillis`, `Workflow.randomUUID`, `Workflow.sideEffect`)를 다루고, **결정성 위반이 `NonDeterministicException` 으로 터지는 순간**을 직접 재현합니다.

→ [Step 03 — 워크플로우 정의와 결정성](../step-03-workflow-definition/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. 이번 스텝의 실습은 **Worker 를 죽였다 살리는 것**이 핵심이라, 세 파일 모두 "액티비티 사이에 5초 타이머를 넣어 kill 할 시간을 확보"하는 구조를 공유합니다. `Practice.java` 로 2-2 ~ 2-9 를 재현하고, `Exercise.java` 의 6문제를 푼 뒤, `Solution.java` 로 대조합니다.

### Practice.java

강의 본문(2-2 ~ 2-9)의 모든 코드를 절 번호 주석과 함께 담은 실습 스크립트입니다.

- `[2-3]` 리플레이 재현이 이 파일의 중심입니다. `WorkerMain` 을 띄우고 `StarterMain replay` 를 실행한 뒤, **Worker 콘솔에 "결제 요청" 이 찍히고 5초 sleep 에 들어간 순간** 다른 터미널에서 `kill -9 $(pgrep -f Practice)` 를 실행하세요. 타이밍을 놓치면 워크플로우가 그냥 끝나 버려 실습이 안 됩니다. 그래서 타이머를 5초로 넉넉하게 잡았습니다.
- `[2-9]` 로거 함정은 `BAD_LOGGER` 상수로 전환합니다. `true` 면 `LoggerFactory`(중복 발생), `false` 면 `Workflow.getLogger`(정상)입니다. **두 값을 각각 돌려 콘솔 출력 줄 수를 직접 세어 비교**하는 것이 이 절의 실습입니다. 값을 바꾸면 Worker 재시작이 필요합니다.
- `REPLAY_COUNT` 라는 `AtomicInteger` 를 워크플로우 클래스에 두고 `processOrder` 진입마다 증가시킵니다. 이 값이 워크플로우가 몇 번 재실행됐는지를 보여 줍니다. `Workflow.isReplaying()` 결과도 함께 출력하므로, "리플레이 중인 실행"과 "새 실행"을 구분해 볼 수 있습니다.
- `StarterMain` 시나리오는 `basic`(2-2 이벤트 소싱), `replay`(2-3 kill 실험), `logger`(2-9 로그 중복), `sticky`(2-7 캐시 측정) 네 가지입니다. `sticky` 는 액티비티를 200번 호출해 히스토리를 1,205 이벤트까지 키우므로 완주에 3~4분 걸립니다.
- 액티비티 구현체는 전부 **호출될 때마다 `INVOKE_COUNT` 를 증가시키고 그 값을 로그에 찍습니다.** 리플레이 후 이 카운터가 늘지 않는 것이 2-3 의 결정적 증거이며, 파일 끝의 `printInvokeCounts()` 로 한눈에 확인할 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// TODO: 여기에 작성` 을 채운 뒤 Worker 를 띄우고 히스토리로 검증합니다.

- **문제 1** 은 이벤트 수를 **먼저 예측**하게 합니다. `EXPECTED_EVENT_COUNT` 에 값을 적으면 실행 후 `temporal workflow describe` 로 확인하라는 안내가 나옵니다. 액티비티 3개 + 타이머 2개이므로 Step 01 의 산식에 타이머 항을 더해야 합니다.
- **문제 2·3** 은 코드를 거의 안 쓰고 **관찰**하는 문제입니다. 대신 관찰 결과를 `// 답:` 주석에 적게 되어 있습니다. 문제 3 은 로그 줄 수를 세는 것이라, 콘솔을 `tee replay.log` 로 파일에 받아 `grep -c` 하면 편합니다. 파일 주석에 그 명령이 적혀 있습니다.
- **문제 4** 는 코드를 실행하지 않는 지필 문제입니다. 히스토리 발췌 20줄이 주석으로 들어 있고, 각 `WorkflowTaskCompleted` 옆에 어떤 Command 를 반환했는지 적으면 됩니다. 답을 다 적은 뒤 실제 워크플로우를 돌려 대조하세요.
- **문제 5** 의 `Workflow.isReplaying()` 은 캐시 히트일 때와 미스일 때 결과가 다릅니다. Worker 를 재기동하지 않으면 sticky 캐시가 살아 있어 리플레이가 거의 안 일어나므로, **반드시 kill 을 섞어야** 의미 있는 값이 나옵니다.
- **문제 6** 은 일부러 위험한 코드로 시작합니다. 워크플로우 코드 안에 `Thread.sleep(3000)` 과 `static int counter++` 가 들어 있습니다. 실행하면 `WorkflowTaskTimedOut` 이 나거나 카운터가 예상보다 크게 나옵니다. **왜 그런지**를 히스토리로 설명하는 것이 문제입니다.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답과 긴 해설 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 **33 이벤트**입니다. 기본 5 + 액티비티 3개 × 6 + 타이머 2개 × 5 = 5 + 18 + 10 = 33. 타이머 하나가 5 이벤트(`TimerStarted` 1 + `TimerFired` 1 + 뒤따르는 `WorkflowTask*` 3)인 이유를 이벤트별로 분해해 설명합니다.
- **정답 2** 의 핵심은 "액티비티 로그 부재"입니다. 재기동 후 `PaymentActivityImpl` 로그가 없고, `INVOKE_COUNT` 도 그대로이며, `ActivityTaskStarted` 의 `attempt` 도 1인 세 가지 증거를 각각 어떻게 확인하는지 명령까지 적어 뒀습니다.
- **정답 3** 은 로그 줄 수를 **8줄 → 4줄**로 줄입니다. `Workflow.getLogger` 가 돌려주는 `ReplayAwareLogger` 가 `Workflow.isReplaying()` 을 확인해 리플레이 중 출력을 버린다는 내부 동작까지 설명하고, 팀 규칙으로 강제하는 ArchUnit 규칙 예시를 붙여 뒀습니다.
- **정답 4** 는 Command 매핑표입니다. `ScheduleActivityTask` / `StartTimer` / `CompleteWorkflowExecution` 세 가지가 각각 어느 이벤트로 나타나는지, 그리고 "`WorkflowTaskCompleted` 다음 이벤트를 보면 된다"는 규칙이 왜 항상 성립하는지 설명합니다.
- **정답 6** 이 가장 깁니다. `Thread.sleep(3000)` 은 두 가지를 동시에 망가뜨립니다. ① 워크플로우 코루틴 스레드를 진짜로 블로킹해 `workflowTaskTimeout` 10초를 위협하고, 리플레이 때마다 다시 3초씩 자므로 히스토리가 길어질수록 선형으로 느려집니다. ② `static int counter++` 는 리플레이마다 증가해 실제 실행 횟수와 무관한 값이 됩니다. 정답은 각각 `Workflow.sleep(Duration.ofSeconds(3))` 과 "액티비티로 이동"이며, 왜 `Workflow.sleep` 은 안전한지(타이머 Command 를 만들 뿐 스레드를 안 잡는다)를 히스토리로 보여 줍니다.

```java file="./Solution.java"
```
