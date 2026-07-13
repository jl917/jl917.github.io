# Step 08 — 서브쿼리

> **학습 목표**
> - 스칼라 / 행 / 테이블 서브쿼리를 구분하고, 각각 어디에 쓸 수 있는지 안다
> - WHERE·SELECT·FROM 절에서 서브쿼리를 자유롭게 쓴다
> - 상관 서브쿼리(correlated subquery)의 동작 원리를 이해한다
> - `IN` / `EXISTS` / `JOIN` 을 상황에 맞게 고른다
> - **`NOT IN` + `NULL` 함정**을 알고 피한다 (실무 최다 버그)
> - MySQL 8 의 파생 테이블 머지(derived merge)와 `LATERAL` 을 안다
>
> **선행 스텝**: Step 07 (조인)
> **예상 소요**: 50분

---

## 8-1. 서브쿼리란 무엇인가

쿼리 안에 들어 있는 또 다른 `SELECT` 를 서브쿼리라고 합니다.
왜 필요할까요? "평균보다 비싼 상품"을 찾으려면 **평균을 먼저 알아야** 합니다. 그런데 평균은 그 자체로 또 하나의 `SELECT` 입니다. 즉 "질문에 답하기 위해 먼저 답해야 하는 작은 질문"이 있을 때 서브쿼리를 씁니다.

서브쿼리는 **반환하는 모양**에 따라 세 가지로 나뉩니다.

| 종류 | 반환 모양 | 쓰는 곳 |
|---|---|---|
| 스칼라(scalar) | 1행 1열 (값 하나) | `SELECT`, `WHERE`, `HAVING` |
| 행(row) | 1행 N열 | `WHERE (a,b) = (...)` |
| 테이블(table) | N행 N열 | `FROM`(파생 테이블), `IN`, `EXISTS` |

가장 흔한 것이 **스칼라 서브쿼리**입니다. 값 하나를 반환하므로 컬럼처럼 쓸 수 있습니다.

```sql
SELECT
    o.order_id,
    o.total_amount,
    (SELECT ROUND(AVG(total_amount), 0) FROM orders) AS avg_all,
    o.total_amount - (SELECT AVG(total_amount) FROM orders) AS diff
FROM orders o
ORDER BY o.total_amount DESC
LIMIT 5;
```

**결과**
```
+----------+--------------+---------+----------------+
| order_id | total_amount | avg_all | diff           |
+----------+--------------+---------+----------------+
|      122 |   6663900.00 | 1274330 | 5389570.000000 |
|        2 |   6663900.00 | 1274330 | 5389570.000000 |
|      242 |   6663900.00 | 1274330 | 5389570.000000 |
|      482 |   6663900.00 | 1274330 | 5389570.000000 |
|      362 |   6663900.00 | 1274330 | 5389570.000000 |
+----------+--------------+---------+----------------+
```

`WHERE` 절에서도 똑같이 값 하나로 취급됩니다.

```sql
SELECT product_id, name, price
FROM products
WHERE price > (SELECT AVG(price) FROM products)
ORDER BY price DESC
LIMIT 5;
```

**결과**
```
+------------+-----------------------------+------------+
| product_id | name                        | price      |
+------------+-----------------------------+------------+
|         14 | 게이밍 노트북 RTX4060       | 2190000.00 |
|         13 | 울트라북 14 i7/32GB         | 1790000.00 |
|         17 | 스마트폰 X20 Pro 512GB      | 1490000.00 |
|         12 | 울트라북 14 i5/16GB         | 1290000.00 |
|         16 | 스마트폰 X20 256GB          | 1150000.00 |
+------------+-----------------------------+------------+
... (총 11행)
```

> ⚠️ **함정**: 스칼라 서브쿼리가 **2행 이상**을 반환하면 실행 시점에 죽습니다.
> ```sql
> SELECT * FROM products WHERE price = (SELECT price FROM products WHERE category_id = 21);
> -- ERROR 1242 (21000): Subquery returns more than 1 row
> ```
> 개발 환경에선 데이터가 1건이라 통과했다가, 운영에서 2건이 되는 순간 터집니다. `LIMIT 1` 로 덮지 말고 **정말 1행인지** 설계를 다시 보세요.

---

## 8-2. 다중행 서브쿼리 : IN

값이 여러 개면 `=` 대신 `IN` 을 씁니다. "서울에 사는 고객이 낸 주문"처럼 **목록에 속하는가**를 묻는 형태입니다.

