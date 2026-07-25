# STEP 8 — Window Function

> ROW_NUMBER / RANK / DENSE_RANK, LAG/LEAD, 집계 윈도우와 프레임. 앞에서 서브쿼리로 풀던 것을 윈도우로 더 깔끔하게 푼다.

[← 목록으로](index.md)

---

## 윈도우 함수 기초

윈도우 함수는 `함수() OVER (...)` 형태로, 행을 그룹으로 묶지 않고(= 행 수를 줄이지 않고) 각 행마다 "주변 행들"을 참조해 값을 계산한다. `OVER` 절은 세 부품으로 구성된다.

- **PARTITION BY**: 계산을 나눌 그룹. 부서별·상품별 등 파티션 경계를 정한다. 생략하면 전체가 한 파티션.
- **ORDER BY**: 파티션 안에서의 정렬. 순위·누적·LAG/LEAD의 기준이 된다.
- **프레임(frame)**: `ROWS`/`RANGE BETWEEN ... AND ...`. ORDER BY가 있을 때 계산에 포함할 행 범위. 생략하면 기본값은 `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`(= 누적).

순위 함수 3종은 동점(tie) 처리 방식이 다르다. 점수 `90, 90, 90, 80` 기준:

| 함수 | 결과 | 특징 |
|------|------|------|
| `ROW_NUMBER()` | 1, 2, 3, 4 | 동점도 무조건 유일 번호(순서 임의) |
| `RANK()` | 1, 1, 1, 4 | 동점은 같은 순위, 다음은 건너뜀 |
| `DENSE_RANK()` | 1, 1, 1, 2 | 동점은 같은 순위, 다음은 안 건너뜀 |

핵심 함정: 윈도우 함수는 **SELECT / ORDER BY 절에서만** 계산되고 `WHERE` / `HAVING` 에는 직접 쓸 수 없다. "순위 <= 3" 같은 필터는 서브쿼리나 CTE로 한 번 감싼 뒤 바깥에서 걸러야 한다.

---

## 1. [178] Rank Scores

**링크**: https://leetcode.cn/problems/rank-scores/  
**학습 포인트**: 순위 함수 3종의 차이를 한 문제로 확정. 동점은 같은 순위, 순위는 연속이어야 하므로 `DENSE_RANK`.

### 문제
```
Scores(id PK, score DECIMAL)
```
점수를 내림차순으로 매긴다. 동점은 같은 순위, 그리고 순위 사이에 빈 값이 없어야 한다(1,2,2,3 처럼 연속). `score`와 `rank`를 점수 내림차순으로 출력.

### 정답
```sql
SELECT
  score,
  DENSE_RANK() OVER (ORDER BY score DESC) AS 'rank'
FROM Scores;
```

### 풀이 — 왜 이렇게 하는가
1. 요구사항이 "동점은 같은 순위"이므로 `ROW_NUMBER`는 탈락(동점을 1,2,3으로 갈라버린다).
2. "순위 사이에 빈 값이 없어야 한다"가 결정타다. `RANK`는 90,90 뒤가 3위(2위 건너뜀)라 실패한다. `DENSE_RANK`만 1,1,2 처럼 연속을 보장한다.
3. `rank`는 MySQL 8의 예약어이므로 백틱으로 감싼다.

윈도우 이전에는 이 문제를 상관 서브쿼리 `(SELECT COUNT(DISTINCT s2.score) FROM Scores s2 WHERE s2.score >= s1.score)`로 풀었다. 이는 각 행마다 전체 테이블을 다시 스캔해 O(n²)이고 의도도 잘 안 드러난다. 윈도우 한 줄이 훨씬 빠르고 명확하다.

### 핵심 개념
- `DENSE_RANK`: 동점 동순위 + 순위 연속.
- `RANK`: 동점 동순위 + 다음 순위 건너뜀.
- `ROW_NUMBER`: 동점도 유일 번호.
- 예약어 컬럼명은 백틱 처리.

