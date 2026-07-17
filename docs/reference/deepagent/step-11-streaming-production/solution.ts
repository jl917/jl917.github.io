/**
 * Step 11 — 스트리밍과 프로덕션 · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-11-streaming-production/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 각 정답 위 주석에 "왜 이렇게 되는가"와 "여기서 흔히 틀리는 곳"이 적혀 있습니다.
 *
 * 문제만 골라 돌리려면: npx tsx .../solution.ts 1 3
 */
import "dotenv/config";

import { createDeepAgent, StateBackend } from "deepagents";
import {
  modelCallLimitMiddleware,
  modelRetryMiddleware,
  toolCallLimitMiddleware,
  tool,
} from "langchain";
import { MemorySaver, StreamChannel } from "@langchain/langgraph";
import type { ProtocolEvent, StreamTransformer } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printMessages } from "../project/src/lib/print.js";
import type { Todo } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

const only = process.argv.slice(2);
const want = (n: string) => only.length === 0 || only.includes(n);

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
 * [정답 1] 부모/자식 이벤트 구분
 *
 * 핵심은 namespace 입니다.
 *   - 부모의 모델 노드 → ["model_request:<uuid>"]
 *   - 서브에이전트     → ["tools:<tool_call_id>", "model_request:<uuid>"]
 *
 * 즉 "tools:" 로 시작하는 세그먼트가 하나라도 있으면 서브에이전트가 낸 것입니다.
 * task 도구가 서브에이전트를 스폰할 때 그 tool_call_id 가 namespace 접두사가 됩니다.
 *
 * ⚠️ 흔히 틀리는 곳: subgraphs: true 를 빼면 튜플이 [chunk, metadata] 2칸으로
 *    오고 서브에이전트 토큰은 아예 안 옵니다. 그런데 에러가 안 나서
 *    "서브에이전트가 토큰을 안 내나 보다" 라고 오해하기 쉽습니다.
 * ============================================================ */
async function q1() {
  printSection("[정답 1] 부모/자식 토큰 구분해서 세기");

  const agent = await makeResearchAgent();
  const input = {
    messages: [{ role: "user" as const, content: "'벡터 검색'을 조사해서 두 문장으로 알려줘." }],
  };

  let mainTokens = 0;
  let subTokens = 0;

  // subgraphs: true 를 주면 튜플이 [namespace, payload] 로 바뀝니다.
  // streamMode: "messages" 의 payload 는 [chunk, metadata] 튜플입니다.
  for await (const [ns, _payload] of await agent.stream(input, {
    streamMode: "messages",
    subgraphs: true,
  })) {
    if (ns.some((s) => s.startsWith("tools:"))) subTokens++;
    else mainTokens++;
  }

  console.log(`부모 토큰: ${mainTokens}, 서브에이전트 토큰: ${subTokens}`);
  console.log(
    "(모델 응답이므로 매번 다릅니다. 서브에이전트 토큰이 0이면 " +
      "모델이 위임을 안 한 것이니 프롬프트를 더 강하게 쓰세요.)",
  );
}

/* ============================================================
 * [정답 2] 서브에이전트별 도구 호출 집계
 *
 * ⚠️ 이 문제의 함정은 "병렬 소비"입니다.
 *
 *   for await (const sub of run.subagents) {
 *     for await (const call of sub.toolCalls) { ... }   // ← 여기서 막힌다
 *   }
 *
 * 이렇게 쓰면 첫 서브에이전트의 toolCalls 가 끝날 때까지 바깥 루프가
 * 다음 서브에이전트를 받지 못합니다. 병렬로 도는 서브에이전트를
 * 순차로 기다리게 되니, UI 가 "하나 끝나야 다음이 보이는" 모양이 됩니다.
 *
 * 그래서 자식 소비를 Promise 배열에 모아 두고 마지막에 Promise.all 합니다.
 * ============================================================ */
async function q2() {
  printSection("[정답 2] 서브에이전트별 도구 호출 집계");

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
  const pending: Promise<void>[] = [];

  for await (const sub of run.subagents) {
    counts[sub.name] ??= 0;
    pending.push(
      (async () => {
        for await (const _call of sub.toolCalls) {
          counts[sub.name] = (counts[sub.name] ?? 0) + 1;
        }
      })(),
    );
  }

  await Promise.all(pending);
  await run.output; // output 을 기다려야 런이 끝난 걸 보장합니다.

  console.table(counts);
}

