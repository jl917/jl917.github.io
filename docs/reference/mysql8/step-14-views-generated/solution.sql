-- =====================================================================
-- Step 14 — 뷰와 생성 컬럼 : solution.sql  (정답 + 해설)
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [정답 1] 고객별 주문 통계 뷰
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS v14_ex_customer_stats;
CREATE VIEW v14_ex_customer_stats AS
SELECT c.customer_id, c.name, c.grade,
       COUNT(o.order_id)                AS order_cnt,       -- LEFT JOIN 이므로 NULL 은 안 셈
       COALESCE(SUM(o.total_amount), 0) AS total_spent,
       MAX(o.order_date)                AS last_order_date
FROM customers c
LEFT JOIN orders o                                          -- 주문 없는 고객도 살리려면 LEFT
       ON o.customer_id = c.customer_id
      AND o.status <> 'CANCELLED'                           -- 취소 제외는 "ON 절"에 둔다!
GROUP BY c.customer_id, c.name, c.grade;

SELECT * FROM v14_ex_customer_stats ORDER BY total_spent DESC LIMIT 5;

-- 해설
--   * CANCELLED 제외 조건을 WHERE 에 두면 안 됩니다. WHERE o.status<>'CANCELLED' 는
--     주문이 아예 없는 고객(o.status = NULL)까지 걸러내서 LEFT JOIN 이 INNER JOIN 처럼 됩니다.
--     "자식 테이블 조건은 ON 절, 부모 테이블 조건은 WHERE 절" 이 LEFT JOIN 의 철칙입니다(Step 07).
--   * COUNT(o.order_id) 는 NULL 을 세지 않으므로 주문 없는 고객은 0 이 됩니다.
--     COUNT(*) 로 쓰면 LEFT JOIN 이 만든 NULL 행까지 1 로 세어 틀립니다.


-- ---------------------------------------------------------------------
-- [정답 2] 이 뷰들은 업데이트 가능한가?
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s14_ex_prod;
CREATE TABLE s14_ex_prod AS SELECT product_id, name, price, cost, stock, status FROM products;
ALTER TABLE s14_ex_prod ADD PRIMARY KEY (product_id);

CREATE OR REPLACE VIEW v14_ex_a AS
  SELECT product_id, name, price FROM s14_ex_prod WHERE status = 'ON_SALE';
CREATE OR REPLACE VIEW v14_ex_b AS
  SELECT status, COUNT(*) AS cnt FROM s14_ex_prod GROUP BY status;
CREATE OR REPLACE VIEW v14_ex_c AS
  SELECT DISTINCT status FROM s14_ex_prod;
CREATE OR REPLACE VIEW v14_ex_d AS
  SELECT product_id, name, price, price * 1.1 AS price_with_vat FROM s14_ex_prod;

-- (2-1) 예측:
--   a = YES  : 단순 WHERE 필터. 원본 행과 1:1 대응 → 업데이트 가능
--   b = NO   : GROUP BY + COUNT 집계 → 어느 원본 행을 바꿀지 특정 불가
--   c = NO   : DISTINCT → 여러 원본 행이 한 결과 행으로 접힘 → 특정 불가
--   d = YES  : 파생 컬럼(price_with_vat)이 있지만 원본 컬럼도 있어 행 대응이 유지됨 → 뷰 자체는 업데이트 가능

-- (2-2) 확인
SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME IN ('v14_ex_a','v14_ex_b','v14_ex_c','v14_ex_d')
ORDER BY TABLE_NAME;
-- +------------+--------------+
-- | v14_ex_a   | YES          |
-- | v14_ex_b   | NO           |
-- | v14_ex_c   | NO           |
-- | v14_ex_d   | YES          |
-- +------------+--------------+

-- (2-3) v14_ex_d 는 업데이트 가능하지만, "파생 컬럼 자체"는 UPDATE 할 수 없다:
UPDATE v14_ex_d SET price_with_vat = 999 WHERE product_id = 1;
--   → ERROR 1348 (HY000): Column 'price_with_vat' is not updatable
--
-- 해설: 뷰가 업데이트 가능하다는 것과, 그 뷰의 "모든 컬럼"이 업데이트 가능하다는 것은 다릅니다.
--   price 처럼 원본 컬럼을 그대로 노출한 컬럼은 UPDATE 되지만,
--   price*1.1 같은 계산 컬럼은 "역으로 원본에 무엇을 써야 하는지" 알 수 없어 UPDATE 불가입니다.
UPDATE v14_ex_d SET price = 50000 WHERE product_id = 1;    -- 이건 성공 (원본 컬럼이라서)


-- ---------------------------------------------------------------------
-- [정답 3] WITH CHECK OPTION 으로 재고 통제
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v14_ex_instock AS
SELECT product_id, name, stock, status
FROM s14_ex_prod
WHERE stock > 0
WITH CHECK OPTION;

