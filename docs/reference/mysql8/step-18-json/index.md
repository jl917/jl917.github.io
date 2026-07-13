# Step 18 — JSON

> **학습 목표**
> - JSON 타입이 TEXT 와 무엇이 다른지 이해하고, 경로식(`$.a.b[0]`, `$**`)으로 값을 꺼낸다
> - `->` 와 `->>` 의 차이를 정확히 알고, JSON_SET/INSERT/REPLACE/REMOVE/MERGE_PATCH 로 문서를 수정한다
> - `JSON_TABLE` 로 JSON 을 관계형 테이블처럼 펼쳐 JOIN·GROUP BY 를 적용한다
> - 생성 컬럼 인덱스와 **멀티밸류 인덱스(8.0.17+)** 로 JSON 검색을 빠르게 만든다
> - **언제 JSON 을 쓰고 언제 정규화 컬럼을 써야 하는지** 판단 기준을 갖는다
>
> **선행 스텝**: Step 17
> **예상 소요**: 70분

> **MySQL 8.0 신기능**
> - `JSON_TABLE` — **8.0.4**
> - `JSON_OVERLAPS`, `MEMBER OF`, **멀티밸류 인덱스**, `JSON_SCHEMA_VALID` — **8.0.17**
> - 네이티브 JSON 타입 자체는 5.7 에 도입됐지만, **실무에서 쓸 만해진 건 8.0부터**입니다.

> ⚠️ **안전 규칙**
> 공용 테이블 `products` 는 **절대 변경하지 않습니다.**
> JSON 수정 실습은 모두 사본 `s18_products` 에서 합니다. `practice.sql` 첫 블록이 사본을 만들어 줍니다.

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
```

우리 스키마의 `products.attrs` 가 JSON 컬럼입니다. 카테고리마다 속성이 완전히 다릅니다.

```
노트북   {"cpu":"i7-1360P","ram_gb":32,"ssd_gb":1024,"weight_kg":1.25,"tags":["business","pro"]}
셔츠     {"color":["white","blue"],"size":["M","L","XL"],"material":"cotton"}
감귤     {"origin":"제주","weight_kg":3,"organic":false}
USB허브  NULL                                        ← attrs 가 없는 상품도 있습니다
```

---

## 18-1. JSON 타입은 그냥 TEXT 가 아니다

JSON 을 `TEXT` 에 넣어도 동작은 합니다. 하지만 네이티브 `JSON` 타입은 네 가지가 다릅니다.

1. **저장 시 문법 검증** — 깨진 JSON 은 애초에 INSERT 가 거부됩니다 (`ERROR 3140`).
2. **파싱된 바이너리로 저장** — 조회할 때마다 다시 파싱하지 않습니다. TEXT 라면 매번 파싱해야 합니다.
3. **키 정렬 + 중복 키 제거** — 문서가 정규화됩니다.
4. **부분 업데이트 최적화** — `JSON_SET` 으로 값 하나만 바꾸면 문서 전체를 다시 쓰지 않을 수 있습니다.

```sql
SELECT CAST('{"b":1, "a":2, "a":3}' AS JSON) AS normalized;
```

**결과**
```
+------------------+
| normalized       |
+------------------+
| {"a": 3, "b": 1} |
+------------------+
```

키가 정렬되고(`b` 뒤에 있던 `a` 가 앞으로), **중복 키 `a` 는 마지막 값 3만 남았습니다.**
넣은 그대로 돌려받길 기대하면 안 됩니다.

`JSON_TYPE()` 으로 각 값의 타입을 확인할 수 있습니다.

```sql
SELECT JSON_TYPE(attrs)               AS t_root,
       JSON_TYPE(attrs -> '$.color')  AS t_color,
       JSON_TYPE(attrs -> '$.ram_gb') AS t_ram
FROM s18_products WHERE product_id IN (1, 12);
```

**결과**
```
+--------+---------+---------+
| t_root | t_color | t_ram   |
+--------+---------+---------+
| OBJECT | ARRAY   | NULL    |
| OBJECT | NULL    | INTEGER |
+--------+---------+---------+
```

---

## 18-2. JSON 만들기 — JSON_OBJECT / JSON_ARRAY / JSON_QUOTE

```sql
SELECT JSON_OBJECT('id', 1, 'name', '셔츠', 'tags', JSON_ARRAY('new', 'sale')) AS obj,
       JSON_ARRAY(1, 'two', NULL, TRUE)                                        AS arr,
       JSON_QUOTE('he said "hi"')                                              AS quoted;
```

**결과**
```
+------------------------------------------------------+------------------------+------------------+
| obj                                                  | arr                    | quoted           |
+------------------------------------------------------+------------------------+------------------+
| {"id": 1, "name": "셔츠", "tags": ["new", "sale"]}   | [1, "two", null, true] | "he said \"hi\"" |
+------------------------------------------------------+------------------------+------------------+
```

행을 통째로 JSON 문서로 말아 올리는 것도 자주 씁니다 (API 응답 조립).

```sql
SELECT JSON_OBJECT('product_id', product_id, 'name', name,
                   'price', price, 'attrs', attrs) AS doc
FROM s18_products WHERE product_id IN (1, 22);
```

**결과**
```
+-------------------------------------------------------------------------------------------------------------------------------------------------------+
| doc                                                                                                                                                   |
+-------------------------------------------------------------------------------------------------------------------------------------------------------+
| {"name": "베이직 옥스퍼드 셔츠", "attrs": {"size": ["M", "L", "XL"], "color": ["white", "blue"], "material": "cotton"}, "price": 39000.00, ...}       |
| {"name": "USB-C 허브 8in1", "attrs": null, "price": 59000.00, "product_id": 22}                                                                       |
+-------------------------------------------------------------------------------------------------------------------------------------------------------+
```

`attrs` 가 JSON 컬럼이므로 **문자열이 아니라 중첩 객체로** 들어갔습니다.
만약 `attrs` 가 TEXT 였다면 `"{\"size\":...}"` 처럼 이스케이프된 문자열이 됐을 겁니다.

---

## 18-3. 경로식 (path expression)

```
$              문서 루트
$.key          객체 멤버
$[0]           배열 원소 (0부터)
$.a.b[0]       중첩
$[*]           배열의 모든 원소
$.*            객체의 모든 값
$**.key        모든 깊이에서 key 를 찾음 (재귀 와일드카드)
```

```sql
SET @doc = CAST('{
  "name":"노트북",
  "spec":{"cpu":"i7","ram":[16,32],"maker":{"name":"ACME"}},
  "tags":["pro","business"]
}' AS JSON);

