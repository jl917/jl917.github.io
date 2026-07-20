# Step 07 — ItemProcessor

> **학습 목표**
> - `ItemProcessor<I, O>` 로 `Order` 를 `Settlement` 로 변환하고, 등급별 수수료율을 `BigDecimal` 로 정확히 계산한다
> - **`null` 을 반환하면 그 아이템이 필터링된다**는 규칙을 이해하고, 그 결과가 `filterCount` 에 어떻게 잡히는지 `BATCH_STEP_EXECUTION` 에서 확인한다
> - `filterCount` 와 `skipCount` 의 차이를 실행 결과로 구분한다
> - `CompositeItemProcessor` 로 변환 파이프라인을 만들고, **제네릭 타입이 컴파일 타임에 검증되지 않아 `ClassCastException` 이 런타임에 터지는 것**을 직접 재현한다
> - `ClassifierCompositeItemProcessor` 로 아이템 종류에 따라 처리를 분기한다
> - `ValidatingItemProcessor` / `BeanValidatingItemProcessor` 로 검증을 파이프라인에 끼워 넣는다
> - **ItemProcessor 가 상태를 가지면 안 되는 이유**를 카운터 예제로 확인한다
>
> **선행 스텝**: Step 06 — ItemReader
> **예상 소요**: 90분

---

## 7-0. 실습 준비

Step 06 에서 만든 리더를 그대로 씁니다. 시작 전에 메타데이터와 `settlement` 를 비웁니다.

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
```

**결과**
```
(출력 없음 — 정상)
```

이 스텝이 계속 참조할 두 숫자를 먼저 확인해 둡니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT c.grade, c.fee_rate, COUNT(*) AS completed_orders
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
WHERE o.status = 'COMPLETED'
GROUP BY c.grade, c.fee_rate
ORDER BY c.fee_rate DESC;"
```

**결과**
```
+--------+----------+------------------+
| grade  | fee_rate | completed_orders |
+--------+----------+------------------+
| BRONZE |   0.0350 |            20000 |
| SILVER |   0.0300 |            15000 |
| GOLD   |   0.0250 |            20000 |
| VIP    |   0.0200 |            15000 |
+--------+----------+------------------+
```

합계 **70,000건**입니다. 전체 주문은 100,000건이므로 **정산 대상이 아닌 주문이 30,000건** 있습니다. 이 30,000이 곧 `filterCount` 가 됩니다.

---

## 7-1. ItemProcessor 는 청크 루프의 어디에 있는가

Step 05 에서 본 청크 루프를 다시 펼칩니다. 프로세서가 **어디서 몇 번 호출되는지**가 이 스텝의 전제입니다.

```
트랜잭션 시작
  ├── read()  → item1        ┐
  ├── read()  → item2        │  chunkSize 만큼 반복
  ├── ...                    │  (읽기는 트랜잭션 안에서 하나씩)
  └── read()  → item1000     ┘
       │
       ├── process(item1)  → out1
       ├── process(item2)  → null   ← 필터링. 아래 write 로 안 내려간다
       ├── ...
       └── process(item1000) → out1000
       │
       └── write(Chunk[out1, out3, ... ])   ← null 이 빠진 것만 모아서 한 번
트랜잭션 커밋 → BATCH_STEP_EXECUTION 갱신
```

핵심은 세 가지입니다.

| 사실 | 의미 |
|---|---|
| `process()` 는 **아이템 하나당 한 번** 호출된다 | 7만 건이면 7만 번. 여기서 DB 를 한 번씩 조회하면 7만 번 조회한다 |
| 반환값이 `null` 이면 **write 로 내려가지 않는다** | 이것이 "필터링". 에러가 아니다 |
| `process()` 는 **청크 트랜잭션 안**에서 돈다 | 여기서 던진 예외는 청크 전체를 롤백시킨다 |

`ItemProcessor` 인터페이스는 메서드가 하나뿐입니다.

```java
@FunctionalInterface
public interface ItemProcessor<I, O> {
    @Nullable
    O process(@NonNull I item) throws Exception;
}
```

`@Nullable O` — **반환 타입에 `@Nullable` 이 붙어 있다는 것 자체가 "null 은 정상 흐름"이라는 선언**입니다. 이 한 글자가 이 스텝의 절반입니다.

---

## 7-2. Order → Settlement 변환과 BigDecimal

가장 단순한 프로세서부터 만듭니다. 수수료율을 일단 상수로 두고, 변환의 뼈대만 봅니다.

```java
package com.example.batch.step07;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FlatRateSettlementProcessor implements ItemProcessor<Order, Settlement> {

    private static final BigDecimal FLAT_RATE = new BigDecimal("0.0300");

    @Override
    public Settlement process(Order order) {
        BigDecimal gross = order.amount();
        BigDecimal fee   = gross.multiply(FLAT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net   = gross.subtract(fee);

        return new Settlement(
                order.order_id(),
                order.customerId(),
                order.orderedAt().toLocalDate(),
                gross,
                FLAT_RATE,
                fee,
                net
        );
    }
}
```

`setScale(2, RoundingMode.HALF_UP)` 이 반드시 필요합니다. 이유를 눈으로 봅니다.

```java
BigDecimal gross = new BigDecimal("1100.00");
BigDecimal rate  = new BigDecimal("0.0300");
System.out.println(gross.multiply(rate));                              // 스케일 그대로
System.out.println(gross.multiply(rate).setScale(2, RoundingMode.HALF_UP));
```

**결과**
```
33.000000
33.00
```

`multiply` 는 **두 피연산자의 스케일을 더합니다**(2 + 4 = 6). `DECIMAL(12,2)` 컬럼에 `33.000000` 을 넣으면 MySQL 이 알아서 반올림해 주긴 하지만, **자바 쪽 `net` 계산이 이미 6자리 스케일로 진행됩니다.** 7만 건을 더하면 그 잔여 자릿수가 합계에서 드러납니다.

