# Step 06 — 서브에이전트

> **학습 목표**
> - 서브에이전트의 목적이 "일 나눠하기"가 아니라 **부모 컨텍스트 보호**라는 것을 이해한다
> - `task` 도구의 동작(프롬프트 하나 넣고 → 결과 문자열 하나 받기)을 설명한다
> - 기본 `general-purpose` 서브에이전트가 자동으로 붙어 있다는 것을 알고, 교체하거나 끈다
> - `SubAgent` 의 전 필드(`name`/`description`/`systemPrompt`/`tools`/`model`/`permissions`/`responseFormat` 등)를 쓴다
> - **`description` 이 라우팅을 결정한다**는 것을 이해하고 부모가 고를 수 있는 설명을 쓴다
> - 서브에이전트별로 **모델을 차등**하고, 병렬/비동기/동적 서브에이전트를 구분해서 쓴다
> - 큰 결과를 **파일로 주고받는** 패턴을 적용한다
>
> **선행 스텝**: [Step 05 — 백엔드와 권한](../step-05-backends/)
> **예상 소요**: 80분

[Step 05](../step-05-backends/) 에서 우리는 에이전트가 **무엇을 볼 수 있는가**(백엔드)와 **거기서 뭘 할 수 있는가**(권한)를 정했습니다. 이제 다른 문제가 남았습니다. 에이전트가 그 파일들을 실제로 읽기 시작하면 **읽은 내용이 전부 대화에 쌓입니다.** 파일 30개를 읽으면 30개 내용이 컨텍스트에 들어가고, 그 뒤로 모델은 그 쓰레기를 계속 끌고 다니며 추론해야 합니다. 느려지고, 비싸지고, 결국 터집니다.

서브에이전트는 이 문제를 푸는 도구입니다. 그런데 대부분의 사람이 서브에이전트를 **"일을 나눠서 빨리 하는 것"** 으로 오해합니다. 아닙니다. 병렬 실행은 부수 효과일 뿐이고, 진짜 목적은 **"부모의 컨텍스트를 더럽히지 않는 것"** 입니다. 이 차이를 이해하면 "언제 서브에이전트를 쓰나"에 대한 답이 저절로 나옵니다. 이번 스텝은 거기서 출발합니다.

---

## 6-1. 컨텍스트 격리가 목적이다

먼저 오해부터 걷어냅시다. 서브에이전트를 쓰면 **토큰을 아낄 수 있을까요?** 아닙니다. 오히려 더 씁니다. 자식도 LLM 이고, 자식이 파일 30개를 읽으면 그 30개는 **자식의** 토큰으로 계산됩니다. 게다가 자식을 띄우는 것 자체가 추가 호출입니다.

그럼 뭘 얻습니까? **부모가 깨끗해집니다.**

```ts
const files = Array.from({ length: 30 }, (_, i) => ({
  path: `/docs/note-${i + 1}.md`,
  body: `# 노트 ${i + 1}\n` + "이 문서에는 대략 400토큰 분량의 내용이 들어 있습니다. ".repeat(20),
}));

const 직접읽기_누적 = files.reduce((sum, f) => sum + f.body.length, 0);
const 서브에이전트_반환 = "30개 노트를 읽고 요약함: 핵심 주제는 A, B, C 세 가지.".length;
```

**출력**
```
  부모가 직접 30개 읽으면 부모 컨텍스트에 쌓이는 글자 수: 20,031
  서브에이전트에 맡기면 부모에게 돌아오는 글자 수: 36
  압축비: 약 556:1
```

부모 입장에서 20,031자가 36자가 됐습니다. **서브에이전트도 그 30개를 다 읽었습니다.** 다만 그게 **부모의 대화에는 안 남습니다.**

왜 이게 중요한가? 부모는 **오케스트레이터**입니다. 부모가 해야 할 일은 "무엇을 시킬지 정하고, 결과를 종합하는 것"이지 "노트 17번의 3번째 문단"을 기억하는 게 아닙니다. 부모의 컨텍스트가 잡음으로 차면 모델의 판단력이 떨어집니다. 이걸 **컨텍스트 엔지니어링**이라고 부릅니다.

> 💡 **실무 팁 — 판단 기준 한 줄**: 서브에이전트를 쓸지 말지는 이 질문 하나로 정합니다.
> **"이 일이 컨텍스트를 많이 먹는데, 중간 과정은 안 봐도 되는가?"**
> 둘 다 예 → 서브에이전트. 하나라도 아니오 → 그냥 직접 하세요.
> - "1+1은?" → 컨텍스트도 안 먹고 과정도 없음 → 직접
> - "파일 하나 읽어 오타 고치기" → 도구 2번이면 끝 → 직접
> - "코드베이스 200개 파일 훑어 취약점 찾기" → 컨텍스트 폭발 + 과정 안 봐도 됨 → **서브에이전트**
> - "무관한 기술 5개 조사 후 비교" → 각자 자료 많이 읽음 + 요약만 필요 → **서브에이전트(병렬)**

> ⚠️ **함정**: 서브에이전트를 "성능 최적화"로 생각하면 남용하게 됩니다. 사소한 일에 서브에이전트를 띄우면 **LLM 호출 한 번 + 왕복 지연**이 통째로 추가됩니다. 자식은 부모가 이미 읽은 파일을 **다시 읽어야** 하고(부모 대화를 못 보니까), 결국 더 느리고 더 비쌉니다. Deep Agents 의 `task` 도구 설명에도 *"If the task is trivial (a few tool calls or simple lookup)"* 일 때는 쓰지 말라고 명시돼 있습니다.

---

## 6-2. `task` 도구의 동작

서브에이전트를 하나라도 주면 부모에게 **`task`** 도구가 자동으로 붙습니다. 부모는 이 도구로만 자식을 부를 수 있습니다.

```ts
const middleware = createSubAgentMiddleware({
  defaultModel: MODEL,
  defaultTools: [],
  subagents: [
    { name: "researcher", description: "주제를 깊이 조사한다", systemPrompt: "너는 조사원이다." },
  ],
});
```

`task` 도구의 입력 스키마는 **필드 딱 2개**입니다.

**출력**
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "description": {
      "type": "string",
      "description": "The task to execute with the selected agent"
    },
    "subagent_type": {
      "type": "string",
      "description": "Name of the agent to use. Available: general-purpose, researcher"
    }
  },
  "required": ["description", "subagent_type"],
  "additionalProperties": false
}
```

즉 부모가 하는 일은 이것뿐입니다.

```
task({ subagent_type: "researcher", description: "양자컴퓨팅 최신 동향을 조사해줘" })
```

그리고 돌아오는 것은 **`ToolMessage` 문자열 하나**입니다. 자식이 도구를 50번 부르든, 파일을 30개 읽든, 5분을 쓰든 — 부모가 보는 것은 최종 보고서 한 덩어리입니다.

