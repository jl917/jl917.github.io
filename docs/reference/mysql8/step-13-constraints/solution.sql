-- =====================================================================
-- Step 13 — 제약 조건과 정규화 : solution.sql  (정답 + 해설)
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [정답 1] 쿠폰 테이블 설계
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_ex_coupons;
CREATE TABLE s13_ex_coupons (
  coupon_id    INT UNSIGNED NOT NULL AUTO_INCREMENT,
  code         VARCHAR(30)  NOT NULL,                  -- NOT NULL + UNIQUE 를 "둘 다" 걸어야
  discount_pct TINYINT UNSIGNED NOT NULL DEFAULT 10,   --   진짜 유일성이 보장된다
  min_amount   DECIMAL(10,2) NOT NULL DEFAULT 0,
  max_uses     INT UNSIGNED NULL,                      -- NULL = 무제한 (의미 있는 NULL)
  expires_at   DATETIME NOT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (coupon_id),
  UNIQUE KEY uk_coupon_code (code),
  CONSTRAINT chk_cp_pct     CHECK (discount_pct BETWEEN 1 AND 90),
  CONSTRAINT chk_cp_min     CHECK (min_amount >= 0),
  CONSTRAINT chk_cp_uses    CHECK (max_uses IS NULL OR max_uses >= 1)
) ENGINE=InnoDB;

-- (1) 성공
INSERT INTO s13_ex_coupons (code, expires_at) VALUES ('WELCOME10', '2026-12-31 23:59:59');
SELECT coupon_id, code, discount_pct, min_amount, max_uses FROM s13_ex_coupons;

-- (2) 실패 : discount_pct = 95 → ERROR 3819 Check constraint 'chk_cp_pct' is violated.
INSERT INTO s13_ex_coupons (code, discount_pct, expires_at) VALUES ('BAD', 95, '2026-12-31 23:59:59');

-- (3) 실패 : code 중복 → ERROR 1062 Duplicate entry 'WELCOME10' for key 'uk_coupon_code'
INSERT INTO s13_ex_coupons (code, expires_at) VALUES ('WELCOME10', '2026-12-31 23:59:59');

-- 해설
--   * max_uses 의 CHECK 를 그냥 `CHECK (max_uses >= 1)` 로만 써도 NULL 은 통과합니다
--     (NULL >= 1 은 UNKNOWN → 통과). 그래서 사실 IS NULL OR 은 없어도 동작합니다.
--     하지만 "NULL 을 의도적으로 허용한다"는 것을 코드로 명시하는 편이 읽는 사람에게 친절합니다.
--   * discount_pct 에 UNSIGNED 를 썼다고 음수가 막히는 게 아닙니다.
--     STRICT 모드가 꺼져 있으면 음수가 0 으로 조용히 바뀝니다. CHECK 가 정답입니다.


-- ---------------------------------------------------------------------
-- [정답 2] UNIQUE 인데 왜 중복이 들어갈까?
-- ---------------------------------------------------------------------
-- (셋업) exercise.sql 을 먼저 풀지 않고 이 파일만 실행해도 되도록, 문제의 테이블을 여기서 만듭니다.
DROP TABLE IF EXISTS s13_ex_sellers;
CREATE TABLE s13_ex_sellers (
  seller_id INT AUTO_INCREMENT PRIMARY KEY,
  name      VARCHAR(50) NOT NULL,
  biz_no    VARCHAR(20) NULL,
  UNIQUE KEY uk_biz_no (biz_no)
) ENGINE=InnoDB;
INSERT INTO s13_ex_sellers (name, biz_no) VALUES
  ('회사A', '111-11-11111'), ('개인B', NULL), ('개인C', NULL), ('개인D', NULL);

-- (2-1) 답:
--   UNIQUE 제약은 NULL 을 중복으로 취급하지 않습니다. SQL 표준에서 NULL 은 "값이 없음"이라
--   NULL 끼리 서로 같다고 보지 않기 때문입니다. 그래서 NULL 은 몇 개든 들어갑니다.
--   → 이건 버그가 아니라 정상 동작이며, "중복이 들어갔다"는 제보 자체가 오해입니다.

-- (2-2) 사업자번호가 "있는" 판매자의 유일성은 지켜지고 있음을 증명:
--       → ERROR 1062: Duplicate entry '111-11-11111' for key 's13_ex_sellers.uk_biz_no'
INSERT INTO s13_ex_sellers (name, biz_no) VALUES ('회사A-복제', '111-11-11111');