### ⚠️ 흔한 실수
- `RANK()`를 써서 순위가 1,1,3으로 건너뛰는 오답.
- `PARTITION BY`를 붙여버려 파티션마다 순위가 리셋되는 것(여기선 전체가 한 파티션이라 PARTITION 불필요).

### 💡 대안 / 응용
- "동점을 갈라 유일 번호로" 요구면 `ROW_NUMBER`, "동점 동순위 + 건너뛰기 허용"이면 `RANK`.

---

## 2. [185] Department Top Three Salaries

**링크**: https://leetcode.cn/problems/department-top-three-salaries/  
**학습 포인트**: `PARTITION BY`로 부서별 순위. 윈도우는 WHERE에 못 쓰므로 CTE로 감싼 뒤 필터. "서로 다른 급여 상위 3"이라 `DENSE_RANK`.

### 문제
```
Employee(id PK, name, salary, departmentId FK)
Department(id PK, name)
```
각 부서에서 "서로 다른 급여 기준 상위 3개"에 속하는 직원을 모두 출력. 같은 급여가 여럿이면 모두 포함. `Department, Employee, Salary` 컬럼.

### 정답
```sql
WITH ranked AS (
  SELECT
    e.name AS emp_name,
    e.salary,
    e.departmentId,
    DENSE_RANK() OVER (
      PARTITION BY e.departmentId
      ORDER BY e.salary DESC
    ) AS drk
  FROM Employee e
)
SELECT
  d.name AS Department,
  r.emp_name AS Employee,
  r.salary AS Salary
FROM ranked r
JOIN Department d ON d.id = r.departmentId
WHERE r.drk <= 3;
```

### 풀이 — 왜 이렇게 하는가
1. `PARTITION BY e.departmentId`로 부서마다 순위를 독립적으로 매긴다.
2. "서로 다른 급여(distinct high salary) 상위 3"이 핵심이다. 급여 90,90,80,70 이면 90은 1위, 80은 2위, 70은 3위 — 동점을 하나로 취급하고 연속이어야 하므로 `DENSE_RANK`. `ROW_NUMBER`면 90,90을 1·2위로 갈라 상위 3의 의미가 어긋난다.
3. **윈도우 함수는 WHERE에 쓸 수 없다.** `WHERE DENSE_RANK() OVER(...) <= 3`은 문법 오류다. 그래서 CTE(또는 서브쿼리)로 순위 컬럼을 먼저 만들고, 바깥 쿼리 WHERE에서 `drk <= 3`으로 거른다.

### 핵심 개념
- `PARTITION BY`: 그룹별 순위 리셋.
- 윈도우 필터는 반드시 CTE/서브쿼리로 감싸서 바깥에서.
- "distinct 상위 N" = `DENSE_RANK`.

### ⚠️ 흔한 실수
- 윈도우를 WHERE에 직접 넣기.
- `ROW_NUMBER`를 써서 동점 급여를 놓치는 오답(같은 급여인데 3위 안에서 잘림).

### 💡 대안 / 응용
- "동점 무관, 정확히 3명"이면 `ROW_NUMBER`로 바꾼다.
- N을 파라미터화하면 Top-N 일반 문제로 확장.

---

## 3. [1204] Last Person to Fit in the Bus

**링크**: https://leetcode.cn/problems/last-person-to-fit-in-the-bus/  
**학습 포인트**: `SUM() OVER (ORDER BY ...)` 기본 프레임이 곧 누적합. 누적합 <= 1000의 마지막 사람.

### 문제
```
Queue(person_id PK, person_name, weight, turn)
```
버스 정원은 1000kg. `turn` 순서로 사람이 탄다. 누적 체중이 1000을 넘지 않는 한 계속 타고, 마지막으로 탑승 가능한 사람의 `person_name`을 출력.

