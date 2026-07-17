/**
 * Step 18 — 멀티 에이전트
 * 실행: npx tsx docs/reference/langchain/step-18-multi-agent/practice.ts
 *
 * 이 파일은 본문 18-1 ~ 18-8 의 예제를 순서대로 담고 있습니다.
 * 통째로 실행하면 모델을 30번 넘게 호출합니다(비용/시간 주의).
 * 특정 절만 보고 싶으면 아래 RUN 상수를 바꾸세요.
 *
 *   const RUN = ["18-3"];   // 18-3 만 실행
 *   const RUN = "all";      // 전부 실행
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool, ToolMessage } from "langchain";
import { Command, MemorySaver } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

/* ===== 실행 스위치 =====
 *
 * 절 번호를 배열로 주면 그 절만 돕니다. "all" 이면 전부.
 * 멀티에이전트 예제는 한 번 돌 때 모델을 여러 번 부르므로,
 * 처음에는 관심 있는 절 하나만 켜고 보는 걸 권합니다.
 */
const RUN: string[] | "all" = "all";

function should(section: string): boolean {
  return RUN === "all" || RUN.includes(section);
}

/* ===== 공용 설정 ===== */

// 이 코스의 표준 모델입니다.
// OpenAI 로 바꾸려면 "openai:gpt-5.5" 로만 바꾸면 됩니다 — 아래 코드는 그대로 동작합니다.
// (createAgent 는 "provider:model" 문자열을 받아 알아서 해당 제공자 클래스를 씁니다)
const MODEL = "anthropic:claude-sonnet-4-6";

// 호출 횟수를 세기 위한 카운터.
// "에이전트를 나누면 지연이 곱해진다"(18-1)를 숫자로 확인하는 데 씁니다.
let modelCalls = 0;

/**
 * 모델이 몇 번 불렸는지 세는 미들웨어.
 *
 * wrapModelCall 은 "모델 호출 한 번"을 감싸는 훅입니다.
 * handler(request) 를 부르면 실제 모델이 호출되고, 그 반환값이 AIMessage 입니다.
 * 여기서는 세기만 하고 request 를 그대로 흘려보냅니다.
 */
const countingMiddleware = createMiddleware({
  name: "CountingMiddleware",
  wrapModelCall: async (request, handler) => {
    modelCalls += 1;
    return handler(request);
  },
});

function resetCalls(): void {
  modelCalls = 0;
}

/* ===== [18-1] 먼저 단일 에이전트로 시도하라 ===== */

// 재고 조회 도구 — 실제 DB 대신 상수 테이블을 씁니다.
const STOCK: Record<string, number> = {
  "노트북": 3,
  "키보드": 0,
  "모니터": 12,
};

const checkStock = tool(
  ({ product }) => {
    const n = STOCK[product];
    return n === undefined ? `${product}: 취급하지 않는 상품입니다` : `${product}: 재고 ${n}개`;
  },
  {
    name: "check_stock",
    // 설명이 곧 프롬프트입니다. 부실하면 모델이 이 도구를 안 부릅니다.
    description: "상품명으로 현재 재고 수량을 조회합니다.",
    schema: z.object({ product: z.string().describe("상품명 (예: 노트북)") }),
  },
);

const REFUND_POLICY = `구매 후 7일 이내 미개봉 상품은 전액 환불.
7일 초과 30일 이내는 50% 환불. 30일 초과는 환불 불가.`;

const getRefundPolicy = tool(() => REFUND_POLICY, {
  name: "get_refund_policy",
  description: "환불 정책 전문을 반환합니다.",
  schema: z.object({}),
});

async function section18_1(): Promise<void> {
  printSection("[18-1] 단일 에이전트 — 도구 2개면 이걸로 충분하다");

  resetCalls();

  // 도구가 2개뿐인 이 정도 일에는 멀티에이전트가 필요 없습니다.
  // "도구 하나 더" 로 해결되면 나누지 마세요.
  const agent = createAgent({
    model: MODEL,
    tools: [checkStock, getRefundPolicy],
    systemPrompt: "너는 쇼핑몰 상담원이다. 도구로 사실을 확인한 뒤 한국어로 간결히 답하라.",
    middleware: [countingMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "노트북 재고 있나요? 그리고 환불 정책도 알려주세요." }],
  });

  printMessages(result.messages);
  printKV({ "모델 호출 횟수": modelCalls, "메시지 수": result.messages.length });
}

