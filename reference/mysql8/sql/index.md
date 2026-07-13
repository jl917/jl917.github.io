# 샘플 데이터베이스

이 코스의 **모든 Step 이 공유하는 예제 데이터베이스** `shop` 을 만드는 곳입니다. 가상의 온라인 쇼핑몰을 모델로 삼았고, 카테고리·고객·상품·주문·결제·후기·사원 테이블이 실제 서비스와 비슷한 모양으로 얽혀 있습니다. Step 04 의 단순 `SELECT` 부터 Step 21 의 파티셔닝까지, 교재에 나오는 예제 쿼리는 전부 이 데이터를 대상으로 실행합니다.

가장 중요한 특징은 **재현 가능성**입니다. 데이터를 만들 때 `RAND()` 를 전혀 쓰지 않고 나머지 연산(`%`)만 사용하기 때문에, 누가 몇 번을 실행하든 항상 똑같은 행이 만들어집니다. 덕분에 교재에 적힌 예제 결과가 여러분 화면의 결과와 **정확히 일치**합니다. 결과가 다르다면 데이터가 아니라 쿼리가 다른 것입니다.

## 전체 구성

| 파일 | 역할 | 만들어지는 것 |
| --- | --- | --- |
| `install.sh` | 아래 SQL 들을 순서대로 실행하는 설치 스크립트 | — |
| `01_schema.sql` | 데이터베이스와 9개 테이블 DDL | `shop` 스키마 |
| `02_seed_master.sql` | 마스터 데이터 적재 | tally 10,000 / 카테고리 17 / 고객 30 / 상품 40 / 사원 18 |
| `03_seed_orders.sql` | 트랜잭션 데이터 생성 | 주문 600 / 주문상세 약 1,200 / 결제 약 540 / 후기 약 100 |
| `04_seed_big.sql` | 대용량 테이블 생성 (선택) | `access_logs` 100만 행 |

테이블 관계는 다음과 같습니다.

```
categories ──┐
             └─< products ──< order_items >── orders >── customers
                    │                            │
                    └──< reviews >───────────────┘
                                        payments >── orders
employees (자기참조: manager_id → employee_id)
tally     (1~10000 숫자 테이블. 데이터 생성 보조용)
```

## 사용법

Docker 실습 환경(`docker/docker-compose.yml`)이 먼저 떠 있어야 합니다. MySQL 이 `127.0.0.1:3307` 에서 대기 중인 상태에서 이 디렉터리로 이동해 스크립트를 실행합니다.

```bash
cd docs/reference/mysql8/sql
chmod +x install.sh

# 스키마 + 마스터 + 주문 데이터 (약 5초)
./install.sh

# Step 15(인덱스) 이후에는 100만 행 access_logs 까지
./install.sh --big
```

Step 14 까지는 `--big` 없이도 충분합니다. 인덱스와 EXPLAIN 을 다루는 **Step 15 부터 `--big` 이 필요**하므로, 그때 다시 `./install.sh --big` 을 실행하면 됩니다. 접속 정보는 `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` 환경 변수로 덮어쓸 수 있습니다.

:::warning 파괴적 스크립트입니다
`01_schema.sql` 은 `DROP TABLE`, `02`/`03` 은 `TRUNCATE TABLE` 로 시작합니다. 실습 중 만든 데이터가 있다면 **전부 사라집니다.** 다행히 이는 "언제든 깨끗한 초기 상태로 되돌릴 수 있다"는 뜻이기도 합니다. 실습하다 데이터를 망가뜨렸다면 `./install.sh` 를 다시 돌리면 됩니다.
:::

## 실습 파일

설치는 `install.sh` 하나로 끝나지만, 그 안에서 SQL 4개가 **번호 순서대로** 실행됩니다. `01_schema.sql` 로 빈 테이블을 만들고 → `02_seed_master.sql` 로 사람이 읽을 수 있는 고정 데이터를 넣고 → `03_seed_orders.sql` 로 주문·결제·후기를 계산해서 생성한 뒤 → 필요할 때만 `04_seed_big.sql` 로 대용량 로그를 붙입니다. 순서 의존성이 있으므로 개별 실행할 때도 번호를 지켜야 합니다.

### install.sh

전체 설치를 한 방에 끝내는 진입점입니다. `set -euo pipefail` 로 시작하므로 중간에 SQL 하나라도 실패하면 즉시 멈춥니다 — 절반만 적재된 애매한 상태로 넘어가지 않습니다.

