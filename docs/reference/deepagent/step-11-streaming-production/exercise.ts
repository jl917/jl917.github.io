/**
 * Step 11 — 스트리밍과 프로덕션 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-11-streaming-production/exercise.ts
 *
 * 각 [문제 N] 아래의 빈 곳을 채우세요.
 * 정답은 solution.ts 에 있습니다 — 먼저 스스로 풀어 보세요.
 *
 * 문제만 골라 돌리려면 인자를 주세요:
 *   npx tsx .../exercise.ts 1 3
 */
import "dotenv/config";

import { createDeepAgent, StateBackend } from "deepagents";
import { tool } from "langchain";
import { MemorySaver, StreamChannel } from "@langchain/langgraph";
import type { ProtocolEvent, StreamTransformer } from "@langchain/langgraph";
import * as z from "zod";

import { printSection } from "../project/src/lib/print.js";
import type { Todo } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

const only = process.argv.slice(2);
const want = (n: string) => only.length === 0 || only.includes(n);

/** 문제들이 공유하는 가짜 검색 도구. */
const searchDocs = tool(
  async ({ query }) => {
    await new Promise((r) => setTimeout(r, 200));
    return `"${query}" 검색 결과 3건:\n- ${query}의 정의\n- ${query}의 사례\n- ${query}의 한계`;
  },
  {
    name: "search_docs",
    description: "사내 문서를 키워드로 검색합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

/** 문제들이 공유하는 서브에이전트 2개짜리 리서치 에이전트. */
async function makeResearchAgent() {
  return createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    subagents: [
      {
        name: "researcher",
        description: "주제 하나를 조사해 요약을 돌려줍니다.",
        systemPrompt: "search_docs 로 조사한 뒤 3문장 이내로 요약하세요.",
        tools: [searchDocs],
        model: CHEAP_MODEL,
      },
      {
        name: "writer",
        description: "조사 결과로 보고서 초안을 씁니다.",
        systemPrompt: "주어진 재료로만 간결한 보고서를 씁니다.",
        model: MODEL,
      },
    ] as const,
    systemPrompt:
      "당신은 리서치 오케스트레이터입니다. 조사는 researcher 에게, 글쓰기는 writer 에게 위임하세요.",
  });
}

/* ============================================================
 * [문제 1] 부모/자식 이벤트 구분
 *
 * agent.stream(..., { streamMode: "messages", subgraphs: true }) 로
 * 토큰을 받으면서, 그 토큰이 "부모(오케스트레이터)의 것"인지
 * "서브에이전트의 것"인지 구분해 각각 몇 개인지 세세요.
 *
 * 힌트: 튜플이 [namespace, chunk_and_metadata] 로 옵니다.
 *       namespace 의 세그먼트 중 "tools:" 로 시작하는 게 있으면 서브에이전트입니다.
 * ============================================================ */
async function q1() {
  printSection("[문제 1] 부모/자식 토큰 구분해서 세기");

  const agent = await makeResearchAgent();
  const input = {
    messages: [{ role: "user" as const, content: "'벡터 검색'을 조사해서 두 문장으로 알려줘." }],
  };

  let mainTokens = 0;
  let subTokens = 0;

  // TODO: agent.stream 을 streamMode: "messages", subgraphs: true 로 돌면서
  //       mainTokens / subTokens 를 세세요.

  console.log(`부모 토큰: ${mainTokens}, 서브에이전트 토큰: ${subTokens}`);
}

/* ============================================================
 * [문제 2] 서브에이전트별 도구 호출 집계
 *
 * streamEvents(..., { version: "v3" }) 의 run.subagents 를 써서,
 * 서브에이전트 이름별로 "도구를 몇 번 불렀는지"를 집계해 표로 출력하세요.
 *
 * 힌트: run.subagents 의 각 항목은 .name, .toolCalls, .output 을 가집니다.
 *       자식 스트림은 반드시 병렬로 소비해야 합니다 — 순차로 await 하면
 *       뒤에 오는 서브에이전트를 놓칩니다.
 * ============================================================ */
