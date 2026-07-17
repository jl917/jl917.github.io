# Step 04 — 가상 파일시스템

> **학습 목표**
> - 파일시스템을 저장소가 아니라 **에이전트의 외부 기억장치**로 이해한다
> - `ls` `read_file` `write_file` `edit_file` `glob` `grep` 6종의 시그니처와 반환값을 정확히 안다
> - `read_file` 의 `offset`/`limit` 으로 큰 파일을 나눠 읽는 이유를 설명한다
> - `edit_file` 이 `write_file` 보다 나은 이유와, `old_string` 이 여러 곳에 매치될 때의 실패를 다룬다
> - `grep`/`glob` 로 **RAG 없이** 필요한 것만 가져오는 패턴을 구현한다
> - `StateBackend` 에 저장된 `files` 상태를 직접 들여다보고, 체크포인터 유무에 따른 소멸을 확인한다
> - `createFilesystemMiddleware` 를 일반 `createAgent` 에 붙인다
>
> **선행 스텝**: [Step 03 — 계획 도구 (write_todos)](../step-03-planning-todos/)
> **예상 소요**: 75분

[Step 03](../step-03-planning-todos/) 에서 계획이 모델의 **주의**를 붙잡는 장치라는 걸 봤습니다. 이번엔 **기억**입니다.

계획을 아무리 잘 세워도, 작업 결과물 자체가 컨텍스트 창을 넘치면 에이전트는 무너집니다. 로그 파일 하나가 20만 자라면 계획이고 뭐고 없습니다. 여기서 Deep Agent 의 두 번째 기둥이 등장합니다 — **가상 파일시스템**입니다.

이름 때문에 오해하기 쉬운데, 이건 "에이전트가 파일을 다룰 수 있게 해주는 기능"이 **아닙니다.** 그건 부수효과입니다. 진짜 목적은 **컨텍스트 오프로딩(context offloading)** — 지금 당장 필요 없는 정보를 컨텍스트 밖으로 내보내 두고, 필요할 때만 다시 가져오는 것입니다. 사람으로 치면 노트에 적어 두고 머리에서 지우는 것과 같습니다. 이 관점을 잡고 시작하세요.

---

## 4-1. 컨텍스트 오프로딩 — 파일시스템은 외부 기억장치다

문제 상황부터 봅시다. 로그 2000줄을 반환하는 도구가 있습니다.

```ts
import { tool } from "langchain";
import * as z from "zod";

const fetchLogs = tool(
  async ({ service }) => {
    const lines: string[] = [];
    for (let i = 1; i <= 2000; i++) {
      const level = i % 97 === 0 ? "ERROR" : i % 13 === 0 ? "WARN" : "INFO";
      lines.push(
        `2026-07-17T10:${String(i % 60).padStart(2, "0")}:00Z [${level}] ` +
          `${service} request_id=req-${i} latency=${(i * 7) % 900}ms`,
      );
    }
    return lines.join("\n");
  },
  {
    name: "fetch_logs",
    description: "지정한 서비스의 최근 로그를 반환한다. 결과가 매우 길 수 있다.",
    schema: z.object({ service: z.string().describe("서비스 이름") }),
  },
);
```

파일시스템 없이 이 도구를 세 번 부르면 로그 6000줄이 **ToolMessage 로 대화에 영원히 박힙니다.** 대화가 이어지는 내내 매 턴 다시 전송됩니다. 이미 다 읽고 결론까지 낸 로그인데도 말이죠.

파일시스템이 있으면 흐름이 달라집니다.

```ts
import { createDeepAgent } from "deepagents";

const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [fetchLogs],
  systemPrompt: [
    "너는 로그 분석 담당자다.",
    "",
    "긴 도구 결과는 컨텍스트에 쌓아두지 말고 파일로 저장해라.",
    "그 다음 grep 으로 필요한 줄만 찾아 읽어라.",
  ].join("\n"),
});

const result = await agent.invoke({
  messages: [{
    role: "user",
    content:
      "payment 서비스 로그를 가져와서 /logs/payment.log 에 저장하고, " +
      "ERROR 가 몇 건인지 세어라. 로그 전문을 응답에 옮기지 마라.",
  }],
});

console.log(result.files);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
  실행 후 files (1개):
    /logs/payment.log  (139284자, mime=text/plain)

최종 응답:
 payment 서비스 로그를 /logs/payment.log 에 저장했습니다.
 ERROR 는 총 20건입니다.
```

로그 13만 자는 **파일 안**에 있고, 컨텍스트에는 "저장했다"는 한 줄과 "20건"이라는 결론만 남았습니다. 이것이 오프로딩입니다.

### 자동 오프로딩

Deep Agent 는 여기서 한 걸음 더 나갑니다. 프롬프트로 시키지 않아도 **도구 결과가 너무 길면 알아서 파일로 내립니다.**

| 임계값 | 기본값 | 동작 |
|---|---|---|
| 도구 결과 | **20,000 토큰** (약 80KB) | 결과를 백엔드에 저장하고, 컨텍스트엔 **파일 경로 + 앞 10줄 미리보기**만 남김 |
| 사용자 메시지 | **50,000 토큰** (약 200KB) | 같은 방식으로 파일로 내림 |
| 대화 전체 | 모델 창의 **85%** | 요약 미들웨어가 작동, 원본은 파일로 보존 |

이 값들은 `createFilesystemMiddleware` 의 `toolTokenLimitBeforeEvict` / `humanMessageTokenLimitBeforeEvict` 로 조절합니다(4-8).

> 💡 **실무 팁**: 오프로딩의 가치는 "한 번의 호출"이 아니라 **"긴 대화"** 에서 나옵니다. 도구 결과가 10만 자여도 한 번 쓰고 끝이면 큰 문제가 아닙니다. 하지만 대화가 20턴 이어지면 그 10만 자가 **20번 전송**됩니다. 오프로딩은 이 곱셈을 끊습니다. 그래서 도구 결과가 클수록, 대화가 길수록 이득이 커집니다.

> ⚠️ **함정 (이 스텝에서 가장 중요)**: **파일에 내려도 에이전트가 다시 읽으면 결국 컨텍스트에 들어옵니다.** 오프로딩은 정보를 "지우는" 게 아니라 **"필요할 때만 꺼내 쓰도록 미루는"** 것입니다. `read_file` 로 13만 자짜리 파일을 통째로 읽으면 오프로딩을 한 의미가 완전히 사라집니다 — 오히려 `write_file` 한 번 + `read_file` 한 번으로 **두 배**를 쓴 셈입니다. 파일에 저장하는 것만으로 안심하지 마세요. **어떻게 다시 읽는가**(`grep`, `offset`/`limit`)가 절반입니다. 4-3 과 4-5 가 이 문제를 다룹니다.

