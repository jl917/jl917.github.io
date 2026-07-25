# STEP 10 — 실전 종합

> 조인·서브쿼리·집계·윈도우를 조합해 푸는 대표 문제들. 지금까지 배운 도구를 언제 꺼낼지 판단하는 연습이다.

[← 목록으로](index.md)

---

## 1. [262] Trips and Users

**링크**: https://leetcode.cn/problems/trips-and-users/  
**학습 포인트**: 조인 + 필터 + 조건부 집계(`AVG(불리언)`)를 한 번에 조합한다.
**관련 STEP**: STEP 4(JOIN)·STEP 6(집계)·STEP 7(조건부 집계)

### 문제

```
Trips(id, client_id, driver_id, city_id, status, request_at)
  status: 'completed' | 'cancelled_by_driver' | 'cancelled_by_client'
  request_at: 'YYYY-MM-DD'
Users(users_id, banned, role)
  banned: 'Yes' | 'No', role: 'client' | 'driver' | 'partner'
```

`2013-10-01` ~ `2013-10-03` 기간에 대해, **client 와 driver 가 모두 banned 되지 않은** 주문만 대상으로 날짜별 **취소율(Cancellation Rate)** 을 구한다. 취소율 = (그 날 취소된 주문 수) / (그 날 전체 주문 수), 소수 둘째 자리 반올림.

### 정답

```sql
SELECT
    t.request_at AS Day,
    ROUND(
        AVG(t.status LIKE 'cancelled%'),
        2
    ) AS 'Cancellation Rate'
FROM Trips t
JOIN Users uc ON t.client_id = uc.users_id AND uc.banned = 'No'
JOIN Users ud ON t.driver_id = ud.users_id AND ud.banned = 'No'
WHERE t.request_at BETWEEN '2013-10-01' AND '2013-10-03'
GROUP BY t.request_at;
```

### 풀이 — 왜 이렇게 하는가

1. **비금지 사용자 필터를 조인으로 처리**한다. client 와 driver 각각을 `Users` 와 조인해야 하므로 `Users` 를 두 번(`uc`, `ud`) 조인한다. `banned = 'No'` 조건을 `ON` 절에 두면 조인 시점에 바로 걸러진다.
2. **취소율은 조건부 집계로 계산**한다. MySQL 에서 불리언 표현식 `t.status LIKE 'cancelled%'` 은 참이면 1, 거짓이면 0 이다. 따라서 `AVG(불리언)` 자체가 곧 "취소 비율"이 된다. 굳이 `SUM(CASE ...) / COUNT(*)` 로 쓸 필요가 없다.
3. `WHERE` 로 기간을 좁힌 뒤 `request_at` 으로 그룹핑하면 날짜별 취소율이 나온다.

### 핵심 개념

- `Users` 를 **client 용·driver 용으로 두 번 조인**하는 self-multi-join 패턴.
- MySQL 의 불리언은 0/1 정수이므로 `AVG(조건)` = 비율.
- 필터 조건을 `ON` 에 두면 조인 단계에서 즉시 제거되어 의도가 명확하다.

### ⚠️ 흔한 실수

- driver 만 검사하고 client 의 banned 를 빼먹는 것. **둘 다** 비금지여야 한다.
- `status = 'cancelled'` 로 비교하는 것. 실제 값은 `cancelled_by_driver` / `cancelled_by_client` 이므로 `LIKE 'cancelled%'` 를 써야 한다.
- `ROUND` 를 빼먹어 자릿수가 초과되는 것.

### 💡 다른 풀이와 비교

- 조건부 집계를 명시적으로 쓰면 `ROUND(SUM(status != 'completed') / COUNT(*), 2)` 로도 가능하다. `!= 'completed'` 는 취소만 남는다는 가정에 의존하므로, 상태가 늘어날 수 있는 실무에서는 `LIKE 'cancelled%'` 가 더 안전하다.
- banned 필터를 `WHERE client_id IN (SELECT ... WHERE banned='No')` 서브쿼리로 처리할 수도 있으나, 두 컬럼 모두 걸어야 해 서브쿼리가 두 개 필요하고 가독성이 떨어진다. 조인이 정석이다.