SELECT JSON_EXTRACT(@doc, '$.name')        AS p_name,
       JSON_EXTRACT(@doc, '$.spec.ram[0]') AS p_ram0,
       JSON_EXTRACT(@doc, '$.spec.ram[*]') AS p_ram_all,
       JSON_EXTRACT(@doc, '$.tags[1]')     AS p_tag1,
       JSON_EXTRACT(@doc, '$**.name')      AS p_any_name;
```

**결과**
```
+-------------+--------+-----------+------------+-----------------------+
| p_name      | p_ram0 | p_ram_all | p_tag1     | p_any_name            |
+-------------+--------+-----------+------------+-----------------------+
| "노트북"    | 16     | [16, 32]  | "business" | ["노트북", "ACME"]    |
+-------------+--------+-----------+------------+-----------------------+
```

`$**.name` 은 깊이에 상관없이 `name` 을 전부 찾아 **배열로** 돌려줍니다.
(루트의 `"노트북"` 과 `spec.maker.name` 의 `"ACME"` 둘 다)
와일드카드를 쓰면 결과가 항상 **배열**이 된다는 점을 기억하세요.

---

## 18-4. 추출 — `->` 와 `->>` 의 차이 (중요)

```
col -> '$.k'    ≡  JSON_EXTRACT(col, '$.k')                 → 결과 타입: JSON
col ->> '$.k'   ≡  JSON_UNQUOTE(JSON_EXTRACT(col, '$.k'))   → 결과 타입: 문자열
```

`->>` 를 **"화살표 두 개 = 따옴표 벗김"** 으로 외우세요.

```sql
SELECT product_id,
       attrs ->  '$.material'                         AS arrow,       -- "cotton"
       attrs ->> '$.material'                         AS arrow2,      -- cotton
       JSON_UNQUOTE(JSON_EXTRACT(attrs,'$.material')) AS unquote_fn,
       attrs ->> '$.color[0]'                         AS first_color
FROM s18_products WHERE product_id IN (1, 2, 3);
```

**결과**
```
+------------+----------+--------+------------+-------------+
| product_id | arrow    | arrow2 | unquote_fn | first_color |
+------------+----------+--------+------------+-------------+
|          1 | "cotton" | cotton | cotton     | white       |
|          2 | "cotton" | cotton | cotton     | beige       |
|          3 | "nylon"  | nylon  | nylon      | black       |
+------------+----------+--------+------------+-------------+
```

> ⚠️ **함정 — `=` 비교는 잘 되는데 `LIKE` 는 안 된다**
> `->` 의 결과에는 **따옴표가 실제로 들어 있습니다.**
> 그런데 `=` 비교는 우연히 잘 동작합니다. MySQL 이 오른쪽 `'cotton'` 을 JSON 으로 캐스팅해서 비교하기 때문입니다.
> **이것 때문에 `->` 를 써도 되는 줄 착각하게 됩니다.**
> 하지만 `LIKE`, `CONCAT`, `CHAR_LENGTH` 처럼 값을 **문자열로 다루는 순간** 따옴표가 튀어나옵니다.

```sql
SELECT product_id,
       (attrs ->  '$.material') = 'cotton'      AS eq_arrow,
       (attrs ->  '$.material') LIKE 'cot%'     AS like_arrow,
       (attrs ->> '$.material') LIKE 'cot%'     AS like_arrow2,
       CONCAT('소재: ', attrs ->  '$.material') AS concat_arrow,
       CONCAT('소재: ', attrs ->> '$.material') AS concat_arrow2,
       CHAR_LENGTH(attrs ->  '$.material')      AS len_arrow,
       CHAR_LENGTH(attrs ->> '$.material')      AS len_arrow2
FROM s18_products WHERE product_id IN (1, 2);
```

**결과**
```
+------------+----------+------------+-------------+------------------+----------------+-----------+------------+
| product_id | eq_arrow | like_arrow | like_arrow2 | concat_arrow     | concat_arrow2  | len_arrow | len_arrow2 |
+------------+----------+------------+-------------+------------------+----------------+-----------+------------+
|          1 |        1 |          0 |           1 | 소재: "cotton"   | 소재: cotton   |         8 |          6 |
|          2 |        1 |          0 |           1 | 소재: "cotton"   | 소재: cotton   |         8 |          6 |
+------------+----------+------------+-------------+------------------+----------------+-----------+------------+
```

`eq_arrow` 는 1(성공)인데 `like_arrow` 는 0(실패)입니다. `CHAR_LENGTH` 가 **8 vs 6** — 따옴표 2개가 들어 있다는 증거입니다.

> 💡 **실무 팁 — 규칙은 간단합니다**
> **애플리케이션으로 값을 꺼낼 땐 언제나 `->>`.**
> `->` 는 "JSON 조각을 JSON 인 채로" 다룰 때만 쓰세요 (예: `JSON_CONTAINS`, `MEMBER OF`, 멀티밸류 인덱스의 대상).

`attrs` 가 NULL 인 행에서 값을 꺼내면 에러가 아니라 **NULL** 이 나옵니다. 존재하지 않는 키도 NULL 입니다.

```
+------------+--------------------------+-------+----------+
| product_id | name                     | attrs | material |
+------------+--------------------------+-------+----------+
|         22 | USB-C 허브 8in1          | NULL  | NULL     |
|         29 | 프리미엄 라면 5입        | NULL  | NULL     |
+------------+--------------------------+-------+----------+
```

이 "조용한 NULL" 이 JSON 의 양날의 검입니다. 컬럼명을 오타 내도 에러가 안 나고 그냥 NULL 이 나옵니다.
정규화 컬럼이었다면 `Unknown column` 에러로 즉시 잡혔을 실수입니다.

---

## 18-5. 수정 — SET / INSERT / REPLACE / REMOVE / MERGE_PATCH

| 함수 | 키가 있으면 | 키가 없으면 |
|---|---|---|
| `JSON_SET` | **수정** | **추가** (upsert) |
| `JSON_INSERT` | 무시 | **추가** |
| `JSON_REPLACE` | **수정** | 무시 |
| `JSON_REMOVE` | **삭제** | 무시 |

한 줄로 셋을 비교해 봅시다. `$.material` 은 **있고** `$.new` 는 **없는** 상태입니다.

```sql
SELECT attrs                                                  AS original,
       JSON_SET(attrs,     '$.material', 'linen', '$.new', 1) AS after_set,
       JSON_INSERT(attrs,  '$.material', 'linen', '$.new', 1) AS after_insert,
       JSON_REPLACE(attrs, '$.material', 'linen', '$.new', 1) AS after_replace,
       JSON_REMOVE(attrs,  '$.material')                      AS after_remove
