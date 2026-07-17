/**
 * Step 11 — 내장 미들웨어
 * 실행: npx tsx docs/reference/langchain/step-11-middleware-builtin/practice.ts
 *
 * 본문 11-1 ~ 11-8 의 예제를 순서대로 담았습니다.
 * 블록 주석의 [11-x] 번호가 본문 소제목과 1:1 대응합니다.
 *
 * 주의: LLM 응답은 비결정적입니다. 출력은 매번 다릅니다.
 *       이 파일이 보여주려는 건 "내용"이 아니라 "순서와 구조"입니다.
 */
import "dotenv/config";

import {
  createAgent,
  createMiddleware,
  tool,
  summarizationMiddleware,
  contextEditingMiddleware,
  ClearToolUsesEdit,
  todoListMiddleware,
  modelRetryMiddleware,
  toolRetryMiddleware,
  modelFallbackMiddleware,
  modelCallLimitMiddleware,
  toolCallLimitMiddleware,
  piiMiddleware,
  humanInTheLoopMiddleware,
  llmToolSelectorMiddleware,
  dynamicSystemPromptMiddleware,
} from "langchain";
import { MemorySaver, Command } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printMessages, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

/* ===== 공용 도구 ===== */

// 조회 전용 도구 — 여러 번 불러도 안전합니다(멱등).
// 그래서 뒤에서 toolRetryMiddleware 의 화이트리스트에 넣을 수 있습니다.
const getWeather = tool(
  async ({ city }) => JSON.stringify({ city, temp: 3, condition: "맑음" }),
  {
    name: "get_weather",
    description: "도시의 현재 날씨를 조회합니다. 조회 전용이라 여러 번 불러도 안전합니다.",
    schema: z.object({ city: z.string().describe("도시 이름") }),
  },
);

/* ===== [11-1] 미들웨어가 왜 필요한가 — v0 의 한계 ===== */

async function step_11_1(): Promise<void> {
  printSection("[11-1] 미들웨어 없이 vs 미들웨어와 함께");

  // (A) 미들웨어 없는 기본 에이전트 — Step 08 에서 만든 것과 같습니다.
  //     루프 안쪽에 손댈 방법이 없습니다.
  const plain = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "간결하게 답하세요.",
  });

  const r1 = await plain.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘" }],
  });
  console.log("\n(A) 미들웨어 없음:");
  printMessages(r1.messages.slice(-1));

  // (B) 미들웨어 2개를 얹었습니다.
  //     루프를 다시 짜지 않았고, createAgent 호출은 그대로입니다.
  //     배열에 넣고 빼는 것만으로 동작이 바뀝니다 — 이게 v0 대비 핵심 차이입니다.
  const withMw = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "간결하게 답하세요.",
    middleware: [
      piiMiddleware("email", { strategy: "redact" }),
      modelRetryMiddleware({ maxRetries: 2 }),
    ],
  });

  const r2 = await withMw.invoke({
    messages: [{ role: "user", content: "내 이메일 kim@example.com 을 기억해 줘" }],
  });
  console.log("\n(B) piiMiddleware + modelRetryMiddleware:");
  printMessages(r2.messages);
  console.log("\n→ 사용자 메시지의 이메일이 [REDACTED_EMAIL] 로 바뀌어 모델에게 갔습니다.");
}

/* ===== [11-2] 미들웨어 실행 순서와 생명주기 ===== */

/**
 * 자기 이름과 훅 이름을 찍기만 하는 미들웨어.
 * 이 스텝의 모든 순서 규칙을 눈으로 확인하는 도구입니다.
 */
function traceMiddleware(name: string) {
  return createMiddleware({
    name: `Trace-${name}`,
    beforeAgent: () => {
      console.log(`  beforeAgent   ${name}`);
      return;
    },
    beforeModel: () => {
      console.log(`  beforeModel   ${name}`);
      return;
    },
    wrapModelCall: async (request, handler) => {
      console.log(`  wrapModelCall ${name}  ↓ 들어감`);
      const result = await handler(request);
      console.log(`  wrapModelCall ${name}  ↑ 나옴`);
      return result;
    },
    afterModel: () => {
      console.log(`  afterModel    ${name}`);
      return;
    },
    afterAgent: () => {
      console.log(`  afterAgent    ${name}`);
      return;
    },
  });
}

