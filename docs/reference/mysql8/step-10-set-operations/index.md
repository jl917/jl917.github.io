# Step 10 — 집합 연산 (UNION / INTERSECT / EXCEPT)

> **학습 목표**
> - `UNION` 과 `UNION ALL` 의 차이를 이해하고, **왜 UNION ALL 이 빠른지** 설명할 수 있다
> - 집합 연산의 컬럼 개수·타입 규칙과 조용히 일어나는 타입 변환 함정을 안다
> - `ORDER BY` / `LIMIT` 를 **전체에 걸지, 개별 SELECT 에 걸지** 구분한다
> - `INTERSECT` / `EXCEPT` (**MySQL 8.0.31+**) 를 쓰고, 그 이전 방식과 비교한다
> - MySQL에 없는 `FULL OUTER JOIN` 을 흉내낸다
>
> **선행 스텝**: Step 09 (CTE)
> **예상 소요**: 40분

---

## 10-0. 조인은 가로, 집합 연산은 세로

조인은 테이블을 **옆으로** 붙입니다(컬럼이 늘어남). 집합 연산은 **아래로** 붙입니다(행이 늘어남). 서로 다른 질문에 대한 답을 하나의 결과로 합칠 때 씁니다.

`INTERSECT` 와 `EXCEPT` 는 **MySQL 8.0.31 에서 처음 추가**됐습니다. 그 이전 버전에서는 문법 에러가 납니다. 먼저 버전을 확인하세요.

```sql
SELECT VERSION() AS version;
```

**결과**
```
+---------+
| version |
+---------+
| 8.0.46  |
+---------+
```

---

## 10-1. UNION vs UNION ALL

`UNION` 은 두 결과를 붙인 뒤 **중복을 제거**합니다. `UNION ALL` 은 중복 제거 없이 그냥 이어붙입니다.

```sql
SELECT city FROM customers WHERE grade = 'VIP'
UNION
SELECT shipping_city FROM orders WHERE total_amount > 6000000;
```

**결과**
```
+--------+
| city   |
+--------+
| 서울   |
| 인천   |
| 대전   |
+--------+
```

```sql
SELECT city FROM customers WHERE grade = 'VIP'
UNION ALL
SELECT shipping_city FROM orders WHERE total_amount > 6000000;
```

**결과**
```
+--------+
| city   |
+--------+
| 서울   |
| 서울   |
| 서울   |
| 서울   |
| 서울   |
| 인천   |
| 대전   |
| 인천   |
| 대전   |
| 인천   |
+--------+
... (총 15행)
```

같은 데이터인데 3행 vs 15행입니다.

```sql
SELECT 'UNION' AS op, COUNT(*) AS cnt FROM (
    SELECT city FROM customers WHERE grade = 'VIP'
    UNION
    SELECT shipping_city FROM orders WHERE total_amount > 6000000
) x
UNION ALL
SELECT 'UNION ALL', COUNT(*) FROM (
    SELECT city FROM customers WHERE grade = 'VIP'
    UNION ALL
    SELECT shipping_city FROM orders WHERE total_amount > 6000000
) y;
```

**결과**
```
+-----------+-----+
| op        | cnt |
+-----------+-----+
| UNION     |   3 |
| UNION ALL |  15 |
+-----------+-----+
```

### UNION ALL 이 빠른 이유

"중복 제거"는 공짜가 아닙니다. **모든 행을 서로 비교해야** 하고, 그러려면 정렬하거나 해시 테이블을 만들어야 합니다. 실행계획을 보면 확연합니다.

```sql
EXPLAIN FORMAT=TREE
SELECT city FROM customers UNION SELECT shipping_city FROM orders;
```

**결과**
```
-> Table scan on <union temporary>  (cost=127..138 rows=630)
    -> Union materialize with deduplication  (cost=127..127 rows=630)
        -> Table scan on customers  (cost=3.25 rows=30)
        -> Table scan on orders  (cost=61 rows=600)
```

