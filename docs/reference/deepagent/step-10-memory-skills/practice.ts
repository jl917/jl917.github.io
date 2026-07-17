/**
 * Step 10 — 장기 메모리와 스킬
 * 실행: npx tsx docs/reference/deepagent/step-10-memory-skills/practice.ts
 *
 * 본문 10-1 ~ 10-9 의 예제를 순서대로 담았습니다.
 * 검증 버전: deepagents 1.11.0 / langchain 1.5.3 / @langchain/langgraph 1.4.8
 */
import "dotenv/config";
import {
  createDeepAgent,
  createMemoryMiddleware,
  createSkillsMiddleware,
  CompositeBackend,
  StateBackend,
  StoreBackend,
  FilesystemBackend,
} from "deepagents";
import { InMemoryStore, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";
// OpenAI 대안: const MODEL = "openai:gpt-5.5";

/**
 * state 의 files 에 넣을 FileData 객체를 만드는 헬퍼.
 *
 * 주의: files 는 `Record<string, string>` 이 아닙니다.
 * `{ content, mimeType, created_at, modified_at }` 형태의 FileData 객체를 요구합니다.
 * 문자열을 그대로 넣으면 타입 에러가 납니다.
 */
function textFile(content: string) {
  const now = new Date().toISOString();
  return { content, mimeType: "text/markdown", created_at: now, modified_at: now };
}

/* ===== [10-1] memory 옵션 — Deep Agent 의 장기 기억 ===== */
// memory: string[] — 시스템 프롬프트에 주입할 "메모리 파일 경로" 목록입니다.
// 벡터 DB 가 아니라 그냥 파일입니다. 매 턴 읽혀서 프롬프트에 붙습니다.

async function step10_1() {
  console.log("\n===== [10-1] memory 옵션 =====");

  const store = new InMemoryStore();

  const agent = await createDeepAgent({
    model: MODEL,
    // memory 경로가 영속되려면 그 경로를 StoreBackend 로 라우팅해야 합니다.
    // StateBackend 만 쓰면 스레드가 끝나는 순간 사라집니다 (= 장기 기억이 아님).
    backend: new CompositeBackend(new StateBackend(), {
      "/memories/": new StoreBackend({ namespace: () => ["demo-user"] }),
    }),
    store, // StoreBackend 를 쓰려면 store 를 반드시 넘겨야 합니다
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"], // 이 파일 내용이 시스템 프롬프트에 주입됩니다
  });

  // 아직 파일이 없으므로 주입될 내용도 없습니다. 에러는 나지 않습니다.
  const r = await agent.invoke(
    { messages: [{ role: "user", content: "내 이름이 뭐라고 했지?" }] },
    { configurable: { thread_id: "10-1" } },
  );
  console.log("응답:", r.messages.at(-1)?.text);

  return { agent, store };
}

/* ===== [10-2] 파일시스템 = 메모리 — CompositeBackend 로 구성 ===== */
// CompositeBackend(defaultBackend, routes)
//   defaultBackend: 라우트에 안 걸리는 모든 경로가 여기로
//   routes:        경로 접두사 → 백엔드

async function step10_2() {
  console.log("\n===== [10-2] CompositeBackend 로 영속 경로 만들기 =====");

  const store = new InMemoryStore();

  const backend = new CompositeBackend(
    // 기본: 스레드 안에서만 사는 임시 작업 공간
    new StateBackend(),
    {
      // /memories/ 아래만 영속. 스레드가 바뀌어도 남습니다.
      "/memories/": new StoreBackend({ namespace: () => ["demo-user"] }),
    },
  );

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"],
    systemPrompt:
      "너는 사용자를 기억하는 도우미다. 사용자가 알려준 선호는 /memories/AGENTS.md 에 write_file 로 저장해라.",
  });

  // --- 스레드 A: 정보를 알려주고 저장시킨다
  await agent.invoke(
    {
      messages: [
        {
          role: "user",
          content: "나는 들여쓰기를 스페이스 2칸으로 쓴다. 기억해줘.",
        },
      ],
    },
    { configurable: { thread_id: "10-2-A" } },
  );

  // --- 스레드 B: 완전히 새 대화. 대화 기록은 공유되지 않는다.
  const rB = await agent.invoke(
    { messages: [{ role: "user", content: "내 들여쓰기 취향이 뭐였지?" }] },
    { configurable: { thread_id: "10-2-B" } },
  );

  // 스레드가 달라도 /memories/AGENTS.md 는 남아 있으므로 답할 수 있습니다.
  console.log("스레드 B 응답:", rB.messages.at(-1)?.text);

  // store 에 실제로 뭐가 들었는지 직접 확인
  const items = await store.search(["demo-user"]);
  console.log("store 에 저장된 키:", items.map((i) => i.key));
}

