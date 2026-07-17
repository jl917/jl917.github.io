/**
 * Step 12 — 커스텀 미들웨어
 * 실행: npx tsx docs/reference/langchain/step-12-middleware-custom/practice.ts
 *
 * 본문 12-1 ~ 12-8 의 예제를 순서대로 담았습니다.
 * 모델을 실제로 호출하므로 ANTHROPIC_API_KEY 가 필요하고, 전부 돌리면 30~60초 걸립니다.
 * 특정 절만 보고 싶으면 파일 맨 아래 main() 의 호출부를 주석 처리하세요.
 */
import "dotenv/config";

import {
  AIMessage,
  MiddlewareError,
  SystemMessage,
  ToolMessage,
  createAgent,
  createMiddleware,
  initChatModel,
  tool,
} from "langchain";
import { Command, ReducedValue, StateSchema } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printMessages, printKV, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

/* ===== 공용 도구 =====
 *
 * 미들웨어를 관찰하려면 "도구를 부르는 에이전트"가 필요합니다.
 * 네트워크 의존성을 없애려고 전부 가짜 데이터로 즉답합니다.
 */

const getWeather = tool(
  ({ city }) => {
    console.log(`      [도구 실제 실행] getWeather(${city})`);
    const table: Record<string, string> = {
      서울: "맑음, 24도",
      부산: "흐림, 21도",
      제주: "비, 19도",
    };
    return table[city] ?? `${city} 날씨 정보 없음`;
  },
  {
    name: "get_weather",
    description: "특정 도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름 (예: 서울)") }),
  },
);

const getPopulation = tool(
  ({ city }) => {
    console.log(`      [도구 실제 실행] getPopulation(${city})`);
    const table: Record<string, string> = {
      서울: "약 938만 명",
      부산: "약 329만 명",
      제주: "약 67만 명",
    };
    return table[city] ?? `${city} 인구 정보 없음`;
  },
  {
    name: "get_population",
    description: "특정 도시의 인구를 조회합니다.",
    schema: z.object({ city: z.string().describe("도시 이름") }),
  },
);

/** 위험한 도구 — 12-5 의 권한 검사 대상 */
const deleteRecord = tool(
  ({ id }) => {
    console.log(`      [도구 실제 실행] deleteRecord(${id})  ← 이게 찍히면 권한 검사가 뚫린 것`);
    return `레코드 ${id} 삭제됨`;
  },
  {
    name: "delete_record",
    description: "데이터베이스에서 레코드를 영구 삭제합니다.",
    schema: z.object({ id: z.string().describe("삭제할 레코드 ID") }),
  },
);

/* ===== [12-1] 훅 전체 — 6개 훅이 언제 불리는가 ===== */

/**
 * 6개 훅에 전부 로그만 심은 미들웨어를 만듭니다.
 * label 을 받아 두 개를 겹쳐 놓으면 "before 는 순서대로 / after 는 역순 / wrap 은 중첩"
 * 이라는 규칙이 출력으로 그대로 드러납니다.
 */
const tracer = (label: string) =>
  createMiddleware({
    name: `Tracer-${label}`,

    // 에이전트 실행 전체에서 딱 한 번. 루프를 몇 바퀴 돌든 한 번입니다.
    beforeAgent: () => {
      console.log(`${label} │ beforeAgent`);
      return;
    },

    // 모델 호출마다. 루프를 3바퀴 돌면 3번 불립니다.
    beforeModel: () => {
      console.log(`${label} │   beforeModel`);
      return;
    },

    // 모델 호출을 감싸는 훅. handler() 앞뒤로 코드가 갈라집니다.
    wrapModelCall: async (request, handler) => {
      console.log(`${label} │     wrapModelCall  →  (모델 부르기 직전)`);
      const response = await handler(request);
      console.log(`${label} │     wrapModelCall  ←  (모델이 답한 직후)`);
      return response;
    },

    // 모델 응답 직후, 도구가 실행되기 전.
    afterModel: () => {
      console.log(`${label} │   afterModel`);
      return;
    },

    // 도구 호출마다. 모델이 도구를 2개 부르면 2번 불립니다.
    wrapToolCall: async (request, handler) => {
      console.log(`${label} │     wrapToolCall   →  ${request.toolCall.name}`);
      const result = await handler(request);
      console.log(`${label} │     wrapToolCall   ←  ${request.toolCall.name}`);
      return result;
    },

    // 에이전트 실행 전체에서 딱 한 번, 맨 마지막.
    afterAgent: () => {
      console.log(`${label} │ afterAgent`);
      return;
    },
  });

