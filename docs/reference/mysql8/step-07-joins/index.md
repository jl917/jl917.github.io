# Step 07 — 조인(JOIN)

> **학습 목표**
> - `INNER` / `LEFT` / `RIGHT` / `CROSS` / `SELF` JOIN 을 구분해서 쓴다
> - 여러 테이블을 잇는 **다중 조인**을 작성한다
> - **LEFT JOIN 에서 필터를 `ON` 에 두느냐 `WHERE` 에 두느냐**가 결과를 바꾸는 함정을 이해한다
> - NULL 확장을 이용한 **안티조인**(`LEFT JOIN ... IS NULL`)을 쓴다
> - MySQL 엔 **`FULL OUTER JOIN` 이 없다**는 것을 알고 `UNION` 으로 우회한다
>
> **선행 스텝**: [Step 06 — 집계함수와 GROUP BY](../step-06-aggregate-groupby/README.md)
> **예상 소요**: 70분

조인은 관계형 DB 의 심장입니다. 스키마 관계도는 실습 환경의 `sql/01_schema.sql` 상단 주석을 참고하세요.

---

## 7-1. INNER JOIN — 양쪽에 다 있는 것만

주문(`orders`)에는 `customer_id` 만 있고 고객 이름은 없습니다. 이름을 붙이려면 `customers` 와 이어야 합니다. **양쪽에 짝이 있는 행만** 남기는 것이 `INNER JOIN` 입니다.

```sql
SELECT
    o.order_id,
    o.order_date,
    c.name  AS 고객명,
    c.grade AS 등급,
    o.total_amount
FROM orders o
INNER JOIN customers c ON c.customer_id = o.customer_id
ORDER BY o.order_id
LIMIT 5;
```

**결과**
```
+----------+---------------------+-----------+--------+--------------+
| order_id | order_date          | 고객명    | 등급   | total_amount |
+----------+---------------------+-----------+--------+--------------+
|        1 | 2024-02-07 13:07:00 | 류하나    | GOLD   |   1836000.00 |
|        2 | 2024-03-15 02:14:00 | 정  훈    | GOLD   |   6663900.00 |
|        3 | 2024-04-21 15:21:00 | 안지수    | GOLD   |    658000.00 |
|        4 | 2024-05-28 04:28:00 | 한지호    | BRONZE |    837000.00 |
|        5 | 2024-07-04 17:35:00 | 배채영    | GOLD   |   1194000.00 |
+----------+---------------------+-----------+--------+--------------+
```

`ON c.customer_id = o.customer_id` 가 **조인 조건**입니다. "orders 의 customer_id 와 customers 의 customer_id 가 같은 행끼리 짝지어라". `INNER` 는 생략할 수 있습니다 — 그냥 `JOIN` 은 `INNER JOIN` 입니다.

> 💡 **실무 팁**: 조인이 등장하면 **모든 테이블에 별칭을 붙이고 모든 컬럼에 별칭을 접두사로** 다세요(`c.name`, `o.order_id`). 두 테이블에 같은 이름의 컬럼(`name` 등)이 있을 때 접두사가 없으면 `ERROR 1052: Column 'name' in field list is ambiguous` 가 납니다. 접두사는 "이 컬럼이 어디서 왔는지" 를 읽는 사람에게도 알려줍니다.

---

## 7-2. 다중 조인 — 여러 테이블을 잇기

"어떤 고객이 어떤 상품을 어느 카테고리에서 몇 개 샀나" 를 보려면 4개 테이블을 이어야 합니다. `orders → order_items → products → categories`, 그리고 고객 이름을 위해 `customers` 까지.

```sql
SELECT
    o.order_id,
    c.name        AS 고객,
    p.name        AS 상품,
    cat.name      AS 카테고리,
    oi.quantity   AS 수량,
    oi.unit_price AS 단가
FROM orders o
JOIN customers c    ON c.customer_id   = o.customer_id
JOIN order_items oi ON oi.order_id     = o.order_id
JOIN products p     ON p.product_id    = oi.product_id
JOIN categories cat ON cat.category_id = p.category_id
ORDER BY o.order_id, p.product_id
LIMIT 8;
```

**결과**
```
+----------+-----------+-------------------------------+--------------+--------+------------+
| order_id | 고객      | 상품                          | 카테고리     | 수량   | 단가       |
+----------+-----------+-------------------------------+--------------+--------+------------+
|        1 | 류하나    | 27인치 4K 모니터              | 주변기기     |      3 |  459000.00 |
|        1 | 류하나    | 원목 4인 식탁                 | 가구         |      1 |  459000.00 |
|        2 | 정  훈    | 베이직 옥스퍼드 셔츠          | 남성의류     |      2 |   39000.00 |
|        2 | 정  훈    | 게이밍 노트북 RTX4060         | 노트북       |      3 | 2190000.00 |
|        2 | 정  훈    | 콜드브루 원액 1L              | 가공식품     |      1 |   15900.00 |
|        3 | 안지수    | 인체공학 사무용 의자          | 가구         |      2 |  329000.00 |
|        4 | 한지호    | 슬림핏 치노 팬츠              | 남성의류     |      3 |   49000.00 |
|        4 | 한지호    | 보급형 노트북 15              | 노트북       |      1 |  690000.00 |
+----------+-----------+-------------------------------+--------------+--------+------------+
```

`order_id = 1` 이 두 줄인 것에 주목하세요. **주문 1건에 상품이 2개**(1:N)라서, 조인 결과에서 주문 헤더 정보(고객명 등)가 상품 수만큼 **반복**됩니다. 이게 다음 함정으로 이어집니다.