/* ===== [18-3] 서브에이전트 — 에이전트를 도구로 감싸기 ===== */

// 서브에이전트도 그냥 에이전트입니다. 특별한 클래스가 없습니다.
const researchSubagent = createAgent({
  model: MODEL,
  tools: [checkStock],
  systemPrompt:
    "너는 재고 조사 전문가다. check_stock 으로 확인한 사실만 보고하라. 추측하지 마라.",
  middleware: [countingMiddleware],
});

/**
 * 위 에이전트를 "도구"로 감싼 것.
 *
 * 핵심은 마지막 줄입니다 — 서브에이전트의 내부 대화(messages 전체)는 버리고
 * **마지막 메시지의 텍스트만** 부모에게 돌려줍니다.
 * 이게 컨텍스트 격리(context isolation)입니다. 부모의 컨텍스트 창에는
 * 서브에이전트가 도구를 몇 번 부르며 헤맸는지가 안 들어옵니다.
 */
const researchTool = tool(
  async ({ query }) => {
    const result = await researchSubagent.invoke({
      // ⚠️ 여기 넣어주는 query 가 서브에이전트가 보는 전부입니다.
      //    부모의 대화 히스토리는 자동으로 안 넘어갑니다.
      messages: [{ role: "user", content: query }],
    });

    const last = result.messages.at(-1);
    return last?.text ?? "(빈 응답)";
  },
  {
    name: "research_stock",
    // 이 설명이 부모 에이전트가 보는 유일한 정보입니다.
    // "언제 부를지"를 여기에 정확히 써야 합니다.
    description:
      "재고 조사 전문가에게 조사를 위임합니다. 상품명과 알고 싶은 것을 자연어로 완결되게 적으세요. " +
      "이 도구는 대화 맥락을 볼 수 없으므로 '그거', '아까 그 상품' 같은 지시어를 쓰지 마세요.",
    schema: z.object({
      query: z.string().describe("조사 요청. 예: '노트북과 키보드의 재고 수량을 확인해줘'"),
    }),
  },
);

async function section18_3(): Promise<void> {
  printSection("[18-3] 서브에이전트 — 도구로서의 에이전트");

  resetCalls();

  const supervisor = createAgent({
    model: MODEL,
    tools: [researchTool, getRefundPolicy],
    systemPrompt: "너는 총괄 상담원이다. 재고 관련 조사는 research_stock 에 위임하라.",
    middleware: [countingMiddleware],
  });

  const result = await supervisor.invoke({
    messages: [{ role: "user", content: "노트북이랑 키보드 재고 상황 정리해서 알려줘." }],
  });

  printMessages(result.messages);

  // 부모의 messages 에는 서브에이전트의 내부 도구 호출이 **없습니다**.
  // research_stock 이라는 도구를 한 번 부른 것으로만 보입니다.
  printKV({
    "모델 호출 횟수(부모+서브 합산)": modelCalls,
    "부모가 보는 메시지 수": result.messages.length,
  });
}

/* ===== [18-4] 핸드오프 — Command 로 제어권 넘기기 ===== */

/**
 * 핸드오프의 상태.
 *
 * activeAgent 가 "지금 누가 대화를 맡고 있는가"입니다.
 * 이 값이 체크포인터에 저장되어 **다음 턴까지 유지**되는 게 핵심입니다.
 * 그래서 두 번째 턴부터는 분류 비용이 0 이 됩니다(18-2 비교표의 "반복 요청" 열).
 */
const handoffMiddleware = createMiddleware({
  name: "HandoffMiddleware",
  stateSchema: z.object({
    // .default() 를 주지 않으면 첫 턴에 undefined 가 되어 아래 분기가 터집니다.
    activeAgent: z.string().default("general"),
  }),
  wrapModelCall: async (request, handler) => {
    const active = request.state.activeAgent;

    const prompts: Record<string, string> = {
      general: "너는 일반 상담원이다. 결제 문제로 보이면 transfer_to_billing 을 불러 넘겨라.",
      billing: "너는 결제 전문 상담원이다. 환불/청구 문제를 직접 해결하라. 넘기지 마라.",
    };

    // 상태에 따라 시스템 프롬프트를 갈아끼웁니다.
    // 에이전트는 하나지만 "인격"이 바뀝니다 — 이게 단일 에이전트 핸드오프입니다.
    return handler({
      ...request,
      systemPrompt: prompts[active] ?? prompts["general"] ?? "",
    });
  },
});

