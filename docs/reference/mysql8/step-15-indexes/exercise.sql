-- =====================================================================
-- Step 15 — 인덱스 : exercise.sql  (문제 7개)
-- ---------------------------------------------------------------------
-- 정답은 solution.sql. EXPLAIN 을 직접 돌려 확인하세요.
--
-- ⚠️ access_logs 에 인덱스를 만들어도 되지만, 각 문제 끝에 반드시 DROP 하세요.
--    다른 공용 테이블에는 인덱스를 만들거나 지우지 마세요.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [문제 1] 느린 쿼리에 맞는 인덱스 설계
-- ---------------------------------------------------------------------
-- 다음 쿼리가 자주 실행됩니다. filesort/풀스캔 없이 처리되도록 인덱스 하나를 설계하세요.
--   SELECT log_id, status_code, duration_ms
--   FROM access_logs
--   WHERE customer_id = 15 AND method = 'POST'
--   ORDER BY logged_at DESC
--   LIMIT 20;
--
-- (1-1) 인덱스를 만들기 전 EXPLAIN 을 확인하세요.
-- (1-2) 인덱스를 만들고 EXPLAIN 이 어떻게 바뀌는지 확인하세요.
--       힌트: 등치 조건 컬럼 → 정렬 컬럼 순서로 배치.
-- (1-3) 확인 후 인덱스를 DROP 하세요.

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 2] 이 복합 인덱스는 어떤 쿼리를 커버하나
-- ---------------------------------------------------------------------
-- idx_test = (customer_id, status_code, logged_at) 를 만들었다고 합시다.
ALTER TABLE access_logs ADD INDEX idx_test (customer_id, status_code, logged_at);

-- 아래 (a)~(e) 각각이 idx_test 를 "탐색(seek)"에 쓸 수 있는지 예측하고,
-- EXPLAIN 으로 확인하세요. (type 이 ref/range 면 탐색, index/ALL 이면 못 탐)
--   (a) WHERE customer_id = 7
--   (b) WHERE customer_id = 7 AND status_code = 200
--   (c) WHERE status_code = 200
--   (d) WHERE customer_id = 7 AND logged_at >= '2024-06-01'
--   (e) WHERE customer_id = 7 AND status_code = 200 AND logged_at >= '2024-06-01'

-- 여기에 EXPLAIN 들 작성 후:
ALTER TABLE access_logs DROP INDEX idx_test;



-- ---------------------------------------------------------------------
-- [문제 3] 커버링 인덱스로 Using index 만들기
-- ---------------------------------------------------------------------
-- 다음 쿼리가 테이블을 아예 읽지 않도록(Using index) 인덱스를 설계하세요.
--   SELECT customer_id, method, COUNT(*)
--   FROM access_logs
--   WHERE customer_id = 3
--   GROUP BY customer_id, method;
--
-- 인덱스 전/후 EXPLAIN 을 비교하고, 확인 후 DROP 하세요.

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 4] 인덱스를 못 타는 쿼리 고치기
-- ---------------------------------------------------------------------
-- 아래 세 쿼리는 인덱스를 못 탑니다. sargable 하게(인덱스를 타도록) 고쳐 쓰세요.
-- (먼저 idx_time 을 만들고, 원본/수정본 EXPLAIN 을 비교하세요)
ALTER TABLE access_logs ADD INDEX idx_time (logged_at);

--   (a) SELECT COUNT(*) FROM access_logs WHERE YEAR(logged_at) = 2024;
--   (b) SELECT COUNT(*) FROM access_logs WHERE logged_at + INTERVAL 0 DAY >= '2024-06-01';
--   (c) SELECT COUNT(*) FROM access_logs WHERE DATE(logged_at) = '2024-06-15';

-- 여기에 수정본 작성 후:
ALTER TABLE access_logs DROP INDEX idx_time;



-- ---------------------------------------------------------------------
-- [문제 5] 선택도로 복합 인덱스 컬럼 순서 정하기
-- ---------------------------------------------------------------------
-- "특정 고객이 특정 경로에 남긴 로그"를 자주 조회합니다.
--   WHERE customer_id = ? AND path = ?
-- (customer_id, path) 와 (path, customer_id) 중 어느 순서가 나을까요?
--
-- (5-1) 두 컬럼의 카디널리티를 구하는 쿼리를 쓰세요.
-- (5-2) 어느 순서가 나은지 근거와 함께 주석으로 답하세요.
--       (둘 다 등치 조건일 때 선두 컬럼 선택 기준은 무엇일까요?)

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 6] 인비저블 인덱스로 삭제 안전성 실험
-- ---------------------------------------------------------------------
-- idx_dur = (duration_ms) 인덱스가 정말 필요한지 확인하려 합니다.
ALTER TABLE access_logs ADD INDEX idx_dur (duration_ms);

-- (6-1) 이 쿼리가 idx_dur 을 쓰는지 EXPLAIN 으로 확인:
--       SELECT COUNT(*) FROM access_logs WHERE duration_ms BETWEEN 100 AND 110;
-- (6-2) idx_dur 을 "지우지 않고" 숨겨서, 위 쿼리가 어떻게 바뀌는지 확인:
-- (6-3) 다시 보이게 되돌린 뒤 DROP:

-- 여기에 작성:



-- ---------------------------------------------------------------------
-- [문제 7] 프리픽스 인덱스 적정 길이 찾기
-- ---------------------------------------------------------------------
-- 아래 테이블의 sku 컬럼에 프리픽스 인덱스를 걸려고 합니다.
DROP TABLE IF EXISTS s15_ex_sku;
CREATE TABLE s15_ex_sku (id INT AUTO_INCREMENT PRIMARY KEY, sku VARCHAR(40) NOT NULL) ENGINE=InnoDB;
-- 앞부분이 'PROD-2024-' 로 공통, 뒤가 구별되는 형태
INSERT INTO s15_ex_sku (sku)
SELECT CONCAT('PROD-2024-', LPAD(n, 8, '0')) FROM tally WHERE n <= 5000;

-- (7-1) LEFT(sku, N) 의 distinct 수를 N=8,10,12,14,16 에 대해 구해서,
--       full distinct 에 근접하는 최소 N 을 찾으세요.
-- (7-2) 그 길이로 프리픽스 인덱스를 만들고, 이 sku 설계에서 왜 짧은 프리픽스가
--       쓸모없는지 주석으로 설명하세요.

-- 여기에 작성:

DROP TABLE IF EXISTS s15_ex_sku;


-- ---------------------------------------------------------------------
-- 안전 정리 — 이 파일을 통째로 실행했다면 access_logs 에 남은 실습 인덱스를 제거
--   (문제를 손으로 풀 때는 각 문제에서 이미 DROP 했을 수 있어 IF EXISTS 로 감쌉니다)
-- ---------------------------------------------------------------------
-- 이 파일이 setup 으로 만든 idx_dur 을 정리합니다(문제 6에서 직접 DROP 했다면 --force 로 넘어감).
ALTER TABLE access_logs DROP INDEX idx_dur;

-- 남은 인덱스가 있는지 최종 확인 — PRIMARY 만 남아야 정상입니다.
SELECT DISTINCT INDEX_NAME AS remaining_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'shop' AND TABLE_NAME = 'access_logs';
