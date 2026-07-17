/**
 * Step 01 — Deep Agent란 무엇인가 · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-01-why-deep-agents/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 문제 1 과 8 만 RUN_LIVE=1 이 필요합니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { createDeepAgent } from "deepagents";
import { AIMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";
import { writeFileSync } from "node:fs";

import { printSection } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";
const RUN_LIVE = process.env["RUN_LIVE"] === "1";

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

/* ===== [정답 1] 얕은 에이전트 vs Deep Agent — 턴 수 비교 =====
 *
 * 기대 결과 (모델 응답이므로 매번 다릅니다):
 *   createAgent     → messages.length = 2
 *   createDeepAgent → messages.length = 2 ~ 8
 *
 * 답: 이 작업에는 createAgent 가 적합합니다.
 *
 * 해설: 피보나치 10번째 항은 모델이 암산으로 즉시 답합니다(55).
 * 계획도, 파일도, 서브에이전트도 필요 없습니다. 그런데 createDeepAgent 로
 * 시키면 프롬프트 약 1,700 토큰을 헛되이 태우고, 운이 나쁘면 모델이
 * "단계별로 해 보자" 며 write_todos 까지 부릅니다 — 55 하나 얻으려고요.
 *
 * 이게 본문 1-4 의 "애매하면 createAgent 부터" 팁의 실물입니다.
 * Deep 은 공짜가 아니라 고정비를 내고 사는 능력입니다.
 */
printSection("[정답 1] 턴 수 비교");

const FIB_TASK = "피보나치 수열 10번째 항을 구해 줘";

if (RUN_LIVE) {
  const shallow = createAgent({ model: MODEL, tools: [] });
  const a = await shallow.invoke({ messages: [{ role: "user", content: FIB_TASK }] });
  console.log(`(A) createAgent     messages.length = ${(a.messages as BaseMessage[]).length}`);

  const deep = createDeepAgent({ model: MODEL });
  const b = await deep.invoke({ messages: [{ role: "user", content: FIB_TASK }] });
  console.log(`(B) createDeepAgent messages.length = ${(b.messages as BaseMessage[]).length}`);
  console.log("→ 간단한 계산에 Deep 을 쓰면 고정비만 더 냅니다. createAgent 가 정답.");
} else {
  console.log("RUN_LIVE=1 로 실행하면 실제 숫자를 볼 수 있습니다.");
  console.log("기대: createAgent = 2, createDeepAgent = 2~8 → 이 작업엔 createAgent 가 적합");
}

/* ===== [정답 2] 커스텀 도구는 대체인가 추가인가 =====
 *
 * 답: 9개 — 추가입니다. 대체가 아닙니다.
 *
 * 해설: 내장 8개(ls, read_file, write_file, edit_file, glob, grep, task,
 * write_todos)에 내 도구가 "더해져" 9개가 됩니다.
 *
 * 이 사실의 함의가 중요합니다. createDeepAgent 를 쓰는 한 내장 도구를
 * 뺄 수 없습니다. "파일시스템은 필요 없고 계획만 쓰고 싶다" 같은 요구가
 * 있어도 옵션으로는 안 됩니다. 그러려면 createAgent 로 내려가서
 * 미들웨어를 직접 골라 붙여야 합니다 (Step 08).
 *
 * 즉 createDeepAgent 는 "전부 아니면 전무" 에 가까운 묶음 상품입니다.
 */
printSection("[정답 2] 커스텀 도구 1개를 주면 도구가 몇 개?");

const myTool = tool(({ city }: { city: string }) => `${city}는 맑음`, {
  name: "get_weather",
  description: "도시의 날씨를 알려 줍니다",
  schema: z.object({ city: z.string() }),
});

const withCustom = await observe((spy) =>
  createDeepAgent({ model: MODEL, tools: [myTool], middleware: [spy] }),
);
console.log(`도구 개수: ${withCustom.tools.length}개`);
console.log(`  ${[...withCustom.tools].sort().join(", ")}`);
console.log("→ 8 + 1 = 9. 대체가 아니라 추가입니다.");

/* ===== [정답 3] 시스템 프롬프트 해부 =====
 *
 * 답: `task`(서브에이전트)에 지면이 압도적으로 많이 갑니다.
 *
 * 실제 측정값 (deepagents@1.11.0, 결정적):
 *   등장 횟수:  task 26회 / write_todos 3회 / read_file 2회
 *   섹션 길이:  task 약 2,140자 / write_todos 약 1,239자 / filesystem 약 483자
 *
 * 왜? 서브에이전트 위임은 모델이 "가장 안 하려는" 행동이기 때문입니다.
 * 모델의 기본 성향은 "내가 직접 다 하기" 입니다. 남에게 맡기고 결과만
 * 받는 건 부자연스러운 행동이라, 언제 위임해야 하는지 / 언제 하면 안 되는지 /
 * 왜 그게 이득인지를 예시까지 들어 가며 길게 설득해야 합니다.
 *
 * 반대로 write_todos 는 짧습니다 — 모델은 이미 계획 세우기를 좋아합니다.
 * 오히려 프롬프트가 "간단한 일엔 쓰지 마라" 고 말리는 쪽입니다.
 *
 * ⚠️ 함정: "ls" 를 단순 substring 으로 세면 23회가 나옵니다. tooLS, calLS
 * 같은 단어에 걸리기 때문입니다. 짧은 도구 이름을 셀 때는 단어 경계(\b)나
 * 백틱(`ls`)까지 포함해서 세야 합니다. 이 문제의 정답 코드가 왜 긴 이름만
 * 골랐는지가 여기 있습니다.
 */
printSection("[정답 3] 프롬프트에서 어느 기둥이 가장 긴가");

const deepObs = await observe((spy) => createDeepAgent({ model: MODEL, middleware: [spy] }));
const sys = deepObs.systemPrompt;

// (A) 파일로 저장
writeFileSync("./prompt.txt", sys, "utf8");
console.log(`시스템 프롬프트 ${sys.length.toLocaleString()}자를 ./prompt.txt 에 저장했습니다.`);

// (B) 등장 횟수 세기
const countOf = (needle: string) => (sys.match(new RegExp(needle, "g")) ?? []).length;
for (const w of ["write_todos", "task", "read_file"]) {
  console.log(`  ${w.padEnd(12)} ${countOf(w)}회`);
}

// 섹션 길이로도 확인 — 이게 "지면" 을 재는 더 정확한 방법입니다.
const iTodo = sys.indexOf("## `write_todos`");
const iFs = sys.indexOf("## Filesystem Tools");
const iTask = sys.indexOf("## `task`");
console.log("\n섹션 길이:");
console.log(`  write_todos 약 ${iFs - iTodo}자`);
console.log(`  filesystem  약 ${iTask - iFs}자`);
console.log(`  task        약 ${sys.length - iTask}자  ← 압도적`);
console.log("\n→ 위임은 모델이 가장 안 하려는 행동이라 가장 길게 설득해야 합니다.");

/* ===== [정답 4] createDeepAgent 는 정말 비동기인가 =====
 *
 * 답: 비동기가 아닙니다. instanceof Promise 는 false, 생성자는 ReactAgent.
 *
 * 해설: deepagents@1.11.0 의 타입 선언은
 *   declare function createDeepAgent<...>(params?): DeepAgent<...>
 * 로, Promise<DeepAgent> 가 아닙니다. memory / skills / subagents 를 줘도
 * 마찬가지입니다.
 *
 * 그런데 공식 문서는 전부 `await createDeepAgent(...)` 로 씁니다.
 * 모순처럼 보이지만 문제가 안 되는 이유는, JS 의 await 가 Promise 가 아닌
 * 값에 붙으면 그냥 그 값을 돌려주기 때문입니다. 그래서 아래 두 줄은 같습니다.
 *
 * 결론: await 를 붙이세요. 손해가 없고, 향후 버전이 비동기로 바뀌어도 안 깨집니다.
 *
 * 진짜 조심할 건 agent.invoke() 앞의 await 입니다. 이건 진짜 Promise 라
 * 빠뜨리면 result.messages 가 undefined 라며 엉뚱한 데서 터집니다.
 */
printSection("[정답 4] createDeepAgent 의 반환값 정체");

const ret = createDeepAgent({ model: MODEL });
console.log(`(A) instanceof Promise : ${ret instanceof Promise}`);
console.log(`(B) constructor.name   : ${ret.constructor.name}`);

const awaited = await createDeepAgent({ model: MODEL });
console.log(`await 를 붙인 것도 같은 타입인가? ${awaited.constructor.name === ret.constructor.name}`);
console.log("→ await 는 붙여도 그만, 안 붙여도 그만. 문서를 따라 붙이는 걸 권합니다.");

