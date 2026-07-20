# Step 06 — ItemReader

> **학습 목표**
> - `ItemReader` / `ItemStream` / `ItemStreamReader` 의 계층과 각각의 책임을 구분한다
> - `JdbcCursorItemReader` 와 `JdbcPagingItemReader` 의 차이를 커넥션·메모리·멀티스레드 관점에서 판단한다
> - `DataClassRowMapper` 로 `record` 를 매핑하고, `BeanPropertyRowMapper` 가 안 되는 이유를 설명한다
> - **페이징 리더의 `sortKeys` 가 유니크하지 않으면 70,000건 중 67,207건만 읽고도 `COMPLETED` 로 끝나는 것을 재현한다**
> - `OFFSET` 페이징의 위험을 알고 키셋(seek) 방식과 비교한다
> - `JpaPagingItemReader` 의 영속성 컨텍스트 문제와 `FlatFileItemReader` 의 구성요소를 다룬다
>
> **선행 스텝**: Step 05 — 청크 지향 처리
> **예상 소요**: 110분

---

## 6-0. 실습 준비

[Step 05](../step-05-chunk/) 의 흔적을 지웁니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;
SET FOREIGN_KEY_CHECKS = 1;
TRUNCATE TABLE settlement;
SQL
```

이 스텝의 실습에서 계속 쓸 `orders` 의 성질을 미리 확인해 둡니다. **6-6 의 함정이 여기에 통째로 기대므로 반드시 실행하세요.**

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) AS completed,
       COUNT(DISTINCT order_id)   AS distinct_order_id,
       COUNT(DISTINCT ordered_at) AS distinct_ordered_at,
       ROUND(COUNT(*) / COUNT(DISTINCT ordered_at), 2) AS rows_per_timestamp
FROM orders WHERE status = 'COMPLETED';"
```

**결과**
```
+-----------+-------------------+---------------------+--------------------+
| completed | distinct_order_id | distinct_ordered_at | rows_per_timestamp |
+-----------+-------------------+---------------------+--------------------+
|     70000 |             70000 |                1008 |              69.44 |
+-----------+-------------------+---------------------+--------------------+
```

**`order_id` 는 70,000개 전부 다르고, `ordered_at` 은 1,008개뿐입니다.** 같은 시각을 가진 주문이 평균 69.44건씩 있습니다.

이건 시드 데이터의 성질입니다. `ordered_at` 은 `(n-1) % 180` 일과 `n % 1440` 분으로 만들어지는데, `1440` 이 `180` 의 배수라서 **`n % 1440` 하나가 시각 전체를 결정**합니다. 결과적으로 서로 다른 시각은 1,440가지뿐이고, 그중 `COMPLETED` 인 조합이 1,008가지입니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT ordered_at, COUNT(*) cnt FROM orders WHERE status = 'COMPLETED'
GROUP BY ordered_at ORDER BY ordered_at LIMIT 5;"
```

**결과**
```
+---------------------+-----+
| ordered_at          | cnt |
+---------------------+-----+
| 2025-01-01 00:01:00 |  70 |
| 2025-01-01 00:11:00 |  70 |
| 2025-01-01 00:21:00 |  70 |
| 2025-01-01 00:31:00 |  69 |
| 2025-01-01 00:41:00 |  70 |
+---------------------+-----+
```

**한 시각에 69~70건씩 뭉쳐 있습니다.** 이 숫자를 기억하세요. 6-6 에서 정확히 이 뭉치가 데이터를 삼킵니다.

---

## 6-1. ItemReader 계층

`ItemReader<T>` 자체는 메서드가 하나뿐인 아주 작은 인터페이스입니다.

```java
package org.springframework.batch.item;

@FunctionalInterface
public interface ItemReader<T> {
    /** 다음 아이템을 반환. 더 이상 없으면 null. */
    T read() throws Exception, UnexpectedInputException, ParseException, NonTransientResourceException;
}
```

계약은 딱 하나입니다. **`null` 을 반환하면 데이터의 끝**입니다. [Step 05](../step-05-chunk/) 에서 본 "마지막 빈 청크"가 여기서 나옵니다.

그런데 이것만으로는 재시작을 못 합니다. "어디까지 읽었는지"를 저장할 방법이 없기 때문입니다. 그래서 `ItemStream` 이 붙습니다.

```
                    ┌──────────────────────────────┐
                    │        ItemReader<T>          │
                    │        T read()               │   ← 아이템 하나를 반환. null = 끝
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────┴───────────────┐
                    │      ItemStreamReader<T>      │   ← ItemReader + ItemStream
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────┴───────────────────────────┐
                    │  AbstractItemCountingItemStreamItemReader │
                    │  - 읽은 개수를 세고 ExecutionContext 에 저장 │   ← 재시작의 근간
                    │  - update(ctx) / open(ctx) 구현            │
                    └──────────────┬───────────────────────────┘
                                   │
         ┌─────────────────┬───────┴────────┬──────────────────┐
         ▼                 ▼                ▼                  ▼
 JdbcCursorItemReader  JdbcPagingItemReader  JpaPagingItemReader  FlatFileItemReader
   (커서 1개 유지)       (페이지마다 재쿼리)    (JPA + 페이징)       (파일 한 줄씩)


  ItemStream (별도 인터페이스)
  ├── void open(ExecutionContext ctx)    — Step 시작 시 1회. 재시작이면 저장된 위치 복원
  ├── void update(ExecutionContext ctx)  — 청크 커밋마다 호출. 현재 위치 저장
  └── void close()                       — Step 종료 시 1회. 리소스 반납
```

여기서 중요한 건 **`update()` 가 청크 커밋마다 호출된다**는 점입니다. 청크와 같은 트랜잭션 안에서 `BATCH_STEP_EXECUTION_CONTEXT` 에 저장되므로, "커밋된 데이터"와 "저장된 읽기 위치"가 항상 일치합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT SHORT_CONTEXT FROM BATCH_STEP_EXECUTION_CONTEXT ORDER BY STEP_EXECUTION_ID DESC LIMIT 1\G"
```

**결과**
```
*************************** 1. row ***************************
SHORT_CONTEXT: {"@class":"java.util.HashMap","orderReader.read.count":70000}
```

`orderReader.read.count` 가 리더가 저장해 둔 위치입니다. 이름 앞의 `orderReader` 는 `.name(...)` 으로 준 값입니다.

> ⚠️ **함정 — 리더에 `.name()` 을 안 주면 재시작이 조용히 망가집니다**
> `AbstractItemCountingItemStreamItemReader` 는 `ExecutionContext` 키의 접두사로 `name` 을 씁니다.
> `.name()` 을 생략하면 `setName()` 이 호출되지 않아 `ItemStreamException: ItemStream must have a name` 로 실패합니다 — 이건 시끄러운 실패라 괜찮습니다.
> **진짜 문제는 리더 두 개에 같은 이름을 준 경우**입니다. 두 리더가 `ExecutionContext` 의 **같은 키를 서로 덮어씁니다.**
> 예외는 나지 않습니다. 그냥 재시작할 때 A 리더가 B 리더의 위치에서 시작합니다. 복합 Step 이나 `CompositeItemReader` 를 쓸 때 실제로 벌어집니다.
> 리더 이름은 **Job 전체에서 유일**해야 합니다. 빈 이름을 그대로 쓰는 습관이 가장 안전합니다.

---

## 6-2. JdbcCursorItemReader — 커서 하나로 끝까지

커서 리더는 **쿼리를 딱 한 번 실행**하고, `ResultSet` 을 열어 둔 채 `read()` 마다 `rs.next()` 를 호출합니다.

```java
@Bean
public JdbcCursorItemReader<Order> cursorReader(DataSource dataSource) {
    return new JdbcCursorItemReaderBuilder<Order>()
            .name("cursorReader")
            .dataSource(dataSource)
            .sql("""
                 SELECT order_id, customer_id, amount, status, ordered_at
                 FROM orders
                 WHERE status = ?
                 ORDER BY order_id
                 """)
            .queryArguments("COMPLETED")
            .fetchSize(1000)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

실행하면 SQL 은 **단 한 번** 나갑니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=cursorJob' 2>&1 | grep -c 'Executing prepared SQL statement'
```

**결과**
```
1
```

7만 건을 읽는 동안 쿼리는 하나입니다. 페이징 리더와 비교하면 이 차이가 큽니다.

### 커넥션을 붙잡는다

