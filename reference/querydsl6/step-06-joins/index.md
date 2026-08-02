# Step 06 — 조인

> **학습 목표**
> - 연관 기반 조인과 세타 조인을 구분하고 각각의 생성 SQL 을 읽는다
> - `join` / `leftJoin` / `on` 을 MySQL 의 `JOIN` / `LEFT JOIN` / `ON` 과 1:1 로 대응시킨다
> - 1:N 조인의 **fan-out** 이 집계를 망가뜨리는 것을 숫자로 재현하고 방어한다
> - **LEFT JOIN 의 필터를 `where` 에 두면 INNER JOIN 으로 퇴화하는 것**을 건수로 확인한다
> - `fetchJoin()` 으로 N+1 이 사라지는 것을 쿼리 개수로 증명한다
> - **컬렉션 fetch join 에 페이징을 붙이면 전건을 메모리로 읽는다**는 것을 경고 로그와 생성 SQL 로 확인하고 세 가지 방법으로 고친다
> - `MultipleBagFetchException` 의 원인과 처방을 안다
>
> **선행 스텝**: [Step 05 — 프로젝션과 DTO](../step-05-projections/)
> **예상 소요**: 120분

> 📌 **이 스텝은 MySQL8 코스 [Step 07 — 조인](../../mysql8/step-07-joins/) 을 QueryDSL 로 다시 쓰는 스텝입니다.**
> 같은 스키마, 같은 데이터, 같은 결과입니다. 절마다 대응되는 SQL 을 나란히 놓았습니다.
> SQL 쪽을 먼저 읽고 오면 절반은 이미 아는 내용입니다.

그리고 절반은 **JPA 에만 있는 이야기**입니다.
`fetchJoin()`, `MultipleBagFetchException`, "컬렉션 fetch join + 페이징" 은 SQL 에 없는 개념입니다.
SQL 을 아무리 잘 알아도 이건 따로 배워야 합니다. 6-7 절부터가 그 부분입니다.

본문은 아래 static import 를 전제합니다.

```java
import static com.example.shop.entity.QCustomer.customer;
import static com.example.shop.entity.QOrder.order;
import static com.example.shop.entity.QOrderItem.orderItem;
import static com.example.shop.entity.QProduct.product;
import static com.example.shop.entity.QCategory.category;
import static com.example.shop.entity.QPayment.payment;
import static com.example.shop.entity.QReview.review;
import static com.example.shop.entity.QEmployee.employee;
```

---

## 6-1. 연관 기반 조인 vs 세타 조인

QueryDSL 에는 조인을 쓰는 방법이 두 가지 있습니다.

### 연관 기반 조인 — 엔티티의 관계를 타고 간다

```java
List<Tuple> result = queryFactory
        .select(order.id, customer.name)
        .from(order)
        .join(order.customer, customer)     // ← 연관 경로 + 별칭
        .limit(3)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, c1_0.name
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
limit ?
```
```
바인딩: [1] 3
1 / 류하나
2 / 정  훈
3 / 안지수
```

`join(order.customer, customer)` 의 두 인자를 정확히 구분하십시오.

- 첫 번째 `order.customer` — **연관 경로**. "orders 에서 customer 로 가는 관계" 입니다.
  `@ManyToOne` 매핑에 조인 조건(`customer_id = customer_id`)이 이미 적혀 있으므로 `on` 이 필요 없습니다.
- 두 번째 `customer` — **별칭**. 조인해 온 대상을 앞으로 이 이름으로 부르겠다는 선언입니다.
  이걸 줘야 `select` 나 `where` 에서 `customer.name` 을 쓸 수 있습니다.

별칭을 빼고 `join(order.customer)` 만 쓰면 조인은 되지만 그 대상을 참조할 방법이 없어집니다.

### 세타 조인 — 관계 없이 곱집합에서 걸러낸다

```java
List<Tuple> result = queryFactory
        .select(order.id, customer.name)
        .from(order, customer)                     // ← from 에 둘을 나열
        .where(order.customer.eq(customer))        // ← 조인 조건을 where 로
        .limit(3)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, c1_0.name
from orders o1_0,
     customers c1_0
where o1_0.customer_id = c1_0.customer_id
limit ?
```
```
바인딩: [1] 3
1 / 류하나
2 / 정  훈
3 / 안지수
```

결과는 같지만 SQL 이 다릅니다. `from a, b where 조건` — MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) 의
`7-7` 절에서 "콤마 조인 대신 명시적 `JOIN ... ON` 을 쓰라" 고 경고했던 그 형태입니다.

| | 연관 기반 조인 | 세타 조인 |
|---|---|---|
| 문법 | `.join(order.customer, customer)` | `.from(order, customer).where(...)` |
| 생성 SQL | `join ... on ...` | `from a, b where ...` |
| 연관 매핑 | **필요** | 불필요 |
| 외부 조인 | `leftJoin` 가능 | **불가능** (`on` 으로 우회 — 6-6 절) |
| 조건 누락 시 | 문법상 불가능 | **곱집합 폭발** |

> ⚠️ **함정 — 세타 조인에서 `where` 를 빠뜨리면 곱집합입니다**
> `.from(order, customer)` 만 쓰고 `where(order.customer.eq(customer))` 를 안 쓰면
> 주문 600 × 고객 30 = **18,000 행**이 나옵니다. 예외는 나지 않습니다.
> 우리 데이터는 작아서 그냥 느릴 뿐이지만, 주문 100만 건이면 서버가 멈춥니다.
> **연관 매핑이 있으면 언제나 연관 기반 조인을 쓰십시오.**
> 세타 조인은 연관이 없는 엔티티를 이어야 할 때만(6-6 절) 쓰는 마지막 수단입니다.

---

## 6-2. `join` / `innerJoin` / `leftJoin` / `rightJoin`

MySQL8 코스 [Step 07 — 조인](../../mysql8/step-07-joins/) 의 `7-1` 절 첫 예제입니다.
"주문에 고객 이름을 붙인다."

**SQL (MySQL8 코스 7-1)**
```sql
SELECT o.order_id, o.order_date, c.name, c.grade, o.total_amount
FROM orders o
INNER JOIN customers c ON c.customer_id = o.customer_id
ORDER BY o.order_id
LIMIT 5;
```

**QueryDSL**
```java
List<Tuple> result = queryFactory
        .select(order.id, order.orderDate, customer.name,
                customer.grade, order.totalAmount)
        .from(order)
        .join(order.customer, customer)
        .orderBy(order.id.asc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.order_date, c1_0.name, c1_0.grade, o1_0.total_amount
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
order by o1_0.order_id asc
limit ?
```
```
바인딩: [1] 5
1 | 2024-02-07T13:07 | 류하나 | GOLD   | 1836000.00
2 | 2024-03-15T02:14 | 정  훈 | GOLD   | 6663900.00
3 | 2024-04-21T15:21 | 안지수 | GOLD   |  658000.00
4 | 2024-05-28T04:28 | 한지호 | BRONZE |  837000.00
5 | 2024-07-04T17:35 | 배채영 | GOLD   | 1194000.00
```

MySQL8 코스의 7-1 결과와 **완전히 같습니다.** 같은 데이터니까요.

네 가지 조인 메서드는 SQL 과 1:1 로 대응합니다.

| QueryDSL | 생성 SQL | 의미 |
|---|---|---|
| `.join(a, b)` | `join ... on ...` | INNER JOIN |
| `.innerJoin(a, b)` | `join ... on ...` | `join` 과 **완전히 동일** |
| `.leftJoin(a, b)` | `left join ... on ...` | 왼쪽 전부 보존 |
| `.rightJoin(a, b)` | `right join ... on ...` | 오른쪽 전부 보존 |

`join` 과 `innerJoin` 은 이름만 다른 같은 메서드입니다. SQL 에서 `INNER` 를 생략할 수 있는 것과 같습니다.

`leftJoin` 으로 바꾸면 어떻게 될까요? "상품이 없는 카테고리도 보고 싶다" 는 요구입니다.
우리 카테고리 17개 중 대분류 5개에는 상품이 직접 매달려 있지 않습니다.

**SQL (MySQL8 코스 7-3)**
```sql
SELECT cat.category_id, cat.name, p.product_id, p.name
FROM categories cat
LEFT JOIN products p ON p.category_id = cat.category_id
WHERE cat.parent_id IS NULL
ORDER BY cat.category_id;
```

**QueryDSL**
```java
List<Tuple> result = queryFactory
        .select(category.id, category.name, product.id, product.name)
        .from(category)
        .leftJoin(category.products, product)
        .where(category.parent.isNull())
        .orderBy(category.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.category_id, c1_0.name, p1_0.product_id, p1_0.name
from categories c1_0
left join products p1_0 on c1_0.category_id = p1_0.category_id
where c1_0.parent_id is null
order by c1_0.category_id asc
```
```
조회 5건
1 | 패션   | null | null
2 | 디지털 | null | null
3 | 식품   | null | null
4 | 리빙   | null | null
5 | 도서   | null | null
```

