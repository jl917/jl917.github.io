/**
 * Step 03 — 계획 도구 (write_todos) · 연습문제
 * 실행: npx tsx docs/reference/deepagent/step-03-planning-todos/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어보세요.
 */
import "dotenv/config";
import { createAgent, todoListMiddleware, tool } from "langchain";
import * as z from "zod";

type Todo = { content: string; status: "pending" | "in_progress" | "completed" };

function printTodos(todos: Todo[] | undefined, label = "todos") {
  if (!todos || todos.length === 0) {
    console.log(`  (${label}: 비어 있음)`);
    return;
  }
  const mark = { pending: "[ ]", in_progress: "[~]", completed: "[x]" } as const;
  console.log(`  ${label} (${todos.length}개):`);
  for (const t of todos) console.log(`    ${mark[t.status]} ${t.content}`);
}

/** 문제 전반에서 쓰는 예제 도구 */
const lookupSpec = tool(
  async ({ topic }) => `[${topic}] 스펙 요약: 필드 3개, 필수 2개, deprecated 1개.`,
  {
    name: "lookup_spec",
    description: "주어진 주제의 사내 스펙 요약을 반환한다.",
    schema: z.object({ topic: z.string().describe("조회할 주제") }),
  },
);

/** 5단계 작업 — 문제 1에서 사용 */
const FIVE_STEP_TASK =
  "사내 REST API 가이드 문서를 만들려고 한다. " +
  "(1) 목차를 짜고 (2) 인증 章 초안을 쓰고 (3) 에러 코드 표를 만들고 " +
  "(4) 각 章마다 예제 요청/응답을 넣고 (5) 마지막에 '자가 점검' 이라는 제목의 " +
  "절을 만들어 빠진 항목을 스스로 점검해라.";

/* ===== [문제 1] =====
 * 계획 도구가 없는 createAgent 와 todoListMiddleware() 를 붙인 createAgent 에게
 * 동일한 5단계 작업(FIVE_STEP_TASK)을 시키고,
 * 마지막 단계(5번 = "자가 점검" 절)를 실제로 수행했는지 각각 확인하세요.
 *
 * 힌트: 최종 응답 텍스트에 "자가 점검" 이 포함되어 있는지 includes 로 검사하면 됩니다.
 * 결과를 아래 주석에 기록하세요.
 */
async function ex1() {
  console.log("\n===== [문제 1] drift 재현 =====");

  // TODO: 계획 없는 에이전트를 만들고 FIVE_STEP_TASK 를 실행하세요.

  // TODO: todoListMiddleware() 를 붙인 에이전트로 같은 작업을 실행하세요.

  // TODO: 각각의 최종 응답에 "자가 점검" 이 들어 있는지 확인해 출력하세요.

  // → 계획 X: 5번 단계 수행했나? (여기에 답)
  // → 계획 O: 5번 단계 수행했나? (여기에 답)
}

/* ===== [문제 2] =====
 * TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT 를 출력하고,
 * 그 안에서 "이 도구를 쓰지 말라" 고 지시하는 문장을 3개 찾아
 * 아래 주석에 옮겨 적으세요.
 *
 * 힌트: import { TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT } from "langchain";
 * 이 문제는 모델을 호출하지 않습니다 (API 키 불필요).
 */
async function ex2() {
  console.log("\n===== [문제 2] 계획 프롬프트 정독 =====");

  // TODO: 프롬프트 상수를 출력하세요.

  // → 쓰지 말라는 문장 1: (여기에 답)
  // → 쓰지 말라는 문장 2: (여기에 답)
  // → 쓰지 말라는 문장 3: (여기에 답)
}

/* ===== [문제 3] =====
 * agent.stream(..., { streamMode: "values" }) 로 todos 가 갱신되는 횟수를 세세요.
 * write_todos 를 호출한 횟수(name === "write_todos" 인 ToolMessage 개수)와
 * 일치하나요? 다르다면 왜 그럴까요?
 *
 * 힌트: 스트림의 "첫 청크"가 무엇인지 눈여겨보세요.
 */
async function ex3() {
  console.log("\n===== [문제 3] 갱신 횟수 vs 호출 횟수 =====");

  // TODO: todoListMiddleware 를 붙인 에이전트를 만들고,
  //       lookupSpec 을 여러 번 부르게 만드는 작업을 스트리밍으로 실행하세요.

  // TODO: todos 스냅샷이 바뀐 횟수를 세세요.

  // TODO: 최종 상태에서 write_todos ToolMessage 개수를 세세요.

  // → 갱신 횟수: (여기에 답)
  // → 호출 횟수: (여기에 답)
  // → 다르다면 이유: (여기에 답)
}

