# Step 11 — 테스트

> **학습 목표**
> - `TestWorkflowEnvironment` 로 **Temporal 서버 없이** 워크플로우를 실행하고 JUnit 5 로 검증한다
> - **시간 스킵**을 실측한다 — `Workflow.sleep(Duration.ofDays(30))` 을 하는 워크플로우 테스트가 0.4초에 끝나는 것을 확인한다
> - Mockito 로 Activity 를 모킹하고, `withoutAnnotations()` 를 빠뜨렸을 때 나는 에러를 재현한다
> - `TestActivityEnvironment` 로 액티비티만 단독 테스트한다
> - Saga 보상이 **역순으로** 호출되었는지 `InOrder` 로 검증한다
> - **`WorkflowReplayer` 로 운영 히스토리를 리플레이**해 Step 10 의 버저닝이 안전한지 자동 검증하고, 깨졌을 때의 `NonDeterministicException` 을 직접 본다
>
> **선행 스텝**: Step 10 — 버저닝과 무중단 배포
> **예상 소요**: 100분

---

## 11-0. 실습 준비

Step 10 까지의 `OrderWorkflow` 와 4종 액티비티가 그대로 필요합니다. 이 스텝은 `src/main` 코드를 거의 건드리지 않고 `src/test` 만 채웁니다.

```
src/
├── main/java/com/example/order/
│   ├── OrderWorkflow.java
│   ├── OrderWorkflowImpl.java
│   ├── PaymentActivity.java  InventoryActivity.java
│   ├── ShippingActivity.java NotificationActivity.java
│   └── OrderRequest.java
└── test/
    ├── java/com/example/order/
    │   ├── OrderWorkflowTest.java        ← 11-2 ~ 11-7
    │   └── OrderWorkflowReplayTest.java  ← 11-8
    └── resources/histories/              ← 11-8 에서 운영 히스토리를 커밋할 곳
```

---

## 11-1. 테스트 의존성

`build.gradle` 에 테스트 의존성 3종을 추가합니다. Temporal Java SDK 1.22.3 기준입니다.

```groovy
dependencies {
    implementation 'io.temporal:temporal-sdk:1.22.3'

    testImplementation 'io.temporal:temporal-testing:1.22.3'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
    testImplementation 'org.mockito:mockito-core:5.8.0'
    testImplementation 'org.assertj:assertj-core:3.24.2'
}

test {
    useJUnitPlatform()
    testLogging { events 'passed', 'failed', 'skipped'; showStandardStreams = true }
}
```

```bash
./gradlew dependencies --configuration testRuntimeClasspath | grep temporal
```

**결과**
```
+--- io.temporal:temporal-sdk:1.22.3
|    +--- io.temporal:temporal-serviceclient:1.22.3
+--- io.temporal:temporal-testing:1.22.3
|    +--- io.temporal:temporal-test-server:1.22.3
|    \--- io.temporal:temporal-sdk:1.22.3 (*)
```

`temporal-testing` 이 `temporal-test-server` 를 끌고 옵니다. **이게 인메모리 Temporal 서비스의 실체**입니다. Docker 도, `temporal server start-dev` 도 필요 없습니다.

테스트 환경을 만드는 방법은 두 가지입니다.

### (a) `TestWorkflowExtension` — JUnit 5 확장

```java
@RegisterExtension
public static final TestWorkflowExtension testWorkflowExtension =
    TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(OrderWorkflowImpl.class)
        .setActivityImplementations(new PaymentActivityImpl(), new InventoryActivityImpl())
        .setDoNotStart(true)      // 테스트 안에서 직접 start() 하고 싶을 때
        .build();

@Test
void 주문이_완료된다(TestWorkflowEnvironment testEnv, Worker worker, OrderWorkflow workflow) {
    worker.registerActivitiesImplementations(mockPayment);
    testEnv.start();
    assertThat(workflow.processOrder(req)).isEqualTo("order-1001 COMPLETED");
}
```

확장이 `TestWorkflowEnvironment`, `Worker`, 워크플로우 스텁을 **테스트 메서드 파라미터로 주입**합니다. 짧게 쓸 때 편합니다.

### (b) 수동 `TestWorkflowEnvironment` — 명시적

```java
@BeforeEach void setUp()  { testEnv = TestWorkflowEnvironment.newInstance(); ... }
@AfterEach  void tearDown() { testEnv.close(); }
```

Worker 구성·모킹·옵션을 테스트마다 다르게 가져가야 할 때는 (b) 가 낫습니다. 이 스텝은 **(b) 를 기준**으로 설명합니다. 동작 원리가 그대로 드러나기 때문입니다.

> 💡 **실무 팁 — 둘을 섞지 마세요**
> 한 테스트 클래스에서 `TestWorkflowExtension` 과 수동 `TestWorkflowEnvironment` 를 같이 쓰면 인메모리 서비스가 두 개 뜨고, 시간 스킵의 가상 시계도 두 개가 되어 타이머 테스트가 예측 불가능해집니다.

---

## 11-2. `TestWorkflowEnvironment` — 서버 없이 실행

`TestWorkflowEnvironment.newInstance()` 는 **프로세스 안에** Temporal 서비스를 띄웁니다. gRPC 포트도, PostgreSQL 도 없습니다. 히스토리는 힙 위의 자료구조입니다.

