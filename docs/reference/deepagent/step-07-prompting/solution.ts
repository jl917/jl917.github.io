/**
 * Step 07 — 시스템 프롬프트 설계 · 정답
 * 실행: npx tsx docs/reference/deepagent/step-07-prompting/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 여세요.
 * 각 정답 위 주석에 기대 결과와 해설이 있습니다.
 */
import "dotenv/config";
import {
  createDeepAgent,
  registerHarnessProfile,
  getHarnessProfile,
  REQUIRED_MIDDLEWARE_NAMES,
} from "deepagents";
import { createMiddleware, tool } from "langchain";
import * as z from "zod";

/* ===== 공용 준비물 ===== */

function makePromptSpy(onPrompt: (text: string) => void) {
  return createMiddleware({
    name: "PromptSpyMiddleware",
    wrapModelCall: async (request, handler) => {
      onPrompt(request.systemMessage.text ?? "");
      return handler(request);
    },
  });
}

/** 프롬프트를 한 번만 캡처해서 돌려주는 헬퍼. 모델을 실제로 한 번 호출합니다. */
async function capturePrompt(params: Parameters<typeof createDeepAgent>[0]): Promise<string> {
  let captured = "";
  const agent = await createDeepAgent({
    ...params,
    middleware: [
      ...((params?.middleware as any[]) ?? []),
      makePromptSpy((t) => {
        if (!captured) captured = t;
      }),
    ] as any,
  });
  await agent.invoke({ messages: [{ role: "user", content: "안녕" }] });
  return captured;
}

