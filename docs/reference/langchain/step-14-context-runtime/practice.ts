/**
 * Step 14 — 컨텍스트와 런타임
 * 실행: npx tsx docs/reference/langchain/step-14-context-runtime/practice.ts
 *
 * 본문 14-1 ~ 14-8 의 예제를 순서대로 담았습니다.
 * 절 하나만 돌리려면 인자를 주세요:
 *   npx tsx docs/reference/langchain/step-14-context-runtime/practice.ts 14-5
 */
import "dotenv/config";

import { createAgent, createMiddleware, dynamicSystemPromptMiddleware, tool } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore as GraphStore } from "@langchain/langgraph";
import { ChatAnthropic } from "@langchain/anthropic";
import type { ToolRuntime } from "@langchain/core/tools";
import type { BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printMessages, printKV, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

const only = process.argv[2];
const run = (section: string): boolean => only === undefined || only === section;

/**
 * store 를 꺼내는 헬퍼.
 *
 * runtime.store 의 "타입"은 @langchain/core 의 BaseStore<string, unknown>
 * (mget/mset 을 가진 옛 키-값 스토어)로 선언되어 있습니다. 그런데 "실제로"
 * 주입되는 객체는 LangGraph 의 네임스페이스 스토어(get/put/search)입니다.
 * 즉 타입과 런타임이 어긋나 있습니다. (@langchain/core 1.2.3 기준)
 *
 * 그래서 runtime.store.get(...) 을 그냥 쓰면 tsc 가
 *   Property 'get' does not exist ... Did you mean 'mget'?
 * 라고 막습니다. 코드는 맞는데 타입만 틀린 상황이라 캐스팅으로 넘깁니다.
 * 본문 14-4 의 함정 참고.
 */
function graphStore(runtime: { store: unknown }): GraphStore | null {
  return (runtime.store ?? null) as GraphStore | null;
}

/**
 * 도구 이름을 꺼내는 헬퍼.
 *
 * request.tools 의 원소 타입은 ClientTool | ServerTool 인데,
 * ServerTool 은 그냥 Record<string, unknown> 입니다(제공자가 서버에서 실행하는
 * 내장 도구라 고정된 모양이 없습니다). 그래서 유니온 상태로 t.name 을 읽으면
 * 타입이 unknown 이 되어 t.name.startsWith(...) 같은 게 막힙니다.
 * 이름으로 도구를 거를 때는 이렇게 문자열인지 한 번 확인하고 씁니다.
 */
function toolName(t: { name?: unknown }): string {
  return typeof t.name === "string" ? t.name : "";
}

/* ===== [14-1] 컨텍스트 엔지니어링이란 — 모델이 실제로 본 것을 들여다본다 ===== */

/**
 * 컨텍스트 엔지니어링을 배우기 전에 "모델이 실제로 무엇을 받았는가"를
 * 볼 수 있어야 합니다. wrapModelCall 은 모델 호출 직전에 끼어들어
 * 최종 요청(ModelRequest)을 통째로 넘겨받습니다 — 여기서 찍어 보면 됩니다.
 *
 * 이 미들웨어는 이 스텝 내내 재사용합니다. 실무에서도 하나 만들어 두면
 * "왜 모델이 저 도구를 안 부르지?" 같은 질문의 절반은 눈으로 풀립니다.
 */
const inspectContext = createMiddleware({
  name: "InspectContext",
  wrapModelCall: async (request, handler) => {
    const systemText = request.systemMessage.text;
    console.log("┌─ 모델이 받은 컨텍스트 ────────────────────────");
    console.log(`│ 시스템 프롬프트: ${systemText.length}자`);
    console.log(`│   "${systemText.slice(0, 70).replace(/\n/g, " ")}${systemText.length > 70 ? "…" : ""}"`);
    console.log(`│ 도구        : [${request.tools.map(toolName).join(", ")}]`);
    console.log(`│ 메시지      : ${request.messages.length}개`);
    console.log(`│ 런타임 컨텍스트: ${JSON.stringify(request.runtime.context)}`);
    console.log("└──────────────────────────────────────────────");
    return handler(request);
  },
});

async function section141(): Promise<void> {
  printSection("[14-1] 모델이 실제로 본 것 — 컨텍스트를 눈으로 확인하기");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "당신은 간결한 사내 도우미입니다. 한국어로 두 문장 이내로 답합니다.",
    middleware: [inspectContext],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "환불 규정을 알려줘." }],
  });

  printMessages(result.messages.at(-1) as BaseMessage);

  // 출력의 "런타임 컨텍스트: undefined" 에 주목하세요.
  // contextSchema 를 안 줬고 invoke 에도 context 를 안 줬으니 비어 있습니다.
  // 이 자리를 14-3 에서 채웁니다.
  //
  // 그리고 모델은 "환불 규정"을 모릅니다 — 우리가 안 줬으니까요.
  // 프롬프트를 아무리 다듬어도 없는 정보는 나오지 않습니다.
  // 그게 프롬프트 엔지니어링과 컨텍스트 엔지니어링의 차이입니다.
}