```java
class OrderWorkflowTest {

    private static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    private TestWorkflowEnvironment testEnv;
    private Worker worker;
    private WorkflowClient client;

    private PaymentActivity payment;
    private InventoryActivity inventory;
    private ShippingActivity shipping;
    private NotificationActivity notification;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        worker  = testEnv.newWorker(TASK_QUEUE);
        client  = testEnv.getWorkflowClient();

        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        payment      = mock(PaymentActivity.class,      withSettings().withoutAnnotations());
        inventory    = mock(InventoryActivity.class,    withSettings().withoutAnnotations());
        shipping     = mock(ShippingActivity.class,     withSettings().withoutAnnotations());
        notification = mock(NotificationActivity.class, withSettings().withoutAnnotations());
        worker.registerActivitiesImplementations(payment, inventory, shipping, notification);
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void 정상_주문은_COMPLETED_를_반환한다() {
        when(payment.charge("1001", 39000L)).thenReturn("PAY-8821");
        when(inventory.reserve("1001", "SKU-A", 2)).thenReturn("RSV-3310");
        when(shipping.requestShipment(eq("1001"), anyString())).thenReturn("SHIP-5507");

        testEnv.start();

        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE).setWorkflowId("order-1001").build());

        String result = wf.processOrder(
            new OrderRequest("1001", "C-77", "SKU-A", 2, 39000L, "서울시 강남구"));

        assertThat(result).isEqualTo("order-1001 COMPLETED");
        verify(payment).charge("1001", 39000L);
        verify(inventory).reserve("1001", "SKU-A", 2);
        verify(notification).notifyCustomer("1001", "주문이 완료되었습니다");
    }
}
```

```bash
./gradlew test --tests 'com.example.order.OrderWorkflowTest'
```

**결과**
```
> Task :test

OrderWorkflowTest > 정상_주문은_COMPLETED_를_반환한다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.918 s

BUILD SUCCESSFUL in 4s
3 actionable tasks: 2 executed, 1 up-to-date
```

**0.918초.** 이 안에 인메모리 서비스 기동 + Worker 폴러 기동 + 워크플로우 전체 실행이 모두 들어 있습니다.

테스트 안에서도 히스토리는 진짜로 쌓입니다. 실행 후 이렇게 꺼내 볼 수 있습니다.

```java
System.out.println(testEnv.getDiagnostics());
```

**결과** (발췌)
```
Workflow Executions:
  WorkflowId=order-1001 RunId=cf1a1f0e-... Type=OrderWorkflow Status=COMPLETED
  Event History:
     1 WorkflowExecutionStarted
     4 WorkflowTaskCompleted
     5 ActivityTaskScheduled       PaymentActivity.charge
     7 ActivityTaskCompleted
     ...
    22 WorkflowExecutionCompleted
```

Step 02~03 에서 본 것과 **동일한 이벤트 히스토리**입니다. 테스트 환경이 진짜 Temporal 을 흉내 내는 게 아니라, 같은 실행 모델을 그대로 돌립니다.

---

## 11-3. 시간 스킵 — 30일을 0.4초에

이 스텝에서 가장 강력한 기능입니다.

주문 후 **30일이 지나면 자동으로 리뷰 요청 알림**을 보내는 워크플로우가 있다고 합시다.

```java
public class ReviewReminderWorkflowImpl implements ReviewReminderWorkflow {
    private final NotificationActivity notification =
        Workflow.newActivityStub(NotificationActivity.class, ACT_OPTS);

    @Override
    public String remind(String orderId) {
        Workflow.sleep(Duration.ofDays(30));          // ← 30일 대기
        notification.notifyCustomer(orderId, "리뷰를 남겨 주세요");
        return orderId + " REMINDED";
    }
}
```

실제 서버에서는 이 워크플로우가 끝나는 데 30일이 걸립니다. 테스트에서는:

```java
@Test
void 삼십일_뒤_리뷰_요청을_보낸다() {
    worker.registerWorkflowImplementationTypes(ReviewReminderWorkflowImpl.class);
    testEnv.start();

    ReviewReminderWorkflow wf = client.newWorkflowStub(
        ReviewReminderWorkflow.class,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE)
            .setWorkflowId("review-1001").build());

    long before = System.currentTimeMillis();
    String result = wf.remind("1001");                 // 블로킹 호출인데도…
    long wallClock = System.currentTimeMillis() - before;

    assertThat(result).isEqualTo("1001 REMINDED");
    assertThat(wallClock).isLessThan(2000);            // 실제 경과 2초 미만
    verify(notification).notifyCustomer("1001", "리뷰를 남겨 주세요");
}
```

```bash
./gradlew test --tests '*ReviewReminder*'
```

**결과**
```
OrderWorkflowTest > 삼십일_뒤_리뷰_요청을_보낸다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.412 s
```

**`Tests run: 1, Time elapsed: 0.412 s`.** 30일 = 2,592,000초를 0.412초에 통과했습니다. 약 **630만 배**입니다.

### 원리 — 가상 시계와 "모두 대기 중" 조건

테스트 환경의 시계는 벽시계가 아니라 **가상 시계**입니다. 규칙은 하나입니다.

> **모든 Worker 가 할 일이 없어 대기 상태가 되면, 가상 시계를 다음 타이머의 발화 시각으로 즉시 점프시킨다.**

```
가상시각 T0        워크플로우 시작
                  Workflow.sleep(30d) → 타이머 등록 (발화 예정: T0+30d)
                  Worker: 처리할 Task 없음 → 유휴
                          ↓  "전원 유휴" 감지
가상시각 T0+30d     시계 점프. 타이머 발화 → TimerFired 이벤트
                  Workflow Task 생성 → Worker 가 깨어나 액티비티 실행
```

액티비티가 실행 중이면(=Worker 가 바쁘면) 점프하지 않습니다. 그래서 액티비티 로직의 실제 소요 시간은 그대로 반영되고, **대기 시간만** 사라집니다.

가상 시각은 직접 조작하고 확인할 수 있습니다.

