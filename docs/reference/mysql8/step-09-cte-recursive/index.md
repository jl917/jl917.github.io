# Step 09 — CTE와 재귀 쿼리

> **학습 목표**
> - `WITH` 절(CTE)로 중첩 서브쿼리를 풀어 쓰고 가독성을 끌어올린다
> - 여러 CTE를 체이닝해서 "계산 단계"를 이름으로 표현한다
> - 재귀 CTE(`WITH RECURSIVE`, **MySQL 8.0+**)로 조직도를 전개한다
> - 재귀로 날짜 시퀀스를 만들고 **빈 구간(gap)** 을 0으로 채운다
> - `cte_max_recursion_depth` 와 무한루프 방어법을 안다
>
> **선행 스텝**: Step 08 (서브쿼리)
> **예상 소요**: 60분

---

## 9-1. CTE란 : 파생 테이블에 이름을 붙인 것

Step 08 의 파생 테이블은 강력하지만 한 가지 문제가 있습니다. **쿼리 한가운데에 박혀 있어서** 읽기 어렵습니다. 계산 단계가 3개면 괄호가 3겹으로 중첩되고, 어디부터 읽어야 할지 알 수 없게 됩니다.

CTE(Common Table Expression)는 그 파생 테이블을 **쿼리 맨 앞으로 빼내고 이름을 붙이는** 문법입니다. 결과는 같지만, 읽는 사람은 "위에서 아래로" 자연스럽게 따라갈 수 있습니다.

```sql
WITH customer_stats AS (
    SELECT customer_id,
           COUNT(*)          AS order_cnt,
           SUM(total_amount) AS sum_amount
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
)
SELECT c.customer_id, c.name, s.order_cnt, s.sum_amount
FROM customer_stats s
JOIN customers c ON c.customer_id = s.customer_id
WHERE s.sum_amount >= 40000000
ORDER BY s.sum_amount DESC;
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
|          30 | 하준서    |        20 | 41989500.00 |
+-------------+-----------+-----------+-------------+
```

### 파생 테이블이 못 하는 것 : 재사용

CTE 는 **한 번 정의하고 여러 번 참조**할 수 있습니다. 파생 테이블은 쓸 때마다 통째로 복사해 붙여야 합니다. 월별 매출을 자기 자신과 조인해 전월 대비 성장률을 내 봅시다.

```sql
WITH monthly AS (
    SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym,
           SUM(total_amount) AS amt
    FROM orders
    WHERE status <> 'CANCELLED'
    GROUP BY ym
)
SELECT cur.ym,
       cur.amt,
       prev.amt AS prev_amt,
       ROUND((cur.amt - prev.amt) / prev.amt * 100, 1) AS growth_pct
FROM monthly cur
JOIN monthly prev                       -- ← 같은 CTE 를 두 번 참조
  ON prev.ym = DATE_FORMAT(DATE_SUB(STR_TO_DATE(CONCAT(cur.ym,'-01'), '%Y-%m-%d'),
                                    INTERVAL 1 MONTH), '%Y-%m')
ORDER BY cur.ym;
```

**결과**
```
+---------+-------------+-------------+------------+
| ym      | amt         | prev_amt    | growth_pct |
+---------+-------------+-------------+------------+
| 2024-02 | 37569800.00 | 23577600.00 |       59.3 |
| 2024-03 | 39916500.00 | 37569800.00 |        6.2 |
| 2024-04 | 38226300.00 | 39916500.00 |       -4.2 |
| 2024-05 | 38634300.00 | 38226300.00 |        1.1 |
| 2024-06 | 42717900.00 | 38634300.00 |       10.6 |
| 2024-07 | 35525000.00 | 42717900.00 |      -16.8 |
| 2024-08 | 36005000.00 | 35525000.00 |        1.4 |
| 2024-09 | 34139700.00 | 36005000.00 |       -5.2 |
| 2024-10 | 33796100.00 | 34139700.00 |       -1.0 |
| 2024-11 | 29206000.00 | 33796100.00 |      -13.6 |
+---------+-------------+-------------+------------+
... (총 23행)
```

실행계획을 보면 CTE 가 **한 번만 구체화**되고 두 번 읽히는 것을 확인할 수 있습니다.

