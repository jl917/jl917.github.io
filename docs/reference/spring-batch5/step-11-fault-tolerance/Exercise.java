package com.example.batch.step11;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Step 11 — 연습문제 (6문제)
 *
 * 정답은 Solution.java. 먼저 직접 풀어 보십시오.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ⚠️ 풀이 순서를 지키십시오.
 *
 *   - 문제 1 을 풀기 전에 Practice.PlantBadData.PLANT_SQL 을 실행해
 *     불량 데이터를 심어야 합니다. 안 그러면 skip 이 0건이라 문제가
 *     성립하지 않습니다.
 *
 *   - 문제 5 와 6 은 연속 실습입니다. 5번에서 실패시킨 JobInstance 를
 *     6번에서 그대로 재시작합니다. 5번을 푼 뒤 메타데이터를 초기화하면
 *     6번을 풀 수 없습니다.
 *
 *   - 각 문제 끝의 `-- 검증:` SQL 을 반드시 돌리십시오.
 *     "에러 없이 끝났다"는 정답의 근거가 되지 못합니다.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class Exercise {

    // =====================================================================
    // 문제 1. skipLimit 기본값에 걸려 죽는 지점을 특정하기
    //
    // 아래 Step 은 .skipLimit() 을 명시하지 않았습니다.
    //
    // (a) 이 Step 을 그대로 실행하면 어떻게 됩니까? 예외 메시지를 적으십시오.
    // (b) 몇 번째 불량 데이터에서 죽습니까?
    // (c) 그 불량의 order_id 는 몇입니까?
    //     힌트: 불량은 order_id % 1000 = 0 인 100건입니다.
    // (d) 그 지점은 몇 번째 청크입니까?
    //     힌트: COMPLETED 는 order_id % 10 <= 6 이므로, order_id N 까지의
    //           COMPLETED 건수는 대략 N * 0.7 입니다.
    // (e) skipLimit 을 200 으로 바꾸고 다시 돌려 카운터를 비교하십시오.
    // =====================================================================

    public static Step problem1Step(JobRepository jobRepository,
                                    PlatformTransactionManager txManager,
                                    DataSource dataSource) {
        return new StepBuilder("problem1Step", jobRepository)
                .<Order, Settlement>chunk(1000, txManager)
                .reader(Practice.buildOrderReader(dataSource))
                .processor(new Practice.SettlementProcessor())
                .writer(Practice.buildStrictWriter(dataSource))
                .faultTolerant()
                .skip(IllegalArgumentException.class)
                // 여기에 작성: skipLimit 을 명시하십시오
                //
                .build();
    }

    // (a) 예외 메시지
    // 여기에 작성:
    //

    // (b) 몇 번째 불량에서 죽는가
    // 여기에 작성:
    //

    // (c) 그 불량의 order_id
    // 여기에 작성:
    //

    // (d) 몇 번째 청크인가
    // 여기에 작성:
    //

    // -- 검증:
    // SELECT STEP_NAME, STATUS, READ_COUNT, PROCESS_SKIP_COUNT, COMMIT_COUNT
    // FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;

    // =====================================================================
    // 문제 2. SkipPolicy 를 직접 구현하기
    //
    // 다음 요구사항을 만족하는 SkipPolicy 를 작성하십시오.
    //
    //   ① IllegalArgumentException (데이터 오류)      → 최대 50건까지 skip
    //   ② DataIntegrityViolationException (중복)      → 최대 50건까지 skip
    //   ③ DataAccessResourceFailureException (인프라) → 절대 skip 하지 않음
    //   ④ 그 외 모든 예외                              → skip 하지 않음
    //
    // ⚠️ 이 문제의 진짜 함정은 instanceof 판정 "순서" 입니다.
    //    DataAccessResourceFailureException 은 DataAccessException 의
    //    하위 타입입니다. 넓은 타입을 먼저 검사하면 인프라 장애가
    //    데이터 오류로 오인되어 조용히 skip 됩니다.
    //
    //    그러면 DB 커넥션이 끊긴 상황에서 배치가 "나머지를 전부 skip 하고"
    //    COMPLETED 로 끝납니다. 정산이 안 됐는데 성공했다고 보고합니다.
    //
    // (a) SkipPolicy 를 구현하십시오.
    // (b) 순서를 일부러 뒤집어 보고, 인프라 예외가 skip 되는 것을 확인하십시오.
    // (c) 예외 "종류별로" 한도를 따로 두려면 무엇이 필요합니까?
    //     힌트: shouldSkip 의 skipCount 파라미터는 Step 전체 누적입니다.
    // =====================================================================

    public static class Problem2SkipPolicy implements SkipPolicy {

        @Override
        public boolean shouldSkip(Throwable t, long skipCount)
                throws SkipLimitExceededException {
            // 여기에 작성: 판정 순서에 주의하십시오
            //
            return false;
        }
    }

    // (c) 예외 종류별 한도를 두려면?
    // 여기에 작성:
    //

    // -- 검증: 인프라 예외를 흉내 내려면 실행 중 컨테이너를 잠깐 멈추십시오.
    // docker compose -f docker/docker-compose.yml pause mysql
    // (5초 뒤) docker compose -f docker/docker-compose.yml unpause mysql

    // =====================================================================
    // 문제 3. 스캔 모드를 피해 commit_count 를 70 으로 만들기
    //
    // 11-5 의 writeSkipJob 은 commit_count 가 69,900 이고 34.712초 걸립니다.
    // 쓰기 단계에서 UNIQUE 충돌이 나기 때문입니다.
    //
    // 이 배치를 **결과는 같으면서** commit_count 70, 소요 10초 이내로
    // 바꾸십시오.
    //
    // (a) 핵심 아이디어를 한 문장으로 적으십시오.
    //     힌트: 쓰기에서 터질 조건을 "미리" 알 수 있습니까?
    // (b) 구현하십시오.
    // (c) ⚠️ 함정: 아이템마다 검증 쿼리를 날리면 어떻게 됩니까?
    //     70,000번의 SELECT 가 생깁니다. 스캔 모드보다 느려질 수 있습니다.
    //     이것을 피하려면 어떻게 해야 합니까?
    // (d) 더 나은 답이 있습니다. 애초에 Reader 가 그 행들을 안 읽게 하려면?
    // =====================================================================

    // (a) 핵심 아이디어
    // 여기에 작성:
    //

    public static class Problem3Processor implements ItemProcessor<Order, Settlement> {
        @Override
        public Settlement process(Order order) {
            // 여기에 작성:
            //
            return null;
        }
    }

    // (c) 아이템마다 쿼리를 날리면? 어떻게 피하는가?
    // 여기에 작성:
    //

    // (d) Reader 쿼리로 거르는 방법
    // 여기에 작성:
    //

    // -- 검증: commit_count 가 70 이고 write_count + skip 이 70000 이어야 합니다.
    // SELECT READ_COUNT, WRITE_COUNT, FILTER_COUNT, PROCESS_SKIP_COUNT,
    //        WRITE_SKIP_COUNT, COMMIT_COUNT,
    //        TIMESTAMPDIFF(SECOND, START_TIME, END_TIME) secs
    // FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;

    // =====================================================================
    // 문제 4. 재시도를 정확히 3번 하려면 retryLimit 을 얼마로?
    //
    // (a) "최초 시도 후 재시도를 3번 더" 하고 싶습니다. retryLimit 값은?
    // (b) ExponentialBackOffPolicy(initial=200, multiplier=2.0) 일 때
    //     최악의 경우 총 대기 시간은 몇 ms 입니까? 계산 과정을 적으십시오.
    // (c) maxInterval 을 설정하지 않으면 어떤 문제가 생깁니까?
    // (d) .retry() 는 썼는데 .retryLimit() 을 빠뜨리면 어떻게 됩니까?
    //     에러가 납니까, 아니면 조용히 아무 일도 안 일어납니까?
    // =====================================================================

    public static Step problem4Step(JobRepository jobRepository,
                                    PlatformTransactionManager txManager,
                                    DataSource dataSource) {
        return new StepBuilder("problem4Step", jobRepository)
                .<Order, Settlement>chunk(1000, txManager)
                .reader(Practice.buildOrderReader(dataSource))
                .processor(new Practice.SettlementProcessor())
                .writer(new Practice.FlakyWriter(Practice.buildStrictWriter(dataSource)))
                .faultTolerant()
                .retry(org.springframework.dao.DeadlockLoserDataAccessException.class)
                // 여기에 작성: retryLimit
                //
                .backOffPolicy(Practice.exponentialBackOff())
                .build();
    }

    // (a) retryLimit 값과 이유
    // 여기에 작성:
    //

    // (b) 최악의 총 대기 시간 계산
    // 여기에 작성:
    //

    // (c) maxInterval 미설정 시 문제
    // 여기에 작성:
    //

    // (d) retryLimit 을 빠뜨리면
    // 여기에 작성:
    //

    // =====================================================================
    // 문제 5. 중단과 재시작 (문제 6 과 연속)
    //
    // (a) settlementRestartJob 을 failAt=30000 으로 실행해 실패시키십시오.
    //     실행 후 read_count / commit_count / status 를 기록하십시오.
    //
    //       read_count  = ____
    //       commit_count= ____
    //       status      = ____
    //
    // (b) settlement 테이블에는 몇 행이 있습니까? 왜 그 숫자입니까?
    //     힌트: 실패한 청크는 롤백됩니다.
    //
    // (c) 같은 파라미터로 다시 실행하면 새 JobInstance 가 생깁니까,
    //     아니면 같은 JobInstance 에 붙습니까? 왜입니까?
    //
    // ⚠️ 여기서 메타데이터를 지우지 마십시오. 문제 6 에서 이어서 씁니다.
    // =====================================================================

    // (a) 기록
    // 여기에 작성:
    //

    // (b) settlement 행 수와 이유
    // 여기에 작성:
    //

    // (c) JobInstance 판정
    // 여기에 작성:
    //

    // -- 검증:
    // SELECT ji.JOB_INSTANCE_ID, je.JOB_EXECUTION_ID, je.STATUS,
    //        se.READ_COUNT, se.WRITE_COUNT, se.COMMIT_COUNT
    // FROM BATCH_JOB_INSTANCE ji
    // JOIN BATCH_JOB_EXECUTION je USING (JOB_INSTANCE_ID)
    // JOIN BATCH_STEP_EXECUTION se USING (JOB_EXECUTION_ID)
    // WHERE ji.JOB_NAME = 'settlementRestartJob'
    // ORDER BY je.JOB_EXECUTION_ID;

    // =====================================================================
    // 문제 6. 상태를 저장하는 Reader 직접 만들기 (문제 5 에서 이어짐)
    //
    // Practice.NaiveReader 는 재시작해도 처음부터 다시 읽습니다.
    //
    // (a) 문제 5 의 Job 을 NaiveReader 로 바꿔 재시작하십시오.
    //     read_count 가 얼마입니까? 왜 그렇습니까?
    //
    // (b) ItemStreamReader 를 구현해 재시작이 되게 만드십시오.
    //     open / update / close 를 각각 언제 호출하는지도 적으십시오.
    //
    // (c) ⚠️ ExecutionContext 의 키 이름을 그냥 "index" 로 하면
    //     어떤 문제가 생깁니까? 한 Step 에 Reader 가 둘이라면?
    //
    // (d) 재시작 여부는 어떻게 판정합니까? open() 안의 관용구를 적으십시오.
    //
    // (e) 마지막 질문: 이걸 직접 만들어야 합니까?
    //     실무에서는 어떻게 하는 것이 옳습니까?
    // =====================================================================

    // (a) NaiveReader 의 read_count 와 이유
    // 여기에 작성:
    //

    public static class Problem6Reader implements ItemStreamReader<Order> {

        private final List<Order> orders;
        private int index = 0;

        public Problem6Reader(List<Order> orders) {
            this.orders = orders;
        }

        @Override
        public void open(ExecutionContext ctx) throws ItemStreamException {
            // 여기에 작성: 재시작이면 저장된 위치를 복원하십시오
            //
        }

        @Override
        public Order read() {
            // 여기에 작성:
            //
            return null;
        }

        @Override
        public void update(ExecutionContext ctx) throws ItemStreamException {
            // 여기에 작성: 현재 위치를 기록하십시오
            //
        }

        @Override
        public void close() throws ItemStreamException {
            // 여기에 작성:
            //
        }
    }

    // (b) open / update / close 호출 시점
    // 여기에 작성:
    //

    // (c) 키 이름을 "index" 로 하면 생기는 문제
    // 여기에 작성:
    //

    // (d) 재시작 판정 관용구
    // 여기에 작성:
    //

    // (e) 직접 만들어야 하는가?
    // 여기에 작성:
    //

    // -- 검증: 재시작 후 read_count 가 41,000 (= 70,000 - 29,000) 이어야 합니다.
    // SELECT se.STEP_EXECUTION_ID, se.READ_COUNT, se.WRITE_COUNT, se.STATUS
    // FROM BATCH_STEP_EXECUTION se
    // JOIN BATCH_JOB_EXECUTION je USING (JOB_EXECUTION_ID)
    // JOIN BATCH_JOB_INSTANCE ji USING (JOB_INSTANCE_ID)
    // WHERE ji.JOB_NAME = 'settlementRestartJob'
    // ORDER BY se.STEP_EXECUTION_ID;
}
