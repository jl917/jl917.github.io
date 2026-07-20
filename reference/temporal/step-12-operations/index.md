# Step 12 — 운영

> **학습 목표**
> - Namespace 를 만들고 Retention Period 를 설정해, 히스토리가 언제 사라지는지 확인한다
> - `temporal` CLI 의 주요 명령을 실제 출력과 함께 익힌다 (list / count / describe / show / signal / query / reset)
> - **terminate 와 cancel 의 차이**를 히스토리 이벤트로 확인하고, cancel 후 보상을 돌리려면 왜 `CancellationScope.newDetachedScope` 가 필요한지 이해한다
> - 커스텀 Search Attribute 를 등록해 `temporal workflow list --query "CustomerId='C-77'"` 로 조회한다
> - Worker 메트릭 5종을 읽고, 각 지표가 오를 때 무엇을 의심할지 판단한다
> - `WorkerOptions` 를 튜닝하고, `workflowCacheSize` 와 `maxWorkflowThreadCount` 를 함께 올려야 하는 이유를 확인한다
> - "워크플로우가 Running 인데 안 움직인다"를 5단계로 진단한다
>
> **선행 스텝**: Step 11 — 테스트
> **예상 소요**: 100분

---

## 12-0. 실습 준비

Temporal Server 1.22.4 와 CLI 0.11.0 이 필요합니다.

```bash
docker compose up -d && temporal --version
```

**결과**
```
[+] Running 4/4
 ✔ Container temporal-postgresql  Healthy    12.4s
 ✔ Container temporal             Started     1.1s
 ✔ Container temporal-ui          Started     0.9s
 ✔ Container temporal-admin-tools Started     0.8s

temporal version 0.11.0 (server 1.22.4)
```

CLI 는 기본적으로 `127.0.0.1:7233`, namespace `default` 로 접속합니다. 매번 `--namespace` 를 치기 싫으면 환경변수로 둡니다.

```bash
export TEMPORAL_ADDRESS=127.0.0.1:7233
export TEMPORAL_NAMESPACE=orders
```

---

## 12-1. Namespace — 격리 단위

Namespace 는 Temporal 의 **최상위 격리 단위**입니다. 워크플로우 ID 의 유일성도, Task Queue 이름도, Search Attribute 도, Retention 도 전부 Namespace 단위입니다. 서로 다른 Namespace 의 `order-1001` 은 완전히 다른 워크플로우입니다.

```bash
temporal operator namespace create --namespace orders --retention 72h
```

**결과**
```
Namespace orders successfully registered.
```

```bash
temporal operator namespace describe --namespace orders
```

**결과**
```
NamespaceInfo.Name                                orders
NamespaceInfo.Id                                  8f3a1c2e-9d47-4b60-a5e1-7c8f2b0d4a19
NamespaceInfo.State                               Registered
Config.WorkflowExecutionRetentionTtl              72h0m0s
Config.HistoryArchivalState                       Disabled
Config.VisibilityArchivalState                    Disabled
ReplicationConfig.ActiveClusterName               active
IsGlobalNamespace                                 false
```

`Config.WorkflowExecutionRetentionTtl` 이 72시간, Archival 은 둘 다 Disabled 입니다.

### 환경 분리 전략

| 방식 | 구성 | 장점 | 단점 |
|---|---|---|---|
| Namespace 로 분리 | 한 클러스터에 `orders-dev` / `orders-stg` / `orders-prod` | 운영 비용 낮음, CLI 로 오가기 편함 | 클러스터 장애가 전 환경에 영향. **prod 를 실수로 조작할 위험** |
| 클러스터로 분리 | dev/stg 한 클러스터, prod 별도 클러스터 | prod 완전 격리 | 인프라 두 벌 |
| 혼합 (권장) | dev·stg 는 한 클러스터의 두 Namespace, **prod 는 별도 클러스터** | 격리와 비용의 절충 | — |

Namespace 는 **서비스 도메인 단위**로 나누는 것도 좋습니다. `orders`, `payments`, `settlement` 처럼 나누면 팀별 권한 분리와 Retention 정책 분리가 자연스럽습니다.

> 💡 **실무 팁 — prod Namespace 는 CLI 기본값에서 빼세요**
> `TEMPORAL_NAMESPACE=orders-prod` 를 셸 프로필에 박아 두면 언젠가 `temporal workflow terminate` 를 prod 에 쏩니다.
> 기본값은 dev 로 두고, prod 작업은 항상 `--namespace orders-prod` 를 **명시적으로** 붙이게 하세요. 타이핑 한 번이 사고를 막습니다.

---

## 12-2. Retention Period — 히스토리는 영원하지 않다

**Retention Period 는 종료된(Closed) 워크플로우의 히스토리를 보관하는 기간**입니다. 진행 중인(Running) 워크플로우에는 적용되지 않습니다 — 3년 돌아가는 워크플로우도 안전합니다.

- 기본값: **72시간**
- 최소: 1일
- 권장: 최소 7일, 사후 분석이 중요하면 **30~90일**

Retention 이 지나면 히스토리가 삭제됩니다. 그러면:

```bash
temporal workflow show -w order-0042 --namespace orders
```

**결과** (Retention 초과)
```
Error: unable to get workflow execution history:
  workflow execution not found for workflow id: order-0042
('workflow execution already completed' or retention period expired)
Error Details: NotFound
```

**사후 분석이 아예 불가능해집니다.** 장애가 금요일 밤에 나고 월요일에 조사를 시작하면, 72시간 Retention 에서는 이미 늦습니다.

Retention 변경:

```bash
temporal operator namespace update --namespace orders --retention 720h   # 30일
temporal operator namespace describe --namespace orders | grep Retention
```

**결과**
```
Config.WorkflowExecutionRetentionTtl              720h0m0s
```

### Step 11 과의 연결 — 리플레이 히스토리는 만료 전에 아카이빙

Step 11 의 `WorkflowReplayer` 테스트는 **운영 히스토리 파일**을 필요로 합니다. 그 파일은 Retention 안에 덤프해야 합니다.

```bash
# 매일 새벽 크론
0 4 * * *  /opt/app/scripts/dump-histories.sh >> /var/log/dump-histories.log 2>&1
```

**결과**
```
$ cat /var/log/dump-histories.log
[2026-07-20 04:00:01] dumped order-1001 (18432 bytes)
[2026-07-20 04:00:02] dumped order-1002-saga (31067 bytes)
[2026-07-20 04:00:03] 3 histories written to src/test/resources/histories/
```

### Archival — 만료된 히스토리를 외부 저장소로

Archival 을 켜면 Retention 만료 시 삭제 대신 **S3/GCS/파일시스템으로 이관**합니다.

```bash
temporal operator namespace update --namespace orders \
  --history-archival-state enabled \
  --history-uri "s3://temporal-archival-prod/orders"
```

**결과**
```
Namespace orders successfully updated.
```

```bash
temporal operator namespace describe --namespace orders | grep Archival
```

**결과**
```
Config.HistoryArchivalState                       Enabled
Config.HistoryArchivalUri                         s3://temporal-archival-prod/orders
Config.VisibilityArchivalState                    Disabled
```

아카이브된 히스토리는 `temporal workflow show` 로 조회는 되지만 **Visibility 검색(`workflow list`)에는 안 걸립니다.** 즉 workflowId 를 정확히 알아야 꺼낼 수 있습니다. 검색까지 필요하면 Visibility Archival 도 켜야 합니다.

> ⚠️ **함정 — Retention 72시간에서 3일 지난 장애는 조사 불가**
> "월요일 아침에 지난주 금요일 주문 건을 확인해 달라"는 요청이 오면 이미 늦습니다. `workflow show` 도 `workflow describe` 도 NotFound 입니다.
> 워크플로우가 **성공적으로 끝난 것**도 사라집니다. "성공했으니 로그도 없다"는 상태가 됩니다.
> 애플리케이션 로그에는 워크플로우 내부 결정 과정이 없으므로, 히스토리가 사라지면 "왜 이 액티비티가 3번 재시도됐는지" 같은 질문에 영영 답할 수 없습니다.
> **prod 는 최소 30일**로 잡고, 그래도 부족하면 Archival 을 켜세요. 스토리지 비용보다 조사 불가의 비용이 훨씬 큽니다.

---

## 12-3. `temporal` CLI 레퍼런스

### workflow list — 검색

```bash
temporal workflow list \
  --query "WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'"
```

**결과**
```
  Status   WorkflowId              Name           StartTime             CloseTime
  Running  order-1001              OrderWorkflow  2026-07-20T09:12:04Z
  Running  order-1004              OrderWorkflow  2026-07-20T09:18:33Z
  Running  order-1007              OrderWorkflow  2026-07-20T09:21:57Z
  Running  order-1011              OrderWorkflow  2026-07-20T09:24:02Z
```

쿼리 언어는 SQL 유사 문법입니다(List Filter). `AND`, `OR`, `=`, `!=`, `>`, `<`, `BETWEEN`, `IN`, `STARTS_WITH` 를 지원합니다.

### workflow count — 개수만

목록이 필요 없고 개수만 알고 싶을 때 씁니다. `list` 보다 훨씬 가볍습니다.

```bash
temporal workflow count --query "WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'"
```

**결과**
```
Total: 1247
```

### workflow describe — 현재 상태

```bash
temporal workflow describe -w order-1001
```

**결과**
```
Execution Info:
  Workflow Id       order-1001
  Run Id            5c2f8e1a-6b3d-4c9f-8a71-2d0e4f6a9b13
  Type              OrderWorkflow
  Namespace         orders
  Task Queue        ORDER_TASK_QUEUE
  Start Time        2026-07-20 09:12:04 +0000 UTC
  Close Time        2026-07-20 09:12:09 +0000 UTC
  Status            COMPLETED
  History Length    18
  History Size      2451

Result:
  Status            COMPLETED
  Output            ["order-1001 COMPLETED"]
```

진행 중이고 뭔가 막혀 있으면 **Pending Activities** 섹션이 붙습니다. 이게 진단의 출발점입니다(12-8).

**결과** (막힌 워크플로우 — 발췌)
```
  Workflow Id       order-1004
  Status            RUNNING
  History Length    11

Pending Activities:
  ActivityId              2
  ActivityType            ShippingActivity_requestShipment
  State                   Started
  Attempt                 7
  MaximumAttempts         10
  ScheduledTime           2026-07-20 09:19:02 +0000 UTC
  LastStartedTime         2026-07-20 09:33:41 +0000 UTC
  ExpirationTime          2026-07-20 10:19:02 +0000 UTC
  LastFailure             {"message":"connect timed out","source":"JavaSDK",
                           "applicationFailureInfo":{"type":"SocketTimeoutException"}}
  LastWorkerIdentity      41@worker-7f9c8d-xk2mq@
```

`Attempt 7 / MaximumAttempts 10`, `LastFailure: connect timed out`. **배송 API 가 죽어 있고 재시도 중**임이 한눈에 보입니다.

### workflow show — 이벤트 히스토리

```bash
temporal workflow show -w order-1001
```

**결과**
```
Progress:
  ID          Time                     Type
   1  2026-07-20T09:12:04Z  WorkflowExecutionStarted
   2  2026-07-20T09:12:04Z  WorkflowTaskScheduled
   3  2026-07-20T09:12:04Z  WorkflowTaskStarted
   4  2026-07-20T09:12:04Z  WorkflowTaskCompleted
   5  2026-07-20T09:12:04Z  ActivityTaskScheduled
   6  2026-07-20T09:12:04Z  ActivityTaskStarted
   7  2026-07-20T09:12:05Z  ActivityTaskCompleted
   ...
  17  2026-07-20T09:12:09Z  ActivityTaskCompleted
  18  2026-07-20T09:12:09Z  WorkflowExecutionCompleted

Result:
  Status: COMPLETED
  Output: ["order-1001 COMPLETED"]
```

이벤트 상세(입력·출력·실패 내용)를 보려면 JSON 으로 뽑습니다.

```bash
temporal workflow show -w order-1001 --output json | jq '.events[4]'
```

**결과**
```json
{
  "eventId": "5",
  "eventTime": "2026-07-20T09:12:04.512Z",
  "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
  "taskId": "1048823",
  "activityTaskScheduledEventAttributes": {
    "activityId": "1",
    "activityType": { "name": "PaymentActivity_charge" },
    "taskQueue": { "name": "ORDER_TASK_QUEUE", "kind": "TASK_QUEUE_KIND_NORMAL" },
    "input": { "payloads": [ { "data": "IjEwMDEi" }, { "data": "MzkwMDA=" } ] },
    "startToCloseTimeout": "10s",
    "retryPolicy": {
      "initialInterval": "1s", "backoffCoefficient": 2, "maximumAttempts": 3
    }
  }
}
```

이 JSON 이 Step 11 의 리플레이 테스트에 그대로 들어가는 그 파일입니다.

### workflow signal / query

```bash
temporal workflow signal -w order-1004 --name cancelRequested --input '"고객 변심"'
```

**결과**
```
Signal workflow succeeded.
  WorkflowId  order-1004
  RunId       a91c3f77-2ed8-4b19-9f02-51ac7e3b8d40
```

```bash
temporal workflow query -w order-1004 --type getStatus
```

**결과**
```
Query result:
  QueryResult  "CANCELED"
```

### workflow reset — 특정 시점으로 되감기

**reset 은 히스토리의 어느 지점부터 워크플로우를 다시 실행**합니다. 새 RunId 가 만들어지고, 지정 지점 이후의 이벤트는 버려집니다.

```bash
temporal workflow reset -w order-1004 --event-id 4 --reason "배송 API 복구 후 재시도"
```

**결과**
```
Reset workflow succeeded.
  WorkflowId  order-1004
  RunId       d7e2b840-16fc-4a53-b9e7-3c1f8a05e622   ← 새 RunId
```

`--event-id` 는 반드시 `WorkflowTaskCompleted`(또는 `WorkflowTaskStarted`) 이벤트여야 합니다. 액티비티 이벤트 ID 를 주면 거부됩니다.

**결과** (잘못된 event-id)
```
Error: reset point event id 5 is not a valid WorkflowTaskCompleted event
Error Details: InvalidArgument
```

이벤트 ID 를 고르기 귀찮으면 `--type LastWorkflowTask` 처럼 타입으로 지정합니다. `--type` 값:

| 값 | 의미 | 쓰임 |
|---|---|---|
| `FirstWorkflowTask` | 맨 처음부터 완전히 다시 | 입력만 맞고 전부 다시 하고 싶을 때 |
| `LastWorkflowTask` | 마지막으로 성공한 Workflow Task 지점 | **버그 픽스 배포 후 멈춘 워크플로우 재개 — 가장 흔함** |
| `LastContinuedAsNew` | 마지막 Continue-As-New 지점 | 장기 실행 워크플로우 |

