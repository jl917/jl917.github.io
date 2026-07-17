/**
 * Step 03 — 계획 도구 (write_todos) · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-03-planning-todos/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 주의: LLM 응답은 비결정적입니다. 주석의 기대값은 "경향"이며,
 *       구조(todos 의 shape, 에러 메시지)는 결정적입니다.
 */
import "dotenv/config";
import {
  createAgent,
  todoListMiddleware,
  tool,
  TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT,
} from "langchain";
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

const lookupSpec = tool(
  async ({ topic }) => `[${topic}] 스펙 요약: 필드 3개, 필수 2개, deprecated 1개.`,
  {
    name: "lookup_spec",
    description: "주어진 주제의 사내 스펙 요약을 반환한다.",
    schema: z.object({ topic: z.string().describe("조회할 주제") }),
  },
);

const FIVE_STEP_TASK =
  "사내 REST API 가이드 문서를 만들려고 한다. " +
  "(1) 목차를 짜고 (2) 인증 章 초안을 쓰고 (3) 에러 코드 표를 만들고 " +
  "(4) 각 章마다 예제 요청/응답을 넣고 (5) 마지막에 '자가 점검' 이라는 제목의 " +
  "절을 만들어 빠진 항목을 스스로 점검해라.";

/* ===== [정답 1] drift 재현 ===== */
async function sol1() {
  console.log("\n===== [정답 1] drift 재현 =====");

  const withoutPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 기술 문서 작성 보조자다.",
  });

  const withPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 기술 문서 작성 보조자다.",
    middleware: [todoListMiddleware()],
  });

  const a = await withoutPlan.invoke({ messages: [{ role: "user", content: FIVE_STEP_TASK }] });
  const b = await withPlan.invoke({ messages: [{ role: "user", content: FIVE_STEP_TASK }] });

  const textA = String(a.messages.at(-1)?.text ?? "");
  const textB = String(b.messages.at(-1)?.text ?? "");

  console.log("계획 X — '자가 점검' 포함?", textA.includes("자가 점검"));
  console.log("계획 O — '자가 점검' 포함?", textB.includes("자가 점검"));
  printTodos(b.todos as Todo[], "계획 O 의 todos");

  // → 계획 X: 5번 단계를 빠뜨리는 경우가 잦습니다. 앞의 (1)~(3)에 토큰을 다 쓰고
  //           마지막 지시를 잊습니다. (경향이며 매번 다릅니다)
  // → 계획 O: 5번이 todos 에 항목으로 박히므로 수행률이 눈에 띄게 올라갑니다.
  //
  // 해설: 이 차이는 모델 성능이 아니라 "지시의 위치" 때문입니다.
  //   계획 X 에서 (5)번은 첫 사용자 메시지의 맨 끝 한 줄이고, 턴이 쌓일수록 뒤로 밀립니다.
  //   계획 O 에서 (5)번은 매 턴 갱신되는 최근 ToolMessage 안에 pending 으로 살아 있습니다.
  //
  // 주의: 이 실험은 비결정적입니다. 계획 X 가 우연히 5단계를 다 하는 경우도 있습니다.
  //   여러 번 돌려 "경향"을 보세요. 한 번의 결과로 결론 내리지 마세요.
}

/* ===== [정답 2] 계획 프롬프트 정독 ===== */
async function sol2() {
  console.log("\n===== [정답 2] 계획 프롬프트 정독 =====");

  console.log(TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT);

  // → 쓰지 말라는 문장 1:
  //   "For simple objectives that only require a few steps, it is better to just
  //    complete the objective directly and NOT use this tool."
  // → 쓰지 말라는 문장 2:
  //   "Writing todos takes time and tokens, use it when it is helpful for managing
  //    complex many-step problems! But not for simple few-step requests."
  // → 쓰지 말라는 문장 3:
  //   "The `write_todos` tool should never be called multiple times in parallel."
  //   (엄밀히는 "병렬로 쓰지 마라" 이지만, 사용 제한 조항입니다)
  //
  // 해설: 짧은 프롬프트인데 3분의 1이 "쓰지 마라" 입니다. 설계자가 이 도구의
  //   오남용(= 사소한 요청에도 계획을 세워 토큰을 태우는 것)을 실제 위험으로
  //   본다는 뜻입니다. 본문 3-7 의 토큰 측정이 그 이유를 보여줍니다.
  //
  //   참고: write_todos 의 "도구 설명(description)" 은 이 시스템 프롬프트와 별개이며
  //   수천 자에 달합니다. 거기엔 "쓰지 말아야 할 때" 예시가 5개나 더 있습니다.
}

