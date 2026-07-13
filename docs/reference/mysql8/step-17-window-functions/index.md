# Step 17 — 윈도우 함수

> **학습 목표**
> - `OVER` 절의 세 부품(PARTITION BY / ORDER BY / frame)을 분해해서 이해한다
> - 순위 함수(ROW_NUMBER·RANK·DENSE_RANK·NTILE)의 차이를 동점 데이터로 구분한다
> - 집계 윈도우로 비율·누적합·이동평균을 만들고, LAG/LEAD 로 전월 대비 증감률을 계산한다
> - 프레임 절(ROWS vs RANGE)의 동작을 이해하고 **LAST_VALUE 함정**을 피한다
> - 그룹별 TOP-N 패턴과 명명된 윈도우(`WINDOW w AS ...`)를 실무처럼 쓴다
>
> **선행 스텝**: Step 16
> **예상 소요**: 60분

> **MySQL 8.0 신기능**
> 윈도우 함수는 **MySQL 8.0에서 처음 추가**되었습니다. 5.7 이하에서는 사용자 변수(`@rn := @rn + 1`)를 이용한
> 악명 높은 트릭으로 흉내 내야 했고, 그 트릭은 평가 순서가 보장되지 않아 공식적으로는 "쓰지 말라"고 문서에 명시되어 있었습니다.
> 8.0부터는 그럴 필요가 전혀 없습니다.

이 스텝의 모든 예제는 **SELECT 전용**입니다. 어떤 테이블도 변경하지 않습니다.

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
```

---

## 17-1. GROUP BY 는 행을 접고, 윈도우 함수는 행을 남긴다

이것이 윈도우 함수의 존재 이유 전부입니다. 딱 한 문장으로 요약하면:

> **집계 함수는 여러 행을 한 행으로 "접는다". 윈도우 함수는 접지 않고, 계산 결과를 각 행 옆에 "붙인다".**

```
GROUP BY                          윈도우 함수
─────────────────────             ─────────────────────────────
product  price                    product  price   cat_avg
  A      1290000  ─┐                A      1290000  1490000
  B      1790000   ├─▶ avg=1490000  B      1790000  1490000
  C      2190000   │                C      2190000  1490000
  D       690000  ─┘                D       690000  1490000
   (4행 → 1행)                       (4행 → 4행. 행이 그대로!)
```

`GROUP BY` 로 카테고리 평균을 구하면 상품 이름은 사라집니다.

```sql
SELECT category_id,
       COUNT(*)   AS cnt,
       AVG(price) AS avg_price
FROM products
WHERE category_id IN (21, 22)
GROUP BY category_id;
```

**결과**
```
+-------------+-----+----------------+
| category_id | cnt | avg_price      |
+-------------+-----+----------------+
|          21 |   4 | 1490000.000000 |
|          22 |   3 | 1013000.000000 |
+-------------+-----+----------------+
```

같은 평균을 윈도우 함수로 구하면, 상품 행은 그대로 살아 있고 평균이 옆에 붙습니다.
그래서 **"각 상품이 카테고리 평균보다 얼마나 비싼가"** 를 한 방에 계산할 수 있습니다.
GROUP BY 로는 셀프 조인이나 서브쿼리를 한 번 더 써야 하는 작업입니다.

```sql
SELECT product_id, category_id, name, price,
       AVG(price) OVER (PARTITION BY category_id) AS cat_avg_price,
       price - AVG(price) OVER (PARTITION BY category_id) AS diff_from_avg
FROM products
WHERE category_id IN (21, 22)
ORDER BY category_id, price DESC;
```

**결과**
```
+------------+-------------+-----------------------------+------------+----------------+----------------+
| product_id | category_id | name                        | price      | cat_avg_price  | diff_from_avg  |
+------------+-------------+-----------------------------+------------+----------------+----------------+
|         14 |          21 | 게이밍 노트북 RTX4060       | 2190000.00 | 1490000.000000 |  700000.000000 |
|         13 |          21 | 울트라북 14 i7/32GB         | 1790000.00 | 1490000.000000 |  300000.000000 |
|         12 |          21 | 울트라북 14 i5/16GB         | 1290000.00 | 1490000.000000 | -200000.000000 |
|         15 |          21 | 보급형 노트북 15            |  690000.00 | 1490000.000000 | -800000.000000 |
|         17 |          22 | 스마트폰 X20 Pro 512GB      | 1490000.00 | 1013000.000000 |  477000.000000 |
|         16 |          22 | 스마트폰 X20 256GB          | 1150000.00 | 1013000.000000 |  137000.000000 |
|         18 |          22 | 스마트폰 A5 128GB           |  399000.00 | 1013000.000000 | -614000.000000 |
+------------+-------------+-----------------------------+------------+----------------+----------------+
```

> ⚠️ **함정 — 윈도우 함수는 WHERE 절에서 쓸 수 없다**
> `WHERE ROW_NUMBER() OVER (...) <= 3` 은 문법 에러입니다.
> SQL의 논리적 처리 순서가 `FROM → WHERE → GROUP BY → HAVING → **SELECT(윈도우 함수)** → ORDER BY` 이기 때문입니다.
> WHERE 가 평가되는 시점에는 윈도우 함수가 아직 계산되지 않았습니다.
> → 반드시 CTE/서브쿼리로 한 번 감싼 뒤 바깥에서 필터링합니다. (17-9 참고)

---

## 17-2. OVER 절 해부

```
함수(인자) OVER ( PARTITION BY ...   ORDER BY ...     프레임절 )
                 └──────┬──────┘   └────┬────┘   └────┬────┘
                   ① 어떻게 나눌까     ② 어떤 순서로   ③ 그중 어디까지
                   (생략 → 전체가 1덩어리) (생략 → 순서없음) (생략 → 자동 결정)
```

세 부품 모두 생략 가능합니다. `OVER ()` 처럼 빈 괄호면 **결과 집합 전체가 하나의 윈도우**입니다.
전체 합계 대비 비율을 구할 때 가장 자주 쓰는 형태입니다.

```sql
SELECT product_id, name, price,
       SUM(price)   OVER () AS total_price,
       COUNT(*)     OVER () AS row_cnt,
       ROUND(price / SUM(price) OVER () * 100, 1) AS pct
