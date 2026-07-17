/**
 * Step 06 — 서브에이전트 · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-06-subagents/exercise.ts
 *
 * 문제 1~5 는 API 키 없이 풀 수 있습니다 (task 도구의 구조를 들여다보는 문제).
 * 문제 6~7 은 실제 모델을 호출합니다.
 *
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createSubAgentMiddleware,
  StateBackend,
  GENERAL_PURPOSE_SUBAGENT,
  isAsyncSubAgent,
  type SubAgent,
  type AsyncSubAgent,
} from "deepagents";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

/** 서브에이전트 미들웨어에서 task 도구를 꺼내는 헬퍼 */
function taskToolOf(middleware: unknown) {
  const tools = (middleware as { tools?: Array<{ name: string; description: string; schema: unknown }> }).tools ?? [];
  return tools.find((t) => t.name === "task");
}

/** task 도구 설명에서 "- 이름: 설명" 목록만 뽑는 헬퍼 */
function agentList(middleware: unknown): string[] {
  return (taskToolOf(middleware)?.description ?? "")
    .split("\n")
    .filter((l) => l.startsWith("- "))
    .map((l) => l.replace("- ", ""));
}

/* ===== [문제 1] 서브에이전트를 쓸까 말까 ===== */
// 다음 4가지 상황에서 서브에이전트를 쓰는 게 맞는지 판단하고, 이유를 한 줄 주석으로 적으세요.
//
//  (a) 사용자가 "1+1은?" 이라고 물었다.
//      → 답:
//  (b) 코드베이스 전체(파일 200개)를 훑어 보안 취약점을 찾아야 한다.
//      → 답:
//  (c) 파일 하나를 읽어 오타 하나를 고쳐야 한다.
//      → 답:
//  (d) 서로 무관한 기술 5개를 각각 조사해 비교표를 만들어야 한다.
//      → 답:

/* ===== [문제 2] task 도구는 언제 생기나 ===== */
// createSubAgentMiddleware 를 두 가지로 만들어, task 도구의 "서브에이전트 목록" 을 각각 출력하세요.
//   (a) subagents: [] 이고 generalPurposeAgent 기본값
//   (b) subagents: [] 이고 generalPurposeAgent: false
// 힌트: 위의 agentList() 헬퍼를 쓰세요.
// 확인 후, (b)에서 task 도구가 어떻게 되는지 주석으로 적으세요.
function exercise2() {
  console.log("\n=== [문제 2] task 도구는 언제 생기나 ===");

  // TODO: 여기에 작성
}

/* ===== [문제 3] description 을 고쳐라 ===== */
// 아래 두 서브에이전트는 description 이 너무 모호해서 부모가 못 고릅니다.
// description 만 고쳐서, 부모가 "이번 달 매출 쿼리 좀 짜줘" 를 받았을 때
// 망설임 없이 sql 쪽을 고르게 만드세요. (systemPrompt 는 건드리지 마세요.)
// 고친 뒤 agentList() 로 부모에게 보이는 모습을 출력해 확인하세요.
function exercise3() {
  console.log("\n=== [문제 3] description 고치기 ===");

  const before: SubAgent[] = [
    { name: "db-helper", description: "DB 관련 작업", systemPrompt: "너는 SQL 전문가다." },
    { name: "report-helper", description: "리포트 관련 작업", systemPrompt: "너는 차트/리포트 렌더링 전문가다." },
  ];

  // TODO: after 배열을 만들고, before / after 를 agentList() 로 비교 출력하세요.
}

/* ===== [문제 4] 모델 차등 설계 ===== */
// "뉴스 기사 100건을 훑어 우리 회사 관련된 것만 추린 뒤, 그것들만 깊이 분석" 하는 파이프라인을
// 서브에이전트 2개로 설계하세요.
//   - 어느 쪽에 싼 모델(CHEAP_MODEL), 어느 쪽에 비싼 모델(MODEL)을 줄지 정하고
//   - 부모 모델은 무엇으로 할지 정하고
//   - 왜 그렇게 나눴는지 주석으로 적으세요.
// createDeepAgent 까지 만들어 생성이 되는지 확인하세요.
function exercise4() {
  console.log("\n=== [문제 4] 모델 차등 ===");

  // TODO: 여기에 작성
}

