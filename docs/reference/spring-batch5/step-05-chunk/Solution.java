package com.example.batch.step05;

/*
 * ============================================================================
 * Step 05 — 청크 지향 처리 / 정답과 해설
 * ============================================================================
 * 문제를 직접 풀어 본 뒤에 여세요.
 * ============================================================================
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import com.example.batch.step05.Practice.Order;
import com.example.batch.step05.Practice.Settlement;

@Configuration
public class Solution {

    // ========================================================================
    // 정답 1. 4.x → 5.x 마이그레이션
    // ========================================================================
    //
    // 바꿔야 할 것이 정확히 두 가지입니다.
    //
    //   (a) StepBuilderFactory 삭제  →  new StepBuilder(name, jobRepository)
    //       5.0 에서 JobBuilderFactory / StepBuilderFactory 가 모두 제거됐습니다.
    //       "Job 이름과 JobRepository 를 명시적으로 받는다"는 게 5.x 의 설계입니다.
    //       팩터리가 숨겨 주던 JobRepository 를 이제 개발자가 직접 넘깁니다.
    //       → 이건 컴파일 에러로 드러납니다. 안전한 변경입니다.
    //
    //   (b) .chunk(1000)  →  .chunk(1000, txManager)
    //       ← 이게 진짜 함정입니다.
    //
    //       5.x 에도 인자 하나짜리 chunk(int) 오버로드가 남아 있습니다.
    //       SimpleStepBuilder 를 직접 조립해 쓰는 경로를 위해 남겨 둔 것인데,
    //       그 바람에 4.x 코드를 복사해도 "컴파일이 통과합니다."
    //
    //       그리고 Step 빈이 만들어지는 시점에 이렇게 죽습니다.
    //
    //         java.lang.IllegalStateException: A transaction manager must be provided
    //           at org.springframework.util.Assert.state(Assert.java:76)
    //           at org.springframework.batch.core.step.builder.StepBuilderHelper
    //                 .enhance(StepBuilderHelper.java:198)
    //           at org.springframework.batch.core.step.builder.SimpleStepBuilder
    //                 .build(SimpleStepBuilder.java:145)
    //
    //       "컴파일이 됐으니 마이그레이션이 끝났다"고 판단하면 안 되는 이유입니다.
    //       5.x 로 옮길 때는 컴파일러가 아니라 **애플리케이션 기동**까지 확인하세요.
    //
    // 참고: 트랜잭션 매니저를 어디서 받는가?
    //   Boot 3.2 의 자동설정이 DataSourceTransactionManager 를 하나 만들어 둡니다.
    //   JPA 를 함께 쓰면 JpaTransactionManager 가 대신 들어옵니다.
    //   메타데이터 갱신과 업무 데이터 쓰기를 한 트랜잭션으로 묶으려면
    //   이 둘이 같은 DataSource 를 봐야 합니다 (프로젝트 셋업 P-1 참고).

    @Bean
    public Step migratedStep(JobRepository jobRepository,
                             PlatformTransactionManager txManager,
                             ItemReader<Order> orderReader,
                             ItemProcessor<Order, Settlement> settlementProcessor,
                             ItemWriter<Settlement> settlementWriter) {

        return new StepBuilder("migratedStep", jobRepository)   // (a)
                .<Order, Settlement>chunk(1000, txManager)      // (b)
                .reader(orderReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    // ========================================================================
    // 정답 2. 청크 700 일 때의 COMMIT_COUNT = 101
    // ========================================================================
    //
    //   70,000 / 700 = 100 청크. 그런데 답은 101 입니다.
    //
    //   왜 하나 더인가:
    //     Spring Batch 는 ItemReader 가 null 을 반환해야만 "데이터가 끝났다"는
    //     것을 압니다. 이게 ItemReader 인터페이스의 계약입니다.
    //
    //       T read() throws Exception;   // 더 이상 없으면 null
    //
    //     100번째 청크가 70,000번째 아이템을 읽어 커밋해도, 프레임워크는
    //     아직 데이터가 남았는지 모릅니다. 리더에게 물어보지 않았으니까요.
    //     그래서 101번째 사이클을 한 번 더 돌려 read() 를 호출하고,
    //     null 을 받고, 빈 청크로 Step 을 종료합니다.
    //
    //     그 빈 트랜잭션도 커밋되므로 COMMIT_COUNT 가 1 늘어납니다.
    //     DEBUG 로그의 마지막 줄 `read=0` 이 바로 이 사이클입니다.
    //
    //   일반식:  COMMIT_COUNT = ceil(전체건수 / 청크크기) + 1
    //
    //     70,000 / 700   → 100 + 1 = 101
    //     70,000 / 1000  →  70 + 1 =  71
    //     70,000 / 300   → 234 + 1 = 235   (70000/300 = 233.33 → 올림 234)
    //     70,000 / 10000 →   7 + 1 =   8
    //
    //   실무에서 이게 왜 중요한가:
    //     "커밋 횟수로 처리량을 역산"하는 모니터링 대시보드를 만들 때
    //     이 +1 을 빼먹으면 항상 한 청크만큼 과대 집계됩니다.
    //     청크가 작을 때는 오차가 무시할 만하지만(7001 중 1),
    //     청크가 클 때는 8분의 1 — 12.5% 오차입니다.

    public static final int Q2_PREDICTED_COMMIT_COUNT = 101;
    public static final String Q2_REASON =
            "ceil(70000/700)=100 청크 + reader 가 null 을 반환하는 마지막 빈 사이클 1회";

    // ========================================================================
    // 정답 3. 청크 300 → 8.9초. LEFT_SLOPE (왼쪽 내리막)
    // ========================================================================
    //
    //   실측 (3회 중앙값):
    //     INFO --- o.s.batch.core.step.AbstractStep : Step: [settlementStep] executed in 8s914ms
    //
    //   본문 실측값과 나란히 놓으면:
    //
    //     청크    10 → 48.2초   (커밋 7,001회)
    //     청크   100 → 12.7초   (커밋   701회)
    //     청크   300 →  8.9초   (커밋   235회)   ← 여기
    //     청크  1000 →  6.1초   (커밋    71회)   ← 최적
    //     청크 10000 →  7.4초   (커밋     8회)
    //
    //   판정 근거 — LEFT_SLOPE 인 이유 두 가지:
    //
    //   (1) 청크를 300 → 1000 으로 더 키우면 여전히 빨라집니다 (8.9 → 6.1초).
    //       U자 곡선의 오른쪽에 있다면 키울수록 느려져야 합니다.
    //
    //   (2) GC 로그를 보면 메모리가 아직 병목이 아닙니다.
    //         [3.102s][info][gc] GC(4) Pause Young (Normal) 197M->72M(512M) 4.881ms
    //       pause 가 4.9ms 로, 청크 1000 의 7ms 보다도 작습니다.
    //       힙 peak 도 218MB 로 여유가 많습니다.
    //       즉 300 이 느린 건 메모리 때문이 아니라 **커밋 235회의 고정비용**
    //       때문입니다. 전형적인 왼쪽 내리막입니다.
    //
    //   덤으로 배울 점 — 수익 체감:
    //     10 → 100  : 48.2 → 12.7초  (35.5초 단축)
    //     100 → 300 : 12.7 →  8.9초  ( 3.8초 단축)
    //     300 → 1000:  8.9 →  6.1초  ( 2.8초 단축)
    //     1000 → 10000: 6.1 → 7.4초  (오히려 1.3초 손해)
    //
    //     청크 크기를 10배 키울 때마다 얻는 이득이 급격히 줄어듭니다.
    //     그래서 "일단 1,000 부터 시작하라"는 권고가 나옵니다 —
    //     1,000 근처에서는 어느 쪽으로 틀려도 손해가 작기 때문입니다.

    public static final double Q3_MEASURED_SECONDS = 8.9;
    public static final String Q3_VERDICT =
            "LEFT_SLOPE — 더 키우면 여전히 빨라지고(8.9→6.1초), "
            + "GC pause 4.9ms·힙 218MB 로 메모리는 아직 병목이 아님. 커밋 235회가 비용의 주범";

    // ========================================================================
    // 정답 4. failAt=12345, chunkSize=500 → settlement 12,000행
    // ========================================================================
    //
    //   계산식:   ((failAt - 1) / chunkSize) * chunkSize      ← 정수 나눗셈
    //
    //   대입:     ((12345 - 1) / 500) * 500
    //           = (12344 / 500) * 500
    //           = 24 * 500
    //           = 12,000
    //
    //   말로 풀면:
    //     12,345번째 아이템은 25번째 청크(12,001 ~ 12,500)에 속합니다.
    //     그 청크는 통째로 롤백되고, 1~24번 청크만 커밋됩니다.
    //     24 × 500 = 12,000행.
    //
    //   실측:
    //     +----------+
    //     | COUNT(*) |
    //     +----------+
    //     |    12000 |
    //     +----------+
    //
    //   ★ 여기서 핵심은 off-by-one 입니다. 경계를 직접 확인해 보세요.
    //
    //     failAt = 12500 → (12499 / 500) * 500 = 24 * 500 = 12,000
    //         12,500번째는 25번째 청크의 **마지막** 아이템입니다.
    //         그 청크가 롤백되므로 여전히 12,000행.
    //
    //     failAt = 12501 → (12500 / 500) * 500 = 25 * 500 = 12,500
    //         12,501번째는 26번째 청크의 **첫** 아이템입니다.
    //         25번째 청크는 이미 커밋됐으므로 12,500행.
    //
    //     분자가 failAt 이 아니라 (failAt - 1) 인 이유가 여기 있습니다.
    //     failAt / chunkSize 로 계산하면 12500/500 = 25 → 12,500 이라는
    //     틀린 답이 나옵니다. 딱 청크 하나(500건) 만큼 틀립니다.
    //
    //   실무로 옮기면:
    //     "배치가 실패했으니 12,345건까지는 처리됐겠지" 라고 가정하고
    //     12,346번째부터 수동 보정을 돌리면 **345건이 정산에서 누락**됩니다.
    //     실패한 배치의 복구 지점은 추측하지 말고
    //     BATCH_STEP_EXECUTION.WRITE_COUNT 를 읽으세요. 그 값이 12,000 입니다.

    public static final int Q4_FAIL_AT = 12345;
    public static final int Q4_EXPECTED_ROWS = 12000;
    public static final String Q4_FORMULA = "((failAt - 1) / chunkSize) * chunkSize  (정수 나눗셈)";

    // ========================================================================
    // 정답 5. write(List) → write(Chunk)
    // ========================================================================
    //
    //   고친 코드는 아래 FixedWriter 입니다. 변경 자체는 두 줄이지만,
    //   이 문제의 값어치는 코드가 아니라 **왜 이게 무서운가** 에 있습니다.
    //
    //   ── 무엇이 벌어지는가 ─────────────────────────────────────────────
    //
    //   ItemWriter<T> 는 5.x 에서 이렇게 생겼습니다.
    //
    //     public interface ItemWriter<T> {
    //         void write(Chunk<? extends T> chunk) throws Exception;
    //     }
    //
    //   추상 메서드가 하나뿐인 인터페이스인데, 문제의 LegacyWriter 는
    //   write(List<? extends Settlement>) 를 구현했습니다.
    //   시그니처가 다르므로 이건 **인터페이스 구현이 아니라 그냥 새 메서드**입니다.
    //
    //   그렇다면 왜 컴파일 에러가 안 났을까요?
    //   ItemWriter 의 추상 메서드가 구현되지 않았으니 에러가 나야 정상인데—
    //   실제로 인터페이스를 직접 implements 했다면 에러가 납니다.
    //   위험한 건 다음 세 경우입니다.
    //
    //     (1) @Override 없이 추상 클래스(예: AbstractItemStreamItemWriter)를 상속한 경우
    //         → 상위 클래스의 기본 구현이 대신 호출됩니다. 대개 아무 일도 안 합니다.
    //     (2) ItemWriter 를 람다/익명 클래스로 만들면서 위임 대상만 4.x 로 남긴 경우
    //     (3) 여러 writer 를 CompositeItemWriter 로 묶었는데 그중 하나만 4.x 인 경우
    //         → 나머지는 정상 동작하므로 "일부 데이터만 안 들어가는" 형태로 나타납니다.
    //
    //   ── 왜 이게 최악인가 ─────────────────────────────────────────────
    //
    //   결과가 이렇습니다.
    //
    //     STATUS       : COMPLETED     ← 배치는 성공했다고 말합니다
    //     READ_COUNT   : 70000
    //     WRITE_COUNT  : 70000         ← 카운터도 정상입니다
    //     settlement   : 0행           ← 그런데 데이터가 없습니다
    //
    //   WRITE_COUNT 는 "writer 에 넘긴 아이템 수"이지 "DB 에 들어간 행 수"가
    //   아니기 때문에, writer 가 아무것도 안 해도 70,000 으로 찍힙니다.
    //   모니터링도, 알림도, 후속 Step 의 검증도 전부 통과합니다.
    //
    //   ── 안전벨트 ─────────────────────────────────────────────────────
    //
    //   @Override 를 붙이면 컴파일러가 즉시 잡아 줍니다.
    //
    //     error: method does not override or implement a method from a supertype
    //         @Override
    //         ^
    //
    //   4.x → 5.x 마이그레이션에서 @Override 는 선택 사항이 아니라 필수입니다.
    //   "런타임에 조용히 틀리는 것"을 "컴파일 타임에 시끄럽게 실패하는 것"으로
    //   바꿔 주는 한 줄이기 때문입니다.
    //
    //   보너스: 실제 INSERT 여부를 검증하는 후속 Step 을 두는 것도 방법입니다.
    //           settlement 행 수와 WRITE_COUNT 를 대조하는 Tasklet 하나면 됩니다.

    static class FixedWriter implements ItemWriter<Settlement> {

        private final JdbcTemplate jdbc;

        FixedWriter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override                                          // ← 안전벨트
        public void write(Chunk<? extends Settlement> chunk) {   // ← List 가 아니라 Chunk
            for (Settlement s : chunk) {                    // Chunk 는 Iterable
                jdbc.update("""
                            INSERT INTO settlement
                              (order_id, customer_id, settle_date, gross_amount,
                               fee_rate, fee_amount, net_amount)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                        s.orderId(), s.customerId(), s.settleDate(),
                        s.grossAmount(), s.feeRate(), s.feeAmount(), s.netAmount());
            }
        }

        /**
         * 굳이 List 가 필요하다면 getItems() 로 꺼냅니다.
         * 다만 대부분의 경우 Chunk 를 그대로 순회하는 편이 낫습니다 —
         * 순회 중 remove() 가 가능한 ChunkIterator 를 쓸 수 있고,
         * getSkips() 같은 5.x 전용 정보에도 접근할 수 있기 때문입니다.
         */
        @SuppressWarnings("unused")
        private void alsoWorks(Chunk<? extends Settlement> chunk) {
            List<? extends Settlement> items = chunk.getItems();
            jdbc.batchUpdate("INSERT INTO settlement (order_id) VALUES (?)",
                    items.stream().map(s -> new Object[]{s.orderId()}).toList());
        }
    }

    // ========================================================================
    // 정답 6. 안전한 최대 청크 크기
    // ========================================================================
    //
    //   ① 상한 계산
    //
    //      판단식:  chunkSize × (입력 + 출력) × 2  ≤  힙 / 4
    //
    //      좌변의 ×2 는 "입력 N개와 출력 N개가 동시에 살아 있고,
    //      GC 가 회수하기 전 이전 청크의 잔재도 잠시 남는다"는 여유분입니다.
    //      우변의 /4 는 "힙 전체를 청크 하나에 쓸 수는 없다"는 뜻입니다.
    //      나머지 3/4 은 커넥션 풀, Hibernate 1차 캐시, 프레임워크, 그리고
    //      GC 가 일할 여유 공간(headroom)이 씁니다.
    //
    //        입력 + 출력 = 8 KB + 4 KB = 12 KB
    //        힙 / 4      = 512 MB / 4 = 128 MB = 131,072 KB
    //
    //        chunkSize ≤ 131,072 KB / (12 KB × 2)
    //                  = 131,072 / 24
    //                  = 5,461.3
    //                  → 5,461
    //
    //   ② 실제로 고를 값: 1,000
    //
    //      상한 5,461 은 "여기까지는 OOM 이 안 난다"는 선일 뿐이고,
    //      "여기가 가장 빠르다"는 뜻이 전혀 아닙니다.
    //      본문 5-6 의 실측이 정확히 이 점을 보여 줍니다 —
    //      청크 10,000 은 OOM 없이 완주했지만 청크 1,000 보다 느렸습니다.
    //      GC pause 가 7ms 에서 44ms 로 늘었기 때문입니다.
    //
    //      상한 근처를 고른다는 건 **GC 를 감수하겠다**는 선택입니다.
    //      게다가 다음 위험이 남습니다.
    //
    //        - 아이템 크기 8KB 는 평균입니다. 실제 데이터에는 꼬리가 있습니다.
    //          주문 하나에 order_items 가 3건이 아니라 300건인 이상치가
    //          섞여 있으면 그 청크만 폭발합니다.
    //        - 데이터는 늘어납니다. 오늘 8KB 인 아이템이 컬럼 추가로
    //          내년에 12KB 가 되면 상한이 3,640 으로 내려앉습니다.
    //        - 힙 512MB 는 컨테이너 메모리 한도보다 작아야 합니다.
    //          JVM 은 힙 외에 메타스페이스·스레드 스택·다이렉트 버퍼도 씁니다.
    //
    //      1,000 은 상한의 약 1/5 로, 위 세 가지가 동시에 나빠져도
    //      버틸 여유가 있습니다. 그리고 실측상 성능도 최적 구간입니다.
    //
    //   ③ 4스레드로 바꾸면
    //
    //      멀티스레드 Step(Step 13)에서는 각 스레드가 자기 청크를 들고 있습니다.
    //      즉 동시에 살아 있는 아이템이 4배가 됩니다.
    //
    //        chunkSize ≤ 5,461 / 4 = 1,365
    //
    //      실제로 고를 값은 여기서도 여유를 두어 250 ~ 500 이 적당합니다.
    //      "스레드를 4배로 늘렸으니 청크도 4배로" 는 정확히 반대입니다.
    //      스레드를 늘리면 청크는 **줄여야** 합니다.
    //
    //      덧붙여, 멀티스레드 Step 은 청크 크기 × 스레드 수만큼의
    //      DB 커넥션도 동시에 씁니다. application.yml 의
    //      hikari.maximum-pool-size: 20 이 그래서 필요합니다.

    public static final int Q6_UPPER_BOUND = 5461;
    public static final int Q6_CHOSEN = 1000;
    public static final String Q6_WHY_SMALLER =
            "상한은 OOM 이 안 나는 선일 뿐 최적점이 아니다. 상한 근처는 GC pause 를 감수하는 선택이고, "
            + "아이템 크기의 꼬리(이상치)·데이터 증가·힙 외 메모리까지 감안하면 상한의 1/5 이 안전하다. "
            + "실측상 1,000 이 성능 최적 구간이기도 하다.";
    public static final int Q6_UPPER_BOUND_4_THREADS = 1365;

    // ========================================================================
    // 실행용 Job
    // ========================================================================

    @Bean
    public Job solutionJob(JobRepository jobRepository, Step migratedStep) {
        return new JobBuilder("solutionJob", jobRepository)
                .start(migratedStep)
                .build();
    }
}
