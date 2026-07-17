/**
 * Step 06 — 서브에이전트
 * 실행: npx tsx docs/reference/deepagent/step-06-subagents/practice.ts
 *
 * 이 파일은 본문 6-1 ~ 6-10 의 예제를 순서대로 담고 있습니다.
 * [6-2] ~ [6-6] 은 task 도구의 "구조" 를 들여다보는 예제라 API 키 없이도 돌아갑니다.
 * [6-9], [6-10] 은 실제 모델을 호출하므로 ANTHROPIC_API_KEY 가 필요합니다.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createSubAgentMiddleware,
  StateBackend,
  GENERAL_PURPOSE_SUBAGENT,
  DEFAULT_GENERAL_PURPOSE_DESCRIPTION,
  isAsyncSubAgent,
  type SubAgent,
  type AsyncSubAgent,
} from "deepagents";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";
// OpenAI 로 바꾸려면: const MODEL = "openai:gpt-5.5";  (OPENAI_API_KEY 필요)
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

function show(label: string, value: unknown, max = 220) {
  const s = typeof value === "string" ? value : JSON.stringify(value);
  console.log(`  ${label}: ${s.length > max ? s.slice(0, max) + " …" : s}`);
}

/** 서브에이전트 미들웨어에서 task 도구를 꺼내 보는 헬퍼 (교육용 내부 들여다보기) */
function taskToolOf(middleware: unknown) {
  const tools = (middleware as { tools?: Array<{ name: string; description: string; schema: unknown }> }).tools ?? [];
  return tools.find((t) => t.name === "task");
}

/* ===== [6-1] 컨텍스트 격리가 목적이다 ===== */
// 서브에이전트는 "일을 나눠서 빨리 하기" 가 아닙니다. 그건 부수 효과입니다.
// 진짜 목적은 "부모의 컨텍스트를 보호하기" 입니다.
//
// 부모가 직접 파일 30개를 읽으면 → 30개 내용이 전부 부모 대화에 쌓입니다.
// 서브에이전트가 읽으면      → 부모에게는 "요약 한 덩어리" 만 돌아옵니다.
//
// 아래는 그 차이를 토큰 수로 가늠해 보는 시뮬레이션입니다 (모델 호출 없음).
async function section_6_1() {
  console.log("\n=== [6-1] 컨텍스트 격리 ===");

  const files = Array.from({ length: 30 }, (_, i) => ({
    path: `/docs/note-${i + 1}.md`,
    body: `# 노트 ${i + 1}\n` + "이 문서에는 대략 400토큰 분량의 내용이 들어 있습니다. ".repeat(20),
  }));

  const 직접읽기_누적 = files.reduce((sum, f) => sum + f.body.length, 0);
  const 서브에이전트_반환 = "30개 노트를 읽고 요약함: 핵심 주제는 A, B, C 세 가지.".length;

  show("부모가 직접 30개 읽으면 부모 컨텍스트에 쌓이는 글자 수", 직접읽기_누적.toLocaleString());
  show("서브에이전트에 맡기면 부모에게 돌아오는 글자 수", 서브에이전트_반환.toLocaleString());
  show("압축비", `약 ${Math.round(직접읽기_누적 / 서브에이전트_반환)}:1`);

  console.log("  → 서브에이전트도 그 30개를 다 읽습니다. 토큰을 아끼는 게 아닙니다.");
  console.log("  → 다만 그 30개가 '부모의' 대화에는 안 남습니다. 그게 격리입니다.");
}