/* ===== [정답 5] 컨텍스트 예산 계산 =====
 *
 * 답: 28회.
 *   (170,000 − 1,700) ÷ 6,000 = 28.05 → 28회
 *
 * 해설: 이 숫자가 "작다" 는 게 요점입니다. 리서치 한 번에 검색 28번이면
 * 넉넉해 보이지만, 실제로는 파일 읽기와 모델의 응답도 같은 예산을 씁니다.
 * 게다가 28회를 넘기는 순간 자동 요약이 걸려서 앞쪽 대화가 손실 압축됩니다.
 *
 * 서브에이전트로 격리하면? 검색 20번을 서브에이전트가 자기 컨텍스트에서
 * 하고 부모에겐 요약 500 토큰만 돌려줍니다. 부모 기준으로는 검색 20번이
 * 500 토큰이 되므로, 같은 예산으로 수백 번도 가능해집니다.
 *
 * 이게 본문 1-5 "컨텍스트를 예산처럼 다루세요" 팁의 계산 근거입니다.
 */
printSection("[정답 5] 요약 전에 검색을 몇 번 할 수 있나");

const SUMMARIZE_AT = 170_000;
const SYSTEM_TOKENS = 1_700;
const SEARCH_TOKENS = 6_000;

const budget = SUMMARIZE_AT - SYSTEM_TOKENS;
const maxSearches = Math.floor(budget / SEARCH_TOKENS);
console.log(`작업 예산: ${SUMMARIZE_AT.toLocaleString()} − ${SYSTEM_TOKENS.toLocaleString()} = ${budget.toLocaleString()} 토큰`);
console.log(`검색 1건 = ${SEARCH_TOKENS.toLocaleString()} 토큰`);
console.log(`→ 최대 ${maxSearches}회. 그 다음엔 자동 요약이 걸립니다.`);
console.log("\n서브에이전트로 격리하면 부모엔 요약만 남으므로 훨씬 많이 할 수 있습니다.");

/* ===== [정답 6] 설계 판단 =====
 *
 * (a) 고객 문의 분류        → createAgent
 *     한 턴이면 끝나는 분류 작업. 계획도 파일도 필요 없습니다.
 *     responseFormat 으로 enum 을 주면 그걸로 완성입니다.
 *
 * (b) 저장소 읽고 아키텍처 문서 작성 → createDeepAgent
 *     파일이 몇 개인지 미리 모르고(턴 수 예측 불가), 읽은 내용이
 *     컨텍스트를 다 먹으며(오프로딩 필요), 초안을 고쳐야 합니다(파일 필요).
 *     4대 기둥이 전부 필요한 교과서적 사례입니다.
 *
 * (c) 사내 위키 검색 챗봇   → createAgent
 *     ⚠️ 여기서 많이 틀립니다. "검색" 이 들어가니 Deep 일 것 같지만,
 *     이 작업은 "검색 1번 → 답변" 2턴이면 끝납니다. 챗봇이라 지연 시간도
 *     중요한데 Deep 을 쓰면 매 질문마다 1,700 토큰을 더 태우고 느려집니다.
 *
 *     만약 요구가 "위키를 전부 뒤져서 종합 리포트를 써라" 였다면 Deep 입니다.
 *     구분 기준은 "검색이 있느냐" 가 아니라 "턴 수를 예측할 수 있느냐" 입니다.
 */
printSection("[정답 6] 설계 판단");
console.log("(a) 문의 분류            → createAgent     (한 턴, 예측 가능)");
console.log("(b) 저장소 → 아키텍처 문서 → createDeepAgent (턴 예측 불가, 오프로딩·초안 필요)");
console.log("(c) 위키 검색 챗봇       → createAgent     (검색 1번 + 답변 = 2턴, 지연 중요)");
console.log("\n기준은 '검색이 있느냐' 가 아니라 '턴 수를 예측할 수 있느냐' 입니다.");