async function step_11_2(): Promise<void> {
  printSection("[11-2] 실행 순서 — before 는 순서대로, after 는 역순, wrap 은 중첩");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    middleware: [traceMiddleware("A"), traceMiddleware("B"), traceMiddleware("C")],
  });

  console.log("\n실행 로그:");
  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });

  console.log(`
→ 예상했던 대로 나왔나요? 규칙은 하나입니다: "배열 앞쪽 = 바깥 껍질".

  beforeAgent    A → B → C     (순서대로)
  beforeModel    A → B → C     (순서대로)
  wrapModelCall  A( B( C( 모델 ) ) )   (중첩: 앞이 바깥)
  afterModel     C → B → A     (역순!)
  afterAgent     C → B → A     (역순!)

  afterModel 이 역순이라는 것이 11-7 의 모든 함정의 뿌리입니다.
  [마스킹, 로깅] 이라고 쓰면 afterModel 에서는 로깅이 먼저 돌아
  로그에 원본이 남습니다.
`);
}

/* ===== [11-3] 내장 미들웨어 카탈로그 ===== */

async function step_11_3(): Promise<void> {
  printSection("[11-3] 카탈로그 — 최소 형태 모음 (생성만, 실행 안 함)");

  // 이 블록은 "이렇게 생겼다"를 보여주는 게 목적이라 만들기만 합니다.
  // 전부 import { ... } from "langchain" 한 줄로 가져옵니다.

  const catalog = {
    // 1. 요약 — 4000 토큰 넘으면 압축, 최근 20개 보존
    summarization: summarizationMiddleware({
      model: CHEAP_MODEL,
      trigger: { tokens: 4000 },
      keep: { messages: 20 },
    }),

    // 2. 컨텍스트 편집 — 오래된 도구 결과를 "[cleared]" 로
    contextEditing: contextEditingMiddleware({
      edits: [new ClearToolUsesEdit({ trigger: { tokens: 100000 }, keep: { messages: 3 } })],
    }),

    // 3. 할 일 목록 — write_todos 도구가 추가된다
    todoList: todoListMiddleware(),

    // 4. 모델 재시도 — 1s → 2s → 4s
    modelRetry: modelRetryMiddleware({
      maxRetries: 3,
      initialDelayMs: 1000,
      backoffFactor: 2.0,
      jitter: true,
    }),

    // 5. 도구 재시도 — 특정 도구에만! (11-5 의 중복 결제 함정)
    toolRetry: toolRetryMiddleware({ maxRetries: 2, tools: ["get_weather"] }),

    // 6. 모델 폴백 — ⚠️ 가변 인자다. 배열이 아니다!
    modelFallback: modelFallbackMiddleware("openai:gpt-5.5", CHEAP_MODEL),

    // 7. 모델 호출 횟수 상한 — exitBehavior 기본값 "end"
    modelCallLimit: modelCallLimitMiddleware({ threadLimit: 20, runLimit: 8, exitBehavior: "end" }),

    // 8. 도구 호출 횟수 상한 — exitBehavior 기본값 "continue"
    toolCallLimit: toolCallLimitMiddleware({ toolName: "get_weather", runLimit: 3 }),

    // 9. PII — ⚠️ 첫 인자가 문자열이다. 객체 하나가 아니다!
    pii: piiMiddleware("credit_card", { strategy: "mask", applyToInput: true }),

    // 10. 도구 선별 — 도구가 50개일 때 LLM 이 추린다
    llmToolSelector: llmToolSelectorMiddleware({
      model: CHEAP_MODEL,
      maxTools: 3,
      alwaysInclude: ["get_weather"],
    }),

    // 11. 동적 시스템 프롬프트 — 매 모델 호출마다 새로 만든다
    dynamicPrompt: dynamicSystemPromptMiddleware(
      (state) => `현재 메시지 ${state.messages.length}개. 간결히 답하라.`,
    ),
  };

  console.log(`\n생성된 미들웨어 ${Object.keys(catalog).length}개:`);
  for (const key of Object.keys(catalog)) {
    console.log(`  - ${key}`);
  }

  console.log(`
→ 시그니처 예외 2개만 기억하세요:
    modelFallbackMiddleware(a, b)        ← 가변 인자 (배열 X)
    piiMiddleware("email", { ... })      ← 첫 인자가 문자열 (객체 X)
  나머지는 전부 옵션 객체 하나입니다.
`);
}

/* ===== [11-4] 요약 미들웨어 심화 — trigger 의 AND / OR ===== */