`Union materialize with deduplication` — **임시 테이블을 만들어** 630행을 다 넣고 중복을 제거한 뒤, 그 임시 테이블을 다시 스캔합니다.

```sql
EXPLAIN FORMAT=TREE
SELECT city FROM customers UNION ALL SELECT shipping_city FROM orders;
```

**결과**
```
-> Append  (cost=64.2 rows=630)
    -> Stream results  (cost=3.25 rows=30)
        -> Table scan on customers  (cost=3.25 rows=30)
    -> Stream results  (cost=61 rows=600)
        -> Table scan on orders  (cost=61 rows=600)
```

`Append` + `Stream results` — 임시 테이블이 **없습니다.** 읽는 즉시 클라이언트로 흘려보냅니다. 비용도 127 → 64로 절반입니다.

> 💡 **실무 팁**: **중복이 없다는 것을 알고 있다면 항상 `UNION ALL`** 을 쓰세요. 서로 다른 상태의 주문을 합치는 것처럼 애초에 겹칠 수 없는 경우가 대부분입니다. 습관적으로 `UNION` 을 쓰면 수백만 행에서 불필요한 정렬/해시 비용을 매번 지불하게 됩니다.

---

## 10-2. 규칙 : 컬럼 개수와 타입

### 컬럼 개수는 반드시 같아야 한다

```sql
SELECT customer_id, name FROM customers
UNION
SELECT product_id FROM products;
-- ERROR 1222 (21000): The used SELECT statements have a different number of columns
```

### 컬럼 이름은 첫 번째 SELECT 를 따른다

```sql
SELECT customer_id AS id, name AS label FROM customers WHERE customer_id <= 2
UNION ALL
SELECT product_id, name FROM products WHERE product_id <= 2;
```

**결과**
```
+----+-------------------------------+
| id | label                         |
+----+-------------------------------+
|  1 | 김민수                        |
|  2 | 이지은                        |
|  1 | 베이직 옥스퍼드 셔츠          |
|  2 | 슬림핏 치노 팬츠              |
+----+-------------------------------+
```

두 번째 SELECT 의 별칭은 완전히 무시됩니다.

### 타입이 달라도 에러가 안 난다 — 이게 함정

```sql
SELECT customer_id AS v FROM customers WHERE customer_id = 1
UNION ALL
SELECT email FROM customers WHERE customer_id = 1;
```

**결과**
```
+-----------------------+
| v                     |
+-----------------------+
| 1                     |
| kim.minsu@example.com |
+-----------------------+
```

`INT` 와 `VARCHAR` 를 합쳤는데 **에러가 안 납니다.** MySQL 이 알아서 공통 타입을 찾아 캐스팅합니다. 결과 컬럼이 무슨 타입이 됐는지 봅시다.

```sql
CREATE TEMPORARY TABLE s10_tmp_type AS
SELECT customer_id AS v FROM customers WHERE customer_id = 1
UNION ALL
SELECT email FROM customers WHERE customer_id = 1;
DESC s10_tmp_type;
DROP TEMPORARY TABLE s10_tmp_type;
```

**결과**
```
+-------+--------------+------+-----+---------+-------+
| Field | Type         | Null | Key | Default | Extra |
+-------+--------------+------+-----+---------+-------+
| v     | varchar(120) | NO   |     |         | NULL  |
+-------+--------------+------+-----+---------+-------+
```

`INT` 가 `VARCHAR(120)` 으로 승격됐습니다.

> ⚠️ **함정**: 컬럼 순서를 하나 빠뜨리거나 순서를 바꿔 써도 **타입이 호환되면 에러 없이 통과**합니다. 금액 자리에 수량이 들어가 있는 리포트가 몇 주째 돌아가는 사고가 여기서 나옵니다. 집합 연산을 쓸 때는 각 `SELECT` 의 컬럼 목록을 **세로로 줄맞춤해서** 눈으로 대조하는 습관을 들이세요.

---

## 10-3. ORDER BY / LIMIT 는 어디에 붙는가

