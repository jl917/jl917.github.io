# 부록 A — NULL 완전 정복

> **학습 목표**
> - NULL 이 "값"이 아니라 **"값이 없는 상태"** 라는 것과, 그래서 생기는 **3값 논리(TRUE/FALSE/UNKNOWN)** 를 이해한다
> - **절(clause)마다 NULL 규칙이 다르다**는 사실을 표 하나로 정리한다 — 헷갈림의 진짜 원인이 여기 있다
> - `NOT IN` + NULL, 안티조인, `ON` vs `WHERE`, ROLLUP 의 NULL 을 **하나의 원리**로 설명할 수 있다
> - NULL 을 만났을 때 **어떤 도구를 꺼낼지**(`IS NULL` / `<=>` / `COALESCE` / `NOT EXISTS` / `GROUPING()`)를 즉시 고른다
>
> **선행 스텝**: [Step 05](../step-05-where-operators/index.md) · [Step 06](../step-06-aggregate-groupby/index.md) · [Step 07](../step-07-joins/index.md) · [Step 08](../step-08-subqueries/index.md)
> **예상 소요**: 60분

---

## A-0. 왜 헷갈리는가 — 규칙이 하나가 아니기 때문

Step 05~08 에서 NULL 함정을 하나씩 만났습니다. 그런데 배우고 나면 이런 상태가 됩니다.

- "NULL 은 NULL 과 같지 않다"고 배웠는데, `GROUP BY` 는 NULL 을 **한 그룹으로 묶는다**
- "NULL 끼리는 비교가 안 된다"고 배웠는데, `DISTINCT` 와 `UNION` 은 NULL 을 **중복으로 제거한다**
- `UNIQUE` 제약은 "중복 금지"인데 NULL 은 **몇 개든 허용**한다
- `WHERE` 는 UNKNOWN 을 **버리는데**, `CHECK` 제약은 UNKNOWN 을 **통과**시킨다

전부 사실입니다. 서로 모순돼 보이지만 모순이 아닙니다. **NULL 에 대한 규칙이 딱 두 종류로 나뉘기 때문**입니다.

| 관점 | 규칙 | 적용되는 곳 |
|---|---|---|
| **비교(comparison)** 관점 | NULL 과의 비교는 **UNKNOWN** — 같지도 다르지도 않다 | `WHERE` · `ON` · `HAVING` · `CASE WHEN` · `IN` / `NOT IN` · `CHECK` |
| **그룹핑(grouping)** 관점 | NULL 끼리는 **같은 값 취급** — 하나로 묶인다 | `GROUP BY` · `DISTINCT` · `UNION` · `ORDER BY` · 인덱스 · `<=>` |

**이 부록은 이 두 줄짜리 표를 몸에 새기는 것이 목표입니다.** 앞으로 나올 모든 함정은 "지금 나는 비교를 하고 있나, 그룹핑을 하고 있나?" 로 설명됩니다.

> 💡 왜 두 종류인가: 표준 SQL 이 그렇게 정했습니다. 비교는 "모르는 값끼리는 답할 수 없다"는 논리를 따르고, 그룹핑·정렬은 "결과를 만들어야 하니 NULL 도 하나의 자리로 다룬다"는 실용을 따릅니다. 원리가 아니라 **약속**이니, 외우는 게 맞습니다.

---

## A-1. NULL 은 값이 아니라 상태다

```sql
SELECT
    NULL = NULL   AS `NULL = NULL`,
    NULL <> NULL  AS `NULL <> NULL`,
    NULL = 0      AS `NULL = 0`,
    '' = NULL     AS `'' = NULL`,
    '' IS NULL    AS `'' IS NULL`,
    LENGTH('')    AS `LENGTH('')`;
```

**결과**
```
+-------------+--------------+----------+-----------+------------+------------+
| NULL = NULL | NULL <> NULL | NULL = 0 | '' = NULL | '' IS NULL | LENGTH('') |
+-------------+--------------+----------+-----------+------------+------------+
|        NULL |         NULL |     NULL |      NULL |          0 |          0 |
+-------------+--------------+----------+-----------+------------+------------+
```

세 가지를 확실히 구분하세요. **완전히 다른 것들입니다.**

| | 의미 | `IS NULL` | 저장 크기 | 집계에 포함 |
|---|---|---|---|---|
| `NULL` | 값을 **모른다 / 없다** | 참 | 별도 NULL 비트 | 제외 |
| `0` | 숫자 **영** | 거짓 | 값 그대로 | 포함 |
| `''` | **빈 문자열**(길이 0) | 거짓 | 값 그대로 | 포함 |

> ⚠️ **함정**: Oracle 은 `''` 를 NULL 로 취급하지만 **MySQL 은 다릅니다.** `''` 는 어엿한 값입니다. Oracle 경험자가 MySQL 로 넘어와 `WHERE name IS NULL` 로 빈 값을 찾다가 못 찾는 일이 흔합니다.

### NULL 은 전염된다

```sql
SELECT
    NULL + 1              AS `NULL + 1`,
    1 / NULL              AS `1 / NULL`,
    CONCAT('a', NULL)     AS `CONCAT`,
    CONCAT_WS('-','a',NULL,'b') AS `CONCAT_WS`,
    LENGTH(NULL)          AS `LENGTH(NULL)`,
    UPPER(NULL)           AS `UPPER(NULL)`;
```

**결과**
```
+----------+----------+--------+-----------+--------------+-------------+
| NULL + 1 | 1 / NULL | CONCAT | CONCAT_WS | LENGTH(NULL) | UPPER(NULL) |
+----------+----------+--------+-----------+--------------+-------------+
|     NULL |     NULL | NULL   | a-b       |         NULL | NULL        |
+----------+----------+--------+-----------+--------------+-------------+
```

거의 모든 연산자·함수는 인자에 NULL 이 하나만 있어도 **결과 전체가 NULL** 이 됩니다. 예외적으로 NULL 을 무시하는 것들이 이 부록의 관전 포인트입니다 — `CONCAT_WS`, 집계함수, `COALESCE` 계열.

> 💡 **실무 팁**: 주소를 조립할 때 `CONCAT(city, ' ', street)` 는 `street` 가 NULL 이면 주소 전체가 사라집니다. **`CONCAT_WS(' ', city, street)`** 를 쓰세요. 구분자 함수는 NULL 인자를 **건너뜁니다**(첫 인자인 구분자가 NULL 이면 그때만 전체 NULL).