async function step_11_4(): Promise<void> {
  printSection("[11-4] 요약 — trigger 객체는 AND, 배열은 OR");

  // 같은 대화를 두 설정으로 돌립니다.
  // 메시지 수는 적고(2~4개) 토큰은 넘는 상황을 만듭니다.
  const longText = "긴 문서입니다. ".repeat(200); // 토큰은 많지만 메시지는 1개

  // (A) 객체 하나 = AND. tokens 200 이상 "그리고" messages 100 이상.
  //     메시지가 100개가 안 되므로 절대 발동하지 않습니다.
  const andAgent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: { tokens: 200, messages: 100 }, // ← AND!
        keep: { messages: 2 },
      }),
    ],
  });

  const rA = await andAgent.invoke({
    messages: [{ role: "user", content: `${longText}\n\n요약하지 말고 "네"라고만 답해.` }],
  });
  console.log(`\n(A) trigger: { tokens: 200, messages: 100 }  ← AND`);
  console.log(`    결과 메시지 수: ${rA.messages.length}`);
  console.log(`    → 요약 발동 안 함. messages: 100 조건을 못 넘겼기 때문입니다.`);
  console.log(`    → 토큰이 아무리 많아도 안 됩니다. 이게 함정입니다.`);

  // (B) 배열 = OR. 둘 중 하나만 넘어도 발동.
  const orAgent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 200 }, { messages: 100 }], // ← OR!
        keep: { messages: 2 },
      }),
    ],
  });

  const rB = await orAgent.invoke({
    messages: [{ role: "user", content: `${longText}\n\n요약하지 말고 "네"라고만 답해.` }],
  });
  console.log(`\n(B) trigger: [{ tokens: 200 }, { messages: 100 }]  ← OR`);
  console.log(`    결과 메시지 수: ${rB.messages.length}`);
  console.log(`    → tokens 조건만으로 발동합니다.`);

  console.log(`
→ 같은 대화, 같은 임계치인데 발동 여부가 갈립니다.
  "4000 토큰이거나 10개 메시지면 요약"을 원한다면 반드시 배열로 쓰세요.
  객체 하나로 쓰면 "둘 다"여야 발동하고,
  발동 안 하면 컨텍스트 초과 에러가 그대로 터집니다.
`);
}

/* ===== [11-4b] 요약 대신 잘라내기 — contextEditingMiddleware ===== */

async function step_11_4b(): Promise<void> {
  printSection("[11-4b] contextEditingMiddleware — 도구 결과만 잘라낸다");

  const agent = createAgent({
    model: MODEL,
    tools: [getWeather],
    systemPrompt: "날씨를 물으면 도구를 쓰세요.",
    middleware: [
      contextEditingMiddleware({
        edits: [
          new ClearToolUsesEdit({
            // 실습이라 아주 낮게 잡습니다. 실제로는 { fraction: 0.7 } 같은 값을 씁니다.
            trigger: { tokens: 1 },
            keep: { messages: 0 }, // 전부 지워서 효과를 눈에 보이게
            clearToolInputs: false, // ← 도구 "인자"는 남긴다 (기본값). 중요합니다!
            placeholder: "[cleared]",
          }),
        ],
      }),
    ],
  });

  const r = await agent.invoke({
    messages: [{ role: "user", content: "서울 날씨 알려줘. 그리고 부산도." }],
  });

  printMessages(r.messages);
  console.log(`
→ ToolMessage 의 내용이 "[cleared]" 로 바뀌었는데
  AIMessage 의 tool_calls(= 어떤 도구를 어떤 인자로 불렀는지)는 그대로 남아 있습니다.

  이게 clearToolInputs: false 가 기본값인 이유입니다.
  "이미 불렀다"는 사실이 남아 있어야 모델이 같은 도구를 다시 안 부릅니다.
  요약 미들웨어는 이 구조를 자연어로 뭉개버려서 재호출을 유발합니다 — 본문 11-4 참조.

  그리고 이건 LLM 을 안 부릅니다. 공짜이고 즉시입니다.
`);
}

/* ===== [11-5] 재시도와 폴백 ===== */

// 처음 두 번은 실패하고 세 번째에 성공하는 도구.
// 모듈 스코프 카운터로 호출 횟수를 셉니다.
let flakyCallCount = 0;