> ⚠️ **함정 (행 뻥튀기, fan-out)**: 1:N 조인은 "왼쪽 행 × 매칭되는 오른쪽 행 수" 만큼 행을 만듭니다. 여기에 `SUM(o.total_amount)` 를 걸면 주문 금액이 상품 개수만큼 **중복 합산**됩니다. 주문 1의 total_amount 가 2번 더해지는 식이죠. 집계 전에 "지금 한 행이 무엇의 단위인가?" 를 항상 자문하세요. 방어법은 7-11 에서 다룹니다.

---

## 7-3. LEFT JOIN — 왼쪽은 모두 남긴다

`INNER JOIN` 은 짝이 없으면 버립니다. 하지만 "상품이 하나도 없는 카테고리" 처럼 **짝이 없는 쪽도 보고 싶을 때**가 많습니다. `LEFT JOIN` 은 왼쪽 테이블의 행을 **전부** 남기고, 오른쪽에 짝이 없으면 그 자리를 **NULL 로 채웁니다**(NULL 확장).

우리 카테고리는 2단계입니다. 대분류 5개(패션, 디지털 ...)에는 상품이 직접 매달려 있지 않습니다(상품은 소분류에만).

```sql
SELECT
    cat.category_id,
    cat.name      AS 카테고리,
    p.product_id,
    p.name        AS 상품명
FROM categories cat
LEFT JOIN products p ON p.category_id = cat.category_id
WHERE cat.parent_id IS NULL     -- 대분류만
ORDER BY cat.category_id;
```

**결과**
```
+-------------+--------------+------------+-----------+
| category_id | 카테고리     | product_id | 상품명    |
+-------------+--------------+------------+-----------+
|           1 | 패션         |       NULL | NULL      |
|           2 | 디지털       |       NULL | NULL      |
|           3 | 식품         |       NULL | NULL      |
|           4 | 리빙         |       NULL | NULL      |
|           5 | 도서         |       NULL | NULL      |
+-------------+--------------+------------+-----------+
```

카테고리는 남았고, 짝이 없는 상품 컬럼은 NULL 이 되었습니다. `INNER JOIN` 이었다면 이 5줄은 통째로 사라졌을 겁니다.

### COUNT 의 함정 — LEFT JOIN 에서는 COUNT(*) 를 쓰지 마라

"카테고리별 상품 수"를 셀 때, 상품이 0개인 카테고리도 `0` 으로 보이길 원합니다.

```sql
SELECT
    cat.category_id,
    cat.name            AS 카테고리,
    COUNT(p.product_id) AS 상품수,
    COUNT(*)            AS `COUNT(*)_함정`
FROM categories cat
LEFT JOIN products p ON p.category_id = cat.category_id
GROUP BY cat.category_id, cat.name
ORDER BY 상품수, cat.category_id
LIMIT 8;
```

**결과**
```
+-------------+--------------+-----------+-----------------+
| category_id | 카테고리     | 상품수    | COUNT(*)_함정   |
+-------------+--------------+-----------+-----------------+
|           1 | 패션         |         0 |               1 |
|           2 | 디지털       |         0 |               1 |
|           3 | 식품         |         0 |               1 |
|           4 | 리빙         |         0 |               1 |
|           5 | 도서         |         0 |               1 |
|          52 | 소설         |         1 |               1 |
|          13 | 신발         |         3 |               3 |
|          22 | 스마트폰     |         3 |               3 |
+-------------+--------------+-----------+-----------------+
```

> ⚠️ **함정**: 패션 카테고리의 상품 수는 `0` 이어야 하는데 **`COUNT(*)` 는 `1`** 을 반환합니다. NULL 확장으로 "상품이 전부 NULL 인 행" 이 1줄 생겼고, `COUNT(*)` 는 그 행도 세기 때문입니다. **`COUNT(오른쪽 테이블의 컬럼)`** 을 쓰면 그 컬럼이 NULL 인 행은 안 세므로 올바른 `0` 이 나옵니다. **LEFT JOIN 뒤의 COUNT 는 반드시 오른쪽 테이블 컬럼을 대상으로 하세요.**

---

## 7-4. ON vs WHERE — LEFT JOIN 필터 위치의 함정 (이 스텝의 핵심)

이건 실무에서 가장 많이 틀리는 조인 함정입니다.

"고객별 배송완료(DELIVERED) 주문" 을 보려고 합니다. 조건 `status = 'DELIVERED'` 를 **`ON` 에 두는 것**과 **`WHERE` 에 두는 것**은 결과가 다릅니다.

```sql
-- (A) 조건을 ON 에: 조인 짝을 만들 때만 적용. 왼쪽(고객)은 전부 보존된다.
SELECT COUNT(*) AS 조건이_ON
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
      AND o.status = 'DELIVERED';
```

**결과**
```
+--------------+
| 조건이_ON    |
+--------------+
|          258 |
+--------------+
```

```sql
-- (B) 조건을 WHERE 에: 조인 "후" 적용. NULL 확장된 행이 탈락 → 사실상 INNER JOIN.
SELECT COUNT(*) AS 조건이_WHERE
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
WHERE o.status = 'DELIVERED';
```

**결과**
```
+-----------------+
| 조건이_WHERE    |
+-----------------+
|             240 |
+-----------------+
```

**258 vs 240.** 18 행이 차이납니다. 무슨 일이 벌어진 걸까요?

