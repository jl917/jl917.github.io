package com.example.batch.step03;

/*
 * ============================================================================
 * Step 03 — JobParameters 와 실행 식별 / 실습 코드
 * ============================================================================
 *
 * 배치: Spring Boot 3.2.5 / Spring Batch 5.1.1 / Java 21 / MySQL 8.0.36(127.0.0.1:3308)
 *
 * 놓을 위치:
 *   src/main/java/com/example/batch/step03/Practice.java
 *
 * 실행 예 (zsh / bash 공통 — 쉼표는 bootRun 의 args 구분자이므로 값 안의 쉼표는 \, 로 이스케이프):
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=paramJob,date=2025-03-01"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=incrementJob,date=2025-03-01"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=dailyJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=validatedJob"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=strictJob,date=2025-13-45"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=typedJob,date=2025-03-06\,java.time.LocalDate,dryRun=true\,java.lang.Boolean"
 *   ./gradlew bootRun -Dargs="--spring.batch.job.name=scopedJob,date=2025-03-07"
 *
 *   jar 로 실행할 때는 쉼표가 아니라 공백으로 나열합니다:
 *   java -jar build/libs/spring-batch5-lab-1.0.0.jar --spring.batch.job.name=paramJob date=2025-03-02
 *
 * 메타데이터 초기화:
 *   mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb < batch-reset.sql
 *
 * 이 파일은 최상위 클래스 Practice 안에 @Configuration 을 static class 로 중첩한 구조입니다.
 * 특정 절만 실험하려면 나머지 static class 의 @Configuration 을 잠시 주석 처리하세요.
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.CompositeJobParametersValidator;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

public class Practice {

    /** 본문 전체에서 재사용하는 카운트 쿼리. 하루치 COMPLETED 는 약 389건입니다. */
    static final String COUNT_SQL = """
            SELECT COUNT(*) FROM orders
             WHERE status = 'COMPLETED' AND DATE(ordered_at) = ?
            """;

    // ========================================================================
    // [3-1] JobParameters 는 Job 의 "입력"이자 "신분증"
    // [3-2] 파라미터 전달 경로 (1) bootRun (2) java -jar (3) JobLauncher 직접 호출
    // ========================================================================
    @Configuration
    public static class ParamJobConfig {

        @Bean
        public Job paramJob(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("paramJob", jobRepository)
                    .start(paramStep)
                    .build();
        }

        @Bean
        public Step paramStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              JdbcTemplate jdbcTemplate) {
            return new StepBuilder("paramStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        // JobParameters 로 가는 경로:
                        //   ChunkContext → StepContext → StepExecution → JobParameters
                        JobParameters params = chunkContext.getStepContext()
                                .getStepExecution().getJobParameters();

                        String date = params.getString("date");

                        // ⚠️ date 가 null 이면 SQL 은 DATE(ordered_at) = NULL 이 되어 0건입니다.
                        //    그런데 예외가 아니라 COMPLETED 로 끝납니다. 3-2 의 함정 블록 참고.
                        Integer count = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, date);

                        System.out.printf("[paramStep] date=%s, COMPLETED orders=%d%n", date, count);
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .build();
        }
    }

    /**
     * [3-2] (3) 프로그램에서 JobLauncher 로 직접 실행.
     *
     * application.yml 의 spring.batch.job.enabled 를 false 로 둔 상태에서만 의미가 있습니다.
     * 자동 실행을 켠 채로 두면 Job 이 두 번 도는 것처럼 보입니다.
     *
     * ⚠️ 이 경로로 실행하면 Job 에 붙인 JobParametersIncrementer 는 호출되지 않습니다. ([3-7] 함정)
     */
    @Component
    public static class ManualLauncher {

        private final JobLauncher jobLauncher;
        private final Job paramJob;

        public ManualLauncher(JobLauncher jobLauncher, Job paramJob) {
            this.jobLauncher = jobLauncher;
            this.paramJob = paramJob;
        }

        public void run(String date) throws Exception {
            JobParameters params = new JobParametersBuilder()
                    .addString("date", date)     // 3번째 인자를 생략하면 identifying = true
                    .toJobParameters();
            jobLauncher.run(paramJob, params);
        }
    }

    // ========================================================================
    // [3-5] 해결책 (b) JobParametersIncrementer
    // [3-6] identifying vs non-identifying
    // [3-7] 커스텀 Incrementer
    // ========================================================================
    @Configuration
    public static class IncrementerConfig {

        /**
         * [3-5] RunIdIncrementer — 매 실행마다 run.id 를 1 증가시켜 항상 새 JobInstance 를 만듭니다.
         *
         * ⚠️⚠️ 정산 배치에 절대 붙이지 마세요.
         *
         * run.id 가 붙으면 identifying 파라미터 집합이 매번 달라지므로,
         * JobInstanceAlreadyCompleteException 이 영원히 발생하지 않습니다.
         * 즉 "같은 날짜를 두 번 정산하는" 사고를 프레임워크가 더 이상 막아 주지 않습니다.
         *
         *   - settlement 에 UNIQUE KEY uk_settlement_order (order_id) 가 있으면
         *     → DuplicateKeyException 으로 시끄럽게 실패합니다. (다행)
         *   - UNIQUE 키가 없으면
         *     → 3월 1일 389건이 두 번 INSERT 되어 778행이 되고, 상태는 COMPLETED 입니다. (재앙)
         *
         * RunIdIncrementer 는 "몇 번 돌려도 결과가 같은 멱등한 Job" 에만 씁니다.
         * 예) 전체 통계 테이블을 TRUNCATE 한 뒤 다시 채우는 Job.
         */
        @Bean
        public Job incrementJob(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("incrementJob", jobRepository)
                    .incrementer(new RunIdIncrementer())
                    .start(paramStep)
                    .build();
        }

        /**
         * [3-7] 날짜를 하루씩 전진시키는 커스텀 Incrementer.
         *
         * ⚠️ getNext() 를 호출해 주는 주체는 JobLauncherApplicationRunner 또는
         *    JobOperator.startNextInstance(jobName) 입니다.
         *    jobLauncher.run(job, params) 를 직접 부르면 이 클래스는 호출되지 않습니다.
         */
        public static class DailyDateIncrementer implements JobParametersIncrementer {

            private static final LocalDate START = LocalDate.of(2025, 3, 1);

            @Override
            public JobParameters getNext(JobParameters parameters) {
                LocalDate next;
                if (parameters == null || parameters.getString("date") == null) {
                    next = START;
                } else {
                    next = LocalDate.parse(parameters.getString("date")).plusDays(1);
                }
                JobParameters base = (parameters == null) ? new JobParameters() : parameters;
                return new JobParametersBuilder(base)
                        .addString("date", next.toString())
                        .toJobParameters();
            }
        }

        @Bean
        public Job dailyJob(JobRepository jobRepository, Step paramStep) {
            return new JobBuilder("dailyJob", jobRepository)
                    .incrementer(new DailyDateIncrementer())
                    .start(paramStep)
                    .build();
        }

        /**
         * [3-5] 해결책 (c) allowStartIfComplete(true).
         *
         * 이것은 (a)·(b) 의 대안이 아닙니다. 적용 범위가 Step 이기 때문입니다.
         * JobInstanceAlreadyCompleteException 은 JobExecution 이 만들어지기 "전"에 터지므로
         * 이 옵션으로는 막을 수 없습니다.
         *
         * 진짜 용도: "Step1(임시테이블 초기화) → Step2(대량처리)" 에서 Step2 만 실패해 재시작할 때,
         *           기본 동작은 성공한 Step1 을 건너뜁니다. 그러면 임시 테이블이 안 비워진 채
         *           Step2 가 돌아 데이터가 섞입니다. 이럴 때 Step1 에 붙입니다.
         */
        @Bean
        public Step alwaysRunStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
            return new StepBuilder("alwaysRunStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        System.out.println("[alwaysRunStep] 준비 작업은 재시작 때도 반드시 다시 돕니다.");
                        return RepeatStatus.FINISHED;
                    }, txManager)
                    .allowStartIfComplete(true)
                    .build();
        }

        /**
         * [3-6] identifying / non-identifying 을 코드에서 만드는 두 가지 방법.
         * 어느 쪽을 쓰든 결과는 동일합니다.
         */
        public static JobParameters buildMixedParameters() {
            // (1) JobParameter<T>(value, Class<T>, identifying) 를 직접 생성 — 5.x 시그니처
            JobParameters explicit = new JobParametersBuilder()
                    .addJobParameter("date",
                            new JobParameter<>(LocalDate.of(2025, 3, 1), LocalDate.class, true))
                    .addJobParameter("chunkSize",
                            new JobParameter<>(1000L, Long.class, false))
                    .addJobParameter("executedBy",
                            new JobParameter<>("jenkins-42", String.class, false))
                    .toJobParameters();

            // (2) 빌더 편의 메서드 — 마지막 boolean 인자가 identifying
            JobParameters shorthand = new JobParametersBuilder()
                    .addLocalDate("date", LocalDate.of(2025, 3, 1))
                    .addLong("chunkSize", 1000L, false)
                    .addString("executedBy", "jenkins-42", false)
                    .toJobParameters();

            System.out.println("explicit  = " + explicit);
            System.out.println("shorthand = " + shorthand);
            return shorthand;
        }
    }

    // ========================================================================
    // [3-8] JobParametersValidator
    // ========================================================================
    @Configuration
    public static class ValidatorConfig {

        /**
         * [3-8](1) DefaultJobParametersValidator — 키의 존재 여부만 검사합니다.
         *
         * ⚠️ optionalKeys 를 하나라도 지정하면 화이트리스트 모드가 됩니다.
         *    required + optional 에 없는 키는 전부 거부됩니다.
         *    RunIdIncrementer 와 함께 쓸 거라면 반드시 "run.id" 를 optionalKeys 에 넣으세요.
         *    (넣지 않으면 로컬에서는 멀쩡하고 운영 스케줄러에서만 터집니다.)
         */
        @Bean
        public JobParametersValidator dateRequiredValidator() {
            DefaultJobParametersValidator validator = new DefaultJobParametersValidator(
                    new String[]{"date"},                             // requiredKeys
                    new String[]{"run.id", "chunkSize", "executedBy"} // optionalKeys
            );
            validator.afterPropertiesSet();
            return validator;
        }

        @Bean
        public Job validatedJob(JobRepository jobRepository, Step paramStep,
                                JobParametersValidator dateRequiredValidator) {
            return new JobBuilder("validatedJob", jobRepository)
                    .validator(dateRequiredValidator)
                    .start(paramStep)
                    .build();
        }

        /**
         * [3-8](2) 커스텀 Validator — 값의 형식과 범위까지 검사합니다.
         *
         * 키 존재 검사만으로는 date=NOT-A-DATE 나 date=20250301 을 못 막습니다.
         * 그런 값은 SQL 에서 0건을 반환하며 조용히 COMPLETED 됩니다.
         */
        public static class SettlementDateValidator implements JobParametersValidator {

            private static final LocalDate MIN = LocalDate.of(2025, 1, 1);
            private static final LocalDate MAX = LocalDate.of(2025, 6, 29);

            @Override
            public void validate(JobParameters parameters) throws JobParametersInvalidException {
                if (parameters == null) {
                    throw new JobParametersInvalidException("JobParameters 가 null 입니다.");
                }
                String raw = parameters.getString("date");
                if (raw == null || raw.isBlank()) {
                    throw new JobParametersInvalidException(
                            "필수 파라미터 'date' 가 없습니다. 예: date=2025-03-01");
                }
                LocalDate d;
                try {
                    d = LocalDate.parse(raw);   // ISO-8601(yyyy-MM-dd) 만 허용
                } catch (DateTimeParseException e) {
                    throw new JobParametersInvalidException(
                            "'date' 형식이 잘못되었습니다: '%s' (기대 형식: yyyy-MM-dd)".formatted(raw));
                }
                if (d.isBefore(MIN) || d.isAfter(MAX)) {
                    throw new JobParametersInvalidException(
                            "'date' 가 데이터 보유 기간(%s ~ %s)을 벗어났습니다: %s".formatted(MIN, MAX, d));
                }
            }
        }

        /** 여러 Validator 를 묶습니다. 키 검사 → 값 검사 순으로 실행됩니다. */
        @Bean
        public JobParametersValidator compositeValidator() {
            CompositeJobParametersValidator composite = new CompositeJobParametersValidator();
            composite.setValidators(List.of(
                    new DefaultJobParametersValidator(
                            new String[]{"date"},
                            new String[]{"run.id", "chunkSize", "dryRun"}),
                    new SettlementDateValidator()
            ));
            return composite;
        }

        @Bean
        public Job strictJob(JobRepository jobRepository, Step paramStep,
                             JobParametersValidator compositeValidator) {
            return new JobBuilder("strictJob", jobRepository)
                    .validator(compositeValidator)
                    .start(paramStep)
                    .build();
        }
    }

    // ========================================================================
    // [3-9] 타입 변환 — 5.x 에서 LocalDate / LocalDateTime / Boolean 등으로 확대
    // ========================================================================
    @Configuration
    public static class TypedConfig {

        /**
         * 커맨드라인 표기:
         *   date=2025-03-06                          → java.lang.String   (타입 미지정 시 기본)
         *   date=2025-03-06,java.time.LocalDate      → java.time.LocalDate
         *   dryRun=true,java.lang.Boolean            → java.lang.Boolean
         *   chunkSize=1000,java.lang.Long,false      → java.lang.Long, non-identifying
         *
         * ⚠️ 커맨드라인 표기와 아래 게터는 "한 쌍" 입니다.
         *    date 를 타입 없이(String) 넘겨 놓고 getLocalDate("date") 를 부르면 ClassCastException 입니다.
         *    컴파일은 통과하고 런타임에 터집니다. 일부러 한 번 틀려 보세요.
         */
        public static class TypedTasklet implements Tasklet {

            private final JdbcTemplate jdbcTemplate;

            public TypedTasklet(JdbcTemplate jdbcTemplate) {
                this.jdbcTemplate = jdbcTemplate;
            }

            @Override
            public RepeatStatus execute(org.springframework.batch.core.StepContribution contribution,
                                        org.springframework.batch.core.scope.context.ChunkContext chunkContext) {
                JobParameters params = chunkContext.getStepContext()
                        .getStepExecution().getJobParameters();

                LocalDate date = params.getLocalDate("date");                 // 5.x 신규 게터
                Boolean dryRun = (Boolean) params.getParameter("dryRun").getValue();
                Long chunkSize = params.getLong("chunkSize", 1000L);          // 2번째 인자는 기본값

                Integer count = jdbcTemplate.queryForObject(
                        COUNT_SQL, Integer.class, date.toString());

                System.out.printf("[typedStep] date=%s (LocalDate), dryRun=%s, chunkSize=%d, COMPLETED orders=%d%n",
                        date, dryRun, chunkSize, count);
                return RepeatStatus.FINISHED;
            }
        }

        @Bean
        public Step typedStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              JdbcTemplate jdbcTemplate) {
            return new StepBuilder("typedStep", jobRepository)
                    .tasklet(new TypedTasklet(jdbcTemplate), txManager)
                    .build();
        }

        @Bean
        public Job typedJob(JobRepository jobRepository, Step typedStep) {
            return new JobBuilder("typedJob", jobRepository)
                    .start(typedStep)
                    .build();
        }
    }

    // ========================================================================
    // [3-10] @StepScope 와 늦은 바인딩
    // ========================================================================
    @Configuration
    public static class ScopedConfig {

        /**
         * Bean 은 애플리케이션 기동 시점에 만들어지는데 JobParameters 는 Job 실행 시점에야 정해집니다.
         * @StepScope 는 Bean 생성을 Step 실행 시점까지 미뤄 그 간극을 메웁니다(늦은 바인딩).
         *
         * @StepScope 를 빼면 기동 시점에 BeanExpressionException 이 납니다:
         *   EL1008E: Property or field 'jobParameters' cannot be found on object of type
         *            'org.springframework.beans.factory.config.BeanExpressionContext'
         * 이건 다행히 시끄럽게 실패합니다.
         */
        @Bean
        @StepScope
        public Tasklet dateAwareTasklet(@Value("#{jobParameters['date']}") String date,
                                        JdbcTemplate jdbcTemplate) {
            return (contribution, chunkContext) -> {
                Integer count = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, date);
                System.out.printf("[dateAwareTasklet] date=%s, count=%d%n", date, count);
                return RepeatStatus.FINISHED;
            };
        }

        @Bean
        public Step scopedStep(JobRepository jobRepository,
                               PlatformTransactionManager txManager,
                               Tasklet dateAwareTasklet) {
            return new StepBuilder("scopedStep", jobRepository)
                    .tasklet(dateAwareTasklet, txManager)
                    .build();
        }

        @Bean
        public Job scopedJob(JobRepository jobRepository, Step scopedStep) {
            return new JobBuilder("scopedJob", jobRepository)
                    .start(scopedStep)
                    .build();
        }
    }

    // ========================================================================
    // [3-3] 메타데이터 확인용 SQL — mysql CLI 에 붙여 넣어 실행하세요.
    // ========================================================================
    /*
    -- JobInstance: Job 이름 + identifying 파라미터의 MD5 해시(JOB_KEY)
    --              UNIQUE KEY JOB_INST_UN (JOB_NAME, JOB_KEY) 로 중복이 원천 차단됩니다.
    SELECT JOB_INSTANCE_ID, JOB_NAME, JOB_KEY
      FROM BATCH_JOB_INSTANCE ORDER BY JOB_INSTANCE_ID;

    -- JobExecution: 그 인스턴스를 실제로 돌린 한 번의 시도. JobInstance 와 1:N.
    SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, e.STATUS, e.EXIT_CODE,
           DATE_FORMAT(e.START_TIME,'%H:%i:%s') st, DATE_FORMAT(e.END_TIME,'%H:%i:%s') et
      FROM BATCH_JOB_EXECUTION e ORDER BY e.JOB_EXECUTION_ID;

    -- 5.0 에서 통합된 파라미터 스키마 (4.x 의 STRING_VAL/DATE_VAL/LONG_VAL/DOUBLE_VAL 은 폐기)
    SELECT p.JOB_EXECUTION_ID, p.PARAMETER_NAME, p.PARAMETER_TYPE, p.PARAMETER_VALUE, p.IDENTIFYING
      FROM BATCH_JOB_EXECUTION_PARAMS p
      JOIN BATCH_JOB_EXECUTION e ON e.JOB_EXECUTION_ID = p.JOB_EXECUTION_ID
      JOIN BATCH_JOB_INSTANCE  i ON i.JOB_INSTANCE_ID  = e.JOB_INSTANCE_ID
     WHERE i.JOB_NAME = 'incrementJob'
     ORDER BY p.JOB_EXECUTION_ID, p.PARAMETER_NAME;

    -- 인스턴스 1개에 실행이 여러 개 붙었는지 확인 (FAILED 재실행 시 1:N 이 됩니다)
    SELECT i.JOB_NAME, i.JOB_INSTANCE_ID, COUNT(*) AS executions,
           GROUP_CONCAT(e.STATUS ORDER BY e.JOB_EXECUTION_ID) AS statuses
      FROM BATCH_JOB_INSTANCE i
      JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
     GROUP BY i.JOB_NAME, i.JOB_INSTANCE_ID
     ORDER BY i.JOB_INSTANCE_ID;
    */
}