```sql
EXPLAIN FORMAT=TREE
WITH monthly AS (
    SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, SUM(total_amount) AS amt
    FROM orders GROUP BY ym
)
SELECT a.ym, a.amt, b.amt FROM monthly a JOIN monthly b ON b.ym = a.ym;
```

**결과**
```
-> Nested loop inner join  (cost=1570 rows=0)
    -> Filter: (a.ym is not null)  (cost=0.117..70 rows=600)
        -> Table scan on a  (cost=2.5..2.5 rows=0)
            -> Materialize CTE monthly if needed  (cost=0..0 rows=0)
                -> Table scan on <temporary>
                    -> Aggregate using temporary table
                        -> Table scan on orders  (cost=61 rows=600)
    -> Index lookup on b using <auto_key0> (ym=a.ym)  (cost=0.25..2.5 rows=10)
        -> Materialize CTE monthly if needed (query plan printed elsewhere)  (cost=0..0 rows=0)
```

> 💡 **실무 팁**: `Materialize CTE ... if needed` — MySQL 8 은 CTE 를 **필요할 때만** 임시 테이블로 만듭니다. 한 번만 참조되고 머지 가능한 CTE 는 파생 테이블과 똑같이 머지됩니다. 즉 **CTE 를 쓴다고 느려지지 않습니다.** 가독성을 위해 마음껏 쓰세요.
> ⚠️ 단, MySQL 의 CTE 는 여러 번 참조돼도 **한 번만 계산**되는 것이 보장되지는 않습니다. 위 계획처럼 구체화되면 재사용되지만, 머지되면 두 번 실행될 수 있습니다.

---

## 9-2. CTE 체이닝 : 계산 단계를 이름으로

CTE 는 콤마로 여러 개를 이어 쓸 수 있고, **뒤의 CTE 가 앞의 CTE 를 참조**할 수 있습니다. 복잡한 집계를 "1단계 → 2단계 → 3단계" 파이프라인으로 표현하면 리뷰하기도 디버깅하기도 쉬워집니다.

```sql
WITH
paid_orders AS (                       -- 1단계: 유효 주문만 추린다
    SELECT order_id, customer_id, total_amount, order_date
    FROM orders
    WHERE status IN ('PAID','SHIPPED','DELIVERED')
),
per_customer AS (                      -- 2단계: 고객별로 집계
    SELECT customer_id,
           COUNT(*)                 AS cnt,
           ROUND(AVG(total_amount)) AS avg_amt
    FROM paid_orders
    GROUP BY customer_id
),
ranked AS (                            -- 3단계: 고객 정보를 붙인다
    SELECT p.*, c.name, c.grade
    FROM per_customer p
    JOIN customers c ON c.customer_id = p.customer_id
)
SELECT customer_id, name, grade, cnt, avg_amt
FROM ranked
ORDER BY avg_amt DESC
LIMIT 5;
```

**결과**
```
+-------------+-----------+--------+-----+---------+
| customer_id | name      | grade  | cnt | avg_amt |
+-------------+-----------+--------+-----+---------+
|           8 | 임수진    | SILVER |  20 | 2922450 |
|           5 | 정  훈    | GOLD   |  20 | 2578400 |
|          21 | 황도윤    | BRONZE |  20 | 2512425 |
|          14 | 남규리    | BRONZE |  20 | 2316100 |
|          17 | 백승호    | BRONZE |  20 | 2285850 |
+-------------+-----------+--------+-----+---------+
```

> ⚠️ **함정**: CTE 는 **앞에서 뒤로만** 참조할 수 있습니다. `per_customer` 가 아직 정의되지 않은 `ranked` 를 참조하면 에러입니다. (예외: `WITH RECURSIVE` 에서 자기 자신을 참조하는 경우)

---

## 9-3. 재귀 CTE (MySQL 8.0+)

MySQL 5.7 에는 없었습니다. **8.0 에서 처음 추가된** 기능이고, 계층 데이터를 다루는 방식을 완전히 바꿔놨습니다.

재귀 CTE 는 항상 두 부분으로 이루어집니다.

```
WITH RECURSIVE cte_name AS (
    <앵커(anchor)>          -- 시작점. 자기 자신을 참조하지 않는다
    UNION ALL
    <재귀(recursive)>       -- cte_name 을 참조한다. 여기서 결과가 늘어난다
)
```

