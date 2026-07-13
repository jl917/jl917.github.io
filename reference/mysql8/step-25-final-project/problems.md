# Step 25 — 최종 프로젝트 : 문제

지금까지 배운 전부(SELECT, JOIN, 서브쿼리, CTE, 윈도우 함수, 인덱스, EXPLAIN, 트랜잭션, 스키마 설계)를 총동원합니다.
정답과 채점 기준은 [`solutions.md`](./solutions.md) 에 있습니다. **먼저 스스로 풀고** 나서 여세요.

> 모든 쿼리는 `shop` DB 에서 실행합니다.
> `mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop`
> 공용 테이블은 **읽기만** 하고, 새 테이블은 `s25_` 접두사로 만드세요.
> 매출은 취소 주문(`status='CANCELLED'`)을 제외하고, `order_items.quantity * unit_price` 로 계산합니다.

---

## Part 1 — 비즈니스 리포트 12문제 (SQL 작성)

각 문제 아래에 **요구 출력**과 **핵심 힌트**, **배점**이 있습니다.

### Q1. 월별 매출 리포트 (10점)
2024년 각 월(`YYYY-MM`)의 **주문 건수**와 **매출 합계**를 구하라.
- 힌트: `DATE_FORMAT`, `JOIN order_items`, `GROUP BY 월`
- 채점: 취소 제외 여부 / 월 포맷 / 매출 계산식

### Q2. 카테고리별 매출 순위 (10점)
카테고리별 총매출을 구하고 **매출 내림차순 순위**(`RANK`)를 매겨라.
- 힌트: `categories JOIN products JOIN order_items`, 윈도우 `RANK() OVER (ORDER BY ...)`

### Q3. RFM 세그먼트 (15점)
고객별 **R**(최근성: 마지막 주문일로부터의 경과일), **F**(빈도: 주문 수), **M**(금액: 총구매액)을 구하고,
각각을 `NTILE(5)` 로 1~5 점수화한 뒤, 아래 규칙으로 세그먼트를 붙여라.
- `r>=4 AND f>=4` → `Champions` / `r>=4` → `Recent` / `f>=4` → `Loyal` / `r<=2 AND f<=2` → `At Risk` / 그 외 `Others`
- 기준일은 `2025-12-31`.
- 힌트: `CTE` 두 단계(집계 → NTILE), `DATEDIFF`, `CASE`
- 채점: R 은 경과일이 **작을수록** 높은 점수여야 함(`NTILE(5) OVER (ORDER BY recency DESC)`)

### Q4. 카테고리별 전년 대비 성장률 (15점)
카테고리 × 연도 매출을 구하고, **전년 대비 성장률(%)** 을 `LAG` 로 계산하라.
- 힌트: `LAG(rev) OVER (PARTITION BY category ORDER BY year)`
- 채점: 첫 해는 `NULL` / 성장률 = `(올해-작년)/작년*100`

### Q5. 재구매율 (10점)
전체 구매 고객 중 **2회 이상** 주문한 고객의 비율을 구하라.
- 힌트: 고객별 주문 수 CTE → `SUM(cnt>=2)/COUNT(*)`

### Q6. 이탈(휴면) 고객 (10점)
기준일(`2025-12-31`)로부터 **180일 넘게** 주문이 없거나, **주문 이력이 아예 없는** 고객을 찾아라.
- 힌트: `LEFT JOIN`, `HAVING days_since > 180 OR last_order IS NULL`

### Q7. 장바구니 상품 조합 (15점)
같은 주문에 **함께 담긴 상품 쌍**을 세어 상위 조합을 구하라(2회 이상).
- 힌트: `order_items` **셀프조인** with `a.product_id < b.product_id` (중복/자기자신 제거)
- 채점: `<` 로 쌍 중복 제거했는가

### Q8. 코호트 리텐션 (15점)
**첫 주문 월**을 코호트로 삼아, 코호트별로 이후 0/1/2/3개월차에 활동한 고객 수를 피벗하라.
- 힌트: 첫 주문월 CTE → `TIMESTAMPDIFF(MONTH, ...)` 로 월차 계산 → `COUNT(DISTINCT CASE WHEN month_no=k ...)`

### Q9. 등급별 평균 주문금액(AOV) (10점)
고객 등급(`grade`)별 주문 수, 총매출, **평균 주문금액**을 구하라.
- 힌트: `AOV = 매출 / 주문수`, `ORDER BY FIELD(grade,'VIP','GOLD','SILVER','BRONZE')`