이게 헷갈립니다. 규칙은 단순합니다.

**맨 끝의 `ORDER BY` / `LIMIT` 는 전체 결과에 적용됩니다.**

```sql
SELECT name, price FROM products WHERE category_id = 21
UNION ALL
SELECT name, price FROM products WHERE category_id = 22
ORDER BY price DESC;
```

**결과**
```
+-----------------------------+------------+
| name                        | price      |
+-----------------------------+------------+
| 게이밍 노트북 RTX4060       | 2190000.00 |
| 울트라북 14 i7/32GB         | 1790000.00 |
| 스마트폰 X20 Pro 512GB      | 1490000.00 |
| 울트라북 14 i5/16GB         | 1290000.00 |
| 스마트폰 X20 256GB          | 1150000.00 |
| 보급형 노트북 15            |  690000.00 |
| 스마트폰 A5 128GB           |  399000.00 |
+-----------------------------+------------+
```

노트북과 스마트폰이 뒤섞여 가격순으로 정렬됐습니다. 전체에 걸렸다는 뜻입니다.

**개별 SELECT 에 걸고 싶다면 괄호로 감쌉니다.**

```sql
(SELECT name, price FROM products WHERE category_id = 21 ORDER BY price DESC LIMIT 2)
UNION ALL
(SELECT name, price FROM products WHERE category_id = 22 ORDER BY price DESC LIMIT 2);
```

**결과**
```
+-----------------------------+------------+
| name                        | price      |
+-----------------------------+------------+
| 게이밍 노트북 RTX4060       | 2190000.00 |
| 울트라북 14 i7/32GB         | 1790000.00 |
| 스마트폰 X20 Pro 512GB      | 1490000.00 |
| 스마트폰 X20 256GB          | 1150000.00 |
+-----------------------------+------------+
```

각 카테고리에서 상위 2개씩 — 이것이 **"그룹별 Top-N"** 을 집합 연산으로 푸는 방법입니다(그룹 수가 적을 때만 쓸 만합니다).

둘을 조합할 수도 있습니다.

```sql
(SELECT name, price, '노트북' AS kind FROM products WHERE category_id = 21)
UNION ALL
(SELECT name, price, '스마트폰' FROM products WHERE category_id = 22)
ORDER BY price DESC
LIMIT 3;
```

**결과**
```
+-----------------------------+------------+--------------+
| name                        | price      | kind         |
+-----------------------------+------------+--------------+
| 게이밍 노트북 RTX4060       | 2190000.00 | 노트북       |
| 울트라북 14 i7/32GB         | 1790000.00 | 노트북       |
| 스마트폰 X20 Pro 512GB      | 1490000.00 | 스마트폰     |
+-----------------------------+------------+--------------+
```

> ⚠️ **함정**: 맨 끝 `ORDER BY` 에는 **첫 번째 SELECT 의 결과 컬럼명**만 쓸 수 있습니다. `ORDER BY p.price` 처럼 테이블 별칭을 붙이면 에러입니다. 두 번째 SELECT 의 별칭도 못 씁니다. 별칭을 붙였다면 그 별칭으로 정렬하세요.
> ```sql
> SELECT p.name AS pname, p.price AS pprice FROM products p WHERE p.category_id = 21
> UNION ALL
> SELECT p.name, p.price FROM products p WHERE p.category_id = 22
> ORDER BY pprice DESC LIMIT 3;   -- ← 첫 SELECT 의 별칭 pprice 사용
> ```

> ⚠️ **함정**: 괄호 없는 개별 `ORDER BY` 는 **의미가 없습니다.** MySQL 은 `UNION ALL` 중간의 `ORDER BY` 를(LIMIT 이 없으면) 최적화 과정에서 버립니다. 정렬을 기대하고 썼다가 순서가 뒤죽박죽 나오는 원인입니다.

---

## 10-4. INTERSECT (MySQL 8.0.31+)

