/**
 * Step 11 — 스트리밍과 프로덕션
 * 실행: npx tsx docs/reference/deepagent/step-11-streaming-production/practice.ts
 *
 * 본문 11-1 ~ 11-11 의 예제를 순서대로 담았습니다.
 * 절 전체를 다 돌리면 모델 호출이 10회 이상 발생합니다(= 돈이 나갑니다).
 * 특정 절만 돌리려면 인자를 주세요:
 *   npx tsx .../practice.ts 11-3
 *   npx tsx .../practice.ts 11-3 11-4 11-11
 */
import "dotenv/config";

import { createDeepAgent, StateBackend } from "deepagents";
import {
  modelCallLimitMiddleware,
  modelRetryMiddleware,
  toolCallLimitMiddleware,
  toolRetryMiddleware,
  tool,
} from "langchain";
import { MemorySaver, StreamChannel } from "@langchain/langgraph";
import type { ProtocolEvent, StreamTransformer } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printMessages, printTodos, printFiles } from "../project/src/lib/print.js";
import type { Todo } from "../project/src/lib/print.js";

/* ===== 공용 설정 ===== */

// 기본 모델. OpenAI 로 바꾸려면 "openai:gpt-5.5" 로만 바꾸면 됩니다
// (@langchain/openai 설치 + OPENAI_API_KEY 필요).
const MODEL = "anthropic:claude-sonnet-4-6";

// 서브에이전트용 저가 모델. 11-9 에서 왜 이렇게 나누는지 설명합니다.
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

/** 인자로 준 절만 실행. 인자가 없으면 전부 실행. */
const only = process.argv.slice(2);
const want = (id: string) => only.length === 0 || only.includes(id);

/** 이 스텝의 예제가 공통으로 쓰는 가짜 검색 도구. 네트워크를 안 씁니다. */
const searchDocs = tool(
  async ({ query }) => {
    // 실제 검색기를 붙이는 자리입니다. 여기서는 결정적인 더미를 돌려줍니다.
    await new Promise((r) => setTimeout(r, 200));
    return `"${query}" 검색 결과 3건:\n- ${query}의 정의와 배경\n- ${query}의 대표 사례\n- ${query}의 한계`;
  },
  {
    name: "search_docs",
    description: "사내 문서를 키워드로 검색합니다. 조사가 필요할 때 사용하세요.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

/** 11-3, 11-4, 11-11 이 공유하는 서브에이전트 2개짜리 리서치 에이전트. */
async function makeResearchAgent() {
  return createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    subagents: [
      {
        name: "researcher",
        description:
          "주제 하나를 조사해 요약을 돌려줍니다. 조사할 주제가 여러 개면 병렬로 여러 번 부르세요.",
        systemPrompt:
          "당신은 조사 전문가입니다. search_docs 로 조사한 뒤 3문장 이내로 요약해 돌려주세요.",
        tools: [searchDocs],
        model: CHEAP_MODEL,
      },
      {
        name: "writer",
        description: "조사 결과를 받아 한국어 보고서 초안을 씁니다.",
        systemPrompt: "당신은 테크니컬 라이터입니다. 주어진 재료로만 간결한 보고서를 씁니다.",
        model: MODEL,
      },
    ] as const,
    systemPrompt:
      "당신은 리서치 오케스트레이터입니다. 조사는 researcher 서브에이전트에게, " +
      "글쓰기는 writer 서브에이전트에게 위임하세요. 직접 다 하지 마세요.",
  });
}

/* ===== [11-1] Deep Agent 스트리밍이 어려운 이유 ===== */