- 접속 정보는 `HOST="${MYSQL_HOST:-127.0.0.1}"`, `PORT="${MYSQL_PORT:-3307}"`, `USER="${MYSQL_USER:-learner}"`, `PASS="${MYSQL_PASSWORD:-learn1234}"` 로 **기본값을 갖되 환경 변수로 덮어쓸 수 있게** 되어 있습니다. 이 기본값은 `docker/docker-compose.yml` 과 맞춰져 있으니, 도커 환경을 그대로 쓴다면 아무것도 설정하지 않아도 됩니다.
- `HERE="$(cd "$(dirname "$0")" && pwd)"` 로 스크립트 자신의 절대 경로를 구한 뒤 `$MYSQL < "${HERE}/01_schema.sql"` 식으로 호출합니다. 덕분에 **어느 디렉터리에서 실행해도** SQL 파일을 찾아냅니다.
- 본격 적재 전에 `SELECT VERSION()` 을 한 번 날려 **접속부터 확인**합니다. 여기서 실패하면 컨테이너가 아직 안 떴거나 포트가 다른 것입니다.
- `01_schema.sql` 은 DB 이름 없이 실행하고(파일 안에 `CREATE DATABASE` 가 있으므로), `02`/`03` 은 `$MYSQL shop < ...` 처럼 **`shop` 을 기본 DB 로 지정**해 실행합니다.
- `if [[ "${1:-}" == "--big" ]]` — 첫 번째 인자가 `--big` 일 때만 `04_seed_big.sql` 을 추가로 돌립니다. 20초~1분 걸리므로 기본에서는 제외되어 있습니다.

```bash file="./install.sh"
```

### 01_schema.sql

`shop` 데이터베이스와 9개 테이블을 만드는 DDL 입니다. 가장 먼저, 그리고 스키마를 초기화하고 싶을 때마다 실행합니다. Step 02(데이터 타입)와 Step 13(제약 조건)의 내용이 실물로 들어 있으니, 그 Step 들을 읽을 때 이 파일을 함께 보면 이해가 빠릅니다.

- 문자셋은 `DEFAULT CHARACTER SET utf8mb4` / `COLLATE utf8mb4_0900_ai_ci` 입니다. 한글과 이모지를 안전하게 담기 위한 MySQL 8 의 표준 선택입니다.
- 맨 앞의 `DROP TABLE IF EXISTS` 들은 **FK 역순**(reviews → payments → order_items → orders → products → categories → customers)으로 나열되어 있습니다. 부모 테이블을 먼저 지우려 하면 외래 키 때문에 실패하기 때문입니다. 이 순서 덕분에 스크립트를 **몇 번이든 다시 실행**할 수 있습니다.
- `tally` 는 1~10000 을 담는 숫자 테이블입니다. 그 자체로는 아무 의미가 없지만, `03_seed_orders.sql` 에서 **행을 늘리는 도구**로 쓰입니다. SQL 로 데이터를 만들어낼 때 매우 자주 쓰는 기법입니다.
- `categories.parent_id` 와 `employees.manager_id` 는 **자기 자신을 참조하는 FK** 입니다. 각각 카테고리 계층(대분류 5 + 소분류 12)과 4단계 조직도를 표현하며, Step 09 의 재귀 CTE 와 Step 07 의 SELF JOIN 실습 재료가 됩니다.
- `products.attrs JSON` 컬럼은 Step 18 전용입니다. `chk_products_price CHECK (price >= 0)`, `chk_order_items_qty CHECK (quantity > 0)`, `chk_reviews_rating CHECK (rating BETWEEN 1 AND 5)` 같은 CHECK 제약은 MySQL 8.0.16+ 에서 실제로 동작하며 Step 13 에서 일부러 위반해 보게 됩니다.
- `ON DELETE` 동작이 테이블마다 다릅니다. `order_items`/`payments`/`reviews` 는 `CASCADE`(부모가 지워지면 같이 삭제), `categories` 의 자기참조는 `RESTRICT`(자식이 있으면 삭제 거부)입니다. 이 차이를 Step 13 에서 직접 확인합니다.

```sql file="./01_schema.sql"
```

### 02_seed_master.sql

