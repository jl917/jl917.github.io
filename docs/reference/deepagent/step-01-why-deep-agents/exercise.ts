/**
 * Step 01 — Deep Agent란 무엇인가 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-01-why-deep-agents/exercise.ts
 *
 * 아래 [문제 N] 블록 밑을 채우세요.
 * 문제 1 과 8 만 실제 API 호출이 필요합니다 (RUN_LIVE=1 로 켜세요).
 * 나머지는 스파이 미들웨어 덕분에 API 키 없이 풀립니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { createDeepAgent } from "deepagents";
import { AIMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const RUN_LIVE = process.env["RUN_LIVE"] === "1";

/* ── 준비물: 본문 1-3 의 스파이 미들웨어 ──────────────────────
   문제 2, 3, 8 에서 그대로 씁니다. 다시 만들 필요 없습니다. */

type Observed = { tools: string[]; systemPrompt: string };

function createSpy(sink: Observed) {
  return createMiddleware({
    name: "Spy",
    wrapModelCall: async (request) => {
      sink.tools = (request.tools ?? []).map((t) => (t as { name: string }).name);
      sink.systemPrompt =
        typeof request.systemPrompt === "string" ? request.systemPrompt : "";
      return new AIMessage("(스파이가 가로챘습니다)");
    },
  });
}

type Invokable = { invoke(input: { messages: { role: string; content: string }[] }): Promise<unknown> };

async function observe(make: (spy: ReturnType<typeof createSpy>) => Invokable): Promise<Observed> {
  const sink: Observed = { tools: [], systemPrompt: "" };
  const agent = make(createSpy(sink));
  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  return sink;
}

/* ===== [문제 1] 얕은 에이전트 vs Deep Agent — 턴 수 비교 =====
 *
 * "피보나치 수열 10번째 항을 구해 줘" 를
 *   (A) createAgent 로 시키고 result.messages.length 를 찍으세요.
 *   (B) 같은 요청을 createDeepAgent 로 시키고 같은 값을 찍으세요.
 * 두 숫자를 비교하고, 이 작업에 어느 쪽이 적합한지 이유를 주석으로 적으세요.
 *
 * RUN_LIVE=1 이 필요합니다.
 */
printSection("[문제 1] 턴 수 비교");

// 여기에 작성하세요

// → 어느 쪽이 적합한가요? 왜?
//   (여기에 답을 주석으로)

/* ===== [문제 2] 커스텀 도구는 대체인가 추가인가 =====
 *
 * 위의 observe() 와 createSpy() 를 써서,
 * createDeepAgent({ tools: [myTool] }) 처럼 커스텀 도구를 1개 줬을 때
 * 모델에 실리는 도구가 몇 개인지 찍으세요.
 * 커스텀 도구는 내장 도구를 대체하나요, 더해지나요?
 */
printSection("[문제 2] 커스텀 도구 1개를 주면 도구가 몇 개?");

const myTool = tool(({ city }: { city: string }) => `${city}는 맑음`, {
  name: "get_weather",
  description: "도시의 날씨를 알려 줍니다",
  schema: z.object({ city: z.string() }),
});

// 여기에 작성하세요

// → 대체인가요, 추가인가요?
//   (여기에 답을 주석으로)

/* ===== [문제 3] 시스템 프롬프트 해부 =====
 *
 * observe() 로 createDeepAgent 의 시스템 프롬프트를 얻어
 *   (A) 파일로 저장하세요 (fs.writeFileSync, 예: "./prompt.txt")
 *   (B) "write_todos", "task", "read_file" 이 각각 몇 번 등장하는지 세세요
 * 어느 기둥에 가장 많은 지면을 할애했나요?
 *
 * 힌트: 개수 세기는 (s.match(/task/g) ?? []).length
 */
printSection("[문제 3] 프롬프트에서 어느 기둥이 가장 긴가");

// 여기에 작성하세요

// → 어느 기둥에 지면이 가장 많이 갔나요? 왜 그럴까요?
//   (여기에 답을 주석으로)