동작은 이렇습니다: ① 앵커를 실행해 첫 결과를 만든다 → ② 그 결과를 입력으로 재귀 부분을 실행한다 → ③ 새 행이 나오면 그것을 다시 입력으로 ②를 반복 → ④ **새 행이 안 나오면 멈춘다.**

```sql
WITH RECURSIVE seq AS (
    SELECT 1 AS n                       -- 앵커
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 5   -- 재귀 (n=5 가 되면 새 행이 안 나옴 → 종료)
)
SELECT n FROM seq;
```

**결과**
```
+------+
| n    |
+------+
|    1 |
|    2 |
|    3 |
|    4 |
|    5 |
+------+
```

### 종료 조건을 빼면?

`WHERE n < 5` 를 빼면 영원히 새 행이 나옵니다. 다행히 MySQL 은 안전장치를 갖고 있습니다.

```sql
WITH RECURSIVE boom AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM boom)
SELECT * FROM boom;
-- ERROR 3636 (HY000): Recursive query aborted after 1001 iterations.
-- Try increasing @@cte_max_recursion_depth to a larger value.
```

```sql
SELECT @@cte_max_recursion_depth AS default_depth;
```

**결과**
```
+---------------+
| default_depth |
+---------------+
|          1000 |
+---------------+
```

기본값 1000 입니다. 1000 번 반복하면 강제 중단하고 에러를 냅니다. 정말로 더 깊이 가야 한다면 **세션 한정으로** 늘립니다.

```sql
SET SESSION cte_max_recursion_depth = 10000;
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 5000
)
SELECT COUNT(*) AS cnt, MAX(n) AS max_n FROM seq;
SET SESSION cte_max_recursion_depth = 1000;   -- 반드시 되돌린다
```

**결과**
```
+------+-------+
| cnt  | max_n |
+------+-------+
| 5000 |  5000 |
+------+-------+
```

> ⚠️ **함정**: `SET GLOBAL cte_max_recursion_depth = 1000000` 같은 짓은 하지 마세요. 그 한계는 **버그를 막아주는 방어벽**입니다. 벽을 치우면, 실수로 만든 무한루프가 서버 메모리를 다 먹고 인스턴스를 죽입니다. 늘려야 한다면 세션 한정으로, 필요한 만큼만.

---

## 9-4. 재귀 CTE로 조직도 전개하기

`employees` 는 `manager_id` 로 자기 자신을 참조하는 4단계 조직입니다. 재귀 CTE 로 트리 전체를 펼쳐 봅시다.

```sql
WITH RECURSIVE org AS (
    -- 앵커: 최상위(상사가 없는 사람)
    SELECT employee_id, name, manager_id, dept, position, 1 AS lvl,
           CAST(name AS CHAR(200)) AS path
    FROM employees
    WHERE manager_id IS NULL
    UNION ALL
    -- 재귀: 이미 찾은 사람(o)을 상사로 두는 사람(e)을 붙인다
    SELECT e.employee_id, e.name, e.manager_id, e.dept, e.position, o.lvl + 1,
           CONCAT(o.path, ' > ', e.name)
    FROM employees e
    JOIN org o ON e.manager_id = o.employee_id
)
SELECT lvl,
       CONCAT(REPEAT('    ', lvl - 1), name) AS tree,
       position, dept, path
FROM org
ORDER BY path;
```

**결과**
```
+------+-----------------------+-----------+--------------+-----------------------------------------------+
| lvl  | tree                  | position  | dept         | path                                          |
+------+-----------------------+-----------+--------------+-----------------------------------------------+
|    1 | 정한별                | CEO       | 경영진       | 정한별                                        |
|    2 |     김코드            | 본부장    | 개발본부     | 정한별 > 김코드                               |
|    3 |         박서버        | 팀장      | 개발본부     | 정한별 > 김코드 > 박서버                      |
|    4 |             서인덱    | 주니어    | 개발본부     | 정한별 > 김코드 > 박서버 > 서인덱             |
|    4 |             임쿼리    | 주니어    | 개발본부     | 정한별 > 김코드 > 박서버 > 임쿼리             |
|    4 |             한백엔    | 시니어    | 개발본부     | 정한별 > 김코드 > 박서버 > 한백엔             |
|    3 |         최화면        | 팀장      | 개발본부     | 정한별 > 김코드 > 최화면                      |
|    4 |             남뷰어    | 주니어    | 개발본부     | 정한별 > 김코드 > 최화면 > 남뷰어             |
|    4 |             조리액    | 시니어    | 개발본부     | 정한별 > 김코드 > 최화면 > 조리액             |
|    2 |     오지원            | 본부장    | 경영지원     | 정한별 > 오지원                               |
+------+-----------------------+-----------+--------------+-----------------------------------------------+
... (총 18행)
```