```sql
SELECT order_id, customer_id, total_amount
FROM orders
WHERE customer_id IN (SELECT customer_id FROM customers WHERE city = '서울')
ORDER BY order_id
LIMIT 5;
```

**결과**
```
+----------+-------------+--------------+
| order_id | customer_id | total_amount |
+----------+-------------+--------------+
|        3 |          22 |    658000.00 |
|        6 |          13 |   2300000.00 |
|       12 |          25 |    798000.00 |
|       14 |          29 |   1434000.00 |
|       15 |          16 |     90000.00 |
+----------+-------------+--------------+
... (총 200행)
```

### 행(ROW) 서브쿼리

컬럼 **여러 개를 한 묶음으로** 비교할 수도 있습니다. `(a, b) = (서브쿼리)` 형태입니다.

```sql
SELECT order_id, customer_id, order_date, total_amount
FROM orders
WHERE (customer_id, total_amount) = (
    SELECT customer_id, MAX(total_amount)
    FROM orders
    WHERE customer_id = 1
    GROUP BY customer_id
);
```

**결과**
```
+----------+-------------+---------------------+--------------+
| order_id | customer_id | order_date          | total_amount |
+----------+-------------+---------------------+--------------+
|      120 |           1 | 2024-03-01 00:00:00 |   4380000.00 |
|      240 |           1 | 2024-04-30 00:00:00 |   4380000.00 |
|      360 |           1 | 2024-06-29 00:00:00 |   4380000.00 |
|      480 |           1 | 2024-08-28 00:00:00 |   4380000.00 |
|      600 |           1 | 2024-10-27 00:00:00 |   4380000.00 |
+----------+-------------+---------------------+--------------+
```

(시드 데이터가 규칙적이라 최대 금액이 같은 주문이 5건 나옵니다. "최댓값을 가진 행"은 하나가 아닐 수 있다는 것도 함께 기억하세요.)

---

## 8-3. FROM 절 서브쿼리 = 파생 테이블(derived table)

집계한 **결과를 다시 필터링**하고 싶을 때가 있습니다. `WHERE` 는 집계 전에 실행되므로 쓸 수 없고, `HAVING` 으로도 되지만 조인까지 얽히면 읽기 어려워집니다. 이럴 때 "집계 결과를 하나의 테이블처럼" 취급하는 것이 파생 테이블입니다.

```sql
SELECT s.customer_id, c.name, s.order_cnt, s.sum_amount
FROM (
    SELECT customer_id, COUNT(*) AS order_cnt, SUM(total_amount) AS sum_amount
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
) AS s
JOIN customers c ON c.customer_id = s.customer_id
WHERE s.sum_amount >= 3000000
ORDER BY s.sum_amount DESC
LIMIT 5;
```

**결과**
```
+-------------+-----------+-----------+-------------+
| customer_id | name      | order_cnt | sum_amount  |
+-------------+-----------+-----------+-------------+
|           8 | 임수진    |        20 | 58449000.00 |
|           5 | 정  훈    |        20 | 51568000.00 |
|          21 | 황도윤    |        20 | 50248500.00 |
|          14 | 남규리    |        20 | 46322000.00 |
|          17 | 백승호    |        20 | 45717000.00 |
+-------------+-----------+-----------+-------------+
... (총 26행)
```

> ⚠️ **함정**: 파생 테이블에는 **반드시 별칭**이 있어야 합니다.
> ```sql
> SELECT * FROM (SELECT 1);
> -- ERROR 1248 (42000): Every derived table must have its own alias
> ```

> 💡 **실무 팁**: 파생 테이블이 2단, 3단으로 중첩되기 시작하면 가독성이 급격히 나빠집니다. 그때가 바로 **Step 09 의 CTE(`WITH`)** 로 갈아탈 시점입니다.

---

## 8-4. 상관 서브쿼리(correlated subquery)

지금까지의 서브쿼리는 **바깥과 무관하게 한 번만** 계산됐습니다. 그런데 서브쿼리 안에서 바깥 테이블의 컬럼을 참조하면, 서브쿼리는 **바깥 행마다 새로 평가**됩니다. 이것을 상관 서브쿼리라고 합니다.

```sql
SELECT
    c.customer_id,
    c.name,
    (SELECT COUNT(*)          FROM orders o WHERE o.customer_id = c.customer_id) AS order_cnt,
    (SELECT MAX(o.order_date) FROM orders o WHERE o.customer_id = c.customer_id) AS last_order
FROM customers c
ORDER BY order_cnt DESC
LIMIT 5;
```

**결과**
```
+-------------+-----------+-----------+---------------------+
| customer_id | name      | order_cnt | last_order          |
+-------------+-----------+-----------+---------------------+
|           1 | 김민수    |        20 | 2025-10-12 18:30:00 |
|           2 | 이지은    |        20 | 2025-12-12 05:11:00 |
|           3 | 박철수    |        20 | 2025-12-13 16:52:00 |
|           4 | 최영희    |        20 | 2025-12-14 03:33:00 |
|           5 | 정  훈    |        20 | 2025-12-25 20:44:00 |
+-------------+-----------+-----------+---------------------+
... (총 30행)
```

`c.customer_id` 가 안쪽에 등장하는 순간 이 서브쿼리는 고객 30명 각각에 대해 실행됩니다. 고객이 30명이면 괜찮지만 300만 명이면 이야기가 달라집니다.

> 💡 **실무 팁**: 위 쿼리처럼 **같은 조건의 상관 서브쿼리를 SELECT 절에 여러 개** 늘어놓으면 테이블을 그만큼 반복해서 훑습니다. 하나의 `LEFT JOIN + GROUP BY` 또는 파생 테이블로 바꾸면 한 번에 끝납니다.

---

## 8-5. EXISTS / NOT EXISTS

`EXISTS` 는 "그런 행이 **하나라도 있으면** 참"입니다. 값을 가져오는 게 아니라 **존재 여부**만 봅니다. 그래서 안쪽 `SELECT` 에 무엇을 쓰든(`1`, `*`, `NULL`) 성능은 같습니다. 관례적으로 `SELECT 1` 을 씁니다.

```sql
SELECT c.customer_id, c.name, c.grade
FROM customers c
WHERE EXISTS (
    SELECT 1 FROM reviews r WHERE r.customer_id = c.customer_id
)
ORDER BY c.customer_id;
```

**결과**
```
+-------------+-----------+--------+
| customer_id | name      | grade  |
+-------------+-----------+--------+
|           1 | 김민수    | VIP    |
|          22 | 안지수    | GOLD   |
|          25 | 양현우    | VIP    |
|          28 | 심준호    | SILVER |
+-------------+-----------+--------+
```

반대로 `NOT EXISTS` 는 "그런 행이 **하나도 없으면** 참"입니다. 결제가 없는 주문(= `PENDING`)을 찾아봅시다.

```sql
SELECT o.order_id, o.status, o.total_amount
FROM orders o
WHERE NOT EXISTS (
    SELECT 1 FROM payments p WHERE p.order_id = o.order_id
)
ORDER BY o.order_id
LIMIT 5;
```

**결과**
```
+----------+---------+--------------+
| order_id | status  | total_amount |
+----------+---------+--------------+
|        7 | PENDING |   1116000.00 |
|       17 | PENDING |   1942800.00 |
|       27 | PENDING |    318000.00 |
|       37 | PENDING |    276000.00 |
|       47 | PENDING |    854000.00 |
+----------+---------+--------------+
... (총 60행)
```

---

## 8-6. IN vs EXISTS vs JOIN — 어떤 걸 써야 하나

"한 번이라도 팔린 상품 수"를 세 가지로 써 보겠습니다.

```sql
-- (1) IN
SELECT COUNT(*) AS cnt FROM products p
WHERE p.product_id IN (SELECT oi.product_id FROM order_items oi);

-- (2) EXISTS
SELECT COUNT(*) AS cnt FROM products p
WHERE EXISTS (SELECT 1 FROM order_items oi WHERE oi.product_id = p.product_id);

-- (3) JOIN — DISTINCT 를 잊으면?
SELECT COUNT(*) AS cnt_wrong
FROM products p JOIN order_items oi ON oi.product_id = p.product_id;
```

**결과**
```
(1) IN        → 40
(2) EXISTS    → 40
(3) JOIN      → 1200   ← 틀렸다!
```

> ⚠️ **함정**: `JOIN` 은 1:N 이면 **행이 늘어납니다**. 상품 40개가 주문상세 1,200건과 조인되어 1,200이 나왔습니다. 존재 여부만 궁금할 때 조인을 쓰면 이렇게 **부풀려진 카운트**가 나옵니다. `COUNT(DISTINCT p.product_id)` 로 고쳐야 40이 됩니다.
> `IN`/`EXISTS` 는 원래 "존재 여부"를 묻는 문법이므로 이런 사고가 구조적으로 일어나지 않습니다. **행을 늘리고 싶지 않다면 IN/EXISTS 를 쓰세요.**