async function hookOrder(): Promise<void> {
  printSection("[12-1] 훅 전체 — 실행 순서 관찰");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    systemPrompt: "너는 날씨 비서다. 도구로 확인한 사실만 답해라.",
    // 배열 순서가 곧 바깥→안쪽 순서입니다. A 가 바깥, B 가 안쪽.
    middleware: [tracer("A"), tracer("B")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });

  console.log(`
읽는 법:
  beforeAgent   A → B      (등록 순서대로)
  beforeModel   A → B      (등록 순서대로)
  wrapModelCall A→ B→ 모델 →B← A←   (양파처럼 중첩)
  afterModel    B → A      (역순!)
  afterAgent    B → A      (역순!)
`);
}

/* ===== [12-2] createMiddleware() 로 첫 미들웨어 만들기 ===== */

/**
 * 가장 쓸모 있는 첫 미들웨어는 로깅입니다.
 * 에이전트가 왜 그렇게 답했는지는 "모델에 뭘 넣었고 뭐가 나왔나"를 봐야 알 수 있는데,
 * createAgent 는 그걸 기본으로 보여주지 않습니다.
 */
const loggingMiddleware = createMiddleware({
  name: "LoggingMiddleware",

  beforeModel: (state) => {
    console.log(`  [로그] 모델 호출 예정 — 메시지 ${state.messages.length}개`);
    // 아무것도 안 바꿀 거면 undefined 를 반환합니다(= 통과).
    return;
  },

  afterModel: (state) => {
    const last = state.messages.at(-1);
    const toolCalls = (last as AIMessage | undefined)?.tool_calls ?? [];

    if (toolCalls.length > 0) {
      console.log(`  [로그] 모델이 도구 ${toolCalls.length}개 요청: ${toolCalls.map((c) => c.name).join(", ")}`);
    } else {
      console.log(`  [로그] 모델이 최종 답변: ${last?.text.slice(0, 40)}...`);
    }
    return;
  },
});

async function firstMiddleware(): Promise<void> {
  printSection("[12-2] createMiddleware() — 로깅 미들웨어");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather, getPopulation],
    systemPrompt: "너는 도시 정보 비서다.",
    middleware: [loggingMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "서울과 부산의 날씨와 인구를 알려줘." }],
  });

  console.log("\n최종 답변:");
  printMessages(result.messages.at(-1)!);
}

/* ===== [12-3] beforeModel — 매 모델 호출 전 메시지 손보기 ===== */

/**
 * beforeModel 은 state 를 받아 "상태 업데이트"를 반환합니다.
 * messages 는 append 리듀서를 쓰므로, 반환한 메시지는 기존 뒤에 "붙습니다".
 *
 * 여기서는 매 모델 호출 직전에 현재 시각을 SystemMessage 로 밀어 넣습니다.
 * (모델은 지금이 몇 시인지 모릅니다 — 알려주지 않으면 지어냅니다.)
 */
const timeInjectorMiddleware = createMiddleware({
  name: "TimeInjectorMiddleware",

  beforeModel: (state) => {
    const now = new Date().toISOString();
    console.log(`  [시각 주입] ${now} (메시지 ${state.messages.length}개 뒤에 append)`);

    // ⚠️ 반환값은 "덧붙일 것"이지 "전체 목록"이 아닙니다.
    //    state.messages 를 통째로 돌려주면 대화가 두 배로 불어납니다.
    return {
      messages: [new SystemMessage(`[시스템] 현재 시각은 ${now} 입니다.`)],
    };
  },
});

/**
 * beforeModel 로 "동적 시스템 프롬프트"를 흉내 낼 수는 있지만,
 * 그건 대화 기록(messages)을 영구히 오염시킵니다 — 주입한 SystemMessage 가
 * 체크포인터에 그대로 저장되고, 다음 턴에도 남아 있습니다.
 * 진짜 동적 시스템 프롬프트는 12-4 의 wrapModelCall 로 합니다.
 */
