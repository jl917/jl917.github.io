# Step 14 — 성능과 최종 프로젝트

> **학습 목표**
> - 생성 SQL 을 확인하는 5가지 방법을 상황에 맞게 골라 쓴다
> - Hibernate statistics 로 N+1 을 **쿼리 개수로** 진단한다
> - N+1 을 fetch join / `@BatchSize` / DTO 직접 조회 세 가지로 해결하고 선택 기준을 세운다
> - `exists` 와 `count` 의 실행시간 차이를 실측한다
> - QueryDSL 이 만든 SQL 을 그대로 `EXPLAIN` 에 넣어 인덱스 활용을 검증한다
> - 이 코스의 모든 기법을 써서 **상품 검색 API** 를 처음부터 끝까지 완성한다
>
> **선행 스텝**: [Step 13 — 고급 표현식](../step-13-advanced/)
> **예상 소요**: 150분

---

마지막 스텝입니다.

지금까지 13개 스텝에서 "이 QueryDSL 코드가 어떤 SQL 을 만드는가"를 봤습니다.
이 스텝은 질문을 하나 더 붙입니다. **"그 SQL 은 빠른가."**

그리고 코스의 마지막에 [14-9 절](#14-9-종합-실습--상품-검색-api)의 상품 검색 API 가 있습니다.
Step 04 의 동적 조건, Step 05 의 `@QueryProjection`, Step 06 의 조인, Step 07 의 서브쿼리,
Step 09 의 count 분리, Step 10 의 정렬 화이트리스트, Step 12 의 커스텀 리포지토리가
한 파일에 모입니다. 그것을 만들고 `EXPLAIN` 으로 검증하고 인덱스로 개선하면 이 코스는 끝입니다.

> 📌 MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 와
> [Step 16 — EXPLAIN과 옵티마이저](../../mysql8/step-16-explain-optimizer/) 를 옆에 펴 두십시오.
> 이 스텝은 그 두 스텝의 도구를 **QueryDSL 이 만든 SQL 에 적용**하는 스텝입니다.

---

## 14-1. 생성 SQL 확인법 총정리

지금까지 `org.hibernate.SQL: debug` 하나만 썼습니다.
운영에서 문제를 추적하려면 도구가 더 필요합니다. 다섯 가지를 정리합니다.

### ① `org.hibernate.SQL` + `org.hibernate.orm.jdbc.bind`

가장 기본입니다. [프로젝트 셋업 0-5 절](../project/) 에서 켰습니다.

```yaml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

**결과**

```sql
Hibernate:
    select
        c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
        c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
    from
        customers c1_0
    where
        c1_0.grade = ?
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [VIP]
```

| 장점 | 단점 |
|---|---|
| 설정 두 줄. 의존성 추가 없음 | SQL 과 바인딩이 **따로** 찍혀서 눈으로 합쳐야 함 |
| Hibernate 가 실제로 만든 SQL 그대로 | 파라미터가 많으면 대조가 고통스러움 |
| 모든 환경에서 동작 | 복사해서 바로 실행할 수 없음 |

> ⚠️ Hibernate 5 의 로거 이름은 `org.hibernate.type.descriptor.sql.BasicBinder` 였습니다.
> Hibernate 6 은 `org.hibernate.orm.jdbc.bind` 입니다.
> 옛 이름을 쓰면 **설정은 적용된 것처럼 보이는데 바인딩만 안 찍힙니다.** 에러도 안 납니다.

### ② `hibernate.format_sql` / `highlight_sql`

```yaml
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
        highlight_sql: true
        use_sql_comments: false
```

`format_sql` 없이 찍힌 SQL 은 이렇습니다.

```
Hibernate: select c1_0.customer_id,c1_0.city,c1_0.created_at,c1_0.email,c1_0.grade,c1_0.name,c1_0.phone,c1_0.points from customers c1_0 where c1_0.grade=?
```

읽을 수 없습니다. 이 코스가 처음부터 `format_sql: true` 로 시작한 이유입니다.

`use_sql_comments: true` 로 두면 SQL 앞에 JPQL 이 주석으로 붙습니다.

```sql
/* select customer from Customer customer where customer.grade = ?1 */
select c1_0.customer_id, ...
```

디버깅에는 유용하지만 로그가 두 배가 되고, MySQL 의 쿼리 통계에서 같은 쿼리가
다른 쿼리로 집계되는 부작용이 있습니다. 이 코스는 `false` 로 둡니다.

### ③ p6spy — 바인딩까지 완성된 SQL

①의 가장 큰 불편, "SQL 과 바인딩이 따로 찍힘"을 해결합니다.
p6spy 는 JDBC 드라이버를 감싸서 **실제로 실행된 SQL 을 값이 채워진 채로** 보여줍니다.

이건 `spring.jpa.properties` 설정이 아니라 **의존성**입니다.

```groovy
// build.gradle
implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.1'
```

의존성만 추가하면 자동 설정됩니다. 별도 코드가 필요 없습니다.

**결과**

```
2026-07-22 14:03:11.882  INFO 41233 --- [    Test worker] p6spy :
    select c1_0.customer_id,c1_0.city,c1_0.created_at,c1_0.email,c1_0.grade,
           c1_0.name,c1_0.phone,c1_0.points
    from customers c1_0
    where c1_0.grade='VIP'
```

`?` 대신 `'VIP'` 가 들어 있습니다. **이 SQL 을 그대로 복사해 MySQL 콘솔에 붙여넣을 수 있습니다.**
14-6 절에서 `EXPLAIN` 을 걸 때 이것이 결정적입니다.

| 장점 | 단점 |
|---|---|
| 복사해서 바로 실행 가능 | 의존성 추가 필요 |
| 실행 시간(ms)도 함께 찍힘 | JDBC 를 프록시하므로 **운영에서는 끄십시오** |
| 배치 실행도 개별 SQL 로 보임 | 로그량이 큼 |

> 💡 **실무 팁 — p6spy 는 로컬/개발 전용**
> `build.gradle` 에서 `developmentOnly` 로 두거나, 프로파일별 의존성으로 분리하십시오.
> 운영에 켜 두면 모든 쿼리의 파라미터가 로그에 남습니다.
> 개인정보가 그대로 로그 파일에 기록된다는 뜻입니다.

### ④ `JPAQuery.toString()` — JPQL 보기

SQL 이 아니라 **QueryDSL 이 만든 JPQL** 을 보고 싶을 때가 있습니다.
"내 자바 코드가 JPQL 로 제대로 번역됐는가"를 확인하는 단계입니다.

```java
JPAQuery<Customer> query = queryFactory
        .selectFrom(customer)
        .where(customer.grade.eq(Grade.VIP)
                .and(customer.points.goe(10000)))
        .orderBy(customer.points.desc());

System.out.println(query);      // toString()

List<Customer> result = query.fetch();
```

**결과**

```
select customer
from Customer customer
where customer.grade = ?1 and customer.points >= ?2
order by customer.points desc
```

**DB 에 나가지 않고** 문자열만 얻습니다. `fetch()` 를 부르지 않아도 됩니다.
Step 04 의 `or` 괄호 문제 같은 것은 SQL 까지 안 가고 여기서 잡힙니다.

```java
// 괄호가 어떻게 붙었는지 JPQL 로 확인
System.out.println(queryFactory.selectFrom(customer)
        .where(customer.city.eq("서울")
                .and(customer.grade.eq(Grade.VIP))
                .or(customer.points.goe(10000))));
```

```
select customer
from Customer customer
where customer.city = ?1 and customer.grade = ?2 or customer.points >= ?3
```

괄호가 없습니다. 이것이 Step 04 의 함정이었습니다. **한 줄로 확인할 수 있습니다.**

### ⑤ Hibernate statistics — 쿼리 개수 세기

**이것이 14-2 의 N+1 진단 도구입니다.**

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
logging:
  level:
    org.hibernate.stat: debug
```

트랜잭션이 끝날 때 요약이 찍힙니다.

**결과**

```
Session Metrics {
    1204583 nanoseconds spent acquiring 1 JDBC connections;
    0 nanoseconds spent releasing 0 JDBC connections;
    892041 nanoseconds spent preparing 11 JDBC statements;
    18344209 nanoseconds spent executing 11 JDBC statements;
    0 nanoseconds spent executing 0 JDBC batches;
    0 nanoseconds spent performing 0 L2C puts;
    0 nanoseconds spent performing 0 L2C hits;
    0 nanoseconds spent performing 0 L2C misses;
    0 nanoseconds spent executing 0 flushes (flushing a total of 0 entities and 0 collections);
    0 nanoseconds spent executing 0 partial-flushes
}
```

**`executing 11 JDBC statements`** — 이 숫자 하나가 진단의 시작입니다.

코드에서 직접 읽을 수도 있습니다.

```java
Statistics stats = em.getEntityManagerFactory()
        .unwrap(SessionFactory.class).getStatistics();
stats.clear();

// ... 쿼리 실행 ...

System.out.println("쿼리 개수: " + stats.getQueryExecutionCount());
System.out.println("엔티티 로드: " + stats.getEntityLoadCount());
System.out.println("컬렉션 페치: " + stats.getCollectionFetchCount());
```

> 💡 `generate_statistics: true` 는 약간의 오버헤드가 있습니다.
> 운영 상시 켜기보다는 **테스트에서 켜고 단언하는** 쪽이 유용합니다.
> "이 API 는 쿼리 3개 이하" 를 테스트로 고정해 두면 N+1 이 회귀할 때 CI 에서 잡힙니다.

### 정리

| 방법 | 보이는 것 | 쓰는 상황 |
|---|---|---|
| ① `hibernate.SQL` + bind | SQL + 바인딩(분리) | 항상 켜 둠. 기본값 |
| ② `format_sql` | 읽을 수 있는 형태 | 항상 켜 둠 |
| ③ p6spy | **실행 가능한 완성 SQL** + 시간 | `EXPLAIN` 걸 때, 성능 추적 |
| ④ `toString()` | **JPQL** | 번역이 의도대로인지 확인 |
| ⑤ statistics | **쿼리 개수** | N+1 진단, 테스트 단언 |

---

## 14-2. N+1 진단

가장 흔한 코드입니다. 주문 10건과 각 주문의 고객 이름을 출력합니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getId() + " → " + o.getCustomer().getName());
}
```

**결과 — 쿼리 로그**

```sql
Hibernate:
    select
        o1_0.order_id, o1_0.customer_id, o1_0.order_date,
        o1_0.shipping_city, o1_0.status, o1_0.total_amount
    from
        orders o1_0
    limit ?
