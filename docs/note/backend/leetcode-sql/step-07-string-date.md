# STEP 7 — 문자열 / 날짜 함수

> 문자열 가공(UPPER/SUBSTRING/CONCAT)과 날짜 연산(DATEDIFF/DATE_ADD/DATE_FORMAT), 연속 날짜 구간을 다룬다.

[← 목록으로](index.md)

---

## 1. [1667] Fix Names in a Table

**링크**: https://leetcode.cn/problems/fix-names-in-a-table/  
**학습 포인트**: 첫 글자만 대문자, 나머지는 소문자로 바꾸는 문자열 가공.

### 문제
`Users(user_id PK, name)` 테이블이 있다. `name`은 대소문자가 뒤섞여 있다.
각 이름을 **첫 글자만 대문자, 나머지는 소문자**로 고쳐서 `user_id` 순으로 출력하라.

예: `aLICE` → `Alice`, `bOB` → `Bob`

### 정답
```sql
SELECT
    user_id,
    CONCAT(UPPER(LEFT(name, 1)), LOWER(SUBSTRING(name, 2))) AS name
FROM Users
ORDER BY user_id;
```

### 풀이 — 왜 이렇게 하는가
1. `LEFT(name, 1)` — 왼쪽 1글자(첫 글자)를 잘라낸다.
2. `UPPER(...)` — 첫 글자를 대문자로.
3. `SUBSTRING(name, 2)` — 2번째 문자부터 끝까지 잘라낸다. MySQL의 문자열 인덱스는 **1부터** 시작하므로 `2`가 두 번째 글자다.
4. `LOWER(...)` — 나머지를 전부 소문자로.
5. `CONCAT(...)` — 두 조각을 이어 붙인다.

### 핵심 개념
- `LEFT(str, n)` / `SUBSTRING(str, pos [, len])`: MySQL 문자열 위치는 1-based.
- `UPPER` / `LOWER`: 대소문자 변환.
- `CONCAT`: 문자열 연결. 인자 중 하나라도 `NULL`이면 결과가 `NULL`이 된다.

### ⚠️ 흔한 실수
- `SUBSTRING(name, 1)`로 쓰면 전체 문자열이 잘려 소문자가 두 번 붙는다. 반드시 `2`부터.
- `SUBSTR(name, 2, 100)`처럼 길이를 억지로 넣지 않아도 된다. 세 번째 인자를 생략하면 끝까지 반환한다.

### 💡 대안 / 응용
- `SUBSTRING`은 `SUBSTR`, `MID`와 동의어다. 아무거나 써도 된다.
- 여러 단어를 각각 카멜케이스로 만들려면 `SUBSTRING_INDEX` + 재귀/함수가 필요하지만, 이 문제는 단일 단어라 단순 처리로 충분하다.

---

## 2. [1527] Patients With a Condition

**링크**: https://leetcode.cn/problems/patients-with-a-condition/  
**학습 포인트**: 공백으로 구분된 코드 목록에서 접두사(prefix) 매칭 — LIKE의 함정.

### 문제
`Patients(patient_id PK, patient_name, conditions)`. `conditions`는 공백으로 구분된 여러 질병 코드 문자열이다(예: `'DIAB100 MYOP200'`).
**Type I Diabetes** 코드는 접두사 `DIAB1`로 시작한다. `conditions` 안에 `DIAB1`로 시작하는 코드가 **하나라도** 있는 환자를 찾아라.

### 정답
```sql
SELECT patient_id, patient_name, conditions
FROM Patients
WHERE conditions LIKE 'DIAB1%'
   OR conditions LIKE '% DIAB1%';
```

### 풀이 — 왜 이렇게 하는가
코드가 하나의 문자열 안에 공백으로 여러 개 나열되어 있으므로, `DIAB1`이 **어떤 코드의 맨 앞**에 오는 경우는 두 가지뿐이다.
1. `LIKE 'DIAB1%'` — 문자열 **전체의 맨 앞**이 `DIAB1`인 경우 (첫 번째 코드).
2. `LIKE '% DIAB1%'` — 앞에 **공백**이 있고 그 뒤가 `DIAB1`인 경우 (두 번째 이후 코드).

