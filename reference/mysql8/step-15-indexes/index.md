# Step 15 — 인덱스

> **학습 목표**
> - B+Tree 구조와, InnoDB 의 클러스터드 인덱스(PK) / 세컨더리 인덱스의 관계를 이해한다
> - 카디널리티·선택도로 "인덱스를 걸 가치"를 판단하고, 복합 인덱스의 컬럼 순서(선두 컬럼 규칙)를 정한다
> - 커버링 인덱스로 테이블 접근 자체를 없앤다
> - 인덱스를 못 타는 5가지 패턴(함수·타입변환·앞 % LIKE·OR·`type: index`)을 눈으로 확인한다
> - 8.0 신기능: 내림차순 인덱스·인비저블 인덱스·스킵 스캔을 실측한다
> - **100만 행 `access_logs` 로 인덱스 전후 실행시간을 직접 측정한다**
>
> **선행 스텝**: Step 14 — 뷰와 생성 컬럼
> **예상 소요**: 90분

---

## 15-0. 실습 데이터 — access_logs (100만 행)

이 스텝은 `access_logs` 테이블로 실습합니다. **보조 인덱스가 하나도 없는** 상태로 시작합니다.

```sql
USE shop;
SHOW INDEX FROM access_logs;
```

**결과** (PRIMARY 하나뿐)
```
+-------------+------------+----------+--------------+-------------+
| Table       | Non_unique | Key_name | Seq_in_index | Column_name |
+-------------+------------+----------+--------------+-------------+
| access_logs |          0 | PRIMARY  |            1 | log_id      |
+-------------+------------+----------+--------------+-------------+
```

| 컬럼 | 분포 |
|---|---|
| `log_id` BIGINT PK | 100만 (연속, 일부 구멍) |
| `customer_id` INT | 1~30 (30개 값이 고르게) |
| `path` VARCHAR | 8종 (`/`, `/products`, `/cart` ...) |
| `method` ENUM | GET 70% / POST 20% / PUT 10% |
| `status_code` SMALLINT | 200 75%, 나머지(304/400/404/500/503) 각 5% |
| `duration_ms` INT | 1~3000 |
| `logged_at` DATETIME | 2024-01-01 ~ 2024-12-01 |

> ⚠️ 이 스텝은 **access_logs 에 인덱스를 만들어도 됩니다**(그게 목적). 각 실습 끝에 `DROP INDEX` 로 정리합니다.
> 다른 공용 테이블(customers/orders/...)에는 절대 인덱스를 만들거나 지우지 마세요.

---

## 15-1. B+Tree — 인덱스는 왜 빠른가

인덱스가 없으면 조건에 맞는 행을 찾으려고 **모든 행을 처음부터 끝까지** 읽습니다(풀 테이블 스캔). 100만 행이면 100만 번입니다.

인덱스는 데이터를 **정렬된 트리(B+Tree)** 로 유지합니다. 정렬되어 있으니 "이진 탐색"처럼 몇 번 만에 원하는 지점으로 내려갑니다.

```
                    [ 루트 노드 ]
                   customer_id 기준
              ┌───────────┼───────────┐
           [ ≤10 ]     [ 11~20 ]    [ 21~30 ]        ← 브랜치(내부) 노드
          ┌──┴──┐      ┌──┴──┐      ┌──┴──┐
       [1..5][6..10] [11..15]...  ...        ← 리프 노드 (실제 값 + 정렬됨)
          │    │
   리프끼리 ↔ 양방향 링크드 리스트로 연결 → 범위 검색이 빠르다
   [1..5] ↔ [6..10] ↔ [11..15] ↔ ...
```

- **루트 → 브랜치 → 리프**로 내려가며 탐색. 100만 행이라도 트리 높이는 3~4 정도라 **디스크 접근 3~4번**이면 도달합니다.
- **리프 노드가 정렬되어 있고 서로 링크**되어 있어, `BETWEEN`·`>=`·`ORDER BY` 같은 범위/정렬 연산이 스캔 없이 순차 읽기로 처리됩니다.

> 💡 **B+Tree 의 리프에만 데이터가 있다**
> B**+**Tree 는 리프 노드에만 실제 값이 있고, 내부 노드는 "길 안내"만 합니다. 그래서 리프끼리 연결해 범위 검색을 최적화할 수 있습니다.

---

## 15-2. 첫 인덱스 — 전후 실행시간 실측

100만 행에서 `customer_id = 7` 인 로그 수를 셉니다. **먼저 인덱스 없이:**

```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
+------+-------------+------+---------------+------+------+--------+-------------+
| type | possible_keys | key | key_len | ref | rows   | filtered | Extra       |
| ALL  | NULL          | NULL| NULL    | NULL| 996151 |    10.00 | Using where |
+------+-------------+------+---------------+------+------+--------+-------------+
```

`type: ALL` = 풀 테이블 스캔. `rows: 996151` = 100만 행을 다 읽겠다는 뜻.

```sql
SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
+----------+
| COUNT(*) |
+----------+
|    33333 |
+----------+
1 row in set (0.076 sec)
```

**0.076초.** 이제 인덱스를 만들고 다시 잽니다.