---

## A-2. 3값 논리 — TRUE / FALSE / **UNKNOWN**

NULL 이 비교에 끼면 결과는 TRUE 도 FALSE 도 아닌 **UNKNOWN** 입니다. 그래서 AND/OR 진리표가 2값 논리보다 큽니다.

```sql
SELECT TRUE AND NULL AS `T AND ?`, FALSE AND NULL AS `F AND ?`, NULL AND NULL AS `? AND ?`;
SELECT TRUE OR  NULL AS `T OR  ?`, FALSE OR  NULL AS `F OR  ?`, NULL OR  NULL AS `? OR  ?`;
SELECT NOT NULL AS `NOT ?`;
```

**결과**
```
+---------+---------+---------+
| T AND ? | F AND ? | ? AND ? |
+---------+---------+---------+
|    NULL |       0 |    NULL |
+---------+---------+---------+
+---------+---------+---------+
| T OR  ? | F OR  ? | ? OR  ? |
+---------+---------+---------+
|       1 |    NULL |    NULL |
+---------+---------+---------+
+-------+
| NOT ? |
+-------+
|  NULL |
+-------+
```

표로 정리하면 이렇습니다. (`?` = UNKNOWN)

| AND | TRUE | FALSE | ? |
|---|---|---|---|
| **TRUE** | TRUE | FALSE | **?** |
| **FALSE** | FALSE | FALSE | **FALSE** |
| **?** | **?** | **FALSE** | **?** |

| OR | TRUE | FALSE | ? |
|---|---|---|---|
| **TRUE** | TRUE | TRUE | **TRUE** |
| **FALSE** | TRUE | FALSE | **?** |
| **?** | **TRUE** | **?** | **?** |

**외울 것은 두 줄뿐입니다.**

- `AND` 에서는 **FALSE 가 흡수한다** — `FALSE AND ?` = FALSE. 하나라도 확실히 거짓이면 전체가 거짓이니까.
- `OR` 에서는 **TRUE 가 흡수한다** — `TRUE OR ?` = TRUE. 하나라도 확실히 참이면 전체가 참이니까.
- 그 외에 UNKNOWN 이 끼면 → **UNKNOWN**. 그리고 `NOT UNKNOWN` 은 여전히 **UNKNOWN**(모르는 것의 반대는 여전히 모르는 것).

이 두 줄이 A-5 의 `NOT IN` 함정과 A-4 의 부정 조건 누락을 **동시에** 설명합니다.

---

## A-3. ★ 절마다 다른 NULL 규칙 (이 부록의 핵심 표)

같은 NULL 인데 어디에 쓰느냐에 따라 취급이 다릅니다. **이 표가 헷갈림의 정체입니다.**

| 절 / 기능 | NULL 취급 | 결과 |
|---|---|---|
| `WHERE` | **TRUE 인 행만** 통과. FALSE·UNKNOWN 모두 탈락 | NULL 행이 **조용히 사라짐** |
| `ON` (조인) | 위와 동일. 단 탈락해도 LEFT/RIGHT 는 **NULL 확장으로 행을 남김** | A-8 |
| `HAVING` | `WHERE` 와 동일 | UNKNOWN 그룹 탈락 |
| `CASE WHEN` | 조건이 UNKNOWN 이면 그 분기 **불성립** → 다음 WHEN, 없으면 `ELSE`, `ELSE` 도 없으면 **NULL** | A-10 |
| `CHECK` 제약 | **UNKNOWN 을 통과시킨다** (위반으로 보지 않음) | A-12 |
| `GROUP BY` | NULL 끼리 **한 그룹** | A-7 |
| `DISTINCT` | NULL 끼리 **중복** → 하나만 남김 | A-7 |
| `UNION` (DISTINCT) | NULL 끼리 **중복** → 하나만 남김 | A-7 |
| `ORDER BY` | NULL 을 **가장 작은 값**으로 정렬 (ASC 면 맨 앞) | A-9 |
| `UNIQUE` 인덱스 | NULL 은 **몇 개든 허용** (서로 다르다고 봄) | A-12 |
| 집계함수 | `COUNT(*)` 빼고 **전부 NULL 무시** | A-6 |
| `<=>` | NULL 끼리 **같다**(1) | A-4 |

**한 문장 요약**: `WHERE` 계열(비교)에서는 **NULL ≠ NULL**, `GROUP BY` 계열(그룹핑)에서는 **NULL = NULL**. 이 한 줄이면 위 표의 90%가 복원됩니다.

---

## A-4. NULL 을 찾는 법 — `IS NULL` 과 `<=>`

`=` 로는 NULL 을 절대 찾을 수 없습니다. 에러도 안 나서 더 위험합니다.

```sql
SELECT
    (SELECT COUNT(*) FROM customers WHERE phone =  NULL) AS `phone = NULL`,
    (SELECT COUNT(*) FROM customers WHERE phone IS NULL) AS `phone IS NULL`,
    (SELECT COUNT(*) FROM customers WHERE phone <=> NULL) AS `phone <=> NULL`;
```

**결과**
```
+--------------+---------------+----------------+
| phone = NULL | phone IS NULL | phone <=> NULL |
+--------------+---------------+----------------+
|            0 |             3 |              3 |
+--------------+---------------+----------------+
```

| 도구 | 용도 |
|---|---|
| `IS NULL` / `IS NOT NULL` | **기본.** NULL 여부 판정. 인덱스도 탄다(A-9) |
| `<=>` (NULL-safe 등호) | 양쪽이 NULL 이어도 정상 비교. **절대 UNKNOWN 을 반환하지 않음**(항상 0 또는 1) |
| `ISNULL(expr)` | `expr IS NULL` 과 동일. MySQL 전용 함수형 |

`<=>` 의 진짜 쓸모는 **바인딩 파라미터가 NULL 일 수 있는 검색**과 **변경 감지**입니다.

```sql
-- 애플리케이션: ? 에 값이 오면 그 값을, NULL 이 오면 NULL 인 행을 찾는다
SELECT * FROM customers WHERE phone <=> ?;

-- 배치: 예전 값과 새 값이 "정말" 다른가? (둘 다 NULL 이면 '안 바뀜'으로 판정)
SELECT * FROM staging s JOIN target t ON t.id = s.id WHERE NOT (s.phone <=> t.phone);
```

