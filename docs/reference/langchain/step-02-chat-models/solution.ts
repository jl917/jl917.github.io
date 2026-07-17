/**
 * Step 02 — 챗 모델과 파라미터 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-02-chat-models/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 "뒤에" 열어보세요.
 * 각 정답 위 주석에 "무엇을 보게 되는가"까지 적어 두어 채점표로 바로 쓸 수 있습니다.
 *
 * 주의: 실제 API 를 호출하므로 요금이 발생합니다.
 */
import "dotenv/config";

import { initChatModel } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import type { AIMessage, AIMessageChunk, UsageMetadata } from "@langchain/core/messages";

const MODEL_ID = "anthropic:claude-sonnet-4-6";

/* ===== [정답 1] await 를 빼면 무슨 일이 일어나나 ===== */
/**
 * 에러 메시지: TypeError: model.invoke is not a function
 *
 * 해설:
 *   await 를 빼면 model 이 Promise<ConfigurableModel> 이 된다.
 *   Promise 에는 invoke 메서드가 없으므로 TypeError 가 난다.
 *
 * 왜 initChatModel 만 await 가 필요하고 new ChatAnthropic() 은 아닌가?
 *   initChatModel("anthropic:...") 은 접두사 "anthropic" 을 보고
 *   @langchain/anthropic 패키지를 "동적으로" import 한다 (await import(...)).
 *   동적 import 는 비동기이므로 함수 전체가 Promise 를 돌려줄 수밖에 없다.
 *
 *   반면 new ChatAnthropic() 은 이미 정적으로 import 된 클래스의 생성자를
 *   부르는 것뿐이다. 비동기 작업이 없으니 await 도 필요 없다.
 *
 *   이것이 2-2 비교표의 "await 필요" 행이 존재하는 이유다.
 *   문자열로 provider 를 고르는 유연성의 대가가 곧 이 Promise 다.
 *
 * TypeScript 를 쓰면 사실 이 실수는 컴파일 타임에 잡힌다:
 *   Property 'invoke' does not exist on type 'Promise<ConfigurableModel<...>>'.
 *   → 그래서 이 함정은 JS 로 쓰거나 any 로 받을 때 주로 터진다.
 */
async function q1() {
  console.log("===== [정답 1] await 누락 =====");

  // ❌ 틀린 코드 — await 가 없다.
  //    TS 에서는 아예 컴파일이 안 되므로, 런타임 에러를 재현하려면 any 를 거쳐야 한다.
  //    (실무에서 이 함정이 터지는 경로가 정확히 이것이다: JS 이거나, any 로 받거나.)
  try {
    const broken: any = initChatModel(MODEL_ID, { maxTokens: 50 }); // ← await 없음
    await broken.invoke("1+1은?");
    console.log("여기에 도달하면 안 된다");
  } catch (e) {
    console.log("에러:", (e as Error).message);
    // → TypeError: broken.invoke is not a function
  }

  // ✅ 올바른 코드
  const model = await initChatModel(MODEL_ID, { maxTokens: 50 });
  const res = await model.invoke("1+1은? 숫자만.");
  console.log("정상:", res.text);
  console.log("");
}

/* ===== [정답 2] 잘림 판정 함수 ===== */
/**
 * 이 스텝 전체에서 가장 실무적인 5줄이다.
 *
 * 핵심: stop_reason 과 finish_reason 을 "둘 다" 봐야 한다.
 *   Anthropic: response_metadata.stop_reason === "max_tokens"
 *   OpenAI   : response_metadata.finish_reason === "length"
 *
 * 한쪽만 보는 답은 provider 를 바꾸는 순간 조용히 false 를 반환하기 시작한다.
 * 에러도 안 나고, 로그도 안 남고, "잘림 감지" 기능만 죽는다.
 * 이것이 본문 2-8 에서 말한 "교체되는 건 인터페이스지 동작이 아니다"의 실물이다.
 *
 * 참고: response_metadata 는 표준화되지 않은 provider 원본 데이터다.
 *      usage_metadata / text / contentBlocks 와 달리 필드명이 provider 마다 다르다.
 *      그래서 Record<string, unknown> 으로 캐스팅해서 방어적으로 읽는다.
 */
function isTruncated(res: AIMessage): boolean {
  const meta = res.response_metadata as Record<string, unknown>;
  return meta.stop_reason === "max_tokens" || meta.finish_reason === "length";
}