```
```sql
Hibernate:
    select
        c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
        c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
    from
        customers c1_0
    where
        c1_0.customer_id = ?
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:BIGINT) <- [3]
```
```sql
Hibernate:
    select c1_0.customer_id, ... from customers c1_0 where c1_0.customer_id = ?
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:BIGINT) <- [7]
```
```
... (같은 쿼리가 8번 더) ...
```
```
Session Metrics {
    ...
    18344209 nanoseconds spent executing 11 JDBC statements;
    ...
}
```

**주문 10건을 조회하는 데 쿼리 11개.** 1 + N 입니다.

결과는 **완벽하게 맞습니다.** 고객 이름도 전부 정확합니다.
느릴 뿐입니다. 그리고 10건에서는 느린 것도 안 느껴집니다.

주문 600건을 전부 돌면 601개가 되고, 각 쿼리가 1ms 라도 0.6초입니다.
네트워크 왕복이 있는 운영 환경이면 5ms × 600 = 3초입니다.

> ⚠️ **함정 — N+1 은 에러가 아닙니다**
> 이 코스에서 반복한 주제입니다. N+1 은 **결과가 맞습니다.**
> 테스트도 통과합니다. 코드 리뷰에서도 이상해 보이지 않습니다.
> 개발 DB 의 데이터가 적을 때는 체감조차 안 됩니다.
>
> **유일한 발견 방법은 쿼리 개수를 세는 것**입니다. 눈으로 로그를 보다가는 놓칩니다.
> 특히 `open-in-view: true` 면 컨트롤러가 아니라 **JSON 직렬화 중**에 쿼리가 나가므로
> 서비스 계층 로그만 봐서는 보이지 않습니다.
> 이 코스가 `open-in-view: false` 를 기본으로 둔 이유입니다.

### 진단 체크리스트

| 단계 | 확인 | 방법 |
|---|---|---|
| 1 | 쿼리 개수를 센다 | `generate_statistics: true` 또는 `stats.getQueryExecutionCount()` |
| 2 | 같은 SQL 이 반복되는가 | 로그에서 동일 SQL 의 `where ... = ?` 반복 확인 |
| 3 | 반복 횟수 = 부모 건수인가 | 부모 10건 → 자식 쿼리 10개면 확정 |
| 4 | 어느 연관인가 | 반복되는 SQL 의 테이블명으로 특정 |
| 5 | 컬렉션인가 단일인가 | `@ManyToOne` 인지 `@OneToMany` 인지 → 처방이 갈림 |
| 6 | 페이징이 필요한가 | 필요하면 fetch join 을 쓸 수 없음 (컬렉션인 경우) |
| 7 | 수정이 필요한가 | 필요 없으면 DTO 직접 조회가 최선 |

5~7번이 14-3 의 선택 기준으로 이어집니다.

> 💡 **실무 팁 — 테스트로 고정하십시오**
> ```java
> @Test
> void 주문목록은_쿼리_2개_이하() {
>     stats.clear();
>     orderService.findRecentOrders(10);
>     assertThat(stats.getQueryExecutionCount()).isLessThanOrEqualTo(2);
> }
> ```
> 이런 테스트가 있으면 누군가 나중에 `fetchJoin()` 을 지웠을 때 **CI 에서 즉시 깨집니다.**
> N+1 은 코드 리뷰로는 못 막습니다. 숫자로 막으십시오.

---

## 14-3. N+1 해결 3가지

### ① fetch join — 쿼리 1개

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.customer, customer).fetchJoin()
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getId() + " → " + o.getCustomer().getName());
}
```

**결과**

```sql
Hibernate:
    select
        o1_0.order_id, c1_0.customer_id, c1_0.city, c1_0.created_at,
        c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points,
        o1_0.order_date, o1_0.shipping_city, o1_0.status, o1_0.total_amount
    from
        orders o1_0
    join
        customers c1_0 on c1_0.customer_id = o1_0.customer_id
    limit ?
```
```
Session Metrics {
    ...
    3102847 nanoseconds spent executing 1 JDBC statements;
    ...
}
```

**쿼리 11개 → 1개.** `customers` 의 전 컬럼이 `select` 절에 함께 실려 왔습니다.

그런데 이 방법에는 [Step 06 6-8 절](../step-06-joins/) 의 제약이 있습니다.

> ⚠️ **함정 — 컬렉션 fetch join + 페이징 = 전건 메모리 로딩**
>
> `@ManyToOne` (주문 → 고객) 은 위처럼 잘 됩니다. **1:1 이므로 행이 늘지 않습니다.**
> `@OneToMany` (주문 → 주문항목) 는 다릅니다.
>
> ```java
> queryFactory
>         .selectFrom(order)
>         .join(order.orderItems, orderItem).fetchJoin()
>         .offset(0).limit(10)                  // ★
>         .fetch();
> ```
>
> **결과**
> ```
> WARN o.h.h.internal.ast.QueryTranslatorImpl :
>     HHH90003004: firstResult/maxResults specified with collection fetch;
>     applying in memory
> ```
> ```sql
> select o1_0.order_id, oi1_0.order_id, oi1_0.order_item_id, ...
> from orders o1_0
> join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
> ```
> **`limit` 이 SQL 에 없습니다.**
>
> 조인으로 행이 1200건으로 뻥튀기되면 `limit 10` 을 SQL 에 붙일 수 없습니다.
> "주문 10건"이 아니라 "조인 결과 10행"이 되어 버리기 때문입니다.
> 그래서 Hibernate 는 **1200행을 전부 메모리로 읽고 자바에서 잘라냅니다.**
>
> 경고 로그 한 줄만 찍히고 **결과는 정확합니다.** 이것이 위험한 이유입니다.
> `orders` 가 600건이면 1200행이지만, 60만 건이면 120만 행을 힙에 올립니다. OOM 입니다.
>
> 그리고 컬렉션 fetch join 은 **하나만** 가능합니다.
> `orderItems` 와 `payments` 를 동시에 fetch join 하면
> `MultipleBagFetchException` 이 기동 시점 또는 쿼리 시점에 터집니다.

### ② `@BatchSize` / `default_batch_fetch_size` — 쿼리 2개

지연 로딩을 없애는 대신, **한 번에 모아서** 가져오게 합니다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

또는 특정 연관에만:

```java
@BatchSize(size = 100)
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
private List<OrderItem> orderItems = new ArrayList<>();
```

코드는 **아무것도 바꾸지 않습니다.**

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getId() + " → " + o.getOrderItems().size());
}
```

**결과**

```sql
Hibernate:
    select
        o1_0.order_id, o1_0.customer_id, o1_0.order_date,
        o1_0.shipping_city, o1_0.status, o1_0.total_amount
    from
        orders o1_0
    limit ?
```
```sql
Hibernate:
    select
        oi1_0.order_id, oi1_0.order_item_id, oi1_0.product_id,
        oi1_0.quantity, oi1_0.unit_price
    from
        order_items oi1_0
    where
        oi1_0.order_id in (?,?,?,?,?,?,?,?,?,?)
```
```
TRACE o.h.orm.jdbc.bind : binding parameter (1:BIGINT) <- [1]
TRACE o.h.orm.jdbc.bind : binding parameter (2:BIGINT) <- [2]
...
TRACE o.h.orm.jdbc.bind : binding parameter (10:BIGINT) <- [10]
```
```
Session Metrics {
    ...
    5218904 nanoseconds spent executing 2 JDBC statements;
    ...
}
```

**쿼리 11개 → 2개.** `where ... in (?,?,?,...)` 가 핵심입니다.

첫 번째 지연 로딩이 발생하는 순간, Hibernate 가
**"어차피 이 컬렉션들도 곧 필요하겠지"** 하고 최대 `batch_fetch_size` 개를 모아 한 번에 가져옵니다.

**그리고 `limit` 이 SQL 에 그대로 있습니다.** 페이징과 공존합니다.

| `batch_fetch_size` | 부모 1000건일 때 쿼리 수 |
|---:|---:|
| 없음 | 1 + 1000 = 1001 |
| 10 | 1 + 100 = 101 |
| 100 | 1 + 10 = 11 |
| 1000 | 1 + 1 = 2 |

> 💡 **실무 팁 — 100~1000 이 실무 기본값**
> 너무 작으면 효과가 없고, 너무 크면 `in` 절이 길어져 SQL 파싱 비용과
> DB 의 `max_allowed_packet` 문제가 생깁니다.
> **100 에서 시작하고, 부모 건수가 큰 화면에서 1000 까지 올려 보십시오.**
> 이 코스의 `application.yml` 은 `default_batch_fetch_size: 100` 으로 설정돼 있습니다.
>
> 전역 설정을 켜 두는 것을 권합니다. `@BatchSize` 를 연관마다 붙이는 방식은
> 새 연관을 추가할 때 빠뜨리기 쉽습니다. 전역이 기본, `@BatchSize` 는 예외 조정용입니다.

### ③ DTO 직접 조회 — 쿼리 1개, 엔티티 없음

[Step 05](../step-05-projections/) 의 방법입니다. 애초에 엔티티를 만들지 않습니다.

```java
List<OrderSummary> result = queryFactory
        .select(new QOrderSummary(
                order.id,
                order.orderDate,
                order.totalAmount,
                customer.name,
                customer.grade))
        .from(order)
        .join(order.customer, customer)
        .limit(10)
        .fetch();
```

**결과**

```sql
Hibernate:
    select
        o1_0.order_id,
        o1_0.order_date,
        o1_0.total_amount,
        c1_0.name,
        c1_0.grade
    from
        orders o1_0
    join
        customers c1_0 on c1_0.customer_id = o1_0.customer_id
    limit ?
```
```
Session Metrics {
    ...
    2104338 nanoseconds spent executing 1 JDBC statements;
    ...
}
```

**쿼리 1개, 그리고 컬럼이 5개뿐입니다.**
fetch join(①)은 양쪽 엔티티의 **전 컬럼 13개**를 읽었습니다.

대신 잃는 것이 있습니다.

- **영속성 컨텍스트가 관리하지 않습니다.** 변경 감지(dirty checking)가 없습니다.
  수정하려면 별도로 엔티티를 조회해야 합니다.
- **지연 로딩이 없습니다.** DTO 에 없는 필드는 그냥 없습니다.
- DTO 를 화면 요구사항에 맞춰 만들어야 하므로 **DTO 클래스가 늘어납니다.**

### 선택 기준

| 상황 | ① fetch join | ② batch size | ③ DTO |
|---|:---:|:---:|:---:|
| 단일 연관 (`@ManyToOne`) 만 | ✅ 최선 | ✅ | ✅ |
| 컬렉션 (`@OneToMany`) + **페이징 없음** | ✅ | ✅ | △ (중복 행 처리 필요) |
| 컬렉션 + **페이징 있음** | ❌ **전건 메모리** | ✅ **최선** | ✅ |
| 컬렉션 **2개 이상** | ❌ `MultipleBagFetchException` | ✅ **최선** | ✅ |
| 조회 후 **수정** 필요 | ✅ | ✅ | ❌ 엔티티가 아님 |
| **읽기 전용** 화면 | ✅ | ✅ | ✅ **최선** |
| 필요한 컬럼이 **일부** | △ 전 컬럼 읽음 | △ | ✅ **최선** |
| 데이터가 **아주 큼** | △ | ✅ | ✅ **최선** |
| 코드 변경량 | 쿼리 수정 | **설정만** | DTO + 쿼리 |

**실무의 기본 조합**은 이것입니다.

1. **`default_batch_fetch_size: 100` 을 전역으로 켭니다.** 안전망입니다.
2. **읽기 전용 조회 API 는 DTO 직접 조회**로 만듭니다. 가장 빠르고 예측 가능합니다.
3. **수정이 필요한 흐름에서만 엔티티를 fetch join** 으로 가져옵니다.

②는 "실수해도 최악은 면하게" 하는 장치이고,
③은 "제대로 만든" 조회이며,
①은 "수정할 것"을 가져오는 방법입니다. 역할이 다릅니다.

---

## 14-4. `exists` vs `count`

"주문이 한 건이라도 있는 고객" 을 찾습니다.
흔히 이렇게 씁니다.

```java
// ❌ 전부 셉니다
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(JPAExpressions
                .select(order.count())
                .from(order)
                .where(order.customer.eq(customer))
                .gt(0L))
        .fetch();
```

**결과**

```sql
select
    c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
    c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from
    customers c1_0
where
    (select count(o1_0.order_id)
     from orders o1_0
     where o1_0.customer_id = c1_0.customer_id) > ?
```

```
조회 30건 (0.180 sec)
```

`count(...)` 는 **조건에 맞는 행을 끝까지 다 셉니다.**
어떤 고객이 주문 200건을 가지고 있어도 200건을 전부 세고 나서 `> 0` 을 판정합니다.
우리가 알고 싶은 것은 "1건이라도 있는가"인데 200번을 셉니다.

```java
// ✅ 첫 행에서 멈춥니다
List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(JPAExpressions
                .selectOne()
                .from(order)
                .where(order.customer.eq(customer))
                .exists())
        .fetch();
```

**결과**

```sql
select
    c1_0.customer_id, c1_0.city, c1_0.created_at, c1_0.email,
    c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from
    customers c1_0