/* ===== [6-2] task 도구의 동작 ===== */
// 서브에이전트를 하나라도 주면 부모에게 `task` 도구가 자동으로 붙습니다.
// 부모는 이 도구로만 자식을 부를 수 있습니다.
async function section_6_2() {
  console.log("\n=== [6-2] task 도구 ===");

  const middleware = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    subagents: [
      { name: "researcher", description: "주제를 깊이 조사한다", systemPrompt: "너는 조사원이다." },
    ],
  });

  const task = taskToolOf(middleware);
  show("도구 이름", task?.name);

  // task 도구의 입력 스키마는 딱 2개 필드입니다.
  const { toJsonSchema } = await import("@langchain/core/utils/json_schema");
  console.log("\n  --- task 도구 입력 스키마 ---");
  console.log(JSON.stringify(toJsonSchema(task!.schema as never), null, 2));

  // 즉 부모는 이렇게 부릅니다:
  //   task({ subagent_type: "researcher", description: "양자컴퓨팅 최신 동향을 조사해줘" })
  //
  // 그리고 돌아오는 것은 ToolMessage 문자열 "하나" 뿐입니다.
  // 자식이 도구를 50번 불렀든 파일을 30개 읽었든, 부모는 최종 보고서 한 덩어리만 봅니다.
}

/* ===== [6-3] 기본 general-purpose 서브에이전트 ===== */
// subagents 를 안 줘도 general-purpose 하나가 자동으로 붙어 있습니다.
async function section_6_3() {
  console.log("\n=== [6-3] general-purpose ===");

  show("이름", GENERAL_PURPOSE_SUBAGENT.name);
  show("systemPrompt", GENERAL_PURPOSE_SUBAGENT.systemPrompt);
  console.log("\n  --- 기본 description (부모가 이걸 보고 고릅니다) ---");
  console.log("  " + DEFAULT_GENERAL_PURPOSE_DESCRIPTION);

  // general-purpose 는 부모의 도구를 전부 물려받고, 부모의 모델을 씁니다.
  // 끄고 싶으면 createSubAgentMiddleware 에서 generalPurposeAgent: false 를 줍니다.
  const withGP = createSubAgentMiddleware({ defaultModel: MODEL, defaultTools: [], subagents: [] });
  const withoutGP = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    generalPurposeAgent: false,
    subagents: [{ name: "researcher", description: "조사 담당", systemPrompt: "너는 조사원이다." }],
  });

  // ⚠️ description 전체에 .includes("general-purpose") 를 쓰면 안 됩니다.
  //    task 도구 설명의 "사용 요령" 문단에도 그 단어가 나오기 때문에 항상 true 가 나옵니다.
  //    실제 목록은 "- 이름: 설명" 형태의 줄들입니다.
  const agentList = (mw: unknown) =>
    (taskToolOf(mw)?.description ?? "")
      .split("\n")
      .filter((l) => l.startsWith("- "))
      .map((l) => (l.split(":")[0] ?? "").replace("- ", ""));

  show("\n  기본 상태의 서브에이전트 목록", agentList(withGP));
  show("generalPurposeAgent:false 면", agentList(withoutGP));

  // 같은 이름("general-purpose")으로 커스텀 서브에이전트를 주면 교체됩니다.
  const custom: SubAgent = {
    ...GENERAL_PURPOSE_SUBAGENT,
    name: "general-purpose",
    systemPrompt: "너는 만능 조수다. 항상 한국어로 보고한다.",
  };
  show("교체용 서브에이전트", { name: custom.name, systemPrompt: custom.systemPrompt });
}