- **(A) `ON` 에 두면**: `status='DELIVERED'` 는 오른쪽 행을 **매칭할지 말지** 결정하는 데만 쓰입니다. 배송완료 주문이 없는 고객도 LEFT JOIN 규칙에 따라 **NULL 확장으로 한 줄 남습니다**. 그래서 240(배송완료 주문) + 18(배송완료 없는 고객) = **258**.
- **(B) `WHERE` 에 두면**: 조인이 다 끝난 뒤 `status='DELIVERED'` 로 거릅니다. NULL 확장된 행의 `status` 는 NULL 이고 `NULL = 'DELIVERED'` 는 UNKNOWN 이라 **탈락**합니다. 결국 배송완료 주문만 남아 **240** — LEFT JOIN 이 **INNER JOIN 으로 퇴화**했습니다.

배송완료 주문이 없는 고객(예: 주문이 전부 취소된 강소라, customer_id=6)을 직접 봅시다.

```sql
SELECT c.customer_id, c.name, o.order_id, o.status
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
      AND o.status = 'DELIVERED'
WHERE c.customer_id = 6;
```

**결과**
```
+-------------+-----------+----------+--------+
| customer_id | name      | order_id | status |
+-------------+-----------+----------+--------+
|           6 | 강소라    |     NULL | NULL   |
+-------------+-----------+----------+--------+
```

강소라는 배송완료 주문이 없지만 **NULL 확장으로 한 줄 남았습니다.** 만약 이 조건을 `WHERE` 로 옮기면 강소라는 결과에서 사라집니다.

> ⚠️ **핵심 규칙**: **LEFT JOIN 에서 오른쪽 테이블 조건은 `ON` 에, 왼쪽 테이블 조건은 `WHERE` 에.**
> - "모든 고객 + 그들의 배송완료 주문(없으면 NULL)" 을 원하면 → 조건을 **`ON`** 에.
> - "배송완료 주문이 있는 고객만" 을 원하면 → 조건을 **`WHERE`** 에 (또는 그냥 INNER JOIN).
>
> 실수의 전형: LEFT JOIN 을 써놓고 오른쪽 테이블 조건을 `WHERE` 에 걸어서, 자기도 모르게 INNER JOIN 을 만들어 놓고 "왜 LEFT JOIN 인데 행이 안 남지?" 라고 헤매는 것. 예외: `WHERE o.order_id IS NULL` 처럼 **NULL 확장을 일부러 노리는** 경우는 정상입니다(다음 절).

---

## 7-5. 안티조인 — LEFT JOIN ... IS NULL

방금 본 NULL 확장을 **거꾸로 이용**하면 "짝이 없는 행" 만 골라낼 수 있습니다. `LEFT JOIN` 후 `WHERE 오른쪽PK IS NULL`. 이것을 **안티조인(anti-join)** 이라 합니다.

우리 데이터에서 `PENDING` 주문은 결제(payments)가 없습니다. "결제가 없는 주문" 을 안티조인으로 찾아봅시다.

```sql
SELECT o.order_id, o.status, o.total_amount
FROM orders o
LEFT JOIN payments pay ON pay.order_id = o.order_id
WHERE pay.payment_id IS NULL      -- 짝이 없었던 행만
ORDER BY o.order_id
LIMIT 8;
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
|       57 | PENDING |   3580000.00 |
|       67 | PENDING |   1627000.00 |
|       77 | PENDING |    694000.00 |
+----------+---------+--------------+
```

전부 `PENDING` 입니다. 개수가 실제 PENDING 주문 수와 일치하는지 검산합니다.

```sql
SELECT
    (SELECT COUNT(*) FROM orders o
       LEFT JOIN payments pay ON pay.order_id = o.order_id
      WHERE pay.payment_id IS NULL)  AS 안티조인_결과,
    (SELECT COUNT(*) FROM orders WHERE status = 'PENDING') AS PENDING_주문수;
```

**결과**
```
+---------------------+-------------------+
| 안티조인_결과       | PENDING_주문수    |
+---------------------+-------------------+
|                  60 |                60 |
+---------------------+-------------------+
```

`60 = 60`. 정확합니다. [Step 05](../step-05-where-operators/README.md) 에서 배운 `NOT EXISTS`, `NOT IN` 과 함께 **안티조인의 세 가지 표현**이 완성되었습니다.

```sql
-- 후기를 한 번도 안 남긴 고객 (Step 05 의 NOT EXISTS 를 조인 버전으로)
SELECT c.customer_id, c.name, c.grade
FROM customers c
LEFT JOIN reviews r ON r.customer_id = c.customer_id
WHERE r.review_id IS NULL
ORDER BY c.customer_id
LIMIT 8;
```

**결과**
```
+-------------+-----------+--------+
| customer_id | name      | grade  |
+-------------+-----------+--------+
|           2 | 이지은    | GOLD   |
|           3 | 박철수    | SILVER |
|           4 | 최영희    | BRONZE |
|           5 | 정  훈    | GOLD   |
|           6 | 강소라    | VIP    |
|           7 | 윤대현    | BRONZE |
|           8 | 임수진    | SILVER |
|           9 | 한지호    | BRONZE |
+-------------+-----------+--------+
... (총 26행)
```

> 💡 **실무 팁 — 안티조인 3형제 중 무엇을 쓰나**:
> - `NOT EXISTS` : **기본값으로 추천.** NULL 에 안전하고 옵티마이저가 잘 처리.
> - `LEFT JOIN ... IS NULL` : 짝이 없는 쪽의 **컬럼도 함께 SELECT** 해야 할 때 자연스러움. 단, `IS NULL` 대상은 반드시 **NOT NULL 컬럼(대개 PK)** 이어야 함 — NULL 이 가능한 컬럼을 쓰면 "짝은 있는데 그 컬럼이 NULL 인 행" 까지 딸려온다.
> - `NOT IN (서브쿼리)` : ⚠️ NULL 하나면 전체가 빈 결과. 상수 리스트에만.
>
> 세 방법의 성능은 MySQL 8 에서 대체로 비슷하지만, 의미가 가장 명확한 `NOT EXISTS` 를 기본으로 삼으세요.

