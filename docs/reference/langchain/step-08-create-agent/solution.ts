/**
 * Step 08 — createAgent, 첫 에이전트 (정답과 해설)
 * 실행: npx tsx docs/reference/langchain/step-08-create-agent/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 *
 * ⚠️ 이 파일의 "관찰 결과" 주석은 실행마다 달라질 수 있습니다.
 *    에이전트는 확률적입니다 — 한 번 돌려 보고 "잘 되네" 라고 결론 내리는 것이
 *    이 코스에서 가장 위험한 습관입니다.
 */
import "dotenv/config";
import { createAgent, tool } from "langchain";
import { GraphRecursionError } from "@langchain/langgraph";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

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

async function traceAgent(
  agent: { stream: (input: any, config?: any) => Promise<AsyncIterable<any>> },
  content: string,
  config: Record<string, unknown> = {},
) {
  let toolCallCount = 0;
  for await (const chunk of await agent.stream(
    { messages: [{ role: "user", content }] },
    { streamMode: "updates", ...config },
  )) {
    for (const [node, update] of Object.entries(chunk as Record<string, any>)) {
      for (const m of update?.messages ?? []) {
        if (m.tool_calls?.length) {
          for (const tc of m.tool_calls) {
            toolCallCount++;
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
  return toolCallCount;
}

/* ===== [정답 1] =====
 * systemPrompt 없이 만들면 에러는 안 나지만 모델이 도구를 안 부르고
 * 자기 상식으로 답해 버리는 실행이 섞여 나옵니다.
 *
 * 핵심: 한 번만 돌려 보면 안 됩니다. 5번 돌려서 몇 번 도구를 불렀는지 세세요.
 *       systemPrompt 없이도 도구를 잘 부르는 실행이 나올 수 있고,
 *       그게 바로 본문 8-4 함정("확률적")의 실증입니다.
 */

async function solution1() {
  console.log("\n===== [정답 1] =====");

  console.log("-- systemPrompt 없음 (5회 반복) --");
  const naked = createAgent({ model: MODEL, tools: [getWeather] });
  let nakedCalls = 0;
  for (let i = 0; i < 5; i++) {
    console.log(`  [실행 ${i + 1}]`);
    const n = await traceAgent(naked, "서울 날씨 어때?");
    if (n > 0) nakedCalls++;
  }
  console.log(`  → 5회 중 도구를 부른 실행: ${nakedCalls}회`);

  console.log("\n-- systemPrompt 있음 (5회 반복) --");
  const guided = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });
  let guidedCalls = 0;
  for (let i = 0; i < 5; i++) {
    console.log(`  [실행 ${i + 1}]`);
    const n = await traceAgent(guided, "서울 날씨 어때?");
    if (n > 0) guidedCalls++;
  }
  console.log(`  → 5회 중 도구를 부른 실행: ${guidedCalls}회`);

  // → 관찰 결과: systemPrompt 없는 쪽은 도구를 건너뛰고 "서울은 이맘때 보통
  //   20도 안팎입니다" 같은 그럴듯한 환각을 내는 실행이 섞여 나옵니다.
  //   있는 쪽은 5/5 로 도구를 부릅니다.
  //
  //   중요한 건 비율이 아니라 "에러가 안 난다" 는 사실입니다.
  //   도구를 안 부른 실행도 예외 없이, 경고 없이, 그럴듯한 문장으로 끝납니다.
  //   systemPrompt 는 장식이 아니라 루프의 제어 장치입니다.
}

/* ===== [정답 2] =====
 * 메시지가 4개인 이유: 도구 왕복 1회 = Human + AI(tool_calls) + Tool + AI(최종).
 * 내가 넣은 건 1개인데 3개가 더 붙습니다 — messages 는 덮어쓰기가 아니라 누적입니다.
 */

async function solution2() {
  console.log("\n===== [정답 2] =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "서울 날씨 어때?" }],
  });

  console.log("result 의 키:", Object.keys(result)); // → [ 'messages' ]
  console.log("메시지 개수:", result.messages.length); // → 4
  console.log("");
  console.log("idx | 타입          | tool_calls | text");
  console.log("----+---------------+------------+------------------------------");
  for (const [i, m] of result.messages.entries()) {
    const type = m.constructor.name.padEnd(13);
    const n = String(((m as any).tool_calls ?? []).length).padEnd(10);
    const text = String(m.text ?? "").slice(0, 30);
    console.log(`  ${i} | ${type} | ${n} | ${text}`);
  }

  // → 메시지가 4개인 이유:
  //   [0] HumanMessage  — 내가 invoke 로 넣은 질문
  //   [1] AIMessage     — 모델 1차 호출. "도구를 불러야겠다" → tool_calls 가 차 있고 text 는 빈다
  //   [2] ToolMessage   — 에이전트가 도구를 실행한 결과. tool_call_id 로 [1]과 짝지어짐
  //   [3] AIMessage     — 모델 2차 호출. 최종 답변 → tool_calls 가 비어 있다
  //
  //   [3]의 tool_calls 가 비는 것이 루프 종료 조건입니다.
  //   Step 07 의 `if (!res.tool_calls?.length) break;` 와 정확히 같은 조건입니다.
  //   도구를 N번 부르면 1 + 2N + 1 개가 됩니다.
}

/* ===== [정답 3] =====
 * 정답은 2 입니다. checkpointer 가 없으므로 두 invoke 는 완전히 별개의 실행입니다.
 */

async function solution3() {
  console.log("\n===== [정답 3] =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "너는 친절한 비서다.",
  });

  await agent.invoke({
    messages: [{ role: "user", content: "내 이름은 지은이야. 기억해줘." }],
  });
  const second = await agent.invoke({
    messages: [{ role: "user", content: "내 이름이 뭐야?" }],
  });

  console.log("두 번째 messages.length:", second.messages.length); // → 2
  console.log("두 번째 답변:", second.messages.at(-1)?.text);
  // → "이름을 알려주신 적이 없습니다" 류의 답

  /* --- 추가 확인: thread_id 만 줘도 소용없습니다 --- */
  console.log("\n-- thread_id 를 줘도 마찬가지 --");
  await agent.invoke(
    { messages: [{ role: "user", content: "내 이름은 지은이야. 기억해줘." }] },
    { configurable: { thread_id: "same-thread" } },
  );
  const withThread = await agent.invoke(
    { messages: [{ role: "user", content: "내 이름이 뭐야?" }] },
    { configurable: { thread_id: "same-thread" } },
  );
  console.log("thread_id 준 두 번째 messages.length:", withThread.messages.length); // → 여전히 2
  console.log("답변:", withThread.messages.at(-1)?.text);

  // → 두 번째 messages.length: 2 (질문 + 답변)
  // → 기억하지 못하는 이유:
  //   messages 누적은 "한 번의 invoke 안에서만" 일어납니다.
  //   invoke 가 끝나면 상태는 어디에도 저장되지 않고 사라집니다.
  //   두 번째 invoke 는 백지에서 시작하므로 첫 대화가 존재하지 않습니다.
  //
  //   ⚠️ 여기가 진짜 함정입니다 — thread_id 를 줘도 여전히 2 입니다.
  //   checkpointer 가 없으면 thread_id 는 "조용히 무시" 됩니다.
  //   에러도 경고도 없고, 코드만 보면 메모리가 있는 것처럼 생겼습니다.
  //   저장할 곳(checkpointer)이 없는데 저장 위치 이름(thread_id)만 준 셈입니다.
  //   해결은 Step 10 — createAgent({ checkpointer: new MemorySaver() }).
}

/* ===== [정답 4] =====
 * 성공하는 최소 recursionLimit 은 3 입니다.
 * 다만 이 문제의 진짜 교훈은 숫자가 아니라
 * "한도는 도구 호출 수가 아니라 그래프 노드 스텝 수를 센다" 는 것입니다.
 */

async function solution4() {
  console.log("\n===== [정답 4] =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  const ask = (limit: number) =>
    agent.invoke(
      { messages: [{ role: "user", content: "서울 날씨 어때?" }] },
      { recursionLimit: limit },
    );

  /* --- limit 3 미만이면 터집니다 --- */
  try {
    await ask(2);
    console.log("limit=2 성공 — 모델이 도구를 아예 안 불렀을 수 있습니다.");
  } catch (e) {
    if (e instanceof GraphRecursionError) {
      console.log("limit=2 →", e.name);
      console.log("  lc_error_code:", (e as any).lc_error_code); // GRAPH_RECURSION_LIMIT
      console.log("  message:", e.message.split("\n")[0]);
      // Recursion limit of 2 reached without hitting a stop condition. ...
    } else {
      throw e;
    }
  }

  /* --- limit 3 이면 도구 왕복 1회가 딱 들어갑니다 --- */
  try {
    const r = await ask(3);
    console.log("\nlimit=3 성공. 최종:", r.messages.at(-1)?.text);
  } catch (e) {
    if (e instanceof GraphRecursionError) {
      console.log("\nlimit=3 실패 — 모델이 도구를 두 번 부르기로 했습니다.");
      console.log("  (에이전트는 확률적입니다. 이래서 여유를 둬야 합니다.)");
    } else {
      throw e;
    }
  }

  // → 성공하는 최소 recursionLimit: 3
  // → 그 이유 (스텝 계산):
  //   카운트되는 것은 도구 호출 횟수가 아니라 그래프 노드 실행 횟수입니다.
  //     model_request(1) + tools(2) + model_request(3) = 3스텝
  //   일반화하면 도구 왕복 N회 = 2N + 1 스텝.
  //     0회 → 1 / 1회 → 3 / 2회 → 5 / N회 → 2N+1
  //   기본값 25 = 도구 왕복 12번에 해당합니다.
  //   반대로 "도구를 최대 3번까지만" 을 원하면 recursionLimit: 7 입니다.
  //
  //   ⚠️ 정답은 3이지만 실무에서 3을 쓰면 안 됩니다.
  //   모델이 도구를 두 번 부르기로 하는 순간(도시 이름 재시도 등) 터집니다.
  //   그리고 GraphRecursionError 는 부분 결과를 주지 않습니다 —
  //   태운 토큰은 그대로 청구되고 산출물은 0입니다.
  //   본문 8-5 팁대로 "정상일 때 쓰는 스텝의 2배" 로 잡으세요.
}

/* ===== [정답 5] =====
 * responseFormat 이 없으면 structuredResponse 는 결과 상태에 아예 존재하지 않습니다.
 */

const Report = z.object({
  tempC: z.number().describe("섭씨 기온"),
  summary: z.string().describe("날씨를 한 문장으로 요약"),
});

async function solution5() {
  console.log("\n===== [정답 5] =====");

  const plain = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });
  const r1 = await plain.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘" }],
  });
  console.log("responseFormat 없음 → 키:", Object.keys(r1));
  // → [ 'messages' ]
  console.log("  structuredResponse:", (r1 as any).structuredResponse);
  // → undefined (키 자체가 없음)

  const structured = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
    responseFormat: Report,
  });
  const r2 = await structured.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘" }],
  });
  console.log("\nresponseFormat 있음 → 키:", Object.keys(r2));
  // → [ 'messages', 'structuredResponse' ]
  console.log("  structuredResponse:", r2.structuredResponse);

  // 파싱도, 정규식도, Number() 캐스팅도 없이 바로 계산됩니다.
  const plusOne = r2.structuredResponse.tempC + 1;
  console.log("  tempC + 1 =", plusOne, `(typeof: ${typeof plusOne})`);
  // → 22 (typeof: number) — "211" 이 아닙니다.

  // → 두 result 키의 차이:
  //   responseFormat 을 주지 않으면 structuredResponse 는 undefined 가 아니라
  //   "키 자체가 상태에 없습니다". Object.keys 로 확인하는 이유가 이것입니다.
  //
  //   ⚠️ responseFormat 은 공짜가 아닙니다. 에이전트 루프가 끝난 뒤
  //   별도의 LLM 호출이 한 번 더 일어나 구조화된 응답을 만듭니다.
  //   지연시간·토큰이 늘고 recursionLimit 예산도 갉아먹습니다.
  //   사람에게 문장으로 보여줄 뿐이라면 빼는 게 맞습니다.
}

