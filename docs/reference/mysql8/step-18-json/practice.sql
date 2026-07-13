-- =====================================================================
-- Step 18 — JSON  practice.sql
-- ---------------------------------------------------------------------
-- 실행:
--   mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
--
-- ★ 안전 규칙
--   공용 테이블 products 는 절대 변경하지 않습니다.
--   JSON 을 수정하는 실습은 모두 사본 테이블 s18_products 에서 합니다.
--   맨 끝의 cleanup 블록으로 s18_* 를 모두 지울 수 있습니다.
-- =====================================================================
USE shop;

-- ---------------------------------------------------------------------
-- [18-0] 실습용 사본 만들기 (products → s18_products)
-- ---------------------------------------------------------------------
-- CREATE TABLE ... LIKE 는 컬럼/인덱스/CHECK 는 복사하지만 FOREIGN KEY 는 복사하지 않습니다.
-- (그래서 사본은 categories 와 무관하게 자유롭게 실습할 수 있습니다)
DROP TABLE IF EXISTS s18_products;
CREATE TABLE s18_products LIKE products;
INSERT INTO s18_products SELECT * FROM products;

SELECT COUNT(*) AS copied FROM s18_products;

-- ---------------------------------------------------------------------
-- [18-1] JSON 타입 : 그냥 TEXT 랑 뭐가 다른가?
-- ---------------------------------------------------------------------
-- (1) 저장할 때 문법 검증을 한다 → 깨진 JSON 은 애초에 못 들어간다.
--     아래 문장은 일부러 에러를 냅니다. (ERROR 3140)
-- INSERT INTO s18_products (category_id,name,price,cost,attrs)
--   VALUES (11,'깨진JSON',1000,500,'{not json}');

-- (2) 파싱된 바이너리로 저장 → 조회할 때 다시 파싱하지 않는다 (빠름)
-- (3) 키 순서가 정규화되고 중복 키는 제거된다. 아래를 보세요.
SELECT CAST('{"b":1, "a":2, "a":3}' AS JSON) AS normalized;

-- (4) 타입 확인
SELECT JSON_TYPE(attrs)              AS t_root,
       JSON_TYPE(attrs -> '$.color') AS t_color,
       JSON_TYPE(attrs -> '$.ram_gb') AS t_ram
FROM s18_products WHERE product_id IN (1, 12);

-- ---------------------------------------------------------------------
-- [18-2] JSON 만들기 : JSON_OBJECT / JSON_ARRAY / JSON_QUOTE
-- ---------------------------------------------------------------------
SELECT JSON_OBJECT('id', 1, 'name', '셔츠', 'tags', JSON_ARRAY('new', 'sale')) AS obj,
       JSON_ARRAY(1, 'two', NULL, TRUE)                                        AS arr,
       JSON_QUOTE('he said "hi"')                                              AS quoted,
       JSON_QUOTE('한글')                                                      AS quoted_ko;

-- 테이블 행을 JSON 으로 말아 올리기 (API 응답 만들 때 자주 씁니다)
SELECT JSON_OBJECT(
         'product_id', product_id,
         'name',       name,
         'price',      price,
         'attrs',      attrs           -- JSON 컬럼은 그대로 중첩됨
       ) AS doc
FROM s18_products
WHERE product_id IN (1, 22);

-- ---------------------------------------------------------------------
-- [18-3] 경로식(path expression)
-- ---------------------------------------------------------------------
--   $            문서 루트
--   $.key        객체 멤버
--   $[0]         배열 원소
--   $.a.b[0]     중첩
--   $[*]         배열 전체
--   $.*          객체의 모든 값
--   $**.key      모든 깊이에서 key 를 찾음 (와일드카드)
SET @doc = CAST('{
  "name":"노트북",
  "spec":{"cpu":"i7","ram":[16,32],"maker":{"name":"ACME"}},
  "tags":["pro","business"]
}' AS JSON);

SELECT JSON_EXTRACT(@doc, '$.name')            AS p_name,
       JSON_EXTRACT(@doc, '$.spec.ram[0]')     AS p_ram0,
       JSON_EXTRACT(@doc, '$.spec.ram[*]')     AS p_ram_all,
       JSON_EXTRACT(@doc, '$.tags[1]')         AS p_tag1,
       JSON_EXTRACT(@doc, '$**.name')          AS p_any_name;   -- 모든 깊이의 name