/* ===== [문제 5] 동기 vs 비동기 구분 ===== */
// 아래 4개 정의 중 어떤 것이 AsyncSubAgent 인지 isAsyncSubAgent() 로 판별해 출력하세요.
// 그리고 "무엇이 그 둘을 가르는가"(어떤 필드) 를 주석으로 적으세요.
function exercise5() {
  console.log("\n=== [문제 5] 동기 vs 비동기 ===");

  const candidates: Array<SubAgent | AsyncSubAgent> = [
    { name: "a", description: "조사한다", systemPrompt: "너는 조사원이다." },
    { name: "b", description: "조사한다", graphId: "researcher" },
    { name: "c", description: "코딩한다", graphId: "coder", url: "https://example.langsmith.dev" },
    { name: "d", description: "요약한다", systemPrompt: "너는 요약가다.", model: CHEAP_MODEL },
  ];

  // TODO: 여기에 작성
  // → 무엇이 둘을 가르나요? (여기에 설명)
}

/* ===== [문제 6] 부모 컨텍스트를 못 본다 ===== */
// 아래 에이전트는 "고장나 있습니다". 서브에이전트가 부모 대화를 못 보기 때문입니다.
// 1) 먼저 그대로 실행해서 어떻게 어긋나는지 관찰하세요.
// 2) 그다음 systemPrompt 를 고쳐서, 부모가 필요한 맥락을 task 프롬프트에 "다 담도록" 만드세요.
// (서브에이전트의 systemPrompt 가 아니라 "부모의" systemPrompt 를 고치는 게 포인트입니다.)
async function exercise6() {
  console.log("\n=== [문제 6] 서브에이전트는 부모 대화를 못 본다 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 건너뜁니다.");
    return;
  }

  const translator: SubAgent = {
    name: "translator",
    description: "주어진 문장을 번역한다.",
    systemPrompt: "너는 번역가다. 주어진 문장을 번역하라.",
  };

  const agent = createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    subagents: [translator],
    // TODO: 이 systemPrompt 가 문제입니다. 고치세요.
    systemPrompt: "너는 조수다. 번역이 필요하면 translator 를 불러라.",
  });

  const result = await agent.invoke({
    messages: [
      { role: "user", content: "나는 앞으로 모든 답변을 일본어로 받고 싶어. 기억해줘." },
      { role: "assistant", content: "알겠습니다. 앞으로 일본어로 답변드리겠습니다." },
      { role: "user", content: "'안녕하세요, 반갑습니다' 를 번역해줘." },
    ],
  });

  console.log("  " + String(result.messages.at(-1)?.content).split("\n").join("\n  "));
  // → translator 가 무슨 언어로 번역했나요? 왜 그런가요? (여기에 설명)
}

/* ===== [문제 7] 병렬 리서치 ===== */
// 아래 mockSearch 도구를 쓰는 researcher 서브에이전트를 만들고,
// 부모가 3개 주제(typescript / rust / go)를 "병렬로" 조사하게 만드세요.
// 요구사항:
//   - researcher 는 CHEAP_MODEL, 부모는 MODEL
//   - description 에 "주제 하나당 하나씩 병렬로 호출할 것" 을 명시
//   - 각 researcher 는 결과를 /research/<주제>.md 에 저장하고 경로만 반환
//   - 부모는 파일들을 읽어 비교표를 만듦
// 실행 후 task 호출이 몇 번 일어났는지 세어 출력하세요.
async function exercise7() {
  console.log("\n=== [문제 7] 병렬 리서치 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 건너뜁니다.");
    return;
  }

  const mockSearch = tool(
    async ({ query }) => {
      const db: Record<string, string> = {
        typescript: "TypeScript: JS 의 상위집합. 정적 타입, 컴파일 필요. 생태계 거대.",
        rust: "Rust: 소유권 모델로 GC 없이 메모리 안전. 학습 곡선 가파름. 성능 최상.",
        go: "Go: 단순한 문법, 빠른 컴파일, goroutine 동시성. GC 있음.",
      };
      const hit = Object.entries(db).find(([k]) => query.toLowerCase().includes(k));
      return hit ? hit[1] : `"${query}" 자료 없음`;
    },
    {
      name: "web_search",
      description: "기술 자료를 검색한다",
      schema: z.object({ query: z.string().describe("검색어") }),
    },
  );

  // TODO: 여기에 작성
}

async function main() {
  exercise2();
  exercise3();
  exercise4();
  exercise5();
  await exercise6();
  await exercise7();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