FROM products
WHERE category_id = 13
ORDER BY price DESC;
```

**결과**
```
+------------+---------------------------+-----------+-------------+---------+------+
| product_id | name                      | price     | total_price | row_cnt | pct  |
+------------+---------------------------+-----------+-------------+---------+------+
|         11 | 첼시 부츠                 | 189000.00 |   417000.00 |       3 | 45.3 |
|         10 | 러닝화 에어플로우         | 139000.00 |   417000.00 |       3 | 33.3 |
|          9 | 클래식 스니커즈           |  89000.00 |   417000.00 |       3 | 21.3 |
+------------+---------------------------+-----------+-------------+---------+------+
```

`PARTITION BY` 를 넣으면 윈도우가 그룹 단위로 쪼개집니다. **GROUP BY 와 달리 행 수는 그대로**입니다.

```sql
SELECT dept, name, salary,
       MAX(salary) OVER (PARTITION BY dept)                    AS dept_max,
       ROUND(salary / MAX(salary) OVER (PARTITION BY dept), 2) AS ratio_to_top
FROM employees
WHERE dept IN ('개발본부', '영업본부')
ORDER BY dept, salary DESC;
```

**결과**
```
+--------------+-----------+------------+------------+--------------+
| dept         | name      | salary     | dept_max   | ratio_to_top |
+--------------+-----------+------------+------------+--------------+
| 개발본부     | 김코드    | 9500000.00 | 9500000.00 |         1.00 |
| 개발본부     | 박서버    | 7200000.00 | 9500000.00 |         0.76 |
| 개발본부     | 최화면    | 7000000.00 | 9500000.00 |         0.74 |
| 개발본부     | 한백엔    | 5800000.00 | 9500000.00 |         0.61 |
| 개발본부     | 조리액    | 5600000.00 | 9500000.00 |         0.59 |
| 개발본부     | 임쿼리    | 4200000.00 | 9500000.00 |         0.44 |
| 개발본부     | 서인덱    | 4000000.00 | 9500000.00 |         0.42 |
| 개발본부     | 남뷰어    | 3900000.00 | 9500000.00 |         0.41 |
| 영업본부     | 이세일    | 9000000.00 | 9000000.00 |         1.00 |
| 영업본부     | 강매출    | 6800000.00 | 9000000.00 |         0.76 |
| 영업본부     | 배계약    | 5400000.00 | 9000000.00 |         0.60 |
| 영업본부     | 전상담    | 3700000.00 | 9000000.00 |         0.41 |
+--------------+-----------+------------+------------+--------------+
```

---

## 17-3. 순위 함수 — ROW_NUMBER / RANK / DENSE_RANK / NTILE

세 함수의 차이는 **동점(tie)이 있을 때만** 드러납니다. 동점이 없으면 셋 다 결과가 같습니다.

| 함수 | 동점 처리 | 1,1,1,45 인 경우 |
|---|---|---|
| `ROW_NUMBER()` | 동점을 무시하고 무조건 1,2,3,4… (**임의로** 순서를 정함) | 1, 2, 3, 4 |
| `RANK()` | 동점은 같은 순위, 다음 순위는 **건너뛴다** | 1, 1, 1, **4** |
| `DENSE_RANK()` | 동점은 같은 순위, 다음 순위는 **안 건너뛴다** | 1, 1, 1, **2** |
| `NTILE(n)` | 정렬 후 n개 버킷으로 균등 분할 | (버킷 번호) |

```sql
WITH sold AS (
  SELECT p.category_id, p.product_id, p.name, SUM(oi.quantity) AS qty
  FROM order_items oi
  JOIN orders   o ON o.order_id   = oi.order_id
  JOIN products p ON p.product_id = oi.product_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY p.category_id, p.product_id, p.name
)
SELECT category_id, name, qty,
       ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY qty DESC) AS rn,
       RANK()       OVER (PARTITION BY category_id ORDER BY qty DESC) AS rnk,
       DENSE_RANK() OVER (PARTITION BY category_id ORDER BY qty DESC) AS drnk,
       NTILE(2)     OVER (PARTITION BY category_id ORDER BY qty DESC) AS tile
FROM sold
WHERE category_id IN (11, 13)
ORDER BY category_id, qty DESC, name;
```

**결과**
```
+-------------+-------------------------------+------+----+-----+------+------+
| category_id | name                          | qty  | rn | rnk | drnk | tile |
+-------------+-------------------------------+------+----+-----+------+------+
|          11 | 라이트 다운 재킷              |   60 |  2 |   1 |    1 |    1 |
|          11 | 베이직 옥스퍼드 셔츠          |   60 |  1 |   1 |    1 |    1 |
|          11 | 울 니트 스웨터                |   60 |  3 |   1 |    1 |    2 |
|          11 | 슬림핏 치노 팬츠              |   45 |  4 |   4 |    2 |    2 |
|          13 | 러닝화 에어플로우             |   60 |  1 |   1 |    1 |    1 |
|          13 | 첼시 부츠                     |   60 |  2 |   1 |    1 |    1 |
|          13 | 클래식 스니커즈               |   30 |  3 |   3 |    2 |    2 |
+-------------+-------------------------------+------+----+-----+------+------+
```

`category_id=11` 을 보세요. 60이 세 개 동점입니다.
`RANK` 는 1,1,1 다음이 **4**로 점프하고, `DENSE_RANK` 는 1,1,1 다음이 **2**입니다.
`ROW_NUMBER` 는 셋에게 각각 다른 번호(2,1,3)를 붙였습니다.

> ⚠️ **함정 — ROW_NUMBER 는 동점일 때 순서가 보장되지 않는다**
> 위 결과에서 60짜리 세 상품의 `rn` 이 2,1,3 으로 뒤죽박죽인 것을 보세요.
> ORDER BY 가 `qty DESC` 뿐이므로 동점 사이의 순서는 MySQL 마음대로입니다.
> 실행 계획이 바뀌면 번호도 바뀔 수 있습니다.
> **"안정적인 번호"가 필요하면 반드시 타이브레이커를 넣으세요**: `ORDER BY qty DESC, product_id`.

> 💡 **실무 팁 — 어떤 걸 쓰나**
> - 중복 제거(각 그룹에서 1건만 뽑기) → `ROW_NUMBER()`
> - 순위표 게시(공동 3등이 둘이면 다음은 5등) → `RANK()`
> - "상위 3개 등급" 처럼 구간을 세고 싶을 때 → `DENSE_RANK()`
> - 4분위/10분위 나누기 → `NTILE(4)` / `NTILE(10)`

---

## 17-4. 집계 윈도우 — SUM / AVG / COUNT OVER

일반 집계 함수(`SUM`, `AVG`, `COUNT`, `MIN`, `MAX` …)에 `OVER` 만 붙이면 그대로 윈도우 함수가 됩니다.
전체 대비 비중 계산의 정석 패턴입니다.

```sql
WITH city_sales AS (
  SELECT shipping_city AS city, SUM(total_amount) AS amt
  FROM orders WHERE status <> 'CANCELLED'
  GROUP BY shipping_city
)
SELECT city, amt,
       SUM(amt) OVER ()                       AS grand_total,
       ROUND(amt / SUM(amt) OVER () * 100, 2) AS pct,
       ROUND(AVG(amt) OVER (), 0)             AS avg_city_amt,
       COUNT(*) OVER ()                       AS city_cnt
