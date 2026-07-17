/**
 * Step 03 — 계획 도구 (write_todos)
 * 실행: npx tsx docs/reference/deepagent/step-03-planning-todos/practice.ts
 *
 * 이 파일은 본문 3-1 ~ 3-8 의 예제를 순서대로 담았습니다.
 * 블록 주석의 [3-N] 번호가 본문 소제목 번호와 1:1 대응합니다.
 *
 * 주의: LLM 응답은 비결정적입니다. 출력이 본문과 글자 단위로 같을 수는 없습니다.
 *       하지만 todos 배열의 "구조"({ content, status })는 결정적입니다.
 */
import "dotenv/config";
import { createDeepAgent } from "deepagents";
import {
  createAgent,
  todoListMiddleware,
  tool,
  TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT,
} from "langchain";
import * as z from "zod";

/** todos 배열을 사람이 읽기 좋게 출력하는 헬퍼 */
type Todo = { content: string; status: "pending" | "in_progress" | "completed" };

function printTodos(todos: Todo[] | undefined, label = "todos") {
  if (!todos || todos.length === 0) {
    console.log(`  (${label}: 비어 있음 — 모델이 write_todos 를 부르지 않았습니다)`);
    return;
  }
  const mark = { pending: "[ ]", in_progress: "[~]", completed: "[x]" } as const;
  console.log(`  ${label} (${todos.length}개):`);
  for (const t of todos) {
    console.log(`    ${mark[t.status]} ${t.content}`);
  }
}

/* ===== [3-1] 왜 계획이 필요한가 — 계획 없는 에이전트의 drift ===== */

/**
 * 계획 도구가 전혀 없는 일반 에이전트.
 * 여러 단계를 요구하면 앞부분만 하고 멈추거나, 뒤에서 지시를 잊는 경향(drift)이 있습니다.
 */
async function step3_1() {
  console.log("\n===== [3-1] 계획 없는 에이전트 =====");

  const plain = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    systemPrompt: "너는 기술 문서 작성 보조자다. 요청받은 작업을 수행한다.",
  });

  const task =
    "사내 REST API 가이드 문서를 만들려고 한다. " +
    "(1) 목차를 짜고 (2) 인증 章 초안을 쓰고 (3) 에러 코드 표를 만들고 " +
    "(4) 각 章마다 예제 요청/응답을 넣고 (5) 마지막에 빠진 항목을 스스로 점검해라.";

  const result = await plain.invoke({
    messages: [{ role: "user", content: task }],
  });

  const last = result.messages.at(-1);
  console.log("응답 길이:", String(last?.text ?? "").length, "자");
  console.log("응답 앞부분:\n", String(last?.text ?? "").slice(0, 400));

  // 일반 createAgent 의 상태에는 todos 키 자체가 없습니다.
  console.log("todos 키 존재?", "todos" in result);
}

/* ===== [3-2] write_todos 도구 동작 — 상태에 저장되고 매 턴 다시 주입된다 ===== */

async function step3_2() {
  console.log("\n===== [3-2] Deep Agent 의 write_todos =====");

  // createDeepAgent 는 await 이 필요합니다. todoListMiddleware 가 기본 스택에 포함되어
  // 별도 설정 없이 write_todos 도구가 붙습니다.
  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 기술 문서 작성 보조자다.",
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "사내 REST API 가이드 문서를 만들려고 한다. " +
          "(1) 목차를 짜고 (2) 인증 章 초안을 쓰고 (3) 에러 코드 표를 만들고 " +
          "(4) 각 章마다 예제 요청/응답을 넣고 (5) 마지막에 빠진 항목을 스스로 점검해라.",
      },
    ],
  });

  // 상태의 todos 를 직접 읽습니다. 타입은 { content, status }[] 입니다.
  printTodos(result.todos as Todo[], "최종 todos");

  // write_todos 가 반환한 ToolMessage 를 직접 봅니다.
  // content 형식: `Updated todo list to ${JSON.stringify(todos)}`
  const todoToolMessages = result.messages.filter(
    (m: any) => m.getType?.() === "tool" && m.name === "write_todos",
  );
  console.log(`\nwrite_todos 호출 횟수: ${todoToolMessages.length}`);
  for (const [i, m] of todoToolMessages.entries()) {
    console.log(`  #${i + 1}: ${String((m as any).text ?? "").slice(0, 120)}...`);
  }
}

/* ===== [3-3] 계획은 프롬프트다 — 주입되는 시스템 프롬프트 확인 ===== */

