# Deep Agents (TypeScript) 완전 학습 코스

**12개 스텝**으로 LangChain 의 Deep Agents 하네스를 익힙니다.

`createAgent` 로 만든 에이전트에게 *"경쟁사 3곳을 조사해서 보고서를 써줘"* 를 시켜 보면 대개 이렇게 됩니다 — 처음엔 잘 하다가, 중간에 뭘 하던 중이었는지 잊고, 컨텍스트가 터지고, 대충 얼버무린 결론을 냅니다.

**Deep Agent 는 이 실패를 고치는 하네스(harness)입니다.** 마법이 아니라 `createAgent` + 엄선된 미들웨어 묶음이고, 이 코스는 그 뚜껑을 열어서 보여줍니다.

---

## 시작하기 (5분)

```bash
# 1. 실습 프로젝트로 이동
cd docs/reference/deepagent/project

# 2. 의존성 설치 (Node.js 22+ 필요)
npm install

# 3. API 키 설정
cp .env.example .env
# .env 를 열어 ANTHROPIC_API_KEY 를 채웁니다

# 4. 첫 예제 실행
npx tsx ../step-02-quickstart/practice.ts
```

자세한 셋업은 [실습 프로젝트 셋업](./project/) 을 보세요.

> **선행 지식**: [LangChain (TypeScript) 코스](../langchain/) 의 Step 01~14 를 먼저 보시길 강하게 권합니다.
> 특히 [Step 08 — createAgent](../langchain/step-08-create-agent/), [Step 11 — 내장 미들웨어](../langchain/step-11-middleware-builtin/),
> [Step 14 — 컨텍스트와 런타임](../langchain/step-14-context-runtime/) 은 이 코스의 전제입니다.
> Deep Agent 는 그 위에 얹힌 얇은 층이라, 아래층을 모르면 뚜껑을 열어도 안 보입니다.

---

## Deep Agent 의 4대 기둥

이 코스 전체가 이 네 가지를 하나씩 파고듭니다. 각각은 **구체적인 실패를 고치기 위해** 존재합니다.

| 기둥 | 고치는 실패 | 다루는 스텝 |
|---|---|---|
| **계획** (`write_todos`) | 긴 작업 중간에 목표를 잊고 표류(drift) | [Step 03](step-03-planning-todos/) |
| **파일시스템** (컨텍스트 오프로딩) | 중간 결과가 컨텍스트를 다 잡아먹고 터짐 | [Step 04](step-04-filesystem/), [Step 05](step-05-backends/) |
| **서브에이전트** (컨텍스트 격리) | 곁가지 조사가 본 대화를 오염시킴 | [Step 06](step-06-subagents/) |
| **상세 시스템 프롬프트** | 도구를 갖고도 언제 쓸지 모름 | [Step 07](step-07-prompting/) |

관통하는 한 문장: **컨텍스트 윈도우는 유한한 자원이고, Deep Agent 는 그 자원을 관리하는 기법의 묶음이다.**

---

## 커리큘럼

### 1부 — 개념과 첫걸음 (Step 01~02)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-why-deep-agents/) | Deep Agent란 무엇인가 | 얕은 에이전트의 실패를 **직접 재현**, 4대 기둥, `createAgent` 와 비교 |
| [02](step-02-quickstart/) | 첫 Deep Agent | 설치, 엔트리포인트 3종, **`createDeepAgent` 는 `await` 필요**, 옵션 전체 표 |

### 2부 — 4대 기둥 (Step 03~07)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [03](step-03-planning-todos/) | 계획 도구 (`write_todos`) | todo 가 **매 턴 컨텍스트에 재주입**되는 원리, 계획이 곧 프롬프트 |
| [04](step-04-filesystem/) | 가상 파일시스템 | `ls`/`read_file`/`write_file`/`edit_file`/`glob`/`grep`, **외부 기억장치라는 발상** |
| [05](step-05-backends/) | 백엔드와 권한 | State/Filesystem/Store/Composite/샌드박스 비교, **LLM에게 디스크를 준다는 의미** |
| [06](step-06-subagents/) | 서브에이전트 | `task` 도구, **일 나누기가 아니라 컨텍스트 보호**, description 이 라우팅을 결정 |
| [07](step-07-prompting/) | 시스템 프롬프트 설계 | 내장 프롬프트 뜯어보기, `prefix` vs `base: null` |

### 3부 — 조립과 통제 (Step 08~10)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [08](step-08-middleware/) | 미들웨어 조합 | **`createAgent` + 미들웨어로 `createDeepAgent` 를 직접 재현**, 필요한 기능만 빌려쓰기 |
| [09](step-09-hitl-permissions/) | HITL과 권한 제어 | `interruptOn` vs `permissions`, 위험 등급별 정책 |
| [10](step-10-memory-skills/) | 장기 메모리와 스킬 | `/memories` 경로, **스킬 vs 도구 vs 서브에이전트 3구분** |

