# Step 09 — HITL과 권한 제어

> **학습 목표**
> - Deep Agent 가 일반 에이전트보다 왜 더 위험한지 설명하고, 위험 표면을 도구 단위로 나눈다
> - `interruptOn` 으로 특정 도구에만 사람 승인을 걸고, `allowedDecisions` 로 허용 액션을 좁힌다
> - `__interrupt__` 를 읽고 `Command({ resume })` 로 재개하는 승인 흐름을 직접 구현한다
> - `approve` / `edit` / `reject` 세 가지 결정을 구분해서 쓴다
> - `permissions` 로 백엔드 레벨 정적 접근 제어를 걸고, `interruptOn` 과의 역할 차이를 안다
> - 위험 등급(read / write / execute)별로 정책을 설계하고 승인 피로를 피한다
>
> **선행 스텝**: [Step 08 — 미들웨어 조합](../step-08-middleware/)
> **예상 소요**: 80분

지금까지 우리는 Deep Agent 에게 계획을 세우게 하고([Step 03](../step-03-planning-todos/)), 파일시스템을 주고([Step 04](../step-04-filesystem/)), 서브에이전트를 스폰하게 했습니다([Step 06](../step-06-subagents/)). 능력은 충분히 갖췄습니다. 이제 문제는 반대쪽입니다. **이 에이전트를 어떻게 막을 것인가.**

이 스텝은 두 개의 다른 도구를 다룹니다. `interruptOn` 은 "사람에게 물어본다" 이고, `permissions` 는 "사람에게 묻지도 않고 그냥 막는다" 입니다. 둘은 대체재가 아니라 보완재입니다. 어느 쪽 하나만으로는 안전한 에이전트를 만들 수 없습니다. 왜 그런지가 이 스텝의 핵심입니다.

> **검증 버전**: `deepagents` 1.11.0 / `langchain` 1.5.3 / `@langchain/langgraph` 1.4.8 / `@langchain/core` 1.2.3

---

## 9-1. Deep Agent 는 왜 더 위험한가

일반 챗봇이 잘못하면 **틀린 말**을 합니다. Deep Agent 가 잘못하면 **틀린 일**을 합니다. 이 차이가 전부입니다.

Deep Agent 는 기본적으로 다음 도구들을 갖고 태어납니다([Step 04](../step-04-filesystem/) 참고).

| 도구 | 하는 일 | 되돌릴 수 있나 |
|---|---|---|
| `ls`, `read_file`, `glob`, `grep` | 읽기 | 되돌릴 것이 없음 |
| `write_file`, `edit_file` | 쓰기 | 백엔드에 따라 다름 |
| `execute` | 셸 명령 실행 (샌드박스 백엔드 전용) | **불가능** |
| `task` | 서브에이전트 스폰 | 스폰된 놈이 또 뭘 할지 모름 |

`task` 가 특히 고약합니다. 서브에이전트는 부모와 같은 도구를 갖고, 자기 판단으로 파일을 씁니다. 즉 **승인을 걸어야 할 지점이 하나가 아니라 트리 전체**입니다.

통제 없는 에이전트가 어떻게 동작하는지 먼저 봅시다.

```ts
import { createDeepAgent, StateBackend } from "deepagents";
import { MemorySaver } from "@langchain/langgraph";

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),
  systemPrompt: "너는 파일 관리 도우미다. 요청받은 파일 작업을 수행해라.",
});

const result = await agent.invoke(
  { messages: [{ role: "user", content: "/notes/hello.txt 에 '안녕'이라고 써줘." }] },
  { configurable: { thread_id: "9-1" } },
);

console.log("__interrupt__:", result.__interrupt__);
console.log("파일 목록:", Object.keys(result.files ?? {}));
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
__interrupt__: undefined
파일 목록: [ '/notes/hello.txt' ]
```

물어보지도 않고 썼습니다. `__interrupt__` 는 `undefined` 입니다 — 멈출 이유가 하나도 없었으니까요. 실습에서는 `StateBackend`(메모리상의 가상 FS)라 안전하지만, `FilesystemBackend` 였다면 실제 디스크에 파일이 생겼을 겁니다.

> 💡 **실무 팁**: 개발 중에는 `StateBackend` 를 기본으로 쓰세요. 상태에만 존재하는 가상 파일시스템이라 에이전트가 뭘 하든 프로세스가 끝나면 사라집니다. `FilesystemBackend` 로 갈아끼우는 것은 정책을 다 붙인 다음입니다. "일단 돌려보고 나중에 잠그자" 는 순서가 사고를 만듭니다.

---

## 9-2. `interruptOn` — 어떤 도구에 승인을 걸 것인가

`interruptOn` 은 `createDeepAgent` 의 최상위 옵션입니다. 타입은 정확히 이렇습니다.

```ts
interruptOn?: Record<string, boolean | InterruptOnConfig>
```

**도구 이름 → 설정** 의 맵입니다. 값으로 올 수 있는 것은 세 가지입니다.

| 값 | 의미 |
|---|---|
| `true` | 멈추고 승인을 기다린다. `approve` / `edit` / `reject` 모두 허용 |
| `false` | 자동 승인 (= 키를 아예 안 적은 것과 동일) |
| `InterruptOnConfig` | 허용 결정과 문구를 명시적으로 지정 |

`InterruptOnConfig` 의 필드는 네 개입니다.

```ts
interface InterruptOnConfig {
  allowedDecisions: ("approve" | "edit" | "reject")[];  // 필수
  description?: string | ((toolCall, state, runtime) => string | Promise<string>);
  argsSchema?: Record<string, any>;   // edit 을 허용할 때 인자 JSON 스키마
  when?: (request) => boolean | Promise<boolean>;  // true 면 멈춘다
}
```

