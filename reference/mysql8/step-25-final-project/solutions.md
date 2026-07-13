# Step 25 — 최종 프로젝트 : 정답과 해설

모든 쿼리는 라이브 서버에서 실행해 검증했습니다(결과는 재현 가능한 시드 기준).

---

## Part 1 — 비즈니스 리포트 12문제

### Q1. 월별 매출 리포트
```sql
SELECT DATE_FORMAT(o.order_date, '%Y-%m')           AS ym,
       COUNT(DISTINCT o.order_id)                    AS orders,
       FORMAT(SUM(oi.quantity * oi.unit_price), 0)   AS revenue
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.status <> 'CANCELLED'
GROUP BY ym
ORDER BY ym;
```
**결과(일부)**
```
+---------+--------+------------+
| ym      | orders | revenue    |
+---------+--------+------------+
| 2024-01 |     22 | 23,577,600 |
| 2024-02 |     22 | 37,569,800 |
| 2024-03 |     23 | 39,916,500 |
| 2024-04 |     22 | 38,226,300 |
| 2024-05 |     26 | 38,634,300 |
| 2024-06 |     25 | 42,717,900 |
+---------+--------+------------+
```
> 함정: `COUNT(*)` 이 아니라 `COUNT(DISTINCT o.order_id)`. `order_items` 와 조인하면 주문이 상품 수만큼 뻥튀기됩니다.

### Q2. 카테고리별 매출 순위
```sql
SELECT c.name AS category,
       FORMAT(SUM(oi.quantity * oi.unit_price), 0) AS rev,
       RANK() OVER (ORDER BY SUM(oi.quantity * oi.unit_price) DESC) AS rnk
FROM order_items oi
JOIN products   p ON p.product_id  = oi.product_id
JOIN categories c ON c.category_id = p.category_id
JOIN orders     o ON o.order_id    = oi.order_id AND o.status <> 'CANCELLED'
GROUP BY c.category_id, c.name
ORDER BY rnk;
```
**결과(일부)**
```
| category   | rev         | rnk |
| 노트북     | 327,900,000 |   1 |
| 스마트폰   | 182,340,000 |   2 |
| 주변기기   | 58,545,000  |   3 |
```

### Q3. RFM 세그먼트
```sql
WITH base AS (
  SELECT c.customer_id, c.name,
         DATEDIFF('2025-12-31', MAX(o.order_date)) AS recency_days,
         COUNT(DISTINCT o.order_id)                AS frequency,
         SUM(oi.quantity * oi.unit_price)          AS monetary
  FROM customers c
  JOIN orders o      ON o.customer_id = c.customer_id AND o.status <> 'CANCELLED'
  JOIN order_items oi ON oi.order_id  = o.order_id
  GROUP BY c.customer_id, c.name
),
scored AS (
  SELECT *,
         NTILE(5) OVER (ORDER BY recency_days DESC) AS r,   -- 경과일 작을수록 高점
         NTILE(5) OVER (ORDER BY frequency)         AS f,
         NTILE(5) OVER (ORDER BY monetary)          AS m
  FROM base
)
SELECT customer_id, name, recency_days, frequency, FORMAT(monetary,0) AS monetary,
       CONCAT(r,f,m) AS rfm,
       CASE WHEN r>=4 AND f>=4 THEN 'Champions'
            WHEN r>=4          THEN 'Recent'
            WHEN f>=4          THEN 'Loyal'
            WHEN r<=2 AND f<=2 THEN 'At Risk'
            ELSE 'Others' END AS segment
FROM scored
ORDER BY monetary DESC;
```
**결과(일부)**
```
| customer_id | name   | recency_days | frequency | monetary   | rfm  | segment |
|           8 | 임수진 |           53 |        20 | 58,449,000 | 155  | Loyal   |
|           5 | 정  훈 |            6 |        20 | 51,568,000 | 515  | Recent  |
|          30 | 하준서 |            1 |        20 | 41,989,500 | 514  | Recent  |
```
> 핵심: R 은 **경과일이 작을수록** 좋은 고객이므로 `ORDER BY recency_days DESC` 로 NTILE 을 줘야 5점이 최근 고객에게 갑니다.