커서 리더의 결정적 성질은 **`ResultSet` 이 열려 있는 동안 DB 커넥션을 계속 점유**한다는 것입니다. Step 이 6초 걸리면 6초 내내, 6시간 걸리면 6시간 내내 커넥션 하나가 묶여 있습니다.

```bash
# 배치가 도는 중에 다른 터미널에서
mysql -h127.0.0.1 -P3308 -uroot -proot1234 -t -e "
SELECT ID, USER, TIME, STATE, LEFT(INFO, 50) AS query
FROM information_schema.PROCESSLIST WHERE USER = 'batch';"
```

**결과**
```
+-----+-------+------+-----------+----------------------------------------------------+
| ID  | USER  | TIME | STATE     | query                                              |
+-----+-------+------+-----------+----------------------------------------------------+
|  31 | batch |    4 | Sending data | SELECT order_id, customer_id, amount, status, or |
|  32 | batch |    0 |           | NULL                                               |
|  33 | batch |    0 |           | NULL                                               |
+-----+-------+------+-----------+----------------------------------------------------+
```

31번 커넥션이 `Sending data` 상태로 4초째 붙잡혀 있습니다. 32·33번은 writer 와 메타데이터용입니다.

> ⚠️ **함정 — MySQL 에서 `fetchSize` 는 기본적으로 무시됩니다**
> `.fetchSize(1000)` 을 줬으니 1,000행씩 가져올 것 같지만, **MySQL Connector/J 는 기본 설정에서 결과셋 전체를 클라이언트 메모리로 한 번에 끌어옵니다.** `fetchSize` 를 그냥 무시합니다.
> 7만 건이면 그럭저럭 버티지만 **700만 건이면 리더가 첫 `read()` 를 반환하기도 전에 `OutOfMemoryError`** 입니다. 청크 크기를 아무리 줄여도 소용없습니다. 청크는 커서 다음 단계의 이야기이기 때문입니다.
> 진짜로 스트리밍하려면 둘 중 하나가 필요합니다.
>
> | 방법 | 설정 | 특징 |
> |---|---|---|
> | 서버 사이드 커서 | JDBC URL 에 `useCursorFetch=true` + `fetchSize` 양수 | 서버가 임시 테이블을 만듦. 안정적이지만 서버 부하 |
> | 행 단위 스트리밍 | `fetchSize(Integer.MIN_VALUE)` | 진짜 한 행씩. **단, 그 커넥션으로 다른 쿼리를 못 날림** |
>
> `Integer.MIN_VALUE` 방식은 스트리밍이 끝날 때까지 같은 커넥션에서 다른 SQL 을 실행할 수 없습니다. writer 가 **같은 커넥션**을 쓰면 `Streaming result set is still active` 로 죽습니다.
> 이 스텝의 실습은 7만 건이라 기본 설정으로도 돌아가지만, **운영 데이터 규모에서 커서 리더를 쓸 거라면 이 설정을 반드시 정하고 들어가세요.**

### 재시작이 느리다

커서 리더는 재시작할 때 **쿼리를 처음부터 다시 실행하고, 저장된 개수만큼 `read()` 를 호출해서 버립니다.** `AbstractItemCountingItemStreamItemReader.open()` 의 `jumpToItem()` 이 그 일을 합니다.

```
INFO --- o.s.b.i.d.AbstractCursorItemReader : Executing prepared SQL statement [SELECT order_id, ... ORDER BY order_id]
DEBUG --- o.s.b.i.s.AbstractItemCountingItemStreamItemReader : Jumping to item 25000
```

25,000건을 건너뛰려고 25,000번 `read()` + `rowMapper` 를 돌립니다. 100만 건째에서 실패했다면 재시작에 100만 번의 매핑이 낭비됩니다.

`JdbcCursorItemReader` 는 이 점을 개선한 `setDriverSupportsAbsolute(true)` 옵션이 있습니다. `ResultSet.absolute(n)` 으로 한 번에 점프하는 방식이며, MySQL Connector/J 는 이를 지원합니다. 다만 커서를 클라이언트 메모리에 다 올려 둔 상태에서만 의미가 있습니다.

---

## 6-3. JdbcPagingItemReader — 페이지마다 새 쿼리

페이징 리더는 반대입니다. **한 페이지를 다 읽으면 커넥션을 반납하고**, 다음 페이지가 필요할 때 쿼리를 새로 날립니다.

```java
@Bean
public JdbcPagingItemReader<Order> pagingReader(DataSource dataSource) {

    MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
    provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
    provider.setFromClause("FROM orders");
    provider.setWhereClause("status = :status");
    provider.setSortKeys(Map.of("order_id", Order.ASCENDING));   // ← 유니크 키!

    return new JdbcPagingItemReaderBuilder<Order>()
            .name("pagingReader")
            .dataSource(dataSource)
            .queryProvider(provider)
            .parameterValues(Map.of("status", "COMPLETED"))
            .pageSize(1000)
            .rowMapper(new DataClassRowMapper<>(Order.class))
            .build();
}
```

여기서 `Order` 는 도메인 레코드가 아니라 `org.springframework.batch.item.database.Order` 열거형입니다(`ASCENDING` / `DESCENDING`). 이름이 겹치므로 실습 코드에서는 `import ... database.Order as SortOrder` 대신 정규화된 이름을 씁니다.

`MySqlPagingQueryProvider` 가 만드는 SQL 을 보겠습니다. `org.springframework.batch.item.database` 를 DEBUG 로 올리면 나옵니다.

```yaml
logging:
  level:
    org.springframework.batch.item.database: DEBUG
```

**결과**
```
DEBUG --- o.s.b.i.d.JdbcPagingItemReader : SQL used for reading first page: [SELECT order_id, customer_id, amount, status, ordered_at FROM orders WHERE status = ? ORDER BY order_id ASC LIMIT 1000]
DEBUG --- o.s.b.i.d.JdbcPagingItemReader : SQL used for reading remaining pages: [SELECT order_id, customer_id, amount, status, ordered_at FROM orders WHERE (status = ?) AND ((order_id > ?)) ORDER BY order_id ASC LIMIT 1000]
```

**첫 페이지와 나머지 페이지의 SQL 이 다릅니다.** 이게 이 스텝에서 가장 중요한 한 줄입니다.

```sql
-- 첫 페이지
... ORDER BY order_id ASC LIMIT 1000

-- 두 번째 페이지부터
... WHERE (status = ?) AND ((order_id > ?)) ORDER BY order_id ASC LIMIT 1000
                            ^^^^^^^^^^^^^^
                            직전 페이지 마지막 행의 order_id
```

즉 Spring Batch 의 페이징 리더는 **`OFFSET` 을 쓰지 않습니다.** 직전 페이지의 마지막 정렬 키 값보다 **큰** 행부터 가져오는 **키셋(keyset) 방식**입니다. 이 설계는 성능상 훌륭하지만(6-7), **정렬 키가 유니크하지 않으면 재앙**입니다(6-6).

`SqlPagingQueryProviderFactoryBean` 을 쓰면 `DataSource` 의 메타데이터를 보고 DB 별 프로바이더를 자동으로 고릅니다.

```java
@Bean
public SqlPagingQueryProviderFactoryBean queryProvider(DataSource dataSource) {
    SqlPagingQueryProviderFactoryBean factory = new SqlPagingQueryProviderFactoryBean();
    factory.setDataSource(dataSource);        // ← MySQL 이면 MySqlPagingQueryProvider
    factory.setSelectClause("order_id, customer_id, amount, status, ordered_at");
    factory.setFromClause("FROM orders");
    factory.setWhereClause("status = :status");
    factory.setSortKeys(Map.of("order_id", Order.ASCENDING));
    return factory;
}
```

**결과**
```
DEBUG --- o.s.b.i.d.s.SqlPagingQueryProviderFactoryBean : Using provider: org.springframework.batch.item.database.support.MySqlPagingQueryProvider
```

로컬은 MySQL, 운영은 다른 DB 인 경우가 아니라면 명시적으로 `MySqlPagingQueryProvider` 를 쓰는 편이 읽기 쉽습니다.

---

## 6-4. 커서 vs 페이징 — 비교표