---

## 7-6. SELF JOIN — 같은 테이블을 두 번

`employees` 는 `manager_id → employee_id` 로 **자기 자신을 참조**합니다. 사원과 그의 관리자를 나란히 보려면 같은 테이블을 두 번, **다른 별칭으로** 조인합니다.

```sql
SELECT
    e.employee_id,
    e.name     AS 사원,
    e.position AS 직급,
    m.name     AS 관리자,
    m.position AS 관리자_직급
FROM employees e
LEFT JOIN employees m ON m.employee_id = e.manager_id
ORDER BY e.employee_id
LIMIT 10;
```

**결과**
```
+-------------+-----------+-----------+-----------+------------------+
| employee_id | 사원      | 직급      | 관리자    | 관리자_직급      |
+-------------+-----------+-----------+-----------+------------------+
|           1 | 정한별    | CEO       | NULL      | NULL             |
|           2 | 김코드    | 본부장    | 정한별    | CEO              |
|           3 | 이세일    | 본부장    | 정한별    | CEO              |
|           4 | 오지원    | 본부장    | 정한별    | CEO              |
|           5 | 박서버    | 팀장      | 김코드    | 본부장           |
|           6 | 최화면    | 팀장      | 김코드    | 본부장           |
|           7 | 강매출    | 팀장      | 이세일    | 본부장           |
|           8 | 윤사람    | 팀장      | 오지원    | 본부장           |
|           9 | 한백엔    | 시니어    | 박서버    | 팀장             |
|          10 | 임쿼리    | 주니어    | 박서버    | 팀장             |
+-------------+-----------+-----------+-----------+------------------+
```

CEO(정한별)는 관리자가 없어서 NULL 입니다. `LEFT JOIN` 을 썼기에 CEO 도 남았습니다. `INNER JOIN` 이었다면 CEO 가 사라졌을 겁니다.

SELF JOIN 은 "같은 그룹 안에서 서로 비교" 할 때도 씁니다. "나보다 같은 부서에서 급여가 높은 사람이 몇 명인가" (= 부서 내 급여 순위 - 1).

```sql
SELECT
    e.name   AS 사원,
    e.dept   AS 부서,
    e.salary AS 급여,
    COUNT(h.employee_id) AS 나보다_높은_사람수
FROM employees e
LEFT JOIN employees h
       ON h.dept = e.dept
      AND h.salary > e.salary
GROUP BY e.employee_id, e.name, e.dept, e.salary
ORDER BY e.dept, e.salary DESC
LIMIT 10;
```

**결과**
```
+-----------+--------------+------------+----------------------------+
| 사원      | 부서         | 급여       | 나보다_높은_사람수         |
+-----------+--------------+------------+----------------------------+
| 김코드    | 개발본부     | 9500000.00 |                          0 |
| 박서버    | 개발본부     | 7200000.00 |                          1 |
| 최화면    | 개발본부     | 7000000.00 |                          2 |
| 한백엔    | 개발본부     | 5800000.00 |                          3 |
| 조리액    | 개발본부     | 5600000.00 |                          4 |
| 임쿼리    | 개발본부     | 4200000.00 |                          5 |
| 서인덱    | 개발본부     | 4000000.00 |                          6 |
| 남뷰어    | 개발본부     | 3900000.00 |                          7 |
| 오지원    | 경영지원     | 8800000.00 |                          0 |
| 윤사람    | 경영지원     | 6500000.00 |                          1 |
+-----------+--------------+------------+----------------------------+
```

"나보다 높은 사람 수 + 1" 이 곧 부서 내 급여 순위입니다. 여기서도 `LEFT JOIN` 이 필수입니다 — 부서 1등은 "나보다 높은 사람" 이 없어서 매칭이 0건인데, INNER JOIN 이면 1등이 결과에서 사라집니다.

> 💡 **참고**: 이런 "그룹 내 순위" 는 MySQL 8 의 **윈도우 함수**(`RANK() OVER (PARTITION BY dept ORDER BY salary DESC)`)로 훨씬 간결하게 쓸 수 있습니다. Step 12 에서 다룹니다. SELF JOIN 은 윈도우 함수가 없던 시절의 정석이었고, 지금도 원리를 이해하는 데 좋습니다.

---

## 7-7. CROSS JOIN — 모든 조합

`CROSS JOIN` 은 조건 없이 두 테이블의 **모든 조합(곱집합)** 을 만듭니다. `A` 가 m행, `B` 가 n행이면 결과는 m×n 행입니다.

```sql
SELECT g.grade, ct.city
FROM (SELECT DISTINCT grade FROM customers) g
CROSS JOIN (SELECT DISTINCT city FROM customers WHERE city IN ('서울','부산','인천')) ct
ORDER BY g.grade, ct.city;
```

**결과** (4등급 × 3도시 = 12행)
```
+--------+--------+
| grade  | city   |
+--------+--------+
| BRONZE | 부산   |
| BRONZE | 서울   |
| BRONZE | 인천   |
| SILVER | 부산   |
| SILVER | 서울   |
| SILVER | 인천   |
| GOLD   | 부산   |
| ...    | ...    |
+--------+--------+
... (총 12행)
```

실전에서 CROSS JOIN 이 빛나는 곳은 **"빈 조합 채우기"** 입니다. 리포트에서 "데이터가 0인 칸"도 0으로 보여야 할 때, 모든 조합을 먼저 만들고 실제 데이터를 LEFT JOIN 합니다.