### Q4. 카테고리별 전년 대비 성장률
```sql
WITH cat_year AS (
  SELECT p.category_id, YEAR(o.order_date) AS yr,
         SUM(oi.quantity * oi.unit_price)  AS rev
  FROM orders o
  JOIN order_items oi ON oi.order_id  = o.order_id
  JOIN products    p  ON p.product_id = oi.product_id
  WHERE o.status <> 'CANCELLED'
  GROUP BY p.category_id, yr
)
SELECT c.name AS category, cy.yr, FORMAT(cy.rev,0) AS rev,
       FORMAT(LAG(cy.rev) OVER w, 0) AS prev,
       CONCAT(ROUND(100*(cy.rev - LAG(cy.rev) OVER w) / LAG(cy.rev) OVER w, 1), '%') AS growth
FROM cat_year cy
JOIN categories c ON c.category_id = cy.category_id
WINDOW w AS (PARTITION BY cy.category_id ORDER BY cy.yr)
ORDER BY cy.category_id, cy.yr;
```
**결과(일부)**
```
| category | yr   | rev         | prev        | growth |
| 신발     | 2024 | 2,975,000   | NULL        | NULL   |
| 신발     | 2025 | 19,375,000  | 2,975,000   | 551.3% |
| 스마트폰 | 2025 | 144,426,000 | 37,914,000  | 280.9% |
```
> `WINDOW` 절로 같은 윈도우 정의를 재사용하면 `LAG(...) OVER (PARTITION BY ...)` 를 세 번 안 써도 됩니다.

### Q5. 재구매율
```sql
WITH oc AS (
  SELECT customer_id, COUNT(DISTINCT order_id) AS cnt
  FROM orders WHERE status <> 'CANCELLED'
  GROUP BY customer_id
)
SELECT COUNT(*)                              AS total_buyers,
       SUM(cnt >= 2)                         AS repeat_buyers,
       CONCAT(ROUND(100*SUM(cnt>=2)/COUNT(*),1), '%') AS repeat_rate
FROM oc;
```
**결과**
```
| total_buyers | repeat_buyers | repeat_rate |
|           27 |            27 | 100.0%      |
```
> 이 데이터는 고객 대부분이 다회 구매라 100%가 나옵니다. `SUM(불린식)` 은 참=1 합계로 카운트하는 관용구입니다.

### Q6. 이탈(휴면) 고객
```sql
SELECT c.customer_id, c.name, c.grade,
       MAX(o.order_date)                          AS last_order,
       DATEDIFF('2025-12-31', MAX(o.order_date))  AS days_since
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.status <> 'CANCELLED'
GROUP BY c.customer_id, c.name, c.grade
HAVING days_since > 180 OR last_order IS NULL
ORDER BY days_since DESC;
```
**결과**
```
| customer_id | name   | grade  | last_order | days_since |
|           6 | 강소라 | VIP    | NULL       |       NULL |
|          16 | 문재현 | SILVER | NULL       |       NULL |
|          26 | 배채영 | GOLD   | NULL       |       NULL |
```
> `LEFT JOIN` 이 핵심: 주문이 아예 없는 고객(`NULL`)을 놓치지 않습니다.

### Q7. 장바구니 상품 조합
```sql
SELECT p1.name AS prod_a, p2.name AS prod_b, COUNT(*) AS together
FROM order_items a
JOIN order_items b ON a.order_id = b.order_id AND a.product_id < b.product_id
JOIN products p1 ON p1.product_id = a.product_id
JOIN products p2 ON p2.product_id = b.product_id
GROUP BY a.product_id, b.product_id
HAVING together >= 2
ORDER BY together DESC
LIMIT 10;
```
**결과(일부)**
```
| prod_a           | prod_b          | together |
| 보급형 노트북 15 | 콜드브루 원액 1L|       15 |
| 니트 가디건      | 27인치 4K 모니터|       15 |
```
> `a.product_id < b.product_id` 가 두 가지를 동시에 해결: **자기 자신과의 쌍 제거**, **(A,B)와 (B,A) 중복 제거**.

