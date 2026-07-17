/**
 * Step 01 — Deep Agent란 무엇인가
 * 실행: npx tsx docs/reference/deepagent/step-01-why-deep-agents/practice.ts
 *
 * 이 파일의 절반은 API 키가 없어도 돌아갑니다.
 * [1-3] [1-4] [1-5] 는 "모델을 부르기 직전에 가로채서 관찰"하는 방식이라
 * 실제 모델 호출이 일어나지 않습니다. 토큰도 안 씁니다.
 * [1-1] [1-2] 만 실제 API 호출이 필요합니다.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { createDeepAgent } from "deepagents";
import { AIMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printMessages, printTodos, printFiles } from "../project/src/lib/print.js";

/* ── 이 파일 전체에서 쓰는 모델 ───────────────────────────────
   문자열 형식은 "provider:model" 입니다. OpenAI 로 바꾸려면
   "openai:gpt-5.5" 로 두고 @langchain/openai 를 설치하세요. */
const MODEL = "anthropic:claude-sonnet-4-6";

/* ── 실제 API 호출을 켤지 스위치 ──────────────────────────────
   기본은 꺼짐입니다. 리서치 보고서 한 번이 수만 토큰을 태우므로,
   의도적으로 켰을 때만 돌게 했습니다.
       RUN_LIVE=1 npx tsx .../practice.ts   */
const RUN_LIVE = process.env["RUN_LIVE"] === "1";

/* ===== [1-3] 관찰 도구 — 모델 호출을 가로채는 스파이 미들웨어 =====
 *
 * wrapModelCall 은 "모델을 부르기 직전"에 끼어듭니다. request 안에는
 * 이번 호출에 실제로 실릴 도구 목록과 시스템 프롬프트가 다 들어 있습니다.
 * 여기서 handler(request) 를 부르면 진짜 모델이 호출되지만,
 * 우리는 부르지 않고 가짜 AIMessage 를 돌려줍니다. → 모델 호출 0회.
 *
 * 이게 이 스텝의 핵심 관찰 장치입니다. "Deep Agent 가 마법이 아니라
 * 그냥 도구와 프롬프트를 얹어 준 것"임을 눈으로 확인시켜 줍니다.
 *
 * 주의: wrapModelCall 은 반드시 AIMessage 나 Command 를 돌려줘야 합니다.
 * { result: [...] } 같은 객체를 돌려주면 이런 에러가 납니다:
 *   MiddlewareError: Invalid response from "wrapModelCall" in middleware
 *   "Spy": expected AIMessage or Command, got object
 */
type Observed = { tools: string[]; systemPrompt: string };

function createSpy(sink: Observed) {
  return createMiddleware({
    name: "Spy",
    wrapModelCall: async (request) => {
      sink.tools = (request.tools ?? []).map((t) => (t as { name: string }).name);
      sink.systemPrompt =
        typeof request.systemPrompt === "string" ? request.systemPrompt : "";
      // handler 를 부르지 않는다 = 모델을 부르지 않는다.
      return new AIMessage("(스파이가 가로챘습니다 — 모델 호출 없음)");
    },
  });
}

/* invoke 를 "메서드 표기"로 선언한 것이 의도적입니다. 프로퍼티 표기
   ({ invoke: (i: unknown) => ... })로 쓰면 strictFunctionTypes 가 인자를
   반공변으로 검사해서 DeepAgent 를 못 받습니다. 메서드 표기는 양공변이라
   createAgent 와 createDeepAgent 를 같은 자리에 넣을 수 있습니다. */
type Invokable = { invoke(input: { messages: { role: string; content: string }[] }): Promise<unknown> };

/** 에이전트를 한 번 굴려서 "모델에 실릴 뻔한 것"만 관찰합니다. */
async function observe(make: (spy: ReturnType<typeof createSpy>) => Invokable): Promise<Observed> {
  const sink: Observed = { tools: [], systemPrompt: "" };
  const agent = make(createSpy(sink));
  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  return sink;
}

/* ===== [1-1] 얕은 에이전트의 한계 =====
 *
 * createAgent 로 "리서치 보고서를 써 줘" 를 시켜 봅니다.
 * 도구도, 계획 수단도, 메모장도 없습니다. 모델이 가진 것은
 * "한 번의 응답으로 전부 쏟아내기" 뿐입니다.
 */
printSection("[1-1] 얕은 에이전트 — createAgent 로 리서치 보고서 시키기");