### 정답
```sql
WITH cum AS (
  SELECT
    person_name,
    turn,
    SUM(weight) OVER (ORDER BY turn) AS total
  FROM Queue
)
SELECT person_name
FROM cum
WHERE total <= 1000
ORDER BY turn DESC
LIMIT 1;
```

### 풀이 — 왜 이렇게 하는가
1. `SUM(weight) OVER (ORDER BY turn)`은 ORDER BY만 있고 프레임을 생략했다. 이때 **기본 프레임은 `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`** — 즉 파티션 시작부터 현재 행까지의 합, 정확히 누적합(running total)이 된다. 별도 서브쿼리 없이 한 줄로 turn 순서 누적 체중을 얻는다.
2. `total <= 1000`인 사람들 중 turn이 가장 큰 사람이 "마지막 탑승자"다. `ORDER BY turn DESC LIMIT 1`.
3. 윈도우 결과 `total`도 WHERE에 직접 못 쓰므로 CTE로 감싼다.

과거 self-join `SUM`(`q1.turn >= q2.turn` 조건으로 합산) 방식은 삼각형 조인이라 O(n²)이고 GROUP BY까지 필요했다. 윈도우 누적합은 정렬 한 번으로 끝난다.

### 핵심 개념
- ORDER BY만 있는 집계 윈도우 = 누적합.
- 기본 프레임 `RANGE UNBOUNDED PRECEDING ~ CURRENT ROW`.
- 누적 조건 필터는 CTE로 감싸기.

### ⚠️ 흔한 실수
- `turn` 정렬을 빼먹으면 누적 순서가 뒤죽박죽 → 엉뚱한 누적합.
- `total <= 1000` 중 `MAX(turn)`을 안 잡고 그냥 하나 뽑기.

### 💡 대안 / 응용
- `RANGE`와 `ROWS`는 동점 turn이 없으면 결과가 같다. turn이 PK 성격(유일)이라 안전.

---

## 4. [1321] Restaurant Growth

**링크**: https://leetcode.cn/problems/restaurant-growth/  
**학습 포인트**: `ROWS BETWEEN 6 PRECEDING AND CURRENT ROW`로 7일 이동 윈도우. 첫 6일은 7일치가 안 차서 제외.

### 문제
```
Customer(customer_id, name, visited_on, amount)  -- (customer_id, visited_on) 유일
```
각 날짜에 대해 "그날 포함 직전 7일"의 매출 합계(`amount`)와 평균(`average_amount`, 소수 2자리)을 구한다. 단, 7일치가 모이는 날부터 출력(= 가장 이른 날짜 + 6일 이후). `visited_on` 오름차순.

### 정답
```sql
WITH daily AS (
  SELECT visited_on, SUM(amount) AS day_amount
  FROM Customer
  GROUP BY visited_on
),
win AS (
  SELECT
    visited_on,
    SUM(day_amount) OVER (
      ORDER BY visited_on
      ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
    ) AS amount,
    AVG(day_amount) OVER (
      ORDER BY visited_on
      ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
    ) AS avg7,
    ROW_NUMBER() OVER (ORDER BY visited_on) AS rn
  FROM daily
)
SELECT
  visited_on,
  amount,
  ROUND(avg7, 2) AS average_amount
FROM win
WHERE rn >= 7
ORDER BY visited_on;
```

### 풀이 — 왜 이렇게 하는가
1. 하루에 방문이 여러 건일 수 있으므로 먼저 `daily` CTE에서 날짜별 매출을 합친다(그래야 "6일 전 행"이 정확히 6일 전이 된다).
2. `ROWS BETWEEN 6 PRECEDING AND CURRENT ROW`는 "현재 행 + 직전 6개 행" = 7일 이동 윈도우. `ROWS`는 물리적 행 개수 기준이라, `daily`가 날짜별 1행이므로 정확히 7일이 된다.
3. 첫 6개 날짜는 앞에 6개 행이 다 없어 7일치가 안 찬다. `ROW_NUMBER`로 순번을 매겨 `rn >= 7`부터 출력한다(문제 요구).
4. 여기서 `ROWS` vs `RANGE`: `RANGE`는 값(날짜) 기준이라 프레임 계산이 다르다. "직전 6개 행"이라는 개수 의미에는 `ROWS`가 맞다.

