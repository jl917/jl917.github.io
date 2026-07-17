/**
 * Step 10 — 장기 메모리와 스킬 · 정답과 해설
 * 실행: npx tsx docs/reference/deepagent/step-10-memory-skills/solution.ts
 *
 * exercise.ts 를 먼저 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";
import {
  createDeepAgent,
  createMemoryMiddleware,
  CompositeBackend,
  StateBackend,
  StoreBackend,
} from "deepagents";
import { InMemoryStore, MemorySaver } from "@langchain/langgraph";
import { tool } from "langchain";
import * as z from "zod";

const MODEL = "anthropic:claude-sonnet-4-6";

/** state 의 files 에 넣을 FileData 객체를 만드는 헬퍼. */
function textFile(content: string) {
  const now = new Date().toISOString();
  return { content, mimeType: "text/markdown", created_at: now, modified_at: now };
}

/* ===== [정답 1] 영속되는 메모리 구성하기 =====
 * 핵심은 "memory 옵션이 영속을 만들어주지 않는다" 는 것입니다.
 * memory: ["/memories/AGENTS.md"] 는 그냥 "이 경로의 파일을 시스템 프롬프트에 넣어라" 일 뿐입니다.
 * 그 경로가 실제로 어디에 저장되는지는 backend 가 정합니다.
 *
 * StateBackend 만 쓰면 파일이 그래프 state 에 들어가고, state 는 thread_id 에 묶입니다.
 * 스레드가 바뀌면 state 도 새것이라 파일이 없습니다 — memory 옵션을 줬는데도 아무것도 안 떠오릅니다.
 * 에러는 안 납니다. 그냥 조용히 빈 메모리로 동작합니다.
 *
 * 그래서 CompositeBackend 로 /memories/ 만 StoreBackend 로 라우팅해야 합니다.
 * Store 는 thread 바깥에 있으므로 스레드가 바뀌어도 남습니다.
 */
function makeMemoryAgent(store: InMemoryStore, userId = "demo-user") {
  const backend = new CompositeBackend(
    new StateBackend(), // 기본: 스레드 안에서만 사는 작업 공간
    {
      "/memories/": new StoreBackend({ namespace: () => ["users", userId, "memories"] }),
    },
  );

  return createDeepAgent({
    model: MODEL,
    backend,
    store, // StoreBackend 를 쓰면 store 를 반드시 넘겨야 합니다
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"],
    systemPrompt: `너는 사용자를 기억하는 도우미다.

## 메모리 규칙
- 사용자가 "기억해", "앞으로" 라고 말하면 /memories/AGENTS.md 에 저장해라.
- 저장 전에 read_file 로 현재 내용을 먼저 읽어라.
- 기존 내용을 덮어쓰지 말고 edit_file 로 항목을 추가해라.`,
  });
}

async function sol1() {
  console.log("\n===== [정답 1] =====");
  const store = new InMemoryStore();
  const agent = await makeMemoryAgent(store);
  const r = await agent.invoke(
    { messages: [{ role: "user", content: "안녕" }] },
    { configurable: { thread_id: "sol-1" } },
  );
  console.log("응답:", r.messages.at(-1)?.text);
}

/* ===== [정답 2] 스레드를 넘는 기억 확인 =====
 * 이 문제의 포인트는 "대화 기록" 과 "메모리" 가 완전히 다른 것이라는 점입니다.
 *
 * - 대화 기록(checkpointer): thread_id 에 묶임. 스레드 B 는 A 의 대화를 전혀 모릅니다.
 * - 메모리(store): thread 바깥. 스레드 B 도 읽습니다.
 *
 * 그래서 스레드 B 는 "우리가 그런 얘기를 했다" 는 건 모르지만
 * "이 사용자는 다크 모드를 쓴다" 는 사실은 압니다. 이 구분이 장기 메모리의 본질입니다.
 *
 * 함정: 모델이 저장을 안 하면 아무 일도 안 일어납니다. memory 옵션은 "읽기" 만 자동이고
 * "쓰기" 는 모델이 write_file/edit_file 을 불러야 합니다. systemPrompt 에 저장 규칙이
 * 없으면 모델은 그냥 "알겠습니다" 하고 넘어갑니다 — 그리고 다음 스레드에서 아무것도 모릅니다.
 */
async function sol2() {
  console.log("\n===== [정답 2] =====");
  const store = new InMemoryStore();
  const agent = await makeMemoryAgent(store);

  // 스레드 A: 알려주고 저장시킨다
  await agent.invoke(
    { messages: [{ role: "user", content: "나는 다크 모드를 쓴다. 기억해줘." }] },
    { configurable: { thread_id: "sol-2-A" } },
  );

  // 스레드 B: 완전히 새 대화. 대화 기록은 공유되지 않는다.
  const rB = await agent.invoke(
    { messages: [{ role: "user", content: "내 테마 취향이 뭐였지?" }] },
    { configurable: { thread_id: "sol-2-B" } },
  );
  console.log("스레드 B 응답:", rB.messages.at(-1)?.text);

  // store 에 실제로 저장된 것 확인
  const items = await store.search(["users", "demo-user", "memories"]);
  console.log("저장된 키:", items.map((i) => i.key));
}

