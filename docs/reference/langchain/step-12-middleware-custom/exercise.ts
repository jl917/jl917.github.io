/**
 * Step 12 — 커스텀 미들웨어 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-12-middleware-custom/exercise.ts
 *
 * 각 [문제 N] 블록 아래 빈 곳을 채우세요.
 * 채우기 전에는 타입 에러가 나거나 미들웨어가 아무 일도 하지 않는 게 정상입니다.
 *
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어 보세요.
 */
import "dotenv/config";

import {
  AIMessage,
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

/** 일부러 느린 도구 — 문제 3 의 타임아웃 대상 */
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

/** 문제 6 의 검열 대상 — 인자에 민감정보가 섞여 들어옵니다 */
const sendEmail = tool(
  ({ to, body }) => `${to} 에게 발송 완료: ${body}`,
  {
    name: "send_email",
    description: "이메일을 보냅니다.",
    schema: z.object({ to: z.string(), body: z.string() }),
  },
);

/* ===== [문제 1] beforeModel + jumpTo 로 메시지 개수 제한 =====
 *
 * 대화 메시지가 maxMessages 개 이상이면 모델을 부르지 않고 즉시 종료하는
 * 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - beforeModel 훅을 쓸 것
 *   - 한계에 도달하면 AIMessage("대화가 너무 길어졌습니다.") 를 넣고 종료
 *   - 한계 미만이면 아무것도 안 하고 통과
 *
 * 힌트: jumpTo 를 쓰려면 훅을 { canJumpTo, hook } 형태로 선언해야 합니다.
 *       그냥 함수로 쓰고 jumpTo 만 반환하면 조용히 무시됩니다.
 */

const createMessageLimitMiddleware = (maxMessages: number) =>
  createMiddleware({
    name: "MessageLimitMiddleware",
    // 여기에 beforeModel 훅을 작성하세요.
  });

/* ===== [문제 2] wrapModelCall 로 지연시간 측정 =====
 *
 * 모델 호출이 몇 ms 걸렸는지 재서 콘솔에 찍는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - handler() 호출 전후로 Date.now() 를 재서 차이를 출력
 *   - 모델이 에러를 던져도 걸린 시간은 찍혀야 함 (실패도 시간이 든다)
 *   - 응답 자체는 그대로 반환 (관찰만 하고 바꾸지 않는다)
 *
 * 힌트: try/finally. 그리고 handler 앞의 await 를 빠뜨리지 마세요.
 */

const latencyMiddleware = createMiddleware({
  name: "LatencyMiddleware",
  // 여기에 wrapModelCall 훅을 작성하세요.
});

/* ===== [문제 3] wrapToolCall 로 도구 타임아웃 =====
 *
 * 도구가 timeoutMs 안에 안 끝나면 포기하고 에러 ToolMessage 를 돌려주는
 * 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - Promise.race 로 handler(request) 와 타이머를 경쟁시킬 것
 *   - 타임아웃이면 throw 하지 말고 ToolMessage 를 반환할 것
 *     (throw 하면 에이전트 전체가 죽습니다 — 모델이 스스로 복구할 기회를 주세요)
 *   - ToolMessage 의 tool_call_id 는 request.toolCall.id 를 쓸 것
 *   - status: "error" 를 붙일 것
 *
 * slowSearch 는 3초 걸리므로 timeoutMs=1000 이면 반드시 타임아웃됩니다.
 */

const createToolTimeoutMiddleware = (timeoutMs: number) =>
  createMiddleware({
    name: "ToolTimeoutMiddleware",
    // 여기에 wrapToolCall 훅을 작성하세요.
  });

/* ===== [문제 4] stateSchema 로 도구별 호출 횟수 집계 =====
 *
 * 어떤 도구가 몇 번 불렸는지를 상태에 누적하는 미들웨어를 만드세요.
 * 최종 결과는 { get_weather: 2, slow_search: 1 } 같은 객체여야 합니다.
 *
 * 요구사항:
 *   - StateSchema + ReducedValue 를 쓸 것
 *   - 리듀서는 두 객체를 "합쳐야" 합니다 (덮어쓰기 아님)
 *     예: {a:1} 에 {a:1, b:1} 이 오면 → {a:2, b:1}
 *   - wrapToolCall 에서 Command 로 업데이트를 반환할 것
 *
 * 힌트: 리듀서 안에서 state 를 직접 수정하지 말고 새 객체를 만들어 반환하세요.
 */

const ToolStatsState = new StateSchema({
  // 여기에 toolCallCounts 필드를 작성하세요.
});

const toolStatsMiddleware = createMiddleware({
  name: "ToolStatsMiddleware",
  stateSchema: ToolStatsState,
  // 여기에 wrapToolCall 훅을 작성하세요.
});

/* ===== [문제 5] wrapModelCall 재시도 미들웨어 =====
 *
 * 모델 호출이 실패하면 최대 maxRetries 번까지 다시 시도하는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - 재시도 사이에 지수 백오프(exponential backoff)를 넣을 것
 *     (1차 100ms, 2차 200ms, 3차 400ms ...)
 *   - 마지막 시도까지 실패하면 마지막 에러를 그대로 throw
 *   - 시도 횟수를 콘솔에 찍을 것
 *
 * ⚠️ 이 문제의 진짜 함정: try 블록 안에서 `return handler(request)` 라고 쓰면
 *    재시도가 절대 동작하지 않습니다. 왜인지 설명할 수 있어야 합니다.
 *    (답은 solution.ts 주석에)
 */

const createRetryMiddleware = (maxRetries: number) =>
  createMiddleware({
    name: "RetryMiddleware",
    // 여기에 wrapModelCall 훅을 작성하세요.
  });

/* ===== [문제 6] wrapToolCall 로 인자 검열 =====
 *
 * send_email 도구의 body 인자에서 신용카드 번호처럼 보이는 문자열을
 * "[REDACTED]" 로 바꾼 뒤 도구를 실행하는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - 정규식 /\d{4}-\d{4}-\d{4}-\d{4}/g 로 찾을 것
 *   - request.toolCall.args 를 직접 수정(mutate)하지 말 것 — 새 객체를 만들 것
 *   - 검열한 인자로 handler 를 호출할 것
 *   - 검열이 일어났으면 콘솔에 알릴 것
 *
 * 힌트: handler({ ...request, toolCall: { ...request.toolCall, args: 새인자 } })
 */

const redactMiddleware = createMiddleware({
  name: "RedactMiddleware",
  // 여기에 wrapToolCall 훅을 작성하세요.
});

/* ===== [문제 7] afterModel + jumpTo "model" 로 되돌리기 =====
 *
 * 모델이 도구를 하나도 안 부르고 답변이 20자 미만이면
 * "너무 짧다, 다시 답해라" 라고 시키고 모델로 되돌아가는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - afterModel 훅 + canJumpTo: ["model"]
 *   - 되돌릴 때 SystemMessage 나 HumanMessage 로 지적을 넣을 것
 *   - ⚠️ 무한 루프 방지: 되돌리기는 최대 1번만. 상태로 세세요.
 *     (안 그러면 모델이 계속 짧게 답할 때 recursionLimit 까지 돌다 죽습니다)
 *
 * 힌트: stateSchema 에 retryCount 를 두고, 이미 1 이면 되돌리지 마세요.
 */

const RetryState = new StateSchema({
  // 여기에 retryCount 필드를 작성하세요.
});

const expandAnswerMiddleware = createMiddleware({
  name: "ExpandAnswerMiddleware",
  stateSchema: RetryState,
  // 여기에 afterModel 훅을 작성하세요.
});

/* ===== [문제 8] 종합 — 속도 제한(rate limit) 미들웨어 =====
 *
 * 모델 호출이 초당 maxPerSecond 회를 넘지 않도록 강제하는 미들웨어를 만드세요.
 *
 * 요구사항:
 *   - wrapModelCall 에서 마지막 호출 시각을 기억하고,
 *     간격이 부족하면 sleep 한 뒤 handler 를 부를 것
 *   - 얼마나 기다렸는지 콘솔에 찍을 것
 *   - stateSchema 로 총 대기 시간(ms)을 누적할 것
 *   - handler 를 부른 뒤 Command 로 대기 시간을 상태에 반영할 것
 *
 * 생각해 볼 것: 이 미들웨어의 "마지막 호출 시각"을 모듈 스코프 변수에 둘까요,
 * 아니면 state 에 둘까요? 둘의 차이는 무엇일까요? (solution.ts 참고)
 */

const RateLimitState = new StateSchema({
  // 여기에 totalWaitMs 필드를 작성하세요.
});

const createRateLimitMiddleware = (maxPerSecond: number) =>
  createMiddleware({
    name: "RateLimitMiddleware",
    stateSchema: RateLimitState,
    // 여기에 wrapModelCall 훅을 작성하세요.
  });

/* ===== 실행 =====
 *
 * 문제를 풀면서 아래 주석을 하나씩 풀어 확인하세요.
 */

async function main(): Promise<void> {
  printSection("[문제 1] 메시지 개수 제한");
  // const agent1 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [getWeather],
  //   middleware: [createMessageLimitMiddleware(2)],
  // });
  // const r1 = await agent1.invoke({
  //   messages: [
  //     { role: "user", content: "안녕" },
  //     { role: "assistant", content: "안녕하세요" },
  //     { role: "user", content: "서울 날씨는?" },
  //   ],
  // });
  // printMessages(r1.messages.at(-1)!);

  printSection("[문제 2] 지연시간 측정");
  // const agent2 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [],
  //   middleware: [latencyMiddleware],
  // });
  // await agent2.invoke({ messages: [{ role: "user", content: "안녕이라고만 답해줘." }] });

  printSection("[문제 3] 도구 타임아웃");
  // const agent3 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [slowSearch],
  //   systemPrompt: "너는 검색 비서다. 질문을 받으면 slow_search 를 써라.",
  //   middleware: [createToolTimeoutMiddleware(1000)],
  // });
  // const r3 = await agent3.invoke({
  //   messages: [{ role: "user", content: "타입스크립트 뉴스 검색해줘." }],
  // });
  // printMessages(r3.messages);

  printSection("[문제 4] 도구별 호출 횟수");
  // const agent4 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [getWeather],
  //   systemPrompt: "너는 날씨 비서다. 도시마다 도구를 따로 호출해라.",
  //   middleware: [toolStatsMiddleware],
  // });
  // const r4 = await agent4.invoke({
  //   messages: [{ role: "user", content: "서울, 부산, 제주 날씨 알려줘." }],
  // });
  // printKV({ toolCallCounts: JSON.stringify(r4.toolCallCounts) });

  printSection("[문제 5] 재시도");
  // const broken = await initChatModel("anthropic:claude-없는-모델");
  // const agent5 = createAgent({
  //   model: broken,
  //   tools: [],
  //   middleware: [createRetryMiddleware(3)],
  // });
  // try {
  //   await agent5.invoke({ messages: [{ role: "user", content: "안녕" }] });
  // } catch (error) {
  //   console.log(`3번 다 실패했습니다: ${(error as Error).message.slice(0, 60)}`);
  // }

  printSection("[문제 6] 인자 검열");
  // const agent6 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [sendEmail],
  //   systemPrompt: "너는 이메일 비서다. 사용자가 준 문장을 그대로 body 에 넣어 보내라.",
  //   middleware: [redactMiddleware],
  // });
  // const r6 = await agent6.invoke({
  //   messages: [
  //     { role: "user", content: "a@b.com 으로 '내 카드는 1234-5678-9012-3456 입니다' 를 보내줘." },
  //   ],
  // });
  // printMessages(r6.messages);

  printSection("[문제 7] 짧은 답변 되돌리기");
  // const agent7 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [],
  //   systemPrompt: "너는 비서다.",
  //   middleware: [expandAnswerMiddleware],
  // });
  // const r7 = await agent7.invoke({ messages: [{ role: "user", content: "'네' 라고만 답해줘." }] });
  // printMessages(r7.messages);
  // printKV({ retryCount: r7.retryCount });

  printSection("[문제 8] 속도 제한");
  // const agent8 = createAgent({
  //   model: "anthropic:claude-sonnet-4-6",
  //   tools: [getWeather],
  //   systemPrompt: "너는 날씨 비서다. 도시마다 도구를 따로 호출해라.",
  //   middleware: [createRateLimitMiddleware(1)],
  // });
  // const r8 = await agent8.invoke({
  //   messages: [{ role: "user", content: "서울, 부산, 제주 날씨를 순서대로 알려줘." }],
  // });
  // printKV({ "총 대기(ms)": r8.totalWaitMs });
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
