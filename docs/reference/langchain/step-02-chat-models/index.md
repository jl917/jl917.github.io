# Step 02 — 챗 모델과 파라미터

> **학습 목표**
> - 챗 모델 **추상화**가 왜 필요한지 설명하고, provider 를 코드 변경 없이 갈아끼운다
> - `initChatModel` / `"provider:model"` 문자열 / `new ChatAnthropic()` **세 가지 생성 방식**을 구분해서 쓴다
> - `temperature` `maxTokens` `topP` `stopSequences` `timeout` `maxRetries` 가 **각각 무엇을 바꾸는지** 안다
> - `invoke` / `batch` / `stream` 세 호출 방식을 상황에 맞게 고른다
> - `usage_metadata` 를 읽어 **입력/출력/캐시/추론 토큰**을 구분하고 비용을 계산한다
> - reasoning(thinking) 모델을 켜고, 추론 블록과 추론 토큰을 다룬다
>
> **선행 스텝**: [Step 01 — 환경 구축과 첫 모델 호출](../step-01-setup/)
> **예상 소요**: 70분

Step 01 에서 모델을 한 번 호출해 봤습니다. `invoke` 하니 답이 나왔죠. 하지만 그건 자동차 시동을 건 것에 불과합니다. 실제로 에이전트를 만들기 시작하면 곧바로 이런 질문에 부딪힙니다. "왜 같은 질문에 매번 다른 답이 오지?", "왜 답이 문장 중간에서 끊겼지?", "이번 달 API 요금이 왜 이렇게 나왔지?", "Claude 로 짠 코드를 GPT 로 돌리려면 얼마나 고쳐야 하지?"

이 스텝은 그 질문들에 답합니다. **챗 모델은 앞으로 배울 모든 것(도구, 에이전트, 미들웨어)의 바닥**입니다. 바닥에서 파라미터를 잘못 잡으면 위층에서 벌어지는 이상 현상의 원인을 영영 못 찾습니다. 특히 이 스텝의 함정들은 **에러를 내지 않습니다** — 응답이 조용히 잘리고, 파라미터가 조용히 무시되고, `temperature: 0` 인데 조용히 답이 달라집니다.

이 스텝의 모든 내용은 아래 버전에서 검증했습니다.

| 패키지 | 검증 버전 |
|---|---|
| `langchain` | 1.5.3 |
| `@langchain/core` | 1.2.3 |
| `@langchain/anthropic` | 1.5.1 |
| `@langchain/openai` | 1.5.5 |

---

## 2-1. 챗 모델 추상화 — 왜 provider 를 갈아끼울 수 있어야 하나

Anthropic SDK 를 직접 쓰면 이렇게 됩니다.

```ts
// LangChain 없이 — Anthropic SDK 직접 호출 (참고용, 실습 아님)
const res = await client.messages.create({
  model: "claude-sonnet-4-6",
  max_tokens: 1024,                                  // snake_case
  messages: [{ role: "user", content: "안녕" }],
});
console.log(res.content[0].text);                    // content 는 블록 배열
console.log(res.usage.input_tokens);                 // usage 위치
console.log(res.stop_reason);                        // 종료 이유 필드명
```

OpenAI SDK 는 같은 일을 이렇게 합니다.

```ts
// LangChain 없이 — OpenAI SDK 직접 호출 (참고용, 실습 아님)
const res = await client.chat.completions.create({
  model: "gpt-5.5",
  max_completion_tokens: 1024,                       // 이름이 다르다
  messages: [{ role: "user", content: "안녕" }],
});
console.log(res.choices[0].message.content);         // choices 배열
console.log(res.usage.prompt_tokens);                // input 이 아니라 prompt
console.log(res.choices[0].finish_reason);           // stop_reason 이 아니라 finish_reason
```

**같은 개념에 다른 이름이 붙어 있습니다.** `max_tokens` vs `max_completion_tokens`, `usage.input_tokens` vs `usage.prompt_tokens`, `stop_reason` vs `finish_reason`. 응답에서 텍스트를 꺼내는 경로조차 `content[0].text` vs `choices[0].message.content` 로 다릅니다.

문제는 이름이 다르다는 것 자체가 아니라, **이 이름들이 여러분 코드 곳곳에 스며든다**는 점입니다. 프롬프트 조립, 로깅, 비용 집계, 재시도, 도구 호출 파싱 — 전부 provider 고유 필드명에 묶입니다. 그 상태에서 "이번 분기부터 비용 절감을 위해 일부 트래픽을 다른 모델로 돌리자"는 결정이 내려오면, 갈아끼우는 게 아니라 다시 쓰는 일이 됩니다.

LangChain 의 챗 모델 추상화는 이 이름들을 **하나로 정규화**합니다.

```ts
const res = await model.invoke("안녕");
res.text;                       // 어느 provider 든 여기에 텍스트
res.usage_metadata;             // 어느 provider 든 input_tokens/output_tokens/total_tokens
res.contentBlocks;              // 어느 provider 든 표준화된 블록 배열
res.tool_calls;                 // 어느 provider 든 같은 shape
```

이것이 이 코스 전체가 서 있는 계약입니다. 앞으로 배울 `createAgent`, 미들웨어, 스트리밍은 전부 "모델은 `invoke` 를 갖고 `AIMessage` 를 돌려준다"는 이 계약 위에 지어져 있습니다. 그래서 에이전트를 통째로 다른 모델로 바꾸는 게 문자열 한 줄 수정이 됩니다 (2-8 에서 직접 해봅니다).

> 💡 **실무 팁**: 추상화의 진짜 값어치는 "provider 를 바꾸는 것"보다 **"provider 를 동시에 여러 개 쓰는 것"** 에서 나옵니다. 실무 시스템은 대개 한 모델만 쓰지 않습니다. 분류·라우팅 같은 값싼 작업은 작은 모델, 최종 답변 생성은 큰 모델, 특정 기능은 그것만 지원하는 provider — 이렇게 섞습니다. 이때 호출 코드가 전부 같은 인터페이스면 모델을 **설정값**으로 다룰 수 있습니다. 2-7 에서 이 전략을 다룹니다.

---

## 2-2. 모델을 만드는 세 가지 방법

LangChain 에서 모델 인스턴스를 얻는 길은 세 가지입니다. 셋 다 정답이고, **쓰는 자리가 다릅니다.**

### (A) `initChatModel` + `"provider:model"` 문자열

```ts
import { initChatModel } from "langchain";

const model = await initChatModel("anthropic:claude-sonnet-4-6", {
  temperature: 0,
  maxTokens: 1024,
});
```

`initChatModel` 은 `langchain` 패키지가 직접 export 합니다. 접두사(`anthropic:`)를 보고 알맞은 통합 패키지를 **동적으로 import** 하기 때문에 **`await` 가 필요합니다.** 이걸 빼먹으면 `model` 이 Promise 가 되고, `model.invoke is not a function` 을 보게 됩니다.

지원하는 provider 접두사는 `langchain` 1.5.3 기준 다음과 같습니다.

```
openai            anthropic         azure_openai      cohere
google            google-vertexai   google-vertexai-web  google-genai
ollama            mistralai         mistral           groq
bedrock           aws               deepseek          xai
cerebras          fireworks         together          perplexity
```

접두사를 생략하면 모델 이름으로 provider 를 추론합니다(`"claude-sonnet-4-6"` → anthropic). 편하지만 **이 코스에서는 항상 접두사를 붙입니다** — 추론에 기대면 모델 이름이 바뀔 때 조용히 엉뚱한 provider 로 갈 수 있습니다.

해당 통합 패키지는 **따로 설치되어 있어야** 합니다. `initChatModel("anthropic:...")` 은 `@langchain/anthropic` 이 없으면 런타임에 실패합니다. 문자열이 마법으로 패키지를 만들어내지는 않습니다.

### (B) 문자열만 (모델 인스턴스 없이)

```ts
import { createAgent } from "langchain";

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",   // 문자열을 그대로 넘긴다
  tools: [],
});
```

`createAgent` 같은 상위 API 는 문자열을 받으면 내부에서 알아서 `initChatModel` 을 부릅니다. 모델을 따로 만들 필요조차 없습니다. Step 08 에서 본격적으로 씁니다.

### (C) `new ChatAnthropic()` 직접 생성

```ts
import { ChatAnthropic } from "@langchain/anthropic";

const model = new ChatAnthropic({
  model: "claude-sonnet-4-6",
  temperature: 0,
  maxTokens: 1024,
  topK: 40,                                  // ← Anthropic 에만 있는 파라미터
  thinking: { type: "disabled" },            // ← Anthropic 에만 있는 파라미터
});
```

`await` 가 없습니다(동적 import 가 아니니까). 그리고 **provider 고유 파라미터가 타입으로 잡힙니다.** `topK`, `thinking`, `contextManagement`, `betas` 같은 건 Anthropic 에만 있고, `new ChatAnthropic()` 으로 만들 때만 자동완성과 타입 체크를 받습니다.

### 세 방식 비교

| | (A) `initChatModel` | (B) 문자열만 | (C) `new ChatAnthropic()` |
|---|---|---|---|
| import | `langchain` | (없음) | `@langchain/anthropic` |
| `await` | **필요** | 해당 없음 | 불필요 |
| provider 교체 | 문자열 한 줄 | 문자열 한 줄 | **클래스·import 를 바꿔야 함** |
| 런타임 교체(설정/환경변수) | ✅ 쉽다 | ✅ 쉽다 | ❌ 분기 코드 필요 |
| provider 고유 파라미터 | 넘어는 가지만 **타입 체크 없음** | 넘기기 어려움 | ✅ 완전한 타입 체크 |
| 오타 검출 | ❌ **안 됨** (아래 함정) | 해당 없음 | ✅ 컴파일 에러 |
| 번들 크기 | 동적 import | 동적 import | 정적 import |
| 주로 쓰는 자리 | 앱 진입점, 설정 기반 | 에이전트 정의 | 라이브러리, 고유 기능 사용 |