> ⚠️ **함정 — 반올림 시점을 정하지 않으면 합계가 조용히 어긋납니다**
> `fee` 를 반올림하지 않고 `net = gross - fee` 를 구하면 `net` 도 6자리가 됩니다. 그 상태로 DB 에 넣으면 **DB 가 각 행을 2자리로 반올림**합니다.
> 그러면 `SUM(gross) - SUM(fee) != SUM(net)` 이 되는 행이 생깁니다. 한 건당 0.005원 차이가 7만 건 쌓이면 수백 원이 맞지 않습니다.
> **에러는 나지 않습니다.** 정산서 검증에서 "합이 1원 안 맞는데요"라는 문의로 돌아옵니다.
> 규칙: **fee 를 먼저 2자리로 확정하고, net 은 확정된 fee 로 뺀다.** 반올림은 한 번, 가장 이른 시점에.

`RoundingMode` 를 생략하면 어떻게 되는지도 확인합니다.

```java
new BigDecimal("1050.00").multiply(new BigDecimal("0.0250")).setScale(2);
```

**결과**
```
Exception in thread "main" java.lang.ArithmeticException: Rounding necessary
	at java.base/java.math.BigDecimal.divideAndRound(BigDecimal.java:4791)
	at java.base/java.math.BigDecimal.setScale(BigDecimal.java:3186)
```

`setScale(int)` 단독은 `RoundingMode.UNNECESSARY` 입니다. 버려질 자릿수가 있으면 예외를 던집니다. **항상 `RoundingMode` 를 명시하세요.**

---

## 7-3. 등급별 수수료율 — 7만 번의 조회를 1번으로

이제 진짜 요구사항입니다. 수수료율은 고객 등급마다 다릅니다.

| 등급 | fee_rate | COMPLETED 주문 |
|---|---|---|
| BRONZE | 0.0350 | 20,000 |
| SILVER | 0.0300 | 15,000 |
| GOLD | 0.0250 | 20,000 |
| VIP | 0.0200 | 15,000 |

가장 먼저 떠오르는 구현은 이렇습니다.

```java
// ❌ 하지 마세요
@Override
public Settlement process(Order order) {
    BigDecimal rate = jdbcTemplate.queryForObject(
            "SELECT fee_rate FROM customers WHERE customer_id = ?",
            BigDecimal.class, order.customerId());
    // ...
}
```

돌아가긴 합니다. 하지만 **`process()` 는 7만 번 호출되므로 SELECT 도 7만 번** 나갑니다.

**결과** (`spring.jpa`/JdbcTemplate 로그를 DEBUG 로 올리고 실행)
```
INFO 52310 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 52310 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
INFO 52310 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementStep] executed in 41s882ms
INFO 52310 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 42s103ms
```

`customers` 는 **1,000행짜리 마스터 테이블**입니다. 통째로 메모리에 올리면 됩니다.

```java
package com.example.batch.step07;

import com.example.batch.domain.Order;
import com.example.batch.domain.Settlement;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public class GradeFeeSettlementProcessor implements ItemProcessor<Order, Settlement> {

    /** customer_id → fee_rate. 생성자에서 한 번만 채우고, 이후 읽기 전용입니다. */
    private final Map<Integer, BigDecimal> feeRateByCustomer;

    public GradeFeeSettlementProcessor(JdbcTemplate jdbcTemplate) {
        Map<Integer, BigDecimal> map = new HashMap<>(1400);
        jdbcTemplate.query("SELECT customer_id, fee_rate FROM customers",
                rs -> { map.put(rs.getInt("customer_id"), rs.getBigDecimal("fee_rate")); });
        this.feeRateByCustomer = Map.copyOf(map);   // 불변화 — 7-10 의 무상태 원칙
    }

    @Override
    public Settlement process(Order order) {
        BigDecimal rate = feeRateByCustomer.get(order.customerId());
        if (rate == null) {
            throw new IllegalStateException("등급 정보 없는 고객: " + order.customerId());
        }
        BigDecimal gross = order.amount();
        BigDecimal fee   = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        return new Settlement(
                order.order_id(), order.customerId(), order.orderedAt().toLocalDate(),
                gross, rate, fee, gross.subtract(fee));
    }
}
```

**결과**
```
INFO 52488 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [settlementStep]
INFO 52488 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [settlementStep] executed in 5s217ms
INFO 52488 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=settlementJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 5s441ms
```

**41.9초 → 5.2초. 약 8배 빨라졌습니다.** 없어진 것은 7만 번의 네트워크 왕복뿐입니다.

계산이 맞는지 앞 5건으로 검증합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT s.order_id, c.grade, s.gross_amount, s.fee_rate, s.fee_amount, s.net_amount
FROM settlement s JOIN customers c ON c.customer_id = s.customer_id
ORDER BY s.order_id LIMIT 5;"
```

**결과**
```
+----------+--------+--------------+----------+------------+------------+
| order_id | grade  | gross_amount | fee_rate | fee_amount | net_amount |
+----------+--------+--------------+----------+------------+------------+
|        1 | SILVER |      1100.00 |   0.0300 |      33.00 |    1067.00 |
|        2 | GOLD   |      1200.00 |   0.0250 |      30.00 |    1170.00 |
|        3 | VIP    |      1300.00 |   0.0200 |      26.00 |    1274.00 |
|        4 | BRONZE |      1400.00 |   0.0350 |      49.00 |    1351.00 |
|        5 | SILVER |      1500.00 |   0.0300 |      45.00 |    1455.00 |
+----------+--------+--------------+----------+------------+------------+
```

전체 합계도 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) cnt, SUM(gross_amount) gross, SUM(fee_amount) fee, SUM(net_amount) net
FROM settlement;"
```

**결과**
```
+-------+---------------+--------------+---------------+
| cnt   | gross         | fee          | net           |
+-------+---------------+--------------+---------------+
| 70000 | 3485250000.00 |  97089107.00 | 3388160893.00 |
+-------+---------------+--------------+---------------+
```

`3485250000.00 - 97089107.00 = 3388160893.00`. **딱 맞습니다.** 7-2 의 반올림 규칙을 지켰기 때문입니다.

