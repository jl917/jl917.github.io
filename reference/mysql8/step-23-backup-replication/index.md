# Step 23 — 백업과 복제

> **학습 목표**
> - 논리 백업(`mysqldump`)의 핵심 옵션을 이해하고 무중단 백업/복구를 직접 해본다
> - 물리 백업(XtraBackup)과 논리 백업의 차이를 안다
> - 바이너리 로그와 GTID, binlog 포맷(ROW/STATEMENT/MIXED), PITR(시점 복구) 개념을 잡는다
> - 소스-레플리카 복제를 **직접 구성**하고 `CHANGE REPLICATION SOURCE TO` / `SHOW REPLICA STATUS`(8.0 신문법)로 검증한다
>
> **선행 스텝**: [Step 19 — 트랜잭션](../step-19-transactions/), [Step 22 — 사용자와 보안](../step-22-users-security/)
> **예상 소요**: 90분

> 이 스텝은 SQL 보다 **셸 스크립트**가 중심입니다.
> - `backup-restore.sh` — learn-mysql8(3307)에서 mysqldump 백업/복구
> - `setup-replication.sh` + `../docker/replication/docker-compose.yml` — 별도 2노드(3308/3309) 복제
>
> ⚠️ 복제 실습은 **반드시 별도 컨테이너(3308/3309)** 로만 합니다. 기존 learn-mysql8(3307)은 건드리지 않습니다.

---

## 23-1. 백업의 두 갈래 — 논리 vs 물리

| | 논리 백업 (Logical) | 물리 백업 (Physical) |
|---|---|---|
| 무엇을 뜨나 | `CREATE`/`INSERT` **SQL 텍스트** | InnoDB **데이터 파일 자체**(ibd, 로그) |
| 도구 | `mysqldump`, `mysqlpump`, `mysqlsh` dump-utility | **Percona XtraBackup**, MySQL Enterprise Backup |
| 속도(백업) | 느림(행을 SQL 로 변환) | 빠름(파일 복사) |
| 속도(복구) | **느림**(SQL 재실행 + 인덱스 재구축) | **빠름**(파일 되돌리기) |
| 크기 | 큼(텍스트) | 원본과 비슷 |
| 이식성 | **높음**(버전/OS 넘나듦, 일부만 복구 가능) | 낮음(같은 버전/환경) |
| 언제 | 수 GB~수십 GB, 부분 복구, 마이그레이션 | 수백 GB~TB, 전체 복구 속도가 중요 |

핵심 원칙: **"백업은 성공했는가"가 아니라 "복구가 되는가"가 백업의 목적**입니다. 복구 리허설 없는 백업은 백업이 아닙니다.

---

## 23-2. 논리 백업 — mysqldump 옵션 완전 해설

`backup-restore.sh` 가 쓰는 명령입니다.

```bash
mysqldump -h127.0.0.1 -P3307 -uroot -proot1234 \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=AUTO \
  --hex-blob \
  --default-character-set=utf8mb4 \
  --databases shop > shop_YYYYMMDD.sql
```

| 옵션 | 의미 / 왜 필요한가 |
|---|---|
| **`--single-transaction`** | InnoDB 를 **잠그지 않고** 일관된 스냅샷을 뜬다. 내부적으로 `REPEATABLE READ` 트랜잭션을 열어 그 시점의 데이터를 덤프 → **서비스 무중단 백업의 핵심**. (MyISAM 에는 효과 없음 — MyISAM 은 `--lock-tables` 필요) |
| **`--routines`** | 저장 프로시저/함수([Step 20](../step-20-stored-programs/))도 포함. **기본은 제외**되므로 반드시 명시 |
| **`--triggers`** | 트리거 포함(기본 ON이지만 명시 권장) |
| **`--events`** | 이벤트 스케줄러 포함. **기본 제외** |
| **`--set-gtid-purged=AUTO`** | GTID 환경이면 복구 시 필요한 `SET @@GLOBAL.GTID_PURGED` 를 덤프에 넣는다. **레플리카를 백업으로 초기 구성할 때 필수** |
| **`--hex-blob`** | BLOB/JSON 을 16진수로 안전하게 덤프(문자셋 문제 회피) |
| **`--default-character-set=utf8mb4`** | 한글 깨짐 방지 |
| `--databases shop` | `CREATE DATABASE`/`USE` 문까지 포함. 단일 테이블만 뜰 땐 생략 |
| (기타) `--master-data=2` | 덤프 시점의 binlog 파일/포지션을 주석으로 기록 → PITR 시작점 |

