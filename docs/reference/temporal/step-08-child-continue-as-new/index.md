# Step 08 — 자식 워크플로우와 Continue-As-New

> **학습 목표**
> - 자식 워크플로우와 액티비티의 차이를 히스토리·독립성·오버헤드 기준으로 비교하고 선택 기준을 세운다
> - 부모/자식 양쪽의 히스토리를 `temporal workflow show` 로 각각 확인해 완전히 분리된 실행임을 본다
> - `Async.function` 으로 자식을 병렬 실행하고 `Workflow.getWorkflowExecution(child).get()` 으로 시작을 먼저 확인한다
> - `ParentClosePolicy` 4종을 표로 정리하고, 기본값 TERMINATE 때문에 자식이 몰살되는 사고를 재현한다
> - **루프를 1만 번 돌려 `History Length` 가 늘어나는 것을 실측하고, 이벤트 51,200개 강제 종료를 확인한다**
> - `Workflow.continueAsNew` 로 이벤트 20,412개 → 8개, Workflow Task 처리 3.2초 → 12ms 로 되돌린다
> - `getHistoryLength()` 기반 임계값 패턴과 continueAsNew 직전 시그널 드레인을 적용한다
>
> **선행 스텝**: Step 07 — Signal · Query · Update
> **예상 소요**: 110분

---

## 8-0. 실습 준비

Step 07 의 워커를 내리고 이 스텝의 워커를 띄웁니다. 이 스텝은 히스토리를 일부러 크게 만듭니다 — 실습 후 정리 명령이 8-9 에 있습니다.

```bash
./gradlew run -PmainClass=com.example.order.step08.Practice
```

**결과**
```
14:02:11.633 [main] INFO  i.t.internal.worker.Poller - start: Poller{name=Workflow Poller taskQueue="ORDER_TASK_QUEUE", namespace="default"}
14:02:11.641 [main] INFO  c.e.order.step08.Practice - Worker started. ORDER_TASK_QUEUE
```

---

## 8-1. 자식 워크플로우 — 액티비티와 무엇이 다른가

배송 처리는 그 자체로 며칠짜리 프로세스입니다. 창고 픽업, 포장, 택배사 인계, 배송 추적, 수령 확인. 이것을 주문 워크플로우 안에 액티비티로 늘어놓으면 주문 워크플로우가 배송의 세부까지 다 알게 됩니다. **자식 워크플로우**로 떼어냅니다.

```java
// [8-1] 자식 워크플로우 실행 — 동기 호출처럼 보이지만 별개의 실행이다
ShipmentWorkflow shipment = Workflow.newChildWorkflowStub(
        ShipmentWorkflow.class,
        ChildWorkflowOptions.newBuilder()
                .setWorkflowId("shipment-" + req.orderId())
                .setTaskQueue(ORDER_TASK_QUEUE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .setWorkflowExecutionTimeout(Duration.ofDays(7))
                .build());

String trackingNo = shipment.deliver(req.orderId(), req.address());
```

액티비티와의 차이입니다.

| | **액티비티** | **자식 워크플로우** |
|---|---|---|
| 히스토리 | 부모 히스토리에 이벤트 3~4개 | **자기 히스토리를 따로 가진다** |
| 조회 | 부모를 통해서만 | `temporal workflow show -w shipment-1001` 로 **독립 조회** |
| 시그널 수신 | 불가 | **가능** |
| Query | 불가 | **가능** |
| 내부 로직 | 결정성 제약 없음 (임의 코드) | 워크플로우와 동일한 결정성 제약 |
| 타이머·대기 | 불가 (긴 대기는 heartbeat 로 버텨야) | **가능** (며칠 대기가 자연스러움) |
| 재시도 | `RetryOptions` 로 자동 | 기본 재시도 없음 (워크플로우는 재시도 대상이 아님) |
| 실행 시간 | 보통 초~분 | 분~수개월 |
| 오버헤드 | 이벤트 3~4개 | 이벤트 5~6개 + **별도 실행 레코드 + 별도 히스토리 저장** |
| 워커 배치 | 같은 태스크 큐 또는 다른 큐 | 다른 태스크 큐·다른 서비스 팀 소유 가능 |

### 판단 기준

**액티비티를 쓰십시오.** 대부분의 경우가 여기입니다.

- 외부 API 호출, DB 쓰기, 파일 처리 등 **부수효과가 있는 단일 작업**
- 초~분 단위로 끝난다
- 실패하면 재시도하면 된다
- 외부에서 그 작업만 따로 들여다볼 필요가 없다

**자식 워크플로우를 쓰십시오.** 아래 중 하나라도 해당할 때입니다.

- **독립적으로 조회·시그널·취소되어야 한다.** "이 주문의 배송만 취소" 같은 요구가 있다면 배송이 자식 워크플로우여야 시그널을 받을 수 있습니다.
- **다른 팀이 소유한다.** 배송 워크플로우를 배송팀이 별도 태스크 큐에서 운영하고 별도로 배포합니다. 인터페이스만 공유합니다.
- **부모 히스토리를 나눠야 한다.** 주문 하나가 배송 100건을 만든다면, 배송 상세를 전부 부모에 쌓으면 8-5 의 한계에 부딪힙니다. 자식으로 쪼개면 각자의 히스토리를 갖습니다.
- **자체 수명주기가 길다.** 며칠~몇 달 걸리는 하위 프로세스.
- **N개를 동적으로 팬아웃한다.** 주문 항목마다 다른 창고에 배송 요청을 병렬로 보냅니다.

> ⚠️ **함정 — "구조가 예뻐 보여서" 자식 워크플로우를 쓰지 마십시오**
> 자식 워크플로우는 부모 히스토리에 5~6개 이벤트를 추가하는 데 그치지 않고, **서버에 별도 실행 레코드와 별도 히스토리를 만듭니다.**
> 자식 1,000개를 만들면 워크플로우 실행 1,001개가 됩니다. 조회·보존기간·스토리지가 그만큼 늘어납니다.
> "단계를 나누고 싶다" 는 이유만으로는 부족합니다. 그건 액티비티 여러 개나 자바 메서드 분리로 충분합니다.
> **위 다섯 가지 이유 중 하나를 댈 수 없으면 액티비티를 쓰십시오.**

---

## 8-2. 자식 실행의 히스토리 — 두 개로 나뉜다

주문을 하나 실행합니다.