/* ===== [6-4] 커스텀 SubAgent 정의 ===== */
// 필수는 name / description / systemPrompt 3개. 나머지는 전부 선택입니다.
async function section_6_4() {
  console.log("\n=== [6-4] 커스텀 SubAgent ===");

  const webSearch = tool(
    async ({ query }) => `[모의 검색 결과] "${query}" 에 대한 자료 3건을 찾았습니다.`,
    {
      name: "web_search",
      description: "웹을 검색해 자료를 찾는다",
      schema: z.object({ query: z.string().describe("검색어") }),
    },
  );

  // (A) 최소 정의
  const minimal: SubAgent = {
    name: "summarizer",
    description: "긴 글을 3줄로 요약한다",
    systemPrompt: "너는 요약가다. 항상 정확히 3줄로 요약하라.",
  };

  // (B) 모든 필드를 쓴 정의
  const full: SubAgent = {
    name: "researcher",
    description:
      "웹에서 특정 주제를 조사한다. 주제 하나당 하나씩 부를 것. " +
      "출처 URL 과 함께 조사 결과를 반환한다.",
    systemPrompt:
      "너는 조사원이다. web_search 로 자료를 찾고, 찾은 내용을 근거와 함께 정리하라. " +
      "추측하지 말고 찾은 것만 보고하라.",
    tools: [webSearch], // 지정하면 부모 도구를 물려받지 않고 이것만 씁니다
    model: CHEAP_MODEL, // 서브에이전트별 모델 차등 (6-6)
    responseFormat: z.object({
      // 구조화된 반환 (JSON 문자열로 부모에게 전달됨)
      summary: z.string().describe("조사 요약"),
      sources: z.array(z.string()).describe("출처 URL 목록"),
      confidence: z.number().min(0).max(1).describe("확신도"),
    }),
    permissions: [
      // 부모 권한을 "병합" 이 아니라 "완전 교체" 합니다 (Step 05 참고)
      { operations: ["read"], paths: ["/research/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
  };

  show("(A) 최소 정의", { name: minimal.name, 필드수: Object.keys(minimal).length });
  show("(B) 전체 정의", { name: full.name, 필드: Object.keys(full) });

  const agent = createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    subagents: [minimal, full],
  });
  show("에이전트 생성", typeof agent.invoke === "function");
}

/* ===== [6-5] description 이 라우팅을 결정한다 ===== */
// 부모는 자식의 systemPrompt 도, tools 도 못 봅니다. 오직 description 만 봅니다.
// 증거: task 도구의 설명 문자열에 각 서브에이전트의 description 이 그대로 박힙니다.
async function section_6_5() {
  console.log("\n=== [6-5] description 이 곧 라우팅 규칙 ===");

  const vague = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    generalPurposeAgent: false,
    subagents: [
      { name: "helper1", description: "도와준다", systemPrompt: "너는 SQL 전문가다." },
      { name: "helper2", description: "처리한다", systemPrompt: "너는 이미지 편집 전문가다." },
    ],
  });

  const clear = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    generalPurposeAgent: false,
    subagents: [
      {
        name: "sql-expert",
        description: "SQL 쿼리를 작성하고 최적화한다. 데이터베이스 스키마 질문에도 답한다.",
        systemPrompt: "너는 SQL 전문가다.",
      },
      {
        name: "image-editor",
        description: "이미지를 자르고 리사이즈하고 포맷을 변환한다.",
        systemPrompt: "너는 이미지 편집 전문가다.",
      },
    ],
  });

  console.log("\n  --- 모호한 description 이 부모에게 보이는 모습 ---");
  const vagueDesc = taskToolOf(vague)!.description;
  console.log("  " + vagueDesc.split("\n").filter((l) => l.startsWith("- ")).join("\n  "));
  console.log("  → 부모는 'SQL 짜줘' 를 받고 helper1/helper2 중 뭘 골라야 할지 알 수 없습니다.");

  console.log("\n  --- 명확한 description ---");
  const clearDesc = taskToolOf(clear)!.description;
  console.log("  " + clearDesc.split("\n").filter((l) => l.startsWith("- ")).join("\n  "));
  console.log("  → 부모는 망설임 없이 sql-expert 를 고릅니다.");

  // systemPrompt 는 task 도구 설명에 안 들어갑니다 — 부모는 못 봅니다.
  show("\n  task 설명에 'SQL 전문가다'(systemPrompt) 가 들어있나?", vagueDesc.includes("SQL 전문가다"));
}