---

## 2. [601] Human Traffic of Stadium

**링크**: https://leetcode.cn/problems/human-traffic-of-stadium/  
**학습 포인트**: gaps-and-islands — `id - ROW_NUMBER()` 로 연속 구간을 그룹핑한다.
**관련 STEP**: STEP 8(윈도우 함수)·STEP 9(연속 구간)

### 문제

```
Stadium(id, visit_date, people)
  id 는 자동 증가, 날짜 순서와 일치한다고 가정
```

방문객 수 `people >= 100` 인 날이 **연속으로 3일 이상** 이어지는 구간의 모든 행을 `id` 순으로 반환한다.

### 정답

```sql
WITH hot AS (
    SELECT
        id, visit_date, people,
        id - ROW_NUMBER() OVER (ORDER BY id) AS grp
    FROM Stadium
    WHERE people >= 100
)
SELECT id, visit_date, people
FROM hot
WHERE grp IN (
    SELECT grp FROM hot GROUP BY grp HAVING COUNT(*) >= 3
)
ORDER BY id;
```

### 풀이 — 왜 이렇게 하는가

1. **먼저 `people >= 100` 인 행만 남긴다.** 이 필터 후에도 원래 `id` 는 그대로 유지된다.
2. **gaps-and-islands 트릭**: 필터된 행에 `ROW_NUMBER()` 를 매기면 1, 2, 3, ... 로 촘촘히 붙는다. 원래 `id` 가 연속이면 `id - ROW_NUMBER()` 값이 **일정**하고, 중간에 100 미만 날이 끼어 `id` 가 건너뛰면 이 차이가 바뀐다. 따라서 `grp` 가 같은 행들이 곧 하나의 연속 구간이다.
3. 각 `grp` 를 세어 **3개 이상**인 그룹만 남기고, 그 그룹에 속한 원본 행을 출력한다.

### 핵심 개념

- **정수 연속성 판정 = `기준값 - 순번`이 상수인지**로 환원한다.
- 필터 후 `ROW_NUMBER()` 를 매기는 순서가 중요하다(`ORDER BY id`).
- 그룹 판정과 최종 출력이 같은 CTE(`hot`)를 두 번 참조한다.

### ⚠️ 흔한 실수

- `people >= 100` 필터 전에 `ROW_NUMBER()` 를 매기는 것. 그러면 연속 판정이 깨진다. **반드시 필터 후**에 순번을 매긴다.
- `id` 가 날짜 순서와 일치한다는 전제. 만약 불일치하면 `ROW_NUMBER() OVER (ORDER BY visit_date)` 와 날짜 기반 판정이 필요하다(문제에서는 일치 가정).

### 💡 다른 풀이와 비교

- **3중 self join** 대안: `s1, s2, s3` 를 `id` 가 연속(`s2.id = s1.id+1`, `s3.id = s2.id+2` 등 3가지 배치)이 되게 조인하고 셋 다 `people >= 100` 인 경우를 모아 `DISTINCT`. 직관적이지만 조인 조건이 6가지 조합으로 장황하고, "3일 이상"이 "정확히 3일 창"으로 제한돼 확장성이 나쁘다.
- gaps-and-islands 는 **연속 길이가 늘어나도 코드가 그대로**라 실전에서 우월하다. self join 은 "연속 N" 이 커질수록 급격히 복잡해진다.

---

## 3. [184] Department Highest Salary

**링크**: https://leetcode.cn/problems/department-highest-salary/  
**학습 포인트**: 부서별 최댓값 조회 — 윈도우 `RANK()` 와 상관 서브쿼리 두 갈래.
**관련 STEP**: STEP 5(서브쿼리)·STEP 8(윈도우 함수)

### 문제

```
Employee(id, name, salary, departmentId)
Department(id, name)
```

각 부서에서 **급여가 가장 높은** 직원(들)을 반환한다. 동일 최고 급여가 여러 명이면 **모두** 포함한다. 출력: `Department, Employee, Salary`.

### 정답