카테고리는 남았고 상품 자리는 NULL 로 채워졌습니다(NULL 확장).
`join` 이었다면 이 5줄이 통째로 사라집니다. 직접 바꿔서 확인해 보십시오.

> 💡 **실무 팁 — `rightJoin` 은 거의 쓰지 마십시오**
> MySQL8 코스 `7-9` 절에서 한 이야기가 QueryDSL 에도 그대로 적용됩니다.
> 사람은 왼쪽에서 오른쪽으로 읽습니다. "전부 남길 대상을 `from` 에 놓고 `leftJoin`" 하는 흐름이
> 훨씬 읽기 쉽습니다. `rightJoin` 을 보면 대개 `leftJoin` 으로 뒤집을 후보입니다.

---

## 6-3. 다중 조인 — 5개 테이블

MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) 의 `7-2` 절입니다.
"어떤 고객이 어떤 상품을 어느 카테고리에서 몇 개 샀나."

**SQL (MySQL8 코스 7-2)**
```sql
SELECT o.order_id, c.name, p.name, cat.name, oi.quantity, oi.unit_price
FROM orders o
JOIN customers c    ON c.customer_id   = o.customer_id
JOIN order_items oi ON oi.order_id     = o.order_id
JOIN products p     ON p.product_id    = oi.product_id
JOIN categories cat ON cat.category_id = p.category_id
ORDER BY o.order_id, p.product_id
LIMIT 8;
```

**QueryDSL**
```java
List<Tuple> result = queryFactory
        .select(order.id, customer.name, product.name,
                category.name, orderItem.quantity, orderItem.unitPrice)
        .from(order)
        .join(order.customer, customer)
        .join(order.orderItems, orderItem)
        .join(orderItem.product, product)
        .join(product.category, category)
        .orderBy(order.id.asc(), product.id.asc())
        .limit(8)
        .fetch();
```

**결과**
```sql
select o1_0.order_id, c1_0.name, p1_0.name, c2_0.name,
       oi1_0.quantity, oi1_0.unit_price
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
join products p1_0 on p1_0.product_id = oi1_0.product_id
join categories c2_0 on c2_0.category_id = p1_0.category_id
order by o1_0.order_id asc, p1_0.product_id asc
limit ?
```
```
바인딩: [1] 8
1 | 류하나 | 27인치 4K 모니터      | 주변기기 | 3 |  459000.00
1 | 류하나 | 원목 4인 식탁         | 가구     | 1 |  459000.00
2 | 정  훈 | 베이직 옥스퍼드 셔츠  | 남성의류 | 2 |   39000.00
2 | 정  훈 | 게이밍 노트북 RTX4060 | 노트북   | 3 | 2190000.00
2 | 정  훈 | 콜드브루 원액 1L      | 가공식품 | 1 |   15900.00
3 | 안지수 | 인체공학 사무용 의자  | 가구     | 2 |  329000.00
4 | 한지호 | 슬림핏 치노 팬츠      | 남성의류 | 3 |   49000.00
4 | 한지호 | 보급형 노트북 15      | 노트북   | 1 |  690000.00
```

MySQL8 코스 7-2 의 결과와 한 줄도 다르지 않습니다.

별칭 규칙을 눈여겨 보십시오. `customers` 와 `categories` 가 둘 다 `c` 로 시작해서
Hibernate 가 **`c1_0`, `c2_0`** 으로 번호를 매겼습니다.
어느 쪽이 무엇인지는 `from`/`join` 등장 순서로 결정됩니다.
로그를 읽을 때 헷갈리기 쉬운 지점이니 `on` 절의 컬럼명으로 확인하는 습관을 들이십시오.

**`order_id = 1` 이 두 줄인 것에 다시 주목하십시오.** 주문 1건에 상품이 2개(1:N)입니다.
주문 헤더 정보(고객명)가 상품 수만큼 반복됩니다. 다음 절의 사고가 여기서 시작됩니다.

---

## 6-4. ⚠️ fan-out — 1:N 조인이 집계를 망가뜨린다

김민수(고객 1번)의 주문 총액을 구해 봅시다. 주문만 조인하면 정상입니다.

```java
Tuple correct = queryFactory
        .select(order.count(), order.totalAmount.sum())
        .from(order)
        .join(order.customer, customer)
        .where(customer.id.eq(1L))
        .fetchOne();
```

**결과**
```sql
select count(o1_0.order_id), sum(o1_0.total_amount)
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where c1_0.customer_id = ?
```
```
바인딩: [1] 1
주문 수 = 20, 총액 = 24300000.00
```

이제 "상품명도 같이 보고 싶어서" `orderItems` 조인을 하나 추가합니다.
집계식은 **한 글자도 바꾸지 않았습니다.**

```java
Tuple wrong = queryFactory
        .select(order.count(), order.totalAmount.sum())
        .from(order)
        .join(order.customer, customer)
        .join(order.orderItems, orderItem)          // ← 이 한 줄만 추가
        .where(customer.id.eq(1L))
        .fetchOne();
```

**결과**
```sql
select count(o1_0.order_id), sum(o1_0.total_amount)
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
where c1_0.customer_id = ?
```
```
바인딩: [1] 1
주문 수 = 41, 총액 = 49860000.00
```

| | 주문 수 | 총액 |
|---|---:|---:|
| `orderItems` 조인 없음 (정답) | **20** | **24,300,000** |
| `orderItems` 조인 추가 | 41 | 49,860,000 |
| 차이 | +21 | **+25,560,000** |

**주문이 20건에서 41건으로 늘었고 총액이 2배가 됐습니다.**
예외는 없습니다. 경고도 없습니다. 매출 리포트에 2배 숫자가 찍힐 뿐입니다.

이유는 6-3 절에서 본 것과 같습니다. `orders : order_items` 는 1:N 이라,
주문 1건에 상품이 2개면 그 주문 행이 **2번 복제**됩니다.
김민수의 주문 20건에 딸린 `order_items` 가 41건이니 조인 결과는 41행이고,
각 주문의 `total_amount` 가 그 주문의 상품 개수만큼 반복해서 더해집니다.

> ⚠️ **함정 — fan-out(행 뻥튀기)**
> 조인을 하나 추가하는 것은 "컬럼을 추가하는 일" 이 아니라 **"행의 단위를 바꾸는 일"** 입니다.
> `orderItems` 를 조인한 순간 한 행의 의미가 "주문 하나" 에서 "주문 상품 한 줄" 로 바뀌었습니다.
> 집계식은 그대로인데 집계 대상이 바뀐 것입니다.
>
> **집계 전에 언제나 자문하십시오 — "지금 한 행은 무엇의 단위인가?"**

### 방어 1 — `countDistinct`

```java
Tuple result = queryFactory
        .select(order.countDistinct(), order.totalAmount.sum())
        .from(order)
        .join(order.customer, customer)
        .join(order.orderItems, orderItem)
        .where(customer.id.eq(1L))
        .fetchOne();
```

**결과**
```sql
select count(distinct o1_0.order_id), sum(o1_0.total_amount)
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
where c1_0.customer_id = ?
```
```
주문 수 = 20  ← 고쳐짐
총액 = 49860000.00  ← 여전히 틀림
```

**`count` 는 고쳐졌지만 `sum` 은 그대로입니다.**
`countDistinct` 는 "서로 다른 order_id 의 개수" 를 세니까 맞습니다.
그런데 `sum` 에 `distinct` 를 붙이면 **다른 주문인데 금액이 우연히 같으면 한 번만 더합니다** —
더 큰 사고가 납니다. MySQL8 코스 `7-11` 절의 경고와 같은 이야기입니다.

### 방어 2 — 집계는 fan-out 없는 쿼리로 분리

```java
// 금액 합계는 orderItems 를 조인하지 않은 쿼리로 따로 구한다
BigDecimal total = queryFactory
        .select(order.totalAmount.sum())
        .from(order)
        .where(order.customer.id.eq(1L))
        .fetchOne();
```

**결과**
```sql
select sum(o1_0.total_amount)
from orders o1_0
where o1_0.customer_id = ?
```
```
총액 = 24300000.00  ← 정답
```

### 방어 3 — 합계를 line 단위로 다시 정의

`total_amount` 를 더하는 대신 `quantity × unit_price` 를 더하면 행 단위와 집계 단위가 일치합니다.

```java
BigDecimal lineTotal = queryFactory
        .select(orderItem.unitPrice.multiply(orderItem.quantity).sum())
        .from(order)
        .join(order.orderItems, orderItem)
        .where(order.customer.id.eq(1L))
        .fetchOne();
```

