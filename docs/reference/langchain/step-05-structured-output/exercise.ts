/**
 * Step 05 — 구조화된 출력 (zod) · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-05-structured-output/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 그대로 실행하면 아무것도 출력되지 않습니다 (정상입니다 — main() 의 주석을 풀면서 진행하세요).
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import * as z from "zod";
import {
  createAgent,
  initChatModel,
  toolStrategy,
  providerStrategy,
  tool,
} from "langchain";

const MODEL = "anthropic:claude-sonnet-4-6";

function show(label: string, value: unknown): void {
  console.log(`\n=== ${label} ===`);
  console.dir(value, { depth: null });
}

/* ===== [문제 1] optional vs nullable 을 몸으로 익히기 =====
 *
 * 아래 요구사항을 만족하는 zod 스키마 `Profile` 을 만드세요.
 *   - name    : 문자열, 필수
 *   - nickname: 문자열, "키 자체가 없어도 됨"
 *   - bio     : 문자열, "키는 반드시 있어야 하지만 값이 null 이어도 됨"
 *
 * 그리고 아래 4가지 입력에 대해 safeParse 결과(true/false)를 출력하세요.
 *   (a) { name: "김민수" }
 *   (b) { name: "김민수", nickname: null }
 *   (c) { name: "김민수", bio: null }
 *   (d) { name: "김민수", bio: null, nickname: "민수" }
 *
 * 어느 것이 왜 실패하는지 주석으로 적으세요.
 */
function exercise1(): void {
  // 여기에 작성
}

/* ===== [문제 2] 자유 텍스트 파싱을 구조화 출력으로 대체하기 =====
 *
 * 아래 `receipt` 문자열에서 다음을 추출하는 스키마를 만들고
 * model.withStructuredOutput() 으로 뽑아내세요.
 *   - storeName: 상점 이름
 *   - total    : 총액 (숫자, 원 단위)
 *   - date     : YYYY-MM-DD 형식 문자열
 *   - items    : { name, price } 배열
 *
 * 모든 필드에 .describe() 를 반드시 붙이세요.
 */
async function exercise2(): Promise<void> {
  const receipt = `
    스타벅스 강남점
    2026-07-15 14:32
    아메리카노(T)      4,500
    카페라떼(G)        5,900
    치즈케이크         6,500
    ------------------------
    합계              16,900
  `;
  // 여기에 작성
}

/* ===== [문제 3] .describe() 가 정확도를 바꾸는 걸 직접 확인하기 =====
 *
 * 같은 필드 이름으로 스키마 두 개를 만드세요.
 *   (A) describe 없음:  z.object({ company: z.string(), amount: z.string() })
 *   (B) describe 있음:  company 는 "회사 이름만. 법인격(주식회사/(주)/Inc.) 제외."
 *                       amount 는 "금액을 숫자와 단위만. 예) '350억원'"
 *
 * 아래 news 로 둘 다 호출해 결과를 나란히 출력하고,
 * 무엇이 달라졌는지 주석으로 적으세요.
 */
async function exercise3(): Promise<void> {
  const news =
    "주식회사 넥스트테크는 시리즈B 라운드에서 총 350억원 규모의 투자를 유치했다고 밝혔다.";
  // 여기에 작성
}

/* ===== [문제 4] providerStrategy 와 toolStrategy 의 메시지 흐름 비교 =====
 *
 * 같은 스키마·같은 질문으로 에이전트 두 개를 만드세요.
 *   (A) responseFormat: providerStrategy(Schema)
 *   (B) responseFormat: toolStrategy(Schema)
 *
 * 각각 invoke 한 뒤 result.messages.map(m => m.getType()) 을 출력해
 * 두 전략의 메시지 흐름 차이를 관찰하고 주석으로 설명하세요.
 * (힌트: 한쪽에만 "tool" 이 등장합니다. 왜일까요?)
 */
async function exercise4(): Promise<void> {
  const Sentiment = z.object({
    label: z.enum(["positive", "negative", "neutral"]).describe("감정"),
    score: z.number().min(-1).max(1).describe("-1(부정) ~ 1(긍정)"),
  });
  const question = "이 문장의 감정을 분석해: '기대했던 것보단 별로였지만 못 쓸 정도는 아니에요.'";
  // 여기에 작성
}