실제로 조합해 보면 이렇습니다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),   // ← 없으면 아무 일도 안 일어납니다
  interruptOn: {
    // (a) 가장 단순한 형태
    write_file: true,

    // (b) 허용 결정을 좁힌다 — edit 을 빼면 사람이 인자를 못 고친다
    edit_file: {
      allowedDecisions: ["approve", "reject"],
      description: "⚠️ 파일을 수정하려 합니다. 내용을 확인하세요.",
    },

    // (c) description 을 함수로 — 툴 호출 인자를 보고 문구를 만든다
    execute: {
      allowedDecisions: ["approve", "reject"],
      description: (toolCall) =>
        `⛔ 셸 명령 실행 요청\n명령: ${JSON.stringify(toolCall.args, null, 2)}`,
    },

    // (d) 읽기 도구는 자동 승인 — 명시적으로 false
    read_file: false,
    ls: false,
    grep: false,
  },
});
```

`read_file: false` 는 사실 **생략해도 결과가 같습니다.** `interruptOn` 에 없는 도구는 자동 승인이 기본이기 때문입니다. 그래도 적는 이유는 "읽기는 일부러 열어뒀다" 는 판단을 코드에 남기기 위해서입니다. 나중에 이 파일을 읽는 사람이 "read_file 승인을 깜빡한 건가?" 를 고민하지 않아도 됩니다.

> ⚠️ **함정 (기본이 승인이 아니라 통과다)**: `interruptOn` 은 **화이트리스트가 아니라 블랙리스트**입니다. 적지 않은 도구는 전부 자동 승인됩니다. "위험한 것들 몇 개 적어놨으니 나머지는 알아서 막히겠지" 가 아닙니다. 새 도구를 추가하고 `interruptOn` 에 등록하는 것을 잊으면, 그 도구는 **아무 승인 없이 실행됩니다.** 에러도 경고도 없습니다. 도구를 추가할 때마다 `interruptOn` 을 같이 검토하는 것을 습관으로 만드세요.

---

## 9-3. 승인 흐름 — interrupt → `__interrupt__` → `Command` resume

승인은 3단계입니다. 그리고 **checkpointer 가 없으면 이 3단계가 통째로 무너집니다.**

```ts
import { Command, MemorySaver } from "@langchain/langgraph";
import type { HITLRequest, HITLResponse, Interrupt } from "langchain";

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),   // 필수
  interruptOn: { write_file: true },
});

// thread_id 는 재개할 때 같은 값을 써야 합니다. 이게 "재개 커서" 입니다.
const config = { configurable: { thread_id: "9-3" } };

// (1) 최초 호출 → interrupt 발생
const first = await agent.invoke(
  { messages: [{ role: "user", content: "/report.md 에 '분기 매출 요약'이라고 써줘." }] },
  config,
);

// (2) __interrupt__ 읽기 — value 는 HITLRequest 타입
const req = first.__interrupt__![0] as Interrupt<HITLRequest>;
console.log(JSON.stringify(req.value, null, 2));

// (3) Command 로 재개
const resume: HITLResponse = { decisions: [{ type: "approve" }] };
const second = await agent.invoke(new Command({ resume }), config);
```

`req.value` 의 **구조는 결정적**입니다. 정확히 이렇게 생겼습니다.

```json
{
  "actionRequests": [
    {
      "name": "write_file",
      "args": { "file_path": "/report.md", "content": "분기 매출 요약" },
      "description": "Tool execution requires approval\n\nwrite_file"
    }
  ],
  "reviewConfigs": [
    {
      "actionName": "write_file",
      "allowedDecisions": ["approve", "edit", "reject"]
    }
  ]
}
```

두 배열의 역할이 다릅니다.

- **`actionRequests`**: 지금 승인을 기다리는 **개별 도구 호출들**. 모델이 한 턴에 `write_file` 을 두 번 부르면 여기는 2개가 됩니다.
- **`reviewConfigs`**: **도구별 정책**. 같은 도구를 두 번 불러도 여기는 1개일 수 있습니다.

`description` 의 기본 문구 `"Tool execution requires approval"` 은 `descriptionPrefix` 옵션의 기본값입니다. 도구별 `description` 을 직접 주면 이 접두사는 무시됩니다.

> ⚠️ **함정 (reviewConfigs 를 인덱스로 짝짓지 마라)**: `actionRequests[i]` 와 `reviewConfigs[i]` 를 같은 인덱스로 매칭하는 코드를 자주 봅니다. **틀립니다.** 두 배열은 길이가 다를 수 있습니다. `actionRequests` 는 호출 단위, `reviewConfigs` 는 도구 단위이기 때문입니다. 반드시 `reviewConfigs.find(c => c.actionName === action.name)` 으로 이름을 맞춰 찾으세요. 인덱스로 짝지으면 도구를 여러 번 부른 순간 엉뚱한 정책이 붙고, 에러 없이 조용히 잘못된 UI 를 보여줍니다.

> ⚠️ **함정 (checkpointer 가 없으면 interrupt 는 무동작이다)**: 이 스텝 최대의 함정입니다. `interruptOn` 을 다 설정해 놓고 `checkpointer` 를 빼면, 에이전트는 **멈추지 않고 그냥 도구를 실행합니다.** 예외도, 경고도, 로그도 없습니다. `__interrupt__` 는 `undefined` 이고 파일은 이미 쓰여 있습니다. 이유는 단순합니다 — interrupt 는 "상태를 저장했다가 나중에 되살리는" 메커니즘인데, 저장할 곳이 없으니 되살릴 수도 없습니다. **"승인 게이트를 달았다"고 믿고 프로덕션에 올렸는데 게이트가 열려 있는 상태**가 이렇게 만들어집니다. `interruptOn` 을 쓰는 코드에서는 `checkpointer` 가 있는지 테스트로 못 박아 두세요.

> 💡 **실무 팁**: `MemorySaver` 는 프로세스가 죽으면 전부 날아갑니다. 승인 대기 중인 작업이 있는데 서버가 재시작되면 그 작업은 영영 재개할 수 없습니다. 사람의 승인은 몇 분에서 며칠까지 걸리므로, 프로덕션에서는 반드시 영속 체크포인터(Postgres 등)를 쓰세요. `thread_id` 만 알면 몇 시간 뒤 다른 프로세스에서도 재개할 수 있다는 게 이 설계의 핵심입니다.

---

## 9-4. 승인 액션 — `approve` / `edit` / `reject`

`langchain` 1.5.3 의 `DecisionType` 은 정확히 **세 가지**입니다.

```ts
declare const DecisionType: z.ZodEnum<["approve", "edit", "reject"]>;
```

> ⚠️ **버전 주의**: LangChain 공식 문서 일부 페이지에는 네 번째 액션으로 `respond` 가 나옵니다. 하지만 **`langchain` 1.5.3 에 설치된 타입에는 `respond` 가 없습니다.** 쓰면 런타임 스키마 검증에서 거부됩니다. "사람이 대신 답해준다" 는 효과가 필요하면 `reject` 의 `message` 로 같은 목적을 달성할 수 있습니다 — 도구를 실행하지 않고 사람이 쓴 문장이 모델에게 전달되기 때문입니다. 문서와 설치된 타입이 다를 때는 **설치된 타입이 진실**입니다.

각 결정의 shape 은 결정적입니다.

```ts
// (a) approve — 원래 인자 그대로 실행
{ type: "approve" }

