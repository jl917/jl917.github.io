# Step 07 — 서브쿼리

> **학습 목표**
> - `JPAExpressions` 로 where / select / having 절 서브쿼리를 작성한다
> - 바깥 별칭과 서브쿼리 별칭을 분리해야 하는 이유를 생성 SQL 로 확인한다
> - `in` / `exists` / `notExists` 를 구분하고, 상관 서브쿼리가 언제 도는지 안다
> - **`NOT IN` + NULL 함정**을 QueryDSL 로 재현하고 `notExists` 로 고친다
> - select 절 상관 서브쿼리가 **행 수만큼 반복 실행되는 비용**을 측정한다
> - **JPQL 이 from 절 서브쿼리를 지원하지 않는다는 사실**과 우회 3가지를 익힌다
>
> **선행 스텝**: [Step 06 — 조인](../step-06-joins/)
> **예상 소요**: 90분

---

## 7-0. 이 스텝의 위치

Step 06 에서 조인을 배웠습니다. 조인은 "여러 테이블의 행을 옆으로 붙이는" 연산입니다.
서브쿼리는 다릅니다. **"질문에 답하기 위해 먼저 답해야 하는 작은 질문"** 을 쿼리 안에 넣는 것입니다.

> 📌 MySQL8 코스 [Step 08 — 서브쿼리](../../mysql8/step-08-subqueries/) 에서 SQL 로 배웠던 내용입니다.
> 이 스텝은 그 SQL 을 QueryDSL 로 다시 쓰면서, **QueryDSL 로 쓸 수 있는 것과 쓸 수 없는 것**의 경계를 긋습니다.

미리 결론부터 말하면 경계는 이렇습니다.

| 절 | SQL | JPQL / QueryDSL-JPA |
|---|---|---|
| `where` 서브쿼리 | 가능 | **가능** |
| `having` 서브쿼리 | 가능 | **가능** |
| `select` 서브쿼리 | 가능 | **가능** |
| `from` 서브쿼리 (인라인 뷰) | 가능 | **불가능** ← 이 스텝의 핵심 |

`from` 절만 막혀 있습니다. 그리고 그것 때문에 실무에서 꽤 자주 막힙니다.
7-7 절에서 왜 그런지와 어떻게 우회하는지를 길게 다룹니다.

이 스텝의 코드는 아래 static import 를 전제로 합니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QReview.review;
import static com.example.shop.entity.QEmployee.employee;
import static com.example.shop.entity.QPayment.payment;
import static com.querydsl.jpa.JPAExpressions.select;
import static com.querydsl.jpa.JPAExpressions.selectOne;
import static com.querydsl.jpa.JPAExpressions.selectFrom;
```

---

## 7-1. `JPAExpressions` — 서브쿼리를 만드는 정적 팩토리

`queryFactory` 는 **실행 가능한** 쿼리를 만듭니다. `fetch()` 를 부르면 SQL 이 나갑니다.
서브쿼리는 혼자 실행되면 안 됩니다. 바깥 쿼리에 **표현식으로 끼워 넣어야** 합니다.
그래서 QueryDSL 은 서브쿼리 전용 정적 팩토리를 따로 둡니다.

```java
JPQLQuery<Double> sub = JPAExpressions
        .select(customer.points.avg())
        .from(customer);
```

`JPAExpressions.select(...)` 가 돌려주는 것은 `JPQLQuery<T>` 이고, 이것은 동시에 `Expression<T>` 입니다.
표현식이므로 `where(...)` 안에 값처럼 들어갈 수 있습니다.

| 팩토리 메서드 | 용도 |
|---|---|
| `JPAExpressions.select(expr)` | 값 하나(스칼라) 또는 컬럼 하나를 뽑는 서브쿼리 |
| `JPAExpressions.selectFrom(entity)` | `select(entity).from(entity)` 축약 |
| `JPAExpressions.selectOne()` | `select(1)`. `exists` 전용 관례 |
| `JPAExpressions.selectDistinct(expr)` | 중복 제거 |

`JPAExpressions` 는 전부 static 메서드이므로 **static import 하는 것이 관례**입니다.

```java
import static com.querydsl.jpa.JPAExpressions.select;
import static com.querydsl.jpa.JPAExpressions.selectOne;
```

이렇게 두면 본문이 아래처럼 짧아집니다.

```java
.where(customer.points.gt(select(sub.points.avg()).from(sub)))
```

> 💡 **실무 팁 — `JPAExpressions` 와 `queryFactory` 를 섞지 마십시오**
> `queryFactory.select(...).from(...)` 로 만든 쿼리를 서브쿼리 자리에 넣으면 컴파일은 됩니다
> (`JPAQuery` 도 `Expression` 이므로). 하지만 그 객체는 `EntityManager` 를 물고 있어
> 의도치 않게 독립 실행될 여지가 있고, 무엇보다 "이건 서브쿼리다" 라는 의도가 코드에서 사라집니다.
> **서브쿼리는 반드시 `JPAExpressions` 로** 만드십시오. 읽는 사람이 한눈에 구분할 수 있습니다.

---

## 7-2. where 절 스칼라 서브쿼리 — 그리고 별칭 함정

### 평균 포인트보다 많은 고객

> 📌 MySQL8 [Step 08 — 8-1 절](../../mysql8/step-08-subqueries/) 의 `WHERE price > (SELECT AVG(price) ...)` 와 같은 구조입니다.

먼저 SQL 로 쓰면 이렇습니다.

```sql
SELECT customer_id, name, grade, points
FROM customers
WHERE points > (SELECT AVG(points) FROM customers)
ORDER BY points DESC;
```

QueryDSL 로 옮깁니다. **여기서 첫 번째 함정이 나옵니다.**

```java
QCustomer sub = new QCustomer("sub");          // ← 별칭이 다른 Q타입을 하나 더 만든다

List<Customer> result = queryFactory
        .selectFrom(customer)
        .where(customer.points.gt(
                select(sub.points.avg()).from(sub)
        ))
        .orderBy(customer.points.desc())
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points > (select avg(c2_0.points) from customers c2_0)
order by c1_0.points desc
```
```
조회 11건
 1. 김서준   VIP     22400
 2. 류하나   VIP     19800
 3. 정  훈   VIP     18600
 4. 배채영   VIP     16200
 5. 안지수   GOLD    12400
 ... (총 11건, 평균 5959 초과)
```

바깥 루트는 `c1_0`, 서브쿼리 루트는 `c2_0` 으로 **서로 다른 별칭**이 붙었습니다. 이것이 정상입니다.

### 별칭을 같게 쓰면 어떻게 되는가

Step 02 에서 Q타입 인스턴스는 **경로(path)에 이름표를 붙인 객체**라고 했습니다.
`QCustomer.customer` 라는 기본 인스턴스는 별칭 `customer` 를 갖습니다.
바깥과 안쪽에서 **같은 인스턴스**를 쓰면 어떻게 될까요?

```java
// ⚠️ 잘못된 코드 — 컴파일도 되고 예외도 안 납니다
List<Customer> wrong = queryFactory
        .selectFrom(customer)
        .where(customer.points.gt(
                select(customer.points.avg()).from(customer)   // ← 같은 customer
        ))
        .fetch();
```

**결과** — `hibernate.SQL` 로그
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.points > (select avg(c1_0.points) from customers c1_0)
```
```
조회 0건
```

서브쿼리 안의 `avg(c1_0.points)` 가 **바깥과 같은 별칭 `c1_0`** 을 가리킵니다.
즉 "각 고객 자신의 포인트 평균" = 자기 자신의 포인트가 되어, `points > points` 라는
영원히 거짓인 조건이 되었습니다. 결과는 0건인데 **에러는 나지 않습니다.**