-- 재고가 있는 상품 하나를 고른다
SELECT product_id, stock FROM s14_ex_prod WHERE stock > 0 ORDER BY product_id LIMIT 1;

-- (a) stock 을 1 로 → 여전히 stock > 0 이므로 성공
UPDATE v14_ex_instock SET stock = 1 WHERE product_id = 1;
SELECT product_id, stock FROM s14_ex_prod WHERE product_id = 1;

-- (b) stock 을 0 으로 → 뷰 조건(stock > 0) 위반 → 거부
UPDATE v14_ex_instock SET stock = 0 WHERE product_id = 1;
--   → ERROR 1369 (HY000): CHECK OPTION failed 'shop.v14_ex_instock'
--
-- 해설: CHECK OPTION 이 없었다면 stock=0 UPDATE 가 통과하면서 그 상품이 뷰에서 사라졌을 것입니다.
--   "재고 있는 상품 뷰"를 통해 재고를 0 으로 만드는 모순을 DB 가 막아줍니다.


-- ---------------------------------------------------------------------
-- [정답 4] LOCAL vs CASCADED 결과 예측
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s14_ex_emp;
CREATE TABLE s14_ex_emp (
  emp_id INT PRIMARY KEY, dept VARCHAR(20) NOT NULL, salary INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO s14_ex_emp VALUES (1,'개발',5000),(2,'개발',3000),(3,'영업',4000);

CREATE OR REPLACE VIEW v14_ex_dev AS
  SELECT * FROM s14_ex_emp WHERE dept = '개발';                    -- CHECK OPTION 없음

-- (4-1) LOCAL 버전
CREATE OR REPLACE VIEW v14_ex_dev_highpay AS
  SELECT * FROM v14_ex_dev WHERE salary >= 4000 WITH LOCAL CHECK OPTION;

UPDATE v14_ex_dev_highpay SET dept = '영업' WHERE emp_id = 1;
--   → 성공! emp_id=1 이 dept='영업' 이 된다.
--   해설: LOCAL 은 "이 뷰 자신의 조건(salary >= 4000)"만 검사합니다.
--         dept 를 바꿔도 salary 는 그대로 5000 이라 자기 조건은 만족합니다.
--         부모 뷰 v14_ex_dev 의 조건(dept='개발')은 LOCAL 이라 검사하지 않으므로 통과합니다.
--         (단, dept='영업'이 되었으니 이제 이 행은 부모 뷰에서 사라집니다.)
SELECT emp_id, dept, salary FROM s14_ex_emp WHERE emp_id = 1;      -- 영업, 5000
UPDATE s14_ex_emp SET dept = '개발' WHERE emp_id = 1;              -- 원복

-- (4-2) CASCADED 버전
CREATE OR REPLACE VIEW v14_ex_dev_highpay AS
  SELECT * FROM v14_ex_dev WHERE salary >= 4000 WITH CASCADED CHECK OPTION;

UPDATE v14_ex_dev_highpay SET dept = '영업' WHERE emp_id = 1;
--   → ERROR 1369 CHECK OPTION failed
--   해설: CASCADED 는 부모 뷰 v14_ex_dev 의 조건(dept='개발')까지 검사합니다.
--         dept 를 '영업'으로 바꾸면 부모 조건을 위반하므로 거부됩니다.
--
-- 결론: 같은 UPDATE 가 LOCAL 이면 성공, CASCADED 면 실패. 기본값은 CASCADED 입니다.


-- ---------------------------------------------------------------------
-- [정답 5] 생성 컬럼 + 인덱스로 이메일 도메인 검색
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s14_ex_cust;
CREATE TABLE s14_ex_cust AS SELECT customer_id, email, name FROM customers;
ALTER TABLE s14_ex_cust ADD PRIMARY KEY (customer_id);
UPDATE s14_ex_cust SET email = REPLACE(email, 'example.com', 'test.org') WHERE customer_id % 3 = 0;

-- Before: 함수를 씌운 조건은 인덱스를 못 탄다 → type: ALL
EXPLAIN SELECT * FROM s14_ex_cust WHERE SUBSTRING_INDEX(email, '@', -1) = 'example.com';

-- 생성 컬럼 추가 + 인덱스
ALTER TABLE s14_ex_cust
  ADD COLUMN email_domain VARCHAR(120) AS (SUBSTRING_INDEX(email, '@', -1)) VIRTUAL;
ALTER TABLE s14_ex_cust ADD INDEX idx_domain (email_domain);
ANALYZE TABLE s14_ex_cust;

-- After: 생성 컬럼으로 조회 → type: ref
EXPLAIN SELECT * FROM s14_ex_cust WHERE email_domain = 'example.com';

-- 보너스: 원래의 함수 표현식으로 그대로 써도 옵티마이저가 생성 컬럼 인덱스로 자동 매핑한다!
--         (표현식이 생성 컬럼 정의와 글자까지 일치하면 됨)
EXPLAIN SELECT * FROM s14_ex_cust WHERE SUBSTRING_INDEX(email, '@', -1) = 'example.com';
-- → 둘 다 type: ref, key: idx_domain 으로 나옵니다.
--
-- 해설: 애플리케이션 코드를 email_domain 으로 고치지 않아도 됩니다.
--   원래 쓰던 SUBSTRING_INDEX(...) 조건 그대로 두면, MySQL 이 "아, 이건 생성 컬럼과 같네" 하고
--   인덱스를 씁니다. 무중단으로 인덱스를 도입하는 좋은 방법입니다.


-- ---------------------------------------------------------------------
-- [정답 6] 함수 기반 인덱스가 안 먹는 쿼리 진단
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s14_ex_log;
CREATE TABLE s14_ex_log (
  id INT AUTO_INCREMENT PRIMARY KEY,
  logged_at DATETIME NOT NULL,
  amount INT NOT NULL,
  INDEX idx_ym ((YEAR(logged_at)))
) ENGINE=InnoDB;
INSERT INTO s14_ex_log (logged_at, amount)
SELECT TIMESTAMPADD(DAY, n, '2022-01-01'), n FROM tally WHERE n <= 1000;
ANALYZE TABLE s14_ex_log;

-- (a) YEAR(logged_at) = 2023  →  인덱스를 탄다 (type: ref, key: idx_ym)
EXPLAIN SELECT * FROM s14_ex_log WHERE YEAR(logged_at) = 2023;
--   표현식이 인덱스 정의 YEAR(logged_at) 와 정확히 일치 → 매핑 성공.

-- (b) YEAR(logged_at) + 0 = 2023  →  못 탄다 (type: ALL)
EXPLAIN SELECT * FROM s14_ex_log WHERE YEAR(logged_at) + 0 = 2023;
--   이유: 인덱스 표현식은 "YEAR(logged_at)" 인데 쿼리 표현식은 "YEAR(logged_at) + 0" 입니다.
--         옵티마이저는 수학적으로 같은지 따지지 않고 표현식을 "문자 그대로" 매칭합니다.
--         + 0 하나 붙었다고 인덱스가 죽습니다.
--   고치기: + 0 을 빼서 (a) 형태로 쓴다.

-- (c) logged_at >= '2023-01-01' AND logged_at < '2024-01-01'  →  못 탄다 (type: ALL)
EXPLAIN SELECT * FROM s14_ex_log WHERE logged_at >= '2023-01-01' AND logged_at < '2024-01-01';
--   이유: 이 조건은 logged_at "원본 컬럼"에 대한 범위 검색인데,
--         우리에겐 YEAR(logged_at) 함수 인덱스만 있고 logged_at 자체 인덱스는 없습니다.
--         함수 인덱스 YEAR(logged_at) 로는 원본 컬럼의 범위 검색을 처리할 수 없습니다.
--   고치기: logged_at 원본에 일반 인덱스를 만든다.
ALTER TABLE s14_ex_log ADD INDEX idx_logged (logged_at);
ANALYZE TABLE s14_ex_log;
EXPLAIN SELECT * FROM s14_ex_log WHERE logged_at >= '2023-01-01' AND logged_at < '2024-01-01';
--   → 이제 type: range, key: idx_logged 로 바뀝니다.
--
-- 💡 중요한 교훈:
--   (a) YEAR(logged_at)=2023 과 (c) 범위 검색은 "같은 데이터"를 찾지만,
--   실무에서는 (c) 의 범위 형태(sargable)가 더 낫습니다.
--   YEAR() 함수 인덱스는 "연도 단위" 조회에만 쓸 수 있지만,
--   logged_at 원본 인덱스는 "임의 기간"(지난 7일, 이번 분기 등) 조회를 전부 커버합니다.
--   즉 함수 인덱스를 남발하기보다, 원본 컬럼에 범위로 접근하도록 쿼리를 짜는 편이
--   더 범용적입니다. (Step 15/16 의 "인덱스를 못 타는 패턴"에서 다시 다룹니다.)


-- ---------------------------------------------------------------------
-- 정리
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS v14_ex_customer_stats, v14_ex_a, v14_ex_b, v14_ex_c, v14_ex_d,
                    v14_ex_instock, v14_ex_dev_highpay, v14_ex_dev;
DROP TABLE IF EXISTS s14_ex_prod, s14_ex_emp, s14_ex_cust, s14_ex_log;

SELECT 'Step 14 solution 완료' AS msg;
