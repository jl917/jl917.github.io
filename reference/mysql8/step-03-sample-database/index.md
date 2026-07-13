# Step 03 — 예제 데이터베이스 구축과 탐색

> **학습 목표**
> - 이 코스 전체에서 쓸 `shop` 데이터베이스를 구축한다
> - 테이블 8개의 관계(ER)를 머릿속에 넣는다 — **이걸 알아야 이후 모든 스텝이 쉬워진다**
> - 처음 보는 DB를 만났을 때 구조를 파악하는 방법을 익힌다
> - 언제든 초기 상태로 되돌리는 법을 안다
>
> **선행 스텝**: [Step 02](../step-02-ddl-datatypes/index.md)
> **예상 소요**: 40분

---

## 3-1. 한 번에 설치하기

```bash
cd mysql8/sql
./install.sh          # 스키마 + 데이터 (5초)
./install.sh --big    # + 100만 행 access_logs (Step 15부터 필요, 30초~1분)
```

**결과**
```
▶ MySQL 접속 확인 (127.0.0.1:3307)
mysql_version
8.0.46
▶ 01_schema.sql
▶ 02_seed_master.sql
msg                     categories  customers  products  employees  tally
02_seed_master.sql 완료         17         30        40         18  10000
▶ 03_seed_orders.sql
msg                     orders  order_items  payments  reviews
03_seed_orders.sql 완료     600         1200       540       80
status     cnt  sum_amount
DELIVERED  240  326,280,000
SHIPPED    120  162,834,000
PAID       120  132,630,000
CANCELLED   60   47,742,000
PENDING     60   95,112,000
✅ 완료
```

**언제든 이 스크립트를 다시 돌리면 완전한 초기 상태로 돌아갑니다.** 실습하다 데이터를 망가뜨렸다면 주저 말고 다시 실행하세요.

### 왜 데이터가 항상 똑같은가

시드 스크립트 `03_seed_orders.sql`(`mysql8/sql` 디렉터리)은 `RAND()`를 쓰지 않습니다. 대신 **나머지 연산(`%`)** 으로 값을 만듭니다.

```sql
-- 주문 600건을 만드는 핵심 부분
SELECT
    t.n                                   AS order_id,
    c.customer_id,                        -- customer_id = 1 + (n * 17) % 30
    DATE_ADD('2024-01-01', INTERVAL (t.n * 37) % 730 DAY)  AS order_date,
    ELT(1 + (t.n * 7) % 10,               -- 상태를 10개 슬롯에 분배
        'DELIVERED','DELIVERED','PAID','SHIPPED','DELIVERED',
        'CANCELLED','PAID','DELIVERED','SHIPPED','PENDING') AS status
FROM tally t
JOIN customers c ON c.customer_id = 1 + (t.n * 17) % 30
WHERE t.n <= 600;
```

덕분에 **여러분 화면의 결과가 이 교재의 결과와 정확히 일치합니다.** 숫자가 다르면 뭔가 잘못된 것이니 바로 알아챌 수 있습니다.

> 💡 **실무 팁**: `tally`(1~10000 숫자 테이블)를 `JOIN`해서 행을 불려 데이터를 만드는 이 기법은 실무에서도 매우 유용합니다. 성능 테스트용 더미 데이터, 날짜 구간 채우기(Step 09), 리포트의 빈 칸 메우기에 두루 씁니다.

---

## 3-2. shop 데이터베이스 구조

가상의 온라인 쇼핑몰입니다.

```
                     ┌──────────────┐
                     │  categories  │◄─┐ parent_id (자기참조: 대분류 ─ 소분류)
                     └──────┬───────┘  │
                            │ 1        └──┘
                            │
                            │ N
   ┌────────────┐    ┌──────▼───────┐    ┌──────────────┐
   │ customers  │    │   products   │    │  employees   │◄─┐ manager_id
   └─────┬──────┘    └──────┬───────┘    └──────────────┘  │ (자기참조: 조직도)
         │ 1                │ 1                            └──┘
         │                  │                          (주문과 무관. 계층 쿼리 연습용)
         │ N                │ N
   ┌─────▼──────┐    ┌──────▼───────┐
   │   orders   │───►│ order_items  │
   └─────┬──────┘ 1:N└──────────────┘
         │ 1
         │ N
   ┌─────▼──────┐         ┌──────────────┐
   │  payments  │         │   reviews    │──► products, customers
   └────────────┘         └──────────────┘
```

