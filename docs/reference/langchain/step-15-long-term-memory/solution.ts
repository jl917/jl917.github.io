/**
 * Step 15 — 장기 메모리와 Store · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-15-long-term-memory/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";

import { createAgent, tool, type ToolRuntime, FakeToolCallingModel } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printKV, printJson } from "../project/src/lib/print.js";

const hasAnthropic = process.env["ANTHROPIC_API_KEY"] !== undefined;

/* ===== [정답 1] BaseStore 기본 연산 ===== */

printSection("[정답 1] BaseStore 기본 연산");

const store1 = new InMemoryStore();
{
  const ns = ["user_1", "memories"];

  await store1.put(ns, "m1", { content: "커피는 디카페인만", kind: "semantic" });
  await store1.put(ns, "m2", { content: "2026-07-01 환불 문의", kind: "episodic" });
  await store1.put(ns, "m3", { content: "회신은 항상 존댓말로", kind: "procedural" });

  const all = await store1.search(ns);
  printKV({ "전체 key": all.map((i) => i.key).join(", ") });

  // filter 는 value 안의 필드를 매칭합니다. namespace/key 가 아니라 "값"이 대상입니다.
  const semantic = await store1.search(ns, { filter: { kind: "semantic" } });
  printKV({ "kind=semantic": semantic.map((i) => i.value["content"]).join(", ") });

  // 해설: put/get/search/delete 는 모두 async 입니다. await 를 빠뜨리면
  // 아직 쓰이지 않은 상태에서 search 가 돌아 "방금 저장한 게 없다"는 현상이 납니다.
  // 조용히 틀리는 대표적인 경로라 저장 직후 읽는 코드는 특히 조심하세요.
}

/* ===== [정답 2] upsert ===== */

printSection("[정답 2] upsert");

{
  const ns = ["user_1", "memories"];
  const beforeItem = await store1.get(ns, "m1");

  // 주의: get 이 돌려준 Item 은 store 내부 객체의 "참조"입니다(InMemoryStore 한정).
  // 나중 비교를 위해 값을 붙들어 두려면 반드시 복사해야 합니다.
  const beforeSnapshot = structuredClone(beforeItem!.value);

  // 같은 key 로 다시 put → 새 행이 생기지 않고 덮어써집니다(upsert).
  await new Promise((r) => setTimeout(r, 5)); // updatedAt 차이를 눈으로 보려고 잠깐 대기
  await store1.put(ns, "m1", { content: "커피는 아예 안 마심", kind: "semantic" });

  const after = await store1.get(ns, "m1");
  printKV({
    "search 개수": (await store1.search(ns)).length, // 여전히 3 — 늘지 않습니다
    "이전 값(복사해 둔 것)": JSON.stringify(beforeSnapshot),
    "이전 값(참조를 그대로 들고 있던 것)": JSON.stringify(beforeItem?.value), // 새 값으로 바뀌어 있습니다
    "이후 값": JSON.stringify(after?.value),
    createdAt: String(after?.createdAt.toISOString()),
    updatedAt: String(after?.updatedAt.toISOString()),
  });

  // 답: 덮어써집니다. (namespace, key) 쌍이 곧 기본키입니다.
  //
  // 해설 1 — upsert:
  // 이 성질이 메모리 갱신 전략의 토대입니다. "사실이 바뀌었다"면 새 key 로
  // 쌓지 말고 같은 key 를 덮어써야 합니다. 그렇지 않으면 "커피는 디카페인만"과
  // "커피는 아예 안 마심"이 동시에 검색되어 모델이 모순된 사실을 보게 됩니다.
  // key 를 매번 crypto.randomUUID() 로 만드는 코드가 흔한데, 그건 "사건 기록"
  // (일화기억)에나 맞고 "현재 상태"(의미기억)에는 맞지 않습니다.
  //
  // 해설 2 — 참조 함정(위 출력의 두 번째/세 번째 줄):
  // InMemoryStore 의 get/search 는 내부 객체를 "복사 없이" 돌려줍니다.
  // 그래서 (1) 먼저 읽어 둔 Item 이 나중 put 때문에 소급해서 바뀌고,
  //        (2) item.value 를 직접 수정하면 put 을 부르지 않았는데도 store 가 오염됩니다.
  // 둘 다 에러가 나지 않습니다. 게다가 PostgresStore 는 값을 직렬화하므로
  // 이 동작이 "다릅니다" — InMemoryStore 에서 우연히 동작하던 코드가
  // DB 로 바꾸는 순간 조용히 깨집니다. 읽은 값을 수정할 거면 항상 복사하고,
  // 쓰기는 반드시 put 으로만 하세요.
}