> 💡 **실무 팁 — 마스터 캐시는 "작고, 안 변하고, 자주 쓰이는 것"에만**
> `customers` 1,000행은 안전합니다. 하지만 마스터가 100만 행이면 힙이 터집니다.
> 판단 기준은 셋입니다: ① 행 수가 만 단위 이하인가 ② Job 실행 중에 바뀌지 않는가 ③ 아이템마다 조회되는가.
> 셋 다 예면 캐시하세요. ②가 아니면 캐시하면 **조용히 옛날 값으로 정산**합니다.
> 캐시가 부담스러우면 리더 쪽에서 `JOIN` 으로 fee_rate 를 함께 읽어 오는 것이 정석입니다(Step 06 의 `JdbcPagingItemReader`).

---

## 7-4. null 반환 = 필터링

이제 이 스텝의 핵심입니다. 리더를 **`WHERE status = 'COMPLETED'` 없이** 10만 건 전부 읽도록 바꾸고, 걸러내는 일을 프로세서에게 맡깁니다.

```java
public class CompletedOnlyProcessor implements ItemProcessor<Order, Order> {

    @Override
    public Order process(Order order) {
        if (!"COMPLETED".equals(order.status())) {
            return null;          // ← 이 아이템은 writer 로 내려가지 않습니다
        }
        return order;
    }
}
```

`return null` 이 전부입니다. 예외를 던지는 것도 아니고, 리스트에서 빼는 것도 아닙니다.

```java
@Bean
public Step filteringStep(JobRepository jobRepository, PlatformTransactionManager tx,
                          JdbcTemplate jdbcTemplate, DataSource dataSource) {
    return new StepBuilder("filteringStep", jobRepository)
            .<Order, Settlement>chunk(1000, tx)
            .reader(allOrdersReader(dataSource))          // 10만 건 전부
            .processor(new CompositeItemProcessor<>(      // 7-6 에서 설명
                    List.of(new CompletedOnlyProcessor(),
                            new GradeFeeSettlementProcessor(jdbcTemplate))))
            .writer(settlementWriter(dataSource))
            .build();
}
```

실행합니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=filteringJob'
```

**결과**
```
INFO 53102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=filteringJob]] launched with the following parameters: [{'run.id':'{value=1, type=class java.lang.Long, identifying=true}'}]
INFO 53102 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [filteringStep]
INFO 53102 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [filteringStep] executed in 7s034ms
INFO 53102 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=filteringJob]] completed with the following parameters: [...] and the following status: [COMPLETED] in 7s251ms
```

로그만 봐서는 아무것도 모릅니다. **메타데이터를 봐야 합니다.**

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, FILTER_COUNT,
       COMMIT_COUNT, ROLLBACK_COUNT, READ_SKIP_COUNT, PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+----------------+-----------+------------+-------------+--------------+--------------+----------------+-----------------+--------------------+------------------+
| STEP_NAME      | STATUS    | READ_COUNT | WRITE_COUNT | FILTER_COUNT | COMMIT_COUNT | ROLLBACK_COUNT | READ_SKIP_COUNT | PROCESS_SKIP_COUNT | WRITE_SKIP_COUNT |
+----------------+-----------+------------+-------------+--------------+--------------+----------------+-----------------+--------------------+------------------+
| filteringStep  | COMPLETED |     100000 |       70000 |        30000 |          101 |              0 |               0 |                  0 |                0 |
+----------------+-----------+------------+-------------+--------------+--------------+----------------+-----------------+--------------------+------------------+
```

**`readCount=100000, writeCount=70000, filterCount=30000, skipCount=0`.**

세 숫자의 관계가 명확합니다.

```
readCount = writeCount + filterCount + (스킵된 건수)
100000    = 70000      + 30000       + 0
```

`COMMIT_COUNT` 가 101 인 것도 짚고 갑니다. 10만 건 ÷ 1,000 = 100 청크이고, **마지막에 "더 읽을 게 없음"을 확인하는 빈 커밋이 1회** 더 붙습니다.

같은 값을 애플리케이션 코드에서 읽을 수도 있습니다.

```java
@Bean
public StepExecutionListener countLogger() {
    return new StepExecutionListener() {
        @Override
        public ExitStatus afterStep(StepExecution se) {
            log.info("readCount={}, writeCount={}, filterCount={}, skipCount={}",
                    se.getReadCount(), se.getWriteCount(),
                    se.getFilterCount(), se.getSkipCount());
            return se.getExitStatus();
        }
    };
}
```

**결과**
```
INFO 53102 --- [           main] c.e.batch.step07.CountLogger             : readCount=100000, writeCount=70000, filterCount=30000, skipCount=0
```

> ⚠️ **함정 — 필터링을 예외로 구현하면 통계가 거짓말을 합니다**
> "정산 대상이 아니면 예외를 던지고 `.faultTolerant().skip(...)` 으로 넘긴다"는 구현을 실무에서 자주 봅니다. 돌아가긴 합니다. 하지만:
> - `filterCount=0`, `processSkipCount=30000` 으로 기록됩니다. **"정상적으로 제외한 30,000건"과 "장애로 버린 30,000건"이 같은 칸에 들어갑니다.**
> - `skipLimit` 을 넘기면 Job 이 `FAILED` 로 끝납니다. 정상 데이터인데 실패합니다.
> - 예외 생성·스택트레이스 비용이 3만 번 발생합니다.
>
> **정상적으로 제외할 것은 `null` 로, 진짜 잘못된 데이터만 예외로.** 이 구분이 무너지면 모니터링 대시보드의 "스킵 건수" 알람이 영구히 무의미해집니다.

---

## 7-5. filterCount 와 skipCount 는 다르다

두 카운터를 한 표로 못 박아 둡니다.

| | `filterCount` | `processSkipCount` |
|---|---|---|
| 발생 조건 | `process()` 가 `null` 반환 | `process()` 가 예외를 던지고 `skip` 정책이 그것을 삼킴 |
| 의미 | **정상 제외** (비즈니스 규칙) | **비정상 무시** (장애 허용) |
| 필요 설정 | 없음 | `.faultTolerant().skip(X.class).skipLimit(n)` |
| 롤백 | 없음 | 청크 롤백 후 **아이템 단위 재처리** 발생 |
| 성능 | 무시할 수준 | 스킵 1건마다 청크 하나가 재실행됨 — 비쌈 |
| 알람 대상 | 아니오 | **예** |

