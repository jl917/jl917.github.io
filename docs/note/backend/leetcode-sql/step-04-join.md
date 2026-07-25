# STEP 4 — JOIN

> 안티조인, 상관 서브쿼리, 그룹별 Top-N, 연속값 탐지까지 — 조인의 실전 패턴을 총정리한다.

[← 목록으로](index.md)

---

## 1. [1070] Product Sales Analysis III

**링크**: https://leetcode.cn/problems/product-sales-analysis-iii/  
**학습 포인트**: 그룹별 최소값을 서브쿼리로 구한 뒤 튜플 `IN`으로 매칭하기.

### 문제

- `Sales(sale_id, product_id, year, quantity, price)` — `(sale_id, year)`가 기본키
- `Product(product_id, product_name)`

각 상품(`product_id`)에 대해 **가장 먼저 판매된 연도(첫 판매연도)**의 `product_id`, `first_year`, `quantity`, `price`를 구한다. 한 상품이 첫 해에 여러 건 팔렸다면 그 행을 모두 출력한다.

### 정답

```sql
SELECT
    product_id,
    year AS first_year,
    quantity,
    price
FROM Sales
WHERE (product_id, year) IN (
    SELECT product_id, MIN(year)
    FROM Sales
    GROUP BY product_id
);
```

### 풀이 — 왜 이렇게 하는가

1. "각 상품의 첫 판매연도"는 `product_id`별 `MIN(year)`다. 이를 서브쿼리로 뽑으면 `(product_id, 첫해)` 쌍의 목록이 나온다.
2. 본문 `Sales`에서 그 쌍과 일치하는 행만 남기면 된다. MySQL은 `(a, b) IN (SELECT ...)` 형태의 **행(튜플) 비교**를 지원하므로 한 번에 매칭한다.
3. 한 상품이 첫해에 여러 건 팔렸다면 조건을 만족하는 행이 여러 개이므로 자연스럽게 모두 출력된다. 별도 집계가 필요 없다.
4. `Product` 테이블은 이 문제에서 실제로 필요 없다(`product_name`을 요구하지 않음).

### 핵심 개념

- **튜플 IN**: `(col1, col2) IN (subquery)`로 두 컬럼을 동시에 매칭.
- **그룹별 최소값 재조인**: `GROUP BY`로 대푯값을 구하고 원본과 다시 매칭하는 전형 패턴.

### ⚠️ 흔한 실수

- `MIN(year)`만 SELECT하고 `product_id`를 빼면 어떤 상품의 최소연도인지 알 수 없어 매칭이 무너진다. 반드시 `product_id`와 함께 반환한다.
- `GROUP BY product_id` 후 `quantity`, `price`를 그냥 SELECT하면 첫해가 아닌 임의 행의 값이 섞일 수 있다. 집계로 해결하려 하지 말고 재조인/서브쿼리로 원본 행을 꺼내야 한다.

### 💡 대안 / 응용

- 조인 버전:
  ```sql
  SELECT s.product_id, s.year AS first_year, s.quantity, s.price
  FROM Sales s
  JOIN (
      SELECT product_id, MIN(year) AS first_year
      FROM Sales GROUP BY product_id
  ) f ON s.product_id = f.product_id AND s.year = f.first_year;
  ```

---

## 2. [1075] Project Employees I

**링크**: https://leetcode.cn/problems/project-employees-i/  
**학습 포인트**: 조인 후 그룹 집계 + `ROUND(AVG(...), 2)`.

### 문제

- `Project(project_id, employee_id)` — `(project_id, employee_id)`가 기본키
- `Employee(employee_id, name, experience_years)`

각 프로젝트별로 소속 직원들의 **평균 근속연수**를 소수점 둘째 자리까지 반올림해 구한다.

### 정답

```sql
SELECT
    p.project_id,
    ROUND(AVG(e.experience_years), 2) AS average_years
FROM Project p
JOIN Employee e ON p.employee_id = e.employee_id
GROUP BY p.project_id;
```

### 풀이 — 왜 이렇게 하는가

1. `Project`는 프로젝트-직원 매핑만 가지고 근속연수가 없으므로, `employee_id`로 `Employee`를 조인해 `experience_years`를 붙인다.
2. 프로젝트 단위 집계이므로 `GROUP BY p.project_id`.
3. `AVG(e.experience_years)`는 부동소수 결과를 내므로 `ROUND(..., 2)`로 요구 형식(소수 둘째 자리)에 맞춘다.

### 핵심 개념

- **JOIN 후 GROUP BY**: 매핑 테이블 + 속성 테이블 결합 후 그룹 집계.
- **ROUND(값, 자릿수)**: 반올림 자릿수 명시.

### ⚠️ 흔한 실수

- `GROUP BY`를 빼면 전체 평균 한 행만 나온다. 프로젝트별로 묶어야 한다.
- `ROUND`를 생략하면 `3.3333...` 같은 값이 나와 오답 처리된다.

---

## 3. [181] Employees Earning More Than Their Managers