/* ===== [정답 6] =====
 * 코드 없이 판단하는 문제입니다. 핵심 질문은 하나 — "단계를 미리 알 수 있는가?"
 */

// (a) PDF 텍스트 추출 → 영어 번역 → 3문장 요약 → DB 저장
// → 답: 워크플로 (prompt chaining)
// → 이유: 4단계가 전부 코드로 적힙니다. LLM 이 순서를 정할 여지가 없고,
//   에이전트로 만들면 "번역을 건너뛰기로 결정" 하는 사고가 언젠가 납니다.
//   결정적이어야 하고, 비용도 고정(LLM 호출 2회)이라 예측 가능합니다.

// (b) 사내 위키·DB·달력을 오가며 "다음 주 팀 회의 준비해줘"
// → 답: 에이전트
// → 이유: 어느 소스를 몇 번 볼지 시작할 때 알 수 없습니다 — 달력을 보고
//   회의가 있어야 위키를 찾고, 위키 내용에 따라 DB 조회 여부가 갈립니다.
//   도구 결과를 보고 다음 행동을 정하는 피드백 루프가 본질입니다.

// (c) 고객 문의를 "환불 / 배송 / 기타" 로 분류해 각각 다른 템플릿으로 응답
// → 답: 워크플로 (routing)
// → 이유: ⚠️ 이 문제는 함정입니다. "LLM 이 판단한다" 는 말에 끌려 에이전트라고
//   답하기 쉽지만, 여기서 LLM 이 하는 일은 분류 한 번뿐이고 그 뒤는 템플릿입니다.
//   에이전트로 만들면 if 문 하나를 아주 비싸고 불안정하게 만든 셈이 됩니다.
//   분류에만 LLM 을 쓰고(structured output 으로 enum 뽑기, Step 05)
//   응답은 코드로 분기하는 것이 정답입니다.
//   에이전트라고 답했다면 본문 8-6의 "LLM 이 필요 없다" 항목을 다시 읽으세요.
//
// 종합: (a)(c)가 워크플로, (b)만 에이전트. 실무 비율도 대략 이렇습니다 —
// "에이전트로 만들자" 가 아니라 "에이전트가 꼭 필요한 구간이 어디인가" 를 물으세요.