/* ===== [문제 5] 검증 실패를 일부러 유발하고 재시도 관찰하기 =====
 *
 * `z.number().min(1).max(5)` 인 rating 필드를 가진 스키마를 만들고,
 * "이 제품 100점 만점에 200점!" 같은 입력으로 검증 실패를 유도하세요.
 *
 * (a) 기본 handleError(=true) 로 실행 → 최종 structuredResponse 와
 *     messages 안에 남은 에러 tool 메시지를 출력하세요.
 * (b) handleError: false 로 실행 → try/catch 로 감싸 예외를 잡아 출력하세요.
 * (c) 커스텀 문자열 handleError 로 실행 → 결과를 출력하세요.
 *
 * 셋의 차이를 주석으로 정리하세요.
 */
async function exercise5(): Promise<void> {
  // 여기에 작성
}

/* ===== [문제 6] 분류기 만들기 — enum + reasoning + confidence =====
 *
 * 고객 문의를 ["bug", "feature", "question", "billing", "spam"] 중 하나로 분류하는
 * 스키마를 만드세요. 단 다음 조건을 지키세요.
 *   - reasoning 필드를 label 보다 "먼저" 선언할 것 (왜 그래야 할까요?)
 *   - confidence 는 0~1 사이 number
 *   - label 은 enum
 *
 * 아래 3개 문의를 모두 분류해 출력하세요.
 */
async function exercise6(): Promise<void> {
  const inquiries = [
    "비밀번호 재설정 메일이 안 옵니다.",
    "🔥🔥 지금 클릭하면 아이폰 무료 증정 🔥🔥 http://spam.example.com",
    "엑셀 내보내기 기능도 추가해주실 수 있나요?",
  ];
  // 여기에 작성
}

/* ===== [문제 7] 중첩 깊이의 함정 확인하기 =====
 *
 * 같은 정보를 담되 구조가 다른 스키마 두 개를 만드세요.
 *   (A) 깊게 중첩:
 *       { order: { customer: { profile: { name, tier } }, payment: { method, amount } } }
 *   (B) 평평하게:
 *       { customerName, customerTier, paymentMethod, paymentAmount }
 *
 * 아래 텍스트로 둘 다 호출해 결과를 비교하고,
 * 어느 쪽이 더 안정적인지 주석으로 적으세요.
 */
async function exercise7(): Promise<void> {
  const text =
    "VIP 고객 이수진님이 신용카드로 129,000원을 결제했습니다.";
  // 여기에 작성
}

/* ===== [문제 8] 도구 + 구조화 출력을 함께 쓰는 라우팅 에이전트 =====
 *
 * (a) 주문번호를 받아 배송 상태를 돌려주는 가짜 도구 `lookup_order` 를 만드세요.
 *     (예: "ORD-1001" → "배송중, 예상 도착 2026-07-19")
 * (b) 그 도구를 쓰는 에이전트를 만들고, 최종 답변을 아래 스키마로 구조화하세요.
 *       - destination: enum(["refund_agent", "tech_support", "sales", "human"])
 *       - priority   : enum(["low", "normal", "urgent"])
 *       - summary    : 담당자가 읽을 한 문장 요약
 * (c) "ORD-1001 주문이 3주째 안 오는데 환불해주세요" 로 실행하고,
 *     messages 의 타입 흐름과 structuredResponse 를 함께 출력하세요.
 * (d) destination 값으로 switch 문을 써서 각 분기를 console.log 하세요.
 *     (힌트: enum 이라 switch 가 타입 안전합니다)
 */
async function exercise8(): Promise<void> {
  // 여기에 작성
}

async function main(): Promise<void> {
  // 푼 문제부터 하나씩 주석을 푸세요.
  // exercise1();
  // await exercise2();
  // await exercise3();
  // await exercise4();
  // await exercise5();
  // await exercise6();
  // await exercise7();
  // await exercise8();
}

main().catch((error) => {
  console.error("실행 실패:", error);
  process.exitCode = 1;
});
