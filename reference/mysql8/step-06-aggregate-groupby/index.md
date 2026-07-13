# Step 06 — 집계함수와 GROUP BY

> **학습 목표**
> - `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` 로 여러 행을 한 값으로 접는다
> - `COUNT(*)` · `COUNT(col)` · `COUNT(DISTINCT col)` 의 **결정적 차이**를 안다
> - `GROUP BY` 로 그룹별 집계를 하고, `WHERE`(그룹 전)와 `HAVING`(그룹 후)을 구분한다
> - **`ONLY_FULL_GROUP_BY`**(MySQL 8 기본 ON)의 에러를 이해하고 `ANY_VALUE` 로 푼다
> - `WITH ROLLUP` + `GROUPING()`, `GROUP_CONCAT`, `SUM(조건)` 관용구를 익힌다
>
> **선행 스텝**: [Step 05 — 연산자와 조건](../step-05-where-operators/)
> **예상 소요**: 60분

---

## 6-1. 집계함수 — 여러 행을 한 행으로 접기

지금까지의 SELECT 는 "행 1개 → 값 여러 개"였습니다. 집계함수는 반대로 **"행 여러 개 → 값 1개"** 로 접습니다.

```sql
SELECT
    COUNT(*)   AS 상품수,
    SUM(stock) AS 총재고,
    AVG(price) AS 평균가,
    MIN(price) AS 최저가,
    MAX(price) AS 최고가
FROM products;
```

**결과**
```
+-----------+-----------+---------------+-----------+------------+
| 상품수    | 총재고    | 평균가        | 최저가    | 최고가     |
+-----------+-----------+---------------+-----------+------------+
|        40 |      5438 | 318582.500000 |   4900.00 | 2190000.00 |
+-----------+-----------+---------------+-----------+------------+
```

40개 상품 행이 한 행으로 접혔습니다. `AVG` 의 소수점이 지저분하면 `ROUND`, 큰 숫자에 콤마를 넣으려면 `FORMAT` 을 씁니다.

```sql
SELECT
    COUNT(*)                      AS 상품수,
    ROUND(AVG(price))             AS 평균가,
    FORMAT(SUM(price * stock), 0) AS 재고자산
FROM products;
```

**결과**
```
+-----------+-----------+--------------+
| 상품수    | 평균가    | 재고자산     |
+-----------+-----------+--------------+
|        40 |    318583 | 472,940,000  |
+-----------+-----------+--------------+
```

> ⚠️ **함정**: `FORMAT(x, 0)` 은 **숫자를 문자열로 바꿉니다.** 보기엔 좋지만 그 컬럼으로 다시 정렬하거나 계산하면 문자열 정렬("9" > "10")이 되어버립니다. **표시(presentation)는 마지막에, 정렬/계산은 원본 숫자로.**

---

## 6-2. COUNT 3형제 — 완전히 다른 셋

이걸 헷갈리면 리포트 숫자가 틀립니다.

| 형태 | 세는 것 |
|---|---|
| `COUNT(*)` | **행의 개수** (NULL 포함, 무조건) |
| `COUNT(col)` | **col 이 NULL 이 아닌 행의 개수** |
| `COUNT(DISTINCT col)` | **col 의 서로 다른 값의 개수** (NULL 제외) |

`employees` 로 확인합니다. 18명 중 CEO 1명만 `manager_id` 가 NULL 입니다.

```sql
SELECT
    COUNT(*)                   AS `COUNT(*)`,
    COUNT(manager_id)          AS `COUNT(manager_id)`,
    COUNT(DISTINCT manager_id) AS `COUNT(DISTINCT manager_id)`
FROM employees;
```

**결과**
```
+----------+-------------------+----------------------------+
| COUNT(*) | COUNT(manager_id) | COUNT(DISTINCT manager_id) |
+----------+-------------------+----------------------------+
|       18 |                17 |                          8 |
+----------+-------------------+----------------------------+
```

- `18` : 전체 사원 수
- `17` : manager_id 가 있는 사원 수 (CEO 제외 → NULL 이 빠짐)
- `8` : 서로 다른 관리자 수 = **관리자 역할을 하는 사람이 8명**

`customers` 로 한 번 더. phone 이 NULL 인 고객이 3명이었죠.

```sql
SELECT
    COUNT(*)             AS 전체고객,
    COUNT(phone)         AS 전화번호_있는_고객,
    COUNT(DISTINCT city) AS 도시_수
FROM customers;
```

**결과**
```
+--------------+----------------------------+------------+
| 전체고객     | 전화번호_있는_고객         | 도시_수    |
+--------------+----------------------------+------------+
|           30 |                         27 |          8 |
+--------------+----------------------------+------------+
```

> 💡 **실무 팁**: `COUNT(1)` 은 `COUNT(*)` 와 **완전히 같습니다.** "별표는 모든 컬럼을 읽으니 느리다"는 건 미신입니다. 옵티마이저가 둘을 똑같이 처리합니다. 취향껏 쓰되, 팀 컨벤션을 따르세요.

> ⚠️ **함정**: "주문한 고객이 몇 명?" 이라는 질문에 `COUNT(customer_id)` 를 쓰면 **주문 건수**가 나옵니다(고객 1명이 20건 주문했으면 20). 답은 `COUNT(DISTINCT customer_id)` 입니다. "몇 명/몇 종류" 는 거의 항상 `DISTINCT` 가 필요합니다.

---

## 6-3. 집계함수는 NULL 을 무시한다