// (b) edit — 인자를 바꿔서 실행
{
  type: "edit",
  editedAction: { name: "write_file", args: { file_path: "/safe/draft.txt", content: "..." } }
}

// (c) reject — 실행하지 않고 message 를 모델에게 피드백
{ type: "reject", message: "루트 경로에는 파일을 만들 수 없습니다." }
```

### edit 의 함정 — `editedAction` 은 병합이 아니라 교체다

```ts
const req = r1.__interrupt__![0] as Interrupt<HITLRequest>;
const original = req.value.actionRequests[0];

const resume: HITLResponse = {
  decisions: [
    {
      type: "edit",
      editedAction: {
        name: original.name,
        args: { ...original.args, file_path: "/safe/draft.txt" },  // ← 스프레드가 핵심
      },
    },
  ],
};
```

> ⚠️ **함정 (editedAction 은 통째로 교체된다)**: `args: { file_path: "/safe/draft.txt" }` 라고만 쓰면 **`content` 가 사라집니다.** `editedAction` 은 원본 인자를 부분 수정하는 게 아니라 통째로 갈아끼우기 때문입니다. 결과는 "빈 파일이 만들어지거나" "필수 인자 누락으로 도구가 실패" 인데, 둘 다 승인 UI 상에서는 정상으로 보입니다. 반드시 `...original.args` 로 원본을 펼친 뒤 바꿀 키만 덮어쓰세요.

### reject 의 함정 — 거절했다고 끝이 아니다

`reject` 는 "도구를 실행하지 않고, `message` 를 도구 결과인 것처럼 모델에게 돌려주는 것" 입니다. 모델 입장에서는 도구가 실패 메시지를 반환한 것처럼 보이고, 그걸 읽고 **다음 행동을 정합니다.**

```ts
const resume: HITLResponse = {
  decisions: [{ type: "reject", message: "루트에는 못 씁니다. /workspace/ 아래로만 쓰세요." }],
};
const r2 = await agent.invoke(new Command({ resume }), config);

console.log("또 interrupt?", Boolean(r2.__interrupt__));   // true 일 수 있습니다
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
또 interrupt? true
```

모델이 `/workspace/draft.txt` 로 경로를 고쳐서 **재시도**했고, 그래서 승인 요청이 또 온 것입니다. 이건 버그가 아니라 정상 동작이며, 오히려 `message` 를 잘 써준 덕분입니다.

> 💡 **실무 팁**: `reject` 의 `message` 를 비워두지 마세요. 모델은 왜 거절당했는지 모르면 **같은 시도를 반복**합니다. "안 됩니다" 가 아니라 "이 경로는 금지다, 대신 여기로 써라" 처럼 **다음 행동을 지시**하는 문장을 쓰세요. `message` 는 사용자에게 보여주는 텍스트가 아니라 **모델에게 주는 프롬프트**입니다.

---

## 9-5. `permissions` — 백엔드 레벨 정적 접근 제어

`interruptOn` 이 "사람에게 물어본다" 라면, `permissions` 는 **묻지도 않고 막는 정적 규칙**입니다.

```ts
interface FilesystemPermission {
  operations: readonly ("read" | "write")[];
  paths: string[];             // 절대 glob. `/` 로 시작해야 하고 `..` `~` 불가
  mode?: "allow" | "deny";     // 기본값 "allow"
}
```

`operations` 는 두 가지뿐이고, 각각이 커버하는 도구가 정해져 있습니다.

| operation | 커버하는 내장 도구 |
|---|---|
| `read` | `ls`, `read_file`, `glob`, `grep` |
| `write` | `write_file`, `edit_file` |

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),
  permissions: [
    // 1) /workspace 아래는 읽기·쓰기 허용
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    // 2) /secrets 아래는 읽기조차 금지
    { operations: ["read", "write"], paths: ["/secrets/**"], mode: "deny" },
    // 3) 그 외 전부 금지 — 이 "마지막 빗장"이 반드시 필요합니다
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ],
});
```

규칙은 **선언 순서대로 평가되고 first-match-wins** 입니다. 그리고 어느 규칙에도 안 걸리면 **기본이 `allow`** 입니다.

> ⚠️ **함정 (기본이 deny 가 아니라 allow 다)**: 위 예제의 3번 규칙을 빼면 어떻게 될까요? `/etc/passwd` 는 1번(안 걸림), 2번(안 걸림)을 지나 **아무 규칙에도 매칭되지 않고 → 기본값 allow → 읽힙니다.** "허용 목록을 적었으니 나머지는 막히겠지" 는 정확히 반대입니다. `permissions` 는 방화벽이 아니라 필터 체인입니다. **허용 목록 방식으로 쓰려면 반드시 마지막에 `{ paths: ["/**"], mode: "deny" }` 빗장을 넣으세요.**

### `interruptOn` vs `permissions` — 무엇이 다른가

이 표가 이 절의 핵심입니다.

| | `interruptOn` | `permissions` |
|---|---|---|
| **결정 주체** | 사람 (런타임) | 코드 (구성 시점) |
| **판단 시점** | 도구 호출 직전, 매번 | 도구 호출 직전, 규칙 매칭 |
| **필요 조건** | **checkpointer 필수** | 없음 |
| **실행 흐름** | 멈춘다 → 재개해야 함 | 안 멈춘다. 에러 문자열 반환 |
| **적용 대상** | **모든 도구** (커스텀·MCP 포함) | **내장 파일시스템 도구만** |
| **유연성** | 상황을 보고 그때그때 판단 | 정적. 컨텍스트를 못 봄 |
| **비용** | 사람의 시간. 지연 발생 | 0 |
| **뚫리는 경우** | 사람이 y 를 남발할 때 | 규칙 구멍 / 대상 밖 도구 |

핵심 비대칭이 두 줄에 있습니다.

1. **`permissions` 는 내장 파일시스템 도구에만 적용됩니다.** 여러분이 만든 커스텀 도구나 MCP 도구는 `permissions` 규칙을 **완전히 무시합니다.** `fs.writeFileSync` 를 직접 쓰는 커스텀 도구를 만들면 `permissions` 는 아무것도 못 막습니다.
2. **`permissions` 는 `execute` 를 막지 못합니다.** `execute` 는 셸이라 `cat /secrets/keys.env` 한 줄이면 `permissions` 규칙 전체를 우회합니다.

> 💡 **실무 팁**: 두 개를 겹쳐 쓰세요. `permissions` 는 **"실수를 막는 바닥"** 이고 `interruptOn` 은 **"판단이 필요한 것을 거르는 문"** 입니다. `permissions` 로 범위를 좁혀 놓으면 승인 요청 자체가 줄어들고, 그러면 사람이 각 요청을 실제로 읽게 됩니다. 반대로 `permissions` 없이 `interruptOn` 만 쓰면 승인 요청이 폭주해서 사람이 내용을 안 읽습니다 — 9-7 의 승인 피로 이야기로 이어집니다.

---

## 9-6. 서브에이전트별 `interruptOn` / `permissions`