### Q8. 코호트 리텐션
```sql
WITH first_order AS (
  SELECT customer_id, DATE_FORMAT(MIN(order_date), '%Y-%m') AS cohort
  FROM orders WHERE status <> 'CANCELLED' GROUP BY customer_id
),
activity AS (
  SELECT DISTINCT o.customer_id, fo.cohort,
         TIMESTAMPDIFF(MONTH,
           STR_TO_DATE(CONCAT(fo.cohort,'-01'), '%Y-%m-%d'), o.order_date) AS month_no
  FROM orders o
  JOIN first_order fo ON fo.customer_id = o.customer_id
  WHERE o.status <> 'CANCELLED'
)
SELECT cohort,
       COUNT(DISTINCT CASE WHEN month_no=0 THEN customer_id END) AS m0,
       COUNT(DISTINCT CASE WHEN month_no=1 THEN customer_id END) AS m1,
       COUNT(DISTINCT CASE WHEN month_no=2 THEN customer_id END) AS m2,
       COUNT(DISTINCT CASE WHEN month_no=3 THEN customer_id END) AS m3
FROM activity GROUP BY cohort ORDER BY cohort;
```
**결과(일부)**
```
| cohort  | m0 | m1 | m2 | m3 |
| 2024-01 | 22 | 19 | 18 | 17 |
| 2024-02 |  3 |  3 |  3 |  3 |
```
> m0 대비 m1/m2/m3 의 감소가 리텐션 곡선입니다. 2024-01 코호트는 22 → 17 로 4개월 뒤 77% 유지.

### Q9. 등급별 평균 주문금액(AOV)
```sql
SELECT c.grade,
       COUNT(DISTINCT o.order_id)                                   AS orders,
       FORMAT(SUM(oi.quantity*oi.unit_price), 0)                    AS revenue,
       FORMAT(SUM(oi.quantity*oi.unit_price)/COUNT(DISTINCT o.order_id), 0) AS aov
FROM customers c
JOIN orders o       ON o.customer_id = c.customer_id AND o.status <> 'CANCELLED'
JOIN order_items oi ON oi.order_id   = o.order_id
GROUP BY c.grade
ORDER BY FIELD(c.grade, 'VIP','GOLD','SILVER','BRONZE');
```
**결과**
```
| grade  | orders | revenue     | aov       |
| VIP    |     80 | 61,997,000  | 774,963   |
| GOLD   |    140 | 187,564,500 | 1,339,746 |
| SILVER |    120 | 166,427,500 | 1,386,896 |
| BRONZE |    200 | 300,867,000 | 1,504,335 |
```

### Q10. 결제수단 분포
```sql
SELECT method, COUNT(*) AS cnt, FORMAT(SUM(amount),0) AS total,
       CONCAT(ROUND(100*COUNT(*)/SUM(COUNT(*)) OVER (), 1), '%') AS pct
FROM payments
WHERE status = 'DONE'
GROUP BY method
ORDER BY cnt DESC;
```
**결과**
```
| method | cnt | total       | pct   |
| POINT  | 150 | 202,093,500 | 31.3% |
| CARD   | 150 | 182,208,000 | 31.3% |
| BANK   |  90 | 128,038,500 | 18.8% |
| MOBILE |  90 | 109,404,000 | 18.8% |
```
> `SUM(COUNT(*)) OVER ()` — 집계 위에 윈도우를 얹어 **전체 합**을 각 행에 붙이는 관용구.

### Q11. 리뷰 평점과 판매량
```sql
SELECT p.name,
       ROUND(AVG(r.rating), 2)             AS avg_rating,
       COUNT(DISTINCT r.review_id)         AS reviews,
       COALESCE(SUM(oi.quantity), 0)       AS units_sold
FROM products p
LEFT JOIN reviews     r  ON r.product_id  = p.product_id
LEFT JOIN order_items oi ON oi.product_id = p.product_id
GROUP BY p.product_id, p.name
HAVING reviews > 0
ORDER BY avg_rating DESC, units_sold DESC;
```
> 주의: `reviews`, `order_items` 를 동시에 조인하면 카티전 곱으로 `units_sold` 가 부풀 수 있습니다. 정밀 리포트라면 각각 서브쿼리로 집계 후 조인하세요(이 문제는 관찰용이라 허용).