```java
long t0 = testEnv.currentTimeMillis();
testEnv.sleep(Duration.ofDays(7));                 // 가상 시계를 7일 밀기
long t1 = testEnv.currentTimeMillis();
System.out.println("가상 경과일 = " + Duration.ofMillis(t1 - t0).toDays());
```

**결과**
```
가상 경과일 = 7
```

`testEnv.sleep()` 은 **테스트 스레드에서 시간을 진행시키는** 용도입니다(워크플로우 안의 `Workflow.sleep` 과 다릅니다). 비동기로 워크플로우를 띄워 놓고 "3일 뒤 상태"를 검증할 때 씁니다.

```java
WorkflowClient.start(wf::processOrder, req);        // 비동기 시작
testEnv.sleep(Duration.ofDays(3));                  // 가상 3일 경과
assertThat(wf.getStatus()).isEqualTo("WAITING_SHIPMENT");
```

> ⚠️ **함정 — 시간 스킵이 꺼지는 조건**
> 시간 스킵은 "모든 Worker 가 유휴"일 때만 발동합니다. 그런데 **테스트 스레드가 워크플로우를 동기 호출로 블로킹**한 채, 별도 스레드에서 시그널을 보내는 패턴이 있습니다.
> ```java
> new Thread(() -> { Thread.sleep(500); wf.cancelRequested("x"); }).start();
> wf.processOrder(req);   // 여기서 블로킹
> ```
> 이때 가상 시계가 먼저 점프해 버리면 시그널이 도착하기 전에 워크플로우가 타임아웃으로 끝나 버립니다. 테스트가 **재현 불가능하게 깜빡거립니다(flaky)**.
> 이런 테스트는 시간 스킵을 끕니다.
> ```java
> testEnv = TestWorkflowEnvironment.newInstance(
>     TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());
> ```
> 끄면 가상 시계가 벽시계처럼 흐릅니다. 즉 `Workflow.sleep(30d)` 짜리 테스트는 절대 이 옵션으로 돌리면 안 됩니다. **긴 대기가 있는 테스트와 외부 스레드 시그널 테스트는 클래스를 분리**하세요.

> ⚠️ **함정 — 테스트에 `Thread.sleep()` 을 쓰면 시간 스킵이 무의미해진다**
> "시그널 보내기 전에 좀 기다려야지" 하고 `Thread.sleep(2000)` 을 넣는 순간, 그 2초는 **실제로** 흘러갑니다. 가상 시계가 아니라 벽시계이기 때문입니다.
> 테스트 100개에 2초씩 넣으면 CI 가 3분 더 걸립니다. 그리고 CI 머신이 느린 날에는 2초로 부족해 실패합니다.
> **대기가 필요하면 항상 `testEnv.sleep(Duration)`** 을 쓰세요. 가상 시계를 밀 뿐이라 즉시 반환됩니다. `Thread.sleep` 은 시간 스킵을 끈 테스트에서만, 그것도 최후 수단으로 씁니다.

---

## 11-4. Activity 모킹

워크플로우 테스트에서 액티비티 구현체를 그대로 쓰면 결제 API 를 실제로 호출하게 됩니다. **액티비티는 모킹하고 워크플로우 로직만 검증**하는 것이 원칙입니다.

```java
PaymentActivity payment = mock(PaymentActivity.class, withSettings().withoutAnnotations());
when(payment.charge("1001", 39000L)).thenReturn("PAY-8821");
worker.registerActivitiesImplementations(payment);
```

실패 주입은 `ApplicationFailure` 로 합니다. Step 05 에서 본 그대로입니다.

```java
// 재시도 가능한 실패 — RetryOptions 만큼 재시도된 뒤 최종 실패
when(payment.charge("1001", 39000L))
    .thenThrow(ApplicationFailure.newFailure("카드사 응답 없음", "PaymentGatewayTimeout"));

// 재시도 불가 실패 — 즉시 워크플로우로 전파
when(payment.charge("1001", 39000L))
    .thenThrow(ApplicationFailure.newNonRetryableFailure("잔액 부족", "InsufficientFunds"));

// 첫 두 번은 실패, 세 번째 성공 — 재시도 동작 자체를 검증
when(payment.charge("1001", 39000L))
    .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
    .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
    .thenReturn("PAY-8821");
```

세 번째 패턴의 테스트:

```java
@Test
void 결제는_두번_실패해도_세번째에_성공한다() {
    when(payment.charge("1001", 39000L))
        .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
        .thenThrow(ApplicationFailure.newFailure("일시 오류", "Transient"))
        .thenReturn("PAY-8821");
    when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-3310");
    when(shipping.requestShipment(any(), any())).thenReturn("SHIP-5507");

    testEnv.start();
    assertThat(newStub("order-1002").processOrder(req("1002")))
        .isEqualTo("order-1002 COMPLETED");

    verify(payment, times(3)).charge("1002", 39000L);   // 재시도 2회 + 성공 1회
}
```

**결과**
```
OrderWorkflowTest > 결제는_두번_실패해도_세번째에_성공한다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.507 s
```

재시도 백오프가 `initialInterval=1s, backoffCoefficient=2.0` 이면 실제로는 1초 + 2초 = 3초를 기다려야 합니다. **0.507초에 끝난 것은 시간 스킵이 재시도 백오프에도 적용되기 때문**입니다. 재시도 테스트가 실용적인 이유입니다.

