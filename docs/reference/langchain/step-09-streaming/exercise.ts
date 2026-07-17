/**
 * Step 09 — 스트리밍 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-09-streaming/exercise.ts
 *
 * 각 [문제 N] 블록의 TODO 를 채우세요.
 * 채우기 전에 실행하면 "미구현" 안내만 출력됩니다 — 정상입니다.
 * 정답과 해설은 solution.ts 에 있습니다.
 */
import "dotenv/config";

import { createAgent, tool } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessageChunk } from "@langchain/core/messages";
import { type LangGraphRunnableConfig } from "@langchain/langgraph";
import * as z from "zod";

import { printSection } from "../project/src/lib/print.js";

const model = new ChatAnthropic({ model: "claude-sonnet-4-6" });

const searchDocs = tool(
  async (input: { query: string }, _config: LangGraphRunnableConfig) => {
    await new Promise((r) => setTimeout(r, 200));
    return `"${input.query}" 검색 결과: 문서 3건을 찾았습니다.`;
  },
  {
    name: "search_docs",
    description: "사내 문서를 검색합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchDocs],
  systemPrompt: "당신은 사내 문서 검색 비서입니다. 질문에는 반드시 도구로 근거를 찾아 답하세요.",
});

const ask = { messages: [{ role: "user" as const, content: "휴가 정책을 알려줘" }] };

/* =====================================================================
 * [문제 1] TTFT 측정
 *
 * model.stream() 을 호출해 "첫 청크가 도착한 시각 - 호출 시각"(TTFT)과
 * 전체 완료 시간을 각각 밀리초로 반환하세요.
 * 반환: { ttftMs, totalMs, text }
 *
 * 힌트: for await 안에서 "아직 TTFT 를 안 찍었으면 지금 찍는다" 로 처리합니다.
 * ===================================================================== */
async function problem1(
  prompt: string,
): Promise<{ ttftMs: number; totalMs: number; text: string }> {
  // TODO: 구현
  void prompt;
  return { ttftMs: -1, totalMs: -1, text: "" };
}

/* =====================================================================
 * [문제 2] concat 으로 청크 합치기
 *
 * model.stream() 의 청크를 concat 으로 합쳐 완성된 AIMessageChunk 하나를
 * 반환하세요. 문자열 이어붙이기(+=)를 쓰면 안 됩니다.
 *
 * 이 함수의 반환값에서 usage_metadata 가 살아 있어야 정답입니다.
 * (문자열로 이어붙이면 usage_metadata 는 절대 얻을 수 없습니다.)
 * ===================================================================== */
async function problem2(prompt: string): Promise<AIMessageChunk | null> {
  // TODO: 구현
  void prompt;
  return null;
}

/* =====================================================================
 * [문제 3] updates 모드로 "도구가 언제 불렸는지" 로그
 *
 * agent.stream(ask, { streamMode: "updates" }) 를 구독해서,
 * 모델이 도구를 호출하기로 결정한 순간과 도구 결과가 돌아온 순간을
 * 각각 한 줄씩 출력하세요.
 *
 * 힌트: chunk 는 { 노드이름: { messages: [...] } } 모양입니다.
 *       AIMessage 에 tool_calls 가 있으면 "호출 결정",
 *       ToolMessage(getType() === "tool") 가 오면 "결과 도착" 입니다.
 * ===================================================================== */
async function problem3(): Promise<void> {
  // TODO: 구현
}

/* =====================================================================
 * [문제 4] messages 모드에서 특정 노드의 토큰만 뽑기
 *
 * streamMode: "messages" 로 구독하되, metadata.langgraph_node 가
 * "model" 인 토큰만 출력하세요. (도구 결과는 걸러집니다.)
 *
 * 먼저 필터 없이 돌려서 어떤 노드 이름들이 나오는지 눈으로 확인한 뒤 거세요.
 * ===================================================================== */
async function problem4(): Promise<void> {
  // TODO: 구현
}