| 항목 | `JdbcCursorItemReader` | `JdbcPagingItemReader` |
|---|---|---|
| 쿼리 실행 횟수 | **1회** | **페이지 수만큼** (7만 건 / 1,000 = 70회) |
| DB 커넥션 | Step 이 끝날 때까지 **점유** | 페이지 읽을 때만 잡고 **반납** |
| 메모리 | 드라이버 설정에 달림. MySQL 기본은 **전량 적재** | 페이지 크기만큼 |
| 정렬 키 | 없어도 됨 | **필수. 그리고 유니크해야 함** |
| 멀티스레드 Step | **불가** (`ResultSet` 이 스레드 안전하지 않음) | **가능** |
| 재시작 | 처음부터 다시 읽고 버림 (느림) | 저장된 페이지부터 (빠름) |
| 처리 중 데이터 변경 | 커서가 스냅샷을 보므로 대체로 안전 | 페이지마다 재조회 → **정렬 키 변경 시 누락/중복** |
| 긴 트랜잭션 | DB 에 오래 걸린 커서 → 락·undo 누적 | 짧은 쿼리 반복 |
| 적합한 상황 | 단일 스레드, 짧은 배치, 정렬 키 잡기 어려운 쿼리 | 대용량, 멀티스레드, 장시간 배치 |

**실측** — 같은 70,000건 정산을 청크 1,000 으로 돌린 결과입니다.

```bash
for R in cursorJob pagingJob; do
  mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
  echo "=== $R ==="
  ./gradlew bootRun --args="--spring.batch.job.name=$R" | grep 'executed in'
done
```

**결과**
```
=== cursorJob ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [cursorStep] executed in 6s101ms
=== pagingJob ===
INFO --- o.s.batch.core.step.AbstractStep : Step: [pagingStep] executed in 6s842ms
```

| 리더 | 실행시간 | 쿼리 수 | 커넥션 점유 |
|---|---|---|---|
| 커서 | **6.1초** | 1 | 6.1초 내내 |
| 페이징 | **6.8초** | 71 | 페이지당 수십 ms |

**단일 스레드에서는 커서가 약 12% 빠릅니다.** 쿼리 70번의 파싱·실행 계획 수립 비용이 그만큼입니다.

> 💡 **실무 팁 — 그래도 기본값은 페이징입니다**
> 12% 차이 때문에 커서를 고르면 안 됩니다. 페이징 리더가 주는 것은 속도가 아니라 **확장 가능성**입니다.
> - 멀티스레드 Step([Step 13](../step-13-scaling/))으로 스레드 4개를 붙이면 페이징은 6.8초 → 2.1초가 되지만, 커서는 **애초에 못 붙입니다.**
> - 배치가 6시간짜리로 커지면, 커넥션 하나를 6시간 점유하는 건 운영에서 받아들이기 어렵습니다. DBA 가 `wait_timeout` 으로 끊어 버리면 배치가 죽습니다.
> - 재시작 비용이 다릅니다. 100만 건째 실패 시 커서는 100만 번을 버리고 시작하고, 페이징은 마지막 키 값부터 곧장 시작합니다.
>
> **"작고 확실히 단일 스레드"면 커서, 나머지는 전부 페이징**으로 두세요. 단, 페이징을 고른 순간 다음 절의 규칙을 반드시 지켜야 합니다.

---

## 6-5. `DataClassRowMapper` — `record` 매핑

[프로젝트 셋업](../project/) 의 도메인은 Java 21 `record` 입니다. 여기에 `BeanPropertyRowMapper` 를 쓰면 이렇게 됩니다.

```java
// ❌ record 에는 안 됩니다
.rowMapper(new BeanPropertyRowMapper<>(Order.class))
```

**결과**
```
org.springframework.beans.BeanInstantiationException: Failed to instantiate [com.example.batch.domain.Order]:
    No default constructor found
	at org.springframework.beans.BeanUtils.instantiateClass(BeanUtils.java:172)
	at org.springframework.jdbc.core.BeanPropertyRowMapper.mapRow(BeanPropertyRowMapper.java:296)
```

이유는 두 가지입니다.

1. `BeanPropertyRowMapper` 는 **기본 생성자로 객체를 만든 뒤 setter 로 채웁니다.** `record` 에는 기본 생성자가 없습니다.
2. `record` 의 접근자는 `getAmount()` 가 아니라 `amount()` 입니다. 자바빈 규약이 아닙니다.

해결은 `DataClassRowMapper` 입니다(Spring 5.3+). **생성자 기반**으로 매핑합니다.

```java
.rowMapper(new DataClassRowMapper<>(Order.class))
```

**결과**
```
DEBUG --- c.e.b.step06.ReaderPractice : 첫 아이템: Order[order_id=1, customerId=1, amount=1100.00, status=COMPLETED, orderedAt=2025-01-01T00:01]
```

컬럼 이름 매칭 규칙도 알아 둘 만합니다. `DataClassRowMapper` 는 컬럼 이름의 **언더스코어를 제거하고 소문자로 비교**합니다.

| DB 컬럼 | 매칭되는 생성자 파라미터 |
|---|---|
| `order_id` | `orderId`, `order_id`, `ORDERID` 전부 매칭 |
| `customer_id` | `customerId`, `customer_id` |
| `ordered_at` | `orderedAt`, `ordered_at` |

그래서 도메인 레코드의 첫 컴포넌트가 `order_id` 든 `orderId` 든 똑같이 동작합니다.

> ⚠️ **함정 — `BeanPropertyRowMapper` 가 진짜 위험한 경우는 예외가 안 날 때입니다**
> 위처럼 `record` 를 쓰면 `BeanInstantiationException` 으로 **시끄럽게** 실패합니다. 이건 운이 좋은 경우입니다.
> 진짜 사고는 **기본 생성자는 있는데 setter 가 없는 일반 클래스**(예: Lombok `@Getter` 만 붙이고 `@Setter` 를 빠뜨린 경우)에서 납니다.
> `BeanPropertyRowMapper` 는 객체를 만드는 데는 성공하고, 채울 setter 를 못 찾으면 **조용히 건너뜁니다.**
> ```
> DEBUG --- o.s.jdbc.core.BeanPropertyRowMapper : No property found for column 'amount'
> ```
> 결과는 **모든 필드가 null 인 객체 70,000개**입니다. 예외는 없고, `READ_COUNT` 는 70,000 이고, `STATUS` 는 `COMPLETED` 입니다.
> 그리고 processor 에서 `NullPointerException` 이 나면 그나마 다행이고, 금액 필드가 `BigDecimal` 이 아니라 `int` 라면 **0 으로 정산서가 만들어집니다.**
> **`record` + `DataClassRowMapper` 조합을 기본으로 쓰세요.** 생성자 매핑은 컬럼이 안 맞으면 인스턴스를 못 만들어 실패하므로, 조용히 틀릴 여지가 구조적으로 없습니다.

> 💡 **`-parameters` 컴파일 옵션**
> `DataClassRowMapper` 는 생성자 파라미터 **이름**으로 컬럼을 찾습니다. `record` 는 컴포넌트 이름이 리플렉션으로 항상 노출되므로 문제없지만, **일반 클래스의 생성자**를 쓸 때는 `-parameters` 없이 컴파일하면 이름이 `arg0`, `arg1` 이 되어 매핑이 전부 실패합니다.
> Spring Boot Gradle 플러그인이 `-parameters` 를 자동으로 켜 주므로 이 프로젝트는 괜찮습니다. 플러그인 없이 순수 Gradle 로 옮길 때만 신경 쓰면 됩니다.

---

## 6-6. 함정 — `sortKeys` 가 유니크하지 않으면 데이터가 조용히 사라진다

이 스텝의 핵심입니다. **정렬 키를 `ordered_at` 으로 바꿔 보겠습니다.**

"주문 시각 순으로 정산하는 게 자연스럽지" 라는 판단은 업무적으로 전혀 이상하지 않습니다. 그리고 `orders` 에는 `idx_orders_status_date (status, ordered_at)` 인덱스도 있어서 성능도 좋아 보입니다.

```java
// ⚠️ 이 코드는 에러 없이 돌고, 데이터를 잃습니다
MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
provider.setSelectClause("order_id, customer_id, amount, status, ordered_at");
provider.setFromClause("FROM orders");
provider.setWhereClause("status = :status");
provider.setSortKeys(Map.of("ordered_at", Order.ASCENDING));   // ← 유니크하지 않다!
```