**양쪽에 모두 존재**하는 행만 남깁니다. "10만원 이상 상품" ∩ "후기가 달린 상품"을 구해봅시다.

```sql
SELECT product_id FROM products WHERE price >= 100000
INTERSECT
SELECT product_id FROM reviews
ORDER BY product_id;
```

**결과**
```
+------------+
| product_id |
+------------+
|         11 |
|         14 |
|         15 |
|         18 |
|         21 |
|         34 |
|         35 |
+------------+
```

8.0.31 이전에는 `IN` 이나 `EXISTS` 로 풀었습니다. 결과는 동일합니다.

```sql
SELECT DISTINCT p.product_id
FROM products p
WHERE p.price >= 100000
  AND p.product_id IN (SELECT product_id FROM reviews)
ORDER BY p.product_id;
```

**결과**
```
+------------+
| product_id |
+------------+
|         11 |
|         14 |
|         15 |
|         18 |
|         21 |
|         34 |
|         35 |
+------------+
```

---

## 10-5. EXCEPT (MySQL 8.0.31+)

**왼쪽에는 있고 오른쪽에는 없는** 행만 남깁니다. 다른 DB에서는 `MINUS`(Oracle) 라고 부르기도 합니다.

```sql
SELECT product_id FROM products
EXCEPT
SELECT product_id FROM reviews
ORDER BY product_id
LIMIT 5;
```

**결과**
```
+------------+
| product_id |
+------------+
|          2 |
|          3 |
|          6 |
|          7 |
|          9 |
+------------+
... (총 24행)
```

이것은 Step 08 에서 `NOT EXISTS` 로 풀었던 문제와 정확히 같습니다. 개수를 대조해 봅시다.

```sql
SELECT COUNT(*) AS never_reviewed FROM (
    SELECT product_id FROM products
    EXCEPT
    SELECT product_id FROM reviews
) x;

SELECT COUNT(*) AS never_reviewed
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = p.product_id);
```

**결과**
```
+----------------+        +----------------+
| never_reviewed |        | never_reviewed |
+----------------+        +----------------+
|             24 |        |             24 |
+----------------+        +----------------+
```

> 💡 **실무 팁**: `EXCEPT` 는 **`NOT IN` 의 NULL 함정이 없습니다.** 집합 연산은 NULL 을 "같은 값"으로 취급하기 때문입니다(`NULL EXCEPT NULL` → 결과 없음). 8.0.31+ 환경이라면 안티 조인을 `EXCEPT` 로 쓰는 것도 좋은 선택입니다. 다만 **왼쪽 결과에서 중복이 제거된다**는 점은 기억하세요.

### INTERSECT ALL / EXCEPT ALL

`ALL` 을 붙이면 중복을 남깁니다. 중복이 남는 개수는 "양쪽 중 적은 쪽"(INTERSECT ALL) 또는 "왼쪽 개수 - 오른쪽 개수"(EXCEPT ALL) 입니다.

```sql
SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) a
INTERSECT ALL
SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 3) b;
```

**결과**
```
+---+
| n |
+---+
| 1 |
| 1 |    ← 양쪽에 1이 두 번씩 있으므로 두 번 남는다
+---+
```

```sql
SELECT n FROM (SELECT 1 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) a
EXCEPT ALL
SELECT n FROM (SELECT 1 AS n) b;
```

**결과**
```
+---+
| n |
+---+
| 1 |    ← 왼쪽 1이 2개, 오른쪽 1이 1개 → 1개 남는다
| 2 |
+---+
```

### 연산 우선순위

`INTERSECT` 가 `UNION` / `EXCEPT` 보다 **먼저** 평가됩니다(곱셈이 덧셈보다 먼저인 것과 같습니다).

```sql
(SELECT 1 AS n UNION ALL SELECT 2)
UNION
(SELECT 2 INTERSECT SELECT 3);      -- 안쪽은 빈 결과
```

**결과**
```
+---+
| n |
+---+
| 1 |
| 2 |
+---+
```

