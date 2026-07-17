/**
 * Step 18 — 멀티 에이전트 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-18-multi-agent/exercise.ts
 *
 * 각 [문제 N] 블록의 TODO 를 채우세요.
 * 지금 이 파일을 그대로 실행하면 문제 1부터 "TODO" 에러가 납니다. 정상입니다.
 *
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어본 뒤에 여세요.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool, ToolMessage } from "langchain";
import { Command, MemorySaver } from "@langchain/langgraph";
import type { ToolRuntime } from "@langchain/core/tools";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== 공용 도구 (문제에서 가져다 씁니다) ===== */

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

/* ===== [문제 1] 나눌 것인가, 말 것인가 =====
 *
 * 아래 세 상황을 읽고, 각각 "단일 에이전트" 와 "멀티 에이전트" 중 무엇으로
 * 시작해야 할지 고르고 이유를 주석으로 적으세요.
 *
 *   (a) 사내 위키를 검색해 질문에 답한다. 검색 도구 1개면 된다.
 *   (b) 고객 문의를 결제/기술/일반으로 나눠 처리한다. 도메인마다 도구가
 *       5~8개씩 있고, 세 팀이 각자 자기 도메인을 유지보수한다.
 *   (c) 사용자가 올린 CSV 를 분석해 요약한다. 도구는 3개. 응답이 2초 안에 와야 한다.
 *
 * 답:
 *   (a) → TODO: 단일/멀티 + 이유
 *   (b) → TODO: 단일/멀티 + 이유
 *   (c) → TODO: 단일/멀티 + 이유
 */

/* ===== [문제 2] 서브에이전트를 도구로 감싸기 =====
 *
 * "요약 전문가" 서브에이전트를 만들고, 그것을 도구로 감싸세요.
 *
 * 요구사항:
 *   - 서브에이전트: 도구 없음. systemPrompt 로 "긴 글을 3줄로 요약하라" 지시.
 *   - 감싼 도구 이름은 "summarize", schema 는 { text: string }.
 *   - ⚠️ 서브에이전트의 messages 전체가 아니라 **마지막 메시지의 텍스트만** 반환할 것.
 *     (그래야 컨텍스트 격리가 됩니다)
 */

const summarizeSubagent = createAgent({
  model: MODEL,
  tools: [],
  systemPrompt: "TODO: 요약 전문가 프롬프트를 작성하세요",
});

const summarizeTool = tool(
  async ({ text }): Promise<string> => {
    // TODO: summarizeSubagent 를 invoke 하고 마지막 메시지의 텍스트만 반환하세요.
    void text;
    throw new Error("TODO: 문제 2");
  },
  {
    name: "summarize",
    description: "TODO: 부모 모델이 언제 이걸 불러야 하는지 적으세요",
    schema: z.object({ text: z.string() }),
  },
);