> ⚠️ **함정 — `initChatModel` 의 옵션은 타입 체크를 받지 않는다**
>
> `initChatModel` 의 두 번째 인자 타입은 `Partial<Record<string, any>>` 입니다. 무슨 뜻이냐면, **아무 키나 넣어도 컴파일이 통과합니다.**
>
> ```ts
> const model = await initChatModel("anthropic:claude-sonnet-4-6", {
>   temperatur: 0.9,             // ← 오타. 에러 안 남
>   completelyMadeUpParam: 123,  // ← 존재하지 않는 파라미터. 에러 안 남
> });
> ```
>
> `tsc --noEmit --strict` 를 돌려도 **에러가 0개**입니다. 실행도 됩니다. 다만 `temperature` 는 설정되지 않은 채로요. 그래서 "temperature 를 0.9 로 줬는데 왜 창의적이지 않지?" 하며 며칠을 헤매게 됩니다.
>
> 이건 문자열 기반 유연성의 대가입니다. **방어법**: (1) 파라미터를 튜닝하는 중이라면 `new ChatAnthropic()` 으로 잠깐 바꿔서 타입 체크를 받아보세요. (2) 아래 2-3 처럼 `model.invoke` 전에 실제 적용된 파라미터를 한 번 찍어보세요. (3) 설정 객체를 zod 로 검증한 뒤 넘기세요.

> 💡 **실무 팁 — 어떤 걸 언제 쓰나**
>
> 대부분의 앱은 **(A) 를 진입점에 딱 한 번** 두고 나머지 코드에는 만들어진 `model` 인스턴스를 주입하는 형태가 좋습니다. provider 선택이 환경변수 한 줄이 되고, 테스트에서 가짜 모델로 바꿔치기하기도 쉽습니다.
>
> ```ts
> // src/lib/model.ts — 앱 전체가 여기서 모델을 받아간다
> export const model = await initChatModel(
>   process.env.MODEL ?? "anthropic:claude-sonnet-4-6",
>   { temperature: 0 },
> );
> ```
>
> **(C)** 는 Anthropic 의 `thinking` 이나 OpenAI 의 `reasoning` 처럼 provider 고유 기능을 정면으로 쓸 때만 꺼내세요. 그 순간 그 코드는 그 provider 에 묶입니다 — 그게 나쁜 건 아니지만, **묶인다는 걸 알고** 묶여야 합니다.

---

## 2-3. 파라미터 완전 해부

파라미터를 "창의성 슬라이더" 정도로 이해하고 넘어가면 나중에 반드시 대가를 치릅니다. 각각이 **실제로 무엇을 바꾸는지** 봅시다.

모델이 다음 토큰을 고르는 과정은 이렇습니다. 모델은 어휘 전체(수만 개 토큰)에 대해 확률 분포를 뱉습니다. 그 분포에서 **하나를 뽑아야** 합니다. `temperature`, `topP`, `topK` 는 전부 **"어떻게 뽑을 것인가"** 를 조절합니다. `maxTokens`, `stopSequences` 는 **"언제 멈출 것인가"** 를 조절합니다. `timeout`, `maxRetries`, `maxConcurrency` 는 모델이 아니라 **HTTP 클라이언트**를 조절합니다. 이 세 부류는 성격이 완전히 다릅니다.

### 뽑는 방법을 바꾸는 것들

**`temperature`** — 확률 분포를 평평하게(높을수록) 또는 뾰족하게(낮을수록) 만듭니다. `0` 에 가까우면 가장 확률 높은 토큰만 거의 항상 고르고, 높으면 낮은 확률 토큰도 뽑힐 여지가 생깁니다. Anthropic 은 `0~1`, OpenAI 는 `0~2` 범위입니다 — **범위가 다릅니다.** OpenAI 기준으로 `temperature: 1.5` 를 짜 놓고 Anthropic 으로 옮기면 범위를 벗어납니다.

**`topP`** (nucleus sampling) — 토큰을 확률 내림차순으로 줄 세운 뒤, 누적 확률이 `topP` 에 닿을 때까지만 후보로 남기고 나머지는 버립니다. `topP: 0.9` 면 "상위 90% 확률 질량 안에 드는 토큰들"만 후보입니다. 꼬리에 있는 이상한 토큰을 잘라내는 장치입니다.

**`topK`** — 확률 상위 K 개만 후보로 남깁니다. `@langchain/anthropic` 에는 있지만 **`@langchain/openai` 에는 없습니다** (아래 지원 표 참고).

> ⚠️ **함정 — `temperature` 와 `topP` 를 동시에 만지지 마라**
>
> `@langchain/anthropic` 의 `topP` JSDoc 이 직접 이렇게 말합니다.
>
> > "Note that you should either alter temperature or top_p, but not both."
>
> 둘 다 샘플링 분포를 자르는 장치라서 함께 쓰면 상호작용이 예측 불가능해집니다. `temperature: 0.2` 로 이미 분포가 뾰족한데 `topP: 0.5` 로 또 자르면, 두 설정 중 무엇이 결과를 만든 건지 아무도 모릅니다. **하나만 고르세요.** 실무에서는 대개 `temperature` 만 만지고 `topP` 는 기본값으로 둡니다.

> ⚠️ **함정 (중요) — `temperature: 0` 은 결정성을 보장하지 않는다**
>
> 이건 거의 모든 LLM 입문자가 믿었다가 배신당하는 명제입니다. `temperature: 0` 을 주고 같은 프롬프트를 두 번 보내면 **다른 답이 올 수 있습니다.**
>
> `temperature: 0` 이 약속하는 건 "매번 같은 출력"이 아니라 **"각 스텝에서 확률이 가장 높은 토큰을 고른다"** 입니다. 그런데 그 확률값 자체가 매번 미세하게 다릅니다.
>
> - **부동소수점 비결정성**: GPU 연산은 배치 크기·커널 스케줄링에 따라 덧셈 순서가 달라지고, 부동소수점 덧셈은 결합법칙이 성립하지 않습니다. 서버가 지금 얼마나 바쁜지(= 여러분 요청이 어떤 배치에 묶였는지)에 따라 logit 마지막 자리가 흔들립니다.
> - **1·2위가 거의 붙어 있을 때**: 확률 0.5001 vs 0.4999 인 상황에서 마지막 자리가 흔들리면 **순위가 뒤집힙니다.** 토큰 하나가 갈리면 그 뒤 문장 전체가 다른 길로 갑니다.
> - **MoE 라우팅, 인프라 변경**: provider 는 예고 없이 하드웨어와 서빙 스택을 바꿉니다. 같은 모델 이름이 6개월 뒤 같은 답을 준다는 보장은 어디에도 없습니다.
>
> **그래서 어떻게 하나**: `temperature: 0` 을 "결정적"이 아니라 **"편차를 줄이는 최선의 노력"** 으로 이해하세요. 그리고 —
> - 테스트를 **정확한 문자열 일치**로 짜지 마세요. 반드시 깨집니다. 구조(JSON 스키마 만족 여부), 포함 관계, 또는 LLM 채점(Step 19)으로 검증하세요.
> - 진짜로 같은 입력에 같은 출력이 필요하면 **캐시**를 쓰세요(2-9). 캐시만이 유일하게 결정성을 보장합니다.
> - 출력 형식이 흔들리는 게 문제라면 temperature 를 낮추는 것보다 **구조화된 출력**(Step 05)이 훨씬 확실한 해법입니다.

### 멈추는 시점을 바꾸는 것들

**`maxTokens`** — 생성할 **출력** 토큰의 상한입니다. 입력 토큰과는 무관하고, 컨텍스트 윈도우와도 다른 개념입니다.

> ⚠️ **함정 (중요) — `maxTokens` 를 넘기면 응답이 조용히 잘린다**
>
> `maxTokens: 20` 을 주고 긴 답을 요구하면 어떻게 될까요? **에러가 나지 않습니다.** 20 토큰까지 만들고 문장 중간에서 뚝 끊긴 `AIMessage` 가 정상 응답인 척 돌아옵니다.
>
> ```
> **출력 예시** (모델 응답이므로 매번 다릅니다)
> text: 광합성은 식물이 빛 에너지를 이용해 이산화탄소와 물로부
> ```
>
> `res.text` 만 보고 있으면 이게 완성된 답인지 잘린 답인지 **구분할 방법이 없습니다.** JSON 을 요구했다면 더 나쁩니다 — 잘린 JSON 은 파싱에 실패하고, 여러분은 "모델이 JSON 을 못 만든다"고 오해하며 프롬프트를 고치기 시작합니다. 진짜 원인은 `maxTokens` 인데요.
>
> **반드시 종료 이유를 확인하세요.** 필드명이 provider 마다 다릅니다.
>
> | provider | 위치 | 정상 종료 | **잘림** |
> |---|---|---|---|
> | Anthropic | `res.response_metadata.stop_reason` | `"end_turn"` | `"max_tokens"` |
> | OpenAI | `res.response_metadata.finish_reason` | `"stop"` | `"length"` |
> | 공통(추정) | `res.usage_metadata.output_tokens` | `< maxTokens` | `=== maxTokens` |
>
> provider 중립적으로 확인하는 헬퍼를 하나 만들어 두세요.
>
> ```ts
> function isTruncated(res: AIMessage): boolean {
>   const meta = res.response_metadata as Record<string, unknown>;
>   return meta.stop_reason === "max_tokens" || meta.finish_reason === "length";
> }
> ```
>
> **`maxTokens` 는 "비용 상한"이 아니라 "이 길이를 넘으면 실패로 친다"는 선언으로 쓰세요.** 비용을 아끼려고 낮게 잡는 건 최악의 선택입니다 — 잘린 답도 만든 토큰만큼 요금은 그대로 내면서, 쓸모는 없으니까요. 돈은 버리고 결과도 못 얻습니다.

