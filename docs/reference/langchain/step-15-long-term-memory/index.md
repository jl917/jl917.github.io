# Step 15 — 장기 메모리와 Store

> **학습 목표**
> - **단기 메모리(checkpointer)** 와 **장기 메모리(store)** 를 구분하고, 왜 **둘 다** 필요한지 설명한다
> - `BaseStore` 의 **namespace / key / value** 모델을 이해하고 `put` / `get` / `search` / `delete` / `listNamespaces` 를 쓴다
> - `createAgent({ store })` 로 에이전트에 store 를 붙이고, 도구 안에서 `runtime.store` 로 읽고 쓴다
> - **무엇을 언제 저장할지** 정하고, 무한정 쌓이지 않게 **갱신·삭제 전략**을 넣는다
> - **임베딩 인덱스**를 설정해 시맨틱 검색을 켜고, 인덱스 없이 `query` 를 주면 **조용히 퇴화**하는 것을 잡아낸다
> - **의미기억 / 일화기억 / 절차기억**을 구분해 네임스페이스를 설계한다
> - `InMemoryStore` → `PostgresStore` 로 갈아끼울 때 무엇이 달라지는지 안다
>
> **선행 스텝**: [Step 14 — 컨텍스트와 런타임](../step-14-context-runtime/)
> **예상 소요**: 80분

[Step 10](../step-10-memory/) 에서 `checkpointer` 를 붙여 대화가 이어지게 만들었습니다. 하지만 그 메모리는 **스레드 안에 갇혀 있습니다.** `thread_id` 를 바꾸는 순간 에이전트는 사용자를 처음 보는 사람 취급합니다. 어제 "나 매운 거 못 먹어"라고 말했어도, 오늘 새 대화창을 열면 마라탕을 추천합니다.

사람이 쓰는 제품에서 이건 치명적입니다. 우리에게 필요한 건 "이 대화에서 무슨 말이 오갔나"(단기)가 아니라 **"이 사용자는 어떤 사람인가"**(장기)를 스레드를 넘어 들고 있는 저장소입니다. 그게 **Store** 입니다.

이 스텝은 Store 의 API 자체보다 **운영에서 조용히 터지는 것들**에 무게를 둡니다. 네임스페이스 설계를 한 글자 잘못 짜면 사용자 간 기억이 섞이고(보안 사고), 인덱스 설정을 빠뜨리면 시맨틱 검색이 에러 없이 그냥 목록으로 퇴화하며, 모델이 잘못 박은 사실은 영원히 남습니다. 전부 **테스트에서는 안 잡히는** 것들입니다.

---

## 15-1. 단기 vs 장기 — 스레드 안 vs 스레드를 넘어

LangGraph 에는 상태를 남기는 장치가 **두 개** 있고, 둘은 서로를 대체하지 않습니다.

| | **Checkpointer** (단기) | **Store** (장기) |
|---|---|---|
| 무엇을 담나 | 그래프 상태 스냅샷 (메시지 전체) | 애플리케이션이 정의한 키-값 데이터 |
| 범위 | **하나의 스레드 안** | **스레드를 넘어** (사용자·조직 단위) |
| 누가 쓰나 | 프레임워크가 **자동으로** | **내가 직접** `put` 을 불러야 |
| 식별자 | `thread_id` | `namespace` + `key` |
| 대표 클래스 | `MemorySaver`, `PostgresSaver` | `InMemoryStore`, `PostgresStore` |
| 붙이는 법 | `createAgent({ checkpointer })` | `createAgent({ store })` |
| 전형적 용도 | 대화 이어가기, HITL 중단/재개 | 사용자 선호, 사실, 공유 지식 |
| 없으면 | 매 턴 대화가 리셋된다 | 스레드가 바뀌면 사용자를 잊는다 |

핵심은 **자동 vs 수동** 입니다. checkpointer 는 붙여두면 알아서 메시지를 쌓지만, store 는 "무엇을 기억할지" 내가 코드로 결정해야 합니다. 아무것도 안 하면 store 는 영원히 비어 있습니다.

두 개를 모두 붙인 에이전트를 만들고 스레드를 바꿔 봅니다.

```ts
import { createAgent, tool, type ToolRuntime, FakeToolCallingModel } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore } from "@langchain/langgraph";
import * as z from "zod";

const checkpointer = new MemorySaver();
const store = new InMemoryStore();

const remember = tool(
  async ({ fact }, runtime: ToolRuntime<any, { userId: string }>) => {
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

const mkAgent = (toolCalls: Array<Array<{ name: string; args: object; id: string }>>) =>
  createAgent({
    model: new FakeToolCallingModel({ toolCalls }),
    tools: [remember],
    contextSchema: z.object({ userId: z.string() }),
    checkpointer,  // 단기: 스레드별 대화 기록
    store,         // 장기: 스레드를 넘는 사실
  });

// 스레드 A — 사실을 저장
await mkAgent([[{ name: "remember", args: { fact: "좋아하는 음식은 피자" }, id: "call_1" }], []])
  .invoke(
    { messages: [{ role: "user", content: "나는 피자를 좋아해" }] },
    { configurable: { thread_id: "thread-A" }, context: { userId: "user_1" } },
  );

// 스레드 B — 완전히 새 대화
const threadB = await mkAgent([[]]).invoke(
  { messages: [{ role: "user", content: "안녕" }] },
  { configurable: { thread_id: "thread-B" }, context: { userId: "user_1" } },
);
```

**출력**
```
thread-B 의 메시지 수  2
store 에 남은 기억     좋아하는 음식은 피자
```

스레드 B 의 메시지는 **2개**(방금 보낸 것 + 응답)뿐입니다. 스레드 A 에서 오간 대화는 보이지 않습니다 — checkpointer 는 `thread_id` 단위로 격리되니까요. 반면 **store 의 기억은 그대로 남아 있습니다.** 이게 장기 메모리입니다.

여기서 `FakeToolCallingModel` 을 쓴 이유는 API 키 없이 **결정적으로** 도구 호출을 재현하기 위해서입니다. 실제 모델은 "저장할지 말지"를 스스로 판단하므로 매번 결과가 달라집니다.

> ⚠️ **함정 — store 는 checkpointer 와 별개다. 둘 다 필요하다.**
> 가장 흔한 오해가 "store 를 붙였으니 메모리는 해결됐다"입니다. **아닙니다.**
> - `store` 만 붙이면: 스레드 안에서 방금 한 말을 다음 턴에 기억하지 못합니다. 매 턴이 첫 턴입니다. HITL 중단/재개도 동작하지 않습니다.
> - `checkpointer` 만 붙이면: 스레드가 바뀌는 순간 사용자에 대해 아무것도 모릅니다.
>
> 둘은 **보완재**입니다. 프로덕션 에이전트는 대개 **둘 다** 붙입니다. 그리고 [Step 10](../step-10-memory/) 에서 본 것처럼 `thread_id` 만 주고 `checkpointer` 를 안 붙이면 아무것도 안 남습니다 — 에러 없이요.