돌립니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=brokenSortJob'
```

**결과**
```
INFO 45210 --- [           main] o.s.batch.core.job.SimpleStepHandler     : Executing step: [brokenSortStep]
DEBUG 45210 --- [           main] o.s.b.i.d.JdbcPagingItemReader           : SQL used for reading first page: [SELECT order_id, customer_id, amount, status, ordered_at FROM orders WHERE status = ? ORDER BY ordered_at ASC LIMIT 1000]
DEBUG 45210 --- [           main] o.s.b.i.d.JdbcPagingItemReader           : SQL used for reading remaining pages: [SELECT order_id, customer_id, amount, status, ordered_at FROM orders WHERE (status = ?) AND ((ordered_at > ?)) ORDER BY ordered_at ASC LIMIT 1000]
INFO 45210 --- [           main] o.s.batch.core.step.AbstractStep         : Step: [brokenSortStep] executed in 5s912ms
INFO 45210 --- [           main] o.s.b.c.l.s.TaskExecutorJobLauncher      : Job: [SimpleJob: [name=brokenSortJob]] completed with the following parameters: [{}] and the following status: [COMPLETED] in 6s047ms

BUILD SUCCESSFUL in 9s
```

**`COMPLETED` 입니다.** 경고 하나 없습니다. 이제 메타데이터를 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT
FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;"
```

**결과**
```
+----------------+-----------+------------+-------------+--------------+----------------+
| STEP_NAME      | STATUS    | READ_COUNT | WRITE_COUNT | COMMIT_COUNT | ROLLBACK_COUNT |
+----------------+-----------+------------+-------------+--------------+----------------+
| brokenSortStep | COMPLETED |      67207 |       67207 |           69 |              0 |
+----------------+-----------+------------+-------------+--------------+----------------+
```

**70,000건이어야 하는데 67,207건입니다.**

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT (SELECT COUNT(*) FROM orders WHERE status='COMPLETED') AS should_be,
       (SELECT COUNT(*) FROM settlement)                      AS actually_settled,
       (SELECT COUNT(*) FROM orders WHERE status='COMPLETED')
     - (SELECT COUNT(*) FROM settlement)                      AS missing;"
```

**결과**
```
+-----------+------------------+---------+
| should_be | actually_settled | missing |
+-----------+------------------+---------+
|     70000 |            67207 |    2793 |
+-----------+------------------+---------+
```

**2,793건의 주문이 정산되지 않았습니다.** 배치는 성공했다고 말합니다. `ROLLBACK_COUNT` 는 0 이고, 로그에는 `ERROR` 도 `WARN` 도 없습니다.

누락된 주문의 금액을 세어 봅니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT COUNT(*) missing_orders, SUM(o.amount) missing_amount
FROM orders o
LEFT JOIN settlement s ON s.order_id = o.order_id
WHERE o.status = 'COMPLETED' AND s.order_id IS NULL;"
```

**결과**
```
+----------------+----------------+
| missing_orders | missing_amount |
+----------------+----------------+
|           2793 |   138865290.00 |
+----------------+----------------+
```

**1억 3,886만 원어치 주문이 정산에서 통째로 빠졌습니다.** 전체 정산 대상 `3,485,250,000.00` 의 약 4% 입니다.

### 왜 이런 일이 벌어지는가

6-3 에서 본 "나머지 페이지" SQL 을 다시 봅니다.

```sql
WHERE (status = ?) AND ((ordered_at > ?)) ORDER BY ordered_at ASC LIMIT 1000
                        ^^^^^^^^^^^^^^^
                        직전 페이지 마지막 행의 ordered_at
```

부등호가 `>` 입니다. **같은 값(`=`)은 제외**합니다.

6-0 에서 확인했듯 하나의 `ordered_at` 에는 주문이 69~70건씩 뭉쳐 있습니다. 페이지 경계가 그 뭉치의 한가운데를 자르면, **뒷부분이 통째로 사라집니다.**

```
   ordered_at = '2025-01-02 18:02:00' 인 주문이 70건 있다고 합시다.
   1페이지가 그중 27번째에서 끝났습니다.

   ┌──────────────── 1페이지 (1,000행) ────────────────┐
   │ ...  │ T번 1 │ T번 2 │ ... │ T번 27 │             │   ← 여기서 LIMIT 1000 에 걸려 끝
   └───────────────────────────────────────────────────┘
                                    ▲
                             마지막 행의 ordered_at = T

   2페이지 SQL:  WHERE ordered_at > T
                                  ^^^
   ┌──────────────── 2페이지 ──────────────────────────┐
   │ T번 28 ~ T번 70 은 조건에 안 맞음 → 건너뜀 ❌      │   ← 43건 증발
   │ 그다음 시각부터 다시 1,000행                       │
   └───────────────────────────────────────────────────┘
```

**페이지 경계마다 평균 43건씩 사라집니다.** 로그로 확인해 보면 정확히 그렇습니다.

```bash
./gradlew bootRun --args='--spring.batch.job.name=brokenSortJob --debug-pages=true' 2>&1 | grep '페이지 경계'
```

**결과**
```
DEBUG --- c.e.b.step06.PageBoundaryLogger : 페이지 1 마지막 ordered_at=2025-01-02T18:02, 같은 값 총 70건 중 27건만 읽음 → 43건 유실
DEBUG --- c.e.b.step06.PageBoundaryLogger : 페이지 2 마지막 ordered_at=2025-01-04T15:04, 같은 값 총 70건 중 27건만 읽음 → 43건 유실
DEBUG --- c.e.b.step06.PageBoundaryLogger : 페이지 3 마지막 ordered_at=2025-01-06T12:06, 같은 값 총 70건 중 27건만 읽음 → 43건 유실
DEBUG --- c.e.b.step06.PageBoundaryLogger : 페이지 4 마지막 ordered_at=2025-01-11T09:11, 같은 값 총 70건 중 27건만 읽음 → 43건 유실
DEBUG --- c.e.b.step06.PageBoundaryLogger : 페이지 5 마지막 ordered_at=2025-01-13T06:13, 같은 값 총 69건 중 27건만 읽음 → 42건 유실
...
DEBUG --- c.e.b.step06.PageBoundaryLogger : 총 68페이지, 누적 유실 2793건
```

산수가 맞는지 확인합니다.

| 항목 | 값 |
|---|---|
| 정산 대상 | 70,000건 |
| 서로 다른 `ordered_at` | 1,008개 |
| 시각 하나당 주문 | 평균 69.44건 (69 또는 70) |
| 페이지 크기 | 1,000 |
| 페이지 경계마다 유실 | 평균 약 41건 |
| 페이지 수 | 68 |
| **누적 유실** | **2,793건** |
| **실제로 읽은 건수** | **67,207건** |

> ⚠️ **함정 — `sortKeys` 는 반드시 유니크해야 합니다**
> 이 사고가 무서운 이유를 정리합니다.
>
> | | |
> |---|---|
> | 예외 | 없음 |
> | 로그 | `WARN` 조차 없음 |
> | Job 상태 | `COMPLETED` |
> | `ROLLBACK_COUNT` | 0 |
> | `READ_COUNT` vs `WRITE_COUNT` | **67,207 == 67,207 로 일치** |
> | 재실행 | 같은 결과. 재현되지만 여전히 조용함 |
>
> [Step 05](../step-05-chunk/) 에서 "`READ_COUNT == WRITE_COUNT` 를 성공의 근거로 삼지 말라"고 했는데, 여기서는 **`STATUS` 조차 믿을 수 없습니다.** 배치 내부의 어떤 지표로도 이 사고를 잡을 수 없습니다.
>
> **해결책은 세 가지이며, 셋 다 하세요.**
>
> **① 정렬 키를 유니크하게** — 가장 근본적입니다.
> ```java
> provider.setSortKeys(Map.of("order_id", Order.ASCENDING));
> ```
>
> **② 유니크하지 않은 키로 정렬해야 한다면 복합 키로** — 업무상 시각 순서가 필요한 경우입니다. `LinkedHashMap` 을 써서 **순서를 보장**해야 합니다. `Map.of()` 는 순서를 보장하지 않으므로 복합 키에 쓰면 안 됩니다.
> ```java
> Map<String, Order> sortKeys = new LinkedHashMap<>();
> sortKeys.put("ordered_at", Order.ASCENDING);
> sortKeys.put("order_id",   Order.ASCENDING);   // ← 타이브레이커
> provider.setSortKeys(sortKeys);
> ```
>
> **③ 배치 끝에 건수 검증 Step 을 붙이기** — 위 둘을 지켜도 사람은 실수합니다. 정산 대상 수와 결과 수를 대조하는 Tasklet 하나면 됩니다.
> ```java
> int expected = jdbc.queryForObject(
>         "SELECT COUNT(*) FROM orders WHERE status='COMPLETED'", Integer.class);
> int actual = jdbc.queryForObject("SELECT COUNT(*) FROM settlement", Integer.class);
> if (expected != actual) {
>     throw new IllegalStateException("정산 누락: 대상 %d건, 처리 %d건".formatted(expected, actual));
> }
> ```
> **조용히 틀리느니 시끄럽게 실패하는 편이 낫습니다.** 이 코스가 반복하는 원칙입니다.

