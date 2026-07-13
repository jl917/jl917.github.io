# Docker 실습 환경

이 코스의 모든 실습은 **Docker 컨테이너 안의 MySQL 8.0**에서 진행합니다. 여러분의 노트북에 MySQL을 직접 설치할 필요가 없고, 실습을 망쳐도 명령 한 줄이면 처음 상태로 되돌릴 수 있기 때문입니다.

이 페이지는 그 실습 환경이 **어떻게 구성되어 있는지**를 설명합니다. Step 01에서 컨테이너를 처음 띄울 때, Step 23에서 복제 클러스터를 만들 때, 그리고 "왜 내 환경에서는 이 값이 이렇게 나오지?" 싶을 때 다시 찾아오면 됩니다.

## 전체 구성

이 디렉터리는 두 개의 독립적인 스택으로 이루어져 있습니다.

| 스택 | 파일 | 컨테이너 | 포트 | 언제 씁니까 |
|------|------|----------|------|-------------|
| **학습용 단일 노드** | `docker-compose.yml` | `learn-mysql8` | `3307` | Step 01 ~ Step 25 (거의 전 과정) |
| **복제 실습용 2노드** | `replication/docker-compose.yml` | `repl-source`, `repl-replica` | `3308`, `3309` | Step 23 (백업과 복제) |

학습용 스택은 두 개의 보조 파일을 마운트해서 씁니다.

- `conf/my.cnf` — 서버 설정. 슬로우 쿼리 로그, 타임존, sql_mode 등을 "관찰하기 좋게" 맞춰 둡니다.
- `initdb/00-grants.sql` — 컨테이너가 **처음 만들어질 때 딱 한 번** 자동 실행되어 `learner` 계정에 권한을 부여합니다.

두 스택은 포트도, 컨테이너 이름도, 볼륨도 겹치지 않습니다. 복제 실습을 하는 동안에도 `learn-mysql8`은 그대로 살아 있습니다.

## 사용법

### 학습용 환경 (Step 01부터 계속 씁니다)

```bash
cd mysql8/docker
docker compose up -d

# 헬스체크가 healthy 가 될 때까지 기다립니다 (보통 20~40초)
docker inspect -f '{{.State.Health.Status}}' learn-mysql8

# 접속
mysql -h 127.0.0.1 -P 3307 -ulearner -plearn1234 shop
```

컨테이너 안에서 바로 접속해도 됩니다.

```bash
docker exec -it learn-mysql8 mysql -ulearner -plearn1234 shop
```

실습이 꼬였다면 아래 한 줄로 완전히 초기화합니다. **볼륨까지 삭제되므로 그동안 넣은 데이터는 전부 사라집니다.**

```bash
docker compose down -v && docker compose up -d
```

### 복제 실습 환경 (Step 23에서만)

```bash
cd mysql8/docker/replication
docker compose up -d
../../step-23-backup-replication/setup-replication.sh

# 실습이 끝나면 볼륨까지 정리
docker compose down -v
```

## 실습 파일

아래 네 개 파일이 이 실습 환경의 전부입니다. 순서대로 보면, `docker-compose.yml`이 컨테이너를 정의하고, 그 안에서 `conf/my.cnf`가 서버 설정으로 마운트되며, 컨테이너가 처음 뜰 때 `initdb/00-grants.sql`이 자동 실행되어 계정 권한을 세팅합니다. `replication/docker-compose.yml`은 Step 23에서만 쓰는 별도 스택입니다.

### docker-compose.yml

Step 01에서 `docker compose up -d`로 실행하는, 이 코스의 **메인 컨테이너 정의**입니다.