/* ============================================================
 * [정답 3] todo 진행률 실시간 표시
 *
 * run.values 는 "매 스텝의 state 스냅샷"을 흘려줍니다.
 * todos 는 write_todos 가 바꿀 때만 달라지므로, 스냅샷마다 그대로 찍으면
 * 같은 줄이 수십 번 반복됩니다. 그래서 지문(fingerprint)을 비교합니다.
 *
 * ⚠️ run.values 는 AsyncIterable 이면서 동시에 PromiseLike 입니다.
 *    `await run.values` 하면 최종값 하나만 받고 스트림은 못 봅니다.
 *    for await 으로 돌아야 중간 스냅샷이 옵니다. 둘 다 에러 없이 동작하므로
 *    실수해도 조용히 "진행상황이 안 보이는" 결과만 남습니다.
 * ============================================================ */
async function q3() {
  printSection("[정답 3] todo 진행률 실시간 표시");

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

  let last = "";
  for await (const snap of run.values) {
    const todos = (snap as { todos?: Todo[] }).todos;
    if (!todos || todos.length === 0) continue;

    const fingerprint = todos.map((t) => `${t.status}:${t.content}`).join("|");
    if (fingerprint === last) continue;
    last = fingerprint;

    const done = todos.filter((t) => t.status === "completed").length;
    console.log(`계획 ${done}/${todos.length} ${progressBar(done, todos.length)}`);
    for (const t of todos) {
      const mark = t.status === "completed" ? "☑" : t.status === "in_progress" ? "▶" : "☐";
      console.log(`   ${mark} ${t.content}`);
    }
  }

  await run.output;
}

function progressBar(done: number, total: number, width = 20): string {
  if (total === 0) return "";
  const filled = Math.round((done / total) * width);
  return `[${"█".repeat(filled)}${"░".repeat(width - filled)}] ${Math.round((done / total) * 100)}%`;
}

/* ============================================================
 * [정답 4] 파일 변경 스트리밍
 *
 * ToolCallStream 의 모양을 정확히 알면 쉽습니다.
 *   .name    — 도구 이름 (확정값)
 *   .callId  — tool_call_id
 *   .input   — 파싱된 인자 (확정값)
 *   .output  — Promise<결과>
 *   .status  — Promise<"running" | "finished" | "error">
 *   .error   — Promise<string | undefined>
 *
 * .input 은 이미 확정된 값이라 await 이 필요 없습니다 — 스트림이 yield 되는
 * 시점이 곧 "인자 JSON 이 완성된 시점"이기 때문입니다.
 * 반면 .status / .output 은 도구가 끝나야 확정되므로 Promise 입니다.
 *
 * ⚠️ 여러 도구 호출이 병렬로 뜰 때, 여기서 `await call.status` 를 하면
 *    바깥 for await 이 막혀 다음 도구 호출을 늦게 받습니다.
 *    "쓰는 중…"을 정확한 시점에 보이려면 정답처럼 Promise 로 떼어내세요.
 * ============================================================ */
async function q4() {
  printSection("[정답 4] 파일 변경 스트리밍");

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

  const pending: Promise<void>[] = [];

  for await (const call of run.toolCalls) {
    if (call.name !== "write_file" && call.name !== "edit_file") continue;

    const path = (call.input as { file_path?: string }).file_path ?? "?";
    console.log(`✎ ${call.name} → ${path} (쓰는 중…)`);

    // await 을 바깥 루프에 두지 않고 떼어냅니다 — 병렬 쓰기를 놓치지 않기 위해.
    pending.push(
      (async () => {
        const status = await call.status;
        console.log(`${status === "finished" ? "✔" : "✖"} ${path} (${status})`);
        if (status === "error") console.log(`   사유: ${await call.error}`);
      })(),
    );
  }

  await Promise.all(pending);
  await run.output;
}

/* ============================================================
 * [정답 5] streamTransformer 로 도구 호출 로그 만들기
 *
 * StreamTransformer 의 계약:
 *   init()            → 이 반환값이 run.extensions 에 병합된다
 *   onRegister?(emit) → 합성 이벤트를 직접 쏘고 싶을 때만
 *   process(event)    → 모든 ProtocolEvent 를 본다. false 를 리턴하면 그 이벤트가
 *                       메인 로그에서 사라진다 (= 다른 소비자가 못 본다)
 *   finalize?() / fail?(err) → 정리
 *
 * ProtocolEvent 의 모양:
 *   { type: "event", seq, method, params: { namespace, timestamp, node?, data } }
 *
 * method 는 "messages" | "updates" | "values" | "tasks" | "checkpoints" |
 * "lifecycle" | "custom" ... 입니다. 도구 호출은 "tools" 채널로 옵니다.
 *
 * ⚠️ StreamChannel.local() 은 in-process 전용입니다.
 *    원격 클라이언트(SSE/WebSocket)에도 보내려면 StreamChannel.remote("이름")
 *    을 쓰세요 — 그러면 custom:<이름> 채널로 자동 전달됩니다.
 * ============================================================ */