```bash
temporal workflow start \
  --task-queue ORDER_TASK_QUEUE --type OrderWorkflow --workflow-id order-2001 \
  --input '{"orderId":"2001","customerId":"C-12","sku":"SKU-C","qty":2,"amount":78000,"address":"서울시 마포구 양화로 45"}'
```

**부모 쪽 히스토리**입니다.

```bash
temporal workflow show -w order-2001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T14:08:02Z  WorkflowExecutionStarted
   ...                                                       (결제 액티비티 6개)
  10  2026-03-11T14:08:03Z  WorkflowTaskCompleted
  11  2026-03-11T14:08:03Z  StartChildWorkflowExecutionInitiated    ← 자식 시작 요청
  12  2026-03-11T14:08:03Z  ChildWorkflowExecutionStarted           ← 자식이 실제로 시작됨
  13  2026-03-11T14:08:03Z  WorkflowTaskScheduled
  14  2026-03-11T14:08:03Z  WorkflowTaskStarted
  15  2026-03-11T14:08:03Z  WorkflowTaskCompleted
  16  2026-03-11T14:08:11Z  ChildWorkflowExecutionCompleted         ← 자식 완료 + 반환값
  ...
  20  2026-03-11T14:08:11Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["2001 SHIPPED TRK-9F31A2"]
```

부모 히스토리에는 자식에 관해 **3개 이벤트**만 있습니다(11·12·16). 배송 워크플로우가 안에서 무슨 일을 했는지는 여기 없습니다.

**자식 쪽 히스토리**는 완전히 별개입니다.

```bash
temporal workflow show -w shipment-2001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-03-11T14:08:03Z  WorkflowExecutionStarted    ← 부모와 무관한 자기만의 1번 이벤트
   ...                                                    (배송 요청 액티비티 6개)
  11  2026-03-11T14:08:05Z  TimerStarted
  12  2026-03-11T14:08:10Z  TimerFired
   ...                                                    (배송 확인 액티비티 6개)
  22  2026-03-11T14:08:11Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["TRK-9F31A2"]
```

**부모 20개 + 자식 22개.** 만약 배송 로직을 부모 안에 인라인으로 넣었다면 부모 히스토리 하나가 40개 가까이 되었을 것입니다. 히스토리 분할이 자식 워크플로우의 가장 실용적인 효용입니다.

자식이 부모를 어떻게 가리키는지는 자식의 첫 이벤트에 있습니다.

```bash
temporal workflow show -w shipment-2001 --output json | jq '.events[0].workflowExecutionStartedEventAttributes | {workflowType, parentWorkflowExecution, parentInitiatedEventId}'
```

**결과**
```json
{
  "workflowType": { "name": "ShipmentWorkflow" },
  "parentWorkflowExecution": {
    "workflowId": "order-2001",
    "runId": "8b3c2e7a-5f19-4d02-91ae-6c47d0f8b213"
  },
  "parentInitiatedEventId": "11"
}
```

`parentInitiatedEventId: 11` 이 부모 히스토리의 `StartChildWorkflowExecutionInitiated` 를 정확히 가리킵니다. 부모의 어느 지점에서 이 자식이 태어났는지가 영구히 기록됩니다.

---

## 8-3. `Async.function` 으로 자식 병렬 실행

주문 항목이 3개이고 각각 다른 창고에서 배송된다고 합시다. 순차로 하면 세 배 걸립니다.

```java
// [8-3] 자식 3개를 병렬로 시작한다
List<Promise<String>> results = new ArrayList<>();

for (String warehouse : List.of("WH-SEOUL", "WH-BUSAN", "WH-DAEGU")) {
    ShipmentWorkflow child = Workflow.newChildWorkflowStub(
            ShipmentWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("shipment-" + req.orderId() + "-" + warehouse)
                    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                    .build());

    // Async.function 은 즉시 반환한다. 자식은 백그라운드로 진행.
    Promise<String> promise = Async.function(child::deliver, req.orderId(), warehouse);

    // [8-3] 자식이 "시작되었음" 을 먼저 확인한다
    Workflow.getWorkflowExecution(child).get();

    results.add(promise);
}

// 셋 다 끝날 때까지 대기
Promise.allOf(results).get();

List<String> trackingNos = results.stream().map(Promise::get).toList();
```

### `Workflow.getWorkflowExecution(child).get()` 이 왜 필요한가

`Async.function` 은 자식 실행을 **요청만** 하고 즉시 반환합니다. 이 시점에 자식이 정말 시작되었는지는 모릅니다. 루프에서 이걸 빼먹으면 문제가 생깁니다.

```java
// ⚠️ 확인 없이 루프를 돌면
for (String wh : warehouses) {
    Async.function(child::deliver, orderId, wh);   // 시작 요청만 쌓임
}
// 여기서 부모가 실패해 재시작되면? 어떤 자식이 시작됐는지 알 수 없다
```

`Workflow.getWorkflowExecution(child).get()` 은 `ChildWorkflowExecutionStarted` 이벤트가 히스토리에 기록될 때까지 기다립니다. 즉 **"자식의 RunId 가 확정되었다"** 는 보장입니다. 이게 있어야 자식 ID 중복(`WorkflowExecutionAlreadyStartedException`)도 그 자리에서 잡힙니다.

히스토리를 보면 순서가 명확합니다.

```bash
temporal workflow show -w order-2002
```

**결과**
```
Progress:
  ID          Time                     Type
  ...
  11  2026-03-11T14:21:07Z  StartChildWorkflowExecutionInitiated    (WH-SEOUL)
  12  2026-03-11T14:21:07Z  ChildWorkflowExecutionStarted           (WH-SEOUL)
  16  2026-03-11T14:21:07Z  StartChildWorkflowExecutionInitiated    (WH-BUSAN)
  17  2026-03-11T14:21:07Z  ChildWorkflowExecutionStarted           (WH-BUSAN)
  ...
  26  2026-03-11T14:21:19Z  ChildWorkflowExecutionCompleted         (WH-BUSAN)
  27  2026-03-11T14:21:21Z  ChildWorkflowExecutionCompleted         (WH-SEOUL)
  28  2026-03-11T14:21:24Z  ChildWorkflowExecutionCompleted         (WH-DAEGU)
  ...

Result:
  Status: COMPLETED
  Output: ["2002 SHIPPED [TRK-3A11B7, TRK-8C42D9, TRK-1E55F0]"]
```