/* ===== [정답 3] 갱신 횟수 vs 호출 횟수 ===== */
async function sol3() {
  console.log("\n===== [정답 3] 갱신 횟수 vs 호출 횟수 =====");

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [todoListMiddleware()],
  });

  const stream = await agent.stream(
    {
      messages: [{
        role: "user",
        content:
          "결제/회원/알림 3개 도메인의 스펙을 각각 조회하고, " +
          "도메인별 요약 표를 만들어라. 각 단계마다 진행 상황을 갱신해라.",
      }],
    },
    { streamMode: "values" },
  );

  let prev: string | null = null;
  let updates = 0;
  let firstChunkTodos: unknown = "(청크 없음)";
  let final: any = null;
  let isFirst = true;

  for await (const chunk of stream) {
    final = chunk;
    if (isFirst) {
      firstChunkTodos = (chunk as any).todos;
      isFirst = false;
    }
    const snapshot = JSON.stringify((chunk as any).todos ?? []);
    if (snapshot !== prev) {
      prev = snapshot;
      updates++;
    }
  }

  const toolMsgCount = final.messages.filter(
    (m: any) => m.getType?.() === "tool" && m.name === "write_todos",
  ).length;

  console.log("첫 청크의 todos:", JSON.stringify(firstChunkTodos));
  console.log("스냅샷 갱신 횟수:", updates);
  console.log("write_todos 호출 횟수:", toolMsgCount);

  // → 갱신 횟수: 호출 횟수 + 1 (위 코드처럼 prev 를 null 로 시작한 경우)
  // → 호출 횟수: 모델이 계획을 갱신한 만큼 (보통 3~5회)
  // → 다르다면 이유:
  //
  // 해설: streamMode "values" 의 "첫 청크"는 write_todos 호출과 무관한
  //   그래프의 초기 상태입니다. todos 의 기본값이 [] 이므로 첫 청크에는
  //   todos: [] 가 실려 나옵니다. prev 를 null 로 초기화하면 이 초기값도
  //   "변화"로 세어 갱신 횟수가 1 많아집니다.
  //
  //   practice.ts 의 [3-4] 는 prev 를 "" 로 초기화했습니다. 그 경우
  //   JSON.stringify([]) === "[]" 이므로 "" 와 달라서 역시 1회 세어집니다.
  //   초기값을 세지 않으려면 prev 를 "[]" 로 시작하면 됩니다.
  //
  //   요점: 스트림의 청크 수는 "상태가 흘러나온 횟수"이지 "도구 호출 횟수"가
  //   아닙니다. 둘을 같다고 가정하면 UI 진행률이 어긋납니다.
}