**링크**: https://leetcode.cn/problems/employees-earning-more-than-their-managers/  
**학습 포인트**: 같은 테이블을 두 번 조인하는 셀프 조인.

### 문제

- `Employee(id, name, salary, managerId)` — `managerId`는 같은 테이블의 `id`를 가리킴(상사 없으면 NULL)

**자기 상사보다 급여가 높은** 직원의 이름(`Employee`)을 구한다.

### 정답

```sql
SELECT e.name AS Employee
FROM Employee e
JOIN Employee m ON e.managerId = m.id
WHERE e.salary > m.salary;
```

### 풀이 — 왜 이렇게 하는가

1. 한 직원 행에는 자기 급여와 상사의 `id`만 있고 상사의 급여가 없다. 상사 급여를 알려면 같은 테이블을 한 번 더 참조해야 한다.
2. `e`(직원)와 `m`(매니저) 두 별칭으로 셀프 조인: `e.managerId = m.id`로 직원 행에 그의 상사 행을 붙인다.
3. `WHERE e.salary > m.salary`로 상사보다 급여 높은 직원만 남긴다.
4. `INNER JOIN`이라 `managerId`가 NULL인 사장은 자동으로 제외된다(비교 대상이 없음). 문제 취지와 일치.

### 핵심 개념

- **셀프 조인(Self Join)**: 계층 구조(직원-상사)를 같은 테이블 두 별칭으로 결합.
- **INNER JOIN의 NULL 제거**: 매칭 안 되는 상사 없는 행은 결과에서 빠진다.

### ⚠️ 흔한 실수

- 별칭 없이 `Employee`를 두 번 쓰면 컬럼 참조가 모호해진다. 반드시 별칭 부여.
- 조인 조건과 비교 조건을 헷갈려 `ON e.salary > m.salary`처럼 섞지 말 것. 관계는 `ON`, 필터는 `WHERE`.

---

## 4. [626] Exchange Seats

**링크**: https://leetcode.cn/problems/exchange-seats/  
**학습 포인트**: `CASE`로 홀짝 좌석을 교환하되 마지막 홀수 좌석은 그대로 두기.

### 문제

- `Seat(id, student)` — `id`는 연속된 정수(1부터), 기본키

짝을 지어 **홀수 id와 바로 다음 짝수 id의 학생을 서로 바꾼다**. 즉 (1↔2), (3↔4), ... 학생이 홀수라 마지막 id가 짝을 못 이루면(전체 학생 수가 홀수) 그 좌석은 그대로 둔다. 결과는 `id` 순서로 출력.

### 정답

```sql
SELECT
    CASE
        WHEN id % 2 = 1 AND id = (SELECT MAX(id) FROM Seat) THEN id
        WHEN id % 2 = 1 THEN id + 1
        ELSE id - 1
    END AS id,
    student
FROM Seat
ORDER BY id;
```

### 풀이 — 왜 이렇게 하는가

1. 좌석의 학생을 옮기는 대신, **id를 재배치**하고 마지막에 `ORDER BY id`로 정렬하는 방식이 간결하다.
2. 홀수 id는 `+1`(다음 짝수 자리로), 짝수 id는 `-1`(앞 홀수 자리로) 밀면 두 학생이 교환된다.
3. 예외: 학생 수가 홀수면 **가장 큰 id가 홀수**이고 짝이 없다. 이 경우 `+1`을 하면 존재하지 않는 자리로 밀리므로, `id = MAX(id)`이면서 홀수이면 그대로 둔다. 이 조건을 `CASE`의 첫 분기에 두어 우선 처리한다.
4. `CASE`는 위에서부터 첫 참 분기만 적용하므로 "마지막 홀수" 예외가 일반 홀수 규칙보다 먼저 검사되어야 한다.

### 핵심 개념

- **CASE 분기 순서**: 특수 케이스(마지막 홀수)를 일반 케이스보다 위에 둔다.
- **MAX(id)로 마지막 판정**: id가 1부터 연속이므로 최대 id가 마지막 좌석.
- **id 재배치 + ORDER BY**: 값을 바꾸는 대신 키를 바꿔 정렬.

### ⚠️ 흔한 실수

- 마지막 홀수 예외를 빠뜨리면 학생 수가 홀수일 때 마지막 좌석이 사라지거나 NULL이 된다.
- `CASE` 분기 순서를 뒤집어 일반 홀수 규칙을 먼저 두면 마지막 홀수도 `+1`되어 버린다.

### 💡 대안 / 응용

- 윈도우 함수 `COUNT(*) OVER ()`로 총 개수를 구해 비교하거나, `LEAD/LAG`로 옆자리 학생을 직접 당겨오는 방식도 가능(STEP 8에서 다룸).

---

## 5. [183] Customers Who Never Order

**링크**: https://leetcode.cn/problems/customers-who-never-order/  
**학습 포인트**: 안티조인 — "존재하지 않는 것"을 찾는 세 가지 방법.

### 문제

- `Customers(id, name)`
- `Orders(id, customerId)`

**주문을 한 번도 하지 않은** 고객의 이름을 구한다.