```sql
ALTER TABLE access_logs ADD INDEX idx_customer (customer_id);   -- (0.688 sec, 1회성)

EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
+------+---------------+--------------+---------+-------+-------+----------+-------------+
| type | possible_keys | key          | key_len | ref   | rows  | filtered | Extra       |
| ref  | idx_customer  | idx_customer | 4       | const | 64428 |   100.00 | Using index |
+------+---------------+--------------+---------+-------+-------+----------+-------------+
```

`type: ref` (인덱스로 특정 값을 찾음), `Using index` (커버링 — 아래 15-6), `rows: 64428` (통계 추정치. 실제는 33333).

```sql
SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```

**결과**
```
+----------+
| COUNT(*) |
+----------+
|    33333 |
+----------+
1 row in set (0.003 sec)
```

**0.076초 → 0.003초. 약 25배 빨라졌습니다.**

> 💡 인덱스 생성 자체는 0.688초 걸렸습니다. 인덱스는 "한 번 만들어 두고 수없이 조회"할 때 이득입니다.
> 조회가 거의 없고 쓰기만 많은 테이블에 인덱스를 잔뜩 걸면 손해입니다(아래 15-9).

---

## 15-3. 클러스터드 인덱스와 세컨더리 인덱스 — "PK를 품는다"

InnoDB 에서 **PK 는 특별합니다.** PK 인덱스의 리프 노드가 **행 데이터 그 자체**입니다. 이것을 **클러스터드 인덱스**라고 합니다. 즉 테이블 = PK로 정렬된 B+Tree 입니다.

그럼 PK 가 아닌 인덱스(세컨더리 인덱스)의 리프에는 무엇이 있을까요? **행 전체가 아니라 PK 값**이 들어 있습니다.

```
클러스터드 인덱스 (PK = log_id)          세컨더리 인덱스 (idx_customer)
리프 = [log_id | 행 전체 데이터]          리프 = [customer_id | log_id]
                                                          │
   customer_id 로 찾으면 → log_id 를 얻고 ────────────────┘
   → 그 log_id 로 클러스터드 인덱스를 "다시" 탐색해서 행을 읽는다 (이걸 "북마크 룩업"이라 함)
```

**세컨더리 인덱스로 조회하면 두 번 탐색합니다:** ① 세컨더리 인덱스에서 PK 를 찾고 ② 그 PK 로 클러스터드 인덱스에서 행을 읽습니다.

이 사실에서 두 가지 중요한 결론이 나옵니다.

1. **PK 는 짧아야 한다.** 모든 세컨더리 인덱스가 PK 값을 품기 때문입니다. PK 가 `BIGINT`(8바이트)면 세컨더리 인덱스마다 8바이트씩 더 듭니다. PK 를 `UUID VARCHAR(36)` 로 하면 세컨더리 인덱스가 전부 뚱뚱해집니다.
2. **커버링 인덱스가 가능하다.** 필요한 컬럼이 세컨더리 인덱스 안에 다 있으면 ②단계(클러스터드 재탐색)를 건너뜁니다(15-6).

> 💡 **실무 팁 — PK 는 AUTO_INCREMENT 정수를 권장**
> 클러스터드 인덱스는 PK 순서로 물리 정렬되므로, PK 가 **증가하는 값**이면 새 행이 항상 뒤에 붙어 페이지 분할이 적습니다.
> UUID 처럼 무작위 PK 는 중간중간 삽입되어 페이지 분할·단편화를 유발합니다. (Step 13 의 AUTO_INCREMENT 로 연결됩니다.)

---

## 15-4. 카디널리티와 선택도 — 인덱스 걸 가치가 있는가

- **카디널리티(cardinality)**: 그 컬럼의 **서로 다른 값의 개수**
- **선택도(selectivity)**: `카디널리티 / 전체 행 수`. 1 에 가까울수록 좋다(=값 하나가 적은 행을 가리킴)

```sql
SELECT
  COUNT(*)                                       AS total,
  COUNT(DISTINCT customer_id)                    AS cust_card,
  COUNT(DISTINCT status_code)                    AS status_card,
  ROUND(COUNT(*) / COUNT(DISTINCT customer_id))  AS rows_per_cust,
  ROUND(COUNT(*) / COUNT(DISTINCT status_code))  AS rows_per_status
FROM access_logs;
```

**결과**
```
+---------+-----------+-------------+---------------+-----------------+
| total   | cust_card | status_card | rows_per_cust | rows_per_status |
+---------+-----------+-------------+---------------+-----------------+
| 1000000 |        30 |           6 |         33333 |          166667 |
+---------+-----------+-------------+---------------+-----------------+
```

`customer_id` 는 30종(값 하나당 평균 33,333행), `status_code` 는 6종(값 하나당 평균 166,667행). 둘 다 선택도가 낮습니다.
(선택도 = 카디널리티/전체행. `30/1000000` 처럼 아주 작은 값이라, "값 하나가 몇 행을 가리키나"로 뒤집어 보는 게 직관적입니다.)

> ⚠️ **함정 — 선택도가 낮은 컬럼 단독 인덱스는 효과가 작다**
> `status_code = 200` 은 전체의 75%(75만 행)를 가리킵니다. 이걸 인덱스로 찾아 봐야 75만 번 북마크 룩업을 하느니
> **옵티마이저가 그냥 풀스캔을 고르는 게 더 빠릅니다.** 실제로 걸어도 안 씁니다.
> 인덱스는 "적은 행을 콕 집어낼 때"(높은 선택도) 빛납니다. 성별·상태값처럼 값이 몇 개뿐인 컬럼의 **단독** 인덱스는 대개 낭비입니다.
> (단, 그런 컬럼도 **복합 인덱스의 구성원**으로는 유용할 수 있습니다 — 다음 절.)

