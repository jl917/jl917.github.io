/**
 * Step 08 — createAgent, 첫 에이전트
 * 실행: npx tsx docs/reference/langchain/step-08-create-agent/practice.ts
 *
 * 본문 8-1 ~ 8-8 의 예제를 순서대로 담았습니다.
 * 실제로 모델을 호출하므로 API 비용과 30초 내외의 시간이 듭니다.
 * 특정 블록만 보고 싶으면 맨 아래 main() 에서 원하는 함수만 남기세요.
 */
import "dotenv/config";
import { createAgent, tool } from "langchain";
import { GraphRecursionError } from "@langchain/langgraph";
import * as z from "zod";

/* 모델을 바꾸려면 여기 한 곳만 고치면 됩니다.
 * OpenAI: "openai:gpt-5.5" (환경변수 OPENAI_API_KEY 필요) */
const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== 공용 도구 ===== */

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

/* ===== [8-4] traceAgent — 이 파일에서 가장 재사용 가치가 높은 헬퍼 =====
 * updates 청크를 풀어 "어느 노드가 무엇을 추가했는지" 를 한 줄씩 찍습니다.
 * 새 에이전트를 만들 때마다 이 함수부터 복사해 쓰세요. */
async function traceAgent(
  agent: { stream: (input: any, config?: any) => Promise<AsyncIterable<any>> },
  content: string,
  config: Record<string, unknown> = {},
) {
  for await (const chunk of await agent.stream(
    { messages: [{ role: "user", content }] },
    { streamMode: "updates", ...config },
  )) {
    // updates 청크의 모양: { 노드이름: { messages: [...] } }
    for (const [node, update] of Object.entries(chunk as Record<string, any>)) {
      for (const m of update?.messages ?? []) {
        if (m.tool_calls?.length) {
          for (const tc of m.tool_calls) {
            console.log(`  [${node}] → ${tc.name}(${JSON.stringify(tc.args)})`);
          }
        } else {
          const head = String(m.text ?? "").split("\n")[0];
          console.log(`  [${node}] ${m.constructor.name}: ${head}`);
        }
      }
    }
  }
}

/* ===== [8-1] createAgent 옵션 — 모델을 문자열로 vs 인스턴스로 ===== */

async function section1() {
  console.log("\n===== [8-1] 옵션 =====");

  // (A) 문자열: 짧지만 temperature 등 파라미터를 못 준다.
  const byString = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  // (B) 인스턴스: 파라미터가 필요하면 이쪽.
  //     import { ChatAnthropic } from "@langchain/anthropic";
  //     const byInstance = createAgent({
  //       model: new ChatAnthropic({ model: "claude-sonnet-4-6", temperature: 0 }),
  //       tools: [getWeather],
  //     });

  // 에이전트가 만드는 그래프의 노드를 직접 확인합니다.
  // → [ '__start__', 'model_request', 'tools', '__end__' ] (결정적)
  const graph = await (byString as any).getGraphAsync();
  console.log("그래프 노드:", Object.keys(graph.nodes));

  // version 은 기본값 "v2" 를 그대로 두세요.
  //   "v1": 한 노드에서 Promise.all 로 모든 도구 호출을 동시 실행 (진짜 병렬)
  //   "v2": 도구 호출 하나하나가 독립 그래프 태스크
  //         → 도구 호출 단위 체크포인팅과 interrupt() 지원 (Step 13 의 HITL 이 이걸 씁니다)
}

/* ===== [8-2] 첫 에이전트 만들고 돌리기 ===== */

