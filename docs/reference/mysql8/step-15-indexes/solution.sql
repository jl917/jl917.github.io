-- =====================================================================
-- Step 15 — 인덱스 : solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- ⚠️ access_logs 에 만든 인덱스는 각 문제 끝에서 DROP 합니다.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [정답 1] 느린 쿼리에 맞는 인덱스 설계
-- ---------------------------------------------------------------------
-- 조건: customer_id = ? (등치), method = ? (등치), ORDER BY logged_at DESC
-- 규칙: 등치 컬럼 → 정렬 컬럼 순서. 등치가 둘이면 순서는 무관하지만 정렬 컬럼은 반드시 뒤.
ALTER TABLE access_logs ADD INDEX idx_q1 (customer_id, method, logged_at);

EXPLAIN SELECT log_id, status_code, duration_ms FROM access_logs
WHERE customer_id = 15 AND method = 'POST' ORDER BY logged_at DESC LIMIT 20;
-- 결과: type: ref, key: idx_q1, Extra: "Using where; Backward index scan"
--   → filesort 없음! (customer_id, method) 로 좁힌 구간이 logged_at 로 정렬돼 있어
--     DESC 는 뒤에서부터 읽으면 됨(Backward index scan).
--
-- 해설:
--   * status_code, duration_ms 는 SELECT 목록에만 있고 조건/정렬에 없으므로 인덱스에 넣지 않아도 됨.
--     (넣으면 커버링까지 되지만 인덱스가 커짐. LIMIT 20 이라 테이블 접근 20번은 저렴하므로 굳이 불필요.)
--   * 순서를 (logged_at, customer_id, method) 로 하면? 선두가 정렬 컬럼이라
--     customer_id 등치 조건이 인덱스 탐색을 못 해서 훨씬 나쁨.
ALTER TABLE access_logs DROP INDEX idx_q1;


-- ---------------------------------------------------------------------
-- [정답 2] 이 복합 인덱스는 어떤 쿼리를 커버하나
-- ---------------------------------------------------------------------
ALTER TABLE access_logs ADD INDEX idx_test (customer_id, status_code, logged_at);

-- (a) WHERE customer_id = 7            → 탄다 (ref). 선두 컬럼 등치
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;

-- (b) customer_id = 7 AND status_code = 200 → 탄다 (ref, key_len 6). 앞 두 컬럼 등치
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7 AND status_code = 200;

-- (c) WHERE status_code = 200         → 못 탄다 (type: index = 인덱스 풀스캔).
--     선두 컬럼(customer_id)이 조건에 없어 탐색 불가.
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE status_code = 200;

-- (d) customer_id = 7 AND logged_at >= '2024-06-01' → 탄다 (range).
--     중간 컬럼(status_code)을 건너뛰므로 8.0 스킵 스캔이 관여
--     (Extra: Using index for skip scan). customer_id 등치 + logged_at 범위.
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7 AND logged_at >= '2024-06-01';

-- (e) 세 컬럼 모두 → 탄다 (range). 앞 두 등치 + 마지막 범위. 인덱스를 가장 잘 활용.
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE customer_id = 7 AND status_code = 200 AND logged_at >= '2024-06-01';

-- 정리: (a)O (b)O (c)X (d)O(스킵스캔) (e)O
--   핵심은 (c). "인덱스의 두 번째/세 번째 컬럼만으로는 탐색할 수 없다" = 선두 컬럼 규칙.
ALTER TABLE access_logs DROP INDEX idx_test;


-- ---------------------------------------------------------------------
-- [정답 3] 커버링 인덱스로 Using index 만들기
-- ---------------------------------------------------------------------
-- 쿼리가 참조하는 컬럼: customer_id(조건+GROUP), method(GROUP+SELECT). 딱 두 개.
-- 두 컬럼을 다 담는 인덱스 → 테이블 접근 없이 처리 → Using index
ALTER TABLE access_logs ADD INDEX idx_q3 (customer_id, method);

EXPLAIN SELECT customer_id, method, COUNT(*)
FROM access_logs WHERE customer_id = 3 GROUP BY customer_id, method;
-- 결과: type: ref, key: idx_q3, Extra: Using index
--   → customer_id=3 구간 안에서 method 가 이미 정렬돼 있어 GROUP BY 도 인덱스로 처리.
--     COUNT(*) 는 인덱스 항목만 세면 되므로 테이블을 안 읽음.
ALTER TABLE access_logs DROP INDEX idx_q3;


-- ---------------------------------------------------------------------
-- [정답 4] 인덱스를 못 타는 쿼리 고치기 (sargable 화)
-- ---------------------------------------------------------------------
ALTER TABLE access_logs ADD INDEX idx_time (logged_at);

-- (a) YEAR(logged_at) = 2024  → 함수 때문에 못 탐 (type: index)
--     고침: 컬럼에 손대지 말고 범위로
EXPLAIN SELECT COUNT(*) FROM access_logs                        -- BEFORE: type index
WHERE YEAR(logged_at) = 2024;
EXPLAIN SELECT COUNT(*) FROM access_logs                        -- AFTER: type range
WHERE logged_at >= '2024-01-01' AND logged_at < '2025-01-01';

-- (b) logged_at + INTERVAL 0 DAY >= '2024-06-01'  → 컬럼에 연산 → 못 탐
--     고침: 연산을 없앤다
EXPLAIN SELECT COUNT(*) FROM access_logs                        -- AFTER
WHERE logged_at >= '2024-06-01';

-- (c) DATE(logged_at) = '2024-06-15'  → 함수 → 못 탐
--     고침: 그날 00:00 이상 ~ 다음날 00:00 미만 범위로
EXPLAIN SELECT COUNT(*) FROM access_logs                        -- AFTER: type range
WHERE logged_at >= '2024-06-15' AND logged_at < '2024-06-16';