```sql
SELECT
    d.name AS Department,
    e.name AS Employee,
    e.salary AS Salary
FROM (
    SELECT
        name, salary, departmentId,
        RANK() OVER (PARTITION BY departmentId ORDER BY salary DESC) AS rnk
    FROM Employee
) e
JOIN Department d ON e.departmentId = d.id
WHERE e.rnk = 1;
```

### 풀이 — 왜 이렇게 하는가

1. **동점 다수 포함이 핵심 요구사항**이다. "최고 1명"이 아니라 "최고 급여를 받는 모두"이므로, 순번이 아니라 **순위**가 필요하다.
2. `RANK() OVER (PARTITION BY departmentId ORDER BY salary DESC)` 로 부서별 급여 순위를 매기면 동점자는 같은 `rnk` 를 받는다. `rnk = 1` 을 걸면 최고 급여자가 동점 포함 전부 선택된다.
3. 그 결과에 `Department` 를 조인해 부서명을 붙인다.

### 핵심 개념

- 동점을 **모두 포함**해야 하므로 `ROW_NUMBER()`(동점도 하나만) 가 아니라 `RANK()`/`DENSE_RANK()` 를 쓴다.
- `PARTITION BY` 로 부서별 독립 순위를 만든다.

### ⚠️ 흔한 실수

- `ROW_NUMBER()` 를 쓰면 동점 최고 급여자 중 한 명만 나와 오답이 된다.
- `MAX(salary)` 를 `GROUP BY departmentId` 로 구한 뒤 그냥 조인하면서 부서명·직원명 매칭을 놓치는 것.

### 💡 다른 풀이와 비교

- **상관 서브쿼리(IN) 방식**:
  ```sql
  SELECT d.name AS Department, e.name AS Employee, e.salary AS Salary
  FROM Employee e
  JOIN Department d ON e.departmentId = d.id
  WHERE e.salary = (
      SELECT MAX(salary) FROM Employee
      WHERE departmentId = e.departmentId
  );
  ```
  윈도우가 없던 시절의 표준 풀이. 부서별 `MAX` 를 상관 서브쿼리로 구해 같은 값을 가진 행을 모두 뽑는다. **동점 다수도 자연스럽게 포함**된다.
- 트레이드오프: 상관 서브쿼리는 부서 수만큼 재평가될 수 있어 대용량에서 불리하고, 윈도우 방식은 한 번의 스캔으로 순위를 매겨 일반적으로 더 효율적이며 "상위 N" 확장(184→185)이 자연스럽다.

---

## 4. [185] Department Top Three Salaries

**링크**: https://leetcode.cn/problems/department-top-three-salaries/  
**학습 포인트**: 부서별 상위 N — "서로 다른 급여" 기준이면 `DENSE_RANK()`.
**관련 STEP**: STEP 8(윈도우 함수)·STEP 5(상관 서브쿼리)

### 문제

```
Employee(id, name, salary, departmentId)
Department(id, name)
```

각 부서에서 **서로 다른 급여 기준 상위 3개**에 해당하는 직원을 모두 반환한다. 예를 들어 급여가 90, 90, 80, 70, 60 이면 상위 3개 급여값은 90/80/70 이고, 90 을 받는 두 명 모두 포함된다.

### 정답

```sql
SELECT
    d.name AS Department,
    e.name AS Employee,
    e.salary AS Salary
FROM (
    SELECT
        name, salary, departmentId,
        DENSE_RANK() OVER (PARTITION BY departmentId ORDER BY salary DESC) AS drnk
    FROM Employee
) e
JOIN Department d ON e.departmentId = d.id
WHERE e.drnk <= 3;
```

### 풀이 — 왜 이렇게 하는가

1. **"서로 다른 급여 상위 3"** 이라는 문구가 `DENSE_RANK()` 를 지목한다. `DENSE_RANK()` 는 동점에 같은 순위를 주면서 **순위 사이에 빈틈을 만들지 않는다**. 그래서 세 번째로 큰 "급여값"까지 정확히 `drnk <= 3` 으로 잡힌다.
2. 동점자는 같은 `drnk` 를 받으므로, 상위 3개 급여값에 해당하는 사람은 여러 명이어도 모두 포함된다.
3. 부서명을 조인해 마무리한다.

