# STEP 6 — CASE WHEN

> CASE 로 조건 분기, 조건부 집계(SUM(CASE))로 피벗, 행↔열 변환을 익힌다.

[← 목록으로](index.md)

---

## 1. [1873] Calculate Special Bonus

**링크**: https://leetcode.cn/problems/calculate-special-bonus/  
**학습 포인트**: `CASE WHEN` 으로 조건부 값 계산, `IF()` 와의 비교.

### 문제

```
Employees
+-------------+---------+
| employee_id | int     |  -- PK
| name        | varchar |
| salary      | int     |
+-------------+---------+
```

요구사항: 직원의 보너스를 계산한다. `employee_id` 가 **홀수**이고 `name` 이 **'M' 으로 시작하지 않으면** 보너스는 `salary`, 그 외에는 `0`. `employee_id` 오름차순으로 출력한다.

### 정답

```sql
SELECT
    employee_id,
    CASE
        WHEN employee_id % 2 = 1 AND name NOT LIKE 'M%' THEN salary
        ELSE 0
    END AS bonus
FROM Employees
ORDER BY employee_id;
```

### 풀이 — 왜 이렇게 하는가

1. **홀수 판정**: `employee_id % 2 = 1` (또는 `MOD(employee_id, 2) = 1`). 짝수는 `% 2 = 0`.
2. **'M' 으로 시작하지 않음**: `name NOT LIKE 'M%'`. `%` 는 0글자 이상 임의 문자열이므로 `'M%'` 는 "M 으로 시작". 이를 부정한다.
3. 두 조건을 `AND` 로 묶어 참이면 `salary`, 거짓이면 `ELSE 0`.

### 핵심 개념

- **`CASE WHEN 조건 THEN 값 ELSE 값 END`**: SQL 표준 조건식. `ELSE` 를 생략하면 어떤 `WHEN` 에도 걸리지 않을 때 `NULL` 이 된다. 여기선 0이 필요하니 `ELSE 0` 필수.
- **`CASE` vs `IF()`**: 같은 로직을 `IF(employee_id % 2 = 1 AND name NOT LIKE 'M%', salary, 0)` 로도 쓸 수 있다. `IF()` 는 MySQL 전용, 인자 3개(조건·참·거짓)로 간결하다. `CASE` 는 SQL 표준이라 이식성이 좋고, 조건이 3개 이상으로 늘어날 때 `WHEN` 을 계속 추가해 가독성을 유지한다.

### ⚠️ 흔한 실수

- `WHERE` 로 걸러버리기: 요구사항은 조건 미충족 행도 `bonus = 0` 으로 **모두 출력**하는 것이다. `WHERE` 를 쓰면 행이 사라진다. 반드시 `SELECT` 절의 `CASE` 로 처리한다.
- `name LIKE 'M%'` 를 부정할 때 `NOT LIKE` 대신 `!= 'M%'` 사용 → `LIKE` 의 와일드카드가 동작하지 않아 오답.

### 💡 대안 / 응용

- MySQL 전용 간결형: `IF(employee_id % 2 = 1 AND name NOT LIKE 'M%', salary, 0) AS bonus`.
- 이 문제는 STEP 2(WHERE·기본 조건)에도 등장한다. 그쪽은 조건식 자체에 초점을 두었고, 여기서는 "필터가 아니라 값을 분기한다"는 `CASE` 의 역할에 초점을 둔다.

---

## 2. [1179] Reformat Department Table

**링크**: https://leetcode.cn/problems/reformat-department-table/  
**학습 포인트**: `SUM(CASE ...)` 로 **행(월) → 열(피벗)** 변환.

### 문제

```
Department
+-------------+------+
| id          | int  |  -- (id, month) 가 PK
| revenue     | int  |
| month       | enum |  -- 'Jan' ~ 'Dec'
+-------------+------+
```

요구사항: 부서별 월 매출을 **한 행에 한 부서, 12개월을 열로** 펼쳐 출력한다. 출력 컬럼은 `id, Jan_Revenue, Feb_Revenue, ..., Dec_Revenue`. 특정 월 데이터가 없으면 `NULL`.

### 정답