/* ===== [14-2] 컨텍스트의 4가지 출처 ===== */

/**
 * 지시(systemPrompt) / 상태(stateSchema) / 런타임 컨텍스트(contextSchema) / 장기 메모리(store).
 * 네 곳을 한 번에 읽는 도구를 만들어 차이를 눈으로 봅니다.
 */

// (b) 상태 — 이 대화(thread) 안에서만 살아 있고, 체크포인터에 저장된다.
const fourState = z.object({
  ticketCount: z.number().default(0),
});

// (c) 런타임 컨텍스트 — 이번 invoke 에만 살아 있고, 어디에도 저장되지 않는다.
const fourContext = z.object({
  userId: z.string(),
  plan: z.enum(["free", "pro"]),
});

/**
 * 도구는 stateSchema 를 자기 필드로 선언하지 않습니다.
 * (ToolWrapperParams 에 stateSchema 라는 필드는 없습니다 — 넣으면 tsc 가 막습니다.)
 * 스키마는 createAgent 쪽에 주고, 도구에서는 ToolRuntime 의 제네릭 인자로만
 * "그 상태가 어떤 모양인지"를 타입에 알려 줍니다.
 */
const whereAmI = tool(
  async (_input, runtime: ToolRuntime<typeof fourState, typeof fourContext>) => {
    // (b) 상태 읽기
    const ticketCount = runtime.state.ticketCount;

    // (c) 런타임 컨텍스트 읽기 — 클로저로 캡처하지 않고 반드시 runtime 에서 꺼낸다 (14-8 함정)
    const { userId, plan } = runtime.context;

    // (d) 장기 메모리 읽기 — 대화가 끝나도 남는다
    const saved = await graphStore(runtime)?.get(["preferences", userId], "profile");
    const nickname = (saved?.value as { nickname?: string } | undefined)?.nickname ?? "(없음)";

    return [
      `상태(state).ticketCount = ${ticketCount}`,
      `컨텍스트(context).userId = ${userId}, plan = ${plan}`,
      `장기메모리(store).nickname = ${nickname}`,
    ].join("\n");
  },
  {
    name: "where_am_i",
    description: "현재 실행에서 접근 가능한 상태/컨텍스트/장기메모리 값을 그대로 보고합니다.",
    schema: z.object({}),
  },
);