성능은 어떨까요? MySQL 8 옵티마이저는 `IN (서브쿼리)` 를 **세미조인(semijoin)** 으로 바꿔버립니다.

```sql
EXPLAIN FORMAT=TREE
SELECT p.product_id FROM products p
WHERE p.product_id IN (SELECT oi.product_id FROM order_items oi);
```

**결과**
```
-> Nested loop semijoin  (cost=135 rows=1200)
    -> Index scan on p using idx_products_category  (cost=4.25 rows=40)
    -> Covering index lookup on oi using idx_order_items_product (product_id=p.product_id)  (cost=10.1 rows=30)
```

`Nested loop semijoin` — 조인처럼 처리하되 **중복은 만들지 않는** 조인입니다. 즉 MySQL 8 에서는 `IN` 과 `EXISTS` 의 성능 차이가 거의 없습니다. "`EXISTS` 가 무조건 빠르다"는 말은 MySQL 5.5 이전 시절의 이야기입니다.

| 상황 | 권장 |
|---|---|
| 존재 여부만 확인 | `EXISTS` / `IN` |
| 서브쿼리 쪽 컬럼도 **결과에 필요** | `JOIN` |
| 없는 것을 찾기(안티 조인) | **`NOT EXISTS`** (NOT IN 은 위험, 아래 참조) |
| 1:N 조인인데 개수를 세야 함 | `JOIN` + `COUNT(DISTINCT ...)` 또는 파생 테이블 |

---

## 8-7. ANY / ALL

`> ANY (...)` 는 "서브쿼리 결과 중 **하나라도** 보다 크면", `> ALL (...)` 은 "**전부**보다 크면" 입니다. 결국 `> ANY` = `> MIN(...)`, `> ALL` = `> MAX(...)` 와 같습니다.

비교 대상인 스마트폰(카테고리 22) 가격은 399,000 / 1,150,000 / 1,490,000 입니다.

```sql
-- 노트북 중, 어떤 스마트폰보다든 비싼 것 (= 최저가 스마트폰보다 비싼 것)
SELECT product_id, name, price FROM products
WHERE category_id = 21 AND price > ANY (SELECT price FROM products WHERE category_id = 22)
ORDER BY price;
```

**결과**
```
+------------+-----------------------------+------------+
| product_id | name                        | price      |
+------------+-----------------------------+------------+
|         15 | 보급형 노트북 15            |  690000.00 |
|         12 | 울트라북 14 i5/16GB         | 1290000.00 |
|         13 | 울트라북 14 i7/32GB         | 1790000.00 |
|         14 | 게이밍 노트북 RTX4060       | 2190000.00 |
+------------+-----------------------------+------------+
```

```sql
-- 노트북 중, 모든 스마트폰보다 비싼 것 (= 최고가 스마트폰보다 비싼 것)
SELECT product_id, name, price FROM products
WHERE category_id = 21 AND price > ALL (SELECT price FROM products WHERE category_id = 22)
ORDER BY price;
```

**결과**
```
+------------+-----------------------------+------------+
| product_id | name                        | price      |
+------------+-----------------------------+------------+
|         13 | 울트라북 14 i7/32GB         | 1790000.00 |
|         14 | 게이밍 노트북 RTX4060       | 2190000.00 |
+------------+-----------------------------+------------+
```

> 💡 **실무 팁**: `= ANY` 는 `IN` 과 완전히 같고, `<> ALL` 은 `NOT IN` 과 같습니다. 가독성 때문에 보통 `IN`/`NOT IN` 을 씁니다.
> ⚠️ 서브쿼리가 **0행**이면 `> ALL` 은 항상 참, `> ANY` 는 항상 거짓입니다. 직관과 반대이니 주의하세요.

---

## 8-8. ⚠️ 최대 함정 : NOT IN + NULL

이 절 하나만 기억해도 이 스텝은 본전을 뽑습니다.

"**관리자가 아닌 사원**", 즉 부하 직원이 한 명도 없는 사원을 찾아봅시다. 자연스러운 첫 시도는 이렇습니다.

```sql
SELECT COUNT(*) AS cnt
FROM employees e
WHERE e.employee_id NOT IN (SELECT manager_id FROM employees);
```

**결과**
```
+-----+
| cnt |
+-----+
|   0 |
+-----+
```

**0행.** 18명 중 팀원이 10명은 있는데 왜 0일까요? 원인은 서브쿼리에 있습니다.