이 두 조건을 OR로 묶으면 "단어 경계에서 시작하는 DIAB1"만 정확히 잡는다.

### 핵심 개념
- `%`는 0글자 이상, `_`는 정확히 1글자를 의미하는 LIKE 와일드카드.
- 접두사 매칭은 반드시 **단어 경계**(문자열 시작 또는 공백 뒤)를 함께 확인해야 한다.

### ⚠️ 흔한 실수 (앞공백 함정 — 정밀 설명)
- `WHERE conditions LIKE '%DIAB1%'` **한 줄만** 쓰면 틀린다.
  `'%DIAB1%'`는 문자열 아무 위치에나 `DIAB1`이 있으면 매칭한다. 예를 들어 `'ACADIAB1'`이나 `'XDIAB100'`처럼 **다른 코드의 중간**에 우연히 `DIAB1`이 들어가도 오탐(false positive)한다.
- 그래서 "맨 앞(`DIAB1%`)" 또는 "공백 뒤(`% DIAB1%`)"로 시작 위치를 강제해야 한다. 앞 공백 ` `이 단어 경계를 보장하는 핵심이다.
- 반대로 `' DIAB1%'`처럼 공백만 있는 조건 하나만 쓰면, **첫 번째 코드**(앞에 공백이 없음)를 놓친다.

### 💡 대안 / 응용
- 정규식으로 한 줄에 처리 가능: `WHERE conditions REGEXP '\\bDIAB1'` (단어 경계 `\b`). MySQL 8은 REGEXP를 지원한다. 다만 LIKE보다 느리고 인덱스 활용이 어렵다.
- 이런 "공백 구분 다중 값" 컬럼은 정규화(별도 테이블로 1행 1코드) 대상이다. 실무라면 스키마 개선을 고려한다.

---

## 3. [1141] User Activity for the Past 30 Days I

**링크**: https://leetcode.cn/problems/user-activity-for-the-past-30-days-i/  
**학습 포인트**: 날짜 범위 필터 + `COUNT(DISTINCT)`, 그리고 컬럼에 함수를 씌우지 않는 이유.

### 문제
`Activity(user_id, session_id, activity_date, activity_type)`. `2019-07-27`을 기준으로 **최근 30일**(당일 포함) 동안, **날짜별 활동한 고유 사용자 수**를 구하라.
"최근 30일"은 `2019-06-28` ~ `2019-07-27` 구간이다. 활동이 없는 날은 출력하지 않는다.

### 정답
```sql
SELECT
    activity_date AS day,
    COUNT(DISTINCT user_id) AS active_users
FROM Activity
WHERE activity_date BETWEEN '2019-06-28' AND '2019-07-27'
GROUP BY activity_date;
```

### 풀이 — 왜 이렇게 하는가
1. `BETWEEN '2019-06-28' AND '2019-07-27'` — 30일 구간을 명시적 상수 범위로 필터한다. `BETWEEN`은 양 끝을 포함(inclusive)하므로 6/28부터 7/27까지 딱 30일이다.
2. `GROUP BY activity_date` — 날짜별로 묶는다.
3. `COUNT(DISTINCT user_id)` — 한 날에 같은 사용자가 여러 세션을 열었을 수 있으므로, **중복 제거**하여 고유 사용자만 센다.

### 핵심 개념
- `BETWEEN a AND b`는 `>= a AND <= b`와 동일(양 끝 포함).
- `COUNT(DISTINCT ...)`: 중복 제거 후 개수. 여기서는 사용자 유니크 카운트가 핵심.
- 날짜 구간은 **끝점을 포함하는지** 항상 확인해야 한다.

### ⚠️ 흔한 실수 (컬럼에 함수 안 씌우는 이유)
- `WHERE DATEDIFF('2019-07-27', activity_date) < 30` 같은 식으로 쓰면 결과가 미묘하게 틀리기 쉽고(경계 오차), **더 중요하게는 `activity_date` 컬럼에 함수를 씌우면 인덱스를 못 탄다(non-sargable)**. 옵티마이저가 모든 행을 스캔해야 하므로 대용량에서 느려진다.
- 반면 `activity_date BETWEEN 상수 AND 상수`는 컬럼을 그대로 두고 상수와 비교하므로 인덱스 범위 스캔이 가능하다. **필터 조건의 컬럼 쪽은 가공하지 않는 것**이 실무 원칙이다.
- `COUNT(user_id)`(DISTINCT 없이)로 쓰면 세션 수를 세게 되어 오답.