where
    exists (select 1
            from orders o1_0
            where o1_0.customer_id = c1_0.customer_id)
```

```
조회 30건 (0.002 sec)
```

**0.180초 → 0.002초. 약 90배입니다.**

`EXISTS` 는 **첫 번째 매칭 행을 찾는 순간 그 서브쿼리를 끝냅니다.**
`shop` 의 고객 30명은 모두 주문이 있으므로, 각 고객마다 인덱스에서 첫 행 하나만 찾고 끝납니다.

`EXPLAIN` 으로 확인하면 차이가 분명합니다.

```
-- count > 0
| id | select_type        | table     | type | key                 | rows | Extra       |
|  1 | PRIMARY            | customers | ALL  | NULL                |   30 | Using where |
|  2 | DEPENDENT SUBQUERY | orders    | ref  | idx_orders_customer |   20 | Using index |

-- exists
| id | select_type        | table     | type | key                 | rows | Extra                    |
|  1 | PRIMARY            | customers | ALL  | NULL                |   30 | Using where              |
|  2 | DEPENDENT SUBQUERY | orders    | ref  | idx_orders_customer |    1 | Using index; FirstMatch  |
```

서브쿼리의 `rows` 가 **20 → 1** 입니다. `FirstMatch` 가 "첫 매치에서 중단"을 뜻합니다.

> 📌 MySQL8 코스 [Step 08 — 서브쿼리](../../mysql8/step-08-subqueries/) 의 `IN` vs `EXISTS` 와
> 같은 이야기입니다. SQL 에서 배운 원칙이 QueryDSL 에서도 그대로입니다.
> QueryDSL 은 SQL 을 만드는 도구이지 SQL 의 규칙을 바꾸는 도구가 아닙니다.

단건 존재 확인도 마찬가지입니다.

```java
// ❌ 엔티티를 만들고 버림
boolean exists = queryFactory.selectFrom(customer)
        .where(customer.email.eq(email))
        .fetchFirst() != null;

// ✅ 1만 읽음
Integer found = queryFactory.selectOne()
        .from(customer)
        .where(customer.email.eq(email))
        .fetchFirst();
boolean exists = found != null;
```

```sql
-- ❌ select c1_0.customer_id, c1_0.city, ... (8컬럼) from customers c1_0 where c1_0.email = ? limit ?
-- ✅ select 1 from customers c1_0 where c1_0.email = ? limit ?
```

`fetchFirst()` 는 `limit 1` 을 붙입니다. 두 번째 형태는 **컬럼을 하나도 안 읽습니다.**

> 💡 **실무 팁 — `fetchCount()` 는 6.x 에서도 쓰지 마십시오**
> QueryDSL 5.x 부터 `fetchCount()` / `fetchResults()` 는 deprecated 입니다.
> `group by`, `having`, `distinct` 가 있는 쿼리에서 count 쿼리를 잘못 만드는 경우가 있기 때문입니다.
> `select(x.count())` 로 **직접** count 쿼리를 쓰십시오. [Step 09](../step-09-sorting-paging/) 참고.

---

## 14-5. 필요한 컬럼만

`products` 40행에서는 아무 차이가 없습니다. 그래서 이 절이 필요합니다.

```java
// A — 엔티티 전체
List<Product> entities = queryFactory
        .selectFrom(product)
        .fetch();
```

```sql
select
    p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
    p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from
    products p1_0
```

```java
// B — 필요한 두 컬럼만
List<Tuple> tuples = queryFactory
        .select(product.name, product.price)
        .from(product)
        .fetch();
```

```sql
select
    p1_0.name, p1_0.price
from
    products p1_0
```

| | A (엔티티) | B (프로젝션) |
|---|---:|---:|
| 읽는 컬럼 | 8 | 2 |
| 40행 실행시간 | 0.004초 | 0.003초 |
| 40행 힙 사용 | ~18 KB | ~5 KB |
| 100만 행 실행시간 (추정) | 4.2초 | 0.9초 |
| 100만 행 힙 사용 (추정) | ~450 MB | ~120 MB |
| 영속성 컨텍스트 등록 | 40개 | 0개 |

40행에서는 **차이가 오차 범위**입니다. 그래서 개발 중에는 절대 보이지 않습니다.

100만 행에서는 다릅니다. 그리고 엔티티 조회에는 컬럼 읽기 말고도 비용이 있습니다.

- **1차 캐시 등록** — 40개면 무시할 수 있지만 100만 개는 힙을 먹습니다
- **스냅샷 저장** — 변경 감지를 위해 원본 값을 복사해 둡니다. **메모리가 두 배**입니다
- **플러시 시 비교** — 트랜잭션 종료 시 전 엔티티를 스냅샷과 대조합니다

읽기 전용 조회에서 이 세 가지는 전부 낭비입니다.
14-8 절의 `@Transactional(readOnly = true)` 가 뒤 두 개를 없애 줍니다.

> 💡 **실무 팁 — "일단 엔티티로 받고 나중에 최적화" 가 안 되는 이유**
> 엔티티를 DTO 로 바꾸는 리팩터링은 **호출부를 전부 고쳐야** 합니다.
> 지연 로딩에 기대던 코드가 여기저기 있기 때문입니다.
> 조회 API 는 **처음부터 DTO** 로 만드십시오. 나중에 바꾸는 비용이 훨씬 큽니다.

---

## 14-6. 인덱스 활용 확인 — QueryDSL 이 만든 SQL 을 EXPLAIN 에

여기가 이 코스와 MySQL8 코스가 만나는 지점입니다.

### 절차

1. **p6spy 로그에서 완성된 SQL 을 복사합니다.** (14-1 ③)
   `?` 가 있는 SQL 은 `EXPLAIN` 에 넣을 수 없으므로 p6spy 가 필요합니다.
2. MySQL 콘솔에 붙이고 앞에 `EXPLAIN` 을 붙입니다.
3. `type`, `key`, `rows`, `Extra` 를 봅니다.

```java
List<Product> result = queryFactory
        .selectFrom(product)
        .where(product.status.eq(ProductStatus.ON_SALE)
                .and(product.price.between(new BigDecimal("100000"), new BigDecimal("500000"))))
        .orderBy(product.price.desc())
        .fetch();
```

p6spy 로그:

```sql
select p1_0.product_id,p1_0.category_id,p1_0.cost,p1_0.created_at,
       p1_0.name,p1_0.price,p1_0.status,p1_0.stock
from products p1_0
where p1_0.status='ON_SALE' and p1_0.price between 100000 and 500000
order by p1_0.price desc
```

```sql
EXPLAIN select p1_0.product_id, ... from products p1_0
where p1_0.status='ON_SALE' and p1_0.price between 100000 and 500000
order by p1_0.price desc;
```

**결과**

```
+----+-------------+----------+------+---------------+------+---------+------+------+----------+-----------------------------+
| id | select_type | table    | type | possible_keys | key  | key_len | ref  | rows | filtered | Extra                       |
+----+-------------+----------+------+---------------+------+---------+------+------+----------+-----------------------------+
|  1 | SIMPLE      | p1_0     | ALL  | NULL          | NULL | NULL    | NULL |   40 |     3.70 | Using where; Using filesort |
+----+-------------+----------+------+---------------+------+---------+------+------+----------+-----------------------------+
```

### 찾아야 할 세 가지

| 신호 | 의미 | 대응 |
|---|---|---|
| **`type: ALL`** | 풀 테이블 스캔. 인덱스를 전혀 안 씀 | `where` 컬럼에 인덱스 |
| **`Using filesort`** | 별도 정렬 단계. 인덱스 순서를 못 씀 | 정렬 컬럼을 인덱스에 포함 |
| **`Using temporary`** | 임시 테이블 생성 (`group by`/`distinct`) | 인덱스로 정렬 순서 제공 |

`products` 40행에서 `type: ALL` 은 정상입니다.
[MySQL8 Step 16](../../mysql8/step-16-explain-optimizer/) 에서 본 것처럼,
작은 테이블은 옵티마이저가 **일부러** 풀스캔을 고릅니다.
**`type` 은 항상 `rows` 와 함께 해석하십시오.**

인덱스를 만들면 계획이 바뀝니다.

```sql
ALTER TABLE products ADD INDEX idx_products_status_price (status, price);

EXPLAIN select ... from products p1_0
where p1_0.status='ON_SALE' and p1_0.price between 100000 and 500000
order by p1_0.price desc;
```

**결과**

```
+----+-------------+-------+-------+---------------------------+---------------------------+---------+------+------+----------+-----------------------+
| id | select_type | table | type  | possible_keys             | key                       | key_len | ref  | rows | filtered | Extra                 |
+----+-------------+-------+-------+---------------------------+---------------------------+---------+------+------+----------+-----------------------+
|  1 | SIMPLE      | p1_0  | range | idx_products_status_price | idx_products_status_price | 47      | NULL |   11 |   100.00 | Using index condition |
+----+-------------+-------+-------+---------------------------+---------------------------+---------+------+------+----------+-----------------------+
```

`ALL` → `range`, `rows` 40 → 11, 그리고 **`Using filesort` 가 사라졌습니다.**
`(status, price)` 복합 인덱스가 `status` 로 범위를 좁히고 `price` 순서를 그대로 제공하기 때문입니다.

> 📌 복합 인덱스의 선두 컬럼 규칙은 MySQL8 코스
> [Step 15 5절](../../mysql8/step-15-indexes/) 에서 자세히 다룹니다.
> `(status, price)` 는 `status` 단독 조회에도 쓰이지만 `price` 단독 조회에는 쓰이지 않습니다.

### QueryDSL 코드에서 인덱스를 죽이는 4가지 패턴

이 코스 전체에서 흩어져 나왔던 함정들을 여기서 한 표로 모읍니다.

#### 패턴 1 — 컬럼에 함수를 씌운다

```java
// ❌
.where(order.orderDate.year().eq(2025))
.where(customer.name.lower().eq("김서준"))
.orderBy(new CaseBuilder().when(...).then(...).otherwise(...).desc())
```

```sql
where extract(year from o1_0.order_date) = ?
where lower(c1_0.name) = ?
order by case when ... end desc
```

인덱스는 **컬럼 값** 순으로 정렬돼 있습니다. 함수의 결과는 컬럼 값이 아닙니다.

**대안**

```java
// ✅ 범위로 바꿉니다
.where(order.orderDate.goe(LocalDateTime.of(2025, 1, 1, 0, 0))
        .and(order.orderDate.lt(LocalDateTime.of(2026, 1, 1, 0, 0))))

// ✅ 콜레이션으로 해결합니다 (utf8mb4_0900_ai_ci 는 이미 대소문자 무시)
.where(customer.name.eq("김서준"))

