# STEP 9 — CTE (Common Table Expression)

> WITH 로 복잡한 쿼리를 단계로 쪼개 가독성을 높이고, WITH RECURSIVE 로 계층·연속 데이터를 다룬다.

[← 목록으로](index.md)

---

CTE(공통 테이블 표현식)는 `WITH 이름 AS (...)` 형태로 쿼리 앞에 임시 결과셋을 정의하는 문법이다. 세 가지 이점이 있다. (1) **가독성** — 중첩 서브쿼리를 위에서 아래로 읽히는 단계로 펼친다. (2) **재사용** — 하나의 CTE 를 본문에서 여러 번 참조할 수 있다(파생테이블은 매번 다시 써야 함). (3) **재귀** — `WITH RECURSIVE` 로 계층·연속 데이터를 반복 전개할 수 있다.

성능 면에서 MySQL 8 의 비재귀 CTE 는 대부분 파생테이블(derived table)로 **머티리얼라이즈되거나 병합(merge)** 되어 실행되며, 같은 쿼리를 파생테이블로 쓴 것과 **성능이 사실상 동일**하다. 즉 CTE 로 바꿨다고 느려지지 않으니, 복잡한 쿼리는 가독성을 위해 적극적으로 CTE 로 쪼개도 좋다.

---

## 1. [608] Tree Node

**링크**: https://leetcode.cn/problems/tree-node/  
**학습 포인트**: 비재귀 CTE 로 "자식을 가진 노드 집합"을 먼저 정의해 CASE 를 단순화한다.

### 문제

`Tree` 테이블. 각 노드는 `id` 와 부모 `p_id` 를 가진다.

| 컬럼 | 설명 |
|------|------|
| id | 노드 ID (PK) |
| p_id | 부모 노드 ID (루트면 NULL) |

각 노드를 다음 세 종류로 분류한다.

- **Root**: 부모가 없다(`p_id IS NULL`).
- **Leaf**: 부모는 있고(`p_id IS NOT NULL`), 자기를 부모로 삼는 노드가 없다(자식 없음).
- **Inner**: 부모도 있고 자식도 있다.

`id`, `type` 두 컬럼으로 출력한다.

### 정답

```sql
WITH parents AS (
    SELECT DISTINCT p_id AS id
    FROM Tree
    WHERE p_id IS NOT NULL
)
SELECT
    t.id,
    CASE
        WHEN t.p_id IS NULL              THEN 'Root'
        WHEN p.id IS NOT NULL            THEN 'Inner'
        ELSE                                  'Leaf'
    END AS type
FROM Tree AS t
LEFT JOIN parents AS p ON t.id = p.id
ORDER BY t.id;
```

### 풀이 — 왜 이렇게 하는가

분류 규칙의 핵심은 "이 노드가 **다른 누군가의 부모인가?**" 이다. 이것만 알면 Inner 와 Leaf 가 갈린다.

1. `parents` CTE 에서 `p_id` 컬럼에 등장하는 값들을 `DISTINCT` 로 모은다. 이 집합에 든 `id` 는 "자식을 가진 노드"다.
2. 본문에서 `Tree` 에 `parents` 를 LEFT JOIN 한다. 매칭되면(`p.id IS NOT NULL`) 자식이 있는 노드, 아니면 자식이 없는 노드다.
3. CASE 는 위에서부터 평가된다. `p_id IS NULL` → Root, 그다음 자식 있으면 Inner, 나머지는 Leaf.

CTE 없이 하면 CASE 안에서 `id IN (SELECT p_id FROM Tree WHERE p_id IS NOT NULL)` 서브쿼리를 매 행 개념적으로 반복 참조하게 된다. "자식 가진 노드 집합"이라는 개념에 `parents` 라는 이름을 붙여두면 의도가 한눈에 드러난다.

### 핵심 개념

- CTE 로 **의미 있는 중간 집합에 이름을 붙이면** CASE 로직이 단순해진다.
- `IN (SELECT ...)` 상관 서브쿼리를 LEFT JOIN + NULL 판정으로 바꾸는 패턴.
- CASE 는 **위에서 아래로** 첫 참인 분기를 채택하므로 Root 판정을 맨 위에 둔다.

### ⚠️ 흔한 실수

- 트리에 노드가 하나뿐이면(루트만 존재) 그 노드는 Root 다. `p_id IS NULL` 을 최우선으로 두지 않으면 Leaf 로 잘못 분류된다.
- `parents` 를 만들 때 `WHERE p_id IS NOT NULL` 을 빠뜨리면 NULL 이 집합에 섞여 JOIN 결과가 어긋날 수 있다.