```sql
SELECT manager_id, COUNT(*) AS cnt FROM employees GROUP BY manager_id ORDER BY manager_id LIMIT 3;
```

**결과**
```
+------------+-----+
| manager_id | cnt |
+------------+-----+
|       NULL |   1 |   ← CEO 는 상사가 없다
|          1 |   3 |
|          2 |   2 |
+------------+-----+
```

서브쿼리 결과에 `NULL` 이 섞여 있습니다. SQL 의 3값 논리(TRUE / FALSE / **UNKNOWN**)에서는:

```sql
SELECT 5 NOT IN (1, 2, NULL) AS r1,
       5 IN     (1, 2, NULL) AS r2,
       5 <> NULL             AS r3;
```

**결과**
```
+------+------+------+
| r1   | r2   | r3   |
+------+------+------+
| NULL | NULL | NULL |
+------+------+------+
```

`5 NOT IN (1,2,NULL)` 은 내부적으로 `5<>1 AND 5<>2 AND 5<>NULL` 입니다. 마지막이 `UNKNOWN` 이므로 전체가 `TRUE` 가 될 수 없습니다. `WHERE` 는 **TRUE 인 행만** 통과시키므로 결과는 항상 0행입니다.

**해결 방법 3가지 — 셋 다 정답 10을 냅니다.**

```sql
-- 해결 1: 서브쿼리에서 NULL 제거
SELECT COUNT(*) FROM employees e
WHERE e.employee_id NOT IN (SELECT manager_id FROM employees WHERE manager_id IS NOT NULL);

-- 해결 2: NOT EXISTS  ← 가장 권장. NULL 에 구조적으로 안전
SELECT COUNT(*) FROM employees e
WHERE NOT EXISTS (SELECT 1 FROM employees m WHERE m.manager_id = e.employee_id);

-- 해결 3: LEFT JOIN ... IS NULL (안티 조인)
SELECT COUNT(*) FROM employees e
LEFT JOIN employees m ON m.manager_id = e.employee_id
WHERE m.employee_id IS NULL;
```

**결과**
```
해결 1 → 10
해결 2 → 10
해결 3 → 10
```

> ⚠️ **함정 요약**: **서브쿼리 컬럼이 NULL 을 허용한다면 `NOT IN` 을 쓰지 마세요.** 에러도 안 나고 조용히 0행을 반환합니다. "왜 결과가 안 나오지?" 하며 몇 시간을 날리는 대표적 버그입니다. 습관적으로 `NOT EXISTS` 를 쓰는 것이 가장 안전합니다.
> (반대로 `IN` 은 NULL 이 있어도 "있는 것"은 정상적으로 찾아주므로 상대적으로 안전합니다. 문제는 부정형뿐입니다.)

---

## 8-9. MySQL 8 의 파생 테이블 처리 : merge vs materialize

MySQL 5.6 까지는 `FROM` 절 서브쿼리를 **무조건 임시 테이블로 구체화(materialize)** 했습니다. 인덱스도 없는 임시 테이블이 생기니 느렸죠. MySQL 5.7 부터(그리고 8.0 에서 기본) 옵티마이저는 가능하면 파생 테이블을 **바깥 쿼리에 합쳐(merge)** 버립니다.

```sql
EXPLAIN FORMAT=TREE
SELECT d.product_id, d.name
FROM (SELECT product_id, name, price, category_id FROM products) AS d
WHERE d.category_id = 21;
```

**결과**
```
-> Index lookup on products using idx_products_category (category_id=21)  (cost=1.15 rows=4)
```

파생 테이블이 **사라졌습니다.** 옵티마이저가 `SELECT ... FROM products WHERE category_id = 21` 로 다시 써서, 인덱스까지 그대로 탑니다.

세션에서 머지를 꺼 보면 차이가 확 드러납니다.

```sql
SET SESSION optimizer_switch = 'derived_merge=off';
EXPLAIN FORMAT=TREE
SELECT d.product_id, d.name
FROM (SELECT product_id, name, price, category_id FROM products) AS d
WHERE d.category_id = 21;
SET SESSION optimizer_switch = 'derived_merge=on';
```

**결과**
```
-> Table scan on d  (cost=2.19..4.1 rows=4)
    -> Materialize  (cost=1.55..1.55 rows=4)
        -> Index lookup on products using idx_products_category (category_id=21)  (cost=1.15 rows=4)
```

`Materialize` → 임시 테이블을 만들고 → 그걸 다시 `Table scan` 합니다. 한 단계가 더 붙었죠.