---

## 4-2. 도구 6종 상세

파일시스템 미들웨어가 등록하는 도구는 6종(+ 샌드박스 전용 `execute`)입니다. 모델 없이 직접 확인할 수 있습니다.

```ts
import { createFilesystemMiddleware, StateBackend } from "deepagents";

const mw = createFilesystemMiddleware({ backend: new StateBackend() });
console.log(mw.tools.map((t) => t.name));
```

**출력** (라이브러리 동작이므로 **결정적입니다**)

```
[ 'ls', 'read_file', 'write_file', 'edit_file', 'glob', 'grep', 'execute' ]
```

### 시그니처 표

| 도구 | 파라미터 | 기본값 | 반환값 |
|---|---|---|---|
| `ls` | `path: string` | `"/"` | 파일 목록 (줄바꿈 구분) |
| `read_file` | `file_path: string`<br>`offset: number`<br>`limit: number` | —<br>`0`<br>`100` | `cat -n` 형식 텍스트 블록 (또는 바이너리 블록) |
| `write_file` | `file_path: string`<br>`content: string` | —<br>`""` | `Successfully wrote to '<경로>'` |
| `edit_file` | `file_path: string`<br>`old_string: string`<br>`new_string: string`<br>`replace_all: boolean` | —<br>—<br>—<br>`false` | `Successfully replaced N occurrence(s) in '<경로>'` |
| `glob` | `pattern: string`<br>`path: string` | —<br>`"/"` | 매칭된 절대경로 목록 |
| `grep` | `pattern: string`<br>`path: string`<br>`glob: string \| null` | —<br>`"/"`<br>`null` | 파일별로 묶인 `  <줄번호>: <내용>` |
| `execute` | `command: string` | — | 명령 실행 결과 (**샌드박스 백엔드만**) |

모든 경로는 **`/` 로 시작**해야 합니다. 상대경로는 쓰지 않습니다.

### 반환 문자열은 결정적이다

모델 응답과 달리 도구의 반환 문자열은 라이브러리가 만드는 것이라 **정확히 예측 가능합니다.** 에러 문자열도 마찬가지입니다.

| 상황 | 반환 문자열 |
|---|---|
| 파일 없음 | `Error: File '<경로>' not found` |
| `edit_file` — 문자열 못 찾음 | `Error: String not found in file: '<old_string>'` |
| `edit_file` — 여러 곳 매치 | `Error: String '<old_string>' has multiple occurrences (appears N times) in file. Use replace_all=True to replace all instances, or provide a more specific string with surrounding context.` |
| `edit_file` — 빈 old_string | `Error: oldString cannot be empty when file has content` |
| `glob` — 매치 없음 | `No files found matching pattern '<pattern>'` |
| `grep` — 매치 없음 | `No matches found for pattern '<pattern>'` |
| `execute` — 샌드박스 아님 | `Error: Execution not available. This agent's backend does not support command execution (SandboxBackendProtocol). ...` |

> ⚠️ **함정**: 위 에러들은 **예외로 던져지지 않습니다.** 도구가 에러 **문자열을 정상 반환**하고, 그게 ToolMessage 로 모델에게 갑니다. 즉 여러분의 코드에서는 `try/catch` 로 잡히지 않고, 실행도 멈추지 않습니다. 모델이 그 문자열을 읽고 알아서 대처합니다 — **대처를 잘하면** 다행이고, 무시하고 넘어가면 [Step 03 의 거짓 completed](../step-03-planning-todos/#3-4-todo-상태-관찰) 와 똑같은 조용한 실패가 됩니다. 로그에서 `Error:` 로 시작하는 ToolMessage 를 모니터링하세요.

> 💡 **실무 팁**: `execute` 는 도구 목록에는 등록되지만, 백엔드가 `SandboxBackendProtocol` 을 구현하지 않으면 **모델에게 노출되기 전에 걸러집니다.** 기본값인 `StateBackend` 는 샌드박스가 아니므로 `execute` 는 실제로 보이지 않습니다. 위 출력에 `execute` 가 있다고 해서 "우리 에이전트가 셸을 쓸 수 있구나"라고 오해하지 마세요. 백엔드 이야기는 [Step 05](../step-05-backends/) 에서 이어집니다.

---

## 4-3. `read_file` 의 offset / limit

`read_file` 의 기본 `limit` 은 **100줄**입니다. 이 기본값이 왜 이렇게 작은지가 이 절의 핵심입니다.

라이브러리가 모델에게 주는 `read_file` 의 도구 설명 원문 일부입니다(**결정적** — 라이브러리 상수).

```
Usage:
- By default, it reads up to 100 lines starting from the beginning of the file
- **IMPORTANT for large files and codebase exploration**: Use pagination with offset and limit parameters to avoid context overflow
  - First scan: read_file(file_path, limit=100) to see file structure
  - Read more sections: read_file(file_path, offset=100, limit=200) for next 200 lines
  - Only omit limit (read full file) when necessary for editing
- Results are returned using cat -n format, with line numbers starting at 1
```

"**avoid context overflow**" — 목적이 명시되어 있습니다. `read_file` 은 파일을 읽는 도구가 아니라 **컨텍스트를 아끼며 파일을 훔쳐보는 도구**입니다.

### 반환 형식

결과는 `cat -n` 형식입니다. 줄 번호가 6칸 우측 정렬되고 탭이 붙습니다.

```
     1	2026-07-17T10:01:00Z [INFO] payment request_id=req-1 latency=7ms
     2	2026-07-17T10:02:00Z [INFO] payment request_id=req-2 latency=14ms
     3	2026-07-17T10:03:00Z [INFO] payment request_id=req-3 latency=21ms
```

한 줄이 **5,000자를 넘으면** 여러 줄로 쪼개지고 `5.1`, `5.2` 같은 연속 표시가 붙습니다. 이때 **쪼개진 줄들이 각각 limit 에 카운트됩니다** — 미니파이된 JS 파일 한 줄이 `limit: 100` 을 통째로 잡아먹을 수 있다는 뜻입니다.

### 나눠 읽기 유도

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [fetchLogs],
  systemPrompt: [
    "너는 로그 분석 담당자다.",
    "",
    "큰 파일은 절대 통째로 읽지 마라.",
    "read_file 의 limit 으로 먼저 앞부분만 훑어 구조를 파악하고,",
    "필요한 구간만 offset 으로 짚어 읽어라.",
  ].join("\n"),
});
```

**출력 예시** (모델이 선택한 인자이므로 매번 다릅니다)

```
read_file 호출 인자: {"file_path":"/logs/payment.log","limit":20}
```

2000줄짜리 파일에서 20줄만 읽어 형식을 파악했습니다.

### 비용 감각

| 읽기 방식 | 대략 토큰 | 비고 |
|---|---|---|
| 전체 (2000줄) | ~35,000 | 자동 오프로딩 임계값(20,000)을 넘어 다시 파일로 밀려남 |
| `limit: 100` (기본) | ~1,800 | 구조 파악에 충분 |
| `limit: 20` | ~350 | 형식만 볼 때 |
| `grep` 로 ERROR 만 | ~350 | 20줄만 매치 (4-5) |

> ⚠️ **함정**: `limit` 을 **생략하면 기본값 100** 이 적용되지 `무제한`이 되지 않습니다. 반대로 큰 값(`limit: 99999`)을 명시하면 정말로 다 읽어 컨텍스트가 터집니다. 그런데 도구 설명에는 `"Only omit limit (read full file) when necessary for editing"` 이라고 적혀 있어 마치 생략 시 전체를 읽는 것처럼 읽힙니다 — **모델이 이 문장에 낚여 큰 limit 을 명시하는 경우가 실제로 있습니다.** 파일 크기를 모를 땐 `ls` 로 먼저 크기를 보게 하거나, 프롬프트에서 "limit 은 항상 200 이하로"처럼 상한을 못 박으세요.

> 💡 **실무 팁**: `offset` 은 **0-indexed** 인데 출력의 줄 번호는 **1부터** 시작합니다. `offset: 100` 은 "101번째 줄부터"입니다. 모델이 `grep` 으로 "1523번 줄에 ERROR" 를 찾은 뒤 그 주변을 읽으려 할 때 이 off-by-one 이 종종 나옵니다. 정확히 맞출 필요는 없고 `offset: 1500, limit: 50` 처럼 넉넉히 잡으면 됩니다. 정밀하게 짚으려 하지 말고 **범위로 긁는 편**이 안전합니다.

---

## 4-4. `edit_file` 의 문자열 치환

파일을 고치는 방법은 두 가지입니다.

| | `write_file` | `edit_file` |
|---|---|---|
| 동작 | 전체 덮어쓰기 | 문자열 치환 |
| 보내는 토큰 | **파일 전체** | **바뀌는 부분만** |
| 5000자 파일에서 한 줄 수정 | ~5000자 전송 | ~50자 전송 |
| 사고 위험 | 나머지 내용 소실 | 낮음 |

`edit_file` 의 도구 설명 원문(**결정적**)입니다.

```
Performs exact string replacements in files.