### 💡 대안 / 응용

- 서브쿼리 버전: `CASE WHEN p_id IS NULL THEN 'Root' WHEN id IN (SELECT p_id FROM Tree WHERE p_id IS NOT NULL) THEN 'Inner' ELSE 'Leaf' END`.
- 자식 개수를 함께 보고 싶다면 `parents` 를 `SELECT p_id AS id, COUNT(*) AS child_cnt ... GROUP BY p_id` 로 확장한다.

---

## 2. [1225] Report Contiguous Dates

**링크**: https://leetcode.cn/problems/report-contiguous-dates/  
**학습 포인트**: gaps-and-islands. 연속된 날짜 구간을 CTE 단계로 압축한다.

### 문제

두 테이블이 있다.

- `Failed(fail_date)`: 실패한 날짜들.
- `Succeeded(success_date)`: 성공한 날짜들.

기간은 2019-01-01 ~ 2019-12-31. 상태(`failed` / `succeeded`)가 **같고 날짜가 연속**인 구간을 하나로 묶어, 각 구간의 `period_state`, `start_date`, `end_date` 를 출력한다. `start_date` 오름차순 정렬.

예: 실패가 1/1, 1/2, 1/3 연속이면 한 행 `('failed', 2019-01-01, 2019-01-03)`.

### 정답

```sql
WITH logs AS (           -- (1) 두 상태를 한 테이블로 합침
    SELECT 'failed'    AS period_state, fail_date    AS dt FROM Failed
    WHERE  fail_date    BETWEEN '2019-01-01' AND '2019-12-31'
    UNION ALL
    SELECT 'succeeded' AS period_state, success_date AS dt FROM Succeeded
    WHERE  success_date BETWEEN '2019-01-01' AND '2019-12-31'
),
numbered AS (            -- (2) 상태별로 날짜 순번 부여
    SELECT
        period_state,
        dt,
        ROW_NUMBER() OVER (PARTITION BY period_state ORDER BY dt) AS rn
    FROM logs
),
grouped AS (             -- (3) dt - rn 로 같은 구간에 공통 앵커 부여
    SELECT
        period_state,
        dt,
        DATE_SUB(dt, INTERVAL rn DAY) AS grp
    FROM numbered
)
SELECT                   -- (4) 그룹별 MIN/MAX = 구간의 시작/끝
    period_state,
    MIN(dt) AS start_date,
    MAX(dt) AS end_date
FROM grouped
GROUP BY period_state, grp
ORDER BY start_date;
```

### 풀이 — 왜 이렇게 하는가

이 문제는 gaps-and-islands 의 교과서적 예다. CTE 각 단계가 어떤 중간결과인지 그림처럼 따라가 보자.

**(1) `logs`** — 실패/성공을 하나의 테이블로 합친다. `period_state` 라벨을 붙여 UNION ALL 로 세로로 쌓는다. 결과는 `(period_state, dt)` 목록.

```
period_state | dt
-------------+-----------
failed       | 2019-01-01
failed       | 2019-01-02
failed       | 2019-01-03
succeeded    | 2019-01-04
failed       | 2019-01-05
failed       | 2019-01-06
```

**(2) `numbered`** — 상태별로 날짜순 순번(`rn`)을 매긴다. `PARTITION BY period_state` 로 failed 는 failed 끼리, succeeded 는 succeeded 끼리 1,2,3... 을 센다.

```
period_state | dt         | rn
-------------+------------+----
failed       | 2019-01-01 | 1
failed       | 2019-01-02 | 2
failed       | 2019-01-03 | 3
failed       | 2019-01-05 | 4
failed       | 2019-01-06 | 5
succeeded    | 2019-01-04 | 1
```

**(3) `grouped`** — 핵심 트릭. **날짜가 연속이면 `dt - rn` 이 일정한 값**이 된다. 날짜가 1일씩 증가하고 `rn` 도 1씩 증가하므로, 둘의 차이는 같은 섬(island) 안에서 상수다. 중간에 날짜가 끊기면(gap) 이 값이 달라진다.

```
period_state | dt         | grp(=dt-rn)
-------------+------------+------------
failed       | 2019-01-01 | 2018-12-31   ┐ 같은 grp → 한 구간
failed       | 2019-01-02 | 2018-12-31   │
failed       | 2019-01-03 | 2018-12-31   ┘
failed       | 2019-01-05 | 2019-01-01   ┐ grp 바뀜 → 새 구간
failed       | 2019-01-06 | 2019-01-01   ┘
succeeded    | 2019-01-04 | 2019-01-03
```

