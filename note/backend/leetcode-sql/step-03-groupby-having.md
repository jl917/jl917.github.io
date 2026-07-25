# STEP 3 — GROUP BY & HAVING

> 집계의 핵심. WHERE(행 필터)와 HAVING(그룹 필터)의 차이, 조건부 집계(SUM(CASE))를 익힌다.

[← 목록으로](index.md)

---

## 1. [182] Duplicate Emails

**링크**: https://leetcode.cn/problems/duplicate-emails/  
**학습 포인트**: `GROUP BY`로 묶고 `HAVING COUNT(*) > 1`로 중복 그룹만 필터한다.

### 문제

`Person` 테이블

| 컬럼 | 타입 |
|------|------|
| id | int (PK) |
| email | varchar |

- `email`은 NULL이 아니며 모두 소문자다.
- 중복으로 나타나는 이메일을 모두 찾아라. (결과 순서 무관)

### 정답
```sql
SELECT email
FROM Person
GROUP BY email
HAVING COUNT(*) > 1;
```

### 풀이 — 왜 이렇게 하는가

1. "중복 이메일"이란 같은 값이 2번 이상 등장하는 이메일이다. 값이 같은 행끼리 묶어야 하니 `GROUP BY email`로 이메일별 그룹을 만든다.
2. 각 그룹의 행 개수는 `COUNT(*)`로 센다. 그룹의 크기가 곧 그 이메일의 등장 횟수다.
3. "2번 이상 등장" = `COUNT(*) > 1`. 이 조건은 **개별 행이 아니라 그룹 전체에 대한 조건**이므로 `WHERE`가 아니라 `HAVING`에 쓴다.

### 핵심 개념

- **GROUP BY**: 지정한 컬럼 값이 같은 행들을 하나의 그룹으로 묶어 그룹당 한 행을 출력한다.
- **HAVING**: 집계 결과(그룹 단위)에 조건을 건다. `WHERE`는 그룹화 이전 개별 행에, `HAVING`은 그룹화 이후에 적용된다.
- `COUNT(*)`는 그룹 내 행 수를 센다.

### ⚠️ 흔한 실수

- `WHERE COUNT(*) > 1`은 오류다. `WHERE`는 그룹화 전에 실행되므로 집계 함수를 쓸 수 없다.
- `SELECT email, COUNT(*)`처럼 개수를 함께 출력하면 요구 컬럼(`email`)과 달라져 오답이 될 수 있다.

### 💡 대안 / 응용

- 서브쿼리 방식: `SELECT DISTINCT a.email FROM Person a JOIN Person b ON a.email=b.email AND a.id<>b.id;` 하지만 `GROUP BY + HAVING`이 훨씬 간결하다.

---

## 2. [586] Customer Placing the Largest Number of Orders

**링크**: https://leetcode.cn/problems/customer-placing-the-largest-number-of-orders/  
**학습 포인트**: `GROUP BY` 후 `ORDER BY COUNT(*) DESC LIMIT 1`로 최대 그룹을 뽑는다.

### 문제

`Orders` 테이블

| 컬럼 | 타입 |
|------|------|
| order_number | int (PK) |
| customer_number | int |

- 가장 많은 주문을 한 고객의 `customer_number`를 구하라.
- 테스트 케이스는 단 한 명만 최다 주문을 한다고 보장한다.

### 정답
```sql
SELECT customer_number
FROM Orders
GROUP BY customer_number
ORDER BY COUNT(*) DESC
LIMIT 1;
```

### 풀이 — 왜 이렇게 하는가

1. 고객별 주문 수를 알아야 하니 `GROUP BY customer_number`로 고객 단위 그룹을 만든다.
2. 각 그룹의 주문 수는 `COUNT(*)`다.
3. "가장 많은" = 주문 수 내림차순으로 정렬(`ORDER BY COUNT(*) DESC`) 후 맨 위 한 건(`LIMIT 1`)을 취한다.
4. `SELECT` 절에 `COUNT(*)`를 안 써도 `ORDER BY`에서는 집계 함수를 사용할 수 있다.