### 고쳐서 다시 돌리기

②의 복합 키를 적용합니다.

```bash
mysql -h127.0.0.1 -P3308 -uroot -proot1234 batchdb -e "TRUNCATE TABLE settlement;"
./gradlew bootRun --args='--spring.batch.job.name=fixedSortJob'
```

**결과**
```
DEBUG --- o.s.b.i.d.JdbcPagingItemReader : SQL used for reading remaining pages: [SELECT order_id, customer_id, amount, status, ordered_at FROM orders WHERE (status = ?) AND ((ordered_at > ?) OR (ordered_at = ? AND order_id > ?)) ORDER BY ordered_at ASC, order_id ASC LIMIT 1000]
INFO  --- o.s.batch.core.step.AbstractStep : Step: [fixedSortStep] executed in 6s773ms
INFO  --- o.s.b.c.l.s.TaskExecutorJobLauncher : ... and the following status: [COMPLETED] in 6s901ms
```

SQL 의 `WHERE` 절이 바뀐 것을 보세요.

```sql
AND ((ordered_at > ?) OR (ordered_at = ? AND order_id > ?))
                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                          "시각이 같으면 order_id 로 이어서"
```

이게 올바른 키셋 페이징입니다. 결과를 확인합니다.

```bash
mysql -h127.0.0.1 -P3308 -ubatch -pbatch1234 batchdb -t -e "
SELECT READ_COUNT, WRITE_COUNT FROM BATCH_STEP_EXECUTION ORDER BY STEP_EXECUTION_ID DESC LIMIT 1;
SELECT COUNT(*) AS settled FROM settlement;"
```

**결과**
```
+------------+-------------+
| READ_COUNT | WRITE_COUNT |
+------------+-------------+
|      70000 |       70000 |
+------------+-------------+
+---------+
| settled |
+---------+
|   70000 |
+---------+
```

**67,207 → 70,000.** 누락 2,793건이 사라졌습니다. 실행시간은 5.9초 → 6.8초로 늘었는데, 이건 느려진 게 아니라 **원래 해야 할 일을 다 한 것**입니다. 앞의 5.9초는 4%를 빼먹고 얻은 시간이었습니다.

> ⚠️ **함정 안의 함정 — "우연히 잘 도는" 경우가 가장 위험합니다**
> 지금까지는 정렬 키가 유니크하지 않으면 **항상** 유실이 나는 것처럼 보였습니다. 그렇지 않습니다.
> 정렬 키를 `customer_id` 로 바꿔 보면 유실이 **0건**입니다. `COMPLETED` 주문의 `customer_id` 는 700종이고 값 하나당 **정확히 100건**씩이라, 페이지 크기 1,000이 뭉치 크기 100의 배수여서 페이지 경계가 항상 뭉치 경계와 정확히 맞아떨어지기 때문입니다.
>
> | 정렬 키 | 뭉치 크기 | `pageSize` | 읽은 건수 | 유실 |
> |---|---|---|---|---|
> | `customer_id` | 100 (균일) | 1000 | **70,000** | **0** |
> | `customer_id` | 100 (균일) | 500 | 70,000 | 0 |
> | `customer_id` | 100 (균일) | **1024** | **65,212** | **4,788** |
> | `customer_id` | 100 (균일) | **250** | **58,350** | **11,650** |
> | `ordered_at` | 69~70 | 1000 | 67,207 | 2,793 |
>
> 즉 `customer_id` 로 정렬한 배치는 **몇 년이고 멀쩡히 돌 수 있습니다.** 그러다 누군가 성능 튜닝이랍시고 `pageSize` 를 1000 에서 1024 로 바꾸는 순간, **아무 관련 없어 보이는 그 한 줄이 4,788건을 증발시킵니다.**
> 코드 리뷰에서 "`pageSize` 를 1024로 조정" 이라는 diff 를 보고 데이터 유실을 떠올릴 사람은 없습니다. 테스트도 통과합니다(소량 데이터에서는 페이지가 하나뿐이라 경계가 없습니다).
> **"지금 잘 돌아간다"는 정렬 키가 유니크하다는 증거가 아닙니다.** 유일성은 우연이 아니라 설계로 보장하세요.

---

## 6-7. `OFFSET` 페이징의 위험과 키셋 방식

Spring Batch 가 왜 `OFFSET` 을 안 쓰는지 봅니다. 직접 `OFFSET` 리더를 만들었다고 가정합니다.

```sql
-- 1페이지
SELECT ... FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 0;
-- 2페이지
SELECT ... FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 1000;
-- 70페이지
SELECT ... FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 69000;
```

### 문제 1 — 뒤로 갈수록 느려진다

`OFFSET 69000` 은 "69,000행을 읽고 버린 다음 1,000행을 반환하라"는 뜻입니다. 건너뛰는 게 아니라 **읽고 버립니다.**

```sql
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 0;
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 35000;
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' ORDER BY order_id LIMIT 1000 OFFSET 69000;
```

**결과**
```
1000 rows in set (0.004 sec)
1000 rows in set (0.071 sec)
1000 rows in set (0.138 sec)
```

**0.004초 → 0.138초. 약 35배입니다.** 그리고 이건 7만 건일 때 이야기입니다. 700만 건이면 마지막 페이지가 몇 초씩 걸립니다. 전체 비용은 페이지 수의 제곱에 비례합니다.

키셋 방식은 어떨까요?

```sql
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' AND order_id > 0     ORDER BY order_id LIMIT 1000;
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' AND order_id > 50000 ORDER BY order_id LIMIT 1000;
SELECT SQL_NO_CACHE order_id FROM orders WHERE status='COMPLETED' AND order_id > 98570 ORDER BY order_id LIMIT 1000;
```

**결과**
```
1000 rows in set (0.004 sec)
1000 rows in set (0.005 sec)
1000 rows in set (0.004 sec)
```

**어느 페이지든 0.004초 근처로 일정합니다.** 인덱스로 시작점을 바로 찾아 1,000행만 읽기 때문입니다. 마지막 페이지 기준 **0.138초 → 0.004초, 약 34배**입니다.

### 문제 2 — 처리 중 데이터가 바뀌면 누락·중복이 난다

이쪽이 더 위험합니다. 배치가 도는 동안 다른 프로세스가 `orders` 에 행을 넣거나 지우면 `OFFSET` 이 밀립니다.

```
   1페이지: OFFSET 0     → order_id 1 ~ 1000 을 읽음
   ── 이 사이에 order_id 500 인 주문이 취소되어 삭제됨 ──
   2페이지: OFFSET 1000  → 이제 1001번째 행은 order_id 1002 다
                           order_id 1001 은 아무도 안 읽음 ❌
```

**정확히 삭제된 개수만큼 뒤로 밀리고, 그만큼 누락됩니다.** 반대로 앞쪽에 행이 삽입되면 같은 행을 두 번 읽습니다.

키셋 방식은 `WHERE order_id > 1000` 이라는 **절대적인 기준점**을 쓰므로, 앞쪽에서 무슨 일이 벌어져도 영향을 받지 않습니다.

| 방식 | 마지막 페이지 성능 | 앞쪽 삭제 시 | 앞쪽 삽입 시 | 정렬 키 요구사항 |
|---|---|---|---|---|
| `OFFSET` | O(n) — 뒤로 갈수록 느림 | **누락** | **중복** | 정렬만 되면 됨 |
| 키셋(seek) | O(log n) — 일정 | 영향 없음 | 영향 없음 | **유니크해야 함** |

Spring Batch 의 `JdbcPagingItemReader` 는 키셋 방식입니다. 그래서 성능과 안정성을 얻는 대신 **"정렬 키가 유니크해야 한다"는 조건**을 지불합니다. 6-6 의 사고는 그 대가를 안 치른 결과입니다.

> 💡 **실무 팁 — 정렬 키의 마지막은 항상 PK**
> 어떤 컬럼으로 정렬하든 **마지막 정렬 키로 PK 를 붙이는 습관**을 들이세요. 비용은 거의 없고(인덱스 리프에 이미 PK 가 들어 있습니다 — mysql8 코스 Step 15 참고), 유일성이 보장됩니다.
> `(ordered_at, order_id)`, `(customer_id, order_id)`, `(amount DESC, order_id)` 처럼 씁니다.
> 이건 배치뿐 아니라 **API 의 무한스크롤 페이징**에도 그대로 적용되는 규칙입니다.

