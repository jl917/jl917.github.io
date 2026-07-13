# Step 01 — 환경 구축과 첫 접속

> **학습 목표**
> - Docker로 MySQL 8을 5분 만에 띄운다
> - 클라이언트로 접속하고, 내가 접속한 서버가 어떤 상태인지 확인한다
> - `mysql` CLI의 필수 조작법(출력 모드, source, 편집)을 익힌다
> - 설정 파일(`my.cnf`)이 실제 서버 동작을 어떻게 바꾸는지 확인한다
>
> **선행 스텝**: 없음
> **예상 소요**: 30분

---

## 1-1. 왜 Docker인가

MySQL을 OS에 직접 설치하면 버전이 섞이고, 실습하다 설정을 망가뜨리면 되돌리기 번거롭습니다. Docker로 띄우면 **컨테이너를 지웠다 다시 만드는 것만으로 완전한 초기 상태로 돌아갑니다.** 학습에는 이게 압도적으로 유리합니다.

이 코스의 컨테이너 정의는 `docker/docker-compose.yml`에 있습니다(전문과 해설은 [Docker 실행 환경](../docker/index.md) 페이지에 있습니다). 핵심만 보면:

| 항목 | 값 | 이유 |
|---|---|---|
| 이미지 | `mysql:8.0` | 현재 가장 널리 쓰이는 LTS 계열 |
| 호스트 포트 | **3307** | 내 PC에 이미 MySQL(3306)이 있어도 충돌하지 않도록 |
| root 비밀번호 | `root1234` | 관리 작업용 (Step 22에서 사용) |
| 일반 계정 | `learner` / `learn1234` | 평소 실습은 이 계정으로 |
| 기본 DB | `shop` | 이 코스의 예제 쇼핑몰 DB |

---

## 1-2. 컨테이너 띄우기

```bash
cd mysql8/docker
docker compose up -d
```

준비될 때까지 기다립니다(헬스체크가 `healthy`가 되면 접속 가능):

```bash
docker inspect -f '{{.State.Health.Status}}' learn-mysql8
```

**결과**
```
healthy
```

로그를 보고 싶다면:

```bash
docker logs -f learn-mysql8
```

> 💡 **실무 팁**: `docker compose down`은 컨테이너만 지우고 데이터(볼륨)는 남깁니다. 데이터까지 완전 초기화하려면 `docker compose down -v`입니다. 실습을 망쳤을 때 이 명령 하나면 처음으로 돌아갑니다.

---

## 1-3. 접속하기

### 방법 A — 호스트의 mysql 클라이언트 (권장)

macOS라면:
```bash
brew install mysql-client
echo 'export PATH="/opt/homebrew/opt/mysql-client/bin:$PATH"' >> ~/.zshrc && source ~/.zshrc
```

접속:
```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop
```

### 방법 B — 컨테이너 안에서

```bash
docker exec -it learn-mysql8 mysql -ulearner -plearn1234 shop
```

접속되면 프롬프트가 이렇게 바뀝니다:
```
mysql>
```

> ⚠️ **함정**: `-p` 와 비밀번호는 **붙여 써야** 합니다. `-p learn1234`처럼 띄우면 `learn1234`를 DB 이름으로 해석해 버립니다. 그리고 비밀번호를 명령줄에 노출하면 `Using a password on the command line interface can be insecure` 경고가 뜹니다. 실무에서는 `~/.my.cnf`나 `mysql_config_editor`를 쓰세요.

---

## 1-4. 내가 접속한 서버는 어떤 서버인가

접속 직후 가장 먼저 확인해야 할 것들입니다.

```sql
SELECT
  VERSION()                 AS version,
  @@version_comment         AS edition,
  @@character_set_server    AS charset,
  @@collation_server        AS collation,
  @@default_storage_engine  AS engine,
  @@time_zone               AS tz;
```

**결과**
```
+-----------+-----------------------------+---------+--------------------+--------+--------+
| version   | edition                     | charset | collation          | engine | tz     |
+-----------+-----------------------------+---------+--------------------+--------+--------+
| 8.0.46    | MySQL Community Server - GPL| utf8mb4 | utf8mb4_0900_ai_ci | InnoDB | +09:00 |
+-----------+-----------------------------+---------+--------------------+--------+--------+
```

각 값이 왜 중요한지:

- **`utf8mb4`** — MySQL의 `utf8`은 **가짜 UTF-8**입니다. 3바이트까지만 저장해서 이모지(4바이트)를 넣으면 에러가 납니다. 반드시 `utf8mb4`를 쓰세요. MySQL 8부터는 기본값이 `utf8mb4`라 다행이지만, 5.7에서 마이그레이션한 DB는 아직 `utf8`인 경우가 많습니다.
- **`utf8mb4_0900_ai_ci`** — MySQL 8의 새 기본 콜레이션(정렬 규칙). `ai`=악센트 무시, `ci`=대소문자 무시. 즉 `'Apple' = 'apple'`이 **참**입니다. 5.7의 `utf8mb4_general_ci`보다 유니코드 정확도가 높습니다.
- **`InnoDB`** — 트랜잭션/외래키/행 수준 잠금을 지원하는 유일한 실용 엔진입니다. MyISAM은 쓰지 마세요.
- **`+09:00`** — 서버 타임존. 이 값에 따라 `NOW()`가 달라집니다. 컨테이너 기본은 UTC라서 `docker/conf/my.cnf`에서 KST로 고정해 두었습니다.

### sql_mode 확인 — 이게 제일 중요합니다

```sql
SELECT @@sql_mode;
```

**결과**
```
ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,
ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
```

`sql_mode`는 **MySQL이 얼마나 엄격하게 굴지**를 정합니다.

| 모드 | 없으면 벌어지는 일 |
|---|---|
| `STRICT_TRANS_TABLES` | `VARCHAR(10)`에 20글자를 넣으면 **경고만 내고 잘라서 저장**합니다. 데이터가 조용히 손상됩니다. |
| `ONLY_FULL_GROUP_BY` | `GROUP BY`에 없는 컬럼을 `SELECT`해도 **아무 행이나 하나 골라서** 반환합니다. 틀린 결과가 나옵니다. |
| `NO_ZERO_DATE` | `'0000-00-00'` 같은 존재하지 않는 날짜가 저장됩니다. |

> ⚠️ **함정**: 인터넷 예제 중에 "`ONLY_FULL_GROUP_BY` 때문에 에러 나면 끄세요"라는 글이 정말 많습니다. **끄지 마세요.** 그 에러는 MySQL이 당신의 쿼리가 틀렸다고 알려주는 것입니다. Step 06에서 왜 그런지 직접 겪어봅니다. 이 코스는 일부러 기본값(엄격 모드)을 유지합니다.

---

## 1-5. mysql CLI 필수 조작법

### 출력 모드 바꾸기 — `\G`

컬럼이 많으면 표가 화면을 넘어가 읽을 수 없습니다. 명령 끝을 `;` 대신 `\G`로 하면 세로로 출력됩니다.

```sql
SELECT * FROM customers WHERE customer_id = 1\G
```

**결과**
```
*************************** 1. row ***************************
customer_id: 1
      email: kim.minsu@example.com
       name: 김민수
      phone: 010-1000-0001
      grade: VIP
 birth_date: 1985-03-12
       city: 서울
     points: 12500
 created_at: 2023-01-05 10:11:00
```

> ⚠️ **함정**: `\G`는 **mysql CLI 안에서만** 동작합니다. `mysql -e "...\G"` 처럼 셸에서 `-e` 옵션으로 넘기면 `Unknown command '\G'` 에러가 납니다. 셸에서는 `-e "SELECT ..." --vertical` 을 쓰세요.

### 파일 실행 — `source`

```sql
source /sql/01_schema.sql
```
또는 셸에서:
```bash
mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < 01_schema.sql
```

### 그 외 자주 쓰는 명령

| 명령 | 뜻 |
|---|---|
| `\h` 또는 `help` | 도움말 |
| `\s` (status) | 현재 접속 정보 요약 |
| `\c` | 입력 중인 쿼리 취소 |
| `\q` | 종료 |
| `USE shop;` | 사용할 DB 전환 |
| `SHOW DATABASES;` | DB 목록 |
| `SHOW TABLES;` | 현재 DB의 테이블 목록 |
| `\! clear` | 셸 명령 실행 (화면 지우기) |