async function step3_3() {
  console.log("\n===== [3-3] 계획은 프롬프트다 =====");

  // langchain 이 write_todos 와 함께 주입하는 시스템 프롬프트 원문입니다.
  // 이 상수는 "langchain" 루트에서 export 되므로 직접 읽어볼 수 있습니다.
  console.log("write_todos 와 함께 주입되는 시스템 프롬프트:\n");
  console.log(TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT);

  // 핵심: todos 는 상태(state)에 살아 있고, 매 턴 모델에게 다시 보입니다.
  // 즉 계획은 "한 번 말하고 끝나는 지시"가 아니라 "매 턴 반복되는 지시"입니다.
  console.log("\n프롬프트 길이:", TODO_LIST_MIDDLEWARE_SYSTEM_PROMPT.length, "자");
}

/* ===== [3-4] todo 상태 관찰 — stream 으로 계획이 갱신되는 것 보기 ===== */

async function step3_4() {
  console.log("\n===== [3-4] 계획이 갱신되는 과정 관찰 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    systemPrompt: "너는 기술 문서 작성 보조자다.",
  });

  // streamMode: "values" → 매 스텝마다 "상태 전체"가 흘러나옵니다.
  // 여기서 chunk.todos 를 읽으면 계획이 갱신되는 순간을 볼 수 있습니다.
  const stream = await agent.stream(
    {
      messages: [
        {
          role: "user",
          content:
            "블로그 글 '타입스크립트 제네릭 입문'을 기획해라. " +
            "개요 → 예제 3개 선정 → 각 예제 설명 → 마무리 순으로 진행하고, " +
            "각 단계를 끝낼 때마다 진행 상황을 갱신해라.",
        },
      ],
    },
    { streamMode: "values" },
  );

  let prev = "";
  let tick = 0;
  for await (const chunk of stream) {
    const todos = (chunk as any).todos as Todo[] | undefined;
    const snapshot = JSON.stringify(todos ?? []);
    // 계획이 "바뀐 순간"에만 출력합니다.
    if (snapshot !== prev) {
      prev = snapshot;
      console.log(`\n--- 갱신 #${++tick} ---`);
      printTodos(todos);
    }
  }
}

/* ===== [3-5] todoListMiddleware 를 일반 createAgent 에 붙이기 ===== */

/** 예제용 가짜 도구 — 실제 네트워크를 타지 않습니다. */
const lookupSpec = tool(
  async ({ topic }) => {
    return `[${topic}] 스펙 요약: 필드 3개, 필수 2개, deprecated 1개.`;
  },
  {
    name: "lookup_spec",
    description: "주어진 주제의 사내 스펙 요약을 반환한다.",
    schema: z.object({ topic: z.string().describe("조회할 주제") }),
  },
);

async function step3_5() {
  console.log("\n===== [3-5] deepagents 없이 계획 능력만 빌려오기 =====");

  // createDeepAgent 를 쓰지 않고, 일반 createAgent 에 계획 능력만 추가합니다.
  // 파일시스템/서브에이전트 없이 "계획"만 필요할 때 이 조합이 가볍고 좋습니다.
  const agent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    systemPrompt: "너는 사내 API 문서 작성 보조자다.",
    middleware: [todoListMiddleware()],
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "결제/회원/알림 3개 도메인의 스펙을 각각 조회하고, " +
          "도메인별 요약과 deprecated 필드 목록을 정리한 표를 만들어라.",
      },
    ],
  });

  // todoListMiddleware 가 stateSchema 에 todos 를 추가했기 때문에 읽을 수 있습니다.
  printTodos(result.todos as Todo[], "일반 createAgent + todoListMiddleware");

  // OpenAI 로 바꾸려면 model 문자열만 바꾸면 됩니다:
  //   model: "openai:gpt-5.5"
}

/* ===== [3-6] 계획을 잘 세우게 하는 프롬프팅 ===== */

async function step3_6() {
  console.log("\n===== [3-6] 계획 프롬프팅 — 기본 vs 튜닝 =====");

  // (A) 기본 프롬프트
  const basic = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [todoListMiddleware()],
  });

  const a = await basic.invoke({
    messages: [{ role: "user", content: "결제/회원/알림 도메인 문서를 정리해라." }],
  });
  printTodos(a.todos as Todo[], "(A) 기본");

  // (B) systemPrompt 옵션으로 계획 규칙을 직접 지정
  //     todoListMiddleware 는 systemPrompt / toolDescription 두 옵션을 받습니다.
  //     systemPrompt 를 주면 기본 계획 프롬프트를 "대체"합니다.
  const tuned = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    middleware: [
      todoListMiddleware({
        systemPrompt: [
          "## `write_todos`",
          "",
          "복잡한 작업은 반드시 `write_todos` 로 계획을 먼저 세워라.",
          "",
          "계획 작성 규칙:",
          "- 각 항목은 **검증 가능한 산출물**로 적어라. ('조사한다' X → '결제 스펙의 필수 필드 목록을 뽑는다' O)",
          "- 항목 수는 3~7개로 유지해라. 그보다 잘게 쪼개면 관리 비용이 더 크다.",
          "- 각 항목은 '무엇을' + '어떻게 확인하는가' 를 포함해라.",
          "- 계획을 쓰는 즉시 첫 항목을 in_progress 로 표시해라.",
          "- 한 항목이 끝나면 **즉시** completed 로 바꿔라. 몰아서 처리하지 마라.",
          "- 새로 알게 된 사실이 있으면 계획을 고쳐 써라. 계획은 고정된 계약이 아니다.",
          "",
          "`write_todos` 를 한 턴에 두 번 이상 병렬로 부르지 마라.",
        ].join("\n"),
      }),
    ],
  });

  const b = await tuned.invoke({
    messages: [{ role: "user", content: "결제/회원/알림 도메인 문서를 정리해라." }],
  });
  printTodos(b.todos as Todo[], "(B) 튜닝");
}