/* ===== [정답 3] 네임스페이스 격리 ===== */

printSection("[정답 3] 네임스페이스 격리");

{
  const store = new InMemoryStore();
  await store.put(["user_1", "memories"], "a", { content: "user_1 의 비밀" });
  await store.put(["user_2", "memories"], "b", { content: "user_2 의 비밀" });

  const onlyUser1 = await store.search(["user_1", "memories"]);
  const everything = await store.search([]); // prefix 를 비우면 = 전부

  printKV({
    "(A) user_1 네임스페이스": onlyUser1.map((i) => i.value["content"]).join(", "),
    "(B) prefix 없이 search([])": everything.map((i) => `${i.namespace.join("/")}:${i.value["content"]}`).join(" | "),
  });

  // 답: search([]) 는 모든 사용자의 기억을 한꺼번에 돌려줍니다.
  //
  // 해설: 이게 이 스텝에서 가장 위험한 대목입니다. namespacePrefix 는 "필터"가
  // 아니라 "경로"이고, 빈 배열은 루트 = 전체입니다. 도구 안에서 네임스페이스를
  // [userId, "memories"] 가 아니라 ["memories"] 로 짜 두면, 에러 없이 잘 도는
  // 것처럼 보이면서 A 사용자의 기억이 B 사용자 답변에 섞여 나옵니다.
  // 테스트에서는 사용자가 한 명이라 절대 안 잡히고, 프로덕션에서 터집니다.
  // userId 는 반드시 네임스페이스의 "앞쪽"에 두세요. value 안에 userId 를 넣고
  // filter 로 거르는 방식은 필터를 빠뜨리는 순간 그대로 유출이 됩니다.
}

/* ===== [정답 4] 인덱스 없는 시맨틱 검색 ===== */

printSection("[정답 4] 인덱스 없는 시맨틱 검색");

{
  const store = new InMemoryStore(); // index 설정 없음
  const ns = ["u", "m"];
  await store.put(ns, "a", { text: "피자를 좋아함" });
  await store.put(ns, "b", { text: "주말엔 등산을 함" });

  const hits = await store.search(ns, { query: "이 사용자는 뭘 먹나요?" });

  printKV({
    "에러가 났는가": "아니오 — 정상 반환됩니다",
    "결과 score": hits.map((i) => `${i.key}=${i.score}`).join(", "), // 전부 undefined
    "store.indexConfig": String(store.indexConfig), // undefined
  });

  // 답: 시맨틱 검색이 동작 중인지는 store.indexConfig 가 정의되어 있는지로 판별합니다.
  //     (결과 쪽에서는 item.score 가 undefined 인지로 확인할 수 있습니다.)
  //
  // 해설: query 를 줬는데 인덱스가 없으면 LangGraph 는 예외를 던지지 않고
  // "그냥 저장 순서대로의 목록"을 돌려줍니다. limit 도 그대로 먹기 때문에
  // 코드는 완벽히 정상으로 보입니다. 문제는 검색 품질만 조용히 나빠진다는 것입니다.
  // 기억이 5건일 때는 아무 차이가 없어서 개발 중엔 절대 안 보이고,
  // 500건이 되면 "왜 엉뚱한 기억을 물어오지?"가 됩니다.
  // 부팅 시 store.indexConfig 를 한 번 assert 하는 게 값싼 방어입니다.
}

/* ===== [정답 5] store 에 쓰는 도구 ===== */

printSection("[정답 5] store 에 쓰는 도구");