const flakyTool = tool(
  async ({ query }) => {
    flakyCallCount += 1;
    console.log(`    flakySearch 호출 #${flakyCallCount}`);
    if (flakyCallCount < 3) {
      throw new Error("ETIMEDOUT: 일시적 네트워크 오류");
    }
    return JSON.stringify({ query, results: ["결과1", "결과2"] });
  },
  {
    name: "flaky_search",
    description: "웹을 검색합니다. 조회 전용이라 여러 번 불러도 안전합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

async function step_11_5(): Promise<void> {
  printSection("[11-5] toolRetryMiddleware — 지수 백오프 재시도");

  flakyCallCount = 0;

  const agent = createAgent({
    model: MODEL,
    tools: [flakyTool],
    systemPrompt: "검색이 필요하면 flaky_search 를 쓰세요.",
    middleware: [
      toolRetryMiddleware({
        maxRetries: 3, // ← 최초 1회 + 재시도 3회 = 최대 4회
        initialDelayMs: 100, // 실습이라 짧게. 실제로는 1000 정도.
        backoffFactor: 2.0,
        jitter: true,
        // ETIMEDOUT 만 재시도합니다. 다른 에러는 바로 포기.
        retryOn: (error: Error) => error.message.includes("ETIMEDOUT"),
        // 다 실패하면 에러를 ToolMessage 로 만들어 모델에게 줍니다 (기본값).
        // 에이전트가 죽지 않고 모델이 보고 대처합니다.
        onFailure: "continue",
        // ⚠️ 가장 중요한 옵션: 재시도해도 안전한 도구만 지정합니다.
        //    이걸 빼면 결제/발송 도구까지 재시도되어 중복 실행됩니다.
        tools: ["flaky_search"],
      }),
    ],
  });

  const r = await agent.invoke({
    messages: [{ role: "user", content: "LangChain 미들웨어를 검색해줘" }],
  });

  console.log(`\n최종 flakySearch 호출 횟수: ${flakyCallCount}`);
  console.log(`→ maxRetries: 3 인데 호출은 3번입니다 (실패2 + 성공1).`);
  console.log(`  만약 계속 실패했다면 4번이 됐을 겁니다 — 최초 1회 + 재시도 3회.`);
  console.log(`  "maxRetries = 총 시도 횟수"가 아닙니다. 하나 어긋납니다.\n`);
  printMessages(r.messages.slice(-1));

  console.log(`
⚠️ 중복 결제 함정:
  toolRetryMiddleware 는 도구가 무슨 일을 하는지 모릅니다.
  "실패했다"와 "실패한 것처럼 보인다"는 다릅니다.

    1. charge_payment 호출
    2. 서버가 결제를 성공적으로 처리
    3. 응답 보내다 타임아웃 ← 클라이언트는 실패로 인식
    4. 재시도 → 5만원이 두 번 빠져나감

  방어: tools 옵션으로 읽기 도구만 화이트리스트에 넣으세요.
`);
}

async function step_11_5b(): Promise<void> {
  printSection("[11-5b] 재시도 + 폴백 조합 — 순서가 호출 수를 바꾼다");

  // 둘 다 wrapModelCall 이고 "앞이 바깥"이므로 fallback( retry( 모델 ) ) 로 중첩됩니다.
  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      // 바깥: 재시도를 다 소진해도 안 되면 여기서 잡아 다른 모델로
      modelFallbackMiddleware(CHEAP_MODEL),
      // 안쪽: 먼저 같은 모델에 매달려 본다
      modelRetryMiddleware({ maxRetries: 2, initialDelayMs: 500, jitter: true }),
      // 폭주 상한
      modelCallLimitMiddleware({ runLimit: 5, exitBehavior: "end" }),
    ],
  });

  const r = await agent.invoke({ messages: [{ role: "user", content: "안녕하세요" }] });
  printMessages(r.messages.slice(-1));

  console.log(`
→ [fallback, retry] 순서의 동작:
    Claude 시도 → 실패 → 대기 → 재시도 → 실패 → 대기 → 재시도 → 실패
      → (retry 소진, fallback 이 잡는다) → Haiku 시도 → 성공

  뒤집어서 [retry, fallback] 로 쓰면 retry( fallback( 모델 ) ) 이 되어
    "Claude 실패 → 즉시 Haiku → 그것도 실패 → 둘 다 다시 → ..."
  가 됩니다. 재시도 1회가 두 모델을 모두 다시 부르므로 호출 수가 곱해집니다.
  429 상황에서 이러면 두 제공자 모두에서 rate limit 을 맞습니다.

  실무 기본: [fallback, retry] — fallback 이 바깥.
  폴백 모델은 "다른 제공자"로 고르세요. 같은 제공자는 통째로 죽으면 같이 죽습니다.
`);
}

