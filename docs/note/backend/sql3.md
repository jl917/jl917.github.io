# MySQL 마스터 체크리스트

> MySQL 8을 공부하며 **꼭 이해해야 하는 개념 · 내장 함수 · 실전 함정 · 실무 팁**을 한 장에 압축했습니다.
> 이 체크리스트만 외우면 실전에서 막히지 않는 것을 목표로 합니다.
> 깊은 설명·실행 결과는 [reference/mysql8](../../reference/mysql8/index.md) 코스(25 스텝)로 연결됩니다.

**사용법**
- 각 항목 앞의 `[ ]`를 "설명할 수 있다 / 직접 칠 수 있다" 기준으로 채우세요. 읽어서 아는 것과 손이 아는 것은 다릅니다.
- ⚠️ 는 **에러 없이 조용히 틀린 답을 내는** 함정입니다. 문법 에러보다 위험합니다. 최우선 암기 대상.
- 💡 는 실무에서 사고를 막아주는 팁입니다.

---

## 0. 반드시 몸에 배어야 할 대원칙 (제일 먼저 암기)

- [ ] ⚠️ **NULL은 값이 아니라 "모름"이다.** `= NULL`은 항상 `UNKNOWN`. NULL 비교는 `IS NULL` / `IS NOT NULL` / `<=>`로만.
- [ ] ⚠️ **`NOT IN (서브쿼리)`에 NULL이 하나라도 섞이면 결과가 통째로 0건.** 부정 매칭은 `NOT EXISTS`를 기본값으로.
- [ ] ⚠️ **컬럼에 함수를 씌우면 인덱스를 못 탄다.** `WHERE DATE(created) = '..'` ✗ → `WHERE created >= '..' AND created < '..'` ✓
- [ ] ⚠️ **`LEFT JOIN`의 조건을 `WHERE`에 쓰면 INNER JOIN이 되어버린다.** 오른쪽 테이블 조건은 `ON`에.
- [ ] ⚠️ **1:N 조인 후 집계는 뻥튀기된다.** 조인 전에 "지금 한 행의 단위가 무엇인가?"를 자문. `COUNT(DISTINCT ...)` / 조건부 집계 / 선집계 후 조인.
- [ ] ⚠️ **`ORDER BY` 없는 `LIMIT`은 순서를 아무것도 보장하지 않는다.** GROUP BY도 8.0부터 암묵 정렬 없음.
- [ ] ⚠️ **돈·수량·비율은 `DECIMAL`, `FLOAT/DOUBLE` 금지.** `0.1 + 0.2 ≠ 0.3`으로 정산이 어긋난다.
- [ ] **`ONLY_FULL_GROUP_BY`를 끄지 마라.** 에러는 쿼리가 틀렸다는 신호다.
- [ ] 💡 운영 DB에 붙을 땐 `SET sql_safe_updates=1` (WHERE 없는 UPDATE/DELETE 거부).

---

## 1. 데이터 타입 & 스키마 설계

### 개념
- [ ] `INT / BIGINT / TINYINT`, `UNSIGNED`의 의미와 범위
- [ ] `DECIMAL(p,s)` vs `FLOAT/DOUBLE` — 정확성 vs 근사
- [ ] `CHAR` vs `VARCHAR` — 고정 vs 가변, 정렬/임시테이블 메모리 영향
- [ ] `DATE / DATETIME / TIMESTAMP / TIME / YEAR`, `TIMESTAMP`의 타임존 자동 변환
- [ ] `ENUM / SET` — 내부 정수값 저장
- [ ] `JSON` 타입 (5부에서 상세)
- [ ] `NULL` 허용 여부 설계 (`NOT NULL DEFAULT`)
- [ ] 문자셋/콜레이션: `utf8mb4` / `utf8mb4_0900_ai_ci` (대소문자·악센트 무시)