> 💡 **실무 팁**: `mysql --pager="less -S"` 로 접속하면 넓은 결과를 좌우 스크롤로 볼 수 있습니다. 그리고 `mysql --safe-updates` (또는 `SET sql_safe_updates=1`)로 접속하면 **WHERE 없는 UPDATE/DELETE를 서버가 거부**합니다. 운영 DB에 붙을 땐 무조건 켜세요. 이 습관 하나가 사고를 막습니다.

---

## 1-6. 설정 파일이 실제로 먹었는지 확인

`docker/conf/my.cnf`([Docker 실행 환경](../docker/index.md) 페이지에 전문이 있습니다)에서 슬로우 쿼리 로그를 켜두었습니다. 정말 적용됐는지 봅시다.

```sql
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';
```

**결과**
```
+---------------------+--------------------------+
| Variable_name       | Value                    |
+---------------------+--------------------------+
| slow_query_log      | ON                       |
| slow_query_log_file | /var/lib/mysql/slow.log  |
+---------------------+--------------------------+
+-----------------+----------+
| Variable_name   | Value    |
+-----------------+----------+
| long_query_time | 0.500000 |
+-----------------+----------+
```

0.5초 넘는 쿼리는 전부 로그에 남습니다. Step 24에서 이 로그를 분석합니다.

### 변수의 두 가지 범위

```sql
-- 이 세션에만 적용 (접속 끊으면 사라짐)
SET SESSION sort_buffer_size = 1024*1024;

-- 서버 전체에 적용 (재시작하면 사라짐. 영구히 하려면 my.cnf에 써야 함)
SET GLOBAL long_query_time = 1;    -- root 권한 필요

-- 8.0 신기능: 재시작해도 유지 (mysqld-auto.cnf 에 기록됨)
SET PERSIST long_query_time = 1;
```

> 💡 **실무 팁**: `SET PERSIST`는 MySQL 8의 매우 유용한 신기능입니다. 예전에는 설정을 바꾸려면 `SET GLOBAL`(임시) + `my.cnf` 수정(영구)을 **둘 다** 해야 했고, 하나를 빼먹어 재시작 후 설정이 날아가는 사고가 흔했습니다.

---

## 1-7. 문제가 생겼을 때

| 증상 | 원인과 해결 |
|---|---|
| `Can't connect to MySQL server on '127.0.0.1'` | 컨테이너가 아직 안 떴습니다. `docker ps`로 확인, `docker logs learn-mysql8`로 원인 확인 |
| `Access denied for user 'learner'` | 비밀번호 오타, 또는 `-p`와 비밀번호를 띄어 씀 |
| `Unknown database 'shop'` | 아직 스키마를 안 만들었습니다. Step 03 진행 |
| 한글이 `???`로 깨짐 | 클라이언트 charset 문제. `mysql --default-character-set=utf8mb4` |
| 포트 3307 충돌 | `docker-compose.yml`의 포트를 3308 등으로 변경 |
| 완전히 꼬임 | `docker compose down -v && docker compose up -d` 후 Step 03 재실행 |

---

## 정리

| 명령 | 용도 |
|---|---|
| `docker compose up -d` | MySQL 8 기동 |
| `docker compose down -v` | 데이터까지 완전 삭제 (초기화) |
| `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop` | 접속 |
| `SELECT VERSION(), @@sql_mode;` | 서버 정체 파악 |
| `SHOW VARIABLES LIKE '...'` | 설정값 조회 |
| `SET SESSION / GLOBAL / PERSIST` | 설정 변경 (범위별) |
| `\G` | 세로 출력 |
| `source file.sql` | SQL 파일 실행 |

---

## 연습문제

`exercise.sql`의 문제를 풀고 `solution.sql`로 확인하세요. 두 파일의 전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다.

1. 이 서버의 최대 동시 접속 수(`max_connections`)는?
2. InnoDB 버퍼 풀 크기를 MB 단위로 출력하시오.
3. 현재 접속 중인 세션 목록을 보시오. (힌트: `SHOW PROCESSLIST`)
4. 이 서버가 켜진 지 몇 초가 지났는가? (힌트: `SHOW GLOBAL STATUS LIKE 'Uptime'`)
5. 내 세션의 타임존만 UTC로 바꾸고 `NOW()`가 어떻게 변하는지 확인하시오.

---

## 다음 단계

→ [Step 02 — 데이터베이스·테이블·데이터 타입](../step-02-ddl-datatypes/index.md)

---

## 실습 파일