**(4) 최종** — `(period_state, grp)` 로 그룹핑해 `MIN(dt)` 를 `start_date`, `MAX(dt)` 를 `end_date` 로 뽑는다. `grp` 는 그룹 키로만 쓰고 출력하지 않는다.

CTE 로 4단계를 분리하니 "합치기 → 순번 → 그룹키 → 집계"라는 사고 흐름이 그대로 코드가 된다. 한 쿼리에 서브쿼리로 3중 중첩하면 안쪽부터 거꾸로 읽어야 해서 이해가 훨씬 어렵다.

### 핵심 개념

- **gaps-and-islands**: 연속 구간 압축의 정석. `날짜 - ROW_NUMBER = 상수` 원리.
- `ROW_NUMBER()` 는 정수 순번이므로 `DATE_SUB(dt, INTERVAL rn DAY)` 로 날짜에서 빼서 앵커 날짜를 만든다.
- 그룹 키(`grp`)는 **연산의 매개일 뿐 최종 출력에는 넣지 않는다** — `GROUP BY` 에만 사용.

### ⚠️ 흔한 실수

- `PARTITION BY period_state` 를 빼면 failed/succeeded 순번이 뒤섞여 서로 다른 상태가 한 구간으로 묶인다.
- `GROUP BY` 에 `period_state` 를 빠뜨리고 `grp` 만 넣으면, 우연히 `grp` 값이 겹치는 다른 상태가 한 그룹이 될 수 있다. **반드시 `period_state, grp` 둘 다** 넣는다.
- 날짜에서 순번을 뺄 때 `dt - rn` 같은 정수 뺄셈으로 처리하면 안 되고, 날짜 타입은 `DATE_SUB(..., INTERVAL rn DAY)` 를 써야 한다.

### 💡 대안 / 응용

- `grp` 를 날짜 대신 정수로 만들려면 `DATEDIFF(dt, '2019-01-01') - rn` 처럼 일수 차이를 써도 된다(구간 판별에는 상대값이면 충분).
- 로그인 연속 출석일, 서버 무중단 구간, 연속 결제월 등 "끊김 없는 구간" 문제 전반에 그대로 응용된다.

---

## 3. [1070] Product Sales Analysis III

**링크**: https://leetcode.cn/problems/product-sales-analysis-iii/  
**학습 포인트**: CTE 로 "상품별 첫 판매연도"를 정의해 조인 조건을 명확히 한다.

### 문제

`Sales` 테이블.

| 컬럼 | 설명 |
|------|------|
| sale_id | (product_id 와 함께) PK 일부 |
| product_id | 상품 ID |
| year | 판매 연도 |
| quantity | 수량 |
| price | 단가 |

각 상품에 대해 **가장 이른 판매연도(first_year)** 의 판매 기록을 모두 찾아 `product_id`, `first_year`, `quantity`, `price` 를 출력한다. (한 상품이 첫해에 여러 건 팔렸다면 여러 행이 나올 수 있다.)

### 정답

```sql
WITH first_year AS (
    SELECT product_id, MIN(year) AS first_year
    FROM Sales
    GROUP BY product_id
)
SELECT
    s.product_id,
    s.year AS first_year,
    s.quantity,
    s.price
FROM Sales AS s
JOIN first_year AS f
    ON s.product_id = f.product_id
   AND s.year       = f.first_year;
```

### 풀이 — 왜 이렇게 하는가

1. `first_year` CTE 에서 상품별 최소 연도를 계산한다. `(product_id, first_year)` 한 행씩.
2. 원본 `Sales` 에 이 CTE 를 조인한다. 조인 조건이 `product_id` 일치 **그리고** `year = first_year` 이므로, **각 상품의 첫해 기록만** 살아남는다.
3. 첫해에 여러 판매가 있으면 조인 결과도 여러 행이 되어 요구사항("모든 첫해 기록")을 자연히 만족한다.

여기서 `WHERE year = (SELECT MIN(year) ...)` 상관 서브쿼리로도 풀 수 있지만, 첫 판매연도라는 개념에 `first_year` 라는 이름을 붙이면 조인이 무엇을 걸러내는지 즉시 읽힌다.

### 핵심 개념

- **그룹 집계 결과를 원본에 되조인**(self-join back)하여 "그룹 대표값과 일치하는 원본 행"을 뽑는 정석 패턴.
- 조인 키를 `product_id + year` 두 컬럼으로 잡아 "그 상품의 첫해"를 정확히 지정.
- 집계로 값이 하나로 줄어든 CTE 를 원본과 조인하면 **원본의 상세 컬럼(quantity, price)** 을 그대로 보존할 수 있다.

