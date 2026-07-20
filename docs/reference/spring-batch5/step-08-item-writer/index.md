# Step 08 — ItemWriter

> **학습 목표**
> - Spring Batch 5.x 의 `void write(Chunk<? extends T> chunk)` 시그니처를 이해하고, 4.x 의 `List<T>` 에서 무엇이 왜 바뀌었는지 설명한다
> - `JdbcBatchItemWriter` 로 70,000건을 쓰고, **`record` 에 `beanMapped()` 가 통하지 않는 이유**를 확인한 뒤 `itemSqlParameterSourceProvider` 람다로 해결한다
> - **JDBC URL 의 `rewriteBatchedStatements=true` 유무로 70,000건 쓰기 시간을 실측해 약 8배 차이를 확인한다**
> - `assertUpdates` 옵션이 무엇을 잡고 **무엇을 못 잡는지** 구분한다
> - `FlatFileItemWriter` 로 `DelimitedLineAggregator` · `headerCallback` · `footerCallback` 를 갖춘 CSV 를 만든다
> - `CompositeItemWriter` / `ClassifierCompositeItemWriter` 로 출력을 복제하거나 분기한다
> - `ItemStream` 의 `open/update/close` 생명주기를 이해하고, **콜백이 호출되지 않아 파일이 0바이트가 되는 사고를 재현한 뒤 `.stream()` 수동 등록으로 고친다**
>
> **선행 스텝**: Step 07 — ItemProcessor
> **예상 소요**: 100분

---

## 8-0. 실습 준비

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;
TRUNCATE TABLE settlement;
SQL

mkdir -p ./out
```

이 스텝은 Step 07 의 파이프라인(리더 10만 건 → 프로세서가 3만 건 필터 → **70,000건 출력**)을 그대로 이어받습니다. 바뀌는 것은 writer 뿐입니다.

---

## 8-1. 5.x 의 `write(Chunk<? extends T>)` — 무엇이 바뀌었나

Spring Batch 4.x 의 `ItemWriter` 는 이랬습니다.

```java
// Spring Batch 4.x
public interface ItemWriter<T> {
    void write(List<? extends T> items) throws Exception;
}
```

Spring Batch 5.0 부터는 이렇습니다.

```java
// Spring Batch 5.x
@FunctionalInterface
public interface ItemWriter<T> {
    void write(Chunk<? extends T> chunk) throws Exception;
}
```

`java.util.List` 가 `org.springframework.batch.item.Chunk` 로 바뀌었습니다. **소스 호환성이 깨지는 변경**입니다. 4.x 예제를 그대로 붙여 넣으면 이렇게 됩니다.

```bash
./gradlew build
```

**결과**
```
> Task :compileJava FAILED
/src/main/java/com/example/batch/step08/LegacyWriter.java:14: error: LegacyWriter is not abstract and does not override abstract method write(Chunk<? extends Settlement>) in ItemWriter
public class LegacyWriter implements ItemWriter<Settlement> {
       ^
/src/main/java/com/example/batch/step08/LegacyWriter.java:17: error: method does not override or implement a method from a supertype
    @Override
    ^
2 errors

BUILD FAILED in 2s
```

이건 **좋은 실패**입니다. 컴파일러가 잡아 줬습니다. 고치는 법은 파라미터 타입만 바꾸는 것입니다.

```java
public class SettlementLogWriter implements ItemWriter<Settlement> {

    private static final Logger log = LoggerFactory.getLogger(SettlementLogWriter.class);

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        log.debug("chunk size={} first={} last={}",
                chunk.size(),
                chunk.getItems().get(0).orderId(),
                chunk.getItems().get(chunk.size() - 1).orderId());
    }
}
```

`Chunk` 가 `List` 보다 나은 이유는 **아이템 말고도 정보를 실을 수 있기 때문**입니다.

| 메서드 | 설명 |
|---|---|
| `getItems()` | `List<T>` 반환. 4.x 코드를 옮길 때 여기서 꺼내 쓰면 됩니다 |
| `size()` / `isEmpty()` | 아이템 개수 |
| `iterator()` | `Chunk` 는 `Iterable<T>` 이므로 `for (Settlement s : chunk)` 가 됩니다 |
| `getSkips()` | 이 청크에서 스킵된 아이템 목록 (fault tolerant 모드) |
| `getErrors()` | 이 청크에서 발생한 예외 목록 |
| `clear()` / `add(T)` | 커스텀 writer 에서 청크를 조작할 때 |

`@FunctionalInterface` 가 붙은 것도 실질적인 변화입니다. 람다로 writer 를 쓸 수 있습니다.

```java
ItemWriter<Settlement> writer = chunk -> chunk.forEach(System.out::println);
```

> 💡 **실무 팁 — 4.x 코드를 옮길 때는 `getItems()` 한 줄만**
> ```java
> // 4.x
> public void write(List<? extends Settlement> items) { doWrite(items); }
> // 5.x — 본문은 그대로 두고 어댑터만
> public void write(Chunk<? extends Settlement> chunk) { doWrite(chunk.getItems()); }
> ```
> 대부분의 마이그레이션은 이 한 줄로 끝납니다. `getSkips()` 같은 새 기능은 필요할 때 나중에 쓰면 됩니다.

---

## 8-2. JdbcBatchItemWriter — 그리고 record 가 조용히 부수는 것

가장 흔한 writer 입니다. 스프링 문서와 대부분의 예제는 이렇게 씁니다.

```java
// ❌ record 에는 통하지 않습니다
return new JdbcBatchItemWriterBuilder<Settlement>()
        .dataSource(dataSource)
        .sql("""
                INSERT INTO settlement
                  (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
                VALUES
                  (:orderId, :customerId, :settleDate, :grossAmount, :feeRate, :feeAmount, :netAmount)
                """)
        .beanMapped()          // ← 여기
        .build();
```

`beanMapped()` 는 내부적으로 `BeanPropertyItemSqlParameterSourceProvider` 를 씁니다. 이름 그대로 **자바빈 규약**(`getOrderId()`)을 기대합니다. 그런데 `record` 의 접근자는 `orderId()` 입니다. `get` 이 없습니다.

실행하면 이렇습니다.

**결과**
```
INFO 61022 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
ERROR 61022 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step settlementStep in job settlementJob