async function beforeModelDemo(): Promise<void> {
  printSection("[12-3] beforeModel — 메시지 손보기");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 간결한 비서다.",
    middleware: [timeInjectorMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "지금 몇 시인지 시스템이 알려준 대로만 말해줘." }],
  });

  printMessages(result.messages);

  console.log(`
주목: 주입한 SystemMessage 가 result.messages 안에 남아 있습니다.
      대화 기록을 더럽히지 않으려면 12-4 의 wrapModelCall 을 쓰세요.
`);
}

/* ===== [12-4] wrapModelCall — 모델 호출 감싸기 ===== */

/**
 * (1) 동적 시스템 프롬프트
 *
 * wrapModelCall 은 request.systemMessage 를 갈아끼울 수 있습니다.
 * 이건 "이번 모델 호출에만" 적용되고 state.messages 에는 안 남습니다.
 * 12-3 과 결정적으로 다른 점입니다.
 */
const dynamicPromptMiddleware = createMiddleware({
  name: "DynamicPromptMiddleware",

  wrapModelCall: async (request, handler) => {
    const turn = request.messages.filter((m) => m.getType() === "human").length;
    const extra = turn > 1 ? " 사용자가 이미 여러 번 물었다. 더 짧게 답해라." : "";

    console.log(`  [동적 프롬프트] 사용자 턴 ${turn}회 → 추가 지시${extra === "" ? " 없음" : " 있음"}`);

    // request 를 통째로 바꾸지 말고, 스프레드로 필요한 필드만 덮어씁니다.
    return handler({
      ...request,
      systemMessage: request.systemMessage.concat(extra),
    });
  },
});

async function dynamicPromptDemo(): Promise<void> {
  printSection("[12-4-1] wrapModelCall — 동적 시스템 프롬프트");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 친절한 비서다.",
    middleware: [dynamicPromptMiddleware],
  });

  const result = await agent.invoke({
    messages: [
      { role: "user", content: "하늘은 왜 파랗지?" },
      { role: "assistant", content: "빛의 산란 때문입니다." },
      { role: "user", content: "그럼 노을은 왜 빨갛지?" },
    ],
  });

  printMessages(result.messages.at(-1)!);

  console.log(`
주목: result.messages 에 추가 지시가 안 보입니다.
      systemMessage 는 이번 모델 호출에만 쓰이고 상태에 저장되지 않습니다.
      12-3 의 beforeModel 방식과 정반대입니다.
`);
}

/**
 * (2) 동적 모델 선택
 *
 * 짧은 대화는 싼 모델로, 길고 복잡해지면 비싼 모델로.
 * request.model 을 바꿔서 handler 에 넘기면 됩니다.
 */
async function dynamicModelDemo(): Promise<void> {
  printSection("[12-4-2] wrapModelCall — 동적 모델 선택");

  const models = {
    cheap: await initChatModel("anthropic:claude-haiku-4-5-20251001"),
    strong: await initChatModel("anthropic:claude-sonnet-4-6"),
  };

  const routerMiddleware = createMiddleware({
    name: "ModelRouterMiddleware",
    wrapModelCall: (request, handler) => {
      const useStrong = request.messages.length > 4;
      console.log(`  [모델 라우팅] 메시지 ${request.messages.length}개 → ${useStrong ? "sonnet(비쌈)" : "haiku(쌈)"}`);

      return handler({ ...request, model: useStrong ? models.strong : models.cheap });
    },
  });

  const agent = createAgent({
    // 이 model 은 "기본값"일 뿐, 미들웨어가 매번 덮어씁니다.
    model: models.strong,
    tools: [getWeather],
    systemPrompt: "너는 날씨 비서다.",
    middleware: [routerMiddleware],
  });

  await agent.invoke({ messages: [{ role: "user", content: "서울 날씨는?" }] });
}

/**
 * (3) 폴백을 직접 구현
 *
 * handler 를 여러 번 부를 수 있다는 게 wrapModelCall 의 힘입니다.
 * 첫 모델이 죽으면 두 번째 모델로 다시 부릅니다.
 */