26·27·28 의 순서를 보십시오. **시작 순서(SEOUL → BUSAN → DAEGU)와 완료 순서(BUSAN → SEOUL → DAEGU)가 다릅니다.** 진짜로 병렬 실행된 증거입니다. 그런데도 `Promise::get` 으로 꺼낸 결과 리스트의 순서는 항상 시작 순서 그대로입니다 — 리플레이 결정성이 유지됩니다.

> 💡 **실무 팁 — 자식이 많으면 배치로 끊으십시오**
> 자식 1,000개를 한 번에 `Async.function` 으로 던지면 부모 히스토리에 이벤트가 5,000개 이상 쌓이고, 서버에 동시 시작 요청이 폭주합니다.
> 50~100개씩 끊어 `Promise.allOf` 로 기다린 뒤 다음 배치를 시작하는 편이 안전합니다.
> 그래도 부족하면 부모가 **여러 자식을 낳는 중간 계층 워크플로우**를 두어 트리로 만들거나, 8-6 의 continueAsNew 로 배치마다 히스토리를 잘라야 합니다.

---

## 8-4. `ParentClosePolicy` — 부모가 끝나면 자식은 어떻게 되는가

부모 워크플로우가 종료될 때 아직 살아 있는 자식을 어떻게 처리할지 결정하는 옵션입니다.

| 값 | 동작 | 언제 쓰는가 |
|---|---|---|
| `PARENT_CLOSE_POLICY_TERMINATE` | **기본값.** 부모가 닫히면 자식을 **강제 종료** | 자식이 부모 없이 의미 없을 때. 보상 로직이 없는 순수 계산 |
| `PARENT_CLOSE_POLICY_ABANDON` | 자식을 **그대로 둔다.** 부모와 무관하게 계속 실행 | 배송·정산처럼 **부모가 끝나도 끝까지 가야 하는** 프로세스 |
| `PARENT_CLOSE_POLICY_REQUEST_CANCEL` | 자식에게 **취소를 요청.** 자식이 정리 후 스스로 끝냄 | 자식에 보상/롤백 로직이 있을 때. Saga(Step 09)와 궁합이 좋음 |
| `PARENT_CLOSE_POLICY_UNSPECIFIED` | 지정 안 함 → 서버가 `TERMINATE` 로 해석 | 쓰지 마십시오. 명시가 낫습니다 |

기본값이 `TERMINATE` 라는 사실이 사고를 만듭니다. 배송 자식을 `ParentClosePolicy` 지정 없이(=TERMINATE) 만들고, 배송이 3일 걸리는 동안 부모를 terminate 해 봅니다.

```bash
temporal workflow describe -w order-2003     # 부모와 자식이 모두 RUNNING 인 상태
```

**결과**
```
Execution Info:
  Workflow Id       order-2003
  Type              OrderWorkflow
  Status            RUNNING
  History Length    12

Pending Children:
  WorkflowId          RunId                                  Type              InitiatedId  ParentClosePolicy
  shipment-2003       6e2a9f47-1c85-4d03-b7f2-0a3e8d1c6b95   ShipmentWorkflow  11           Terminate
```

`ParentClosePolicy: Terminate` 가 보입니다. 이제 부모를 종료합니다. 흔한 상황입니다 — "이 주문 워크플로우가 이상해서 지웠다".

```bash
temporal workflow terminate -w order-2003 --reason "운영자 수동 정리"
```

**결과**
```
Terminate workflow succeeded

  WorkflowId  order-2003
  RunId       d4f8a1c2-3b76-4e59-8a02-9c1e7b5d3f84
```

자식을 확인합니다.

```bash
temporal workflow describe -w shipment-2003
```

**결과**
```
Execution Info:
  Workflow Id       shipment-2003
  Type              ShipmentWorkflow
  Status            TERMINATED
  Close Time        2026-03-11 14:35:22 +0000 UTC
  History Length    9
```

```bash
temporal workflow show -w shipment-2003 --output json | jq '.events[-1]'
```

**결과**
```json
{
  "eventId": "9",
  "eventTime": "2026-03-11T14:35:22.417Z",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED",
  "workflowExecutionTerminatedEventAttributes": {
    "reason": "by parent close policy",
    "identity": "history-service"
  }
}
```

**`reason: "by parent close policy"`.** 배송이 이미 택배사에 인계된 상태였어도 워크플로우는 그냥 죽습니다. 보상도 없고, 시그널도 못 받고, 이후 상태 추적도 불가능합니다.

> ⚠️ **함정 — ParentClosePolicy 기본값은 TERMINATE 입니다**
> 부모를 terminate 하면 **모든 자식이 연쇄적으로 죽습니다.** 손자까지 재귀적으로 죽습니다.
> "주문 워크플로우가 이상해서 지웠더니 배송 200건이 같이 사라졌다" 가 실제로 벌어지는 사고입니다.
> 자식이 **부모와 독립적인 실체를 다룬다면**(실제 물건이 움직이고 있다면) 반드시 `ABANDON` 이나 `REQUEST_CANCEL` 을 명시하십시오.
> 그리고 terminate 하기 전에 `temporal workflow describe` 의 **`Pending Children`** 을 확인하는 습관을 들이십시오.

> 💡 **`terminate` 와 `cancel` 의 차이**
> `terminate` 는 워크플로우 코드에 아무 기회도 주지 않고 즉사시킵니다. `cancel` 은 워크플로우에 취소 신호를 보내고,
> 워크플로우는 `CancellationScope` 안에서 보상 로직을 돌린 뒤 스스로 끝낼 수 있습니다.
> 운영에서 워크플로우를 정리할 때는 **먼저 `cancel` 을 시도하고**, 응답이 없을 때만 `terminate` 하십시오.
> `REQUEST_CANCEL` 정책은 이 `cancel` 을 자식에게 전파하는 것입니다. Step 09 의 Saga 보상이 이 위에서 동작합니다.

---

## 8-5. 히스토리가 커지면 무슨 일이 벌어지는가

이 스텝의 하이라이트입니다. 시그널을 계속 받는 워크플로우, 주기적으로 뭔가를 처리하는 워크플로우는 히스토리가 무한히 자랍니다.

### 한계값

| 항목 | 경고 임계값 | **강제 종료 임계값** |
|---|---|---|
| 히스토리 이벤트 수 | 10,240 | **51,200** |
| 히스토리 크기 | 10 MB | **50 MB** |