org.springframework.dao.InvalidDataAccessApiUsageException: No value supplied for the SQL parameter 'orderId': Invalid property 'orderId' of bean class [com.example.batch.domain.Settlement]: Bean property 'orderId' is not readable or has an invalid getter method: Does the return type of the getter match the parameter type of the setter?
	at org.springframework.jdbc.core.namedparam.NamedParameterUtils.substituteNamedParameters(NamedParameterUtils.java:392)
	at org.springframework.batch.item.database.JdbcBatchItemWriter.write(JdbcBatchItemWriter.java:189)
```

**이 경우는 운이 좋습니다.** 시끄럽게 실패했으니까요.

> ⚠️ **함정 — 컬럼이 전부 NULL 로 들어가는 조용한 실패**
> 위 예외는 `settlement` 컬럼들이 `NOT NULL` 이라서 명확하게 터진 것입니다. **`NULL` 을 허용하는 테이블이라면 예외 없이 전부 `NULL` 인 7만 행이 들어갑니다.**
> `SELECT COUNT(*)` 는 70000 이 나오고, 로그는 `COMPLETED` 이고, `writeCount=70000` 입니다. **모든 지표가 정상입니다.** 정산 금액만 전부 비어 있습니다.
> 이 사고를 만드는 조합은 세 가지가 맞물릴 때입니다: ① 도메인이 `record` ② `beanMapped()` ③ 대상 테이블이 nullable.
> 세 번째를 없애는 것이 가장 확실한 방어입니다. **결과 테이블의 컬럼은 되도록 `NOT NULL` 로 만드세요.** 제약은 성능을 위한 것이기 이전에 **조용한 실패를 시끄럽게 만드는 장치**입니다.

`record` 에 맞는 해법은 **람다로 파라미터 소스를 직접 만드는 것**입니다.

```java
@Bean
public JdbcBatchItemWriter<Settlement> settlementJdbcWriter(DataSource dataSource) {
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
```

장황해 보이지만 **컴파일 타임에 검증되는 코드**입니다. `record` 의 필드 이름을 바꾸면 여기서 컴파일 에러가 납니다. `beanMapped()` 는 리팩터링을 따라오지 못합니다.

| 방식 | record 지원 | 오타 검출 | 비고 |
|---|---|---|---|
| `.beanMapped()` | ❌ | 런타임 | 자바빈(POJO + getter)에만 |
| `.itemSqlParameterSourceProvider(람다)` | ✅ | **컴파일 타임** | 권장 |
| `.columnMapped()` | `Map<String,Object>` 아이템 전용 | 런타임 | 리더가 Map 을 낼 때 |

> 💡 읽기 쪽도 대칭입니다. Step 06 에서 본 것처럼 **읽기는 `DataClassRowMapper`, 쓰기는 람다**가 `record` 의 정석 조합입니다.
> `BeanPropertyRowMapper` 는 읽기 쪽에서 같은 이유로 `record` 를 못 채웁니다.

---

## 8-3. `rewriteBatchedStatements=true` — 70,000건 쓰기 실측

이제 이 코스에서 가장 가성비 좋은 한 줄입니다.

`JdbcBatchItemWriter` 는 청크의 1,000건을 `PreparedStatement.addBatch()` 로 쌓고 `executeBatch()` 를 한 번 부릅니다. 자바 코드상으로는 "한 번"입니다. 그런데 **MySQL JDBC 드라이버가 그것을 어떻게 보내느냐**는 별개의 문제입니다.

```
rewriteBatchedStatements=false (기본값)
  executeBatch()
    → INSERT INTO settlement VALUES (...)   ← 왕복 1
    → INSERT INTO settlement VALUES (...)   ← 왕복 2
    → ...                                    ← 왕복 1000
  (청크 하나에 1,000번 왕복. 70청크면 70,000번)

rewriteBatchedStatements=true
  executeBatch()
    → INSERT INTO settlement VALUES (...),(...),(...), ... ,(...)   ← 왕복 1
  (청크 하나에 1번 왕복. 70청크면 70번)
```

**측정합니다.** 먼저 옵션을 뺀 URL 로.

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3308/batchdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
```

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
```

**결과**
```
INFO 62110 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 62110 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
INFO 62110 --- [           main] c.e.batch.step08.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
INFO 62110 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementStep] executed in 1m1s412ms
INFO 62110 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 1m1s688ms
```

**61.4초.** 이제 URL 끝에 옵션 하나만 붙입니다.

```yaml
    url: jdbc:mysql://127.0.0.1:3308/batchdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
```

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=settlementJob'
```

**결과**
```
INFO 62344 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
INFO 62344 --- [           main] c.e.batch.step08.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
INFO 62344 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementStep] executed in 7s702ms
INFO 62344 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 7s934ms
```

**61.4초 → 7.7초. 약 8배 빨라졌습니다.**

| 항목 | `rewriteBatchedStatements` 없음 | 있음 |
|---|---|---|
| Step 실행 시간 | 1m 1s 412ms | **7s 702ms** |
| INSERT 왕복 횟수 | 70,000 | 70 |
| `writeCount` | 70000 | 70000 |
| 결과 행 수 | 70000 | 70000 |
| Job 상태 | COMPLETED | COMPLETED |

**아래 네 줄이 완전히 같다는 점을 보세요.** 결과도, 카운터도, 로그 상태도 동일합니다. 다른 것은 시간뿐입니다.