/* ===== [11-6] 가드레일 — PII ===== */

// 결과 안에 이메일을 담아 돌려주는 도구.
// PII 는 대개 사용자 입력이 아니라 "DB" 에서 들어온다는 걸 보여줍니다.
const getUserTool = tool(
  async ({ userId }) =>
    JSON.stringify({ userId, name: "김민수", email: "kim.minsu@example.com" }),
  {
    name: "get_user",
    description: "사용자 ID로 사용자 정보를 조회합니다.",
    schema: z.object({ userId: z.number().describe("사용자 ID") }),
  },
);

async function step_11_6(): Promise<void> {
  printSection("[11-6] piiMiddleware — 기본값은 '입력만' 검사한다");

  // (A) 기본값. applyToInput 만 true.
  const leaky = createAgent({
    model: MODEL,
    tools: [getUserTool],
    systemPrompt: "사용자 정보를 물으면 get_user 를 쓰고, 조회한 내용을 그대로 알려주세요.",
    middleware: [
      // applyToInput: true (기본), applyToOutput: false, applyToToolResults: false
      piiMiddleware("email", { strategy: "redact" }),
    ],
  });

  const rA = await leaky.invoke({
    messages: [{ role: "user", content: "42번 사용자 정보 알려줘" }],
  });
  console.log("\n(A) 기본값 — applyToInput 만:");
  printMessages(rA.messages);
  console.log(`
→ 사용자 입력에는 PII 가 없었습니다(통과).
  그런데 ToolMessage 에 DB 에서 온 생 이메일이 들어왔고, 검사하지 않았습니다.
  모델이 그걸 읽고 최종 응답에 그대로 뱉었고, 그것도 검사하지 않았습니다.
  "PII 미들웨어를 걸었는데 이메일이 나오네?" 의 정체입니다.
`);

  // (B) 세 방향 다 켜기.
  const guarded = createAgent({
    model: MODEL,
    tools: [getUserTool],
    systemPrompt: "사용자 정보를 물으면 get_user 를 쓰고, 조회한 내용을 그대로 알려주세요.",
    middleware: [
      piiMiddleware("email", {
        strategy: "redact",
        applyToInput: true,
        applyToOutput: true, // AI 응답도 검사
        applyToToolResults: true, // 도구 결과도 검사 ← 이게 핵심
      }),
    ],
  });

  const rB = await guarded.invoke({
    messages: [{ role: "user", content: "42번 사용자 정보 알려줘" }],
  });
  console.log("\n(B) 세 방향 다 켬:");
  printMessages(rB.messages);
  console.log(`
→ ToolMessage 의 이메일이 [REDACTED_EMAIL] 로 바뀌었습니다.

  PII 는 대개 사용자가 타이핑하는 게 아니라 여러분의 DB 에서 들어옵니다.
  applyToToolResults: true 를 켜지 않으면 입구만 지키고 뒷문은 열어둔 셈입니다.
`);
}

