/**
 * Step 18 — 멀티 에이전트 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-18-multi-agent/solution.ts
 *
 * exercise.ts 를 먼저 스스로 풀어본 뒤에 읽으세요.
 * 각 정답 위 주석에 "왜 이렇게 푸는가" 와 "틀리면 어떻게 되는가" 를 적어 두었습니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool, ToolMessage } from "langchain";
import { Command, MemorySaver } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== 공용 도구 ===== */

const STOCK: Record<string, number> = { "노트북": 3, "키보드": 0, "모니터": 12 };

const checkStock = tool(
  ({ product }) => {
    const n = STOCK[product];
    return n === undefined ? `${product}: 취급하지 않는 상품입니다` : `${product}: 재고 ${n}개`;
  },
  {
    name: "check_stock",
    description: "상품명으로 현재 재고 수량을 조회합니다.",
    schema: z.object({ product: z.string().describe("상품명") }),
  },
);

const getRefundPolicy = tool(
  () => "7일 이내 미개봉 전액 환불. 7~30일 50% 환불. 30일 초과 환불 불가.",
  {
    name: "get_refund_policy",
    description: "환불 정책 전문을 반환합니다.",
    schema: z.object({}),
  },
);

/* ===== [정답 1] 나눌 것인가, 말 것인가 =====
 *
 * (a) 사내 위키 검색 → **단일**.
 *     도구가 1개입니다. 나눌 대상 자체가 없습니다. "도구 하나 더" 로 끝나는 일에
 *     에이전트를 나누면 지연과 디버깅 비용만 얻고 얻는 게 없습니다.
 *
 * (b) 고객 문의 3도메인, 도메인당 도구 5~8개, 팀이 3개 → **멀티**.
 *     이유는 두 가지입니다. 첫째, 도구가 20개 넘게 한 모델에 붙으면 선택 정확도가
 *     떨어집니다. 둘째, 그리고 이게 더 중요한데, **팀이 3개**입니다.
 *     멀티에이전트의 가장 현실적인 명분은 성능이 아니라 조직입니다 —
 *     각 팀이 남의 프롬프트를 건드리지 않고 자기 에이전트를 배포할 수 있습니다.
 *
 * (c) CSV 분석, 도구 3개, 2초 제한 → **단일**.
 *     2초 제한이 결정타입니다. 에이전트를 나누면 모델 호출이 최소 1번 늘고,
 *     한 번이 1~3초입니다. 지연 예산이 빡빡하면 멀티에이전트는 시작부터 탈락입니다.
 *
 * 정리: (a)(c) 는 나눌 이유가 없고, (b) 는 "조직이 나뉘어 있다" 는 이유로 나눕니다.
 */

/* ===== [정답 2] 서브에이전트를 도구로 감싸기 ===== */

const summarizeSubagent = createAgent({
  model: MODEL,
  tools: [],
  systemPrompt:
    "너는 요약 전문가다. 어떤 글이 오든 핵심만 뽑아 정확히 3줄로 요약하라. " +
    "원문에 없는 내용을 덧붙이지 마라.",
});

const summarizeTool = tool(
  async ({ text }): Promise<string> => {
    const r = await summarizeSubagent.invoke({
      messages: [{ role: "user", content: text }],
    });

    // 핵심: r.messages 전체가 아니라 **마지막 메시지의 텍스트만** 반환합니다.
    // r.messages 를 통째로 문자열화해 돌려주면 서브에이전트의 내부 시행착오가
    // 부모 컨텍스트로 새어 들어옵니다 — 격리가 깨지고 토큰이 폭증합니다.
    return r.messages.at(-1)?.text ?? "(빈 응답)";
  },
  {
    name: "summarize",
    // description 은 부모 모델이 이 도구에 대해 아는 전부입니다.
    // "언제" 부를지를 여기에 적어야 모델이 제때 부릅니다.
    description:
      "긴 글을 3줄로 요약합니다. 요약할 원문 전체를 text 에 그대로 넣으세요. " +
      "이 도구는 대화 맥락을 볼 수 없으므로 원문을 생략하면 안 됩니다.",
    schema: z.object({ text: z.string().describe("요약할 원문 전체") }),
  },
);