/* ===== [6-6] 서브에이전트별 모델 차등 ===== */
// 싼 모델로 넓게 조사하고, 비싼 모델로 종합합니다.
async function section_6_6() {
  console.log("\n=== [6-6] 모델 차등 ===");

  const scout: SubAgent = {
    name: "scout",
    description: "자료를 넓게 훑어 후보를 추린다. 정확도보다 커버리지가 중요할 때 쓴다.",
    systemPrompt: "너는 정찰병이다. 빠르게 훑고 후보만 추려라.",
    model: CHEAP_MODEL, // 싼 모델
  };

  const analyst: SubAgent = {
    name: "analyst",
    description: "추려진 자료를 깊이 분석하고 결론을 낸다. 정확도가 중요할 때 쓴다.",
    systemPrompt: "너는 분석가다. 근거를 들어 결론을 내라.",
    model: MODEL, // 비싼 모델
  };

  // 부모(종합 담당)는 비싼 모델
  const agent = createDeepAgent({ model: MODEL, backend: new StateBackend(), subagents: [scout, analyst] });

  show("scout 모델", scout.model);
  show("analyst 모델", analyst.model);
  show("부모 모델", MODEL);
  show("에이전트 생성", typeof agent.invoke === "function");

  // model 을 생략하면 부모 모델을 그대로 물려받습니다.
  const inherits: SubAgent = { name: "x", description: "…", systemPrompt: "…" };
  show("model 생략 시", `부모 모델(${MODEL})을 상속`);
}

/* ===== [6-7] 비동기/병렬 서브에이전트 ===== */
// 두 가지를 구분해야 합니다.
//  (1) 동기 서브에이전트의 "병렬 실행": 부모가 task 를 여러 개 한 번에 호출 → 부모는 전부 끝날 때까지 기다림
//  (2) 비동기 서브에이전트(AsyncSubAgent): 부모가 작업을 띄우고 "즉시" 다른 일을 함
async function section_6_7() {
  console.log("\n=== [6-7] 비동기/병렬 ===");

  // (1) 동기 병렬 — 그냥 평범한 SubAgent 입니다. 부모가 한 메시지에 task 를 여러 번 부르면 됩니다.
  //     task 도구 설명에 "Launch multiple agents concurrently whenever possible" 이라고
  //     이미 지시가 들어 있어서, 모델이 알아서 병렬로 부릅니다.
  const syncSub: SubAgent = {
    name: "researcher",
    description: "주제 하나를 조사한다. 여러 주제면 주제당 하나씩 병렬로 부를 것.",
    systemPrompt: "너는 조사원이다.",
  };
  show("(1) 동기 서브에이전트", { name: syncSub.name, isAsync: isAsyncSubAgent(syncSub) });

  // (2) 비동기 서브에이전트 — graphId 로 Agent Protocol 서버의 그래프를 가리킵니다.
  //     systemPrompt 가 아니라 graphId 가 있다는 게 결정적 차이입니다.
  const asyncSubs: AsyncSubAgent[] = [
    {
      name: "long-researcher",
      description: "몇 분씩 걸리는 장기 조사를 백그라운드로 수행한다.",
      graphId: "researcher", // 같은 배포 안의 그래프 (ASGI)
    },
    {
      name: "remote-coder",
      description: "원격 배포된 코딩 에이전트.",
      graphId: "coder",
      url: "https://coder-deployment.langsmith.dev", // 원격이면 url 추가
    },
  ];
  for (const s of asyncSubs) show(`(2) ${s.name}`, { graphId: s.graphId, isAsync: isAsyncSubAgent(s) });

  // 비동기를 주면 부모는 task 대신 5개 도구를 받습니다:
  //   start_async_task / check_async_task / update_async_task / cancel_async_task / list_async_tasks
  console.log("\n  비동기 서브에이전트를 쓰면 부모가 받는 도구:");
  for (const t of ["start_async_task", "check_async_task", "update_async_task", "cancel_async_task", "list_async_tasks"]) {
    console.log(`    - ${t}`);
  }
  console.log("  → start 는 즉시 task id 를 돌려주고, 부모는 기다리지 않고 다음 일을 합니다.");
  console.log("  → 실행하려면 Agent Protocol 서버가 필요합니다 (langgraph dev --n-jobs-per-worker 10).");

  // 아래는 실제로 서버가 없으면 안 도므로 생성만 해둡니다.
  //   const agent = createDeepAgent({ model: MODEL, subagents: [...asyncSubs] });
}