async function section142(): Promise<void> {
  printSection("[14-2] 컨텍스트의 4가지 출처 — 지시 / 상태 / 컨텍스트 / 장기 메모리");

  const store = new InMemoryStore();
  // (d) 장기 메모리에 미리 심어 둔다. 이건 이 대화 밖에서 온 데이터다.
  await store.put(["preferences", "u-77"], "profile", { nickname: "민수님" });

  const agent = createAgent({
    // (a) 지시 — 고정된 규칙. 모든 요청에서 똑같다.
    systemPrompt: "당신은 사내 헬프데스크입니다. 도구 결과를 그대로 옮겨 적으세요.",
    model: "anthropic:claude-sonnet-4-6",
    tools: [whereAmI],
    stateSchema: fourState,
    contextSchema: fourContext,
    store,
    checkpointer: new MemorySaver(),
  });

  const result = await agent.invoke(
    { messages: [{ role: "user", content: "지금 내가 접근 가능한 값들을 보고해줘." }] },
    {
      configurable: { thread_id: "t-142" },
      context: { userId: "u-77", plan: "pro" },
    },
  );

  printMessages(result.messages.at(-1) as BaseMessage);
}

/* ===== [14-3] contextSchema — 실행 시점 데이터 주입 ===== */

// (A) 필수 필드로 만든 스키마
const strictContext = z.object({
  userId: z.string(),
  role: z.enum(["member", "admin"]),
});

// (B) 전부 optional / default 로 만든 스키마 — 조용히 틀리는 쪽
const looseContext = z.object({
  userId: z.string().optional(),
  role: z.enum(["member", "admin"]).default("member"),
});

const whoAmI = tool(
  async (_input, runtime: ToolRuntime<any, typeof strictContext>) => {
    const ctx = runtime.context;
    return `userId=${ctx.userId} role=${ctx.role}`;
  },
  {
    name: "who_am_i",
    description: "현재 요청을 보낸 사용자의 식별 정보를 반환합니다.",
    schema: z.object({}),
  },
);

async function section143(): Promise<void> {
  printSection("[14-3] contextSchema — 스레드에 저장되지 않는다");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [whoAmI],
    systemPrompt: "who_am_i 도구를 호출해 결과를 그대로 알려주세요.",
    contextSchema: strictContext,
    checkpointer: new MemorySaver(),
  });

  const thread = { configurable: { thread_id: "t-143" } };

  // 1회차 — context 를 준다.
  const first = await agent.invoke(
    { messages: [{ role: "user", content: "내 정보 알려줘." }] },
    { ...thread, context: { userId: "u-1", role: "admin" } },
  );
  console.log("\n[1회차 — context 있음]");
  printMessages(first.messages.at(-1) as BaseMessage);

  // 2회차 — 같은 thread_id 지만 context 를 빼먹는다.
  //   메시지 히스토리는 체크포인터에서 복원되지만 context 는 복원되지 않습니다.
  //
  //   참고로 아래 줄을 그냥 쓰면 tsc 가 먼저 막아 줍니다:
  //     await agent.invoke({ messages: [...] }, thread);
  //     → error TS2345: ... 'context' is missing
  //   contextSchema 에 필수 필드가 있으면 invoke 의 config 타입이 context 를 요구합니다.
  //   여기서는 "타입 검사를 안 거치면 런타임에 무슨 일이 나는지"를 보려고 일부러 우회합니다.
  //   (config 를 다른 함수에서 조립해 넘기면 실제로 이렇게 타입이 헐거워집니다.)
  const configWithoutContext = thread as unknown as Parameters<typeof agent.invoke>[1];

  console.log("\n[2회차 — 같은 thread, context 없음 → 필수 스키마는 런타임 에러]");
  try {
    await agent.invoke(
      { messages: [{ role: "user", content: "한 번 더 알려줘." }] },
      configWithoutContext,
    );
    console.log("(에러가 안 났다면 라이브러리 동작이 바뀐 것입니다)");
  } catch (error) {
    const message = (error as Error).message.replace(/\s+/g, " ").slice(0, 120);
    console.log("에러 발생 →", message, "…");
    console.log("context 는 스레드에 저장되지 않습니다. 매 invoke 마다 다시 주세요.");
  }

  // (B) 전부 optional 인 스키마였다면? — 에러 없이 그냥 넘어갑니다.
  console.log("\n[optional 스키마 — 에러 없이 조용히 undefined]");
  const looseAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "한 문장으로 인사하세요.",
    contextSchema: looseContext,
    middleware: [
      createMiddleware({
        name: "ShowContext",
        wrapModelCall: async (request, handler) => {
          // z.infer 상으로 role 은 "member" | "admin" (필수)입니다.
          // 하지만 실제로 찍히는 값은 undefined 입니다. 타입이 거짓말을 합니다.
          console.log("runtime.context =", JSON.stringify(request.runtime.context));
          return handler(request);
        },
      }),
    ],
  });

  await looseAgent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  console.log("context 자체를 안 주면 zod 의 .default() 는 발동하지 않습니다.");
  console.log("→ runtime.context 는 {} 도 아니고 통째로 undefined 입니다.");
}

