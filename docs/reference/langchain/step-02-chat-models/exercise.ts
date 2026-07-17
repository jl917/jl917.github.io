/**
 * Step 02 — 챗 모델과 파라미터 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-02-chat-models/exercise.ts
 *
 * 사용법:
 *   1. 아래 [문제 N] 블록의 TODO 를 채운다.
 *   2. main() 하단에서 해당 문제의 주석을 풀고 실행한다.
 *      → 8개를 한꺼번에 돌리면 API 호출이 20번 넘게 나가고
 *        어느 출력이 어느 문제 것인지 알 수 없게 된다. 한 문제씩 풀 것.
 *   3. 다 풀었으면 solution.ts 로 채점한다.
 *
 * 주의: 실제 API 를 호출하므로 요금이 발생합니다.
 */
import "dotenv/config";

import { initChatModel } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import type { AIMessage, UsageMetadata } from "@langchain/core/messages";

const MODEL_ID = "anthropic:claude-sonnet-4-6";

/* ===== [문제 1] await 를 빼면 무슨 일이 일어나나 ===== */
/**
 * initChatModel 로 anthropic:claude-sonnet-4-6 모델을 만들되 await 를 빼고
 * 호출해 보세요. 어떤 에러가 나나요? 주석으로 적고, 고치세요.
 *
 * 힌트: initChatModel 이 왜 Promise 를 돌려주는지 생각해 보세요.
 *      new ChatAnthropic() 은 왜 await 가 필요 없을까요?
 */
async function q1() {
  console.log("===== [문제 1] await 누락 =====");

  // TODO: await 없이 initChatModel 을 부르고 invoke 를 시도하세요.
  //       try/catch 로 감싸서 에러 메시지를 출력하세요.

  // → 에러 메시지를 여기에 적으세요:
  //   (답:                                                          )

  // TODO: 위 코드를 올바르게 고치세요.
}

/* ===== [문제 2] 잘림 판정 함수 ===== */
/**
 * maxTokens: 20 을 주고 "광합성을 자세히 설명해줘"라고 물어보세요.
 * res.text 와 res.response_metadata.stop_reason 을 함께 출력해,
 * 응답이 잘렸는지 판정하는 함수 isTruncated(res): boolean 을 완성하세요.
 *
 * 조건: Anthropic 과 OpenAI 양쪽에서 동작해야 합니다.
 *      stop_reason 하나만 보면 절반짜리 답입니다.
 */
function isTruncated(res: AIMessage): boolean {
  const meta = res.response_metadata as Record<string, unknown>;

  // 힌트: 먼저 이 줄로 response_metadata 에 실제로 어떤 키가 들어 있는지 보세요.
  console.log("  response_metadata:", JSON.stringify(meta, null, 2));

  // TODO: Anthropic 과 OpenAI 양쪽에서 동작하는 잘림 판정을 구현하세요.
  return false;
}

async function q2() {
  console.log("===== [문제 2] 잘림 판정 =====");

  // TODO: maxTokens: 20 인 모델을 만들어 긴 답을 요구하고,
  //       text 와 isTruncated() 결과를 출력하세요.
}

/* ===== [문제 3] temperature: 0 이 결정적인가? ===== */
/**
 * 같은 프롬프트를 temperature: 0 으로 5번 호출해 응답을 모으세요.
 * 서로 다른 응답이 몇 종류 나왔나요? new Set(texts).size 로 세어 보세요.
 *
 * 주의: 이 문제는 정답이 하나로 정해져 있지 않습니다.
 *      1 이 나와도 정상이고 3 이 나와도 정상입니다.
 *      중요한 건 숫자가 아니라 "1 이 보장되지 않는다"는 사실입니다.
 */
async function q3() {
  console.log("===== [문제 3] temperature: 0 의 비결정성 =====");

  // TODO: temperature: 0 인 모델로 같은 프롬프트를 5번 호출하고,
  //       new Set(texts).size 로 서로 다른 응답의 종류 수를 세세요.
  //       (힌트: batch 를 쓰면 5번을 한 번에 보낼 수 있습니다)

  // → 여러분의 결과를 여기에 적어 두고 나중에 다시 돌려 비교해 보세요:
  //   (1회차 결과:      종류 / 2회차 결과:      종류)
}