### ⚠️ 흔한 실수

- 조인 조건에서 `s.year = f.first_year` 를 빠뜨리고 `product_id` 만 맞추면 모든 연도 행이 살아 첫해 필터가 무의미해진다.
- `GROUP BY product_id` 로 집계하면서 `quantity`, `price` 를 같이 SELECT 하려는 시도 — 집계 단계에서는 상세값을 특정할 수 없다. 그래서 **집계는 CTE 로 분리하고 상세는 되조인**으로 가져온다.

### 💡 대안 / 응용

- 윈도우 함수 버전: `WITH ranked AS (SELECT *, RANK() OVER (PARTITION BY product_id ORDER BY year) AS rk FROM Sales) SELECT product_id, year AS first_year, quantity, price FROM ranked WHERE rk = 1;` — `RANK` 는 동점 연도(같은 최소 연도 여러 건)를 모두 1로 매겨 그대로 통과시킨다.

---

## 4. [1321] Restaurant Growth

**링크**: https://leetcode.cn/problems/restaurant-growth/  
**학습 포인트**: CTE 로 일별 합계를 정리한 뒤 윈도우 프레임 `ROWS 6 PRECEDING` 으로 7일 이동합/이동평균을 구한다.

### 문제

`Customer` 테이블.

| 컬럼 | 설명 |
|------|------|
| customer_id | 고객 ID |
| name | 이름 |
| visited_on | 방문일 |
| amount | 결제액 |

식당은 매일 영업한다. 각 날짜에 대해 **그날을 포함한 직전 7일**의 합계(`amount`)와 그 7일 **평균**(소수 둘째 자리 반올림)을 구한다. 단, **7일치 데이터가 확보되는 날부터** 출력한다(첫 6일은 제외). `visited_on` 오름차순.

출력: `visited_on`, `amount`(7일 합), `average_amount`(7일 평균, 반올림 2자리).

### 정답

```sql
WITH daily AS (                    -- (1) 하루에 여러 손님이면 먼저 일별 합계로 정리
    SELECT visited_on, SUM(amount) AS day_amount
    FROM Customer
    GROUP BY visited_on
),
rolling AS (                       -- (2) 그날 포함 직전 7일 창으로 합/평균
    SELECT
        visited_on,
        SUM(day_amount) OVER w                       AS amount,
        ROUND(AVG(day_amount) OVER w, 2)             AS average_amount,
        ROW_NUMBER() OVER (ORDER BY visited_on)      AS rn
    FROM daily
    WINDOW w AS (ORDER BY visited_on
                 ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)
)
SELECT visited_on, amount, average_amount
FROM rolling
WHERE rn >= 7                      -- (3) 7일치가 쌓인 날부터
ORDER BY visited_on;
```

### 풀이 — 왜 이렇게 하는가

**(1) `daily`** — 같은 날짜에 손님이 여러 명일 수 있으므로 먼저 `visited_on` 별로 `amount` 를 합쳐 "하루=한 행"으로 만든다. 이 정리를 안 하면 윈도우가 "행 7개"를 세는데 그게 "날짜 7일"과 어긋난다.

**(2) `rolling`** — 정렬된 일별 행 위에서 윈도우를 연다. `ROWS BETWEEN 6 PRECEDING AND CURRENT ROW` 는 **현재 행 + 앞의 6행 = 총 7행(=7일)** 을 프레임으로 잡는다. `SUM` 은 7일 합, `AVG` 는 7일 평균이다. `WINDOW w AS (...)` 절로 프레임 정의를 한 번만 쓰고 재사용한다.

**(3) 최종** — 처음 6일은 프레임에 7일치가 안 차므로 `ROW_NUMBER() >= 7` 로 걸러 7일째부터 출력한다.

일별 정리(CTE 1) → 이동창 계산(CTE 2) → 앞부분 컷(본문)으로 단계가 나뉘어 흐름이 분명하다.

### 핵심 개념

- `ROWS BETWEEN 6 PRECEDING AND CURRENT ROW` = 현재 포함 7행 슬라이딩 윈도우. `ROWS`(물리적 행)와 `RANGE`(값 범위)의 차이를 이해할 것.
- 날짜가 **빠짐없이 매일** 있어야 "7행 = 7일"이 성립한다(문제에서 매일 영업 보장). 결측일이 있으면 `RANGE INTERVAL` 이나 날짜 채우기가 필요하다.
- `WINDOW` 절로 프레임을 명명해 `SUM`, `AVG` 가 공유하도록 하면 중복이 줄고 읽기 쉽다.