**실행 결과**
```
### 1) 논리 백업 — mysqldump 주요 옵션 총동원
  백업 파일: .../mysql8/backup/shop_20260713_104842.sql
  크기: 248M
  머리 부분(메타/GTID):
-- Host: 127.0.0.1    Database: shop
-- Server version	8.0.46
CREATE DATABASE /*!32312 IF NOT EXISTS*/ `shop` /*!40100 DEFAULT CHARACTER SET utf8mb4 ... */;
```

> ⚠️ **함정** — `--routines`, `--events` 는 **기본으로 빠집니다.** 이걸 모르고 백업하면
> 복구했을 때 프로시저/이벤트가 통째로 사라진 걸 나중에야 발견합니다. 항상 명시하세요.

### 복구 — 별도 DB 로 안전하게

원본을 덮어쓰지 않도록 `shop_restore_test` 로 복구하고 행수를 비교합니다.

```bash
mysql ... -e "CREATE DATABASE shop_restore_test CHARACTER SET utf8mb4;"
sed 's/`shop`/`shop_restore_test`/g' shop_YYYYMMDD.sql | mysql ... shop_restore_test
```

**결과** (원본 vs 복구본 행수 일치)
```
+-------------+---------+----------+
| t           | src     | restored |
+-------------+---------+----------+
| orders      |     600 |      600 |
| customers   |      30 |       30 |
| products    |      40 |       40 |
| access_logs | 1000000 |  1000000 |
+-------------+---------+----------+
```

> 💡 **실무 팁 — 다른 논리 백업 도구**
> - **`mysqlpump`** : mysqldump 의 병렬 버전(`--default-parallelism`). 단, 8.0.34+ 에서 **deprecated**.
> - **`mysqlsh` (MySQL Shell) dump-utility** : `util.dumpInstance()` / `util.dumpSchemas()` + `util.loadDump()`.
>   압축·병렬·청크·재개(resume)를 지원해 **현재 권장되는 논리 백업 도구**. 대용량은 이걸 쓰세요.
>   ```
>   mysqlsh root@127.0.0.1:3307 -- util dump-schemas shop --output-url=/backup/dump-shop
>   mysqlsh root@127.0.0.1:3307 -- util load-dump /backup/dump-shop
>   ```

---

## 23-3. 물리 백업 — XtraBackup (개념)

Percona XtraBackup 은 InnoDB 데이터 파일을 **온라인 상태로** 복사합니다.

```
1) 데이터 파일(ibd) 복사 시작    ← 이 동안에도 서비스는 계속 쓰기 가능
2) 복사 중 발생한 변경은 redo log 를 함께 복사해 따라잡는다
3) --prepare : 복사본에 redo 를 적용해 "일관된 시점"으로 만든다(crash recovery 재현)
4) 복구: 데이터 디렉터리에 파일을 되돌리고 서버 기동
```

논리 백업이 수십 GB 에서 이미 몇 시간씩 걸리는 반면, 물리 백업은 수백 GB~TB 급에서도 **파일 복사 속도**로 끝납니다. 대신 **같은 MySQL 버전/플랫폼**이어야 하고 부분 복구가 어렵습니다.

---

## 23-4. 바이너리 로그 · GTID · binlog 포맷

### 바이너리 로그(binlog)

**데이터를 바꾼 모든 변경(INSERT/UPDATE/DELETE/DDL)을 순서대로 기록**한 로그입니다. 두 가지 용도:
1. **복제** — 레플리카가 이 로그를 받아 그대로 재생
2. **PITR(시점 복구)** — 전체 백업 + 그 이후 binlog 재생 = 원하는 시점까지 복구

```sql
SHOW BINARY LOGS;
```
```
+------------------+-----------+-----------+
| Log_name         | File_size | Encrypted |
+------------------+-----------+-----------+
| mysql-bin.000001 |       180 | No        |
| mysql-bin.000002 |   2998143 | No        |
| mysql-bin.000003 |      3973 | No        |
+------------------+-----------+-----------+
```

### binlog 포맷