### 💡 대안 / 응용
- 상대 기준이 필요하면 `WHERE activity_date > DATE_SUB('2019-07-27', INTERVAL 30 DAY) AND activity_date <= '2019-07-27'`처럼 **범위의 양 끝을 상수로 계산**해 컬럼은 가공하지 않는 형태로 만든다.

---

## 4. [1225] Report Contiguous Dates

**링크**: https://leetcode.cn/problems/report-contiguous-dates/  
**학습 포인트**: 연속 날짜 구간 압축 — gaps and islands(날짜 − ROW_NUMBER 그룹핑).

### 문제
두 테이블이 있다.
- `Failed(fail_date)`: 시스템이 실패한 날짜들
- `Succeeded(success_date)`: 성공한 날짜들

기간은 `2019-01-01` ~ `2019-12-31`이며, 한 날짜는 실패 또는 성공 중 정확히 하나에만 속한다.
같은 상태(`failed`/`succeeded`)가 **연속된 날짜 구간**을 하나로 묶어 `period_state, start_date, end_date`를 `start_date` 순으로 출력하라.

예: 실패가 1/1, 1/2, 1/3 연속이면 → `(failed, 2019-01-01, 2019-01-03)` 한 행.

### 정답
```sql
WITH logs AS (
    SELECT 'failed'    AS period_state, fail_date    AS dt FROM Failed
    WHERE fail_date    BETWEEN '2019-01-01' AND '2019-12-31'
    UNION ALL
    SELECT 'succeeded' AS period_state, success_date AS dt FROM Succeeded
    WHERE success_date BETWEEN '2019-01-01' AND '2019-12-31'
),
grouped AS (
    SELECT
        period_state,
        dt,
        DATE_SUB(
            dt,
            INTERVAL ROW_NUMBER() OVER (
                PARTITION BY period_state ORDER BY dt
            ) DAY
        ) AS grp
    FROM logs
)
SELECT
    period_state,
    MIN(dt) AS start_date,
    MAX(dt) AS end_date
FROM grouped
GROUP BY period_state, grp
ORDER BY start_date;
```

### 풀이 — 왜 이렇게 하는가 (그림처럼 단계별)

**1단계: 두 로그를 합친다.**
`UNION ALL`로 실패/성공을 `period_state`, `dt` 두 컬럼짜리 하나의 스트림으로 만든다.

**2단계: gaps-and-islands 핵심 아이디어.**
같은 상태 안에서 날짜를 정렬한 뒤 `ROW_NUMBER()`(1,2,3,...)를 매긴다.
**연속된 날짜라면 날짜도 1씩 증가하고 행번호도 1씩 증가**한다. 따라서 `날짜 − 행번호`는 **연속 구간 내내 같은 값**으로 고정된다. 구간이 끊기면(하루라도 건너뛰면) 이 값이 바뀐다.

`failed` 상태를 예로 표로 보자. `grp = 날짜 − rn`(일 단위 뺄셈):

| dt (fail_date) | rn | dt − rn (grp) |
|----------------|----|---------------|
| 2019-01-01 | 1 | 2018-12-31 |
| 2019-01-02 | 2 | 2018-12-31 |
| 2019-01-03 | 3 | 2018-12-31 |
| 2019-01-06 | 4 | 2019-01-02 |
| 2019-01-07 | 5 | 2019-01-02 |

- 1/1~1/3은 연속이라 `grp`가 `2018-12-31`로 동일 → 한 섬(island).
- 1/4, 1/5는 성공이라 실패 목록엔 없으니 rn이 건너뛴다. 1/6부터 rn=4가 되고 `grp`가 `2019-01-02`로 바뀜 → 새 섬.