const searchDocs = tool(
  ({ query }: { query: string }) => {
    const db: Record<string, string> = {
      환불: "환불은 구매 후 14일 이내, 미개봉 상태에서만 가능합니다. (규정 3.2조)",
      배송: "배송은 결제 후 2~3 영업일이 소요됩니다. (규정 5.1조)",
    };
    const hit = Object.entries(db).find(([k]) => query.includes(k));
    return hit ? hit[1] : "NOT_FOUND";
  },
  {
    name: "search_docs",
    description: "사내 규정 문서를 검색합니다. 규정 원문을 반환합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [정답 1] =====
 * 기대 결과: (a) true — 마커가 index 0 에 있다.
 *            (b) true — 내장 프롬프트가 **지워지지 않고** 뒤에 그대로 남아 있다.
 *
 * 해설: createDeepAgent 는 문자열 systemPrompt 를 `{ prefix: systemPrompt }` 로
 * 정규화합니다. 조립은 prefix → base → suffix 순이므로 내 문자열은 항상 맨 앞이고,
 * 내장 BASE_AGENT_PROMPT 는 그 뒤에 살아남습니다.
 * "systemPrompt 를 주면 내장 프롬프트가 대체된다" 는 흔한 오해입니다.
 */
async function sol1() {
  console.log("\n===== [정답 1] =====");
  const text = await capturePrompt({ model: MODEL, systemPrompt: "### MARKER ###" });

  console.log("(a) 마커가 맨 앞인가:", text.indexOf("### MARKER ###") === 0);
  console.log("(b) 내장 프롬프트가 남아있나:", text.includes("You are a Deep Agent"));
}

/* ===== [정답 2] =====
 * 기대 결과: (a) false — base 가 사라졌다.
 *            (b) true  — 파일 지침은 **그대로 남아 있다.**
 *
 * → base: null 이 지우는 것은: `createDeepAgent` 가 붙이는 BASE_AGENT_PROMPT **한 겹뿐**이다.
 *   미들웨어(Filesystem/SubAgent/TodoList)가 wrapModelCall 에서 systemMessage 에
 *   덧붙이는 도구 지침은 조립 단계 **밖**에서 일어나므로 전혀 영향을 받지 않는다.
 */
async function sol2() {
  console.log("\n===== [정답 2] =====");
  const text = await capturePrompt({
    model: MODEL,
    systemPrompt: { prefix: "당신은 컴플라이언스 봇입니다.", base: null },
  });

  console.log("(a) 내장 base 포함:", text.includes("You are a Deep Agent")); // false
  console.log("(b) 파일 지침 포함:", text.includes("## Filesystem Tools")); // true
}

/* ===== [정답 3] =====
 * 기대 결과: base:null 쪽이 눈에 띄게 짧다 (BASE_AGENT_PROMPT 약 2천 자만큼).
 *
 * → 왜 다른가: 내부 판정이 `promptConfig.base !== undefined` 이기 때문이다.
 *   - base: undefined → "필드를 생략한 것" 으로 취급 → 내장 base 를 그대로 유지
 *   - base: null      → "명시적으로 비운 것" 으로 취급 → base 를 통째로 제거
 *   TypeScript 는 `base?: string | SystemMessage | null` 이라 둘 다 통과시킵니다.
 *   객체 스프레드로 프롬프트 설정을 조립하다 보면 실수로 undefined 가 들어가기 쉽고,
 *   그러면 "왜 내장 프롬프트가 안 지워지지?" 로 조용히 헤매게 됩니다.
 */
async function sol3() {
  console.log("\n===== [정답 3] =====");
  const withNull = await capturePrompt({
    model: MODEL,
    systemPrompt: { prefix: "P", base: null },
  });
  const withUndefined = await capturePrompt({
    model: MODEL,
    systemPrompt: { prefix: "P", base: undefined },
  });

  console.log("base:null      길이:", withNull.length);
  console.log("base:undefined 길이:", withUndefined.length);
  console.log("차이:", withUndefined.length - withNull.length, "자 (= 내장 base 프롬프트 분량)");
}

/* ===== [정답 4] =====
 * 기대 결과: PROFILE-SUFFIX 의 위치가 MY-SUFFIX 보다 **뒤**다.
 *
 * 해설: 조립 순서는 prefix → base → suffix → harnessProfile.systemPromptSuffix.
 * 즉 프로파일 suffix 가 **항상 최후미**입니다.
 * "모델별 마지막 한마디" 를 프로파일에 두면 호출부와 충돌하지 않는 이유가 이것입니다.
 */
async function sol4() {
  console.log("\n===== [정답 4] =====");
  registerHarnessProfile(MODEL, { systemPromptSuffix: "### PROFILE-SUFFIX ###" });

  const text = await capturePrompt({
    model: MODEL,
    systemPrompt: { prefix: "P", suffix: "### MY-SUFFIX ###" },
  });

  const mine = text.indexOf("### MY-SUFFIX ###");
  const profile = text.indexOf("### PROFILE-SUFFIX ###");
  console.log("MY-SUFFIX      위치:", mine);
  console.log("PROFILE-SUFFIX 위치:", profile);
  console.log("프로파일 suffix 가 더 뒤인가:", profile > mine); // true

  registerHarnessProfile(MODEL, { systemPromptSuffix: undefined });
}

/* ===== [정답 5] =====
 * 다섯 요소를 모두 채운 프롬프트. 정답은 하나가 아니지만, 아래 다섯 헤더가
 * 모두 존재하고 각 규칙이 **검증 가능한 문장**인지가 채점 기준입니다.
 *
 * 특히 "NOT_FOUND 면 X 라고만 답한다" 처럼 **도구의 실제 반환값 문자열**을 프롬프트에
 * 못박는 것이 중요합니다. "모르면 모른다고 해" 같은 추상적 지시는 잘 안 지켜집니다.
 */
const RULE_BOT_PREFIX = `당신은 사내 규정 안내 봇입니다. 규정 원문에만 근거해 답합니다. (역할)

## 워크플로
1. 사용자 질문에서 규정 키워드를 뽑는다.
2. search_docs 로 그 키워드를 검색한다.
3. 반환된 규정 원문에 근거해서만 답한다.

## 도구 사용 규칙
- 절대 기억이나 상식으로 규정을 답하지 않는다. search_docs 를 반드시 먼저 호출한다.
- search_docs 가 정확히 "NOT_FOUND" 를 반환하면, 다른 검색어로 재시도하지 말고
  "해당 규정을 찾지 못했습니다." 라고만 답한다. 내용을 지어내지 않는다.
- 사용자가 규정 키워드를 하나도 주지 않았으면 검색하지 말고 "어떤 규정이 궁금하신가요?" 라고 되묻는다.

## 출력 형식
답변 한 줄.
근거: (규정 N조)

그 외 서론/맺음말/이모지 금지.

## 중단 조건
- 답변 한 줄과 근거를 출력했으면 즉시 종료한다.
- search_docs 는 질문당 최대 1회만 호출한다.`;

async function sol5() {
  console.log("\n===== [정답 5] =====");
  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    systemPrompt: RULE_BOT_PREFIX,
  });

  for (const q of ["환불 규정 알려줘", "주차장 규정 알려줘"]) {
    const r = await agent.invoke({ messages: [{ role: "user", content: q }] });
    console.log(`\nQ: ${q}\nA: ${r.messages.at(-1)?.text}`);
  }
}