---

## 15-5. 복합 인덱스와 선두 컬럼 규칙

"특정 고객의 최근 로그 5건"은 흔한 쿼리입니다.

```sql
SELECT log_id, path, logged_at FROM access_logs
WHERE customer_id = 7 ORDER BY logged_at DESC LIMIT 5;
```

인덱스 없이 하면 풀스캔 + 정렬입니다.
```
| type | ... | Extra                       |
| ALL  | ... | Using where; Using filesort |    ← 100만 행 읽고 정렬 (0.122 sec)
```

`(customer_id, logged_at)` **복합 인덱스**를 만들면:

```sql
ALTER TABLE access_logs ADD INDEX idx_cust_time (customer_id, logged_at);

EXPLAIN SELECT log_id, path, logged_at FROM access_logs
WHERE customer_id = 7 ORDER BY logged_at DESC LIMIT 5;
```

**결과**
```
+------+---------------+---------+-------+---------------------+
| type | key           | key_len | rows  | Extra               |
| ref  | idx_cust_time | 4       | 64428 | Backward index scan |
+------+---------------+---------+-------+---------------------+
```

`Using filesort` 가 사라졌습니다. 인덱스가 이미 `(customer_id, logged_at)` 순으로 정렬돼 있어, customer_id=7 구간을 **정렬된 채로** 읽으면 됩니다. `ORDER BY ... DESC` 는 **뒤에서부터** 읽는 `Backward index scan` 으로 처리합니다. **0.122초 → 0.001초.**

### 선두 컬럼 규칙 — (a, b) 인덱스는 b 단독 조회에 못 쓴다

복합 인덱스 `(customer_id, logged_at)` 는 전화번호부처럼 **"성(customer_id) → 이름(logged_at)" 순**으로 정렬됩니다. 그래서:

- `customer_id = 7` 조회 → **가능** (성으로 찾음)
- `customer_id = 7 AND logged_at >= '...'` → **가능** (성 찾고 이름 범위)
- `logged_at >= '...'` **단독** → **원칙적으로 불가** (이름만으로는 전화번호부를 못 찾음)

```sql
-- 스킵 스캔을 잠깐 끄고 순수한 동작을 봅니다
SET SESSION optimizer_switch = 'skip_scan=off';
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-06-02';
```

**결과**
```
+-------+---------------+---------+--------+--------------------------+
| type  | key           | key_len | rows   | Extra                    |
| index | idx_cust_time | 9       | 996151 | Using where; Using index |
+-------+---------------+---------+--------+--------------------------+
```

`type: index`, `rows: 996151`. 선두 컬럼(customer_id)이 조건에 없으니 인덱스를 **탐색(seek)** 하지 못하고, 어쩔 수 없이 인덱스 전체를 훑습니다.

> ⚠️ **함정 — `type: index` 는 사실상 풀스캔이다**
> `type: index` 를 보고 "인덱스를 탔네, 좋다" 하면 안 됩니다. 이건 **인덱스 B+Tree 를 처음부터 끝까지 훑는** 것으로,
> 테이블 풀스캔(`ALL`)보다 조금 나을 뿐(인덱스가 테이블보다 작고 커버링이면 테이블 접근이 없어서) 여전히 전수 조사입니다.
> `ref`·`range`·`const`·`eq_ref` 여야 진짜로 "탐색"한 것입니다. (EXPLAIN type 등급은 Step 16 에서 정리합니다.)

### 8.0 의 구제책 — 스킵 스캔(Skip Scan)

방금 스킵 스캔을 껐었죠. 다시 켜면(8.0 기본값):

```sql
SET SESSION optimizer_switch = 'skip_scan=on';
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-06-02';
```

**결과**
```
+-------+---------------+---------+--------+----------------------------------------+
| type  | key           | key_len | rows   | Extra                                  |
| range | idx_cust_time | 9       | 110661 | Using where; Using index for skip scan |
+-------+---------------+---------+--------+----------------------------------------+
```

`type: range`, `Using index for skip scan`. MySQL 8.0 은 **선두 컬럼의 값이 몇 개 안 되면**(여기선 customer_id 30종),
"customer_id=1 이면서 logged_at 범위, customer_id=2 이면서 범위, ... 30번 반복"으로 인덱스를 부분 활용합니다.

> 💡 **스킵 스캔은 보너스이지, 선두 컬럼 규칙의 면제가 아니다**
> 스킵 스캔은 선두 컬럼의 **카디널리티가 낮을 때만** 작동합니다. 선두 컬럼이 수천~수만 종이면 스킵 스캔도 포기합니다.
> `logged_at` 로 자주 조회한다면, 스킵 스캔에 기대지 말고 `(logged_at)` 또는 `(logged_at, customer_id)` 인덱스를 따로 만드는 게 정석입니다.