/**
 * 제어권을 넘기는 도구.
 *
 * 반환값이 Command 라는 게 포인트입니다. ToolNode 는 도구가 Command 를 돌려주면
 * update 를 그래프 상태에 그대로 적용합니다.
 *
 * ⚠️ update.messages 에 ToolMessage 를 반드시 넣어야 합니다.
 *    모델이 만든 tool_call 에는 짝이 되는 ToolMessage 가 있어야 하는데,
 *    Command 를 돌려주면 그 자동 생성이 일어나지 않습니다.
 *    빠뜨리면 다음 모델 호출에서 "tool_use 에 대응하는 tool_result 가 없다"는
 *    제공자 에러가 납니다.
 */
const transferToBilling = tool(
  (_input, runtime: ToolRuntime) =>
    new Command({
      update: {
        activeAgent: "billing",
        messages: [
          new ToolMessage({
            content: "결제 전문 상담원으로 전환했습니다.",
            // runtime.toolCallId 가 지금 처리 중인 tool_call 의 id 입니다.
            // 이걸 손으로 만들거나 빠뜨리면 대화가 깨집니다.
            tool_call_id: runtime.toolCallId,
          }),
        ],
      },
    }),
  {
    name: "transfer_to_billing",
    description: "결제/환불/청구 문제일 때 결제 전문 상담원에게 대화를 넘깁니다.",
    schema: z.object({}),
  },
);

async function section18_4(): Promise<void> {
  printSection("[18-4] 핸드오프 — 상태로 제어권 이양");

  resetCalls();

  const agent = createAgent({
    model: MODEL,
    tools: [transferToBilling, getRefundPolicy],
    systemPrompt: "너는 상담원이다.", // 미들웨어가 덮어씁니다.
    // 핸드오프는 상태가 턴을 넘어 살아남아야 의미가 있습니다.
    // 체크포인터가 없으면 activeAgent 가 매 턴 초기화되어 핸드오프가 무의미해집니다.
    checkpointer: new MemorySaver(),
    middleware: [handoffMiddleware, countingMiddleware],
  });

  const config = { configurable: { thread_id: "handoff-demo" } };

  // 1턴: general 이 받아서 billing 으로 넘깁니다.
  const t1 = await agent.invoke(
    { messages: [{ role: "user", content: "환불받고 싶은데요." }] },
    config,
  );
  printMessages(t1.messages);
  printKV({ "1턴 후 activeAgent": t1.activeAgent, "누적 모델 호출": modelCalls });

  const callsAfterTurn1 = modelCalls;

  // 2턴: 이미 billing 이므로 분류/전환 없이 바로 답합니다.
  const t2 = await agent.invoke(
    { messages: [{ role: "user", content: "20일 전에 샀는데 얼마나 돌려받나요?" }] },
    config,
  );
  printMessages(t2.messages.slice(-2));
  printKV({
    "2턴 후 activeAgent": t2.activeAgent,
    "2턴에서 쓴 모델 호출": modelCalls - callsAfterTurn1,
  });
}

/* ===== [18-5] 라우터 — 구조화 출력으로 분류 후 위임 ===== */

const RouteSchema = z.object({
  // enum 을 쓰면 모델이 정해진 값만 뱉습니다. z.string() 이면 "결제팀" 같은
  // 예상 못 한 값이 와서 아래 조회가 undefined 가 됩니다.
  domain: z.enum(["billing", "technical", "general"]).describe("문의가 속한 도메인"),
  reason: z.string().describe("그렇게 분류한 이유 한 문장"),
});

// 분류 전용 에이전트. 도구가 없고 responseFormat 만 있습니다.
// 이런 일에는 값싼 모델을 쓰는 게 정석입니다(18-5 실무 팁).
const classifier = createAgent({
  model: MODEL,
  tools: [],
  systemPrompt: "너는 고객 문의 분류기다. 문의를 읽고 도메인 하나를 고르라.",
  responseFormat: RouteSchema,
  middleware: [countingMiddleware],
});