> ⚠️ **함정 — `withSettings().withoutAnnotations()` 를 빠뜨리면 액티비티가 등록되지 않는다**
> Mockito 는 기본적으로 모킹 대상의 어노테이션을 **프록시 클래스에 복사**합니다. 그래서 `mock(PaymentActivity.class)` 의 결과물에는 `@ActivityInterface` 가 클래스 레벨로 붙어 버립니다.
> Temporal SDK 는 등록된 객체를 보고 "이 클래스가 직접 `@ActivityInterface` 를 달고 있네? 그럼 이건 인터페이스가 아니라 구현이어야 하는데?" 라고 판단하고 거부합니다.
> ```java
> worker.registerActivitiesImplementations(mock(PaymentActivity.class));   // 잘못됨
> ```
> **결과**
> ```
> java.lang.IllegalArgumentException: Interface annotated with @ActivityInterface
>   can't be registered as an activity implementation:
>   interface com.example.order.PaymentActivity
>     at io.temporal.internal.activity.ActivityTaskHandlerImpl.registerActivityImplementations
>     at io.temporal.worker.Worker.registerActivitiesImplementations(Worker.java:214)
>     at com.example.order.OrderWorkflowTest.setUp(OrderWorkflowTest.java:48)
> ```
> 이 에러 메시지가 혼란스러운 이유는 **인터페이스를 등록한 적이 없기 때문**입니다. 등록한 건 mock 객체인데, 어노테이션이 복사되는 바람에 SDK 눈에는 인터페이스처럼 보인 것입니다.
> **해결**: 모든 액티비티 mock 에 `withSettings().withoutAnnotations()` 를 붙입니다.
> ```java
> mock(PaymentActivity.class, withSettings().withoutAnnotations())
> ```
> 헬퍼 메서드로 감싸 두면 빠뜨릴 일이 없습니다.
> ```java
> static <T> T activityMock(Class<T> type) {
>     return mock(type, withSettings().withoutAnnotations());
> }
> ```

---

## 11-5. `TestActivityEnvironment` — 액티비티만 단독 테스트

워크플로우를 거치지 않고 액티비티 **구현체**만 테스트하고 싶을 때 씁니다. 액티비티 안에서 `Activity.getExecutionContext()` 를 쓰거나 하트비트를 보내는 코드는 일반 JUnit 테스트로는 돌지 않습니다(컨텍스트가 없어 NPE). `TestActivityEnvironment` 가 그 컨텍스트를 제공합니다.

```java
@Test
void 재고_예약_액티비티가_예약ID를_반환한다() {
    TestActivityEnvironment env = TestActivityEnvironment.newInstance();
    env.registerActivitiesImplementations(new InventoryActivityImpl());

    InventoryActivity stub = env.newActivityStub(InventoryActivity.class);

    String reservationId = stub.reserve("1001", "SKU-A", 2);

    assertThat(reservationId).startsWith("RSV-");
    env.close();
}
```

**결과**
```
InventoryActivityTest > 재고_예약_액티비티가_예약ID를_반환한다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.089 s
```

**0.089초.** 워크플로우 테스트(0.9초)의 1/10 입니다. 인메모리 워크플로우 서비스를 띄우지 않기 때문입니다.

하트비트와 취소를 검증하려면:

```java
TestActivityEnvironment env = TestActivityEnvironment.newInstance();
env.registerActivitiesImplementations(new ShippingActivityImpl());

List<Object> heartbeats = new ArrayList<>();
env.setActivityHeartbeatListener(String.class, heartbeats::add);

ShippingActivity stub = env.newActivityStub(ShippingActivity.class);
CompletableFuture<String> f =
    CompletableFuture.supplyAsync(() -> stub.requestShipment("1001", "서울시 강남구"));

Thread.sleep(300);
env.requestCancelActivity();                    // 취소 요청

assertThatThrownBy(f::get).hasCauseInstanceOf(ActivityCanceledException.class);
assertThat(heartbeats).contains("polling-1", "polling-2");
env.close();
```

**결과**
```
ShippingActivityTest > 장시간_배송조회는_하트비트를_보내고_취소에_반응한다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.463 s
```

`setActivityHeartbeatListener` 로 액티비티가 실제로 하트비트를 보냈는지 확인하고, `requestCancelActivity()` 로 취소 신호를 넣어 `ActivityCanceledException` 이 나오는지 봅니다. **하트비트를 안 보내는 장시간 액티비티**를 잡아내는 유일한 자동화 수단입니다.

---

## 11-6. 시그널·쿼리 테스트

Step 07 의 시그널·쿼리를 검증하려면 워크플로우를 **비동기로 시작**해야 합니다. 동기 호출(`wf.processOrder(req)`)은 완료까지 블로킹하므로 시그널을 보낼 틈이 없습니다.

```java
@Test
void 취소_시그널을_받으면_상태가_CANCELED_로_바뀐다() {
    when(payment.charge(any(), anyLong())).thenReturn("PAY-8821");
    when(inventory.reserve(any(), any(), anyInt())).thenReturn("RSV-3310");
    testEnv.start();

    OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE)
            .setWorkflowId("order-1003").build());

    // ① 비동기 시작 — 즉시 반환된다
    WorkflowExecution exec = WorkflowClient.start(wf::processOrder, req("1003"));
    assertThat(exec.getWorkflowId()).isEqualTo("order-1003");

    // ② 가상 시간을 조금 흘려 워크플로우가 대기 지점까지 진행하게 한다
    testEnv.sleep(Duration.ofSeconds(1));

    // ③ 쿼리 — 진행 중 상태 확인
    assertThat(wf.getStatus()).isEqualTo("WAITING_SHIPMENT");

    // ④ 시그널 — 취소 요청
    wf.cancelRequested("고객 변심");

    // ⑤ 쿼리 — 시그널 반영 확인
    testEnv.sleep(Duration.ofSeconds(1));
    assertThat(wf.getStatus()).isEqualTo("CANCELED");

    // ⑥ 결과 회수 — 타입 스텁을 WorkflowStub 으로 변환
    String result = WorkflowStub.fromTyped(wf).getResult(String.class);
    assertThat(result).isEqualTo("order-1003 CANCELED");
}
```

