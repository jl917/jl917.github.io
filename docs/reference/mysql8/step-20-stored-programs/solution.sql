-- =====================================================================
-- Step 20 — 저장 프로그램  solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- mysql 클라이언트로 실행하세요 (DELIMITER 사용).
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < solution.sql
-- =====================================================================
USE shop;

-- 사본이 없을 수 있으니 최소한으로 다시 만듭니다.
DROP TABLE IF EXISTS s20_order_items;
DROP TABLE IF EXISTS s20_audit;
DROP TABLE IF EXISTS s20_products;
CREATE TABLE s20_products (
  product_id INT PRIMARY KEY, name VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL, stock INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO s20_products SELECT product_id, name, price, stock FROM products WHERE product_id <= 10;
CREATE TABLE s20_audit (
  audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, ts DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  action VARCHAR(30) NOT NULL, detail VARCHAR(200) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE s20_order_items (
  item_id BIGINT AUTO_INCREMENT PRIMARY KEY, product_id INT NOT NULL,
  quantity INT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- [정답 1] 할인가 계산 함수
--
-- 해설: 입력이 같으면 결과가 항상 같으므로 DETERMINISTIC.
-- ---------------------------------------------------------------------
DROP FUNCTION IF EXISTS s20ex_discounted;
DELIMITER //
CREATE FUNCTION s20ex_discounted(p_price DECIMAL(10,2), p_pct DECIMAL(5,2))
  RETURNS INT DETERMINISTIC
BEGIN
  RETURN ROUND(p_price * (1 - p_pct/100));
END//
DELIMITER ;
SELECT s20ex_discounted(10000, 15) AS d1, s20ex_discounted(39000, 20) AS d2;

-- ---------------------------------------------------------------------
-- [정답 2] 등급별 배송비 (CASE)
--
-- 해설: 값 비교형 CASE. ELSE 로 나머지를 처리.
-- ---------------------------------------------------------------------
DROP FUNCTION IF EXISTS s20ex_shipping_fee;
DELIMITER //
CREATE FUNCTION s20ex_shipping_fee(p_grade VARCHAR(10))
  RETURNS INT DETERMINISTIC
BEGIN
  RETURN CASE p_grade
           WHEN 'VIP'  THEN 0
           WHEN 'GOLD' THEN 1500
           ELSE 3000
         END;
END//
DELIMITER ;
SELECT s20ex_shipping_fee('VIP') AS vip, s20ex_shipping_fee('GOLD') AS gold,
       s20ex_shipping_fee('BRONZE') AS bronze;

-- ---------------------------------------------------------------------
-- [정답 3] 재입고 프로시저 (OUT + SIGNAL + 감사로그)
--
-- 해설: p_qty 검증 후 UPDATE, 새 재고를 OUT 으로 반환.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS s20ex_restock;
DELIMITER //
CREATE PROCEDURE s20ex_restock(IN p_id INT, IN p_qty INT, OUT p_new_stock INT)
BEGIN
  IF p_qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '재입고 수량은 1 이상이어야 합니다';
  END IF;
  UPDATE s20_products SET stock = stock + p_qty WHERE product_id = p_id;
  IF ROW_COUNT() = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '존재하지 않는 상품입니다';
  END IF;
  SELECT stock INTO p_new_stock FROM s20_products WHERE product_id = p_id;
  INSERT INTO s20_audit(action, detail)
  VALUES ('RESTOCK', CONCAT('상품 ', p_id, ' +', p_qty, ' → ', p_new_stock));
END//
DELIMITER ;
CALL s20ex_restock(3, 50, @ns);
SELECT @ns AS new_stock_p3;

-- ---------------------------------------------------------------------
-- [정답 4] 커서로 저재고 목록
--
-- 해설: 표준 커서 패턴. DECLARE 순서(변수 → 커서 → 핸들러)를 지킵니다.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS s20ex_low_stock;
DELIMITER //
CREATE PROCEDURE s20ex_low_stock(IN p_threshold INT)
BEGIN
  DECLARE v_done INT DEFAULT 0;
  DECLARE v_id INT; DECLARE v_name VARCHAR(100); DECLARE v_stock INT;
  DECLARE v_out TEXT DEFAULT '';
  DECLARE cur CURSOR FOR
    SELECT product_id, name, stock FROM s20_products
    WHERE stock < p_threshold ORDER BY stock;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;
  OPEN cur;
  lp: LOOP
    FETCH cur INTO v_id, v_name, v_stock;
    IF v_done = 1 THEN LEAVE lp; END IF;
    SET v_out = CONCAT_WS(', ', NULLIF(v_out,''), CONCAT(v_id, ':', v_name, '(', v_stock, ')'));
  END LOOP;
  CLOSE cur;
  SELECT p_threshold AS threshold, v_out AS low_stock_list;
END//
DELIMITER ;
CALL s20ex_low_stock(30);

-- ---------------------------------------------------------------------
-- [정답 5] 주문 취소 트리거 (AFTER DELETE)
--
-- 해설: OLD 로 삭제된 행의 값을 참조해 재고를 복원합니다.
--   데모가 앞뒤로 맞아떨어지도록, 주문 시 재고를 차감하는 AFTER INSERT 트리거도
--   함께 만듭니다. 그러면 INSERT 로 -5, DELETE 로 +5 되어 원래 재고로 돌아옵니다.
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS s20ex_ai_order_items;
DROP TRIGGER IF EXISTS s20ex_ad_order_items;
DELIMITER //
CREATE TRIGGER s20ex_ai_order_items AFTER INSERT ON s20_order_items FOR EACH ROW
BEGIN
  UPDATE s20_products SET stock = stock - NEW.quantity WHERE product_id = NEW.product_id;
END//
CREATE TRIGGER s20ex_ad_order_items AFTER DELETE ON s20_order_items FOR EACH ROW
BEGIN
  UPDATE s20_products SET stock = stock + OLD.quantity WHERE product_id = OLD.product_id;
  INSERT INTO s20_audit(action, detail)
  VALUES ('CANCEL', CONCAT('상품 ', OLD.product_id, ' 수량 ', OLD.quantity, ' 취소 → 재고 복원'));
END//
DELIMITER ;

-- 확인: 넣으면 -5, 지우면 +5 → 원위치(200 → 195 → 200)
SELECT stock AS before_stock FROM s20_products WHERE product_id = 9;
INSERT INTO s20_order_items(product_id, quantity) VALUES (9, 5);
SELECT stock AS after_insert FROM s20_products WHERE product_id = 9;
DELETE FROM s20_order_items WHERE product_id = 9;
SELECT stock AS after_delete FROM s20_products WHERE product_id = 9;
SELECT action, detail FROM s20_audit WHERE action='CANCEL' ORDER BY audit_id DESC LIMIT 1;

-- ---------------------------------------------------------------------
-- [정답 6] 입력 검증 프로시저
--
-- 해설: 두 조건을 SIGNAL 로 각각 처리.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS s20ex_set_price;
DELIMITER //
CREATE PROCEDURE s20ex_set_price(IN p_id INT, IN p_price DECIMAL(10,2))
BEGIN
  IF p_price < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '가격은 음수가 될 수 없습니다';
  END IF;
  UPDATE s20_products SET price = p_price WHERE product_id = p_id;
  IF ROW_COUNT() = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '상품 없음';
  END IF;
  SELECT product_id, name, price FROM s20_products WHERE product_id = p_id;
END//
DELIMITER ;
CALL s20ex_set_price(1, 42000);
-- CALL s20ex_set_price(1, -100);   -- ERROR: 가격은 음수가 될 수 없습니다
-- CALL s20ex_set_price(999, 100);  -- ERROR: 상품 없음

-- ---------------------------------------------------------------------
-- 정리(cleanup) : 연습문제 객체
-- ---------------------------------------------------------------------
DROP FUNCTION IF EXISTS s20ex_discounted;
DROP FUNCTION IF EXISTS s20ex_shipping_fee;
DROP PROCEDURE IF EXISTS s20ex_restock;
DROP PROCEDURE IF EXISTS s20ex_low_stock;
DROP PROCEDURE IF EXISTS s20ex_set_price;
DROP TRIGGER IF EXISTS s20ex_ai_order_items;
DROP TRIGGER IF EXISTS s20ex_ad_order_items;
-- s20_ 공통 객체/테이블은 cleanup.sql 로 정리하세요.