async function fallbackDemo(): Promise<void> {
  printSection("[12-4-3] wrapModelCall — 폴백 직접 구현");

  // 존재하지 않는 모델 이름 → invoke 시점에 provider 가 404 를 던집니다.
  const brokenModel = await initChatModel("anthropic:claude-이런-모델-없음");
  const goodModel = await initChatModel("anthropic:claude-sonnet-4-6");

  const fallbackMiddleware = createMiddleware({
    name: "FallbackMiddleware",

    wrapModelCall: async (request, handler) => {
      try {
        // ⚠️ await 를 반드시 붙여야 합니다.
        //    `return handler(...)` 로 쓰면 Promise 가 그대로 반환되고,
        //    거부(rejection)는 이 try/catch 를 그냥 통과해 버립니다.
        return await handler({ ...request, model: brokenModel });
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.log(`  [폴백] 1차 모델 실패 → 2차 모델로 재시도`);
        console.log(`         원인: ${message.slice(0, 70)}...`);

        return await handler({ ...request, model: goodModel });
      }
    },
  });

  const agent = createAgent({
    model: goodModel,
    tools: [],
    systemPrompt: "너는 간결한 비서다.",
    middleware: [fallbackMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "안녕이라고만 답해줘." }],
  });

  printMessages(result.messages.at(-1)!);
}

/* ===== [12-5] wrapToolCall — 도구 호출 가로채기 ===== */

/**
 * (1) 캐싱
 *
 * 같은 인자로 같은 도구를 또 부르면 실제 실행을 건너뜁니다.
 *
 * ⚠️ 핵심: ToolMessage 객체를 통째로 캐시하면 안 됩니다.
 *    ToolMessage 에는 tool_call_id 가 박혀 있는데, 이건 호출마다 새로 생깁니다.
 *    옛날 id 가 박힌 메시지를 돌려주면 provider 가 400 을 냅니다.
 *    → "내용"만 캐시하고 ToolMessage 는 매번 새로 만듭니다.
 */
const toolCache = new Map<string, string>();

const cachingMiddleware = createMiddleware({
  name: "CachingMiddleware",

  wrapToolCall: async (request, handler) => {
    const key = `${request.toolCall.name}:${JSON.stringify(request.toolCall.args)}`;

    const hit = toolCache.get(key);
    if (hit !== undefined) {
      console.log(`  [캐시 HIT] ${key} → 도구를 실행하지 않음`);
      // tool_call_id 는 반드시 "이번" 호출의 id 를 씁니다.
      return new ToolMessage({
        content: hit,
        tool_call_id: request.toolCall.id!,
        name: request.toolCall.name,
      });
    }

    console.log(`  [캐시 MISS] ${key}`);
    const result = await handler(request);

    // handler 는 ToolMessage 또는 Command 를 돌려줍니다.
    // Command 는 상태를 직접 조작하는 도구가 쓰는 것이라 캐시 대상이 아닙니다.
    if (ToolMessage.isInstance(result)) {
      toolCache.set(key, result.text);
    }
    return result;
  },
});

/**
 * (2) 권한 검사
 *
 * handler 를 아예 안 부르고 ToolMessage 를 직접 만들어 돌려주면
 * 도구는 실행되지 않고, 모델은 "거부당했다"는 사실을 텍스트로 읽습니다.
 *
 * throw 하면 안 됩니다 — 에이전트 전체가 죽습니다(12-5 함정 참고).
 */
const ALLOWED_TOOLS = new Set(["get_weather", "get_population"]);

const permissionMiddleware = createMiddleware({
  name: "PermissionMiddleware",

  wrapToolCall: (request, handler) => {
    if (!ALLOWED_TOOLS.has(request.toolCall.name)) {
      console.log(`  [권한 거부] ${request.toolCall.name} — handler 를 부르지 않고 차단`);

      return new ToolMessage({
        content: `권한 없음: '${request.toolCall.name}' 도구는 이 사용자에게 허용되지 않았습니다. 다른 방법을 찾거나 사용자에게 알리세요.`,
        tool_call_id: request.toolCall.id!,
        name: request.toolCall.name,
        status: "error",
      });
    }
    return handler(request);
  },
});

/**
 * (3) 감사 로그
 *
 * 누가 어떤 도구를 언제 어떤 인자로 불렀고 몇 ms 걸렸는지 남깁니다.
 * 실무에선 console.log 대신 DB/Datadog 로 보냅니다.
 */