/* ===== [정답 7] 실패 증상과 기둥 짝짓기 =====
 *
 * (a) 검색 결과 15개로 컨텍스트가 넘쳤다
 *     → 기둥 3 (서브에이전트 / 격리)
 *     검색을 서브에이전트에 맡기면 부모엔 요약만 옵니다.
 *     (기둥 2 오프로딩도 부분적으로 답이 됩니다 — 결과를 파일에 쓰면
 *      대화엔 참조만 남으니까요. 하지만 "탐색 과정 자체" 를 없애는 건
 *      격리 쪽입니다.)
 *
 * (b) 30턴째에 처음 계획을 잊었다
 *     → 기둥 1 (계획 / write_todos)
 *     계획이 대화 텍스트면 앞쪽으로 밀려 잊힙니다. todos 는 별도 상태라
 *     안 밀려납니다.
 *
 * (c) 도구는 붙였는데 모델이 안 부른다
 *     → 기둥 4 (상세 시스템 프롬프트)
 *     도구를 쥐여 주는 것과 쓰게 만드는 건 다른 문제입니다.
 *     정답 3 에서 본 대로, 위임 하나 시키려고 2,140자를 씁니다.
 *
 * (d) 초안을 고쳐 달라니까 처음부터 다시 쓴다
 *     → 기둥 2 (파일시스템)
 *     ⚠️ 기둥 4(프롬프트)로 착각하기 쉽습니다. "고쳐 써" 라고 잘 시키면
 *     될 것 같지만 아닙니다. 대화에 뱉은 글은 고칠 대상이 없습니다.
 *     초안이 /report.md 라는 파일로 있어야 edit_file 로 그 부분만 고칩니다.
 *     "고칠 수 있으려면 고칠 대상이 파일로 존재해야 한다" 가 핵심입니다.
 */
printSection("[정답 7] 증상 → 기둥 짝짓기");
console.log("(a) 컨텍스트 넘침       → 기둥 3 (서브에이전트 / 격리)");
console.log("(b) 계획을 잊음         → 기둥 1 (계획 / write_todos)");
console.log("(c) 도구를 안 부름      → 기둥 4 (상세 시스템 프롬프트)");
console.log("(d) 처음부터 다시 씀    → 기둥 2 (파일시스템)  ← 기둥 4로 착각하기 쉬움");

/* ===== [정답 8] (심화) 통과시키는 스파이 =====
 *
 * 핵심은 wrapModelCall 의 두 번째 인자 handler 입니다.
 * handler(request) 를 부르면 진짜 모델이 호출되고, 그 결과를 그대로
 * 돌려주면 에이전트는 아무 일 없었다는 듯 계속 굴러갑니다.
 *
 * 답: 시스템 프롬프트 길이는 매 호출마다 "거의 변하지 않습니다"(6,979자 고정).
 * 커지는 건 messages 입니다. 대화가 쌓일수록 messages 배열이 길어지고,
 * 그게 컨텍스트를 먹습니다.
 *
 * 이게 본문 1-5 의 "고정 비용은 반올림 오차, 변동 비용이 전부" 를
 * 눈으로 확인하는 실험입니다. 시스템 프롬프트 7천 자를 아끼려고 애쓰는 건
 * 의미가 없고, 검색 결과 하나를 파일로 밀어내는 게 훨씬 큽니다.
 */
printSection("[정답 8] 통과시키는 스파이로 호출 횟수 세기");

let callCount = 0;
const passThroughSpy = createMiddleware({
  name: "PassThroughSpy",
  wrapModelCall: async (request, handler) => {
    callCount += 1;
    const sysLen = typeof request.systemPrompt === "string" ? request.systemPrompt.length : 0;
    const msgCount = request.messages?.length ?? 0;
    console.log(
      `  호출 #${callCount}: 도구 ${request.tools?.length ?? 0}개 / ` +
        `시스템 프롬프트 ${sysLen.toLocaleString()}자 / messages ${msgCount}개`,
    );
    return await handler(request); // ← 진짜 모델을 부른다
  },
});

if (RUN_LIVE) {
  const agent = createDeepAgent({ model: MODEL, middleware: [passThroughSpy] });
  await agent.invoke({
    messages: [{ role: "user", content: "/hello.md 파일에 인사말을 써 줘" }],
  });
  console.log(`\n총 모델 호출 횟수: ${callCount}회`);
  console.log("→ 시스템 프롬프트 길이는 고정. 커지는 건 messages 입니다.");
} else {
  console.log("RUN_LIVE=1 로 실행하면 호출마다 한 줄씩 찍힙니다.");
  console.log("기대: 시스템 프롬프트는 6,979자로 고정, messages 개수만 늘어남");
}

console.log("\n(정답 확인 끝. Step 02 로 넘어가세요)");