그런데 파생 테이블이 항상 머지되는 건 아닙니다. `GROUP BY`, `DISTINCT`, `LIMIT`, 집계함수, `UNION` 등이 들어 있으면 **머지할 수 없어** 구체화됩니다.

```sql
EXPLAIN FORMAT=TREE
SELECT d.category_id, d.cnt
FROM (SELECT category_id, COUNT(*) AS cnt FROM products GROUP BY category_id) AS d
WHERE d.cnt >= 4;
```

**결과**
```
-> Table scan on d  (cost=9.67..12.1 rows=12)
    -> Materialize  (cost=9.45..9.45 rows=12)
        -> Filter: (count(0) >= 4)  (cost=8.25 rows=12)
            -> Group aggregate: count(0)  (cost=8.25 rows=12)
                -> Index scan on products using idx_products_category  (cost=4.25 rows=40)
```

> 💡 **실무 팁**: 파생 테이블이 구체화되면 **바깥 조건이 안쪽으로 내려가지 않아** 안쪽에서 대량의 행을 만들어놓고 바깥에서 버리는 낭비가 생깁니다. 구체화가 확실한 파생 테이블(GROUP BY 등)에는 **안쪽에도 직접 WHERE 를 넣어** 행 수를 미리 줄이세요.

---

## 8-10. LATERAL (MySQL 8.0.14+)

일반 파생 테이블은 **바깥 행을 참조할 수 없습니다.** 그래서 "고객마다 가장 비싼 주문 1건"처럼 행마다 다른 Top-N 을 뽑는 게 불가능했습니다. `LATERAL` 은 그 제약을 풉니다. 파생 테이블이 상관 서브쿼리처럼 바깥 행을 볼 수 있게 됩니다.

```sql
SELECT c.customer_id, c.name, t.order_id, t.total_amount
FROM customers c
JOIN LATERAL (
    SELECT o.order_id, o.total_amount
    FROM orders o
    WHERE o.customer_id = c.customer_id   -- ← 바깥의 c 를 참조!
    ORDER BY o.total_amount DESC
    LIMIT 1
) AS t ON TRUE
ORDER BY t.total_amount DESC
LIMIT 5;
```

**결과**
```
+-------------+-----------+----------+--------------+
| customer_id | name      | order_id | total_amount |
+-------------+-----------+----------+--------------+
|           5 | 정  훈    |        2 |   6663900.00 |
|          21 | 황도윤    |       40 |   6599000.00 |
|          14 | 남규리    |       59 |   5430600.00 |
|          30 | 하준서    |       97 |   5378900.00 |
|           8 | 임수진    |       71 |   4717000.00 |
+-------------+-----------+----------+--------------+
... (총 30행)
```

> 💡 **실무 팁**: 그룹별 Top-N 은 Step 13 의 윈도우 함수(`ROW_NUMBER()`)로도 풀 수 있습니다. **N이 작고 인덱스가 잘 잡혀 있으면 LATERAL 이 더 빠른** 경우가 많습니다(각 그룹에서 딱 N행만 읽고 끝내므로). 둘 다 알아두고 `EXPLAIN` 으로 비교하세요.
> `LATERAL` 파생 테이블에는 조인 조건이 필요 없으므로 관례적으로 `ON TRUE` 를 씁니다.

---

## 8-11. 종합 예제

"자기가 속한 카테고리의 평균가보다 비싼 상품"은 상관 서브쿼리의 교과서적 사례입니다.

```sql
SELECT p.product_id, p.name, p.category_id, p.price,
       (SELECT ROUND(AVG(p2.price)) FROM products p2 WHERE p2.category_id = p.category_id) AS cat_avg
FROM products p
WHERE p.price > (SELECT AVG(p2.price) FROM products p2 WHERE p2.category_id = p.category_id)
ORDER BY p.category_id, p.price DESC
LIMIT 8;
```

**결과**
```
+------------+-----------------------------+-------------+------------+---------+
| product_id | name                        | category_id | price      | cat_avg |
+------------+-----------------------------+-------------+------------+---------+
|          3 | 라이트 다운 재킷            |          11 |  159000.00 |   81500 |
|          7 | 트렌치 코트                 |          12 |  249000.00 |  126500 |
|          6 | 실크 블라우스               |          12 |  129000.00 |  126500 |
|         11 | 첼시 부츠                   |          13 |  189000.00 |  139000 |
|         14 | 게이밍 노트북 RTX4060       |          21 | 2190000.00 | 1490000 |
|         13 | 울트라북 14 i7/32GB         |          21 | 1790000.00 | 1490000 |
|         17 | 스마트폰 X20 Pro 512GB      |          22 | 1490000.00 | 1013000 |
|         16 | 스마트폰 X20 256GB          |          22 | 1150000.00 | 1013000 |
+------------+-----------------------------+-------------+------------+---------+
... (총 18행)
```