### 핵심 개념

- `ORDER BY` 절에서도 집계 함수를 정렬 기준으로 쓸 수 있다.
- `LIMIT n`은 정렬된 결과에서 상위 n행만 반환한다.

### ⚠️ 흔한 실수

- **동점 처리**: 문제는 최다 주문 고객이 유일하다고 가정하지만, 실제로 동점이 있으면 `LIMIT 1`은 임의로 한 명만 남긴다. 동점을 모두 뽑으려면 `HAVING COUNT(*) = (SELECT MAX(cnt) ...)` 또는 윈도 함수 `RANK()`를 써야 한다.

### 💡 대안 / 응용

- 동점 대응 버전:
  ```sql
  SELECT customer_number
  FROM Orders
  GROUP BY customer_number
  HAVING COUNT(*) = (
    SELECT COUNT(*) FROM Orders
    GROUP BY customer_number
    ORDER BY COUNT(*) DESC LIMIT 1
  );
  ```

---

## 3. [1050] Actors and Directors Who Cooperated At Least Three Times

**링크**: https://leetcode.cn/problems/actors-and-directors-who-cooperated-at-least-three-times/  
**학습 포인트**: 여러 컬럼으로 그룹화(`GROUP BY a, b`)한 뒤 `HAVING`으로 그룹 크기를 거른다.

### 문제

`ActorDirector` 테이블

| 컬럼 | 타입 |
|------|------|
| actor_id | int |
| director_id | int |
| timestamp | int (PK) |

- 같은 배우-감독 조합으로 3번 이상 협업한 (actor_id, director_id) 쌍을 구하라.

### 정답
```sql
SELECT actor_id, director_id
FROM ActorDirector
GROUP BY actor_id, director_id
HAVING COUNT(*) >= 3;
```

### 풀이 — 왜 이렇게 하는가

1. "같은 배우-감독 조합"이 단위이므로 두 컬럼을 함께 묶는다: `GROUP BY actor_id, director_id`. 두 값이 모두 같아야 한 그룹이다.
2. 협업 횟수는 그룹 내 행 수(`COUNT(*)`)다. `timestamp`가 PK라 각 협업 기록이 별개 행으로 존재한다.
3. "3번 이상" = `HAVING COUNT(*) >= 3`.

### 핵심 개념

- **복합 GROUP BY**: 여러 컬럼을 나열하면 그 컬럼 값들의 조합마다 그룹이 생긴다.
- `HAVING`은 `>=`, `<`, 범위 조건 등 집계값 비교에 자유롭게 쓸 수 있다.

### ⚠️ 흔한 실수

- `GROUP BY actor_id`만 하면 감독이 뒤섞여 잘못 집계된다. 조합 단위 문제에서는 관련 컬럼을 모두 그룹키에 넣어야 한다.

---

## 4. [1729] Find Followers Count

**링크**: https://leetcode.cn/problems/find-followers-count/  
**학습 포인트**: 그룹별 `COUNT`를 컬럼으로 출력하고 `ORDER BY`로 정렬한다.

### 문제

`Followers` 테이블

| 컬럼 | 타입 |
|------|------|
| user_id | int |
| follower_id | int |

- (user_id, follower_id)가 PK다.
- 각 사용자의 팔로워 수를 구하고, `user_id` 오름차순으로 정렬하라.
- 출력: `user_id`, `followers_count`

### 정답
```sql
SELECT user_id, COUNT(follower_id) AS followers_count
FROM Followers
GROUP BY user_id
ORDER BY user_id;
```

### 풀이 — 왜 이렇게 하는가

1. 사용자별 팔로워 수가 목표이므로 `GROUP BY user_id`.
2. 팔로워 수는 그룹 내 `follower_id` 개수다. `(user_id, follower_id)`가 PK라 중복이 없으니 `COUNT(follower_id)`와 `COUNT(*)` 결과가 같다.
3. 요구된 대로 `ORDER BY user_id`로 오름차순 정렬한다.
4. 출력 컬럼명은 `AS followers_count`로 맞춘다.