async function q2() {
  printSection("[문제 2] 서브에이전트별 도구 호출 집계");

  const agent = await makeResearchAgent();
  const run = await agent.streamEvents(
    {
      messages: [
        { role: "user" as const, content: "'벡터 검색'과 'BM25'를 각각 조사해서 비교해줘." },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  const counts: Record<string, number> = {};

  // TODO: run.subagents 를 순회하면서 counts[서브에이전트이름] 를 증가시키세요.
  //       마지막에 await run.output 하는 것도 잊지 마세요.

  console.table(counts);
}

/* ============================================================
 * [문제 3] todo 진행률 실시간 표시
 *
 * run.values 를 구독해서 todos 가 바뀔 때마다
 *   계획 2/5 [████████░░░░░░░░░░░░] 40%
 * 형태로 한 줄 출력하세요. 같은 내용이면 다시 찍지 마세요.
 *
 * 힌트: run.values 는 async iterable 이면서 PromiseLike 입니다.
 *       스냅샷의 todos 는 { content, status } 배열입니다.
 * ============================================================ */
async function q3() {
  printSection("[문제 3] todo 진행률 실시간 표시");

  const agent = await makeResearchAgent();
  const run = await agent.streamEvents(
    {
      messages: [
        {
          role: "user" as const,
          content:
            "'벡터 검색', 'BM25', '하이브리드 검색' 세 가지를 각각 조사하고 비교 보고서를 써줘. 먼저 계획부터 세워.",
        },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  // TODO: run.values 를 순회하며 todos 진행률을 출력하세요.
  //       중복 출력을 막는 로직도 넣으세요.

  await run.output;
}

/** 진행률 막대 — 문제 3에서 쓰세요. */
function progressBar(done: number, total: number, width = 20): string {
  if (total === 0) return "";
  const filled = Math.round((done / total) * width);
  return `[${"█".repeat(filled)}${"░".repeat(width - filled)}] ${Math.round((done / total) * 100)}%`;
}

/* ============================================================
 * [문제 4] 파일 변경 스트리밍
 *
 * 에이전트가 write_file / edit_file 을 호출할 때마다
 *   ✎ write_file → /notes/a.md (쓰는 중…)
 *   ✔ /notes/a.md (finished)
 * 처럼 "시작"과 "끝"을 각각 출력하세요.
 *
 * 힌트: run.toolCalls 의 각 항목은 .name, .input, .status(Promise) 를 가집니다.
 *       .input 은 이미 확정된 값이고, .status 는 await 해야 합니다.
 * ============================================================ */
async function q4() {
  printSection("[문제 4] 파일 변경 스트리밍");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    systemPrompt: "요청받은 문서를 가상 파일시스템에 write_file 로 저장하세요.",
  });

  const run = await agent.streamEvents(
    {
      messages: [
        {
          role: "user" as const,
          content: "/notes/a.md 와 /notes/b.md 에 각각 3줄짜리 메모를 써줘.",
        },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  // TODO: run.toolCalls 를 순회하며 파일 쓰기 도구만 골라 시작/끝을 출력하세요.

  await run.output;
}

/* ============================================================
 * [문제 5] streamTransformer 로 도구 호출 로그 만들기
 *
 * ProtocolEvent 를 보고 "도구 호출이 시작될 때마다" StreamChannel 에
 * { tool, namespace } 를 push 하는 StreamTransformer 를 만드세요.
 * 그리고 run.extensions.toolLog 로 소비해 출력하세요.
 *
 * 힌트: event.method === "tools" 인 이벤트가 도구 채널입니다.
 *       무엇이 들어오는지 모르겠으면 일단 전부 console.log 해서 관찰하세요.
 *       process() 는 반드시 true 를 돌려주세요(false 면 이벤트가 사라집니다).
 * ============================================================ */
type ToolLogEntry = { tool: string; namespace: string[] };

function createToolLogger() {
  return (): StreamTransformer<{ toolLog: StreamChannel<ToolLogEntry> }> => {
    const channel = StreamChannel.local<ToolLogEntry>();

    return {
      init: () => ({ toolLog: channel }),
      process: (_event: ProtocolEvent) => {
        // TODO: 도구 이벤트를 골라 channel.push({ tool, namespace }) 하세요.
        return true;
      },
    };
  };
}

async function q5() {
  printSection("[문제 5] streamTransformer 로 도구 호출 로그");

  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    streamTransformers: [createToolLogger()],
    systemPrompt: "요청을 search_docs 로 조사해서 답하세요.",
  });

  const run = await agent.streamEvents(
    { messages: [{ role: "user" as const, content: "'HNSW'를 조사해줘." }] },
    { version: "v3", recursionLimit: 100 },
  );

  // TODO: run.extensions.toolLog 를 소비해 출력하세요.
  //       (run.output 과 병렬로 소비해야 합니다)

  await run.output;
}

/* ============================================================
 * [문제 6] 중단 → 재개
 *
 * MemorySaver 체크포인터를 붙인 에이전트를 AbortSignal 로 중간에 끊고,
 * 같은 thread_id 로 재개해서 끝까지 완료시키세요.
 *
 * 확인할 것:
 *   - 중단 후 agent.getState(config) 의 next 가 비어 있지 않다
 *   - 재개할 때 invoke 의 첫 인자로 무엇을 줘야 "이어서" 되는가?
 *
 * 힌트: durability 옵션을 무엇으로 둬야 마지막 스텝을 안 잃을까요?
 * ============================================================ */
async function q6() {
  printSection("[문제 6] 중단 → 재개");

  const checkpointer = new MemorySaver();
  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    checkpointer,
    systemPrompt: "요청을 조사해서 자세히 답하세요.",
  });

  const config = { configurable: { thread_id: `q6-${Date.now()}` } };
  const controller = new AbortController();
  setTimeout(() => controller.abort(new Error("강제 중단")), 2500);

  // TODO: 1) signal 과 durability 를 준 invoke 를 try/catch 로 감싸 중단시키세요.
  //       2) agent.getState(config) 로 체크포인트가 남았는지 확인하세요.
  //       3) 같은 config 로 재개하세요.

  void config;
  void controller;
}

/* ============================================================
 * [문제 7] 비용 통제 스택
 *
 * 다음 조건을 모두 만족하는 에이전트를 만드세요.
 *   - 오케스트레이터는 MODEL, 서브에이전트 researcher 는 CHEAP_MODEL
 *   - 모델 호출은 실행 1회당 15번, 스레드 전체로 50번까지 (초과 시 조용히 종료)
 *   - search_docs 는 실행 1회당 5번까지 (초과해도 다른 도구는 계속 동작)
 *   - 모델 호출 실패 시 최대 3번 재시도, 지수 백오프 + 지터
 *
 * 힌트: modelCallLimitMiddleware / toolCallLimitMiddleware / modelRetryMiddleware.
 *       exitBehavior 값이 미들웨어마다 다릅니다 — 타입을 확인하세요.
 * ============================================================ */
async function q7() {
  printSection("[문제 7] 비용 통제 스택");

  // TODO: createDeepAgent 로 위 조건을 만족하는 에이전트를 만들고 invoke 하세요.
}

/* ============================================================
 * [문제 8] recursionLimit 함정 재현
 *
 * recursionLimit 을 아주 낮게(예: 5) 주고 서브에이전트를 여러 개 쓰는
 * 요청을 던져서, 어떤 에러가 나는지 직접 확인하세요.
 * 그리고 그 에러 메시지를 주석으로 적으세요.
 *
 * 질문: 왜 Deep Agent 는 일반 에이전트보다 recursionLimit 이 훨씬 커야 하나요?
 * → (여기에 답을 적으세요)
 * ============================================================ */
async function q8() {
  printSection("[문제 8] recursionLimit 함정 재현");

  const agent = await makeResearchAgent();

  // TODO: recursionLimit: 5 로 invoke 하고 try/catch 로 에러를 잡아 출력하세요.
  void agent;
}

/* ===== 실행 ===== */

async function main() {
  if (!process.env["ANTHROPIC_API_KEY"]) {
    console.error("ANTHROPIC_API_KEY 가 없습니다. project/.env 를 확인하세요.");
    process.exit(1);
  }

  const problems: [string, () => Promise<void>][] = [
    ["1", q1],
    ["2", q2],
    ["3", q3],
    ["4", q4],
    ["5", q5],
    ["6", q6],
    ["7", q7],
    ["8", q8],
  ];

  for (const [n, fn] of problems) {
    if (!want(n)) continue;
    await fn();
  }
}

// 미사용 경고를 막기 위한 참조 (문제를 풀면서 실제로 쓰게 됩니다)
void progressBar;
void ({} as Todo);

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
