# Temporal Workflow 완전 학습 코스

첫 워크플로우부터 운영 배포까지, **13개 스텝**으로 Temporal 을 처음부터 끝까지 익힙니다.
모든 예제는 실제로 돌아가는 Temporal Server 1.22.4 + Java SDK 1.22.3 에서 검증했고, **교재에 실린 이벤트 히스토리는 여러분 화면의 히스토리와 정확히 일치합니다.**

Temporal 을 배우는 일은 API 사용법을 외우는 일이 아닙니다. **"내 코드가 언제, 왜, 몇 번 다시 실행되는가"** 를 이해하는 일입니다. 이 코스는 그 답을 매번 **이벤트 히스토리로** 보여줍니다.

---

## 시작하기 (10분)

```bash
# 1. Temporal Server + PostgreSQL + Web UI 기동
cd temporal/project
docker compose up -d

# 2. 기동 확인 (30초쯤 걸립니다)
docker compose ps
temporal operator namespace list --address 127.0.0.1:7233

# 3. Web UI 열기
open http://localhost:8233

# 4. 실습 프로젝트 빌드
./gradlew build
```

문제가 생기면 `docker compose down -v && docker compose up -d` 로 완전히 초기화하세요.
Temporal 의 상태는 전부 PostgreSQL 볼륨에 있으므로, `-v` 를 붙이면 워크플로우 기록까지 깨끗이 지워집니다.

> `temporal` CLI 가 없다면: `brew install temporal` (macOS) 또는 `curl -sSf https://temporal.download/cli.sh | sh`
> 설치·의존성·`build.gradle` 전문은 [실습 프로젝트 셋업](project/) 에 있습니다.

---

## 커리큘럼

### 1부 — 기초 (Step 01~04)
> Temporal 을 한 번도 안 써 봐도 됩니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 환경 구축과 첫 워크플로우 | Worker 기동, WorkflowClient, **Web UI 에서 이벤트 히스토리 직접 확인** |
| [02](step-02-concepts/) | 핵심 개념과 실행 모델 | Workflow/Activity/Worker/Task Queue, **이벤트 소싱과 리플레이**, Workflow Task vs Activity Task |
| [03](step-03-workflow-definition/) | 워크플로우 정의와 결정성 | `@WorkflowInterface`, **결정성 규칙**, `Workflow.currentTimeMillis()`, **NonDeterministicException 재현** |
| [04](step-04-activities/) | 액티비티 | `ActivityOptions`, **타임아웃 4종 비교**, Heartbeat 와 취소, **멱등성 요구사항** |

### 2부 — 제어 흐름 (Step 05~08)
> 워크플로우가 "기다리고, 실패하고, 다시 시도하는" 방법입니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [05](step-05-retry-failure/) | 재시도와 실패 처리 | `RetryOptions`, **기본값이 무한 재시도**, 예외 계층, `newNonRetryableFailure` |
| [06](step-06-timers-await/) | 타이머와 대기 | **몇 달짜리 `sleep` 이 가능한 이유**, `Workflow.await`, `Async`/`Promise` 병렬화 |
| [07](step-07-signal-query-update/) | Signal · Query · Update | 외부 입력, **Query 에서 상태를 바꾸면 안 되는 이유**, `signalWithStart` |
| [08](step-08-child-continue-as-new/) | 자식 워크플로우와 Continue-As-New | `ParentClosePolicy`, **히스토리 51,200 이벤트 한계**, `continueAsNew` |

### 3부 — 실전 (Step 09~11)
> 이 코스의 심장부입니다. "로컬에서는 잘 도는데 운영에서 깨지는" 문제를 직접 만듭니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [09](step-09-saga/) | Saga 보상 트랜잭션 | `Saga` 클래스, `addCompensation`, **보상 액티비티도 실패한다**, 역순 보상 |
| [10](step-10-versioning/) | 버저닝과 무중단 배포 | **실행 중 워크플로우 + 코드 변경 = 리플레이 붕괴**, `Workflow.getVersion`, Worker Versioning |
| [11](step-11-testing/) | 테스트 | **시간 스킵으로 30일 sleep 을 0.4초에**, Activity 모킹, **`WorkflowReplayer` 리플레이 테스트** |