**3단계: 같은 `(period_state, grp)`끼리 묶어** 최솟값을 `start_date`, 최댓값을 `end_date`로 집계한다.
`period_state`를 그룹 키에 포함하는 이유: 실패 섬과 성공 섬의 `grp` 값이 우연히 겹칠 수 있으므로 상태별로 분리해야 한다.

### 핵심 개념
- **gaps and islands**: 연속 구간을 찾는 대표 패턴. "정렬값 − 행번호가 일정하면 연속"이 핵심.
- 날짜에서 `INTERVAL rn DAY`를 빼면 날짜형 그룹 키가 나온다. 정수 날짜(`TO_DAYS(dt) - rn`)로 만들어도 결과는 동일하다.
- `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)`: 그룹별 순번.

### ⚠️ 흔한 실수
- `PARTITION BY period_state`를 빠뜨리면 실패/성공이 섞여 순번이 뒤엉킨다.
- 최종 `GROUP BY`에 `grp`만 넣고 `period_state`를 빼면 서로 다른 상태의 섬이 합쳐질 수 있다.
- 문제 조건상 날짜 범위(`2019` 한 해)를 필터하지 않으면 범위 밖 데이터가 섞일 수 있으니 확인한다.

### 💡 대안 / 응용
- `DATE_SUB(dt, INTERVAL rn DAY)` 대신 `DATEDIFF(dt, '2000-01-01') - rn`(정수)로 그룹 키를 만들어도 된다. 정수 비교가 더 가볍다.
- 윈도우 함수 없이 상관 서브쿼리로 이전 날짜를 찾아 구간을 나누는 방식도 있으나 복잡하다. CTE(WITH)의 활용은 STEP 9에서 더 다룬다.

---

## 5. [1454] Active Users

**링크**: https://leetcode.cn/problems/active-users/  
**학습 포인트**: 5일 연속 로그인 판정 — self join 또는 날짜−행번호 그룹핑.

### 문제
`Accounts(id PK, name)`, `Logins(id, login_date)`. `Logins`는 중복 로그인 행이 있을 수 있다.
**연속 5일(이상) 이상 로그인**한 적이 있는 사용자의 `id, name`을 `id` 순으로 출력하라. 하루에 여러 번 로그인해도 하루로 센다.

### 정답 (날짜 − 행번호 그룹핑)
```sql
WITH daily AS (          -- 사용자별 하루 1행으로 중복 제거
    SELECT DISTINCT id, login_date
    FROM Logins
),
grouped AS (
    SELECT
        id,
        login_date,
        DATE_SUB(
            login_date,
            INTERVAL ROW_NUMBER() OVER (
                PARTITION BY id ORDER BY login_date
            ) DAY
        ) AS grp
    FROM daily
)
SELECT DISTINCT a.id, a.name
FROM grouped g
JOIN Accounts a ON a.id = g.id
GROUP BY g.id, a.name, g.grp
HAVING COUNT(*) >= 5
ORDER BY a.id;
```

### 풀이 — 왜 이렇게 하는가
1. `SELECT DISTINCT id, login_date` — **핵심 전처리**. 한 사용자가 같은 날 여러 번 로그인한 중복을 제거해 "1일 = 1행"으로 만든다. 이걸 빠뜨리면 연속 판정이 깨진다.
2. 1225번과 같은 **gaps-and-islands**: `login_date − ROW_NUMBER()`가 같으면 연속된 날짜다.
3. `GROUP BY id, grp` 후 `HAVING COUNT(*) >= 5` — 같은 연속 섬에 날짜가 5개 이상이면 5일 연속 로그인.
4. 그런 섬이 하나라도 있는 사용자를 `Accounts`와 조인해 이름을 붙이고 `DISTINCT`로 중복 제거.

### 핵심 개념
- 연속 N일 판정도 gaps-and-islands로 일반화된다. `HAVING COUNT(*) >= N`만 바꾸면 된다.
- **하루 여러 로그인 → DISTINCT 필수**: 날짜 중복은 순번을 왜곡한다.