```sql
SELECT
    id,
    SUM(CASE WHEN month = 'Jan' THEN revenue END) AS Jan_Revenue,
    SUM(CASE WHEN month = 'Feb' THEN revenue END) AS Feb_Revenue,
    SUM(CASE WHEN month = 'Mar' THEN revenue END) AS Mar_Revenue,
    SUM(CASE WHEN month = 'Apr' THEN revenue END) AS Apr_Revenue,
    SUM(CASE WHEN month = 'May' THEN revenue END) AS May_Revenue,
    SUM(CASE WHEN month = 'Jun' THEN revenue END) AS Jun_Revenue,
    SUM(CASE WHEN month = 'Jul' THEN revenue END) AS Jul_Revenue,
    SUM(CASE WHEN month = 'Aug' THEN revenue END) AS Aug_Revenue,
    SUM(CASE WHEN month = 'Sep' THEN revenue END) AS Sep_Revenue,
    SUM(CASE WHEN month = 'Oct' THEN revenue END) AS Oct_Revenue,
    SUM(CASE WHEN month = 'Nov' THEN revenue END) AS Nov_Revenue,
    SUM(CASE WHEN month = 'Dec' THEN revenue END) AS Dec_Revenue
FROM Department
GROUP BY id;
```

### 풀이 — 왜 이렇게 하는가

1. **피벗의 원리**: 원본은 "부서 하나 + 월 하나 = 한 행"인 세로(long) 형태다. 목표는 "부서 하나 = 한 행, 월은 열"인 가로(wide) 형태. `GROUP BY id` 로 부서별로 행을 묶고, 각 월 값을 별도 컬럼으로 뽑는다.
2. **`CASE WHEN month = 'Jan' THEN revenue END`**: 해당 월이면 매출, 아니면 `NULL`(ELSE 생략). 즉 "그 월의 매출만 남기고 나머지는 NULL 로 지운" 열이 만들어진다.
3. **왜 `SUM()` 으로 감싸나**: `GROUP BY id` 를 하면 부서 그룹 안에 12개월치 행이 뒤섞여 있다. `CASE` 하나만으로는 그룹의 여러 행 중 어떤 값을 대표로 쓸지 정할 수 없어 집계 함수가 필요하다. `(id, month)` 가 PK 라 부서+월 조합당 행은 최대 1개이므로, 그룹 안에서 'Jan' 인 행은 1개뿐 → 나머지는 전부 `NULL`. `SUM` 은 `NULL` 을 무시하므로 그 유일한 값이 그대로 결과가 되고, 데이터가 없으면 `SUM` 결과가 `NULL` 이 되어 요구사항과 맞는다.

### 핵심 개념

- **조건부 집계 피벗 관용구**: `집계함수(CASE WHEN 분류키 = '값' THEN 측정값 END)` 는 세로→가로 변환의 표준 패턴. 분류키의 각 값마다 컬럼을 하나씩 만든다.
- **`SUM` / `MAX` 둘 다 가능**: 그룹당 값이 최대 1개라면 `MAX(CASE ...)` 로 바꿔도 결과가 동일하다. 그룹당 값이 여러 개일 때는 의미에 맞게(합계면 `SUM`, 대표 1개면 `MAX`) 선택한다.
- **`NULL` 처리**: 없는 월을 `NULL` 로 두라는 요구이므로 `ELSE` 를 생략(암묵적 `NULL`)하는 것이 정답. `ELSE 0` 을 넣으면 오답.

### ⚠️ 흔한 실수

- `SUM`/`MAX` 없이 `CASE` 만 쓰기: `GROUP BY` 와 함께 쓰면 집계되지 않은 컬럼이라 오류가 나거나(엄격 모드) 그룹당 임의의 한 행 값이 나온다. 반드시 집계로 감싼다.
- `GROUP BY id` 를 빠뜨리면 부서별로 접히지 않아 각 원본 행이 그대로 남는다.
- `ELSE 0` 을 넣어 없는 월이 `0` 으로 나오면 요구사항(`NULL`) 위반.

### 💡 대안 / 응용

- 분류키 값이 동적으로 늘어나면(예: 연도별) 위 방식은 SQL 을 손으로 계속 늘려야 한다. 실무에선 애플리케이션에서 컬럼 목록을 만들어 **동적 SQL** 을 생성하거나 BI 도구의 피벗 기능을 쓴다.
- 반대 방향(가로→세로 unpivot)은 `UNION ALL` 로 각 열을 행으로 풀어 만든다.

---

## 3. [1205] Monthly Transactions II