/* ===== [14-4] Runtime 객체 — 도구/미들웨어 안에서 무엇을 볼 수 있나 ===== */

const runtimeContext = z.object({
  userId: z.string(),
  tenantId: z.string(),
});

const dumpRuntime = tool(
  async (_input, runtime: ToolRuntime<any, typeof runtimeContext>) => {
    // writer — custom 스트림 모드로 중간 진행 상황을 흘려보냅니다.
    // 스트리밍으로 실행하지 않으면 없을 수 있으니 옵셔널 호출을 씁니다.
    runtime.writer?.({ phase: "start", tool: "dump_runtime" });

    const store = graphStore(runtime);

    printKV({
      "context.userId": runtime.context?.userId ?? "(없음)",
      "context.tenantId": runtime.context?.tenantId ?? "(없음)",
      // 이 도구 호출을 가리키는 ID. ToolMessage 가 이 값으로 짝을 찾습니다(Step 07).
      toolCallId: runtime.toolCallId,
      "store 연결됨": store !== null,
      "writer 연결됨": runtime.writer !== null,
    });

    runtime.writer?.({ phase: "done", tool: "dump_runtime" });
    return "런타임 정보를 콘솔에 출력했습니다.";
  },
  {
    name: "dump_runtime",
    description: "디버깅용. 현재 도구 실행의 런타임 정보를 출력합니다.",
    schema: z.object({}),
  },
);

async function section144(): Promise<void> {
  printSection("[14-4] Runtime 객체 — context / store / writer / toolCallId");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [dumpRuntime],
    systemPrompt: "dump_runtime 도구를 한 번 호출하세요.",
    contextSchema: runtimeContext,
    store: new InMemoryStore(),
  });

  // streamMode: "custom" 으로 받으면 runtime.writer 로 보낸 청크가 여기로 옵니다.
  const stream = await agent.stream(
    { messages: [{ role: "user", content: "런타임 정보 좀 보여줘." }] },
    {
      context: { userId: "u-9", tenantId: "acme" },
      streamMode: "custom",
    },
  );

  for await (const chunk of stream) {
    console.log("custom 스트림:", chunk);
  }
}

/* ===== [14-5] 동적 시스템 프롬프트 ===== */

const promptContext = z.object({
  userName: z.string(),
  plan: z.enum(["free", "pro", "enterprise"]),
  locale: z.enum(["ko", "en"]),
});

/**
 * dynamicSystemPromptMiddleware 의 콜백은 (state, runtime) 을 받고,
 * 모델 호출 직전마다 실행됩니다. 대화 도중 상태가 바뀌면 프롬프트도 따라 바뀝니다.
 *
 * 제네릭 인자에는 zod 스키마가 아니라 "추론된 타입"을 넣습니다.
 *   dynamicSystemPromptMiddleware<z.infer<typeof promptContext>>
 * 이걸 빠뜨리면 runtime.context 가 unknown 이라 필드 접근에서 타입 에러가 납니다.
 */