FROM s18_products WHERE product_id = 1;
```

**결과** (보기 좋게 줄바꿈했습니다)
```
original      {"size": ["M","L","XL"], "color": ["white","blue"], "material": "cotton"}
after_set     {"new": 1, "size": [...], "color": [...], "material": "linen"}    ← 둘 다 반영
after_insert  {"new": 1, "size": [...], "color": [...], "material": "cotton"}   ← new 만 추가
after_replace {          "size": [...], "color": [...], "material": "linen"}    ← material 만 수정
after_remove  {          "size": [...], "color": [...]}                         ← material 삭제
```

실제 UPDATE 는 **사본 테이블에서** 합니다.

```sql
UPDATE s18_products
SET attrs = JSON_SET(attrs, '$.season', 'summer', '$.reviewed', TRUE)
WHERE product_id = 1;

UPDATE s18_products
SET attrs = JSON_ARRAY_APPEND(attrs, '$.color', 'green')
WHERE product_id = 1;

SELECT JSON_PRETTY(attrs) AS attrs FROM s18_products WHERE product_id = 1;
```

**결과**
```
{
  "size": ["M", "L", "XL"],
  "color": ["white", "blue", "green"],
  "season": "summer",
  "material": "cotton",
  "reviewed": true
}
```

### JSON_MERGE_PATCH vs JSON_MERGE_PRESERVE

이 둘을 헷갈리면 데이터가 조용히 망가집니다.

- `JSON_MERGE_PATCH` (RFC 7386) — 같은 키는 **덮어쓰기**. 값에 `null` 을 주면 **그 키를 삭제**. → **이게 여러분이 원하는 것**
- `JSON_MERGE_PRESERVE` — 같은 키를 **배열로 합침**. → 대개 원하지 않는 결과

```sql
SELECT JSON_MERGE_PATCH(
         '{"a":1, "b":{"x":1,"y":2}, "c":3}',
         '{"b":{"y":99,"z":100}, "c":null, "d":4}'
       ) AS merge_patch,
       JSON_MERGE_PRESERVE(
         '{"a":1, "b":{"x":1,"y":2}}',
         '{"a":2, "b":{"y":99}}'
       ) AS merge_preserve;
```

**결과**
```
+----------------------------------------------------+--------------------------------------------+
| merge_patch                                        | merge_preserve                             |
+----------------------------------------------------+--------------------------------------------+
| {"a": 1, "b": {"x": 1, "y": 99, "z": 100}, "d": 4} | {"a": [1, 2], "b": {"x": 1, "y": [2, 99]}} |
+----------------------------------------------------+--------------------------------------------+
```

`merge_patch` 에서 `"c":null` 을 줬더니 **`c` 키가 사라졌습니다.** 그리고 `b` 는 재귀적으로 병합됐습니다.
`merge_preserve` 는 `a` 가 `1` 도 `2` 도 아닌 **`[1, 2]` 배열**이 돼 버렸습니다. 거의 항상 버그입니다.

> 💡 **실무 팁 — 부분 업데이트(PATCH) API 구현**
> `SET attrs = JSON_MERGE_PATCH(COALESCE(attrs, '{}'), ?)` 가 정석입니다.
> `COALESCE` 를 빼면 `attrs` 가 NULL 인 행에서 결과가 통째로 NULL 이 됩니다.
> (JSON 함수는 인자가 하나라도 NULL 이면 대부분 NULL 을 반환합니다)

---

## 18-6. 검색 — CONTAINS / CONTAINS_PATH / OVERLAPS / MEMBER OF

```sql
-- 배열 안에 값이 있는가. candidate 는 JSON 이어야 하므로 '"gaming"' 처럼 따옴표를 넣습니다.
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE JSON_CONTAINS(attrs, '"gaming"', '$.tags');

-- MEMBER OF (8.0.17+) — 같은 일을 훨씬 읽기 좋게
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE 'pro' MEMBER OF (attrs -> '$.tags');
```

**결과**
```
+------------+-----------------------------+------------+
| product_id | name                        | tags       |
+------------+-----------------------------+------------+
|         14 | 게이밍 노트북 RTX4060       | ["gaming"] |
+------------+-----------------------------+------------+

+------------+----------------------------+---------------------+
| product_id | name                       | tags                |
+------------+----------------------------+---------------------+
|         13 | 울트라북 14 i7/32GB        | ["business", "pro"] |
|         17 | 스마트폰 X20 Pro 512GB     | ["flagship", "pro"] |
+------------+----------------------------+---------------------+
```

`JSON_CONTAINS_PATH(doc, 'one'|'all', path...)` 는 **키의 존재 여부**만 봅니다 (값은 안 봄).

```sql
SELECT product_id, name,
       JSON_CONTAINS_PATH(attrs, 'one', '$.cpu', '$.storage_gb') AS has_cpu_or_storage,
       JSON_CONTAINS_PATH(attrs, 'all', '$.cpu', '$.ram_gb')     AS has_cpu_and_ram