### 테이블 한눈에

| 테이블 | 행 수 | 설명 | 이 테이블로 배우는 것 |
|---|---:|---|---|
| `categories` | 17 | 카테고리 (대분류 5 + 소분류 12) | 자기참조, 재귀 CTE |
| `customers` | 30 | 고객 (등급/도시/포인트) | GROUP BY, 세그먼트 분석 |
| `products` | 40 | 상품 (가격/재고/JSON 속성) | JSON, 집계 |
| `orders` | 600 | 주문 (2024-01-02 ~ 2025-12-30) | JOIN, 윈도우 함수, 시계열 |
| `order_items` | 1,200 | 주문 상세 (주문당 1~3개) | 다대다 해소, 집계 |
| `payments` | 540 | 결제 (PENDING 주문엔 없음) | LEFT JOIN, 안티 조인 |
| `reviews` | 80 | 후기 (일부 배송완료 주문만) | 평점 집계, 상관 서브쿼리 |
| `employees` | 18 | 사원 (4단계 조직도) | SELF JOIN, 재귀 CTE |
| `tally` | 10,000 | 숫자 1~10000 | 데이터 생성, 구간 채우기 |
| `access_logs` | 1,000,000 | 접근 로그 (**인덱스 없음**) | 인덱스, EXPLAIN, 파티셔닝 |

### 데이터를 이렇게 설계한 이유 (중요)

이 데이터에는 **일부러 심어둔 함정**들이 있습니다. 나중에 각 스텝에서 재료로 씁니다.

| 심어둔 것 | 어디서 쓰나 |
|---|---|
| `customers.phone`이 **NULL인 고객 3명** (윤대현, 남규리, 심준호) | Step 05 — NULL 3값 논리, `NOT IN` 함정 |
| **PENDING 주문 60건엔 결제가 없음** | Step 07/08 — `LEFT JOIN ... IS NULL`, `NOT EXISTS` |
| **후기가 하나도 없는 상품 24개** | Step 07/08 — 안티 조인, `NOT EXISTS` |
| `products.attrs`가 **NULL인 상품 2개** (USB-C 허브, 프리미엄 라면) | Step 18 — JSON 함수의 NULL 처리 |
| `products.status`에 `SOLD_OUT` 2개, `HIDDEN` 1개 | Step 06 — 필터링이 집계에 미치는 영향 |
| `orders.status`가 5종류로 **비율까지 고정** | Step 06 — GROUP BY, 조건부 집계 |
| `access_logs`에 **인덱스가 하나도 없음** | Step 15/16 — 인덱스를 직접 붙이며 효과 실측 |
| 카테고리·사원의 **자기참조 계층** | Step 09 — 재귀 CTE |

반대로, **모든 고객은 최소 1건 이상 주문했습니다.** (`customer_id = 1 + (n * 17) % 30` 이 30명을 고르게 순환하므로) 그래서 "주문이 없는 고객 찾기" 안티 조인은 **0건**을 반환합니다 — 이것도 좋은 학습 재료입니다. 안티 조인이 빈 결과를 냈을 때 "쿼리가 틀렸나?"가 아니라 "정말 없구나"를 구분할 수 있어야 하니까요.

---

## 3-3. 데이터 둘러보기

```sql
USE shop;

SELECT * FROM orders ORDER BY order_id LIMIT 5;
```