이 구조를 그림으로 정리하면 이렇습니다.

```
부모 대화                          자식 대화 (완전히 별개)
─────────                         ──────────────────────
User: "…조사해줘"
AI: task(researcher, "…")   ───▶  System: (자식의 systemPrompt)
                                  User: "…"            ← description 이 여기 들어감
                                  AI: web_search(…)
                                  Tool: (결과 3000자)
                                  AI: read_file(…)
                                  Tool: (결과 5000자)
                                  AI: "최종 보고서"     ← 이것만 반환
Tool: "최종 보고서"          ◀───
AI: "정리하면 …"
```

**자식의 대화는 부모 대화에 한 줄도 안 들어갑니다.** 그게 격리입니다. 그리고 이 그림에서 두 가지가 바로 보입니다.

1. 자식의 첫 메시지는 부모가 준 `description` **하나뿐**입니다 → 자식은 부모 대화를 모릅니다 (6-9 의 함정).
2. 부모에게 돌아오는 건 문자열 **하나뿐**입니다 → 큰 결과는 담을 수 없습니다 (6-9 의 해법).

> 💡 **실무 팁**: `task` 도구의 설명에는 이미 이런 지시가 들어 있습니다 — *"Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses"*. 즉 **병렬 실행은 따로 켜는 옵션이 아닙니다.** 모델이 한 메시지에서 `task` 를 여러 번 호출하면 그게 병렬입니다. 우리가 할 일은 "병렬로 하라"고 지시하는 게 아니라, 자식들이 **정말 독립적이도록** 설계하는 것입니다.

---

## 6-3. 기본 `general-purpose` 서브에이전트

`subagents` 를 **안 줘도** 서브에이전트가 하나 있습니다.

```ts
console.log(GENERAL_PURPOSE_SUBAGENT.name);
console.log(GENERAL_PURPOSE_SUBAGENT.systemPrompt);
console.log(DEFAULT_GENERAL_PURPOSE_DESCRIPTION);
```

**출력**
```
  이름: general-purpose
  systemPrompt: In order to complete the objective that the user asks of you, you have access to a number of standard tools.

  --- 기본 description (부모가 이걸 보고 고릅니다) ---
  General-purpose agent for researching complex questions, searching for files and content, and executing
  multi-step tasks. When you are searching for a keyword or file and are not confident that you will find
  the right match in the first few tries use this agent to perform the search for you. This agent has
  access to all tools as the main agent.
```

`general-purpose` 의 성질은 이렇습니다.

| 항목 | 동작 |
|---|---|
| 도구 | 부모의 **모든 도구**를 물려받음 |
| 모델 | 부모와 **같은 모델** |
| 스킬 | 부모의 스킬을 **상속** (커스텀 서브에이전트는 상속 안 함) |
| 시스템 프롬프트 | 부모 프롬프트를 이어받음 |

**끄기**와 **교체하기**가 됩니다.

```ts
const withGP = createSubAgentMiddleware({ defaultModel: MODEL, defaultTools: [], subagents: [] });
const withoutGP = createSubAgentMiddleware({
  defaultModel: MODEL,
  defaultTools: [],
  generalPurposeAgent: false,
  subagents: [{ name: "researcher", description: "조사 담당", systemPrompt: "너는 조사원이다." }],
});
```

**출력**
```
  기본 상태의 서브에이전트 목록: ["general-purpose"]
  generalPurposeAgent:false 면: ["researcher"]
```

같은 이름(`"general-purpose"`)으로 커스텀 서브에이전트를 주면 **교체**됩니다.

```ts
const custom: SubAgent = {
  ...GENERAL_PURPOSE_SUBAGENT,
  name: "general-purpose",
  systemPrompt: "너는 만능 조수다. 항상 한국어로 보고한다.",
};
```

> ⚠️ **함정**: "나는 `subagents` 를 안 줬으니 서브에이전트가 없다" 고 생각하기 쉽습니다. **아닙니다.** `general-purpose` 가 이미 붙어 있고, `task` 도구도 이미 모델에게 노출돼 있습니다. 즉 모델은 언제든 `task` 를 부를 수 있고, 그러면 **예상 못 한 LLM 호출과 지연과 비용**이 생깁니다. "왜 간단한 질문에 응답이 10초나 걸리지?" 의 범인이 이것일 때가 있습니다. 정말 원치 않으면 명시적으로 꺼야 합니다.

> ⚠️ **함정 2**: 반대로 `generalPurposeAgent: false` 에 `subagents: []` 로 다 꺼도 **`task` 도구 자체는 남습니다.** 목록만 비어 있는 도구가 모델에게 노출된 상태가 되고, 모델이 그걸 부르려 시도할 수 있습니다. 서브에이전트를 아예 안 쓸 거라면 미들웨어를 넣지 않는 쪽이 깔끔합니다.

> 💡 **실무 팁**: 위 예제에서 `createSubAgentMiddleware` 를 직접 부른 것은 **내부를 들여다보기 위한 교육용**입니다. 실무에서는 `createDeepAgent({ subagents: [...] })` 만 쓰면 됩니다 — 이 미들웨어가 기본 스택에 이미 들어 있습니다.

---

## 6-4. 커스텀 SubAgent 정의

필수 필드는 **3개**(`name`, `description`, `systemPrompt`), 나머지는 전부 선택입니다.

| 필드 | 타입 | 필수 | 의미 / 주의 |
|---|---|:---:|---|
| `name` | `string` | ✅ | `task` 의 `subagent_type` 으로 쓰이는 식별자 |
| `description` | `string` | ✅ | **부모가 보는 유일한 정보.** 라우팅을 결정 (6-5) |
| `systemPrompt` | `string` | ✅ | 자식의 시스템 프롬프트. **부모 것을 상속하지 않음** |
| `tools` | `StructuredTool[]` | | 지정하면 부모 도구를 **물려받지 않고 이것만** 씀 |
| `model` | `LanguageModelLike \| string` | | 생략 시 부모 모델. `"provider:model"` 문자열 가능 |
| `middleware` | `AgentMiddleware[]` | | 기본 스택 **뒤에 추가**. 부모 것을 상속하지 않음 |
| `interruptOn` | `Record<string, boolean \| InterruptOnConfig>` | | HITL. **체크포인터 필요** |
| `skills` | `string[]` | | 스킬 경로. **커스텀 서브에이전트는 부모 스킬을 상속하지 않음** |
| `responseFormat` | zod 스키마 등 | | 구조화 출력. JSON 문자열로 부모에게 전달됨 |
| `permissions` | `FilesystemPermission[]` | | **부모 권한을 병합이 아니라 완전 교체** |