> 💡 **실무 팁**: 세 개 이상의 집합 연산을 섞을 때는 **항상 괄호를 명시**하세요. 우선순위를 외우는 것보다 괄호 하나 더 치는 게 싸게 먹힙니다.

---

## 10-6. FULL OUTER JOIN 흉내내기

MySQL 에는 `FULL OUTER JOIN` 이 **없습니다.** 표준 SQL 에는 있고 PostgreSQL 에도 있지만, MySQL 8.0.46 까지도 지원하지 않습니다. `UNION` 으로 흉내냅니다.

핵심 아이디어: `LEFT JOIN` (왼쪽 전부 + 매칭) ∪ `RIGHT JOIN` (오른쪽 전부 + 매칭) = 양쪽 전부. `UNION` 이 중복(양쪽 매칭된 행)을 제거해 줍니다.

```sql
SELECT c.category_id, c.name AS cat_name, p.product_id, p.name AS product_name
FROM categories c
LEFT JOIN products p ON p.category_id = c.category_id
UNION                                        -- ← UNION ALL 이면 안 된다!
SELECT c.category_id, c.name, p.product_id, p.name
FROM categories c
RIGHT JOIN products p ON p.category_id = c.category_id
ORDER BY category_id, product_id
LIMIT 8;
```

**결과**
```
+-------------+--------------+------------+-------------------------------+
| category_id | cat_name     | product_id | product_name                  |
+-------------+--------------+------------+-------------------------------+
|           1 | 패션         |       NULL | NULL                          |
|           2 | 디지털       |       NULL | NULL                          |
|           3 | 식품         |       NULL | NULL                          |
|           4 | 리빙         |       NULL | NULL                          |
|           5 | 도서         |       NULL | NULL                          |
|          11 | 남성의류     |          1 | 베이직 옥스퍼드 셔츠          |
|          11 | 남성의류     |          2 | 슬림핏 치노 팬츠              |
|          11 | 남성의류     |          3 | 라이트 다운 재킷              |
+-------------+--------------+------------+-------------------------------+
... (총 45행)
```

대분류(1~5)는 상품이 직접 달려 있지 않으므로 `NULL` 로 나옵니다. 이것이 `LEFT JOIN` 쪽에서 온 행입니다.

> ⚠️ **함정**: 반드시 **`UNION`(중복 제거)** 이어야 합니다. `UNION ALL` 을 쓰면 양쪽 모두에서 매칭된 행이 **두 번씩** 나옵니다.

### 짝 없는 행만 보고 싶다면

전체 FULL OUTER JOIN 이 아니라 "양쪽 어디에도 짝이 없는 행"만 필요할 때가 많습니다(데이터 정합성 검사 등). 이때는 `UNION ALL` 로 안티 조인 둘을 붙이면 됩니다 — 겹칠 수가 없으므로 중복 제거가 불필요하고, 훨씬 빠릅니다.

```sql
SELECT c.category_id, c.name AS cat_name, NULL AS product_id, NULL AS product_name
FROM categories c
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.category_id = c.category_id)
UNION ALL
SELECT NULL, NULL, p.product_id, p.name
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.category_id = p.category_id)
ORDER BY category_id;
```

**결과**
```
+-------------+-----------+------------+--------------+
| category_id | cat_name  | product_id | product_name |
+-------------+-----------+------------+--------------+
|           1 | 패션      |       NULL | NULL         |
|           2 | 디지털    |       NULL | NULL         |
|           3 | 식품      |       NULL | NULL         |
|           4 | 리빙      |       NULL | NULL         |
|           5 | 도서      |       NULL | NULL         |
+-------------+-----------+------------+--------------+
```

상품이 없는 대분류 5개. 반대쪽(카테고리가 없는 상품)은 FK 제약 덕분에 0건입니다.

---

## 10-7. 실무 패턴

### 서로 다른 소스를 하나의 타임라인으로

