/**
 * Step 11 — 내장 미들웨어 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-11-middleware-builtin/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 "뒤에" 열어보세요.
 */
import "dotenv/config";

import {
  createAgent,
  createMiddleware,
  tool,
  summarizationMiddleware,
  toolRetryMiddleware,
  modelFallbackMiddleware,
  piiMiddleware,
} from "langchain";
import * as z from "zod";

import { printSection, printMessages, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

/* ===================================================================
 * [정답 1] 실행 순서
 *
 * 정답:
 *   beforeModel  A → B → C   (배열 순서대로)
 *   afterModel   C → B → A   (역순!)
 *
 * 규칙은 하나입니다: "배열 앞쪽 = 바깥 껍질".
 * 양파를 생각하세요. 들어갈 때는 바깥부터, 나올 때는 안쪽부터.
 * Express/Koa 의 미들웨어 스택과 같은 모델입니다.
 *
 *   middleware: [A, B, C]
 *
 *   들어갈 때        나올 때
 *   A.beforeModel    C.afterModel
 *     B.beforeModel  B.afterModel
 *       C.beforeModel A.afterModel
 *         (모델)
 *
 * 이 역순 규칙이 11-7 의 모든 함정의 뿌리입니다.
 * [마스킹, 로깅] 이라고 쓰면 "마스킹하고 로깅하겠지"로 읽히지만
 * afterModel 에서는 로깅이 먼저 돌아 로그에 원본이 남습니다.
 * =================================================================== */

function traceMiddleware(name: string) {
  return createMiddleware({
    name: `Trace-${name}`,
    beforeModel: () => {
      console.log(`  beforeModel ${name}`);
      return; // 상태를 안 바꾸면 undefined 를 반환합니다.
    },
    afterModel: () => {
      console.log(`  afterModel  ${name}`);
      return;
    },
  });
}

async function solution1(): Promise<void> {
  printSection("[정답 1] 실행 순서");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로만 답하세요.",
    middleware: [traceMiddleware("A"), traceMiddleware("B"), traceMiddleware("C")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });

  console.log(`
→ beforeModel: A → B → C  (순서대로)
  afterModel:  C → B → A  (역순)

  wrap 훅(wrapModelCall/wrapToolCall)은 중첩됩니다: A( B( C( 모델 ) ) )
  전부 "배열 앞쪽 = 바깥 껍질" 이라는 한 규칙의 다른 표현입니다.
`);
}

/* ===================================================================
 * [정답 2] 요약이 발동하지 않는 이유
 *
 * 핵심: trigger 에 객체 하나를 주면 AND 입니다.
 *
 *   trigger: { tokens: 100, messages: 100 }
 *     → "토큰 100 이상 그리고 메시지 100개 이상" 일 때만 발동
 *
 * 짧은 대화는 messages: 100 을 절대 못 넘기므로
 * 토큰이 아무리 많아도 요약이 안 돕니다.
 *
 * 타입 정의의 주석이 명시합니다:
 *   "Single condition: trigger if tokens >= 4000 AND messages >= 10"
 *   "Multiple conditions: trigger if (...) OR (...)"
 *
 * 고치는 법: 배열로 쓰면 OR 가 됩니다.
 *   trigger: [{ tokens: 100 }, { messages: 100 }]
 *
 * ⚠️ 이게 왜 위험한가:
 *   "요약 미들웨어를 붙였는데 왜 컨텍스트 초과 에러가 나지?" 의 대표 원인입니다.
 *   긴 문서를 붙여넣은 경우처럼 메시지는 3개인데 토큰이 8000인 상황에서
 *   messages 조건을 못 넘겨 요약이 아예 안 돌고,
 *   컨텍스트를 초과하면 조용히 잘리는 게 아니라 제공자가 에러를 던집니다.
 * =================================================================== */

