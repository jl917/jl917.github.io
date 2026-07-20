# Step 13 — 병렬 처리와 확장

> **학습 목표**
> - 확장 4가지 방식(멀티스레드 Step · 병렬 Step · 파티셔닝 · 원격 청킹)의 적용 조건을 구분한다
> - `.taskExecutor(...)` 로 멀티스레드 Step 을 만들고, **상태 있는 Reader 가 조용히 데이터를 잃는 것을 70,000건 기준으로 재현한다**
> - `SynchronizedItemStreamReader` 로 고치고, 그 해법이 왜 천장을 갖는지 측정한다
> - `saveState(false)` 가 강제하는 **재시작 포기**의 대가를 이해한다
> - `Partitioner` 로 `order_id` 범위를 쪼개고 `#{stepExecutionContext['minId']}` 늦은 바인딩으로 워커 Step 을 파라미터화한다
> - **단일 스레드 62.4초 → 8파티션 11.2초(약 5.6배)** 를 실측하고, 커넥션 풀 20 이 어디서 병목이 되는지 확인한다
>
> **선행 스텝**: Step 12 — 리스너
> **예상 소요**: 120분

---

## 13-0. 실습 준비 — 단일 스레드 기준선

이 스텝의 모든 측정은 **하나의 기준선**과 비교합니다. 먼저 그 기준선을 만듭니다.

지금까지 만든 정산 Job 을 파라미터 없이 **COMPLETED 70,000건 전체**를 도는 형태로 정리합니다.

```java
@Bean
public Step settlementSingleStep(JobRepository jobRepository,
                                 PlatformTransactionManager txManager,
                                 DataSource dataSource) {
    return new StepBuilder("settlementSingleStep", jobRepository)
            .<Order, Settlement>chunk(1000, txManager)
            .reader(orderCursorReader(dataSource))
            .processor(new SettlementProcessor())
            .writer(settlementWriter(dataSource))
            .build();
}

@Bean
public JdbcCursorItemReader<Order> orderCursorReader(DataSource dataSource) {
    return new JdbcCursorItemReaderBuilder<Order>()
            .name("orderCursorReader")
            .dataSource(dataSource)
            .fetchSize(1000)
            .sql("""
                 SELECT order_id, customer_id, amount, status, ordered_at
                 FROM orders
                 WHERE status = 'COMPLETED'
                 ORDER BY order_id
                 """)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

`Settlement` 를 쓰는 Writer 는 [Step 08](../step-08-item-writer/) 에서 만든 **멱등 Writer** 를 그대로 씁니다. 이게 뒤에서 아주 중요해집니다.

```java
@Bean
public JdbcBatchItemWriter<Settlement> settlementWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<Settlement>()
            .dataSource(dataSource)
            .sql("""
                 INSERT INTO settlement
                   (order_id, customer_id, settle_date, gross_amount, fee_rate, fee_amount, net_amount)
                 VALUES (:orderId, :customerId, :settleDate, :grossAmount, :feeRate, :feeAmount, :netAmount)
                 ON DUPLICATE KEY UPDATE
                   fee_amount = VALUES(fee_amount),
                   net_amount = VALUES(net_amount)
                 """)
            .itemSqlParameterSourceProvider(s -> new MapSqlParameterSource()
                    .addValue("orderId",     s.orderId())
                    .addValue("customerId",  s.customerId())
                    .addValue("settleDate",  s.settleDate())
                    .addValue("grossAmount", s.grossAmount())
                    .addValue("feeRate",     s.feeRate())
                    .addValue("feeAmount",   s.feeAmount())
                    .addValue("netAmount",   s.netAmount()))
            .build();
}
```

실행합니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun -Pargs=--spring.batch.job.name=settlementSingleJob
```

**결과**
```
INFO 51204 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementSingleJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 51204 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementSingleStep]
INFO 51204 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementSingleStep] executed in 1m2s400ms
INFO 51204 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementSingleJob]] completed with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 1m2s512ms
```

**62.4초.** 이 숫자가 이 스텝 전체의 기준선입니다.

결과 검증도 같이 해 둡니다. 앞으로 매 실험 뒤에 **이 쿼리 하나**를 반드시 돌립니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) rows_written, COUNT(DISTINCT order_id) distinct_orders,
       SUM(net_amount) total_net FROM settlement;"
```

**결과**
```
+--------------+-----------------+----------------+
| rows_written | distinct_orders | total_net      |
+--------------+-----------------+----------------+
|        70000 |           70000 | 3389358750.00  |
+--------------+-----------------+----------------+
```

**70,000행, `total_net` 3,389,358,750.00.** 정답지입니다. 어떤 확장 방식을 쓰든 이 세 숫자가 나와야 합니다.

> 💡 **실무 팁 — 성능 실험에는 반드시 "정답지"를 먼저 만드세요**
> 빠르게 만드는 실험은 **틀리기 쉬운 실험**입니다. 빨라진 것만 보고 결과 검증을 빼먹으면, 여러분은 "3배 빨라진 틀린 배치"를 운영에 올리게 됩니다.
> 이 스텝의 모든 절은 **소요 시간과 `distinct_orders` 를 한 쌍으로** 봅니다.

---

## 13-1. 확장 4가지 방식 — 무엇을 언제 쓰는가

Spring Batch 의 확장 전략은 크게 넷입니다. **"무엇을 쪼개느냐"** 로 구분하면 명확합니다.

| 방식 | 무엇을 쪼개나 | JVM | Reader 인스턴스 | 재시작 | 적용 조건 |
|---|---|---|---|---|---|
| **멀티스레드 Step** | 한 Step 안의 **청크** | 1개 | **1개 공유** ⚠️ | 사실상 불가 | Reader 가 스레드 안전할 때만 |
| **병렬 Step (split)** | 서로 **다른 Step** | 1개 | 각자 | 가능 | 독립적인 Step 이 2개 이상 있을 때 |
| **파티셔닝** | **데이터 범위** | 1개 (로컬) | 파티션마다 **별도** | 가능 | 데이터를 균등한 키 범위로 나눌 수 있을 때 |
| **원격 청킹** | **처리(processor)** | N개 | 1개(마스터) | 제한적 | 처리가 무겁고 I/O 는 가벼울 때 |

여기서 가장 중요한 칸은 **"Reader 인스턴스"** 열입니다.

- 멀티스레드 Step 은 **하나의 Reader 를 여러 스레드가 동시에 호출**합니다. Reader 가 내부 상태(커서 위치, 페이지 번호, 읽은 건수)를 들고 있으면 그 상태가 곧바로 경쟁 조건이 됩니다.
- 파티셔닝은 파티션마다 **Step 실행 자체가 별개**라서 Reader 도 별개입니다. 공유 상태가 아예 없습니다.

이 한 줄 차이가 13-3 의 사고와 13-7 의 해결을 가릅니다.

```
[ 멀티스레드 Step ]                      [ 파티셔닝 ]

        Reader (1개)                     Partitioner → {p0: 1~12500, p1: 12501~25000, ...}
       ↙   ↓   ↘                                 │
   T1    T2    T3   ← 동시에 read()              ├─ worker0: Reader(1~12500)     ← 독립
   각자 process/write                            ├─ worker1: Reader(12501~25000) ← 독립
                                                 └─ worker7: Reader(87501~100000)
   ⚠️ 상태 공유                                    ✅ 상태 공유 없음
```

---

## 13-2. 멀티스레드 Step — `.taskExecutor(...)` 한 줄

Step 빌더에 `TaskExecutor` 를 하나 붙이면 끝입니다. 정말 한 줄입니다.

```java
@Bean
public TaskExecutor batchTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(0);                 // 큐에 쌓지 않고 바로 스레드로
    executor.setThreadNamePrefix("batch-mt-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.initialize();
    return executor;
}