```sql
SELECT 'ORDER'   AS src, order_id   AS id, order_date AS ts, total_amount AS amount
FROM orders WHERE order_date >= '2025-12-01'
UNION ALL
SELECT 'PAYMENT', payment_id, paid_at, amount
FROM payments WHERE paid_at >= '2025-12-01'
ORDER BY ts
LIMIT 8;
```

**결과**
```
+---------+-----+---------------------+------------+
| src     | id  | ts                  | amount     |
+---------+-----+---------------------+------------+
| ORDER   | 453 | 2025-12-02 09:51:00 |   96000.00 |
| PAYMENT | 408 | 2025-12-02 11:29:00 |   96000.00 |
| ORDER   | 236 | 2025-12-03 20:32:00 | 3968900.00 |
| PAYMENT | 213 | 2025-12-03 22:33:00 | 3968900.00 |
| ORDER   |  19 | 2025-12-04 07:13:00 |  102800.00 |
| PAYMENT |  17 | 2025-12-04 07:37:00 |  102800.00 |
| ORDER   | 532 | 2025-12-05 04:04:00 | 1286000.00 |
| PAYMENT | 479 | 2025-12-05 05:01:00 | 1286000.00 |
+---------+-----+---------------------+------------+
```

`src` 같은 **구분 컬럼(discriminator)** 을 넣는 것이 핵심입니다. 없으면 어느 테이블에서 왔는지 알 수 없습니다.

### 합계 행 붙이기

```sql
SELECT status, COUNT(*) AS cnt, SUM(total_amount) AS amount
FROM orders
GROUP BY status
UNION ALL
SELECT '__TOTAL__', COUNT(*), SUM(total_amount)
FROM orders
ORDER BY cnt DESC;
```

**결과**
```
+-----------+-----+--------------+
| status    | cnt | amount       |
+-----------+-----+--------------+
| __TOTAL__ | 600 | 764598000.00 |
| DELIVERED | 240 | 326280000.00 |
| SHIPPED   | 120 | 162834000.00 |
| PAID      | 120 | 132630000.00 |
| CANCELLED |  60 |  47742000.00 |
| PENDING   |  60 |  95112000.00 |
+-----------+-----+--------------+
```

> 💡 **실무 팁**: 소계/합계 행은 `WITH ROLLUP`(Step 06)으로도 만들 수 있습니다. `UNION ALL` 방식은 **합계 행의 계산식을 자유롭게 바꿀 수 있다**는 장점이 있습니다(예: 합계 행에서는 다른 필터를 적용).

---

## 정리

| 연산 | 의미 | 중복 제거 | 최소 버전 |
|---|---|---|---|
| `UNION` | 합집합 | O (임시테이블 + 중복제거) | 모든 버전 |
| `UNION ALL` | 그냥 이어붙이기 | X (**Append, 스트리밍**) | 모든 버전 |
| `INTERSECT` | 교집합 | O | **8.0.31+** |
| `INTERSECT ALL` | 교집합, 중복 유지 | X | **8.0.31+** |
| `EXCEPT` | 차집합 (왼쪽 - 오른쪽) | O | **8.0.31+** |
| `EXCEPT ALL` | 차집합, 중복 유지 | X | **8.0.31+** |

| 규칙 | 내용 |
|---|---|
| 컬럼 개수 | **반드시 동일** (다르면 ERROR 1222) |
| 컬럼 이름 | **첫 번째 SELECT** 것을 따름 |
| 컬럼 타입 | 다르면 **조용히 공통 타입으로 캐스팅** ← 함정 |
| 맨 끝 `ORDER BY`/`LIMIT` | **전체 결과**에 적용. 첫 SELECT 의 별칭만 사용 가능 |
| 개별 `ORDER BY`/`LIMIT` | **괄호로 감싸야** 유효 |
| 우선순위 | `INTERSECT` > `UNION` = `EXCEPT` → **괄호를 쓰자** |
| FULL OUTER JOIN | 없음. `LEFT JOIN` **UNION**(ALL 아님!) `RIGHT JOIN` |

---