async function step_11_6b(): Promise<void> {
  printSection("[11-6b] 전략(strategy) 4가지와 커스텀 detector");

  // 전략별 출력 형식을 한 번에 보여줍니다.
  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "받은 내용을 그대로 따라 적으세요. 다른 말은 하지 마세요.",
    middleware: [
      piiMiddleware("email", { strategy: "redact" }), // [REDACTED_EMAIL]
      piiMiddleware("credit_card", { strategy: "mask" }), // ****-****-****-1234
      piiMiddleware("ip", { strategy: "hash" }), // <ip_hash:a1b2c3d4>
      // 커스텀 PII — 내장 5개(email/credit_card/ip/mac_address/url)에 없는 것.
      // piiType 이 내장이 아닌데 detector 를 안 주면 에러를 던집니다.
      piiMiddleware("employee_id", {
        detector: (content: string) => {
          const matches: { text: string; start: number; end: number }[] = [];
          for (const m of content.matchAll(/EMP-\d{6}/g)) {
            if (m.index === undefined) continue;
            matches.push({ text: m[0], start: m.index, end: m.index + m[0].length });
          }
          return matches;
        },
        strategy: "hash", // 결정적! 같은 사번은 항상 같은 해시.
      }),
    ],
  });

  const r = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "이메일 kim@example.com, 카드 5105-1051-0510-5100, IP 192.168.1.1, " +
          "사번 EMP-123456 과 EMP-123456 (같은 사번 두 번).",
      },
    ],
  });

  printMessages(r.messages);
  console.log(`
→ 미들웨어 이름은 piiType 에서 만들어집니다:
    piiMiddleware("email")       → "PIIMiddleware[email]"
    piiMiddleware("credit_card") → "PIIMiddleware[credit_card]"
  타입이 다르므로 위처럼 4개를 나란히 넣어도 충돌하지 않습니다.
  같은 타입을 두 번 넣으면 createAgent 가 던집니다 — 아래 [11-6c] 참조.

→ 전략별 출력 형식:
    redact  → [REDACTED_EMAIL]        신원 보존 X. 일반 컴플라이언스.
    mask    → ****-****-****-5100     신원 보존 X. 사람이 읽는 UI.
    hash    → <ip_hash:a1b2c3d4>      신원 보존 O (가명). 분석/디버깅.
    block   → PIIDetectionError 예외   아예 안 받겠다.

  hash 만 결정적입니다. 같은 사번 EMP-123456 이 두 번 나왔다면 해시도 같습니다.
  모델은 "이 둘이 동일인"은 알되 실제 값은 모릅니다.

⚠️ 마스킹된 값을 도구가 받으면 도구가 깨집니다:
    "kim@example.com 으로 메일 보내줘"
      → "[REDACTED_EMAIL] 으로 메일 보내줘"
      → send_email({ to: "[REDACTED_EMAIL]" })  → 발송 실패

  모델이 못 보는 값은 도구에도 못 넘깁니다.
  도구가 실제 값이 필요하면 PII 를 마스킹하면 안 됩니다.
  대신 도구가 ID 로 동작하게 설계하세요:
    send_email({ user_id: 42 }) → 서버가 이메일을 조회해 발송.
  모델은 이메일을 영영 안 봅니다. 이게 진짜 방어입니다.
`);
}

/* ===== [11-6c] 미들웨어 이름 충돌 — 공식 문서 예제가 죽는다 ===== */

function step_11_6c(): void {
  printSection("[11-6c] 같은 미들웨어를 두 번 넣으면 createAgent 가 던진다");

  // 미들웨어 이름을 직접 확인해 봅니다.
  console.log("\n미들웨어 이름:");
  console.log(`  piiMiddleware("email")       → ${piiMiddleware("email", { strategy: "redact" }).name}`);
  console.log(
    `  piiMiddleware("credit_card") → ${piiMiddleware("credit_card", { strategy: "mask" }).name}`,
  );
  console.log(
    `  summarizationMiddleware      → ${summarizationMiddleware({ model: CHEAP_MODEL, trigger: { tokens: 10 } }).name}`,
  );

  // (A) ❌ 공식 가드레일 문서의 "Layered Guardrails" 예제 형태.
  //     입력용과 출력용을 따로 쌓습니다. 이름이 같아 충돌합니다.
  console.log("\n(A) 같은 타입(email)을 두 번 — 문서에 나오는 형태:");
  try {
    createAgent({
      model: MODEL,
      tools: [],
      middleware: [
        piiMiddleware("email", { strategy: "redact", applyToInput: true }),
        piiMiddleware("email", { strategy: "redact", applyToOutput: true }),
      ],
    });
    console.log("    성공 (이 버전에서는 충돌하지 않습니다)");
  } catch (error) {
    console.log(`    💥 ${(error as Error).message}`);
  }

  // (B) ✅ 하나로 합칩니다.
  //     적용 범위는 "옵션" 으로 지정하는 것이지 미들웨어를 쌓는 게 아닙니다.
  console.log("\n(B) 하나로 합치기:");
  createAgent({
    model: MODEL,
    tools: [],
    middleware: [
      piiMiddleware("email", {
        strategy: "redact",
        applyToInput: true,
        applyToOutput: true,
        applyToToolResults: true,
      }),
    ],
  });
  console.log("    성공");

  console.log(`
→ 미들웨어의 name 은 한 에이전트 안에서 유일해야 합니다.
  중복이면 createAgent 가 즉시 던집니다:
    Error: Middleware PIIMiddleware[email] is defined multiple times

  이름이 piiType 에서 만들어지므로 타입이 다르면(email/credit_card/ip) 여러 개 넣어도 됩니다.
  충돌하는 건 "같은 타입을 두 번" 넣을 때뿐입니다.

  반면 summarizationMiddleware 는 이름이 항상 "SummarizationMiddleware" 라
  두 번 넣는 것 자체가 불가능합니다.

  💡 그나마 이건 시끄럽게 죽는 함정이라 다행입니다.
     이 스텝의 다른 함정들과 달리 배포 전에 잡힙니다.
`);
}

