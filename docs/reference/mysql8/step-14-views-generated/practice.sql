-- =====================================================================
-- Step 14 — 뷰와 생성 컬럼 : practice.sql
-- ---------------------------------------------------------------------
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 --force shop < practice.sql
--        ( --force : 일부러 에러를 내는 예제가 있어서 계속 진행 )
--
-- ⚠️ 공용 테이블에는 "뷰(읽기)"만 만듭니다. 변경 실습은 s14_ 사본에서.
-- =====================================================================
USE shop;

DROP VIEW IF EXISTS v14_vip_seoul, v14_vip, v14_grouped, v14_order_summary,
                    v14_high, v14_merge, v14_temptable;
DROP TABLE IF EXISTS s14_customers, s14_gen, s14_inv, s14_scores;


-- ---------------------------------------------------------------------
-- [14-0] 실습 준비 — 변경 실습용 사본
-- ---------------------------------------------------------------------
CREATE TABLE s14_customers AS SELECT * FROM customers;
ALTER TABLE s14_customers ADD PRIMARY KEY (customer_id);


-- ---------------------------------------------------------------------
-- [14-1] 뷰 기본 — 복잡한 JOIN 을 이름 붙여 감춘다
-- ---------------------------------------------------------------------
CREATE VIEW v14_order_summary AS
SELECT o.order_id, o.order_date, o.status,
       c.name AS customer_name, c.grade, o.total_amount
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.status <> 'CANCELLED';

SELECT * FROM v14_order_summary WHERE grade = 'VIP' ORDER BY total_amount DESC LIMIT 5;

-- 뷰 정의와 업데이트 가능 여부 확인
SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME LIKE 'v14%';

-- 뷰 관리 명령 (정의 확인). 대화형 클라이언트에서는 \G 로 세로 출력하면 보기 좋다.
SHOW CREATE VIEW v14_order_summary;


-- ---------------------------------------------------------------------
-- [14-2] 업데이트 가능한 뷰
-- ---------------------------------------------------------------------
CREATE VIEW v14_vip AS
SELECT customer_id, name, grade, city, points
FROM s14_customers
WHERE grade = 'VIP';

SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='v14_vip';       -- YES

-- 뷰를 통한 UPDATE → 원본(s14_customers)이 바뀐다
UPDATE v14_vip SET points = points + 1000 WHERE customer_id = 1;
SELECT customer_id, name, points FROM s14_customers WHERE customer_id = 1;   -- 13500

-- 집계가 있는 뷰는 업데이트 불가
CREATE VIEW v14_grouped AS
SELECT grade, COUNT(*) AS cnt, AVG(points) AS avg_points
FROM s14_customers GROUP BY grade;

SELECT TABLE_NAME, IS_UPDATABLE FROM information_schema.VIEWS
WHERE TABLE_SCHEMA='shop' AND TABLE_NAME='v14_grouped';   -- NO

UPDATE v14_grouped SET cnt = 99 WHERE grade = 'VIP';      -- ERROR 1288


-- ---------------------------------------------------------------------
-- [14-3] WITH CHECK OPTION
-- ---------------------------------------------------------------------
-- CHECK OPTION 없는 뷰: 조건을 벗어나는 UPDATE 가 통과 → 행이 뷰에서 사라진다
UPDATE v14_vip SET grade = 'GOLD' WHERE customer_id = 1;
SELECT customer_id, name, grade FROM s14_customers WHERE customer_id = 1;    -- GOLD 로 바뀜
SELECT COUNT(*) AS still_in_view FROM v14_vip WHERE customer_id = 1;         -- 0 (증발!)
UPDATE s14_customers SET grade = 'VIP' WHERE customer_id = 1;                -- 원복

-- WITH CHECK OPTION 을 걸면 그런 변경을 거부
CREATE OR REPLACE VIEW v14_vip AS
SELECT customer_id, name, grade, city, points
FROM s14_customers WHERE grade = 'VIP'
WITH CHECK OPTION;

UPDATE v14_vip SET grade = 'GOLD' WHERE customer_id = 1;   -- ERROR 1369 CHECK OPTION failed

-- INSERT 도 검사한다 (깨끗한 데모용 테이블)
DROP TABLE IF EXISTS s14_scores;
CREATE TABLE s14_scores (
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(20) NOT NULL,
  score INT NOT NULL
) ENGINE=InnoDB;
CREATE VIEW v14_high AS
SELECT id, name, score FROM s14_scores WHERE score >= 60 WITH CHECK OPTION;

INSERT INTO v14_high (name, score) VALUES ('통과', 80);    -- OK
INSERT INTO v14_high (name, score) VALUES ('탈락', 40);    -- ERROR 1369 (score < 60)
SELECT * FROM s14_scores;

-- LOCAL vs CASCADED
CREATE OR REPLACE VIEW v14_vip AS
SELECT customer_id, name, grade, city, points FROM s14_customers WHERE grade = 'VIP';

-- LOCAL: 자기 조건(city)만 검사, 부모 조건(grade)은 안 봄 → grade 변경 통과
CREATE OR REPLACE VIEW v14_vip_seoul AS
SELECT * FROM v14_vip WHERE city = '서울' WITH LOCAL CHECK OPTION;
UPDATE v14_vip_seoul SET grade = 'GOLD' WHERE customer_id = 1;
SELECT customer_id, grade, city FROM s14_customers WHERE customer_id = 1;    -- GOLD (통과)
UPDATE s14_customers SET grade = 'VIP' WHERE customer_id = 1;

-- CASCADED: 부모 뷰의 grade='VIP' 조건까지 검사 → 거부
CREATE OR REPLACE VIEW v14_vip_seoul AS
SELECT * FROM v14_vip WHERE city = '서울' WITH CASCADED CHECK OPTION;
UPDATE v14_vip_seoul SET grade = 'GOLD' WHERE customer_id = 1;   -- ERROR 1369