/* ===== [정답 4] systemPrompt 는 대체다 ===== */
async function sol4() {
  console.log("\n===== [정답 4] systemPrompt 는 대체다 =====");

  // (A) 대체 방식 — 기본 프롬프트가 통째로 사라집니다
  const replaced = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [
      todoListMiddleware({
        systemPrompt: [
          "## `write_todos`",
          "",
          "복잡한 작업은 `write_todos` 로 계획을 세워라.",
          "IMPORTANT: 모든 todo 항목의 content 는 반드시 영어로 작성해라.",
        ].join("\n"),
      }),
    ],
  });

  const a = await replaced.invoke({
    messages: [{ role: "user", content: "결제/회원/알림 도메인 문서를 정리해라." }],
  });
  printTodos(a.todos as Todo[], "(A) 대체 — 영어 규칙만");

  // (B) 이어붙이기 방식 — 기본 프롬프트를 지키면서 규칙만 추가
  const appended = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [
      todoListMiddleware({
        systemPrompt: `${TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT}

## 추가 규칙
- IMPORTANT: 모든 todo 항목의 content 는 반드시 영어로 작성해라.`,
      }),
    ],
  });

  const b = await appended.invoke({
    messages: [{ role: "user", content: "결제/회원/알림 도메인 문서를 정리해라." }],
  });
  printTodos(b.todos as Todo[], "(B) 이어붙이기 — 기본 + 영어 규칙");

  // → 계획이 영어로 나왔나? 대체로 예. (A)(B) 둘 다 영어로 나옵니다.
  // → 대체 때문에 잃은 것:
  //   1. "한 턴에 write_todos 를 병렬로 부르지 마라" 경고
  //      → 병렬 호출 에러가 늘고, 그때마다 턴 하나를 통째로 낭비합니다.
  //   2. "간단한 작업엔 쓰지 마라 / 토큰을 낭비한다" 지침
  //      → 사소한 요청에도 계획을 세우기 시작합니다.
  //   3. "끝나면 즉시 completed 로 바꿔라 (몰아서 하지 마라)" 지침
  //      → 진행률이 0% 에 머물다 끝에 100% 로 점프해 UI 가 무용지물이 됩니다.
  //
  // 해설: 이 문제의 핵심은 "영어로 나오나"가 아니라 "대체는 조용히 손해다" 입니다.
  //   에러가 안 나기 때문에 무엇을 잃었는지 알아채기 어렵습니다.
  //   기본 동작을 유지하며 규칙만 더하려면 (B) 처럼 상수를 명시적으로 이어붙이세요.
  //
  //   같은 이름의 옵션이지만 동작이 반대라는 점도 기억하세요:
  //     createDeepAgent({ systemPrompt })      → 내장 프롬프트 "앞에 붙임"
  //     todoListMiddleware({ systemPrompt })   → 계획 프롬프트를 "대체함"
}

/* ===== [정답 5] 계획의 토큰 비용 ===== */
async function sol5() {
  console.log("\n===== [정답 5] 계획의 토큰 비용 =====");

  const withPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    middleware: [todoListMiddleware()],
  });

  const withoutPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
  });

  const trivial = "1+1은?";

  const r1 = await withPlan.invoke({ messages: [{ role: "user", content: trivial }] });
  const r2 = await withoutPlan.invoke({ messages: [{ role: "user", content: trivial }] });

  const u1 = (r1.messages.find((m: any) => m.getType?.() === "ai") as any)?.usage_metadata;
  const u2 = (r2.messages.find((m: any) => m.getType?.() === "ai") as any)?.usage_metadata;

  const t1 = u1?.input_tokens ?? 0;
  const t2 = u2?.input_tokens ?? 0;

  console.log("계획 O — input_tokens:", t1);
  console.log("계획 X — input_tokens:", t2);
  console.log("배수:", t2 ? (t1 / t2).toFixed(1) + "배" : "(측정 불가)");
  printTodos(r1.todos as Todo[], "짧은 작업의 todos");

  // → 몇 배 차이? 수십 배입니다. (모델/버전에 따라 다르지만 자릿수가 다릅니다)
  //
  // 해설: "1+1은?" 의 입력 토큰은 계획 없이 수십 개입니다. 여기에
  //   (a) TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT (수백 토큰)
  //   (b) write_todos 의 도구 설명 (수천 자 → 대략 2000 토큰 내외)
  //   가 얹힙니다. (b)가 압도적입니다.
  //
  //   결정적 사실: todos 는 [] 입니다. 모델은 기본 프롬프트의 지시대로
  //   계획을 "세우지 않았습니다". 그런데도 입력 토큰은 이미 다 나갔습니다.
  //   → 계획을 안 세운다고 비용이 안 드는 게 아닙니다. 도구가 "존재하는 것" 자체가 비용입니다.
  //
  //   실무 함의: todos 가 늘 [] 로 나오는 프로덕션 에이전트라면
  //   미들웨어를 빼세요. 에러도 없고 결과도 정상이라 아무도 눈치채지 못한 채
  //   청구서만 몇 배가 됩니다.
}