/* =====================================================================
 * [문제 5] 여러 모드 동시 구독
 *
 * streamMode: ["updates", "messages"] 로 구독해서
 * - messages 청크는 그대로 화면에 흘리고
 * - updates 청크가 오면 줄바꿈 후 "[스텝 완료: 노드이름]" 을 찍으세요.
 *
 * 함정: 배열을 주면 각 항목이 [mode, chunk] 튜플이 됩니다.
 *       단일 모드일 때의 구조분해를 그대로 쓰면 안 됩니다.
 * ===================================================================== */
async function problem5(): Promise<void> {
  // TODO: 구현
}

/* =====================================================================
 * [문제 6] tool_call_chunks 를 손으로 누적하기 ★어려움
 *
 * concat 을 쓰지 말고, tool_call_chunks 를 직접 index 별로 모아
 * "인자 JSON 이 완성되는 순간"을 감지하세요.
 *
 * 반환: 완성된 { index, name, args } 배열
 *
 * 규칙:
 * - tc.index 가 같은 조각끼리 args 문자열을 이어붙입니다.
 * - name 은 보통 첫 조각에만 실려 옵니다. 나중 조각의 undefined 로 덮어쓰면 안 됩니다.
 * - 매 조각마다 JSON.parse 를 시도하되, 실패는 "아직 미완성"이라는 뜻이므로
 *   에러를 던지지 말고 넘어가야 합니다.
 *
 * 힌트: model.bindTools([searchDocs]) 로 도구를 붙인 뒤 stream() 하세요.
 * ===================================================================== */
async function problem6(): Promise<Array<{ index: number; name: string; args: unknown }>> {
  // TODO: 구현
  return [];
}

/* =====================================================================
 * [문제 7] 커스텀 진행률 스트리밍
 *
 * (a) config.writer?.() 로 진행률을 3번 내보내는 도구를 새로 만드세요.
 *     예: { type: "progress", done: 1, total: 3 }
 * (b) 그 도구를 쓰는 에이전트를 만들고 streamMode: "custom" 으로 구독해
 *     "1/3", "2/3", "3/3" 을 출력하세요.
 *
 * 함정: writer 는 config 의 두 번째 인자로만 들어옵니다.
 *       도구 함수 시그니처에서 config 를 안 받으면 진행률이 조용히 사라집니다.
 * ===================================================================== */
async function problem7(): Promise<void> {
  // TODO: 구현
}

/* =====================================================================
 * [문제 8] SSE 핸들러 ★어려움
 *
 * node:http 서버를 띄워 /chat 에서 SSE 로 토큰을 흘리세요. 요구사항:
 * - Content-Type: text/event-stream, Cache-Control: no-cache
 * - 토큰은 `event: token` 으로
 * - 스트림 도중 예외가 나면 `event: error` 로 내보내고 res.end()
 *   (이미 200 을 보냈으니 500 으로 바꿀 수 없습니다)
 * - req 의 "close" 이벤트에서 AbortController 로 에이전트를 취소
 *
 * 검증: 같은 프로세스에서 fetch 로 구독해 출력해 보세요.
 * ===================================================================== */
async function problem8(): Promise<void> {
  // TODO: 구현
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  printSection("[문제 1] TTFT 측정");
  console.log(await problem1("스트리밍을 한 문장으로 설명해줘"));

  printSection("[문제 2] concat 으로 청크 합치기");
  console.log(await problem2("안녕이라고만 답해줘"));

  printSection("[문제 3] updates 로 도구 호출 추적");
  await problem3();

  printSection("[문제 4] messages 에서 model 노드만");
  await problem4();

  printSection("[문제 5] 여러 모드 동시 구독");
  await problem5();

  printSection("[문제 6] tool_call_chunks 수동 누적");
  console.log(await problem6());

  printSection("[문제 7] 커스텀 진행률");
  await problem7();

  printSection("[문제 8] SSE 핸들러");
  await problem8();
}

void ask;
void agent;

main().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