### Q12. 월별 누적 매출
```sql
WITH m AS (
  SELECT DATE_FORMAT(o.order_date, '%Y-%m') AS ym,
         SUM(oi.quantity*oi.unit_price)     AS rev
  FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
  WHERE o.status <> 'CANCELLED' GROUP BY ym
)
SELECT ym, FORMAT(rev,0) AS monthly,
       FORMAT(SUM(rev) OVER (ORDER BY ym), 0) AS cumulative
FROM m ORDER BY ym;
```
**결과(일부)**
```
| ym      | monthly    | cumulative  |
| 2024-01 | 23,577,600 | 23,577,600  |
| 2024-02 | 37,569,800 | 61,147,400  |
| 2024-03 | 39,916,500 | 101,063,900 |
```

---

## Part 2 — 느린 쿼리 튜닝

### T1. 함수로 감싼 날짜 → sargable 범위 조건

**느린 버전** (`EXPLAIN ANALYZE` 실측)
```
-> Aggregate: count(0)  (actual time=128..128 rows=1 loops=1)
    -> Filter: (cast(s25_logs.logged_at as date) = '2024-06-15')  (actual time=64..128 rows=2979)
        -> Table scan on s25_logs  (cost=100633 rows=996151) (actual time=0.075..90.3 rows=1e+6)   ← 100만 행 풀스캔
```
`DATE(logged_at)` 로 컬럼을 감싸면 인덱스를 만들어도 못 씁니다(컬럼 값이 아니라 함수 결과로 비교).

**튜닝**: 인덱스 추가 + **컬럼을 건드리지 않는 반열린 범위**로 재작성
```sql
ALTER TABLE s25_logs ADD INDEX idx_logged_at (logged_at);

SELECT COUNT(*) FROM s25_logs
WHERE logged_at >= '2024-06-15' AND logged_at < '2024-06-16';
```
```
-> Aggregate: count(0)  (actual time=0.517..0.517 rows=1 loops=1)
    -> Covering index range scan on s25_logs using idx_logged_at
       over ('2024-06-15' <= logged_at < '2024-06-16')  (actual time=0.039..0.262 rows=2979)
```
**128ms → 0.5ms.** 결과 동일(둘 다 2,979행).
> 규칙: **WHERE 절의 컬럼에는 함수를 씌우지 마라.** 이걸 "sargable(Search ARGument able)" 이라고 합니다.

### T2. 앞쪽 와일드카드 LIKE

**느린 버전**
```
-> Filter: (path like '%detail%')  (actual time=0.043..153 rows=250000)
    -> Table scan on s25_logs (rows=1e+6)   ← 풀스캔
```
`LIKE '%detail%'` 는 **앞에 `%`가 있어** B-트리 인덱스를 쓸 수 없습니다(인덱스는 접두사로 정렬돼 있음).

**핵심 통찰**: 여기서는 인덱스를 걸어도 크게 안 빨라집니다. `detail` 이 포함된 행이 **전체의 25%(25만 행)** 라 선택도가 낮기 때문입니다. 인덱스는 **"소수만 고를 때"** 이득입니다.

**현실적 대안 3가지**
1. **의도가 정확 매치라면** — 앱이 원한 건 `/products/detail`, `/orders/detail` 두 엔드포인트였을 것. 그러면:
   ```sql
   ALTER TABLE s25_logs ADD INDEX idx_path (path);
   SELECT COUNT(*) FROM s25_logs WHERE path IN ('/products/detail','/orders/detail');
   ```
   `Covering index range scan` 으로 바뀝니다(단, 여전히 25%라 극적이진 않음 — 데이터 특성의 한계).
2. **부분 문자열 검색이 정말 필요하면** — 전문검색(FULLTEXT) 인덱스나 별도 검색엔진.
3. **접두 검색이면** — `LIKE 'detail%'`(앞 `%` 없음)로 바꾸면 인덱스 사용 가능.

> 교훈: **"인덱스를 걸면 무조건 빨라진다"는 착각.** 선택도가 낮으면(많이 매치되면) 옵티마이저는 인덱스를 무시하고 풀스캔을 고릅니다. 그게 실제로 더 빠르니까요.

### T3. 상관 서브쿼리 → JOIN + GROUP BY