### 핵심 개념
- `ROWS BETWEEN n PRECEDING AND CURRENT ROW`: 물리적 행 개수 기반 이동 윈도우.
- 날짜별 선집계 후 윈도우 → 하루 다건을 정규화.
- 7일 미충족 구간은 순번으로 제외.

### ⚠️ 흔한 실수
- 날짜별 집계 없이 원본에 바로 윈도우 → 하루 여러 건이면 "6행 전"이 6일 전이 아님.
- `ROWS`를 `RANGE`로 잘못 써서 프레임 의미가 달라짐.
- 첫 6일을 안 걸러 미완성 이동평균이 노출됨.

### 💡 대안 / 응용
- `RANGE BETWEEN INTERVAL 6 DAY PRECEDING AND CURRENT ROW`는 중간에 빠진 날짜가 있어도 "날짜 값 기준" 7일을 잡는다(요건에 따라 선택).

---

## 5. [1341] Movie Rating

**링크**: https://leetcode.cn/problems/movie-rating/  
**학습 포인트**: 윈도우가 항상 답은 아니다. 단순 "최댓값 1건 + 동점 사전순"은 `ORDER BY ... LIMIT 1`이 가장 자연스럽다. 두 결과를 `UNION ALL`.

### 문제
```
Movies(movie_id PK, title)
Users(user_id PK, name)
MovieRating(movie_id, user_id, rating, created_at)
```
두 행을 출력한다.
1. 리뷰를 가장 많이 남긴 사용자의 이름(동점이면 이름 사전순 앞).
2. 2020년 2월에 평균 평점이 가장 높은 영화 제목(동점이면 제목 사전순 앞).
결과 컬럼명은 `results`.

### 정답
```sql
(
  SELECT u.name AS results
  FROM MovieRating mr
  JOIN Users u ON u.user_id = mr.user_id
  GROUP BY mr.user_id, u.name
  ORDER BY COUNT(*) DESC, u.name ASC
  LIMIT 1
)
UNION ALL
(
  SELECT m.title AS results
  FROM MovieRating mr
  JOIN Movies m ON m.movie_id = mr.movie_id
  WHERE mr.created_at >= '2020-02-01'
    AND mr.created_at <  '2020-03-01'
  GROUP BY mr.movie_id, m.title
  ORDER BY AVG(mr.rating) DESC, m.title ASC
  LIMIT 1
);
```

### 풀이 — 왜 이렇게 하는가
1. 두 질문은 성격이 다르므로 각각 뽑아 `UNION ALL`로 붙인다. 둘 다 결과가 1행이라 순서 보존을 위해 각 SELECT를 괄호로 감싸고 각자 `LIMIT 1`.
2. 상단: 사용자별 리뷰 수 `COUNT(*)` 내림차순, 동점은 이름 사전순 오름차순, 첫 1건.
3. 하단: 2020-02 범위는 `>= '2020-02-01' AND < '2020-03-01'`로 잡는다(월말 경계·시간부 안전). 영화별 평균 `AVG(rating)` 내림차순, 동점은 제목 사전순, 첫 1건.
4. **윈도우 대비 판단**: "그룹별 최댓값 1건"은 `RANK() OVER` 뒤 CTE로 감싸 `= 1` 필터해도 되지만, 여기선 전체에서 딱 1행이면 되므로 정렬 + `LIMIT 1`이 코드가 짧고 의도가 명확하다. 윈도우는 "그룹마다 상위 N"처럼 파티션이 여럿일 때 진가를 발휘한다.