> 💡 `=` 로 같은 걸 하려면 `WHERE (phone = ? OR (? IS NULL AND phone IS NULL))` 같은 흉물이 됩니다. 표준 SQL 의 `IS NOT DISTINCT FROM` 이 같은 역할인데 MySQL 에는 없고, **`<=>` 가 MySQL 의 답**입니다.

### 부정 조건에서 NULL 이 새어나간다

전체 30명 중 1명을 제외하면 29명이어야 할 것 같지만:

```sql
SELECT
    (SELECT COUNT(*) FROM customers WHERE phone <> '010-1000-0001') AS `<> 만`,
    (SELECT COUNT(*) FROM customers WHERE phone <> '010-1000-0001' OR phone IS NULL) AS `NULL 포함`;
```

**결과**
```
+--------+-------------+
| <> 만  | NULL 포함   |
+--------+-------------+
|     26 |          29 |
+--------+-------------+
```

phone 이 NULL 인 3명은 `NULL <> '010-...'` = UNKNOWN 이라 `WHERE` 에서 탈락했습니다. **`<>`, `NOT LIKE`, `NOT IN`, `NOT BETWEEN` 등 모든 부정 조건에서 동일하게 일어납니다.**

> ⚠️ **함정**: "제외" 요구사항을 받으면 반드시 되물으세요 — **"값이 없는(NULL) 행은 포함인가요, 제외인가요?"** 기획서에는 거의 항상 이 내용이 빠져 있습니다. 답에 따라 `OR col IS NULL` 을 붙일지가 결정됩니다.

---

## A-5. `NOT IN` + NULL — 왜 결과가 통째로 사라지는가

Step 05·08 의 최대 함정을, 이제 A-2 의 진리표로 **완전히** 설명할 수 있습니다.

```sql
SELECT
    3 IN (1,2,NULL)     AS `3 IN (1,2,?)`,
    1 IN (1,2,NULL)     AS `1 IN (1,2,?)`,
    3 NOT IN (1,2,NULL) AS `3 NOT IN (1,2,?)`,
    1 NOT IN (1,2,NULL) AS `1 NOT IN (1,2,?)`;
```

**결과**
```
+--------------+--------------+------------------+------------------+
| 3 IN (1,2,?) | 1 IN (1,2,?) | 3 NOT IN (1,2,?) | 1 NOT IN (1,2,?) |
+--------------+--------------+------------------+------------------+
|         NULL |            1 |             NULL |                0 |
+--------------+--------------+------------------+------------------+
```

전개해 보면 답이 보입니다.

| 식 | 전개 | 진리표 적용 | 결과 |
|---|---|---|---|
| `1 IN (1,2,NULL)` | `1=1 OR 1=2 OR 1=NULL` | `TRUE OR FALSE OR ?` → **TRUE 가 흡수** | **1** ✅ |
| `3 IN (1,2,NULL)` | `3=1 OR 3=2 OR 3=NULL` | `FALSE OR FALSE OR ?` → 흡수 없음 | **NULL** |
| `1 NOT IN (1,2,NULL)` | `1<>1 AND 1<>2 AND 1<>NULL` | `FALSE AND ... ` → **FALSE 가 흡수** | **0** ✅ |
| `3 NOT IN (1,2,NULL)` | `3<>1 AND 3<>2 AND 3<>NULL` | `TRUE AND TRUE AND ?` → 흡수 없음 | **NULL** |

**핵심**: `IN` 은 **찾으면** TRUE 로 확정되고(흡수), `NOT IN` 은 **못 찾으면** TRUE 여야 하는데 NULL 때문에 확정을 못 합니다. 그래서

- `IN` : 매칭되는 행은 정상적으로 나옴 → **NULL 이 있어도 상대적으로 안전**
- `NOT IN` : "없는 것"을 찾는 게 목적인데 그게 전부 UNKNOWN → **결과 0행**

실제로 확인합니다. `employees.manager_id` 는 CEO 한 명 때문에 NULL 을 포함합니다.

```sql
SELECT
    (SELECT COUNT(*) FROM employees e
      WHERE e.employee_id NOT IN (SELECT manager_id FROM employees)) AS `NOT IN (버그)`,
    (SELECT COUNT(*) FROM employees e
      WHERE e.employee_id NOT IN (SELECT manager_id FROM employees WHERE manager_id IS NOT NULL)) AS `NULL 제거`,
    (SELECT COUNT(*) FROM employees e
      WHERE NOT EXISTS (SELECT 1 FROM employees m WHERE m.manager_id = e.employee_id)) AS `NOT EXISTS`,
    (SELECT COUNT(*) FROM employees e
      LEFT JOIN employees m ON m.manager_id = e.employee_id
      WHERE m.employee_id IS NULL) AS `LEFT JOIN IS NULL`;
```

**결과**
```
+-----------------+-------------+------------+-------------------+
| NOT IN (버그)   | NULL 제거   | NOT EXISTS | LEFT JOIN IS NULL |
+-----------------+-------------+------------+-------------------+
|               0 |          10 |         10 |                10 |
+-----------------+-------------+------------+-------------------+
```

정답은 **10**. `NOT IN` 만 0 입니다. 에러도 경고도 없습니다.

### 안티조인 3형제 — 무엇을 쓸 것인가

| 방법 | NULL 안전 | 언제 |
|---|---|---|
| `NOT EXISTS` | ✅ **안전** | **기본값.** 상관 조건이 NULL 이면 그냥 "매칭 안 됨"으로 처리됨 |
| `LEFT JOIN ... IS NULL` | ⚠️ 조건부 | 짝 없는 쪽의 컬럼도 SELECT 해야 할 때. **`IS NULL` 대상은 반드시 NOT NULL 컬럼(보통 PK)** — A-8 |
| `NOT IN (서브쿼리)` | ❌ **위험** | 쓰지 말 것. **상수 리스트**(`NOT IN (1,2,3)`)에만 |

> ⚠️ **함정**: 지금 잘 도는 `NOT IN` 쿼리도, **그 컬럼에 NULL 이 처음 들어오는 날 조용히 0건**이 됩니다. 배치 잡이 어느 날부터 아무 일도 안 하기 시작하고, 아무도 모릅니다. `NOT EXISTS` 를 습관으로 만드세요.

### 참고 — 서브쿼리가 빈 결과일 때

```sql
SELECT 5 IN     (SELECT customer_id FROM customers WHERE 1=0) AS in_empty,
       5 NOT IN (SELECT customer_id FROM customers WHERE 1=0) AS notin_empty;
```

