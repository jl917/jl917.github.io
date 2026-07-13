-- =====================================================================
-- Step 16 — EXPLAIN 과 옵티마이저 : practice.sql
-- ---------------------------------------------------------------------
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--   * EXPLAIN ANALYZE / FORMAT=TREE 는 대화형 클라이언트에서 봐야 여러 줄이 예쁘게 나옵니다.
--     (배치 실행 시 줄바꿈이 \n 으로 보일 수 있습니다)
--
-- ⚠️ access_logs 에 인덱스/히스토그램을 만들지만 마지막에 전부 정리합니다.
--    다른 공용 테이블에는 인덱스를 만들거나 지우지 마세요.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [16-2] type — 접근 방식 등급 (system > const > eq_ref > ref > range > index > ALL)
-- ---------------------------------------------------------------------
-- const : PK 등치
EXPLAIN SELECT * FROM customers WHERE customer_id = 1;

-- eq_ref : 조인에서 상대의 PK 를 1:1 매칭
EXPLAIN SELECT o.order_id, c.name
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_id < 10;

-- ref : 비유니크 인덱스 등치
EXPLAIN SELECT * FROM orders WHERE customer_id = 1;

-- range : 범위
EXPLAIN SELECT * FROM orders WHERE order_id BETWEEN 1 AND 50;

-- index : 인덱스 풀스캔 (사실상 전수조사!)
EXPLAIN SELECT customer_id FROM orders;

-- ALL : 테이블 풀스캔 (작은 테이블에선 정상 — orders 는 600행뿐)
EXPLAIN SELECT * FROM orders WHERE shipping_city = '서울';


-- ---------------------------------------------------------------------
-- [16-3] Extra — Using filesort / Using temporary
-- ---------------------------------------------------------------------
-- GROUP BY(임시테이블) + ORDER BY 집계값(정렬) → 둘 다 등장
EXPLAIN SELECT shipping_city, COUNT(*) c FROM orders GROUP BY shipping_city ORDER BY c DESC;


-- ---------------------------------------------------------------------
-- [16-5] EXPLAIN ANALYZE — 추정이 아닌 실측 (8.0.18+)
-- ---------------------------------------------------------------------
-- 인덱스 없이: Table scan → 느림 (actual time 확인)
EXPLAIN ANALYZE SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;

ALTER TABLE access_logs ADD INDEX idx_customer (customer_id);

-- 인덱스 후: Covering index lookup → 빠름
EXPLAIN ANALYZE SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;


-- ---------------------------------------------------------------------
-- [16-6] FORMAT=TREE / JSON
-- ---------------------------------------------------------------------
EXPLAIN FORMAT=TREE
SELECT c.grade, COUNT(*) FROM orders o JOIN customers c ON c.customer_id = o.customer_id
GROUP BY c.grade;

EXPLAIN FORMAT=JSON SELECT * FROM orders WHERE customer_id = 1;


-- ---------------------------------------------------------------------
-- [16-7] 옵티마이저 제어 — 힌트와 스위치
-- ---------------------------------------------------------------------
-- 옵티마이저 힌트: 인덱스 사용 금지 → 풀스캔으로 강제
EXPLAIN SELECT /*+ NO_INDEX(access_logs idx_customer) */ COUNT(*)
FROM access_logs WHERE customer_id = 7;

-- 인덱스 힌트 (전통 방식)
EXPLAIN SELECT COUNT(*) FROM access_logs IGNORE INDEX (idx_customer) WHERE customer_id = 7;
EXPLAIN SELECT COUNT(*) FROM access_logs FORCE INDEX (idx_customer) WHERE customer_id = 7;

-- 옵티마이저 스위치 (대화형에서는 \G 로 세로 출력하면 읽기 좋다)
SELECT @@optimizer_switch;
SET SESSION optimizer_switch = 'skip_scan=off';
SET SESSION optimizer_switch = 'skip_scan=on';    -- 원복


-- ---------------------------------------------------------------------
-- [16-8] ANALYZE TABLE 과 히스토그램
-- ---------------------------------------------------------------------
ANALYZE TABLE access_logs;

-- 히스토그램 없이: filtered 10.00 (막연한 기본 추정)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE status_code = 500;

-- 히스토그램 생성
ANALYZE TABLE access_logs UPDATE HISTOGRAM ON status_code, method;

-- 히스토그램 후: filtered 4.99 (실제 분포 5% 반영)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE status_code = 500;

-- 히스토그램 조회
SELECT COLUMN_NAME,
       JSON_EXTRACT(HISTOGRAM, '$."histogram-type"') AS htype
FROM information_schema.COLUMN_STATISTICS
WHERE SCHEMA_NAME = 'shop' AND TABLE_NAME = 'access_logs';

-- 히스토그램 삭제
ANALYZE TABLE access_logs DROP HISTOGRAM ON status_code, method;


-- ---------------------------------------------------------------------
-- [16-9] 실전 튜닝 절차 — 미니 실습
-- ---------------------------------------------------------------------
-- 요구: "특정 고객의 5xx 에러 로그를 최근순으로 10건"
-- BEFORE: 풀스캔 + filesort
EXPLAIN SELECT log_id, status_code, logged_at FROM access_logs
WHERE customer_id = 3 AND status_code >= 500 ORDER BY logged_at DESC LIMIT 10;
EXPLAIN ANALYZE SELECT log_id, status_code, logged_at FROM access_logs
WHERE customer_id = 3 AND status_code >= 500 ORDER BY logged_at DESC LIMIT 10;

-- 처방: (customer_id, logged_at, status_code) — 등치→정렬→커버링
ALTER TABLE access_logs ADD INDEX idx_tune (customer_id, logged_at, status_code);

-- AFTER: 커버링 인덱스 역방향 조회, filesort 제거 (157ms → 0.05ms)
EXPLAIN ANALYZE SELECT log_id, status_code, logged_at FROM access_logs
WHERE customer_id = 3 AND status_code >= 500 ORDER BY logged_at DESC LIMIT 10;

ALTER TABLE access_logs DROP INDEX idx_tune;


-- ---------------------------------------------------------------------
-- 정리 — access_logs 를 원래 상태(PK만)로
-- ---------------------------------------------------------------------
ALTER TABLE access_logs DROP INDEX idx_customer;
SHOW INDEX FROM access_logs;    -- PRIMARY 만 남아야 정상

SELECT 'Step 16 practice 완료' AS msg;