FROM s18_products WHERE category_id IN (21, 22) ORDER BY product_id;
```

**결과**
```
+------------+-----------------------------+--------------------+-----------------+
| product_id | name                        | has_cpu_or_storage | has_cpu_and_ram |
+------------+-----------------------------+--------------------+-----------------+
|         12 | 울트라북 14 i5/16GB         |                  1 |               1 |
|         13 | 울트라북 14 i7/32GB         |                  1 |               1 |
|         14 | 게이밍 노트북 RTX4060       |                  1 |               1 |
|         15 | 보급형 노트북 15            |                  1 |               1 |
|         16 | 스마트폰 X20 256GB          |                  1 |               0 |
|         17 | 스마트폰 X20 Pro 512GB      |                  1 |               0 |
|         18 | 스마트폰 A5 128GB           |                  1 |               0 |
+------------+-----------------------------+--------------------+-----------------+
```

노트북은 `cpu`+`ram_gb` 둘 다 있고, 스마트폰은 `storage_gb` 만 있어 `all` 조건에서 0 이 나왔습니다.

`JSON_OVERLAPS` (8.0.17+) 는 **두 JSON 이 하나라도 겹치는 원소가 있는가**를 봅니다. 배열판 `IN` 이라고 생각하세요.

```sql
SELECT product_id, name, attrs ->> '$.tags' AS tags
FROM s18_products
WHERE JSON_OVERLAPS(attrs -> '$.tags', '["gaming","flagship"]');
```

**결과**
```
+------------+-----------------------------+---------------------+
| product_id | name                        | tags                |
+------------+-----------------------------+---------------------+
|         14 | 게이밍 노트북 RTX4060       | ["gaming"]          |
|         16 | 스마트폰 X20 256GB          | ["flagship"]        |
|         17 | 스마트폰 X20 Pro 512GB      | ["flagship", "pro"] |
+------------+-----------------------------+---------------------+
```

`JSON_SEARCH` 는 반대로 **값으로 경로를 역추적**합니다.

```
+------------+-------------------------------+---------------+----------------+
| product_id | name                          | path_of_white | path_of_cotton |
+------------+-------------------------------+---------------+----------------+
|          1 | 베이직 옥스퍼드 셔츠          | "$.color[0]"  | "$.material"   |
|          2 | 슬림핏 치노 팬츠              | NULL          | "$.material"   |
|          9 | 클래식 스니커즈               | "$.color[0]"  | NULL           |
+------------+-------------------------------+---------------+----------------+
```

---

## 18-7. ★ JSON_TABLE — JSON 을 관계형으로 펼치기 (8.0.4+)

**이 스텝에서 가장 중요한 기능입니다.**
JSON_TABLE 로 일단 테이블 모양으로 펼치고 나면, JOIN·GROUP BY·윈도우 함수 등 **SQL 전체를 그대로 쓸 수 있습니다.**

```
    products.attrs (JSON)                    JSON_TABLE 결과 (관계형)
  ┌──────────────────────────┐             ┌────────────┬────────┬────────┐
  │ {"cpu":"i7","ram_gb":32, │  ─────────▶ │ cpu        │ ram_gb │ ssd_gb │
  │  "ssd_gb":1024}          │             │ i7-1360P   │     32 │   1024 │
  └──────────────────────────┘             └────────────┴────────┴────────┘
```

### (1) 스칼라 컬럼 뽑기

```sql
SELECT p.product_id, p.name, jt.cpu, jt.ram_gb, jt.ssd_gb
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       cpu    VARCHAR(30) PATH '$.cpu',
       ram_gb INT         PATH '$.ram_gb',
       ssd_gb INT         PATH '$.ssd_gb'
     )) AS jt
WHERE p.category_id = 21
ORDER BY p.product_id;
```

**결과**
```
+------------+-----------------------------+-----------+--------+--------+
| product_id | name                        | cpu       | ram_gb | ssd_gb |
+------------+-----------------------------+-----------+--------+--------+
|         12 | 울트라북 14 i5/16GB         | i5-1340P  |     16 |    512 |
|         13 | 울트라북 14 i7/32GB         | i7-1360P  |     32 |   1024 |
|         14 | 게이밍 노트북 RTX4060       | i7-13700H |     16 |   1024 |
|         15 | 보급형 노트북 15            | i3-1215U  |      8 |    256 |
+------------+-----------------------------+-----------+--------+--------+
```

### (2) 배열 펼치기 — 1행이 N행이 된다

경로를 `'$.color[*]'` 로 주면 배열 원소마다 한 행씩 생깁니다.

```sql
SELECT p.product_id, p.name, jt.color
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.color[*]' COLUMNS (
       color VARCHAR(20) PATH '$'
     )) AS jt
WHERE p.category_id = 11
ORDER BY p.product_id, jt.color;
```

**결과**
```
+------------+-------------------------------+----------+
| product_id | name                          | color    |
+------------+-------------------------------+----------+
|          1 | 베이직 옥스퍼드 셔츠          | blue     |
|          1 | 베이직 옥스퍼드 셔츠          | green    |
|          1 | 베이직 옥스퍼드 셔츠          | white    |
|          2 | 슬림핏 치노 팬츠              | beige    |
|          2 | 슬림핏 치노 팬츠              | navy     |
|          3 | 라이트 다운 재킷              | black    |
|          4 | 울 니트 스웨터                | charcoal |
|          4 | 울 니트 스웨터                | gray     |
+------------+-------------------------------+----------+
```

상품 1이 색상 3개라 **3행**이 됐습니다. (`green` 은 18-5 에서 우리가 추가한 값입니다)

> ⚠️ **함정 — JSON_TABLE 은 암묵적으로 INNER JOIN 처럼 동작한다**
> 위 쿼리는 `FROM a, JSON_TABLE(...)` 즉 **CROSS JOIN LATERAL** 입니다.
> `attrs` 가 NULL 이거나 `color` 배열이 없는 상품은 **결과에서 통째로 사라집니다.**
> 모든 상품을 남기려면 `LEFT JOIN JSON_TABLE(...) ON TRUE` 를 쓰세요.

### (3) FOR ORDINALITY + NESTED PATH

`FOR ORDINALITY` 는 1부터 시작하는 순번을, `NESTED PATH` 는 중첩 배열을 펼칩니다.

```sql
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
```

**결과**
```
+------------+-----------------------------+------+----------+
| product_id | name                        | seq  | tag      |
+------------+-----------------------------+------+----------+
|         12 | 울트라북 14 i5/16GB         |    1 | business |
|         13 | 울트라북 14 i7/32GB         |    1 | business |
|         13 | 울트라북 14 i7/32GB         |    2 | pro      |
|         14 | 게이밍 노트북 RTX4060       |    1 | gaming   |
|         15 | 보급형 노트북 15            |    1 | entry    |
|         16 | 스마트폰 X20 256GB          |    1 | flagship |
|         17 | 스마트폰 X20 Pro 512GB      |    1 | flagship |
|         17 | 스마트폰 X20 Pro 512GB      |    2 | pro      |
|         18 | 스마트폰 A5 128GB           |    1 | entry    |
+------------+-----------------------------+------+----------+
```

### (4) 펼친 다음 집계하기 — JSON_TABLE 의 진가

"가장 많이 쓰인 태그"를 구합니다. 태그가 정규화 테이블에 있었다면 썼을 쿼리와 **똑같습니다.**

```sql
SELECT jt.tag, COUNT(*) AS cnt
FROM s18_products p,
     JSON_TABLE(p.attrs, '$.tags[*]' COLUMNS (tag VARCHAR(20) PATH '$')) AS jt