**느린 버전**
```
-> Select #2 (subquery in projection; dependent)     ← 고객 30명마다 서브쿼리 반복
    -> Aggregate: count(0)  (actual time=0.013..0.013 rows=1 loops=30)
```
`SELECT (서브쿼리)` 가 **바깥 행마다 반복 실행**됩니다(`loops=30`). 데이터가 커지면 치명적입니다.

**튜닝**: 한 번의 조인 + 그룹핑으로
```sql
SELECT c.name, COUNT(o.order_id) AS order_count
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.status <> 'CANCELLED'
GROUP BY c.customer_id, c.name
ORDER BY order_count DESC
LIMIT 5;
```
```
-> Aggregate using temporary table  (actual time=0.496..0.496 rows=30)
    -> Nested loop left join  (cost=153 rows=600) (actual time=0.038..0.345 rows=543)
```
서브쿼리 반복(`loops=30`)이 사라지고 **한 번의 조인**으로 끝납니다. `LEFT JOIN` 이라 주문 0건 고객도 `order_count=0` 으로 보존됩니다.
> 조건 `status <> 'CANCELLED'` 를 `WHERE` 가 아니라 **`ON` 절**에 둔 이유: `WHERE` 에 두면 주문 없는 고객(NULL)이 탈락해 LEFT JOIN 이 무의미해집니다.

---

## Part 3 — "쿠폰" 도메인 스키마 설계 (모범답안)

라이브 서버에서 생성·제약 위반까지 검증한 스키마입니다.

```sql
-- 1) 쿠폰 마스터
CREATE TABLE s25_coupon (
  coupon_id        INT UNSIGNED NOT NULL AUTO_INCREMENT,
  code             VARCHAR(30)  NOT NULL,
  name             VARCHAR(100) NOT NULL,
  discount_type    ENUM('FIXED','PERCENT') NOT NULL,
  discount_value   DECIMAL(10,2) NOT NULL COMMENT 'FIXED=원, PERCENT=%',
  max_discount     DECIMAL(10,2) NULL     COMMENT 'PERCENT 상한(원). FIXED 는 NULL',
  min_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  valid_from       DATETIME NOT NULL,
  valid_to         DATETIME NOT NULL,
  total_quota      INT UNSIGNED NULL      COMMENT '총 발급 한도. NULL=무제한',
  issued_count     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (coupon_id),
  UNIQUE KEY uk_coupon_code (code),
  CONSTRAINT chk_discount_value CHECK (discount_value > 0),
  CONSTRAINT chk_percent_range  CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
  CONSTRAINT chk_valid_period   CHECK (valid_to > valid_from),
  CONSTRAINT chk_quota          CHECK (total_quota IS NULL OR issued_count <= total_quota)
) ENGINE=InnoDB;

-- 2) 발급 내역 (쿠폰 : 고객 = N:M 의 연결)
CREATE TABLE s25_coupon_issue (
  issue_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  coupon_id    INT UNSIGNED NOT NULL,
  customer_id  INT UNSIGNED NOT NULL,
  issued_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status       ENUM('ISSUED','USED','EXPIRED') NOT NULL DEFAULT 'ISSUED',
  PRIMARY KEY (issue_id),
  UNIQUE KEY uk_coupon_customer (coupon_id, customer_id),   -- ★ 중복 발급 방지
  KEY idx_issue_customer (customer_id),
  CONSTRAINT fk_issue_coupon   FOREIGN KEY (coupon_id)   REFERENCES s25_coupon(coupon_id),
  CONSTRAINT fk_issue_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB;

-- 3) 사용 내역 (발급 1건이 주문 1건에 적용)
CREATE TABLE s25_coupon_redemption (
  redemption_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  issue_id        BIGINT UNSIGNED NOT NULL,
  order_id        BIGINT UNSIGNED NOT NULL,
  discount_amount DECIMAL(10,2) NOT NULL,
  redeemed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (redemption_id),
  UNIQUE KEY uk_issue_once (issue_id),    -- ★ 발급 1건당 사용 1회
  UNIQUE KEY uk_order_once (order_id),    -- 주문당 쿠폰 1개(정책에 따라)
  CONSTRAINT fk_redeem_issue FOREIGN KEY (issue_id) REFERENCES s25_coupon_issue(issue_id),
  CONSTRAINT fk_redeem_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
  CONSTRAINT chk_discount_amount CHECK (discount_amount >= 0)
) ENGINE=InnoDB;
```