### 핵심 개념

- **`RANK` vs `DENSE_RANK`**: 90,90,80 에서 `RANK` 는 1,1,3 (80이 3위 → `<=3` 이면 통과)이지만, 값이 더 밀집하면 결과가 달라진다. "서로 다른 급여 개수" 기준일 때는 빈틈 없는 `DENSE_RANK` 가 정확하다.
- `PARTITION BY` 로 부서별 독립 순위.

### ⚠️ 흔한 실수

- `RANK()` 를 쓰는 것. "서로 다른 급여 3종"을 요구하는데 `RANK` 는 동점 수만큼 순위를 건너뛰어 세 번째 급여값을 놓칠 수 있다.
- `ROW_NUMBER()` 를 쓰면 동점자 중 일부만 나와 오답이 된다.

### 💡 다른 풀이와 비교

- **상관 서브쿼리 방식**(윈도우 없이):
  ```sql
  SELECT d.name AS Department, e.name AS Employee, e.salary AS Salary
  FROM Employee e
  JOIN Department d ON e.departmentId = d.id
  WHERE 3 > (
      SELECT COUNT(DISTINCT e2.salary)
      FROM Employee e2
      WHERE e2.departmentId = e.departmentId
        AND e2.salary > e.salary
  );
  ```
  "나보다 높은 **서로 다른** 급여가 3개 미만"이면 상위 3위 안이라는 논리. `COUNT(DISTINCT)` 가 `DENSE_RANK` 의 의미와 정확히 일치한다.
- 트레이드오프: 서브쿼리 방식은 각 행마다 부서 내 카운트를 재계산해 O(N²) 에 가깝다. 윈도우는 한 번의 정렬로 끝나 대용량에서 확연히 빠르다. 다만 오래된 MySQL 5.x 환경에서는 윈도우가 없어 서브쿼리 방식이 유일한 선택이었다.

---

## 5. [1321] Restaurant Growth

**링크**: https://leetcode.cn/problems/restaurant-growth/  
**학습 포인트**: 이동 합/평균 — 윈도우 프레임 `ROWS 6 PRECEDING`.
**관련 STEP**: STEP 8(윈도우 함수)·STEP 9(누적·이동 집계)

### 문제

```
Customer(customer_id, name, visited_on, amount)
```

먼저 날짜별 총매출로 집계한 뒤, 각 날짜에 대해 **당일 포함 최근 7일**의 매출 합(`amount`)과 평균(`average_amount`, 소수 둘째 자리 반올림)을 구한다. 7일 창을 완전히 채울 수 있는 날짜, 즉 **데이터의 7번째 날부터** 출력한다.

### 정답

```sql
WITH daily AS (
    SELECT visited_on, SUM(amount) AS day_amount
    FROM Customer
    GROUP BY visited_on
),
rolling AS (
    SELECT
        visited_on,
        SUM(day_amount) OVER w AS amount,
        ROUND(AVG(day_amount) OVER w, 2) AS average_amount,
        ROW_NUMBER() OVER (ORDER BY visited_on) AS rn
    FROM daily
    WINDOW w AS (ORDER BY visited_on ROWS 6 PRECEDING)
)
SELECT visited_on, amount, average_amount
FROM rolling
WHERE rn >= 7
ORDER BY visited_on;
```

### 풀이 — 왜 이렇게 하는가

1. **하루에 여러 손님이 있을 수 있으므로 먼저 날짜별로 집계**한다(`daily`). 이 선-집계를 빠뜨리면 7일 창이 "7행"이 아니라 "7손님"이 되어 틀린다.
2. **이동 창은 프레임으로 표현**한다. `ROWS 6 PRECEDING` 은 "현재 행 + 앞 6행 = 7행"을 의미한다. 여기에 `SUM`/`AVG` 를 씌우면 곧 7일 이동합·이동평균이다. `WINDOW w AS (...)` 로 프레임을 한 번 정의해 두 집계에 재사용한다.
3. **처음 6일은 창이 7일에 못 미친다.** `ROW_NUMBER()` 로 순번을 매겨 `rn >= 7` 인 날짜만 출력한다.

