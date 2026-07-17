/**
 * Step 02 — 챗 모델과 파라미터
 * 실행: npx tsx docs/reference/langchain/step-02-chat-models/practice.ts
 *
 * 주의: 이 파일은 실제 API 를 호출하므로 요금이 발생합니다.
 *      모든 블록은 maxTokens 를 작게 잡아 두었지만, 반복 실행할 때는
 *      main() 하단에서 필요한 블록만 남기고 주석 처리하세요.
 *
 * 검증 버전: langchain 1.5.3 / @langchain/core 1.2.3
 *           @langchain/anthropic 1.5.1 / @langchain/openai 1.5.5
 */
import "dotenv/config";

import { initChatModel } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import { InMemoryCache } from "@langchain/core/caches";
import type { AIMessage, AIMessageChunk, UsageMetadata } from "@langchain/core/messages";

const MODEL_ID = "anthropic:claude-sonnet-4-6";

/* ===== [2-9] 계측 헬퍼 — 아래 모든 블록에서 재사용한다 ===== */

/**
 * 응답 하나를 이 스텝에서 배운 관점 전부로 해부해 출력한다.
 * - 종료 이유(잘렸는지)
 * - 입력/출력/합계 토큰
 * - 캐시 읽기/생성 토큰
 * - 추론 토큰
 */
function inspect(res: AIMessage, label = "") {
  const meta = res.response_metadata as Record<string, unknown>;
  const u = res.usage_metadata;

  // 종료 이유 필드명이 provider 마다 다르다. 둘 다 봐야 한다.
  //   Anthropic: stop_reason === "max_tokens"
  //   OpenAI   : finish_reason === "length"
  const truncated = meta.stop_reason === "max_tokens" || meta.finish_reason === "length";

  console.log(`--- ${label} ---`);
  console.log(`텍스트   : ${res.text.slice(0, 60)}${res.text.length > 60 ? "..." : ""}`);
  console.log(
    `종료 이유: ${meta.stop_reason ?? meta.finish_reason ?? "(알 수 없음)"}` +
      `${truncated ? "  ⚠️ 잘림!" : ""}`,
  );
  console.log(
    `토큰     : 입력 ${u?.input_tokens ?? 0} / 출력 ${u?.output_tokens ?? 0} / 합계 ${u?.total_tokens ?? 0}`,
  );
  console.log(
    `캐시     : 읽기 ${u?.input_token_details?.cache_read ?? 0} / 생성 ${u?.input_token_details?.cache_creation ?? 0}`,
  );
  console.log(`추론     : ${u?.output_token_details?.reasoning ?? 0} 토큰`);
  console.log("");
}

/* ===== [2-1] 챗 모델 추상화 — 어느 provider 든 같은 인터페이스 ===== */

async function section_2_1() {
  console.log("========== [2-1] 챗 모델 추상화 ==========\n");

  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 200 });
  const res = await model.invoke("광합성을 한 문장으로 설명해줘.");

  // provider 가 무엇이든 아래 네 가지는 항상 같은 이름, 같은 shape 다.
  // 이것이 앞으로 배울 createAgent / 미들웨어 / 스트리밍이 서 있는 계약이다.
  console.log("res.text          :", res.text);
  console.log("res.usage_metadata:", res.usage_metadata);
  console.log("res.contentBlocks :", res.contentBlocks.map((b) => b.type));
  console.log("res.tool_calls    :", res.tool_calls);
  console.log("");
}

/* ===== [2-2] 모델을 만드는 세 가지 방법 ===== */