async function q2() {
  console.log("===== [정답 2] 잘림 판정 =====");

  const model = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 20 });
  const res = await model.invoke("광합성을 아주 자세히, 단계별로 설명해줘.");

  console.log("text        :", res.text);
  console.log("stop_reason :", (res.response_metadata as Record<string, unknown>).stop_reason);
  console.log("잘렸는가?   :", isTruncated(res)); // → true

  // 대조군: maxTokens 를 넉넉히 주면 잘리지 않는다
  const roomy = new ChatAnthropic({ model: "claude-sonnet-4-6", maxTokens: 500 });
  const ok = await roomy.invoke("광합성을 한 문장으로 설명해줘.");
  console.log("넉넉할 때   :", isTruncated(ok)); // → false (stop_reason: "end_turn")
  console.log("");
}

/* ===== [정답 3] temperature: 0 이 결정적인가? ===== */
/**
 * 정답이 "하나로 정해져 있지 않은" 문제다.
 * new Set(texts).size 가 1 이 나와도 정상이고 3 이 나와도 정상이다.
 *
 * 중요한 건 숫자가 아니라 "1 이 보장되지 않는다"는 사실이다.
 *
 * 왜 그런가 (본문 2-3 함정 재확인):
 *   temperature: 0 이 약속하는 건 "매번 같은 출력"이 아니라
 *   "각 스텝에서 확률이 가장 높은 토큰을 고른다" 이다.
 *   그런데 그 확률값 자체가 매번 미세하게 다르다.
 *     - GPU 연산은 배치 크기·커널 스케줄링에 따라 덧셈 순서가 달라지고,
 *       부동소수점 덧셈은 결합법칙이 성립하지 않는다.
 *     - 확률 0.5001 vs 0.4999 인 상황에서 마지막 자리가 흔들리면 순위가 뒤집힌다.
 *     - 토큰 하나가 갈리면 그 뒤 문장 전체가 다른 길로 간다.
 *
 * 실무 교훈:
 *   - 테스트를 "정확한 문자열 일치"로 짜지 마라. 반드시 깨진다.
 *     구조(JSON 스키마 만족 여부), 포함 관계, LLM 채점(Step 19)으로 검증하라.
 *   - 진짜로 결정성이 필요하면 캐시를 써라 (2-9). 캐시만이 유일하게 보장한다.
 *   - 출력 형식이 흔들리는 게 문제라면 temperature 를 낮추는 것보다
 *     구조화된 출력(Step 05)이 훨씬 확실한 해법이다.
 *
 * 팁: 짧고 정답이 명확한 프롬프트("1+1은?")는 1위와 2위 확률 차이가 커서
 *    거의 항상 같은 답이 나온다. 반대로 자유 서술형은 갈릴 여지가 크다.
 *    아래에서 두 종류를 모두 돌려 비교한다.
 */
async function q3() {
  console.log("===== [정답 3] temperature: 0 의 비결정성 =====");

  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 150 });

  for (const prompt of [
    "1+1은? 숫자만 답해.", //                          1·2위 차이가 크다 → 거의 항상 같음
    "고양이에 대해 흥미로운 사실을 한 문장으로 말해줘.", // 자유 서술 → 갈릴 여지가 크다
  ]) {
    // batch 로 5번을 한 번에 보낸다. 순서는 보장되지만 여기선 상관없다.
    const results = await model.batch(Array(5).fill(prompt), { maxConcurrency: 5 });
    const texts = results.map((r) => r.text.trim());
    const unique = new Set(texts);

    console.log(`프롬프트: ${prompt}`);
    console.log(`  서로 다른 응답: ${unique.size} 종류 / 5회`);
    if (unique.size > 1) {
      console.log("  ⚠️ temperature: 0 인데 답이 갈렸다! 이것이 정상이다.");
      [...unique].forEach((t, i) => console.log(`    (${i + 1}) ${t.slice(0, 60)}`));
    } else {
      console.log("  이번엔 모두 같았다. 하지만 이것이 '보장'된 것은 아니다.");
    }
    console.log("");
  }
}

