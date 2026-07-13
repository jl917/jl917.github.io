-- =====================================================================
-- Step 15 — 인덱스 : practice.sql
-- ---------------------------------------------------------------------
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
--   * EXPLAIN 은 계획만 보고, SELECT 는 실제 실행시간을 확인하세요.
--   * 실행시간을 직접 보려면 대화형 클라이언트에서 한 줄씩 실행하는 것을 권합니다.
--
-- ⚠️ 이 스텝은 access_logs 에 인덱스를 만들어도 됩니다. 마지막에 전부 DROP 합니다.
--    다른 공용 테이블에는 인덱스를 만들거나 지우지 마세요.
-- =====================================================================
USE shop;

-- [15-0] 시작 상태 확인 — PRIMARY 하나뿐
SHOW INDEX FROM access_logs;


-- ---------------------------------------------------------------------
-- [15-2] 첫 인덱스 — 전후 실행시간 실측
-- ---------------------------------------------------------------------
-- BEFORE: 풀스캔 (type: ALL, rows ~100만)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;          -- 약 0.076 sec

-- 인덱스 생성
ALTER TABLE access_logs ADD INDEX idx_customer (customer_id);    -- 약 0.688 sec (1회성)

-- AFTER: type: ref, Using index (커버링) → 약 25배 빠름
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;
SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;          -- 약 0.003 sec


-- ---------------------------------------------------------------------
-- [15-4] 카디널리티와 선택도
-- ---------------------------------------------------------------------
SELECT
  COUNT(*)                                       AS total,
  COUNT(DISTINCT customer_id)                    AS cust_card,
  COUNT(DISTINCT status_code)                    AS status_card,
  ROUND(COUNT(*) / COUNT(DISTINCT customer_id))  AS rows_per_cust,
  ROUND(COUNT(*) / COUNT(DISTINCT status_code))  AS rows_per_status
FROM access_logs;
-- customer_id 30종, status_code 6종 → 둘 다 선택도 낮음(값 하나가 수만~십수만 행)


-- ---------------------------------------------------------------------
-- [15-5] 복합 인덱스와 선두 컬럼 규칙
-- ---------------------------------------------------------------------
-- BEFORE: 풀스캔 + filesort
EXPLAIN SELECT log_id, path, logged_at FROM access_logs
WHERE customer_id = 7 ORDER BY logged_at DESC LIMIT 5;

ALTER TABLE access_logs ADD INDEX idx_cust_time (customer_id, logged_at);

-- AFTER: filesort 사라짐, Backward index scan → 약 0.001 sec
EXPLAIN SELECT log_id, path, logged_at FROM access_logs
WHERE customer_id = 7 ORDER BY logged_at DESC LIMIT 5;
SELECT log_id, path, logged_at FROM access_logs
WHERE customer_id = 7 ORDER BY logged_at DESC LIMIT 5;

-- 선두 컬럼 규칙: logged_at 단독 조건은 (customer_id, logged_at) 을 "탐색"하지 못한다
-- 스킵 스캔을 끄고 순수 동작 확인 → type: index (인덱스 풀스캔!)
SET SESSION optimizer_switch = 'skip_scan=off';
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-06-02';

-- 스킵 스캔 켜기(8.0 기본): 선두 컬럼 카디널리티가 낮으면 부분 활용 → Using index for skip scan
SET SESSION optimizer_switch = 'skip_scan=on';
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-06-02';


-- ---------------------------------------------------------------------
-- [15-6] 커버링 인덱스
-- ---------------------------------------------------------------------
-- 필요한 컬럼(customer_id, logged_at)이 전부 인덱스에 있음 → Using index (커버링)
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id = 7 AND logged_at >= '2024-06-01';

-- path 는 인덱스에 없음 → 테이블 접근 필요 (Using index 없음)
EXPLAIN SELECT customer_id, path FROM access_logs
WHERE customer_id = 7 AND logged_at >= '2024-06-01';


-- ---------------------------------------------------------------------
-- [15-7] 인덱스를 못 타는 패턴들
-- ---------------------------------------------------------------------
-- (1) 컬럼에 함수 → type: index (풀스캔)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE MONTH(logged_at) = 6;
-- 해결: 범위로 (sargable)
EXPLAIN SELECT COUNT(*) FROM access_logs
WHERE logged_at >= '2024-06-01' AND logged_at < '2024-07-01';

-- (2) 오해 풀기: 정수 컬럼 vs 문자열 리터럴 → 리터럴이 변환됨, 인덱스 정상 사용
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = '7';
-- 진짜 문제: 컬럼 쪽이 변환될 때 → 풀스캔
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE CAST(customer_id AS CHAR) = '7';