---

## 15-2. BaseStore 인터페이스 — namespace, key, value

Store 의 데이터 모델은 단순합니다. **네임스페이스(폴더) 안에 키(파일 이름)로 값(JSON)을 넣습니다.**

- **namespace**: `string[]` — 계층 경로. `["user_1", "memories"]` 는 `user_1/memories` 폴더입니다.
- **key**: `string` — 그 폴더 안의 고유 식별자.
- **value**: `Record<string, any>` — JSON 직렬화 가능한 객체.

`(namespace, key)` 쌍이 **기본키**입니다. 같은 쌍에 다시 `put` 하면 새 행이 생기는 게 아니라 **덮어써집니다(upsert)**.

```ts
import { InMemoryStore } from "@langchain/langgraph";

const store = new InMemoryStore();
const ns = ["user_1", "memories"];

// put(namespace, key, value)
await store.put(ns, "m1", { text: "피자를 좋아함", kind: "semantic" });
await store.put(ns, "m2", { text: "채식 지향", kind: "semantic" });
await store.put(ns, "m3", { text: "2026-07-01 환불을 요청했음", kind: "episodic" });

// get → Item | null
const one = await store.get(ns, "m1");
```

**출력** (구조는 결정적입니다)
```json
{
  "value": { "text": "피자를 좋아함", "kind": "semantic" },
  "key": "m1",
  "namespace": ["user_1", "memories"],
  "createdAt": "2026-07-17T08:25:36.884Z",
  "updatedAt": "2026-07-17T08:25:36.884Z"
}
```

`Item` 의 필드는 정확히 이 다섯 개입니다: `value`, `key`, `namespace`, `createdAt`, `updatedAt`. 시맨틱 검색 결과(`SearchItem`)에는 여기에 `score?: number` 가 붙습니다.

`search` 는 **네임스페이스 접두사**로 훑고, `filter` 로 값을 거릅니다.

```ts
// 쿼리 없이 부르면 그냥 목록
const all = await store.search(ns);

// filter — value 안의 필드를 정확히 매칭
const semantic = await store.search(ns, { filter: { kind: "semantic" } });

// delete
await store.delete(ns, "m2");

// listNamespaces — 어떤 네임스페이스가 있는지 훑어보기
await store.put(["user_2", "memories"], "x1", { text: "다른 사용자" });
const namespaces = await store.listNamespaces({ maxDepth: 2 });
```

**출력**
```
search(ns) 개수  3
keys           m1, m2, m3
filter kind=semantic  m1, m2
delete 후  m1, m3
listNamespaces({ maxDepth: 2 })
[ ["user_1", "memories"], ["user_2", "memories"] ]
```

메서드 시그니처를 정확히 정리하면 이렇습니다.

| 메서드 | 시그니처 | 반환 |
|---|---|---|
| `put` | `put(namespace, key, value, index?: false \| string[])` | `Promise<void>` |
| `get` | `get(namespace, key)` | `Promise<Item \| null>` |
| `search` | `search(namespacePrefix, { filter?, limit?, offset?, query? })` | `Promise<SearchItem[]>` |
| `delete` | `delete(namespace, key)` | `Promise<void>` |
| `listNamespaces` | `listNamespaces({ prefix?, suffix?, maxDepth?, limit?, offset? })` | `Promise<string[][]>` |

`filter` 는 정확일치뿐 아니라 연산자도 받습니다: `$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte`. 예: `filter: { confidence: { $gte: 0.8 } }`.

> ⚠️ **함정 — `put` 의 4번째 인자는 객체가 아니라 배열이다.**
> 인덱싱할 필드를 지정하는 인자는 **위치 인자**이고 타입은 `false | string[]` 입니다.
> ```ts
> await store.put(ns, "m1", { text: "..." }, ["text"]);   // ✅ 올바름
> await store.put(ns, "m1", { text: "..." }, { index: ["text"] });  // ❌ 조용히 무시됨
> ```
> 두 번째 형태는 Python 문서의 관용구가 옮겨 붙은 것으로, 일부 문서 예제에도 이 형태가 남아 있습니다. TypeScript 에서 이걸 쓰면 `as any` 를 곁들이는 순간 **에러 없이 통과하면서 인덱싱만 안 됩니다.** `as any` 없이 쓰면 tsc 가 잡아 주니, 이 자리에 캐스팅을 하지 마세요.

> 💡 **실무 팁 — `await` 를 빠뜨리지 마세요.**
> `put`/`get`/`search`/`delete` 는 **전부 async** 입니다. "저장하고 바로 읽기" 패턴에서 `await` 를 빠뜨리면 아직 쓰이지 않은 상태에서 `search` 가 돌아 "방금 저장한 게 없다"가 됩니다. 도구 함수는 대개 `async` 라서 lint 가 없으면 눈에 잘 안 띕니다.

---

## 15-3. InMemoryStore 로 시작하기

`InMemoryStore` 는 JavaScript `Map` 위에 얹은 구현체입니다. 설치할 것도, 띄울 것도 없어서 학습과 테스트에 좋습니다.

```ts
import { InMemoryStore } from "@langchain/langgraph";

const store = new InMemoryStore();
```

네임스페이스에는 **검증 규칙**이 있습니다. 셋 다 조용히 넘어가지 않고 즉시 에러를 던집니다.

```ts
const tries: Array<[string, string[]]> = [
  ["빈 네임스페이스", []],
  ["예약어 langgraph", ["langgraph", "x"]],
  ["점이 든 라벨", ["a.b"]],
];

for (const [label, ns] of tries) {
  try {
    await store.put(ns, "k", { t: 1 });
  } catch (e) {
    console.log(label, (e as Error).message);
  }
}
```

**출력** (에러 메시지는 결정적입니다)
```
빈 네임스페이스    에러: Namespace cannot be empty.
예약어 langgraph  에러: Root label for namespace cannot be "langgraph". Got: langgraph,x
점이 든 라벨      에러: Invalid namespace label 'a.b' found in a.b. Namespace labels cannot contain periods ('.').
```

라벨에 점을 못 쓴다는 게 실무에서 은근히 걸립니다. **이메일 주소를 네임스페이스 라벨로 쓰면 바로 터집니다**(`kim.minsu@example.com`). 사용자 식별자는 점이 없는 내부 ID 를 쓰세요.

이제 `InMemoryStore` 의 진짜 성격을 봅시다.