const auditMiddleware = createMiddleware({
  name: "AuditMiddleware",

  wrapToolCall: async (request, handler) => {
    const startedAt = Date.now();
    try {
      const result = await handler(request);
      console.log(
        `  [감사] ok    ${request.toolCall.name} ${JSON.stringify(request.toolCall.args)} (${Date.now() - startedAt}ms)`,
      );
      return result;
    } catch (error) {
      console.log(`  [감사] FAIL  ${request.toolCall.name} (${Date.now() - startedAt}ms) — ${String(error)}`);
      throw error; // 삼키지 말고 그대로 올립니다. 감사자는 관찰만 합니다.
    }
  },
});

async function wrapToolCallDemo(): Promise<void> {
  printSection("[12-5] wrapToolCall — 캐싱 / 권한 / 감사");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather, getPopulation, deleteRecord],
    systemPrompt: "너는 도시 정보 비서다. 사용자가 요청하면 주저하지 말고 도구를 써라.",
    // 순서: 감사(바깥) → 권한(중간) → 캐시(안쪽) → 실제 도구
    // 권한을 캐시보다 바깥에 둬야 "거부된 호출이 캐시에 저장되는 일"이 없습니다.
    middleware: [auditMiddleware, permissionMiddleware, cachingMiddleware],
  });

  console.log("\n─ 1회차: 서울 날씨 ─");
  await agent.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });

  console.log("\n─ 2회차: 같은 질문 (캐시 HIT 기대) ─");
  await agent.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });

  console.log("\n─ 3회차: 금지된 도구 요청 ─");
  const blocked = await agent.invoke({
    messages: [{ role: "user", content: "레코드 abc-123 을 delete_record 도구로 삭제해줘." }],
  });
  printMessages(blocked.messages.at(-1)!);
}

/* ===== [12-6] 상태 확장 — stateSchema ===== */

/**
 * 미들웨어는 자기만의 상태 필드를 에이전트 상태에 추가할 수 있습니다.
 *
 * 방법 1: zod object — 리듀서 없음. 나중에 쓴 값이 이깁니다(last-write-wins).
 * 방법 2: StateSchema + ReducedValue — 리듀서 있음. 누적이 됩니다.
 *
 * 카운터/누적기는 반드시 방법 2 를 쓰세요. 방법 1 로 누적을 하면
 * 병렬 도구 호출 같은 상황에서 조용히 값이 덮어써집니다.
 */

const CounterState = new StateSchema({
  // 모델을 몇 번 불렀나 — 더하기 리듀서로 누적
  modelCallCount: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  // 마지막으로 쓴 모델 이름 — 덮어쓰기 리듀서
  lastModelName: new ReducedValue(z.string().default(""), {
    reducer: (_current: string, next: string) => next,
  }),
  // _ 로 시작하면 private — agent.invoke() 결과에 안 실립니다.
  _internalNote: new ReducedValue(z.string().default(""), {
    reducer: (_current: string, next: string) => next,
  }),
});

const counterMiddleware = createMiddleware({
  name: "CounterMiddleware",
  stateSchema: CounterState,

  afterModel: (state) => {
    // ⚠️ state.modelCallCount++ 처럼 state 를 직접 건드리면 안 됩니다.
    //    반드시 "업데이트 객체"를 반환해야 리듀서가 돕니다.
    console.log(`  [카운터] 지금까지 모델 호출 ${state.modelCallCount}회`);

    // 리듀서가 더하기니까 "증가분 1" 을 반환합니다. "새 총합" 이 아닙니다.
    return {
      modelCallCount: 1,
      lastModelName: "claude-sonnet-4-6",
      _internalNote: "이 필드는 결과에 안 보입니다",
    };
  },
});

async function stateSchemaDemo(): Promise<void> {
  printSection("[12-6] 상태 확장 — stateSchema");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather, getPopulation],
    systemPrompt: "너는 도시 정보 비서다.",
    middleware: [counterMiddleware],
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "서울과 제주의 날씨를 각각 알려줘." }],
  });

  printKV({
    "modelCallCount (public)": result.modelCallCount,
    "lastModelName (public)": result.lastModelName,
    "_internalNote (private)": (result as Record<string, unknown>)["_internalNote"] ?? "(결과에 없음 — 의도된 동작)",
  });
}

/* ===== [12-7] 흐름 제어 — jumpTo ===== */