> 💡 **복합 인덱스 컬럼 순서 정하는 법**
> 1. **등치(=) 조건 컬럼을 앞으로**, 범위(`>`, `<`, `BETWEEN`, `LIKE`) 컬럼을 뒤로. (`WHERE a=? AND b>?` → `(a, b)`)
> 2. 앞쪽일수록 **선택도가 높은(값이 다양한)** 컬럼이 유리.
> 3. `ORDER BY` 컬럼을 인덱스 뒤에 두면 filesort 를 없앨 수 있음.
> 이 셋이 충돌하면 **"등치 → 정렬 → 범위"** 순서를 우선하세요.

---

## 15-6. 커버링 인덱스 — 테이블을 아예 안 읽는다

15-3 에서 세컨더리 인덱스는 "인덱스에서 PK 찾고 → 클러스터드에서 행 읽고" 두 번 탐색한다고 했습니다.
그런데 **쿼리가 필요로 하는 컬럼이 전부 인덱스 안에 있으면**, 두 번째 탐색(테이블 접근)을 생략합니다. 이것이 **커버링 인덱스**입니다. EXPLAIN 에 `Using index` 로 나타납니다.

`idx_cust_time = (customer_id, logged_at)` 이 있을 때:

```sql
-- 필요한 컬럼(customer_id, logged_at)이 전부 인덱스에 있다 → 커버링
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id = 7 AND logged_at >= '2024-06-01';
```

**결과**
```
+-------+---------------+---------+-------+--------------------------+
| type  | key           | key_len | rows  | Extra                    |
| range | idx_cust_time | 9       | 33424 | Using where; Using index |   ← Using index = 커버링!
+-------+---------------+---------+-------+--------------------------+
```

```sql
-- path 는 인덱스에 없다 → 테이블을 읽어야 함 (커버링 실패)
EXPLAIN SELECT customer_id, path FROM access_logs
WHERE customer_id = 7 AND logged_at >= '2024-06-01';
```

**결과**
```
+------+--------------+---------+-------+-------------+
| type | key          | key_len | rows  | Extra       |
| ref  | idx_customer | 4       | 64428 | Using where |   ← Using index 없음 = 테이블 접근함
+------+--------------+---------+-------+-------------+
```

> 💡 **실무 팁 — 자주 함께 조회하는 컬럼을 인덱스에 포함시켜라**
> "목록 화면에서 항상 `customer_id, logged_at, status_code` 를 함께 보여준다"면
> `(customer_id, logged_at, status_code)` 로 인덱스를 만들면 그 목록 쿼리가 테이블을 아예 안 읽습니다.
> 단, 컬럼을 넣을수록 인덱스가 커지므로 **정말 자주 쓰는 조합**에만 하세요.

> ⚠️ 커버링을 노린다고 `SELECT *` 를 인덱스로 커버할 수는 없습니다. `*` 는 모든 컬럼이라 사실상 불가능합니다.
> 커버링은 **꼭 필요한 컬럼만 SELECT** 할 때 성립합니다. `SELECT *` 습관이 커버링을 망칩니다.

---

## 15-7. 인덱스를 못 타는 5가지 패턴

인덱스를 만들어도 **쿼리를 이렇게 쓰면 안 탑니다.** 실무 장애의 단골입니다.

### (1) 인덱스 컬럼에 함수를 씌우면 못 탄다

```sql
-- idx_cust_time 이 (customer_id, logged_at) 이지만 logged_at 에 MONTH() 를 씌움
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE MONTH(logged_at) = 6;
```
```
| type  | key           | rows   | Extra                    |
| index | idx_cust_time | 996151 | Using where; Using index |    ← type: index = 풀스캔
```
인덱스는 `logged_at` 원본값으로 정렬돼 있는데 `MONTH(logged_at)` 은 다른 값입니다. **해결**: 범위로 바꾼다.
```sql
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-07-01'   -- sargable
```
(또는 Step 14 의 함수 기반 인덱스를 만든다. 하지만 범위 형태가 더 범용적입니다.)

### (2) 타입 불일치로 컬럼이 변환되면 못 탄다

먼저 **오해 하나를 풀고** 갑시다. 정수 컬럼을 문자열 **리터럴**과 비교하는 건 문제없습니다.

```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = '7';   -- 리터럴 '7'
```
```
| type | key          | ref   | Extra       |
| ref  | idx_customer | const | Using index |    ← 인덱스 정상 사용!
```
MySQL 이 **리터럴 '7' 을 정수 7 로** 변환하기 때문입니다(컬럼은 그대로).

진짜 문제는 **컬럼 쪽이 변환될 때**입니다.

```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE CAST(customer_id AS CHAR) = '7';
```
```
| type  | key          | rows   | Extra                    |
| index | idx_customer | 996151 | Using where; Using index |    ← 풀스캔
```
`CAST(customer_id ...)` 는 (1)의 함수와 같습니다. **컬럼에 손대는 순간** 인덱스가 죽습니다.
현업에서는 "숫자를 VARCHAR 컬럼에 저장해 놓고 숫자로 비교"할 때 이 일이 조용히 벌어집니다.
`WHERE varchar_col = 123` → MySQL 이 컬럼을 숫자로 변환 → 인덱스 무효.

### (3) 앞 % LIKE 는 못 타고, 뒤 % 는 탄다