// ✅ 정렬용 컬럼을 두거나, 애플리케이션에서 정렬합니다
```

함수 기반 인덱스(MySQL 8.0.13+)로 푸는 방법도 있습니다.
`ALTER TABLE orders ADD INDEX idx_year ((YEAR(order_date)));`
다만 이건 그 함수를 쓰는 쿼리 하나만을 위한 인덱스입니다. 범위 조건이 대개 낫습니다.

#### 패턴 2 — 앞에 `%` 가 붙는 LIKE

```java
// ❌ contains 는 '%키워드%' 를 만듭니다
.where(product.name.contains("노트북"))
```

```sql
where p1_0.name like ? escape '!'      -- '%노트북%'
```

```
| type | key  | rows | Extra       |
| ALL  | NULL |   40 | Using where |
```

인덱스는 **앞에서부터** 비교합니다. 앞이 와일드카드면 시작점을 찾을 수 없습니다.

**대안**

```java
// ✅ 앞부분 일치 — 인덱스 사용 가능
.where(product.name.startsWith("노트북"))
```

```sql
where p1_0.name like ? escape '!'      -- '노트북%'
```

```
| type  | key              | rows | Extra                 |
| range | idx_products_name|    2 | Using index condition |
```

같은 `like` 인데 계획이 다릅니다. **패턴의 첫 글자가 와일드카드인지가 전부입니다.**

앞뒤 모두 일치가 요구사항이라면 선택지는 셋입니다.

| 방법 | 설명 |
|---|---|
| FULLTEXT 인덱스 | MySQL 내장. `MATCH ... AGAINST`. 한국어는 `ngram` 파서 필요 |
| 검색 엔진 | Elasticsearch, OpenSearch 등. 규모가 크면 정답 |
| 그냥 둔다 | 40행, 4만 행이면 풀스캔이 더 빠릅니다. **재고 나서 결정하십시오** |

> 📌 FULLTEXT 는 MySQL8 코스 [Step 15 10절](../../mysql8/step-15-indexes/) 에 있습니다.

#### 패턴 3 — 타입 불일치

```java
// customer.id 는 NumberPath<Long>, DB 는 BIGINT — 이 자체는 문제 없습니다.
// 문제는 문자열 컬럼에 숫자를 비교하거나 그 반대일 때 생깁니다.
```

QueryDSL 은 타입이 다르면 **컴파일 에러**를 냅니다.
`customer.name.eq(123)` 은 컴파일되지 않습니다. 이것이 QueryDSL 의 큰 장점입니다.

타입 불일치가 남아 있을 수 있는 곳은 **템플릿**입니다.

```java
// ❌ 템플릿에서는 타입 검사가 없습니다
Expressions.booleanTemplate("{0} = {1}", customer.id, "3")
```

```sql
where c1_0.customer_id = '3'
```

MySQL 은 `BIGINT` 와 문자열을 비교할 때 **컬럼 쪽을 숫자로 캐스팅**하므로
이 경우에는 인덱스를 쓸 수 있습니다. 하지만 반대 방향
(`VARCHAR` 컬럼에 숫자를 비교) 이면 **컬럼이 캐스팅되어 인덱스를 잃습니다.**

```sql
-- phone 은 VARCHAR
where c1_0.phone = 1012345678       -- ❌ 컬럼이 숫자로 캐스팅됨 → 인덱스 못 씀
where c1_0.phone = '1012345678'     -- ✅
```

**대안**: 템플릿을 쓰지 않으면 이 문제는 발생하지 않습니다. Q타입 경로 메서드를 쓰십시오.

#### 패턴 4 — `or` 남발

```java
// ❌
.where(product.name.eq("노트북")
        .or(product.status.eq(ProductStatus.SOLD_OUT))
        .or(product.stock.eq(0)))
```

```sql
where p1_0.name = ? or p1_0.status = ? or p1_0.stock = ?
```

```
| type | key  | rows | Extra       |
| ALL  | NULL |   40 | Using where |
```

`and` 는 조건을 좁히지만 `or` 는 넓힙니다.
서로 다른 컬럼을 `or` 로 묶으면 **어느 인덱스로도 전체를 커버할 수 없어** 풀스캔이 되기 쉽습니다.

**대안**

```java
// ✅ 같은 컬럼의 or 는 in 으로
.where(product.status.in(ProductStatus.SOLD_OUT, ProductStatus.HIDDEN))
```

```sql
where p1_0.status in (?,?)          -- 인덱스 사용 가능
```

```java
// ✅ 다른 컬럼이면 union 으로 쪼갭니다 (각 쿼리가 자기 인덱스를 씁니다)
List<Product> byName = queryFactory.selectFrom(product)
        .where(product.name.eq("노트북")).fetch();
List<Product> byStock = queryFactory.selectFrom(product)
        .where(product.stock.eq(0)).fetch();
// 자바에서 합칩니다 (JPQL 은 UNION 을 지원하지 않습니다)
```

> ⚠️ JPQL 에는 `UNION` 이 없습니다. Hibernate 6 의 HQL 은 지원하지만 QueryDSL-JPA 로는 만들 수 없습니다.
> 위처럼 두 번 조회해 자바에서 합치거나, 네이티브 쿼리를 쓰십시오.

MySQL 이 `index_merge` 로 여러 인덱스를 합쳐 쓰는 경우도 있지만,
옵티마이저가 항상 고르지는 않고 비용도 큽니다. **`EXPLAIN` 으로 확인하십시오.**

### 4가지 패턴 요약

| 패턴 | QueryDSL 코드 | 증상 | 대안 |
|---|---|---|---|
| 컬럼에 함수 | `.year()`, `.lower()`, `case` 정렬 | `type: ALL`, `Using filesort` | 범위 조건, 콜레이션, 정렬용 컬럼 |
| 앞 `%` LIKE | `.contains("x")` | `type: ALL` | `.startsWith()`, FULLTEXT, 검색엔진 |
| 타입 불일치 | 템플릿 안의 비교 | 컬럼 쪽 캐스팅 → 인덱스 상실 | Q타입 경로 메서드 사용 |
| `or` 남발 | `.or().or().or()` | `type: ALL` | 같은 컬럼은 `in`, 다른 컬럼은 쿼리 분리 |

---

## 14-7. 배치 insert / update 설정

조회 이야기만 했으니 쓰기도 짚습니다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100
        order_inserts: true
        order_updates: true
        batch_versioned_data: true
```

| 설정 | 하는 일 |
|---|---|
| `jdbc.batch_size` | 같은 SQL 을 N개까지 모아 **한 번에 전송** (JDBC `addBatch`) |
| `order_inserts` | INSERT 를 **엔티티 타입별로 정렬**해 배치가 끊기지 않게 함 |
| `order_updates` | UPDATE 를 같은 방식으로 정렬 |
| `batch_versioned_data` | `@Version` 이 있는 엔티티도 배치 대상에 포함 |

`order_inserts` 가 없으면 이런 일이 벌어집니다.

```
persist(Order A)      → INSERT orders     ┐
persist(OrderItem A1) → INSERT order_items│ 타입이 바뀔 때마다
persist(Order B)      → INSERT orders     │ 배치가 끊깁니다
persist(OrderItem B1) → INSERT order_items┘
```

배치는 **연속된 같은 SQL** 에만 적용됩니다. 위 순서면 배치가 하나도 안 묶입니다.
`order_inserts: true` 를 켜면 Hibernate 가 플러시 직전에 타입별로 정렬해
`orders` INSERT 를 모아 한 배치, `order_items` INSERT 를 모아 한 배치로 만듭니다.

**결과** — statistics 비교 (주문 100건 + 항목 300건 저장)

```
-- batch_size 없음
executing 400 JDBC statements;
executing 0 JDBC batches;
소요: 1.842초

-- batch_size: 100 + order_inserts: true
executing 0 JDBC statements;
executing 4 JDBC batches;
소요: 0.211초
```

**1.842초 → 0.211초.**

> ⚠️ **함정 — `GenerationType.IDENTITY` 는 배치 INSERT 가 안 됩니다**
> `@GeneratedValue(strategy = GenerationType.IDENTITY)` 는 INSERT 를 실행해야
> 생성된 ID 를 알 수 있으므로, Hibernate 가 **INSERT 를 미룰 수 없습니다.**
> `persist()` 하는 즉시 INSERT 가 나갑니다. 배치가 원천적으로 불가능합니다.
>
> 이 코스의 엔티티는 전부 `IDENTITY` 입니다. MySQL 의 `AUTO_INCREMENT` 를 쓰기 때문입니다.
> **대량 INSERT 성능이 중요하다면 `SEQUENCE` 또는 `TABLE` 전략을 검토하십시오.**
> MySQL 에는 시퀀스가 없으므로 `TABLE` 전략이나 애플리케이션 측 ID 생성이 필요합니다.
> 설정만 켜고 "왜 배치가 안 되지" 하는 경우의 90%가 이것입니다.

### QueryDSL 벌크 연산과의 역할 분담

[Step 11](../step-11-bulk-operations/) 의 벌크 연산은 완전히 다른 도구입니다.

| | JPA 배치 (`batch_size`) | QueryDSL 벌크 (Step 11) |
|---|---|---|
| SQL | `INSERT INTO ... VALUES (...)` × N (배치 전송) | `UPDATE ... WHERE ...` **1개** |
| 영속성 컨텍스트 | 거쳐 감. 변경 감지 동작 | **건너뜀** |
| 대상 | 개별 엔티티를 저장/수정할 때 | 조건에 맞는 전체를 한 번에 |
| 100만 행 수정 | 100만 건 로드 필요. 비현실적 | UPDATE 1개. 즉시 |
| 함정 | `IDENTITY` 면 배치 안 됨 | **1차 캐시에 옛 값이 남음** → `em.clear()` 필수 |

"조건에 맞는 것 전부를 같은 값으로" → 벌크.
"각각 다른 값으로 저장" → JPA 배치.

---

## 14-8. 읽기 전용 최적화

```java
@Transactional(readOnly = true)
public List<ProductSearchResponse> search(ProductSearchCond cond, Pageable pageable) {
    ...
}
```

`readOnly = true` 가 하는 일은 두 가지입니다.

### ① 플러시 모드를 `MANUAL` 로

Hibernate 는 기본적으로 쿼리 실행 전에 **자동 플러시**를 합니다.
"수정한 내용이 쿼리 결과에 반영되어야 하니까" 입니다.
읽기만 하는 트랜잭션에는 이 플러시가 전부 낭비입니다.

`readOnly = true` 는 플러시 모드를 `MANUAL` 로 바꿔 자동 플러시를 끕니다.
쿼리를 10번 날리면 자동 플러시 10번이 사라집니다.

### ② 스냅샷 비교 생략

엔티티를 조회하면 Hibernate 는 변경 감지를 위해 **원본 값의 복사본(스냅샷)** 을 만듭니다.
트랜잭션이 끝날 때 현재 값과 스냅샷을 필드 단위로 비교해 UPDATE 를 만듭니다.

읽기 전용이면 이 비교가 필요 없습니다.
**메모리도 절반**입니다. 스냅샷을 안 만들기 때문입니다.

**결과** — 상품 40건 조회 (엔티티)

```
-- @Transactional
executing 1 JDBC statements;
executing 1 flushes (flushing a total of 40 entities and 0 collections);
힙 사용: ~18 KB

-- @Transactional(readOnly = true)
executing 1 JDBC statements;
executing 0 flushes (flushing a total of 0 entities and 0 collections);
힙 사용: ~9 KB
```

`flushes` 가 0 이 되고 힙이 절반입니다.

> 💡 **실무 팁 — 서비스 클래스에 `readOnly = true` 를 기본으로**
> ```java
> @Service
> @Transactional(readOnly = true)      // 클래스 레벨 기본값
> public class ProductService {
>
>     public List<ProductSearchResponse> search(...) { ... }   // 읽기
>
>     @Transactional                                            // 쓰기만 오버라이드
>     public void updateStock(Long id, int stock) { ... }
> }
> ```
> 조회 메서드가 훨씬 많으므로 이쪽이 자연스럽습니다.
> 그리고 **읽기 전용 트랜잭션에서 실수로 수정하면 조용히 무시됩니다.**
> `readOnly` 를 명시적으로 벗기는 행위가 곧 "여기서 쓴다"는 선언이 됩니다.

DTO 직접 조회(14-3 ③)에서는 애초에 엔티티를 안 만드므로 ②의 이득은 없습니다.
그래도 ①의 자동 플러시 제거 효과는 그대로이므로 붙이는 것이 낫습니다.

> ⚠️ `readOnly = true` 는 **DB 트랜잭션을 읽기 전용으로 만들지 않습니다.**
> JDBC 커넥션에 `setReadOnly(true)` 가 전달되긴 하지만, MySQL 드라이버가
> 이것으로 무엇을 하는지는 설정에 따라 다릅니다.
> **네이티브 쿼리로 UPDATE 를 날리면 그냥 실행됩니다.**
> `readOnly` 는 Hibernate 의 최적화 힌트이지 안전장치가 아닙니다.

---

