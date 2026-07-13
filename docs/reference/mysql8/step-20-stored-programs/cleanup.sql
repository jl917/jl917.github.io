-- =====================================================================
-- Step 20 — 저장 프로그램  cleanup.sql
-- ---------------------------------------------------------------------
-- 실습으로 만든 s20_ 객체를 모두 삭제합니다.
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < cleanup.sql
-- =====================================================================
USE shop;

-- 이벤트
DROP EVENT IF EXISTS s20_heartbeat;
DROP EVENT IF EXISTS s20_once;

-- 트리거
DROP TRIGGER IF EXISTS s20_bi_order_items;
DROP TRIGGER IF EXISTS s20_ai_order_items;
DROP TRIGGER IF EXISTS s20_bu_products;

-- 프로시저
DROP PROCEDURE IF EXISTS s20_price_stats;
DROP PROCEDURE IF EXISTS s20_add_bonus;
DROP PROCEDURE IF EXISTS s20_fill_numbers;
DROP PROCEDURE IF EXISTS s20_stock_report;
DROP PROCEDURE IF EXISTS s20_ship;
DROP PROCEDURE IF EXISTS s20_ship_safe;

-- 함수
DROP FUNCTION IF EXISTS s20_margin_rate;
DROP FUNCTION IF EXISTS s20_stock_of;
DROP FUNCTION IF EXISTS s20_stock_grade;
DROP FUNCTION IF EXISTS s20_factorial;
DROP FUNCTION IF EXISTS s20_bad;

-- 테이블
DROP TABLE IF EXISTS s20_order_items;
DROP TABLE IF EXISTS s20_audit;
DROP TABLE IF EXISTS s20_products;

SELECT 's20 cleanup 완료' AS msg;