`path` 컬럼이 핵심 아이디어입니다. 경로 문자열을 만들어 두면 **`ORDER BY path` 만으로 트리 순서(깊이 우선)** 가 나옵니다.

> ⚠️ **함정**: 앵커에서 `CAST(name AS CHAR(200))` 을 한 이유가 있습니다. 재귀 CTE 의 **컬럼 타입은 앵커에서 결정**됩니다. 그냥 `name` 을 쓰면 `VARCHAR(50)` 으로 고정되고, 재귀에서 `CONCAT` 으로 길어진 문자열이 **말없이 잘려나갑니다**(엄격 모드에선 에러). 재귀에서 자라는 컬럼은 앵커에서 넉넉하게 `CAST` 하세요.

### 반대 방향 : 내 위로 누가 있나

앵커와 조인 방향만 뒤집으면 됩니다.

```sql
WITH RECURSIVE up AS (
    SELECT employee_id, name, manager_id, position, 0 AS up_lvl
    FROM employees
    WHERE employee_id = 13                       -- 남뷰어(주니어)
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, e.position, u.up_lvl + 1
    FROM employees e
    JOIN up u ON e.employee_id = u.manager_id    -- ← 방향이 반대
)
SELECT up_lvl, employee_id, name, position FROM up ORDER BY up_lvl;
```

**결과**
```
+--------+-------------+-----------+-----------+
| up_lvl | employee_id | name      | position  |
+--------+-------------+-----------+-----------+
|      0 |          13 | 남뷰어    | 주니어    |
|      1 |           6 | 최화면    | 팀장      |
|      2 |           2 | 김코드    | 본부장    |
|      3 |           1 | 정한별    | CEO       |
+--------+-------------+-----------+-----------+
```

### 하위 조직 인원 수 세기

각 사원을 루트로 삼아 하위 트리를 모두 펼친 뒤 세면 됩니다.

```sql
WITH RECURSIVE sub AS (
    SELECT employee_id AS root_id, employee_id, name
    FROM employees                                  -- 앵커: 모든 사원이 각자 루트
    UNION ALL
    SELECT s.root_id, e.employee_id, e.name
    FROM employees e
    JOIN sub s ON e.manager_id = s.employee_id
)
SELECT r.employee_id, r.name, r.position,
       COUNT(*) - 1 AS descendants                  -- 자기 자신 제외
FROM sub s
JOIN employees r ON r.employee_id = s.root_id
GROUP BY r.employee_id, r.name, r.position
ORDER BY descendants DESC, r.employee_id
LIMIT 6;
```

**결과**
```
+-------------+-----------+-----------+-------------+
| employee_id | name      | position  | descendants |
+-------------+-----------+-----------+-------------+
|           1 | 정한별    | CEO       |          17 |
|           2 | 김코드    | 본부장    |           7 |
|           4 | 오지원    | 본부장    |           4 |
|           3 | 이세일    | 본부장    |           3 |
|           5 | 박서버    | 팀장      |           3 |
|           8 | 윤사람    | 팀장      |           3 |
+-------------+-----------+-----------+-------------+
... (총 18행)
```

---

## 9-5. 재귀로 날짜 시퀀스 만들기

DB에 없는 데이터를 만들어내야 할 때가 있습니다. 대표적인 것이 **달력**입니다.

```sql
WITH RECURSIVE dates AS (
    SELECT DATE('2025-01-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-01-10'
)
SELECT d, DAYNAME(d) AS dow FROM dates;
```