### Q10. 결제수단 분포 (10점)
완료된 결제(`status='DONE'`)를 수단별로 집계하고 **전체 대비 비율(%)** 을 구하라.
- 힌트: `COUNT(*) / SUM(COUNT(*)) OVER ()`

### Q11. 리뷰 평점과 판매량 (10점)
리뷰가 있는 상품에 대해 **평균 평점**과 **판매 수량**을 함께 보여라.
- 힌트: `products LEFT JOIN reviews LEFT JOIN order_items`, `HAVING reviews > 0`

### Q12. 월별 누적 매출 (running total) (10점)
월별 매출과 **누적 매출**을 함께 구하라.
- 힌트: `SUM(rev) OVER (ORDER BY 월)`

---

## Part 2 — 느린 쿼리 튜닝 3문제

`slow-queries.sql` 을 실행하면 100만 행 `s25_logs` 와 느린 쿼리 3개가 준비됩니다.
각 쿼리를 `EXPLAIN ANALYZE` 로 진단하고, **결과는 동일하되 빠른** 버전으로 고치세요.

### T1. 함수로 감싼 날짜 (15점)
```sql
SELECT COUNT(*) FROM s25_logs WHERE DATE(logged_at) = '2024-06-15';
```
- 무엇이 문제인가? 어떻게 고치면 인덱스를 탈 수 있는가?
- 채점: 인덱스 추가 + **sargable**(컬럼에 함수를 씌우지 않은) 조건으로 재작성 / 결과 동일

### T2. 앞쪽 와일드카드 LIKE (15점)
```sql
SELECT COUNT(*) FROM s25_logs WHERE path LIKE '%detail%';
```
- 왜 인덱스가 안 걸리는가? 인덱스를 걸어도 왜 크게 안 빨라지는가?(선택도)
- 채점: "앞 와일드카드 = 인덱스 불가" 설명 + 현실적 대안 제시(정확/접두 매치, 전문검색 인덱스, 데이터 모델 변경 중 택)

### T3. 상관 서브쿼리 (15점)
```sql
SELECT c.name,
       (SELECT COUNT(*) FROM orders o
        WHERE o.customer_id = c.customer_id AND o.status <> 'CANCELLED') AS order_count
FROM customers c ORDER BY order_count DESC LIMIT 5;
```
- 상관 서브쿼리를 `JOIN + GROUP BY` 로 바꾸면 무엇이 좋아지는가?
- 채점: JOIN 재작성 / `LEFT JOIN` 으로 주문 0건 고객 보존 / 결과 동일

---

## Part 3 — 스키마 설계 과제 : "쿠폰" 도메인

쇼핑몰에 **쿠폰** 기능을 추가하려 합니다. 아래 요구사항을 만족하는 스키마를 설계하세요.
(테이블은 `s25_coupon`, `s25_coupon_issue` 등 `s25_` 접두사)

**요구사항**
1. 쿠폰은 **정액 할인**(예: 5,000원)과 **정률 할인**(예: 10%, 최대 20,000원) 두 종류가 있다.
2. 쿠폰마다 **유효기간**(시작/종료), **최소 주문금액**, **총 발급 수량 한도**가 있다.
3. 쿠폰은 특정 **고객에게 발급**되며, 고객은 발급받은 쿠폰을 **주문에 1회 사용**한다.
4. 같은 쿠폰을 한 고객이 **중복 발급받지 않도록** 막아야 한다.
5. 어떤 주문에 어떤 쿠폰이 얼마 할인에 쓰였는지 **추적**할 수 있어야 한다.

**제출물**
- `CREATE TABLE` 문 (컬럼 타입, PK/FK, UNIQUE, CHECK, 인덱스 포함)
- 각 설계 결정의 **근거**(왜 이 타입, 왜 이 제약)
- 정률/정액을 한 테이블에 담을지 나눌지에 대한 판단과 이유
- "쿠폰 사용" 시 동시성 문제(재고 초과 발급/중복 사용)를 **어떤 제약과 트랜잭션**으로 막을지 설명

**채점 기준(30점)**
- 정규화와 관계 설정(FK) 정확성 (10)
- 중복 발급 방지(UNIQUE), 사용 1회 보장 설계 (10)
- 할인 타입 모델링의 합리성 + 근거 (5)
- 동시성/무결성 고려(CHECK, 트랜잭션, 재고 차감 전략) (5)

---

## 제출 & 자기채점
- Part 1: 12문제 × 실제 실행 결과 캡처
- Part 2: 튜닝 전/후 `EXPLAIN ANALYZE` 비교 + 결과 동일 증명
- Part 3: `CREATE TABLE` + 설계 근거 문서
- 총점 200점. 160점 이상이면 이 코스를 **완주**한 것입니다.
