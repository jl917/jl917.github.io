package com.example.batch.step02;

/*
 * ============================================================================
 * Step 02 — 연습문제 (6문제)
 * ============================================================================
 *
 * 규칙
 *   - "// 여기에 작성:" 자리를 채웁니다.
 *   - 예측을 요구하는 문제는 반드시 먼저 적고 나서 실행하세요.
 *     예측이 틀린 지점이 이 스텝의 학습 포인트입니다.
 *   - 정답은 Solution.java.
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

public final class Exercise {

    private Exercise() {
    }

    // =========================================================================
    // 문제 1. 4.x 설정 클래스를 5.1 로 마이그레이션하기  ★ 이 스텝의 본체
    // -------------------------------------------------------------------------
    // 아래 주석 블록이 Spring Batch 4.3 기준 원본입니다.
    // 그 아래 Ex1Config 를 5.1 방식으로 완성하세요.
    //
    // 치환해야 할 곳이 다섯 군데입니다. 그중 "컴파일러가 안 잡아 주는 것"이 하나 있습니다.
    // 어느 것인지 찾아서 아래에 적으세요.
    //
    //   컴파일러가 안 잡아 주는 항목:
    //   →
    //   그것을 놓치면 나타나는 증상:
    //   →
    //
    // ── 원본 (4.3) ──────────────────────────────────────────────────────────
    //
    // @Configuration
    // @EnableBatchProcessing
    // public class DailyCloseJobConfig {
    //
    //     private final JobBuilderFactory jobBuilderFactory;
    //     private final StepBuilderFactory stepBuilderFactory;
    //
    //     public DailyCloseJobConfig(JobBuilderFactory jbf, StepBuilderFactory sbf) {
    //         this.jobBuilderFactory = jbf;
    //         this.stepBuilderFactory = sbf;
    //     }
    //
    //     @Bean
    //     public Job dailyCloseJob() {
    //         return jobBuilderFactory.get("dailyCloseJob")
    //                 .start(closeStep())
    //                 .next(archiveStep())
    //                 .build();
    //     }
    //
    //     @Bean
    //     public Step closeStep() {
    //         return stepBuilderFactory.get("closeStep")
    //                 .tasklet((contribution, chunkContext) -> {
    //                     System.out.println("일마감 처리");
    //                     return RepeatStatus.FINISHED;
    //                 })
    //                 .build();
    //     }
    //
    //     @Bean
    //     public Step archiveStep() {
    //         return stepBuilderFactory.get("archiveStep")
    //                 .tasklet((contribution, chunkContext) -> {
    //                     System.out.println("아카이브");
    //                     return RepeatStatus.FINISHED;
    //                 })
    //                 .build();
    //     }
    // }
    // ────────────────────────────────────────────────────────────────────────
    // =========================================================================
    @Configuration
    public static class Ex1Config {

        // 여기에 작성: dailyCloseJob (closeStep → archiveStep)

        // 여기에 작성: closeStep

        // 여기에 작성: archiveStep
    }

    // =========================================================================
    // 문제 2. JobRepository 없는 JobBuilder
    // -------------------------------------------------------------------------
    //   new JobBuilder("ex2Job")          ← deprecated 생성자, JobRepository 없음
    //           .start(someStep)
    //           .build();
    //
    // ★ 실행하기 전에 예측을 적으세요.
    //
    //   (a) 컴파일은 되는가?                                  →
    //   (b) 예외가 난다면 클래스명은?                          →
    //   (c) 예외 메시지는?                                     →
    //   (d) 발생 시점은 "빈 초기화" 인가 "Job 실행" 인가?       →
    //   (e) 이 실패는 시끄러운 실패인가 조용한 실패인가?        →
    //
    // 확인한 뒤 아래에 실제 결과를 적으세요.
    //   실제:
    //   →
    // =========================================================================

    // =========================================================================
    // 문제 3. @EnableBatchProcessing 을 붙이면 사라지는 빈 3개
    // -------------------------------------------------------------------------
    // Practice.java 의 [2-6] TrapConfig 주석을 풀고 아래를 실행해 보세요.
    //
    //   ./gradlew bootRun --args='--debug' 2>&1 | grep -A2 'BatchAutoConfiguration'
    //
    // 사라지는 빈과 그 증상을 매칭하세요.
    //
    //   (1) 빈: ____________________________________
    //       증상: ______________________________________________________
    //
    //   (2) 빈: ____________________________________
    //       증상: ______________________________________________________
    //
    //   (3) 설정 바인딩: ___________________________
    //       증상: ______________________________________________________
    //
    //   세 증상 중 "조용한" 것은 몇 번인가?  →
    //   그것을 탐지하려면 로그에서 무슨 문자열을 찾아야 하나?  →
    //
    // ⚠️ 확인이 끝나면 TrapConfig 를 반드시 다시 주석 처리하세요.
    // =========================================================================

    // =========================================================================
    // 문제 4. ExitStatus 를 바꿔도 .next() 는 진행되는가
    // -------------------------------------------------------------------------
    // 요구사항
    //   - Job 이름: ex4Job
    //   - Step 3개: ex4StepA → ex4StepB → ex4StepC
    //   - ex4StepB 는 contribution.setExitStatus(new ExitStatus("WARN", "..."))
    //     를 호출한 뒤 정상 종료한다.
    //
    // ★ 실행 전 예측
    //   ex4StepC 는 실행되는가?  →
    //   그 이유는?               →
    //
    // 실행 후 확인
    //   SELECT STEP_NAME, STATUS, EXIT_CODE FROM BATCH_STEP_EXECUTION
    //   WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION);
    //
    //   실제 결과와, .next() 가 무엇을 보고 판단하는지 한 문장으로 정리하세요.
    //   →
    // =========================================================================
    @Configuration
    public static class Ex4Config {

        @Bean
        public Job ex4Job(JobRepository jobRepository,
                          Step ex4StepA, Step ex4StepB, Step ex4StepC) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex4StepA(JobRepository jobRepository, PlatformTransactionManager tx) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex4StepB(JobRepository jobRepository, PlatformTransactionManager tx) {
            // 여기에 작성: ExitStatus 를 "WARN" 으로 설정한 뒤 정상 종료
            return null;
        }

        @Bean
        public Step ex4StepC(JobRepository jobRepository, PlatformTransactionManager tx) {
            // 여기에 작성:
            return null;
        }
    }

    // =========================================================================
    // 문제 5. 상태를 가진 Step 을 ExecutionContext 기반으로 고치기
    // -------------------------------------------------------------------------
    // 아래는 "이 Step 이 몇 번째로 처리한 파일인지" 를 세는 코드입니다.
    // 지금은 설정 클래스 필드를 쓰고 있어 같은 JVM 에서 여러 번 실행하면 값이 누적됩니다.
    //
    // ExecutionContext 를 쓰도록 고치세요.
    //   - JobExecution 의 컨텍스트가 아니라 StepExecution 의 컨텍스트를 쓸 것
    //   - 왜 StepExecution 쪽인지 이유를 주석으로 적을 것
    //   - 실행 후 BATCH_STEP_EXECUTION_CONTEXT.SHORT_CONTEXT 에 어떤 JSON 이
    //     저장되는지 확인해 아래에 붙여 넣을 것
    //
    //   저장된 JSON:
    //   →
    // =========================================================================
    @Configuration
    public static class Ex5Config {

        private int fileCount = 0;   // ⚠️ 이 필드를 없애야 합니다

        @Bean
        public Job ex5Job(JobRepository jobRepository, Step ex5Step) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex5Step(JobRepository jobRepository, PlatformTransactionManager tx) {
            // 여기에 작성: fileCount 를 ExecutionContext 로 옮긴 버전
            return null;
        }
    }

    // =========================================================================
    // 문제 6. ResourcelessTransactionManager 와 메타데이터
    // -------------------------------------------------------------------------
    // 요구사항
    //   - Job 이름: ex6Job / Step 이름: ex6Step
    //   - Step 의 트랜잭션 매니저로 new ResourcelessTransactionManager() 를 쓴다
    //   - Tasklet 은 주입된 txManager 의 클래스명을 출력한다
    //
    // ★ 실행 전 예측
    //   (a) BATCH_STEP_EXECUTION 에 행이 남는가?     →
    //   (b) 남는다면/안 남는다면 그 이유는?            →
    //
    // 실행 후 확인
    //   SELECT STEP_NAME, STATUS, COMMIT_COUNT FROM BATCH_STEP_EXECUTION
    //   WHERE STEP_NAME = 'ex6Step';
    //
    //   실제 결과:
    //   →
    //   Step 의 트랜잭션 매니저와 JobRepository 의 트랜잭션 매니저의 관계를 한 문장으로:
    //   →
    // =========================================================================
    @Configuration
    public static class Ex6Config {

        @Bean
        public Job ex6Job(JobRepository jobRepository, Step ex6Step) {
            // 여기에 작성:
            return null;
        }

        @Bean
        public Step ex6Step(JobRepository jobRepository) {
            // 여기에 작성:
            return null;
        }
    }
}
