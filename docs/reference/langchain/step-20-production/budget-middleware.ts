/**
 * Step 20 — 비용 상한 미들웨어 (cost cap middleware)
 * 실행: 이 파일은 단독 실행용이 아니라 production-agent.ts 에서 import 합니다.
 *
 * 왜 미들웨어인가:
 *   비용 상한을 도구나 프롬프트에 넣으면 모델이 "지켜 주기를" 바라는 것이 됩니다.
 *   모델은 지시를 어깁니다. 상한은 모델이 볼 수 없는 곳 — 즉 모델 호출을 감싸는
 *   코드에서 강제해야 합니다. 그게 wrapModelCall 입니다.
 *
 * 왜 커스텀인가:
 *   내장 modelCallLimitMiddleware 는 "호출 횟수"를 셉니다. 하지만 돈은 횟수가 아니라
 *   토큰에 비례합니다. 1만 토큰 호출 1번과 100 토큰 호출 20번은 횟수로는 20배 차이지만
 *   비용은 반대입니다. 그래서 토큰 기준 상한은 직접 만들어야 합니다.
 */

import { createMiddleware, AIMessage } from "langchain";
import type { BaseMessage, UsageMetadata } from "@langchain/core/messages";

/* ===== 가격표 ===== */

/**
 * 100만 토큰당 USD 단가.
 *
 * ⚠️ 이 숫자는 예시입니다. 가격은 바뀌고, 제공자마다 다릅니다.
 * 실제로 쓸 때는 제공자 가격 페이지를 보고 직접 채우세요.
 * 여기서 중요한 건 숫자 자체가 아니라 "입력과 출력 단가가 다르다"는 구조입니다.
 * 출력이 입력보다 보통 3~5배 비쌉니다.
 */
export interface ModelPrice {
  /** 입력 100만 토큰당 USD */
  inputPerMillion: number;
  /** 출력 100만 토큰당 USD */
  outputPerMillion: number;
  /** 캐시 읽기 100만 토큰당 USD (프롬프트 캐싱 지원 모델만) */
  cacheReadPerMillion?: number;
}

export const PRICES: Record<string, ModelPrice> = {
  "claude-sonnet-4-6": { inputPerMillion: 3.0, outputPerMillion: 15.0, cacheReadPerMillion: 0.3 },
  "claude-haiku-4-5": { inputPerMillion: 1.0, outputPerMillion: 5.0, cacheReadPerMillion: 0.1 },
  "gpt-5.5": { inputPerMillion: 2.5, outputPerMillion: 10.0, cacheReadPerMillion: 0.25 },
};

/* ===== 토큰 → 돈 ===== */

/**
 * usage_metadata 한 건을 USD 로 환산합니다.
 *
 * 핵심은 input_token_details.cache_read 를 따로 빼는 것입니다.
 * 캐시에서 읽은 토큰은 input_tokens 에 **포함되어** 있으면서 단가는 1/10 입니다.
 * 이걸 안 빼면 캐싱을 켜 놓고도 비용이 그대로인 것처럼 계산됩니다.
 */
export function usageToUsd(usage: UsageMetadata, price: ModelPrice): number {
  const cacheRead = readCacheTokens(usage);

  // input_tokens 는 캐시 읽기를 포함한 총량입니다. 캐시분을 빼야 "정가로 낸 입력"이 남습니다.
  const fullPriceInput = Math.max(0, usage.input_tokens - cacheRead);

  const inputCost = (fullPriceInput / 1_000_000) * price.inputPerMillion;
  const cacheCost = (cacheRead / 1_000_000) * (price.cacheReadPerMillion ?? price.inputPerMillion);
  const outputCost = (usage.output_tokens / 1_000_000) * price.outputPerMillion;

  return inputCost + cacheCost + outputCost;
}

/**
 * input_token_details 는 optional 이고 제공자마다 키가 다릅니다.
 * 없으면 0 으로 취급합니다 — 없다고 터지면 안 됩니다.
 */
function readCacheTokens(usage: UsageMetadata): number {
  const details = usage.input_token_details;
  if (details === undefined) return 0;
  const v = (details as { cache_read?: unknown }).cache_read;
  return typeof v === "number" ? v : 0;
}

/**
 * 메시지 목록 전체에서 지금까지 쓴 돈을 계산합니다.
 *
 * 여기가 이 미들웨어의 트릭입니다. 커스텀 state 필드를 만들어 누적하는 대신,
 * 이미 state.messages 안에 있는 AIMessage 들의 usage_metadata 를 더합니다.
 *
 * 장점:
 *   - reducer 를 직접 정의할 필요가 없다
 *   - 체크포인터가 messages 를 저장하므로 비용 누적도 **공짜로 영속**된다
 *     (프로세스가 재시작해도 이미 쓴 돈을 기억한다)
 *   - 스레드를 재개해도 상한이 이어진다
 */
export function spentUsd(messages: BaseMessage[], price: ModelPrice): number {
  let total = 0;
  for (const m of messages) {
    const usage = (m as { usage_metadata?: UsageMetadata }).usage_metadata;
    if (usage !== undefined) total += usageToUsd(usage, price);
  }
  return total;
}

/* ===== 미들웨어 ===== */

export interface BudgetMiddlewareOptions {
  /** 스레드 하나가 쓸 수 있는 최대 USD */
  maxUsd: number;
  /** 비용 계산에 쓸 가격표 */
  price: ModelPrice;
  /** 상한 초과 시 사용자에게 보여줄 문구 */
  message?: string;
}

/**
 * 스레드당 누적 토큰 비용에 상한을 겁니다.
 *
 * 동작:
 *   모델을 부르기 **전에** 지금까지 쓴 돈을 계산하고, 상한을 넘었으면
 *   handler 를 아예 호출하지 않고 AIMessage 를 대신 돌려줍니다.
 *   → 모델 호출이 일어나지 않으므로 돈이 더 나가지 않습니다.
 *
 * wrapModelCall 의 반환 타입은 `AIMessage | Command` 입니다.
 * AIMessage 를 돌려주면 그게 모델이 답한 것처럼 취급되고, tool_calls 가 없으므로
 * 에이전트 루프는 거기서 자연스럽게 끝납니다.
 *
 * ⚠️ 이건 "사후" 상한이 아니라 "사전" 상한입니다. 즉 마지막 호출 **하나**는
 * 상한을 넘겨서 끝날 수 있습니다(호출 전엔 몰랐으니까). 정확히 $N 에서 끊고 싶다면
 * maxUsd 를 실제 예산보다 한 호출분만큼 낮게 잡으세요.
 */
export function budgetMiddleware(options: BudgetMiddlewareOptions) {
  const { maxUsd, price } = options;
  const message =
    options.message ??
    "이 대화의 사용 한도를 초과했습니다. 새 대화를 시작하거나 관리자에게 문의해 주세요.";

  return createMiddleware({
    name: "BudgetMiddleware",

    wrapModelCall: (request, handler) => {
      const spent = spentUsd(request.state.messages, price);

      if (spent >= maxUsd) {
        // 관측을 위해 남깁니다 — 상한에 걸린 사실이 로그에 없으면
        // "에이전트가 갑자기 이상한 소리를 한다"는 제보만 받게 됩니다.
        console.warn(
          `[budget] 상한 초과로 모델 호출을 건너뜁니다. spent=$${spent.toFixed(4)} max=$${maxUsd.toFixed(4)}`,
        );

        // handler 를 호출하지 않는 것이 핵심입니다. 여기서 return 하면 모델 API 를 안 탑니다.
        return new AIMessage({ content: message });
      }

      return handler(request);
    },
  });
}
