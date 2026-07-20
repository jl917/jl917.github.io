package com.example.order;

/*
 * Step 10 — 버저닝과 무중단 배포 / Exercise.java
 *
 * 7문제입니다. `// TODO: 여기에 작성` 자리를 채우세요.
 * 정답은 Solution.java 에 있습니다.
 *
 * ⚠️ 문제 6 은 문제 3·4 를 먼저 풀어야 합니다.
 *    MarkerRecorded 가 있는 히스토리가 존재해야 그것을 삭제한 코드로 리플레이할 수 있습니다.
 *
 * 실행: ./gradlew run -PmainClass=com.example.order.Exercise --args="--q=1"
 */

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class Exercise {

    public static final String TASK_QUEUE = "ORDER_TASK_QUEUE";

    // 문제 3 에서 정한 changeId 를 여기에 두고 문제 4·6 에서 재사용하세요.
    // 한번 정하면 절대 바꾸지 않는 것이 규칙입니다.
    // TODO: 여기에 작성 — changeId 상수
    public static final String CHANGE_ID = "";

    public record OrderRequest(String orderId, String customerId, String sku,
                               int qty, long amount, String address) {
    }

    static final ActivityOptions OPTS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    @ActivityInterface
    public interface PaymentActivity {
        @ActivityMethod String charge(String orderId, long amount);
    }

    @ActivityInterface
    public interface FraudActivity {
        @ActivityMethod void check(String customerId, long amount);
    }

    @ActivityInterface
    public interface CreditActivity {
        @ActivityMethod void verify(String customerId);
    }

    @ActivityInterface
    public interface NotificationActivity {
        @ActivityMethod void notifyCustomer(String orderId, String message);
    }

    @WorkflowInterface
    public interface OrderWorkflow {
        @WorkflowMethod String processOrder(OrderRequest req);
    }

    /* ==================================================================
     * 문제 1 — NonDeterministicException 재현
     *
     * (a) Q1V1Impl 로 Worker 를 띄우고 order-3001 을 시작하세요.
     *     temporal workflow show -w order-3001 로 TimerStarted 까지 확인.
     * (b) Worker 를 내리고, Q1V2Impl 로 다시 띄우세요.
     * (c) 콘솔에 찍힌 NonDeterministicException 전문을 아래 주석에 붙여넣고,
     *     "event N" 의 N 이 왜 그 숫자인지 설명하세요.
     *
     * 힌트 — 이벤트 1~4 는 어떤 코드에서도 똑같이 생깁니다. 그게 뭘까요?
     * ================================================================== */
    public static class Q1V1Impl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    public static class Q1V2Impl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            fraud.check(req.customerId(), req.amount());   // 추가된 한 줄
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    // TODO: 여기에 작성 — (c) 예외 전문 + event N 이 그 숫자인 이유
    //
    // 예외 전문:
    //
    //
    // 왜 그 숫자인가:
    //
    //

    /* ==================================================================
     * 문제 2 — 안전/위험 분류 (코드를 짜지 않는 유일한 문제)
     *
     * 아래 8개 변경을 "안전" 또는 "위험"으로 분류하고,
     * 위험한 것은 "히스토리의 무엇과 어긋나는지" 한 줄로 쓰세요.
     * 안전한 것도 단서가 필요하면 함께 쓰세요.
     *
     * 판정 기준: 그 변경이 Command 의 종류·순서·개수를 바꾸는가?
     * (표를 그대로 대입하지 말고 기준으로 판정하세요. 8개 중 3개가 함정입니다.)
     *
     * (a) PaymentActivityImpl.charge() 안의 PG 호출 라이브러리를 교체
     *     → TODO: 여기에 작성
     *
     * (b) 액티비티 인터페이스의 메서드명을 charge → chargePayment 로 변경
     *     → TODO: 여기에 작성
     *
     * (c) ActivityOptions 의 maximumAttempts 를 3 → 10 으로 변경
     *     → TODO: 여기에 작성
     *
     * (d) Workflow.sleep(Duration.ofDays(30)) 를 ofDays(14) 로 변경
     *     → TODO: 여기에 작성
     *
     * (e) 워크플로우 안에 log.info("...") 한 줄 추가
     *     → TODO: 여기에 작성
     *
     * (f) OrderRequest record 에 String couponCode 필드 추가 (워크플로우에서 안 씀)
     *     → TODO: 여기에 작성
     *
     * (g) if (amount > 100000) 조건을 if (amount > 50000) 으로 변경
     *     → TODO: 여기에 작성
     *
     * (h) 워크플로우 메서드의 마지막 세 줄을 private 헬퍼로 추출
     *     → TODO: 여기에 작성
     * ================================================================== */

    /* ==================================================================
     * 문제 3 — 문제 1의 변경을 getVersion 으로 감싸세요.
     *
     * 요구사항:
     *   - getVersion 은 메서드 최상위에서 무조건 호출
     *   - 반환값으로만 분기
     *   - changeId 는 파일 상단 CHANGE_ID 상수 사용
     *   - maxSupported 는 1
     *
     * 확인: 이 구현으로 Worker 를 띄우면 order-3001(문제 1에서 깨진 것)이
     *       다시 진행되어야 합니다.
     *       temporal workflow describe -w order-3001
     *       → Pending Workflow Task 의 Attempt 가 사라져야 합니다.
     * ================================================================== */
    public static class Q3Impl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성 — getVersion 호출

            // TODO: 여기에 작성 — 반환값으로 분기

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    /* ==================================================================
     * 문제 4 — 2차 변경 추가
     *
     * 요구사항:
     *   - maxSupported 를 2 로 올리고, version == 2 일 때 credit.verify() 추가
     *   - 같은 changeId 사용
     *
     * 실행 후 확인 — 세 종류의 실행이 각각 어떤 경로를 타는지 히스토리로 보세요:
     *   order-3001 : 문제 1에서 v1 으로 시작 (마커 없음)
     *   order-3002 : 문제 3 배포 후 시작    (마커 version=1)
     *   order-3003 : 이 배포 후 시작        (마커 version=2)
     *
     *   temporal workflow show -w order-3003 --output json \
     *     | jq '.events[] | select(.eventType=="EVENT_TYPE_MARKER_RECORDED")
     *           | .markerRecordedEventAttributes.details.version.payloads[0].data'
     *
     * 위 Base64 를 디코드한 값을 주석에 쓰세요.
     * ================================================================== */
    public static class Q4Impl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성 — maxSupported = 2 로 확장한 getVersion + 3분기

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    // TODO: 여기에 작성 — order-3001 / 3002 / 3003 의 Base64 디코드 결과
    //   order-3001: (마커 자체가 없음)
    //   order-3002:
    //   order-3003:

    /* ==================================================================
     * 문제 5 — 잘못된 getVersion 을 일부러 작성하세요.
     *
     * 요구사항:
     *   - getVersion 을 if (req.amount() > 100000) 블록 안에 넣습니다
     *   - 그리고 아래 질문에 주석으로 답하세요
     *
     * 질문:
     *   (1) amount=39000 인 실행과 amount=250000 인 실행의 히스토리는
     *       어떻게 달라지나요? (이벤트 번호까지)
     *   (2) 이 버그가 왜 메트릭에서 발견되기 어려운가요?
     *   (3) 나중에 임계값을 100000 → 50000 으로 바꾸면 무슨 일이 일어나나요?
     * ================================================================== */
    public static class Q5BadImpl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // TODO: 여기에 작성 — 일부러 잘못된 코드 (getVersion 을 if 안에)

            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    // TODO: 여기에 작성 — (1)(2)(3) 답
    //
    //

    /* ==================================================================
     * 문제 6 — getVersion 호출을 삭제하고 옛 실행을 리플레이하세요.
     *
     * ⚠️ 문제 3·4 를 먼저 풀어 order-3002(마커 있음)를 만들어 두어야 합니다.
     *
     * (a) 아래 Q6Impl 은 getVersion 이 통째로 빠진 코드입니다.
     *     "이제 v1 실행이 없으니 지워도 되겠지" 라고 판단한 상황입니다.
     * (b) 이 구현으로 Worker 를 띄우고 order-3002 가 어떻게 되는지 보세요.
     * (c) 예외 메시지의 이벤트 타입을 주석에 쓰고, 왜 그런지 설명하세요.
     * (d) 올바른 삭제 순서 3단계를 쓰세요.
     * ================================================================== */
    public static class Q6Impl implements OrderWorkflow {
        private final PaymentActivity payment =
                Workflow.newActivityStub(PaymentActivity.class, OPTS);
        private final FraudActivity fraud =
                Workflow.newActivityStub(FraudActivity.class, OPTS);
        private final CreditActivity credit =
                Workflow.newActivityStub(CreditActivity.class, OPTS);
        private final NotificationActivity notification =
                Workflow.newActivityStub(NotificationActivity.class, OPTS);

        @Override
        public String processOrder(OrderRequest req) {
            // getVersion 이 없다. 분기도 없다.
            fraud.check(req.customerId(), req.amount());
            credit.verify(req.customerId());
            payment.charge(req.orderId(), req.amount());
            Workflow.sleep(Duration.ofDays(30));
            notification.notifyCustomer(req.orderId(), "리뷰를 남겨 주세요");
            return req.orderId() + " COMPLETED";
        }
    }

    // TODO: 여기에 작성
    //   (c) 예외 메시지의 이벤트 타입과 이유:
    //
    //   (d) 올바른 삭제 순서 3단계:
    //       1)
    //       2)
    //       3)

    /* ==================================================================
     * 문제 7 — 깨진 워크플로우를 reset 으로 복구하세요.
     *
     * (a) 문제 1에서 깨뜨린 order-3001 을 reset 합니다:
     *       temporal workflow reset --workflow-id order-3001 \
     *         --type LastWorkflowTask --reason "실습"
     * (b) 출력된 새 RunId 를 주석에 기록하세요.
     * (c) temporal workflow show -w order-3001 로 새 히스토리를 보고,
     *     어느 이벤트부터 재생성됐는지 쓰세요.
     * (d) --type FirstWorkflowTask 로 했다면 무엇이 위험한가요?
     *     Step 09 의 어떤 개념과 연결되나요?
     * ================================================================== */

    // TODO: 여기에 작성 — (b)(c)(d) 답
    //
    //

    // ==================================================================
    // 헬퍼 — 10-6 체크리스트 5번의 카운트를 Java 로
    // ==================================================================
    public static long countRunningByType(WorkflowClient client, String workflowType) {
        CountWorkflowExecutionsRequest req = CountWorkflowExecutionsRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setQuery("WorkflowType='" + workflowType + "' AND ExecutionStatus='Running'")
                .build();
        CountWorkflowExecutionsResponse res = client.getWorkflowServiceStubs()
                .blockingStub().countWorkflowExecutions(req);
        return res.getCount();
    }

    // ==================================================================
    // 헬퍼 — 히스토리를 파일로 떨어뜨린다.
    // Step 11 의 WorkflowReplayer 입력으로 그대로 쓸 수 있습니다.
    //   WorkflowReplayer.replayWorkflowExecutionFromResource(
    //       "histories/order-3001.json", Q3Impl.class);
    // ==================================================================
    public static void dumpHistoryToFile(WorkflowClient client, String workflowId)
            throws Exception {
        String json = client.fetchHistory(workflowId).toJson();
        Path dir = Path.of("src/test/resources/histories");
        Files.createDirectories(dir);
        Path out = dir.resolve(workflowId + ".json");
        Files.writeString(out, json);
        System.out.println("히스토리 저장: " + out.toAbsolutePath());
    }

    public static void main(String[] args) {
        System.out.println("""
                각 문제의 TODO 를 채우고, Practice.java 의 main 을 참고해
                Worker 를 phase 별로 띄우며 order-3001 ~ order-3003 을 실행하세요.

                순서:
                  문제1 → Q1V1Impl 로 order-3001 시작 → Q1V2Impl 로 재기동 (깨짐)
                  문제3 → Q3Impl 로 재기동 (복구) → order-3002 시작
                  문제4 → Q4Impl 로 재기동 → order-3003 시작
                  문제6 → Q6Impl 로 재기동 (order-3002 가 깨짐)
                  문제7 → order-3001 을 reset
                """);
    }
}