### 핵심 개념

- `ROWS 6 PRECEDING` = 당일 포함 7행 창. `ROWS` 는 물리적 행 기준(날짜가 매일 연속이라는 전제).
- `WINDOW` 절로 프레임을 명명해 중복 제거.
- 앞쪽 미완성 창은 `ROW_NUMBER >= 7` 로 잘라낸다.

### ⚠️ 흔한 실수

- 날짜별 선-집계 없이 원본에 바로 윈도우를 적용하는 것. 하루 다중 방문이 있으면 창 크기가 틀어진다.
- `RANGE` 와 `ROWS` 혼동. 날짜 gap 없이 매일 데이터가 있다는 전제 하에서 `ROWS 6 PRECEDING` 이 정확하다. 결측일이 있다면 날짜 기반 `RANGE` 가 필요하다.
- 처음 6일을 필터하지 않아 불완전한 창까지 출력하는 것.

### 💡 다른 풀이와 비교

- **자기 조인 서브쿼리 방식**: 각 날짜에 대해 `visited_on BETWEEN d-6 AND d` 범위를 조인해 `SUM`/`AVG` 를 구한다. 윈도우가 없던 시절 표준이지만, 날짜 산술과 범위 조인이 장황하고 성능도 떨어진다.
- 윈도우 프레임은 **의도가 문장 그대로 코드에 드러나고**(7행 창), 한 번의 스캔으로 계산돼 실전에서 압도적으로 낫다.

---

## 6. [1341] Movie Rating

**링크**: https://leetcode.cn/problems/movie-rating/  
**학습 포인트**: 서로 다른 두 Top-1 결과를 `UNION` 으로 합치기 — 각 파트는 `ORDER BY ... LIMIT 1`.
**관련 STEP**: STEP 6(집계)·STEP 7(정렬·LIMIT)·STEP 4(JOIN)

### 문제

```
Movies(movie_id, title)
Users(user_id, name)
MovieRating(movie_id, user_id, rating, created_at)
```

두 결과를 한 컬럼(`results`)에 세로로 합쳐 반환한다.
1. **리뷰를 가장 많이 남긴 사용자 이름** (동점이면 이름 사전순 오름차순 1명).
2. **2020년 2월** 평균 평점이 가장 높은 **영화 제목** (동점이면 제목 사전순 오름차순 1편).

### 정답

```sql
(
    SELECT u.name AS results
    FROM MovieRating mr
    JOIN Users u ON mr.user_id = u.user_id
    GROUP BY mr.user_id, u.name
    ORDER BY COUNT(*) DESC, u.name ASC
    LIMIT 1
)
UNION ALL
(
    SELECT m.title AS results
    FROM MovieRating mr
    JOIN Movies m ON mr.movie_id = m.movie_id
    WHERE mr.created_at BETWEEN '2020-02-01' AND '2020-02-29'
    GROUP BY mr.movie_id, m.title
    ORDER BY AVG(mr.rating) DESC, m.title ASC
    LIMIT 1
);
```

### 풀이 — 왜 이렇게 하는가

1. **두 개의 독립된 Top-1 질의**를 각각 만든 뒤 `UNION ALL` 로 세로 결합한다. 컬럼명을 둘 다 `results` 로 맞춰야 한 컬럼으로 합쳐진다.
2. **파트 1**: 사용자별 리뷰 수를 세고 `ORDER BY COUNT(*) DESC, name ASC LIMIT 1`. 동점 처리를 위해 2차 정렬 키로 이름 오름차순을 둔다.
3. **파트 2**: 2020-02 기간으로 필터한 뒤 영화별 평균 평점을 구하고 `ORDER BY AVG(rating) DESC, title ASC LIMIT 1`. 마찬가지로 동점이면 제목 사전순.
4. 각 서브쿼리를 괄호로 감싸야 `ORDER BY`/`LIMIT` 가 각 파트에 개별 적용된다.

### 핵심 개념

