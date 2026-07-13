-- =====================================================================
-- Step 11 — DML : 정리 스크립트
--   실습으로 만든 s11_ 사본 테이블을 모두 삭제합니다.
--   실습 도중 중단했거나 사본이 남아 있을 때 이 파일만 실행하세요.
--   실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < cleanup.sql
-- =====================================================================
USE shop;

DROP TABLE IF EXISTS s11_order_items;
DROP TABLE IF EXISTS s11_orders;
DROP TABLE IF EXISTS s11_products;
DROP TABLE IF EXISTS s11_customers;
DROP TABLE IF EXISTS s11_stock_feed;
DROP TABLE IF EXISTS s11_seq_demo;
DROP TABLE IF EXISTS s11_ignore_demo;
DROP TABLE IF EXISTS s11_trunc_demo;
DROP TABLE IF EXISTS s11_load_demo;

SELECT '정리 완료.' AS msg;