**`stopSequences`** — 이 문자열이 나오면 즉시 생성을 멈춥니다. 중요한 두 가지 성질이 있습니다. (1) **정지 문자열 자체는 출력에 포함되지 않습니다.** (2) `stopSequences` 로 멈춘 것도 "잘린 것"과는 다른 정상 종료입니다(Anthropic `stop_reason: "stop_sequence"`).

```ts
const model = new ChatAnthropic({
  model: "claude-sonnet-4-6",
  stopSequences: ["\n\n관련 항목:"],   // 이 헤더가 시작되면 거기서 끝
});
```

`stopSequences` 는 "모델이 사족을 붙이는 것"을 막는 데 유용합니다. 다만 **모델이 그 문자열을 낼 거라고 100% 확신할 때만** 쓰세요. 안 나오면 그냥 아무 일도 안 일어납니다.

### 모델이 아니라 HTTP 를 바꾸는 것들

**`maxRetries`** — 실패한 요청의 재시도 횟수. **기본값 6** 이고, **지수 백오프**로 간격을 늘려가며 재시도합니다. 재시도 대상은 네트워크 에러, `429`(레이트리밋), `5xx`(서버 에러)입니다. `401`(인증 실패), `404` 같은 클라이언트 에러는 재시도해도 소용없으니 **즉시 던집니다.**

**`maxConcurrency`** — 이 모델 인스턴스가 동시에 날릴 수 있는 요청 수. **기본값 `Infinity`** 입니다. 즉 **아무 제한이 없습니다.** 이게 2-4 의 `batch` 함정으로 이어집니다.

**`timeout`** — 한 요청을 얼마나 기다릴지. 그런데…

> ⚠️ **함정 (중요) — 모델마다 지원 파라미터가 다르다. `timeout` 이 대표 사례.**
>
> 실제로 `tsc --strict` 로 검증한 결과입니다. **`timeout` 은 `ChatAnthropic` 생성자에 존재하지 않습니다.**
>
> ```ts
> new ChatOpenAI({ model: "gpt-5.5", timeout: 30000 });        // ✅ 컴파일 통과
> new ChatAnthropic({ model: "claude-sonnet-4-6", timeout: 30000 });
> // ❌ error TS2769: Object literal may only specify known properties,
> //    and 'timeout' does not exist in type 'ChatAnthropicInput'.
> ```
>
> `ChatAnthropic` 에서 타임아웃을 주려면 **`clientOptions.timeout`** 을 쓰거나, **호출 옵션**으로 넘겨야 합니다.
>
> ```ts
> // 방법 1: 클라이언트 옵션 (밀리초)
> new ChatAnthropic({ model: "claude-sonnet-4-6", clientOptions: { timeout: 30000 } });
>
> // 방법 2: 호출 시점 (밀리초) — provider 무관하게 동작. 이쪽을 권장.
> await model.invoke("안녕", { timeout: 30000 });
> ```
>
> **여기서 진짜 무서운 부분**: 위 컴파일 에러는 `new ChatAnthropic()` 을 썼기 때문에 **잡힌** 것입니다. 같은 걸 `initChatModel` 로 넘기면 (2-2 함정에서 봤듯) 타입 체크가 없으므로 **에러 없이 그냥 무시됩니다.**
>
> ```ts
> const m = await initChatModel("anthropic:claude-sonnet-4-6", { timeout: 30 });
> // 에러 없음. 타임아웃도 없음. 30초라고 믿고 있지만 아무 일도 안 일어남.
> ```
>
> 게다가 단위도 함정입니다. LangChain 문서 예시에는 `timeout: 30` 처럼 적혀 있어 초 단위처럼 보이지만, **`RunnableConfig.timeout` 의 JSDoc 은 "Timeout for this call in milliseconds"** 라고 명시합니다. `timeout: 30` 은 30초가 아니라 **30밀리초** 입니다. 그 값이 실제로 먹으면 모든 요청이 즉시 타임아웃됩니다.

### 지원 파라미터 매트릭스 (실제 타입 체크로 검증)

| 파라미터 | `ChatAnthropic` | `ChatOpenAI` | 비고 |
|---|:---:|:---:|---|
| `model` | ✅ | ✅ | |
| `temperature` | ✅ | ✅ | **범위가 다름** (0~1 vs 0~2) |
| `maxTokens` | ✅ | ✅ | |
| `topP` | ✅ | ✅ | temperature 와 **동시 사용 금지** |
| `topK` | ✅ | ❌ | Anthropic 전용 |
| `stopSequences` | ✅ | ✅ | |
| `stop` | ❌ | ✅ | OpenAI 는 둘 다 받음 |
| `maxRetries` | ✅ | ✅ | 기본 6 |
| `maxConcurrency` | ✅ | ✅ | 기본 **Infinity** |
| `timeout` | ❌ | ✅ | Anthropic 은 `clientOptions.timeout` |
| `streaming` | ✅ | ✅ | |
| `cache` | ✅ | ✅ | 2-9 |
| `thinking` | ✅ | ❌ | Anthropic 전용 (2-6) |
| `reasoning` | ❌ | ✅ | OpenAI 전용 (2-6) |
| `clientOptions` | ✅ | ✅ | |

이 표가 이 스텝에서 가장 실용적인 자산입니다. **"어느 provider 에서나 안전한 공통 분모"** 는 `model`, `temperature`, `maxTokens`, `topP`, `stopSequences`, `maxRetries`, `maxConcurrency` 정도입니다. 그 밖의 것을 쓰는 순간 provider 에 묶입니다.

> 💡 **실무 팁**: provider 별 분기가 필요하면 이렇게 좁은 지점에 격리하세요. 호출부는 계속 provider 를 모릅니다.
>
> ```ts
> type Provider = "anthropic" | "openai";
>
> function buildModel(provider: Provider) {
>   if (provider === "anthropic") {
>     return new ChatAnthropic({
>       model: "claude-sonnet-4-6",
>       maxTokens: 2048,
>       clientOptions: { timeout: 30_000 },   // ← 여기만 다르다
>     });
>   }
>   return new ChatOpenAI({
>     model: "gpt-5.5",
>     maxTokens: 2048,
>     timeout: 30_000,                        // ← 여기만 다르다
>   });
> }
> ```

---

## 2-4. invoke / batch / stream — 세 호출 방식

모델은 `Runnable` 인터페이스를 구현합니다. 핵심 메서드가 셋입니다.

### `invoke` — 하나 보내고 하나 받는다

```ts
const res = await model.invoke("광합성을 두 문장으로 설명해줘.");
console.log(res.text);
```

가장 단순합니다. 문자열 하나 또는 메시지 배열을 받고, `AIMessage` 하나를 돌려줍니다. **모델이 답을 다 만들 때까지 기다립니다.** 긴 답이면 사용자는 수십 초간 빈 화면을 봅니다.

### `stream` — 만들어지는 대로 받는다

```ts
const stream = await model.stream("광합성을 두 문장으로 설명해줘.");
for await (const chunk of stream) {
  process.stdout.write(chunk.text);
}
```

`AIMessageChunk` 를 순차적으로 받습니다. 총 소요 시간은 `invoke` 와 비슷하지만 **첫 글자가 나오는 시간(TTFT)이 극적으로 짧습니다.** 사용자에게 보여주는 모든 곳에서는 `stream` 이 기본값이어야 합니다. 자세한 건 Step 09 에서 다룹니다.

### `batch` — 여러 개를 동시에

```ts
const inputs = ["고양이를 한 문장으로", "강아지를 한 문장으로", "앵무새를 한 문장으로"];
const results = await model.batch(inputs);
results.forEach((r, i) => console.log(`[${i}] ${r.text}`));
```

`batch` 는 **입력 순서와 출력 순서가 일치합니다.** `results[0]` 은 반드시 `inputs[0]` 의 답입니다. 이건 보장됩니다. (Step 07 에서 볼 **병렬 도구 호출**은 순서 보장이 없습니다 — 헷갈리지 마세요.)

여기서 이름이 사람을 속입니다. `batch` 는 OpenAI Batch API 같은 **"묶어서 싸게 처리하는 오프라인 배치"가 아닙니다.** 그냥 **N 개의 요청을 병렬로 날리는 것**입니다. 할인은 없습니다.

> ⚠️ **함정 (중요) — `batch` 는 실패 하나가 전체를 죽인다**
>
> `batch` 의 기본 동작은 **fail-fast** 입니다. 100개를 보냈는데 37번째가 실패하면 **전체가 throw 하고, 성공한 99개의 결과도 함께 사라집니다.** 돈은 100개어치 다 냈는데 손에 남는 건 없습니다.
>
> `returnExceptions: true` 를 주면 실패한 자리에 `Error` 객체가 들어옵니다.
>
> ```ts
> const results = await model.batch(inputs, {}, { returnExceptions: true });
> //     ^? (AIMessage | Error)[]
>
> for (const [i, r] of results.entries()) {
>   if (r instanceof Error) {
>     console.error(`[${i}] 실패: ${r.message}`);   // 이 항목만 재시도하면 된다
>   } else {
>     console.log(`[${i}] ${r.text}`);
>   }
> }
> ```
>
> 반환 타입이 `AIMessage[]` 에서 `(AIMessage | Error)[]` 로 **바뀐다**는 점에 주목하세요. 타입 시스템이 "이제 에러를 처리해야 한다"고 강제합니다. 이건 좋은 설계입니다 — `instanceof Error` 체크를 빼먹으면 컴파일이 안 됩니다.

