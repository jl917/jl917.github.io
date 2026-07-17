/**
 * Step 02 — 첫 Deep Agent
 * 실행: npx tsx docs/reference/deepagent/step-02-quickstart/practice.ts
 *
 * [2-1] [2-2] [2-4] [2-5] [2-6] [2-7] 은 API 키 없이 돌아갑니다.
 * 실제 모델을 부르는 [2-3] 과 [2-8] 만 RUN_LIVE=1 이 필요합니다.
 *     RUN_LIVE=1 npx tsx docs/reference/deepagent/step-02-quickstart/practice.ts
 */
import "dotenv/config";

import { createDeepAgent } from "deepagents";
import { createMiddleware, tool } from "langchain";
import { initChatModel } from "langchain/chat_models/universal";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";
import { createRequire } from "node:module";

import { printSection, printMessages, printTodos, printFiles } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const RUN_LIVE = process.env["RUN_LIVE"] === "1";

/* ===== [2-1] 설치와 peer dependency =====
 *
 *   npm install deepagents langchain @langchain/core
 *
 * 세 패키지를 "같이" 설치하는 이유가 여기 있습니다. deepagents 는
 * @langchain/core 를 peer dependency 로 요구합니다 — "내가 깔 테니 넌 빠져"가
 * 아니라 "네가 깐 걸 같이 쓰겠다"는 선언입니다.
 *
 * peer 가 어긋나면 npm 이 사본을 하나 더 깔아 주고, 그 순간 instanceof 가
 * 조용히 false 가 됩니다. 그래서 설치 직후 이걸 확인하는 습관이 필요합니다.
 */
printSection("[2-1] 설치 검증 — @langchain/core 가 한 벌인가");

const require = createRequire(import.meta.url);

/** 이 패키지가 선언한 peer dependency 를 읽습니다. */
function peersOf(pkg: string): Record<string, string> {
  const meta = require(`${pkg}/package.json`) as {
    peerDependencies?: Record<string, string>;
  };
  return meta.peerDependencies ?? {};
}

const deepPeers = peersOf("deepagents");
console.log("deepagents 가 요구하는 peer dependency:");
for (const [name, range] of Object.entries(deepPeers)) {
  console.log(`  ${name.padEnd(34)} ${range}`);
}

/* @langchain/core 가 실제로 몇 벌 깔렸는지 봅니다.
   여러 패키지 입장에서 각각 resolve 해 보고, 경로가 전부 같으면 한 벌입니다. */
const coreResolvedFrom = ["deepagents", "langchain", "@langchain/anthropic"].map((from) => {
  const req = createRequire(require.resolve(`${from}/package.json`));
  return req.resolve("@langchain/core/package.json");
});

const uniquePaths = [...new Set(coreResolvedFrom)];
const coreVersion = (require("@langchain/core/package.json") as { version: string }).version;

console.log(`\n@langchain/core 버전: ${coreVersion}`);
console.log(`서로 다른 사본 개수: ${uniquePaths.length}`);
console.log(
  uniquePaths.length === 1
    ? "  ✅ 한 벌입니다 — instanceof 가 정상 동작합니다."
    : "  ⚠️ 두 벌 이상입니다! instanceof 가 조용히 false 가 됩니다.\n" +
        "     rm -rf node_modules package-lock.json && npm install",
);

/* ===== [2-2] 엔트리포인트 3종 =====
 *
 *   deepagents          → Node 기본. 전부 들어 있음
 *   deepagents/browser  → 브라우저 안전. Node 전용 심볼이 빠져 있음
 *   deepagents/node     → 명시적 Node. deepagents 와 동일
 *
 * 말로만 하면 안 와닿으니 직접 세어 봅니다.
 */
printSection("[2-2] 엔트리포인트 3종 — 무엇이 다른가");

const mainMod = await import("deepagents");
const browserMod = await import("deepagents/browser");
const nodeMod = await import("deepagents/node");

const keysOf = (m: object) => Object.keys(m).sort();
const main = keysOf(mainMod);
const browser = keysOf(browserMod);
const node = keysOf(nodeMod);

console.log(`deepagents         : export ${main.length}개`);
console.log(`deepagents/browser : export ${browser.length}개`);
console.log(`deepagents/node    : export ${node.length}개`);