/* ===== [정답 4] batch 부분 실패 살려내기 — 이 파일의 하이라이트 ===== */
/**
 * 핵심 1: returnExceptions: true 를 넣으면 반환 타입이
 *        AIMessage[] 에서 (AIMessage | Error)[] 로 "바뀐다".
 *        그래서 instanceof Error 로 좁히지 않으면 .text 접근에서 컴파일 에러가 난다.
 *        타입 시스템이 에러 처리를 강제하는 좋은 예다.
 *
 * 핵심 2: maxConcurrency 와 returnExceptions 는 "서로 다른 인자"에 들어간다.
 *        2번째 = config       (모델 호출 하나하나에 적용될 설정)  → maxConcurrency
 *        3번째 = batchOptions (배치 자체의 동작)                → returnExceptions
 *
 *        batchOptions 에도 maxConcurrency 가 있지만 deprecated 다.
 *        @langchain/core 1.2.3 의 타입 정의:
 *          type RunnableBatchOptions = {
 *            /** @deprecated Pass in via the standard runnable config object instead * /
 *            maxConcurrency?: number;
 *            returnExceptions?: boolean;
 *          };
 *
 *        deprecated 자리에 넣어도 컴파일되고 실행된다.
 *        "돌아간다 ≠ 맞다" 의 사례다.
 */
async function q4() {
  console.log("===== [정답 4] batch 부분 실패 =====");

  const questions = ["고양이를 한 문장으로", "강아지를 한 문장으로", "앵무새를 한 문장으로", "여우를 한 문장으로"];

  /* --- (a) fail-fast: 실패 하나가 전체를 죽인다 --- */
  const broken = new ChatAnthropic({
    model: "this-model-does-not-exist", // 반드시 실패한다
    maxTokens: 50,
    maxRetries: 0, //                     실습이니 재시도로 시간 끌지 않는다
  });
  try {
    await broken.batch(questions);
    console.log("여기에 도달하면 안 된다");
  } catch (e) {
    console.log("(a) 전체가 죽었다:", (e as Error).message.slice(0, 70));
    console.log("    → catch 에 들어왔다는 것 자체가 '성공한 것들도 함께 사라졌다'는 증거다.");
    console.log("      돈은 다 냈는데 손에 남는 게 없다.");
  }
  console.log("");

  /* --- (b) returnExceptions: true 로 살려낸다 --- */

  // ❌ 틀린 버전 — maxConcurrency 를 3번째 인자(batchOptions)에 넣었다.
  //    컴파일도 되고 실행도 된다. 하지만 deprecated 경로다.
  //
  // const wrong = await model.batch(questions, {}, { maxConcurrency: 2, returnExceptions: true });

  // ✅ 올바른 버전
  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 60 });
  const results = await model.batch(
    questions,
    { maxConcurrency: 2 }, //      ← config: 동시에 2개까지만
    { returnExceptions: true }, // ← batchOptions: 실패해도 전체를 죽이지 않는다
  );
  //    ^? (AIMessage | Error)[]

  let ok = 0;
  let failed = 0;
  for (const [i, r] of results.entries()) {
    // instanceof Error 로 좁히지 않으면 아래 r.text 에서 컴파일 에러가 난다.
    if (r instanceof Error) {
      console.log(`(b) [${i}] 실패: ${r.message.slice(0, 50)}`); // 이 항목만 재시도하면 된다
      failed++;
    } else {
      console.log(`(b) [${i}] ${r.text.slice(0, 45)}...`);
      ok++;
    }
  }
  console.log(`    → 성공 ${ok} / 실패 ${failed}. 실패가 있어도 성공한 결과는 살아남는다.`);
  console.log("");
}

/* ===== [정답 5] 비용 계산 ===== */
/**
 * 핵심 한 줄:
 *   const plainInput = Math.max(0, usage.input_tokens - cacheRead - cacheWrite);
 *
 * 왜 빼는가:
 *   input_tokens 는 캐시 토큰을 "포함한" 총합이다.
 *   타입 정의 주석이 직접 말한다: "Sum of all input token types."
 *   빼지 않고 input_tokens * 단가 + cache_read * 캐시단가 로 쓰면
 *   캐시 토큰의 요금을 두 번 낸다.
 *
 *   캐시를 잘 쓰는 에이전트일수록(= cache_read 비중이 클수록) 오차가 커진다.
 *   캐시를 도입해 실제 비용은 절반으로 줄었는데 대시보드 숫자는 오히려 올라가는,
 *   아주 헷갈리는 상황이 만들어진다.
 *
 * 왜 Math.max(0, ...) 으로 감쌌는가:
 *   input_token_details 의 타입 주석: "Does *not* need to sum to full input token count."
 *   즉 provider 가 details 를 부정확하게 채울 수 있다는 걸 타입이 인정하고 있다.
 *   cacheRead + cacheWrite > input_tokens 인 상황이 오면 plainInput 이 음수가 되고,
 *   음수 토큰이 비용을 "깎아 버리면" 집계가 조용히 틀린다. 그래서 0 으로 막는다.
 *
 * 왜 ?. 와 ?? 0 이 필요한가:
 *   input_token_details 는 optional 이고 "Does *not* need to have all keys."
 *   res.usage_metadata.input_token_details.cache_read 를 무심코 쓰면 undefined 에서 터진다.
 */