| 포맷 | 기록 방식 | 특징 |
|---|---|---|
| **`ROW`** | 바뀐 **행의 before/after 이미지** | **8.0 기본값**. 가장 안전(비결정적 함수도 정확히 복제). 로그 크다 |
| `STATEMENT` | 실행된 **SQL 문 자체** | 로그 작다. 그러나 `NOW()`, `UUID()`, `LIMIT`(ORDER 없이) 등에서 소스≠레플리카 위험 |
| `MIXED` | 평소 STATEMENT, 위험한 문만 ROW | 절충안 |

```sql
SELECT @@binlog_format, @@gtid_mode, @@enforce_gtid_consistency;
```
```
+-----------------+-------------+----------------------------+
| @@binlog_format | @@gtid_mode | @@enforce_gtid_consistency |
+-----------------+-------------+----------------------------+
| ROW             | ON          | ON                         |
+-----------------+-------------+----------------------------+
```

### GTID (Global Transaction Identifier)

각 트랜잭션에 **전역 고유 ID**(`source_uuid:number`)를 붙입니다.
예전에는 복제를 붙일 때 "binlog 파일명 + 포지션"을 사람이 계산해야 했지만, GTID 는 **"어디까지 실행했는지"를 ID 로 알기에** 레플리카가 알아서 이어받습니다(`SOURCE_AUTO_POSITION=1`). 장애 시 레플리카 승격도 훨씬 안전합니다.

### PITR(Point-In-Time Recovery) 절차

```
[사고] 오후 3시에 실수로 대량 DELETE 를 커밋했다.

1) 가장 최근 전체 백업(예: 새벽 2시 mysqldump)을 복구한다
2) 그 이후 binlog 를 mysqlbinlog 로 뽑되, "사고 직전(2:59:59)"까지만 재생한다
     mysqlbinlog --start-datetime="02:00:00" --stop-datetime="14:59:59" \
       mysql-bin.0000NN | mysql ...
   (또는 GTID 로 --exclude-gtids 로 문제 트랜잭션만 건너뛴다)
3) → 사고 직전 상태로 복구 완료
```

> 💡 **실무 팁** — PITR 이 가능하려면 **평소에 binlog 가 켜져 있고(`log_bin`), 보관 기간이 충분**해야 합니다
> (`binlog_expire_logs_seconds`, 8.0 신규. 예전 `expire_logs_days` 대체). 사고 나고 켜면 늦습니다.

---

## 23-5. 복제 직접 구성하기 (실습)

별도 2노드를 띄웁니다. **learn-mysql8(3307)과 완전히 분리**됩니다.

```bash
cd mysql8/docker/replication
docker compose up -d          # repl-source(3308), repl-replica(3309)
```

`docker-compose.yml` 의 핵심 설정:

| 설정 | source | replica | 의미 |
|---|---|---|---|
| `server-id` | 1 | 2 | 복제 참여 노드는 **서로 다른** ID 필수 |
| `log-bin` | ON | ON | 레플리카도 켜둠(승격/체인 복제 대비) |
| `gtid-mode` / `enforce-gtid-consistency` | ON | ON | GTID 복제 |
| `binlog-format` | ROW | ROW | 안전한 복제 |
| `read_only`/`super_read_only` | - | 복제 구성 후 SQL 로 ON | 레플리카 쓰기 차단 |

> ⚠️ **함정** — `super_read_only=ON` 을 **컨테이너 부팅 옵션으로** 주면, MySQL 엔트리포인트가 root 계정을
> 만드는 초기화 쓰기까지 막혀 컨테이너가 죽습니다(`ERROR 1290 ... running with the --super-read-only`).
> 그래서 이 실습은 **복제 구성이 끝난 뒤 SQL 로** `SET GLOBAL super_read_only=ON` 을 켭니다.

### 구성 스크립트 실행

```bash
mysql8/step-23-backup-replication/setup-replication.sh
```

스크립트가 하는 일:

**1) source 에 복제 전용 계정(최소권한: `REPLICATION SLAVE` 만)**
```sql
CREATE USER 'repl'@'%' IDENTIFIED WITH caching_sha2_password BY 'Repl#Pass123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
```

**2) source 상태**
```
@@server_id: 1   @@gtid_mode: ON   @@log_bin: 1

SHOW MASTER STATUS:
             File: mysql-bin.000003
         Position: 2873
Executed_Gtid_Set: 8e93fb4b-7e5c-11f1-b261-ce0aa42dd4b9:1-18
```