`temporal workflow reset-batch` 로 쿼리에 걸린 워크플로우를 한 번에 되감을 수도 있습니다(12-8 에서 사용).

> ⚠️ **함정 — reset 은 액티비티 부수 효과를 되돌리지 않는다**
> reset 은 **히스토리를 자를 뿐**입니다. 이미 실행된 결제 액티비티가 청구한 39,000원은 그대로입니다. 되감은 뒤 다시 결제하면 **이중 청구**됩니다.
> reset 지점은 반드시 "부수 효과가 없는 지점"으로 잡거나, 액티비티가 멱등(idempotency key 사용)해야 합니다. Step 04 에서 액티비티를 멱등하게 만든 이유가 여기서 회수됩니다.

### task-queue describe — Worker 가 붙어 있나

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE
```

**결과**
```
Workflow Poller Info:
  Identity                          LastAccessTime          RatePerSecond
  41@worker-7f9c8d-xk2mq@           2026-07-20T09:34:02Z    100000
  41@worker-7f9c8d-p8vn4@           2026-07-20T09:34:02Z    100000

Activity Poller Info:
  Identity                          LastAccessTime          RatePerSecond
  41@worker-7f9c8d-xk2mq@           2026-07-20T09:34:02Z    100000
  41@worker-7f9c8d-p8vn4@           2026-07-20T09:34:02Z    100000
```

Worker 2대가 워크플로우·액티비티 양쪽을 폴링 중입니다. `LastAccessTime` 이 현재 시각과 가까워야 정상입니다.

**결과** (Worker 가 없을 때)
```
Workflow Poller Info:
  Identity  LastAccessTime  RatePerSecond

Activity Poller Info:
  Identity  LastAccessTime  RatePerSecond
```

**비어 있습니다.** 이 상태면 워크플로우는 영원히 Running 인 채로 아무 일도 일어나지 않습니다. 12-8 의 2단계에서 이걸 봅니다.

---

## 12-4. terminate vs cancel

운영 중 워크플로우를 멈추는 방법은 두 가지입니다. **완전히 다릅니다.**

```bash
temporal workflow cancel -w order-1004 --reason "고객 요청 취소"
```

**결과**
```
Cancel workflow succeeded.
  WorkflowId  order-1004
  RunId       a91c3f77-2ed8-4b19-9f02-51ac7e3b8d40
```

```bash
temporal workflow terminate -w order-1007 --reason "결정성 깨져 진행 불가 (INC-2291)"
```

**결과**
```
Terminate workflow succeeded.
  WorkflowId  order-1007
  RunId       b2d8e415-7c60-49a3-8e12-0f5a6c9d3e78
```

| | cancel | terminate |
|---|---|---|
| 워크플로우 코드가 아는가 | **예** — `CanceledFailure` 가 던져지고 catch 가능 | **아니오** — 코드가 실행될 기회조차 없음 |
| 정리 / 보상 실행 | **가능** | **불가능** |
| 히스토리 이벤트 | `WorkflowExecutionCancelRequested` → (코드 실행) → `WorkflowExecutionCanceled` | `WorkflowExecutionTerminated` (즉시, 단일 이벤트) |
| 실행 중 액티비티 | 취소 요청이 전달됨(하트비트로 감지) | 그냥 버려짐 — 액티비티는 계속 돌다가 결과를 못 돌려줌 |
| 최종 Status | `CANCELED` | `TERMINATED` |
| 되돌리기 | reset 가능 | reset 가능하나 보상 안 된 상태에서 시작 |
| 언제 쓰나 | **정상적인 중단** — 고객 취소, 정책 변경 | **최후 수단** — 코드가 망가져 진행이 아예 불가할 때 |

히스토리로 보면 차이가 확연합니다.

**cancel 의 히스토리**
```
  ...
  11  2026-07-20T09:30:12Z  WorkflowExecutionCancelRequested
  15  2026-07-20T09:30:12Z  ActivityTaskScheduled   InventoryActivity_release   ← 보상!
  16  2026-07-20T09:30:13Z  ActivityTaskCompleted
  17  2026-07-20T09:30:13Z  ActivityTaskScheduled   PaymentActivity_refund      ← 보상!
  18  2026-07-20T09:30:14Z  ActivityTaskCompleted
  19  2026-07-20T09:30:14Z  WorkflowExecutionCanceled

Result:
  Status: CANCELED
```

**terminate 의 히스토리**
```
  ...
  11  2026-07-20T09:31:40Z  WorkflowExecutionTerminated

Result:
  Status: TERMINATED
```

**11번 이벤트 하나로 끝났습니다.** 결제는 이미 됐고 재고는 잡혀 있는데, 보상이 하나도 안 돌았습니다.

> ⚠️ **함정 — terminate 는 보상을 안 돌린다**
> "워크플로우가 이상해서 일단 terminate 했습니다" 는 운영에서 가장 흔한 사고입니다.
> `order-1007` 은 이미 39,000원이 결제됐고 재고 2개가 예약된 상태였습니다. terminate 하는 순간 그 워크플로우는 사라지고, **환불도 재고 해제도 영원히 실행되지 않습니다.** 아무도 그 사실을 모릅니다 — 에러 로그조차 없습니다.
> 몇 주 뒤 재고 실사에서 "장부상 2개 부족"이 발견되고, 고객이 "결제됐는데 물건이 안 왔다"고 문의하고 나서야 드러납니다.
> **원칙**: 먼저 `cancel` 을 시도하세요. cancel 이 먹지 않는 경우(=Worker 가 아예 없거나 코드가 리플레이 단계에서 죽어 Workflow Task 를 처리 못 하는 경우)에만 terminate 를 쓰고, **terminate 하기 전에 `temporal workflow describe` 로 어디까지 진행됐는지 반드시 기록**한 뒤 보상을 수동으로 처리하세요.

### cancel 을 받았을 때 보상을 돌리는 코드

cancel 을 받으면 워크플로우의 현재 대기 지점에서 `CanceledFailure` 가 던져집니다. 이걸 잡아 보상하면 됩니다 — 그런데 **그냥 잡아서 보상 액티비티를 부르면 동작하지 않습니다.**

```java
} catch (CanceledFailure e) {
    // ↓ 이 액티비티들은 시작하자마자 취소됩니다
    if (reservationId != null) inventory.release(reservationId);
    if (paymentId != null)     payment.refund(paymentId);
    throw e;
}
```

**결과** (히스토리)
```
  11  2026-07-20T09:35:02Z  WorkflowExecutionCancelRequested
  13  2026-07-20T09:35:02Z  ActivityTaskScheduled   InventoryActivity_release
  14  2026-07-20T09:35:02Z  ActivityTaskCancelRequested                 ← 즉시 취소
  15  2026-07-20T09:35:02Z  ActivityTaskCanceled
  16  2026-07-20T09:35:02Z  WorkflowExecutionCanceled

Result:
  Status: CANCELED