> ⚠️ **함정 — 서브쿼리에 바깥과 같은 Q타입 인스턴스를 쓰는 것**
> 이건 이 코스가 계속 말하는 그 유형입니다. **컴파일 통과 + 예외 없음 + 결과만 틀림.**
> 개발 데이터에서 "0건이네? 데이터가 없나 보다" 하고 넘어가기 딱 좋습니다.
> **같은 엔티티를 바깥과 서브쿼리에서 동시에 쓸 때는 반드시 별칭이 다른 인스턴스를 새로 만드십시오.**
> ```java
> QCustomer sub = new QCustomer("sub");
> ```
> 반대로 **의도적으로 바깥 행을 참조하고 싶을 때**(상관 서브쿼리)는 같은 인스턴스를 씁니다(7-4).
> 그 구분이 이 스텝 전체의 핵심입니다.

> 💡 **실무 팁 — 별칭 인스턴스는 메서드 안에서 지역 변수로**
> `private static final QCustomer SUB = new QCustomer("sub");` 처럼 상수로 두는 팀도 있습니다.
> Q타입 인스턴스는 불변이므로 스레드 안전하고, 이렇게 하면 별칭 문자열이 한 곳에만 존재합니다.
> 다만 서브쿼리가 중첩되면 `sub`, `sub2` 처럼 이름이 늘어나므로,
> **의미 있는 이름**(`avgSrc`, `maxSrc`)을 쓰는 편이 읽기 좋습니다.

### 서로 다른 엔티티라면 별칭 걱정이 없다

바깥이 `Customer`, 안쪽이 `Order` 처럼 **엔티티가 다르면** 별칭 충돌이 없습니다.

```java
List<Order> bigOrders = queryFactory
        .selectFrom(order)
        .where(order.totalAmount.gt(
                select(order2.totalAmount.avg()).from(order2)   // order2 = new QOrder("o2")
        ))
        .orderBy(order.totalAmount.desc())
        .limit(5)
        .fetch();
```

같은 `Order` 를 두 번 쓰므로 여기서도 별칭 분리는 필요합니다.
**"같은 엔티티가 바깥과 안쪽에 동시에 등장하는가"** 만 확인하면 됩니다.

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date, o1_0.shipping_city,
       o1_0.status, o1_0.total_amount
from orders o1_0
where o1_0.total_amount > (select avg(o2_0.total_amount) from orders o2_0)
order by o1_0.total_amount desc
limit ?
```
```
바인딩: [1] 5
조회 5건 — 6663900.00 이 5건 (시드 데이터가 규칙적이라 최대 금액이 동률입니다)
```

---

## 7-3. `in` 서브쿼리

값이 여러 개인 서브쿼리는 `=` 가 아니라 `in` 으로 받습니다.

```java
List<Customer> hasOrder = queryFactory
        .selectFrom(customer)
        .where(customer.id.in(
                select(order.customer.id).from(order)
        ))
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.customer_id in (select o1_0.customer_id from orders o1_0)
order by c1_0.customer_id
```
```
조회 30건 — 전체 고객
```

`order.customer.id` 는 **조인을 만들지 않습니다.** `orders.customer_id` 는 이미 `orders` 테이블에
있는 FK 컬럼이므로, Hibernate 는 `orders` 만 읽고 끝냅니다. Step 06 에서 다룬 규칙이 여기서도 그대로입니다.

다만 이 예제는 결과가 30건, 즉 **전체 고객**입니다. 시드 데이터에서 30명 모두 주문 20건씩 갖고 있으니까요.
필터로서 의미가 없습니다. 판별력이 있는 예제로 바꿉니다.

### 후기를 쓴 고객

```java
List<Customer> reviewers = queryFactory
        .selectFrom(customer)
        .where(customer.id.in(
                select(review.customer.id).from(review)
        ))
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where c1_0.customer_id in (select r1_0.customer_id from reviews r1_0)
order by c1_0.customer_id
```
```
조회 4건
  1  김민수  VIP
 22  안지수  GOLD
 25  양현우  VIP
 28  심준호  SILVER