## 연습문제

`exercise.sql` 을 푸세요. 정답은 `solution.sql`.

1. VIP 고객의 도시와 GOLD 고객의 도시를 합치되, 중복 없이 출력
2. 위 문제를 `UNION ALL` 로 바꾸고 행 수 차이를 설명
3. 상품 카테고리 21과 23에서 각각 최고가 상품 1개씩 (괄호 + LIMIT)
4. `INTERSECT` 로 "후기가 달린 상품" ∩ "재고가 100개 이상인 상품"
5. `EXCEPT` 로 "주문된 적 있는 상품" - "후기가 달린 상품"
6. `EXCEPT` 와 `NOT EXISTS` 로 같은 결과를 내고 개수 비교
7. orders 와 payments 를 FULL OUTER JOIN 처럼 합치기
8. 등급별 고객 수에 `__TOTAL__` 합계 행 붙이기

---

## 다음 단계

→ [Step 11 — DML (INSERT / UPDATE / DELETE)](../step-11-dml/)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 구성됩니다. 먼저 `practice.sql` 로 본문 10-0 ~ 10-7 의 예제를 직접 실행하며 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸 8문제를 스스로 풀고, 마지막으로 `solution.sql` 로 답과 해설을 대조합니다. 세 파일 모두 `SELECT` 만 수행하므로 원본 데이터를 망가뜨릴 걱정 없이 반복 실행해도 됩니다.

### practice.sql

강의 본문에 나온 쿼리를 순서 그대로 담아 둔 **따라 하기용 스크립트**입니다. 파일 상단 주석의 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 통째로 돌려도 되지만, 처음 볼 때는 클라이언트에 한 블록씩 붙여 넣어 결과를 본문과 비교하는 편이 좋습니다.

- 맨 앞의 `[10-0] SELECT VERSION()` 을 **반드시 먼저** 실행하세요. `[10-12]` 이후의 `INTERSECT` / `EXCEPT` / `INTERSECT ALL` / `EXCEPT ALL` 은 **MySQL 8.0.31 이상**에서만 파싱됩니다. 8.0.30 이하라면 그 지점부터 문법 에러로 스크립트가 멈춥니다.
- `[10-4]` 블록(컬럼 개수 불일치 → `ERROR 1222`)은 **일부러 주석 처리**되어 있습니다. 스크립트를 통째로 실행할 때 에러로 중단되지 않게 하기 위함이며, 에러를 직접 보고 싶다면 주석을 풀고 그 문장만 따로 실행하세요.
- `[10-6]` 은 `INT` 컬럼(`customer_id`)과 `VARCHAR` 컬럼(`email`)을 `UNION ALL` 로 합치는, **에러 없이 조용히 통과하는 함정** 예제입니다. 바로 이어지는 `[10-7]` 이 `CREATE TEMPORARY TABLE s10_tmp_type AS ...` → `DESC` → `DROP TEMPORARY TABLE` 순서로 결과 타입이 `varchar(120)` 으로 승격됐음을 증명합니다. 임시 테이블은 세션 한정이며 마지막 줄에서 바로 삭제하므로 뒷정리를 따로 할 필요는 없습니다.
- `[10-19]` / `[10-20]` 의 `EXPLAIN FORMAT=TREE` 두 개가 이 스텝의 핵심 근거입니다. `UNION` 은 `Union materialize with deduplication`, `UNION ALL` 은 `Append` + `Stream results` 로 나옵니다. 반드시 두 계획을 나란히 놓고 비교하세요.
- `[10-9]` 처럼 **괄호로 감싼** 서브 SELECT 와 `[10-8]` 처럼 괄호 없이 맨 끝에만 `ORDER BY` 를 둔 쿼리를 이어서 실행하면, 정렬이 개별 SELECT 에 걸리는지 전체에 걸리는지 차이가 한눈에 보입니다.

```sql file="./practice.sql"
```

### exercise.sql

