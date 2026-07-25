# STEP 2 — 정렬과 조건

> 정렬·필터링과 함께 MIN/GROUP BY, LIKE, CASE, 문자열 함수, DELETE 를 처음 만난다.

[← 목록으로](index.md)

---

## 1. [511] Game Play Analysis I

**링크**: https://leetcode.cn/problems/game-play-analysis-i/  
**학습 포인트**: `GROUP BY` 로 그룹을 나누고 `MIN()` 으로 그룹별 최솟값 구하기

### 문제

`Activity` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| player_id | int | 플레이어 식별자 |
| device_id | int | 접속 기기 |
| event_date | date | 접속(활동) 날짜 |
| games_played | int | 그 날 플레이한 게임 수 |

- 기본키(primary key)는 `(player_id, event_date)` 조합이다. 즉 한 플레이어는 하루에 한 행만 갖는다.

**요구사항**: 각 플레이어(`player_id`)의 **첫 로그인 날짜**(가장 이른 `event_date`)를 구한다. 결과 컬럼은 `player_id`, `first_login`.

### 정답

```sql
SELECT
    player_id,
    MIN(event_date) AS first_login
FROM Activity
GROUP BY player_id;
```

### 풀이 — 왜 이렇게 하는가

1. "플레이어별로 무언가를 구하라"는 요구는 곧 `GROUP BY player_id` 다. `GROUP BY` 는 같은 `player_id` 를 가진 행들을 하나의 그룹으로 묶는다.

2. "첫 로그인 날짜"는 그 그룹 안에서 `event_date` 가 가장 이른 값이다. 날짜도 크기 비교가 되므로 가장 이른 날짜 = 가장 작은 날짜이고, 이는 집계 함수 `MIN(event_date)` 로 구한다.

3. `GROUP BY player_id` 로 묶은 상태에서 `MIN(event_date)` 를 계산하면, MySQL 이 각 그룹마다 최솟값을 따로 계산해 준다. 그룹이 10개면 결과 행도 10개다.

4. `SELECT` 절에는 그룹의 기준 컬럼(`player_id`)과 집계 함수 결과(`MIN(...)`)만 넣는 것이 원칙이다. `first_login` 은 채점 요구 컬럼명이므로 `AS` 로 별칭을 붙인다.

### 핵심 개념

- `GROUP BY 컬럼`: 같은 값을 가진 행들을 한 그룹으로 묶는다.
- `MIN()` / `MAX()`: 그룹 내 최소/최대값. 숫자뿐 아니라 날짜·문자열에도 동작한다.
- 집계 함수는 `GROUP BY` 와 함께 쓰면 "그룹마다" 계산된다.

### ⚠️ 흔한 실수

- `GROUP BY` 없이 `SELECT player_id, MIN(event_date)` 만 쓰면 전체에서 최솟값 1행만 나온다. 플레이어별로 나누려면 반드시 `GROUP BY player_id` 가 필요하다.
- 별칭을 `first_login` 이 아닌 다른 이름으로 두면 채점에서 컬럼명 불일치로 틀린다.

---

## 2. [619] Biggest Single Number

**링크**: https://leetcode.cn/problems/biggest-single-number/  
**학습 포인트**: `GROUP BY ... HAVING COUNT = 1` 로 중복 없는 값만 걸러내고, 결과가 없을 때 `NULL` 을 반환하기 위해 서브쿼리로 감싸기

### 문제

`MyNumbers` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| num | int | 정수 (중복 있을 수 있음) |

- 이 테이블은 기본키가 없어 중복 행이 존재할 수 있다.

**요구사항**: 테이블에서 **딱 한 번만 나타나는 수(single number)** 중 **가장 큰 값**을 구한다. 그런 수가 하나도 없으면 `NULL` 을 반환한다. 결과 컬럼명은 `num`.

예시: `[8, 8, 3, 3, 1, 4, 5, 6]` → 한 번만 나온 수는 `1, 4, 5, 6` → 가장 큰 값 `6`.