### 4부 — 운영과 종합 (Step 12~13)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [12](step-12-operations/) | 운영 | Namespace, Retention, CLI, **terminate vs cancel**, Search Attributes, Worker 튜닝 |
| [13](step-13-final-project/) | 최종 프로젝트 — 주문 처리 Saga | 결제→재고→배송→알림, 단계별 보상, 취소 시그널, 타임아웃, 테스트까지 종합 |

> 📌 Step 03(결정성)과 Step 10(버저닝)은 **한 쌍**입니다. Step 03 에서 "왜 깨지는지"를 배우고, Step 10 에서 "그런데도 코드를 바꿔야 할 때 어떻게 하는지"를 배웁니다. Step 11 의 리플레이 테스트가 그 둘을 자동으로 검증합니다. 이 셋이 이 코스의 뼈대입니다.

---

## 각 스텝의 구성

```
step-04-activities/
├── index.md         ← 교재 본문. 개념 + 코드 + 실제 이벤트 히스토리 + 함정/팁
├── Practice.java    ← 교재의 모든 예제를 절 번호 주석과 함께 담은 실행 파일
├── Exercise.java    ← 연습문제 (문제만, "// TODO: 여기에 작성")
└── Solution.java    ← 정답 + 왜 그 답인지 설명하는 해설 주석
```

**권장 학습 방법**

1. `index.md` 를 읽으며 **직접 타이핑해서** 실행합니다. 복붙하지 마세요.
2. 워크플로우를 실행할 때마다 **Web UI 나 `temporal workflow show` 로 히스토리를 확인**합니다. 교재의 히스토리와 다르면 멈추고 원인을 찾으세요.
3. `Exercise.java` 를 풀고 `Solution.java` 로 채점합니다.
4. 다음 스텝으로.

```bash
# Worker 기동 (터미널 1)
./gradlew runWorker

# 워크플로우 실행 (터미널 2)
./gradlew runStarter -Pstep=step-04

# 히스토리 확인 (터미널 3)
temporal workflow show -w order-1001
```

> 💡 터미널을 **세 개** 띄워 두고 하십시오. Worker 로그 · 실행 명령 · 히스토리 조회를 동시에 봐야
> "코드의 어느 줄이 어느 이벤트가 되는지"가 눈에 들어옵니다. 이 코스는 그 감각을 기르는 것이 목표입니다.

---

## 실습 도메인 — 주문 처리

가상의 커머스 주문 처리 워크플로우입니다. 결제·재고·배송·알림이 각각 별도 서비스라고 가정합니다.

```
                        ┌─────────────────────────────────┐
                        │        OrderWorkflow            │
                        │   (워크플로우 = 조정자)          │
                        └───┬─────┬─────┬─────┬───────────┘
                            │     │     │     │
              ┌─────────────┘     │     │     └─────────────┐
              │        ┌──────────┘     └────────┐          │
              ▼        ▼                         ▼          ▼
      ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
      │  Payment    │ │  Inventory   │ │  Shipping    │ │  Notification    │
      │  Activity   │ │  Activity    │ │  Activity    │ │  Activity        │
      ├─────────────┤ ├──────────────┤ ├──────────────┤ ├──────────────────┤
      │ charge()    │ │ reserve()    │ │ requestShip()│ │ notifyCustomer() │
      │ refund()  ◄─┤ │ release()  ◄─┤ │ cancelShip()◄┤ │                  │
      └─────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘
         보상             보상              보상
              ▲                ▲                ▲
              └────────────────┴────────────────┘
                실패 시 성공한 단계를 역순으로 되돌린다 (Step 09)
```

| 구성 요소 | 값 | 비고 |
|---|---|---|
| Task Queue | `ORDER_TASK_QUEUE` | 상수로 관리. 오타 하나로 워크플로우가 영원히 멈춥니다 (Step 01) |
| Workflow | `OrderWorkflow` | `processOrder(OrderRequest)` |
| Workflow ID | `order-{orderId}` | 비즈니스 키. 중복 실행 방지에 그대로 쓰입니다 |
| Activity | 4종 × 정상/보상 | 각각 다른 타임아웃·재시도 정책 (Step 04, 13) |
| Namespace | `default` | Step 12 에서 `orders` 를 추가로 만듭니다 |

**의도적으로 실패하는 입력을 심어 두었습니다.** `sku` 가 `OUT-OF-STOCK` 이면 재고 액티비티가, `address` 가 비어 있으면 배송 액티비티가 실패합니다. 보상 트랜잭션(Step 09)과 재시도(Step 05)의 재료로 씁니다.