`SubAgent` 는 자기만의 `interruptOn` 과 `permissions` 를 가질 수 있습니다. 그런데 **상속 규칙이 둘이 다릅니다.**

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),
  permissions: [
    { operations: ["read", "write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ],
  interruptOn: { write_file: true },
  subagents: [
    {
      name: "auditor",
      description: "코드를 읽고 문제점만 보고한다. 절대 파일을 수정하지 않는다.",
      systemPrompt: "너는 감사자다. 읽기만 하고 발견한 문제를 목록으로 보고해라.",
      // 부모 규칙을 통째로 교체 — 이 배열만으로 완결적이어야 합니다
      permissions: [
        { operations: ["write"], paths: ["/**"], mode: "deny" },
        { operations: ["read"], paths: ["/workspace/**"], mode: "allow" },
        { operations: ["read"], paths: ["/**"], mode: "deny" },
      ],
      // 쓰기가 애초에 불가능하니 승인 게이트가 불필요 → 감사자는 안 멈추고 끝까지 돈다
      interruptOn: {},
    },
    {
      name: "writer",
      description: "감사 결과를 바탕으로 파일을 실제로 수정한다.",
      systemPrompt: "너는 수정자다. 감사 결과를 반영해 파일을 고쳐라.",
      // permissions 생략 → 부모 규칙 상속
      interruptOn: {
        write_file: { allowedDecisions: ["approve", "edit", "reject"] },
        edit_file: { allowedDecisions: ["approve", "edit", "reject"] },
      },
    },
  ],
});
```

`permissions` 의 상속 규칙은 정확히 이렇습니다.

| 서브에이전트의 `permissions` | 결과 |
|---|---|
| 생략 (`undefined`) | 부모 규칙을 **상속** |
| 배열 명시 | 부모 규칙을 **통째로 교체** (병합 아님) |
| `[]` (빈 배열) | **무제한** — 부모 규칙도 무시 |

> ⚠️ **함정 (`permissions: []` 는 "권한 없음" 이 아니라 "무제한" 이다)**: 직관과 정반대입니다. 빈 배열이면 어떤 규칙에도 매칭되지 않고 → 기본값 `allow` → **전부 통과**합니다. 서브에이전트를 잠그려는 의도로 `permissions: []` 를 쓰면, 부모보다 **더 강한 권한**을 가진 서브에이전트가 만들어집니다. 부모가 `/secrets` 를 막아놨더라도 이 서브에이전트는 읽습니다. 잠그려면 명시적 `deny` 규칙을 쓰세요.

그리고 `permissions` 를 명시하면 **부모 규칙이 병합되지 않고 교체**되므로, 서브에이전트의 배열은 그 자체로 완결적이어야 합니다. 마지막 빗장도 직접 넣어야 합니다. 위 `auditor` 에서 `{ operations: ["read"], paths: ["/**"], mode: "deny" }` 를 빠뜨리면, 부모가 막아둔 `/secrets` 를 감사자는 읽을 수 있게 됩니다.

> ⚠️ **함정 (서브에이전트 안의 승인은 부모에게 안 온다고 착각하지 마라)**: 반대로 착각하는 경우도 많습니다. 서브에이전트가 `interruptOn` 을 가지면 그 서브에이전트가 도구를 부를 때도 **interrupt 가 발생하고, 부모의 `invoke` 결과에 `__interrupt__` 로 올라옵니다.** 즉 승인 루프를 한 번 돌면 끝이 아니라 **서브에이전트가 도구를 부를 때마다 또 멈춥니다.** 이걸 모르면 "왜 승인을 다섯 번이나 하지?" 라고 당황하게 됩니다. 9-8 의 `while` 루프가 필요한 이유입니다.

> 💡 **실무 팁**: `auditor` 처럼 **권한으로 막을 수 있는 것은 승인으로 막지 마세요.** 쓰기가 구조적으로 불가능한 에이전트에게 쓰기 승인 게이트를 다는 것은 사람의 시간만 낭비합니다. "이 서브에이전트는 애초에 그걸 못 한다" 가 "이 서브에이전트가 그걸 하려 하면 물어본다" 보다 항상 낫습니다. 승인은 **정말로 사람의 판단이 필요한 곳**에만 남겨두세요.

---

## 9-7. 위험 등급별 정책 설계

도구를 세 등급으로 나누고 등급마다 다른 무기를 씁니다.

| 등급 | 예 | 정책 | 이유 |
|---|---|---|---|
| **읽기** | `ls`, `read_file`, `grep`, `glob` | `permissions` 로 범위만 제한. **승인 없음** | 되돌릴 게 없다. 승인을 걸면 피로만 쌓인다 |
| **쓰기** | `write_file`, `edit_file` | `permissions` + `interruptOn` 승인 | 되돌릴 수 있지만 아프다 |
| **실행** | `execute`, 배포, 송금 | **기본 금지.** 필요하면 샌드박스 + 승인 | 되돌릴 수 없다 |

```ts
const deployTool = tool(
  async ({ env }) => `${env} 에 배포했습니다.`,
  {
    name: "deploy",
    description: "지정한 환경에 애플리케이션을 배포한다.",
    schema: z.object({ env: z.enum(["staging", "production"]) }),
  },
);

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  backend: new StateBackend(),
  checkpointer: new MemorySaver(),
  tools: [deployTool],

  // 등급 1(read): 정적 규칙으로 범위만 제한, 승인 없음
  permissions: [
    { operations: ["read"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["write"], paths: ["/workspace/**"], mode: "allow" },
    { operations: ["read", "write"], paths: ["/**"], mode: "deny" },
  ],

  interruptOn: {
    // 등급 1: 자동 승인
    read_file: false, ls: false, grep: false, glob: false,

    // 등급 2(write): 승인 필요, 인자 수정 허용
    write_file: {
      allowedDecisions: ["approve", "edit", "reject"],
      description: (toolCall) => `📝 파일 쓰기: ${toolCall.args.file_path}`,
    },
    edit_file: { allowedDecisions: ["approve", "edit", "reject"] },

    // 등급 3(execute): 승인 필요 + edit 금지 + when 으로 production 만 거른다
    deploy: {
      allowedDecisions: ["approve", "reject"],
      description: (toolCall) => `🚨 PRODUCTION 배포 요청 (env=${toolCall.args.env})`,
      when: (request) => request.toolCall.args.env === "production",
    },
  },
});
```

`when` 이 이 설계의 핵심 장치입니다. `staging` 배포는 자동 승인되고 `production` 만 사람을 부릅니다. **`when` 은 "허용 조건" 이 아니라 "개입 조건" 입니다** — `true` 를 반환하면 멈춥니다. 반대로 읽기 쉬워서 자주 뒤집어 씁니다.

`deploy` 에서 `edit` 을 뺀 것도 의도적입니다. `edit` 을 허용하면 사람이 `env: "staging"` 을 `"production"` 으로 바꿔서 승인할 수 있게 되는데, 이건 승인이 아니라 **새로운 명령**입니다. 파괴적 작업에는 "그대로 하거나 / 안 하거나" 두 선택지만 주는 편이 안전합니다.

> ⚠️ **함정 (execute 를 승인만으로 막는 것은 부족하다)**: `execute: { allowedDecisions: ["approve", "reject"] }` 를 달아놓고 안심하면 안 됩니다. 사람이 `npm test` 를 보고 y 를 누릅니다. 그런데 그 `package.json` 의 test 스크립트가 뭔지는 아무도 안 봤습니다. 셸 명령은 **한 줄이 무한한 것을 할 수 있고, 승인 화면에 보이는 문자열이 실제 효과의 전부가 아닙니다.** `curl x | sh` 를 승인 화면에서 판별할 수 있는 사람은 없습니다. `execute` 에는 반드시 **샌드박스**(네트워크 차단, FS 격리, 리소스 제한)를 붙이세요. 승인은 샌드박스 **위에 얹는 것**이지 대체재가 아닙니다.

> ⚠️ **함정 (승인 피로, approval fatigue)**: 가장 조용하고 가장 위험한 함정입니다. 승인 요청을 100번 보내면 사람은 100번째쯤엔 내용을 안 읽고 y 를 누릅니다. 승인 게이트는 **코드상으로는 완벽하게 동작하는데 실질적으로는 없는 것과 같아집니다.** 그리고 이건 로그에 안 남습니다 — 승인 기록은 전부 정상으로 보입니다. 방어법은 **요청 수를 줄이는 것**뿐입니다: (1) `permissions` 로 애초에 못 하게 만들어 요청을 줄이고, (2) `when` 으로 정말 위험한 것만 물어보고, (3) `description` 에 판단에 필요한 정보를 다 넣어서 사람이 화면만 보고 결정할 수 있게 하세요. **하루 승인 건수가 두 자리를 넘어가면 정책 설계가 잘못된 것입니다.**

> 💡 **실무 팁**: 승인 UI 에는 "무엇을 하려는지" 만이 아니라 **"승인하면 무슨 일이 벌어지는지"** 를 보여주세요. `write_file` 이면 인자 JSON 이 아니라 **diff** 를 보여주는 것이 낫습니다. 사람은 `{"content": "..."}` 를 읽고 판단하지 못하지만 diff 는 읽습니다. `description` 을 함수로 만들 수 있는 이유가 이것입니다 — 기존 파일을 읽어서 diff 를 만들어 넣을 수 있습니다.

---

## 9-8. 실전 — 승인 게이트 달린 파일 편집 에이전트

지금까지의 조각을 CLI 로 조립합니다. 핵심은 **`while` 루프**입니다.

```ts
import * as readline from "node:readline/promises";