-- (2-3) "모든 판매자는 사업자번호 필수 + 유일" 로 요구사항이 바뀐 경우:
--   기존 NULL 행이 있으므로 NOT NULL 로 바로 못 바꿉니다.
--   → 먼저 NULL 행을 처리(값 채우기 또는 삭제)한 뒤 ALTER 해야 합니다.

-- 1단계: NULL 행이 몇 개인지 확인
SELECT COUNT(*) AS null_biz_no FROM s13_ex_sellers WHERE biz_no IS NULL;    -- 3

-- 2단계: 값을 채운다 (실무에서는 담당자가 실제 값을 채워 넣습니다.
--         여기서는 예시로 임시값을 넣습니다 — 절대 운영에서 이러면 안 됩니다!)
UPDATE s13_ex_sellers SET biz_no = CONCAT('TEMP-', seller_id) WHERE biz_no IS NULL;

-- 3단계: 이제 NOT NULL 로 바꿀 수 있다
ALTER TABLE s13_ex_sellers MODIFY COLUMN biz_no VARCHAR(20) NOT NULL;

SHOW CREATE TABLE s13_ex_sellers;
-- uk_biz_no 는 그대로 두면 됩니다. NOT NULL + UNIQUE 조합이 완성되었습니다.


-- ---------------------------------------------------------------------
-- [정답 3] CHECK 로 비즈니스 규칙 강제하기
-- ---------------------------------------------------------------------
-- (셋업) 이 파일만 실행해도 되도록 문제의 테이블을 여기서 만듭니다.
DROP TABLE IF EXISTS s13_ex_items;
CREATE TABLE s13_ex_items (
  item_id    INT AUTO_INCREMENT PRIMARY KEY,
  list_price DECIMAL(10,2) NOT NULL,
  sale_price DECIMAL(10,2) NOT NULL,
  qty        INT NULL
) ENGINE=InnoDB;

ALTER TABLE s13_ex_items
  ADD CONSTRAINT chk_it_sale_le_list CHECK (sale_price <= list_price),
  ADD CONSTRAINT chk_it_prices_pos   CHECK (list_price >= 0 AND sale_price >= 0),
  ADD CONSTRAINT chk_it_qty_range    CHECK (qty BETWEEN 1 AND 999);

-- 규칙 확인
INSERT INTO s13_ex_items (list_price, sale_price, qty) VALUES (10000, 8000, 5);   -- OK
INSERT INTO s13_ex_items (list_price, sale_price, qty) VALUES (10000, 12000, 5);  -- ERROR 3819 (a 위반)
INSERT INTO s13_ex_items (list_price, sale_price, qty) VALUES (10000, 8000, 1500);-- ERROR 3819 (c 위반)

-- 핵심 질문: CHECK (qty BETWEEN 1 AND 999) 만으로 NULL 을 막을 수 있는가?
INSERT INTO s13_ex_items (list_price, sale_price, qty) VALUES (10000, 8000, NULL);
SELECT item_id, list_price, sale_price, qty FROM s13_ex_items;

-- 답: 막지 못합니다. NULL 이 그대로 들어갑니다.
--   NULL BETWEEN 1 AND 999  →  UNKNOWN
--   CHECK 는 결과가 "FALSE 일 때만" 거부합니다. UNKNOWN 은 통과입니다.
--
-- NULL 도 막으려면 둘 중 하나:
--   (방법 A) 컬럼에 NOT NULL 을 건다        ← 권장
--   (방법 B) CHECK (qty IS NOT NULL AND qty BETWEEN 1 AND 999)
--
-- 방법 A 로 고치기:
DELETE FROM s13_ex_items WHERE qty IS NULL;                       -- 기존 NULL 행 정리 후
ALTER TABLE s13_ex_items MODIFY COLUMN qty INT NOT NULL;
INSERT INTO s13_ex_items (list_price, sale_price, qty) VALUES (10000, 8000, NULL);  -- 이제 ERROR 1048