**결과**
```
OrderWorkflowTest > 취소_시그널을_받으면_상태가_CANCELED_로_바뀐다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.634 s
```

핵심은 ⑥ 입니다. `WorkflowClient.start()` 는 `WorkflowExecution`(ID 와 RunId)만 돌려주므로 결과를 받으려면 **`WorkflowStub.fromTyped(wf).getResult(String.class)`** 로 타입 스텁을 언타입 스텁으로 바꿔 기다려야 합니다. 이걸 모르면 "시그널 테스트에서 결과를 어떻게 받나" 하고 막힙니다.

> 💡 **실무 팁 — 쿼리 사이에 `testEnv.sleep()` 을 한 번 넣으세요**
> 시그널은 히스토리에 기록된 뒤 다음 Workflow Task 에서 처리됩니다. 시그널 직후 곧바로 쿼리하면 아직 반영 전일 수 있습니다.
> `testEnv.sleep(Duration.ofSeconds(1))` 은 가상 시각만 밀 뿐이라 **비용이 0** 이면서 Workflow Task 를 한 번 돌게 만듭니다. `Thread.sleep` 과 달리 CI 를 느리게 하지 않습니다.

---

## 11-7. Saga 보상 테스트 — 역순 검증

Step 09 의 Saga 는 "배송 실패 시 재고 해제 → 결제 환불" 순으로 **역순 보상**합니다. 순서가 뒤바뀌면 재고를 못 푼 채 환불만 되는 상태가 생길 수 있습니다. 이걸 자동으로 잡습니다.

```java
@Test
void 배송이_실패하면_보상이_역순으로_실행된다() {
    when(payment.charge("1004", 39000L)).thenReturn("PAY-8821");
    when(inventory.reserve("1004", "SKU-A", 2)).thenReturn("RSV-3310");
    // 배송만 재시도 불가 실패로 주입
    when(shipping.requestShipment(eq("1004"), anyString()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure(
            "배송 불가 지역", "ShippingUnavailable"));

    testEnv.start();

    assertThatThrownBy(() -> newStub("order-1004").processOrder(req("1004")))
        .isInstanceOf(WorkflowFailedException.class)
        .hasRootCauseMessage("배송 불가 지역");

    // 보상이 "실행의 역순"으로 호출되었는지 검증
    InOrder inOrder = inOrder(payment, inventory, shipping);
    inOrder.verify(payment).charge("1004", 39000L);         // 정방향 1
    inOrder.verify(inventory).reserve("1004", "SKU-A", 2);  // 정방향 2
    inOrder.verify(shipping).requestShipment(eq("1004"), anyString()); // 정방향 3 (실패)
    inOrder.verify(inventory).release("RSV-3310");          // 보상 1 ← 나중 것 먼저
    inOrder.verify(payment).refund("PAY-8821");             // 보상 2 ← 먼저 한 것 나중에

    verify(shipping, never()).cancelShipment(anyString());  // 배송은 애초에 성공한 적 없음
}
```

**결과**
```
OrderWorkflowTest > 배송이_실패하면_보상이_역순으로_실행된다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.721 s
```

만약 구현이 보상을 **정방향**으로 돌린다면(`Saga.Options.setParallelCompensation` 을 잘못 쓰거나 직접 짠 보상 루프의 순서를 뒤집었다면) 다음처럼 실패합니다.

**결과** (보상 순서가 잘못된 구현일 때)
```
OrderWorkflowTest > 배송이_실패하면_보상이_역순으로_실행된다() FAILED
    org.mockito.exceptions.verification.VerificationInOrderFailure:
    Verification in order failure
    Wanted but not invoked:
    inventoryActivity.release("RSV-3310");
    Wanted anywhere AFTER following interaction:
    paymentActivity.refund("PAY-8821");

Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.698 s
```

`InOrder` 없이 `verify(payment).refund(...)` 만 했다면 **둘 다 호출되었으니 통과**했을 것입니다. 순서 버그는 `InOrder` 로만 잡힙니다.

---

## 11-8. 리플레이 테스트 (`WorkflowReplayer`)

여기가 이 스텝의 결론입니다.

Step 10 에서 배운 것: **워크플로우 코드를 바꾸면 진행 중인 워크플로우가 리플레이 시 `NonDeterministicException` 으로 멈출 수 있다.** `Workflow.getVersion()` 으로 방어하지만, "제대로 방어했는지"는 어떻게 확인할까요?

로컬 테스트로는 못 잡습니다. **로컬 테스트는 항상 새 워크플로우를 처음부터 실행**하므로 옛 히스토리와 부딪힐 일이 없습니다. 필요한 건 **운영에서 실제로 만들어진 히스토리로 새 코드를 리플레이해 보는 것**입니다.

### ① 운영 히스토리 덤프

```bash
temporal workflow show \
  --workflow-id order-1001 \
  --namespace orders \
  --output json > src/test/resources/histories/order-1001.json
```

**결과**
```
$ ls -lh src/test/resources/histories/
-rw-r--r--  1 dev  staff    18K Jul 20 11:04 order-1001.json

$ head -c 320 src/test/resources/histories/order-1001.json
{
  "events": [
    {
      "eventId": "1",
      "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_STARTED",
      "workflowExecutionStartedEventAttributes": {
        "workflowType": { "name": "OrderWorkflow" },
        "taskQueue": { "name": "ORDER_TASK_QUEUE" },
```

### ② 리플레이 테스트

```java
import io.temporal.testing.WorkflowReplayer;

class OrderWorkflowReplayTest {

    @Test
    void 운영_히스토리를_현재_코드로_리플레이할_수_있다() throws Exception {
        WorkflowReplayer.replayWorkflowExecutionFromResource(
            "histories/order-1001.json", OrderWorkflowImpl.class);
    }
}
```

