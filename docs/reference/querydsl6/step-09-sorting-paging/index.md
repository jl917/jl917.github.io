# Step 09 — 정렬과 페이징

> **학습 목표**
> - `orderBy` 로 단일·다중 정렬을 걸고, 생성 SQL 의 `order by` 절과 1:1 로 대조한다
> - `nullsFirst()` / `nullsLast()` 가 MySQL 방언에서 어떤 SQL 로 풀려 나가는지 확인한다
> - `offset` / `limit` 과 Spring `Pageable` 을 연결해 `Page` 를 만든다
> - **count 쿼리를 콘텐츠 쿼리와 분리해 최적화**하고, `PageableExecutionUtils` 로 아예 생략시킨다
> - 정렬 컬럼에 함수를 씌워 인덱스를 죽이는 사고를 재현하고 `Using filesort` 를 눈으로 본다
> - 깊은 `offset` 의 비용을 실측하고 **키셋(커서) 페이징**으로 100배 이상 줄인다
> - 정렬 기준이 유일하지 않을 때 페이지 사이에서 행이 새거나 중복되는 현상을 재현한다
>
> **선행 스텝**: [Step 08 — 집계와 그룹핑](../step-08-aggregation/)
> **예상 소요**: 100분

---

## 9-0. 실습 준비

이 스텝의 코드는 모두 아래를 전제로 합니다. Q타입은 **static import** 로 씁니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;

@SpringBootTest
@Transactional
class Practice {

    @Autowired JPAQueryFactory queryFactory;
    @PersistenceContext EntityManager em;
}
```

정렬과 페이징은 **데이터가 적으면 어떤 방식으로 짜도 똑같이 빠릅니다.**
`orders` 는 600건, `customers` 는 30건이라 잘못 짠 쿼리와 잘 짠 쿼리의 실행시간이 구별되지 않습니다.
그래서 이 스텝의 성능 측정 구간(9-7, 9-8)은 **MySQL8 코스의 `access_logs` 100만 행**을 씁니다.
QueryDSL 로 만든 SQL 을 그대로 복사해 `access_logs` 에 대고 돌린 수치입니다.

> 📌 `access_logs` 는 엔티티로 매핑하지 않습니다. MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 의 실습 테이블이며,
> 이 스텝에서는 "같은 모양의 SQL 이 100만 행에서 어떻게 동작하는가"를 보기 위한 대조군으로만 씁니다.

---

## 9-1. `orderBy` — 정렬의 기본

QueryDSL 의 모든 비교 가능한 경로(`ComparableExpression` 계열)에는 `.asc()` 와 `.desc()` 가 있습니다.
이 메서드가 반환하는 것이 **`OrderSpecifier`** 이고, `orderBy(...)` 는 이 타입만 받습니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .orderBy(customer.points.desc())
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.points desc
```
```
조회 30건
1. 류하나   (VIP,   48200p)
2. 정  훈   (VIP,   41500p)
3. 배채영   (VIP,   37800p)
4. 김서준   (VIP,   30100p)
5. 오하윤   (GOLD,  19600p)
...
```

`.desc()` 가 `desc` 로, `.asc()` 가 `asc` 로 그대로 번역됩니다.
QueryDSL 에서 정렬만큼은 SQL 과의 대응이 거의 완벽합니다.

### 다중 정렬

`orderBy` 는 가변 인자를 받습니다. **인자 순서가 곧 정렬 우선순위**입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .orderBy(
                customer.grade.desc(),      // 1순위: 등급 내림차순
                customer.points.desc(),     // 2순위: 포인트 내림차순
                customer.customerId.asc()   // 3순위: PK 오름차순 (타이브레이커)
        )
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.grade desc, c1_0.points desc, c1_0.customer_id asc
```
```
조회 30건
1. 류하나   (VIP,    48200p, id=7)
2. 정  훈   (VIP,    41500p, id=12)
3. 배채영   (VIP,    37800p, id=23)
4. 김서준   (VIP,    30100p, id=3)
5. 오하윤   (GOLD,   19600p, id=15)
...
```

> ⚠️ **`grade` 는 ENUM 입니다 — `desc` 가 무엇을 뜻하는지 확인하십시오**
> `grade` 는 `@Enumerated(EnumType.STRING)` 으로 매핑돼 있고 DB 컬럼은 MySQL `ENUM('BRONZE','SILVER','GOLD','VIP')` 입니다.
> MySQL 의 `ENUM` 은 **선언 순서의 정수값**으로 정렬됩니다. 즉 `grade desc` 는 문자열 사전순(`VIP > SILVER > GOLD > BRONZE`)이 아니라
> 선언 순서 역순(`VIP > GOLD > SILVER > BRONZE`)입니다. 우연히 원하는 결과가 나오지만 **그 이유는 사전순이 아닙니다.**
> 등급별 우선순위를 확실히 하려면 `CaseBuilder` 로 순위를 만들어 정렬하십시오 (Step 13 에서 다룹니다).

### 세 개 이상의 정렬 키와 가독성

정렬 키가 많아지면 `OrderSpecifier` 배열로 뽑아 두는 편이 읽기 쉽습니다.
이 형태는 Step 10 의 동적 정렬로 그대로 이어집니다.

```java
OrderSpecifier<?>[] sortByGradeThenPoints = {
        customer.grade.desc(),
        customer.points.desc(),
        customer.customerId.asc()
};

List<Customer> result = queryFactory
        .selectFrom(customer)
        .orderBy(sortByGradeThenPoints)
        .fetch();
```

생성 SQL 은 위와 완전히 동일합니다. `orderBy(OrderSpecifier<?>...)` 오버로드가 배열을 그대로 받습니다.

---

## 9-2. NULL 정렬 — `nullsFirst()` / `nullsLast()`

`customers` 30명 중 **전화번호가 NULL 인 고객이 3명** 있습니다. 정렬할 때 이 3명을 어디에 둘지가 문제입니다.

먼저 아무것도 지정하지 않고 정렬합니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .orderBy(customer.phone.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by c1_0.phone asc
```
```
조회 30건
1. 한지호   phone=NULL
2. 안지수   phone=NULL
3. 문시우   phone=NULL
4. 김서준   phone=010-1002-...
...
```

MySQL 은 `ASC` 정렬에서 **NULL 을 가장 작은 값으로 취급**해 앞에 놓습니다.
문제는 이게 **DB 마다 다르다**는 점입니다. PostgreSQL 과 Oracle 은 `ASC` 에서 NULL 을 **뒤에** 놓습니다.
DB 를 바꾸면 페이지 1의 첫 3건이 조용히 달라집니다.

그래서 명시합니다.

```java
List<Customer> nullsLast = queryFactory
        .selectFrom(customer)
        .orderBy(customer.phone.asc().nullsLast())
        .fetch();
```

**결과** — MySQL 8 + Hibernate 6.4
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by case when c1_0.phone is null then 1 else 0 end, c1_0.phone asc
```
```
조회 30건
1. 김서준   phone=010-1002-...
...
28. 한지호  phone=NULL
29. 안지수  phone=NULL
30. 문시우  phone=NULL
```

**여기가 이 절의 핵심입니다.** `nullsLast()` 는 SQL 표준의 `nulls last` 키워드로 나가지 않았습니다.
**MySQL 에는 `NULLS FIRST / NULLS LAST` 문법이 없기 때문에**, Hibernate 의 MySQL 방언이
`case when ... is null then 1 else 0 end` 라는 정렬 키를 **앞에 하나 더 끼워 넣어** 흉내 냅니다.

`nullsFirst()` 는 `then 0 else 1` 로 뒤집힌 형태가 됩니다.

```java
queryFactory.selectFrom(customer)
        .orderBy(customer.phone.asc().nullsFirst())
        .fetch();