**결과**
```sql
select sum(oi1_0.unit_price * oi1_0.quantity)
from orders o1_0
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
where o1_0.customer_id = ?
```
```
총액 = 24300000.00  ← 정답
```

세 방법 중 **방어 3이 가장 안전합니다.** 집계식의 단위가 행의 단위와 같으면 fan-out 자체가 문제가 되지 않습니다.

---

## 6-5. ⚠️ `on` vs `where` — 이 스텝의 SQL 쪽 핵심

MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) 의 `7-4` 절, 실무에서 가장 많이 틀리는 조인 함정입니다.
QueryDSL 로도 똑같이 틀립니다.

"모든 카테고리와 그 카테고리의 **고가 상품(100만원 이상)**" 을 보려고 합니다.
카테고리는 17개고, 100만원 이상 상품은 6개인데 서로 다른 6개 카테고리에 흩어져 있습니다.

### (A) 조건을 `on` 에

```java
List<Tuple> onVersion = queryFactory
        .select(category.name, product.name, product.price)
        .from(category)
        .leftJoin(category.products, product)
        .on(product.price.goe(new BigDecimal("1000000")))    // ← on
        .orderBy(category.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.name, p1_0.name, p1_0.price
from categories c1_0
left join products p1_0
       on c1_0.category_id = p1_0.category_id
      and p1_0.price >= ?
order by c1_0.category_id asc
```
```
바인딩: [1] 1000000
조회 17건

패션     | null                  | null
디지털   | null                  | null
식품     | null                  | null
리빙     | null                  | null
도서     | null                  | null
남성의류 | null                  | null
여성의류 | null                  | null
노트북   | 게이밍 노트북 RTX4060 | 2190000.00
...
```

**17건.** 조건에 맞는 상품 6건 + 조건에 맞는 상품이 없는 카테고리 11건(NULL 확장) = 17.

### (B) 조건을 `where` 에

```java
List<Tuple> whereVersion = queryFactory
        .select(category.name, product.name, product.price)
        .from(category)
        .leftJoin(category.products, product)
        .where(product.price.goe(new BigDecimal("1000000")))  // ← where
        .orderBy(category.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.name, p1_0.name, p1_0.price
from categories c1_0
left join products p1_0 on c1_0.category_id = p1_0.category_id
where p1_0.price >= ?
order by c1_0.category_id asc
```
```
바인딩: [1] 1000000
조회 6건

노트북   | 게이밍 노트북 RTX4060 | 2190000.00
노트북   | 크리에이터 노트북 16  | 2450000.00
...
```

**6건.** LEFT JOIN 을 써 놓고 INNER JOIN 결과를 얻었습니다.

| | 조건 위치 | 생성 SQL | 결과 |
|---|---|---|---|
| (A) | `on` | `left join ... on 조인조건 and 필터` | **17건** (카테고리 전부 보존) |
| (B) | `where` | `left join ... on 조인조건` + `where 필터` | **6건** (INNER JOIN 과 동일) |

**11건이 사라진 이유**는 NULL 확장입니다.
`where` 는 조인이 **끝난 뒤** 적용됩니다. NULL 확장된 행의 `p1_0.price` 는 NULL 이고,
`NULL >= 1000000` 은 참도 거짓도 아닌 UNKNOWN 이라 `where` 를 통과하지 못하고 탈락합니다.

> ⚠️ **핵심 규칙 — LEFT JOIN 에서 오른쪽 조건은 `on` 에, 왼쪽 조건은 `where` 에**
> - "모든 카테고리 + 그들의 고가 상품(없으면 NULL)" 을 원하면 → **`on`**
> - "고가 상품이 있는 카테고리만" 을 원하면 → **`where`** (또는 그냥 `join`)
>
> 전형적인 실수는 `leftJoin` 을 써 놓고 오른쪽 테이블 조건을 `where` 에 걸어서,
> 자기도 모르게 INNER JOIN 을 만들어 놓고 "왜 LEFT JOIN 인데 행이 안 남지?" 라고 헤매는 것입니다.
>
> **QueryDSL 에서 특히 위험한 이유가 하나 더 있습니다.**
> `.on(...)` 과 `.where(...)` 는 **둘 다 `BooleanExpression` 을 받습니다.**
> 조건을 변수로 빼서 `BooleanExpression cond = product.price.goe(...)` 로 만들어 두면
> `.on(cond)` 도 `.where(cond)` 도 컴파일됩니다.
> 리팩터링하다가 조건을 옮기는 순간 결과가 바뀌는데 컴파일러는 아무 말도 하지 않습니다.

예외가 하나 있습니다. **`where` 에 `isNull()` 을 두는 것은 정상**입니다.
NULL 확장을 일부러 노리는 안티 조인이기 때문입니다 (6-12 절).

---

## 6-6. 연관 없는 엔티티의 `on` 조인

여기까지는 전부 **연관 매핑이 있는** 엔티티끼리의 조인이었습니다.
연관이 없는 두 엔티티를 이으려면 `join(엔티티)` + `on(조건)` 형태를 씁니다.

억지스럽지만 명확한 예로, "상품명과 카테고리명이 같은 경우" 를 이어 보겠습니다.

```java
List<Tuple> result = queryFactory
        .select(category.name, product.name)
        .from(category)
        .leftJoin(product).on(product.name.eq(category.name))   // ← 연관 경로 없음
        .orderBy(category.id.asc())
        .limit(5)
        .fetch();
```

**결과**
```sql
select c1_0.name, p1_0.name
from categories c1_0
left join products p1_0 on p1_0.name = c1_0.name
order by c1_0.category_id asc
limit ?
```
```
바인딩: [1] 5
패션   | null
디지털 | null
식품   | null
리빙   | null
도서   | null
```

`leftJoin(product)` — 인자가 **하나**입니다. 연관 경로가 아니라 엔티티 그 자체입니다.
그래서 조인 조건이 없고, `on(...)` 으로 직접 줘야 합니다.

생성 SQL 의 `on` 절을 보십시오. `on p1_0.name = c1_0.name` 뿐입니다.
연관 기반 조인이었다면 `on c1_0.category_id = p1_0.category_id and ...` 처럼
매핑에서 온 조건이 먼저 붙었을 텐데, 여기엔 그게 없습니다.

실무에서 쓰이는 예는 이런 것들입니다.

```java
// 코드 테이블처럼 FK 없이 값으로만 이어진 경우
.leftJoin(codeItem).on(codeItem.groupCode.eq("ORDER_STATUS")
                 .and(codeItem.code.eq(order.status.stringValue())))
```

> 💡 **실무 팁 — 연관이 없다고 세타 조인으로 도망가지 마십시오**
> `.from(category, product).where(product.name.eq(category.name))` 로도 같은 결과를 얻습니다.
> 하지만 세타 조인은 **외부 조인을 못 합니다.** 위 예제의 `leftJoin` 을 세타 조인으로는 표현할 수 없습니다.
> `join(엔티티).on(조건)` 은 세타 조인의 상위 호환입니다. 이쪽을 쓰십시오.

---

## 6-7. fetch join — N+1 을 없애는 JPA 고유 기능

여기서부터는 **SQL 에 없는 이야기**입니다.

주문 10건을 읽고 각 주문의 고객 이름을 출력해 봅시다.
`@ManyToOne` 은 `LAZY` 이므로 `order.getCustomer()` 는 프록시입니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getId() + " / " + o.getCustomer().getName());   // ← 여기서 프록시 초기화
}
```

**결과** — 쿼리가 **11개** 나갑니다
```sql
-- 1번째: 주문 목록
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount
from orders o1_0
limit ?

-- 2번째: 1번 주문의 고객
select c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from customers c1_0 where c1_0.customer_id = ?
바인딩: [1] 18

-- 3번째: 2번 주문의 고객
select c1_0.customer_id, ... from customers c1_0 where c1_0.customer_id = ?
바인딩: [1] 5

-- ... 11번째까지 반복
```
```
총 쿼리 수 = 11 (1 + 10)
```

**이것이 N+1 입니다.** 결과는 완벽하게 맞습니다. 느릴 뿐입니다.
그리고 개발 환경에서 10건일 때는 아무도 눈치채지 못합니다.

`fetchJoin()` 을 붙이면 이렇게 됩니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.customer, customer).fetchJoin()     // ← .fetchJoin()
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getId() + " / " + o.getCustomer().getName());
}
```

**결과** — 쿼리가 **1개**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount,
       c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
