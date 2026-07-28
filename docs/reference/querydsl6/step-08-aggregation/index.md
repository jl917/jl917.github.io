# Step 08 — 집계와 그룹핑

> **학습 목표**
> - `count/sum/avg/max/min` 을 Path 에서 직접 호출하고 **반환 타입**을 정확히 안다
> - 집계 결과를 `Tuple` 로 꺼낼 때 표현식 객체를 재사용하는 관례를 익힌다
> - `groupBy` / `having` 을 쓰고 `where` 와의 실행 시점 차이를 설명한다
> - **`count(컬럼)` 이 NULL 을 세지 않는 것**과 **`sum()` 이 0건에서 `null` 인 것**을 재현한다
> - `GroupBy.transform()` 이 **DB 가 아니라 애플리케이션 메모리에서** 그룹핑한다는 것을 생성 SQL 로 확인한다
> - 조인 fan-out 이 집계를 어떻게 망가뜨리는지 보고 `countDistinct()` 로 고친다
>
> **선행 스텝**: [Step 07 — 서브쿼리](../step-07-subqueries/)
> **예상 소요**: 90분

---

## 8-0. 이 스텝의 위치

> 📌 MySQL8 코스 [Step 06 — 집계함수와 GROUP BY](../../mysql8/step-06-aggregate-groupby/) 와 나란히 진행합니다.
> 그 스텝에서 SQL 로 배운 `COUNT`/`SUM`/`AVG`, `GROUP BY`, `HAVING`, `ONLY_FULL_GROUP_BY` 를
> QueryDSL 로 다시 씁니다. **같은 데이터, 같은 숫자**가 나옵니다.

집계는 **"행 여러 개 → 값 하나"** 로 접는 연산입니다.
Step 03 까지의 조회가 "행 1개 → 값 여러 개" 였다면 정확히 반대 방향입니다.

QueryDSL 에서 집계가 까다로운 지점은 문법이 아닙니다. **타입**입니다.

- `avg()` 는 `Double` 을 돌려줍니다. `BigDecimal` 컬럼이어도 `Double` 입니다.
- `sum()` 은 원래 타입을 유지합니다. `BigDecimal` 컬럼이면 `BigDecimal` 입니다.
- `count()` 는 항상 `Long` 입니다.
- 결과는 대부분 `Tuple` 이고, `Tuple.get()` 은 **표현식 객체를 키로** 씁니다.

그리고 이 스텝에는 이 코스에서 가장 오해가 많은 API 가 하나 있습니다.
**`GroupBy.transform()`** 입니다. 이름과 달리 DB 의 `group by` 를 만들지 않습니다.
8-8 절에서 생성 SQL 로 직접 확인합니다.

이 스텝의 코드는 아래 static import 를 전제로 합니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QReview.review;
import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.group.GroupBy.list;
import static com.querydsl.core.group.GroupBy.set;
import static com.querydsl.core.group.GroupBy.map;
import static com.querydsl.core.group.GroupBy.sum;
import static com.querydsl.core.group.GroupBy.avg;
```

---

## 8-1. 집계 함수 — Path 에서 바로 나옵니다

QueryDSL 의 집계 함수는 별도 유틸이 아니라 **Path 객체의 메서드**입니다.
`order.totalAmount` 가 `NumberPath<BigDecimal>` 이므로 `.sum()` 이 바로 나옵니다.

```java
Tuple stats = queryFactory
        .select(order.count(),
                order.totalAmount.sum(),
                order.totalAmount.avg(),
                order.totalAmount.max(),
                order.totalAmount.min())
        .from(order)
        .fetchOne();
```

**결과**
```sql
select count(o1_0.order_id),
       sum(o1_0.total_amount),
       avg(o1_0.total_amount),
       max(o1_0.total_amount),
       min(o1_0.total_amount)
from orders o1_0
```
```
count = 600
sum   = 764598000.00
avg   = 1274330.0
max   = 6663900.00
min   = 8900.00
```

> 📌 MySQL8 [Step 06 — 6-1 절](../../mysql8/step-06-aggregate-groupby/) 의
> `SELECT COUNT(*), SUM(...), AVG(...) FROM ...` 과 정확히 같은 값입니다.

### 반환 타입 표 — 여기가 함정의 출발점입니다

| QueryDSL | 생성 SQL | 반환 타입 | 비고 |
|---|---|---|---|
| `order.count()` | `count(o1_0.order_id)` | `Long` | 엔티티 count → PK 를 셈 |
| `customer.phone.count()` | `count(c1_0.phone)` | `Long` | **NULL 을 세지 않음** (8-6) |
| `order.totalAmount.sum()` | `sum(o1_0.total_amount)` | **`BigDecimal`** | 원래 타입 유지 |
| `customer.points.sum()` | `sum(c1_0.points)` | `Integer` | 원래 타입 유지 |
| `order.totalAmount.avg()` | `avg(o1_0.total_amount)` | **`Double`** | **항상 Double** |
| `order.totalAmount.max()` | `max(o1_0.total_amount)` | `BigDecimal` | 원래 타입 유지 |
| `order.totalAmount.min()` | `min(o1_0.total_amount)` | `BigDecimal` | 원래 타입 유지 |
| `order.countDistinct()` | `count(distinct o1_0.order_id)` | `Long` | fan-out 대응 (8-9) |

**`avg()` 만 타입이 바뀝니다.** `BigDecimal` 컬럼의 평균이 `Double` 로 나옵니다.
이것이 8-5 절의 `ClassCastException` 의 원인입니다.

### `count()` 는 무엇을 세는가

`order.count()` 는 SQL 로 `count(o1_0.order_id)` 가 됩니다. `count(*)` 가 아닙니다.
`order_id` 는 PK 라 NULL 이 될 수 없으므로 결과는 `count(*)` 와 같습니다.
하지만 **NULL 이 가능한 컬럼을 세면 이야기가 달라집니다.** 8-6 절에서 봅니다.

> 💡 **실무 팁 — 엔티티 count 와 컬럼 count 를 구분해서 읽으십시오**
> ```java
> order.count()               // count(o1_0.order_id)  — 행 수
> order.totalAmount.count()   // count(o1_0.total_amount) — NULL 아닌 금액의 수
> ```
> 두 번째는 `total_amount` 가 NOT NULL 이라 지금은 같은 값이 나옵니다.
> nullable 컬럼이라면 다릅니다. **"몇 건인가" 를 묻는다면 엔티티 count 를 쓰십시오.**

---

## 8-2. 집계 결과는 `Tuple`

집계 표현식을 여러 개 select 하면 결과 타입은 `Tuple` 입니다.
`groupBy` 가 없으면 전체가 한 그룹이므로 **`Tuple` 1건**이 나옵니다.

```java
Tuple t = queryFactory
        .select(customer.count(), customer.points.avg())
        .from(customer)
        .fetchOne();

Long cnt = t.get(customer.count());
Double avg = t.get(customer.points.avg());
```

**결과**
```sql
select count(c1_0.customer_id), avg(c1_0.points)
from customers c1_0
```
```
cnt = 30
avg = 5959.0
```

### `Tuple.get()` 은 표현식 객체를 키로 씁니다

여기가 처음 쓸 때 가장 어색한 부분입니다.
`t.get(0)` 처럼 인덱스로도 꺼낼 수 있지만, 인덱스는 select 순서가 바뀌면 조용히 틀립니다.
**표현식으로 꺼내는 것이 정석**입니다.

```java
t.get(customer.points.avg())    // ⭕ 표현식으로
t.get(1)                        // ⚠️ 인덱스로 — 순서 바뀌면 틀림
```

그런데 `customer.points.avg()` 는 호출할 때마다 **새 객체**를 만듭니다.
그래도 값이 꺼내지는 이유는 `Tuple` 내부가 `equals()` 로 매칭하기 때문입니다.
동작은 합니다. 다만 코드가 이렇게 됩니다.

```java
// 장황하고 오타에 취약합니다
System.out.println(t.get(customer.points.avg()));
System.out.println(t.get(customer.points.sum()));
System.out.println(t.get(customer.points.max()));
```

**표현식을 변수로 뽑아 두는 것이 관례입니다.**

```java
NumberExpression<Double>  avgPoints = customer.points.avg();
NumberExpression<Integer> sumPoints = customer.points.sum();
NumberExpression<Long>    cntAll    = customer.count();