async function solution2(): Promise<void> {
  printSection("[정답 2] 요약 trigger — 객체는 AND, 배열은 OR");

  const longText = "긴 문서입니다. ".repeat(200);
  const prompt = `${longText}\n\n요약하지 말고 "네"라고만 답해.`;

  // (A) ❌ 객체 하나 = AND. messages: 100 을 못 넘겨서 발동 안 함.
  const andAgent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: { tokens: 100, messages: 100 }, // ← AND
        keep: { messages: 2 },
      }),
    ],
  });
  const rA = await andAgent.invoke({ messages: [{ role: "user", content: prompt }] });
  console.log(`\n(A) trigger: { tokens: 100, messages: 100 }  ← AND`);
  console.log(`    결과 메시지 수: ${rA.messages.length}  → 요약 발동 안 함`);

  // (B) ✅ 배열 = OR. tokens 조건만으로 발동.
  const orAgent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 100 }, { messages: 100 }], // ← OR
        keep: { messages: 2 },
      }),
    ],
  });
  const rB = await orAgent.invoke({ messages: [{ role: "user", content: prompt }] });
  console.log(`\n(B) trigger: [{ tokens: 100 }, { messages: 100 }]  ← OR`);
  console.log(`    결과 메시지 수: ${rB.messages.length}  → 발동함`);

  console.log(`
→ 같은 임계치인데 발동 여부가 갈립니다.
  "A 이거나 B 면 요약"을 원한다면 반드시 배열로 쓰세요.

  참고: { fraction: 0.8 } 은 "모델 컨텍스트의 80%" 라는 뜻이라
  모델을 바꿔도 알아서 따라옵니다. 토큰 수를 직접 박는 것보다 안전합니다.
`);
}

/* ===================================================================
 * [정답 3] 도구 결과의 PII
 *
 * 정답: applyToToolResults: true (그리고 applyToOutput: true)
 *
 * piiMiddleware 의 기본값은
 *   applyToInput: true, applyToOutput: false, applyToToolResults: false
 * 즉 "입력만" 봅니다.
 *
 * 그런데 PII 는 대개 사용자가 타이핑하는 게 아니라 여러분의 DB 에서 들어옵니다.
 *
 *   HUMAN │ 42번 사용자 정보 알려줘        ← PII 없음. 통과.
 *   AI    │ → tool get_user({"userId":42})
 *   TOOL  │ {"email":"kim@example.com"}   ← DB 에서 온 생 PII. 검사 안 함!
 *   AI    │ 이메일은 kim@example.com 입니다 ← 모델이 그대로 뱉음. 검사 안 함!
 *
 * 입구만 지키고 뒷문은 열어둔 셈입니다.
 * =================================================================== */

const getUserTool = tool(
  async ({ userId }) =>
    JSON.stringify({ userId, name: "김민수", email: "kim.minsu@example.com" }),
  {
    name: "get_user",
    description: "사용자 ID로 사용자 정보를 조회합니다.",
    schema: z.object({ userId: z.number().describe("사용자 ID") }),
  },
);

async function solution3(): Promise<void> {
  printSection("[정답 3] 도구 결과의 PII");

  const systemPrompt = "사용자 정보를 물으면 get_user 를 쓰고, 조회한 내용을 그대로 알려주세요.";
  const question = "42번 사용자 정보 알려줘";

  // (A) ❌ 기본값 — 입력만 검사. 도구 결과의 이메일이 샙니다.
  const leaky = createAgent({
    model: MODEL,
    tools: [getUserTool],
    systemPrompt,
    middleware: [piiMiddleware("email", { strategy: "redact" })],
  });
  const rA = await leaky.invoke({ messages: [{ role: "user", content: question }] });
  console.log("\n(A) 기본값 — 이메일이 샙니다:");
  printMessages(rA.messages);

  // (B) ✅ 세 방향 다 켜기.
  const guarded = createAgent({
    model: MODEL,
    tools: [getUserTool],
    systemPrompt,
    middleware: [
      piiMiddleware("email", {
        strategy: "redact",
        applyToInput: true,
        applyToOutput: true, // AI 응답도 검사
        applyToToolResults: true, // ← 이게 정답. 도구 결과도 검사.
      }),
    ],
  });
  const rB = await guarded.invoke({ messages: [{ role: "user", content: question }] });
  console.log("\n(B) applyToToolResults: true — 막혔습니다:");
  printMessages(rB.messages);

  console.log(`
→ 정답은 applyToToolResults: true 입니다.
  applyToOutput: true 도 같이 켜야 모델이 뱉는 것도 막힙니다.

⚠️ 반대 방향 함정도 기억하세요:
   마스킹된 값을 도구가 받으면 도구가 깨집니다.
     "kim@example.com 으로 메일 보내줘" → "[REDACTED_EMAIL] 으로 메일 보내줘"
     → send_email({ to: "[REDACTED_EMAIL]" }) → 발송 실패

   모델이 못 보는 값은 도구에도 못 넘깁니다.
   진짜 방어는 도구가 ID 로 동작하게 설계하는 것입니다:
     send_email({ user_id: 42 }) → 서버가 이메일을 조회해 발송.
   모델은 이메일을 영영 안 봅니다.
`);
}