limit ?
```
```
바인딩: [1] 10
총 쿼리 수 = 1
```

| | 쿼리 수 | select 컬럼 |
|---|---:|---|
| 일반 `join` | **11** (1 + 10) | 주문 컬럼만 |
| `join` + `fetchJoin()` | **1** | 주문 + 고객 컬럼 전부 |

`join` 과 `fetchJoin()` 의 차이를 정확히 이해하십시오.

- **일반 `join`**: 조인은 하지만 **select 에는 왼쪽 엔티티만** 담습니다.
  고객 정보는 나중에 프록시가 초기화될 때 따로 읽습니다.
- **`fetchJoin()`**: 조인한 엔티티의 컬럼까지 **한 번에 select 해서 영속성 컨텍스트에 채웁니다.**
  프록시가 아니라 실제 객체가 들어 있으니 추가 쿼리가 없습니다.

`fetchJoin()` 의 중요한 제약이 하나 있습니다.
**fetch join 대상에는 `on` 을 걸 수 없습니다.**

```java
.leftJoin(order.customer, customer).on(customer.grade.eq(Grade.VIP)).fetchJoin()
```

이건 논리적으로 위험합니다. 연관 컬렉션의 일부만 로딩해서 영속성 컨텍스트에 넣으면,
같은 트랜잭션 안에서 그 컬렉션을 다시 읽는 코드가 "잘려 있는 컬렉션" 을 보게 됩니다.
JPA 표준은 이를 금지하고, Hibernate 는 버전에 따라 예외를 내거나 경고합니다.
**fetch join 은 "연관 전체를 통째로 가져오는 것" 이라고 이해하십시오.**

> 💡 **실무 팁 — DTO 프로젝션이면 fetch join 이 필요 없습니다**
> [Step 05](../step-05-projections/) 에서 배운 대로 DTO 로 받으면 애초에 프록시가 없습니다.
> N+1 도 없습니다. **조회 전용이면 DTO 프로젝션이 먼저이고, fetch join 은
> "엔티티가 꼭 필요할 때" 의 도구입니다.** 순서를 거꾸로 잡는 경우가 많습니다.

---

## 6-8. ⚠️ 컬렉션 fetch join + 페이징 = 메모리에서 페이징

**이 스텝, 어쩌면 이 코스 전체에서 가장 중요한 함정입니다.**

주문 목록 페이지를 만든다고 합시다. 주문마다 상품 목록도 같이 보여줘야 하니
`orderItems` 를 fetch join 하고, 페이징을 위해 `offset` / `limit` 을 붙입니다.
아주 자연스러운 코드입니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.orderItems, orderItem).fetchJoin()    // ← 컬렉션 fetch join
        .offset(0)
        .limit(10)
        .fetch();

System.out.println("결과 = " + orders.size() + "건");
```

**컴파일**: 성공. **실행**: 성공. **결과**: 10건. 화면도 정상입니다.

그런데 로그를 보십시오.

**결과**
```
WARN 12345 --- [    Test worker] org.hibernate.orm.query :
    HHH90003004: firstResult/maxResults specified with collection fetch;
    applying in memory
```
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount,
       oi1_0.order_id, oi1_0.order_item_id, oi1_0.product_id,
       oi1_0.quantity, oi1_0.unit_price
from orders o1_0
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
```
```
결과 = 10건
```

**생성 SQL 에 `limit` 이 없습니다.**

`offset(0).limit(10)` 을 분명히 썼는데 SQL 에는 `limit` 절이 통째로 사라졌습니다.
Hibernate 는 **`order_items` 1,200행 × 조인된 주문 정보를 전부 읽어서 힙에 올린 뒤**,
자바 메모리에서 중복을 제거하고 앞의 10건을 잘라 돌려줬습니다.

### 왜 SQL 로 페이징할 수 없나

1:N 조인을 하면 행이 뻥튀기됩니다(6-4 절). 주문 600건에 상품 1,200건이니 조인 결과는 1,200행입니다.
여기에 `limit 10` 을 걸면 **"주문 10건" 이 아니라 "조인 행 10개"** 가 잘립니다.
주문 1번에 상품이 2개, 2번에 3개... 라면 10행은 주문 4~5건 정도밖에 안 됩니다.
게다가 마지막 주문은 상품이 잘린 채로 들어옵니다 — **불완전한 엔티티**입니다.

Hibernate 는 그 결과를 돌려줄 수 없으니, 페이징을 포기하고 전건을 읽어 메모리에서 자릅니다.
**결과의 정확성을 지키기 위해 성능을 버린 것입니다.**

### 경고 코드는 Hibernate 버전에 따라 다릅니다

| Hibernate | 로거 | 코드 | 메시지 |
|---|---|---|---|
| **6.x** (이 코스) | `org.hibernate.orm.query` | `HHH90003004` | `firstResult/maxResults specified with collection fetch; applying in memory` |
| 5.x | `org.hibernate.hql.internal.ast.QueryTranslatorImpl` | `HHH000104` | `firstResult/maxResults specified with collection fetch; applying in memory!` |

메시지는 거의 같지만 **코드와 로거 이름이 다릅니다.**
5.x 시절 자료를 보고 `HHH000104` 로 로그를 검색하면 Hibernate 6 환경에서는 아무것도 안 나옵니다.
운영 로그 알람을 걸 때는 **양쪽 코드를 모두 등록**하거나, 메시지 본문으로 잡으십시오.

> ⚠️ **함정 — 이 경고를 못 보고 지나가기가 너무 쉽습니다**
> - `WARN` 레벨입니다. 애플리케이션은 정상 동작합니다.
> - 결과는 **정확합니다.** 10건 달라면 10건 줍니다. 테스트도 통과합니다.
> - 개발 DB 에 주문이 600건이면 체감 차이가 없습니다.
> - 운영에 나가서 주문이 60만 건이 되면 그때 `OutOfMemoryError` 가 납니다.
>
> **OOM 시나리오**: 주문 60만 건 × 상품 평균 2건 = 조인 행 120만 개.
> 엔티티 하나당 대략 수백 바이트라고만 잡아도 수백 MB 가 한 요청에 힙으로 들어옵니다.
> 동시 요청이 몇 개만 겹치면 힙이 터집니다.
> 첫 페이지를 보든 마지막 페이지를 보든 **매번 전건**을 읽습니다.

### 처방 ① — 컬렉션 fetch join 을 빼고 배치 페치

가장 간단합니다. 컬렉션은 fetch join 하지 말고 `@BatchSize` 또는 전역 설정으로 묶어서 읽습니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.customer, customer).fetchJoin()   // ToOne 만 fetch join
        .offset(0)
        .limit(10)
        .fetch();

for (Order o : orders) {
    System.out.println(o.getOrderItems().size());     // 여기서 컬렉션 초기화
}
```

```yaml
# application.yml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

**결과** — 쿼리 2개
```sql
-- 1번째: 주문 + 고객. limit 이 정상적으로 들어갑니다
select o1_0.order_id, o1_0.customer_id, ..., c1_0.customer_id, c1_0.name, ...
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
limit ?

-- 2번째: 10건의 주문에 딸린 상품을 in 절로 한 번에
select oi1_0.order_id, oi1_0.order_item_id, oi1_0.product_id,
       oi1_0.quantity, oi1_0.unit_price
from order_items oi1_0
where oi1_0.order_id in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```
```
바인딩: [1] 10 / [1..10] 1,2,3,4,5,6,7,8,9,10
총 쿼리 수 = 2
```

`limit` 이 SQL 에 들어갔고, 컬렉션은 `in` 절로 한 번에 읽었습니다.
`default_batch_fetch_size` 가 없었다면 이 두 번째 쿼리가 **10개**로 쪼개졌을 겁니다(N+1).
특정 연관에만 적용하려면 엔티티에 `@BatchSize(size = 100)` 을 붙입니다.

```java
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
@BatchSize(size = 100)
private List<OrderItem> orderItems = new ArrayList<>();
```

### 처방 ② — ToOne 만 fetch join 하고 페이징

`@ManyToOne` / `@OneToOne` 은 fetch join 해도 **행이 늘지 않습니다.**
주문 1건에 고객은 1명뿐이니 조인해도 여전히 600행입니다.
행 수가 그대로면 SQL 로 페이징해도 안전합니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.customer, customer).fetchJoin()   // ToOne — 안전
        .orderBy(order.id.asc())
        .offset(20)
        .limit(10)
        .fetch();
```