사람이 눈으로 읽을 수 있는 **고정 마스터 데이터**를 넣습니다. `01_schema.sql` 다음에 실행하며, `USE shop` → `SET FOREIGN_KEY_CHECKS = 0` → 전체 `TRUNCATE` → 재적재 순서로 진행됩니다. FK 체크를 잠시 끄는 이유는 부모/자식 관계 때문에 `TRUNCATE` 가 거부되는 것을 피하기 위해서이고, 적재 직전에 `SET FOREIGN_KEY_CHECKS = 1` 로 다시 켭니다.

- **tally**: `SET SESSION cte_max_recursion_depth = 100000` 을 먼저 지정한 뒤 `WITH RECURSIVE seq AS (SELECT 1 ... WHERE n < 10000)` 으로 1~10000 을 만들어 넣습니다. MySQL 의 기본 재귀 한도는 1000 이라서 이 설정이 없으면 "Recursive query aborted" 에러가 납니다. 재귀 CTE 의 원리는 Step 09 에서 설명합니다.
- **categories**: 대분류를 1~5, 소분류를 11·12·13(패션), 21·22·23(디지털) 처럼 **부모 번호 + 순번**으로 지어 두었습니다. `parent_id` 를 눈으로 따라가기 쉬우라고 일부러 이렇게 만든 것입니다.
- **customers**: 30명. `grade`(BRONZE/SILVER/GOLD/VIP), `city`(서울·부산·대구 등), `birth_date` 가 골고루 섞여 있어 Step 06 의 `GROUP BY` 실습에 바로 쓸 수 있습니다. `phone` 이 `NULL` 인 고객(7·14·28번)과 `points` 가 `0` 인 고객(7·17·29번)이 섞여 있는데, 이는 **NULL 과 0 의 차이**를 체감시키기 위한 의도적 배치입니다.
- **products**: 40개. `attrs` JSON 에는 카테고리마다 다른 키가 들어 있습니다(노트북은 `cpu`/`ram_gb`, 식품은 `origin`/`organic`). 22번 'USB-C 허브'와 29번 '프리미엄 라면'은 **`attrs` 가 `NULL`** 인데, JSON 함수가 NULL 을 어떻게 다루는지 확인하는 재료입니다. 재고 `stock` 이 0 이면서 `status = 'SOLD_OUT'` 인 상품(4·27번), `HIDDEN` 상태인 상품(8번)도 조건 검색 실습용입니다.
- **employees**: 18명, 4단계 조직도(CEO → 본부장 → 팀장 → 팀원). 파일 상단 주석의 트리 그림과 데이터가 정확히 일치하므로, 재귀 CTE 결과가 맞는지 대조해 볼 수 있습니다.
- 마지막 `SELECT` 는 각 테이블 건수를 한 줄로 찍어 줍니다. `categories 17 / customers 30 / products 40 / employees 18 / tally 10000` 이 나오면 정상입니다.

```sql file="./02_seed_master.sql"
```

### 03_seed_orders.sql

주문·주문상세·결제·후기를 **계산으로 생성**합니다. 이 파일의 핵심은 `RAND()` 를 쓰지 않는다는 점입니다. `(t.n * 17) % 30` 처럼 나머지 연산으로 값을 뽑기 때문에 실행할 때마다 항상 동일한 데이터가 나오고, 그래서 교재의 예제 출력과 여러분의 결과가 일치합니다.