/* ===== [문제 4] =====
 * todoListMiddleware({ systemPrompt }) 로
 * "모든 항목을 반드시 영어로 작성하라" 는 규칙을 넣고,
 * 계획이 영어로 나오는지 확인하세요.
 *
 * 그리고 이 옵션이 기본 프롬프트를 "대체" 한다는 사실 때문에
 * 무엇을 잃었는지 아래 주석에 적으세요.
 */
async function ex4() {
  console.log("\n===== [문제 4] systemPrompt 는 대체다 =====");

  // TODO: systemPrompt 옵션으로 영어 규칙을 넣은 에이전트를 만드세요.

  // TODO: 계획을 세우게 하고 todos 를 출력하세요.

  // → 계획이 영어로 나왔나? (여기에 답)
  // → 대체 때문에 잃은 것: (여기에 답)
}

/* ===== [문제 5] =====
 * 짧은 작업("1+1은?")을 계획 미들웨어 O/X 로 각각 실행하고
 * usage_metadata.input_tokens 를 비교하세요. 몇 배 차이가 나나요?
 *
 * 힌트: const usage = result.messages.find((m) => m.getType() === "ai")?.usage_metadata;
 */
async function ex5() {
  console.log("\n===== [문제 5] 계획의 토큰 비용 =====");

  const trivial = "1+1은?";

  // TODO: 계획 미들웨어를 붙인 에이전트로 실행하고 input_tokens 를 구하세요.

  // TODO: 계획 미들웨어 없는 에이전트로 실행하고 input_tokens 를 구하세요.

  // TODO: 배수를 계산해 출력하세요.

  // → 몇 배 차이? (여기에 답)
}

/* ===== [문제 6] =====
 * write_todos 의 status 에 "blocked" 같은 잘못된 값을 넣어
 * 도구를 직접 호출해 보고, 어떤 에러가 나는지 확인하세요.
 *
 * 힌트: 미들웨어에서 도구를 꺼낼 수 있습니다.
 *   const mw = todoListMiddleware();
 *   const writeTodos = mw.tools[0];
 *   await writeTodos.invoke({ todos: [...] }, { toolCall: { id: "x", name: "write_todos", args: {} } });
 *
 * 이 문제는 모델을 호출하지 않습니다 (API 키 불필요).
 */
async function ex6() {
  console.log("\n===== [문제 6] 잘못된 status =====");

  // TODO: 미들웨어에서 write_todos 도구를 꺼내세요.

  // TODO: 올바른 status 로 한 번 호출해 성공을 확인하세요.

  // TODO: status: "blocked" 로 호출해 에러를 확인하세요. (try/catch)

  // → 에러 메시지: (여기에 답)
  // → 이 검증은 도구 함수 본문 실행 "전"인가 "후"인가? (여기에 답)
}

/* ===== [문제 7] =====
 * 계획 항목을 "검증 가능한 산출물" 로 쓰게 만드는 systemPrompt 를 직접 작성하고,
 * 기본 프롬프트 대비 계획이 어떻게 달라지는지 A/B 로 비교하세요.
 */
async function ex7() {
  console.log("\n===== [문제 7] 계획 프롬프팅 A/B =====");

  const task = "결제/회원/알림 도메인 문서를 정리해라.";

  // TODO: (A) 기본 todoListMiddleware() 로 실행하고 todos 를 출력하세요.

  // TODO: (B) 검증 가능한 산출물을 요구하는 systemPrompt 를 넣고 실행하세요.

  // → A 와 B 의 차이: (여기에 답)
}

/* ===== [문제 8] =====
 * result.todos 의 모든 항목이 completed 인데도
 * 실제 작업이 안 된 사례를 만들어 보세요.
 *
 * 힌트: 항상 실패하거나 빈 결과를 돌려주는 도구를 주고,
 *       "결과가 없으면 그냥 넘어가라" 고 지시합니다.
 *
 * 이것이 왜 위험한지 아래 주석에 적으세요.
 */
async function ex8() {
  console.log("\n===== [문제 8] 거짓 completed =====");

  // TODO: 항상 빈 결과를 반환하는 도구를 만드세요.

  // TODO: 그 도구만 주고, 결과가 없어도 진행하도록 지시하는 에이전트를 만드세요.

  // TODO: todos 가 전부 completed 인지 확인하세요.

  // → 전부 completed 인가? (여기에 답)
  // → 실제로 작업이 됐나? (여기에 답)
  // → 왜 위험한가? (여기에 답)
}

/* ===== 실행 ===== */

async function main() {
  // 풀고 있는 문제만 주석을 해제하세요.
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