/** __interrupt__ 를 사람에게 보여주고 y/n/e 를 받아 HITLResponse 로 바꾼다. */
async function askHuman(req: Interrupt<HITLRequest>): Promise<HITLResponse> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const decisions: HITLResponse["decisions"] = [];

  try {
    for (const action of req.value.actionRequests) {
      // reviewConfigs 는 actionName 으로 찾습니다 (인덱스 대응이 아닙니다)
      const review = req.value.reviewConfigs.find((c) => c.actionName === action.name);
      const allowed = review?.allowedDecisions ?? ["approve", "reject"];

      console.log(`\n도구: ${action.name}`);
      if (action.description) console.log(action.description);
      console.log("인자:\n" + JSON.stringify(action.args, null, 2));
      console.log(`허용된 결정: ${allowed.join(" / ")}`);

      const answer = (await rl.question("승인? [y / n / e] ")).trim().toLowerCase();

      if (answer === "y" && allowed.includes("approve")) {
        decisions.push({ type: "approve" });
      } else if (answer === "e" && allowed.includes("edit")) {
        const raw = await rl.question("새 args (JSON): ");
        decisions.push({ type: "edit", editedAction: { name: action.name, args: JSON.parse(raw) } });
      } else {
        const why = await rl.question("거절 사유 (모델에게 전달됩니다): ");
        decisions.push({ type: "reject", message: why || "사용자가 거절했습니다." });
      }
    }
  } finally {
    rl.close();
  }

  return { decisions };
}
```

그리고 호출부입니다.

```ts
let result = await agent.invoke(
  { messages: [{ role: "user", content: "/workspace/README.md 를 만들고 '# 프로젝트' 를 넣어줘." }] },
  config,
);

// interrupt 가 없어질 때까지 승인 루프를 돈다
while (result.__interrupt__) {
  const req = result.__interrupt__[0] as Interrupt<HITLRequest>;
  const resume = await askHuman(req);
  result = await agent.invoke(new Command({ resume }), config);
}
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
도구: write_file
Tool execution requires approval

write_file
인자:
{
  "file_path": "/workspace/README.md",
  "content": "# 프로젝트\n"
}
허용된 결정: approve / edit / reject
승인? [y / n / e] y