async function problem2(): Promise<void> {
  printSection("[정답 2] 서브에이전트를 도구로 감싸기");

  const agent = createAgent({
    model: MODEL,
    tools: [summarizeTool],
    systemPrompt: "너는 비서다. 긴 글을 받으면 summarize 도구로 요약해서 전달하라.",
  });

  const long = `LangChain 은 2022년에 시작된 LLM 애플리케이션 프레임워크다.
초기에는 체인(Chain) 개념이 중심이었으나, v1 에서는 에이전트와 미들웨어가 핵심이 되었다.
LangGraph 는 그 아래에서 상태 그래프를 담당한다. 멀티 에이전트는 이 위에 얹힌다.`;

  const r = await agent.invoke({ messages: [{ role: "user", content: `요약해줘:\n${long}` }] });
  printMessages(r.messages);
}

/* ===== [정답 3] 컨텍스트 격리 함정 고치기 ===== */

const stockSubagent = createAgent({
  model: MODEL,
  tools: [checkStock],
  systemPrompt: "너는 재고 조사 전문가다. check_stock 으로 확인한 사실만 보고하라.",
});

/**
 * 고친 도구.
 *
 * 문제의 원본은 schema 가 `{ query: string }` 이었습니다. 자유 문자열 한 칸이면
 * 모델은 사용자가 한 말을 거의 그대로("그거 재고 있어?") 복사해 넣습니다.
 * 그러면 서브에이전트는 "그거"를 해석할 방법이 없습니다 — 부모 대화를 못 보니까요.
 *
 * 해법의 핵심은 **description 을 잘 쓰는 게 아니라 schema 로 강제하는 것**입니다.
 * productNames 를 string[] 로 만들면 모델이 지시어를 넣을 자리가 구조적으로 없어집니다.
 * "노트북" 이라는 실제 이름을 대화에서 찾아 채워 넣는 수밖에 없습니다.
 */
const stockResearchTool = tool(
  async ({ productNames, question }): Promise<string> => {
    // 부모가 넘긴 정보만으로 자기완결적인 지시문을 조립합니다.
    const prompt = `다음 상품들의 재고를 확인해줘: ${productNames.join(", ")}
알고 싶은 것: ${question}`;

    const r = await stockSubagent.invoke({ messages: [{ role: "user", content: prompt }] });
    return r.messages.at(-1)?.text ?? "";
  },
  {
    name: "research_stock",
    description:
      "재고 조사 전문가에게 위임합니다. 이 전문가는 지금까지의 대화를 전혀 볼 수 없습니다. " +
      "따라서 '그거', '아까 그 상품' 같은 지시어 대신 반드시 실제 상품명을 나열하세요.",
    schema: z.object({
      productNames: z.array(z.string()).describe("조사할 상품명들. 지시어 금지, 실제 이름만."),
      question: z.string().describe("이 상품들에 대해 알고 싶은 것"),
    }),
  },
);

async function problem3(): Promise<void> {
  printSection("[정답 3] 컨텍스트 격리 함정 고치기");

  const agent = createAgent({
    model: MODEL,
    tools: [stockResearchTool],
    systemPrompt: "너는 상담원이다.",
  });

  const r = await agent.invoke({
    messages: [
      { role: "user", content: "노트북 보고 있어요." },
      { role: "assistant", content: "네, 노트북이요. 무엇을 도와드릴까요?" },
      { role: "user", content: "그거 재고 있어요?" },
    ],
  });

  // productNames: ["노트북"] 이 들어간 게 보일 겁니다.
  printMessages(r.messages.slice(-3));
}

/* ===== [정답 4] 핸드오프 도구 만들기 ===== */

const handoffMiddleware = createMiddleware({
  name: "HandoffMiddleware",
  stateSchema: z.object({ activeAgent: z.string().default("general") }),
  wrapModelCall: async (request, handler) => {
    const prompts: Record<string, string> = {
      general: "너는 일반 상담원이다. 기술 문제로 보이면 transfer_to_technical 로 넘겨라.",
      technical: "너는 기술지원 상담원이다. 재현 절차를 묻고 해결책을 제시하라.",
    };
    return handler({
      ...request,
      systemPrompt: prompts[request.state.activeAgent] ?? prompts["general"] ?? "",
    });
  },
});