**3) replica 에서 소스 지정 — `CHANGE REPLICATION SOURCE TO` (8.0.23+ 신문법)**
```sql
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='repl-source',      -- docker 네트워크 내부 컨테이너명
  SOURCE_PORT=3306,               -- 컨테이너 내부 포트
  SOURCE_USER='repl',
  SOURCE_PASSWORD='Repl#Pass123',
  SOURCE_AUTO_POSITION=1,         -- ★ GTID 자동 포지셔닝 (파일/포지션 불필요)
  GET_SOURCE_PUBLIC_KEY=1;        -- caching_sha2 공개키 자동 수신(학습 편의)
START REPLICA;
```

> 예전 문법 `CHANGE MASTER TO ... MASTER_HOST=... MASTER_LOG_FILE=...` 은 8.0 에서 **deprecated**.
> 8.0.22~23 부터 `master`→`source`, `slave`→`replica` 로 용어가 전면 교체됐습니다.

### 검증 — `SHOW REPLICA STATUS` (8.0.22+ 신용어)

```sql
SHOW REPLICA STATUS\G
```
**결과** (실제 출력)
```
           Replica_IO_Running: Yes          ← 소스에서 binlog 받아오는 스레드
          Replica_SQL_Running: Yes          ← 받은 걸 재생하는 스레드
        Seconds_Behind_Source: 0            ← 복제 지연(초). 0 = 실시간
                Last_IO_Error:
               Last_SQL_Error:
    Replica_SQL_Running_State: Replica has read all relay log; waiting for more updates
           Retrieved_Gtid_Set: 8e93fb4b-7e5c-11f1-b261-ce0aa42dd4b9:1-18
            Executed_Gtid_Set: 8e93fb4b-7e5c-11f1-b261-ce0aa42dd4b9:1-18
                Auto_Position: 1
```

**두 스레드가 모두 `Yes`, 지연 0** 이면 정상입니다. Retrieved 와 Executed GTID 셋이 같으니 다 따라잡았습니다.

### 복제 동작 확인 — source 에 쓰고 replica 에서 읽기

```bash
# source
mysql ... -P3308 -e "CREATE DATABASE replrepl; ...; INSERT INTO replrepl.t VALUES (1,'hello from source',NOW());"
# replica
mysql ... -P3309 -e "SELECT * FROM replrepl.t;"
```
**결과**
```
  [source] 행수:  2
  [replica] 행수: 2      ← 복제됨!
+----+-------------------+---------------------+
| id | msg               | at                  |
+----+-------------------+---------------------+
|  1 | hello from source | 2026-07-13 10:47:52 |
|  2 | second row        | 2026-07-13 10:47:52 |
+----+-------------------+---------------------+
```

replica 에 직접 쓰면 거부됩니다:
```
INSERT INTO replrepl.t VALUES (99,'should fail',NOW());
ERROR 1290 (HY000): The MySQL server is running with the --super-read-only option
                    so it cannot execute this statement
```

### 비동기 vs 반동기(semi-sync)

| | 비동기(기본) | 반동기(semi-synchronous) |
|---|---|---|
| 커밋 시점 | 소스가 자기 로그만 쓰면 즉시 완료 | 레플리카 **최소 1대가 받았다고 응답**할 때까지 대기 |
| 장애 시 | 소스가 죽으면 아직 안 넘어간 트랜잭션 유실 가능 | 유실 위험 감소 |
| 성능 | 빠름 | 왕복 대기만큼 느림 |
| 설정 | 기본 | `rpl_semi_sync_source`/`rpl_semi_sync_replica` 플러그인 설치 |

### 정리

```bash
cd mysql8/docker/replication
docker compose down -v        # 컨테이너 + 볼륨까지 삭제
```

> ⚠️ **함정** — `docker compose down` 만 하면 볼륨(데이터)이 남습니다. 다음 실습 때 이전 서버 UUID 가
> 그대로라 GTID 가 꼬일 수 있습니다. 실습 종료 시엔 **`-v`** 를 붙여 볼륨까지 지우세요.

---

## 정리

