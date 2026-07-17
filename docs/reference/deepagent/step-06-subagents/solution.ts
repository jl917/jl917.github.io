/**
 * Step 06 — 서브에이전트 · 정답
 * 실행: npx tsx docs/reference/deepagent/step-06-subagents/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createSubAgentMiddleware,
  StateBackend,
  isAsyncSubAgent,
  type SubAgent,
  type AsyncSubAgent,
} from "deepagents";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";
const CHEAP_MODEL = "anthropic:claude-haiku-4-5";

function show(label: string, value: unknown, max = 220) {
  const s = typeof value === "string" ? value : JSON.stringify(value);
  console.log(`  ${label}: ${s.length > max ? s.slice(0, max) + " …" : s}`);
}

function taskToolOf(middleware: unknown) {
  const tools = (middleware as { tools?: Array<{ name: string; description: string; schema: unknown }> }).tools ?? [];
  return tools.find((t) => t.name === "task");
}

function agentList(middleware: unknown): string[] {
  return (taskToolOf(middleware)?.description ?? "")
    .split("\n")
    .filter((l) => l.startsWith("- "))
    .map((l) => l.replace("- ", ""));
}

/* ===== [정답 1] 서브에이전트를 쓸까 말까 ===== */
// (a) "1+1은?"
//     → 쓰지 않는다. 서브에이전트를 띄우는 것 자체가 LLM 호출 한 번 + 지연입니다.
//       task 도구 설명에도 "If the task is trivial (a few tool calls or simple lookup)" 은
//       쓰지 말라고 명시돼 있습니다.
//
// (b) 파일 200개를 훑어 취약점 찾기
//     → 쓴다. 전형적인 케이스입니다. 200개 파일 내용이 부모 컨텍스트에 쌓이면 터집니다.
//       서브에이전트가 다 읽고 "취약점 목록" 만 돌려주면 부모는 깨끗합니다.
//
// (c) 파일 하나 읽어 오타 하나 고치기
//     → 쓰지 않는다. 도구 2번이면 끝나는 일입니다. 위임이 토큰도 복잡도도 안 줄입니다.
//       오히려 자식이 파일을 다시 읽어야 해서 느려집니다.
//
// (d) 무관한 기술 5개 조사 후 비교
//     → 쓴다. 그리고 "병렬로" 씁니다. 각 조사는 서로 독립적이고,
//       각자 자료를 많이 읽지만 부모는 5개 요약만 받으면 됩니다.
//       task 설명의 LeBron/Jordan/Kobe 예시가 정확히 이 패턴입니다.
//
// 판단 기준 한 줄: "이 일이 컨텍스트를 많이 먹는데, 중간 과정은 안 봐도 되는가?"
//                  둘 다 예 → 서브에이전트. 하나라도 아니오 → 그냥 직접.

/* ===== [정답 2] task 도구는 언제 생기나 ===== */
function solution2() {
  console.log("\n=== [정답 2] task 도구는 언제 생기나 ===");

  const withGP = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    subagents: [],
  });
  const withoutGP = createSubAgentMiddleware({
    defaultModel: MODEL,
    defaultTools: [],
    generalPurposeAgent: false,
    subagents: [],
  });

  show("(a) subagents:[] + 기본값 → 목록", agentList(withGP));
  show("(b) subagents:[] + generalPurposeAgent:false → 목록", agentList(withoutGP));
  show("(b) 에서 task 도구 자체가 있나?", taskToolOf(withoutGP) !== undefined);

  // 해설:
  //  (a) subagents 를 하나도 안 줬는데도 general-purpose 가 있습니다.
  //      → Deep Agent 는 기본으로 general-purpose 를 붙입니다. task 도구도 그래서 생깁니다.
  //  (b) generalPurposeAgent: false + subagents: [] 이면 목록이 빈 배열이 됩니다.
  //      → 그런데 task 도구 자체는 "여전히 존재합니다" (위 출력의 마지막 줄).
  //        부를 대상이 하나도 없는 도구가 모델에게 노출돼 있는 상태입니다.
  //
  // ⚠️ 함정 1: "나는 subagents 를 안 줬으니 서브에이전트가 없다" 고 생각하기 쉽지만
  //          general-purpose 가 이미 있습니다. 즉 모델은 언제든 task 를 부를 수 있고,
  //          그러면 예상 못 한 LLM 호출과 지연과 비용이 생깁니다.
  //
  // ⚠️ 함정 2: 반대로 generalPurposeAgent: false 로 다 끄면 목록만 비고 도구는 남습니다.
  //          모델이 그 빈 도구를 부르려 시도할 수 있습니다. 서브에이전트를 안 쓸 거라면
  //          createSubAgentMiddleware 를 아예 넣지 않는 쪽이 깔끔합니다.
  //
  // 💡 참고: createDeepAgent 는 이 미들웨어를 기본 스택에 넣어줍니다.
  //    여기서 createSubAgentMiddleware 를 직접 부른 건 내부를 들여다보려는 교육용 목적입니다.
  //    실무에서는 createDeepAgent({ subagents: [...] }) 만 쓰면 됩니다.
}