```sql
SELECT
    g.grade,
    ct.city,
    COUNT(c.customer_id) AS 고객수
FROM (SELECT DISTINCT grade FROM customers) g
CROSS JOIN (SELECT DISTINCT city FROM customers) ct
LEFT JOIN customers c ON c.grade = g.grade AND c.city = ct.city
GROUP BY g.grade, ct.city
HAVING COUNT(c.customer_id) > 0
ORDER BY g.grade, 고객수 DESC
LIMIT 10;
```

**결과** (일부)
```
+--------+--------+-----------+
| grade  | city   | 고객수    |
+--------+--------+-----------+
| BRONZE | 울산   |         2 |
| BRONZE | 수원   |         2 |
| BRONZE | 부산   |         2 |
| BRONZE | 서울   |         1 |
| ...    | ...    |    ...    |
+--------+--------+-----------+
```

(위에선 `HAVING > 0` 으로 걸렀지만, 이 조건을 빼면 "GOLD 등급 울산 고객 0명" 같은 빈 조합도 0으로 나옵니다 — 달력/매트릭스 리포트의 핵심 기법.)

> ⚠️ **함정**: `CROSS JOIN` 을 **실수로** 만드는 것이 진짜 위험합니다. `FROM a, b` 로 콤마 조인을 쓰고 `WHERE` 에 조인 조건을 빠뜨리면 곱집합이 터집니다. 100만 × 100만 = 1조 행. 서버가 멈춥니다. **콤마 조인 대신 항상 명시적 `JOIN ... ON` 을 쓰세요.** ON 을 빼먹으면 문법 에러라도 나서 사고를 막아줍니다 (CROSS JOIN 만 ON 없이 허용).

---

## 7-8. USING — 조인 컬럼 이름이 같을 때

양쪽 조인 컬럼의 **이름이 완전히 같으면** `ON a.x = b.x` 대신 `USING (x)` 로 줄일 수 있습니다.

```sql
SELECT customer_id, o.order_id, o.total_amount
FROM orders o
JOIN customers c USING (customer_id)
ORDER BY o.order_id
LIMIT 5;
```

**결과**
```
+-------------+----------+--------------+
| customer_id | order_id | total_amount |
+-------------+----------+--------------+
|          18 |        1 |   1836000.00 |
|           5 |        2 |   6663900.00 |
|          22 |        3 |    658000.00 |
|           9 |        4 |    837000.00 |
|          26 |        5 |   1194000.00 |
+-------------+----------+--------------+
```

주목할 점: `USING` 으로 조인한 컬럼은 결과에 **한 번만** 나옵니다. 그래서 위에서 `customer_id` 를 접두사 없이 그냥 썼습니다(`o.customer_id` 도 `c.customer_id` 도 아닌 통합된 하나). `SELECT *` 를 하면 그 컬럼이 맨 앞에 한 번만 옵니다.

> 💡 **실무 팁**: `USING` 은 깔끔하지만, 조인 컬럼 이름이 양쪽 정확히 같을 때만 됩니다. 우리 스키마는 `orders.customer_id` / `customers.customer_id` 처럼 일관되게 이름을 맞췄기에 `USING` 이 잘 먹힙니다. 이름이 다르면(`user_id` vs `id`) `ON` 을 써야 합니다. 팀 스타일에 따라 `ON` 으로 통일하는 곳도 많습니다(명시성 선호).

---

## 7-9. RIGHT JOIN — 방향만 바꾼 LEFT

`RIGHT JOIN` 은 **오른쪽** 테이블을 전부 남깁니다. `LEFT` 의 거울상일 뿐입니다.

```sql
SELECT
    p.product_id,
    p.name,
    COUNT(oi.order_item_id) AS 판매_횟수
FROM order_items oi
RIGHT JOIN products p ON p.product_id = oi.product_id
GROUP BY p.product_id, p.name
ORDER BY 판매_횟수 ASC, p.product_id
LIMIT 5;
```

**결과**
```
+------------+-------------------------------+---------------+
| product_id | name                          | 판매_횟수     |
+------------+-------------------------------+---------------+
|          1 | 베이직 옥스퍼드 셔츠          |            30 |
|          2 | 슬림핏 치노 팬츠              |            30 |
|          3 | 라이트 다운 재킷              |            30 |
|          4 | 울 니트 스웨터                |            30 |
|          5 | 플리츠 롱스커트               |            30 |
+------------+-------------------------------+---------------+
```

이 쿼리는 "판매된 적 없는 상품(판매 횟수 0)도 보이게" 하려고 products 를 다 남긴 것입니다(우리 데이터에선 40개 상품이 모두 팔려서 0 은 없습니다).

> 💡 **실무 팁**: **RIGHT JOIN 은 거의 쓰지 마세요.** 위 쿼리는 `FROM products p LEFT JOIN order_items oi ...` 로 쓰는 게 훨씬 자연스럽습니다. 사람은 "왼쪽에서 오른쪽으로" 읽으므로, "전부 남길 테이블을 FROM 에 놓고 LEFT JOIN" 하는 흐름이 이해하기 쉽습니다. RIGHT JOIN 은 코드를 거꾸로 읽게 만듭니다. 실무 코드베이스에서 RIGHT JOIN 을 보면 대개 LEFT 로 리팩터링할 후보입니다.

---

## 7-10. FULL OUTER JOIN 이 없다 — UNION 으로 우회

`FULL OUTER JOIN` 은 "양쪽 다 남기고, 짝 없으면 NULL" — LEFT 와 RIGHT 를 합친 것입니다. PostgreSQL/Oracle 엔 있지만 **MySQL 에는 없습니다.** 쓰려고 하면 문법 에러입니다.