```sql
ALTER TABLE access_logs ADD INDEX idx_path (path);

EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '%detail';    -- 앞 %
```
```
| type  | key      | rows   | Extra                    |
| index | idx_path | 996151 | Using where; Using index |    ← 풀스캔
```
```sql
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '/products%';  -- 뒤 %
```
```
| type  | key      | rows   | Extra                    |
| range | idx_path | 498075 | Using where; Using index |    ← range! 인덱스 사용
```
전화번호부는 "김"으로 **시작하는** 사람은 빨리 찾지만, "수"로 **끝나는** 사람은 다 뒤져야 합니다.
`LIKE '값%'`(앞이 고정)만 인덱스를 탑니다. `LIKE '%값'`·`LIKE '%값%'` 는 못 탑니다.
**해결**: "끝나는 값" 검색이 잦으면 뒤집어 저장한 컬럼에 인덱스를 걸거나(예: 도메인 역순), 전문검색(15-10)을 씁니다.

### (4) OR 로 서로 다른 컬럼을 묶으면 (인덱스가 없는 쪽이 있으면) 못 탄다

```sql
-- customer_id 는 인덱스 있음, status_code 는 인덱스 없음
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7 OR status_code = 500;
```
```
| type | possible_keys              | key  | rows   | Extra       |
| ALL  | idx_customer,idx_cust_time | NULL | 996151 | Using where |    ← 풀스캔
```
`OR` 은 "둘 중 하나라도 맞으면"이라, 한쪽 컬럼에 인덱스가 없으면 **결국 전 행을 봐야** 합니다.
**해결**: 양쪽 다 인덱스가 있으면 MySQL 이 `index_merge` 로 두 인덱스 결과를 합칠 수도 있습니다. 아니면 `UNION` 으로 쪼갭니다.
```sql
SELECT ... WHERE customer_id = 7
UNION
SELECT ... WHERE status_code = 500;   -- 각 SELECT 가 자기 인덱스를 탄다
```

### (5) 부정 조건(`!=`, `NOT IN`, `IS NOT NULL`)은 대개 못 탄다

"7 이 아닌 것"은 결국 대부분의 행이라 인덱스로 좁혀지지 않습니다. 옵티마이저가 풀스캔을 고릅니다.

```sql
ALTER TABLE access_logs DROP INDEX idx_path;   -- (3)에서 만든 것 정리
```

---

## 15-8. 8.0 신기능 인덱스

### 내림차순 인덱스 (Descending Index)

8.0 부터 인덱스 컬럼별로 정렬 방향을 지정할 수 있습니다. 진가는 **혼합 정렬**에서 나옵니다.

```sql
-- ORDER BY customer_id ASC, logged_at DESC  → ASC 전용 인덱스로는 filesort
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id BETWEEN 5 AND 8 ORDER BY customer_id ASC, logged_at DESC LIMIT 10;
```
```
| type  | key           | Extra                                    |
| range | idx_cust_time | Using where; Using index; Using filesort |    ← filesort!
```

방향이 다른 정렬(`ASC, DESC`)은 모두-ASC 인덱스로 못 맞춥니다(뒤집어도 `DESC, ASC` 만 됨). 딱 맞는 인덱스를 만들면:

```sql
ALTER TABLE access_logs ADD INDEX idx_mixed (customer_id ASC, logged_at DESC);
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id BETWEEN 5 AND 8 ORDER BY customer_id ASC, logged_at DESC LIMIT 10;
```
```
| type  | key       | Extra                    |
| range | idx_mixed | Using where; Using index |    ← filesort 사라짐
```
```sql
ALTER TABLE access_logs DROP INDEX idx_mixed;
```

> 💡 단일 컬럼이나 방향이 모두 같은 정렬은 8.0 의 **Backward index scan** 으로 ASC 인덱스를 뒤에서 읽어 해결하므로 내림차순 인덱스가 필요 없습니다.
> 내림차순 인덱스는 **`ORDER BY a ASC, b DESC` 처럼 방향이 섞였을 때** 쓰세요.

### 인비저블 인덱스 (Invisible Index)

인덱스를 **지우지 않고** 옵티마이저에게만 숨깁니다. "이 인덱스 지워도 될까?"를 안전하게 실험할 때 씁니다.

```sql
ALTER TABLE access_logs ALTER INDEX idx_customer INVISIBLE;   -- 숨김
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
```
```
| possible_keys | key           |
| idx_cust_time | idx_cust_time |    ← idx_customer 가 후보에서 사라짐(다른 인덱스로 대체)
```
```sql
ALTER TABLE access_logs ALTER INDEX idx_customer VISIBLE;     -- 되돌림
```

> 💡 **실무 절차 — 인덱스 삭제 전 리허설**
> 1. 지우려는 인덱스를 `INVISIBLE` 로 바꾼다.
> 2. 며칠간 성능/슬로우 쿼리를 관찰한다. 문제 없으면 → `DROP INDEX`.
> 3. 성능이 나빠지면 → 즉시 `VISIBLE` 로 복구(재생성보다 훨씬 빠름).
> 대용량 테이블에서 인덱스를 잘못 지웠다가 다시 만들면 몇 분~몇십 분이 걸립니다. 인비저블은 그 리스크를 없앱니다.

---

## 15-9. 유니크 / 프리픽스 인덱스

### 유니크 인덱스