/* ===== [정답 3] description 을 고쳐라 ===== */
function solution3() {
  console.log("\n=== [정답 3] description 고치기 ===");

  const before: SubAgent[] = [
    { name: "db-helper", description: "DB 관련 작업", systemPrompt: "너는 SQL 전문가다." },
    { name: "report-helper", description: "리포트 관련 작업", systemPrompt: "너는 차트/리포트 렌더링 전문가다." },
  ];

  const after: SubAgent[] = [
    {
      name: "db-helper",
      // 좋은 description = 동사로 시작 + 무엇을 받아 무엇을 주는지 + 언제 부르는지
      description:
        "SQL 쿼리를 작성하고 최적화한다. 매출/집계/조인 같은 데이터 조회 요청이나 " +
        "테이블 스키마 질문이 오면 이걸 부를 것. 쿼리문과 설명을 반환한다.",
      systemPrompt: "너는 SQL 전문가다.",
    },
    {
      name: "report-helper",
      description:
        "이미 계산된 데이터를 받아 차트나 리포트 문서로 렌더링한다. " +
        "'표로 그려줘', '차트로 보여줘' 같은 시각화 요청에만 부를 것. " +
        "데이터를 조회하지는 못한다.",
      systemPrompt: "너는 차트/리포트 렌더링 전문가다.",
    },
  ];

  const mk = (subs: SubAgent[]) =>
    createSubAgentMiddleware({
      defaultModel: MODEL,
      defaultTools: [],
      generalPurposeAgent: false,
      subagents: subs,
    });

  console.log("\n  --- before (부모에게 보이는 전부) ---");
  for (const l of agentList(mk(before))) console.log("    - " + l);
  console.log("  → '이번 달 매출 쿼리 좀 짜줘' … db-helper? report-helper? 둘 다 '관련 작업' 입니다.");

  console.log("\n  --- after ---");
  for (const l of agentList(mk(after))) console.log("    - " + l);
  console.log("  → 'SQL 쿼리를 작성' + '매출/집계 요청이 오면' 이 있으니 db-helper 로 확정됩니다.");

  // ⚠️ 함정 (이 문제의 핵심): 부모는 자식의 systemPrompt 를 볼 수 없습니다.
  //    before 의 systemPrompt 에는 "너는 SQL 전문가다" 라고 또렷이 적혀 있지만,
  //    그건 자식이 실행될 때 쓰이는 것이지 부모의 선택에는 아무 영향이 없습니다.
  //    부모가 보는 것은 오직 description 뿐입니다.
  const desc = taskToolOf(mk(before))!.description;
  show("\n  task 설명에 'SQL 전문가다'(systemPrompt) 가 있나?", desc.includes("SQL 전문가다"));

  // 💡 description 작성 공식:
  //    "[동사]한다. [언제 부르는지]. [무엇을 반환하는지]."
  //    그리고 헷갈리는 형제가 있으면 "~는 못 한다" 로 경계를 그어주세요.
}