/* ===== [정답 3] 사용자별 네임스페이스 격리 =====
 * namespace 팩토리가 반환하는 배열이 곧 저장 위치의 계층 경로입니다.
 * userId 를 그 경로에 넣으면 alice 와 bob 의 파일이 물리적으로 다른 곳에 저장됩니다.
 *
 * ⚠️ 이게 이 스텝에서 가장 위험한 지점입니다.
 * namespace: () => ["memories"] 처럼 사용자 구분 없이 고정 문자열을 쓰면,
 * 모든 사용자가 같은 /memories/AGENTS.md 를 공유합니다.
 * alice 가 "내 계좌번호는 ..." 이라고 저장하면 bob 의 에이전트가 그걸 시스템 프롬프트로 읽습니다.
 * 에러도 경고도 없습니다. 데모에서는 사용자가 하나뿐이라 절대 발견되지 않고,
 * 프로덕션에 올라가서 두 번째 사용자가 생기는 순간 정보가 샙니다.
 *
 * 그리고 userId 는 반드시 "서버가 인증으로 확인한 값" 이어야 합니다.
 * 클라이언트가 보낸 값을 그대로 쓰면 남의 네임스페이스를 지목할 수 있습니다.
 */
async function sol3() {
  console.log("\n===== [정답 3] =====");
  const store = new InMemoryStore(); // store 는 공유. 네임스페이스만 분리.

  const alice = await makeMemoryAgent(store, "alice");
  const bob = await makeMemoryAgent(store, "bob");

  await alice.invoke(
    { messages: [{ role: "user", content: "내 사번은 A-1234야. 기억해줘." }] },
    { configurable: { thread_id: "sol-3-alice" } },
  );

  const rBob = await bob.invoke(
    { messages: [{ role: "user", content: "내 사번이 뭐였지?" }] },
    { configurable: { thread_id: "sol-3-bob" } },
  );
  // bob 은 alice 의 메모리를 볼 수 없으므로 모른다고 답해야 합니다
  console.log("bob 응답:", rBob.messages.at(-1)?.text);

  console.log("alice 네임스페이스:", (await store.search(["users", "alice", "memories"])).length);
  console.log("bob 네임스페이스:", (await store.search(["users", "bob", "memories"])).length);
}

/* ===== [정답 4] createMemoryMiddleware 직접 쓰기 =====
 * memory: [...] 옵션은 내부적으로 이 미들웨어를 붙여주는 축약형입니다.
 * 직접 붙이면 addCacheControl 을 켤 수 있습니다.
 *
 * addCacheControl 이 중요한 이유: 메모리는 "매 턴 똑같이 붙는 큰 덩어리" 입니다.
 * 캐싱을 안 걸면 매 턴 전체 메모리를 다시 토큰으로 계산해서 비용을 냅니다.
 * 메모리가 커질수록 이 비용이 선형으로 커집니다.
 *
 * 주의: 미들웨어에 넘기는 backend 는 에이전트의 backend 와 같은 인스턴스여야 합니다.
 * 다른 인스턴스를 주면 미들웨어는 엉뚱한 곳에서 파일을 찾고, 조용히 빈 메모리를 주입합니다.
 */
async function sol4() {
  console.log("\n===== [정답 4] =====");
  const store = new InMemoryStore();

  // backend 를 변수로 빼서 에이전트와 미들웨어가 같은 인스턴스를 쓰게 합니다
  const backend = new CompositeBackend(new StateBackend(), {
    "/memories/": new StoreBackend({ namespace: () => ["users", "demo-user", "memories"] }),
  });

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    middleware: [
      createMemoryMiddleware({
        backend, // ← 에이전트와 같은 인스턴스
        sources: ["/memories/AGENTS.md", "/memories/STYLE.md"], // 순서대로 주입
        addCacheControl: true, // Anthropic 프롬프트 캐싱
      }),
    ],
  });

  console.log("createMemoryMiddleware 직접 구성 완료 (sources 2개, 캐싱 on)");
  return agent;
}