**결과** — 경고 없음, `limit` 정상
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount,
       c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from orders o1_0
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
order by o1_0.order_id asc
offset ? rows fetch first ? rows only
```
```
바인딩: [1] 20, [2] 10
조회 10건 — WARN 없음
```

**ToOne 과 컬렉션의 구분은 이 절에서 가장 중요한 표입니다.**

| 연관 종류 | 예 | fetch join 시 행 수 | 페이징 |
|---|---|---|---|
| `@ManyToOne` | `order.customer` | 그대로 (1:1 대응) | **안전** |
| `@OneToOne` | `order.receipt` | 그대로 | **안전** |
| `@OneToMany` | `order.orderItems` | **늘어남** (fan-out) | ⚠️ 메모리 페이징 |
| `@ManyToMany` | — | **늘어남** | ⚠️ 메모리 페이징 |

"fetch join 은 페이징과 못 쓴다" 는 말은 **정확하지 않습니다.**
**"컬렉션 fetch join 은 페이징과 못 쓴다"** 가 정확합니다. ToOne 은 얼마든지 같이 씁니다.

### 처방 ③ — ID 를 먼저 페이징하고 `in` 으로 컬렉션 로딩 (2단계 조회)

정렬이나 조건이 복잡해서 배치 페치로도 부족할 때 쓰는 방법입니다.

```java
// 1단계 — ID 만 페이징으로. fan-out 이 없으니 limit 이 정확히 동작합니다.
List<Long> ids = queryFactory
        .select(order.id)
        .from(order)
        .where(order.status.eq(OrderStatus.DELIVERED))
        .orderBy(order.orderDate.desc())
        .offset(0)
        .limit(10)
        .fetch();
```

**결과**
```sql
select o1_0.order_id
from orders o1_0
where o1_0.status = ?
order by o1_0.order_date desc
offset ? rows fetch first ? rows only
```
```
바인딩: [1] DELIVERED, [2] 0, [3] 10
조회 10건 — [587, 571, 559, 543, 531, 519, 503, 487, 475, 463]
```

```java
// 2단계 — 그 ID 들로 컬렉션까지 fetch join. limit 이 없으니 경고가 안 납니다.
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.orderItems, orderItem).fetchJoin()
        .join(order.customer, customer).fetchJoin()
        .where(order.id.in(ids))
        .orderBy(order.orderDate.desc())
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount,
       oi1_0.order_id, oi1_0.order_item_id, oi1_0.product_id,
       oi1_0.quantity, oi1_0.unit_price,
       c1_0.customer_id, c1_0.birth_date, c1_0.city, c1_0.created_at,
       c1_0.email, c1_0.grade, c1_0.name, c1_0.phone, c1_0.points
from orders o1_0
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
join customers c1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.order_id in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
order by o1_0.order_date desc
```
```
조회 10건 — WARN 없음. 읽은 조인 행 = 21행 (1,200행이 아님)
```

핵심은 **1단계에서 fan-out 이 없다는 것**입니다.
`order.id` 만 select 하면 조인이 없으니 `limit 10` 이 정확히 주문 10건을 자릅니다.
2단계는 `in` 으로 딱 그 10건만 읽으므로 `limit` 이 필요 없고, 따라서 경고도 없습니다.

### 세 처방 비교

| | 쿼리 수 | 읽는 행 | 복잡도 | 적합한 상황 |
|---|---:|---|---|---|
| ① 배치 페치 | 1 + 연관 수 | 필요한 만큼 | **낮음** | 대부분의 경우. **기본값** |
| ② ToOne 만 fetch join | 1 (+지연 로딩) | 페이지 크기 | 낮음 | 컬렉션이 화면에 필요 없을 때 |
| ③ 2단계 조회 | 2 | 페이지 크기 | 중간 | 정렬·조건이 복잡할 때 |

> 💡 **실무 팁 — `default_batch_fetch_size` 를 기본으로 켜 두십시오**
> 100~1000 사이가 일반적입니다. 이 한 줄이 대부분의 N+1 을 조용히 해결합니다.
> 그리고 팀 규칙으로 이렇게 정하십시오:
> **"`fetchJoin()` 옆에 `limit` 이 보이면 리뷰에서 멈춘다."**
> 컬렉션인지 ToOne 인지 확인하기 전에는 통과시키지 않는 겁니다.
> 이 함정은 코드 리뷰가 아니면 운영에서 잡히기 때문입니다.

---

## 6-9. ⚠️ 컬렉션 fetch join 은 하나만 — `MultipleBagFetchException`

주문에는 `orderItems` 도 있고 `payments` 도 있습니다. 둘 다 fetch join 해 봅시다.

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.orderItems, orderItem).fetchJoin()
        .join(order.payments, payment).fetchJoin()      // ← 두 번째 컬렉션
        .fetch();
```

**결과** — 애플리케이션 기동조차 못 하거나 쿼리 실행 시점에 터집니다
```
org.hibernate.loader.MultipleBagFetchException:
    cannot simultaneously fetch multiple bags:
    [com.example.shop.entity.Order.orderItems, com.example.shop.entity.Order.payments]

	at org.hibernate.query.sqm.internal.SqmUtil.verifyMultipleBagFetches(...)
	at org.hibernate.query.sqm.internal.ConcreteSqmSelectQueryPlan.<init>(...)
	...
```

### 왜 금지인가 — 카테시안 곱

주문 1건에 상품 3개, 결제 1건이 있다고 합시다.
`orderItems` 와 `payments` 를 동시에 조인하면 **3 × 1 = 3행**이 됩니다.
결제가 2건이면 **3 × 2 = 6행**입니다.

Hibernate 는 이 6행에서 `orderItems` 를 복원할 때 각 상품이 2번씩 나타나는 것을 봅니다.
`List` 는 **순서와 중복을 보존하는 컬렉션**이라, Hibernate 는 이 중복이
"진짜 데이터인지 조인 때문인지" 판단할 수 없습니다.
`orderItems.size()` 가 3인지 6인지 결정할 근거가 없는 것입니다.
그래서 **판단을 포기하고 예외를 던집니다.**

"bag" 은 Hibernate 용어로 **순서가 없고 중복을 허용하는 `List` 매핑**을 뜻합니다.
`@OrderColumn` 이 없는 `List<T>` 가 bag 입니다.

### 처방 ① — `List` 를 `Set` 으로

```java
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
private Set<OrderItem> orderItems = new LinkedHashSet<>();   // List → Set

@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
private Set<Payment> payments = new LinkedHashSet<>();
```

`Set` 은 중복을 허용하지 않으므로 Hibernate 가 안심하고 중복을 제거합니다. 예외가 사라집니다.

하지만 **문제가 사라진 것은 아닙니다.** DB 에서 읽어 오는 행 수는 여전히 3 × 2 = 6행입니다.
컬렉션이 3개, 4개로 늘면 곱집합이 기하급수적으로 커집니다.
`Set` 은 "예외를 없애는" 것이지 "곱집합을 없애는" 것이 아닙니다.
그리고 `LinkedHashSet` 이 아니면 순서가 보장되지 않아 화면 정렬이 깨집니다.

### 처방 ② — 하나만 fetch join 하고 나머지는 배치 페치 (권장)

```java
List<Order> orders = queryFactory
        .selectFrom(order)
        .join(order.orderItems, orderItem).fetchJoin()   // 하나만
        .where(order.id.loe(10L))
        .fetch();

// payments 는 지연 로딩 + default_batch_fetch_size 로 in 절 묶음 조회
orders.forEach(o -> System.out.println(o.getPayments().size()));
```

**결과** — 쿼리 2개
```sql
select o1_0.order_id, ..., oi1_0.order_item_id, ...
from orders o1_0
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
where o1_0.order_id <= ?

select p1_0.order_id, p1_0.payment_id, p1_0.amount,
       p1_0.method, p1_0.paid_at, p1_0.status
from payments p1_0
where p1_0.order_id in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```
```
총 쿼리 수 = 2
```

행 수가 곱해지지 않습니다. **컬렉션이 여러 개면 이쪽이 정답입니다.**

> 💡 **정리하면 규칙은 하나입니다 — 컬렉션 fetch join 은 최대 하나.**
> 그리고 그 하나에도 페이징을 붙이지 마십시오(6-8 절).
> 두 규칙을 합치면 이렇게 됩니다:
> **"컬렉션 fetch join 은 하나만, 페이징 없이."**
> 이 조건을 못 맞추겠으면 배치 페치를 쓰십시오.

---

## 6-10. `distinct()` 와 fetch join — Hibernate 6 에서 달라진 것

Hibernate 5 시절에는 컬렉션 fetch join 뒤에 `distinct()` 를 붙이는 것이 필수였습니다.

```java
List<Order> orders = queryFactory
        .selectFrom(order).distinct()
        .join(order.orderItems, orderItem).fetchJoin()
        .where(order.id.loe(3L))
        .fetch();
```

주문 3건에 상품이 각각 2, 3, 1개면 조인 결과는 6행입니다.
`distinct()` 없이 `fetch()` 하면 **같은 `Order` 객체가 6번 담긴 리스트**가 나왔습니다
(영속성 컨텍스트 덕분에 객체 자체는 동일했지만, 리스트에는 6개가 들어 있었습니다).

**Hibernate 6 부터 엔티티 쿼리는 기본적으로 중복을 제거합니다.**

```java
List<Order> withoutDistinct = queryFactory
        .selectFrom(order)
        .join(order.orderItems, orderItem).fetchJoin()
        .where(order.id.loe(3L))
        .fetch();
```