const browserSet = new Set(browser);
const missingInBrowser = main.filter((k) => !browserSet.has(k));

console.log(`\ndeepagents 에는 있는데 /browser 에는 없는 것 (${missingInBrowser.length}개):`);
for (const k of missingInBrowser) console.log(`  - ${k}`);

const nodeSet = new Set(node);
console.log(`\ndeepagents 와 /node 의 차이: ${main.filter((k) => !nodeSet.has(k)).length}개`);
console.log("→ Node 환경에서 'deepagents' 와 'deepagents/node' 는 사실상 같습니다.");

/* ===== [2-3] createDeepAgent 첫 실행 =====
 *
 * 공식 문서 표기를 그대로 따릅니다: await createDeepAgent({...})
 *
 * 주의: 1.11.0 에서 이 함수는 사실 동기입니다(Step 01 의 1-4 함정).
 * 그래도 await 를 붙이는 게 안전합니다 — 손해가 없고 문서 표기와 같습니다.
 * 반대로 agent.invoke() 앞의 await 는 "진짜" 필수입니다.
 */
printSection("[2-3] 첫 Deep Agent 실행");

const getWeather = tool(({ city }: { city: string }) => `${city}는 언제나 맑음!`, {
  name: "get_weather",
  description: "주어진 도시의 날씨를 알려 줍니다",
  schema: z.object({ city: z.string().describe("도시 이름") }),
});

const agent = await createDeepAgent({
  model: MODEL,
  tools: [getWeather],
  systemPrompt: "당신은 친절한 날씨 안내원입니다. 한국어로 답하세요.",
});

if (RUN_LIVE) {
  const result = await agent.invoke({
    messages: [{ role: "user", content: "도쿄 날씨 알려 줘" }],
  });
  const msgs = result.messages as BaseMessage[];
  printMessages(msgs, 120);
  console.log(`\n최종 답변: ${msgs.at(-1)?.text}`);
} else {
  console.log("RUN_LIVE=1 을 붙이면 실제로 호출합니다.");
  console.log(`에이전트 생성 완료: ${agent.constructor.name}`);
}

/* ===== [2-4] CreateDeepAgentParams 전체 옵션 =====
 *
 * 16개 옵션이 있습니다. 여기서는 "지도"만 펼치고, 각각은 해당 스텝에서
 * 본격적으로 다룹니다. 본문의 표와 같은 내용입니다.
 */
printSection("[2-4] CreateDeepAgentParams 옵션 지도");

const OPTIONS: Array<[string, string, string]> = [
  ["model", "쓸 모델. 'provider:model' 문자열 또는 인스턴스", "2-7"],
  ["tools", "에이전트에 붙일 커스텀 도구. 내장 8개에 더해진다", "2-6"],
  ["systemPrompt", "커스텀 지침. 내장 프롬프트 '앞'에 붙는다", "Step 07"],
  ["subagents", "위임용 서브에이전트 목록", "Step 06"],
  ["middleware", "표준 미들웨어 '뒤'에 덧붙일 미들웨어", "Step 08"],
  ["backend", "파일이 실제로 저장될 곳. 기본 StateBackend", "Step 05"],
  ["permissions", "파일시스템 경로별 접근 제어(glob)", "Step 05"],
  ["interruptOn", "실행 전 사람 승인이 필요한 도구 지정", "Step 09"],
  ["checkpointer", "실행 간 상태 저장. 대화를 이어가려면 필수", "Step 10"],
  ["store", "스레드를 넘는 장기 메모리 저장소", "Step 10"],
  ["memory", "시작 시 읽어들일 AGENTS.md 경로 목록", "Step 10"],
  ["skills", "필요할 때만 불러올 SKILL.md 디렉터리", "Step 10"],
  ["responseFormat", "구조화된 출력 스키마(zod)", "Step 11"],
  ["contextSchema", "실행마다 주입할 런타임 컨텍스트(userId 등)", "Step 11"],
  ["stateSchema", "messages/todos/files 외에 추가할 커스텀 상태", "Step 11"],
  ["name", "에이전트 이름", "—"],
  ["streamTransformers", "스트림 변환기. streamEvents v3 에서 노출", "Step 11"],
];