`skipCount` 쪽을 일부러 만들어 비교합니다. `customer_id` 가 `customers` 에 없는 주문을 하나 만들어 두고, `GradeFeeSettlementProcessor` 의 `IllegalStateException` 을 스킵하게 합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "
INSERT INTO orders VALUES (999999, 4242, 5000.00, 'COMPLETED', '2025-03-01 10:00:00');"
```

```java
.faultTolerant()
    .skip(IllegalStateException.class)
    .skipLimit(10)
```

**결과**
```
WARN 53540 --- [           main] o.s.batch.core.step.item.ChunkMonitor    : No ItemReader set, so ignoring offset data.
INFO 53540 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [filteringStep] executed in 8s612ms
```

```
+----------------+-----------+------------+-------------+--------------+----------------+--------------------+
| STEP_NAME      | STATUS    | READ_COUNT | WRITE_COUNT | FILTER_COUNT | ROLLBACK_COUNT | PROCESS_SKIP_COUNT |
+----------------+-----------+------------+-------------+--------------+----------------+--------------------+
| filteringStep  | COMPLETED |     100001 |       70000 |        30000 |              1 |                  1 |
+----------------+-----------+------------+-------------+--------------+----------------+--------------------+
```

`ROLLBACK_COUNT=1` 을 보세요. **스킵 한 건 때문에 청크 하나가 통째로 롤백되고 다시 처리됐습니다.** 필터링은 롤백을 만들지 않습니다. 이것이 두 방식의 실질적 비용 차이입니다.

정리하고 갑니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -e "DELETE FROM orders WHERE order_id = 999999;"
```

---

## 7-6. CompositeItemProcessor — 변환 파이프라인

하나의 프로세서에 "상태 필터 + 등급 조회 + 계산 + 검증"을 다 넣으면 테스트가 불가능한 덩어리가 됩니다. `CompositeItemProcessor` 는 프로세서들을 **줄로 세워** 앞의 출력을 뒤의 입력으로 넘깁니다.

```
Order ──▶ [CompletedOnlyProcessor] ──▶ Order ──▶ [MinAmountFilter] ──▶ Order ──▶ [GradeFee...] ──▶ Settlement
                    │                                    │
                 null 이면 여기서 즉시 중단 ──────────────┘  뒤 프로세서는 호출되지 않는다
```

```java
@Bean
public ItemProcessor<Order, Settlement> settlementPipeline(JdbcTemplate jdbcTemplate) {
    CompositeItemProcessor<Order, Settlement> composite = new CompositeItemProcessor<>();
    composite.setDelegates(List.of(
            new CompletedOnlyProcessor(),                    // Order → Order
            new MinAmountFilterProcessor(new BigDecimal("1000")),  // Order → Order
            new GradeFeeSettlementProcessor(jdbcTemplate)    // Order → Settlement
    ));
    return composite;
}
```

Spring Batch 5.1 부터는 **생성자로도 넘길 수 있습니다.**

```java
return new CompositeItemProcessor<>(
        new CompletedOnlyProcessor(),
        new MinAmountFilterProcessor(new BigDecimal("1000")),
        new GradeFeeSettlementProcessor(jdbcTemplate));
```

**중간 델리게이트가 `null` 을 반환하면 나머지는 호출되지 않고 즉시 `null` 이 최종 결과가 됩니다.** 위 그림의 점선입니다. 그래서 **비싼 프로세서일수록 뒤에 두는 것이 이득**입니다. 30,000건을 첫 단계에서 걸러내면 등급 조회·계산은 70,000번만 돕니다.

순서를 뒤집어 보면 차이가 드러납니다.

| 순서 | `GradeFeeSettlementProcessor` 호출 횟수 | 실행 시간 |
|---|---|---|
| 필터 먼저 → 계산 나중 | 70,000 | 7s034ms |
| 계산 먼저 → 필터 나중 | 100,000 | 9s470ms |

계산이 무거울수록 이 차이는 커집니다. **필터는 앞으로.**

---

## 7-7. 제네릭 타입 불일치 — 컴파일은 되는데 런타임에 터진다

`CompositeItemProcessor<I, O>` 의 타입 파라미터는 **바깥 경계에만 적용**됩니다. 델리게이트들 사이의 타입이 이어지는지는 **아무도 검사하지 않습니다.** 시그니처를 보면 이유가 보입니다.

```java
public class CompositeItemProcessor<I, O> implements ItemProcessor<I, O>, InitializingBean {
    private List<? extends ItemProcessor<?, ?>> delegates;   // ← ?, ? 입니다
    public void setDelegates(List<? extends ItemProcessor<?, ?>> delegates) { ... }
}
```

`ItemProcessor<?, ?>` 입니다. **어떤 프로세서든 어떤 순서로든 넣을 수 있습니다.** 컴파일러는 침묵합니다.

순서를 잘못 넣어 봅시다. 계산 프로세서(`Order → Settlement`)를 먼저, 필터(`Order → Order`)를 뒤에.

```java
// ❌ 순서가 뒤집혔습니다. 그런데 컴파일은 됩니다.
CompositeItemProcessor<Order, Settlement> composite = new CompositeItemProcessor<>();
composite.setDelegates(List.of(
        new GradeFeeSettlementProcessor(jdbcTemplate),   // Order → Settlement
        new CompletedOnlyProcessor()                     // Order 를 기대하는데 Settlement 이 온다
));
```

```bash
./gradlew build
```

**결과**
```
BUILD SUCCESSFUL in 3s
5 actionable tasks: 2 executed, 3 up-to-date
```

**빌드가 성공합니다.** 실행합니다.