### 결과가 항상 똑같은 이유

액티비티 구현은 `Math.random()` 을 쓰지 않고 **입력값에 따라 결정적으로** 성공/실패합니다. 그래서 누가 몇 번을 실행하든 **완전히 동일한 이벤트 히스토리**가 나오고, 교재에 실린 `temporal workflow show` 출력이 여러분 화면과 일치합니다. 히스토리가 다르면 바로 뭔가 잘못됐다는 뜻입니다.

(워크플로우 코드에서 `Math.random()` 을 쓰면 안 되는 것과는 별개의 이야기입니다. 그건 Step 03 에서 다룹니다.)

---

## 실습 규칙

- **Worker 를 띄우지 않고 워크플로우를 실행하면 에러가 나지 않습니다.** `Running` 상태로 조용히 멈춰 있습니다. 실행 전에 Worker 로그를 확인하세요.
- 실습 흔적을 지우려면:
  ```bash
  # 이 코스에서 만든 워크플로우를 전부 종료
  temporal workflow list --query "WorkflowType='OrderWorkflow'" --limit 100
  temporal workflow terminate --query "WorkflowType='OrderWorkflow' AND ExecutionStatus='Running'" --reason "실습 정리"
  ```
- 그래도 지저분하면 `docker compose down -v && docker compose up -d` 로 전부 초기화합니다.
- Step 03·10 은 **일부러 깨뜨리는** 실습입니다. 깨진 워크플로우가 남아도 정상이며, 각 스텝 끝에서 정리 방법을 안내합니다.

---

## 이 코스가 특히 신경 쓴 것

**"로컬에서는 잘 도는데 운영에서 리플레이가 깨지는"** 상황을 잡는 데 집중했습니다. 컴파일 에러는 금방 고칠 수 있지만, **테스트를 전부 통과하고 배포된 뒤에야 조용히 멈추는 워크플로우**가 진짜 위험합니다. 예를 들면:

- `System.currentTimeMillis()` 하나로 워크플로우가 **재배포 순간에만** 깨집니다 (Step 03)
- 재시도 옵션 기본값이 **무한 재시도**라, 잘못된 액티비티가 영원히 서버를 두드립니다 (Step 05)
- `while(true) { Workflow.sleep(1초) }` 폴링은 **하루에 히스토리 이벤트 17만 개**를 만듭니다 (Step 06, 08)
- Query 핸들러에서 필드를 하나 바꾸면 **다음 리플레이에서 상태가 되돌아갑니다** (Step 07)
- 부모 워크플로우를 terminate 했더니 `ParentClosePolicy` 기본값 때문에 **자식이 전부 죽습니다** (Step 08)
- 액티비티 순서를 한 줄 바꿨을 뿐인데 **실행 중이던 워크플로우 전부가 멈춥니다** (Step 10)
- `terminate` 는 보상을 돌리지 않아 **결제만 되고 재고는 안 잡힌 주문**이 영구히 남습니다 (Step 12)
- 액티비티가 멱등하지 않으면 타임아웃 재시도로 **결제가 두 번** 됩니다 (Step 04, 09)

각 스텝의 `⚠️ 함정` 블록을 특히 눈여겨 보세요. 그리고 이 모든 것을 **배포 전에** 잡아내는 방법이 Step 11 의 리플레이 테스트입니다.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Temporal Server | 1.22.4 (`temporalio/auto-setup:1.22.4`) |
| Temporal Web UI | 2.21.3 — `http://localhost:8233` |
| gRPC 엔드포인트 | `127.0.0.1:7233` |
| Java SDK | `io.temporal:temporal-sdk:1.22.3` |
| temporal CLI | 0.11.0 |
| Java | 21 (Temurin) |
| Gradle | 8.5 (Groovy DSL) |
| 영속 저장소 | PostgreSQL 15 (`temporal` / `temporal`) |
| Namespace | `default` (Retention 72시간) |
| 설정 | [`project/docker-compose.yml`](./project/) |

> 실습 환경의 `default` Namespace 는 Retention 이 **72시간**입니다. 사흘 지난 워크플로우는 히스토리 조회가 아예 안 됩니다.
> 운영에서 이 값을 어떻게 정하고, 리플레이 테스트용 히스토리를 어떻게 아카이빙하는지는 [Step 12](step-12-operations/) 에서 다룹니다.