/* ===== [정답 6] =====
 * 기대 경향 (모델 응답이므로 매번 다릅니다):
 *   - BAD  : 도구를 아예 안 부르거나, 부른 뒤 NOT_FOUND 를 무시하고 그럴듯한 주차 규정을 **지어냄**
 *   - GOOD : 도구 1회 호출 → NOT_FOUND → "해당 규정을 찾지 못했습니다." 한 줄
 *
 * 해설: 환각은 모델이 나빠서가 아니라 **프롬프트가 실패 경로를 정의하지 않아서** 납니다.
 * "NOT_FOUND 면 이렇게 답해라" 한 줄이 환각을 막는 실질적 장치입니다.
 */
async function sol6() {
  console.log("\n===== [정답 6] =====");
  const question = "주차장 규정 알려줘";

  for (const [label, prompt] of [
    ["BAD ", "규정 알려줘."],
    ["GOOD", RULE_BOT_PREFIX],
  ] as const) {
    const agent = await createDeepAgent({
      model: MODEL,
      tools: [searchDocs],
      systemPrompt: prompt,
    });
    const r = await agent.invoke({ messages: [{ role: "user", content: question }] });
    const answer = r.messages.at(-1)?.text ?? "";
    const toolCalls = r.messages.filter((m) => m.getType() === "tool").length;

    console.log(`\n--- ${label} ---`);
    console.log(`도구호출=${toolCalls} 응답길이=${answer.length} 환각안함=${answer.includes("찾지 못했")}`);
    console.log(answer);
  }
}

/* ===== [정답 7] =====
 * 기대 결과:
 *   (a) Error 를 던진다:
 *       Cannot exclude required middleware "FilesystemMiddleware" —
 *       it provides essential agent capabilities that the runtime depends on.
 *   (b) SummarizationMiddleware 는 조용히 등록된다.
 *
 * → 왜 그런가: `REQUIRED_MIDDLEWARE_NAMES` 가
 *   Set(["FilesystemMiddleware", "SubAgentMiddleware"]) 이기 때문이다.
 *   이 둘은 Deep Agent 의 필수 골격(파일 도구 / task 도구)을 제공하므로
 *   프로파일로 제거할 수 없다.
 *
 * 중요한 것은 **던지는 시점**이다. createDeepAgent 가 아니라 registerHarnessProfile
 * 이 던진다 — 프로파일 옵션은 등록 시점에 createHarnessProfile 로 검증되기 때문이다.
 * 프로파일 등록을 모듈 최상단(로드 시점)에 두면 앱이 부팅하다 죽는다.
 *
 * 참고로 excludedMiddleware 엔 세 가지 검증이 더 있다:
 *   - 빈 문자열/공백만 있는 문자열 불가
 *   - ":" 포함 불가 (클래스 경로 문법은 지원하지 않음)
 *   - "_" 로 시작 불가 (비공개 미들웨어는 제외 대상이 아님)
 */