/* ===================================================================
 * [정답 4] maxRetries 와 실제 호출 횟수
 *
 * 정답: 카운터 = 4
 *
 * maxRetries: 3 은 "3번 시도" 가 아니라 "최초 1회 + 재시도 3회" 입니다.
 * 타입 정의: "Retry attempts after initial call" — 최초 호출 "이후" 의 재시도 횟수.
 *
 *   호출 #1  (최초)      실패
 *   호출 #2  (재시도 1)  실패   ← 1초 대기
 *   호출 #3  (재시도 2)  실패   ← 2초 대기
 *   호출 #4  (재시도 3)  실패   ← 4초 대기
 *   → 재시도 소진. onFailure: "continue" 라 에러가 ToolMessage 로 모델에게 전달됨.
 *
 * 이 오프셋 하나가 실무에서 재시도 예산 계산을 어긋나게 합니다.
 * "3번만 시도하게 했는데 왜 API 사용량이 4배지?" 의 정체입니다.
 *
 * 참고: onFailure: "continue" (기본값) 라서 에이전트가 죽지 않습니다.
 *       에러가 ToolMessage 로 모델에게 전달되고,
 *       모델이 그걸 읽고 "다른 방법을 써보자" 로 갑니다. 이게 에이전트다운 동작입니다.
 * =================================================================== */

let failCallCount = 0;

