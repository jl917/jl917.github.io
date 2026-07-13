-- =====================================================================
-- 03_seed_orders.sql : 트랜잭션 데이터 생성 (orders / order_items / payments / reviews)
-- ---------------------------------------------------------------------
-- 핵심: RAND() 를 쓰지 않고 "나머지 연산(%)"으로 값을 만듭니다.
--       → 누가 몇 번을 실행하든 항상 똑같은 데이터가 나옵니다(재현 가능).
--       → 그래서 이후 Step 들의 예제 결과가 여러분 화면과 정확히 일치합니다.
--
-- 생성량: 주문 600건 / 주문상세 약 1,200건 / 결제 약 540건 / 후기 약 100건
-- =====================================================================
USE shop;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE reviews;
TRUNCATE TABLE payments;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 1) orders : 600건
--    - 기간: 2024-01-01 ~ 2025-12-31 (730일에 고르게 분포)
--    - 상태: DELIVERED 40% / PAID 20% / SHIPPED 20% / CANCELLED 10% / PENDING 10%
--    - total_amount 는 0 으로 넣고 3) 에서 상세 합계로 갱신합니다.
-- ---------------------------------------------------------------------
INSERT INTO orders (order_id, customer_id, order_date, status, total_amount, shipping_city)
SELECT
    t.n                                             AS order_id,
    c.customer_id,
    TIMESTAMPADD(MINUTE, (t.n * 7)  % 60,
      TIMESTAMPADD(HOUR, (t.n * 13) % 24,
        DATE_ADD('2024-01-01', INTERVAL (t.n * 37) % 730 DAY)))   AS order_date,
    ELT(1 + (t.n * 7) % 10,
        'DELIVERED','DELIVERED','PAID','SHIPPED','DELIVERED',
        'CANCELLED','PAID','DELIVERED','SHIPPED','PENDING')       AS status,
    0                                               AS total_amount,
    c.city                                          AS shipping_city
FROM tally t
JOIN customers c ON c.customer_id = 1 + (t.n * 17) % 30
WHERE t.n <= 600;

-- ---------------------------------------------------------------------
-- 2) order_items : 주문 1건당 1~3개 상품
--    tally 를 CROSS JOIN 해서 "행을 늘리는" 전형적인 기법입니다.
-- ---------------------------------------------------------------------
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT
    o.order_id,
    p.product_id,
    1 + (o.order_id + t.n) % 3                      AS quantity,
    p.price                                         AS unit_price
FROM orders o
JOIN tally t
      ON t.n <= 1 + (o.order_id % 3)                -- 주문당 상품 개수 1~3
JOIN products p
      ON p.product_id = 1 + (o.order_id * 7 + t.n * 13) % 40
ORDER BY o.order_id, t.n;

-- ---------------------------------------------------------------------
-- 3) orders.total_amount 갱신 (상세 합계)
--    상관 서브쿼리를 이용한 UPDATE. Step 11 에서 다시 다룹니다.
-- ---------------------------------------------------------------------
UPDATE orders o
SET o.total_amount = (
    SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0)
    FROM order_items oi
    WHERE oi.order_id = o.order_id
);

-- ---------------------------------------------------------------------
-- 4) payments
--    - PAID / SHIPPED / DELIVERED : 정상 결제 1건 (status = DONE)
--    - CANCELLED                  : 결제 후 환불 1건 (status = REFUNDED)
--    - PENDING                    : 결제 없음  ← LEFT JOIN / NOT EXISTS 실습 재료
-- ---------------------------------------------------------------------
INSERT INTO payments (order_id, method, amount, status, paid_at)
SELECT
    o.order_id,
    ELT(1 + (o.order_id % 4), 'CARD','BANK','POINT','MOBILE')     AS method,
    o.total_amount,
    IF(o.status = 'CANCELLED', 'REFUNDED', 'DONE')                AS status,
    TIMESTAMPADD(MINUTE, 5 + (o.order_id % 120), o.order_date)    AS paid_at
FROM orders o
WHERE o.status <> 'PENDING'
  AND o.total_amount > 0;

-- ---------------------------------------------------------------------
-- 5) reviews
--    배송완료(DELIVERED) 주문 중 일부에만 후기가 달립니다.
--    평점은 4~5점에 몰리도록(현실적인 분포) 만들었습니다.
-- ---------------------------------------------------------------------
INSERT INTO reviews (product_id, customer_id, rating, title, body, created_at)
SELECT
    oi.product_id,
    o.customer_id,
    ELT(1 + (oi.order_item_id % 10), 5,4,5,3,4,5,2,4,5,1)         AS rating,
    CONCAT(p.name, ' 후기')                                       AS title,
    ELT(1 + (oi.order_item_id % 5),
        '배송도 빠르고 품질도 좋아요. 재구매 의사 있습니다.',
        '가격 대비 만족스럽습니다.',
        '사진이랑 조금 다르지만 무난해요.',
        '기대보다 별로였어요. 아쉽습니다.',
        '완전 강추합니다! 주변에도 추천했어요.')                   AS body,
    DATE_ADD(o.order_date, INTERVAL 7 DAY)                        AS created_at
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
JOIN products p     ON p.product_id = oi.product_id
WHERE o.status = 'DELIVERED'
  AND o.order_id % 3 = 0
  AND oi.order_item_id % 2 = 0;

-- ---------------------------------------------------------------------
-- 검증
-- ---------------------------------------------------------------------
SELECT '03_seed_orders.sql 완료' AS msg,
       (SELECT COUNT(*) FROM orders)      AS orders,
       (SELECT COUNT(*) FROM order_items) AS order_items,
       (SELECT COUNT(*) FROM payments)    AS payments,
       (SELECT COUNT(*) FROM reviews)     AS reviews;

SELECT status, COUNT(*) AS cnt, FORMAT(SUM(total_amount),0) AS sum_amount
FROM orders GROUP BY status ORDER BY cnt DESC;