### 정답

```sql
SELECT MAX(num) AS num
FROM (
    SELECT num
    FROM MyNumbers
    GROUP BY num
    HAVING COUNT(*) = 1
) AS t;
```

### 풀이 — 왜 이렇게 하는가

1. 먼저 "한 번만 나오는 수"를 찾아야 한다. `GROUP BY num` 으로 같은 값끼리 묶으면 각 그룹의 크기가 그 수의 등장 횟수다. `HAVING COUNT(*) = 1` 은 등장 횟수가 정확히 1인 그룹만 남긴다.

2. `WHERE` 가 아니라 `HAVING` 을 쓰는 이유: `COUNT(*)` 같은 집계 결과로 거르는 조건은 그룹을 만든 뒤에 적용해야 한다. `WHERE` 는 그룹핑 이전(개별 행)에 동작하므로 집계값을 조건에 쓸 수 없다. 그래서 그룹 이후 필터인 `HAVING` 을 쓴다.

3. 이 서브쿼리 `t` 는 "한 번만 나온 수들의 목록"이다. 여기에 `MAX(num)` 을 씌워 그중 가장 큰 값을 뽑는다.

4. **왜 서브쿼리로 감싸는가?** 만약 한 번만 나온 수가 하나도 없다면, 안쪽 서브쿼리는 0행을 반환한다. 그런데 바깥에서 `MAX()` 라는 집계 함수를 0행에 적용하면 결과는 `NULL` 이 된 **1행**이 나온다. 문제는 "없으면 NULL 을 출력"하라 했으므로 이 동작이 정확히 요구사항과 맞는다. 만약 서브쿼리 없이 `... HAVING COUNT(*)=1 ORDER BY num DESC LIMIT 1` 로 짜면, 조건에 맞는 수가 없을 때 아예 0행이 나와 `NULL` 행조차 출력되지 않아 오답이 된다.

### 핵심 개념

- `HAVING`: 그룹핑 후 집계 결과에 대한 필터. `WHERE` 는 그룹핑 전 개별 행 필터.
- 집계 함수(`MAX` 등)를 0행에 적용하면 `NULL` 1행이 나온다 → "없으면 NULL" 요구를 자연스럽게 충족.
- 파생 테이블(FROM 절 서브쿼리)에는 반드시 별칭(`AS t`)이 필요하다.

### ⚠️ 흔한 실수

- `HAVING` 대신 `WHERE COUNT(*)=1` 로 쓰면 문법 오류가 난다.
- `ORDER BY ... LIMIT 1` 방식은 "없으면 NULL" 요구를 만족하지 못한다(0행 출력).

---

## 3. [2356] Number of Unique Subjects Taught by Each Teacher

**링크**: https://leetcode.cn/problems/number-of-unique-subjects-taught-by-each-teacher/  
**학습 포인트**: `COUNT(DISTINCT ...)` 로 중복을 제거한 개수 세기

### 문제

`Teacher` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| teacher_id | int | 교사 식별자 |
| subject_id | int | 과목 식별자 |
| dept_id | int | 학과 식별자 |

- 기본키는 `(subject_id, dept_id)` 조합이다. 같은 과목을 서로 다른 학과에서 가르치면 여러 행이 생긴다.

**요구사항**: 각 교사(`teacher_id`)가 가르치는 **서로 다른 과목의 수**를 구한다. 결과 컬럼은 `teacher_id`, `cnt`.

### 정답

```sql
SELECT
    teacher_id,
    COUNT(DISTINCT subject_id) AS cnt
FROM Teacher
GROUP BY teacher_id;
```

### 풀이 — 왜 이렇게 하는가

1. "교사별"이므로 `GROUP BY teacher_id`.

2. 여기서 핵심은 **"서로 다른 과목의 수"** 라는 표현이다. 한 교사가 같은 과목(`subject_id`)을 여러 학과(`dept_id`)에서 가르치면 그 과목에 대한 행이 여러 개 생긴다. 단순히 `COUNT(*)` 를 쓰면 이 중복까지 세어 실제 과목 수보다 많아진다.