/* ===== [정답 5] SKILL.md 작성하기 =====
 * frontmatter 의 description 이 이 파일에서 가장 중요한 한 줄입니다.
 * 이유: 모델은 평소에 SKILL.md 본문을 안 봅니다. name 과 description 만 봅니다
 * (progressive disclosure). 즉 description 이 "이 스킬을 켤지 말지" 를 결정하는
 * 유일한 정보입니다.
 *
 * 그래서 description 에는 "무엇을 하는가" 만 쓰면 안 되고
 * "언제 켜야 하는가" 를 활성화 키워드와 함께 써야 합니다.
 * "커밋 메시지를 잘 쓴다" (X) → 모델이 언제 켤지 모릅니다.
 * "커밋 메시지를 작성할 때 사용한다. commit, 커밋, PR 제목 요청에 활성화된다" (O)
 *
 * name 은 소문자·하이픈이고 부모 디렉터리 이름과 같아야 합니다.
 * /skills/commit-message/SKILL.md 면 name: commit-message 여야 합니다.
 */
const COMMIT_SKILL = `---
name: commit-message
description: 커밋 메시지를 작성할 때 사용한다. commit, 커밋, 커밋 메시지 작성, PR 제목 요청에 활성화된다.
---

# 커밋 메시지 작성 절차

1. git diff 로 실제 변경 내용을 확인한다. 추측하지 마라.
2. 변경의 종류를 정한다: feat / fix / docs / refactor / test / chore
3. 아래 형식으로 작성한다.

   <type>: <한 줄 요약 50자 이내, 한국어, 마침표 없음>

   <본문: 왜 이 변경이 필요했는지. 무엇을 바꿨는지가 아니라 왜인지를 쓴다>

4. 한 커밋에 여러 종류의 변경이 섞여 있으면 커밋을 나누라고 제안한다.
5. 비밀값이나 개인정보가 diff 에 있으면 커밋을 만들지 말고 경고한다.
`;

async function sol5() {
  console.log("\n===== [정답 5] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    skills: ["/skills/"],
  });

  const result = await agent.invoke(
    {
      messages: [{ role: "user", content: "커밋 메시지를 써줘. 로그인 버그를 고쳤어." }],
      files: {
        // 경로의 디렉터리 이름(commit-message)과 frontmatter 의 name 이 일치해야 합니다
        "/skills/commit-message/SKILL.md": textFile(COMMIT_SKILL),
      },
    },
    { configurable: { thread_id: "sol-5" } },
  );

  console.log("응답:", result.messages.at(-1)?.text);
}

/* ===== [정답 6] 스킬 vs 도구 판별 =====
 *
 * (a) 현재 시각 조회 → 도구
 *     이유: 모델이 "알" 수 없는 외부 사실이고, 결정적인 코드 실행이 필요합니다.
 *     절차서로는 시각을 알 수 없습니다. 스킬로 "시각을 알아내라" 고 써봐야 모델은 못 합니다.
 *
 * (b) 팀의 PR 리뷰 절차 5단계 → 스킬
 *     이유: 새로운 능력이 아니라 "이미 할 수 있는 일(읽기·분석)을 우리 방식대로 하는 법" 입니다.
 *     코드로 강제할 필요도 없고, 강제할 수도 없습니다(판단이 필요하니까).
 *     도구로 만들면 각 단계를 함수로 쪼개야 하는데 그건 절차가 아니라 파이프라인입니다.
 *
 * (c) 50개 파일 각각 요약 → 서브에이전트
 *     이유: 컨텍스트 격리가 목적입니다. 50개 파일 본문을 부모 컨텍스트에 다 넣으면
 *     컨텍스트가 터집니다. 서브에이전트에게 파일 하나씩 주고 "요약만" 받아오면
 *     부모는 요약 50개만 갖습니다. 병렬 실행 이득도 있습니다.
 *
 * 아래는 (a) 를 도구로 구현한 것입니다.
 */
const getCurrentTime = tool(
  async ({ timezone }) => new Date().toLocaleString("ko-KR", { timeZone: timezone }),
  {
    name: "get_current_time",
    description: "지정한 시간대의 현재 시각을 반환한다.",
    schema: z.object({ timezone: z.string().describe("IANA 시간대. 예: Asia/Seoul") }),
  },
);

async function sol6() {
  console.log("\n===== [정답 6] =====");
  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    tools: [getCurrentTime],
  });

  const r = await agent.invoke(
    { messages: [{ role: "user", content: "서울 지금 몇 시야?" }] },
    { configurable: { thread_id: "sol-6" } },
  );
  console.log("응답:", r.messages.at(-1)?.text);
}