/* ===== [정답 7] =====
 * 도구 description 은 코드 주석이 아니라 프롬프트의 일부입니다.
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

const stockImpl = ({ productId }: { productId: string }) => {
  const p = CATALOG.find((x) => x.id === productId);
  if (!p) return `상품 ${productId} 없음. search_products 로 올바른 ID 를 먼저 확인하세요.`;
  return p.stock === 0 ? `${p.name}: 품절` : `${p.name}: 재고 ${p.stock}개`;
};

/* 부실한 설명 */
const checkStockBad = tool(stockImpl, {
  name: "check_stock",
  description: "재고 조회",
  schema: z.object({ productId: z.string() }),
});

/* 충실한 설명 */
const checkStockGood = tool(stockImpl, {
  name: "check_stock",
  description:
    "상품 ID 로 재고 수량을 조회한다. productId 는 search_products 결과에 나온 ID(P1 형식)여야 한다.",
  schema: z.object({
    productId: z.string().describe("상품 ID. 예: P2"),
  }),
});

const SHOPPING_PROMPT = [
  "너는 온라인 쇼핑몰 상담원이다.",
  "",
  "규칙:",
  "1. 상품 ID 는 반드시 search_products 로 먼저 확인한다. ID 를 추측하지 않는다.",
  "2. 재고를 언급하기 전에 반드시 check_stock 으로 확인한다.",
  "3. 도구 결과에 없는 정보는 지어내지 않고 모른다고 답한다.",
].join("\n");