**결과**
```
+------------+-----------+
| d          | dow       |
+------------+-----------+
| 2025-01-01 | Wednesday |
| 2025-01-02 | Thursday  |
| 2025-01-03 | Friday    |
| 2025-01-04 | Saturday  |
| 2025-01-05 | Sunday    |
| 2025-01-06 | Monday    |
| 2025-01-07 | Tuesday   |
| 2025-01-08 | Wednesday |
| 2025-01-09 | Thursday  |
| 2025-01-10 | Friday    |
+------------+-----------+
```

---

## 9-6. 빈 구간 채우기(gap filling) — 실무에서 가장 자주 쓰는 패턴

`GROUP BY 날짜` 로 일별 집계를 내면 **데이터가 없는 날은 아예 행이 사라집니다.** 그래프를 그리면 선이 뚝 끊기거나 없는 날이 그냥 스킵돼서 추세가 왜곡됩니다.

먼저 문제를 봅시다.

```sql
SELECT DATE(order_date) AS d, COUNT(*) AS cnt
FROM orders
WHERE order_date >= '2025-03-01' AND order_date < '2025-03-11'
GROUP BY DATE(order_date)
ORDER BY d;
```

**결과**
```
+------------+-----+
| d          | cnt |
+------------+-----+
| 2025-03-01 |   1 |
| 2025-03-02 |   1 |
| 2025-03-03 |   1 |
| 2025-03-04 |   1 |
| 2025-03-05 |   1 |
| 2025-03-06 |   1 |
| 2025-03-08 |   1 |    ← 03-07 이 사라졌다
| 2025-03-09 |   1 |
+------------+-----+
```

10일 구간인데 8행만 나옵니다. `03-07`, `03-10` 은 주문이 없어서 **행 자체가 없습니다.** 해법은 "날짜를 먼저 만들고, 거기에 집계를 LEFT JOIN" 하는 것입니다.

```sql
WITH RECURSIVE dates AS (                 -- ① 날짜 뼈대를 만든다
    SELECT DATE('2025-03-01') AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM dates WHERE d < '2025-03-10'
),
daily AS (                                -- ② 실제 집계
    SELECT DATE(order_date) AS d, COUNT(*) AS cnt, SUM(total_amount) AS amt
    FROM orders
    WHERE order_date >= '2025-03-01' AND order_date < '2025-03-11'
    GROUP BY DATE(order_date)
)
SELECT dt.d,                              -- ③ 뼈대에 살을 붙인다
       COALESCE(dl.cnt, 0) AS order_cnt,
       COALESCE(dl.amt, 0) AS amount
FROM dates dt
LEFT JOIN daily dl ON dl.d = dt.d
ORDER BY dt.d;
```

**결과**
```
+------------+-----------+------------+
| d          | order_cnt | amount     |
+------------+-----------+------------+
| 2025-03-01 |         1 | 1194000.00 |
| 2025-03-02 |         1 |  746000.00 |
| 2025-03-03 |         1 |  378000.00 |
| 2025-03-04 |         1 |   98000.00 |
| 2025-03-05 |         1 | 2479700.00 |
| 2025-03-06 |         1 | 1727000.00 |
| 2025-03-07 |         0 |       0.00 |   ← 채워졌다
| 2025-03-08 |         1 |  258000.00 |
| 2025-03-09 |         1 | 1303000.00 |
| 2025-03-10 |         0 |       0.00 |   ← 채워졌다
+------------+-----------+------------+
```

`LEFT JOIN` 방향이 핵심입니다. **날짜(dates)가 왼쪽**이어야 없는 날이 살아남습니다. 반대로 하면 원래대로 8행이 됩니다.

월 단위도 똑같습니다.

```sql
WITH RECURSIVE months AS (
    SELECT DATE('2025-01-01') AS m
    UNION ALL
    SELECT m + INTERVAL 1 MONTH FROM months WHERE m < '2025-06-01'
)
SELECT DATE_FORMAT(mo.m, '%Y-%m')     AS ym,
       COUNT(o.order_id)              AS order_cnt,
       COALESCE(SUM(o.total_amount),0) AS amount
FROM months mo
LEFT JOIN orders o
       ON o.order_date >= mo.m
      AND o.order_date <  mo.m + INTERVAL 1 MONTH
      AND o.status <> 'CANCELLED'
GROUP BY ym
ORDER BY ym;
```

