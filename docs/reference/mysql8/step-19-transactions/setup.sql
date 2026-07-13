-- =====================================================================
-- Step 19 — 트랜잭션과 락  setup.sql
-- ---------------------------------------------------------------------
-- 두 세션 실습에 쓸 전용 사본 테이블을 만듭니다.
-- 공용 테이블(accounts 같은 건 없지만 products/orders 등)은 건드리지 않습니다.
--
-- 실행:
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < setup.sql
-- =====================================================================
USE shop;

DROP TABLE IF EXISTS s19_accounts;
CREATE TABLE s19_accounts (
  account_id INT PRIMARY KEY,
  owner      VARCHAR(30)   NOT NULL,
  balance    DECIMAL(12,2) NOT NULL,
  KEY idx_owner (owner)
) ENGINE=InnoDB COMMENT='계좌 이체/락 실습';
INSERT INTO s19_accounts VALUES
  (1,'김민수',100000),(2,'이지은',50000),(3,'박철수',30000),(4,'최영희',0);

DROP TABLE IF EXISTS s19_seats;
CREATE TABLE s19_seats (
  seat_id   INT PRIMARY KEY,
  seat_no   VARCHAR(10) NOT NULL,
  status    ENUM('FREE','BOOKED') NOT NULL DEFAULT 'FREE',
  booked_by VARCHAR(30) NULL,
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='좌석 예약(SKIP LOCKED) 실습';
INSERT INTO s19_seats VALUES
  (1,'A1','FREE',NULL),(2,'A2','FREE',NULL),(3,'A3','FREE',NULL),
  (4,'A4','FREE',NULL),(5,'A5','FREE',NULL);

SELECT 's19 setup 완료' AS msg,
       (SELECT COUNT(*) FROM s19_accounts) AS accounts,
       (SELECT COUNT(*) FROM s19_seats)    AS seats;