서버 쪽에서도 확인할 수 있습니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 -e "
SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status
WHERE VARIABLE_NAME IN ('Com_insert','Questions');"
```

**결과** (옵션 없이 한 번 돌린 뒤)
```
+---------------+----------------+
| VARIABLE_NAME | VARIABLE_VALUE |
+---------------+----------------+
| Com_insert    | 70000          |
| Questions     | 70412          |
+---------------+----------------+
```

**결과** (옵션을 켜고 한 번 돌린 뒤)
```
+---------------+----------------+
| VARIABLE_NAME | VARIABLE_VALUE |
+---------------+----------------+
| Com_insert    | 70             |
| Questions     | 482            |
+---------------+----------------+
```

`Com_insert` 가 70,000 에서 70 으로 줄었습니다. **DB 가 한 일의 양이 아니라, 왕복 횟수가 줄어든 것**입니다.

> ⚠️ **함정 — 이 옵션이 없으면 "느린 것"이 아니라 "느린 줄 모르는 것"입니다**
> 이 옵션의 부재는 어떤 에러도, 어떤 경고도 남기지 않습니다. `writeCount=70000` 이 정확히 찍히고 Job 은 `COMPLETED` 입니다.
> 그래서 "우리 배치는 원래 한 시간 걸려요"가 됩니다. 청크 크기를 늘려도, 인덱스를 지워도, 서버를 키워도 8배가 안 나옵니다. **병목이 DB 가 아니라 네트워크 왕복이기 때문입니다.**
> 진단법은 간단합니다. 위처럼 `Com_insert` 를 재 보세요. **처리 건수와 같은 숫자가 나오면 배치 쓰기가 배치가 아닌 것입니다.**

> 💡 **실무 팁 — `rewriteBatchedStatements` 는 MySQL 전용이며 주의점이 둘 있습니다**
> 1. **PostgreSQL·Oracle 에는 이 옵션이 없습니다.** 두 드라이버는 기본적으로 배치를 제대로 묶어 보냅니다. MySQL 만의 기본값 문제입니다.
> 2. 켜면 `max_allowed_packet` 에 걸릴 수 있습니다. 청크 크기 × 행 길이가 패킷 한도(기본 64MB)를 넘으면 드라이버가 알아서 쪼개지만, 청크를 10만 단위로 키운다면 `SHOW VARIABLES LIKE 'max_allowed_packet';` 를 확인하세요.
> 3. `INSERT ... ON DUPLICATE KEY UPDATE` 도 재작성 대상입니다. 다만 `UPDATE`/`DELETE` 배치는 멀티 VALUES 로 합칠 수 없어 **여러 문장을 세미콜론으로 이어 보내는** 방식이 되고, 이때 반환되는 갱신 건수가 달라집니다 — 다음 절의 주제입니다.

---

## 8-4. `assertUpdates` — 무엇을 잡고 무엇을 못 잡는가

`JdbcBatchItemWriter` 의 `assertUpdates` 는 기본값이 `true` 입니다. `executeBatch()` 가 돌려준 갱신 건수 배열을 훑어 **0 인 항목이 있으면 예외를 던집니다.**

```java
// JdbcBatchItemWriter 내부 (5.1.1)
if (assertUpdates) {
    for (int i = 0; i < updateCounts.length; i++) {
        if (updateCounts[i] == 0) {
            throw new EmptyResultDataAccessException(
                "Item " + i + " of " + updateCounts.length
                + " did not update any rows: [" + chunk.getItems().get(i) + "]", 1);
        }
    }
}
```

이게 필요한 상황은 **`UPDATE` writer** 입니다. `WHERE` 절에 걸리는 행이 없으면 갱신 건수가 0 이고, 그건 대개 버그입니다.

```java
.sql("UPDATE settlement SET net_amount = :netAmount WHERE order_id = :orderId")
.assertUpdates(true)
```

없는 `order_id` 를 하나 섞어 실행하면:

**결과**
```
ERROR 62901 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step updateStep in job settlementJob

org.springframework.dao.EmptyResultDataAccessException: Item 137 of 1000 did not update any rows: [Settlement[orderId=999999, customerId=4242, settleDate=2025-03-01, grossAmount=5000.00, feeRate=0.0250, feeAmount=125.00, netAmount=4875.00]]
	at org.springframework.batch.item.database.JdbcBatchItemWriter.write(JdbcBatchItemWriter.java:206)
```

`assertUpdates(false)` 로 두면 **이 상황이 아무 흔적 없이 지나갑니다.** `writeCount` 는 여전히 70000 입니다. 갱신되지 않은 행이 몇 개인지 아무도 모릅니다.

> ⚠️ **함정 — `assertUpdates` 는 "0건"만 잡습니다**
> 두 가지 구멍이 있습니다.
>
> **(1) 드라이버가 `SUCCESS_NO_INFO(-2)` 를 돌려주면 검사가 무력화됩니다.**
> `rewriteBatchedStatements=true` 로 `UPDATE`/`DELETE` 배치가 다중 문장으로 재작성되면, MySQL 드라이버는 개별 갱신 건수를 알 수 없어 `Statement.SUCCESS_NO_INFO`(값 `-2`)를 채워 돌려줍니다.
> 위 루프는 `== 0` 만 봅니다. **`-2` 는 통과합니다.** 즉 성능 옵션을 켜는 순간 `UPDATE` writer 의 안전장치가 조용히 꺼집니다.
> → `UPDATE` 계열 writer 를 쓴다면 **`writeCount` 를 믿지 말고, Step 뒤에 `SELECT COUNT(*)` 검증 Tasklet 을 붙이세요**(Step 10 의 흐름 제어로 조건 분기까지 만들 수 있습니다).
>
> **(2) "1건 기대했는데 500건 갱신됨"은 잡지 못합니다.**
> `WHERE` 절을 빠뜨린 `UPDATE settlement SET fee_rate = :feeRate` 같은 실수는 갱신 건수가 0 이 아니라 70,000 입니다. `assertUpdates` 는 통과시킵니다. 정산 테이블 전체가 마지막 아이템의 값으로 덮입니다.
> 이건 옵션으로 막을 수 없습니다. **`UPDATE` 문의 `WHERE` 절에 유니크 키가 들어 있는지 리뷰하는 것**이 유일한 방어입니다.

| 상황 | `assertUpdates(true)` 가 잡는가 |
|---|---|
| `UPDATE` 가 0건 갱신 (드라이버가 실제 건수 반환) | ✅ |
| `UPDATE` 가 0건 갱신 (드라이버가 `-2` 반환) | ❌ |
| `UPDATE` 가 의도보다 많이 갱신 | ❌ |
| `INSERT` 중복 키 | 해당 없음 — `DuplicateKeyException` 이 먼저 남 |

---

## 8-5. FlatFileItemWriter — CSV 만들기

정산 결과를 파일로도 내보냅니다. 회계팀에 넘길 CSV 입니다.

```java
@Bean
public FlatFileItemWriter<Settlement> settlementFileWriter() {
    DelimitedLineAggregator<Settlement> aggregator = new DelimitedLineAggregator<>();
    aggregator.setDelimiter(",");
    aggregator.setFieldExtractor(s -> new Object[]{
            s.orderId(), s.customerId(), s.settleDate(),
            s.grossAmount(), s.feeRate(), s.feeAmount(), s.netAmount()
    });

    return new FlatFileItemWriterBuilder<Settlement>()
            .name("settlementFileWriter")
            .resource(new FileSystemResource("out/settlement.csv"))
            .encoding("UTF-8")
            .lineSeparator("\n")
            .lineAggregator(aggregator)
            .headerCallback(w -> w.write(
                    "order_id,customer_id,settle_date,gross_amount,fee_rate,fee_amount,net_amount"))
            .footerCallback(w -> w.write("# generated by settlementJob"))
            .shouldDeleteIfExists(true)
            .build();
}
```

`FieldExtractor` 를 람다로 쓴 것에 주목하세요. `BeanWrapperFieldExtractor` 는 `beanMapped()` 와 같은 이유로 **`record` 에 통하지 않습니다.** 8-2 와 완전히 같은 함정입니다.

실행하고 결과를 봅니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=fileJob'
wc -l out/settlement.csv && ls -lh out/settlement.csv
```