`replayWorkflowExecutionFromResource` 는 클래스패스 리소스에서 히스토리를 읽어 **워크플로우 코드를 그 히스토리대로 재실행**합니다. 액티비티는 실행되지 않습니다 — 히스토리에 결과가 이미 있으므로 그것을 먹입니다. 즉 **네트워크도, Worker 도, 서버도 없이** 순수 결정성만 검증합니다.

```bash
./gradlew test --tests '*ReplayTest'
```

**결과** (코드가 히스토리와 호환될 때)
```
OrderWorkflowReplayTest > 운영_히스토리를_현재_코드로_리플레이할_수_있다() PASSED

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.147 s

BUILD SUCCESSFUL in 3s
```

**0.147초.** 서버가 없으니 워크플로우 테스트보다도 빠릅니다.

### ③ 깨졌을 때

`OrderWorkflowImpl` 에서 `Workflow.getVersion()` 가드를 빼고 액티비티 순서를 바꿔 봅니다(결제 → 재고 를 재고 → 결제 로).

**결과**
```
OrderWorkflowReplayTest > 운영_히스토리를_현재_코드로_리플레이할_수_있다() FAILED
    java.lang.RuntimeException: Replay failed
        at io.temporal.testing.WorkflowReplayer.replayWorkflowExecution(WorkflowReplayer.java:210)
    Caused by: io.temporal.worker.NonDeterministicException:
        History event is not compatible with the command produced by the workflow code.
        HistoryEvent[eventId=5, eventType=ACTIVITY_TASK_SCHEDULED,
                     activityType=PaymentActivity_charge, activityId=1]
        Command[commandType=SCHEDULE_ACTIVITY_TASK,
                activityType=InventoryActivity_reserve, activityId=1]
        at io.temporal.internal.statemachines.WorkflowStateMachines.handleCommandEvent
        at io.temporal.internal.replay.ReplayWorkflowRunTaskHandler.handleWorkflowTask

Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.203 s

BUILD FAILED
```

`eventId=5` 에서 히스토리는 `PaymentActivity_charge` 를 기대했는데 새 코드는 `InventoryActivity_reserve` 를 요청했다 — **정확히 어디가 어긋났는지** 알려 줍니다. 운영에 나가기 전에, 3초짜리 CI 단계에서 잡힙니다.

### ④ 히스토리 디렉터리 전체를 도는 파라미터화 테스트

히스토리 하나로는 부족합니다. 정상 완료, 보상 발생, 시그널 수신, Continue-As-New 등 **대표 경로별로** 모아 둡니다.

```java
class OrderWorkflowReplayTest {

    static Stream<Path> histories() throws Exception {
        Path dir = Paths.get(
            OrderWorkflowReplayTest.class.getResource("/histories").toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).toList().stream();
        }
    }

    @ParameterizedTest(name = "replay {0}")
    @MethodSource("histories")
    void 모든_운영_히스토리가_리플레이된다(Path history) throws Exception {
        WorkflowReplayer.replayWorkflowExecution(
            Files.readString(history), OrderWorkflowImpl.class);
    }
}
```

```bash
./gradlew test --tests '*ReplayTest'
```

**결과**
```
OrderWorkflowReplayTest > replay order-1001.json          PASSED
OrderWorkflowReplayTest > replay order-1002-saga.json     PASSED
OrderWorkflowReplayTest > replay order-1003-signal.json   PASSED
OrderWorkflowReplayTest > replay order-1004-can.json      PASSED
OrderWorkflowReplayTest > replay order-1005-timeout.json  PASSED

Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.612 s
```

### ⑤ CI 에 넣기

운영 히스토리를 주기적으로 덤프해 리포지터리에 커밋하는 스크립트입니다.

```bash
#!/usr/bin/env bash
# scripts/dump-histories.sh — 대표 워크플로우 히스토리를 테스트 리소스로 덤프
set -euo pipefail
DEST=src/test/resources/histories
mkdir -p "$DEST"

for wid in order-1001 order-1002-saga order-1003-signal order-1004-can order-1005-timeout; do
  temporal workflow show -w "$wid" --namespace orders --output json > "$DEST/$wid.json"
  echo "dumped $wid ($(wc -c < "$DEST/$wid.json") bytes)"
done
```

**결과**
```
$ ./scripts/dump-histories.sh
dumped order-1001 (18432 bytes)
dumped order-1002-saga (31067 bytes)
dumped order-1003-signal (22910 bytes)
dumped order-1004-can (15588 bytes)
dumped order-1005-timeout (12204 bytes)
```

이 파일들을 커밋하고, **모든 PR 에서 리플레이 테스트를 돌립니다.**

```yaml
# .github/workflows/ci.yml
- name: Replay compatibility check
  run: ./gradlew test --tests '*ReplayTest'
```

> 💡 **실무 팁 — 히스토리는 Retention 안에 덤프해야 한다**
> Namespace 의 Retention Period 가 72시간이면 3일 지난 워크플로우는 `temporal workflow show` 가 NotFound 입니다. 덤프 스크립트를 **매일 도는 크론**에 넣으세요. (Step 12 의 12-2 에서 이어집니다.)