**결과**
```
+---------+-----------+-------------+
| ym      | order_cnt | amount      |
+---------+-----------+-------------+
| 2025-01 |        22 | 24905400.00 |
| 2025-02 |        20 | 24284600.00 |
| 2025-03 |        22 | 26566300.00 |
| 2025-04 |        22 | 22795700.00 |
| 2025-05 |        25 | 27386000.00 |
| 2025-06 |        24 | 23937100.00 |
+---------+-----------+-------------+
```

> 💡 **실무 팁**: 조인 조건을 `DATE_FORMAT(o.order_date,'%Y-%m') = DATE_FORMAT(mo.m,'%Y-%m')` 로 쓰고 싶은 유혹이 들지만, **그 순간 order_date 인덱스를 못 씁니다**(Step 12 참고). 위처럼 `>= 시작 AND < 다음달` 범위 조건으로 쓰세요. 컬럼에 함수를 씌우지 않는 것이 원칙입니다.

> 💡 **실무 팁**: 이 스키마의 `tally`(1~10000) 테이블을 쓰면 재귀 없이도 시퀀스를 만들 수 있고, **재귀 CTE 보다 빠릅니다.**
> ```sql
> SELECT DATE_ADD('2025-01-01', INTERVAL t.n - 1 DAY) AS d FROM tally t WHERE t.n <= 5;
> ```
> 대량 배치라면 숫자 테이블을, 일회성 조회라면 재귀 CTE 를 쓰는 게 무난합니다.

---

## 9-7. 무한루프 방어

재귀 CTE 가 폭주하는 원인은 대개 두 가지입니다. **종료 조건 누락**, 그리고 **데이터에 사이클**이 있는 경우(A의 상사가 B, B의 상사가 A). 데이터 사이클은 애플리케이션 버그로 충분히 만들어질 수 있습니다.

방어법 1 — **UNION (DISTINCT)**: 이미 나온 행이 또 나오면 새 행이 아니므로 재귀가 멈춥니다.

```sql
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION            -- UNION ALL 이었다면 무한루프
    SELECT 1 FROM seq
)
SELECT * FROM seq;
```

**결과**
```
+------+
| n    |
+------+
|    1 |
+------+
```

방어법 2 — **깊이 상한을 쿼리에 직접 박기**: 가장 확실하고, 의도가 명확합니다.

```sql
WITH RECURSIVE org AS (
    SELECT employee_id, name, manager_id, 1 AS lvl
    FROM employees WHERE manager_id IS NULL
    UNION ALL
    SELECT e.employee_id, e.name, e.manager_id, o.lvl + 1
    FROM employees e
    JOIN org o ON e.manager_id = o.employee_id
    WHERE o.lvl < 10          -- ← 안전장치
)
SELECT lvl, COUNT(*) AS cnt FROM org GROUP BY lvl ORDER BY lvl;
```

**결과**
```
+------+-----+
| lvl  | cnt |
+------+-----+
|    1 |   1 |
|    2 |   3 |
|    3 |   4 |
|    4 |  10 |
+------+-----+
```

> ⚠️ **함정**: `cte_max_recursion_depth` 는 **반복 횟수** 한계이지 결과 행 수 한계가 아닙니다. 한 번의 반복에서 100만 행이 나오면, 1000회를 채우기 전에 메모리가 먼저 터집니다. 조인 조건이 잘못돼서 카티션 곱이 되면 그런 일이 생깁니다. 재귀 부분의 조인 조건을 항상 의심하세요.

> 💡 **실무 팁**: 계층이 깊고 자주 조회된다면 **경로 컬럼(materialized path)** 이나 **closure table** 을 두어 재귀 자체를 없애는 설계도 고려하세요. 재귀 CTE 는 편리하지만 매번 트리를 다시 펼칩니다.

---

## 정리

