package com.example.batch.step10;

/*
 * ============================================================================
 *  Step 10 — 흐름 제어 : 연습문제 (7문제)
 * ============================================================================
 *
 *  각 문제의 "// 여기에 작성:" 자리를 채우세요. 정답은 Solution.java 입니다.
 *
 *  실행 전 준비
 *    mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "TRUNCATE TABLE settlement;"
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
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

public final class Exercise {

    private Exercise() {
    }

    // ========================================================================
    // 문제 1.  [관찰 + SQL]
    //
    //   "on() 이 매칭하는 것은 BatchStatus 가 아니라 ExitStatus 다" 를
    //   메타데이터 한 줄로 증명하세요.
    //
    //   힌트: 어떤 두 컬럼의 값이 서로 다른 행을 보여 주면 증명이 됩니까?
    //
    //   (a) 증명용 SQL
    //       여기에 작성:
    //
    //   (b) 그 결과가 왜 증명이 되는지 한 문장
    //       여기에 작성:
    // ========================================================================

    // ========================================================================
    // 문제 2.
    //   settlement 테이블에 정산 결과가 하나도 안 들어갔으면 "EMPTY",
    //   1건 이상이면 "WRITTEN" 을 ExitStatus 로 내보내는 리스너를 완성하세요.
    //
    //   주의: BatchStatus 는 건드리면 안 됩니다.
    // ========================================================================
    @Configuration
    static class Q2 {

        @Bean
        Step q2SettleStep(JobRepository jobRepository, PlatformTransactionManager tm,
                          JdbcTemplate jdbcTemplate) {
            return new StepBuilder("q2SettleStep", jobRepository)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .listener(new StepExecutionListener() {
                        @Override
                        public ExitStatus afterStep(StepExecution stepExecution) {
                            // 여기에 작성: settlement COUNT(*) 를 조회해 EMPTY / WRITTEN 반환
                            return null;
                        }
                    })
                    .build();
        }
    }

    // ========================================================================
    // 문제 3.  [예측 문제 — 실행하기 전에 답을 적으세요]
    //
    //   아래 Job 에서 q3SourceStep 의 ExitStatus 가 "COMPLETED" 일 때
    //   어느 Step 으로 갑니까?
    //
    //   (a) 예측 (실행 전에 적을 것)
    //       여기에 작성:
    //
    //   (b) 그렇게 판단한 이유
    //       여기에 작성:
    //
    //   (c) 실행 결과
    //       여기에 작성:
    //
    //   (d) ExitStatus 가 "COMPLETED_WITH_SKIPS" 였다면 어디로 갑니까?
    //       여기에 작성:
    // ========================================================================
    @Configuration
    static class Q3 {

        private static final Logger log = LoggerFactory.getLogger(Q3.class);

        @Bean
        Job q3Job(JobRepository jobRepository, PlatformTransactionManager tm) {
            Step src = q3Step("q3SourceStep", jobRepository, tm);
            return new JobBuilder("q3Job", jobRepository)
                    .start(src)
                        .on("COMPLETED*").to(q3Step("q3PrefixStep", jobRepository, tm))
                    .from(src)
                        .on("*").to(q3Step("q3AllStep", jobRepository, tm))
                    .from(src)
                        .on("COMPLETED").to(q3Step("q3ExactStep", jobRepository, tm))
                    .from(src)
                        .on("COMPLETED_WITH_*").to(q3Step("q3SkipStep", jobRepository, tm))
                    .end()
                    .build();
        }

        private Step q3Step(String name, JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder(name, r)
                    .tasklet((c, cc) -> {
                        log.info("[{}] 선택됨", name);
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }
    }

    // ========================================================================
    // 문제 4.  [진단 + 수정]
    //
    //   아래 Job 을 실행하면 Job 은 COMPLETED 로 끝납니다.
    //   그런데 이 Job 에는 심각한 문제가 있습니다.
    //
    //   (a) 문제를 드러내는 SQL 을 쓰세요.
    //       여기에 작성:
    //
    //   (b) 이 상태가 운영에서 왜 위험한지 세 가지를 적으세요.
    //       여기에 작성:
    //
    //   (c) .end("RECOVERED") 로 바꾸면 해결됩니까? 왜 그렇습니까/아닙니까?
    //       여기에 작성:
    //
    //   (d) Job 정의를 고치세요.
    // ========================================================================
    @Configuration
    static class Q4 {

        private static final Logger log = LoggerFactory.getLogger(Q4.class);

        @Bean
        Job q4Job(JobRepository jobRepository,
                  Step q4SettleStep, Step q4RecoveryStep, Step q4ReportStep) {
            return new JobBuilder("q4Job", jobRepository)
                    .start(q4SettleStep)
                        .on("COMPLETED").to(q4ReportStep)
                    .from(q4SettleStep)
                        .on("FAILED").to(q4RecoveryStep).end()
                    // 여기에 작성: 위 한 줄을 어떻게 고쳐야 합니까?
                    .from(q4ReportStep)
                        .on("*").end()
                    .end()
                    .build();
        }

        @Bean
        Step q4SettleStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q4SettleStep", r)
                    .tasklet((c, cc) -> {
                        throw new IllegalStateException("정산 중 오류");
                    }, tm)
                    .build();
        }

        @Bean
        Step q4RecoveryStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q4RecoveryStep", r)
                    .tasklet((c, cc) -> {
                        log.info("[q4Recovery] 정리 완료");
                        return RepeatStatus.FINISHED;
                    }, tm)
                    .build();
        }

        @Bean
        Step q4ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q4ReportStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm)
                    .build();
        }
    }

    // ========================================================================
    // 문제 5.
    //   고객 등급별 정산 방식을 고르는 Decider 를 만드세요.
    //
    //   요구사항
    //     - VIP 고객의 주문이 100건 이상이면 "PRIORITY"
    //     - 1건 이상 100건 미만이면 "NORMAL"
    //     - 0건이면 "SKIP"
    //     - 재시작해도 첫 실행과 같은 경로로 가야 합니다  ← 이게 핵심입니다
    //
    //   힌트: 단순히 decide() 안에서 COUNT(*) 를 부르면 절반만 맞은 답입니다.
    // ========================================================================
    static class Q5Decider implements JobExecutionDecider {

        private final JdbcTemplate jdbcTemplate;

        Q5Decider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            // 여기에 작성:
            //   1) Job ExecutionContext 에 이미 판단 근거가 있으면 그것을 씁니다
            //   2) 없으면 DB 를 조회하고 컨텍스트에 저장합니다
            //   3) 건수에 따라 PRIORITY / NORMAL / SKIP 을 반환합니다
            return null;
        }

        static final String VIP_COUNT_SQL = """
                SELECT COUNT(*) FROM orders o
                  JOIN customers c ON c.customer_id = o.customer_id
                 WHERE o.status = 'COMPLETED' AND c.grade = 'VIP'
                   AND DATE(o.ordered_at) = ?
                """;
    }

    @Configuration
    static class Q5 {

        @Bean
        Job q5Job(JobRepository jobRepository, Q5Decider q5Decider,
                  Step q5PrepareStep, Step q5PriorityStep,
                  Step q5NormalStep, Step q5SkipStep) {
            return new JobBuilder("q5Job", jobRepository)
                    .start(q5PrepareStep)
                    // 여기에 작성: Decider 를 붙이고 세 갈래 + 안전망 전이를 만드세요
                    .build();
        }

        @Bean
        Step q5PrepareStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q5PrepareStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step q5PriorityStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q5PriorityStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step q5NormalStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q5NormalStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }

        @Bean
        Step q5SkipStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q5SkipStep", r)
                    .tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }

    // ========================================================================
    // 문제 6.
    //   q6StatusAggStep 과 q6GradeAggStep 을 병렬로 실행하고,
    //   둘 다 끝난 뒤 q6ReportStep 이 돌게 만드세요.
    //
    //   검증 방법: 로그의 스레드 이름이 갈리는지 확인합니다.
    //             둘 다 [main] 이면 병렬이 아닙니다.
    // ========================================================================
    @Configuration
    static class Q6 {

        private static final Logger log = LoggerFactory.getLogger(Q6.class);

        @Bean
        Job q6Job(JobRepository jobRepository,
                  Step q6StatusAggStep, Step q6GradeAggStep, Step q6ReportStep) {
            return new JobBuilder("q6Job", jobRepository)
                    // 여기에 작성: split 으로 두 집계 Step 을 병렬 실행한 뒤 리포트로
                    .build();
        }

        @Bean
        Step q6StatusAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("q6StatusAggStep", r)
                    .tasklet((c, cc) -> {
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT status FROM orders GROUP BY status) x",
                                Integer.class);
                        log.info("[statusAgg] {}개 상태 집계", n);   // 4
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step q6GradeAggStep(JobRepository r, PlatformTransactionManager tm, JdbcTemplate jdbc) {
            return new StepBuilder("q6GradeAggStep", r)
                    .tasklet((c, cc) -> {
                        Integer n = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM (SELECT grade FROM customers GROUP BY grade) x",
                                Integer.class);
                        log.info("[gradeAgg] {}개 등급 집계", n);    // 4
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }

        @Bean
        Step q6ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder("q6ReportStep", r)
                    .tasklet((c, cc) -> {
                        log.info("[q6Report] 두 집계가 모두 끝난 뒤 실행됩니다");
                        return RepeatStatus.FINISHED;
                    }, tm).build();
        }
    }

    // ========================================================================
    // 문제 7.  [설계 문제 — 다이어그램을 먼저 그리세요]
    //
    //   요구사항
    //     1) importStep 으로 외부 파일을 적재한다
    //     2) 적재 결과가 0건이면 알림만 보내고 정상 종료한다
    //     3) 1건 이상이면 검증(validateStep)을 한다
    //     4) 검증 실패면 알림을 보내되 Job 은 반드시 FAILED 로 끝나야 한다
    //     5) 검증 성공이면 정산(settleStep)과 백업(backupStep)을 병렬로 돌린다
    //     6) 둘 다 끝나면 리포트(reportStep) 후 정상 종료한다
    //
    //   (a) ASCII 흐름도
    //       여기에 작성:
    //
    //   (b) 위 흐름도를 코드로
    // ========================================================================
    @Configuration
    static class Q7 {

        @Bean
        Job q7Job(JobRepository jobRepository,
                  Step importStep, Step q7ValidateStep, Step q7NoticeStep,
                  Step q7AlertStep, Step q7SettleStep, Step q7BackupStep,
                  Step q7ReportStep) {
            return new JobBuilder("q7Job", jobRepository)
                    // 여기에 작성:
                    .build();
        }

        // 아래 Step 들은 이미 준비되어 있습니다.
        @Bean
        Step importStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("importStep", r, tm);
        }

        @Bean
        Step q7ValidateStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7ValidateStep", r, tm);
        }

        @Bean
        Step q7NoticeStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7NoticeStep", r, tm);
        }

        @Bean
        Step q7AlertStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7AlertStep", r, tm);
        }

        @Bean
        Step q7SettleStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7SettleStep", r, tm);
        }

        @Bean
        Step q7BackupStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7BackupStep", r, tm);
        }

        @Bean
        Step q7ReportStep(JobRepository r, PlatformTransactionManager tm) {
            return simple("q7ReportStep", r, tm);
        }

        private Step simple(String name, JobRepository r, PlatformTransactionManager tm) {
            return new StepBuilder(name, r).tasklet((c, cc) -> RepeatStatus.FINISHED, tm).build();
        }
    }
}