```

**보상 액티비티가 시작하자마자 취소됐습니다.** 워크플로우 코드 전체는 하나의 **CancellationScope** 안에서 돕니다. cancel 이 오면 그 스코프 전체가 "취소됨" 상태가 되고, **취소된 스코프에서 새로 띄우는 액티비티는 태어나자마자 취소**됩니다. `catch` 블록도 같은 스코프 안입니다.

해결은 **분리된(detached) 스코프**에서 보상을 실행하는 것입니다.

```java
} catch (CanceledFailure e) {
    // 부모 스코프의 취소에 영향받지 않는 별도 스코프
    CancellationScope compensation = Workflow.newDetachedCancellationScope(() -> {
        if (reservationId[0] != null) inventory.release(reservationId[0]);
        if (paymentId[0] != null)     payment.refund(paymentId[0]);
        notification.notifyCustomer(req.orderId(), "주문이 취소되었습니다");
    });
    compensation.run();     // ★ 이 줄이 없으면 보상이 통째로 사라집니다
    throw e;                // 워크플로우는 CANCELED 로 마무리
}
```

(람다에서 참조하려면 `paymentId` / `reservationId` 를 배열이나 필드로 감싸야 합니다. 전체 코드는 `Practice.java` 의 `CorrectOrderWorkflowImpl` 에 있습니다.)

**결과** (히스토리)
```
  11  2026-07-20T09:38:20Z  WorkflowExecutionCancelRequested
  13  2026-07-20T09:38:20Z  ActivityTaskScheduled   InventoryActivity_release
  14  2026-07-20T09:38:21Z  ActivityTaskCompleted
  15  2026-07-20T09:38:21Z  ActivityTaskScheduled   PaymentActivity_refund
  16  2026-07-20T09:38:22Z  ActivityTaskCompleted
  17  2026-07-20T09:38:22Z  ActivityTaskScheduled   NotificationActivity_notifyCustomer
  18  2026-07-20T09:38:22Z  ActivityTaskCompleted
  19  2026-07-20T09:38:22Z  WorkflowExecutionCanceled

Result:
  Status: CANCELED
```

보상 3개가 모두 실행되고 CANCELED 로 끝났습니다.

> ⚠️ **함정 — detached scope 없이 보상하면 조용히 실패한다**
> 위 두 히스토리를 비교하세요. **둘 다 Status 가 CANCELED 입니다.** 에러도, 실패 이벤트도 없습니다.
> 잘못된 버전은 `ActivityTaskCanceled` 하나만 남기고 정상적으로 끝난 것처럼 보입니다. 모니터링 대시보드에서는 "취소 처리 완료"로 집계됩니다.
> 이것이 이 코스가 말하는 **"에러 없이 조용히 잘못 동작하는 코드"** 의 전형입니다. 로컬 테스트에서도 취소 테스트를 대충 짜면(결과 Status 만 단언하면) 통과합니다.
> Step 11 의 방식대로 `verify(payment).refund(...)` 까지 검증해야 잡힙니다.

> 💡 **실무 팁 — detached scope 에는 타임아웃을 짧게**
> 보상 스코프가 너무 오래 걸리면 워크플로우가 CANCELED 로 넘어가지 못하고 매달립니다. 보상 액티비티의 `startToCloseTimeout` 과 `maximumAttempts` 를 정방향보다 짧고 적게 잡으세요. 보상은 "최선 노력"이고, 실패하면 별도 알림으로 사람에게 넘기는 편이 낫습니다.

---

## 12-5. Search Attributes

`temporal workflow list` 의 `--query` 는 **Search Attribute** 위에서 동작합니다. 기본 제공되는 것들:

| 이름 | 타입 | 내용 |
|---|---|---|
| `WorkflowId` | Keyword | 워크플로우 ID |
| `RunId` | Keyword | 실행 ID |
| `WorkflowType` | Keyword | 워크플로우 타입명 |
| `ExecutionStatus` | Keyword | Running / Completed / Failed / Canceled / Terminated / ContinuedAsNew / TimedOut |
| `StartTime` / `CloseTime` | Datetime | 시작·종료 시각 |
| `ExecutionTime` | Datetime | 실행 예정 시각(스케줄된 경우) |
| `TaskQueue` | Keyword | Task Queue 이름 |
| `HistoryLength` | Int | 이벤트 개수 |

기본 제공만으로는 "고객 C-77 의 주문을 전부 보여줘"를 못 합니다. **커스텀 Search Attribute** 가 필요합니다.

```bash
temporal operator search-attribute create --name CustomerId --type Keyword --namespace orders
temporal operator search-attribute create --name OrderAmount --type Int --namespace orders
temporal operator search-attribute create --name OrderStage --type Keyword --namespace orders
```

**결과**
```
Search attribute CustomerId successfully registered.
Search attribute OrderAmount successfully registered.
Search attribute OrderStage successfully registered.
```

```bash
temporal operator search-attribute list --namespace orders
```

**결과** (발췌)
```
  Name              Type
  CloseTime         Datetime
  CustomerId        Keyword       ← 방금 추가
  ExecutionStatus   Keyword
  OrderAmount       Int           ← 방금 추가
  OrderStage        Keyword       ← 방금 추가
  WorkflowType      Keyword
```

### 시작 시점에 설정

```java
SearchAttributes sa = SearchAttributes.newBuilder()
    .set(SearchAttributeKey.forKeyword("CustomerId"), req.customerId())
    .set(SearchAttributeKey.forLong("OrderAmount"), req.amount())
    .build();

OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
    WorkflowOptions.newBuilder()
        .setTaskQueue(ORDER_TASK_QUEUE)
        .setWorkflowId("order-" + req.orderId())
        .setTypedSearchAttributes(sa)          // ← 1.20+ 의 타입 안전 API
        .build());
```

> 💡 SDK 1.20 이전에는 `setSearchAttributes(Map<String, Object>)` 였습니다. 이 API 는 deprecated 이고 타입 오류가 런타임에야 드러납니다. 1.22.3 에서는 **`setTypedSearchAttributes`** 를 쓰세요.

### 실행 중에 갱신

워크플로우가 진행하면서 단계를 기록합니다.

```java
static final SearchAttributeKey<String> STAGE = SearchAttributeKey.forKeyword("OrderStage");

Workflow.upsertTypedSearchAttributes(STAGE.valueSet("PAYMENT"));
String paymentId = payment.charge(req.orderId(), req.amount());

Workflow.upsertTypedSearchAttributes(STAGE.valueSet("INVENTORY"));
String reservationId = inventory.reserve(req.orderId(), req.sku(), req.qty());

Workflow.upsertTypedSearchAttributes(STAGE.valueSet("SHIPPING"));
shipping.requestShipment(req.orderId(), req.address());
```

`upsertTypedSearchAttributes` 는 히스토리에 `UpsertWorkflowSearchAttributes` 이벤트를 남깁니다. 즉 **결정적**입니다 — 리플레이해도 같은 순서로 실행됩니다.

### 조회

```bash
temporal workflow list --query "CustomerId='C-77'" --namespace orders
```

**결과**
```
  Status     WorkflowId    Name           StartTime             CloseTime
  Completed  order-1001    OrderWorkflow  2026-07-20T09:12:04Z  2026-07-20T09:12:09Z
  Running    order-1004    OrderWorkflow  2026-07-20T09:18:33Z
  Completed  order-0997    OrderWorkflow  2026-07-19T22:04:51Z  2026-07-19T22:05:02Z
```

```bash
temporal workflow count \
  --query "OrderStage='SHIPPING' AND ExecutionStatus='Running'" --namespace orders