const dynamicPrompt = dynamicSystemPromptMiddleware<z.infer<typeof promptContext>>(
  (state, runtime) => {
    const ctx = runtime.context;
    const lines = [
      ctx.locale === "ko"
        ? "당신은 한국어로만 답하는 고객 지원 에이전트입니다."
        : "You are a customer support agent. Answer only in English.",
      `상대방의 이름은 ${ctx.userName} 입니다. 이름으로 불러 주세요.`,
    ];

    // 요금제별 규칙 — 없는 기능을 약속하지 않게 막는 것이 핵심입니다.
    if (ctx.plan === "free") {
      lines.push(
        "이 사용자는 무료 요금제입니다. 유료 전용 기능(우선 지원, 전화 상담)은 안내하지 마세요.",
        "복잡한 요청은 요금제 업그레이드를 부드럽게 제안하세요.",
      );
    } else if (ctx.plan === "enterprise") {
      lines.push("이 사용자는 엔터프라이즈 고객입니다. 전담 매니저 연결을 먼저 제안하세요.");
    }

    // 상태(state)도 함께 볼 수 있습니다 — 대화가 길어지면 더 짧게.
    if (state.messages.length > 10) {
      lines.push("대화가 길어졌습니다. 답변을 세 문장 이내로 줄이세요.");
    }

    return lines.join("\n");
  },
);

async function section145(): Promise<void> {
  printSection("[14-5] 동적 시스템 프롬프트 — 같은 에이전트, 다른 프롬프트");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    contextSchema: promptContext,
    // systemPrompt 를 일부러 주지 않았습니다.
    // 둘 다 주면 "systemPrompt + 동적 프롬프트" 가 구분자 없이 이어붙습니다 (본문 함정 참고).
    middleware: [dynamicPrompt, inspectContext],
  });

  const question = { messages: [{ role: "user" as const, content: "전화로 상담받고 싶어요." }] };

  console.log("\n── free 사용자 ──");
  const free = await agent.invoke(question, {
    context: { userName: "김민수", plan: "free", locale: "ko" },
  });
  printMessages(free.messages.at(-1) as BaseMessage);

  console.log("\n── enterprise 사용자 (같은 질문, 같은 에이전트) ──");
  const ent = await agent.invoke(question, {
    context: { userName: "박지훈", plan: "enterprise", locale: "ko" },
  });
  printMessages(ent.messages.at(-1) as BaseMessage);
}

/* ===== [14-6] 동적 모델/도구 선택 ===== */

const routingContext = z.object({
  plan: z.enum(["free", "pro"]),
  role: z.enum(["member", "admin"]),
});

// 도구 3개 — 이름 접두사로 권한을 구분합니다.
const publicSearchDocs = tool(async ({ query }) => `문서 검색 결과: "${query}" 관련 문서 3건`, {
  name: "public_search_docs",
  description: "공개 문서를 검색합니다. 누구나 사용할 수 있습니다.",
  schema: z.object({ query: z.string().describe("검색어") }),
});

// 도구 본문에서 권한을 "다시" 확인하는 것에 주목하세요.
// 미들웨어의 도구 필터링은 방어선 1, 이 검사가 방어선 2 입니다.
const adminDeleteUser = tool(
  async ({ userId }, runtime: ToolRuntime<any, typeof routingContext>) => {
    if (runtime.context?.role !== "admin") {
      return "권한 없음: 관리자만 사용할 수 있습니다.";
    }
    return `사용자 ${userId} 를 삭제했습니다.`;
  },
  {
    name: "admin_delete_user",
    description: "사용자 계정을 영구 삭제합니다. 관리자 전용입니다.",
    schema: z.object({ userId: z.string().describe("삭제할 사용자 ID") }),
  },
);

const adminRefund = tool(
  async ({ amount }, runtime: ToolRuntime<any, typeof routingContext>) => {
    if (runtime.context?.role !== "admin") {
      return "권한 없음: 관리자만 사용할 수 있습니다.";
    }
    return `${amount}원을 환불 처리했습니다.`;
  },
  {
    name: "admin_refund",
    description: "결제를 환불합니다. 관리자 전용입니다.",
    schema: z.object({ amount: z.number().describe("환불 금액(원)") }),
  },
);

const fastModel = new ChatAnthropic({ model: "claude-haiku-4-5", temperature: 0 });
const strongModel = new ChatAnthropic({ model: "claude-sonnet-4-6", temperature: 0 });

