-- =====================================================================
-- 04_seed_big.sql : 대용량 테이블 생성 (인덱스 / EXPLAIN / 파티셔닝 실습용)
-- ---------------------------------------------------------------------
-- shop 의 600건짜리 orders 로는 인덱스 효과가 눈에 보이지 않습니다.
-- (행이 적으면 옵티마이저가 그냥 풀스캔을 고르는 게 실제로 더 빠릅니다)
--
-- 그래서 100만 행짜리 access_logs 를 만듭니다.
--   * 처음에는 "인덱스가 하나도 없는" 상태로 둡니다. Step 15/16 에서 직접 붙입니다.
--   * 소요 시간: 노트북 기준 20초 ~ 1분. 커피 한 잔 하고 오세요.
--
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < 04_seed_big.sql
-- =====================================================================
USE shop;

DROP TABLE IF EXISTS access_logs;

CREATE TABLE access_logs (
  log_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  path        VARCHAR(100) NOT NULL,
  method      ENUM('GET','POST','PUT','DELETE') NOT NULL,
  status_code SMALLINT NOT NULL,
  duration_ms INT NOT NULL,
  user_agent  VARCHAR(60) NOT NULL,
  logged_at   DATETIME NOT NULL,
  PRIMARY KEY (log_id)
  -- 보조 인덱스 없음! Step 15 에서 직접 만들어 봅니다.
) ENGINE=InnoDB COMMENT='접근 로그(대용량 실습용)';

SET SESSION cte_max_recursion_depth = 2000000;

-- 100만 행을 한 번에 만들면 언두 로그가 커지므로 10만 행씩 10번 나눠 넣습니다.
-- (한 번에 넣고 싶다면 아래 프로시저 대신 seq 조건을 1000000 으로 바꾸세요)
DROP PROCEDURE IF EXISTS gen_access_logs;
DELIMITER $$
CREATE PROCEDURE gen_access_logs(IN total_rows INT, IN batch_size INT)
BEGIN
  DECLARE done INT DEFAULT 0;

  WHILE done < total_rows DO
    INSERT INTO access_logs (customer_id, path, method, status_code, duration_ms, user_agent, logged_at)
    WITH RECURSIVE seq AS (
      SELECT 1 AS i
      UNION ALL
      SELECT i + 1 FROM seq WHERE i < batch_size
    )
    SELECT
      1 + ((done + i) * 7)  % 30                                                    AS customer_id,
      ELT(1 + ((done + i) * 3) % 8,
          '/', '/products', '/products/detail', '/cart', '/orders',
          '/orders/detail', '/mypage', '/search')                                   AS path,
      ELT(1 + ((done + i) * 11) % 10, 'GET','GET','GET','GET','GET','GET','GET','POST','POST','PUT') AS method,
      ELT(1 + ((done + i) * 13) % 20,
          200,200,200,200,200,200,200,200,200,200,
          200,200,200,200,200,304,400,404,500,503)                                  AS status_code,
      1 + ((done + i) * 31) % 3000                                                  AS duration_ms,
      ELT(1 + ((done + i) * 5) % 4, 'Chrome/120','Safari/17','Edge/120','Bot/1.0')  AS user_agent,
      TIMESTAMPADD(SECOND, ((done + i) * 29) % 63072000, '2024-01-01 00:00:00')     AS logged_at
    FROM seq;

    SET done = done + batch_size;
    COMMIT;
  END WHILE;
END$$
DELIMITER ;

-- 100만 행 (10만 x 10 배치)
CALL gen_access_logs(1000000, 100000);

DROP PROCEDURE gen_access_logs;

-- 옵티마이저가 최신 통계로 판단하도록 통계 갱신 (Step 16 에서 의미 설명)
ANALYZE TABLE access_logs;

SELECT '04_seed_big.sql 완료' AS msg, COUNT(*) AS rows_generated FROM access_logs;

SELECT
  table_name,
  ROUND(data_length  / 1024 / 1024, 1) AS data_mb,
  ROUND(index_length / 1024 / 1024, 1) AS index_mb,
  table_rows AS approx_rows
FROM information_schema.tables
WHERE table_schema = 'shop' AND table_name = 'access_logs';