FROM city_sales
ORDER BY amt DESC;
```

**결과**
```
+--------+--------------+--------------+-------+--------------+----------+
| city   | amt          | grand_total  | pct   | avg_city_amt | city_cnt |
+--------+--------------+--------------+-------+--------------+----------+
| 부산   | 166978000.00 | 716856000.00 | 23.29 |     89607000 |        8 |
| 서울   | 145589500.00 | 716856000.00 | 20.31 |     89607000 |        8 |
| 대전   | 108697500.00 | 716856000.00 | 15.16 |     89607000 |        8 |
| 인천   |  96427000.00 | 716856000.00 | 13.45 |     89607000 |        8 |
| 수원   |  67918500.00 | 716856000.00 |  9.47 |     89607000 |        8 |
| 울산   |  61537500.00 | 716856000.00 |  8.58 |     89607000 |        8 |
| 대구   |  37468500.00 | 716856000.00 |  5.23 |     89607000 |        8 |
| 광주   |  32239500.00 | 716856000.00 |  4.50 |     89607000 |        8 |
+--------+--------------+--------------+-------+--------------+----------+
```

여기서 `GROUP BY` 와 `OVER ()` 가 한 쿼리에 공존한다는 점에 주목하세요.
CTE 안에서 먼저 도시별로 **접고**, 바깥에서 그 결과 집합 전체를 윈도우로 잡아 총합을 **붙였습니다**.

---

## 17-5. 프레임 절 (1) — ORDER BY 를 쓰는 순간 기본 프레임이 붙는다

여기가 윈도우 함수에서 가장 많이 틀리는 부분입니다. **프레임을 생략해도 프레임은 있습니다.**

| `OVER` 안에 ORDER BY | 자동으로 적용되는 기본 프레임 | 결과 |
|---|---|---|
| **없음** | 파티션 전체 | 전체 합계 (모든 행이 같은 값) |
| **있음** | `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` | **누적 합계** |

즉 `SUM(x) OVER (ORDER BY d)` 는 "전체 합"이 아니라 **"누적 합"** 입니다. 의도한 게 아니라면 버그입니다.

```sql
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       SUM(amt) OVER ()            AS sum_no_order,   -- 파티션 전체 합
       SUM(amt) OVER (ORDER BY ym) AS running_sum     -- 누적 합 (기본 프레임!)
FROM m ORDER BY ym;
```

**결과**
```
+---------+-------------+--------------+--------------+
| ym      | amt         | sum_no_order | running_sum  |
+---------+-------------+--------------+--------------+
| 2024-01 | 23577600.00 | 416400600.00 |  23577600.00 |
| 2024-02 | 37569800.00 | 416400600.00 |  61147400.00 |
| 2024-03 | 39916500.00 | 416400600.00 | 101063900.00 |
| 2024-04 | 38226300.00 | 416400600.00 | 139290200.00 |
| 2024-05 | 38634300.00 | 416400600.00 | 177924500.00 |
| 2024-06 | 42717900.00 | 416400600.00 | 220642400.00 |
| 2024-07 | 35525000.00 | 416400600.00 | 256167400.00 |
| 2024-08 | 36005000.00 | 416400600.00 | 292172400.00 |
| 2024-09 | 34139700.00 | 416400600.00 | 326312100.00 |
| 2024-10 | 33796100.00 | 416400600.00 | 360108200.00 |
| 2024-11 | 29206000.00 | 416400600.00 | 389314200.00 |
| 2024-12 | 27086400.00 | 416400600.00 | 416400600.00 |
+---------+-------------+--------------+--------------+
```

마지막 행에서 두 값이 같아지는 것을 보세요. 누적합이 끝까지 쌓이면 전체합과 만납니다.

---

## 17-6. 프레임 절 (2) — ROWS vs RANGE

프레임을 직접 쓰는 문법입니다.

```
{ROWS | RANGE} BETWEEN <시작> AND <끝>

  <시작>/<끝> 에 올 수 있는 것:
    UNBOUNDED PRECEDING   파티션의 맨 처음
    n PRECEDING           현재 행에서 n 앞
    CURRENT ROW           현재 행
    n FOLLOWING           현재 행에서 n 뒤
    UNBOUNDED FOLLOWING   파티션의 맨 끝
```

**ROWS 와 RANGE 의 차이는 "동점(peer)을 어떻게 볼 것인가"** 입니다.

- `ROWS` : **물리적인 행**을 센다. `CURRENT ROW` = 딱 그 한 행.
- `RANGE` : **ORDER BY 값이 같은 행들(peer)을 한 덩어리**로 본다. `CURRENT ROW` = 나와 값이 같은 모든 행.

```
qty:  60   60   60   45          ORDER BY qty DESC
      ─────────────────