3. 따라서 `COUNT(DISTINCT subject_id)` 를 쓴다. `DISTINCT` 는 그룹 안에서 `subject_id` 의 중복을 제거한 뒤 개수를 센다. 결과적으로 "고유 과목 수"가 정확히 나온다.

### 핵심 개념

- `COUNT(*)`: 행의 개수 (중복 포함).
- `COUNT(DISTINCT 컬럼)`: 그 컬럼의 중복을 뺀 고유값 개수.
- `NULL` 은 `COUNT` 대상에서 제외된다(단, `COUNT(*)` 는 NULL 여부와 무관하게 행을 센다).

### ⚠️ 흔한 실수

- `COUNT(subject_id)` 만 쓰면 중복 과목까지 세어 과대 집계된다. `DISTINCT` 를 반드시 넣어야 한다.

---

## 4. [1141] User Activity for the Past 30 Days I

**링크**: https://leetcode.cn/problems/user-activity-for-the-past-30-days-i/  
**학습 포인트**: 날짜 **범위 조건(BETWEEN)** 과 `COUNT(DISTINCT user_id)`. 컬럼에 함수를 씌우지 않고 범위로 거르는 이유

### 문제

`Activity` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| user_id | int | 사용자 |
| session_id | int | 세션 |
| activity_date | date | 활동 날짜 |
| activity_type | enum | 'open_session', 'end_session', 'scroll_down', 'send_message' 중 하나 |

**요구사항**: 기준일 `2019-07-27` 을 포함해 **최근 30일간** 각 날짜(`activity_date`)별로 활동한 **활성 사용자 수(중복 제거)** 를 구한다. 활동이 있는 날짜만 출력하면 된다. 결과 컬럼은 `day`, `active_users`.

"최근 30일"은 `2019-07-27` 을 마지막 날로 포함하는 30일 구간, 즉 `2019-06-28` ~ `2019-07-27` 이다.

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

1. 최근 30일 구간을 계산한다. 마지막 날(`2019-07-27`)을 포함해 30일이므로 시작일은 `2019-07-27` 에서 29일을 뺀 `2019-06-28` 이다. `BETWEEN a AND b` 는 양 끝(a, b)을 모두 포함하므로 30일이 정확히 담긴다.

2. 날짜별 집계이므로 `GROUP BY activity_date`, 각 날짜의 활성 사용자는 **중복을 뺀** 사용자 수여야 하므로 `COUNT(DISTINCT user_id)`. 같은 사용자가 하루에 여러 번 활동해도 1명으로 세어야 하기 때문이다.

3. **왜 `activity_date` 에 함수를 씌우지 않고 범위(BETWEEN)로 거르는가?** `WHERE DATEDIFF('2019-07-27', activity_date) < 30` 처럼 컬럼에 함수를 걸어도 논리적으로는 맞다. 하지만 컬럼을 함수로 감싸면 그 컬럼에 인덱스가 있어도 **인덱스를 타지 못한다(sargable 하지 않다)**. 매 행마다 함수를 계산해 전체를 훑어야 하므로 느리다. 반면 `activity_date BETWEEN '...' AND '...'` 는 컬럼 원본을 상수 범위와 비교하므로 인덱스 범위 스캔이 가능해 훨씬 효율적이다. 조건을 짤 때는 "컬럼은 가공하지 말고, 상수 쪽을 미리 계산해 비교"하는 습관이 좋다.

### 핵심 개념

- `BETWEEN a AND b`: `a <= x AND x <= b` 와 동일. 양 끝을 포함한다.
- **Sargable 조건**: 컬럼을 함수로 감싸지 않아야 인덱스를 활용할 수 있다.
- `COUNT(DISTINCT user_id)`: 하루 안에서 중복 사용자를 1명으로.