```ts
const ns = ["user_1", "memories"];
await store.put(ns, "ref", { content: "원본" });

const item = await store.get(ns, "ref");
(item!.value as Record<string, unknown>)["content"] = "몰래 바꿈";  // put 을 부르지 않았습니다

console.log(JSON.stringify((await store.get(ns, "ref"))?.value));
console.log(item === (await store.get(ns, "ref")));
```

**출력**
```
value 를 직접 수정한 뒤 재조회  {"content":"몰래 바꿈"}
get 이 같은 객체를 주는가      true
```

`put` 을 부르지 않았는데 store 가 바뀌었습니다.

> ⚠️ **함정 — InMemoryStore 는 값을 복사하지 않는다 (그리고 PostgresStore 는 복사한다).**
> `InMemoryStore` 의 `get`/`search` 는 내부 객체의 **참조**를 그대로 돌려줍니다. 결과는 두 가지입니다.
> 1. 읽어 둔 `item.value` 를 수정하면 **`put` 없이 store 가 오염됩니다.**
> 2. 먼저 읽어 둔 `Item` 이 나중의 `put` 때문에 **소급해서 바뀝니다.** (읽은 값을 나중에 비교하려면 `structuredClone` 으로 복사해 두세요.)
>
> 진짜 문제는 여기서부터입니다. `PostgresStore` 는 값을 **직렬화**하므로 이 동작이 **다릅니다.** 즉 InMemoryStore 에서 우연히 잘 돌던 코드가 DB 로 갈아끼우는 순간 조용히 깨집니다. 에러도, 경고도 없습니다.
> **읽은 값을 수정할 거면 복사하고, 쓰기는 반드시 `put` 으로만 하세요.**

> ⚠️ **함정 — InMemoryStore 는 프로세스와 함께 죽는다.**
> 이름 그대로 전부 메모리에 있습니다. 서버를 재배포하면 모든 사용자의 기억이 **전부 사라집니다.** `MemorySaver` 와 똑같은 함정인데, 장기 메모리라 체감 피해가 훨씬 큽니다(사용자는 "얘가 어제 일을 잊었다"를 즉시 알아챕니다). 게다가 프로세스가 여러 개면(오토스케일링, 서버리스) **인스턴스마다 기억이 다릅니다.** 같은 사용자가 요청을 두 번 보냈는데 한 번은 기억하고 한 번은 못 하는, 재현이 안 되는 버그가 됩니다. 개발·테스트 전용으로만 쓰세요.

---

## 15-4. 에이전트에 store 붙이기 — `createAgent({ store })`

`createAgent` 에 `store` 를 넘기면, 그 에이전트가 실행하는 **모든 도구**가 `runtime.store` 로 그 store 에 접근할 수 있습니다.

```ts
const store = new InMemoryStore();

const probe = tool(
  async (_input, runtime: ToolRuntime<any, { userId: string }>) => {
    const isInMemory = (runtime.store as unknown) instanceof InMemoryStore;
    const s = runtime.store as unknown as BaseStore;
    await s.put([runtime.context.userId, "memories"], "from_tool", { text: "도구가 저장함" });
    return `constructor=${runtime.store?.constructor?.name} instanceof InMemoryStore=${isInMemory}`;
  },
  { name: "probe", description: "store 접근을 확인합니다.", schema: z.object({}) },
);

const agent = createAgent({
  model: new FakeToolCallingModel({ toolCalls: [[{ name: "probe", args: {}, id: "call_1" }], []] }),
  tools: [probe],
  contextSchema: z.object({ userId: z.string() }),
  store,   // ← 여기서 준 store 가 도구의 runtime.store 로 흘러 들어갑니다
});

const res = await agent.invoke(
  { messages: [{ role: "user", content: "확인해줘" }] },
  { context: { userId: "user_1" } },
);
```

**출력**
```
도구가 본 store     constructor=AsyncBatchedStore instanceof InMemoryStore=false
바깥 store 에서 읽기  {"text":"도구가 저장함"}
```

두 가지가 보입니다. 첫째, 도구가 쓴 값은 **바깥의 진짜 store 에 도달합니다.** 둘째, `runtime.store` 는 우리가 넘긴 `InMemoryStore` **그 객체가 아닙니다** — `AsyncBatchedStore` 라는 래퍼입니다. LangGraph 가 여러 연산을 묶어 처리하려고 감싼 것입니다.

`userId` 는 `contextSchema` 로 받습니다([Step 14](../step-14-context-runtime/)). 이건 선택이 아니라 **필수에 가깝습니다** — 네임스페이스를 사용자별로 나누려면 도구가 "지금 누구인지" 알아야 하니까요.

> ⚠️ **함정 — `runtime.store` 의 TS 타입이 langgraph 의 `BaseStore` 가 아니다.**
> 공식 문서 예제는 `runtime.store.put(...)` 을 바로 부르지만, 현재 배포된 타입 정의에서 `ToolRuntime.store` 는 이렇게 선언되어 있습니다.
> ```ts
> store: BaseStore<string, unknown> | null;   // ← @langchain/core/stores 의 BaseStore
> ```
> 이건 `mget`/`mset` 을 가진 **다른 인터페이스**입니다. `put`/`search` 가 없어서 그대로 쓰면 tsc 가 이렇게 냅니다.
> ```
> error TS2339: Property 'put' does not exist on type 'BaseStore<string, unknown>'.
> ```
> **런타임에 실제로 들어오는 객체는 langgraph 의 store 가 맞습니다.** 타입 선언만 어긋나 있습니다. 그래서 캐스팅으로 좁혀 쓰면 됩니다.
> ```ts
> import type { BaseStore } from "@langchain/langgraph";
> const s = runtime.store as unknown as BaseStore;
> await s.put(ns, key, value);   // ✅ 컴파일도 되고 런타임도 동작
> ```
> 이름이 같은 타입이 두 패키지에 있다는 게 이 함정의 본질입니다. import 를 어디서 했는지 항상 확인하세요.

> ⚠️ **함정 — `instanceof` 로 store 를 검사하지 마라.**
> 위 출력에서 봤듯 `runtime.store instanceof InMemoryStore` 는 **false** 입니다. `AsyncBatchedStore` 래퍼니까요. "store 가 제대로 붙었나"를 `instanceof` 로 확인하는 코드는 멀쩡한 상황에서도 실패합니다. 붙었는지 보려면 `runtime.store != null` 로 충분합니다.

---

## 15-5. 메모리 쓰기 — 무엇을 언제 저장할 것인가

Store 는 **자동으로 채워지지 않습니다.** 누군가 `put` 을 불러야 합니다. 방식은 크게 둘입니다.