@Bean
public Step settlementMultiThreadStep(JobRepository jobRepository,
                                      PlatformTransactionManager txManager,
                                      DataSource dataSource,
                                      TaskExecutor batchTaskExecutor) {
    return new StepBuilder("settlementMultiThreadStep", jobRepository)
            .<Order, Settlement>chunk(1000, txManager)
            .reader(orderCursorReader(dataSource))      // 13-0 의 그 Reader 를 그대로
            .processor(new SettlementProcessor())
            .writer(settlementWriter(dataSource))
            .taskExecutor(batchTaskExecutor)            // ← 이 한 줄
            .build();
}
```

`.taskExecutor(...)` 를 붙이면 `ChunkOrientedTasklet` 의 각 반복이 워커 스레드에 던져집니다. 즉 **청크 1은 T1, 청크 2는 T2, 청크 3은 T3 …** 가 동시에 처리합니다.

돌려 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun -Pargs=--spring.batch.job.name=settlementMultiThreadJob
```

**결과**
```
INFO 51988 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementMultiThreadStep]
DEBUG 51988 --- [    batch-mt-2] o.s.b.c.s.item.ChunkOrientedTasklet      : Inputs not busy, ended: false
DEBUG 51988 --- [    batch-mt-1] o.s.b.c.s.item.ChunkOrientedTasklet      : Inputs not busy, ended: false
DEBUG 51988 --- [    batch-mt-3] o.s.b.c.s.item.ChunkOrientedTasklet      : Inputs not busy, ended: false
DEBUG 51988 --- [    batch-mt-4] o.s.b.c.s.item.ChunkOrientedTasklet      : Inputs not busy, ended: false
INFO 51988 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementMultiThreadStep] executed in 18s912ms
INFO 51988 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementMultiThreadJob]] completed with the following parameters: [{'run.id':'{value=2, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 19s031ms
```

**62.4초 → 18.9초. 약 3.3배 빨라졌습니다.** `COMPLETED` 로 끝났고, 에러도 없고, 예외 스택도 없습니다.

여기서 멈추면 안 됩니다. 13-0 에서 약속한 검증 쿼리를 돌립니다.

---

## 13-3. 함정 재현 — 3.3배 빨라진 배치가 787건을 잃었다

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) rows_written, COUNT(DISTINCT order_id) distinct_orders,
       SUM(net_amount) total_net FROM settlement;"
```

**결과**
```
+--------------+-----------------+----------------+
| rows_written | distinct_orders | total_net      |
+--------------+-----------------+----------------+
|        69213 |           69213 | 3351251400.00  |
+--------------+-----------------+----------------+
```

**70,000이 아니라 69,213입니다.** 787건의 주문이 정산되지 않았습니다. 그런데 메타데이터를 보면 이렇습니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+---------------------------+-----------+------------+-------------+--------------+----------------+
| STEP_NAME                 | STATUS    | READ_COUNT | WRITE_COUNT | COMMIT_COUNT | ROLLBACK_COUNT |
+---------------------------+-----------+------------+-------------+--------------+----------------+
| settlementMultiThreadStep | COMPLETED | 70000      | 70000       | 70           | 0              |
+---------------------------+-----------+------------+-------------+--------------+----------------+
```

**Spring Batch 는 70,000건을 읽고 70,000건을 썼다고 말합니다.** `STATUS` 는 `COMPLETED`, 롤백 0. 프레임워크 입장에서는 완벽한 성공입니다. 그런데 테이블에는 69,213행뿐입니다.

한 번 더 돌려 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun -Pargs=--spring.batch.job.name=settlementMultiThreadJob
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(DISTINCT order_id) distinct_orders FROM settlement;"
```

**결과** (3회 반복)
```
1회차: 69213
2회차: 68940
3회차: 69551
```

**매번 다릅니다.** [프로젝트 셋업](../project/) 에서 `RAND()` 를 한 번도 쓰지 않고 결정론적 데이터를 만든 이유가 여기서 빛을 발합니다. 입력이 완전히 동일한데 출력이 매번 다르다면, **원인은 데이터가 아니라 코드**입니다.

### 왜 사라졌나

`JdbcCursorItemReader.read()` 의 알맹이는 이렇습니다.

```java
// JdbcCursorItemReader 내부 (개념 코드)
protected Order doRead() throws Exception {
    if (rs.next()) {                   // ① 커서를 한 칸 전진
        return rowMapper.mapRow(rs, currentRow++);   // ② 지금 위치의 행을 매핑
    }
    return null;
}
```

`rs` 는 **하나의 `ResultSet` 인스턴스**이고, 네 스레드가 이 메서드를 동시에 호출합니다. ①과 ② 사이에 다른 스레드가 끼어들면 이렇게 됩니다.

```
시각   T1                          T2
 t1   rs.next()  → row 501
 t2                               rs.next()  → row 502
 t3   mapRow(rs) → row 502 읽음!  
 t4                               mapRow(rs) → row 502 읽음!
      ↑ row 501 은 아무도 안 읽었다 (누락)
      ↑ row 502 는 두 번 읽혔다 (중복)
```

**누락 1건 + 중복 1건**이 짝으로 발생합니다. 그래서 `READ_COUNT` 는 정확히 70,000 입니다 — `rs.next()` 는 정확히 70,000번 성공했으니까요. 다만 그중 787번이 **엉뚱한 행을 두 번 봤을 뿐**입니다.

그럼 중복은 어디로 갔을까요? Writer 를 다시 보세요.

```sql
INSERT INTO settlement (...) VALUES (...)
ON DUPLICATE KEY UPDATE fee_amount = VALUES(fee_amount), net_amount = VALUES(net_amount)
```

`settlement.uk_settlement_order` UNIQUE 제약에 걸린 중복 787건이 **`UPDATE` 로 조용히 흡수**됐습니다. 예외도, 경고 로그도 없습니다.

> ⚠️ **함정 — 멱등 Writer 는 중복은 막아 주지만 누락은 감춰 줍니다**
> `ON DUPLICATE KEY UPDATE` 는 좋은 습관입니다. 재시작 안전성을 위해 [Step 11](../step-11-fault-tolerance/) 에서 권장했습니다.
> 그런데 멀티스레드 Reader 의 버그와 만나면 **최악의 조합**이 됩니다.
> - 만약 Writer 가 순수 `INSERT` 였다면? 787건의 중복이 `DuplicateKeyException` 을 던져 Job 이 **시끄럽게 실패**했을 것입니다. 여러분은 즉시 알아챘을 것입니다.
> - 멱등 Writer 는 그 787건을 삼켜 버립니다. 남는 것은 **`COMPLETED` 로 끝난, 787건이 비는 정산서**뿐입니다.
>
> **중복은 제약 조건이 시끄럽게 알려 주지만, 누락은 아무도 알려 주지 않습니다.** 없는 행에 대해 알람을 걸어 줄 컴포넌트는 세상에 없습니다.
> 이 스텝의 모든 실험에서 소요 시간과 `COUNT(DISTINCT order_id)` 를 항상 함께 보라고 한 이유입니다.

> ⚠️ **함정 — 스레드 안전하지 않은 Reader 목록**
> 다음은 **`.taskExecutor(...)` 와 함께 쓰면 안 됩니다.**
>
> | Reader | 위험 상태 | 증상 |
> |---|---|---|
> | `JdbcCursorItemReader` | 공유 `ResultSet` 커서 | 누락/중복, 매 실행 결과 다름 |
> | `HibernateCursorItemReader` | 공유 `ScrollableResults` | 위와 동일 + `Session` 비안전 |
> | `FlatFileItemReader` | `BufferedReader` 위치, `lineCount` | 라인 섞임, 파싱 예외 |
> | `StaxEventItemReader` | XML 이벤트 커서 | 문서 구조 깨짐 |
> | 직접 만든 Reader (`List` + `index++`) | `index` 필드 | 누락/중복 |
>
> 반면 `JdbcPagingItemReader`·`JpaPagingItemReader` 는 **페이지 단위로 새 쿼리를 던지므로** 커서를 공유하지 않습니다.
> 다만 이들도 "다음에 읽을 페이지 번호"라는 상태는 공유합니다. 5.x 의 `AbstractPagingItemReader.doRead()` 는 이 부분이 `synchronized` 라 **읽기 자체는 안전**하지만,
> **`ORDER BY` 가 유니크하지 않으면** 페이지 경계에서 행이 흔들려 여전히 누락/중복이 생깁니다. 정렬 키에 반드시 PK 를 포함시키세요.

직접 만든 Reader 가 가장 흔한 사고 원인입니다. 이런 코드입니다.

```java
// ⚠️ 절대 멀티스레드 Step 에 쓰지 마세요
public class NaiveOrderReader implements ItemReader<Order> {
    private final List<Order> orders;
    private int index = 0;                       // ← 이 필드 하나가 전부입니다