async function sol7() {
  console.log("\n===== [정답 7] =====");
  console.log("REQUIRED_MIDDLEWARE_NAMES:", [...REQUIRED_MIDDLEWARE_NAMES]);

  // (a) 필수 미들웨어 제외 시도 → throw
  try {
    registerHarnessProfile("anthropic", { excludedMiddleware: ["FilesystemMiddleware"] });
    console.log("(a) 등록 성공 — 예상과 다름!");
  } catch (err) {
    console.log("(a) 등록 실패:", (err as Error).message);
  }

  // (b) 필수가 아닌 미들웨어는 등록된다
  // ⚠️ getHarnessProfile 은 등록된 프로파일이 없으면 undefined 를 반환합니다.
  registerHarnessProfile("anthropic", { excludedMiddleware: ["SummarizationMiddleware"] });
  console.log("(b) 등록된 excludedMiddleware:", [
    ...(getHarnessProfile("anthropic")?.excludedMiddleware ?? []),
  ]);

  // 파일 지침은 당연히 그대로 남아 있다 — 애초에 뺄 수 없었으므로.
  const text = await capturePrompt({ model: MODEL, systemPrompt: "P" });
  console.log("파일 지침 포함:", text.includes("## Filesystem Tools")); // true

  // 되돌리기: 재등록은 머지이므로 빈 배열로 덮어써도 Set 은 비워지지 않는다는 점에 주의.
  // 확실히 초기화하려면 새 프로세스를 쓰거나, 애초에 테스트용 키를 쓰는 것이 낫다.
  registerHarnessProfile("anthropic", { excludedMiddleware: [] });
  console.log("되돌린 뒤:", [...(getHarnessProfile("anthropic")?.excludedMiddleware ?? [])]);
}

/* ===== [정답 8] =====
 * 기대 결과: RULE_BOT_PREFIX 는 대체로 3/3.
 * 만약 케이스 C(되묻기)가 FAIL 이면, "도구 사용 규칙" 에 아래 한 줄을 더 못박습니다:
 *   "- 질문에 구체적 규정 키워드(환불/배송 등)가 없으면 search_docs 를 호출하지 말고 되묻는다."
 *
 * 해설: 이것이 프롬프트 개선 루프의 전부입니다.
 *   실패 케이스를 고정된 테스트로 박제 → 규칙 한 줄 추가 → 재측정 → 점수가 올랐는지 확인.
 *   "프롬프트를 통째로 다시 쓰는" 것보다 훨씬 빠르고, 회귀(regression)를 잡아줍니다.
 *   LLM 응답은 비결정적이라 1회 실행으로 판정하면 노이즈가 큽니다.
 *   실무에서는 케이스당 3~5회 반복해 다수결/통과율로 봅니다.
 */
type Case = { question: string; expect: (a: string) => boolean; why: string };

const CASES: Case[] = [
  { question: "환불 규정 알려줘", expect: (a) => a.includes("14일"), why: "규정 원문 인용" },
  { question: "주차장 규정 알려줘", expect: (a) => a.includes("찾지 못했"), why: "환각 금지" },
  { question: "규정 알려줘", expect: (a) => /어떤|무엇/.test(a), why: "키워드 없으면 되묻기" },
];

async function sol8() {
  console.log("\n===== [정답 8] =====");
  const agent = await createDeepAgent({
    model: MODEL,
    tools: [searchDocs],
    systemPrompt: RULE_BOT_PREFIX,
  });

  let pass = 0;
  for (const c of CASES) {
    const r = await agent.invoke({ messages: [{ role: "user", content: c.question }] });
    const answer = r.messages.at(-1)?.text ?? "";
    const ok = c.expect(answer);
    if (ok) pass += 1;
    console.log(`[${ok ? "PASS" : "FAIL"}] ${c.question} (${c.why})`);
    if (!ok) console.log(`       실제: ${answer.slice(0, 120).replace(/\n/g, " ")}`);
  }
  console.log(`점수: ${pass}/${CASES.length}`);
}

async function main() {
  await sol1();
  await sol2();
  await sol3();
  await sol4();
  await sol5();
  await sol6();
  await sol7();
  await sol8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