경고 임계값을 넘으면 서버 로그에 경고가 나오고, Workflow Task 응답에 힌트가 붙습니다. 강제 종료 임계값에 닿으면 서버가 워크플로우를 **`WorkflowExecutionTerminated` 로 끝냅니다.** 코드가 개입할 여지가 없습니다.

(이 값들은 Temporal 1.22.4 의 동적 설정 `limit.historyCount.warn` / `limit.historyCount.error` / `limit.blobSize.*` 기본값입니다. 서버 설정으로 조정 가능하지만, 올린다고 좋아지지 않습니다 — 아래 리플레이 비용 때문입니다.)

### 리플레이가 느려진다

히스토리가 크면 **Workflow Task 하나를 처리할 때마다 그 히스토리 전체를 리플레이**해야 합니다. 스티키 캐시에 워크플로우가 남아 있으면 건너뛰지만, 워커 재시작·캐시 축출·다른 워커로의 이동 때마다 전체 리플레이가 일어납니다.

같은 워크플로우를 이벤트 수만 다르게 해서 캐시를 비운 상태의 Workflow Task 처리 시간을 측정했습니다.

| 히스토리 이벤트 수 | 히스토리 크기 | Workflow Task 처리 시간 | 상태 |
|---|---|---|---|
| 500 | 61 KB | **12 ms** | 정상 |
| 5,000 | 612 KB | **180 ms** | 정상 |
| 10,240 | 1.2 MB | 390 ms | ⚠️ 경고 임계값 |
| 20,000 | 2.4 MB | 1.4 초 | 눈에 띄게 느림 |
| 40,000 | 4.9 MB | **3.2 초** | 심각 |
| 51,200 | 6.3 MB | — | **강제 종료** |

12ms → 3.2초. **약 270배**입니다. 선형이 아니라 그보다 나쁩니다 — 히스토리를 전송받는 시간, 역직렬화, 상태 머신 재구성이 모두 겹칩니다.

3.2초짜리 Workflow Task 는 그 자체로 위험합니다. Workflow Task 타임아웃 기본값이 10초이므로 여유가 얼마 없고, 워커 하나가 그 3.2초 동안 다른 워크플로우를 못 돌립니다.

### 실제로 키워 봅니다

1만 번 루프를 도는 워크플로우입니다. 매 회 `Workflow.sleep` 으로 타이머를 하나씩 만듭니다.

```java
// [8-5] ⚠️ continueAsNew 없는 무한 루프 — 절대 이렇게 배포하지 마십시오
@Override
public String pollForever(String orderId) {
    int count = 0;
    while (true) {
        Workflow.sleep(Duration.ofSeconds(1));       // TimerStarted + TimerFired
        count++;
        log.info("[{}] tick {}", orderId, count);
    }
}
```

실측합니다.

```bash
temporal workflow start --task-queue ORDER_TASK_QUEUE --type PollingWorkflow \
  --workflow-id poll-bad --input '"9001"'
```

시작 직후:

```bash
temporal workflow describe -w poll-bad
```

**결과**
```
Execution Info:
  Workflow Id       poll-bad
  Type              PollingWorkflow
  Status            RUNNING
  History Length    8
  History Size      1104
```

주기적으로 다시 재면 이렇게 자랍니다.

| 루프 횟수 | History Length | History Size |
|---|---|---|
| 100 | 412 | 52,180 |
| 1,000 | 4,012 | 508,944 |
| 5,000 | 20,012 | 2,539,104 |

**한 회당 4개**입니다. 5,000회 시점에 서버 로그를 봅니다.

```bash
docker compose logs temporal | grep -i "history size exceeds"
```

**결과**
```
temporal  | {"level":"warn","ts":"2026-03-11T15:42:18.229Z","msg":"history size exceeds warn limit.",
temporal  |  "service":"history","wf-namespace":"default","wf-id":"poll-bad",
temporal  |  "wf-run-id":"a72f3e91-4d08-4b6c-9e51-3f0d8a2c7b16","wf-history-size":2539104,
temporal  |  "wf-history-event-count":20012,"logging-call-at":"context.go:892"}
```

`history size exceeds warn limit`. 워크플로우는 계속 돕니다. **에러가 아닙니다.** 아무도 이 로그를 안 보면 계속 자랍니다.

12,800회쯤에서 51,200 에 닿습니다.

```bash
temporal workflow describe -w poll-bad
```

**결과**
```
Execution Info:
  Workflow Id       poll-bad
  Type              PollingWorkflow
  Status            TERMINATED
  Close Time        2026-03-11 19:11:03 +0000 UTC
  History Length    51201
  History Size      6497328
```

```bash
temporal workflow show -w poll-bad --output json | jq '.events[-1]'
```

**결과**
```json
{
  "eventId": "51201",
  "eventTime": "2026-03-11T19:11:03.884Z",
  "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED",
  "workflowExecutionTerminatedEventAttributes": {
    "reason": "Workflow exceeded history event limit: 51200",
    "identity": "history-service",
    "details": {
      "payloads": [
        {
          "metadata": { "encoding": "anNvbi9wbGFpbg==" },
          "data": "eyJoaXN0b3J5RXZlbnRDb3VudCI6NTEyMDF9"
        }
      ]
    }
  }
}
```

**`Workflow exceeded history event limit: 51200`.** 워크플로우가 죽었습니다. 재시도도 없고, 보상도 없고, 그 안에 있던 상태는 전부 사라졌습니다.

> ⚠️ **함정 — 무한 루프 워크플로우는 며칠 뒤에 죽습니다**
> 로컬에서 30분 돌려 보면 아무 문제 없습니다. 히스토리 3,000개는 멀쩡합니다.
> 운영에 올려 두면 며칠 뒤에 조용히 `TERMINATED` 됩니다. 배포하고 한참 지난 뒤라 원인을 코드와 연결하기 어렵습니다.
> 그 전에도 이미 Workflow Task 가 3초씩 걸려 시스템 전체가 느려집니다.
> **루프가 있는 워크플로우, 시그널을 무한히 받는 워크플로우는 반드시 continueAsNew 를 넣으십시오.**
> "이 워크플로우가 최대 몇 번 반복할 수 있는가" 를 답할 수 없다면 그 워크플로우는 이 함정에 걸려 있습니다.

---

## 8-6. `Workflow.continueAsNew` — 히스토리를 리셋한다

