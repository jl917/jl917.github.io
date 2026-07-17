/**
 * Step 12 — 커스텀 미들웨어 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-12-middleware-custom/solution.ts
 *
 * exercise.ts 를 스스로 풀어 본 뒤에 열어 보세요.
 * 각 정답 위 주석에 "왜 이렇게 짜야 하는가"와 "이렇게 짜면 조용히 틀린다"를 적어 두었습니다.
 */
import "dotenv/config";

import {
  AIMessage,
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

/* ===== 공용 도구 ===== */

const getWeather = tool(
  ({ city }) => {
    const table: Record<string, string> = { 서울: "맑음, 24도", 부산: "흐림, 21도", 제주: "비, 19도" };
    return table[city] ?? `${city} 날씨 정보 없음`;
  },
  {
    name: "get_weather",
    description: "특정 도시의 현재 날씨를 조회합니다.",
    schema: z.object({ city: z.string() }),
  },
);

const slowSearch = tool(
  async ({ query }) => {
    await new Promise((resolve) => setTimeout(resolve, 3000));
    return `'${query}' 검색 결과: (3초 걸렸습니다)`;
  },
  {
    name: "slow_search",
    description: "웹을 검색합니다. 느립니다.",
    schema: z.object({ query: z.string() }),
  },
);

const sendEmail = tool(({ to, body }) => `${to} 에게 발송 완료: ${body}`, {
  name: "send_email",
  description: "이메일을 보냅니다.",
  schema: z.object({ to: z.string(), body: z.string() }),
});

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/* ===== [정답 1] beforeModel + jumpTo 로 메시지 개수 제한 =====
 *
 * 핵심은 `{ canJumpTo, hook }` 형태입니다.
 *
 * 이렇게 쓰면 조용히 틀립니다:
 *
 *   beforeModel: (state) => {
 *     if (...) return { messages: [...], jumpTo: "end" };   // ← 안 먹습니다
 *   }
 *
 * 타입 에러도 안 나고 런타임 에러도 안 납니다. 그냥 jumpTo 가 무시되고
 * 모델이 평소대로 돕니다. canJumpTo 는 "그래프에 이 엣지를 깔아 달라"는
 * 선언이고, 선언이 없으면 갈 길이 없어서 점프가 성립하지 않습니다.
 *
 * 또 하나: `jumpTo: "end"` 를 그냥 쓰면 TS 가 string 으로 넓혀 버립니다.
 * `as const` 를 붙이거나 반환 타입을 명시해야 JumpToTarget 에 맞습니다.
 */
const createMessageLimitMiddleware = (maxMessages: number) =>
  createMiddleware({
    name: "MessageLimitMiddleware",
    beforeModel: {
      canJumpTo: ["end"],
      hook: (state) => {
        if (state.messages.length >= maxMessages) {
          console.log(`  [제한] 메시지 ${state.messages.length}개 ≥ ${maxMessages} → 종료`);
          return {
            messages: [new AIMessage("대화가 너무 길어졌습니다.")],
            jumpTo: "end" as const,
          };
        }
        return;
      },
    },
  });

/* ===== [정답 2] wrapModelCall 로 지연시간 측정 =====
 *
 * try/finally 를 쓰는 이유: 모델이 던져도 finally 는 돕니다.
 * "실패한 호출이 얼마나 걸렸나"는 타임아웃을 튜닝할 때 가장 중요한 숫자인데,
 * try 뒤에만 측정하면 실패 케이스가 통계에서 통째로 빠집니다.
 *
 * 그리고 `const response = await handler(request)` 의 await 가 핵심입니다.
 * await 없이 `const response = handler(request)` 로 쓰면 Promise 를 받는 즉시
 * finally 가 돌아서 "0ms" 가 찍힙니다 — 조용히, 매번, 틀린 값으로.
 */
const latencyMiddleware = createMiddleware({
  name: "LatencyMiddleware",
  wrapModelCall: async (request, handler) => {
    const startedAt = Date.now();
    try {
      return await handler(request);
    } finally {
      console.log(`  [지연] 모델 호출 ${Date.now() - startedAt}ms`);
    }
  },
});

/* ===== [정답 3] wrapToolCall 로 도구 타임아웃 =====
 *
 * throw 대신 ToolMessage 를 돌려주는 게 요점입니다.
 *
 * throw 하면: 에이전트 전체가 죽고 사용자는 스택트레이스를 봅니다.
 * ToolMessage 를 돌려주면: 모델이 "아 이 도구는 느려서 실패했구나" 를 읽고
 * 다른 도구를 쓰거나 사용자에게 사정을 설명합니다. 에이전트가 살아 있습니다.
 *
 * status: "error" 는 provider 에게도 "이건 실패한 결과다" 를 알려 줍니다.
 *
 * ⚠️ Promise.race 는 진 쪽을 취소하지 않습니다. 도구는 백그라운드에서
 *    계속 3초를 채우고 끝납니다. 진짜로 중단시키려면 도구가 AbortSignal 을
 *    받아야 하고, 그 시그널은 request.runtime.signal 로 옵니다.
 */
const createToolTimeoutMiddleware = (timeoutMs: number) =>
  createMiddleware({
    name: "ToolTimeoutMiddleware",
    wrapToolCall: async (request, handler) => {
      // 판별 유니온(discriminated union)으로 감싸서 경쟁시킵니다.
      // Symbol 같은 센티널로 race 하면 TS 가 타입을 좁혀 주지 못해
      // 결국 as 캐스팅을 쓰게 됩니다. kind 태그를 붙이면 캐스팅 없이 좁혀집니다.
      //
      // ⚠️ Promise.resolve(...) 로 감싼 이유: handler 의 반환 타입은
      //    PromiseOrValue<ToolMessage | Command> 입니다 — Promise 가 아니라
      //    값을 그대로 돌려줄 수도 있습니다(동기 도구). handler(request).then(...)
      //    이라고 쓰면 tsc 가 "Property 'then' does not exist" 로 잡아 줍니다.
      const raced = await Promise.race([
        Promise.resolve(handler(request)).then((value) => ({ kind: "done" as const, value })),
        sleep(timeoutMs).then(() => ({ kind: "timeout" as const })),
      ]);

      if (raced.kind === "timeout") {
        console.log(`  [타임아웃] ${request.toolCall.name} 이 ${timeoutMs}ms 를 넘겼습니다`);
        return new ToolMessage({
          content: `도구 '${request.toolCall.name}' 이 ${timeoutMs}ms 안에 응답하지 않아 중단했습니다. 다른 방법을 시도하거나 사용자에게 알리세요.`,
          tool_call_id: request.toolCall.id!,
          name: request.toolCall.name,
          status: "error",
        });
      }

      return raced.value;
    },
  });

/* ===== [정답 4] stateSchema 로 도구별 호출 횟수 집계 =====
 *
 * 리듀서가 "합치기"여야 하는 이유:
 *
 * 모델이 도구를 3개 병렬로 부르면 wrapToolCall 이 3번, 거의 동시에 돕니다.
 * 각각 { get_weather: 1 } 을 반환하죠. 리듀서가 덮어쓰기(`(_a, b) => b`)면
 * 최종 결과는 { get_weather: 1 } — 3번 불렀는데 1로 남습니다.
 * 에러도 경고도 없습니다. 그냥 숫자가 틀립니다.
 *
 * 리듀서 안에서 current 를 직접 수정하지 않는 것도 중요합니다.
 * LangGraph 는 상태를 스냅샷으로 관리하는데, 스냅샷을 mutate 하면
 * 체크포인터에 저장된 과거 상태까지 바뀌어 타임트래블이 깨집니다.
 */
type ToolCounts = Record<string, number>;

const ToolStatsState = new StateSchema({
  toolCallCounts: new ReducedValue(z.record(z.string(), z.number()).default({}), {
    reducer: (current: ToolCounts, next: ToolCounts): ToolCounts => {
      // 새 객체를 만듭니다 — current 를 건드리지 않습니다.
      const merged: ToolCounts = { ...current };
      for (const [name, count] of Object.entries(next)) {
        merged[name] = (merged[name] ?? 0) + count;
      }
      return merged;
    },
  }),
});

const toolStatsMiddleware = createMiddleware({
  name: "ToolStatsMiddleware",
  stateSchema: ToolStatsState,
  wrapToolCall: async (request, handler) => {
    const result = await handler(request);

    // wrapToolCall 에서 상태를 갱신하려면 Command 를 반환합니다.
    // handler 의 결과(ToolMessage)를 messages 에 직접 실어 줘야 합니다 —
    // wrapModelCall 과 달리 wrapToolCall 은 프레임워크가 대신 붙여 주지 않습니다.
    if (ToolMessage.isInstance(result)) {
      return new Command({
        update: {
          messages: [result],
          toolCallCounts: { [request.toolCall.name]: 1 },
        },
      });
    }

    return result;
  },
});

/* ===== [정답 5] wrapModelCall 재시도 미들웨어 =====
 *
 * ⚠️ 이 문제의 함정 (공식 문서 예제조차 이 모양입니다):
 *
 *   for (...) {
 *     try {
 *       return handler(request);      // ← await 없음
 *     } catch (e) { ... }
 *   }
 *
 * handler 는 async 함수라 Promise 를 돌려줍니다. `return handler(request)` 는
 * Promise 를 만들자마자 반환하고 함수를 빠져나갑니다. 모델 호출이 나중에
 * 실패해도 그 rejection 은 이미 떠난 try/catch 를 잡을 수 없습니다.
 * 결과: 재시도 로직이 통째로 죽은 코드가 되고, 첫 실패가 그대로 위로 올라갑니다.
 * 에러 메시지는 정상이라 "재시도를 넣었는데 왜 한 번만 시도하지?" 로 몇 시간을 씁니다.
 *
 * `return await handler(request)` — await 하나가 이 미들웨어의 전부입니다.
 *
 * 참고: 실무에서 재시도는 직접 짜지 말고 내장 modelRetryMiddleware 를 쓰세요(Step 11).
 * 여기서는 wrapModelCall 이 handler 를 여러 번 부를 수 있다는 걸 보이려고 짭니다.
 */
const createRetryMiddleware = (maxRetries: number) =>
  createMiddleware({
    name: "RetryMiddleware",
    wrapModelCall: async (request, handler) => {
      let lastError: unknown;

      for (let attempt = 1; attempt <= maxRetries; attempt++) {
        try {
          console.log(`  [재시도] 시도 ${attempt}/${maxRetries}`);
          return await handler(request); // ← await 가 핵심
        } catch (error) {
          lastError = error;

          if (attempt === maxRetries) break;

          const backoff = 100 * 2 ** (attempt - 1); // 100, 200, 400...
          console.log(`  [재시도] 실패 → ${backoff}ms 후 재시도`);
          await sleep(backoff);
        }
      }

      throw lastError;
    },
  });

/* ===== [정답 6] wrapToolCall 로 인자 검열 =====
 *
 * mutate 금지가 요점입니다.
 *
 *   request.toolCall.args["body"] = redacted;   // ← 이렇게 하지 마세요
 *
 * 이건 AIMessage 안에 들어 있는 tool_calls 배열의 객체를 직접 고치는 것입니다.
 * 그 AIMessage 는 이미 state.messages 에 들어가 체크포인터에 저장돼 있습니다.
 * 즉 "모델이 실제로 뭘 요청했는가"라는 기록이 사후에 조작됩니다.
 * 감사 로그가 거짓말을 하게 되고, 디버깅할 때 "모델이 카드번호를 안 보냈는데
 * 왜 검열 로그가 찍히지?" 라는 미궁에 빠집니다.
 *
 * 새 객체를 만들어 handler 에 넘기면 원본 기록은 그대로 남고
 * 도구에만 검열된 값이 갑니다.
 */
const CARD_PATTERN = /\d{4}-\d{4}-\d{4}-\d{4}/g;

const redactMiddleware = createMiddleware({
  name: "RedactMiddleware",
  wrapToolCall: (request, handler) => {
    const args = request.toolCall.args;
    const body = args["body"];

    if (typeof body !== "string" || !CARD_PATTERN.test(body)) {
      return handler(request);
    }

    // 정규식에 /g 가 붙어 있으면 test() 가 lastIndex 를 남깁니다.
    // 리셋하지 않으면 다음 호출에서 같은 문자열인데도 false 가 나옵니다.
    CARD_PATTERN.lastIndex = 0;

    const redacted = body.replace(CARD_PATTERN, "[REDACTED]");
    console.log(`  [검열] 카드번호 패턴 발견 → 마스킹`);

    return handler({
      ...request,
      toolCall: {
        ...request.toolCall,
        args: { ...args, body: redacted },
      },
    });
  },
});

/* ===== [정답 7] afterModel + jumpTo "model" 로 되돌리기 =====
 *
 * 무한 루프 방지가 이 문제의 전부입니다.
 *
 * retryCount 가드를 빼면: 모델이 계속 짧게 답할 때마다 model 로 되돌아가고,
 * 되돌아간 모델이 또 짧게 답하고... recursionLimit(기본 25)에 닿아
 * GraphRecursionError 로 죽습니다. 그것도 25번치 토큰을 다 쓴 뒤에요.
 *
 * "미들웨어가 루프를 만들 수 있다면, 그 루프를 끊는 조건도 미들웨어가 책임진다"
 * 가 원칙입니다.
 */
const RetryState = new StateSchema({
  retryCount: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
});

const expandAnswerMiddleware = createMiddleware({
  name: "ExpandAnswerMiddleware",
  stateSchema: RetryState,
  afterModel: {
    canJumpTo: ["model"],
    hook: (state) => {
      const last = state.messages.at(-1);
      const toolCalls = (last as AIMessage | undefined)?.tool_calls ?? [];

      // 도구를 부르는 중이면 아직 최종 답변이 아닙니다 — 건드리지 않습니다.
      if (toolCalls.length > 0) return;

      const text = last?.text ?? "";
      if (text.length >= 20) return;

      // 이미 한 번 되돌렸으면 포기합니다. 이게 루프 차단기입니다.
      if (state.retryCount >= 1) {
        console.log(`  [확장] 이미 1번 되돌렸음 → 짧은 답변 그대로 수용`);
        return;
      }

      console.log(`  [확장] 답변이 ${text.length}자뿐 → 모델로 되돌림`);
      return {
        messages: [new SystemMessage("답변이 너무 짧습니다. 두 문장 이상으로 다시 답하세요.")],
        retryCount: 1,
        jumpTo: "model" as const,
      };
    },
  },
});

/* ===== [정답 8] 종합 — 속도 제한(rate limit) 미들웨어 =====
 *
 * "마지막 호출 시각을 모듈 변수에 둘까 state 에 둘까?" 의 답:
 *
 * 모듈 변수(클로저)에 둡니다. 이유:
 *
 * - rate limit 은 "이 프로세스가 provider 를 때리는 속도"에 대한 제약입니다.
 *   대화(thread) 단위가 아니라 프로세스 단위의 관심사입니다.
 * - state 에 두면 thread_id 마다 리셋됩니다. 사용자 100명이 각자 스레드를
 *   열면 각 스레드가 "나는 방금 처음 호출했다"고 믿고 동시에 100번 때립니다.
 *   429 를 막으려고 만든 미들웨어가 429 를 막지 못합니다.
 *
 * 반대로 totalWaitMs(관측값)는 "이 대화가 얼마나 기다렸나"라서 state 가 맞습니다.
 * 같은 미들웨어 안에서도 "제어에 쓰는 값"과 "기록에 남길 값"의 자리가 다릅니다.
 *
 * ⚠️ 모듈 변수 방식은 프로세스가 여러 개면(서버 인스턴스 N대) 안 통합니다.
 *    진짜 프로덕션에서는 Redis 같은 공유 저장소가 필요합니다.
 */
const RateLimitState = new StateSchema({
  totalWaitMs: new ReducedValue(z.number().default(0), {
    reducer: (current: number, next: number) => current + next,
  }),
});

const createRateLimitMiddleware = (maxPerSecond: number) => {
  const minIntervalMs = 1000 / maxPerSecond;
  let lastCallAt = 0; // ← 프로세스 전역. state 가 아닙니다.

  return createMiddleware({
    name: "RateLimitMiddleware",
    stateSchema: RateLimitState,
    wrapModelCall: async (request, handler) => {
      const elapsed = Date.now() - lastCallAt;
      const waitMs = Math.max(0, minIntervalMs - elapsed);

      if (waitMs > 0) {
        console.log(`  [속도제한] ${waitMs.toFixed(0)}ms 대기`);
        await sleep(waitMs);
      }
      lastCallAt = Date.now();

      await handler(request);

      // handler 를 이미 불렀으므로 AI 메시지는 프레임워크가 붙여 줍니다.
      // 여기서는 상태 업데이트만 실어 보냅니다.
      return new Command({ update: { totalWaitMs: waitMs } });
    },
  });
};

/* ===== 실행 ===== */

async function main(): Promise<void> {
  /* [정답 1] 기대: 모델을 안 부르고 "대화가 너무 길어졌습니다." 가 나옵니다. */
  printSection("[정답 1] 메시지 개수 제한");
  const agent1 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    middleware: [createMessageLimitMiddleware(2)],
  });
  const r1 = await agent1.invoke({
    messages: [
      { role: "user", content: "안녕" },
      { role: "assistant", content: "안녕하세요" },
      { role: "user", content: "서울 날씨는?" },
    ],
  });
  printMessages(r1.messages.at(-1)!);

  /* [정답 2] 기대: "[지연] 모델 호출 ###ms" 가 찍힙니다. 값은 매번 다릅니다. */
  printSection("[정답 2] 지연시간 측정");
  const agent2 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    middleware: [latencyMiddleware],
  });
  await agent2.invoke({ messages: [{ role: "user", content: "안녕이라고만 답해줘." }] });

  /* [정답 3] 기대: 타임아웃 ToolMessage 가 나오고, 모델이 그걸 읽고 사정을 설명합니다.
   *          에이전트는 죽지 않습니다 — 그게 핵심입니다. */
  printSection("[정답 3] 도구 타임아웃");
  const agent3 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [slowSearch],
    systemPrompt: "너는 검색 비서다. 질문을 받으면 slow_search 를 써라.",
    middleware: [createToolTimeoutMiddleware(1000)],
  });
  const r3 = await agent3.invoke({
    messages: [{ role: "user", content: "타입스크립트 뉴스 검색해줘." }],
  });
  printMessages(r3.messages);

  /* [정답 4] 기대: { get_weather: 3 } — 모델이 도시 3개를 각각 조회했다면.
   *          모델이 병렬로 부르든 순차로 부르든 합계는 3이어야 합니다. */
  printSection("[정답 4] 도구별 호출 횟수");
  const agent4 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    systemPrompt: "너는 날씨 비서다. 도시마다 도구를 따로 호출해라.",
    middleware: [toolStatsMiddleware],
  });
  const r4 = await agent4.invoke({
    messages: [{ role: "user", content: "서울, 부산, 제주 날씨 알려줘." }],
  });
  printKV({ toolCallCounts: JSON.stringify(r4.toolCallCounts) });

  /* [정답 5] 기대: "시도 1/3", "시도 2/3", "시도 3/3" 이 순서대로 찍힌 뒤 실패.
   *          await 를 빼면 "시도 1/3" 만 찍히고 바로 실패합니다 — 그게 함정입니다. */
  printSection("[정답 5] 재시도");
  const broken = await initChatModel("anthropic:claude-없는-모델");
  const agent5 = createAgent({
    model: broken,
    tools: [],
    middleware: [createRetryMiddleware(3)],
  });
  try {
    await agent5.invoke({ messages: [{ role: "user", content: "안녕" }] });
  } catch (error) {
    console.log(`3번 다 실패했습니다: ${(error as Error).message.slice(0, 60)}...`);
  }

  /* [정답 6] 기대: "[검열] 카드번호 패턴 발견" 이 찍히고,
   *          도구 결과에는 [REDACTED] 가 들어갑니다.
   *          그런데 AIMessage 의 tool_calls 에는 원본 카드번호가 그대로 남아 있습니다 —
   *          기록은 보존하고 도구에만 검열된 값을 주는 게 의도한 동작입니다. */
  printSection("[정답 6] 인자 검열");
  const agent6 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [sendEmail],
    systemPrompt: "너는 이메일 비서다. 사용자가 준 문장을 그대로 body 에 넣어 보내라.",
    middleware: [redactMiddleware],
  });
  const r6 = await agent6.invoke({
    messages: [
      { role: "user", content: "a@b.com 으로 '내 카드는 1234-5678-9012-3456 입니다' 를 보내줘." },
    ],
  });
  printMessages(r6.messages);

  /* [정답 7] 기대: retryCount 는 0 또는 1. 모델이 "네" 라고만 답하면 1번 되돌리고,
   *          두 번째 답이 또 짧아도 더는 되돌리지 않습니다. */
  printSection("[정답 7] 짧은 답변 되돌리기");
  const agent7 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 비서다.",
    middleware: [expandAnswerMiddleware],
  });
  const r7 = await agent7.invoke({ messages: [{ role: "user", content: "'네' 라고만 답해줘." }] });
  printMessages(r7.messages);
  printKV({ retryCount: r7.retryCount });

  /* [정답 8] 기대: 모델 호출이 2번 이상이면 두 번째부터 ~1000ms 씩 대기합니다.
   *          totalWaitMs 는 그 합. 첫 호출은 대기 0 입니다. */
  printSection("[정답 8] 속도 제한");
  const agent8 = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    systemPrompt: "너는 날씨 비서다. 도시마다 도구를 따로 호출해라.",
    middleware: [createRateLimitMiddleware(1)],
  });
  const r8 = await agent8.invoke({
    messages: [{ role: "user", content: "서울, 부산, 제주 날씨를 순서대로 알려줘." }],
  });
  printKV({ "총 대기(ms)": r8.totalWaitMs });
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