**결과**
```
   70002 out/settlement.csv
-rw-r--r--  1 julong  staff   3.0M Jul 20 14:12 out/settlement.csv
```

70,000 데이터 행 + 헤더 1 + 푸터 1 = **70,002줄**입니다.

```bash
head -3 out/settlement.csv && echo '...' && tail -2 out/settlement.csv
```

**결과**
```
order_id,customer_id,settle_date,gross_amount,fee_rate,fee_amount,net_amount
1,1,2025-01-01,1100.00,0.0300,33.00,1067.00
2,2,2025-01-01,1200.00,0.0250,30.00,1170.00
...
99996,996,2025-06-29,1400.00,0.0350,49.00,1351.00
# generated by settlementJob
```

Step 07 에서 검증한 앞 5건과 같은 값입니다.

주요 옵션을 정리합니다.

| 옵션 | 의미 | 주의 |
|---|---|---|
| `.resource(...)` | 출력 경로 | 디렉터리가 없으면 예외. 미리 `mkdir` |
| `.encoding("UTF-8")` | 인코딩 | 기본은 플랫폼 기본값. **명시하세요** |
| `.lineSeparator("\n")` | 줄 구분자 | 기본은 `System.lineSeparator()`. 윈도우에서 만든 파일이 리눅스에서 `\r` 섞여 나오는 사고 방지 |
| `.append(true)` | 이어쓰기 | `shouldDeleteIfExists` 와 배타적 |
| `.shouldDeleteIfExists(true)` | 기존 파일 삭제 후 새로 씀 | 기본값 `true` |
| `.transactional(true)` | 청크 커밋 시점까지 버퍼링 | 기본값 `true`. 롤백되면 그 청크는 파일에도 안 씀 |
| `.headerCallback(...)` | 파일 열 때 1회 | **`open()` 안에서 호출됩니다** — 8-7 의 핵심 |
| `.footerCallback(...)` | 파일 닫을 때 1회 | **`close()` 안에서 호출됩니다** |

> 💡 **실무 팁 — `.transactional(true)` 의 의미**
> `FlatFileItemWriter` 는 기본적으로 청크 커밋 직전까지 출력을 메모리 버퍼에 담아 뒀다가, **트랜잭션이 커밋될 때 실제로 파일에 씁니다.**
> 덕분에 청크가 롤백되면 파일에도 그 청크가 남지 않습니다. DB 와 파일의 내용이 어긋나지 않게 하는 장치입니다.
> 반대로 말하면 **버퍼를 비우는 시점이 커밋 시점**이라는 뜻이고, 그래서 `close()` 가 호출되지 않으면 마지막 버퍼가 통째로 사라집니다.

---

## 8-6. CompositeItemWriter / ClassifierCompositeItemWriter

### CompositeItemWriter — 같은 청크를 여러 곳에

DB 에도 쓰고 파일에도 쓰고 싶을 때입니다. **모든 델리게이트가 같은 청크를 받습니다.**

```java
@Bean
public ItemWriter<Settlement> dualWriter(JdbcBatchItemWriter<Settlement> settlementJdbcWriter,
                                         FlatFileItemWriter<Settlement> settlementFileWriter) {
    CompositeItemWriter<Settlement> writer = new CompositeItemWriter<>();
    writer.setDelegates(List.of(settlementJdbcWriter, settlementFileWriter));
    return writer;
}
```

**결과**
```
INFO 63180 --- [           main] c.e.batch.step08.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -N -e "SELECT COUNT(*) FROM settlement;"
wc -l out/settlement.csv
```

**결과**
```
70000
   70002 out/settlement.csv
```

`writeCount` 는 **70000 한 번만** 올라갑니다. 두 곳에 썼다고 140000 이 되지 않습니다. `writeCount` 는 "writer 를 통과한 아이템 수"이지 "쓰기 연산 수"가 아닙니다.

### ClassifierCompositeItemWriter — 아이템마다 한 곳으로

VIP 정산(15,000건)만 별도 파일로 빼고, 나머지 55,000건은 DB 로 보냅니다.