| 주제 | 핵심 |
|---|---|
| `WITH` (비재귀 CTE) | 파생 테이블에 이름을 붙인 것. 성능 손해 없음 |
| CTE 재사용 | 한 번 정의 → 여러 번 참조 (파생 테이블은 불가) |
| CTE 체이닝 | 콤마로 나열. **앞 → 뒤 방향으로만** 참조 가능 |
| `WITH RECURSIVE` | **MySQL 8.0+**. 앵커 `UNION ALL` 재귀 |
| 재귀 컬럼 타입 | **앵커에서 결정됨** → 자라는 컬럼은 `CAST(... AS CHAR(n))` |
| `path` + `ORDER BY path` | 트리 출력 순서를 만드는 정석 |
| 날짜 시퀀스 | 재귀 CTE 또는 `tally` 테이블 |
| gap filling | **날짜 뼈대를 LEFT JOIN 의 왼쪽**에 두고 `COALESCE(.., 0)` |
| `cte_max_recursion_depth` | 기본 **1000**. 세션 한정으로만 늘릴 것 |
| 무한루프 방어 | `UNION`(DISTINCT) / 쿼리 안 깊이 상한 (`WHERE lvl < N`) |

---

## 연습문제

`exercise.sql` 을 푸세요. 정답은 `solution.sql`.

1. CTE 로 "카테고리별 상품 수"를 만들고 4개 이상인 카테고리만 출력
2. 재귀 CTE 로 1~20 중 짝수만 출력
3. 재귀 CTE 로 employees 조직도를 전개해 각 사원의 depth 출력
4. 특정 사원(employee_id=17)의 모든 상위 관리자를 출력
5. 2025-06-01 ~ 2025-06-15 의 일별 주문 건수를 **빈 날 0 포함**으로 출력
6. CTE 체이닝으로 "VIP/GOLD 고객의 월별 매출"을 구하시오
7. 재귀 CTE 로 categories 계층을 전개하고 `대분류 / 소분류` 경로 문자열 출력
8. `cte_max_recursion_depth` 를 임시로 조정해 1~3000 시퀀스의 합을 구하시오

---

## 다음 단계

→ [Step 10 — 집합 연산](../step-10-set-operations/README.md)

---

## 실습 파일

이 스텝은 SQL 파일 3개로 구성됩니다. 먼저 `practice.sql` 을 실행해 본문 9-1 ~ 9-7 의 예제를 순서대로 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸 8문제를 직접 채워 풉니다. 막히거나 다 풀었다면 `solution.sql` 로 정답과 해설을 대조하면 됩니다. 세 파일 모두 `USE shop;` 으로 시작하므로 `shop` 스키마가 이미 적재돼 있어야 하고, 모두 `SELECT` 위주라 원본 데이터를 바꾸지 않습니다.

### practice.sql

강의 본문에 나온 모든 예제를 `[9-1]` ~ `[9-20]` 번호 순서로 모아 둔 **따라치기용 스크립트**입니다. 파일 상단 주석의 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 한 번에 돌려도 되지만, 처음에는 블록을 하나씩 복사해 붙여 넣으며 결과를 본문과 비교하는 편이 좋습니다.

- `[9-1]` ~ `[9-3]` 은 비재귀 CTE 구간입니다. `[9-2]` 의 `JOIN monthly prev` 는 **같은 CTE 를 두 번 참조**하는 대목으로, 파생 테이블로는 불가능한 일을 보여줍니다.
- `[9-5]` 의 무한루프 예제(`WITH RECURSIVE boom ...`)는 **일부러 주석 처리**되어 있습니다. 그대로 실행하면 `ERROR 3636` 으로 죽기 때문에, 스크립트 전체 실행이 중간에 멈추지 않도록 막아 둔 것입니다. 에러를 직접 보고 싶다면 그 두 줄의 주석을 풀어 클라이언트에 따로 붙여 넣으세요.
- `[9-7]` 은 `SET SESSION cte_max_recursion_depth = 10000;` 로 한계를 올린 뒤 5000행을 만들고, **마지막 줄에서 다시 `= 1000` 으로 되돌립니다.** 이 복구 줄을 빼먹으면 같은 세션에서 이어지는 실습이 느슨한 한계로 돌아가니 항상 짝으로 쓰는 습관을 들이세요.
- `[9-8]` 의 `CAST(name AS CHAR(200)) AS path` 는 재귀 컬럼 타입이 앵커에서 결정되는 함정을 피하기 위한 것이고, `[9-12]`/`[9-14]` 는 gap filling, `[9-17]`/`[9-18]` 은 무한루프 방어(`UNION` DISTINCT, `WHERE o.lvl < 10`)를 담당합니다.
- `[9-19]` 의 `WITH target AS (...) DELETE FROM orders ...` 예제도 주석입니다. CTE 를 `DELETE` 앞에 붙일 수 있다는 **구문 확인용**이며, 실제로 실행하면 `orders` 의 `PENDING` 주문이 지워지므로 **절대 주석을 풀지 마세요.** 데이터 변경 실습은 Step 11 에서 사본 테이블로 진행합니다.

