/**
 * Step 07 — 시스템 프롬프트 설계 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-07-prompting/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보고 여세요.
 *
 * 필요 환경변수: ANTHROPIC_API_KEY
 */
import "dotenv/config";
import { createDeepAgent, registerHarnessProfile, getHarnessProfile } from "deepagents";
import { createMiddleware, tool } from "langchain";
import * as z from "zod";

/* ===== 공용 준비물 (그대로 쓰세요) ===== */

/** 최종 시스템 프롬프트를 가로채는 스파이 미들웨어 */
function makePromptSpy(onPrompt: (text: string) => void) {
  return createMiddleware({
    name: "PromptSpyMiddleware",
    wrapModelCall: async (request, handler) => {
      onPrompt(request.systemMessage.text ?? "");
      return handler(request);
    },
  });
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

/* ===== [문제 1] =====
 * `systemPrompt: "### MARKER ###"` 를 준 Deep Agent 를 만들고,
 * makePromptSpy 로 최종 시스템 프롬프트를 가로채서 다음 두 가지를 콘솔에 출력하세요.
 *
 *   (a) "### MARKER ###" 가 프롬프트의 **맨 앞**(index 0)에 있는가?
 *   (b) 내장 프롬프트 문구인 "You are a Deep Agent" 가 **여전히 포함**되어 있는가?
 *
 * 이 문제의 목적: systemPrompt 문자열이 "교체" 가 아니라 "prefix" 임을 스스로 증명하는 것.
 */
async function ex1() {
  console.log("\n===== [문제 1] =====");
  // 여기에 작성
}

/* ===== [문제 2] =====
 * `systemPrompt: { prefix: "...", base: null }` 로 에이전트를 만들고,
 * 최종 프롬프트에서 다음을 각각 true/false 로 출력하세요.
 *
 *   (a) "You are a Deep Agent" 포함 여부
 *   (b) "## Filesystem Tools" 포함 여부
 *
 * (b) 의 결과를 보고, base: null 이 "무엇까지" 지우는지 주석으로 한 줄 적으세요.
 */
async function ex2() {
  console.log("\n===== [문제 2] =====");
  // 여기에 작성
  // → base: null 이 지우는 것은:
}

/* ===== [문제 3] =====
 * `base: null` 과 `base: undefined` 를 각각 준 에이전트 두 개를 만들고
 * 최종 프롬프트 길이를 나란히 출력해 **다르다**는 것을 보이세요.
 *
 * 왜 다른지 주석으로 설명하세요. (힌트: 내부 판정이 `base !== undefined` 다)
 */
async function ex3() {
  console.log("\n===== [문제 3] =====");
  // 여기에 작성
  // → 왜 다른가:
}

/* ===== [문제 4] =====
 * 아래 두 가지를 동시에 설정하고, 최종 프롬프트에서 둘의 **등장 순서**를 확인하세요.
 *   - systemPrompt: { prefix: "P", suffix: "### MY-SUFFIX ###" }
 *   - registerHarnessProfile("anthropic:claude-sonnet-4-6", { systemPromptSuffix: "### PROFILE-SUFFIX ###" })
 *
 * indexOf 로 두 마커의 위치를 찍어서 어느 쪽이 더 뒤에 오는지 출력하세요.
 * 테스트가 끝나면 프로파일을 되돌려 놓으세요.
 */
async function ex4() {
  console.log("\n===== [문제 4] =====");
  // 여기에 작성
}

/* ===== [문제 5] =====
 * search_docs 도구를 쓰는 "사내 규정 안내 봇" 의 systemPrompt(prefix)를 작성하세요.
 * 반드시 다섯 요소를 모두 포함해야 합니다:
 *   역할 / 워크플로 / 도구 사용 규칙 / 출력 형식 / 중단 조건
 *
 * 요구사항:
 *   - 규정 원문을 인용하지 않고 답하면 안 된다 (환각 금지)
 *   - search_docs 가 "NOT_FOUND" 를 반환하면 "해당 규정을 찾지 못했습니다" 라고만 답한다
 *   - 출력은 "답변 한 줄 + 근거(규정 N조)" 형식
 *
 * 작성 후 "환불 규정 알려줘" 와 "주차장 규정 알려줘" 두 질문으로 실행해 확인하세요.
 */
const RULE_BOT_PREFIX = `TODO: 여기에 다섯 요소를 갖춘 프롬프트를 작성하세요.`;

async function ex5() {
  console.log("\n===== [문제 5] =====");
  // 여기에 작성
}

/* ===== [문제 6] =====
 * 나쁜 프롬프트("규정 알려줘")와 문제 5 의 RULE_BOT_PREFIX 를 A/B 로 비교하세요.
 * 질문은 "주차장 규정 알려줘" (= DB 에 없는 항목) 로 고정합니다.
 *
 * 각 프롬프트에 대해 다음을 출력하세요:
 *   - 도구 호출 횟수 (result.messages 중 getType() === "tool" 개수)
 *   - 최종 응답 길이
 *   - "찾지 못했" 문구 포함 여부 (= 환각을 안 했는가)
 */
async function ex6() {
  console.log("\n===== [문제 6] =====");
  // 여기에 작성
}

/* ===== [문제 7] =====
 * (a) registerHarnessProfile("anthropic", { excludedMiddleware: ["FilesystemMiddleware"] })
 *     를 try/catch 로 감싸 호출하고, 무슨 일이 일어나는지 출력하세요.
 * (b) 이어서 excludedMiddleware: ["SummarizationMiddleware"] 는 등록이 되는지 확인하세요.
 *
 * 두 결과의 차이를 REQUIRED_MIDDLEWARE_NAMES 를 근거로 주석에 설명하세요.
 * (힌트: import { REQUIRED_MIDDLEWARE_NAMES } from "deepagents")
 *
 * 끝나면 프로파일을 되돌려 놓으세요.
 */
async function ex7() {
  console.log("\n===== [문제 7] =====");
  // 여기에 작성
  // → 왜 그런가:
}

/* ===== [문제 8] =====
 * 문제 5 의 RULE_BOT_PREFIX 를 대상으로 최소 평가 하네스를 만드세요.
 *
 *   케이스 A: "환불 규정 알려줘"   → 응답에 "14일" 포함
 *   케이스 B: "주차장 규정 알려줘" → 응답에 "찾지 못했" 포함
 *   케이스 C: "규정 알려줘"        → 응답에 "어떤" 또는 "무엇" 포함 (되묻기)
 *
 * 3케이스를 돌려 pass/3 를 출력하고, FAIL 이 나면 RULE_BOT_PREFIX 에
 * 규칙 한 줄을 추가해 다시 돌려 점수가 오르는지 확인하세요.
 */
async function ex8() {
  console.log("\n===== [문제 8] =====");
  // 여기에 작성
}

async function main() {
  await ex1();
  await ex2();
  await ex3();
  await ex4();
  await ex5();
  await ex6();
  await ex7();
  await ex8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
