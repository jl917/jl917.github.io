-- =====================================================================
-- Step 22 — 사용자와 보안 : exercise.sql (연습문제)
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < exercise.sql
--   ※ root 로 실행. 마지막 [문제 6] 에서 반드시 정리하세요.
-- =====================================================================

-- 깨끗한 시작
DROP ROLE IF EXISTS s22_batch_role, s22_prod_role;
DROP USER IF EXISTS 's22_batch'@'%', 's22_temp'@'%';

-- ---------------------------------------------------------------------
-- [문제 1] 배치 계정 s22_batch@'%' 를 만들고,
--          롤 s22_batch_role 을 통해 orders/order_items 에만
--          SELECT/INSERT 권한을 부여하라. 기본 롤도 활성화하라.
-- ---------------------------------------------------------------------
-- TODO
-- CREATE USER 's22_batch'@'%' IDENTIFIED WITH caching_sha2_password BY 'Batch#123';
-- CREATE ROLE s22_batch_role;
-- GRANT ... ;
-- GRANT s22_batch_role TO 's22_batch'@'%';
-- SET DEFAULT ROLE ALL TO 's22_batch'@'%';

-- 확인
-- SHOW GRANTS FOR 's22_batch'@'%' USING s22_batch_role;


-- ---------------------------------------------------------------------
-- [문제 2] s22_batch 가 customers 를 SELECT 하면 어떤 에러가 나는가?
--          (셸에서 s22_batch 로 로그인해 확인. 에러 번호를 주석에 적어라)
-- 힌트: mysql -h127.0.0.1 -P3307 -us22_batch -pBatch#123 shop -e "SELECT * FROM customers LIMIT 1;"
-- ---------------------------------------------------------------------
-- 답: ERROR ____ (____) : ____


-- ---------------------------------------------------------------------
-- [문제 3] 롤 s22_prod_role 을 만들고, products 에서 cost(원가)를 제외한
--          모든 컬럼만 읽는 컬럼 레벨 SELECT 권한을 부여하라.
-- 힌트: 컬럼: product_id, category_id, name, price, cost, stock, status, attrs, created_at
--       cost 만 빼고 GRANT SELECT (...) ON shop.products TO ...
-- ---------------------------------------------------------------------
-- TODO
-- CREATE ROLE s22_prod_role;
-- GRANT SELECT (...) ON shop.products TO s22_prod_role;
-- SHOW GRANTS FOR s22_prod_role;


-- ---------------------------------------------------------------------
-- [문제 4] s22_temp@'%' 를 만들되:
--          - 연속 실패 5회 시 2일 잠금
--          - 생성 즉시 비밀번호 만료(다음 로그인 때 변경 강제)
-- ---------------------------------------------------------------------
-- TODO
-- CREATE USER 's22_temp'@'%' IDENTIFIED BY 'Temp#123' ... ;
-- ALTER USER ... PASSWORD EXPIRE;
-- SELECT user, JSON_EXTRACT(User_attributes,'$.Password_locking') AS p, password_expired
--   FROM mysql.user WHERE user='s22_temp';


-- ---------------------------------------------------------------------
-- [문제 5] s22_batch_role 에서 INSERT 권한만 회수하고 남은 권한을 확인하라.
-- ---------------------------------------------------------------------
-- TODO
-- REVOKE INSERT ON ... FROM s22_batch_role;
-- SHOW GRANTS FOR s22_batch_role;


-- ---------------------------------------------------------------------
-- [문제 6] 실습에서 만든 계정/롤을 전부 삭제하라. (반드시 실행!)
-- ---------------------------------------------------------------------
-- TODO
-- DROP USER IF EXISTS ... ;
-- DROP ROLE IF EXISTS ... ;
