# Step 02 — 데이터베이스·테이블·데이터 타입

> **학습 목표**
> - `CREATE / ALTER / DROP` 으로 스키마를 만들고 고친다
> - 데이터 타입을 **의도적으로** 고른다 (아무거나 `VARCHAR(255)` 쓰지 않기)
> - `DECIMAL vs FLOAT`, `DATETIME vs TIMESTAMP`, `CHAR vs VARCHAR` 의 실제 차이를 눈으로 확인한다
> - 스키마를 조회하는 방법(`SHOW CREATE TABLE`, `information_schema`)을 익힌다
>
> **선행 스텝**: [Step 01](../step-01-setup/index.md)
> **예상 소요**: 60분

---

## 2-0. 이 스텝의 실습 규칙

이 스텝은 테이블을 만들고 고치고 지웁니다. **예제 데이터를 망가뜨리지 않도록 모든 실습 테이블은 `s02_` 접두사를 씁니다.** 맨 끝에 정리 스크립트가 있습니다.

---

## 2-1. 데이터베이스 만들기

```sql
CREATE DATABASE IF NOT EXISTS playground
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

SHOW DATABASES;
```

**결과**
```
+--------------------+
| Database           |
+--------------------+
| information_schema |
| performance_schema |
| playground         |
| shop               |
+--------------------+
```

- `information_schema` — 스키마 메타데이터를 담은 **가상 DB**. 실제 파일이 없고 조회 시점에 만들어집니다.
- `performance_schema` — 서버 내부 성능 지표. Step 24에서 씁니다.
- MySQL에서 **DATABASE와 SCHEMA는 완전히 같은 말**입니다. (`CREATE SCHEMA` == `CREATE DATABASE`) Oracle/PostgreSQL과 다른 점이니 주의하세요.

```sql
DROP DATABASE playground;   -- 되돌릴 수 없습니다. 확인하고 실행하세요.
USE shop;
```

> ⚠️ **함정**: `DROP DATABASE`에는 확인 절차가 없습니다. 엔터를 누르는 순간 끝입니다. 운영 서버에서 `USE`를 잘못한 상태로 이 명령을 치는 사고가 실제로 일어납니다. **접속 계정에 애초에 DROP 권한을 주지 않는 것**이 유일한 방어입니다(Step 22).

---

## 2-2. 숫자 타입 — 크기와 정확도

### 정수

| 타입 | 크기 | 부호 있음 범위 | UNSIGNED 범위 |
|---|---|---|---|
| `TINYINT` | 1B | -128 ~ 127 | 0 ~ 255 |
| `SMALLINT` | 2B | ±32,767 | 0 ~ 65,535 |
| `MEDIUMINT` | 3B | ±838만 | 0 ~ 1,677만 |
| `INT` | 4B | ±21억 | 0 ~ 42억 |
| `BIGINT` | 8B | ±922경 | 0 ~ 1844경 |

- `BOOLEAN` 은 **`TINYINT(1)`의 별칭**일 뿐입니다. 진짜 불린 타입이 없습니다. `TRUE`=1, `FALSE`=0.
- `INT(11)`의 **11은 자릿수 제한이 아닙니다.** 표시 폭(display width)일 뿐이고 저장 범위와 무관합니다. MySQL 8.0.17부터 **deprecated**이니 이제 그냥 `INT`라고 쓰세요.
- 주문 ID처럼 무한히 늘어나는 값은 처음부터 `BIGINT`로. `INT`(21억)는 트래픽 많은 서비스에서 실제로 넘칩니다. 나중에 바꾸려면 테이블 전체를 재작성해야 합니다.

### DECIMAL vs FLOAT — 돈에 FLOAT를 쓰면 안 되는 이유

```sql
CREATE TABLE s02_types (
  c_dec DECIMAL(10,2),   -- 고정소수점: 정확
  c_flt FLOAT,           -- 부동소수점: 근사값
  c_dbl DOUBLE           -- 부동소수점: 근사값
);
INSERT INTO s02_types VALUES (0.1, 0.1, 0.1), (0.2, 0.2, 0.2);

SELECT SUM(c_dec) AS dec_sum,
       SUM(c_flt) AS flt_sum,
       SUM(c_dbl) AS dbl_sum,
       SUM(c_dec) = 0.3 AS dec_eq_03,
       SUM(c_dbl) = 0.3 AS dbl_eq_03
FROM s02_types;
```