## 14-9. 종합 실습 — 상품 검색 API

이 코스의 최종 프로젝트입니다.
14개 스텝의 기법이 한 파일에 모입니다.

### 요구사항

| 항목 | 내용 | 관련 스텝 |
|---|---|---|
| 키워드 | 상품명 부분 일치 | [04](../step-04-where-conditions/) |
| 카테고리 | 선택한 카테고리 **+ 그 하위 카테고리** | [07](../step-07-subqueries/) |
| 가격 범위 | 최소/최대 (둘 다 선택) | [04](../step-04-where-conditions/) |
| 재고 유무 | "재고 있는 것만" 토글 | [04](../step-04-where-conditions/) |
| 상태 | `ON_SALE` / `SOLD_OUT` / `HIDDEN` 다중 선택 | [04](../step-04-where-conditions/) |
| 정렬 | 가격 / 평점 / 최신순, 오름·내림 | [10](../step-10-dynamic-sort/) |
| 페이징 | `Pageable`, count 쿼리 분리 | [09](../step-09-sorting-paging/) [12](../step-12-spring-data/) |
| 응답 | 상품명, 가격, 카테고리명, **평균 평점**, **후기 수** | [05](../step-05-projections/) [08](../step-08-aggregation/) |

**후기 없는 상품 24개** 가 여기서 재료가 됩니다.
평균 평점이 NULL 인 상품이 24개 나와야 합니다.

### 조건 객체와 응답 DTO

```java
package com.example.shop.step14;

import com.example.shop.entity.ProductStatus;

import java.math.BigDecimal;
import java.util.List;

public record ProductSearchCond(
        String keyword,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Boolean inStockOnly,
        List<ProductStatus> statuses,
        String sort,            // "price" | "rating" | "created"
        String direction        // "asc" | "desc"
) {}
```

```java
package com.example.shop.step14;

import com.example.shop.entity.ProductStatus;
import com.querydsl.core.annotations.QueryProjection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductSearchResponse(
        Long productId,
        String name,
        BigDecimal price,
        Integer stock,
        ProductStatus status,
        String categoryName,
        Double avgRating,
        Long reviewCount,
        LocalDateTime createdAt
) {
    @QueryProjection
    public ProductSearchResponse {
    }
}
```

`record` 의 **compact constructor** 에 `@QueryProjection` 을 붙입니다.
`./gradlew compileJava` 후 `QProductSearchResponse` 가 생성됩니다.

> ⚠️ **함정 — `@QueryProjection` 을 붙였는데 Q클래스가 안 생깁니다**
> `@QueryProjection` 은 **`querydsl-apt` 가 처리**합니다.
> DTO 가 `src/test/java` 에 있으면 `testAnnotationProcessor` 설정이 필요합니다.
> ```groovy
> testAnnotationProcessor "io.github.openfeign.querydsl:querydsl-apt:${querydslVersion}:jpa"
> ```
> 이 실습에서는 DTO 를 `src/main/java/com/example/shop/step14/` 에 두는 것을 권합니다.
> 그리고 `@QueryProjection` 을 쓰면 **DTO 가 QueryDSL 에 의존**하게 됩니다.
> 도메인 최상단 DTO 에는 붙이지 않고, 조회 전용 DTO 에만 쓰는 것이 관례입니다.

### 리포지토리 인터페이스 — Step 12 구조

```java
package com.example.shop.step14;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepositoryCustom {
    Page<ProductSearchResponse> search(ProductSearchCond cond, Pageable pageable);
    Page<ProductSearchResponse> searchWithGroupBy(ProductSearchCond cond, Pageable pageable);
}
```

```java
package com.example.shop.step14;

import com.example.shop.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository
        extends JpaRepository<Product, Long>, ProductRepositoryCustom {
}
```

> ⚠️ 구현 클래스 이름은 **반드시 `ProductRepositoryImpl`** 입니다.
> `ProductRepositoryCustomImpl` 도 인식되지만(Spring Data 2.x+),
> `ProductRepositoryImplementation` 이나 `ProductRepositoryimpl` 은 안 됩니다.
> 빈 등록이 조용히 실패하고, 에러 메시지는 엉뚱한 곳을 가리킵니다.
> [Step 12](../step-12-spring-data/) 의 함정입니다.

### 구현체 — 버전 A (서브쿼리)

```java
package com.example.shop.step14;

import com.example.shop.entity.ProductStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QReview.review;

@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    // ── 정렬 화이트리스트 (Step 10) ─────────────────────────────────
    // 사용자 입력은 이 Map 의 "키"로만 쓰입니다.
    // SQL 에 들어가는 것은 코드에 이미 존재하는 표현식뿐입니다.
    private static final Map<String, OrderSpecifier<?>> SORT_ASC = Map.of(
            "price",   product.price.asc(),
            "created", product.createdAt.asc(),
            "name",    product.name.asc());

    private static final Map<String, OrderSpecifier<?>> SORT_DESC = Map.of(
            "price",   product.price.desc(),
            "created", product.createdAt.desc(),
            "name",    product.name.desc());

    private static final OrderSpecifier<?> DEFAULT_SORT = product.id.desc();

    @Override
    public Page<ProductSearchResponse> search(ProductSearchCond cond, Pageable pageable) {

        // ── 콘텐츠 쿼리 ────────────────────────────────────────────
        List<ProductSearchResponse> content = queryFactory
                .select(new QProductSearchResponse(
                        product.id,
                        product.name,
                        product.price,
                        product.stock,
                        product.status,
                        category.name,
                        // 평균 평점 — 상관 서브쿼리
                        JPAExpressions
                                .select(review.rating.avg())
                                .from(review)
                                .where(review.product.eq(product)),
                        // 후기 수 — 상관 서브쿼리
                        JPAExpressions
                                .select(review.count())
                                .from(review)
                                .where(review.product.eq(product)),
                        product.createdAt))
                .from(product)
                .join(product.category, category)
                .where(
                        keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()))
                .orderBy(toOrder(cond.sort(), cond.direction()), product.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // ── count 쿼리 (분리) ──────────────────────────────────────
        // 서브쿼리와 join 을 뺀 최소 형태입니다.
        JPAQuery<Long> countQuery = queryFactory
                .select(product.count())
                .from(product)
                .where(
                        keywordContains(cond.keyword()),
                        categoryIn(cond.categoryId()),
                        priceGoe(cond.minPrice()),
                        priceLoe(cond.maxPrice()),
                        inStock(cond.inStockOnly()),
                        statusIn(cond.statuses()));

        // count 쿼리를 "필요할 때만" 실행합니다 (Step 09)
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // ── 동적 조건 (Step 04, 10) ─────────────────────────────────────
    // null 을 반환하면 where 에서 무시됩니다.
    // BooleanBuilder 대신 메서드로 분리하면 재사용과 조합이 됩니다.

    private BooleanExpression keywordContains(String keyword) {
        return (keyword == null || keyword.isBlank())
                ? null
                : product.name.contains(keyword);
    }

    /** 선택한 카테고리 + 그 하위 카테고리 (Step 07) */
    private BooleanExpression categoryIn(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return product.category.id.eq(categoryId)
                .or(product.category.id.in(
                        JPAExpressions
                                .select(category.id)
                                .from(category)
                                .where(category.parent.id.eq(categoryId))));
    }

    private BooleanExpression priceGoe(BigDecimal min) {
        return min == null ? null : product.price.goe(min);
    }

    private BooleanExpression priceLoe(BigDecimal max) {
        return max == null ? null : product.price.loe(max);
    }

    private BooleanExpression inStock(Boolean inStockOnly) {
        return Boolean.TRUE.equals(inStockOnly) ? product.stock.gt(0) : null;
    }

    private BooleanExpression statusIn(List<ProductStatus> statuses) {
        return (statuses == null || statuses.isEmpty())
                ? null
                : product.status.in(statuses);
    }

    /** 정렬 화이트리스트 (Step 10). 입력은 키로만 쓰입니다. */
    private OrderSpecifier<?> toOrder(String sort, String direction) {
        if (sort == null) {
            return DEFAULT_SORT;
        }
        Map<String, OrderSpecifier<?>> table =
                "asc".equalsIgnoreCase(direction) ? SORT_ASC : SORT_DESC;
        return table.getOrDefault(sort, DEFAULT_SORT);
    }
}
```

### 생성 SQL — 콘텐츠 쿼리 전문

요청: `keyword=노트, categoryId=7, minPrice=500000, inStockOnly=true, sort=price, direction=desc, page=0, size=10`

```sql
Hibernate:
    select
        p1_0.product_id,
        p1_0.name,
        p1_0.price,
        p1_0.stock,
        p1_0.status,
        c1_0.name,
        (select
            avg(r1_0.rating)
        from
            reviews r1_0
        where
            r1_0.product_id = p1_0.product_id),
        (select
            count(r2_0.review_id)
        from
            reviews r2_0
        where
            r2_0.product_id = p1_0.product_id),
        p1_0.created_at
    from
        products p1_0
    join
        categories c1_0
            on c1_0.category_id = p1_0.category_id
    where
        p1_0.name like ? escape '!'
        and (
            p1_0.category_id = ?
            or p1_0.category_id in (select
                c2_0.category_id
            from
                categories c2_0
            where
                c2_0.parent_id = ?)
        )
        and p1_0.price >= ?
        and p1_0.stock > ?
    order by
        p1_0.price desc,
        p1_0.product_id desc
    limit ?, ?
```

```
TRACE o.h.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [%노트%]
TRACE o.h.orm.jdbc.bind : binding parameter (2:BIGINT)  <- [7]
TRACE o.h.orm.jdbc.bind : binding parameter (3:BIGINT)  <- [7]
TRACE o.h.orm.jdbc.bind : binding parameter (4:DECIMAL) <- [500000]
TRACE o.h.orm.jdbc.bind : binding parameter (5:INTEGER) <- [0]
TRACE o.h.orm.jdbc.bind : binding parameter (6:INTEGER) <- [0]
TRACE o.h.orm.jdbc.bind : binding parameter (7:INTEGER) <- [10]
```

`maxPrice` 와 `statuses` 는 null 이었으므로 **SQL 에 아예 없습니다.**
이것이 동적 쿼리의 이점입니다. `where 1=1` 같은 것도 없습니다.

### 생성 SQL — count 쿼리 전문

```sql
Hibernate:
    select
        count(p1_0.product_id)
    from
        products p1_0
    where
        p1_0.name like ? escape '!'
        and (
            p1_0.category_id = ?
            or p1_0.category_id in (select
                c1_0.category_id
            from
                categories c1_0
            where
                c1_0.parent_id = ?)
        )
        and p1_0.price >= ?
        and p1_0.stock > ?
```

**`join categories` 가 없습니다.** count 에는 카테고리명이 필요 없기 때문입니다.
서브쿼리 두 개도 없습니다. `PageableExecutionUtils` 덕분에
**결과가 한 페이지 안에 들어가면 이 쿼리는 아예 실행되지 않습니다.**

### 응답 JSON

```json
{
  "content": [
    {
      "productId": 12,
      "name": "게이밍 노트북 RTX4060",
      "price": 2190000.00,
      "stock": 12,
      "status": "ON_SALE",
      "categoryName": "노트북",
      "avgRating": 4.6,
      "reviewCount": 5,
      "createdAt": "2024-03-14T10:22:00"
    },
    {
      "productId": 13,
      "name": "보급형 노트북 15",
      "price": 690000.00,
      "stock": 31,
      "status": "ON_SALE",
      "categoryName": "노트북",
      "avgRating": null,
      "reviewCount": 0,
      "createdAt": "2024-05-02T09:10:00"
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 10 },
  "totalElements": 2,
  "totalPages": 1,
  "last": true
}
```

**두 번째 상품의 `avgRating` 이 `null`, `reviewCount` 가 `0`** 입니다.
**후기 없는 상품 24개** 중 하나입니다.