### 핵심 개념
- `UNION ALL`로 이질적 두 결과 결합(중복 제거 불필요 → ALL).
- 날짜 범위는 반열림 구간 `>= 시작 AND < 다음달`.
- 동점 타이브레이커는 `ORDER BY 기준, 텍스트 ASC`.

### ⚠️ 흔한 실수
- 괄호 없이 `UNION`에 각 `LIMIT`/`ORDER BY`를 붙여 파싱 모호 → 괄호로 감싸야 안전.
- `DATE_FORMAT(created_at,'%Y-%m')='2020-02'`도 되지만 인덱스 활용이 어렵다.

### 💡 대안 / 응용
- 굳이 윈도우로 풀면 `RANK() OVER (ORDER BY cnt DESC, name)` 후 `=1`. 그러나 이 문제엔 과한 도구.

---

## 6. [1164] Product Price at a Given Date

**링크**: https://leetcode.cn/problems/product-price-at-a-given-date/  
**학습 포인트**: 특정 시점 최신 가격 = `ROW_NUMBER() ... ORDER BY change_date DESC`로 상품별 최신 1건. 변경 이력이 없던 상품은 기본가 10.

### 문제
```
Products(product_id, new_price, change_date)  -- (product_id, change_date) 유일
```
모든 상품의 가격은 처음에 10이었다. 각 상품의 `2019-08-16` 시점 가격을 구한다. 그날 이전(포함)에 변경 이력이 있으면 가장 최근 변경가, 한 번도 변경이 없었으면 10. 컬럼: `product_id, price`.

### 정답
```sql
WITH latest AS (
  SELECT
    product_id,
    new_price,
    ROW_NUMBER() OVER (
      PARTITION BY product_id
      ORDER BY change_date DESC
    ) AS rn
  FROM Products
  WHERE change_date <= '2019-08-16'
)
SELECT product_id, new_price AS price
FROM latest
WHERE rn = 1

UNION

SELECT product_id, 10 AS price
FROM Products
WHERE product_id NOT IN (
  SELECT product_id FROM Products WHERE change_date <= '2019-08-16'
);
```

### 풀이 — 왜 이렇게 하는가
1. **기준일 이전 변경이 있는 상품**: `change_date <= '2019-08-16'`로 거른 뒤, 상품별로 `change_date` 내림차순 `ROW_NUMBER`를 매겨 `rn = 1`(가장 최근 변경)을 취한다. 이것이 그 시점의 가격.
2. **기준일까지 한 번도 변경이 없던 상품**: 위 집합에 안 들어온다. 그래서 두 번째 SELECT에서 "기준일 이전 변경이 없는 상품"을 골라 기본가 10을 준다.
3. 두 집합을 `UNION`으로 합친다(상호 배타라 UNION/UNION ALL 결과 동일하나 UNION이 안전).

윈도우의 장점: 상품별 "최신 1건"을 상관 서브쿼리 `MAX(change_date)`로 다시 조인하는 대신, `ROW_NUMBER = 1` 한 번으로 최신 행 전체(가격 포함)를 바로 집는다.

### 핵심 개념
- `PARTITION BY id ORDER BY date DESC` + `rn = 1` = 그룹별 최신 레코드.
- "시점 이전 최신값" 패턴(as-of query).
- 이력 없는 대상은 별도 SELECT + 기본값으로 보충.

### ⚠️ 흔한 실수
- 변경 이력이 없는 상품(기본가 10)을 빠뜨리기 — 가장 흔한 함정.
- `WHERE change_date <= ...`를 안 걸고 전체에서 rn=1 하면 미래 변경가가 섞임.

### 💡 대안 / 응용
- `NOT IN` 대신 `LEFT JOIN ... IS NULL` 또는 원본 상품 목록에 `COALESCE(price, 10)` 방식으로도 처리 가능.

---