```

**결과**
```sql
order by case when c1_0.phone is null then 0 else 1 end, c1_0.phone asc
```

| DB | `nulls last` 지원 | Hibernate 가 내보내는 SQL |
|---|---|---|
| MySQL 8 | 없음 | `case when col is null then 1 else 0 end, col asc` (에뮬레이션) |
| PostgreSQL | 있음 | `col asc nulls last` |
| Oracle | 있음 | `col asc nulls last` |
| H2 | 있음 | `col asc nulls last` |

> ⚠️ **함정 — NULL 정렬 에뮬레이션이 인덱스를 죽입니다**
> 위 SQL 의 첫 번째 정렬 키는 `case when c1_0.phone is null then 1 else 0 end` 라는 **계산식**입니다.
> 인덱스는 `phone` 원본 값으로 정렬돼 있으므로, `phone` 에 인덱스가 있어도 이 정렬은 그 인덱스로 처리할 수 없습니다.
> `EXPLAIN` 에 `Using filesort` 가 남습니다. 이유는 9-7 에서 다루는 "함수를 씌우면 인덱스를 못 탄다"와 정확히 같습니다.
> **처방**: 정렬 대상 컬럼을 `NOT NULL` + 기본값으로 설계하거나, NULL 행을 `where col is not null` 로 먼저 걸러낸 뒤 정렬하십시오.
> 정렬 순서가 DB 기본값과 일치한다면(MySQL 의 `asc` + NULL 앞) `nullsFirst()` 를 **생략**하는 것도 정당한 선택입니다.
> 다만 그 선택은 "이 애플리케이션은 MySQL 을 벗어나지 않는다"는 약속과 짝을 이룹니다.

> 💡 정확한 에뮬레이션 SQL 의 모양(`then 1 else 0` 인지 `is null` 을 그대로 쓰는지)은 **Hibernate 버전과 방언에 따라 달라질 수 있습니다.**
> 위 로그는 Hibernate 6.4 + MySQL 8 에서 확인한 것입니다. 여러분의 콘솔에 다른 형태가 찍힌다면
> 그것이 여러분 환경의 정답이며, 중요한 것은 **"표준 키워드가 아니라 계산식으로 풀린다"** 는 사실입니다.

---

## 9-3. `offset` / `limit`

```java
List<Order> page = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.desc(), order.orderId.desc())
        .offset(20)     // 0-based. 앞의 20건을 건너뛴다
        .limit(10)      // 10건을 가져온다
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
바인딩: [1] 20  [2] 10
조회 10건 — 3페이지(0-based 로는 index 2)
order_id=571  2025-11-28  DELIVERED  268,000
order_id=568  2025-11-26  PAID       97,000
...
```

**`offset` 은 0-based 입니다.** `offset(0)` 이 1페이지, `offset(10)` 이 2페이지(페이지 크기 10 기준)입니다.
페이지 번호 → offset 변환은 `pageNumber * pageSize` 입니다.

> 💡 **MySQL 의 `limit ?, ?` 는 `limit offset, rowcount` 순서입니다.**
> Hibernate 6 의 MySQL 방언은 offset 이 있을 때 `limit ?, ?` 를, offset 이 없을 때 `limit ?` 를 내보냅니다.
> 방언에 따라 `limit ? offset ?` 형태로 나가는 경우도 있으니 **여러분의 로그를 기준으로 읽으십시오.**
> 어느 쪽이든 바인딩 값의 의미(건너뛸 수 / 가져올 수)는 같습니다.

`offset` 없이 `limit` 만 쓰면 `limit ?` 하나만 나갑니다.

```java
List<Order> top5 = queryFactory
        .selectFrom(order)
        .orderBy(order.totalAmount.desc(), order.orderId.asc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.total_amount desc, o1_0.order_id asc
limit ?
```
```
바인딩: [1] 5
조회 5건 — 최고 금액 주문 TOP 5
order_id=412  2,847,000
order_id=88   2,631,000
order_id=305  2,514,000
order_id=177  2,190,000
order_id=534  2,190,000
```

---

## 9-4. Spring `Pageable` 을 받아서

컨트롤러가 `?page=2&size=10` 을 받으면 Spring 이 `Pageable` 로 바인딩해 줍니다.
`Pageable` 은 offset 계산을 대신해 줍니다.

```java
public List<Order> findOrders(Pageable pageable) {
    return queryFactory
            .selectFrom(order)
            .orderBy(order.orderDate.desc(), order.orderId.desc())
            .offset(pageable.getOffset())        // long. page * size 를 계산해 준다
            .limit(pageable.getPageSize())       // int
            .fetch();
}
```

`PageRequest.of(2, 10)` 을 넘기면:

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
바인딩: [1] 20  [2] 10
pageable.getOffset() = 20   (page=2 * size=10)
조회 10건
```

| `Pageable` 메서드 | 반환 | QueryDSL 대응 |
|---|---|---|
| `getOffset()` | `long` | `.offset(long)` |
| `getPageSize()` | `int` | `.limit(long)` — 자동 확장 |
| `getPageNumber()` | `int` (0-based) | 직접 쓸 일은 거의 없음 |
| `getSort()` | `Sort` | `orderBy` 로 **직접 변환해야 함** → Step 10 |

> ⚠️ **`Pageable` 의 `Sort` 는 QueryDSL 에 자동으로 적용되지 않습니다.**
> `.offset()` / `.limit()` 은 받아 쓸 수 있지만, `pageable.getSort()` 를 QueryDSL 이 알아서 `order by` 로 바꿔 주지 않습니다.
> 클라이언트가 `?sort=totalAmount,desc` 를 보내도 **아무 일도 일어나지 않습니다.** 에러도 안 납니다.
> `Sort` → `OrderSpecifier[]` 변환기를 직접 만들어야 하고, 그것이 [Step 10](../step-10-dynamic-sort/) 의 주제입니다.

---

## 9-5. `Page` 만들기 — 콘텐츠 쿼리 + count 쿼리

`Page<T>` 는 **콘텐츠 + 전체 건수**를 함께 담습니다. 전체 건수를 알아야 "총 60페이지 중 3페이지" 를 그릴 수 있습니다.
그러므로 쿼리가 **두 번** 나갑니다.

```java
public Page<Order> searchOrders(Pageable pageable) {

    // ① 콘텐츠 쿼리
    List<Order> content = queryFactory
            .selectFrom(order)
            .where(order.status.eq(OrderStatus.DELIVERED))
            .orderBy(order.orderDate.desc(), order.orderId.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    // ② count 쿼리
    Long total = queryFactory
            .select(order.count())
            .from(order)
            .where(order.status.eq(OrderStatus.DELIVERED))
            .fetchOne();

    return new PageImpl<>(content, pageable, total);
}
```

**결과** — 쿼리 2건
```sql
-- ① 콘텐츠
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```sql
-- ② count
select count(o1_0.order_id)
from orders o1_0
where o1_0.status = ?
```
```
바인딩 ①: [1] DELIVERED  [2] 0  [3] 10
바인딩 ②: [1] DELIVERED
content 10건 / total 214건 / totalPages 22
```

`order.count()` 는 `count(o1_0.order_id)` 로 번역됩니다. `count(*)` 가 아닙니다.
PK 는 `NOT NULL` 이므로 결과값은 `count(*)` 와 같지만, **생성 SQL 은 다릅니다.**

> 📌 MySQL8 코스 [Step 06 — 집계와 GROUP BY](../../mysql8/step-06-aggregate-groupby/) 에서 다뤘던
> `COUNT(*)` vs `COUNT(컬럼)` 의 차이(NULL 을 세지 않는다)가 여기서 그대로 적용됩니다.
> `order.count()` 는 PK 를 세므로 안전하지만, `order.customer.customerId.count()` 처럼 NULL 가능 컬럼을 세면
> 결과가 달라집니다. Step 08 의 8-2 에서 확인했습니다.

---

## 9-6. count 쿼리를 따로 최적화한다

이 절이 이 스텝에서 가장 실무적인 부분입니다.

### 왜 `fetchCount()` 를 쓰지 않는가

QueryDSL 5.0 부터 `fetchCount()` 와 `fetchResults()` 는 **deprecated** 입니다. 6.x 에서도 그대로 deprecated 입니다.

```java
// deprecated — 쓰지 마십시오
long total = queryFactory.selectFrom(order)
        .where(order.status.eq(OrderStatus.DELIVERED))
        .fetchCount();
```

문제는 이 메서드가 **원래 쿼리를 그대로 감싸서** count 로 바꾼다는 데 있습니다.
`select` 절이 복잡하거나 `groupBy` 가 붙어 있거나 조인이 여럿이면, 자동 변환이 만들어내는 SQL 이
의도한 것과 다르거나 아예 실패합니다. QueryDSL 팀이 "자동 변환은 신뢰할 수 없다"고 판단해 폐기했습니다.

> 📌 [Step 03 — 기본 조회](../step-03-basic-query/) 의 3-6 에서 `fetchResults()` / `fetchCount()` 의
> deprecation 배경과 경고 로그를 다뤘습니다. 여기서는 **대체 방법**에 집중합니다.

### count 쿼리는 콘텐츠 쿼리와 다른 쿼리다

핵심 통찰은 이것입니다.
**콘텐츠 쿼리에 필요한 것들이 count 쿼리에는 대부분 불필요합니다.**

| 요소 | 콘텐츠 쿼리 | count 쿼리 |
|---|---|---|
| `where` 조건 | 필요 | **필요** (건수가 달라짐) |
| `order by` | 필요 | **불필요** — 세는 데 순서는 의미 없음 |
| `join` (표시용 컬럼 때문에) | 필요 | **불필요** |
| `join` (where 조건 때문에) | 필요 | 필요 |
| `limit / offset` | 필요 | 불필요 |
| `select` 컬럼 목록 | 필요 | 불필요 (`count()` 하나) |

그러니 **count 쿼리는 직접 씁니다.** 조인과 정렬을 뺍니다.

```java
// 콘텐츠 쿼리 — 고객 이름을 함께 보여줘야 하므로 join 이 필요하다
List<OrderListDto> content = queryFactory
        .select(new QOrderListDto(
                order.orderId, order.orderDate, order.totalAmount, customer.name))
        .from(order)
        .join(order.customer, customer)
        .where(order.status.eq(OrderStatus.DELIVERED))
        .orderBy(order.orderDate.desc(), order.orderId.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

// count 쿼리 — join 도 orderBy 도 없다
JPAQuery<Long> countQuery = queryFactory
        .select(order.count())
        .from(order)
        .where(order.status.eq(OrderStatus.DELIVERED));
```

**결과** — 콘텐츠
```sql
select o1_0.order_id, o1_0.order_date, o1_0.total_amount, c1_0.name
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```

**결과** — count
```sql
select count(o1_0.order_id)
from orders o1_0
where o1_0.status = ?
```

`join customers` 가 사라졌습니다. `order by` 도 사라졌습니다.
600건에서는 차이가 없지만, `orders` 가 수백만 건이고 조인 대상이 3~4개면 이 차이가 응답시간 전부입니다.

> ⚠️ **함정 — 조인을 무조건 빼면 안 됩니다**
> `where` 조건이 조인 대상 테이블의 컬럼을 참조한다면 그 조인은 count 쿼리에도 **반드시 남아야 합니다.**
> 예를 들어 `where(customer.grade.eq(Grade.VIP))` 가 있으면 `join(order.customer, customer)` 를 빼는 순간
> 조건이 성립하지 않아 **컴파일은 되는데 JPQL 파싱에서 터지거나, 조건이 무시된 엉뚱한 건수가 나옵니다.**
> 규칙: **표시(select)를 위한 조인만 뺀다. 필터(where)를 위한 조인은 남긴다.**
> 또한 `@ManyToOne` 이 `NOT NULL` FK 일 때 `inner join` 은 행 수를 바꾸지 않지만,
> `@OneToMany` 조인은 **행이 뻥튀기되어 count 가 커집니다**(Step 08 의 8-7 에서 다룬 문제).
> count 쿼리에 컬렉션 조인이 남아 있으면 `countDistinct()` 가 필요한지 반드시 확인하십시오.

### `PageableExecutionUtils` — count 쿼리를 아예 안 보낸다

여기서 한 걸음 더 갈 수 있습니다.
**count 쿼리가 필요 없는 경우가 있습니다.**

- **첫 페이지인데 조회 결과가 페이지 크기보다 작다** → 전체 건수 = 조회된 건수
  (`offset=0`, `size=10` 인데 7건 나왔다면 전체가 7건입니다)
- **마지막 페이지다** → 전체 건수 = `offset + 조회된 건수`
  (`offset=200`, `size=10` 인데 4건 나왔다면 전체가 204건입니다)

이 두 경우에 count 쿼리를 보내는 것은 순수한 낭비입니다.
Spring Data 가 이 판단을 대신해 주는 유틸이 `PageableExecutionUtils` 입니다.

```java
public Page<OrderListDto> searchOrders(Pageable pageable) {

    List<OrderListDto> content = queryFactory
            .select(new QOrderListDto(
                    order.orderId, order.orderDate, order.totalAmount, customer.name))
            .from(order)
            .join(order.customer, customer)
            .where(order.status.eq(OrderStatus.DELIVERED))
            .orderBy(order.orderDate.desc(), order.orderId.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    JPAQuery<Long> countQuery = queryFactory
            .select(order.count())
            .from(order)
            .where(order.status.eq(OrderStatus.DELIVERED));

    // countQuery::fetchOne 은 Supplier<Long>. 필요할 때만 호출된다.
    return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
}
```

**핵심은 세 번째 인자가 `Supplier<Long>` 이라는 점입니다.** `countQuery.fetchOne()` 을 미리 호출해 값을 넘기는 게 아니라,
`countQuery::fetchOne` 이라는 **호출 가능한 것**을 넘깁니다. `PageableExecutionUtils` 가 위 조건을 검사해
필요할 때만 `get()` 을 부릅니다.

**결과 ① — `PageRequest.of(0, 10)`, DELIVERED 214건**
```sql
select o1_0.order_id, o1_0.order_date, o1_0.total_amount, c1_0.name
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```sql
select count(o1_0.order_id)
from orders o1_0
where o1_0.status = ?
```
```
쿼리 2건 — 10건이 꽉 찼으므로 전체 건수를 알 수 없다. count 실행됨.
total = 214
```

**결과 ② — `PageRequest.of(0, 500)`, DELIVERED 214건**
```sql
select o1_0.order_id, o1_0.order_date, o1_0.total_amount, c1_0.name
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
쿼리 1건 — count 쿼리가 나가지 않았다.
offset=0 이고 조회 결과(214)가 pageSize(500)보다 작으므로 total = 214 로 확정.
total = 214
```

**결과 ③ — `PageRequest.of(21, 10)` (마지막 페이지), DELIVERED 214건**
```sql
select o1_0.order_id, o1_0.order_date, o1_0.total_amount, c1_0.name
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
바인딩: [1] DELIVERED  [2] 210  [3] 10
쿼리 1건 — count 쿼리가 나가지 않았다.
조회 4건 < pageSize 10 → total = offset(210) + 4 = 214 로 확정.
total = 214
```

②와 ③에서 **count SQL 로그가 아예 없습니다.** 이것을 눈으로 확인하는 것이 이 절의 목표입니다.
`hibernate.SQL` 로그를 켜 두고 페이지 번호를 바꿔 가며 실행해 보십시오.

> ⚠️ **`PageableExecutionUtils` 의 패키지가 버전에 따라 다릅니다**
> 초기 Spring Data 에서는 `org.springframework.data.repository.support.PageableExecutionUtils` 였고,
> 이후 `org.springframework.data.support.PageableExecutionUtils` 로 옮겨졌습니다.
> 블로그 글을 복사하면 `import` 가 안 잡히는 일이 흔합니다.
> **정확한 패키지는 Spring Data 버전에 따라 다르므로, IDE 자동완성으로 확인하십시오.**
> 클래스명 `PageableExecutionUtils` 만 타이핑하고 자동 import 를 쓰는 것이 가장 확실합니다.
> (이 코스는 Spring Boot 3.2.5 기준입니다.)

> 💡 **실무 팁 — count 를 아예 없애는 것도 선택지입니다**
> 총 페이지 수를 화면에 꼭 보여줘야 합니까? 무한 스크롤이나 "더 보기" UI 라면 필요 없습니다.
> 그런 화면에는 `Slice` (9-10) 나 키셋 페이징 (9-8) 이 훨씬 낫습니다.
> **count 쿼리는 화면 요구사항이 강제할 때만 지불하는 비용**입니다.

---

## 9-7. ⚠️ 정렬 컬럼에 함수를 씌워 인덱스를 죽인다

이 스텝의 대표 함정입니다. 컴파일도 되고, 결과도 정확하고, 30건에서는 즉시 응답합니다.

### 잘못된 코드

"연도 기준 최신순으로 주문을 보여 달라"는 요구를 이렇게 씁니다.

```java
List<Order> result = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.year().desc(), order.orderId.desc())
        .limit(20)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by year(o1_0.order_date) desc, o1_0.order_id desc
limit ?
```
```
바인딩: [1] 20
조회 20건 — 결과는 정확합니다
```

고객 이름을 대소문자 무시하고 정렬하는 것도 같은 문제입니다.

```java
List<Customer> result = queryFactory
        .selectFrom(customer)
        .orderBy(customer.name.lower().asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
order by lower(c1_0.name) asc
```

### 왜 틀렸는가

**인덱스는 컬럼의 원본 값으로 정렬돼 있습니다.**
`(order_date)` 인덱스의 리프 노드는 `2024-01-02`, `2024-01-05`, ... 순으로 물리적으로 늘어서 있습니다.
`year(order_date)` 는 그와 **다른 값**입니다. 인덱스 어디에도 `2024`, `2025` 라는 값은 저장돼 있지 않습니다.
따라서 MySQL 은 인덱스의 정렬 순서를 재사용할 수 없고, **모든 행을 읽어 메모리(또는 디스크)에서 다시 정렬**합니다.

> 📌 MySQL8 코스 [Step 15 — 인덱스](../../mysql8/step-15-indexes/) 의 15-7 (1) 에서
> "인덱스 컬럼에 함수를 씌우면 못 탄다"를 `WHERE MONTH(logged_at) = 6` 으로 확인했습니다.
> **`ORDER BY` 에서도 똑같습니다.** `WHERE` 만의 이야기가 아닙니다.
> 함수 자체의 동작은 [Step 12 — 내장 함수](../../mysql8/step-12-builtin-functions/) 를 참고하십시오.

100만 행 `access_logs` 에서 같은 모양의 SQL 을 돌려 확인합니다.
(`idx_time (logged_at)` 인덱스가 있는 상태입니다.)

```sql
EXPLAIN SELECT log_id, logged_at FROM access_logs
ORDER BY YEAR(logged_at) DESC, log_id DESC LIMIT 20;
```

**결과**
```
+------+---------------+------+---------+--------+----------------+
| type | possible_keys | key  | key_len | rows   | Extra          |
| ALL  | NULL          | NULL | NULL    | 996151 | Using filesort |
+------+---------------+------+---------+--------+----------------+
```

`type: ALL` + `Using filesort`. 100만 행을 다 읽고 전부 정렬한 다음 20건만 꺼냅니다.

```sql
SELECT log_id, logged_at FROM access_logs
ORDER BY YEAR(logged_at) DESC, log_id DESC LIMIT 20;
```
```
20 rows in set (1.284 sec)
```

**1.284초.**

### 고친 코드

정렬은 **원본 컬럼**으로 합니다. 함수는 표시용으로만 씁니다.

```java
List<Order> result = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.desc(), order.orderId.desc())   // 원본 컬럼
        .limit(20)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_date desc, o1_0.order_id desc
limit ?
```

`order_date` 로 내림차순 정렬한 결과는 `year(order_date)` 로 내림차순 정렬한 것과
**연도 단위로는 동일한 순서**입니다. 애초에 함수가 필요 없었습니다.

100만 행에서 다시 측정합니다.

```sql
EXPLAIN SELECT log_id, logged_at FROM access_logs
ORDER BY logged_at DESC, log_id DESC LIMIT 20;
```

**결과**
```
+-------+---------------+----------+---------+-------+---------------------+
| type  | possible_keys | key      | key_len | rows  | Extra               |
| index | NULL          | idx_time | 5       | 20    | Backward index scan |
+-------+---------------+----------+---------+-------+---------------------+
```

`rows: 20`. 인덱스를 뒤에서부터 20건만 읽고 끝냅니다. `Using filesort` 가 사라졌습니다.

```sql
SELECT log_id, logged_at FROM access_logs ORDER BY logged_at DESC, log_id DESC LIMIT 20;
```
```
20 rows in set (0.002 sec)
```

| 정렬 방식 | EXPLAIN | 읽은 행 | 실행시간 |
|---|---|---:|---:|
| `ORDER BY YEAR(logged_at) DESC` | `ALL` + `Using filesort` | 996,151 | **1.284초** |
| `ORDER BY logged_at DESC` | `index` + `Backward index scan` | 20 | **0.002초** |

**1.284초 → 0.002초. 약 640배.**

### 함수 정렬이 정말 필요할 때

대소문자 무시 정렬(`lower(name)`)처럼 원본 컬럼으로는 대체할 수 없는 경우가 있습니다.
두 가지 처방이 있습니다.

**(1) 컬레이션을 바꾼다.** `shop` 스키마는 이미 `utf8mb4_0900_ai_ci` — **대소문자 구분 없는(ci)** 컬레이션입니다.
즉 `ORDER BY name` 이 이미 대소문자를 무시합니다. `lower()` 는 처음부터 불필요했습니다.
이 사실을 모르고 `lower()` 를 씌우는 것이 실무에서 가장 흔한 형태입니다.

**(2) 생성 컬럼 + 인덱스.** 정말 다른 값으로 정렬해야 한다면 그 값을 컬럼으로 만들고 인덱스를 겁니다.

```sql
ALTER TABLE customers
  ADD COLUMN name_sort VARCHAR(50) AS (LOWER(name)) STORED,
  ADD INDEX idx_name_sort (name_sort);
```

> 📌 생성 컬럼(Generated Column)과 함수 기반 인덱스는 MySQL8 코스
> [Step 14 — 뷰와 생성 컬럼](../../mysql8/step-14-views-generated/) 에서 다룹니다.
> QueryDSL 에서는 이 컬럼을 엔티티 필드(`@Column(insertable=false, updatable=false)`)로 매핑하면
> `customer.nameSort.asc()` 로 타입 안전하게 정렬할 수 있습니다.

> ⚠️ **공용 테이블에 인덱스를 만들지 마십시오.**
> 위 `ALTER TABLE` 은 설명용입니다. `customers` 를 포함한 공용 실습 테이블에는 인덱스를 추가하지 마십시오.
> 다른 스텝의 `EXPLAIN` 결과가 달라집니다. 실험은 `access_logs` 에서만 하고 끝나면 `DROP INDEX` 로 정리하십시오.

---

## 9-8. ⚠️ 깊은 offset 의 비용

두 번째 대표 함정입니다. 이건 **코드가 틀린 게 아니라 접근 방식이 확장되지 않는** 경우입니다.

### 문제

```java
List<Order> page5001 = queryFactory
        .selectFrom(order)
        .orderBy(order.orderId.desc())
        .offset(100_000)
        .limit(20)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_id desc
limit ?, ?
```
```
바인딩: [1] 100000  [2] 20
```

SQL 자체는 완벽합니다. 문제는 **DB 가 이 SQL 을 어떻게 실행하는가**입니다.

`LIMIT 100000, 20` 은 DB 에게 이렇게 말합니다.
**"정렬된 순서로 100,020개를 읽어라. 그리고 앞의 100,000개는 버려라."**

DB 에는 "정렬 결과의 100,001번째로 바로 점프하는" 방법이 없습니다.
B+Tree 인덱스도 "N번째 리프"를 직접 가리키지 못합니다. 처음부터 세면서 가야 합니다.
**offset 이 깊어질수록 버리는 행의 수만큼 비용이 증가합니다.**

100만 행 `access_logs` 로 측정합니다.

```sql
SELECT log_id, logged_at FROM access_logs ORDER BY log_id DESC LIMIT 0, 20;
```
```
20 rows in set (0.001 sec)
```

```sql
SELECT log_id, logged_at FROM access_logs ORDER BY log_id DESC LIMIT 100000, 20;
```
```
20 rows in set (0.087 sec)
```

```sql
SELECT log_id, logged_at FROM access_logs ORDER BY log_id DESC LIMIT 500000, 20;
```
```
20 rows in set (0.412 sec)
```

```sql
SELECT log_id, logged_at FROM access_logs ORDER BY log_id DESC LIMIT 900000, 20;
```
```
20 rows in set (0.741 sec)
```

| offset | 실제로 읽는 행 | 실행시간 |
|---:|---:|---:|
| 0 | 20 | 0.001초 |
| 100,000 | 100,020 | 0.087초 |
| 500,000 | 500,020 | 0.412초 |
| 900,000 | 900,020 | 0.741초 |

**offset 에 정비례합니다.** 반환하는 행은 늘 20건인데 말입니다.

> 💡 사용자가 5,001페이지를 실제로 볼까요? 대부분 안 봅니다. 하지만 **크롤러와 배치는 봅니다.**
> "전체 목록을 페이지 단위로 순회하는" 배치 잡이 offset 을 계속 늘려 가면
> 뒤로 갈수록 느려지다가 타임아웃으로 죽습니다. 이 사고는 항상 **운영 데이터가 커진 뒤에** 발생합니다.

### 처방 — 키셋(커서) 페이징

발상을 뒤집습니다. "몇 개를 건너뛸까" 대신 **"어디부터 읽을까"** 를 지정합니다.
직전 페이지의 마지막 행 값(커서)을 클라이언트가 들고 오게 하고, `where` 로 그 지점부터 읽습니다.

```java
public List<Order> nextPage(Long lastSeenOrderId, int size) {
    return queryFactory
            .selectFrom(order)
            .where(lastSeenOrderId == null ? null : order.orderId.lt(lastSeenOrderId))
            .orderBy(order.orderId.desc())
            .limit(size)
            .fetch();
}
```

`where` 에 `null` 을 넘기면 QueryDSL 이 그 조건을 **무시**합니다(Step 04 의 4-4).
그래서 첫 페이지(`lastSeenOrderId == null`)는 조건 없이 나갑니다.

**결과 — 첫 페이지**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_id desc
limit ?
```
```
바인딩: [1] 20
조회 20건 — 마지막 order_id = 581
```

**결과 — 다음 페이지 (`lastSeenOrderId = 581`)**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.order_id < ?
order by o1_0.order_id desc
limit ?
```
```
바인딩: [1] 581  [2] 20
조회 20건 — 마지막 order_id = 561
```

`offset` 이 사라지고 `where order_id < ?` 가 생겼습니다.
**이 조건은 인덱스로 즉시 탐색(seek)됩니다.** PK 인덱스에서 581 지점으로 바로 내려간 뒤
거기서부터 20건을 읽고 끝냅니다. 몇 번째 페이지든 읽는 행은 항상 20건입니다.

100만 행에서 확인합니다.

```sql
EXPLAIN SELECT log_id, logged_at FROM access_logs
WHERE log_id < 100000 ORDER BY log_id DESC LIMIT 20;
```

**결과**
```
+-------+---------------+---------+---------+------+-------------+
| type  | possible_keys | key     | key_len | rows | Extra       |
| range | PRIMARY       | PRIMARY | 8       | 20   | Using where |
+-------+---------------+---------+---------+------+-------------+
```

```sql
SELECT log_id, logged_at FROM access_logs WHERE log_id < 100000 ORDER BY log_id DESC LIMIT 20;
```
```
20 rows in set (0.001 sec)
```

```sql
SELECT log_id, logged_at FROM access_logs WHERE log_id < 500000 ORDER BY log_id DESC LIMIT 20;
```
```
20 rows in set (0.001 sec)
```

| 방식 | offset 100,000 | offset 500,000 | offset 900,000 |
|---|---:|---:|---:|
| offset 페이징 | 0.087초 | 0.412초 | 0.741초 |
| 키셋 페이징 | **0.001초** | **0.001초** | **0.001초** |

**0.412초 → 0.001초. 400배 이상이며, 더 중요한 것은 offset 이 깊어져도 느려지지 않는다는 점입니다.**

### 정렬 키가 PK 가 아닐 때의 키셋

`order_date` 로 정렬하면서 키셋을 쓰려면 **커서가 복합 값**이 됩니다.
`order_date` 는 중복될 수 있으므로 PK 를 함께 들고 가야 합니다.

```java
public List<Order> nextPageByDate(LocalDateTime lastDate, Long lastId, int size) {
    BooleanExpression cursor = (lastDate == null) ? null :
            order.orderDate.lt(lastDate)
                    .or(order.orderDate.eq(lastDate).and(order.orderId.lt(lastId)));

    return queryFactory
            .selectFrom(order)
            .where(cursor)
            .orderBy(order.orderDate.desc(), order.orderId.desc())
            .limit(size)
            .fetch();
}
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.order_date < ? or o1_0.order_date = ? and o1_0.order_id < ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?
```
```
바인딩: [1] 2025-11-26T00:00  [2] 2025-11-26T00:00  [3] 568  [4] 20
조회 20건
```

**생성 SQL 의 괄호를 보십시오.** `a < ? or a = ? and b < ?` 로 괄호가 없습니다.
SQL 에서 `AND` 가 `OR` 보다 우선순위가 높으므로 이것은 `a < ? or (a = ? and b < ?)` 와 같습니다.
**의도한 대로입니다.** 그러나 이건 운입니다.

> ⚠️ **함정 — `.or()` 조립에서 사라지는 괄호**
> [Step 04](../step-04-where-conditions/) 의 4-5 에서 다뤘듯이 QueryDSL 은 불필요하다고 판단한 괄호를 생략합니다.
> 위 경우는 우연히 의도와 일치했지만, 조건 순서가 조금만 바뀌면 달라집니다.
> **키셋 조건처럼 `and`/`or` 가 섞이는 표현식은 반드시 생성 SQL 을 눈으로 확인하십시오.**
> 확실하게 하려면 `Expressions.anyOf(...)` / `Expressions.allOf(...)` 로 그룹을 명시하십시오.

> 💡 **키셋 페이징의 한계**
> - "7페이지로 바로 가기" 가 불가능합니다. 순차 이동(다음/이전)만 됩니다.
> - 정렬 조건이 바뀌면 커서 형태도 바뀝니다. 동적 정렬(Step 10)과 결합하기가 까다롭습니다.
> - 전체 건수를 모릅니다(별도 count 가 필요).
> 그래서 **"무한 스크롤·피드·배치 순회" 에는 키셋, "페이지 번호를 찍어 주는 관리자 화면" 에는 offset** 이 보통의 선택입니다.
> 관리자 화면이라면 offset 이 깊어질 일 자체가 드뭅니다.

---

## 9-9. ⚠️ 정렬 기준이 유일하지 않으면 행이 새거나 중복된다

가장 조용한 함정입니다. **에러도 없고, 한 번 실행해서는 절대 안 보입니다.**

### 재현

주문을 날짜 내림차순으로 10건씩 페이징합니다.

```java
// 1페이지
List<Order> page1 = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.desc())     // 정렬 키가 하나뿐
        .offset(0).limit(10)
        .fetch();

// 2페이지
List<Order> page2 = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.desc())
        .offset(10).limit(10)
        .fetch();
```

**결과** — 1페이지
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_date desc
limit ?, ?
```
```
바인딩: [1] 0  [2] 10
order_id=598  2025-12-30
order_id=597  2025-12-30
order_id=600  2025-12-30      ← 같은 날짜 3건. 이 셋의 순서는 보장되지 않는다
order_id=595  2025-12-28
...
order_id=588  2025-12-21
```

**결과** — 2페이지
```
바인딩: [1] 10  [2] 10
order_id=587  2025-12-20
...
order_id=578  2025-12-14
```

여기까지는 멀쩡해 보입니다. 그런데 같은 코드를 다시 실행하면 1페이지가 이렇게 나올 수 있습니다.

```
order_id=600  2025-12-30      ← 순서가 바뀌었다
order_id=598  2025-12-30
order_id=597  2025-12-30
order_id=595  2025-12-28
...
```

### 왜 이런 일이 벌어지는가

**SQL 표준은 `ORDER BY` 로 지정되지 않은 부분의 순서를 보장하지 않습니다.**
`order_date` 가 같은 3건 사이의 순서는 **DB 가 마음대로 정합니다.**
그리고 그 "마음대로"는 실행할 때마다 달라질 수 있습니다.

- 옵티마이저가 다른 실행 계획을 고르면 (통계 갱신, 인덱스 추가, 데이터 증가)
- 정렬 알고리즘이 메모리 정렬에서 디스크 정렬로 넘어가면 (`sort_buffer_size` 초과)
- 병렬 읽기나 버퍼 풀 상태가 달라지면

1페이지 조회와 2페이지 조회는 **서로 다른 SQL 실행**입니다.
그 사이에 순서가 뒤바뀌면 어떤 일이 일어날까요?

```
1페이지 실행 시점의 순서:  ... 598, 597, 600 | 595 ...    → 1페이지에 600 포함
2페이지 실행 시점의 순서:  ... 600, 598, 597 | 595 ...    → 2페이지 시작은 595
                                                            → 600 은 1페이지에만
```

반대 방향으로 뒤바뀌면:

```
1페이지 실행 시점:  ... 598, 597, 600 | 595 ...           → 1페이지 마지막은 588
2페이지 실행 시점:  ... 597, 600, 598 | 595 ...           → 598 이 11번째로 밀림
                                                            → 598 이 1페이지와 2페이지에 모두 등장
```

**결과적으로 어떤 행은 어느 페이지에도 안 나오고(누락), 어떤 행은 두 페이지에 나옵니다(중복).**
사용자는 "목록에서 방금 본 주문이 다음 페이지에 또 있네" 정도로 느끼고 넘어갑니다.
버그 리포트로 올라와도 재현이 안 됩니다.

### 처방 — 타이브레이커

**정렬 키의 조합이 행을 유일하게 결정하도록 만듭니다.**
가장 쉬운 방법은 **PK 를 마지막 정렬 키로 추가**하는 것입니다.

```java
List<Order> page1 = queryFactory
        .selectFrom(order)
        .orderBy(order.orderDate.desc(), order.orderId.desc())   // PK 타이브레이커
        .offset(0).limit(10)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
order_id=600  2025-12-30      ← 몇 번을 실행해도 이 순서
order_id=598  2025-12-30
order_id=597  2025-12-30
order_id=595  2025-12-28
...
```

`order_id` 는 PK 이므로 중복이 없습니다. 따라서 `(order_date, order_id)` 조합은 **모든 행에 대해 유일**합니다.
전순서(total order)가 확정되어 실행 계획이 어떻게 바뀌든 결과 순서가 같습니다.

> 💡 **규칙으로 만드십시오 — 페이징 쿼리의 `orderBy` 마지막은 언제나 PK**
> 코드 리뷰 체크리스트에 넣을 만한 규칙입니다.
> "`offset`/`limit` 이 있는데 `orderBy` 에 PK 가 없다" 는 그 자체로 결함입니다.
> 정렬 키가 이미 유니크 컬럼(예: `email`)이라면 생략해도 되지만, 판단할 시간에 PK 를 붙이는 편이 낫습니다.
> 비용은 `order by` 절에 컬럼 하나 추가하는 것뿐이며, 대개 인덱스의 마지막 컬럼이라 추가 비용이 없습니다.

> ⚠️ 이 문제는 `Page` 든 `Slice` 든 키셋이든 **모든 페이징 방식에 공통**입니다.
> 특히 키셋 페이징에서는 더 치명적입니다. 커서 값이 유일하지 않으면 **커서 지점을 잘못 잡아 행을 통째로 건너뜁니다.**
> 9-8 의 복합 커서(`orderDate` + `orderId`)가 바로 이 문제에 대한 대응입니다.

---

## 9-10. `Slice` — count 를 아예 쓰지 않는다

"다음 페이지가 있는가"만 알면 되는 화면(무한 스크롤, 더 보기 버튼)에서는
전체 건수가 필요 없습니다. Spring Data 의 `Slice<T>` 가 이 모델입니다.

방법은 단순합니다. **요청한 크기보다 1건 더 가져옵니다.**

```java
public Slice<Order> searchSlice(Pageable pageable) {

    List<Order> content = queryFactory
            .selectFrom(order)
            .where(order.status.eq(OrderStatus.DELIVERED))
            .orderBy(order.orderDate.desc(), order.orderId.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize() + 1)      // +1
            .fetch();

    boolean hasNext = content.size() > pageable.getPageSize();
    if (hasNext) {
        content.remove(pageable.getPageSize());     // 여분 1건 제거
    }

    return new SliceImpl<>(content, pageable, hasNext);
}
```

**결과** — `PageRequest.of(0, 10)`
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.status = ?
order by o1_0.order_date desc, o1_0.order_id desc
limit ?, ?
```
```
바인딩: [1] DELIVERED  [2] 0  [3] 11      ← 10 이 아니라 11
쿼리 1건 — count 쿼리 없음
조회 11건 → hasNext=true, 1건 제거 후 content 10건
```

**결과** — 마지막 슬라이스 (`PageRequest.of(21, 10)`, DELIVERED 214건)
```
바인딩: [1] DELIVERED  [2] 210  [3] 11
조회 4건 → 11보다 작으므로 hasNext=false, content 4건
```

count 쿼리가 **한 번도 나가지 않습니다.**

> ⚠️ **`content.remove(...)` 가 `UnsupportedOperationException` 을 던질 수 있습니다**
> `fetch()` 가 반환하는 리스트가 항상 수정 가능한 `ArrayList` 라는 보장은 없습니다.
> 안전하게 하려면 `new ArrayList<>(content)` 로 감싸거나
> `content.subList(0, pageable.getPageSize())` 로 잘라내십시오.
> 후자는 뷰(view)를 반환하므로 원본 참조가 남는다는 점에 주의하십시오.

> 💡 **`Slice` 도 `offset` 을 쓰므로 9-8 의 깊은 offset 문제를 그대로 안습니다.**
> `Slice` 가 없애는 것은 **count 쿼리**이지 offset 비용이 아닙니다.
> 무한 스크롤을 아주 깊게 내려가는 화면이라면 `Slice` + offset 이 아니라 **키셋 페이징**을 쓰십시오.
> 이 둘을 혼동하는 글이 많습니다.

---

## 9-11. 페이징 전략 비교

세 가지 방식의 성격을 정리합니다.

| 항목 | offset 페이징 (`Page`) | offset 페이징 (`Slice`) | 키셋(커서) 페이징 |
|---|---|---|---|
| 쿼리 수 | 2 (count 생략 가능) | 1 | 1 |
| 전체 건수 | 알 수 있음 | 모름 | 모름 |
| 총 페이지 수 표시 | 가능 | 불가 | 불가 |
| N페이지 직접 이동 | 가능 | 가능 | **불가** |
| 깊은 offset 성능 | **선형 악화** | **선형 악화** | 일정 |
| 페이지 사이 누락/중복 | 데이터 변경 시 발생 | 데이터 변경 시 발생 | **원리적으로 없음** |
| 동적 정렬과의 궁합 | 좋음 | 좋음 | 나쁨(커서 재설계 필요) |
| 대표 용도 | 관리자 목록 화면 | 더 보기 버튼 | 무한 스크롤, 배치 순회, 피드 |

> 💡 **"페이지 사이 누락/중복" 은 9-9 와 다른 이야기입니다**
> 9-9 는 **정렬이 유일하지 않아서** 생기는 문제이고, 타이브레이커로 해결됩니다.
> 여기 표의 항목은 **1페이지와 2페이지 조회 사이에 데이터가 삽입/삭제되어** 생기는 문제입니다.
> 1페이지를 본 뒤 누군가 새 주문을 넣으면 모든 행이 한 칸씩 밀려 2페이지 첫 행이 1페이지 마지막 행과 같아집니다.
> **offset 페이징에서는 원리적으로 막을 수 없습니다.** 키셋은 "값 기준"이라 이 문제가 없습니다.

### 선택 기준

1. 화면에 **총 건수/총 페이지 수**를 표시해야 하는가? → 그렇다면 `Page`
2. 아니라면, offset 이 깊어질 수 있는가? → 그렇다면 **키셋**
3. 깊어지지 않는다면 `Slice` 로 충분
4. `Page` 를 쓰더라도 **count 쿼리는 직접 작성**하고 `PageableExecutionUtils` 로 감쌀 것 (9-6)
5. 어느 방식이든 **`orderBy` 마지막은 PK** (9-9)

---

## 정리

| 개념 | 핵심 |
|---|---|
| `orderBy` | `OrderSpecifier` 가변 인자. **인자 순서 = 정렬 우선순위** |
| `.asc()` / `.desc()` | SQL 의 `asc` / `desc` 로 그대로 번역 |
| `nullsFirst/Last` | MySQL 에는 표준 문법이 없어 **`case when ... is null` 계산식으로 에뮬레이션** → 인덱스 못 탐 |
| `offset` / `limit` | 0-based. MySQL 방언에서 `limit ?, ?` (offset, rowcount) |
| `Pageable` | `getOffset()`/`getPageSize()` 는 쓸 수 있지만 **`getSort()` 는 자동 적용 안 됨** |
| `PageImpl` | 콘텐츠 + total. 쿼리가 2번 나감 |
| count 쿼리 분리 | 표시용 join 과 orderBy 를 뺀다. **필터용 join 은 남긴다** |
| `fetchCount()` | deprecated. 자동 변환을 신뢰할 수 없어 폐기됨 |
| `PageableExecutionUtils` | 첫 페이지가 다 안 찼거나 마지막 페이지면 **count 쿼리 생략**. 패키지는 버전마다 다르니 IDE 로 확인 |
| 정렬 컬럼에 함수 | `year(col)`, `lower(col)` → `Using filesort`. **1.284초 vs 0.002초** |
| 깊은 offset | `limit 100000, 20` 은 100,020행 읽고 100,000행 버림. offset 에 정비례 |
| 키셋 페이징 | `where(id.lt(lastSeen))`. offset 깊이와 무관하게 **0.001초** |
| 타이브레이커 | 정렬이 유일하지 않으면 페이지 사이 **누락/중복**. `orderBy` 마지막은 PK |
| `Slice` | `limit(size + 1)` 로 다음 페이지 존재만 판단. **count 쿼리 없음**. offset 비용은 그대로 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.
**답이 맞아도 생성 SQL 이 다르면 틀린 것입니다.** 콘솔 로그를 반드시 대조하십시오.

1. VIP 고객을 포인트 내림차순, 이름 오름차순으로 정렬하고 생성 SQL 의 `order by` 절을 확인하기
2. 전화번호가 NULL 인 고객 3명을 **맨 뒤**로 보내는 정렬을 작성하고, 생성 SQL 에 `nulls last` 가 아니라 무엇이 나오는지 기록하기
3. `PageRequest` 를 받아 배송 완료 주문의 `Page<Order>` 를 만들되, **count 쿼리를 직접 작성**하고 `PageableExecutionUtils` 로 감싸기
4. 3번에서 만든 메서드에 `PageRequest.of(0, 1000)` 을 넘겨 **count 쿼리가 나가지 않는 것**을 로그로 확인하기
5. `orderBy(order.orderDate.year().desc())` 로 짠 쿼리를 인덱스를 탈 수 있는 형태로 고치고, 두 SQL 을 나란히 적기
6. `order_id` 커서 기반 키셋 페이징 메서드를 작성하고, 첫 페이지와 두 번째 페이지의 생성 SQL 차이를 기록하기
7. 정렬 키가 `orderDate` 하나뿐인 페이징 코드에 타이브레이커를 추가하고, **왜 필요한지**를 두 문장으로 설명하기

---

## 다음 단계

지금까지의 정렬은 전부 **컴파일 시점에 정해진** 정렬이었습니다.
그러나 실제 목록 화면은 클라이언트가 `?sort=price,desc` 처럼 정렬 기준을 보냅니다.
`orderBy` 는 `OrderSpecifier` 만 받는데, 문자열을 어떻게 그것으로 바꿀까요.
그리고 그 문자열을 그대로 경로로 쓰면 무슨 일이 일어날까요.

검색 조건도 마찬가지입니다. 키워드·카테고리·가격 범위·상태가 **있을 수도 없을 수도** 있는 조건들을
어떻게 조립해야 `null` 하나에 NPE 로 죽지 않을까요.

→ [Step 10 — 동적 정렬과 검색 조건 조립](../step-10-dynamic-sort/)

---

## 실습 파일

이 스텝은 Java 파일 세 개로 진행합니다. `Practice.java` 를 위에서부터 실행하며
9-1 ~ 9-11 의 모든 생성 SQL 을 콘솔에서 직접 확인한 뒤, `Exercise.java` 의 7문제를 풀고,
`Solution.java` 로 대조합니다. 세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이며
`src/test/java/com/example/shop/step09/` 에 그대로 넣으면 실행됩니다.

### Practice.java

본문의 모든 예제를 `// [9-3]` 형태의 절 번호 주석과 함께 담은 실행 파일입니다.

- `s0_...` 부터 순서대로 배치돼 있으므로 **테스트 메서드를 하나씩 실행**하며 콘솔의 `hibernate.SQL` 로그를 읽으십시오. 전체를 한 번에 돌리면 로그가 뒤섞여 어느 SQL 이 어느 절인지 구분하기 어렵습니다.
- `s6_pageWithSeparateCount` / `s6_pageableExecutionUtils` 는 **같은 데이터에 페이지 크기만 바꿔** 세 번 호출합니다. `PageRequest.of(0, 10)` → count 실행, `PageRequest.of(0, 500)` → count 생략, `PageRequest.of(21, 10)` → count 생략. **로그에 count SQL 이 몇 번 찍히는지 세는 것**이 이 메서드의 목적입니다.
- `s7_sortByFunctionKillsIndex` 는 600건짜리 `orders` 에서는 실행시간 차이가 나지 않습니다. 본문 9-7 의 수치는 100만 행 `access_logs` 에 SQL 을 직접 던진 것이므로, 그 부분은 MySQL 클라이언트에서 재현하십시오. 이 메서드가 확인시켜 주는 것은 **생성 SQL 에 `year(o1_0.order_date)` 가 들어간다는 사실**입니다.
- `s9_missingTiebreaker` 는 같은 쿼리를 **5회 반복 실행**해 결과 순서를 출력합니다. 600건 규모에서는 순서가 안정적으로 나올 가능성이 높으므로, 매번 같은 순서가 나와도 그것이 "보장된다"는 뜻이 아니라는 점을 주석으로 못박아 두었습니다.
- 파일 상단의 `PageableExecutionUtils` import 는 **여러분의 Spring Data 버전에 맞게 IDE 가 다시 잡아 줘야 할 수 있습니다.** 빨간 줄이 뜨면 import 를 지우고 클래스명을 다시 타이핑해 자동완성을 쓰십시오.

```java file="./Practice.java"
```

### Exercise.java

7문제의 문제지입니다. 각 문제는 요구사항 주석과 `// 여기에 작성:` 자리로 되어 있습니다.

- **문제 2·5** 는 코드를 짜는 것보다 **생성 SQL 을 기록하는 것**이 본체입니다. 주석의 "생성 SQL 을 여기에 적으십시오" 칸을 반드시 채우십시오. 답만 맞히고 넘어가면 이 스텝에서 배울 것이 없습니다.
- **문제 4** 는 코드를 새로 짜지 않습니다. 문제 3에서 만든 메서드를 호출하고 **로그를 관찰**하는 문제입니다. `assertThat` 으로 검증할 수 있는 것이 아니라 눈으로 확인해야 하므로, 콘솔 출력에 count SQL 이 없다는 것을 확인한 뒤 주석에 기록하십시오.
- **문제 6** 의 키셋 페이징은 `lastSeenId` 가 `null` 인 첫 호출을 반드시 처리해야 합니다. `where(null)` 이 조건 무시로 동작한다는 Step 04 의 성질을 쓰는 것이 가장 깔끔합니다. `if` 분기로 쿼리를 두 벌 만드는 답도 동작하지만, 왜 그럴 필요가 없는지 `Solution.java` 에서 설명합니다.
- **문제 7** 은 코드가 두 줄이고 설명이 본체입니다. "행이 중복되거나 누락된다"까지만 쓰면 절반입니다. **왜 실행마다 순서가 달라질 수 있는가**를 함께 쓰십시오.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과, 왜 그 답인지를 설명하는 긴 주석이 들어 있습니다. 문제를 풀어 본 **뒤에** 여십시오.

- **정답 2** 는 `nullsLast()` 를 쓰는 것 자체보다, 생성 SQL 이 `order by case when c1_0.phone is null then 1 else 0 end, c1_0.phone asc` 로 나온다는 사실과 **그것이 인덱스를 못 탄다**는 결론이 핵심입니다. MySQL 이 기본적으로 NULL 을 앞에 놓는다는 사실도 함께 짚습니다.
- **정답 3** 은 count 쿼리에서 `join(order.customer, customer)` 를 **뺄 수 있는 경우와 없는 경우**를 나눠 설명합니다. `where` 가 `order.status` 만 본다면 뺄 수 있고, `customer.grade` 를 본다면 남겨야 합니다. 이 판단 기준이 9-6 의 전부입니다.
- **정답 5** 의 교훈은 한 줄입니다: **"정렬 컬럼을 가공하지 말고, 요구사항을 원본 컬럼으로 번역하라."** `year(order_date) desc` 가 필요했던 게 아니라 `order_date desc` 면 충분했다는 것을 보여줍니다. 정말 함수 정렬이 필요한 경우(대소문자 무시)에는 컬레이션이 이미 해결하고 있다는 점도 함께 설명합니다.
- **정답 6** 은 복합 커서 버전까지 함께 제시합니다. `orderDate` 로 정렬하는 키셋에서 `.or()` 로 조립한 조건의 **괄호가 생성 SQL 에서 어떻게 나오는지**, 그리고 `AND` 우선순위 덕분에 우연히 맞는 상황에 기대면 안 되는 이유를 주석으로 길게 다룹니다.
- **정답 7** 은 "정렬 키가 유일하지 않으면 나머지 순서는 DB 재량" 이라는 SQL 표준의 성질에서 출발해, 실행 계획이 바뀌는 세 가지 계기(통계 갱신 / 정렬 버퍼 초과 / 인덱스 변경)를 나열합니다. 그리고 **키셋 페이징에서는 이 문제가 훨씬 치명적**이라는 점으로 9-8 과 연결합니다.

```java file="./Solution.java"
```