**결과**
```
+----------+-------------+---------------------+-----------+--------------+---------------+
| order_id | customer_id | order_date          | status    | total_amount | shipping_city |
+----------+-------------+---------------------+-----------+--------------+---------------+
|        1 |          18 | 2024-02-07 13:07:00 | DELIVERED |   1836000.00 | 인천          |
|        2 |           5 | 2024-03-15 02:14:00 | DELIVERED |   6663900.00 | 인천          |
|        3 |          22 | 2024-04-21 15:21:00 | DELIVERED |    658000.00 | 서울          |
|        4 |           9 | 2024-05-28 04:28:00 | SHIPPED   |    837000.00 | 부산          |
|        5 |          26 | 2024-07-04 17:35:00 | CANCELLED |   1194000.00 | 인천          |
+----------+-------------+---------------------+-----------+--------------+---------------+
```

주문 1건이 상세 여러 줄로 갈라집니다.

```sql
SELECT * FROM order_items ORDER BY order_item_id LIMIT 5;
```

**결과**
```
+---------------+----------+------------+----------+------------+
| order_item_id | order_id | product_id | quantity | unit_price |
+---------------+----------+------------+----------+------------+
|             1 |        1 |         21 |        3 |  459000.00 |
|             2 |        1 |         34 |        1 |  459000.00 |   ← order 1 은 상품 2개
|             3 |        2 |         28 |        1 |   15900.00 |
|             4 |        2 |          1 |        2 |   39000.00 |
|             5 |        2 |         14 |        3 | 2190000.00 |   ← order 2 는 상품 3개
+---------------+----------+------------+----------+------------+
```

> 💡 **왜 `unit_price`가 따로 있나?** `products.price`를 조인해서 쓰면 될 것 같지만 안 됩니다. **상품 가격은 바뀝니다.** 작년에 39,000원에 팔린 셔츠가 오늘 29,000원이라고 해서 작년 매출이 줄어들면 안 되죠. 그래서 주문 시점의 가격을 **스냅샷**으로 박아둡니다. 실무 주문 테이블은 상품명까지 복사해 두는 경우가 많습니다. 이것은 "중복"이 아니라 **의도된 반정규화**입니다(Step 13).

---

## 3-4. 처음 보는 DB의 구조를 파악하는 법

실무에서 남이 만든 DB를 인계받았을 때 쓰는 순서입니다.

### ① 테이블 목록과 크기

```sql
SELECT table_name,
       table_rows AS approx_rows,
       ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb,
       table_comment
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_type = 'BASE TABLE'
ORDER BY (data_length + index_length) DESC;
```

큰 테이블부터 보세요. **데이터가 몰려 있는 곳이 그 서비스의 핵심**입니다.

### ② 외래키 관계 — ER 다이어그램을 SQL로 뽑기

```sql
SELECT
  table_name           AS `자식 테이블`,
  column_name          AS `자식 컬럼`,
  referenced_table_name  AS `부모 테이블`,
  referenced_column_name AS `부모 컬럼`
FROM information_schema.key_column_usage
WHERE table_schema = 'shop'
  AND referenced_table_name IS NOT NULL
ORDER BY table_name;
```

**결과**
```
+-------------+-------------+------------+-------------+
| 자식 테이블 | 자식 컬럼   | 부모 테이블| 부모 컬럼   |
+-------------+-------------+------------+-------------+
| categories  | parent_id   | categories | category_id |   ← 자기참조
| employees   | manager_id  | employees  | employee_id |   ← 자기참조
| order_items | order_id    | orders     | order_id    |
| order_items | product_id  | products   | product_id  |
| orders      | customer_id | customers  | customer_id |
| payments    | order_id    | orders     | order_id    |
| products    | category_id | categories | category_id |
| reviews     | customer_id | customers  | customer_id |
| reviews     | product_id  | products   | product_id  |
+-------------+-------------+------------+-------------+
```

**이 쿼리 하나로 ER 다이어그램이 나옵니다.** 처음 보는 DB에서 가장 먼저 실행하세요.