**결과**
```sql
select o1_0.order_id, o1_0.customer_id, o1_0.order_date,
       o1_0.shipping_city, o1_0.status, o1_0.total_amount,
       oi1_0.order_id, oi1_0.order_item_id, oi1_0.product_id,
       oi1_0.quantity, oi1_0.unit_price
from orders o1_0
join order_items oi1_0 on o1_0.order_id = oi1_0.order_id
where o1_0.order_id <= ?
```
```
바인딩: [1] 3
읽은 조인 행 = 6, 결과 리스트 크기 = 3   ← distinct() 없이도 3
```

`distinct()` 를 붙여도 결과는 같습니다. 붙일 이유가 없어졌습니다.

| | Hibernate 5 | Hibernate 6 (이 코스) |
|---|---|---|
| 컬렉션 fetch join 후 `distinct` 없이 | 중복된 엔티티가 리스트에 들어옴 | **자동 제거** |
| `distinct()` 의 역할 | **필수** (자바 쪽 중복 제거) | 사실상 불필요 |
| SQL 에 `distinct` 전달 | `hibernate.query.passDistinctThrough=false` 로 제어 | **해당 설정 제거됨** |

`hibernate.query.passDistinctThrough` 는 Hibernate 5 에서 "자바에서만 중복 제거하고
SQL 에는 `distinct` 를 보내지 마라" 는 뜻으로 쓰던 설정입니다.
엔티티 fetch join 에서 SQL `distinct` 는 성능만 깎고 의미가 없었기 때문입니다.
**Hibernate 6 에서는 이 설정이 제거됐습니다.** 설정해도 무시됩니다.

> ⚠️ **주의 — `distinct()` 를 아무 데나 붙이지 마십시오**
> 엔티티 조회에서는 필요 없어졌지만, **DTO 프로젝션이나 스칼라 조회에서는 여전히 의미가 있습니다.**
> `select(customer.city).distinct()` 는 SQL 에 `distinct` 를 넣고 실제로 중복을 제거합니다.
> "Hibernate 6 이니까 distinct 는 필요 없다" 로 일반화하면 안 됩니다.
> 정확히는 **"엔티티 쿼리에서 fetch join 때문에 붙이던 distinct 가 필요 없어졌다"** 입니다.

---

## 6-11. 셀프 조인 — 같은 엔티티를 두 번

`Employee` 는 `manager` 로 자기 자신을 참조합니다.
사원과 관리자를 나란히 보려면 같은 엔티티를 **다른 별칭으로** 두 번 등장시켜야 합니다.

기본 인스턴스 `employee` 하나로는 안 됩니다. [Step 02](../step-02-qtype/) 에서 본 대로
`new QEmployee("별칭")` 으로 별칭을 직접 만듭니다.

```java
QEmployee manager = new QEmployee("manager");     // ← 별칭 생성

List<Tuple> result = queryFactory
        .select(employee.id, employee.name, employee.position,
                manager.name, manager.position)
        .from(employee)
        .leftJoin(employee.manager, manager)
        .orderBy(employee.id.asc())
        .limit(10)
        .fetch();
```

**결과**
```sql
select e1_0.employee_id, e1_0.name, e1_0.position, m1_0.name, m1_0.position
from employees e1_0
left join employees m1_0 on m1_0.employee_id = e1_0.manager_id
order by e1_0.employee_id asc
limit ?
```
```
바인딩: [1] 10
 1 | 정한별 | CEO    | null   | null
 2 | 김코드 | 본부장 | 정한별 | CEO
 3 | 이세일 | 본부장 | 정한별 | CEO
 4 | 오지원 | 본부장 | 정한별 | CEO
 5 | 박서버 | 팀장   | 김코드 | 본부장
 6 | 최화면 | 팀장   | 김코드 | 본부장
 7 | 강매출 | 팀장   | 이세일 | 본부장
 8 | 윤사람 | 팀장   | 오지원 | 본부장
 9 | 한백엔 | 시니어 | 박서버 | 팀장
10 | 임쿼리 | 주니어 | 박서버 | 팀장
```

> 📌 MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) 의 `7-6` 절과 같은 결과입니다.
> 거기서는 `FROM employees e LEFT JOIN employees m ON m.employee_id = e.manager_id` 로 썼습니다.

별칭 `"manager"` 를 Hibernate 가 `m1_0` 으로 변환했습니다.
`leftJoin` 이라서 관리자가 없는 CEO(정한별)도 NULL 로 남았습니다.
`join` 이었다면 CEO 가 결과에서 사라집니다.

> ⚠️ **함정 — `employee` 를 두 번 쓰면 조인이 안 됩니다**
> ```java
> .from(employee).leftJoin(employee.manager, employee)   // ← 같은 별칭
> ```
> 별칭이 같으니 Hibernate 는 `e1_0` 하나만 만듭니다.
> 컴파일은 됩니다. 실행하면 JPQL 파싱 단계에서 중복 별칭 에러가 나거나,
> 조건에 따라 엉뚱한 자기 자신 비교가 됩니다.
> **셀프 조인에는 반드시 `new QEmployee("...")` 로 별도 별칭을 만드십시오.**

"같은 부서에서 나보다 급여가 높은 사람 수" 처럼 자기 그룹 안에서 비교할 때도 셀프 조인을 씁니다.

```java
QEmployee higher = new QEmployee("higher");

List<Tuple> result = queryFactory
        .select(employee.name, employee.dept, employee.salary, higher.count())
        .from(employee)
        .leftJoin(higher)
        .on(higher.dept.eq(employee.dept)
                .and(higher.salary.gt(employee.salary)))     // 연관 없는 on 조인
        .groupBy(employee.id, employee.name, employee.dept, employee.salary)
        .orderBy(employee.dept.asc(), employee.salary.desc())
        .limit(6)
        .fetch();
```

**결과**
```sql
select e1_0.name, e1_0.dept, e1_0.salary, count(h1_0.employee_id)
from employees e1_0
left join employees h1_0
       on h1_0.dept = e1_0.dept
      and h1_0.salary > e1_0.salary
group by e1_0.employee_id, e1_0.name, e1_0.dept, e1_0.salary
order by e1_0.dept asc, e1_0.salary desc
limit ?
```
```
바인딩: [1] 6
김코드 | 개발본부 | 9500000.00 | 0
박서버 | 개발본부 | 7200000.00 | 1
최화면 | 개발본부 | 7000000.00 | 2
한백엔 | 개발본부 | 5800000.00 | 3
조리액 | 개발본부 | 5600000.00 | 4
임쿼리 | 개발본부 | 4200000.00 | 5
```

"나보다 높은 사람 수 + 1" 이 부서 내 급여 순위입니다.
여기서 `leftJoin` 이 필수인 이유는 1등이 매칭 0건이기 때문입니다 —
`join` 이면 부서 1등이 결과에서 사라집니다.
그리고 `higher.count()` 를 쓴 것에 주목하십시오.
`count()` (즉 `count(*)`) 를 썼다면 NULL 확장 행도 세어서 1등이 `1` 로 나옵니다.
MySQL8 코스 `7-3` 절의 "`COUNT(*)` 함정" 과 완전히 같은 이야기입니다.

---

## 6-12. 안티 조인 — 짝이 없는 것 찾기

`leftJoin` 후 오른쪽 PK 가 NULL 인 행만 남기면 "짝이 없는 행" 이 나옵니다.
MySQL8 코스 [Step 07](../../mysql8/step-07-joins/) `7-5` 절의 `LEFT JOIN ... IS NULL` 입니다.

**SQL (MySQL8 코스 7-5)**
```sql
SELECT c.customer_id, c.name, c.grade
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.order_id IS NULL;
```

**QueryDSL**
```java
List<Tuple> result = queryFactory
        .select(customer.id, customer.name, customer.grade)
        .from(customer)
        .leftJoin(customer.orders, order)
        .where(order.id.isNull())               // ← NULL 확장을 노린다
        .orderBy(customer.id.asc())
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.name, c1_0.grade
from customers c1_0
left join orders o1_0 on c1_0.customer_id = o1_0.customer_id
where o1_0.order_id is null
order by c1_0.customer_id asc
```
```
조회 0건
```

**0건입니다.** 시드 데이터가 고객 30명 전원에게 주문 20건씩 배정했기 때문입니다.
"아무것도 안 나오는 것" 이 정답인 쿼리입니다. 데이터 검증에서 안티 조인이 쓰이는 전형입니다.

후기로 바꾸면 결과가 나옵니다.

```java
List<Tuple> result = queryFactory
        .select(customer.id, customer.name, customer.grade)
        .from(customer)
        .leftJoin(review).on(review.customer.eq(customer))
        .where(review.id.isNull())
        .orderBy(customer.id.asc())
        .limit(8)
        .fetch();
```

