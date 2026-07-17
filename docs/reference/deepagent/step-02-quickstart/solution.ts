/**
 * Step 02 — 첫 Deep Agent · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-02-quickstart/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 문제 4(뒷부분), 6, 8 만 RUN_LIVE=1 이 필요합니다.
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

/* ===== [정답 1] peer dependency 확인 =====
 *
 * (A) `npm ls @langchain/core` 의 출력에서 "deduped" 는 6번 나옵니다
 *     (@langchain/anthropic, @langchain/langgraph 아래 3개,
 *      deepagents 아래 2개, langchain 아래 1개 — 설치 상태에 따라 다름).
 *
 *     "deduped" 는 "이미 위에서 설치된 걸 그대로 쓴다"는 뜻입니다.
 *     즉 사본을 새로 안 깔았다는 표시입니다. 이게 우리가 원하는 상태입니다.
 *
 *     반대로 이런 게 보이면 위험합니다:
 *       └─┬ deepagents@1.11.0
 *         └── @langchain/core@1.1.0     ← deduped 가 없다 = 사본이 따로 있다!
 *
 * (B) peerDependencies 는 아래 코드로 읽습니다.
 *
 * 해설: peer dependency 는 "네가 깐 걸 같이 쓰겠다"는 선언입니다.
 * deepagents 가 만든 AIMessage 와 여러분이 import 한 AIMessage 가
 * 같은 클래스여야 instanceof 가 동작하기 때문입니다.
 */
printSection("[정답 1] deepagents 의 peer dependency");

const meta = require("deepagents/package.json") as {
  version: string;
  peerDependencies?: Record<string, string>;
};

console.log(`deepagents@${meta.version} 의 peerDependencies:`);
for (const [name, range] of Object.entries(meta.peerDependencies ?? {})) {
  console.log(`  ${name.padEnd(34)} ${range}`);
}

// 사본이 몇 벌인지 코드로 검증 — CI 에 넣어 두면 좋습니다.
const resolvedFrom = ["deepagents", "langchain", "@langchain/anthropic"].map((from) => {
  const req = createRequire(require.resolve(`${from}/package.json`));
  return req.resolve("@langchain/core/package.json");
});
const copies = new Set(resolvedFrom).size;
console.log(`\n@langchain/core 사본 개수: ${copies}`);
console.log(copies === 1 ? "  ✅ 한 벌 — instanceof 정상" : "  ⚠️ 두 벌 이상 — instanceof 가 깨집니다");

/* ===== [정답 2] 엔트리포인트 diff =====
 *
 * 답: browser 에만 없는 9개는
 *   FilesystemBackend, LocalShellBackend, SUBAGENT_RESPONSE_FORMAT_CONFIG_KEY,
 *   createAgentMemoryMiddleware, createSettings, createSubAgent,
 *   findProjectRoot, listSkills, parseSkillMetadata
 *
 * 공통점: 전부 Node 의 fs / process 에 의존합니다.
 *   - FilesystemBackend  → 실제 디스크 읽기/쓰기
 *   - LocalShellBackend  → 셸 프로세스 실행
 *   - findProjectRoot    → 상위 디렉터리 탐색
 *   - listSkills / parseSkillMetadata → 디렉터리 스캔 + 파일 파싱
 *   - createSettings     → 설정 파일 읽기
 *
 * 브라우저엔 fs 모듈이 없으니 넣을 수가 없습니다.
 *
 * 반대로 StateBackend(메모리 안 가상 FS)와 StoreBackend 는 양쪽에 다 있습니다.
 * 디스크를 안 건드리니까요. 이 대비가 "Deep Agent 의 파일시스템은 진짜
 * 파일시스템이 아니다"라는 Step 04 의 주제를 미리 보여 줍니다.
 */
printSection("[정답 2] browser 엔트리에 없는 9개");

const mainMod = await import("deepagents");
const browserMod = await import("deepagents/browser");
const nodeMod = await import("deepagents/node");

const main = Object.keys(mainMod).sort();
const browser = Object.keys(browserMod).sort();
const node = Object.keys(nodeMod).sort();

console.log(`deepagents: ${main.length}개 / browser: ${browser.length}개 / node: ${node.length}개`);

const browserSet = new Set(browser);
const missing = main.filter((k) => !browserSet.has(k));
console.log(`\nbrowser 에만 없는 것 (${missing.length}개):`);
for (const k of missing) console.log(`  - ${k}`);

console.log("\n→ 공통점: 전부 Node 의 fs / process 에 의존합니다.");
console.log("   StateBackend / StoreBackend 는 양쪽에 다 있습니다 (디스크를 안 씀).");

// deepagents 와 /node 는 같은가?
const nodeSet = new Set(node);
console.log(`\ndeepagents 와 /node 의 차이: ${main.filter((k) => !nodeSet.has(k)).length}개 → 사실상 동일`);

/* ===== [정답 3] 옵션 없이 만들 수 있나 =====
 *
 * 답: 됩니다. createDeepAgent({}) 는 정상 동작하고 도구 8개가 그대로 실립니다.
 *
 * 해설: CreateDeepAgentParams 의 17개 필드가 전부 optional 입니다.
 * 심지어 params 자체도 optional 이라 createDeepAgent() 도 됩니다.
 *
 * model 을 안 주면 기본값 claude-sonnet-4-5-20250929 이 쓰입니다.
 * ⚠️ 이게 본문 2-7 의 함정입니다 — "우리는 OpenAI 만 쓰는데 왜
 * Anthropic 청구서가 오지?" 의 범인입니다. 프로덕션에선 항상 명시하세요.
 */
printSection("[정답 3] createDeepAgent({}) 가 되는가");

const bare = await observe((spy) => createDeepAgent({ middleware: [spy] }));
console.log(`createDeepAgent({}) → 생성 성공, 도구 ${bare.tools.length}개`);
console.log(`  ${[...bare.tools].sort().join(", ")}`);
console.log("\n→ 17개 옵션이 전부 optional. model 도 기본값(claude-sonnet-4-5-20250929)이 들어갑니다.");

/* ===== [정답 4] description 이 곧 프롬프트 =====
 *
 * (A) 답: 9개 (내장 8 + add 1). 커스텀 도구는 내장을 대체하지 않고 더해집니다.
 *
 * (B) 답: description 을 "계산" 으로 줄이면 모델이 add 를 "안 부르고"
 *     그냥 8이라고 답해 버립니다.
 *
 * 해설: 이게 이 스텝에서 가장 중요한 실험입니다.
 *
 * 도구는 멀쩡히 실려 있습니다(스파이로 확인하면 9개 그대로). 그런데
 * 모델이 안 부릅니다. 왜? "계산" 두 글자로는 "3 더하기 5" 가 이 도구를
 * 쓸 상황인지 판단할 수가 없기 때문입니다. 게다가 3+5 는 모델이 암산으로
 * 아는 거라 굳이 도구를 쓸 이유도 없습니다.
 *
 * ⚠️ 에러도 경고도 없습니다. 답(8)은 맞습니다. 그래서 "잘 동작하네" 하고
 * 넘어갑니다. 그러다 숫자가 커지거나(모델 암산이 틀림) 도구가 진짜
 * 외부 API 를 부르는 것이었다면(부작용이 안 일어남) 그때 터집니다.
 *
 * 게다가 비결정적이라 가끔은 부릅니다. 그게 더 나쁩니다 — 테스트에선
 * 통과하고 프로덕션에서 실패하니까요.
 *
 * 교훈: 좋은 description 은 "무엇을 하는가"가 아니라 "언제 써야 하는가"를
 * 적습니다. 모델이 판단해야 하는 건 그거니까요.
 */
printSection("[정답 4] description 을 줄이면 모델이 도구를 부를까");

// (A) 제대로 된 description
const addGood = tool(({ a, b }: { a: number; b: number }) => `${a + b}`, {
  name: "add",
  description:
    "두 숫자를 더합니다. 사용자가 덧셈을 요청하거나 두 수의 합이 필요할 때 반드시 이 도구를 쓰세요. 암산하지 마세요.",
  schema: z.object({
    a: z.number().describe("첫 번째 숫자"),
    b: z.number().describe("두 번째 숫자"),
  }),
});

const withAdd = await observe((spy) =>
  createDeepAgent({ model: MODEL, tools: [addGood], middleware: [spy] }),
);
console.log(`(A) 도구 개수: ${withAdd.tools.length}개 (내장 8 + add 1)`);

// (B) 부실한 description — 이름만 같고 설명이 두 글자
const addBad = tool(({ a, b }: { a: number; b: number }) => `${a + b}`, {
  name: "add",
  description: "계산", // ← 이게 문제
  schema: z.object({ a: z.number(), b: z.number() }),
});

if (RUN_LIVE) {
  for (const [label, t] of [
    ["좋은 description", addGood],
    ["나쁜 description(계산)", addBad],
  ] as const) {
    const a = createDeepAgent({ model: MODEL, tools: [t] });
    const out = await a.invoke({ messages: [{ role: "user", content: "3 더하기 5는?" }] });
    const msgs = out.messages as BaseMessage[];
    // 실제로 add 를 불렀는지는 메시지에 tool_calls 가 있는지로 봅니다.
    const called = msgs.some((m) =>
      ((m as { tool_calls?: { name?: string }[] }).tool_calls ?? []).some((c) => c.name === "add"),
    );
    console.log(`\n[${label}] add 도구를 불렀나? ${called ? "✅ 예" : "❌ 아니오"}`);
    console.log(`  최종 답변: ${msgs.at(-1)?.text?.slice(0, 60)}`);
  }
  console.log("\n→ 나쁜 쪽은 도구가 실려 있는데도 안 부르고 암산해 버립니다. 에러 없이.");
} else {
  console.log("\n(B) RUN_LIVE=1 로 실행하면 두 description 의 차이를 볼 수 있습니다.");
  console.log("기대: 좋은 쪽은 add 를 부르고, 나쁜 쪽('계산')은 안 부르고 그냥 8이라고 답함");
}