/**
 * node 계열 훅(beforeAgent/beforeModel/afterModel/afterAgent)은
 * 반환 객체에 jumpTo 를 실어 루프의 다음 목적지를 바꿀 수 있습니다.
 *
 * jumpTo 로 갈 수 있는 곳은 딱 3개: "model" | "tools" | "end"
 *
 * ⚠️ jumpTo 를 쓰려면 훅을 { canJumpTo, hook } 형태로 선언해야 합니다.
 *    그냥 함수로 쓰고 jumpTo 만 반환하면 그래프에 엣지가 없어서 동작하지 않습니다.
 */

const BLOCKED_WORDS = ["비밀번호", "주민등록번호"];

const guardMiddleware = createMiddleware({
  name: "GuardMiddleware",

  beforeModel: {
    // 이 훅이 어디로 점프할 수 있는지 미리 선언 → 그래프에 엣지가 생깁니다.
    canJumpTo: ["end"],

    hook: (state) => {
      const lastHuman = [...state.messages].reverse().find((m) => m.getType() === "human");
      const text = lastHuman?.text ?? "";

      const hit = BLOCKED_WORDS.find((w) => text.includes(w));
      if (hit !== undefined) {
        console.log(`  [가드] 금칙어 '${hit}' 감지 → 모델을 부르지 않고 즉시 종료`);

        // 답변을 직접 만들어 넣고 end 로 점프합니다. 모델은 아예 안 돕니다.
        return {
          messages: [new AIMessage("죄송합니다. 해당 정보는 다룰 수 없습니다.")],
          jumpTo: "end" as const,
        };
      }
      return;
    },
  },
});

async function jumpToDemo(): Promise<void> {
  printSection("[12-7] 흐름 제어 — jumpTo");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    systemPrompt: "너는 비서다.",
    middleware: [guardMiddleware],
  });

  console.log("\n─ 정상 질문 (모델이 돎) ─");
  const ok = await agent.invoke({ messages: [{ role: "user", content: "서울 날씨 알려줘." }] });
  printMessages(ok.messages.at(-1)!);

  console.log("\n─ 금칙어 질문 (모델을 안 부르고 조기 종료) ─");
  const blocked = await agent.invoke({
    messages: [{ role: "user", content: "내 비밀번호가 뭐였지?" }],
  });
  printMessages(blocked.messages.at(-1)!);
}

/* ===== [12-8] 실전 — 비용 추적 + 토큰 예산 ===== */

/**
 * 여기까지 배운 걸 전부 씁니다.
 *
 * - stateSchema(ReducedValue)   : 토큰을 누적
 * - wrapModelCall               : usage_metadata 를 읽어 비용 계산, Command 로 상태 갱신
 * - beforeModel + jumpTo        : 예산 초과면 모델을 안 부르고 종료
 * - afterAgent                  : 최종 리포트
 */

// 100만 토큰당 USD. 값은 예시이므로 실제 단가는 provider 문서를 보세요.
const PRICE_PER_MTOK = {
  input: 3.0,
  output: 15.0,
} as const;

const BudgetState = new StateSchema({
  inputTokens: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  outputTokens: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  costUsd: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
  budgetExceeded: new ReducedValue(z.boolean().default(false), {
    reducer: (_current: boolean, next: boolean) => next,
  }),
});

