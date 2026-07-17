# Step 05 — 백엔드와 권한

> **학습 목표**
> - **백엔드(backend)** 가 파일 도구의 실제 구현체라는 것을 이해하고, 도구는 그대로 둔 채 저장소만 바꾼다
> - `StateBackend` / `FilesystemBackend` / `StoreBackend` / `CompositeBackend` / 샌드박스 백엔드를 **상황에 맞게 고른다**
> - `FilesystemBackend` 의 `rootDir` 와 `virtualMode` 로 **로컬 디스크 노출 범위를 잠근다**
> - `CompositeBackend` 로 `/memories` 는 영속 Store, `/workspace` 는 디스크로 **경로 라우팅**한다
> - `permissions` 규칙(`operations`/`paths`/`mode`)을 **첫 매칭 승리 + 기본 allow** 규칙에 맞게 쌓는다
> - `execute` 도구(셸)를 주는 것의 위험을 알고, 언제 필요하고 어떻게 가두는지 판단한다
> - `BackendProtocolV2` 를 직접 구현해 **커스텀 백엔드**를 만든다
>
> **선행 스텝**: [Step 04 — 가상 파일시스템](../step-04-filesystem/)
> **예상 소요**: 80분

[Step 04](../step-04-filesystem/) 에서 우리는 Deep Agent 에게 `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep` 이라는 파일 도구가 기본으로 달려 있다는 것을 배웠습니다. 그때 그 파일들은 전부 **메모리 안**에 있었습니다. 프로그램이 끝나면 사라졌죠.

이번 스텝의 질문은 하나입니다. **"그 파일들은 실제로 어디에 저장되는가?"** 답은 **백엔드(backend)** 입니다. 그리고 이 질문이 중요한 이유는, 백엔드를 바꾸는 순간 `write_file` 이 "메모리에 쓰기"에서 "당신의 노트북 디스크에 쓰기"로 조용히 바뀌기 때문입니다. 도구 이름도, 모델이 보는 설명도, 코드 한 줄도 그대로인데 말입니다. 그래서 백엔드는 Deep Agents 에서 **가장 조용하게 위험한 설정**입니다. 이 스텝은 그 설정을 정확히 이해하고, `permissions` 로 잠그는 법까지 다룹니다.

---

## 5-1. 백엔드 = 파일 도구의 실제 구현체

Deep Agent 의 파일 도구는 **인터페이스**이고, 백엔드는 **구현체**입니다. 모델은 언제나 `read_file("/notes.md")` 라고 부릅니다. 그 호출이 메모리 Map 을 뒤질지, 디스크를 읽을지, S3 에 GET 을 날릴지는 백엔드가 정합니다.

```ts
import { createDeepAgent, StateBackend } from "deepagents";
import { FilesystemBackend } from "deepagents/node";

const MODEL = "anthropic:claude-sonnet-4-6";

// 두 에이전트는 도구 목록이 완전히 같습니다. 저장소만 다릅니다.
const stateAgent = createDeepAgent({ model: MODEL, backend: new StateBackend() });
const diskAgent = createDeepAgent({
  model: MODEL,
  backend: new FilesystemBackend({ rootDir: process.cwd(), virtualMode: true }),
});
```

**출력**
```
  createDeepAgent 가 Promise 인가?: false
  invoke 는 함수인가?: true
  diskAgent 도 동일한 형태인가?: true
```

두 에이전트에게 똑같이 `"/notes.md 에 회의록을 정리해줘"` 라고 시키면, 앞은 메모리에 쓰고 뒤는 **당신의 현재 디렉터리에 진짜 파일을 만듭니다.** 코드에서 다른 것은 `backend:` 한 줄뿐입니다.

> ⚠️ **함정**: `createDeepAgent` 는 **동기 함수입니다.** 위 출력의 `Promise 인가?: false` 가 그 증거입니다. `const agent = await createDeepAgent({...})` 라고 써도 JavaScript 에서 `await` 는 Promise 가 아닌 값을 그냥 통과시키므로 **에러 없이 잘 돌아갑니다.** 그래서 틀린 줄도 모르고 씁니다. 동작에는 지장이 없지만, "이 함수는 비동기구나" 라고 잘못 배우게 되고 `.then()` 을 붙이는 순간 깨집니다. `await` 없이 쓰세요. 반면 `agent.invoke()` 는 **진짜 비동기**이므로 반드시 `await` 가 필요합니다.

---

## 5-2. 백엔드 카탈로그 — 무엇을 언제 쓰나

먼저 전체 지도를 봅시다. 이 표가 이 스텝의 요약입니다.

| 백엔드 | 저장 위치 | 수명 | 위험도 | 언제 쓰나 |
|---|---|---|---|---|
| `StateBackend` | LangGraph 상태 (`state.files`) | **스레드 한정, 휘발성** | 낮음 | 기본값. 브라우저 데모, 임시 작업 |
| `FilesystemBackend` | 로컬 디스크 (`rootDir` 기준) | 영구 (진짜 파일) | **높음** | 코딩 에이전트, CLI 도구 |
| `StoreBackend` | LangGraph Store | **실행/스레드 간 영속** | 중간 | 장기 메모리, 사용자 선호 |
| `CompositeBackend` | 경로 접두사별로 위임 | 라우팅 대상에 따름 | 조합에 따름 | 실무 대부분 |
| `LocalShellBackend` | 로컬 디스크 + **셸** | 영구 | **매우 높음** | 로컬 개발, 격리된 컨테이너 안 |
| `LangSmithSandbox` 등 | 원격 샌드박스 + 셸 | 샌드박스 수명 | 중간(격리됨) | 프로덕션에서 셸이 필요할 때 |
| `ContextHubBackend` | LangSmith Hub 저장소 | 영속 | 중간 | 스킬/프롬프트 공유 |

핵심은 **이 전부가 같은 인터페이스(`BackendProtocolV2`)를 구현한다**는 것입니다. 그래서 서로 갈아끼울 수 있습니다.

```ts
const methods = ["ls", "read", "readRaw", "write", "edit", "glob", "grep"] as const;

const candidates: Array<[string, object]> = [
  ["StateBackend", new StateBackend()],
  ["FilesystemBackend", new FilesystemBackend({ rootDir: tmp, virtualMode: true })],
  ["StoreBackend", new StoreBackend({ store: new InMemoryStore(), namespace: () => ["demo"] })],
  ["CompositeBackend", new CompositeBackend(new StateBackend(), {})],
];

for (const [name, backend] of candidates) {
  const has = methods.every((m) => typeof (backend as Record<string, unknown>)[m] === "function");
  console.log(`${name}: 전부 구현? ${has}`);
}
```

**출력**
```
  StateBackend: BackendProtocolV2 메서드 전부 구현? true
  FilesystemBackend: BackendProtocolV2 메서드 전부 구현? true
  StoreBackend: BackendProtocolV2 메서드 전부 구현? true
  CompositeBackend: BackendProtocolV2 메서드 전부 구현? true
```