async function problem2(): Promise<void> {
  printSection("[문제 2] 서브에이전트를 도구로 감싸기");

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

/* ===== [문제 3] 컨텍스트 격리 함정 고치기 =====
 *
 * 아래 도구는 "서브에이전트가 부모 대화를 못 본다" 는 함정에 그대로 걸립니다.
 * 사용자가 "그거 재고 있어?" 라고 하면 모델이 query="그거 재고 있어?" 를 넘겨
 * 서브에이전트가 "그거"가 뭔지 몰라 헤맵니다.
 *
 * 요구사항:
 *   - schema 를 고쳐서 모델이 지시어를 쓸 수 없게 **강제**하세요.
 *     (힌트: 자유 문자열 하나 대신 productNames: string[] 처럼 필드를 쪼갠다)
 *   - description 에 "이 도구는 대화 맥락을 볼 수 없다" 는 사실을 명시하세요.
 */

const stockSubagent = createAgent({
  model: MODEL,
  tools: [checkStock],
  systemPrompt: "너는 재고 조사 전문가다. check_stock 으로 확인한 사실만 보고하라.",
});

// TODO: 이 도구의 schema 와 description 을 고치세요.
const stockResearchTool = tool(
  async ({ query }): Promise<string> => {
    const r = await stockSubagent.invoke({ messages: [{ role: "user", content: query }] });
    return r.messages.at(-1)?.text ?? "";
  },
  {
    name: "research_stock",
    description: "재고를 조사합니다.",
    schema: z.object({ query: z.string() }),
  },
);

async function problem3(): Promise<void> {
  printSection("[문제 3] 컨텍스트 격리 함정 고치기");

  const agent = createAgent({
    model: MODEL,
    tools: [stockResearchTool],
    systemPrompt: "너는 상담원이다.",
  });

  // 일부러 "그거" 라는 지시어를 쓰게 만드는 대화입니다.
  const r = await agent.invoke({
    messages: [
      { role: "user", content: "노트북 보고 있어요." },
      { role: "assistant", content: "네, 노트북이요. 무엇을 도와드릴까요?" },
      { role: "user", content: "그거 재고 있어요?" },
    ],
  });
  printMessages(r.messages.slice(-3));
}

/* ===== [문제 4] 핸드오프 도구 만들기 =====
 *
 * 기술지원으로 넘기는 transfer_to_technical 도구를 완성하세요.
 *
 * 요구사항:
 *   - Command 를 반환해 activeAgent 를 "technical" 로 바꿀 것
 *   - ⚠️ update.messages 에 ToolMessage 를 반드시 포함할 것.
 *     tool_call_id 는 runtime.toolCallId 를 쓸 것.
 *     (빠뜨리면 로컬에선 조용히 지나가고 실제 제공자에서 에러가 납니다)
 */

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

const transferToTechnical = tool(
  (_input, runtime: ToolRuntime): Command => {
    void runtime;
    // TODO: Command 를 반환하세요. activeAgent 를 "technical" 로 바꾸고
    //       ToolMessage 를 messages 에 넣으세요.
    throw new Error("TODO: 문제 4");
  },
  {
    name: "transfer_to_technical",
    description: "기술적 문제일 때 기술지원 상담원에게 대화를 넘깁니다.",
    schema: z.object({}),
  },
);

async function problem4(): Promise<void> {
  printSection("[문제 4] 핸드오프 도구 만들기");

  const agent = createAgent({
    model: MODEL,
    tools: [transferToTechnical],
    systemPrompt: "상담원",
    checkpointer: new MemorySaver(),
    middleware: [handoffMiddleware],
  });

  const config = { configurable: { thread_id: "ex4" } };
  const t1 = await agent.invoke({ messages: [{ role: "user", content: "앱이 자꾸 튕겨요" }] }, config);
  printMessages(t1.messages);
  printKV({ "1턴 후 activeAgent": t1.activeAgent });

  // 2턴에서도 technical 이 유지되어야 합니다.
  const t2 = await agent.invoke({ messages: [{ role: "user", content: "아이폰이에요" }] }, config);
  printKV({ "2턴 후 activeAgent": t2.activeAgent });
}

/* ===== [문제 5] 라우터 만들기 =====
 *
 * 구조화 출력으로 문의를 분류한 뒤 전문 에이전트에게 넘기는 라우터를 완성하세요.
 *
 * 요구사항:
 *   - RouteSchema 의 domain 은 z.enum 으로 "billing" | "stock" | "general" 만 허용.
 *     (⚠️ z.string() 을 쓰면 모델이 "결제팀" 같은 값을 뱉어 조회가 undefined 가 됩니다)
 *   - routeAndAnswer(question) 이 분류 → 위임 → 답변 텍스트 반환.
 */

const RouteSchema = z.object({
  // TODO: domain 을 z.enum 으로 정의하세요.
  domain: z.string().describe("문의가 속한 도메인"),
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

async function routeAndAnswer(question: string): Promise<string> {
  // TODO: 1) 분류 전용 에이전트를 만들어 responseFormat: RouteSchema 로 분류하고
  //       2) routeSpecialists 에서 해당 전문가를 골라 invoke 한 뒤
  //       3) 마지막 메시지 텍스트를 반환하세요.
  void question;
  throw new Error("TODO: 문제 5");
}

async function problem5(): Promise<void> {
  printSection("[문제 5] 라우터 만들기");
  for (const q of ["환불 언제 되나요?", "모니터 재고 있어요?"]) {
    console.log(`\nQ: ${q}`);
    console.log(`A: ${await routeAndAnswer(q)}`);
  }
}

/* ===== [문제 6] Skills 로 전문 지식 꺼내 쓰기 =====
 *
 * load_skill 도구를 완성하세요.
 *
 * 요구사항:
 *   - SKILLS 에서 이름으로 찾아 반환.
 *   - 없는 이름이면 빈 문자열 대신 **사용 가능한 목록을 알려주는 에러 문자열**을 반환할 것.
 *     (빈 문자열을 주면 모델이 그냥 지어냅니다)
 *   - description 에 스킬 목록을 적어 모델이 뭘 고를 수 있는지 알게 할 것.
 */

const SKILLS: Record<string, string> = {
  refund_expert: `[환불 전문가]
정책: 7일 이내 전액, 7~30일 50%, 30일 초과 불가.
규칙: 구매일을 먼저 묻고, 환불액은 계산식을 보여줄 것.`,
  stock_expert: `[재고 전문가]
규칙: 재고가 0이면 반드시 대체 상품을 함께 제안할 것.`,
};

const loadSkill = tool(
  ({ skillName }): string => {
    // TODO: SKILLS 에서 찾아 반환. 없으면 사용 가능 목록을 안내하세요.
    void skillName;
    throw new Error("TODO: 문제 6");
  },
  {
    name: "load_skill",
    description: "TODO: 사용 가능한 스킬 목록을 여기에 적으세요",
    schema: z.object({ skillName: z.string().describe("불러올 스킬 이름") }),
  },
);

async function problem6(): Promise<void> {
  printSection("[문제 6] Skills");

  const agent = createAgent({
    model: MODEL,
    tools: [loadSkill, checkStock],
    systemPrompt: "너는 상담원이다. 전문 지식이 필요하면 load_skill 로 불러온 뒤 그 규칙을 따르라.",
  });

  const r = await agent.invoke({ messages: [{ role: "user", content: "키보드 사려는데요" }] });
  printMessages(r.messages);
}

/* ===== [문제 7] 핸드오프 루프 막기 =====
 *
 * A→B→A→B 무한 핸드오프를 막는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - stateSchema 에 transferCount: z.number().default(0) 를 두세요.
 *   - 전환이 3회를 넘으면 wrapModelCall 에서 **핸드오프 도구를 빼고** 모델을 부르세요.
 *     (힌트: handler({ ...request, tools: request.tools.filter(...) }))
 *   - 왜 recursionLimit 만으론 부족한지 주석으로 적으세요.
 */

const loopGuardMiddleware = createMiddleware({
  name: "LoopGuardMiddleware",
  stateSchema: z.object({
    activeAgent: z.string().default("general"),
    transferCount: z.number().default(0),
  }),
  wrapModelCall: async (request, handler) => {
    // TODO: transferCount 가 3 을 넘으면 transfer_ 로 시작하는 도구를 제거하고 handler 를 부르세요.
    return handler(request);
  },
});

/* ===== [문제 8] 종합 — 라우터 + 서브에이전트 =====
 *
 * 문제 5의 라우터와 문제 2의 요약 서브에이전트를 합치세요.
 *
 * 요구사항:
 *   - 여러 문의를 **병렬로**(Promise.all) 처리할 것. 순차로 돌리면 지연이 누적됩니다.
 *   - 각 답변을 summarizeTool 의 서브에이전트로 3줄 요약해 최종 출력할 것.
 *   - 처리한 문의 수와 총 소요 시간(ms)을 출력할 것.
 */

async function problem8(): Promise<void> {
  printSection("[문제 8] 종합 — 라우터 + 서브에이전트");

  const questions = ["환불 정책이 어떻게 되나요?", "노트북 재고 있어요?", "영업시간 알려주세요"];

  // TODO: questions 를 병렬로 라우팅해 답변을 만들고, 각 답변을 요약해 출력하세요.
  //       Date.now() 로 총 소요 시간도 재세요.
  void questions;
  throw new Error("TODO: 문제 8");
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await problem2();
  await problem3();
  await problem4();
  await problem5();
  await problem6();
  void loopGuardMiddleware; // 문제 7 은 미들웨어만 작성하면 됩니다.
  await problem8();
}

await main();