const createCostTrackingMiddleware = (maxTotalTokens: number) =>
  createMiddleware({
    name: "CostTrackingMiddleware",
    stateSchema: BudgetState,

    // 1) 예산을 이미 넘겼으면 모델을 아예 부르지 않는다.
    beforeModel: {
      canJumpTo: ["end"],
      hook: (state) => {
        const used = state.inputTokens + state.outputTokens;

        if (used >= maxTotalTokens) {
          console.log(`  [예산] ${used} / ${maxTotalTokens} 토큰 — 초과. 모델 호출 중단.`);

          return {
            messages: [
              new AIMessage(
                `토큰 예산(${maxTotalTokens})을 소진해 작업을 중단했습니다. ` +
                  `지금까지 ${used} 토큰, 약 $${state.costUsd.toFixed(4)} 를 썼습니다.`,
              ),
            ],
            budgetExceeded: true,
            jumpTo: "end" as const,
          };
        }

        console.log(`  [예산] ${used} / ${maxTotalTokens} 토큰 사용 — 계속 진행`);
        return;
      },
    },

    // 2) 모델 호출을 감싸 실제 사용량을 읽는다.
    wrapModelCall: async (request, handler) => {
      const response = await handler(request);

      // usage_metadata 는 optional 입니다. 제공자가 안 주면 undefined 입니다.
      const usage = response.usage_metadata;
      if (usage === undefined) {
        console.log("  [비용] usage_metadata 없음 — 이번 호출은 집계 불가");
        return response;
      }

      const cost =
        (usage.input_tokens / 1_000_000) * PRICE_PER_MTOK.input +
        (usage.output_tokens / 1_000_000) * PRICE_PER_MTOK.output;

      console.log(
        `  [비용] in=${usage.input_tokens} out=${usage.output_tokens} → $${cost.toFixed(6)}`,
      );

      // wrapModelCall 에서 상태를 갱신하려면 Command 를 반환합니다.
      // handler() 를 이미 불렀으므로 프레임워크가 AI 메시지는 알아서 붙여 줍니다.
      // (handler 를 안 불렀다면 이 Command 는 답변 없는 빈 턴을 만듭니다.)
      return new Command({
        update: {
          inputTokens: usage.input_tokens,
          outputTokens: usage.output_tokens,
          costUsd: cost,
        },
      });
    },

    // 3) 끝날 때 리포트.
    afterAgent: (state) => {
      console.log(
        `\n  [최종] 입력 ${state.inputTokens} + 출력 ${state.outputTokens} = ` +
          `${state.inputTokens + state.outputTokens} 토큰 / $${state.costUsd.toFixed(6)}` +
          `${state.budgetExceeded ? "  ← 예산 초과로 중단됨" : ""}`,
      );
      return;
    },
  });

async function costTrackingDemo(): Promise<void> {
  printSection("[12-8] 실전 — 비용 추적 + 토큰 예산");

  // 일부러 아주 작게 잡아 중단이 실제로 걸리는 걸 봅니다.
  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather, getPopulation],
    systemPrompt: "너는 도시 정보 비서다. 도시마다 도구를 하나씩 따로 호출해라.",
    middleware: [createCostTrackingMiddleware(1500)],
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content: "서울, 부산, 제주의 날씨와 인구를 전부 조사해서 표로 정리해줘.",
      },
    ],
  });

  console.log("\n최종 답변:");
  printMessages(result.messages.at(-1)!);

  printKV({
    "입력 토큰": result.inputTokens,
    "출력 토큰": result.outputTokens,
    "비용(USD)": `$${result.costUsd.toFixed(6)}`,
    "예산 초과": result.budgetExceeded,
  });
}

/* ===== [12-9] 함정 확인 — 미들웨어에서 던진 예외 ===== */

/**
 * 미들웨어가 throw 하면 에이전트 전체가 죽습니다.
 * 다만 던져진 에러는 MiddlewareError 로 감싸져서 어느 미들웨어가 범인인지 알려줍니다.
 */
async function errorDemo(): Promise<void> {
  printSection("[12-9] 함정 — 미들웨어 예외는 에이전트를 죽인다");

  const throwingMiddleware = createMiddleware({
    name: "ThrowingMiddleware",
    beforeModel: () => {
      throw new Error("여기서 던지면 어떻게 될까요?");
    },
  });

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    middleware: [throwingMiddleware],
  });

  try {
    await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
    console.log("여기는 실행되지 않습니다.");
  } catch (error) {
    console.log(`MiddlewareError.isInstance(error) = ${MiddlewareError.isInstance(error)}`);
    console.log(`error.message                     = ${(error as Error).message}`);
    console.log(`error.cause                       = ${String((error as Error).cause)}`);
    console.log("\n→ invoke() 가 통째로 실패했습니다. 부분 결과도, 답변도 없습니다.");
    console.log("  미들웨어에서 '거부'를 표현하고 싶다면 throw 가 아니라");
    console.log("  ToolMessage 를 돌려주거나(12-5) jumpTo:'end' 를 쓰세요(12-7).");
  }
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await hookOrder(); // [12-1]
  await firstMiddleware(); // [12-2]
  await beforeModelDemo(); // [12-3]
  await dynamicPromptDemo(); // [12-4-1]
  await dynamicModelDemo(); // [12-4-2]
  await fallbackDemo(); // [12-4-3]
  await wrapToolCallDemo(); // [12-5]
  await stateSchemaDemo(); // [12-6]
  await jumpToDemo(); // [12-7]
  await costTrackingDemo(); // [12-8]
  await errorDemo(); // [12-9]
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