/* ===== [10-3] createMemoryMiddleware ===== */
// memory: [...] 옵션은 내부적으로 이 미들웨어를 붙여줍니다.
// 직접 쓰면 addCacheControl 같은 옵션을 제어할 수 있습니다.

async function step10_3() {
  console.log("\n===== [10-3] createMemoryMiddleware 직접 쓰기 =====");

  const store = new InMemoryStore();
  const backend = new CompositeBackend(new StateBackend(), {
    "/memories/": new StoreBackend({ namespace: () => ["demo-user"] }),
  });

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    // memory: [...] 를 쓰지 않고 미들웨어를 직접 붙입니다
    middleware: [
      createMemoryMiddleware({
        backend,
        sources: ["/memories/AGENTS.md", "/memories/PROJECT.md"], // 순서대로 로드
        // 메모리 블록에 cache_control 을 붙여 프롬프트 캐싱을 활성화 (Anthropic)
        // 메모리는 매 턴 똑같이 붙는 큰 덩어리라 캐싱 효과가 큽니다.
        addCacheControl: true,
      }),
    ],
  });

  console.log("sources 2개를 순서대로 주입하는 메모리 미들웨어 구성 완료");
  return agent;
}

/* ===== [10-4] 메모리 읽기/쓰기 전략 ===== */
// 언제 저장할지를 (a) 모델에게 맡기기 (b) 코드로 강제하기

async function step10_4() {
  console.log("\n===== [10-4] 저장 시점 전략 =====");

  const store = new InMemoryStore();
  const backend = new CompositeBackend(new StateBackend(), {
    "/memories/": new StoreBackend({ namespace: () => ["demo-user"] }),
  });

  // --- (a) 모델에게 맡기기: 프롬프트로 "언제 쓸지" 를 지시한다
  const modelDriven = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"],
    systemPrompt: `너는 사용자를 기억하는 코딩 도우미다.

## 메모리 규칙
- 사용자가 "기억해", "앞으로는" 이라고 말하면 /memories/AGENTS.md 에 저장해라.
- 저장 전에 반드시 read_file 로 현재 내용을 먼저 읽어라.
- 기존 내용을 지우지 말고 edit_file 로 항목을 추가해라.
- 일회성 사실(오늘 날씨 등)은 저장하지 마라. 지속되는 선호만 저장해라.`,
  });

  const r = await modelDriven.invoke(
    { messages: [{ role: "user", content: "앞으로 커밋 메시지는 한국어로 써줘." }] },
    { configurable: { thread_id: "10-4-a" } },
  );
  console.log("(a) 모델 주도 응답:", r.messages.at(-1)?.text);

  // --- (b) 코드로 강제하기: 저장 전용 도구를 만들고 스키마로 형식을 강제한다
  const rememberPreference = tool(
    async ({ category, rule }) => {
      // 실제로는 여기서 store 에 직접 씁니다. 형식이 코드로 보장됩니다.
      return `기록했습니다: [${category}] ${rule}`;
    },
    {
      name: "remember_preference",
      description:
        "사용자의 지속적인 선호를 기록한다. 일회성 사실이 아니라 앞으로도 계속 적용될 규칙에만 사용해라.",
      schema: z.object({
        category: z.enum(["code-style", "communication", "workflow"]),
        rule: z.string().describe("한 문장으로 된 규칙. 예: '커밋 메시지는 한국어로 쓴다'"),
      }),
    },
  );

  const toolDriven = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    tools: [rememberPreference],
    memory: ["/memories/AGENTS.md"],
  });

  const r2 = await toolDriven.invoke(
    { messages: [{ role: "user", content: "앞으로 커밋 메시지는 한국어로 써줘." }] },
    { configurable: { thread_id: "10-4-b" } },
  );
  console.log("(b) 도구 주도 응답:", r2.messages.at(-1)?.text);
}