async function solution7() {
  console.log("\n===== [정답 7] =====");
  const question = "주변기기 중에 재고 있는 거 추천해줘.";

  console.log("-- description: '재고 조회' (부실) --");
  const bad = createAgent({
    model: MODEL,
    tools: [searchProducts, checkStockBad],
    systemPrompt: SHOPPING_PROMPT,
  });
  await traceAgent(bad, question, { recursionLimit: 12 });

  console.log("\n-- description: 충실 --");
  const good = createAgent({
    model: MODEL,
    tools: [searchProducts, checkStockGood],
    systemPrompt: SHOPPING_PROMPT,
  });
  await traceAgent(good, question, { recursionLimit: 12 });

  // → 관찰 결과 (description 이 부실할 때):
  //   productId 의 형식을 모르므로 모델이 ID 를 추측합니다 —
  //   check_stock({"productId":"27인치 4K 모니터"}) 처럼 상품명을 넣거나,
  //   check_stock({"productId":"monitor"}) 처럼 영어로 부르는 실행이 나옵니다.
  //   도구는 "상품 ... 없음. search_products 로 ..." 를 돌려주고,
  //   모델이 다시 검색 → 다시 시도 → 왕복이 늘어납니다.
  //   systemPrompt 규칙 1이 있는데도 이런 일이 생기는 게 핵심입니다 —
  //   systemPrompt 는 "먼저 검색해라" 만 말하지 "ID 는 P1 형식" 은 말하지 않습니다.
  //   그 정보가 있어야 할 곳은 도구의 description 과 스키마의 .describe() 입니다.
  //
  // → 관찰 결과 (description 이 충실할 때):
  //   search_products → check_stock(P2) → check_stock(P3) → 답변.
  //   왕복이 줄고 recursionLimit 예산이 남습니다.
  //
  //   교훈: 도구 description 은 모델이 호출 여부와 인자를 정하는 유일한 근거입니다.
  //   부실해도 에러가 안 납니다 — 그냥 결과가 나빠지고 비용이 오를 뿐입니다.
}