/**
 * wrapModelCall 하나에서 모델과 도구를 동시에 갈아끼웁니다.
 *
 * 중요: 여기서 tools 를 걸러내는 것은 "모델에게 안 보여주는" 것이지
 * "실행을 막는" 것이 아닙니다. 진짜 권한 통제는 도구 본문 안에서
 * runtime.context.role 을 다시 확인해야 합니다 (위 도구 참고).
 */
const routeByContext = createMiddleware({
  name: "RouteByContext",
  contextSchema: routingContext,
  wrapModelCall: async (request, handler) => {
    const ctx = request.runtime.context;
    const role = ctx?.role ?? "member"; // context 가 없으면 가장 좁은 권한으로 떨어진다
    const plan = ctx?.plan ?? "free";

    // 도구 필터링 — 관리자가 아니면 admin_ 접두사 도구를 아예 안 보여준다.
    const tools =
      role === "admin"
        ? request.tools
        : request.tools.filter((t) => !toolName(t).startsWith("admin_"));

    // 모델 선택 — free 는 싸고 빠른 모델, pro 는 더 좋은 모델.
    const model = plan === "pro" ? strongModel : fastModel;

    console.log(
      `[라우팅] role=${role} plan=${plan} → 모델=${plan === "pro" ? "sonnet" : "haiku"}, ` +
        `도구=[${tools.map(toolName).join(", ")}]`,
    );

    // ModelRequest 는 평범한 객체입니다. 스프레드로 필요한 필드만 덮어씁니다.
    return handler({ ...request, model, tools });
  },
});

async function section146(): Promise<void> {
  printSection("[14-6] 동적 모델/도구 선택 — 컨텍스트가 도구 목록을 정한다");

  const agent = createAgent({
    model: fastModel, // 기본값. 미들웨어가 매번 덮어씁니다.
    tools: [publicSearchDocs, adminDeleteUser, adminRefund],
    systemPrompt: "사용 가능한 도구로 요청을 처리하세요. 불가능하면 왜 불가능한지 말하세요.",
    contextSchema: routingContext,
    middleware: [routeByContext],
  });

  const ask = { messages: [{ role: "user" as const, content: "사용자 u-42 계정을 삭제해줘." }] };

  console.log("\n── member (free) 가 삭제를 요청 ──");
  const member = await agent.invoke(ask, { context: { plan: "free", role: "member" } });
  printMessages(member.messages.at(-1) as BaseMessage);

  console.log("\n── admin (pro) 이 같은 요청 ──");
  const admin = await agent.invoke(ask, { context: { plan: "pro", role: "admin" } });
  printMessages(admin.messages.at(-1) as BaseMessage);
}

/* ===== [14-7] 컨텍스트 예산 — 많이 넣을수록 좋다는 착각 ===== */

/**
 * 관련 없는 문서를 잔뜩 넣으면 (1) 토큰이 늘고 (2) 정답률이 떨어집니다.
 * 후자를 흔히 컨텍스트 오염(context rot) 이라고 부릅니다.
 *
 * 같은 질문을 두 번 던집니다.
 *   A) 관련 문서 1건만
 *   B) 관련 문서 1건 + 무관한 문서 24건 (정답을 소음 한가운데 묻습니다)
 * 정답 근거가 양쪽 다 들어 있으므로 원리상 둘 다 맞아야 합니다.
 * 입력 토큰이 몇 배로 뛰는지, 답이 흔들리는지 보세요.
 */

const RELEVANT_DOC = "사규 제12조: 연차는 입사 1년 후 15일이 부여되며, 매 2년마다 1일씩 늘어난다.";

const NOISE_DOCS = Array.from({ length: 24 }, (_, i) => {
  const topics = ["3층 정수기 교체", "주차장 공사", "동호회 모집", "보안 교육"];
  return (
    `사내 공지 ${i + 1}: ${topics[i % topics.length]} 안내. ` +
    "자세한 내용은 인트라넷을 참고하세요. 문의는 총무팀으로 부탁드립니다."
  );
});