```java
@Bean
public ItemWriter<Settlement> routingWriter(JdbcBatchItemWriter<Settlement> settlementJdbcWriter,
                                            FlatFileItemWriter<Settlement> vipFileWriter) {
    ClassifierCompositeItemWriter<Settlement> writer = new ClassifierCompositeItemWriter<>();
    BigDecimal vipRate = new BigDecimal("0.0150");
    writer.setClassifier((Classifier<Settlement, ItemWriter<? super Settlement>>)
            s -> vipRate.compareTo(s.feeRate()) == 0 ? vipFileWriter : settlementJdbcWriter);
    return writer;
}
```

내부적으로는 청크를 분류자로 **그룹핑한 뒤, 그룹별로 델리게이트의 `write()` 를 한 번씩** 부릅니다. 아이템마다 한 번씩 부르는 것이 아닙니다 — 배치 성능이 유지됩니다.

**결과**
```
INFO 63455 --- [           main] c.e.batch.step08.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -N -e "SELECT COUNT(*) FROM settlement;"
wc -l out/vip-settlement.csv
```

**결과**
```
55000
       0 out/vip-settlement.csv
```

**파일이 0줄입니다.** DB 는 55,000건으로 정확한데 파일만 비어 있습니다. Job 은 `COMPLETED` 이고 `writeCount=70000` 입니다.

이것이 이 스텝의 본론입니다.

---

## 8-7. ItemStream — open / update / close

파일에 쓰는 writer 는 **상태**를 가집니다. 열려 있는 파일 핸들, 지금까지 쓴 줄 수, 버퍼. 이 상태를 프레임워크가 관리하도록 하는 계약이 `ItemStream` 입니다.

```java
public interface ItemStream {
    void open(ExecutionContext executionContext) throws ItemStreamException;
    void update(ExecutionContext executionContext) throws ItemStreamException;
    void close() throws ItemStreamException;
}
```

`FlatFileItemWriter` 는 `ItemStreamWriter<T>`(= `ItemWriter<T>` + `ItemStream`)를 구현합니다. 각 콜백에서 무슨 일을 하는지가 핵심입니다.

| 콜백 | 호출 시점 | `FlatFileItemWriter` 가 하는 일 |
|---|---|---|
| `open(ctx)` | Step 시작 시 **1회** | 파일 열기, **`headerCallback` 실행**, 재시작이면 `ctx` 의 `written` 위치로 truncate |
| `update(ctx)` | **청크 커밋마다** | 버퍼 flush, `ctx` 에 `settlementFileWriter.written=NNNNN` 저장 |
| `close()` | Step 종료 시 **1회** | **`footerCallback` 실행**, flush, 파일 핸들 반납 |

그림으로 보면 이렇습니다.

```
Step 시작
  └── open(ctx)          ← 파일 열기 + 헤더 기록
        ├── [청크 1] write() ... update(ctx)   ← flush + "1000줄 썼음" 저장
        ├── [청크 2] write() ... update(ctx)   ← flush + "2000줄 썼음" 저장
        └── ...
  └── close()            ← 푸터 기록 + 마지막 flush + 파일 닫기
Step 종료
```

**세 콜백이 각각 다른 것을 책임집니다.**
- `open` 이 안 불리면 → 파일이 안 열리고 헤더가 없습니다.
- `update` 가 안 불리면 → 재시작 시 어디부터 이어 쓸지 모릅니다.
- `close` 가 안 불리면 → **푸터가 없고, 마지막 버퍼가 통째로 사라집니다.**

그러면 누가 이 콜백들을 불러 줄까요? **`StepBuilder` 입니다.**

```java
// SimpleStepBuilder.registerAsStreams() (5.1.1)
protected void registerStepListenerAsItemListener() { ... }

// build() 과정에서
if (reader instanceof ItemStream) stream((ItemStream) reader);
if (processor instanceof ItemStream) stream((ItemStream) processor);
if (writer instanceof ItemStream) stream((ItemStream) writer);
```

`.reader(...)` / `.processor(...)` / `.writer(...)` 로 **직접 넘긴 객체**가 `ItemStream` 이면 자동으로 등록합니다.

문제는 **직접 넘기지 않았을 때**입니다.

---

## 8-8. 함정 — 파일이 0바이트가 되는 이유

8-6 의 `ClassifierCompositeItemWriter` 를 다시 봅니다.

```java
.writer(routingWriter)     // ← StepBuilder 가 받은 것은 이것뿐
```

`StepBuilder` 는 `routingWriter` 만 봅니다. 그리고 **`ClassifierCompositeItemWriter` 는 `ItemStream` 을 구현하지 않습니다.**

```java
public class ClassifierCompositeItemWriter<T> implements ItemWriter<T> {   // ItemStream 없음
    private Classifier<T, ItemWriter<? super T>> classifier;
    ...
}
```

그래서 `stream()` 에 아무것도 등록되지 않고, 안에 숨어 있는 `vipFileWriter` 의 `open()` / `update()` / `close()` 는 **한 번도 호출되지 않습니다.**

`open()` 이 없는데 `write()` 가 왜 예외를 안 냈을까요? 냈어야 정상입니다. 확인해 봅니다.

```java
// FlatFileItemWriter.write() 내부
if (!getOutputState().isInitialized()) {
    throw new WriterNotOpenException("Writer must be open before it can be written to");
}
```

실제로 이렇게 나옵니다.

**결과**
```
ERROR 63455 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step routingStep in job fileJob

org.springframework.batch.item.WriterNotOpenException: Writer must be open before it can be written to
	at org.springframework.batch.item.support.AbstractFileItemWriter.write(AbstractFileItemWriter.java:249)
	at org.springframework.batch.item.support.ClassifierCompositeItemWriter.write(ClassifierCompositeItemWriter.java:66)
```

이 경우는 **시끄럽게 실패했으니 다행**입니다. 그런데 8-6 에서는 예외 없이 0줄짜리 파일이 나왔습니다. 차이가 뭘까요?

**`vipFileWriter` 를 `@Bean` 으로 등록했기 때문**입니다. `FlatFileItemWriter` 는 `InitializingBean` 이므로 스프링이 `afterPropertiesSet()` 을 불러 주고, 그 과정에서 `shouldDeleteIfExists(true)` 에 따라 **빈 파일이 만들어집니다.** 그 뒤 `open()` 이 안 불리면...

