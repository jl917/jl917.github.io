/**
 * Step 07 — 시스템 프롬프트 설계
 * 실행: npx tsx docs/reference/deepagent/step-07-prompting/practice.ts
 *
 * 검증 버전: deepagents 1.11.0 / langchain 1.5.3 / @langchain/anthropic 1.5.1
 *
 * 이 파일은 본문 7-1 ~ 7-9 의 예제를 순서대로 담고 있습니다.
 * 위에서부터 차례로 실행되며, 각 블록은 독립적으로 잘라내 써도 됩니다.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
 *   (OpenAI 를 쓰려면 model 을 "openai:gpt-5.5" 로 바꾸고 OPENAI_API_KEY 를 설정하세요)
 */
import "dotenv/config";
import { createDeepAgent, registerHarnessProfile, getHarnessProfile } from "deepagents";
import { createMiddleware, tool } from "langchain";
import * as z from "zod";

/* ===== 공용 준비물 ===== */

/**
 * 조립된 최종 시스템 프롬프트를 가로채서 찍어보는 미들웨어.
 *
 * `wrapModelCall` 훅은 모델 호출 직전에 `request` 를 받습니다.
 * `request.systemMessage` 가 **미들웨어까지 전부 반영된 최종 시스템 메시지**입니다.
 * 이 스텝 내내 "실제로 모델에게 뭐가 갔는가" 를 확인하는 데 씁니다.
 */
function makePromptSpy(label: string) {
  return createMiddleware({
    name: "PromptSpyMiddleware",
    wrapModelCall: async (request, handler) => {
      const text = request.systemMessage.text ?? "";
      console.log(`\n===== [${label}] 최종 시스템 프롬프트 (${text.length}자) =====`);
      console.log(text);
      console.log(`===== [${label}] 끝 =====\n`);
      // 한 번만 찍고 싶으면 여기서 플래그를 세우세요. 매 모델 호출마다 찍힙니다.
      return handler(request);
    },
  });
}

/** 프롬프트 길이/포함 여부만 요약해서 찍는 가벼운 버전 (A/B 비교용) */
function makePromptSummarySpy(label: string) {
  return createMiddleware({
    name: "PromptSpyMiddleware",
    wrapModelCall: async (request, handler) => {
      const text = request.systemMessage.text ?? "";
      console.log(
        `[${label}] 길이=${text.length} | 내장기반="${text.includes("You are a Deep Agent")}" ` +
          `| todo지침=${text.includes("write_todos") || text.includes("todo")} ` +
          `| 파일지침=${text.includes("Filesystem Tools")} ` +
          `| task지침=${text.includes("subagent spawner")}`,
      );
      return handler(request);
    },
  });
}

/** 예제 내내 재사용할 간단한 도구 하나 */
const getStockPrice = tool(
  ({ ticker }: { ticker: string }) => {
    // 실제 API 대신 고정값. 이 스텝의 관심사는 프롬프트지 도구가 아닙니다.
    const table: Record<string, number> = { AAPL: 231.4, MSFT: 512.9, NVDA: 178.2 };
    const price = table[ticker.toUpperCase()];
    return price === undefined
      ? `Unknown ticker: ${ticker}`
      : `${ticker.toUpperCase()} = ${price} USD`;
  },
  {
    name: "get_stock_price",
    description: "티커 심볼로 현재 주가를 조회합니다. 예: AAPL, MSFT, NVDA",
    schema: z.object({ ticker: z.string().describe("주식 티커 심볼") }),
  },
);

/* ===== [7-1] 네 번째 기둥 — 상세한 시스템 프롬프트 ===== */
/**
 * 같은 모델, 같은 도구. 프롬프트만 다릅니다.
 * "한 줄짜리 프롬프트" 와 "상세한 프롬프트" 가 얼마나 다르게 행동하는지 뒤(7-6)에서 A/B 로 봅니다.
 * 여기서는 우선 Deep Agent 의 기본 동작을 확인합니다.
 */
async function step7_1() {
  console.log("\n########## [7-1] 기본 Deep Agent ##########");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "AAPL 주가 알려줘." }],
  });

  console.log(result.messages.at(-1)?.text);
}

/* ===== [7-2] 프롬프트 조립 구조 — systemPrompt 는 prefix 다 ===== */
/**
 * 핵심: `systemPrompt: "문자열"` 은 내장 프롬프트를 **교체하지 않습니다.**
 * 내부적으로 `{ prefix: "문자열" }` 로 정규화되어 내장 프롬프트 **앞**에 붙습니다.
 *
 * 아래를 실행하면 내 문장이 맨 앞에 있고, 그 뒤로
 * "You are a Deep Agent, ..." 로 시작하는 내장 프롬프트가 그대로 이어지는 것을 볼 수 있습니다.
 */