const alwaysFailTool = tool(
  async ({ query }) => {
    failCallCount += 1;
    console.log(`    always_fail 호출 #${failCallCount}`);
    throw new Error(`ETIMEDOUT: 항상 실패 (query=${query})`);
  },
  {
    name: "always_fail",
    description: "웹을 검색합니다. 조회 전용이라 여러 번 불러도 안전합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

async function solution4(): Promise<void> {
  printSection("[정답 4] maxRetries 와 실제 호출 횟수");

  failCallCount = 0;

  const agent = createAgent({
    model: MODEL,
    tools: [alwaysFailTool],
    systemPrompt: "검색이 필요하면 always_fail 을 딱 한 번만 쓰세요. 실패하면 포기하고 사과하세요.",
    middleware: [
      toolRetryMiddleware({
        maxRetries: 3, // ← 최초 1회 + 재시도 3회 = 4회
        initialDelayMs: 100, // 실습이라 짧게
        backoffFactor: 2.0,
        jitter: true,
        onFailure: "continue", // 기본값. 에러를 ToolMessage 로 모델에게.
        tools: ["always_fail"], // 읽기 도구만 화이트리스트
      }),
    ],
  });

  const r = await agent.invoke({
    messages: [{ role: "user", content: "LangChain 을 검색해줘" }],
  });

  console.log(`\n최종 always_fail 호출 횟수: ${failCallCount}`);
  console.log(`→ maxRetries: 3 인데 4번 불렸습니다 (최초 1 + 재시도 3).`);
  console.log(`  "maxRetries = 총 시도 횟수" 가 아닙니다. 하나 어긋납니다.\n`);
  printMessages(r.messages.slice(-1));

  console.log(`
→ 모델이 4번 실패한 도구를 여러 번 다시 부르려 할 수 있어
  카운터가 4의 배수로 나올 수도 있습니다 (재시도 4회 × 모델의 도구 호출 N회).
  그럴 땐 toolCallLimitMiddleware({ toolName: "always_fail", runLimit: 1 }) 로
  상한을 두세요.
`);
}

/* ===================================================================
 * [정답 5] modelFallbackMiddleware 의 인자 모양
 *
 * 정답: 가변 인자입니다. 배열이 아닙니다.
 *
 *   ❌ modelFallbackMiddleware(["openai:gpt-5.5", CHEAP_MODEL])
 *   ✅ modelFallbackMiddleware("openai:gpt-5.5", CHEAP_MODEL)
 *
 * 실제 시그니처 (langchain@1.5.3 의 modelFallback.d.ts):
 *   declare function modelFallbackMiddleware(
 *     ...fallbackModels: (string | AgentLanguageModelLike)[]
 *   ): AgentMiddleware<...>
 *
 * 배열로 넘기면 "배열 한 개"를 모델 하나로 취급합니다.
 *
 * ⚠️ 공식 문서 API 레퍼런스 페이지는 이걸
 *    `fallbackModels: string | AgentLanguageModelLike[]` (단일 인자)
 *    로 표시하는데, 실제 .d.ts 는 가변 인자입니다.
 *    문서와 실제가 어긋나면 node_modules 의 .d.ts 가 진실입니다.
 *
 * 시그니처 예외는 이 스텝에서 딱 2개입니다:
 *   modelFallbackMiddleware(a, b)      ← 가변 인자
 *   piiMiddleware("email", { ... })    ← 첫 인자가 문자열
 * 나머지는 전부 옵션 객체 하나입니다.
 * =================================================================== */

function solution5(): void {
  printSection("[정답 5] modelFallbackMiddleware 인자");

  // ❌ 배열 — 타입 에러
  // const wrong = modelFallbackMiddleware(["openai:gpt-5.5", CHEAP_MODEL]);

  // ✅ 가변 인자
  const correct = modelFallbackMiddleware("openai:gpt-5.5", CHEAP_MODEL);

  console.log(`\n생성됨: ${correct.name ?? "modelFallbackMiddleware"}`);
  console.log(`
→ createAgent 의 model 이 "주 모델" 이고,
  여기 적는 것들은 주 모델이 실패했을 때 "순서대로" 시도할 대상입니다.
  여기 주 모델을 또 적지 마세요.

  폴백 모델은 "다른 제공자" 로 고르세요.
  같은 제공자의 다른 모델은 제공자가 통째로 죽으면 같이 죽습니다.
`);
}

/* ===================================================================
 * [정답 6] 커스텀 PII 타입
 *
 * 핵심 두 가지:
 *
 * 1) detector 함수는 PIIMatch[] 를 돌려줘야 합니다.
 *    interface PIIMatch { text: string; start: number; end: number }
 *    matchAll 의 m.index 를 start 로, m.index + m[0].length 를 end 로 씁니다.
 *    m.index 는 타입상 number | undefined 라 반드시 확인해야 합니다.
 *
 * 2) strategy: "hash" 는 결정적(deterministic) 입니다.
 *    같은 사번 EMP-123456 은 항상 같은 해시 <employee_id_hash:...> 가 됩니다.
 *    모델은 "이 둘이 동일인" 은 알되 실제 값은 모릅니다.
 *    → 분석/디버깅에 유용합니다. 4가지 전략 중 유일하게 신원을 (가명으로) 보존합니다.
 *
 * ⚠️ piiType 이 내장 5개(email/credit_card/ip/mac_address/url)가 아닌데
 *    detector 를 안 주면 에러를 던집니다:
 *    "If piiType is not built-in and no detector is provided"
 *
 * 참고: detector 는 함수 말고 정규식 문자열이나 RegExp 도 됩니다.
 *   piiMiddleware("api_key", { detector: "sk-[a-zA-Z0-9]{32}", strategy: "block" })
 * 아래는 함수 버전을 보여주기 위해 일부러 함수로 썼습니다.
 * =================================================================== */

async function solution6(): Promise<void> {
  printSection("[정답 6] 커스텀 PII");

  const agent = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "받은 내용을 그대로 따라 적으세요. 다른 말은 하지 마세요.",
    middleware: [
      piiMiddleware("employee_id", {
        detector: (content: string) => {
          const matches: { text: string; start: number; end: number }[] = [];
          for (const m of content.matchAll(/EMP-\d{6}/g)) {
            if (m.index === undefined) continue; // m.index 는 number | undefined
            matches.push({
              text: m[0],
              start: m.index,
              end: m.index + m[0].length,
            });
          }
          return matches;
        },
        strategy: "hash", // 결정적!
      }),
    ],
  });

  const r = await agent.invoke({
    messages: [
      { role: "user", content: "사번 EMP-123456 과 EMP-123456 과 EMP-999999 를 그대로 따라 적어줘" },
    ],
  });

  printMessages(r.messages);

  console.log(`
→ EMP-123456 이 두 번 나왔고, 둘 다 같은 해시가 됐습니다.
  EMP-999999 는 다른 해시입니다.

  전략별 출력 형식 (langchain@1.5.3 의 pii.d.ts 에서 확인):
    redact  → [REDACTED_EMPLOYEE_ID]        신원 보존 X
    mask    → 부분 마스킹                    신원 보존 X
    hash    → <employee_id_hash:a1b2c3d4>   신원 보존 O (가명)
    block   → PIIDetectionError 예외

  ⚠️ 공식 가드레일 문서는 hash 결과를 "a8f5f167..." 로 표기하지만
     실제 구현은 <타입_hash:...> 형식입니다. .d.ts 가 진실입니다.
`);
}