GROUP BY jt.tag
ORDER BY cnt DESC, jt.tag;
```

**결과**
```
+----------+-----+
| tag      | cnt |
+----------+-----+
| business |   2 |
| entry    |   2 |
| flagship |   2 |
| pro      |   2 |
| audio    |   1 |
| gaming   |   1 |
+----------+-----+
```

색상별 재고 합계도 같은 방식입니다.

```
+-------+----------+-------------+
| color | products | total_stock |
+-------+----------+-------------+
| white |        3 |         450 |
| blue  |        2 |         250 |
| black |        4 |         225 |
| green |        1 |         120 |
| beige |        2 |          95 |
| red   |        1 |          90 |
| ivory |        2 |          90 |
| navy  |        1 |          80 |
+-------+----------+-------------+
```

### (5) 외부 JSON 문자열 파싱

테이블 컬럼이 아니라 **리터럴 JSON 문자열**도 펼칠 수 있습니다. API 응답이나 배치 입력을 처리할 때 유용합니다.

```sql
SELECT *
FROM JSON_TABLE(
  '[{"id":1,"qty":2},{"id":5,"qty":1},{"id":9,"qty":7}]',
  '$[*]' COLUMNS (
    rn  FOR ORDINALITY,
    id  INT PATH '$.id',
    qty INT PATH '$.qty'
  )
) AS t;
```

**결과**
```
+------+------+------+
| rn   | id   | qty  |
+------+------+------+
|    1 |    1 |    2 |
|    2 |    5 |    1 |
|    3 |    9 |    7 |
+------+------+------+
```

### (6) ON EMPTY / ON ERROR

값이 없을 때 NULL 대신 기본값을 줄 수 있습니다. (`NULL ON EMPTY` 가 기본값, `ERROR ON EMPTY` 도 가능)

```sql
SELECT p.product_id, p.name, jt.ram_gb
FROM s18_products p,
     JSON_TABLE(p.attrs, '$' COLUMNS (
       ram_gb INT PATH '$.ram_gb' DEFAULT '0' ON EMPTY
     )) AS jt
WHERE p.category_id = 23 ORDER BY p.product_id;
```

**결과**
```
+------------+------------------------------+--------+
| product_id | name                         | ram_gb |
+------------+------------------------------+--------+
|         19 | 기계식 키보드 87키           |      0 |
|         20 | 무선 마우스 프로             |      0 |
|         21 | 27인치 4K 모니터             |      0 |
|         22 | USB-C 허브 8in1              |      0 |
|         23 | 노이즈캔슬링 헤드폰          |      0 |
+------------+------------------------------+--------+
```

---

## 18-8. JSON 컬럼 인덱싱

**JSON 컬럼에는 직접 인덱스를 걸 수 없습니다.** 우회로가 두 개 있습니다.

### (A) 생성 컬럼 + 일반 인덱스 — 스칼라 값용

```sql
ALTER TABLE s18_products
  ADD COLUMN cpu VARCHAR(30)
    GENERATED ALWAYS AS (attrs ->> '$.cpu') STORED,
  ADD INDEX idx_s18_cpu (cpu);

EXPLAIN SELECT product_id, name FROM s18_products WHERE cpu = 'i7-13700H';
```

**결과**
```
+----+-------------+--------------+------+---------------+-------------+---------+-------+------+----------+
| id | select_type | table        | type | possible_keys | key         | key_len | ref   | rows | filtered |
+----+-------------+--------------+------+---------------+-------------+---------+-------+------+----------+
|  1 | SIMPLE      | s18_products | ref  | idx_s18_cpu   | idx_s18_cpu |     123 | const |    1 |   100.00 |
+----+-------------+--------------+------+---------------+-------------+---------+-------+------+----------+
```

> 💡 **실무 팁 — 생성 컬럼 이름을 몰라도 인덱스를 탄다**
> MySQL 8 은 **원래 표현식으로 검색해도** 생성 컬럼 인덱스에 매칭시켜 줍니다.
> 아래 세 쿼리는 전부 `key: idx_s18_cpu` 로 같은 인덱스를 탑니다 (직접 EXPLAIN 해 보세요).
> ```sql
> WHERE cpu = 'i7-13700H'                                        -- 생성 컬럼 직접
> WHERE attrs ->> '$.cpu' = 'i7-13700H'                          -- 정의와 같은 표현식
> WHERE JSON_UNQUOTE(JSON_EXTRACT(attrs,'$.cpu')) = 'i7-13700H'  -- ->> 의 원래 형태
> ```
> 옵티마이저가 표현식을 정규화해서 매칭하기 때문입니다. 덕분에 애플리케이션 쿼리를 안 고쳐도 인덱스 효과를 얻습니다.
> `STORED` 대신 `VIRTUAL` 을 써도 인덱싱은 됩니다 (디스크를 안 먹는 대신 읽을 때 계산 비용).

### (B) 멀티밸류 인덱스 (MULTI-VALUE INDEX, 8.0.17+) — 배열용

**한 행이 인덱스 레코드를 여러 개 갖는 유일한 인덱스 타입입니다.** 배열의 각 원소가 인덱스 엔트리가 됩니다.
문법의 핵심은 `CAST(... AS <type> ARRAY)`.

```sql
ALTER TABLE s18_products
  ADD INDEX idx_s18_tags ( (CAST(attrs -> '$.tags' AS CHAR(20) ARRAY)) );

EXPLAIN SELECT product_id, name FROM s18_products
 WHERE 'gaming' MEMBER OF (attrs -> '$.tags');