async function section_2_2() {
  console.log("========== [2-2] 모델 생성 세 방식 ==========\n");

  // --- (A) initChatModel + "provider:model" 문자열 ---
  // await 가 필요하다! 접두사를 보고 통합 패키지를 "동적으로" import 하기 때문이다.
  // await 를 빼면 model 이 Promise 가 되고 "model.invoke is not a function" 이 난다.
  const a = await initChatModel("anthropic:claude-sonnet-4-6", {
    temperature: 0,
    maxTokens: 100,
  });
  console.log("(A) initChatModel :", (await a.invoke("1+1은? 숫자만.")).text);

  // --- (B) 문자열만 (createAgent 등 상위 API 에 그대로 넘긴다) ---
  // createAgent({ model: "anthropic:claude-sonnet-4-6", tools: [] })
  // → 내부에서 알아서 initChatModel 을 부른다. Step 08 에서 본격적으로 쓴다.

  // --- (C) new ChatAnthropic() 직접 생성 ---
  // await 가 없다(동적 import 가 아니니까).
  // 그리고 provider 고유 파라미터가 타입으로 잡힌다.
  const c = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    temperature: 0,
    maxTokens: 100,
    topK: 40, //                       ← Anthropic 에만 있는 파라미터
    thinking: { type: "disabled" }, //  ← Anthropic 에만 있는 파라미터
  });
  console.log("(C) new ChatAnthropic:", (await c.invoke("2+2는? 숫자만.")).text);
  console.log("");

  /* --- ⚠️ 함정: initChatModel 의 옵션은 타입 체크를 받지 않는다 --- */
  // initChatModel 의 2번째 인자 타입은 Partial<Record<string, any>> 다.
  // 즉 아무 키나 넣어도 컴파일이 통과한다. 아래 줄은 tsc --strict 를 그냥 통과한다.
  // 지우지 말 것 — 오타가 컴파일에도 실행에도 안 걸린다는 걸 눈으로 확인하는 자리다.
  const typo = await initChatModel("anthropic:claude-sonnet-4-6", {
    temperatur: 0.9, //            ← 오타. 에러 안 남. temperature 는 설정되지 않는다.
    completelyMadeUpParam: 123, // ← 존재하지 않는 파라미터. 에러 안 남.
    maxTokens: 50,
  });
  console.log("⚠️ 오타를 넣어도 그냥 실행된다:", (await typo.invoke("3+3은? 숫자만.")).text);

  // 반면 (C) 방식은 같은 오타를 컴파일 타임에 잡아준다.
  // 아래 주석을 풀면 컴파일 에러가 난다:
  //   error TS2769: Object literal may only specify known properties,
  //                 and 'temperatur' does not exist in type 'ChatAnthropicInput'.
  // → 이것이 (C) 방식의 값어치다. 주석을 지우지 말고 그대로 둘 것.
  //
  // new ChatAnthropic({ model: "claude-sonnet-4-6", temperatur: 0.9 });

  console.log("");
}

/* ===== [2-3] 파라미터 완전 해부 ===== */