**결과**
```
INFO 54021 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [filteringStep]
ERROR 54021 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step filteringStep in job filteringJob

java.lang.ClassCastException: class com.example.batch.domain.Settlement cannot be cast to class com.example.batch.domain.Order (com.example.batch.domain.Settlement and com.example.batch.domain.Order are in unnamed module of loader 'app')
	at com.example.batch.step07.CompletedOnlyProcessor.process(CompletedOnlyProcessor.java:12) ~[main/:na]
	at org.springframework.batch.item.support.CompositeItemProcessor.process(CompositeItemProcessor.java:70) ~[spring-batch-infrastructure-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.doProcess(SimpleChunkProcessor.java:127) ~[spring-batch-core-5.1.1.jar:5.1.1]
	at org.springframework.batch.core.step.item.SimpleChunkProcessor.transform(SimpleChunkProcessor.java:301) ~[spring-batch-core-5.1.1.jar:5.1.1]
	...

INFO 54021 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=filteringJob]] completed with the following parameters: [...] and the following status: [FAILED] in 1s118ms
```

`CompositeItemProcessor.process` 안에서 무슨 일이 벌어지는지 보면 명확합니다.

```java
@SuppressWarnings("unchecked")
public O process(I item) throws Exception {
    Object result = item;
    for (ItemProcessor<?, ?> delegate : delegates) {
        if (result == null) return null;
        result = processItem((ItemProcessor<Object, Object>) delegate, result);
    }
    return (O) result;   // ← 여기와 델리게이트 안쪽에서 캐스팅이 일어난다
}
```

`(ItemProcessor<Object, Object>)` 로 캐스팅해서 돌립니다. **제네릭은 지워졌고, 실제 캐스팅은 델리게이트 메서드 진입 시점**에 일어납니다.

> ⚠️ **함정 — CompositeItemProcessor 의 타입 안전성은 컴파일 타임에 존재하지 않습니다**
> 이 함정이 특히 나쁜 이유는 **터지는 위치가 원인 위치와 다르다**는 점입니다. 스택트레이스가 가리키는 건 `CompletedOnlyProcessor.process()` 이지만, **틀린 것은 `setDelegates()` 의 순서**입니다. 처음 보면 `CompletedOnlyProcessor` 를 들여다보며 시간을 씁니다.
>
> 더 나쁜 경우도 있습니다. 두 타입이 **캐스팅 가능한 관계**(상속, 혹은 둘 다 `Map`)라면 **예외조차 안 납니다.** 엉뚱한 필드를 읽고 조용히 잘못된 값을 씁니다.
>
> **방어책 세 가지**
> 1. 델리게이트를 **인라인 익명 클래스가 아니라 명시적 타입의 지역 변수**로 선언하고, 다음처럼 손으로 타입 사슬을 적어 두기:
>    ```java
>    ItemProcessor<Order, Order>       step1 = new CompletedOnlyProcessor();
>    ItemProcessor<Order, Order>       step2 = new MinAmountFilterProcessor(min);
>    ItemProcessor<Order, Settlement>  step3 = new GradeFeeSettlementProcessor(jdbc);
>    // Order → Order → Order → Settlement : 눈으로 검증 가능
>    ```
> 2. `Function.andThen()` 스타일로 직접 합성하면 **컴파일러가 검사해 줍니다.**
>    ```java
>    ItemProcessor<Order, Settlement> safe = order -> {
>        Order a = step1.process(order);
>        if (a == null) return null;
>        Order b = step2.process(a);
>        if (b == null) return null;
>        return step3.process(b);
>    };
>    ```
>    `null` 전파를 손으로 써야 하지만, **순서를 틀리면 컴파일이 안 됩니다.** 파이프라인이 3~4단계로 고정되어 있다면 이쪽을 권합니다.
> 3. 파이프라인에 대한 **단위 테스트를 아이템 1건으로** 반드시 작성. `composite.process(sampleOrder)` 한 줄이면 잡힙니다. 7만 건짜리 Job 을 돌려서 알아낼 일이 아닙니다.

---

## 7-8. ClassifierCompositeItemProcessor — 분기

`CompositeItemProcessor` 가 직렬이라면, `ClassifierCompositeItemProcessor` 는 **병렬 분기**입니다. 아이템을 보고 **하나의 프로세서를 골라** 실행합니다.

```
             ┌── VIP  ──▶ [VipSettlementProcessor]   (추가 할인 0.0050 적용)
Order ──▶ 분류 ├── 그 외 ──▶ [GradeFeeSettlementProcessor]
             └── 취소/보류 ──▶ [(null 반환 프로세서)]
```

```java
@Bean
public ItemProcessor<Order, Settlement> classifyingProcessor(JdbcTemplate jdbcTemplate) {

    ItemProcessor<Order, Settlement> standard = new GradeFeeSettlementProcessor(jdbcTemplate);
    ItemProcessor<Order, Settlement> vip      = new VipSettlementProcessor(jdbcTemplate);
    ItemProcessor<Order, Settlement> drop     = order -> null;   // 정산 대상 아님

    Map<Integer, BigDecimal> rates = loadFeeRates(jdbcTemplate);

    ClassifierCompositeItemProcessor<Order, Settlement> processor =
            new ClassifierCompositeItemProcessor<>();

    processor.setClassifier((Classifier<Order, ItemProcessor<?, ? extends Settlement>>) order -> {
        if (!"COMPLETED".equals(order.status())) return drop;
        BigDecimal rate = rates.get(order.customerId());
        return new BigDecimal("0.0200").compareTo(rate) == 0 ? vip : standard;
    });

    return processor;
}
```

`Classifier<C, T>` 는 함수형 인터페이스입니다.

```java
@FunctionalInterface
public interface Classifier<C, T> extends Serializable {
    T classify(C classifiable);
}
```