const RESEARCH_TASK =
  "LangGraph, CrewAI, AutoGen 세 프레임워크를 비교하는 리서치 보고서를 써 주세요. " +
  "각각의 아키텍처, 상태 관리 방식, 적합한 사용처를 다루고, 마지막에 선택 가이드를 붙여 주세요.";

const shallowAgent = createAgent({
  model: MODEL,
  tools: [], // 도구 없음
  // systemPrompt 도 없음 — 기본값은 "비어 있음"입니다. 아래 [1-4] 에서 확인합니다.
});

if (RUN_LIVE) {
  const shallow = await shallowAgent.invoke({
    messages: [{ role: "user", content: RESEARCH_TASK }],
  });
  const msgs = shallow.messages as BaseMessage[];
  console.log(`메시지 개수: ${msgs.length}`); // 보통 2 — 사람 1, AI 1
  printMessages(msgs, 160);
  console.log("\n관찰: 턴이 한 번에 끝났습니다. 계획도, 중간 산출물도, 검증도 없습니다.");
} else {
  console.log("RUN_LIVE=1 을 붙이면 실제로 호출합니다. 여기서는 구조만 봅니다.");
  console.log(`요청: ${RESEARCH_TASK.slice(0, 40)}…`);
}

/* ===== [1-2] Deep Agent 의 4대 기둥 =====
 *
 * 같은 요청을 createDeepAgent 에 던집니다. 도구를 하나도 안 줬는데도
 * 계획(write_todos)을 세우고, 파일에 초안을 쓰고, 서브에이전트를 띄웁니다.
 * 그 넷이 이 스텝에서 말하는 4대 기둥입니다.
 */
printSection("[1-2] Deep Agent — 같은 요청을 createDeepAgent 로");

const deepAgent = createDeepAgent({
  model: MODEL,
  // tools 를 안 줍니다. 그런데도 도구 8개가 생깁니다 → [1-3] 에서 확인.
});

if (RUN_LIVE) {
  const deep = await deepAgent.invoke({
    messages: [{ role: "user", content: RESEARCH_TASK }],
  });
  const msgs = deep.messages as BaseMessage[];
  console.log(`메시지 개수: ${msgs.length}`); // 보통 수십 개
  printMessages(msgs, 80);

  console.log("\n── 에이전트가 세운 계획(todos) ──");
  printTodos(deep.todos);

  console.log("\n── 에이전트가 만든 파일(files) ──");
  printFiles(deep.files);

  console.log("\n관찰: 계획 → 파일 쓰기 → 서브에이전트 위임이 알아서 일어났습니다.");
} else {
  console.log("RUN_LIVE=1 을 붙이면 실제로 호출합니다.");
}

/* ===== [1-3] Deep Agent = 하네스 — 마법이 아님을 증명 =====
 *
 * "아무 도구도 안 줬는데 왜 도구가 8개인가?" 를 직접 찍어 봅니다.
 * 여기부터는 API 키 없이 돌아갑니다.
 */
printSection("[1-3] 하네스의 정체 — 도구를 안 줘도 8개가 실린다");

const deepObs = await observe((spy) => createDeepAgent({ model: MODEL, middleware: [spy] }));

console.log(`createDeepAgent 가 모델에 실은 도구: ${deepObs.tools.length}개`);
for (const name of [...deepObs.tools].sort()) console.log(`  - ${name}`);
console.log(`\n시스템 프롬프트 길이: ${deepObs.systemPrompt.length.toLocaleString()}자`);
console.log("시스템 프롬프트 앞 120자:");
console.log(`  ${JSON.stringify(deepObs.systemPrompt.slice(0, 120))}`);

/* ===== [1-4] createAgent vs createDeepAgent 나란히 =====
 *
 * 같은 스파이를 createAgent 에도 붙여서 둘을 나란히 놓습니다.
 * 차이는 "무엇이 기본으로 실려 있는가" 하나뿐입니다.
 */
printSection("[1-4] createAgent vs createDeepAgent — 같은 스파이로 나란히");

const shallowObs = await observe((spy) =>
  createAgent({ model: MODEL, tools: [], middleware: [spy] }),
);

console.table({
  createAgent: {
    도구수: shallowObs.tools.length,
    시스템프롬프트길이: shallowObs.systemPrompt.length,
  },
  createDeepAgent: {
    도구수: deepObs.tools.length,
    시스템프롬프트길이: deepObs.systemPrompt.length,
  },
});