/* ===== [6-8] 동적 서브에이전트 ===== */
// 인터프리터 안에서 task() 를 코드로 부릅니다 — 루프, 조건문, Promise.all 이 가능해집니다.
async function section_6_8() {
  console.log("\n=== [6-8] 동적 서브에이전트 ===");

  console.log("  @langchain/quickjs 의 createCodeInterpreterMiddleware 를 붙이면");
  console.log("  모델이 '코드로' 서브에이전트를 부를 수 있습니다:\n");

  const example = `
  // 모델이 eval 도구 안에서 작성하는 코드 (우리가 쓰는 게 아닙니다)
  const files = (await tools.glob({ pattern: "src/routes/**/*.ts" }))
    .split("\\n").filter(Boolean);

  const reviews = await Promise.all(
    files.map((file) =>
      task({
        description: \`\${file} 을 인증 취약점 관점에서 리뷰하라. 줄 번호를 인용할 것.\`,
        subagentType: "reviewer",
        responseSchema: issuesSchema,
      }),
    ),
  );
  const issues = reviews.flatMap((r) => r.issues);`;
  console.log(example);

  console.log("\n  설정은 이렇게 합니다:");
  console.log(`
  import { createCodeInterpreterMiddleware } from "@langchain/quickjs";

  const agent = createDeepAgent({
    model: MODEL,
    subagents: [{
      name: "reviewer",
      description: "코드를 보안 관점에서 리뷰하고 줄 번호와 심각도를 인용한다",
      systemPrompt: "너는 보안 중심 코드 리뷰어다.",
    }],
    middleware: [createCodeInterpreterMiddleware()],
  });`);

  console.log("\n  일반 task 와의 차이:");
  console.log("    - 일반 task: 부모가 '파일 개수만큼' 도구 호출을 직접 해야 함 (몇 개인지 미리 알아야 함)");
  console.log("    - 동적 task: 파일 개수를 런타임에 알아내서 그만큼 루프를 돎");
  console.log("  프롬프트에 'workflow' 라는 말을 넣으면 모델이 코드 오케스트레이션을 택하는 경향이 있습니다.");
}

/* ===== [6-9] 파일시스템으로 결과 주고받기 ===== */
// task 의 반환은 "문자열 하나" 입니다. 큰 결과는 파일로 넘기고 경로만 돌려받습니다.
async function section_6_9() {
  console.log("\n=== [6-9] 파일로 결과 주고받기 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 건너뜁니다.");
    return;
  }

  const writer: SubAgent = {
    name: "report-writer",
    description:
      "주제에 대한 상세 리포트를 작성해 지정된 파일 경로에 저장한다. " +
      "반환값으로는 저장한 경로와 한 줄 요약만 준다.",
    systemPrompt:
      "너는 리포트 작성자다. 요청받은 내용을 write_file 로 지정된 경로에 저장하라. " +
      "최종 답변에는 리포트 전문을 넣지 말고, 저장 경로와 한 줄 요약만 적어라.",
  };

  const agent = createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    subagents: [writer],
    systemPrompt:
      "너는 편집자다. report-writer 서브에이전트에게 리포트 작성을 시키고, " +
      "결과 파일을 read_file 로 읽어 사용자에게 핵심만 전달하라.",
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "TypeScript 의 satisfies 연산자에 대해 리포트를 /reports/satisfies.md 에 작성하게 하고, " +
          "그 파일을 읽어서 핵심 3가지만 알려줘.",
      },
    ],
  });

  console.log("\n  --- 최종 답변 (매번 다릅니다) ---");
  console.log("  " + String(result.messages.at(-1)?.content).split("\n").join("\n  "));

  // 서브에이전트가 쓴 파일은 부모 상태에 남습니다 — 같은 백엔드를 공유하기 때문입니다.
  const files = (result as { files?: Record<string, unknown> }).files;
  show("\n  상태에 남은 파일", files ? Object.keys(files) : "(없음)");
}