### ⚠️ 흔한 실수

- `COUNT(user_id)` (DISTINCT 누락)로 하루에 여러 번 활동한 사용자를 중복으로 세는 실수.
- 30일 경계 계산 오류(28일이 아닌 27일/29일로 잡아 하루 어긋남).

### 💡 대안 / 응용

- 기준일을 하드코딩하지 않고 표현하려면 `WHERE activity_date BETWEEN DATE_SUB('2019-07-27', INTERVAL 29 DAY) AND '2019-07-27'` 처럼 쓸 수 있다. 상수만 가공하므로 여전히 sargable 하다.

---

## 5. [1527] Patients With a Condition

**링크**: https://leetcode.cn/problems/patients-with-a-condition/  
**학습 포인트**: `LIKE` 패턴 매칭과 **단어 경계 함정**(앞 공백 처리)

### 문제

`Patients` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| patient_id | int | 환자 식별자 |
| patient_name | varchar | 환자 이름 |
| conditions | varchar | 공백으로 구분된 질병 코드 목록 (예: 'DIAB100 MYOP200') |

**요구사항**: **Type I Diabetes** 를 가진 환자를 찾는다. 이 질병의 코드는 접두사 `DIAB1` 로 시작한다(예: `DIAB100`, `DIAB199`). `conditions` 는 여러 코드가 공백으로 이어진 문자열이므로, 그 안의 **어떤 코드든** `DIAB1` 로 시작하면 해당된다. 결과 컬럼은 `patient_id`, `patient_name`, `conditions`.

### 정답

```sql
SELECT
    patient_id,
    patient_name,
    conditions
FROM Patients
WHERE conditions LIKE 'DIAB1%'
   OR conditions LIKE '% DIAB1%';
```

### 풀이 — 왜 이렇게 하는가

1. `conditions` 는 한 칸의 코드가 아니라 `'DIAB100 MYOP200'` 처럼 **공백으로 구분된 여러 코드** 다. 우리가 찾는 것은 문자열 어딘가에 있는 "한 코드가 `DIAB1` 로 시작"하는 경우다.

2. 코드가 시작하는 위치는 두 가지다.
   - **문자열 맨 앞**: `conditions LIKE 'DIAB1%'` — 첫 글자부터 DIAB1 로 시작.
   - **중간(어떤 코드)**: 그 코드 앞에는 반드시 구분 공백이 있다 → `conditions LIKE '% DIAB1%'` (퍼센트 뒤에 **공백 한 칸** + DIAB1).

3. **왜 그냥 `LIKE '%DIAB1%'` 로 하면 안 되는가?** 이것이 이 문제의 핵심 함정이다. `'%DIAB1%'` 는 문자열 아무 위치에나 `DIAB1` 이 들어 있으면 매칭한다. 그러면 `SADIAB100` 같은 **다른 코드**도 `DIAB1` 을 부분 문자열로 포함하므로 잘못 매칭(오탐)된다. 우리가 원하는 것은 "코드의 **시작**이 DIAB1"인 경우뿐이다.

4. 코드의 시작을 보장하려면 그 앞이 "문자열의 처음"이거나 "공백"이어야 한다. 그래서 앞 공백을 명시한 `'% DIAB1%'` 와, 맨 앞을 처리하는 `'DIAB1%'` 를 `OR` 로 묶는다. 이렇게 하면 `SADIAB100` 은 걸러지고 `DIAB100`, `... DIAB199` 만 매칭된다.

### 핵심 개념

- `LIKE` 와일드카드: `%` 는 0글자 이상, `_` 는 정확히 1글자.
- 접두사 매칭 시 **단어 경계(공백/문자열 시작)** 를 반드시 고려한다.
- 두 시작 위치(맨 앞, 공백 뒤)를 `OR` 로 함께 처리.

### ⚠️ 흔한 실수