/* ===== [정답 4] 모델 차등 설계 ===== */
function solution4() {
  console.log("\n=== [정답 4] 모델 차등 ===");

  const screener: SubAgent = {
    name: "screener",
    description:
      "뉴스 기사 목록을 훑어 특정 키워드와 관련된 기사만 추린다. " +
      "많은 양을 빠르게 걸러야 할 때 부를 것. 관련 기사 제목 목록만 반환한다.",
    systemPrompt: "너는 선별가다. 관련 있는 것만 추려라. 분석하지 말고 추리기만 하라.",
    model: CHEAP_MODEL, // ← 싼 모델: 단순 분류, 양이 많음
  };

  const analyst: SubAgent = {
    name: "analyst",
    description:
      "추려진 기사 몇 건을 깊이 읽고 시사점과 리스크를 분석한다. " +
      "정확한 판단이 필요할 때 부를 것. 근거와 함께 분석 결과를 반환한다.",
    systemPrompt: "너는 분석가다. 근거를 들어 시사점과 리스크를 정리하라.",
    model: MODEL, // ← 비싼 모델: 소수, 정확도가 중요
  };

  const agent = createDeepAgent({
    model: MODEL, // ← 부모도 비싼 모델: 최종 종합은 품질이 중요
    backend: new StateBackend(),
    subagents: [screener, analyst],
    systemPrompt:
      "너는 리서치 리드다. 먼저 screener 로 관련 기사를 추리고, " +
      "추려진 것만 analyst 에게 넘겨 분석시킨 뒤 종합하라.",
  });

  show("screener", screener.model);
  show("analyst", analyst.model);
  show("부모", MODEL);
  show("에이전트 생성", typeof agent.invoke === "function");

  // 왜 이렇게 나눴나:
  //  - screener 는 "기사 100건" 이라는 큰 입력을 받지만 하는 일은 단순 분류입니다.
  //    양 × 단순 → 싼 모델이 정확히 맞는 자리입니다.
  //  - analyst 는 입력이 몇 건뿐이지만 틀리면 안 됩니다.
  //    소수 × 중요 → 비싼 모델.
  //  - 부모는 최종 결과물을 만듭니다. 사용자가 실제로 읽는 글이니 비싼 모델.
  //
  // 💡 일반 원칙: "입력이 크고 판단이 단순한 곳" 에 싼 모델을 넣으면 비용이 가장 많이 절감됩니다.
  //    반대로 부모(종합)를 싼 모델로 바꾸는 건 대개 손해입니다 — 품질 저하가 바로 보입니다.
  //
  // ⚠️ 함정: 싼 모델은 도구 호출을 덜 안정적으로 합니다.
  //    서브에이전트에게 도구를 많이 주면서 싼 모델을 쓰면, 도구를 안 부르거나
  //    엉뚱한 인자를 넣고도 "완료했습니다" 라고 보고합니다. 부모는 그걸 그대로 믿습니다
  //    (task 설명에 "The agent's outputs should generally be trusted" 라고 적혀 있습니다).
  //    싼 모델 서브에이전트는 도구를 최소로 주고 역할을 좁히세요.
}

/* ===== [정답 5] 동기 vs 비동기 구분 ===== */
function solution5() {
  console.log("\n=== [정답 5] 동기 vs 비동기 ===");

  const candidates: Array<SubAgent | AsyncSubAgent> = [
    { name: "a", description: "조사한다", systemPrompt: "너는 조사원이다." },
    { name: "b", description: "조사한다", graphId: "researcher" },
    { name: "c", description: "코딩한다", graphId: "coder", url: "https://example.langsmith.dev" },
    { name: "d", description: "요약한다", systemPrompt: "너는 요약가다.", model: CHEAP_MODEL },
  ];

  for (const c of candidates) {
    show(c.name, isAsyncSubAgent(c) ? "AsyncSubAgent (비동기)" : "SubAgent (동기)");
  }

  // → 무엇이 둘을 가르나요?
  //   `graphId` 필드의 존재입니다.
  //   - SubAgent      = systemPrompt 로 "여기서 즉석에서" 만들어지는 에이전트. task 로 부르고, 부모가 기다립니다.
  //   - AsyncSubAgent = graphId 로 "Agent Protocol 서버에 이미 배포된" 그래프를 가리킵니다.
  //                     start_async_task 로 띄우면 즉시 task id 가 오고, 부모는 안 기다립니다.
  //                     url 이 있으면 원격, 없으면 같은 배포 안(ASGI).
  //
  // ⚠️ 함정: 비동기는 "병렬" 과 다릅니다.
  //    동기 서브에이전트도 부모가 한 메시지에서 task 를 여러 번 부르면 "병렬로" 돕니다.
  //    다만 부모는 전부 끝날 때까지 기다립니다 (블로킹).
  //    비동기의 진짜 가치는 "부모가 안 기다리는 것" — 즉 사용자와 계속 대화하면서
  //    백그라운드로 몇 분짜리 작업을 굴리는 것입니다.
  //    "빨리 하고 싶어서" 비동기를 쓰는 거라면 잘못 고른 것입니다. 그냥 병렬 task 를 쓰세요.
}