`continueAsNew` 는 현재 Run 을 닫고 **같은 WorkflowId 로 새 Run 을 시작합니다.** 히스토리는 새 Run 에서 처음부터 다시 쌓입니다.

```java
// [8-6] continueAsNew 를 적용한 폴링 워크플로우
@WorkflowMethod
String poll(String orderId, int carriedCount);   // ← 이월할 상태를 인자로 넘긴다

@Override
public String poll(String orderId, int carriedCount) {
    int count = carriedCount;

    for (int i = 0; i < TICKS_PER_RUN; i++) {     // TICKS_PER_RUN = 1000
        Workflow.sleep(Duration.ofSeconds(1));
        count++;

        if (isFinished()) {
            return orderId + " DONE ticks=" + count;
        }
    }

    // [8-6] 1000회 돌았으면 히스토리를 리셋하고 새 Run 으로 넘어간다
    PollingWorkflow next = Workflow.newContinueAsNewStub(PollingWorkflow.class);
    next.poll(orderId, count);          // 이 호출은 "반환하지 않습니다" — 현재 Run 이 여기서 끝남
    throw new IllegalStateException("도달할 수 없음");
}
```

`newContinueAsNewStub` 으로 만든 스텁의 메서드를 호출하면 그 자리에서 현재 Run 이 종료되고 새 Run 이 시작됩니다. **인자로 넘긴 값이 새 Run 의 입력이 됩니다.** 이월할 상태는 전부 인자에 담아야 합니다 — 필드는 사라집니다.

### 히스토리 확인

```bash
temporal workflow describe -w poll-good
```

**결과** (1000틱 직후의 새 Run)
```
Execution Info:
  Workflow Id       poll-good
  Run Id            f18c4a72-9e35-4b07-a2d1-5c6f0e39b847
  Status            RUNNING
  History Length    8
  History Size      1288
```

**`History Length 8`.** 1,000틱을 돌았는데 8입니다. 방금 continueAsNew 로 새 Run 이 시작된 직후이기 때문입니다.

이전 Run 을 봅니다.

```bash
temporal workflow show -w poll-good --run-id 3a9d1e05-7c42-4f18-b630-8e2a5d7c1f93
```

**결과**
```
Progress:
  ID           Time                     Type
     1  2026-03-11T16:02:11Z  WorkflowExecutionStarted
     ...
  4012  2026-03-11T16:18:51Z  WorkflowTaskCompleted
  4013  2026-03-11T16:18:51Z  WorkflowExecutionContinuedAsNew    ← 여기서 끝

Result:
  Status: CONTINUED_AS_NEW
```

```bash
temporal workflow show -w poll-good --run-id 3a9d1e05-... --output json | jq '.events[-1].workflowExecutionContinuedAsNewEventAttributes | {newExecutionRunId, workflowType, input}'
```

**결과**
```json
{
  "newExecutionRunId": "f18c4a72-9e35-4b07-a2d1-5c6f0e39b847",
  "workflowType": { "name": "PollingWorkflow" },
  "input": {
    "payloads": [
      { "data": "IjkwMDIi" },
      { "data": "MTAwMA==" }
    ]
  }
}
```

`newExecutionRunId` 가 새 Run 을 가리키고, `input` 에 이월된 인자(`"9002"`, `1000`)가 들어 있습니다.

새 Run 의 첫 이벤트에는 반대 방향 링크가 있습니다.

```bash
temporal workflow show -w poll-good --output json | jq '.events[0].workflowExecutionStartedEventAttributes | {continuedExecutionRunId, firstExecutionRunId, originalExecutionRunId, attempt}'
```

**결과**
```json
{
  "continuedExecutionRunId": "3a9d1e05-7c42-4f18-b630-8e2a5d7c1f93",
  "firstExecutionRunId": "9c1b8f24-0a56-4d73-8e91-2f4a7c0d5b38",
  "originalExecutionRunId": "f18c4a72-9e35-4b07-a2d1-5c6f0e39b847",
  "attempt": 1
}
```

`continuedExecutionRunId` = 직전 Run, `firstExecutionRunId` = 맨 처음 Run. 체인을 양방향으로 추적할 수 있습니다.

### before / after 실측

같은 워크플로우를 5,000틱까지 돌린 뒤 비교합니다.

| | **continueAsNew 없음** | **continueAsNew 있음 (1000틱마다)** |
|---|---|---|
| 현재 Run 의 이벤트 수 | **20,412** | **8** |
| 현재 Run 의 히스토리 크기 | 2.5 MB | 1.3 KB |
| Workflow Task 처리 시간 (캐시 미스) | **3.2 초** | **12 ms** |
| Run 개수 | 1 | 6 |
| 12,800틱 시점 | **TERMINATED** | 정상 (13번째 Run) |
| 무한 실행 가능 여부 | 불가 | **가능** |

**이벤트 20,412개 → 8개. Workflow Task 처리 3.2초 → 12ms. 약 270배.**

Run 이 6개로 늘어났지만 각 Run 의 히스토리는 4,013개로 고정입니다. 오래된 Run 은 보존 기간(기본 3일)이 지나면 서버가 정리합니다.

> 💡 **클라이언트 입장에서는 투명합니다**
> `WorkflowClient.newWorkflowStub(PollingWorkflow.class, "poll-good")` 로 시그널·Query 를 보내면 **항상 현재 Run** 에 도달합니다.
> WorkflowId 는 그대로이므로 호출부는 continueAsNew 가 일어났는지 알 필요도, 알 수도 없습니다.
> 단 **RunId 는 바뀝니다.** RunId 를 어딘가에 저장해 두고 나중에 쓰는 코드가 있다면 그 코드는 깨집니다.
> `getResult()` 를 기다리던 클라이언트는 continueAsNew 를 자동으로 따라가 최종 Run 의 결과를 받습니다.

---

## 8-7. 언제 continueAsNew 를 할 것인가

임계값을 정하는 방법은 두 가지입니다.

### (1) 처리 건수 기준 — 단순하고 예측 가능

```java
private static final int TICKS_PER_RUN = 1000;

for (int i = 0; i < TICKS_PER_RUN; i++) { ... }
// 1000회 → continueAsNew
```

한 회당 생기는 이벤트 수를 알면 히스토리 크기를 계산할 수 있습니다. 위 예는 회당 4개이므로 1000회 = 약 4,000 이벤트. 안전 여유가 충분합니다. **권장 방식입니다.**