// 도메인별 전문 에이전트.
const specialists: Record<string, ReturnType<typeof createAgent>> = {
  billing: createAgent({
    model: MODEL,
    tools: [getRefundPolicy],
    systemPrompt: "너는 결제 전문 상담원이다. 환불 정책 도구를 확인하고 답하라.",
    middleware: [countingMiddleware],
  }),
  technical: createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "너는 기술지원 상담원이다. 재현 절차를 먼저 묻고 해결책을 제시하라.",
    middleware: [countingMiddleware],
  }),
  general: createAgent({
    model: MODEL,
    tools: [checkStock],
    systemPrompt: "너는 일반 상담원이다.",
    middleware: [countingMiddleware],
  }),
};

async function section18_5(): Promise<void> {
  printSection("[18-5] 라우터 — 분류 후 위임");

  resetCalls();

  const question = "결제는 됐다는데 주문 내역에 안 보여요.";

  // 1단계: 분류
  const routed = await classifier.invoke({
    messages: [{ role: "user", content: question }],
  });

  // responseFormat 을 주면 structuredResponse 에 파싱된 객체가 들어옵니다.
  const route = routed.structuredResponse as z.infer<typeof RouteSchema>;
  printKV({ 분류: route.domain, 이유: route.reason });

  // 2단계: 위임
  const specialist = specialists[route.domain];
  if (specialist === undefined) {
    // enum 을 썼으므로 여기 올 일은 없지만, 방어 코드는 남겨 둡니다.
    throw new Error(`알 수 없는 도메인: ${route.domain}`);
  }

  const answer = await specialist.invoke({
    messages: [{ role: "user", content: question }],
  });

  printMessages(answer.messages.at(-1) ?? []);
  printKV({ "모델 호출 횟수": modelCalls });
}

/* ===== [18-6] Skills — 필요할 때 전문 지식만 꺼내 쓰기 ===== */

// 스킬은 "프롬프트 + 지식" 덩어리입니다. 에이전트도 아니고 도구도 아닙니다.
// 그냥 문자열입니다. 실무에서는 파일이나 DB 에서 읽어옵니다.
const SKILLS: Record<string, string> = {
  refund_expert: `[환불 전문가 스킬]
${REFUND_POLICY}
답변 규칙:
- 반드시 구매일을 먼저 물어라. 모르면 환불액을 계산하지 마라.
- 환불액은 "정가 × 비율" 로 계산하고 계산식을 보여줘라.`,

  shipping_expert: `[배송 전문가 스킬]
배송 정책: 오후 2시 이전 결제분은 당일 출고. 도서산간 +2일.
답변 규칙:
- 송장번호가 있으면 그것부터 확인하라고 안내하라.`,
};

const loadSkill = tool(
  ({ skillName }) => {
    const skill = SKILLS[skillName];
    // 없는 스킬을 요청했을 때 조용히 빈 문자열을 주면 모델이 그냥 지어냅니다.
    // 에러 문자열을 돌려주면 모델이 다시 고를 기회를 얻습니다.
    return skill ?? `그런 스킬은 없습니다. 사용 가능: ${Object.keys(SKILLS).join(", ")}`;
  },
  {
    name: "load_skill",
    // 스킬 목록을 description 에 적어두는 게 핵심입니다.
    // 이게 "진열장"이고, 모델은 이걸 보고 뭘 꺼낼지 정합니다.
    description: `전문 스킬을 불러옵니다.

사용 가능한 스킬:
- refund_expert: 환불 정책과 환불액 계산 규칙
- shipping_expert: 배송 정책과 송장 안내 규칙

스킬의 프롬프트와 지식을 반환합니다.`,
    schema: z.object({
      skillName: z.string().describe("불러올 스킬 이름"),
    }),
  },
);