이 스텝에서 쓰는 파일은 세 개입니다. 컨테이너를 띄우고 접속한 뒤(1-2 ~ 1-3) 먼저 `practice.sql`을 실행해 본문 1-4 ~ 1-7에서 다룬 조회를 한 번에 따라가 보고, 그다음 `exercise.sql`의 7문제를 직접 풀어본 뒤 `solution.sql`로 답을 맞춰보는 순서입니다. 세 파일 모두 조회 위주라 데이터를 바꾸지 않으므로 몇 번을 반복 실행해도 안전합니다.

### practice.sql

본문의 실습 코드를 순서대로 모아둔 파일입니다. 접속 후 `source practice.sql`로 실행하거나, 셸에서 `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < practice.sql`로 한 번에 흘려보내면 됩니다.

- **[1-4] 서버 정체 파악** — `VERSION()`, `@@character_set_server`, `@@collation_server`, `@@default_storage_engine`, `@@time_zone`을 한 줄로 뽑습니다. `utf8mb4` / `utf8mb4_0900_ai_ci` / `InnoDB` / `+09:00`이 나와야 정상입니다.
- **sql_mode 확인** — 이어지는 `SELECT @@sql_mode;`는 본문 1-4에서 강조한 엄격 모드가 살아 있는지 보는 줄입니다. `ONLY_FULL_GROUP_BY`와 `STRICT_TRANS_TABLES`가 목록에 보이면 정상이며, 이 코스는 이 둘을 끄지 않은 채로 끝까지 갑니다.
- **콜레이션 증명** — `SELECT 'Apple' = 'apple'`이 `1`을 반환합니다. `_ci`(case insensitive) 콜레이션이라 대소문자를 구분하지 않는다는 것을 눈으로 확인하는 줄입니다.
- **utf8mb4 증명** — `LENGTH('가')`=3, `CHAR_LENGTH('가')`=1, `LENGTH('🚀')`=4. 바이트 수와 글자 수가 다르다는 점, 그리고 이모지는 4바이트라 가짜 `utf8`(3바이트)에서는 저장조차 안 된다는 점이 핵심입니다.
- **[1-5] 주의** — 28행의 `SELECT * FROM customers ...`는 본문에서 `\G`로 보여준 쿼리지만, 여기서는 `;`로 끝냅니다. `\G`는 mysql CLI 안에서만 동작하므로 파일로 실행하면 에러가 나기 때문입니다.
- **[1-6] 설정 확인과 변수 범위** — `SHOW VARIABLES LIKE 'slow_query%'`로 `my.cnf`가 먹었는지 보고(`slow_query_log`=`ON`, 파일은 `/var/lib/mysql/slow.log`), `long_query_time`이 기본 10초가 아니라 `0.500000`으로 내려가 있는지 봅니다. 세 번째 줄의 `SHOW VARIABLES LIKE 'event_scheduler'`는 본문에는 없는 항목인데, `my.cnf`에 `event_scheduler = ON`을 넣어 두었기 때문에 `ON`이 나옵니다(Step 20의 이벤트 스케줄러 실습을 위한 사전 준비입니다). 이어서 `SET SESSION sort_buffer_size = 1048576`(=1MB) 전후 값을 비교해 SESSION 범위가 내 접속에만 적용됨을 확인합니다.
- **안전 모드 체험** — `SET SESSION sql_safe_updates = 1`로 켰다가 44행에서 다시 `0`으로 끕니다. 이후 스텝의 `UPDATE`/`DELETE` 실습이 막히지 않도록 되돌리는 것이니, 운영 DB에서는 반대로 **켠 채로** 두어야 합니다.
- **[1-7] 접속 정보** — `USER()`는 "내가 접속을 시도한 계정", `CURRENT_USER()`는 "서버가 실제로 매칭한 계정"입니다. 권한 문제가 생겼을 때 이 둘이 다르면 원인이 그 자리에 있습니다(예: `learner@%`가 아니라 익명 계정에 매칭된 경우).

```sql file="./practice.sql"
```

### exercise.sql

정답 없이 문제 주석만 들어 있는 연습문제 파일입니다. 본문 "연습문제"의 5문제에 두 개(스토리지 엔진 목록, `%buffer%` 변수 찾기)를 더한 총 7문제로, 각 문제 아래 빈 줄에 직접 SQL을 써 넣고 실행해 보는 방식입니다.