async function section_2_3() {
  console.log("========== [2-3] 파라미터 완전 해부 ==========\n");

  /* --- ⚠️ 함정: maxTokens 를 넘기면 응답이 "조용히" 잘린다 --- */
  // 에러가 나지 않는다. 20 토큰까지 만들고 문장 중간에서 뚝 끊긴
  // AIMessage 가 정상 응답인 척 돌아온다.
  const truncating = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 20, // ← 일부러 아주 작게
  });
  const cut = await truncating.invoke("광합성을 아주 자세히, 단계별로 설명해줘.");
  inspect(cut, "maxTokens: 20 (잘림 예상)");
  // → stop_reason 이 "max_tokens" 로 찍히는 것을 확인하라.
  //   res.text 만 봐서는 완성된 답인지 잘린 답인지 구분할 방법이 없다.
  //   JSON 을 요구했다면 더 나쁘다 — 잘린 JSON 은 파싱에 실패하고,
  //   "모델이 JSON 을 못 만든다"고 오해하며 프롬프트를 고치기 시작한다.

  /* --- stopSequences: 정지 문자열 자체는 출력에 포함되지 않는다 --- */
  const stopping = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 300,
    stopSequences: ["3."], // "3." 이 나오면 즉시 멈춘다
  });
  const stopped = await stopping.invoke("과일 이름을 1. 2. 3. 4. 형식으로 4개 나열해줘.");
  inspect(stopped, 'stopSequences: ["3."]');
  // → stop_reason 이 "stop_sequence" 다. "max_tokens" 와 달리 이건 정상 종료다.
  //   그리고 "3." 자체는 출력에 포함되지 않는다.

  /* --- ⚠️ 함정: 모델마다 지원 파라미터가 다르다 (timeout 이 대표 사례) --- */
  // ChatOpenAI 에는 timeout 이 있지만 ChatAnthropic 생성자에는 없다.
  // 아래 주석을 풀면 컴파일 에러가 난다:
  //   error TS2769: Object literal may only specify known properties,
  //                 and 'timeout' does not exist in type 'ChatAnthropicInput'.
  // → 주석을 지우지 말 것. 본문 2-3 의 지원 매트릭스를 컴파일러로 검증하는 자리다.
  //
  // new ChatAnthropic({ model: "claude-sonnet-4-6", timeout: 30000 });

  // ChatAnthropic 에서 타임아웃을 주는 올바른 두 가지 방법:
  // 방법 1: clientOptions.timeout (밀리초)
  const withClientTimeout = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 50,
    clientOptions: { timeout: 30_000 },
  });
  console.log("clientOptions.timeout:", (await withClientTimeout.invoke("4+4는? 숫자만.")).text);

  // 방법 2: 호출 시점에 넘긴다 (밀리초). provider 무관하게 동작하므로 이쪽을 권장.
  const m = await initChatModel(MODEL_ID, { maxTokens: 50 });
  console.log("호출 옵션 timeout   :", (await m.invoke("5+5는? 숫자만.", { timeout: 30_000 })).text);

  // ⚠️ 단위 함정: RunnableConfig.timeout 의 JSDoc 은
  //   "Timeout for this call in milliseconds" 라고 명시한다.
  //   timeout: 30 은 30초가 아니라 30밀리초다.
  //   그리고 이걸 initChatModel 에 넘기면 (위 함정대로) 타입 체크도 없이 조용히 무시된다.

  console.log("");
}

/* ===== [2-4] invoke / batch / stream ===== */