async function step11_1() {
  printSection("[11-1] 이벤트가 계층적으로 온다 — subgraphs 를 끄면 자식이 안 보인다");

  const agent = await makeResearchAgent();
  const input = { messages: [{ role: "user" as const, content: "'벡터 검색'을 조사해서 알려줘." }] };

  // (A) subgraphs 기본값(false) — 서브에이전트 내부는 통째로 안 보입니다.
  let aCount = 0;
  for await (const _chunk of await agent.stream(input, { streamMode: "updates" })) {
    aCount++;
  }
  console.log(`(A) subgraphs 없음  → 이벤트 ${aCount}건`);

  // (B) subgraphs: true — 서브에이전트 내부까지 열립니다. 튜플 모양이 바뀝니다.
  let bCount = 0;
  const namespaces = new Set<string>();
  for await (const [ns, _chunk] of await agent.stream(input, {
    streamMode: "updates",
    subgraphs: true,
  })) {
    bCount++;
    namespaces.add(JSON.stringify(ns));
  }
  console.log(`(B) subgraphs: true → 이벤트 ${bCount}건, 서로 다른 namespace ${namespaces.size}개`);
  for (const ns of namespaces) console.log(`    ${ns}`);
}

/* ===== [11-2] streamMode 별 관찰 ===== */

async function step11_2() {
  printSection("[11-2] streamMode 별로 무엇이 나오나");

  const agent = await makeResearchAgent();
  const input = { messages: [{ role: "user" as const, content: "'RAG'를 한 문장으로 정의해줘." }] };

  // updates: 노드가 하나 끝날 때마다 "그 노드가 바꾼 것"만.
  printSection("[11-2] streamMode: updates");
  for await (const [ns, chunk] of await agent.stream(input, {
    streamMode: "updates",
    subgraphs: true,
  })) {
    // chunk 의 키가 곧 "방금 실행된 노드 이름"입니다.
    console.log(`${JSON.stringify(ns).padEnd(48)} ${Object.keys(chunk).join(", ")}`);
  }

  // values: 매 스텝의 전체 state 스냅샷. 크고 느립니다 — 크기만 재 봅시다.
  printSection("[11-2] streamMode: values (스냅샷 크기만)");
  let i = 0;
  for await (const chunk of await agent.stream(input, { streamMode: "values" })) {
    const msgs = (chunk as { messages?: unknown[] }).messages ?? [];
    console.log(`스냅샷 #${++i}: messages ${msgs.length}개`);
  }

  // messages: 토큰. [chunk, metadata] 튜플이 옵니다.
  printSection("[11-2] streamMode: messages (토큰)");
  let tokens = 0;
  for await (const [chunk, metadata] of await agent.stream(input, { streamMode: "messages" })) {
    tokens++;
    if (tokens === 1) {
      // metadata 에 langgraph_node, langgraph_step 등이 들어 있습니다.
      console.log("첫 청크의 metadata 키:", Object.keys(metadata as object).slice(0, 6).join(", "));
    }
    const text = (chunk as { text?: string }).text;
    if (typeof text === "string") process.stdout.write(text);
  }
  console.log(`\n(총 ${tokens} 청크)`);

  // 여러 모드 동시 — 튜플이 [namespace, mode, data] 3칸으로 바뀝니다.
  printSection("[11-2] streamMode 여러 개 + subgraphs");
  const seen: Record<string, number> = {};
  for await (const [ns, mode, _data] of await agent.stream(input, {
    streamMode: ["updates", "messages", "custom"],
    subgraphs: true,
  })) {
    const key = `${mode} @ ${ns.length === 0 ? "(root)" : ns[0]}`;
    seen[key] = (seen[key] ?? 0) + 1;
  }
  console.table(seen);
}

/* ===== [11-3] 서브에이전트 스트리밍 — 자식과 부모를 구분해서 보여주기 ===== */