**결과**
```
+----------+-------------+
| in_empty | notin_empty |
+----------+-------------+
|        0 |           1 |
+----------+-------------+
```

**빈 집합은 UNKNOWN 이 아니라 확실한 FALSE/TRUE 입니다.** "결과가 0행"과 "NULL 이 섞임"은 완전히 다른 상황이라는 점을 기억하세요.

---

## A-6. 집계함수와 NULL

**`COUNT(*)` 를 제외한 모든 집계함수는 NULL 을 건너뜁니다.**

```sql
SELECT
    COUNT(*)                   AS `COUNT(*)`,
    COUNT(phone)               AS `COUNT(phone)`,
    COUNT(DISTINCT phone)      AS `COUNT(DISTINCT phone)`,
    MIN(phone)                 AS `MIN(phone)`
FROM customers;
```

**결과**
```
+----------+--------------+-----------------------+---------------+
| COUNT(*) | COUNT(phone) | COUNT(DISTINCT phone) | MIN(phone)    |
+----------+--------------+-----------------------+---------------+
|       30 |           27 |                    27 | 010-1000-0001 |
+----------+--------------+-----------------------+---------------+
```

| 형태 | 세는 것 |
|---|---|
| `COUNT(*)` | **행의 개수.** NULL 상관없이 무조건 |
| `COUNT(col)` | col 이 **NULL 이 아닌** 행의 개수 |
| `COUNT(DISTINCT col)` | col 의 서로 다른 값의 개수 (**NULL 제외**) |

### AVG 의 분모가 달라진다

`AVG(col)` 은 정확히 **`SUM(col) / COUNT(col)`** 입니다. 분모에서 NULL 이 빠지므로, "0 을 평균에 넣을 것인가"가 결과를 바꿉니다.

```sql
SELECT
    AVG(points)            AS `AVG(0 포함)`,
    AVG(NULLIF(points, 0)) AS `AVG(0을 NULL로)`,
    SUM(points) / COUNT(*) AS `SUM / COUNT(*)`
FROM customers;
```

**결과**
```
+---------------+-------------------+----------------+
| AVG(0 포함)   | AVG(0을 NULL로)   | SUM / COUNT(*) |
+---------------+-------------------+----------------+
|     5959.0000 |         6621.1111 |      5959.0000 |
+---------------+-------------------+----------------+
```

### 빈 집합 — SUM 은 NULL, COUNT 는 0

```sql
SELECT SUM(points) AS s, AVG(points) AS a, MAX(points) AS m,
       COUNT(points) AS cp, COUNT(*) AS ca
FROM customers WHERE 1 = 0;
```

**결과**
```
+------+------+------+----+----+
| s    | a    | m    | cp | ca |
+------+------+------+----+----+
| NULL | NULL | NULL |  0 |  0 |
+------+------+------+----+----+
```

**행이 하나도 없어도 집계 쿼리는 1행을 돌려줍니다.** 그리고 `SUM`/`AVG`/`MAX` 는 `0` 이 아니라 **NULL** 입니다.

> ⚠️ **함정**: 매출 합계를 `SUM(amount)` 로 뽑아 그대로 화면에 뿌리면, 거래가 없는 날 **`0` 이 아니라 빈칸/NULL** 이 나옵니다. 그 값을 다시 계산에 쓰면(`SUM(a) - SUM(b)`) 전염되어 전체가 NULL 이 됩니다. **`COALESCE(SUM(amount), 0)`** 을 습관으로 쓰세요. 반면 `COUNT` 는 언제나 0 이상이라 감쌀 필요가 없습니다.

`GROUP_CONCAT` 도 마찬가지로 NULL 을 건너뜁니다 — 값 3개 중 1개가 NULL 이면 2개만 이어붙고, **전부 NULL 이면 결과가 빈 문자열이 아니라 NULL** 입니다.

---

## A-7. GROUP BY · DISTINCT · UNION 은 NULL 을 "같다"고 본다

여기서 규칙이 뒤집힙니다. **비교가 아니라 그룹핑이기 때문입니다.**

```sql
SELECT parent_id, COUNT(*) AS cnt FROM categories GROUP BY parent_id ORDER BY parent_id;
```

**결과**
```
+-----------+-----+
| parent_id | cnt |
+-----------+-----+
|      NULL |   5 |    ← NULL 5건이 흩어지지 않고 한 그룹으로
|         1 |   3 |
|         2 |   3 |
|         3 |   2 |
|         4 |   2 |
|         5 |   2 |
+-----------+-----+
```

`NULL = NULL` 이 UNKNOWN 인데도 **NULL 5건이 한 그룹**입니다. `DISTINCT` 와 `UNION` 도 동일하게 NULL 을 하나로 접습니다.

```sql
SELECT COUNT(DISTINCT phone) AS distinct_phone, COUNT(*) AS total FROM customers;
-- → 27, 30  : NULL 3건은 DISTINCT 결과에 아예 포함되지 않는다(COUNT 가 NULL 을 세지 않으므로)

SELECT COUNT(*) AS rows_after_union FROM (
    SELECT phone FROM customers WHERE phone IS NULL
    UNION                                    -- UNION = 중복 제거
    SELECT phone FROM customers WHERE phone IS NULL
) t;
```

**결과**
```
+------------------+
| rows_after_union |
+------------------+
|                1 |    ← NULL 6건(3+3)이 1건으로 접혔다
+------------------+
```

### ROLLUP 의 NULL — "진짜 NULL"과 "소계 표시 NULL"

`WITH ROLLUP` 은 소계·총계 행의 그룹 컬럼을 **NULL 로 채웁니다.** 그런데 그 컬럼에 원래 NULL 데이터가 있었다면 **둘을 구분할 수 없습니다.**

```sql
SELECT city AS 도시, COUNT(*) AS 고객수, SUM(points) AS 포인트합
FROM customers WHERE city IN ('서울','부산')
GROUP BY city WITH ROLLUP;
```

**결과**
```
+--------+-----------+--------------+
| 도시   | 고객수    | 포인트합     |
+--------+-----------+--------------+
| 부산   |         5 |        17450 |
| 서울   |        10 |       117300 |
| NULL   |        15 |       134750 |   ← 이 NULL 은 "총계"인가 "도시 미상"인가?
+--------+-----------+--------------+
```