- **`ORDER BY 정렬키 DESC, 이름 ASC LIMIT 1`** 은 "동점 시 사전순 1개"의 정석 패턴.
- `UNION ALL` 로 성격이 다른 두 단일 값을 한 컬럼에 결합.
- 기간 필터는 `BETWEEN '2020-02-01' AND '2020-02-29'`(2020년은 윤년이라 29일).

### ⚠️ 흔한 실수

- `UNION` (중복 제거)을 쓰면 우연히 사용자 이름과 영화 제목이 같을 때 한 행이 사라진다. **`UNION ALL`** 을 써야 안전하다.
- 괄호 없이 마지막 쿼리에만 `ORDER BY`/`LIMIT` 를 붙이면 전체에 적용되어 오답이 된다.
- 파트 2 의 날짜 상한을 `'2020-02-28'` 로 두는 것(2020 윤년 → 29일까지). `YEAR()=2020 AND MONTH()=2` 로 쓰면 이 실수를 피할 수 있다.

### 💡 다른 풀이와 비교

- 날짜 필터를 `WHERE created_at LIKE '2020-02%'` 또는 `YEAR(created_at)=2020 AND MONTH(created_at)=2` 로 써도 된다. 함수 적용은 인덱스를 못 타므로 대용량에서는 범위 조건(`BETWEEN`)이 유리하다.
- 두 결과를 하나의 쿼리로 합치는 대신 애플리케이션에서 두 번 질의할 수도 있으나, 문제는 단일 결과셋을 요구하므로 `UNION ALL` 이 정답 형태다.

---

## 7. [1934] Confirmation Rate

**링크**: https://leetcode.cn/problems/confirmation-rate/  
**학습 포인트**: 신호 없는 사용자도 0 으로 포함 — `LEFT JOIN` + `AVG(불리언)`.
**관련 STEP**: STEP 4(LEFT JOIN)·STEP 7(조건부 집계)

### 문제

```
Signups(user_id, time_stamp)
Confirmations(user_id, time_stamp, action)
  action: 'confirmed' | 'timeout'
```

각 사용자의 **확인율(confirmation rate)** 을 구한다. = (confirmed 요청 수) / (전체 확인 요청 수), 소수 둘째 자리 반올림. **확인 요청 기록이 전혀 없는 사용자는 확인율 0.00**.

### 정답

```sql
SELECT
    s.user_id,
    ROUND(AVG(c.action = 'confirmed'), 2) AS confirmation_rate
FROM Signups s
LEFT JOIN Confirmations c ON s.user_id = c.user_id
GROUP BY s.user_id;
```

### 풀이 — 왜 이렇게 하는가

1. **모든 가입자를 기준으로 삼아야** 하므로 `Signups` 를 왼쪽에 두고 `Confirmations` 를 `LEFT JOIN` 한다. 확인 기록이 없는 사용자도 결과에 남는다.
2. **확인율은 조건부 집계**로 구한다. `c.action = 'confirmed'` 는 1/0 이므로 `AVG(...)` 가 곧 confirmed 비율이다.
3. **신호가 없는 사용자 처리가 핵심 포인트**다. `LEFT JOIN` 결과 `c.action` 이 전부 `NULL` 인데, `AVG` 는 NULL 을 **무시**한다. 그런데 NULL 만 있으면 `AVG` 는 NULL 을 반환한다. 다행히 이 문제의 판정은 대개 NULL→0 처리를 요구하므로, 안전하게 하려면 아래처럼 감싼다.

```sql
SELECT
    s.user_id,
    ROUND(IFNULL(AVG(c.action = 'confirmed'), 0), 2) AS confirmation_rate
FROM Signups s
LEFT JOIN Confirmations c ON s.user_id = c.user_id
GROUP BY s.user_id;
```

`AVG(c.action = 'confirmed')` 이 매칭 0건이면 NULL 이므로 `IFNULL(..., 0)` 으로 0 을 보장한다.

### 핵심 개념

- **`LEFT JOIN` = "왼쪽 전체 유지"**, 그래서 확인 기록 없는 가입자도 남는다.
- `AVG(불리언)` = 비율. 단, 대상 행이 모두 NULL 이면 `AVG` 는 NULL → `IFNULL` 로 0 방어.
- `GROUP BY s.user_id` 로 사용자별 집계.

