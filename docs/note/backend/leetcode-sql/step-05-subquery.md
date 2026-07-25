# STEP 5 — 서브쿼리

> 스칼라 서브쿼리, 상관 서브쿼리, N번째 값 구하기. 같은 문제를 STEP 8(윈도우)에서 다시 풀어 비교한다.

[← 목록으로](index.md)

---

## 1. [176] Second Highest Salary

**링크**: https://leetcode.cn/problems/second-highest-salary/  
**학습 포인트**: 결과가 없을 때 `NULL`을 반드시 반환해야 하는 문제 — 스칼라 서브쿼리로 보장한다.

### 문제
- `Employee(id, salary)`

두 번째로 높은 급여(`SecondHighestSalary`)를 구한다. 두 번째로 높은 값이 없으면(직원이 1명이거나 급여가 모두 같으면) `NULL`을 반환해야 한다.

### 정답
```sql
SELECT MAX(salary) AS SecondHighestSalary
FROM Employee
WHERE salary < (SELECT MAX(salary) FROM Employee);
```

### 풀이 — 왜 이렇게 하는가
1. 서브쿼리 `(SELECT MAX(salary) FROM Employee)`가 최고 급여를 구한다.
2. 바깥 쿼리는 "최고 급여보다 낮은" 행들만 남긴 뒤 그중 `MAX`를 취한다 → 결과적으로 두 번째로 높은 급여다.
3. 핵심은 **집계 함수 `MAX`는 대상 행이 하나도 없어도 `NULL`을 반환한다**는 점이다. 직원이 1명뿐이면 `WHERE` 조건을 만족하는 행이 0개지만, `MAX`가 `NULL`을 내주므로 요구사항을 자동으로 만족한다.

### 핵심 개념
- 스칼라 서브쿼리: 값 하나(1행 1열)를 반환하여 다른 표현식처럼 쓴다.
- 집계 함수의 빈 결과: `GROUP BY` 없는 `MAX/MIN/AVG/SUM`은 행이 0개면 `NULL`을 반환한다(단, `COUNT`은 0).

### ⚠️ 흔한 실수
- `SELECT DISTINCT salary FROM Employee ORDER BY salary DESC LIMIT 1 OFFSET 1` 로 풀면, 두 번째 값이 없을 때 **결과가 0행**이 되어 `NULL`이 아니라 아예 빈 결과가 나온다 → 오답. 굳이 `LIMIT`로 풀려면 서브쿼리로 한 번 더 감싸야 한다: `SELECT (SELECT DISTINCT salary ... LIMIT 1 OFFSET 1) AS SecondHighestSalary;`
- 중복 급여를 고려하지 않고 `salary < MAX`가 아니라 `!=`만 쓰면 논리가 흐트러질 수 있다.

### 💡 대안 / 응용
- 윈도우 함수 풀이(`DENSE_RANK`)는 STEP 8에서 다룬다. 서브쿼리 방식과 결과·NULL 처리를 비교해 보라.
- `LIMIT` 감싸기 방식: `SELECT (SELECT DISTINCT salary FROM Employee ORDER BY salary DESC LIMIT 1,1) AS SecondHighestSalary;` — 스칼라 서브쿼리가 빈 결과일 때 `NULL`이 되는 성질을 이용한다.

---

## 2. [177] Nth Highest Salary

**링크**: https://leetcode.cn/problems/nth-highest-salary/  
**학습 포인트**: 저장 함수(`CREATE FUNCTION`) 안에서 `LIMIT`에 변수를 직접 넣을 수 없다는 제약을 우회한다.

### 문제
- `Employee(id, salary)`

N번째로 높은 **서로 다른** 급여를 반환하는 함수 `getNthHighestSalary(N)`를 작성한다. 해당 순위가 없으면 `NULL`을 반환한다.

### 정답
```sql
CREATE FUNCTION getNthHighestSalary(N INT) RETURNS INT
BEGIN
  SET N = N - 1;
  RETURN (
    SELECT DISTINCT salary
    FROM Employee
    ORDER BY salary DESC
    LIMIT N, 1
  );
END
```