- `LIKE '%DIAB1%'` 만 쓰면 `SADIAB100`, `ADIAB1` 같은 값이 오탐된다.
- `'% DIAB1%'` 하나만 쓰면 문자열 맨 앞에 오는 `DIAB100` 을 놓친다(앞에 공백이 없으므로).

### 💡 대안 / 응용

- 정규식으로 한 줄에 처리할 수 있다: `WHERE conditions REGEXP '(^| )DIAB1'` — 문자열 시작(`^`) 또는 공백 뒤에 DIAB1 이 오는 경우를 뜻한다.

---

## 6. [1873] Calculate Special Bonus

**링크**: https://leetcode.cn/problems/calculate-special-bonus/  
**학습 포인트**: `CASE WHEN` 조건 분기, `MOD` 로 홀짝 판별, `NOT LIKE` 조합

### 문제

`Employees` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| employee_id | int | 직원 식별자 |
| name | varchar | 직원 이름 |
| salary | int | 급여 |

**요구사항**: 각 직원의 **특별 보너스**를 계산한다. 규칙은 다음과 같다.
- `employee_id` 가 **홀수**이고, `name` 이 **문자 'M' 으로 시작하지 않으면** → 보너스 = `salary`
- 그 외에는 → 보너스 = `0`

결과 컬럼은 `employee_id`, `bonus`. `employee_id` 오름차순으로 정렬한다.

### 정답

```sql
SELECT
    employee_id,
    CASE
        WHEN MOD(employee_id, 2) = 1 AND name NOT LIKE 'M%'
        THEN salary
        ELSE 0
    END AS bonus
FROM Employees
ORDER BY employee_id;
```

### 풀이 — 왜 이렇게 하는가

1. 행마다 "조건에 따라 다른 값"을 계산해야 하므로 `CASE WHEN ... THEN ... ELSE ... END` 를 쓴다. 이는 SQL 의 if-else 표현식이다.

2. 조건은 두 가지를 **모두** 만족해야 하므로 `AND` 로 잇는다.
   - **홀수 판별**: `MOD(employee_id, 2) = 1`. `MOD(a, 2)` 는 a 를 2로 나눈 나머지이고, 홀수면 1이다. (`employee_id % 2 = 1` 로 써도 같다.)
   - **M 으로 시작하지 않음**: `name NOT LIKE 'M%'`. `'M%'` 는 "M 으로 시작"을 뜻하고, `NOT` 을 붙여 그 반대를 만든다.

3. 두 조건이 모두 참이면 `THEN salary`(급여를 그대로 보너스로), 하나라도 거짓이면 `ELSE 0`.

4. 마지막으로 문제가 요구한 대로 `ORDER BY employee_id` 로 오름차순 정렬한다.

> 참고: MySQL 의 기본 문자열 대조(collation)는 대소문자를 구분하지 않으므로 `'M%'` 는 소문자 `m` 으로 시작하는 이름도 매칭한다. 이 문제 데이터는 대문자 M 만 다루므로 문제되지 않는다.

### 핵심 개념

- `CASE WHEN 조건 THEN 값 ELSE 값 END`: 행 단위 조건 분기 표현식.
- `MOD(n, 2) = 1`: 홀수 판별(짝수는 `= 0`).
- `NOT LIKE '패턴'`: 패턴에 맞지 **않는** 경우.

### ⚠️ 흔한 실수

- `name LIKE 'M%'` 로 조건을 반대로 쓰는 실수. "M 으로 시작하지 않으면"이므로 `NOT LIKE` 다.
- `ORDER BY` 를 빠뜨려 정렬 요구를 놓치는 경우.

### 💡 대안 / 응용

- MySQL 에서는 `IF(조건, 참값, 거짓값)` 로 더 짧게 쓸 수도 있다: `IF(MOD(employee_id,2)=1 AND name NOT LIKE 'M%', salary, 0)`.

---

## 7. [1667] Fix Names in a Table

**링크**: https://leetcode.cn/problems/fix-names-in-a-table/  
**학습 포인트**: 문자열 함수 `UPPER`, `LOWER`, `SUBSTRING`, `CONCAT` 조합