MySQL 8 의 **`GROUPING(col)`** 이 이걸 해결합니다. 그 행이 col 에 대한 **소계 행이면 1**, 실제 데이터 행이면 **0** 입니다.

```sql
SELECT
    IF(GROUPING(city) = 1, '── 전체 ──', COALESCE(city, '(도시 미상)')) AS 도시,
    COUNT(*)       AS 고객수,
    GROUPING(city) AS g_city
FROM customers WHERE city IN ('서울','부산')
GROUP BY city WITH ROLLUP;
```

**결과**
```
+----------------------+-----------+--------+
| 도시                 | 고객수    | g_city |
+----------------------+-----------+--------+
| 부산                 |         5 |      0 |
| 서울                 |        10 |      0 |
| ── 전체 ──           |        15 |      1 |
+----------------------+-----------+--------+
```

> 💡 **판별 공식**: `GROUPING(col) = 1` → **소계/총계 행**. `GROUPING(col) = 0 AND col IS NULL` → **진짜 NULL 데이터**. ROLLUP 리포트를 만들 땐 이 두 가지를 항상 따로 라벨링하세요. MySQL 5.7 에는 `GROUPING()` 이 없어서 이 구분이 사실상 불가능했습니다.

자세한 내용은 [Step 06 — 6-8](../step-06-aggregate-groupby/index.md) 참고.

---

## A-8. JOIN 과 NULL — NULL 확장(NULL extension)

`LEFT JOIN` 에서 오른쪽에 짝이 없으면, MySQL 은 **오른쪽 컬럼을 전부 NULL 로 채운 가짜 행**을 만들어 왼쪽 행을 살립니다. 이걸 **NULL 확장**이라고 합니다. Step 07 의 두 함정이 전부 여기서 나옵니다.

```sql
-- 고객 3명, 그중 2명만 주문이 있다. 2번 고객의 주문에는 memo 가 NULL.
SELECT c.id, c.name, o.order_id, o.memo
FROM s26_cust c LEFT JOIN s26_order_memo o ON o.cust_id = c.id
ORDER BY c.id;
```

**결과**
```
+----+------+----------+--------+
| id | name | order_id | memo   |
+----+------+----------+--------+
|  1 | 김   |      100 | 급송   |
|  2 | 이   |      101 | NULL   |   ← 짝은 있다. memo 값이 NULL 일 뿐
|  3 | 박   |     NULL | NULL   |   ← 짝이 없다 (NULL 확장)
+----+------+----------+--------+
```

**2번과 3번은 `memo` 만 보면 구분이 안 됩니다.** 이 한 줄이 안티조인 함정의 전부입니다.

```sql
-- (A) 올바른 안티조인 : NOT NULL 컬럼(PK)으로 판정
SELECT c.id, c.name FROM s26_cust c
LEFT JOIN s26_order_memo o ON o.cust_id = c.id
WHERE o.order_id IS NULL;

-- (B) 잘못된 안티조인 : NULL 가능 컬럼으로 판정
SELECT c.id, c.name FROM s26_cust c
LEFT JOIN s26_order_memo o ON o.cust_id = c.id
WHERE o.memo IS NULL;
```

**결과**
```
(A) 올바름                (B) 잘못됨
+----+------+            +----+------+
| id | name |            | id | name |
+----+------+            +----+------+
|  3 | 박   |            |  2 | 이   |   ← 주문이 있는데도 딸려왔다!
+----+------+            |  3 | 박   |
                         +----+------+
```

> ⚠️ **함정**: `LEFT JOIN ... IS NULL` 안티조인에서 **`IS NULL` 을 거는 컬럼은 반드시 NOT NULL 이어야 합니다.** 실무에서는 조인 대상 테이블의 **PK** 를 쓰는 것이 정석입니다(`WHERE o.order_id IS NULL`). 아무 컬럼이나 골랐다가 "짝은 있는데 그 컬럼이 NULL 인 행"까지 딸려오면, 이건 **에러 없이 틀린 답**입니다.

### ON vs WHERE — NULL 확장이 살아남느냐 죽느냐

```sql
-- (A) 조건을 ON 에 : 매칭 단계에서만 적용 → 왼쪽 전부 보존(NULL 확장)
SELECT COUNT(*) AS 조건이_ON FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.status = 'DELIVERED';

-- (B) 조건을 WHERE 에 : 조인이 끝난 뒤 적용 → NULL 확장 행이 탈락
SELECT COUNT(*) AS 조건이_WHERE FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.status = 'DELIVERED';
```

**결과**
```
+--------------+     +-----------------+
| 조건이_ON    |     | 조건이_WHERE    |
+--------------+     +-----------------+
|          258 |     |             240 |
+--------------+     +-----------------+
```

**258 vs 240.** (B) 에서 NULL 확장된 18행의 `status` 는 NULL 이고, `NULL = 'DELIVERED'` 는 **UNKNOWN** 이라 `WHERE` 에서 탈락했습니다. **LEFT JOIN 이 INNER JOIN 으로 퇴화**한 것입니다.

> ⚠️ **핵심 규칙**: LEFT JOIN 에서 **오른쪽 테이블 조건은 `ON` 에, 왼쪽 테이블 조건은 `WHERE` 에.**
> 유일한 예외가 안티조인(`WHERE 오른쪽PK IS NULL`) — 이건 **NULL 확장을 일부러 노리는** 경우라 정상입니다.

자세한 내용은 [Step 07 — 7-4, 7-5](../step-07-joins/index.md) 참고.

---

## A-9. 정렬과 인덱스에서의 NULL

### ORDER BY — NULL 이 가장 작다

```sql
SELECT customer_id, name, phone FROM customers ORDER BY phone LIMIT 4;
```

**결과**
```
+-------------+-----------+---------------+
| customer_id | name      | phone         |
+-------------+-----------+---------------+
|          28 | 심준호    | NULL          |   ← NULL 이 맨 앞
|          14 | 남규리    | NULL          |
|           7 | 윤대현    | NULL          |
|           1 | 김민수    | 010-1000-0001 |
+-------------+-----------+---------------+
```

MySQL 은 NULL 을 **모든 값보다 작다**고 봅니다. 따라서 `ASC` 면 맨 앞, `DESC` 면 맨 뒤입니다.

표준 SQL 의 `NULLS FIRST` / `NULLS LAST` 는 **MySQL 에 없습니다.** 흉내내려면 정렬 키를 하나 더 얹습니다.