### ⚠️ 흔한 실수
- `DISTINCT` 없이 순번을 매기면 같은 날에 rn이 2씩 늘어 `grp`가 어긋나고 연속 판정이 틀린다.
- self join 방식에서 "5일 연속"을 `DATEDIFF`로 판단할 때 경계(정확히 4일 차이 = 5일 구간)를 헷갈리기 쉽다.

### 💡 대안 / 응용 (self join)
```sql
SELECT DISTINCT a.id, a.name
FROM (SELECT DISTINCT id, login_date FROM Logins) l
JOIN Accounts a ON a.id = l.id
WHERE (
    SELECT COUNT(DISTINCT login_date)
    FROM Logins l2
    WHERE l2.id = l.id
      AND l2.login_date BETWEEN l.login_date AND DATE_ADD(l.login_date, INTERVAL 4 DAY)
) = 5;
```
- 각 로그인 날짜를 시작점으로 보고 그날부터 4일 뒤(총 5일)까지의 **고유 날짜 수가 정확히 5**면 5일 연속이다. `INTERVAL 4 DAY`가 "당일 포함 5일"을 뜻하는 점에 주의.

---

## 6. [1084] Sales Analysis III

**링크**: https://leetcode.cn/problems/sales-analysis-iii/  
**학습 포인트**: "특정 기간에만" 팔린 항목 — MIN/MAX로 판별하는 HAVING.

### 문제
`Product(product_id PK, product_name, unit_price)`, `Sales(seller_id, product_id, buyer_id, sale_date, quantity, price)`.
**오직 2019년 봄(2019-01-01 ~ 2019-03-31) 사이에만** 팔린 상품의 `product_id, product_name`을 구하라. 즉 그 기간 밖에서는 한 번도 안 팔린 상품.

### 정답
```sql
SELECT
    p.product_id,
    p.product_name
FROM Sales s
JOIN Product p ON p.product_id = s.product_id
GROUP BY s.product_id, p.product_name
HAVING MIN(s.sale_date) >= '2019-01-01'
   AND MAX(s.sale_date) <= '2019-03-31';
```

### 풀이 — 왜 이렇게 하는가
"오직 봄에만 팔렸다"는 곧 **모든 판매 날짜가 봄 구간 안에 있다**는 뜻이다.
어떤 상품의 판매 날짜 집합에서
- 가장 이른 판매일 `MIN(sale_date)`이 `2019-01-01` 이상이고,
- 가장 늦은 판매일 `MAX(sale_date)`이 `2019-03-31` 이하이면,

그 사이의 모든 날짜도 자동으로 구간 안에 있다. 따라서 `MIN`과 `MAX`만 검사하면 전체가 구간 내인지 판정된다.

### 핵심 개념
- **"모든 값이 범위 내" = MIN >= 하한 AND MAX <= 상한**. 개별 행을 다 볼 필요 없이 양 극값만 보면 된다.
- 이 판정은 `WHERE`가 아니라 그룹 단위 조건이므로 `HAVING`에 둔다.

### ⚠️ 흔한 실수
- `WHERE sale_date BETWEEN ...`으로 필터하면 **틀린다**. 봄 밖 판매 행만 제거될 뿐, 봄에도 팔리고 여름에도 팔린 상품이 걸러지지 않아 오답이 된다. 반드시 그룹 전체를 `HAVING MIN/MAX`로 봐야 한다.
- `HAVING` 조건 하나만(예: `MAX <= '2019-03-31'`) 쓰면 하한 검증이 빠진다.

### 💡 대안 / 응용
- `HAVING SUM(sale_date NOT BETWEEN '2019-01-01' AND '2019-03-31') = 0`처럼 "구간 밖 판매 건수가 0"으로도 표현 가능하다. MIN/MAX 방식이 더 직관적이다.

---

## 7. [1795] Rearrange Products Table

**링크**: https://leetcode.cn/problems/rearrange-products-table/  
**학습 포인트**: 열→행 unpivot을 UNION ALL로 구현, NULL 제외.

### 문제
`Products(product_id, store1, store2, store3)`. 각 `storeN` 컬럼에는 해당 매장에서의 가격이 들어 있고, 그 매장에서 안 팔면 `NULL`이다.
이 **와이드(wide) 형태**를 **롱(long) 형태** `(product_id, store, price)`로 펼쳐라. 단, 가격이 `NULL`인 조합(그 매장에서 안 파는 것)은 출력하지 않는다.