```sql file="./practice.sql"
```

### exercise.sql

본문 「연습문제」의 8문항을 담은 **빈칸 채우기 파일**입니다. 각 문제는 주석 블록으로 요구사항(출력 컬럼, 정렬, 행 수)까지 못박아 두었고, 그 아래 빈 줄에 여러분이 직접 쿼리를 씁니다. 그대로 실행하면 `USE shop;` 만 수행되고 아무 결과도 나오지 않는 것이 정상입니다.

- Q1(카테고리별 상품 수 4개 이상)·Q6(VIP/GOLD 월별 매출)은 **비재귀 CTE / CTE 체이닝** 연습이고, Q2~Q5·Q7·Q8 은 재귀 CTE 연습입니다. Q5 는 날짜 뼈대를 재귀로 만든 뒤 `LEFT JOIN` 하는 문제라 두 성격이 섞여 있습니다.
- Q4 는 `employee_id = 17 (노총무)` 의 상위 라인을 거슬러 올라가는 문제로, `[9-9]` 와 **조인 방향이 같습니다**(`e.employee_id = u.manager_id`). 9-4 의 하향 전개와 헷갈리지 않도록 주의하세요.
- Q5 는 `2025-06-01 ~ 2025-06-15` 를 **반드시 15행**으로 내라고 못박아 둡니다. 주문이 없는 날이 사라지면 틀린 답이라는 뜻이고, 이것이 gap filling 의 채점 기준입니다.
- Q8 은 `1 ~ 3000` 의 합을 구하되 검산값 `4,501,500` 을 알려줍니다. 기본 깊이 1000 으로는 반드시 실패하므로 `SET SESSION` 으로 한계를 올린 뒤 **끝나고 1000 으로 되돌리는 것까지가 문제의 일부**입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 의 정답과, 왜 그렇게 푸는지에 대한 해설 주석을 담은 파일입니다. **먼저 스스로 풀어 본 뒤에** 열어 보세요. 단순 정답 나열이 아니라 흔히 저지르는 실수를 함께 짚어 주므로, 정답을 맞혔더라도 주석은 읽어 볼 값어치가 있습니다.

- A2 는 정답(1~20 생성 후 `WHERE n % 2 = 0` 으로 필터) 외에 **반복 횟수가 절반인 대안**(`SELECT 2` 로 시작해 `n + 2`)을 함께 제시합니다. "생성 → 필터"의 가독성과 효율 사이의 트레이드오프를 보여 주는 대목입니다.
- A5 가 가장 중요합니다. 첫 번째 풀이의 주석은 **`COUNT(*)` 가 아니라 `COUNT(o.order_id)` 를 써야 하는 이유**를 짚습니다. `COUNT(*)` 로 쓰면 `LEFT JOIN` 으로 붙은 NULL 행까지 1로 세어 주문 없는 날이 0 이 아니라 1 로 나옵니다. 이어서 두 번째 풀이는 `DATE(o.order_date)` 에 함수를 씌우는 대신 `order_date >= '2025-06-01' AND order_date < '2025-06-16'` 범위 조건으로 집계를 먼저 하고 조인하는 **실무형 개선안**을 보여 줍니다.
- A8 은 `SET SESSION cte_max_recursion_depth = 5000;` → 재귀 → `SET SESSION ... = 1000;` 의 3단 구조이며, 맨 마지막 줄에서 `tally` 테이블을 쓰면 **재귀도 설정 변경도 필요 없다**는 대안(`SELECT COUNT(*), SUM(n) FROM tally WHERE n <= 3000`)을 덧붙입니다.
- 이 파일도 전부 `SELECT` 와 `SET SESSION` 뿐이라 데이터를 변경하지 않으므로 통째로 실행해도 안전합니다.

```sql file="./solution.sql"
```