/* ===== [정답 8] =====
 * 설명이 겹치는 도구 둘은 모델을 헷갈리게 하고 왕복을 늘립니다.
 * 본문 8-5의 "도구가 많을수록 헤맨다" 함정을 2개짜리로 축소 재현한 문제입니다.
 */

const compareImpl = ({ idA, idB }: { idA: string; idB: string }) => {
  const a = CATALOG.find((x) => x.id === idA);
  const b = CATALOG.find((x) => x.id === idB);
  if (!a || !b) {
    return `상품 ${!a ? idA : idB} 없음. search_products 로 올바른 ID 를 먼저 확인하세요.`;
  }
  return [
    `항목      | ${a.name} | ${b.name}`,
    `가격      | ${a.price}원 | ${b.price}원`,
    `카테고리  | ${a.category} | ${b.category}`,
    `재고      | ${a.stock}개 | ${b.stock}개`,
  ].join("\n");
};

/* 설명이 search_products 와 겹침 */
const compareBad = tool(compareImpl, {
  name: "compare_products",
  description: "상품을 찾아 비교한다",
  schema: z.object({ idA: z.string(), idB: z.string() }),
});

/* 설명을 명확히 가름 */
const compareGood = tool(compareImpl, {
  name: "compare_products",
  description:
    "이미 아는 두 상품 ID 를 나란히 비교한 표를 만든다. 검색 기능은 없다 — " +
    "ID 를 모르면 먼저 search_products 를 쓴다.",
  schema: z.object({
    idA: z.string().describe("비교할 첫 번째 상품 ID. 예: P1"),
    idB: z.string().describe("비교할 두 번째 상품 ID. 예: P2"),
  }),
});

async function solution8() {
  console.log("\n===== [정답 8] =====");
  const question = "노트북이랑 모니터 중에 뭐가 나아?";

  console.log("-- compare_products description: '상품을 찾아 비교한다' (겹침) --");
  const bad = createAgent({
    model: MODEL,
    tools: [searchProducts, checkStockGood, compareBad],
    systemPrompt: SHOPPING_PROMPT,
  });
  await traceAgent(bad, question, { recursionLimit: 14 });

  console.log("\n-- compare_products description: 역할을 가름 --");
  const good = createAgent({
    model: MODEL,
    tools: [searchProducts, checkStockGood, compareGood],
    systemPrompt: SHOPPING_PROMPT,
  });
  await traceAgent(good, question, { recursionLimit: 14 });

  // → 관찰 결과 (설명이 겹칠 때):
  //   "상품을 찾아 비교한다" 의 "찾아" 때문에 모델은 이 도구가 검색도 한다고 믿습니다.
  //   그래서 ID 없이 compare_products({"idA":"노트북","idB":"모니터"}) 로 부릅니다.
  //   도구가 "상품 노트북 없음..." 을 돌려주면 그제야 search_products 로 되돌아가
  //   ID 를 얻고 다시 compare_products 를 부릅니다.
  //   → 도구 왕복이 3~4회로 늘고, 그만큼 recursionLimit 예산과 토큰을 씁니다.
  //   두 도구를 다 부르고 결과가 겹쳐 혼란스러워하는 실행도 나옵니다.
  //
  // → 관찰 결과 (설명을 가른 뒤):
  //   search_products("노트북") → search_products("모니터") →
  //   compare_products(P1, P2) → 답변.
  //   "검색 기능은 없다 — ID 를 모르면 먼저 search_products 를 쓴다" 한 줄이
  //   경계를 그어 줍니다.
  //
  //   교훈: 도구가 많을수록 유능해지는 게 아닙니다. 도구 설명이 겹치면
  //   모델은 둘 다 부르거나, 하나를 부르고 다시 다른 걸 부르며 헤맵니다.
  //   경험칙은 한 에이전트당 도구 5~7개이고, 그 이상이면
  //   (1) 도구를 합치거나 (2) 서브에이전트로 쪼개거나(Step 18)
  //   (3) llmToolSelectorMiddleware 로 관련 도구만 추려 넣습니다(Step 11).
  //
  //   그리고 도구를 추가할 때는 항상 물으세요 —
  //   "이 도구의 설명만 읽고, 기존 도구와 헷갈리지 않게 고를 수 있는가?"
}

/* ===== 실행 =====
 * 전부 돌리면 모델을 수십 번 호출합니다(정답 1만 10회). 비용에 주의하세요.
 */

async function main() {
  await solution1();
  await solution2();
  await solution3();
  await solution4();
  await solution5();
  // 정답 6 은 주석으로만 답하는 문제입니다.
  await solution7();
  await solution8();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
