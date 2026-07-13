-- =====================================================================
-- Step 16 — EXPLAIN 과 옵티마이저 : solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- ⚠️ access_logs 에 만든 인덱스/히스토그램은 각 문제 끝에서 정리합니다.
-- =====================================================================
USE shop;


-- ---------------------------------------------------------------------
-- [정답 1] 이 EXPLAIN, 무엇이 문제인가
-- ---------------------------------------------------------------------
EXPLAIN SELECT * FROM access_logs WHERE DATE(logged_at) = '2024-06-15';
-- 결과: type: ALL, key: NULL, rows ~996151, Using where
--
-- (1-1) 진단:
--   * type: ALL + key: NULL → 인덱스를 전혀 못 쓰고 전 행을 스캔합니다.
--   * 설령 logged_at 에 인덱스가 있어도 DATE(logged_at) 처럼 컬럼에 함수를 씌우면 못 탑니다(Step 15).
-- (1-2) 100만 행에서 위험한 이유:
--   * 매 실행마다 100만 행을 읽습니다. 이 쿼리가 초당 수십 번 실행되면 그때마다 풀스캔이 겹쳐
--     디스크/CPU 를 소진하고, 버퍼 풀을 이 테이블로 가득 채워 다른 쿼리까지 느려집니다.
--   * 고침: 함수를 없애고 sargable 범위로.
--     WHERE logged_at >= '2024-06-15' AND logged_at < '2024-06-16'
--     (그리고 logged_at 에 인덱스를 만든다)


-- ---------------------------------------------------------------------
-- [정답 2] type 등급 서열
-- ---------------------------------------------------------------------
-- 좋은 것 → 나쁜 것:
--   const  >  eq_ref  >  ref  >  range  >  index  >  ALL
--
-- "index 와 ALL 중 무엇이 더 나쁠 수 있는가":
--   보통은 ALL(테이블 풀스캔)이 나쁘지만, 둘 다 "전수조사"라는 점이 핵심입니다.
--   index 는 인덱스 전체를 훑는 것이라 커버링이면 테이블 접근이 없어 ALL 보다 나을 때가 많습니다.
--   그러나 "index 니까 인덱스를 잘 탔다"는 착각이 더 위험합니다 — 실제로는 seek 가 아닌 풀스캔이니까요.
--   결론: type 은 반드시 rows 와 함께 보고, index/ALL 이 대용량에서 나오면 둘 다 경보로 취급합니다.


-- ---------------------------------------------------------------------
-- [정답 3] filesort + temporary 없애기
-- ---------------------------------------------------------------------
-- (3-1) 인덱스 전: ALL + Using temporary + Using filesort
EXPLAIN SELECT customer_id, COUNT(*) c FROM access_logs
WHERE customer_id BETWEEN 1 AND 5 GROUP BY customer_id ORDER BY customer_id;

-- (3-2) customer_id 인덱스 하나면 충분
ALTER TABLE access_logs ADD INDEX idx_ex3 (customer_id);
EXPLAIN SELECT customer_id, COUNT(*) c FROM access_logs
WHERE customer_id BETWEEN 1 AND 5 GROUP BY customer_id ORDER BY customer_id;
-- 결과: type: range, Extra: "Using where; Using index" — temporary/filesort 둘 다 사라짐!

-- (3-3) 해설:
--   * 인덱스가 customer_id 순으로 정렬돼 있어 GROUP BY customer_id 를 임시테이블 없이 처리
--     ("정렬된 입력을 순서대로 읽으며 그룹 경계를 만난다" → tight/loose index scan).
--   * 같은 정렬 순서라 ORDER BY customer_id 도 추가 정렬(filesort)이 필요 없습니다.
--   * COUNT(*) 만 필요하므로 인덱스만 읽으면 됨(Using index, 커버링).
ALTER TABLE access_logs DROP INDEX idx_ex3;


-- ---------------------------------------------------------------------
-- [정답 4] 추정 vs 실측 괴리 찾기
-- ---------------------------------------------------------------------
ALTER TABLE access_logs ADD INDEX idx_ex4 (customer_id);

EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 3;
-- EXPLAIN 의 rows: 약 66756 (추정)

EXPLAIN ANALYZE SELECT COUNT(*) FROM access_logs WHERE customer_id = 3;
-- actual rows: 33333 (실측)
--
-- (4-1)(4-2) 해설:
--   * 추정(≈66756) vs 실측(33333) → 약 2배 차이.
--   * 왜? 인덱스 카디널리티 통계는 "샘플링"으로 추정합니다. customer_id 는 30종이 고르게 분포하므로
--     "100만 / 30 ≈ 33333" 이 실제인데, 통계 추정은 그보다 크게 잡았습니다.
--   * 이 정도 오차로는 계획이 안 바뀌지만, 오차가 크면(예: 10배) 옵티마이저가
--     "인덱스 대신 풀스캔"처럼 잘못된 선택을 합니다. 그때 ANALYZE TABLE / 히스토그램으로 바로잡습니다.
--   * 교훈: EXPLAIN 의 rows 는 "추정"이다. 진짜 값은 EXPLAIN ANALYZE 의 actual rows 로 본다.
ALTER TABLE access_logs DROP INDEX idx_ex4;


