/**
 * Step 15 — 장기 메모리와 Store
 * 실행: npx tsx docs/reference/langchain/step-15-long-term-memory/practice.ts
 *
 * [15-1] ~ [15-4], [15-7] 블록은 API 키 없이 돌아갑니다 (Store 만 직접 조작).
 * [15-6], [15-9] 는 실제 모델/임베딩 호출이 필요합니다 — 키가 없으면 자동으로 건너뜁니다.
 */
import "dotenv/config";

import { createAgent, tool, type ToolRuntime, FakeToolCallingModel } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore, type Item } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printKV, printJson } from "../project/src/lib/print.js";

const hasAnthropic = process.env["ANTHROPIC_API_KEY"] !== undefined;
const hasOpenAI = process.env["OPENAI_API_KEY"] !== undefined;

/* ===== [15-1] 단기 vs 장기 — 같은 사실을 두 곳에 넣어 보고 스레드를 바꿔 본다 ===== */

printSection("[15-1] 단기(checkpointer) vs 장기(store)");

{
  // checkpointer 는 "스레드 안"의 대화 기록을, store 는 "스레드를 넘는" 사실을 갖습니다.
  // 둘은 서로를 대체하지 않습니다. 아래에서 같은 에이전트에 둘 다 붙입니다.
  const checkpointer = new MemorySaver();
  const store = new InMemoryStore();

  const remember = tool(
    async ({ fact }, runtime: ToolRuntime<any, { userId: string }>) => {
      // 함정: runtime.store 의 TS 타입은 @langchain/core 의 BaseStore<string, unknown> 입니다.
      // 실제로 주입되는 객체는 langgraph 의 BaseStore(AsyncBatchedStore) 이므로 캐스팅합니다.
      const s = runtime.store as unknown as BaseStore;
      await s.put([runtime.context.userId, "memories"], crypto.randomUUID(), { fact });
      return `기억했습니다: ${fact}`;
    },
    {
      name: "remember",
      description: "사용자에 대해 알게 된 사실을 장기 메모리에 저장합니다.",
      schema: z.object({ fact: z.string() }),
    },
  );

  // 실제 모델 대신 FakeToolCallingModel 로 도구 호출을 강제합니다 (API 키 불필요, 결정적).
  // 주의: FakeToolCallingModel 은 호출될 때마다 toolCalls 배열을 순서대로 소비합니다.
  // 스레드마다 의도한 행동을 정확히 재현하려고 에이전트를 따로 만듭니다.
  const mkAgent = (toolCalls: Array<Array<{ name: string; args: object; id: string }>>) =>
    createAgent({
      model: new FakeToolCallingModel({ toolCalls }),
      tools: [remember],
      contextSchema: z.object({ userId: z.string() }),
      checkpointer, // 단기: 스레드별 대화 기록
      store, // 장기: 스레드를 넘는 사실
    });

  // 스레드 A — 사실을 저장합니다.
  await mkAgent([[{ name: "remember", args: { fact: "좋아하는 음식은 피자" }, id: "call_1" }], []]).invoke(
    { messages: [{ role: "user", content: "나는 피자를 좋아해" }] },
    { configurable: { thread_id: "thread-A" }, context: { userId: "user_1" } },
  );

  // 스레드 B — 완전히 새 대화. 저장은 하지 않고 인사만 합니다.
  // checkpointer 관점에서 thread-A 의 메시지는 여기서 보이지 않습니다.
  const threadB = await mkAgent([[]]).invoke(
    { messages: [{ role: "user", content: "안녕" }] },
    { configurable: { thread_id: "thread-B" }, context: { userId: "user_1" } },
  );

  printKV({
    "thread-B 의 메시지 수": threadB.messages.length, // 새 스레드라 A 의 기록이 없음
    "store 에 남은 기억": (await store.search(["user_1", "memories"])).map((i) => i.value["fact"]).join(", "),
  });
  // → 대화 기록은 스레드마다 리셋되지만, store 의 사실은 스레드를 넘어 그대로 남습니다.
}

/* ===== [15-2] BaseStore 인터페이스 — put / get / search / delete / listNamespaces ===== */

printSection("[15-2] BaseStore 기본 연산");