**결과**
```
+---------+---------------------+---------------------+-----------+-----------+
| dec_sum | flt_sum             | dbl_sum             | dec_eq_03 | dbl_eq_03 |
+---------+---------------------+---------------------+-----------+-----------+
| 0.30    | 0.30000000447034836 | 0.30000000000000004 |         1 |         0 |
+---------+---------------------+---------------------+-----------+-----------+
```

**0.1 + 0.2 ≠ 0.3** 입니다. `DOUBLE`로 계산하면 `0.30000000000000004`가 나오고, `= 0.3` 비교가 **거짓(0)** 이 됩니다.

이게 실무에서 어떻게 터지냐면: 결제 금액을 `FLOAT`로 저장한 서비스에서 "장바구니 합계 = 결제 금액" 검증이 랜덤하게 실패합니다. 재현도 안 되고 원인도 안 보입니다.

> 💡 **실무 팁**: **돈·수량·비율 등 정확해야 하는 값은 무조건 `DECIMAL(p, s)`.** `p`=전체 자릿수, `s`=소수 자릿수. 원화라면 `DECIMAL(12,2)` 정도가 무난합니다. `FLOAT/DOUBLE`은 과학 계산이나 좌표처럼 오차가 허용되는 곳에만 쓰세요. 이 코스의 `products.price`도 `DECIMAL(10,2)`입니다.

---

## 2-3. 문자열 타입

| 타입 | 특징 | 언제 |
|---|---|---|
| `CHAR(n)` | **고정 길이**. 항상 n바이트 차지. 뒤 공백 제거됨 | 길이가 항상 같은 값 (국가코드 `CHAR(2)`, 해시 `CHAR(64)`) |
| `VARCHAR(n)` | 가변 길이. 실제 길이 + 1~2바이트 | 대부분의 문자열 |
| `TEXT` | 최대 64KB. **DEFAULT 값 못 줌**, 인덱스는 프리픽스만 | 본문, 설명 |
| `ENUM('A','B')` | 내부적으로 정수 저장. 매우 컴팩트 | 상태값처럼 값 집합이 고정 |
| `JSON` | 구조화 문서 (Step 18) | 스키마가 유동적인 속성 |

### CHAR는 뒤 공백을 조용히 삼킵니다

```sql
CREATE TABLE s02_char (a CHAR(5), b VARCHAR(5));
INSERT INTO s02_char VALUES ('ab   ', 'ab   ');   -- 둘 다 'ab' + 공백 3칸

SELECT CONCAT('[', a, ']') AS char_col,
       CONCAT('[', b, ']') AS varchar_col,
       LENGTH(a) AS char_len,
       LENGTH(b) AS varchar_len
FROM s02_char;
```

**결과**
```
+----------+-------------+----------+-------------+
| char_col | varchar_col | char_len | varchar_len |
+----------+-------------+----------+-------------+
| [ab]     | [ab   ]     |        2 |           5 |
+----------+-------------+----------+-------------+
```

`CHAR`에 넣은 뒤 공백은 **조회할 때 사라집니다.** 공백이 의미 있는 데이터라면 `CHAR`를 쓰면 안 됩니다.

> ⚠️ **함정**: `VARCHAR(255)`를 습관적으로 쓰지 마세요. 길이 제한은 **데이터 검증의 마지막 방어선**입니다. 이메일이 `VARCHAR(255)`인데 실제로는 120자면 충분하다면 120으로 두세요. 더 중요한 이유: MySQL이 정렬/임시테이블을 만들 때 `VARCHAR`는 **선언된 최대 길이만큼 메모리를 잡습니다.** 불필요하게 크면 임시 테이블이 메모리를 넘겨 디스크로 떨어집니다(Step 16의 `Using temporary`).

### ENUM

```sql
-- products.status 는 ENUM('ON_SALE','SOLD_OUT','HIDDEN')
SELECT status, COUNT(*) AS cnt FROM products GROUP BY status;
```