경우가 갈립니다. 그래서 표로 못 박습니다.

| 구성 | `open/update/close` | 증상 |
|---|---|---|
| `.writer(flatFileWriter)` 직접 | ✅ 자동 등록 | 정상 |
| `.writer(compositeItemWriter)` | ✅ `CompositeItemWriter` 가 `ItemStream` 이라 델리게이트에 전파 | 정상 |
| `.writer(classifierCompositeItemWriter)` | ❌ `ItemStream` 아님 | **파일 0바이트 / `WriterNotOpenException`** |
| 프로세서·리스너 안에 숨긴 writer | ❌ | **파일 0바이트** |
| `@StepScope` 프록시 뒤의 writer | ⚠️ 반환 타입이 `ItemWriter<T>` 면 `instanceof ItemStream` 이 실패 | **파일 0바이트** |

> ⚠️ **함정 — `CompositeItemWriter` 는 전파하고 `ClassifierCompositeItemWriter` 는 전파하지 않습니다**
> 이름이 비슷하고 역할도 비슷한 두 클래스가 **정반대로 동작합니다.** `CompositeItemWriter` 는 `ItemStreamWriter` 를 구현해 `open`/`update`/`close` 를 모든 델리게이트에 넘깁니다. `ClassifierCompositeItemWriter` 는 그냥 `ItemWriter` 입니다.
> 그래서 "`CompositeItemWriter` 로 잘 되던 코드"를 분기가 필요해져 `ClassifierCompositeItemWriter` 로 바꾸는 순간 **파일이 조용히 비기 시작합니다.** 코드 리뷰에서는 클래스 이름 한 단어만 바뀐 것으로 보입니다.
>
> 재시작 쪽 피해는 더 깊습니다. `update()` 가 안 불리면 `ExecutionContext` 에 `written` 위치가 남지 않습니다. 실패 후 재시작하면 writer 는 **파일 처음부터 다시 씁니다.** 리더는 중단 지점부터 이어가는데 파일은 처음부터라, **파일에는 뒤쪽 데이터만 남습니다.** DB 는 맞고 파일은 틀린 상태입니다.

**해법: `.stream()` 으로 수동 등록합니다.**

```java
@Bean
public Step routingStep(JobRepository jobRepository, PlatformTransactionManager tx,
                        ItemReader<Order> allOrdersReader,
                        ItemProcessor<Order, Settlement> settlementPipeline,
                        ItemWriter<Settlement> routingWriter,
                        FlatFileItemWriter<Settlement> vipFileWriter,
                        JdbcBatchItemWriter<Settlement> settlementJdbcWriter) {
    return new StepBuilder("routingStep", jobRepository)
            .<Order, Settlement>chunk(1000, tx)
            .reader(allOrdersReader)
            .processor(settlementPipeline)
            .writer(routingWriter)
            .stream(vipFileWriter)          // ← 숨어 있는 ItemStream 을 손으로 등록
            .build();
}
```

`JdbcBatchItemWriter` 는 `ItemStream` 이 아니므로 등록할 필요가 없습니다(파일 핸들 같은 상태가 없습니다).

다시 실행합니다.

**결과**
```
INFO 63980 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [routingStep]
INFO 63980 --- [           main] c.e.batch.step08.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
INFO 63980 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [routingStep] executed in 8s119ms
INFO 63980 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=fileJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 8s344ms
```

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -N -e "SELECT COUNT(*) FROM settlement;"
wc -l out/vip-settlement.csv
head -2 out/vip-settlement.csv
tail -1 out/vip-settlement.csv
```

**결과**
```
55000
   15002 out/vip-settlement.csv
order_id,customer_id,settle_date,gross_amount,fee_rate,fee_amount,net_amount
3,3,2025-01-01,1300.00,0.0150,19.50,1280.50
# vip settlements: generated by fileJob
```

**55,000 + 15,000 = 70,000.** 헤더와 푸터도 살아났습니다. `.stream()` 한 줄이 만든 차이입니다.

등록이 제대로 됐는지는 `ExecutionContext` 로 확인하는 것이 가장 확실합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT
ORDER BY STEP_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
SHORT_CONTEXT: {"@class":"java.util.HashMap","vipFileWriter.written":15000,"allOrdersReader.read.count":100000}
```

`vipFileWriter.written` 키가 보이면 **`update()` 가 불렸다는 증거**입니다. 이 키가 없으면 등록이 안 된 것이고, 재시작이 깨져 있는 상태입니다.

> 💡 **실무 팁 — "파일 writer 를 만들면 `.stream()` 을 의심한다"를 습관으로**
> 점검 절차 세 줄로 압축합니다.
> 1. Step 을 만들 때 `ItemStream` 구현체가 `.reader()`/`.writer()` 에 **직접** 들어갔는지 본다.
> 2. 아니라면(합성·분기·프록시 뒤에 있다면) `.stream(...)` 으로 손수 등록한다.
> 3. 한 번 돌리고 `BATCH_STEP_EXECUTION_CONTEXT` 에 그 writer 의 `.written` 키가 있는지 확인한다.
>
> 3번을 하는 데 10초 걸립니다. 안 하면 **장애가 나기 전까지, 즉 재시작이 필요해지는 그날까지** 아무도 모릅니다.

---

## 8-9. 커스텀 ItemWriter 를 직접 만들 때

프레임워크가 제공하지 않는 대상(외부 API, 큐 등)에 쓸 때는 직접 구현합니다. 지켜야 할 규칙이 셋 있습니다.

```java
public class SettlementApiWriter implements ItemStreamWriter<Settlement> {

    private final RestClient restClient;
    private long sent = 0;

    public SettlementApiWriter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void open(ExecutionContext ctx) {
        // (1) 재시작이면 이전 위치를 복원한다
        this.sent = ctx.getLong("settlementApiWriter.sent", 0L);
    }

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        // (2) 아이템 하나씩이 아니라 청크 통째로 한 번에 보낸다
        restClient.post().uri("/settlements/bulk")
                .body(chunk.getItems())
                .retrieve().toBodilessEntity();
        sent += chunk.size();
    }

    @Override
    public void update(ExecutionContext ctx) {
        // (3) 청크 커밋마다 위치를 남긴다
        ctx.putLong("settlementApiWriter.sent", sent);
    }

    @Override
    public void close() { }
}
```