### 핵심 개념

- 집계 결과 컬럼은 별칭(`AS`)으로 요구된 이름과 정확히 일치시켜야 한다.
- `COUNT(col)`은 그 컬럼이 NULL이 아닌 행만 센다. `COUNT(*)`는 NULL 포함 전체 행을 센다.

### ⚠️ 흔한 실수

- 정렬 요구를 빠뜨리기 쉽다. "order by" 지시가 있으면 반드시 `ORDER BY`를 넣는다.

---

## 5. [1211] Queries Quality and Percentage

**링크**: https://leetcode.cn/problems/queries-quality-and-percentage/  
**학습 포인트**: 비율 집계 `AVG(rating/position)`와 조건부 비율 `AVG(조건)*100`, `ROUND(x, 2)`.

### 문제

`Queries` 테이블

| 컬럼 | 타입 |
|------|------|
| query_name | varchar |
| result | varchar |
| position | int |
| rating | int |

- **quality** = 각 쿼리에서 `rating/position` 값들의 평균.
- **poor_query_percentage** = `rating < 3`인 쿼리의 비율(백분율).
- 두 값 모두 소수 2자리로 반올림. `query_name`별로 출력.

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

1. `query_name`별 지표이므로 `GROUP BY query_name`.
2. **quality**: 정의가 "행별로 `rating/position`을 계산한 값들의 평균"이다. 따라서 `AVG(rating / position)` — 나눗셈을 먼저 하고 그 결과를 평균낸다. (`AVG(rating)/AVG(position)`과는 다른 값이므로 순서가 중요하다.)
3. **poor_query_percentage**: 핵심은 MySQL에서 불리언 표현식 `rating < 3`이 참이면 1, 거짓이면 0을 반환한다는 점이다. 따라서 `AVG(rating < 3)`은 "1의 비율" = 조건을 만족하는 행의 비율(0~1)이 된다. 여기에 `*100`을 곱해 백분율로 만든다.
4. 두 값 모두 `ROUND(..., 2)`로 소수 2자리 반올림.

### 핵심 개념

- **조건부 비율 관용구**: `AVG(불리언 조건)`은 참인 행의 비율을 준다. `SUM(조건)/COUNT(*)`와 동일하다.
- `AVG(a/b)` ≠ `AVG(a)/AVG(b)`. 정의된 계산 순서를 그대로 옮겨야 한다.
- `ROUND(값, 자릿수)`로 반올림.

### ⚠️ 흔한 실수

- 백분율에서 `*100`을 빠뜨려 0~1 사이 값을 내는 실수.
- `SUM(CASE WHEN rating<3 THEN 1 ELSE 0 END)/COUNT(*)`처럼 정수 나눗셈을 쓰면 결과가 0으로 잘릴 수 있다. `AVG` 방식이 안전하다.

### 💡 대안 / 응용

- 명시적 CASE 버전:
  ```sql
  ROUND(SUM(CASE WHEN rating < 3 THEN 1 ELSE 0 END) / COUNT(*) * 100, 2)
  ```
  `AVG(rating < 3) * 100`이 더 짧지만 의미는 같다.

---

## 6. [1193] Monthly Transactions I

**링크**: https://leetcode.cn/problems/monthly-transactions-i/  
**학습 포인트**: `DATE_FORMAT`으로 월 추출 + `SUM(CASE ...)` 조건부 집계로 피벗(가로 집계).

### 문제

`Transactions` 테이블

| 컬럼 | 타입 |
|------|------|
| id | int (PK) |
| country | varchar |
| state | enum('approved','declined') |
| amount | int |
| trans_date | date |