> 💡 **실무 팁 — 어디서 import 하나**: `deepagents` 패키지는 엔트리포인트가 3개입니다.
> - `deepagents` — 기본. Node 환경이면 여기서 전부 나옵니다.
> - `deepagents/browser` — **브라우저 안전**. `StateBackend`, `StoreBackend`, `CompositeBackend` 는 있지만 `FilesystemBackend` 와 `LocalShellBackend` 는 **아예 없습니다.** 브라우저 번들에 `node:fs` 가 딸려오는 사고를 막아줍니다.
> - `deepagents/node` — Node 전용임을 코드로 드러내고 싶을 때. `FilesystemBackend`, `LocalShellBackend` 가 여기 있습니다.
>
> 브라우저에서 도는 코드를 쓴다면 처음부터 `deepagents/browser` 만 import 하세요. 나중에 번들러 에러로 알게 되는 것보다 훨씬 낫습니다.

---

## 5-3. StateBackend — 기본값, 그리고 휘발성

`StateBackend` 는 파일을 LangGraph **상태**에 넣습니다. 상태는 그래프 실행 중에만 존재하는 객체입니다. 그러니 파일도 그만큼만 삽니다.

```ts
// 브라우저 번들에서는 이렇게 가져옵니다:
//   import { StateBackend, createDeepAgent } from "deepagents/browser";
const agent = createDeepAgent({ model: MODEL, backend: new StateBackend() });
```

`backend` 를 아예 안 주면 기본이 `StateBackend` 입니다. [Step 04](../step-04-filesystem/) 에서 우리가 쓴 게 바로 이것입니다.

**장점**: 디스크도 네트워크도 안 씁니다. 사고가 날 수 없습니다. 브라우저에서도 돕니다.
**단점**: 대화가 끝나면 다 사라집니다.

```ts
// ⚠️ StateBackend 를 직접 호출하면 터집니다.
const naked = new StateBackend();
try {
  naked.ls("/");
} catch (e) {
  console.log((e as Error).message);
}
```

**출력**
```
  직접 호출하면: Cannot read properties of undefined (reading 'configurable')
```

> ⚠️ **함정**: `new StateBackend()` 를 만들어 놓고 테스트 삼아 `backend.write("/a.txt", "hi")` 를 직접 불러보면 위처럼 **정체불명의 에러**가 납니다. "백엔드가 고장났나?" 싶지만 정상입니다. `StateBackend` 는 자기 상태를 스스로 갖고 있지 않고, `createDeepAgent` 가 **런타임(state)을 주입해줘야** 동작합니다. 단위 테스트에서 백엔드를 직접 두드리고 싶다면 `StateBackend` 말고 `StoreBackend`(`store` 를 직접 넘길 수 있음)나 `FilesystemBackend` 를 쓰세요. 이 둘은 독립적으로 동작합니다. 실제로 이 스텝의 `practice.ts` 도 그래서 대부분의 예제를 `StoreBackend` / `FilesystemBackend` 로 시연합니다.

> 💡 **실무 팁**: 상태에 들어간 파일은 체크포인터([Step 10](../../langchain/step-10-memory/) 에서 다룬 `MemorySaver` 같은)를 붙이면 **스레드 안에서는** 살아남습니다. 즉 같은 `thread_id` 로 다시 부르면 파일이 아직 있습니다. 하지만 다른 스레드에서는 안 보이고, `MemorySaver` 를 썼다면 프로세스 재시작 시 다 날아갑니다. "스레드를 넘어 기억" 이 필요하면 5-5 의 `StoreBackend` 로 가야 합니다.

---

## 5-4. FilesystemBackend — 로컬 디스크를 LLM 에게 준다

이제 진짜 이야기입니다. `FilesystemBackend` 를 꽂는다는 것은 **언어 모델에게 당신의 파일시스템 핸들을 넘긴다**는 뜻입니다. 모델이 `write_file` 을 부르면 진짜 파일이 생기고, `edit_file` 을 부르면 진짜 파일이 바뀝니다. 되돌리기 버튼은 없습니다.

그래서 옵션이 두 개 있습니다.

| 옵션 | 타입 | 의미 |
|---|---|---|
| `rootDir` | `string` | 에이전트가 볼 디렉터리 (절대경로 권장) |
| `virtualMode` | `boolean` | `true` 면 `rootDir` 가 곧 `/` 가 되고, 밖으로 못 나감 |
| `maxFileSizeMb` | `number` | 읽을 파일 크기 상한 |

**`rootDir` 는 격리가 아닙니다. 격리는 `virtualMode` 가 합니다.** 이게 이 절의 핵심입니다.

```ts
const sandboxDir = await fs.mkdtemp(path.join(os.tmpdir(), "da-5-4-"));
await fs.writeFile(path.join(sandboxDir, "readme.txt"), "이 파일은 rootDir 안에 있습니다.\n");

// (A) virtualMode: true — rootDir 가 곧 "/" 가 된다
const guarded = new FilesystemBackend({ rootDir: sandboxDir, virtualMode: true });
console.log(await guarded.ls("/"));
console.log(await guarded.read("/readme.txt"));
console.log(await guarded.read("/../../etc/passwd"));  // 탈출 시도
console.log(await guarded.read("/etc/hosts"));         // 절대경로 시도

// (B) virtualMode: false — "/" 가 진짜 디스크 루트다
const open = new FilesystemBackend({ rootDir: sandboxDir, virtualMode: false });
console.log(await open.read("/etc/hosts"));
```

**출력** (경로의 임시 디렉터리 이름은 실행마다 다릅니다)
```
  (A) ls('/'): {"files":[{"path":"/readme.txt","is_dir":false,"size":43,"modified_at":"…"}]}
  (A) read('/readme.txt'): {"content":"이 파일은 rootDir 안에 있습니다.\n","mimeType":"text/plain"}
  (A) read('/../../etc/passwd'): {"error":"Error reading file '/../../etc/passwd': Path traversal not allowed"}
  (A) read('/etc/hosts'): {"error":"Error reading file '/etc/hosts': ENOENT: no such file or directory, stat '/var/folders/…/da-5-4-WyZzvQ/etc/hosts'"}
  (B) read('/etc/hosts'): 실제 /etc/hosts 내용이 그대로 읽힘!
```

이 출력을 한 줄씩 읽어봅시다.

- `ls("/")` 가 `/readme.txt` 를 보여줍니다. 에이전트에게 `/` 는 `rootDir` 입니다. 실제 경로(`/var/folders/…`)는 **모델에게 아예 안 보입니다.**
- `/../../etc/passwd` → **차단**. `Path traversal not allowed` 로 즉시 거부됩니다.
- `/etc/hosts` → **차단이 아니라 재해석**. 에러 메시지를 보세요. `stat '/var/folders/…/da-5-4-WyZzvQ/etc/hosts'` — `rootDir` **안에서** `/etc/hosts` 를 찾다가 없어서 ENOENT 가 난 것입니다. 막은 게 아니라 "그 경로는 여기서는 이걸 뜻해" 라고 번역한 것입니다.
- `(B)` → **진짜 `/etc/hosts` 가 읽혔습니다.**