### 정답

```sql
SELECT c.name AS Customers
FROM Customers c
LEFT JOIN Orders o ON c.id = o.customerId
WHERE o.customerId IS NULL;
```

### 풀이 — 왜 이렇게 하는가

1. "주문이 없는 고객"은 대표적인 **안티조인(anti-join)** 문제다.
2. `LEFT JOIN`으로 모든 고객을 남기고, 주문이 있으면 `Orders` 컬럼이 채워지고 없으면 NULL이 된다.
3. `WHERE o.customerId IS NULL`은 매칭되는 주문이 하나도 없던 고객만 골라낸다.

### 핵심 개념

- **LEFT JOIN + IS NULL 안티조인**: 매칭 실패한 왼쪽 행만 남기는 관용구.
- **안티조인 3형태 비교**:
  - `NOT EXISTS`: 상관 서브쿼리. NULL에 안전하고 대개 성능이 좋다.
  - `NOT IN`: 서브쿼리 결과에 **NULL이 하나라도 있으면 전체가 빈 결과**가 되는 함정.
  - `LEFT JOIN ... IS NULL`: 직관적이고 널리 쓰임.

### ⚠️ 흔한 실수

- `NOT IN`을 쓸 때 `Orders.customerId`에 NULL이 섞이면 결과가 통째로 사라진다. 안전하게 쓰려면 `WHERE customerId IS NOT NULL`을 서브쿼리에 붙여야 한다.

### 💡 대안 / 응용

- `NOT EXISTS` 버전(권장):
  ```sql
  SELECT c.name AS Customers
  FROM Customers c
  WHERE NOT EXISTS (
      SELECT 1 FROM Orders o WHERE o.customerId = c.id
  );
  ```

---

## 6. [184] Department Highest Salary

**링크**: https://leetcode.cn/problems/department-highest-salary/  
**학습 포인트**: 그룹별 최대값과 튜플 매칭(동점 다수 포함).

### 문제

- `Employee(id, name, salary, departmentId)`
- `Department(id, name)`

각 부서에서 **급여가 가장 높은** 직원(들)의 `Department`, `Employee`, `Salary`를 구한다. 동일 최고 급여자가 여러 명이면 모두 출력한다.

### 정답

```sql
SELECT
    d.name AS Department,
    e.name AS Employee,
    e.salary AS Salary
FROM Employee e
JOIN Department d ON e.departmentId = d.id
WHERE (e.departmentId, e.salary) IN (
    SELECT departmentId, MAX(salary)
    FROM Employee
    GROUP BY departmentId
);
```

### 풀이 — 왜 이렇게 하는가

1. 부서별 최고 급여는 `departmentId`별 `MAX(salary)`. 서브쿼리로 `(부서, 최고급여)` 쌍을 만든다.
2. 본문 `Employee`에서 `(departmentId, salary)`가 그 쌍과 일치하는 직원을 튜플 `IN`으로 고른다. 최고 급여가 여러 명이면 모두 조건을 만족하므로 **동점자 전원 출력**.
3. 부서 이름을 위해 `Department`를 조인.

### 핵심 개념

- **튜플 IN으로 그룹 최대 매칭**: `(부서, MAX)` 쌍과 일치하는 원본 행 추출.
- **동점 다수 처리**: 집계로 한 명만 뽑는 방식과 달리, 매칭 방식은 동률을 자연히 포함.

### ⚠️ 흔한 실수

- 서브쿼리에서 `MAX(salary)`만 뽑아 `salary IN (...)`로 비교하면 다른 부서의 최고 급여와도 매칭되어 오답이 된다. 반드시 `departmentId`와 짝지어 비교.

### 💡 대안 / 응용

- 윈도우 함수:
  ```sql
  SELECT Department, Employee, Salary FROM (
      SELECT d.name AS Department, e.name AS Employee, e.salary AS Salary,
             RANK() OVER (PARTITION BY e.departmentId ORDER BY e.salary DESC) rk
      FROM Employee e JOIN Department d ON e.departmentId = d.id
  ) t WHERE rk = 1;
  ```

---

## 7. [185] Department Top Three Salaries

**링크**: https://leetcode.cn/problems/department-top-three-salaries/  
**학습 포인트**: 상관 서브쿼리로 "더 높은 서로 다른 급여의 개수"를 세어 Top-N 구하기.

### 문제

- `Employee(id, name, salary, departmentId)`
- `Department(id, name)`

각 부서에서 **급여 기준 상위 3개의 서로 다른(distinct) 급여**에 해당하는 직원을 모두 구한다. 즉 부서 내에서 자기보다 높은 "서로 다른 급여" 값이 3개 미만이면 High Earner다. 같은 급여를 받는 사람이 여러 명이면 모두 포함.

### 정답

```sql
SELECT
    d.name AS Department,
    e.name AS Employee,
    e.salary AS Salary
FROM Employee e
JOIN Department d ON e.departmentId = d.id
WHERE (
    SELECT COUNT(DISTINCT e2.salary)
    FROM Employee e2
    WHERE e2.departmentId = e.departmentId
      AND e2.salary > e.salary
) < 3;
```