- (월, 국가)별로 다음을 구하라:
  - `month` : `YYYY-MM` 형식
  - `trans_count` : 전체 거래 수
  - `approved_count` : 승인된 거래 수
  - `trans_total_amount` : 전체 거래 금액 합
  - `approved_total_amount` : 승인된 거래 금액 합

### 정답
```sql
SELECT
  DATE_FORMAT(trans_date, '%Y-%m') AS month,
  country,
  COUNT(*) AS trans_count,
  SUM(state = 'approved') AS approved_count,
  SUM(amount) AS trans_total_amount,
  SUM(CASE WHEN state = 'approved' THEN amount ELSE 0 END) AS approved_total_amount
FROM Transactions
GROUP BY month, country;
```

### 풀이 — 왜 이렇게 하는가

1. 집계 단위가 (월, 국가)이므로 월 문자열을 먼저 만든다: `DATE_FORMAT(trans_date, '%Y-%m')` → `'2019-01'` 형태.
2. 이 월 문자열과 `country`로 그룹화한다. MySQL은 `SELECT`의 별칭 `month`를 `GROUP BY`에서 참조할 수 있다.
3. **전체 집계**: `COUNT(*)`, `SUM(amount)`는 그룹 전체 대상.
4. **조건부 집계(피벗의 핵심)**: 승인 건만 세려면 승인일 때만 1을, 아닐 때 0을 만들어 더한다.
   - `SUM(state = 'approved')` : 불리언이 1/0이므로 승인 건수가 된다.
   - `SUM(CASE WHEN state = 'approved' THEN amount ELSE 0 END)` : 승인이면 금액을, 아니면 0을 더해 승인 금액 합만 골라낸다.
   - 이렇게 **조건을 집계 함수 안에 넣으면** 한 번의 `GROUP BY`로 전체·승인 지표를 한 행에 나란히(피벗) 배치할 수 있다.

### 핵심 개념

- **조건부 집계 = 필터를 집계 안으로**: `SUM(CASE WHEN 조건 THEN 값 ELSE 0 END)`는 "조건을 만족하는 행만" 합산하는 표준 관용구다.
- `WHERE state='approved'`로 걸러버리면 declined 행이 사라져 `trans_count`(전체)를 계산할 수 없다. 그래서 필터를 `WHERE`가 아닌 집계 함수 안에 둔다.
- `DATE_FORMAT(date, '%Y-%m')`으로 연-월 문자열을 만든다.

### ⚠️ 흔한 실수

- 승인 건만 `WHERE`로 거르면 전체 지표를 잃는다. 조건부 집계로 해결해야 한다.
- `CASE`에서 `ELSE 0`을 빼면 NULL이 되고, `SUM`은 NULL을 무시하므로 건수 집계엔 문제없지만 습관적으로 `ELSE 0`을 명시하는 게 안전하다.

### 💡 대안 / 응용

- `SUM(state = 'approved')` 대신 `COUNT(CASE WHEN state='approved' THEN 1 END)`도 승인 건수를 준다 (`COUNT`는 NULL을 세지 않음).

---

## 7. [1174] Immediate Food Delivery II

**링크**: https://leetcode.cn/problems/immediate-food-delivery-ii/  
**학습 포인트**: 서브쿼리로 고객별 "첫 주문"을 뽑고, 그중 즉시배달 비율을 `AVG(조건)`으로 계산.

### 문제

`Delivery` 테이블

| 컬럼 | 타입 |
|------|------|
| delivery_id | int (PK) |
| customer_id | int |
| order_date | date |
| customer_pref_delivery_date | date |

- 각 고객의 **첫 주문**(가장 이른 `order_date`)만 대상으로 한다.
- 첫 주문이 "즉시(immediate)"란 `order_date = customer_pref_delivery_date`인 경우.
- 첫 주문이 즉시배달인 고객의 비율(백분율, 소수 2자리)을 구하라.

### 정답
```sql
SELECT
  ROUND(AVG(order_date = customer_pref_delivery_date) * 100, 2) AS immediate_percentage
FROM Delivery
WHERE (customer_id, order_date) IN (
  SELECT customer_id, MIN(order_date)
  FROM Delivery
  GROUP BY customer_id
);
```

