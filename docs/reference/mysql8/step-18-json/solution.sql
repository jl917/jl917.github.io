-- =====================================================================
-- Step 18 — JSON  solution.sql  (정답 + 해설)
-- ---------------------------------------------------------------------
-- 실행:
--   먼저 practice.sql 로 s18_products 를 만든 뒤
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < solution.sql
-- =====================================================================
USE shop;

-- 사본이 없으면 만들어 둡니다 (practice.sql 을 안 돌렸을 경우 대비)
DROP TABLE IF EXISTS s18_products;
CREATE TABLE s18_products LIKE products;
INSERT INTO s18_products SELECT * FROM products;

-- ---------------------------------------------------------------------
-- [정답 1] weight_kg 가 있는 상품
--
-- 해설:
--   -> 로 꺼내면 숫자는 따옴표 없이 그대로 나옵니다(숫자니까).
--   "키가 있는가"는 JSON_EXTRACT 결과가 NULL 이 아닌지로 판단합니다.
--   (attrs 자체가 NULL 인 행은 자동으로 걸러집니다)
-- ---------------------------------------------------------------------
SELECT product_id, name, attrs -> '$.weight_kg' AS weight_kg
FROM s18_products
WHERE attrs -> '$.weight_kg' IS NOT NULL
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [정답 2] 태그 2개 이상
--
-- 해설:
--   JSON_LENGTH(attrs, '$.tags') 로 배열 길이를 셉니다.
--   $.tags 가 없으면 JSON_LENGTH 는 NULL 을 반환하고, NULL >= 2 는 거짓이라 자동 제외됩니다.
-- ---------------------------------------------------------------------
SELECT product_id, name,
       attrs ->> '$.tags'          AS tags,
       JSON_LENGTH(attrs, '$.tags') AS tag_cnt
FROM s18_products
WHERE JSON_LENGTH(attrs, '$.tags') >= 2
ORDER BY tag_cnt DESC, product_id;

-- ---------------------------------------------------------------------
-- [정답 3] 사이즈별 상품 수
--
-- 해설:
--   JSON_TABLE 로 $.size[*] 를 펼친 뒤 GROUP BY.
--   size 배열이 없는 상품은 INNER JOIN 특성상 자동으로 빠집니다(여기선 그게 원하는 동작).
-- ---------------------------------------------------------------------
SELECT jt.sz AS size_value, COUNT(*) AS product_cnt
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.size[*]' COLUMNS (sz VARCHAR(10) PATH '$')) AS jt
GROUP BY jt.sz
ORDER BY product_cnt DESC, size_value;

-- ---------------------------------------------------------------------
-- [정답 4] 유기농 식품
--
-- 해설:
--   JSON 의 boolean true 는 문자열 'true' 가 아니라 JSON true 값입니다.
--   attrs -> '$.organic' = TRUE 처럼 JSON 값끼리 비교하는 게 정확합니다.
--   attrs ->> '$.organic' = 'true' (문자열 비교)도 동작하지만 타입이 섞여 권장하지 않습니다.
-- ---------------------------------------------------------------------
SELECT product_id, name, attrs ->> '$.origin' AS origin
FROM s18_products
WHERE attrs -> '$.organic' = TRUE
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [정답 5] 부분 업데이트  (사본 테이블에서!)
--
-- 해설:
--   29번은 attrs 가 NULL 이므로 COALESCE(attrs,'{}') 로 빈 객체를 만들어 병합합니다.
--   COALESCE 를 빼면 결과가 NULL 이 됩니다 (JSON 함수는 NULL 인자에 NULL 반환).
--   24번은 이미 organic 키가 있으니 JSON_SET 또는 JSON_MERGE_PATCH 둘 다 됩니다.
-- ---------------------------------------------------------------------
UPDATE s18_products
SET attrs = JSON_MERGE_PATCH(COALESCE(attrs, '{}'), '{"spicy": true, "servings": 5}')
WHERE product_id = 29;

UPDATE s18_products
SET attrs = JSON_SET(attrs, '$.organic', TRUE)
WHERE product_id = 24;

SELECT product_id, name, attrs
FROM s18_products
WHERE product_id IN (24, 29)
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- [정답 6] 노트북 스펙 리포트 (JSON_TABLE + 윈도우 함수)
--
-- 해설:
--   JSON_TABLE 로 펼친 결과에 윈도우 함수를 그대로 얹을 수 있습니다.
--   "펼치고 나면 그냥 테이블" 이라는 점이 JSON_TABLE 의 핵심입니다.
-- ---------------------------------------------------------------------
SELECT p.name,
       jt.cpu, jt.ram_gb, jt.ssd_gb,
       RANK() OVER (ORDER BY jt.ram_gb DESC)   AS rnk,
       ROUND(AVG(jt.ram_gb) OVER (), 1)        AS avg_ram
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       cpu    VARCHAR(30) PATH '$.cpu',
       ram_gb INT         PATH '$.ram_gb',
       ssd_gb INT         PATH '$.ssd_gb'
     )) AS jt
WHERE p.category_id = 21
ORDER BY jt.ram_gb DESC;

-- ---------------------------------------------------------------------
-- [정답 7] 멀티밸류 인덱스
--
-- 해설:
--   CAST(attrs -> '$.size' AS CHAR(10) ARRAY) 가 핵심.
--   size 는 문자열 배열("M","L"...)이므로 CHAR 로 캐스팅합니다.
--   MEMBER OF / JSON_CONTAINS / JSON_OVERLAPS 에서만 이 인덱스가 쓰입니다.
-- ---------------------------------------------------------------------
ALTER TABLE s18_products
  ADD INDEX idx_s18_size ( (CAST(attrs -> '$.size' AS CHAR(10) ARRAY)) );

EXPLAIN SELECT product_id, name
FROM s18_products
WHERE 'L' MEMBER OF (attrs -> '$.size');

SELECT product_id, name, attrs ->> '$.size' AS sizes
FROM s18_products
WHERE 'L' MEMBER OF (attrs -> '$.size')
ORDER BY product_id;

-- ---------------------------------------------------------------------
-- 정리(cleanup)
-- ---------------------------------------------------------------------
-- DROP TABLE IF EXISTS s18_specs;
-- DROP TABLE IF EXISTS s18_products;
