-- =====================================================================
-- Step 07 — 조인(JOIN) : practice.sql
-- 실행: mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop -t < practice.sql
-- =====================================================================
USE shop;

-- [7-1] INNER JOIN — 양쪽에 다 있는 것만
SELECT
    o.order_id,
    o.order_date,
    c.name       AS 고객명,
    c.grade      AS 등급,
    o.total_amount
FROM orders o
INNER JOIN customers c ON c.customer_id = o.customer_id
ORDER BY o.order_id
LIMIT 5;

-- [7-1] INNER 는 생략 가능 (JOIN = INNER JOIN)
SELECT o.order_id, c.name
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
LIMIT 3;

-- [7-2] 다중 조인 — 주문 → 상세 → 상품 → 카테고리
SELECT
    o.order_id,
    c.name        AS 고객,
    p.name        AS 상품,
    cat.name      AS 카테고리,
    oi.quantity   AS 수량,
    oi.unit_price AS 단가
FROM orders o
JOIN customers c    ON c.customer_id  = o.customer_id
JOIN order_items oi ON oi.order_id    = o.order_id
JOIN products p     ON p.product_id   = oi.product_id
JOIN categories cat ON cat.category_id = p.category_id
ORDER BY o.order_id, p.product_id
LIMIT 8;

-- [7-3] LEFT JOIN — 왼쪽은 전부 남기고, 오른쪽은 없으면 NULL
--   categories 5개 대분류는 직접 매달린 상품이 없다 → 상품 컬럼이 NULL
SELECT
    cat.category_id,
    cat.name       AS 카테고리,
    p.product_id,
    p.name         AS 상품명
FROM categories cat
LEFT JOIN products p ON p.category_id = cat.category_id
WHERE cat.parent_id IS NULL
ORDER BY cat.category_id;

-- [7-3] LEFT JOIN + COUNT — 상품이 0개인 카테고리도 보인다
--   ⚠️ COUNT(*) 가 아니라 COUNT(오른쪽컬럼) 을 써야 0 이 나온다!
SELECT
    cat.category_id,
    cat.name              AS 카테고리,
    COUNT(p.product_id)   AS 상품수,
    COUNT(*)              AS `COUNT(*)_함정`
FROM categories cat
LEFT JOIN products p ON p.category_id = cat.category_id
GROUP BY cat.category_id, cat.name
ORDER BY 상품수, cat.category_id
LIMIT 8;

-- [7-4] ON vs WHERE — LEFT JOIN 에서 필터 위치가 결과를 바꾼다 (핵심)
-- 조건을 ON 에 두면: 조인 짝을 만들 때만 적용 → 왼쪽 행은 전부 보존
SELECT COUNT(*) AS 조건이_ON
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
      AND o.status = 'DELIVERED';

-- 조건을 WHERE 에 두면: 조인 "후" 적용 → NULL 확장된 행이 탈락 → INNER JOIN 이 됨
SELECT COUNT(*) AS 조건이_WHERE
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
WHERE o.status = 'DELIVERED';

-- [7-4] 눈으로 확인 — 배송완료 주문이 하나도 없는 고객
--   (예: 주문이 전부 CANCELLED 인 강소라)
SELECT
    c.customer_id,
    c.name,
    o.order_id,
    o.status
FROM customers c
LEFT JOIN orders o
       ON o.customer_id = c.customer_id
      AND o.status = 'DELIVERED'
WHERE c.customer_id = 6;

-- [7-5] 안티조인 ① — LEFT JOIN ... IS NULL
--   "결제가 없는 주문" = PENDING 주문 60건
SELECT
    o.order_id,
    o.status,
    o.total_amount
FROM orders o
LEFT JOIN payments pay ON pay.order_id = o.order_id
WHERE pay.payment_id IS NULL
ORDER BY o.order_id
LIMIT 8;

-- [7-5] 개수로 확인 — LEFT JOIN IS NULL vs 순수 조건
SELECT
    (SELECT COUNT(*) FROM orders o
       LEFT JOIN payments pay ON pay.order_id = o.order_id
      WHERE pay.payment_id IS NULL)     AS 안티조인_결과,
    (SELECT COUNT(*) FROM orders
      WHERE status = 'PENDING')          AS PENDING_주문수;

-- [7-5] 안티조인 ② — 후기를 한 번도 안 남긴 고객 (Step 05 의 NOT EXISTS 를 조인으로)
SELECT
    c.customer_id,
    c.name,
    c.grade
FROM customers c
LEFT JOIN reviews r ON r.customer_id = c.customer_id
WHERE r.review_id IS NULL
ORDER BY c.customer_id
LIMIT 8;

-- [7-6] SELF JOIN — 같은 테이블을 두 번 (사원 ↔ 관리자)
SELECT
    e.employee_id,
    e.name       AS 사원,
    e.position   AS 직급,
    m.name       AS 관리자,
    m.position   AS 관리자_직급
FROM employees e
LEFT JOIN employees m ON m.employee_id = e.manager_id
ORDER BY e.employee_id
LIMIT 10;

-- [7-6] SELF JOIN 으로 "나보다 월급 많은 같은 부서 사람" 세기
SELECT
    e.name       AS 사원,
    e.dept       AS 부서,
    e.salary     AS 급여,
    COUNT(h.employee_id) AS 나보다_높은_사람수