| 규칙 | 이유 |
|---|---|
| `write()` 안에서 **아이템 단위 루프로 원격 호출 금지** | 청크 크기만큼 왕복이 늘어납니다. 8-3 의 8배 차이와 같은 문제입니다 |
| `open()` 에서 복원, `update()` 에서 저장 | 이걸 빼면 재시작 시 중복 전송됩니다 |
| writer 는 **멱등하게** | 청크가 롤백되면 같은 청크가 다시 옵니다. 외부 시스템에는 같은 데이터가 두 번 갑니다 |

> ⚠️ **함정 — 외부 시스템 쓰기는 트랜잭션에 참여하지 않습니다**
> DB writer 는 청크가 롤백되면 함께 롤백됩니다. **REST 호출은 롤백되지 않습니다.** 이미 보낸 것은 보낸 것입니다.
> 청크의 900번째 아이템에서 예외가 나면 DB 는 깨끗이 되돌아가지만 API 쪽에는 이미 1,000건이 들어가 있습니다. 재시도하면 2,000건이 됩니다.
> 방어책은 **멱등 키**입니다. `order_id` 처럼 아이템마다 고유한 값을 요청에 실어 보내고, 수신 측이 중복을 무시하게 만드세요. 이 코스의 `settlement.order_id` 에 걸린 `UNIQUE` 제약이 바로 그 역할을 하는 DB 버전입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 5.x 시그니처 | `void write(Chunk<? extends T> chunk)`. 4.x 의 `List<T>` 에서 **깨지는 변경** |
| `Chunk` | `Iterable`. `getItems()` / `size()` / `getSkips()` / `getErrors()` |
| `beanMapped()` | **`record` 에 통하지 않음.** nullable 테이블이면 전 컬럼 NULL 로 조용히 성공 |
| `itemSqlParameterSourceProvider` | 람다로 직접 작성. **컴파일 타임에 검증됨** — record 의 정답 |
| `rewriteBatchedStatements=true` | 70,000건 쓰기 **61.4초 → 7.7초, 약 8배**. `Com_insert` 70000 → 70 |
| 진단법 | `Com_insert` 가 처리 건수와 같으면 배치 쓰기가 배치가 아님 |
| `assertUpdates` | 갱신 건수 **0 만** 잡음. `-2`(SUCCESS_NO_INFO)와 "과다 갱신"은 못 잡음 |
| `FlatFileItemWriter` | `DelimitedLineAggregator` + **람다 `FieldExtractor`**(record 대응) |
| `headerCallback` | **`open()` 에서** 실행 |
| `footerCallback` | **`close()` 에서** 실행 → close 가 안 불리면 푸터도 버퍼도 사라짐 |
| `CompositeItemWriter` | 모든 델리게이트에 같은 청크. `ItemStream` **전파함** |
| `ClassifierCompositeItemWriter` | 아이템별 분기. `ItemStream` **전파 안 함** |
| `ItemStream` | `open`(1회) / `update`(청크마다) / `close`(1회) |
| 자동 등록 | `.reader()`/`.processor()`/`.writer()` 에 **직접** 넘긴 것만 |
| `.stream(writer)` | 합성·분기·프록시 뒤에 숨은 `ItemStream` 을 수동 등록 |
| 등록 검증 | `BATCH_STEP_EXECUTION_CONTEXT` 에 `<name>.written` 키가 있는가 |
| 외부 시스템 writer | 트랜잭션에 참여하지 않음 → **멱등 키 필수** |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. 4.x 스타일 `write(List<? extends T>)` writer 를 5.x 시그니처로 마이그레이션하기
2. `record` 도메인에 맞는 `JdbcBatchItemWriter` 를 `itemSqlParameterSourceProvider` 로 완성하고, `beanMapped()` 버전이 왜 실패하는지 예외 메시지를 예측하기
3. `rewriteBatchedStatements` 를 껐다 켜며 70,000건 쓰기를 실측하고, `Com_insert` 값으로 왕복 횟수를 증명하기
4. `assertUpdates(true)` 가 잡지 못하는 두 가지 상황을 코드로 만들고, 각각의 대안 방어책을 적기
5. `headerCallback` / `footerCallback` 를 갖춘 `FlatFileItemWriter` 를 만들고, 출력이 70,002줄인 이유를 설명하기
6. `ClassifierCompositeItemWriter` 로 VIP 15,000건만 파일로 분기하되, **`.stream()` 없이 먼저 실행해 실패를 관찰**한 뒤 고치기
7. 커스텀 `ItemStreamWriter` 를 만들고, `open`/`update` 를 뺐을 때 재시작에서 무엇이 깨지는지 답하기

---

## 다음 단계

8-7 에서 `update(ExecutionContext)` 가 청크 커밋마다 위치를 저장한다는 것을 봤습니다.
그 `ExecutionContext` 가 정확히 무엇이고, 어디에 어떤 형태로 저장되며, 재시작 시 어떻게 복원되는지 — 그리고 Job 레벨 컨텍스트와 Step 레벨 컨텍스트의 차이는 무엇인지를 다음 스텝에서 파고듭니다.
Step 07 에서 미뤄 둔 "누적 합계를 재시작에도 이어가는 법"도 여기서 답이 나옵니다.