-- ---------------------------------------------------------------------
-- [14-4] ALGORITHM MERGE vs TEMPTABLE
-- ---------------------------------------------------------------------
CREATE ALGORITHM=MERGE VIEW v14_merge AS
SELECT log_id, customer_id, path, status_code, logged_at FROM access_logs;

CREATE ALGORITHM=TEMPTABLE VIEW v14_temptable AS
SELECT log_id, customer_id, path, status_code, logged_at FROM access_logs;

-- MERGE: PK 조건이 원본까지 전달됨 → type: const, rows=1
EXPLAIN SELECT * FROM v14_merge WHERE log_id = 1;

-- TEMPTABLE: <derived2> 임시테이블을 거친다
EXPLAIN SELECT * FROM v14_temptable WHERE log_id = 1;

-- 실제 시간 비교 (status_code 인덱스는 아직 없음)
SELECT COUNT(*) FROM v14_merge     WHERE status_code = 500;   -- 약 0.096 sec
SELECT COUNT(*) FROM v14_temptable WHERE status_code = 500;   -- 약 0.136 sec (임시테이블 구체화)


-- ---------------------------------------------------------------------
-- [14-5] 생성 컬럼 (VIRTUAL vs STORED)
-- ---------------------------------------------------------------------
CREATE TABLE s14_gen (
  product_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name       VARCHAR(50) NOT NULL,
  price      DECIMAL(10,2) NOT NULL,
  cost       DECIMAL(10,2) NOT NULL,
  margin     DECIMAL(10,2) AS (price - cost) VIRTUAL,                      -- 읽을 때 계산
  margin_pct DECIMAL(5,2)  AS (ROUND((price-cost)/price*100, 2)) STORED,   -- 디스크에 저장
  name_upper VARCHAR(50)   AS (UPPER(name)) VIRTUAL,
  PRIMARY KEY (product_id)
) ENGINE=InnoDB;

INSERT INTO s14_gen (name, price, cost) VALUES ('shirt', 39000, 18000), ('laptop', 1290000, 980000);
SELECT product_id, name, price, cost, margin, margin_pct, name_upper FROM s14_gen;

-- 생성 컬럼에 직접 값 INSERT 불가 → ERROR 3105
INSERT INTO s14_gen (name, price, cost, margin) VALUES ('x', 100, 50, 999);

-- 원본이 바뀌면 생성 컬럼 자동 갱신
UPDATE s14_gen SET price = 45000 WHERE name = 'shirt';
SELECT product_id, name, price, cost, margin, margin_pct FROM s14_gen WHERE name = 'shirt';


-- ---------------------------------------------------------------------
-- [14-6] 함수 인덱스 만들기
-- ---------------------------------------------------------------------
-- 함수 인덱스가 실제로 사용되는 걸 보려면 데이터가 충분해야 한다 → 5000행 추가
INSERT INTO s14_gen (name, price, cost)
SELECT CONCAT('item', n), 1000 + n, 500 FROM tally WHERE n <= 5000;

-- 방법 1) 생성 컬럼 + 인덱스
--   인덱스 없이 UPPER(name) 조회 → type: ALL (풀스캔)
EXPLAIN SELECT * FROM s14_gen WHERE UPPER(name) = 'SHIRT';

ALTER TABLE s14_gen ADD INDEX idx_name_upper (name_upper);
--   생성 컬럼에 인덱스 → UPPER(name) 을 옵티마이저가 자동 매핑 → type: ref
EXPLAIN SELECT * FROM s14_gen WHERE UPPER(name) = 'SHIRT';

-- 방법 2) 함수 기반 인덱스 (8.0.13+) — 표현식에 직접, 괄호로 감싼다
ALTER TABLE s14_gen ADD INDEX idx_func_margin ((price - cost));
ANALYZE TABLE s14_gen;                                          -- 통계 갱신 필수!

--   함정: 리터럴 타입이 표현식 타입(DECIMAL)과 맞아야 인덱스를 탄다
--   3500 (정수) 이 아니라 3500.00 (DECIMAL) 으로 써야 한다
EXPLAIN SELECT product_id, name FROM s14_gen WHERE (price - cost) = 3500.00;    -- type: ref
SELECT product_id, name, margin FROM s14_gen WHERE (price - cost) = 3500.00;

-- 인덱스에 표현식이 그대로 보인다
SHOW INDEX FROM s14_gen;


-- ---------------------------------------------------------------------
-- [14-7] 인비저블 컬럼 (8.0.23+)
-- ---------------------------------------------------------------------
CREATE TABLE s14_inv (
  id     INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name   VARCHAR(50) NOT NULL,
  secret VARCHAR(50) NOT NULL INVISIBLE,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO s14_inv (id, name, secret) VALUES (1, '홍길동', 'password123');

SELECT * FROM s14_inv;                    -- secret 안 보임
SELECT id, name, secret FROM s14_inv;     -- 명시하면 보임

-- 토글
ALTER TABLE s14_inv ALTER COLUMN secret SET VISIBLE;
SELECT * FROM s14_inv;                     -- 이제 secret 보임
ALTER TABLE s14_inv ALTER COLUMN secret SET INVISIBLE;


-- ---------------------------------------------------------------------
-- 정리
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS v14_vip_seoul, v14_vip, v14_grouped, v14_order_summary,
                    v14_high, v14_merge, v14_temptable;
DROP TABLE IF EXISTS s14_customers, s14_gen, s14_inv, s14_scores;

SELECT 'Step 14 practice 완료' AS msg;