type ToolLogEntry = { tool: string; namespace: string[] };

function createToolLogger() {
  return (): StreamTransformer<{ toolLog: StreamChannel<ToolLogEntry> }> => {
    const channel = StreamChannel.local<ToolLogEntry>();

    return {
      init: () => ({ toolLog: channel }),
      process: (event: ProtocolEvent) => {
        if (event.method === "tools") {
          const data = event.params.data as { name?: string };
          if (data?.name) {
            channel.push({ tool: data.name, namespace: [...event.params.namespace] });
          }
        }
        // 항상 true. false 를 돌려주면 run.toolCalls 같은 다른 프로젝션이 굶습니다.
        return true;
      },
    };
  };
}

async function q5() {
  printSection("[정답 5] streamTransformer 로 도구 호출 로그");

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

  // extensions 의 타입이 추론됩니다 — toolLog 가 StreamChannel<ToolLogEntry> 로 잡힙니다.
  const watch = (async () => {
    for await (const e of run.extensions.toolLog) {
      console.log(`도구 ${e.tool} @ ${JSON.stringify(e.namespace)}`);
    }
  })();

  await run.output;
  await watch; // 채널은 런이 끝나면 mux 가 자동으로 닫아 줍니다.
}

/* ============================================================
 * [정답 6] 중단 → 재개
 *
 * 세 가지가 다 맞아야 재개가 됩니다.
 *
 * 1) checkpointer 가 있어야 한다.
 *    없으면 thread_id 를 줘도 아무것도 안 남습니다 — 에러 없이 조용히.
 *
 * 2) durability 를 "sync" 로 둬야 마지막 스텝을 안 잃는다.
 *    기본은 "async" — 다음 스텝과 동시에 저장하므로, 프로세스가 급사하면
 *    마지막 체크포인트 쓰기가 날아갈 수 있습니다.
 *    "exit" 는 종료 시에만 저장하므로 중간에 죽으면 통째로 잃습니다.
 *
 * 3) 재개할 때 invoke 의 첫 인자를 null 로 준다.
 *    새 메시지를 주면 "이어서 대화"(새 사용자 턴)가 되고,
 *    null 을 주면 "멈춘 자리부터 하던 일 계속"이 됩니다.
 *    이걸 헷갈리면 에이전트가 작업을 처음부터 다시 합니다 — 돈이 두 배.
 * ============================================================ */
async function q6() {
  printSection("[정답 6] 중단 → 재개");

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

  // (1) 중단
  try {
    await agent.invoke(
      { messages: [{ role: "user" as const, content: "'HNSW 인덱스'를 조사해서 자세히 설명해줘." }] },
      { ...config, signal: controller.signal, durability: "sync", recursionLimit: 100 },
    );
    console.log("(중단 전에 끝났습니다 — 타이머를 줄여 다시 시도해 보세요)");
    return;
  } catch (err) {
    console.log(`✖ 끊김: ${(err as Error).message}`);
  }

  // (2) 체크포인트 확인
  // getState 의 반환 타입은 제네릭 해석 결과에 따라 좁혀지지 않는 경우가 있어
  // 필요한 필드만 명시적으로 꺼냅니다.
  const snapshot = (await agent.getState(config)) as unknown as {
    values: { messages?: unknown[] };
    next: string[];
  };
  console.log(`남은 messages: ${snapshot.values.messages?.length ?? 0}개`);
  console.log(`next: ${JSON.stringify(snapshot.next)}`);

  // (3) 재개 — 첫 인자가 null 인 것이 핵심입니다.
  if (snapshot.next.length === 0) {
    console.log("(재개할 노드가 없습니다)");
    return;
  }
  console.log("↻ 재개…");
  const resumed = await agent.invoke(null, { ...config, recursionLimit: 100 });
  printMessages((resumed.messages as never[]).slice(-2));
}

/* ============================================================
 * [정답 7] 비용 통제 스택
 *
 * exitBehavior 값이 미들웨어마다 다른 것이 이 문제의 함정입니다.
 *   modelCallLimitMiddleware: "error" | "end"                (기본 "end")
 *   toolCallLimitMiddleware : "continue" | "error" | "end"   (기본 "continue")
 *
 * "조용히 종료" = "end", "다른 도구는 계속" = "continue".
 * 문자열을 잘못 쓰면 타입 에러로 잡히지만, 의미를 반대로 고르면
 * 타입은 통과하고 런타임 동작만 달라집니다.
 *
 * ⚠️ 그리고 이 상한들은 "호출 횟수"만 셉니다. 토큰은 안 셉니다.
 *    서브에이전트가 1회 호출로 5만 토큰을 태우면 이 미들웨어는 못 막습니다.
 *    토큰 상한이 필요하면 practice.ts 의 createTokenBudgetMiddleware 처럼
 *    직접 만들어야 합니다.
 * ============================================================ */