`avg()` 는 대상 행이 없으면 NULL 을, `count()` 는 0 을 반환합니다.
[13-7 절](../step-13-advanced/) 에서 본 그 차이입니다.
프론트가 NULL 을 못 받는다면 `coalesce` 로 닫으십시오.

```java
JPAExpressions.select(review.rating.avg())
        .from(review)
        .where(review.product.eq(product))
// →
Expressions.numberTemplate(Double.class, "coalesce({0}, {1})",
        JPAExpressions.select(review.rating.avg())
                .from(review).where(review.product.eq(product)),
        0.0)
```

템플릿을 쓰지 않는 더 나은 방법은 **DTO 의 compact constructor 에서 처리**하는 것입니다.

```java
public record ProductSearchResponse(...) {
    @QueryProjection
    public ProductSearchResponse {
        avgRating = avgRating == null ? 0.0 : avgRating;
    }
}
```

SQL 을 건드리지 않고 자바에서 닫습니다. 이쪽이 안전합니다.

### 구현체 — 버전 B (left join + group by)

같은 결과를 조인으로 만들면 이렇게 됩니다.

```java
@Override
public Page<ProductSearchResponse> searchWithGroupBy(ProductSearchCond cond, Pageable pageable) {

    List<ProductSearchResponse> content = queryFactory
            .select(new QProductSearchResponse(
                    product.id,
                    product.name,
                    product.price,
                    product.stock,
                    product.status,
                    category.name,
                    review.rating.avg(),
                    review.count(),
                    product.createdAt))
            .from(product)
            .join(product.category, category)
            .leftJoin(review).on(review.product.eq(product))    // ★ left join 필수
            .where(
                    keywordContains(cond.keyword()),
                    categoryIn(cond.categoryId()),
                    priceGoe(cond.minPrice()),
                    priceLoe(cond.maxPrice()),
                    inStock(cond.inStockOnly()),
                    statusIn(cond.statuses()))
            .groupBy(product.id, product.name, product.price, product.stock,
                     product.status, category.name, product.createdAt)   // ★ 전부 나열
            .orderBy(toOrder(cond.sort(), cond.direction()), product.id.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    JPAQuery<Long> countQuery = queryFactory
            .select(product.count())
            .from(product)
            .where(/* 위와 동일 */);

    return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
}
```

**생성 SQL**

```sql
Hibernate:
    select
        p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock, p1_0.status,
        c1_0.name,
        avg(r1_0.rating),
        count(r1_0.review_id),
        p1_0.created_at
    from
        products p1_0
    join
        categories c1_0 on c1_0.category_id = p1_0.category_id
    left join
        reviews r1_0 on r1_0.product_id = p1_0.product_id
    where
        p1_0.name like ? escape '!'
        and (p1_0.category_id = ? or p1_0.category_id in (
            select c2_0.category_id from categories c2_0 where c2_0.parent_id = ?))
        and p1_0.price >= ?
        and p1_0.stock > ?
    group by
        p1_0.product_id, p1_0.name, p1_0.price, p1_0.stock,
        p1_0.status, c1_0.name, p1_0.created_at
    order by
        p1_0.price desc, p1_0.product_id desc
    limit ?, ?
```

### A vs B — 어느 쪽이 나은가

| | A (서브쿼리) | B (left join + group by) |
|---|---|---|
| SQL 문 수 | 1 (서브쿼리 2개 포함) | 1 |
| `reviews` 접근 | 결과 행마다 2번 | 조인 1번 |
| `EXPLAIN` | `DEPENDENT SUBQUERY` × 2 | `Using temporary; Using filesort` |
| `groupBy` 나열 | 불필요 | **select 컬럼 전부** 나열 필요 |
| 페이징 정확도 | 정확 | 정확 (group by 후 limit) |
| 후기 0건 상품 | `avg=null, count=0` | `avg=null, count=0` ✅ |
| 코드 가독성 | 서브쿼리가 길어짐 | `groupBy` 나열이 지저분 |

**EXPLAIN 실측**

```
-- A (서브쿼리)
+----+--------------------+-------+------+---------------------+------+-------------+
| id | select_type        | table | type | key                 | rows | Extra       |
+----+--------------------+-------+------+---------------------+------+-------------+
|  1 | PRIMARY            | p1_0  | ALL  | NULL                |   40 | Using where |
|  1 | PRIMARY            | c1_0  |eq_ref| PRIMARY             |    1 | NULL        |
|  3 | DEPENDENT SUBQUERY | r2_0  | ref  | idx_reviews_product |    5 | Using index |
|  2 | DEPENDENT SUBQUERY | r1_0  | ref  | idx_reviews_product |    5 | NULL        |
+----+--------------------+-------+------+---------------------+------+-------------+
실행: 0.006 sec

-- B (left join + group by)
+----+-------------+-------+------+---------------------+------+---------------------------------+
| id | select_type | table | type | key                 | rows | Extra                           |
+----+-------------+-------+------+---------------------+------+---------------------------------+
|  1 | SIMPLE      | p1_0  | ALL  | NULL                |   40 | Using where; Using temporary;   |
|    |             |       |      |                     |      | Using filesort                  |
|  1 | SIMPLE      | c1_0  |eq_ref| PRIMARY             |    1 | NULL                            |
|  1 | SIMPLE      | r1_0  | ref  | idx_reviews_product |    5 | NULL                            |
+----+-------------+-------+------+---------------------+------+---------------------------------+
실행: 0.009 sec
```

**이 데이터에서는 A 가 약간 빠릅니다.** 하지만 이것이 일반 규칙은 아닙니다.

| 상황 | 유리한 쪽 | 이유 |
|---|---|---|
| 페이지 크기가 작다 (10~20) | **A** | 서브쿼리가 페이지 안의 행에만 실행됨 |
| 페이지 크기가 크다 (1000+) | **B** | 서브쿼리 2000번 vs 조인 1번 |
| 후기 테이블이 아주 크다 | **B** | `DEPENDENT SUBQUERY` 는 행마다 재실행 |
| 집계 대상이 여러 개 | **B** | 서브쿼리가 개수만큼 늘어남 |
| 정렬 키가 집계값 (평점순) | **B** | A 는 서브쿼리로 정렬 불가 |

**평점 정렬을 지원한다면 B 를 써야 합니다.**
A 의 정렬 화이트리스트에 `"rating"` 이 없는 것을 눈치채셨습니까?
A 에서는 서브쿼리 결과로 정렬할 수 없습니다.
`order by (select avg(...) ...)` 가 문법적으로 불가능하지는 않지만
QueryDSL 로 표현하기 어렵고 성능도 나쁩니다.

**요구사항에 "평점순 정렬"이 있으므로 최종 선택은 B 입니다.**
B 의 화이트리스트에는 `"rating"` 을 추가할 수 있습니다.

```java
private static final Map<String, OrderSpecifier<?>> SORT_DESC_B = Map.of(
        "price",   product.price.desc(),
        "created", product.createdAt.desc(),
        "name",    product.name.desc(),
        "rating",  review.rating.avg().desc());     // ★ 집계값 정렬
```

```sql
order by avg(r1_0.rating) desc, p1_0.product_id desc
```

> 💡 **실무 팁 — 재고 나서 고르십시오**
> 위 표는 "이럴 것이다"의 정리이지 정답표가 아닙니다.
> 두 버전을 다 만들어 두고 **여러분의 데이터로 `EXPLAIN ANALYZE`** 를 돌려 보십시오.
> 이 코스가 처음부터 끝까지 한 이야기입니다. 추측하지 말고 SQL 을 보고 재십시오.

### 컨트롤러

```java
package com.example.shop.step14;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService productSearchService;

    @GetMapping("/search")
    public Page<ProductSearchResponse> search(
            @ModelAttribute ProductSearchCond cond,
            @PageableDefault(size = 20) Pageable pageable) {
        return productSearchService.search(cond, pageable);
    }
}
```

```java
package com.example.shop.step14;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)      // 14-8
public class ProductSearchService {

    private final ProductRepository productRepository;

    public Page<ProductSearchResponse> search(ProductSearchCond cond, Pageable pageable) {
        return productRepository.searchWithGroupBy(cond, pageable);
    }
}
```

호출 예:

```
GET /api/products/search?keyword=노트&categoryId=7&minPrice=500000
    &inStockOnly=true&sort=rating&direction=desc&page=0&size=10
```

> ⚠️ **함정 — `Pageable` 의 `sort` 파라미터와 직접 만든 `sort` 가 충돌합니다**
> Spring Data 의 `Pageable` 은 `?sort=price,desc` 형태를 자동으로 파싱합니다.
> 우리 `ProductSearchCond` 에도 `sort` 필드가 있습니다.
> **같은 이름의 쿼리 파라미터를 둘이 나눠 갖게 되어** 예측 불가능한 동작이 됩니다.
>
> 해결은 셋 중 하나입니다.
> 1. `ProductSearchCond` 의 필드명을 `sortKey` / `sortDirection` 으로 바꿉니다 (권장)
> 2. `Pageable` 대신 `page` / `size` 를 직접 받습니다
> 3. `Pageable.getSort()` 를 화이트리스트로 변환해 씁니다
>
> 이 실습 코드는 1번을 씁니다. `Practice.java` 를 확인하십시오.

### EXPLAIN 으로 검증하고 인덱스 하나 추가하기

최종 쿼리의 `EXPLAIN` 입니다.

```
+----+-------------+-------+------+---------------------+------+-------------------------------+
| id | select_type | table | type | key                 | rows | Extra                         |
+----+-------------+-------+------+---------------------+------+-------------------------------+
|  1 | SIMPLE      | p1_0  | ALL  | NULL                |   40 | Using where; Using temporary; |
|    |             |       |      |                     |      | Using filesort                |
|  1 | SIMPLE      | c1_0  |eq_ref| PRIMARY             |    1 | NULL                          |
|  1 | SIMPLE      | r1_0  | ref  | idx_reviews_product |    5 | NULL                          |
+----+-------------+-------+------+---------------------+------+-------------------------------+
```

`products` 가 `type: ALL` 입니다. `products` 에는 `idx_products_category` 밖에 없습니다.

```sql
SHOW INDEX FROM products;
```

```
+----------+------------+------------------------+--------------+-------------+
| Table    | Non_unique | Key_name               | Seq_in_index | Column_name |
+----------+------------+------------------------+--------------+-------------+
| products |          0 | PRIMARY                |            1 | product_id  |
| products |          1 | idx_products_category  |            1 | category_id |
+----------+------------+------------------------+--------------+-------------+
```

검색 조건은 `category_id`, `price`, `stock`, `status`, `name` 입니다.
어느 컬럼으로 인덱스를 만들어야 할까요.

**선택도를 먼저 봅니다.** ([MySQL8 Step 15 4절](../../mysql8/step-15-indexes/))

```sql
SELECT COUNT(*) total,
       COUNT(DISTINCT category_id) cat,
       COUNT(DISTINCT status) st,
       COUNT(DISTINCT price) pr,
       SUM(stock > 0) in_stock
FROM products;
```

```
+-------+-----+----+----+----------+
| total | cat | st | pr | in_stock |
+-------+-----+----+----+----------+
|    40 |  12 |  3 | 34 |       33 |
+-------+-----+----+----------+-----+
```

- `status` — 3종. 선택도가 낮습니다. 단독 인덱스는 무의미합니다
- `stock > 0` — 40 중 33. 거의 전부입니다. 무의미합니다
- `price` — 34종. 선택도가 높습니다
- `category_id` — 12종. 중간. 이미 인덱스가 있습니다

**검색 화면의 주 필터는 카테고리이고, 그 안에서 가격으로 좁히고 가격으로 정렬합니다.**
따라서 `(category_id, price)` 복합 인덱스가 후보입니다.

```sql
ALTER TABLE products ADD INDEX idx_products_cat_price (category_id, price);
```

```
Query OK, 0 rows affected (0.031 sec)
```