### ⚠️ 흔한 실수

- `INNER JOIN` 을 쓰면 확인 기록 없는 사용자가 사라져 "0.00 으로 포함" 요구를 어긴다.
- NULL 방어를 안 해 신호 없는 사용자가 NULL 로 나오는 것(`IFNULL`/`COALESCE` 로 0 처리).
- `SUM(action='confirmed') / COUNT(*)` 로 쓸 때 `COUNT(*)` 가 LEFT JOIN 미매칭 행까지 1 로 세어 분모가 틀어지는 것. 이때는 `COUNT(c.action)` 을 써야 한다. `AVG(불리언)` 방식은 이 함정이 없어 더 안전하다.

### 💡 다른 풀이와 비교

- **명시적 조건부 집계 방식**:
  ```sql
  ROUND(
      SUM(c.action = 'confirmed') / COUNT(c.action),
      2
  )
  ```
  `COUNT(c.action)` 은 NULL 을 세지 않으므로 미매칭 사용자는 `0/0` → NULL 이 되어 역시 `IFNULL` 이 필요하다. `AVG(불리언)` 이 더 간결하다.
- 서브쿼리로 사용자별 confirmed 수와 전체 수를 따로 구해 나눌 수도 있으나, 한 번의 `LEFT JOIN + GROUP BY` 가 가장 간명하다.

---

## 마무리 — 문제 유형별 도구 선택 치트시트

실전에서는 "무엇을 요구하는가"를 유형으로 환원한 뒤 1순위 도구부터 꺼낸다.

| 유형 | 1순위 도구 | 왜 | 대표 문제 |
|------|-----------|-----|-----------|
| **순위 / Top-N** | `RANK` / `DENSE_RANK` / `ROW_NUMBER` OVER (PARTITION BY ...) | 동점 포함 여부로 함수 선택(모두 포함=RANK/DENSE_RANK, 딱 1개=ROW_NUMBER) | 184, 185 |
| **누적 · 이동 집계** | 윈도우 프레임 `SUM/AVG OVER (ORDER BY ... ROWS n PRECEDING)` | 창 크기가 문장 그대로 코드에 드러나고 단일 스캔 | 1321 |
| **안티 조인 (없는 것 찾기)** | `LEFT JOIN ... WHERE 우측 IS NULL` 또는 `NOT EXISTS` | 매칭 실패 행만 남김. `NOT IN` 은 NULL 함정 주의 | 1934(포함 유지 관점) |
| **연속 구간** | gaps-and-islands: `값 - ROW_NUMBER()` 로 그룹핑 | 연속 길이가 늘어도 코드 불변, self join 대비 확장성 우수 | 601 |
| **피벗 (행→열)** | 조건부 집계 `SUM(CASE WHEN ... THEN ... END)` | 카테고리별 컬럼을 한 번의 GROUP BY 로 생성 | 262(조건부 집계 응용) |
| **비율 계산** | `AVG(불리언조건)` (+ 필요 시 `IFNULL(...,0)`) | MySQL 불리언=0/1 이라 AVG 가 곧 비율, `SUM/COUNT` 분모 함정 회피 | 262, 1934 |
| **Top-1 여러 개 결합** | 각 파트 `ORDER BY ... LIMIT 1` 후 `UNION ALL` | 성격 다른 단일 값들을 한 컬럼으로, 동점은 2차 정렬키로 | 1341 |

**도구 선택 원칙**

1. **동점을 어떻게 다루나?** — 포함이면 `RANK`/`DENSE_RANK`, 정렬 대표 1개면 `ORDER BY ... LIMIT 1`.
2. **없는 행도 결과에 남겨야 하나?** — 그렇다면 `LEFT JOIN` + NULL 방어(`IFNULL`).
3. **비율·조건부 카운트인가?** — MySQL 에선 `AVG(조건)` / `SUM(조건)` 이 `CASE` 보다 간결하다.
4. **윈도우 vs 서브쿼리** — 대용량·상위 N·이동 집계는 윈도우가 우월. 단, 구버전 MySQL 5.x 는 상관 서브쿼리가 유일한 대안.