async function step11_3() {
  printSection("[11-3] streamEvents v3 — run.subagents 로 부모/자식 분리");

  const agent = await makeResearchAgent();

  const run = await agent.streamEvents(
    {
      messages: [
        {
          role: "user" as const,
          content: "'벡터 검색'과 '전문 검색'을 각각 조사한 뒤, 비교 보고서를 써줘.",
        },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  // 부모(오케스트레이터)의 메시지. run.messages 에는 자식 토큰이 섞이지 않습니다.
  const parent = (async () => {
    for await (const msg of run.messages) {
      let text = "";
      for await (const token of msg.text) text += token;
      if (text.trim()) console.log(`\n[부모] ${text.trim().slice(0, 200)}`);
    }
  })();

  // 자식(서브에이전트). name 으로 어느 서브에이전트인지 알 수 있습니다.
  const children = (async () => {
    const pending: Promise<void>[] = [];
    for await (const sub of run.subagents) {
      console.log(`\n[자식 시작] ${sub.name} (cause=${JSON.stringify(sub.cause)})`);
      pending.push(
        (async () => {
          // 자식의 도구 호출은 자식 핸들에만 나옵니다 — 부모 run.toolCalls 에는 안 나옵니다.
          for await (const call of sub.toolCalls) {
            console.log(`  [${sub.name}] 도구 ${call.name}(${JSON.stringify(call.input)})`);
            console.log(`  [${sub.name}] → ${await call.status}`);
          }
        })(),
      );
      pending.push(
        (async () => {
          for await (const msg of sub.messages) {
            let text = "";
            for await (const token of msg.text) text += token;
            if (text.trim()) console.log(`  [${sub.name}] ${text.trim().slice(0, 120)}`);
          }
        })(),
      );
    }
    await Promise.all(pending);
  })();

  // 부모의 도구 호출. task(서브에이전트 스폰)가 여기 보입니다.
  const parentTools = (async () => {
    for await (const call of run.toolCalls) {
      console.log(`\n[부모] 도구 ${call.name} 시작`);
    }
  })();

  // ⚠️ 반드시 모든 프로젝션을 병렬로 소비한 뒤 output 을 기다립니다.
  await Promise.all([parent, children, parentTools]);
  const state = await run.output;
  console.log("\n최종 messages:", (state.messages as unknown[]).length, "개");
}

/* ===== [11-4] todo 리스트 실시간 표시 ===== */

async function step11_4() {
  printSection("[11-4] todos 를 실시간으로 — run.values 를 구독한다");

  const agent = await makeResearchAgent();

  const run = await agent.streamEvents(
    {
      messages: [
        {
          role: "user" as const,
          content:
            "'벡터 검색', '전문 검색', '하이브리드 검색' 세 가지를 각각 조사하고 비교 보고서를 써줘. " +
            "먼저 계획부터 세워.",
        },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  // run.values 는 async iterable(스냅샷 스트림)이면서 동시에 PromiseLike(최종값)입니다.
  // 여기서는 iterable 쪽을 씁니다.
  let lastRendered = "";
  for await (const snapshot of run.values) {
    const todos = (snapshot as { todos?: Todo[] }).todos;
    if (!todos || todos.length === 0) continue;

    // 같은 내용을 반복해서 다시 그리지 않도록 지문(fingerprint)을 비교합니다.
    const fingerprint = todos.map((t) => `${t.status}:${t.content}`).join("|");
    if (fingerprint === lastRendered) continue;
    lastRendered = fingerprint;

    const done = todos.filter((t) => t.status === "completed").length;
    console.log(`\n── 계획 (${done}/${todos.length}) ${progressBar(done, todos.length)}`);
    printTodos(todos);
  }

  const state = await run.output;
  console.log("\n최종 todos:");
  printTodos((state as { todos?: Todo[] }).todos);
}

/** 진행률 막대. 11-11 CLI 프론트엔드에서도 재사용합니다. */
function progressBar(done: number, total: number, width = 20): string {
  if (total === 0) return "";
  const filled = Math.round((done / total) * width);
  return `[${"█".repeat(filled)}${"░".repeat(width - filled)}] ${Math.round((done / total) * 100)}%`;
}

/* ===== [11-5] 파일 변경 스트리밍 ===== */

async function step11_5() {
  printSection("[11-5] 에이전트가 지금 무슨 파일을 쓰고 있나");

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
          content:
            "/notes/vector.md 에 벡터 검색 소개를, /notes/keyword.md 에 키워드 검색 소개를 " +
            "각각 5줄 정도로 써줘. 그 다음 /notes/README.md 에 두 파일 목록을 정리해줘.",
        },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  // (A) 도구 호출 단위로 보기 — 어떤 경로에 쓰는지 즉시 알 수 있습니다.
  const byToolCall = (async () => {
    for await (const call of run.toolCalls) {
      if (call.name === "write_file" || call.name === "edit_file") {
        const path = (call.input as { file_path?: string }).file_path ?? "?";
        console.log(`✎ ${call.name} → ${path} (쓰는 중…)`);
        const status = await call.status;
        console.log(`  ${status === "finished" ? "✔" : "✖"} ${path} (${status})`);
      }
    }
  })();

  // (B) state 스냅샷으로 보기 — 파일 "전체 목록"의 변화를 추적합니다.
  const byState = (async () => {
    let known = new Set<string>();
    for await (const snapshot of run.values) {
      const files = (snapshot as { files?: Record<string, unknown> }).files ?? {};
      const now = new Set(Object.keys(files));
      for (const p of now) if (!known.has(p)) console.log(`  + 새 파일: ${p}`);
      for (const p of known) if (!now.has(p)) console.log(`  - 삭제됨: ${p}`);
      known = now;
    }
  })();

  await Promise.all([byToolCall, byState]);
  const state = await run.output;
  console.log("\n최종 파일:");
  printFiles((state as { files?: Record<string, unknown> }).files, true);
}

/* ===== [11-6] streamTransformers ===== */

/**
 * 토큰 사용량을 세는 커스텀 StreamTransformer.
 *
 * init() 이 돌려준 객체가 run.extensions 에 병합됩니다.
 * process() 는 모든 ProtocolEvent 를 봅니다 — false 를 돌려주면 그 이벤트가
 * 메인 로그에서 사라지므로, 웬만하면 true 를 돌려주세요.
 */
function createUsageTracker() {
  return (): StreamTransformer<{ usageLog: StreamChannel<UsageEntry> }> => {
    const channel = StreamChannel.local<UsageEntry>();
    let input = 0;
    let output = 0;

    return {
      init: () => ({ usageLog: channel }),
      process: (event: ProtocolEvent) => {
        if (event.method !== "messages") return true;
        const data = event.params.data as { event?: string; usage?: UsageLike };
        if (data?.event !== "message-finish" || !data.usage) return true;

        const ns = event.params.namespace;
        // namespace 에 "tools:" 세그먼트가 있으면 서브에이전트가 낸 토큰입니다.
        const isSub = ns.some((s) => s.startsWith("tools:"));
        input += data.usage.input_tokens ?? 0;
        output += data.usage.output_tokens ?? 0;
        channel.push({
          who: isSub ? "subagent" : "main",
          namespace: ns,
          inputTokens: data.usage.input_tokens ?? 0,
          outputTokens: data.usage.output_tokens ?? 0,
        });
        return true;
      },
      finalize: () => {
        console.log(`\n[usageTracker] 누적 input=${input} output=${output}`);
      },
    };
  };
}

type UsageLike = { input_tokens?: number; output_tokens?: number };
type UsageEntry = {
  who: "main" | "subagent";
  namespace: string[];
  inputTokens: number;
  outputTokens: number;
};

async function step11_6() {
  printSection("[11-6] streamTransformers — run.extensions 에 내 프로젝션 얹기");

  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    subagents: [
      {
        name: "researcher",
        description: "주제 하나를 조사합니다.",
        systemPrompt: "search_docs 로 조사하고 2문장으로 요약하세요.",
        tools: [searchDocs],
        model: CHEAP_MODEL,
      },
    ] as const,
    streamTransformers: [createUsageTracker()],
    systemPrompt: "조사는 researcher 서브에이전트에게 위임하세요.",
  });

  const run = await agent.streamEvents(
    { messages: [{ role: "user" as const, content: "'BM25'를 조사해서 알려줘." }] },
    { version: "v3", recursionLimit: 100 },
  );

  // extensions 는 타입까지 추론됩니다 — usageLog 가 StreamChannel<UsageEntry> 로 잡힙니다.
  const watch = (async () => {
    for await (const e of run.extensions.usageLog) {
      console.log(
        `${e.who === "main" ? "[본체]  " : "[서브]  "} in=${e.inputTokens} out=${e.outputTokens} ns=${JSON.stringify(e.namespace)}`,
      );
    }
  })();

  await run.output;
  await watch;
}

/* ===== [11-7] 내결함성 — 영속 체크포인터 + 재개 ===== */

async function step11_7() {
  printSection("[11-7] 죽었다가 되살아나기 — 체크포인터 + 재개");

  // ⚠️ MemorySaver 는 프로세스 메모리입니다. 재시작하면 전부 사라집니다.
  //    프로덕션에서는 Postgres/Redis 등 영속 체크포인터를 쓰세요(본문 참고).
  const checkpointer = new MemorySaver();

  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    checkpointer,
    systemPrompt: "요청을 조사해서 답하세요.",
  });

  const threadId = `demo-${Date.now()}`;
  const config = { configurable: { thread_id: threadId } };

  // (1) 중간에 강제로 끊기 — AbortSignal 로 "죽음"을 흉내냅니다.
  const controller = new AbortController();
  setTimeout(() => controller.abort(new Error("강제 중단(배포로 인한 인스턴스 종료 상황)")), 2500);

  try {
    await agent.invoke(
      { messages: [{ role: "user" as const, content: "'HNSW 인덱스'를 조사해서 자세히 설명해줘." }] },
      {
        ...config,
        signal: controller.signal,
        // durability: "sync" — 다음 스텝이 시작되기 전에 체크포인트를 저장합니다.
        // 기본 "async" 는 다음 스텝과 동시에 저장하므로, 급사하면 마지막 1스텝을 잃을 수 있습니다.
        durability: "sync",
        recursionLimit: 100,
      },
    );
    console.log("(중단되지 않고 끝났습니다 — 타이머를 줄여 다시 시도해 보세요)");
  } catch (err) {
    console.log(`✖ 실행이 끊겼습니다: ${(err as Error).message}`);
  }

  // (2) 체크포인트가 남아 있는지 확인.
  // getState 의 반환 타입은 제네릭 해석 결과에 따라 좁혀지지 않는 경우가 있어
  // 필요한 필드만 명시적으로 꺼냅니다. 런타임 동작에는 영향이 없습니다.
  const snapshot = (await agent.getState(config)) as unknown as {
    values: { messages?: unknown[] };
    next: string[];
  };
  console.log(`체크포인트에 남은 messages: ${snapshot.values.messages?.length ?? 0}개`);
  console.log(`다음에 실행할 노드(next): ${JSON.stringify(snapshot.next)}`);

  // (3) 재개 — 같은 thread_id 로 input 을 null 로 주면 "멈춘 자리부터" 이어갑니다.
  //     새 메시지를 주면 "이어서 대화"가 되고, null 을 주면 "하던 일 계속"입니다.
  if (snapshot.next.length > 0) {
    console.log("\n↻ 같은 thread_id 로 재개합니다…");
    const resumed = await agent.invoke(null, { ...config, recursionLimit: 100 });
    printMessages((resumed.messages as never[]).slice(-3));
  } else {
    console.log("\n(재개할 노드가 없습니다 — 이미 완료된 상태입니다)");
  }
}

/* ===== [11-8] 프로덕션 체크리스트 — 관측 훅 ===== */

async function step11_8() {
  printSection("[11-8] 관측 — 서브에이전트별 지연/토큰을 실제로 재 본다");

  const agent = await makeResearchAgent();

  const t0 = Date.now();
  const run = await agent.streamEvents(
    {
      messages: [
        { role: "user" as const, content: "'벡터 검색'과 'BM25'를 각각 조사해서 비교해줘." },
      ],
    },
    { version: "v3", recursionLimit: 100 },
  );

  type Span = { name: string; startMs: number; endMs?: number; toolCalls: number };
  const spans: Span[] = [];

  await (async () => {
    const pending: Promise<void>[] = [];
    for await (const sub of run.subagents) {
      const span: Span = { name: sub.name, startMs: Date.now() - t0, toolCalls: 0 };
      spans.push(span);
      pending.push(
        (async () => {
          for await (const _c of sub.toolCalls) span.toolCalls++;
        })(),
      );
      pending.push(
        (async () => {
          await sub.output;
          span.endMs = Date.now() - t0;
        })(),
      );
    }
    await Promise.all(pending);
  })();

  await run.output;
  const totalMs = Date.now() - t0;

  console.log(`\n총 소요: ${totalMs}ms`);
  console.table(
    spans.map((s) => ({
      서브에이전트: s.name,
      시작: `${s.startMs}ms`,
      종료: `${s.endMs ?? "?"}ms`,
      소요: s.endMs ? `${s.endMs - s.startMs}ms` : "?",
      도구호출: s.toolCalls,
    })),
  );
  console.log(
    "\n서브에이전트 구간의 합이 총 소요보다 크면 병렬로 돈 것이고, " +
      "작으면 오케스트레이터가 순차로 기다린 것입니다.",
  );
}

/* ===== [11-9] 비용 통제 — 모델 티어링 + 예산 미들웨어 ===== */

/**
 * 토큰 예산 미들웨어.
 *
 * 내장 modelCallLimitMiddleware 는 "호출 횟수"를 세지 "토큰"을 세지 않습니다.
 * 서브에이전트가 한 번 호출로 5만 토큰을 쓰면 횟수 제한은 못 막습니다.
 * 그래서 토큰 기준 상한이 필요하면 직접 만듭니다.
 */
function createTokenBudgetMiddleware(maxTokens: number) {
  let used = 0;
  return {
    name: "TokenBudgetMiddleware",
    // wrapModelCall 은 모델 호출을 감싸므로 응답의 usage_metadata 를 볼 수 있습니다.
    wrapModelCall: async (
      request: unknown,
      handler: (r: unknown) => Promise<{ result?: unknown }>,
    ) => {
      if (used >= maxTokens) {
        throw new Error(`토큰 예산 초과: ${used}/${maxTokens} — 실행을 중단합니다.`);
      }
      const response = await handler(request);
      const msgs = (response as { result?: { usage_metadata?: UsageMeta }[] }).result ?? [];
      for (const m of msgs) {
        used += m.usage_metadata?.total_tokens ?? 0;
      }
      console.log(`  [예산] ${used}/${maxTokens} 토큰 사용`);
      return response;
    },
  };
}

type UsageMeta = { total_tokens?: number };

async function step11_9() {
  printSection("[11-9] 비용 통제 — 모델 티어링 + 호출 상한");

  const agent = await createDeepAgent({
    model: MODEL, // 오케스트레이터: 비싼 모델 (계획/판단은 여기서 한다)
    tools: [searchDocs],
    subagents: [
      {
        name: "researcher",
        description: "단순 조사. 요약만 잘하면 됩니다.",
        systemPrompt: "search_docs 로 조사하고 2문장으로 요약하세요.",
        tools: [searchDocs],
        model: CHEAP_MODEL, // 서브에이전트: 싼 모델
      },
    ] as const,
    middleware: [
      // 실행 1회당 모델 호출 20번, 스레드 전체로는 60번까지.
      modelCallLimitMiddleware({ runLimit: 20, threadLimit: 60, exitBehavior: "end" }),
      // search_docs 는 실행 1회당 8번까지만.
      toolCallLimitMiddleware({ toolName: "search_docs", runLimit: 8, exitBehavior: "continue" }),
    ],
    systemPrompt: "조사는 researcher 서브에이전트에게 위임하세요.",
  });

  const result = await agent.invoke(
    { messages: [{ role: "user" as const, content: "'ANN 검색'을 조사해줘." }] },
    { recursionLimit: 100 },
  );
  printMessages((result.messages as never[]).slice(-2));

  // 커스텀 토큰 예산 미들웨어 데모 — 일부러 낮게 잡아 중단시킵니다.
  printSection("[11-9] 토큰 예산 미들웨어 (일부러 낮게 잡음)");
  const budgeted = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    middleware: [createTokenBudgetMiddleware(3000) as any],
    systemPrompt: "요청을 조사해서 답하세요.",
  });

  try {
    await budgeted.invoke(
      {
        messages: [
          { role: "user" as const, content: "'벡터 검색'을 아주 길고 자세하게 여러 번 조사해줘." },
        ],
      },
      { recursionLimit: 100 },
    );
  } catch (err) {
    console.log(`✖ ${(err as Error).message}`);
  }
}