**결과**
```
+----------+-----+
| status   | cnt |
+----------+-----+
| ON_SALE  |  37 |
| SOLD_OUT |   2 |
| HIDDEN   |   1 |
+----------+-----+
```

ENUM은 내부적으로 1~2바이트 정수로 저장되어 공간 효율이 좋고, **정의되지 않은 값의 입력을 서버가 막아줍니다**(엄격 모드에서 에러).

> ⚠️ **함정**: ENUM에 값을 추가하려면 `ALTER TABLE`이 필요합니다. 값이 자주 늘어나는 도메인(예: 결제수단이 계속 추가됨)이라면 ENUM 대신 **별도 코드 테이블 + FK**가 낫습니다. 또 하나: ENUM의 정렬 순서는 알파벳순이 아니라 **선언 순서**입니다. `ORDER BY status`가 `HIDDEN, ON_SALE, SOLD_OUT`이 아니라 `ON_SALE, SOLD_OUT, HIDDEN` 순으로 나옵니다.

---

## 2-4. 날짜/시간 타입 — DATETIME vs TIMESTAMP

| 타입 | 범위 | 크기 | 타임존 |
|---|---|---|---|
| `DATE` | 1000-01-01 ~ 9999-12-31 | 3B | 없음 |
| `DATETIME` | 1000 ~ 9999년 | 5B(+소수) | **없음** (적힌 값 그대로) |
| `TIMESTAMP` | **1970 ~ 2038-01-19** | 4B(+소수) | **있음** (UTC 저장 → 세션 TZ로 변환) |
| `TIME` | -838:59:59 ~ 838:59:59 | 3B | 없음 |

이 차이를 직접 봅시다.

```sql
CREATE TABLE s02_time (id INT, dt DATETIME, ts TIMESTAMP);
INSERT INTO s02_time VALUES (1, '2026-07-13 10:00:00', '2026-07-13 10:00:00');

-- 세션 타임존이 KST 일 때
SELECT 'KST(+09:00)' AS session_tz, dt, ts FROM s02_time;

-- 세션 타임존을 UTC로 바꾸면?
SET SESSION time_zone = '+00:00';
SELECT 'UTC(+00:00)' AS session_tz, dt, ts FROM s02_time;

SET SESSION time_zone = '+09:00';   -- 원복
```

**결과**
```
+-------------+---------------------+---------------------+
| session_tz  | dt                  | ts                  |
+-------------+---------------------+---------------------+
| KST(+09:00) | 2026-07-13 10:00:00 | 2026-07-13 10:00:00 |
+-------------+---------------------+---------------------+
+-------------+---------------------+---------------------+
| session_tz  | dt                  | ts                  |
+-------------+---------------------+---------------------+
| UTC(+00:00) | 2026-07-13 10:00:00 | 2026-07-13 01:00:00 |   ← ts 만 바뀜!
+-------------+---------------------+---------------------+
```

**같은 행인데 `ts`만 값이 달라졌습니다.** `TIMESTAMP`는 UTC로 저장하고 읽을 때 세션 타임존으로 변환하기 때문입니다. `DATETIME`은 저장된 문자 그대로입니다.

어느 쪽을 쓸까:

- **글로벌 서비스, "이 사건이 절대적으로 언제 일어났나"** → `TIMESTAMP` (또는 `DATETIME` + UTC로 통일해 저장)
- **"사용자가 입력한 날짜 그 자체"** (생일, 예약일, 계약 만료일) → `DATETIME` / `DATE`
- 생일을 `TIMESTAMP`로 저장하면 해외에서 조회할 때 하루 밀립니다.

> ⚠️ **함정 (2038년 문제)**: `TIMESTAMP`는 32비트라서 **2038-01-19에 오버플로우**합니다. 구독 만료일, 보증 기간처럼 먼 미래 날짜를 다룬다면 `TIMESTAMP`를 쓰면 안 됩니다.

> 💡 **실무 팁**: 자동 생성/수정 시각은 이렇게 선언합니다.
> ```sql
> created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
> updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
> ```
> `ON UPDATE CURRENT_TIMESTAMP`는 UPDATE 시 자동으로 시각을 갱신합니다. 애플리케이션에서 매번 챙기지 않아도 됩니다.