### 풀이 — 왜 이렇게 하는가
1. N번째로 높은 값 = 정렬 후 `OFFSET (N-1)` 위치의 1개 행이다. `LIMIT N, 1`은 `LIMIT offset, count` 형식이므로 `offset = N-1`.
2. MySQL에서 `LIMIT`은 리터럴 정수 또는 (특정 버전에서) 준비된 변수만 허용하고, `LIMIT N-1, 1` 처럼 **표현식**은 문법 오류다. 그래서 함수 시작에서 `SET N = N - 1;`로 변수 자체를 미리 감소시켜 두고, `LIMIT N, 1`에는 순수 변수만 넣는다.
3. `DISTINCT`로 중복 급여를 하나로 묶어 "서로 다른 N번째"를 만족시킨다.
4. 해당 순위가 없으면 서브쿼리가 0행 → 스칼라 서브쿼리이므로 `NULL` 반환(176번과 같은 원리).

### 핵심 개념
- `LIMIT offset, count` 문법과 표현식 불가 제약.
- 저장 함수 안의 `SET`으로 파라미터를 가공한 뒤 `LIMIT`에 사용.
- `RETURN (SELECT ...)`: 서브쿼리 결과를 함수 반환값으로.

### ⚠️ 흔한 실수
- `LIMIT N-1, 1`로 바로 쓰면 문법 오류. 반드시 `SET`으로 분리한다.
- `DISTINCT` 누락 → 같은 급여가 여러 명일 때 순위가 밀려 오답.
- N이 0 이하로 들어오는 예외를 문제는 요구하지 않지만, 실무라면 방어 로직을 고려한다.

### 💡 대안 / 응용
- 윈도우 함수로 `DENSE_RANK() = N` 을 걸러 함수 없이도 표현할 수 있다(STEP 8 참고).
- N=2로 호출하면 176번과 동일한 결과가 된다.

---

## 3. [178] Rank Scores

**링크**: https://leetcode.cn/problems/rank-scores/  
**학습 포인트**: 상관 서브쿼리로 `DENSE_RANK`를 손으로 흉내 낸다.

### 문제
- `Scores(id, score)`

점수를 내림차순으로 정렬하고 순위를 매긴다. 동점은 같은 순위, 순위는 연속(빈 순위 없음)이어야 한다 → 즉 `DENSE_RANK` 규칙. 출력: `score`, `rank`.

### 정답
```sql
SELECT
  s.score,
  (SELECT COUNT(DISTINCT s2.score)
   FROM Scores s2
   WHERE s2.score >= s.score) AS 'rank'
FROM Scores s
ORDER BY s.score DESC;
```

### 풀이 — 왜 이렇게 하는가
1. 어떤 행의 순위 = "그 점수보다 **크거나 같은** 서로 다른 점수의 개수"다.
2. 상관 서브쿼리 `s2.score >= s.score`가 바깥 행 `s`마다 다시 실행되면서, 자기보다 높거나 같은 **distinct 점수 수**를 센다.
3. `DISTINCT`가 핵심: 동점이 여러 명이어도 점수 종류로만 세므로 동점은 같은 순위를 받고, 순위는 1,2,3처럼 빈 곳 없이 이어진다(= dense rank).
4. 최종적으로 점수 내림차순 정렬.

### 핵심 개념
- 상관 서브쿼리(correlated subquery): 바깥 쿼리의 각 행 값을 참조하여 행마다 재평가된다.
- `COUNT(DISTINCT ...)`로 "몇 종류가 더 크거나 같은가"를 세면 dense rank가 된다.
- `rank`는 MySQL 8 예약어이므로 백틱으로 감싼다(``` `rank` ```).

### ⚠️ 흔한 실수
- `>` 로 세고 `+1`을 하지 않으면 순위가 0부터 시작한다. `>=`로 세면 자기 자신 포함이라 `+1` 없이 맞다(둘 중 하나로 일관되게).
- `DISTINCT`를 빼면 `RANK`(동점 뒤 순위 건너뜀)처럼 되어 오답.
- 별칭 `rank`를 백틱 없이 쓰면 문법 오류.