/* ===== [10-5] Skills — 절차적 지식을 파일로 주기 ===== */
// 스킬은 "이 일을 이렇게 하라" 는 절차서입니다.
// SKILL.md 의 frontmatter(name, description)만 항상 로드되고,
// 본문은 모델이 필요하다고 판단할 때만 읽힙니다 (progressive disclosure).

const SKILL_MD = `---
name: code-review
description: 코드 리뷰를 수행할 때 사용한다. PR 리뷰, 코드 검토, 개선점 찾기 요청에 활성화된다.
---

# 코드 리뷰 절차

다음 순서를 반드시 지켜라.

1. glob 으로 변경 대상 파일 목록을 만든다.
2. 각 파일을 read_file 로 읽는다. 추측하지 말고 반드시 읽어라.
3. 아래 체크리스트로 검토한다.
   - 에러 처리가 빠진 곳
   - 하드코딩된 비밀값
   - 테스트되지 않은 분기
4. 발견한 문제를 심각도(high/medium/low)와 함께 목록으로 보고한다.
5. 파일을 직접 수정하지 마라. 보고만 해라.
`;

async function step10_5() {
  console.log("\n===== [10-5] 스킬이란 무엇인가 =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    skills: ["/skills/"], // 이 디렉터리를 스캔해 SKILL.md 가 있는 하위 폴더를 스킬로 로드
  });

  // StateBackend 를 쓸 때는 files 로 스킬 내용을 직접 주입할 수 있습니다.
  const result = await agent.invoke(
    {
      messages: [{ role: "user", content: "/workspace 의 코드를 리뷰해줘." }],
      files: {
        "/skills/code-review/SKILL.md": textFile(SKILL_MD),
        "/workspace/app.ts": textFile("export const apiKey = 'sk-hardcoded-secret';\n"),
      },
    },
    { configurable: { thread_id: "10-5" } },
  );

  console.log("응답:", result.messages.at(-1)?.text);
}

/* ===== [10-6] skills 옵션 / createSkillsMiddleware ===== */
// sources 의 두 가지 형식:
//   "/skills/"          → 디렉터리를 스캔, SKILL.md 를 가진 하위 폴더를 각각 로드
//   "/skills/my-skill/" → SKILL.md 가 루트에 있는 단일 스킬 디렉터리

async function step10_6() {
  console.log("\n===== [10-6] createSkillsMiddleware =====");

  // 디스크의 실제 스킬 폴더를 읽는 구성
  const backend = new FilesystemBackend({ rootDir: process.cwd(), virtualMode: true });

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    checkpointer: new MemorySaver(),
    middleware: [
      createSkillsMiddleware({
        backend,
        sources: [
          "/skills/", // 디렉터리 스캔
          "/skills/code-review/", // 단일 스킬 직접 지정
        ],
        // 같은 name 의 스킬이 있으면 뒤에 온 source 가 이깁니다 (last one wins)
      }),
    ],
  });

  console.log("skills 미들웨어 구성 완료 (sources: 디렉터리 스캔 + 단일 스킬)");
  return agent;
}

/* ===== [10-7] 스킬 vs 도구 vs 서브에이전트 ===== */
// 같은 "코드 리뷰" 기능을 세 가지 방식으로 구현해 차이를 본다.