```

**결과**
```
+----+-------------+--------------+------+---------------+--------------+---------+-------+------+-------------+
| id | select_type | table        | type | possible_keys | key          | key_len | ref   | rows | Extra       |
+----+-------------+--------------+------+---------------+--------------+---------+-------+------+-------------+
|  1 | SIMPLE      | s18_products | ref  | idx_s18_tags  | idx_s18_tags |      83 | const |    1 | Using where |
+----+-------------+--------------+------+---------------+--------------+---------+-------+------+-------------+
```

`JSON_OVERLAPS` 도 인덱스를 탑니다 (`type: range`).

```
+----+-------------+--------------+-------+---------------+--------------+---------+------+------+-------------+
| id | select_type | table        | type  | possible_keys | key          | key_len | ref  | rows | Extra       |
+----+-------------+--------------+-------+---------------+--------------+---------+------+------+-------------+
|  1 | SIMPLE      | s18_products | range | idx_s18_tags  | idx_s18_tags |      83 | NULL |    3 | Using where |
+----+-------------+--------------+-------+---------------+--------------+---------+------+------+-------------+
```

> ⚠️ **함정 — 멀티밸류 인덱스는 딱 3가지에서만 쓰인다**
> `MEMBER OF()`, `JSON_CONTAINS()`, `JSON_OVERLAPS()` — **이 셋뿐입니다.**
> `WHERE attrs ->> '$.tags' LIKE '%pro%'` 같은 건 절대 인덱스를 못 탑니다. 풀스캔입니다.
> 그 외 제약: 테이블당 개수 제한이 있고, 커버링 인덱스로 못 쓰며, PRIMARY/UNIQUE/외래키로는 못 만듭니다.

---

## 18-9. JSON_SCHEMA_VALID — 구조 검증 (8.0.17+)

JSON 은 스키마가 없다는 게 장점이자 최대 약점입니다. 최소한의 방어선을 칠 수 있습니다.

```sql
SET @schema = '{
  "type": "object",
  "required": ["cpu", "ram_gb"],
  "properties": {
    "cpu":    {"type": "string"},
    "ram_gb": {"type": "integer", "minimum": 4},
    "tags":   {"type": "array", "items": {"type": "string"}}
  }
}';

