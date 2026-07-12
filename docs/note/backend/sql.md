# SQL

SQL(Structured Query Language)은 미국 국가표준협회(ANSI)에 의해 관계형 데이터베이스 언어의 미국 표준으로 확정되었고, 이후 국제표준화기구(ISO)에 의해 관계형 데이터베이스 언어의 국제 표준으로 채택되었다. 데이터베이스 관리 시스템은 SQL을 통해 데이터베이스를 관리하며, 데이터를 정의·조작하고 데이터의 무결성과 보안을 유지한다.

## SQL 분류

- DDL(Data Definition Language, 데이터 정의어): 데이터베이스, 테이블, 컬럼 등의 구조를 정의·조작한다. 대표 구문: CREATE, ALTER, DROP, TRUNCATE
- DML(Data Manipulation Language, 데이터 조작어): 테이블에 저장된 데이터를 조작한다. 대표 구문: INSERT, UPDATE, DELETE
- DCL(Data Control Language, 데이터 제어어): 접근 권한과 보안 수준을 제어한다. 대표 구문: GRANT, REVOKE
- DQL(Data Query Language, 데이터 질의어): 데이터를 조회한다. 대표 구문: SELECT

> 참고: DQL은 DML의 일부로 분류하기도 한다. DCL의 `DENY`는 표준 SQL이 아닌 SQL Server 전용 구문이며, 표준에서는 권한 회수에 `REVOKE`를 사용한다. `TRUNCATE`는 DDL로 분류된다.

## Normal Form

1. 제1정규형 (1NF: First Normal Form)
   - 목표: 테이블의 모든 컬럼이 원자값(Atomic)을 갖도록 합니다.
   - 핵심 규칙: 하나의 컬럼에는 하나의 값만 있어야 하며, 콤마(,) 등으로 구분된 여러 값이 하나의 셀에 들어가면 안 됩니다.

2. 제2정규형 (2NF: Second Normal Form)
   - 목표: 1NF를 만족하고, 부분 함수 종속(Partial Functional Dependency)을 제거합니다.
   - 핵심 규칙: 기본키(Primary Key)가 2개 이상의 컬럼(복합키)으로 구성된 경우, 일반 컬럼은 복합키 전체에 종속되어야 합니다. 복합키의 일부분에만 종속되는 컬럼이 있다면 이를 분리합니다.

3. 제3정규형 (3NF: Third Normal Form)
   - 목표: 2NF를 만족하고, 이행적 종속(Transitive Dependency)을 제거합니다.
   - 핵심 규칙: 기본키가 아닌 일반 컬럼이 다른 일반 컬럼을 결정하는 현상(A → B, B → C)을 없앱니다. 모든 일반 컬럼은 오직 기본키에만 직접 종속되어야 합니다.

## 데이터 유형

> MySQL 기준으로 정리한 데이터 타입입니다.

### 숫자형

| 타입 | 저장 크기 | 설명 / 범위 |
|------|-----------|-------------|
| `BIT(M)` | 약 (M+7)/8 byte | 비트 값 저장. M = 1~64 |
| `TINYINT` | 1 byte | 정수. SIGNED −128 ~ 127 / UNSIGNED 0 ~ 255 |
| `SMALLINT` | 2 byte | 정수. SIGNED −32,768 ~ 32,767 / UNSIGNED 0 ~ 65,535 |
| `MEDIUMINT` | 3 byte | 정수. SIGNED −8,388,608 ~ 8,388,607 / UNSIGNED 0 ~ 16,777,215 |
| `INT` (`INTEGER`) | 4 byte | 정수. SIGNED −21억 ~ 21억 / UNSIGNED 0 ~ 약 42억 |
| `BIGINT` | 8 byte | 정수. SIGNED −2⁶³ ~ 2⁶³−1 / UNSIGNED 0 ~ 2⁶⁴−1 |
| `DECIMAL(M, D)` (`NUMERIC`) | 가변 | 고정 소수점(정확한 값). M = 전체 자릿수(최대 65), D = 소수 자릿수(최대 30). 예) `DECIMAL(6, 2)` → 전체 6자리, 소수점 이하 2자리 |
| `FLOAT` | 4 byte | 단정밀도 부동소수점(근사값) |
| `DOUBLE` (`REAL`) | 8 byte | 배정밀도 부동소수점(근사값) |
| `BOOL` (`BOOLEAN`) | 1 byte | `TINYINT(1)`의 별칭. 0 = false, 그 외 = true |

### 날짜와 시간

| 타입 | 저장 크기 | 설명 / 범위 |
|------|-----------|-------------|
| `YEAR` | 1 byte | 연도. 1901 ~ 2155 |
| `DATE` | 3 byte | 날짜. `1000-01-01` ~ `9999-12-31` |
| `TIME` | 3 byte(+) | 시간/경과시간. `-838:59:59` ~ `838:59:59` |
| `DATETIME` | 5 byte(+) | 날짜 + 시간. `1000-01-01 00:00:00` ~ `9999-12-31 23:59:59`. **타임존 영향 없음** |
| `TIMESTAMP` | 4 byte(+) | UTC로 저장되며 조회 시 세션 타임존 반영. `1970-01-01` ~ `2038-01-19` |

### 문자 / 이진

| 타입 | 저장 크기 | 설명 |
|------|-----------|------|
| `CHAR(M)` | M byte (고정) | 고정 길이 문자열. M = 0~255. 실제 길이와 무관하게 항상 M byte 사용 |
| `VARCHAR(M)` | 실제 길이 + 1~2 byte | 가변 길이 문자열. M = 0~65,535 |
| `BINARY(M)` | M byte (고정) | 고정 길이 이진 문자열 |
| `VARBINARY(M)` | 실제 길이 + 1~2 byte | 가변 길이 이진 문자열 |
| `TINYTEXT` | 최대 255 byte | 짧은 텍스트 |
| `TEXT` | 최대 64 KB | 텍스트 |
| `MEDIUMTEXT` | 최대 16 MB | 중간 길이 텍스트 |
| `LONGTEXT` | 최대 4 GB | 긴 텍스트 |
| `TINYBLOB` | 최대 255 byte | 짧은 이진 데이터 |
| `BLOB` | 최대 64 KB | 이진 데이터 |
| `MEDIUMBLOB` | 최대 16 MB | 중간 길이 이진 데이터 |
| `LONGBLOB` | 최대 4 GB | 긴 이진 데이터 |
| `ENUM` | 1~2 byte | 정의된 목록 중 **하나**의 값 (최대 65,535개) |
| `SET` | 1~8 byte | 정의된 목록 중 **0개 이상**의 값 (최대 64개) |

> `CHAR(M)`은 삽입된 값의 실제 길이와 관계없이 항상 M byte를 차지합니다. 반면 `VARCHAR(M)`은 실제 길이에 길이 저장용 1~2 byte(M ≤ 255면 1 byte, 그보다 크면 2 byte)를 더한 만큼 차지합니다.

### 기타 (공간 / JSON)

| 타입 | 설명 |
|------|------|
| `JSON` | JSON 문서 저장 및 유효성 검증 (MySQL 5.7+) |
| `GEOMETRY` | 모든 공간(spatial) 타입의 상위 타입 |
| `POINT` | 점 |
| `LINESTRING` | 선 |
| `POLYGON` | 다각형 |
| `MULTIPOINT` | 점 집합 |
| `MULTILINESTRING` | 선 집합 |
| `MULTIPOLYGON` | 다각형 집합 |
| `GEOMETRYCOLLECTION` | 공간 객체 집합 |