type Pricing = {
  inputPerMTok: number;
  outputPerMTok: number;
  cacheReadPerMTok: number;
  cacheWritePerMTok: number;
};

const DEMO_PRICING: Pricing = {
  inputPerMTok: 3,
  outputPerMTok: 15,
  cacheReadPerMTok: 0.3,
  cacheWritePerMTok: 3.75,
};

function estimateCost(usage: UsageMetadata, p: Pricing): number {
  const cacheRead = usage.input_token_details?.cache_read ?? 0;
  const cacheWrite = usage.input_token_details?.cache_creation ?? 0;

  // ⚠️ 이중 계산 방어 — 이 함수의 심장.
  const plainInput = Math.max(0, usage.input_tokens - cacheRead - cacheWrite);

  return (
    (plainInput / 1_000_000) * p.inputPerMTok +
    (cacheRead / 1_000_000) * p.cacheReadPerMTok +
    (cacheWrite / 1_000_000) * p.cacheWritePerMTok +
    (usage.output_tokens / 1_000_000) * p.outputPerMTok
  );
}

async function q5() {
  console.log("===== [정답 5] 비용 계산 =====");

  const fake: UsageMetadata = {
    input_tokens: 10_000, // 이 안에 캐시 3000 + 캐시생성 2000 이 포함되어 있다
    output_tokens: 1_000,
    total_tokens: 11_000,
    input_token_details: { cache_read: 3_000, cache_creation: 2_000 },
  };
  console.log("가짜 usage 비용:", estimateCost(fake, DEMO_PRICING).toFixed(6), "(기대: 0.038400)");

  // 이중 계산하는 "틀린" 버전과 비교해 보자.
  const wrong =
    (fake.input_tokens / 1e6) * DEMO_PRICING.inputPerMTok + //              캐시분을 안 뺐다
    ((fake.input_token_details?.cache_read ?? 0) / 1e6) * DEMO_PRICING.cacheReadPerMTok +
    ((fake.input_token_details?.cache_creation ?? 0) / 1e6) * DEMO_PRICING.cacheWritePerMTok +
    (fake.output_tokens / 1e6) * DEMO_PRICING.outputPerMTok;
  console.log("이중 계산한 값 :", wrong.toFixed(6), "← 과다 계상. 캐시를 쓸수록 오차가 커진다.");

  // details 가 없어도 터지지 않아야 한다
  const noDetails: UsageMetadata = { input_tokens: 100, output_tokens: 50, total_tokens: 150 };
  console.log("details 없는 usage:", estimateCost(noDetails, DEMO_PRICING).toFixed(6));

  // 실제 호출로도 확인
  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 150 });
  const res = await model.invoke("광합성을 한 문장으로 설명해줘.");
  if (res.usage_metadata) {
    console.log("실제 usage      :", JSON.stringify(res.usage_metadata));
    console.log("실제 추정 비용  :", estimateCost(res.usage_metadata, DEMO_PRICING).toFixed(6));
  }
  console.log("");
}

/* ===== [정답 6] 스트리밍에서 토큰 수 얻기 ===== */
/**
 * 각 청크의 usage_metadata 는 대부분 undefined 다.
 * 토큰 수는 마지막 청크에 실려 온다.
 * 청크 하나만 보고 "usage 가 없네"라고 결론 내리면 안 된다.
 *
 * concat 으로 청크를 합치면 usage_metadata 가 병합되어 온전한 값이 나온다.
 * @langchain/core 에 mergeUsageMetadata 가 있는 이유가 이것이다.
 *
 * ⚠️ ChatAnthropic 은 streamUsage 기본값이 true 라서 그냥 된다.
 *    하지만 streamUsage: false 로 꺼두면 토큰 수를 아예 못 받는다
 *    — 비용 집계가 조용히 0 이 된다.
 */