실행 결과를 보면 VIP 만 수수료율이 다릅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT c.grade, s.fee_rate, COUNT(*) cnt
FROM settlement s JOIN customers c ON c.customer_id = s.customer_id
GROUP BY c.grade, s.fee_rate ORDER BY s.fee_rate DESC;"
```

**결과**
```
+--------+----------+-------+
| grade  | fee_rate | cnt   |
+--------+----------+-------+
| BRONZE |   0.0350 | 20000 |
| SILVER |   0.0300 | 15000 |
| GOLD   |   0.0250 | 20000 |
| VIP    |   0.0150 | 15000 |
+--------+----------+-------+
```

VIP 만 `0.0200 → 0.0150` 으로 내려갔습니다.

> 💡 **실무 팁 — 분류자는 아이템만 보고 결정해야 합니다**
> `classify()` 안에서 DB 를 조회하거나 외부 API 를 호출하면, 7-3 에서 없앤 "아이템당 왕복"이 다시 살아납니다.
> 분류에 필요한 정보는 **리더가 이미 실어 온 필드**이거나 **미리 캐시된 맵**이어야 합니다.
> `SubclassClassifier` 를 쓰면 "아이템의 클래스 타입"으로 분기하는 흔한 경우를 한 줄로 처리할 수 있습니다:
> `new SubclassClassifier<>(Map.of(DomesticOrder.class, p1, OverseasOrder.class, p2), defaultP)`

---

## 7-9. ValidatingItemProcessor / BeanValidatingItemProcessor

검증을 파이프라인의 한 칸으로 넣는 방법입니다. 둘 다 **통과하면 입력을 그대로 반환**하는 `ItemProcessor<T, T>` 입니다.

### ValidatingItemProcessor — 직접 쓴 규칙

```java
ValidatingItemProcessor<Order> validator = new ValidatingItemProcessor<>(order -> {
    if (order.amount() == null || order.amount().signum() <= 0) {
        throw new ValidationException("금액이 0 이하: order_id=" + order.order_id());
    }
    if (order.orderedAt() == null) {
        throw new ValidationException("주문일시 없음: order_id=" + order.order_id());
    }
});
validator.setFilter(false);   // 기본값. 위반 시 ValidationException 을 던진다
```

`setFilter(true)` 로 바꾸면 **예외 대신 `null` 을 반환**합니다. 즉 같은 규칙 위반이 `skipCount` 가 아니라 `filterCount` 로 잡힙니다.

| `setFilter` | 위반 시 동작 | 통계 |
|---|---|---|
| `false` (기본) | `ValidationException` 을 던짐 | `processSkipCount` (스킵 설정 시) 또는 Job `FAILED` |
| `true` | `null` 반환 | `filterCount` |

**"이건 버그다 → false", "이건 대상이 아니다 → true".** 7-4 의 원칙 그대로입니다.

### BeanValidatingItemProcessor — Jakarta Bean Validation

`build.gradle` 에 검증 스타터가 필요합니다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-validation'
```

`record` 컴포넌트에도 애너테이션을 붙일 수 있습니다.

```java
public record Settlement(
        @NotNull Long orderId,
        @NotNull Integer customerId,
        @NotNull LocalDate settleDate,
        @NotNull @DecimalMin("0.00") BigDecimal grossAmount,
        @NotNull @DecimalMin("0.0000") @DecimalMax("0.1000") BigDecimal feeRate,
        @NotNull @DecimalMin("0.00") BigDecimal feeAmount,
        @NotNull @DecimalMin("0.00") BigDecimal netAmount
) {}
```

```java
@Bean
public BeanValidatingItemProcessor<Settlement> beanValidator() throws Exception {
    BeanValidatingItemProcessor<Settlement> p = new BeanValidatingItemProcessor<>();
    p.setFilter(false);
    p.afterPropertiesSet();     // ← 잊지 마세요
    return p;
}
```

수수료율에 잘못된 값이 들어가면 이렇게 실패합니다.

**결과**
```
ERROR 54880 --- [           main] o.s.batch.core.step.AbstractStep         : Encountered an error executing step validatingStep in job settlementJob

org.springframework.batch.item.validator.ValidationException: Validation failed for Settlement[orderId=48213, customerId=213, settleDate=2025-03-14, grossAmount=8200.00, feeRate=0.9000, feeAmount=7380.00, netAmount=820.00]:
Field error in object 'item' on field 'feeRate': rejected value [0.9000]; codes [DecimalMax.item.feeRate,...]; default message [0.1000 이하여야 합니다]
	at org.springframework.batch.item.validator.SpringValidator.validate(SpringValidator.java:54)
	at org.springframework.batch.item.validator.ValidatingItemProcessor.process(ValidatingItemProcessor.java:83)
```

> ⚠️ **함정 — `@Bean` 이 아니면 `afterPropertiesSet()` 이 안 불립니다**
> `BeanValidatingItemProcessor` 는 `InitializingBean` 입니다. 내부 `Validator` 를 `afterPropertiesSet()` 에서 만듭니다.
> `new BeanValidatingItemProcessor<>()` 로 만들고 그냥 파이프라인에 끼우면 **검증기가 `null` 인 채로** 돌다가
> `NullPointerException` 이 나거나, 최악의 경우 **아무것도 검증하지 않고 전부 통과**합니다.
> 다음 스텝(Step 08)의 `ItemStream` 함정과 **뿌리가 같은 문제**입니다: 스프링이 만들지 않은 객체는 스프링이 초기화해 주지 않습니다.

---

## 7-10. ItemProcessor 는 상태를 갖지 말아야 한다

프로세서에 필드를 하나 두고 세어 보는 코드는 아주 자연스러워 보입니다.

```java
// ❌ 상태를 가진 프로세서
public class CountingProcessor implements ItemProcessor<Order, Settlement> {
    private int processed = 0;              // ← 상태
    private BigDecimal runningTotal = BigDecimal.ZERO;   // ← 상태

    @Override
    public Settlement process(Order order) {
        processed++;
        runningTotal = runningTotal.add(order.amount());
        // ...
    }
}
```

단일 스레드에서는 잘 돕니다. 문제는 두 곳에서 터집니다.

**(1) 재시작하면 카운터가 0 부터 시작합니다.** 7만 건 중 5만 건까지 처리하고 죽은 뒤 재시작하면, 리더는 5만 건째부터 이어가지만 `processed` 는 0 입니다. 최종 리포트가 20,000 이라고 찍힙니다. **에러 없이 틀립니다.**

**(2) 멀티스레드에서 값이 어긋납니다.** Step 13 의 `.taskExecutor(...)` 를 붙이는 순간, 같은 프로세서 인스턴스를 여러 스레드가 동시에 부릅니다.

