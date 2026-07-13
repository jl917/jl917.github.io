-- =====================================================================
-- Step 14 — 뷰와 생성 컬럼 : exercise.sql  (문제 6개)
-- ---------------------------------------------------------------------
-- 정답은 solution.sql. 새로 만드는 객체는 v14_ex_ / s14_ex_ 접두사를 쓰세요.
-- 공용 테이블에는 뷰(읽기)만 만들고, 변경 실습은 사본에서 하세요.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [문제 1] 고객별 주문 통계 뷰
-- ---------------------------------------------------------------------
-- v14_ex_customer_stats 뷰를 만드세요. 컬럼:
--   customer_id, name, grade,
--   order_cnt      : 그 고객의 (취소 아닌) 주문 수
--   total_spent    : (취소 아닌) 주문 total_amount 합계
--   last_order_date: 마지막 주문일
-- CANCELLED 주문은 제외합니다.
-- 주문이 한 건도 없는 고객도 결과에 포함되어야 합니다 (order_cnt = 0).
--
-- 만든 뒤 total_spent 상위 5명을 조회하세요.

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 2] 이 뷰들은 업데이트 가능한가?
-- ---------------------------------------------------------------------
-- 아래 각 뷰가 업데이트 가능한지(IS_UPDATABLE) 예측하고, 이유를 주석으로 쓰세요.
-- 그런 다음 information_schema.VIEWS 로 실제로 확인하세요.

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

-- (2-1) 예측을 주석으로:  a=?, b=?, c=?, d=?
-- (2-2) 확인 쿼리 작성:
-- (2-3) v14_ex_d 는 업데이트 가능하다면, price_with_vat 컬럼을 UPDATE 할 수 있을까요?
--       직접 시도해서 결과를 주석으로 남기세요.

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 3] WITH CHECK OPTION 으로 재고 통제
-- ---------------------------------------------------------------------
-- "재고가 있는 상품만" 보여주는 v14_ex_instock 뷰를 만들되,
-- 이 뷰를 통해서는 stock 을 0 이하로 만들 수 없도록 하세요.
-- (즉, 뷰를 통한 UPDATE 로 재고를 다 소진시켜 뷰에서 사라지게 하는 것을 막습니다)
--
-- 검증:
--   (a) stock 을 1 로 줄이는 UPDATE → 성공해야 함
--   (b) stock 을 0 으로 줄이는 UPDATE → 거부되어야 함
-- (s14_ex_prod 를 대상으로 하세요)

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 4] LOCAL vs CASCADED 결과 예측
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s14_ex_emp;
CREATE TABLE s14_ex_emp (
  emp_id INT PRIMARY KEY,
  dept   VARCHAR(20) NOT NULL,
  salary INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO s14_ex_emp VALUES (1,'개발',5000),(2,'개발',3000),(3,'영업',4000);

CREATE OR REPLACE VIEW v14_ex_dev AS
  SELECT * FROM s14_ex_emp WHERE dept = '개발';                    -- CHECK OPTION 없음

CREATE OR REPLACE VIEW v14_ex_dev_highpay AS
  SELECT * FROM v14_ex_dev WHERE salary >= 4000 WITH LOCAL CHECK OPTION;

-- (4-1) 다음 UPDATE 는 성공할까 실패할까? 예측 후 실행:
--       UPDATE v14_ex_dev_highpay SET dept = '영업' WHERE emp_id = 1;
--       (dept 를 바꾸면 부모 뷰 v14_ex_dev 의 조건에서 벗어남. 하지만 LOCAL 이라면?)
-- (4-2) WITH LOCAL 을 WITH CASCADED 로 바꾸면 결과가 달라지나요? 확인하세요.

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 5] 생성 컬럼 + 인덱스로 이메일 도메인 검색
-- ---------------------------------------------------------------------
-- 고객 이메일에서 "@ 뒤 도메인"으로 검색을 자주 한다고 합시다.
--   WHERE SUBSTRING_INDEX(email, '@', -1) = 'example.com'
-- 이 조건은 email 인덱스를 못 탑니다.
--
-- s14_ex_cust 사본에 email_domain 생성 컬럼을 추가하고 인덱스를 걸어,
-- 도메인 검색이 인덱스를 타도록 만드세요. EXPLAIN 으로 type 이 바뀌는지 확인하세요.

DROP TABLE IF EXISTS s14_ex_cust;
CREATE TABLE s14_ex_cust AS SELECT customer_id, email, name FROM customers;
ALTER TABLE s14_ex_cust ADD PRIMARY KEY (customer_id);
-- (도메인 분포를 만들기 위해 일부 이메일을 다른 도메인으로 바꿔둡니다)
UPDATE s14_ex_cust SET email = REPLACE(email, 'example.com', 'test.org') WHERE customer_id % 3 = 0;

-- 여기에 작성 (ALTER TABLE ... ADD COLUMN ... AS (...) / ADD INDEX / EXPLAIN):



-- ---------------------------------------------------------------------
-- [문제 6] 함수 기반 인덱스가 안 먹는 쿼리 진단
-- ---------------------------------------------------------------------
-- 아래 테이블에 함수 기반 인덱스 idx_ym ((YEAR(logged_at))) 이 있습니다.
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

-- 아래 세 쿼리 중 idx_ym 을 "타는" 것과 "못 타는" 것을 EXPLAIN 으로 구분하고,
-- 못 타는 것은 왜 그런지 주석으로 설명하세요. (고칠 수 있으면 고쳐보세요)
--
--   (a) SELECT * FROM s14_ex_log WHERE YEAR(logged_at) = 2023;
--   (b) SELECT * FROM s14_ex_log WHERE YEAR(logged_at) + 0 = 2023;
--   (c) SELECT * FROM s14_ex_log WHERE logged_at >= '2023-01-01' AND logged_at < '2024-01-01';

-- 여기에 작성:
