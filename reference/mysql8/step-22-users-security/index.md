# Step 22 — 사용자와 보안

> **학습 목표**
> - 사용자를 만들고 인증 플러그인(`caching_sha2_password` vs `mysql_native_password`)을 이해한다
> - 권한을 4개 레벨(글로벌/DB/테이블/컬럼)로 나누어 **최소권한 원칙**대로 부여한다
> - MySQL 8.0 신기능 **롤(ROLE)** 로 권한을 역할 단위로 관리한다
> - 계정 잠금(`FAILED_LOGIN_ATTEMPTS`), 비밀번호 만료·재사용 제한을 건다
> - 뷰로 컬럼을 마스킹하고, SQL Injection 이 DB 관점에서 왜 치명적인지 이해한다
>
> **선행 스텝**: [Step 14 — 뷰](../step-14-views-generated/index.md)
> **예상 소요**: 60분

> ⚠️ 이 스텝의 SQL 은 **root** 로 실행합니다: `mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop`
> 실습이 끝나면 만든 계정/롤/뷰를 **전부 삭제**합니다(공용 서버이므로).

---

## 22-1. 사용자 = `'이름'@'호스트'`

MySQL 의 사용자는 **이름과 접속 호스트의 조합**입니다. `'app'@'10.0.0.5'` 와 `'app'@'%'` 는 **다른 계정**입니다.

```sql
CREATE USER 'app_ro'@'%'     IDENTIFIED WITH caching_sha2_password BY 'Ro#Pass123';
CREATE USER 's22_native'@'%' IDENTIFIED WITH mysql_native_password  BY 'Nat#Pass123';

SELECT user, host, plugin FROM mysql.user
WHERE user IN ('app_ro','app_rw','s22_native') ORDER BY user;
```

**결과**
```
+------------+------+-----------------------+
| user       | host | plugin                |
+------------+------+-----------------------+
| app_ro     | %    | caching_sha2_password |
| app_rw     | %    | caching_sha2_password |
| s22_native | %    | mysql_native_password |
+------------+------+-----------------------+
```

| 인증 플러그인 | 설명 |
|---|---|
| **`caching_sha2_password`** | MySQL **8.0 기본값**. SHA-256 해시 + 서버측 캐시. TLS 또는 RSA 키 교환으로 비밀번호 보호. **이걸 쓰세요.** |
| `mysql_native_password` | 5.x 시절 방식(SHA-1 기반). 구형 드라이버 호환용. 8.0 에서 **비권장**, 8.4 에서 기본 비활성, 9.x 에서 제거 예정. |

> ⚠️ **함정** — `'app'@'%'` 는 "어느 IP 에서든 접속 허용"입니다. 편하지만 위험합니다.
> 운영에서는 애플리케이션 서버 대역(`'app'@'10.0.1.%'`)으로 **호스트를 좁히세요.** 이것도 최소권한입니다.

> 💡 **실무 팁** — `caching_sha2_password` 는 **첫 접속 시 TLS 나 RSA 공개키**가 필요합니다.
> 평문 비TLS 연결에서 "Authentication plugin ... reported error" 가 나면 인증 방식이 원인일 때가 많습니다.
> 드라이버가 caching_sha2 를 지원하는지 먼저 확인하세요(옛 커넥터는 지원 안 함).

---

## 22-2. 롤(ROLE) — 8.0 신기능

8.0 이전에는 권한을 **사람마다 일일이** 부여했습니다. 담당자 10명이면 GRANT 를 10번. 담당자가 바뀌면 또 REVOKE/GRANT.
8.0 의 **롤**은 권한을 "역할"에 모아두고, **역할을 사람에게** 줍니다.

```sql
CREATE ROLE s22_reader, s22_writer, s22_analyst;
```

```
읽기전용 담당자 ──┐
             ├──> [s22_reader 롤] ──> GRANT SELECT ON shop.*
백엔드 서버   ────┤
             └──> [s22_writer 롤] ──> GRANT SELECT,INSERT,UPDATE,DELETE ON shop.*
```

---

## 22-3. 권한 레벨 4단계 — 최소권한 원칙

권한은 **넓은 것부터 좁은 것**까지 4단계로 줄 수 있습니다. **항상 가장 좁게** 주세요.

```sql
GRANT SELECT                         ON *.*            TO ...;  -- ① 글로벌 (모든 DB) — 거의 쓰지 마라
GRANT SELECT, INSERT, UPDATE, DELETE ON shop.*         TO s22_writer;  -- ② DB 레벨
GRANT SELECT                         ON shop.orders    TO s22_analyst; -- ③ 테이블 레벨
GRANT SELECT (customer_id, grade, city, points)
                                     ON shop.customers TO s22_analyst; -- ④ 컬럼 레벨
```

**컬럼 레벨 권한이 실제로 막는지** 확인해 봅시다. `s22_analyst` 롤을 받은 `s22_alice` 로 로그인:

```bash
mysql -h127.0.0.1 -P3307 -us22_alice -pAlice#123 shop \
  -e "SELECT customer_id, grade, city, points FROM customers LIMIT 3;"
```
```
+-------------+--------+--------+--------+
| customer_id | grade  | city   | points |
+-------------+--------+--------+--------+
|           1 | VIP    | 서울   |  12500 |
|           2 | GOLD   | 서울   |   8300 |
|           3 | SILVER | 부산   |   3100 |
+-------------+--------+--------+--------+
```

허용되지 않은 컬럼(`email`)이나 `SELECT *` 는 거부됩니다:

```bash
mysql ... -us22_alice ... -e "SELECT email FROM customers LIMIT 1;"
# ERROR 1143 (42000): SELECT command denied to user 's22_alice'@'...' for column 'email' in table 'customers'

mysql ... -us22_alice ... -e "SELECT * FROM customers LIMIT 1;"
# ERROR 1142 (42000): SELECT command denied to user 's22_alice'@'...' for table 'customers'

mysql ... -us22_alice ... -e "SELECT * FROM products LIMIT 1;"
# ERROR 1142 (42000): SELECT command denied ... for table 'products'
```

> 💡 **실무 팁** — `SELECT *` 가 거부되는 이유: `*` 는 `email`, `phone` 을 포함하는데 그 컬럼 권한이 없기 때문입니다.
> 컬럼 레벨 권한을 쓰면 애플리케이션이 **컬럼을 명시**하도록 강제되는 부수 효과가 있습니다(좋은 습관).

---

## 22-4. 롤 부여 + 기본 롤 활성화 (여기서 많이 막힌다)

```sql
GRANT s22_reader             TO 'app_ro'@'%';
GRANT s22_reader, s22_writer TO 'app_rw'@'%';

-- ★ 롤은 "부여"만으로 켜지지 않는다. 로그인 시 자동 활성화되도록 기본 롤 지정
SET DEFAULT ROLE ALL TO 'app_ro'@'%', 'app_rw'@'%';
```

> ⚠️ **함정** — `GRANT role TO user` 만 하고 `SET DEFAULT ROLE` 를 빠뜨리면,
> 그 유저는 로그인해도 **아무 권한이 없습니다**(`CURRENT_ROLE()` 가 `NONE`).
> 세션에서 직접 `SET ROLE ...` 로 켜야 하죠. 서버 전역 `activate_all_roles_on_login=ON` 으로 바꾸면
> 항상 켜지지만, 공용 서버의 전역 설정은 건드리지 않습니다.

```sql
SHOW GRANTS FOR 'app_ro'@'%';
```
**결과**
```
+----------------------------------------+
| Grants for app_ro@%                    |
+----------------------------------------+
| GRANT USAGE ON *.* TO `app_ro`@`%`     |     ← USAGE = "로그인만 가능, 권한 없음"
| GRANT `s22_reader`@`%` TO `app_ro`@`%` |
+----------------------------------------+
```

`SHOW GRANTS ... USING <role>` 로 **특정 롤을 켠 상태의 유효 권한**을 펼쳐 볼 수 있습니다:

```sql
SHOW GRANTS FOR 'app_rw'@'%' USING s22_writer;
```
```
+------------------------------------------------------------------+
| Grants for app_rw@%                                              |
+------------------------------------------------------------------+
| GRANT USAGE ON *.* TO `app_rw`@`%`                               |
| GRANT SELECT, INSERT, UPDATE, DELETE ON `shop`.* TO `app_rw`@`%` |
| GRANT `s22_reader`@`%`,`s22_writer`@`%` TO `app_rw`@`%`          |
+------------------------------------------------------------------+
```

읽기 전용 계정이 쓰기를 시도하면 거부됩니다:

```bash
mysql ... -uapp_ro -pRo#Pass123 shop \
  -e "INSERT INTO reviews(product_id,customer_id,rating,created_at) VALUES(1,1,5,NOW());"
# ERROR 1142 (42000): INSERT command denied to user 'app_ro'@'...' for table 'reviews'
```

---

## 22-5. 뷰를 통한 컬럼 마스킹

민감정보를 원본 테이블 대신 **가려진 뷰**로만 노출합니다. 분석가에게는 `customers` 원본 권한을 안 주고, 뷰만 줍니다.

```sql
CREATE VIEW shop.s22_customers_masked AS
SELECT
  customer_id, name,
  CONCAT(LEFT(email, 2), '***@', SUBSTRING_INDEX(email, '@', -1)) AS email_masked,
  CONCAT(LEFT(phone, 3), '-****-', RIGHT(phone, 4))               AS phone_masked,
  grade, city
FROM shop.customers;

GRANT SELECT ON shop.s22_customers_masked TO s22_analyst;
```

**결과** (분석가 계정으로 조회)
```
+-------------+-----------+-------------------+---------------+--------+--------+
| customer_id | name      | email_masked      | phone_masked  | grade  | city   |
+-------------+-----------+-------------------+---------------+--------+--------+
|           1 | 김민수    | ki***@example.com | 010-****-0001 | VIP    | 서울   |
|           2 | 이지은    | le***@example.com | 010-****-0002 | GOLD   | 서울   |
|           3 | 박철수    | pa***@example.com | 010-****-0003 | SILVER | 부산   |
+-------------+-----------+-------------------+---------------+--------+--------+
```

뷰는 **정의자(definer) 권한**으로 실행되므로, 뷰를 통해서는 원본을 읽지만 분석가가 직접 `customers` 를 SELECT 하면 거부됩니다.
이것이 뷰 기반 접근 통제의 핵심입니다.

> 💡 **실무 팁** — MySQL 8.0.34+ 에는 정식 **Data Masking** 컴포넌트(엔터프라이즈)가 있지만,
> 뷰 마스킹은 어느 에디션에서나 됩니다. 마스킹 규칙이 자주 바뀌면 뷰가 관리하기 쉽습니다.

---

## 22-6. 비밀번호 정책 & 계정 잠금

서버 전역 정책(`validate_password` 컴포넌트)은 공용 서버라 설치하지 않습니다. (참고: `INSTALL COMPONENT 'file://component_validate_password';` 로 설치하면 길이/대소문자/숫자/특수문자 규칙을 전역 강제)
대신 **계정 단위 정책**은 자유롭게 겁니다.

### 계정 잠금 — `FAILED_LOGIN_ATTEMPTS` (8.0.19+)

```sql
CREATE USER 's22_lock'@'%' IDENTIFIED BY 'Lock#Pass123'
  FAILED_LOGIN_ATTEMPTS 3       -- 3회 연속 실패하면
  PASSWORD_LOCK_TIME 1;         -- 1일간 잠금
```

3번 틀린 뒤에는 **올바른 비밀번호로도** 로그인이 막힙니다:

```bash
# 3회 오답
mysql ... -us22_lock -pWRONG -e "SELECT 1"   # ERROR 1045 (Access denied)
mysql ... -us22_lock -pWRONG -e "SELECT 1"   # ERROR 1045
mysql ... -us22_lock -pWRONG -e "SELECT 1"
# ERROR 3955 (HY000): Account is blocked for 1 day(s) (1 day(s) remaining) due to 3 consecutive failed logins.

# 이제 올바른 비밀번호도 막힌다
mysql ... -us22_lock -pLock#Pass123 -e "SELECT 1"
# ERROR 3955 (HY000): Account is blocked ...
```

잠긴 계정 해제:
```sql
ALTER USER 's22_lock'@'%' ACCOUNT UNLOCK;
```

### 비밀번호 만료 / 재사용 제한

```sql
ALTER USER 's22_lock'@'%' PASSWORD EXPIRE;                         -- 다음 로그인 때 강제 변경
ALTER USER 's22_lock'@'%' PASSWORD HISTORY 3                       -- 최근 3개
                          PASSWORD REUSE INTERVAL 365 DAY;         -- 또는 1년 내 재사용 금지
```

같은 비밀번호로 다시 바꾸려 하면 거부됩니다:
```sql
ALTER USER 's22_lock'@'%' IDENTIFIED BY 'NewPass#111';   -- 첫 변경: OK
ALTER USER 's22_lock'@'%' IDENTIFIED BY 'NewPass#111';   -- 재사용 시도
-- ERROR 3638 (HY000): Cannot use these credentials for 's22_lock@%'
--                     because they contradict the password history policy
```

계정에 걸린 정책은 `mysql.user.User_attributes`(JSON)에서 확인합니다:
```sql
SELECT user, host,
       JSON_EXTRACT(User_attributes, '$.Password_locking') AS lock_policy,
       password_expired
FROM mysql.user WHERE user = 's22_lock';
```
```
+----------+------+------------------------------------------------------------+------------------+
| user     | host | lock_policy                                                | password_expired |
+----------+------+------------------------------------------------------------+------------------+
| s22_lock | %    | {"failed_login_attempts": 3, "password_lock_time_days": 1} | Y                |
+----------+------+------------------------------------------------------------+------------------+
```

---

## 22-7. REVOKE

```sql
REVOKE INSERT, UPDATE, DELETE ON shop.* FROM s22_writer;
SHOW GRANTS FOR s22_writer;
```
```
+----------------------------------------------+
| Grants for s22_writer@%                      |
+----------------------------------------------+
| GRANT USAGE ON *.* TO `s22_writer`@`%`       |
| GRANT SELECT ON `shop`.* TO `s22_writer`@`%` |   ← 쓰기 권한이 회수됨
+----------------------------------------------+
```

> 💡 **실무 팁** — 롤에서 권한을 회수하면 그 롤을 가진 **모든 유저**에게 즉시 반영됩니다.
> 사람마다 REVOKE 할 필요가 없다는 것이 롤의 최대 장점입니다.

---

## 22-8. SQL Injection 이 DB 관점에서 왜 치명적인가

애플리케이션이 사용자 입력을 문자열로 이어붙여 쿼리를 만들면(`"... WHERE email='" + input + "'"`),
입력값 `' OR '1'='1` 하나로 **WHERE 절 전체가 무력화**됩니다. DB 는 문법적으로 올바른 쿼리를 **충실히 실행**할 뿐입니다.

```
정상 :  SELECT * FROM users WHERE email='a@b.com' AND pw='...'
공격 :  SELECT * FROM users WHERE email='' OR '1'='1' -- ' AND pw='...'
                                          ▲ 항상 참    ▲ 뒤는 주석 처리
        → 전체 사용자 유출. UNION SELECT 로 다른 테이블까지, ; DROP TABLE 로 파괴까지.
```

**DB 관점의 방어선(애플리케이션의 프리페어드 스테이트먼트와 별개로 반드시 병행):**

1. **최소권한** — 웹 앱 계정에 `DROP`/`ALTER`/`FILE` 권한이 없으면, 인젝션이 나도 **테이블을 못 지운다**. 이 스텝의 `app_rw` 처럼 DML 만 주면 피해가 데이터 조회/변조로 한정된다.
2. **컬럼/뷰 마스킹** — 인젝션으로 `customers` 를 통째로 읽어도, 앱 계정이 마스킹 뷰만 볼 수 있으면 평문 이메일/전화번호는 새지 않는다.
3. **계정 분리** — 읽기 API 는 `app_ro`, 쓰기 API 는 `app_rw`. 읽기 경로의 인젝션으로는 **쓰기 자체가 불가능**하다.
4. **감사(audit)** — 누가 언제 무엇을 했는지 로그가 있어야 사고 후 추적이 된다.

> ⚠️ **함정** — "프리페어드 스테이트먼트만 쓰면 안전하다"는 절반만 맞습니다.
> **정렬 컬럼명, 테이블명, `LIMIT` 등은 바인딩할 수 없어** 여전히 문자열 조립이 필요합니다(화이트리스트로 검증해야 함).
> 그래서 DB 최소권한이 **마지막 방어선**으로 반드시 있어야 합니다.

### 감사 관점

```sql
-- 접속 시도/쿼리를 남기는 방법 (개념)
--  * 엔터프라이즈: MySQL Enterprise Audit (audit_log 컴포넌트)
--  * 커뮤니티     : general_log(성능 영향 큼, 임시 진단용) 또는
--                   Percona/MariaDB audit plugin, 또는 프록시(ProxySQL) 레벨 로깅
SELECT user, host, plugin, password_last_changed, password_expired, account_locked
FROM mysql.user WHERE user NOT LIKE 'mysql.%';
```
정기적으로 **"권한이 과한 계정", "오래 안 바꾼 비밀번호", "쓰지 않는 계정"** 을 감사해야 합니다.

---

## 정리

| 항목 | 요점 |
|---|---|
| 사용자 | `'이름'@'호스트'`. 호스트를 좁히는 것도 최소권한 |
| 인증 플러그인 | **`caching_sha2_password`**(8.0 기본, 권장) vs `mysql_native_password`(레거시) |
| 권한 레벨 | 글로벌 > DB > 테이블 > **컬럼**. 항상 가장 좁게 |
| 롤(8.0) | `CREATE ROLE` → `GRANT 권한 TO 롤` → `GRANT 롤 TO 유저` → **`SET DEFAULT ROLE`** |
| 롤 함정 | `SET DEFAULT ROLE` 를 빠뜨리면 로그인해도 권한 0 |
| 마스킹 | 민감 컬럼은 뷰로 가리고, 원본 테이블 권한은 주지 않는다 |
| 계정 잠금 | `FAILED_LOGIN_ATTEMPTS N PASSWORD_LOCK_TIME D` (8.0.19+), ERROR 3955 |
| 비밀번호 | `PASSWORD EXPIRE` / `PASSWORD HISTORY` / `PASSWORD REUSE INTERVAL` |
| SQL Injection | DB 최소권한이 **마지막 방어선**. 읽기/쓰기 계정 분리, 마스킹, 감사 |
| 8.0/8.4 변경 | `validate_password` 는 플러그인→**컴포넌트**로. `mysql_native_password` 는 8.4 기본 비활성 |

---

## 연습문제

`exercise.sql` 을 열어 풀어 보세요. 정답은 `solution.sql`.

1. 배치 잡 전용 계정 `s22_batch@'%'` 를 만들고, `orders` 와 `order_items` 에 대해서만 SELECT/INSERT 를 주는 롤 `s22_batch_role` 을 통해 권한을 부여하라.
2. `s22_batch` 가 `customers` 를 SELECT 하면 거부되는 것을 확인하라(개념: 어떤 에러 번호?).
3. `products` 에서 `cost`(원가)를 **제외한** 모든 컬럼만 읽는 컬럼 레벨 권한을 롤에 부여하라.
4. `s22_temp@'%'` 를 만들되, 연속 실패 5회 시 2일 잠금 + 생성 즉시 비밀번호 만료로 설정하라.
5. `s22_batch_role` 에서 INSERT 권한만 회수(REVOKE)하고 남은 권한을 확인하라.
6. 실습에서 만든 모든 계정/롤을 삭제하는 정리 SQL 을 작성하라.

---

## 다음 단계

→ [Step 23 — 백업과 복제](../step-23-backup-replication/index.md)

---

## 실습 파일

이 스텝은 SQL 파일 3개로 구성됩니다. 먼저 `practice.sql` 을 실행해 계정 생성 → 롤 → 권한 → 마스킹 뷰 → 비밀번호 정책 → REVOKE → 뒷정리의 흐름을 그대로 재현해 보고, 그다음 `exercise.sql` 의 6문제를 직접 채워 넣은 뒤, 마지막으로 `solution.sql` 과 비교합니다. 세 파일 모두 **root 로 실행**해야 합니다(계정·롤 생성은 관리 권한이 필요합니다).

> ⚠️ **뒷정리 주의** — `practice.sql` 과 `solution.sql` 은 마지막에 만든 계정/롤/뷰를 전부 지우는 정리 블록이 **이미 들어 있어** 그대로 실행됩니다. 하지만 `exercise.sql` 의 정리 블록(문제 6)은 **주석 처리된 TODO 일 뿐이라 아무것도 지우지 않습니다.** 공용 서버이므로 문제 6은 여러분이 직접 작성해서 반드시 실행해야 합니다.

> 💡 `practice.sql` 안의 절 번호는 본문과 한 칸씩 어긋납니다. 파일의 22-6(마스킹 뷰)이 본문 22-5, 파일의 22-7(비밀번호 정책)이 본문 22-6, 파일의 22-8(REVOKE)이 본문 22-7 에 해당하고, 파일의 22-9(뒷정리)는 본문에 대응하는 절이 없습니다.

### practice.sql

본문 강의를 그대로 따라가는 **메인 실습 스크립트**입니다. 실행은 `mysql -h127.0.0.1 -P3307 -uroot -proot1234 shop -t --force < practice.sql`.

- **맨 앞의 `DROP VIEW/ROLE/USER IF EXISTS` 블록**이 이전 실행 잔재를 지우므로, 몇 번을 다시 돌려도 같은 결과가 나옵니다(멱등). `--force` 옵션은 중간에 에러가 나도 끝까지 진행시켜, 마지막 정리 블록이 반드시 실행되게 하려는 의도입니다.
- 22-1 구간에서 `app_ro`/`app_rw` 는 `IDENTIFIED WITH caching_sha2_password`, `s22_native` 는 `IDENTIFIED WITH mysql_native_password` 로 만들어 **`mysql.user.plugin` 컬럼에서 두 인증 방식의 차이를 눈으로 확인**하게 합니다.
- 22-3 구간이 권한 레벨의 핵심입니다. `GRANT SELECT ON shop.* TO s22_reader`(DB 레벨), `GRANT SELECT ON shop.orders TO s22_analyst`(테이블 레벨), `GRANT SELECT (customer_id, grade, city, points) ON shop.customers TO s22_analyst`(컬럼 레벨)가 나란히 놓여 범위가 좁아지는 과정을 보여줍니다. 다만 본문 22-3 이 소개한 4레벨 중 **글로벌 레벨(`ON *.*`)은 스크립트에 일부러 넣지 않았습니다** — "거의 쓰지 말라"고 한 권한을 실습 서버에서 실제로 부여할 이유가 없기 때문입니다. 그래서 파일에서 실행되는 것은 DB/테이블/컬럼 3레벨뿐입니다. `s22_analyst` 에는 `email`·`phone` 컬럼이 빠져 있어서, 이 롤을 받은 계정은 `SELECT *` 조차 ERROR 1142 로 거부됩니다.
- 22-5 구간의 `SHOW GRANTS FOR 'app_rw'@'%' USING s22_writer;` 는 롤을 **켠 상태의 유효 권한**을 펼쳐 보여 줍니다. 바로 앞줄의 `SHOW GRANTS FOR 'app_ro'@'%';`(롤 미적용) 출력과 비교하면, 롤이 활성화되기 전에는 `USAGE`(권한 없음)만 보인다는 점이 드러납니다.
- 51번째 줄의 `SET DEFAULT ROLE ALL TO 'app_ro'@'%', 'app_rw'@'%';` 가 본문 22-4 의 "많이 막히는 함정"에 해당합니다. 이 한 줄이 없으면 `GRANT s22_reader TO 'app_ro'@'%'` 를 해 두고도 로그인 시 `CURRENT_ROLE()` 이 `NONE` 이라 **권한이 하나도 없는 상태**가 됩니다.
- **주의**: 스크립트에는 `s22_alice` 를 만드는 문장이 없고 DROP 문에만 들어 있습니다. 본문 22-3 의 컬럼 권한 검증(`-us22_alice`)을 직접 해 보려면 `CREATE USER 's22_alice'@'%' IDENTIFIED BY 'Alice#123';` + `GRANT s22_analyst TO 's22_alice'@'%';` + `SET DEFAULT ROLE ALL TO 's22_alice'@'%';` 를 별도 세션에서 추가해야 합니다. 계정 잠금(ERROR 3955)이나 로그인 거부처럼 **접속 자체를 시도해야 보이는 결과는 SQL 파일만으로는 재현되지 않으므로**, 본문의 셸 명령을 함께 실행해 보세요.
- 마지막 22-9 블록은 뷰·롤·계정을 다시 전부 DROP 하고, `leftover_users` 가 `0` 인지 세어서 뒷정리가 끝났음을 스스로 검증합니다.