```sql
SELECT * FROM a FULL OUTER JOIN b ON ...;    -- ERROR 1064 (문법 에러)
```

대신 **`(LEFT JOIN) UNION (RIGHT JOIN)`** 으로 만듭니다. `UNION` 이 중복(양쪽 다 매칭된 행)을 자동으로 제거해 줍니다.

"2024년 1분기에 주문한 고객" 과 "2025년 1분기에 주문한 고객" 을 비교합시다. 한쪽에만 있는 고객까지 전부 보려면 FULL OUTER JOIN 이 필요합니다.

```sql
WITH
  q2024 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2024-01-01' AND order_date < '2024-04-01'),
  q2025 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2025-01-01' AND order_date < '2025-04-01')
SELECT a.customer_id AS `2024_1분기`, b.customer_id AS `2025_1분기`
FROM q2024 a
LEFT JOIN q2025 b ON b.customer_id = a.customer_id
UNION
SELECT a.customer_id, b.customer_id
FROM q2024 a
RIGHT JOIN q2025 b ON b.customer_id = a.customer_id
ORDER BY `2024_1분기`, `2025_1분기`
LIMIT 6;
```

**결과**
```
+--------------+--------------+
| 2024_1분기   | 2025_1분기   |
+--------------+--------------+
|         NULL |           25 |     ← 2025 1분기에만 주문 (25번 고객)
|            1 |            1 |
|            2 |            2 |
|            3 |            3 |
|            4 |            4 |
|            5 |            5 |
+--------------+--------------+
```

첫 행의 `2024_1분기` 가 NULL 입니다 — 25번 고객은 2025 1분기에만 주문했고 2024 1분기엔 없었다는 뜻입니다. "한쪽에만 있는 고객" (대칭 차집합)만 뽑으면 이렇게 됩니다.

```sql
WITH
  q2024 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2024-01-01' AND order_date < '2024-04-01'),
  q2025 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2025-01-01' AND order_date < '2025-04-01')
SELECT a.customer_id AS `2024만`, b.customer_id AS `2025만`
FROM q2024 a LEFT JOIN q2025 b ON b.customer_id = a.customer_id
WHERE b.customer_id IS NULL
UNION
SELECT a.customer_id, b.customer_id
FROM q2024 a RIGHT JOIN q2025 b ON b.customer_id = a.customer_id
WHERE a.customer_id IS NULL;
```

**결과**
```
+---------+---------+
| 2024만  | 2025만  |
+---------+---------+
|      15 |    NULL |     ← 15번은 2024 1분기에만
|    NULL |      25 |     ← 25번은 2025 1분기에만
+---------+---------+
```

> 💡 **실무 팁**: `UNION` 은 중복 제거를 위해 정렬/해시 작업을 합니다. 두 쿼리 결과에 중복이 없다고 **확신**하면 `UNION ALL` 이 더 빠릅니다. 하지만 FULL OUTER JOIN 우회에서는 "양쪽 매칭된 행" 이 LEFT 결과와 RIGHT 결과 양쪽에 나타나므로 **반드시 `UNION`**(중복 제거)을 써야 합니다. `UNION ALL` 을 쓰면 매칭된 행이 두 번씩 나옵니다.

---

## 7-11. 종합 — LEFT JOIN 여러 개 + fan-out 방어

"고객별 주문/결제/후기 요약" 을 한 번에 뽑아봅시다. 1:N 관계를 **세 개나** 동시에 LEFT JOIN 하면 행이 곱해집니다. `COUNT(DISTINCT)` 로 방어합니다.

```sql
SELECT
    c.customer_id,
    c.name                          AS 고객,
    c.grade                         AS 등급,
    COUNT(DISTINCT o.order_id)      AS 주문수,
    COUNT(DISTINCT pay.payment_id)  AS 결제수,
    COUNT(DISTINCT r.review_id)     AS 후기수
FROM customers c
LEFT JOIN orders   o   ON o.customer_id = c.customer_id
LEFT JOIN payments pay ON pay.order_id  = o.order_id
LEFT JOIN reviews  r   ON r.customer_id = c.customer_id
GROUP BY c.customer_id, c.name, c.grade
ORDER BY 후기수 DESC, 주문수 DESC
LIMIT 8;
```

**결과**
```
+-------------+-----------+--------+-----------+-----------+-----------+
| customer_id | 고객      | 등급   | 주문수    | 결제수    | 후기수    |
+-------------+-----------+--------+-----------+-----------+-----------+
|           1 | 김민수    | VIP    |        20 |        20 |        20 |
|          28 | 심준호    | SILVER |        20 |        20 |        20 |
|          25 | 양현우    | VIP    |        20 |        20 |        20 |
|          22 | 안지수    | GOLD   |        20 |        20 |        20 |
|           2 | 이지은    | GOLD   |        20 |        20 |         0 |
|           3 | 박철수    | SILVER |        20 |        20 |         0 |
|          30 | 하준서    | GOLD   |        20 |         0 |         0 |
|           5 | 정  훈    | GOLD   |        20 |        20 |         0 |
+-------------+-----------+--------+-----------+-----------+-----------+
```

> ⚠️ **함정 (fan-out 재확인)**: 만약 위에서 `COUNT(o.order_id)` (DISTINCT 없이)를 썼다면, `payments` 와 `reviews` 조인으로 `orders` 행이 여러 번 복제되어 **주문 수가 부풀려집니다.** 여러 1:N 을 한 쿼리에서 집계할 때는 (1) `COUNT(DISTINCT ...)` 로 방어하거나, (2) 각 집계를 **서브쿼리로 따로** 계산해 붙이는 게 안전합니다. 특히 `SUM` 은 DISTINCT 로도 완전히 못 고칩니다(같은 값이 여러 주문에 있으면 DISTINCT 가 지워버림) — 이럴 땐 반드시 서브쿼리 분리나 상관 서브쿼리를 쓰세요. 자세한 건 Step 11(서브쿼리)에서.