| | **모델이 저장 도구를 호출** | **미들웨어가 자동 추출** |
|---|---|---|
| 방식 | `save_memory` 도구를 주고 모델이 판단 | 매 턴 끝에 훅에서 대화를 훑어 저장 |
| 저장 시점 | 모델이 "기억할 만하다"고 볼 때 | 정해진 시점마다 |
| 장점 | 의도가 명확, 사용자가 "기억해"라고 하면 확실히 동작 | 모델이 깜빡해도 놓치지 않음 |
| 단점 | **모델이 안 부르면 아무것도 안 남음** | 잡음까지 저장, 매 턴 추가 비용 |
| 제어 지점 | 도구 `description` | 미들웨어 로직 |

**모델이 직접 저장 도구를 호출**하는 방식이 기본값입니다. 여기서 도구의 `description` 이 곧 **저장 정책**입니다.

```ts
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
```

같은 사실을 두 번 저장하도록 강제해도 결과는 이렇습니다.

**출력**
```
저장된 기억 수  1
내용        매운 음식을 못 먹는다
```

두 번 호출됐지만 `dup` 을 찾아 같은 `key` 를 재사용했기 때문에 행이 늘지 않았습니다. 이게 **갱신 전략**의 가장 단순한 형태입니다.

> ⚠️ **함정 — 메모리를 무한정 쌓으면 검색 품질이 무너진다.**
> `key` 를 매번 `crypto.randomUUID()` 로 만들고 `put` 만 하는 코드는 처음엔 완벽하게 동작합니다. 기억이 5건일 땐 뭘 해도 잘 나오니까요. 그런데 500건이 되면:
> - "커피는 디카페인만"과 "커피는 아예 안 마심"이 **동시에** 검색되어 모델이 모순된 사실을 봅니다. 최신 것이 이기지 않습니다 — 유사도가 이깁니다.
> - 비슷한 기억이 상위 `limit` 를 다 차지해 정작 필요한 다른 기억이 밀려납니다.
>
> **UUID 키는 "사건 기록"(일화기억)에나 맞고 "현재 상태"(의미기억)에는 맞지 않습니다.** 변하는 사실은 안정적인 키(예: `key: "food_preference"`)로 덮어쓰거나, 위처럼 기존 항목을 찾아 갱신하세요. 쓰기 경로에 **갱신·삭제 전략이 없는 store 는 반드시 썩습니다.**

> ⚠️ **함정 — 모델이 잘못된 사실을 박으면 영구히 틀린다 (메모리 오염).**
> 대화 중 모델이 사용자 말을 오해해 "사용자는 서울에 산다"를 저장했다고 합시다. 그 사실은 **다음 모든 대화의 시스템 프롬프트에 주입됩니다.** 그러면 모델은 그걸 근거로 답하고, 사용자가 정정하지 않는 한 계속 틀립니다. 게다가 그 잘못된 기억을 바탕으로 새 기억이 파생되면 오염이 번집니다.
> 단기 메모리의 실수는 대화가 끝나면 사라지지만, **장기 메모리의 실수는 영구적입니다.** 방어책:
> - 도구 description 에 **"사용자가 명시적으로 말한 것만 저장하라"** 를 넣는다 (추론한 것 저장 금지)
> - `confidence` 나 `source`(원문 인용)를 value 에 같이 저장해 나중에 추적 가능하게 한다
> - 중요한 사실은 [Step 13](../step-13-hitl/) 의 HITL 로 저장 전에 사람이 승인하게 한다
> - 사용자가 자기 기억을 **보고 지울 수 있는** 경로를 반드시 제공한다 (`delete` 를 쓰는 도구 또는 UI)

> 💡 **실무 팁 — 미들웨어 자동 추출은 "놓치면 안 되는 것"에만.**
> 매 턴 대화를 훑어 사실을 추출하는 미들웨어([Step 12](../step-12-middleware-custom/))는 모델이 저장을 깜빡하는 문제를 없애 주지만, 턴마다 추가 LLM 호출이 붙어 비용과 지연이 늘고 잡음까지 저장합니다. 실무에서는 **저장 도구를 기본으로 두고**, 놓치면 치명적인 항목(예: 알레르기, 계약 조건)에만 자동 추출을 겹쳐 쓰는 조합이 무난합니다.

---

## 15-6. 메모리 읽기 — 시맨틱 검색

기억이 쌓이면 "전부 읽어서 프롬프트에 넣기"가 불가능해집니다. 필요한 것만 골라야 하고, 그때 쓰는 게 **시맨틱 검색**입니다. 이건 **인덱스를 설정해야만** 켜집니다.

먼저 설정을 **빠뜨렸을 때** 무슨 일이 나는지부터 봅니다.

```ts
const noIndex = new InMemoryStore();   // index 설정 없음
await noIndex.put(["u", "m"], "a", { text: "피자를 좋아함" });
await noIndex.put(["u", "m"], "b", { text: "주말엔 등산을 함" });

const bad = await noIndex.search(["u", "m"], { query: "이 사용자는 뭘 먹나요?" });
```

**출력**
```
인덱스 없이 query       a(score=undefined), b(score=undefined)
store.indexConfig  undefined
```

**에러가 안 납니다.** `query` 를 줬는데 인덱스가 없으면 LangGraph 는 예외를 던지지 않고 **그냥 저장 순서대로의 목록**을 돌려줍니다. `limit` 도 그대로 먹습니다. 코드는 완벽히 정상으로 보이고, 기억이 5건일 땐 결과도 멀쩡해 보입니다.

인덱스를 설정하면 진짜 시맨틱 검색이 됩니다.

```ts
import { OpenAIEmbeddings } from "@langchain/openai";

const store = new InMemoryStore({
  index: {
    embeddings: new OpenAIEmbeddings({ model: "text-embedding-3-small" }),
    dims: 1536,          // 임베딩 모델의 차원과 반드시 일치해야 합니다
    fields: ["text"],    // 이 필드만 임베딩. ["$"] 면 문서 전체.
  },
});

const ns = ["user_1", "memories"];
await store.put(ns, "m1", { text: "좋아하는 음식은 피자" });
await store.put(ns, "m2", { text: "주말마다 등산을 감" });
await store.put(ns, "m3", { text: "다크 테마를 씀" }, false);   // 인덱싱 제외

const hits = await store.search(ns, { query: "이 사용자는 뭘 먹나요?", limit: 3 });
```

**출력 예시** (임베딩 모델 응답이므로 score 값은 매번 다릅니다)
```json
[
  { "key": "m1", "text": "좋아하는 음식은 피자", "score": 0.41 },
  { "key": "m2", "text": "주말마다 등산을 감", "score": 0.12 },
  { "key": "m3", "text": "다크 테마를 씀", "score": null }
]
```