## 7. [1934] Confirmation Rate

**링크**: https://leetcode.cn/problems/confirmation-rate/  
**학습 포인트**: `AVG(불리언 조건)`으로 비율을 바로 계산. 신호가 없는 사용자는 0 → `LEFT JOIN` + `IFNULL`. (윈도우 필수는 아니고 집계·ROUND 복습)

### 문제
```
Signups(user_id PK, time_stamp)
Confirmations(user_id, action, time_stamp)  -- action ∈ {'confirmed','timeout'}
```
각 가입 사용자의 confirmation rate = (confirmed 건수 / 전체 confirmation 요청 건수), 소수 2자리 반올림. 요청 신호가 하나도 없는 사용자는 0. 컬럼: `user_id, confirmation_rate`.

### 정답
```sql
SELECT
  s.user_id,
  ROUND(IFNULL(AVG(c.action = 'confirmed'), 0), 2) AS confirmation_rate
FROM Signups s
LEFT JOIN Confirmations c ON c.user_id = s.user_id
GROUP BY s.user_id;
```

### 풀이 — 왜 이렇게 하는가
1. **모든 가입자**가 결과에 나와야 하므로 `Signups`를 왼쪽에 두고 `LEFT JOIN`. confirmation이 없는 사용자도 살아남는다.
2. MySQL에서 `c.action = 'confirmed'`는 참이면 1, 거짓이면 0이다. 따라서 `AVG(c.action = 'confirmed')`는 "confirmed 비율"과 정확히 같다(= 1의 개수 / 전체 개수).
3. confirmation이 전혀 없는 사용자는 조인 결과가 전부 NULL이고 `AVG(NULL)`은 NULL이므로 `IFNULL(..., 0)`으로 0 처리.
4. `ROUND(x, 2)`로 소수 2자리.

### 핵심 개념
- `AVG(조건식)` = 조건 참 비율(불리언 → 1/0).
- 전체 대상 보존은 `LEFT JOIN`.
- `IFNULL`/`COALESCE`로 빈 그룹을 기본값 처리.

### ⚠️ 흔한 실수
- `INNER JOIN`을 써서 신호 없는 사용자가 통째로 사라짐.
- `AVG` 대신 `SUM(confirmed)/COUNT(*)`를 쓰며 정수 나눗셈으로 0이 나오는 실수(AVG가 더 안전).
- `IFNULL`을 빼서 신호 없는 사용자가 NULL로 남음.

### 💡 대안 / 응용
- `ROUND(SUM(c.action='confirmed') / COUNT(c.action), 2)`도 가능하나 분모 0(빈 그룹) 처리를 별도로 해야 한다.

---

## 8. [1211] Queries Quality and Percentage

**링크**: https://leetcode.cn/problems/queries-quality-and-percentage/  
**학습 포인트**: `AVG(rating/position)`으로 품질, `AVG(rating < 3)`으로 불량 비율. 하나의 GROUP BY로 두 지표. (집계·ROUND 복습)

### 문제
```
Queries(query_name, result, position, rating)
```
각 `query_name`에 대해:
- `quality` = AVG(rating / position), 소수 2자리.
- `poor_query_percentage` = rating < 3인 쿼리의 비율(%) , 소수 2자리.
`query_name`이 NULL인 행은 무시(그룹에서 제외).

### 정답
```sql
SELECT
  query_name,
  ROUND(AVG(rating / position), 2) AS quality,
  ROUND(AVG(rating < 3) * 100, 2) AS poor_query_percentage
FROM Queries
WHERE query_name IS NOT NULL
GROUP BY query_name;
```