/* ===== [정답 6] 잘못된 status ===== */
async function sol6() {
  console.log("\n===== [정답 6] 잘못된 status =====");

  const mw: any = todoListMiddleware();
  const writeTodos = mw.tools[0];

  console.log("도구 이름:", writeTodos.name);          // → write_todos (결정적)

  const fakeConfig = { toolCall: { id: "call_1", name: "write_todos", args: {} } };

  // (1) 올바른 status
  const ok = await writeTodos.invoke(
    { todos: [{ content: "스펙 조회", status: "in_progress" }] },
    fakeConfig,
  );
  console.log("정상 호출 → 반환 타입:", ok?.constructor?.name);   // → Command (결정적)
  console.log("  update.todos:", JSON.stringify(ok?.update?.todos));

  // (2) 잘못된 status
  try {
    await writeTodos.invoke(
      { todos: [{ content: "스펙 조회", status: "blocked" }] },
      fakeConfig,
    );
    console.log("에러가 나지 않았습니다 (예상 밖)");
  } catch (err) {
    console.log("에러 발생:", (err as Error).message.split("\n")[0]);
  }

  // → 에러 메시지 (결정적):
  //     Received tool input did not match expected schema
  //   raw ZodError 가 그대로 튀어나오지 않습니다. LangChain 이 zod 검증 실패를
  //   ToolInputParsingException 으로 감싸서 던집니다. 원본 zod 이슈는 예외 객체
  //   내부에 있습니다. 로그에서 이 문구를 보면 "모델이 스키마에 없는 값을 보냈다"
  //   는 뜻으로 읽으면 됩니다.
  //
  // → 이 검증은 도구 함수 본문 실행 "전" 입니다.
  //
  // 해설: tool() 로 만든 도구는 schema 로 입력을 먼저 파싱합니다.
  //   파싱에 실패하면 함수 본문은 실행조차 되지 않습니다. 즉 상태의 todos 는
  //   오염되지 않습니다. 정상 호출이 Command 를 반환하는 것과 대조하세요 —
  //   실패 시엔 Command 자체가 만들어지지 않으므로 상태 갱신도 없습니다.
  //
  //   실제 에이전트 루프에서는 이 에러가 예외로 튀지 않고 ToolMessage 로
  //   모델에게 돌아갑니다. 모델은 "아 status 값이 틀렸구나" 하고 재시도합니다.
  //   → 이건 조용히 틀리지 않는 "착한 실패" 입니다. 대가는 턴 하나뿐입니다.
  //
  //   대조: 본문 3-4 의 "거짓 completed" 는 스키마상 완전히 합법이라
  //   아무 에러도 나지 않습니다. 그게 훨씬 위험합니다 (정답 8 참고).
}

/* ===== [정답 7] 계획 프롬프팅 A/B ===== */
async function sol7() {
  console.log("\n===== [정답 7] 계획 프롬프팅 A/B =====");

  const task = "결제/회원/알림 도메인 문서를 정리해라.";

  // (A) 기본
  const basic = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [todoListMiddleware()],
  });
  const a = await basic.invoke({ messages: [{ role: "user", content: task }] });
  printTodos(a.todos as Todo[], "(A) 기본");

  // (B) 검증 가능한 산출물 요구
  const tuned = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [
      todoListMiddleware({
        systemPrompt: `${TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT}

## 계획 작성 추가 규칙
- 각 항목은 **검증 가능한 산출물**로 적어라.
  나쁨: "결제 도메인 조사"
  좋음: "결제 스펙을 조회해 필수 필드 목록과 deprecated 필드 목록을 확보"
- 각 항목은 '무엇을 하는가' + '무엇이 있으면 끝난 것인가' 를 포함해라.
- 항목 수는 3~7개로 유지해라.
- 마지막 항목은 항상 산출물 검증으로 둬라.
  예: "표에 3개 도메인이 모두 있는지 확인"`,
      }),
    ],
  });
  const b = await tuned.invoke({ messages: [{ role: "user", content: task }] });
  printTodos(b.todos as Todo[], "(B) 튜닝");

  // → A 와 B 의 차이 (경향):
  //   A: "결제 도메인 조사" 처럼 동사가 모호합니다. 언제 끝난 건지 기준이 없어
  //      모델이 스펙을 한 줄만 보고도 completed 로 넘길 수 있습니다.
  //   B: "필수 필드 목록과 deprecated 필드 목록을 확보" 처럼 완료 조건이
  //      항목 안에 박혀 있습니다. 계획 자체가 체크리스트가 됩니다.
  //      마지막 검증 항목 덕에 누락 도메인도 잡힙니다.
  //
  // 해설: 계획의 품질은 곧 "완료 판정 기준의 품질" 입니다. todos 는 모델이
  //   스스로 채점하는 자기 보고인데, 채점 기준이 모호하면 후하게 줍니다.
  //   기준을 항목 안에 적어 두면 모델이 자신을 채점할 근거가 생깁니다.
  //
  //   단, 이것도 여전히 자기 보고입니다. 강제력은 없습니다 (정답 8 참고).
  //   TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT 를 이어붙여 기본 조항을 지킨 것도
  //   눈여겨보세요 (정답 4 의 교훈).
}