> ⚠️ **함정 (이 스텝에서 두 번째로 위험한 것)**: `rootDir` 를 지정했으니 그 안에 갇혔다고 생각하기 쉽습니다. **아닙니다.** `virtualMode` 를 안 주면 기본값은 격리가 아니고, `rootDir` 는 그저 "상대경로의 기준점(cwd)" 일 뿐입니다. 위 (B)가 정확히 그 상황입니다 — `rootDir` 를 임시 디렉터리로 줬는데도 `/etc/hosts` 가 그대로 읽혔습니다. 에러도 경고도 없습니다. 실무에서 이 실수는 `read_file("/Users/me/.ssh/id_rsa")` 나 `read_file("/Users/me/.aws/credentials")` 를 모델이 읽어서 그 내용이 프롬프트에 실려 API 로 나가는 것으로 이어집니다. **로컬 디스크를 붙일 거면 `virtualMode: true` 를 기본으로 삼으세요.**

> 💡 **실무 팁 — `rootDir` 를 좁게**: `virtualMode: true` 를 켰더라도 `rootDir: "/"` 나 `rootDir: os.homedir()` 로 주면 아무 의미가 없습니다. 감옥의 벽을 집 전체로 친 셈이니까요. 에이전트가 **실제로 건드려야 하는 최소 디렉터리**를 주세요. 코딩 에이전트라면 프로젝트 루트, 문서 정리 에이전트라면 그 문서 폴더. 그리고 그 안에서도 `.env` 같은 건 5-7 의 `permissions` 로 한 번 더 잠급니다. **rootDir(어디까지 보이나) → permissions(그 안에서 뭘 하나)** 2단 방어가 정석입니다.

---

## 5-5. StoreBackend — 실행 간 영속

`StateBackend` 는 스레드가 끝나면 사라집니다. 하지만 "이 사용자는 한국어를 선호한다" 같은 건 **다음 대화에서도 기억해야** 합니다. 그게 `StoreBackend` 입니다. LangGraph 의 `Store`(키-값 저장소)에 파일을 씁니다.

```ts
import { InMemoryStore } from "@langchain/langgraph";

const store = new InMemoryStore();

// namespace 는 "누구의 파일인가" 를 가르는 칸막이입니다.
const backend = new StoreBackend({ store, namespace: () => ["memories", "user-123"] });

await backend.write("/preference.md", "- 답변은 한국어로\n- 코드는 TypeScript 로\n");
console.log(await backend.ls("/"));
console.log(await backend.read("/preference.md"));

// 같은 store, 다른 namespace → 서로 안 보입니다.
const otherUser = new StoreBackend({ store, namespace: () => ["memories", "user-999"] });
console.log(await otherUser.ls("/"));

// 같은 namespace 로 새 백엔드를 만들면 → 그대로 남아 있습니다 (영속).
const reopened = new StoreBackend({ store, namespace: () => ["memories", "user-123"] });
console.log(await reopened.read("/preference.md"));
```

**출력**
```
  write 후 ls('/'): {"files":[{"path":"/preference.md","is_dir":false,"size":30,"modified_at":"…"}]}
  read('/preference.md'): {"content":"- 답변은 한국어로\n- 코드는 TypeScript 로\n","mimeType":"text/markdown"}
  다른 namespace 의 ls('/'): {"files":[]}
  같은 namespace 재접속 read: {"content":"- 답변은 한국어로\n- 코드는 TypeScript 로\n","mimeType":"text/markdown"}
```

`user-999` 의 `ls("/")` 가 **빈 배열**인 것에 주목하세요. 같은 `store` 인스턴스인데도 서로 안 보입니다. 이게 `namespace` 의 역할입니다.

실무에서는 `namespace` 를 하드코딩하지 않고 **런타임에서 뽑습니다**.

```ts
const agent = createDeepAgent({
  model: MODEL,
  store,
  backend: new StoreBackend({ namespace: () => ["memories"] }),
});
```

`namespace` 는 배열이나 팩토리 함수를 받습니다. 팩토리는 런타임 컨텍스트를 인자로 받으므로, 거기서 사용자 ID 를 꺼내 쓸 수 있습니다.

| `namespace` 패턴 | 의미 |
|---|---|
| `["memories"]` | 전역 공유 (모든 사용자가 같은 칸) |
| `(ctx) => ["memories", ctx.state.userId]` | 사용자별 격리 |
| `(ctx) => [ctx.assistantId ?? "default"]` | 어시스턴트별 격리 |

> ⚠️ **함정**: `namespace` 를 `() => ["memories"]` 처럼 **고정**해 두면 모든 사용자가 같은 칸을 씁니다. 개발할 때는 나 혼자 쓰니 아무 문제가 없다가, 배포하면 A 의 메모를 B 가 읽습니다. 에러가 안 나므로 사고가 나기 전까지 모릅니다. 멀티테넌트라면 **`namespace` 팩토리에서 반드시 사용자 식별자를 꺼내 쓰세요.** 그리고 그 식별자는 사용자가 조작할 수 없는 곳(인증된 세션)에서 와야 합니다 — 모델이 채우는 값이면 프롬프트 인젝션으로 남의 칸에 들어갑니다.

> ⚠️ **함정 2**: `InMemoryStore` 는 이름 그대로 **프로세스 메모리**입니다. `StoreBackend` 를 썼으니 영속이라고 안심했는데 서버를 재시작하면 다 날아갑니다. "스레드 간 영속"(`StateBackend` 보다 오래 삶)과 "프로세스 간 영속"(재시작 후에도 삶)은 다른 얘기입니다. 진짜 영속이 필요하면 DB 기반 Store 를 쓰세요.

---

## 5-6. CompositeBackend — 경로 접두사로 라우팅

실무에서는 대개 **한 종류로 안 끝납니다.** 작업 파일은 디스크에, 장기 기억은 Store 에, 나머지 임시 파일은 상태에 두고 싶습니다. `CompositeBackend` 가 이걸 **경로 접두사**로 해결합니다.

```ts
const backend = new CompositeBackend(
  // 1번 인자 = 기본 백엔드 (라우팅에 안 걸리는 모든 경로)
  new StoreBackend({ store, namespace: () => ["scratch"] }),
  // 2번 인자 = 접두사 → 백엔드 라우팅 표
  {
    "/memories/": new StoreBackend({ store, namespace: () => ["memories"] }),
    "/workspace/": new FilesystemBackend({ rootDir: workspaceDir, virtualMode: true }),
  },
);

await backend.write("/memories/user.md", "이름: 김개발");
await backend.write("/workspace/main.ts", "console.log('hi');");
await backend.write("/scratch.txt", "아무 접두사에도 안 걸림 → 기본 백엔드로");
```

**출력**
```
  routePrefixes: ["/memories/","/workspace/"]
  read /memories/user.md: {"content":"이름: 김개발","mimeType":"text/markdown"}
  read /workspace/main.ts: {"content":"console.log('hi');","mimeType":"text/plain"}
  read /scratch.txt: {"content":"아무 접두사에도 안 걸림 → 기본 백엔드로","mimeType":"text/plain"}
  실제 디스크 확인: ["main.ts"]
  라우팅 대상 백엔드가 보는 실제 키: {"files":[{"path":"/user.md","is_dir":false,"size":7,"modified_at":"…"}]}
  composite ls('/'): {"files":[{"path":"/memories/","is_dir":true,"size":0,"modified_at":""},{"path":"/scratch.txt",…},{"path":"/workspace/","is_dir":true,…}]}
```

세 가지를 확인했습니다.