```

**결과**
```
Total: 38
```

**"지금 배송 단계에서 대기 중인 주문이 38건"** 을 한 줄로 알아냅니다. 이게 커스텀 Search Attribute 의 가치입니다.

> ⚠️ **함정 — Search Attribute 는 인덱싱용이지 상태 저장용이 아니다**
> Search Attribute 를 워크플로우 상태 저장소처럼 쓰면 안 됩니다.
> - **비동기 반영**: Elasticsearch 인덱싱이 수백 ms~수 초 지연됩니다. upsert 직후 `workflow list` 로 조회하면 아직 옛 값이 나옵니다.
> - **크기 제한**: 값 하나당 기본 2KB, 워크플로우당 총합 40KB 제한이 있습니다. 넘으면 워크플로우가 실패합니다.
> - **비용**: 모든 upsert 가 히스토리 이벤트를 하나씩 늘립니다. 루프 안에서 매번 upsert 하면 히스토리가 폭증합니다.
>
> 워크플로우 내부 상태는 **필드에 담고 Query 로 노출**하세요(Step 07). Search Attribute 는 "수천 건 중에 조건에 맞는 것을 찾는" 용도에만 씁니다.
> 판단 기준: **`list` 로 검색할 일이 있으면 Search Attribute, 특정 한 건의 상세를 볼 일이면 Query.**

---

## 12-6. 메트릭

Java SDK 는 Micrometer 를 통해 Prometheus 로 메트릭을 내보냅니다.

```java
Scope scope = new RootScopeBuilder()
    .reporter(new MicrometerClientStatsReporter(
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)))
    .reportEvery(Duration.ofSeconds(10));

WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
    WorkflowServiceStubsOptions.newBuilder()
        .setTarget("127.0.0.1:7233")
        .setMetricsScope(scope)
        .build());
```

```bash
curl -s localhost:8080/actuator/prometheus | grep temporal_workflow_task
```

**결과**
```
temporal_workflow_task_execution_failed_total{namespace="orders",task_queue="ORDER_TASK_QUEUE",workflow_type="OrderWorkflow"} 143.0
temporal_workflow_task_schedule_to_start_latency_seconds{quantile="0.95",namespace="orders",task_queue="ORDER_TASK_QUEUE"} 4.812
temporal_workflow_task_execution_latency_seconds{quantile="0.95",namespace="orders"} 0.031
```

### 반드시 봐야 할 지표

| 지표 | 오르면 무엇을 의심 | 대응 |
|---|---|---|
| `temporal_workflow_task_execution_failed` | **`NonDeterministicException`** — 코드와 히스토리가 어긋남. 버저닝 사고의 1차 신호 | 배포 롤백. Step 11 의 리플레이 테스트로 원인 확인 |
| `temporal_workflow_task_schedule_to_start_latency` | Task 가 큐에 쌓이는데 Worker 가 못 가져감 = **Worker 부족** | Worker 증설 또는 `maxConcurrentWorkflowTaskExecutionSize` / 폴러 수 증가 (12-7) |
| `temporal_activity_schedule_to_start_latency` | 액티비티 큐 적체. Worker 부족이거나 액티비티가 오래 걸려 슬롯을 다 씀 | `maxConcurrentActivityExecutionSize` 증가, 또는 무거운 액티비티를 별도 Task Queue 로 분리 |
| `temporal_sticky_cache_miss` | 워크플로우가 캐시에서 밀려나 **전체 히스토리를 다시 리플레이** 중 | `workflowCacheSize` 증가 (+ `maxWorkflowThreadCount` 동반 증가) |
| `temporal_worker_task_slots_available` | **낮을수록 위험.** 0에 붙으면 Worker 가 포화 | Worker 증설. 이 값이 0이면 위 latency 들도 같이 오릅니다 |
| `temporal_activity_execution_failed` | 외부 시스템 장애. 재시도로 흡수되는 중일 수 있음 | `workflow describe` 의 Pending Activities 에서 `LastFailure` 확인 |
| `temporal_long_request_failure` | SDK ↔ 서버 gRPC 실패. 네트워크나 서버 과부하 | 서버 측 지표 확인 |

> 💡 **실무 팁 — 알람은 이 세 개면 충분히 시작할 수 있습니다**
> 1. `rate(temporal_workflow_task_execution_failed_total[5m]) > 0` — **즉시 알람.** 정상 운영에서는 0이어야 합니다. 0이 아니면 거의 항상 결정성 문제입니다.
> 2. `temporal_workflow_task_schedule_to_start_latency{quantile="0.95"} > 1` — Worker 부족.
> 3. `temporal_worker_task_slots_available == 0` (5분 지속) — 포화.

---

## 12-7. Worker 튜닝

`WorkerOptions` 와 `WorkerFactoryOptions` 의 주요 항목입니다 (SDK 1.22.3 기준).

| 옵션 | 소속 | 기본값 | 의미 |
|---|---|---|---|
| `maxConcurrentWorkflowTaskExecutionSize` | WorkerOptions | 200 | 동시에 처리할 Workflow Task 수 |
| `maxConcurrentActivityExecutionSize` | WorkerOptions | 200 | 동시에 실행할 Activity 수 |
| `maxConcurrentLocalActivityExecutionSize` | WorkerOptions | 200 | 동시 Local Activity 수 |
| `maxConcurrentWorkflowTaskPollers` | WorkerOptions | 5 | Workflow Task 폴러 스레드 수 |
| `maxConcurrentActivityTaskPollers` | WorkerOptions | 5 | Activity Task 폴러 스레드 수 |
| `maxTaskQueueActivitiesPerSecond` | WorkerOptions | 무제한 | **Task Queue 전체**의 초당 액티비티 시작 수 (전역 레이트 리밋) |
| `maxWorkerActivitiesPerSecond` | WorkerOptions | 무제한 | **이 Worker 하나**의 초당 액티비티 시작 수 |
| `maxWorkflowThreadCount` | WorkerFactoryOptions | 600 | 워크플로우 코드를 돌리는 스레드 총수 |
| `workflowCacheSize` | WorkerFactoryOptions | 600 | 메모리에 캐시할 워크플로우 인스턴스 수 (Sticky Cache) |

```java
WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
    .setWorkflowCacheSize(1200)
    .setMaxWorkflowThreadCount(2400)      // cacheSize 의 2배 이상
    .build();
WorkerFactory factory = WorkerFactory.newInstance(client, factoryOptions);

WorkerOptions workerOptions = WorkerOptions.newBuilder()
    .setMaxConcurrentWorkflowTaskExecutionSize(400)
    .setMaxConcurrentActivityExecutionSize(100)     // 결제 API 부하를 고려해 낮춤
    .setMaxConcurrentWorkflowTaskPollers(10)
    .setMaxConcurrentActivityTaskPollers(10)
    .setMaxTaskQueueActivitiesPerSecond(50.0)       // 결제 게이트웨이 보호
    .build();