/* ===== [11-7] 미들웨어 조합 — 순서가 결과를 바꾼다 ===== */

/**
 * beforeModel 시점에 "모델에게 무엇이 전달되는지" 엿보는 미들웨어.
 * 11-7 의 PII 유출을 눈으로 확인하는 도구입니다.
 */
function peekMiddleware(label: string) {
  return createMiddleware({
    name: `Peek-${label}`,
    beforeModel: (state) => {
      const last = state.messages.at(-1);
      const text = last === undefined ? "(없음)" : last.text.slice(0, 80);
      console.log(`  [${label}] 이 시점의 마지막 메시지: ${text}`);
      return;
    },
  });
}

async function step_11_7(): Promise<void> {
  printSection("[11-7] 조합 — PII 를 요약보다 뒤에 두면 유출된다");

  const secret = "제 이메일은 secret.user@example.com 입니다. 기억해 주세요.";

  // (A) PII 가 바깥 (배열 앞) — 올바른 순서
  //     beforeModel 이 순서대로 도니: pii → peek → summarization
  //     요약 모델은 마스킹된 텍스트를 봅니다.
  console.log("\n(A) [pii, peek, summarization] — PII 가 먼저:");
  const good = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      piiMiddleware("email", { strategy: "redact" }),
      peekMiddleware("pii 이후"),
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 50 }], // 낮게 잡아 발동시킴
        keep: { messages: 2 },
      }),
    ],
  });
  await good.invoke({ messages: [{ role: "user", content: secret }] });

  // (B) 요약이 바깥 (배열 앞) — 잘못된 순서
  //     beforeModel 순서: summarization → peek → pii
  //     요약 모델이 생 PII 를 먼저 봅니다!
  console.log("\n(B) [summarization, peek, pii] — 요약이 먼저:");
  const bad = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 50 }],
        keep: { messages: 2 },
      }),
      peekMiddleware("summarization 이후"),
      piiMiddleware("email", { strategy: "redact" }),
    ],
  });
  await bad.invoke({ messages: [{ role: "user", content: secret }] });

  console.log(`
→ (B) 에서 요약 미들웨어가 먼저 돌았습니다.
  요약 미들웨어는 별도 모델(Haiku)을 부릅니다 = 외부 전송입니다.
  그 호출에 마스킹 안 된 secret.user@example.com 이 실려 갔습니다.

  최종적으로 주 모델에게 갈 때는 마스킹되니 "결과만 보면 멀쩡"합니다.
  마스킹이 잘 되고 있다고 믿게 되죠.
  그런데 요약 모델 제공자의 로그에는 원본이 남았습니다.

  규칙: "데이터를 외부로 내보내는 미들웨어보다, 정화하는 미들웨어가 먼저"
  같은 이유로 llmToolSelectorMiddleware(별도 LLM), openAIModerationMiddleware(외부 API)도
  PII 뒤에 와야 합니다.

  이 순서가 틀려도 에러는 절대 안 납니다.
  컴플라이언스 감사 때 다른 회사 로그에서 발견됩니다.
`);
}

/* ===== [11-8] 종합 — 프로덕션 에이전트 ===== */

const getOrder = tool(
  async ({ orderId }) =>
    JSON.stringify({ orderId, status: "배송중", email: "customer@example.com", amount: 50000 }),
  {
    name: "get_order",
    description: "주문 ID로 주문 상태를 조회합니다. 조회 전용이라 여러 번 불러도 안전합니다.",
    schema: z.object({ orderId: z.number().describe("주문 번호") }),
  },
);

const refundOrder = tool(
  async ({ orderId }) => `주문 ${orderId} 환불 완료`,
  {
    name: "refund_order",
    description: "주문을 환불합니다. 실제로 돈이 나가므로 되돌릴 수 없습니다.",
    schema: z.object({ orderId: z.number().describe("주문 번호") }),
  },
);