async function section2() {
  console.log("\n===== [8-2] 첫 에이전트 =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  // 주의: 챗 모델은 model.invoke(배열) 이지만
  //       에이전트는 agent.invoke({ messages: 배열 }) — 상태 객체를 받는다.
  const result = await agent.invoke({
    messages: [{ role: "user", content: "서울 날씨 어때?" }],
  });

  console.log("최종 답변:", result.messages.at(-1)?.text);

  // systemPrompt 없이 만든 버전 — 에러는 안 나지만 도구를 안 부르고
  // 자기 상식으로 답해 버리는 실행이 섞여 나옵니다. (본문 8-2 함정)
  console.log("\n-- systemPrompt 없는 버전의 트레이스 --");
  const naked = createAgent({ model: MODEL, tools: [getWeather] });
  await traceAgent(naked, "서울 날씨 어때?");
}

/* ===== [8-3] 에이전트 상태 — messages 는 어떻게 쌓이는가 ===== */

async function section3() {
  console.log("\n===== [8-3] 상태 해부 =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "서울 날씨 어때?" }],
  });

  // responseFormat 이 없으므로 키는 messages 하나뿐입니다.
  console.log("result 의 키:", Object.keys(result));
  console.log("메시지 개수:", result.messages.length, "(내가 넣은 건 1개)");

  for (const [i, m] of result.messages.entries()) {
    console.log(`[${i}] ${m.constructor.name}`);
    console.log(`     text       : ${JSON.stringify(m.text)}`);
    console.log(`     tool_calls : ${JSON.stringify((m as any).tool_calls ?? [])}`);
  }
  // Human → AI(tool_calls 있음) → Tool → AI(tool_calls 없음)
  // 마지막 AIMessage 의 tool_calls 가 비는 것이 루프 종료 조건입니다.

  /* --- 함정 재현: checkpointer 없으면 invoke 간 기억이 없다 --- */
  console.log("\n-- 기억 상실 재현 --");
  await agent.invoke({
    messages: [{ role: "user", content: "내 이름은 지은이야. 기억해줘." }],
  });
  const second = await agent.invoke({
    messages: [{ role: "user", content: "내 이름이 뭐게?" }],
  });
  console.log("두 번째 invoke 의 messages.length:", second.messages.length);
  console.log("두 번째 답변:", second.messages.at(-1)?.text);
  // → length 는 2. 첫 대화가 통째로 없습니다. 에러도 경고도 없습니다.
  //   thread_id 를 줘도 checkpointer 가 없으면 조용히 무시됩니다. (Step 10)
}

/* ===== [8-4] 실행 흐름 추적 — stream({ streamMode: "updates" }) ===== */

async function section4() {
  console.log("\n===== [8-4] updates 트레이스 =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  // 기대 흐름: model_request → tools → model_request
  // 노드 이름과 순서는 결정적이지만, 호출 횟수는 실행마다 달라질 수 있습니다.
  await traceAgent(agent, "서울 날씨 어때?");

  /* --- 청크의 날것 그대로를 한 번 봅니다 --- */
  console.log("\n-- 청크 원본 --");
  for await (const chunk of await agent.stream(
    { messages: [{ role: "user", content: "부산 날씨는?" }] },
    { streamMode: "updates" },
  )) {
    // { model_request: { messages: [AIMessage] } } 처럼 노드 이름이 키입니다.
    console.log("  키:", Object.keys(chunk as Record<string, unknown>));
  }

  /* --- 여러 모드를 동시에 켜면 결과가 [모드, 청크] 튜플로 바뀝니다 --- */
  console.log("\n-- 멀티 모드 --");
  for await (const [mode, chunk] of await agent.stream(
    { messages: [{ role: "user", content: "대구 날씨는?" }] },
    { streamMode: ["updates", "values"] },
  ) as any) {
    const keys = Object.keys(chunk as Record<string, unknown>);
    console.log(`  mode=${mode} keys=${JSON.stringify(keys)}`);
  }
}

/* ===== [8-5] recursionLimit 과 무한 루프 방어 ===== */

async function section5() {
  console.log("\n===== [8-5] recursionLimit =====");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });

  // 한도는 "도구 호출 수" 가 아니라 "그래프 노드 스텝 수" 를 셉니다.
  //   도구 왕복 1회 = model_request + tools + model_request = 3스텝
  //   도구 왕복 N회 = 2N + 1 스텝
  //   기본값 25 = 도구 왕복 12번

  // recursionLimit: 4 는 도구 왕복 1회(3스텝)는 되지만 2회(5스텝)는 안 됩니다.
  // 여러 도시를 물어 도구를 여러 번 부르게 유도해 일부러 터뜨립니다.
  try {
    await agent.invoke(
      {
        messages: [
          {
            role: "user",
            content:
              "서울, 부산, 대구, 인천, 광주 날씨를 하나씩 순서대로 확인해서 알려줘.",
          },
        ],
      },
      { recursionLimit: 4 },
    );
    console.log("에러 없이 끝났습니다 — 모델이 도구를 한 번에 병렬로 불렀을 수 있습니다.");
    console.log("(에이전트는 확률적입니다. 다시 돌리면 결과가 달라질 수 있습니다.)");
  } catch (e) {
    if (e instanceof GraphRecursionError) {
      // 부분 결과는 없습니다. invoke 자체가 throw 합니다.
      console.log("이름       :", e.name);
      console.log("에러 코드  :", (e as any).lc_error_code);
      console.log("메시지     :", e.message);
    } else {
      throw e;
    }
  }

  // 한도를 올리는 게 대개 답이 아닙니다. 25에서 터지는 에이전트는 50에서도 터집니다.
  // updates 로 원인(같은 도구 반복 호출 등)을 먼저 보세요.
}