### 풀이 — 왜 이렇게 하는가
1. `quality`: 각 행의 `rating/position`을 구해 그룹 평균 → `AVG(rating / position)`. 나눗셈이 행 단위로 먼저 일어나고 그 평균이므로 `AVG(rating)/AVG(position)`과 다르다(주의).
2. `poor_query_percentage`: `rating < 3`은 1/0을 반환하므로 `AVG(...)`는 불량 비율(0~1), `* 100`으로 퍼센트. `ROUND(..., 2)`.
3. `query_name IS NULL` 행은 요구대로 `WHERE`에서 제외. (GROUP BY가 NULL을 별도 그룹으로 만들기 때문에 미리 걸러야 한다.)

### 핵심 개념
- `AVG(a/b)` ≠ `AVG(a)/AVG(b)` — 행 단위 계산 순서에 유의.
- `AVG(불리언)*100` = 퍼센트.
- 집계 전 필터는 `WHERE`(그룹 후 필터는 `HAVING`).

### ⚠️ 흔한 실수
- `AVG(rating)/AVG(position)`으로 잘못 계산.
- 퍼센트 변환에서 `*100`을 빼먹거나 반올림 자리 실수.

### 💡 대안 / 응용
- `SUM(rating < 3)/COUNT(*)*100`도 동일 결과. `AVG`가 더 간결.

---

## 9. [601] Human Traffic of Stadium

**링크**: https://leetcode.cn/problems/human-traffic-of-stadium/  
**학습 포인트**: gaps-and-islands. `id - ROW_NUMBER()`로 연속 그룹을 식별한 뒤 그룹 크기 >= 3.

### 문제
```
Stadium(id PK, visit_date, people)
```
`people >= 100`인 날이 **연속 3일 이상** 이어지는 구간의 모든 행을 출력. `id` 오름차순.
(문제 전제상 id는 날짜 순서와 일치, 연속 id = 연속 날짜.)

### 정답
```sql
WITH hi AS (
  SELECT
    id, visit_date, people,
    id - ROW_NUMBER() OVER (ORDER BY id) AS grp
  FROM Stadium
  WHERE people >= 100
),
sized AS (
  SELECT
    id, visit_date, people, grp,
    COUNT(*) OVER (PARTITION BY grp) AS cnt
  FROM hi
)
SELECT id, visit_date, people
FROM sized
WHERE cnt >= 3
ORDER BY id;
```

### 풀이 — 왜 이렇게 하는가
1. 먼저 `people >= 100`인 행만 남긴다. 이제 이들 중 **id가 연속인 구간**(섬, island)을 찾아야 한다.
2. 핵심 트릭 `id - ROW_NUMBER()`. 필터 후 남은 행에 id 순서대로 1,2,3,... 번호(rn)를 매긴다. id가 1씩 늘어나는 연속 구간에서는 `id - rn`이 상수로 고정된다. 중간이 끊기면 그 값이 바뀐다.

```
id  people  rn   id-rn(grp)
2   100     1    1   ┐
3   100     2    1   │ 연속 → grp=1
4   100     3    1   ┘
6   100     4    2   ┐ (5가 빠져 gap)
7   100     5    2   ┘ grp=2 (2개뿐)
8   100     6    2   → grp=2 (총 3개면 포함)
```
연속이면 id와 rn이 같은 보폭으로 증가해 차가 일정, 끊기면 차가 점프한다.

3. `grp`가 같은 행끼리가 하나의 연속 구간. `COUNT(*) OVER (PARTITION BY grp)`로 구간 크기를 세고 `cnt >= 3`인 구간만 남긴다.

self-join 3개(t1,t2,t3를 id로 이어 붙이는) 방식은 "정확히 3일"에 맞춰져 있어 4일·5일 연속으로 확장이 지저분하다. gaps-and-islands는 길이에 무관하게 `>= N`으로 일반화된다.

### 핵심 개념
- gaps-and-islands: `id - ROW_NUMBER()`가 연속 구간마다 상수 → 그룹 키.
- `COUNT(*) OVER (PARTITION BY grp)`로 구간 길이 측정.
- 필터(`people>=100`)를 rn 매기기 전에 적용해야 "연속"의 의미가 성립.

