package com.example.batch.step08;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Step 08 — 연습문제 정답과 해설.
 *
 * 문제를 직접 풀어 본 뒤에 여세요.
 */
public class Solution {

    // ======================================================================
    // 정답 1 — 5.x 시그니처로 마이그레이션
    // ======================================================================
    /*
     * 예상 컴파일 에러:
     *   error: Q1_LegacyWriter is not abstract and does not override abstract method
     *          write(Chunk<? extends Settlement>) in ItemWriter
     *   error: method does not override or implement a method from a supertype
     *
     * 4.x 의 write(List<? extends T>) 는 5.0 에서 write(Chunk<? extends T>) 로 바뀌었습니다.
     * 소스 호환성이 깨지는 변경이고, 이것은 "좋은 실패" 입니다 — 컴파일러가 잡아 주니까요.
     * 이 코스에서 다루는 대부분의 함정은 컴파일러가 못 잡는 것들입니다.
     *
     * 마이그레이션은 어댑터 한 줄이면 끝납니다. 본문(doWrite)은 손대지 않습니다.
     * Chunk 로 바뀐 실질적 이득은 getSkips() / getErrors() 처럼
     * "아이템 말고 다른 정보"를 함께 실을 수 있다는 점입니다. List 로는 불가능했습니다.
     */
    public static class A1_MigratedWriter implements ItemWriter<Settlement> {

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            doWrite(chunk.getItems());
        }