1. **디스크 확인**: `["main.ts"]` — `/workspace/main.ts` 만 진짜 디스크에 떨어졌습니다. `/memories/user.md` 는 Store 에만 있습니다.
2. **`ls("/")` 는 합칩니다**: 라우팅된 접두사를 디렉터리(`is_dir: true`)처럼 보여주고, 기본 백엔드 결과와 나란히 놓습니다. 모델 눈에는 그냥 하나의 파일시스템입니다.
3. **접두사가 벗겨집니다**: 아래 함정을 보세요.

> ⚠️ **함정 (커스텀 백엔드를 조합할 때 반드시 밟는 것)**: 라우팅된 백엔드는 **접두사가 벗겨진 경로**를 받습니다. 위 출력의 `라우팅 대상 백엔드가 보는 실제 키` 를 보세요. 에이전트는 `/memories/user.md` 라고 불렀지만, 그 `StoreBackend` 에는 **`/user.md`** 로 저장돼 있습니다. `/memories/` 부분은 `CompositeBackend` 가 떼어내고 넘긴 것이고, 결과를 돌려줄 때 다시 붙입니다. 이걸 모르고 커스텀 백엔드 안에서 `if (path.startsWith("/memories/"))` 같은 걸 쓰면 **영원히 false** 입니다. 에러는 안 나고 그냥 조건이 안 걸립니다. 5-9 에서 커스텀 백엔드를 만들 때 이 점을 꼭 기억하세요.

> 💡 **실무 팁 — 기본 백엔드는 신중하게**: `CompositeBackend` 의 1번 인자(기본 백엔드)는 라우팅에 안 걸리는 **모든** 경로를 받습니다. 그런데 Deep Agent 는 내부적으로도 파일시스템을 씁니다 — 큰 도구 결과를 파일로 오프로딩하거나(컨텍스트 절약), 대화 기록을 저장할 때요. 그 내부 파일들이 전부 기본 백엔드로 갑니다. 그러니 기본 백엔드를 `FilesystemBackend` 로 두면 **에이전트 내부 부산물이 당신의 디스크에 쌓입니다.** 기본은 `StateBackend`(휘발성)로 두고, 영속이 필요한 경로만 명시적으로 라우팅하는 게 정석입니다.

> 💡 **실무 팁 — 접두사는 긴 것이 이깁니다**: `{"/a/": X, "/a/b/": Y}` 에서 `/a/b/c.txt` 는 `Y` 로 갑니다. 더 구체적인 규칙이 우선입니다. 그리고 접두사는 **슬래시로 끝내세요**(`"/memories/"`). `"/memories"` 로 쓰면 `/memories-backup.txt` 같은 게 딸려 들어옵니다.

---

## 5-7. permissions — 그 안에서 뭘 할 수 있나

백엔드가 "어디까지 보이나" 를 정했다면, `permissions` 는 "그 안에서 뭘 할 수 있나" 를 정합니다. 규칙은 필드 3개짜리 객체입니다.

| 필드 | 타입 | 값 |
|---|---|---|
| `operations` | `("read" \| "write")[]` | `"read"` 는 `ls`/`read_file`/`glob`/`grep` 을, `"write"` 는 `write_file`/`edit_file` 을 덮습니다 |
| `paths` | `string[]` | **절대** glob 패턴. `/` 로 시작해야 하고 `..` 나 `~` 를 못 씁니다. `**`(임의 깊이), `*`(한 세그먼트), `{a,b}`(택일) 지원 |
| `mode` | `"allow" \| "deny"` | 생략하면 `"allow"`. **이 둘뿐입니다 — `"ask"` 는 없습니다** |

그리고 평가 규칙은 딱 두 줄입니다.

> **1. 첫 매칭 규칙이 이긴다 (first-match-wins).**
> **2. 아무 규칙에도 안 걸리면 허용된다 (permissive default).**

```ts
// (A) 읽기 전용 에이전트: 모든 쓰기 금지
const readOnly: FilesystemPermission[] = [
  { operations: ["write"], paths: ["/**"], mode: "deny" },
];

// (B) 작업공간 격리: /workspace 안에서만 뭐든 하고, 나머지는 전부 금지
const workspaceOnly: FilesystemPermission[] = [
  { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
  { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
];

// (C) 특정 파일 보호: .env 는 못 건드리되 나머지 workspace 는 허용
const protectEnv: FilesystemPermission[] = [
  { operations: ["read", "write"], paths: ["/workspace/.env", "/workspace/secrets/**"], mode: "deny" },
  { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
  { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
];

const agent = createDeepAgent({ model: MODEL, backend: new StateBackend(), permissions: protectEnv });
```

`protectEnv` 규칙을 경로별로 평가해 보면 구조가 눈에 들어옵니다.

**출력**
```
  --- protectEnv 규칙을 손으로 평가 ---
  /workspace/main.ts: allow (paths=["/workspace/**"])
  /workspace/.env: deny (paths=["/workspace/.env","/workspace/secrets/**"])
  /etc/passwd: deny (paths=["/**"])
  /workspace/secrets/key.pem: deny (paths=["/workspace/.env","/workspace/secrets/**"])
```

세 규칙이 **샌드위치 구조**를 이룹니다. 좁은 `deny`(구멍 막기) → 중간 `allow`(작업 영역) → 넓은 `deny`(나머지 전부 차단). 방화벽 규칙과 똑같은 사고방식입니다.

거부되면 모델은 이런 `ToolMessage` 를 받습니다.

```
Error: permission denied for write on /workspace/.env
```

> ⚠️ **함정 1 — 순서가 전부다**: 아래 두 배열은 **규칙이 같고 순서만 다릅니다.**
> ```ts
> const good = [
>   { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
>   { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
> ];
> const bad = [
>   { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },  // ← 먼저 매칭됨
>   { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" }, // ← 평가조차 안 됨
> ];
> ```
> `bad` 로 `/workspace/.env` 에 쓰면 **allow** 가 나옵니다. `.env` 가 그대로 뚫립니다. 에러도, 경고도, 로그도 없습니다. 첫 매칭에서 끝나므로 뒤의 `deny` 는 죽은 코드입니다. **좁은 규칙을 위로.**

> ⚠️ **함정 2 — 기본이 allow 다**: 아래 규칙은 "workspace 만 허용" 처럼 읽힙니다.
> ```ts
> const leaky = [{ operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" }];
> ```
> 하지만 `/etc/passwd` 쓰기는 **허용됩니다.** 아무 규칙에도 안 걸리니까요. `allow` 규칙을 쓴다고 나머지가 자동으로 막히지 않습니다. **화이트리스트를 원하면 맨 끝에 `{ paths: ["/**"], mode: "deny" }` 를 반드시 깔아야 합니다.**

> ⚠️ **함정 3 — read 거부는 에러가 아니라 "안 보임"이다**: `write` 를 거부당하면 모델은 위의 `permission denied` 에러를 받습니다. 하지만 `ls`/`glob`/`grep` 에서 `read` 가 거부된 항목은 **에러 없이 결과에서 조용히 빠집니다**(필터링). 모델은 그 파일이 존재하는지조차 모릅니다. 보안상으로는 이게 옳지만(존재 자체를 숨김), 디버깅할 때는 "왜 내 파일이 `ls` 에 안 나오지?" 하고 백엔드를 의심하게 됩니다. **`permissions` 를 먼저 확인하세요.**