**`COUNT(*)` 를 제외한 모든 집계함수는 NULL 을 그냥 건너뜁니다.** 이게 `AVG` 에서 미묘한 함정을 만듭니다.

우리 데이터에는 points 가 `0` 인 고객이 3명 있습니다(NULL 이 아니라 진짜 0). "포인트를 적립한 고객만의 평균" 을 원한다면 0 을 NULL 로 바꿔야 합니다.

```sql
SELECT
    COUNT(*)                              AS 전체,
    AVG(points)                           AS `AVG(points)_0포함`,
    AVG(NULLIF(points, 0))                AS `AVG_0을_NULL로`,
    SUM(points) / COUNT(*)                AS `SUM/COUNT(*)`,
    SUM(points) / COUNT(NULLIF(points,0)) AS `SUM/COUNT(non-zero)`
FROM customers;
```

**결과**
```
+--------+---------------------+------------------+--------------+---------------------+
| 전체   | AVG(points)_0포함   | AVG_0을_NULL로   | SUM/COUNT(*) | SUM/COUNT(non-zero) |
+--------+---------------------+------------------+--------------+---------------------+
|     30 |           5959.0000 |        6621.1111 |    5959.0000 |           6621.1111 |
+--------+---------------------+------------------+--------------+---------------------+
```

`5959` vs `6621`. **"평균 포인트"의 정의**에 따라 답이 달라집니다. `AVG(col)` 은 정확히 `SUM(col) / COUNT(col)` 이므로, 분모에서 무엇을 뺄지가 핵심입니다.

> ⚠️ **함정**: "이 컬럼의 평균" 을 낼 때 **NULL 과 0 을 어떻게 취급할지 반드시 확인**하세요. 매출 데이터에서 "결제액이 없는(NULL)" 주문과 "결제액이 0원인" 주문은 평균에 다르게 기여합니다. 기획자에게 "포함인가요?" 를 물어야 하는 순간입니다.

---

## 6-4. GROUP BY — 그룹마다 집계

`GROUP BY city` 는 "도시가 같은 행끼리 한 무더기로 묶고, 각 무더기마다 집계함수를 한 번씩 계산" 합니다.

```sql
SELECT
    city               AS 도시,
    COUNT(*)           AS 고객수,
    ROUND(AVG(points)) AS 평균포인트,
    MAX(points)        AS 최대포인트
FROM customers
GROUP BY city
ORDER BY 고객수 DESC, 도시;
```

**결과**
```
+--------+-----------+-----------------+-----------------+
| 도시   | 고객수    | 평균포인트      | 최대포인트      |
+--------+-----------+-----------------+-----------------+
| 서울   |        10 |           11730 |           22400 |
| 부산   |         5 |            3490 |            9200 |
| 인천   |         4 |            6675 |            8800 |
| 대구   |         3 |            3400 |            6700 |
| 광주   |         2 |            1450 |            2900 |
| 대전   |         2 |            1395 |            2450 |
| 수원   |         2 |             325 |             600 |
| 울산   |         2 |             390 |             700 |
+--------+-----------+-----------------+-----------------+
```

여러 컬럼으로 묶으면 **조합마다** 그룹이 생깁니다.

```sql
SELECT grade AS 등급, city AS 도시, COUNT(*) AS 고객수
FROM customers
GROUP BY grade, city
ORDER BY grade, city
LIMIT 10;
```

**결과**
```
+--------+--------+-----------+
| 등급   | 도시   | 고객수    |
+--------+--------+-----------+
| BRONZE | 광주   |         1 |
| BRONZE | 대구   |         1 |
| BRONZE | 대전   |         1 |
| BRONZE | 부산   |         2 |
| BRONZE | 서울   |         1 |
| BRONZE | 수원   |         2 |
| BRONZE | 울산   |         2 |
| SILVER | 광주   |         1 |
| SILVER | 대구   |         1 |
| SILVER | 대전   |         1 |
+--------+--------+-----------+
... (총 18행)
```

주문 상태별 매출 집계는 실무에서 매일 보는 리포트입니다.

```sql
SELECT
    status                              AS 상태,
    COUNT(*)                            AS 주문수,
    FORMAT(SUM(total_amount), 0)        AS 합계금액,
    FORMAT(ROUND(AVG(total_amount)), 0) AS 평균금액
FROM orders
GROUP BY status
ORDER BY 주문수 DESC;
```

**결과**
```
+-----------+-----------+--------------+--------------+
| 상태      | 주문수    | 합계금액     | 평균금액     |
+-----------+-----------+--------------+--------------+
| DELIVERED |       240 | 326,280,000  | 1,359,500    |
| SHIPPED   |       120 | 162,834,000  | 1,356,950    |
| PAID      |       120 | 132,630,000  | 1,105,250    |
| CANCELLED |        60 | 47,742,000   | 795,700      |
| PENDING   |        60 | 95,112,000   | 1,585,200    |
+-----------+-----------+--------------+--------------+
```

---

## 6-5. WHERE vs HAVING — 언제 거르는가

이 둘은 **거르는 시점**이 다릅니다.

```
FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY
        ↑                    ↑
   개별 행을 거름       그룹을 거름
   (집계 전)            (집계 후, 집계함수 사용 가능)
```

"VIP 또는 GOLD 고객이 2명 이상 사는 도시" 를 봅시다.

```sql
SELECT city AS 도시, COUNT(*) AS VIP_GOLD_수
FROM customers
WHERE grade IN ('VIP', 'GOLD')   -- ① 먼저 개별 행을 거르고
GROUP BY city
HAVING COUNT(*) >= 2             -- ② 그 다음 그룹을 거른다
ORDER BY VIP_GOLD_수 DESC, 도시;
```