-- (3) 앞 % LIKE 못 탐 / 뒤 % LIKE 는 탐
ALTER TABLE access_logs ADD INDEX idx_path (path);
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '%detail';      -- type: index
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE path LIKE '/products%';   -- type: range

-- (4) OR 한쪽에 인덱스 없으면 풀스캔 (status_code 무인덱스)
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7 OR status_code = 500;
-- 해결: UNION 으로 쪼개면 각 SELECT 가 자기 인덱스를 탄다
EXPLAIN
SELECT log_id FROM access_logs WHERE customer_id = 7
UNION
SELECT log_id FROM access_logs WHERE path = '/cart';

ALTER TABLE access_logs DROP INDEX idx_path;


-- ---------------------------------------------------------------------
-- [15-8] 8.0 신기능 인덱스
-- ---------------------------------------------------------------------
-- 내림차순 인덱스: 혼합 정렬(ASC, DESC)의 filesort 제거
-- BEFORE: ASC 인덱스로는 filesort
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id BETWEEN 5 AND 8 ORDER BY customer_id ASC, logged_at DESC LIMIT 10;

ALTER TABLE access_logs ADD INDEX idx_mixed (customer_id ASC, logged_at DESC);
-- AFTER: filesort 사라짐
EXPLAIN SELECT customer_id, logged_at FROM access_logs
WHERE customer_id BETWEEN 5 AND 8 ORDER BY customer_id ASC, logged_at DESC LIMIT 10;
SHOW INDEX FROM access_logs WHERE Key_name = 'idx_mixed';   -- Collation 컬럼: A(ASC)/D(DESC)
ALTER TABLE access_logs DROP INDEX idx_mixed;

-- 인비저블 인덱스: 지우지 않고 옵티마이저에게만 숨김
ALTER TABLE access_logs ALTER INDEX idx_customer INVISIBLE;
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;   -- idx_customer 후보에서 빠짐
ALTER TABLE access_logs ALTER INDEX idx_customer VISIBLE;
EXPLAIN SELECT COUNT(*) FROM access_logs WHERE customer_id = 7;   -- 다시 사용


-- ---------------------------------------------------------------------
-- [15-9] 유니크 / 프리픽스 인덱스
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s15_uk, s15_email;

-- 유니크 인덱스 → type: const (정확히 1행)
CREATE TABLE s15_uk (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(20) NOT NULL,
  UNIQUE KEY uk_code (code)) ENGINE=InnoDB;
INSERT INTO s15_uk (code) VALUES ('A'),('B');
EXPLAIN SELECT * FROM s15_uk WHERE code = 'A';

-- 프리픽스 인덱스: 앞 N글자만
CREATE TABLE s15_email (id INT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(100) NOT NULL) ENGINE=InnoDB;
INSERT INTO s15_email (email) SELECT CONCAT(SUBSTRING(MD5(n),1,12), '@example.com') FROM tally WHERE n <= 3000;
-- 적정 프리픽스 길이 찾기: full 과 거의 같아지는 최소 N
SELECT COUNT(DISTINCT email)          AS full_distinct,
       COUNT(DISTINCT LEFT(email, 4)) AS p4,
       COUNT(DISTINCT LEFT(email, 8)) AS p8
FROM s15_email;
ALTER TABLE s15_email ADD INDEX idx_email_prefix (email(8));
EXPLAIN SELECT id FROM s15_email WHERE email = CONCAT(SUBSTRING(MD5(1),1,12), '@example.com');
DROP TABLE s15_uk, s15_email;


-- ---------------------------------------------------------------------
-- [15-10] 전문검색(FULLTEXT) 인덱스
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS s15_ft;
CREATE TABLE s15_ft (id INT AUTO_INCREMENT PRIMARY KEY, body TEXT,
  FULLTEXT KEY ft_body (body)) ENGINE=InnoDB;
INSERT INTO s15_ft (body) VALUES
  ('mysql index tuning guide'), ('postgres vacuum internals'), ('mysql replication and index');
EXPLAIN SELECT * FROM s15_ft WHERE MATCH(body) AGAINST('index' IN NATURAL LANGUAGE MODE);
SELECT id, body FROM s15_ft WHERE MATCH(body) AGAINST('index' IN NATURAL LANGUAGE MODE);
DROP TABLE s15_ft;


-- ---------------------------------------------------------------------
-- [15-11] 인덱스 정리 — access_logs 를 원래 상태(PK만)로
-- ---------------------------------------------------------------------
ALTER TABLE access_logs DROP INDEX idx_customer;
ALTER TABLE access_logs DROP INDEX idx_cust_time;
SHOW INDEX FROM access_logs;    -- PRIMARY 만 남아야 정상

SELECT 'Step 15 practice 완료' AS msg;