function buildPrompt(docs: string[]): string {
  return [
    "당신은 사규 안내 도우미입니다. 아래 문서만 근거로 답하세요.",
    "",
    "=== 문서 ===",
    ...docs,
    "=== 문서 끝 ===",
  ].join("\n");
}

async function askWithDocs(label: string, docs: string[]): Promise<void> {
  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: buildPrompt(docs),
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "입사 5년차의 연차는 며칠인가요? 숫자만 답하세요." }],
  });

  const last = result.messages.at(-1) as BaseMessage;
  // usage_metadata 는 optional 입니다. 없을 수도 있으니 방어적으로 읽습니다.
  const usage = (last as { usage_metadata?: { input_tokens: number } }).usage_metadata;

  printKV({
    조건: label,
    "문서 수": docs.length,
    "입력 토큰": usage?.input_tokens ?? "(제공자가 안 줌)",
    답변: last.text.trim().slice(0, 60),
  });
  console.log("");
}

async function section147(): Promise<void> {
  printSection("[14-7] 컨텍스트 예산 — 넣을수록 좋아지지 않는다");

  // A) 필요한 것만
  await askWithDocs("A) 관련 문서 1건", [RELEVANT_DOC]);

  // B) 다 밀어 넣기 — 정답 문서를 소음 한가운데 묻는다
  const buried = [...NOISE_DOCS.slice(0, 12), RELEVANT_DOC, ...NOISE_DOCS.slice(12)];
  await askWithDocs("B) 관련 1건 + 소음 24건", buried);

  console.log("입력 토큰이 몇 배로 뛰었는지 보세요. 답의 품질은 그만큼 좋아지지 않습니다.");
  console.log("컨텍스트는 예산입니다. '넣을 수 있다'와 '넣어야 한다'는 다릅니다.");
}

/* ===== [14-8] 실전 — 멀티테넌트 에이전트 ===== */

/**
 * 하나의 에이전트 정의로 여러 고객사(테넌트)를 서빙합니다.
 * 테넌트마다 프롬프트/도구/모델이 다릅니다. 프로세스는 하나입니다.
 *
 * 규칙:
 *   - 테넌트 식별은 오직 runtime.context.tenantId 로만 한다.
 *   - 모듈 최상단 변수(전역)에 tenantId 를 담지 않는다 — 동시 요청에서 섞인다.
 */

const tenantContext = z.object({
  tenantId: z.enum(["acme", "globex"]),
  userId: z.string(),
  role: z.enum(["member", "admin"]),
});
type TenantContext = z.infer<typeof tenantContext>;

interface TenantProfile {
  displayName: string;
  tone: string;
  allowedTools: string[];
  model: ChatAnthropic;
}

const TENANTS: Record<TenantContext["tenantId"], TenantProfile> = {
  acme: {
    displayName: "ACME 주식회사",
    tone: "격식 있는 존댓말로, 답변 끝에 담당자 연락처를 안내합니다.",
    allowedTools: ["public_search_docs", "acme_check_stock"],
    model: strongModel,
  },
  globex: {
    displayName: "글로벡스",
    tone: "친근한 반존대로 짧게 답합니다. 이모지는 쓰지 않습니다.",
    allowedTools: ["public_search_docs"],
    model: fastModel,
  },
};

// ACME 전용 도구. 여기서도 tenantId 를 다시 확인합니다.
const acmeCheckStock = tool(
  async ({ sku }, runtime: ToolRuntime<any, typeof tenantContext>) => {
    const tenantId = runtime.context?.tenantId;
    if (tenantId !== "acme") {
      return "권한 없음: 이 도구는 ACME 테넌트 전용입니다.";
    }
    // 실제라면 여기서 tenantId 로 스코프된 DB 를 조회합니다.
    return `[${tenantId}] SKU ${sku} 재고: 42개`;
  },
  {
    name: "acme_check_stock",
    description: "ACME 창고의 SKU 재고를 조회합니다.",
    schema: z.object({ sku: z.string().describe("상품 SKU 코드") }),
  },
);