### ⚠️ 흔한 실수

- 일별 합계(CTE 1)를 생략하면 하루 여러 손님일 때 프레임이 날짜가 아닌 행 기준으로 밀려 결과가 틀린다.
- 프레임을 안 쓰면(`ROWS ...` 생략) 기본값이 `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` 라 **누적 합**이 되어 7일 이동합이 되지 않는다.
- `ROUND(..., 2)` 를 빠뜨려 `average_amount` 자릿수가 요구와 다르게 나온다.

### 💡 대안 / 응용

- **STEP 5(서브쿼리) 방식**과 비교: 자기 자신을 `b.visited_on BETWEEN a.visited_on - 6일 AND a.visited_on` 조건으로 self-join 후 `GROUP BY` 하는 상관/셀프조인 풀이가 가능하지만, 날짜 산술과 조인이 얽혀 읽기 어렵다.
- **STEP 8(윈도우) 방식**과 비교: 윈도우 프레임 한 줄(`ROWS 6 PRECEDING`)로 이동합을 직접 표현해 의도가 가장 명확하다. CTE 는 여기에 "일별 정리 → 윈도우 → 필터"의 단계 구조를 더해 준다.

---

## 보너스 — 재귀 CTE 맛보기

이 STEP 의 4문제는 재귀가 필수가 아니지만, `WITH RECURSIVE` 는 CTE 의 진짜 강력한 무기다. 구조는 항상 **앵커부(anchor) + `UNION ALL` + 재귀부(recursive)** 다.

### 예제 A — 1~10 숫자 생성 / 빈 날짜 메우기

```sql
-- 1부터 10까지 생성
WITH RECURSIVE seq AS (
    SELECT 1 AS n                 -- 앵커: 시작값
    UNION ALL
    SELECT n + 1 FROM seq         -- 재귀: 직전 행을 참조해 +1
    WHERE n < 10                  -- 종료 조건 (필수)
)
SELECT n FROM seq;
```

같은 원리로 **빠진 날짜를 채울** 수 있다. 예를 들어 2019-01-01 ~ 2019-01-31 을 모두 생성한 뒤, 실제 데이터를 LEFT JOIN 하면 데이터가 없는 날도 0 으로 표시할 수 있다.

```sql
WITH RECURSIVE cal AS (
    SELECT DATE '2019-01-01' AS d
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM cal
    WHERE d < DATE '2019-01-31'
)
SELECT c.d, COALESCE(SUM(t.amount), 0) AS total
FROM cal AS c
LEFT JOIN Sales AS t ON t.sale_date = c.d
GROUP BY c.d
ORDER BY c.d;
```

### 예제 B — 조직도(자기참조 계층) 전개

`Employee(id, name, manager_id)` 에서 특정 관리자 밑의 **모든 하위 직원**을 깊이와 함께 전개한다.

```sql
WITH RECURSIVE org AS (
    SELECT id, name, manager_id, 1 AS lvl        -- 앵커: 최상위(예: manager_id IS NULL)
    FROM Employee
    WHERE manager_id IS NULL
    UNION ALL
    SELECT e.id, e.name, e.manager_id, o.lvl + 1 -- 재귀: 부모(org)에 매달린 자식 찾기
    FROM Employee AS e
    JOIN org AS o ON e.manager_id = o.id
)
SELECT id, name, lvl FROM org ORDER BY lvl, id;
```

### 재귀 CTE 핵심 규칙

- **앵커 + 재귀부** 구조. 앵커가 시작 행을 만들고, 재귀부는 CTE 자신을 참조해 다음 행을 만든다. `UNION ALL`(또는 `UNION`)로 연결한다.
- **컬럼 타입·길이는 앵커부에서 결정**된다. 재귀부에서 문자열을 이어 붙이거나(`CONCAT`) 값이 커지면 앵커에서 미리 넉넉한 타입으로 **`CAST`** 해 둬야 잘림(truncation)을 막는다. 예: 앵커에서 `CAST(name AS CHAR(1000))`.
- **종료 조건은 필수**다. `WHERE` 로 재귀를 멈추지 않으면 무한 반복이 된다. 안전장치로 시스템 변수 **`cte_max_recursion_depth`(기본 1000)** 가 있어, 깊이가 이를 넘으면 에러로 중단된다. 정당하게 더 깊게 가야 하면 `SET SESSION cte_max_recursion_depth = ...` 로 조정한다.

---

[← 목록으로](index.md)