### 문제

`Users` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| user_id | int | 사용자 식별자 |
| name | varchar | 이름 (대소문자가 뒤섞여 있음, 예: 'aLICE') |

**요구사항**: 이름의 **첫 글자는 대문자, 나머지는 소문자**로 고쳐서 출력한다(예: `'aLICE'` → `'Alice'`). 결과 컬럼은 `user_id`, `name`. `user_id` 오름차순으로 정렬한다.

### 정답

```sql
SELECT
    user_id,
    CONCAT(
        UPPER(SUBSTRING(name, 1, 1)),
        LOWER(SUBSTRING(name, 2))
    ) AS name
FROM Users
ORDER BY user_id;
```

### 풀이 — 왜 이렇게 하는가

1. 이름을 "첫 글자"와 "나머지" 두 부분으로 나누어 각각 다르게 처리한 뒤 다시 붙이는 것이 전략이다.

2. **첫 글자 추출**: `SUBSTRING(name, 1, 1)` 은 1번째 위치에서 1글자를 잘라낸다(MySQL 의 문자열 인덱스는 1부터 시작). 이를 `UPPER(...)` 로 대문자화한다.

3. **나머지 추출**: `SUBSTRING(name, 2)` 는 2번째 위치부터 끝까지를 잘라낸다(세 번째 인자를 생략하면 끝까지). 이를 `LOWER(...)` 로 모두 소문자화한다.

4. **결합**: `CONCAT(대문자화한 첫 글자, 소문자화한 나머지)` 로 두 조각을 이어 붙이면 원하는 형태가 된다. 이름이 한 글자여도 `SUBSTRING(name, 2)` 가 빈 문자열을 반환하므로 안전하게 동작한다.

5. 마지막으로 `ORDER BY user_id` 로 정렬한다.

### 핵심 개념

- `SUBSTRING(str, pos, len)`: `pos`(1부터) 위치에서 `len` 글자. `len` 생략 시 끝까지.
- `UPPER()` / `LOWER()`: 대문자/소문자 변환.
- `CONCAT(a, b, ...)`: 문자열 이어 붙이기.

### ⚠️ 흔한 실수

- 문자열 시작 인덱스를 0으로 착각하는 것. MySQL 은 **1부터** 시작한다. `SUBSTRING(name, 0, 1)` 은 빈 문자열을 반환한다.
- 나머지 부분에 `LOWER` 를 씌우지 않으면 `'aLICE'` → `'ALICE'` 처럼 원래 대문자가 남아 오답이 된다.

### 💡 대안 / 응용

- `SUBSTRING` 대신 `LEFT(name, 1)` (왼쪽 1글자), `SUBSTR(name, 2)` 를 써도 동일하다. `SUBSTR` 은 `SUBSTRING` 의 별칭이다.

---

## 8. [196] Delete Duplicate Emails

**링크**: https://leetcode.cn/problems/delete-duplicate-emails/  
**학습 포인트**: `DELETE` 문과 **자기조인(self-join)**, 그리고 MySQL 에서 "삭제 대상 테이블을 서브쿼리로 직접 읽지 못하는" 제약과 우회

### 문제

`Person` 테이블

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | int | 기본키 |
| email | varchar | 이메일 |

**요구사항**: 같은 `email` 이 여러 개 있으면 **`id` 가 가장 작은 행만 남기고 나머지(중복)를 삭제**한다. 이 문제는 `SELECT` 가 아니라 실제로 테이블에서 행을 **DELETE** 하는 문제다.

예시: `(1, a@x)`, `(2, a@x)`, `(3, b@y)` → `id=2` 삭제, 남는 것은 `(1, a@x)`, `(3, b@y)`.

### 정답

```sql
DELETE p1
FROM Person AS p1
JOIN Person AS p2
    ON p1.email = p2.email
   AND p1.id > p2.id;
```

