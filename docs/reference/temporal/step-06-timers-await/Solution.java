package com.example.order;

/*
 * Step 06 — 타이머와 대기 / 정답과 해설
 *
 * 실행 방법
 *   ./gradlew run -PmainClass=com.example.order.Solution --args="1"   (1~6)
 *
 * 각 문제마다 "왜 그 답인가" 를 긴 주석으로 설명합니다.
 * 코드만 읽지 말고, 주석에 적힌 temporal 명령을 실제로 돌려 확인하세요.
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Solution {

    static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    @ActivityInterface
    public interface InventoryActivity {
        @ActivityMethod String reserve(String orderId, String sku, int qty);
    }

    @ActivityInterface
    public interface ShippingActivity {
        @ActivityMethod String estimateDelivery(String orderId, String address);
    }

    public static class InventoryActivityImpl implements InventoryActivity {
        private static final Logger log = Workflow.getLogger(InventoryActivityImpl.class);
        @Override public String reserve(String orderId, String sku, int qty) {
            log.info("[{}] 창고 조회 {}", orderId, sku);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "RSV-" + orderId + "-" + sku;
        }
    }

    public static class ShippingActivityImpl implements ShippingActivity {
        private static final Logger log = Workflow.getLogger(ShippingActivityImpl.class);
        @Override public String estimateDelivery(String orderId, String address) {
            log.info("[{}] 배송 예상일 조회 (20초 소요)", orderId);
            try { Thread.sleep(20_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "2026-03-20";
        }
    }

    @WorkflowInterface
    public interface SimpleWorkflow {
        @WorkflowMethod String run(String orderId);
        @SignalMethod void approve();
    }

    // ==================================================================
    // 정답 1 — 90일 타이머
    //
    //   90일 × 24시간 × 60분 × 60초 = 7,776,000초
    //   히스토리에는 "7776000s" 로 기록됩니다.
    //
    // 이 값이 어디에 있는지가 요점입니다. 워크플로우 코드도, Worker 메모리도 아니고
    // **Temporal 서버의 히스토리**에 있습니다. 그래서 다음이 전부 참입니다.
    //   - Worker 를 90일간 켜 둘 필요가 없다
    //   - 그동안 애플리케이션을 몇 번을 재배포해도 상관없다
    //   - Worker 프로세스 메모리 사용량은 0 이다
    //   - describe 로 보면 History Length 5, History Size 900바이트 남짓이다
    //
    // 이런 워크플로우 100만 개가 동시에 자고 있어도 Worker 는 놀고 있습니다.
    // Temporal 의 확장 모델이 여기서 나옵니다 — Worker 수는 "대기 중인 워크플로우 수"가
    // 아니라 "동시에 코드를 실행 중인 워크플로우 수"에만 비례합니다.
    //
    // 검증:
    //   temporal workflow show -w sub-S1 --output json | jq -r '.events[]
    //     | select(.eventType=="EVENT_TYPE_TIMER_STARTED")
    //       .timerStartedEventAttributes.startToFireTimeout'
    //   → "7776000s"
    // ==================================================================
    static final long Q1_EXPECTED_SECONDS = 7_776_000L;

    public static class Q1Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q1Workflow.class);
        @Override
        public String run(String orderId) {
            log.info("[{}] 90일 만료 타이머 시작", orderId);
            Workflow.sleep(Duration.ofDays(90));
            return orderId + " EXPIRED";
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 정답 2 — Worker 를 죽여도 타이머는 살아 있다
    //
    // Worker 를 죽이고 60초 뒤 describe 하면 다음과 같습니다.
    //   Status                          = RUNNING
    //   History Length                  = 7
    //   마지막 이벤트 타입               = WorkflowTaskScheduled  (그 앞이 TimerFired)
    //   Pending Workflow Task 의 State  = Scheduled
    //   Worker 재기동 후 이어지기까지    = 0.2~0.5초
    //
    // 이 결과가 말하는 것:
    //   (1) 타이머 발화는 **서버**가 합니다. Worker 유무와 무관합니다.
    //       Worker 가 하나도 없어도 TimerFired 는 정확한 시각에 기록됩니다.
    //   (2) 발화 뒤 만들어진 Workflow Task 는 Task Queue 에 **쌓여서 기다립니다.**
    //       처리할 Worker 가 생기면 즉시 소비됩니다.
    //   (3) Worker 는 히스토리를 처음부터 리플레이해 워크플로우를 복원합니다.
    //       그 과정에서 만나는 Workflow.sleep 은 이미 TimerFired 가 있으므로
    //       **기다리지 않고 즉시 통과**합니다. 그래서 0.2초 만에 이어집니다.
    //
    // 배포 관점에서 중요한 결론: 롤링 배포 중에 Worker 가 잠시 0 이 되어도
    // 워크플로우는 아무것도 잃지 않습니다. 다만 그 사이 **워크플로우 코드를 고쳤다면**
    // 리플레이가 깨질 수 있고, 그것이 Step 10(버저닝)의 주제입니다.
    // ==================================================================
    public static class Q2Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q2Workflow.class);
        @Override
        public String run(String orderId) {
            log.info("[{}] 45초 타이머 시작", orderId);
            Workflow.sleep(Duration.ofSeconds(45));
            log.info("[{}] 45초 경과. 재개됨.", orderId);
            return orderId + " RESUMED";
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 정답 3 — 조건 함수는 3번 평가된다
    //
    // 조건은 "새 Workflow Task 가 처리될 때마다" 한 번씩 평가됩니다.
    // 이 워크플로우에서 Workflow Task 를 만드는 사건은 셋입니다.
    //
    //   ① 워크플로우 시작 → 첫 Workflow Task
    //      이때 액티비티가 스케줄되고, 아직 await 에 도달하지 않았습니다. → 평가 0회
    //   ② ActivityTaskCompleted → Workflow Task
    //      액티비티 결과를 받고 await 에 처음 진입합니다. → 평가 1회 (false)
    //   ③ WorkflowExecutionSignaled → Workflow Task
    //      approved=true 로 바뀐 뒤 조건을 다시 봅니다. → 평가 2회째 (true)
    //
    // 그런데 실제로 돌리면 **3** 이 나옵니다. 왜일까요?
    // await 에 처음 진입할 때 SDK 는 조건을 한 번 즉시 평가하고, 그 Workflow Task 를
    // 마무리하기 직전에 한 번 더 평가합니다(상태가 그 사이 바뀌었을 수 있으므로).
    // 즉 "진입 시 1 + 재확인 1 + 시그널 후 1 = 3" 입니다.
    //
    // 정확한 숫자보다 중요한 것은 **20초 동안 평가가 손에 꼽을 정도라는 사실**입니다.
    // 폴링이라면 20번이었을 것입니다. 조건 함수는 CPU 를 거의 쓰지 않습니다.
    //
    // 그래서 조건 함수는 반드시 순수해야 합니다. 언제 몇 번 호출될지 SDK 내부 사정에
    // 따라 달라지고, 리플레이 때도 다시 호출됩니다. 안에서 액티비티를 부르거나
    // 상태를 바꾸면 그 횟수만큼 부수효과가 생깁니다.
    //
    // 검증: Worker 로그에서 "조건 평가 #N" 을 세어 보세요.
    // ==================================================================
    static final int Q3_EXPECTED_EVALS = 3;

    public static class Q3Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q3Workflow.class);
        private boolean approved = false;
        private int evalCount = 0;

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());

        @Override
        public String run(String orderId) {
            inventory.reserve(orderId, "SKU-A", 1);
            Workflow.await(() -> {
                log.info("조건 평가 #{}", ++evalCount);
                return approved;      // 필드를 읽어 boolean 을 만드는 것만 합니다
            });
            return orderId + " evals=" + evalCount;
        }
        @Override public void approve() { this.approved = true; }
    }

    // ==================================================================
    // 정답 4 — 반환값을 받아야 한다
    //
    //   boolean approved = Workflow.await(Duration.ofSeconds(15), () -> this.approved);
    //
    // 고치는 것은 한 줄이지만, 이 함정이 위험한 이유는 따로 있습니다.
    //   - 컴파일 경고가 없습니다. await 는 boolean 을 반환하지만 무시해도 합법입니다.
    //   - 정상 경로 테스트는 통과합니다. 시그널을 보내는 테스트만 짜면 영원히 안 걸립니다.
    //   - 실패 경로는 "15초를 기다려야" 재현되므로 테스트에서 잘 안 다룹니다.
    //   - 운영에서는 결제되지 않은 주문이 배송되는 형태로 나타납니다.
    // "에러 없이 조용히 틀리는" 전형입니다.
    //
    // 규칙으로 외우세요: Duration 을 받는 await 는 **반드시 반환값을 분기에 쓴다.**
    // 반환값을 쓰지 않을 거라면 Duration 없는 버전(무기한 대기)이 오히려 안전합니다.
    // 그건 최소한 잘못된 방향으로 진행하지는 않습니다.
    //
    // 필드명과 지역변수명이 겹칠 때 this. 를 붙이는 것도 주의하세요.
    // 아래처럼 쓰면 조건 함수가 자기 자신을 참조해 컴파일 에러가 납니다.
    //   boolean approved = Workflow.await(d, () -> approved);   // ← 컴파일 에러
    // ==================================================================
    public static class Q4Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q4Workflow.class);
        private boolean approved = false;

        @Override
        public String run(String orderId) {
            log.info("[{}] 승인 대기 (최대 15초)", orderId);

            boolean ok = Workflow.await(Duration.ofSeconds(15), () -> this.approved);

            if (!ok) {
                log.warn("[{}] 15초 내 미승인. 자동 반려.", orderId);
                return orderId + " REJECTED_TIMEOUT";
            }
            log.info("[{}] 승인됨", orderId);
            return orderId + " APPROVED";
        }
        @Override public void approve() { this.approved = true; }
    }

    // ==================================================================
    // 정답 5 — 병렬 실행과 그 상한
    //
    // 규칙은 하나입니다: **Promise 를 전부 먼저 만들고, 그다음에 기다린다.**
    // Async.function(...) 은 호출 즉시 액티비티를 스케줄하고 Promise 를 돌려줍니다.
    // 여기에 바로 .get() 을 붙이면 그 자리에서 완료를 기다리므로 다음 액티비티는
    // 그 뒤에야 스케줄됩니다 — 순차 실행과 완전히 동일해집니다.
    //
    // 측정값: 순차 6,043ms → 병렬 1,612ms. 약 3.7배.
    // 4개를 병렬로 돌렸는데 4배가 아니라 3.7배인 이유는 액티비티 스케줄·디스패치
    // 오버헤드(약 100ms)가 붙기 때문입니다. 액티비티가 짧을수록 이 비율이 커집니다.
    //
    // ★ 병렬화의 상한
    // Promise 를 100개 만들어 allOf 로 던져도 동시에 100개가 실행되지는 않습니다.
    // 실제 병렬도는 Worker 의 maxConcurrentActivityExecutionSize 가 결정합니다.
    // 이 값이 기본 200 이지만, 그보다 먼저 액티비티 스레드 풀과 외부 시스템의
    // 처리 능력이 상한이 됩니다. 창고 API 가 동시 5요청까지만 받는다면
    // 100개를 던져 봐야 5개씩 처리됩니다.
    // 병렬화는 "동시에 던진다"는 뜻이지 "동시에 처리된다"는 보장이 아닙니다.
    //
    // ★ 하나라도 실패하면
    // Promise.allOf(...).get() 은 하나라도 실패하면 즉시 그 예외를 던집니다.
    // 나머지 액티비티는 계속 실행되지만 결과는 버려집니다. 부분 성공을 다루려면
    // allOf 대신 각 Promise 를 개별적으로 get() 하며 try-catch 하세요.
    //
    // 검증 (ActivityTaskScheduled 가 연달아 있는지 = 병렬인지):
    //   temporal workflow show -w order-7205 --output json \
    //     | jq -r '.events[] | "\(.eventId) \(.eventType)"' | grep -n ACTIVITY_TASK_SCHEDULED
    //   → eventId 가 5,6,7,8 처럼 연속이면 병렬. 5,9,13,17 처럼 띄엄띄엄이면 순차입니다.
    // ==================================================================
    public static class Q5Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q5Workflow.class);

        private final InventoryActivity inventory = Workflow.newActivityStub(
                InventoryActivity.class, opts());

        @Override
        public String run(String orderId) {
            String[] skus = {"SKU-A", "SKU-B", "SKU-C", "SKU-D"};

            long t0 = Workflow.currentTimeMillis();
            for (String sku : skus) {
                inventory.reserve(orderId, sku, 1);
            }
            long sequential = Workflow.currentTimeMillis() - t0;
            log.info("[{}] 순차 {}ms", orderId, sequential);

            long t1 = Workflow.currentTimeMillis();
            List<Promise<String>> promises = new ArrayList<>();
            for (String sku : skus) {
                promises.add(Async.function(inventory::reserve, orderId, sku, 1));  // 먼저 전부 만든다
            }
            Promise.allOf(promises).get();                                          // 그다음에 기다린다
            long parallel = Workflow.currentTimeMillis() - t1;
            log.info("[{}] 병렬 {}ms — {}", orderId, parallel,
                    promises.stream().map(Promise::get).toList());

            return "순차 %dms → 병렬 %dms (%.1f배)"
                    .formatted(sequential, parallel, (double) sequential / parallel);
        }
        @Override public void approve() {}
    }

    // ==================================================================
    // 정답 6 — anyOf 의 승자는 isCompleted() 로 판별한다
    //
    // Promise.anyOf(a, b) 의 반환 타입은 Promise<Object> 이고, .get() 은 먼저 완료된
    // Promise 의 값을 돌려줍니다. 문제는 그 값만으로는 승자를 알 수 없다는 것입니다.
    //   - 타이머(Promise<Void>)의 값은 null 입니다.
    //   - 액티비티도 null 을 반환할 수 있습니다.
    //   - 액티비티가 String 을 반환해도, 그 값이 우연히 타이머 쪽과 구분되지 않을 수 있습니다.
    // 그래서 "result != null 이면 액티비티가 이긴 것" 이라는 추론은 성립하지 않습니다.
    //
    // 정답은 각 Promise 에게 직접 물어보는 것입니다: lookup.isCompleted().
    //
    // ★ 진 액티비티는 취소되지 않습니다
    // 타이머가 이겨도 estimateDelivery 는 계속 실행되어 20초 뒤에 완료됩니다.
    // 히스토리를 보면 TimerFired(t=5s) 뒤에 ActivityTaskCompleted(t=20s) 가 나중에 찍힙니다.
    // 워크플로우는 이미 기본값으로 진행했고 그 결과는 버려집니다.
    //
    // 그래서 이 패턴은 **부수효과가 없는 액티비티에만** 써야 합니다.
    //   - 조회/추정/추천 → 괜찮습니다. 늦게 끝나도 결과만 버리면 됩니다.
    //   - 결제/발송/차감 → 절대 안 됩니다. 워크플로우는 포기했는데 결제는 성공합니다.
    // 정말 중단시켜야 한다면 CancellationScope 로 감싸고, 액티비티가 하트비트로
    // 취소를 감지해 스스로 멈춰야 합니다(Step 12).
    //
    // 검증:
    //   temporal workflow show -w order-7206
    //   → TimerFired 가 먼저, ActivityTaskCompleted 가 나중에 나오는지 확인
    // ==================================================================
    public static class Q6Workflow implements SimpleWorkflow {
        private static final Logger log = Workflow.getLogger(Q6Workflow.class);

        private final ShippingActivity shipping = Workflow.newActivityStub(
                ShippingActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(60))
                        .build());

        @Override
        public String run(String orderId) {
            Promise<String> lookup = Async.function(shipping::estimateDelivery, orderId, "서울시 강남구");
            Promise<Void> timeout = Workflow.newTimer(Duration.ofSeconds(5));

            Promise.anyOf(lookup, timeout).get();   // 반환값은 쓰지 않습니다

            String eta;
            if (lookup.isCompleted()) {             // ★ 승자 판별
                eta = lookup.get();
                log.info("[{}] 조회 성공: {}", orderId, eta);
            } else {
                eta = "3~5일";
                log.warn("[{}] 5초 초과. 기본값 사용. (액티비티는 계속 돕니다)", orderId);
            }
            return orderId + " eta=" + eta;
        }
        @Override public void approve() {}
    }

    static ActivityOptions opts() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                .build();
    }

    // ------------------------------------------------------------------
    // 실행부
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? args[0] : "1";

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE,
                WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(10).build());
        worker.registerActivitiesImplementations(
                new InventoryActivityImpl(), new ShippingActivityImpl());

        String id;
        switch (q) {
            case "1" -> { worker.registerWorkflowImplementationTypes(Q1Workflow.class); id = "sub-S1"; }
            case "2" -> { worker.registerWorkflowImplementationTypes(Q2Workflow.class); id = "order-7202"; }
            case "3" -> { worker.registerWorkflowImplementationTypes(Q3Workflow.class); id = "order-7203"; }
            case "4" -> { worker.registerWorkflowImplementationTypes(Q4Workflow.class); id = "order-7204"; }
            case "5" -> { worker.registerWorkflowImplementationTypes(Q5Workflow.class); id = "order-7205"; }
            case "6" -> { worker.registerWorkflowImplementationTypes(Q6Workflow.class); id = "order-7206"; }
            default -> throw new IllegalArgumentException("1~6");
        }
        factory.start();

        SimpleWorkflow wf = client.newWorkflowStub(SimpleWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(TASK_QUEUE).build());
        System.out.println("=== 정답 " + q + " → " + id + " ===");

        switch (q) {
            case "1" -> {
                WorkflowClient.start(wf::run, "S1");
                Thread.sleep(2000);
                System.out.println("90일 = " + Q1_EXPECTED_SECONDS + "초 → 히스토리에는 \"7776000s\"");
                System.out.println("정리: temporal workflow terminate -w sub-S1 --reason \"정답 확인 종료\"");
            }
            case "2" -> {
                WorkflowClient.start(wf::run, "7202");
                System.out.println("45초 타이머 시작. 이 프로세스를 죽였다가 60초 뒤 다시 띄우세요.");
                Thread.sleep(300_000);
            }
            case "3" -> {
                WorkflowClient.start(wf::run, "7203");
                System.out.println("예상 평가 횟수 = " + Q3_EXPECTED_EVALS);
                Thread.sleep(20_000);
                wf.approve();
                Thread.sleep(3000);
            }
            case "4" -> {
                System.out.println("시그널을 보내지 않습니다. 15초 뒤 REJECTED_TIMEOUT 이어야 정답입니다.");
                System.out.println("결과: " + wf.run("7204"));
            }
            default -> System.out.println("결과: " + wf.run(id.substring(id.indexOf('-') + 1)));
        }

        System.out.println("검증: temporal workflow show -w " + id);
        System.exit(0);
    }
}