**결과** — 다시 EXPLAIN

```
+----+-------------+-------+-------+---------------------------------+------------------------+------+-------------------------------+
| id | select_type | table | type  | possible_keys                   | key                    | rows | Extra                         |
+----+-------------+-------+-------+---------------------------------+------------------------+------+-------------------------------+
|  1 | SIMPLE      | p1_0  | range | idx_products_category,          | idx_products_cat_price |    3 | Using index condition;        |
|    |             |       |       | idx_products_cat_price          |                        |      | Using where; Using temporary; |
|    |             |       |       |                                 |                        |      | Using filesort                |
|  1 | SIMPLE      | c1_0  |eq_ref | PRIMARY                         | PRIMARY                |    1 | NULL                          |
|  1 | SIMPLE      | r1_0  | ref   | idx_reviews_product             | idx_reviews_product    |    5 | NULL                          |
+----+-------------+-------+-------+---------------------------------+------------------------+------+-------------------------------+
```

**`type: ALL` → `range`, `rows: 40` → `3`.**

`Using temporary; Using filesort` 는 남아 있습니다.
`group by` 와 집계값 정렬 때문이고, **이건 인덱스로 못 없앱니다.**
집계 결과의 순서는 계산이 끝나야 알 수 있기 때문입니다.

실행시간 대조:

```
-- 인덱스 추가 전
40 rows scanned, 1 row in set (0.009 sec)

-- 인덱스 추가 후
3 rows scanned, 1 row in set (0.004 sec)
```

40행에서는 0.009 → 0.004 로 큰 의미가 없습니다.
**40만 행이라면 이 차이가 4초 → 0.05초가 됩니다.**

> 💡 **실무 팁 — 인덱스를 추가하기 전에 세 가지를 확인하십시오**
> 1. **정말 그 쿼리가 느린가** — `EXPLAIN` 보다 실측이 먼저입니다. 느리지 않으면 건드리지 마십시오
> 2. **기존 인덱스로 안 되는가** — `idx_products_category` 를 `(category_id, price)` 로
>    **확장**하면 인덱스가 하나 늘지 않습니다. 선두 컬럼이 같으므로 기존 용도도 그대로입니다
> 3. **쓰기 비용을 감당할 수 있는가** — 인덱스는 INSERT/UPDATE 마다 갱신됩니다.
>    조회가 거의 없고 쓰기만 많은 테이블에 인덱스를 잔뜩 걸면 손해입니다
>
> 이 실습에서는 2번이 더 나은 선택입니다.
> ```sql
> ALTER TABLE products DROP INDEX idx_products_category;
> ALTER TABLE products ADD INDEX idx_products_category (category_id, price);
> ```
> 인덱스 개수는 그대로이고 효과는 같습니다.

실습이 끝나면 되돌리십시오.

```sql
ALTER TABLE products DROP INDEX idx_products_cat_price;
```

---

## 14-10. QueryDSL 7.x 는 어떤가

이 코스는 **6.12 (2025-06-09)** 기준으로 작성했고, 모든 예제를 그 버전에서 확인했습니다.
그 이후 **7.x 가 나왔습니다 (7.2, 2026-05).**

확실한 것부터 정리합니다.

| 항목 | 사실 |
|---|---|
| groupId | **`io.github.openfeign.querydsl` 그대로** |
| 5.x → 6.x 같은 좌표 이동 | **아닙니다.** 그때는 `com.querydsl` → OpenFeign 포크로 옮기는 이동이었습니다 |
| 메이저 버전 승격 | **breaking change 가 있을 수 있습니다** |
| 이 코스의 기준 | 6.12 |

그리고 이 코스가 **단정하지 않는** 것입니다.

**7.x 에서 어떤 API 가 어떻게 바뀌었는지 이 코스는 말하지 않습니다.**
릴리스 노트를 직접 확인하십시오.
인터넷에서 찾은 "7.x 에서는 이렇게 바뀌었다"는 설명도 그대로 믿지 마십시오.
**여러분의 프로젝트에서 테스트로 검증하는 것이 유일한 확인 방법입니다.**

이 코스에서 배운 API 의 골격 — `JPAQueryFactory`, Q타입, `BooleanExpression`,
`Projections`, `fetchJoin()` 의 페이징 제약 — 은 QueryDSL 의 근본 설계이므로
대체로 유지될 것으로 보입니다. 하지만 "그럴 것"과 "그렇다"는 다릅니다.

### 마이그레이션 체크리스트 — "무엇을 확인하라"

| # | 확인할 것 | 방법 |
|---|---|---|
| 1 | 릴리스 노트의 breaking change 항목 | GitHub 릴리스 페이지 전부 읽기 (6.12 → 7.x 사이 전 버전) |
| 2 | `querydsl-apt` classifier 가 여전히 `:jpa` 인가 | `./gradlew dependencies --configuration annotationProcessor` |
| 3 | Q타입이 정상 생성되는가 | `./gradlew clean compileJava` 후 `find build/generated -name 'Q*.java'` |
| 4 | 요구 Java / Hibernate 최소 버전 | 릴리스 노트 또는 POM 의 `maven.compiler.source` |
| 5 | Spring Boot 버전과의 호환 | Hibernate 버전이 Spring Boot 관리 버전과 맞는지 |
| 6 | deprecated 였던 API 가 제거됐는가 | `fetchCount()`, `fetchResults()` 등. 컴파일 에러로 드러남 |
| 7 | **생성 SQL 이 같은가** | ★ 가장 중요. 아래 참고 |
| 8 | `@QueryProjection` 이 record 에서 동작하는가 | 컴파일 후 Q클래스 확인 |
| 9 | Spring Data JPA 의 QueryDSL 지원과 충돌하는가 | `QuerydslPredicateExecutor` 를 쓴다면 확인 |

7번이 이 코스의 결론입니다.

**버전을 올린 뒤 확인할 것은 "컴파일이 되는가"가 아니라 "같은 SQL 이 나가는가"입니다.**

컴파일이 되고 테스트가 통과해도, 생성 SQL 이 바뀌었다면 성능이 바뀝니다.
조인 순서가 바뀌거나, `exists` 가 `in` 으로 번역되거나, 서브쿼리가 파생 테이블이 되면
결과는 같고 실행 계획만 달라집니다. **결과를 단언하는 테스트로는 못 잡습니다.**

이 코스에서 만든 습관이 그대로 도구가 됩니다.

```java
@Test
void 상품검색_SQL이_바뀌지_않았는가() {
    // JPQL 스냅샷 (14-1 ④)
    JPAQuery<?> query = buildSearchQuery(cond);
    assertThat(query.toString()).isEqualTo(EXPECTED_JPQL);

    // 쿼리 개수 (14-1 ⑤)
    stats.clear();
    query.fetch();
    assertThat(stats.getQueryExecutionCount()).isEqualTo(1L);
}
```

버전을 올리기 전에 이런 테스트를 먼저 만들어 두십시오.
올린 뒤에 만들면 **무엇이 바뀌었는지 알 수 없습니다.**

---

## 14-11. 코스 마무리 — 14스텝의 함정 전체 목록

이 코스의 정체성은 첫 페이지에 이렇게 적혀 있습니다.

> QueryDSL 에서 정말 위험한 코드는 컴파일 에러가 나는 코드가 아닙니다.
> **컴파일도 되고 실행도 되는데, 나가는 SQL 이 내가 의도한 것과 다른 코드**입니다.

14개 스텝에서 심어 둔 함정 전부입니다.