- `ports: "3307:3306"` — 컨테이너의 3306을 호스트의 **3307**로 내보냅니다. 로컬에 이미 MySQL이 3306으로 떠 있어도 충돌하지 않습니다. 그래서 접속할 때 항상 `-P 3307`을 붙여야 합니다.
- `MYSQL_DATABASE: shop`, `MYSQL_USER: learner`, `MYSQL_PASSWORD: learn1234` — 최초 기동 시 `shop` 데이터베이스와 `learner` 계정이 자동으로 만들어집니다. root 비밀번호는 `root1234`입니다.
- `command`의 `--character-set-server=utf8mb4` / `--collation-server=utf8mb4_0900_ai_ci` — 한글과 이모지를 안전하게 저장하기 위한 기본값입니다. `my.cnf`에도 같은 값이 있지만, 커맨드라인 인자가 최종적으로 이깁니다.
- `volumes` 세 줄이 핵심입니다. `./conf/my.cnf`는 `/etc/mysql/conf.d/learn.cnf`로 **읽기 전용(`:ro`)** 마운트되고, `./initdb`는 `/docker-entrypoint-initdb.d`로, `../sql`은 `/sql`로 마운트됩니다. 마지막 덕분에 컨테이너 안에서 `source /sql/01_schema.sql` 같은 식으로 샘플 데이터베이스 스크립트를 바로 실행할 수 있습니다.
- `healthcheck`는 `mysqladmin ping`을 `interval: 5s`로 최대 `retries: 20`번 시도합니다. 즉 **최대 100초까지** 기동을 기다려 줍니다. MySQL은 첫 기동 시 데이터 디렉터리를 초기화하느라 시간이 꽤 걸리므로, `healthy`가 뜨기 전에 접속하면 "Can't connect" 에러가 나는 게 정상입니다.
- `volumes: mysql8-data` — 이름 있는 볼륨이라 `docker compose down`만으로는 지워지지 않습니다. 데이터를 정말 지우려면 `-v` 옵션이 필요합니다.

```yaml file="./docker-compose.yml"
```

### conf/my.cnf

컨테이너의 `/etc/mysql/conf.d/learn.cnf`로 마운트되는 서버 설정 파일입니다. 운영용 튜닝이 아니라 **"내부 동작을 관찰하기 좋은"** 설정이라는 점이 중요합니다.

- `default_time_zone = '+09:00'` — 컨테이너 기본 타임존은 UTC입니다. 이걸 KST로 고정해 두었기 때문에 Step 12에서 `NOW()`가 한국 시간으로 나옵니다.
- `innodb_buffer_pool_size = 256M` — 학습용이라 일부러 작게 잡았습니다. 실제 운영에서는 물리 메모리의 50~70%를 씁니다.
- `slow_query_log = 1` + `long_query_time = 0.5` + `log_queries_not_using_indexes = 1` — **0.5초 이상 걸린 쿼리와 인덱스를 안 탄 쿼리**가 모두 `/var/lib/mysql/slow.log`에 쌓입니다. Step 15(인덱스)와 Step 24(모니터링)에서 이 로그를 직접 열어 봅니다. 처음 실습할 때 로그가 금방 커지는 게 정상입니다.
- `general_log = 0` — 일반 쿼리 로그는 **꺼 둔 채로 시작합니다.** 모든 쿼리를 다 기록하기 때문에 켜 두면 로그가 순식간에 불어납니다. Step 24에서 필요할 때만 `SET GLOBAL general_log = 1`로 잠깐 켰다가 다시 끕니다.
- `event_scheduler = ON` — Step 20의 `CREATE EVENT` 실습이 실제로 동작하려면 이 값이 켜져 있어야 합니다. MySQL 기본값은 OFF입니다.
- `local_infile = 1` — Step 03의 `LOAD DATA LOCAL INFILE` 대량 적재 실습을 위해 켭니다.
- `sql_mode`에 `ONLY_FULL_GROUP_BY`와 `STRICT_TRANS_TABLES`를 **일부러 남겨 두었습니다.** Step 06에서 GROUP BY 에러를 직접 겪어 봐야 왜 이 모드가 있는지 이해할 수 있기 때문입니다. 에러가 난다고 이 값을 끄지 마세요.

```ini file="./conf/my.cnf"
```

### initdb/00-grants.sql