async function q7() {
  printSection("[정답 7] 비용 통제 스택");

  const agent = await createDeepAgent({
    model: MODEL, // 오케스트레이터: 비싼 모델
    tools: [searchDocs],
    subagents: [
      {
        name: "researcher",
        description: "단순 조사.",
        systemPrompt: "search_docs 로 조사하고 2문장으로 요약하세요.",
        tools: [searchDocs],
        model: CHEAP_MODEL, // 서브에이전트: 싼 모델
      },
    ] as const,
    middleware: [
      modelCallLimitMiddleware({ runLimit: 15, threadLimit: 50, exitBehavior: "end" }),
      toolCallLimitMiddleware({ toolName: "search_docs", runLimit: 5, exitBehavior: "continue" }),
      modelRetryMiddleware({
        maxRetries: 3,
        backoffFactor: 2,
        initialDelayMs: 1000,
        jitter: true,
      }),
    ],
    systemPrompt: "조사는 researcher 서브에이전트에게 위임하세요.",
  });

  const result = await agent.invoke(
    { messages: [{ role: "user" as const, content: "'ANN 검색'을 조사해줘." }] },
    { recursionLimit: 100 },
  );
  printMessages((result.messages as never[]).slice(-2));
}

/* ============================================================
 * [정답 8] recursionLimit 함정 재현
 *
 * 에러 메시지 (실제 출력):
 *   GraphRecursionError: Recursion limit of 5 reached without hitting a stop
 *   condition. You can increase the limit by setting the "recursionLimit"
 *   config key.
 *
 * 왜 Deep Agent 는 recursionLimit 이 훨씬 커야 하나?
 *
 * recursionLimit 은 "그래프 슈퍼스텝의 최대 횟수"입니다. Deep Agent 는
 * 한 번의 사용자 요청을 처리하는 데 아래를 전부 슈퍼스텝으로 소비합니다.
 *
 *   before_agent 미들웨어(파일시스템 등) → 계획 수립(write_todos) →
 *   모델 호출 → task 도구로 서브에이전트 스폰 →
 *   서브에이전트의 전체 루프(그 안에서 또 모델 호출 + 도구 호출 N회) →
 *   결과 회수 → todo 갱신 → 다음 서브에이전트 → … → 최종 종합
 *
 * 즉 서브에이전트 하나가 도는 동안에도 부모의 슈퍼스텝이 계속 소진됩니다.
 * 기본값 25는 "계획 세우고 첫 서브에이전트 부르다가" 끝나는 수준입니다.
 * LangGraph Platform 이 Deep Agent 용으로 10,000 을 기본값으로 쓰는 이유입니다.
 *
 * ⚠️ 이 함정이 고약한 이유: 에러가 "계획 단계"에서 나기 때문에
 *    로그만 보면 "모델이 일을 안 하고 멈췄다" 처럼 보입니다.
 *    실제로는 그래프가 예산을 다 쓴 것입니다.
 * ============================================================ */
async function q8() {
  printSection("[정답 8] recursionLimit 함정 재현");

  const agent = await makeResearchAgent();

  try {
    await agent.invoke(
      {
        messages: [
          {
            role: "user" as const,
            content: "'벡터 검색', 'BM25', '하이브리드 검색'을 각각 조사하고 비교 보고서를 써줘.",
          },
        ],
      },
      { recursionLimit: 5 },
    );
    console.log("(에러 없이 끝났습니다 — 모델이 위임을 안 했을 수 있습니다)");
  } catch (err) {
    console.log(`✖ ${(err as Error).name}: ${(err as Error).message}`);
  }

  // 비교: 넉넉하게 주면 통과합니다.
  const ok = await agent.invoke(
    { messages: [{ role: "user" as const, content: "'BM25'를 한 문장으로 정의해줘." }] },
    { recursionLimit: 100 },
  );
  console.log(`✔ recursionLimit 100 → messages ${(ok.messages as unknown[]).length}개`);
}

/* ===== 실행 ===== */

async function main() {
  if (!process.env["ANTHROPIC_API_KEY"]) {
    console.error("ANTHROPIC_API_KEY 가 없습니다. project/.env 를 확인하세요.");
    process.exit(1);
  }

  const answers: [string, () => Promise<void>][] = [
    ["1", q1],
    ["2", q2],
    ["3", q3],
    ["4", q4],
    ["5", q5],
    ["6", q6],
    ["7", q7],
    ["8", q8],
  ];

  for (const [n, fn] of answers) {
    if (!want(n)) continue;
    await fn();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