```ts
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
  tools: [webSearch],   // 지정하면 부모 도구를 물려받지 않고 이것만 씁니다
  model: CHEAP_MODEL,   // 서브에이전트별 모델 차등 (6-6)
  responseFormat: z.object({
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

const agent = createDeepAgent({ model: MODEL, backend: new StateBackend(), subagents: [minimal, full] });
```

**출력**
```
  (A) 최소 정의: {"name":"summarizer","필드수":3}
  (B) 전체 정의: {"name":"researcher","필드":["name","description","systemPrompt","tools","model","responseFormat","permissions"]}
  에이전트 생성: true
```

> ⚠️ **함정 — 상속되는 것과 안 되는 것이 뒤섞여 있다**: 이 표에서 가장 헷갈리는 부분입니다.
> - `model` 을 생략하면 → 부모 모델을 **상속**합니다.
> - `tools` 를 생략하면 → 부모 도구를 **상속**합니다.
> - `systemPrompt` 는 → **상속 안 합니다** (필수 필드인 이유).
> - `permissions` 를 지정하면 → 부모 것과 **병합이 아니라 교체**입니다. 부모가 `/etc/**` 를 막아놨어도 자식이 `permissions` 를 주면 그 규칙은 통째로 사라집니다. 자식 규칙에 `deny` 를 다시 안 넣으면 **자식이 부모보다 권한이 세집니다.**
> - `skills` 는 → 커스텀 서브에이전트는 **상속 안 합니다**. `general-purpose` 만 상속합니다.
>
> 특히 `permissions` 교체는 보안 사고로 이어집니다. 자식에게 권한을 줄 때는 부모 규칙을 **복사해서 시작**하세요.

> 💡 **실무 팁 — `responseFormat` 을 쓰면 파싱이 사라집니다**: `responseFormat` 에 zod 스키마를 주면 자식의 결과가 JSON 문자열로 직렬화돼 부모에게 갑니다. 부모가 자유 형식 텍스트에서 정보를 긁어내는 것보다 훨씬 안정적입니다. 특히 여러 자식의 결과를 **기계적으로 합쳐야 할 때**(점수 비교, 표 만들기) 필수에 가깝습니다.

---

## 6-5. `description` 이 라우팅을 결정한다

이 절이 이 스텝에서 실무적으로 가장 중요합니다.

**부모는 자식의 `systemPrompt` 를 볼 수 없습니다. `tools` 도 못 봅니다. 오직 `description` 만 봅니다.**

증거가 있습니다. `task` 도구의 설명 문자열에 각 서브에이전트의 `description` 이 **그대로 박힙니다.**

```ts
const vague = createSubAgentMiddleware({
  defaultModel: MODEL, defaultTools: [], generalPurposeAgent: false,
  subagents: [
    { name: "helper1", description: "도와준다", systemPrompt: "너는 SQL 전문가다." },
    { name: "helper2", description: "처리한다", systemPrompt: "너는 이미지 편집 전문가다." },
  ],
});

const clear = createSubAgentMiddleware({
  defaultModel: MODEL, defaultTools: [], generalPurposeAgent: false,
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
```

**출력**
```
  --- 모호한 description 이 부모에게 보이는 모습 ---
  - helper1: 도와준다
  - helper2: 처리한다
  → 부모는 'SQL 짜줘' 를 받고 helper1/helper2 중 뭘 골라야 할지 알 수 없습니다.

  --- 명확한 description ---
  - sql-expert: SQL 쿼리를 작성하고 최적화한다. 데이터베이스 스키마 질문에도 답한다.
  - image-editor: 이미지를 자르고 리사이즈하고 포맷을 변환한다.
  → 부모는 망설임 없이 sql-expert 를 고릅니다.

  task 설명에 'SQL 전문가다'(systemPrompt) 가 들어있나?: false
```

마지막 줄을 보세요. `helper1` 의 `systemPrompt` 에는 **"너는 SQL 전문가다"** 라고 또렷이 적혀 있습니다. 하지만 `task` 도구 설명에는 **없습니다**. 부모는 그 정보를 영원히 볼 수 없습니다.

> ⚠️ **함정**: `systemPrompt` 를 정성껏 쓰고 `description` 을 `"도와준다"` 로 대충 쓰는 실수는 놀랍도록 흔합니다. 결과는 두 가지 중 하나입니다.
> - **부모가 안 부릅니다.** 뭘 하는 앤지 모르니까요. 그리고 부모는 혼자서 어떻게든 해내려 하다가 컨텍스트를 다 태웁니다. 에러는 안 납니다.
> - **부모가 잘못 부릅니다.** `helper1` 과 `helper2` 중 아무거나 고릅니다. 이미지 편집 요청이 SQL 전문가에게 갑니다. 자식은 자기가 뭘 받았는지도 모르고 최선을 다해 엉뚱한 답을 냅니다.
>
> `description` 은 자식의 소개말이 아니라 **부모가 읽는 라우팅 규칙**입니다. [Step 06](../../langchain/step-06-tools/) 에서 "도구 설명이 곧 프롬프트다" 라고 배운 것과 정확히 같은 원리입니다.

> 💡 **실무 팁 — `description` 작성 공식**:
> ```
> "[동사]한다. [언제 부르는지]. [무엇을 반환하는지]."
> ```
> 그리고 헷갈리는 형제가 있으면 **"~는 못 한다"** 로 경계를 그어주세요.
> ```ts
> description:
>   "이미 계산된 데이터를 받아 차트나 리포트 문서로 렌더링한다. " +
>   "'표로 그려줘', '차트로 보여줘' 같은 시각화 요청에만 부를 것. " +
>   "데이터를 조회하지는 못한다.",   // ← 이 한 줄이 오라우팅을 막습니다
> ```
> 그리고 병렬로 돌리고 싶으면 **`description` 에 그렇게 적으세요** — `"여러 기술을 비교해야 하면 기술 하나당 하나씩 병렬로 호출할 것"`. 부모가 읽는 건 이것뿐이니까요.

---

## 6-6. 서브에이전트별 모델 차등

자식마다 다른 모델을 줄 수 있습니다. 이게 Deep Agents 에서 **비용을 가장 크게 줄이는 레버**입니다.

```ts
const scout: SubAgent = {
  name: "scout",
  description: "자료를 넓게 훑어 후보를 추린다. 정확도보다 커버리지가 중요할 때 쓴다.",
  systemPrompt: "너는 정찰병이다. 빠르게 훑고 후보만 추려라.",
  model: CHEAP_MODEL,     // 싼 모델
};

const analyst: SubAgent = {
  name: "analyst",
  description: "추려진 자료를 깊이 분석하고 결론을 낸다. 정확도가 중요할 때 쓴다.",
  systemPrompt: "너는 분석가다. 근거를 들어 결론을 내라.",
  model: MODEL,           // 비싼 모델
};

// 부모(종합 담당)는 비싼 모델
const agent = createDeepAgent({ model: MODEL, backend: new StateBackend(), subagents: [scout, analyst] });
```