SELECT product_id, name, JSON_SCHEMA_VALID(@schema, attrs) AS valid
FROM s18_products WHERE category_id IN (21, 22) ORDER BY product_id;
```

**결과**
```
+------------+-----------------------------+-------+
| product_id | name                        | valid |
+------------+-----------------------------+-------+
|         12 | 울트라북 14 i5/16GB         |     1 |
|         13 | 울트라북 14 i7/32GB         |     1 |
|         14 | 게이밍 노트북 RTX4060       |     1 |
|         15 | 보급형 노트북 15            |     1 |
|         16 | 스마트폰 X20 256GB          |     0 |
|         17 | 스마트폰 X20 Pro 512GB      |     0 |
|         18 | 스마트폰 A5 128GB           |     0 |
+------------+-----------------------------+-------+
```

스마트폰은 `cpu`/`ram_gb` 가 없으니 스키마 위반(0)입니다. **왜** 실패했는지는 REPORT 로 봅니다.

```sql
SELECT JSON_PRETTY(JSON_SCHEMA_VALIDATION_REPORT(@schema, '{"cpu":"i5","ram_gb":2}')) AS report;
```

**결과**
```
{
  "valid": false,
  "reason": "The JSON document location '#/ram_gb' failed requirement 'minimum' at JSON Schema location '#/properties/ram_gb'",
  "schema-location": "#/properties/ram_gb",
  "document-location": "#/ram_gb",
  "schema-failed-keyword": "minimum"
}
```

`CHECK` 제약으로 걸면 아예 못 들어오게 막을 수 있습니다.

```sql
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
INSERT INTO s18_specs VALUES (2, '{"cpu":"i3","ram_gb":2}');    -- ERROR 3819
```

**결과**
```
ERROR 3819 (HY000): Check constraint 'chk_s18_spec' is violated.
```

---

## 18-10. ★ 언제 JSON 을 쓰고, 언제 정규화 컬럼을 쓸 것인가

이 스텝에서 **가장 중요한 절**입니다. JSON 은 강력하지만, 남용하면 관계형 DB를 쓸 이유가 사라집니다.

### JSON 을 쓰면 좋은 경우

- **스키마가 진짜로 가변적일 때** — 우리 `products.attrs` 가 딱 그렇습니다.
  노트북은 `cpu/ram_gb`, 셔츠는 `color/size`, 감귤은 `origin/organic`.
  이걸 정규화 컬럼으로 만들면 컬럼 40개짜리 테이블에 NULL 이 90%인 참사가 벌어집니다.
- **속성이 자주 추가되고, 그때마다 `ALTER TABLE` 하기 곤란할 때**
- **읽기 전용에 가까운 부가 정보** — 로그의 메타데이터, 외부 API 원본 응답 보관
- **문서를 통째로 읽고 통째로 쓰는** 접근 패턴

### 정규화 컬럼을 써야 하는 경우

- **자주 `WHERE` / `JOIN` / `ORDER BY` 에 쓰이는 값** → 인덱스가 필요하면 컬럼으로 빼세요
- **NOT NULL, UNIQUE, FOREIGN KEY 같은 제약이 필요한 값** → JSON 안에는 FK 를 걸 수 없습니다
- **집계의 중심이 되는 값** (금액, 수량, 상태)
- **모든 행이 반드시 갖는 값** → 그건 가변 속성이 아니라 그냥 컬럼입니다

### 판단 기준 (표)

| 질문 | 예 → | 아니오 → |
|---|---|---|
| 모든 행이 이 값을 갖는가? | **컬럼** | JSON 후보 |
| 이 값으로 검색·정렬·조인하는가? | **컬럼** (또는 생성 컬럼 + 인덱스) | JSON 가능 |
| FK/UNIQUE 제약이 필요한가? | **컬럼** | JSON 가능 |
| 값의 종류가 행마다 다른가? | **JSON** | 컬럼 |
| 속성이 계속 늘어나는가? | **JSON** | 컬럼 |

> ⚠️ **함정 — JSON 을 "스키마 설계 안 해도 되는 핑계"로 쓰지 마세요**
> 실무에서 가장 흔한 실패 패턴입니다.
> `orders` 테이블에 `data JSON` 컬럼 하나 만들어 놓고 금액·상태·고객ID를 전부 그 안에 넣으면:
> - `WHERE data->>'$.status' = 'PAID'` → **인덱스 없이 풀스캔**
> - 금액 합계가 문자열로 저장돼 `SUM()` 이 이상하게 동작
> - 고객ID에 FK 를 못 걸어 **고아 데이터**가 쌓임
> - 오타 낸 키(`statuss`)가 조용히 NULL 을 반환해 **버그가 몇 달 뒤에 발견됨**
>
> **관계형 DB 안에서 스키마리스를 흉내 내면 두 세계의 단점만 갖게 됩니다.**

> 💡 **실무 팁 — 하이브리드가 정답**
> 우리 스키마가 좋은 예입니다.
> `price`, `stock`, `status`, `category_id` 는 **정규화 컬럼**(검색·집계·FK 대상),
> 상품별로 제각각인 스펙만 **`attrs` JSON**.
> 그리고 JSON 안의 값이 자주 검색되기 시작하면 **생성 컬럼으로 승격**시키세요.
> "JSON 에서 시작해 안정된 것부터 컬럼으로 빼낸다" 가 현실적인 진화 경로입니다.

---

## 정리

### 함수 요약

| 분류 | 함수 | 비고 |
|---|---|---|
| 생성 | `JSON_OBJECT` / `JSON_ARRAY` / `JSON_QUOTE` | |
| 추출 | `JSON_EXTRACT` = `->` | 결과가 **JSON** (따옴표 포함) |
| 추출 | `JSON_UNQUOTE(JSON_EXTRACT())` = `->>` | 결과가 **문자열** ← 보통 이걸 쓴다 |
| 수정 | `JSON_SET` | 있으면 수정, 없으면 추가 |
| 수정 | `JSON_INSERT` / `JSON_REPLACE` | 없을 때만 / 있을 때만 |
| 수정 | `JSON_REMOVE` / `JSON_ARRAY_APPEND` | |
| 병합 | `JSON_MERGE_PATCH` | RFC 7386. 덮어쓰기, null 로 삭제 ← 보통 이걸 쓴다 |
| 병합 | `JSON_MERGE_PRESERVE` | 배열로 합침. 대개 원하지 않는 결과 |
| 검색 | `JSON_CONTAINS` / `MEMBER OF` (8.0.17) | 값 포함 여부. **멀티밸류 인덱스 사용 가능** |
| 검색 | `JSON_OVERLAPS` (8.0.17) | 교집합 존재 여부. **멀티밸류 인덱스 사용 가능** |
| 검색 | `JSON_CONTAINS_PATH` / `JSON_SEARCH` | 키 존재 / 값→경로 역추적 |
| 펼치기 | **`JSON_TABLE`** (8.0.4) | **가장 중요.** 관계형으로 변환 |
| 검증 | `JSON_SCHEMA_VALID` (8.0.17) | CHECK 제약과 조합 |
| 기타 | `JSON_TYPE` / `JSON_PRETTY` / `JSON_LENGTH` / `JSON_KEYS` | |

### 인덱싱 요약

| 대상 | 방법 | 사용 가능한 조건 |
|---|---|---|
| 스칼라 값 | 생성 컬럼(`STORED`/`VIRTUAL`) + 일반 인덱스 | `=`, `<`, `BETWEEN`, `ORDER BY` … 전부 |
| 배열 | **멀티밸류 인덱스** `CAST(... AS CHAR(n) ARRAY)` | `MEMBER OF`, `JSON_CONTAINS`, `JSON_OVERLAPS` **만** |

### 이 스텝의 함정 3가지

1. **`->` 는 따옴표를 남긴다.** `=` 비교는 되는데 `LIKE`/`CONCAT` 은 안 된다 → 항상 `->>`
2. **`JSON_TABLE` 은 INNER JOIN 처럼 동작한다.** attrs 가 NULL 인 행이 사라진다 → `LEFT JOIN ... ON TRUE`
3. **JSON 남용.** 검색·집계·제약이 필요한 값을 JSON 에 넣으면 관계형 DB 를 쓸 이유가 없어진다

---

## 정리(cleanup)

실습이 끝나면 사본 테이블을 지우세요.

```sql
DROP TABLE IF EXISTS s18_specs;
DROP TABLE IF EXISTS s18_products;
```

---

## 연습문제

`exercise.sql` 의 7문제를 풀어 보세요. 정답은 `solution.sql` 에 있습니다.

1. 무게(`weight_kg`)가 있는 상품만 뽑기
2. 태그가 2개 이상인 상품 (JSON_LENGTH)
3. 사이즈 배열을 펼쳐 사이즈별 상품 수 집계 (JSON_TABLE)
4. `organic: true` 인 식품 찾기
5. JSON_MERGE_PATCH 로 부분 업데이트 (사본 테이블)
6. 노트북 스펙을 관계형 테이블로 펼쳐 RAM 순 정렬 + 윈도우 함수 (Step 17 복습)
7. 멀티밸류 인덱스를 만들고 EXPLAIN 으로 확인

---

## 다음 단계
→ [Step 19 — 트랜잭션과 락](../step-19-transactions/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 3개로 진행합니다. 먼저 `practice.sql` 을 통째로 실행해 사본 테이블 `s18_products` 를 만들고 18-1 ~ 18-10 의 예제를 순서대로 따라갑니다. 그다음 `exercise.sql` 의 7문제를 직접 풀어 보고, 마지막으로 `solution.sql` 로 정답과 해설을 확인합니다. 세 파일 모두 **공용 테이블 `products` 는 읽기만 하고, 수정은 사본 `s18_products` 에서만** 하도록 설계돼 있습니다.

### practice.sql

강의 본문의 모든 예제를 그대로 담은 메인 실습 스크립트입니다. `mysql ... shop --table < practice.sql` 로 한 번에 실행하면 됩니다.

- **맨 처음 `[18-0]` 블록이 안전장치입니다.** `CREATE TABLE s18_products LIKE products;` + `INSERT INTO s18_products SELECT * FROM products;` 로 사본을 만듭니다. 주석에 적힌 대로 `LIKE` 는 컬럼·인덱스·CHECK 는 복사하지만 **FOREIGN KEY 는 복사하지 않아서**, 사본은 `categories` 와 무관하게 자유롭게 고칠 수 있습니다. 이 블록을 건너뛰면 이후 모든 쿼리가 `Table 's18_products' doesn't exist` 로 죽습니다.
- **일부러 주석 처리해 둔 문장이 두 군데 있습니다.** `[18-1]` 의 `'{not json}'` INSERT 는 주석을 풀면 **`ERROR 3140`** (Invalid JSON text) 이 납니다 — JSON 타입이 저장 시점에 문법을 검증한다는 증거입니다. `[18-9]` 끝의 `INSERT INTO s18_specs VALUES (2, '{"cpu":"i3","ram_gb":2}');` 는 주석을 풀면 **`ERROR 3819`** (Check constraint violated) 가 납니다. `ram_gb` 가 스키마의 `"minimum": 4` 를 못 넘기기 때문입니다. **둘 다 에러를 보는 것이 학습 목표**이니 반드시 직접 풀어서 확인하세요.
- `[18-4]` 의 `eq_arrow` / `like_arrow` / `len_arrow` 비교 쿼리가 이 스텝의 핵심 함정입니다. `(attrs -> '$.material') = 'cotton'` 은 1 이 나오는데 `LIKE 'cot%'` 는 0, `CHAR_LENGTH` 는 **8 vs 6** — 따옴표 2개가 실제로 들어 있다는 뜻입니다.
- `[18-5]` 의 UPDATE 들은 사본의 데이터를 **실제로 바꿉니다.** 상품 1에 `JSON_SET` 으로 `season`/`reviewed` 를 추가하고, `JSON_ARRAY_APPEND` 로 `color` 에 `green` 을 덧붙입니다. 그래서 뒤쪽 `[18-7] (2)` 의 색상 펼치기 결과에 `green` 이 등장합니다. 이어지는 세 번째 UPDATE 는 `JSON_MERGE_PATCH(COALESCE(attrs, '{}'), '{"origin":"국내","organic":true}')` 로 **`attrs` 가 NULL 이던 상품 22**를 패치합니다 — `COALESCE` 가 왜 필요한지를 보여 주는 실물 예제입니다(빼면 결과가 통째로 NULL). **순서 의존성이 있으니 스크립트를 위에서부터 순서대로 돌리세요.**
- `[18-8]` 의 두 `ALTER TABLE` 은 생성 컬럼 `cpu`(STORED) + `idx_s18_cpu`, 그리고 멀티밸류 인덱스 `idx_s18_tags ((CAST(attrs -> '$.tags' AS CHAR(20) ARRAY)))` 를 만듭니다. 이 스크립트를 두 번 실행하면 `[18-0]` 이 테이블을 `DROP` 하고 다시 만들기 때문에 중복 에러는 나지 않습니다.
- 맨 끝 `[18-10]` cleanup 두 줄(`DROP TABLE ... s18_specs / s18_products`)은 **주석 처리돼 있습니다.** 실습이 끝난 뒤 직접 실행해서 정리하세요.