```java
.<Order, Settlement>chunk(1000, tx)
.reader(syncReader)
.processor(new CountingProcessor())     // 인스턴스 하나를 4스레드가 공유
.writer(writer)
.taskExecutor(new SimpleAsyncTaskExecutor("batch-"))
```

**결과**
```
INFO 55310 --- [           main] c.e.batch.step07.CountLogger             : StepExecution: readCount=70000, writeCount=70000, filterCount=0
INFO 55310 --- [           main] c.e.batch.step07.CountLogger             : CountingProcessor.processed=68847     ← 70000 이 아님
INFO 55310 --- [           main] c.e.batch.step07.CountLogger             : CountingProcessor.runningTotal=3421880400.00   ← 3485250000.00 이 아님
```

`processed++` 는 원자적이지 않고, `runningTotal = runningTotal.add(...)` 는 읽고-더하고-쓰는 세 동작이라 **덮어쓰기가 일어납니다.** `BigDecimal` 이 불변이어도 **참조를 바꾸는 것은 여전히 경쟁 상태**입니다.

> ⚠️ **함정 — 상태를 가진 프로세서는 "지금은" 잘 돕니다**
> 이 코드는 오늘 단일 스레드에서 정확한 값을 내놓습니다. 6개월 뒤 누군가 성능 개선을 위해 `.taskExecutor(...)` 한 줄을 추가하는 순간 **틀린 숫자를 조용히 내놓기 시작합니다.** 그 사람은 프로세서 파일을 열어 보지도 않았습니다.
> `AtomicInteger` 로 바꾸면 (2)는 해결되지만 **(1) 재시작 문제는 그대로**입니다.

**올바른 방법 세 가지**

| 하고 싶은 일 | 올바른 도구 | 스텝 |
|---|---|---|
| 처리 건수를 알고 싶다 | `StepExecution.getWriteCount()` — 프레임워크가 이미 셉니다 | 7-4 |
| 누적 합계를 재시작에도 이어가고 싶다 | `ExecutionContext` 에 저장 (`@BeforeStep` 으로 주입받아 갱신) | [Step 09](../step-09-execution-context/) |
| Step 이 끝난 뒤 집계 리포트를 쓰고 싶다 | `StepExecutionListener.afterStep()` 또는 별도 Tasklet Step | [Step 12](../step-12-listeners/) |

프로세서가 가져도 되는 필드는 **생성 시점에 정해지고 절대 안 바뀌는 것**뿐입니다. 7-3 의 `Map.copyOf(map)` 가 바로 그 예입니다 — 불변 맵이라 여러 스레드가 동시에 읽어도 안전합니다.

> 💡 **실무 팁 — 프로세서 클래스의 필드는 전부 `final` 로**
> 규칙을 하나로 압축하면 이렇습니다: **`ItemProcessor` 구현체의 모든 인스턴스 필드에 `final` 을 붙이고, 그 안에 담기는 컬렉션도 불변으로 만든다.**
> 이러면 (2)는 컴파일 단계에서 예방되고, (1)은 애초에 시도조차 못 하게 됩니다. `final` 을 못 붙이겠다는 필드가 생기면 그게 곧 설계가 잘못됐다는 신호입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `ItemProcessor<I, O>` | 아이템 **하나당 한 번** 호출. 청크 트랜잭션 안에서 동작 |
| `null` 반환 | **필터링**. 에러가 아니며 writer 로 내려가지 않음 |
| `filterCount` | `null` 반환 횟수. `readCount = writeCount + filterCount + skipCount` |
| `filterCount` vs `skipCount` | 정상 제외 vs 비정상 무시. 스킵은 **청크 롤백**을 유발 |
| BigDecimal | `multiply` 는 스케일이 더해짐. **fee 를 먼저 확정하고 net 을 뺀다** |
| `setScale(2)` 단독 | `RoundingMode.UNNECESSARY` → `ArithmeticException`. 항상 모드 명시 |
| 마스터 캐시 | 작고·안 변하고·아이템마다 쓰이면 캐시. 7만 회 조회 → 1회 (**약 8배**) |
| `CompositeItemProcessor` | 직렬 파이프라인. 중간에 `null` 이면 **이후 델리게이트는 호출 안 됨** |
| 델리게이트 순서 | **필터를 앞으로.** 비싼 변환은 뒤로 |
| 타입 안전성 | `List<? extends ItemProcessor<?, ?>>` — **컴파일러가 검사하지 않음** → 런타임 `ClassCastException` |
| `ClassifierCompositeItemProcessor` | 아이템별 분기. `classify()` 안에서 DB 조회 금지 |
| `ValidatingItemProcessor` | `setFilter(false)`=예외, `setFilter(true)`=`null` |
| `BeanValidatingItemProcessor` | `@Bean` 아니면 `afterPropertiesSet()` 직접 호출 필수 |
| 무상태 원칙 | 인스턴스 필드는 전부 `final` + 불변. 카운터는 `StepExecution`/`ExecutionContext` 로 |

---

## 연습문제

`Exercise.java` 에 6문제가 있습니다. 정답은 `Solution.java`.

1. `Order → Settlement` 변환 프로세서를 완성하고, 반올림 규칙(fee 먼저 확정 → net 은 뺄셈)을 지켜 `SUM(gross) - SUM(fee) = SUM(net)` 을 만족시키기
2. `status != 'COMPLETED'` 를 `null` 로 필터링하고, 실행 후 `filterCount` 가 정확히 30000 임을 확인하기
3. 같은 요구사항을 예외 방식으로 바꿨을 때 `filterCount` / `processSkipCount` / `rollbackCount` 가 어떻게 달라지는지 예측해서 적기
4. 3단계 `CompositeItemProcessor` 를 만들되, **델리게이트 순서를 일부러 틀리게 넣고** 어떤 예외가 어느 클래스에서 터지는지 예측하기. 그다음 컴파일 타임에 잡히도록 고치기
5. `ClassifierCompositeItemProcessor` 로 "VIP 는 추가 할인, 취소/보류는 `null`, 나머지는 표준" 분기 구현하기
6. 상태를 가진 프로세서를 무상태로 리팩터링하고, 누적 합계를 어디에 두어야 재시작에도 살아남는지 답하기