Worker worker = factory.newWorker(ORDER_TASK_QUEUE, workerOptions);
```

### `workflowCacheSize` 와 `maxWorkflowThreadCount` 의 관계

이 둘은 **반드시 함께** 조정해야 합니다.

Sticky Cache 는 실행 중인 워크플로우 인스턴스를 메모리에 유지해, 다음 Workflow Task 가 왔을 때 **히스토리를 처음부터 리플레이하지 않고** 이어서 실행하게 합니다. 그런데 캐시된 워크플로우는 **자기 스레드를 점유한 채** 대기합니다. `Workflow.sleep()` 이나 액티비티 완료를 기다리는 지점에서 스레드가 멈춰 있는 구조이기 때문입니다.

즉 `workflowCacheSize = 600`, `maxWorkflowThreadCount = 600` 으로 두면 캐시가 가득 차는 순간 스레드가 정확히 소진됩니다. 여기에 자식 워크플로우나 `Async.function` 으로 스레드를 추가로 쓰는 워크플로우가 하나라도 있으면 데드락입니다.

**결과** (스레드 고갈 시 Worker 로그)
```
10:22:41.907 [workflow-method-order-2288-1] ERROR i.t.i.w.WorkflowExecutionHandler -
  io.temporal.internal.sync.PotentialDeadlockException:
  Potential deadlock detected: workflow thread "workflow-method-order-2288-1"
  didn't yield control for over a second.
  Failure to yield can be caused by blocking calls or by exhausting the workflow thread pool.
  Current thread pool: 600/600 in use, 0 available.
	at io.temporal.internal.sync.WorkflowThreadContext.yield
```

`600/600 in use, 0 available` — 스레드 풀이 바닥났습니다. 워크플로우들이 서로를 기다리며 멈춥니다.

**규칙**: `maxWorkflowThreadCount ≥ workflowCacheSize × (워크플로우당 평균 스레드 수 + 1)`

- 단순 워크플로우(자식 없음, `Async` 없음): 배수 2 면 충분
- 자식 워크플로우나 `Async.function` 을 여러 개 쓰는 워크플로우: 배수 3~4

반대로 **`workflowCacheSize` 를 너무 낮추면** 캐시 미스가 늘어 매번 전체 히스토리를 리플레이합니다. 히스토리 이벤트가 5,000개인 워크플로우라면 Workflow Task 하나당 5,000개를 다시 돌립니다.

**결과** (cacheSize 부족 시)
```
temporal_sticky_cache_miss_total{namespace="orders"} 8421.0
temporal_workflow_task_execution_latency_seconds{quantile="0.95"} 2.841
```

캐시 미스 8,421건, Workflow Task 처리 p95 가 **2.841초**입니다. cacheSize 를 600 → 2000 으로 올린 뒤:

**결과**
```
temporal_sticky_cache_miss_total{namespace="orders"} 37.0
temporal_workflow_task_execution_latency_seconds{quantile="0.95"} 0.028
```

**2.841초 → 0.028초. 약 100배**입니다. 물론 `maxWorkflowThreadCount` 도 4000 으로 함께 올렸습니다.

### `schedule_to_start_latency` 가 높을 때의 튜닝 순서

이 지표가 오르면 순서대로 확인합니다. **위에서부터** 하세요 — 아래 항목부터 손대면 원인을 못 찾고 파라미터만 커집니다.

1. **Worker 가 살아 있는가** — `temporal task-queue describe` 로 Poller 목록 확인. 비어 있으면 튜닝이 아니라 배포 문제입니다.
2. **슬롯이 남아 있는가** — `temporal_worker_task_slots_available` 확인. 0이면 `maxConcurrentActivityExecutionSize` / `maxConcurrentWorkflowTaskExecutionSize` 를 올리거나 **Worker 를 증설**합니다. 대개 증설이 정답입니다.
3. **폴러가 부족한가** — 슬롯은 남는데 latency 가 높으면 폴러가 부족한 것입니다. `maxConcurrentActivityTaskPollers` 를 5 → 10~20 으로 올립니다.
4. **워크플로우가 느린가** — `temporal_workflow_task_execution_latency` 가 높으면 워크플로우 코드 자체가 느립니다. 캐시 미스(`temporal_sticky_cache_miss`)를 먼저 확인하고, 그다음 워크플로우 안의 무거운 연산을 액티비티로 빼냅니다.
5. **한 Task Queue 에 성격이 다른 일이 섞였는가** — 3초짜리 결제와 5분짜리 리포트 생성이 같은 큐에 있으면 리포트가 슬롯을 다 차지합니다. **Task Queue 를 분리**하고 Worker 도 나눕니다. 이게 가장 근본적인 해결인 경우가 많습니다.

> 💡 **실무 팁 — 폴러 수보다 Worker 대수를 먼저**
> 폴러를 늘리면 서버 gRPC 호출이 늘어 서버 부하가 커집니다. 한 Worker 프로세스의 파라미터를 키우는 것보다 **Worker 프로세스를 늘리는 편**이 장애 격리 면에서도 낫습니다. 파라미터 튜닝은 "이미 여러 대인데도 부족할 때" 하세요.

---

## 12-8. 운영 플레이북 — "Running 인데 안 움직인다"

가장 흔한 신고입니다. 다섯 단계로 진단합니다.

### 1단계 — `describe` 로 무엇을 기다리는지 본다

```bash
temporal workflow describe -w order-1004 --namespace orders
```

출력은 12-3 의 "막힌 워크플로우" 예시와 같습니다. 분기는 이렇습니다.

- `Pending Activities` 에 항목이 있고 `Attempt` 가 계속 증가 → **외부 시스템 장애**. 4단계로.
- `Pending Activities` 가 있는데 `State: Scheduled` 이고 `Attempt: 1` 에서 멈춤 → **Worker 가 안 가져가는 중**. 2단계로.
- `Pending Activities` 가 비어 있고 `Pending Workflow Task` 만 있음 → **워크플로우 코드가 리플레이에서 실패 중**. 3단계로.

**결과** (워크플로우 태스크가 막힌 경우)
```
  Status            RUNNING
  History Length    9

Pending Workflow Task:
  State                   Started
  ScheduledTime           2026-07-20 09:41:02 +0000 UTC
  StartedTime             2026-07-20 10:14:55 +0000 UTC
  Attempt                 214                                ← 214회 재시도 중
```

`Attempt 214` — 이건 거의 확실히 `NonDeterministicException` 입니다.

### 2단계 — `task-queue describe` 로 Worker 존재 확인

```bash
temporal task-queue describe --task-queue ORDER_TASK_QUEUE --namespace orders
```

**결과** (Worker 없음)
```
Workflow Poller Info:
  Identity  LastAccessTime  RatePerSecond

Activity Poller Info:
  Identity  LastAccessTime  RatePerSecond
```

Poller 가 비었으면 원인은 셋 중 하나입니다.

- Worker 프로세스가 죽었다 → 배포 상태 확인
- Worker 가 **다른 Task Queue 이름**으로 붙었다 → 오타나 환경변수 실수. 매우 흔합니다
- Worker 가 **다른 Namespace** 에 붙었다 → `TEMPORAL_NAMESPACE` 확인

`LastAccessTime` 이 몇 분 전에서 멈춰 있으면 Worker 는 살아 있으나 폴링을 못 하는 상태입니다(스레드 고갈 등). 3~4단계로.

### 3단계 — Worker 로그

```bash
kubectl logs deploy/order-worker --since=10m | grep -E "NonDeterministic|Deadlock|ERROR"
```

**결과**
```
10:14:55.412 [workflow-method-order-1004-3] ERROR i.t.i.w.WorkflowExecutionHandler -
  io.temporal.worker.NonDeterministicException: History event is not compatible with
  the command produced by the workflow code.
  HistoryEvent[eventId=8, eventType=ACTIVITY_TASK_SCHEDULED,
               activityType=InventoryActivity_reserve, activityId=2]
  Command[commandType=SCHEDULE_ACTIVITY_TASK,
          activityType=ShippingActivity_requestShipment, activityId=2]