---

## 2-5. 테이블 만들기 — 전체 문법

```sql
CREATE TABLE s02_orders (
  -- 컬럼 정의
  order_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no     CHAR(16)        NOT NULL COMMENT '외부 노출용 주문번호',
  customer_id  INT UNSIGNED    NOT NULL,
  amount       DECIMAL(12,2)   NOT NULL DEFAULT 0,
  status       ENUM('PENDING','PAID','CANCELLED') NOT NULL DEFAULT 'PENDING',
  memo         VARCHAR(200)    NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  -- 키와 제약 (Step 13에서 자세히)
  PRIMARY KEY (order_id),
  UNIQUE  KEY uk_s02_orders_no (order_no),
  KEY     idx_s02_orders_customer (customer_id),
  CONSTRAINT chk_s02_orders_amount CHECK (amount >= 0),
  CONSTRAINT fk_s02_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
)
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COMMENT='Step02 실습용 주문';
```

읽는 순서: **컬럼 → 키 → 제약 → 테이블 옵션**.

- `NOT NULL` — 기본값은 NULL 허용입니다. **NULL을 허용할 이유가 없으면 NOT NULL을 붙이세요.** NULL은 비교/집계에서 특이하게 동작해서 버그의 온상입니다(Step 05).
- `AUTO_INCREMENT` — 테이블당 1개, 반드시 인덱스가 있어야 합니다.
- `COMMENT` — 컬럼과 테이블에 설명을 남기세요. 6개월 뒤의 당신이 고마워합니다.

---

## 2-6. 스키마 조회하기

### SHOW CREATE TABLE — 가장 자주 쓰는 명령

```sql
SHOW CREATE TABLE products\G
```