    @Override
    public Order read() {
        if (index < orders.size()) {
            return orders.get(index++);          // index++ 는 원자적이지 않습니다
        }
        return null;
    }
}
```

`index++` 는 "읽고 → 더하고 → 쓰고" 세 연산입니다. 네 스레드가 동시에 하면 같은 값을 여러 번 읽습니다.

---

## 13-4. `SynchronizedItemStreamReader` — 해법과 그 천장

Spring Batch 5 는 **아무 Reader 나 감싸서 스레드 안전하게 만들어 주는** 데코레이터를 제공합니다.

```java
@Bean
public SynchronizedItemStreamReader<Order> syncOrderReader(DataSource dataSource) {
    SynchronizedItemStreamReader<Order> reader = new SynchronizedItemStreamReader<>();
    reader.setDelegate(orderCursorReader(dataSource));
    return reader;
}
```

빌더도 있습니다(5.0+).

```java
return new SynchronizedItemStreamReaderBuilder<Order>()
        .delegate(orderCursorReader(dataSource))
        .build();
```

하는 일은 정확히 한 가지입니다.

```java
// SynchronizedItemStreamReader 내부
private final Lock lock = new ReentrantLock();

@Override
public T read() throws Exception {
    this.lock.lock();
    try {
        return this.delegate.read();     // ①과 ②를 하나의 임계 구역으로 묶는다
    } finally {
        this.lock.unlock();
    }
}
```

13-3 의 `rs.next()` 와 `mapRow(rs)` 사이에 다른 스레드가 못 끼어들게 하는 것입니다. 붙이고 다시 측정합니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun -Pargs=--spring.batch.job.name=settlementSyncMtJob
```

**결과**
```
INFO 52440 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementSyncMtStep] executed in 21s607ms
INFO 52440 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementSyncMtJob]] completed with the following parameters: [{'run.id':'{value=5, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 21s718ms
```

```
+--------------+-----------------+----------------+
| rows_written | distinct_orders | total_net      |
+--------------+-----------------+----------------+
|        70000 |           70000 | 3389358750.00  |
+--------------+-----------------+----------------+
```

**70,000건, `total_net` 도 정답지와 일치.** 3회를 더 돌려도 항상 같습니다.

정리하면 이렇습니다.

| 구성 | 소요 | 배수 | `distinct_orders` | 판정 |
|---|---|---|---|---|
| 단일 스레드 | 62.4초 | 1.00배 | 70,000 | ✅ |
| 4스레드 (동기화 없음) | 18.9초 | 3.3배 | 69,213 (매번 다름) | ❌ **틀림** |
| 4스레드 (`Synchronized`) | 21.6초 | **약 2.9배** | 70,000 | ✅ |

동기화 비용은 18.9초 → 21.6초, **약 14% 손해**입니다. 이 정도면 싼 보험입니다.

### 천장 — 스레드를 8개로 늘려도 안 빨라집니다

```java
executor.setCorePoolSize(8);
executor.setMaxPoolSize(8);
```

**결과**
```
INFO 52905 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementSyncMtStep] executed in 19s412ms
```

| 스레드 | 소요 | 배수 |
|---|---|---|
| 4 | 21.6초 | 2.9배 |
| **8** | **19.4초** | **3.2배** |
| 16 | 19.1초 | 3.3배 |

4 → 8 로 두 배 늘렸는데 **10% 밖에 안 빨라졌고**, 16 은 사실상 제자리입니다.

이유는 명확합니다. `SynchronizedItemStreamReader` 는 **읽기를 완전히 직렬화**합니다. 스레드가 몇 개든 `read()` 는 한 번에 하나씩만 실행됩니다.

```
                 ┌──────────── 직렬 (아무리 스레드를 늘려도 여기는 1줄) ─────────────┐
   T1  ─ read ─┐
   T2  ─ read ─┤ Lock 대기 ...
   T3  ─ read ─┤
   T4  ─ read ─┘
                 └─→ 각자 process / write 는 병렬 ──────────────────────────────────┘
```

암달의 법칙 그대로입니다. 전체 작업 중 읽기가 30% 를 차지하고 그 30% 가 직렬이면, 나머지를 아무리 병렬화해도 **최대 3.3배**를 넘을 수 없습니다.

> 💡 **실무 팁 — 멀티스레드 Step 의 손익분기점**
> 멀티스레드 Step 은 **처리(processor)가 무거울 때만** 값어치를 합니다.
> - 읽기 30% / 처리 60% / 쓰기 10% → 병렬화 효과 큼 ✅
> - 읽기 70% / 처리 10% / 쓰기 20% → 거의 효과 없음 ❌ (이럴 땐 파티셔닝)
> 우리 정산 Job 은 processor 가 `BigDecimal` 곱셈 몇 번뿐이라 후자에 가깝습니다. **그래서 13-7 의 파티셔닝이 필요합니다.**

---

## 13-5. `saveState(false)` — 속도를 위해 재시작을 포기한다

멀티스레드 Step 에는 아직 남은 문제가 있습니다. **재시작이 망가집니다.**

`ItemStream` 인 Reader 는 청크 커밋마다 `update(ExecutionContext)` 로 "여기까지 읽었다"를 저장합니다. 단일 스레드에서는 이게 정확합니다. 그런데 멀티스레드에서는:

```
청크 1 (T1) ─ 진행 중 ...............
청크 2 (T2) ─ 완료 → "2000건까지 읽음" 저장
청크 3 (T3) ─ 완료 → "3000건까지 읽음" 저장
청크 1 (T1) ─ 완료 → "1000건까지 읽음" 저장   ← 뒤늦게 덮어씀!
```

**청크 완료 순서가 보장되지 않으므로 저장되는 위치도 뒤죽박죽**입니다. 이 상태로 재시작하면 이미 처리한 구간을 다시 하거나, 처리 안 한 구간을 건너뜁니다.

이대로 두면 어떻게 되는지 보겠습니다. 멀티스레드 Step 을 일부러 30,000건쯤에서 죽인 뒤 재시작합니다.

**결과**
```
INFO 53102 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step settlementSyncMtStep in job settlementSyncMtJob
java.lang.IllegalStateException: Cannot restart step from ExecutionContext that was saved concurrently.
	Reader state may be inconsistent. Consider setting saveState(false) on the reader.
	at org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader.open(...)
```

Spring Batch 는 이 상황을 **감지는 합니다.** 하지만 고쳐 주지는 않습니다. 유일한 대응은 상태 저장을 아예 끄는 것입니다.

```java
@Bean
public JdbcCursorItemReader<Order> orderCursorReaderNoState(DataSource dataSource) {
    return new JdbcCursorItemReaderBuilder<Order>()
            .name("orderCursorReaderNoState")
            .dataSource(dataSource)
            .fetchSize(1000)
            .saveState(false)            // ← 재시작 정보를 저장하지 않습니다
            .sql("""
                 SELECT order_id, customer_id, amount, status, ordered_at
                 FROM orders WHERE status = 'COMPLETED' ORDER BY order_id
                 """)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

`saveState(false)` 를 켜고 `BATCH_STEP_EXECUTION_CONTEXT` 를 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_EXECUTION_ID, SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT
ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+-------------------+------------------------------------------------------------+
| STEP_EXECUTION_ID | SHORT_CONTEXT                                              |
+-------------------+------------------------------------------------------------+
|                12 | {"@class":"java.util.HashMap","batch.taskletType":"org.spr  |
|                   | ingframework.batch.core.step.item.ChunkOrientedTasklet","b  |
|                   | atch.stepType":"org.springframework.batch.core.step.taskle  |
|                   | t.TaskletStep"}                                            |
+-------------------+------------------------------------------------------------+
```