> 💡 **하준서(30번)의 결제수가 0** 인 게 보이나요? 하준서의 주문은 전부 PENDING 이라 결제가 없습니다. LEFT JOIN 이라 주문수는 20 으로 남으면서 결제수만 0 이 되었습니다 — INNER JOIN 이었다면 하준서가 통째로 빠졌을 겁니다.

---

## 정리

| 조인 | 남기는 것 | 대표 용도 |
|---|---|---|
| `INNER JOIN` (= `JOIN`) | 양쪽에 짝이 있는 행만 | 관련 데이터 결합 |
| `LEFT JOIN` | 왼쪽 전부 + 오른쪽(없으면 NULL) | "없는 것도 0으로", 안티조인 |
| `RIGHT JOIN` | 오른쪽 전부 + 왼쪽 | 거의 안 씀 (LEFT 로 뒤집기) |
| `CROSS JOIN` | 모든 조합 (m×n) | 빈 조합 채우기, 매트릭스 |
| `SELF JOIN` | 같은 테이블 두 번 | 계층(관리자), 그룹 내 비교 |
| `LEFT JOIN ... IS NULL` | 짝이 **없는** 왼쪽 행 | 안티조인 |
| `(LEFT) UNION (RIGHT)` | 양쪽 전부 | FULL OUTER JOIN 우회 |

**핵심 함정 3가지**

1. **ON vs WHERE**: LEFT JOIN 에서 오른쪽 조건을 `WHERE` 에 걸면 INNER JOIN 으로 퇴화한다. 오른쪽 조건은 `ON` 에, 왼쪽 조건은 `WHERE` 에.
2. **LEFT JOIN + COUNT**: `COUNT(*)` 는 NULL 확장 행도 세어 0을 1로 만든다. `COUNT(오른쪽컬럼)` 을 써라.
3. **fan-out**: 여러 1:N 을 조인하면 행이 곱해진다. `COUNT(DISTINCT)` 또는 서브쿼리 분리로 방어.

**MySQL 특이사항**: `FULL OUTER JOIN` 없음 → UNION 우회. `USING` 컬럼은 결과에 한 번만.

---

## 연습문제

1. `orders` 와 `customers` 를 INNER JOIN 해서, **서울에 사는 고객의 주문** 중 금액 상위 5건을 조회하세요. (주문번호, 고객명, 금액)
2. `products` 를 기준으로 `categories` 를 LEFT JOIN 해서, 각 상품의 **카테고리명과 상위 카테고리명**을 함께 보이세요. (힌트: categories 를 두 번 조인 — 소분류용, 대분류용)
3. **주문을 한 번도 안 한 고객**이 있는지 안티조인(`LEFT JOIN ... IS NULL`)으로 확인하세요. (우리 데이터에선 몇 명일까요?)
4. `employees` 에서 **부하 직원이 없는 사원(말단)** 을 SELF JOIN 안티조인으로 찾으세요. (힌트: 자신의 employee_id 가 누군가의 manager_id 로 쓰이지 않는 사원)
5. `orders o LEFT JOIN payments pay` 에서, **환불(REFUNDED)된 결제가 있는 주문** 과 **결제가 아예 없는 주문(PENDING)** 을 구분해서, 상태별 주문 수를 세세요. (힌트: `pay.status` 를 GROUP BY, NULL 도 한 그룹)
6. 카테고리별 매출 리포트를 만드세요. `categories`(대분류만 아님, 소분류 전체) 를 LEFT JOIN 기준으로 삼아 **매출이 0인 카테고리도 0으로** 보이게 하세요. (category_id, 카테고리명, 매출 — 취소 주문 제외)
7. (ON vs WHERE) 다음 두 쿼리의 결과 행 수가 왜 다른지 실행해서 확인하고 주석으로 설명하세요.
   ```sql
   -- (A)
   SELECT COUNT(*) FROM customers c
   LEFT JOIN orders o ON o.customer_id=c.customer_id AND o.total_amount > 5000000;
   -- (B)
   SELECT COUNT(*) FROM customers c
   LEFT JOIN orders o ON o.customer_id=c.customer_id WHERE o.total_amount > 5000000;
   ```
8. (FULL OUTER 우회) `products` 의 카테고리 집합과, `reviews` 가 달린 상품들의 카테고리 집합을 비교하세요. "상품은 있지만 후기가 하나도 없는 카테고리" 를 FULL OUTER JOIN 우회 또는 안티조인으로 찾으세요.

문제만 담긴 파일은 `exercise.sql`, 정답과 해설은 `solution.sql` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ Step 08 — 집합 연산과 서브쿼리 기초 (이후 스텝에서 계속됩니다)

---

## 실습 파일

이 스텝은 SQL 파일 3개로 구성됩니다. 본문(7-1 ~ 7-11)의 예제를 순서대로 담은 `practice.sql` 을 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.sql` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.sql` 로 채점하고 해설을 읽는 흐름입니다. 세 파일 모두 맨 앞에 `USE shop;` 이 있어 `shop` 데이터베이스를 대상으로 동작하며, Step 01 에서 띄운 컨테이너(`mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < 파일명`)에 그대로 흘려 넣으면 됩니다. `-t` 옵션이 있어야 본문에 나온 것과 같은 ASCII 표 형태로 결과가 출력됩니다.