/* ===== [정답 6] 부모 컨텍스트를 못 본다 ===== */
async function solution6() {
  console.log("\n=== [정답 6] 서브에이전트는 부모 대화를 못 본다 ===");

  if (!process.env.ANTHROPIC_API_KEY) {
    console.log("  ANTHROPIC_API_KEY 가 없어 건너뜁니다.");
    return;
  }

  const translator: SubAgent = {
    name: "translator",
    description: "주어진 문장을 지정된 언어로 번역한다. 대상 언어를 반드시 명시해서 부를 것.",
    systemPrompt: "너는 번역가다. 요청받은 대상 언어로 번역하라. 대상 언어가 없으면 되물어라.",
  };

  const messages = [
    { role: "user" as const, content: "나는 앞으로 모든 답변을 일본어로 받고 싶어. 기억해줘." },
    { role: "assistant" as const, content: "알겠습니다. 앞으로 일본어로 답변드리겠습니다." },
    { role: "user" as const, content: "'안녕하세요, 반갑습니다' 를 번역해줘." },
  ];

  // (1) 고장난 버전 — 부모가 맥락을 안 넘깁니다.
  const broken = createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    subagents: [translator],
    systemPrompt: "너는 조수다. 번역이 필요하면 translator 를 불러라.",
  });

  const r1 = await broken.invoke({ messages });
  console.log("\n  --- (1) 고장난 버전 (매번 다릅니다) ---");
  console.log("  " + String(r1.messages.at(-1)?.content).split("\n").join("\n  "));

  // (2) 고친 버전 — 부모가 task 프롬프트에 맥락을 "다 담도록" 지시합니다.
  const fixed = createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    subagents: [translator],
    systemPrompt:
      "너는 조수다. 번역이 필요하면 translator 서브에이전트를 불러라.\n" +
      "**중요**: 서브에이전트는 지금까지의 대화를 전혀 볼 수 없다. 완전히 백지 상태에서 시작한다.\n" +
      "그러므로 task 의 description 에는 서브에이전트가 일을 끝내는 데 필요한 모든 것을 담아야 한다:\n" +
      "- 번역할 원문 전체\n" +
      "- 대상 언어 (대화에서 사용자가 지정한 언어를 네가 직접 찾아서 명시할 것)\n" +
      "- 원하는 출력 형식\n" +
      "'사용자가 아까 말한 언어로' 같은 표현은 서브에이전트에게 아무 의미가 없다.",
  });

  const r2 = await fixed.invoke({ messages });
  console.log("\n  --- (2) 고친 버전 (매번 다릅니다) ---");
  console.log("  " + String(r2.messages.at(-1)?.content).split("\n").join("\n  "));

  // 부모가 실제로 자식에게 뭘 넘겼는지 봅시다 — 이게 이 문제의 핵심입니다.
  const taskArgs = r2.messages.flatMap((m) => {
    const calls = (m as { tool_calls?: Array<{ name: string; args: Record<string, unknown> }> }).tool_calls ?? [];
    return calls.filter((c) => c.name === "task").map((c) => c.args);
  });
  console.log("\n  --- 부모가 자식에게 넘긴 프롬프트 ---");
  for (const a of taskArgs) show("description", a.description, 400);

  // → translator 가 무슨 언어로 번역했나요? 왜 그런가요?
  //   (1) 에서는 대개 영어로 번역합니다. "일본어로 받고 싶어" 는 부모 대화에만 있고,
  //       translator 는 그 대화를 못 보기 때문입니다. translator 가 받는 것은
  //       "'안녕하세요, 반갑습니다' 를 번역해줘" 라는 문장 하나뿐이고,
  //       대상 언어가 없으니 기본값(영어)으로 갑니다.
  //   (2) 에서는 부모가 대화를 읽고 "일본어로" 를 직접 프롬프트에 박아 넣으므로 제대로 됩니다.
  //
  // ⚠️ 이게 서브에이전트 최대의 함정입니다. 그리고 "에러가 안 납니다."
  //    번역은 됐고, 결과도 그럴듯합니다. 언어만 틀렸습니다.
  //    부모는 자식을 믿으므로 검증도 안 합니다.
  //
  // 💡 원칙: task 의 description 은 "처음 만난 외주 업체에게 보내는 작업 지시서" 라고 생각하세요.
  //    그 사람은 우리 회사의 맥락을 하나도 모릅니다. 필요한 건 전부 문서에 적어야 합니다.
}