값의 유일성을 강제하면서(제약) 동시에 인덱스로 씁니다(성능). `type: const` 로 "정확히 1행"임이 보장됩니다.

```sql
CREATE TABLE s15_uk (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(20) NOT NULL,
  UNIQUE KEY uk_code (code)) ENGINE=InnoDB;
EXPLAIN SELECT * FROM s15_uk WHERE code = 'A';
```
```
| type  | key     | ref   | Extra       |
| const | uk_code | const | Using index |    ← const = 유니크로 딱 1행
```

### 프리픽스 인덱스 — 긴 문자열의 앞 N글자만

`VARCHAR(200)` 전체를 인덱싱하면 인덱스가 큽니다. **앞부분만으로 충분히 구별**된다면 앞 N글자만 인덱싱합니다.

```sql
-- 이메일 3000건 (앞 8글자면 이미 다 구별됨)
SELECT COUNT(DISTINCT email)          AS full_distinct,
       COUNT(DISTINCT LEFT(email, 4)) AS p4,
       COUNT(DISTINCT LEFT(email, 8)) AS p8
FROM s15_email;
```
```
+---------------+------+------+
| full_distinct | p4   | p8   |
+---------------+------+------+
|          3000 | 2927 | 3000 |    ← 8글자 프리픽스가 전체(3000)와 동일한 구별력
+---------------+------+------+
```
```sql
ALTER TABLE s15_email ADD INDEX idx_email_prefix (email(8));   -- 앞 8글자만
```

> 💡 **프리픽스 길이 정하는 법**: `COUNT(DISTINCT LEFT(col, N))` 이 `COUNT(DISTINCT col)` 에 **거의 근접하는 최소 N** 을 고릅니다.
> N 이 너무 짧으면 구별을 못 해 인덱스가 무력하고(위 예에서 URL 처럼 앞부분이 다 같으면 최악), 너무 길면 공간 낭비입니다.

> ⚠️ **함정 — 프리픽스 인덱스는 커버링이 안 되고, ORDER BY 도 못 돕는다**
> 앞 8글자만 저장하므로 인덱스만으로 전체 값을 복원할 수 없어 커버링이 불가능하고,
> 정렬도 8글자까지만 정확해서 `ORDER BY email` 을 완전히 대신하지 못합니다.

---

## 15-10. 전문검색(FULLTEXT) 인덱스

15-7 에서 `LIKE '%값%'` 는 인덱스를 못 탄다고 했습니다. **본문 검색**이 필요하면 FULLTEXT 인덱스를 씁니다.

```sql
CREATE TABLE s15_ft (id INT AUTO_INCREMENT PRIMARY KEY, body TEXT,
  FULLTEXT KEY ft_body (body)) ENGINE=InnoDB;
INSERT INTO s15_ft (body) VALUES
  ('mysql index tuning guide'), ('postgres vacuum internals'), ('mysql replication and index');

EXPLAIN SELECT * FROM s15_ft WHERE MATCH(body) AGAINST('index' IN NATURAL LANGUAGE MODE);
```
```
| type     | key     | Extra                         |
| fulltext | ft_body | Using where; Ft_hints: sorted |
```
```sql
SELECT id, body FROM s15_ft WHERE MATCH(body) AGAINST('index' IN NATURAL LANGUAGE MODE);
```
```
+----+-----------------------------+
| id | body                        |
+----+-----------------------------+
|  1 | mysql index tuning guide    |
|  3 | mysql replication and index |
+----+-----------------------------+
```

> 💡 한글 전문검색은 공백 기준 토큰화로는 잘 안 됩니다. `WITH PARSER ngram` 을 지정해 n-gram 토크나이저를 씁니다.
> 본격적인 한글 검색·형태소 분석이 필요하면 Elasticsearch 같은 전용 엔진을 검토하세요. FULLTEXT 는 "가벼운 본문 검색"용입니다.

---

## 15-11. 인덱스 정리 (실습 마무리)

이 스텝에서 `access_logs` 에 만든 인덱스를 모두 제거해 원래 상태(PK만)로 되돌립니다.

```sql
ALTER TABLE access_logs DROP INDEX idx_customer;
ALTER TABLE access_logs DROP INDEX idx_cust_time;
-- idx_path, idx_mixed, idx_desc 등은 각 절에서 이미 DROP 했습니다.

SHOW INDEX FROM access_logs;   -- PRIMARY 만 남았는지 확인
```

> ⚠️ **"인덱스는 많을수록 좋다"는 착각**
> 인덱스는 조회를 빠르게 하지만, **INSERT/UPDATE/DELETE 마다 모든 관련 인덱스를 함께 갱신**해야 합니다.
> 인덱스 10개짜리 테이블에 INSERT 하면 B+Tree 10개를 수정합니다. 쓰기가 느려지고, 디스크·메모리도 그만큼 더 씁니다.
> - 안 쓰는 인덱스는 **부채**입니다(`sys.schema_unused_indexes` 로 찾을 수 있음).
> - `(a)`, `(a,b)`, `(a,b,c)` 를 다 만들지 마세요. **`(a,b,c)` 하나면 `(a)`·`(a,b)` 조회도 커버**합니다(선두 컬럼 규칙).
> - "혹시 몰라서" 거는 인덱스가 가장 위험합니다. **측정된 느린 쿼리**에만 인덱스를 거세요(Step 16).