---

## 다음 단계

프로세서까지 통과한 아이템은 이제 `Chunk` 에 담겨 writer 로 내려갑니다.
Spring Batch 5 에서 `ItemWriter` 의 시그니처가 `List<T>` 에서 `Chunk<? extends T>` 로 바뀌었고, `record` 를 쓰는 순간 `JdbcBatchItemWriter.beanMapped()` 가 조용히 실패합니다.
그리고 JDBC URL 의 옵션 하나로 7만 건 쓰기 성능이 8배 달라지는 것을 실측합니다.

→ [Step 08 — ItemWriter](../step-08-item-writer/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 로 7-1 ~ 7-10 의 모든 예제를 눈으로 훑고, `Exercise.java` 의 6문제를 직접 채운 뒤, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step07` 패키지이며, 예제 클래스들은 **하나의 파일 안에 `static class` 로 중첩**되어 있습니다.

### Practice.java

본문의 모든 프로세서 구현과 Step 설정을 절 번호 주석(`// [7-3]`)과 함께 한 파일에 모아 둔 참조 코드입니다.

- `Practice` 클래스 자체가 `@Configuration` 이며, `filteringJob` / `classifyingJob` 두 개의 Job 빈을 정의합니다. 어느 것을 돌릴지는 `--spring.batch.job.name=` 으로 고릅니다.
- `[7-2]` 의 `FlatRateSettlementProcessor` 와 `[7-3]` 의 `GradeFeeSettlementProcessor` 는 **의도적으로 둘 다 남겨 두었습니다.** 전자는 반올림 규칙만 보여 주는 최소 형태이고, 실제 Job 이 쓰는 것은 후자입니다.
- `[7-3]` 의 `SlowLookupProcessor` 는 **일부러 느린 코드**입니다(아이템마다 `queryForObject`). 41.9초 → 5.2초 비교를 재현하려면 이 클래스를 파이프라인에 잠깐 끼워 넣었다가 빼세요. 7만 번 왕복이라 실행에 40초 이상 걸립니다.
- `[7-7]` 의 `brokenPipeline()` 메서드는 **컴파일은 되지만 실행하면 `ClassCastException` 이 나는 코드**입니다. Job 빈으로 등록되어 있지 않으므로 그냥 빌드해도 안전하고, 재현하려면 `settlementPipeline()` 자리에 손으로 바꿔 끼워야 합니다.
- `[7-10]` 의 `CountingProcessor` 역시 **잘못된 예시**입니다. 단일 스레드로 돌리면 정확한 값이 나오므로, 함정을 재현하려면 주석에 적힌 대로 `.taskExecutor(new SimpleAsyncTaskExecutor("batch-"))` 를 붙이고 여러 번 돌려 보세요. **매번 다른 값이 나오는 것**이 관찰 포인트입니다.

```java file="./Practice.java"
```

### Exercise.java

6문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 1·2·5·6** 은 코드를 채우는 문제, **문제 3·4** 는 **실행 결과를 먼저 예측해서 주석으로 적은 뒤** 실제로 돌려 대조하는 문제입니다. 3·4 는 답을 보기 전에 예측을 반드시 적어 두세요. 예측이 틀린 지점이 곧 이해가 빈 지점입니다.
- 문제 2 는 리더가 `WHERE status = 'COMPLETED'` **없이** 10만 건을 읽도록 이미 설정되어 있습니다. 이 조건을 리더에 되돌려 놓으면 `filterCount` 가 0 이 되어 문제가 성립하지 않습니다.
- 문제 4 의 `Q4_BrokenComposite` 는 델리게이트 순서가 틀린 채로 주어집니다. **이 파일을 그대로 실행하면 Job 이 `FAILED` 로 끝나는 것이 정상입니다.**
- 문제 6 의 `Q6_Stateful` 은 실행 자체는 성공합니다. 틀린 값이 나오는 것을 확인해야 하는 문제이므로, **Job 이 `COMPLETED` 로 끝났다고 통과한 게 아닙니다.**

```java file="./Exercise.java"
```

### Solution.java

6문제의 정답 코드와, "왜 그 답인가"를 설명하는 긴 주석입니다. 풀어 본 **뒤에** 여세요.

- **정답 1** 의 핵심은 `fee` 를 `setScale(2, HALF_UP)` 으로 **먼저 확정**한 다음 `net = gross.subtract(fee)` 를 계산하는 순서입니다. 순서를 뒤집으면(`net` 을 먼저 6자리로 만들고 나중에 반올림) 합계가 어긋나는 이유를 숫자로 보여 줍니다.
- **정답 3** 은 예외 방식의 결과를 `filterCount=0, processSkipCount=30000, rollbackCount≈30000` 으로 답합니다. 롤백 횟수가 스킵 건수와 비슷해지는 이유 — **스킵 한 건마다 청크 하나가 롤백되고 아이템 단위로 재처리된다**는 메커니즘을 설명합니다.
- **정답 4** 는 두 부분입니다. (a) `ClassCastException` 이 `CompletedOnlyProcessor.process()` 에서 터지지만 **원인은 `setDelegates()` 호출부**라는 것, (b) 람다로 직접 합성해서 컴파일러가 순서를 검사하게 만드는 리팩터링. (b) 코드에서 **델리게이트 순서를 바꾸면 실제로 컴파일 에러가 난다**는 것을 주석의 에러 메시지로 확인할 수 있습니다.
- **정답 6** 은 "`AtomicLong` 으로 바꾸면 되나요?"라는 흔한 오답을 먼저 다룹니다. 멀티스레드는 해결되지만 **재시작 시 0 부터 다시 세는 문제는 그대로**이므로 정답이 아닙니다. `ExecutionContext` 에 누적값을 넣어야 하는 이유가 여기 있고, 그 방법은 Step 09 에서 이어집니다.

```java file="./Solution.java"
```