**출력**
```
  scout 모델: anthropic:claude-haiku-4-5
  analyst 모델: anthropic:claude-sonnet-4-6
  부모 모델: anthropic:claude-sonnet-4-6
  model 생략 시: 부모 모델(anthropic:claude-sonnet-4-6)을 상속
```

**어디에 싼 모델을 넣을 것인가**가 핵심입니다.

| 자리 | 입력 크기 | 판단 난이도 | 모델 |
|---|---|---|---|
| 선별/정찰 (기사 100건 훑기) | **큼** | 낮음 | **싼 모델** ← 여기서 가장 많이 절감 |
| 분석 (추려진 5건 판단) | 작음 | **높음** | 비싼 모델 |
| 부모 (최종 종합) | 작음(요약만 받음) | **높음** | 비싼 모델 |

원칙은 **"입력이 크고 판단이 단순한 곳"** 에 싼 모델을 넣는 것입니다. 반대로 부모를 싼 모델로 바꾸는 건 대개 손해입니다 — 사용자가 실제로 읽는 글이 부모의 출력이라 품질 저하가 바로 보입니다.

모델 문자열은 `"provider:model"` 형식입니다. OpenAI 를 섞어 쓸 수도 있습니다.

```ts
const scout: SubAgent = { name: "scout", description: "…", systemPrompt: "…", model: "openai:gpt-5.5" };
```

> ⚠️ **함정**: 싼 모델은 **도구 호출을 덜 안정적으로** 합니다. 서브에이전트에게 도구를 잔뜩 주면서 싼 모델을 쓰면, 도구를 아예 안 부르거나 엉뚱한 인자를 넣고도 **"완료했습니다"** 라고 보고합니다. 그리고 부모는 그걸 그대로 믿습니다 — `task` 도구 설명에 *"The agent's outputs should generally be trusted"* 라고 적혀 있으니까요. 즉 **거짓 보고가 조용히 최종 결과에 섞입니다.** 싼 모델 서브에이전트는 도구를 최소로 주고 역할을 좁히세요. 그리고 중요한 자식에는 `responseFormat` 을 걸어 결과 형태를 강제하세요.

---

## 6-7. 비동기/병렬 서브에이전트

여기서 두 개념을 **반드시** 구분해야 합니다.

| | 동기 서브에이전트의 병렬 실행 | 비동기 서브에이전트 (`AsyncSubAgent`) |
|---|---|---|
| 정의 필드 | `systemPrompt` | **`graphId`** |
| 부모가 받는 도구 | `task` | `start_async_task` 외 4개 |
| 부모가 기다리나? | **예** (전부 끝날 때까지 블로킹) | **아니오** (즉시 task id 반환) |
| 필요한 것 | 없음 | Agent Protocol 서버 |
| 쓰는 이유 | 독립 작업을 동시에 | 사용자와 대화하며 백그라운드 작업 |

```ts
// (1) 동기 — 그냥 평범한 SubAgent 입니다.
const syncSub: SubAgent = {
  name: "researcher",
  description: "주제 하나를 조사한다. 여러 주제면 주제당 하나씩 병렬로 부를 것.",
  systemPrompt: "너는 조사원이다.",
};

// (2) 비동기 — graphId 로 이미 배포된 그래프를 가리킵니다.
const asyncSubs: AsyncSubAgent[] = [
  {
    name: "long-researcher",
    description: "몇 분씩 걸리는 장기 조사를 백그라운드로 수행한다.",
    graphId: "researcher",                              // 같은 배포 안 (ASGI)
  },
  {
    name: "remote-coder",
    description: "원격 배포된 코딩 에이전트.",
    graphId: "coder",
    url: "https://coder-deployment.langsmith.dev",      // 원격이면 url 추가
  },
];
```

**출력**
```
  (1) 동기 서브에이전트: {"name":"researcher","isAsync":false}
  (2) long-researcher: {"graphId":"researcher","isAsync":true}
  (2) remote-coder: {"graphId":"coder","isAsync":true}
```

`isAsyncSubAgent()` 로 판별할 수 있고, 가르는 것은 **`graphId` 의 존재**입니다.

비동기를 쓰면 부모는 `task` 대신 도구 5개를 받습니다.

| 도구 | 하는 일 |
|---|---|
| `start_async_task` | 백그라운드 작업 시작, **즉시** task id 반환 |
| `check_async_task` | 현재 상태와 결과 조회 |
| `update_async_task` | 실행 중인 작업에 새 지시 전달 (중간 조종) |
| `cancel_async_task` | 작업 취소 |
| `list_async_tasks` | 추적 중인 작업 전체 조회 |

`AsyncSubAgent` 의 필드는 `name`, `description`, `graphId`, `url`(원격일 때), `headers`(인증)입니다.

> ⚠️ **함정 — "비동기"와 "병렬"은 다른 얘기입니다**: "여러 개를 빨리 돌리고 싶어서" 비동기를 고르는 것은 **잘못된 선택**입니다. 동기 서브에이전트도 부모가 한 메시지에서 `task` 를 여러 번 부르면 **이미 병렬로 돕니다.** 다만 부모가 전부 끝날 때까지 기다릴 뿐이죠. 비동기의 진짜 가치는 **"부모가 안 기다리는 것"** — 사용자와 계속 대화하면서 백그라운드로 몇 분짜리 작업을 굴리는 것입니다. 단순히 빠르게 하고 싶은 거라면 그냥 병렬 `task` 를 쓰세요. Agent Protocol 서버를 띄우고 배포를 관리하는 비용을 치를 이유가 없습니다.

> 💡 **실무 팁**: 비동기 서브에이전트의 작업 메타데이터는 `asyncTasks` 라는 **별도 채널**에 저장됩니다. 그래서 대화가 요약(compaction)돼도 진행 중인 작업 정보가 살아남습니다. 로컬 개발에서는 워커 풀이 필요합니다 — `langgraph dev --n-jobs-per-worker 10`. 안 그러면 작업이 큐에서 안 빠져나갑니다.

---

## 6-8. 동적 서브에이전트

지금까지의 `task` 는 **부모가 도구 호출로 직접** 부르는 것이었습니다. 그래서 "파일이 몇 개인지 미리 알아야" 그만큼 호출할 수 있습니다. 파일이 47개인지 몰라도 되게 하려면? **코드로** 부르면 됩니다.

`@langchain/quickjs` 의 인터프리터를 붙이면 모델이 `eval` 도구 안에서 `task()` 를 **함수처럼** 부를 수 있습니다.

```ts
import { createCodeInterpreterMiddleware } from "@langchain/quickjs";

const agent = createDeepAgent({
  model: MODEL,
  subagents: [{
    name: "reviewer",
    description: "코드를 보안 관점에서 리뷰하고 줄 번호와 심각도를 인용한다",
    systemPrompt: "너는 보안 중심 코드 리뷰어다.",
  }],
  middleware: [createCodeInterpreterMiddleware()],
});
```