> ⚠️ **함정 — `maxConcurrency` 를 안 주면 전부 동시에 날아간다**
>
> `maxConcurrency` 의 기본값은 **`Infinity`** 입니다. `model.batch(inputs)` 에 1000개를 넣으면 **1000개가 동시에 출발합니다.** 결과는 즉각적인 `429 Rate limit exceeded` 폭격이고, `maxRetries` 기본값 6이 그 1000개를 각각 최대 6번씩 재시도하면서 상황을 더 악화시킵니다.
>
> 더 나쁜 건 **`maxConcurrency` 를 어디에 주느냐** 입니다. `batchOptions`(3번째 인자)에도 `maxConcurrency` 가 있지만 **deprecated** 되었습니다. `@langchain/core` 1.2.3 의 타입 정의가 직접 이렇게 말합니다.
>
> ```ts
> type RunnableBatchOptions = {
>   /** @deprecated Pass in via the standard runnable config object instead */
>   maxConcurrency?: number;
>   returnExceptions?: boolean;
> };
> ```
>
> 즉 **2번째 인자(config)** 에 줘야 합니다. 헷갈리기 딱 좋은 자리입니다.
>
> ```ts
> // ❌ deprecated 자리 — 3번째 인자
> await model.batch(inputs, {}, { maxConcurrency: 5, returnExceptions: true });
>
> // ✅ 올바른 자리 — 2번째 인자(config)에 maxConcurrency, 3번째에 returnExceptions
> await model.batch(inputs, { maxConcurrency: 5 }, { returnExceptions: true });
> ```
>
> 두 인자의 역할이 다릅니다. **2번째 = config**(모델 호출 하나하나에 적용될 설정), **3번째 = batchOptions**(배치 자체의 동작). `maxConcurrency` 는 config 쪽, `returnExceptions` 는 batchOptions 쪽입니다.

### 세 방식 비교

| | `invoke` | `stream` | `batch` |
|---|---|---|---|
| 입력 | 1개 | 1개 | N개 |
| 반환 | `AIMessage` | `AIMessageChunk` 의 async iterable | `AIMessage[]` |
| 첫 응답까지 | 전체 생성 후 | **즉시** | 전체 생성 후 |
| 순서 보장 | 해당 없음 | 해당 없음 | ✅ 입력 순서 = 출력 순서 |
| 부분 실패 | 해당 없음 | 해당 없음 | ⚠️ 기본 fail-fast |
| 동시성 제어 | `maxConcurrency`(생성자) | 해당 없음 | `maxConcurrency`(config) |
| 주 용도 | 서버 내부 로직, 짧은 응답 | **사용자에게 보이는 모든 것** | 오프라인 대량 처리, 평가 |

> 💡 **실무 팁 — 레이트리밋에 대응하는 실전 순서**
>
> `@langchain/core` v1 에는 **`InMemoryRateLimiter` 가 없습니다.** v0.3 시절 자료나 블로그 글에 나오는 `import { InMemoryRateLimiter } from "@langchain/core/rate_limiters"` 를 복사해 오면 모듈을 못 찾습니다. (실제로 1.2.3 의 export 목록에 `rate_limiters` 엔트리가 없습니다.) v1 에서 쓸 수 있는 수단은 이렇습니다.
>
> 1. **`maxConcurrency` 로 압력 자체를 낮춘다** — 가장 중요합니다. 429 를 맞고 재시도하는 것보다 애초에 안 맞는 게 쌉니다. 생성자에 주면 그 모델의 모든 호출에 적용됩니다.
>    ```ts
>    const model = new ChatAnthropic({ model: "claude-sonnet-4-6", maxConcurrency: 5 });
>    ```
> 2. **`maxRetries` 를 상황에 맞게** — 기본 6 이면 대부분 충분합니다. 불안정한 네트워크면 10~15 까지 올릴 만합니다. **다만 대화형 요청에는 낮추세요** — 지수 백오프로 6번 재시도하면 사용자는 몇 분을 기다립니다. 사용자 대면은 `maxRetries: 2` + 빠른 실패가 낫습니다.
> 3. **`onFailedAttempt` 으로 관측한다** — 재시도가 조용히 일어나면 레이트리밋에 걸리고 있다는 사실조차 모릅니다.
>    ```ts
>    const model = new ChatAnthropic({
>      model: "claude-sonnet-4-6",
>      maxConcurrency: 5,
>      onFailedAttempt: (error) => {
>        console.warn(`[재시도] ${error.message}`);
>        throw error;   // ← 다시 던져야 재시도가 계속된다. 안 던지면 재시도가 멈춘다.
>      },
>    });
>    ```
>    `onFailedAttempt` 안에서 **에러를 다시 던지지 않으면 재시도 로직이 중단됩니다.** 로깅만 하고 넘어가려면 반드시 `throw error` 를 넣으세요.
> 4. **작업을 청크로 쪼갠다** — 10만 건을 `batch` 하나에 넣지 마세요. 500건씩 끊어 진행 상황을 저장하며 돌리면, 중간에 죽어도 처음부터 다시 하지 않습니다.

---

## 2-5. 토큰과 비용 — `usage_metadata` 읽기

에이전트를 프로덕션에 올리면 비용이 곧 설계 제약이 됩니다. 그 출발점이 `usage_metadata` 입니다.

```ts
const res = await model.invoke("광합성을 한 문장으로 설명해줘.");
console.log(res.usage_metadata);
```

**출력 예시** (모델 응답이므로 토큰 수는 매번 다릅니다. 다만 **필드 이름과 구조는 결정적입니다**.)

```
{
  input_tokens: 18,
  output_tokens: 62,
  total_tokens: 80,
  input_token_details: { cache_read: 0, cache_creation: 0 },
  output_token_details: {}
}
```

`@langchain/core` 1.2.3 의 실제 타입 정의는 이렇습니다.

```ts
type UsageMetadata = {
  input_tokens: number;          // 입력 토큰. 모든 입력 종류의 합
  output_tokens: number;         // 출력 토큰. 모든 출력 종류의 합
  total_tokens: number;          // input_tokens + output_tokens
  input_token_details?: InputTokenDetails;
  output_token_details?: OutputTokenDetails;
};

type ModalitiesTokenDetails = {
  text?: number; image?: number; audio?: number; video?: number; document?: number;
};

type InputTokenDetails = ModalitiesTokenDetails & {
  cache_read?: number;       // 캐시 히트로 캐시에서 읽어온 토큰
  cache_creation?: number;   // 캐시 미스로 캐시를 새로 만든 토큰
};

type OutputTokenDetails = ModalitiesTokenDetails & {
  reasoning?: number;        // 추론(thinking) 토큰. 출력에는 안 보이지만 요금은 낸다
};
```

여기서 반드시 이해해야 할 두 가지가 있습니다. 타입 정의의 주석이 직접 경고합니다.

> "Does **not** need to sum to full input token count. Does **not** need to have all keys."

즉 **`input_token_details` 의 값을 다 더해도 `input_tokens` 가 안 됩니다.** 그리고 **키가 아예 없을 수도 있습니다.** `res.usage_metadata.input_token_details.cache_read` 를 무심코 쓰면 `undefined` 에서 터집니다. 전부 optional 로 다루세요.

### 세 종류의 입력 토큰

`input_tokens` 는 **모든 입력의 합**입니다. 그 안에서 성격이 갈립니다.

| 종류 | 필드 | 성격 | 상대 단가 |
|---|---|---|---|
| 일반 입력 | (`input_tokens` 에서 나머지) | 매번 새로 처리 | 기준 |
| **캐시 읽기** | `input_token_details.cache_read` | 캐시 히트 — 이미 처리된 걸 재사용 | **훨씬 쌈** |
| **캐시 생성** | `input_token_details.cache_creation` | 캐시 미스 — 캐시를 새로 만듦 | **기준보다 비쌈** |

캐시 생성이 **기준 단가보다 비싸다**는 게 핵심입니다. 캐시는 공짜가 아닙니다. 한 번 만들어 여러 번 읽어야 이득입니다. 한 번 만들고 한 번 읽으면 **손해**입니다.

### 비용 계산

```ts
type Pricing = {
  inputPerMTok: number;          // 100만 토큰당 USD
  outputPerMTok: number;
  cacheReadPerMTok: number;
  cacheWritePerMTok: number;
};

function estimateCost(usage: UsageMetadata, p: Pricing): number {
  const cacheRead = usage.input_token_details?.cache_read ?? 0;
  const cacheWrite = usage.input_token_details?.cache_creation ?? 0;

  // 중요: input_tokens 는 캐시 토큰을 "포함한" 총합이다.
  // 캐시분을 빼야 일반 입력 토큰이 남는다. 안 빼면 이중 계산이 된다.
  const plainInput = Math.max(0, usage.input_tokens - cacheRead - cacheWrite);

  return (
    (plainInput   / 1_000_000) * p.inputPerMTok +
    (cacheRead    / 1_000_000) * p.cacheReadPerMTok +
    (cacheWrite   / 1_000_000) * p.cacheWritePerMTok +
    (usage.output_tokens / 1_000_000) * p.outputPerMTok
  );
}
```

> ⚠️ **함정 — 캐시 토큰을 이중으로 세지 마라**
>
> `input_tokens` 는 **캐시 읽기·생성 토큰을 이미 포함한 총합**입니다(타입 주석: "Sum of all input token types"). 그런데 비용 계산 코드를 짤 때 `input_tokens * 단가 + cache_read * 캐시단가` 로 쓰기 쉽습니다. 그러면 캐시 토큰의 요금을 **두 번** 내는 것으로 계산됩니다.
>
> 캐시를 잘 쓰는 에이전트일수록(= `cache_read` 비중이 클수록) 이 오차가 커집니다. 캐시를 도입해서 실제 비용은 절반으로 줄었는데 대시보드 숫자는 오히려 올라가는, 아주 헷갈리는 상황이 만들어집니다. **반드시 빼고 나서 곱하세요.**