async function section18_6(): Promise<void> {
  printSection("[18-6] Skills — 스킬 기반 위임");

  resetCalls();

  const agent = createAgent({
    model: MODEL,
    tools: [loadSkill],
    systemPrompt:
      "너는 상담원이다. 전문 지식이 필요하면 load_skill 로 먼저 불러온 뒤, " +
      "불러온 스킬의 답변 규칙을 그대로 따르라.",
    middleware: [countingMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "환불 얼마나 받을 수 있어요?" }],
  });

  printMessages(result.messages);

  // 스킬은 제어권을 넘기지 않습니다. 에이전트는 계속 하나입니다.
  // 대신 불러온 스킬 텍스트가 컨텍스트에 **누적**됩니다 — 이게 스킬의 비용입니다.
  printKV({ "모델 호출 횟수": modelCalls });
}

/* ===== [18-7] 상태 공유 설계 — 무엇을 넘기고 무엇을 가릴 것인가 ===== */

/**
 * 나쁜 예: 컨텍스트를 안 넘기는 서브에이전트 도구.
 *
 * 모델이 query 에 "그 상품 재고 확인해줘" 라고 적어 보내면
 * 서브에이전트는 "그 상품"이 뭔지 알 방법이 전혀 없습니다.
 */
const badResearchTool = tool(
  async ({ query }) => {
    const r = await researchSubagent.invoke({ messages: [{ role: "user", content: query }] });
    return r.messages.at(-1)?.text ?? "";
  },
  {
    name: "research_bad",
    description: "재고를 조사합니다.", // 맥락을 넘기라는 지시가 없습니다.
    schema: z.object({ query: z.string() }),
  },
);

/**
 * 좋은 예: 넘길 것을 스키마로 강제한다.
 *
 * 서브에이전트가 알아야 할 것을 **필드로 쪼개서** 모델이 빠뜨릴 수 없게 만듭니다.
 * "설명을 잘 쓰자"보다 "스키마로 강제하자"가 훨씬 잘 먹힙니다.
 */
const goodResearchTool = tool(
  async ({ productNames, question }) => {
    // 부모가 넘겨준 정보만으로 자기완결적인 지시문을 조립합니다.
    const prompt = `다음 상품들의 재고를 확인해줘: ${productNames.join(", ")}
알고 싶은 것: ${question}`;

    const r = await researchSubagent.invoke({ messages: [{ role: "user", content: prompt }] });
    return r.messages.at(-1)?.text ?? "";
  },
  {
    name: "research_good",
    description:
      "재고 조사 전문가에게 위임합니다. 이 전문가는 지금까지의 대화를 전혀 볼 수 없으므로, " +
      "상품명을 반드시 명시적으로 나열하세요.",
    schema: z.object({
      // 배열로 강제하면 모델이 "그거" 같은 지시어를 쓸 자리가 없어집니다.
      productNames: z.array(z.string()).describe("조사할 상품명들. 지시어 금지, 실제 이름만."),
      question: z.string().describe("이 상품들에 대해 알고 싶은 것"),
    }),
  },
);

async function section18_7(): Promise<void> {
  printSection("[18-7] 상태 공유 설계 — 스키마로 컨텍스트를 강제한다");

  resetCalls();

  // 일부러 "그거" 라는 지시어가 나오게 유도합니다.
  const messages = [
    { role: "user" as const, content: "노트북 관심 있어요." },
    { role: "assistant" as const, content: "네, 노트북 문의시군요. 무엇을 도와드릴까요?" },
    { role: "user" as const, content: "그거 재고 있어요?" },
  ];

  console.log("\n--- research_bad (맥락 안 넘김) ---");
  const bad = createAgent({
    model: MODEL,
    tools: [badResearchTool],
    systemPrompt: "너는 상담원이다.",
    middleware: [countingMiddleware],
  });
  const badResult = await bad.invoke({ messages });
  printMessages(badResult.messages.slice(-3));

  console.log("\n--- research_good (스키마로 강제) ---");
  const good = createAgent({
    model: MODEL,
    tools: [goodResearchTool],
    systemPrompt: "너는 상담원이다.",
    middleware: [countingMiddleware],
  });
  const goodResult = await good.invoke({ messages });
  printMessages(goodResult.messages.slice(-3));

  printKV({ "모델 호출 횟수(둘 합산)": modelCalls });
}

/* ===== [18-8] 실전 — 고객지원 멀티에이전트 ===== */