async function q6() {
  console.log("===== [정답 6] 스트리밍 토큰 =====");

  const model = await initChatModel(MODEL_ID, { temperature: 0, maxTokens: 150 });

  let final: AIMessageChunk | undefined;
  let chunkCount = 0;
  let chunksWithUsage = 0;

  process.stdout.write("응답: ");
  for await (const chunk of await model.stream("광합성을 두 문장으로 설명해줘.")) {
    process.stdout.write(chunk.text);
    chunkCount++;
    if (chunk.usage_metadata !== undefined) chunksWithUsage++;

    // 첫 청크는 그대로, 이후는 concat 으로 누적한다.
    final = final === undefined ? chunk : final.concat(chunk);
  }
  console.log("");
  console.log("");
  console.log(`총 청크 수                : ${chunkCount}`);
  console.log(`usage_metadata 가 있는 청크: ${chunksWithUsage}  ← 전체가 아니다!`);
  console.log("합친 뒤 usage_metadata    :", JSON.stringify(final?.usage_metadata));
  console.log("→ 청크를 concat 해야 온전한 토큰 수를 얻는다.");
  console.log("");
}

/* ===== [정답 7] reasoning 모델 ===== */
/**
 * (a)(b)(c) 는 본문 2-6 그대로다. 핵심은 (d) 다.
 *
 * (d) maxTokens: 2100 + budget_tokens: 2000 의 결과는 stop_reason: "max_tokens" 다.
 *     생각에 2000 토큰을 쓰고 답변에 100 토큰밖에 안 남아 답을 못 끝낸 것이다.
 *     추론 토큰 값은 다 치르고 답은 못 받은 상태 — 최악의 조합이다.
 *
 * maxTokens 를 budget_tokens 대비 얼마나 여유 있게 잡아야 하나:
 *     maxTokens >= budget_tokens + (기대하는 답변 길이) + 여유분
 *   실무 기준으로는 budget_tokens 의 2배 이상을 권한다.
 *   budget_tokens: 2000 이면 maxTokens 는 4000 이상.
 *   추론이 budget 을 다 안 쓸 수도 있지만, 다 쓰는 경우에 대비해야 한다.
 *
 * 그 밖의 reasoning 함정:
 *   - reasoning 모델은 temperature 를 무시한다.
 *     @langchain/openai 의 reasoning JSDoc:
 *       "These options will be ignored when not using a reasoning model."
 *   - Anthropic 은 thinking 이 켜져 있으면 temperature 를 함께 쓸 수 없다.
 *   - thinking 이 켜진 채 withStructuredOutput() 을 쓰면 @langchain/anthropic 1.5.1 은
 *     콘솔 경고를 찍는다 (에러가 아니라 경고다!):
 *       "Anthropic structured output relies on forced tool calling, which is not
 *        supported when `thinking` is enabled. This method will raise
 *        OutputParserException if tool calls are not generated..."
 *     잘 돌아가다가 어느 날 갑자기 OutputParserException 이 터진다. Step 05 에서 다시 만난다.
 */
async function q7() {
  console.log("===== [정답 7] reasoning 모델 =====");

  /* --- (a)(b)(c) 넉넉한 maxTokens --- */
  const model = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 4000, //                                 budget_tokens 의 2배. 여유 있게.
    thinking: { type: "enabled", budget_tokens: 2000 },
  });
  const res = await model.invoke("17 * 24 를 암산하듯 단계별로 계산해줘.");

  // (a) 추론 블록 뽑기 — provider 가 달라도 contentBlocks 로 표준화된다.
  const reasoning = res.contentBlocks.filter((b) => b.type === "reasoning");
  console.log("(a) 추론 블록 수:", reasoning.length);
  console.log(
    "    추론 내용   :",
    reasoning
      .map((b) => (b as { reasoning?: string }).reasoning ?? "")
      .join("\n")
      .slice(0, 200) + "...",
  );

  // (b) 추론 토큰 수
  const u = res.usage_metadata;
  const reasoningTokens = u?.output_token_details?.reasoning ?? 0;
  console.log("(b) 출력 토큰 합계:", u?.output_tokens ?? 0);
  console.log("    그중 추론     :", reasoningTokens, "← 화면에 못 쓰는데 요금은 냄");
  console.log("    실제 답변     :", (u?.output_tokens ?? 0) - reasoningTokens);

  // (c) res.text 에는 추론이 섞이지 않는다 — 최종 답변만 들어 있다.
  console.log("(c) res.text:", res.text.slice(0, 100));
  console.log(
    "    추론 내용이 text 에 들어 있는가?:",
    reasoning.length > 0 &&
      res.text.includes(((reasoning[0] as { reasoning?: string }).reasoning ?? "").slice(0, 30)),
    "← false 여야 한다",
  );
  console.log("");

  /* --- (d) maxTokens 를 조여 보면 --- */
  const tight = new ChatAnthropic({
    model: "claude-sonnet-4-6",
    maxTokens: 2100, //                                 budget_tokens 보다 겨우 100 크다
    thinking: { type: "enabled", budget_tokens: 2000 },
  });
  const squeezed = await tight.invoke("17 * 24 를 암산하듯 단계별로 계산해줘.");
  const sm = squeezed.response_metadata as Record<string, unknown>;

  console.log("(d) maxTokens: 2100 / budget_tokens: 2000");
  console.log("    stop_reason:", sm.stop_reason, '← "max_tokens" 일 것이다');
  console.log("    추론 토큰  :", squeezed.usage_metadata?.output_token_details?.reasoning ?? 0);
  console.log("    최종 텍스트:", JSON.stringify(squeezed.text.slice(0, 80)));
  console.log("    → 생각에 2000 토큰을 쓰고 답변에 100 토큰밖에 안 남았다.");
  console.log("      추론 토큰 값은 다 치르고 답은 못 받았다. 최악의 조합이다.");
  console.log("      maxTokens 는 budget_tokens 의 2배 이상으로 잡아라.");
  console.log("");
}