Tuple t = queryFactory
        .select(cntAll, avgPoints, sumPoints)
        .from(customer)
        .fetchOne();

Long   c = t.get(cntAll);
Double a = t.get(avgPoints);
Integer s = t.get(sumPoints);
```

**결과**
```sql
select count(c1_0.customer_id), avg(c1_0.points), sum(c1_0.points)
from customers c1_0
```
```
c = 30
a = 5959.0
s = 178770
```

> 💡 **실무 팁 — 집계가 3개를 넘으면 DTO 프로젝션으로 가십시오**
> `Tuple` 은 편하지만 **타입 정보가 런타임까지 미뤄집니다.**
> 집계가 늘어나면 Step 05 의 `Projections.constructor` 나 `@QueryProjection` 으로 바꾸십시오.
> ```java
> queryFactory
>     .select(Projections.constructor(PointStat.class,
>             customer.count(), customer.points.avg(), customer.points.sum()))
>     .from(customer)
>     .fetchOne();
> ```
> 생성자 시그니처가 컴파일 타임에 검증되므로, `avg()` 가 `Double` 이라는 사실을
> **컴파일러가 대신 기억해 줍니다.** 8-5 의 `ClassCastException` 이 원천 차단됩니다.

---

## 8-3. `groupBy` — 등급별 고객 수와 평균 포인트

> 📌 MySQL8 [Step 06 — 6-4 절](../../mysql8/step-06-aggregate-groupby/) 의 `GROUP BY` 입니다.

SQL 로 먼저 씁니다.

```sql
SELECT grade, COUNT(*) AS cnt, AVG(points) AS avg_points
FROM customers
GROUP BY grade
ORDER BY grade;
```

QueryDSL 로 옮깁니다.

```java
List<Tuple> byGrade = queryFactory
        .select(customer.grade, customer.count(), customer.points.avg())
        .from(customer)
        .groupBy(customer.grade)
        .orderBy(customer.grade.asc())
        .fetch();
```

**결과**
```sql
select c1_0.grade, count(c1_0.customer_id), avg(c1_0.points)
from customers c1_0
group by c1_0.grade
order by c1_0.grade
```
```
+--------+-----+------------+
| grade  | cnt | avg_points |
+--------+-----+------------+
| BRONZE |   9 |   952.2222 |
| SILVER |   8 |  3100.0    |
| GOLD   |   9 |  7600.0    |
| VIP    |   4 | 19250.0    |
+--------+-----+------------+
조회 4행 (합계 30명, 전체 평균 5959.0)
```

`ORDER BY grade` 는 ENUM 의 **선언 순서**로 정렬됩니다(`BRONZE < SILVER < GOLD < VIP`).
알파벳 순이 아닙니다. MySQL 의 ENUM 정렬 규칙이 그대로 적용됩니다.

### 여러 컬럼으로 묶기

```java
List<Tuple> byGradeCity = queryFactory
        .select(customer.grade, customer.city, customer.count())
        .from(customer)
        .groupBy(customer.grade, customer.city)
        .orderBy(customer.grade.asc(), customer.city.asc())
        .fetch();
```

**결과**
```sql
select c1_0.grade, c1_0.city, count(c1_0.customer_id)
from customers c1_0
group by c1_0.grade, c1_0.city
order by c1_0.grade, c1_0.city
```
```
BRONZE 광주 1
BRONZE 대구 1
BRONZE 대전 1
BRONZE 부산 2
BRONZE 서울 1
...
조회 18행
```

### 주문 상태별 매출

```java
List<Tuple> byStatus = queryFactory
        .select(order.status, order.count(), order.totalAmount.sum(), order.totalAmount.avg())
        .from(order)
        .groupBy(order.status)
        .orderBy(order.count().desc())
        .fetch();
```

**결과**
```sql
select o1_0.status, count(o1_0.order_id), sum(o1_0.total_amount), avg(o1_0.total_amount)
from orders o1_0
group by o1_0.status
order by count(o1_0.order_id) desc
```
```
+-----------+-----+---------------+------------+
| status    | cnt | sum           | avg        |
+-----------+-----+---------------+------------+
| DELIVERED | 240 | 326280000.00  | 1359500.0  |
| SHIPPED   | 120 | 162834000.00  | 1356950.0  |
| PAID      | 120 | 132630000.00  | 1105250.0  |
| CANCELLED |  60 |  47742000.00  |  795700.0  |
| PENDING   |  60 |  95112000.00  | 1585200.0  |
+-----------+-----+---------------+------------+
조회 5행 (합계 600건 / 764,598,000)
```

`orderBy(order.count().desc())` 처럼 **집계 표현식으로 정렬**할 수 있습니다.
SQL 에서도 `order by count(...) desc` 로 나갑니다.

---

## 8-4. `having` — 그룹을 거른다

`where` 와 `having` 은 **거르는 시점**이 다릅니다.

```
FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY
        ↑                    ↑
   개별 행을 거름        그룹을 거름
   (집계 전)             (집계 후, 집계 함수 사용 가능)
```

"주문 5건 이상인 고객" 을 찾습니다.

```java
List<Tuple> heavy = queryFactory
        .select(order.customer.id, order.count())
        .from(order)
        .groupBy(order.customer.id)
        .having(order.count().goe(5))
        .orderBy(order.customer.id.asc())
        .fetch();
```

**결과**
```sql
select o1_0.customer_id, count(o1_0.order_id)
from orders o1_0
group by o1_0.customer_id
having count(o1_0.order_id) >= ?
order by o1_0.customer_id
```
```
바인딩: [1] 5
조회 30행 — 전원 20건씩이므로 아무도 걸러지지 않음
```

시드 데이터가 규칙적이라 30명 모두 통과합니다. 판별력 있는 조건으로 바꿉니다.

### 취소 제외 주문 합계가 5천만 원 이상인 고객

```java
List<Tuple> bigSpenders = queryFactory
        .select(order.customer.id, order.count(), order.totalAmount.sum())
        .from(order)
        .where(order.status.ne(OrderStatus.CANCELLED))          // ① 개별 행 필터 (집계 전)
        .groupBy(order.customer.id)
        .having(order.totalAmount.sum().goe(new BigDecimal("50000000")))  // ② 그룹 필터 (집계 후)
        .orderBy(order.totalAmount.sum().desc())
        .fetch();
```

**결과**
```sql
select o1_0.customer_id, count(o1_0.order_id), sum(o1_0.total_amount)
from orders o1_0
where o1_0.status <> ?
group by o1_0.customer_id
having sum(o1_0.total_amount) >= ?
order by sum(o1_0.total_amount) desc
```
```
바인딩: [1] CANCELLED, [2] 50000000
조회 3행
   8  임수진   20   58449000.00
   5  정  훈   20   51568000.00
  21  황도윤   20   50248500.00