/* ===================================================================
 * [정답 7] PII 와 요약의 순서 — 어느 쪽이 유출인가
 *
 * 정답: (B) [summarization, pii] 가 유출입니다.
 *
 * beforeModel 은 "배열 순서대로" 돕니다. 그래서:
 *
 *   (A) [pii, peek, summarization]
 *       pii → peek → summarization
 *       요약 모델은 마스킹된 텍스트를 봅니다.  ✅
 *
 *   (B) [summarization, peek, pii]
 *       summarization → peek → pii
 *       요약 모델이 생 PII 를 먼저 봅니다.     ❌ 유출!
 *
 * 왜 무서운가:
 *   요약 미들웨어는 별도 모델(Haiku)을 부릅니다 = 외부 전송입니다.
 *   (B) 에서는 그 호출에 마스킹 안 된 secret.user@example.com 이 실려 갑니다.
 *
 *   그런데 최종적으로 주 모델에게 갈 때는 마스킹되니
 *   "결과만 보면 두 조합이 똑같아 보입니다."
 *   마스킹이 잘 되고 있다고 믿게 되죠.
 *   에러도 경고도 없습니다.
 *   요약 모델 제공자의 로그에만 원본이 남고,
 *   컴플라이언스 감사 때 다른 회사 로그에서 발견됩니다.
 *
 * 규칙:
 *   "데이터를 외부로 내보내는 미들웨어보다, 정화하는 미들웨어가 먼저"
 *
 *   외부로 내보내는 미들웨어의 예:
 *     summarizationMiddleware      (요약용 LLM 호출)
 *     llmToolSelectorMiddleware    (선별용 LLM 호출)
 *     openAIModerationMiddleware   (외부 Moderation API)
 *   전부 piiMiddleware 뒤(배열에서 아래)에 와야 합니다.
 * =================================================================== */

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

async function solution7(): Promise<void> {
  printSection("[정답 7] PII 와 요약의 순서");

  const secret = "제 이메일은 secret.user@example.com 입니다. 기억해 주세요.";

  // (A) ✅ PII 가 바깥 — 요약 모델이 마스킹된 것만 본다
  console.log("\n(A) [pii, peek, summarization] — 올바른 순서:");
  const good = createAgent({
    model: MODEL,
    tools: [],
    systemPrompt: "한 문장으로 답하세요.",
    middleware: [
      piiMiddleware("email", { strategy: "redact" }),
      peekMiddleware("pii 이후 → 요약이 볼 텍스트"),
      summarizationMiddleware({
        model: CHEAP_MODEL,
        trigger: [{ tokens: 50 }], // 낮게 잡아 발동시킴
        keep: { messages: 2 },
      }),
    ],
  });
  await good.invoke({ messages: [{ role: "user", content: secret }] });

  // (B) ❌ 요약이 바깥 — 요약 모델이 생 PII 를 본다
  console.log("\n(B) [summarization, peek, pii] — 유출:");
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
      peekMiddleware("summarization 이후 → 요약은 이미 원본을 봤다"),
      piiMiddleware("email", { strategy: "redact" }),
    ],
  });
  await bad.invoke({ messages: [{ role: "user", content: secret }] });

  console.log(`
→ (B) 가 유출입니다.

  최종 결과만 보면 (A) 와 (B) 가 똑같아 보입니다.
  둘 다 주 모델에게는 마스킹된 텍스트가 갑니다.
  차이는 "요약 모델이 무엇을 봤는가" 뿐이고, 그건 로그에만 남습니다.

  이 순서가 틀려도 에러는 절대 안 납니다.
  테스트로도 안 잡힙니다 — 결과가 "그럴듯하게" 나오기 때문입니다.
`);
}