async function section_2_4() {
  console.log("========== [2-4] invoke / batch / stream ==========\n");

  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 100 });

  /* --- invoke: 하나 보내고 하나 받는다 --- */
  const one = await model.invoke("고양이를 한 문장으로 설명해줘.");
  console.log("[invoke]", one.text);
  console.log("");

  /* --- stream: 만들어지는 대로 받는다 --- */
  // 총 소요 시간은 invoke 와 비슷하지만 첫 글자가 나오는 시간(TTFT)이 극적으로 짧다.
  // 사용자에게 보여주는 모든 곳에서는 stream 이 기본값이어야 한다.
  process.stdout.write("[stream] ");
  let final: AIMessageChunk | undefined;
  for await (const chunk of await model.stream("강아지를 한 문장으로 설명해줘.")) {
    process.stdout.write(chunk.text);
    // ⚠️ 각 청크의 usage_metadata 는 대부분 undefined 다. 토큰 수는 마지막 청크에 실려 온다.
    //    청크를 concat 으로 합치면 usage_metadata 가 병합되어 온전한 값이 나온다.
    final = final === undefined ? chunk : final.concat(chunk);
  }
  console.log("");
  console.log("[stream] 합친 뒤 usage_metadata:", final?.usage_metadata);
  console.log("");

  /* --- batch: 여러 개를 동시에. 입력 순서 = 출력 순서가 보장된다 --- */
  // 주의: 이 batch 는 OpenAI Batch API 같은 "묶어서 싸게 처리하는 오프라인 배치"가 아니다.
  //      그냥 N 개의 요청을 병렬로 날리는 것이다. 할인은 없다.
  const questions = ["고양이를 한 문장으로", "강아지를 한 문장으로", "앵무새를 한 문장으로"];
  const results = await model.batch(questions);
  results.forEach((r, i) => console.log(`[batch ${i}] ${r.text}`));
  console.log("→ results[i] 는 반드시 questions[i] 의 답이다. 순서는 보장된다.");
  console.log("");

  /* --- ⚠️ 함정: batch 는 실패 하나가 전체를 죽인다 (fail-fast) --- */
  const broken = new ChatAnthropic({
    model: "this-model-does-not-exist", // ← 반드시 실패한다
    maxTokens: 50,
    maxRetries: 0, // 실습이니 재시도로 시간 끌지 않는다
  });
  try {
    await broken.batch(["a", "b", "c"]);
    console.log("여기에 도달하면 안 된다");
  } catch (e) {
    // 3개 중 하나만 실패해도 전체가 throw 한다.
    // 성공한 것들의 결과도 함께 사라진다. 돈은 다 냈는데 손에 남는 게 없다.
    console.log("[fail-fast] 전체가 죽었다:", (e as Error).message.slice(0, 80));
  }
  console.log("");

  /* --- ✅ returnExceptions: true 로 부분 실패를 살려낸다 --- */

  // ❌ deprecated 자리 — maxConcurrency 를 3번째 인자(batchOptions)에 넣은 버전.
  //    @langchain/core 1.2.3 의 타입 정의가 직접 이렇게 말한다:
  //      /** @deprecated Pass in via the standard runnable config object instead */
  //      maxConcurrency?: number;
  //    컴파일도 되고 실행도 된다. 하지만 deprecated 경로다. "돌아간다 ≠ 맞다".
  //
  // await model.batch(questions, {}, { maxConcurrency: 2, returnExceptions: true });

  // ✅ 올바른 자리 — 2번째 인자(config)에 maxConcurrency, 3번째(batchOptions)에 returnExceptions.
  //    두 인자의 역할이 다르다:
  //      2번째 = config       (모델 호출 하나하나에 적용될 설정)
  //      3번째 = batchOptions (배치 자체의 동작)
  const safe = await model.batch(
    questions,
    { maxConcurrency: 2 }, //      ← config
    { returnExceptions: true }, // ← batchOptions
  );
  //    ^? (AIMessage | Error)[]
  // 반환 타입이 AIMessage[] 에서 (AIMessage | Error)[] 로 바뀐다는 점에 주목.
  // 타입 시스템이 "이제 에러를 처리해야 한다"고 강제한다.
  // instanceof Error 체크를 빼먹으면 .text 접근에서 컴파일이 안 된다.
  for (const [i, r] of safe.entries()) {
    if (r instanceof Error) {
      console.log(`[safe ${i}] 실패: ${r.message}`); // 이 항목만 재시도하면 된다
    } else {
      console.log(`[safe ${i}] ${r.text.slice(0, 40)}...`);
    }
  }

  // ⚠️ maxConcurrency 의 기본값은 Infinity 다. 즉 아무 제한이 없다.
  //    model.batch(inputs) 에 1000개를 넣으면 1000개가 동시에 출발한다.
  //    결과는 즉각적인 429 폭격이고, maxRetries 기본값 6이 그 1000개를
  //    각각 최대 6번씩 재시도하면서 상황을 더 악화시킨다.

  console.log("");
}

/* ===== [2-5] 토큰과 비용 — usage_metadata 읽기 ===== */

type Pricing = {
  inputPerMTok: number; // 100만 토큰당 USD
  outputPerMTok: number;
  cacheReadPerMTok: number;
  cacheWritePerMTok: number;
};

/**
 * ⚠️ 단가가 전부 0인 것은 의도된 것이다.
 * 모델 단가는 바뀌고, 새 모델이 나오고, 교재에 박아 두면 반드시 낡는다.
 * 반드시 각 provider 의 공식 가격 페이지에서 확인해 채워 넣어라.
 * 실무에서는 이 표를 설정 파일이나 DB 에 둔다.
 */
const PRICING: Record<string, Pricing> = {
  "anthropic:claude-sonnet-4-6": {
    inputPerMTok: 0,
    outputPerMTok: 0,
    cacheReadPerMTok: 0,
    cacheWritePerMTok: 0,
  },
  "openai:gpt-5.5": {
    inputPerMTok: 0,
    outputPerMTok: 0,
    cacheReadPerMTok: 0,
    cacheWritePerMTok: 0,
  },
};