### ⚠️ 흔한 실수
- `people >= 100` 필터를 ROW_NUMBER 이후에 걸어 연속 판정이 깨짐(필터가 먼저다).
- id에 실제 gap이 있는데 id 대신 날짜로 rn을 매겨야 하는 경우 혼동(이 문제는 id 연속 = 날짜 연속 전제).

### 💡 대안 / 응용
- "연속 N일 이상"의 N만 바꾸면 즉시 재사용 가능한 범용 패턴.

---

## 10. [180] Consecutive Numbers

**링크**: https://leetcode.cn/problems/consecutive-numbers/  
**학습 포인트**: `LAG`/`LEAD`로 인접 행 비교, 또는 gaps-and-islands. 같은 값 3연속 판정을 윈도우로.

### 문제
```
Logs(id PK 자동증가, num)
```
`num`이 **연속으로 3번 이상** 나타난 서로 다른 값을 출력. 컬럼: `ConsecutiveNums`(중복 없이).

### 정답 (LAG/LEAD)
```sql
SELECT DISTINCT num AS ConsecutiveNums
FROM (
  SELECT
    num,
    LAG(num, 1) OVER (ORDER BY id) AS prev1,
    LAG(num, 2) OVER (ORDER BY id) AS prev2
  FROM Logs
) t
WHERE num = prev1 AND num = prev2;
```

### 풀이 — 왜 이렇게 하는가
1. `LAG(num, 1) OVER (ORDER BY id)`는 id 순서로 "1행 전"의 num, `LAG(num, 2)`는 "2행 전"의 num을 현재 행에 붙인다.
2. 현재·직전·직전전 세 값이 모두 같으면(`num = prev1 AND num = prev2`) 그 지점을 끝으로 3연속이 성립. 해당 num을 모은다.
3. 같은 값이 여러 번 3연속일 수 있으므로 `DISTINCT`로 중복 제거.
4. 윈도우 함수는 WHERE에 못 쓰므로 서브쿼리로 `prev1/prev2`를 만든 뒤 바깥에서 비교.

기존 self-join 3중 방식(`l1.id = l2.id-1 = l3.id-2`)은 id가 반드시 1씩 증가한다고 가정한다. `LAG(... ORDER BY id)`는 id에 gap이 있어도 "행 순서상 이전"을 정확히 집어 더 견고하다.

### 정답 (gaps-and-islands, 대안)
```sql
WITH g AS (
  SELECT num,
         id - ROW_NUMBER() OVER (PARTITION BY num ORDER BY id) AS grp
  FROM Logs
)
SELECT DISTINCT num AS ConsecutiveNums
FROM g
GROUP BY num, grp
HAVING COUNT(*) >= 3;
```
`PARTITION BY num`으로 값별 순번을 매기면, 같은 값이 연속인 구간에서 `id - rn`이 상수가 된다. 그 그룹 크기가 3 이상이면 3연속.

### 핵심 개념
- `LAG(col, n)` / `LEAD(col, n)`: 앞/뒤 n번째 행 값 가져오기.
- 윈도우 결과 비교는 서브쿼리로 감싸기.
- gaps-and-islands는 "값별 연속"에도 `PARTITION BY num`으로 적용.

### ⚠️ 흔한 실수
- `ORDER BY id`를 빼면 LAG의 "이전 행"이 정의되지 않아 결과 불안정.
- `num = prev1 AND num = prev2`에서 하나만 검사해 2연속을 3연속으로 오판.
- `DISTINCT`를 빼 같은 값이 여러 번 출력됨.

### 💡 대안 / 응용
- N연속 일반화: LAG 방식은 `LAG(num,1)...LAG(num,N-1)` 확장이 번거롭지만, gaps-and-islands 방식은 `HAVING COUNT(*) >= N`만 바꾸면 되어 확장에 유리.

---