Usage:
- You must read the file before editing. This tool will error if you attempt an edit without reading the file first.
- When editing, preserve the exact indentation (tabs/spaces) from the read output. Never include line number prefixes in old_string or new_string.
- ALWAYS prefer editing existing files over creating new ones.
```

첫 줄이 중요합니다 — **읽지 않고 편집하면 에러입니다.**

### 실행

```ts
const result = await agent.invoke({
  messages: [{
    role: "user",
    content: [
      "1. /docs/api.md 에 아래 내용을 써라:",
      "",
      "# API 가이드",
      "",
      "## 인증",
      "인증은 API_KEY 헤더로 한다.",
      "",
      "## 요청",
      "요청은 JSON 으로 보낸다.",
      "",
      "2. 그 다음 '인증은 API_KEY 헤더로 한다.' 를",
      "   '인증은 Bearer 토큰으로 한다.' 로 바꿔라.",
    ].join("\n"),
  }],
});
```

**출력 예시** (도구 반환 문자열은 **형식이 결정적입니다**)

```
  [write_file] Successfully wrote to '/docs/api.md'
  [edit_file] Successfully replaced 1 occurrence(s) in '/docs/api.md'

/docs/api.md 내용:
# API 가이드

## 인증
인증은 Bearer 토큰으로 한다.

## 요청
요청은 JSON 으로 보낸다.
```

`occurrence(s)` 앞의 숫자에 주목하세요. 몇 곳이 바뀌었는지 알려줍니다. 이 숫자가 예상과 다르면 뭔가 잘못된 것입니다.

> ⚠️ **함정 (자주 발생)**: `old_string` 이 **여러 곳에 매치되면 실패합니다.** `replace_all: false`(기본값)일 때 매치가 2개 이상이면 아래 에러가 납니다.
>
> ```
> Error: String 'JSON' has multiple occurrences (appears 3 times) in file.
> Use replace_all=True to replace all instances, or provide a more specific string
> with surrounding context.
> ```
>
> 이건 **의도된 안전장치**입니다. "어느 것을 바꿀지 모르겠으니 안 바꾸겠다"는 뜻이고, 잘못된 곳을 고치는 것보다 훨씬 낫습니다. 해법은 두 가지입니다.
> - 전부 바꾸려면 → `replace_all: true`
> - 하나만 바꾸려면 → **주변 문맥을 포함해** `old_string` 을 유일하게 만들기 (`"## 인증\n인증은 API_KEY"` 처럼)
>
> 에러 메시지의 `replace_all=True` 는 파이썬 표기가 그대로 남은 것입니다. TypeScript 에서는 `replace_all: true` 입니다. 모델이 이 메시지를 읽고 `"True"` 라는 **문자열**을 보내려 시도하는 경우가 있는데, zod `boolean` 검증에서 걸립니다.

> ⚠️ **함정**: **`read_file` 없이 `write_file` 만 시키면 기존 내용이 날아갑니다.** `write_file` 은 확인 없이 덮어씁니다 — "이미 파일이 있는데요?" 같은 경고가 **없습니다.** 도구 설명이 `"Prefer to edit existing files (with the edit_file tool) over creating new ones"` 라고 부탁하지만 강제는 아닙니다. 특히 위험한 조합은 "이 문서에 섹션 하나 추가해줘"입니다 — 모델이 `edit_file` 대신 `write_file` 로 "새로 쓴" 내용에는 원래 있던 다른 섹션이 빠져 있을 수 있고, **에러 없이** 조용히 소실됩니다. 프롬프트에 "기존 파일은 반드시 read_file 로 먼저 읽고 edit_file 로 수정한다"를 못 박으세요.

> 💡 **실무 팁**: `edit_file` 은 줄 번호 접두사를 **포함하면 안 됩니다.** `read_file` 결과는 `     3\t인증은...` 형식인데, 여기서 `     3\t` 를 빼고 `인증은...` 만 `old_string` 에 넣어야 합니다. 도구 설명에도 `"Never include line number prefixes in old_string or new_string"` 이라고 명시돼 있지만 모델이 종종 실수합니다. 그 결과는 `Error: String not found in file` 입니다 — 이 에러가 반복되면 줄 번호를 섞어 보내고 있을 가능성이 높습니다.

---

## 4-5. `grep` / `glob` — RAG 의 대안

여기가 이 스텝에서 가장 관점이 바뀌는 부분입니다.

"긴 문서에서 필요한 부분만 찾아 쓴다" 는 문제에 지금까지의 정석은 **RAG** 였습니다. 문서를 청크로 쪼개고, 임베딩하고, 벡터 DB 에 넣고, 질문을 임베딩해 유사도 검색을 하는 방식입니다. Deep Agent 는 다른 답을 냅니다 — **그냥 grep 하면 되지 않나?**

```ts
const result = await agent.invoke({
  messages: [{
    role: "user",
    content:
      "payment, order, shipping 세 서비스의 로그를 각각 " +
      "/logs/payment.log, /logs/order.log, /logs/shipping.log 에 저장해라. " +
      "그 다음 glob 으로 /logs 아래 파일 목록을 확인하고, " +
      "grep 으로 ERROR 가 있는 파일과 건수를 정리해라. " +
      "파일 전문을 읽지 마라.",
  }],
});
```

**출력 예시** (인자는 모델이 정하므로 매번 다릅니다)

```
  저장된 로그 (3개):
    /logs/payment.log  (139284자, mime=text/plain)
    /logs/order.log    (135284자, mime=text/plain)
    /logs/shipping.log (141284자, mime=text/plain)

  glob 인자: {"pattern":"*.log","path":"/logs"}
  grep 인자: {"pattern":"ERROR","path":"/logs","glob":"*.log"}