/**
 * 라우터(18-5) + 서브에이전트(18-3) 를 합친 구성입니다.
 *
 *   문의 → [분류기] → billing / technical / general 전문 에이전트 → 답변
 *
 * 각 전문 에이전트는 **자기 도구만** 봅니다. 결제 상담원은 재고 도구를 모르고,
 * 기술지원은 환불 정책을 모릅니다. 이게 컨텍스트 격리의 실전 형태입니다.
 */

// 주문 조회 도구 — 기술/결제 양쪽에서 필요합니다.
const ORDERS: Record<string, { status: string; product: string; daysAgo: number }> = {
  "A-1001": { status: "결제완료", product: "노트북", daysAgo: 20 },
  "A-1002": { status: "배송중", product: "모니터", daysAgo: 2 },
};

const lookupOrder = tool(
  ({ orderId }) => {
    const o = ORDERS[orderId];
    if (o === undefined) return `주문 ${orderId} 을(를) 찾을 수 없습니다.`;
    return `주문 ${orderId}: 상품=${o.product}, 상태=${o.status}, 구매 ${o.daysAgo}일 전`;
  },
  {
    name: "lookup_order",
    description: "주문번호로 주문 상세를 조회합니다. 주문번호 형식은 A-숫자4자리입니다.",
    schema: z.object({ orderId: z.string().describe("주문번호 (예: A-1001)") }),
  },
);

const restartGuide = tool(() => "1) 앱 완전 종료 2) 캐시 삭제 3) 재로그인 4) 재설치", {
  name: "get_troubleshooting_steps",
  description: "일반적인 앱 문제 해결 절차를 반환합니다.",
  schema: z.object({}),
});

// 도메인별 에이전트 — 도구 구성이 서로 다릅니다.
function buildSupportTeam() {
  return {
    billing: createAgent({
      model: MODEL,
      tools: [lookupOrder, getRefundPolicy],
      systemPrompt:
        "너는 결제 전문 상담원이다. 주문번호가 있으면 lookup_order 로 사실을 확인하고, " +
        "환불 정책을 조회해 실제 환불액까지 계산해 답하라. 정책에 없는 건 지어내지 마라.",
      middleware: [countingMiddleware],
    }),
    technical: createAgent({
      model: MODEL,
      tools: [restartGuide],
      systemPrompt: "너는 기술지원 상담원이다. 해결 절차를 단계별로 안내하라.",
      middleware: [countingMiddleware],
    }),
    general: createAgent({
      model: MODEL,
      tools: [checkStock, lookupOrder],
      systemPrompt: "너는 일반 상담원이다. 재고와 주문 조회를 도울 수 있다.",
      middleware: [countingMiddleware],
    }),
  };
}

async function handleSupportRequest(question: string): Promise<void> {
  const team = buildSupportTeam();

  // 1단계 — 분류
  const routed = await classifier.invoke({ messages: [{ role: "user", content: question }] });
  const route = routed.structuredResponse as z.infer<typeof RouteSchema>;

  // 2단계 — 위임
  const agent = team[route.domain];
  const result = await agent.invoke({ messages: [{ role: "user", content: question }] });

  console.log(`\nQ: ${question}`);
  console.log(`   ↳ 분류: ${route.domain} (${route.reason})`);
  console.log(`   ↳ 답변: ${result.messages.at(-1)?.text ?? ""}`);
}

async function section18_8(): Promise<void> {
  printSection("[18-8] 실전 — 고객지원 멀티에이전트");

  resetCalls();

  const questions = [
    "주문 A-1001 환불하면 얼마 받나요?",
    "앱이 자꾸 튕겨요.",
    "모니터 재고 있어요?",
  ];

  // 세 문의는 서로 독립적이므로 병렬로 처리할 수 있습니다.
  // 순차로 돌리면 3배 느립니다 — 멀티에이전트에서 병렬화는 지연을 되찾는 유일한 수단입니다.
  await Promise.all(questions.map(handleSupportRequest));

  printKV({ "총 모델 호출 횟수": modelCalls });
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  if (should("18-1")) await section18_1();
  if (should("18-3")) await section18_3();
  if (should("18-4")) await section18_4();
  if (should("18-5")) await section18_5();
  if (should("18-6")) await section18_6();
  if (should("18-7")) await section18_7();
  if (should("18-8")) await section18_8();
}

await main();