/* ===== [정답 7] 스킬을 읽기 전용으로 잠그기 =====
 * 스킬을 왜 잠가야 하나? 스킬은 "모델이 따라야 할 규칙" 인데,
 * 모델이 그 규칙 파일을 수정할 수 있다면 규칙이 아닙니다.
 * 모델이 절차가 귀찮아서 SKILL.md 를 고쳐버리는 일은 실제로 일어납니다.
 * 그리고 다음 세션부터 그 잘못된 스킬이 영구히 적용됩니다.
 *
 * Step 09 의 규칙 그대로입니다: first-match-wins, 기본은 allow, 마지막 빗장 필수.
 * write deny 를 read allow 보다 먼저 둡니다.
 *
 * 참고: 공식 문서 일부에 permissions 의 mode 로 "interrupt" 가 나오지만,
 * deepagents 1.11.0 의 PermissionMode 는 "allow" | "deny" 뿐입니다.
 * 스킬 수정에 사람 승인을 걸고 싶으면 interruptOn: { write_file: true } 를 쓰세요.
 */
async function sol7() {
  console.log("\n===== [정답 7] =====");
  const store = new InMemoryStore();

  const backend = new CompositeBackend(new StateBackend(), {
    "/memories/": new StoreBackend({ namespace: () => ["users", "demo-user", "memories"] }),
    "/skills/": new StoreBackend({ namespace: () => ["shared", "skills"] }),
  });

  const agent = await createDeepAgent({
    model: MODEL,
    backend,
    store,
    checkpointer: new MemorySaver(),
    memory: ["/memories/AGENTS.md"],
    skills: ["/skills/"],
    permissions: [
      // 1) 스킬 쓰기 금지 — read allow 보다 먼저 와야 의도가 명확합니다
      { operations: ["write"], paths: ["/skills/**"], mode: "deny" },
      { operations: ["read"], paths: ["/skills/**"], mode: "allow" },
      // 2) 메모리와 작업공간은 읽기·쓰기 허용
      { operations: ["read", "write"], paths: ["/memories/**"], mode: "allow" },
      { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
      // 3) 마지막 빗장 — 없으면 나머지가 전부 기본 allow 로 열립니다
      { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
    ],
  });

  console.log("스킬 읽기 전용 / 메모리·작업공간 읽기쓰기 / 나머지 금지 구성 완료");
  return agent;
}

/* ===== [정답 8] grep RAG =====
 * 벡터 DB 도, 임베딩도, 인덱싱 파이프라인도 없습니다. 내장 grep/glob 이 전부입니다.
 *
 * 이게 되는 이유: Deep Agent 는 여러 턴을 돌 수 있습니다.
 * 한 번의 유사도 검색으로 정답을 맞혀야 하는 단발성 RAG 와 달리,
 * 에이전트는 grep 해보고 → 아니면 다른 키워드로 다시 grep 하고 → 파일을 읽고 → 또 뒤집니다.
 * 사람이 코드베이스에서 뭔가 찾는 방식과 같습니다.
 *
 * 한계: grep 은 정확한 문자열 매칭이라 "인증" 을 찾을 때 "로그인" 이라고 적힌 문서를 못 찾습니다.
 * 어휘가 갈리는 코퍼스나 문서가 수천 개면 벡터 검색이 낫습니다.
 * 반대로 문서가 수십 개고 용어가 통제돼 있으면 grep 이 더 정확하고 훨씬 쌉니다.
 *
 * 함정: files 값은 문자열이 아니라 FileData 객체입니다.
 * "/docs/a.md": "내용" 이라고 쓰면 타입 에러가 납니다.
 */
async function sol8() {
  console.log("\n===== [정답 8] =====");

  const agent = await createDeepAgent({
    model: MODEL,
    backend: new StateBackend(),
    checkpointer: new MemorySaver(),
    systemPrompt: `질문에 답하려면 /docs 안을 직접 뒤져라.

1. glob 으로 문서 목록을 본다.
2. grep 으로 키워드를 찾는다. 첫 검색이 실패하면 다른 표현으로 다시 시도해라.
3. 찾은 파일을 read_file 로 읽고 근거를 인용해 답한다.
4. 문서에 없으면 "문서에 없다" 고 답해라. 지어내지 마라.`,
  });

  const result = await agent.invoke(
    {
      messages: [{ role: "user", content: "배포 롤백은 어떻게 해?" }],
      files: {
        "/docs/auth.md": textFile("# 인증\n\nAPI 키를 ANTHROPIC_API_KEY 환경변수에 넣으세요."),
        "/docs/deploy.md": textFile(
          "# 배포\n\ndocker build 로 이미지를 만듭니다.\n\n## 롤백\n\n`kubectl rollout undo deployment/app` 으로 직전 버전으로 되돌립니다.",
        ),
        "/docs/style.md": textFile("# 코드 스타일\n\n들여쓰기는 스페이스 2칸."),
      },
    },
    { configurable: { thread_id: "sol-8" } },
  );

  // /docs/deploy.md 에만 있는 kubectl rollout undo 를 찾아내야 정답입니다
  console.log("응답:", result.messages.at(-1)?.text);
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

main().catch(console.error);