/* ===== [문제 4] batch 부분 실패 살려내기 ===== */
/**
 * 질문 4개를 batch 로 처리하되, 하나는 반드시 실패하도록 만드세요.
 * (힌트: 존재하지 않는 모델 이름을 쓴 모델 인스턴스를 섞으세요)
 *
 * (a) returnExceptions 없이 먼저 돌려 전체가 죽는 걸 확인하세요.
 * (b) returnExceptions: true 와 maxConcurrency: 2 를 올바른 인자 자리에 넣어
 *     성공한 것만 살려내세요.
 *
 * ⚠️ maxConcurrency 와 returnExceptions 는 서로 다른 인자에 들어갑니다.
 *    본문 2-4 의 함정 블록을 다시 보세요.
 */
async function q4() {
  console.log("===== [문제 4] batch 부분 실패 =====");

  // (a) fail-fast 확인
  // 아래는 일부러 프로그램을 죽이는 코드입니다.
  // catch 에 들어왔다는 것 자체가 "성공한 것들도 함께 사라졌다"는 증거입니다.
  const broken = new ChatAnthropic({
    model: "this-model-does-not-exist",
    maxTokens: 50,
    maxRetries: 0,
  });
  try {
    // TODO: broken.batch(...) 로 4개를 보내 전체가 죽는 것을 확인하세요.
  } catch (e) {
    console.log("(a) 전체가 죽었다:", (e as Error).message.slice(0, 80));
  }

  // (b) returnExceptions 로 살려내기
  // TODO: 정상 모델로 batch 를 호출하되
  //       - maxConcurrency: 2 를 올바른 인자 자리에
  //       - returnExceptions: true 를 올바른 인자 자리에
  //       넣고, instanceof Error 로 성공/실패를 갈라 출력하세요.
}

/* ===== [문제 5] 비용 계산 ===== */
/**
 * usage_metadata 를 받아 비용을 계산하는 estimateCost(usage, pricing) 를 구현하세요.
 *
 * 조건:
 *   - 캐시 토큰 이중 계산을 피해야 합니다.
 *     (input_tokens 는 캐시분을 "포함한" 총합입니다!)
 *   - input_token_details 가 아예 없는 경우에도 터지지 않아야 합니다.
 *
 * 힌트: input_token_details 는 optional 입니다. ?. 와 ?? 0 없이는
 *      컴파일이 안 될 겁니다 — 그 컴파일 에러가 곧 힌트입니다.
 */
type Pricing = {
  inputPerMTok: number; // 100만 토큰당 USD
  outputPerMTok: number;
  cacheReadPerMTok: number;
  cacheWritePerMTok: number;
};

// 실제 단가는 공식 가격 페이지에서 확인해 채우세요.
// 여기서는 계산이 맞는지 보기 위해 임의의 값을 씁니다.
const DEMO_PRICING: Pricing = {
  inputPerMTok: 3,
  outputPerMTok: 15,
  cacheReadPerMTok: 0.3,
  cacheWritePerMTok: 3.75,
};

function estimateCost(usage: UsageMetadata, p: Pricing): number {
  // TODO: 구현하세요.
  //   1. cache_read, cache_creation 을 안전하게 꺼낸다 (없을 수 있다)
  //   2. input_tokens 에서 캐시분을 빼서 "일반 입력 토큰"을 구한다  ← 핵심!
  //   3. 각 종류별로 단가를 곱해 더한다
  return 0;
}

async function q5() {
  console.log("===== [문제 5] 비용 계산 =====");

  // 검산용 가짜 usage — 이 값으로 먼저 손계산과 맞춰 보세요.
  const fake: UsageMetadata = {
    input_tokens: 10_000, // 이 안에 캐시 3000 + 캐시생성 2000 이 포함되어 있다
    output_tokens: 1_000,
    total_tokens: 11_000,
    input_token_details: { cache_read: 3_000, cache_creation: 2_000 },
  };
  // 기대값: 일반입력 5000 → 5000/1e6*3     = 0.015
  //        캐시읽기 3000 → 3000/1e6*0.3   = 0.0009
  //        캐시생성 2000 → 2000/1e6*3.75  = 0.0075
  //        출력    1000 → 1000/1e6*15     = 0.015
  //        합계                            = 0.0384
  console.log("가짜 usage 비용:", estimateCost(fake, DEMO_PRICING).toFixed(6), "(기대: 0.038400)");

  // details 가 아예 없는 경우에도 터지면 안 된다
  const noDetails: UsageMetadata = { input_tokens: 100, output_tokens: 50, total_tokens: 150 };
  console.log("details 없는 usage:", estimateCost(noDetails, DEMO_PRICING).toFixed(6));

  // TODO: 실제 모델을 호출해 진짜 usage_metadata 로도 계산해 보세요.
}

