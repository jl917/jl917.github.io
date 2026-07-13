# Step 20 — 저장 프로그램

> **학습 목표**
> - 저장 프로시저(IN/OUT/INOUT)와 저장 함수(DETERMINISTIC/READS SQL DATA)를 만들고 호출한다
> - 제어문(IF/CASE/WHILE/LOOP/REPEAT), 커서와 핸들러(DECLARE CONTINUE HANDLER)를 쓴다
> - SIGNAL/RESIGNAL 로 사용자 정의 에러를 던지고, EXIT HANDLER 로 잡아 롤백한다
> - 트리거(BEFORE/AFTER, OLD/NEW)로 재고 차감과 감사로그를 구현한다
> - 이벤트 스케줄러(EVENT)로 작업을 예약하고, 저장 프로그램의 **실무 한계**를 이해한다
>
> **선행 스텝**: Step 19
> **예상 소요**: 90분

> ⚠️ **안전 규칙**
> 모든 객체와 테이블에 **`s20_` 접두사**를 씁니다. 공용 테이블은 변경하지 않습니다.
> 반드시 `mysql` 클라이언트로 실행하세요 (`DELIMITER` 를 사용합니다).
> ```bash
> mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql
> ```
> 실습이 끝나면 `cleanup.sql` 로 전부 삭제합니다.

> **이 서버의 중요한 전제**
> `practice.sql` 첫 부분이 `products` 의 일부를 복사해 `s20_products`, `s20_audit`, `s20_order_items` 를 만듭니다.

---

## 20-1. DELIMITER — 왜 필요한가

저장 프로그램의 본문(body)에는 `;` 로 끝나는 문장이 여러 개 들어갑니다.
그런데 클라이언트는 `;` 를 만나면 "문장 끝"으로 알고 서버에 보내버립니다. 그러면 `CREATE PROCEDURE` 가 중간에 잘립니다.

그래서 **문장 구분자를 잠시 다른 것(`//`)으로 바꿔** 프로그램 전체를 한 덩어리로 보냅니다.

```sql
DELIMITER //
CREATE PROCEDURE ... BEGIN
  ... ;      -- 이 세미콜론은 이제 문장 끝이 아님
  ... ;
END//        -- 여기가 진짜 끝
DELIMITER ;  -- 원래대로 복구
```

> ⚠️ **함정** — `DELIMITER` 는 `mysql` **클라이언트 명령어**입니다. SQL 이 아닙니다.
> 그래서 애플리케이션의 DB 드라이버(JDBC 등)에 `DELIMITER //` 를 그대로 보내면 에러가 납니다.
> 애플리케이션에서 프로시저를 만들 땐 드라이버의 멀티스테이트먼트 옵션을 쓰거나 구분자 없이 한 문장으로 보냅니다.

---

## 20-2. 저장 함수 — DETERMINISTIC / READS SQL DATA (필수 선언)

함수는 값을 하나 **반환**하며 SQL 안에서 컬럼처럼 쓸 수 있습니다.

> ⚠️ **함정 (이 서버에서 반드시 걸림)** — 특성 선언 없이는 함수를 못 만든다
> 이 서버는 **바이너리 로깅이 켜져 있고** `log_bin_trust_function_creators = 0` 입니다.
> 이 상태에서 함수를 만들면 다음 에러가 납니다.
> ```
> ERROR 1418 (HY000): This function has none of DETERMINISTIC, NO SQL, or
> READS SQL DATA in its declaration and binary logging is enabled ...
> ```
> 복제 안전성 때문입니다. 함수에 아래 중 하나를 반드시 선언하세요.
> - **`DETERMINISTIC`** : 같은 입력 → 항상 같은 출력 (예: 세금·마진 계산)
> - **`READS SQL DATA`** : SELECT 만 함 (데이터 변경 없음)
> - **`NO SQL`** : 본문에 SQL 이 없음

```sql
DELIMITER //
CREATE FUNCTION s20_margin_rate(p_price DECIMAL(10,2), p_cost DECIMAL(10,2))
  RETURNS DECIMAL(5,2)
  DETERMINISTIC
BEGIN
  IF p_price = 0 THEN RETURN 0; END IF;
  RETURN ROUND((p_price - p_cost) / p_price * 100, 2);
END//
DELIMITER ;

SELECT product_id, name, price, s20_margin_rate(price, price*0.45) AS margin_pct
FROM s20_products WHERE product_id <= 3;
```