> ⚠️ **함정 — 정렬 키로 쓰는 컬럼을 배치가 직접 수정하면 안 됩니다**
> `status` 로 정렬하면서 processor 가 `status` 를 `'SETTLED'` 로 바꾸는 배치를 생각해 보세요.
> 페이지 1을 읽고 처리해서 `status` 를 바꾸면, 페이지 2의 `WHERE status > ?` 조건에 맞는 행 집합이 **읽는 도중에 달라집니다.**
> 키셋 방식이라도 이건 못 막습니다. **"읽는 기준"과 "쓰는 대상"이 같은 컬럼이면 안 됩니다.**
> 처리 완료 표시가 필요하면 별도 컬럼을 두거나, 이 코스처럼 **결과를 다른 테이블(`settlement`)에 쌓으세요.**

---

## 6-8. `JpaPagingItemReader` 와 영속성 컨텍스트

JPA 로 읽고 싶다면 `JpaPagingItemReader` 입니다.

```java
@Bean
public JpaPagingItemReader<OrderEntity> jpaReader(EntityManagerFactory emf) {
    return new JpaPagingItemReaderBuilder<OrderEntity>()
            .name("jpaReader")
            .entityManagerFactory(emf)
            .queryString("""
                         SELECT o FROM OrderEntity o
                         WHERE o.status = :status
                         ORDER BY o.orderId ASC
                         """)   // ← ORDER BY 를 직접 씁니다. 그리고 유니크해야 합니다.
            .parameterValues(Map.of("status", "COMPLETED"))
            .pageSize(1000)
            .build();
}
```

`JdbcPagingItemReader` 와 달리 **`ORDER BY` 를 JPQL 안에 직접 씁니다.** `sortKeys` 설정이 없으므로 6-6 의 함정을 프레임워크가 알려 줄 방법이 더 없습니다. `ORDER BY` 를 빼먹어도 아무 경고가 없고, 그러면 페이지마다 순서가 달라져 **누락과 중복이 동시에** 납니다.

그리고 JPA 는 `OFFSET` 을 씁니다.

**결과** (Hibernate SQL 로그)
```
Hibernate: select o1_0.order_id,o1_0.amount,o1_0.customer_id,o1_0.ordered_at,o1_0.status from orders o1_0 where o1_0.status=? order by o1_0.order_id asc limit ?,?
```

`limit ?,?` 는 MySQL 의 `LIMIT offset, count` 입니다. 즉 **6-7 의 `OFFSET` 문제를 그대로 안고 있습니다.** 뒤로 갈수록 느려지고, 처리 중 데이터가 바뀌면 밀립니다.

### 영속성 컨텍스트 문제

더 큰 문제는 1차 캐시입니다. `JpaPagingItemReader` 는 페이지를 읽을 때마다 트랜잭션을 열고, 읽은 엔티티는 **영속 상태**가 됩니다.

```java
// processor 에서 엔티티를 건드리면
public Settlement process(OrderEntity item) {
    item.setStatus("SETTLED");        // ← 더티 체킹 대상이 됨
    return toSettlement(item);
}
```

이 `setStatus` 는 여러분이 `save()` 를 부르지 않아도 **플러시 시점에 UPDATE 로 나갑니다.** "읽기만 하려던" 배치가 조용히 `orders` 를 수정합니다.

Spring Batch 5.1 의 `JpaPagingItemReader` 는 `doReadPage()` 에서 페이지를 읽은 뒤 `entityManager.clear()` 를 호출해 1차 캐시를 비웁니다. 덕분에 페이지 사이에 엔티티가 누적되지는 않습니다. 하지만 **한 페이지 안에서는 여전히 영속 상태**입니다.

```
INFO --- o.s.b.i.d.JpaPagingItemReader : Clearing persistence context after page 1
```

| 항목 | `JdbcPagingItemReader` | `JpaPagingItemReader` |
|---|---|---|
| 페이징 방식 | 키셋 (`WHERE key > ?`) | **`OFFSET`** |
| 정렬 키 지정 | `setSortKeys()` — 복합 키 SQL 자동 생성 | JPQL 의 `ORDER BY` 를 직접 작성 |
| 유니크 정렬 키 누락 시 | 조용히 누락 | 조용히 누락 **+ 중복** |
| 반환 객체 | DTO / `record` (분리됨) | **영속 엔티티** |
| 의도치 않은 UPDATE | 없음 | **더티 체킹으로 발생 가능** |
| 1차 캐시 | 없음 | 페이지마다 `clear()`, 페이지 내부는 유지 |
| 성능 | 빠름 | 엔티티 생성·프록시 비용 추가 |

> ⚠️ **함정 — 배치에서 JPA 리더는 기본 선택지가 아닙니다**
> "우리 서비스가 JPA 를 쓰니 배치도 JPA 로" 는 흔한 판단이지만, 배치의 읽기는 **대량·단방향·읽기 전용**이라 JPA 가 주는 이점(더티 체킹, 지연 로딩, 1차 캐시)이 전부 **비용**으로만 작용합니다.
> - 더티 체킹 → 의도치 않은 UPDATE
> - 지연 로딩 → 아이템마다 N+1 쿼리
> - 1차 캐시 → 메모리 압박
>
> **읽기는 `JdbcPagingItemReader` + `record`, 쓰기만 필요하면 그때 JPA** 를 쓰는 조합을 권합니다.
> JPA 리더를 꼭 써야 한다면 최소한 이 둘을 지키세요.
> ```java
> // 1) 엔티티 대신 DTO 프로젝션으로 읽는다 — 영속 상태를 만들지 않는다
> .queryString("SELECT new com.example.batch.dto.OrderDto(o.orderId, o.amount, o.orderedAt) "
>            + "FROM OrderEntity o WHERE o.status = :status ORDER BY o.orderId ASC")
> ```
> ```yaml
> # 2) open-in-view 를 끈다 (프로젝트 셋업에 이미 반영돼 있습니다)
> spring.jpa.open-in-view: false
> ```

---

## 6-9. FlatFileItemReader — CSV 읽기

파일 입력도 자주 씁니다. 외부 정산 데이터를 CSV 로 받는 상황을 가정합니다.

```bash
cat > /tmp/external-orders.csv <<'CSV'
# 외부 정산 파일 v2 — 2025-06-30 생성
order_id,customer_id,amount,ordered_at
900001,1,15000.00,2025-06-30 10:00:00
900002,2,23500.00,2025-06-30 10:05:00
900003,3,8700.00,2025-06-30 10:11:00
CSV
```

`FlatFileItemReader` 는 부품 조립입니다. **한 줄 → `LineTokenizer`(구분자로 잘라 `FieldSet` 생성) → `FieldSetMapper`(타입 변환해 객체 생성)** 이고, 이 둘을 묶는 것이 `LineMapper`(보통 `DefaultLineMapper`)입니다.

```java
@Bean
public FlatFileItemReader<ExternalOrder> csvReader() {

    DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
    tokenizer.setDelimiter(",");                 // 기본값도 쉼표입니다
    tokenizer.setNames("orderId", "customerId", "amount", "orderedAt");
    tokenizer.setStrict(true);                   // 컬럼 수가 다르면 예외 (기본 true)

    DefaultLineMapper<ExternalOrder> lineMapper = new DefaultLineMapper<>();
    lineMapper.setLineTokenizer(tokenizer);
    lineMapper.setFieldSetMapper(fieldSet -> new ExternalOrder(
            fieldSet.readLong("orderId"),
            fieldSet.readInt("customerId"),
            fieldSet.readBigDecimal("amount"),
            LocalDateTime.parse(fieldSet.readString("orderedAt").replace(' ', 'T'))
    ));

    return new FlatFileItemReaderBuilder<ExternalOrder>()
            .name("csvReader")
            .resource(new FileSystemResource("/tmp/external-orders.csv"))
            .encoding("UTF-8")
            .linesToSkip(2)                      // 주석 1줄 + 헤더 1줄
            .lineMapper(lineMapper)
            .build();
}
```

**결과**
```
DEBUG --- c.e.b.step06.CsvJobConfig : 읽음: ExternalOrder[orderId=900001, customerId=1, amount=15000.00, orderedAt=2025-06-30T10:00]
DEBUG --- c.e.b.step06.CsvJobConfig : 읽음: ExternalOrder[orderId=900002, customerId=2, amount=23500.00, orderedAt=2025-06-30T10:05]
DEBUG --- c.e.b.step06.CsvJobConfig : 읽음: ExternalOrder[orderId=900003, customerId=3, amount=8700.00, orderedAt=2025-06-30T10:11]
INFO  --- o.s.batch.core.step.AbstractStep : Step: [csvStep] executed in 78ms
```