FROM employees e
LEFT JOIN employees h
       ON h.dept = e.dept
      AND h.salary > e.salary
GROUP BY e.employee_id, e.name, e.dept, e.salary
ORDER BY e.dept, e.salary DESC
LIMIT 10;

-- [7-7] CROSS JOIN — 모든 조합 (곱집합)
--   등급 4종 × 도시 3종 = 12행. 조건 없이 곱한다.
SELECT g.grade, ct.city
FROM (SELECT DISTINCT grade FROM customers) g
CROSS JOIN (SELECT DISTINCT city FROM customers WHERE city IN ('서울','부산','인천')) ct
ORDER BY g.grade, ct.city;

-- [7-7] CROSS JOIN 의 실용 예: 등급×도시 조합별 고객 수 (없는 조합도 0으로)
SELECT
    g.grade,
    ct.city,
    COUNT(c.customer_id) AS 고객수
FROM (SELECT DISTINCT grade FROM customers) g
CROSS JOIN (SELECT DISTINCT city FROM customers) ct
LEFT JOIN customers c ON c.grade = g.grade AND c.city = ct.city
GROUP BY g.grade, ct.city
HAVING COUNT(c.customer_id) > 0
ORDER BY g.grade, 고객수 DESC
LIMIT 10;

-- [7-8] USING — 조인 컬럼 이름이 양쪽 같을 때 축약
--   ON a.customer_id = b.customer_id  →  USING (customer_id)
--   USING 컬럼은 결과에 한 번만 나온다 (SELECT * 시 차이)
SELECT customer_id, o.order_id, o.total_amount
FROM orders o
JOIN customers c USING (customer_id)
ORDER BY o.order_id
LIMIT 5;

-- [7-9] RIGHT JOIN — LEFT 의 방향만 바꾼 것 (실무에선 잘 안 씀)
--   products 를 전부 남기고 order_items 를 매칭
SELECT
    p.product_id,
    p.name,
    COUNT(oi.order_item_id) AS 판매_횟수
FROM order_items oi
RIGHT JOIN products p ON p.product_id = oi.product_id
GROUP BY p.product_id, p.name
ORDER BY 판매_횟수 ASC, p.product_id
LIMIT 5;

-- [7-10] MySQL 엔 FULL OUTER JOIN 이 없다 → UNION 으로 우회
--   먼저 실패를 확인: FULL JOIN 은 문법 에러
--   SELECT * FROM a FULL OUTER JOIN b ON ...;   ← ERROR 1064
--
--   질문: "2024년 1분기 주문 고객" 과 "2025년 1분기 주문 고객" 을 비교하고 싶다.
--   한쪽에만 있는 고객까지 전부 보려면 FULL OUTER JOIN 이 필요하다.
--   우회: (LEFT JOIN) UNION (RIGHT JOIN). UNION 이 중복(양쪽 매칭 행)을 제거한다.
WITH
  q2024 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2024-01-01' AND order_date < '2024-04-01'),
  q2025 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2025-01-01' AND order_date < '2025-04-01')
SELECT a.customer_id AS `2024_1분기`, b.customer_id AS `2025_1분기`
FROM q2024 a
LEFT JOIN q2025 b ON b.customer_id = a.customer_id
UNION
SELECT a.customer_id, b.customer_id
FROM q2024 a
RIGHT JOIN q2025 b ON b.customer_id = a.customer_id
ORDER BY `2024_1분기`, `2025_1분기`
LIMIT 6;

-- [7-10] 한쪽에만 있는 고객만 추리기 (FULL OUTER + 대칭차집합)
--   2024 1분기에만 주문(15번) / 2025 1분기에만 주문(25번)
WITH
  q2024 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2024-01-01' AND order_date < '2024-04-01'),
  q2025 AS (SELECT DISTINCT customer_id FROM orders
             WHERE order_date >= '2025-01-01' AND order_date < '2025-04-01')
SELECT a.customer_id AS `2024만`, b.customer_id AS `2025만`
FROM q2024 a LEFT JOIN q2025 b ON b.customer_id = a.customer_id
WHERE b.customer_id IS NULL
UNION
SELECT a.customer_id, b.customer_id
FROM q2024 a RIGHT JOIN q2025 b ON b.customer_id = a.customer_id
WHERE a.customer_id IS NULL;

-- [7-11] 종합: 고객별 주문/결제/후기 요약 (LEFT JOIN 여러 개 + 집계)
--   ⚠️ 여러 1:N 을 한꺼번에 조인하면 행이 곱해진다 → COUNT(DISTINCT) 로 방어
SELECT
    c.customer_id,
    c.name                              AS 고객,
    c.grade                             AS 등급,
    COUNT(DISTINCT o.order_id)          AS 주문수,
    COUNT(DISTINCT pay.payment_id)      AS 결제수,
    COUNT(DISTINCT r.review_id)         AS 후기수,
    FORMAT(COALESCE(SUM(DISTINCT o.total_amount), 0), 0) AS 주문금액참고
FROM customers c
LEFT JOIN orders   o   ON o.customer_id = c.customer_id
LEFT JOIN payments pay ON pay.order_id  = o.order_id
LEFT JOIN reviews  r   ON r.customer_id = c.customer_id
GROUP BY c.customer_id, c.name, c.grade
ORDER BY 후기수 DESC, 주문수 DESC
LIMIT 8;