음식 관련 `m1` 이 가장 높은 `score` 로 올라왔습니다. 그리고 `index: false` 로 넣은 `m3` 는 **결과에서 빠지지 않고** `score` 가 `undefined` 인 채 뒤에 붙습니다 — "인덱싱 제외"는 "검색 제외"가 아니라 "순위 매기기 제외"입니다.

`fields` 는 **임베딩할 텍스트를 어디서 뽑을지**를 정합니다. 경로 문법을 지원합니다.

| `fields` 값 | 의미 |
|---|---|
| `["$"]` (기본값) | 문서 전체를 하나의 벡터로 |
| `["text"]` | `value.text` 만 |
| `["metadata.title"]` | 중첩 필드 |
| `["chapters[*].content"]` | 배열의 각 원소를 **따로** 임베딩 |
| `["authors[0].name"]` | 특정 인덱스 |

`put` 의 4번째 인자로 항목별 재정의도 됩니다: `["content"]` 면 그 필드만, `false` 면 이 항목은 인덱싱 안 함.

> ⚠️ **함정 — 인덱스 설정 없이 `query` 를 주면 시맨틱 검색이 조용히 퇴화한다.**
> 위에서 본 그대로입니다. 에러가 없고, 결과도 나오고, 개수도 맞습니다. **품질만 조용히 나빠집니다.** 그래서 개발 중엔 절대 안 보이고 기억이 쌓인 뒤에야 "왜 엉뚱한 기억을 물어오지?"가 됩니다.
> 판별법은 두 가지입니다.
> ```ts
> store.indexConfig === undefined      // 인덱스가 아예 없다
> items[0].score === undefined         // 순위가 안 매겨졌다
> ```
> 부팅 시 `if (!store.indexConfig) throw new Error("시맨틱 검색 미설정")` 한 줄이 값싼 방어입니다.

> ⚠️ **함정 — `dims` 가 모델과 안 맞아도 put 은 통과한다.**
> `dims` 는 임베딩 모델의 실제 출력 차원과 **일치해야** 합니다. `text-embedding-3-small` 은 1536(또는 512), `text-embedding-3-large` 는 3072/1024/256 입니다. 값을 잘못 적으면 저장 시점이 아니라 검색 품질에서 어긋남이 드러나거나 벡터 백엔드에서 뒤늦게 실패합니다. 모델을 바꾸면 `dims` 도 같이 바꾸고, **이미 저장된 벡터는 예전 모델로 만들어진 것**이라는 점도 기억하세요 — 임베딩 모델 교체는 **재색인**이 필요합니다.

> 💡 **실무 팁 — `fields` 로 임베딩 대상을 좁히세요.**
> 기본값 `["$"]` 는 문서 전체를 임베딩합니다. `updatedAt: "2026-07-17T..."` 같은 타임스탬프나 내부 ID 까지 벡터에 섞이면 유사도가 흐려집니다. 사람이 읽을 의미가 담긴 필드(`content`, `text`)만 지정하는 게 검색 품질에 유리하고, 임베딩 토큰 비용도 아낍니다.

---

## 15-7. 메모리 유형 — 의미 / 일화 / 절차

인지심리학에서 빌려온 분류지만, 실무에서 **저장·읽기 전략이 실제로 달라지기** 때문에 쓸모가 있습니다.

| 유형 | 무엇 | 예 | 키 전략 | 읽기 전략 |
|---|---|---|---|---|
| **의미기억** (semantic) | 사실·선호. 현재 상태 | "이름은 김민수", "매운 걸 못 먹음" | **안정적 키로 덮어쓰기** | 대체로 **항상** 프롬프트에 주입 |
| **일화기억** (episodic) | 특정 시점의 사건 | "2026-07-10 배송 지연 문의" | **UUID 로 append** (사건은 안 변함) | **관련될 때만** 시맨틱 검색 |
| **절차기억** (procedural) | 일하는 방식·규칙 | "보고서는 표로 먼저 요약" | 안정적 키로 덮어쓰기 | **항상** 시스템 프롬프트에 주입 |

가장 중요한 차이는 **의미기억은 변하고 일화기억은 안 변한다**는 점입니다. "커피는 디카페인만"이 "커피는 아예 안 마심"으로 바뀌면 **이전 사실은 틀린 것**이 되어 지워져야 합니다. 반면 "7월 1일에 환불을 요청했다"는 나중에 무슨 일이 있어도 여전히 참입니다. 그래서 의미기억에 UUID 키를 쓰면 15-5 의 모순 문제가 생기고, 일화기억을 덮어쓰면 이력이 날아갑니다.

유형을 **네임스페이스로 나누면** 읽을 때 필요한 것만 가져올 수 있습니다.

```ts
const userId = "user_1";

await store.put([userId, "semantic"], "s1", { content: "이름은 김민수", confidence: 0.95 });
await store.put([userId, "semantic"], "s2", { content: "회사는 무신사", confidence: 0.9 });
await store.put([userId, "episodic"], "e1", { content: "2026-07-10 배송 지연으로 문의함", at: "2026-07-10" });
await store.put([userId, "procedural"], "p1", { content: "보고서는 항상 표로 먼저 요약해 줄 것" });

// 항상 주입할 것 = 의미 + 절차
const always = [
  ...(await store.search([userId, "semantic"])),
  ...(await store.search([userId, "procedural"])),
];
```

**출력**
```
semantic    이름은 김민수 | 회사는 무신사
episodic    2026-07-10 배송 지연으로 문의함
procedural  보고서는 항상 표로 먼저 요약해 줄 것
프롬프트에 항상 넣을 것  3건
```

의미·절차기억은 보통 건수가 적어서(수십 건) **통째로 프롬프트에 넣습니다.** 일화기억은 무한히 늘어나므로 **시맨틱 검색으로 관련된 것만** 꺼냅니다. 유형별로 네임스페이스가 갈려 있으면 이 두 전략을 섞지 않고 깔끔하게 구현할 수 있습니다.

> 💡 **실무 팁 — 유형을 `value.kind` 필드가 아니라 네임스페이스로 나누세요.**
> `{ kind: "semantic" }` 을 넣고 `filter` 로 거를 수도 있지만, 네임스페이스로 나누면 **의미기억만 대상으로 시맨틱 검색**을 돌리거나 **일화기억만 90일 후 삭제**하는 것 같은 유형별 정책을 훨씬 쉽게 겁니다. `filter` 는 빠뜨릴 수 있지만 경로는 빠뜨릴 수 없습니다.

---

## 15-8. 영속 Store — PostgresStore

`InMemoryStore` 는 프로세스와 함께 죽습니다. 프로덕션에서는 DB 백엔드가 필요합니다.

```bash
npm install @langchain/langgraph-checkpoint-postgres
```