### 4부 — 프로덕션 (Step 11~12)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [11](step-11-streaming-production/) | 스트리밍과 프로덕션 | 중첩 서브에이전트 이벤트, 진행상황 UI, **비용이 곱해진다** |
| [12](step-12-final-project/) | 종합 프로젝트 | 딥 리서치 에이전트를 처음부터 끝까지 + 확장 과제와 해설 |

---

## 각 스텝의 구성

```
step-06-subagents/
├── index.md       ← 교재 본문
├── practice.ts    ← 교재의 모든 예제
├── exercise.ts    ← 연습문제
└── solution.ts    ← 정답 + 해설
```

```bash
npx tsx docs/reference/deepagent/step-06-subagents/practice.ts
```

---

## ⚠️ 읽기 전에 — 두 가지 경고

### 1. 결과가 매번 다릅니다

LLM 은 확률적입니다. 출력 예시는 `**출력 예시** (모델 응답이므로 매번 다릅니다)` 로 표기합니다.
**문장이 다른 건 정상이고, 구조나 이벤트 순서가 다른 건 문제입니다.** 후자에 집중하세요.

### 2. Deep Agent 는 비쌉니다

서브에이전트 하나마다 **별도의 컨텍스트**가 생깁니다. 토큰이 더해지는 게 아니라 **곱해집니다.**
얕은 에이전트로 10원 나오던 작업이 Deep Agent 로는 수백 원이 될 수 있습니다.

이 코스는 그래서 **"언제 Deep Agent 를 쓰면 안 되는가"** 도 같은 비중으로 다룹니다.
짧은 작업에는 계획이 오버헤드고, 서브에이전트는 지연을 곱합니다.
[Step 01](step-01-why-deep-agents/) 의 비교표와 [Step 11](step-11-streaming-production/) 의 비용 통제를 반드시 보세요.

실습 중 비용이 걱정되면 각 스텝의 예제를 작은 모델로 바꿔 돌리세요 — 모든 스텝에 안내가 있습니다.

---

## 이 코스가 특히 신경 쓴 것

`⚠️ 함정` 블록에 모아둔 것들입니다. 조용히 틀리는 것들:

- `createDeepAgent` 는 **async 입니다** — `await` 를 빼면 Promise 에 `.invoke` 를 부르게 됩니다 (Step 02)
- `systemPrompt` 문자열은 내장 프롬프트를 **대체하지 않고 앞에 붙습니다** (Step 02, 07)
- 기본 `general-purpose` 서브에이전트가 **자동으로 켜져 있어** 예상 못 한 토큰을 태웁니다 (Step 02, 06)
- 가상 파일시스템은 진짜 디스크가 아닙니다 — 기본값에선 **실행이 끝나면 사라집니다** (Step 04)
- 파일로 오프로딩해도 에이전트가 **다시 읽으면 결국 컨텍스트에 들어옵니다** (Step 04)
- 서브에이전트는 부모 대화를 **못 봅니다** — 그게 의도지만, 모르고 쓰면 맥락 없는 답이 옵니다 (Step 06)
- 서브에이전트 `description` 이 모호하면 부모가 **안 부르거나 잘못 부릅니다** (Step 06)
- 요약 미들웨어가 파일 경로를 날리면 에이전트가 **자기가 쓴 파일을 잊습니다** (Step 08)
- `checkpointer` 없으면 `interrupt` 가 **동작하지 않습니다** (Step 09)
- 메모리 네임스페이스에 사용자 구분이 없으면 **정보가 샙니다** (Step 10)
- 긴 실행에 `MemorySaver` 를 쓰면 재시작 시 **전부 소실됩니다** (Step 11)

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Node.js | 22+ |
| 언어 | TypeScript (ESM) |
| 실행기 | `tsx` |
| 기본 모델 | Anthropic |
| 검증 버전 | `deepagents@1.11.0`, `langchain@1.5.3`, `@langchain/core@1.2.3` |
| 공식 문서 | [docs.langchain.com/oss/javascript/deepagents](https://docs.langchain.com/oss/javascript/deepagents/overview) |
| 저장소 | [langchain-ai/deepagentsjs](https://github.com/langchain-ai/deepagentsjs) |

> `deepagents` 는 `langchain` / `@langchain/core` 를 **peer dependency** 로 선언합니다.
> 버전을 여러분 앱이 통제하고, 모두가 하나의 사본을 공유하게 하기 위해서입니다.
> npm 7+ / pnpm 8+ 는 자동 설치하지만, **Yarn 사용자는 직접 추가해야 합니다.** 자세한 건 [Step 02](step-02-quickstart/) 에서.

---

## 관련 문서

- [LangChain (TypeScript) 코스](../langchain/) — 선행 코스
- [AI — 에이전트 패턴](/ai/05-agent/02-agentPattern)
- [AI — 에이전트 프로젝트](/ai/05-agent/03-agentProject)
- [AI — MCP](/ai/05-agent/04-mcp)