> 💡 **실무 팁**: `permissions` 는 백엔드가 아니라 **에이전트/서브에이전트** 수준의 설정입니다. 그래서 백엔드를 `StateBackend` 에서 `FilesystemBackend` 로 갈아끼워도 규칙이 그대로 따라옵니다. 또 서브에이전트마다 다른 규칙을 줄 수 있습니다([Step 06](../step-06-subagents/) 에서 다룹니다) — 단, 서브에이전트의 `permissions` 는 부모 것을 **병합이 아니라 완전 교체**합니다.

---

## 5-8. 샌드박스와 `execute` — 셸을 준다는 것

지금까지의 도구는 전부 "파일 하나를 읽고 쓴다" 수준이었습니다. `execute` 는 다릅니다. **임의의 셸 명령**을 실행합니다. `npm test` 도, `git commit` 도, `rm -rf /` 도요.

`execute` 도구는 **샌드박스 백엔드를 꽂았을 때만** 생깁니다. 가장 간단한 것이 `LocalShellBackend` 입니다.

```ts
import { LocalShellBackend } from "deepagents/node";

const shell = await LocalShellBackend.create({
  rootDir: dir,
  virtualMode: true,
  timeout: 10,          // 초
  maxOutputBytes: 100_000,
});

console.log(await shell.execute("echo hello"));
console.log(await shell.execute("exit 3"));
await shell.close();
```

`execute` 의 반환은 **구조가 결정적**입니다.

```ts
interface ExecuteResponse {
  output: string;         // stdout + stderr 합본 (+ 종료코드 안내)
  exitCode: number | null;
  truncated: boolean;     // 출력이 잘렸는가
}
```

**출력**
```
  execute('echo hello'): {"output":"hello\n","exitCode":0,"truncated":false}
  execute('exit 3'): {"output":"<no output>\n\nExit code: 3","exitCode":3,"truncated":false}
  execute('node -v'): {"output":"[stderr] /bin/sh: node: command not found\n\nExit code: 127","exitCode":127,"truncated":false}
```

출력이 없으면 `<no output>`, stderr 는 `[stderr]` 로 표시되고, 0 이 아닌 종료코드는 `Exit code: N` 이 붙습니다. 모델이 실패를 읽고 스스로 고칠 수 있게 하기 위한 형식입니다.

세 번째 줄을 보세요. `node -v` 가 **`command not found`** 입니다. `inheritEnv` 기본값이 `false` 라서 `PATH` 가 최소한으로만 잡혀 있기 때문입니다.

### 그리고 진짜 함정

```ts
const shell = await LocalShellBackend.create({ rootDir: dir, virtualMode: true, timeout: 10 });

// (a) 파일 도구로 rootDir 밖 읽기
console.log(await shell.read("/etc/hosts"));

// (b) 셸로 rootDir 밖 읽기
console.log(await shell.execute("cat /etc/hosts | head -2"));
```

**출력**
```
  read('/etc/hosts'): {"error":"File '/etc/hosts' not found"}
  execute('cat /etc/hosts'): ## ⏎ # Host Database ⏎
```

> ⚠️ **함정 (이 스텝에서 가장 위험한 것)**: **`virtualMode: true` 인데도 셸은 `rootDir` 밖으로 나갑니다.** 같은 백엔드, 같은 설정, 같은 경로인데 파일 도구는 막히고 셸은 뚫립니다.
>
> 이유는 단순합니다. `virtualMode` 는 **백엔드의 파일 메서드가 경로를 어떻게 해석하는가**만 바꾸는 설정입니다. `execute` 는 그 경로 해석기를 거치지 않고 문자열을 그대로 `/bin/sh` 에 넘깁니다. 셸에게 `rootDir` 는 그저 시작 `cwd` 일 뿐, 감옥이 아닙니다. `cd /` 한 줄이면 끝입니다.
>
> 그래서 `LocalShellBackend` 를 쓰면서 "`virtualMode` 켰으니 안전하지" 라고 믿으면, 에이전트는 `~/.ssh/id_rsa` 도 `~/.aws/credentials` 도 읽을 수 있습니다. 문서에도 이렇게 쓰여 있습니다 — *"The command runs with your user's full permissions."* **`LocalShellBackend` 의 "Local" 은 "격리 없음" 으로 읽으세요.**

### 그럼 어떻게 하나

셸이 필요하다고 느낄 때, 이 순서로 자문하세요.

1. **셸이 정말 필요한가?** 대개는 아닙니다. "숫자를 계산해줘", "JSON 을 변환해줘" 라면 **인터프리터**로 충분합니다. `@langchain/quickjs` 의 `createCodeInterpreterMiddleware()` 는 `eval` 도구를 추가하는데, QuickJS **WASM 안**에서 돕니다. 파일시스템도, 네트워크도, 셸도, 시계조차 없습니다. 호스트 Node 프로세스가 아닙니다.

   ```ts
   import { createCodeInterpreterMiddleware } from "@langchain/quickjs";

   const agent = createDeepAgent({
     model: MODEL,
     middleware: [createCodeInterpreterMiddleware()],
   });
   ```

2. **진짜 셸이 필요하다면, 프로세스 밖으로 내보내라.** 원격 샌드박스를 쓰면 명령이 당신의 머신이 아니라 일회용 격리 환경에서 돕니다.

   ```ts
   import { createDeepAgent, LangSmithSandbox } from "deepagents";
   import { SandboxClient } from "langsmith/sandbox";

   const sandbox = await new SandboxClient().createSandbox();
   const agent = createDeepAgent({ model: MODEL, backend: new LangSmithSandbox({ sandbox }) });
   ```

   LangSmith 외에 Deno, Daytona, Modal 등도 지원됩니다.

3. **그래도 `LocalShellBackend` 를 쓴다면, 그 프로세스 자체를 가둬라.** 컨테이너 안에서 돌리세요. 백엔드 설정이 아니라 **컨테이너 경계**가 진짜 방어선입니다.

> 💡 **실무 팁**: `execute` 출력이 너무 크면 자동으로 파일에 저장되고, 모델은 `read_file` 로 조금씩 읽으라는 안내를 받습니다(`truncated: true`). 그래서 `maxOutputBytes` 를 너무 작게 잡으면 매 명령마다 파일 왕복이 생겨 느려지고 토큰도 더 씁니다. 기본값 근처(10만 바이트)에서 시작하세요. `timeout` 은 초 단위이며, 무한 루프에 빠진 명령을 끊어주는 유일한 안전장치이니 반드시 설정하세요.

---

## 5-9. 커스텀 백엔드 만들기 — BackendProtocol 구현

내장 백엔드로 안 되면 직접 만듭니다. **메서드 7개**만 구현하면 됩니다.

```ts
interface BackendProtocolV2 {
  ls(path: string): MaybePromise<LsResult>;
  read(filePath: string, offset?: number, limit?: number): MaybePromise<ReadResult>;
  readRaw(filePath: string): MaybePromise<ReadRawResult>;
  grep(pattern: string, path?: string | null, glob?: string | null): MaybePromise<GrepResult>;
  glob(pattern: string, path?: string): MaybePromise<GlobResult>;
  write(filePath: string, content: string): MaybePromise<WriteResult>;
  edit(filePath: string, oldString: string, newString: string, replaceAll?: boolean): MaybePromise<EditResult>;
  delete?(filePath: string): MaybePromise<DeleteResult>;   // 선택
}
```