**결과**
```
+------------+-------------------------------+-----------+------------+
| product_id | name                          | price     | margin_pct |
+------------+-------------------------------+-----------+------------+
|          1 | 베이직 옥스퍼드 셔츠          |  39000.00 |      55.00 |
|          2 | 슬림핏 치노 팬츠              |  49000.00 |      55.00 |
|          3 | 라이트 다운 재킷              | 159000.00 |      55.00 |
+------------+-------------------------------+-----------+------------+
```

테이블을 읽는 함수는 `READS SQL DATA` 를 씁니다. 없는 값이면 조용히 NULL 입니다.

```sql
SELECT s20_stock_of(1) AS stock_p1, s20_stock_of(999) AS stock_missing;
```
```
+----------+---------------+
| stock_p1 | stock_missing |
+----------+---------------+
|      120 |          NULL |
+----------+---------------+
```

> ⚠️ **함정 — 함수를 WHERE 에 쓰면 인덱스가 죽는다**
> `WHERE s20_stock_of(product_id) < 30` 처럼 컬럼을 함수로 감싸면 인덱스를 못 타고 전 행에 함수를 실행합니다.
> 게다가 `READS SQL DATA` 함수를 큰 테이블의 SELECT 목록에 넣으면 **행마다 쿼리가 한 번씩** 나가
> N+1 문제가 SQL 안에서 벌어집니다. 함수는 편하지만 성능 함정이 큽니다.

---

## 20-3. 저장 프로시저 — IN / OUT / INOUT

프로시저는 값을 반환하지 않고 `CALL` 로 실행하며, 결과 집합을 내거나 OUT 파라미터로 값을 돌려줍니다.

```sql
DELIMITER //
CREATE PROCEDURE s20_price_stats(IN p_max_id INT, OUT p_cnt INT, OUT p_avg DECIMAL(12,2))
BEGIN
  SELECT COUNT(*), AVG(price) INTO p_cnt, p_avg
  FROM s20_products WHERE product_id <= p_max_id;
END//
DELIMITER ;

CALL s20_price_stats(5, @cnt, @avg);
SELECT @cnt AS cnt, @avg AS avg_price;
```
```
+------+-----------+
| cnt  | avg_price |
+------+-----------+
|    5 |  77000.00 |
+------+-----------+
```

`INOUT` 은 받은 값을 읽고 다시 쓸 수 있습니다.

```sql
SET @t = 100;
CALL s20_add_bonus(@t, 50);
CALL s20_add_bonus(@t, 25);
SELECT @t AS running_total;   -- 175
```

| 모드 | 방향 | 용도 |
|---|---|---|
| `IN` (기본) | 호출자 → 프로시저 | 입력값 |
| `OUT` | 프로시저 → 호출자 | 결과값 (프로시저가 채움) |
| `INOUT` | 양방향 | 받아서 갱신 |

---

## 20-4. 제어문 — IF / CASE / WHILE / LOOP / REPEAT

`CASE` 로 재고를 등급화하는 함수:

```sql
DELIMITER //
CREATE FUNCTION s20_stock_grade(p_stock INT) RETURNS VARCHAR(10) DETERMINISTIC
BEGIN
  DECLARE v_grade VARCHAR(10);
  CASE
    WHEN p_stock = 0   THEN SET v_grade = '품절';
    WHEN p_stock < 30  THEN SET v_grade = '부족';
    WHEN p_stock < 100 THEN SET v_grade = '보통';
    ELSE                    SET v_grade = '충분';
  END CASE;
  RETURN v_grade;
END//
DELIMITER ;
```

**결과**
```
+------------+-------------------------------+-------+--------+
| product_id | name                          | stock | grade  |
+------------+-------------------------------+-------+--------+
|          3 | 라이트 다운 재킷              |    25 | 부족   |
|          4 | 울 니트 스웨터                |     0 | 품절   |
|          9 | 클래식 스니커즈               |   200 | 충분   |
+------------+-------------------------------+-------+--------+
```

`LOOP` + `LEAVE` (라벨로 탈출) 로 팩토리얼:

```sql
DELIMITER //
CREATE FUNCTION s20_factorial(p_n INT) RETURNS BIGINT DETERMINISTIC
BEGIN
  DECLARE v_result BIGINT DEFAULT 1;
  DECLARE v_i INT DEFAULT 1;
  calc: LOOP
    IF v_i > p_n THEN LEAVE calc; END IF;
    SET v_result = v_result * v_i;
    SET v_i = v_i + 1;
  END LOOP calc;
  RETURN v_result;
END//
DELIMITER ;
SELECT s20_factorial(5) AS fact5, s20_factorial(10) AS fact10;
```
```
+-------+---------+
| fact5 | fact10  |
+-------+---------+
|   120 | 3628800 |
+-------+---------+
```