/* ===== [정답 5] 모델 지정 세 가지 방법 =====
 *
 * 셋 다 됩니다. 고르는 기준:
 *   (a) 문자열       → 파라미터 조정이 필요 없을 때. 가장 간단.
 *   (b) initChatModel → temperature 등 공통 파라미터를 줄 때. provider 중립적.
 *   (c) 클래스 직접   → 그 provider 고유 옵션(thinking, cacheControl 등)까지 쓸 때.
 *
 * 참고: (a) 의 문자열이 동작하려면 해당 provider 패키지가 설치돼 있어야 합니다.
 * "openai:gpt-5.5" 를 쓰려면 npm install @langchain/openai 가 먼저입니다.
 * 안 깔고 쓰면 런타임에 "Unable to import ..." 에러가 납니다.
 */
printSection("[정답 5] 모델 지정 세 가지 방법");

const byString = createDeepAgent({ model: "anthropic:claude-sonnet-4-6" });
console.log(`(a) 문자열        : ${byString.constructor.name}`);

const tuned = await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0 });
const byInit = createDeepAgent({ model: tuned });
console.log(`(b) initChatModel : ${byInit.constructor.name} (temperature: 0)`);

const direct = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 4096 });
const byClass = createDeepAgent({ model: direct });
console.log(`(c) 클래스 직접   : ${byClass.constructor.name} (maxTokens: 4096)`);

console.log("\n→ 셋 다 동일한 ReactAgent 를 만듭니다. 파라미터 조정 필요 여부로 고르세요.");

/* ===== [정답 6] 스트리밍으로 흐름 보기 =====
 *
 * 답: subgraphs 를 빼면 "서브에이전트 내부가 통째로 안 보입니다."
 * task 호출과 그 결과만 보이고, 그 안에서 무슨 도구를 몇 번 불렀는지는 깜깜합니다.
 *
 * 해설: subgraphs: true 가 있으면 [namespace, chunk] 튜플이 오고,
 * namespace 로 출처를 구분할 수 있습니다.
 *   []                  → 메인 에이전트
 *   ["tools:abc123"]    → task 로 띄운 서브에이전트
 *
 * 없으면 chunk 만 오고 메인 것만 보입니다.
 *
 * ⚠️ await agent.stream(...) 의 await 를 빼면 [namespace, chunk] 구조분해가
 * 깨져서 namespace 가 undefined 가 되고 namespace.length 에서 터집니다.
 */
printSection("[정답 6] stream 으로 도구 호출 순서 기록");

const STREAM_TASK = "/notes.md 에 오늘 할 일 3개를 써 줘";

if (RUN_LIVE) {
  const agent = await createDeepAgent({ model: MODEL });

  console.log("── subgraphs: true ──");
  for await (const [namespace, chunk] of await agent.stream(
    { messages: [{ role: "user", content: STREAM_TASK }] },
    { streamMode: "updates", subgraphs: true },
  )) {
    const who = namespace.length === 0 ? "메인" : `서브(${namespace.join("|")})`;
    for (const [node, update] of Object.entries(chunk as Record<string, unknown>)) {
      const msgs = (update as { messages?: BaseMessage[] })?.messages ?? [];
      for (const m of msgs) {
        const calls = (m as { tool_calls?: { name?: string }[] }).tool_calls ?? [];
        if (calls.length > 0) {
          console.log(`[${who}] ${node} → ${calls.map((c) => c.name).join(", ")}`);
        } else if (m.getType() === "tool") {
          console.log(`[${who}] ${node} ← ${(m as { name?: string }).name} (${m.text.length}자)`);
        }
      }
    }
  }
} else {
  console.log("RUN_LIVE=1 로 실행하면 실제 순서를 볼 수 있습니다.");
  console.log("기대 순서: write_todos(계획) → write_file(저장). 간단한 작업이라 task 는 안 쓸 수도 있습니다.");
  console.log("\n→ subgraphs 를 빼면 서브에이전트 내부가 통째로 안 보입니다.");
}