### 정답
```sql
SELECT product_id, 'store1' AS store, store1 AS price
FROM Products
WHERE store1 IS NOT NULL
UNION ALL
SELECT product_id, 'store2' AS store, store2 AS price
FROM Products
WHERE store2 IS NOT NULL
UNION ALL
SELECT product_id, 'store3' AS store, store3 AS price
FROM Products
WHERE store3 IS NOT NULL;
```

### 풀이 — 왜 이렇게 하는가
1. 매장별로 쿼리를 하나씩 만든다. 각 쿼리는 `store` 컬럼에 **매장 이름 문자열 리터럴**을, `price` 컬럼에 해당 매장 가격 컬럼을 넣는다.
2. 세 결과를 `UNION ALL`로 세로로 이어 붙인다 → 열이 행으로 펼쳐진다(unpivot).
3. 각 쿼리에서 `WHERE storeN IS NOT NULL`로 안 파는 매장은 제외한다.

### 핵심 개념
- MySQL에는 전용 UNPIVOT 문법이 없어 **UNION ALL로 수동 unpivot**한다.
- `UNION`(중복 제거)이 아니라 `UNION ALL`을 쓴다: 각 조각은 서로 다른 매장이라 중복이 없고, 중복 제거 정렬 비용을 아낄 수 있다.
- 결과 컬럼 이름/개수/타입은 **첫 SELECT 기준**으로 맞춰진다.

### ⚠️ 흔한 실수
- `NULL` 제외를 각 조각의 `WHERE`에 넣지 않으면 안 파는 매장 행이 섞여 나온다.
- `UNION`을 쓰면 (드물지만) 완전히 동일한 `(product_id, store, price)` 행이 합쳐질 수 있고 불필요한 정렬이 생긴다. 여기선 `UNION ALL`이 맞다.

### 💡 대안 / 응용
- 매장이 아주 많다면 `CROSS JOIN`으로 매장 목록을 만들고 `CASE`로 가격을 고르는 방식도 있으나, 컬럼이 몇 개뿐이면 UNION ALL이 가장 명료하다.

---

## 8. [1127] User Purchase Platform

**링크**: https://leetcode.cn/problems/user-purchase-platform/  
**학습 포인트**: 사용자·날짜별 플랫폼 집합(desktop/mobile/both) 판정 후 집계 — 촘촘한 단계 설계.

### 문제
`Spending(user_id, spend_date, platform, amount)`. `platform`은 `'desktop'` 또는 `'mobile'`. `(user_id, spend_date, platform)`은 유일하다.
각 `spend_date`마다, 그날 각 사용자가 어떤 플랫폼에서 샀는지에 따라 사용자를 세 부류로 나눈다.
- `desktop`: 그날 데스크톱에서만 구매
- `mobile`: 그날 모바일에서만 구매
- `both`: 그날 두 플랫폼 모두에서 구매

각 `(spend_date, platform)` 조합에 대해 **총 지출액(total_amount)**과 **해당 사용자 수(total_users)**를 구하라. 그날 특정 부류에 해당하는 사용자가 **한 명도 없어도 0으로 출력**해야 한다(3부류 × 각 날짜 모두).

### 정답
```sql
WITH user_day AS (          -- 1) 사용자·날짜별로 플랫폼 부류와 그날 총액 계산
    SELECT
        user_id,
        spend_date,
        CASE
            WHEN COUNT(DISTINCT platform) = 2 THEN 'both'
            ELSE MIN(platform)          -- desktop 또는 mobile 하나만
        END AS platform,
        SUM(amount) AS amount
    FROM Spending
    GROUP BY user_id, spend_date
),
dates AS (                  -- 2) 데이터에 존재하는 모든 날짜
    SELECT DISTINCT spend_date FROM Spending
),
platforms AS (              -- 3) 세 부류 라벨
    SELECT 'desktop' AS platform
    UNION ALL SELECT 'mobile'
    UNION ALL SELECT 'both'
),
grid AS (                   -- 4) (모든 날짜 × 3부류) 조합 = 출력해야 할 뼈대
    SELECT d.spend_date, p.platform
    FROM dates d
    CROSS JOIN platforms p
)
SELECT
    g.spend_date,
    g.platform,
    COALESCE(SUM(u.amount), 0) AS total_amount,
    COUNT(u.user_id)           AS total_users
FROM grid g
LEFT JOIN user_day u
    ON u.spend_date = g.spend_date
   AND u.platform  = g.platform
GROUP BY g.spend_date, g.platform;
```