### 함정 & 팁
- [ ] 💡 **돈·수량은 무조건 `DECIMAL(p,s)`.** 원화면 `DECIMAL(12,2)` 정도.
- [ ] ⚠️ **`TIMESTAMP`는 2038년 오버플로우.** 먼 미래 날짜(구독 만료 등)는 `DATETIME`.
- [ ] ⚠️ **`ENUM` 정렬은 사전순이 아니라 선언 순서.** `ORDER BY grade DESC`가 등급순이 되는 이유.
- [ ] ⚠️ **`ENUM`은 값 추가 시 `ALTER TABLE` 필요.** 자주 늘어나는 도메인은 **코드 테이블 + FK**가 낫다.
- [ ] ⚠️ **`VARCHAR(255)` 습관적으로 쓰지 마라.** 길이 제한은 검증의 마지막 방어선이고, 정렬 시 선언 길이만큼 메모리를 잡는다.
- [ ] 💡 자동 시각: `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, `updated_at ... ON UPDATE CURRENT_TIMESTAMP`
- [ ] 💡 대형 테이블 스키마 변경은 `pt-online-schema-change`(Percona) / `gh-ost`(GitHub).

→ [Step 02 데이터 타입](../../reference/mysql8/step-02-ddl-datatypes/), [Step 13 제약·정규화](../../reference/mysql8/step-13-constraints/)

---

## 2. SELECT 기본 & 연산자

### 개념
- [ ] 논리적 실행 순서: **FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT**
- [ ] `DISTINCT`, 컬럼/테이블 별칭(`AS`)
- [ ] 비교/논리 연산자, `BETWEEN / IN / LIKE / REGEXP`
- [ ] `LIMIT offset, count` 와 페이징

### 함정 & 팁
- [ ] ⚠️ **`SELECT name price` (콤마 누락)는 에러가 안 난다.** `price`를 별칭으로 해석 → 컬럼이 조용히 사라짐. **`AS`를 항상 명시.**
- [ ] ⚠️ **별칭을 붙이면 원래 테이블명은 못 쓴다.** `FROM products p WHERE products.price` → 에러.
- [ ] ⚠️ **`DISTINCT`는 함수가 아니다.** `DISTINCT(city)`의 괄호는 무시됨. SELECT 목록 **전체**에 걸린다.
- [ ] ⚠️ **`OR`엔 무조건 괄호.** 실무 버그 다수가 "OR 괄호 누락".
- [ ] ⚠️ **DATETIME에 `BETWEEN` 쓰지 마라.** `BETWEEN '2024-01-01' AND '2024-01-31'`은 `23:00:00` 이후를 놓친다 → `>= '2024-01-01' AND < '2024-02-01'`.
- [ ] ⚠️ **`LIKE '%키워드%'`는 인덱스를 못 탄다.** 앞이 고정된 `'키워드%'`만 range scan. 대량이면 FULLTEXT/검색엔진.
- [ ] ⚠️ **`REGEXP`는 절대 인덱스를 못 쓴다** — 항상 풀스캔. 좁혀진 결과의 추가 필터로만.
- [ ] ⚠️ **NULL 가능 컬럼에 부정 조건(`<>`, `NOT IN`, `NOT LIKE`)** 쓸 땐 `OR ... IS NULL` 여부를 반드시 결정.
- [ ] ⚠️ **`ORDER BY` 없는 `LIMIT`은 결과 순서 미보장.**
- [ ] ⚠️ **`LIMIT 100000, 20` 깊은 OFFSET은 재앙** — 100,020행 읽고 100,000행 버림. → **커서(keyset) 페이징**: `WHERE id > 마지막본id ORDER BY id LIMIT 20`.
- [ ] 💡 MySQL엔 `NULLS LAST` 문법이 없다 → `ORDER BY (col IS NULL), col`.
- [ ] 💡 `<=>` (NULL-safe equal): 파라미터가 NULL일 수 있는 검색/변경 감지에 유용.

→ [Step 04 SELECT](../../reference/mysql8/step-04-select-basics/), [Step 05 연산자·NULL](../../reference/mysql8/step-05-where-operators/)

---

## 3. 내장 함수 완전 리스트

> 함수 이름만 봐도 "무엇을 하는지 + 함정"이 떠올라야 합니다.

### 3-1. 문자열 함수
- [ ] `CONCAT(a,b,...)` — 이어붙이기. **인자 하나라도 NULL이면 결과 NULL** (주의)
- [ ] `CONCAT_WS(sep, ...)` — 구분자로 이어붙이기, **NULL 인자는 건너뜀**
- [ ] `LENGTH()` (바이트) vs `CHAR_LENGTH()` (문자 수) — **한글은 utf8mb4에서 3바이트**, 글자 수는 `CHAR_LENGTH`
- [ ] `UPPER() / LOWER()`
- [ ] `SUBSTRING(str, pos, len)` (1-based), `LEFT() / RIGHT()`
- [ ] `TRIM() / LTRIM() / RTRIM()`
- [ ] `REPLACE(str, from, to)`
- [ ] `LOCATE(sub, str)` / `INSTR()` — 위치 찾기(없으면 0)
- [ ] `LPAD() / RPAD()`
- [ ] `FORMAT(x, d)` — 천단위 콤마. ⚠️ **결과는 문자열** → 정렬/계산 금지, 표시 전용
- [ ] `REGEXP_REPLACE() / REGEXP_SUBSTR() / REGEXP_LIKE()` (8.0)

### 3-2. 숫자 함수
- [ ] `ROUND(x, d)` / `TRUNCATE(x, d)` (반올림 vs 버림)
- [ ] `CEIL() / FLOOR()`
- [ ] `ABS()`, `MOD(n,m)` 또는 `%`
- [ ] `POWER() / SQRT()`
- [ ] `RAND()` — ⚠️ 재현 불가, 인덱스로 정렬 불가(`ORDER BY RAND()`는 풀스캔+파일소트)
- [ ] `GREATEST() / LEAST()` — 여러 값 중 최대/최소 (행 단위, 집계 아님)

### 3-3. 날짜/시간 함수
- [ ] `NOW() / CURDATE() / CURTIME() / SYSDATE()` — ⚠️ `NOW()`는 문 시작 고정, `SYSDATE()`는 호출 시각
- [ ] `DATE() / TIME() / YEAR() / MONTH() / DAY() / HOUR()`
- [ ] `DATE_ADD(d, INTERVAL n UNIT) / DATE_SUB()` — `INTERVAL 7 DAY`
- [ ] `DATEDIFF(a,b)` (일수), `TIMESTAMPDIFF(UNIT, a, b)` (단위 지정)
- [ ] `DATE_FORMAT(d, '%Y-%m-%d')` / `STR_TO_DATE()`
- [ ] `LAST_DAY()`, `WEEKDAY() / DAYOFWEEK()`, `EXTRACT(UNIT FROM d)`
- [ ] `UNIX_TIMESTAMP() / FROM_UNIXTIME()`
- [ ] ⚠️ **WHERE 절 날짜 컬럼에 `DATE()/DATE_FORMAT()` 씌우면 인덱스 사망** → 범위 조건으로.

### 3-4. 조건/NULL 함수
- [ ] `IF(cond, a, b)` — 3항
- [ ] `IFNULL(a, b)` vs 💡 **`COALESCE(a, b, c...)`** — COALESCE가 표준이고 확장 쉬움
- [ ] `NULLIF(a, b)` — 같으면 NULL (0으로 나누기 방지: `x / NULLIF(y,0)`)
- [ ] `CASE WHEN ... THEN ... ELSE ... END` — 단순형/검색형 둘 다
- [ ] `COALESCE`로 여러 컬럼 중 첫 비-NULL 뽑기

### 3-5. 집계 함수
- [ ] `COUNT(*)` vs `COUNT(col)` — ⚠️ **`COUNT(col)`은 NULL을 안 센다**
- [ ] `COUNT(DISTINCT col)` — "몇 명/몇 종류"는 거의 항상 이것
- [ ] `SUM() / AVG() / MIN() / MAX()` — ⚠️ **모두 NULL을 무시**(0이 아님)
- [ ] `GROUP_CONCAT(col ORDER BY .. SEPARATOR ..)` — ⚠️ `group_concat_max_len` 넘으면 **조용히 잘림**
- [ ] `SUM(조건)` = 조건부 카운트 (`SUM(status='X')`), `AVG(조건)` = 비율
- [ ] 💡 `COUNT(1) == COUNT(*)` — "별표가 느리다"는 미신

### 3-6. 형변환 & 기타
- [ ] `CAST(x AS type)` / `CONVERT()` — `DECIMAL, CHAR, DATE, UNSIGNED` 등
- [ ] `JSON_EXTRACT() / ->` / `->>` (5부)
- [ ] 윈도우 함수 (섹션 7)

### 함정 & 팁 요약
- [ ] ⚠️ `CONCAT`은 NULL 전파 / `CONCAT_WS`는 NULL 스킵 — 상황 따라 골라 쓰기
- [ ] ⚠️ `FORMAT`/`DATE_FORMAT` 결과는 문자열 — 정렬·재계산 금지 (**표시는 마지막에**)
- [ ] 💡 NULL 방어 기본기: `COALESCE`, `NULLIF(y,0)`으로 0 나눗셈 방지

→ [Step 12 내장 함수](../../reference/mysql8/step-12-builtin-functions/)

---

## 4. GROUP BY & HAVING & 집계

### 개념
- [ ] `GROUP BY`의 의미와 `HAVING`(그룹 필터) vs `WHERE`(행 필터)
- [ ] `ONLY_FULL_GROUP_BY` — SELECT의 비집계 컬럼은 GROUP BY에 있어야
- [ ] `WITH ROLLUP` — 소계/총계, `GROUPING()`
- [ ] 조건부 집계 (`SUM(CASE WHEN ...)`, `SUM(조건)`)로 피벗

### 함정 & 팁
- [ ] ⚠️ **집계와 무관한 조건은 `WHERE`에.** `HAVING city IN (..)`은 다 그룹핑 후 버려서 느리고 인덱스도 못 탐.
- [ ] ⚠️ **5.7→8.0: `ONLY_FULL_GROUP_BY`가 켜져서** 옛 리포트 쿼리가 무더기 에러. **끄지 말 것** — 버그 방지.
- [ ] ⚠️ **8.0부터 `GROUP BY`는 암묵 정렬을 안 한다.** 순서 필요하면 `ORDER BY` 명시.
- [ ] ⚠️ **조인 후 `COUNT(*)`는 고객 수가 아니라 주문 수** — `COUNT(DISTINCT customer_id)`.
- [ ] ⚠️ **평균 낼 때 NULL vs 0 취급 확인** — 기획자에게 "포함인가요?" 물어라.
- [ ] ⚠️ `WITH ROLLUP` + `ORDER BY`/`LIMIT`/`DISTINCT` 조합은 주의(NULL 정렬).

→ [Step 06 집계·GROUP BY](../../reference/mysql8/step-06-aggregate-groupby/)

---

## 5. JOIN

### 개념
- [ ] `INNER / LEFT / RIGHT / CROSS / SELF JOIN`
- [ ] `ON` (조인 조건) vs `WHERE` (조인 후 필터)의 차이
- [ ] 안티 조인 3형태: `NOT EXISTS` / `NOT IN` / `LEFT JOIN ... WHERE 오른쪽 IS NULL`
- [ ] 세미 조인 (`EXISTS`, `IN`)

### 함정 & 팁
- [ ] ⚠️ **`LEFT JOIN` + 오른쪽 조건을 `WHERE`에 두면 INNER JOIN이 된다.** 오른쪽 조건은 `ON`에.
- [ ] ⚠️ **행 뻥튀기(fan-out):** 1:N 조인 후 `SUM()`은 중복 합산. 조인 전 단위 확인, 선집계 후 조인.
- [ ] ⚠️ **`LEFT JOIN` 뒤 `COUNT(*)`는 없는 행도 1로 센다.** → `COUNT(오른쪽테이블컬럼)`로 0을 얻어라.
- [ ] ⚠️ **`SELECT DISTINCT`가 보이면 잘못된 조인의 반창고인지 의심.**
- [ ] 💡 조인엔 **모든 테이블 별칭 + 모든 컬럼에 접두사**(`c.name`) — `ambiguous` 에러 방지.
- [ ] 💡 실무엔 FK 미선언 프로젝트가 흔함 → 관계를 컬럼명으로 추측해야 하는 고통.

→ [Step 07 JOIN](../../reference/mysql8/step-07-joins/)

---

## 6. 서브쿼리 · CTE · 집합 연산

### 개념
- [ ] 스칼라 서브쿼리 / 파생 테이블(FROM절) / 상관 서브쿼리
- [ ] `IN` vs `EXISTS`, `= ANY`(=IN) / `<> ALL`(=NOT IN)
- [ ] `WITH` (CTE), `WITH RECURSIVE` (조직도 전개, 날짜 채우기)
- [ ] `UNION` vs `UNION ALL` (중복 제거 여부), `INTERSECT / EXCEPT` (8.0.31+)
- [ ] `LATERAL` 조인 (그룹별 Top-N)

### 함정 & 팁
- [ ] ⚠️ **스칼라 서브쿼리가 2행 이상 반환하면 런타임 에러.**
- [ ] ⚠️ **파생 테이블엔 반드시 별칭.**
- [ ] ⚠️ **`NOT IN (서브쿼리)` + NULL = 항상 0건.** → `NOT EXISTS`.
- [ ] ⚠️ **서브쿼리가 0행이면 `> ALL`은 항상 참, `> ANY`는 항상 거짓** (직관 반대).
- [ ] ⚠️ **CTE는 앞→뒤로만 참조** (재귀 제외). 재귀 CTE 컬럼 타입은 **앵커에서 결정** → 자라는 컬럼은 `CAST(x AS CHAR(200))`.
- [ ] ⚠️ **`UNION`은 중복 제거로 정렬 비용 발생** — 중복 없음이 확실하면 `UNION ALL`.
- [ ] 💡 **MySQL CTE는 안 느리다** — 한 번 참조·머지 가능하면 파생 테이블과 동일. 가독성 위해 맘껏.
- [ ] 💡 상관 서브쿼리를 SELECT에 여러 개 늘어놓으면 테이블 반복 스캔 → `LEFT JOIN + GROUP BY`로.
- [ ] 💡 깊은 계층은 재귀 대신 **materialized path / closure table** 설계 고려.

→ [Step 08 서브쿼리](../../reference/mysql8/step-08-subqueries/), [Step 09 CTE·재귀](../../reference/mysql8/step-09-cte-recursive/), [Step 10 집합 연산](../../reference/mysql8/step-10-set-operations/)

---

## 7. 윈도우 함수 (실무·면접 핵심)

### 개념
- [ ] `함수() OVER (PARTITION BY ... ORDER BY ... 프레임절)` 3부품
- [ ] 순위: `ROW_NUMBER()` (1,2,3,4) / `RANK()` (1,1,1,4) / `DENSE_RANK()` (1,1,1,2) / `NTILE(n)`
- [ ] 오프셋: `LAG() / LEAD()` (전월 대비 증감), `FIRST_VALUE() / LAST_VALUE() / NTH_VALUE()`
- [ ] 분포: `PERCENT_RANK() / CUME_DIST()`
- [ ] 집계 윈도우: 누적합, 이동평균, 그룹 내 비율
- [ ] 프레임 절: `ROWS` (물리 행) vs `RANGE` (동점 peer 묶음)

### 함정 & 팁
- [ ] ⚠️ **윈도우 함수는 `WHERE`/`HAVING`에 못 쓴다** — 서브쿼리/CTE로 감싸서 바깥에서 필터.
- [ ] ⚠️ **`ORDER BY`를 쓰는 순간 기본 프레임 `RANGE UNBOUNDED PRECEDING ~ CURRENT ROW`가 붙는다** → 누적합이 됨.
- [ ] ⚠️ **`LAST_VALUE()`가 마지막 값을 안 준다** — 기본 프레임이 "현재 행까지"라서. `ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING` 명시.
- [ ] ⚠️ **`ROW_NUMBER()`는 동점 시 순서 비결정.** ROWS 프레임 쓸 땐 `ORDER BY`를 유일하게(`ORDER BY qty DESC, name`).
- [ ] 용도 매핑: 중복 제거→`ROW_NUMBER`, 순위표(공동 3등 다음 5등)→`RANK`, 상위 N등급→`DENSE_RANK`, 4/10분위→`NTILE`.

→ [Step 17 윈도우 함수](../../reference/mysql8/step-17-window-functions/)

---

## 8. DML (INSERT / UPDATE / DELETE)

### 개념
- [ ] `INSERT ... VALUES`, `INSERT ... SELECT`, 다중행 INSERT
- [ ] **UPSERT**: `INSERT ... ON DUPLICATE KEY UPDATE`
- [ ] `UPDATE ... JOIN`, `DELETE ... JOIN`
- [ ] `REPLACE` (= DELETE + INSERT)

### 함정 & 팁
- [ ] ⚠️ **`WHERE` 없는 `UPDATE`/`DELETE`는 전체 테이블을 날린다.** → `sql_safe_updates=1`.
- [ ] ⚠️ **`REPLACE`는 DELETE+INSERT** — 기존 행이 사라지며 FK/AUTO_INCREMENT/트리거 부작용. UPSERT는 대개 `ON DUPLICATE KEY UPDATE`가 정답.
- [ ] 💡 대량 DELETE는 배치로 쪼개기(`LIMIT`) — 긴 트랜잭션/락/binlog 폭증 방지.
- [ ] 💡 `INSERT IGNORE`는 에러를 조용히 삼킨다 — 의도 확인.

→ [Step 11 DML](../../reference/mysql8/step-11-dml/)

---

## 9. 제약조건 · 정규화

- [ ] `PRIMARY KEY / UNIQUE / NOT NULL / CHECK / FOREIGN KEY / DEFAULT`
- [ ] FK의 `ON DELETE / ON UPDATE`: `CASCADE / SET NULL / RESTRICT / NO ACTION`
- [ ] 정규화 1NF~3NF와 **의도된 반정규화** (주문 시점 가격/상품명 스냅샷)
- [ ] `AUTO_INCREMENT` 동작과 갭
- [ ] 💡 `UNIQUE` 제약 컬럼의 NULL은 **여러 개 허용**(NULL끼리 중복 아님).

→ [Step 13 제약·정규화](../../reference/mysql8/step-13-constraints/)

---

## 10. 인덱스 & 성능 (코스의 심장부)

### 개념
- [ ] B+Tree 구조, **클러스터드 인덱스**(PK가 데이터 자체), 보조 인덱스 = PK를 가리킴
- [ ] **복합 인덱스 컬럼 순서** — 왼쪽 접두사(leftmost prefix) 규칙
- [ ] **커버링 인덱스** (인덱스만으로 쿼리 해결, `Using index`)
- [ ] 카디널리티, 선택도, 인덱스 유무의 트레이드오프(쓰기 비용/디스크)
- [ ] 함수 기반 인덱스 / 생성 컬럼 인덱스 (8.0)

### 함정 & 팁
- [ ] ⚠️ **컬럼에 함수/연산을 씌우면 인덱스 무효** — `WHERE DATE(c)=..`, `WHERE col+1=..`, `WHERE LIKE '%x%'`.
- [ ] ⚠️ **복합 인덱스 `(a,b,c)`는 `a`부터 순서대로** 써야 탄다. `WHERE b=..`만으론 못 탐.
- [ ] ⚠️ **암묵적 형변환도 인덱스를 죽인다** — `WHERE phone = 010...`(문자열 컬럼에 숫자), 콜레이션 불일치 조인.
- [ ] ⚠️ **인덱스는 공짜가 아니다** — `index_mb > data_mb`면 과다. 쓰기 느려짐.
- [ ] 💡 `ORDER BY RAND()`, 깊은 OFFSET 회피. 인덱스로 정렬·범위를 태워라.

→ [Step 15 인덱스](../../reference/mysql8/step-15-indexes/)

---

## 11. EXPLAIN & 옵티마이저

### 개념
- [ ] `EXPLAIN` 읽는 법: `type`(system>const>eq_ref>ref>range>index>ALL), `key`, `rows`, `filtered`, `Extra`
- [ ] `Extra`의 신호: `Using index`(커버링👍), `Using where`, `Using temporary`⚠️, `Using filesort`⚠️
- [ ] `EXPLAIN ANALYZE` (실제 실행 시간/행수)
- [ ] 옵티마이저 힌트, 히스토그램(`ANALYZE TABLE ... UPDATE HISTOGRAM`)

### 함정 & 팁
- [ ] ⚠️ `type: ALL` = 풀 테이블 스캔. 대형 테이블에서 보이면 인덱스 점검.
- [ ] ⚠️ `Using temporary` + `Using filesort` = 메모리 임시테이블→디스크로 떨어질 수 있음. `VARCHAR` 과다 선언·불필요 정렬 의심.
- [ ] ⚠️ `rows`는 **추정값**. `table_rows`(information_schema)도 추정 — 정확한 개수는 `COUNT(*)`.
- [ ] 💡 붙자마자 상태 확인 3종: `SELECT NOW(); SELECT VERSION(); SELECT @@sql_mode;`

→ [Step 16 EXPLAIN·옵티마이저](../../reference/mysql8/step-16-explain-optimizer/)

---

## 12. 트랜잭션 & 락 (동시성)

### 개념
- [ ] **ACID** (원자성·일관성·격리성·지속성)
- [ ] `BEGIN / COMMIT / ROLLBACK`, `SAVEPOINT`, 오토커밋
- [ ] 격리 수준 4단계 + 이상현상:
  - `READ UNCOMMITTED` (dirty read)
  - `READ COMMITTED` (non-repeatable read)
  - **`REPEATABLE READ`** (MySQL 기본, phantom은 갭락으로 대부분 방지)
  - `SERIALIZABLE`
- [ ] **MVCC** — 스냅샷 읽기(일반 SELECT는 락 없음)
- [ ] 락 종류: 공유(S)/배타(X) 락, **레코드 락 / 갭 락 / 넥스트키 락**
- [ ] 잠금 읽기: `SELECT ... FOR UPDATE` / `FOR SHARE`
- [ ] 데드락 탐지와 자동 롤백

### 함정 & 팁
- [ ] ⚠️ **긴 트랜잭션 금지** — undo 로그·락 보유로 다른 세션 블로킹, 복제 지연.
- [ ] ⚠️ **`REPEATABLE READ`에서 같은 SELECT는 스냅샷 고정** — 중간에 커밋된 남의 변경이 안 보인다(의도이자 함정).
- [ ] ⚠️ **갱신 시엔 갱신 시점 값을 다시 읽어야**(`FOR UPDATE`) 로스트 업데이트 방지.
- [ ] ⚠️ **데드락은 정상적으로 발생 가능** — 애플리케이션은 **재시도 로직**을 갖춰야. 락 획득 순서를 일관되게.
- [ ] 💡 데드락 분석: `SHOW ENGINE INNODB STATUS`의 `LATEST DETECTED DEADLOCK`.

→ [Step 19 트랜잭션·락](../../reference/mysql8/step-19-transactions/)

---

## 13. 뷰 · 저장 프로그램 · 트리거

- [ ] `VIEW` (갱신 가능한 뷰 조건), 생성 컬럼 `VIRTUAL` vs `STORED`
- [ ] 저장 프로시저 / 저장 함수 / 커서
- [ ] 트리거(`BEFORE/AFTER INSERT/UPDATE/DELETE`), 이벤트 스케줄러
- [ ] 💡 트리거는 디버깅·성능 추적이 어렵다 — 남용 주의, 비즈니스 로직은 애플리케이션에.

→ [Step 14 뷰·생성컬럼](../../reference/mysql8/step-14-views-generated/), [Step 20 저장 프로그램](../../reference/mysql8/step-20-stored-programs/)

---

## 14. JSON · 파티셔닝

### JSON
- [ ] `JSON_EXTRACT(doc, '$.path')` = `doc->'$.path'`, `->>`(따옴표 제거)
- [ ] `JSON_TABLE()` (JSON→관계형 행), `JSON_ARRAYAGG() / JSON_OBJECTAGG()`
- [ ] JSON 인덱싱: 생성 컬럼 + 인덱스, 멀티밸류 인덱스
- [ ] 💡 JSON은 유연하지만 스키마리스의 대가(검증·인덱싱 어려움) — 관계형이 맞는지 먼저 판단.

### 파티셔닝
- [ ] `RANGE / LIST / HASH / KEY` 파티셔닝, 파티션 프루닝
- [ ] 💡 **`DROP PARTITION`으로 오래된 데이터 즉시 삭제**(대량 DELETE 회피) — 시계열 데이터에 강력.

→ [Step 18 JSON](../../reference/mysql8/step-18-json/), [Step 21 파티셔닝](../../reference/mysql8/step-21-partitioning/)

---

## 15. 계정·보안·백업·운영

- [ ] `CREATE USER / GRANT / REVOKE`, **ROLE**(8.0), 최소 권한 원칙
- [ ] ⚠️ **접속 계정에 애초에 `DROP` 권한을 주지 마라** — `DROP DATABASE`엔 확인 절차가 없다.
- [ ] `mysqldump`, PITR(시점 복구), **binlog**, **GTID**, 복제(replication)
- [ ] 슬로우 쿼리 로그, `performance_schema`, `sys` 스키마
- [ ] 💡 새 DB 받으면 **주요 테이블의 시간 범위부터 확인**(데이터가 멈춰있는지).
- [ ] 💡 `SET PERSIST`로 설정을 재시작 후에도 유지(8.0). 공용 DB 설정 변경은 `SET SESSION`만.

→ [Step 22 계정·보안](../../reference/mysql8/step-22-users-security/), [Step 23 백업·복제](../../reference/mysql8/step-23-backup-replication/), [Step 24 모니터링·튜닝](../../reference/mysql8/step-24-monitoring-tuning/)

---

## 16. NULL 완전 정복 (별도 암기)

- [ ] **3값 논리**: `TRUE / FALSE / UNKNOWN`. NULL과의 비교는 UNKNOWN.
- [ ] **절마다 다른 NULL 규칙**:
  - `WHERE` : UNKNOWN인 행은 **제외**
  - `GROUP BY` : NULL끼리 **한 그룹**
  - `ORDER BY` : NULL이 가장 **작은 값** 취급(기본 ASC면 맨 앞)
  - 집계 함수 : NULL **무시** (`COUNT(*)` 제외)
  - `UNIQUE` : NULL 여러 개 **허용**
  - `DISTINCT` : NULL끼리 **하나**로 취급
- [ ] 네 가지 대표 함정이 하나의 원리(=NULL은 모름): 3값 논리 · 집계의 NULL 무시 · `ON` vs `WHERE` · `NOT IN`
- [ ] 도구: `IS NULL`, `<=>`, `COALESCE`, `IFNULL`, `NULLIF`, `GROUPING()`

→ [부록 A — NULL 완전 정복](../../reference/mysql8/appendix-a-null/)

---

## 17. 실전 90초 자가진단 (이것만 즉답하면 합격)

1. `NOT IN` 서브쿼리에 NULL이 들어오면? → **결과 0건. `NOT EXISTS` 써라.**
2. `LEFT JOIN` 오른쪽 조건을 `WHERE`에 두면? → **INNER JOIN 됨. `ON`에 둬라.**
3. `WHERE DATE(created)='2024-01-01'`의 문제? → **인덱스 못 탐. 범위 조건으로.**
4. 돈을 `FLOAT`로 저장하면? → **정산 오차. `DECIMAL` 써라.**
5. `LAST_VALUE()`가 마지막 값을 안 주는 이유? → **기본 프레임이 현재 행까지. 프레임 명시.**
6. 조인 후 고객 수 세는 법? → **`COUNT(DISTINCT customer_id)`.**
7. `LIMIT 100000, 20`이 느린 이유와 해법? → **앞 행 다 읽고 버림. 커서(keyset) 페이징.**
8. MySQL 기본 격리 수준과 특징? → **REPEATABLE READ + MVCC 스냅샷.**
9. `COUNT(*)` vs `COUNT(col)`? → **후자는 NULL 제외.**
10. `ONLY_FULL_GROUP_BY` 에러가 나면? → **끄지 말고 쿼리를 고쳐라.**

---

> 더 깊게: 각 섹션의 링크를 따라 [reference/mysql8](../../reference/mysql8/index.md)에서 100만 행 테이블로 직접 측정하며 확인하세요.
> 이 문서는 **암기용 인덱스**, reference는 **검증된 교재**입니다.