-- 교훈: "컬럼을 가공하지 말고, 비교 대상(리터럴) 쪽을 가공하라."
--   YEAR(col)=2024  대신  col >= '2024-01-01' AND col < '2025-01-01'
--   이것이 sargable(Search ARGument ABLE) 쿼리의 핵심입니다.
ALTER TABLE access_logs DROP INDEX idx_time;


-- ---------------------------------------------------------------------
-- [정답 5] 선택도로 복합 인덱스 컬럼 순서 정하기
-- ---------------------------------------------------------------------
SELECT COUNT(DISTINCT customer_id) AS cust_card,   -- 30
       COUNT(DISTINCT path)        AS path_card    -- 8
FROM access_logs;

-- 답: (customer_id, path) 가 낫다.
--   둘 다 등치 조건일 때는 "카디널리티가 높은(값이 더 다양한) 컬럼을 선두로" 둡니다.
--   customer_id(30종)로 먼저 좁히면 1/30 로 줄지만, path(8종)로 먼저 좁히면 1/8 밖에 못 줄입니다.
--   선두에서 더 많이 좁힐수록 뒤 컬럼이 훑을 범위가 작아져 유리합니다.
--
--   다만 실무에서는 카디널리티만으로 정하지 않습니다:
--     * "customer_id 만으로 조회하는 쿼리"도 많다면 (customer_id, path) 가 그 쿼리까지 커버(선두 컬럼 규칙).
--     * 반대로 "path 만으로 조회"가 잦다면 그쪽을 선두로 두거나 별도 인덱스를 고려.
--   → 컬럼 순서는 "카디널리티 + 실제 쿼리 패턴"을 함께 봅니다.


-- ---------------------------------------------------------------------
-- [정답 6] 인비저블 인덱스로 삭제 안전성 실험
-- ---------------------------------------------------------------------
ALTER TABLE access_logs ADD INDEX idx_dur (duration_ms);

-- (6-1) 이 쿼리는 idx_dur 을 쓴다 (type: range, Using index)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE duration_ms BETWEEN 100 AND 110;

-- (6-2) 지우지 않고 숨긴다 → 옵티마이저가 못 봄 → 풀스캔(type: ALL)으로 퇴화
ALTER TABLE access_logs ALTER INDEX idx_dur INVISIBLE;
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE duration_ms BETWEEN 100 AND 110;
--   → type: ALL. "이 인덱스를 지우면 이 쿼리가 풀스캔이 되겠구나"를 실제 삭제 없이 확인.
--     성능이 나빠지면 아래처럼 즉시 되돌리면 됨(재생성보다 훨씬 빠름).

-- (6-3) 되돌린 뒤 정리
ALTER TABLE access_logs ALTER INDEX idx_dur VISIBLE;
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE duration_ms BETWEEN 100 AND 110;   -- 다시 range
ALTER TABLE access_logs DROP INDEX idx_dur;
--
-- 결론: 이 실험에서 idx_dur 은 숨기자마자 쿼리가 풀스캔이 됐으므로 "필요한 인덱스"입니다. 지우면 안 됩니다.


-- ---------------------------------------------------------------------
-- [정답 7] 프리픽스 인덱스 적정 길이 찾기
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s15_ex_sku;
CREATE TABLE s15_ex_sku (id INT AUTO_INCREMENT PRIMARY KEY, sku VARCHAR(40) NOT NULL) ENGINE=InnoDB;
INSERT INTO s15_ex_sku (sku)
SELECT CONCAT('PROD-2024-', LPAD(n, 8, '0')) FROM tally WHERE n <= 5000;

-- (7-1) 프리픽스 길이별 distinct
SELECT COUNT(DISTINCT sku)           AS full_distinct,   -- 5000
       COUNT(DISTINCT LEFT(sku,  8)) AS p8,              -- 1
       COUNT(DISTINCT LEFT(sku, 10)) AS p10,             -- 1
       COUNT(DISTINCT LEFT(sku, 12)) AS p12,             -- 1
       COUNT(DISTINCT LEFT(sku, 14)) AS p14,             -- 1
       COUNT(DISTINCT LEFT(sku, 16)) AS p16,             -- 51
       COUNT(DISTINCT LEFT(sku, 18)) AS p18              -- 5000
FROM s15_ex_sku;

-- (7-2) 결과 해석:
--   sku = 'PROD-2024-XXXXXXXX' 형태. 앞 'PROD-2024-' 가 10글자로 모두 공통입니다.
--   그래서 LEFT(sku, 10) 까지는 전부 같은 값(distinct = 1) → 프리픽스가 완전히 무력.
--   숫자 부분(8자리)이 앞에서부터 조금씩 갈라지므로 16글자에서 51종, 18글자에서야 5000종(=full).
--
--   → 이 sku 설계에서 유효한 프리픽스는 18글자인데, sku 전체가 18글자입니다.
--     즉 "프리픽스로 아낄 공간이 없습니다." 프리픽스 인덱스가 무의미한 대표적 경우입니다.
--
--   교훈: 프리픽스 인덱스는 "구별되는 정보가 문자열 앞쪽에 있을 때"만 효과적입니다.
--     공통 접두사(PROD-2024- 같은)가 길면, 그 뒤까지 잘라야 해서 프리픽스의 이점이 사라집니다.
--     이런 데이터라면 (a) 공통 접두사를 빼고 저장하거나, (b) 숫자부만 별도 컬럼으로 분리하는 게 낫습니다.
ALTER TABLE s15_ex_sku ADD INDEX idx_sku_prefix (sku(18));   -- 굳이 만든다면 18
DROP TABLE IF EXISTS s15_ex_sku;

SELECT 'Step 15 solution 완료' AS msg;