| 반복문 | 조건 검사 시점 | 비고 |
|---|---|---|
| `WHILE ... DO ... END WHILE` | 시작 (0번 실행 가능) | 가장 흔함 |
| `REPEAT ... UNTIL ... END REPEAT` | 끝 (최소 1번 실행) | do-while 형 |
| `LOOP ... END LOOP` | 없음 | `LEAVE` 로 직접 탈출. 라벨 필수 |

> 💡 **실무 팁 — 반복문보다 집합 연산**
> `WHILE` 로 한 행씩 INSERT 하는 코드는 대개 **단일 SQL 로 바꿀 수 있고 수십~수백 배 빠릅니다.**
> 위 `s20_fill_numbers` 같은 반복 INSERT 는 `tally` 조인이나 재귀 CTE(Step 09)로 대체하세요.
> 저장 프로그램의 반복문은 "정말 절차적일 수밖에 없는" 로직에만 쓰는 게 좋습니다.

---

## 20-5. 커서와 핸들러 — DECLARE ... CURSOR / CONTINUE HANDLER

커서는 결과 행을 **하나씩** 훑습니다. 끝에 도달하면 `NOT FOUND` 조건이 발생하므로 `CONTINUE HANDLER` 로 감지합니다.

```sql
DELIMITER //
CREATE PROCEDURE s20_stock_report()
BEGIN
  DECLARE v_done INT DEFAULT 0;
  DECLARE v_id INT; DECLARE v_name VARCHAR(100); DECLARE v_stock INT;
  DECLARE v_lines INT DEFAULT 0; DECLARE v_msg TEXT DEFAULT '';

  DECLARE cur CURSOR FOR SELECT product_id, name, stock FROM s20_products ORDER BY product_id;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;   -- 끝 감지

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_id, v_name, v_stock;
    IF v_done = 1 THEN LEAVE read_loop; END IF;
    SET v_lines = v_lines + 1;
    SET v_msg = CONCAT(v_msg, v_id, ':', v_name, '(', s20_stock_grade(v_stock), ') ');
  END LOOP;
  CLOSE cur;
  SELECT v_lines AS scanned, v_msg AS report;
END//
DELIMITER ;
```

**결과** (일부)
```
scanned = 10
report  = 1:베이직 옥스퍼드 셔츠(충분) 2:슬림핏 치노 팬츠(보통) 3:라이트 다운 재킷(부족) 4:울 니트 스웨터(품절) ...
```

> ⚠️ **함정 — DECLARE 순서가 엄격하다**
> 프로시저 안에서 `DECLARE` 는 정해진 순서를 지켜야 합니다:
> **① 변수/조건 → ② 커서 → ③ 핸들러.** 순서가 틀리면 문법 에러입니다.
> 또 `NOT FOUND` 핸들러가 커서뿐 아니라 **`SELECT ... INTO` 가 0행일 때도 발동**한다는 점을 조심하세요.
> 커서 로직 중간의 다른 SELECT INTO 가 0행이면 `v_done` 이 엉뚱하게 1이 될 수 있습니다.

> 💡 **실무 팁 — 커서는 최후의 수단**
> 커서는 느리고 코드가 장황합니다. "행마다 완전히 다른 처리"가 필요한 게 아니라면 집합 SQL 을 쓰세요.
> 위 리포트도 사실 `GROUP_CONCAT` 한 줄로 됩니다.

---

## 20-6. 에러 처리 — SIGNAL / RESIGNAL / HANDLER

`SIGNAL SQLSTATE '45000'` 으로 사용자 정의 에러를 던집니다. (`45000` = 처리되지 않은 사용자 예외)

```sql
DELIMITER //
CREATE PROCEDURE s20_ship(IN p_id INT, IN p_qty INT)
BEGIN
  DECLARE v_stock INT;
  IF p_qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '수량은 1 이상이어야 합니다';
  END IF;
  SELECT stock INTO v_stock FROM s20_products WHERE product_id = p_id;
  IF v_stock IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '존재하지 않는 상품입니다';
  END IF;
  IF v_stock < p_qty THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '재고가 부족합니다';
  END IF;
  UPDATE s20_products SET stock = stock - p_qty WHERE product_id = p_id;
END//
DELIMITER ;
```

**실제 에러 출력**
```sql
CALL s20_ship(1, 99999);
-- ERROR 1644 (45000): 재고가 부족합니다
CALL s20_ship(999, 1);
-- ERROR 1644 (45000): 존재하지 않는 상품입니다
CALL s20_ship(1, 0);
-- ERROR 1644 (45000): 수량은 1 이상이어야 합니다
```

에러를 **잡아서** 롤백하려면 `DECLARE EXIT HANDLER FOR SQLEXCEPTION` 을 씁니다.
`GET DIAGNOSTICS` 로 에러 메시지를 꺼낼 수 있습니다.