### 풀이 — 왜 이렇게 하는가

1. 먼저 "각 고객의 첫 주문"을 정의한다. 고객별 최소 주문일이 첫 주문이므로 서브쿼리에서 `GROUP BY customer_id`, `MIN(order_date)`로 (고객, 첫주문일) 쌍을 만든다.
2. 원본 테이블에서 그 (customer_id, order_date) 쌍에 해당하는 행만 남긴다: `WHERE (customer_id, order_date) IN (...)`. MySQL은 이런 **튜플(다중 컬럼) IN**을 지원한다.
3. 이제 남은 행은 각 고객의 첫 주문 한 건씩이다. 그중 즉시배달 비율은 `AVG(order_date = customer_pref_delivery_date)`. 불리언이 1/0이므로 평균이 곧 즉시배달 고객 비율(0~1)이다.
4. `*100`으로 백분율, `ROUND(..., 2)`로 2자리.

### 핵심 개념

- **튜플 IN**: `WHERE (a, b) IN (SELECT a, MIN(b) ...)`으로 그룹별 대표 행을 정확히 매칭한다.
- 첫 주문은 고객별 `MIN(order_date)`. 문제는 한 고객이 같은 날 두 번 첫 주문하는 경우가 없다고 가정한다.
- 분모가 "전체 고객 수"이고 분자가 "첫 주문이 즉시인 고객 수"인데, 첫 주문만 남긴 집합에서 `AVG(조건)`을 취하면 자동으로 이 비율이 된다.

### ⚠️ 흔한 실수

- 첫 주문으로 한정하지 않고 전체 행에서 비율을 내면 "Immediate Food Delivery I"의 답이 되어 이 문제(II)에선 오답이다.
- `MIN(order_date)`만 서브쿼리로 뽑아 `WHERE order_date IN (...)`처럼 쓰면 고객 매칭이 빠져 엉뚱한 행이 섞인다. 반드시 (customer_id, order_date) 쌍으로 매칭한다.

---

## 8. [550] Game Play Analysis IV

**링크**: https://leetcode.cn/problems/game-play-analysis-iv/  
**학습 포인트**: 고객별 첫 로그인일 + `DATE_ADD(첫날, 1)` 재접속 여부. 분모는 전체 플레이어 수.

### 문제

`Activity` 테이블

| 컬럼 | 타입 |
|------|------|
| player_id | int |
| device_id | int |
| event_date | date |
| games_played | int |

- (player_id, event_date)가 PK다.
- **각 플레이어의 첫 로그인 다음 날(첫 로그인일 + 1)에도 접속한 플레이어의 비율**을 구하라.
- 분모는 전체 플레이어 수. 결과는 소수 2자리.

### 정답
```sql
SELECT
  ROUND(
    COUNT(DISTINCT a.player_id) / (SELECT COUNT(DISTINCT player_id) FROM Activity),
    2
  ) AS fraction
FROM Activity a
WHERE (a.player_id, DATE_SUB(a.event_date, INTERVAL 1 DAY)) IN (
  SELECT player_id, MIN(event_date)
  FROM Activity
  GROUP BY player_id
);
```

### 풀이 — 왜 이렇게 하는가

1. 분모는 "전체 플레이어 수" = `(SELECT COUNT(DISTINCT player_id) FROM Activity)`. 스칼라 서브쿼리로 고정값을 만든다.
2. 분자는 "첫 로그인 다음 날에도 접속한 플레이어 수"다. 이를 구하려면 각 접속 행이 그 플레이어의 (첫 로그인 + 1)일인지 판별해야 한다.
3. 서브쿼리로 플레이어별 첫 로그인일을 구한다: `GROUP BY player_id`, `MIN(event_date)`.
4. 어떤 접속 행 `a`가 "첫날 다음날 접속"이려면, `a.event_date`에서 하루를 뺀 날(`DATE_SUB(a.event_date, INTERVAL 1 DAY)`)이 그 플레이어의 첫 로그인일과 같아야 한다. 그래서 `(player_id, event_date - 1) IN (player_id, MIN(event_date))`로 매칭한다.
   - 동등하게 첫 로그인일 쪽에 `+1`을 해도 된다: `DATE_ADD(MIN(event_date), 1)`. 여기서는 바깥 행 기준으로 `-1`을 적용했다.