> ⚠️ **함정**: 외래키를 **선언하지 않은** 프로젝트가 실무에 흔합니다(성능 우려나 ORM 관행 때문). 그러면 이 쿼리는 아무것도 반환하지 않고, 관계는 컬럼 이름(`xxx_id`)으로 추측하는 수밖에 없습니다. 그게 얼마나 괴로운지 겪어보면 FK를 선언하게 됩니다.

### ③ 각 테이블이 실제로 어떤 값을 담고 있나

```sql
-- 주문 상태 분포
SELECT status, COUNT(*) AS cnt,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM orders GROUP BY status ORDER BY cnt DESC;
```

**결과**
```
+-----------+-----+------+
| status    | cnt | pct  |
+-----------+-----+------+
| DELIVERED | 240 | 40.0 |
| SHIPPED   | 120 | 20.0 |
| PAID      | 120 | 20.0 |
| CANCELLED |  60 | 10.0 |
| PENDING   |  60 | 10.0 |
+-----------+-----+------+
```

(`OVER ()`는 윈도우 함수입니다. Step 17에서 배웁니다. 지금은 "전체 합계로 나눠 비율을 냈다"고만 이해하세요.)

```sql
-- 데이터가 언제부터 언제까지 있나
SELECT MIN(order_date) AS first_order, MAX(order_date) AS last_order FROM orders;
```

**결과**
```
+---------------------+---------------------+
| first_order         | last_order          |
+---------------------+---------------------+
| 2024-01-02 21:51:00 | 2025-12-30 13:19:00 |
+---------------------+---------------------+
```

> 💡 **실무 팁**: 새 DB를 받으면 **주요 테이블의 시간 범위**를 꼭 확인하세요. "데이터가 3개월 전에 멈춰 있다"는 사실을 리포트 다 만들고 나서 발견하는 일이 정말 많습니다.

---

## 3-5. 데이터 정합성 검증

시드가 제대로 들어갔는지 확인하는 쿼리들입니다. 동시에 **좋은 검증 쿼리가 어떤 모양인지** 배우는 예제이기도 합니다.

```sql
-- 1) orders.total_amount 가 order_items 합계와 일치하는가?
SELECT COUNT(*) AS mismatched_orders
FROM orders o
WHERE o.total_amount <> (
    SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0)
    FROM order_items oi WHERE oi.order_id = o.order_id
);
```

**결과**
```
+-------------------+
| mismatched_orders |
+-------------------+
|                 0 |
+-------------------+
```

0이어야 합니다. 0이 아니면 데이터가 깨진 것입니다.

```sql
-- 2) 결제가 없는 주문은 정말 PENDING 뿐인가?
SELECT o.status, COUNT(*) AS orders_without_payment
FROM orders o
LEFT JOIN payments p ON p.order_id = o.order_id
WHERE p.payment_id IS NULL
GROUP BY o.status;
```

**결과**
```
+---------+------------------------+
| status  | orders_without_payment  |
+---------+------------------------+
| PENDING |                     60 |
+---------+------------------------+
```

의도한 대로 `PENDING` 60건만 결제가 없습니다. 이 `LEFT JOIN ... IS NULL` 패턴이 **안티 조인**이고, Step 07의 핵심입니다.

---

## 3-6. 되돌리기 / 정리

| 상황 | 명령 |
|---|---|
| 데이터만 초기화 | `cd mysql8/sql && ./install.sh` |
| 대용량 테이블까지 | `./install.sh --big` |
| 컨테이너째 초기화 | `cd mysql8/docker && docker compose down -v && docker compose up -d` 후 위 실행 |
| 내가 만든 실습 테이블만 삭제 | 아래 쿼리로 목록을 뽑아 DROP |

```sql
-- 각 스텝의 실습 테이블은 s02_, s11_ 처럼 접두사가 붙습니다. 찾아서 지우기:
SELECT CONCAT('DROP TABLE IF EXISTS ', table_name, ';') AS cleanup_sql
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_name REGEXP '^s[0-9]{2}_';
```

이렇게 **SQL로 SQL을 생성**하는 기법도 실무에서 자주 씁니다. 결과를 복사해서 실행하면 됩니다.