```

> 📌 MySQL8 [Step 08 — 8-3 절](../../mysql8/step-08-subqueries/) 의 파생 테이블 예제와 같은 인물입니다.
> 거기서는 인라인 뷰로 풀었지만, JPQL 에서는 인라인 뷰를 못 쓰므로 `having` 으로 풉니다 (Step 07 의 7-7).

> ⚠️ **함정 — 집계와 무관한 조건을 `having` 에 쓰는 것**
> ```java
> // ❌ 동작은 하지만 비효율
> .groupBy(order.customer.id)
> .having(order.status.ne(OrderStatus.CANCELLED))
> ```
> 이렇게 쓰면 **CANCELLED 주문까지 전부 그룹핑한 뒤** 버립니다.
> `where` 로 옮기면 그룹핑 대상 자체가 줄고 인덱스도 탈 수 있습니다.
> QueryDSL 은 `having(...)` 에 아무 `BooleanExpression` 이나 받아 주므로 **막아 주지 않습니다.**
> - `having` 에 써야 할 것: `order.count().goe(5)`, `order.totalAmount.sum().goe(...)` — 집계 결과 조건
> - `where` 로 옮겨야 할 것: `order.status.ne(...)`, `order.orderDate.after(...)` — 개별 행 조건

> 💡 **`having` 에는 별칭을 못 씁니다**
> MySQL 은 `HAVING 고객수 >= 3` 처럼 select 별칭을 허용하는 확장을 갖고 있지만,
> QueryDSL 에서는 애초에 별칭이 아니라 **표현식 객체**를 넘기므로 이 문제가 없습니다.
> `having(order.count().goe(5))` 에서 `order.count()` 는 select 절의 것과 같은 표현식이고,
> QueryDSL 이 같은 SQL 조각을 양쪽에 만들어 줍니다.

---

## 8-5. ⚠️ 집계 결과의 `Tuple` 다루기 — `ClassCastException`

이 절이 이 스텝의 첫 번째 함정입니다.

### 증상

`totalAmount` 는 `BigDecimal` 입니다. 그러니 평균도 `BigDecimal` 이겠거니 하고 이렇게 씁니다.

```java
// ⚠️ 컴파일이 됩니다
Tuple t = queryFactory
        .select(order.totalAmount.sum(), order.totalAmount.avg())
        .from(order)
        .fetchOne();

BigDecimal sum = t.get(order.totalAmount.sum());   // ⭕ BigDecimal 맞음
BigDecimal avg = (BigDecimal) t.get(0);            // ⚠️ 인덱스 접근 + 캐스팅
```

**결과**
```
java.lang.ClassCastException: class java.lang.Double cannot be cast to class java.math.BigDecimal
	at com.example.shop.step08.Practice.avgTypeTrap(Practice.java:88)
```

`avg()` 는 SQL 의 `avg()` 를 그대로 부르고, JDBC 는 그 결과를 `Double` 로 돌려줍니다.
QueryDSL 의 `avg()` 시그니처도 `NumberExpression<Double>` 입니다.
**컬럼이 `BigDecimal` 이든 `Integer` 든 평균은 항상 `Double` 입니다.**

### 왜 컴파일러가 못 잡았나

표현식으로 꺼내면 잡습니다.

```java
BigDecimal avg = t.get(order.totalAmount.avg());
//         ^^^^ 컴파일 에러: incompatible types: Double cannot be converted to BigDecimal
```

**컴파일 에러가 나는 것이 정상이고 좋은 일입니다.** 타입이 맞지 않는다고 알려 준 것이니까요.
문제는 위 예제처럼 **인덱스 접근 + 수동 캐스팅**을 하는 순간 컴파일러가 손을 뗀다는 점입니다.
`Tuple.get(int)` 의 반환 타입은 `Object` 입니다.

> ⚠️ **함정 — `Tuple.get(int)` 는 타입 안전성을 통째로 버립니다**
> QueryDSL 을 쓰는 이유가 타입 안전인데, `t.get(0)` 한 줄로 그것이 무효화됩니다.
> 게다가 select 절 순서가 바뀌면 **캐스팅이 성공하면서 값만 틀리는** 최악의 경우도 생깁니다.
> 예: `select(sum, avg)` 를 `select(avg, sum)` 으로 바꿨는데 둘 다 숫자라 조용히 통과.
> **`Tuple.get(int)` 는 쓰지 마십시오.**

### 두 번째 증상 — 장황함

표현식으로 꺼내는 것이 정답이지만, 매번 새로 만들면 이렇게 됩니다.

```java
// 동작은 하지만 읽기 나쁩니다
Long   cnt = t.get(order.count());
BigDecimal sum = t.get(order.totalAmount.sum());
Double avg = t.get(order.totalAmount.avg());
BigDecimal max = t.get(order.totalAmount.max());
BigDecimal min = t.get(order.totalAmount.min());
```

`order.totalAmount.sum()` 을 select 절과 get 에서 각각 한 번씩, 총 두 번 씁니다.
한쪽만 `.sum()` → `.avg()` 로 잘못 고치면 **`null` 이 조용히 나옵니다.** (매칭 실패)

### 처방 1 — 표현식을 상수로 뽑는다

```java
private static final NumberExpression<Long>       ORDER_CNT = order.count();
private static final NumberExpression<BigDecimal> AMT_SUM   = order.totalAmount.sum();
private static final NumberExpression<Double>     AMT_AVG   = order.totalAmount.avg();

Tuple t = queryFactory
        .select(ORDER_CNT, AMT_SUM, AMT_AVG)
        .from(order)
        .fetchOne();

Long       cnt = t.get(ORDER_CNT);
BigDecimal sum = t.get(AMT_SUM);
Double     avg = t.get(AMT_AVG);      // 타입이 코드에 박혀 있으므로 헷갈릴 여지가 없음
```

표현식 객체는 불변이므로 `static final` 로 둬도 안전합니다.

### 처방 2 — DTO 프로젝션 (권장)

```java
public record OrderStat(Long count, BigDecimal sum, Double avg) {}

OrderStat stat = queryFactory
        .select(Projections.constructor(OrderStat.class,
                order.count(),
                order.totalAmount.sum(),
                order.totalAmount.avg()))
        .from(order)
        .fetchOne();
```

**결과**
```sql
select count(o1_0.order_id), sum(o1_0.total_amount), avg(o1_0.total_amount)
from orders o1_0
```
```
OrderStat[count=600, sum=764598000.00, avg=1274330.0]
```

record 의 생성자 시그니처가 **컴파일 타임에 검증**됩니다.
`avg` 를 `BigDecimal` 로 선언하면 그 자리에서 컴파일이 깨집니다.

> ⚠️ **`Projections.constructor` 는 타입이 맞아야 합니다**
> `record OrderStat(Long count, BigDecimal sum, BigDecimal avg)` 로 잘못 선언하면
> `com.querydsl.core.types.ExpressionException` 이 **런타임에** 납니다.
> 생성자 인자 타입과 표현식 타입을 대조하기 때문입니다.
> `@QueryProjection` 을 쓰면 이것도 컴파일 타임으로 당겨집니다 (Step 05).

---

## 8-6. 집계와 NULL — `count(컬럼)` 은 NULL 을 세지 않습니다

> 📌 MySQL8 [Step 06 — 6-2, 6-3 절](../../mysql8/step-06-aggregate-groupby/) 및
> [부록 A — NULL 완전 정복](../../mysql8/appendix-a-null/) 과 같은 주제입니다.

`customers` 30명 중 **전화번호가 NULL 인 고객이 3명**입니다.

```java
Tuple t = queryFactory
        .select(customer.count(),           // 엔티티 count → PK
                customer.phone.count(),     // 컬럼 count  → NULL 제외
                customer.city.countDistinct())
        .from(customer)
        .fetchOne();
```

**결과**
```sql
select count(c1_0.customer_id), count(c1_0.phone), count(distinct c1_0.city)
from customers c1_0
```
```
customer.count()             = 30
customer.phone.count()       = 27   ← NULL 3명이 빠졌다
customer.city.countDistinct() = 8
```

**30 과 27.** 컴파일도 되고 예외도 없습니다. 리포트 숫자만 틀립니다.

| 형태 | 생성 SQL | 세는 것 |
|---|---|---|
| `customer.count()` | `count(c1_0.customer_id)` | 행 수 (PK 는 NOT NULL) |
| `customer.phone.count()` | `count(c1_0.phone)` | **NULL 이 아닌 phone 의 수** |
| `customer.city.countDistinct()` | `count(distinct c1_0.city)` | 서로 다른 city 의 수 (NULL 제외) |

> ⚠️ **함정 — "고객 수" 를 세면서 nullable 컬럼을 세는 것**
> ```java
> .select(customer.phone.count())     // ❌ "전화번호가 있는 고객 수"
> .select(customer.count())           // ⭕ "고객 수"
> ```
> 두 줄은 비슷하게 생겼고 둘 다 `Long` 을 돌려줍니다. 값만 다릅니다.
> **"몇 명인가" 를 묻는다면 엔티티 count 를 쓰십시오.**
> 그리고 nullable 컬럼을 셀 때는 그 의미가 "NULL 아닌 것의 수" 라는 것을 항상 의식하십시오.

### `avg()` 도 NULL 을 무시합니다

`count(*)` 를 제외한 **모든 집계 함수가 NULL 을 건너뜁니다.** `avg()` 에서 이게 특히 미묘합니다.

`AVG(col)` 은 정확히 `SUM(col) / COUNT(col)` 입니다.
**분모가 전체 행 수가 아니라 "NULL 이 아닌 행 수"** 입니다.

```java
Tuple t = queryFactory
        .select(customer.count(),
                customer.points.avg(),                      // 30으로 나눔 (points 는 NOT NULL)
                customer.points.sum())
        .from(customer)
        .fetchOne();