-- ---------------------------------------------------------------------
-- [정답 5] 옵티마이저 힌트로 계획 제어
-- ---------------------------------------------------------------------
ALTER TABLE access_logs ADD INDEX idx_ex5 (customer_id);

-- (5-1) 평소엔 인덱스 사용: type ref
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 1;

-- (5-2) 옵티마이저 힌트로 차단 → type ALL
EXPLAIN SELECT /*+ NO_INDEX(access_logs idx_ex5) */ COUNT(*)
FROM access_logs WHERE customer_id = 1;

-- (5-3) 인덱스 힌트로도 동일하게
EXPLAIN SELECT COUNT(*) FROM access_logs IGNORE INDEX (idx_ex5) WHERE customer_id = 1;
--
-- 해설: 둘 다 인덱스를 못 쓰게 만들어 풀스캔으로 되돌립니다.
--   옵티마이저 힌트(/*+ ... */)는 8.0 권장 방식이고 더 세밀합니다.
--   인덱스 힌트(USE/FORCE/IGNORE INDEX)는 전통 방식입니다.
--   ⚠️ 실무에서 이런 강제는 최후의 수단 — 데이터가 커지면 강제된 계획이 발목을 잡습니다.
ALTER TABLE access_logs DROP INDEX idx_ex5;


-- ---------------------------------------------------------------------
-- [정답 6] 히스토그램으로 filtered 개선
-- ---------------------------------------------------------------------
-- (6-1) 히스토그램 없이: filtered 25.00 (ENUM 4종이라 막연히 1/4 로 추정)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE method = 'PUT';

-- (6-2) 히스토그램 생성 후: filtered 10.00 (실제 분포 반영)
ANALYZE TABLE access_logs UPDATE HISTOGRAM ON method;
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE method = 'PUT';

-- (6-3) 검증: 실제 PUT 비율
SELECT ROUND(SUM(method='PUT') / COUNT(*) * 100, 2) AS put_pct FROM access_logs;   -- 10.00
--
-- 해설:
--   * method 는 GET/POST/PUT/DELETE 4종 ENUM 이라, 히스토그램 없으면 옵티마이저가 "1/4 = 25%" 로 추정.
--   * 실제 PUT 은 10% 뿐. 히스토그램이 이 편향을 저장해 filtered 를 25 → 10 으로 정확히 잡았습니다.
--   * 주의: 히스토그램은 "추정"만 개선합니다. method='PUT' 조회 자체는 여전히 풀스캔입니다(인덱스가 아님).
--     이 정확한 추정은 조인 순서 등 "더 나은 계획"을 세우는 데 쓰입니다.
ANALYZE TABLE access_logs DROP HISTOGRAM ON method;


-- ---------------------------------------------------------------------
-- [정답 7] 종합 튜닝
-- ---------------------------------------------------------------------
-- (7-1) 인덱스 전: 풀스캔 + filesort, actual time ~211ms
EXPLAIN ANALYZE SELECT log_id, path, duration_ms, logged_at FROM access_logs
WHERE method = 'GET' AND duration_ms >= 2900 ORDER BY logged_at DESC LIMIT 20;

-- (7-2) 인덱스 설계:
--   등치 조건 method → 정렬 컬럼 logged_at → 범위 조건 duration_ms  순서.
--   → (method, logged_at, duration_ms)
--   이유:
--     * method 등치를 선두에 두어 GET 구간으로 좁힌다.
--     * 그 안에서 logged_at 이 정렬돼 있으므로 ORDER BY logged_at DESC 를 filesort 없이
--       역방향 인덱스 스캔으로 처리한다.
--     * duration_ms 는 범위 조건이라 정렬 컬럼 뒤에 둔다(범위를 정렬 앞에 두면 정렬이 깨진다).
ALTER TABLE access_logs ADD INDEX idx_q7 (method, logged_at, duration_ms);

-- (7-3) 인덱스 후: Index lookup (reverse), filesort 제거, actual time ~0.4ms
EXPLAIN ANALYZE SELECT log_id, path, duration_ms, logged_at FROM access_logs
WHERE method = 'GET' AND duration_ms >= 2900 ORDER BY logged_at DESC LIMIT 20;
--   211ms → 0.4ms. LIMIT 20 이라 인덱스를 뒤에서부터 조금만 읽고 20건을 채우면 끝납니다.
--
--   참고: path 는 인덱스에 없어 커버링은 아니지만, 최종 20건에 대해서만 테이블을 읽으므로 충분히 빠릅니다.
--   커버링까지 원하면 (method, logged_at, duration_ms, path, log_id) 로 넓힐 수 있지만
--   인덱스가 커지므로 "20건 테이블 접근"과 저울질해야 합니다.

-- (7-4) 정리
ALTER TABLE access_logs DROP INDEX idx_q7;

SELECT 'Step 16 solution 완료' AS msg;