async function step7_2() {
  console.log("\n########## [7-2] systemPrompt 는 prefix 다 ##########");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "### MY-PREFIX-MARKER ###\n당신은 주식 리서치 어시스턴트입니다.",
    middleware: [makePromptSpy("7-2 문자열 systemPrompt")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
}

/* ===== [7-3] { prefix, base, suffix } — 구조체로 완전 제어 ===== */
/**
 * SystemPromptConfig = { prefix?, base?, suffix? }
 * 조립 순서: prefix → base → suffix → (하네스 프로파일의 systemPromptSuffix)
 * 구분자는 빈 줄 두 개("\n\n").
 *
 * - base 를 **생략**하면       → 내장 base 프롬프트 유지 (기본값)
 * - base: "내 문자열" 이면      → 내장 base 를 내 것으로 **교체**
 * - base: null 이면            → base 프롬프트를 **완전히 제거**
 */
async function step7_3() {
  console.log("\n########## [7-3] prefix / base / suffix ##########");

  // (A) base 를 교체
  const replaced = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: {
      prefix: "### PREFIX ###",
      base: "### 내가 직접 쓴 BASE 프롬프트. 내장 Deep Agent 프롬프트는 사라졌다. ###",
      suffix: "### SUFFIX ###",
    },
    middleware: [makePromptSpy("7-3A base 교체")],
  });
  await replaced.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // (B) base: null — base 제거. 단, 미들웨어가 붙이는 도구 지침은 그대로 남는다!
  const removed = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: {
      prefix: "당신은 사내 규정만 인용해 답하는 컴플라이언스 봇입니다. 추측 금지.",
      base: null,
    },
    middleware: [makePromptSpy("7-3B base:null")],
  });
  await removed.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // (C) 함정: base: undefined 는 "제거" 가 아니라 "생략" 이다 → 내장 base 가 그대로 남는다.
  const notRemoved = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: {
      prefix: "제거하려던 시도",
      base: undefined, // ← null 이 아니라 undefined. 내장 프롬프트가 살아있다.
    },
    middleware: [makePromptSummarySpy("7-3C base:undefined")],
  });
  await notRemoved.invoke({ messages: [{ role: "user", content: "안녕" }] });
}

/* ===== [7-4] 내장 프롬프트가 실제로 뭘 시키는가 ===== */
/**
 * 최종 시스템 프롬프트는 네 겹입니다.
 *   1. 내 prefix
 *   2. BASE_AGENT_PROMPT  (createDeepAgent 가 붙임)
 *   3. 미들웨어가 런타임에 덧붙이는 도구 지침
 *      - todoListMiddleware  → write_todos 사용 지침
 *      - FilesystemMiddleware → "## Filesystem Tools ..." 파일 지침
 *      - SubAgentMiddleware   → "## `task` (subagent spawner)" 위임 지침
 *   4. 하네스 프로파일 suffix
 *
 * 아래는 3번 겹이 "붙는 조건" 을 직접 확인합니다.
 * 서브에이전트를 하나 주면 task 지침이, 도구를 주면 파일 지침이 붙습니다.
 */
async function step7_4() {
  console.log("\n########## [7-4] 내장 프롬프트 해부 ##########");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    subagents: [
      {
        name: "ticker-checker",
        description: "티커 심볼이 유효한지만 확인하는 서브에이전트",
        systemPrompt: "티커가 유효한지 한 줄로만 답하세요.",
        tools: [getStockPrice],
      },
    ],
    systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
    middleware: [makePromptSpy("7-4 전체 조립본")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // 프롬프트에서 각 섹션이 어디쯤 있는지 눈으로 찾아보세요:
  //   "You are a Deep Agent"        ← BASE_AGENT_PROMPT 시작
  //   "## Filesystem Tools"          ← FilesystemMiddleware
  //   "## `task` (subagent spawner)" ← SubAgentMiddleware
}

/* ===== [7-5] 좋은 Deep Agent 프롬프트의 다섯 요소 ===== */
/**
 * 역할 / 워크플로 / 도구 사용 규칙 / 출력 형식 / 중단 조건.
 * 아래는 다섯 요소를 모두 채운 prefix 예시입니다.
 * 내장 base 는 그대로 두고(생략), 내 도메인 지식만 앞에 얹습니다 — 이게 권장 패턴입니다.
 */
const GOOD_PREFIX = `당신은 주식 리서치 어시스턴트입니다. (역할)

## 워크플로
1. 사용자가 요청한 티커를 확인한다.
2. get_stock_price 로 각 티커의 가격을 조회한다. 여러 개면 **반드시 병렬로** 호출한다.
3. 조회 결과를 표로 정리한다.
4. 마지막에 한 줄 코멘트를 단다.

## 도구 사용 규칙
- 가격은 절대 기억이나 추측으로 말하지 않는다. 반드시 get_stock_price 결과만 인용한다.
- get_stock_price 가 "Unknown ticker" 를 반환하면 그 티커는 "조회 불가" 로 표기하고 넘어간다.
- 티커가 3개 이상이면 write_todos 로 계획을 먼저 세운다.

## 출력 형식
| 티커 | 가격(USD) |
|---|---|
| ... | ... |

표 아래에 코멘트 한 줄. 그 외 서론/맺음말 금지.

## 중단 조건
- 모든 티커의 가격을 표에 채웠으면 즉시 종료한다.
- 같은 도구를 같은 인자로 두 번 부르지 않는다.
- 사용자가 티커를 하나도 주지 않았으면 조회하지 말고 티커를 되묻는다.`;

async function step7_5() {
  console.log("\n########## [7-5] 다섯 요소를 갖춘 프롬프트 ##########");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    systemPrompt: GOOD_PREFIX,
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "AAPL, NVDA, TSLA 가격 비교해줘." }],
  });

  console.log(result.messages.at(-1)?.text);
  // TSLA 는 표에 없으므로 "조회 불가" 로 표기되어야 합니다 — 규칙이 지켜지는지 보세요.
}