const tenantRouting = createMiddleware({
  name: "TenantRouting",
  contextSchema: tenantContext,
  wrapModelCall: async (request, handler) => {
    const ctx = request.runtime.context;
    if (ctx === undefined || ctx === null) {
      // context 없이 들어온 요청은 어느 테넌트인지 알 수 없습니다.
      // 조용히 기본 테넌트로 넘기면 데이터가 새어 나갑니다. 반드시 막으세요.
      throw new Error("TenantRouting: context.tenantId 가 없습니다. invoke 에 context 를 주세요.");
    }

    const profile = TENANTS[ctx.tenantId];
    const tools = request.tools.filter((t) => profile.allowedTools.includes(toolName(t)));

    // SystemMessage.concat 을 쓰면 다른 미들웨어가 붙여 둔 캐시 제어나
    // 구조화된 콘텐츠 블록을 보존한 채로 이어붙일 수 있습니다.
    const systemMessage = request.systemMessage.concat(
      [
        "",
        `고객사: ${profile.displayName}`,
        `말투 규칙: ${profile.tone}`,
        `현재 사용자 권한: ${ctx.role}`,
      ].join("\n"),
    );

    console.log(
      `[테넌트] ${ctx.tenantId} / ${ctx.userId} → 도구=[${tools.map(toolName).join(", ")}]`,
    );

    return handler({ ...request, model: profile.model, tools, systemMessage });
  },
});

async function section148(): Promise<void> {
  printSection("[14-8] 실전 — 멀티테넌트 에이전트");

  const agent = createAgent({
    model: fastModel,
    tools: [publicSearchDocs, acmeCheckStock],
    systemPrompt: "당신은 B2B SaaS 의 고객 지원 에이전트입니다.",
    contextSchema: tenantContext,
    middleware: [tenantRouting],
  });

  const ask = { messages: [{ role: "user" as const, content: "SKU-1234 재고 얼마나 있어?" }] };

  console.log("\n── ACME 사용자 (재고 도구 있음) ──");
  const acme = await agent.invoke(ask, {
    context: { tenantId: "acme", userId: "u-1", role: "member" },
  });
  printMessages(acme.messages.at(-1) as BaseMessage);

  console.log("\n── 글로벡스 사용자 (재고 도구 없음, 같은 질문) ──");
  const globex = await agent.invoke(ask, {
    context: { tenantId: "globex", userId: "u-2", role: "member" },
  });
  printMessages(globex.messages.at(-1) as BaseMessage);

  // 동시 요청 안전성 확인 — 두 테넌트를 동시에 쏴도 섞이지 않아야 합니다.
  console.log("\n── 두 테넌트 동시 요청 (Promise.all) ──");
  const [a, b] = await Promise.all([
    agent.invoke(ask, { context: { tenantId: "acme", userId: "u-1", role: "member" } }),
    agent.invoke(ask, { context: { tenantId: "globex", userId: "u-2", role: "member" } }),
  ]);
  console.log("acme  :", (a.messages.at(-1) as BaseMessage).text.trim().slice(0, 50));
  console.log("globex:", (b.messages.at(-1) as BaseMessage).text.trim().slice(0, 50));
  console.log("\n두 답이 각자의 테넌트 규칙을 따르고 있다면 성공입니다.");
  console.log("tenantId 를 모듈 전역 변수에 담았다면 여기서 섞였을 겁니다.");
}

/* ===== 실행 ===== */

const SECTIONS: Array<[string, () => Promise<void>]> = [
  ["14-1", section141],
  ["14-2", section142],
  ["14-3", section143],
  ["14-4", section144],
  ["14-5", section145],
  ["14-6", section146],
  ["14-7", section147],
  ["14-8", section148],
];

for (const [id, fn] of SECTIONS) {
  if (run(id)) {
    await fn();
  }
}
