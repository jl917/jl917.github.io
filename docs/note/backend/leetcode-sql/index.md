# LeetCode SQL 문제 해설

[sql2 — SQL 문제 풀이](../sql2.md)의 85문항을 **왜 이렇게 푸는지**까지 단계별로 해설합니다.
정답만 외우는 게 아니라, **문제를 보고 어떤 도구를 꺼낼지 판단하는 힘**을 기르는 것이 목표입니다.

## 각 문제 해설의 구성

- **문제** — 테이블 스키마와 요구사항을 한국어로 요약
- **정답 SQL** — 표준적이고 실무에서 통하는 풀이
- **풀이 (왜 이렇게 하는가)** — 사고 과정을 단계별로
- **핵심 개념** — 이 문제로 익히는 것
- ⚠️ **흔한 실수 / 함정** — 에러 없이 조용히 틀리는 지점
- 💡 **대안 / 응용** — 다른 풀이, 실무 확장

## 커리큘럼

| STEP | 주제 | 핵심 |
|---|---|---|
| [1](step-01-basics.md) | SQL 기초 | SELECT, WHERE, LEFT JOIN, DISTINCT, Self Join, NOT EXISTS |
| [2](step-02-sort-filter.md) | 정렬과 조건 | MIN, GROUP BY, LIKE, CASE, 문자열 함수, DELETE |
| [3](step-03-groupby-having.md) | GROUP BY & HAVING | 집계, HAVING, 조건부 집계, 자기조인 날짜 |
| [4](step-04-join.md) | JOIN | 안티조인, 상관 서브쿼리, Top-N, 연속값 |
| [5](step-05-subquery.md) | 서브쿼리 | 스칼라/상관 서브쿼리, N번째 값, 누적 |
| [6](step-06-case-when.md) | CASE WHEN | 조건부 집계, 피벗, 행↔열 변환 |
| [7](step-07-string-date.md) | 문자열 / 날짜 | 문자열 가공, 날짜 연산, 연속 구간 |
| [8](step-08-window.md) | Window Function | RANK, ROW_NUMBER, LAG/LEAD, 프레임 |
| [9](step-09-cte.md) | CTE | WITH, 재귀 CTE |
| [10](step-10-final.md) | 실전 종합 | 여러 기법을 조합한 고난도 |

> 함께 보면 좋은 자료: [MySQL 마스터 체크리스트](../sql3.md) · [MySQL 8 완전 학습 코스](../../reference/mysql8/index.md)

> 📌 STEP 5·8·9 에는 앞 STEP 과 **같은 문제**(예: 178, 185, 1321)가 다시 등장합니다. 같은 문제를 **서브쿼리 → 윈도우 함수 → CTE** 로 각각 다르게 푸는 것을 비교하며, "어떤 도구가 언제 더 나은가"를 익히기 위한 의도적 반복입니다.