```

41만 자를 파일에 넣어 두고, `grep` 한 번으로 ERROR 줄 60개(약 1000토큰)만 가져왔습니다. **벡터 DB 도, 임베딩 모델도, 청킹 전략도 없습니다.**

### `glob` 패턴

| 패턴 | 의미 |
|---|---|
| `*.log` | 해당 디렉터리의 모든 `.log` |
| `**/*.ts` | 모든 하위 디렉터리의 `.ts` |
| `?` | 한 글자 |
| `/subdir/**/*.md` | `/subdir` 아래 모든 `.md` |

### `grep` 파라미터

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `pattern` | — | 찾을 **리터럴 문자열** |
| `path` | `"/"` | 검색 시작 경로 |
| `glob` | `null` | 대상 파일 필터 (`"*.log"`) |

출력은 파일별로 묶여 나옵니다.

```

/logs/payment.log:
  97: 2026-07-17T10:37:00Z [ERROR] payment request_id=req-97 latency=679ms
  194: 2026-07-17T10:14:00Z [ERROR] payment request_id=req-194 latency=458ms
```

> ⚠️ **함정 (중요)**: **`grep` 은 정규식이 아니라 리터럴 문자열을 찾습니다.** 도구 설명 원문에 이렇게 적혀 있습니다.
>
> ```
> Searches for literal text (not regex) and returns matching files or content based on output_mode.
> Special characters like parentheses, brackets, pipes, etc. are treated as literal characters, not regex operators.
> ```
>
> `grep` 이라는 이름 때문에 모델이 `"ERROR|WARN"` 같은 정규식을 보내는 일이 **자주** 있습니다. 결과는 에러가 아니라 `No matches found for pattern 'ERROR|WARN'` 입니다 — 파이프 문자가 들어간 줄을 진짜로 찾았고, 없었을 뿐입니다. **에러가 아니라 "없음"으로 나오기 때문에** 모델은 "ERROR 가 하나도 없구나"라고 결론 내리고 넘어갑니다. 조용한 오답의 전형입니다. 정규식이 필요하면 `grep` 을 두 번 부르게 하세요.
>
> 참고로 도구 설명에는 `output_mode` 가 언급되지만 **실제 스키마에는 없습니다**(`pattern`/`path`/`glob` 세 개뿐). 문서와 구현이 어긋난 부분이니 `output_mode` 를 쓰려 하지 마세요.

> 💡 **실무 팁 — grep 이냐 RAG 냐**:
>
> | | `grep`/`glob` | RAG (벡터 검색) |
> |---|---|---|
> | 인프라 | 없음 | 벡터 DB + 임베딩 모델 |
> | 검색 방식 | 정확한 문자열 일치 | 의미 유사도 |
> | `"ERROR"` 찾기 | ✅ 완벽 | △ 과잉 매치 |
> | `"결제가 실패하는 이유"` | ❌ 그런 문자열 없음 | ✅ 강점 |
> | 최신성 | 항상 최신 | 재색인 필요 |
> | 정확도 | 100% (찾으면 진짜 있는 것) | 유사도 임계값에 의존 |
>
> **코드·로그·구조화된 문서는 `grep` 이 압도적입니다.** 식별자와 에러 코드는 정확히 일치하는 문자열이니까요. 반대로 "고객이 화난 이유" 같은 의미 기반 질의는 여전히 RAG 가 맞습니다. Deep Agent 는 RAG 를 없애는 게 아니라, **RAG 가 필요 없는 경우가 생각보다 훨씬 많다**는 걸 보여줍니다. 실무에서는 먼저 grep 으로 되는지 보고, 안 되면 RAG 를 얹으세요. 순서를 거꾸로 하면 쓸데없이 벡터 DB 를 운영하게 됩니다. ([Step 16 — 검색과 RAG](../../langchain/step-16-retrieval-rag/) 와 함께 읽으면 좋습니다.)

---

## 4-6. StateBackend — 파일은 상태에 산다

여기서 "가상"의 의미가 드러납니다. **`/logs/payment.log` 는 여러분 디스크에 없습니다.** `ls` 로 확인해도 없습니다. 이 파일은 LangGraph **상태(state)** 안의 객체입니다.

```ts
console.log(result.files);
```

**출력 예시** (구조는 **결정적입니다**)

```
{
  '/logs/payment.log': {
    content: '2026-07-17T10:01:00Z [INFO] payment request_id=req-1 latency=7ms\n...',
    mimeType: 'text/plain',
    created_at: '2026-07-17T08:31:12.482Z',
    modified_at: '2026-07-17T08:31:12.482Z'
  }
}
```

타입은 이렇습니다.

```ts
type FilesRecord = Record<string, FileData>;

// 현재 형식 (v2)
interface FileDataV2 {
  content: string | Uint8Array;   // 텍스트는 string, 바이너리는 Uint8Array
  mimeType: string;               // "text/plain", "image/png" ...
  created_at: string;             // ISO 타임스탬프
  modified_at: string;
}