---

## 정리

| 개념 | 핵심 |
|---|---|
| B+Tree | 정렬된 트리. 리프에만 값, 리프끼리 연결 → 범위·정렬에 강함 |
| 클러스터드 인덱스 | InnoDB 의 PK. 리프 = 행 데이터 자체 |
| 세컨더리 인덱스 | 리프에 **PK 를 품는다** → PK 는 짧게, 커버링이 가능 |
| 선택도 | 높을수록(값이 다양) 인덱스 효과 큼. 상태값 단독 인덱스는 대개 낭비 |
| 복합 인덱스 | **선두 컬럼 규칙**: `(a,b)` 는 `b` 단독 조회에 못 씀 |
| 컬럼 순서 | 등치 → 정렬 → 범위 순, 선택도 높은 걸 앞으로 |
| 커버링 인덱스 | 필요한 컬럼이 인덱스에 다 있으면 테이블 접근 생략 (`Using index`) |
| `type: index` | 인덱스 풀스캔 = **사실상 전수조사**. 좋은 게 아니다 |
| 못 타는 패턴 | 함수·컬럼 타입변환·앞 % LIKE·OR(한쪽 무인덱스)·부정조건 |
| 내림차순 인덱스(8.0) | 혼합 정렬(`ASC, DESC`)에서 filesort 제거 |
| 인비저블 인덱스(8.0) | 지우지 않고 숨김 → 삭제 전 안전 리허설 |
| 스킵 스캔(8.0) | 선두 컬럼 카디널리티가 낮을 때만 복합 인덱스 부분 활용 |
| 인덱스는 부채 | 쓰기마다 갱신·공간 소비. 측정된 느린 쿼리에만 |

---

## 연습문제

`exercise.sql` 에 7문제가 있습니다. 정답은 `solution.sql`. **직접 EXPLAIN 을 돌려 확인**하세요.

1. 느린 쿼리에 맞는 최적 인덱스 설계하기
2. 주어진 복합 인덱스가 어떤 쿼리를 커버하는지 판정
3. 커버링 인덱스로 `Using index` 만들기
4. 인덱스를 못 타는 쿼리 3개를 sargable 하게 고치기
5. 선택도 계산으로 인덱스 컬럼 순서 정하기
6. 인비저블 인덱스로 "이 인덱스 지워도 되나" 실험
7. 프리픽스 인덱스의 적정 길이 찾기

---

## 다음 단계

인덱스를 설계했으면, 이제 옵티마이저가 그 인덱스를 **정말 쓰는지** 읽어낼 줄 알아야 합니다.
EXPLAIN 의 모든 컬럼, EXPLAIN ANALYZE(실측), 옵티마이저 힌트, 히스토그램, 그리고
"느린 쿼리 → EXPLAIN → 인덱스 설계 → 재측정"의 실전 튜닝 절차로 마무리합니다.

→ [Step 16 — EXPLAIN 과 옵티마이저](../step-16-explain-optimizer/)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 진행합니다. 먼저 `practice.sql` 을 위에서부터 따라 실행하며 15-0 ~ 15-11 의 모든 실측을 재현하고, 그다음 `exercise.sql` 의 7문제를 직접 풀어 본 뒤, `solution.sql` 로 정답과 해설을 대조합니다. 세 파일 모두 `USE shop;` 으로 시작하며, `access_logs` 에 만든 인덱스를 **파일 끝에서 반드시 되돌린다**는 규칙을 공유합니다.

### practice.sql

강의 본문(15-0 ~ 15-11)의 모든 쿼리를 절 번호 주석과 함께 한 파일에 모아 둔 실습 스크립트입니다.

- 실행은 파일 상단 주석의 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 통째로 돌릴 수 있지만, **대화형 클라이언트에서 한 줄씩 실행하는 것을 권합니다.** 15-2 의 "0.076초 → 0.003초" 같은 **실행시간**은 파일 리다이렉션으로는 체감하기 어렵기 때문입니다.
- `[15-2]` 구간은 `EXPLAIN` → `SELECT`(BEFORE) → `ALTER TABLE access_logs ADD INDEX idx_customer (customer_id)` → `EXPLAIN` → `SELECT`(AFTER) 순서로 배치돼 있습니다. 이 **BEFORE/AFTER 쌍**이 이 스텝의 핵심 측정이므로 순서를 건너뛰지 마세요.
- `[15-5]` 는 `SET SESSION optimizer_switch = 'skip_scan=off'` 로 스킵 스캔을 **끈 상태**의 `type: index`(인덱스 풀스캔)를 먼저 보여준 뒤, `skip_scan=on` 으로 되돌려 `Using index for skip scan` 을 보여줍니다. `off` 로 둔 채 뒤 절로 넘어가면 이후 EXPLAIN 결과가 본문과 달라지니 주의하세요(세션 한정이라 재접속하면 기본값으로 돌아옵니다).
- `[15-9]` 의 `INSERT INTO s15_email (email) SELECT CONCAT(SUBSTRING(MD5(n),1,12), '@example.com') FROM tally WHERE n <= 3000` 은 앞선 스텝에서 만든 **`tally` 숫자 테이블**에 의존합니다. `tally` 가 없으면 이 구간이 실패합니다.
- `s15_uk` / `s15_email` / `s15_ft` 는 전부 `DROP TABLE IF EXISTS` 로 시작해 `DROP TABLE` 로 끝나는 **일회용 실습 테이블**이라 몇 번을 다시 돌려도 안전합니다.
- 마지막 `[15-11]` 이 `idx_customer`, `idx_cust_time` 를 `DROP INDEX` 해서 `access_logs` 를 PK만 남은 원래 상태로 되돌립니다. 중간에 멈췄다면 `SHOW INDEX FROM access_logs;` 로 남은 인덱스를 확인하고 직접 정리하세요.