/**
 * 핸드오프 도구.
 *
 * 두 가지가 핵심입니다.
 *
 * 1. 반환값이 Command 입니다. 평범한 도구는 문자열을 돌려주고 그게 ToolMessage 로
 *    자동 포장되지만, Command 를 돌려주면 그 자동 포장이 **일어나지 않습니다**.
 *    ToolNode 는 Command.update 를 그래프 상태에 그대로 적용할 뿐입니다.
 *
 * 2. 그래서 ToolMessage 를 직접 넣어야 합니다. 이걸 빠뜨리면 messages 가
 *    [human, ai(tool_call), ai] 가 되어 tool_call 에 짝이 없는 상태가 됩니다.
 *    LangChain 은 여기서 에러를 내지 않습니다 — 조용히 지나갑니다.
 *    터지는 건 다음 모델 호출 때 제공자 쪽입니다:
 *      Anthropic → "tool_use ids were found without tool_result blocks"
 *      OpenAI    → "assistant message with 'tool_calls' must be followed by tool messages"
 *    로컬 테스트에서 안 잡히고 실제 호출에서만 터지는 전형적인 함정입니다.
 */
const transferToTechnical = tool(
  (_input, runtime: ToolRuntime): Command =>
    new Command({
      update: {
        activeAgent: "technical",
        messages: [
          new ToolMessage({
            content: "기술지원 상담원으로 전환했습니다.",
            // runtime.toolCallId 를 그대로 씁니다. 손으로 만든 id 를 넣으면
            // 짝이 안 맞아 위와 똑같은 에러가 납니다.
            tool_call_id: runtime.toolCallId,
          }),
        ],
      },
    }),
  {
    name: "transfer_to_technical",
    description: "기술적 문제일 때 기술지원 상담원에게 대화를 넘깁니다.",
    schema: z.object({}),
  },
);

async function problem4(): Promise<void> {
  printSection("[정답 4] 핸드오프 도구 만들기");

  const agent = createAgent({
    model: MODEL,
    tools: [transferToTechnical],
    systemPrompt: "상담원",
    // 체크포인터가 없으면 activeAgent 가 매 턴 "general" 로 되돌아가
    // 핸드오프가 아무 의미도 없어집니다.
    checkpointer: new MemorySaver(),
    middleware: [handoffMiddleware],
  });

  const config = { configurable: { thread_id: "sol4" } };

  const t1 = await agent.invoke({ messages: [{ role: "user", content: "앱이 자꾸 튕겨요" }] }, config);
  printMessages(t1.messages);
  printKV({ "1턴 후 activeAgent": t1.activeAgent }); // → "technical"

  const t2 = await agent.invoke({ messages: [{ role: "user", content: "아이폰이에요" }] }, config);
  printKV({ "2턴 후 activeAgent": t2.activeAgent }); // → "technical" (유지)
}

/* ===== [정답 5] 라우터 만들기 ===== */

/**
 * ⚠️ domain 을 z.string() 으로 두면 모델이 "결제팀", "billing 관련" 같은 값을
 * 자유롭게 뱉습니다. 그러면 routeSpecialists[domain] 이 undefined 가 되고,
 * strict 모드가 아니면 그대로 런타임 크래시입니다.
 * z.enum 은 제공자의 구조화 출력 스키마에 "이 셋 중 하나" 로 박히므로
 * 모델이 다른 값을 만들 수 없습니다.
 */
const RouteSchema = z.object({
  domain: z.enum(["billing", "stock", "general"]).describe("문의가 속한 도메인"),
  reason: z.string().describe("분류 이유 한 문장"),
});