### 풀이 — 왜 이렇게 하는가

이 문제의 핵심은 "상위 3개"를 **순위가 아니라 '나보다 높은 급여 종류의 개수'로 재정의**하는 것이다. 차근차근 보자.

1. 어떤 직원 `e`가 부서 내에서 몇 등인지 알려면, **같은 부서에서 `e`보다 급여가 높은 사람**이 몇 명인지 세면 된다.
2. 그런데 "서로 다른 급여 3개"가 조건이므로 인원수가 아니라 **급여 값의 종류 수**를 세야 한다. 그래서 `COUNT(DISTINCT e2.salary)`.
3. 자기보다 높은 서로 다른 급여가
   - 0개면 → 1위 급여
   - 1개면 → 2위 급여
   - 2개면 → 3위 급여
   즉 **`< 3`이면 상위 3개 급여 안에 든다.**
4. 이 계산을 각 직원 행마다 수행해야 하므로 **상관 서브쿼리**를 쓴다. 서브쿼리 안의 `e2.departmentId = e.departmentId`가 바깥 행의 부서로 범위를 좁히는 상관 조건이다.
5. 같은 급여가 여러 명이어도 각자 동일하게 "나보다 높은 distinct 급여 수"가 같으므로 함께 통과한다 → 동점자 전원 포함.

### 핵심 개념

- **상관 서브쿼리(correlated subquery)**: 바깥 행마다 서브쿼리를 재평가. 여기서는 부서·급여 기준으로 순위를 매기는 도구.
- **DISTINCT로 순위 정의**: "상위 N개 급여"는 인원이 아니라 급여 값의 종류를 세야 한다.
- **`COUNT(더 큰 값) < N` 패턴**: 윈도우 함수 없이 그룹별 Top-N을 구하는 고전 기법.

### ⚠️ 흔한 실수

- `COUNT(DISTINCT ...)`가 아니라 그냥 `COUNT(*)`로 세면, 동일 급여 인원이 많은 부서에서 순위가 왜곡된다(같은 급여를 여러 등수로 계산).
- `AND e2.salary > e.salary`에서 `>`를 `>=`로 쓰면 자기 자신 포함으로 카운트가 하나 늘어 결과가 어긋난다.
- 상관 조건 `e2.departmentId = e.departmentId`를 빠뜨리면 전 부서를 통틀어 비교하게 된다.

### 💡 대안 / 응용

- 정석은 윈도우 함수 `DENSE_RANK() OVER (PARTITION BY departmentId ORDER BY salary DESC) <= 3`이다. "서로 다른 급여" 요건 때문에 `RANK`가 아니라 `DENSE_RANK`를 써야 한다는 점이 포인트(STEP 8에서 상세히 다룬다).

---

## 8. [602] Friend Requests II: Who Has the Most Friends

**링크**: https://leetcode.cn/problems/friend-requests-ii-who-has-the-most-friends/  
**학습 포인트**: 두 방향 컬럼을 `UNION ALL`로 세로로 합쳐 카운트.

### 문제

- `RequestAccepted(requester_id, accepter_id, accept_date)` — `(requester_id, accepter_id)`가 기본키(수락된 요청만 존재)

친구 관계는 양방향이다. **친구가 가장 많은 사람**의 `id`와 친구 수 `num`을 구한다(정답은 유일하다고 가정).

### 정답

```sql
SELECT id, COUNT(*) AS num
FROM (
    SELECT requester_id AS id FROM RequestAccepted
    UNION ALL
    SELECT accepter_id AS id FROM RequestAccepted
) t
GROUP BY id
ORDER BY num DESC
LIMIT 1;
```

### 풀이 — 왜 이렇게 하는가

1. 수락된 요청 한 건은 **양쪽 모두에게 친구 1명**을 의미한다. 즉 `requester_id`도 친구가 한 명 늘고, `accepter_id`도 한 명 는다.
2. 두 컬럼을 각각 `id`라는 이름으로 뽑아 `UNION ALL`로 세로로 쌓으면, 각 등장 횟수가 곧 그 사람의 친구 수가 된다.
3. `UNION`이 아니라 **`UNION ALL`**을 써야 중복 제거 없이 모든 관계가 카운트된다.
4. `GROUP BY id`로 사람별 집계 후 `ORDER BY num DESC LIMIT 1`로 최다 친구 보유자를 뽑는다.

### 핵심 개념

- **UNION ALL로 컬럼 언피벗**: 두 방향 관계를 한 열로 접어 카운트.
- **UNION vs UNION ALL**: `UNION`은 중복 행을 제거하므로 카운트가 틀어진다. 반드시 `UNION ALL`.

### ⚠️ 흔한 실수

- `UNION`을 쓰면 (A가 여러 명과 친구여도) 중복 제거로 수가 줄어 오답.
- 한쪽 컬럼만 세면 요청을 보내기만/받기만 한 관계가 누락된다.

---