### 💡 대안 / 응용
- 표준 해법은 윈도우 함수 `DENSE_RANK() OVER (ORDER BY score DESC)` 이며 STEP 8에서 다룬다. 상관 서브쿼리 버전은 O(n²)이라 대량 데이터에서 느리다는 점을 대비해 보라.

---

## 4. [262] Trips and Users

**링크**: https://leetcode.cn/problems/trips-and-users/  
**학습 포인트**: 다중 조건 필터 + 조건부 집계로 취소율(cancellation rate)을 계산한다.

### 문제
- `Trips(id, client_id, driver_id, city_id, status, request_at)` — `status`는 `completed / cancelled_by_driver / cancelled_by_client`
- `Users(users_id, banned, role)` — `banned`는 `Yes/No`

`2013-10-01` ~ `2013-10-03` 기간에, **금지되지 않은(banned='No') 사용자**가 client이면서 동시에 driver도 금지되지 않은 주문만 대상으로, 날짜별 취소율을 구한다. 취소율 = 취소된 주문 수 / 전체 주문 수, 소수점 둘째 자리 반올림.

### 정답
```sql
SELECT
  t.request_at AS 'Day',
  ROUND(
    AVG(t.status LIKE 'cancelled%'),
    2
  ) AS 'Cancellation Rate'
FROM Trips t
JOIN Users c ON t.client_id = c.users_id AND c.banned = 'No'
JOIN Users d ON t.driver_id = d.users_id AND d.banned = 'No'
WHERE t.request_at BETWEEN '2013-10-01' AND '2013-10-03'
GROUP BY t.request_at;
```

### 풀이 — 왜 이렇게 하는가
1. **비금지 필터**: 취소율은 client와 driver **둘 다** banned='No'인 주문만 센다. `Users`를 두 번 조인한다 — 한 번은 client용(`c`), 한 번은 driver용(`d`). 각 조인 `ON`절에 `banned='No'`를 붙이면 금지 사용자가 낀 주문은 조인에서 탈락한다.
2. **날짜 필터**: `request_at BETWEEN '2013-10-01' AND '2013-10-03'`. `request_at`이 DATE 타입이라 문자열 비교가 안전하다.
3. **취소율 계산**: `t.status LIKE 'cancelled%'`는 취소면 1, 아니면 0(불리언 → 0/1)을 준다. `AVG(0/1)`은 곧 "취소 비율"이다. `SUM(...)/COUNT(*)`와 같지만 더 간결하다.
4. `ROUND(..., 2)`로 소수 둘째 자리 반올림, 날짜별 `GROUP BY`.

### 핵심 개념
- 자기 조인 아닌 **동일 테이블 다중 조인**: 같은 `Users`를 역할별로 별칭을 달아 두 번 조인.
- 조건부 집계: `AVG(boolean)` = 비율. MySQL은 불리언을 1/0으로 취급한다.
- 필터를 `ON`에 둘지 `WHERE`에 둘지 — INNER JOIN에서는 결과가 같지만, 의미를 명확히 하려 banned 조건을 `ON`에 둔다.

### ⚠️ 흔한 실수
- client의 banned만 검사하고 driver의 banned를 빠뜨리는 것 → 문제는 둘 다 요구한다.
- `LIKE 'cancelled%'` 대신 `= 'cancelled_by_driver'`만 세면 client 취소가 누락된다.
- 취소 건이 0인 날에도 그 날짜가 결과에 나와야 한다 — `AVG(...)`가 0을 정상 계산하므로 이 쿼리는 문제없다.

### 💡 대안 / 응용
- `AVG(t.status != 'completed')` 로도 같은 결과(취소=완료 아님)를 낼 수 있다.
- 필터가 복잡할 때는 먼저 "대상 주문 집합"을 CTE로 뽑고, 그 위에서 집계하면 가독성이 좋아진다(STEP 7 CTE 참고).

---

## 5. [1045] Customers Who Bought All Products