---

## 정리

| 하고 싶은 것 | 방법 |
|---|---|
| 예제 DB 설치/초기화 | `./install.sh` |
| 테이블 목록과 크기 | `information_schema.tables` |
| ER 관계 파악 | `information_schema.key_column_usage` (FK 조회) |
| 컬럼 상세 | `information_schema.columns` / `DESC t` |
| 데이터 정합성 검증 | 상관 서브쿼리 비교, `LEFT JOIN ... IS NULL` |
| 실습 흔적 청소 | `s??_` 접두사 테이블 DROP |

---

## 연습문제

문제는 `exercise.sql`, 정답은 `solution.sql` 입니다. 두 파일의 전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다.

1. `shop`의 모든 테이블과 행 수를 **정확하게**(추정값 말고) 한 번에 출력하시오.
2. 인덱스가 하나도 없는(PK 제외) 테이블을 찾으시오.
3. 카테고리 계층을 "대분류 > 소분류" 형태로 출력하시오. (힌트: SELF JOIN)
4. 주문이 한 건도 없는 고객이 있는가? 있다면 누구인가?
5. 후기(reviews)가 하나도 없는 상품은 몇 개인가?
6. `products.attrs`가 NULL인 상품을 찾으시오.
7. 각 테이블의 PK 컬럼명을 information_schema로 조회하시오.

---

## 다음 단계

여기까지가 준비운동입니다. 이제 진짜 SQL을 씁니다.

→ [Step 04 — SELECT 기본](../step-04-select-basics/index.md)

---

## 실습 파일

이 스텝에는 SQL 파일 3개가 있습니다. `./install.sh` 로 `shop` DB를 설치한 뒤, 먼저 `practice.sql` 을 통째로 실행해 3-3 ~ 3-6 절의 쿼리를 눈으로 확인하고, 그다음 `exercise.sql` 의 빈칸을 직접 채워 풀어본 뒤, 마지막에 `solution.sql` 로 답과 해설을 맞춰보는 순서입니다. 세 파일 모두 **읽기 전용 `SELECT` 만** 담고 있어서 몇 번을 돌려도 데이터가 망가지지 않습니다.

### practice.sql