/* ===== [정답 8] provider 를 모르는 함수 ===== */
/**
 * askAll 안에 "anthropic" 이나 "openai" 라는 문자열이 하나도 없다.
 * batch, usage_metadata.input_tokens, text — 전부 표준 인터페이스다.
 * 이것이 2-1 에서 말한 추상화의 실물이다.
 *
 * ⚠️ 다만 교체되는 건 인터페이스지 동작이 아니다.
 *    코드가 그대로 돈다고 결과가 같지는 않다:
 *      - 프롬프트 민감도: Claude 에 맞춰 튜닝한 프롬프트는 GPT 에서 최적이 아니다
 *      - temperature 범위: Anthropic 0~1, OpenAI 0~2
 *      - 무시되는 파라미터: topK 를 넣어 뒀다면 OpenAI 로 가는 순간 사라진다
 *      - response_metadata 필드명: stop_reason → finish_reason 으로 바뀐다
 *        (그래서 [정답 2] 의 isTruncated 가 둘 다 봐야 했던 것이다)
 *    provider 교체는 "코드 수정 비용"을 0 으로 만들 뿐 "검증 비용"은 그대로다.
 *    바꿨으면 반드시 평가를 다시 돌려라 (Step 19).
 */
async function askAll(
  model: Awaited<ReturnType<typeof initChatModel>>,
  questions: string[],
  label: string,
) {
  const results = await model.batch(
    questions,
    { maxConcurrency: 2 }, //      config
    { returnExceptions: true }, // batchOptions
  );

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

async function q8() {
  console.log("===== [정답 8] provider 교체 =====");

  const questions = ["광합성을 한 문장으로", "중력을 한 문장으로"];

  // provider 를 문자열로만 바꾼다. askAll 은 한 글자도 안 바뀐다.
  await askAll(
    await initChatModel("anthropic:claude-sonnet-4-6", { temperature: 0, maxTokens: 200 }),
    questions,
    "anthropic:claude-sonnet-4-6 (큰 모델)",
  );

  if (process.env.OPENAI_API_KEY) {
    // OPENAI_API_KEY 와 @langchain/openai 가 모두 있어야 한다.
    // provider 를 바꾼다고 API 키가 자동으로 생기지는 않는다.
    await askAll(
      await initChatModel("openai:gpt-5.5", { temperature: 0, maxTokens: 200 }),
      questions,
      "openai:gpt-5.5",
    );
  } else {
    // 대체안: 같은 provider 의 작은 모델과 비교한다. askAll 은 여전히 그대로다.
    console.log("(OPENAI_API_KEY 가 없어 Anthropic 의 작은 모델로 대체합니다)\n");
    await askAll(
      await initChatModel("anthropic:claude-haiku-4-5", { temperature: 0, maxTokens: 200 }),
      questions,
      "anthropic:claude-haiku-4-5 (작은 모델)",
    );
  }

  console.log("→ askAll 안에는 'anthropic' 도 'openai' 도 없다. 표준 인터페이스만 쓴다.");
}

/* ===== 실행 ===== */

async function main() {
  await q1();
  await q2();
  await q3();
  await q4();
  await q5();
  await q6();
  await q7();
  await q8();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