/* ===== [7-6] 나쁜 프롬프트 vs 좋은 프롬프트 A/B ===== */
/**
 * 완전히 같은 모델·도구·질문. 프롬프트만 교체합니다.
 * 나쁜 쪽은 "환각 가격", "형식 제멋대로", "안 끝나는 서론" 이 나오기 쉽습니다.
 */
const BAD_PREFIX = "주식 도와줘.";

async function step7_6() {
  console.log("\n########## [7-6] A/B 실험 ##########");

  const question = "AAPL, NVDA, TSLA 가격 비교해줘.";

  for (const [label, prompt] of [
    ["BAD ", BAD_PREFIX],
    ["GOOD", GOOD_PREFIX],
  ] as const) {
    const agent = await createDeepAgent({
      model: "anthropic:claude-sonnet-4-6",
      tools: [getStockPrice],
      systemPrompt: prompt,
    });

    const result = await agent.invoke({ messages: [{ role: "user", content: question }] });

    // 정량 지표: 도구 호출 횟수 / 총 메시지 수 / 응답 길이
    const toolCalls = result.messages.filter((m) => m.getType() === "tool").length;
    const answer = result.messages.at(-1)?.text ?? "";

    console.log(`\n--- ${label} ---`);
    console.log(`도구호출=${toolCalls} 메시지수=${result.messages.length} 응답길이=${answer.length}`);
    console.log(answer);
  }
}

/* ===== [7-7] 하네스 프로파일 (profiles) ===== */
/**
 * registerHarnessProfile(key, options) 로 "모델/프로바이더별 기본값" 을 등록합니다.
 * key 는 "anthropic" (프로바이더) 또는 "anthropic:claude-sonnet-4-6" (모델) 형식.
 *
 * 옵션:
 *   baseSystemPrompt        — 내장 base 프롬프트 자체를 교체
 *   systemPromptSuffix      — 조립된 프롬프트 **맨 뒤**에 덧붙임 (프로파일의 주력 수단)
 *   toolDescriptionOverrides— 도구 설명 문구 교체
 *   excludedTools           — 하네스 도구 제거
 *   excludedMiddleware      — 미들웨어 제거 (단, 필수 2개는 못 뺌)
 *   extraMiddleware         — 사용자 미들웨어 뒤에 추가
 *   generalPurposeSubagent  — general-purpose 서브에이전트 설정/비활성화
 *
 * ⚠️ 전역 레지스트리입니다. 한 번 등록하면 그 프로세스의 모든 createDeepAgent 에 적용됩니다.
 */
async function step7_7() {
  console.log("\n########## [7-7] 하네스 프로파일 ##########");

  registerHarnessProfile("anthropic:claude-sonnet-4-6", {
    systemPromptSuffix: "### PROFILE-SUFFIX ### 모든 답변은 한국어로, 200자 이내로 하세요.",
    generalPurposeSubagent: { enabled: false }, // general-purpose 서브에이전트 끄기
  });

  // 등록된 프로파일 확인
  // ⚠️ getHarnessProfile 은 등록된 게 없으면 undefined 를 반환합니다. ?. 로 접근하세요.
  const profile = getHarnessProfile("anthropic:claude-sonnet-4-6");
  console.log("등록된 suffix:", profile?.systemPromptSuffix);
  console.log("GP 서브에이전트:", profile?.generalPurposeSubagent);

  // createDeepAgent 호출부는 하나도 안 바꿨는데 프롬프트 끝에 suffix 가 붙습니다.
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "당신은 주식 리서치 어시스턴트입니다.",
    middleware: [makePromptSpy("7-7 프로파일 적용")],
  });

  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });

  // 정리: 이 프로세스에서 이후 예제에 영향을 주지 않도록 되돌립니다.
  // (registerHarnessProfile 은 "머지" 라서 빈 문자열로 덮어써야 합니다)
  registerHarnessProfile("anthropic:claude-sonnet-4-6", {
    systemPromptSuffix: undefined,
    generalPurposeSubagent: { enabled: true },
  });
}