```sql file="./practice.sql"
```

### exercise.sql

7문제의 문제지입니다. 각 문제는 "여기에 작성:" 자리를 비워 두었고, 여러분이 직접 `EXPLAIN` 을 돌려 답을 채우는 구조입니다.

- **문제 1·3·5** 는 인덱스를 **설계**하는 문제(느린 쿼리용 복합 인덱스 / 커버링 인덱스 / 등치 조건 두 개의 컬럼 순서)이고, **문제 2·4·6·7** 은 파일이 미리 인덱스나 테이블을 만들어 주고 그 **동작을 관찰**하는 문제입니다.
- 문제 2·4·6 은 `ALTER TABLE access_logs ADD INDEX idx_test (...)` / `idx_time (logged_at)` / `idx_dur (duration_ms)` 를 **문제지 쪽에서 미리 생성**합니다. 즉 이 파일을 열어 보기만 해도 인덱스가 생기는 게 아니라, 해당 줄을 실행하면 생깁니다.
- ⚠️ **주의 — 이 파일을 통째로 실행하면 마지막 `ALTER TABLE access_logs DROP INDEX idx_dur;` 이 에러날 수 있습니다.** 문제 6 을 손으로 풀면서 이미 `idx_dur` 을 DROP 했다면, 파일 끝의 정리 구문이 "없는 인덱스를 지우려" 하기 때문입니다. 파일 주석이 안내하듯 `--force` 로 넘기거나, 그 줄을 건너뛰면 됩니다. (MySQL 에는 `DROP INDEX ... IF EXISTS` 가 없어서 생기는 불편입니다.)
- 파일 맨 끝의 `SELECT DISTINCT INDEX_NAME ... FROM information_schema.STATISTICS WHERE TABLE_NAME = 'access_logs'` 는 **뒷정리 검증**입니다. 결과가 `PRIMARY` 하나뿐이어야 정상이고, 다른 이름이 남아 있으면 그 인덱스를 직접 DROP 하세요.
- 문제 7 의 `s15_ex_sku` 는 `CONCAT('PROD-2024-', LPAD(n, 8, '0'))` 로 **앞 10글자가 전부 공통인** sku 5000건을 만듭니다. 프리픽스 인덱스가 **무력해지는 데이터**를 일부러 만든 것이며, 이게 문제 7 의 함정이자 학습 포인트입니다.

```sql file="./exercise.sql"
```

### solution.sql

7문제의 정답 쿼리와, "왜 그 답인가"를 설명하는 긴 주석이 함께 들어 있습니다. 문제를 풀어 본 **뒤에** 여세요.

- **정답 1** 은 `idx_q1 (customer_id, method, logged_at)` — 등치 컬럼 두 개를 앞에, 정렬 컬럼을 뒤에 둡니다. `Backward index scan` 으로 filesort 가 사라지고, `status_code`/`duration_ms` 는 SELECT 목록에만 있으므로 **인덱스에 넣지 않는** 판단 근거까지 주석으로 설명합니다.
- **정답 2** 의 핵심은 (c) `WHERE status_code = 200` 이 **못 타는** 것입니다. `idx_test (customer_id, status_code, logged_at)` 의 선두 컬럼이 조건에 없어 `type: index`(인덱스 풀스캔)가 됩니다. 반면 (d)는 중간 컬럼을 건너뛰지만 스킵 스캔이 구제해 줍니다.
- **정답 4** 의 교훈은 한 줄로 요약됩니다: **"컬럼을 가공하지 말고, 비교 대상(리터럴) 쪽을 가공하라."** `YEAR(logged_at) = 2024` → `logged_at >= '2024-01-01' AND logged_at < '2025-01-01'` 로 바꾸는 게 sargable 화입니다.
- **정답 5** 는 둘 다 등치 조건일 때 `customer_id`(30종) vs `path`(8종) 중 **카디널리티가 높은 쪽을 선두로** 두라고 답합니다(1/30 로 좁힘 > 1/8 로 좁힘). 다만 실제 쿼리 패턴도 함께 봐야 한다는 단서를 답니다.
- **정답 7** 이 특히 재미있습니다. `LEFT(sku, 10)` 까지 distinct 가 **1** 이고, 16글자에서 51종, 18글자에서야 5000종이 됩니다. sku 전체 길이가 18글자이므로 **프리픽스로 아낄 공간이 하나도 없는** 경우 — "프리픽스 인덱스가 무의미한 대표 사례"라는 결론에 도달합니다.
- 이 파일 역시 각 정답 블록 끝에서 `DROP INDEX idx_q1` / `idx_test` / `idx_q3` / `idx_time` / `idx_dur` 로 스스로 뒷정리합니다.

```sql file="./solution.sql"
```