그러면 **모델이** 이런 코드를 작성해서 실행합니다 (우리가 쓰는 게 아닙니다).

```ts
// 모델이 eval 도구 안에서 작성하는 코드
const files = (await tools.glob({ pattern: "src/routes/**/*.ts" }))
  .split("\n").filter(Boolean);

const reviews = await Promise.all(
  files.map((file) =>
    task({
      description: `${file} 을 인증 취약점 관점에서 리뷰하라. 줄 번호를 인용할 것.`,
      subagentType: "reviewer",
      responseSchema: issuesSchema,
    }),
  ),
);
const issues = reviews.flatMap((r) => r.issues);
```

`task()` 의 시그니처는 이렇습니다.

```ts
const result = await task({
  description: string;          // 자식에게 줄 프롬프트
  subagentType: string;         // 서브에이전트 이름 (camelCase 주의!)
  responseSchema?: JSONSchema;  // 있으면 결과가 이미 파싱된 객체로 옴
});
```

일반 `task` 도구와의 차이는 이렇습니다.

| | 일반 `task` 도구 | 동적 `task()` |
|---|---|---|
| 호출 주체 | 부모가 도구 호출로 | 모델이 작성한 코드가 |
| 개수 | 미리 알아야 함 | **런타임에 결정** (루프) |
| 조합 | 순차 또는 병렬 | `Promise.all`, 조건문, `while` 루프 |
| 결과 | 문자열 | `responseSchema` 주면 **객체** |
| 인자 이름 | `subagent_type` | **`subagentType`** |

이걸로 만들 수 있는 패턴들이 있습니다 — 팬아웃 후 종합, 분류 후 처리, **적대적 검증**(찾은 것을 다른 자식이 반증), 토너먼트(여러 후보를 붙여 이긴 것만), 루프(더 안 나올 때까지).

> 💡 **실무 팁**: 프롬프트에 **"workflow"** 라는 단어를 넣으면 모델이 단일 도구 호출 대신 코드 오케스트레이션을 택하는 경향이 있습니다. `"Run a workflow that reviews every file in src/routes/ and summarizes the top risks."` 처럼요. `createCodeInterpreterMiddleware({ subagents: false })` 로 동적 서브에이전트만 끌 수도 있고, `{ ptc: ["glob"] }` 로 인터프리터가 부를 수 있는 도구를 화이트리스트할 수도 있습니다.

> ⚠️ **함정**: 동적 `task()` 는 부모의 **승인 워크플로(`interruptOn`)를 우회합니다.** 부모에 HITL 을 걸어놨어도 인터프리터 안에서 도는 자식들은 거기 안 걸립니다. 그리고 `Promise.all` 로 자식 50개를 한 번에 띄우는 코드를 모델이 작성할 수도 있습니다 — 비용과 레이트 리밋이 순식간에 터집니다. 인터프리터를 붙일 때는 자식들의 `tools` 를 좁게 주고, 프롬프트에 "한 번에 N개까지만" 같은 상한을 명시하세요.

---

## 6-9. 파일시스템으로 결과 주고받기

`task` 의 반환은 **문자열 하나**입니다. 그런데 자식이 만든 게 5,000줄짜리 리포트라면? 그걸 문자열로 돌려받는 순간 **부모 컨텍스트가 터집니다** — 서브에이전트를 쓴 이유가 통째로 사라지는 거죠.

해법은 [Step 04](../step-04-filesystem/) 와 [Step 05](../step-05-backends/) 에서 배운 파일시스템입니다. **자식이 파일에 쓰고, 경로만 반환합니다.** 부모는 필요할 때만 읽습니다.

```ts
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
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
  --- 최종 답변 ---
  /reports/satisfies.md 에 리포트를 작성했습니다. 핵심 3가지는 다음과 같습니다.
  1. satisfies 는 타입을 "검사"하되 "넓히지" 않는다 …
  2. as 와 달리 실제 타입을 잃지 않는다 …
  3. 설정 객체의 키 자동완성을 유지하면서 오타를 잡는다 …

  상태에 남은 파일: ["/reports/satisfies.md"]
```

**자식과 부모는 같은 백엔드를 공유합니다.** 그래서 자식이 쓴 파일이 부모 상태에 그대로 남습니다. 이게 서브에이전트 간 데이터 전달의 정석입니다.

> ⚠️ **함정 (이 스텝 최대의 함정) — 서브에이전트는 부모 대화를 못 본다**: 이건 **의도된 설계**입니다(그게 격리니까요). 문제는 **모르고 쓸 때** 벌어집니다.
>
> ```ts
> // 대화:
> //   User: "나는 앞으로 모든 답변을 일본어로 받고 싶어."
> //   AI:   "알겠습니다."
> //   User: "'안녕하세요, 반갑습니다' 를 번역해줘."
> const agent = createDeepAgent({
>   model: MODEL, subagents: [translator],
>   systemPrompt: "너는 조수다. 번역이 필요하면 translator 를 불러라.",
> });
> ```
>
> `translator` 는 **영어로 번역합니다.** "일본어로 받고 싶어" 는 부모 대화에만 있고, 자식이 받는 것은 `task` 의 `description` 문자열 하나뿐이기 때문입니다. 자식에게는 세상이 그 한 줄이 전부입니다.
>
> **그리고 에러가 안 납니다.** 번역은 됐고, 결과도 그럴듯합니다. 언어만 틀렸습니다. 부모는 자식을 믿으므로 검증도 안 합니다. 사용자만 이상하다고 느낍니다.
>
> 고치는 방법은 **자식이 아니라 부모의 프롬프트**를 고치는 것입니다.
> ```ts
> systemPrompt:
>   "너는 조수다. 번역이 필요하면 translator 서브에이전트를 불러라.\n" +
>   "**중요**: 서브에이전트는 지금까지의 대화를 전혀 볼 수 없다. 완전히 백지 상태에서 시작한다.\n" +
>   "그러므로 task 의 description 에는 서브에이전트가 일을 끝내는 데 필요한 모든 것을 담아야 한다:\n" +
>   "- 번역할 원문 전체\n" +
>   "- 대상 언어 (대화에서 사용자가 지정한 언어를 네가 직접 찾아서 명시할 것)\n" +
>   "'사용자가 아까 말한 언어로' 같은 표현은 서브에이전트에게 아무 의미가 없다.",
> ```
>
> 💡 **원칙**: `task` 의 `description` 은 **"처음 만난 외주 업체에게 보내는 작업 지시서"** 라고 생각하세요. 그 사람은 우리 회사의 맥락을 하나도 모릅니다. 필요한 건 전부 문서에 적어야 합니다. Deep Agents 공식 `task` 프롬프트도 같은 말을 합니다 — *"Each agent invocation is stateless... your prompt should contain a highly detailed task description."*