        private void doWrite(List<? extends Settlement> items) {
            System.out.println("wrote " + items.size() + " items");
        }
    }

    // ======================================================================
    // 정답 2 — record 와 beanMapped()
    // ======================================================================
    /*
     * (a)
     *   예외 클래스: org.springframework.dao.InvalidDataAccessApiUsageException
     *   메시지 요지: No value supplied for the SQL parameter 'orderId':
     *               Invalid property 'orderId' of bean class
     *               [com.example.batch.domain.Settlement]
     *
     *   이유: beanMapped() 는 BeanPropertyItemSqlParameterSourceProvider 를 쓰고,
     *         이 클래스는 BeanWrapper 를 통해 "자바빈 규약"으로 값을 읽습니다.
     *         자바빈 규약의 읽기 접근자는 getOrderId() 입니다.
     *         record 의 접근자는 orderId() — get 접두사가 없습니다.
     *         그래서 BeanWrapper 는 orderId 라는 읽을 수 있는 프로퍼티가 없다고 판단합니다.
     *
     * (b) settlement 가 nullable 이었다면 — 이쪽이 진짜 무서운 경우입니다.
     *   writeCount = 70000
     *   Job STATUS = COMPLETED
     *   데이터     = 70,000행이 들어가 있고 order_id 부터 net_amount 까지 전부 NULL
     *
     *   로그도 정상, 카운터도 정상, 행 수도 정상입니다.
     *   "정산이 안 맞는다"는 문의가 며칠 뒤에 옵니다.
     *
     *   실제로 재현해 보려면:
     *     CREATE TABLE settlement_nullable LIKE settlement;
     *     ALTER TABLE settlement_nullable
     *       MODIFY gross_amount DECIMAL(12,2) NULL,
     *       MODIFY net_amount   DECIMAL(12,2) NULL,
     *       DROP INDEX uk_settlement_order;
     *   그리고 이 테이블로 beanMapped() writer 를 돌려 보세요.
     *
     *   교훈: 결과 테이블의 컬럼은 되도록 NOT NULL 로 만드세요.
     *         제약은 성능 장치이기 이전에 "조용한 실패를 시끄럽게 만드는 장치" 입니다.
     *         project 스펙의 settlement 가 전 컬럼 NOT NULL + uk_settlement_order 인 것은
     *         우연이 아닙니다.
     *
     * (c) 정답 코드는 아래.
     *     장황해 보이지만 record 의 컴포넌트 이름을 바꾸면 여기서 컴파일 에러가 납니다.
     *     beanMapped() 는 리팩터링을 따라오지 못하고, 런타임에야 알려 줍니다.
     *
     *     읽기 쪽도 대칭입니다: BeanPropertyRowMapper 는 record 를 못 채우고,
     *     DataClassRowMapper 가 생성자 기반으로 채웁니다(Step 06).
     */
    public static class A2_RecordWriter {

        public JdbcBatchItemWriter<Settlement> build(DataSource dataSource) {
            return new JdbcBatchItemWriterBuilder<Settlement>()
                    .dataSource(dataSource)
                    .sql("""
                            INSERT INTO settlement
                              (order_id, customer_id, settle_date, gross_amount,
                               fee_rate, fee_amount, net_amount)
                            VALUES
                              (:orderId, :customerId, :settleDate, :grossAmount,
                               :feeRate, :feeAmount, :netAmount)
                            """)
                    .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                            .addValue("orderId",     s.orderId())
                            .addValue("customerId",  s.customerId())
                            .addValue("settleDate",  s.settleDate())
                            .addValue("grossAmount", s.grossAmount())
                            .addValue("feeRate",     s.feeRate())
                            .addValue("feeAmount",   s.feeAmount())
                            .addValue("netAmount",   s.netAmount()))
                    .assertUpdates(true)
                    .build();
        }
    }

    // ======================================================================
    // 정답 3 — rewriteBatchedStatements 실측
    // ======================================================================
    /*
     *   ┌───────────────┬──────────────┬─────────────┬────────────┐
     *   │               │ Step 실행시간 │ Com_insert  │ writeCount │
     *   ├───────────────┼──────────────┼─────────────┼────────────┤
     *   │ (A) 옵션 없음  │  1m 1s 412ms │      70000  │      70000 │
     *   │ (B) 옵션 있음  │     7s 702ms │         70  │      70000 │
     *   └───────────────┴──────────────┴─────────────┴────────────┘
     *   61.4초 -> 7.7초. 약 8배.
     *
     * 왜 8배인가 — 분해해 봅니다.
     *   줄어든 왕복 = 70,000 - 70 = 69,930회
     *   줄어든 시간 = 61.4 - 7.7 = 53.7초
     *   왕복 1회당  = 53.7s / 69,930 ≈ 0.77ms
     *   로컬 도커 MySQL 에 대한 TCP 왕복 + 파싱 비용으로 타당한 값입니다.
     *
     *   즉 사라진 것은 DB 가 한 일이 아니라 "왕복" 입니다.
     *   InnoDB 가 실제로 삽입한 행 수는 양쪽 다 70,000 으로 같습니다.
     *   그래서 서버를 키워도, 인덱스를 지워도 8배가 안 나옵니다.
     *   병목이 DB 가 아니기 때문입니다.
     *
     * (a) writeCount, Job STATUS, 결과 행 수가 모두 같습니까?
     *     네, 완전히 같습니다. 70000 / COMPLETED / 70000.
     *
     * (b) 모니터링만으로 발견 가능한가?
     *     아니오. 어떤 에러도 경고도 남지 않습니다.
     *     그래서 "우리 배치는 원래 한 시간 걸려요" 가 굳어집니다.
     *
     *     대안 진단법: 서버 쪽 Com_insert 를 재세요.
     *       FLUSH STATUS;  -> Job 실행 -> SHOW GLOBAL STATUS LIKE 'Com_insert';
     *     이 값이 "처리 건수와 같으면" 배치 쓰기가 배치가 아닌 것입니다.
     *     정상이라면 처리건수 / 청크크기 정도의 값이 나와야 합니다(70,000/1,000 = 70).
     *
     *     참고: PostgreSQL·Oracle 드라이버에는 이 옵션이 없습니다.
     *           기본적으로 배치를 제대로 묶어 보냅니다. MySQL 만의 기본값 문제입니다.
     */

    // ======================================================================
    // 정답 4 — assertUpdates 의 두 구멍
    // ======================================================================
    /*
     * (a) WHERE 절 없는 UPDATE
     *   각 아이템마다 settlement 70,000행이 전부 갱신됩니다.
     *   청크의 1,000개 아이템이 순서대로 전체를 덮으므로,
     *   최종적으로 남는 값은 "마지막에 처리된 아이템의 fee_rate" 하나입니다.
     *   70,000행 전체가 같은 fee_rate 를 갖게 됩니다.
     *
     *   assertUpdates(true) 가 잡습니까? 아니오.
     *   검사는 `updateCounts[i] == 0` 뿐입니다. 여기서는 각 항목이 70000 입니다.
     *   0 이 아니므로 통과합니다. writeCount 도 70000 으로 정상입니다.
     *
     *   이것이 assertUpdates 의 본질적 한계입니다.
     *   "너무 적게 갱신됨"만 잡고 "너무 많이 갱신됨"은 개념적으로 잡을 수 없습니다.
     *
     * (b) SUCCESS_NO_INFO
     *   값 = java.sql.Statement.SUCCESS_NO_INFO, 숫자로는 -2.
     *
     *   rewriteBatchedStatements=true 일 때 UPDATE/DELETE 배치는 멀티 VALUES 로
     *   합칠 수 없어서 여러 문장을 이어 보내는 형태로 재작성됩니다.
     *   이때 드라이버는 개별 문장의 갱신 건수를 분리해 낼 수 없어 -2 를 채웁니다.
     *
     *   `updateCounts[i] == 0` 검사를 통과합니까? 네, 통과합니다. -2 != 0 이니까요.
     *
     *   의미: 성능 옵션을 켜는 순간 UPDATE writer 의 안전장치가 조용히 꺼집니다.
     *   8-3 에서 "가장 가성비 좋은 한 줄"이라고 한 옵션이,
     *   8-4 의 안전장치를 무력화합니다. 두 절을 따로 읽으면 이 상호작용을 놓칩니다.
     *
     * (c) 대안 방어책
     *   (a) 의 방어책 — 코드 리뷰 규칙으로 못 박습니다.
     *       "ItemWriter 의 UPDATE/DELETE 문에는 반드시 유니크 키가 WHERE 에 있어야 한다."
     *       settlement 라면 order_id (uk_settlement_order) 입니다.
     *       자동화하려면 SQL 문자열을 정적 검사하는 테스트를 붙이세요.
     *
     *   (b) 의 방어책 — 갱신 건수를 믿지 말고 Step 뒤에서 결과를 직접 검증합니다.
     *       아래 A4_VerifyTasklet 처럼 Tasklet Step 을 붙이고,
     *       기대 건수와 다르면 예외를 던져 Job 을 FAILED 로 만듭니다.
     *       Step 10 의 흐름 제어를 쓰면 검증 실패 시 보정 Step 으로 분기시킬 수도 있습니다.
     */
    public static class A4_VerifyTasklet implements Tasklet {

        private final JdbcTemplate jdbcTemplate;
        private final long expected;

        public A4_VerifyTasklet(JdbcTemplate jdbcTemplate, long expected) {
            this.jdbcTemplate = jdbcTemplate;
            this.expected = expected;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext ctx) {
            Long actual = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM settlement", Long.class);
            if (actual == null || actual != expected) {
                // 조용히 틀리느니 시끄럽게 실패하는 편이 낫습니다.
                throw new IllegalStateException(
                        "정산 건수 불일치: expected=" + expected + ", actual=" + actual);
            }
            return RepeatStatus.FINISHED;
        }
    }

    // ======================================================================
    // 정답 5 — FlatFileItemWriter
    // ======================================================================
    /*
     * wc -l 예측 = 70002 줄.
     *   데이터 70,000행 + headerCallback 1줄 + footerCallback 1줄.
     *
     * FieldExtractor 를 람다로 쓰는 이유는 문제 2 와 완전히 같습니다.
     * BeanWrapperFieldExtractor 는 이름 그대로 BeanWrapper 를 쓰므로
     * record 의 orderId() 를 읽지 못합니다.
     *
     * 옵션 중 실무에서 실제로 사고를 내는 것 둘:
     *  - encoding 을 지정하지 않으면 플랫폼 기본 인코딩이 쓰입니다.
     *    개발자 맥에서는 UTF-8, 운영 리눅스 컨테이너에서 POSIX 로케일이면 US-ASCII 가
     *    되어 한글이 '?' 로 깨집니다. 로컬에서는 절대 재현되지 않습니다.
     *  - lineSeparator 를 지정하지 않으면 System.lineSeparator() 입니다.
     *    윈도우에서 만든 파일에 \r\n 이 섞여 수신 시스템의 파서가 마지막 필드에
     *    \r 을 붙여 읽습니다. 이것도 조용한 실패입니다.
     * 둘 다 "명시하면 끝나는" 문제라 명시하는 것이 정답입니다.
     */
    public static class A5_FileWriter {

        public FlatFileItemWriter<Settlement> build() {
            DelimitedLineAggregator<Settlement> aggregator = new DelimitedLineAggregator<>();
            aggregator.setDelimiter(",");
            aggregator.setFieldExtractor(s -> new Object[]{
                    s.orderId(), s.customerId(), s.settleDate(),
                    s.grossAmount(), s.feeRate(), s.feeAmount(), s.netAmount()
            });

            return new FlatFileItemWriterBuilder<Settlement>()
                    .name("exerciseFileWriter")
                    .resource(new FileSystemResource("out/exercise-settlement.csv"))
                    .encoding("UTF-8")
                    .lineSeparator("\n")
                    .lineAggregator(aggregator)
                    .headerCallback(w -> w.write(
                            "order_id,customer_id,settle_date,gross_amount,"
                                    + "fee_rate,fee_amount,net_amount"))
                    .footerCallback(w -> w.write("# total lines above = 70000"))
                    .shouldDeleteIfExists(true)
                    .build();
        }
    }

    // ======================================================================
    // 정답 6 — .stream() 수동 등록
    // ======================================================================
    /*
     * 1단계 관찰 결과
     *   Job STATUS   = FAILED (WriterNotOpenException) 또는
     *                  COMPLETED (파일은 0줄) — vipFileWriter 가 @Bean 이라
     *                  afterPropertiesSet() 이 불려 빈 파일만 만들어진 경우
     *   DB 행 수     = 55000  (JdbcBatchItemWriter 는 ItemStream 이 아니라 멀쩡합니다)
     *   파일 줄 수   = 0
     *   예외 클래스  = org.springframework.batch.item.WriterNotOpenException
     *                  "Writer must be open before it can be written to"
     *   vipFileWriter.written 키 = 없음
     *
     * 2단계 — 왜 이렇게 되는가
     *   StepBuilder 는 .reader()/.processor()/.writer() 로 "직접 넘긴" 객체가
     *   ItemStream 이면 자동으로 stream() 에 등록합니다.
     *   여기서 직접 넘긴 것은 routingWriter 하나뿐이고,
     *   ClassifierCompositeItemWriter 는 ItemStream 을 구현하지 않습니다.
     *
     *     public class ClassifierCompositeItemWriter<T> implements ItemWriter<T>
     *                                                              ^^^^^^^^^^^^ 이것뿐
     *
     *   그래서 안에 숨은 vipFileWriter 의 콜백이 하나도 불리지 않습니다.
     *   빠진 것은 셋이고 각각 다른 피해를 냅니다.
     *
     *     open()   미호출 -> 파일이 열리지 않음 + headerCallback 미실행
     *     update() 미호출 -> ExecutionContext 에 written 위치가 안 남음
     *                        => 재시작하면 파일을 처음부터 다시 씀.
     *                           리더는 중단 지점부터 이어가는데 파일은 처음부터라
     *                           파일에는 뒤쪽 데이터만 남습니다. DB 는 맞고 파일은 틀립니다.
     *     close()  미호출 -> footerCallback 미실행 + 마지막 버퍼 유실
     *                        (transactional=true 라 출력이 버퍼에 있습니다)
     *
     *   진짜 원인은 "비대칭" 입니다.
     *     CompositeItemWriter           : ItemStreamWriter 구현 -> 델리게이트에 전파함
     *     ClassifierCompositeItemWriter : ItemWriter 만 구현    -> 전파 안 함
     *   이름이 한 단어 다를 뿐인데 정반대로 동작합니다.
     *   "CompositeItemWriter 로 잘 되던 코드"를 분기가 필요해져 바꾸는 순간
     *   파일이 조용히 비기 시작합니다. 코드 리뷰에서는 클래스 이름만 바뀐 것으로 보입니다.
     *
     * 3단계 — 고치기: .stream(vipFileWriter) 한 줄.
     *   JdbcBatchItemWriter 는 ItemStream 이 아니므로 등록할 필요가 없습니다
     *   (파일 핸들 같은 상태가 없습니다).
     *
     *   고친 뒤 검증:
     *     DB 55000 + 파일 15002줄 (= 15000 + 헤더 + 푸터) = 70000
     *     SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT
     *       ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
     *     -> {"vipFileWriter.written":15000, "allOrdersReader.read.count":100000}
     *
     *   습관으로 만들 점검 3줄:
     *     1. ItemStream 구현체가 .reader()/.writer() 에 직접 들어갔는가?
     *     2. 아니라면 .stream(...) 으로 손수 등록했는가?
     *     3. 한 번 돌리고 컨텍스트에 <name>.written 키가 있는가?
     *   3번은 10초면 됩니다. 안 하면 재시작이 필요해지는 그날까지 아무도 모릅니다.
     */
    public static class A6_RoutingStep {

        public Step build(JobRepository jobRepository, PlatformTransactionManager tx,
                          ItemReader<Order> reader,
                          ItemProcessor<Order, Settlement> processor,
                          ClassifierCompositeItemWriter<Settlement> routingWriter,
                          FlatFileItemWriter<Settlement> vipFileWriter) {
            return new StepBuilder("q6RoutingStep", jobRepository)
                    .<Order, Settlement>chunk(1000, tx)
                    .reader(reader)
                    .processor(processor)
                    .writer(routingWriter)
                    .stream(vipFileWriter)      // ← 숨어 있는 ItemStream 을 수동 등록
                    .build();
        }
    }

    // ======================================================================
    // 정답 7 — 커스텀 ItemStreamWriter 의 생명주기
    // ======================================================================
    /*
     * (a) open() 을 비워 두면
     *     재시작 시 이전에 전송한 건수를 복원하지 못해 sent 가 0 부터 시작합니다.
     *     이 예제처럼 sent 를 "몇 건 보냈나" 리포트에만 쓴다면 리포트만 틀립니다.
     *     그러나 sent 를 "여기부터 보내면 된다"는 위치로 쓴다면 중복 전송이 일어납니다.
     *     외부 리소스를 여는 writer(파일·소켓·커넥션)라면 아예 열리지 않아
     *     첫 write() 에서 즉시 터집니다.
     *
     * (b) update() 를 비워 두면
     *     ExecutionContext 에 위치가 저장되지 않습니다.
     *     open() 이 복원할 것이 애초에 없으므로, (a) 와 같은 결과가 되지만
     *     발현 시점이 다릅니다.
     *
     * (c) 어느 쪽이 더 발견하기 어려운가 — update() 쪽입니다.
     *
     *     ┌───────────┬────────────────────┬──────────────────────────┐
     *     │           │ 첫 실행            │ 재시작                   │
     *     ├───────────┼────────────────────┼──────────────────────────┤
     *     │ open 누락 │ 즉시 실패하기도 함 │ 위치 복원 안 됨          │
     *     │ update 누락│ 완벽하게 성공     │ 처음부터 다시 전송(중복) │
     *     └───────────┴────────────────────┴──────────────────────────┘
     *
     *     update() 누락은 첫 실행이 완벽하게 성공합니다.
     *     단위 테스트도 통과하고, 스테이징에서도 통과하고, 운영 첫날도 통과합니다.
     *     재시작이 필요해지는 날 — 즉 이미 장애 대응 중인 순간에 — 두 번째 장애로
     *     모습을 드러냅니다. 최악의 타이밍입니다.
     *
     *     이 코스가 반복해 말하는 종류의 버그입니다:
     *     문법 에러는 금방 고치지만, 에러 없이 조용히 틀리는 코드가 진짜 위험합니다.
     *
     * (d) 청크 롤백 시
     *     DB writer 는 함께 롤백됩니다. 외부 API 호출은 롤백되지 않습니다.
     *     청크의 900번째에서 예외가 나면 DB 는 깨끗이 되돌아가지만
     *     API 쪽에는 이미 1,000건이 들어가 있습니다. 재시도하면 2,000건이 됩니다.
     *
     *     방어책은 멱등 키입니다.
     *     order_id 처럼 아이템마다 고유한 값을 요청에 실어 보내고,
     *     수신 측이 같은 키를 두 번 받으면 무시하게 만듭니다.
     *     이 코스의 settlement.order_id 에 걸린 UNIQUE 제약이 바로 그 DB 버전입니다.
     *     "한 주문에 정산 한 번"을 스키마가 보장하므로, 배치를 두 번 돌려도
     *     정산이 두 배가 되는 대신 DuplicateKeyException 으로 시끄럽게 실패합니다.
     */
    public static class A7_CustomWriter implements ItemStreamWriter<Settlement> {

        private static final String CTX_KEY = "q7Writer.sent";
        private long sent = 0;

        @Override
        public void open(ExecutionContext ctx) {
            this.sent = ctx.getLong(CTX_KEY, 0L);   // 재시작 복원
        }

        @Override
        public void write(Chunk<? extends Settlement> chunk) {
            // 실제 구현이라면 여기서 chunk.getItems() 를 통째로 한 번에 보냅니다.
            // 아이템 단위 루프로 원격 호출하면 8-3 의 8배 문제가 그대로 재현됩니다.
            sent += chunk.size();
        }

        @Override
        public void update(ExecutionContext ctx) {
            ctx.putLong(CTX_KEY, sent);             // 청크 커밋마다 위치 저장
        }

        @Override
        public void close() {
        }
    }
}