> 💡 **실무 팁 — 단가를 코드에 박지 마세요**
>
> 위 `estimateCost` 가 `Pricing` 을 **인자로 받는** 이유가 있습니다. 모델 단가는 바뀌고, 새 모델이 나오고, 여러분은 여러 모델을 동시에 씁니다. 단가는 설정 파일이나 DB 에 두고, **반드시 각 provider 의 공식 가격 페이지에서 확인해 갱신하세요.** 이 교재를 포함해 어떤 문서에 적힌 단가도 오늘 기준으로 맞다는 보장이 없습니다.
>
> ```ts
> // 실제 단가는 공식 가격 페이지에서 확인해 채우세요.
> const PRICING: Record<string, Pricing> = {
>   "anthropic:claude-sonnet-4-6": { inputPerMTok: 0, outputPerMTok: 0, cacheReadPerMTok: 0, cacheWritePerMTok: 0 },
>   "openai:gpt-5.5":              { inputPerMTok: 0, outputPerMTok: 0, cacheReadPerMTok: 0, cacheWritePerMTok: 0 },
> };
> ```
>
> 그리고 비용은 **호출당이 아니라 요청(대화)당으로 집계**하세요. 에이전트 한 번 돌리면 모델이 5~20번 호출됩니다(도구 루프 때문에 — Step 07). 사용자에게 의미 있는 단위는 "이 대화가 얼마였나"이지 "이 LLM 호출이 얼마였나"가 아닙니다. `usage_metadata` 를 요청 단위로 누적하는 헬퍼를 초반에 만들어 두면 두고두고 씁니다.

> ⚠️ **함정 — 스트리밍에서 `usage_metadata` 가 안 보인다**
>
> `stream` 으로 받으면 각 청크의 `usage_metadata` 는 대부분 `undefined` 입니다. 토큰 수는 **마지막 청크**에 실려 옵니다. 청크를 하나만 보고 "usage 가 없네"라고 결론 내리면 안 됩니다.
>
> 청크를 다 합치면(`concat`) `usage_metadata` 가 병합되어 온전한 값이 나옵니다. `@langchain/core` 에 `mergeUsageMetadata` 가 있는 이유가 이것입니다.
>
> ```ts
> let final: AIMessageChunk | undefined;
> for await (const chunk of await model.stream("안녕")) {
>   process.stdout.write(chunk.text);
>   final = final === undefined ? chunk : final.concat(chunk);
> }
> console.log(final?.usage_metadata);   // ← 여기서 온전한 토큰 수
> ```
>
> `ChatAnthropic` 은 `streamUsage` 기본값이 `true` 라서 그냥 됩니다. 하지만 `streamUsage: false` 로 꺼두면 **토큰 수를 아예 못 받습니다** — 비용 집계가 조용히 0 이 됩니다.

---

## 2-6. reasoning 모델 다루기

일부 모델은 답을 내기 전에 **속으로 먼저 생각합니다.** 이 생각 과정이 별도의 토큰으로 생성되고, 여러분은 **그 토큰에도 요금을 냅니다.** 최종 답변에는 안 보이는데 말이죠.

### 켜는 법 — provider 마다 다르다

이건 표준 파라미터가 **아닙니다.** provider 고유 파라미터이므로 `new Chat*()` 로 만들 때 타입 체크를 받는 편이 안전합니다.

```ts
// Anthropic — thinking
import { ChatAnthropic } from "@langchain/anthropic";

const model = new ChatAnthropic({
  model: "claude-sonnet-4-6",
  maxTokens: 4000,
  thinking: { type: "enabled", budget_tokens: 2000 },
});
```

`thinking` 의 타입은 `AnthropicThinkingConfigParam` 이고, 세 가지 형태가 있습니다.

| 형태 | 의미 |
|---|---|
| `{ type: "enabled", budget_tokens: N }` | 추론 켬. 최대 N 토큰까지 생각 |
| `{ type: "disabled" }` | 추론 끔. **`ChatAnthropic` 의 기본값** |
| `{ type: "adaptive" }` | 모델이 문제 난이도를 보고 알아서 조절 |

```ts
// OpenAI — reasoning
import { ChatOpenAI } from "@langchain/openai";

const model = new ChatOpenAI({
  model: "gpt-5.5",
  reasoning: { effort: "low" },     // "low" | "medium" | "high"
});
```

`@langchain/openai` 1.5.5 의 `reasoningEffort` 파라미터는 **deprecated** 입니다. 타입 정의가 이렇게 말합니다.

> "@deprecated This is a convenience option that will be merged into the `reasoning` object. Use `reasoning.effort` instead."

`reasoning: { effort: "low" }` 쪽을 쓰세요.

### 추론 내용 읽기

provider 가 달라도 `contentBlocks` 로 **표준화되어** 나옵니다. Anthropic 은 `thinking` 블록으로, OpenAI 는 `reasoning` 블록으로 주지만, `contentBlocks` 를 거치면 둘 다 `type: "reasoning"` 입니다.

```ts
const res = await model.invoke("17 * 24 를 암산하듯 계산해줘.");

const reasoning = res.contentBlocks.filter((b) => b.type === "reasoning");
console.log("--- 추론 과정 ---");
console.log(reasoning.map((b) => b.reasoning).join("\n"));

console.log("--- 최종 답변 ---");
console.log(res.text);         // text 에는 추론이 섞이지 않는다
```

`res.text` 에는 **추론 내용이 포함되지 않습니다.** 최종 답변만 들어 있습니다. 추론을 보려면 반드시 `contentBlocks` 를 뒤져야 합니다.

### 추론 토큰 요금

```ts
console.log(res.usage_metadata?.output_token_details?.reasoning);
```

**출력 예시** (매번 다릅니다)

```
256
```

`output_token_details.reasoning` 의 타입 주석이 이렇게 말합니다.

> "Tokens generated by the model in a chain of thought process ... that are **not returned as part of model output**."

**출력으로 돌려주지 않는데 output_tokens 에는 포함**됩니다. `output_tokens: 304, output_token_details: { reasoning: 256 }` 이면, 여러분이 실제로 화면에 쓸 수 있는 건 48 토큰뿐이고 나머지 256 토큰은 모델의 혼잣말입니다. 그런데 요금은 304 토큰어치를 냅니다.

> ⚠️ **함정 — reasoning 모델은 여러분이 준 파라미터를 조용히 무시한다**
>
> 이게 2-3 에서 예고한 "무시되는 파라미터" 함정의 가장 아픈 사례입니다.
>
> - **`temperature` 가 무시됩니다.** reasoning 모델은 대개 샘플링 파라미터를 지원하지 않습니다. `@langchain/openai` 의 `reasoning` JSDoc 이 직접 이렇게 말합니다: **"These options will be ignored when not using a reasoning model."** 반대 방향도 마찬가지로, reasoning 모델에서는 `temperature` 쪽이 무시됩니다. `temperature: 0` 을 주고 "왜 여전히 답이 흔들리지?" 하게 됩니다.
> - **Anthropic 은 `thinking` 이 켜져 있으면 `temperature` 를 함께 쓸 수 없습니다.**
> - **`maxTokens` 가 추론 토큰을 포함합니다.** `maxTokens: 1000` + `budget_tokens: 800` 을 주면 답변에 쓸 공간이 200 토큰밖에 안 남습니다. **`maxTokens` 는 반드시 `budget_tokens` 보다 넉넉히 크게** 잡으세요. 안 그러면 생각만 하다 답을 못 내고 `stop_reason: "max_tokens"` 로 잘립니다 — 생각 값은 다 치르고 답은 못 받는 최악의 조합입니다.
> - **구조화된 출력과 충돌합니다.** `@langchain/anthropic` 1.5.1 은 `thinking` 이 켜진 채로 `withStructuredOutput()` 을 쓰면 이런 경고를 콘솔에 찍습니다.
>   > "Anthropic structured output relies on forced tool calling, which is not supported when `thinking` is enabled. This method will raise OutputParserException if tool calls are not generated. Consider disabling `thinking` or adjust your prompt to ensure the tool is called."
>
>   **경고일 뿐 에러가 아닙니다.** 잘 돌아가다가 어느 날 갑자기 `OutputParserException` 이 터집니다. Step 05 에서 다시 만납니다.

> 💡 **실무 팁 — reasoning 을 언제 켜나**
>
> 기본은 **끄는 것**입니다(`ChatAnthropic` 의 기본값이 `{ type: "disabled" }` 인 데는 이유가 있습니다). 추론은 지연 시간과 비용을 동시에 올립니다.
>
> **켤 만한 것**: 수학·논리 퍼즐, 복잡한 코드 디버깅, 여러 제약을 동시에 만족시켜야 하는 계획 수립, 다단계 추론이 필요한 분석.
> **켜면 손해인 것**: 요약, 번역, 형식 변환, 분류, 추출, 단순 QA. 이런 작업은 생각할 게 없습니다. 지연 시간만 늘고 답은 그대로입니다.
>
> `{ type: "adaptive" }` 는 이 판단을 모델에게 위임하는 선택지입니다. 입력 난이도가 들쭉날쭉한 실서비스에서 특히 쓸 만합니다.

---

## 2-7. 모델 선택 가이드 — 언제 큰 모델, 언제 작은 모델

"제일 좋은 모델 쓰면 되잖아"는 프로토타입에서만 맞는 말입니다. 에이전트는 한 번 돌 때 모델을 5~20번 호출합니다. 큰 모델을 전 구간에 쓰면 **작은 모델 대비 비용이 자릿수 단위로** 벌어집니다.

| 축 | 작은/빠른 모델 | 큰/강한 모델 |
|---|---|---|
| 비용 | 낮음 | 높음 (수 배~수십 배) |
| 지연 | 낮음 | 높음 |
| 지시 따르기 | 짧고 명확한 지시엔 충분 | 복잡·다중 제약에 강함 |
| 도구 선택 | 도구 3~5개까진 쓸 만함 | 도구 많고 애매할 때 안정적 |
| 다단계 계획 | 약함 | 강함 |
| 긴 컨텍스트 추론 | 앞부분을 잘 놓침 | 상대적으로 나음 |

### 작업별 권장