## 9. [608] Tree Node

**링크**: https://leetcode.cn/problems/tree-node/  
**학습 포인트**: `CASE` + `EXISTS`로 노드 종류(Root/Inner/Leaf) 분류.

### 문제

- `Tree(id, p_id)` — `p_id`는 부모 노드의 id(루트는 NULL)

각 노드를 다음으로 분류한다.
- **Root**: `p_id`가 NULL(부모 없음)
- **Inner**: 부모도 있고 자식도 있음
- **Leaf**: 부모는 있으나 자식이 없음

### 정답

```sql
SELECT
    id,
    CASE
        WHEN p_id IS NULL THEN 'Root'
        WHEN id IN (SELECT p_id FROM Tree WHERE p_id IS NOT NULL) THEN 'Inner'
        ELSE 'Leaf'
    END AS type
FROM Tree;
```

### 풀이 — 왜 이렇게 하는가

1. 판단 기준은 두 가지: "부모가 있는가"(`p_id` NULL 여부)와 "자식이 있는가"(누군가의 `p_id`로 등장하는가).
2. `CASE`를 위에서부터 검사한다.
   - 먼저 `p_id IS NULL`이면 **Root**로 확정.
   - 그다음 자기 `id`가 다른 행의 `p_id`로 등장하면(= 자식을 가짐) **Inner**.
   - 둘 다 아니면(부모는 있고 자식은 없음) **Leaf**.
3. "자식이 있는가"는 `id IN (SELECT p_id FROM Tree)`로 판정한다. 부모 목록에 자기 id가 들어 있으면 자식이 있다는 뜻.

### 핵심 개념

- **CASE 순서 논리**: Root → Inner → Leaf 순으로 검사해 조건을 배타적으로 만든다.
- **부모 목록 조회로 자식 유무 판정**: `p_id` 집합에 자기 `id`가 있으면 자식 보유.

### ⚠️ 흔한 실수

- `id IN (SELECT p_id FROM Tree)`에서 서브쿼리에 NULL(`p_id` NULL)이 섞여도 `IN`은 "일치"만 보므로 참/거짓 판정 자체는 문제없다. 다만 안전을 위해 `WHERE p_id IS NOT NULL`을 붙이면 의미가 명확하다.
- 트리에 노드가 하나뿐이면 그 노드는 Root로만 분류되어야 한다. `p_id IS NULL`을 가장 먼저 검사하므로 올바르게 처리된다.

### 💡 대안 / 응용

- `EXISTS` 버전:
  ```sql
  ... WHEN EXISTS (SELECT 1 FROM Tree c WHERE c.p_id = t.id) THEN 'Inner' ...
  ```
  (본 테이블 별칭을 `t`로 두어 상관 조건 사용)

---

## 10. [601] Human Traffic of Stadium

**링크**: https://leetcode.cn/problems/human-traffic-of-stadium/  
**학습 포인트**: "3일 연속 조건" — 셀프 조인 세 벌로 연속 구간 탐지.

### 문제

- `Stadium(id, visit_date, people)` — `id`는 자동 증가(연속이라 보장되진 않지만 이 문제에선 날짜와 함께 증가)

`people >= 100`인 날이 **연속으로 3일 이상** 이어진 모든 행을 `id` 순으로 출력한다. `id`가 연속(간격 1)이면 날짜가 연속이라고 본다.

### 정답

```sql
SELECT DISTINCT t1.*
FROM Stadium t1, Stadium t2, Stadium t3
WHERE t1.people >= 100
  AND t2.people >= 100
  AND t3.people >= 100
  AND (
        (t1.id + 1 = t2.id AND t2.id + 1 = t3.id)  -- t1 t2 t3
     OR (t2.id + 1 = t1.id AND t1.id + 1 = t3.id)  -- t2 t1 t3
     OR (t3.id + 1 = t2.id AND t2.id + 1 = t1.id)  -- t3 t2 t1
  )
ORDER BY t1.id;
```

### 풀이 — 왜 이렇게 하는가

이 문제는 어렵다. 핵심은 "연속 3일"을 **세 행의 id가 1씩 차이 나는 조합**으로 표현하는 것이다. 천천히 쌓아 보자.

1. 우선 조건 하나는 명확하다: 후보가 되려면 세 행 모두 `people >= 100`이어야 한다.
2. "연속 3일"이란 id가 `n-1, n, n+1`처럼 이어진 세 행이 존재하고, 그 셋이 모두 100명 이상이라는 뜻이다.
3. 그런데 우리가 출력해야 하는 것은 "그런 3연속 블록에 속한 모든 행"이다. 어떤 행 `t1`이 3연속 블록에 속하는 경우는 그 행이 블록에서 **어느 위치**에 있느냐에 따라 세 가지다.
   - `t1`이 블록의 **첫째**: `t1, t1+1, t1+2`가 모두 조건 만족 → `t1.id+1=t2.id AND t2.id+1=t3.id`
   - `t1`이 블록의 **가운데**: `t1-1, t1, t1+1` → `t2.id+1=t1.id AND t1.id+1=t3.id`
   - `t1`이 블록의 **셋째**: `t1-2, t1-1, t1` → `t3.id+1=t2.id AND t2.id+1=t1.id`