**결과**
```
+--------+--------------+
| 도시   | VIP_GOLD_수  |
+--------+--------------+
| 서울   |            8 |
| 인천   |            3 |
+--------+--------------+
```

`WHERE grade IN (...)` 은 그룹핑 전에 BRONZE/SILVER 고객을 쳐냅니다. `HAVING COUNT(*) >= 2` 는 그룹핑 후에 "1명뿐인 도시" 그룹을 쳐냅니다. **집계 결과(`COUNT(*)`)로 거르는 건 WHERE 로는 불가능**하고 HAVING 만 할 수 있습니다.

`HAVING` 에서는 SELECT 별칭도 쓸 수 있습니다 (MySQL 확장).

```sql
SELECT city AS 도시, COUNT(*) AS 고객수
FROM customers
GROUP BY city
HAVING 고객수 >= 3          -- 별칭 사용 가능
ORDER BY 고객수 DESC;
```

**결과**
```
+--------+-----------+
| 도시   | 고객수    |
+--------+-----------+
| 서울   |        10 |
| 부산   |         5 |
| 인천   |         4 |
| 대구   |         3 |
+--------+-----------+
```

> ⚠️ **함정 (성능)**: **집계와 무관한 조건은 HAVING 이 아니라 WHERE 에 쓰세요.** `HAVING city IN ('서울','부산')` 도 동작은 하지만, 모든 도시를 다 그룹핑한 뒤 대부분을 버립니다. `WHERE city IN ('서울','부산')` 으로 쓰면 그룹핑 대상 자체가 줄고 인덱스도 탈 수 있습니다.
> - HAVING 에 써야 하는 것: `HAVING COUNT(*) >= 2`, `HAVING SUM(amount) > 1000000` — 집계 결과 조건
> - WHERE 로 옮겨야 하는 것: `HAVING city = '서울'` — 개별 행 조건

---

## 6-6. ONLY_FULL_GROUP_BY — MySQL 8 의 기본값 (중요)

MySQL 8 의 기본 `sql_mode` 에는 `ONLY_FULL_GROUP_BY` 가 **켜져 있습니다.**

```sql
SELECT @@sql_mode;
```

**결과**
```
+-----------------------------------------------------------------------------------------------------------------------+
| @@sql_mode                                                                                                            |
+-----------------------------------------------------------------------------------------------------------------------+
| ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION |
+-----------------------------------------------------------------------------------------------------------------------+
```

이 모드는 **GROUP BY 에 없고 집계함수로도 안 감싼 컬럼을 SELECT 하면 에러**를 냅니다.

```sql
SELECT city, name, COUNT(*) FROM customers GROUP BY city;
```

**결과**
```
ERROR 1055 (42000): Expression #2 of SELECT list is not in GROUP BY clause
and contains nonaggregated column 'shop.customers.name' which is not
functionally dependent on columns in GROUP BY clause;
this is incompatible with sql_mode=only_full_group_by
```

왜 에러일까요? 서울 그룹에는 고객이 10명입니다. 그런데 `name` 은 하나만 골라야 합니다 — **어느 것을?** 이 질문에 답이 없기 때문에 MySQL 8 은 거부합니다.

> ⚠️ **함정 (5.7 → 8.0 마이그레이션 1순위)**: MySQL 5.7 까지는 이 모드가 **꺼져 있어서** 위 쿼리가 그냥 통과했습니다. 그리고 `name` 에 **아무 값이나(대개 그룹의 첫 행) 담아서** 조용히 돌려줬습니다. 5.7 에서 잘 돌던 리포트 쿼리가 8.0 에서 무더기로 에러를 뱉는 가장 흔한 원인입니다. 이건 **버그를 막아주는 좋은 변화**입니다 — 끄지 마세요.

**해결책 ① 집계함수로 감싼다** — "어느 값?" 에 명시적으로 답한다.

```sql
SELECT city, MAX(name) AS 대표이름, COUNT(*) AS 고객수
FROM customers
GROUP BY city
ORDER BY 고객수 DESC
LIMIT 5;
```

**결과**
```
+--------+--------------+-----------+
| city   | 대표이름     | 고객수    |
+--------+--------------+-----------+
| 서울   | 장혜원       |        10 |
| 부산   | 한지호       |         5 |
| 인천   | 정  훈       |         4 |
| 대구   | 최영희       |         3 |
| 광주   | 조원준       |         2 |
+--------+--------------+-----------+
```

**해결책 ② `ANY_VALUE()`** — "아무 값이나 하나 주세요" 를 명시적으로 선언.

```sql
SELECT city, ANY_VALUE(name) AS 샘플이름, COUNT(*) AS 고객수
FROM customers
GROUP BY city
ORDER BY 고객수 DESC
LIMIT 5;
```

**결과**
```
+--------+--------------+-----------+
| city   | 샘플이름     | 고객수    |
+--------+--------------+-----------+
| 서울   | 김민수       |        10 |
| 부산   | 박철수       |         5 |
| 인천   | 정  훈       |         4 |
| 대구   | 최영희       |         3 |
| 광주   | 윤대현       |         2 |
+--------+--------------+-----------+
```

`ANY_VALUE` 는 "값이 뭐가 나오든 상관없다는 걸 내가 알고 있다" 는 의사 표현입니다. `MAX` 와 달리 "아무거나"라는 의도가 코드에 드러납니다.