{
  const store = new InMemoryStore();

  const saveNote = tool(
    async ({ note }, runtime: ToolRuntime<any, { userId: string }>) => {
      // 핵심: runtime.store 의 TS 타입은 @langchain/core 의 BaseStore<string, unknown> 입니다.
      // put/search 가 없는 다른 인터페이스라 그대로 쓰면 컴파일이 안 됩니다.
      // 런타임에 실제로 들어오는 객체는 langgraph 의 BaseStore 이므로 캐스팅합니다.
      const s = runtime.store as unknown as BaseStore;
      await s.put([runtime.context.userId, "notes"], crypto.randomUUID(), { note });
      return `메모했습니다: ${note}`;
    },
    {
      name: "save_note",
      description: "사용자의 메모를 저장합니다.",
      schema: z.object({ note: z.string() }),
    },
  );

  const agent = createAgent({
    model: new FakeToolCallingModel({
      toolCalls: [[{ name: "save_note", args: { note: "금요일 회고 준비" }, id: "c1" }], []],
    }),
    tools: [saveNote],
    contextSchema: z.object({ userId: z.string() }),
    store,
  });

  await agent.invoke({ messages: [{ role: "user", content: "메모해줘" }] }, { context: { userId: "user_1" } });

  printJson(
    "store 에 남은 notes",
    (await store.search(["user_1", "notes"])).map((i) => i.value),
  );

  // 해설: 캐스팅이 필요하다는 사실 자체가 함정입니다. 공식 문서 예제는
  // runtime.store.put(...) 을 바로 부르지만, 현재 배포된 타입 정의에서는
  // tsc 가 "Property 'put' does not exist" 를 냅니다.
  // as unknown as BaseStore 로 한 번 좁혀 놓고 쓰는 게 가장 깔끔합니다.
  // 참고로 runtime.store 는 우리가 넘긴 InMemoryStore 그 객체가 아니라
  // AsyncBatchedStore 래퍼입니다 → instanceof InMemoryStore 는 false 입니다.
  // "store 가 제대로 붙었나"를 instanceof 로 검사하면 멀쩡한데도 실패합니다.
}

/* ===== [정답 6] 중복 방지 저장 ===== */

printSection("[정답 6] 중복 방지 저장");

{
  const store = new InMemoryStore();

  async function saveDedup(s: BaseStore, userId: string, content: string): Promise<number> {
    const ns = [userId, "memories"];
    const existing = await s.search(ns);
    const dup = existing.find((i) => i.value["content"] === content);
    const key = dup?.key ?? crypto.randomUUID(); // 있으면 그 key 재사용 → upsert
    await s.put(ns, key, { content, updatedAt: new Date().toISOString() });
    return (await s.search(ns)).length;
  }

  const n1 = await saveDedup(store, "user_1", "매운 음식을 못 먹는다");
  const n2 = await saveDedup(store, "user_1", "매운 음식을 못 먹는다");
  const n3 = await saveDedup(store, "user_1", "매운 음식을 못 먹는다");

  printKV({ "1회 후": n1, "2회 후": n2, "3회 후": n3 }); // 1, 1, 1

  // 해설: 여기서는 문자열 완전일치로 중복을 잡았지만 실전에서는
  // "매운 거 못 먹음"과 "맵찔이임"처럼 표현만 다른 같은 사실이 들어옵니다.
  // 완전일치 dedup 은 이걸 못 잡습니다. 실무에서는
  //   (1) 사실 종류를 key 로 고정하거나 (예: key="food_preference")
  //   (2) 시맨틱 검색으로 유사한 기억을 먼저 찾아 모델에게 "갱신할지 새로 만들지" 결정시키거나
  //   (3) 주기적으로 요약·병합하는 배치를 돌립니다.
  // 무한정 append 하는 store 는 반드시 검색 품질이 무너집니다.
}

/* ===== [정답 7] checkpointer + store ===== */

printSection("[정답 7] checkpointer + store");

{
  const checkpointer = new MemorySaver();
  const store = new InMemoryStore();

  const remember = tool(
    async ({ fact }, runtime: ToolRuntime<any, { userId: string }>) => {
      const s = runtime.store as unknown as BaseStore;
      await s.put([runtime.context.userId, "memories"], crypto.randomUUID(), { fact });
      return `기억했습니다: ${fact}`;
    },
    { name: "remember", description: "사실을 저장합니다.", schema: z.object({ fact: z.string() }) },
  );

  const mk = (toolCalls: Array<Array<{ name: string; args: object; id: string }>>) =>
    createAgent({
      model: new FakeToolCallingModel({ toolCalls }),
      tools: [remember],
      contextSchema: z.object({ userId: z.string() }),
      checkpointer,
      store,
    });

  await mk([[{ name: "remember", args: { fact: "닉네임은 민수" }, id: "c1" }], []]).invoke(
    { messages: [{ role: "user", content: "내 닉네임은 민수야" }] },
    { configurable: { thread_id: "A" }, context: { userId: "user_1" } },
  );

  const b = await mk([[]]).invoke(
    { messages: [{ role: "user", content: "안녕" }] },
    { configurable: { thread_id: "B" }, context: { userId: "user_1" } },
  );

  printKV({
    "스레드 B 의 messages 길이": b.messages.length, // 2 — A 의 대화는 안 보입니다
    "store 의 기억": (await store.search(["user_1", "memories"])).map((i) => i.value["fact"]).join(", "),
  });

  // 답: store 만 붙이고 checkpointer 를 빼면 "같은 스레드 안의 대화 이어가기"가 깨집니다.
  //     사용자가 방금 한 말을 다음 턴에 기억하지 못하고, HITL(중단/재개)도 동작하지 않습니다.
  //     반대로 checkpointer 만 붙이면 스레드가 바뀌는 순간 사용자에 대해 아무것도 모르게 됩니다.
  //
  // 해설: 둘은 대체재가 아니라 보완재입니다. 헷갈리면 이렇게 기억하세요.
  //   checkpointer = "이 대화에서 무슨 말이 오갔나"  (스레드 안, 자동)
  //   store        = "이 사용자는 어떤 사람인가"      (스레드 밖, 직접 저장)
  //   thread_id 만 주고 checkpointer 를 안 붙이면 아무것도 안 남습니다(Step 10).
}