10:14:56.418 [workflow-method-order-1004-3] ERROR ... (동일 에러 반복)
10:14:58.421 [workflow-method-order-1004-3] ERROR ... (동일 에러 반복)
```

**진단 확정.** 배포한 코드가 진행 중인 워크플로우의 히스토리와 호환되지 않습니다. Step 11 의 리플레이 테스트가 있었다면 CI 에서 잡혔을 사고입니다.

### 4단계 — 메트릭으로 범위 파악

한 건인지 전체인지 확인합니다.

```bash
temporal workflow count --query "ExecutionStatus='Running'" --namespace orders
```

**결과**
```
Total: 1247
```

```bash
curl -s localhost:8080/actuator/prometheus | grep workflow_task_execution_failed
```

**결과**
```
temporal_workflow_task_execution_failed_total{namespace="orders",workflow_type="OrderWorkflow"} 8912.0
```

Running 1,247건에 실패 8,912회 — **한 건이 아니라 전체입니다.** 개별 조치가 아니라 롤백이 답입니다.

### 5단계 — 조치

| 상황 | 조치 |
|---|---|
| NonDeterministicException, 다수 영향 | **이전 버전으로 즉시 롤백.** 워크플로우들이 자동으로 이어서 진행됩니다. 그다음 `Workflow.getVersion()` 을 넣어 다시 배포 |
| NonDeterministicException, 소수 영향 | 롤백 후 재배포. 그래도 안 되는 개별 건은 `reset --type LastWorkflowTask` |
| 외부 시스템 장애 (Pending Activity 재시도 중) | **아무것도 하지 마세요.** 외부 시스템이 복구되면 재시도가 성공합니다. 이게 Temporal 을 쓰는 이유입니다. `maximumAttempts` 소진이 임박하면 그때 개입 |
| 외부 시스템이 장시간 복구 불가 | `temporal workflow cancel` → 보상 실행 → 나중에 재주문 |
| 코드가 완전히 망가져 cancel 도 안 먹음 | `describe` 로 진행 상태를 **기록한 뒤** `terminate`, 보상은 수동 처리 |
| Worker 가 없음 | Worker 배포/설정 수정. 워크플로우는 그대로 두면 Worker 가 붙는 즉시 재개됩니다 |

```bash
# 롤백 후, 멈춰 있던 워크플로우들을 한 번에 재개
temporal workflow reset-batch \
  --query "WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'" \
  --type LastWorkflowTask --reason "INC-2291 롤백 후 재개" --namespace orders
```

**결과**
```
Batch job started.
  JobId  9f1c4e70-3a82-4b56-8d09-27e5f3a1c604

$ temporal batch describe --job-id 9f1c4e70-3a82-4b56-8d09-27e5f3a1c604
  JobId            9f1c4e70-3a82-4b56-8d09-27e5f3a1c604
  State            Completed
  TotalOperations  1247
  CompleteOperations 1247
  FailureOperations  0