`orderCursorReaderNoState.read.count` 같은 키가 **아예 없습니다.** 재시작 시 참고할 정보가 하나도 남지 않았다는 뜻입니다.

> ⚠️ **함정 — `saveState(false)` 는 "재시작하면 처음부터"가 아니라 "재시작하면 안 됨"입니다**
> 이걸 켜 두고 실패한 Job 을 재시작하면, Spring Batch 는 예외 없이 **처음부터 다시 읽습니다.**
> 우리 Writer 는 멱등이므로 이미 정산된 건은 `UPDATE` 로 덮어써 결과가 맞습니다. **운이 좋은 경우입니다.**
> 그런데 Writer 가 다음 중 하나라면 재실행은 곧 사고입니다.
> - 파일에 append 하는 Writer → 3만 줄이 중복으로 더 붙습니다
> - 카운터를 `UPDATE ... SET cnt = cnt + 1` 하는 Writer → 3만 건이 두 번 더해집니다
> - 외부 API 호출(정산 알림 발송) → 3만 명에게 알림이 두 번 갑니다
>
> **`saveState(false)` 를 켜는 순간, "이 Step 은 어떤 지점에서 죽어도 전체를 안전하게 다시 돌릴 수 있는가?"에 대한 책임이 전부 여러분에게 넘어옵니다.**
> 답이 "아니오"라면 멀티스레드 Step 을 쓰면 안 됩니다. 파티셔닝을 쓰세요. **파티셔닝은 재시작이 정상 동작합니다**(13-7).

멀티스레드 Step 의 대차대조표입니다.

| 얻는 것 | 잃는 것 |
|---|---|
| 코드 한 줄로 약 3배 (processor 가 무거우면 더) | Reader 스레드 안전성을 직접 보장해야 함 |
| Step 구조 변경 없음 | 재시작 불가 (`saveState(false)` 강제) |
| | 읽기 직렬화로 3~4배에서 천장 |
| | 청크 실패 시 어느 데이터가 문제였는지 추적 어려움 |

---

## 13-6. 병렬 Step (split) — 서로 다른 일을 동시에

지금까지는 **하나의 Step 을 쪼갰습니다.** 반대로, **원래 별개인 Step 두 개를 동시에** 돌릴 수도 있습니다.

정산 Job 에는 이런 Step 들이 있습니다.

- `settlementStep` — 주문 정산 (62.4초)
- `customerStatStep` — 고객별 집계 갱신 (8.1초)

둘은 서로 의존하지 않습니다. 순차로 하면 70.5초이지만, 동시에 하면 둘 중 긴 쪽만 기다리면 됩니다.

```java
@Bean
public Job parallelStepJob(JobRepository jobRepository,
                           Step settlementSingleStep,
                           Step customerStatStep,
                           Step mergeReportStep) {

    Flow settlementFlow = new FlowBuilder<SimpleFlow>("settlementFlow")
            .start(settlementSingleStep)
            .build();

    Flow statFlow = new FlowBuilder<SimpleFlow>("statFlow")
            .start(customerStatStep)
            .build();

    Flow splitFlow = new FlowBuilder<SimpleFlow>("splitFlow")
            .start(settlementFlow)
            .split(new SimpleAsyncTaskExecutor("split-"))   // ← 여기서 갈라집니다
            .add(statFlow)
            .build();

    return new JobBuilder("parallelStepJob", jobRepository)
            .start(splitFlow)
            .next(mergeReportStep)          // 둘 다 끝나야 실행됩니다
            .end()
            .build();
}
```

**결과**
```
INFO 53511 --- [       split-1] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementSingleStep]
INFO 53511 --- [       split-2] o.s.batch.core.job.SimpleStepHandler     : Executing step: [customerStatStep]
INFO 53511 --- [       split-2] o.s.batch.core.step.AbstractStep         : Step: [customerStatStep] executed in 8s104ms
INFO 53511 --- [       split-1] o.s.batch.core.step.AbstractStep         : Step: [settlementSingleStep] executed in 1m2s488ms
INFO 53511 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [mergeReportStep]
INFO 53511 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [mergeReportStep] executed in 512ms
INFO 53511 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=parallelStepJob]] completed with the following parameters: [{'run.id':'{value=8, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 1m3s049ms
```

**순차 70.5초(62.4 + 8.1) → 병렬 63.0초. 약 1.1배.**

효과가 초라합니다. 당연합니다. **split 은 가장 긴 Step 보다 빨라질 수 없습니다.** 62.4초짜리 하나가 있는 한 63초 밑으로 안 내려갑니다.

> 💡 **split 은 "확장"이라기보다 "낭비 제거"입니다**
> Step 들의 소요가 비슷할수록 이득이 큽니다. 30초 Step 4개를 split 하면 120초 → 32초(약 3.8배)입니다.
> 반대로 하나가 압도적으로 길면 거의 의미가 없습니다. **그 긴 Step 자체를 쪼개야 하고, 그게 파티셔닝입니다.**
> split 은 흐름 제어의 일부이므로 문법은 [Step 10](../step-10-flow-control/) 을 참고하세요.

---

## 13-7. 파티셔닝 — 데이터를 나누고, Step 을 복제한다

파티셔닝의 발상은 다릅니다. **하나의 Step 을 여러 스레드가 공유하는 게 아니라, Step 실행 자체를 N개로 복제**하고 각각에 서로 다른 데이터 구간을 줍니다.

```
                        ┌──────────────────────────┐
                        │  마스터 Step (Manager)    │
                        │  Partitioner 로 범위 계산  │
                        └────────────┬─────────────┘
                                     │ StepExecution 8개 생성
        ┌──────────┬──────────┬──────┴───┬──────────┬──────────┐
        ▼          ▼          ▼          ▼          ▼          ▼
   worker:0    worker:1    worker:2   ...        worker:7
   1~12500   12501~25000  25001~37500          87501~100000
   ↑ 각자 자기만의 Reader / Processor / Writer / 트랜잭션 / ExecutionContext
```

핵심은 **워커끼리 공유하는 상태가 하나도 없다**는 점입니다. 그래서 스레드 안전성 문제도, 재시작 문제도 원천적으로 생기지 않습니다.

### Partitioner — 범위를 계산한다

`Partitioner` 는 "파티션 이름 → 그 파티션이 처리할 범위를 담은 `ExecutionContext`" 맵을 만드는 인터페이스입니다.

```java
public class OrderIdRangePartitioner implements Partitioner {

    private final JdbcTemplate jdbcTemplate;

    public OrderIdRangePartitioner(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Long min = jdbcTemplate.queryForObject(
                "SELECT MIN(order_id) FROM orders WHERE status = 'COMPLETED'", Long.class);
        Long max = jdbcTemplate.queryForObject(
                "SELECT MAX(order_id) FROM orders WHERE status = 'COMPLETED'", Long.class);

        long targetSize = (max - min) / gridSize + 1;

        Map<String, ExecutionContext> result = new LinkedHashMap<>();
        long start = min;
        for (int i = 0; i < gridSize; i++) {
            ExecutionContext ctx = new ExecutionContext();
            long end = Math.min(start + targetSize - 1, max);
            ctx.putLong("minId", start);
            ctx.putLong("maxId", end);
            ctx.putString("partitionName", "partition" + i);
            result.put("partition" + i, ctx);
            start = end + 1;
        }
        return result;
    }
}
```

우리 데이터에서 `MIN=1`, `MAX=100000` 입니다(`1 % 10 = 1`, `100000 % 10 = 0` 둘 다 COMPLETED 조건을 만족). `gridSize=8` 이면 파티션당 12,500개의 `order_id` 구간이고, 그 안의 COMPLETED 는 **정확히 8,750건씩**입니다. `70000 / 8 = 8750` — 완벽하게 균등합니다.