| 작업 | 권장 | 이유 |
|---|---|---|
| 분류, 라우팅, 의도 파악 | **작은 모델** | 출력이 짧고 정답이 명확. 큰 모델이 낭비 |
| 추출, 형식 변환 | **작은 모델** | 스키마(Step 05)가 품질을 보장해 줌 |
| 요약 | **작은 모델** | 대개 충분. 원문이 길고 미묘하면 승급 |
| 대화 응답 | 중간 | 지연이 UX 를 지배 |
| **도구 호출 루프** | **큰 모델** | 도구를 잘못 고르면 루프 전체가 낭비 |
| **계획 수립, 멀티 에이전트 오케스트레이션** | **큰 모델** | 여기서 틀리면 하위 작업 전부가 헛수고 |
| 코드 생성·리뷰 | **큰 모델** | 정확도 차이가 가장 크게 벌어지는 영역 |
| 최종 사용자 대면 답변 | **큰 모델** | 품질이 곧 제품 |

> 💡 **실무 팁 — 두 개를 섞어 쓰는 게 정답이다**
>
> 실무의 정석은 "하나 고르기"가 아니라 **역할별로 나누기**입니다. 2-1 에서 말한 추상화의 값어치가 여기서 현금화됩니다.
>
> ```ts
> // 라우터: 값싸고 빠른 모델
> const router = await initChatModel("anthropic:claude-haiku-4-5", { temperature: 0 });
> // 작업자: 강한 모델
> const worker = await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0 });
>
> const intent = (await router.invoke(`다음을 [단순질문|복잡분석] 중 하나로만 분류: ${q}`)).text;
> const model = intent.includes("복잡분석") ? worker : router;
> const answer = await model.invoke(q);
> ```
>
> 트래픽의 80% 가 단순 질문이면 비용이 극적으로 줄어듭니다. 라우팅 자체의 비용은 무시할 만하고요.
>
> **순서도 중요합니다.** 큰 모델로 먼저 만들어 "되는 것"을 확인한 뒤, 구간별로 작은 모델로 내려보며 품질이 유지되는 지점을 찾으세요. 처음부터 작은 모델로 시작하면 "모델이 약해서 안 되는 건지 내 프롬프트가 틀린 건지" 구분이 안 됩니다. **일단 되게 만들고, 그 다음에 싸게 만드세요.** 이 판단을 감이 아니라 숫자로 하려면 평가가 필요합니다 — Step 19 에서 다룹니다.

---

## 2-8. provider 교체 실습 — 같은 코드로 anthropic ↔ openai

지금까지 배운 걸 합칩니다. **호출 코드를 한 글자도 바꾸지 않고** provider 를 바꿔 봅시다.

```ts
import { initChatModel } from "langchain";

// 이 함수는 provider 를 전혀 모른다. 모델 인터페이스만 안다.
async function askAll(model: Awaited<ReturnType<typeof initChatModel>>, questions: string[]) {
  const results = await model.batch(
    questions,
    { maxConcurrency: 2 },
    { returnExceptions: true },
  );

  let totalIn = 0, totalOut = 0;
  for (const [i, r] of results.entries()) {
    if (r instanceof Error) {
      console.log(`[${i}] 실패: ${r.message}`);
      continue;
    }
    totalIn += r.usage_metadata?.input_tokens ?? 0;
    totalOut += r.usage_metadata?.output_tokens ?? 0;
    console.log(`[${i}] ${r.text.slice(0, 50)}...`);
  }
  console.log(`합계: 입력 ${totalIn} / 출력 ${totalOut} 토큰`);
}

const questions = ["광합성을 한 문장으로", "중력을 한 문장으로"];

// provider 를 문자열로만 바꾼다
await askAll(await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0, maxTokens: 200 }), questions);
await askAll(await initChatModel("openai:gpt-5.5",              { temperature: 0, maxTokens: 200 }), questions);
```

**출력 예시** (모델 응답이므로 매번 다릅니다)

```
[0] 광합성은 식물이 빛 에너지로 이산화탄소와 물을 포도당...
[1] 중력은 질량을 가진 모든 물체가 서로를 끌어당기는 힘...
합계: 입력 24 / 출력 118 토큰
```

`askAll` 은 provider 를 모릅니다. `batch`, `usage_metadata.input_tokens`, `text` — 전부 표준 인터페이스입니다. 이것이 2-1 에서 말한 추상화의 실물입니다.

환경변수로 빼면 배포 설정만으로 모델을 바꿀 수 있습니다.

```ts
const model = await initChatModel(process.env.MODEL ?? "anthropic:claude-sonnet-4-6", {
  temperature: 0,
  maxTokens: 200,
});
```

```bash
MODEL="anthropic:claude-sonnet-4-6" npx tsx practice.ts   # ANTHROPIC_API_KEY 필요
MODEL="openai:gpt-5.5"              npx tsx practice.ts   # OPENAI_API_KEY 필요
```

**API 키는 자동으로 바뀌지 않습니다.** provider 를 바꾸면 그 provider 의 키가 환경에 있어야 하고, 해당 통합 패키지(`@langchain/openai`)도 설치되어 있어야 합니다.

> ⚠️ **함정 — 교체되는 건 인터페이스지 동작이 아니다**
>
> 코드가 그대로 돈다고 **결과가 같지는 않습니다.** provider 를 바꾸면 이런 것들이 함께 바뀝니다.
>
> - **프롬프트 민감도**: 같은 프롬프트가 다른 모델에서 다르게 읽힙니다. Claude 에 맞춰 튜닝한 프롬프트는 GPT 에서 최적이 아닙니다.
> - **파라미터 범위**: `temperature` 가 Anthropic 은 0~1, OpenAI 는 0~2. `temperature: 1` 의 의미가 다릅니다.
> - **무시되는 파라미터**: `topK` 를 넣어 뒀다면 OpenAI 로 가는 순간 사라집니다.
> - **`response_metadata` 의 필드명**: `stop_reason` 이 `finish_reason` 으로 바뀝니다. 이 필드를 읽는 코드가 있다면 조용히 `undefined` 가 됩니다.
> - **도구 호출 스타일, 구조화 출력의 zod 처리**: Step 05·06 에서 다룹니다. `.optional()` vs `.nullable()` 처리가 provider 마다 다릅니다.
>
> **provider 교체는 "코드 수정 비용"을 0 으로 만들 뿐, "검증 비용"은 그대로입니다.** 바꿨으면 반드시 평가를 다시 돌리세요(Step 19).

---

## 2-9. 종합 — 캐싱과 계측

마지막으로 실무에서 바로 쓰는 두 가지를 붙입니다.

### 캐싱 — 유일하게 결정성을 보장하는 방법

LangChain 에는 **정확히 같은 프롬프트**에 대해 모델 호출 자체를 건너뛰는 캐시가 있습니다.

```ts
import { InMemoryCache } from "@langchain/core/caches";

const cache = new InMemoryCache();
const model = new ChatAnthropic({ model: "claude-sonnet-4-6", cache });
// 또는 그냥: new ChatAnthropic({ model: "claude-sonnet-4-6", cache: true });

console.time("1회차");
await model.invoke("광합성을 한 문장으로 설명해줘.");
console.timeEnd("1회차");     // 1회차: 1823ms  ← 실제 호출

console.time("2회차");
await model.invoke("광합성을 한 문장으로 설명해줘.");
console.timeEnd("2회차");     // 2회차: 0.3ms   ← 캐시 히트. 네트워크 안 탐
```

2-3 에서 "`temperature: 0` 은 결정성을 보장하지 않는다"고 했죠. **캐시는 보장합니다.** 같은 프롬프트면 저장된 그 응답이 그대로 나오니까요. 개발·테스트에서 특히 값집니다.

> ⚠️ **함정 — 두 가지 "캐시"는 완전히 다른 것이다**
>
> 이 스텝에 캐시가 두 번 나왔습니다. **전혀 다른 물건입니다.**
>
> | | LangChain 캐시 (`cache: true`) | provider 프롬프트 캐시 (`cache_control`) |
> |---|---|---|
> | 어디서 | **여러분 프로세스 안** | provider 서버 |
> | 무엇을 | 프롬프트 → 최종 응답 통째로 | 프롬프트 **접두사**의 처리 상태 |
> | 히트 조건 | 프롬프트가 **완전히 동일** | **앞부분**이 동일 |
> | API 호출 | **안 함** | 함 |
> | 비용 | **0원** | 할인된 입력 단가 |
> | `usage_metadata` | 원본 호출의 값이 재생됨 | `cache_read` 에 반영 |
>
> 특히 마지막 줄을 조심하세요. LangChain 캐시가 히트하면 `usage_metadata` 는 **처음 호출했을 때의 토큰 수가 그대로 재생**됩니다. 실제로는 API 를 안 불렀으니 **비용이 0원인데, 비용 집계 코드는 요금이 발생한 것처럼 셉니다.** 캐시 적중률이 높은 개발 환경에서 비용 대시보드가 실제보다 훨씬 부풀려 보이는 원인이 이것입니다.
>
> `InMemoryCache` 는 이름 그대로 **메모리에만** 있습니다. 프로세스가 죽으면 다 날아가고, 서버 여러 대에 걸쳐 공유되지도 않습니다. (Step 10 의 `MemorySaver` 와 정확히 같은 성질입니다.) 프로덕션에서 공유 캐시가 필요하면 Redis 같은 백엔드를 쓰는 `BaseCache` 구현체가 필요합니다.

provider 프롬프트 캐시는 긴 시스템 프롬프트를 반복해서 보낼 때 씁니다. Anthropic 은 콘텐츠 블록에 `cache_control` 을 붙입니다.

```ts
const messages = [
  {
    role: "system",
    content: [
      {
        type: "text",
        text: LONG_TEXT,                        // 길고, 매 요청 동일한 내용
        cache_control: { type: "ephemeral" },   // ← 여기까지 캐시
      },
    ],
  },
  { role: "user", content: "이 문서에서 X 를 찾아줘." },
];
const res = await model.invoke(messages);
console.log(res.usage_metadata?.input_token_details);
// 1회차: { cache_creation: 2431, cache_read: 0 }     ← 캐시 만듦 (비쌈)
// 2회차: { cache_creation: 0,    cache_read: 2431 }  ← 캐시 읽음 (쌈)
```

**출력 예시** (토큰 수는 입력에 따라 다릅니다. 필드 이름은 결정적입니다.)

> 💡 **실무 팁 — 프롬프트 캐시는 "안 변하는 것을 앞에" 두어야 작동한다**
>
> 프롬프트 캐시는 **접두사(prefix)** 매칭입니다. 앞에서부터 한 글자라도 다르면 그 지점 이후는 전부 캐시 미스입니다. 그래서 프롬프트를 이렇게 배치해야 합니다.
>
> ```
> [불변]  시스템 프롬프트, 도구 정의, 참고 문서   ← cache_control 을 여기 끝에
> [가변]  대화 이력
> [가변]  이번 사용자 입력
> ```
>
> 흔한 실수는 **맨 앞에 타임스탬프나 요청 ID 를 넣는 것**입니다. `"현재 시각: 2026-07-17 14:23:11"` 한 줄이 프롬프트 맨 앞에 있으면 **캐시가 영원히 100% 미스**합니다. 게다가 캐시 생성 단가는 일반 입력보다 비싸므로, 캐시를 켜기 전보다 **오히려 비싸집니다.** 이 함정은 에러도 안 내고 로그도 안 남깁니다 — `cache_read` 가 계속 0 인 것으로만 알 수 있습니다. 캐시를 켰다면 **`cache_read` 를 반드시 모니터링하세요.**

### 계측 헬퍼

이 스텝의 결론을 하나로 묶은 헬퍼입니다. 앞으로의 스텝에서 계속 씁니다.

```ts
function inspect(res: AIMessage, label = "") {
  const meta = res.response_metadata as Record<string, unknown>;
  const u = res.usage_metadata;
  const truncated = meta.stop_reason === "max_tokens" || meta.finish_reason === "length";

  console.log(`--- ${label} ---`);
  console.log(`텍스트   : ${res.text.slice(0, 60)}${res.text.length > 60 ? "..." : ""}`);
  console.log(`종료 이유: ${meta.stop_reason ?? meta.finish_reason ?? "(알 수 없음)"}${truncated ? "  ⚠️ 잘림!" : ""}`);
  console.log(`토큰     : 입력 ${u?.input_tokens ?? 0} / 출력 ${u?.output_tokens ?? 0} / 합계 ${u?.total_tokens ?? 0}`);
  console.log(`캐시     : 읽기 ${u?.input_token_details?.cache_read ?? 0} / 생성 ${u?.input_token_details?.cache_creation ?? 0}`);
  console.log(`추론     : ${u?.output_token_details?.reasoning ?? 0} 토큰`);
}
```

---

## 정리

**모델 생성 세 방식**

| 방식 | `await` | provider 교체 | 타입 체크 | 쓰는 자리 |
|---|---|---|---|---|
| `initChatModel("anthropic:...")` | 필요 | 문자열 한 줄 | ❌ 없음 | 앱 진입점 |
| `createAgent({ model: "anthropic:..." })` | 해당 없음 | 문자열 한 줄 | 해당 없음 | 에이전트 정의 |
| `new ChatAnthropic({...})` | 불필요 | 코드 수정 | ✅ 완전 | provider 고유 기능 |

**파라미터 3분류**

| 분류 | 파라미터 | 무엇을 바꾸나 |
|---|---|---|
| 샘플링 | `temperature`, `topP`, `topK` | 확률 분포에서 **어떻게 뽑을지** |
| 종료 | `maxTokens`, `stopSequences` | **언제 멈출지** |
| 전송 | `timeout`, `maxRetries`, `maxConcurrency` | **HTTP 클라이언트 동작** (모델과 무관) |

**호출 세 방식**

| 방식 | 순서 보장 | 부분 실패 | 주 용도 |
|---|---|---|---|
| `invoke` | 해당 없음 | 해당 없음 | 서버 내부 로직 |
| `stream` | 해당 없음 | 해당 없음 | 사용자 대면 |
| `batch` | ✅ | ⚠️ 기본 fail-fast | 대량 처리, 평가 |

**토큰 필드 (구조는 결정적)**

```
usage_metadata.input_tokens                          ← 캐시 토큰 포함한 총합
usage_metadata.output_tokens                         ← 추론 토큰 포함한 총합
usage_metadata.total_tokens
usage_metadata.input_token_details.cache_read        ← 쌈
usage_metadata.input_token_details.cache_creation    ← 비쌈
usage_metadata.output_token_details.reasoning        ← 안 보이는데 요금은 냄
```

**핵심 함정 3가지**

1. **`temperature: 0` 은 결정성을 보장하지 않는다.** 부동소수점 비결정성 때문에 1·2위 토큰이 붙어 있으면 순위가 뒤집힌다. 정확한 문자열 일치로 테스트를 짜지 마라. 결정성이 진짜 필요하면 캐시를 써라.
2. **`maxTokens` 를 넘기면 에러 없이 조용히 잘린다.** 반드시 `response_metadata.stop_reason === "max_tokens"`(Anthropic) 또는 `finish_reason === "length"`(OpenAI)를 확인하라. 잘린 응답도 요금은 그대로 낸다.
3. **모델마다 지원 파라미터가 다르고, 안 맞는 건 조용히 무시된다.** `timeout` 은 `ChatOpenAI` 엔 있고 `ChatAnthropic` 엔 없다. reasoning 모델은 `temperature` 를 무시한다. 그리고 `initChatModel` 은 **타입 체크를 안 해서 오타조차 안 잡는다.**

**보너스 함정**: `batch` 는 순서를 보장하지만 실패 하나가 전체를 죽인다 → `returnExceptions: true`. 그리고 `maxConcurrency` 는 **2번째 인자(config)** 에 줘야 한다 — 3번째 인자의 것은 deprecated 다.

**v1 특이사항**: `@langchain/core` v1 에는 `InMemoryRateLimiter` 가 **없다**. v0 자료를 복사하지 마라. 동시성 제어는 `maxConcurrency` 로 한다.

---

## 연습문제

1. `initChatModel` 로 `anthropic:claude-sonnet-4-6` 모델을 만들되 **`await` 를 빼고** 호출해 보세요. 어떤 에러가 나나요? 주석으로 적고, 고치세요.
2. `maxTokens: 20` 을 주고 "광합성을 자세히 설명해줘"라고 물어보세요. `res.text` 와 `res.response_metadata.stop_reason` 을 함께 출력해, **응답이 잘렸는지 판정하는 함수** `isTruncated(res): boolean` 를 완성하세요. Anthropic 과 OpenAI 양쪽에서 동작해야 합니다.
3. 같은 프롬프트를 `temperature: 0` 으로 **5번** 호출해 응답을 모으세요. 서로 다른 응답이 몇 종류 나왔나요? `new Set(texts).size` 로 세어 보세요. (2-3 의 함정을 직접 확인하는 문제입니다. 1이 나올 수도, 아닐 수도 있습니다 — 둘 다 정상입니다.)
4. 질문 4개를 `batch` 로 처리하되, **하나는 반드시 실패하도록** 만드세요(힌트: 존재하지 않는 모델 이름을 쓴 모델 인스턴스를 섞거나, `maxTokens: 0` 처럼 잘못된 값을 주세요). `returnExceptions` 없이 먼저 돌려 전체가 죽는 걸 확인한 뒤, `returnExceptions: true` 와 `maxConcurrency: 2` 를 **올바른 인자 자리**에 넣어 성공한 것만 살려내세요.
5. `usage_metadata` 를 받아 비용을 계산하는 `estimateCost(usage, pricing)` 를 구현하세요. **캐시 토큰 이중 계산을 피해야 합니다** (`input_tokens` 는 캐시분을 포함한 총합입니다). `input_token_details` 가 아예 없는 경우에도 터지지 않아야 합니다.
6. `stream` 으로 응답을 받으면서, 각 청크의 `usage_metadata` 를 출력해 보세요. 대부분 `undefined` 인 것을 확인한 뒤, 청크를 `concat` 으로 합쳐 **최종 토큰 수**를 얻으세요.
7. `ChatAnthropic` 에 `thinking: { type: "enabled", budget_tokens: 2000 }` 과 `maxTokens: 4000` 을 주고 "17 × 24 를 계산해줘"라고 물어보세요. (a) `contentBlocks` 에서 `type: "reasoning"` 블록을 뽑아 출력하고, (b) `output_token_details.reasoning` 토큰 수를 출력하고, (c) `res.text` 에 추론 내용이 **포함되지 않는다**는 걸 확인하세요. 그 다음 `maxTokens` 를 `2100` 으로 낮춰 다시 돌려 `stop_reason` 이 어떻게 되는지 보세요.
8. `askAll(model, questions)` 함수를 만들되 **provider 를 전혀 모르게** 작성하고, `anthropic:claude-sonnet-4-6` 과 `openai:gpt-5.5` 양쪽으로 각각 호출해 토큰 합계를 비교하세요. (OpenAI 키가 없다면 Anthropic 의 큰 모델과 작은 모델로 대체해도 됩니다.)