> ⚠️ **함정 2 — 병렬 자식이 같은 파일에 쓰면 충돌한다**: 자식 3개를 병렬로 띄우면서 전부 `/research/result.md` 에 쓰라고 하면, **마지막에 끝난 하나만 남고 나머지는 조용히 사라집니다.** 에러도, 경고도 없습니다. 부모는 파일을 읽고 "어? 하나밖에 없네" 하거나, 더 나쁘게는 그걸 전체 결과로 착각합니다. **경로에 자식마다 다른 식별자를 넣으세요** — `/research/<주제>.md` 처럼요.

---

## 6-10. 실전 — 리서치 서브에이전트 3개 병렬 + 종합

지금까지의 모든 것을 조립합니다. 조사는 싼 모델 자식 3개가 **병렬로**, 종합은 비싼 모델 부모가. 결과는 **파일로** 주고받습니다.

```ts
const researcher: SubAgent = {
  name: "researcher",
  description:
    "기술 하나를 조사해 특징과 트레이드오프를 정리한다. " +
    "여러 기술을 비교해야 하면 기술 하나당 하나씩 병렬로 호출할 것. " +      // ← 병렬 지시
    "조사 결과를 /research/<기술명>.md 에 저장하고 경로만 반환한다.",        // ← 파일 전달
  systemPrompt:
    "너는 기술 조사원이다. web_search 로 자료를 찾아 특징/장점/단점을 정리하고, " +
    "지정된 경로에 write_file 로 저장하라. 최종 답변은 '저장 경로 + 한 줄 요약' 만 적어라. " +
    "너는 부모의 대화를 볼 수 없으므로, 프롬프트에 주어진 정보만으로 판단하라.",  // ← 격리 인지
  tools: [webSearch],       // ← 도구를 좁게 (자식이 또 task 를 못 부르게)
  model: CHEAP_MODEL,       // ← 조사는 싼 모델
};

const agent = createDeepAgent({
  model: MODEL,             // ← 종합은 비싼 모델
  backend: new StateBackend(),
  subagents: [researcher],
  systemPrompt:
    "너는 기술 선임이다. 비교 요청을 받으면 researcher 서브에이전트를 " +
    "대상 기술마다 하나씩 '병렬로' 띄워라(한 메시지에 task 를 여러 번 호출). " +
    "각자가 저장한 파일을 읽어 최종 비교표를 만들어라.",
});

const result = await agent.invoke({
  messages: [{
    role: "user",
    content: "Redis, PostgreSQL, DynamoDB 를 세션 저장소 용도로 비교해줘. 각각 조사한 뒤 표로 정리해줘.",
  }],
});
```

**출력 예시** (모델 응답이므로 매번 다릅니다)
```
  --- 최종 비교 ---
  | 기술 | 속도 | 영속성 | 확장성 | 세션 저장소 적합도 |
  |---|---|---|---|---|
  | Redis | 매우 빠름 | RDB/AOF 옵션 | 클러스터 | ★★★ 가장 일반적 |
  | PostgreSQL | 보통 | 완전 (ACID) | 수직 위주 | ★★ 이미 쓰고 있다면 |
  | DynamoDB | 빠름 | 완전 관리형 | 무제한 | ★★★ AWS 환경이면 |

  task 호출 횟수: 3
    → subagent_type: researcher
    → subagent_type: researcher
    → subagent_type: researcher
```

병렬로 돌았는지 확인하는 방법이 있습니다. **한 메시지 안에 `task` 호출이 여러 개** 들어 있으면 병렬입니다.

```ts
const perMessage = result.messages.map((m) => {
  const calls = (m as { tool_calls?: Array<{ name: string }> }).tool_calls ?? [];
  return calls.filter((c) => c.name === "task").length;
}).filter((n) => n > 0);
```

**출력**
```
  메시지당 task 호출 수: [3]
  → [3] 처럼 한 메시지에 3개면 병렬. [1,1,1] 이면 순차입니다.
```

`[3]` 이면 부모가 한 번에 3개를 띄운 것이고, `[1,1,1]` 이면 하나씩 순차로 부른 것입니다. 후자라면 `description` 의 병렬 지시가 약한 것이니 문구를 강화하세요.

> ⚠️ **함정 — 서브에이전트가 서브에이전트를 부르면 비용이 곱해진다**: 위에서 `researcher` 에게 `tools: [webSearch]` 를 **명시**했습니다. 이걸 생략하면 자식이 부모 도구를 전부 물려받는데, 거기엔 **`task` 도구도 포함됩니다.** 그러면 자식이 또 자식을 부를 수 있습니다. 3개 × 3개 = 9개 에이전트가 되고, 그게 또 분기하면 27개입니다. 비용과 지연이 지수적으로 터지고, 최악의 경우 재귀가 멈추지 않습니다. **서브에이전트의 `tools` 는 좁게 주세요.** 그게 재귀를 막는 가장 확실한 방법입니다.

> 💡 **실무 팁**: 이 구조(싼 모델 자식 N개 병렬 → 파일로 저장 → 비싼 모델 부모가 읽고 종합)가 **딥 리서치 에이전트의 기본형**입니다. [Step 12 — 종합 프로젝트](../step-12-final-project/) 에서 이걸 제대로 만듭니다. 여기서 뼈대를 확실히 이해해 두세요.

---

## 정리

| 개념 | 요점 |
|---|---|
| **목적** | 일 나눠하기가 아니라 **부모 컨텍스트 보호**. 토큰은 오히려 더 씀 |
| `task` 도구 | 입력은 `{ description, subagent_type }` 2개, 출력은 **문자열 하나** |
| `general-purpose` | **자동으로 붙음.** 부모 도구/모델/스킬 상속. 끄려면 명시적으로 |
| `SubAgent` 필수 필드 | `name`, `description`, `systemPrompt` |
| **`description`** | 부모가 보는 **유일한** 정보. 라우팅을 결정 |
| `model` 차등 | "입력이 크고 판단이 단순한 곳"에 싼 모델 |
| 병렬 | 동기 서브에이전트도 한 메시지에 `task` 여러 번 = 병렬 |
| 비동기 (`AsyncSubAgent`) | `graphId` 로 식별. 부모가 **안 기다림**. Agent Protocol 서버 필요 |
| 동적 | 인터프리터 안에서 `task()` 를 코드로 호출. 개수를 런타임에 결정 |
| 큰 결과 | 파일에 쓰고 **경로만 반환** |

**상속 규칙 (헷갈리는 것만)**

| 필드 | 생략하면 | 지정하면 |
|---|---|---|
| `model` | 부모 것 상속 | 교체 |
| `tools` | 부모 것 상속 | **이것만** 사용 |
| `systemPrompt` | (필수라 생략 불가) | — |
| `permissions` | 부모 것 상속 | **완전 교체** (병합 아님!) |
| `skills` | 상속 **안 함** (general-purpose 만 상속) | 사용 |