/* ===== [문제 4] createDeepAgent 는 정말 비동기인가 =====
 *
 * createDeepAgent({ model: MODEL }) 의 반환값에 대해
 *   (A) instanceof Promise
 *   (B) constructor.name
 * 을 찍어 본문 1-4 의 함정을 재현하세요.
 * 이어서 await 를 붙인 결과와 안 붙인 결과가 같은 객체인지 확인하세요.
 */
printSection("[문제 4] createDeepAgent 의 반환값 정체");

// 여기에 작성하세요

/* ===== [문제 5] 컨텍스트 예산 계산 =====
 *
 * 컨텍스트 윈도우 200,000 토큰, 자동 요약은 170,000 에서 걸린다고 합시다.
 * 시스템 프롬프트가 약 1,700 토큰이고 검색 결과 1건이 6,000 토큰이면,
 * 요약이 걸리기 전에 검색을 몇 번 할 수 있나요? 계산하는 코드를 쓰세요.
 */
printSection("[문제 5] 요약 전에 검색을 몇 번 할 수 있나");

const SUMMARIZE_AT = 170_000;
const SYSTEM_TOKENS = 1_700;
const SEARCH_TOKENS = 6_000;

// 여기에 작성하세요

/* ===== [문제 6] 설계 판단 — 무엇을 쓸 것인가 =====
 *
 * 다음 세 작업에 createAgent 와 createDeepAgent 중 무엇을 쓸지 고르고
 * 이유를 한 줄씩 주석으로 적으세요. (코드 없이 주석만)
 *
 *   (a) 고객 문의를 "환불 / 배송 / 기타" 셋 중 하나로 분류
 *   (b) 저장소 전체를 읽고 아키텍처 문서를 작성
 *   (c) 사내 위키를 검색해 질문에 답하는 챗봇
 */
printSection("[문제 6] 설계 판단");

// (a) →
// (b) →
// (c) →

/* ===== [문제 7] 실패 증상과 기둥 짝짓기 =====
 *
 * 아래 실패 증상 4개를 4대 기둥 중 하나씩에 짝지으세요. (주석으로)
 *   기둥 1 = 계획(write_todos)
 *   기둥 2 = 파일시스템(오프로딩)
 *   기둥 3 = 서브에이전트(격리)
 *   기둥 4 = 상세 시스템 프롬프트
 *
 *   (a) 검색 결과를 15개 쌓았더니 컨텍스트가 넘쳤다
 *   (b) 30턴째에 처음 세운 계획을 잊고 엉뚱한 걸 하고 있다
 *   (c) 도구는 다 붙여 줬는데 모델이 한 번도 안 부른다
 *   (d) 초안을 뱉었는데 고쳐 달라니까 처음부터 다시 쓴다
 */
printSection("[문제 7] 증상 → 기둥 짝짓기");

// (a) →
// (b) →
// (c) →
// (d) →

/* ===== [문제 8] (심화) 스파이를 통과시키기 =====
 *
 * createSpy 를 고쳐서 handler(request) 를 "실제로 호출하되"
 * 매 호출마다 도구 개수와 프롬프트 길이를 찍는 미들웨어를 만드세요.
 * 그리고 RUN_LIVE=1 로 Deep Agent 를 돌려
 *   - 모델이 몇 번 호출되는지
 *   - 매번 시스템 프롬프트 길이가 변하는지
 * 관찰하세요.
 *
 * 힌트: wrapModelCall 의 두 번째 인자가 handler 입니다.
 *       return await handler(request); 로 통과시키면 됩니다.
 *       대화가 길어질 때 커지는 건 systemPrompt 가 아니라 messages 입니다.
 */
printSection("[문제 8] 통과시키는 스파이로 호출 횟수 세기");

// 여기에 작성하세요

// → 시스템 프롬프트 길이는 매번 변하나요? 그럼 뭐가 커지나요?
//   (여기에 답을 주석으로)

console.log("\n(문제를 다 풀었으면 solution.ts 로 채점하세요)");
