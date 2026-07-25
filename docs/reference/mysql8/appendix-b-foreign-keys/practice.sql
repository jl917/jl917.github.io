-- =====================================================================
-- 부록 B — 외래키(FK) 실무 완전 정복  실습 파일
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql
-- 주의:  s13b_ 접두사 테이블만 사용. 공용 테이블은 건드리지 않습니다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- B-0. 정리 (재실행 안전)
-- ---------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 1;
DROP TABLE IF EXISTS s13b_child, s13b_parent,
  s13b_order_item, s13b_order, s13b_a, s13b_b;

-- ---------------------------------------------------------------------
-- B-2. 1452 / 1451 재현
-- ---------------------------------------------------------------------
CREATE TABLE s13b_parent (id INT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE s13b_child (
  cid INT AUTO_INCREMENT PRIMARY KEY,
  pid INT UNSIGNED NULL,                 -- NULL 허용 (고아 vs NULL 구분 실습용)
  KEY idx_pid (pid),
  CONSTRAINT fk_b_child FOREIGN KEY (pid) REFERENCES s13b_parent(id)
) ENGINE=InnoDB;

INSERT INTO s13b_parent VALUES (1);

-- ▼ 1452: 부모에 없는 값 참조 (에러 예상)
-- INSERT INTO s13b_child (pid) VALUES (999);

INSERT INTO s13b_child (pid) VALUES (1);

-- ▼ 1451: 참조되는 부모 삭제 (에러 예상)
-- DELETE FROM s13b_parent WHERE id = 1;

-- ---------------------------------------------------------------------
-- B-3. 타입 불일치 3780 재현 (주석 해제하면 에러)
-- ---------------------------------------------------------------------
CREATE TABLE s13b_a (id INT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
-- CREATE TABLE s13b_b (
--   id INT PRIMARY KEY,
--   a_id INT,                            -- 부모는 UNSIGNED, 여긴 SIGNED → 3780
--   CONSTRAINT fk_b_a FOREIGN KEY (a_id) REFERENCES s13b_a(id)
-- ) ENGINE=InnoDB;

-- 올바른 버전 (타입 일치)
CREATE TABLE s13b_b (
  id   INT UNSIGNED PRIMARY KEY,
  a_id INT UNSIGNED NULL,
  KEY idx_a (a_id),
  CONSTRAINT fk_b_a FOREIGN KEY (a_id) REFERENCES s13b_a(id)
) ENGINE=InnoDB;

-- FK 생성 실패 시 원인 확인:
-- SHOW ENGINE INNODB STATUS\G      -- LATEST FOREIGN KEY ERROR 섹션

-- ---------------------------------------------------------------------
-- B-4. 순서 문제 / 검사 일시 해제
-- ---------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO s13b_child (pid) VALUES (777);   -- 부모 777 없지만 검사 꺼서 통과
SET FOREIGN_KEY_CHECKS = 1;                  -- 다시 켜도 기존 고아는 재검증 안 함!

-- ---------------------------------------------------------------------
-- B-8. 고아 행 찾기 (안티조인)  —  777 이 잡혀야 함
-- ---------------------------------------------------------------------
SELECT c.cid, c.pid
FROM s13b_child c
LEFT JOIN s13b_parent p ON p.id = c.pid
WHERE c.pid IS NOT NULL       -- NULL 은 "참조 안 함", 고아 아님
  AND p.id IS NULL;

-- 정리
DELETE c FROM s13b_child c
LEFT JOIN s13b_parent p ON p.id = c.pid
WHERE c.pid IS NOT NULL AND p.id IS NULL;

-- 정리 후 다시 조회하면 0건
SELECT COUNT(*) AS orphan_after
FROM s13b_child c
LEFT JOIN s13b_parent p ON p.id = c.pid
WHERE c.pid IS NOT NULL AND p.id IS NULL;

-- ---------------------------------------------------------------------
-- B-9. TRUNCATE vs FK  (주석 해제하면 1701 에러)
-- ---------------------------------------------------------------------
-- TRUNCATE TABLE s13b_parent;   -- 자식이 참조 중이면 ERROR 1701

-- ---------------------------------------------------------------------
-- B-10. FK 사용 중 인덱스 삭제 시도 (주석 해제하면 1553 에러)
-- ---------------------------------------------------------------------
-- ALTER TABLE s13b_child DROP INDEX idx_pid;   -- ERROR 1553

-- ---------------------------------------------------------------------
-- 정리
-- ---------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 1;
DROP TABLE IF EXISTS s13b_child, s13b_parent, s13b_b, s13b_a;