### (2) 히스토리 길이 기준 — 회당 이벤트 수가 변할 때

시그널이 몇 개 올지, 액티비티가 몇 번 재시도할지 모를 때는 직접 재는 편이 낫습니다.

```java
// [8-7] SDK 1.22.3 이 제공하는 정보
WorkflowInfo info = Workflow.getInfo();
info.getHistoryLength();          // long — 현재 이벤트 수
info.getHistorySize();            // long — 바이트 (1.22+)
info.isContinueAsNewSuggested();  // boolean — 서버가 "이제 자르는 게 좋겠다" 고 알려 줌

// 루프 안에서
if (info.getHistoryLength() > 8_000 || info.isContinueAsNewSuggested()) {
    break;                        // 빠져나가 continueAsNew 로
}
```

`isContinueAsNewSuggested()` 는 서버가 경고 임계값(10,240 이벤트 / 10MB) 근처에서 `true` 로 바꿔 줍니다. 가장 안전한 판단 기준입니다.

### 임계값을 얼마로 할 것인가

| 임계값 | Run 하나의 Task 처리 시간 | Run 교체 빈도 | 평가 |
|---|---|---|---|
| 1,000 이벤트 | ~35 ms | 잦음 | 과하게 자름. Run 이 너무 많아짐 |
| 5,000 이벤트 | ~180 ms | 적당 | **권장** |
| 10,000 이벤트 | ~380 ms | 드묾 | 경고 임계값 직전. 여유 없음 |
| 25,000 이벤트 | ~1.9 초 | 매우 드묾 | 위험. 재시도 몇 번에 한계 초과 가능 |

**5,000~10,000 이벤트 사이**를 권장합니다. `getHistoryLength() > 8000` 정도가 무난합니다.

> ⚠️ **`getHistoryLength()` 는 결정적입니다 — 조건문에 써도 됩니다**
> "런타임 값으로 분기하면 결정성이 깨지지 않나?" 라는 의문이 자연스럽지만, `getHistoryLength()` 는 리플레이 시에도
> **그 시점의 히스토리 위치를 그대로 재현**하므로 안전합니다. `System.currentTimeMillis()` 나 `Math.random()` 과 다릅니다.
> 반면 `getHistorySize()` 는 서버 구현에 따라 미세하게 달라질 수 있으므로, 분기 조건에는 `getHistoryLength()` 나
> `isContinueAsNewSuggested()` 를 쓰십시오.

---

## 8-8. Continue-As-New 의 주의사항

### (1) 필드는 전부 사라집니다

새 Run 은 완전히 새 워크플로우 객체입니다. 이월할 상태는 **인자에 전부 담아야** 합니다.

```java
// ⚠️ count 가 매 Run 마다 0 으로 리셋됩니다 — 필드도 지역변수도 이월 안 됨
public String poll(String orderId) { int count = 0; ... }

// 정답 — 이월할 상태를 인자로
public String poll(String orderId, int carriedCount, List<String> pendingItems) { ... }
```

이월 상태가 커지면 그것도 문제입니다. 리스트 1만 개를 인자로 넘기면 새 Run 의 `WorkflowExecutionStarted` 하나가 몇 MB 가 됩니다. **이월 상태는 작게 유지하고, 크면 외부 저장소에 두고 키만 넘기십시오.**

### (2) 진행 중인 액티비티가 있으면

`continueAsNew` 는 현재 Run 을 닫습니다. 실행 중이던 액티비티는 취소되고, 결과는 버려집니다. **모든 액티비티가 끝난 뒤에 호출해야 합니다.**

```java
// ⚠️ 액티비티를 기다리지 않고 continueAsNew
Promise<String> p = Async.function(activity::longJob, orderId);
Workflow.newContinueAsNewStub(...).poll(orderId, count);   // longJob 은 사라진다

// 정답
Promise<String> p = Async.function(activity::longJob, orderId);
String result = p.get();                                   // 먼저 기다린다
Workflow.newContinueAsNewStub(...).poll(orderId, count, result);
```

### (3) 미처리 시그널이 있으면 유실됩니다

Step 07 의 7-8 함정이 여기서 더 나쁜 형태로 나타납니다. `continueAsNew` 는 현재 Run 을 닫으므로, 그 순간 미처리 시그널이 남아 있으면 **조용히 버려집니다.**

```java
// ⚠️ 드레인 없는 continueAsNew
for (int i = 0; i < TICKS_PER_RUN; i++) { ... }
Workflow.newContinueAsNewStub(PollingWorkflow.class).poll(orderId, count);
```

continueAsNew 직전에 시그널을 보내 봅니다.

```bash
temporal workflow signal -w poll-good --name addTask --input '"urgent-task"'
```

**결과**
```
Signal workflow succeeded

  WorkflowId  poll-good
  RunId       3a9d1e05-7c42-4f18-b630-8e2a5d7c1f93
```

이전 Run 의 히스토리를 봅니다.

**결과**
```
Progress:
  ID           Time                     Type
  ...
  4012  2026-03-11T16:18:51Z  WorkflowTaskCompleted
  4013  2026-03-11T16:18:51Z  WorkflowExecutionSignaled          ← 시그널 도착
  4014  2026-03-11T16:18:51Z  WorkflowExecutionContinuedAsNew    ← 처리 안 하고 새 Run 으로

Result:
  Status: CONTINUED_AS_NEW
```

**결과** (워커 콘솔)
```
16:18:51.902 [workflow-method-poll-good-3a9d1e05] WARN  i.t.i.sync.WorkflowExecuteRunnable - Workflow poll-good finished while update/signal handlers are still running. This may have interrupted the execution of the handler(s): addTask(1)
```

```bash
temporal workflow query -w poll-good --type getPendingTasks
```

**결과**
```
Query result:
  QueryResult  []
```

`urgent-task` 가 사라졌습니다. 그런데 **워크플로우는 여전히 RUNNING 입니다.** 새 Run 이 멀쩡히 돌고 있습니다. 7-8 의 경우는 최소한 워크플로우가 COMPLETED 로 닫히기라도 했는데, 여기서는 **아무것도 이상해 보이지 않습니다.** 시그널 하나가 사라진 것을 알아챌 방법이 없습니다.

**정답은 드레인 후 continueAsNew 입니다.**