/* ===== [8-6] 에이전트 vs 워크플로 =====
 * 이 절은 판단 기준을 다루므로 실행 코드가 없습니다.
 *
 *   핵심 질문: "단계를 내가 미리 알 수 있는가?"
 *     알 수 있다 → 워크플로 (StateGraph, Step 17)
 *     모른다     → 에이전트 (createAgent, 지금)
 *
 *   에이전트를 쓰면 안 되는 경우
 *     - 단계가 고정 (PDF 추출 → 번역 → 요약 → 저장)
 *     - 결정성/감사가 요구됨 (금융, 의료)
 *     - 지연시간 예산이 빡빡함 (에이전트는 최소 2회 모델 호출)
 *     - 애초에 LLM 이 필요 없음 (분기뿐이면 if 문이 정답)
 *
 *   워크플로 패턴 5가지
 *     prompt chaining / parallelization / routing /
 *     orchestrator-worker / evaluator-optimizer
 *
 *   실무의 정답은 대개 섞는 것 — 바깥은 워크플로, 안쪽 한 칸만 에이전트.
 */

/* ===== [8-7] responseFormat 으로 구조화된 최종 답변 ===== */

const WeatherReport = z.object({
  city: z.string().describe("조회한 도시 이름"),
  tempC: z.number().describe("섭씨 기온"),
  condition: z.enum(["맑음", "흐림", "비", "눈"]).describe("날씨 상태"),
  advice: z.string().describe("한 문장 옷차림 조언"),
});

async function section7() {
  console.log("\n===== [8-7] responseFormat =====");

  /* (A) responseFormat 없음 */
  const plain = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
  });
  const r1 = await plain.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘" }],
  });
  console.log("responseFormat 없음 → 키:", Object.keys(r1));
  // → [ 'messages' ] — structuredResponse 가 아예 존재하지 않습니다.

  /* (B) responseFormat 있음 */
  const structured = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: WEATHER_PROMPT,
    responseFormat: WeatherReport,
  });
  const r2 = await structured.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘" }],
  });
  console.log("responseFormat 있음 → 키:", Object.keys(r2));
  // → [ 'messages', 'structuredResponse' ]

  console.log(r2.structuredResponse);
  // tempC 가 number 이므로 파싱 없이 바로 계산됩니다.
  console.log("기온에 5를 더하면:", r2.structuredResponse.tempC + 5);

  // 전략을 명시하고 싶다면:
  //   import { providerStrategy, toolStrategy } from "langchain";
  //   responseFormat: providerStrategy(WeatherReport)  // provider 네이티브
  //   responseFormat: toolStrategy(WeatherReport)      // 도구 지원 모델 전부
  //
  // 주의: responseFormat 은 루프가 끝난 뒤 모델 호출을 한 번 더 씁니다.
  //       지연시간·토큰이 늘고 recursionLimit 예산도 갉아먹습니다.
}

/* ===== [8-8] 실전 예제 — 도구 3개짜리 쇼핑 상담 에이전트 ===== */

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
      // .optional() 이 아니라 .nullable() 을 쓰는 이유는 본문 8-7 함정 참고.
      maxPrice: z.number().nullable().describe("가격 상한(원). 상한이 없으면 null"),
    }),
  },
);

const checkStock = tool(
  ({ productId }) => {
    const p = CATALOG.find((x) => x.id === productId);
    // 예상 가능한 실패는 throw 하지 않고 "다음에 뭘 해야 하는지" 를 문자열로 돌려줍니다.
    // 모델은 이 문장을 읽고 실제로 search_products 를 부릅니다.
    if (!p) return `상품 ${productId} 없음. search_products 로 올바른 ID 를 먼저 확인하세요.`;
    return p.stock === 0 ? `${p.name}: 품절` : `${p.name}: 재고 ${p.stock}개`;
  },
  {
    name: "check_stock",
    description:
      "상품 ID 로 재고 수량을 조회한다. productId 는 search_products 결과에 나온 ID(P1 형식)여야 한다.",
    schema: z.object({
      productId: z.string().describe("상품 ID. 예: P2"),
    }),
  },
);