```sql
DELIMITER //
CREATE PROCEDURE s20_ship_safe(IN p_id INT, IN p_qty INT,
                               OUT p_ok INT, OUT p_msg VARCHAR(200))
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 p_msg = MESSAGE_TEXT;
    ROLLBACK;
    SET p_ok = 0;
  END;
  SET p_ok = 1; SET p_msg = 'OK';
  START TRANSACTION;
  CALL s20_ship(p_id, p_qty);   -- 내부 SIGNAL 을 위 핸들러가 잡음
  COMMIT;
END//
DELIMITER ;
```

**결과**
```sql
CALL s20_ship_safe(2, 5, @ok, @msg);      SELECT @ok, @msg;   -- ok=1, msg=OK
CALL s20_ship_safe(2, 99999, @ok, @msg);  SELECT @ok, @msg;   -- ok=0, msg=재고가 부족합니다
```
```
+------+---------------------------+
| ok   | msg                       |
+------+---------------------------+
|    0 | 재고가 부족합니다         |
+------+---------------------------+
```

`RESIGNAL` 은 잡은 에러를 (로그를 남기는 등 처리한 뒤) **다시 던질** 때 씁니다. `SIGNAL` 과 달리 원래 에러 정보를 보존합니다.

---

## 20-7. 트리거 — BEFORE/AFTER, OLD/NEW (재고 차감 & 감사로그)

트리거는 INSERT/UPDATE/DELETE 가 일어날 때 **자동으로** 실행됩니다.

- `NEW` : 새로 들어오는 값 (INSERT/UPDATE). **BEFORE 트리거에서는 수정 가능**
- `OLD` : 기존 값 (UPDATE/DELETE)

| 타이밍 × 이벤트 | NEW | OLD | 주 용도 |
|---|:--:|:--:|---|
| BEFORE INSERT | O | - | 값 검증/보정 |
| AFTER INSERT | O(읽기) | - | 다른 테이블 갱신, 로그 |
| BEFORE UPDATE | O | O | 검증, 변경 감사 |
| BEFORE/AFTER DELETE | - | O | 삭제 로그 |

세 개의 트리거를 겁니다: 주문 수량 검증(BEFORE INSERT), 재고 차감+로그(AFTER INSERT), 재고 음수 방지+변동 로그(BEFORE UPDATE).

```sql
DELIMITER //
CREATE TRIGGER s20_bi_order_items BEFORE INSERT ON s20_order_items FOR EACH ROW
BEGIN
  IF NEW.quantity <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '주문 수량은 1 이상이어야 합니다';
  END IF;
END//

CREATE TRIGGER s20_ai_order_items AFTER INSERT ON s20_order_items FOR EACH ROW
BEGIN
  UPDATE s20_products SET stock = stock - NEW.quantity WHERE product_id = NEW.product_id;
  INSERT INTO s20_audit(action, detail)
  VALUES ('ORDER', CONCAT('상품 ', NEW.product_id, ' 수량 ', NEW.quantity, ' 주문 → 재고 차감'));
END//

CREATE TRIGGER s20_bu_products BEFORE UPDATE ON s20_products FOR EACH ROW
BEGIN
  IF NEW.stock < 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '재고는 음수가 될 수 없습니다';
  END IF;
  IF OLD.stock <> NEW.stock THEN
    INSERT INTO s20_audit(action, detail)
    VALUES ('STOCK', CONCAT('상품 ', NEW.product_id, ' 재고 ', OLD.stock, ' → ', NEW.stock));
  END IF;
END//
DELIMITER ;
```

주문 한 건을 넣으면 트리거들이 연쇄적으로 동작합니다.

```sql
INSERT INTO s20_order_items(product_id, quantity) VALUES (5, 3);
SELECT action, detail FROM s20_audit ORDER BY audit_id;
```

**결과** (재고 60 → 57, 감사로그 2줄)
```
+--------+--------------------------------------------+
| action | detail                                     |
+--------+--------------------------------------------+
| STOCK  | 상품 5 재고 60 → 57                        |
| ORDER  | 상품 5 수량 3 주문 → 재고 차감             |
+--------+--------------------------------------------+
```

> 여기서 **STOCK 로그가 ORDER 로그보다 먼저** 찍힌 것에 주목하세요.
> `AFTER INSERT` 트리거가 먼저 `UPDATE s20_products` 를 실행하는데, 그 UPDATE 가
> `BEFORE UPDATE` 트리거(STOCK 로그)를 **다시** 발동시킵니다. 그게 끝난 뒤에야 ORDER 로그가 찍힙니다.
> **트리거가 트리거를 부르는 이 연쇄가 디버깅을 어렵게 만드는 주범입니다.**