/* ===== [정답 8] 개인 비서 ===== */

printSection("[정답 8] 개인 비서");

{
  const ContextSchema = z.object({ userId: z.string() });
  type Ctx = z.infer<typeof ContextSchema>;

  const store = new InMemoryStore();
  const checkpointer = new MemorySaver();

  const saveMemory = tool(
    async ({ content }, runtime: ToolRuntime<any, Ctx>) => {
      const s = runtime.store as unknown as BaseStore;
      const ns = [runtime.context.userId, "memories"]; // userId 를 반드시 앞에
      const existing = await s.search(ns);
      const dup = existing.find((i) => i.value["content"] === content);
      await s.put(ns, dup?.key ?? crypto.randomUUID(), { content, updatedAt: new Date().toISOString() }, [
        "content",
      ]);
      return `기억했습니다: ${content}`;
    },
    {
      name: "save_memory",
      description:
        "사용자의 선호·사실 중 다음 대화에서도 쓸모 있는 것을 저장합니다. 사용자가 명시적으로 말한 것만 저장하세요.",
      schema: z.object({ content: z.string().describe("한 문장으로 요약한 사실") }),
    },
  );

  const searchMemory = tool(
    async ({ query }, runtime: ToolRuntime<any, Ctx>) => {
      const s = runtime.store as unknown as BaseStore;
      const items = await s.search([runtime.context.userId, "memories"], { query, limit: 5 });
      return items.length === 0 ? "저장된 기억이 없습니다." : items.map((i) => `- ${i.value["content"]}`).join("\n");
    },
    {
      name: "search_memory",
      description: "사용자에 대해 저장해 둔 사실을 검색합니다. 사용자 취향이 관련된 답을 하기 전에 먼저 호출하세요.",
      schema: z.object({ query: z.string() }),
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
        "사용자 취향이 관련된 질문에는 먼저 search_memory 로 확인한 뒤 답해라.",
        "기억에 없는 것을 지어내지 마라. 모르면 모른다고 말해라.",
      ].join(" "),
      contextSchema: ContextSchema,
      checkpointer,
      store,
    });

    const cfg = (thread: string) => ({
      configurable: { thread_id: thread },
      context: { userId: "user_1" },
    });

    const day1 = await agent.invoke(
      { messages: [{ role: "user", content: "나는 매운 음식을 못 먹고, 채식을 지향해." }] },
      cfg("day-1"),
    );
    printKV({ "1일차": String(day1.messages.at(-1)?.content).slice(0, 150) });

    const day2 = await agent.invoke(
      { messages: [{ role: "user", content: "점심 메뉴 하나만 추천해줘." }] },
      cfg("day-2"), // 새 스레드 — 대화 기록은 없지만 store 는 살아 있음
    );
    printKV({ "2일차": String(day2.messages.at(-1)?.content).slice(0, 250) });

    printJson(
      "store 의 기억",
      (await store.search(["user_1", "memories"])).map((i) => i.value["content"]),
    );

    // 해설: 2일차 응답에 "맵지 않은 채식 메뉴"가 나오면 성공입니다.
    // 이때 검증해야 할 것은 "그럴듯한 답"이 아니라 "search_memory 를 실제로 불렀는가"입니다.
    // 모델이 도구를 안 부르고 일반 상식으로 답해도 그럴듯해 보이기 때문입니다.
    // day2.messages 에서 tool 메시지를 찾아 확인하세요.
    const calledSearch = day2.messages.some((m) => m.getType() === "tool" && String(m.name) === "search_memory");
    printKV({ "search_memory 를 실제로 호출했는가": calledSearch ? "예" : "아니오(프롬프트를 더 강하게)" });
  } else {
    printKV({ 건너뜀: "ANTHROPIC_API_KEY 가 없어 실행을 건너뜁니다" });
  }
}

printSection("끝");