function estimateCost(usage: UsageMetadata, p: Pricing): number {
  // input_token_details 는 optional 이고, 키가 아예 없을 수도 있다.
  // 타입 정의 주석: "Does *not* need to have all keys."
  // 그래서 ?. 와 ?? 0 이 필수다.
  const cacheRead = usage.input_token_details?.cache_read ?? 0;
  const cacheWrite = usage.input_token_details?.cache_creation ?? 0;

  // ⚠️ 이중 계산 방어 — 이 스텝에서 가장 중요한 한 줄.
  // input_tokens 는 캐시 토큰을 "포함한" 총합이다("Sum of all input token types").
  // 캐시분을 빼야 일반 입력 토큰이 남는다. 안 빼면 캐시 토큰 요금을 두 번 낸다.
  // Math.max(0, ...) 로 감싼 이유: provider 가 details 를 부정확하게 채우면 음수가
  // 나올 수 있고, 음수 토큰이 비용을 깎아 버리면 집계가 조용히 틀린다.
  const plainInput = Math.max(0, usage.input_tokens - cacheRead - cacheWrite);

  return (
    (plainInput / 1_000_000) * p.inputPerMTok +
    (cacheRead / 1_000_000) * p.cacheReadPerMTok +
    (cacheWrite / 1_000_000) * p.cacheWritePerMTok +
    (usage.output_tokens / 1_000_000) * p.outputPerMTok
  );
}

async function section_2_5() {
  console.log("========== [2-5] 토큰과 비용 ==========\n");

  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 200 });
  const res = await model.invoke("광합성을 한 문장으로 설명해줘.");

  // 필드 이름과 구조는 결정적이다. 토큰 "숫자"만 매번 다르다.
  console.log("usage_metadata:", JSON.stringify(res.usage_metadata, null, 2));

  const usage = res.usage_metadata;
  if (usage) {
    const cost = estimateCost(usage, PRICING[MODEL_ID]);
    console.log(`추정 비용: $${cost.toFixed(6)}  (단가가 0이라 0이 나온다 — 위 PRICING 참고)`);
  }
  console.log("");
}

/* ===== [2-6] reasoning 모델 다루기 ===== */