/* ===== [6-10] 실전 — 리서치 서브에이전트 3개 병렬 + 종합 ===== */
async function section_6_10() {
  console.log("\n=== [6-10] 병렬 리서치 + 종합 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 건너뜁니다.");
    return;
  }

  // 모의 검색 도구 (실제로는 Tavily 등을 씁니다)
  const webSearch = tool(
    async ({ query }) => {
      const db: Record<string, string> = {
        redis: "Redis: 인메모리 키-값 저장소. 단일 스레드, 매우 빠름. 영속성은 RDB/AOF 옵션.",
        postgres: "PostgreSQL: 관계형 DB. ACID 완전 지원, JSONB 로 문서도 저장 가능.",
        dynamodb: "DynamoDB: AWS 관리형 NoSQL. 무제한 확장, 키 설계가 성능을 좌우.",
      };
      const hit = Object.entries(db).find(([k]) => query.toLowerCase().includes(k));
      return hit ? hit[1] : `"${query}" 에 대한 자료를 찾지 못했습니다.`;
    },
    {
      name: "web_search",
      description: "웹에서 기술 자료를 검색한다",
      schema: z.object({ query: z.string().describe("검색어") }),
    },
  );

  const researcher: SubAgent = {
    name: "researcher",
    description:
      "기술 하나를 조사해 특징과 트레이드오프를 정리한다. " +
      "여러 기술을 비교해야 하면 기술 하나당 하나씩 병렬로 호출할 것. " +
      "조사 결과를 /research/<기술명>.md 에 저장하고 경로만 반환한다.",
    systemPrompt:
      "너는 기술 조사원이다. web_search 로 자료를 찾아 특징/장점/단점을 정리하고, " +
      "지정된 경로에 write_file 로 저장하라. 최종 답변은 '저장 경로 + 한 줄 요약' 만 적어라. " +
      "너는 부모의 대화를 볼 수 없으므로, 프롬프트에 주어진 정보만으로 판단하라.",
    tools: [webSearch],
    model: CHEAP_MODEL, // 조사는 싼 모델로
  };

  const agent = createDeepAgent({
    model: MODEL, // 종합은 비싼 모델로
    backend: new StateBackend(),
    subagents: [researcher],
    systemPrompt:
      "너는 기술 선임이다. 비교 요청을 받으면 researcher 서브에이전트를 " +
      "대상 기술마다 하나씩 '병렬로' 띄워라(한 메시지에 task 를 여러 번 호출). " +
      "각자가 저장한 파일을 읽어 최종 비교표를 만들어라.",
  });

  const result = await agent.invoke({
    messages: [
      {
        role: "user",
        content:
          "Redis, PostgreSQL, DynamoDB 를 세션 저장소 용도로 비교해줘. " +
          "각각 조사한 뒤 표로 정리해줘.",
      },
    ],
  });

  console.log("\n  --- 최종 비교 (매번 다릅니다) ---");
  console.log("  " + String(result.messages.at(-1)?.content).split("\n").join("\n  "));

  // 부모가 실제로 task 를 몇 번 불렀는지 세어 봅니다.
  const taskCalls = result.messages.flatMap((m) => {
    const calls = (m as { tool_calls?: Array<{ name: string; args: Record<string, unknown> }> }).tool_calls ?? [];
    return calls.filter((c) => c.name === "task");
  });
  console.log(`\n  task 호출 횟수: ${taskCalls.length}`);
  for (const c of taskCalls) {
    show("  → subagent_type", c.args.subagent_type);
  }
  show("  부모 메시지 총 개수", result.messages.length);
}

/* ===== 실행 ===== */
async function main() {
  await section_6_1();
  await section_6_2();
  await section_6_3();
  await section_6_4();
  await section_6_5();
  await section_6_6();
  await section_6_7();
  await section_6_8();
  await section_6_9();
  await section_6_10();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