/* ===== [정답 7] 병렬 리서치 ===== */
async function solution7() {
  console.log("\n=== [정답 7] 병렬 리서치 ===");

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

  const researcher: SubAgent = {
    name: "researcher",
    // description 에 "병렬" 지시를 넣는 것이 핵심입니다. 부모는 이것만 봅니다.
    description:
      "기술 하나를 조사해 특징과 트레이드오프를 정리한다. " +
      "여러 기술을 비교해야 하면 반드시 기술 하나당 하나씩 '병렬로' 호출할 것. " +
      "조사 결과를 지정된 파일 경로에 저장하고, 저장 경로와 한 줄 요약만 반환한다.",
    systemPrompt:
      "너는 기술 조사원이다. web_search 로 자료를 찾아 특징/장점/단점을 정리하고 " +
      "지정된 경로에 write_file 로 저장하라.\n" +
      "너는 부모의 대화를 볼 수 없다. 프롬프트에 주어진 정보만으로 판단하라.\n" +
      "최종 답변에는 조사 전문을 넣지 말고 '저장 경로 + 한 줄 요약' 만 적어라.",
    tools: [mockSearch],
    model: CHEAP_MODEL, // 조사는 싼 모델
  };

  const agent = createDeepAgent({
    model: MODEL, // 종합은 비싼 모델
    backend: new StateBackend(),
    subagents: [researcher],
    systemPrompt:
      "너는 기술 선임이다. 비교 요청을 받으면 researcher 서브에이전트를 대상 기술마다 하나씩 " +
      "'병렬로' 띄워라 — 한 메시지 안에서 task 를 여러 번 호출하면 병렬로 실행된다.\n" +
      "각 서브에이전트에게는 조사할 기술명과 저장할 파일 경로(/research/<기술명>.md)를 명시하라.\n" +
      "모두 끝나면 각 파일을 read_file 로 읽어 최종 비교표를 만들어라.",
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: "TypeScript, Rust, Go 를 백엔드 언어로 비교해줘." }],
  });

  console.log("\n  --- 최종 비교표 (매번 다릅니다) ---");
  console.log("  " + String(result.messages.at(-1)?.content).split("\n").join("\n  "));

  // task 호출 횟수 세기
  const taskCalls = result.messages.flatMap((m) => {
    const calls = (m as { tool_calls?: Array<{ name: string; args: Record<string, unknown> }> }).tool_calls ?? [];
    return calls.filter((c) => c.name === "task");
  });
  console.log(`\n  task 호출 횟수: ${taskCalls.length} (기대: 3)`);

  // 한 메시지에 여러 task 가 들어 있으면 병렬로 실행된 것입니다.
  const perMessage = result.messages.map((m) => {
    const calls = (m as { tool_calls?: Array<{ name: string }> }).tool_calls ?? [];
    return calls.filter((c) => c.name === "task").length;
  }).filter((n) => n > 0);
  show("메시지당 task 호출 수", perMessage);
  console.log("  → [3] 처럼 한 메시지에 3개면 병렬. [1,1,1] 이면 순차입니다.");

  const files = (result as { files?: Record<string, unknown> }).files;
  show("서브에이전트들이 남긴 파일", files ? Object.keys(files) : "(없음)");

  // ⚠️ 함정: 병렬 서브에이전트가 "같은 파일" 에 쓰면 서로를 덮어씁니다.
  //    그래서 위에서 경로를 /research/<기술명>.md 로 "기술마다 다르게" 지정했습니다.
  //    만약 전부 /research/result.md 에 쓰라고 했다면 마지막에 끝난 하나만 남고
  //    나머지는 조용히 사라집니다 — 에러 없이.
  //
  // ⚠️ 함정 2: 서브에이전트가 또 서브에이전트를 부르면 비용이 곱해집니다.
  //    researcher 에게 tools 를 명시했기 때문에(mockSearch 만) task 도구가 없습니다.
  //    tools 를 생략했다면 부모 도구를 물려받아 자식이 또 task 를 부를 수 있습니다.
  //    3개 × 3개 = 9개 에이전트가 되는 식이죠. 서브에이전트의 tools 는 좁게 주세요.
}

async function main() {
  solution2();
  solution3();
  solution4();
  solution5();
  await solution6();
  await solution7();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