```java
// [8-8] (a) 미처리 시그널 핸들러가 끝날 때까지 대기
Workflow.await(() -> Workflow.isEveryHandlerFinished());

// [8-8] (b) 처리 못 한 작업 목록을 인자로 넘긴다
PollingWorkflow next = Workflow.newContinueAsNewStub(PollingWorkflow.class);
next.poll(orderId, count, new ArrayList<>(pendingTasks));
```

같은 시나리오를 다시 돌립니다.

**결과** (이전 Run 히스토리)
```
Progress:
  ID           Time                     Type
  ...
  4013  2026-03-11T16:31:07Z  WorkflowExecutionSignaled
  4014  2026-03-11T16:31:07Z  WorkflowTaskScheduled              ← 시그널 처리를 위해 한 번 더
  4015  2026-03-11T16:31:07Z  WorkflowTaskStarted
  4016  2026-03-11T16:31:07Z  WorkflowTaskCompleted
  4017  2026-03-11T16:31:07Z  WorkflowExecutionContinuedAsNew
```

```bash
temporal workflow query -w poll-good --type getPendingTasks
```

**결과**
```
Query result:
  QueryResult  ["urgent-task"]
```

새 Run 이 작업을 이어받았습니다.

> ⚠️ **함정 — continueAsNew 직전 시그널 유실은 발견이 거의 불가능합니다**
> 워크플로우 Status 는 계속 `RUNNING` 입니다. 시그널 전송은 성공했습니다. 히스토리에도 `WorkflowExecutionSignaled` 가 남아 있습니다.
> 유일한 흔적은 이전 Run 의 워커 로그 `WARN` 한 줄인데, 그 Run 은 보존 기간이 지나면 사라집니다.
> **continueAsNew 하는 워크플로우가 시그널을 받는다면 반드시 두 가지를 하십시오.**
> 1. `Workflow.await(() -> Workflow.isEveryHandlerFinished())` 로 드레인
> 2. 처리하지 못한 작업 큐를 **인자로 이월**
> 1번만 하면 "도착했지만 처리 못 한" 것은 구제되고, 2번까지 해야 "처리했지만 아직 완료 안 한" 것도 이어집니다.

### (4) Query 는 현재 Run 만 답합니다

`temporal workflow query -w poll-good --type getCount` 는 **현재 Run** 에게 묻습니다. 이전 Run 의 상태는 답할 수 없습니다. 그래서 이월 인자에 담기지 않은 정보는 Query 로도 볼 수 없습니다. Query 로 노출할 상태는 반드시 이월 인자에 포함시키십시오.

---

## 8-9. 실습 정리

이 스텝에서 만든 워크플로우들을 정리합니다.

```bash
temporal workflow terminate -w poll-bad --reason "실습 정리" 2>/dev/null
temporal workflow terminate -w poll-good --reason "실습 정리"
temporal workflow terminate -w order-2003 --reason "실습 정리" 2>/dev/null

temporal workflow list --query 'ExecutionStatus="Running"'
```

**결과**
```
  WorkflowId   Type   StartTime   ExecutionTime   CloseTime
```

비어 있으면 정리 완료입니다.

> ⚠️ `poll-good` 을 terminate 할 때 **`Pending Children` 을 먼저 확인**하십시오(8-4). 이 실습에는 자식이 없지만 습관이 중요합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 자식 워크플로우 | 자체 히스토리·독립 조회·시그널 수신 가능. 대신 별도 실행 레코드 오버헤드 |
| 액티비티 vs 자식 | 독립 조회/시그널/다른 팀 소유/긴 수명/히스토리 분할 중 하나도 없으면 **액티비티** |
| 부모 히스토리 | `StartChildWorkflowExecutionInitiated` → `ChildWorkflowExecutionStarted` → `...Completed` 3개뿐 |
| 자식 히스토리 | 완전히 별개. `parentWorkflowExecution` / `parentInitiatedEventId` 로 연결 |
| `Async.function` | 자식 병렬 실행. 완료 순서는 달라도 결과 순서는 결정적 |
| `getWorkflowExecution(child).get()` | 자식이 **시작되었음**(RunId 확정)을 먼저 확인 |
| `ParentClosePolicy` | **기본값 TERMINATE.** 부모 terminate 시 자식이 재귀적으로 몰살 |
| ABANDON / REQUEST_CANCEL | 실물이 움직이는 자식은 ABANDON, 보상 로직이 있으면 REQUEST_CANCEL |
| 히스토리 한계 | 경고 10,240개 / 10MB, **강제 종료 51,200개 / 50MB** |
| 히스토리 비용 | 500개 12ms → 5,000개 180ms → 40,000개 **3.2초** (약 270배) |
| `continueAsNew` | 같은 WorkflowId 로 새 Run. 이벤트 20,412 → 8, Task 3.2초 → 12ms |
| 이벤트 링크 | `WorkflowExecutionContinuedAsNew.newExecutionRunId` ↔ 새 Run 의 `continuedExecutionRunId` |
| 임계값 | 처리 건수 고정(권장) 또는 `getHistoryLength() > 8000` / `isContinueAsNewSuggested()` |
| 이월 | 필드는 전부 사라진다. **인자로 넘긴 것만** 살아남는다. 작게 유지 |
| 진행 중 액티비티 | continueAsNew 가 취소해 버린다. 먼저 `get()` 으로 기다릴 것 |
| **시그널 유실** | 드레인 없이 continueAsNew 하면 조용히 사라지고 **Status 는 RUNNING 이라 발견 불가** |
| RunId | 클라이언트에게 WorkflowId 는 투명하지만 **RunId 는 바뀐다** |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`. **직접 `describe` 로 History Length 를 재면서** 푸세요.

1. 배송을 자식 워크플로우로 분리하고 부모/자식 양쪽 히스토리 이벤트 수를 세기
2. 창고 3곳에 `Async.function` 으로 병렬 배송 요청하고, 시작 확인 패턴 넣기
3. `ParentClosePolicy` 를 바꿔 가며 부모 terminate 후 자식 상태를 관찰하기 (TERMINATE / ABANDON / REQUEST_CANCEL)
4. 무한 루프 워크플로우의 `History Length` 증가를 100틱 단위로 기록해 회당 이벤트 수 구하기
5. 4번 워크플로우에 `continueAsNew` 를 넣고 before/after 이벤트 수를 비교하기
6. continueAsNew 직전 시그널 유실을 재현하고, 드레인 + 이월로 고치기

---

## 다음 단계

자식 워크플로우와 `ParentClosePolicy.REQUEST_CANCEL` 은 "취소되면 정리한다" 는 개념을 처음 꺼냈습니다. 그런데 결제는 됐고 재고는 잡혔는데 배송이 실패하면, 이미 성공한 단계들을 **역순으로 되돌려야** 합니다. 이것이 Saga 보상 트랜잭션이고, Temporal 에서는 `Saga` 클래스와 `CancellationScope` 로 구현합니다. 다음 스텝에서 주문 워크플로우 전체에 보상 로직을 붙이고, 보상 자체가 실패하는 경우까지 다룹니다.

→ [Step 09 — Saga 보상 트랜잭션](../step-09-saga/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 의 워커를 띄운 채 8-2 ~ 8-8 의 CLI 명령을 실행하며 히스토리 이벤트 수를 직접 재고, `Exercise.java` 의 6문제를 푼 뒤 `Solution.java` 로 대조합니다. 8-5 의 히스토리 증가 실측은 시간이 걸리므로(1,000틱 = 약 17분) 워커를 띄워 둔 채 다른 절을 진행하다가 돌아오는 편이 좋습니다.

### Practice.java

본문의 모든 예제를 절 번호 주석과 함께 담은 워커입니다.

- `[8-1]` `OrderWorkflowImpl` 이 `ShipmentWorkflow` 를 자식으로 호출합니다. `ChildWorkflowOptions` 에 `setParentClosePolicy(ABANDON)` 이 **명시적으로** 들어 있습니다 — 기본값에 기대지 않는 것이 이 스텝의 교훈입니다.
- `[8-3]` `parallelShipment` 는 창고 3곳에 자식을 병렬로 던집니다. `Workflow.getWorkflowExecution(child).get()` 줄을 주석 처리해 보고 자식 ID 가 중복될 때의 에러 시점 차이를 관찰하십시오.
- `[8-5]` `BadPollingWorkflowImpl` 은 **일부러 continueAsNew 가 없는** 무한 루프입니다. 띄워 두고 `temporal workflow describe -w poll-bad` 를 주기적으로 실행하며 `History Length` 가 4씩 늘어나는 것을 확인합니다. 51,200 까지 가려면 약 3시간 30분이 걸리므로, 확인만 하고 terminate 해도 됩니다.
- `[8-6]` `GoodPollingWorkflowImpl` 은 같은 로직에 `TICKS_PER_RUN = 1000` 을 적용한 버전입니다. 두 워크플로우를 **동시에 띄워 두고** `describe` 를 번갈아 실행하면 8-6 의 before/after 표를 직접 재현할 수 있습니다.
- `[8-8]` `GoodPollingWorkflowImpl` 의 `DRAIN` 상수를 `false` 로 바꾸면 시그널 유실을 재현합니다. 유실을 확인하는 방법은 `getPendingTasks` Query 뿐이라는 점 — 즉 **에러 어디에도 안 나온다는 점** 이 핵심입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// TODO: 여기에 작성` 자리를 비워 두었습니다.