console.log("옵션".padEnd(20) + "설명".padEnd(46) + "다루는 곳");
console.log("─".repeat(88));
for (const [name, desc, where] of OPTIONS) {
  console.log(name.padEnd(20) + desc.padEnd(46) + where);
}
console.log(`\n총 ${OPTIONS.length}개. 전부 optional 입니다 — createDeepAgent({}) 도 됩니다.`);

/* ===== [2-5] 기본 제공 도구 관찰 =====
 *
 * 아무 도구도 안 줬는데 8개가 실립니다. Step 01 의 스파이를 다시 씁니다.
 */
printSection("[2-5] 기본 제공 도구 — 안 줘도 8개가 실린다");

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

const bare = await observe((spy) => createDeepAgent({ model: MODEL, middleware: [spy] }));

const TOOL_ROLE: Record<string, string> = {
  ls: "디렉터리 목록",
  read_file: "파일 읽기",
  write_file: "파일 쓰기",
  edit_file: "파일 부분 수정(문자열 치환)",
  glob: "패턴으로 파일 찾기",
  grep: "파일 내용 검색",
  task: "서브에이전트 띄우기",
  write_todos: "할 일 목록 작성/갱신",
};

console.log(`tools 를 안 줬는데 실린 도구: ${bare.tools.length}개\n`);
for (const name of [...bare.tools].sort()) {
  console.log(`  ${name.padEnd(14)} ${TOOL_ROLE[name] ?? "?"}`);
}
console.log("\n주의: execute(셸 실행)는 목록에 없습니다 — 샌드박스 백엔드에서만 생깁니다(Step 05).");

/* ===== [2-6] 커스텀 도구 추가 =====
 *
 * 내 도구는 내장 8개를 "대체하지 않고 더해집니다".
 */
printSection("[2-6] 커스텀 도구 추가 — 대체가 아니라 추가");

const searchDocs = tool(
  async ({ query }: { query: string }) => {
    // 실제로는 여기서 검색 API 를 부릅니다. 여기선 흉내만.
    return `"${query}" 검색 결과: (예시) LangGraph 는 상태 그래프 기반 프레임워크입니다.`;
  },
  {
    name: "search_docs",
    // description 이 곧 프롬프트입니다. 부실하면 모델이 이 도구를 안 부릅니다.
    description:
      "사내 기술 문서를 검색합니다. 프레임워크 사용법이나 API 를 물어볼 때 쓰세요.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const withTools = await observe((spy) =>
  createDeepAgent({ model: MODEL, tools: [getWeather, searchDocs], middleware: [spy] }),
);

console.log(`내장 8개 + 커스텀 2개 = ${withTools.tools.length}개`);
console.log(`  ${[...withTools.tools].sort().join(", ")}`);

const custom = withTools.tools.filter((t) => !bare.tools.includes(t));
console.log(`\n새로 늘어난 것: ${custom.join(", ")}`);
console.log("→ 내장 도구를 끄는 옵션은 없습니다. 끄려면 Step 08 에서 직접 조립해야 합니다.");

/* ===== [2-7] 모델 교체 =====
 *
 * 세 가지 방법이 있습니다.
 *   (A) "provider:model" 문자열   — 가장 간단
 *   (B) initChatModel 로 파라미터까지 지정
 *   (C) provider 클래스를 직접 생성
 */
printSection("[2-7] 모델 교체 — 세 가지 방법");

// (A) 문자열. provider 접두사가 어떤 LangChain 통합을 쓸지 고릅니다.
const byString = createDeepAgent({ model: "anthropic:claude-sonnet-4-6" });
console.log(`(A) 문자열        : ${byString.constructor.name} 생성됨`);

// (B) initChatModel — temperature 같은 파라미터를 줄 때.
const tuned = await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0 });
const byInit = createDeepAgent({ model: tuned });
console.log(`(B) initChatModel : ${byInit.constructor.name} 생성됨 (temperature: 0)`);

// (C) 클래스 직접 생성 — 그 provider 고유 옵션까지 다 쓸 때.
const direct = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 4096 });
const byClass = createDeepAgent({ model: direct });
console.log(`(C) 클래스 직접   : ${byClass.constructor.name} 생성됨 (maxTokens: 4096)`);