`/docker-entrypoint-initdb.d`에 마운트되어, **컨테이너의 데이터 볼륨이 비어 있을 때(= 최초 기동 시) 딱 한 번** 자동 실행됩니다. 이미 볼륨이 있으면 실행되지 않으므로, 이 파일을 고쳐도 `docker compose down -v`로 볼륨을 지우고 다시 띄우지 않으면 반영되지 않습니다.

- `GRANT ALL PRIVILEGES ON *.* TO 'learner'@'%'` — `learner`가 DB 생성/삭제(Step 02), `performance_schema`·`sys` 조회(Step 24), 프로세스 목록 조회(Step 19)까지 다 할 수 있게 열어 줍니다.
- `GRANT SYSTEM_VARIABLES_ADMIN` / `SESSION_VARIABLES_ADMIN` — MySQL 8.0의 **동적 권한(Dynamic Privileges)** 은 `ALL PRIVILEGES`에 포함되지 않습니다. `SET GLOBAL`이나 `SET PERSIST`를 쓰려면 반드시 따로 부여해야 합니다. Step 24에서 일반 쿼리 로그를 동적으로 켤 때 이 권한이 필요합니다.
- ⚠️ **이건 학습 환경이라서 이렇게 하는 것입니다.** 운영 환경에서 애플리케이션 계정에 `ALL PRIVILEGES ON *.*`를 주는 것은 심각한 보안 사고입니다. 올바른 최소 권한 원칙은 Step 22에서 배웁니다.

```sql file="./initdb/00-grants.sql"
```

### replication/docker-compose.yml

Step 23(백업과 복제)에서만 쓰는 **2노드 복제 클러스터**입니다. `docker compose up -d`로 띄운 뒤 `step-23-backup-replication/setup-replication.sh`를 실행하면 복제가 구성됩니다. 포트(3308/3309)와 컨테이너 이름(`repl-source`/`repl-replica`)이 학습용 스택과 완전히 분리되어 있으므로, `learn-mysql8`은 영향을 받지 않습니다.

- **source (3308, `server_id=1`)** — `--log-bin=mysql-bin`으로 바이너리 로그를 켭니다. 이것이 복제와 PITR(시점 복구)의 전제조건입니다. `--binlog-format=ROW`는 8.0 기본값이지만 안전한 복제를 위해 명시했습니다.
- `--gtid-mode=ON` + `--enforce-gtid-consistency=ON` — GTID 기반 복제를 씁니다. 후자는 GTID와 호환되지 않는 SQL(예: 트랜잭션 안의 `CREATE TABLE ... SELECT`)을 아예 막아 줍니다. 실습 중 이런 문법이 거부되면 버그가 아니라 의도된 동작입니다.
- `--binlog-expire-logs-seconds=604800` — binlog를 7일간 보관합니다. 8.0에서 새로 생긴 옵션으로, 예전의 `expire_logs_days`를 대체합니다.
- **replica (3309, `server_id=2`)** — `--relay-log=relay-bin`으로 릴레이 로그를 지정하고, 레플리카에서도 `--log-bin`을 켭니다. 나중에 이 노드를 source로 승격하거나 체인 복제를 할 때를 대비한 것입니다.
- `read_only` / `super_read_only`를 **compose 파일에서 켜지 않은 이유**가 주석에 적혀 있습니다. 부팅 시점에 켜면 컨테이너 초기화(root 계정 생성 등) 자체가 막혀 버리기 때문입니다. 그래서 `setup-replication.sh`가 복제를 구성한 **직후에** SQL로 켭니다.
- `depends_on: source: condition: service_healthy` — 레플리카는 source의 헬스체크가 통과한 뒤에야 시작합니다. 그래도 `setup-replication.sh`는 두 노드가 모두 준비된 다음에 실행해야 합니다.
- 실습이 끝나면 `docker compose down -v`로 **볼륨까지** 정리하세요. 남겨 두면 다음에 다시 띄울 때 이전 GTID 상태가 남아 복제 구성이 꼬입니다.

```yaml file="./replication/docker-compose.yml"
```