최종 답변: /workspace/README.md 를 만들고 제목을 넣었습니다.
파일 목록: [ '/workspace/README.md' ]
```

`if` 가 아니라 `while` 인 이유가 핵심입니다. **한 번 재개했다고 끝이 아닙니다.** 모델은 다음 턴에 또 도구를 부를 수 있고, 서브에이전트가 도구를 불러도 멈추고, `reject` 를 받으면 재시도하면서 또 멈춥니다. `if` 로 한 번만 처리하면 두 번째 승인 요청을 놓치고 "왜 파일이 안 만들어지지?" 로 헤매게 됩니다.

> ⚠️ **함정 (재개하면 노드가 처음부터 다시 실행된다)**: LangGraph 의 근본 동작입니다. `Command({ resume })` 로 재개하면 런타임은 **`interrupt()` 를 호출한 그 줄부터 이어가는 게 아니라 그 노드를 처음부터 다시 실행합니다.** 즉 interrupt **앞**에 있던 코드가 **또 돕니다.** 커스텀 미들웨어나 도구 안에서 `interrupt()` 를 직접 쓴다면, 그 앞의 부수효과(감사 로그 append, 카운터 증가, 이메일 발송, INSERT)가 **승인 한 번에 두 번씩 실행됩니다.** 그리고 이건 조용히 일어납니다 — 로그가 두 줄 쌓인 걸 아무도 안 봅니다. 방어법은 interrupt 앞의 연산을 **멱등(idempotent)하게** 만드는 것입니다. `insert` 대신 `upsert`, `append` 대신 키 기반 `set`. 위 `askHuman` 처럼 승인 로직을 **에이전트 바깥**에 두면 이 문제를 통째로 피할 수 있습니다.

> 💡 **실무 팁**: 자동 승인자(사람 없이 규칙으로 승인)를 만든다면 **반드시 재개 횟수 상한**을 두세요. 모델이 `reject` 를 받고 계속 재시도하면 무한 루프가 됩니다. 사람이 붙은 CLI 는 사람이 지쳐서 멈추지만 자동 승인자는 영원히 돕니다. `solution.ts` 의 `MAX_RESUMES` 가 그 방어입니다.

---

## 정리

| 도구 | 무엇을 하나 | 언제 쓰나 |
|---|---|---|
| `interruptOn: { tool: true }` | 멈추고 사람에게 물어봄 | 판단이 필요한 위험 작업 |
| `interruptOn: { tool: false }` | 자동 승인 | 읽기 등 안전한 작업 (생략과 동일) |
| `allowedDecisions` | 허용 액션 제한 | 파괴적 작업에서 `edit` 빼기 |
| `when` | 조건부 개입 (`true` = 멈춤) | 승인 피로 방지 |
| `description` (함수) | 승인 화면 문구 생성 | diff 등 판단 근거 제시 |
| `permissions` | 정적 접근 차단 (내장 FS 도구만) | 애초에 못 하게 만들기 |
| `Command({ resume })` | 승인 결과로 재개 | `__interrupt__` 를 받은 뒤 |

**핵심 함정 3가지**

1. **checkpointer 없으면 `interruptOn` 은 무동작**: 에러도 경고도 없이 그냥 실행됩니다. "게이트를 달았다"고 믿는 상태가 가장 위험합니다.
2. **`permissions` 의 기본은 `allow`**: 허용 목록만 적으면 나머지가 전부 열립니다. 마지막 `{ paths: ["/**"], mode: "deny" }` 빗장이 필수. 그리고 `permissions: []` 는 "권한 없음" 이 아니라 **"무제한"** 입니다.
3. **재개 = 노드 재실행**: interrupt 앞의 부수효과가 중복됩니다. 멱등하게 만들거나 승인 로직을 에이전트 바깥에 두세요.

**설계 원칙**: `permissions` 로 **못 하게** 만들고 → 남은 것 중 위험한 것만 `interruptOn` 으로 **물어보고** → `when` 으로 물어보는 횟수를 **줄인다**. `execute` 는 승인이 아니라 **샌드박스**로 막습니다.

---

## 연습문제

1. `write_file` 에만 승인을 걸고 `read_file` / `ls` 는 자동 승인되는 에이전트를 만드세요. 그리고 `checkpointer` 를 뺐을 때 무슨 일이 벌어지는지 직접 확인하세요.
2. 문제 1의 에이전트에 파일 쓰기를 시키고, `__interrupt__` 에서 `actionRequests` 의 도구 이름·인자와 그 도구의 `allowedDecisions` 를 출력하세요. (힌트: `reviewConfigs` 는 `actionName` 으로 찾습니다)
3. 문제 2의 interrupt 를 `approve` 하지 말고 `edit` 으로 재개해서, 모델이 요청한 경로가 무엇이든 `/workspace/todo.md` 로 바꿔 실행되게 하세요. (힌트: `content` 를 잃지 않으려면?)
4. `write_file` 을 `reject` 하되 `message` 에 이유를 넣어 모델에게 피드백하세요. 재개 결과에 `__interrupt__` 가 또 있는지 확인하고, 왜 그런지 설명하세요.
5. 다음을 만족하는 `permissions` 배열을 작성하세요: `/workspace` 읽기·쓰기 허용 / `/config` 읽기만 허용 / 그 외 전부 금지. (힌트: 규칙 순서와 마지막 빗장)
6. `transfer_money` 도구에 승인을 걸되, 100,000원 이하는 자동 승인되고 초과분만 사람을 부르게 하세요. (힌트: `when` 은 `true` 가 "멈춤" 입니다)
7. 어떤 경로에도 쓰기를 못 하고 `/workspace` 만 읽을 수 있는 `reviewer` 서브에이전트를 정의하세요. 부모 `permissions` 를 상속하면 안 됩니다. (힌트: `permissions: []` 를 쓰면 안 되는 이유는?)
8. `__interrupt__` 가 사라질 때까지 모든 요청을 자동 `approve` 하는 루프를 작성하되, 최대 5회까지만 재개하고 초과하면 경고를 출력하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 10 — 장기 메모리와 스킬](../step-10-memory-skills/)

`permissions` 로 파일 접근을 통제하는 법을 배웠으니, 다음은 그 파일시스템을 **기억 장치**로 쓰는 법입니다. `/memories` 에 쓴 것은 대화가 끝나도 남습니다 — 그리고 그 순간 "누구의 기억인가" 라는 새로운 권한 문제가 생깁니다.

LangChain 코스에서 같은 주제를 에이전트 레벨에서 다룹니다: [LangChain Step 13 — Human-in-the-Loop](../../langchain/step-13-hitl/). `humanInTheLoopMiddleware` 를 직접 조립하는 관점이라, Deep Agent 의 `interruptOn` 이 내부적으로 무엇을 하는지 이해하는 데 도움이 됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(9-1 ~ 9-8)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 승인 흐름을 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `project/` 의 의존성으로 실행됩니다. `ANTHROPIC_API_KEY` 를 `.env` 에 넣고 `npx tsx docs/reference/deepagent/step-09-hitl-permissions/practice.ts` 로 실행하세요. 백엔드는 전부 `StateBackend`(메모리상 가상 FS)라서 실제 디스크는 건드리지 않습니다 — 승인을 실수로 눌러도 안전합니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[9-1] ~ [9-8]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다.

- `[9-1]` 은 **통제가 하나도 없는 에이전트**입니다. `__interrupt__: undefined` 와 이미 만들어진 파일 목록을 나란히 출력해, "승인을 안 걸면 그냥 실행된다" 를 눈으로 보여줍니다. 이후 절의 대조군입니다.
- `[9-2]` 는 `interruptOn` 의 네 가지 값 형태(`true` / `InterruptOnConfig` / `description` 함수 / `false`)를 **한 객체 안에 나란히** 놓았습니다. 실행 결과보다 코드 자체가 학습 대상인 블록입니다.
- `[9-3]` 이 이 파일의 심장입니다. `invoke` → `__interrupt__` 읽기 → `Command({ resume })` 3단계를 최소 코드로 보여줍니다. `req.value.actionRequests` 와 `req.value.reviewConfigs` 를 각각 출력하니, 두 배열의 구조 차이(호출 단위 vs 도구 단위)를 직접 비교하세요.
- `[9-4]` 는 `approve` / `edit` / `reject` 를 **각각 다른 thread_id 로 세 번** 실행합니다. 같은 프롬프트에 세 가지 결정을 내렸을 때 결과가 어떻게 갈리는지 보는 게 목적입니다. `edit` 블록의 `args: { ...original.args, file_path: ... }` 에서 스프레드를 지워보면 본문의 함정이 재현됩니다.
- `[9-7]` 의 `deploy` 도구는 `when: (request) => request.toolCall.args.env === "production"` 으로 조건부 승인을 겁니다. `when` 이 "허용 조건" 이 아니라 "개입 조건" 이라는 것을 코드로 확인하는 지점입니다.
- `[9-8]` 은 **stdin 입력이 필요합니다.** 터미널이 멈춘 것처럼 보이면 승인을 기다리는 중입니다. `y` / `n` / `e` 중 하나를 누르세요. CI 에서 이 파일을 통째로 돌리면 여기서 멈추므로, 자동 실행이 필요하면 `main()` 에서 `step9_8()` 을 주석 처리하세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 함수 본문이 비어 있으니, 거기에 직접 코드를 써 넣고 실행해 검증하면 됩니다.

- `[문제 1]` 의 "checkpointer 를 빼면 어떻게 되는지도 직접 확인해 보세요" 가 이 파일에서 가장 중요한 지시입니다. 답을 읽고 아는 것과, `__interrupt__` 가 `undefined` 로 찍히고 파일이 이미 만들어진 걸 직접 보는 것은 다릅니다. 꼭 두 버전을 모두 돌려보세요.
- `[문제 3]` 의 힌트 "원본 args 를 스프레드한 뒤 file_path 만 덮어쓰세요" 를 **일부러 무시하고** 먼저 틀려보길 권합니다. `args: { file_path: "..." }` 만 주면 무슨 일이 벌어지는지 보는 게 학습입니다.
- `[문제 5]` 는 코드를 쓰기 전에 **종이에 규칙 순서를 먼저 적어보세요.** first-match-wins 와 "기본 allow" 두 가지를 동시에 만족시켜야 해서, 순서를 잘못 잡으면 조용히 틀립니다.
- `[문제 6]` 의 `transferMoney` 도구는 **이미 정의되어 있습니다.** 여러분이 할 일은 도구를 만드는 게 아니라 `interruptOn` 설정을 짜는 것입니다.
- `[문제 7]` 의 "`permissions: []` 를 쓰면 안 되는 이유" 는 본문 9-6 의 함정 블록과 짝지어 읽으세요.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 1]` 의 해설이 이 파일에서 가장 깁니다. checkpointer 가 없을 때 왜 **에러가 안 나는지**(interrupt 는 상태 저장·복원 메커니즘이고, 저장할 곳이 없으면 되살릴 것도 없다)를 설명합니다.
- `[정답 2]` 는 `reviewConfigs.find(c => c.actionName === action.name)` 를 쓰는 이유를 못 박습니다. 인덱스로 짝지으면 같은 도구를 두 번 부른 순간 어긋난다는 것이 포인트입니다.
- `[정답 3]` 의 `args: { ...original.args, file_path: "/workspace/todo.md" }` 에서 스프레드가 핵심입니다. `editedAction` 은 병합이 아니라 **통째로 교체**이므로, 스프레드를 빼면 `content` 가 사라져 빈 파일이 만들어지거나 도구가 실패합니다.
- `[정답 5]` 는 `/config` 의 `write` deny 규칙을 `read` allow 규칙보다 **먼저** 둡니다. 사실 마지막 빗장이 어차피 잡아주므로 순서를 바꿔도 결과는 같지만, 의도를 코드에 남기는 편이 안전하다는 판단을 주석에 적어두었습니다.
- `[정답 6]` 의 `when: (request) => Number(request.toolCall.args.amount ?? 0) > 100000` 에서 `request.tool` 이 **`undefined`** 라는 점을 주석으로 경고합니다. `when` 은 `afterModel` 단계에서 평가되므로 `request.toolCall.args` 만 쓸 수 있습니다.
- `[정답 7]` 은 서브에이전트의 `permissions` 가 부모를 **교체**하므로 마지막 빗장을 직접 넣어야 한다는 것과, `permissions: []` 가 "무제한" 이라는 반직관을 다시 짚습니다.
- `[정답 8]` 이 실전에 가장 가까운 코드입니다. `while` 을 쓰는 이유, `MAX_RESUMES` 상한이 필요한 이유(자동 승인자 + reject 재시도 = 무한 루프), `decisions` 를 `actionRequests.map()` 으로 만들어 개수·순서를 자동으로 맞추는 이유 세 가지를 주석에 정리했습니다.

```ts file="./solution.ts"
```