본문 "연습문제"의 8문제가 담긴 **빈 답안지**입니다. 각 문제는 주석 블록으로만 되어 있고 그 아래가 비어 있으니, 직접 쿼리를 적어 넣고 실행해 보세요. 아무것도 채우지 않은 채 그대로 실행하면 `USE shop;` 만 수행되고 아무 결과도 나오지 않습니다.

- Q1 → Q2 는 **같은 데이터에 `UNION` 과 `UNION ALL` 을 각각 적용**해 행 수 차이(4행 vs 13행)를 스스로 설명하게 하는 문제입니다. "왜 다른지 주석으로 설명할 것"이 요구사항이니 SQL 만 쓰고 넘어가지 마세요.
- Q3 은 "카테고리 21(노트북)과 23(주변기기)에서 각각 최고가 1개씩, 총 2행"입니다. 힌트대로 **괄호 + `ORDER BY` + `LIMIT 1`** 을 각 SELECT 에 걸어야 하며, 괄호를 빼면 10-3 에서 본 것처럼 `LIMIT` 이 전체에 걸려 1행만 나옵니다.
- Q4 / Q5 는 `INTERSECT` / `EXCEPT` 를 쓰라고 명시하므로 **8.0.31 이상 서버가 전제**입니다. 파일 머리말에도 같은 경고가 적혀 있습니다.
- Q6 은 Q5 를 `NOT EXISTS` 로 다시 풀어 **건수가 일치하는지** 확인하는 교차 검증 문제입니다. `order_items` 에는 같은 상품이 여러 번 등장하므로, `NOT EXISTS` 쪽에서는 `COUNT(DISTINCT ...)` 를 써야 `EXCEPT` 와 숫자가 맞습니다.
- Q7 은 `orders` 와 `payments` 로 FULL OUTER JOIN 을 흉내내는 문제이고, Q8 은 등급별 고객 수에 `'__TOTAL__'` 합계 행을 붙이는 문제입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 의 정답과, 왜 그렇게 푸는지에 대한 해설 주석이 함께 들어 있습니다. **먼저 스스로 풀어 본 뒤에** 열어 보세요. 답만 보는 것보다 주석에 적힌 "왜"를 읽는 것이 이 스텝의 목적입니다.

- A2 는 정답 쿼리 아래에 `→ 중복 제거가 필요 없다면 UNION ALL 이 항상 빠르다(임시테이블 없이 Append 로 스트리밍하기 때문)` 라는 결론을 못 박아 둡니다. 10-1 의 실행계획 비교와 짝을 이룹니다.
- A5 의 `SELECT product_id FROM order_items EXCEPT SELECT product_id FROM reviews` 는 `DISTINCT` 를 쓰지 않았는데도 중복이 사라집니다. **`EXCEPT` 가 왼쪽의 중복을 자동으로 제거**하기 때문이며, 주석에 그 이유가 적혀 있습니다.
- A6 주석의 3줄 비교(`EXCEPT` / `NOT EXISTS` / `NOT IN`)가 핵심입니다. 앞의 둘은 NULL 에 안전하지만 `NOT IN` 은 NULL 이 섞이면 0행이 되므로 쓰지 말라는 경고입니다.
- A7 주석은 이 스키마의 `payments.order_id` 에 **FK 가 걸려 있어 "주문 없는 결제"가 존재할 수 없다**는 점을 짚습니다. 즉 결과는 `LEFT JOIN` 과 같아지지만, `UNION`(ALL 아님) 으로 양쪽을 합치는 **패턴 자체를 익히는 것**이 목적입니다.
- A8 은 `0 AS sort_key` / `1` 이라는 **정렬용 보조 컬럼**을 만들고 바깥에서 `ORDER BY sort_key, cnt DESC` 로 감싸, 합계 행을 항상 맨 아래로 보냅니다. 10-7 의 예제처럼 `cnt DESC` 만 쓰면 합계(30)가 제일 커서 맨 위로 올라가 버리는 문제를 해결한 버전입니다.

```sql file="./solution.sql"
```