```

**결과**
```sql
select count(c1_0.customer_id), avg(c1_0.points), sum(c1_0.points)
from customers c1_0
```
```
count = 30
avg   = 5959.0
sum   = 178770
```
`178770 / 30 = 5959.0` — `points` 가 NOT NULL 이므로 일치합니다.

만약 `points` 가 nullable 이고 5명이 NULL 이라면 분모는 25가 되어 평균이 올라갑니다.
**"평균" 을 리포트에 낼 때는 분모가 무엇인지 반드시 확인하십시오.**

### 그룹핑 컬럼이 NULL 이면

`groupBy` 대상에 NULL 이 있으면 **NULL 도 하나의 그룹**이 됩니다.

```java
List<Tuple> byPhone = queryFactory
        .select(customer.phone.isNull(), customer.count())
        .from(customer)
        .groupBy(customer.phone.isNull())
        .fetch();
```

**결과**
```sql
select c1_0.phone is null, count(c1_0.customer_id)
from customers c1_0
group by c1_0.phone is null
```
```
false 27
true   3
```

`employees.manager_id` 처럼 실제로 NULL 을 가진 컬럼으로 묶으면 NULL 그룹이 직접 보입니다.

```java
List<Tuple> byManager = queryFactory
        .select(employee.manager.id, employee.count())
        .from(employee)
        .groupBy(employee.manager.id)
        .orderBy(employee.manager.id.asc().nullsFirst())
        .fetch();
```

**결과**
```
null  1     ← CEO. 이 NULL 그룹이 Step 07 의 NOT IN 함정의 원인이었습니다
   1  3
   2  2
   3  3
   ...
조회 9행 (NULL 그룹 1 + 관리자 8명)
```

---

## 8-7. `sum()` 이 0건일 때 `null` 을 돌려줍니다

`0` 이 아닙니다. **`null` 입니다.**

```java
BigDecimal total = queryFactory
        .select(order.totalAmount.sum())
        .from(order)
        .where(order.shippingCity.eq("제주"))       // 해당 행이 0건
        .fetchOne();

System.out.println(total);
```

**결과**
```sql
select sum(o1_0.total_amount)
from orders o1_0
where o1_0.shipping_city = ?
```
```
바인딩: [1] 제주
null
```

SQL 표준이 그렇습니다. "더할 것이 없으면 합계는 정의되지 않는다" 로 봅니다.
`count()` 만 0건에서 `0` 을 돌려주고, `sum` / `avg` / `max` / `min` 은 전부 `null` 입니다.

| 함수 | 대상 0건일 때 |
|---|---|
| `count()` | `0` |
| `sum()` | **`null`** |
| `avg()` | **`null`** |
| `max()` / `min()` | **`null`** |

### NPE 로 이어지는 경로

```java
// ⚠️ 운영에서 터지는 전형
BigDecimal total = queryFactory
        .select(order.totalAmount.sum())
        .from(order)
        .where(order.customer.eq(someCustomer),
               order.orderDate.between(from, to))
        .fetchOne();

BigDecimal fee = total.multiply(new BigDecimal("0.03"));   // 💥 NullPointerException
```

**해당 기간에 주문이 하나도 없는 고객이 등장하는 순간 터집니다.**
개발 데이터에는 항상 주문이 있으니 통과하고, 신규 가입자가 배치에 들어오는 날 터집니다.

### 처방 1 — `coalesce`

```java
BigDecimal total = queryFactory
        .select(order.totalAmount.sum().coalesce(BigDecimal.ZERO))
        .from(order)
        .where(order.shippingCity.eq("제주"))
        .fetchOne();
```

**결과**
```sql
select coalesce(sum(o1_0.total_amount), ?)
from orders o1_0
where o1_0.shipping_city = ?
```
```
바인딩: [1] 0, [2] 제주
0
```

`coalesce` 는 SQL 의 `COALESCE` 로 번역되어 **DB 에서** NULL 을 대체합니다.
`Expressions` 와 조합한 더 복잡한 형태는 [Step 13 — 고급 표현식](../step-13-advanced/) 에서 다룹니다.

### 처방 2 — 자바 쪽에서 방어

```java
BigDecimal total = Optional.ofNullable(
        queryFactory.select(order.totalAmount.sum()).from(order)
                .where(order.shippingCity.eq("제주"))
                .fetchOne()
).orElse(BigDecimal.ZERO);
```

둘 다 유효합니다. **`coalesce` 쪽이 SQL 에 의도가 드러나서 더 낫습니다.**

> ⚠️ **함정 — `groupBy` 가 있으면 이 문제가 숨습니다**
> ```java
> .select(order.customer.id, order.totalAmount.sum())
> .from(order).groupBy(order.customer.id)
> ```
> 이 쿼리는 **주문이 0건인 고객의 행 자체를 만들지 않습니다.** 그룹이 없으니까요.
> 그래서 `sum` 이 `null` 인 행을 볼 일이 없고, "이 문제는 나와 상관없다" 고 착각하게 됩니다.
> 그러다 `leftJoin` 을 붙여 "주문 0건 고객도 포함" 시키는 순간 `null` 이 등장합니다.
> ```java
> .select(customer.id, order.totalAmount.sum().coalesce(BigDecimal.ZERO))
> .from(customer).leftJoin(order).on(order.customer.eq(customer))
> .groupBy(customer.id)
> ```
> **`leftJoin` + 집계 조합에는 `coalesce` 를 습관적으로 붙이십시오.**

---

## 8-8. `GroupBy.transform()` — 강력하지만 DB 의 group by 가 아닙니다

이 절이 이 스텝의 하이라이트입니다.

### 문제 상황

"고객별 주문 목록" 을 `Map<Long, List<OrderDto>>` 로 받고 싶습니다.
평범하게 쓰면 조인 결과를 받아 자바에서 직접 묶어야 합니다.

```java
List<Tuple> rows = queryFactory
        .select(customer.id, order.id, order.totalAmount)
        .from(order)
        .innerJoin(order.customer, customer)
        .fetch();

Map<Long, List<OrderDto>> map = rows.stream()
        .collect(Collectors.groupingBy(
                t -> t.get(customer.id),
                Collectors.mapping(t -> new OrderDto(t.get(order.id), t.get(order.totalAmount)),
                                   Collectors.toList())));
```

QueryDSL 은 이 조립 과정을 API 로 제공합니다.

### `transform` 사용법

```java
import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.group.GroupBy.list;

Map<Long, List<OrderDto>> byCustomer = queryFactory
        .from(order)                                    // ← select() 가 없다!
        .innerJoin(order.customer, customer)
        .transform(groupBy(customer.id).as(
                list(Projections.constructor(OrderDto.class, order.id, order.totalAmount))
        ));
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, o1_0.order_id, o1_0.total_amount
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
```
```
Map 크기 = 30
byCustomer.get(1L).size() = 20
byCustomer.get(1L).get(0) = OrderDto[orderId=1, totalAmount=1116000.00]
```

### 생성 SQL 에 `group by` 가 없습니다

위 SQL 을 다시 보십시오.

```sql
select c1_0.customer_id, o1_0.order_id, o1_0.total_amount
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
```

**`group by` 가 한 글자도 없습니다.**

`transform` 은 **조인 결과 600행을 전부 애플리케이션으로 가져온 다음, 자바 메모리에서 묶습니다.**
DB 의 `GROUP BY` 와는 아무 관계가 없습니다.

```
                 QueryDSL transform 의 실제 동작
   ┌─────────────────────────────────────────────────────┐
   │  DB                                                 │
   │    select ... from orders join customers            │  ← group by 없음
   │    → 600행 전부 반환                                 │
   └──────────────────────┬──────────────────────────────┘
                          │  600행이 네트워크를 타고 넘어옴
   ┌──────────────────────▼──────────────────────────────┐
   │  애플리케이션 (JVM 힙)                               │
   │    600행을 읽으며 Map<Long, List<OrderDto>> 조립     │  ← 여기서 그룹핑
   │    → Map 크기 30                                     │
   └─────────────────────────────────────────────────────┘