-- ---------------------------------------------------------------------
-- [18-4] 추출 : JSON_EXTRACT / -> / ->> / JSON_UNQUOTE
-- ---------------------------------------------------------------------
--   col -> '$.k'   = JSON_EXTRACT(col, '$.k')              → 결과가 JSON (문자열에 따옴표 붙음)
--   col ->> '$.k'  = JSON_UNQUOTE(JSON_EXTRACT(col,'$.k')) → 결과가 문자열 (따옴표 벗김)
SELECT product_id,
       attrs -> '$.material'                        AS arrow,      -- "cotton"  ← 따옴표 포함
       attrs ->> '$.material'                       AS arrow2,     -- cotton
       JSON_EXTRACT(attrs, '$.material')            AS extract_fn,
       JSON_UNQUOTE(JSON_EXTRACT(attrs,'$.material')) AS unquote_fn,
       attrs ->> '$.color[0]'                       AS first_color
FROM s18_products
WHERE product_id IN (1, 2, 3);

-- ★ 함정: -> 의 결과에는 따옴표가 "실제로 들어 있다"
--   = 비교는 우연히 잘 됩니다. MySQL 이 오른쪽 'cotton' 을 JSON 으로 캐스팅해서 비교하기 때문.
--   하지만 CONCAT / LIKE / CHAR_LENGTH 처럼 "문자열로 다루는" 순간 따옴표가 튀어나옵니다.
SELECT product_id,
       (attrs ->  '$.material') = 'cotton'      AS eq_arrow,      -- 1 (잘 됨! 착각하기 쉬움)
       (attrs ->> '$.material') = 'cotton'      AS eq_arrow2,     -- 1
       (attrs ->  '$.material') LIKE 'cot%'     AS like_arrow,    -- 0  ← 안 맞음!
       (attrs ->> '$.material') LIKE 'cot%'     AS like_arrow2,   -- 1
       CONCAT('소재: ', attrs ->  '$.material') AS concat_arrow,  -- 소재: "cotton"
       CONCAT('소재: ', attrs ->> '$.material') AS concat_arrow2, -- 소재: cotton
       CHAR_LENGTH(attrs ->  '$.material')      AS len_arrow,     -- 8 (따옴표 2개 포함)
       CHAR_LENGTH(attrs ->> '$.material')      AS len_arrow2     -- 6
FROM s18_products
WHERE product_id IN (1, 2);

-- attrs 가 NULL 인 상품은 결과도 NULL (에러가 아님)
SELECT product_id, name, attrs, attrs ->> '$.material' AS material
FROM s18_products WHERE product_id IN (22, 29);

-- ---------------------------------------------------------------------
-- [18-5] 수정 : JSON_SET / JSON_INSERT / JSON_REPLACE / JSON_REMOVE
-- ---------------------------------------------------------------------
--   JSON_SET     : 있으면 수정, 없으면 추가   (upsert)
--   JSON_INSERT  : 없을 때만 추가   (있으면 무시)
--   JSON_REPLACE : 있을 때만 수정   (없으면 무시)
--   JSON_REMOVE  : 삭제
-- 셋의 차이를 한 줄로 비교합니다. (원본 attrs 는 그대로, SELECT 로만 확인)
SELECT product_id,
       attrs                                                  AS original,
       JSON_SET(attrs,     '$.material', 'linen', '$.new', 1) AS after_set,
       JSON_INSERT(attrs,  '$.material', 'linen', '$.new', 1) AS after_insert,
       JSON_REPLACE(attrs, '$.material', 'linen', '$.new', 1) AS after_replace,
       JSON_REMOVE(attrs,  '$.material')                      AS after_remove
FROM s18_products WHERE product_id = 1;

-- 실제로 UPDATE 해 봅니다 (사본 테이블!)
UPDATE s18_products
SET attrs = JSON_SET(attrs, '$.season', 'summer', '$.reviewed', TRUE)
WHERE product_id = 1;

UPDATE s18_products
SET attrs = JSON_ARRAY_APPEND(attrs, '$.color', 'green')
WHERE product_id = 1;

SELECT product_id, name, JSON_PRETTY(attrs) AS attrs
FROM s18_products WHERE product_id = 1;

-- JSON_MERGE_PATCH : RFC 7386. 같은 키는 덮어쓰고, null 을 주면 삭제.
--   (JSON_MERGE_PRESERVE 는 반대로 배열로 합칩니다 — 헷갈리기 쉬움)
SELECT JSON_MERGE_PATCH(
         '{"a":1, "b":{"x":1,"y":2}, "c":3}',
         '{"b":{"y":99,"z":100}, "c":null, "d":4}'
       ) AS merge_patch,
       JSON_MERGE_PRESERVE(
         '{"a":1, "b":{"x":1,"y":2}}',
         '{"a":2, "b":{"y":99}}'
       ) AS merge_preserve;

-- 실무 패턴 : attrs 에 부분 패치 적용
UPDATE s18_products
SET attrs = JSON_MERGE_PATCH(COALESCE(attrs, '{}'), '{"origin":"국내","organic":true}')
WHERE product_id = 22;   -- attrs 가 NULL 이던 상품