-- ---------------------------------------------------------------------
-- [정답 4] FK 참조 동작 고르기
-- ---------------------------------------------------------------------
--
-- (4-1) orders → order_items  :  ON DELETE CASCADE
--       주문 상세는 주문 없이는 존재 의미가 없는 "종속 엔티티"입니다.
--       주문당 상세가 1~10건으로 적으므로 CASCADE 의 대량 삭제 위험도 낮습니다.
--       → 실제로 01_schema.sql 이 이렇게 되어 있습니다.
--
-- (4-2) customers → orders    :  ON DELETE RESTRICT  (= 기본값, 생략)
--       주문은 회계 기록입니다. 고객이 탈퇴해도 절대 사라지면 안 됩니다.
--       CASCADE 였다면 탈퇴 한 번에 그 고객의 모든 주문/결제 기록이 증발합니다.
--       실무에서는 customers 를 물리 삭제하지 않고 `deleted_at` 으로 소프트 삭제합니다.
--       → 01_schema.sql 도 RESTRICT(기본값)입니다.
--
-- (4-3) employees → employees :  ON DELETE SET NULL
--       팀장이 퇴사해도 팀원은 남아야 합니다. manager_id 만 NULL(= 관리자 없음)이 되면 됩니다.
--       CASCADE 였다면 본부장 한 명 퇴사에 그 밑의 조직 전체가 삭제됩니다.
--       (주의: manager_id 가 NULL 허용이어야 SET NULL 을 걸 수 있습니다. employees 는 NULL 허용입니다.)
--
-- (4-4) categories → products :  ON DELETE RESTRICT
--       자식이 수만 건인 관계에 CASCADE 를 걸면 재앙입니다.
--       "카테고리 1건 삭제 = 상품 5만건 + 그에 딸린 리뷰 30만건 삭제"가 되고,
--       그 트랜잭션이 수십만 행에 X락을 잡은 채 서비스를 멈춥니다.
--       RESTRICT 로 막아두고, 정말 지워야 한다면 애플리케이션이 배치로 나눠서 처리하게 합니다.
--       → 01_schema.sql 도 RESTRICT 입니다.
--
-- 판단 규칙 요약:
--   * 자식이 부모 없이 무의미하고, 자식 수가 적다      → CASCADE
--   * 자식이 부모 없이도 독립적으로 의미가 있다        → SET NULL
--   * 자식이 회계/법적 기록이거나, 자식 수가 많다      → RESTRICT


-- ---------------------------------------------------------------------
-- [정답 5] CASCADE 지뢰 찾기
-- ---------------------------------------------------------------------
-- shop 스키마에서 ON DELETE CASCADE 인 FK 를 모두 조회
SELECT rc.CONSTRAINT_NAME,
       rc.TABLE_NAME            AS child_table,
       rc.REFERENCED_TABLE_NAME AS parent_table,
       rc.DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.CONSTRAINT_SCHEMA = 'shop'
  AND rc.DELETE_RULE = 'CASCADE'
  AND rc.TABLE_NAME NOT LIKE 's1%';        -- 실습용 사본 테이블 제외

-- 결과
-- +----------------------+-------------+-----------+-------------+
-- | CONSTRAINT_NAME      | child_table | parent    | DELETE_RULE |
-- +----------------------+-------------+-----------+-------------+
-- | fk_order_items_order | order_items | orders    | CASCADE     |
-- | fk_payments_order    | payments    | orders    | CASCADE     |
-- | fk_reviews_customer  | reviews     | customers | CASCADE     |   ← 가장 위험!
-- | fk_reviews_product   | reviews     | products  | CASCADE     |
-- +----------------------+-------------+-----------+-------------+

-- 가장 위험한 것: fk_reviews_customer (customers → reviews CASCADE)
--   이유: orders → order_items/payments 는 "주문 1건당 상세 몇 건"이라 폭발 규모가 작습니다.
--         하지만 customers → reviews 는 "고객 1명당 리뷰 수십~수백 건"이 될 수 있고,
--         무엇보다 리뷰는 그 고객만의 것이 아니라 "상품 페이지의 자산"입니다.
--         고객 1명을 지웠을 뿐인데 상품 평점이 바뀌어 버립니다.

-- 고객 1명(customer_id=1)을 지우면 리뷰가 몇 건 함께 삭제되는가?
-- (절대 DELETE 하지 않고 COUNT 만!)
SELECT c.customer_id, c.name,
       (SELECT COUNT(*) FROM reviews r WHERE r.customer_id = c.customer_id) AS cascade_deleted_reviews
FROM customers c
WHERE c.customer_id = 1;
-- +-------------+-----------+-------------------------+
-- | customer_id | name      | cascade_deleted_reviews |
-- +-------------+-----------+-------------------------+
-- |           1 | 김민수    |                      20 |
-- +-------------+-----------+-------------------------+

-- 전체 고객에 대해 "삭제 시 파급 규모" 를 미리 확인하는 쿼리 (실무 안전 점검용)
SELECT c.customer_id, c.name,
       (SELECT COUNT(*) FROM reviews r WHERE r.customer_id = c.customer_id) AS reviews_lost
FROM customers c
ORDER BY reviews_lost DESC
LIMIT 5;

-- 💡 실무 팁: 위험한 DELETE 를 실행하기 전에는 항상 같은 WHERE 절로 SELECT COUNT 를 먼저 돌리세요.
--    CASCADE 는 "1 row affected" 라고만 보고하기 때문에, 실행 후에는 규모를 알 방법이 없습니다.