**기능적 종속성(functional dependency)** 이 성립하면 에러가 안 납니다. `GROUP BY` 에 **PK** 가 들어가면, 나머지 컬럼은 그 PK 에 종속되므로 값이 유일하게 정해집니다.

```sql
SELECT c.customer_id, c.name, COUNT(o.order_id) AS 주문수
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_id            -- PK 로 그룹핑 → name 은 자동으로 유일
ORDER BY 주문수 DESC
LIMIT 5;
```

**결과**
```
+-------------+-----------+-----------+
| customer_id | name      | 주문수    |
+-------------+-----------+-----------+
|           1 | 김민수    |        20 |
|           2 | 이지은    |        20 |
|           3 | 박철수    |        20 |
|           4 | 최영희    |        20 |
|           5 | 정  훈    |        20 |
+-------------+-----------+-----------+
```

`GROUP BY c.customer_id` 만 썼는데 `c.name` 을 SELECT 해도 됩니다. customer_id 가 PK 라 name 이 그에 종속됨을 MySQL 이 알기 때문입니다. (모든 고객이 정확히 20건씩 주문한 건 시드 데이터의 특성입니다.)

---

## 6-7. GROUP BY ... ASC/DESC 는 제거되었다 (MySQL 8)

5.7 까지는 `GROUP BY col DESC` 로 그룹핑과 정렬을 동시에 할 수 있었습니다. **8.0.13 부터 이 문법이 제거**되었습니다.

```sql
SELECT city, COUNT(*) FROM customers GROUP BY city DESC;
```

**결과**
```
ERROR 1064 (42000): You have an error in your SQL syntax; check the manual
that corresponds to your MySQL server version for the right syntax to use near 'DESC'
```

정렬은 이제 `ORDER BY` 로 명시해야 합니다.

```sql
SELECT city, COUNT(*) AS cnt
FROM customers
GROUP BY city
ORDER BY city DESC;
```

**결과**
```
+--------+-----+
| city   | cnt |
+--------+-----+
| 인천   |   4 |
| 울산   |   2 |
| 수원   |   2 |
| 서울   |  10 |
| 부산   |   5 |
| 대전   |   2 |
| 대구   |   3 |
| 광주   |   2 |
+--------+-----+
```

> 💡 **참고**: 사실 옛날 `GROUP BY` 는 **암묵적으로 정렬까지** 해줬습니다(그래서 ASC/DESC 를 붙일 수 있었죠). 8.0 부터는 그 암묵적 정렬도 사라졌습니다. 즉 **`GROUP BY` 만 쓰고 `ORDER BY` 를 안 붙이면 순서가 보장되지 않습니다.** 정렬이 필요하면 반드시 `ORDER BY` 를 쓰세요. (이건 오히려 불필요한 정렬 비용을 없앤 성능 개선입니다.)

---

## 6-8. WITH ROLLUP — 소계와 총계

`WITH ROLLUP` 은 그룹별 집계 아래에 **소계/총계 행을 자동으로** 붙여줍니다.

```sql
SELECT
    status                       AS 상태,
    COUNT(*)                     AS 주문수,
    FORMAT(SUM(total_amount), 0) AS 합계
FROM orders
GROUP BY status WITH ROLLUP;
```

**결과**
```
+-----------+-----------+-------------+
| 상태      | 주문수    | 합계        |
+-----------+-----------+-------------+
| PENDING   |        60 | 95,112,000  |
| PAID      |       120 | 132,630,000 |
| SHIPPED   |       120 | 162,834,000 |
| DELIVERED |       240 | 326,280,000 |
| CANCELLED |        60 | 47,742,000  |
| NULL      |       600 | 764,598,000 |   ← ROLLUP 이 만든 총계 행
+-----------+-----------+-------------+
```

마지막 `NULL` 행이 전체 총계입니다. 2단계로 묶으면 중간 소계도 생깁니다.

```sql
SELECT city AS 도시, grade AS 등급, COUNT(*) AS 고객수
FROM customers
WHERE city IN ('서울', '부산')
GROUP BY city, grade WITH ROLLUP;
```

**결과**
```
+--------+--------+-----------+
| 도시   | 등급   | 고객수    |
+--------+--------+-----------+
| 부산   | BRONZE |         2 |
| 부산   | SILVER |         2 |
| 부산   | GOLD   |         1 |
| 부산   | NULL   |         5 |   ← 부산 소계
| 서울   | BRONZE |         1 |
| 서울   | SILVER |         1 |
| 서울   | GOLD   |         3 |
| 서울   | VIP    |         5 |
| 서울   | NULL   |        10 |   ← 서울 소계
| NULL   | NULL   |        15 |   ← 전체 총계
+--------+--------+-----------+
```

### ROLLUP 의 NULL 문제와 GROUPING() (MySQL 8 신규)

여기 심각한 함정이 있습니다. 만약 `grade` 컬럼 자체에 NULL 값이 있었다면, "진짜 NULL 데이터" 와 "ROLLUP 이 만든 소계 표시 NULL" 을 **구분할 수 없습니다.**

MySQL 8 이 도입한 **`GROUPING()`** 함수가 이걸 해결합니다. `GROUPING(col)` 은 그 행이 col 에 대한 소계 행이면 `1`, 아니면 `0` 을 돌려줍니다.

```sql
SELECT
    IF(GROUPING(city)  = 1, '── 전체 ──', city)  AS 도시,
    IF(GROUPING(grade) = 1, '  소계',     grade) AS 등급,
    COUNT(*)        AS 고객수,
    GROUPING(city)  AS g_city,
    GROUPING(grade) AS g_grade
FROM customers
WHERE city IN ('서울', '부산')
GROUP BY city, grade WITH ROLLUP;
```

**결과**
```
+----------------------+----------+-----------+--------+---------+
| 도시                 | 등급     | 고객수    | g_city | g_grade |
+----------------------+----------+-----------+--------+---------+
| 부산                 | BRONZE   |         2 |      0 |       0 |
| 부산                 | SILVER   |         2 |      0 |       0 |
| 부산                 | GOLD     |         1 |      0 |       0 |
| 부산                 |   소계   |         5 |      0 |       1 |
| 서울                 | BRONZE   |         1 |      0 |       0 |
| 서울                 | SILVER   |         1 |      0 |       0 |
| 서울                 | GOLD     |         3 |      0 |       0 |
| 서울                 | VIP      |         5 |      0 |       0 |
| 서울                 |   소계   |        10 |      0 |       1 |
| ── 전체 ──           |   소계   |        15 |      1 |       1 |
+----------------------+----------+-----------+--------+---------+
```

`GROUPING()` 덕분에 소계 행에 "소계" / "전체" 라벨을 정확히 붙일 수 있습니다. 5.7 에서는 이게 안 되어서 소계 NULL 을 다루기가 지저분했습니다.

> ⚠️ **함정**: `WITH ROLLUP` 과 `ORDER BY` 를 같이 쓰면 총계 행의 위치가 의도와 다를 수 있습니다(NULL 정렬 규칙 때문). 또 `WITH ROLLUP` 이 붙은 쿼리에는 `DISTINCT` 나 `LIMIT` 을 조합할 때 주의가 필요합니다. 복잡해지면 애플리케이션에서 소계를 계산하거나, 윈도우 함수(Step 12)를 쓰는 편이 깔끔할 때가 많습니다.

---

## 6-9. GROUP_CONCAT — 그룹의 값을 한 줄로

집계는 보통 "숫자 1개" 를 만들지만, `GROUP_CONCAT` 은 그룹 안의 **값들을 이어붙인 문자열**을 만듭니다.

```sql
SELECT
    city                                            AS 도시,
    COUNT(*)                                        AS 고객수,
    GROUP_CONCAT(name ORDER BY name SEPARATOR ', ') AS 고객목록
FROM customers
GROUP BY city
ORDER BY 고객수 DESC;
```

**결과** (일부)
```
+--------+-----------+-------------------------------------------------------------------------------+
| 도시   | 고객수    | 고객목록                                                                      |
+--------+-----------+-------------------------------------------------------------------------------+
| 서울   |        10 | 강소라, 김민수, 문재현, 송민지, 안지수, 양현우, 오세영, 우예린, 이지은, 장혜원 |
| 부산   |         5 | 구세진, 박철수, 백승호, 하준서, 한지호                                        |
| 인천   |         4 | 류하나, 배채영, 신태민, 정  훈                                                 |
+--------+-----------+-------------------------------------------------------------------------------+
... (총 8행)
```

`DISTINCT` 와 `ORDER BY` 를 안에 넣을 수 있습니다.

```sql
SELECT
    c.parent_id                                         AS 상위카테고리,
    GROUP_CONCAT(DISTINCT c.name ORDER BY c.sort_order) AS 하위목록
FROM categories c
WHERE c.parent_id IS NOT NULL
GROUP BY c.parent_id
ORDER BY c.parent_id;
```

**결과**
```
+--------------------+--------------------------+
| 상위카테고리       | 하위목록                 |
+--------------------+--------------------------+
|                  1 | 남성의류,여성의류,신발   |
|                  2 | 노트북,스마트폰,주변기기 |
|                  3 | 신선식품,가공식품        |
|                  4 | 주방용품,가구            |
|                  5 | IT/컴퓨터,소설           |
+--------------------+--------------------------+
```

### GROUP_CONCAT 의 무서운 함정 — 조용히 잘린다

`GROUP_CONCAT` 의 결과 길이는 `group_concat_max_len` (기본 **1024 바이트**)으로 제한됩니다. 넘으면 **에러가 아니라 그냥 잘립니다.** 경고만 뜨는데, 배치 잡에서는 아무도 경고를 안 봅니다.

```sql
SELECT @@group_concat_max_len AS 기본_최대길이;
-- 1024

-- 600건 주문 id 를 전부 이어붙이면 2291 바이트가 나와야 하는데...
SELECT LENGTH(GROUP_CONCAT(order_id)) AS 잘린_길이, COUNT(*) AS 실제_주문수
FROM orders;
```

**결과**
```
+---------------+------------------+
| 잘린_길이     | 실제_주문수      |
+---------------+------------------+
|          1024 |              600 |     ← 딱 1024 에서 잘렸다!
+---------------+------------------+
1 row in set, 1 warning
```

대화형 클라이언트에서 `SHOW WARNINGS;` 를 치면 범인이 보입니다.

```
+---------+------+-----------------------------------+
| Level   | Code | Message                           |
+---------+------+-----------------------------------+
| Warning | 1260 | Row 269 was cut by GROUP_CONCAT() |
+---------+------+-----------------------------------+
```

세션 단위로 한도를 늘리면 온전해집니다.

```sql
SET SESSION group_concat_max_len = 1000000;

SELECT LENGTH(GROUP_CONCAT(order_id)) AS 온전한_길이, COUNT(*) AS 실제_주문수
FROM orders;
```