{
  const store = new InMemoryStore();
  const ns = ["user_1", "memories"]; // namespace = 문자열 배열(계층 경로)

  // put(namespace, key, value) — key 가 같으면 덮어씁니다(upsert).
  await store.put(ns, "m1", { text: "피자를 좋아함", kind: "semantic" });
  await store.put(ns, "m2", { text: "채식 지향", kind: "semantic" });
  await store.put(ns, "m3", { text: "2026-07-01 환불을 요청했음", kind: "episodic" });

  // get → Item | null
  const one = await store.get(ns, "m1");
  printJson("get(ns, 'm1')", one);
  // Item 의 필드는 결정적입니다: value, key, namespace, createdAt, updatedAt

  // search(namespacePrefix) — 쿼리 없이 부르면 그냥 목록입니다.
  const all = await store.search(ns);
  printKV({ "search(ns) 개수": all.length, keys: all.map((i) => i.key).join(", ") });

  // filter — value 안의 필드를 정확히 매칭. $eq/$ne/$gt/$gte/$lt/$lte 연산자도 됩니다.
  const semantic = await store.search(ns, { filter: { kind: "semantic" } });
  printKV({ "filter kind=semantic": semantic.map((i) => i.key).join(", ") });

  // delete
  await store.delete(ns, "m2");
  printKV({ "delete 후": (await store.search(ns)).map((i) => i.key).join(", ") });

  // listNamespaces — 어떤 네임스페이스들이 있는지 훑어보기
  await store.put(["user_2", "memories"], "x1", { text: "다른 사용자" });
  printJson("listNamespaces({ maxDepth: 2 })", await store.listNamespaces({ maxDepth: 2 }));
}

/* ===== [15-3] InMemoryStore — 네임스페이스 규칙과 검증 ===== */

printSection("[15-3] InMemoryStore 와 네임스페이스 규칙");

{
  const store = new InMemoryStore();

  // 네임스페이스는 "빈 배열 금지", "루트 라벨 langgraph 금지", "라벨에 점(.) 금지" 입니다.
  // 아래 셋은 모두 조용히 넘어가지 않고 즉시 에러를 던집니다 — 확인해 봅니다.
  const tries: Array<[string, string[]]> = [
    ["빈 네임스페이스", []],
    ["예약어 langgraph", ["langgraph", "x"]],
    ["점이 든 라벨", ["a.b"]],
  ];

  for (const [label, ns] of tries) {
    try {
      await store.put(ns, "k", { t: 1 });
      printKV({ [label]: "통과(예상 밖)" });
    } catch (e) {
      printKV({ [label]: `에러: ${(e as Error).message}` });
    }
  }

  // 정상 네임스페이스
  await store.put(["user_1", "memories"], "ok", { t: 1 });
  printKV({ "정상 네임스페이스": "통과" });

  // InMemoryStore 는 값을 "복사 없이" 그대로 들고 있습니다 — get 은 내부 참조를 돌려줍니다.
  const ns = ["user_1", "memories"];
  await store.put(ns, "ref", { content: "원본" });
  const item = await store.get(ns, "ref");
  (item!.value as Record<string, unknown>)["content"] = "몰래 바꿈"; // put 을 부르지 않았습니다

  printKV({
    "value 를 직접 수정한 뒤 재조회": JSON.stringify((await store.get(ns, "ref"))?.value),
    "get 이 같은 객체를 주는가": String(item === (await store.get(ns, "ref"))),
  });
  // → put 없이 store 가 오염됩니다. 에러도 경고도 없습니다.
  //   PostgresStore 는 값을 직렬화하므로 이 동작이 다릅니다(오염되지 않습니다).
  //   즉 InMemoryStore 에서 "되던" 코드가 DB 로 바꾸면 조용히 깨집니다.
}

/* ===== [15-4] 에이전트에 store 붙이기 — 도구에서 runtime.store 접근 ===== */

printSection("[15-4] createAgent({ store }) 와 runtime.store");