// 레거시 형식 (v1) — 오래된 상태에서 나올 수 있음
interface FileDataV1 {
  content: string[];              // ← 줄 배열! v2 와 다름
  created_at: string;
  modified_at: string;
}
```

> ⚠️ **함정**: `FileData` 는 **v1/v2 유니온**입니다. `content` 가 v1 에서는 `string[]`(줄 배열), v2 에서는 `string`(통 문자열)입니다. `files["/x.log"].content.length` 를 찍으면 v1 에서는 **줄 수**, v2 에서는 **글자 수**가 나옵니다 — 에러 없이 완전히 다른 의미의 숫자입니다. 상태를 직접 파싱한다면 반드시 분기하세요.
>
> ```ts
> const raw = files["/x.log"]?.content;
> const text = Array.isArray(raw) ? raw.join("\n") : raw;   // v1/v2 모두 처리
> ```

### 체크포인터가 없으면 파일도 없다

이게 가장 많이 데는 지점입니다.

```ts
// (A) 체크포인터 없음
const noCheckpoint = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: "너는 메모 담당자다.",
});

await noCheckpoint.invoke({
  messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }],
});

// 새 invoke — 완전히 새 상태에서 시작합니다
const r2 = await noCheckpoint.invoke({
  messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }],
});
```

**출력 예시**

```
(A) 체크포인터 X — 두 번째 호출:
  (files: 비어 있음)
  응답: /memo.txt 파일을 찾을 수 없습니다. (Error: File '/memo.txt' not found)
```

파일이 **사라졌습니다.** 첫 `invoke` 가 끝나는 순간 상태가 폐기됐기 때문입니다.

체크포인터를 주면 달라집니다.

```ts
import { MemorySaver } from "@langchain/langgraph";

const withCheckpoint = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  systemPrompt: "너는 메모 담당자다.",
  checkpointer: new MemorySaver(),
});

const config = { configurable: { thread_id: "step04-demo" } };

await withCheckpoint.invoke(
  { messages: [{ role: "user", content: "/memo.txt 에 '첫 번째 메모' 라고 저장해라." }] },
  config,
);

const r4 = await withCheckpoint.invoke(
  { messages: [{ role: "user", content: "/memo.txt 를 읽어서 내용을 알려줘." }] },
  config,   // ← 같은 thread_id
);
```

**출력 예시**

```
(B) 체크포인터 O — 두 번째 호출:
  files (1개):
    /memo.txt  (6자, mime=text/plain)
  응답: /memo.txt 의 내용은 "첫 번째 메모" 입니다.
```

`thread_id` 를 바꾸면 다시 안 보입니다.

```
(C) 다른 thread_id:
  (files: 비어 있음)
```

| 조건 | 한 번의 invoke 안 | invoke 사이 (같은 thread) | 다른 thread |
|---|---|---|---|
| 체크포인터 없음 | ✅ | ❌ | ❌ |
| `MemorySaver` | ✅ | ✅ | ❌ |
| `MemorySaver` + 프로세스 재시작 | ✅ | ❌ | ❌ |

> ⚠️ **함정 3연타**:
> 1. **체크포인터 없이 `thread_id` 만 주면 아무 일도 안 일어납니다.** 에러도 없습니다. `configurable: { thread_id: "abc" }` 를 정성껏 넘겨도 저장할 곳이 없으면 그냥 무시됩니다. "thread_id 를 줬으니 이어지겠지"는 틀렸습니다.
> 2. **`MemorySaver` 는 프로세스 메모리입니다.** 서버를 재시작하면 전부 날아갑니다. 이름이 "Saver" 라 저장되는 것 같지만 영속성이 **없습니다.** 프로덕션에서는 DB 기반 체크포인터를 쓰세요.
> 3. **StateBackend 는 스레드 격리입니다.** 사용자 A 의 파일을 사용자 B 가 볼 수 없습니다 — 보안상 좋지만, "지난 대화에서 만든 파일"을 꺼내려는 의도라면 실패합니다. 스레드를 넘는 영속성이 필요하면 `StoreBackend` 나 `CompositeBackend` 가 필요합니다([Step 05](../step-05-backends/)).

> 💡 **실무 팁**: 이 성질은 **버그가 아니라 설계**입니다. 파일시스템은 "작업용 스크래치 공간"으로 의도된 것이라, 작업이 끝나면 같이 사라지는 게 자연스럽습니다. 영구 저장이 필요한 산출물은 (1) 최종 응답에 담아 반환하거나 (2) `StoreBackend` 로 라우팅하거나 (3) 진짜 파일로 쓰는 커스텀 도구를 주세요. **"에이전트가 파일을 썼으니 어딘가 저장됐겠지"는 위험한 가정입니다.**

---

## 4-7. 실전 패턴 — 오프로딩 후 요약만 남기기

지금까지의 조각을 하나로 묶습니다. 핵심 규칙은 **"원본은 파일로, 컨텍스트엔 요약만"** 입니다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [fetchLogs],
  systemPrompt: [
    "너는 로그 분석 담당자다.",
    "",
    "작업 절차:",
    "1. fetch_logs 결과는 즉시 /logs/<서비스>.log 에 저장한다.",
    "2. grep 으로 ERROR 줄만 찾는다.",
    "3. 분석 결과를 /reports/summary.md 에 저장한다.",
    "4. 최종 응답에는 요약 3줄과 리포트 파일 경로만 적는다.",
    "",
    "원본 로그를 응답에 옮기지 마라.",
  ].join("\n"),
});
```

**출력 예시** (매번 다릅니다)

```
  최종 files (3개):
    /logs/payment.log     (139284자, mime=text/plain)
    /logs/order.log       (135284자, mime=text/plain)
    /reports/summary.md   (412자, mime=text/plain)

/reports/summary.md:
# 로그 분석 요약

## payment
- ERROR 20건 / 2000줄
- 최대 지연: 679ms

## order
- ERROR 20건 / 2000줄
- 최대 지연: 693ms

최종 응답 (짧아야 정상):
 두 서비스 모두 ERROR 20건씩 발견했습니다.
 상세 리포트는 /reports/summary.md 에 저장했습니다.

마지막 모델 호출 input_tokens: 4820
```

원본 27만 자가 파일에 있는데 마지막 모델 호출의 입력은 5천 토큰 남짓입니다. 파일시스템이 없었다면 이 숫자는 7만을 넘었을 겁니다.

### 패턴 정리