**핵심 함정 3가지**

1. **서브에이전트는 부모 대화를 못 본다.** 그게 의도이지만, 모르고 쓰면 "일본어로 답해줘"가 자식에게 전달되지 않아 **에러 없이 영어로 번역**됩니다. `task` 의 `description` 에 필요한 맥락을 **전부** 담으세요. 처음 만난 외주 업체에게 보내는 작업 지시서처럼.
2. **`description` 이 모호하면 부모가 안 부르거나 잘못 부른다.** 부모는 `systemPrompt` 를 **볼 수 없습니다.** `"도와준다"` 로 써놓고 `systemPrompt` 에만 정성을 쏟는 건 아무 의미가 없습니다.
3. **자식이 자식을 부르면 비용이 폭발한다.** `tools` 를 생략하면 `task` 도구까지 상속되어 재귀가 열립니다. 그리고 병렬 자식이 **같은 파일**에 쓰면 조용히 덮어씁니다 — 경로에 식별자를 넣으세요.

---

## 연습문제

1. **서브에이전트를 쓸까 말까**: 다음 4가지 상황에서 서브에이전트를 쓰는 게 맞는지 판단하고 이유를 적으세요. (a) "1+1은?" (b) 파일 200개를 훑어 취약점 찾기 (c) 파일 하나 읽어 오타 하나 고치기 (d) 무관한 기술 5개 조사 후 비교
2. **`task` 도구는 언제 생기나**: `subagents: []` + 기본값 / `subagents: []` + `generalPurposeAgent: false` 두 경우의 서브에이전트 목록을 출력하세요. 후자에서 **`task` 도구 자체는 어떻게 되는지**도 확인하고 설명하세요.
3. **`description` 을 고쳐라**: `"DB 관련 작업"` / `"리포트 관련 작업"` 이라는 모호한 `description` 을 가진 두 서브에이전트를, 부모가 `"이번 달 매출 쿼리 좀 짜줘"` 를 받았을 때 망설임 없이 고를 수 있게 고치세요. **`systemPrompt` 는 건드리지 마세요.**
4. **모델 차등 설계**: "뉴스 기사 100건을 훑어 우리 회사 관련된 것만 추린 뒤, 그것들만 깊이 분석"하는 파이프라인을 서브에이전트 2개로 설계하세요. 어느 쪽에 싼 모델을 줄지, 부모는 무엇으로 할지, 왜 그렇게 나눴는지 적으세요.
5. **동기 vs 비동기 구분**: 주어진 4개 정의 중 어떤 것이 `AsyncSubAgent` 인지 `isAsyncSubAgent()` 로 판별하고, **무엇이 그 둘을 가르는지**(어떤 필드) 설명하세요.
6. **부모 컨텍스트를 못 본다**: 일부러 고장난 번역 에이전트를 실행해 어긋나는 것을 관찰한 뒤, **부모의** `systemPrompt` 를 고쳐 맥락이 자식에게 전달되게 만드세요. 부모가 자식에게 실제로 넘긴 `description` 을 출력해 확인하세요.
7. **병렬 리서치**: `researcher` 서브에이전트(싼 모델)를 만들어 3개 주제를 **병렬로** 조사하게 하고, 각자 `/research/<주제>.md` 에 저장한 뒤 부모(비싼 모델)가 읽어 비교표를 만들게 하세요. **한 메시지에 `task` 가 몇 개 들어 있는지** 세어 병렬 여부를 검증하세요.

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 07 — 시스템 프롬프트 설계](../step-07-prompting/)

이번 스텝에서 우리는 프롬프트가 **라우팅을 결정한다**(`description`)는 것과, **맥락을 전부 담아야 한다**(`task` 의 `description`)는 것을 배웠습니다. 즉 Deep Agent 를 다룬다는 건 결국 프롬프트를 다루는 일입니다. 다음 스텝에서는 `systemPrompt` 가 내장 Deep Agent 프롬프트와 **어떻게 합쳐지는지**, 그리고 `{ prefix, base: null }` 로 완전히 제어하는 법을 다룹니다.

**관련**: 멀티 에이전트 패턴 전반(핸드오프, 라우터, 슈퍼바이저)은 [LangChain Step 18 — 멀티 에이전트](../../langchain/step-18-multi-agent/) 에서 다룹니다. Deep Agents 의 서브에이전트는 그중 **슈퍼바이저 패턴**을 컨텍스트 격리에 특화해 미리 구현해 둔 것이라고 보면 됩니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(6-1 ~ 6-10)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 7개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 `docs/reference/deepagent/` 에서 `npx tsx step-06-subagents/practice.ts` 처럼 실행합니다. **6-1 ~ 6-8 은 API 키 없이 그대로 돌아갑니다** — `task` 도구의 스키마와 설명 문자열을 직접 들여다보는 예제라 모델을 안 부르기 때문입니다. `[6-9]` 와 `[6-10]` 만 실제 모델을 호출하며, `ANTHROPIC_API_KEY` 가 없으면 그 절만 조용히 건너뜁니다. OpenAI 로 바꾸려면 각 파일 상단의 `MODEL` / `CHEAP_MODEL` 상수를 `"openai:gpt-5.5"` 등으로 고치면 됩니다.

### practice.ts