**결과**
```
+------------------+------------------+
| 온전한_길이      | 실제_주문수      |
+------------------+------------------+
|             2291 |              600 |
+------------------+------------------+
```

> ⚠️ **함정**: `GROUP_CONCAT` 이 프로덕션에서 조용히 데이터를 잘라먹는 사고는 흔합니다. 그룹의 크기가 커질 수 있다면 **반드시 `group_concat_max_len` 을 넉넉히 설정**하고, 애초에 "많은 값을 문자열로 이어붙이는" 설계가 맞는지 재고하세요. 공용 DB 에서는 `SET GLOBAL` 이 아니라 **`SET SESSION`** 으로만 바꾸세요 (다른 사람에게 영향을 주지 않도록).

---

## 6-10. 조건부 집계 — SUM(조건) 관용구

MySQL 에서 불리언은 `1`/`0` 입니다. 그래서 **`SUM(조건)` = "조건을 만족하는 행의 수"**, **`AVG(조건)` = "조건을 만족하는 비율"** 이 됩니다. 이건 피벗 테이블의 기초입니다.

```sql
SELECT
    c.grade                                     AS 등급,
    COUNT(*)                                    AS 전체주문,
    SUM(o.status = 'CANCELLED')                 AS 취소,
    SUM(o.status = 'DELIVERED')                 AS 배송완료,
    ROUND(AVG(o.status = 'CANCELLED') * 100, 1) AS 취소율_pct
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.grade
ORDER BY 취소율_pct DESC;
```

**결과**
```
+--------+--------------+--------+--------------+---------------+
| 등급   | 전체주문     | 취소   | 배송완료     | 취소율_pct    |
+--------+--------------+--------+--------------+---------------+
| VIP    |          100 |     20 |           40 |          20.0 |
| SILVER |          140 |     20 |           60 |          14.3 |
| GOLD   |          160 |     20 |          100 |          12.5 |
| BRONZE |          200 |      0 |           40 |           0.0 |
+--------+--------------+--------+--------------+---------------+
```

한 번의 스캔으로 등급별 취소/배송완료 건수와 취소율을 동시에 뽑았습니다. `CASE WHEN ... THEN 1 END` 로도 같은 걸 할 수 있지만, MySQL 에선 `SUM(조건)` 이 훨씬 간결합니다.

### JOIN + GROUP BY 에서 그룹이 통째로 사라지는 함정

"등급별 매출" 을 낼 때 취소 주문을 빼려고 `WHERE status <> 'CANCELLED'` 를 붙였다고 합시다.

```sql
SELECT
    c.grade                        AS 등급,
    COUNT(DISTINCT c.customer_id)  AS 고객수,
    COUNT(o.order_id)              AS 주문수,
    FORMAT(SUM(o.total_amount), 0) AS 총매출
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
WHERE o.status <> 'CANCELLED'
GROUP BY c.grade
ORDER BY SUM(o.total_amount) DESC;
```

**결과**
```
+--------+-----------+-----------+-------------+
| 등급   | 고객수    | 주문수    | 총매출      |
+--------+-----------+-----------+-------------+
| BRONZE |        10 |       200 | 300,867,000 |
| GOLD   |         7 |       140 | 187,564,500 |
| SILVER |         6 |       120 | 166,427,500 |
| VIP    |         4 |        80 | 61,997,000  |
+--------+-----------+-----------+-------------+
```

고객수를 다 더하면 `10+7+6+4 = 27` 입니다. **전체 30명인데 3명이 사라졌습니다.** 범인은 "주문이 전부 취소된 고객" 입니다. `WHERE` 가 그들의 모든 행을 걸러내니 그룹에 낄 행 자체가 없어진 것이죠.

```sql
SELECT
    c.customer_id, c.name, c.grade,
    COUNT(*)                     AS 총주문,
    SUM(o.status = 'CANCELLED')  AS 취소건,
    SUM(o.status <> 'CANCELLED') AS 정상건
FROM customers c
JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_id, c.name, c.grade
HAVING SUM(o.status <> 'CANCELLED') = 0;
```

**결과**
```
+-------------+-----------+--------+-----------+-----------+-----------+
| customer_id | name      | grade  | 총주문    | 취소건    | 정상건    |
+-------------+-----------+--------+-----------+-----------+-----------+
|           6 | 강소라    | VIP    |        20 |        20 |         0 |
|          16 | 문재현    | SILVER |        20 |        20 |         0 |
|          26 | 배채영    | GOLD   |        20 |        20 |         0 |
+-------------+-----------+--------+-----------+-----------+-----------+
```

> ⚠️ **함정 (실무 상급)**: **JOIN 결과에 WHERE 를 걸면, 조건에 맞는 행이 하나도 없는 그룹은 결과에서 통째로 사라집니다.** "취소 제외 매출" 은 맞게 나오지만, "전 고객 목록" 을 기대했다면 3명이 조용히 빠집니다. 이걸 피하려면 (1) 필터를 `SUM(o.status<>'CANCELLED')` 같은 **조건부 집계**로 옮기거나, (2) `LEFT JOIN` 으로 바꾸고 조건을 `ON` 절이나 집계 안으로 넣어야 합니다. 이 "필터 위치" 문제는 [Step 07](../step-07-joins/) 의 핵심 주제입니다.

---

## 정리

| 문법 | 용도 | 주의점 |
|---|---|---|
| `COUNT(*)` | 행 수 (NULL 포함) | `COUNT(1)` 과 동일 |
| `COUNT(col)` | col 이 NULL 아닌 행 수 | NULL 을 뺌 |
| `COUNT(DISTINCT col)` | 서로 다른 값의 수 | "몇 명/몇 종류" 는 이것 |
| `SUM/AVG/MIN/MAX` | 합/평균/최소/최대 | **NULL 을 무시함** |
| `AVG(NULLIF(col,0))` | 0 을 평균에서 제외 | AVG = SUM/COUNT(col) |
| `GROUP BY col` | 그룹별 집계 | 8.0 은 암묵 정렬 없음 → `ORDER BY` 필수 |
| `WHERE` | 그룹핑 **전** 행 필터 | 집계함수 못 씀 |
| `HAVING` | 그룹핑 **후** 그룹 필터 | 집계 조건만. 나머진 WHERE 로 |
| `ONLY_FULL_GROUP_BY` | **8.0 기본 ON** | GROUP BY 밖 컬럼은 에러 |
| `ANY_VALUE(col)` | "아무 값이나" 명시 | ONLY_FULL_GROUP_BY 우회 |
| `GROUP BY ... DESC` | ❌ **8.0.13 에서 제거** | `ORDER BY` 로 대체 |
| `WITH ROLLUP` | 소계/총계 자동 생성 | 소계 행은 NULL 로 표시 |
| `GROUPING(col)` | **8.0 신규.** 소계 행 판별 | 1=소계, 0=데이터 |
| `GROUP_CONCAT(...)` | 값들을 문자열로 | ⚠️ `group_concat_max_len` 넘으면 잘림 |
| `SUM(조건)` / `AVG(조건)` | 조건부 집계 (개수/비율) | 불리언 = 1/0 |

---

## 연습문제

1. `products` 에서 **카테고리(category_id)별 상품 수와 평균 가격**을 구하되, 상품이 3개 이상인 카테고리만 평균가 내림차순으로 보이세요.
2. `orders` 에서 **결제 방법(payments.method)별 결제 건수와 총액**을 구하세요. (orders 가 아니라 payments 를 쓰세요.) status 가 `DONE` 인 것만.
3. `customers` 에서 **등급별 고객 수**를 구하되, `WITH ROLLUP` 으로 전체 합계 행도 함께 보이세요. 소계 행의 등급은 `GROUPING()` 을 써서 `'전체'` 로 표시하세요.
4. `COUNT(*)` 와 `COUNT(birth_date)` 가 다른 값이 나오는지 확인하세요. 왜 그런가요? (customers 테이블)
5. `reviews` 에서 **상품별 평균 평점과 후기 수**를 구하되, 후기가 3건 이상이고 평균 평점이 4.0 미만인 상품만 평균 평점 오름차순으로 보이세요. (문제가 있는 상품을 찾는 리포트)
6. `orders` 에서 **고객별 주문 상태 분포**를 `SUM(조건)` 관용구로 만드세요. 컬럼: customer_id, 전체주문, PAID수, SHIPPED수, DELIVERED수, CANCELLED수. 주문이 가장 많은 상위 5명만.
7. `products` 에서 **카테고리별 상품명 목록**을 `GROUP_CONCAT` 으로 만드세요. 가격 높은 순으로 정렬해서 이어붙이고, 구분자는 `' | '` 로 하세요.
8. (함정 확인) `customers c JOIN orders o ...` 에서 `WHERE o.total_amount > 5000000` 을 걸고 등급별 고객 수를 세면, 전체 30명이 다 나올까요? 쿼리를 작성해 확인하고, 왜 그런지 주석으로 설명하세요.

문제만 담긴 파일은 `exercise.sql`, 정답과 해설은 `solution.sql` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 있습니다.

---

## 다음 단계

→ [Step 07 — 조인(JOIN)](../step-07-joins/)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 구성됩니다. 먼저 `practice.sql` 로 6-1 ~ 6-10 본문의 모든 쿼리를 순서대로 직접 실행해 보고, 그다음 `exercise.sql` 의 빈칸 8문제를 스스로 풀어본 뒤, 마지막에 `solution.sql` 로 답과 해설을 확인하는 흐름입니다. 세 파일 모두 첫 줄에서 `USE shop;` 을 실행하므로 앞선 스텝에서 만든 `shop` 스키마와 시드 데이터가 이미 적재돼 있어야 합니다.

### practice.sql

강의 본문의 예제 쿼리를 그대로 담은 **따라치기용 파일**입니다. 각 쿼리 위에 `[6-1]`, `[6-2]` 처럼 본문 절 번호가 주석으로 달려 있어, 문서를 읽다가 막힌 지점의 쿼리를 바로 찾아 실행할 수 있습니다.

