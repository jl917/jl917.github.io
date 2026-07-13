-- =====================================================================
-- Step 13 — 제약 조건과 정규화 : exercise.sql  (문제 8개)
-- ---------------------------------------------------------------------
-- 정답은 solution.sql 에 있습니다. 먼저 스스로 풀어보세요.
--
-- ⚠️ 공용 테이블은 절대 수정하지 마세요. 새로 만드는 테이블은 s13_ex_ 접두사를 쓰세요.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [문제 1] 쿠폰 테이블 설계
-- ---------------------------------------------------------------------
-- 아래 요구사항을 모두 만족하는 s13_ex_coupons 테이블을 만드세요.
--
--   - coupon_id     : 자동 증가 PK
--   - code          : 쿠폰 코드. 필수이며 절대 중복될 수 없다 (NULL 도 안 됨)
--   - discount_pct  : 할인율. 필수, 기본값 10, 반드시 1 ~ 90 사이
--   - min_amount    : 최소 주문금액. 필수, 기본값 0, 음수 불가
--   - max_uses      : 최대 사용 횟수. NULL 이면 "무제한"이라는 뜻 (그래서 NULL 허용)
--                     단, 값이 있다면 1 이상이어야 한다
--   - expires_at    : 만료일시. 필수
--   - created_at    : 생성일시. 필수, 기본값은 현재 시각
--
-- 만든 뒤, 아래 INSERT 3개가 "의도대로" 동작하는지 확인하세요.
--   (1) 성공해야 함 : code='WELCOME10', expires_at='2026-12-31 23:59:59'  (나머지 기본값)
--   (2) 실패해야 함 : code='BAD', discount_pct=95, expires_at='2026-12-31 23:59:59'
--   (3) 실패해야 함 : code='WELCOME10' 중복

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 2] UNIQUE 인데 왜 중복이 들어갈까?
-- ---------------------------------------------------------------------
-- 아래 테이블은 "사업자번호는 유일해야 한다"는 요구사항으로 만들어졌습니다.
-- 그런데 운영 중에 사업자번호가 없는 개인 판매자 3명이 등록되자
-- "중복이 들어간 것 같다"는 제보가 들어왔습니다.

DROP TABLE IF EXISTS s13_ex_sellers;
CREATE TABLE s13_ex_sellers (
  seller_id INT AUTO_INCREMENT PRIMARY KEY,
  name      VARCHAR(50) NOT NULL,
  biz_no    VARCHAR(20) NULL,
  UNIQUE KEY uk_biz_no (biz_no)
) ENGINE=InnoDB;

INSERT INTO s13_ex_sellers (name, biz_no) VALUES
  ('회사A', '111-11-11111'),
  ('개인B', NULL),
  ('개인C', NULL),
  ('개인D', NULL);

-- (2-1) 위 INSERT 가 왜 전부 성공했는지 한 줄로 설명하세요. (주석으로)
-- (2-2) "사업자번호가 있는 판매자는 반드시 유일하다"는 요구사항은 이미 지켜지고 있습니다.
--       그것을 증명하는 INSERT 문을 하나 작성하세요. (실패해야 정상)
-- (2-3) 만약 "모든 판매자는 사업자번호가 필수이며 유일해야 한다"로 요구사항이 바뀌었다면
--       테이블을 어떻게 고쳐야 할까요? ALTER 문을 쓰세요.
--       (힌트: 기존 NULL 행이 있으면 그냥은 안 됩니다. 어떻게 처리할지도 함께)

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 3] CHECK 로 비즈니스 규칙 강제하기
-- ---------------------------------------------------------------------
-- s13_ex_items 테이블에 아래 3가지 규칙을 CHECK 제약으로 추가하세요.
--   (a) sale_price 는 list_price 보다 클 수 없다
--   (b) sale_price 와 list_price 는 둘 다 0 이상이다
--   (c) qty 는 1 이상 999 이하이다
--
-- 그리고 나서: qty 컬럼이 NULL 을 허용하는데, CHECK (qty BETWEEN 1 AND 999) 만으로
--             "NULL 도 막을 수 있는가?" 를 INSERT 로 직접 확인하고 결과를 주석으로 남기세요.

DROP TABLE IF EXISTS s13_ex_items;
CREATE TABLE s13_ex_items (
  item_id    INT AUTO_INCREMENT PRIMARY KEY,
  list_price DECIMAL(10,2) NOT NULL,
  sale_price DECIMAL(10,2) NOT NULL,
  qty        INT NULL
) ENGINE=InnoDB;

-- 여기에 ALTER TABLE ... ADD CONSTRAINT 작성:



-- ---------------------------------------------------------------------
-- [문제 4] FK 참조 동작 고르기
-- ---------------------------------------------------------------------
-- 아래 4가지 시나리오 각각에 대해 ON DELETE 옵션을
-- CASCADE / SET NULL / RESTRICT 중 무엇으로 할지 고르고, 이유를 주석으로 쓰세요.
--
--   (4-1) orders → order_items
--         주문을 지우면 그 주문의 상세도 의미가 없다. 주문당 상세는 보통 1~10건.
--
--   (4-2) customers → orders
--         고객이 탈퇴해도 주문 기록은 회계/정산 때문에 남아야 한다.
--
--   (4-3) employees → employees (manager_id)
--         팀장이 퇴사하면 팀원들은 남아야 하고, 관리자만 "없음" 상태가 되어야 한다.
--
--   (4-4) categories → products
--         카테고리 하나에 상품이 수만 개 달릴 수 있다.
--         카테고리를 실수로 지웠을 때 상품이 전부 사라지면 절대 안 된다.

-- 여기에 주석으로 답:



-- ---------------------------------------------------------------------
-- [문제 5] CASCADE 지뢰 찾기
-- ---------------------------------------------------------------------
-- shop 스키마(01_schema.sql)에 실제로 걸려 있는 FK 중에서
-- ON DELETE CASCADE 인 것을 information_schema 에서 조회하는 쿼리를 작성하세요.
--
-- 그리고 그중 "가장 위험한" 것 하나를 골라, 부모 1행을 지웠을 때
-- 실제로 몇 행의 자식이 함께 삭제되는지 COUNT 로 계산하는 쿼리를 쓰세요.
-- (절대 진짜로 DELETE 하지 마세요! COUNT 만 하세요.)

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 6] AUTO_INCREMENT 구멍 예측
-- ---------------------------------------------------------------------
-- 아래 SQL 을 순서대로 실행했을 때, 마지막 SELECT 결과의 id 값들을 예측하세요.
-- 예측을 주석으로 먼저 적은 뒤에 실행해서 맞는지 확인하세요.

DROP TABLE IF EXISTS s13_ex_ai;
CREATE TABLE s13_ex_ai (
  id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  val VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_val (val)
) ENGINE=InnoDB;

INSERT INTO s13_ex_ai (val) VALUES ('x'), ('y');      -- ?
START TRANSACTION;
INSERT INTO s13_ex_ai (val) VALUES ('z');             -- ?
ROLLBACK;
INSERT INTO s13_ex_ai (val) VALUES ('x');             -- 실패 (중복)
INSERT INTO s13_ex_ai (val) VALUES ('w');             -- ?
DELETE FROM s13_ex_ai WHERE val = 'y';
INSERT INTO s13_ex_ai (val) VALUES ('v');             -- ?

-- 예측: id = ?, ?, ?  ...
SELECT id, val FROM s13_ex_ai ORDER BY id;


-- ---------------------------------------------------------------------
-- [문제 7] 3NF 위반 찾아서 분해하기
-- ---------------------------------------------------------------------
-- 아래 테이블은 3NF 를 위반하고 있습니다.

DROP TABLE IF EXISTS s13_ex_bad;
CREATE TABLE s13_ex_bad (
  enroll_id     INT AUTO_INCREMENT PRIMARY KEY,
  student_id    INT NOT NULL,
  student_name  VARCHAR(30) NOT NULL,
  course_code   VARCHAR(10) NOT NULL,
  course_title  VARCHAR(50) NOT NULL,
  professor     VARCHAR(30) NOT NULL,
  professor_tel VARCHAR(20) NOT NULL,
  score         INT NULL
) ENGINE=InnoDB;

-- (7-1) 어떤 컬럼이 어떤 컬럼에 이행적으로 종속되는지 주석으로 쓰세요.
-- (7-2) 3NF 를 만족하도록 테이블을 3~4개로 분해해서 CREATE TABLE 문을 쓰세요.
--       (PK, FK 를 반드시 포함할 것)

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 8] 반정규화 컬럼의 동기화 검증
-- ---------------------------------------------------------------------
-- orders.total_amount 는 order_items 합계를 복사해 둔 반정규화 컬럼입니다.
--
-- (8-1) 저장된 total_amount 와 실제 order_items 합계가 다른 주문을 찾는 쿼리를 쓰세요.
--       (지금은 0건이 나오는 게 정상입니다)
--
-- (8-2) 함정: 위 쿼리를 JOIN 으로만 쓰면 놓치는 경우가 있습니다.
--       "order_items 가 한 건도 없는 주문"은 JOIN 에서 아예 빠져버립니다.
--       그런 주문이 있는데 total_amount 가 0 이 아니라면 그것도 불일치입니다.
--       이 경우까지 잡아내는 쿼리로 고치세요. (힌트: LEFT JOIN 또는 상관 서브쿼리)

-- 여기에 작성:
