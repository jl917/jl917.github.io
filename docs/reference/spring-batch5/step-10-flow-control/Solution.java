package com.example.batch.step10;

/*
 * ============================================================================
 *  Step 10 — 흐름 제어 : 정답과 해설
 * ============================================================================
 *  문제를 직접 풀어 본 뒤에 여세요.
 * ============================================================================
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public final class Solution {

    private Solution() {
    }

    /*
     * ========================================================================
     * 정답 1.
     *
     *  (a) 증명용 SQL
     *
     *      SELECT STEP_NAME, STATUS, EXIT_CODE
     *        FROM BATCH_STEP_EXECUTION
     *       WHERE STATUS <> EXIT_CODE
     *       ORDER BY STEP_EXECUTION_ID DESC LIMIT 5;
     *
     *      결과 예시
     *      +-----------+-----------+-----------+
     *      | STEP_NAME | STATUS    | EXIT_CODE |
     *      +-----------+-----------+-----------+
     *      | checkStep | COMPLETED | NO_DATA   |
     *      | checkStep | COMPLETED | HAS_DATA  |
     *      +-----------+-----------+-----------+
     *
     *  (b) 왜 증명이 되는가
     *
     *      두 행 모두 STATUS 는 COMPLETED 로 같습니다. 그런데 흐름은 서로
     *      다른 Step 으로 갈라졌습니다. STATUS 가 같은데 경로가 달라졌다면,
     *      경로를 결정한 것은 STATUS 가 아니라 다른 값 — 즉 EXIT_CODE 입니다.
     *
     *      더 정확히 말하면 BatchStatus 는 enum 이라 값이 8개로 고정입니다.
     *      "NO_DATA" 같은 값은 애초에 표현할 수도 없습니다.
     *      on() 이 임의의 문자열을 받는다는 사실 자체가, 그것이 String 인
     *      ExitStatus 를 본다는 방증입니다.
     * ========================================================================
     */

    // ========================================================================
    // 정답 2.
    //
    //  핵심은 "BatchStatus 는 건드리지 않는다" 입니다.
    //  afterStep 에서 새 ExitStatus 를 반환해도 BatchStatus 는 그대로
    //  COMPLETED 입니다. 둘은 별개의 값입니다.
    //
    //  주의할 점 하나 더 — 이 리스너가 원래 ExitStatus 를 덮어씁니다.
    //  Step 이 실패했을 때도 이 콜백은 호출되므로, 실패 상태를 뭉개지 않도록
    //  방어해야 합니다. 아래 코드는 실패 시 원래 값을 그대로 돌려줍니다.
    //  이 방어가 없으면 실패한 Step 이 "WRITTEN" 으로 보고되어
    //  on("FAILED") 전이를 영영 타지 못합니다. 조용히 틀리는 전형입니다.
    // ========================================================================
    @Configuration
    static class A2 {

        private static final Logger log = LoggerFactory.getLogger(A2.class);

        @Bean
        Step a2SettleStep(JobRepository jobRepository, PlatformTransactionManager tm,
                          JdbcTemplate jdbcTemplate) {
            return new StepBuilder("a2SettleStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution stepExecution) {
                            // 실패한 Step 의 상태는 절대 덮어쓰지 않습니다.
                            if (ExitStatus.FAILED.getExitCode()
                                    .equals(stepExecution.getExitStatus().getExitCode())) {
                                return stepExecution.getExitStatus();
                            }
                            Long cnt = jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM settlement", Long.class);
                            long c = (cnt == null) ? 0L : cnt;
                            log.info("[a2SettleStep] settlement 행 수 = {}", c);
                            return (c == 0) ? new ExitStatus("EMPTY") : new ExitStatus("WRITTEN");
                        }
                    })
                    .build();
        }
    }

    /*
     * ========================================================================
     * 정답 3.
     *
     *  (a)(c) ExitStatus 가 "COMPLETED" 일 때 → q3ExactStep 으로 갑니다.
     *
     *  (b) 이유
     *      네 패턴 중 "COMPLETED", "COMPLETED*", "*" 세 개가 매칭됩니다.
     *      ("COMPLETED_WITH_*" 는 매칭되지 않습니다.)
     *      SimpleFlow 는 전이를 구체성 순으로 정렬해 두고 앞에서부터 검사하며,
     *      가장 구체적인 것이 이깁니다.
     *
     *      구체적 ──────────────────────────────────► 포괄적
     *      "COMPLETED"      와일드카드 0개
     *      "C?MPLETED"      ? 1개 (문자 수 고정)
     *      "COMPLETED_WITH_*"  고정 문자 많음 + * 1개
     *      "COMPLETED*"     고정 문자 적음 + * 1개
     *      "*"              전부
     *
     *      중요한 것은 "소스 코드에 적은 순서는 아무 상관이 없다"는 점입니다.
     *      Q3 에서는 일부러 "COMPLETED*" 를 맨 위에, "COMPLETED" 를 세 번째에
     *      적었습니다. 그래도 정확 일치가 이깁니다.
     *
     *  (d) ExitStatus 가 "COMPLETED_WITH_SKIPS" 였다면
     *      → q3SkipStep 으로 갑니다.
     *
     *      "COMPLETED" 는 정확 일치가 아니므로 탈락.
     *      "C?MPLETED" 도 길이가 안 맞아 탈락.
     *      남은 "COMPLETED_WITH_*" 와 "COMPLETED*" 중
     *      고정 문자가 더 많은 "COMPLETED_WITH_*" 가 이깁니다.
     *
     *      참고 — COMPLETED_WITH_SKIPS 는 가상의 값이 아닙니다.
     *      Step 11 의 faultTolerant + skip 을 쓰고 afterStep 에서 스킵 건수를
     *      확인해 이런 코드를 붙이는 것이 흔한 패턴입니다.
     * ========================================================================
     */

    // ========================================================================
    // 정답 4.
    //
    //  (a) 문제를 드러내는 SQL
    //
    //      SELECT je.JOB_EXECUTION_ID, je.STATUS AS job_status,
    //             se.STEP_NAME, se.STATUS AS step_status
    //        FROM BATCH_JOB_EXECUTION je
    //        JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
    //       WHERE je.STATUS = 'COMPLETED' AND se.STATUS = 'FAILED';
    //
    //      한 행이라도 나오면 "실패가 은폐된 Job" 입니다.
    //      이 쿼리는 운영에서 주기적으로 돌릴 가치가 있습니다.
    //
    //  (b) 왜 위험한가 (세 가지)
    //
    //      1. 모니터링이 침묵합니다.
    //         알람은 대부분 BATCH_JOB_EXECUTION.STATUS='FAILED' 나
    //         프로세스 종료 코드에 걸려 있습니다. 둘 다 정상값입니다.
    //
    //      2. 스케줄러가 성공으로 판정합니다.
    //         BatchLabApplication 의
    //           System.exit(SpringApplication.exit(SpringApplication.run(...)))
    //         이 0 을 반환합니다. Quartz / Airflow / 쿠버네티스 Job 이
    //         모두 성공으로 보고, 후속 잡이 미완성 데이터 위에서 돕니다.
    //
    //      3. 재시작이 막힙니다.
    //         Job 이 COMPLETED 이므로 같은 파라미터로 재실행하면
    //         JobInstanceAlreadyCompleteException 이 납니다.
    //         고쳐서 다시 돌리려면 파라미터를 바꾸거나 메타데이터를
    //         손대야 합니다. 사고 대응 시간이 그만큼 늘어납니다.
    //
    //      (네 번째를 덧붙이면: 정산이 반쯤 된 채로 남습니다.
    //       70,000건 중 일부만 settlement 에 들어간 상태인데 아무도 모릅니다.)
    //
    //  (c) .end("RECOVERED") 로 바꾸면 해결됩니까? → 아닙니다.
    //
    //      .end(String) 은 Job 의 ExitStatus(EXIT_CODE 컬럼)만 바꿉니다.
    //      BatchStatus 는 여전히 COMPLETED 입니다.
    //
    //        BATCH_JOB_EXECUTION.STATUS    = COMPLETED   ← 그대로
    //        BATCH_JOB_EXECUTION.EXIT_CODE = RECOVERED   ← 이것만 바뀜
    //
    //      따라서 (b)의 1·2·3 이 전부 그대로입니다.
    //      "실패처럼 보이는 문자열"을 넣는다고 실패가 되지 않습니다.
    //      상태를 바꾸는 것은 .fail() 뿐입니다.
    //
    //  (d) 고친 코드 ↓
    // ========================================================================
    @Configuration
    static class A4 {

        private static final Logger log = LoggerFactory.getLogger(A4.class);

        @Bean
        Job a4Job(JobRepository jobRepository,
                  Step a4SettleStep, Step a4RecoveryStep, Step a4ReportStep) {
            return new JobBuilder("a4Job", jobRepository)
                    .start(a4SettleStep)
                        .on("COMPLETED").to(a4ReportStep)
                    .from(a4SettleStep)
                        .on("FAILED").to(a4RecoveryStep)   // 복구 Step 은 돌리되
                    .from(a4RecoveryStep)
                        .on("*").fail()                    // Job 은 FAILED 로 끝냅니다
                    .from(a4ReportStep)
                        .on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Step a4SettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a4SettleStep", r)
                    .tasklet((c, cc) -> {
                        throw new IllegalStateException("정산 중 오류");
                    }, tm).build();
        }

        @Bean
        Step a4RecoveryStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a4RecoveryStep", r)
                    .tasklet((c, cc) -> {
                        log.info("[a4Recovery] 정리 완료");
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step a4ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a4ReportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // 정답 5.
    //
    //  절반짜리 답은 이것입니다.
    //
    //      long c = jdbcTemplate.queryForObject(VIP_COUNT_SQL, Long.class, date);
    //      return new FlowExecutionStatus(c == 0 ? "SKIP" : c >= 100 ? "PRIORITY" : "NORMAL");
    //
    //  왜 절반인가 — Decider 는 Step 이 아니라서 실행 이력이 남지 않고,
    //  재시작할 때마다 처음부터 다시 평가됩니다.
    //
    //  사고 시나리오
    //    1차 실행: VIP 주문 87건 → NORMAL → q5NormalStep 이 절반 처리하다 실패
    //    그 사이 데이터가 들어와 VIP 주문이 112건이 됨
    //    재시작:   112건 → PRIORITY → q5PriorityStep 이 시작
    //    → 같은 JobExecution 의 이어서 실행인데 완전히 다른 경로를 탑니다.
    //      q5NormalStep 이 남긴 부분 데이터 위에서 다른 로직이 돕니다.
    //
    //  대응: 판단 근거를 Job ExecutionContext 에 저장하고, 있으면 재사용합니다.
    //        Job ExecutionContext 는 JobExecution 단위로 DB 에 영속되므로
    //        재시작해도 살아 있습니다 (Step 09 참고).
    //
    //  참고 — 캐시하는 것은 "판단 결과"가 아니라 "판단 근거(건수)"입니다.
    //        결과만 저장하면 임계치 정책이 바뀌었을 때 반영할 수 없습니다.
    //        근거를 저장해 두면 코드를 고친 뒤 재시작했을 때 새 정책이
    //        같은 근거에 적용됩니다.
    // ========================================================================
    static class A5Decider implements JobExecutionDecider {

        private static final Logger log = LoggerFactory.getLogger(A5Decider.class);
        private static final long PRIORITY_THRESHOLD = 100L;
        private static final String CTX_KEY = "vipOrderCount";

        private static final String VIP_COUNT_SQL = """
                SELECT COUNT(*) FROM orders o
                  JOIN customers c ON c.customer_id = o.customer_id
                 WHERE o.status = 'COMPLETED' AND c.grade = 'VIP'
                   AND DATE(o.ordered_at) = ?
                """;

        private final JdbcTemplate jdbcTemplate;

        A5Decider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            ExecutionContext ctx = jobExecution.getExecutionContext();
            String date = jobExecution.getJobParameters().getString("date");

            long count;
            if (ctx.containsKey(CTX_KEY)) {
                count = ctx.getLong(CTX_KEY);
                log.info("재시작 — 컨텍스트의 {}={} 를 재사용합니다", CTX_KEY, count);
            } else {
                Long q = jdbcTemplate.queryForObject(VIP_COUNT_SQL, Long.class, date);
                count = (q == null) ? 0L : q;
                ctx.putLong(CTX_KEY, count);      // ← 첫 실행에서만 저장
                log.info("첫 실행 — VIP 주문 {}건 조회", count);
            }

            String status;
            if (count == 0) {
                status = "SKIP";
            } else if (count >= PRIORITY_THRESHOLD) {
                status = "PRIORITY";
            } else {
                status = "NORMAL";
            }
            log.info("VIP {}건 → {}", count, status);
            return new FlowExecutionStatus(status);
        }
    }

    @Configuration
    static class A5 {

        @Bean
        A5Decider a5Decider(JdbcTemplate jdbcTemplate) {
            return new A5Decider(jdbcTemplate);
        }

        @Bean
        Job a5Job(JobRepository jobRepository, A5Decider a5Decider,
                  Step a5PrepareStep, Step a5PriorityStep,
                  Step a5NormalStep, Step a5SkipStep) {
            return new JobBuilder("a5Job", jobRepository)
                    .start(a5PrepareStep)
                    .next(a5Decider)
                        .on("PRIORITY").to(a5PriorityStep)
                    .from(a5Decider)
                        .on("NORMAL").to(a5NormalStep)
                    .from(a5Decider)
                        .on("SKIP").to(a5SkipStep)
                    .from(a5Decider)
                        .on("*").fail()        // 안전망 — 예상 못 한 코드가 오면 조용히 넘어가지 않습니다
                    .end()
                    .build();
        }

        @Bean
        Step a5PrepareStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a5PrepareStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step a5PriorityStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a5PriorityStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step a5NormalStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a5NormalStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step a5SkipStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a5SkipStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // 정답 6.
    //
    //  split 은 Flow 를 받습니다. Step 을 바로 넣을 수 없으므로
    //  FlowBuilder 로 한 겹 감쌉니다.
    //
    //  조인은 자동입니다. add() 에 넣은 모든 흐름이 끝나야 다음으로 갑니다.
    //
    //  확인 포인트: 로그의 스레드 이름이 agg-1 / agg-2 로 갈려야 합니다.
    //    INFO ... [ agg-1] ... Executing step: [q6StatusAggStep]
    //    INFO ... [ agg-2] ... Executing step: [q6GradeAggStep]
    //    INFO ... [  main] ... Executing step: [q6ReportStep]
    //
    //  둘 다 [main] 이면 split 이 아니라 next 로 이어진 것입니다.
    //
    //  주의사항 두 가지
    //    1. SimpleAsyncTaskExecutor 는 요청마다 새 스레드를 만들고
    //       재사용하지 않습니다. 흐름이 많아지면 ThreadPoolTaskExecutor 로.
    //    2. 병렬 흐름 수보다 HikariCP 풀이 커야 합니다.
    //       이 프로젝트는 maximum-pool-size: 20 이라 여유가 있습니다.
    //
    //    3. (가장 중요) split 안의 한 흐름이 실패해도 나머지는 끝까지 돕니다.
    //       중단되지 않습니다. 따라서 split 에 넣는 Step 들은 서로,
    //       그리고 이후 단계와 부수 효과가 독립이어야 합니다.
    //       "실패했는데 부수 효과는 다 일어난" 상태를 견딜 수 있어야 합니다.
    // ========================================================================
    @Configuration
    static class A6 {

        private static final Logger log = LoggerFactory.getLogger(A6.class);

        @Bean
        Job a6Job(JobRepository jobRepository,
                  Step a6StatusAggStep, Step a6GradeAggStep, Step a6ReportStep) {

            Flow statusFlow = new FlowBuilder<Flow>("a6StatusFlow")
                    .start(a6StatusAggStep).build();
            Flow gradeFlow = new FlowBuilder<Flow>("a6GradeFlow")
                    .start(a6GradeAggStep).build();

            Flow parallel = new FlowBuilder<Flow>("a6ParallelAgg")
                    .split(new SimpleAsyncTaskExecutor("agg-"))
                    .add(statusFlow, gradeFlow)
                    .build();

            return new JobBuilder("a6Job", jobRepository)
                    .start(parallel)
                    .next(a6ReportStep)      // 두 흐름이 모두 끝나야 여기 옵니다
                    .end()
                    .build();
        }

        @Bean
        Step a6StatusAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("a6StatusAggStep", r)
                    .tasklet((c, cc) -> {
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT status FROM orders GROUP BY status) x",
                                Integer.class);
                        log.info("[statusAgg] {}개 상태 집계", n);   // 4
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step a6GradeAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("a6GradeAggStep", r)
                    .tasklet((c, cc) -> {
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT grade FROM customers GROUP BY grade) x",
                                Integer.class);
                        log.info("[gradeAgg] {}개 등급 집계", n);    // 4
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step a6ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a6ReportStep", r)
                    .tasklet((c, cc) -> {
                        log.info("[a6Report] 두 집계가 모두 끝난 뒤 실행됩니다");
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }
    }

    /*
     * ========================================================================
     * 정답 7.
     *
     *  (a) ASCII 흐름도
     *
     *                    ┌────────────┐
     *                    │ importStep │
     *                    └─────┬──────┘
     *              "EMPTY"     │      "LOADED"
     *          ┌───────────────┴───────────────┐
     *          ▼                               ▼
     *   ┌──────────────┐              ┌────────────────┐
     *   │ q7NoticeStep │              │ q7ValidateStep │
     *   └──────┬───────┘              └───┬────────┬───┘
     *          │                 "INVALID"│        │"VALID"
     *          │                          ▼        │
     *          │                  ┌──────────────┐ │
     *          │                  │ q7AlertStep  │ │
     *          │                  └──────┬───────┘ │
     *          │                         │ fail()  │
     *          │                         ▼         │
     *          │                    [ FAILED ]     │
     *          │                                   ▼
     *          │                     ┌─────────────────────────┐
     *          │                     │  split("wk-")           │
     *          │                     │  ┌───────────────────┐  │
     *          │                     │  │ q7SettleStep      │  │
     *          │                     │  ├───────────────────┤  │
     *          │                     │  │ q7BackupStep      │  │
     *          │                     │  └───────────────────┘  │
     *          │                     └────────────┬────────────┘
     *          │                                  ▼
     *          │                          ┌───────────────┐
     *          │                          │ q7ReportStep  │
     *          │                          └───────┬───────┘
     *          ▼                                  ▼
     *      [ COMPLETED ]                     [ COMPLETED ]
     *
     *  요구사항 → 코드 번역표
     *
     *    "0건이면 알림만 보내고 정상 종료"
     *        → .from(importStep).on("EMPTY").to(q7NoticeStep)
     *          .from(q7NoticeStep).on("*").end()
     *
     *    "검증 실패면 알림을 보내되 Job 은 반드시 FAILED"
     *        → .from(q7ValidateStep).on("INVALID").to(q7AlertStep)
     *          .from(q7AlertStep).on("*").fail()     ← .end() 였다면 실패 은폐
     *
     *          이 한 줄이 문제 4 와 같은 함정입니다.
     *          "알림 Step 을 태웠으니 흐름은 잘 끝난 것" 이라고 생각해
     *          .end() 를 쓰면 Job 이 COMPLETED 가 되어 알람이 안 울립니다.
     *          알림 Step 을 태우는 것과 Job 을 실패로 끝내는 것은 별개입니다.
     *
     *    "정산과 백업을 병렬로"
     *        → split(new SimpleAsyncTaskExecutor("wk-")).add(settleFlow, backupFlow)
     *
     *    "둘 다 끝나면 리포트"
     *        → split 의 자동 조인 뒤에 .to(q7ReportStep)
     *
     *  (b) 코드 ↓
     * ========================================================================
     */
    @Configuration
    static class A7 {

        @Bean
        Job a7Job(JobRepository jobRepository,
                  Step a7ImportStep, Step a7ValidateStep, Step a7NoticeStep,
                  Step a7AlertStep, Step a7SettleStep, Step a7BackupStep,
                  Step a7ReportStep) {

            Flow parallelWork = new FlowBuilder<Flow>("a7ParallelWork")
                    .split(new SimpleAsyncTaskExecutor("wk-"))
                    .add(new FlowBuilder<Flow>("a7SettleFlow").start(a7SettleStep).build(),
                         new FlowBuilder<Flow>("a7BackupFlow").start(a7BackupStep).build())
                    .build();

            return new JobBuilder("a7Job", jobRepository)
                    .start(a7ImportStep)
                        .on("EMPTY").to(a7NoticeStep)
                    .from(a7ImportStep)
                        .on("LOADED").to(a7ValidateStep)
                    .from(a7ImportStep)
                        .on("*").fail()                   // 안전망
                    .from(a7NoticeStep)
                        .on("*").end()                    // 0건은 정상 종료
                    .from(a7ValidateStep)
                        .on("VALID").to(parallelWork)
                    .from(a7ValidateStep)
                        .on("*").to(a7AlertStep)          // INVALID 포함 그 외 전부
                    .from(a7AlertStep)
                        .on("*").fail()                   // ← 알림은 보내되 Job 은 FAILED
                    .from(parallelWork)
                        .on("*").to(a7ReportStep)
                    .from(a7ReportStep)
                        .on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Step a7ImportStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("a7ImportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution se) {
                            if (ExitStatus.FAILED.getExitCode().equals(se.getExitStatus().getExitCode())) {
                                return se.getExitStatus();
                            }
                            Long n = jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Long.class);
                            return (n == null || n == 0) ? new ExitStatus("EMPTY") : new ExitStatus("LOADED");
                        }
                    })
                    .build();
        }

        @Bean
        Step a7ValidateStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("a7ValidateStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution se) {
                            if (ExitStatus.FAILED.getExitCode().equals(se.getExitStatus().getExitCode())) {
                                return se.getExitStatus();
                            }
                            return new ExitStatus("VALID");
                        }
                    })
                    .build();
        }

        @Bean
        Step a7NoticeStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("a7NoticeStep", r, tm);
        }

        @Bean
        Step a7AlertStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("a7AlertStep", r, tm);
        }

        @Bean
        Step a7SettleStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("a7SettleStep", r, tm);
        }

        @Bean
        Step a7BackupStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("a7BackupStep", r, tm);
        }

        @Bean
        Step a7ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("a7ReportStep", r, tm);
        }

        private Step simple(String name, JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder(name, r).tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }
}