const routeSpecialists: Record<string, ReturnType<typeof createAgent>> = {
  billing: createAgent({
    model: MODEL,
    tools: [getRefundPolicy],
    systemPrompt: "너는 결제 전문 상담원이다.",
  }),
  stock: createAgent({
    model: MODEL,
    tools: [checkStock],
    systemPrompt: "너는 재고 전문 상담원이다.",
  }),
  general: createAgent({ model: MODEL, tools: [], systemPrompt: "너는 일반 상담원이다." }),
};

// 분류기는 도구가 없고 responseFormat 만 있습니다.
// 실무에서는 여기에 값싸고 빠른 모델을 씁니다 — 분류는 추론이 거의 필요 없습니다.
const classifier = createAgent({
  model: MODEL,
  tools: [],
  systemPrompt: "너는 고객 문의 분류기다. 문의를 읽고 도메인 하나를 고르라.",
  responseFormat: RouteSchema,
});

async function routeAndAnswer(question: string): Promise<string> {
  // 1) 분류
  const routed = await classifier.invoke({ messages: [{ role: "user", content: question }] });
  const route = routed.structuredResponse as z.infer<typeof RouteSchema>;

  // 2) 위임 — enum 덕분에 여기서 undefined 가 날 수 없습니다.
  const specialist = routeSpecialists[route.domain];
  if (specialist === undefined) throw new Error(`알 수 없는 도메인: ${route.domain}`);

  const answer = await specialist.invoke({ messages: [{ role: "user", content: question }] });

  // 3) 텍스트 반환
  return `[${route.domain}] ${answer.messages.at(-1)?.text ?? ""}`;
}

async function problem5(): Promise<void> {
  printSection("[정답 5] 라우터 만들기");
  for (const q of ["환불 언제 되나요?", "모니터 재고 있어요?"]) {
    console.log(`\nQ: ${q}`);
    console.log(`A: ${await routeAndAnswer(q)}`);
  }
}

/* ===== [정답 6] Skills ===== */

const SKILLS: Record<string, string> = {
  refund_expert: `[환불 전문가]
정책: 7일 이내 전액, 7~30일 50%, 30일 초과 불가.
규칙: 구매일을 먼저 묻고, 환불액은 계산식을 보여줄 것.`,
  stock_expert: `[재고 전문가]
규칙: 재고가 0이면 반드시 대체 상품을 함께 제안할 것.`,
};

const loadSkill = tool(
  ({ skillName }): string => {
    const skill = SKILLS[skillName];

    // 없는 스킬에 빈 문자열을 돌려주면 모델은 "아 지식이 없구나" 하고
    // 그냥 자기가 아는 대로 지어냅니다(환각). 에러 문자열 + 사용 가능 목록을 주면
    // 모델이 다시 고를 기회를 얻습니다. 도구의 실패 메시지도 프롬프트입니다.
    return skill ?? `'${skillName}' 스킬은 없습니다. 사용 가능: ${Object.keys(SKILLS).join(", ")}`;
  },
  {
    name: "load_skill",
    // 스킬 목록을 description 에 적는 게 핵심입니다. 이게 모델이 보는 "진열장"입니다.
    // 여기 안 적으면 모델은 어떤 스킬이 있는지 영영 모릅니다.
    description: `전문 스킬을 불러옵니다.

사용 가능한 스킬:
- refund_expert: 환불 정책과 환불액 계산 규칙
- stock_expert: 재고 안내 규칙 (품절 시 대체 상품 제안)

스킬의 프롬프트와 지식을 반환합니다.`,
    schema: z.object({ skillName: z.string().describe("불러올 스킬 이름") }),
  },
);

async function problem6(): Promise<void> {
  printSection("[정답 6] Skills");

  const agent = createAgent({
    model: MODEL,
    tools: [loadSkill, checkStock],
    systemPrompt: "너는 상담원이다. 전문 지식이 필요하면 load_skill 로 불러온 뒤 그 규칙을 따르라.",
  });

  // 키보드는 재고 0 입니다. stock_expert 스킬을 불러왔다면
  // "대체 상품을 제안하라" 는 규칙을 따라야 합니다.
  const r = await agent.invoke({ messages: [{ role: "user", content: "키보드 사려는데요" }] });
  printMessages(r.messages);
}