검증 트리거는 SIGNAL 로 작업을 막습니다.

```sql
INSERT INTO s20_order_items(product_id, quantity) VALUES (5, 0);
-- ERROR 1644 (45000): 주문 수량은 1 이상이어야 합니다
UPDATE s20_products SET stock = -1 WHERE product_id = 5;
-- ERROR 1644 (45000): 재고는 음수가 될 수 없습니다
```

> ⚠️ **함정 — 트리거는 "디버깅 지옥"이다**
> - **보이지 않는다.** `INSERT` 한 줄이 다른 테이블 3개를 바꿔도, 코드만 봐서는 알 수 없습니다.
>   신입이 몇 시간을 헤매는 전형적 원인입니다.
> - **연쇄된다.** 위처럼 트리거가 트리거를 부르면 실행 순서를 추적하기 어렵습니다.
> - **성능이 숨는다.** 대량 INSERT 가 느린데 원인이 트리거인 걸 아무도 모릅니다.
> - **에러 처리가 까다롭다.** 트리거 안에서 SIGNAL 이 나면 원래 DML 전체가 롤백됩니다.
> - **한 테이블에 같은 타이밍/이벤트 트리거가 여러 개면** 실행 순서를 `FOLLOWS`/`PRECEDES` 로 지정해야 합니다.

---

## 20-8. 이벤트 스케줄러 — EVENT

이벤트는 예약된 시각/주기에 자동 실행되는 SQL 입니다 (크론과 유사). 전역 `event_scheduler = ON` 이어야 실제로 돕니다.

```sql
SELECT @@event_scheduler;   -- 이 서버는 ON

DELIMITER //
CREATE EVENT s20_heartbeat
ON SCHEDULE EVERY 1 MINUTE STARTS CURRENT_TIMESTAMP
DO BEGIN
  INSERT INTO s20_audit(action, detail) VALUES ('HEARTBEAT', CONCAT('tick @', NOW()));
END//
DELIMITER ;

-- 한 번만 실행
CREATE EVENT s20_once
ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 5 SECOND
DO INSERT INTO s20_audit(action, detail) VALUES ('ONCE', '5초 뒤 1회 실행');
```

**등록된 이벤트 확인**
```sql
SHOW EVENTS WHERE Db='shop' AND Name LIKE 's20_%';
```
```
| Name          | Type      | Execute at          | Interval | Status  |
| s20_heartbeat | RECURRING | NULL                | 1 MINUTE | ENABLED |
| s20_once      | ONE TIME  | 2026-07-13 10:53:29 | NULL     | ENABLED |
```

> 💡 **실무 팁**
> `EVERY` 이벤트는 끄고 켤 수 있습니다: `ALTER EVENT s20_heartbeat DISABLE;`
> 1회성 이벤트는 실행 후 기본으로 자동 삭제됩니다(`ON COMPLETION NOT PRESERVE`).
> **주의**: 이벤트 스케줄러가 `OFF` 면 이벤트는 등록만 되고 절대 실행되지 않습니다. 배포 환경에서 자주 놓칩니다.

---

## 20-9. ★ 저장 프로그램의 장단점과 "쓰지 말아야 할 경우"

### 장점

- **네트워크 왕복 감소** — 여러 쿼리를 서버 한 번 호출로 처리 (지연이 큰 환경에서 유리)
- **데이터 근처에서 실행** — 대량 데이터를 앱으로 안 끌어오고 서버에서 처리
- **권한 캡슐화** — 테이블 직접 권한 없이 프로시저 실행 권한만 줄 수 있음
- **일관된 로직 강제** — 트리거로 감사/제약을 DB 레벨에서 보장

### 단점 / 쓰지 말아야 할 경우

| 상황 | 이유 |
|---|---|
| **비즈니스 로직 대부분을 DB에 넣기** | 버전관리·테스트·코드리뷰·CI 가 애플리케이션 코드보다 훨씬 열악 |
| **복잡한 분기/문자열 처리** | SQL 절차 언어는 디버거가 빈약하고 표현력이 낮음 |
| **트리거로 핵심 로직 숨기기** | "보이지 않는 부작용" → 유지보수 재앙 (20-7 참고) |
| **수평 확장이 중요한 서비스** | 로직이 DB에 몰리면 DB 가 병목. 앱 서버는 늘려도 DB 는 늘리기 어려움 |
| **자주 바뀌는 로직** | 프로시저 배포는 앱 배포보다 롤백/카나리가 어려움 |