4. 세 경우 중 하나라도 성립하면 `t1`은 3연속 블록의 구성원이다. 그래서 `OR`로 묶는다.
5. 한 행이 여러 조합(여러 블록 위치)으로 중복 매칭될 수 있으므로 **`DISTINCT`**로 중복을 제거한다.
6. 마지막으로 `ORDER BY t1.id`.

### 핵심 개념

- **셀프 조인 3벌로 연속 탐지**: 같은 테이블 세 별칭으로 id 인접 관계를 표현.
- **위치 케이스 분기(첫/중/끝)**: 출력 대상이 블록의 어느 위치에 오든 잡아내기 위해 세 패턴을 OR.
- **DISTINCT 필수**: 한 행이 여러 조합으로 매칭되므로 중복 제거.

### ⚠️ 흔한 실수

- 세 패턴 중 하나만 쓰면(예: `t1`이 항상 첫째라고 가정) 블록의 가운데·끝 행이 누락된다.
- `people >= 100` 조건을 `t1`에만 걸고 `t2, t3`에 빠뜨리면 100 미만인 이웃이 섞여 오답.
- `id`가 실제로 연속이 아닐 수 있다는 점(결번). 이 문제 데이터에선 `id`가 날짜와 함께 1씩 증가한다고 가정하지만, 일반적으로는 날짜 기준 연속 판정을 별도로 고려해야 한다.

### 💡 대안 / 응용

- 윈도우 함수로 `people >= 100`인 행에 순번을 매기고 `id - ROW_NUMBER()`가 같은 그룹(gaps-and-islands)을 찾아 `COUNT(*) >= 3`인 그룹만 남기는 방법이 더 확장성 있다(STEP 8).

---

## 11. [180] Consecutive Numbers

**링크**: https://leetcode.cn/problems/consecutive-numbers/  
**학습 포인트**: 셀프 조인으로 "연속된 3개 행이 같은 값"인지 탐지.

### 문제

- `Logs(id, num)` — `id`는 자동 증가(연속)

**같은 숫자가 적어도 3번 연속** 등장한 `num`을 중복 없이 구한다.

### 정답

```sql
SELECT DISTINCT l1.num AS ConsecutiveNums
FROM Logs l1
JOIN Logs l2 ON l1.id = l2.id - 1
JOIN Logs l3 ON l2.id = l3.id - 1
WHERE l1.num = l2.num
  AND l2.num = l3.num;
```

### 풀이 — 왜 이렇게 하는가

1. "3번 연속"은 id가 `n, n+1, n+2`인 세 행을 뜻한다. 셀프 조인으로 이 세 행을 한 줄에 나란히 붙인다.
2. `l1.id = l2.id - 1`은 `l2.id = l1.id + 1`, 즉 `l2`가 `l1` 바로 다음. `l2.id = l3.id - 1`도 마찬가지로 `l3`이 그다음.
3. 세 행의 `num`이 모두 같으면(`l1.num = l2.num = l3.num`) 그 숫자가 3연속 등장한 것.
4. 여러 위치에서 3연속이 나올 수 있고 같은 num이 여러 번 잡히므로 `DISTINCT`.

### 핵심 개념

- **id 인접 셀프 조인**: `id = id ± 1`로 연속 행 결합.
- **연속 등장 판정**: 인접 세 행의 값 일치 확인.

### ⚠️ 흔한 실수

- `l1.id = l2.id - 1`의 방향(부호)을 헷갈리면 엉뚱한 이웃을 붙인다. `l2.id - 1 = l1.id` → `l2`가 뒤 행임을 명확히.
- `id`가 연속이 아닐 수 있는 데이터라면 이 방식이 깨진다. 이 문제는 `id` 연속을 가정.

### 💡 대안 / 응용

- 윈도우 함수 `LEAD(num, 1)`, `LEAD(num, 2)`로 뒤 두 행을 당겨와 세 값이 같은지 비교하는 방식도 간결하다(STEP 8).

---

## 12. [577] Employee Bonus

**링크**: https://leetcode.cn/problems/employee-bonus/  
**학습 포인트**: `LEFT JOIN` 후 NULL과 값 조건을 함께 다루는 함정.

### 문제

- `Employee(empId, name, supervisor, salary)`
- `Bonus(empId, bonus)` — 모든 직원이 보너스 행을 갖지는 않음

**보너스가 1000 미만인** 직원의 `name`과 `bonus`를 구한다. 보너스 기록이 아예 없는(NULL) 직원도 포함한다.

### 정답

```sql
SELECT e.name, b.bonus
FROM Employee e
LEFT JOIN Bonus b ON e.empId = b.empId
WHERE b.bonus < 1000 OR b.bonus IS NULL;
```

### 풀이 — 왜 이렇게 하는가