/* ===== [정답 7] 핸드오프 루프 막기 ===== */

/**
 * A→B→A→B 무한 핸드오프 방어.
 *
 * 왜 recursionLimit 만으론 부족한가:
 *   recursionLimit 은 그래프 스텝이 한계를 넘으면 **GraphRecursionError 를 던집니다.**
 *   즉 사용자는 답변 대신 500 에러를 받습니다. 게다가 한계에 도달할 때까지
 *   모델을 계속 부르므로 토큰은 토큰대로 다 씁니다.
 *   그건 "안전장치" 이지 "해결책" 이 아닙니다.
 *
 *   여기서 하는 것은 다릅니다 — 전환 횟수가 넘으면 핸드오프 도구 자체를
 *   모델에게서 **치워버립니다**. 모델은 넘길 수단이 없으니 직접 답할 수밖에 없습니다.
 *   에러 대신 답변이 나갑니다. 이게 실무에서 원하는 동작입니다.
 *
 * 참고: transferCount 를 실제로 올리는 건 핸드오프 도구 쪽입니다
 *       (Command.update 에 transferCount: state.transferCount + 1 을 넣는 식).
 *       여기서는 "읽어서 도구를 거르는" 부분만 보여줍니다.
 */
const MAX_TRANSFERS = 3;

const loopGuardMiddleware = createMiddleware({
  name: "LoopGuardMiddleware",
  stateSchema: z.object({
    activeAgent: z.string().default("general"),
    transferCount: z.number().default(0),
  }),
  wrapModelCall: async (request, handler) => {
    if (request.state.transferCount > MAX_TRANSFERS) {
      // 핸드오프 도구만 제거하고 나머지는 남깁니다.
      //
      // typeof 검사가 왜 필요한가: request.tools 의 타입은 (ClientTool | ServerTool)[] 이고
      // ServerTool 은 Record<string, unknown> 입니다(제공자가 서버에서 실행하는 도구라
      // 클라이언트가 아는 필드가 없습니다). 그래서 t.name 은 unknown 이고,
      // 곧바로 .startsWith 를 부르면 tsc 가 TS18046 으로 막습니다.
      const withoutHandoffs = request.tools.filter(
        (t) => !(typeof t.name === "string" && t.name.startsWith("transfer_")),
      );

      return handler({
        ...request,
        tools: withoutHandoffs,
        systemPrompt:
          `${request.systemPrompt}\n\n` +
          "[중요] 전환 한도에 도달했습니다. 더 이상 다른 상담원에게 넘길 수 없습니다. " +
          "지금 아는 선에서 직접 답하고, 모르면 모른다고 말하라.",
      });
    }
    return handler(request);
  },
});

/* ===== [정답 8] 종합 — 라우터 + 서브에이전트 ===== */

async function problem8(): Promise<void> {
  printSection("[정답 8] 종합 — 라우터 + 서브에이전트");

  const questions = ["환불 정책이 어떻게 되나요?", "노트북 재고 있어요?", "영업시간 알려주세요"];

  const started = Date.now();

  // 세 문의는 서로 독립적입니다. Promise.all 로 병렬 처리합니다.
  // for 루프로 await 하면 3배 느립니다 — 멀티에이전트에서 늘어난 지연을
  // 되찾는 거의 유일한 수단이 병렬화입니다.
  const answers = await Promise.all(
    questions.map(async (q) => {
      const answer = await routeAndAnswer(q);

      // 각 답변을 요약 서브에이전트로 3줄 요약합니다.
      const summary = await summarizeTool.invoke({ text: answer });

      return { q, answer, summary };
    }),
  );

  for (const { q, summary } of answers) {
    console.log(`\nQ: ${q}`);
    console.log(`요약:\n${String(summary)}`);
  }

  printKV({
    "처리한 문의 수": questions.length,
    "총 소요 시간(ms)": Date.now() - started,
  });
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await problem2();
  await problem3();
  await problem4();
  await problem5();
  await problem6();
  void loopGuardMiddleware; // 정답 7 은 미들웨어 정의가 전부입니다.
  await problem8();
}

await main();
