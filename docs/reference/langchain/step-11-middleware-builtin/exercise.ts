/**
 * Step 11 — 내장 미들웨어 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-11-middleware-builtin/exercise.ts
 *
 * 각 [문제 N] 블록의 TODO 를 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 *
 * 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.
 * 풀고 싶은 문제의 main() 안 호출 주석을 풀고 실행하세요.
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
 * [문제 1] 실행 순서 확인
 *
 * 미들웨어 3개(A, B, C)를 만들어 각각 beforeModel 과 afterModel 에서
 * 자기 이름을 console.log 하게 하세요.
 * middleware: [A, B, C] 로 에이전트를 만들어 실행하고,
 * 출력 순서를 "예측한 뒤" 확인하세요.
 *
 * afterModel 이 역순으로 나오나요?
 *
 * ↓ 실행 전에 여기에 예상 순서를 적으세요:
 *   beforeModel 예상:
 *   afterModel  예상:
 * =================================================================== */

function traceMiddleware(name: string) {
  return createMiddleware({
    name: `Trace-${name}`,
    // TODO: beforeModel 에서 `  beforeModel ${name}` 을 출력하세요.
    // TODO: afterModel 에서 `  afterModel  ${name}` 을 출력하세요.
  });
}

async function exercise1(): Promise<void> {
  printSection("[문제 1] 실행 순서");

  // TODO: traceMiddleware("A"), ("B"), ("C") 를 배열로 넣어 에이전트를 만들고 실행하세요.
}

/* ===================================================================
 * [문제 2] 요약이 발동하지 않는 이유
 *
 * summarizationMiddleware 를 trigger: { tokens: 100, messages: 100 } 으로
 * 설정하고 짧은(하지만 토큰은 많은) 대화를 보내세요.
 *
 * 요약이 발동하나요? 발동하지 않는다면 왜 그런지 설명하고,
 * 발동하도록 고치세요.
 *
 * 힌트: 객체 하나와 배열의 의미가 다릅니다.
 * =================================================================== */

async function exercise2(): Promise<void> {
  printSection("[문제 2] 요약 trigger");

  const longText = "긴 문서입니다. ".repeat(200); // 토큰은 많지만 메시지는 1개

  // TODO: (A) trigger: { tokens: 100, messages: 100 } 으로 에이전트를 만들어 실행하세요.
  //           결과 메시지 수를 출력해 요약 발동 여부를 확인하세요.

  // TODO: (B) 요약이 발동하도록 trigger 를 고쳐서 다시 실행하세요.

  // TODO: 왜 (A) 는 발동하지 않았는지 여기에 주석으로 설명하세요.
  //   →
}

/* ===================================================================
 * [문제 3] 도구 결과의 PII
 *
 * piiMiddleware("email", { strategy: "redact" }) 를 걸고,
 * 이메일을 결과에 포함하는 도구(get_user)를 호출시키세요.
 *
 * 최종 응답에 이메일이 그대로 나오나요?
 * 옵션 하나로 막아 보세요.
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

async function exercise3(): Promise<void> {
  printSection("[문제 3] 도구 결과의 PII");

  // TODO: (A) piiMiddleware("email", { strategy: "redact" }) 만 걸고 실행하세요.
  //           "42번 사용자 정보 알려줘" 를 물어보고 printMessages 로 전체를 확인하세요.
  //           ToolMessage 와 최종 AI 응답에 이메일이 보이나요?

  // TODO: (B) 옵션 하나를 추가해 막으세요. 어떤 옵션인가요?
}

/* ===================================================================
 * [문제 4] maxRetries 와 실제 호출 횟수
 *
 * 아래 alwaysFailTool 은 호출될 때마다 카운터를 올리고 항상 에러를 던집니다.
 * toolRetryMiddleware 를 maxRetries: 3 으로 걸고 실행해,
 * 최종 카운터가 몇이 되는지 확인하세요.
 *
 * maxRetries 값과 같나요, 다른가요? 왜 그런가요?
 *
 * ↓ 실행 전에 예상값을 적으세요:  예상 카운터 =
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

async function exercise4(): Promise<void> {
  printSection("[문제 4] maxRetries 와 실제 호출 횟수");

  failCallCount = 0;

  // TODO: toolRetryMiddleware({ maxRetries: 3, initialDelayMs: 100, onFailure: "continue" })
  //       를 걸고 alwaysFailTool 을 쓰는 에이전트를 만들어 실행하세요.
  //       실행 후 failCallCount 를 출력하세요.

  // TODO: 예상과 맞았나요? 왜 그런지 주석으로 설명하세요.
  //   →
}

/* ===================================================================
 * [문제 5] modelFallbackMiddleware 의 인자 모양
 *
 * 아래 (A) 는 일부러 틀린 코드입니다. 주석을 풀면 tsc --noEmit 이 실패합니다.
 * 에러 메시지를 읽어본 뒤 다시 주석 처리하고, (B) 에 올바른 형태를 쓰세요.
 * =================================================================== */

function exercise5(): void {
  printSection("[문제 5] modelFallbackMiddleware 인자");

  // (A) ❌ 배열로 넘기기 — 주석을 풀어 에러를 확인한 뒤 다시 주석 처리하세요.
  // const wrong = modelFallbackMiddleware(["openai:gpt-5.5", CHEAP_MODEL]);

  // TODO: (B) 올바른 형태로 고쳐 쓰세요.
  //           힌트: 이 함수는 옵션 객체도 배열도 받지 않습니다.
}