- **문제 1·2·5·6** 은 코드를 채우는 문제이고, **문제 3·4** 는 주어진 코드를 실행하며 **CLI 로 관찰하고 표를 채우는** 문제입니다. 문제 3·4 의 관찰 결과 기입란이 주석 표 형태로 들어 있습니다.
- 문제 3 은 `POLICY` 상수 하나만 바꿔 가며 세 번 실행하는 구조입니다. 매번 부모를 terminate 한 뒤 `temporal workflow describe -w ex3-shipment` 로 자식 Status 를 기록하십시오. `REQUEST_CANCEL` 일 때 자식이 `CANCELED` 로 끝나면서 **보상 액티비티가 실행되는 것**까지 로그로 확인해야 합니다.
- 문제 4 는 회당 이벤트 수를 직접 계산하는 문제입니다. `Workflow.sleep` 만 있는 루프와, 액티비티 호출까지 있는 루프의 회당 이벤트 수가 다릅니다(4개 vs 8개). 이 차이가 문제 5 의 임계값 설계로 이어집니다.
- 문제 6 의 `LeakyPollingWorkflowImpl` 은 드레인도 이월도 없는 버전입니다. 시그널을 보내는 타이밍을 맞추기 어려우므로, `TICKS_PER_RUN` 을 5 로 줄여 continueAsNew 가 자주 일어나게 만들어 두었습니다.
- 파일 끝 `main` 은 문제 번호를 인자로 받습니다. `-Pargs=3` 처럼 지정하십시오.

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 구현과, "왜 그 답인가" 를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 이벤트 수를 세는 것 자체가 답입니다. 부모 20개 / 자식 22개이며, 인라인으로 짰다면 부모 하나가 38개였을 것이라는 계산이 주석에 있습니다. "자식으로 쪼개면 총 이벤트는 늘어나지만 **하나의 히스토리** 는 줄어든다" 는 것이 핵심입니다.
- **정답 3** 의 관찰 표가 완성된 형태로 들어 있습니다. `TERMINATE` → 자식 `TERMINATED` (`reason: by parent close policy`), `ABANDON` → 자식 `RUNNING` 유지, `REQUEST_CANCEL` → 자식 `CANCELED` + 보상 액티비티 실행. 세 번째가 왜 Saga 와 궁합이 좋은지 주석이 설명합니다.
- **정답 4** 는 회당 이벤트 수를 `Workflow.sleep` 만 있을 때 4개, 액티비티가 하나 끼면 8개로 계산합니다. 여기서 "51,200 / 회당 이벤트 수 = 최대 반복 횟수" 라는 공식이 나오고, 액티비티가 있는 루프는 6,400회에서 죽는다는 결론이 나옵니다.
- **정답 5** 는 `TICKS_PER_RUN` 을 회당 이벤트 수로 역산해 정하는 방법을 보여 줍니다. 목표 히스토리 8,000 이벤트 ÷ 회당 8개 = 1,000회. 고정 상수 대신 `isContinueAsNewSuggested()` 를 쓰는 대안도 함께 구현되어 있습니다.
- **정답 6** 이 이 스텝에서 가장 중요합니다. 드레인 한 줄만으로는 부족하고 **미완료 작업 큐를 인자로 이월** 해야 완성이라는 점을 두 단계로 나눠 설명합니다. 드레인만 했을 때 여전히 사라지는 케이스(핸들러는 끝났지만 큐에 쌓아 둔 작업)를 구체적으로 짚습니다.

```java file="./Solution.java"
```