→ [Step 09 — ExecutionContext](../step-09-execution-context/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 로 8-1 ~ 8-9 의 writer 구현을 모두 훑고, `Exercise.java` 의 7문제를 채운 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step08` 패키지이고, 예제는 `static class` 중첩으로 담았습니다. **실행 전에 반드시 `mkdir -p ./out` 을 해 두세요** — `FlatFileItemWriter` 는 상위 디렉터리를 만들어 주지 않습니다.

### Practice.java

본문의 모든 writer 와 Step 설정을 절 번호 주석(`// [8-3]`)과 함께 모아 둔 참조 코드입니다.

- `Practice` 는 `@Configuration` 이며 `settlementJob`(DB 만) / `fileJob`(파일 포함) / `routingJob`(분기) 세 Job 을 정의합니다. `--spring.batch.job.name=` 으로 고릅니다.
- `[8-2]` 의 `brokenBeanMappedWriter()` 는 **`record` 에 `beanMapped()` 를 쓴 실패 예시**입니다. `@Bean` 이 아니므로 그냥 빌드해도 안전하고, 재현하려면 `settlementJdbcWriter` 자리에 손으로 바꿔 끼워야 합니다. 이때 나오는 `InvalidDataAccessApiUsageException` 이 **`settlement` 테이블이 `NOT NULL` 이라서 나는 것**이라는 점을 주석이 짚어 둡니다. nullable 테이블이었다면 조용히 성공했을 것입니다.
- `[8-3]` 의 8배 실측은 코드가 아니라 **`application.yml` 의 URL 을 고쳐야** 재현됩니다. 파일 상단 주석에 두 URL 이 그대로 적혀 있으니 복사해 쓰세요. 측정 전마다 `TRUNCATE TABLE settlement;` 를 잊지 마세요 — 안 그러면 `DuplicateKeyException` 이 먼저 납니다(`uk_settlement_order` 제약).
- `[8-6]`/`[8-8]` 의 `routingStep` 에는 `.stream(vipFileWriter)` 줄이 **주석 처리된 채로** 들어 있습니다. 먼저 주석인 상태로 돌려 0줄 파일(또는 `WriterNotOpenException`)을 관찰하고, 그다음 주석을 풀어 15,002줄이 되는 것을 확인하는 순서로 만들어 두었습니다. **순서를 지키세요. 고쳐진 코드만 보면 이 함정은 배워지지 않습니다.**
- `[8-9]` 의 `SettlementApiWriter` 는 실제 엔드포인트가 없으므로 그대로 실행하면 커넥션 예외가 납니다. 구조를 읽는 용도이며, `open`/`update` 에서 `ExecutionContext` 를 다루는 부분이 Step 09 의 예고편입니다.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. `// 여기에 작성:` 자리를 채우세요.

- **문제 2·3·4·6** 은 "먼저 예측을 주석으로 적고 → 실행해서 대조"하는 형식입니다. 예측을 건너뛰면 문제의 절반이 사라집니다.
- 문제 3 은 코드가 아니라 **측정 절차**를 수행하는 문제입니다. `FLUSH STATUS;` → Job 실행 → `SHOW GLOBAL STATUS LIKE 'Com_insert';` 순서로 두 번(옵션 off/on) 재고, 결과를 표에 적습니다. `Com_insert` 가 70000 과 70 으로 갈리는 것을 직접 보는 것이 목적입니다.
- 문제 4 의 `Q4_OverUpdate` 는 **`WHERE` 절이 빠진 `UPDATE`** 를 일부러 담고 있습니다. 실행하면 `settlement` 70,000행이 전부 덮이므로, **반드시 `TRUNCATE` 후 재생성할 각오로** 돌리세요. 이것이 `assertUpdates` 가 못 잡는 사고의 실체입니다.
- 문제 6 은 `.stream()` 이 빠진 채 주어집니다. **이 파일을 그대로 실행하면 파일이 비거나 `WriterNotOpenException` 이 나는 것이 정상입니다.** 그 상태를 확인한 뒤 고치세요.
- 문제 7 은 실행 없이 답만 적는 서술형입니다. `open` 을 뺐을 때와 `update` 를 뺐을 때 깨지는 것이 서로 다르다는 점이 핵심입니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과 "왜 그 답인가"를 설명하는 긴 주석입니다. 풀어 본 **뒤에** 여세요.

- **정답 2** 는 `beanMapped()` 가 실패하는 이유를 `record` 접근자 규약(`orderId()` vs `getOrderId()`)까지 내려가 설명하고, **가장 위험한 시나리오가 "예외가 나는 경우"가 아니라 "nullable 테이블에서 조용히 성공하는 경우"**라는 점을 강조합니다. `settlement` 를 nullable 로 바꿔 재현해 보는 방법도 주석에 적어 두었습니다.
- **정답 3** 은 61.4초 / 7.7초와 `Com_insert` 70000 / 70 을 표로 제시하고, **"왜 8배인가"** 를 분해합니다. 네트워크 왕복 1회당 약 0.77ms 라고 보면 69,930회 절약 × 0.77ms ≈ 54초 — 실측 차이 53.7초와 맞아떨어집니다. 병목이 DB 연산이 아니라 왕복이라는 결론이 여기서 나옵니다.
- **정답 4** 는 두 구멍을 각각 다룹니다. (a) `SUCCESS_NO_INFO(-2)` 는 `== 0` 검사를 통과한다는 것 — 즉 성능 옵션을 켜면 안전장치가 조용히 꺼진다는 것, (b) 과다 갱신은 애초에 갱신 건수로 판별할 수 없다는 것. 대안으로 Step 뒤에 검증 Tasklet 을 붙이는 코드가 함께 들어 있습니다.
- **정답 6** 은 `.stream()` 이 없을 때 벌어지는 일을 세 단계로 나눕니다: `open()` 미호출 → 헤더 없음 + 파일 미개방, `update()` 미호출 → `ExecutionContext` 에 `written` 키 없음(재시작 파괴), `close()` 미호출 → 푸터 없음 + 마지막 버퍼 유실. 그리고 `CompositeItemWriter` 는 전파하고 `ClassifierCompositeItemWriter` 는 안 한다는 **비대칭**이 이 함정의 진짜 원인이라고 결론짓습니다.
- **정답 7** 은 `open` 과 `update` 를 뺐을 때의 증상이 다르다는 점을 표로 정리합니다. `update` 만 빠지면 **처음 실행은 완벽하게 성공**하고 재시작할 때만 중복 전송이 일어납니다 — 즉 테스트 환경에서는 절대 발견되지 않는 종류의 버그입니다.

```java file="./Solution.java"
```