### 풀이 — 왜 이렇게 하는가 (촘촘히)

**1단계 — 사용자·날짜 단위로 부류 판정 (`user_day`).**
같은 사용자가 같은 날 desktop과 mobile을 둘 다 쓰면 `both`로 합쳐야 한다. 그래서 `(user_id, spend_date)`로 그룹핑하고:
- `COUNT(DISTINCT platform) = 2` → 두 플랫폼 다 썼다 → `'both'`
- 아니면(플랫폼 1개) `MIN(platform)`이 그 유일한 값(`desktop` 또는 `mobile`)을 준다.

`SUM(amount)`로 그날 그 사용자의 **총 지출**을 미리 합친다. `both`인 경우 desktop+mobile 금액이 모두 더해져 `both`의 금액이 된다.

**2·3·4단계 — 출력 뼈대(grid) 생성.**
"사용자가 0명인 조합도 0으로 출력"하려면, 실제 데이터로부터 집계만 해서는 안 된다(없는 조합은 행 자체가 안 생기기 때문). 그래서 **모든 날짜**(`dates`)와 **세 부류**(`platforms`)를 `CROSS JOIN`해 나와야 할 모든 `(spend_date, platform)` 조합을 먼저 만든다.

**5단계 — LEFT JOIN 후 집계.**
뼈대(`grid`)를 왼쪽에 두고 `user_day`를 `LEFT JOIN`한다. 매칭되는 사용자가 없으면 오른쪽이 전부 `NULL`이 되고:
- `COUNT(u.user_id)` — `NULL`은 세지 않으므로 **0**.
- `COALESCE(SUM(u.amount), 0)` — 합이 `NULL`이면 **0**으로 대체.

`GROUP BY g.spend_date, g.platform`으로 조합별 최종 행을 만든다.

### 핵심 개념
- **집합 판정**: `COUNT(DISTINCT platform)`로 사용자의 그날 플랫폼 사용 개수를 세어 both/단일을 구분.
- **빈 조합 채우기**: 존재해야 할 모든 조합을 `CROSS JOIN`으로 미리 만들고 실제 데이터를 `LEFT JOIN`하는 것이 정석. 집계 대상이 없어도 0행이 유지된다.
- `COUNT(컬럼)`은 NULL을 제외, `COALESCE`로 NULL 합계를 0 치환.

### ⚠️ 흔한 실수
- 1단계 없이 원본 `Spending`을 바로 집계하면 `both`가 desktop/mobile로 이중 계산되어 사용자 수·금액이 틀린다.
- grid(빈 조합 뼈대) 없이 `user_day`만 `GROUP BY`하면 사용자가 0인 `(날짜, 부류)` 행이 아예 안 나와 요구사항(0 출력)을 못 지킨다.
- `COUNT(*)`를 쓰면 LEFT JOIN에서 매칭 없는 행도 1로 세어 0이어야 할 곳이 1이 된다. 반드시 `COUNT(u.user_id)`처럼 **오른쪽 테이블 컬럼**을 센다.

### 💡 대안 / 응용
- 날짜 목록을 `Spending`에서 뽑는 대신 별도 달력(calendar) 테이블이 있으면 더 견고하다. 여기선 "데이터에 존재하는 날짜"만 요구하므로 `DISTINCT spend_date`로 충분하다.
- 부류가 desktop/mobile 2개뿐인 단순 버전이라면 CROSS JOIN 없이 조건부 집계(`SUM(CASE ...)`)로도 풀 수 있지만, `both`와 "0 출력"이 얽혀 grid 방식이 가장 안전하다.

---