**링크**: https://leetcode.cn/problems/monthly-transactions-ii/  
**학습 포인트**: 두 테이블을 `UNION` 으로 합친 뒤 월·국가별 **조건부 집계**.

### 문제

```
Transactions
+---------------+---------+
| id            | int     |  -- PK
| country       | varchar |
| state         | enum    |  -- 'approved' | 'declined'
| amount        | int     |
| trans_date    | date    |
+---------------+---------+

Chargebacks               -- 승인된 거래에 대한 환불(chargeback)
+---------------+---------+
| trans_id      | int     |  -- Transactions.id 를 참조
| trans_date    | date    |  -- chargeback 발생일
+---------------+---------+
```

요구사항: 월(`YYYY-MM`)·국가별로 다음을 구한다.
- `approved_count`: 승인(approved) 건수
- `approved_amount`: 승인 총액
- `chargeback_count`: chargeback 건수
- `chargeback_amount`: chargeback 총액

주의: chargeback 의 **월/국가는 `Chargebacks.trans_date` 와 원거래의 country** 를 기준으로 한다(승인 시점이 아님). approved 는 `state = 'approved'` 인 거래 기준. 4개 값 중 하나라도 있으면 그 (월, 국가) 행을 출력한다.

### 정답

```sql
SELECT
    month,
    country,
    SUM(CASE WHEN type = 'approved'   THEN 1 ELSE 0 END)      AS approved_count,
    SUM(CASE WHEN type = 'approved'   THEN amount ELSE 0 END) AS approved_amount,
    SUM(CASE WHEN type = 'chargeback' THEN 1 ELSE 0 END)      AS chargeback_count,
    SUM(CASE WHEN type = 'chargeback' THEN amount ELSE 0 END) AS chargeback_amount
FROM (
    -- 승인된 거래
    SELECT
        DATE_FORMAT(trans_date, '%Y-%m') AS month,
        country,
        amount,
        'approved' AS type
    FROM Transactions
    WHERE state = 'approved'

    UNION ALL

    -- chargeback: 원거래에서 country/amount 를 가져오되 날짜는 chargeback 발생일
    SELECT
        DATE_FORMAT(c.trans_date, '%Y-%m') AS month,
        t.country,
        t.amount,
        'chargeback' AS type
    FROM Chargebacks c
    JOIN Transactions t ON c.trans_id = t.id
) AS combined
GROUP BY month, country;
```

### 풀이 — 왜 이렇게 하는가

1. **두 사건을 하나의 스트림으로**: approved 와 chargeback 은 "월·국가별로 집계한다"는 점이 같다. 서로 다른 테이블·서로 다른 날짜 기준이므로, 먼저 각자를 `(month, country, amount, type)` 형태로 **통일**한 뒤 `UNION ALL` 로 세로로 이어붙인다. `type` 은 나중에 어느 쪽인지 구분하는 꼬리표다.
2. **approved 쪽**: `Transactions` 에서 `state = 'approved'` 만 골라 `trans_date` 로 월을 만든다.
3. **chargeback 쪽**: `Chargebacks` 자체엔 country/amount 가 없으므로 `trans_id` 로 `Transactions` 를 `JOIN` 해 원거래의 country/amount 를 끌어온다. 단, **월은 `Chargebacks.trans_date`**(chargeback 발생일)로 만든다 — 이 문제의 핵심 함정.
4. **바깥 쿼리에서 조건부 집계**: 합쳐진 스트림을 `GROUP BY month, country` 로 묶고, `type` 에 따라 `SUM(CASE ...)` 로 4개 값을 한 번에 뽑는다. 카운트는 `THEN 1`, 금액은 `THEN amount`.
5. **`UNION ALL`** 사용: 중복 제거가 필요 없고 성능상 유리하므로 `UNION`(중복 제거) 이 아니라 `UNION ALL`.

### 핵심 개념

- **정규화된 스트림 + 조건부 집계**: 성격이 다른 여러 이벤트를 공통 스키마로 통일(`UNION ALL`)한 뒤 꼬리표(`type`)로 `SUM(CASE)` 집계하는 패턴. 서로 다른 세분화(각기 다른 날짜/테이블)를 하나의 리포트로 합칠 때 강력하다.
- **집계 기준일의 출처가 다름**: approved 는 `Transactions.trans_date`, chargeback 은 `Chargebacks.trans_date`. 어느 날짜로 월을 만드는지가 정오답을 가른다.
- **`DATE_FORMAT(날짜, '%Y-%m')`**: 날짜를 'YYYY-MM' 월 문자열로. 월별 그룹핑의 표준.