```ts
import { PostgresStore } from "@langchain/langgraph-checkpoint-postgres/store";

const store = PostgresStore.fromConnString(process.env.DB_URI!);
await store.setup();   // ← 최초 1회 테이블/인덱스 생성
```

import 경로에 **`/store` 서브패스**가 붙는다는 점을 놓치지 마세요. 같은 패키지의 루트(`@langchain/langgraph-checkpoint-postgres`)는 **checkpointer**(`PostgresSaver`)를 내보냅니다. 이름이 비슷해서 헷갈리기 쉽습니다.

| 클래스 | import | 역할 |
|---|---|---|
| `PostgresSaver` | `@langchain/langgraph-checkpoint-postgres` | checkpointer (단기) |
| `PostgresStore` | `@langchain/langgraph-checkpoint-postgres/store` | store (장기) |

checkpointer 쪽에는 `SqliteSaver`, `MongoDBSaver`, `RedisSaver` 도 각각 `@langchain/langgraph-checkpoint-{sqlite,mongodb,redis}` 패키지로 있습니다.

`PostgresStore` 는 `BaseStore` 를 그대로 구현하므로 **15-9 의 에이전트 코드는 store 를 만드는 한 줄만 바꾸면 그대로 돕니다.** 다만 몇 가지가 더 있습니다.

```ts
// TTL — 오래된 기억을 자동으로 만료
await store.put(ns, key, value, ["content"], { ttl: 60 * 24 * 90 });  // 분 단위 (여기선 90일)
```

`PostgresStoreConfig` 의 `ttl` 설정으로 기본값도 줄 수 있습니다: `defaultTtl`(분), `refreshOnRead`(읽을 때 갱신, 기본 `true`), `sweepIntervalMinutes`(청소 주기, 기본 60).

> ⚠️ **함정 — `setup()` 을 빠뜨리면 런타임에 실패한다.**
> `PostgresStore.fromConnString(...)` 은 객체만 만듭니다. **테이블은 `setup()` 이 만듭니다.** 빠뜨리면 생성 시점이 아니라 첫 `put`/`search` 때 "relation does not exist" 로 터집니다. 애플리케이션 부팅 시 딱 한 번 부르세요(매 요청마다 부르면 안 됩니다). 마이그레이션을 별도로 관리하는 팀은 `ensureTables` 설정을 확인하세요.

> 💡 **실무 팁 — 개발에서도 되도록 영속 store 를 쓰세요.**
> `InMemoryStore` 로만 개발하면 15-3 의 **참조 함정**(값이 복사되지 않음)에 의존하는 코드를 자기도 모르게 짜게 됩니다. Postgres 는 직렬화하므로 그 코드가 배포 후 조용히 깨집니다. 로컬 Docker 로 Postgres 를 띄워 두고 개발하면 이 계열의 사고를 통째로 예방합니다. `InMemoryStore` 는 **단위 테스트**에 남겨두세요 — 테스트마다 새 인스턴스를 만들면 격리가 공짜로 됩니다.

---

## 15-9. 실전: 사용자 선호를 기억하는 개인 비서

지금까지의 것을 모아 **어제 한 말을 오늘 기억하는** 비서를 만듭니다. 저장 도구와 검색 도구를 주고, checkpointer 와 store 를 **둘 다** 붙입니다.

```ts
const ContextSchema = z.object({ userId: z.string() });
type Ctx = z.infer<typeof ContextSchema>;

const store = new InMemoryStore();
const checkpointer = new MemorySaver();

const saveMemory = tool(
  async ({ content }, runtime: ToolRuntime<any, Ctx>) => {
    const s = runtime.store as unknown as BaseStore;
    await s.put(
      [runtime.context.userId, "memories"],   // ← userId 를 반드시 네임스페이스에
      crypto.randomUUID(),
      { content, updatedAt: new Date().toISOString() },
      ["content"],
    );
    return `기억했습니다: ${content}`;
  },
  {
    name: "save_memory",
    description: "사용자의 선호·사실 중 다음 대화에서도 쓸모 있는 것을 저장합니다. 확실한 것만 저장하세요.",
    schema: z.object({ content: z.string().describe("한 문장으로 요약한 사실") }),
  },
);

const searchMemory = tool(
  async ({ query }, runtime: ToolRuntime<any, Ctx>) => {
    const s = runtime.store as unknown as BaseStore;
    const items = await s.search([runtime.context.userId, "memories"], { query, limit: 5 });
    if (items.length === 0) return "저장된 기억이 없습니다.";
    return items.map((i) => `- ${i.value["content"]}`).join("\n");
  },
  {
    name: "search_memory",
    description: "사용자에 대해 이전에 저장해 둔 사실을 검색합니다. 답하기 전에 먼저 호출하세요.",
    schema: z.object({ query: z.string().describe("찾고 싶은 내용") }),
  },
);

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
await agent.invoke(
  { messages: [{ role: "user", content: "나는 매운 음식을 못 먹고, 채식을 지향해." }] },
  cfg("day-1"),
);

// 2일차 — 완전히 새 스레드. 대화 기록은 없지만 store 는 살아 있습니다.
const day2 = await agent.invoke(
  { messages: [{ role: "user", content: "점심 메뉴 하나만 추천해줘." }] },
  cfg("day-2"),
);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
1일차  알겠습니다. 매운 음식을 못 드시고 채식을 지향하신다는 점을 기억해 두었습니다.
2일차  맵지 않은 채식 메뉴로 두부 버섯 덮밥은 어떠세요? 자극적이지 않고 ...
store 에 쌓인 기억  ["매운 음식을 못 먹는다", "채식을 지향한다"]
```

2일차는 **새 스레드**라 "매운 걸 못 먹는다"는 대화 기록이 전혀 없습니다. 그런데도 맵지 않은 채식 메뉴가 나왔습니다 — 모델이 `search_memory` 를 불러 store 에서 꺼내 온 것입니다. 이게 장기 메모리가 하는 일입니다.