/* ===== [3-7] 언제 계획이 방해가 되나 — 짧은 작업의 오버헤드 ===== */

async function step3_7() {
  console.log("\n===== [3-7] 짧은 작업에서의 계획 오버헤드 =====");

  const withPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
    middleware: [todoListMiddleware()],
  });

  const withoutPlan = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [],
  });

  const trivial = "TypeScript 에서 문자열을 숫자로 바꾸는 방법을 한 줄로 알려줘.";

  const r1 = await withPlan.invoke({ messages: [{ role: "user", content: trivial }] });
  const r2 = await withoutPlan.invoke({ messages: [{ role: "user", content: trivial }] });

  // usage_metadata 는 AIMessage 에 실려 옵니다. 필드명은 결정적입니다.
  const usage1 = (r1.messages.find((m: any) => m.getType?.() === "ai") as any)?.usage_metadata;
  const usage2 = (r2.messages.find((m: any) => m.getType?.() === "ai") as any)?.usage_metadata;

  console.log("계획 미들웨어 O — input_tokens:", usage1?.input_tokens);
  console.log("계획 미들웨어 X — input_tokens:", usage2?.input_tokens);
  console.log(
    "\n차이:",
    (usage1?.input_tokens ?? 0) - (usage2?.input_tokens ?? 0),
    "토큰 (계획 프롬프트 + write_todos 도구 스키마가 매 턴 붙는 비용)",
  );

  printTodos(r1.todos as Todo[], "짧은 작업의 todos");
  // 잘 만들어진 기본 프롬프트는 "3단계 미만이면 쓰지 마라"고 지시하므로
  // 대개 비어 있습니다. 하지만 토큰 비용은 이미 지불했습니다.
}

/* ===== [3-8] 종합 — 계획 + 도구를 함께 쓰는 에이전트 ===== */

async function step3_8() {
  console.log("\n===== [3-8] 종합 =====");

  const agent = await createDeepAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [lookupSpec],
    systemPrompt: [
      "너는 사내 API 문서 담당자다.",
      "",
      "작업 규칙:",
      "- 3단계 이상 걸리는 일은 반드시 계획을 먼저 세운다.",
      "- 각 계획 항목은 검증 가능한 산출물로 적는다.",
      "- 항목을 끝내면 즉시 completed 로 바꾼다.",
    ].join("\n"),
  });

  const stream = await agent.stream(
    {
      messages: [
        {
          role: "user",
          content:
            "결제/회원/알림 3개 도메인의 스펙을 조회하고, " +
            "도메인별 요약 + deprecated 필드 목록 표를 만들어라. " +
            "마지막에 빠뜨린 도메인이 없는지 점검해라.",
        },
      ],
    },
    { streamMode: "values" },
  );

  let prev = "";
  let final: any = null;
  for await (const chunk of stream) {
    final = chunk;
    const snapshot = JSON.stringify((chunk as any).todos ?? []);
    if (snapshot !== prev) {
      prev = snapshot;
      const todos = ((chunk as any).todos ?? []) as Todo[];
      const done = todos.filter((t) => t.status === "completed").length;
      const pct = todos.length ? Math.round((done / todos.length) * 100) : 0;
      console.log(`\n[진행률 ${pct}%] ${done}/${todos.length}`);
      printTodos(todos);
    }
  }

  console.log("\n최종 응답:\n", String(final?.messages?.at(-1)?.text ?? "").slice(0, 600));
}

/* ===== 실행 ===== */

async function main() {
  // 필요한 절만 주석 해제해서 돌려보세요. 전부 돌리면 API 호출이 10회 이상 발생합니다.
  await step3_1();
  await step3_2();
  await step3_3();
  await step3_4();
  await step3_5();
  await step3_6();
  await step3_7();
  await step3_8();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