ROWS  ①    ①②   ①②③  ①②③④    ← 행 단위로 하나씩 늘어남
RANGE [①②③] [①②③] [①②③] [①②③④] ← 60 세 개를 통째로 한 덩어리 취급
```

```sql
WITH sold AS (
  SELECT p.name, SUM(oi.quantity) AS qty
  FROM order_items oi
  JOIN orders   o ON o.order_id   = oi.order_id
  JOIN products p ON p.product_id = oi.product_id
  WHERE o.status <> 'CANCELLED' AND p.category_id = 11
  GROUP BY p.name
)
SELECT name, qty,
       SUM(qty) OVER (ORDER BY qty DESC
                      ROWS  BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS by_rows,
       SUM(qty) OVER (ORDER BY qty DESC
                      RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS by_range
FROM sold
ORDER BY qty DESC, name;
```

**결과**
```
+-------------------------------+------+---------+----------+
| name                          | qty  | by_rows | by_range |
+-------------------------------+------+---------+----------+
| 라이트 다운 재킷              |   60 |     120 |      180 |
| 베이직 옥스퍼드 셔츠          |   60 |      60 |      180 |
| 울 니트 스웨터                |   60 |     180 |      180 |
| 슬림핏 치노 팬츠              |   45 |     225 |      225 |
+-------------------------------+------+---------+----------+
```

`by_range` 는 동점 세 행 모두에게 **180 (=60×3)** 을 줍니다. 60짜리들을 한 덩어리로 보기 때문입니다.
`by_rows` 는 60, 120, 180 을 하나씩 나눠 가집니다.

> ⚠️ **함정 — ROWS 는 동점일 때 결과가 비결정적이다**
> 위 결과에서 `by_rows` 가 120, 60, 180 순으로 **뒤죽박죽**인 것을 보세요.
> 윈도우의 `ORDER BY qty DESC` 만으로는 60짜리 세 행의 내부 순서가 정해지지 않기 때문에,
> "몇 번째 행이냐"에 의존하는 ROWS 의 누적값이 행마다 제멋대로 붙었습니다.
> **ROWS 프레임을 쓸 땐 ORDER BY 를 유일하게 만드세요**: `ORDER BY qty DESC, name`.
> 반대로 `RANGE` 는 동점을 통째로 묶으므로 이런 문제가 없습니다.

> 💡 **실무 팁**
> 누적합·이동평균처럼 "몇 번째 행"이 중요한 계산은 `ROWS`,
> "값이 같으면 같이 취급"이 자연스러운 계산(예: 같은 날짜의 여러 주문)은 `RANGE` 를 쓰세요.
> 헷갈리면 **ROWS 를 기본으로 쓰고 ORDER BY 를 유일하게 만드는 것**이 가장 안전합니다.

### 이동 평균 (moving average)

`ROWS BETWEEN 2 PRECEDING AND CURRENT ROW` = 자신 포함 최근 3행.

```sql
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       ROUND(AVG(amt) OVER (ORDER BY ym
                            ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 0) AS ma3,
       COUNT(*) OVER (ORDER BY ym
                      ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)           AS window_rows
FROM m ORDER BY ym;
```

**결과**
```
+---------+-------------+----------+-------------+
| ym      | amt         | ma3      | window_rows |
+---------+-------------+----------+-------------+
| 2024-01 | 23577600.00 | 23577600 |           1 |
| 2024-02 | 37569800.00 | 30573700 |           2 |
| 2024-03 | 39916500.00 | 33687967 |           3 |
| 2024-04 | 38226300.00 | 38570867 |           3 |
| 2024-05 | 38634300.00 | 38925700 |           3 |
| 2024-06 | 42717900.00 | 39859500 |           3 |
| 2024-07 | 35525000.00 | 38959067 |           3 |
| 2024-08 | 36005000.00 | 38082633 |           3 |
| 2024-09 | 34139700.00 | 35223233 |           3 |
| 2024-10 | 33796100.00 | 34646933 |           3 |
| 2024-11 | 29206000.00 | 32380600 |           3 |
| 2024-12 | 27086400.00 | 30029500 |           3 |
+---------+-------------+----------+-------------+
```

`window_rows` 컬럼을 일부러 넣었습니다. 첫 두 달은 앞에 행이 없어서 프레임이 1개, 2개로 짧습니다.
**즉 1~2월의 "3개월 이동평균"은 사실 3개월 평균이 아닙니다.** 리포트에 낼 땐 이 구간을 버리거나 NULL 처리하세요.

---

## 17-7. ★ LAST_VALUE 함정 (반드시 기억할 것)

윈도우 함수를 처음 쓰는 사람이 100% 걸리는 함정입니다.

```sql
SELECT name, price,
       FIRST_VALUE(name) OVER (ORDER BY price DESC) AS first_v,
       LAST_VALUE(name)  OVER (ORDER BY price DESC) AS last_v_TRAP,   -- 함정!
       LAST_VALUE(name)  OVER (ORDER BY price DESC
                               ROWS BETWEEN UNBOUNDED PRECEDING
                                        AND UNBOUNDED FOLLOWING) AS last_v_FIXED
FROM products
WHERE category_id = 21
ORDER BY price DESC;
```

**결과**
```
+-----------------------------+------------+-----------------------------+-----------------------------+------------------------+
| name                        | price      | first_v                     | last_v_TRAP                 | last_v_FIXED           |
+-----------------------------+------------+-----------------------------+-----------------------------+------------------------+
| 게이밍 노트북 RTX4060       | 2190000.00 | 게이밍 노트북 RTX4060       | 게이밍 노트북 RTX4060       | 보급형 노트북 15       |
| 울트라북 14 i7/32GB         | 1790000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i7/32GB         | 보급형 노트북 15       |
| 울트라북 14 i5/16GB         | 1290000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i5/16GB         | 보급형 노트북 15       |
| 보급형 노트북 15            |  690000.00 | 게이밍 노트북 RTX4060       | 보급형 노트북 15            | 보급형 노트북 15       |
+-----------------------------+------------+-----------------------------+-----------------------------+------------------------+
```

`last_v_TRAP` 을 보세요. **"마지막 값"이 아니라 자기 자신의 이름이 나옵니다.**

**왜?** 17-5 에서 배운 대로, `ORDER BY` 가 있으면 기본 프레임이
`RANGE BETWEEN UNBOUNDED PRECEDING AND **CURRENT ROW**` 로 붙습니다.
프레임이 "처음 ~ **현재 행**" 까지이므로 그 프레임의 **마지막 = 현재 행**입니다. 당연히 자기 자신이죠.

```
행1 [■]                     ← 프레임 끝 = 행1 → LAST_VALUE = 행1
행2 [■ ■]                   ← 프레임 끝 = 행2 → LAST_VALUE = 행2
행3 [■ ■ ■]                 ← 프레임 끝 = 행3 → LAST_VALUE = 행3
행4 [■ ■ ■ ■]               ← 프레임 끝 = 행4 → LAST_VALUE = 행4
```

`FIRST_VALUE` 가 멀쩡해 보이는 이유도 같습니다. 프레임의 **시작**은 항상 파티션 시작(UNBOUNDED PRECEDING)이니까요.

**해결책**: 프레임을 끝까지 명시적으로 넓힙니다.
```sql
LAST_VALUE(name) OVER (ORDER BY price DESC
                       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
```

> ⚠️ **NTH_VALUE 도 똑같은 함정에 걸립니다.**
> `NTH_VALUE(name, 2) OVER (ORDER BY price DESC)` 는 첫 행에서 프레임에 행이 1개뿐이라 NULL 을 냅니다.
> 역시 프레임을 `UNBOUNDED FOLLOWING` 까지 넓혀야 합니다.
> **반대로 `FIRST_VALUE`, `LAG`, `LEAD`, `ROW_NUMBER`, `RANK` 는 프레임의 영향을 받지 않습니다.**

---

## 17-8. 오프셋 함수 — LAG / LEAD 로 전월 대비 증감률

```
LAG(expr [, n [, default]])   : n 행 앞(과거)의 값. 기본 n=1
LEAD(expr [, n [, default]])  : n 행 뒤(미래)의 값. 기본 n=1
```

시계열 분석의 주력 무기입니다. 자기 자신과 이전 행을 비교하기 위해 셀프 조인을 할 필요가 없어졌습니다.

```sql
WITH m AS (
  SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
  FROM orders
  WHERE status <> 'CANCELLED'
    AND order_date >= '2024-01-01' AND order_date < '2025-01-01'
  GROUP BY ym
)
SELECT ym, amt,
       LAG(amt)  OVER (ORDER BY ym)      AS prev_amt,
       amt - LAG(amt) OVER (ORDER BY ym) AS diff,
       CONCAT(ROUND((amt - LAG(amt) OVER (ORDER BY ym))
                    / LAG(amt) OVER (ORDER BY ym) * 100, 1), '%') AS mom_pct,
       LEAD(amt) OVER (ORDER BY ym)      AS next_amt
FROM m ORDER BY ym;
```

**결과**
```
+---------+-------------+-------------+-------------+---------+-------------+
| ym      | amt         | prev_amt    | diff        | mom_pct | next_amt    |
+---------+-------------+-------------+-------------+---------+-------------+
| 2024-01 | 23577600.00 |        NULL |        NULL | NULL    | 37569800.00 |
| 2024-02 | 37569800.00 | 23577600.00 | 13992200.00 | 59.3%   | 39916500.00 |
| 2024-03 | 39916500.00 | 37569800.00 |  2346700.00 | 6.2%    | 38226300.00 |
| 2024-04 | 38226300.00 | 39916500.00 | -1690200.00 | -4.2%   | 38634300.00 |
| 2024-05 | 38634300.00 | 38226300.00 |   408000.00 | 1.1%    | 42717900.00 |
| 2024-06 | 42717900.00 | 38634300.00 |  4083600.00 | 10.6%   | 35525000.00 |
| 2024-07 | 35525000.00 | 42717900.00 | -7192900.00 | -16.8%  | 36005000.00 |
| 2024-08 | 36005000.00 | 35525000.00 |   480000.00 | 1.4%    | 34139700.00 |
| 2024-09 | 34139700.00 | 36005000.00 | -1865300.00 | -5.2%   | 33796100.00 |
| 2024-10 | 33796100.00 | 34139700.00 |  -343600.00 | -1.0%   | 29206000.00 |
| 2024-11 | 29206000.00 | 33796100.00 | -4590100.00 | -13.6%  | 27086400.00 |
| 2024-12 | 27086400.00 | 29206000.00 | -2119600.00 | -7.3%   |        NULL |
+---------+-------------+-------------+-------------+---------+-------------+
```

첫 행의 `prev_amt` 와 마지막 행의 `next_amt` 는 참조할 행이 없으므로 NULL 입니다.
NULL 이 싫으면 세 번째 인자로 기본값을 주세요: `LAG(amt, 1, 0)`.

> ⚠️ **함정 — 전년 동월 대비(YoY)를 `LAG(amt, 12)` 로 구하면 위험하다**
> `LAG(amt, 12)` 는 "12행 앞"이지 "12개월 앞"이 아닙니다.
> 매출이 0인 달이 있어서 그 행 자체가 결과에 없으면, 12행 앞은 12개월 앞이 아니게 됩니다.
> 안전하게 하려면 달력 테이블(또는 `tally`)로 빈 달을 먼저 채운 뒤 LAG 를 쓰거나,
> `ym` 을 직접 계산해 조인하세요.

### FIRST_VALUE / NTH_VALUE

```sql
SELECT category_id, name, price,
       FIRST_VALUE(name) OVER w AS top1,
       NTH_VALUE(name, 2) OVER (PARTITION BY category_id ORDER BY price DESC
                                ROWS BETWEEN UNBOUNDED PRECEDING
                                         AND UNBOUNDED FOLLOWING) AS top2
FROM products
WHERE category_id IN (13, 21)
WINDOW w AS (PARTITION BY category_id ORDER BY price DESC)
ORDER BY category_id, price DESC;
```

**결과**
```
+-------------+-----------------------------+------------+-----------------------------+---------------------------+
| category_id | name                        | price      | top1                        | top2                      |
+-------------+-----------------------------+------------+-----------------------------+---------------------------+
|          13 | 첼시 부츠                   |  189000.00 | 첼시 부츠                   | 러닝화 에어플로우         |
|          13 | 러닝화 에어플로우           |  139000.00 | 첼시 부츠                   | 러닝화 에어플로우         |
|          13 | 클래식 스니커즈             |   89000.00 | 첼시 부츠                   | 러닝화 에어플로우         |
|          21 | 게이밍 노트북 RTX4060       | 2190000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i7/32GB       |
|          21 | 울트라북 14 i7/32GB         | 1790000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i7/32GB       |
|          21 | 울트라북 14 i5/16GB         | 1290000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i7/32GB       |
|          21 | 보급형 노트북 15            |  690000.00 | 게이밍 노트북 RTX4060       | 울트라북 14 i7/32GB       |
+-------------+-----------------------------+------------+-----------------------------+---------------------------+
```

---

## 17-9. 실전 패턴 — 그룹별 TOP-N

가장 자주 쓰이는 윈도우 함수 활용법입니다. **"카테고리별 매출 상위 2개 상품"**.

윈도우 함수는 WHERE 에서 못 쓰므로(17-1 함정), **CTE 로 감싸고 바깥에서 `WHERE rn <= 2`** 합니다.

```sql
WITH rev AS (
  SELECT c.name AS category, p.name AS product,
         SUM(oi.quantity * oi.unit_price) AS revenue
  FROM order_items oi
  JOIN orders     o ON o.order_id    = oi.order_id
  JOIN products   p ON p.product_id  = oi.product_id
  JOIN categories c ON c.category_id = p.category_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY c.name, p.name
),
ranked AS (
  SELECT rev.*,
         ROW_NUMBER() OVER (PARTITION BY category ORDER BY revenue DESC) AS rn
  FROM rev
)
SELECT category, rn, product, revenue
FROM ranked
WHERE rn <= 2
ORDER BY category, rn;
```

**결과** (23행)
```
+--------------+----+---------------------------------+--------------+
| category     | rn | product                         | revenue      |
+--------------+----+---------------------------------+--------------+
| IT/컴퓨터    |  1 | 실전 MySQL 8                    |   2280000.00 |
| IT/컴퓨터    |  2 | 모던 자바스크립트               |   2040000.00 |
| 가공식품     |  1 | 콜드브루 원액 1L                |    954000.00 |
| 가공식품     |  2 | 다크초콜릿 72% 100g             |    294000.00 |
| 가구         |  1 | 원목 4인 식탁                   |  27540000.00 |
| 가구         |  2 | 인체공학 사무용 의자            |  14805000.00 |
| 남성의류     |  1 | 라이트 다운 재킷                |   9540000.00 |
| 남성의류     |  2 | 울 니트 스웨터                  |   4740000.00 |
| 노트북       |  1 | 게이밍 노트북 RTX4060           | 131400000.00 |
| 노트북       |  2 | 울트라북 14 i7/32GB             | 107400000.00 |
| 소설         |  1 | 여름의 끝에서                   |    948000.00 |
| 스마트폰     |  1 | 스마트폰 X20 Pro 512GB          |  89400000.00 |
| 스마트폰     |  2 | 스마트폰 X20 256GB              |  69000000.00 |
| 신발         |  1 | 첼시 부츠                       |  11340000.00 |
| 신발         |  2 | 러닝화 에어플로우               |   8340000.00 |
| 신선식품     |  1 | 한우 등심 300g                  |   2160000.00 |
| 신선식품     |  2 | 노르웨이 연어 필렛 500g         |   1740000.00 |
| 여성의류     |  1 | 트렌치 코트                     |  14940000.00 |
| 여성의류     |  2 | 실크 블라우스                   |   7740000.00 |
| 주방용품     |  1 | 무쇠 프라이팬 28cm              |   5355000.00 |
| 주방용품     |  2 | 스테인리스 냄비 24cm            |   5340000.00 |
| 주변기기     |  1 | 27인치 4K 모니터                |  27540000.00 |
| 주변기기     |  2 | 노이즈캔슬링 헤드폰             |  19740000.00 |
+--------------+----+---------------------------------+--------------+
```

"소설"은 상품이 1개라 1행만 나왔습니다. TOP-N 은 N개보다 적게 나올 수 있습니다.

> 💡 **실무 팁 — TOP-N 에 무엇을 쓸까**
> - "정확히 N건" 이 필요하다 (중복 제거) → `ROW_NUMBER()`
> - "공동 N위까지 전부" 가 필요하다 → `RANK()` (동점이면 N개를 넘을 수 있음)

---

## 17-10. 명명된 윈도우 — `WINDOW w AS (...)`

같은 `OVER (...)` 를 여러 번 쓰면 길고 오타 나기 쉽습니다. 이름을 붙이세요.
`WINDOW` 절의 위치는 **`HAVING` 뒤, `ORDER BY` 앞**입니다.

이름 붙인 윈도우를 **상속해서 프레임만 바꿔** 쓸 수도 있습니다: `OVER (w ROWS BETWEEN ...)`.

```sql
SELECT dept, name, salary,
       ROW_NUMBER() OVER w                                    AS rn,
       SUM(salary)  OVER w                                    AS running_sum,
       SUM(salary)  OVER (w ROWS BETWEEN UNBOUNDED PRECEDING
                                     AND UNBOUNDED FOLLOWING) AS dept_total,
       LAG(salary)  OVER w                                    AS prev_salary
FROM employees
WHERE dept = '개발본부'
WINDOW w AS (PARTITION BY dept ORDER BY salary DESC)
ORDER BY salary DESC;
```

**결과**
```
+--------------+-----------+------------+----+-------------+-------------+-------------+
| dept         | name      | salary     | rn | running_sum | dept_total  | prev_salary |
+--------------+-----------+------------+----+-------------+-------------+-------------+
| 개발본부     | 김코드    | 9500000.00 |  1 |  9500000.00 | 47200000.00 |        NULL |
| 개발본부     | 박서버    | 7200000.00 |  2 | 16700000.00 | 47200000.00 |  9500000.00 |
| 개발본부     | 최화면    | 7000000.00 |  3 | 23700000.00 | 47200000.00 |  7200000.00 |
| 개발본부     | 한백엔    | 5800000.00 |  4 | 29500000.00 | 47200000.00 |  7000000.00 |
| 개발본부     | 조리액    | 5600000.00 |  5 | 35100000.00 | 47200000.00 |  5800000.00 |
| 개발본부     | 임쿼리    | 4200000.00 |  6 | 39300000.00 | 47200000.00 |  5600000.00 |
| 개발본부     | 서인덱    | 4000000.00 |  7 | 43300000.00 | 47200000.00 |  4200000.00 |
| 개발본부     | 남뷰어    | 3900000.00 |  8 | 47200000.00 | 47200000.00 |  4000000.00 |
+--------------+-----------+------------+----+-------------+-------------+-------------+
```

`w` 하나를 정의해서 `ROW_NUMBER`, `SUM`, `LAG` 세 곳에 재사용했고,
`dept_total` 만 `w` 를 상속받아 프레임을 파티션 전체로 넓혔습니다.

> ⚠️ **상속 규칙**
> `OVER (w ...)` 로 상속할 때 **PARTITION BY 는 추가할 수 없고**, `w` 에 이미 ORDER BY 가 있으면 **ORDER BY 도 덮어쓸 수 없습니다.**
> 상속으로 바꿀 수 있는 건 사실상 **프레임 절뿐**입니다.

---

## 정리

### 윈도우 함수 종류

| 분류 | 함수 | 설명 | 프레임 영향 |
|---|---|---|---|
| 순위 | `ROW_NUMBER()` | 동점 무시, 1,2,3,4 | 없음 |
| 순위 | `RANK()` | 동점 같은 순위, 다음 건너뜀 (1,1,3) | 없음 |
| 순위 | `DENSE_RANK()` | 동점 같은 순위, 안 건너뜀 (1,1,2) | 없음 |
| 순위 | `NTILE(n)` | n개 버킷으로 균등 분할 | 없음 |
| 순위 | `PERCENT_RANK()` / `CUME_DIST()` | 백분위 순위 / 누적 분포 | 없음 |
| 집계 | `SUM/AVG/COUNT/MIN/MAX ... OVER` | 집계값을 각 행에 붙임 | **있음** |
| 오프셋 | `LAG(x,n,def)` / `LEAD(x,n,def)` | n행 앞/뒤 값 | 없음 |
| 오프셋 | `FIRST_VALUE(x)` | 프레임의 첫 값 | **있음** (보통 문제 없음) |
| 오프셋 | `LAST_VALUE(x)` | 프레임의 마지막 값 | **있음 ← 함정!** |
| 오프셋 | `NTH_VALUE(x,n)` | 프레임의 n번째 값 | **있음 ← 함정!** |

### 기본 프레임 (외우세요)

| OVER 안에 ORDER BY | 기본 프레임 | 의미 |
|---|---|---|
| 없음 | 파티션 전체 | 전체 집계 |
| 있음 | `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` | **누적 집계** |

### GROUP BY vs 윈도우 함수

| | GROUP BY | 윈도우 함수 |
|---|---|---|
| 행 수 | 그룹당 1행으로 **줄어듦** | **그대로 유지** |
| 개별 컬럼 접근 | 불가 (집계/그룹 키만) | 가능 |
| WHERE 에서 필터 | `HAVING` 사용 | **불가** → CTE 로 감싸기 |
| 도입 버전 | 아주 오래됨 | **MySQL 8.0** |
| 같이 쓸 수 있나 | — | **가능** (GROUP BY 후 그 결과에 윈도우 적용) |

### 자주 쓰는 프레임 레시피

```sql
-- 누적합
SUM(x) OVER (ORDER BY d ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
-- 3구간 이동평균
AVG(x) OVER (ORDER BY d ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
-- 중심 이동평균 (앞뒤 1칸씩)
AVG(x) OVER (ORDER BY d ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)
-- 파티션 전체 (LAST_VALUE 용)
LAST_VALUE(x) OVER (ORDER BY d ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
```

---

## 연습문제

`exercise.sql` 을 열어 8개 문제를 풀어 보세요. 정답과 해설은 `solution.sql` 에 있습니다.

1. 고객별 총 구매액 순위 (RANK)
2. 등급 안에서의 순위와 등급 밖 전체 순위 동시에
3. 카테고리별 가격 상위 1개 상품 (TOP-N)
4. 2025년 월별 매출 누적합
5. 5개월 이동평균
6. 전월 대비 증감률 + 최고 매출 월 표시 (LAST_VALUE 함정 포함)
7. 주문 간격 계산 (LAG + DATEDIFF)
8. NTILE 로 고객 4분위 나누기

---

## 다음 단계
→ [Step 18 — JSON](../step-18-json/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 구성됩니다. 먼저 `practice.sql` 을 통째로 실행해 본문 17-1 ~ 17-10 의 예제 결과를 눈으로 확인하고,
그다음 `exercise.sql` 의 8문제를 직접 풀어 본 뒤, 마지막에 `solution.sql` 로 답을 맞춰 보는 순서입니다.
세 파일 모두 **SELECT 전용**이라 몇 번을 돌려도 데이터가 변하지 않습니다. 마음 놓고 반복 실행하세요.

### practice.sql

본문의 모든 예제 쿼리를 한 파일에 모아 둔 **따라 하기용 스크립트**입니다. 맨 위 `USE shop;` 으로 실습용 스키마를 잡고,
`[17-1]` 부터 `[17-14]` 까지 번호가 붙은 14개 블록이 차례로 실행됩니다.

- `[17-1]` ~ `[17-3]`: `GROUP BY` 로 접은 결과와 `AVG(price) OVER (PARTITION BY category_id)` 로 붙인 결과를 나란히 실행해 **행이 접히느냐 남느냐**의 차이를 보여줍니다. `OVER ()` 빈 괄호(전체가 한 윈도우)와 `PARTITION BY dept`(부서 단위 윈도우)를 대비시킵니다.
- `[17-4]`: `sold` CTE 로 카테고리별 판매 수량을 만든 뒤 `ROW_NUMBER` / `RANK` / `DENSE_RANK` / `NTILE(2)` 를 **한 SELECT 에서 동시에** 뽑습니다. `WHERE category_id IN (11, 13)` 으로 좁힌 이유는 category 11 에 수량 60 짜리 동점이 세 개 있어서 세 함수의 차이가 확연히 드러나기 때문입니다.
- `[17-5]`: `city_sales` CTE 로 `shipping_city` 별 매출을 접은 뒤, 바깥에서 `SUM(amt) OVER ()` / `AVG(amt) OVER ()` / `COUNT(*) OVER ()` 를 붙여 **전체 대비 비중(pct)** 을 계산합니다. `GROUP BY` 와 `OVER ()` 가 한 쿼리 안에 공존하는 대표 사례입니다.
- `[17-6]` ~ `[17-8]`: 프레임 절 실습입니다. `SUM(amt) OVER ()` 와 `SUM(amt) OVER (ORDER BY ym)` 을 같은 행에 나란히 놓아 **ORDER BY 를 쓰는 순간 기본 프레임이 붙어 누적합이 된다**는 사실을 한눈에 보게 만듭니다. `[17-7]` 의 `by_rows` / `by_range` 비교, `[17-8]` 의 `ROWS BETWEEN 2 PRECEDING AND CURRENT ROW` 이동평균이 이어집니다.
- `[17-8]` 의 `COUNT(*) OVER (...)` = `window_rows` 컬럼은 일부러 넣은 것입니다. 첫 두 달은 프레임에 행이 1개·2개뿐이라 "3개월 이동평균"이 실제로는 3개월 평균이 아니라는 점을 숫자로 증명합니다.
- `[17-9]`: 이 스텝의 하이라이트인 **LAST_VALUE 함정**입니다. `last_v_TRAP` 과 `last_v_FIXED` 두 컬럼을 같은 쿼리에 넣어, 프레임을 `ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING` 으로 넓혔을 때만 진짜 "마지막 값"이 나온다는 것을 보여줍니다.
- `[17-10]` ~ `[17-11]`: 오프셋 함수입니다. `[17-10]` 은 2024년 월별 매출에 `LAG(amt)` / `LEAD(amt)` 를 붙여 전월 대비 증감액(diff)과 증감률(mom_pct)을 구합니다. `[17-11]` 은 `FIRST_VALUE(name) OVER w`(명명된 윈도우 `w` 사용)와 `NTH_VALUE(name, 2)` 를 대비시키는데, `NTH_VALUE` 쪽에만 `ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING` 프레임이 붙어 있습니다. `FIRST_VALUE` 는 프레임의 **시작**을 보므로 기본 프레임으로도 멀쩡하지만, `NTH_VALUE` 는 `LAST_VALUE` 와 똑같은 함정에 걸리기 때문입니다.
- `[17-12]` ~ `[17-14]`: 실전 패턴(TOP-N, 명명된 윈도우, 고객 등급별 종합 리포트)입니다. `[17-14]` 는 `RANK`, `SUM OVER (PARTITION BY grade)`, `NTILE(4)` 를 한 쿼리에 섞어 쓰는 마무리 예제입니다.

> 주의: 이 파일은 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql` 로 실행합니다.
> `--table` 옵션을 빼면 결과가 탭 구분 텍스트로 나와 본문의 표와 모양이 달라집니다.

```sql file="./practice.sql"
```

### exercise.sql

직접 풀어 볼 **8문제 문제지**입니다. 각 문제 아래 `-- 여기에 작성` 자리에 여러분의 쿼리를 채워 넣으세요.
문제는 본문 순서를 그대로 따라가며 난이도가 올라갑니다.

- 문제 1~2 는 순위 함수입니다. 문제 1 이 "동점이면 같은 순위, 다음 순위는 건너뜁니다"라고 못 박은 것은 `ROW_NUMBER` 가 아니라 `RANK()` 를 쓰라는 뜻입니다.
- 문제 3 은 TOP-N 패턴이며 힌트에 "윈도우 함수는 WHERE 에서 못 씁니다. CTE 로 감싸세요"라고 명시되어 있습니다. 17-9 절의 정석 패턴을 그대로 적용하면 됩니다.
- 문제 4~5 는 프레임 절(누적합, 5개월 이동평균)입니다. 문제 5 가 `window_rows` 를 같이 보여 달라고 하는 이유는 프레임이 짧은 앞부분 구간을 스스로 발견하게 하기 위함입니다.
- 문제 6 이 이 문제지의 핵심입니다. "best_amt 는 **모든 행에서 같은 값**이어야 합니다"라는 조건이 곧 LAST_VALUE 함정을 피하라는 요구입니다. 프레임을 넓히지 않으면 행마다 다른 값이 나와 조건을 만족하지 못합니다.
- 문제 7~8 은 `LAG` + `DATEDIFF` 조합과 `NTILE(4)` 활용입니다.

`USE shop;` 이 파일 상단에 있으니 스키마 지정은 따로 하지 않아도 됩니다. 모두 SELECT 문이므로 테이블을 변경하는 쿼리는 쓰지 마세요.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 8문제의 **정답과 해설**입니다. 각 정답 위에 `-- 해설:` 블록이 붙어 있어 "왜 이 함수를 골랐는지"까지 읽을 수 있습니다.
먼저 스스로 풀어 본 뒤에 열어 보세요.

- 정답 2 의 해설이 특히 중요합니다. `grade_avg` 를 만들 때 `PARTITION BY grade` 만 쓰고 **ORDER BY 를 넣지 않은** 이유를 설명합니다. ORDER BY 를 넣으면 기본 프레임 때문에 "등급 평균"이 아니라 "누적 평균"이 되어 버립니다.
- 정답 6 은 `best_amt`(프레임을 `UNBOUNDED FOLLOWING` 까지 넓힌 정답), `best_amt_simple`(`MAX(amt) OVER ()` — 사실 이게 제일 간단합니다), `best_amt_BAD`(프레임을 안 넓힌 오답) 세 컬럼을 **한 쿼리에 나란히** 뽑습니다. 결과를 보면 `best_amt_BAD` 만 행마다 값이 달라지는 것을 눈으로 확인할 수 있습니다. 함정을 직접 보라고 일부러 넣은 오답 컬럼입니다.
- 정답 8 의 해설은 `NTILE(4)` 가 **"금액을 4등분"이 아니라 "행 수를 4등분"** 이라는 흔한 오해를 짚어 줍니다. 행 수가 4로 나눠떨어지지 않으면 앞쪽 버킷이 1개씩 더 가져갑니다.
- 정답 4·5·6 은 모두 2025년 구간(`order_date >= '2025-01-01' AND order_date < '2026-01-01'`)을 씁니다. 본문 예제가 2024년을 쓰는 것과 대비되니 연도를 헷갈리지 마세요.

```sql file="./solution.sql"
```