`MaybePromise` 이므로 **동기든 비동기든 상관없습니다.** 반환 타입도 단순합니다.

| 타입 | 성공 필드 | 실패 |
|---|---|---|
| `LsResult` | `files?: FileInfo[]` | `error?: string` |
| `ReadResult` | `content?: string \| Uint8Array`, `mimeType?: string` | `error?: string` |
| `WriteResult` | `path?: string` | `error?: string` |
| `EditResult` | `path?: string`, `occurrences?: number` | `error?: string` |
| `GlobResult` | `files?: FileInfo[]` | `error?: string` |
| `GrepResult` | `matches?: GrepMatch[]` | `error?: string` |

`FileInfo` 는 `{ path, is_dir?, size?, modified_at? }`, `GrepMatch` 는 `{ path, line, text }` 입니다.

S3 를 흉내낸 인메모리 목을 만들어 봅시다.

```ts
class MockS3Backend implements BackendProtocolV2 {
  private objects = new Map<string, string>();
  public readonly log: string[] = [];

  constructor(private bucket: string) {}

  read(filePath: string): ReadResult {
    this.log.push(`read ${filePath}`);
    const body = this.objects.get(filePath);
    // ⚠️ 없는 파일은 throw 가 아니라 { error } 로 돌려줍니다.
    if (body === undefined) return { error: `File '${filePath}' not found in s3://${this.bucket}` };
    return { content: body, mimeType: "text/plain" };
  }

  write(filePath: string, content: string): WriteResult {
    this.log.push(`write ${filePath}`);
    this.objects.set(filePath, content);
    return { path: filePath };
  }

  // ls / readRaw / edit / glob / grep 도 같은 식으로 구현 (practice.ts 참고)
}
```

**출력**
```
  read: {"content":"# 리포트\n매출: 100\n비용: 40\n","mimeType":"text/plain"}
  없는 파일 read: {"error":"File '/nope.md' not found in s3://my-agent-bucket"}
  edit: {"path":"/report.md","occurrences":1}
  edit 후 read: {"content":"# 리포트\n매출: 120\n비용: 40\n","mimeType":"text/plain"}
  grep '매출': {"matches":[{"path":"/report.md","line":2,"text":"매출: 120"}]}
  백엔드가 받은 호출 로그: ["write /report.md","read /report.md","read /nope.md","edit /report.md","read /report.md","grep 매출","ls /"]
  커스텀 백엔드 에이전트 생성: true
```

만들었으면 그냥 꽂습니다. 조합도 됩니다.

```ts
const agent = createDeepAgent({ model: MODEL, backend: new MockS3Backend("my-bucket") });

const composed = new CompositeBackend(new StateBackend(), {
  "/s3/": new MockS3Backend("bucket-2"),
});
```

> ⚠️ **함정 — `throw` 하지 말고 `{ error }` 를 반환하라**: 커스텀 백엔드에서 파일이 없을 때 `throw new Error("not found")` 를 하고 싶어집니다. 하지만 그러면 **도구 호출 자체가 터지고 에이전트 루프가 죽습니다.** `{ error: "..." }` 로 돌려주면 그 문자열이 `ToolMessage` 로 모델에게 전달되고, 모델은 "아 없구나, `ls` 로 찾아보자" 하고 **스스로 복구합니다.** 이게 Deep Agent 가 견고하게 도는 이유입니다. 에러 문자열은 모델이 읽는 프롬프트라고 생각하고 쓰세요 — `"error"` 보다 `"File '/nope.md' not found in s3://my-bucket"` 이 훨씬 낫습니다.

> ⚠️ **함정 2 — CompositeBackend 안에서는 경로가 다르다**: 5-6 에서 본 그것입니다. `"/s3/"` 로 라우팅하면 커스텀 백엔드는 `/s3/report.md` 가 아니라 **`/report.md`** 를 받습니다. 커스텀 백엔드는 자기가 어떤 접두사 아래 마운트됐는지 **알 수도 없고 알 필요도 없습니다.** 항상 `/` 부터 시작하는 자기만의 세계라고 생각하고 구현하세요.

> 💡 **실무 팁 — 커스텀 백엔드 vs permissions**: "쓰기를 막고 싶다" 정도라면 커스텀 백엔드를 만들지 말고 `permissions` 를 쓰세요. 선언적이고, 서브에이전트별로 갈아끼울 수 있고, 백엔드를 교체해도 규칙이 따라옵니다.
> 커스텀 백엔드가 필요한 건 **경로 규칙으로 표현할 수 없는 정책**일 때입니다 — "S3 에 저장", "쓰기 전에 감사 로그", "하루 100회 제한", "쓰기 시 자동 백업". 정리하면 **`permissions` = 어디에(where), 커스텀 백엔드 = 어떻게(how)** 입니다.

---

## 5-10. 종합 — 라우팅 + 권한을 함께

실무에서 쓰는 형태를 조립해 봅시다. `/memories` 는 영속, `/workspace` 는 디스크, 나머지는 상태. 그리고 `.env` 는 권한으로 잠급니다.

```ts
const agent = createDeepAgent({
  model: MODEL,
  store,
  backend: new CompositeBackend(new StateBackend(), {
    "/memories/": new StoreBackend({ store, namespace: () => ["memories"] }),
    "/workspace/": new FilesystemBackend({ rootDir: workspaceDir, virtualMode: true }),
  }),
  permissions: [
    // deny 를 먼저 — 순서가 뒤집히면 .env 가 그대로 뚫립니다.
    { operations: ["read", "write"], paths: ["/workspace/.env"], mode: "deny" },
    { operations: ["read", "write"], paths: ["/workspace/**", "/memories/**"], mode: "allow" },
    { operations: ["write"], paths: ["/**"], mode: "deny" },
  ],
  systemPrompt:
    "너는 파일을 다루는 조수다. 작업 파일은 /workspace 에, 오래 기억할 것은 /memories 에 둔다.",
});