```sql
-- NULL 을 맨 뒤로 (NULLS LAST 흉내)
SELECT customer_id, name, phone FROM customers
ORDER BY phone IS NULL, phone LIMIT 4;
```

**결과**
```
+-------------+-----------+---------------+
| customer_id | name      | phone         |
+-------------+-----------+---------------+
|           1 | 김민수    | 010-1000-0001 |
|           2 | 이지은    | 010-1000-0002 |
|           3 | 박철수    | 010-1000-0003 |
|           4 | 최영희    | 010-1000-0004 |
+-------------+-----------+---------------+
```

`phone IS NULL` 은 0/1 을 돌려주므로, 이 컬럼으로 먼저 정렬하면 **값이 있는 행(0)이 앞**으로 옵니다. 숫자 컬럼이라면 `ORDER BY -col DESC` 같은 요령도 있지만, 의도가 드러나는 `IS NULL` 방식을 권합니다.

> ⚠️ 이 방식은 **정렬 키가 표현식**이라 인덱스 정렬을 못 씁니다. 큰 테이블 + 페이징에서는 `filesort` 가 붙으니, 애초에 해당 컬럼을 `NOT NULL DEFAULT ...` 로 설계하는 편이 낫습니다(A-13).

### 인덱스 — `IS NULL` 도 인덱스를 탄다

흔한 오해입니다. **MySQL 의 B+Tree 인덱스는 NULL 도 저장하고, `IS NULL` 검색에 인덱스를 씁니다.**

```sql
EXPLAIN SELECT employee_id, name FROM employees WHERE manager_id IS NULL;
```

**결과**
```
+----+-------------+-----------+------+-----------------------+-----------------------+---------+-------+------+-----------------------+
| id | select_type | table     | type | possible_keys         | key                   | key_len | ref   | rows | Extra                 |
+----+-------------+-----------+------+-----------------------+-----------------------+---------+-------+------+-----------------------+
|  1 | SIMPLE      | employees | ref  | idx_employees_manager | idx_employees_manager | 5       | const |    1 | Using index condition |
+----+-------------+-----------+------+-----------------------+-----------------------+---------+-------+------+-----------------------+
```

`type = ref`, `key = idx_employees_manager`. `manager_id = 1` 로 조회할 때와 **똑같은 접근 방식**입니다. (Oracle 의 일반 B-Tree 인덱스는 NULL 을 저장하지 않아 이게 안 됩니다 — DB 마다 다릅니다.)

반면 **`IS NOT NULL`** 은 대상 행이 대부분이면 옵티마이저가 풀스캔을 고르는 게 정상입니다. 인덱스를 못 타는 게 아니라 **안 타는 게 이득**이라 판단한 것입니다([Step 16](../step-16-explain-optimizer/index.md)).

`key_len = 5` 도 눈여겨보세요. `INT`(4바이트) + **NULL 허용 플래그 1바이트** = 5. NULL 을 허용하는 컬럼은 인덱스 엔트리가 1바이트씩 커집니다.

---

## A-10. NULL 을 다루는 함수 — 무엇을 언제

| 함수 | 동작 | 비고 |
|---|---|---|
| `COALESCE(a, b, c, …)` | 왼쪽부터 **첫 번째 NULL 아닌 값** | **표준 SQL. 기본으로 쓸 것** |
| `IFNULL(a, b)` | a 가 NULL 이면 b | MySQL 전용. 인자 **2개 고정** |
| `NULLIF(a, b)` | `a = b` 이면 NULL, 아니면 a | 0 을 NULL 로 바꿀 때, 0 나누기 방지 |
| `IF(cond, t, f)` | cond 가 **UNKNOWN 이면 f** | MySQL 전용 |
| `ISNULL(a)` | a 가 NULL 이면 1 | `a IS NULL` 과 동일 |
| `CASE` | A-3 참고 | **`ELSE` 생략 시 NULL** |

```sql
SELECT
    COALESCE(NULL, NULL, '기본값') AS coalesce_ex,
    NULLIF(100, 100)               AS nullif_same,
    IF(NULL, 'TRUE분기', 'FALSE분기') AS if_unknown,
    CASE WHEN 1 = 2 THEN 'x' END   AS `CASE(ELSE 없음)`;
```

**결과**
```
+-------------+-------------+-------------+-------------------+
| coalesce_ex | nullif_same | if_unknown  | CASE(ELSE 없음)   |
+-------------+-------------+-------------+-------------------+
| 기본값      |        NULL | FALSE분기   | NULL              |
+-------------+-------------+-------------+-------------------+
```

두 가지를 기억하세요.

- **`IF(NULL, …)` 는 FALSE 분기로 갑니다.** UNKNOWN 은 참이 아니니까요. 3값 논리가 2값으로 뭉개지는 지점이라, `IF` 안에 NULL 가능 컬럼을 넣을 땐 의도한 결과인지 확인해야 합니다.
- **`CASE` 에 `ELSE` 를 안 쓰면 매칭 실패 시 NULL 이 나옵니다.** 조건부 집계(`SUM(CASE WHEN … THEN 1 END)`)에서는 NULL 이 집계에서 무시되므로 오히려 편리하지만, 화면에 그대로 뿌리면 빈칸이 됩니다. **`ELSE` 를 항상 명시하는 습관**을 권합니다.

`NULLIF` 의 정석 용법은 **0 나누기 방지**입니다.

```sql
-- price 가 0 이어도 에러/경고 없이 NULL 을 돌려준다
SELECT name, (price - cost) / NULLIF(price, 0) AS margin_rate FROM products LIMIT 3;
```

---

## A-11. NULL 이 만들어지는 다섯 가지 경로

"내 컬럼은 NOT NULL 인데 왜 결과에 NULL 이 있지?" 라는 질문의 답입니다.

| 경로 | 예 |
|---|---|
| ① 저장된 NULL | `customers.phone`, `employees.manager_id` |
| ② **OUTER JOIN 의 NULL 확장** | `LEFT JOIN` 에서 짝이 없는 오른쪽 컬럼 (A-8) |
| ③ **빈 집합에 대한 집계** | `SUM`/`AVG`/`MAX` → NULL (A-6) |
| ④ **행이 없는 스칼라 서브쿼리** | `(SELECT phone FROM customers WHERE customer_id = 999)` → NULL |
| ⑤ **표현식·형변환 실패** | `NULLIF`, `ELSE` 없는 `CASE`, `WITH ROLLUP` 의 소계 행 |