| 항목 | 요점 |
|---|---|
| 논리 백업 | `mysqldump --single-transaction --routines --triggers --events`. 무중단, 이식성 |
| 물리 백업 | XtraBackup. 대용량에서 빠름. 같은 버전/환경 필요 |
| 권장 논리 도구 | 대용량은 **mysqlsh dump-utility**(병렬/압축/재개). mysqlpump 는 deprecated |
| binlog | 모든 변경 기록 → 복제 + PITR 의 토대. 8.0 기본 포맷 **ROW** |
| GTID | 트랜잭션 전역 ID. `SOURCE_AUTO_POSITION=1` 로 자동 이어받기 |
| PITR | 전체 백업 복구 + 사고 직전까지 binlog 재생 |
| 복제 구성 | `CHANGE REPLICATION SOURCE TO`(8.0.23+), `SHOW REPLICA STATUS`(8.0.22+) |
| 정상 판정 | `Replica_IO_Running=Yes`, `Replica_SQL_Running=Yes`, `Seconds_Behind_Source=0` |
| 8.0 용어 변경 | master→source, slave→replica |

---

## 연습문제 (셸/개념)

1. `backup-restore.sh` 를 실행해 백업 파일을 만들고, `--routines` 를 **뺀** 덤프와 크기/내용을 비교하라. 저장 프로시저가 어디서 사라지는가?
2. `mysqldump ... shop customers orders` 처럼 **테이블 2개만** 백업하고, 새 DB 에 복구하라.
3. 복제를 구성한 뒤 `repl-source`(3308)에서 `products` 에 행을 하나 넣고, `repl-replica`(3309)에 몇 초 만에 반영되는지 `Seconds_Behind_Source` 로 관찰하라.
4. replica 에서 `STOP REPLICA;` 를 하고 source 에 데이터를 넣은 뒤, `START REPLICA;` 로 다시 켜면 밀린 트랜잭션이 따라잡히는지 확인하라(GTID 의 힘).
5. `SHOW REPLICA STATUS` 의 `Retrieved_Gtid_Set` 과 `Executed_Gtid_Set` 이 각각 무엇을 의미하는지, 복제 지연이 있을 때 둘의 차이로 어떻게 진단하는지 설명하라.

---

## 다음 단계

→ [Step 24 — 모니터링과 튜닝](../step-24-monitoring-tuning/)

---

## 실습 파일

이 스텝은 SQL 보다 **셸 스크립트**가 중심입니다. 먼저 `practice.sql` 로 learn-mysql8(3307)의 binlog/GTID 설정과 원본 행수·루틴 목록을 **읽기 전용으로 관찰**해 기준값을 잡고, 그다음 `backup-restore.sh` 로 mysqldump 백업과 별도 DB 복구를 한 바퀴 돌립니다. 마지막으로 `../docker/replication/docker-compose.yml` 로 2노드를 띄운 뒤 `setup-replication.sh` 를 실행해 GTID 기반 소스-레플리카 복제를 구성하고 검증합니다.

### practice.sql

23-4의 binlog·GTID 개념과 23-2의 복구 검증에 쓰이는 **관찰용 SQL**입니다. `mysql -h127.0.0.1 -P3307 -uroot -proot1234 -t < practice.sql` 로 실행합니다.

- 앞부분의 `SHOW VARIABLES LIKE 'log_bin'` / `'binlog_format'` / `'binlog_expire_logs_seconds'` / `'gtid_mode'` 는 본문 23-4에서 설명한 네 가지 설정을 실제 서버에서 눈으로 확인하는 대목입니다. 특히 `binlog_expire_logs_seconds` 는 8.0에서 `expire_logs_days` 를 대체한 신규 변수로, PITR 가능 기간을 결정합니다.
- `SHOW BINARY LOGS;` 와 `SHOW MASTER STATUS;`, `SHOW REPLICA STATUS;` 는 **의도적으로 주석 처리**되어 있습니다. learn-mysql8(3307)은 학습 편의상 binlog 가 꺼져 있을 수 있어 "You are not using binary logging" 에러가 나는 게 정상이고, 3307 은 레플리카가 아니라 복제 상태도 비어 있기 때문입니다. 주석을 풀어 직접 에러를 확인해 보는 것도 좋은 실습입니다.
- 파일 안의 `23-4. 복구 검증용` 주석 블록(파일 자체의 번호이며, 본문 23-4 절과는 별개입니다)에 있는 `UNION ALL` 행수 집계는 `shop` 의 7개 테이블(`orders`, `order_items`, `customers`, `products`, `payments`, `reviews`, `access_logs`)을 셉니다. `backup-restore.sh` 가 복구본과 대조할 **기준 스냅샷**이니 백업 전에 한 번 찍어 두세요. (단, `backup-restore.sh` 의 복구 검증 쿼리는 이 중 `orders`/`customers`/`products`/`access_logs` **4개만** 비교합니다. 나머지 3개까지 확인하고 싶다면 이 파일의 쿼리를 `shop_restore_test` 로 바꿔 한 번 더 돌려 보세요.)
- 마지막 `information_schema.ROUTINES` / `information_schema.EVENTS` 조회는 연습문제 1번과 직결됩니다. `--routines`, `--events` 를 뺀 덤프로 복구하면 여기 나오는 프로시저·이벤트가 복구본에서 **통째로 사라지는 것**을 이 쿼리로 비교해 확인합니다.
- 이 파일은 공용 서버의 설정을 **바꾸지 않습니다.** 전부 `SHOW`/`SELECT` 뿐이라 안심하고 여러 번 실행해도 됩니다.