5. 이 조건을 통과한 행의 `COUNT(DISTINCT a.player_id)`가 분자다.

### 핵심 개념

- **날짜 이동**: `DATE_ADD(d, INTERVAL 1 DAY)` / `DATE_SUB(d, INTERVAL 1 DAY)`로 연속일을 비교한다.
- **분모 주의**: 재접속 비율의 분모는 "전체 플레이어"이지 "첫날 접속자"가 아니다(모든 플레이어는 첫날 반드시 접속하므로 사실상 같지만, 분모를 명시적으로 전체 플레이어로 둔다).
- 튜플 IN으로 (플레이어, 날짜) 조합을 정확히 매칭한다.

### ⚠️ 흔한 실수

- `event_date = MIN(event_date) + 1`을 셀프 조인 없이 한 테이블에서 처리하려다 플레이어 매칭을 놓치는 실수. 반드시 player_id를 함께 매칭한다.
- 분자를 셀 때 `DISTINCT`를 빼면 같은 플레이어가 중복 계산될 수 있다(여기선 PK 때문에 문제없지만 습관적으로 `DISTINCT` 권장).

### 💡 대안 / 응용

- 셀프 조인 버전:
  ```sql
  SELECT ROUND(COUNT(b.player_id) / COUNT(a.player_id), 2) AS fraction
  FROM (SELECT player_id, MIN(event_date) AS first_login FROM Activity GROUP BY player_id) a
  LEFT JOIN Activity b
    ON a.player_id = b.player_id
   AND b.event_date = DATE_ADD(a.first_login, INTERVAL 1 DAY);
  ```

---

## 9. [1393] Capital Gain/Loss

**링크**: https://leetcode.cn/problems/capital-gain-loss/  
**학습 포인트**: `SUM(CASE WHEN operation='Buy' THEN -price ELSE price END)` — 부호를 조건부로 바꿔 합산.

### 문제

`Stocks` 테이블

| 컬럼 | 타입 |
|------|------|
| stock_name | varchar |
| operation | enum('Buy','Sell') |
| operation_day | int (PK와 함께) |
| price | int |

- (stock_name, operation_day)가 PK다.
- 각 주식의 **총 손익(capital gain/loss)**을 구하라.
- 손익 = (모든 매도 금액 합) − (모든 매수 금액 합).

### 정답
```sql
SELECT
  stock_name,
  SUM(CASE WHEN operation = 'Buy' THEN -price ELSE price END) AS capital_gain_loss
FROM Stocks
GROUP BY stock_name;
```

### 풀이 — 왜 이렇게 하는가

1. 주식별 손익이므로 `GROUP BY stock_name`.
2. 손익은 "매도는 더하고(+), 매수는 빼는(−)" 구조다. 이 부호 전환을 `CASE`로 표현한다:
   - `operation = 'Buy'` → `-price` (돈이 나감)
   - 그 외(=`'Sell'`) → `price` (돈이 들어옴)
3. 이 표현식을 `SUM`으로 그룹 내 전부 합치면 (매도 합 − 매수 합) = 순손익이 된다.
4. 하나의 `SUM`에 부호 있는 값을 넣는 것이 핵심 — 매수/매도를 따로 집계해 빼는 것보다 간결하다.

### 핵심 개념

- **부호 조건부 집계**: `SUM(CASE WHEN ... THEN -x ELSE x END)`으로 방향이 다른 값을 한 번에 합산한다.
- 이런 "가감 합산"은 순변동(잔액, 손익 등) 계산의 전형적 패턴이다.