1. 모든 직원을 유지해야 하고 보너스가 없는 직원도 결과에 넣어야 하므로 `LEFT JOIN`.
2. 보너스가 없는 직원은 `b.bonus`가 NULL이 된다.
3. 여기서 함정: SQL에서 `NULL < 1000`은 참이 아니라 **UNKNOWN**이다. 따라서 `WHERE b.bonus < 1000`만 쓰면 보너스 없는 직원이 **탈락**한다.
4. 그래서 `OR b.bonus IS NULL`을 명시해 NULL 직원을 명시적으로 포함시킨다.

### 핵심 개념

- **NULL 비교의 3값 논리**: `NULL < 1000` = UNKNOWN(참 아님) → WHERE에서 제외됨.
- **LEFT JOIN + IS NULL 조건 결합**: "값 조건 OR NULL"로 미매칭 행까지 포함.

### ⚠️ 흔한 실수

- `WHERE b.bonus < 1000`만 작성 → 보너스 없는 직원 누락(가장 흔한 오답).
- `COALESCE(b.bonus, 0) < 1000`처럼 NULL을 0으로 치환해 비교하는 것도 가능한 대안이지만, 출력 `bonus`는 여전히 NULL로 나와야 함에 유의.

### 💡 대안 / 응용

- `WHERE COALESCE(b.bonus, 0) < 1000` — NULL을 0으로 간주해 한 조건으로 처리.

---

## 13. [585] Investments in 2016

**링크**: https://leetcode.cn/problems/investments-in-2016/  
**학습 포인트**: 두 개의 서브쿼리 조건 — 값 중복 필터와 좌표 유일 필터.

### 문제

- `Insurance(pid, tiv_2015, tiv_2016, lat, lon)` — `pid`가 기본키

다음 **두 조건을 모두** 만족하는 폴리시들의 `tiv_2016` 합을 소수 둘째 자리로 구한다.
1. `tiv_2015` 값이 **다른 폴리시와 겹친다**(같은 `tiv_2015`가 둘 이상).
2. `(lat, lon)` 좌표가 **유일하다**(다른 어떤 폴리시와도 위치가 같지 않음).

### 정답

```sql
SELECT ROUND(SUM(tiv_2016), 2) AS tiv_2016
FROM Insurance
WHERE tiv_2015 IN (
        SELECT tiv_2015
        FROM Insurance
        GROUP BY tiv_2015
        HAVING COUNT(*) > 1
    )
  AND (lat, lon) IN (
        SELECT lat, lon
        FROM Insurance
        GROUP BY lat, lon
        HAVING COUNT(*) = 1
    );
```

### 풀이 — 왜 이렇게 하는가

1. 조건 1(`tiv_2015` 중복): `tiv_2015`로 묶어 `COUNT(*) > 1`인 값들의 집합을 구하고, 본문에서 그 값에 해당하는 폴리시만 남긴다.
2. 조건 2(`(lat, lon)` 유일): 좌표로 묶어 `COUNT(*) = 1`인 좌표들의 집합을 구하고, 튜플 `(lat, lon) IN (...)`으로 매칭. 유일한 위치의 폴리시만 통과.
3. 두 조건을 `AND`로 결합한 뒤 `tiv_2016`을 합산하고 `ROUND(..., 2)`.

### 핵심 개념

- **HAVING으로 그룹 필터**: `COUNT(*) > 1`(중복), `COUNT(*) = 1`(유일).
- **튜플 IN으로 좌표 매칭**: `(lat, lon)`을 한 쌍으로 비교.

### ⚠️ 흔한 실수

- 좌표 유일 조건을 `lat IN (...) AND lon IN (...)`처럼 컬럼별로 나눠 쓰면 서로 다른 폴리시의 lat/lon이 교차 매칭되어 오답. 반드시 `(lat, lon)` 튜플로 함께 비교.
- `ROUND`를 빼면 형식이 어긋난다.

---

## 14. [1132] Reported Posts II

**링크**: https://leetcode.cn/problems/reported-posts-ii/  
**학습 포인트**: 날짜별 비율을 구한 뒤 평균 — `DISTINCT`와 계산 순서 주의.

### 문제

- `Actions(user_id, post_id, action_date, action, extra)` — `action`은 'view', 'like', 'reaction', 'report', 'comment' 등
- `Removals(post_id, remove_date)` — 스팸으로 제거된 게시글

`extra = 'spam'`으로 신고된 게시글 중 **실제 제거된(즉 `Removals`에 있는) 비율**을 **날짜별로** 구한 다음, 그 일별 비율들의 **평균**을 백분율(소수 둘째 자리)로 낸다.

### 정답

```sql
SELECT ROUND(AVG(daily_percent), 2) AS average_daily_percent
FROM (
    SELECT
        a.action_date,
        COUNT(DISTINCT r.post_id) * 100.0
            / COUNT(DISTINCT a.post_id) AS daily_percent
    FROM Actions a
    LEFT JOIN Removals r ON a.post_id = r.post_id
    WHERE a.extra = 'spam' AND a.action = 'report'
    GROUP BY a.action_date
) t;
```