### ⚠️ 흔한 실수

- chargeback 의 월을 `Transactions.trans_date`(원거래일)로 잡기 → 발생일 기준이 아니라 오답.
- `Transactions` 와 `Chargebacks` 를 그냥 `LEFT JOIN` 한 뒤 한 번에 집계하려다 approved 건수가 chargeback 유무에 따라 중복 계산되는 실수. 두 사건을 분리해 `UNION ALL` 로 합치는 편이 안전하다.
- approved 필터 위치: `state = 'approved'` 는 approved 스트림에만 적용해야 한다. declined 거래에도 chargeback 은 생기지 않으므로 조인 대상엔 영향 없지만, approved 집계에 declined 가 섞이지 않도록 주의.

### 💡 대안 / 응용

- 서브쿼리 대신 CTE 로 가독성을 높일 수 있다: `WITH combined AS ( ... UNION ALL ... ) SELECT ... FROM combined GROUP BY month, country`.
- STEP 3(집계·GROUP BY)의 Monthly Transactions I 은 한 테이블만 조건부 집계했다. 이 II 는 "다른 테이블·다른 기준일을 UNION 으로 합친 뒤 조건부 집계"로 난이도를 한 단계 올린 버전이다.

---

## 4. [1174] Immediate Food Delivery II

**링크**: https://leetcode.cn/problems/immediate-food-delivery-ii/  
**학습 포인트**: `AVG(CASE ...)` 로 **비율** 계산 — CASE 를 0/1 로 만들어 평균 내기.

### 문제

```
Delivery
+-----------------------------+------+
| delivery_id                 | int  |  -- PK
| customer_id                 | int  |
| order_date                  | date |
| customer_pref_delivery_date | date |
+-----------------------------+------+
```

- **immediate 주문**: `order_date = customer_pref_delivery_date` (희망일이 주문일과 같음). 아니면 scheduled.
- **첫 주문(first order)**: 각 고객의 가장 이른 `order_date` 주문.

요구사항: **각 고객의 첫 주문** 중 immediate 인 비율을 백분율(소수 둘째 자리 반올림)로 구한다.

### 정답

```sql
SELECT
    ROUND(
        AVG(CASE WHEN order_date = customer_pref_delivery_date THEN 1 ELSE 0 END) * 100,
        2
    ) AS immediate_percentage
FROM Delivery
WHERE (customer_id, order_date) IN (
    SELECT customer_id, MIN(order_date)
    FROM Delivery
    GROUP BY customer_id
);
```

### 풀이 — 왜 이렇게 하는가

1. **첫 주문만 남기기**: 서브쿼리 `SELECT customer_id, MIN(order_date) ... GROUP BY customer_id` 로 고객별 최소 주문일을 구하고, `(customer_id, order_date) IN (...)` 튜플 매칭으로 각 고객의 첫 주문 행만 필터한다. (한 고객이 같은 날 첫 주문을 여러 건 넣는 경우는 이 문제 데이터에선 발생하지 않는다고 본다.)
2. **immediate 를 0/1 로**: `CASE WHEN order_date = customer_pref_delivery_date THEN 1 ELSE 0 END` — immediate 면 1, scheduled 면 0.
3. **`AVG` 로 비율**: 0/1 값의 평균 = (1의 개수)/(전체 개수) = immediate 비율. 여기에 `* 100` 으로 백분율, `ROUND(..., 2)` 로 소수 둘째 자리 반올림.

### 핵심 개념

- **`AVG(CASE WHEN 조건 THEN 1 ELSE 0 END)`**: "조건을 만족하는 비율"을 구하는 관용구. `SUM(CASE ... THEN 1 END) / COUNT(*)` 와 동일하지만 `AVG` 한 방으로 간결하다.
- **CASE 각도**: 이 계산의 본질은 "boolean 을 숫자로 캐스팅해 평균"이다. `CASE` 가 그 boolean→숫자 변환을 담당한다.
- **튜플 `IN` 매칭**: `(a, b) IN (SELECT a, MIN(b) ...)` 로 "그룹별 대표 행"을 뽑는 흔한 기법.

### ⚠️ 흔한 실수