> ⚠️ **함정 — 네임스페이스에 `user_id` 를 안 넣으면 사용자 간 기억이 섞인다. (이 스텝에서 가장 위험)**
> 위 코드에서 네임스페이스를 `["memories"]` 로 짰다고 합시다.
> ```ts
> await s.put(["memories"], crypto.randomUUID(), { content });        // ❌
> await s.search(["memories"], { query });                            // ❌ 전 사용자의 기억
> ```
> **에러가 나지 않습니다.** 잘 도는 것처럼 보입니다. 그런데 A 사용자가 저장한 사실이 B 사용자의 답변에 섞여 나옵니다. 남의 이름, 남의 회사, 남의 건강 정보가요. 이건 버그가 아니라 **개인정보 유출 사고**입니다.
> 더 나쁜 건 **테스트에서 절대 안 잡힌다**는 점입니다. 개발 중엔 사용자가 나 혼자라 아무 이상이 없습니다. 프로덕션에서 두 번째 사용자가 들어오는 순간 터집니다.
> 규칙:
> - `userId` 는 반드시 네임스페이스의 **앞쪽**에: `[userId, "memories"]`
> - `value` 안에 `userId` 를 넣고 `filter` 로 거르는 방식은 **쓰지 마세요.** 필터를 한 번 빠뜨리는 순간 그대로 유출입니다. 경로는 빠뜨릴 수 없지만 필터는 빠뜨릴 수 있습니다.
> - `search([])` 처럼 **빈 prefix** 는 루트 = **전체**입니다. 절대 사용자 요청 경로에 두지 마세요.
> - `userId` 는 `runtime.context` 에서 가져오세요. 모델이 채우는 **도구 인자로 받으면 안 됩니다** — 모델이 다른 사용자 ID 를 지어내거나, 사용자가 프롬프트로 주입할 수 있습니다.

> 💡 **실무 팁 — "그럴듯한 답"이 아니라 "도구를 실제로 불렀는가"를 검증하세요.**
> 2일차 응답이 그럴듯하다고 성공이 아닙니다. 모델이 `search_memory` 를 **안 부르고** 일반 상식으로 답해도 채식 메뉴는 나옵니다. 실제로 기억을 읽었는지는 메시지에서 확인하세요.
> ```ts
> const calledSearch = day2.messages.some(
>   (m) => m.getType() === "tool" && String(m.name) === "search_memory",
> );
> ```
> 안 불렀다면 도구 `description` 과 `systemPrompt` 를 더 강하게 쓰거나, 검색을 모델 판단에 맡기지 말고 **미들웨어에서 매 턴 자동으로 주입**하세요([Step 12](../step-12-middleware-custom/)). 기억을 반드시 반영해야 하는 제품이라면 후자가 안전합니다.

---

## 정리

| 개념 | 요점 |
|---|---|
| checkpointer | 스레드 **안**. 대화 기록. 자동. `MemorySaver` / `PostgresSaver` |
| store | 스레드를 **넘어**. 사용자 사실. **내가 직접 `put`**. `InMemoryStore` / `PostgresStore` |
| namespace | `string[]` 계층 경로. **`[userId, ...]` 로 시작할 것**. 빈 배열 = 전체 |
| key | 네임스페이스 안 고유값. `(namespace, key)` = 기본키 → **upsert** |
| value | JSON 객체. `Item` = `value`/`key`/`namespace`/`createdAt`/`updatedAt` |
| `put` | `put(ns, key, value, index?: false \| string[])` — 4번째는 **배열**(객체 아님) |
| `search` | `search(nsPrefix, { filter?, limit?, offset?, query? })` → `SearchItem[]` (`score?`) |
| 시맨틱 검색 | `new InMemoryStore({ index: { embeddings, dims, fields } })` 를 **설정해야** 켜짐 |
| 메모리 유형 | 의미(덮어쓰기·항상 주입) / 일화(append·검색해서) / 절차(덮어쓰기·항상 주입) |
| 도구에서 접근 | `runtime.store` — **`as unknown as BaseStore` 캐스팅 필요** |

**핵심 함정 3가지**

1. **네임스페이스에 `userId` 를 안 넣으면 사용자 간 기억이 섞인다.** 에러도 안 나고 테스트에서도 안 잡힌다. 두 번째 사용자가 들어오는 순간 개인정보 유출 사고가 된다. `[userId, "memories"]` — 경로로 격리하고, `filter` 에 의존하지 마라.
2. **인덱스 설정 없이 `query` 를 주면 시맨틱 검색이 조용히 퇴화한다.** 에러 없이 "그냥 목록"이 온다. `store.indexConfig` 와 `item.score` 가 `undefined` 인지로만 판별할 수 있다.
3. **store 와 checkpointer 는 별개다 — 둘 다 필요하다.** store 만 붙이면 대화가 안 이어지고, checkpointer 만 붙이면 스레드가 바뀔 때 사용자를 잊는다.

**그 다음으로 위험한 것들**: `InMemoryStore` 는 프로세스와 함께 죽고 값을 복사하지 않는다(Postgres 와 동작이 다르다) / 메모리를 UUID 키로 무한정 append 하면 모순된 사실이 함께 검색되어 품질이 무너진다 / 모델이 잘못 박은 사실은 영구히 틀린다(메모리 오염) / `runtime.store` 의 TS 타입이 langgraph 의 `BaseStore` 가 아니다.

---

## 연습문제

1. `InMemoryStore` 에 네임스페이스 `["user_1", "memories"]` 로 의미·일화·절차 기억을 1건씩 저장하고, `search` 로 전체를 읽어 key 목록을 출력하세요. 그다음 `filter` 로 `kind` 가 `"semantic"` 인 것만 골라내세요.
2. 같은 `key` 에 다른 `value` 를 `put` 하세요. `search` 결과 개수가 늘어납니까? `get` 으로 읽은 `Item` 의 `createdAt` 과 `updatedAt` 을 함께 출력하고, **같은 key 로 다시 put 하면 행이 늘어나는지 덮어써지는지** 주석으로 답하세요.
3. `user_1` 과 `user_2` 각각의 네임스페이스에 기억을 1건씩 저장한 뒤, `search(["user_1", "memories"])` 와 `search([])` 의 결과를 비교하세요. **(B) 가 왜 위험한지** 주석으로 설명하세요.
4. `index` 설정이 **없는** store 에 기억 2건을 넣고 `search(ns, { query: "아무 질문" })` 을 호출하세요. 에러가 납니까? `score` 는? `store.indexConfig` 는? 셋을 출력하고 **"시맨틱 검색이 동작 중인지" 판별하는 방법**을 한 줄로 적으세요.
5. `runtime.store` 로 `[userId, "notes"]` 에 저장하는 도구 `save_note` 를 만들고, `FakeToolCallingModel` 로 강제 호출시켜 store 에 실제로 남는지 확인하세요. (힌트: `runtime.store` 의 타입은 langgraph 의 `BaseStore` 가 아닙니다)
6. 함수 `saveDedup(store, userId, content)` 를 작성하세요 — 기존 항목을 `search` 해서 `content` 가 같으면 그 key 를 재사용해 덮어쓰고, 없으면 새 UUID 로 저장합니다. 같은 content 로 **3번 호출해 항목 수가 1로 유지되는지** 확인하세요.
7. checkpointer 와 store 를 모두 붙인 에이전트를 만들고 서로 다른 `thread_id` 두 개로 호출하세요. 스레드 B 의 `messages` 길이와 store 에 남은 기억을 비교하고, **store 만 붙이고 checkpointer 를 빼면 무엇이 깨지는지** 주석으로 답하세요.
8. `save_memory` / `search_memory` 를 가진 개인 비서를 만들어 서로 다른 스레드에서 (1) 선호를 알려주고 (2) 추천을 받아 보세요. 네임스페이스에 `userId` 를 넣고, systemPrompt 에 "기억에 없는 것을 지어내지 마라"를 넣으세요. 그다음 **모델이 `search_memory` 를 실제로 호출했는지** 메시지에서 확인하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 16 — 검색과 RAG](../step-16-retrieval-rag/)