```sql file="./practice.sql"
```

### backup-restore.sh

23-2 "논리 백업 — mysqldump 옵션 완전 해설" 을 그대로 실행하는 스크립트입니다. learn-mysql8(3307)의 `shop` DB 를 덤프하고, **원본을 덮어쓰지 않도록** 별도 DB 로 복구해 행수를 대조한 뒤 뒷정리까지 합니다.

- 백업 파일은 `SCRIPT_DIR/../backup` 즉 `mysql8/backup/` 에 `shop_YYYYMMDD_HHMMSS.sql` 이름으로 떨어집니다. `mkdir -p` 로 디렉터리를 만들고 `STAMP="$(date +%Y%m%d_%H%M%S)"` 로 실행마다 다른 파일명을 쓰므로 이전 백업을 덮어쓰지 않습니다(대신 반복 실행하면 파일이 쌓이니 `access_logs` 100만 행짜리 덤프가 수백 MB씩 늘어납니다).
- 1)단계의 `mysqldump` 는 본문 표에 나온 옵션을 총동원합니다. `--single-transaction` 으로 무중단 스냅샷, `--routines`/`--events` 로 기본 제외되는 프로시저·이벤트까지 포함, `--set-gtid-purged=AUTO` 로 GTID 정보 삽입, `--hex-blob`/`--default-character-set=utf8mb4` 로 바이너리·한글 안전성을 확보합니다.
- 2)단계의 복구가 이 스크립트의 핵심 트릭입니다. `--databases shop` 으로 뜬 덤프에는 `CREATE DATABASE`/`USE shop` 이 박혀 있어 그대로 넣으면 **원본을 덮어씁니다.** 그래서 `sed` 치환으로 백틱 감싼 `shop` 을 `shop_restore_test` 로 바꾸고, 동시에 `/SET @@GLOBAL.GTID_PURGED/d` 와 `/SET @@SESSION.SQL_LOG_BIN/d` 로 복구 대상이 아닌 GTID·binlog 제어문을 지웁니다. (GTID_PURGED 를 그대로 실행하면 "GTID_PURGED can only be set when GTID_EXECUTED is empty" 류의 에러가 납니다.)
- 이어지는 `SELECT ... UNION ALL` 이 `shop` 과 `shop_restore_test` 의 `orders`/`customers`/`products`/`access_logs` 행수를 나란히 찍습니다. 본문의 "src = restored" 출력이 바로 이것으로, **복구가 실제로 되는지**를 증명하는 부분입니다.
- 3)단계는 `${DUMP_CMD} ... ${DB} customers` 처럼 **DB명 뒤에 테이블명만** 붙여 부분 백업을 시연합니다(`--databases` 가 없으므로 `CREATE DATABASE` 문이 들어가지 않아 아무 DB 에나 넣을 수 있습니다). 연습문제 2번의 출발점입니다.
- ⚠️ 4)단계에서 `DROP DATABASE IF EXISTS shop_restore_test` 를 실행합니다. **파괴적 명령**이지만 대상이 복구 테스트용 DB 뿐이며, 2)단계 시작에서도 같은 DROP 을 먼저 하므로 그 이름의 DB 를 따로 쓰고 있다면 날아갑니다. `set -euo pipefail` 때문에 중간에 하나라도 실패하면 즉시 멈춥니다.

```bash file="./backup-restore.sh"
```

### setup-replication.sh

