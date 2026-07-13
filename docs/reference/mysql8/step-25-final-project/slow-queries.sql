-- =====================================================================
-- Step 25 — 최종 프로젝트 : slow-queries.sql
-- ---------------------------------------------------------------------
-- Part 2(느린 쿼리 튜닝) 실습용 놀이터입니다.
-- 100만 행 s25_logs 를 만들어 "느린 버전"을 직접 돌려보고,
-- solutions.md 의 튜닝안과 EXPLAIN ANALYZE 로 비교하세요.
--
-- 실행: mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t < slow-queries.sql
-- =====================================================================
USE shop;

-- 놀이터 테이블 (access_logs 복제, 보조 인덱스 없음)
DROP TABLE IF EXISTS s25_logs;
CREATE TABLE s25_logs LIKE access_logs;
INSERT INTO s25_logs SELECT * FROM access_logs;
ANALYZE TABLE s25_logs;

-- ---------------------------------------------------------------------
-- 느린 쿼리 #1 : 날짜 컬럼을 함수로 감쌌다 (인덱스가 있어도 못 쓴다)
--   "2024-06-15 하루치 접근 로그 수"
-- ---------------------------------------------------------------------
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s25_logs WHERE DATE(logged_at) = '2024-06-15';

-- ---------------------------------------------------------------------
-- 느린 쿼리 #2 : 앞쪽 와일드카드 LIKE (인덱스 사용 불가 + 낮은 선택도)
--   "path 에 'detail' 이 들어간 로그 수"
-- ---------------------------------------------------------------------
EXPLAIN ANALYZE
SELECT COUNT(*) FROM s25_logs WHERE path LIKE '%detail%';

-- ---------------------------------------------------------------------
-- 느린 쿼리 #3 : 상관 서브쿼리 (행마다 서브쿼리 반복 실행)
--   "고객별 주문 수 상위 5명"
-- ---------------------------------------------------------------------
EXPLAIN ANALYZE
SELECT c.name,
       (SELECT COUNT(*) FROM orders o
        WHERE o.customer_id = c.customer_id AND o.status <> 'CANCELLED') AS order_count
FROM customers c
ORDER BY order_count DESC
LIMIT 5;

-- 튜닝 후 비교는 solutions.md 참고. 끝나면 정리:
-- DROP TABLE IF EXISTS s25_logs;