주요 설정은 이렇습니다.

| 설정 | 의미 |
|---|---|
| `linesToSkip(n)` | 앞 n줄 건너뜀. 헤더용 |
| `skippedLinesCallback` | 건너뛴 줄을 받아 처리 (헤더에서 컬럼 순서를 읽을 때 유용) |
| `encoding("UTF-8")` | **기본값은 JVM 기본 인코딩**입니다. 반드시 명시하세요 |
| `strict(true)` | 파일이 없으면 예외. `false` 면 빈 리더처럼 동작 |
| `tokenizer.setStrict(true)` | 컬럼 수가 선언과 다르면 예외 |
| `tokenizer.setQuoteCharacter('"')` | 따옴표로 감싼 필드 안의 구분자 처리 |
| `comments("#")` | 해당 접두사로 시작하는 줄 무시 |

> ⚠️ **함정 — `FlatFileItemReader` 의 `strict(false)` 와 `linesToSkip` 이 조용한 사고를 만듭니다**
> 두 가지 조합이 특히 위험합니다.
>
> **(1) `.strict(false)` 로 두면 파일이 없어도 배치가 성공합니다.**
> 원격 파일 전송이 실패해서 어제 파일이 아예 안 왔는데, 배치는 `READ_COUNT: 0` 으로 `COMPLETED` 합니다. "0건 처리 성공"입니다.
> 실제로 이 옵션은 "파일이 있을 수도 없을 수도 있는" 선택적 입력에만 쓰고, 필수 입력이면 **`strict(true)`(기본값)를 유지**하세요.
>
> **(2) 헤더 줄 수가 바뀌면, 어느 방향으로 틀리느냐에 따라 결과가 정반대입니다.**
> 위 예제는 `linesToSkip(2)`(주석 1줄 + 헤더 1줄)입니다. 파일을 만드는 쪽은 대개 다른 팀·다른 시스템이고, 주석 한 줄 추가는 그쪽에서 "아무 영향 없는 변경"으로 보입니다.
>
> | 파일 변화 | 무슨 일이 일어나나 | 위험도 |
> |---|---|---|
> | 주석이 **1줄 늘어남** | 헤더 줄이 데이터로 파싱됨 → `NumberFormatException` | **시끄러운 실패 — 다행** |
> | 주석이 **없어짐** | 헤더 + **첫 데이터 행**을 건너뜀 → 한 건이 말없이 사라짐 | **조용한 유실 — 위험** |
>
> 두 번째가 진짜 사고입니다. 매일 한 건씩, 아무도 모르게 누락됩니다.
> 줄 수를 세지 말고 **내용으로 판별**하세요. `comments("#")` 는 주석이 0줄이든 10줄이든, 중간에 끼어 있든 동작합니다.
> ```java
> .comments("#")        // 주석은 몇 줄이든 알아서 무시
> .linesToSkip(1)       // 헤더 한 줄만
> .skippedLinesCallback(line -> {   // 헤더 형식까지 검증하면 더 좋습니다
>     if (!"order_id,customer_id,amount,ordered_at".equals(line.trim()))
>         throw new IllegalStateException("CSV 헤더 형식이 바뀌었습니다: " + line);
> })
> ```
> 헤더 검증이 필요한 이유가 하나 더 있습니다. `DelimitedLineTokenizer` 는 **컬럼을 이름이 아니라 위치로** 매핑합니다. 파일 쪽에서 `customer_id` 와 `amount` 의 순서를 바꾸면, 둘 다 숫자라 예외 없이 **값이 뒤바뀐 채 파싱됩니다.**

---

## 정리

| 개념 | 핵심 |
|---|---|
| `ItemReader<T>` | `T read()` 하나뿐. **`null` = 데이터 끝** |
| `ItemStream` | `open`/`update`/`close`. `update()` 는 **청크 커밋마다** 호출 → 재시작 위치 저장 |
| 리더 `.name()` | `ExecutionContext` 키의 접두사. **중복되면 재시작이 조용히 망가짐** |
| `JdbcCursorItemReader` | 쿼리 1회, **커넥션 점유**, 멀티스레드 불가, 재시작이 느림 |
| MySQL `fetchSize` | **기본적으로 무시됨.** `useCursorFetch=true` 또는 `Integer.MIN_VALUE` 필요 |
| `JdbcPagingItemReader` | 페이지마다 재쿼리, 커넥션 반납, **멀티스레드 가능**, 정렬 키 필수 |
| 실측 (70,000건) | 커서 6.1초 vs 페이징 6.8초. **12% 차이지만 기본은 페이징** |
| `DataClassRowMapper` | `record` 매핑. 생성자 기반이라 **조용히 틀릴 여지가 없음** |
| `BeanPropertyRowMapper` | `record` 에는 `BeanInstantiationException`. **setter 없는 POJO 에는 전 필드 null** |
| **`sortKeys` 유니크** | **안 지키면 70,000건 중 67,207건만 읽고 `COMPLETED`. 2,793건·1.38억 원 증발** |
| 유실 원리 | 나머지 페이지 SQL 이 `WHERE key > ?` → 경계의 동일 값 뭉치가 통째로 스킵 |
| **잠복하는 결함** | 뭉치 크기가 `pageSize` 를 나누면 **유실 0**. `pageSize` 1000→1024 로 바꾸는 순간 4,788건 증발 |
| 복합 정렬 키 | `LinkedHashMap` 으로 순서 보장. `Map.of()` 는 **순서 미보장이라 금지** |
| 정렬 키 관용구 | **마지막 정렬 키는 항상 PK** |
| `OFFSET` vs 키셋 | OFFSET 은 마지막 페이지 0.138초, 키셋은 0.004초. **약 34배** |
| `OFFSET` 의 진짜 위험 | 처리 중 앞쪽 삭제 → **누락**, 삽입 → **중복** |
| 정렬 키 수정 금지 | "읽는 기준"과 "쓰는 대상"이 같은 컬럼이면 안 됨 |
| `JpaPagingItemReader` | **`OFFSET` 방식** + 영속 엔티티 반환 → 더티 체킹으로 의도치 않은 UPDATE |
| 배치의 JPA | 읽기는 JDBC + `record` 권장. 꼭 쓴다면 DTO 프로젝션으로 |
| `FlatFileItemReader` | `LineTokenizer`(줄→FieldSet) + `FieldSetMapper`(FieldSet→객체) |
| 파일 리더 함정 | `strict(false)` → 파일 없어도 성공. `linesToSkip` → **`comments()` 로 대체** |
| 검증 Step | 어떤 리더를 쓰든 **대상 건수 vs 결과 건수 대조 Step 을 붙일 것** |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `BeanPropertyRowMapper` 로 `record` 를 매핑해 실패시키고, `DataClassRowMapper` 로 고치기
2. `customer_id` 를 정렬 키로 준 페이징 리더를 `pageSize` 1000 과 1024 로 각각 돌려 유실 건수를 **미리 계산**하고 재현하기 (한쪽은 유실이 0입니다 — 왜인지가 이 문제의 핵심입니다)
3. 위 리더를 복합 정렬 키로 고치고 `pageSize` 와 무관하게 70,000건이 나오게 만들기
4. 커서 리더에 멀티스레드를 붙여 무슨 일이 벌어지는지 확인하기
5. `OFFSET` 리더와 키셋 리더의 마지막 페이지 응답시간 비교하기
6. CSV 헤더가 늘어난 파일로 `linesToSkip` 함정 재현하고 `comments()` 로 고치기
7. 정산 누락을 잡아내는 검증 Tasklet 작성하기

---

## 다음 단계

리더가 아이템을 안전하게 꺼내 왔으니, 이제 그 아이템을 **변환**할 차례입니다.
`ItemProcessor` 는 `null` 을 반환해 아이템을 걸러 낼 수 있는데, 이 필터링이 `FILTER_COUNT` 로만 기록되고 로그에는 아무것도 남지 않아 **"왜 7만 건 중 3만 건만 나왔지?"** 하는 상황을 만듭니다.
`CompositeItemProcessor` 로 변환을 단계별로 쪼개는 법, 그리고 `ValidatingItemProcessor` 로 검증을 붙이는 법도 함께 봅니다.