### ⚠️ 흔한 실수

- 매수/매도를 각각 `WHERE`로 분리해 두 쿼리로 만든 뒤 빼려는 접근은 불필요하게 복잡하다. `CASE` 한 방으로 끝난다.

### 💡 대안 / 응용

- 분리 집계 버전:
  ```sql
  SUM(CASE WHEN operation='Sell' THEN price ELSE 0 END)
  - SUM(CASE WHEN operation='Buy' THEN price ELSE 0 END)
  ```
  결과는 같지만 부호 통합 버전이 더 짧다.

---

## 10. [1398] Customers Who Bought Products A and B but Not C

**링크**: https://leetcode.cn/problems/customers-who-bought-products-a-and-b-but-not-c/  
**학습 포인트**: `HAVING SUM(조건) > 0` (존재)와 `SUM(조건) = 0` (부재)로 그룹 조건을 조합한다.

### 문제

`Customers` 테이블

| 컬럼 | 타입 |
|------|------|
| customer_id | int (PK) |
| customer_name | varchar |

`Orders` 테이블

| 컬럼 | 타입 |
|------|------|
| order_id | int (PK) |
| customer_id | int |
| product_name | varchar |

- 제품 A와 B는 **샀지만** 제품 C는 **사지 않은** 고객을 구하라.
- 출력: `customer_id`, `customer_name` (customer_id 기준 정렬)

### 정답
```sql
SELECT c.customer_id, c.customer_name
FROM Customers c
JOIN Orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.customer_name
HAVING SUM(o.product_name = 'A') > 0
   AND SUM(o.product_name = 'B') > 0
   AND SUM(o.product_name = 'C') = 0
ORDER BY c.customer_id;
```

### 풀이 — 왜 이렇게 하는가

1. 조건이 "여러 주문에 걸친 고객 단위" 판정이므로 고객별로 그룹화한다: `GROUP BY c.customer_id, c.customer_name`.
2. 한 고객이 특정 제품을 샀는지는 **그 제품 주문이 하나라도 있는가**로 판단한다.
   - `SUM(o.product_name = 'A')`는 A인 행마다 1을 더하므로, A를 산 적이 있으면 값이 1 이상(`> 0`), 없으면 0이다.
   - 따라서 "A를 샀다" = `SUM(product_name='A') > 0`, "C를 안 샀다" = `SUM(product_name='C') = 0`.
3. 세 조건을 `AND`로 묶어 `HAVING`에 둔다. 개별 행이 아닌 그룹 전체 성질이므로 `HAVING`이 맞다.
4. `Orders`만으로도 풀리지만 이름 출력을 위해 `Customers`와 조인한다. `GROUP BY`에 `customer_name`도 포함해 `SELECT`와 정합성을 맞춘다.

### 핵심 개념

- **존재/부재 판정**: `SUM(조건) > 0`은 "그 조건을 만족하는 행이 하나라도 있다", `SUM(조건) = 0`은 "하나도 없다"를 뜻한다.
- `MAX(product_name = 'A') = 1`, `MIN(...)`으로도 존재 여부를 표현할 수 있다.
- `HAVING`은 여러 그룹 조건을 `AND`/`OR`로 조합할 수 있다.

### ⚠️ 흔한 실수

- `WHERE product_name IN ('A','B')`로 먼저 걸러버리면 C 구매 행이 사라져 "C를 안 샀다"를 판정할 수 없다. 필터를 `SUM(조건)` 안으로 넣어 모든 행을 보존해야 한다.
- `only_full_group_by` 모드에서는 `SELECT`의 `customer_name`이 `GROUP BY`에 없으면 오류다. 그룹키에 포함시킨다.

### 💡 대안 / 응용

- `MAX` 버전: `HAVING MAX(product_name='A')=1 AND MAX(product_name='B')=1 AND MAX(product_name='C')=0` — "존재=1, 부재=0"을 더 직관적으로 표현한다.

---