/* ===== [정답 7] stateSchema vs contextSchema =====
 *
 * 차이: stateSchema 는 checkpointer 가 있으면 "실행 간 유지"되고,
 *      contextSchema 는 "실행 1회 한정" 입니다.
 *      즉 기준은 "실행이 끝나도 남아야 하는가" 입니다.
 *
 * (a) userId       → contextSchema
 *     매 실행 호출자가 주입하는 값입니다. 다음 실행 땐 다른 사용자일 수 있죠.
 *     도구 안에서 runtime.context.userId 로 읽습니다.
 *
 * (b) 보고서 초안  → stateSchema
 *     다음 턴에도 이어져야 하는 누적 결과입니다.
 *     (실무에선 이런 큰 텍스트는 state 보다 files 에 두는 게 낫습니다 — Step 04)
 *
 * (c) API 키       → contextSchema
 *     ⚠️ 여기서 많이 틀립니다. stateSchema 라고 답하기 쉬운데, state 는
 *     checkpointer 가 "그대로 저장"합니다. API 키를 state 에 두면 키가
 *     체크포인트 DB 에 평문으로 남습니다 — 보안 사고입니다.
 *     비밀은 실행 1회짜리 context 로 주입하세요.
 */
printSection("[정답 7] stateSchema vs contextSchema");
console.log("차이: state 는 실행 간 유지(checkpointer 저장), context 는 실행 1회 한정");
console.log("");
console.log("(a) userId       → contextSchema  (매 실행 주입되는 값)");
console.log("(b) 보고서 초안  → stateSchema    (다음 턴에도 이어져야 함)");
console.log("(c) API 키       → contextSchema  ← state 에 두면 체크포인트에 평문 저장!");

/* ===== [정답 8] (심화) 격리가 아껴 준 컨텍스트 =====
 *
 * 답: 서브에이전트가 소비한 글자 수 대비 메인에게 돌아온 글자 수가
 * 대개 5:1 ~ 20:1 입니다. (모델 응답이라 실행마다 다릅니다)
 *
 * 해설: 이 비율이 곧 격리(isolation)의 값어치입니다.
 * 서브에이전트가 도구 결과 10,000자를 소비했는데 메인에겐 800자만
 * 돌아왔다면, 메인의 컨텍스트를 9,200자만큼 아낀 것입니다.
 *
 * Step 01 의 1-5 에서 "검색 28번이면 요약이 걸린다" 고 계산했죠.
 * 격리를 쓰면 그 28번이 서브에이전트 안에서 일어나고 메인엔 요약만
 * 남으므로, 같은 예산으로 훨씬 많은 탐색이 가능해집니다.
 *
 * 주의: 간단한 작업에선 모델이 task 를 아예 안 씁니다. 그럼 서브 글자 수가
 * 0 이 나오는데 정상입니다. 위임할 만큼 복잡한 요청을 줘야 합니다.
 */
printSection("[정답 8] 격리가 아껴 준 컨텍스트");

const ISOLATION_TASK =
  "LangGraph 와 CrewAI 를 각각 조사해서 비교표를 만들어 /compare.md 에 저장해 줘. " +
  "각 프레임워크 조사는 서브에이전트에 맡기세요.";

if (RUN_LIVE) {
  const agent = await createDeepAgent({ model: MODEL });

  let subChars = 0; // 서브에이전트가 받은 도구 결과 총합
  let taskChars = 0; // 메인에게 돌아온 task 결과 총합

  for await (const [namespace, chunk] of await agent.stream(
    { messages: [{ role: "user", content: ISOLATION_TASK }] },
    { streamMode: "updates", subgraphs: true },
  )) {
    const isSub = namespace.length > 0;
    for (const update of Object.values(chunk as Record<string, unknown>)) {
      const msgs = (update as { messages?: BaseMessage[] })?.messages ?? [];
      for (const m of msgs) {
        if (m.getType() !== "tool") continue;
        const len = m.text.length;
        const name = (m as { name?: string }).name;
        if (isSub) subChars += len;
        else if (name === "task") taskChars += len;
      }
    }
  }

  console.log(`서브에이전트가 소비한 도구 결과 : ${subChars.toLocaleString()}자`);
  console.log(`메인에게 돌아온 task 결과      : ${taskChars.toLocaleString()}자`);
  if (taskChars > 0) {
    console.log(`\n압축비: ${(subChars / taskChars).toFixed(1)} : 1`);
    console.log(`메인 컨텍스트에서 아낀 양: 약 ${(subChars - taskChars).toLocaleString()}자`);
  } else {
    console.log("\ntask 를 안 썼습니다. 모델이 위임할 필요가 없다고 판단한 것입니다.");
  }
} else {
  console.log("RUN_LIVE=1 로 실행하면 실제 비율을 볼 수 있습니다.");
  console.log("기대: 서브가 소비한 글자 : 메인에 돌아온 글자 = 대략 5:1 ~ 20:1");
  console.log("→ 그 차이만큼 메인의 컨텍스트를 아낀 것입니다. 이게 격리의 값어치입니다.");
}

console.log("\n(정답 확인 끝. Step 03 으로 넘어가세요)");