→ [Step 07 — ItemProcessor](../step-07-item-processor/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 읽으며 6-1 ~ 6-9 의 리더들을 하나씩 돌려 보고, 특히 **6-6 의 유실을 반드시 직접 재현한 뒤** `Exercise.java` 의 7문제를 풀고, `Solution.java` 로 대조합니다. 세 파일 모두 `com.example.batch.step06` 패키지입니다.

### Practice.java

본문의 모든 리더 설정을 절 번호 주석(`// [6-3]` 등)과 함께 담은 `@Configuration` 클래스입니다.

- **Job 이 여섯 개(`cursorJob` / `pagingJob` / `brokenSortJob` / `fixedSortJob` / `jpaJob` / `csvJob`) 들어 있으므로 `--spring.batch.job.name=` 을 반드시 지정하세요.** 지정하지 않으면 Boot 3.2 가 전부 순차 실행하고, 그 과정에서 `settlement.order_id` 의 UNIQUE 제약에 걸려 두 번째 Job 부터 `DuplicateKeyException` 이 납니다.
- **`brokenSortJob` 과 `fixedSortJob` 은 짝입니다.** 반드시 `brokenSortJob` 을 먼저 돌려 67,207건을 직접 확인한 뒤 `fixedSortJob` 으로 70,000건을 회복시키세요. 순서를 바꾸면 이 스텝의 핵심 체험이 사라집니다. 각 실행 사이에 `TRUNCATE TABLE settlement` 를 잊지 마세요.
- `brokenSortJob` 의 정렬 키가 `Map.of("ordered_at", Order.ASCENDING)` 인 반면, `fixedSortJob` 은 `LinkedHashMap` 을 씁니다. **이 자료구조 차이가 의도적입니다.** 복합 키에 `Map.of()` 를 쓰면 순서가 보장되지 않아 `ORDER BY` 절의 컬럼 순서가 실행마다 달라질 수 있고, 그러면 페이징이 또 다른 방식으로 깨집니다.
- `PageBoundaryLogger` 는 교육용 계측 코드입니다. `ItemReadListener` 로 페이지 경계를 감지해 "같은 `ordered_at` 이 총 몇 건인데 몇 건만 읽었는지"를 계산해 로그로 남깁니다. **운영 코드에 넣을 것은 아니지만**, 이런 계측 없이는 유실을 눈으로 확인할 방법이 없다는 점 자체가 이 함정의 무서움입니다.
- `verificationTasklet` 이 `orders` 의 COMPLETED 건수와 `settlement` 건수를 대조해 다르면 예외를 던집니다. `brokenSortJob` 에 이 Step 을 붙여 돌리면 **비로소 배치가 `FAILED` 로 끝납니다.** 6-6 의 해결책 ③ 을 실제로 확인해 보세요.
- `[6-9]` 의 `csvReader` 는 `/tmp/external-orders.csv` 를 읽습니다. 본문의 `cat > /tmp/external-orders.csv` 명령을 먼저 실행해야 합니다. 파일이 없으면 `strict` 기본값이 `true` 라 `ItemStreamException` 으로 실패하는데, **이건 정상 동작입니다.**
- `jpaReader` 를 쓰는 `jpaJob` 은 `OrderEntity` 가 필요합니다. 파일 하단에 `@Entity` 클래스가 함께 들어 있으며, `ddl-auto: none` 이므로 기존 `orders` 테이블에 매핑됩니다. Hibernate 가 만드는 `limit ?,?` SQL 을 보려면 `logging.level.org.hibernate.SQL: DEBUG` 를 켜세요.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 `// 여기에 작성:` 자리를 비워 두었습니다.

- **문제 2 가 이 스텝의 중심이고, 본문 6-6 보다도 무섭습니다.** `customer_id` 를 정렬 키로 주면 유실이 몇 건일지 **실행 전에 계산**하세요. 필요한 재료는 문제 주석 안의 SQL 로 구할 수 있습니다 — `customer_id` 의 서로 다른 값 수와, 값 하나당 주문 수입니다. 계산 없이 돌려 보고 숫자를 적으면 이 문제의 의미가 없습니다. `pageSize` 두 가지를 **모두** 돌려 보는 것이 중요합니다.
- 문제 1·3·6 은 **고장 난 코드를 고치는** 문제이고, 문제 2·5 는 **측정**, 문제 4·7 은 **관찰과 작성**입니다.
- 문제 4 는 커서 리더에 `.taskExecutor(...)` 를 붙입니다. 무슨 예외가 나는지, 아니면 예외 없이 결과만 이상해지는지 직접 확인하세요. **어느 쪽인지가 이 문제의 답입니다.**
- 문제 5 는 배치를 돌리지 않고 mysql CLI 에서 SQL 두 개를 실행해 비교하는 문제입니다. `SQL_NO_CACHE` 를 빼먹으면 두 번째 실행부터 캐시 때문에 차이가 안 보입니다.
- 문제 7 의 검증 Tasklet 은 단순히 건수만 비교하면 절반짜리입니다. 주석의 힌트대로 **금액 합계까지** 대조하세요. 건수가 같아도 금액이 다른 경우(중복 처리 + 누락이 동시에 일어난 경우)를 잡아내야 합니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과 해설 주석입니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `DataClassRowMapper` 로 바꾸는 한 줄이지만, 주석은 그보다 **`BeanPropertyRowMapper` 가 조용히 실패하는 경로**를 더 길게 설명합니다. `record` 는 시끄럽게 죽어서 오히려 안전하고, 진짜 사고는 Lombok `@Getter` 만 붙은 클래스에서 전 필드 `null` 로 나타난다는 점이 핵심입니다.
- **정답 2 가 이 스텝 전체에서 가장 값어치 있는 해설입니다.** 답은 `pageSize=1000` 일 때 **유실 0건**, `pageSize=1024` 일 때 **유실 4,788건**(읽기 65,212건)입니다. `customer_id` 는 COMPLETED 기준 700종이고 값 하나당 **정확히 100건**씩이라, 페이지 크기가 100의 배수이면 페이지 경계가 항상 뭉치 경계와 맞아떨어져 아무것도 안 잘립니다. 1024는 100의 배수가 아니라 경계가 뭉치 한가운데를 자릅니다. 주석은 여기서 결론을 끌어냅니다 — **유니크하지 않은 정렬 키는 "지금 우연히 동작하는" 상태일 수 있고, `pageSize` 를 튜닝하는 무관해 보이는 변경 한 줄이 그 폭탄을 터뜨립니다.** 코드 리뷰에서 절대 못 잡는 종류의 결함입니다.
- **정답 3** 은 `LinkedHashMap` 으로 `(customer_id, order_id)` 를 주는 것인데, 주석은 `Map.of()` 를 쓰면 안 되는 이유를 자바 API 계약 수준에서 설명합니다. `Map.of()` 는 순회 순서를 **의도적으로 무작위화**하므로(JVM 재시작마다 달라짐), 같은 코드가 어제는 맞고 오늘은 틀릴 수 있습니다. 복합 키로 고치고 나면 `pageSize` 를 250이든 1024든 3000이든 바꿔도 항상 70,000건이 나온다는 것을 표로 확인시킵니다.
- **정답 4** 의 답은 **"예외가 납니다"** 입니다. `ResultSet` 을 여러 스레드가 동시에 `next()` 하면서 `SQLException: Operation not allowed after ResultSet closed` 또는 `ArrayIndexOutOfBoundsException` 이 무작위 시점에 터집니다. 주석은 "그나마 시끄럽게 실패해서 다행"이라고 평가하고, 페이징 리더로 바꾸는 정공법을 제시합니다.
- **정답 5** 는 `OFFSET 69000` 이 0.138초, 키셋이 0.004초로 **약 34배**임을 보이고, 이 비율이 데이터가 커질수록 벌어진다는 점을 설명합니다. 700만 건이면 마지막 페이지가 10초를 넘긴다는 외삽까지 붙습니다.
- **정답 6** 은 `linesToSkip(3)` 으로 늘리는 게 아니라 **`comments("#") + linesToSkip(1)`** 로 구조를 바꾸는 것이 정답입니다. 숫자를 맞추는 해법은 파일 형식이 또 바뀌면 또 깨지기 때문입니다.
- **정답 7** 은 건수와 금액 합계를 모두 대조하는 Tasklet 이며, `ExitStatus` 를 `FAILED` 로 만드는 것과 예외를 던지는 것의 차이(후자만 Job 전체를 실패시킴)를 함께 설명합니다. 마지막에 이 검증 Step 을 `brokenSortJob` 에 붙였을 때의 실행 로그가 실려 있습니다 — 드디어 배치가 시끄럽게 실패합니다.

```java file="./Solution.java"
```