**링크**: https://leetcode.cn/problems/customers-who-bought-all-products/  
**학습 포인트**: 관계 분할(relational division) — "모든 X를 만족하는" 조건을 `HAVING COUNT`로 표현한다.

### 문제
- `Customer(customer_id, product_key)` — 고객이 구매한 상품(중복 가능)
- `Product(product_key)` — 전체 상품 목록

**모든** 상품을 하나도 빠짐없이 구매한 고객의 `customer_id`를 구한다.

### 정답
```sql
SELECT customer_id
FROM Customer
GROUP BY customer_id
HAVING COUNT(DISTINCT product_key) = (SELECT COUNT(*) FROM Product);
```

### 풀이 — 왜 이렇게 하는가
1. "모든 상품을 샀다" = "그 고객이 산 서로 다른 상품 수 = 전체 상품 수".
2. 고객별로 묶은 뒤 `COUNT(DISTINCT product_key)`로 그 고객이 산 상품 **종류 수**를 센다. `DISTINCT`는 같은 상품 중복 구매를 한 번으로 처리하기 위함이다.
3. 스칼라 서브쿼리 `(SELECT COUNT(*) FROM Product)`는 전체 상품 수(예: 2). 이 둘이 같으면 모든 상품을 산 것이다.
4. `HAVING`은 그룹 집계 결과에 대한 필터이므로 여기에 조건을 건다.

### 핵심 개념
- 관계 분할: "X의 모든 원소를 포함" 문제를 `GROUP BY ... HAVING COUNT = 전체수`로 변환.
- `WHERE`(행 필터) vs `HAVING`(그룹 필터)의 구분.
- `COUNT(DISTINCT ...)`로 중복 제거 개수.

### ⚠️ 흔한 실수
- `COUNT(product_key)`(DISTINCT 없이)를 쓰면 같은 상품을 여러 번 산 고객이 잘못 통과할 수 있다. Product에 실제로 중복이 없더라도 Customer 쪽 중복이 문제다.
- 상품 수를 하드코딩(예: `= 2`)하면 데이터가 바뀌면 깨진다 — 서브쿼리로 동적으로 센다.

### 💡 대안 / 응용
- `NOT EXISTS` 이중 부정으로도 표현 가능: "그 고객이 사지 않은 상품이 존재하지 않는다"(STEP 6 EXISTS에서 다룸).
- Product에 중복이 있을 수 있다면 서브쿼리도 `COUNT(DISTINCT product_key)`로 바꾼다.

---

## 6. [1164] Product Price at a Given Date

**링크**: https://leetcode.cn/problems/product-price-at-a-given-date/  
**학습 포인트**: "특정 시점의 최신 값" 조회 + 기록이 없는 대상의 기본값 처리(UNION).

### 문제
- `Products(product_id, new_price, change_date)` — 가격 변경 이력

`2019-08-16` 시점의 각 상품 가격을 구한다. 그 날짜(포함) 이전의 가장 최근 변경 가격을 쓰고, 해당 날짜까지 한 번도 변경된 적 없는 상품은 기본 가격 `10`으로 본다.

### 정답
```sql
SELECT product_id, new_price AS price
FROM Products
WHERE (product_id, change_date) IN (
  SELECT product_id, MAX(change_date)
  FROM Products
  WHERE change_date <= '2019-08-16'
  GROUP BY product_id
)

UNION

SELECT product_id, 10 AS price
FROM Products
WHERE product_id NOT IN (
  SELECT product_id
  FROM Products
  WHERE change_date <= '2019-08-16'
);
```

### 풀이 — 왜 이렇게 하는가
1. **본체(첫 SELECT)**: 각 상품별로 `2019-08-16` 이하 변경 중 가장 늦은 날짜(`MAX(change_date)`)를 구하고, `(product_id, MAX(change_date))` 쌍과 일치하는 행의 `new_price`를 가져온다. 튜플 `IN` 비교로 "그 상품의 그 최신 날짜 행"을 정확히 집는다.
2. **UNION(둘째 SELECT)**: 기준일 이전에 변경 기록이 **전혀 없는** 상품은 첫 SELECT에서 누락된다. 이들은 `NOT IN`으로 골라 기본값 `10`을 부여한다.
3. 두 결과를 `UNION`으로 합치면 모든 상품이 한 번씩 나온다.

