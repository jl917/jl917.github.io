-- =====================================================================
-- Step 07 — 조인(JOIN) : solution.sql (정답 + 해설)
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < solution.sql
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [정답 1] INNER JOIN 기본
--   서울 고객 조건은 customers(왼쪽 개념) 조건이므로 WHERE 에 둡니다.
--   결과: 김민수의 4,380,000원 주문 5건이 상위를 차지합니다
--        (동점이라 order_id 로 tie-break 하면 더 안정적).
-- ---------------------------------------------------------------------
SELECT o.order_id, c.name AS 고객명, o.total_amount
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE c.city = '서울'
ORDER BY o.total_amount DESC, o.order_id
LIMIT 5;

-- ---------------------------------------------------------------------
-- [정답 2] 카테고리 두 번 조인 (소분류 + 대분류)
--   sub  = 상품이 직접 속한 소분류 (products.category_id)
--   main = 그 소분류의 상위 (sub.parent_id)
--   같은 테이블(categories)을 서로 다른 별칭으로 두 번 조인합니다.
-- ---------------------------------------------------------------------
SELECT
    p.product_id,
    p.name       AS 상품명,
    sub.name     AS 소분류,
    main.name    AS 대분류
FROM products p
JOIN categories sub  ON sub.category_id  = p.category_id
JOIN categories main ON main.category_id = sub.parent_id
ORDER BY p.product_id
LIMIT 10;

-- ---------------------------------------------------------------------
-- [정답 3] 주문 없는 고객 (안티조인)
--   결과: 0명. 30명 모두 주문이 있습니다(시드가 전원에게 20건씩 배정).
--   "0명"을 확인하는 것도 훌륭한 데이터 검증입니다.
-- ---------------------------------------------------------------------
SELECT c.customer_id, c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.order_id IS NULL;

-- ---------------------------------------------------------------------
-- [정답 4] 부하가 없는 말단 사원 (SELF JOIN 안티조인)
--   e 를 기준으로, e.employee_id 를 manager_id 로 가진 부하(s)를 LEFT JOIN.
--   부하가 없으면 s 가 NULL 확장 → WHERE s.employee_id IS NULL 로 걸러냄.
--   결과: 9~18번 (시니어/주니어 실무자 10명). 1~8번은 누군가의 관리자.
-- ---------------------------------------------------------------------
SELECT e.employee_id, e.name
FROM employees e
LEFT JOIN employees s ON s.manager_id = e.employee_id
WHERE s.employee_id IS NULL
ORDER BY e.employee_id;

-- ---------------------------------------------------------------------
-- [정답 5] 결제 상태별 주문 수
--   PENDING 주문은 payments 에 짝이 없어 pay.status 가 NULL 이 됩니다.
--   COALESCE 로 NULL 을 '(무결제)' 로 표시. GROUP BY 는 NULL 도 한 그룹으로 묶습니다.
--   결과: DONE 480 / REFUNDED 60 / (무결제) 60
-- ---------------------------------------------------------------------
SELECT
    COALESCE(pay.status, '(무결제)')   AS 결제상태,
    COUNT(DISTINCT o.order_id)          AS 주문수
FROM orders o
LEFT JOIN payments pay ON pay.order_id = o.order_id
GROUP BY pay.status
ORDER BY 주문수 DESC;

-- ---------------------------------------------------------------------
-- [정답 6] 카테고리별 매출 (0도 보이게)
--   핵심: 취소 제외를 "ON o.status <> 'CANCELLED'" 로 하면 틀립니다!
--   그 조건은 orders 를 매칭할지만 정할 뿐, order_items(oi)의 값은 그대로
--   남아 NULL 확장된 취소 주문의 상세가 매출에 섞여 들어갑니다.
--   → SUM(CASE WHEN o.status <> 'CANCELLED' THEN ... END) 로 "합산 여부"를
--     제어해야 정확합니다.
--   결과: 노트북 327,900,000 이 1위. 상품 없는 대분류 5개는 매출 0.
-- ---------------------------------------------------------------------
SELECT
    cat.category_id,
    cat.name AS 카테고리,
    FORMAT(
      COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED'
                        THEN oi.quantity * oi.unit_price END), 0), 0
    ) AS 매출
FROM categories cat
LEFT JOIN products    p  ON p.category_id = cat.category_id
LEFT JOIN order_items oi ON oi.product_id = p.product_id
LEFT JOIN orders      o  ON o.order_id    = oi.order_id
GROUP BY cat.category_id, cat.name
ORDER BY SUM(CASE WHEN o.status <> 'CANCELLED'
                  THEN oi.quantity * oi.unit_price END) DESC
LIMIT 12;

-- ---------------------------------------------------------------------
-- [정답 7] ON vs WHERE 차이 확인
--   (A) = 46, (B) = 20.
--   (A) 조건이 ON: 500만원 초과 주문만 매칭하되, 그런 주문이 없는 고객도
--       NULL 확장으로 남습니다. 500만 초과 주문 20건 + 그런 주문이 하나도
--       없는 고객 26명 = 46.
--   (B) 조건이 WHERE: 조인 후 필터. NULL 확장 행은 o.total_amount 가 NULL 이라
--       NULL > 5000000 = UNKNOWN 으로 탈락 → 사실상 INNER JOIN → 20.
--   교훈: LEFT JOIN 의 오른쪽 조건을 WHERE 에 두면 LEFT 가 INNER 로 퇴화한다.
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS A
FROM customers c
LEFT JOIN orders o
  ON o.customer_id = c.customer_id
 AND o.total_amount > 5000000;

SELECT COUNT(*) AS B
FROM customers c
LEFT JOIN orders o
  ON o.customer_id = c.customer_id
WHERE o.total_amount > 5000000;

-- ---------------------------------------------------------------------
-- [정답 8] 후기가 하나도 없는 카테고리
--   "상품은 있으나 그 카테고리의 어떤 상품에도 후기가 없는" 카테고리.
--   먼저 상품이 있는 카테고리로 좁히고(EXISTS products),
--   그 카테고리 상품에 후기가 하나도 없음(NOT EXISTS reviews)을 확인합니다.
--   결과: 소설(52). 소설 카테고리의 상품(여름의 끝에서)에는 후기가 없습니다.
-- ---------------------------------------------------------------------
SELECT cat.category_id, cat.name
FROM categories cat
WHERE EXISTS (
        SELECT 1 FROM products p WHERE p.category_id = cat.category_id
      )
  AND NOT EXISTS (
        SELECT 1
        FROM products p
        JOIN reviews  r ON r.product_id = p.product_id
        WHERE p.category_id = cat.category_id
      )
ORDER BY cat.category_id;
