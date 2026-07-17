/**
 * Step 08 — createAgent, 첫 에이전트 (연습문제)
 * 실행: npx tsx docs/reference/langchain/step-08-create-agent/exercise.ts
 *
 * 각 [문제 N] 블록 아래 구현부가 비어 있습니다. 직접 채워 넣고 실행해 검증하세요.
 * 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.
 *
 * 도구 정의와 traceAgent 헬퍼는 미리 채워 뒀습니다 — 문제 자체에 집중하세요.
 */
import "dotenv/config";
import { createAgent, tool } from "langchain";
import { GraphRecursionError } from "@langchain/langgraph";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== 미리 준비된 도구 ===== */

const getWeather = tool(
  ({ city }) => `${city}의 날씨: 맑음, 기온 21도, 습도 45%`,
  {
    name: "get_weather",
    description:
      "특정 도시의 현재 날씨를 조회한다. 도시 이름은 한국어로 받는다.",
    schema: z.object({
      city: z.string().describe("날씨를 조회할 도시 이름 (예: 서울)"),
    }),
  },
);

const WEATHER_PROMPT =
  "너는 날씨 안내원이다. 날씨를 물으면 반드시 get_weather 도구로 확인한 뒤 답한다. " +
  "도구 결과에 없는 정보는 추측하지 말고 모른다고 답한다.";

/* ===== 미리 준비된 헬퍼 ===== */

async function traceAgent(
  agent: { stream: (input: any, config?: any) => Promise<AsyncIterable<any>> },
  content: string,
  config: Record<string, unknown> = {},
) {
  for await (const chunk of await agent.stream(
    { messages: [{ role: "user", content }] },
    { streamMode: "updates", ...config },
  )) {
    for (const [node, update] of Object.entries(chunk as Record<string, any>)) {
      for (const m of update?.messages ?? []) {
        if (m.tool_calls?.length) {
          for (const tc of m.tool_calls) {
            console.log(`  [${node}] → ${tc.name}(${JSON.stringify(tc.args)})`);
          }
        } else {
          console.log(
            `  [${node}] ${m.constructor.name}: ${String(m.text ?? "").split("\n")[0]}`,
          );
        }
      }
    }
  }
}

/* ===== [문제 1] =====
 * get_weather 도구 하나를 가진 에이전트를 만들되 systemPrompt 를 주지 말고
 * "서울 날씨 어때?" 를 물으세요. traceAgent 로 트레이스를 찍고
 * 도구가 호출되었는지 확인한 뒤 결과를 주석으로 적으세요.
 * 그다음 systemPrompt 를 넣고 다시 돌려 차이를 관찰하세요.
 */

async function exercise1() {
  console.log("\n===== [문제 1] =====");

  // TODO: systemPrompt 없는 에이전트를 만들고 트레이스를 찍으세요.

  // TODO: systemPrompt 를 넣은 에이전트로 다시 트레이스를 찍으세요.

  // → 관찰 결과 (systemPrompt 없을 때):
  // → 관찰 결과 (systemPrompt 있을 때):
}

/* ===== [문제 2] =====
 * 8-2 의 에이전트를 invoke 로 돌린 뒤 result.messages 를 순회하며
 * 각 메시지의 타입 이름 / text / tool_calls 개수를 표처럼 출력하세요.
 * 메시지가 왜 4개인지 주석으로 설명하세요.
 */

async function exercise2() {
  console.log("\n===== [문제 2] =====");

  // TODO: 에이전트를 만들고 invoke 한 뒤 result.messages 를 순회해 출력하세요.

  // → 메시지가 4개인 이유:
}

/* ===== [문제 3] =====
 * 같은 에이전트를 연속 두 번 invoke 하세요.
 *   첫 번째: "내 이름은 지은이야"
 *   두 번째: "내 이름이 뭐야?"
 * 두 번째 result.messages.length 를 출력하고,
 * 모델이 이름을 기억하지 못하는 이유를 주석으로 쓰세요.
 */

async function exercise3() {
  console.log("\n===== [문제 3] =====");

  // TODO: 에이전트를 만들고 두 번 invoke 하세요.

  // → 두 번째 messages.length:
  // → 기억하지 못하는 이유:
}

/* ===== [문제 4] =====
 * recursionLimit 을 3 으로 주고 도구를 반드시 부르게 되는 질문을 던져
 * GraphRecursionError 를 일부러 내세요.
 * try/catch 로 잡아 e.name 과 (e as any).lc_error_code 를 출력하세요.
 *
 * 그다음 "도구 왕복 1회에 3스텝" 이라는 8-5의 계산에 따라
 * 성공하는 최소 recursionLimit 을 찾아 주석으로 적으세요.
 *
 * 힌트: 에러가 안 나면 문제를 잘못 푼 것입니다.
 */

async function exercise4() {
  console.log("\n===== [문제 4] =====");

  // TODO: recursionLimit 을 낮게 줘서 GraphRecursionError 를 내고 잡으세요.

  // TODO: 성공하는 최소 recursionLimit 을 실험으로 찾으세요.

  // → 성공하는 최소 recursionLimit:
  // → 그 이유 (스텝 계산):
}

/* ===== [문제 5] =====
 * responseFormat 에 z.object({ tempC: z.number(), summary: z.string() }) 를 주고
 * 에이전트를 돌린 뒤, Object.keys(result) 를
 * responseFormat 이 있을 때와 없을 때 각각 출력해 비교하세요.
 * result.structuredResponse.tempC + 1 이 문자열 연결이 아니라
 * 덧셈이 되는지 확인하세요.
 */