> 💡 **이 균등함은 실습 데이터가 결정론적이라서 얻어진 행운입니다**
> 실제 데이터는 절대 이렇게 안 나눠집니다. 신규 주문이 뒤쪽 ID 에 몰려 있고 오래된 ID 구간에는 삭제된 행이 많습니다.
> 뒤에서 **데이터 스큐(skew)** 문제로 다시 다룹니다(13-10).

### 마스터 Step 과 워커 Step

```java
@Bean
public Step settlementPartitionMasterStep(JobRepository jobRepository,
                                          Step settlementWorkerStep,
                                          DataSource dataSource,
                                          TaskExecutor partitionTaskExecutor) {
    return new StepBuilder("settlementPartitionMasterStep", jobRepository)
            .partitioner("settlementWorkerStep", new OrderIdRangePartitioner(dataSource))
            .step(settlementWorkerStep)
            .partitionHandler(partitionHandler(settlementWorkerStep, partitionTaskExecutor))
            .build();
}

@Bean
public TaskExecutorPartitionHandler partitionHandler(Step settlementWorkerStep,
                                                     TaskExecutor partitionTaskExecutor) {
    TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
    handler.setStep(settlementWorkerStep);
    handler.setTaskExecutor(partitionTaskExecutor);
    handler.setGridSize(8);                 // Partitioner.partition(8) 로 전달됩니다
    return handler;
}
```

`PartitionHandler` 는 "만들어진 파티션들을 **어떻게 실행할 것인가**"를 담당합니다.

| 구현체 | 실행 위치 | 용도 |
|---|---|---|
| `TaskExecutorPartitionHandler` | 같은 JVM 의 스레드 풀 | 로컬 파티셔닝 (이 스텝) |
| `MessageChannelPartitionHandler` | 다른 JVM (메시지 큐 경유) | 원격 파티셔닝 (13-11) |

> ⚠️ **함정 — `gridSize` 는 두 군데에 있고, 서로 다른 값이면 조용히 한쪽이 집니다**
> `.partitioner(name, partitioner)` 만 쓰면 `SimplePartitioner` 기본 `gridSize` 는 **6** 입니다.
> `PartitionHandler` 에도 `setGridSize(8)` 이 있습니다. **`PartitionHandler` 를 명시적으로 등록하면 그쪽 값이 `Partitioner.partition(gridSize)` 로 전달됩니다.**
> 두 곳에 다른 값을 써 놓고 "왜 파티션이 6개지?" 하며 헤매는 일이 흔합니다.
> 그리고 **`gridSize` 는 "스레드 개수"가 아니라 "데이터를 몇 조각으로 쪼갤 것인가"입니다.** 동시에 몇 개가 도는지는 `TaskExecutor` 의 풀 크기가 정합니다.
> `gridSize=100`, `corePoolSize=4` 로 두면 100개의 파티션이 4개씩 순차로 처리됩니다 — 이건 **정상이고 오히려 권장되는 구성**입니다(13-10).

---

## 13-8. 늦은 바인딩 — `#{stepExecutionContext['minId']}`

파티셔닝의 진짜 핵심은 여기입니다. Partitioner 가 `ExecutionContext` 에 `minId`/`maxId` 를 넣어 뒀는데, **워커 Step 의 Reader 가 그 값을 어떻게 읽을까요?**

`@Bean` 메서드는 애플리케이션 기동 시 **딱 한 번** 호출됩니다. 그때는 아직 파티션이 만들어지기도 전입니다. 그래서 `@StepScope` 와 SpEL 늦은 바인딩이 필요합니다. [Step 09](../step-09-execution-context/) 에서 배운 그것입니다.

```java
@Bean
@StepScope                                          // ← 없으면 동작하지 않습니다
public JdbcPagingItemReader<Order> partitionedOrderReader(
        DataSource dataSource,
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) {

    MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
    provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
    provider.setFromClause("FROM orders");
    provider.setWhereClause("WHERE status = 'COMPLETED' AND order_id BETWEEN :minId AND :maxId");
    provider.setSortKeys(Map.of("order_id", Order.ASCENDING));

    return new JdbcPagingItemReaderBuilder<Order>()
            .name("partitionedOrderReader")
            .dataSource(dataSource)
            .queryProvider(provider)
            .parameterValues(Map.of("minId", minId, "maxId", maxId))
            .pageSize(1000)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}

@Bean
public Step settlementWorkerStep(JobRepository jobRepository,
                                 PlatformTransactionManager txManager,
                                 JdbcPagingItemReader<Order> partitionedOrderReader,
                                 JdbcBatchItemWriter<Settlement> settlementWriter) {
    return new StepBuilder("settlementWorkerStep", jobRepository)
            .<Order, Settlement>chunk(1000, txManager)
            .reader(partitionedOrderReader)
            .processor(new SettlementProcessor())
            .writer(settlementWriter)
            .build();
}
```

> ⚠️ **함정 — `@StepScope` 를 빼먹으면 에러 대신 `null` 이 들어옵니다**
> `@StepScope` 없이 `@Value("#{stepExecutionContext['minId']}")` 를 쓰면 기동 시점에 `stepExecutionContext` 가 없어서 이렇게 됩니다.
>
> ```
> org.springframework.expression.spel.SpelEvaluationException: EL1007E:
>   Property or field 'minId' cannot be found on null
> ```
>
> 여기까지는 **시끄러운 실패**라 다행입니다. 문제는 `Long` 이 아니라 `Long` 을 감싼 파라미터가 `null` 허용일 때입니다.
> `parameterValues(Map.of("minId", minId, ...))` 에 `null` 이 들어가면 `Map.of` 가 `NullPointerException` 을 던져 그나마 걸리지만,
> `new HashMap<>()` 으로 넣으면 **`WHERE order_id BETWEEN NULL AND NULL` 이 되어 0건을 읽고 조용히 `COMPLETED` 로 끝납니다.**
> 정산이 0건인데 Job 은 성공입니다. `Map.of` 를 쓰는 게 방어적입니다.

실행합니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun -Pargs=--spring.batch.job.name=settlementPartitionJob
```

**결과**
```
INFO 54120 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementPartitionMasterStep]
INFO 54120 --- [ partition-p-1] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition0] executed in 10s882ms
INFO 54120 --- [ partition-p-3] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition2] executed in 10s941ms
INFO 54120 --- [ partition-p-2] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition1] executed in 11s004ms
INFO 54120 --- [ partition-p-6] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition5] executed in 11s032ms
INFO 54120 --- [ partition-p-4] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition3] executed in 11s070ms
INFO 54120 --- [ partition-p-8] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition7] executed in 11s098ms
INFO 54120 --- [ partition-p-5] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition4] executed in 11s121ms
INFO 54120 --- [ partition-p-7] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition6] executed in 11s143ms
INFO 54120 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementPartitionMasterStep] executed in 11s205ms
INFO 54120 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementPartitionJob]] completed with the following parameters: [{'run.id':'{value=11, type=class java.lang.Long, identifying=true}'}] and the following status: [COMPLETED] in 11s317ms
```

**62.4초 → 11.2초. 약 5.6배.** 멀티스레드 Step 의 천장(3.3배)을 확실히 넘었습니다.

검증합니다.

```
+--------------+-----------------+----------------+
| rows_written | distinct_orders | total_net      |
+--------------+-----------------+----------------+
|        70000 |           70000 | 3389358750.00  |
+--------------+-----------------+----------------+
```

**정확히 일치합니다.** 그리고 몇 번을 돌려도 같습니다 — 공유 상태가 없으니 당연합니다.

메타데이터를 보면 파티셔닝의 구조가 그대로 드러납니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = (SELECT MAX(JOB_EXECUTION_ID) FROM BATCH_JOB_EXECUTION)
ORDER BY STEP_EXECUTION_ID;"
```