> ⚠️ **함정 — 리플레이 테스트 없이 배포하면 로컬은 항상 통과한다**
> 이것이 이 코스 전체에서 가장 위험한 함정입니다.
> `Workflow.getVersion()` 을 빼먹고 액티비티 순서를 바꾼 커밋을 올려도, **로컬 테스트 30개가 전부 통과합니다.** 워크플로우 테스트는 매번 새 히스토리를 만들어 새 코드로 실행하기 때문에 새 코드끼리는 언제나 일관되기 때문입니다.
> 배포하고 나서야, **이미 진행 중이던 워크플로우 수천 개**가 Workflow Task 를 실패하며 무한 재시도에 빠집니다. `temporal_workflow_task_execution_failed` 메트릭이 치솟고, 워크플로우들은 Running 상태로 얼어붙습니다(Step 12 의 12-8 진단 절차로 이어집니다).
> **리플레이 테스트는 Step 10 의 버저닝이 안전한지 배포 전에 검증할 수 있는 유일한 방법입니다.** 다른 어떤 테스트도 이걸 대신하지 못합니다.

---

## 11-9. 테스트 피라미드

| 층 | 도구 | 무엇을 검증 | 속도 | 개수 |
|---|---|---|---|---|
| ① Activity 단위 | `TestActivityEnvironment` 또는 순수 JUnit | 액티비티 내부 로직, 하트비트, 취소 반응 | ~0.09초 | 많이 (액티비티마다) |
| ② Workflow (모킹) | `TestWorkflowEnvironment` + Mockito | 분기·타이머·시그널·Saga 보상 순서 | ~0.5초 | 많이 (경로마다) |
| ③ 리플레이 | `WorkflowReplayer` + 운영 히스토리 | **배포해도 진행 중 워크플로우가 안 깨지는지** | ~0.15초 | 대표 히스토리 5~20개 |
| ④ 통합 | 실제 Temporal 서버 + 실제 액티비티 | 직렬화, Worker 등록, 네트워크, 외부 시스템 | ~10초+ | 소수 (스모크 1~3개) |

- ①②③ 은 **서버가 필요 없어** 모든 PR 에서 돌립니다.
- ④ 는 `docker compose up -d` 로 서버를 띄워야 하므로 nightly 나 배포 파이프라인에만 둡니다.
- ③ 이 없으면 ①②④ 를 아무리 늘려도 **버저닝 사고를 막지 못합니다.** ③ 은 개수는 가장 적지만 대체 불가능합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `temporal-testing` | `temporal-test-server` 를 포함. **인메모리 Temporal**, Docker 불필요 |
| `TestWorkflowEnvironment` | `newInstance()` → `newWorker()` → `start()` → `close()`. 진짜 히스토리가 쌓인다 |
| `TestWorkflowExtension` | JUnit 5 확장. env·worker·스텁을 파라미터로 주입. 간단한 테스트에 |
| **시간 스킵** | 모든 Worker 가 유휴면 가상 시계가 다음 타이머로 점프. **30일 → 0.412초** |
| `testEnv.sleep(Duration)` | 가상 시각을 밀기만 함. 비용 0. `Thread.sleep` 대신 항상 이것 |
| `setUseTimeskipping(false)` | 외부 스레드 시그널 등 시간 스킵이 방해가 될 때만. 긴 대기 테스트와 섞지 말 것 |
| Activity 모킹 | `mock(X.class, withSettings().withoutAnnotations())` — **어노테이션 제거 필수** |
| 실패 주입 | `thenThrow(ApplicationFailure.newFailure / newNonRetryableFailure)` |
| 재시도 검증 | `.thenThrow().thenThrow().thenReturn()` + `verify(x, times(3))`. 백오프도 스킵됨 |
| `TestActivityEnvironment` | 액티비티 단독. 하트비트 리스너 · `requestCancelActivity()` |
| 시그널·쿼리 | `WorkflowClient.start()` 로 비동기 시작 → `WorkflowStub.fromTyped(wf).getResult()` 로 회수 |
| Saga 보상 | `InOrder` 로 **역순 호출**을 검증. `verify` 만으로는 순서 버그를 못 잡는다 |
| **`WorkflowReplayer`** | 운영 히스토리 + 현재 코드 → 호환되면 통과, 아니면 `NonDeterministicException` |
| 리플레이 CI | 히스토리를 `src/test/resources/histories/` 에 커밋, `@ParameterizedTest` 로 전수 |
| 최대 함정 | 리플레이 테스트가 없으면 **로컬은 항상 통과**한다. 버저닝 사고는 배포 후에 터진다 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `TestWorkflowEnvironment` 를 수동으로 구성하고 정상 주문 테스트를 완성하기
2. `Workflow.sleep(Duration.ofDays(30))` 워크플로우를 2초 미만에 통과시키기 (시간 스킵)
3. 액티비티 mock 등록이 `IllegalArgumentException` 으로 실패하는 코드를 고치기
4. 결제가 2회 실패 후 성공하는 시나리오를 만들고 호출 횟수를 검증하기
5. 비동기 시작 → 시그널 → 쿼리 → 결과 회수의 4단계 테스트 작성하기
6. Saga 보상이 역순인지 `InOrder` 로 검증하기
7. `WorkflowReplayer` 로 히스토리 디렉터리 전체를 도는 `@ParameterizedTest` 작성하기

---

## 다음 단계

테스트로 배포 전 안전성을 확보했다면, 이제 배포 **후**를 다룹니다. Namespace 와 Retention 을 어떻게 잡을지, `temporal` CLI 로 무엇을 볼 수 있는지, terminate 와 cancel 이 왜 완전히 다른지, 그리고 워크플로우가 Running 인데 안 움직일 때 무엇부터 확인할지를 정리합니다. 11-8 에서 남겨 둔 "히스토리는 Retention 안에 덤프해야 한다"는 숙제도 여기서 해결합니다.

→ [Step 12 — 운영](../step-12-operations/)

---

## 실습 파일

이 스텝의 세 파일은 모두 **JUnit 5 테스트 클래스**입니다. 프로덕션 코드가 아니라 `src/test/java/com/example/order/` 에 두고 `./gradlew test` 로 돌립니다. 먼저 `Practice.java` 를 그대로 실행해 11-2 ~ 11-8 의 모든 측정(0.918초 / 0.412초 / 0.147초)을 재현하고, `Exercise.java` 의 7문제를 채운 뒤, `Solution.java` 로 대조합니다.