```sql file="./practice.sql"
```

### exercise.sql

7문제의 문제문만 들어 있는 빈 연습 파일입니다. 각 문제 아래 `-- 여기에 작성` 자리에 직접 쿼리를 써 넣고 실행해 보세요. 파일 첫 주석대로 **`practice.sql` 을 먼저 돌려 `s18_products` 사본을 만든 뒤** 시작해야 합니다.

- **문제 1~2** 는 추출·길이 함수 감각을 확인합니다. "숫자로 보이게(따옴표 없이)"라는 단서는 `->` 로 꺼내도 숫자 타입은 따옴표가 안 붙는다는 점을 노린 것이고, 문제 2 의 힌트 `JSON_LENGTH` 는 `$.tags` 가 없는 행에서 NULL 을 돌려줘 자동으로 걸러진다는 점이 포인트입니다.
- **문제 3** 이 이 스텝의 핵심입니다. `$.size[*]` 를 `JSON_TABLE` 로 펼쳐 `GROUP BY` 하는, 18-7 (4)번 예제의 응용입니다.
- **문제 4** 의 힌트("JSON 의 true 는 문자열 'true' 가 아닙니다")는 `attrs ->> '$.organic' = 'true'` 같은 문자열 비교로 도망가지 말라는 경고입니다.
- **문제 5** 는 `product_id = 29` 가 **`attrs` 가 NULL 인 행**이라는 점이 함정입니다. `COALESCE(attrs, '{}')` 를 빼면 `JSON_MERGE_PATCH` 결과가 통째로 NULL 이 되어 데이터가 사라집니다. 반드시 사본 `s18_products` 에서만 UPDATE 하세요.
- **문제 6~7** 은 각각 Step 17 윈도우 함수 복습(JSON_TABLE + `RANK()`)과 멀티밸류 인덱스 만들기입니다.

```sql file="./exercise.sql"
```

### solution.sql

7문제의 정답과 **해설 주석**이 함께 들어 있는 파일입니다. 문제를 먼저 풀어 본 뒤에 열어 보세요.

- 파일 상단에서 `DROP TABLE IF EXISTS s18_products;` → `CREATE TABLE ... LIKE products;` 로 **사본을 다시 만듭니다.** `practice.sql` 을 안 돌렸어도 단독 실행되게 하려는 배려지만, 뒤집어 말하면 **`practice.sql` 로 해 둔 수정(green 색상 추가 등)과 `[18-8]` 의 인덱스가 전부 날아갑니다.** 이 점을 알고 실행하세요.
- 정답 1 은 `WHERE attrs -> '$.weight_kg' IS NOT NULL` — 존재 여부를 NULL 검사로 대신하며, `attrs` 자체가 NULL 인 행도 함께 걸러집니다.
- 정답 4 는 `WHERE attrs -> '$.organic' = TRUE` 로, **`->>` 가 아니라 `->` 를 쓰는 드문 경우**입니다. JSON boolean 은 JSON 값끼리 비교해야 정확하기 때문입니다(18-4 의 "`->` 는 JSON 조각을 JSON 인 채로 다룰 때만" 규칙의 실제 사례).
- 정답 5 는 29번에 `JSON_MERGE_PATCH(COALESCE(attrs, '{}'), ...)`, 이미 키가 있는 24번에는 `JSON_SET(attrs, '$.organic', TRUE)` 로 나눠 씁니다.
- 정답 7 의 `ADD INDEX idx_s18_size ( (CAST(attrs -> '$.size' AS CHAR(10) ARRAY)) )` 는 `size` 가 `["M","L","XL"]` 같은 **문자열 배열**이라 `CHAR(10)` 으로 캐스팅합니다. 멀티밸류 인덱스의 CAST 대상 타입은 `CHAR`/`BINARY`/`SIGNED`/`UNSIGNED`/`DATE`/`TIME`/`DATETIME`/`DECIMAL` 뿐이라 `INT ARRAY` 라고 쓰면 문법 에러이고, `SIGNED ARRAY` 로 바꿔도 `"M"` 을 숫자로 변환할 수 없어 `ALTER TABLE` 이 실패합니다. **배열 원소의 타입에 맞춰 캐스팅해야 합니다.**
- 정답 7 의 인덱스 이름은 `idx_s18_size` 로, `practice.sql` `[18-8]` 이 만드는 `idx_s18_tags` 와 다릅니다. 다만 이 파일 상단이 테이블을 다시 만들기 때문에 `idx_s18_cpu`/`idx_s18_tags` 는 남아 있지 않습니다.

```sql file="./solution.sql"
```