**결과**
```
+-------------------------------+-----------+------------+-------------+--------------+
| STEP_NAME                     | STATUS    | READ_COUNT | WRITE_COUNT | COMMIT_COUNT |
+-------------------------------+-----------+------------+-------------+--------------+
| settlementPartitionMasterStep | COMPLETED |          0 |           0 |            1 |
| settlementWorkerStep:partition0 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition1 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition2 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition3 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition4 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition5 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition6 | COMPLETED |     8750 |        8750 |            9 |
| settlementWorkerStep:partition7 | COMPLETED |     8750 |        8750 |            9 |
+-------------------------------+-----------+------------+-------------+--------------+
```

**워커마다 독립된 `STEP_EXECUTION` 행이 생깁니다.** 이것이 파티셔닝이 재시작 가능한 이유입니다.
`partition3` 만 실패했다면 그 행만 `FAILED` 로 남고, 재시작하면 **`COMPLETED` 인 7개는 건너뛰고 `partition3` 만** 다시 돕니다.

```
INFO 54430 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Step already complete or not restartable, so no action to take: StepExecution: id=32, name=settlementWorkerStep:partition0, status=COMPLETED
INFO 54430 --- [ partition-p-1] o.s.batch.core.step.AbstractStep         : Step: [settlementWorkerStep:partition3] executed in 10s994ms
```

멀티스레드 Step 이 `saveState(false)` 로 재시작을 통째로 포기한 것과 정확히 대비됩니다.

---

## 13-9. gridSize 튜닝 — 스레드 풀과 커넥션 풀

파티션을 늘리면 계속 빨라질까요? `gridSize` 를 4 / 8 / 16 으로 바꿔 가며 재 봅니다(`TaskExecutor` 풀 크기도 같이 맞춤).

| gridSize | 풀 크기 | 파티션당 건수 | 소요 | 배수 | `distinct_orders` |
|---|---|---|---|---|---|
| 1 (= 단일 스레드) | 1 | 70,000 | 62.4초 | 1.00배 | 70,000 |
| 4 | 4 | 17,500 | 17.2초 | 3.6배 | 70,000 |
| **8** | **8** | **8,750** | **11.2초** | **5.6배** | 70,000 |
| 16 | 16 | 4,375 | 12.9초 | 4.8배 | 70,000 |
| 32 | 32 | 2,188 | 24.6초 | 2.5배 | 70,000 |

**8에서 최고점을 찍고 16부터 다시 느려집니다.** 32는 거의 반토막입니다.

16 실행의 로그를 보면 이유가 나옵니다.

**결과**
```
WARN 54780 --- [partition-p-14] com.zaxxer.hikari.pool.HikariPool        : batch-pool - Connection is not available, request timed out after 30001ms.
WARN 54780 --- [partition-p-15] com.zaxxer.hikari.pool.HikariPool        : batch-pool - Thread starvation or clock leap detected (housekeeper delta=32s181ms).
INFO 54780 --- [           main] com.zaxxer.hikari.pool.HikariPool        : batch-pool - Pool stats (total=20, active=20, idle=0, waiting=9)
```

`active=20, idle=0, waiting=9`. **커넥션 풀이 다 찼습니다.**

[프로젝트 셋업](../project/) 의 설정을 다시 봅니다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        # Step 13 의 멀티스레드/파티셔닝에서 필요
```

**풀은 20개**입니다. 그런데 파티셔닝은 워커 하나당 커넥션을 여러 개 씁니다.

| 용도 | 개수 | 설명 |
|---|---|---|
| 워커 트랜잭션 | 워커당 1 | 청크 트랜잭션이 커밋될 때까지 붙잡습니다 |
| `JobRepository` 갱신 | 워커당 간헐적 1 | 청크 커밋마다 `BATCH_STEP_EXECUTION` UPDATE |
| 마스터 Step | 1 | 파티션 상태 폴링 |
| `Partitioner` 초기 쿼리 | 1 (일시적) | `MIN`/`MAX` 조회 |

16 워커 × 2 + 마스터 1 = **33개**를 원하는데 풀은 20개입니다. 나머지는 30초 타임아웃을 기다리며 놀고, 그동안 CPU 도 논다는 뜻입니다.

> ⚠️ **함정 — 병렬도를 늘렸는데 느려졌다면 십중팔구 커넥션 풀입니다**
> 스레드를 늘리면 빨라진다는 직관은 **CPU 가 병목일 때만** 맞습니다. 배치는 대부분 **DB 가 병목**입니다.
> 커넥션 풀이 부족하면 스레드는 늘어나되 전부 `getConnection()` 에서 대기하고, 컨텍스트 스위칭 비용만 늘어납니다.
> 최소 조건은 이겁니다. **`maximum-pool-size` ≥ (동시 워커 수 × 2) + 여유 4**
> 우리 환경(풀 20)의 안전한 동시 워커 수는 **8** 입니다. 그래서 `gridSize=8` 이 최고점이었던 것입니다.
>
> 그리고 풀을 무작정 키우는 것도 답이 아닙니다. MySQL 의 `max_connections` 기본값은 151 이고, 커넥션 하나당 서버 메모리를 씁니다.
> DB 쪽 CPU/디스크가 포화되면 커넥션을 늘려도 **전체 처리량은 오히려 떨어집니다.**

> 💡 **실무 팁 — `gridSize` 와 풀 크기를 분리하세요**
> 데이터 스큐(어떤 구간에만 데이터가 몰림)에 대응하려면 `gridSize` 는 **크게**, 동시 실행 스레드는 **작게** 두는 것이 정석입니다.
> ```java
> handler.setGridSize(100);            // 데이터를 100조각으로
> executor.setCorePoolSize(8);         // 동시에는 8개만
> ```
> 이러면 빨리 끝난 워커가 다음 파티션을 바로 집어 가는 **작업 훔치기(work stealing)** 효과가 생겨,
> 파티션 하나가 유독 오래 걸려도 전체가 그것 때문에 기다리지 않습니다.
> `gridSize = 스레드 수` 로 맞추는 것은 **데이터가 완벽히 균등할 때만** 최적입니다. 실제 데이터는 그렇지 않습니다.

`gridSize=100`, 풀 8 로 다시 재면 이렇습니다.

**결과**
```
INFO 55011 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementPartitionMasterStep] executed in 11s854ms
```

11.9초 — 균등 데이터라 8파티션(11.2초)보다 살짝 느립니다(파티션 100개의 `StepExecution` 생성/갱신 오버헤드). 그러나 스큐가 있는 실데이터에서는 이 구성이 훨씬 안정적입니다.

---

## 13-10. 원격 청킹과 원격 파티셔닝

여기까지는 전부 **하나의 JVM** 안입니다. CPU 코어와 커넥션 풀이 천장입니다. 그 위로 가려면 JVM 을 늘려야 합니다.

### 원격 청킹 (Remote Chunking)

**읽기는 마스터가 혼자 하고, 처리와 쓰기만 워커에게 보냅니다.**

```
   ┌──────── Master JVM ────────┐            ┌──── Worker JVM × N ────┐
   │  ItemReader (1개, 마스터만) │  ──청크──▶  │  Processor + Writer     │
   │  ChunkMessageChannelItem-   │  ◀─응답──  │  ChunkProcessorChunk-   │
   │  WriterItemWriter           │            │  Handler                │
   └────────────────────────────┘            └────────────────────────┘
                    │
              메시지 브로커 (RabbitMQ / Kafka / JMS)