**결과**
```sql
select c1_0.customer_id, c1_0.name, c1_0.grade
from customers c1_0
left join reviews r1_0 on r1_0.customer_id = c1_0.customer_id
where r1_0.review_id is null
order by c1_0.customer_id asc
limit ?
```
```
바인딩: [1] 8
 2 | 이지은 | GOLD
 3 | 박철수 | SILVER
 4 | 최영희 | BRONZE
 5 | 정  훈 | GOLD
 6 | 강소라 | VIP
 7 | 윤대현 | BRONZE
 8 | 임수진 | SILVER
 9 | 한지호 | BRONZE
... (총 26건)
```

30명 중 26명이 후기를 한 번도 안 썼습니다 (작성자는 4명).

> ⚠️ **함정 — `isNull()` 대상은 반드시 NOT NULL 컬럼(대개 PK)이어야 합니다**
> `where(review.title.isNull())` 로 쓰면 완전히 다른 결과가 나옵니다.
> `title` 은 NULL 을 허용하므로, "후기가 없는 고객" 뿐 아니라
> **"후기는 썼는데 제목이 비어 있는 고객" 까지** 딸려옵니다.
> 안티 조인의 `isNull()` 은 언제나 **PK** 에 겁니다.

6-5 절에서 "LEFT JOIN 의 오른쪽 조건을 `where` 에 두면 안 된다" 고 했는데
여기서는 `where(review.id.isNull())` 을 쓰고 있습니다. 모순이 아닙니다.
안티 조인은 **NULL 확장된 행만 골라내는 것이 목적**이므로,
조인 후에 적용되는 `where` 가 정확히 필요한 도구입니다.

같은 것을 `exists` 로도 쓸 수 있습니다. [Step 07](../step-07-subqueries/) 에서 다룹니다.

```java
.where(JPAExpressions.selectOne().from(review)
        .where(review.customer.eq(customer)).notExists())
```

---

## 6-13. 조인 종류별 대조표

| 하려는 것 | SQL | QueryDSL |
|---|---|---|
| INNER JOIN | `JOIN orders o ON ...` | `.join(order.customer, customer)` |
| LEFT JOIN | `LEFT JOIN orders o ON ...` | `.leftJoin(order.customer, customer)` |
| RIGHT JOIN | `RIGHT JOIN ...` | `.rightJoin(...)` (거의 안 씀) |
| 세타 조인 | `FROM a, b WHERE ...` | `.from(a, b).where(...)` |
| 연관 없는 조인 | `LEFT JOIN b ON b.x = a.y` | `.leftJoin(b).on(b.x.eq(a.y))` |
| 조인 조건 추가 | `ON ... AND cond` | `.on(cond)` |
| 조인 후 필터 | `WHERE cond` | `.where(cond)` |
| 셀프 조인 | `JOIN employees m ON ...` | `new QEmployee("manager")` + `.leftJoin(...)` |
| 안티 조인 | `LEFT JOIN ... WHERE b.pk IS NULL` | `.leftJoin(...).where(b.id.isNull())` |
| fan-out 방어 | `COUNT(DISTINCT ...)` | `.countDistinct()` |
| **fetch join** | — (SQL 에 없음) | `.join(...).fetchJoin()` |
| **배치 페치** | — (SQL 에 없음) | `@BatchSize` / `default_batch_fetch_size` |

마지막 두 줄이 이 스텝의 절반입니다.
`fetchJoin()` 은 SQL 문법이 아니라 **"조인한 엔티티까지 영속성 컨텍스트에 채운다"** 는
JPA 의 지시입니다. SQL 만 보면 그냥 `join` 인데, select 절에 오른쪽 컬럼이 전부 들어가 있는 것으로 구분합니다.

---

## 정리

| 개념 | 핵심 |
|---|---|
| 연관 기반 조인 | `join(order.customer, customer)`. 경로 + 별칭. `on` 불필요 |
| 세타 조인 | `from(a, b).where(...)`. 외부 조인 불가. **조건 빠뜨리면 곱집합** |
| `join` = `innerJoin` | 같은 메서드. SQL 의 `INNER` 생략과 동일 |
| `leftJoin` | 왼쪽 전부 보존 + NULL 확장 |
| 다중 조인 | 별칭이 `c1_0`, `c2_0` 처럼 번호로 구분됨 |
| ⚠️ fan-out | 1:N 조인은 행의 **단위**를 바꾼다. `sum` 이 중복 합산됨 (20건 → 41건, 2배) |
| fan-out 방어 | `countDistinct` (count 만), 쿼리 분리, **행 단위에 맞는 집계식** |
| ⚠️ `on` vs `where` | LEFT JOIN 필터를 `where` 에 두면 **INNER JOIN 으로 퇴화** (17건 → 6건) |
| 연관 없는 `on` 조인 | `leftJoin(product).on(...)`. 인자 1개 + `on` |
| `fetchJoin()` | 조인 대상까지 영속성 컨텍스트에 채움. **N+1 11개 → 1개** |
| ⚠️ 컬렉션 fetch join + 페이징 | `HHH90003004` 경고 + **`limit` 없는 SQL** + 전건 메모리 로딩 |
| Hibernate 버전 차 | 6.x `HHH90003004` / 5.x `HHH000104` |
| 페이징 안전 여부 | **ToOne fetch join = 안전**, 컬렉션 fetch join = 위험 |
| 처방 3가지 | ① 배치 페치 ② ToOne 만 fetch join ③ ID 페이징 후 `in` |
| ⚠️ 컬렉션 fetch join 2개 | `MultipleBagFetchException`. 원인은 카테시안 곱 |
| 처방 | `List` → `Set` (예외만 없앰) 또는 **하나만 fetch join + 배치 페치** |
| `distinct()` | Hibernate 6 은 엔티티 쿼리 중복을 자동 제거. `passDistinctThrough` 는 **제거됨** |
| 셀프 조인 | `new QEmployee("manager")` 로 별칭 생성. 기본 인스턴스 재사용 금지 |
| 안티 조인 | `leftJoin(...).where(오른쪽PK.isNull())`. **PK 에만 걸 것** |

**이 스텝의 함정 4가지**

1. **fan-out** — 조인 하나 추가로 집계가 2배가 된다. 예외 없음.
2. **`on` vs `where`** — 둘 다 `BooleanExpression` 을 받아서 컴파일러가 못 잡는다. 17건이 6건이 된다.
3. **컬렉션 fetch join + 페이징** — `WARN` 한 줄만 찍고 전건을 힙에 올린다. 결과는 정확하다.
4. **컬렉션 fetch join 2개** — `MultipleBagFetchException`. 이건 그나마 터져 주니 다행이다.

3번만 기억한다면 이 스텝은 성공입니다.

---

## 연습문제

`Exercise.java` 에 7문제가 있습니다. 정답은 `Solution.java`.

1. `orders` 와 `customers` 를 조인해서 **서울에 사는 고객의 주문** 중 금액 상위 5건을 조회하세요.
   (주문번호, 고객명, 금액) — MySQL8 코스 Step 07 연습문제 1번과 같은 문제입니다.
   생성 SQL 이 `join customers c1_0 on ...` 인지 확인하십시오.
2. "고객별 주문 수와 주문 총액" 을 구하되, `orderItems` 를 조인한 버전과 안 한 버전을 **둘 다** 작성해
   숫자가 어떻게 달라지는지 확인하고 이유를 주석으로 쓰세요. 그다음 **올바른 값**이 나오는 버전을 쓰세요.
3. (`on` vs `where`) "모든 고객 + 그 고객의 **배송완료(DELIVERED) 주문**" 을 조회하는 쿼리를
   조건을 `on` 에 둔 버전과 `where` 에 둔 버전으로 각각 작성하고, **건수 차이**를 확인하세요.
   `on` 버전이 258건, `where` 버전이 240건이 나와야 합니다 (MySQL8 코스 7-4 절과 동일).
4. 주문 20건을 조회하면서 **고객 정보를 fetch join** 하세요.
   그다음 fetch join 없이 같은 코드를 돌려 **쿼리 개수**를 세고 비교하세요. (21개 vs 1개)
5. 아래 코드는 실행되고 결과도 맞지만 로그에 경고가 찍히고 SQL 에 `limit` 이 없습니다.
   원인을 설명하고 **세 가지 방법으로** 각각 고치세요.
   ```java
   queryFactory.selectFrom(order)
       .join(order.orderItems, orderItem).fetchJoin()
       .offset(0).limit(20)
       .fetch();
   ```
6. `Employee` 셀프 조인으로 **부하 직원이 한 명도 없는 사원(말단)** 을 안티 조인으로 찾으세요.
   힌트: 6-11 절의 조인 방향과 **반대**입니다. 자신의 `employee_id` 가 누군가의 `manager_id` 로
   쓰이지 않는 사원입니다. (10명이 나옵니다)