```

> 📌 MySQL8 [Step 08 — 8-5 절](../../mysql8/step-08-subqueries/) 의 `EXISTS (SELECT 1 FROM reviews ...)` 와 같은 4명입니다.

### `notIn`

부정형도 있습니다. **그리고 여기가 이 스텝 최대의 지뢰입니다.** 7-5 에서 자세히 봅니다.

```java
.where(customer.id.notIn(select(review.customer.id).from(review)))
```

### 컬렉션 리터럴 `in` 과 헷갈리지 말 것

```java
.where(customer.grade.in(Grade.VIP, Grade.GOLD))          // 값 목록 in — 서브쿼리 아님
.where(customer.id.in(select(review.customer.id).from(review)))  // 서브쿼리 in
```

앞의 것은 `in (?, ?)` 로 바인딩 파라미터가 되고, 뒤의 것은 `in (select ...)` 가 됩니다.
같은 메서드 이름이지만 **오버로드가 다릅니다.**

---

## 7-4. `exists` / `notExists` — 상관 서브쿼리

`exists` 는 "그런 행이 **하나라도 있으면** 참"입니다. 값을 가져오는 게 아니라 존재 여부만 봅니다.

```java
List<Customer> hasReview = queryFactory
        .selectFrom(customer)
        .where(selectOne()
                .from(review)
                .where(review.customer.eq(customer))    // ← 바깥의 customer 를 참조
                .exists())
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0
where exists (select 1 from reviews r1_0 where r1_0.customer_id = c1_0.customer_id)
order by c1_0.customer_id
```
```
조회 4건 — 김민수, 안지수, 양현우, 심준호
```

**여기서는 바깥과 같은 `customer` 인스턴스를 씁니다.** 7-2 에서는 그게 버그였는데 여기서는 정답입니다.
차이는 의도입니다.

| 목적 | 별칭 |
|---|---|
| 서브쿼리를 **바깥과 무관하게** 한 번만 계산 (전체 평균 등) | **분리해야 함** (`new QCustomer("sub")`) |
| 서브쿼리가 **바깥 행마다 다시 계산** (상관 서브쿼리) | **같은 인스턴스를 써야 함** |

`selectOne()` 은 `select(Expressions.ONE)` 의 축약이고 SQL 로는 `select 1` 이 됩니다.
`exists` 는 행의 존재 여부만 보므로 안쪽에 무엇을 select 하든 결과와 성능이 같습니다.
관례적으로 `selectOne()` 을 씁니다.

### `notExists` — 없는 것 찾기

"후기가 하나도 없는 상품"을 찾습니다. 시드 데이터에서 상품 40개 중 **24개**가 여기 해당합니다.

```java
List<Product> noReview = queryFactory
        .selectFrom(product)
        .where(selectOne()
                .from(review)
                .where(review.product.eq(product))
                .notExists())
        .orderBy(product.id.asc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where not exists (select 1 from reviews r1_0 where r1_0.product_id = p1_0.product_id)
order by p1_0.product_id
```
```
조회 24건
  2  스트레치 슬랙스           69000
  4  캐시미어 니트             139000
  5  슬림핏 치노 팬츠           49000
  ... (총 24건)
```

### `in` 과 `exists` 중 무엇을 쓸까

MySQL 8 옵티마이저는 `IN (서브쿼리)` 를 **세미조인(semijoin)** 으로 변환합니다.
그래서 실행 계획이 거의 같아지고, "`EXISTS` 가 무조건 빠르다" 는 말은 MySQL 5.5 시절 이야기입니다.

```
-> Nested loop semijoin  (cost=13.4 rows=4)
    -> Filter: (c1_0.customer_id is not null)  (cost=3.25 rows=30)
        -> Table scan on c1_0  (cost=3.25 rows=30)
    -> Covering index lookup on r1_0 using idx_reviews_customer (customer_id=c1_0.customer_id)
```

| 상황 | 권장 |
|---|---|
| 존재 여부만 확인 | `exists` 또는 `in` (성능 차이 거의 없음) |
| **없는 것**을 찾기 (안티 조인) | **`notExists`** — `notIn` 은 NULL 에 취약 (7-5) |
| 서브쿼리 쪽 컬럼이 결과에도 필요 | 조인 (Step 06) |
| 1:N 인데 개수를 세야 함 | 조인 + `countDistinct()` 또는 집계 (Step 08) |

성능 대비는 [Step 14 — 성능](../step-14-performance/) 에서 실측으로 다시 다룹니다.

> 💡 **실무 팁 — `exists` 는 `BooleanExpression` 이다**
> `.exists()` 가 돌려주는 타입은 `BooleanExpression` 입니다. Step 04 에서 배운 조건 조립이 그대로 됩니다.
> ```java
> private BooleanExpression hasReview() {
>     return selectOne().from(review).where(review.customer.eq(customer)).exists();
> }
> // 사용
> .where(customer.grade.eq(Grade.VIP), hasReview())
> ```
> 서브쿼리 조건을 메서드로 뽑아 재사용할 수 있다는 것이 QueryDSL 의 큰 장점입니다.
> 문자열 JPQL 로는 이렇게 못 합니다.

---

## 7-5. ⚠️ `NOT IN` + NULL — 조용히 0건

> 📌 MySQL8 [Step 08 — 8-8 절](../../mysql8/step-08-subqueries/) 의 그 함정입니다.
> **QueryDSL 로 쓴다고 해서 SQL 의 3값 논리가 달라지지 않습니다.**

"부하 직원이 한 명도 없는 사원" 을 찾습니다. `employees` 는 18명이고, 그중 관리자 역할을 하는 사람이 8명,
부하가 없는 사원이 **10명**입니다. 자연스러운 첫 시도는 이렇습니다.

```java
// ⚠️ 잘못된 코드
QEmployee manager = new QEmployee("mgr");

List<Employee> leaf = queryFactory
        .selectFrom(employee)
        .where(employee.id.notIn(
                select(manager.manager.id).from(manager)
        ))
        .fetch();
```

**결과**
```sql
select e1_0.employee_id, e1_0.dept, e1_0.hire_date, e1_0.manager_id,
       e1_0.name, e1_0.position, e1_0.salary
from employees e1_0
where e1_0.employee_id not in (select m1_0.manager_id from employees m1_0)
```
```
조회 0건
```

**0건입니다.** 컴파일 통과, 예외 없음, 로그도 깨끗합니다. 그런데 답은 10건이어야 합니다.

### 왜 0건인가

서브쿼리 결과를 직접 봅니다.

```java
List<Long> managerIds = queryFactory
        .select(employee.manager.id)
        .from(employee)
        .fetch();
```

**결과**
```
[null, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 8, 8]
  ↑ CEO 는 상사가 없으므로 manager_id 가 NULL
```

`NULL` 이 하나 섞여 있습니다. SQL 의 `NOT IN` 은 이렇게 전개됩니다.

```
e.employee_id NOT IN (null, 1, 1, ...)
  ≡ e.employee_id <> null AND e.employee_id <> 1 AND ...
       ↑ 이게 UNKNOWN
```

SQL 은 TRUE / FALSE / **UNKNOWN** 의 3값 논리를 씁니다.
`x <> NULL` 은 FALSE 가 아니라 **UNKNOWN** 이고, `UNKNOWN AND 무엇` 은 절대 TRUE 가 될 수 없습니다.
`where` 는 **TRUE 인 행만** 통과시키므로 결과는 항상 0건입니다.

```java
// 확인용 — MySQL 에서 직접
// SELECT 5 NOT IN (1, 2, NULL);  →  NULL
```

> ⚠️ **함정 — `notIn` 서브쿼리에 NULL 이 섞일 수 있는가**
> **에러가 나지 않고 조용히 0건을 반환합니다.** 이 코스에서 가장 악질적인 부류입니다.
> 개발 DB 에서는 NULL 이 없어서 잘 돌다가, 운영에서 NULL 이 한 행 생기는 순간
> 배치 결과가 통째로 비어 버립니다. 그리고 아무 로그도 남지 않습니다.
> QueryDSL 은 타입 안전하지만 **이 문제는 타입의 문제가 아니라 SQL 시맨틱의 문제**라 막아 주지 않습니다.

### 처방 1 — 서브쿼리에서 NULL 을 걸러낸다

```java
List<Employee> fixed1 = queryFactory
        .selectFrom(employee)
        .where(employee.id.notIn(
                select(manager.manager.id)
                        .from(manager)
                        .where(manager.manager.isNotNull())   // ← 추가
        ))
        .orderBy(employee.id.asc())
        .fetch();
```

**결과**
```sql
select e1_0.employee_id, e1_0.dept, e1_0.hire_date, e1_0.manager_id,
       e1_0.name, e1_0.position, e1_0.salary
from employees e1_0
where e1_0.employee_id not in (
        select m1_0.manager_id from employees m1_0 where m1_0.manager_id is not null)
order by e1_0.employee_id
```
```
조회 10건 — 정답
```

### 처방 2 — `notExists` 로 바꾼다 (권장)

```java
List<Employee> fixed2 = queryFactory
        .selectFrom(employee)
        .where(selectOne()
                .from(manager)
                .where(manager.manager.eq(employee))
                .notExists())
        .orderBy(employee.id.asc())
        .fetch();
```

**결과**
```sql
select e1_0.employee_id, e1_0.dept, e1_0.hire_date, e1_0.manager_id,
       e1_0.name, e1_0.position, e1_0.salary
from employees e1_0
where not exists (select 1 from employees m1_0 where m1_0.manager_id = e1_0.employee_id)
order by e1_0.employee_id
```
```
조회 10건 — 정답
```

`not exists` 는 "그런 행이 존재하는가" 만 묻습니다. 비교 자체가 `=` 조인 조건이고,
`m1_0.manager_id = e1_0.employee_id` 에서 `manager_id` 가 NULL 이면 그냥 **매칭이 안 될 뿐**입니다.
UNKNOWN 이 바깥으로 전파되지 않습니다. **구조적으로 NULL 에 안전합니다.**

### 처방 3 — 안티 조인 (Step 06)

```java
List<Employee> fixed3 = queryFactory
        .selectFrom(employee)
        .leftJoin(manager).on(manager.manager.eq(employee))
        .where(manager.id.isNull())
        .orderBy(employee.id.asc())
        .fetch();
```

**결과**
```sql
select e1_0.employee_id, e1_0.dept, e1_0.hire_date, e1_0.manager_id,
       e1_0.name, e1_0.position, e1_0.salary
from employees e1_0
left join employees m1_0 on m1_0.manager_id = e1_0.employee_id
where m1_0.employee_id is null
order by e1_0.employee_id
```
```
조회 10건 — 정답
```

> ⚠️ **안티 조인의 `isNull()` 은 조인 대상의 NOT NULL 컬럼(보통 PK)에 걸어야 합니다.**
> `manager.id.isNull()` 이 맞고, `manager.manager.id.isNull()` 은 틀립니다.
> 후자는 "조인은 됐지만 그 사람의 상사가 없는 경우"까지 섞여 들어옵니다.

**세 처방 모두 10건입니다. 습관은 처방 2 로 잡으십시오.**

| 방법 | NULL 안전 | 비고 |
|---|---|---|
| `notIn` 그대로 | ❌ | 조용히 0건 |
| `notIn` + `isNotNull()` | ⭕ | 서브쿼리 컬럼이 늘어나면 잊기 쉬움 |
| **`notExists`** | ⭕ | **권장.** 구조적으로 안전 |
| `leftJoin` + `isNull()` | ⭕ | `isNull` 대상 컬럼 선택에 주의 |

---

## 7-6. select 절 서브쿼리 — 편리하지만 비쌉니다

"고객 목록 + 각 고객의 주문 수" 를 한 번에 뽑고 싶습니다.
SQL 이라면 select 절에 상관 서브쿼리를 넣습니다.

```sql
SELECT c.customer_id, c.name,
       (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.customer_id) AS order_count
FROM customers c;
```

QueryDSL 에서는 서브쿼리에 별칭을 붙여야 하는데, `JPQLQuery` 에는 `.as(...)` 가 없습니다.
`ExpressionUtils.as(...)` 를 씁니다.

```java
import com.querydsl.core.types.ExpressionUtils;

List<Tuple> rows = queryFactory
        .select(customer.id,
                customer.name,
                ExpressionUtils.as(
                        select(order.count()).from(order).where(order.customer.eq(customer)),
                        "orderCount"))
        .from(customer)
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id,
       c1_0.name,
       (select count(o1_0.order_id) from orders o1_0 where o1_0.customer_id = c1_0.customer_id)
from customers c1_0
order by c1_0.customer_id
```
```
조회 30건
  1  김민수   20
  2  이지은   20
  3  박철수   20
  4  최영희   20
  5  정  훈   20
  ... (총 30건, 시드 데이터가 규칙적이라 전원 20건)
```

값 꺼내기는 Step 05 의 `Tuple` 규칙 그대로입니다. 다만 서브쿼리 표현식으로 꺼내야 하므로
**표현식을 변수로 뽑아 두는 것이 사실상 필수**입니다.

```java
Expression<Long> orderCount = ExpressionUtils.as(
        select(order.count()).from(order).where(order.customer.eq(customer)),
        "orderCount");

List<Tuple> rows = queryFactory
        .select(customer.id, customer.name, orderCount)
        .from(customer)
        .fetch();

for (Tuple t : rows) {
    Long cnt = t.get(orderCount);       // ← 같은 객체로 꺼낸다
}
```

### 비용

> ⚠️ **함정 — select 절 상관 서브쿼리는 행 수만큼 실행됩니다**
> 위 쿼리는 SQL 한 방으로 보이지만, DB 안에서는 **고객 30명 각각에 대해 `orders` 를 세는 작업**이 30번 일어납니다.
> 고객이 30명이면 문제없습니다. **30만 명이면 30만 번**입니다.
> "쿼리 개수가 1개니까 N+1 이 아니다" 라고 안심하면 안 됩니다.
> 쿼리 개수는 1개인데 **DB 내부 반복이 N번**인 형태입니다. 애플리케이션 로그로는 절대 보이지 않습니다.

같은 결과를 조인 + `groupBy` 로 얻으면 `orders` 를 **한 번만** 훑습니다.

```java
List<Tuple> byJoin = queryFactory
        .select(customer.id, customer.name, order.count())
        .from(customer)
        .leftJoin(order).on(order.customer.eq(customer))
        .groupBy(customer.id, customer.name)
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.name, count(o1_0.order_id)
from customers c1_0
left join orders o1_0 on o1_0.customer_id = c1_0.customer_id
group by c1_0.customer_id, c1_0.name
order by c1_0.customer_id
```
```
조회 30건 — 위와 동일한 결과
```

`leftJoin` 인 점이 중요합니다. `innerJoin` 으로 하면 주문이 0건인 고객이 **행 자체가 사라집니다.**
집계와 조인의 조합은 [Step 08 — 집계와 그룹핑](../step-08-aggregation/) 에서 본격적으로 다룹니다.

> 💡 **실무 팁 — 언제 select 절 서브쿼리를 써도 되는가**
> - 바깥 결과가 **페이징으로 잘려 있어** 행 수가 확정적으로 작을 때 (한 페이지 20건 등)
> - 조인으로 풀면 fan-out 이 생겨 오히려 복잡해질 때
> - 서로 다른 집계를 여러 개 붙여야 해서 `groupBy` 하나로 안 될 때
>
> 반대로 **전체 목록 조회에 select 절 서브쿼리를 붙이는 것**은 거의 항상 나쁜 선택입니다.

---

## 7-7. ⚠️ from 절 서브쿼리(인라인 뷰)는 쓸 수 없습니다

이 절이 이 스텝의 핵심입니다. **QueryDSL-JPA 로는 from 절 서브쿼리를 쓸 수 없습니다.**
문법이 어려운 게 아니라 **API 자체가 없습니다.**

### 왜 불가능한가

Step 01 에서 QueryDSL-JPA 의 변환 경로를 이렇게 그렸습니다.

```
QueryDSL 자바 코드  →  JPQL 문자열  →  Hibernate  →  SQL
     (컴파일 타임 타입 안전)      (런타임 파싱)      (방언 적용)
```

**QueryDSL-JPA 는 SQL 을 만들지 않습니다. JPQL 을 만듭니다.**
따라서 JPQL 이 못 하는 것은 QueryDSL 도 못 합니다. 이것이 근본 이유입니다.

그럼 JPQL 은 왜 from 절 서브쿼리를 막았을까요?

**JPQL 은 테이블이 아니라 엔티티를 대상으로 하는 언어**이기 때문입니다.
JPQL 의 `from` 에 올 수 있는 것은 다음 두 가지뿐입니다.

1. 엔티티 타입 (`from Customer c`)
2. 그 엔티티의 연관 경로 (`from Customer c join c.orders o`)

인라인 뷰의 결과 — `(customer_id, order_cnt, sum_amount)` 같은 임시 결과 집합 — 은
**엔티티가 아닙니다.** 매핑된 클래스도 없고 식별자도 없고 영속성 컨텍스트가 관리할 수도 없습니다.
JPA 명세는 이런 것을 `from` 에 두는 것을 정의하지 않았습니다.

> 📌 이것은 JPA 의 **설계상 제약**이지 구현의 게으름이 아닙니다.
> "엔티티 중심" 이라는 JPA 의 정체성에서 직접 따라 나오는 결과입니다.

### 시도하면 어떻게 되는가

먼저 알아 둘 것: **컴파일이 안 됩니다.**

```java
// ❌ 이런 API 는 존재하지 않습니다
queryFactory
        .select(...)
        .from(select(order.customer.id, order.count()).from(order).groupBy(order.customer.id))
        //   ^^^^ from(SubQueryExpression) 오버로드가 없음 → 컴파일 에러
```

`JPAQuery.from(...)` 의 시그니처는 `from(EntityPath<?>... sources)` 입니다.
`EntityPath` 는 Q타입만 구현합니다. 서브쿼리는 `EntityPath` 가 아니므로 애초에 들어가지 않습니다.

**차라리 다행입니다.** 컴파일 타임에 막히면 배포 전에 알 수 있습니다.
문자열 JPQL 로 같은 시도를 하면 런타임까지 갑니다.

```java
// ❌ 문자열 JPQL 로 우회 시도 → 런타임 파싱 에러
em.createQuery("""
        select d.cid, d.cnt
        from (select o.customer.id as cid, count(o) as cnt from Order o group by o.customer.id) d
        """).getResultList();
```

**결과**
```
org.hibernate.query.SemanticException: Could not interpret path expression 'd.cid'
  ... (Hibernate 버전에 따라 QuerySyntaxException / IllegalArgumentException 형태로도 나타납니다)
```

에러 메시지가 원인을 정확히 알려 주지 않는다는 점에 주의하십시오.
`d.cid` 를 못 읽겠다고 하지만 진짜 원인은 `from (...)` 자체입니다.

---

### 우회 ① — 조인 + `groupBy` 로 풀어쓴다

**대부분의 인라인 뷰는 조인 + `groupBy` 로 재작성할 수 있습니다.** 이것이 첫 번째 선택지입니다.

예제: **카테고리별 최고가 상품**. SQL 로는 인라인 뷰가 자연스럽습니다.

```sql
-- SQL: 인라인 뷰 버전
SELECT p.product_id, p.name, p.category_id, p.price
FROM products p
JOIN (
    SELECT category_id, MAX(price) AS max_price
    FROM products
    GROUP BY category_id
) m ON m.category_id = p.category_id AND m.max_price = p.price
ORDER BY p.category_id;
```

QueryDSL 로는 인라인 뷰를 못 쓰므로, **where 절 상관 서브쿼리**로 같은 의미를 만듭니다.

```java
QProduct sub = new QProduct("sub");

List<Product> topPerCategory = queryFactory
        .selectFrom(product)
        .where(product.price.eq(
                select(sub.price.max())
                        .from(sub)
                        .where(sub.category.eq(product.category))   // 상관
        ))
        .orderBy(product.category.id.asc(), product.id.asc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.price = (select max(s1_0.price) from products s1_0
                    where s1_0.category_id = p1_0.category_id)
order by p1_0.category_id, p1_0.product_id
```
```
조회 12건
  3  라이트 다운 재킷          11    159000.00
  7  트렌치 코트               12    249000.00
 11  첼시 부츠                 13    189000.00
 14  게이밍 노트북 RTX4060     21   2190000.00
 17  스마트폰 X20 Pro 512GB    22   1490000.00
 ... (총 12건 — 소분류 12개 각각의 최고가)
```

인라인 뷰 버전과 **결과가 같고**, DB 가 만드는 실행 계획도 유사합니다.
차이는 상관 서브쿼리가 카테고리 수만큼 반복될 수 있다는 점인데, 카테고리가 12개라 무시할 만합니다.

> 💡 **왜 조인이 아니라 상관 서브쿼리로 풀었나**
> 인라인 뷰의 정체는 "그룹별 집계 결과를 원본에 다시 붙이기" 입니다.
> JPQL 에서 그걸 표현하는 정석이 **상관 서브쿼리**입니다.
> 인라인 뷰를 `join (...)` 으로 붙이는 SQL 습관을 그대로 옮기려 하지 말고,
> **"각 행에 대해 그 그룹의 값이 얼마인가"** 라는 질문으로 바꿔 생각하십시오.

### 우회 ② — 쿼리를 둘로 나누고 애플리케이션에서 조합한다

집계 결과가 커서 상관 서브쿼리를 행마다 돌리기 부담스러울 때 씁니다.

```java
// 1단계: 카테고리별 최고가를 먼저 구한다 (12행)
List<Tuple> maxRows = queryFactory
        .select(product.category.id, product.price.max())
        .from(product)
        .groupBy(product.category.id)
        .fetch();
```

**결과**
```sql
select p1_0.category_id, max(p1_0.price)
from products p1_0
group by p1_0.category_id
```
```
조회 12건
 11   159000.00
 12   249000.00
 13   189000.00
 21  2190000.00
 22  1490000.00
 ...
```

```java
// 2단계: (카테고리, 가격) 쌍으로 상품을 조회한다
BooleanBuilder pairs = new BooleanBuilder();
for (Tuple t : maxRows) {
    pairs.or(product.category.id.eq(t.get(product.category.id))
            .and(product.price.eq(t.get(product.price.max()))));
}

List<Product> result = queryFactory
        .selectFrom(product)
        .where(pairs)
        .orderBy(product.category.id.asc(), product.id.asc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.category_id = ? and p1_0.price = ?
   or p1_0.category_id = ? and p1_0.price = ?
   or ... (12쌍)
order by p1_0.category_id, p1_0.product_id
```
```
바인딩: [1] 11, [2] 159000.00, [3] 12, [4] 249000.00, ...
조회 12건 — 우회 ① 과 동일
```

**쿼리 2번. 왕복 2회.** 대신 각 쿼리가 단순합니다.

> ⚠️ **함정 — 2단계의 `in` 목록이 무한정 커질 수 있습니다**
> 1단계 결과가 12행이면 괜찮지만, 그룹이 10만 개면 `or` 가 10만 개 붙습니다.
> MySQL 은 파싱 자체에서 죽거나(`max_allowed_packet`), 실행 계획이 무너집니다.
> **1단계 결과 크기에 상한이 있는지를 반드시 확인**하고, 없으면 배치로 쪼개십시오.
> Step 04 의 `BooleanBuilder` 조립 규칙이 그대로 적용됩니다.

### 우회 ③ — 네이티브 쿼리 또는 QueryDSL-SQL

**네이티브 쿼리**는 JPQL 을 거치지 않으므로 인라인 뷰가 그대로 됩니다.

```java
List<Object[]> rows = em.createNativeQuery("""
        SELECT p.product_id, p.name, p.price
        FROM products p
        JOIN (SELECT category_id, MAX(price) AS mx FROM products GROUP BY category_id) m
          ON m.category_id = p.category_id AND m.mx = p.price
        ORDER BY p.category_id
        """).getResultList();
```

되기는 됩니다. 하지만 얻은 것과 잃은 것을 정확히 보십시오.

| 얻는 것 | 잃는 것 |
|---|---|
| SQL 전체 기능 (인라인 뷰, 윈도우 함수, `LATERAL`, 힌트) | **타입 안전성** — 컬럼명 오타가 런타임까지 감 |
| DB 고유 최적화 | **DB 이식성** |
| 실행 계획을 손으로 통제 | `Object[]` 수동 매핑 (또는 `@SqlResultSetMapping` 설정) |
| — | 리팩터링 시 IDE 가 못 따라감 |

**`QueryDSL-SQL`** 이라는 별도 모듈도 있습니다. 이름이 비슷해 헷갈리기 쉬운데 완전히 다른 물건입니다.

| | QueryDSL-JPA (이 코스) | QueryDSL-SQL |
|---|---|---|
| 번역 대상 | **JPQL** | **SQL 직접** |
| Q타입 생성 근거 | `@Entity` 클래스 (APT) | **DB 스키마** (역공학 플러그인) |
| from 절 서브쿼리 | **불가능** | **가능** |
| 영속성 컨텍스트 | 사용 | 사용 안 함 |
| 설정 | `querydsl-apt:jpa` | 별도 Gradle 태스크로 스키마 export |

QueryDSL-SQL 은 from 절 서브쿼리, 윈도우 함수 등 SQL 의 전 기능을 타입 안전하게 씁니다.
다만 **Q타입을 DB 스키마에서 생성**하므로 빌드 시점에 DB 연결이 필요하고,
엔티티 기반 Q타입과 이름이 충돌하지 않도록 패키지를 분리해야 하며, 트랜잭션 통합도 따로 신경 써야 합니다.

**이 코스는 QueryDSL-SQL 을 다루지 않습니다.** 하지만 "JPA 로는 안 되는데 어떻게 하지"
라는 벽에 부딪혔을 때 **존재를 아는 것과 모르는 것의 차이는 큽니다.**
설정 방법은 QueryDSL 공식 문서의 `querydsl-sql` 장을 확인하십시오.

### 언제 무엇을 고를 것인가

| 상황 | 선택 |
|---|---|
| 그룹이 적고(수십~수백) 결과를 그룹별로 다시 붙이면 됨 | **① 상관 서브쿼리 / 조인 + groupBy** |
| 그룹은 많지만 1단계 결과에 명확한 상한이 있음 | **② 2회 실행 + 애플리케이션 조합** |
| 인라인 뷰가 2~3단 중첩되고 성능이 절대적으로 중요 | **③ 네이티브 쿼리** |
| 프로젝트 전반이 복잡한 분석 쿼리 중심 | **③ QueryDSL-SQL 도입 검토** |
| 통계/리포트 전용이고 엔티티가 필요 없음 | **③ 네이티브 쿼리 + DTO 매핑** |

> 💡 **실무 팁 — 먼저 ① 을 시도하십시오**
> 실무에서 "인라인 뷰가 꼭 필요하다" 고 느끼는 경우의 8할은 **SQL 로 먼저 써 버렸기 때문**입니다.
> 질문 자체를 "각 행에 대해 그 그룹의 값이 얼마인가" 로 바꿔 다시 쓰면 상관 서브쿼리로 풀립니다.
> ③ 으로 바로 가면 타입 안전성이라는, QueryDSL 을 쓰는 이유 자체를 버리게 됩니다.

---

## 7-8. Hibernate 6 는 되는데 QueryDSL 로는 안 되는 것

여기서 정확히 구분해야 할 것이 있습니다.

**Hibernate 6 의 HQL 은 from 절 서브쿼리를 지원하기 시작했습니다.**
HQL 은 JPQL 의 상위 집합인 Hibernate 고유 언어이고, 6.x 에서 `from (subquery)` 형태와
`lateral` 을 다루는 문법이 추가됐습니다. 즉 **Hibernate 라는 구현체 수준에서는 길이 열렸습니다.**

그런데 **QueryDSL-JPA 로는 여전히 쓸 수 없습니다.** 이유는 단순합니다.

```
Hibernate 6 HQL 이 지원함        ≠        QueryDSL 로 쓸 수 있음
        │                                        │
        │                            QueryDSL-JPA 의 JPAQuery API 에
        │                            from(SubQueryExpression) 오버로드가 없음
        ▼                                        ▼
   HQL 문자열로는 표현 가능          자바 API 로 표현할 방법이 없음 → 컴파일 불가
```

QueryDSL-JPA 는 **JPA 명세를 기준으로 설계된 API** 입니다.
Hibernate 가 명세를 넘어 확장한 기능은, QueryDSL 이 그에 대응하는 메서드를 추가해 주지 않는 한
**자바 코드로 표현할 방법이 없습니다.** API 에 없는 것은 쓸 수 없습니다.

이 구분은 일반적으로 성립합니다.

| 계층 | 무엇을 결정하는가 |
|---|---|
| JPA 명세 | JPQL 로 표현 가능한 것의 하한 |
| Hibernate 6 (구현체) | HQL 로 표현 가능한 것 — 명세보다 **넓음** |
| **QueryDSL-JPA (API)** | **자바 코드로 표현 가능한 것** — 실제 사용 가능 범위 |

**실제로 쓸 수 있는 범위를 결정하는 것은 맨 아래 줄입니다.**
"Hibernate 가 지원한다" 는 블로그 글을 보고 QueryDSL 로 옮기려다
"메서드가 없는데?" 로 끝나는 일이 여기서 생깁니다.

> 💡 **경계에 부딪혔을 때의 실무 순서**
> 1. QueryDSL-JPA API 에 해당 메서드가 있는지 IDE 자동완성으로 확인
> 2. 없으면 → 7-7 의 우회 ①②③ 중 선택
> 3. Hibernate 고유 기능이 꼭 필요하면 → `em.createQuery(HQL문자열)` 또는 네이티브 쿼리
>
> from 절 서브쿼리와 `lateral` 의 정확한 HQL 문법, 지원 시작 버전, 제약 사항은
> **Hibernate 공식 문서(User Guide 의 HQL/JPQL 장)를 직접 확인**하십시오.
> Hibernate 마이너 버전 사이에서도 세부가 달라지는 영역이라 이 교재가 단정하지 않습니다.

> ⚠️ **함정 — QueryDSL 버전을 올리면 될까**
> QueryDSL 6.12 기준으로 `JPAQuery.from(SubQueryExpression)` 은 없습니다.
> 7.x 에서 추가됐는지 여부는 릴리스 노트를 확인해야 하며, 이 교재는 단정하지 않습니다.
> 다만 **API 가 추가되지 않는 한 버전만 올려서는 해결되지 않는다**는 구조만 기억하십시오.

---

## 7-9. 서브쿼리 vs 조인 — 같은 답, 다른 SQL

같은 질문을 두 방식으로 쓰고 생성 SQL 과 실행 계획을 비교합니다.
질문: **"후기를 받은 적이 있는 상품"**

### 방식 A — `exists`

```java
List<Product> byExists = queryFactory
        .selectFrom(product)
        .where(selectOne().from(review).where(review.product.eq(product)).exists())
        .orderBy(product.id.asc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where exists (select 1 from reviews r1_0 where r1_0.product_id = p1_0.product_id)
order by p1_0.product_id
```
```
조회 16건
```

### 방식 B — 조인 + `distinct`

```java
List<Product> byJoin = queryFactory
        .selectFrom(product).distinct()
        .join(review).on(review.product.eq(product))
        .orderBy(product.id.asc())
        .fetch();
```

**결과**
```sql
select distinct p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
join reviews r1_0 on r1_0.product_id = p1_0.product_id
order by p1_0.product_id
```
```
조회 16건 — 같은 결과
```

### `distinct` 를 빠뜨리면

```java
// ⚠️ distinct 없음
List<Product> wrong = queryFactory
        .selectFrom(product)
        .join(review).on(review.product.eq(product))
        .fetch();
```

**결과**
```
조회 80건 ← 후기 수만큼 상품이 중복됨
```

> ⚠️ **함정 — 존재 여부를 조인으로 확인하면 행이 부풀어 오릅니다**
> 상품 16개가 후기 80건과 조인되어 80행이 됩니다. Step 06 의 fan-out 그대로입니다.
> `exists` 는 애초에 "존재 여부" 문법이라 이런 사고가 **구조적으로 일어나지 않습니다.**
> **결과에 조인 상대 테이블의 컬럼이 필요 없다면 조인하지 마십시오.**

### 실행 계획 비교

생성된 SQL 을 그대로 MySQL 에 넣습니다.

```sql
EXPLAIN FORMAT=TREE
select p1_0.product_id from products p1_0
where exists (select 1 from reviews r1_0 where r1_0.product_id = p1_0.product_id);
```

**결과**
```
-> Nested loop semijoin  (cost=25.4 rows=16)
    -> Index scan on p1_0 using idx_products_category  (cost=4.25 rows=40)
    -> Covering index lookup on r1_0 using idx_reviews_product (product_id=p1_0.product_id)
```

```sql
EXPLAIN FORMAT=TREE
select distinct p1_0.product_id from products p1_0
join reviews r1_0 on r1_0.product_id = p1_0.product_id;
```

**결과**
```
-> Table scan on <temporary>  (cost=.. rows=..)
    -> Temporary table with deduplication  (cost=41.5 rows=80)
        -> Nested loop inner join  (cost=33.5 rows=80)
            -> Index scan on p1_0 using idx_products_category  (cost=4.25 rows=40)
            -> Covering index lookup on r1_0 using idx_reviews_product (product_id=p1_0.product_id)
```

`exists` 쪽은 **semijoin** 으로 중복 없이 끝납니다.
`distinct` 쪽은 80행을 만든 뒤 **임시 테이블에서 중복을 제거**합니다. 한 단계가 더 붙습니다.
40행/80행 규모에서는 차이가 없지만, 상품 40만 개 · 후기 800만 건이면 임시 테이블이 디스크로 내려갑니다.

> 📌 실행 계획 읽는 법은 MySQL8 [Step 16 — EXPLAIN과 옵티마이저](../../mysql8/step-16-explain-optimizer/) 를 보십시오.
> QueryDSL 이 만든 SQL 을 그대로 `EXPLAIN` 에 넣을 수 있다는 것이 이 코스의 핵심 습관입니다.

---

## 7-10. `having` 절 서브쿼리와 `all` / `any`

### having 서브쿼리

`having` 에도 서브쿼리를 넣을 수 있습니다.
"전체 평균 주문금액보다 평균이 높은 고객" 을 찾습니다.

```java
QOrder o2 = new QOrder("o2");

List<Tuple> aboveAvg = queryFactory
        .select(order.customer.id, order.totalAmount.avg())
        .from(order)
        .groupBy(order.customer.id)
        .having(order.totalAmount.avg().gt(
                select(o2.totalAmount.avg()).from(o2)
        ))
        .orderBy(order.customer.id.asc())
        .fetch();
```

**결과**
```sql
select o1_0.customer_id, avg(o1_0.total_amount)
from orders o1_0
group by o1_0.customer_id
having avg(o1_0.total_amount) > (select avg(o2_0.total_amount) from orders o2_0)
order by o1_0.customer_id
```
```
조회 14건 — 전체 평균 1,274,330 을 넘는 고객
```

집계와 `groupBy` 는 [Step 08](../step-08-aggregation/) 에서 정면으로 다룹니다.

### `all` / `any` / `some`

```java
// 모든 '주변기기'보다 비싼 '노트북'
QProduct acc = new QProduct("acc");

List<Product> pricier = queryFactory
        .selectFrom(product)
        .where(product.category.id.eq(21L)
                .and(product.price.gt(
                        JPAExpressions.select(acc.price).from(acc).where(acc.category.id.eq(23L)).all()
                )))
        .orderBy(product.price.asc())
        .fetch();
```

**결과**
```sql
select p1_0.product_id, p1_0.category_id, p1_0.cost, p1_0.created_at,
       p1_0.name, p1_0.price, p1_0.status, p1_0.stock
from products p1_0
where p1_0.category_id = ? and p1_0.price > all (select a1_0.price from products a1_0
                                                 where a1_0.category_id = ?)
order by p1_0.price
```
```
바인딩: [1] 21, [2] 23
조회 4건 — 보급형 노트북 15, 울트라북 14 i5/16GB, 울트라북 14 i7/32GB, 게이밍 노트북 RTX4060
```

| 연산자 | 의미 | 동치 |
|---|---|---|
| `.gt(sub.all())` | 서브쿼리의 **모든** 값보다 큼 | `> max(...)` |
| `.gt(sub.any())` | 서브쿼리의 **어떤** 값보다 큼 | `> min(...)` |
| `.eq(sub.any())` | 목록에 있음 | `in` 과 동일 |
| `.ne(sub.all())` | 목록에 없음 | `notIn` 과 동일 — **NULL 취약** |

> ⚠️ **서브쿼리 결과가 0행일 때 직관과 반대입니다.**
> `> all (빈 집합)` 은 **항상 참**, `> any (빈 집합)` 은 **항상 거짓**입니다.
> 조건 필터를 붙인 서브쿼리에 `all` 을 쓰면, 필터가 전부 걸러 낸 날 결과가 통째로 통과해 버립니다.
> `all` / `any` 를 쓸 때는 **서브쿼리가 0행이 될 수 있는지**를 반드시 확인하십시오.

> 💡 `.ne(sub.all())` 은 `notIn` 과 완전히 같으므로 **7-5 의 NULL 함정을 똑같이 갖습니다.**
> 부정형은 그냥 `notExists` 로 통일하십시오.

---

## 7-11. MySQL8 Step 08 대조표

같은 질문을 SQL 과 QueryDSL 로 나란히 놓습니다.

| 질문 | MySQL8 SQL | QueryDSL 6 |
|---|---|---|
| 평균보다 큰 값 | `WHERE points > (SELECT AVG(points) FROM customers)` | `.where(customer.points.gt(select(sub.points.avg()).from(sub)))` |
| 목록에 속함 | `WHERE id IN (SELECT customer_id FROM reviews)` | `.where(customer.id.in(select(review.customer.id).from(review)))` |
| 존재 | `WHERE EXISTS (SELECT 1 FROM reviews r WHERE r.customer_id = c.customer_id)` | `.where(selectOne().from(review).where(review.customer.eq(customer)).exists())` |
| 부재 | `WHERE NOT EXISTS (...)` | `.where(selectOne()....notExists())` |
| select 절 스칼라 | `SELECT c.name, (SELECT COUNT(*) FROM orders o WHERE ...)` | `ExpressionUtils.as(select(order.count())..., "orderCount")` |
| having 서브쿼리 | `HAVING AVG(total_amount) > (SELECT AVG(total_amount) FROM orders)` | `.having(order.totalAmount.avg().gt(select(o2.totalAmount.avg()).from(o2)))` |
| ALL / ANY | `price > ALL (SELECT ...)` | `.gt(sub.all())` |
| **FROM 절 (인라인 뷰)** | `FROM (SELECT ...) AS d` | **불가능** → 조인 / 2회 실행 / 네이티브 |
| **LATERAL** | `JOIN LATERAL (...) ON TRUE` | **불가능** → 네이티브 또는 QueryDSL-SQL |
| 행 서브쿼리 | `WHERE (a, b) = (SELECT a, b ...)` | **직접 지원 없음** → `and` 로 분해 |

### 별칭 규칙 대조

| | MySQL SQL | QueryDSL |
|---|---|---|
| 별칭 선언 | `FROM customers c, customers c2` | `QCustomer sub = new QCustomer("sub")` |
| 별칭 생략 | 가능 (테이블명 사용) | 기본 인스턴스(`QCustomer.customer`)가 이미 별칭 보유 |
| 같은 별칭 중복 | **에러** (`Not unique table/alias`) | **에러 없음** — 조용히 바깥을 가리킴 (7-2) |

**마지막 줄이 중요합니다.** SQL 은 같은 별칭을 두 번 쓰면 에러를 냅니다.
QueryDSL 은 에러를 내지 않고 **바깥 별칭을 그대로 참조**합니다. 이것이 7-2 의 0건 버그의 정체입니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| `JPAExpressions` | 서브쿼리 전용 정적 팩토리. `select` / `selectFrom` / `selectOne` |
| static import 관례 | `import static com.querydsl.jpa.JPAExpressions.select;` |
| **별칭 분리** | 바깥과 안쪽이 같은 엔티티면 `new QCustomer("sub")` **필수** |
| 같은 별칭 사용 | 에러 없음. `avg(c1_0.points)` 가 자기 자신을 가리켜 **조용히 0건** |
| 상관 서브쿼리 | 반대로 **같은 인스턴스**를 써야 바깥 행을 참조 |
| `in` 서브쿼리 | `customer.id.in(select(...).from(...))` |
| `exists` | `selectOne().from(x).where(...).exists()` → `BooleanExpression` |
| **`notIn` + NULL** | **조용히 0건.** → `notExists` 를 쓰자 |
| select 절 서브쿼리 | `ExpressionUtils.as(subQuery, "alias")`. **행 수만큼 반복 실행** |
| **from 절 서브쿼리** | **JPQL 상 불가능.** `from(EntityPath...)` 오버로드만 존재 → 컴파일 에러 |
| 우회 ① | 조인 + `groupBy` / 상관 서브쿼리로 재작성 — **먼저 시도할 것** |
| 우회 ② | 쿼리 2회 + 애플리케이션 조합 — 1단계 결과 크기 상한 확인 |
| 우회 ③ | 네이티브 쿼리 또는 **QueryDSL-SQL** (스키마 기반 Q타입, 코스 범위 밖) |
| Hibernate 6 HQL | from 절 서브쿼리 지원 시작. 단 **QueryDSL API 가 노출하지 않아 여전히 못 씀** |
| `exists` vs 조인 | 조인은 fan-out. `distinct` 는 임시 테이블 → `exists` 는 semijoin |
| `all` / `any` | `> all` = `> max`, `> any` = `> min`. **0행일 때 결과가 뒤집힘** |
| `having` 서브쿼리 | 가능. 집계는 Step 08 |

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. 전체 상품 평균가보다 비싼 상품을 조회하십시오. 별칭 분리를 정확히 하고, 생성 SQL 에서 `p1_0` / `p2_0` 이 각각 어디를 가리키는지 확인하십시오. (기대: 11건)
2. 후기를 한 번도 받지 못한 상품을 `notExists` 로 조회하십시오. (기대: 24건)
3. 문제 2 를 `notIn` 으로 다시 푸십시오. `reviews.product_id` 가 NOT NULL 이라 지금은 동작하지만, **스키마가 바뀌어도 안전하도록** 방어적으로 작성하십시오.
4. 결제(`Payment`)가 하나도 없는 주문을 `notExists` 와 `leftJoin + isNull` 두 방법으로 각각 조회하고, 두 결과 건수가 같은지 확인하십시오. (기대: 60건)
5. 고객 목록과 **각 고객이 쓴 후기 수**를 select 절 상관 서브쿼리로 조회하십시오. 그다음 같은 결과를 `leftJoin + groupBy` 로 다시 작성하고, 생성 SQL 두 개를 비교하십시오.
6. **자기가 속한 카테고리의 평균가보다 비싼 상품**을 조회하십시오. 상관 서브쿼리를 써야 하며, 여기서는 별칭을 **분리해야 하는지 아닌지** 스스로 판단하십시오.
7. "카테고리별 최고가 상품" 을 7-7 의 우회 ① 과 ② 두 방식으로 각각 구현하고, 결과가 같은지 · 나가는 쿼리 수가 몇 개인지 비교하십시오. (기대: 12건)

---

## 다음 단계

서브쿼리를 다루면서 `count()`, `avg()`, `max()`, `groupBy`, `having` 이 계속 얼굴을 내밀었습니다.
7-6 에서는 select 절 서브쿼리를 `groupBy` 조인으로 바꾸는 게 낫다고 했고,
7-10 의 `having` 서브쿼리는 집계 없이는 성립하지 않습니다.
이제 그 집계를 정면으로 다룰 차례입니다.

다음 스텝에서는 집계 결과를 `Tuple` 로 꺼낼 때의 타입 함정, `count(컬럼)` 이 NULL 을 세지 않는 문제,
`sum()` 이 0건일 때 `null` 을 돌려주는 문제, 그리고 **DB 의 `group by` 가 아니라 애플리케이션 메모리에서
그룹핑하는 `transform()`** 을 다룹니다.

→ [Step 08 — 집계와 그룹핑](../step-08-aggregation/)

---

## 실습 파일

이 스텝은 자바 파일 세 개로 구성됩니다. 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이므로,
프로젝트의 `src/test/java/com/example/shop/step07/` 에 그대로 복사해 넣고 실행하면 됩니다.
`Practice.java` 로 7-1 ~ 7-11 절의 예제를 눈으로 확인하고, `Exercise.java` 의 7문제를 직접 풀어 본 뒤,
`Solution.java` 로 답과 해설을 맞춰 보는 순서입니다.
세 파일 모두 **조회 계열만 사용하므로 데이터를 변경하지 않습니다.**

실행할 때 `application.yml` 의 SQL 로그 설정이 켜져 있는지 반드시 확인하십시오.
이 스텝은 **생성 SQL 을 보지 않으면 배울 것이 절반으로 줄어듭니다.**

```yaml
logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

### Practice.java

본문 7-1 ~ 7-11 절의 모든 예제를 `// [7-2] 절 제목` 주석과 함께 담은 테스트 클래스입니다.
테스트 메서드 하나가 절 하나에 대응하므로, 본문을 읽으며 해당 메서드만 골라 실행해도 됩니다.

- `aliasCollision()` 은 **의도적으로 틀린 코드**입니다. 0건이 나오는 것이 정상이며,
  바로 아래 `aliasSeparated()` 와 생성 SQL 을 비교하는 것이 목적입니다.
  `where c1_0.points > (select avg(c1_0.points) ...)` 에서 별칭이 둘 다 `c1_0` 인 것을 눈으로 확인하십시오.
- `notInNullTrap()` 이 이 스텝의 핵심입니다. `0` 이 나오는 것을 직접 본 다음
  `managerIdsIncludingNull()` 로 서브쿼리 결과에 `null` 이 섞여 있음을 확인하고,
  `fixWithIsNotNull()` / `fixWithNotExists()` / `fixWithAntiJoin()` 세 처방이 모두 10을 내는 것을 봅니다.
- `fromClauseSubqueryIsImpossible()` 은 **주석 처리된 실패 예제**를 담고 있습니다.
  QueryDSL 쪽은 주석을 풀면 **컴파일 자체가 안 되고**, 문자열 JPQL 쪽은 컴파일은 되지만
  실행 시 `SemanticException` 으로 죽습니다. 둘의 차이를 확인하는 것이 목적입니다.
- `selectSubqueryCost()` 와 `groupByJoinEquivalent()` 는 같은 결과를 내는 두 쿼리입니다.
  결과는 같고 **생성 SQL 이 완전히 다릅니다.** 반드시 나란히 놓고 보십시오.

```java file="./Practice.java"
```

### Exercise.java

「연습문제」 절의 7문제를 담은 빈칸 파일입니다.
각 문항이 `// 문제 N.` 주석 블록으로 되어 있고 `// 여기에 작성:` 아래가 비어 있습니다.

- 문항마다 **기대 결과 건수가 명시**되어 있습니다(예: 문제 1 은 11건, 문제 2 는 24건).
  건수가 맞는다고 끝이 아니라, **생성 SQL 이 의도한 모양인지**까지 확인해야 정답입니다.
- 문제 3 은 문제 2 와 같은 질문을 `notIn` 으로 푸는 것입니다.
  현재 스키마에서는 `isNotNull()` 없이도 답이 맞지만, **방어적으로 붙이는 습관**을 들이는 것이 목적입니다.
- 문제 6 은 별칭 분리 여부를 스스로 판단해야 합니다. 힌트는 7-4 의 표에 있습니다.
- 문제 7 은 우회 ① 과 ② 를 모두 구현하는 문제입니다. **나가는 쿼리 수**를 로그로 세어 보십시오.

```java file="./Exercise.java"
```

### Solution.java

`Exercise.java` 의 정답과 해설입니다. 답만 있는 게 아니라 **왜 그렇게 푸는지**가 주석으로 붙어 있으므로,
자신의 답과 건수가 같더라도 주석을 반드시 읽으십시오.

- 각 답 아래에 생성 SQL 이 주석으로 붙어 있습니다. 자기 콘솔의 로그와 한 글자씩 대조하십시오.
- 문제 3 의 주석이 중요합니다. 지금 스키마에선 `reviews.product_id` 가 `NOT NULL` 이라
  `isNotNull()` 이 없어도 되지만, **스키마는 바뀝니다.** 근본적으로는 문제 2 처럼 `notExists` 가 낫습니다.
- 문제 6 의 답에서 **별칭을 분리한 이유**를 설명합니다. 상관 서브쿼리인데도 분리가 필요한 이유는
  "바깥 행을 참조하는 경로(`product.category`)" 와 "집계 대상(`sub.price`)" 이 서로 다른 역할이기 때문입니다.
- 문제 7 의 주석에서 두 우회의 트레이드오프를 정리합니다.
  ① 은 쿼리 1개 + DB 내부 반복 12회, ② 는 쿼리 2개 + 네트워크 왕복 2회입니다.
  **어느 쪽이 나은지는 그룹 수와 네트워크 지연에 따라 달라집니다.**

```java file="./Solution.java"
```