```

- **적용 조건**: 처리(processor)가 압도적으로 무거울 때. 예를 들어 건별 외부 API 호출, 암호화, ML 추론.
- **한계**: 읽은 데이터를 **전부 네트워크로 실어 보냅니다.** 우리 정산 Job 처럼 processor 가 곱셈 몇 번뿐이면 **직렬화 비용이 처리 비용보다 커서 오히려 느려집니다.**
- 의존성: `spring-batch-integration` + Spring Integration 채널 어댑터.

### 원격 파티셔닝 (Remote Partitioning)

**마스터는 범위만 나눠 주고, 워커가 읽기부터 쓰기까지 전부 합니다.**

```java
// 마스터: TaskExecutorPartitionHandler 대신 MessageChannelPartitionHandler
@Bean
public MessageChannelPartitionHandler remotePartitionHandler(
        MessagingTemplate messagingTemplate, JobExplorer jobExplorer) {
    MessageChannelPartitionHandler handler = new MessageChannelPartitionHandler();
    handler.setStepName("settlementWorkerStep");
    handler.setGridSize(8);
    handler.setMessagingOperations(messagingTemplate);   // 요청 큐로 전송
    handler.setJobExplorer(jobExplorer);                 // 완료는 DB 폴링으로 확인
    handler.setPollInterval(2000L);
    return handler;
}
```

- 마스터가 큐로 보내는 것은 **`minId`/`maxId` 같은 작은 메타데이터뿐**입니다. 실제 데이터는 워커가 DB 에서 직접 읽습니다.
- **네트워크 트래픽이 거의 없습니다.** 대용량 배치의 사실상 표준입니다.
- 워커도 같은 `JobRepository` 를 봐야 합니다. 그래서 **메타데이터 DB 를 공유**해야 합니다.

네 방식을 최종 정리하면 이렇습니다.

| 방식 | 데이터 이동 | 확장 상한 | 구현 난이도 | 재시작 | 우리 정산 Job 에 적합? |
|---|---|---|---|---|---|
| 멀티스레드 Step | 없음 | ~3배 (읽기 직렬화) | ★☆☆ | ❌ | △ processor 가 가벼워 효과 작음 |
| 병렬 Step (split) | 없음 | 가장 긴 Step | ★☆☆ | ✅ | ✗ 긴 Step 이 하나뿐 |
| **파티셔닝 (로컬)** | 없음 | 커넥션 풀 | ★★☆ | ✅ | **◎ 최적** |
| 원격 청킹 | **전 데이터** | 워커 수 | ★★★ | △ | ✗ 직렬화 비용이 더 큼 |
| 원격 파티셔닝 | 메타데이터만 | 워커 수 | ★★★ | ✅ | ○ 수억 건이면 |

> 💡 **실무 팁 — 순서대로 시도하세요**
> 1. **먼저 단일 스레드를 튜닝합니다.** `fetchSize`, `chunkSize`, `rewriteBatchedStatements=true`([Step 08](../step-08-item-writer/) 에서 약 8배), 인덱스.
>    이것만으로 5배가 나오는 경우가 흔합니다. **병렬화는 항상 복잡도를 올립니다.**
> 2. 그래도 부족하면 **로컬 파티셔닝**. 데이터를 균등하게 쪼갤 키가 있으면 여기서 대부분 끝납니다.
> 3. 한 대의 DB 커넥션/CPU 가 진짜로 포화됐을 때만 **원격 파티셔닝**.
> 원격 청킹은 "processor 만 비정상적으로 무겁다"는 특수한 프로파일에서만 정답입니다.

---

## 13-11. 성능 실측 종합

70,000건 정산 기준, 이 스텝의 모든 측정을 한 표에 모읍니다. 환경은 MySQL 8.0.36 (Docker, `innodb-buffer-pool-size=512M`), Hikari 풀 20, 청크 1,000 입니다.

| # | 구성 | 소요 | 배수 | `distinct_orders` | 재시작 | 비고 |
|---|---|---|---|---|---|---|
| 1 | 단일 스레드 | **62.4초** | 1.00배 | 70,000 ✅ | ✅ | 기준선 |
| 2 | 멀티스레드 4 (동기화 없음) | 18.9초 | 3.3배 | **69,213 ❌** | ❌ | **데이터 유실. 매번 다름** |
| 3 | 멀티스레드 4 (`Synchronized`) | 21.6초 | 2.9배 | 70,000 ✅ | ❌ | 동기화 비용 약 14% |
| 4 | 멀티스레드 8 (`Synchronized`) | 19.4초 | 3.2배 | 70,000 ✅ | ❌ | 읽기 직렬화로 천장 |
| 5 | 멀티스레드 16 (`Synchronized`) | 19.1초 | 3.3배 | 70,000 ✅ | ❌ | 제자리 |
| 6 | 병렬 Step (split, 2 Step) | 63.0초 | 1.1배 | 70,000 ✅ | ✅ | 긴 Step 이 지배 |
| 7 | 파티셔닝 grid 4 | 17.2초 | 3.6배 | 70,000 ✅ | ✅ | |
| 8 | **파티셔닝 grid 8** | **11.2초** | **5.6배** | 70,000 ✅ | ✅ | **최적** |
| 9 | 파티셔닝 grid 16 | 12.9초 | 4.8배 | 70,000 ✅ | ✅ | 커넥션 풀 20 포화 |
| 10 | 파티셔닝 grid 32 | 24.6초 | 2.5배 | 70,000 ✅ | ✅ | 대기 지옥 |
| 11 | 파티셔닝 grid 100 / 풀 8 | 11.9초 | 5.2배 | 70,000 ✅ | ✅ | 스큐 대응용 권장 구성 |

이 표에서 읽어야 할 것은 셋입니다.

1. **가장 빠른 잘못된 답(#2, 18.9초)이 정답들 중 여럿보다 빠릅니다.** 성능만 보면 매력적이고, 그래서 위험합니다.
2. **파티셔닝이 멀티스레드를 이깁니다**(5.6배 vs 3.3배). 게다가 재시작까지 됩니다. 로컬 확장에서 파티셔닝을 먼저 검토해야 할 이유입니다.
3. **병렬도에는 최적점이 있습니다.** 단조 증가가 아닙니다. 8 → 32 로 늘리면 **오히려 2배 이상 느려집니다.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| 멀티스레드 Step | `.taskExecutor(...)` 한 줄. Reader **1개를 공유**하는 것이 모든 문제의 근원 |
| 스레드 비안전 Reader | Cursor 계열·FlatFile·Stax·직접 만든 Reader. **누락/중복이 조용히** 발생 |
| 증상 판별법 | 결정론적 입력인데 **매 실행 결과가 다르면** 스레드 안전성 문제 |
| `READ_COUNT` 의 배신 | 70,000 읽고 70,000 썼다고 기록되지만 실제 행은 69,213 |
| 멱등 Writer | 중복은 흡수하지만 **누락은 감춰서 더 위험**해짐 |
| `SynchronizedItemStreamReader` | `read()` 를 락으로 감쌈. 정확해지지만 **읽기가 직렬화**되어 3~4배가 천장 |
| `saveState(false)` | 멀티스레드 Step 의 필수 조건. **재시작을 포기**한다는 선언 |
| 병렬 Step (split) | 서로 다른 Step 동시 실행. **가장 긴 Step 보다 빨라질 수 없음** |
| 파티셔닝 | Step 실행을 N개로 복제. **공유 상태 없음** → 스레드 안전·재시작 모두 해결 |
| `Partitioner` | `gridSize` 를 받아 `{파티션명 → ExecutionContext}` 반환. 보통 PK 범위 분할 |
| `PartitionHandler` | 로컬은 `TaskExecutorPartitionHandler`, 원격은 `MessageChannelPartitionHandler` |
| `gridSize` | 스레드 수가 아니라 **조각 수**. 크게 두고 풀은 작게 두면 스큐에 강함 |
| 늦은 바인딩 | `@StepScope` + `#{stepExecutionContext['minId']}`. **`@StepScope` 빠지면 SpEL 예외** |
| 커넥션 풀 | `maximum-pool-size` ≥ 동시 워커 × 2 + 4. 풀 20 이면 워커 8이 상한 |
| 실측 | 62.4초 → 8파티션 **11.2초 (약 5.6배)**. 16파티션은 12.9초로 **역전** |
| 선택 순서 | 단일 스레드 튜닝 → 로컬 파티셔닝 → 원격 파티셔닝 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. 주어진 커스텀 Reader 가 왜 스레드 안전하지 않은지 짚고, 두 가지 방법으로 고치기
2. `SynchronizedItemStreamReader` 로 감싸도 여전히 틀리는 케이스 찾기 (힌트: Writer 와 Processor 도 상태를 갖는다)
3. `customer_id` 를 기준으로 나누는 `Partitioner` 작성하기 (등급별 스큐 고려)
4. `@StepScope` 를 빼면 어떤 예외가 어느 시점에 나는지 예측하고 확인하기
5. 커넥션 풀 20 에서 안전한 최대 `gridSize` 계산하고 근거 대기
6. 멀티스레드 Step / 파티셔닝 중 어느 것을 써야 하는지 4개 시나리오로 판정하기