| 단계 | 행동 | 컨텍스트에 남는 것 |
|---|---|---|
| 1. 수집 | 도구 결과 → `write_file` | `Successfully wrote to '/logs/x.log'` (한 줄) |
| 2. 탐색 | `grep` / `glob` | 매치된 줄만 (수십 줄) |
| 3. 정밀 조사 | `read_file(offset, limit)` | 해당 구간만 |
| 4. 산출 | `write_file` 로 리포트 | `Successfully wrote to ...` (한 줄) |
| 5. 응답 | 요약 + 파일 경로 | 몇 줄 |

> 💡 **실무 팁**: 4번 단계(리포트도 파일로 저장)를 빠뜨리기 쉽습니다. 리포트를 최종 응답에만 담으면, 대화가 이어질 때 그 리포트 전문이 **매 턴 다시 전송**됩니다. 파일로 내려 두고 "리포트는 /reports/summary.md 에 있다"고만 말하면, 나중에 필요할 때 다시 읽으면 됩니다. **에이전트가 만든 산출물도 오프로딩 대상**이라는 것을 잊지 마세요.

> ⚠️ **함정**: 이 패턴은 **서브에이전트와 조합할 때 무너질 수 있습니다.** 서브에이전트는 부모와 컨텍스트가 격리되지만 **파일시스템(상태)은 공유**합니다. 즉 서브에이전트가 `/logs/payment.log` 를 읽으면 그 내용은 **서브에이전트의** 컨텍스트에 들어갑니다 — 부모는 깨끗하게 유지되지만 서브에이전트가 터질 수 있습니다. 격리가 "안전"을 뜻하지는 않습니다. 자세한 건 [Step 06](../step-06-subagents/) 에서 다룹니다.

---

## 4-8. `createFilesystemMiddleware` 를 일반 `createAgent` 에

계획과 마찬가지로 파일시스템도 미들웨어 하나로 떼어 쓸 수 있습니다.

```ts
import { createFilesystemMiddleware, StateBackend } from "deepagents";
import { createAgent } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [fetchLogs],
  systemPrompt: "너는 로그 분석 담당자다. 긴 결과는 파일로 저장하고 grep 으로 찾아 읽어라.",
  middleware: [
    createFilesystemMiddleware({
      backend: new StateBackend(),
    }),
  ],
});
```

`createDeepAgent` 는 `await` 이 필요했지만 `createAgent` 는 아닙니다.

### 옵션

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `backend` | `StateBackend` | 저장 위치 ([Step 05](../step-05-backends/)) |
| `tools` | 전체 | 노출할 도구 **허용목록**. `read_file` 은 **필수 포함** |
| `systemPrompt` | 자동 생성 | 파일시스템 안내 프롬프트를 **대체** |
| `customToolDescriptions` | — | 도구별 설명 덮어쓰기 |
| `toolTokenLimitBeforeEvict` | `20000` | 도구 결과 자동 오프로딩 임계값 |
| `humanMessageTokenLimitBeforeEvict` | `50000` | 사용자 메시지 자동 오프로딩 임계값 |
| `permissions` | 없음 | 경로별 접근 제어 ([Step 05](../step-05-backends/)) |

### 읽기 전용 파일시스템

```ts
const readOnly = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [],
  middleware: [
    createFilesystemMiddleware({
      backend: new StateBackend(),
      tools: ["read_file", "ls", "glob", "grep"],   // write_file/edit_file 제외
    }),
  ],
});
```

**출력** (허용목록 적용 후 노출되는 도구 — **결정적**)

```
ls, read_file, glob, grep
```

`write_file`/`edit_file` 이 빠졌고, `execute` 도 없습니다.

> ⚠️ **함정**: `tools` 허용목록에 **`read_file` 을 반드시 포함**해야 합니다. 빼면 미들웨어 **생성 시점에 즉시 예외가 던져집니다.**
>
> ```
> Error: read_file must be included in tools; it is required by FilesystemMiddleware
> ```
>
> 이건 이 스텝에서 만나는 **가장 친절한 실패**입니다 — 모델을 부르기도 전에, 개발 중에 바로 터집니다. 라이브러리가 이렇게까지 막는 이유는 반대 상황이 지독하기 때문입니다. 자동 오프로딩이 작동하면 컨텍스트에는 "파일 경로 + 앞 10줄"만 남는데, `read_file` 이 없으면 모델은 나머지를 **영영 볼 방법이 없습니다.** 결과가 사라진 게 아니라 **꺼낼 손이 없는** 상태이고, 이건 에러가 아니라 "답이 조용히 부실해지는" 형태로 나타나 디버깅이 매우 어렵습니다. 그래서 **"읽기 전용"은 `["read_file", "ls", "glob", "grep"]` 이지 `["ls", "glob", "grep"]` 이 아닙니다.**