async function section_2_6() {
  console.log("========== [2-6] reasoning 모델 ==========\n");

  // thinking 은 표준 파라미터가 아니라 Anthropic 고유 파라미터다.
  // 그래서 new ChatAnthropic() 으로 만들어 타입 체크를 받는 편이 안전하다.
  //
  // thinking 의 세 가지 형태:
  //   { type: "enabled", budget_tokens: N }  추론 켬. 최대 N 토큰까지 생각
  //   { type: "disabled" }                   추론 끔. ChatAnthropic 의 기본값
  //   { type: "adaptive" }                   모델이 난이도를 보고 알아서 조절
  //
  // OpenAI 는 이렇게 한다:
  //   new ChatOpenAI({ model: "gpt-5.5", reasoning: { effort: "low" } })
  //   ("low" | "medium" | "high". reasoningEffort 는 deprecated 이니 reasoning.effort 를 쓸 것)
  const model = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 4000, //                                   ← budget_tokens 보다 넉넉히 크게!
    thinking: { type: "enabled", budget_tokens: 2000 },
  });

  const res = await model.invoke("17 * 24 를 암산하듯 단계별로 계산해줘.");

  // provider 가 달라도 contentBlocks 로 표준화되어 나온다.
  // Anthropic 은 thinking 블록으로, OpenAI 는 reasoning 블록으로 주지만
  // contentBlocks 를 거치면 둘 다 type: "reasoning" 이다.
  const reasoning = res.contentBlocks.filter((b) => b.type === "reasoning");
  console.log("--- 추론 과정 (res.text 에는 안 들어 있다) ---");
  console.log(
    reasoning
      .map((b) => (b as { reasoning?: string }).reasoning ?? "")
      .join("\n")
      .slice(0, 300) + "...",
  );
  console.log("");
  console.log("--- 최종 답변 ---");
  console.log(res.text);
  console.log("");

  // 추론 토큰: 출력으로 돌려주지 않는데 output_tokens 에는 포함되고, 요금은 낸다.
  // 타입 주석: "Tokens generated by the model in a chain of thought process
  //            ... that are not returned as part of model output."
  const u = res.usage_metadata;
  console.log(`출력 토큰 합계: ${u?.output_tokens ?? 0}`);
  console.log(`  그중 추론   : ${u?.output_token_details?.reasoning ?? 0}  ← 화면에 못 쓰는데 요금은 냄`);
  console.log(
    `  실제 답변   : ${(u?.output_tokens ?? 0) - (u?.output_token_details?.reasoning ?? 0)}`,
  );
  console.log("");

  /* --- ⚠️ 함정: maxTokens 가 추론 토큰을 포함한다 --- */
  // maxTokens: 2100 + budget_tokens: 2000 → 답변에 쓸 공간이 100 토큰뿐이다.
  // 생각만 하다 답을 못 내고 stop_reason: "max_tokens" 로 잘린다.
  // 생각 값은 다 치르고 답은 못 받는 최악의 조합.
  const tight = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 2100, //                                   ← budget_tokens 보다 겨우 100 큼
    thinking: { type: "enabled", budget_tokens: 2000 },
  });
  const squeezed = await tight.invoke("17 * 24 를 암산하듯 단계별로 계산해줘.");
  inspect(squeezed, "maxTokens: 2100 / budget_tokens: 2000 (잘림 예상)");

  // 그 밖의 reasoning 함정 (본문 2-6 참고):
  //  - reasoning 모델은 temperature 를 무시한다. @langchain/openai 의 reasoning JSDoc:
  //    "These options will be ignored when not using a reasoning model."
  //  - Anthropic 은 thinking 이 켜져 있으면 temperature 를 함께 쓸 수 없다.
  //  - thinking 이 켜진 채 withStructuredOutput() 을 쓰면 콘솔 경고가 뜨고,
  //    (에러가 아니라 경고다!) 어느 날 갑자기 OutputParserException 이 터진다. Step 05 참고.
}

/* ===== [2-7] 모델 선택 가이드 — 라우터 패턴 ===== */

async function section_2_7() {
  console.log("========== [2-7] 모델 선택 — 라우터 패턴 ==========\n");

  // 실무의 정석은 "모델 하나 고르기"가 아니라 "역할별로 나누기"다.
  // 2-1 에서 말한 추상화의 값어치가 여기서 현금화된다.
  const router = await initChatModel("anthropic:claude-haiku-4-5", {
    temperature: 0,
    maxTokens: 20,
  });
  const worker = await initChatModel("anthropic:claude-sonnet-4-6", {
    temperature: 0,
    maxTokens: 200,
  });

  for (const q of ["오늘 날씨 어때?", "분산 시스템에서 합의 알고리즘의 트레이드오프를 분석해줘"]) {
    const intent = (
      await router.invoke(`다음을 [단순질문|복잡분석] 중 하나로만 분류. 다른 말 금지: ${q}`)
    ).text;

    // 라우팅 결과에 따라 모델을 고른다. 호출 코드는 어느 쪽이든 똑같다.
    const model = intent.includes("복잡분석") ? worker : router;
    const label = intent.includes("복잡분석") ? "sonnet(큰 모델)" : "haiku(작은 모델)";

    const answer = await model.invoke(q);
    console.log(`질문: ${q}`);
    console.log(`  분류: ${intent.trim()} → ${label}`);
    console.log(`  답변: ${answer.text.slice(0, 60)}...`);
    console.log("");
  }

  // 트래픽의 80% 가 단순 질문이면 비용이 극적으로 줄어든다.
  // 라우팅 자체의 비용은 무시할 만하다.
  //
  // 순서도 중요하다: 큰 모델로 먼저 "되는 것"을 확인한 뒤, 구간별로 작은 모델로
  // 내려보며 품질이 유지되는 지점을 찾아라. 처음부터 작은 모델로 시작하면
  // "모델이 약해서 안 되는 건지 내 프롬프트가 틀린 건지" 구분이 안 된다.
  // 일단 되게 만들고, 그 다음에 싸게 만들어라.
}

/* ===== [2-8] provider 교체 실습 — 같은 코드로 anthropic ↔ openai ===== */

/**
 * 이 함수는 provider 를 전혀 모른다. 모델 인터페이스만 안다.
 * batch, usage_metadata.input_tokens, text — 전부 표준 인터페이스다.
 */
async function askAll(
  model: Awaited<ReturnType<typeof initChatModel>>,
  questions: string[],
  label: string,
) {
  const results = await model.batch(questions, { maxConcurrency: 2 }, { returnExceptions: true });

  let totalIn = 0;
  let totalOut = 0;
  console.log(`--- ${label} ---`);
  for (const [i, r] of results.entries()) {
    if (r instanceof Error) {
      console.log(`[${i}] 실패: ${r.message.slice(0, 60)}`);
      continue;
    }
    totalIn += r.usage_metadata?.input_tokens ?? 0;
    totalOut += r.usage_metadata?.output_tokens ?? 0;
    console.log(`[${i}] ${r.text.slice(0, 50)}...`);
  }
  console.log(`합계: 입력 ${totalIn} / 출력 ${totalOut} 토큰`);
  console.log("");
}

async function section_2_8() {
  console.log("========== [2-8] provider 교체 ==========\n");

  const questions = ["광합성을 한 문장으로", "중력을 한 문장으로"];

  // provider 를 문자열로만 바꾼다. askAll 은 한 글자도 안 바뀐다.
  await askAll(
    await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0, maxTokens: 200 }),
    questions,
    "anthropic:claude-sonnet-4-6",
  );

  // OPENAI_API_KEY 와 @langchain/openai 가 있어야 동작한다.
  // provider 를 바꾼다고 API 키가 자동으로 생기지는 않는다.
  if (process.env.OPENAI_API_KEY) {
    await askAll(
      await initChatModel("openai:gpt-5.5", { temperature: 0, maxTokens: 200 }),
      questions,
      "openai:gpt-5.5",
    );
  } else {
    console.log("(OPENAI_API_KEY 가 없어 OpenAI 비교는 건너뜁니다)\n");
  }

  // 환경변수로 빼면 배포 설정만으로 모델을 바꿀 수 있다:
  //   MODEL="anthropic:claude-sonnet-4-6" npx tsx practice.ts
  //   MODEL="openai:gpt-5.5"              npx tsx practice.ts
  //
  // ⚠️ 다만 교체되는 건 인터페이스지 동작이 아니다. 코드가 그대로 돈다고
  //    결과가 같지는 않다. 프롬프트 민감도, temperature 범위(0~1 vs 0~2),
  //    무시되는 파라미터(topK), response_metadata 필드명(stop_reason vs finish_reason)이
  //    전부 함께 바뀐다. 바꿨으면 반드시 평가를 다시 돌려라(Step 19).
}

/* ===== [2-9] 종합 — 캐싱 ===== */

