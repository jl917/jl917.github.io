/**
 * Step 02 — 첫 Deep Agent · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-02-quickstart/exercise.ts
 *
 * 아래 [문제 N] 블록 밑을 채우세요.
 * 문제 4(뒷부분), 6, 8 만 실제 API 호출이 필요합니다 (RUN_LIVE=1).
 */
import "dotenv/config";

import { createDeepAgent } from "deepagents";
import { createMiddleware, tool } from "langchain";
import { initChatModel } from "langchain/chat_models/universal";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";
import { createRequire } from "node:module";

import { printSection } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const RUN_LIVE = process.env["RUN_LIVE"] === "1";
const require = createRequire(import.meta.url);

/* ── 준비물: 본문 2-5 의 스파이 미들웨어 ──────────────────────
   문제 3, 4 에서 그대로 씁니다. */

type Observed = { tools: string[]; systemPrompt: string };

function createSpy(sink: Observed) {
  return createMiddleware({
    name: "Spy",
    wrapModelCall: async (request) => {
      sink.tools = (request.tools ?? []).map((t) => (t as { name: string }).name);
      sink.systemPrompt =
        typeof request.systemPrompt === "string" ? request.systemPrompt : "";
      return new AIMessage("(가로챔)");
    },
  });
}

type Invokable = { invoke(input: { messages: { role: string; content: string }[] }): Promise<unknown> };

async function observe(make: (spy: ReturnType<typeof createSpy>) => Invokable): Promise<Observed> {
  const sink: Observed = { tools: [], systemPrompt: "" };
  const a = make(createSpy(sink));
  await a.invoke({ messages: [{ role: "user", content: "안녕" }] });
  return sink;
}

/* ===== [문제 1] peer dependency 확인 =====
 *
 * (A) 터미널에서 `npm ls @langchain/core` 를 실행하고 출력을 확인하세요.
 *     "deduped" 가 몇 번 나오나요?
 * (B) 코드로 deepagents/package.json 의 peerDependencies 를 읽어 찍으세요.
 *
 * 힌트: require("deepagents/package.json") 으로 읽을 수 있습니다.
 */
printSection("[문제 1] deepagents 의 peer dependency");

// 여기에 작성하세요

// → (A) deduped 가 몇 번 나왔나요? 그게 무슨 뜻인가요?
//   (여기에 답을 주석으로)

/* ===== [문제 2] 엔트리포인트 diff =====
 *
 * deepagents 와 deepagents/browser 의 export 개수를 각각 세고,
 * browser 에만 없는 것 9개를 나열하세요.
 * 그 9개의 공통점은 무엇인가요?
 *
 * 힌트: await import("deepagents/browser") 로 동적 import 합니다.
 */
printSection("[문제 2] browser 엔트리에 없는 9개");

// 여기에 작성하세요

// → 9개의 공통점은?
//   (여기에 답을 주석으로)

/* ===== [문제 3] 옵션 없이 만들 수 있나 =====
 *
 * createDeepAgent({}) 처럼 옵션을 하나도 안 주고 에이전트를 만들 수 있나요?
 * 만들어 보고, 스파이로 도구가 몇 개 실리는지 확인하세요.
 */
printSection("[문제 3] createDeepAgent({}) 가 되는가");

// 여기에 작성하세요

/* ===== [문제 4] description 이 곧 프롬프트 =====
 *
 * (A) 계산기 도구(add: 두 수를 더함)를 만들어 Deep Agent 에 붙이고,
 *     도구가 9개가 되는지 확인하세요.
 * (B) description 을 "계산" 이라는 두 글자로 바꾼 도구를 하나 더 만들고,
 *     RUN_LIVE=1 로 "3 더하기 5는?" 을 물었을 때
 *     모델이 그 도구를 부르는지 관찰하세요.
 *
 * 이 스텝에서 가장 중요한 실험입니다. 꼭 (B) 를 돌려 보세요.
 */
printSection("[문제 4] description 을 줄이면 모델이 도구를 부를까");

// (A) 여기에 작성하세요

// (B) 여기에 작성하세요

// → 모델이 도구를 불렀나요? 안 불렀다면 대신 뭘 했나요?
//   (여기에 답을 주석으로)

/* ===== [문제 5] 모델 지정 세 가지 방법 =====
 *
 * 같은 에이전트를 세 가지 방법으로 만들고 셋 다 생성되는지 확인하세요.
 *   (a) "anthropic:claude-sonnet-4-6" 문자열
 *   (b) initChatModel(..., { temperature: 0 })
 *   (c) new ChatAnthropic({ maxTokens: 4096 })
 */
printSection("[문제 5] 모델 지정 세 가지 방법");

// 여기에 작성하세요

/* ===== [문제 6] 스트리밍으로 흐름 보기 =====
 *
 * RUN_LIVE=1 로 streamMode: "updates" 와 subgraphs: true 를 주고
 * "/notes.md 에 오늘 할 일 3개를 써 줘" 를 실행해,
 * 어떤 도구가 어떤 순서로 불리는지 기록하세요.
 *
 * 이어서 subgraphs 를 빼고 다시 돌려 무엇이 안 보이는지 비교하세요.
 *
 * 힌트: for await (const [namespace, chunk] of await agent.stream(...))
 *       await 를 빼면 구조분해가 깨집니다!
 */
printSection("[문제 6] stream 으로 도구 호출 순서 기록");

// 여기에 작성하세요

// → subgraphs 를 빼면 무엇이 안 보이나요?
//   (여기에 답을 주석으로)

/* ===== [문제 7] stateSchema vs contextSchema =====
 *
 * 둘의 차이를 한 줄로 쓰고, 다음 셋을 어디에 둘지 고르세요. (주석만)
 *   (a) 요청자의 userId
 *   (b) 지금까지 작성한 보고서 초안
 *   (c) 외부 API 키
 */
printSection("[문제 7] stateSchema vs contextSchema");

// 차이 →
// (a) userId      →
// (b) 보고서 초안 →
// (c) API 키      →

/* ===== [문제 8] (심화) 격리가 아껴 준 컨텍스트 재기 =====
 *
 * 본문 2-8 의 스트리밍 코드를 고쳐서
 *   - 서브에이전트가 받은 도구 결과의 총 글자 수
 *   - 메인에게 돌아온 task 결과의 글자 수
 * 를 각각 합산해 찍으세요. 두 숫자의 비율이 격리가 아껴 준 컨텍스트입니다.
 *
 * 힌트: namespace.length === 0 이면 메인, 아니면 서브에이전트입니다.
 *       도구 결과는 m.getType() === "tool" 인 메시지의 m.text 입니다.
 */
printSection("[문제 8] 격리가 아껴 준 컨텍스트");

// 여기에 작성하세요

console.log("\n(문제를 다 풀었으면 solution.ts 로 채점하세요)");