async function step10_7() {
  console.log("\n===== [10-7] 세 가지 방식 비교 =====");

  // (a) 도구 — 결정적 코드를 실행한다. 컨텍스트를 안 먹는다.
  const runLinter = tool(
    async ({ path }) => `${path}: 3개 경고 발견 (no-unused-vars x2, no-console x1)`,
    {
      name: "run_linter",
      description: "지정한 경로에 린터를 실행하고 경고 목록을 반환한다.",
      schema: z.object({ path: z.string() }),
    },
  );

  // (b) 스킬 — 절차서. 같은 컨텍스트에서 모델이 읽고 따른다.
  // (위 SKILL_MD 를 files 로 주입)

  // (c) 서브에이전트 — 별도 컨텍스트에서 독립적으로 돈다.
  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    tools: [runLinter],
    skills: ["/skills/"],
    subagents: [
      {
        name: "security-auditor",
        description: "보안 취약점만 집중적으로 감사한다. 결과 요약만 반환한다.",
        systemPrompt: "너는 보안 감사자다. 비밀값 노출과 인젝션 위험만 찾아 보고해라.",
      },
    ],
  });

  const result = await agent.invoke(
    {
      messages: [{ role: "user", content: "/workspace 를 리뷰하고 보안 감사도 함께 해줘." }],
      files: {
        "/skills/code-review/SKILL.md": textFile(SKILL_MD),
        "/workspace/app.ts": textFile("export const apiKey = 'sk-hardcoded-secret';\n"),
      },
    },
    { configurable: { thread_id: "10-7" } },
  );

  console.log("응답:", result.messages.at(-1)?.text);
}

/* ===== [10-8] Deep Agent 에서의 RAG — 벡터 검색 vs grep ===== */
// Deep Agent 는 파일시스템을 갖고 있어서 grep 이라는 선택지가 하나 더 있습니다.

async function step10_8() {
  console.log("\n===== [10-8] RAG — 벡터 검색 vs grep =====");

  // (a) 벡터 검색을 도구로: 결과를 파일에 offload 하고 경로만 반환하는 것이 핵심
  const backend = new StateBackend();

  const searchDocs = tool(
    async ({ query }) => {
      // 실제로는 vectorStore.similaritySearch(query, 4) 를 호출합니다.
      // 여기서는 형태만 보여주기 위해 가짜 결과를 씁니다.
      const fakeHits = [
        { source: "guide.md", text: `${query} 관련 내용 1` },
        { source: "api.md", text: `${query} 관련 내용 2` },
      ];

      const paths: string[] = [];
      for (const [i, hit] of fakeHits.entries()) {
        const path = `/retrieved/chunk_${i + 1}.md`;
        paths.push(path);
        // 청크 본문을 컨텍스트에 붓지 않고 파일로 내려놓습니다
      }

      // 반환값은 "본문" 이 아니라 "경로 목록" — 이게 offload 패턴의 핵심입니다.
      return `${paths.length}개 청크를 저장했습니다:\n${paths.join("\n")}`;
    },
    {
      name: "search_documentation",
      description: "문서를 검색해 관련 청크를 파일시스템에 저장하고 경로를 반환한다.",
      schema: z.object({ query: z.string().describe("자연어 검색 질의") }),
    },
  );

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    checkpointer: new MemorySaver(),
    tools: [searchDocs],
    systemPrompt:
      "문서 질문에는 search_documentation 으로 검색한 뒤, 저장된 파일을 read_file 로 읽어 답해라.",
  });

  const result = await agent.invoke(
    { messages: [{ role: "user", content: "인증은 어떻게 설정해?" }] },
    { configurable: { thread_id: "10-8" } },
  );
  console.log("(a) 벡터 검색 응답:", result.messages.at(-1)?.text);

  // (b) grep 으로 파일 뒤지기: 도구도 인덱싱도 임베딩도 필요 없다.
  // 내장 grep/glob 만으로 충분한 경우가 생각보다 많습니다.
  const grepAgent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    systemPrompt: "질문에 답하려면 grep 과 glob 으로 /docs 안을 직접 뒤져라.",
  });

  const r2 = await grepAgent.invoke(
    {
      messages: [{ role: "user", content: "인증은 어떻게 설정해?" }],
      files: {
        "/docs/auth.md": textFile("# 인증\n\nAPI 키를 ANTHROPIC_API_KEY 환경변수에 넣으세요."),
        "/docs/deploy.md": textFile("# 배포\n\ndocker build 로 이미지를 만듭니다."),
      },
    },
    { configurable: { thread_id: "10-8-b" } },
  );
  console.log("(b) grep 응답:", r2.messages.at(-1)?.text);
}