/* ===== [11-10] 배포 — 내결함성 미들웨어 스택 ===== */

async function step11_10() {
  printSection("[11-10] 프로덕션 스택 — 재시도 + 상한 + 영속 체크포인터");

  // 이것이 "배포용 에이전트" 의 전형적인 모습입니다.
  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    // 프로덕션에서는 StateBackend(스레드 스코프) 또는 StoreBackend/CompositeBackend 를 씁니다.
    // FilesystemBackend/LocalShellBackend 는 호스트를 직접 건드리므로 쓰지 마세요.
    backend: new StateBackend(),
    // 데모라서 MemorySaver — 실제로는 영속 체크포인터.
    checkpointer: new MemorySaver(),
    contextSchema: z.object({ userId: z.string() }),
    middleware: [
      modelRetryMiddleware({
        maxRetries: 3,
        backoffFactor: 2,
        initialDelayMs: 1000,
        maxDelayMs: 20000,
        jitter: true,
      }),
      toolRetryMiddleware({
        tools: ["search_docs"],
        maxRetries: 2,
        initialDelayMs: 500,
        onFailure: "return_message", // 실패해도 모델에게 알려주고 계속 진행
      }),
      modelCallLimitMiddleware({ runLimit: 40, exitBehavior: "end" }),
      toolCallLimitMiddleware({ runLimit: 100, exitBehavior: "continue" }),
    ],
    systemPrompt: "요청을 조사해서 답하세요.",
  });

  const result = await agent.invoke(
    { messages: [{ role: "user" as const, content: "'하이브리드 검색'이 뭐야?" }] },
    {
      configurable: { thread_id: `prod-${Date.now()}` },
      context: { userId: "user-123" },
      durability: "sync",
      recursionLimit: 1000, // Deep Agent 는 기본 25로는 계획 단계에서 죽습니다
    },
  );
  printMessages((result.messages as never[]).slice(-2));
}

/* ===== [11-11] CLI 프론트엔드 ===== */

/** ANSI 헬퍼 — 터미널이 색을 못 쓰면 그냥 통과시킵니다. */
const canColor = process.stdout.isTTY === true && process.env["NO_COLOR"] === undefined;
const c = (code: string, s: string) => (canColor ? `[${code}m${s}[0m` : s);
const SPINNER = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

/**
 * 진행 상황을 터미널에 예쁘게 그리는 CLI 프론트엔드.
 *
 * 핵심 설계:
 * - 부모 토큰은 그대로 흘려보내고(사람이 읽는 답), 자식은 "카드"로 요약한다.
 * - todos 는 별도 패널로 계속 갱신한다.
 * - 모든 프로젝션을 동시에 소비한다 — 하나라도 안 읽으면 그 스트림이 막힌다.
 */
async function runCli(prompt: string) {
  const agent = await makeResearchAgent();

  const run = await agent.streamEvents(
    { messages: [{ role: "user" as const, content: prompt }] },
    { version: "v3", recursionLimit: 100 },
  );

  const state = {
    todos: [] as Todo[],
    subagents: new Map<string, { name: string; status: string; tools: number; last: string }>(),
    files: [] as string[],
  };

  let spin = 0;
  const timer = setInterval(() => {
    spin = (spin + 1) % SPINNER.length;
    render(state, SPINNER[spin]!);
  }, 120);

  const tasks: Promise<void>[] = [];

  // 1) todos
  tasks.push(
    (async () => {
      for await (const snap of run.values) {
        const todos = (snap as { todos?: Todo[] }).todos;
        if (todos) state.todos = todos;
        const files = (snap as { files?: Record<string, unknown> }).files;
        if (files) state.files = Object.keys(files);
      }
    })(),
  );

  // 2) 서브에이전트 카드
  tasks.push(
    (async () => {
      const inner: Promise<void>[] = [];
      for await (const sub of run.subagents) {
        const id = `${sub.name}#${state.subagents.size + 1}`;
        state.subagents.set(id, { name: sub.name, status: "running", tools: 0, last: "" });

        inner.push(
          (async () => {
            for await (const _call of sub.toolCalls) {
              const card = state.subagents.get(id)!;
              card.tools++;
            }
          })(),
        );
        inner.push(
          (async () => {
            for await (const msg of sub.messages) {
              for await (const full of msg.text.full) {
                const card = state.subagents.get(id)!;
                card.last = full.replace(/\s+/g, " ").slice(-60);
              }
            }
          })(),
        );
        inner.push(
          (async () => {
            await sub.output;
            state.subagents.get(id)!.status = "done";
          })(),
        );
      }
      await Promise.all(inner);
    })(),
  );

  // 3) 부모 답변은 마지막에 통째로 — 스피너와 겹쳐 찍으면 화면이 깨집니다.
  const parentTexts: string[] = [];
  tasks.push(
    (async () => {
      for await (const msg of run.messages) {
        const text = await msg.text; // await 하면 완성 텍스트
        if (text.trim()) parentTexts.push(text.trim());
      }
    })(),
  );

  await Promise.all(tasks);
  await run.output;
  clearInterval(timer);
  render(state, "✔");

  console.log(`\n${c("1;36", "── 최종 답변 ──")}`);
  console.log(parentTexts[parentTexts.length - 1] ?? "(없음)");
}