### Practice.java

본문 11-2 ~ 11-9 의 모든 테스트를 절 번호 주석과 함께 한 파일에 담았습니다.

- 최상위 `Practice` 클래스 안에 `@Nested` 로 구간을 나눴습니다. `WorkflowTests`(11-2·11-4·11-6·11-7), `TimeSkippingTests`(11-3), `ActivityTests`(11-5), `ReplayTests`(11-8) 입니다. `./gradlew test --tests 'com.example.order.Practice$TimeSkippingTests'` 처럼 구간만 골라 돌릴 수 있습니다.
- 액티비티 mock 은 전부 파일 상단의 `static <T> T activityMock(Class<T>)` 헬퍼를 거칩니다. 이 헬퍼가 `withSettings().withoutAnnotations()` 를 감싸고 있으므로 11-4 의 함정을 구조적으로 피합니다. **직접 `mock()` 을 부르지 마세요.**
- `TimeSkippingTests` 는 `System.currentTimeMillis()` 로 벽시계 경과를 재서 `assertThat(wallClock).isLessThan(2000)` 으로 단언합니다. 즉 "빨리 끝났다"가 주석이 아니라 **테스트 조건**입니다. 시간 스킵이 꺼지면 이 테스트가 실패합니다.
- `ReplayTests` 는 `src/test/resources/histories/` 에 `.json` 파일이 있어야 돌아갑니다. 파일이 없으면 `@ParameterizedTest` 가 0건으로 끝나며 조용히 통과하므로, 클래스 안에 `histories() 가 비어 있지 않다`는 가드 테스트를 함께 넣어 두었습니다.
- 파일 맨 아래 `SelfContainedFixtures` 에 `ReviewReminderWorkflow` / `Impl` 과 간단한 액티비티 구현이 들어 있어, 본문의 30일 대기 예제를 별도 파일 없이 그대로 돌릴 수 있습니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 `@Test` 안에 `// TODO: 여기에 작성` 자리가 비어 있고, 단언문은 미리 적혀 있어 **무엇을 만족시켜야 하는지**가 명확합니다.

- **문제 3** 은 다른 문제와 성격이 다릅니다. `mock(PaymentActivity.class)` 로 **일부러 실패하는 코드**가 적혀 있고, 이를 실행해 `IllegalArgumentException: Interface annotated with @ActivityInterface can't be registered...` 를 **눈으로 본 다음** 고치는 문제입니다. 고치기 전에 한 번 돌려 보세요.
- **문제 2** 는 `assertThat(wallClock).isLessThan(2000)` 이 이미 적혀 있습니다. 시간 스킵을 이해하지 못하고 `Thread.sleep` 으로 접근하면 절대 통과할 수 없게 설계했습니다.
- **문제 5** 는 `WorkflowClient.start(...)` 까지만 주어져 있고, 그 뒤 시그널·쿼리·결과 회수를 채워야 합니다. 결과 회수에서 `WorkflowStub.fromTyped` 를 떠올리는 것이 이 문제의 핵심입니다.
- **문제 6** 은 `verify(payment).refund(...)` / `verify(inventory).release(...)` 만 적힌 상태로 시작합니다. 이대로도 **통과합니다.** 이걸 `InOrder` 로 바꿔 순서까지 검증하게 만드는 것이 문제입니다. "통과하는 테스트를 더 엄격하게 만드는" 연습입니다.
- **문제 7** 은 `histories/` 디렉터리에 파일이 없으면 의미가 없으므로, 문제지 상단 주석에 `./scripts/dump-histories.sh` 대신 쓸 수 있는 **샘플 히스토리 JSON 생성 방법**(Practice 의 `WorkflowTests` 를 한 번 돌린 뒤 `testEnv.getDiagnostics()` 를 저장)을 안내해 두었습니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과, "왜 그렇게 써야 하는지"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 2** 의 주석은 시간 스킵이 발동하는 조건("모든 Worker 가 유휴")을 다시 짚고, 왜 `Thread.sleep` 이 답이 될 수 없는지, 그리고 `setUseTimeskipping(false)` 를 켠 채로는 이 테스트가 30일간 돌게 된다는 점을 설명합니다.
- **정답 3** 은 단순히 `withoutAnnotations()` 를 붙이는 데 그치지 않고, **Mockito 가 어노테이션을 프록시로 복사하기 때문**이라는 원인과, `activityMock()` 헬퍼로 재발을 막는 방법까지 씁니다.
- **정답 4** 는 `verify(payment, times(3))` 이 왜 3인지(재시도 2회 + 성공 1회)를 히스토리 이벤트 순서로 풀어 쓰고, 이 테스트가 0.5초에 끝나는 이유가 **재시도 백오프에도 시간 스킵이 적용되기 때문**임을 덧붙입니다.
- **정답 6** 의 주석이 가장 깁니다. `verify` 만 쓴 버전과 `InOrder` 버전을 나란히 두고, 보상 순서를 뒤집은 구현에서 전자는 통과하고 후자는 `VerificationInOrderFailure` 로 실패하는 실제 출력을 붙여 두었습니다.
- **정답 7** 은 `@MethodSource` 가 `Stream<Path>` 를 반환하는 형태와, 히스토리 디렉터리가 비었을 때 **테스트가 0건으로 조용히 통과하는 함정**을 막는 가드(`assertThat(histories()).isNotEmpty()`)를 함께 제시합니다. Step 12 의 Retention 과 연결되는 마무리 주석으로 끝납니다.

```java file="./Solution.java"
```