```sql file="./practice.sql"
```

### exercise.sql

연습문제 6개가 담긴 **빈칸 채우기 스크립트**입니다. TODO 주석 아래의 힌트 SQL이 모두 `--` 로 주석 처리되어 있으므로, 그대로 실행하면 앞부분의 DROP 문만 돌고 아무 일도 일어나지 않습니다. 주석을 풀고 `...` 부분을 직접 채워 넣는 것이 과제입니다.

- **문제 1**은 `s22_batch_role` 을 통해 `orders`/`order_items` 에만 SELECT/INSERT 를 주는 문제로, `SET DEFAULT ROLE ALL TO 's22_batch'@'%';` 를 잊지 않는지 확인하는 것이 포인트입니다.
- **문제 2**는 SQL 로 답이 나오지 않습니다. 힌트에 적힌 대로 셸에서 `mysql ... -us22_batch -pBatch#123 shop -e "SELECT * FROM customers LIMIT 1;"` 를 직접 실행해 **에러 번호를 주석에 적어 넣는** 문제입니다(테이블 자체에 권한이 없으므로 컬럼 거부 1143 이 아니라 테이블 거부 1142 입니다).
- **문제 3**의 힌트가 `products` 의 전체 컬럼 목록(`product_id, category_id, name, price, cost, stock, status, attrs, created_at`)을 미리 알려 줍니다. 여기서 `cost` 하나만 빼고 나열하면 됩니다 — 컬럼 레벨 권한은 "제외" 문법이 없어서 **허용할 컬럼을 전부 열거**해야 한다는 점이 학습 포인트입니다.
- **문제 4**는 `FAILED_LOGIN_ATTEMPTS 5` + `PASSWORD_LOCK_TIME 2` + `PASSWORD EXPIRE` 조합을, **문제 5**는 롤에서 INSERT 만 골라 REVOKE 하는 것을 다룹니다.
- **문제 6은 반드시 실행하세요.** 공용 서버에 `s22_batch`/`s22_temp` 계정과 롤이 남으면 다음 사람의 실습이 깨집니다.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql` 의 정답입니다. 먼저 스스로 풀어 본 뒤에 열어 보세요.

- 정답 1은 `GRANT SELECT, INSERT ON shop.orders TO s22_batch_role;` 처럼 **테이블마다 한 줄씩** GRANT 합니다. `shop.*` 로 뭉뚱그리지 않는 것이 최소권한 원칙입니다.
- 정답 2는 주석으로만 적혀 있습니다: `ERROR 1142 (42000): SELECT command denied ... for table 'customers'`. `customers` 에는 컬럼 권한조차 주지 않았기 때문에 컬럼 거부(1143)가 아니라 **테이블 거부(1142)** 가 나옵니다. 본문 22-3 의 `s22_alice` 사례(컬럼 권한이 있어 `email` 만 1143)와 비교해 보면 두 에러의 차이가 분명해집니다.
- 정답 3의 `GRANT SELECT (product_id, category_id, name, price, stock, status, attrs, created_at) ON shop.products TO s22_prod_role;` 에서 `cost` 만 빠져 있습니다. 이 롤을 가진 계정은 `SELECT cost FROM products` 나 `SELECT *` 를 하면 거부됩니다.
- 정답 5의 `REVOKE INSERT ON shop.orders FROM s22_batch_role;` 이 롤의 진가를 보여줍니다 — **롤에서 회수하면 그 롤을 가진 `s22_batch` 유저에게 즉시 반영**되므로 유저마다 REVOKE 할 필요가 없습니다(본문 22-7 의 실무 팁).
- 정답 6이 `DROP USER`/`DROP ROLE` 후 `leftover` 개수를 세어 정리가 끝났는지 확인합니다.

```sql file="./solution.sql"
```