/* ===== [문제 6] 스트리밍에서 토큰 수 얻기 ===== */
/**
 * stream 으로 응답을 받으면서, 각 청크의 usage_metadata 를 출력해 보세요.
 * 대부분 undefined 인 것을 확인한 뒤, 청크를 concat 으로 합쳐
 * 최종 토큰 수를 얻으세요.
 */
async function q6() {
  console.log("===== [문제 6] 스트리밍 토큰 =====");

  // TODO:
  //   1. model.stream(...) 으로 청크를 순회하며 chunk.text 를 출력한다
  //   2. 각 청크의 chunk.usage_metadata 도 함께 출력해 대부분 undefined 임을 확인한다
  //   3. concat 으로 청크를 누적해 최종 usage_metadata 를 얻어 출력한다
}

/* ===== [문제 7] reasoning 모델 ===== */
/**
 * ChatAnthropic 에 thinking: { type: "enabled", budget_tokens: 2000 } 과
 * maxTokens: 4000 을 주고 "17 × 24 를 계산해줘"라고 물어보세요.
 *
 * (a) contentBlocks 에서 type: "reasoning" 블록을 뽑아 출력하세요.
 * (b) output_token_details.reasoning 토큰 수를 출력하세요.
 * (c) res.text 에 추론 내용이 포함되지 않는다는 걸 확인하세요.
 * (d) maxTokens 를 2100 으로 낮춰 다시 돌려 stop_reason 이 어떻게 되는지 보세요.
 *
 * ⚠️ (d) 를 빼먹지 마세요. budget_tokens 와 maxTokens 의 관계를
 *    몸으로 아는 유일한 방법입니다.
 */
async function q7() {
  console.log("===== [문제 7] reasoning 모델 =====");

  // TODO (a)(b)(c): maxTokens: 4000, budget_tokens: 2000 으로 호출하고
  //                 추론 블록 / 추론 토큰 수 / 최종 텍스트를 각각 출력하세요.

  // TODO (d): maxTokens 를 2100 으로 낮춰 다시 호출하고 stop_reason 을 출력하세요.
  //           무슨 일이 일어났나요?
  //   (답:                                                          )
}

/* ===== [문제 8] provider 를 모르는 함수 ===== */
/**
 * askAll(model, questions) 함수를 만들되 provider 를 전혀 모르게 작성하고,
 * anthropic:claude-sonnet-4-6 과 openai:gpt-5.5 양쪽으로 각각 호출해
 * 토큰 합계를 비교하세요.
 *
 * (OpenAI 키가 없다면 Anthropic 의 큰 모델과 작은 모델로 대체해도 됩니다.)
 *
 * 조건: askAll 안에 "anthropic" 이나 "openai" 라는 문자열이 등장하면 안 됩니다.
 */
async function askAll(
  model: Awaited<ReturnType<typeof initChatModel>>,
  questions: string[],
  label: string,
) {
  // TODO: batch 로 questions 를 처리하고, 각 응답의 text 와
  //       usage_metadata 의 input_tokens / output_tokens 를 누적해 합계를 출력하세요.
  //       returnExceptions 와 maxConcurrency 도 올바르게 넣으세요.
}

async function q8() {
  console.log("===== [문제 8] provider 교체 =====");

  const questions = ["광합성을 한 문장으로", "중력을 한 문장으로"];

  // TODO: initChatModel 로 두 provider(또는 두 크기의 모델)를 만들어
  //       각각 askAll 에 넘기고 토큰 합계를 비교하세요.
}

/* ===== 실행 ===== */

async function main() {
  // 한 문제씩 주석을 풀어 가며 푸세요.
  // await q1();
  // await q2();
  // await q3();
  // await q4();
  // await q5();
  // await q6();
  // await q7();
  // await q8();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