### practice.sql

본문 강의를 따라가며 손으로 쳐볼 예제를 `[7-1] ~ [7-11]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[7-1]` 은 `INNER JOIN customers c ON c.customer_id = o.customer_id` 로 시작해, 바로 아래에 `INNER` 를 뺀 `JOIN` 버전을 나란히 두어 **둘이 같은 것**임을 눈으로 확인시킵니다.
- `[7-3]` 의 두 번째 쿼리는 `COUNT(p.product_id)` 와 `COUNT(*)` 를 **한 SELECT 안에 나란히** 놓습니다. 대분류 5개에서 앞은 `0`, 뒤는 `1` 이 나오는 것이 이 절의 핵심 학습 포인트입니다.
- `[7-4]` 는 이 스텝의 심장입니다. `AND o.status = 'DELIVERED'` 를 `ON` 절에 붙인 쿼리(258)와 `WHERE` 로 내린 쿼리(240)를 연달아 실행해 **18행 차이**를 만들고, 이어서 `WHERE c.customer_id = 6` 으로 강소라 한 명만 뽑아 NULL 확장 행을 직접 보여줍니다.
- `[7-10]` 의 `FULL OUTER JOIN` 예시는 주석으로만 남겨두었습니다(`-- SELECT * FROM a FULL OUTER JOIN b ...` → `ERROR 1064`). 주석을 풀면 파일 실행이 그 지점에서 에러로 멈추니, **주석을 지우지 말고** 그대로 두세요.
- `[7-11]` 의 마지막 컬럼 `FORMAT(COALESCE(SUM(DISTINCT o.total_amount), 0), 0) AS 주문금액참고` 는 이름 그대로 **참고용**입니다. `SUM(DISTINCT ...)` 는 fan-out 을 완전히 해결하지 못합니다(서로 다른 주문의 금액이 우연히 같으면 한 번만 더해집니다). 본문 7-11 의 경고와 짝지어 읽으세요.

```sql file="./practice.sql"
```

### exercise.sql

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래가 비어 있으니, 거기에 직접 쿼리를 써 넣고 파일을 통째로 실행해 검증하면 됩니다.

- `[문제 7]` 만 예외적으로 쿼리 (A)/(B) 가 **이미 작성되어 있습니다.** 여러분이 할 일은 쿼리를 쓰는 게 아니라 두 COUNT 를 실제로 실행해 비교하고, 파일 끝의 `-- → 왜 다른가요? (여기에 설명)` 자리에 이유를 주석으로 적는 것입니다.
- `[문제 5]` 는 `- NULL 그룹은 '(무결제)' 로 표시하세요. (힌트: COALESCE)` 라고 힌트를 줍니다. PENDING 주문이 `payments` 에 짝이 없어 `pay.status` 가 NULL 이 되는 성질(7-5 절)을 이용하는 문제입니다.
- `[문제 6]` 의 힌트 `취소 제외는 ON 이 아니라 SUM(CASE WHEN ...) 로 해야 정확합니다. 왜일까요?` 가 이 파일에서 가장 어려운 대목입니다. 답을 보기 전에 두 방식을 모두 돌려 숫자가 왜 갈리는지 먼저 관찰해 보세요.
- 파일을 그대로 실행하면 문제 7 의 두 쿼리만 결과가 나오고 나머지는 아무것도 출력되지 않습니다. 정상입니다.

```sql file="./exercise.sql"
```

### solution.sql

8문제의 정답 쿼리와 해설 주석을 담은 파일입니다. `exercise.sql` 을 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 기대 결과값까지 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 3]` 의 결과는 **0명**입니다. 시드 데이터가 고객 30명 전원에게 주문 20건씩을 배정했기 때문입니다. "아무것도 안 나오는 것" 이 정답인 문제로, 데이터 검증에서 안티조인이 어떻게 쓰이는지 보여줍니다.
- `[정답 4]` 는 `LEFT JOIN employees s ON s.manager_id = e.employee_id` 로 **부하 방향**의 SELF JOIN 을 건 뒤 `WHERE s.employee_id IS NULL` 로 걸러냅니다. 7-6 절의 `m.employee_id = e.manager_id`(관리자 방향)와 **조인 조건의 방향이 반대**라는 점이 포인트입니다. 결과는 9~18번 사원 10명입니다.
- `[정답 6]` 이 이 파일의 하이라이트입니다. `ON o.status <> 'CANCELLED'` 로 취소를 제외하면 **틀립니다.** 그 조건은 `orders` 를 매칭할지 말지만 정할 뿐, 이미 조인된 `order_items` 의 `quantity * unit_price` 값은 그대로 남아 매출에 섞입니다. 그래서 `SUM(CASE WHEN o.status <> 'CANCELLED' THEN oi.quantity * oi.unit_price END)` 로 **합산 여부 자체를 제어**해야 합니다. 결과 1위는 노트북 327,900,000 이고, 상품이 없는 대분류 5개는 `COALESCE(..., 0)` 덕분에 0 으로 나옵니다.
- `[정답 7]` 의 숫자는 (A) 46, (B) 20 입니다. 46 = 500만원 초과 주문 20건 + 그런 주문이 하나도 없는 고객 26명. 본문 7-4 의 258/240 과 같은 원리를 다른 조건으로 한 번 더 겪게 하는 문제입니다.
- `[정답 8]` 은 `EXISTS (products)` 로 "상품이 있는 카테고리" 로 먼저 좁힌 뒤 `NOT EXISTS (products JOIN reviews)` 로 후기 없는 곳만 남깁니다. 정답은 소설(52) 카테고리 하나입니다.

```sql file="./solution.sql"
```