-- ---------------------------------------------------------------------
-- [정답 6] AUTO_INCREMENT 구멍 예측
-- ---------------------------------------------------------------------
-- 정답: id = 1, 5, 6   (val = 'x', 'w', 'v')
--
-- 추적:
--   INSERT ('x'),('y')  → 1, 2 사용.  카운터 = 3
--   START TRANSACTION
--   INSERT ('z')        → 3 사용.     카운터 = 4
--   ROLLBACK            → 'z' 행은 사라지지만 카운터는 4 그대로 (구멍 1)
--   INSERT ('x')        → 4 를 가져갔다가 UNIQUE 위반으로 실패. 카운터 = 5 (구멍 2)
--   INSERT ('w')        → 5 사용.     카운터 = 6
--   DELETE 'y'          → id=2 행 삭제. 카운터는 그대로 6 (구멍 3)
--   INSERT ('v')        → 6 사용.     카운터 = 7
--
--   남은 행: (1,'x'), (5,'w'), (6,'v')
--   사라진 번호: 2(DELETE), 3(ROLLBACK), 4(실패한 INSERT)
--
-- 실제 실행 결과:
-- +----+-----+
-- | id | val |
-- +----+-----+
-- |  1 | x   |
-- |  5 | w   |
-- |  6 | v   |
-- +----+-----+
--
-- 교훈: id 는 "유일성"만 보장합니다. 연속성도, 행 개수도 보장하지 않습니다.
--       "id 최댓값 = 총 가입자 수" 같은 계산은 반드시 틀립니다. COUNT(*) 를 쓰세요.


-- ---------------------------------------------------------------------
-- [정답 7] 3NF 위반 찾아서 분해하기
-- ---------------------------------------------------------------------
-- (7-1) 이행적 함수 종속 분석
--
--   PK = enroll_id
--
--   enroll_id → student_id → student_name          (이행 종속! 3NF 위반)
--   enroll_id → course_code → course_title         (이행 종속! 3NF 위반)
--   enroll_id → course_code → professor            (이행 종속! 3NF 위반)
--   enroll_id → professor   → professor_tel        (이행 종속! 3NF 위반, 2단계나 건너뜀)
--   enroll_id → score                              (정상: PK 에 직접 종속)
--
--   즉 student_name / course_title / professor / professor_tel 은
--   PK(enroll_id)가 아니라 "PK 가 아닌 다른 컬럼"에 의존하고 있습니다.
--
--   실제 이상현상:
--     - 교수 전화번호가 바뀌면 그 교수의 모든 수강 행을 UPDATE 해야 함 (갱신 이상)
--     - 아직 수강생이 없는 과목은 등록할 방법이 없음 (삽입 이상)
--     - 마지막 수강생이 취소하면 과목 정보가 통째로 사라짐 (삭제 이상)

-- (7-2) 3NF 분해 : 4개 테이블로
DROP TABLE IF EXISTS s13_ex_enroll;
DROP TABLE IF EXISTS s13_ex_course;
DROP TABLE IF EXISTS s13_ex_professor;
DROP TABLE IF EXISTS s13_ex_student;

CREATE TABLE s13_ex_student (
  student_id   INT UNSIGNED NOT NULL,
  student_name VARCHAR(30) NOT NULL,
  PRIMARY KEY (student_id)
) ENGINE=InnoDB;

CREATE TABLE s13_ex_professor (
  professor_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name          VARCHAR(30) NOT NULL,
  tel           VARCHAR(20) NOT NULL,        -- professor 에만 종속 → 여기가 제자리
  PRIMARY KEY (professor_id),
  UNIQUE KEY uk_prof_name (name)
) ENGINE=InnoDB;