```

> ⚠️ **함정 — `GroupBy.groupBy` 라는 이름이 `groupBy()` 절과 완전히 다릅니다**
> ```java
> queryFactory.select(...).from(order).groupBy(order.customer.id)   // ⭕ SQL 의 GROUP BY
> queryFactory.from(order).transform(GroupBy.groupBy(customer.id))  // ⚠️ 메모리 그룹핑
> ```
> 이름이 같아서 "DB 에서 그룹핑되겠지" 라고 오해하기 딱 좋습니다.
> **`transform` 은 결과 집합 변환기(ResultTransformer)입니다.** SQL 을 바꾸지 않습니다.
> 로그에 `group by` 가 없는 것을 반드시 눈으로 확인하십시오.

### 언제 써도 되고 언제 안 되는가

| 상황 | 판단 |
|---|---|
| 조인 결과가 수백~수천 행이고 실제로 전부 필요함 | **써도 됨** — 자바 조립 코드를 줄여 줌 |
| 상세 화면: 주문 1건 + 주문상세 5건 | **써도 됨** |
| 페이징된 목록(20건)의 연관 데이터 조립 | **써도 됨** |
| **전체 주문 60만 건을 고객별로 묶기** | **쓰면 안 됨** — 60만 행이 전부 힙으로 |
| 그룹별 **합계만** 필요 (원본 행은 불필요) | **쓰면 안 됨** — DB 의 `groupBy` 로 5행만 받으면 됨 |
| 그룹 수만 세면 됨 | **쓰면 안 됨** — `countDistinct()` 로 1행 |

**판단 기준은 하나입니다: "원본 행이 실제로 필요한가?"**
필요 없으면 DB 에서 접어서 가져오십시오. 필요하면 `transform` 이 편합니다.

### 같은 질문을 DB 그룹핑으로

"고객별 주문 **건수**" 만 필요하다면 600행을 가져올 이유가 없습니다.

```java
List<Tuple> counts = queryFactory
        .select(customer.id, order.count())
        .from(order)
        .innerJoin(order.customer, customer)
        .groupBy(customer.id)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, count(o1_0.order_id)
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
group by c1_0.customer_id
```
```
조회 30행 — 네트워크로 30행만 넘어옴 (transform 은 600행)
```

**600행 → 30행. 20배 차이입니다.** 주문이 60만 건이면 60만 행 → 30행, 2만 배입니다.

### `transform` 은 `select()` 없이 `from()` 으로 시작합니다

```java
queryFactory.from(order)...transform(...)     // ⭕
queryFactory.select(...).from(order)...       // ❌ transform 이 없음
```

`transform` 이 무엇을 뽑을지는 `groupBy(...).as(...)` 안에서 결정되므로,
바깥에 `select()` 를 두면 의미가 중복됩니다. QueryDSL 은 `JPAQueryFactory.from(...)` 이
돌려주는 `JPAQuery` 에만 `transform` 을 둡니다.

### `GroupBy` 표현식 목록

| 메서드 | 결과 타입 | 설명 |
|---|---|---|
| `GroupBy.list(expr)` | `List<T>` | 그룹의 값들을 리스트로. **중복 유지** |
| `GroupBy.set(expr)` | `Set<T>` | 중복 제거 |
| `GroupBy.map(k, v)` | `Map<K, V>` | 그룹 안에서 다시 맵으로 |
| `GroupBy.sum(expr)` | 숫자 | **메모리에서** 합산 |
| `GroupBy.avg(expr)` | `Double` | **메모리에서** 평균 |
| `GroupBy.min(expr)` / `max(expr)` | 값 | **메모리에서** 최소/최대 |
| `GroupBy.count()` | `Long` | **메모리에서** 개수 |

```java
// 고객별 주문 건수와 합계 — 메모리 집계 버전
Map<Long, Tuple> stats = queryFactory
        .from(order)
        .innerJoin(order.customer, customer)
        .transform(groupBy(customer.id).as(
                GroupBy.count(),
                GroupBy.sum(order.totalAmount)
        ));
```

**결과**
```sql
select c1_0.customer_id, o1_0.order_id, o1_0.total_amount
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
```
```
Map 크기 = 30
stats.get(1L) → [20, 25478000.00]
```

> ⚠️ **`GroupBy.sum()` 과 `order.totalAmount.sum()` 은 완전히 다릅니다**
> | | 실행 위치 | 전송 행 수 |
> |---|---|---|
> | `order.totalAmount.sum()` + `groupBy()` | **DB** | 그룹 수만큼 (30) |
> | `GroupBy.sum(order.totalAmount)` + `transform()` | **JVM** | 원본 행 수만큼 (600) |
>
> **집계만 필요하면 DB 쪽을 쓰십시오.** `GroupBy.sum` 은 이미 `transform` 으로 원본을 다 가져오는
> 상황에서 "이왕 가져온 김에 합계도" 일 때만 의미가 있습니다.

> 💡 **실무 팁 — `transform` 은 fan-out 을 자동으로 정리해 줍니다**
> `Order` → `OrderItem` 처럼 1:N 조인을 하면 주문이 상세 개수만큼 중복됩니다(Step 06).
> `transform(groupBy(order.id).as(list(...)))` 를 쓰면 그 중복이 자연스럽게 정리됩니다.
> `distinct` 나 `Set` 을 수동으로 다룰 필요가 없어집니다.
> **이것이 `transform` 의 진짜 장점입니다.** DB 부하를 줄이는 도구가 아니라
> **조립 코드를 없애는 도구**로 이해하십시오.

---

## 8-9. fan-out 과 집계 — 조인이 만드는 잘못된 숫자

> 📌 [Step 06 — 조인](../step-06-joins/) 의 행 뻥튀기가 집계에서 어떻게 드러나는지 봅니다.

"고객별 주문 건수" 를 구하는데 `orderItems` 까지 조인해 버렸습니다.

```java
// ⚠️ 잘못된 코드
List<Tuple> wrong = queryFactory
        .select(customer.id, order.count())
        .from(customer)
        .innerJoin(order).on(order.customer.eq(customer))
        .innerJoin(orderItem).on(orderItem.order.eq(order))     // ← 여기서 뻥튀기
        .groupBy(customer.id)
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, count(o1_0.order_id)
from customers c1_0
join orders o1_0 on o1_0.customer_id = c1_0.customer_id
join order_items oi1_0 on oi1_0.order_id = o1_0.order_id
group by c1_0.customer_id
order by c1_0.customer_id
```
```
   1  40      ← 20이어야 하는데 40
   2  40
   3  40
   ...
조회 30행 (합계 1200 = order_items 전체 행 수)
```

**40 은 주문 건수가 아니라 주문상세 건수입니다.**
주문 600건이 주문상세 1,200건과 조인되어 1,200행이 되었고,
`count(o1_0.order_id)` 는 그 1,200행을 센 것입니다.

에러는 없습니다. 숫자가 그럴듯하게 나옵니다. **리포트가 두 배로 부풀어 있습니다.**

### 처방 1 — `countDistinct()`

```java
List<Tuple> fixed = queryFactory
        .select(customer.id, order.countDistinct())
        .from(customer)
        .innerJoin(order).on(order.customer.eq(customer))
        .innerJoin(orderItem).on(orderItem.order.eq(order))
        .groupBy(customer.id)
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, count(distinct o1_0.order_id)
from customers c1_0
join orders o1_0 on o1_0.customer_id = c1_0.customer_id
join order_items oi1_0 on oi1_0.order_id = o1_0.order_id
group by c1_0.customer_id
order by c1_0.customer_id
```
```
   1  20      ← 정답
   2  20
   ...
```

### 처방 2 — 필요 없는 조인을 지운다 (더 나음)

애초에 `orderItem` 이 필요 없었습니다.

```java
List<Tuple> best = queryFactory
        .select(customer.id, order.count())
        .from(customer)
        .innerJoin(order).on(order.customer.eq(customer))
        .groupBy(customer.id)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, count(o1_0.order_id)
from customers c1_0
join orders o1_0 on o1_0.customer_id = c1_0.customer_id
group by c1_0.customer_id
```
```
   1  20      ← 정답, 그리고 조인 하나가 사라짐
```

### `sum()` 에서는 `distinct` 로도 못 고칩니다

`count` 는 `countDistinct` 로 고쳐지지만 **`sum` 은 그렇지 않습니다.**

```java
// ⚠️ 총액이 부풀어 오른다
List<Tuple> wrongSum = queryFactory
        .select(customer.id, order.totalAmount.sum())
        .from(customer)
        .innerJoin(order).on(order.customer.eq(customer))
        .innerJoin(orderItem).on(orderItem.order.eq(order))
        .groupBy(customer.id)
        .fetch();
```

**결과**
```
   1  50956000.00      ← 실제 25478000.00 의 2배
```

같은 주문의 `total_amount` 가 상세 개수만큼 반복 더해집니다.
`sumDistinct` 같은 것을 써도 **금액이 같은 서로 다른 주문**이 하나로 합쳐져 더 틀립니다.

> ⚠️ **함정 — `sum` 의 fan-out 은 `distinct` 로 고칠 수 없습니다**
> 이 경우 선택지는 두 가지뿐입니다.
> 1. **불필요한 조인을 제거**한다 (대부분 이게 답입니다)
> 2. 서브쿼리로 분리한다 — 합계는 `orders` 만으로 구하고, 상세 조건은 `exists` 로 (Step 07)
> ```java
> .select(customer.id, order.totalAmount.sum())
> .from(customer)
> .innerJoin(order).on(order.customer.eq(customer))
> .where(selectOne().from(orderItem).where(orderItem.order.eq(order)).exists())
> .groupBy(customer.id)
> ```
> **집계 쿼리를 쓸 때는 "조인 후 행 수가 몇인가" 를 먼저 계산하십시오.**
> 그 숫자를 모르면 집계 결과도 믿을 수 없습니다.

### 조인 후 행 수를 확인하는 습관

```java
Long joinedRows = queryFactory
        .select(order.count())
        .from(customer)
        .innerJoin(order).on(order.customer.eq(customer))
        .innerJoin(orderItem).on(orderItem.order.eq(order))
        .fetchOne();
```

**결과**
```
1200      ← "고객 30 × 주문 20 = 600" 을 기대했다면 여기서 이상을 알아챌 수 있습니다
```

---

## 8-10. `ONLY_FULL_GROUP_BY` — JPQL 이 먼저 막아 줍니다

> 📌 MySQL8 [Step 06 — 6-6 절](../../mysql8/step-06-aggregate-groupby/) 의 주제입니다.

MySQL 8 의 기본 `sql_mode` 에는 `ONLY_FULL_GROUP_BY` 가 켜져 있습니다.
`GROUP BY` 에 없고 집계 함수로도 안 감싼 컬럼을 select 하면 에러를 냅니다.

```sql
SELECT city, name, COUNT(*) FROM customers GROUP BY city;
-- ERROR 1055 (42000): Expression #2 of SELECT list is not in GROUP BY clause ...
```

QueryDSL 에서 같은 것을 시도하면 어떻게 될까요?

```java
List<Tuple> rows = queryFactory
        .select(customer.city, customer.name, customer.count())
        .from(customer)
        .groupBy(customer.city)
        .fetch();
```

**결과**
```sql
select c1_0.city, c1_0.name, count(c1_0.customer_id)
from customers c1_0
group by c1_0.city
```
```
org.hibernate.exception.SQLGrammarException: JDBC exception executing SQL query
  Caused by: java.sql.SQLSyntaxErrorException:
    Expression #2 of SELECT list is not in GROUP BY clause and contains
    nonaggregated column 'shop.c1_0.name' which is not functionally dependent
    on columns in GROUP BY clause;
    this is incompatible with sql_mode=only_full_group_by
```

**QueryDSL 은 막지 않고 그대로 SQL 을 만들어 보냅니다.** MySQL 이 거부합니다.

### 왜 이 문제가 JPA 에서는 덜 나타나는가

JPQL 은 원래 `group by` 에 없는 컬럼의 select 를 명세상 허용하지 않습니다.
그리고 실무에서 JPA 를 쓰면 이런 코드를 자연스럽게 안 쓰게 됩니다.
`groupBy` 결과를 `Tuple` 이나 DTO 로 받게 되므로, 필요한 컬럼을
**`groupBy` 목록에도 넣는 것이 자연스럽기** 때문입니다.

```java
// ⭕ 정상 — 필요한 컬럼을 groupBy 에 함께 넣는다
.select(customer.city, customer.count())
.groupBy(customer.city)

// ⭕ 정상 — 여러 컬럼을 함께 묶는다
.select(customer.id, customer.name, order.count())
.groupBy(customer.id, customer.name)
```

### 그래도 마주치는 경우

1. **엔티티를 통째로 select 하면서 groupBy** — 가장 흔합니다.
```java
// ⚠️ 위험
.select(customer, order.count())
.from(customer).innerJoin(order).on(order.customer.eq(customer))
.groupBy(customer.id)
```
`select(customer)` 는 `customers` 의 **모든 컬럼**을 select 합니다.
`group by c1_0.customer_id` 뿐인데 `name`, `city`, `points` 가 select 절에 있습니다.

MySQL 8 은 이 경우를 **함수 종속성(functional dependency)** 으로 인식해 통과시킵니다.
`customer_id` 가 PK 이므로 나머지 컬럼이 결정되기 때문입니다.
그래서 **잘 동작합니다.** 그런데 이게 함정입니다.

> ⚠️ **함정 — PK 로 묶었을 때만 통과합니다**
> ```java
> .select(customer, order.count())
> .groupBy(customer.city)      // ← PK 가 아닌 컬럼으로 묶으면
> ```
> 이건 즉시 `ERROR 1055` 입니다. 함수 종속성이 성립하지 않으니까요.
> **`groupBy(customer.id)` 는 되고 `groupBy(customer.city)` 는 안 되는데,**
> 코드만 봐서는 왜 하나는 되고 하나는 안 되는지 알 수 없습니다.
> 원인은 QueryDSL 이 아니라 **MySQL 의 함수 종속성 판정**입니다.
> 그리고 이 판정은 DB 마다 다릅니다. PostgreSQL 은 더 엄격합니다. **이식성 문제입니다.**

2. **집계 결과에 대표값을 하나 붙이고 싶을 때**
```java
// "도시별 고객 수와 아무 고객 이름 하나"
.select(customer.city, customer.count(), customer.name.max())   // ⭕ max 로 감싸면 통과
.groupBy(customer.city)
```
MySQL 의 `ANY_VALUE()` 에 대응하는 QueryDSL API 는 없습니다.
`max()` / `min()` 으로 감싸는 것이 표준적이고 이식성도 좋은 우회입니다.

> 💡 **실무 팁 — `groupBy` 에 넣을 컬럼을 select 에서 역산하십시오**
> "select 절의 비집계 컬럼 = groupBy 목록" 이 되도록 맞추면 어느 DB 에서든 안전합니다.
> 엔티티를 통째로 select 하는 집계 쿼리는 가급적 피하고, **필요한 컬럼만 나열**하십시오.
> 그러면 이 문제가 아예 발생하지 않습니다.

---

## 8-11. `case` 로 조건부 집계 — 피벗

"등급별 매출을 **한 행으로**" 만들고 싶습니다.
`groupBy` 로 하면 4행이 나오는데, 리포트에서는 한 행에 4개 컬럼으로 놓고 싶을 때가 있습니다.

SQL 의 관용구는 `SUM(CASE WHEN ... THEN ... ELSE 0 END)` 입니다.
QueryDSL 에서는 `CaseBuilder` 를 씁니다.

```java
import com.querydsl.core.types.dsl.CaseBuilder;