/* ===================================================================
 * [문제 6] 커스텀 PII 타입
 *
 * piiMiddleware 로 EMP-123456 형식의 사번을 탐지하는 커스텀 PII 를 만드세요.
 * strategy 는 "hash" 를 쓰세요.
 *
 * 같은 사번이 두 번 나오면 같은 해시가 되는지 확인하세요.
 *
 * 힌트: detector 함수는 { text, start, end } 배열을 돌려줘야 합니다.
 * =================================================================== */

async function exercise6(): Promise<void> {
  printSection("[문제 6] 커스텀 PII");

  // TODO: piiMiddleware("employee_id", { detector: ..., strategy: "hash" }) 를 만드세요.
  //       detector 는 (content: string) => { text, start, end }[] 입니다.
  //       정규식 /EMP-\d{6}/g 와 matchAll 을 쓰세요.

  // TODO: "사번 EMP-123456 과 EMP-123456 을 그대로 따라 적어줘" 를 보내
  //       두 해시가 같은지 확인하세요.
}

/* ===================================================================
 * [문제 7] PII 와 요약의 순서 — 어느 쪽이 유출인가
 *
 * [piiMiddleware, summarizationMiddleware] 와
 * [summarizationMiddleware, piiMiddleware] 두 조합을 만들고,
 * 요약 모델에 "어떤 텍스트가 전달되는지" 확인할 로그 미들웨어를 끼워
 * 차이를 관찰하세요.
 *
 * 어느 쪽이 PII 유출인가요?
 *
 * 힌트: beforeModel 은 배열 순서대로 돕니다.
 *       두 미들웨어 사이에 peek 미들웨어를 끼우면 중간 상태가 보입니다.
 * =================================================================== */

function peekMiddleware(label: string) {
  return createMiddleware({
    name: `Peek-${label}`,
    // TODO: beforeModel 에서 state.messages.at(-1)?.text 의 앞부분을 출력하세요.
  });
}

async function exercise7(): Promise<void> {
  printSection("[문제 7] PII 와 요약의 순서");

  const secret = "제 이메일은 secret.user@example.com 입니다. 기억해 주세요.";

  // TODO: (A) [pii, peek, summarization] 조합으로 실행하세요.
  // TODO: (B) [summarization, peek, pii] 조합으로 실행하세요.
  //       summarization 의 trigger 는 [{ tokens: 50 }] 으로 낮게 잡아 발동시키세요.

  // TODO: 어느 쪽이 유출인가요? 왜 그런지 주석으로 설명하세요.
  //   →
}

/* ===================================================================
 * [문제 8] 미들웨어 이름 충돌
 *
 * 공식 가드레일 문서의 "Layered Guardrails" 예제처럼
 *   piiMiddleware("email", { applyToInput: true })
 *   piiMiddleware("email", { applyToOutput: true })
 * 를 한 에이전트에 나란히 넣어보세요.
 *
 * 무슨 일이 일어나나요? 에러 메시지를 읽고 올바른 형태로 고치세요.
 * 그리고 piiMiddleware("email") 과 piiMiddleware("credit_card") 는
 * 왜 같이 넣어도 되는지 설명하세요.
 *
 * 힌트: 미들웨어 객체의 .name 을 출력해 보세요.
 * =================================================================== */

function exercise8(): void {
  printSection("[문제 8] 미들웨어 이름 충돌");

  // TODO: (A) piiMiddleware("email", ...) 와 piiMiddleware("credit_card", ...) 의
  //           .name 을 각각 출력해 보세요.

  // TODO: (B) 같은 타입(email)을 두 번 넣어 createAgent 를 호출하세요.
  //           try/catch 로 감싸 에러 메시지를 출력하세요.

  // TODO: (C) 올바른 형태로 고치세요. 힌트: 미들웨어를 쌓는 게 아닙니다.

  // TODO: 왜 email 과 credit_card 는 같이 넣어도 되나요?
  //   →
}

/* ===================================================================
 * [문제 9] (서술형) toolRetryMiddleware 의 tools 옵션을 빼면?
 *
 * index.md 11-8 의 종합 예제에서 toolRetryMiddleware 의 tools 옵션을
 * 제거하면 어떤 시나리오에서 무엇이 잘못되는지,
 * 실행 흐름을 단계별로 적으세요. (코드 실행 없이 서술)
 *
 * 관련 코드:
 *   middleware: [
 *     humanInTheLoopMiddleware({ interruptOn: { refund_order: {...} } }),
 *     toolRetryMiddleware({ maxRetries: 2 }),   // ← tools 옵션 제거됨
 *   ]
 *
 * 고려할 것:
 *   - 두 미들웨어 모두 wrapToolCall 입니다. 어떻게 중첩되나요?
 *   - 사람은 승인을 몇 번 누르나요?
 *   - refund_order 는 몇 번 실행될 수 있나요?
 *   - 감사 로그에는 무엇이 남나요?
 *
 * ↓ 여기에 답을 적으세요:
 *
 *   1)
 *   2)
 *   3)
 *   4)
 *
 *   결론:
 *   올바른 해결책:
 *
 * =================================================================== */

/* ===== 실행 ===== */

async function main(): Promise<void> {
  // 풀고 싶은 문제의 주석을 푸세요.
  // await exercise1();
  // await exercise2();
  // await exercise3();
  // await exercise4();
  // exercise5();
  // await exercise6();
  // await exercise7();
  // exercise8();
  // 문제 9 는 서술형입니다. 위 주석 블록에 답을 적으세요.

  console.log("풀고 싶은 문제의 main() 안 주석을 푸세요.");
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