{
  const store = new InMemoryStore();

  const probe = tool(
    async (_input, runtime: ToolRuntime<any, { userId: string }>) => {
      // runtime.store 는 우리가 넘긴 InMemoryStore "그 객체"가 아니라
      // AsyncBatchedStore 래퍼입니다 → instanceof 검사가 실패합니다.
      const isInMemory = (runtime.store as unknown) instanceof InMemoryStore;
      const s = runtime.store as unknown as BaseStore;
      await s.put([runtime.context.userId, "memories"], "from_tool", { text: "도구가 저장함" });
      return `constructor=${runtime.store?.constructor?.name} instanceof InMemoryStore=${isInMemory}`;
    },
    { name: "probe", description: "store 접근을 확인합니다.", schema: z.object({}) },
  );

  const model = new FakeToolCallingModel({
    toolCalls: [[{ name: "probe", args: {}, id: "call_1" }], []],
  });

  const agent = createAgent({
    model,
    tools: [probe],
    contextSchema: z.object({ userId: z.string() }),
    store, // ← 여기서 준 store 가 도구의 runtime.store 로 흘러 들어갑니다
  });

  const res = await agent.invoke(
    { messages: [{ role: "user", content: "확인해줘" }] },
    { context: { userId: "user_1" } },
  );

  // 도구가 남긴 ToolMessage 안에 진단 문자열이 들어 있습니다.
  const toolMsg = res.messages.find((m) => m.getType() === "tool");
  printKV({
    "도구가 본 store": String(toolMsg?.content),
    "바깥 store 에서 읽기": JSON.stringify((await store.get(["user_1", "memories"], "from_tool"))?.value),
  });
  // → 래퍼를 거쳤어도 쓰기는 바깥의 진짜 store 에 도달합니다.
}

/* ===== [15-5] 메모리 쓰기 — 모델이 직접 저장 도구를 호출하는 방식 ===== */

printSection("[15-5] 메모리 쓰기 — 저장 도구");

{
  const store = new InMemoryStore();

  // 저장 도구는 "무엇을 저장할지"를 모델이 정하게 합니다.
  // description 이 곧 저장 정책입니다 — 부실하면 모델이 아무거나 저장하거나 아예 안 부릅니다.
  const saveMemory = tool(
    async ({ content, kind }, runtime: ToolRuntime<any, { userId: string }>) => {
      const s = runtime.store as unknown as BaseStore;
      const ns = [runtime.context.userId, "memories"];

      // 갱신 전략: 같은 내용이 이미 있으면 새로 쌓지 않고 덮어씁니다.
      const existing = await s.search(ns, { filter: { kind } });
      const dup = existing.find((i) => i.value["content"] === content);
      const key = dup?.key ?? crypto.randomUUID();

      await s.put(ns, key, { content, kind, updatedAt: new Date().toISOString() }, ["content"]);
      return dup ? `이미 알고 있어 갱신했습니다: ${content}` : `저장했습니다: ${content}`;
    },
    {
      name: "save_memory",
      description: [
        "사용자에 대해 새로 알게 된, 다음 대화에서도 쓸모 있는 사실을 저장합니다.",
        "kind: semantic=변하지 않는 사실/선호, episodic=특정 시점의 사건, procedural=일하는 방식.",
        "일회성 잡담이나 이미 저장된 내용은 저장하지 마세요.",
      ].join(" "),
      schema: z.object({
        content: z.string().describe("한 문장으로 요약한 사실"),
        kind: z.enum(["semantic", "episodic", "procedural"]),
      }),
    },
  );

  const model = new FakeToolCallingModel({
    toolCalls: [
      [{ name: "save_memory", args: { content: "매운 음식을 못 먹는다", kind: "semantic" }, id: "c1" }],
      // 같은 사실을 또 저장하려 시도 → 갱신으로 흡수되어 행이 늘지 않습니다.
      [{ name: "save_memory", args: { content: "매운 음식을 못 먹는다", kind: "semantic" }, id: "c2" }],
      [],
    ],
  });

  const agent = createAgent({
    model,
    tools: [saveMemory],
    contextSchema: z.object({ userId: z.string() }),
    store,
  });

  await agent.invoke(
    { messages: [{ role: "user", content: "나 매운 거 못 먹어" }] },
    { context: { userId: "user_1" } },
  );

  printKV({
    "저장된 기억 수": (await store.search(["user_1", "memories"])).length, // 2번 호출했지만 1
    내용: (await store.search(["user_1", "memories"])).map((i) => i.value["content"]).join(", "),
  });
}

/* ===== [15-6] 메모리 읽기 — 시맨틱 검색 (임베딩 인덱스) ===== */

printSection("[15-6] 시맨틱 검색");