> 💡 **현대적 균형점**
> - **DB에 둘 것**: 데이터 무결성 제약(FK/CHECK/UNIQUE), 감사 트리거 같은 "데이터에 밀착한 규칙", 대량 배치 집계
> - **앱에 둘 것**: 비즈니스 규칙, 분기 로직, 외부 연동 — 테스트와 버전관리가 되는 곳
> 트리거는 **로깅/감사/무결성 보조**처럼 "조용하고 단순한" 용도로 한정하고,
> 재고 차감 같은 핵심 로직까지 트리거에 넣는 것은 신중히 결정하세요.
> (이 스텝의 재고 차감 트리거는 **학습용 예제**입니다. 실무에선 앱의 트랜잭션 안에서 명시적으로 처리하는 편이 추적이 쉽습니다.)

---

## 정리

| 객체 | 생성 | 호출/발동 | 특성 선언 |
|---|---|---|---|
| 함수(FUNCTION) | `CREATE FUNCTION ... RETURNS t` | SQL 안에서 `f(x)` | **DETERMINISTIC/READS SQL DATA/NO SQL 필수** |
| 프로시저(PROCEDURE) | `CREATE PROCEDURE` | `CALL p(...)` | IN/OUT/INOUT |
| 트리거(TRIGGER) | `CREATE TRIGGER ... BEFORE/AFTER` | DML 시 자동 | OLD/NEW |
| 이벤트(EVENT) | `CREATE EVENT ... ON SCHEDULE` | 스케줄러가 자동 | event_scheduler=ON 필요 |

### 제어·에러 구문

| 구문 | 용도 |
|---|---|
| `IF/CASE` | 분기 |
| `WHILE/REPEAT/LOOP+LEAVE` | 반복 |
| `DECLARE ... CURSOR` + `CONTINUE HANDLER FOR NOT FOUND` | 행 단위 순회 |
| `SIGNAL SQLSTATE '45000'` | 사용자 에러 던지기 |
| `DECLARE EXIT HANDLER FOR SQLEXCEPTION` + `GET DIAGNOSTICS` | 에러 잡기 + 롤백 |
| `RESIGNAL` | 잡은 에러 다시 던지기 |

### 이 스텝의 함정 3가지

1. **함수엔 DETERMINISTIC/READS SQL DATA 필수** (log_bin 켜진 서버, ERROR 1418)
2. **트리거는 보이지 않고 연쇄된다** — 재고 차감 트리거가 재고 감사 트리거를 또 부른다
3. **DELIMITER 는 클라이언트 명령** — 앱 드라이버엔 안 통한다 / 커서 DECLARE 순서 엄격

---

## 정리(cleanup)

```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < cleanup.sql
```

---

## 연습문제

`exercise.sql` 의 6문제를 풀어 보세요. 정답은 `solution.sql`.

1. 할인가 계산 함수 (DETERMINISTIC)
2. 등급별 배송비 함수 (CASE)
3. 재입고 프로시저 (OUT 파라미터 + 감사로그)
4. 커서로 저재고 상품 목록 만들기
5. 주문 취소 트리거 (AFTER DELETE 로 재고 복원)
6. SIGNAL 로 잘못된 입력 막기

---

## 코스 완주를 축하합니다

Step 17~20 을 통해 MySQL 8 의 분석 함수(윈도우), 반정형 데이터(JSON), 동시성(트랜잭션·락), 서버측 프로그래밍(저장 프로그램)까지 마쳤습니다.

---

## 실습 파일

이 스텝은 SQL 스크립트 네 개로 진행합니다. 먼저 `practice.sql` 로 `s20_` 사본 테이블과 함수·프로시저·트리거·이벤트를 한 번에 만들며 본문 20-1 ~ 20-8 을 따라가고, 그다음 `exercise.sql` 의 빈칸 6문제를 직접 채워 본 뒤 `solution.sql` 로 답을 맞춰 봅니다. 마지막에 `cleanup.sql` 로 만들어 둔 `s20_` 객체를 전부 지웁니다. 네 스크립트 모두 `DELIMITER` 를 쓰므로 **반드시 `mysql` 클라이언트로 실행**해야 합니다 (GUI 툴이나 JDBC 로는 `DELIMITER` 가 통하지 않습니다).

### practice.sql