const result = await agent.invoke({
  messages: [{
    role: "user",
    content:
      "/workspace/notes.md 를 읽고 한 줄 요약해서 /memories/summary.md 에 저장해줘. " +
      "그리고 /workspace/.env 도 읽어보고, 읽히는지 안 읽히는지 알려줘.",
  }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
  --- 모델 최종 답변 ---
  /workspace/notes.md 를 읽고 요약해서 /memories/summary.md 에 저장했습니다.
  요약: "백엔드는 CompositeBackend 로 간다"

  /workspace/.env 는 읽을 수 없었습니다. 다음 에러가 반환되었습니다:
    Error: permission denied for read on /workspace/.env
```

이 한 번의 실행에서 네 가지가 동시에 일어났습니다.

1. `/workspace/notes.md` 읽기 → `FilesystemBackend` 로 라우팅 → 진짜 디스크에서 읽음
2. `/memories/summary.md` 쓰기 → `StoreBackend` 로 라우팅 → Store 에 영속
3. `/workspace/.env` 읽기 → `permissions` 첫 규칙에 걸려 거부
4. 모델은 거부를 **에러 메시지로 받고**, 죽지 않고 사용자에게 보고

그리고 `/memories/summary.md` 는 이 `invoke` 가 끝나도 `store` 에 남습니다.

**출력**
```
  store 에 남은 /memories: {"files":[{"path":"/summary.md","is_dir":false,"size":…,"modified_at":"…"}]}
```

여기서도 접두사가 벗겨져 **`/summary.md`** 로 저장된 것을 볼 수 있습니다(5-6 함정).

> 💡 **실무 팁**: 이 조합(`CompositeBackend` + `permissions`)이 프로덕션 Deep Agent 의 기본형이라고 생각하세요. 여기서 셸이 필요해지면 기본 백엔드를 원격 샌드박스로 바꾸고, 사용자별 분리가 필요해지면 `StoreBackend` 의 `namespace` 를 팩토리로 바꿉니다. 구조는 그대로입니다.

---

## 정리

| 백엔드 | 저장 위치 | 수명 | 주의할 점 |
|---|---|---|---|
| `StateBackend` | LangGraph 상태 | 스레드 한정 | 직접 호출 불가 (런타임 주입 필요) |
| `FilesystemBackend` | 로컬 디스크 | 영구 | **`virtualMode: true` 없으면 격리 안 됨** |
| `StoreBackend` | LangGraph Store | 스레드 간 영속 | `namespace` 고정 시 사용자끼리 섞임 |
| `CompositeBackend` | 접두사별 위임 | 대상에 따름 | 라우팅 대상은 **접두사가 벗겨진** 경로를 받음 |
| `LocalShellBackend` | 디스크 + 셸 | 영구 | **`execute` 는 `virtualMode` 를 무시함** |
| 원격 샌드박스 | 격리 환경 | 샌드박스 수명 | 셸이 꼭 필요할 때의 정답 |
| 커스텀 (`BackendProtocolV2`) | 마음대로 | 마음대로 | 메서드 7개, `throw` 대신 `{ error }` |

**`permissions` 요약**

```ts
{ operations: ("read" | "write")[], paths: string[], mode?: "allow" | "deny" }
```
- **첫 매칭 규칙이 이긴다** → 좁은 `deny` 를 위로, 넓은 `allow` 를 아래로
- **기본은 allow** → 화이트리스트를 원하면 맨 끝에 `{ paths: ["/**"], mode: "deny" }`
- `"ask"` 모드는 없다 → 승인을 받고 싶으면 `interruptOn`([Step 09](../step-09-hitl-permissions/))

**핵심 함정 3가지**

1. **`rootDir` 는 격리가 아니다.** `virtualMode: true` 를 켜야 격리됩니다. 안 켜면 `rootDir` 를 줬어도 `/etc/hosts` 가 그대로 읽힙니다 — 에러도 경고도 없이.
2. **`execute` 는 `virtualMode` 를 지키지 않는다.** 파일 도구는 막히는데 셸은 뚫립니다. `LocalShellBackend` 의 격리는 **없습니다.** 셸이 필요하면 인터프리터나 원격 샌드박스로 가세요.
3. **권한 규칙은 순서가 전부다.** 넓은 `allow` 를 위에 두면 아래의 `deny` 는 평가조차 안 됩니다. `.env` 가 조용히 뚫립니다. 그리고 아무 규칙에도 안 걸리면 **허용**입니다.

---

## 연습문제

1. **백엔드 고르기**: 다음 4가지 상황에 어떤 백엔드를 쓸지 고르고 이유를 한 줄로 적으세요. (a) 새로고침하면 날아가도 되는 브라우저 데모 (b) 사용자 코드 저장소를 리팩터링하는 CLI (c) 대화가 끝나도 사용자 선호를 기억하는 챗봇 (d) (c)를 하면서 작업 파일은 디스크에 두기
2. **virtualMode 로 탈출 막기**: 임시 디렉터리를 `rootDir` 로 하는 `FilesystemBackend` 를 `virtualMode: true` 로 만들고, `"/../../etc/passwd"`, `"/etc/hosts"`, `"/~/.ssh/id_rsa"` 세 시도가 전부 막히는지 확인하세요. **세 개가 서로 다른 이유로 막힙니다** — 각각 무엇이 다른지 설명하세요.
3. **namespace 격리**: `InMemoryStore` 하나를 공유하되 `namespace` 가 다른 `StoreBackend` 2개를 만들어, `user-a` 가 쓴 파일이 `user-b` 에게 안 보이는 것을 확인하세요. 그다음 같은 `namespace` 로 다시 열면 남아 있는 것(영속)도 확인하세요.
4. **CompositeBackend 조립**: `/memories/` → Store, `/workspace/` → 디스크, 나머지 → 기본 으로 라우팅하는 백엔드를 만들고 각 경로에 파일을 쓰세요. `fs.readdir` 로 **`/workspace/` 것만 진짜 디스크에 있는지** 검증하고, 라우팅된 Store 가 실제로 가진 키가 무엇인지도 출력해 보세요.
5. **권한 규칙의 순서**: `checkWrite(rules, path)` 를 구현해서, "같은 규칙 다른 순서"인 두 배열이 `/workspace/.env` 에 대해 각각 어떤 결정을 내는지 출력하세요. (첫 매칭 승리 + 기본 allow)
6. **셸은 virtualMode 를 안 지킨다**: `LocalShellBackend` 를 `virtualMode: true` 로 만들고, `shell.read("/etc/hosts")` 와 `shell.execute("cat /etc/hosts")` 의 결과가 다른 것을 확인하세요. **왜 다른지** 주석으로 설명하세요.
7. **커스텀 백엔드 — 읽기 전용 래퍼**: 다른 백엔드를 감싸 읽기는 위임하고 `write`/`edit` 만 `{ error }` 로 거부하는 `ReadOnlyBackend` 를 만드세요. 보너스: 같은 일을 `permissions` 로도 할 수 있는데, 어느 쪽이 나을까요?

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 06 — 서브에이전트](../step-06-subagents/)

백엔드로 "에이전트가 무엇을 볼 수 있는가" 를 정했습니다. 다음은 **컨텍스트**입니다. 에이전트가 파일을 30개 읽으면 그 내용이 전부 대화에 쌓여 컨텍스트가 터집니다. 서브에이전트는 그 문제를 "일을 나눠서" 가 아니라 **"부모의 컨텍스트를 보호해서"** 푸는 도구입니다. 그리고 서브에이전트마다 다른 `backend` 와 `permissions` 를 줄 수 있으니, 이번 스텝의 내용이 그대로 이어집니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(5-1 ~ 5-10)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 7개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `docs/reference/deepagent/` 에서 `npx tsx step-05-backends/practice.ts` 처럼 실행합니다. **대부분의 예제는 API 키 없이 그대로 돌아갑니다** — 백엔드 객체를 직접 두드려 보는 예제라 모델을 안 부르기 때문입니다. `practice.ts` 의 `[5-10]` 만 실제 모델을 호출하며, `ANTHROPIC_API_KEY` 가 없으면 그 절만 조용히 건너뜁니다. OpenAI 로 바꾸려면 각 파일 상단의 `MODEL` 상수를 `"openai:gpt-5.5"` 로 고치고 `OPENAI_API_KEY` 를 넣으면 됩니다.

### practice.ts

본문을 따라가며 손으로 쳐볼 예제를 `[5-1] ~ [5-10]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[5-1]` 은 `createDeepAgent(...) instanceof Promise` 를 실제로 찍어서 **`await` 가 필요 없다**는 것을 눈으로 확인시킵니다. 문서마다 `await` 를 붙인 예제와 안 붙인 예제가 섞여 있어 헷갈리기 쉬운 지점이라, 코드로 못을 박아 둡니다.
- `[5-4]` 가 이 파일의 심장입니다. 같은 `rootDir` 에 `virtualMode` 만 `true`/`false` 로 바꾼 백엔드 2개를 만들어 `/etc/hosts` 를 읽습니다. 앞은 `ENOENT`, 뒤는 **진짜 시스템 파일 내용**이 나옵니다. 이 두 줄이 본문 5-4 함정의 전부입니다.
- `[5-6]` 의 마지막 두 줄은 `CompositeBackend` 에 라우팅된 `StoreBackend` 를 **직접 열어서** `ls("/")` 를 찍습니다. 에이전트가 `/memories/user.md` 로 쓴 파일이 거기서는 `/user.md` 로 보입니다 — 접두사가 벗겨지는 것을 증명하는 대목입니다.
- `[5-8]` 은 `shell.read("/etc/hosts")`(막힘)와 `shell.execute("cat /etc/hosts")`(뚫림)를 **연달아** 실행합니다. 같은 백엔드 인스턴스에서 두 결과가 갈리는 것을 보고 나면 `LocalShellBackend` 를 함부로 못 씁니다. `execute("node -v")` 가 `command not found` 로 실패하는 것도 정상입니다 — `inheritEnv` 기본값이 `false` 라 `PATH` 가 최소입니다.
- `[5-9]` 의 `MockS3Backend` 는 `log` 배열에 모든 호출을 기록합니다. 마지막에 이 로그를 찍으므로 "백엔드가 실제로 어떤 순서로 불렸는가" 를 볼 수 있습니다. 커스텀 백엔드를 디버깅할 때 그대로 쓸 수 있는 패턴입니다.
- `[5-10]` 만 모델을 부릅니다. 키가 없으면 안내 문구를 찍고 넘어가니, 키 없이 파일을 통째로 실행해도 끝까지 돕니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 7개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 `// TODO: 여기에 작성` 아래가 비어 있습니다.

- `[문제 1]` 만 코드가 아니라 **주석으로 답하는 문제**입니다. 파일을 실행해도 아무것도 안 나옵니다. 정상입니다. 백엔드 선택은 이 스텝에서 가장 실무적인 판단이라 일부러 코드 없이 물어봅니다.
- `[문제 2]` 의 힌트 `read() 는 throw 하지 않고 { error } 를 돌려줍니다` 가 중요합니다. `try/catch` 로 감싸면 아무것도 안 잡힙니다. 반환값의 `error` 필드를 봐야 합니다.
- `[문제 4]` 는 기본 백엔드로 `StateBackend` 대신 `StoreBackend` 를 쓰라고 안내합니다. `StateBackend` 는 런타임 주입 없이 직접 호출할 수 없기 때문입니다(본문 5-3 함정). 이 제약을 모르면 문제를 풀다가 정체불명의 `configurable` 에러를 만납니다.
- `[문제 5]` 는 `simpleGlob` 헬퍼를 이미 제공합니다. 여러분이 만들 것은 `checkWrite` 의 **평가 순서**뿐입니다 — glob 매칭 구현에 시간을 쓰지 말라는 뜻입니다.
- `[문제 6]` 은 파일 끝에 `// → 왜 다른가요? (여기에 설명)` 자리가 있습니다. 코드를 돌려 결과를 본 뒤 이유를 직접 적어보세요. `await shell.close()` 를 빠뜨리면 프로세스가 안 끝날 수 있습니다.
- `[문제 7]` 의 `ReadOnlyBackend` 는 메서드 7개가 전부 `throw new Error("구현하세요")` 로 채워져 있습니다. 타입이 맞게 골격만 잡아 둔 것이니, 시그니처는 건드리지 말고 본문만 채우세요.

```ts file="./exercise.ts"
```

### solution.ts

7문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 블록 아래 주석에 "왜 그런가" 와 "무엇이 함정인가" 를 적어 두었습니다.

- `[정답 2]` 의 핵심은 세 탈출 시도가 **다른 이유로** 막힌다는 것입니다. `/../../etc/passwd` 는 `Path traversal not allowed` 로 **차단**되지만, `/etc/hosts` 와 `/~/.ssh/id_rsa` 는 차단이 아니라 `<rootDir>/etc/hosts` 로 **재해석**되어 `ENOENT` 가 납니다. 에러 메시지의 `stat '/var/folders/…/etc/hosts'` 부분을 보면 확인됩니다. "막혔다" 와 "다른 곳을 봤다" 는 다른 얘기이고, 이 차이가 `virtualMode: false` 일 때 왜 뚫리는지를 설명해 줍니다.
- `[정답 4]` 는 `Store 가 실제로 가진 키` 를 출력해 `/memories/pref.md` → `/pref.md` 로 접두사가 벗겨진 것을 보여줍니다. 커스텀 백엔드를 `CompositeBackend` 에 끼울 때 반드시 밟는 함정이라, 정답에서 한 번 더 못을 박습니다.
- `[정답 5]` 가 이 파일의 하이라이트입니다. `good` 과 `bad` 는 **규칙이 완전히 같고 순서만 다른데** `/workspace/.env` 에 대해 `deny` 와 `allow` 로 갈립니다. 이어서 `leaky` 예제가 "allow 규칙만 쓰면 나머지가 자동으로 막히지 않는다"(기본 allow)를 보여주고, `sealed` 가 맨 끝에 `{ paths: ["/**"], mode: "deny" }` 를 깔아 고칩니다. 이 세 배열을 나란히 읽는 것이 권한 규칙을 이해하는 가장 빠른 길입니다.
- `[정답 6]` 은 답을 코드가 아니라 **주석**으로 길게 적었습니다. `virtualMode` 가 "백엔드의 파일 메서드가 경로를 해석하는 방식" 만 바꾸는 설정이고 `execute` 는 그 해석기를 아예 안 거친다는 것, 그래서 셸에게 `rootDir` 는 감옥이 아니라 `cwd` 일 뿐이라는 것. 그리고 대안 3가지(인터프리터 → 원격 샌드박스 → 컨테이너)를 순서대로 정리했습니다.
- `[정답 7]` 은 정답 코드 자체는 짧습니다(위임 5줄 + 거부 2줄). 진짜 내용은 마지막 보너스 주석입니다 — **`permissions` = 어디에(where), 커스텀 백엔드 = 어떻게(how)**. "쓰기 막기" 정도는 `permissions` 가 낫고, 커스텀 백엔드는 "감사 로그", "호출 횟수 제한", "S3 저장" 처럼 경로 규칙으로 표현할 수 없는 정책에 씁니다.

```ts file="./solution.ts"
```