{
  // 먼저 함정부터: 인덱스 설정 없이 query 를 주면 "에러가 아니라" 그냥 정렬 안 된 목록이 옵니다.
  const noIndex = new InMemoryStore();
  await noIndex.put(["u", "m"], "a", { text: "피자를 좋아함" });
  await noIndex.put(["u", "m"], "b", { text: "주말엔 등산을 함" });
  const bad = await noIndex.search(["u", "m"], { query: "이 사용자는 뭘 먹나요?" });
  printKV({
    "인덱스 없이 query": bad.map((i) => `${i.key}(score=${i.score})`).join(", "), // score 가 전부 undefined
    "store.indexConfig": String(noIndex.indexConfig), // undefined
  });
  // → 시맨틱 검색이 조용히 "그냥 목록"으로 퇴화합니다. score 가 undefined 인지 꼭 확인하세요.

  if (hasOpenAI) {
    const { OpenAIEmbeddings } = await import("@langchain/openai");

    // 인덱스를 설정해야 진짜 시맨틱 검색이 됩니다.
    const store = new InMemoryStore({
      index: {
        embeddings: new OpenAIEmbeddings({ model: "text-embedding-3-small" }),
        dims: 1536, // text-embedding-3-small 의 기본 차원
        fields: ["text"], // 이 필드만 임베딩. ["$"] 면 문서 전체.
      },
    });

    const ns = ["user_1", "memories"];
    await store.put(ns, "m1", { text: "좋아하는 음식은 피자" });
    await store.put(ns, "m2", { text: "주말마다 등산을 감" });
    await store.put(ns, "m3", { text: "다크 테마를 씀" }, false); // 인덱싱 제외

    const hits = await store.search(ns, { query: "이 사용자는 뭘 먹나요?", limit: 3 });
    printJson(
      "시맨틱 검색 결과",
      hits.map((i) => ({ key: i.key, text: i.value["text"], score: i.score })),
    );
    // → 음식 관련 m1 이 가장 높은 score 로 올라옵니다.
    //   index:false 로 넣은 m3 는 score 가 undefined 인 채로 뒤에 붙습니다(제외되지 않습니다).
  } else {
    printKV({ 건너뜀: "OPENAI_API_KEY 가 없어 임베딩 검색을 건너뜁니다" });
  }
}

/* ===== [15-7] 메모리 유형 — 의미 / 일화 / 절차 ===== */

printSection("[15-7] 메모리 유형별 네임스페이스 분리");

{
  const store = new InMemoryStore();
  const userId = "user_1";

  // 유형을 네임스페이스로 나누면 "읽을 때 필요한 것만" 가져올 수 있습니다.
  await store.put([userId, "semantic"], "s1", { content: "이름은 김민수", confidence: 0.95 });
  await store.put([userId, "semantic"], "s2", { content: "회사는 무신사", confidence: 0.9 });
  await store.put([userId, "episodic"], "e1", {
    content: "2026-07-10 배송 지연으로 문의함",
    at: "2026-07-10",
  });
  await store.put([userId, "procedural"], "p1", {
    content: "보고서는 항상 표로 먼저 요약해 줄 것",
  });

  for (const kind of ["semantic", "episodic", "procedural"]) {
    const items = await store.search([userId, kind]);
    printKV({ [kind]: items.map((i) => i.value["content"]).join(" | ") });
  }

  // 절차기억은 보통 "시스템 프롬프트에 항상 주입", 의미기억은 "항상 주입",
  // 일화기억은 "관련될 때만 검색해서 주입" 하는 게 실무 기본값입니다.
  const always = [
    ...(await store.search([userId, "semantic"])),
    ...(await store.search([userId, "procedural"])),
  ];
  printKV({ "프롬프트에 항상 넣을 것": always.length + "건" });
}

/* ===== [15-8] 영속 Store — PostgresStore (연결 문자열이 있을 때만) ===== */

printSection("[15-8] 영속 Store");