본문을 따라가며 손으로 쳐볼 예제를 `[6-1] ~ [6-10]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응합니다.

- 이 파일의 특징은 **`taskToolOf()` 헬퍼로 `task` 도구를 직접 꺼내 본다**는 것입니다. `createSubAgentMiddleware` 를 부르고 그 안의 도구 배열에서 `task` 를 찾아 스키마와 설명을 출력합니다. 실무에서 이렇게 쓸 일은 없지만, "부모가 실제로 뭘 보는가" 를 눈으로 확인하는 데는 이만한 방법이 없습니다.
- `[6-2]` 는 `task` 도구의 JSON Schema 를 통째로 찍습니다. 필드가 `description` 과 `subagent_type` **둘뿐**이라는 것, 그리고 `subagent_type` 의 설명에 `"Available: general-purpose, researcher"` 처럼 **서브에이전트 이름이 자동으로 박히는 것**을 볼 수 있습니다.
- `[6-3]` 의 `agentList()` 헬퍼에는 주석으로 함정을 하나 적어 뒀습니다. `description` 전체에 `.includes("general-purpose")` 를 쓰면 **항상 `true`** 가 나옵니다 — `task` 도구 설명의 "사용 요령" 문단에도 그 단어가 나오기 때문입니다. 그래서 `"- 이름: 설명"` 형태의 목록 줄만 파싱합니다. 실제로 이 파일을 처음 쓸 때 밟은 함정이라 그대로 남겨 두었습니다.
- `[6-5]` 가 이 파일의 심장입니다. `vague` 와 `clear` 두 미들웨어를 만들어 부모에게 보이는 목록을 나란히 찍습니다. 그리고 마지막 줄에서 `description.includes("SQL 전문가다")` 가 **`false`** 인 것을 확인합니다 — `systemPrompt` 는 부모에게 전달되지 않는다는 증거입니다.
- `[6-7]` 은 `isAsyncSubAgent()` 로 동기/비동기를 판별합니다. `graphId` 가 있느냐 없느냐가 전부라는 것이 출력으로 드러납니다. 비동기 서브에이전트는 Agent Protocol 서버가 있어야 실제로 돌기 때문에 정의와 판별까지만 하고 실행은 안 합니다.
- `[6-8]` 은 실행 가능한 코드가 아니라 **문자열로 인쇄되는 예시**입니다. 동적 서브에이전트는 `@langchain/quickjs` 가 필요한데 이 코스 프로젝트에는 안 깔려 있고, 무엇보다 그 코드는 **우리가 아니라 모델이 작성하는 것**이라 그 점을 분명히 하려고 일부러 문자열로 두었습니다.
- `[6-10]` 은 마지막에 `task` 호출 횟수를 세고 `subagent_type` 을 하나씩 찍습니다. 모델이 정말 3개를 병렬로 띄웠는지 확인하는 대목입니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 7개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 `// TODO: 여기에 작성` 아래가 비어 있습니다. `taskToolOf()` 와 `agentList()` 헬퍼는 미리 제공하니 그대로 쓰세요.

- `[문제 1]` 만 코드가 아니라 **주석으로 답하는 문제**입니다. 파일을 실행해도 아무것도 안 나옵니다. 정상입니다. "언제 서브에이전트를 쓰나" 는 이 스텝에서 가장 실무적인 판단이라 코드 없이 물어봅니다.
- `[문제 3]` 은 `before` 배열이 이미 주어져 있고 여러분은 `after` 를 만듭니다. **`systemPrompt` 는 건드리지 말라**는 제약이 핵심입니다 — 부모는 그걸 못 보기 때문에 아무리 고쳐봐야 라우팅이 안 바뀝니다. 이 제약을 지키면서 풀어야 6-5 의 교훈이 몸에 남습니다.
- `[문제 5]` 의 `candidates` 배열에는 `SubAgent` 2개와 `AsyncSubAgent` 2개가 섞여 있습니다. `d` 는 `model` 필드가 있어서 헷갈리게 해뒀지만 동기입니다 — 가르는 건 `graphId` 하나뿐입니다.
- `[문제 6]` 은 **일부러 고장난 코드**가 주어집니다. 먼저 그대로 돌려서 translator 가 엉뚱한 언어로 번역하는 걸 관찰한 뒤 고치세요. 관찰 없이 바로 고치면 왜 고쳤는지가 안 남습니다. 그리고 고칠 곳은 `translator` 가 아니라 **부모의 `systemPrompt`** 입니다 — 여기서 헤매는 사람이 많습니다.
- `[문제 7]` 의 `mockSearch` 도구는 이미 완성돼 있습니다. 여러분이 만들 것은 `researcher` 정의와 부모 프롬프트, 그리고 `task` 호출 횟수를 세는 코드입니다. `description` 에 병렬 지시를 안 넣으면 `[1,1,1]` 이 나올 겁니다.

```ts file="./exercise.ts"
```

### solution.ts

7문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요.

- `[정답 1]` 은 4가지 판단을 각각 설명한 뒤 마지막에 **기준 한 줄**로 압축합니다 — *"이 일이 컨텍스트를 많이 먹는데, 중간 과정은 안 봐도 되는가? 둘 다 예 → 서브에이전트."* 이 문장만 외워도 됩니다.
- `[정답 2]` 에서 놀라운 부분은 마지막 줄입니다. `generalPurposeAgent: false` + `subagents: []` 로 다 껐는데도 **`task` 도구 자체는 남아 있습니다**(`true`). 목록만 빈 도구가 모델에게 노출된 상태죠. 서브에이전트를 안 쓸 거면 미들웨어를 아예 안 넣는 게 낫다는 결론이 여기서 나옵니다.
- `[정답 3]` 은 `before`/`after` 를 나란히 출력한 뒤, `task` 설명에 `"SQL 전문가다"`(systemPrompt) 가 없다는 것을 `false` 로 확인시킵니다. 그리고 **description 작성 공식** — `"[동사]한다. [언제 부르는지]. [무엇을 반환하는지]."` + 형제가 헷갈리면 `"~는 못 한다"` 로 경계 긋기 — 를 정리합니다.
- `[정답 4]` 의 해설이 이 파일에서 가장 실무적입니다. `screener`(입력 큼 × 판단 단순 → 싼 모델)와 `analyst`(입력 작음 × 판단 중요 → 비싼 모델)로 나누는 이유를 설명하고, **"부모를 싼 모델로 바꾸는 건 대개 손해"** 라는 반대 방향의 조언도 답니다. 끝에 붙은 함정 — 싼 모델은 도구를 안 부르고도 "완료했습니다" 라고 보고하고 부모는 그걸 **믿는다**(`"The agent's outputs should generally be trusted"`) — 이 실전에서 가장 잡기 어려운 버그입니다.
- `[정답 5]` 는 "비동기 ≠ 병렬" 을 못 박습니다. 동기 서브에이전트도 이미 병렬로 돌고, 비동기의 가치는 **"부모가 안 기다리는 것"** 하나뿐입니다. *"빨리 하고 싶어서 비동기를 쓰는 거라면 잘못 고른 것"* 이라는 문장이 결론입니다.
- `[정답 6]` 이 이 파일의 하이라이트입니다. 고장난 버전과 고친 버전을 **둘 다 실행**해 나란히 보여주고, 마지막에 부모가 자식에게 실제로 넘긴 `description` 을 출력합니다. 고친 버전에서는 거기에 "일본어로" 가 박혀 있는 것을 눈으로 볼 수 있습니다. 해설 주석에는 *"task 의 description 은 처음 만난 외주 업체에게 보내는 작업 지시서"* 라는 비유를 남겨 두었습니다.
- `[정답 7]` 은 `perMessage` 배열로 **병렬 여부를 기계적으로 검증**합니다. `[3]` 이면 병렬, `[1,1,1]` 이면 순차입니다. 그리고 마지막 두 함정 — 병렬 자식이 같은 파일에 쓰면 조용히 덮어쓴다는 것, `tools` 를 생략하면 자식이 `task` 를 상속받아 재귀가 열린다는 것 — 을 정리합니다. 정답 코드에서 `researcher` 에 `tools: [mockSearch]` 를 **명시한 이유**가 바로 그 재귀 차단입니다.

```ts file="./solution.ts"
```