**결과**
```
*************************** 1. row ***************************
       Table: products
Create Table: CREATE TABLE `products` (
  `product_id` int unsigned NOT NULL AUTO_INCREMENT,
  `category_id` int unsigned NOT NULL,
  `name` varchar(100) NOT NULL,
  `price` decimal(10,2) NOT NULL COMMENT '판매가',
  `cost` decimal(10,2) NOT NULL COMMENT '원가',
  `stock` int NOT NULL DEFAULT '0',
  `status` enum('ON_SALE','SOLD_OUT','HIDDEN') NOT NULL DEFAULT 'ON_SALE',
  `attrs` json DEFAULT NULL COMMENT '상품별 가변 속성',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`),
  KEY `idx_products_category` (`category_id`),
  CONSTRAINT `fk_products_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`),
  CONSTRAINT `chk_products_price` CHECK ((`price` >= 0)),
  CONSTRAINT `chk_products_stock` CHECK ((`stock` >= 0))
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='상품'
```

**서버가 실제로 이해하고 있는 정의**를 그대로 보여줍니다. 내가 쓴 DDL과 다를 수 있습니다(MySQL이 정규화해서 저장하므로). 마이그레이션 검증 시 이 출력을 비교하는 게 가장 확실합니다.

### DESCRIBE — 빠른 요약

```sql
DESCRIBE customers;   -- 또는 DESC customers;
```

**결과**
```
+-------------+-------------------------------------+------+-----+-------------------+-------------------+
| Field       | Type                                | Null | Key | Default           | Extra             |
+-------------+-------------------------------------+------+-----+-------------------+-------------------+
| customer_id | int unsigned                        | NO   | PRI | NULL              | auto_increment    |
| email       | varchar(120)                        | NO   | UNI | NULL              |                   |
| name        | varchar(50)                         | NO   |     | NULL              |                   |
| phone       | varchar(20)                         | YES  |     | NULL              |                   |
| grade       | enum('BRONZE','SILVER','GOLD','VIP')| NO   |     | BRONZE            |                   |
| birth_date  | date                                | YES  |     | NULL              |                   |
| city        | varchar(30)                         | NO   |     | NULL              |                   |
| points      | int                                 | NO   |     | 0                 |                   |
| created_at  | datetime                            | NO   |     | CURRENT_TIMESTAMP | DEFAULT_GENERATED |
+-------------+-------------------------------------+------+-----+-------------------+-------------------+
```

### information_schema — 프로그래밍 가능한 스키마 조회

`SHOW`는 사람용, `information_schema`는 **SQL로 가공할 수 있는** 기계용입니다.

```sql
-- shop DB의 테이블별 행 수와 용량
SELECT
  table_name,
  table_rows                                   AS approx_rows,
  ROUND(data_length  / 1024 / 1024, 2)         AS data_mb,
  ROUND(index_length / 1024 / 1024, 2)         AS index_mb,
  table_comment
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_type = 'BASE TABLE'
ORDER BY data_length DESC;
```

**결과**
```
+-------------+-------------+---------+----------+-----------------------------+
| TABLE_NAME  | approx_rows | data_mb | index_mb | TABLE_COMMENT               |
+-------------+-------------+---------+----------+-----------------------------+
| access_logs |      996151 |   63.59 |     0.00 | 접근 로그(대용량 실습용)    |
| tally       |       10000 |    0.27 |     0.00 | 보조용 숫자 테이블 1..10000 |
| order_items |        1200 |    0.09 |     0.11 | 주문 상세                   |
| orders      |         600 |    0.06 |     0.02 | 주문                        |
| payments    |         540 |    0.06 |     0.02 | 결제                        |
| categories  |          17 |    0.02 |     0.02 | 상품 카테고리(계층형)       |
| customers   |          30 |    0.02 |     0.02 | 고객                        |
| employees   |          18 |    0.02 |     0.02 | 사원(계층형 조직)           |
| products    |          40 |    0.02 |     0.02 | 상품                        |
| reviews     |          80 |    0.02 |     0.03 | 상품 후기                   |
+-------------+-------------+---------+----------+-----------------------------+
```

> ⚠️ **함정**: `table_rows`는 **추정값**입니다. InnoDB는 통계 샘플링으로 계산하기 때문에, 실제로 정확히 100만 행인 `access_logs`가 996,151로 나옵니다(실행할 때마다 조금씩 달라집니다). 정확한 개수가 필요하면 반드시 `SELECT COUNT(*)`를 쓰세요.

> 💡 **실무 팁**: `access_logs`의 `index_mb`가 0인 게 보이나요? 보조 인덱스를 하나도 안 만들었기 때문입니다. Step 15에서 여기에 인덱스를 붙이며 조회 속도와 디스크 사용량이 어떻게 바뀌는지 실측합니다.

> 💡 **실무 팁**: `index_mb`가 `data_mb`보다 크다면 인덱스를 과하게 만든 것입니다. 인덱스는 공짜가 아닙니다 — 쓰기가 느려지고 디스크를 먹습니다(Step 15).

---

## 2-7. 테이블 고치기 — ALTER TABLE

```sql
-- 컬럼 추가
ALTER TABLE s02_orders ADD COLUMN coupon_code VARCHAR(20) NULL AFTER memo;

-- 컬럼 타입/속성 변경 (컬럼명 유지)
ALTER TABLE s02_orders MODIFY COLUMN memo VARCHAR(500) NULL;

-- 컬럼명까지 변경
ALTER TABLE s02_orders CHANGE COLUMN memo note VARCHAR(500) NULL;

-- 컬럼명만 변경 (8.0 신문법, 타입 안 써도 됨)
ALTER TABLE s02_orders RENAME COLUMN note TO memo;

-- 컬럼 삭제
ALTER TABLE s02_orders DROP COLUMN coupon_code;

-- 인덱스 추가/삭제
ALTER TABLE s02_orders ADD INDEX idx_s02_orders_status (status);
ALTER TABLE s02_orders DROP INDEX idx_s02_orders_status;

-- 테이블명 변경
RENAME TABLE s02_orders TO s02_orders_v2;
RENAME TABLE s02_orders_v2 TO s02_orders;
```

### 여러 변경은 한 번에 묶으세요

```sql
-- 나쁨: 테이블을 3번 재구성
ALTER TABLE s02_orders ADD COLUMN a INT;
ALTER TABLE s02_orders ADD COLUMN b INT;
ALTER TABLE s02_orders ADD COLUMN c INT;