- 첫 주문 필터를 빼먹고 **전체 주문**의 immediate 비율을 구하기 → 이 문제(II)가 아니라 I(전체 기준)의 답이 된다.
- `AVG` 없이 `COUNT` 로 나눌 때 정수 나눗셈으로 소수가 잘리는 실수. `* 100` 을 곱하거나 실수 연산을 보장해야 한다. `AVG` 방식은 자동으로 실수라 안전하다.

### 💡 대안 / 응용

- 윈도우 함수로 첫 주문을 찾을 수도 있다:
  ```sql
  SELECT ROUND(AVG(CASE WHEN order_date = customer_pref_delivery_date THEN 1 ELSE 0 END) * 100, 2) AS immediate_percentage
  FROM (
      SELECT *, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date) AS rn
      FROM Delivery
  ) t
  WHERE rn = 1;
  ```
- STEP 3(집계)에서는 `SUM/COUNT` 비율 관점으로 다뤘다면, 여기서는 동일 문제를 `AVG(CASE ...)` 관용구로 다시 본 것이다.

---

## 5. [1587] Bank Account Summary II

**링크**: https://leetcode.cn/problems/bank-account-summary-ii/  
**학습 포인트**: `JOIN` + 집계 `SUM` + `HAVING` 으로 그룹 필터 (집계 복습).

### 문제

```
Users
+--------------+---------+
| account      | int     |  -- PK
| name         | varchar |
+--------------+---------+

Transactions
+---------------+---------+
| trans_id      | int     |  -- PK
| account       | int     |
| amount        | int     |  -- 입금(+)/출금(-)
| transacted_on | date    |
+---------------+---------+
```

요구사항: 잔액(= 해당 계좌의 `amount` 총합)이 **10000 초과**인 사용자의 `name` 과 `balance` 를 출력한다.

### 정답

```sql
SELECT
    u.name,
    SUM(t.amount) AS balance
FROM Users u
JOIN Transactions t ON u.account = t.account
GROUP BY u.account, u.name
HAVING SUM(t.amount) > 10000;
```

### 풀이 — 왜 이렇게 하는가

1. **JOIN**: 잔액은 `Transactions` 에서 계산하지만 출력할 `name` 은 `Users` 에 있으므로 `account` 로 조인한다.
2. **계좌별 잔액**: `GROUP BY u.account` 로 계좌별로 묶어 `SUM(t.amount)` 로 입출금을 합산 = 잔액. `name` 도 `SELECT` 하므로 `GROUP BY` 에 함께 넣는다(`account` 가 PK 라 name 은 함수 종속이지만, 표준·엄격 모드 호환을 위해 명시).
3. **`HAVING` 으로 그룹 필터**: 잔액 `> 10000` 조건은 **집계 결과**에 대한 조건이므로 `WHERE` 가 아니라 `HAVING` 을 쓴다.

### 핵심 개념

- **`WHERE` vs `HAVING`**: `WHERE` 는 그룹핑 **전** 개별 행을 거르고, `HAVING` 은 그룹핑 **후** 집계값을 거른다. `SUM(amount) > 10000` 은 집계값이라 `HAVING` 이어야 한다.
- **집계 + 조인 순서**: 조인으로 행을 합친 뒤 그룹핑·집계한다. `Transactions` 가 없는(거래 이력 없는) 계좌는 `INNER JOIN` 이라 제외된다 — 잔액 10000 초과 조건상 어차피 문제없다.

### ⚠️ 흔한 실수

- `WHERE SUM(amount) > 10000` 으로 쓰기 → 집계 함수는 `WHERE` 절에서 사용할 수 없어 오류. 반드시 `HAVING`.
- `GROUP BY` 에 `account` 를 넣지 않고 `name` 만 넣으면, 동명이인 계좌가 하나로 합쳐질 수 있다. PK 인 `account` 로 그룹핑하는 것이 안전하다.

### 💡 대안 / 응용

- CASE 는 이 문제에 필수는 아니지만, 입금/출금을 나눠 보고 싶다면 조건부 집계로 확장할 수 있다:
  ```sql
  SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS deposit,
  SUM(CASE WHEN amount < 0 THEN amount ELSE 0 END) AS withdrawal
  ```
- 이 문제는 STEP 3(집계·HAVING)와 겹친다. 여기서는 "집계+HAVING 을 CASE 절 STEP 에서 복습"하는 마무리 문제로 배치했다.

---