CREATE TABLE s13_ex_course (
  course_code  VARCHAR(10) NOT NULL,
  course_title VARCHAR(50) NOT NULL,
  professor_id INT UNSIGNED NOT NULL,        -- course 에만 종속 → 여기가 제자리
  PRIMARY KEY (course_code),
  KEY idx_course_prof (professor_id),
  CONSTRAINT fk_course_prof FOREIGN KEY (professor_id)
    REFERENCES s13_ex_professor(professor_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE s13_ex_enroll (
  enroll_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  student_id  INT UNSIGNED NOT NULL,
  course_code VARCHAR(10) NOT NULL,
  score       INT NULL,                      -- 오직 (학생, 과목) 조합에만 종속 → 여기가 제자리
  PRIMARY KEY (enroll_id),
  UNIQUE KEY uk_enroll (student_id, course_code),   -- 같은 학생이 같은 과목을 두 번 수강 불가
  KEY idx_enroll_course (course_code),
  CONSTRAINT fk_enroll_student FOREIGN KEY (student_id)
    REFERENCES s13_ex_student(student_id) ON DELETE CASCADE,
  CONSTRAINT fk_enroll_course FOREIGN KEY (course_code)
    REFERENCES s13_ex_course(course_code) ON DELETE RESTRICT,
  CONSTRAINT chk_enroll_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
) ENGINE=InnoDB;

-- 이제:
--   * 교수 전화번호 변경 → s13_ex_professor 의 1행만 UPDATE
--   * 수강생 없는 과목도 등록 가능
--   * 수강 취소해도 과목/교수 정보는 남음
--
-- 보너스: uk_enroll(student_id, course_code) 덕분에 중복 수강도 DB 가 막아줍니다.
--         "제약으로 막을 수 있는 것은 애플리케이션에 맡기지 말라"는 원칙의 좋은 예입니다.


-- ---------------------------------------------------------------------
-- [정답 8] 반정규화 컬럼의 동기화 검증
-- ---------------------------------------------------------------------

-- (8-1) JOIN + HAVING 버전
--   ⚠️ STORED 는 MySQL 8 예약어라 별칭으로 쓸 수 없습니다 (stored_amount 로 우회)
SELECT o.order_id,
       o.total_amount                   AS stored_amount,
       SUM(oi.quantity * oi.unit_price) AS calculated_amount
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY o.order_id, o.total_amount
HAVING stored_amount <> calculated_amount;
-- 결과: Empty set  → 현재 데이터는 정합합니다.

-- (8-2) 함정: 위 쿼리는 "order_items 가 한 건도 없는 주문"을 통째로 놓칩니다.
--       INNER JOIN 이므로 자식이 없는 주문은 결과에서 아예 사라지기 때문입니다.
--       그런 주문의 total_amount 가 0 이 아니라면 그것도 명백한 불일치인데,
--       (8-1) 쿼리는 그것을 영원히 발견하지 못합니다.

-- 고친 버전 A : 상관 서브쿼리 + COALESCE  (가장 명확)
SELECT o.order_id,
       o.total_amount AS stored_amount,
       COALESCE((SELECT SUM(oi.quantity * oi.unit_price)
                 FROM order_items oi WHERE oi.order_id = o.order_id), 0) AS calculated_amount
FROM orders o
WHERE o.total_amount <> COALESCE((SELECT SUM(oi.quantity * oi.unit_price)
                                  FROM order_items oi WHERE oi.order_id = o.order_id), 0);
-- 결과: Empty set

-- 고친 버전 B : LEFT JOIN + COALESCE
SELECT o.order_id,
       o.total_amount                                  AS stored_amount,
       COALESCE(SUM(oi.quantity * oi.unit_price), 0)   AS calculated_amount
FROM orders o
LEFT JOIN order_items oi ON oi.order_id = o.order_id     -- LEFT 로 자식 없는 주문도 살린다
GROUP BY o.order_id, o.total_amount
HAVING stored_amount <> calculated_amount;
-- 결과: Empty set

-- 참고: 지금 shop 에는 상세가 없는 주문이 0건이라 (8-1)과 (8-2)의 결과가 같습니다.
SELECT COUNT(*) AS orders_without_items
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.order_id);
-- +----------------------+
-- | orders_without_items |
-- +----------------------+
-- |                    0 |
-- +----------------------+
--
-- 하지만 운영 DB 에서는 "결제 실패로 상세가 롤백됐는데 헤더만 남은 주문" 같은 것이 반드시 생깁니다.
-- 정합성 검증 쿼리는 그런 "0건 케이스"를 잡으라고 있는 것입니다.
-- INNER JOIN 으로 짠 검증 배치는 정작 잡아야 할 사고를 못 잡습니다.
--
-- 💡 반정규화의 교훈:
--    total_amount 같은 반정규화 컬럼을 만들었다면, 위와 같은 정합성 검증 쿼리를
--    "야간 배치"로 매일 돌리세요. 반정규화는 성능을 빌려오고 정합성을 담보로 잡는 거래입니다.
--    담보를 확인하지 않으면 언젠가 반드시 청구서가 옵니다.


-- ---------------------------------------------------------------------
-- 정리
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s13_ex_enroll, s13_ex_course, s13_ex_professor, s13_ex_student,
                     s13_ex_coupons, s13_ex_sellers, s13_ex_items, s13_ex_bad, s13_ex_ai;

SELECT 'Step 13 solution 완료' AS msg;