- 헤더의 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < practice.sql` 이 실행 방법입니다. `-t` 옵션은 결과를 본문과 똑같은 **ASCII 표 형식**으로 그려주므로 붙이는 편이 좋습니다.
- 본문에서 **에러가 나는 쿼리**(6-6 의 `SELECT city, name, COUNT(*) ... GROUP BY city`, 6-7 의 `GROUP BY city DESC`)는 파일 전체가 중단되지 않도록 **주석 처리**되어 있습니다. `ONLY_FULL_GROUP_BY` 에러(1055)와 문법 에러(1064)를 직접 보고 싶다면 해당 줄의 `--` 를 떼고 **대화형 클라이언트에서 한 줄씩** 실행하세요.
- `[6-5]` 구간에는 `HAVING city IN ('서울','부산')` (나쁜 예)과 `WHERE city IN ('서울','부산')` (좋은 예)이 **나란히** 들어 있습니다. 결과는 같지만 전자는 모든 도시를 그룹핑한 뒤 버린다는 점을 비교하려는 의도입니다.
- `[6-9]` 의 `SET SESSION group_concat_max_len = 1000000;` 은 **순서 의존적**입니다. 이 줄보다 위에 있는 `LENGTH(GROUP_CONCAT(order_id))` 는 1024 에서 잘린 값을, 아래 것은 온전한 2291 을 돌려줍니다. 이 줄을 미리 실행해 버리면 "조용히 잘리는" 함정을 재현할 수 없습니다. `GLOBAL` 이 아니라 `SESSION` 인 점도 그대로 두세요 — 공용 DB 의 다른 세션에 영향을 주지 않기 위함입니다.
- 파일 끝 `[6-10]` 구간에는 쿼리 4개가 이어집니다. **종합 ①** 은 `order_items`/`orders`/`products`/`categories` 4개 테이블을 조인한 카테고리별 매출 TOP 5 이고, **종합 ②** 는 본문의 "사라진 그룹" 함정(고객수 합계가 30 이 아니라 27), 그다음이 사라진 고객 3명을 `HAVING SUM(o.status <> 'CANCELLED') = 0` 으로 찾아내는 쿼리, 마지막이 `SUM(조건)` 관용구입니다. 조인 문법 자체는 Step 07 에서 다루므로 여기서는 `GROUP BY cat.category_id, cat.name` + `HAVING SUM(oi.quantity) >= 100` 같은 **집계 부분에만** 집중하면 됩니다.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 8개를 **답이 비어 있는 상태**로 담은 파일입니다. 문제마다 주석 블록만 있고 그 아래가 공백이므로, 그 빈 줄에 직접 쿼리를 채워 넣은 뒤 실행하는 방식으로 씁니다. 그대로 실행하면 `USE shop;` 만 수행되고 아무 결과도 나오지 않는 것이 정상입니다.

- **[문제 1~2]** 는 `HAVING COUNT(*) >= 3` (집계 조건 → HAVING) 과 `status = 'DONE'` (개별 행 조건 → WHERE) 을 구분하는지 묻습니다. 6-5 의 WHERE/HAVING 판정 기준을 그대로 적용하면 됩니다.
- **[문제 3]** 은 `WITH ROLLUP` + `GROUPING()` 조합으로, 6-8 에서 배운 "소계 NULL 에 라벨 붙이기"를 재현하는 문제입니다.
- **[문제 4]** 는 `COUNT(*)` 와 `COUNT(phone)` 이 왜 30 과 27 로 갈리는지 **주석으로 설명**까지 요구합니다. 숫자를 맞히는 게 아니라 이유를 말로 쓰는 게 핵심입니다. (위 연습문제 목록에는 `COUNT(birth_date)` 로 적혀 있지만, 파일에서는 `COUNT(phone)` 을 씁니다. NULL 이 섞인 컬럼이면 어느 쪽이든 같은 원리를 확인할 수 있으니 둘 다 해 보셔도 좋습니다.)
- **[문제 6]** 은 `SUM(조건)` 관용구, **[문제 7]** 은 `GROUP_CONCAT(... ORDER BY ... SEPARATOR ' | ')` 를 연습합니다.
- **[문제 8]** 은 의도적으로 **함정을 밟게 만든 문제**입니다. `WHERE o.total_amount > 5000000` 을 걸면 4개 등급이 전부 나오지 않고 일부 등급이 통째로 사라지는데, 이 "결과가 비어 보이는" 증상 자체가 학습 포인트입니다. 6-10 의 "사라지는 그룹" 절과 짝을 이룹니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 8문제의 **정답 쿼리와 해설 주석**입니다. 문제를 다 풀어본 뒤에 열어야 의미가 있으니, 순서를 지켜 주세요.

- 각 `[정답 N]` 블록의 주석에는 **기대 결과 요약**까지 적혀 있습니다(예: 정답 2 의 "POINT 2.0억 > CARD 1.8억 > BANK > MOBILE"). 내 쿼리 결과와 대조해 검산하는 용도로 쓰세요.
- 정답 3 의 주석 "`grade` 는 ENUM 이라 정의된 순서(BRONZE..VIP)대로 그룹이 나옵니다" 는 6-7 의 "8.0 은 GROUP BY 에 암묵 정렬이 없다" 와 함께 읽어야 합니다. 순서가 보장돼 보이는 건 ENUM 의 내부 정수 순서 덕이지, `GROUP BY` 가 정렬해 준 것이 아닙니다.
- 정답 7 은 쿼리 앞에 `SET SESSION group_concat_max_len = 100000;` 을 **먼저 실행**합니다. 이 줄이 없으면 카테고리별 상품명 목록이 1024 바이트에서 조용히 잘립니다. 이 파일을 통째로 실행하면 이 설정이 **이후 세션 전체에 남는다**는 점도 기억해 두세요.
- 정답 8 은 두 쿼리가 연달아 나옵니다. 앞의 것은 `WHERE o.total_amount > 5000000` 때문에 **BRONZE/GOLD 2개 등급, 4명만** 나오는 함정 버전이고, 뒤의 대안 쿼리는 필터를 `COUNT(DISTINCT IF(o.total_amount > 5000000, c.customer_id, NULL))` 라는 조건부 집계 안으로 옮겨 **4개 등급을 모두 유지**합니다. 두 결과를 나란히 놓고 보는 것이 이 스텝의 마지막 학습 포인트입니다.

```sql file="./solution.sql"
```