이 스텝에서 임베딩 인덱스로 "사용자에 대한 기억"을 검색했다면, 다음은 같은 벡터 검색 기법을 **문서**에 적용합니다. Store 의 시맨틱 검색과 RAG 의 리트리버는 원리가 같지만, 무엇을 색인하고 언제 갱신하는지가 다릅니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(15-1 ~ 15-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 푼 뒤, `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `docs/reference/langchain/project` 의 의존성을 씁니다. 실행은 프로젝트 루트에서:

```bash
npx tsx docs/reference/langchain/step-15-long-term-memory/practice.ts
```

**API 키 없이도 대부분 돌아갑니다.** Store 조작 자체는 네트워크가 필요 없고, 에이전트 예제는 `FakeToolCallingModel` 로 도구 호출을 결정적으로 재현하기 때문입니다. `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` 가 있으면 15-6(시맨틱 검색)과 15-9(개인 비서)까지 실제로 돕니다. 없으면 그 두 블록만 건너뛰고 나머지는 정상 출력됩니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[15-1] ~ [15-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[15-1]` 은 **에이전트를 스레드마다 따로 만듭니다.** `FakeToolCallingModel` 이 호출될 때마다 `toolCalls` 배열을 순서대로 소비하기 때문에, 하나의 인스턴스를 두 스레드에서 재사용하면 스레드 B 가 의도치 않게 저장 도구를 또 부릅니다. 결정적 재현을 위한 장치입니다.
- `[15-3]` 의 후반부가 이 파일에서 가장 놓치기 쉬운 대목입니다. `get` 이 돌려준 `item.value` 를 **직접 수정**한 뒤 재조회하면 `{"content":"몰래 바꿈"}` 이 나옵니다 — `put` 을 부른 적이 없는데도요. `get 이 같은 객체를 주는가 → true` 가 그 증거입니다. 본문 15-3 의 함정 블록과 짝지어 읽으세요.
- `[15-4]` 의 출력 `constructor=AsyncBatchedStore instanceof InMemoryStore=false` 가 핵심입니다. 도구가 보는 store 는 우리가 넘긴 객체가 아니라 래퍼라는 것, 그런데도 쓰기는 바깥 store 에 정확히 도달한다는 것을 한 화면에서 보여줍니다.
- `[15-6]` 은 **함정을 먼저** 보여주고 정답을 나중에 보여주는 순서입니다. 인덱스 없는 store 에 `query` 를 던져 `score=undefined` 를 확인한 뒤, 키가 있을 때만 진짜 임베딩 검색으로 넘어갑니다. `OPENAI_API_KEY` 가 없으면 뒷부분은 건너뜁니다.
- `[15-8]` 은 실행 가능한 코드가 아니라 **주석**입니다. 이 실습 프로젝트에 `@langchain/langgraph-checkpoint-postgres` 가 없기 때문입니다. `PostgresStore` 의 import 경로(`/store` 서브패스)와 `setup()` 필수라는 점만 확인하고 넘어가세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 블록이 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 통째로 실행해 검증하면 됩니다.

- 문제 1~6 은 **API 키 없이** 풀 수 있습니다. 문제 7~8 만 모델 호출이 필요하고, 그마저도 `FakeToolCallingModel` 로 대체할 수 있습니다.
- `[문제 2]`, `[문제 3]`, `[문제 4]`, `[문제 7]` 은 코드뿐 아니라 **주석으로 답을 적는 것**이 문제의 일부입니다. `→ (여기에 답)` 자리를 비워두지 마세요. 특히 문제 3 의 "왜 위험한가"는 이 스텝 전체에서 가장 중요한 질문입니다.
- `[문제 5]` 의 힌트 `runtime.store 의 TS 타입은 langgraph 의 BaseStore 가 아닙니다` 를 무시하고 `runtime.store.put(...)` 을 그냥 쓰면 `tsc` 가 `Property 'put' does not exist` 를 냅니다. **에러를 직접 한 번 보는 것**이 이 문제의 목적입니다.
- 파일을 그대로 실행하면 섹션 제목만 출력되고 내용은 비어 있습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 아래 해설에 "왜 이렇게 하는가"와 "이렇게 안 하면 무엇이 조용히 깨지는가"가 적혀 있습니다.

- `[정답 2]` 가 이 파일에서 가장 밀도 높은 대목입니다. 출력에 **"이전 값(복사해 둔 것)"과 "이전 값(참조를 그대로 들고 있던 것)"이 나란히** 찍히는데, 값이 서로 다릅니다. 후자는 나중의 `put` 때문에 **소급해서 바뀐** 것입니다. `structuredClone` 이 왜 필요한지, 그리고 이 동작이 `PostgresStore` 에서는 왜 달라지는지가 해설에 있습니다.
- `[정답 3]` 의 `search([])` 출력은 `user_1/memories:user_1 의 비밀 | user_2/memories:user_2 의 비밀` 입니다. 두 사용자의 기억이 한 배열에 담겨 나오는 것을 **눈으로 보는 것**이 목적입니다. 해설은 왜 `value` 에 `userId` 를 넣고 `filter` 로 거르는 방식이 위험한지까지 다룹니다.
- `[정답 4]` 의 답은 "**`store.indexConfig` 가 정의되어 있는지로 판별한다**" 입니다. 결과 쪽에서는 `item.score` 가 `undefined` 인지로도 확인할 수 있습니다. 부팅 시 `assert` 한 줄을 권하는 이유가 해설에 있습니다.
- `[정답 6]` 은 정답 코드가 문자열 **완전일치**로 중복을 잡지만, 해설에서 그것으로 **충분하지 않다**고 명시합니다. "매운 거 못 먹음"과 "맵찔이임"은 같은 사실인데 완전일치로는 안 잡힙니다. 실무의 세 가지 대안(고정 키 / 시맨틱 검색 후 모델이 판단 / 주기적 병합 배치)이 함께 적혀 있습니다.
- `[정답 8]` 의 마지막 줄 `search_memory 를 실제로 호출했는가` 가 이 파일의 마무리입니다. 응답이 그럴듯한 것과 기억을 실제로 읽은 것은 **다른 문제**라는 점을 코드로 확인시킵니다.

```ts file="./solution.ts"
```
