-- =====================================================================
-- Step 10 — 집합 연산 : 정답 + 해설
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- A1. UNION 은 중복을 제거한다.
--     맨 끝 ORDER BY 는 전체 결과에 적용되며, 첫 SELECT 의 컬럼명(city)을 쓴다.
-- ---------------------------------------------------------------------
SELECT city FROM customers WHERE grade = 'VIP'
UNION
SELECT city FROM customers WHERE grade = 'GOLD'
ORDER BY city;
-- → 대구, 부산, 서울, 인천 (4행)

-- ---------------------------------------------------------------------
-- A2. UNION ALL 은 중복을 제거하지 않는다.
--     VIP 5명 + GOLD 8명 = 13행이 그대로 나온다.
--     같은 도시(서울 등)가 여러 번 등장하기 때문에 UNION(4행)과 차이가 난다.
--     → 중복 제거가 필요 없다면 UNION ALL 이 항상 빠르다
--       (임시테이블 없이 Append 로 스트리밍하기 때문).
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS union_all_cnt FROM (
    SELECT city FROM customers WHERE grade = 'VIP'
    UNION ALL
    SELECT city FROM customers WHERE grade = 'GOLD'
) x;
-- → 13

-- ---------------------------------------------------------------------
-- A3. 개별 SELECT 에 ORDER BY / LIMIT 를 걸려면 반드시 괄호로 감싼다.
--     괄호가 없으면 ORDER BY 가 전체 결과에 적용되어 의도와 달라진다.
--     (그룹 수가 적을 때 쓸 수 있는 "그룹별 Top-N" 기법)
-- ---------------------------------------------------------------------
(SELECT category_id, name, price FROM products WHERE category_id = 21 ORDER BY price DESC LIMIT 1)
UNION ALL
(SELECT category_id, name, price FROM products WHERE category_id = 23 ORDER BY price DESC LIMIT 1);
-- → 게이밍 노트북 RTX4060(2,190,000) / 27인치 4K 모니터(459,000)

-- ---------------------------------------------------------------------
-- A4. INTERSECT (8.0.31+). 양쪽 모두에 있는 product_id.
-- ---------------------------------------------------------------------
SELECT product_id FROM reviews
INTERSECT
SELECT product_id FROM products WHERE stock >= 100
ORDER BY product_id;
-- → 1, 18, 24, 28, 38 (5행)

-- ---------------------------------------------------------------------
-- A5. EXCEPT (8.0.31+). 왼쪽에는 있고 오른쪽에는 없는 것.
--     EXCEPT 는 왼쪽의 중복을 자동으로 제거하므로 DISTINCT 를 따로 쓸 필요가 없다.
-- ---------------------------------------------------------------------
SELECT product_id FROM order_items
EXCEPT
SELECT product_id FROM reviews
ORDER BY product_id;
-- → 24행 (모든 상품이 한 번은 주문됐으므로, 후기 없는 상품 24개와 같다)

-- ---------------------------------------------------------------------
-- A6. NOT EXISTS 버전. 결과 건수가 A5 와 같아야 한다.
--     차이점:
--       - EXCEPT     : 집합 연산. NULL 을 "같은 값"으로 취급 → NOT IN 함정 없음
--       - NOT EXISTS : 상관 서브쿼리. 역시 NULL 에 안전
--       - NOT IN     : NULL 이 섞이면 0행 → 쓰지 말 것
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS cnt_except FROM (
    SELECT product_id FROM order_items
    EXCEPT
    SELECT product_id FROM reviews
) x;
-- → 24

SELECT COUNT(DISTINCT oi.product_id) AS cnt_not_exists
FROM order_items oi
WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.product_id = oi.product_id);
-- → 24  (동일)

-- ---------------------------------------------------------------------
-- A7. FULL OUTER JOIN 흉내내기.
--     LEFT JOIN UNION RIGHT JOIN.
--     반드시 UNION(중복 제거)이어야 한다. UNION ALL 이면 매칭된 행이 두 번 나온다.
--     * 이 스키마는 payments.order_id 에 FK 가 있어 "주문 없는 결제"는 존재할 수 없다.
--       따라서 실질적으로는 LEFT JOIN 결과와 같지만, 패턴 자체를 익히는 것이 목적이다.
-- ---------------------------------------------------------------------
SELECT o.order_id, o.status AS order_status, p.payment_id, p.status AS pay_status
FROM orders o
LEFT JOIN payments p ON p.order_id = o.order_id
UNION
SELECT o.order_id, o.status, p.payment_id, p.status
FROM orders o
RIGHT JOIN payments p ON p.order_id = o.order_id
ORDER BY order_id
LIMIT 5;

-- ---------------------------------------------------------------------
-- A8. UNION ALL 로 합계 행 붙이기.
--     합계 행의 grade 자리에 문자열 리터럴을 넣는다.
--     grade 는 ENUM 이지만 UNION 결과 타입은 문자열로 승격되므로 문제없다.
--     정렬 트릭: 합계를 맨 아래로 보내려면 정렬용 컬럼을 하나 더 만든다.
-- ---------------------------------------------------------------------
SELECT grade, cnt FROM (
    SELECT grade AS grade, COUNT(*) AS cnt, 0 AS sort_key
    FROM customers
    GROUP BY grade
    UNION ALL
    SELECT '__TOTAL__', COUNT(*), 1
    FROM customers
) x
ORDER BY sort_key, cnt DESC;
-- → BRONZE(10) GOLD(8) SILVER(7) VIP(5), 그리고 마지막 줄에 __TOTAL__(30)
