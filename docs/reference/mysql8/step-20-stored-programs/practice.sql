-- =====================================================================
-- Step 20 — 저장 프로그램 (Stored Programs)  practice.sql
-- ---------------------------------------------------------------------
-- 저장 프로시저/함수/트리거/이벤트를 다룹니다.
-- 반드시 mysql 클라이언트로 실행하세요 (DELIMITER 를 씁니다):
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
--
-- ★ 안전 규칙
--   모든 객체 이름과 테이블에 s20_ 접두사를 씁니다.
--   공용 테이블(products 등)은 절대 변경하지 않습니다.
--   맨 끝(또는 cleanup.sql)의 DROP 으로 전부 정리합니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [20-0] 실습용 사본 테이블
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s20_order_items;
DROP TABLE IF EXISTS s20_audit;
DROP TABLE IF EXISTS s20_products;

CREATE TABLE s20_products (
  product_id INT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  price      DECIMAL(10,2) NOT NULL,
  stock      INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO s20_products (product_id, name, price, stock)
SELECT product_id, name, price, stock FROM products WHERE product_id <= 10;

CREATE TABLE s20_audit (
  audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ts       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  action   VARCHAR(30) NOT NULL,
  detail   VARCHAR(200) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE s20_order_items (
  item_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id INT NOT NULL,
  quantity   INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SELECT product_id, name, stock FROM s20_products ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [20-1] 저장 함수 (Stored Function) — DETERMINISTIC / READS SQL DATA
-- ---------------------------------------------------------------------
-- 이 서버는 바이너리 로깅이 켜져 있고 log_bin_trust_function_creators=0 입니다.
-- 그래서 함수는 반드시 다음 중 하나를 선언해야 합니다:
--   DETERMINISTIC   : 같은 입력 → 항상 같은 출력 (예: 세금 계산)
--   NO SQL          : 본문에 SQL 이 없음
--   READS SQL DATA  : SELECT 만 함 (데이터를 안 바꿈)
-- 선언을 빠뜨리면 ERROR 1418 이 납니다.
DROP FUNCTION IF EXISTS s20_margin_rate;
DELIMITER //
CREATE FUNCTION s20_margin_rate(p_price DECIMAL(10,2), p_cost DECIMAL(10,2))
  RETURNS DECIMAL(5,2)
  DETERMINISTIC
BEGIN
  IF p_price = 0 THEN
    RETURN 0;
  END IF;
  RETURN ROUND((p_price - p_cost) / p_price * 100, 2);
END//
DELIMITER ;

SELECT product_id, name, price, s20_margin_rate(price, price*0.45) AS margin_pct
FROM s20_products WHERE product_id <= 3;

-- READS SQL DATA 함수 : 테이블을 읽는다
DROP FUNCTION IF EXISTS s20_stock_of;
DELIMITER //
CREATE FUNCTION s20_stock_of(p_id INT)
  RETURNS INT
  READS SQL DATA
BEGIN
  DECLARE v_stock INT;
  SELECT stock INTO v_stock FROM s20_products WHERE product_id = p_id;
  RETURN v_stock;   -- 없으면 NULL
END//
DELIMITER ;

SELECT s20_stock_of(1) AS stock_p1, s20_stock_of(999) AS stock_missing;

-- ---------------------------------------------------------------------
-- [20-2] 저장 프로시저 — IN / OUT / INOUT, DELIMITER
-- ---------------------------------------------------------------------
--   IN    : 입력 (기본)
--   OUT   : 출력 (프로시저가 값을 채워 돌려줌)
--   INOUT : 입출력
DROP PROCEDURE IF EXISTS s20_price_stats;
DELIMITER //
CREATE PROCEDURE s20_price_stats(
  IN  p_max_id INT,
  OUT p_cnt    INT,
  OUT p_avg    DECIMAL(12,2)
)
BEGIN
  SELECT COUNT(*), AVG(price) INTO p_cnt, p_avg
  FROM s20_products WHERE product_id <= p_max_id;
END//
DELIMITER ;

CALL s20_price_stats(5, @cnt, @avg);
SELECT @cnt AS cnt, @avg AS avg_price;

-- INOUT 예시 : 값을 받아 누적
DROP PROCEDURE IF EXISTS s20_add_bonus;
DELIMITER //
CREATE PROCEDURE s20_add_bonus(INOUT p_total INT, IN p_add INT)
BEGIN
  SET p_total = p_total + p_add;
END//
DELIMITER ;

SET @t = 100;
CALL s20_add_bonus(@t, 50);
CALL s20_add_bonus(@t, 25);
SELECT @t AS running_total;   -- 175

-- ---------------------------------------------------------------------
-- [20-3] 제어문 — IF / CASE / WHILE / LOOP / REPEAT
-- ---------------------------------------------------------------------
-- 재고 수준을 등급으로 분류하는 함수 (IF / CASE)
DROP FUNCTION IF EXISTS s20_stock_grade;
DELIMITER //
CREATE FUNCTION s20_stock_grade(p_stock INT)
  RETURNS VARCHAR(10)
  DETERMINISTIC
BEGIN
  DECLARE v_grade VARCHAR(10);
  CASE
    WHEN p_stock = 0            THEN SET v_grade = '품절';
    WHEN p_stock < 30           THEN SET v_grade = '부족';
    WHEN p_stock < 100          THEN SET v_grade = '보통';
    ELSE                             SET v_grade = '충분';
  END CASE;
  RETURN v_grade;
END//
DELIMITER ;

SELECT product_id, name, stock, s20_stock_grade(stock) AS grade
FROM s20_products ORDER BY product_id;

-- WHILE 로 tally 없이 숫자 채우기 (반복문 예시)
DROP PROCEDURE IF EXISTS s20_fill_numbers;
DELIMITER //
CREATE PROCEDURE s20_fill_numbers(IN p_n INT)
BEGIN
  DROP TEMPORARY TABLE IF EXISTS s20_nums;
  CREATE TEMPORARY TABLE s20_nums (n INT);
  SET @i = 1;
  WHILE @i <= p_n DO
    INSERT INTO s20_nums VALUES (@i);
    SET @i = @i + 1;
  END WHILE;
  SELECT GROUP_CONCAT(n ORDER BY n) AS numbers FROM s20_nums;
  DROP TEMPORARY TABLE s20_nums;
END//
DELIMITER ;
CALL s20_fill_numbers(10);

-- REPEAT (do-while 형) 와 LOOP + LEAVE 예시
DROP FUNCTION IF EXISTS s20_factorial;
DELIMITER //
CREATE FUNCTION s20_factorial(p_n INT)
  RETURNS BIGINT
  DETERMINISTIC
BEGIN
  DECLARE v_result BIGINT DEFAULT 1;
  DECLARE v_i INT DEFAULT 1;
  calc: LOOP
    IF v_i > p_n THEN
      LEAVE calc;             -- 루프 탈출
    END IF;
    SET v_result = v_result * v_i;
    SET v_i = v_i + 1;
  END LOOP calc;
  RETURN v_result;
END//
DELIMITER ;
SELECT s20_factorial(5) AS fact5, s20_factorial(10) AS fact10;

-- ---------------------------------------------------------------------
-- [20-4] 커서와 핸들러 — DECLARE ... CURSOR / CONTINUE HANDLER
-- ---------------------------------------------------------------------
-- 커서는 결과 행을 하나씩 훑습니다. NOT FOUND 핸들러로 끝을 감지합니다.
-- ★ 대부분의 경우 커서보다 집합 연산(단일 SQL)이 훨씬 빠릅니다.
--   커서는 "행마다 다른 절차가 필요한" 정말 어쩔 수 없는 경우에만 쓰세요.
DROP PROCEDURE IF EXISTS s20_stock_report;
DELIMITER //
CREATE PROCEDURE s20_stock_report()
BEGIN
  DECLARE v_done   INT DEFAULT 0;
  DECLARE v_id     INT;
  DECLARE v_name   VARCHAR(100);
  DECLARE v_stock  INT;
  DECLARE v_lines  INT DEFAULT 0;
  DECLARE v_msg    TEXT DEFAULT '';

  DECLARE cur CURSOR FOR
    SELECT product_id, name, stock FROM s20_products ORDER BY product_id;
  -- 커서가 끝에 도달하면 v_done = 1 로 설정하고 계속 진행
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_id, v_name, v_stock;
    IF v_done = 1 THEN
      LEAVE read_loop;
    END IF;
    SET v_lines = v_lines + 1;
    SET v_msg = CONCAT(v_msg, v_id, ':', v_name, '(', s20_stock_grade(v_stock), ') ');
  END LOOP;
  CLOSE cur;

  SELECT v_lines AS scanned, v_msg AS report;
END//
DELIMITER ;
CALL s20_stock_report();

-- ---------------------------------------------------------------------
-- [20-5] 에러 처리 — SIGNAL / RESIGNAL
-- ---------------------------------------------------------------------
-- SIGNAL 로 사용자 정의 에러를 던집니다 (SQLSTATE '45000' = 사용자 예외).
DROP PROCEDURE IF EXISTS s20_ship;
DELIMITER //
CREATE PROCEDURE s20_ship(IN p_id INT, IN p_qty INT)
BEGIN
  DECLARE v_stock INT;

  IF p_qty <= 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '수량은 1 이상이어야 합니다';
  END IF;

  SELECT stock INTO v_stock FROM s20_products WHERE product_id = p_id;
  IF v_stock IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '존재하지 않는 상품입니다';
  END IF;
  IF v_stock < p_qty THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '재고가 부족합니다';
  END IF;

  UPDATE s20_products SET stock = stock - p_qty WHERE product_id = p_id;
  SELECT CONCAT('출고 완료: 상품 ', p_id, ', 남은 재고 ', v_stock - p_qty) AS result;
END//
DELIMITER ;

CALL s20_ship(1, 10);         -- 정상
-- 아래는 각각 에러를 냅니다. (한 번에 하나씩 주석을 풀어 확인)
-- CALL s20_ship(1, 99999);   -- 재고 부족
-- CALL s20_ship(999, 1);     -- 없는 상품
-- CALL s20_ship(1, 0);       -- 수량 오류

-- 핸들러로 에러를 잡아 트랜잭션을 롤백하는 패턴 (EXIT HANDLER)
DROP PROCEDURE IF EXISTS s20_ship_safe;
DELIMITER //
CREATE PROCEDURE s20_ship_safe(IN p_id INT, IN p_qty INT, OUT p_ok INT, OUT p_msg VARCHAR(200))
BEGIN
  -- 어떤 SQL 예외든 잡으면 롤백하고 메시지를 담는다
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 p_msg = MESSAGE_TEXT;
    ROLLBACK;
    SET p_ok = 0;
  END;

  SET p_ok = 1; SET p_msg = 'OK';
  START TRANSACTION;
  CALL s20_ship(p_id, p_qty);   -- 내부에서 SIGNAL 나면 위 핸들러가 잡는다
  COMMIT;
END//
DELIMITER ;

CALL s20_ship_safe(2, 5, @ok, @msg);      SELECT @ok AS ok, @msg AS msg;   -- 성공
CALL s20_ship_safe(2, 99999, @ok, @msg);  SELECT @ok AS ok, @msg AS msg;   -- 실패(롤백)

-- ---------------------------------------------------------------------
-- [20-6] 트리거 — BEFORE/AFTER, OLD/NEW (재고 차감 & 감사로그)
-- ---------------------------------------------------------------------
--   NEW : INSERT/UPDATE 로 들어오는 새 값 (BEFORE 에서는 수정 가능)
--   OLD : UPDATE/DELETE 되는 기존 값
-- (1) BEFORE INSERT : 유효성 검사 (SIGNAL)
DROP TRIGGER IF EXISTS s20_bi_order_items;
DELIMITER //
CREATE TRIGGER s20_bi_order_items
BEFORE INSERT ON s20_order_items
FOR EACH ROW
BEGIN
  IF NEW.quantity <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '주문 수량은 1 이상이어야 합니다';
  END IF;
END//
DELIMITER ;

-- (2) AFTER INSERT : 재고 차감 + 감사로그
DROP TRIGGER IF EXISTS s20_ai_order_items;
DELIMITER //
CREATE TRIGGER s20_ai_order_items
AFTER INSERT ON s20_order_items
FOR EACH ROW
BEGIN
  UPDATE s20_products
     SET stock = stock - NEW.quantity
   WHERE product_id = NEW.product_id;
  INSERT INTO s20_audit(action, detail)
  VALUES ('ORDER', CONCAT('상품 ', NEW.product_id, ' 수량 ', NEW.quantity, ' 주문 → 재고 차감'));
END//
DELIMITER ;

-- (3) BEFORE UPDATE : 재고 변동 감사 (OLD vs NEW)
DROP TRIGGER IF EXISTS s20_bu_products;
DELIMITER //
CREATE TRIGGER s20_bu_products
BEFORE UPDATE ON s20_products
FOR EACH ROW
BEGIN
  IF NEW.stock < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '재고는 음수가 될 수 없습니다';
  END IF;
  IF OLD.stock <> NEW.stock THEN
    INSERT INTO s20_audit(action, detail)
    VALUES ('STOCK', CONCAT('상품 ', NEW.product_id, ' 재고 ', OLD.stock, ' → ', NEW.stock));
  END IF;
END//
DELIMITER ;

-- 트리거 동작 확인 : 주문 한 건 넣으면 재고 차감 + 감사로그 2줄(STOCK, ORDER)
SELECT stock AS before_stock FROM s20_products WHERE product_id = 5;
INSERT INTO s20_order_items(product_id, quantity) VALUES (5, 3);
SELECT stock AS after_stock FROM s20_products WHERE product_id = 5;
SELECT action, detail FROM s20_audit ORDER BY audit_id;

-- 트리거가 SIGNAL 로 막는지 확인 (음수 재고 방지)
-- INSERT INTO s20_order_items(product_id, quantity) VALUES (5, 0);       -- BEFORE INSERT 가 막음
-- UPDATE s20_products SET stock = -1 WHERE product_id = 5;               -- BEFORE UPDATE 가 막음

-- ---------------------------------------------------------------------
-- [20-7] 이벤트 스케줄러 (EVENT)
-- ---------------------------------------------------------------------
-- 이벤트는 "예약된 시각/주기에 자동 실행되는 SQL"입니다 (크론과 유사).
-- 전제: 전역 event_scheduler = ON 이어야 실제로 돕니다 (이 서버는 ON).
SELECT @@event_scheduler AS scheduler;

-- 1분마다 감사 테이블에 하트비트를 남기는 이벤트
DROP EVENT IF EXISTS s20_heartbeat;
DELIMITER //
CREATE EVENT s20_heartbeat
ON SCHEDULE EVERY 1 MINUTE
STARTS CURRENT_TIMESTAMP
DO
BEGIN
  INSERT INTO s20_audit(action, detail) VALUES ('HEARTBEAT', CONCAT('tick @', NOW()));
END//
DELIMITER ;

-- 한 번만 실행되는 이벤트 (AT)
DROP EVENT IF EXISTS s20_once;
CREATE EVENT s20_once
ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 5 SECOND
DO
  INSERT INTO s20_audit(action, detail) VALUES ('ONCE', '5초 뒤 1회 실행');

SHOW EVENTS WHERE Db = 'shop' AND Name LIKE 's20_%';

-- 실습에서는 이벤트가 계속 도는 걸 원치 않으므로 바로 비활성화/삭제합니다.
ALTER EVENT s20_heartbeat DISABLE;
-- (cleanup.sql 에서 DROP 합니다)

-- ---------------------------------------------------------------------
-- [20-8] 정의 조회
-- ---------------------------------------------------------------------
SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, IS_DETERMINISTIC
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = 'shop' AND ROUTINE_NAME LIKE 's20_%'
ORDER BY ROUTINE_TYPE, ROUTINE_NAME;

SELECT TRIGGER_NAME, EVENT_MANIPULATION, ACTION_TIMING, EVENT_OBJECT_TABLE
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = 'shop' AND TRIGGER_NAME LIKE 's20_%'
ORDER BY TRIGGER_NAME;

-- ---------------------------------------------------------------------
-- [20-9] 정리(cleanup) — cleanup.sql 참고
-- ---------------------------------------------------------------------
-- 아래는 cleanup.sql 에 모아 두었습니다. 실습 후 실행하세요.