7. `orders` 를 `payments` 와 `leftJoin` 해서 **결제가 아예 없는 주문**을 안티 조인으로 찾고,
   그 개수가 `PENDING` 주문 수와 일치하는지 검산하세요. (양쪽 다 60건이어야 합니다)

---

## 다음 단계

조인은 "여러 테이블의 행을 옆으로 붙이는" 방법이었습니다.
그런데 6-4 절의 fan-out 방어에서 이미 "집계를 별도 쿼리로 분리" 하는 이야기가 나왔고,
6-12 절 끝에서는 안티 조인을 `notExists` 로도 쓸 수 있다고 했습니다.
그 "별도 쿼리" 를 SQL 안에서 쓰는 방법이 서브쿼리입니다.
다음 스텝에서는 `JPAExpressions` 로 스칼라·`in`·`exists` 서브쿼리를 쓰고,
**JPA 가 FROM 절 서브쿼리를 지원하지 않는다**는 제약과 그 우회법을 다룹니다.

→ [Step 07 — 서브쿼리](../step-07-subqueries/)

---

## 실습 파일

이 스텝은 자바 파일 3개로 구성됩니다.
`Practice.java` 의 예제를 `[6-1] ~ [6-13]` 순서대로 실행해 콘솔의 SQL 을 본문과 대조하고,
`Exercise.java` 의 7문제를 직접 푼 뒤, `Solution.java` 로 채점하는 흐름입니다.
세 파일 모두 `@SpringBootTest` + `@Transactional` 테스트 클래스이므로
프로젝트의 `src/test/java/com/example/shop/step06/` 에 그대로 넣고 실행하면 됩니다.

이 스텝은 **쿼리 개수를 세는 예제가 많습니다.**
Hibernate 의 `Statistics` 를 켜야 정확히 셀 수 있습니다.

```yaml
# application.yml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
        default_batch_fetch_size: 100
```

세 파일 모두 `queryCount()` 헬퍼를 갖고 있어, 이 설정만 켜면 그대로 동작합니다.

### Practice.java

본문 6-1 ~ 6-13 의 예제를 절 번호 주석으로 묶은 파일입니다.
절 번호가 본문 소제목과 1:1 대응하므로, 읽다가 막히면 같은 번호 블록을 찾아 실행하십시오.

- `[6-4]` 는 `fanOutCorrect()` → `fanOutWrong()` → `fanOutCountDistinct()` 순으로
  **반드시 이 순서대로** 실행하십시오. 20/24,300,000 → 41/49,860,000 → 20/49,860,000 으로
  숫자가 변하는 것을 눈으로 좇는 것이 목적입니다.
  세 번째에서 `count` 는 고쳐졌는데 `sum` 은 그대로인 것이 이 절의 핵심입니다.
- `[6-5]` 의 `onVersion()` 과 `whereVersion()` 은 `BooleanExpression` 변수를 **하나만 선언해**
  `.on(cond)` 와 `.where(cond)` 에 각각 넘깁니다. 코드 차이가 메서드 이름 하나뿐인데
  결과가 17건과 6건으로 갈리는 것을 보여주기 위한 구성입니다.
- `[6-8]` 이 이 파일의 심장입니다. `collectionFetchJoinWithPaging()` 을 실행하면
  콘솔에 `HHH90003004` 경고가 찍히고, 바로 아래 SQL 에 `limit` 이 **없습니다.**
  경고가 안 보이면 `logging.level.org.hibernate.orm.query: warn` 이 꺼져 있는지 확인하십시오.
  이어지는 `fix1BatchFetch()`, `fix2ToOneOnly()`, `fix3TwoStep()` 이 세 처방입니다.
- `[6-9]` 의 `multipleBagFetch()` 는 **일부러 예외를 내는 메서드**입니다.
  `try/catch` 로 감싸 예외 메시지를 출력하도록 해 뒀으니 테스트는 통과합니다.
  `MultipleBagFetchException` 의 실제 메시지 형태를 눈으로 확인하십시오.
- `[6-10]` 의 `distinctNotNeeded()` 는 "읽은 조인 행 6, 결과 리스트 3" 을 출력합니다.
  Hibernate 5 에서 같은 코드를 돌리면 결과 리스트가 6이 됩니다.

```java file="./Practice.java"
```

### Exercise.java

본문 연습문제 7개를 담은 빈칸 채우기용 파일입니다.
각 문제는 `// 문제 N.` 주석 블록으로 구분되어 있고 `// 여기에 작성:` 아래가 비어 있습니다.

- `[문제 2]` 와 `[문제 3]` 은 **한 메서드 안에 쿼리를 두 벌** 써야 합니다.
  귀찮게 느껴지겠지만, 두 숫자를 나란히 출력해 놓고 보는 것이 이 스텝의 학습 방식입니다.
  하나만 쓰고 "맞는 것 같다" 로 넘어가면 배우는 게 없습니다.
- `[문제 4]` 는 쿼리 개수를 세야 합니다. 파일 하단의 `queryCount()` 헬퍼를 쓰십시오.
  `em.flush(); em.clear();` 로 영속성 컨텍스트를 비우고 시작하지 않으면
  1차 캐시 때문에 쿼리가 안 나가서 개수가 틀어집니다. 이 부분은 이미 작성해 두었습니다.
- `[문제 5]` 만 예외적으로 **문제 코드가 이미 작성되어 있습니다.**
  먼저 그대로 실행해 `HHH90003004` 경고와 `limit` 없는 SQL 을 직접 확인한 뒤 고치십시오.
  세 처방을 각각 별도 메서드(`ex5Fix1`, `ex5Fix2`, `ex5Fix3`)로 나눠 두었습니다.
- `[문제 6]` 의 힌트 "조인 방향이 반대" 가 이 파일에서 가장 어려운 대목입니다.
  6-11 절은 `leftJoin(employee.manager, manager)` 였습니다.
  부하를 찾으려면 조인 조건을 반대로 걸어야 하는데, `employee.manager` 라는 연관 경로로는
  그 방향을 표현할 수 없습니다. 연관 없는 `on` 조인(6-6 절)이 필요합니다.

```java file="./Exercise.java"
```

### Solution.java

7문제의 정답과 해설 주석을 담은 파일입니다. `Exercise.java` 를 스스로 풀어본 **뒤에** 열어보십시오.
각 정답 위 주석에 기대 결과와 생성 SQL 이 함께 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 2]` 는 `orderItems` 를 조인한 버전이 **주문 수 1,200 / 총액 약 2배**가 나오는 것을 보여줍니다.
  그리고 `countDistinct` 로는 `count` 만 고쳐지고 `sum` 은 여전히 틀린다는 것을 숫자로 확인시킵니다.
  최종 정답은 "집계 쿼리를 분리" 또는 "`unitPrice.multiply(quantity).sum()`" 입니다.
- `[정답 3]` 의 258/240 은 MySQL8 코스 7-4 절의 숫자와 정확히 같습니다.
  차이 18은 "배송완료 주문이 하나도 없는 고객 18명" 입니다.
  두 코스의 숫자가 일치하는 것을 확인하면 "QueryDSL 은 SQL 을 만드는 도구일 뿐" 이라는 것이 체감됩니다.
- `[정답 5]` 가 이 파일의 하이라이트입니다. 세 처방의 **쿼리 수와 읽은 행 수**를 표로 정리해 뒀습니다.
  ①번(배치 페치)이 대부분의 경우 정답이라는 것, ②번은 컬렉션이 화면에 필요 없을 때만 쓴다는 것,
  ③번은 정렬·조건이 복잡할 때의 마지막 수단이라는 것을 구분하십시오.
  그리고 **어느 처방을 쓰든 "ToOne fetch join 은 페이징해도 안전하다"** 는 사실은 변하지 않습니다.
- `[정답 6]` 은 `leftJoin(subordinate).on(subordinate.manager.eq(employee))` 로 씁니다.
  `employee.manager` 라는 연관 경로를 쓰지 **못한다**는 것이 포인트입니다 —
  그 경로는 "나의 관리자" 방향이지 "나의 부하" 방향이 아니기 때문입니다.
  `Employee` 에 `@OneToMany subordinates` 매핑이 있었다면 연관 경로로도 되지만,
  우리 엔티티에는 없으므로 6-6 절의 `on` 조인이 유일한 방법입니다. 결과는 9~18번 사원 10명입니다.
- `[정답 7]` 의 60 = 60 검산은 MySQL8 코스 7-5 절과 동일합니다.
  `payment.id.isNull()` 로 걸어야 하며, `payment.status.isNull()` 로 쓰면
  "결제는 있는데 status 가 NULL 인 행" 이라는 다른 의미가 됩니다
  (우리 스키마에서 `status` 는 NOT NULL 이라 결과는 우연히 같지만, 의미가 다릅니다).

```java file="./Solution.java"
```