/* ===== [10-9] 실전: 프로젝트 규칙을 기억하는 코딩 어시스턴트 ===== */

const CODING_SKILL = `---
name: apply-project-rules
description: 이 프로젝트의 코드를 작성하거나 수정할 때 사용한다. 코드 작성, 리팩터링, 파일 생성 요청에 활성화된다.
---

# 프로젝트 규칙 적용 절차

1. /memories/AGENTS.md 를 read_file 로 읽어 현재 규칙을 확인한다.
2. 수정 대상 파일을 read_file 로 읽는다.
3. 규칙을 지켜 코드를 작성한다.
4. 규칙과 충돌하는 요청을 받으면 즉시 실행하지 말고 사용자에게 확인한다.
`;

async function step10_9() {
  console.log("\n===== [10-9] 프로젝트 규칙을 기억하는 코딩 어시스턴트 =====");

  const store = new InMemoryStore();
  const USER_ID = "user-123"; // 실전에서는 인증된 사용자 ID

  const backend = new CompositeBackend(new StateBackend(), {
    // 사용자별로 네임스페이스를 분리 — 이게 없으면 정보가 새어나갑니다
    "/memories/": new StoreBackend({ namespace: () => ["users", USER_ID, "memories"] }),
    // 스킬은 전체 공유 (사용자별로 다를 이유가 없음)
    "/skills/": new StoreBackend({ namespace: () => ["shared", "skills"] }),
  });

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"],
    skills: ["/skills/"],
    systemPrompt: `너는 이 프로젝트의 코딩 어시스턴트다.

## 메모리 규칙
- 사용자가 "앞으로", "항상", "기억해" 라고 말하면 /memories/AGENTS.md 에 규칙을 추가해라.
- 추가 전에 read_file 로 먼저 읽고, edit_file 로 항목만 덧붙여라. 덮어쓰지 마라.
- 이미 있는 규칙과 모순되면 사용자에게 물어봐라.`,
    // 메모리와 스킬은 읽기만 — 모델이 스킬 파일을 고치지 못하게 막습니다
    permissions: [
      { operations: ["write"], paths: ["/skills/**"], mode: "deny" },
      { operations: ["read", "write"], paths: ["/memories/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      { operations: ["read"], paths: ["/skills/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
  });

  // 스킬을 store 에 미리 심어둡니다 (실전에서는 배포 시점에 넣습니다).
  // key 는 전체 경로이고, value 는 FileData 와 같은 shape 입니다
  // ({ content, mimeType?, created_at, modified_at }).
  // created_at / modified_at 을 빠뜨리면 읽을 때 undefined 가 섞입니다.
  await store.put(
    ["shared", "skills"],
    "/skills/apply-project-rules/SKILL.md",
    textFile(CODING_SKILL),
  );

  // --- 세션 1: 규칙을 알려준다
  await agent.invoke(
    {
      messages: [
        {
          role: "user",
          content: "앞으로 이 프로젝트에서는 함수를 화살표 함수로만 쓰고, 세미콜론은 생략해줘.",
        },
      ],
    },
    { configurable: { thread_id: "10-9-session-1" } },
  );

  // --- 세션 2: 완전히 새 대화. 대화 기록은 없지만 메모리는 남아 있다.
  const r2 = await agent.invoke(
    {
      messages: [{ role: "user", content: "/workspace/util.ts 에 두 수를 더하는 함수를 만들어줘." }],
    },
    { configurable: { thread_id: "10-9-session-2" } },
  );

  console.log("세션 2 응답:", r2.messages.at(-1)?.text);
  console.log("생성된 파일:", Object.keys(r2.files ?? {}));

  // 메모리가 실제로 저장됐는지 확인
  const memItems = await store.search(["users", USER_ID, "memories"]);
  console.log("저장된 메모리 키:", memItems.map((i) => i.key));
}

/* ===== 실행 ===== */
async function main() {
  await step10_1();
  await step10_2();
  await step10_3();
  await step10_4();
  await step10_5();
  await step10_6();
  await step10_7();
  await step10_8();
  await step10_9();
}

main().catch(console.error);