```sql
SELECT (SELECT phone FROM customers WHERE customer_id = 999) AS no_row_scalar;
```

**결과**
```
+---------------+
| no_row_scalar |
+---------------+
| NULL          |
+---------------+
```

> 💡 **실무 팁**: ②~⑤ 때문에 **테이블 컬럼이 전부 `NOT NULL` 이어도 쿼리 결과에는 NULL 이 나올 수 있습니다.** 특히 리포트 쿼리에서 조인·집계를 거친 값은 항상 NULL 가능성을 염두에 두고 `COALESCE` 로 마감하세요.

---

## A-12. NULL 이 특별대우를 받는 곳

### UNIQUE 는 NULL 을 몇 개든 허용한다

```sql
CREATE TABLE s26_uniq (
  id    INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(50) UNIQUE,
  age   INT,
  CHECK (age >= 18)
);
INSERT INTO s26_uniq (email, age) VALUES (NULL, 20), (NULL, 30), ('a@x.com', NULL);
SELECT * FROM s26_uniq;
```

**결과**
```
+----+---------+------+
| id | email   | age  |
+----+---------+------+
|  1 | NULL    |   20 |
|  2 | NULL    |   30 |   ← UNIQUE 인데 NULL 이 2건 (에러 아님)
|  3 | a@x.com | NULL |   ← CHECK(age >= 18) 인데 NULL 통과 (에러 아님)
+----+---------+------+
```

한 문장에 **두 가지 특별대우**가 들어 있습니다.

1. **`UNIQUE` 는 NULL 을 중복으로 보지 않습니다.** "모르는 값끼리는 같다고 단정할 수 없다"는 비교 관점이 적용됩니다. → "이메일은 유일해야 한다"는 요구를 `UNIQUE` 만으로 보장하려면 **`NOT NULL` 을 함께** 걸어야 합니다.
2. **`CHECK` 제약은 UNKNOWN 을 통과시킵니다.** `WHERE` 는 "TRUE 인 행만 남기지만", `CHECK` 는 "**FALSE 인 행만 거부**"합니다. `NULL >= 18` 은 UNKNOWN 이므로 위반이 아닙니다. → 값 범위를 강제하려면 역시 **`NOT NULL` 을 함께** 걸어야 합니다.

> ⚠️ **함정**: 제약 조건과 NULL 은 궁합이 나쁩니다. **`UNIQUE`, `CHECK`, `FOREIGN KEY` 모두 NULL 앞에서는 무력합니다**(FK 도 자식 컬럼이 NULL 이면 참조 무결성 검사를 건너뜁니다). 제약으로 무언가를 보장하고 싶다면 **`NOT NULL` 이 전제**입니다. [Step 13](../step-13-constraints/index.md) 참고.

### AUTO_INCREMENT 에 NULL 을 넣으면 번호가 생성된다

```sql
INSERT INTO s26_ai (id, v) VALUES (NULL, 10), (NULL, 20);
```
→ `id` 는 NULL 이 아니라 **1, 2** 가 됩니다. AUTO_INCREMENT 컬럼에서 NULL 은 "네가 알아서 채워라"라는 신호입니다. (`DEFAULT` 키워드나 컬럼 생략도 같은 효과이며, 그쪽이 의도가 더 분명합니다.)

---

## A-13. 설계 지침 — NULL 을 줄이는 것이 최선의 방어

지금까지 본 함정 대부분은 **컬럼이 NULL 을 허용하지 않았다면 아예 생기지 않습니다.**

**1) 기본값은 `NOT NULL DEFAULT ...` 로 시작하세요.** NULL 허용은 "필요해서 켜는 옵션"이지 기본값이 아닙니다.

```sql
qty        INT           NOT NULL DEFAULT 0,
memo       VARCHAR(200)  NOT NULL DEFAULT '',
status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
```

**2) 그래도 NULL 을 쓸 때는, 그 NULL 의 "의미"를 문서화하세요.** NULL 은 최소 세 가지 뜻으로 쓰입니다.

| 의미 | 예 | 대안 |
|---|---|---|
| **모른다** (값은 있는데 미확보) | `birth_date` 미입력 | 그대로 NULL 이 적절 |
| **해당 없음** (구조적으로 없음) | CEO 의 `manager_id`, 최상위 카테고리의 `parent_id` | NULL 이 적절 |
| **아직 없다** (시간이 지나면 채워짐) | `shipped_at`, `deleted_at` | NULL 이 적절 |
| **0 또는 빈 값** | 포인트 0, 메모 없음 | ❌ **NULL 쓰지 말 것** → `0`, `''` |

마지막 줄이 핵심입니다. **"값이 0"과 "값이 없음"을 구분할 필요가 없다면, NULL 을 쓰지 마세요.** 구분이 필요 없는데 NULL 을 허용하면 A-4~A-8 의 함정만 떠안게 됩니다.

**3) 조인 키·필터 조건에 쓰이는 컬럼은 특히 `NOT NULL` 로.** 조인 키에 NULL 이 있으면 그 행은 어떤 조인에도 매칭되지 않고 조용히 사라집니다. 인덱스 크기도 1바이트씩 커집니다(A-9).

**4) 이미 NULL 이 있는 컬럼을 `NOT NULL` 로 바꾸려면** — 데이터를 먼저 채우고(`UPDATE ... SET col = '' WHERE col IS NULL`), 그 다음 `ALTER TABLE ... MODIFY`. 순서를 바꾸면 실패하거나(엄격 모드) 조용히 기본값으로 채워집니다([Step 02](../step-02-ddl-datatypes/index.md), [Step 11](../step-11-dml/index.md)).

---

## A-14. 자가진단 10문항

답을 가리고 풀어 보세요. **8개 이상 맞히면 NULL 은 졸업입니다.**