### 핵심 개념
- 상관/그룹 서브쿼리로 "시점 이하 최신 날짜" 찾기: `MAX(change_date) WHERE change_date <= 기준일`.
- 튜플(row) `IN` 비교: `(a, b) IN (SELECT a, b ...)`.
- 기록 없는 대상의 기본값을 `UNION`으로 보강.

### ⚠️ 흔한 실수
- 기준일 이후 변경까지 포함해 `MAX`를 구하면 미래 가격을 반영하게 된다 — 반드시 `change_date <= '2019-08-16'`로 제한.
- 변경 이력이 없는 상품(기본 10)을 빼먹는 것. UNION 분기를 잊기 쉽다.
- `<=` 대신 `<`를 쓰면 기준일 당일에 변경된 가격을 놓친다.

### 💡 대안 / 응용
- `UNION` 대신 조건부 집계로 한 번에: 각 상품의 기준일 이하 마지막 가격을 서브쿼리로 뽑고 `COALESCE(..., 10)`을 씌우는 방식.
- 윈도우 함수 `ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY change_date DESC)`로 최신 1건을 뽑는 풀이는 STEP 8에서 다룬다.

---

## 7. [1204] Last Person to Fit in the Bus

**링크**: https://leetcode.cn/problems/last-person-to-fit-in-the-bus/  
**학습 포인트**: 상관 서브쿼리로 **누적합(running total)**을 계산한다.

### 문제
- `Queue(person_id, person_name, weight, turn)` — `turn`은 탑승 순서

`turn` 순서대로 탑승할 때, 총 무게가 `1000`을 넘지 않는 선에서 버스에 탈 수 있는 **마지막 사람**의 이름을 구한다.

### 정답
```sql
SELECT q1.person_name
FROM Queue q1
WHERE (
  SELECT SUM(q2.weight)
  FROM Queue q2
  WHERE q2.turn <= q1.turn
) <= 1000
ORDER BY q1.turn DESC
LIMIT 1;
```

### 풀이 — 왜 이렇게 하는가
1. 각 사람에 대해 "자기 순서까지의 누적 무게"를 알아야 한다. 상관 서브쿼리 `SUM(q2.weight) WHERE q2.turn <= q1.turn`이 바깥 행 `q1`마다 그 사람까지의 누적합을 계산한다.
2. 누적합이 `1000` 이하인 사람들만 실제로 탈 수 있다. `WHERE (누적합) <= 1000`으로 거른다.
3. 그중 가장 나중 사람이 답이므로 `turn` 내림차순 정렬 후 `LIMIT 1`.

### 핵심 개념
- 상관 서브쿼리 누적합: `SUM(...) WHERE 순서열 <= 바깥.순서열`.
- "조건을 만족하는 것 중 마지막" → `ORDER BY ... DESC LIMIT 1`.
- 누적이 1000을 넘긴 뒤에도 뒤쪽에 가벼운 사람이 있으면 못 탄다는 점(누적이 넘는 순간 이후는 전부 탈락).

### ⚠️ 흔한 실수
- 개별 무게가 아니라 **누적** 무게로 판단해야 한다. `q1.weight <= 1000`이 아니다.
- `q2.turn < q1.turn`(자기 제외)로 세면 자기 무게가 빠져 오답 — `<=`로 자기 포함.
- 한 번 1000을 넘겼다가 다시 1000 이하가 되는 경우는 없다(무게는 양수, 누적은 단조 증가). 그래도 논리는 "각자 자기까지 누적 ≤ 1000".

### 💡 대안 / 응용
- 윈도우 함수 `SUM(weight) OVER (ORDER BY turn)`로 누적합을 구하는 풀이가 STEP 8에서 다룬다 — 상관 서브쿼리 O(n²)보다 효율적이다.

---

## 8. [1321] Restaurant Growth