NumberExpression<Integer> vipPoints = new CaseBuilder()
        .when(customer.grade.eq(Grade.VIP)).then(customer.points)
        .otherwise(0);

NumberExpression<Integer> goldPoints = new CaseBuilder()
        .when(customer.grade.eq(Grade.GOLD)).then(customer.points)
        .otherwise(0);

NumberExpression<Integer> silverPoints = new CaseBuilder()
        .when(customer.grade.eq(Grade.SILVER)).then(customer.points)
        .otherwise(0);

NumberExpression<Integer> bronzePoints = new CaseBuilder()
        .when(customer.grade.eq(Grade.BRONZE)).then(customer.points)
        .otherwise(0);

Tuple pivot = queryFactory
        .select(vipPoints.sum(), goldPoints.sum(), silverPoints.sum(), bronzePoints.sum())
        .from(customer)
        .fetchOne();
```

**결과**
```sql
select sum(case when c1_0.grade = ? then c1_0.points else 0 end),
       sum(case when c1_0.grade = ? then c1_0.points else 0 end),
       sum(case when c1_0.grade = ? then c1_0.points else 0 end),
       sum(case when c1_0.grade = ? then c1_0.points else 0 end)
from customers c1_0
```
```
바인딩: [1] VIP, [2] GOLD, [3] SILVER, [4] BRONZE
VIP    = 77000
GOLD   = 68400
SILVER = 24800
BRONZE =  8570
합계     178770   ← 8-2 의 sum(points) 와 일치
```

### 조건부 `count`

"등급별 인원" 도 같은 방식으로 한 행에 넣을 수 있습니다.

```java
NumberExpression<Integer> vipCount = new CaseBuilder()
        .when(customer.grade.eq(Grade.VIP)).then(1).otherwise(0);

Tuple counts = queryFactory
        .select(vipCount.sum(), customer.count())
        .from(customer)
        .fetchOne();
```

**결과**
```sql
select sum(case when c1_0.grade = ? then 1 else 0 end), count(c1_0.customer_id)
from customers c1_0
```
```
바인딩: [1] VIP
VIP = 4 / 전체 = 30
```

> ⚠️ **함정 — `otherwise(0)` 대신 `otherwise(null)` 을 쓰면 결과가 달라집니다**
> `SUM` 은 NULL 을 무시하므로 합계는 같습니다. 하지만 `COUNT` 로 바꾸면 다릅니다.
> ```java
> // otherwise(0) + count() → 항상 전체 행 수 (0도 세니까)
> // otherwise(null) + count() → 조건에 맞는 행 수
> ```
> **조건부 집계는 `sum` 을 쓰는 것이 안전합니다.** `count` 와 섞으면 헷갈립니다.

> 💡 `CaseBuilder` 와 `Expressions` 의 전체 사용법은 [Step 13 — 고급 표현식](../step-13-advanced/) 에서 다룹니다.
> 여기서는 "집계와 조합하면 피벗이 된다" 는 것만 기억하십시오.

---

## 8-12. MySQL8 Step 06 대조표

| 질문 | MySQL8 SQL | QueryDSL 6 |
|---|---|---|
| 행 수 | `COUNT(*)` | `customer.count()` → `count(c1_0.customer_id)` |
| 컬럼 count | `COUNT(phone)` | `customer.phone.count()` |
| 중복 제거 count | `COUNT(DISTINCT city)` | `customer.city.countDistinct()` |
| 합계 | `SUM(total_amount)` | `order.totalAmount.sum()` → **`BigDecimal`** |
| 평균 | `AVG(total_amount)` | `order.totalAmount.avg()` → **`Double`** |
| 그룹핑 | `GROUP BY grade` | `.groupBy(customer.grade)` |
| 그룹 필터 | `HAVING COUNT(*) >= 5` | `.having(order.count().goe(5))` |
| 집계로 정렬 | `ORDER BY COUNT(*) DESC` | `.orderBy(order.count().desc())` |
| NULL 대체 | `COALESCE(SUM(x), 0)` | `.sum().coalesce(BigDecimal.ZERO)` |
| 조건부 집계 | `SUM(CASE WHEN ... THEN ... ELSE 0 END)` | `new CaseBuilder().when(...).then(...).otherwise(0).sum()` |
| 대표값 | `ANY_VALUE(name)` | **대응 API 없음** → `customer.name.max()` |
| 그룹 문자열 결합 | `GROUP_CONCAT(name)` | **대응 API 없음** → `Expressions.stringTemplate` (Step 13) |
| 소계 | `WITH ROLLUP` | **대응 API 없음** → 네이티브 쿼리 |
| 결과를 Map 으로 | (SQL 로 불가) | **`transform(groupBy(...).as(...))`** — JPA 고유 |

### 개념 대조

| | MySQL SQL | QueryDSL |
|---|---|---|
| `COUNT(*)` 표기 | 있음 | 없음 — 엔티티 count 가 PK count 로 번역 |
| `ONLY_FULL_GROUP_BY` 위반 | `ERROR 1055` | 그대로 SQL 을 보내 **MySQL 이 거부** |
| `HAVING` 에 별칭 | 가능 (MySQL 확장) | 별칭 개념 없음 — 표현식 객체를 그대로 |
| 0건일 때 `SUM` | `NULL` | `null` — **자바에서 NPE 로 이어짐** |
| 평균 타입 | `DECIMAL` 유지 | **항상 `Double`** ← 차이 |
| 메모리 그룹핑 | 개념 없음 | **`transform()`** ← QueryDSL 고유, group by 아님 |

---

## 정리

| 개념 | 핵심 |
|---|---|
| 집계 함수 | Path 의 메서드. `order.totalAmount.sum()` |
| `count()` 반환 | 항상 `Long`. 엔티티 count 는 `count(PK)` 로 번역 |
| `sum()` / `max()` / `min()` | **원래 타입 유지** (BigDecimal 이면 BigDecimal) |
| **`avg()`** | **항상 `Double`** — BigDecimal 컬럼이어도 |
| `Tuple.get()` | **표현식 객체**를 키로. 변수로 뽑아 두는 것이 관례 |
| `Tuple.get(int)` | 반환 타입 `Object`. **쓰지 말 것** — 순서 바뀌면 조용히 틀림 |
| `groupBy` | SQL 의 `GROUP BY`. 여러 컬럼 가능 |
| `having` | 그룹 필터. **집계와 무관한 조건은 `where` 로** |
| **`count(컬럼)`** | **NULL 을 세지 않음.** `customer.count()` 30 vs `phone.count()` 27 |
| `avg()` 의 분모 | `COUNT(col)` — NULL 아닌 행 수 |
| **`sum()` 0건** | **`0` 이 아니라 `null`** → NPE. `coalesce(BigDecimal.ZERO)` |
| `leftJoin` + 집계 | `coalesce` 를 습관적으로 |
| **`transform()`** | **애플리케이션 메모리 그룹핑.** 생성 SQL 에 `group by` **없음** |
| `transform` 시작 | `select()` 없이 `from()` 으로 |
| `GroupBy.list/set/map/sum/avg` | 전부 **JVM 에서** 계산 |
| `transform` 의 진짜 가치 | DB 부하 절감이 아니라 **조립 코드 제거 + fan-out 정리** |
| fan-out + `count` | `countDistinct()` 로 고침 |
| **fan-out + `sum`** | **`distinct` 로 못 고침** → 조인 제거 또는 서브쿼리 |
| `ONLY_FULL_GROUP_BY` | QueryDSL 이 막지 않음. MySQL 이 `ERROR 1055` |
| PK 로 groupBy | MySQL 함수 종속성으로 통과. **다른 DB 에서는 깨질 수 있음** |
| 조건부 집계 | `new CaseBuilder().when(...).then(...).otherwise(0).sum()` |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. 도시별 고객 수와 평균 포인트를 조회하십시오. 고객 수 내림차순, 같으면 도시 오름차순. 표현식을 변수로 뽑아 `Tuple` 에서 꺼내십시오. (기대: 8행, 서울 10명)
2. 전체 고객 수와 전화번호가 있는 고객 수를 **한 쿼리로** 조회하고, 두 값이 다른 이유를 설명하십시오. (기대: 30 / 27)
3. 취소를 제외한 주문 합계가 4천만 원 이상인 고객을 조회하십시오. `where` 와 `having` 을 각각 어디에 써야 하는지 판단하십시오. (기대: 8행)
4. `shipping_city` 가 `'제주'` 인 주문의 합계를 구하되, 0건이어도 `null` 이 아니라 `0` 이 나오도록 작성하십시오. 생성 SQL 에 `coalesce` 가 들어갔는지 확인하십시오.
5. 고객별 주문 목록을 `Map<Long, List<OrderDto>>` 로 받으십시오. `transform` 을 쓰고, **생성 SQL 에 `group by` 가 없다는 것**을 로그로 확인한 뒤 그 이유를 주석으로 쓰십시오. (기대: Map 크기 30)
6. 문제 5 와 같은 조인에서 **고객별 주문 건수만** 필요하다면 어떻게 써야 하는지 작성하고, 문제 5 와 **네트워크로 넘어오는 행 수**를 비교하십시오. (기대: 600행 vs 30행)
7. 고객 → 주문 → 주문상세를 모두 조인한 상태에서 "고객별 주문 건수" 를 구하십시오. 먼저 `count()` 로 써서 **틀린 답(40)** 을 확인하고, `countDistinct()` 로 고친 뒤, 마지막으로 **불필요한 조인을 제거한 버전**까지 세 개를 모두 작성하십시오.

---

## 다음 단계

집계까지 왔으니 이제 결과를 **정렬하고 잘라서** 화면에 내보낼 차례입니다.
8-3 에서 `orderBy(order.count().desc())` 처럼 집계 표현식으로 정렬해 봤는데,
정렬은 그 자체로 따로 다룰 만큼 함정이 많습니다.

다음 스텝에서는 `orderBy` / `offset` / `limit`, QueryDSL 5.0 에서 **폐기된 `fetchCount()` 대응**,
그리고 정렬 컬럼에 함수를 씌워 인덱스를 죽이는 문제를 다룹니다.
`fetchJoin` 과 페이징을 같이 쓸 때 전건이 메모리로 올라오는 문제(Step 06)도 여기서 다시 만납니다.

→ [Step 09 — 정렬과 페이징](../step-09-sorting-paging/)

---

## 실습 파일

이 스텝은 자바 파일 세 개로 구성됩니다. 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이므로,
프로젝트의 `src/test/java/com/example/shop/step08/` 에 그대로 복사해 넣고 실행하면 됩니다.
`Practice.java` 로 8-1 ~ 8-12 절의 예제를 확인하고, `Exercise.java` 의 7문제를 직접 푼 뒤,
`Solution.java` 로 맞춰 보는 순서입니다. 세 파일 모두 **데이터를 변경하지 않습니다.**

이 스텝은 특히 **로그를 보지 않으면 배울 수 없습니다.**
8-8 절의 `transform` 은 "생성 SQL 에 `group by` 가 없다" 는 것이 핵심인데,
그것은 오직 로그로만 확인할 수 있습니다. 반드시 SQL 로그를 켜 두십시오.

```yaml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

### Practice.java

본문 8-1 ~ 8-12 절의 모든 예제를 `// [8-3] 절 제목` 주석과 함께 담은 테스트 클래스입니다.

- `avgTypeTrap()` 은 **의도적으로 `ClassCastException` 을 일으키는 코드**입니다.
  `assertThatThrownBy` 로 감싸 두었으므로 테스트는 통과하지만, 예외 메시지를 반드시 읽으십시오.
  `class java.lang.Double cannot be cast to class java.math.BigDecimal` 이 나와야 정상입니다.
- `countIgnoresNull()` 에서 30 과 27 을 눈으로 확인하십시오.
  같은 `Long` 타입이고 코드도 비슷하게 생겼는데 값이 다릅니다.
- `sumOfNothingIsNull()` 은 `null` 을 반환하는 것을 확인하고,
  바로 다음 `sumWithCoalesce()` 가 `0` 을 반환하는 것과 대조합니다.
  생성 SQL 의 `coalesce(sum(...), ?)` 를 확인하십시오.
- **`transformDoesNotGroupInDb()` 가 이 파일의 핵심입니다.**
  실행 후 로그에서 `group by` 를 찾아보십시오. **없습니다.**
  바로 아래 `dbGroupByComparison()` 의 SQL 에는 `group by` 가 있습니다. 두 로그를 나란히 놓고 비교하십시오.
- `fanOutBreaksCount()` / `fanOutFixedByDistinct()` / `fanOutFixedByRemovingJoin()` 은
  같은 질문에 대한 세 답입니다. 첫 번째가 40, 나머지 둘이 20 을 냅니다.
- `fanOutBreaksSumUnfixable()` 은 `sum` 이 `distinct` 로 고쳐지지 않는다는 것을 보여 줍니다.
  이 경우 답은 "조인을 지우는 것" 뿐입니다.

```java file="./Practice.java"
```

### Exercise.java

「연습문제」 절의 7문제를 담은 빈칸 파일입니다.
각 문항이 `// 문제 N.` 주석 블록으로 되어 있고 `// 여기에 작성:` 아래가 비어 있습니다.

- 문항마다 **기대 결과가 명시**되어 있습니다. 건수뿐 아니라 **생성 SQL 의 모양**까지 맞아야 정답입니다.
- 문제 2 는 값 두 개를 구하는 것이 목적이 아니라, **왜 다른지**를 설명하는 것이 목적입니다.
  주석으로 이유를 직접 써 보십시오.
- 문제 4 는 `coalesce` 가 SQL 에 들어갔는지를 로그로 확인해야 합니다.
  자바 쪽 `Optional` 로 처리했다면 SQL 에는 `coalesce` 가 없을 것입니다. 둘 다 유효하지만 다른 답입니다.
- 문제 5 와 6 은 짝입니다. **네트워크로 넘어오는 행 수를 세어 보는 것**이 핵심입니다.
  `transform` 쪽은 600행, `groupBy` 쪽은 30행입니다.
- 문제 7 은 세 버전을 모두 작성해야 합니다. 틀린 답(40)을 **먼저 직접 만들어 보는 것**이 목적이므로,
  건너뛰지 마십시오.

```java file="./Exercise.java"
```

### Solution.java

`Exercise.java` 의 정답과 해설입니다. 답만 있는 게 아니라 **왜 그렇게 푸는지**가 주석으로 붙어 있습니다.

- 각 답 아래에 생성 SQL 이 주석으로 붙어 있습니다. 자기 콘솔의 로그와 한 글자씩 대조하십시오.
- 문제 2 의 주석에서 `count(c1_0.customer_id)` 와 `count(c1_0.phone)` 이
  **왜 다른 SQL 로 번역되는지**를 설명합니다. `customer.count()` 가 `count(*)` 가 아니라는 점이 핵심입니다.
- 문제 4 의 주석에서 `coalesce` 방식과 `Optional` 방식의 차이를 정리합니다.
  전자는 **DB 에서** NULL 을 대체하고 후자는 **자바에서** 대체합니다.
  `groupBy` 가 붙어 여러 행이 나올 때는 전자만 통합니다.
- **문제 5, 6 의 주석이 이 파일에서 가장 깁니다.** `transform` 이 언제 옳고 언제 그른지를
  "원본 행이 실제로 필요한가" 라는 하나의 기준으로 정리합니다.
  `transform` 을 성능 최적화 도구로 오해하는 것이 이 API 를 둘러싼 가장 흔한 착각입니다.
- 문제 7 의 주석에서 `count` / `sum` / `avg` 각각이 fan-out 에 어떻게 반응하는지 표로 정리합니다.
  `count` 만 `distinct` 로 고쳐지고 나머지는 안 됩니다.

```java file="./Solution.java"
```