/** 화면을 통째로 다시 그립니다. 스크롤이 아니라 "덮어쓰기"입니다. */
let lastLineCount = 0;
function render(
  state: {
    todos: Todo[];
    subagents: Map<string, { name: string; status: string; tools: number; last: string }>;
    files: string[];
  },
  spinner: string,
) {
  const lines: string[] = [];
  const done = state.todos.filter((t) => t.status === "completed").length;

  lines.push(c("1;36", `${spinner} Deep Agent 실행 중`));

  if (state.todos.length > 0) {
    lines.push(c("2", `  계획 ${done}/${state.todos.length} ${progressBar(done, state.todos.length)}`));
    for (const t of state.todos) {
      const mark = t.status === "completed" ? c("32", "☑") : t.status === "in_progress" ? c("33", "▶") : "☐";
      lines.push(`   ${mark} ${t.content.slice(0, 60)}`);
    }
  }

  if (state.subagents.size > 0) {
    lines.push(c("2", "  서브에이전트"));
    for (const [id, s] of state.subagents) {
      const badge = s.status === "done" ? c("32", "●") : c("33", spinner);
      lines.push(`   ${badge} ${c("1", id.padEnd(16))} 도구 ${String(s.tools).padStart(2)}회  ${c("2", s.last)}`);
    }
  }

  if (state.files.length > 0) {
    lines.push(c("2", `  파일 ${state.files.length}개: ${state.files.join(", ").slice(0, 70)}`));
  }

  // 이전에 그린 줄만큼 커서를 올려 덮어씁니다.
  if (canColor && lastLineCount > 0) process.stdout.write(`[${lastLineCount}A`);
  for (const line of lines) {
    // [2K = 그 줄 지우기. 이전 내용이 더 길었을 때 잔상이 남는 걸 막습니다.
    process.stdout.write(`${canColor ? "[2K" : ""}${line}\n`);
  }
  lastLineCount = lines.length;
}

async function step11_11() {
  printSection("[11-11] CLI 프론트엔드");
  await runCli("'벡터 검색'과 '전문 검색'을 각각 조사한 뒤, 비교 보고서를 /report.md 에 써줘.");
}

/* ===== 실행 ===== */

async function main() {
  if (!process.env["ANTHROPIC_API_KEY"]) {
    console.error("ANTHROPIC_API_KEY 가 없습니다. project/.env 를 확인하세요.");
    process.exit(1);
  }

  const steps: [string, () => Promise<void>][] = [
    ["11-1", step11_1],
    ["11-2", step11_2],
    ["11-3", step11_3],
    ["11-4", step11_4],
    ["11-5", step11_5],
    ["11-6", step11_6],
    ["11-7", step11_7],
    ["11-8", step11_8],
    ["11-9", step11_9],
    ["11-10", step11_10],
    ["11-11", step11_11],
  ];

  for (const [id, fn] of steps) {
    if (!want(id)) continue;
    await fn();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