문제만 담긴 파일은 `exercise.ts`, 정답과 해설은 `solution.ts` 입니다. 두 파일 모두 아래 [실습 파일](#실습-파일) 섹션에 전문이 실려 있습니다.

---

## 다음 단계

→ [Step 03 — 메시지와 콘텐츠 블록](../step-03-messages/)

이 스텝에서 `res.text` 와 `res.contentBlocks` 를 슬쩍 썼습니다. 다음 스텝에서는 그 안을 제대로 엽니다. 메시지에는 왜 `role` 이 있는지, `content` 가 왜 문자열이 아니라 블록 배열인지, 이미지·문서·추론·도구 호출이 어떻게 한 메시지 안에 공존하는지를 다룹니다. 이 스텝에서 본 `contentBlocks.filter(b => b.type === "reasoning")` 이 왜 provider 무관하게 동작하는지도 거기서 밝혀집니다.

---

## 실습 파일

이 스텝은 TypeScript 파일 3개로 구성됩니다. 본문(2-1 ~ 2-9)의 예제를 순서대로 담은 `practice.ts` 를 먼저 실행해 결과를 눈으로 확인하고, 그다음 `exercise.ts` 의 8개 문제를 직접 풀어본 뒤, 마지막으로 `solution.ts` 로 채점하고 해설을 읽는 흐름입니다.

세 파일 모두 맨 위에 `import "dotenv/config"` 가 있어 `.env` 의 `ANTHROPIC_API_KEY` 를 읽습니다. 실행은 이렇게 합니다.

```bash
npx tsx docs/reference/langchain/step-02-chat-models/practice.ts
```

**주의**: 이 파일들은 **실제 API 를 호출하므로 요금이 발생합니다.** 전부 `maxTokens` 를 작게 잡아 두었지만, 반복 실행할 때는 필요한 블록만 남기고 주석 처리하세요. 그리고 모든 출력은 모델 응답이므로 **여러분 화면의 숫자와 문장은 본문과 다릅니다** — 같아야 하는 건 필드 이름과 구조뿐입니다.

### practice.ts

본문 강의를 따라가며 손으로 쳐볼 예제를 `[2-1] ~ [2-9]` 주석 번호로 묶어 놓은 파일입니다. 절 번호가 본문 소제목과 1:1 로 대응하므로, 본문을 읽다가 막히면 같은 번호의 블록을 찾아 실행해 보면 됩니다.

- `[2-2]` 의 세 번째 블록이 이 파일에서 가장 중요합니다. `initChatModel` 에 `temperatur`(오타)와 `completelyMadeUpParam` 을 일부러 넣어 두었습니다. **이 파일은 `tsc --strict` 를 통과합니다.** 오타가 컴파일에도 실행에도 안 걸린다는 걸 눈으로 확인하는 게 목적이니 지우지 마세요. 바로 아래에 `new ChatAnthropic()` 로 같은 오타를 넣은 줄이 **주석으로** 있는데, 주석을 풀면 그때는 컴파일 에러가 납니다 — 그게 (C) 방식의 값어치입니다.
- `[2-3]` 은 `maxTokens: 20` 으로 응답을 일부러 자릅니다. `stop_reason: "max_tokens"` 가 찍히는 걸 확인하세요. 이어서 `timeout` 을 `ChatAnthropic` 생성자에 넣은 줄이 **주석으로** 남아 있습니다. 주석을 풀면 `TS2769` 가 나므로 **주석을 지우지 말고** 그대로 두세요 — 2-3 의 지원 매트릭스를 컴파일러로 검증하는 자리입니다.
- `[2-4]` 는 `batch` 를 세 번 돌립니다. (1) 그냥, (2) 실패를 섞어 fail-fast 로 전체가 죽는 것, (3) `returnExceptions: true` 로 살려내는 것. 특히 (3) 에서 `maxConcurrency` 가 **2번째 인자**에, `returnExceptions` 가 **3번째 인자**에 들어가 있는 배치를 눈에 익히세요. deprecated 자리에 넣은 버전이 바로 위에 주석으로 있습니다.
- `[2-5]` 의 `estimateCost` 는 `PRICING` 상수의 단가가 **전부 0** 입니다. 의도된 것입니다 — 실제 단가는 공식 가격 페이지에서 확인해 채워야 하고, 교재가 단가를 박아 두면 반드시 낡습니다. 0 을 채워 넣고 나면 비용이 계산됩니다. `plainInput` 을 구할 때 캐시 토큰을 빼는 줄이 이중 계산 방어의 핵심입니다.
- `[2-9]` 의 캐시 블록은 `console.time` 으로 1회차와 2회차의 소요 시간을 잽니다. 2회차가 **밀리초 이하**로 떨어지는 걸 보면 캐시가 네트워크를 아예 안 탄다는 게 실감납니다. 같은 블록에서 `usage_metadata` 가 1회차와 **똑같이** 재생되는 것도 함께 찍습니다 — 비용 집계가 부풀려지는 함정의 실물입니다.

```ts file="./practice.ts"
```

### exercise.ts

본문 "연습문제" 8개를 그대로 옮겨 담은 **빈칸 채우기용 파일**입니다. 각 문제는 `[문제 N]` 주석 블록으로 구분되어 있고 그 아래 `TODO` 자리가 비어 있으니, 거기에 직접 코드를 써 넣고 파일을 실행해 검증하면 됩니다.

- 파일 하단의 `main()` 에 `await q1(); await q2(); ...` 가 **전부 주석 처리**되어 있습니다. 한 문제씩 풀면서 해당 줄의 주석을 풀어 가세요. 8개를 한꺼번에 돌리면 API 호출이 20번 넘게 나가고 어느 출력이 어느 문제 것인지 알 수 없게 됩니다.
- `[문제 2]` 의 `isTruncated` 는 **Anthropic 과 OpenAI 양쪽에서 동작해야 한다**는 조건이 붙어 있습니다. `stop_reason` 하나만 보면 절반짜리 답입니다. 힌트로 `response_metadata` 를 통째로 찍는 줄을 미리 넣어 두었으니, 실제로 어떤 키가 들어 있는지 먼저 눈으로 보고 시작하세요.
- `[문제 3]` 은 정답이 **하나로 정해져 있지 않은** 문제입니다. `new Set(texts).size` 가 1 이 나와도 정상이고 3 이 나와도 정상입니다. 중요한 건 숫자가 아니라 "1 이 보장되지 않는다"는 사실입니다. 결과를 주석으로 적어 두고 나중에 다시 돌려 비교해 보세요.
- `[문제 4]` 의 첫 부분은 **일부러 프로그램을 죽이는** 코드입니다. `try/catch` 로 감싸 두었지만, `catch` 에 들어왔다는 것 자체가 "성공한 3개도 함께 사라졌다"는 증거입니다. 그 사실을 확인한 다음에 두 번째 부분으로 넘어가세요.
- `[문제 5]` 의 `estimateCost` 시그니처에는 `usage: UsageMetadata` 타입이 이미 박혀 있습니다. `input_token_details` 가 `optional` 이라 `?.` 와 `?? 0` 없이는 컴파일이 안 될 겁니다 — 그 컴파일 에러가 곧 힌트입니다.
- `[문제 7]` 은 (a)(b)(c) 세 부분에 더해 **`maxTokens: 2100` 으로 낮춰 다시 돌리는** 마지막 단계가 있습니다. 이 단계를 빼먹지 마세요. `budget_tokens` 와 `maxTokens` 의 관계를 몸으로 아는 유일한 방법입니다.

```ts file="./exercise.ts"
```

### solution.ts

8문제의 정답 코드와 해설 주석을 담은 파일입니다. `exercise.ts` 를 스스로 풀어본 **뒤에** 열어보세요. 각 정답 위 주석에 "무엇을 보게 되는가"까지 적혀 있어 채점표로 바로 쓸 수 있습니다.

- `[정답 1]` 의 에러 메시지는 `TypeError: model.invoke is not a function` 입니다. `await` 를 빼면 `model` 이 `Promise<ConfigurableModel>` 이 되고, Promise 에는 `invoke` 가 없기 때문입니다. 해설 주석에 **왜 `initChatModel` 만 `await` 가 필요하고 `new ChatAnthropic()` 은 아닌지**(동적 import 여부) 적어 두었습니다.
- `[정답 2]` 의 `isTruncated` 는 `stop_reason === "max_tokens" || finish_reason === "length"` **둘 다** 봅니다. 한쪽만 보는 답은 provider 를 바꾸는 순간 조용히 `false` 를 반환하기 시작합니다 — 에러도 안 나고 "잘림 감지"만 죽습니다. 이 스텝 전체에서 가장 실무적인 5줄입니다.
- `[정답 4]` 가 이 파일의 하이라이트입니다. `returnExceptions: true` 를 넣으면 반환 타입이 `AIMessage[]` 에서 `(AIMessage | Error)[]` 로 **바뀌기 때문에**, `instanceof Error` 로 좁히지 않으면 `.text` 접근에서 컴파일 에러가 납니다. 타입 시스템이 에러 처리를 강제하는 좋은 예입니다. 그리고 `maxConcurrency` 를 3번째 인자에 넣은 **틀린 버전**을 주석으로 나란히 두었습니다 — 그 버전은 컴파일도 되고 실행도 되지만 deprecated 경로라, "돌아간다 ≠ 맞다"의 사례입니다.
- `[정답 5]` 의 핵심 한 줄은 `const plainInput = Math.max(0, usage.input_tokens - cacheRead - cacheWrite)` 입니다. `Math.max(0, ...)` 로 감싼 이유까지 주석에 있습니다 — provider 가 details 를 부정확하게 채우면 음수가 나올 수 있고, 음수 토큰이 비용을 **깎아 버리면** 집계가 조용히 틀립니다.
- `[정답 7]` 에서 `maxTokens: 2100`, `budget_tokens: 2000` 조합의 결과는 `stop_reason: "max_tokens"` 입니다. 생각에 2000 토큰을 쓰고 답변에 100 토큰밖에 안 남아 답을 못 끝낸 것이죠. **추론 토큰 값은 다 치르고 답은 못 받은** 상태입니다. 해설에 `maxTokens` 를 `budget_tokens` 대비 얼마나 여유 있게 잡아야 하는지 기준을 적어 두었습니다.

```ts file="./solution.ts"
```