- 문제 1~2는 `max_connections`, `@@innodb_buffer_pool_size` 같은 **시스템 변수 조회** 연습입니다. 특히 문제 2는 바이트 단위로 나오는 값을 MB로 환산하라고 요구하므로 `@@변수`를 일반 식처럼 계산에 쓸 수 있다는 걸 익히게 됩니다.
- 문제 3~4는 `SHOW PROCESSLIST`, `SHOW GLOBAL STATUS LIKE 'Uptime'` 등 **서버 상태 조회**입니다. 장애 대응에서 가장 먼저 치는 명령이라 손에 익혀 두는 것이 좋습니다.
- 문제 5의 주석에 달린 "(주의: GLOBAL 이 아니라 SESSION 으로!)"가 이 파일의 학습 포인트입니다. 실수로 `SET GLOBAL time_zone`을 쓰면 서버 전체 타임존이 바뀌어 다른 세션의 `NOW()`까지 흔들립니다.
- 답을 적기 전에 먼저 추측해 보고, 다 푼 뒤 `solution.sql`과 비교하세요.

```sql file="./exercise.sql"
```

### solution.sql

`exercise.sql`의 정답과 해설입니다. 단순 정답뿐 아니라 "왜 그런가"와 실무에서의 함정이 주석으로 붙어 있으니, 답만 맞히고 넘어가지 말고 주석까지 읽어보세요.

- **문제 1** — 기본값은 `151`입니다. 해설의 요지는 "`Too many connections`가 나면 `max_connections`를 올리기 전에 커넥션이 왜 반납되지 않는지부터 의심하라"는 것입니다.
- **문제 2** — `ROUND(@@innodb_buffer_pool_size / 1024 / 1024)` → `256`(MB). `my.cnf`에서 `256M`으로 지정한 값이며, 운영에서는 물리 메모리의 50~70%가 정석이라는 기준을 함께 제시합니다.
- **문제 3** — `SHOW PROCESSLIST` / `SHOW FULL PROCESSLIST` / `information_schema.processlist` 세 가지를 나란히 보여줍니다. `SHOW PROCESSLIST`는 쿼리가 100자에서 잘리므로 범인을 찾을 때는 `FULL`을 붙여야 한다는 점, 그리고 `Time`이 큰 세션이나 `State`가 `Locked`/`Waiting for ...`인 세션이 장애의 1순위 용의자라는 점이 요지입니다. 죽일 때는 `KILL <id>`입니다(Step 24에서 다시 다룹니다).
- **문제 4** — `performance_schema.global_status`에서 `Uptime`을 읽어 `FLOOR(값/3600)시간 FLOOR((값%3600)/60)분` 형태로 가공합니다. MySQL 8에서는 상태 변수 조회처가 `information_schema`에서 `performance_schema`로 옮겨졌다는 점이 포인트입니다.
- **문제 5** — 세션 타임존을 `+00:00`으로 바꾸면 `NOW()`가 9시간 뒤로 갑니다. 마지막 줄에서 `SET SESSION time_zone = '+09:00'`으로 **반드시 원복**합니다. 원복하지 않고 이후 스텝을 진행하면 시간 관련 결과가 예제와 달라집니다. 해설은 여기서 `DATETIME`(타임존 정보 없음)과 `TIMESTAMP`(UTC 저장 후 세션 타임존으로 변환)의 차이까지 연결합니다.
- **문제 6** — `SHOW ENGINES`의 `Support` 컬럼이 `DEFAULT`인 행이 기본 엔진(InnoDB)입니다. `Transactions`/`XA`/`Savepoints`가 모두 `YES`인 엔진은 InnoDB뿐이며, MyISAM은 트랜잭션·외래키·행 잠금이 전부 없고 MEMORY는 재시작하면 데이터가 사라진다는 점을 눈으로 확인하는 문제입니다.
- **문제 7** — `sort_buffer_size`·`join_buffer_size`처럼 **세션마다 할당되는** 버퍼와, 서버 전체가 공유하는 `innodb_buffer_pool_size`를 구분하라는 것이 핵심입니다. 이 구분을 모르고 `sort_buffer_size`를 크게 잡으면 (커넥션 수 × 버퍼 크기)만큼 메모리가 폭증해 OOM으로 이어집니다.

```sql file="./solution.sql"
```

