-- =====================================================================
-- Step 22 — 사용자와 보안 : solution.sql (정답)
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < solution.sql
-- =====================================================================

-- 깨끗한 시작
DROP ROLE IF EXISTS s22_batch_role, s22_prod_role;
DROP USER IF EXISTS 's22_batch'@'%', 's22_temp'@'%';

-- ---------------------------------------------------------------------
-- [정답 1] 배치 계정 + 롤
-- ---------------------------------------------------------------------
CREATE USER 's22_batch'@'%' IDENTIFIED WITH caching_sha2_password BY 'Batch#123';
CREATE ROLE s22_batch_role;
GRANT SELECT, INSERT ON shop.orders      TO s22_batch_role;
GRANT SELECT, INSERT ON shop.order_items TO s22_batch_role;
GRANT s22_batch_role TO 's22_batch'@'%';
SET DEFAULT ROLE ALL TO 's22_batch'@'%';

SHOW GRANTS FOR 's22_batch'@'%' USING s22_batch_role;
-- GRANT USAGE ON *.* ...
-- GRANT SELECT, INSERT ON `shop`.`orders` ...
-- GRANT SELECT, INSERT ON `shop`.`order_items` ...
-- GRANT `s22_batch_role`@`%` ...

-- ---------------------------------------------------------------------
-- [정답 2] s22_batch 가 customers 를 읽으면:
--   ERROR 1142 (42000): SELECT command denied to user 's22_batch'@'...'
--                       for table 'customers'
--   (customers 에는 아무 권한도 주지 않았으므로 테이블 레벨 거부)
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- [정답 3] cost 를 제외한 컬럼 레벨 SELECT
-- ---------------------------------------------------------------------
CREATE ROLE s22_prod_role;
GRANT SELECT (product_id, category_id, name, price, stock, status, attrs, created_at)
  ON shop.products TO s22_prod_role;   -- cost 만 빠졌다
SHOW GRANTS FOR s22_prod_role;

-- ---------------------------------------------------------------------
-- [정답 4] 실패 5회 → 2일 잠금 + 즉시 만료
-- ---------------------------------------------------------------------
CREATE USER 's22_temp'@'%' IDENTIFIED BY 'Temp#123'
  FAILED_LOGIN_ATTEMPTS 5
  PASSWORD_LOCK_TIME 2;
ALTER USER 's22_temp'@'%' PASSWORD EXPIRE;

SELECT user,
       JSON_EXTRACT(User_attributes, '$.Password_locking') AS lock_policy,
       password_expired
FROM mysql.user WHERE user = 's22_temp';
-- lock_policy: {"failed_login_attempts": 5, "password_lock_time_days": 2}
-- password_expired: Y

-- ---------------------------------------------------------------------
-- [정답 5] 롤에서 INSERT 만 회수
-- ---------------------------------------------------------------------
REVOKE INSERT ON shop.orders      FROM s22_batch_role;
REVOKE INSERT ON shop.order_items FROM s22_batch_role;
SHOW GRANTS FOR s22_batch_role;
-- 이제 orders/order_items 에 SELECT 만 남는다.
-- ★ 롤에서 회수하면 s22_batch 유저에게도 즉시 반영된다(사람마다 REVOKE 불필요).

-- ---------------------------------------------------------------------
-- [정답 6] 정리
-- ---------------------------------------------------------------------
DROP USER IF EXISTS 's22_batch'@'%', 's22_temp'@'%';
DROP ROLE IF EXISTS s22_batch_role, s22_prod_role;

SELECT 'cleanup done' AS status,
       (SELECT COUNT(*) FROM mysql.user WHERE user LIKE 's22%') AS leftover;