---

## 정리

| 주제 | 핵심 |
|---|---|
| 스칼라 서브쿼리 | 1행 1열. 2행 이상이면 **ERROR 1242** |
| 행 서브쿼리 | `(a,b) = (SELECT a,b ...)` |
| 파생 테이블 | `FROM (...) AS 별칭` — **별칭 필수** |
| 상관 서브쿼리 | 바깥 컬럼 참조 → **바깥 행마다 재실행** |
| `EXISTS` | 존재 여부만. `SELECT 1` 관례 |
| `IN` vs `EXISTS` | MySQL 8 은 둘 다 **semijoin** 으로 처리 → 성능 비슷 |
| `JOIN` 으로 존재 확인 | 1:N 이면 **행이 부풀어 오름** → `COUNT(DISTINCT)` 필요 |
| `ANY` / `ALL` | `> ANY` = `> MIN`, `> ALL` = `> MAX` |
| **`NOT IN` + NULL** | **조용히 0행.** → `NOT EXISTS` 를 쓰자 |
| derived merge | 8.0 기본 ON. `GROUP BY`/`LIMIT`/`DISTINCT` 있으면 materialize |
| `LATERAL` | **8.0.14+**. 파생 테이블이 바깥 행 참조 가능 → 그룹별 Top-N |

---

## 연습문제

`exercise.sql` 을 푸세요. 정답은 `solution.sql`.

1. 평균 주문금액보다 큰 주문의 건수
2. 후기를 한 번도 받지 못한 상품 목록 (NOT EXISTS)
3. `NOT IN` 으로 같은 질문을 풀되, NULL 함정을 피하도록 작성
4. 고객별 주문 합계를 파생 테이블로 만들어 상위 5명
5. 자기 부서 평균 급여보다 많이 받는 사원 (상관 서브쿼리)
6. `ALL` 을 써서, 모든 '주변기기'보다 비싼 '노트북'
7. `LATERAL` 로 카테고리별 최고가 상품 1개씩
8. 결제가 없는 주문을 `NOT EXISTS` / `LEFT JOIN IS NULL` 두 방법으로

---

## 다음 단계

→ [Step 09 — CTE와 재귀 쿼리](../step-09-cte-recursive/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 구성됩니다. 먼저 `practice.sql` 을 실행해 8-1 ~ 8-11 절의 예제를 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸 8문제를 직접 채워 본 뒤, 마지막으로 `solution.sql` 로 답과 해설을 맞춰 보는 순서입니다. 세 파일은 조회 계열 문장(`SELECT`, `EXPLAIN`)만 사용하므로 `shop` 데이터베이스의 데이터를 **변경하지 않습니다.** 단 하나의 예외가 `practice.sql` 의 `[8-28]` 인데, 여기서만 `SET SESSION optimizer_switch` 로 옵티마이저 옵션을 바꿉니다. 이름 그대로 **접속한 세션에만** 적용되고 마지막 줄에서 원래대로 되돌리므로, 데이터에도 서버 전체 설정에도 영향이 없습니다. 실행은 모두 같은 방식입니다.

```
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
```

### practice.sql

강의 본문(8-1 ~ 8-11)에 나오는 모든 쿼리를 `[8-1]` ~ `[8-30]` 번호로 순서대로 담아 둔 실습 스크립트입니다. 본문을 읽으며 한 블록씩 복사해 실행해도 되고, 파일 전체를 한 번에 흘려보내 결과를 쭉 훑어봐도 됩니다.

- 맨 위 `USE shop;` 이 있으므로 접속 시 DB를 지정하지 않아도 동작합니다.
- `[8-3]` 과 `[8-7]` 은 **의도적으로 주석 처리된 실패 예제**입니다. `SELECT * FROM products WHERE price = (SELECT price FROM products WHERE category_id = 21)` 은 노트북이 4종이라 서브쿼리가 4행을 반환해 `ERROR 1242 (21000): Subquery returns more than 1 row` 로 죽고, `SELECT * FROM (SELECT 1)` 은 별칭이 없어 `ERROR 1248 (42000)` 로 죽습니다. 주석을 풀면 파일 전체 실행이 그 지점에서 중단되므로, 확인하고 싶다면 **한 줄씩 따로** 실행하세요.
- `[8-12]`~`[8-15]` 는 "한 번이라도 팔린 상품 수"를 `IN` / `EXISTS` / `JOIN` / `COUNT(DISTINCT)` 네 가지로 세어 봅니다. `[8-14]` 만 1200 이 나오는데, 이것이 1:N 조인으로 행이 부풀어 오른 오답입니다.
- `[8-20]`~`[8-25]` 가 이 스텝의 핵심인 `NOT IN` + `NULL` 함정과 해결책 3종입니다. `[8-20]` 이 `0` 을 반환하는 것을 직접 본 다음 `[8-21]` 로 서브쿼리에 `NULL` 이 섞여 있음을 확인하는 흐름입니다.
- `[8-28]` 은 `SET SESSION optimizer_switch = 'derived_merge=off'` 로 파생 테이블 머지를 끄고 `Materialize` 가 실행계획에 등장하는 것을 보여 준 뒤, 마지막 줄에서 다시 `derived_merge=on` 으로 되돌립니다. **세션 한정 설정**이라 서버 전체에는 영향이 없지만, 중간에서 실행을 멈추면 그 세션에는 머지가 꺼진 채로 남으니 마지막 줄까지 함께 실행하세요.
- `[8-29]` 의 `LATERAL` 은 **MySQL 8.0.14 이상**에서만 동작합니다. 그보다 낮은 버전이면 구문 에러가 납니다.

```sql file="./practice.sql"
```

### exercise.sql

「연습문제」 절의 8문제를 담은 빈칸 파일입니다. 각 문항이 `Q1` ~ `Q8` 주석 블록으로만 되어 있고 그 아래가 비어 있으니, 직접 쿼리를 써 넣고 실행해 결과를 확인하면 됩니다.

- 문항마다 **요구 컬럼명과 정렬 기준이 명시**되어 있습니다(예: Q4 는 `customer_id, name, grade, order_cnt, sum_amount`, Q5 는 `정렬: dept, salary DESC`). 채점 기준이니 그대로 맞춰 쓰세요.
- Q2 와 Q3 은 **같은 질문을 `NOT EXISTS` 와 `NOT IN` 으로 각각** 푸는 문제입니다. 8-8 절에서 배운 대로 Q3 에서는 `NULL` 함정을 피하는 방어적 형태로 써야 합니다.
- Q5 는 상관 서브쿼리를, Q6 은 `ALL` 을, Q7 은 `LATERAL` 을 쓰라고 **풀이 방법까지 지정**하고 있습니다. 결과만 맞추지 말고 지정된 문법으로 푸는 것이 학습 목적입니다.
- Q8 은 두 방법(`NOT EXISTS`, `LEFT JOIN ... IS NULL`)의 결과가 **같은 값(60)** 이 나오는지 스스로 확인하는 문제입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 의 정답과 해설입니다. 답만 있는 게 아니라 **왜 그렇게 푸는지**가 주석으로 붙어 있으니, 자신의 답과 결과 행 수가 같더라도 주석을 꼭 읽어 보세요.

- 각 답 아래에 `-- → 210`, `-- → 24행`, `-- → 60` 처럼 **기대 결과가 적혀 있어** 자기 답을 바로 대조할 수 있습니다.
- A3 주석이 중요합니다. 지금 스키마에선 `reviews.product_id` 가 `NOT NULL` 이라 `WHERE r.product_id IS NOT NULL` 이 없어도 되지만, **스키마는 바뀔 수 있으므로 방어적으로 붙이는 습관**을 권합니다. 근본적으로는 A2 처럼 `NOT EXISTS` 를 쓰는 쪽이 낫습니다.
- A8-b 주석의 경고를 놓치지 마세요. `LEFT JOIN ... IS NULL` 의 `IS NULL` 검사는 **조인 대상 테이블의 NOT NULL 컬럼(보통 PK)** 에 걸어야 합니다. 그래서 `p.payment_id IS NULL` 을 씁니다. NULL 을 허용하는 컬럼에 걸면 "조인은 됐지만 그 컬럼 값이 NULL 인 행"까지 섞여 들어옵니다.
- A7 의 `LATERAL` 안에 `ORDER BY p.price DESC, p.product_id` 로 **동점 시 tie-breaker** 를 하나 더 둔 점도 눈여겨보세요. 가격이 같은 상품이 있어도 결과가 항상 같게 나옵니다.

```sql file="./solution.sql"
```