**링크**: https://leetcode.cn/problems/restaurant-growth/  
**학습 포인트**: 상관 서브쿼리로 **7일 이동 합계/이동 평균**(sliding window)을 계산한다.

### 문제
- `Customer(customer_id, name, visited_on, amount)` — 하루 여러 손님의 소비가 있을 수 있음(같은 날짜 여러 행)

각 날짜에 대해 **그 날짜를 포함한 최근 7일**의 총 소비(`amount`)와 평균(소수 둘째 자리)을 구한다. 앞쪽 6일은 7일치가 안 되므로 결과에서 제외한다. `visited_on` 오름차순.

> 주의: 같은 `visited_on`에 여러 행이 있을 수 있어 먼저 날짜별로 합산해야 한다.

### 정답
```sql
SELECT
  a.visited_on,
  (SELECT SUM(b.amount)
   FROM Customer b
   WHERE b.visited_on BETWEEN DATE_SUB(a.visited_on, INTERVAL 6 DAY) AND a.visited_on
  ) AS amount,
  ROUND(
    (SELECT SUM(b.amount)
     FROM Customer b
     WHERE b.visited_on BETWEEN DATE_SUB(a.visited_on, INTERVAL 6 DAY) AND a.visited_on
    ) / 7, 2
  ) AS average_amount
FROM (SELECT DISTINCT visited_on FROM Customer) a
WHERE a.visited_on >= (SELECT DATE_ADD(MIN(visited_on), INTERVAL 6 DAY) FROM Customer)
ORDER BY a.visited_on;
```

### 풀이 — 왜 이렇게 하는가
1. **날짜 중복 제거**: 같은 날짜에 여러 행이 있으므로 바깥 쿼리는 `SELECT DISTINCT visited_on`으로 날짜 하나당 한 행만 만든다.
2. **7일 이동 합계**: 각 날짜 `a.visited_on`에 대해, `DATE_SUB(a.visited_on, INTERVAL 6 DAY) ~ a.visited_on` 범위의 모든 `amount`를 `SUM`한다. 이 범위는 "당일 포함 최근 7일". 서브쿼리가 원본 `Customer`를 대상으로 하므로 같은 날짜의 여러 행이 자연스럽게 모두 합산된다.
3. **평균**: 7일 합을 `/ 7` 하고 `ROUND(..., 2)`. 손님 수가 아니라 **7일**로 나누는 점에 주의(문제 정의).
4. **앞 6일 제외**: 첫날부터 6일까지는 7일치가 안 된다. `WHERE a.visited_on >= (MIN(visited_on) + 6일)`로 7일째 이후만 남긴다.

### 핵심 개념
- 이동 윈도우 합계를 상관 서브쿼리 `BETWEEN 날짜-6 AND 날짜`로 흉내.
- `DATE_SUB / DATE_ADD ... INTERVAL n DAY`로 날짜 연산.
- 파생 테이블(`FROM (SELECT DISTINCT ...)`)로 날짜 축을 만든다.

### ⚠️ 흔한 실수
- 날짜 중복을 무시하고 원본을 그대로 바깥에 쓰면 같은 날짜가 여러 번 출력된다 → `DISTINCT` 필요.
- 평균을 `AVG(amount)`(행 수 기준)로 계산하면 오답 — 문제는 무조건 `합 / 7`이다.
- 앞 6일 제외를 안 하면 초반 날짜의 부분 합이 섞여 나온다.
- `INTERVAL 6 DAY`(당일 포함 7일)를 `7 DAY`로 잘못 쓰면 8일 범위가 된다.

### 💡 대안 / 응용
- 윈도우 함수 프레임 `SUM(amount) OVER (ORDER BY visited_on RANGE BETWEEN INTERVAL 6 DAY PRECEDING AND CURRENT ROW)` 같은 프레임 기반 풀이는 STEP 8/9에서 다룬다.
- 날짜별 선집계(먼저 날짜별 `SUM`을 CTE로 만든 뒤 이동합)를 하면 서브쿼리 부하를 줄일 수 있다.

---