---

## 다음 단계

배치를 빠르게 만들었습니다. 이제 남은 것은 **이 배치를 매일 밤 알아서 돌게 만들고, 잘 돌았는지 지켜보는 일**입니다.
스케줄링, 중복 실행 방지, 메타데이터 테이블로 하는 실패 분석, Micrometer 지표 노출을 다루고,
마지막으로 Step 01 부터 13 까지의 모든 요소를 한 Job 에 조립하는 **종합 실습**으로 코스를 마칩니다.

→ [Step 14 — 운영: 스케줄링 · 모니터링 · 최종 프로젝트](../step-14-operations/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 는 13-0 의 기준선부터 13-11 의 종합 비교까지 **모든 구성을 나란히 놓고 직접 갈아 끼워 가며 측정**하도록 만들어져 있습니다. 측정이 목적인 스텝이므로, 읽는 것보다 **돌려서 시간을 재는 것**이 훨씬 중요합니다.

### Practice.java

본문의 모든 Job/Step/Reader 구성을 절 번호 주석(`// [13-2]`)과 함께 `static class` 로 담았습니다.

- `Baseline`(13-0)이 기준선 62.4초를 만듭니다. **다른 어떤 절보다 먼저 실행하세요.** 비교 대상 없이 "18.9초가 나왔다"는 아무 의미가 없습니다.
- `MultiThread`(13-2)와 `ThreadUnsafe`(13-3)는 **일부러 틀린 코드**입니다. `NaiveOrderReader` 의 `index++` 는 고치지 말고 그대로 두세요. 틀린 것을 눈으로 보는 게 이 절의 목적입니다.
- 각 Job 실행 전후로 `TRUNCATE TABLE settlement` 를 해야 합니다. 파일 상단의 `RESET_SQL` 상수에 그 명령이 주석으로 들어 있습니다. **빼먹으면 이전 실행의 70,000행이 남아 있어 누락을 발견하지 못합니다** — 이 스텝에서 가장 흔한 실습 실수입니다.
- `Partitioning`(13-7, 13-8)의 `partitionedOrderReader` 에는 `@StepScope` 가 붙어 있습니다. 연습문제 4를 풀 때 **이 어노테이션만 주석 처리**하고 돌려 보면 됩니다.
- `GridSizeMatrix`(13-9)는 `GRID_SIZES = {1, 4, 8, 16, 32}` 배열을 돌며 같은 Job 을 반복 실행하는 러너입니다. 전체가 약 2분 30초 걸리고, 끝나면 13-11 의 비교표와 같은 형태로 콘솔에 요약을 찍습니다.
- `RemoteSketch`(13-10)는 **컴파일은 되지만 실행되지 않습니다.** RabbitMQ 와 `spring-batch-integration` 이 없기 때문이며, 원격 구성의 뼈대를 읽기 위한 참고용입니다. 실행하려면 브로커부터 띄워야 합니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. `// 여기에 작성:` 자리를 채우는 방식입니다.

- **문제 1·3** 은 코드를 **작성**하는 문제이고, **문제 2·4·5·6** 은 관찰하거나 판정하는 문제입니다.
- 문제 1 의 `BrokenListReader` 는 13-3 의 `NaiveOrderReader` 와 같은 구조이되, `orders` 대신 주입된 `List<Order>` 를 씁니다. 고치는 방법을 **두 개** 요구합니다(`AtomicInteger` 로 인덱스를 원자화 / `SynchronizedItemStreamReader` 로 감싸기). 둘의 성능 차이도 함께 재 보세요.
- ⚠️ **문제 2 가 이 스텝에서 가장 어렵습니다.** `SynchronizedItemStreamReader` 는 **Reader 만** 보호합니다. 문제지의 `RunningTotalProcessor` 는 `BigDecimal runningTotal` 필드를 누적하고 있어, Reader 를 아무리 동기화해도 이 합계는 매번 다르게 나옵니다. **"Reader 만 고치면 된다"는 오해를 깨는 문제**입니다.
- 문제 3 의 `customer_id` 분할은 `order_id` 분할보다 까다롭습니다. `customer_id = ((order_id - 1) % 1000) + 1` 이라 고객 1,000명에 주문이 정확히 100건씩 붙어 있고, 등급은 `customer_id % 4` 로 정해집니다. **`gridSize=4` 로 나누면 등급이 파티션마다 편중**되는 것을 확인하는 게 문제의 핵심입니다.
- 문제 5 는 계산 문제입니다. 답만 쓰지 말고 `HikariPool - Pool stats (total=20, active=..., waiting=...)` 로그를 실제로 확인해 근거를 만드세요. `logging.level.com.zaxxer.hikari=DEBUG` 를 켜면 30초마다 찍힙니다.

```java file="./Exercise.java"
```

### Solution.java

정답 코드와 "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 은 두 해법의 성능을 비교합니다. `AtomicInteger.getAndIncrement()` 는 CAS 라 락보다 싸지만, **`List.get(i)` 이 스레드 안전한 경우에만** 성립합니다. `SynchronizedItemStreamReader` 는 더 느리지만 **어떤 Reader 에도 적용됩니다.** "빠른 특수해 vs 느린 일반해"의 전형적인 트레이드오프입니다.
- **정답 2** 의 결론이 이 스텝의 진짜 교훈입니다. 스레드 안전성은 **Reader·Processor·Writer·Listener 전부**의 문제입니다. `RunningTotalProcessor` 는 애초에 상태를 갖지 말아야 하며(순수 함수), 정말 누적이 필요하면 `StepExecutionListener` 의 `afterStep` 에서 `StepExecution.getWriteCount()` 로 집계하거나 `LongAdder` 를 쓰라고 답합니다.
- **정답 3** 은 `customer_id` 를 250명씩 4구간으로 나누면 각 파티션의 등급 분포가 편중되는 것을 SQL 결과로 보여 준 뒤, `customer_id % gridSize` **해시 분할**로 바꾸면 등급이 고르게 섞이는 것을 대조합니다. **범위 분할과 해시 분할의 선택 기준**을 정리합니다.
- **정답 4** 는 `@StepScope` 를 빼면 `SpelEvaluationException: EL1007E: Property or field 'minId' cannot be found on null` 이 **애플리케이션 기동 중**(빈 생성 시점)에 난다고 답합니다. 런타임이 아니라 기동 시점이라는 게 중요합니다 — **이건 그나마 다행인 실패**이며, 13-8 의 함정 블록이 지적한 "`HashMap` 으로 넘겨 조용히 0건 처리"와 대조됩니다.
- **정답 5** 의 계산은 `(20 - 4) / 2 = 8` 입니다. 여유 4를 빼는 이유(마스터 Step 1 + `Partitioner` 초기 쿼리 1 + `JobRepository` 갱신 여유 2)와, 실측에서 grid 16 이 12.9초로 역전된 로그를 근거로 붙입니다.
- **정답 6** 의 4개 시나리오 판정 표가 실무에서 그대로 쓸 수 있는 의사결정 체크리스트입니다. 특히 "외부 API 를 건당 호출하는 processor" 는 **멀티스레드 Step 이 정답**인 유일한 시나리오입니다(I/O 대기 중 다른 스레드가 일함).

```java file="./Solution.java"
```