/* ===================================================================
 * [정답 8] 미들웨어 이름 충돌
 *
 * 정답: createAgent 가 즉시 에러를 던집니다.
 *   Error: Middleware PIIMiddleware[email] is defined multiple times
 *
 * 미들웨어의 name 은 한 에이전트 안에서 유일해야 합니다.
 * (langchain@1.5.3 ReactAgent.ts:313 에서 검사합니다)
 *
 * piiMiddleware 의 이름은 piiType 에서 만들어집니다:
 *   piiMiddleware("email")       → "PIIMiddleware[email]"
 *   piiMiddleware("credit_card") → "PIIMiddleware[credit_card]"
 *
 * 그래서 "타입이 다르면" 여러 개 넣어도 충돌하지 않습니다.
 * 충돌하는 건 "같은 타입을 두 번" 넣을 때뿐입니다.
 *
 * 반면 summarizationMiddleware 는 이름이 항상 "SummarizationMiddleware" 라
 * 두 번 넣는 것 자체가 불가능합니다.
 *
 * ⚠️ 이 함정이 중요한 이유:
 *    공식 가드레일 문서의 "Layered Guardrails" 예제가 바로 이 형태입니다.
 *    입력용과 출력용을 따로 쌓는 모양이죠. 그대로 베끼면 에이전트가 안 만들어집니다.
 *
 *    올바른 사고방식:
 *      적용 범위(input/output/toolResults)는 "옵션" 으로 지정하는 것이지
 *      미들웨어를 여러 개 쌓아서 만드는 게 아닙니다.
 *
 * 💡 그나마 이건 "시끄럽게 죽는" 함정이라 다행입니다.
 *    이 스텝의 다른 함정들(순서 유출, 중복 결제, AND/OR)과 달리
 *    배포 전에 반드시 잡힙니다.
 * =================================================================== */

function solution8(): void {
  printSection("[정답 8] 미들웨어 이름 충돌");

  // (A) 이름 확인
  console.log("\n미들웨어 이름:");
  console.log(`  piiMiddleware("email")       → ${piiMiddleware("email", { strategy: "redact" }).name}`);
  console.log(
    `  piiMiddleware("credit_card") → ${piiMiddleware("credit_card", { strategy: "mask" }).name}`,
  );
  console.log(
    `  summarizationMiddleware      → ${summarizationMiddleware({ model: CHEAP_MODEL, trigger: { tokens: 10 } }).name}`,
  );

  // (B) ❌ 같은 타입 두 번 — 공식 문서의 "Layered Guardrails" 형태
  console.log("\n(B) 같은 타입(email)을 두 번:");
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

  // (C) ✅ 하나로 합치기
  console.log("\n(C) 하나로 합치기:");
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

  // (D) ✅ 타입이 다르면 여러 개 OK
  console.log("\n(D) 타입이 다른 것 여러 개:");
  createAgent({
    model: MODEL,
    tools: [],
    middleware: [
      piiMiddleware("email", { strategy: "redact" }),
      piiMiddleware("credit_card", { strategy: "mask" }),
      piiMiddleware("ip", { strategy: "hash" }),
    ],
  });
  console.log("    성공 — 이름이 PIIMiddleware[email/credit_card/ip] 로 전부 다르기 때문");
}