23-5 "복제 직접 구성하기" 의 본체입니다. **반드시 먼저** `cd mysql8/docker/replication && docker compose up -d` 로 repl-source(3308)·repl-replica(3309)를 띄운 뒤 실행합니다. 3307 은 전혀 건드리지 않습니다.

- 0)단계는 두 노드가 뜰 때까지 기다립니다. `for i in $(seq 1 60)` 안에서 `SELECT 1` 을 던지고 실패하면 `sleep 2` 하므로, 포트당 **최대 120초**까지 기동을 기다리는 셈입니다. MySQL 컨테이너 초기화가 느린 환경을 위한 안전장치입니다.
- 1)단계는 source 에 복제 전용 계정을 만듭니다. `GRANT REPLICATION SLAVE ON *.*` 하나만 주는 **최소권한** 원칙이고(Step 22와 이어집니다), `ALTER USER ... REQUIRE NONE` 은 `caching_sha2_password` 를 TLS 없이 쓰기 위한 **학습 편의 예외**입니다. 운영에서는 `REQUIRE SSL` 을 쓰세요.
- 2)단계에서 `-E` 플래그를 쓰는 이유가 주석에 있습니다. 일부 클라이언트는 `-e` 안의 `\G` 를 거부하므로 세로 출력은 `-E` 로 냅니다. `SHOW MASTER STATUS` 는 8.0에서도 여전히 그 이름입니다.
- 3)단계가 8.0 신문법의 핵심입니다. `STOP REPLICA; RESET REPLICA ALL;` 로 이전 설정을 지우고 시작해 **재실행해도 안전**하게 만들었고, `SOURCE_HOST='repl-source'` 는 docker 네트워크 안의 **컨테이너 이름**, `SOURCE_PORT=3306` 은 **컨테이너 내부 포트**입니다(호스트의 3308이 아닙니다 — 흔한 실수). `SOURCE_AUTO_POSITION=1` 덕분에 binlog 파일명/포지션을 사람이 계산할 필요가 없고, `GET_SOURCE_PUBLIC_KEY=1` 은 caching_sha2 공개키를 자동 수신합니다.
- 4)단계의 `SET GLOBAL super_read_only = ON` 을 **복제 구성이 끝난 뒤에** 켜는 것이 포인트입니다. 본문 함정 상자대로 이걸 컨테이너 부팅 옵션으로 주면 엔트리포인트의 초기화 쓰기까지 막혀 컨테이너가 죽습니다.
- 5~7)단계는 검증입니다. `sleep 3` 뒤 `SHOW REPLICA STATUS` 의 수십 개 컬럼 중 진단에 필요한 것만 `grep` 으로 추립니다 — 두 스레드(`Replica_IO_Running`, `Replica_SQL_Running`), 지연(`Seconds_Behind_Source`), 에러(`Last_IO_Error`/`Last_SQL_Error`), 그리고 GTID 진행 상황(`Retrieved_Gtid_Set`, `Executed_Gtid_Set`, `Auto_Position`)까지입니다. 위 "검증" 절의 출력이 정확히 이 grep 결과입니다. 이어서 `replrepl.t` 에 두 행을 넣어 replica 에서 읽히는지 확인하고, 마지막으로 replica 에 직접 INSERT 해 `ERROR 1290 ... --super-read-only` 가 나는 것까지 보여줍니다. INSERT 문에 `ON DUPLICATE KEY UPDATE` 를 붙여 둔 덕분에 스크립트를 여러 번 돌려도 중복 키 에러가 나지 않습니다.
- 💡 참고 — 5)단계의 `grep` 은 `set -euo pipefail` 아래에 있습니다. 복제가 아예 구성되지 않아 `SHOW REPLICA STATUS` 가 빈 결과를 내면 grep 이 매칭 실패(exit 1)로 끝나 **스크립트가 그 자리에서 멈춥니다.** 6)단계 출력이 아예 안 보인다면 3)단계가 실패한 것이니 `SHOW REPLICA STATUS\G` 를 직접 쳐서 `Last_IO_Error` 를 확인하세요.
- ⚠️ 실습이 끝나면 `docker compose down -v` 로 **볼륨까지** 지우세요. `-v` 를 빠뜨리면 서버 UUID 가 남아 다음 실습에서 GTID 가 꼬입니다.

```bash file="./setup-replication.sh"
```