강의 본문 3-3절부터 3-6절까지의 쿼리를 순서대로 모아 둔 **따라치기용 스크립트**입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql` 처럼 리다이렉션으로 한 번에 흘려보내도 되고, 클라이언트에 붙여 한 줄씩 실행해도 됩니다.

- 맨 앞에서 `USE shop;` 을 하므로, 다른 DB에 접속해 있어도 자동으로 `shop` 으로 전환됩니다.
- 앞부분의 `SELECT * FROM orders ... LIMIT 5` / `order_items ... LIMIT 5` 는 3-3절의 "주문 1건이 상세 여러 줄로 갈라진다"를 눈으로 보여주는 쌍입니다. 이어지는 스칼라 서브쿼리 8개(`(SELECT COUNT(*) FROM categories) AS categories, ...`)는 전체 행 수를 **한 행으로** 뽑아 3-2절의 표와 대조하기 위한 것입니다.
- `information_schema.key_column_usage` 에서 `referenced_table_name IS NOT NULL` 로 거르는 쿼리(②)가 이 파일의 핵심입니다. 이 조건이 곧 "외래키인 행만 남겨라"는 뜻이고, 결과가 그대로 ER 다이어그램이 됩니다.
- 뒤쪽 정합성 검증 블록에서 `LEFT JOIN payments ... WHERE p.payment_id IS NULL` (안티 조인)과 `NOT EXISTS` 두 가지 표현이 나란히 등장합니다. Step 07/08의 예고편이니 지금은 "같은 질문을 두 방식으로 물었다"는 것만 기억하면 됩니다.
- 마지막 `REGEXP '^s[0-9]{2}_'` 쿼리는 **DROP 문을 실행하지 않고 문자열로 출력만** 합니다. 실제로 지우려면 출력된 결과를 복사해 따로 실행해야 합니다 — 안전장치입니다.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 8개가 주석으로만 들어 있는 **빈 답안지**입니다. 각 문제 아래 빈 줄에 직접 쿼리를 써 넣고 실행해 보세요. 본문 "연습문제" 절의 7문항에 심화 문제(문제 8)가 하나 더 붙어 있습니다.

- 문제 1은 "`information_schema.table_rows` 는 추정값"이라는 함정을 노린 것입니다. 주석의 힌트대로 `UNION ALL` 로 각 테이블의 `COUNT(*)` 를 이어 붙여야 정확한 값이 나옵니다.
- 문제 2의 힌트 `index_name <> 'PRIMARY'` 는 `information_schema.statistics` 에서 PK를 제외하고 세라는 뜻입니다. 정답은 `access_logs` 와 `tally` **두 개**입니다 — 하나만 찾고 끝내지 마세요. 이 중 `access_logs` 에 보조 인덱스가 없다는 사실이 Step 15(인덱스 실습)의 출발점이 됩니다. (`tally` 는 `n` 하나로만 조회하므로 PK만으로 충분해서 인덱스가 없는 것이고, `access_logs` 는 **일부러** 비워 둔 것입니다.)
- 문제 4는 **"결과가 비어 있다면 그것도 답"** 이라고 못박아 둔 것이 포인트입니다. 3-2절에서 설명한 "모든 고객이 최소 1건 주문"이라는 설계를 스스로 확인하는 문제입니다.
- 문제 7의 힌트 `GROUP_CONCAT` 은 복합 PK를 한 줄로 합치기 위한 것입니다.
- 답을 보기 전에 최소 한 번씩은 직접 써 보세요. 특히 문제 4·6은 "쿼리 버그"와 "정말 그런 데이터"를 구분하는 훈련입니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 8문항의 정답 쿼리와 **주석 해설**입니다. 쿼리만 있는 게 아니라 각 문제마다 결과값과 "왜 그런가"가 함께 적혀 있으니, 답을 맞힌 뒤에도 해설 주석까지 읽는 것을 권합니다.

- 문제 3의 `JOIN categories p ON p.category_id = c.parent_id` 가 SELF JOIN의 전형입니다. 같은 테이블에 `c`(자식)와 `p`(부모) 별칭을 각각 붙였고, 계층이 2단계뿐이라 JOIN 한 번으로 끝났다는 점, 그리고 깊이를 모르는 계층에서는 재귀 CTE(Step 09)가 필요하다는 점을 짚어줍니다.
- 문제 4의 해설에 있는 "`COUNT(DISTINCT c.customer_id)` 대신 `COUNT(*)` 를 쓰면 600이 나온다"는 대목이 이 파일에서 가장 값진 부분입니다. **조인은 행을 불린다** — 초보자가 가장 자주 저지르는 집계 실수를 미리 못박아 둡니다.
- 문제 5는 `NOT EXISTS` / `LEFT JOIN ... IS NULL` / `NOT IN` 세 가지를 비교하며, `NOT IN` 은 서브쿼리에 NULL이 하나만 섞여도 **결과가 통째로 비어버린다**고 경고합니다(Step 08의 핵심 함정).
- 문제 6의 해설은 `attrs IS NULL`(컬럼 자체가 빔)과 `JSON_TYPE(attrs) = 'NULL'`(JSON 문서 안에 `null` 이 들어 있음)이 **다른 것**임을 구분합니다. 이걸 헷갈리면 JSON 쿼리가 조용히 틀립니다(Step 18).
- 문제 8은 `WHERE o.order_id = (SELECT order_id FROM orders ORDER BY total_amount DESC LIMIT 1)` 로 "최댓값 행"을 먼저 찾는 패턴입니다. `MAX()` 는 값만 알려줄 뿐 어느 행인지는 못 알려준다는 차이, 그리고 동점 처리는 `RANK()`(Step 17)가 필요하다는 점을 함께 설명합니다.

```sql file="./solution.sql"
```