/* ===================================================================
 * [정답 9] (서술형) toolRetryMiddleware 의 tools 옵션을 빼면?
 *
 * 관련 코드:
 *   middleware: [
 *     humanInTheLoopMiddleware({ interruptOn: { refund_order: {...} } }),
 *     toolRetryMiddleware({ maxRetries: 2 }),   // ← tools 옵션 제거됨
 *   ]
 *
 * 둘 다 wrapToolCall 이고 "앞이 바깥" 이므로 hitl( retry( 도구 ) ) 로 중첩됩니다.
 * 승인이 바깥, 재시도가 안쪽입니다.
 *
 * 실행 흐름:
 *
 *   1) 모델이 refund_order({ orderId: 3 }) 를 호출하려 함.
 *      humanInTheLoopMiddleware 가 인터럽트를 걸고 사람에게 물어봄.
 *
 *   2) 사람이 "approve" 를 누름. 승인은 여기서 "한 번" 끝납니다.
 *      Command({ resume: { decisions: [{ type: "approve" }] } }) 로 재개.
 *
 *   3) 승인 통과 후 안쪽의 toolRetryMiddleware 가 도구를 실행:
 *        refund_order 호출 #1
 *          → 환불 서버가 5만원 환불을 "성공적으로 처리"
 *          → 응답을 보내다 네트워크 타임아웃
 *          → 클라이언트는 "실패" 로 인식
 *        refund_order 호출 #2  (재시도 1)
 *          → 또 5만원 환불 처리됨 → 타임아웃
 *        refund_order 호출 #3  (재시도 2)
 *          → 또 5만원 환불 처리됨
 *
 *   4) 결과: 사람은 승인을 1번 눌렀는데 15만원이 나갔습니다.
 *      감사 로그에는 "사용자가 환불을 승인함" 1건만 남습니다. 깨끗해 보입니다.
 *      HITL 을 걸어놨으니 안전하다고 믿고 있었는데,
 *      HITL 은 "승인 횟수" 를 통제할 뿐 "실행 횟수" 를 통제하지 않습니다.
 *
 * 결론:
 *   HITL 은 재시도를 막아주지 않습니다.
 *   승인은 바깥에서 한 번, 실행은 안쪽에서 여러 번.
 *   "실패했다" 와 "실패한 것처럼 보인다" 는 다르다는 게 근본 원인입니다.
 *   네트워크 타임아웃은 "서버가 처리 안 했다" 를 의미하지 않습니다.
 *
 * 순서를 뒤집으면? [toolRetry, humanInTheLoop] → retry( hitl( 도구 ) )
 *   재시도마다 사람에게 다시 물어봅니다.
 *   안전하지만 실패할 때마다 승인 팝업이 떠서 실용성이 떨어집니다.
 *   그리고 이미 처리된 환불에 대해 또 승인을 누르게 되므로 근본 해결이 아닙니다.
 *
 * 올바른 해결책 (3가지, 위에서부터 우선):
 *
 *   1) 비멱등 도구에는 재시도를 아예 걸지 않는다.
 *      tools 옵션으로 "재시도해도 안전한 도구만" 화이트리스트에 넣습니다.
 *        toolRetryMiddleware({ maxRetries: 2, tools: ["get_order"] })
 *      전역으로 거는 것(tools 생략)이 위험한 이유가 이것입니다.
 *
 *   2) 쓰기 도구에 꼭 재시도가 필요하면 멱등키(idempotency key)를 쓴다.
 *      재시도는 "같은 인자로" 다시 부르므로 멱등키도 같은 값이 됩니다.
 *      서버가 "이미 처리한 요청" 으로 판단해 중복을 걸러냅니다.
 *        refund_order({ orderId: 3, idempotencyKey: "refund-3-abc123" })
 *
 *   3) 도구를 애초에 멱등하게 설계한다.
 *      "환불하라" 대신 "이 주문의 상태를 REFUNDED 로 만들라" 처럼
 *      여러 번 실행해도 결과가 같은 형태로.
 *
 * 일반 규칙:
 *   읽기 도구(검색, 조회) → 재시도 O
 *   쓰기 도구(결제, 발송, 삭제) → 재시도 X (또는 멱등키 필수)
 * =================================================================== */

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await solution1();
  await solution2();
  await solution3();
  await solution4();
  solution5();
  await solution6();
  await solution7();
  solution8();
  // 정답 9 는 서술형입니다. 위 주석 블록을 읽으세요.

  printSection("끝 — Step 12 에서는 미들웨어를 직접 만듭니다");
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