| # | 함정 | 스텝 | 증상 | 처방 |
|---|---|---|---|---|
| 1 | 5.x 좌표를 복사 | [01](../step-01-setup/) [프로젝트](../project/) | 빌드는 성공, 기동 시 `NoClassDefFoundError: javax/persistence/Entity` | `querydsl-jpa` classifier 없음, `querydsl-apt` 는 `:jpa` |
| 2 | Hibernate 5 의 바인딩 로거 이름 | [01](../step-01-setup/) | SQL 은 찍히는데 바인딩 값만 안 보임. 에러 없음 | `org.hibernate.orm.jdbc.bind: trace` |
| 3 | `clean` 후 Q타입 실종 | [02](../step-02-qtype/) | `cannot find symbol: class QCustomer` | Q타입은 빌드 산출물. `compileJava` + Gradle 새로고침 |
| 4 | `fetchOne()` 에 결과 2건 이상 | [03](../step-03-basic-query/) | `NonUniqueResultException` — 개발 DB 에서는 재현 안 됨 | 유일성이 보장 안 되면 `fetchFirst()` |
| 5 | `.or()` 체인의 괄호 소실 | [04](../step-04-where-conditions/) | `A AND (B OR C)` 가 `A AND B OR C` 로. **결과가 다른데 에러 없음** | `Expressions.allOf`/`anyOf` 로 중첩 |
| 6 | `where(null)` 이 조건 무시 | [04](../step-04-where-conditions/) | 조건이 통째로 사라져 전건 조회 | 의도한 동작. 다만 null 이 실수인지 의도인지 구분 |
| 7 | `Projections.fields()` 필드명 불일치 | [05](../step-05-projections/) | 예외 없이 **그 필드만 null** | `@QueryProjection` 으로 컴파일 타임 검증 |
| 8 | 컬렉션 fetch join + 페이징 | [06](../step-06-joins/) | 경고 한 줄. **전건을 메모리로 읽어 페이징** | `default_batch_fetch_size` 또는 DTO |
| 9 | 컬렉션 fetch join 2개 | [06](../step-06-joins/) | `MultipleBagFetchException` | 하나만 fetch join, 나머지는 batch size |
| 10 | JPA 는 FROM 절 서브쿼리 불가 | [07](../step-07-subqueries/) | 표준 SQL 을 그대로 옮기면 실패 | 조인으로 재작성하거나 네이티브 쿼리 |
| 11 | `NOT IN` + NULL | [07](../step-07-subqueries/) | 결과가 **0건**. 에러 없음 | `NOT EXISTS` 로 바꾸거나 `isNotNull()` 추가 |
| 12 | 조인 후 `count()` 뻥튀기 | [08](../step-08-aggregation/) | 고객 수를 셌는데 주문 수가 나옴 | `countDistinct()` 또는 서브쿼리 |
| 13 | `sum()` 이 0 대신 NULL | [08](../step-08-aggregation/) [13](../step-13-advanced/) | 대상 0건이면 NULL. 자바에서 NPE | `.sum().coalesce(ZERO)` |
| 14 | 정렬 컬럼에 함수 | [09](../step-09-sorting-paging/) [13](../step-13-advanced/) [14](#14-6-인덱스-활용-확인--querydsl-이-만든-sql-을-explain-에) | `Using filesort`. 30건일 땐 안 보임 | 범위 조건, 정렬용 컬럼 |
| 15 | `fetchCount()` / `fetchResults()` | [09](../step-09-sorting-paging/) | deprecated. `group by` 에서 잘못된 count | `select(x.count())` 직접 작성 |
| 16 | 정렬 키를 문자열로 그대로 사용 | [10](../step-10-dynamic-sort/) | 인젝션 가능. 없는 컬럼이면 런타임 예외 | `Map<String, OrderSpecifier<?>>` 화이트리스트 |
| 17 | 벌크 연산 후 영속성 컨텍스트 | [11](../step-11-bulk-operations/) | UPDATE 는 됐는데 **조회하면 옛 값** | `em.flush()` → 벌크 → `em.clear()` |
| 18 | `Impl` 접미사 오타 | [12](../step-12-spring-data/) | 빈 등록 실패. **에러 메시지가 엉뚱한 곳을 가리킴** | `<인터페이스명>Impl` 정확히 |
| 19 | `case` 를 `orderBy` 에 | [13](../step-13-advanced/) | `select` 와 `order by` 에 두 번. 인덱스 상실 | 소량이면 OK, 대량이면 정렬용 컬럼 |
| 20 | `concat` 에 NULL 이 섞임 | [13](../step-13-advanced/) | **전체가 NULL.** 이름까지 사라짐 | `coalesce` 를 concat **안쪽**에 |
| 21 | `BigDecimal.divide` 스케일 | [13](../step-13-advanced/) | DB 는 반올림, 자바는 예외. `equals` 는 스케일까지 비교 | 스케일 명시, `compareTo` 로 비교 |
| 22 | **`stringTemplate` 문자열 연결** | [13](../step-13-advanced/) | **SQL 인젝션.** ORM 을 쓴다는 사실이 막아 주지 않음 | 템플릿은 컴파일 시점 상수, 값은 `{n}` |
| 23 | Hibernate 5 방식 함수 등록 | [13](../step-13-advanced/) | 컴파일은 되고 실행 시점에 함수 없음 | Hibernate 6 은 `FunctionContributor` |
| 24 | N+1 | [14](#14-2-n1-진단) | **결과는 완벽하게 맞음.** 느릴 뿐 | 쿼리 개수를 세고 테스트로 고정 |
| 25 | `count() > 0` 로 존재 확인 | [14](#14-4-exists-vs-count) | 전부 셈. 0.180초 vs 0.002초 | `JPAExpressions.selectOne()...exists()` |
| 26 | `IDENTITY` 에서 배치 INSERT | [14](#14-7-배치-insert--update-설정) | 설정을 켜도 배치가 안 묶임 | ID 전략을 바꾸거나 포기 |
| 27 | 앞 `%` LIKE | [14](#14-6-인덱스-활용-확인--querydsl-이-만든-sql-을-explain-에) | `contains` 는 `%x%`. `type: ALL` | `startsWith`, FULLTEXT, 또는 재고 나서 결정 |
| 28 | `Pageable.sort` 와 커스텀 sort 충돌 | [14](#14-9-종합-실습--상품-검색-api) | 같은 쿼리 파라미터를 둘이 나눠 가짐 | 필드명을 `sortKey` 로 분리 |

이 28개 중 **에러가 나는 것은 4개뿐**입니다 (1, 3, 9, 11 중 일부).
나머지는 전부 조용합니다. 결과가 맞거나, 맞아 보이거나, 아무도 안 볼 때만 틀립니다.

**이 코스가 가르친 것은 QueryDSL 문법이 아니라 생성 SQL 을 보는 습관입니다.**
문법은 문서에 있습니다. 습관은 문서에 없습니다.

### 더 공부할 것

| 주제 | 무엇 | 왜 |
|---|---|---|
| **QueryDSL-SQL** | JPA 없이 QueryDSL 로 직접 SQL 을 만드는 모듈 | JPQL 의 제약(FROM 절 서브쿼리, UNION, 윈도우 함수)이 없습니다. 스키마에서 Q타입을 생성합니다 |
| **Hibernate 6 HQL** | `union`, `window function`, `cte`, `values` | Hibernate 6 의 HQL 은 JPQL 표준을 크게 넘어섭니다. QueryDSL 로 안 되는 것이 HQL 로는 됩니다 |
| **JPA 표준 Criteria** | `CriteriaBuilder`, `CriteriaQuery` | QueryDSL 이 왜 만들어졌는지 이해하려면 한 번은 써 봐야 합니다 |
| **MySQL8 코스 3부** | [Step 15 — 인덱스](../../mysql8/step-15-indexes/), [Step 16 — EXPLAIN과 옵티마이저](../../mysql8/step-16-explain-optimizer/) | 이 스텝에서 맛만 본 인덱스와 실행 계획을 처음부터 |
| **커넥션 풀 / 트랜잭션** | HikariCP 튜닝, 격리 수준, 락 | 쿼리를 아무리 튜닝해도 커넥션이 부족하면 소용없습니다 |
| **캐시** | Hibernate 2차 캐시, Redis | 쿼리를 빠르게 하는 것보다 안 하는 것이 빠릅니다 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| SQL 확인 5가지 | 로그 / `format_sql` / **p6spy(실행 가능한 SQL)** / `toString()`(JPQL) / statistics(쿼리 개수) |
| N+1 진단 | `generate_statistics: true` → `executing 11 JDBC statements` |
| N+1 ① fetch join | 쿼리 1개. **컬렉션 + 페이징이면 전건 메모리 로딩** |
| N+1 ② batch size | 쿼리 2개. `where id in (?,?,...)`. **페이징과 공존.** 실무 기본값 100~1000 |
| N+1 ③ DTO 직접 조회 | 쿼리 1개, 필요한 컬럼만. **변경 감지 없음** |
| 선택 기준 | 전역 batch size(안전망) + 조회는 DTO + 수정할 것만 fetch join |
| `exists` vs `count` | `count() > 0` 은 전부 셈. `exists` 는 첫 행에서 멈춤. **0.180초 → 0.002초** |
| 필요한 컬럼만 | 40행에서는 차이 없음. 100만 행에서 다름. **처음부터 DTO 로** |
| EXPLAIN 절차 | p6spy 로 완성 SQL 복사 → `EXPLAIN` → `type` / `rows` / `Extra` |
| 인덱스 죽이는 4패턴 | 컬럼에 함수 / 앞 `%` LIKE / 타입 불일치 / `or` 남발 |
| 배치 쓰기 | `jdbc.batch_size` + `order_inserts`. **`IDENTITY` 면 배치 불가** |
| 벌크 vs 배치 | 벌크는 UPDATE 1개(컨텍스트 건너뜀), 배치는 개별 저장을 모아 전송 |
| `readOnly = true` | 자동 플러시 제거 + 스냅샷 생략. **메모리 절반.** 안전장치는 아님 |
| 최종 프로젝트 | 동적 조건 + 정렬 화이트리스트 + count 분리 + `@QueryProjection` + `EXPLAIN` |
| 서브쿼리 vs group by | 페이지 작으면 서브쿼리, 크거나 **집계값 정렬이면 group by** |
| 7.x | groupId 동일. **breaking change 는 릴리스 노트로 직접 확인.** 이 코스는 6.12 |
| 버전 올린 뒤 | "컴파일되는가"가 아니라 **"같은 SQL 이 나가는가"** |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `generate_statistics` 를 켜고, 주문 20건을 조회하며 각 주문의 고객 등급을 출력하십시오.
   쿼리가 몇 개 나가는지 세고, 그 숫자가 왜 그런지 설명하십시오.
2. 1번을 fetch join / batch size / DTO 세 가지 방법으로 각각 해결하고
   **쿼리 개수와 생성 SQL 을 표로 정리**하십시오.
3. "후기가 한 건이라도 있는 상품" 을 `count() > 0` 과 `exists` 두 가지로 조회하고,
   생성 SQL 과 `EXPLAIN` 의 서브쿼리 `rows` 를 대조하십시오. 16건이 나와야 합니다.
4. 상품명에 "노트"가 들어가는 상품을 `contains` 와 `startsWith` 로 각각 조회하십시오.
   두 SQL 을 `EXPLAIN` 에 넣어 `type` 을 비교하고, 왜 다른지 설명하십시오.
5. 14-9 의 상품 검색을 **서브쿼리 버전(A)** 으로 직접 구현하십시오.
   요구사항 중 "평점순 정렬"을 뺀 나머지를 만족하면 됩니다.
   생성 SQL 두 개(콘텐츠 + count)를 확인하십시오.
6. 5번의 검색 결과에서 **평균 평점이 null 인 상품이 몇 개**인지 세십시오.
   그 숫자가 24가 아니라면 왜 그런지 설명하십시오. (힌트: 검색 조건이 걸려 있습니다)
7. 14-9 의 최종 쿼리에 `(category_id, price)` 인덱스를 추가하기 **전과 후**의
   `EXPLAIN` 을 나란히 붙이고, `type` / `rows` / `Extra` 가 어떻게 바뀌었는지 정리하십시오.
   실습이 끝나면 인덱스를 삭제하십시오.

---

## 다음 단계 — 코스를 마치며

여기가 이 코스의 끝입니다.

14개 스텝에서 QueryDSL 문법을 배웠습니다. 하지만 그건 부수적입니다.
**여러분이 실제로 배운 것은 자바 코드 한 줄이 SQL 한 줄로 번역되는 과정을 눈으로 좇는 습관입니다.**

이 습관이 있으면 QueryDSL 7.x 로 올려도, Hibernate 7 이 나와도,
아예 다른 ORM 으로 옮겨도 같은 방식으로 검증할 수 있습니다.
습관이 없으면 어떤 버전에서도 같은 함정에 빠집니다.

문법은 문서에서 다시 찾을 수 있습니다. 습관은 문서에 없습니다.

**이제 할 일은 하나입니다.**
여러분의 프로젝트에서 `org.hibernate.SQL: debug` 를 켜십시오.
그리고 지금 돌아가고 있는 조회 API 하나를 골라 로그를 보십시오.
28개 함정 중 몇 개가 이미 들어 있을 것입니다.

### 이어서 볼 것

**MySQL 8 코스 3부** — 이 스텝에서 맛만 본 인덱스와 실행 계획을 처음부터 제대로 다룹니다.
QueryDSL 이 만든 SQL 을 읽을 수 있게 됐으니, 이제 그 SQL 이 DB 안에서 어떻게 처리되는지 볼 차례입니다.

→ [MySQL 8 — Step 15 — 인덱스](../../mysql8/step-15-indexes/)
B+Tree, 클러스터드/세컨더리 인덱스, 복합 인덱스의 선두 컬럼 규칙, 커버링 인덱스,
인덱스를 못 타는 5가지 패턴, FULLTEXT. 100만 행 `access_logs` 로 전부 실측합니다.

→ [MySQL 8 — Step 16 — EXPLAIN과 옵티마이저](../../mysql8/step-16-explain-optimizer/)
`type` 등급, `Extra` 읽는 법, `EXPLAIN ANALYZE` 로 추정이 아닌 실측 보기,
`FORMAT=TREE/JSON`, 옵티마이저 힌트, 히스토그램, 실전 튜닝 절차.

→ [QueryDSL 6 코스 개요](../)
커리큘럼 전체와 스텝별 함정 목록을 다시 훑어보십시오.
처음 읽었을 때와 지금은 다르게 읽힐 것입니다.

---

## 실습 파일

`Practice.java` 에는 14-1 ~ 14-8 의 측정 코드와 14-9 의 최종 프로젝트가 모두 들어 있습니다.
**측정 코드를 먼저 돌려 보십시오.** 숫자를 직접 보지 않으면 이 스텝은 읽은 것에 그칩니다.

### Practice.java

- `statisticsSetup()` 으로 Hibernate statistics 를 켜고 쿼리 개수를 세는 방법을 담았습니다.
- `nPlusOne()` → `fetchJoinSolution()` → `batchSizeSolution()` → `dtoSolution()` 순으로
  실행하며 `executing N JDBC statements` 를 비교하십시오.
- `existsVsCount()` 는 실행시간을 직접 잽니다. 여러 번 돌려 평균을 보십시오.
- 14-9 의 리포지토리 구현체 두 버전(A/B)이 nested class 로 들어 있습니다.
  `search()` 와 `searchWithGroupBy()` 의 생성 SQL 을 나란히 놓고 비교하십시오.

```java file="./Practice.java"
```

### Exercise.java

- 7문제. 5번과 7번이 최종 프로젝트에 해당합니다.
- 3, 4, 7번은 **MySQL 콘솔에서 `EXPLAIN` 을 직접 실행**해야 합니다.
  p6spy 를 켜고 완성된 SQL 을 복사하십시오.

```java file="./Exercise.java"
```

### Solution.java

- 정답과 함께 각 선택의 근거를 주석으로 설명합니다.
- 2번의 표는 그대로 팀 문서로 쓸 수 있게 채워 두었습니다.
- 5번은 14-9 의 A 버전 전체 코드입니다. 본문과 비교하며 읽으십시오.

```java file="./Solution.java"
```