{
  // InMemoryStore 는 프로세스가 죽으면 전부 사라집니다. 프로덕션에서는 DB 백엔드를 씁니다.
  //
  //   npm install @langchain/langgraph-checkpoint-postgres
  //
  //   import { PostgresStore } from "@langchain/langgraph-checkpoint-postgres/store";
  //   const store = PostgresStore.fromConnString(process.env.DB_URI!);
  //   await store.setup();   // ← 최초 1회 테이블/인덱스 생성. 빠뜨리면 런타임에 실패합니다.
  //
  // 이 실습 프로젝트에는 postgres 패키지가 없으므로 코드는 주석으로만 둡니다.
  // 인터페이스가 BaseStore 로 같기 때문에, 아래 [15-9] 의 에이전트 코드는
  // store 를 만드는 한 줄만 바꾸면 그대로 돌아갑니다.
  printKV({
    "InMemoryStore": "프로세스와 함께 소멸 — 개발/테스트용",
    "PostgresStore": "@langchain/langgraph-checkpoint-postgres/store — setup() 필수",
    "인터페이스": "둘 다 BaseStore — 교체 시 코드 변경 없음",
  });
}

/* ===== [15-9] 실전: 사용자 선호를 기억하는 개인 비서 ===== */

printSection("[15-9] 개인 비서 에이전트");

{
  const ContextSchema = z.object({ userId: z.string() });
  type Ctx = z.infer<typeof ContextSchema>;

  const store = new InMemoryStore();
  const checkpointer = new MemorySaver();

  const saveMemory = tool(
    async ({ content }, runtime: ToolRuntime<any, Ctx>) => {
      const s = runtime.store as unknown as BaseStore;
      await s.put(
        [runtime.context.userId, "memories"], // ← userId 를 반드시 네임스페이스에
        crypto.randomUUID(),
        { content, updatedAt: new Date().toISOString() },
        ["content"],
      );
      return `기억했습니다: ${content}`;
    },
    {
      name: "save_memory",
      description:
        "사용자의 선호·사실 중 다음 대화에서도 쓸모 있는 것을 저장합니다. 확실한 것만 저장하세요.",
      schema: z.object({ content: z.string().describe("한 문장으로 요약한 사실") }),
    },
  );

  const searchMemory = tool(
    async ({ query }, runtime: ToolRuntime<any, Ctx>) => {
      const s = runtime.store as unknown as BaseStore;
      const items: Item[] = await s.search([runtime.context.userId, "memories"], { query, limit: 5 });
      if (items.length === 0) return "저장된 기억이 없습니다.";
      return items.map((i) => `- ${i.value["content"]}`).join("\n");
    },
    {
      name: "search_memory",
      description: "사용자에 대해 이전에 저장해 둔 사실을 검색합니다. 답하기 전에 먼저 호출하세요.",
      schema: z.object({ query: z.string().describe("찾고 싶은 내용") }),
    },
  );

  if (hasAnthropic) {
    const agent = createAgent({
      model: "anthropic:claude-sonnet-4-6",
      // OpenAI 대안: model: "openai:gpt-5.5"
      tools: [saveMemory, searchMemory],
      systemPrompt: [
        "너는 개인 비서다.",
        "사용자가 자기 선호나 사실을 말하면 save_memory 로 저장해라.",
        "사용자에 대한 질문에 답하기 전에는 search_memory 로 먼저 확인해라.",
        "기억에 없는 것을 지어내지 마라.",
      ].join(" "),
      contextSchema: ContextSchema,
      checkpointer,
      store,
    });

    const cfg = (thread: string) => ({
      configurable: { thread_id: thread },
      context: { userId: "user_1" },
    });

    // 1일차 — 선호를 알려줍니다.
    const day1 = await agent.invoke(
      { messages: [{ role: "user", content: "나는 매운 음식을 못 먹고, 채식을 지향해." }] },
      cfg("day-1"),
    );
    printKV({ "1일차 응답": String(day1.messages.at(-1)?.content).slice(0, 120) });

    // 2일차 — 완전히 새 스레드. 대화 기록은 없지만 store 는 살아 있습니다.
    const day2 = await agent.invoke(
      { messages: [{ role: "user", content: "점심 메뉴 하나만 추천해줘." }] },
      cfg("day-2"),
    );
    printKV({ "2일차 응답": String(day2.messages.at(-1)?.content).slice(0, 200) });

    printJson(
      "store 에 쌓인 기억",
      (await store.search(["user_1", "memories"])).map((i) => i.value["content"]),
    );
  } else {
    printKV({ 건너뜀: "ANTHROPIC_API_KEY 가 없어 개인 비서 실행을 건너뜁니다" });
  }
}

printSection("끝");