SELECT product_id, name, attrs FROM s18_products WHERE product_id = 22;

-- ---------------------------------------------------------------------
-- [18-6] 검색 : JSON_CONTAINS / JSON_CONTAINS_PATH / JSON_OVERLAPS / MEMBER OF
-- ---------------------------------------------------------------------
-- JSON_CONTAINS(target, candidate [, path]) : target 안에 candidate 가 포함되는가
-- 배열 안에 값이 있는지 볼 때 candidate 는 JSON 값이어야 하므로 '"gaming"' 처럼 따옴표를 넣습니다.
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE JSON_CONTAINS(attrs, '"gaming"', '$.tags');

-- MEMBER OF (8.0.17+) — 훨씬 읽기 쉽습니다
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE 'pro' MEMBER OF (attrs -> '$.tags');

-- JSON_CONTAINS_PATH(doc, 'one'|'all', path...) : 키가 존재하는가 (값은 안 봄)
SELECT product_id, name,
       JSON_CONTAINS_PATH(attrs, 'one', '$.cpu', '$.storage_gb') AS has_cpu_or_storage,
       JSON_CONTAINS_PATH(attrs, 'all', '$.cpu', '$.ram_gb')     AS has_cpu_and_ram
FROM s18_products
WHERE category_id IN (21, 22)
ORDER BY product_id;

-- JSON_OVERLAPS (8.0.17+) : 두 JSON 이 하나라도 겹치는 원소가 있는가
--   IN 절의 JSON 판이라고 생각하면 됩니다.
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE JSON_OVERLAPS(attrs -> '$.tags', '["gaming","flagship"]');

-- JSON_SEARCH : 값으로 경로를 역추적
SELECT product_id, name,
       JSON_SEARCH(attrs, 'one', 'white')  AS path_of_white,
       JSON_SEARCH(attrs, 'all', 'cotton') AS path_of_cotton
FROM s18_products
WHERE product_id IN (1, 2, 9);

-- ---------------------------------------------------------------------
-- [18-7] ★ JSON_TABLE (MySQL 8.0.4+) — JSON 을 관계형 테이블로 펼치기
-- ---------------------------------------------------------------------
-- JSON 을 다루는 가장 강력한 도구입니다.
-- 일단 테이블로 펼치고 나면 JOIN / GROUP BY / 윈도우 함수 등 SQL 전부를 쓸 수 있습니다.

-- (1) 스칼라 컬럼 뽑기
SELECT p.product_id, p.name, jt.cpu, jt.ram_gb, jt.ssd_gb
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       cpu    VARCHAR(30) PATH '$.cpu',
       ram_gb INT         PATH '$.ram_gb',
       ssd_gb INT         PATH '$.ssd_gb'
     )) AS jt
WHERE p.category_id = 21
ORDER BY p.product_id;

-- (2) 배열 펼치기 (1행 → N행). color 배열이 상품마다 몇 개든 한 행씩 나옵니다.
SELECT p.product_id, p.name, jt.color
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.color[*]' COLUMNS (
       color VARCHAR(20) PATH '$'
     )) AS jt
WHERE p.category_id = 11
ORDER BY p.product_id, jt.color;

-- (3) FOR ORDINALITY + NESTED PATH : 순번 매기기 + 중첩 배열
SELECT p.product_id, p.name, jt.seq, jt.tag
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       NESTED PATH '$.tags[*]' COLUMNS (
         seq FOR ORDINALITY,
         tag VARCHAR(20) PATH '$'
       )
     )) AS jt
WHERE p.category_id IN (21, 22)
ORDER BY p.product_id, jt.seq;

-- (4) 펼친 뒤 집계 : "가장 많이 쓰인 태그" — JSON_TABLE + GROUP BY
SELECT jt.tag, COUNT(*) AS cnt
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.tags[*]' COLUMNS (tag VARCHAR(20) PATH '$')) AS jt
GROUP BY jt.tag
ORDER BY cnt DESC, jt.tag;

-- (5) 색상별 재고 합계 : 배열 펼치고 집계 (정규화 테이블처럼 사용)
SELECT jt.color, COUNT(*) AS products, SUM(p.stock) AS total_stock
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.color[*]' COLUMNS (color VARCHAR(20) PATH '$')) AS jt
GROUP BY jt.color
ORDER BY total_stock DESC
LIMIT 8;

-- (6) 외부 JSON 문자열을 그대로 펼치기 (API 응답 파싱 등)
SELECT *
FROM JSON_TABLE(
  '[{"id":1,"qty":2},{"id":5,"qty":1},{"id":9,"qty":7}]',
  '$[*]' COLUMNS (
    rn  FOR ORDINALITY,
    id  INT PATH '$.id',
    qty INT PATH '$.qty'
  )
) AS t;