### 제약이 실제로 막는지 검증 (라이브 실행)
```
-- 중복 발급
INSERT INTO s25_coupon_issue (coupon_id, customer_id) VALUES (1,1);   -- 두 번째
ERROR 1062 (23000): Duplicate entry '1-1' for key 's25_coupon_issue.uk_coupon_customer'

-- 정률 > 100%
INSERT INTO s25_coupon (... discount_type='PERCENT', discount_value=150 ...);
ERROR 3819 (HY000): Check constraint 'chk_percent_range' is violated.

-- 같은 발급을 두 번 사용
INSERT INTO s25_coupon_redemption (issue_id, order_id, ...) VALUES (1, 2, 5000);
ERROR 1062 (23000): Duplicate entry '1' for key 's25_coupon_redemption.uk_issue_once'
```

### 설계 근거

| 결정 | 근거 |
|---|---|
| **정액/정률을 한 테이블 + `discount_type` ENUM** | 두 타입의 공통 속성(유효기간/최소금액/한도)이 90% 동일. 테이블을 나누면 조회 시마다 UNION 필요. `CHECK` 로 타입별 규칙(정률 ≤ 100, 상한은 정률만)을 강제하면 한 테이블로 충분 |
| **금액은 `DECIMAL(10,2)`** | 돈은 절대 `FLOAT` 금지(반올림 오차). `DECIMAL` 은 정확한 십진 연산 |
| **`UNIQUE(coupon_id, customer_id)`** | 요구사항 4(중복 발급 금지)를 **DB 레벨에서** 보장. 앱 로직에만 맡기면 동시 요청에 뚫린다 |
| **`UNIQUE(issue_id)` on redemption** | 요구사항 3(발급당 1회 사용)을 유니크 제약으로 보장. "이미 썼는지" 조회 후 INSERT 하는 앱 로직은 경합에 취약 |
| **발급/사용을 별도 테이블로 분리** | 발급받았으나 미사용 상태를 표현해야 함(`status='ISSUED'`). 사용 시점/할인액/주문 연결은 별도 이벤트라 정규화 |
| **`issued_count` + `CHECK(issued_count <= total_quota)`** | 총 발급 한도. 단, `CHECK` 는 행 단위라 동시성까지는 못 막음 → 아래 참조 |

### 동시성/무결성 전략
- **중복 발급**: `UNIQUE(coupon_id, customer_id)` 가 최종 방어선. 두 요청이 동시에 와도 하나는 `ERROR 1062` 로 실패한다. **앱은 이 에러를 잡아 "이미 발급됨"으로 처리**한다.
- **재고(한도) 초과 발급**: `issued_count` 를 `UPDATE ... SET issued_count = issued_count + 1 WHERE coupon_id=? AND (total_quota IS NULL OR issued_count < total_quota)` 로 **원자적 조건부 증가**하고, `ROW_COUNT()=0` 이면 매진 처리. (또는 트랜잭션 + `SELECT ... FOR UPDATE` 로 행 잠금 → [Step 19](../step-19-transactions/index.md))
- **사용 1회**: `UNIQUE(issue_id)` on redemption. 조회-후-삽입이 아니라 **삽입을 시도하고 유니크 위반이면 실패**시키는 것이 경합에 안전하다.
- **트랜잭션 경계**: "쿠폰 사용 + 주문 금액 차감"은 하나의 트랜잭션으로 묶어 원자성을 보장한다.

---

## 채점 요약
- Part 1(12문제): 각 8~15점. 취소 제외/중복 제거/윈도우 정확성이 핵심 감점 포인트
- Part 2(3문제): 튜닝 전후 EXPLAIN 비교 + **결과 동일 증명**이 필수
- Part 3(설계): 제약을 DB 레벨로 강제했는가 + 동시성 고려가 핵심

---

## 뒷정리
```sql
DROP TABLE IF EXISTS s25_logs, s25_coupon_redemption, s25_coupon_issue, s25_coupon;
```