- **orders 600건**: `FROM tally t ... WHERE t.n <= 600` 으로 600행을 만들고, `JOIN customers c ON c.customer_id = 1 + (t.n * 17) % 30` 으로 고객을 고르게 배정합니다. 주문일은 `DATE_ADD('2024-01-01', INTERVAL (t.n * 37) % 730 DAY)` 로 2024-01-01 부터 730일 구간에 흩뿌리고, 시/분까지 `% 24`, `% 60` 로 채웁니다. 상태는 `ELT(1 + (t.n * 7) % 10, 'DELIVERED','DELIVERED','PAID',...)` — **ELT 목록에 'DELIVERED' 를 4번 넣어** 40% 비중을 만든 것이 요령입니다.
- **order_items**: `JOIN tally t ON t.n <= 1 + (o.order_id % 3)` 이 핵심입니다. 주문 1건이 tally 와 조인되며 **1~3행으로 늘어나** 주문당 상품 1~3개가 생깁니다. `unit_price` 에는 `p.price` 를 그대로 복사하는데, 이는 "주문 시점 가격 스냅샷"을 흉내 낸 것입니다. 나중에 상품 가격이 바뀌어도 과거 주문 금액은 그대로여야 하기 때문입니다.
- **total_amount 갱신**: `UPDATE orders o SET o.total_amount = (SELECT COALESCE(SUM(oi.quantity * oi.unit_price), 0) FROM order_items oi WHERE oi.order_id = o.order_id)` — 상관 서브쿼리 UPDATE 의 교과서적 예시이고, Step 11 에서 다시 다룹니다.
- **payments**: `WHERE o.status <> 'PENDING' AND o.total_amount > 0` 이라서 **PENDING 주문에는 결제가 아예 없습니다.** 이것이 Step 07 의 `LEFT JOIN ... IS NULL` 과 Step 08 의 `NOT EXISTS` 실습 재료입니다. `CANCELLED` 주문의 결제는 `IF(o.status = 'CANCELLED', 'REFUNDED', 'DONE')` 로 환불 상태가 됩니다.
- **reviews**: `WHERE o.status = 'DELIVERED' AND o.order_id % 3 = 0 AND oi.order_item_id % 2 = 0` — 배송완료 주문의 일부에만 후기가 달립니다. 평점은 `ELT(1 + (oi.order_item_id % 10), 5,4,5,3,4,5,2,4,5,1)` 로 **4~5점에 몰리는 현실적인 분포**를 만듭니다.
- 마지막 두 `SELECT` 가 건수와 상태별 분포를 출력하니, 숫자가 위 설명과 맞는지 확인하고 넘어가세요.

```sql file="./03_seed_orders.sql"
```

### 04_seed_big.sql

`access_logs` 테이블에 **100만 행**을 만듭니다. `./install.sh --big` 을 줄 때만 실행되며, Step 15(인덱스) 이후에 필요합니다. 600건짜리 `orders` 로는 인덱스 효과가 보이지 않기 때문입니다 — 행이 적으면 옵티마이저가 풀스캔을 고르는 게 실제로 더 빠르고, 그래서 `EXPLAIN` 을 봐도 배울 게 없습니다.

- 테이블 정의에 **보조 인덱스가 하나도 없습니다.** `PRIMARY KEY (log_id)` 뿐입니다. 이것은 실수가 아니라 의도입니다. Step 15/16 에서 인덱스 없는 상태의 느린 쿼리를 먼저 측정하고, 직접 인덱스를 붙여 개선 폭을 눈으로 확인하기 위한 출발점입니다.
- 100만 행을 한 번에 넣으면 언두 로그가 비대해지므로 `CREATE PROCEDURE gen_access_logs(IN total_rows INT, IN batch_size INT)` 를 만들어 `CALL gen_access_logs(1000000, 100000)` — **10만 행씩 10배치**로 나눠 넣고 배치마다 `COMMIT` 합니다. 저장 프로시저와 `DELIMITER $$` 문법은 Step 20 에서 자세히 다룹니다.
- 여기서도 재귀 CTE 를 쓰므로 `SET SESSION cte_max_recursion_depth = 2000000` 이 필요합니다. 값을 낮추면 배치 생성이 실패합니다.
- 로그 값은 전부 나머지 연산으로 만듭니다. `status_code` 는 `ELT(...)` 목록에 200 을 15개, 나머지(304/400/404/500/503)를 5개 넣어 **정상 응답 75% 의 현실적인 분포**를 만듭니다. `logged_at` 은 `TIMESTAMPADD(SECOND, ((done + i) * 29) % 63072000, '2024-01-01 00:00:00')` 로 2024-01-01 부터 2년(63,072,000초) 구간에 퍼집니다 — Step 21 의 날짜 파티셔닝 실습이 여기에 기댑니다.
- 생성이 끝나면 `DROP PROCEDURE gen_access_logs` 로 프로시저를 정리하고 `ANALYZE TABLE access_logs` 로 통계를 갱신합니다. 통계가 오래되면 옵티마이저가 엉뚱한 실행 계획을 고르는데, 그 의미는 Step 16 에서 설명합니다.
- **소요 시간 20초~1분**입니다. 멈춘 게 아니니 기다리세요. 끝나면 `information_schema.tables` 를 조회해 데이터/인덱스 크기를 MB 단위로 보여 줍니다. 인덱스를 붙이기 전이라 `index_mb` 가 0 에 가깝다는 점을 기억해 두고, Step 15 이후에 다시 조회해 비교해 보세요.

```sql file="./04_seed_big.sql"
```
