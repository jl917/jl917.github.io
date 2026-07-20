package com.example.batch.step12;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterChunk;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;

import javax.sql.DataSource;

/**
 * Step 12 — 연습문제 (6문제)
 *
 * 정답은 Solution.java. 먼저 직접 풀어 보십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 이 스텝의 검증 원칙
 *
 *   리스너는 조용히 실패합니다. 등록이 안 돼도, 시그니처가 틀려도
 *   예외가 나지 않습니다. 따라서 매 문제마다 반드시 두 가지를 하십시오.
 *
 *     ① 로그가 실제로 찍히는지 **눈으로** 확인
 *     ② 각 문제 끝의 `-- 검증:` SQL 실행
 *
 *   "에러 없이 끝났다"는 정답의 근거가 되지 못합니다.
 *
 * 사전 준비:
 *   - Practice.PlantBadData.PLANT_SQL 로 불량 데이터 100건을 심으십시오.
 *   - Practice.CleanUp.DDL 로 s12_bad_order 테이블을 만드십시오.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Exercise {

    // =====================================================================
    // 문제 1. 실패한 Job 에만 알림을 보내는 JobExecutionListener
    //
    // 요구사항:
    //   (a) Job 이 성공하면 아무 알림도 보내지 않습니다.
    //   (b) Job 이 실패하면 실패 원인을 담아 알림을 보냅니다.
    //       힌트: jobExecution.getAllFailureExceptions()
    //   (c) 알림 전송 자체가 실패해도 배치에 영향을 주지 않아야 합니다.
    //       힌트: afterJob 의 예외는 삼켜지지만, 그래서 더 위험합니다.
    //             왜 그런지 한 줄로 적으십시오.
    //   (d) Job 소요 시간도 함께 로그에 남기십시오.
    //
    // 검증 방법: 일부러 실패하는 Job 을 만들어 알림이 가는지,
    //           성공하는 Job 에서는 안 가는지 둘 다 확인하십시오.
    // =====================================================================

    /** 알림 전송을 흉내 내는 스텁. 실제로는 슬랙/메일 클라이언트입니다. */
    public static class Notifier {
        public void sendAlert(String message) {
            System.out.println("[ALERT] " + message);
        }
    }

    public static class Problem1Listener implements JobExecutionListener {

        private final Notifier notifier = new Notifier();

        @Override
        public void afterJob(JobExecution jobExecution) {
            // 여기에 작성:
            //
        }
    }

    // (c) afterJob 의 예외가 삼켜지는 것이 왜 위험한가?
    // 여기에 작성:
    //

    // -- 검증: 실패한 Job 의 원인은 여기에도 남습니다.
    // SELECT JOB_EXECUTION_ID, STATUS, LEFT(EXIT_MESSAGE, 100)
    // FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 3;

    // =====================================================================
    // 문제 2. 애너테이션 리스너가 "조용히 무시"되는 것을 재현하기
    //
    // ★ 이 스텝의 핵심 문제입니다.
    //
    // (a) 아래 세 메서드는 전부 컴파일되고 전부 등록되지만
    //     전부 호출되지 않습니다. 각각 왜인지 적으십시오.
    // (b) 셋 중 하나를 골라 올바르게 고치고, 로그가 찍히는 것을 확인하십시오.
    // (c) ⚠️ 가장 중요한 질문:
    //     이런 실수를 **어떻게 알아차릴 수 있습니까?**
    //     컴파일러도, 스프링도, 로그도 아무 말을 하지 않습니다.
    //     실무에서 쓸 수 있는 방어책을 두 가지 이상 적으십시오.
    // (d) 결론: 인터페이스 방식과 애너테이션 방식 중 무엇을 기본으로
    //     삼아야 합니까? 그 이유는?
    // =====================================================================

    public static class Problem2Listener {

        // ① 왜 호출되지 않는가?
        @BeforeStep
        public void before() {
            System.out.println(">>> 문제2-① 이 줄이 찍히면 성공");
        }

        // ② 왜 호출되지 않는가?
        @AfterChunk
        public void afterChunk(StepExecution stepExecution) {
            System.out.println(">>> 문제2-② 이 줄이 찍히면 성공");
        }

        // ③ 왜 호출되지 않는가?
        //    힌트: 이 메서드에는 애너테이션이 아예 없습니다.
        //          메서드 이름만 맞으면 될까요?
        public void beforeStep(StepExecution stepExecution) {
            System.out.println(">>> 문제2-③ 이 줄이 찍히면 성공");
        }
    }

    // (a) 각각의 이유
    //   ①
    //   ②
    //   ③
    // 여기에 작성:
    //

    // (c) 어떻게 알아차릴 것인가 — 방어책 2가지 이상
    // 여기에 작성:
    //

    // (d) 무엇을 기본으로 삼아야 하는가
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 3. SkipListener vs onProcessError — 트랜잭션 경계 체감하기
    //
    // ★ 이 문제가 12-6 의 핵심을 몸으로 이해하게 합니다.
    //
    // 완전히 같은 기록 로직을 두 곳에 각각 넣고 결과를 비교합니다.
    //
    //   버전 A: SkipListener.onSkipInProcess 에서 s12_bad_order 에 INSERT
    //   버전 B: ItemProcessListener.onProcessError 에서 같은 INSERT
    //
    // (a) 두 버전을 각각 구현하십시오.
    // (b) 각각 실행한 뒤 s12_bad_order 의 행 수를 세십시오.
    //     버전 A: ____ 건
    //     버전 B: ____ 건
    // (c) 숫자가 다릅니다. 왜입니까?
    // (d) 그렇다면 onProcessError 는 대체 언제 쓰는 것입니까?
    //
    // ⚠️ 각 버전을 돌리기 전에 반드시 실행하십시오:
    //      TRUNCATE TABLE s12_bad_order;
    //      TRUNCATE TABLE settlement;
    // =====================================================================

    /** 버전 A — SkipListener */
    public static class Problem3SkipListener implements SkipListener<Order, Settlement> {

        public Problem3SkipListener(DataSource dataSource) {
            // 여기에 작성: JdbcTemplate 준비
            //
        }

        @Override
        public void onSkipInProcess(Order item, Throwable t) {
            // 여기에 작성: s12_bad_order 에 INSERT
            //
        }
    }

    /** 버전 B — ItemProcessListener */
    public static class Problem3ProcessListener
            implements ItemProcessListener<Order, Settlement> {

        public Problem3ProcessListener(DataSource dataSource) {
            // 여기에 작성:
            //
        }

        @Override
        public void onProcessError(Order item, Exception e) {
            // 여기에 작성: 버전 A 와 완전히 같은 INSERT
            //
        }
    }

    // (b) 행 수
    // 여기에 작성:
    //

    // (c) 왜 다른가
    // 여기에 작성:
    //

    // (d) onProcessError 는 언제 쓰는가
    // 여기에 작성:
    //

    // -- 검증:
    // SELECT COUNT(*) AS recorded FROM s12_bad_order;
    // SELECT PROCESS_SKIP_COUNT FROM BATCH_STEP_EXECUTION
    // ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;

    // =====================================================================
    // 문제 4. afterChunk 에서 예외를 던지면 — 데이터는 남고 Step 만 실패
    //
    // Practice.BrokenChunkListener 를 활성화해 실행하십시오.
    //
    // (a) 실행 전에 예측하십시오.
    //     - settlement 행 수:  ____
    //     - Step STATUS:       ____
    //     - COMMIT_COUNT:      ____
    //     - ROLLBACK_COUNT:    ____
    // (b) 실제로 실행해 확인하고 예측과 대조하십시오.
    // (c) "데이터는 남았는데 Step 은 실패"가 모순처럼 보입니다.
    //     왜 모순이 아닌지 설명하십시오.
    // (d) 이 상태에서 같은 파라미터로 재실행하면 어떻게 됩니까?
    //     몇 번째 아이템부터 이어집니까? (Step 11 과 연결)
    //
    // ⚠️ (b) 를 실행한 뒤 settlement 를 TRUNCATE 하지 마십시오.
    //    (d) 에서 재시작 실습에 씁니다.
    // =====================================================================

    // (a) 예측
    // 여기에 작성:
    //

    // (b) 실제 결과
    // 여기에 작성:
    //

    // (c) 왜 모순이 아닌가
    // 여기에 작성:
    //

    // (d) 재실행하면
    // 여기에 작성:
    //

    // -- 검증:
    // SELECT COUNT(*) FROM settlement;
    // SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT,
    //        COMMIT_COUNT, ROLLBACK_COUNT
    // FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 2;

    // =====================================================================
    // 문제 5. 리스너 세 개의 실행 순서 관찰하고 뒤집기
    //
    // (a) StepExecutionListener 를 세 개 만들어 A, B, C 순으로 등록하고
    //     beforeStep / afterStep 의 로그 순서를 기록하십시오.
    //       beforeStep 순서: ____
    //       afterStep  순서: ____
    // (b) 예상과 같습니까? 필터 체인처럼 afterStep 이 역순일 거라고
    //     생각했다면, 실제 결과는 어떻습니까?
    // (c) Ordered 인터페이스를 구현해 순서를 C, B, A 로 뒤집으십시오.
    // (d) ⚠️ 마지막 질문: 순서를 제어할 수 있다는 것과
    //     순서에 의존해도 된다는 것은 다릅니다.
    //     리스너끼리 순서에 의존하게 만들면 왜 위험합니까?
    // =====================================================================

    public static class ListenerA implements StepExecutionListener {
        // 여기에 작성:
        //
    }

    // ListenerB, ListenerC
    // 여기에 작성:
    //

    // (a) 관찰된 순서
    // 여기에 작성:
    //

    // (d) 순서 의존이 위험한 이유
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 6. Item 레벨 리스너의 성능 영향 실측 + 로깅 전략 설계
    //
    // 네 가지 구성을 각각 돌려 시간을 재십시오.
    //
    //   ① 리스너 없음                              ____초
    //   ② afterRead 에 log.debug (레벨 INFO)        ____초
    //   ③ afterRead 에 log.info                     ____초
    //   ④ afterRead 에 log.info + 문자열 concat      ____초
    //      (예: log.info(">>> " + item.order_id()))
    //
    // (a) 표를 채우고 배수를 계산하십시오.
    // (b) ②와 ③의 차이가 큰 이유는 무엇입니까?
    // (c) ③과 ④의 차이는 왜 생깁니까?
    //     힌트: SLF4J 의 {} 플레이스홀더는 언제 문자열을 만듭니까?
    // (d) ⚠️ 운영 함정: ②처럼 log.debug 로 짜 두면 평소엔 안전합니다.
    //     그런데 장애 조사를 하려고 로그 레벨을 DEBUG 로 올리는 순간
    //     무슨 일이 벌어집니까?
    // (e) 위 결과를 바탕으로 "배치에서의 안전한 로깅 전략"을
    //     세 줄로 정리하십시오.
    // =====================================================================

    // (a) 측정표와 배수
    // 여기에 작성:
    //

    // (b) ②와 ③의 차이
    // 여기에 작성:
    //

    // (c) ③과 ④의 차이
    // 여기에 작성:
    //

    // (d) 로그 레벨을 올리는 순간
    // 여기에 작성:
    //

    // (e) 안전한 로깅 전략 3줄
    // 여기에 작성:
    //

    // -- 검증: 소요 시간은 여기서도 확인할 수 있습니다.
    // SELECT STEP_NAME, TIMESTAMPDIFF(SECOND, START_TIME, END_TIME) AS secs
    // FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 5;
}