// (D) 아예 생략하면? → Anthropic 기본 모델이 쓰입니다.
const byDefault = createDeepAgent({});
console.log(`(D) model 생략    : ${byDefault.constructor.name} 생성됨 (claude-sonnet-4-5-20250929)`);

console.log("\nOpenAI 로 바꾸려면 npm install @langchain/openai 후 model: 'openai:gpt-5.5'");
console.log("provider 패키지가 설치돼 있어야 문자열이 해석됩니다.");

/* ===== [2-8] 실행 관찰 — stream 으로 흐름 보기 =====
 *
 * invoke 는 다 끝나야 결과를 줍니다. Deep Agent 는 수십 턴을 도는데
 * 그동안 아무것도 안 보이면 답답하고, 무엇보다 디버깅이 안 됩니다.
 * stream 을 쓰면 계획 → 파일 쓰기 → 서브에이전트 흐름이 실시간으로 보입니다.
 *
 * 핵심 두 가지:
 *   - await agent.stream(...)  ← stream 도 await 가 필요합니다
 *   - subgraphs: true          ← 이게 없으면 서브에이전트 내부가 안 보입니다
 */
printSection("[2-8] stream 으로 계획 → 파일 → 서브에이전트 흐름 보기");

const RESEARCH = "LangGraph 의 특징을 조사해서 /report.md 파일로 정리해 줘. 계획을 먼저 세우세요.";

if (RUN_LIVE) {
  const streamer = await createDeepAgent({ model: MODEL });

  // subgraphs: true 를 주면 [namespace, chunk] 튜플이 옵니다.
  // namespace 가 [] 면 메인 에이전트, "tools:..." 로 시작하면 서브에이전트입니다.
  for await (const [namespace, chunk] of await streamer.stream(
    { messages: [{ role: "user", content: RESEARCH }] },
    { streamMode: "updates", subgraphs: true },
  )) {
    const who = namespace.length === 0 ? "메인" : `서브(${namespace.join("|")})`;

    for (const [node, update] of Object.entries(chunk as Record<string, unknown>)) {
      const msgs = (update as { messages?: BaseMessage[] })?.messages ?? [];
      for (const m of msgs) {
        const calls = (m as { tool_calls?: { name?: string }[] }).tool_calls ?? [];
        if (calls.length > 0) {
          console.log(`[${who}] ${node} → 도구 호출: ${calls.map((c) => c.name).join(", ")}`);
        } else if (m.getType() === "tool") {
          const name = (m as { name?: string }).name;
          console.log(`[${who}] ${node} ← ${name} 결과 (${m.text.length}자)`);
        }
      }
    }
  }
  console.log("\n→ write_todos 로 계획을 세우고, task 로 위임하고, write_file 로 저장하는 흐름이 보입니다.");
} else {
  console.log("RUN_LIVE=1 을 붙이면 실시간 흐름이 보입니다. 기대되는 순서:");
  console.log("  [메인] model → 도구 호출: write_todos     ← 계획");
  console.log("  [메인] tools ← write_todos 결과");
  console.log("  [메인] model → 도구 호출: task            ← 서브에이전트 위임");
  console.log("  [서브(tools:...)] model → 도구 호출: ...  ← subgraphs:true 라서 보임");
  console.log("  [메인] model → 도구 호출: write_file      ← 파일 저장");
}

/* ===== 종합 =====
 * 최종 상태에서 계획과 파일을 함께 봅니다. Step 03, 04 의 예고편입니다. */
printSection("[2-8] 종합 — 최종 상태의 todos 와 files");

if (RUN_LIVE) {
  const finalAgent = await createDeepAgent({ model: MODEL });
  const out = await finalAgent.invoke({
    messages: [{ role: "user", content: "/hello.md 에 인사말을 써 줘" }],
  });
  console.log("── todos ──");
  printTodos(out.todos);
  console.log("── files ──");
  printFiles(out.files, true);
} else {
  console.log("RUN_LIVE=1 을 붙이면 todos 와 files 를 볼 수 있습니다.");
  console.log("→ 자세한 건 Step 03(계획)과 Step 04(파일시스템)에서 다룹니다.");
}