-- 좋음: 한 번에
ALTER TABLE s02_orders ADD COLUMN a INT, ADD COLUMN b INT, ADD COLUMN c INT;
```

### 운영 중 ALTER는 위험합니다

MySQL 8은 대부분의 `ALTER`를 **Online DDL**로 처리해서 읽기/쓰기를 막지 않습니다. 하지만 **전부는 아닙니다.**

```sql
-- 알고리즘을 명시해서, 테이블 복사(느리고 락 걸림)가 필요하면 아예 실패하게 만드세요
ALTER TABLE s02_orders
  ADD COLUMN memo2 VARCHAR(100),
  ALGORITHM=INPLACE, LOCK=NONE;
```

`ALGORITHM=INPLACE, LOCK=NONE`을 붙이면, 이 변경이 온라인으로 불가능할 때 **조용히 테이블을 통째로 복사하는 대신 에러를 냅니다.** 1억 행 테이블에서 몇 시간짜리 락이 걸리는 사고를 예방합니다.

| ALGORITHM | 뜻 |
|---|---|
| `INSTANT` | 메타데이터만 수정. 즉시 완료 (8.0.12+: 컬럼 추가 등) |
| `INPLACE` | 테이블 복사 없이 처리. 보통 온라인 가능 |
| `COPY` | 테이블 전체 재작성. **느리고 오래 락** |

> 💡 **실무 팁**: 대형 테이블 스키마 변경은 `pt-online-schema-change`(Percona)나 `gh-ost`(GitHub) 같은 도구를 씁니다. 원본을 두고 새 테이블에 복사하며 트리거/binlog로 변경분을 따라잡은 뒤 원자적으로 교체하는 방식입니다.

---

## 2-8. 실습 정리

```sql
DROP TABLE IF EXISTS s02_orders;
DROP TABLE IF EXISTS s02_types;
DROP TABLE IF EXISTS s02_time;
DROP TABLE IF EXISTS s02_char;
```

---

## 정리

| 고민 | 답 |
|---|---|
| 돈을 저장한다 | `DECIMAL(12,2)`. **절대 FLOAT/DOUBLE 금지** |
| ID가 계속 늘어난다 | 처음부터 `BIGINT UNSIGNED` |
| 참/거짓 | `TINYINT(1)` (= `BOOLEAN`) |
| 상태값이 고정 | `ENUM` (자주 늘어나면 코드 테이블 + FK) |
| 사용자가 입력한 날짜 | `DATE` / `DATETIME` |
| 사건이 일어난 절대 시각 | `TIMESTAMP` (단, 2038년 한계 주의) |
| 자동 생성/수정 시각 | `DATETIME DEFAULT CURRENT_TIMESTAMP [ON UPDATE CURRENT_TIMESTAMP]` |
| 길이가 항상 같은 문자열 | `CHAR(n)` (단, 뒤 공백 사라짐) |
| 일반 문자열 | `VARCHAR(꼭 필요한 만큼)` |
| 스키마 확인 | `SHOW CREATE TABLE t\G` |
| 스키마를 SQL로 가공 | `information_schema.columns/tables` |
| 대형 테이블 변경 | `ALGORITHM=INPLACE, LOCK=NONE` 명시 |

---

## 연습문제

`exercise.sql` 로 풀고 `solution.sql` 로 답을 맞춰보세요. 두 파일의 전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다.

1. `s02_coupons` 테이블을 설계하시오. (쿠폰코드 유니크, 할인율 또는 할인금액, 유효기간, 사용여부, 생성시각 자동)
2. `shop` DB에서 `NULL`을 허용하는 컬럼을 모두 찾으시오. (information_schema 사용)
3. `shop` DB에서 `DECIMAL` 타입 컬럼을 테이블/컬럼명과 함께 나열하시오.
4. `products` 테이블에 `updated_at` 컬럼을 추가하는 ALTER 문을 작성하되, 온라인 DDL이 불가능하면 실패하도록 하시오.
5. `TIMESTAMP` 컬럼에 `'2039-01-01'`을 넣으면 어떻게 되는지 확인하시오.
6. `CHAR(3)`에 4글자를 넣으면 어떻게 되는가? 엄격 모드를 끄면 어떻게 달라지는가?

---

## 다음 단계

→ [Step 03 — 예제 데이터베이스 구축과 탐색](../step-03-sample-database/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 세 개로 구성됩니다. 먼저 `practice.sql` 을 처음부터 끝까지 실행하며 본문 2-1 ~ 2-8 의 예제를 직접 눈으로 확인하고, 그다음 `exercise.sql` 의 문제 7개를 스스로 풀어본 뒤, 마지막에 `solution.sql` 로 답과 해설을 대조합니다.

세 파일 모두 실습 테이블에 `s02_` 접두사를 쓰므로 예제 DB인 `shop` 의 원본 데이터는 손상되지 않습니다. `practice.sql` 과 `solution.sql` 은 맨 끝에서 자신이 만든 테이블을 스스로 `DROP` 합니다. 다만 `exercise.sql` 의 정리 구문(`-- DROP TABLE IF EXISTS s02_coupons, s02_orders_copy;`)은 **주석 처리되어 있습니다.** 문제를 푸는 도중 테이블이 지워지면 곤란하기 때문이니, 실습을 마친 뒤 이 줄의 주석을 직접 풀어 실행하세요.

### practice.sql

본문의 모든 예제를 한 파일에 순서대로 담아 놓은 **따라치기용 스크립트**입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 로 한 번에 흘려도 되지만, 각 결과를 눈으로 확인해야 학습이 되므로 **섹션별로 잘라서 붙여넣는 것을 권합니다.**

- `[2-1]` 은 `CREATE DATABASE IF NOT EXISTS playground` 로 DB를 만든 뒤 곧바로 `DROP DATABASE playground` 로 지웁니다. **`DROP DATABASE` 에는 확인 절차가 없다는 것**을 체험하는 대목이니, 스크립트를 수정해 다른 DB 이름을 넣지 않도록 주의하세요.
- `[2-2]` 의 `s02_types` 는 같은 값 `0.1`, `0.2` 를 `DECIMAL(10,2)` / `FLOAT` / `DOUBLE` 세 컬럼에 동시에 넣습니다. `SUM(c_dec) = 0.3` 은 1(참)인데 `SUM(c_dbl) = 0.3` 은 0(거짓)으로 나오는 것이 이 스텝의 핵심 장면입니다.
- `[2-5]` 의 `s02_orders` 는 `DO SLEEP(1)` 로 1초를 쉰 뒤 `UPDATE` 를 실행합니다. 이 1초가 없으면 `created_at` 과 `updated_at` 이 같은 초로 찍혀서 `ON UPDATE CURRENT_TIMESTAMP` 가 동작했는지 구분되지 않습니다.
- `[2-5]` 의 `FOREIGN KEY (customer_id) REFERENCES customers(customer_id)` 때문에 이 스크립트는 반드시 **`shop` DB에 접속한 상태**여야 하고, Step 01 의 예제 데이터가 적재되어 있어야 합니다. `INSERT ... VALUES ('ORD-2026-000001', 1, 50000)` 의 `1` 은 실제로 존재하는 `customer_id` 입니다.
- `[2-7]` 은 `ADD COLUMN` → `MODIFY` → `CHANGE` → `RENAME COLUMN` → `DROP COLUMN` 을 순서대로 실행하므로 **앞 문장이 성공해야 다음 문장이 성립합니다.** 중간부터 실행하면 "Unknown column" 에러가 납니다.
- 마지막 `[2-8]` 이 `s02_orders`, `s02_types`, `s02_time`, `s02_char` 를 전부 `DROP` 하므로 실습 후 흔적이 남지 않습니다.

```sql file="./practice.sql"
```

### exercise.sql

위 "연습문제"에 대응하는 **문제지**입니다. 정답은 비어 있고 주석으로 요구사항만 적혀 있으니, 각 문제 아래에 직접 SQL을 써 넣으며 푸세요. 본문 목록은 6문제지만 파일에는 **7문제**가 들어 있습니다. 두 가지가 다릅니다.

- 파일의 **문제 4**는 본문(`products` 에 `updated_at` 추가)과 달리, 바로 앞 문제 1에서 만든 `s02_coupons` 에 `used_count INT NOT NULL DEFAULT 0` 을 추가하도록 바뀌어 있습니다. 공용 예제 테이블인 `products` 를 건드리지 않게 하려는 의도이니, 파일 쪽 지시를 따르세요.
- 파일에만 있는 **문제 7**은 `orders.status` ENUM 에 `'REFUNDED'` 를 추가하는 문제입니다.

풀이 힌트:

- 문제 1(`s02_coupons` 설계)이 이 스텝의 종합 문제입니다. "앞으로 수억 장 발행 예정"(→ `BIGINT`), "16자 고정"(→ `CHAR(16)`), "원 단위, 정확해야 함"(→ `DECIMAL`), "참/거짓"(→ `TINYINT(1)`) 처럼 **요구사항 문구 하나하나가 타입 선택의 근거**로 설계되어 있습니다.
- 문제 2·3은 `information_schema.columns` 를 SQL로 가공하는 연습입니다. 2-6 에서는 `information_schema.tables` 만 다뤘으니, 여기서는 `is_nullable`, `data_type`, `numeric_precision`, `numeric_scale` 컬럼을 새로 쓰게 됩니다. (`practice.sql` 의 `[2-6]` 마지막 쿼리가 `columns` 조회 예시입니다.)
- 문제 5·6은 **일부러 에러를 내보는 문제**입니다. `TIMESTAMP` 에 `'2039-01-01'` 을 넣으면 `ERROR 1292`, `CHAR(3)` 에 4글자를 넣으면 `ERROR 1406` 이 납니다. 에러 메시지 자체가 학습 목표이니 겁내지 말고 실행하세요.
- 문제 6에서 `SET SESSION sql_mode = ''` 로 엄격 모드를 끄게 되는데, **세션 한정**이라 접속을 끊으면 원복됩니다. 그래도 실습 후에는 solution.sql 처럼 `sql_mode` 를 명시적으로 되돌려 두는 습관을 들이세요.
- 문제 7은 **`orders` 를 직접 `ALTER` 하지 말라**고 경고합니다. 공용 예제 테이블이라 다른 스텝이 깨집니다. 반드시 `CREATE TABLE s02_orders_copy LIKE orders` 로 사본을 뜬 뒤 실습하세요.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 7문제의 **정답과 해설**입니다. 답만 있는 게 아니라 "왜 이 타입인가"가 주석으로 붙어 있으니, 문제를 푼 뒤 자신의 답과 비교하며 읽으세요.

- 문제 1의 `s02_coupons` 는 `valid_to DATETIME` 에 `'TIMESTAMP 아님! 2038년 이후 만료일도 있을 수 있음'` 이라는 주석을 달아 2038년 문제를 다시 못 박습니다. 또 `CONSTRAINT chk_s02_coupons_discount` 가 "`discount_type='RATE'` 인데 `discount_rate` 가 NULL" 같은 모순 데이터를 DB 레벨에서 막습니다(위반 시 `ERROR 3819`).
- 문제 4는 `ALGORITHM=INPLACE, LOCK=NONE` 과 `ALGORITHM=INSTANT` 두 가지를 모두 보여줍니다. 단순 컬럼 추가는 8.0.12+ 에서 `INSTANT` 로 즉시 끝난다는 점을 확인하세요.
- 문제 5·6에서 **에러가 나는 INSERT 문은 주석 처리되어 있습니다.** 파일을 통째로 흘려도 중단되지 않게 하기 위함이니, 에러를 직접 보고 싶다면 주석(`--`)을 풀고 그 줄만 따로 실행하세요.
- 문제 6 끝에 `SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,...'` 로 엄격 모드를 **되돌려 놓는 줄**이 있습니다. 이 줄을 빼먹으면 그 세션의 이후 실습이 조용히 데이터를 잘라 저장하게 됩니다.
- 문제 7의 해설에 있는 "**새 ENUM 값은 반드시 맨 뒤에 추가하라**"는 경고가 실무에서 가장 중요한 대목입니다. 중간에 끼워 넣으면 기존 행의 내부 정수값이 밀려 데이터가 뒤바뀔 수 있습니다.
- 마지막에 `s02_coupons`, `s02_ts_test`, `s02_char_test`, `s02_orders_copy` 를 모두 `DROP` 합니다.

```sql file="./solution.sql"
```