### 풀이 — 왜 이렇게 하는가

1. "같은 email 중 id 가 더 큰 것을 지운다"를 조건으로 표현해야 한다. 같은 테이블을 두 번 참조해 비교하는 **자기조인**을 쓴다. `p1`, `p2` 는 같은 `Person` 을 가리키는 두 별칭이다.

2. 조인 조건 `p1.email = p2.email AND p1.id > p2.id` 의 의미: "`p1` 과 같은 이메일을 가지면서 `p1` 보다 id 가 **작은** 다른 행 `p2` 가 존재한다." 이런 `p2` 가 하나라도 있다는 것은 `p1` 이 그 이메일의 최소 id 가 아니라는 뜻이다.

3. 따라서 조인 결과에 등장하는 `p1` 은 모두 "삭제해야 할 중복 행"이다. `DELETE p1 FROM ...` 은 그 `p1` 들만 삭제하라는 뜻이다. `DELETE` 바로 뒤에 어떤 별칭을 지울지 명시하는 것이 다중 테이블 DELETE 문법이다. 최소 id 행은 자기보다 작은 id 짝이 없어 조인 결과에 `p1` 으로 등장하지 않으므로 안전하게 살아남는다.

4. **왜 자기조인인가 — MySQL 의 제약**: 직관적으로는 아래처럼 쓰고 싶다.

   ```sql
   -- ❌ MySQL 에서 오류 (You can't specify target table 'Person' for update in FROM clause)
   DELETE FROM Person
   WHERE id NOT IN (
       SELECT MIN(id) FROM Person GROUP BY email
   );
   ```

   그러나 MySQL 은 **`DELETE`(또는 `UPDATE`)의 대상 테이블을, 같은 문장의 서브쿼리에서 직접 읽는 것을 금지**한다. 삭제하면서 동시에 그 테이블을 읽으면 결과가 불안정해질 수 있기 때문이다. 그래서 이 방식은 위 오류로 실패한다.

5. **우회 방법 두 가지**:
   - **자기조인(정답)**: 서브쿼리 없이 조인으로 조건을 표현해 제약을 피한다. 위 정답이 이 방식이다.
   - **파생 테이블로 한 번 더 감싸기**: 서브쿼리를 다시 서브쿼리로 감싸 별칭을 주면, MySQL 이 그 결과를 임시 테이블로 물리화(materialize)하여 "같은 테이블을 직접 읽는" 것으로 보지 않아 허용된다.

     ```sql
     DELETE FROM Person
     WHERE id NOT IN (
         SELECT keep_id FROM (
             SELECT MIN(id) AS keep_id FROM Person GROUP BY email
         ) AS t
     );
     ```

### 핵심 개념

- 다중 테이블 `DELETE p1 FROM ... JOIN ...`: `DELETE` 뒤에 삭제할 별칭을 지정한다.
- **자기조인**: 같은 테이블에 두 별칭을 붙여 행끼리 비교.
- MySQL 제약: DELETE/UPDATE 대상 테이블을 같은 문장의 서브쿼리에서 직접 참조 불가 → 파생 테이블로 감싸거나 조인으로 우회.

### ⚠️ 흔한 실수

- `DELETE FROM Person WHERE ... (SELECT ... FROM Person ...)` 처럼 대상 테이블을 서브쿼리에서 바로 읽어 오류를 내는 경우.
- 조인 조건을 `p1.id <> p2.id` 로만 두면(대소 비교 없이) 남길 행까지 삭제될 수 있다. 반드시 `p1.id > p2.id` 로 "더 큰 것"만 지정한다.

### 💡 대안 / 응용

- MySQL 8 이후에도 이 문제는 `DELETE` 라서 윈도우 함수로 곧장 지우긴 어렵지만, "남길 id 목록"을 만들 때 `ROW_NUMBER() OVER (PARTITION BY email ORDER BY id)` 를 활용하는 접근을 파생 테이블과 결합할 수 있다.

---