async function exercise5() {
  console.log("\n===== [문제 5] =====");

  // TODO: 스키마를 정의하세요.

  // TODO: responseFormat 없는 에이전트로 Object.keys(result) 를 출력하세요.

  // TODO: responseFormat 있는 에이전트로 Object.keys(result) 를 출력하고
  //       structuredResponse.tempC + 1 을 확인하세요.

  // → 두 result 키의 차이:
}

/* ===== [문제 6] =====
 * 다음 세 요구사항 각각에 대해 에이전트인가 워크플로인가를 고르고
 * 이유를 2문장으로 쓰세요. (코드 없이 주석으로만 답하는 문제입니다)
 */

// (a) 업로드된 PDF 를 텍스트 추출 → 영어로 번역 → 3문장 요약 → DB 저장
// → 답:
// → 이유:

// (b) 사내 위키·DB·달력 중 필요한 곳을 찾아가며 "다음 주 팀 회의 준비해줘" 를 처리
// → 답:
// → 이유:

// (c) 고객 문의를 "환불 / 배송 / 기타" 로 분류해 각각 다른 템플릿으로 응답
// → 답:
// → 이유:

/* ===== [문제 7] =====
 * 8-8 의 쇼핑 에이전트에서 check_stock 의 description 을 "재고 조회" 로 줄이고
 * 돌려 보세요. 트레이스가 어떻게 달라지는지
 * (도구를 안 부르거나, 잘못된 productId 로 부르거나) 관찰해 주석으로 적으세요.
 * 그다음 원래 설명으로 되돌리세요.
 */

const CATALOG = [
  { id: "P1", name: "게이밍 노트북 RTX4060", category: "노트북", price: 2190000, stock: 4 },
  { id: "P2", name: "27인치 4K 모니터", category: "주변기기", price: 459000, stock: 12 },
  { id: "P3", name: "무선 기계식 키보드", category: "주변기기", price: 139000, stock: 0 },
  { id: "P4", name: "인체공학 사무용 의자", category: "가구", price: 329000, stock: 7 },
];

const searchProducts = tool(
  ({ keyword, maxPrice }) => {
    const hits = CATALOG.filter(
      (p) =>
        (p.name.includes(keyword) || p.category.includes(keyword)) &&
        (maxPrice == null || p.price <= maxPrice),
    );
    if (hits.length === 0) return "검색 결과 없음. 다른 키워드를 시도하세요.";
    return hits
      .map((p) => `${p.id} | ${p.name} | ${p.category} | ${p.price}원`)
      .join("\n");
  },
  {
    name: "search_products",
    description:
      "상품명 또는 카테고리 키워드로 상품을 검색한다. 상품 ID 를 알아내려면 먼저 이 도구를 써야 한다. " +
      "재고는 알려주지 않으므로 재고가 필요하면 check_stock 을 따로 호출한다.",
    schema: z.object({
      keyword: z.string().describe("검색 키워드 (예: 모니터, 노트북, 주변기기)"),
      maxPrice: z.number().nullable().describe("가격 상한(원). 상한이 없으면 null"),
    }),
  },
);

const SHOPPING_PROMPT = [
  "너는 온라인 쇼핑몰 상담원이다.",
  "",
  "규칙:",
  "1. 상품 ID 는 반드시 search_products 로 먼저 확인한다. ID 를 추측하지 않는다.",
  "2. 재고를 언급하기 전에 반드시 check_stock 으로 확인한다.",
  "3. 도구 결과에 없는 정보는 지어내지 않고 모른다고 답한다.",
].join("\n");

async function exercise7() {
  console.log("\n===== [문제 7] =====");

  // TODO: description 이 "재고 조회" 뿐인 checkStock 도구를 정의하세요.

  // TODO: searchProducts + 부실한 checkStock 으로 에이전트를 만들고
  //       "주변기기 중에 재고 있는 거 추천해줘" 로 트레이스를 찍으세요.

  // → 관찰 결과 (description 이 부실할 때):

  // TODO: description 을 제대로 쓴 checkStock 으로 바꿔 다시 트레이스를 찍으세요.

  // → 관찰 결과 (description 이 충실할 때):
}

/* ===== [문제 8] =====
 * 8-8 의 에이전트에 도구를 하나 더 추가하세요 —
 * compare_products(idA, idB) 로 두 상품을 비교해 표 문자열을 반환합니다.
 *
 * description 을 search_products 와 일부러 비슷하게(예: "상품을 찾아 비교한다")
 * 써 보고, 모델이 헷갈려 두 도구를 다 부르거나 잘못 고르는지 트레이스로 확인하세요.
 * 그다음 설명을 명확히 갈라
 * ("이미 아는 두 상품 ID 를 비교한다. 검색은 search_products 를 쓴다")
 * 다시 관찰하세요.
 */

async function exercise8() {
  console.log("\n===== [문제 8] =====");

  // TODO: compare_products 도구를 description 이 겹치게 정의하세요.

  // TODO: 에이전트를 만들고 "노트북이랑 모니터 중에 뭐가 나아?" 로 트레이스를 찍으세요.

  // → 관찰 결과 (설명이 겹칠 때):

  // TODO: description 을 명확히 갈라 다시 정의하고 트레이스를 찍으세요.

  // → 관찰 결과 (설명을 가른 뒤):
}

/* ===== 실행 =====
 * 푸는 문제만 주석을 풀고 돌리세요.
 */

async function main() {
  // await exercise1();
  // await exercise2();
  // await exercise3();
  // await exercise4();
  // await exercise5();
  // 문제 6 은 주석으로만 답하는 문제입니다.
  // await exercise7();
  // await exercise8();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