/* ===== [7-8] 프롬프트 반복 개선 루프 ===== */
/**
 * 프롬프트는 "한 번 잘 쓰는" 게 아니라 "실패를 보고 고치는" 것입니다.
 * 아래는 최소 하네스: 같은 케이스 묶음을 여러 프롬프트 버전에 돌리고 점수를 비교합니다.
 */
type Case = { question: string; expect: (answer: string) => boolean; why: string };

const CASES: Case[] = [
  {
    question: "AAPL 가격 알려줘.",
    expect: (a) => a.includes("231.4"),
    why: "도구가 준 실제 가격을 인용해야 함",
  },
  {
    question: "TSLA 가격 알려줘.",
    expect: (a) => /조회 불가|Unknown|알 수 없/.test(a),
    why: "모르는 티커를 지어내면 안 됨",
  },
  {
    question: "주식 좀 알려줘.",
    expect: (a) => /어떤|티커|종목/.test(a),
    why: "티커가 없으면 되물어야 함",
  },
];

async function evalPrompt(label: string, systemPrompt: string) {
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getStockPrice],
    systemPrompt,
  });

  let pass = 0;
  for (const c of CASES) {
    const r = await agent.invoke({ messages: [{ role: "user", content: c.question }] });
    const answer = r.messages.at(-1)?.text ?? "";
    const ok = c.expect(answer);
    if (ok) pass += 1;
    console.log(`  [${ok ? "PASS" : "FAIL"}] ${c.question}  (${c.why})`);
    if (!ok) console.log(`         → 실제 응답: ${answer.slice(0, 120).replace(/\n/g, " ")}`);
  }
  console.log(`  ${label}: ${pass}/${CASES.length}`);
  return pass;
}

async function step7_8() {
  console.log("\n########## [7-8] 프롬프트 개선 루프 ##########");
  console.log("\n[v1: 나쁜 프롬프트]");
  await evalPrompt("v1", BAD_PREFIX);
  console.log("\n[v2: 다섯 요소 프롬프트]");
  await evalPrompt("v2", GOOD_PREFIX);
  // FAIL 이 난 케이스를 보고 GOOD_PREFIX 의 "도구 사용 규칙" 에 문장을 한 줄 추가해보세요.
  // 그것이 개선 루프입니다: 실패 → 규칙 추가 → 재측정.
}

/* ===== [7-9] 종합 — 코드 리뷰 에이전트 ===== */
/**
 * 다섯 요소 + base:null 완전 제어 + 프로파일 suffix 를 한 번에 씁니다.
 * base:null 을 썼지만 파일/task 지침은 미들웨어가 여전히 붙여준다는 점을 기억하세요.
 */
async function step7_9() {
  console.log("\n########## [7-9] 종합 ##########");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: {
      prefix: `당신은 TypeScript 코드 리뷰어입니다.

## 워크플로
1. /src 를 ls 로 훑어 구조를 파악한다.
2. 리뷰 대상 파일을 read_file 로 읽는다.
3. 문제를 심각도(HIGH/MEDIUM/LOW)로 분류한다.
4. /review.md 에 결과를 write_file 로 저장한다.

## 도구 사용 규칙
- 파일을 읽지 않고 리뷰하지 않는다. 추측 금지.
- 파일 경로는 항상 /로 시작한다.

## 출력 형식
심각도별 불릿. 각 항목은 "파일:라인 — 문제 — 제안" 형식.

## 중단 조건
- /review.md 를 쓴 뒤 즉시 종료한다. 같은 파일을 두 번 읽지 않는다.`,
      base: null, // 내장 Deep Agent 인격을 걷어내고 내 규칙만 남긴다
      suffix: "리뷰는 반드시 한국어로 작성한다.",
    },
    middleware: [makePromptSummarySpy("7-9")],
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "먼저 /src/util.ts 에 다음 코드를 저장하고 리뷰해줘:\n" +
          "export function sum(a: any, b: any) { return a + b }\n" +
          "export async function load(url: string) { const r = await fetch(url); return r.json() }",
      },
    ],
  });

  console.log(result.messages.at(-1)?.text);
  console.log("\n생성된 파일 목록:", Object.keys(result.files ?? {}));
}

/* ===== 실행 ===== */
async function main() {
  await step7_1();
  await step7_2();
  await step7_3();
  await step7_4();
  await step7_5();
  await step7_6();
  await step7_7();
  await step7_8();
  await step7_9();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