/* ===== [정답 8] 거짓 completed ===== */
async function sol8() {
  console.log("\n===== [정답 8] 거짓 completed =====");

  // 항상 빈 결과를 반환하는 도구 — 에러조차 내지 않습니다
  const brokenSearch = tool(
    async ({ query }) => `"${query}" 에 대한 검색 결과: 없음.`,
    {
      name: "search_docs",
      description: "사내 문서를 검색한다.",
      schema: z.object({ query: z.string().describe("검색어") }),
    },
  );

  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [brokenSearch],
    systemPrompt: [
      "너는 사내 문서 조사 담당자다.",
      "search_docs 로 자료를 찾아 보고서를 작성한다.",
      "검색 결과가 없으면 그 항목은 건너뛰고 다음 단계로 진행해라.",
      "작업이 막히더라도 멈추지 말고 끝까지 진행해라.",
    ].join("\n"),
    middleware: [todoListMiddleware()],
  });

  const result = await agent.invoke({
    messages: [{
      role: "user",
      content:
        "결제/회원/알림 3개 도메인의 사내 문서를 검색해서 " +
        "도메인별 요약 보고서를 작성해라.",
    }],
  });

  const todos = (result.todos ?? []) as Todo[];
  printTodos(todos, "최종 todos");

  const allCompleted = todos.length > 0 && todos.every((t) => t.status === "completed");
  console.log("\n전부 completed?", allCompleted);
  console.log("최종 응답:\n", String(result.messages.at(-1)?.text ?? "").slice(0, 400));

  // → 전부 completed 인가? 대체로 예 (todos 는 100% 를 가리킵니다)
  // → 실제로 작업이 됐나? 아니오. 검색 결과가 하나도 없었으므로
  //   보고서에 실제 내용이 없습니다. 빈 껍데기이거나 모델이 지어낸 내용입니다.
  // → 왜 위험한가?
  //
  // 해설: 이것이 본문 3-4 함정의 실물입니다. 주목할 점:
  //   1. 예외가 발생하지 않습니다. 도구는 정상 실행됐고 문자열을 반환했습니다.
  //   2. zod 검증도 통과합니다. "completed" 는 완벽히 합법인 값입니다.
  //   3. todos 는 100% 를 가리킵니다. UI 는 초록색 체크로 가득합니다.
  //   4. 그런데 산출물은 비어 있거나 환각입니다.
  //
  //   status 는 "모델이 자기 작업을 어떻게 생각하는가" 일 뿐,
  //   "작업이 실제로 됐는가" 가 아닙니다. 기본 프롬프트에도
  //   "ONLY mark a task as completed when you have FULLY accomplished it" 이
  //   있지만, 그건 도구의 강제 조건이 아니라 부탁입니다. 여기서는
  //   "막혀도 진행해라" 라는 우리 지시가 그 부탁을 이겼습니다.
  //
  //   방어법:
  //   - todos 를 완료 판정에 쓰지 마라. 산출물을 직접 검사해라.
  //     (예: 보고서에 3개 도메인 이름이 실제로 들어 있는지 assert)
  //   - 도구가 빈 결과를 주면 "없음" 문자열 대신 에러를 던지게 하라.
  //     조용한 빈 결과가 조용한 실패를 만듭니다.
  //   - responseFormat(구조화된 출력)으로 산출물의 형태를 강제하고,
  //     필수 필드가 비면 검증에서 걸러라.
  //   - 진행률 UI 는 "모델의 주장" 이라고 표시해라. 검증된 사실이 아닙니다.
}

/* ===== 실행 ===== */

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