console.log("createAgent 는 빈 손으로 시작합니다 — 도구 0개, 프롬프트 0자.");
console.log("createDeepAgent 는 도구 8개와 프롬프트 약 7천 자를 이미 얹고 시작합니다.");

/* 반환값도 확인합니다. 공식 문서는 `await createDeepAgent(...)` 로 쓰지만,
   1.11.0 에서 이 함수는 실제로는 동기 함수입니다. 직접 찍어 봅니다. */
const ret = createDeepAgent({ model: MODEL });
console.log(`\ncreateDeepAgent 반환값이 Promise 인가? ${ret instanceof Promise}`);
console.log(`반환값 생성자 이름: ${ret.constructor.name}`);
console.log("→ await 를 붙여도 손해는 없습니다. 문서 표기를 따라 붙이는 걸 권합니다.");

/* ===== [1-5] 컨텍스트 엔지니어링 — 유한 자원 =====
 *
 * Deep Agent 의 모든 설계는 "컨텍스트 윈도우가 유한하다" 에서 나옵니다.
 * 시작도 하기 전에 프롬프트만으로 얼마를 쓰는지 재 봅니다.
 */
printSection("[1-5] 컨텍스트는 유한 자원 — 시작 전에 이미 얼마를 쓰는가");

// 영어 기준 대략 4자 = 1토큰. 정확한 수치가 아니라 감을 잡기 위한 어림입니다.
const roughTokens = (s: string) => Math.round(s.length / 4);

const promptTokens = roughTokens(deepObs.systemPrompt);
const WINDOW = 200_000; // claude-sonnet-4-6 의 대략적인 컨텍스트 윈도우

console.log(`시스템 프롬프트: 약 ${promptTokens.toLocaleString()} 토큰 (어림)`);
console.log(`컨텍스트 윈도우: ${WINDOW.toLocaleString()} 토큰`);
console.log(`→ 사용자가 한 글자도 치기 전에 약 ${((promptTokens / WINDOW) * 100).toFixed(1)}% 를 씁니다.`);
console.log("\n이 비용을 내고 사는 것이 계획/파일/서브에이전트 능력입니다.");
console.log("Deep Agent 의 나머지 설계(오프로딩, 요약, 격리)는 전부");
console.log("'남은 컨텍스트를 어떻게 아낄 것인가' 에 대한 답입니다.");

/* ===== [1-6] 코스 로드맵 =====
 *
 * 여기서 본 4대 기둥을 앞으로 하나씩 분해합니다.
 *
 *   Step 02  첫 Deep Agent — 옵션 전체 지도
 *   Step 03  계획      → write_todos            (기둥 1)
 *   Step 04  파일시스템 → ls/read/write/edit    (기둥 2)
 *   Step 05  백엔드와 권한 — 파일이 어디에 저장되는가
 *   Step 06  서브에이전트 → task                (기둥 3)
 *   Step 07  시스템 프롬프트 설계               (기둥 4)
 *   Step 08  미들웨어 조합 — 하네스를 직접 만들기
 *   Step 09  HITL 과 권한 제어
 *   Step 10  장기 메모리와 스킬
 *   Step 11  스트리밍과 프로덕션
 *   Step 12  종합 — 딥 리서치 에이전트
 */
printSection("[1-6] 코스 로드맵");
console.log("Step 03 계획 / Step 04 파일시스템 / Step 06 서브에이전트 / Step 07 프롬프트");
console.log("→ 이 스텝에서 본 4대 기둥을 하나씩 분해합니다. 다음은 Step 02 입니다.");

/* ===== 참고: 커스텀 도구를 얹으면? =====
 * 내장 8개에 내 도구가 "더해집니다"(대체가 아닙니다). Step 02 의 2-6 에서 다룹니다. */
const getWeather = tool(({ city }: { city: string }) => `${city}는 언제나 맑음!`, {
  name: "get_weather",
  description: "도시의 날씨를 알려 줍니다",
  schema: z.object({ city: z.string() }),
});

const withCustom = await observe((spy) =>
  createDeepAgent({ model: MODEL, tools: [getWeather], middleware: [spy] }),
);
console.log(`\n커스텀 도구 1개를 준 Deep Agent 의 도구 수: ${withCustom.tools.length}개 (8 + 1)`);
console.log(`  → ${[...withCustom.tools].sort().join(", ")}`);