async function step_11_8(): Promise<void> {
  printSection("[11-8] 종합 — 프로덕션 에이전트");

  const agent = createAgent({
    model: MODEL,
    tools: [getOrder, refundOrder],
    systemPrompt: "당신은 고객 지원 상담원입니다. 환불은 반드시 주문 상태를 확인한 뒤에만 진행하세요.",
    middleware: [
      // 1. 정화가 가장 바깥 — 요약 LLM 이 생 PII 를 못 보게 (11-7)
      piiMiddleware("email", {
        strategy: "redact",
        applyToInput: true,
        applyToOutput: true,
        applyToToolResults: true, // get_order 가 뱉는 이메일도 막는다 (11-6)
      }),

      // 2. 폭주 상한
      modelCallLimitMiddleware({ runLimit: 10, exitBehavior: "end" }),

      // 3. 장애 대응 — fallback 바깥, retry 안쪽 (11-5)
      modelFallbackMiddleware(CHEAP_MODEL),
      modelRetryMiddleware({ maxRetries: 2, initialDelayMs: 500, jitter: true }),

      // 4. 컨텍스트 — 싼 것(편집) 먼저, 그래도 넘치면 요약
      contextEditingMiddleware({
        edits: [new ClearToolUsesEdit({ trigger: { fraction: 0.7 }, keep: { messages: 3 } })],
      }),
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 8000 }, { messages: 40 }], // 배열 = OR (11-4)
        keep: { messages: 20 },
      }),

      // 5. 위험한 도구는 사람 승인
      humanInTheLoopMiddleware({
        interruptOn: {
          refund_order: {
            allowedDecisions: ["approve", "reject"], // 수정은 불허
            description: "환불을 승인하시겠습니까? 되돌릴 수 없습니다.",
          },
          // get_order 는 항목이 없으므로 자동 승인됩니다.
          // 명시하고 싶다면 get_order: false 라고 적어도 같습니다.
        },
      }),

      // 6. ⚠️ 재시도는 읽기 도구에만! refund_order 제외 (11-5)
      toolRetryMiddleware({ maxRetries: 2, tools: ["get_order"] }),

      // 7. 계획 도구
      todoListMiddleware(),
    ],
    checkpointer: new MemorySaver(), // HITL 재개와 threadLimit 에 필수 (11-6)
  });

  const config = { configurable: { thread_id: "step-11-demo" } };

  // 1차 실행 — refund_order 앞에서 인터럽트가 걸립니다.
  console.log("\n1차 invoke — 환불 요청:");
  const first = await agent.invoke(
    { messages: [{ role: "user", content: "3번 주문 확인하고 환불해줘" }] },
    config,
  );
  printMessages(first.messages.slice(-2));

  console.log("\n→ refund_order 앞에서 멈췄습니다(인터럽트).");
  console.log("  get_order 는 interruptOn 에 항목이 없어 자동 승인됐습니다.");

  // 2차 실행 — Command 로 재개합니다.
  console.log("\n2차 invoke — 승인하고 재개:");
  const resumed = await agent.invoke(
    new Command({ resume: { decisions: [{ type: "approve" }] } }),
    config, // ← 같은 thread_id 여야 합니다
  );
  printMessages(resumed.messages.slice(-2));

  console.log(`
→ 이 설정에 담긴 판단:
    piiMiddleware 를 맨 위에        요약 LLM 이 생 PII 를 못 보게 (11-7)
    applyToToolResults: true        PII 는 DB 에서 온다 (11-6)
    trigger 를 배열로               OR 의미. 객체면 AND (11-4)
    toolRetry 에 tools: ["get_order"]  refund_order 재시도 = 중복 환불 (11-5)
    modelFallback 이 modelRetry 위   한 모델에 매달려 보고 갈아탄다 (11-5)
    checkpointer                    HITL 재개에 필수 (11-6)

  ⚠️ 만약 toolRetryMiddleware 에서 tools 를 빼면:
     사람이 "승인"을 한 번 눌렀는데 refund_order 는 최대 3번 실행됩니다.
     [hitl, toolRetry] 순서라 hitl( retry( 도구 ) ) 로 중첩되기 때문입니다.
     감사 로그에는 승인 1건만 남습니다. 깨끗해 보입니다.

  💡 이걸 처음부터 다 넣지 마세요.
     createAgent 만으로 시작해서 문제가 생길 때마다 하나씩 추가하는 게 맞습니다.
`);
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await step_11_1();
  await step_11_2();
  await step_11_3();
  await step_11_4();
  await step_11_4b();
  await step_11_5();
  await step_11_5b();
  await step_11_6();
  await step_11_6b();
  step_11_6c();
  await step_11_7();
  await step_11_8();

  printSection("끝 — Step 12 에서는 이걸 직접 만듭니다");
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