> 💡 **실무 팁**: 어떤 조합을 쓸지 정리하면 이렇습니다.
>
> | 필요한 것 | 선택 |
> |---|---|
> | 계획만 | `createAgent` + `todoListMiddleware()` |
> | 파일시스템만 | `createAgent` + `createFilesystemMiddleware()` |
> | 계획 + 파일 | 두 미들웨어를 배열에 나란히 |
> | 전부 (+ 서브에이전트, 요약, 캐싱) | `createDeepAgent()` |
>
> 미들웨어는 **도구 스키마와 프롬프트를 매 턴 컨텍스트에 얹습니다.** 파일시스템 도구 6종의 설명은 합쳐서 수천 토큰입니다. 파일을 안 쓰는 에이전트에 이걸 켜 두는 건 [Step 03 의 계획 오버헤드](../step-03-planning-todos/#3-7-언제-계획이-방해가-되나) 와 똑같은 낭비입니다.

---

## 4-9. 종합

계획([Step 03](../step-03-planning-todos/))과 파일시스템을 함께 씁니다. `createDeepAgent` 는 둘 다 기본으로 켭니다.

```ts
const agent = await createDeepAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [fetchLogs],
  systemPrompt: [
    "너는 장애 분석 담당자다.",
    "",
    "작업 규칙:",
    "- 3단계 이상 걸리는 일은 계획을 먼저 세운다.",
    "- 긴 도구 결과는 즉시 파일로 내린다. 컨텍스트에 쌓지 않는다.",
    "- 파일은 grep/read_file(offset,limit) 으로 필요한 부분만 읽는다.",
    "- 최종 산출물은 /reports/incident.md 에 저장한다.",
    "- 최종 응답은 5줄 이내로 요약한다.",
  ].join("\n"),
});
```

**출력 예시** (매번 다릅니다)

```
[계획 0/4]
[계획 1/4]
[계획 2/4]
[계획 3/4]
[계획 4/4]

  최종 files (4개):
    /logs/payment.log      (139284자, mime=text/plain)
    /logs/order.log        (135284자, mime=text/plain)
    /logs/shipping.log     (141284자, mime=text/plain)
    /reports/incident.md   (683자, mime=text/plain)

최종 응답:
 세 서비스 로그를 분석했습니다. 각 서비스당 ERROR 20건이 발견되었습니다.
 가장 느린 요청은 shipping 의 req-1897 (893ms) 입니다.
 상세 리포트: /reports/incident.md
```

계획이 **무엇을 할지**를 붙잡고, 파일시스템이 **결과물을 컨텍스트 밖에** 둡니다. 이 둘이 Deep Agent 의 앞 두 기둥입니다.

---

## 정리

| 도구 | 파라미터 (기본값) | 핵심 |
|---|---|---|
| `ls` | `path` (`"/"`) | 탐색의 시작 |
| `read_file` | `file_path`, `offset` (`0`), `limit` (`100`) | **통째로 읽지 마라** |
| `write_file` | `file_path`, `content` (`""`) | **확인 없이 덮어쓴다** |
| `edit_file` | `file_path`, `old_string`, `new_string`, `replace_all` (`false`) | 토큰 절약, 읽고 나서만 |
| `glob` | `pattern`, `path` (`"/"`) | 파일 찾기 |
| `grep` | `pattern`, `path` (`"/"`), `glob` (`null`) | **리터럴 문자열만** |
| `execute` | `command` | 샌드박스 백엔드 전용 |

| 개념 | 내용 |
|---|---|
| 상태 키 | `files: Record<string, FileData>` |
| 기본 백엔드 | `StateBackend` (LangGraph 상태, 스레드 격리) |
| 미들웨어 | `createFilesystemMiddleware(options)` from `"deepagents"` |
| 자동 오프로딩 | 도구 결과 20,000 토큰 / 사용자 메시지 50,000 토큰 |
| 요약 트리거 | 모델 창의 85% |

**핵심 함정 3가지**

1. **파일에 내려도 다시 읽으면 컨텍스트에 들어온다.** 오프로딩은 정보를 지우는 게 아니라 **미루는** 것입니다. `read_file` 로 통째로 읽으면 `write_file` + `read_file` 로 두 배를 쓴 셈입니다. **어떻게 다시 읽는가**(`grep`, `offset`/`limit`)가 절반입니다.
2. **체크포인터 없으면 파일도 안 남는다.** `thread_id` 만 줘도 에러가 나지 않고 조용히 무시됩니다. `MemorySaver` 조차 프로세스 재시작이면 전멸입니다. 가상 파일시스템은 진짜 디스크가 아닙니다.
3. **`grep` 은 정규식이 아니다.** `"ERROR|WARN"` 을 보내면 에러가 아니라 `No matches found` 가 나옵니다. 모델은 "ERROR 가 없구나"라고 결론 내리고 넘어갑니다 — 조용한 오답입니다.

**보너스 함정**: `edit_file` 의 `old_string` 이 여러 곳에 매치되면 **실패합니다**(안전장치). `replace_all: true` 를 쓰거나 주변 문맥을 포함해 유일하게 만드세요.

---

## 연습문제

1. `fetchLogs` 도구를 준 에이전트 두 개를 만드세요. 하나는 파일시스템 있음(`createDeepAgent`), 하나는 없음(`createAgent`, 미들웨어 없음). 같은 로그 분석 작업을 시키고 **마지막 AI 메시지의 `usage_metadata.input_tokens`** 를 비교하세요.
2. `createFilesystemMiddleware({ backend: new StateBackend() })` 에서 도구 목록과 각 도구의 파라미터를 출력하세요. `read_file` 의 `offset`/`limit` **기본값**을 코드로 확인하세요. (모델 호출 없이 가능합니다)
3. 에이전트에게 2000줄 로그를 저장시킨 뒤, `read_file` 을 **`limit` 없이** 부르게 하는 프롬프트와 **`limit: 20`** 으로 부르게 하는 프롬프트를 각각 작성해 `input_tokens` 차이를 재세요.
4. `/docs/api.md` 에 `"JSON"` 이라는 단어가 3번 들어가는 문서를 쓰고, `edit_file` 로 `old_string: "JSON"` 을 치환하게 시키세요. 어떤 에러가 나나요? **에러 메시지 전문**을 기록하고, 두 가지 방법으로 해결하세요.
5. 에이전트에게 `"ERROR|WARN"` 정규식으로 grep 하도록 유도하고, 결과를 관찰하세요. 에러가 나나요, 아니면 "없음"이 나오나요? 모델은 이후 어떻게 행동하나요?
6. `MemorySaver` 를 붙인 에이전트에서 (A) 같은 `thread_id` 로 2회 호출, (B) 다른 `thread_id` 로 호출, (C) 체크포인터 없이 2회 호출 — 세 경우의 `files` 를 비교하세요.
7. `createFilesystemMiddleware({ tools: [...] })` 로 **`read_file` 을 뺀** 허용목록을 만들어 보세요. 어떻게 되나요? 왜 `read_file` 이 필수인지 설명하세요.
8. 4-7 의 오프로딩 패턴을 구현하되, 리포트를 **파일로 저장하는 버전**과 **최종 응답에만 담는 버전**을 만드세요. 이어서 후속 질문을 한 번 더 던져 두 버전의 `input_tokens` 를 비교하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 05 — 백엔드와 권한](../step-05-backends/)