async function section_2_9() {
  console.log("========== [2-9] 캐싱 ==========\n");

  // LangChain 캐시: 정확히 같은 프롬프트면 모델 호출 자체를 건너뛴다.
  // 2-3 에서 "temperature: 0 은 결정성을 보장하지 않는다"고 했다. 캐시는 보장한다.
  const cache = new InMemoryCache();
  const model = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 200,
    cache, // 또는 그냥 cache: true
  });

  const prompt = "광합성을 한 문장으로 설명해줘.";

  console.time("1회차");
  const first = await model.invoke(prompt);
  console.timeEnd("1회차"); // ← 실제 호출. 보통 1~3초.

  console.time("2회차");
  const second = await model.invoke(prompt);
  console.timeEnd("2회차"); // ← 캐시 히트. 네트워크를 아예 안 탄다. 보통 1ms 미만.

  console.log("");
  console.log("두 응답이 완전히 같은가?:", first.text === second.text); // true — 캐시니까

  /* --- ⚠️ 함정: 캐시 히트 시 usage_metadata 가 "재생"된다 --- */
  console.log("1회차 usage:", first.usage_metadata);
  console.log("2회차 usage:", second.usage_metadata);
  // → 두 값이 똑같다! 하지만 2회차는 실제로 API 를 안 불렀으니 비용이 0원이다.
  //   비용 집계 코드는 요금이 발생한 것처럼 센다.
  //   캐시 적중률이 높은 개발 환경에서 비용 대시보드가 실제보다 부풀려 보이는 원인이 이것이다.
  //
  // ⚠️ InMemoryCache 는 이름 그대로 메모리에만 있다.
  //    프로세스가 죽으면 다 날아가고, 서버 여러 대에 걸쳐 공유되지도 않는다.
  //    (Step 10 의 MemorySaver 와 정확히 같은 성질이다.)
  console.log("");

  /* --- provider 프롬프트 캐시 (cache_control) — 위 LangChain 캐시와 전혀 다른 물건 --- */
  // LangChain 캐시     : 내 프로세스 안. 프롬프트가 완전히 동일할 때. API 호출 안 함. 비용 0원.
  // provider 프롬프트 캐시: provider 서버. 프롬프트 "접두사"가 동일할 때. API 호출 함. 할인된 입력 단가.
  const LONG_TEXT = "LangChain 은 LLM 애플리케이션을 만드는 프레임워크입니다. ".repeat(120);

  const cachingModel = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 100 });
  const messages = [
    {
      role: "system",
      content: [
        {
          type: "text",
          text: LONG_TEXT, //                     길고, 매 요청 동일한 내용
          cache_control: { type: "ephemeral" }, // ← 여기까지 캐시
        },
      ],
    },
    { role: "user", content: "이 문서를 한 문장으로 요약해줘." },
  ];

  const p1 = await cachingModel.invoke(messages);
  console.log("프롬프트 캐시 1회차:", p1.usage_metadata?.input_token_details);
  // 예: { cache_creation: 2431, cache_read: 0 }     ← 캐시 만듦 (일반 입력보다 비쌈!)

  const p2 = await cachingModel.invoke(messages);
  console.log("프롬프트 캐시 2회차:", p2.usage_metadata?.input_token_details);
  // 예: { cache_creation: 0,    cache_read: 2431 }  ← 캐시 읽음 (쌈)

  // ⚠️ 프롬프트 캐시는 접두사(prefix) 매칭이다. 앞에서부터 한 글자라도 다르면
  //    그 지점 이후는 전부 캐시 미스다. 그래서 프롬프트를 이렇게 배치해야 한다:
  //      [불변] 시스템 프롬프트, 도구 정의, 참고 문서   ← cache_control 을 여기 끝에
  //      [가변] 대화 이력
  //      [가변] 이번 사용자 입력
  //
  //    흔한 실수는 맨 앞에 타임스탬프나 요청 ID 를 넣는 것이다.
  //    "현재 시각: 2026-07-17 14:23:11" 한 줄이 맨 앞에 있으면 캐시가 영원히 100% 미스한다.
  //    게다가 캐시 생성 단가는 일반 입력보다 비싸므로 캐시를 켜기 전보다 오히려 비싸진다.
  //    이 함정은 에러도 안 내고 로그도 안 남긴다 — cache_read 가 계속 0 인 것으로만 알 수 있다.
  //    캐시를 켰다면 cache_read 를 반드시 모니터링하라.

  console.log("");
  inspect(p2, "종합 — 계측 헬퍼로 전부 해부");
}

/* ===== 실행 ===== */

async function main() {
  // ⚠️ 실제 API 를 호출하므로 요금이 발생합니다.
  //    반복 실행할 때는 필요한 블록만 남기고 주석 처리하세요.
  await section_2_1();
  await section_2_2();
  await section_2_3();
  await section_2_4();
  await section_2_5();
  await section_2_6();
  await section_2_7();
  await section_2_8();
  await section_2_9();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