-- ★ ON EMPTY / ON ERROR : 값이 없거나 타입이 안 맞을 때의 동작
SELECT p.product_id, p.name, jt.ram_gb
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       ram_gb INT PATH '$.ram_gb' DEFAULT '0' ON EMPTY
     )) AS jt
WHERE p.category_id = 23
ORDER BY p.product_id;

-- ---------------------------------------------------------------------
-- [18-8] JSON 컬럼 인덱싱
-- ---------------------------------------------------------------------
-- JSON 컬럼에는 직접 인덱스를 못 겁니다. 두 가지 우회로가 있습니다.

-- (A) 생성 컬럼(generated column) + 일반 인덱스 : 스칼라 값에 사용
ALTER TABLE s18_products
  ADD COLUMN cpu VARCHAR(30)
    GENERATED ALWAYS AS (attrs ->> '$.cpu') STORED,
  ADD INDEX idx_s18_cpu (cpu);

EXPLAIN SELECT product_id, name FROM s18_products WHERE cpu = 'i7-13700H';

-- MySQL 8 은 함수 인덱스와의 매칭도 해 줍니다. 원래 표현식으로 써도 인덱스를 탑니다.
EXPLAIN SELECT product_id, name FROM s18_products WHERE attrs ->> '$.cpu' = 'i7-13700H';

-- (B) 멀티밸류 인덱스 (MULTI-VALUE INDEX, MySQL 8.0.17+) : 배열에 사용
--     하나의 행이 인덱스 레코드를 여러 개 가질 수 있는 유일한 인덱스입니다.
--     CAST(... AS <type> ARRAY) 문법이 핵심.
ALTER TABLE s18_products
  ADD INDEX idx_s18_tags ( (CAST(attrs -> '$.tags' AS CHAR(20) ARRAY)) );

-- 멀티밸류 인덱스는 MEMBER OF / JSON_CONTAINS / JSON_OVERLAPS 세 가지에서만 쓰입니다.
EXPLAIN SELECT product_id, name FROM s18_products
 WHERE 'gaming' MEMBER OF (attrs -> '$.tags');

EXPLAIN SELECT product_id, name FROM s18_products
 WHERE JSON_OVERLAPS(attrs -> '$.tags', '["pro","audio"]');

SHOW INDEX FROM s18_products;

-- ---------------------------------------------------------------------
-- [18-9] JSON_SCHEMA_VALID — 문서 구조 검증 (8.0.17+)
-- ---------------------------------------------------------------------
SET @schema = '{
  "type": "object",
  "required": ["cpu", "ram_gb"],
  "properties": {
    "cpu":    {"type": "string"},
    "ram_gb": {"type": "integer", "minimum": 4},
    "tags":   {"type": "array", "items": {"type": "string"}}
  }
}';

SELECT product_id, name,
       JSON_SCHEMA_VALID(@schema, attrs) AS valid
FROM s18_products
WHERE category_id IN (21, 22)
ORDER BY product_id;

-- 왜 실패했는지 알고 싶으면 REPORT 를 쓰세요.
SELECT JSON_PRETTY(
         JSON_SCHEMA_VALIDATION_REPORT(@schema, '{"cpu":"i5","ram_gb":2}')
       ) AS report;

-- CHECK 제약으로 걸어두면 아예 못 들어오게 막을 수 있습니다.
DROP TABLE IF EXISTS s18_specs;
CREATE TABLE s18_specs (
  id   INT PRIMARY KEY,
  spec JSON NOT NULL,
  CONSTRAINT chk_s18_spec CHECK (
    JSON_SCHEMA_VALID(
      '{"type":"object","required":["cpu","ram_gb"],
        "properties":{"ram_gb":{"type":"integer","minimum":4}}}',
      spec)
  )
);

INSERT INTO s18_specs VALUES (1, '{"cpu":"i7","ram_gb":16}');   -- OK
SELECT * FROM s18_specs;
-- 아래는 ram_gb 가 2 라서 실패합니다 (ERROR 3819). 주석을 풀어서 확인해 보세요.
-- INSERT INTO s18_specs VALUES (2, '{"cpu":"i3","ram_gb":2}');

-- ---------------------------------------------------------------------
-- [18-10] 정리 (cleanup) — s18_* 전부 삭제
-- ---------------------------------------------------------------------
-- 실습이 끝나면 아래 두 줄을 실행하세요.
-- DROP TABLE IF EXISTS s18_specs;
-- DROP TABLE IF EXISTS s18_products;