const quote = tool(
  ({ items }) => {
    let subtotal = 0;
    const lines: string[] = [];
    for (const it of items) {
      const p = CATALOG.find((x) => x.id === it.productId);
      if (!p) return `상품 ${it.productId} 없음. 견적을 계산할 수 없습니다.`;
      const amount = p.price * it.quantity;
      subtotal += amount;
      lines.push(`${p.name} × ${it.quantity} = ${amount}원`);
    }
    const shipping = subtotal >= 500000 ? 0 : 3000;
    return [
      ...lines,
      `소계: ${subtotal}원`,
      `배송비: ${shipping}원 (50만원 이상 무료)`,
      `합계: ${subtotal + shipping}원`,
    ].join("\n");
  },
  {
    name: "quote",
    description:
      "상품 ID 와 수량 목록으로 배송비를 포함한 최종 견적을 계산한다. 금액 계산은 직접 하지 말고 반드시 이 도구를 쓴다.",
    schema: z.object({
      items: z
        .array(
          z.object({
            productId: z.string().describe("상품 ID. 예: P1"),
            quantity: z.number().int().positive().describe("수량"),
          }),
        )
        .describe("견적에 포함할 상품과 수량 목록"),
    }),
  },
);

const Recommendation = z.object({
  productIds: z.array(z.string()).describe("추천한 상품 ID 목록"),
  totalPrice: z.number().describe("quote 도구가 계산한 합계 금액(원). 직접 계산하지 말 것"),
  allInStock: z.boolean().describe("추천 상품이 모두 재고가 있으면 true"),
  reason: z.string().describe("추천 이유를 2문장 이내로"),
});

const SHOPPING_PROMPT = [
  "너는 온라인 쇼핑몰 상담원이다.",
  "",
  "규칙:",
  "1. 상품 ID 는 반드시 search_products 로 먼저 확인한다. ID 를 추측하지 않는다.",
  "2. 재고를 언급하기 전에 반드시 check_stock 으로 확인한다.",
  "3. 금액 계산은 절대 직접 하지 않는다. quote 도구를 쓴다.",
  "4. 품절 상품은 추천하지 않는다.",
  "5. 도구 결과에 없는 정보는 지어내지 않고 모른다고 답한다.",
].join("\n");

async function section8() {
  console.log("\n===== [8-8] 쇼핑 상담 에이전트 =====");

  // 도구 3개. 본문 8-5 의 "한 에이전트당 5~7개" 상한 안이고 역할이 겹치지 않습니다.
  const agent = createAgent({
    model: MODEL,
    tools: [searchProducts, checkStock, quote],
    systemPrompt: SHOPPING_PROMPT,
    responseFormat: Recommendation,
    name: "shopping_assistant",
  });

  const question =
    "주변기기 중에 50만원 이하로 살 만한 거 추천해줘. 재고 있는 걸로 2개씩.";

  console.log("-- 트레이스 --");
  // 정상 흐름은 도구 왕복 3회 전후 = 7스텝 전후.
  // 본문 8-5 팁대로 "정상일 때의 2배" 인 12 를 한도로 줍니다.
  await traceAgent(agent, question, { recursionLimit: 12 });

  console.log("\n-- 구조화된 최종 답변 --");
  const result = await agent.invoke(
    { messages: [{ role: "user", content: question }] },
    { recursionLimit: 12 },
  );
  console.log(result.structuredResponse);

  // 트레이스에서 check_stock 이 한 턴에 여러 번 나갈 수 있습니다(병렬 도구 호출).
  // 이때 ToolMessage 가 호출 순서대로 돌아온다는 보장이 없습니다.
  // 짝을 맞추려면 tool_call_id 로 매칭하세요. (본문 8-8 함정)
}

/* ===== 실행 ===== */

async function main() {
  await section1();
  await section2();
  await section3();
  await section4();
  await section5();
  // section6 은 판단 기준이라 실행 코드가 없습니다.
  await section7();
  await section8();
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