본문 전체를 그대로 재현하는 메인 실습 스크립트입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop --table < practice.sql` 로 실행하세요.

> 아래 대괄호 번호(`[20-0]`, `[20-1]` …)는 **스크립트 안의 주석 구분자**입니다. 본문 헤딩 번호와는 커서 절부터 한 칸씩 어긋나니 주의하세요. 예를 들어 스크립트의 `[20-5] 에러 처리` 는 본문 **20-6** 에 해당합니다.

- **[20-0] 사본 만들기** — `INSERT INTO s20_products ... SELECT ... FROM products WHERE product_id <= 10` 으로 공용 `products` 에서 10개만 복사합니다. 이후 모든 재고 차감·가격 변경은 이 사본에만 일어나므로 공용 테이블은 안전합니다. `s20_audit`(감사로그), `s20_order_items`(주문) 도 여기서 만듭니다. 스크립트 맨 앞의 `DROP TABLE IF EXISTS` 3줄 덕분에 여러 번 재실행해도 됩니다(단, **이전 실습 데이터는 날아갑니다**).
- **[20-1] 함수** — `s20_margin_rate` 는 `DETERMINISTIC`, 테이블을 읽는 `s20_stock_of` 는 `READS SQL DATA` 로 선언합니다. 이 선언을 지우면 이 서버(`log_bin` ON, `log_bin_trust_function_creators=0`)에서는 곧바로 **ERROR 1418** 이 납니다.
- **[20-2]~[20-3] 프로시저·제어문** — `s20_price_stats`(OUT 2개), `s20_add_bonus`(INOUT), `CASE` 를 쓰는 `s20_stock_grade`, `WHILE` 반복 INSERT 인 `s20_fill_numbers`, `LOOP`+`LEAVE` 인 `s20_factorial` 을 만듭니다. `s20_fill_numbers` 는 임시 테이블 `s20_nums` 에 1행씩 넣는 **일부러 비효율적인 예제**로, 집합 SQL 로 바꿔야 한다는 본문 20-4 의 팁과 짝을 이룹니다.
- **[20-4] 커서** — `s20_stock_report` 프로시저입니다. `DECLARE` 를 **변수 → 커서 → 핸들러** 순으로 쓰는 규칙, `FETCH` 직후에 `v_done` 을 검사해 `LEAVE` 하는 표준 패턴을 그대로 보여 줍니다. `CALL s20_stock_report();` 로 10개 상품의 등급 요약 한 줄이 나옵니다.
- **[20-5] 에러 처리** — 활성화된 호출은 세 개입니다: 정상 출고인 `CALL s20_ship(1, 10);` 과, `s20_ship_safe` 를 성공(`2, 5`)·실패(`2, 99999`)로 각각 한 번씩 부르는 줄입니다. 실패 호출은 `EXIT HANDLER` 가 잡아 롤백하므로 스크립트를 멈추지 않고 `@ok=0` 을 돌려줍니다. 반면 **에러를 그대로 터뜨리는 세 줄**(`s20_ship(1, 99999)`, `s20_ship(999, 1)`, `s20_ship(1, 0)`)은 **주석 처리**되어 있습니다. 스크립트가 중간에 멈추지 않게 하려는 것이니, 에러 메시지를 직접 보려면 한 번에 하나씩 주석을 풀어 실행하세요.
- **[20-6] 트리거** — `INSERT INTO s20_order_items(product_id, quantity) VALUES (5, 3);` 한 줄이 `AFTER INSERT` → `UPDATE s20_products` → `BEFORE UPDATE` 로 연쇄하며 감사로그가 **STOCK, ORDER 순서로** 남는 것을 눈으로 확인하는 부분입니다. 트리거가 `SIGNAL` 로 작업을 막는 두 줄(수량 0 주문, 재고 -1 UPDATE)도 여기 있지만 **주석 처리**되어 있으니 필요할 때 풀어 보세요.
- **[20-7] 이벤트** — `s20_heartbeat`(1분 주기)와 `s20_once`(5초 뒤 1회)를 만들고 `SHOW EVENTS` 로 확인한 뒤, **`s20_heartbeat` 만** `ALTER EVENT s20_heartbeat DISABLE;` 로 끕니다. 그대로 두면 1분마다 감사로그가 계속 쌓이기 때문입니다. `s20_once` 는 끄지 않으므로 실행 5초 뒤 `s20_audit` 에 `ONCE` 행이 한 줄 남습니다(그리고 `ON COMPLETION NOT PRESERVE` 기본값에 따라 스스로 사라집니다). 두 이벤트의 최종 삭제는 `cleanup.sql` 의 몫입니다.
- **[20-8] 정의 조회** — `information_schema.ROUTINES` 와 `information_schema.TRIGGERS` 를 조회해 방금 만든 `s20_` 루틴·트리거 목록을 확인합니다. `IS_DETERMINISTIC` 컬럼에서 어떤 함수가 `DETERMINISTIC` 으로 선언됐는지도 눈으로 볼 수 있습니다.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 6개의 **빈 껍데기**입니다. 각 문제 아래 `-- 여기에 작성` 자리에 직접 코드를 채워 넣으세요.

- `practice.sql` 을 먼저 실행해 `s20_products` / `s20_audit` / `s20_order_items` 가 있는 상태에서 풀어야 합니다. 문제 3·4·5 가 이 사본 테이블을 직접 건드리기 때문입니다.
- 연습문제에서 만드는 객체는 `s20ex_` 접두사를 씁니다. `practice.sql` 이 만든 `s20_` 객체와 이름이 겹치지 않도록 하기 위한 규칙입니다.
- 문제 1·2 는 함수라서 `DETERMINISTIC` 선언을 빠뜨리면 ERROR 1418 이 납니다. 문제 3·6 은 `SIGNAL SQLSTATE '45000'`, 문제 4 는 커서 + `CONTINUE HANDLER FOR NOT FOUND`, 문제 5 는 `AFTER DELETE` 트리거에서 `OLD.quantity` 를 쓰는 것이 핵심입니다.
- 이 파일도 `DELIMITER` 를 쓰는 답안을 작성하게 되므로 `mysql` 클라이언트로 실행하세요.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 6문제의 모범 답안과 해설입니다. 스스로 풀어 본 뒤에 비교하세요.

- 맨 앞에서 `s20_products` / `s20_audit` / `s20_order_items` 를 **다시 만듭니다.** 즉 이 파일을 실행하면 `practice.sql` 로 쌓아 둔 감사로그와 재고 변동이 전부 초기화됩니다. 순서에 주의하세요.
- [정답 3] `s20ex_restock` 은 `UPDATE` 직후 `ROW_COUNT() = 0` 으로 "존재하지 않는 상품"을 판별합니다. `SELECT ... INTO` 로 먼저 확인하는 방식보다 쿼리가 한 번 적습니다. [정답 6] `s20ex_set_price` 도 같은 패턴입니다.
- [정답 4] `CONCAT_WS(', ', NULLIF(v_out,''), ...)` 는 첫 항목 앞에 콤마가 붙지 않게 하는 관용구입니다. `NULLIF` 로 빈 문자열을 NULL 로 만들면 `CONCAT_WS` 가 그 자리를 건너뜁니다.
- [정답 5] 는 문제에서 요구한 `s20ex_ad_order_items`(AFTER DELETE) 뿐 아니라 `s20ex_ai_order_items`(AFTER INSERT) 도 같이 만듭니다. 그래야 상품 9의 재고가 `200 → 195 → 200` 으로 왕복하는 데모가 성립합니다. `practice.sql` 이 만든 `s20_ai_order_items` 와 이름이 겹쳐 재고가 이중 차감될 걱정은 없습니다 — 이 파일 맨 앞의 `DROP TABLE s20_order_items` 가 **테이블과 함께 그 테이블의 트리거까지** 지우기 때문입니다(MySQL 은 테이블을 DROP 할 때 딸린 트리거도 같이 DROP 합니다).
- 파일 끝에서 `s20ex_` 객체는 스스로 정리하지만, `s20_` 공통 객체는 남깁니다. 그건 `cleanup.sql` 의 몫입니다.

```sql file="./solution.sql"
```

### cleanup.sql

실습이 끝난 뒤 `s20_` 로 시작하는 모든 것을 지우는 정리 스크립트입니다. `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < cleanup.sql` 로 실행합니다.

- **삭제 순서가 의미 있습니다.** 이벤트 → 트리거 → 프로시저 → 함수 → 테이블 순인데, 특히 `s20_heartbeat` 이벤트를 먼저 지우지 않으면 테이블이 사라진 뒤에도 스케줄러가 깨어나 `s20_audit` 에 INSERT 를 시도하며 에러 로그를 남길 수 있습니다.
- 모든 문장이 `DROP ... IF EXISTS` 라서 일부만 만들어진 상태에서 실행해도 안전하고, 여러 번 돌려도 됩니다.
- `DROP FUNCTION IF EXISTS s20_bad;` 한 줄이 눈에 띌 텐데, 이는 20-2 의 함정(특성 선언 없이 함수를 만들어 ERROR 1418 을 재현해 보는 실험)을 직접 해 봤을 경우를 대비한 **보험용 정리**입니다. 실제로 그 함수는 생성 자체가 실패하므로 보통은 아무 일도 하지 않습니다.
- **파괴적입니다.** `DROP TABLE` 로 `s20_products` / `s20_audit` / `s20_order_items` 를 지우므로 실습 기록이 모두 사라집니다. 다만 공용 `products` 는 건드리지 않으니 다른 스텝에는 영향이 없습니다.

```sql file="./cleanup.sql"
```