이번 스텝의 파일은 전부 `StateBackend` — 상태 안에 살다가 사라졌습니다. 다음 스텝에서는 **진짜 디스크**(`FilesystemBackend`), **스레드를 넘는 저장소**(`StoreBackend`), **경로별 라우팅**(`CompositeBackend`) 을 배우고, 에이전트가 읽고 쓸 수 있는 곳을 **권한으로 제한**하는 법을 다룹니다. 파일시스템에 진짜 힘이 생기는 만큼 진짜 위험도 생깁니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(4-1 ~ 4-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 눈으로 확인하고, `exercise.ts` 의 8개 문제를 직접 푼 뒤, `solution.ts` 로 채점하는 흐름입니다.

실행은 프로젝트 루트에서 `npx tsx docs/reference/deepagent/step-04-filesystem/practice.ts` 입니다. `ANTHROPIC_API_KEY` 환경변수가 필요하며, `project/.env.example` 을 `.env` 로 복사해 채우면 `import "dotenv/config"` 가 읽어 갑니다. OpenAI 를 쓰려면 모델 문자열을 `"openai:gpt-5.5"` 로 바꾸고 `OPENAI_API_KEY` 를 설정하세요.

**검증 버전**: `deepagents@1.11.0`, `langchain@1.5.3`, `@langchain/core@1.2.3`, `@langchain/langgraph@1.4.8`

### practice.ts

본문 강의를 따라가며 실행할 예제를 `[4-1] ~ [4-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 대응합니다.

- `printFiles` 헬퍼가 `FileData` 의 **v1/v2 유니온을 분기**합니다. `Array.isArray(raw) ? raw.join("\n") : raw` 한 줄이 본문 4-6 의 함정을 코드로 방어한 것입니다. 상태를 직접 파싱할 때 이 분기를 빠뜨리면 "글자 수"와 "줄 수"를 헷갈리게 됩니다.
- `fetchLogs` 는 2000줄(약 13만 자)을 만들어 냅니다. `i % 97 === 0` 이면 ERROR, `i % 13 === 0` 이면 WARN 이므로 **ERROR 는 정확히 20건**입니다. 모델의 grep 결과가 20이 아니면 뭔가 잘못된 것이니 채점 기준으로 쓸 수 있습니다.
- `[4-2]` 는 이 파일에서 **모델을 호출하지 않는 유일한 블록**입니다. 미들웨어에서 도구를 꺼내 스키마만 출력하므로 API 키 없이도 돌아갑니다. `read_file`/`write_file`/`edit_file` 이 `z.preprocess` 로 감싸여 있어 `.shape` 가 한 겹 안쪽(`_def.schema.shape`)에 있는 것도 여기서 확인합니다.
- `[4-6]` 이 이 파일의 핵심입니다. (A) 체크포인터 없음 → (B) `MemorySaver` + 같은 `thread_id` → (C) 다른 `thread_id` 를 **연달아 실행해 세 결과를 나란히** 보여줍니다. (A) 에서 `files` 가 비어 있고 (B) 에서만 살아남는 것이 본문 함정의 실물입니다.
- `[4-8]` 의 두 번째 에이전트(`readOnly`)는 만들기만 하고 실행하지 않습니다(`void readOnly`). 허용목록에 `read_file` 이 들어가 있는 것을 눈으로 확인하는 용도입니다 — 빼면 어떻게 되는지는 연습문제 7번에서 다룹니다.
- `main()` 이 `step4_1` 부터 `step4_9` 까지 전부 실행합니다. **API 호출이 15회 이상, 긴 로그를 여러 번 처리**하므로 토큰이 꽤 나갑니다. 처음에는 필요한 절만 남기세요.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 파일입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 구현부가 비어 있습니다.

- `[문제 2]` 와 `[문제 7]` 은 **모델을 호출하지 않습니다** (API 키 불필요). 미들웨어의 구조만 들여다보는 문제라 부담 없이 먼저 풀어도 됩니다.
- `[문제 1]` `[문제 3]` `[문제 8]` 은 전부 `input_tokens` 를 비교하는 문제입니다. `getUsage` 헬퍼를 미리 만들어 두었으니 에이전트 설정만 채우면 됩니다.
- `[문제 4]` 는 `old_string` 이 여러 곳에 매치되는 상황을 **일부러 만드는** 문제입니다. `JSON` 이 3번 나오는 문서가 이미 준비되어 있습니다. 에러 메시지 전문을 그대로 옮겨 적는 것이 과제의 절반입니다.
- `[문제 5]` 가 가장 교훈적입니다. 정규식 grep 이 **에러가 아니라 "없음"으로** 돌아오고, 모델이 그걸 어떻게 해석하는지 관찰하세요. 답을 보기 전에 "모델이 ERROR 가 없다고 결론 내릴까?"를 먼저 예측해 보세요.
- 파일을 그대로 실행하면 대부분 아무것도 출력되지 않습니다. 정상입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 2]` 는 `read_file` 의 기본값을 **코드로 증명**하되, **두 가지 방법의 품질 차이**를 함께 보여줍니다. 방법 1 은 `schema._def.out.shape` 로 zod 내부를 뒤지는 것인데, `ZodPreprocess` 가 `pipe(in → out)` 구조라 `_def.out` 에 있습니다 — zod v3 에서는 경로도 달랐고 `defaultValue` 가 함수였습니다(v4 는 값). 즉 **버전이 오르면 깨지는 코드**입니다. 방법 2 는 `schema.parse({ file_path: "/a.txt" })` 로 최소 입력을 파싱해 `{ offset: 0, limit: 100 }` 이 채워져 돌아오는 것을 보는 것 — 공개 API 만 쓰므로 안전합니다. "문서를 믿지 말고 확인하라, 단 확인도 견고하게 하라"가 이 문제의 교훈입니다.
- `[정답 4]` 의 에러 메시지에 `replace_all=True` 라는 **파이썬 표기**가 그대로 남아 있는 것이 포인트입니다. TypeScript 에서는 `replace_all: true` 인데, 모델이 이 메시지를 읽고 `"True"` 라는 **문자열**을 보내려다 zod boolean 검증에 걸리는 일이 실제로 있습니다. 해결책 두 가지(`replace_all: true` / 문맥 포함)를 모두 구현해 두었습니다.
- `[정답 5]` 가 이 파일의 하이라이트입니다. `"ERROR|WARN"` grep 은 `No matches found for pattern 'ERROR|WARN'` 을 반환합니다 — **에러가 아닙니다.** 파이프가 들어간 줄을 진짜로 찾았고 없었을 뿐입니다. 모델은 이걸 "ERROR 가 없다"로 읽고 태연히 "장애 없음"이라고 보고합니다. 정답 코드는 `"ERROR"` 만으로 다시 grep 해 **실제로는 20건이 있었다**는 것을 나란히 보여줍니다.
- `[정답 7]` 은 이 스텝에서 **유일하게 "착한 실패"** 를 보여주는 문제입니다. `read_file` 을 뺀 허용목록은 미들웨어 생성 시점에 `read_file must be included in tools; it is required by FilesystemMiddleware` 로 **즉시 터집니다.** 모델을 부르기도 전에, 개발 중에 잡힙니다. 정답 주석은 라이브러리가 왜 이렇게까지 막는지를 설명합니다 — 막지 않았다면 자동 오프로딩 후 "앞 10줄만 보고 답을 지어내는" 조용한 실패가 됐을 것이고, 그건 에러 없이 답만 부실해지므로 추적이 거의 불가능합니다.
- `[정답 8]` 은 오프로딩의 진짜 가치가 **후속 질문**에서 드러난다는 걸 보여줍니다. 첫 호출만 보면 두 버전의 차이가 작지만, 후속 질문을 던지면 "응답에만 담은" 버전은 리포트 전문을 매 턴 다시 전송합니다. 대화가 길어질수록 격차가 벌어집니다.

```ts file="./solution.ts"
```