| # | 질문 | 답 |
|---|---|---|
| 1 | `NULL = NULL` 의 결과는? | **NULL(UNKNOWN)**. `<=>` 를 쓰면 1 |
| 2 | `WHERE col <> 'A'` 는 col 이 NULL 인 행을 포함하는가? | **아니오.** UNKNOWN 이라 탈락 |
| 3 | `COUNT(*)` 와 `COUNT(col)` 이 다를 수 있는가? | **예.** col 의 NULL 개수만큼 차이 |
| 4 | 행이 0건일 때 `SUM(x)` 는? | **NULL**. (`COUNT(x)` 는 0) |
| 5 | `GROUP BY col` 에서 NULL 행들은 어떻게 되는가? | **한 그룹으로 묶인다** |
| 6 | `UNIQUE` 컬럼에 NULL 을 2건 넣을 수 있는가? | **예.** 그래서 `NOT NULL` 이 함께 필요 |
| 7 | `CHECK (age >= 18)` 인 컬럼에 NULL 을 넣으면? | **통과한다.** UNKNOWN 은 위반이 아님 |
| 8 | `NOT IN` 서브쿼리에 NULL 이 1건 섞이면? | **결과가 항상 0행** |
| 9 | `LEFT JOIN ... WHERE 오른쪽.status = 'X'` 는? | **INNER JOIN 으로 퇴화.** 조건은 `ON` 으로 |
| 10 | `ORDER BY col` 에서 NULL 은 어디에? | **맨 앞**(ASC). 뒤로 보내려면 `ORDER BY col IS NULL, col` |

---

## 정리 — 한 장 치트시트

**판단 기준 한 줄**: 지금 하는 것이 **비교**인가(→ NULL ≠ NULL, UNKNOWN 발생), **그룹핑**인가(→ NULL = NULL, 하나로 묶임).

| 상황 | 쓸 것 | 쓰지 말 것 |
|---|---|---|
| NULL 인 행 찾기 | `IS NULL` | `= NULL` |
| 파라미터가 NULL 일 수 있는 비교 | `<=>` | `=` |
| "제외" 조건 | `col <> 'x' OR col IS NULL` (요구사항 확인!) | `col <> 'x'` 단독 |
| 없는 것 찾기(안티조인) | **`NOT EXISTS`** | `NOT IN (서브쿼리)` |
| LEFT JOIN + IS NULL 안티조인 | 오른쪽 **PK** 로 판정 | NULL 가능 컬럼으로 판정 |
| LEFT JOIN 에 오른쪽 조건 | `ON` 에 | `WHERE` 에 |
| 합계를 화면에 표시 | `COALESCE(SUM(x), 0)` | `SUM(x)` 그대로 |
| "포인트 적립자 평균" | `AVG(NULLIF(points,0))` | `AVG(points)` |
| ROLLUP 소계 라벨링 | `GROUPING(col)` | `col IS NULL` |
| NULL 을 정렬 뒤로 | `ORDER BY col IS NULL, col` | `NULLS LAST`(MySQL 에 없음) |
| 문자열 조립 | `CONCAT_WS` | `CONCAT` |
| 대체값 | `COALESCE` (표준) | `IFNULL` (MySQL 전용) |
| 애초에 | **`NOT NULL DEFAULT ...`** | 습관적 NULL 허용 |

### 스텝별 되짚기

| 스텝 | NULL 관련 절 | 이 부록의 대응 |
|---|---|---|
| [Step 05](../step-05-where-operators/index.md) | 5-5 3값 논리 · 5-6 `<=>` · 5-7 `NOT IN` · 5-10 NULL 함수 | A-1 · A-2 · A-4 · A-5 · A-10 |
| [Step 06](../step-06-aggregate-groupby/index.md) | 6-2 COUNT 3형제 · 6-3 집계는 NULL 무시 · 6-8 ROLLUP/`GROUPING()` | A-6 · A-7 |
| [Step 07](../step-07-joins/index.md) | 7-4 `ON` vs `WHERE` · 7-5 안티조인 | A-8 |
| [Step 08](../step-08-subqueries/index.md) | 8-5 `EXISTS` · 8-8 `NOT IN` + NULL | A-5 |
| [Step 13](../step-13-constraints/index.md) | 제약 조건 | A-12 · A-13 |
| [Step 15](../step-15-indexes/index.md) · [16](../step-16-explain-optimizer/index.md) | 인덱스 · 실행계획 | A-9 |

---

## 연습문제

`practice.sql` 로 이 부록의 모든 예제를 직접 재현한 뒤, 아래를 스스로 작성해 보세요. 전부 이 부록에 답이 있습니다.

1. `customers` 에서 "전화번호가 `010-1000-0001` 이 **아닌**" 고객을 **NULL 포함 29명**으로 세는 쿼리
2. `categories` 에서 잎 노드(부모가 아닌 카테고리)를 **세 가지 방법**(`NOT IN` + NULL 제거 / `NOT EXISTS` / `LEFT JOIN ... IS NULL`)으로 찾고 개수가 같은지 확인
3. 고객별 주문 건수를 뽑되, 주문이 없는 고객도 **0 으로** 표시 (`LEFT JOIN` + `COUNT(o.order_id)`)
4. 도시별 고객 수를 `WITH ROLLUP` 으로 뽑고, `GROUPING()` 으로 총계 행에 `── 전체 ──` 라벨 붙이기
5. `customers` 를 전화번호 순으로 정렬하되 **NULL 을 맨 뒤로**
6. `UNIQUE` 제약이 있는 컬럼에 NULL 을 3건 넣어 보고, `NOT NULL` 을 추가하면 어떻게 되는지 확인
7. `products` 의 마진율을 `price = 0` 인 상품이 있어도 에러 없이 계산 (`NULLIF`)
8. 주문이 하나도 없는 조건(`WHERE 1=0`)에서 `SUM(total_amount)` 과 `COUNT(*)` 의 차이를 보이고, 화면 표시용으로 `COALESCE` 처리

---

## 다음 단계

이 부록은 여기서 끝입니다. Step 09 이후를 계속 진행하세요.

→ [Step 09 — CTE와 재귀 쿼리](../step-09-cte-recursive/index.md)
→ 코스 전체 목차는 [MySQL 8 완전 학습 코스](../index.md)

---

## 실습 파일

### practice.sql

이 부록의 A-1 ~ A-12 예제를 위에서부터 그대로 실행하는 스크립트입니다.

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
```

- **공용 테이블은 읽기만 합니다.** 데이터를 넣어야 하는 A-8 · A-12 예제만 **`s26_` 접두사 사본**을 만들고, 파일 끝에서 `DROP TABLE IF EXISTS` 로 전부 지웁니다.
- `--table` 옵션을 붙여 실행하면 교재와 같은 표 형태로 결과가 나옵니다.
- 블록 번호(`[A-1]` ~ `[A-12]`)가 본문 절 번호와 1:1 대응합니다. 헷갈리는 절만 골라 그 블록만 복사해 실행해도 됩니다.

```sql file="./practice.sql"
```