```

> 💡 **실무 팁 — 롤백만으로 대개 해결됩니다**
> NonDeterministicException 은 **워크플로우를 망가뜨리지 않습니다.** Workflow Task 가 실패할 뿐이고, 히스토리는 그대로입니다. 옛 코드로 돌아가면 워크플로우들이 아무 일 없었다는 듯 이어서 진행합니다.
> 그래서 이 사고의 정답은 거의 언제나 "빠른 롤백 → 원인 분석 → 버저닝 넣고 재배포" 입니다. 당황해서 terminate 하는 것이 최악입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| Namespace | 최상위 격리 단위. WorkflowId·Task Queue·Search Attribute·Retention 이 모두 여기 종속 |
| 환경 분리 | dev/stg 는 Namespace 로, **prod 는 별도 클러스터** 권장 |
| Retention Period | **종료된** 워크플로우 히스토리 보관 기간. 기본 72h, prod 는 최소 30일 |
| Retention 만료 | `workflow show` 가 NotFound. **사후 분석 불가**. 리플레이용 히스토리는 만료 전 덤프 |
| Archival | 만료 시 삭제 대신 S3/GCS 이관. 단 Visibility 검색은 별도 설정 필요 |
| `workflow list` / `count` | Search Attribute 기반 쿼리. `count` 가 훨씬 가벼움 |
| `workflow describe` | **Pending Activities / Pending Workflow Task** 가 진단의 출발점 |
| `workflow show --output json` | 리플레이 테스트에 쓸 히스토리 파일 |
| `workflow reset` | `--type LastWorkflowTask` 가 실무 표준. **부수 효과는 되돌리지 않음** |
| `task-queue describe` | Poller 목록. 비어 있으면 Worker 없음 |
| **cancel** | 코드가 `CanceledFailure` 를 받음 → **보상 실행 가능**. 정상적인 중단 |
| **terminate** | 단일 `WorkflowExecutionTerminated` 이벤트. **보상 절대 불가**. 최후 수단 |
| detached scope | 취소된 스코프에서는 새 액티비티가 즉시 취소됨 → `Workflow.newDetachedCancellationScope` 필수 |
| Search Attribute | 기본 8종 + 커스텀. `setTypedSearchAttributes` / `upsertTypedSearchAttributes` |
| SA 의 용도 | **인덱싱용**. 비동기 반영·크기 제한·히스토리 비용. 상태는 Query 로 |
| 핵심 메트릭 | `workflow_task_execution_failed`(결정성), `schedule_to_start_latency`(Worker 부족), `sticky_cache_miss`(캐시 부족) |
| `workflowCacheSize` ↔ `maxWorkflowThreadCount` | 캐시된 워크플로우가 스레드를 점유. **함께 올려야** `PotentialDeadlockException` 을 피함 |
| 튜닝 순서 | Worker 존재 → 슬롯 → 폴러 → 워크플로우 속도 → **Task Queue 분리** |
| 진단 5단계 | describe → task-queue describe → Worker 로그 → 메트릭 → 롤백/reset |
| 최우선 원칙 | NonDeterministic 사고의 정답은 **롤백**. terminate 가 최악 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. Namespace 를 생성하고 Retention 을 30일로 바꾸는 명령 작성하기
2. "고객 C-77 의 실패한 10만원 초과 주문"을 찾는 `--query` 작성하기
3. cancel 을 받아 보상을 실행하는 워크플로우 구현 — detached scope 사용
4. 커스텀 Search Attribute 를 등록하고 워크플로우에서 단계별로 upsert 하기
5. `WorkerOptions` / `WorkerFactoryOptions` 를 안전한 값으로 구성하기 (스레드 데드락 방지)
6. 주어진 메트릭 스냅샷을 읽고 원인을 판정하기
7. "Running 인데 안 움직인다" 시나리오의 진단 명령 순서를 작성하기

---

## 다음 단계

Step 01 부터 12 까지의 모든 개념 — 워크플로우 정의와 결정성, 액티비티와 재시도, 타이머, 시그널·쿼리, 자식 워크플로우, Saga 보상, 버저닝, 테스트, 운영 — 을 하나의 완성된 서비스로 조립합니다. 실제로 배포 가능한 주문 처리 Saga 를 처음부터 끝까지 만들고, 리플레이 테스트가 포함된 CI 파이프라인까지 구성합니다.

→ [Step 13 — 최종 프로젝트 — 주문 처리 Saga](../step-13-final-project/)

---

## 실습 파일

이 스텝의 세 파일은 **CLI 명령과 Java 설정 코드가 섞여** 있습니다. CLI 부분은 `Practice.java` 안에 실행 가능한 문자열 상수와 주석으로 담고, 실제로는 터미널에 복사해 실행합니다. Java 부분(detached scope 보상, Search Attribute, WorkerOptions)은 그대로 컴파일됩니다. 먼저 `Practice.java` 로 12-1 ~ 12-8 을 재현하고, `Exercise.java` 의 7문제를 푼 뒤 `Solution.java` 로 대조합니다.

### Practice.java

본문 12-1 ~ 12-8 의 모든 명령과 코드를 절 번호 주석과 함께 담았습니다.

- 파일 상단의 `CLI` 중첩 클래스에 12-1 ~ 12-3 의 모든 `temporal` 명령이 `static final String` 상수로 들어 있습니다. `main()` 을 실행하면 **명령과 기대 출력이 짝지어 콘솔에 인쇄**되므로, 터미널에 붙여 넣고 실제 출력과 비교하는 방식으로 씁니다. 명령을 실제로 실행하지는 않습니다 — prod 에 잘못 쏘는 사고를 막기 위해 의도적으로 출력만 합니다.
- `CancelCompensation` 중첩 클래스에 12-4 의 **잘못된 버전과 올바른 버전이 나란히** 들어 있습니다. `WrongOrderWorkflowImpl` 과 `CorrectOrderWorkflowImpl` 두 클래스이며, 둘 다 Worker 에 등록해 각각 cancel 을 걸어 보고 히스토리를 비교하도록 구성했습니다. **잘못된 버전도 Status 가 CANCELED 로 끝난다**는 것을 직접 확인하는 것이 핵심입니다.
- `SearchAttributes` 중첩 클래스는 12-5 의 `SearchAttributeKey` 상수 3종(`CUSTOMER_ID`, `ORDER_AMOUNT`, `ORDER_STAGE`)을 정의하고, 시작 시 설정과 실행 중 upsert 를 모두 보여 줍니다. 상수를 한곳에 모으는 이유는 문자열 오타를 컴파일 타임에 막기 위해서입니다.
- `WorkerTuning` 중첩 클래스의 `buildFactory()` 는 `workflowCacheSize=1200`, `maxWorkflowThreadCount=2400` 으로 **배수 2 규칙**을 지킵니다. 바로 아래 `buildDangerousFactory()` 는 둘을 같은 값으로 둔 위험한 설정이며, 실행하면 `PotentialDeadlockException` 을 재현할 수 있습니다. 주석에 재현 조건(자식 워크플로우를 동시에 600개 이상 띄우기)을 적어 두었습니다.
- `Playbook` 중첩 클래스는 12-8 의 5단계를 `String[] STEP_1` ~ `STEP_5` 로 담고, 각 단계의 분기 조건(`Pending Activities` 가 있는지, `Attempt` 가 증가하는지)을 주석으로 서술합니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. CLI 문제는 문자열 상수를 채우는 형태이고, Java 문제는 메서드 본문을 채우는 형태입니다.

- **문제 1·2·7** 은 `String` 을 반환하는 메서드를 채우는 CLI 문제입니다. 채점은 파일 하단의 `main()` 이 담당하며, 필수 토큰(`--retention 720h`, `CustomerId='C-77'` 등)이 포함되었는지 검사해 통과/실패를 인쇄합니다. 즉 **정답 문자열을 정확히 외울 필요는 없고** 핵심 옵션만 맞으면 됩니다.
- **문제 3** 이 이 스텝의 핵심입니다. `catch (CanceledFailure e)` 블록이 비어 있고, 그 안에서 보상 액티비티를 부르도록 되어 있습니다. **그냥 부르면 통과하지 않습니다** — 파일에 포함된 검증 메서드가 `Workflow.newDetachedCancellationScope` 사용 여부를 확인합니다.
- **문제 5** 는 `workflowCacheSize` 만 2000 으로 지정된 상태에서 시작합니다. `maxWorkflowThreadCount` 를 얼마로 잡아야 하는지 판단하는 문제이고, 검증 메서드가 "cacheSize 의 2배 이상"인지 확인합니다.
- **문제 6** 은 코드가 아니라 판독 문제입니다. 세 개의 메트릭 스냅샷(A/B/C)이 주석으로 주어지고, 각각의 원인을 `String` 으로 답하게 되어 있습니다. A 는 결정성 문제, B 는 Worker 부족, C 는 캐시 부족입니다 — 이걸 지표만 보고 구분하는 연습입니다.
- 문제 4 의 `upsertTypedSearchAttributes` 는 워크플로우 코드 안에서 부르는 것이므로 **결정적이어야 합니다.** 조건문 안에서 부를 때 주의할 점을 문제 주석에 힌트로 남겨 두었습니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과, "왜 그렇게 해야 하는가"를 설명하는 긴 주석이 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `--retention 720h` 를 답하면서, Retention 이 **종료된** 워크플로우에만 적용된다는 점(3년 도는 워크플로우는 안전)과, Retention 을 줄이면 이미 저장된 히스토리도 다음 정리 주기에 삭제된다는 점을 경고합니다.
- **정답 2** 는 `"CustomerId='C-77' AND OrderAmount > 100000 AND ExecutionStatus='Failed'"` 이며, Keyword 타입은 `=`/`IN` 만 되고 부분 일치를 못 한다는 점, `Text` 타입과의 차이, 그리고 Search Attribute 인덱싱이 비동기라 방금 시작한 워크플로우는 안 잡힐 수 있다는 점을 덧붙입니다.
- **정답 3** 의 주석이 가장 깁니다. 워크플로우 전체가 하나의 `CancellationScope` 안에서 돈다는 구조를 설명하고, `catch` 블록이 왜 여전히 취소된 스코프 안인지, detached scope 가 무엇을 분리하는지를 히스토리 이벤트 대조로 보여 줍니다. **두 버전 모두 Status 가 CANCELED 라 로그만으로는 구분할 수 없다**는 점을 강조합니다.
- **정답 4** 는 `SearchAttributeKey` 를 상수로 뽑는 이유(문자열 오타의 컴파일 타임 차단)와, upsert 가 히스토리 이벤트를 만들므로 루프 안에서 남발하면 안 된다는 점, 그리고 "Search Attribute 는 인덱싱용, Query 는 상세 조회용"이라는 판단 기준을 정리합니다.
- **정답 5** 는 `maxWorkflowThreadCount = 4000` (cacheSize 2000 의 2배)을 제시하고, 자식 워크플로우를 쓰면 배수 3~4가 필요한 이유와, 부족할 때 나오는 `PotentialDeadlockException: 600/600 in use, 0 available` 실제 로그를 붙입니다.
- **정답 6** 은 A/B/C 판정과 함께 **각각의 대응 순서**를 씁니다. A(결정성)는 롤백이 1순위이고 절대 terminate 하지 말 것, B(Worker 부족)는 파라미터보다 Worker 증설이 먼저, C(캐시 부족)는 cacheSize 와 threadCount 를 함께 올릴 것 — 이 세 가지가 12-6·12-7 의 결론입니다.
- **정답 7** 은 5단계 명령 시퀀스를 나열하고, 마지막에 "이 진단이 필요한 상황 자체를 Step 11 의 리플레이 테스트로 예방할 수 있었다"는 문장으로 코스 전체를 연결합니다.

```java file="./Solution.java"
```