### 풀이 — 왜 이렇게 하는가

1. 먼저 스팸 신고 행만 남긴다: `a.extra = 'spam' AND a.action = 'report'`.
2. 그날 신고된 게시글 중 제거된 것을 세기 위해 `Removals`를 `LEFT JOIN`. 제거된 게시글은 `r.post_id`가 채워지고, 제거 안 됐으면 NULL.
3. **날짜별 비율** 계산이 안쪽 서브쿼리다.
   - 분모: 그날 신고된 **서로 다른 게시글 수** = `COUNT(DISTINCT a.post_id)`.
   - 분자: 그중 제거된 **서로 다른 게시글 수** = `COUNT(DISTINCT r.post_id)`(NULL은 COUNT에서 제외됨).
   - 같은 게시글을 여러 사용자가 신고할 수 있으므로 `DISTINCT`가 필수.
4. `* 100.0`으로 실수 나눗셈을 유도해 백분율을 만든다.
5. 바깥에서 `AVG(daily_percent)` — **날짜별 비율의 평균**을 구하고 `ROUND(..., 2)`. 전체를 한꺼번에 나누는 게 아니라 "일별 비율의 평균"이라는 정의를 지켜야 한다.

### 핵심 개념

- **비율 후 평균(2단계 집계)**: 날짜별로 비율을 먼저 구하고 그것들을 평균. 전체 합산 비율과 결과가 다르다.
- **COUNT(DISTINCT ...)**: 중복 신고를 게시글 단위로 정규화.
- **COUNT은 NULL 무시**: `LEFT JOIN` 후 매칭 안 된 `r.post_id`(NULL)는 분자에서 자동 제외.

### ⚠️ 흔한 실수

- `DISTINCT`를 빼면 여러 명이 같은 글을 신고했을 때 분모·분자가 부풀려져 오답.
- 정수 나눗셈. `100 / count`처럼 정수끼리 나누면 소수가 잘린다. `100.0`을 곱해 실수 연산 유도.
- 전체 신고 대비 전체 제거로 한 번에 비율을 내면(=일별 평균이 아님) 정의와 다른 값이 나온다.

---

## 15. [1158] Market Analysis I

**링크**: https://leetcode.cn/problems/market-analysis-i/  
**학습 포인트**: `LEFT JOIN` + 조건부 집계로 특정 연도 건수 세기.

### 문제

- `Users(user_id, join_date, favorite_brand)`
- `Orders(order_id, order_date, item_id, buyer_id, seller_id)`
- `Items(item_id, item_brand)`

모든 사용자에 대해 `user_id`, `join_date`, 그리고 **2019년에 구매한 주문 건수**(`orders_in_2019`)를 구한다. 구매가 없으면 0.

### 정답

```sql
SELECT
    u.user_id AS buyer_id,
    u.join_date,
    COUNT(o.order_id) AS orders_in_2019
FROM Users u
LEFT JOIN Orders o
    ON u.user_id = o.buyer_id
   AND YEAR(o.order_date) = 2019
GROUP BY u.user_id, u.join_date;
```

### 풀이 — 왜 이렇게 하는가

1. 주문이 없거나 2019년 주문이 없는 사용자도 **0으로 출력**해야 하므로 `LEFT JOIN`으로 모든 사용자를 유지한다.
2. 핵심은 연도 조건을 **`ON` 절에 두는 것**이다. `AND YEAR(o.order_date) = 2019`를 조인 조건에 넣으면, 2019년 주문만 매칭되고 나머지는 붙지 않아 NULL이 된다.
3. `COUNT(o.order_id)`는 NULL을 세지 않으므로, 매칭된 2019년 주문이 없는 사용자는 자연스럽게 0이 된다.
4. `Items` 테이블은 이 문제에서 필요 없다.

### 핵심 개념

- **조인 조건 vs WHERE 조건(LEFT JOIN)**: 필터를 `ON`에 두면 왼쪽 행은 보존되고 매칭만 제한된다. `WHERE`에 두면 미매칭 행(NULL)이 걸러져 `LEFT JOIN`이 `INNER JOIN`처럼 변한다.
- **COUNT(컬럼)의 NULL 무시**: 조건부 카운트를 `LEFT JOIN`으로 구현.

### ⚠️ 흔한 실수

- `YEAR(o.order_date) = 2019`를 `WHERE`에 두면, 2019년 주문이 없는 사용자가 통째로 사라져 0 행이 누락된다. 반드시 `ON`에 둔다.
- `COUNT(*)`를 쓰면 미매칭 시에도 NULL 행 1개를 세어 1이 나온다. 반드시 `COUNT(o.order_id)`처럼 조인 대상 컬럼을 센다.

### 💡 대안 / 응용

- 조건부 합계로도 표현 가능:
  ```sql
  SUM(CASE WHEN YEAR(o.order_date) = 2019 THEN 1 ELSE 0 END)
  ```
  이때는 `Orders`를 일반 `LEFT JOIN`(연도 조건 없이)하고 SELECT에서 연도를 판별한다.

---
