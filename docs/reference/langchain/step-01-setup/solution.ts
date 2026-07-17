/**
 * Step 01 — 환경 구축과 첫 모델 호출 · 정답과 해설
 * 실행: cd docs/reference/langchain/project && npx tsx ../step-01-setup/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";

import { createAgent, initChatModel } from "langchain";
import { AIMessage } from "@langchain/core/messages";
import { ChatOpenAI } from "@langchain/openai";

import { printSection } from "../project/src/lib/print.js";

/* ===== [정답 1] dotenv 를 빼면 무슨 일이 일어나는가 =====
 *
 * (a) .env 는 그냥 텍스트 파일입니다. Node.js 는 이 파일의 존재를 모릅니다.
 *     `import "dotenv/config"` 가 실행되는 순간 dotenv 가 파일을 읽어
 *     process.env 에 채워 넣습니다. 그 import 가 없으면 파일은 그대로 있지만
 *     아무도 읽지 않으므로 process.env.ANTHROPIC_API_KEY 는 undefined 입니다.
 *
 * (b) 셸에 이미 export 되어 있으면 dotenv 없이도 읽힙니다.
 *     ~/.zshrc 에 export ANTHROPIC_API_KEY=... 를 해 둔 사람은 dotenv 를 빼도
 *     잘 돌아갑니다. 그래서 "내 컴퓨터에선 되는데 동료 컴퓨터에선 안 되는"
 *     전형적인 상황이 만들어집니다.
 *
 *     반대 방향도 알아 두세요: dotenv 는 기본적으로 "이미 있는" 환경변수를
 *     덮어쓰지 않습니다. 셸에 낡은 키가 export 되어 있으면 .env 를 아무리
 *     고쳐도 그 낡은 키가 계속 이깁니다. 이때는 `echo $ANTHROPIC_API_KEY` 로
 *     셸 쪽을 먼저 의심하세요.
 *
 *     그리고 dotenv 는 process.cwd() 기준으로 .env 를 찾습니다.
 *     그래서 이 코스는 항상 project/ 에서 실행합니다.
 */
printSection("[정답 1] dotenv");
console.log("키 존재 여부:", process.env["ANTHROPIC_API_KEY"] !== undefined);
console.log("dotenv 는 cwd 기준으로 .env 를 찾습니다. 지금 cwd:", process.cwd());

/* ===== [정답 2] 모델 문자열을 상수로 =====
 *
 * `as const` 를 붙이면 타입이 string 이 아니라 "anthropic:claude-sonnet-4-6"
 * 리터럴 타입이 됩니다. 그 자체로 오타를 막아주진 않지만(어차피 처음 적을 때
 * 틀리면 그 오타가 리터럴 타입이 됩니다), 아래 MODELS 처럼 허용 목록을
 * 만들어 두면 "목록에 없는 값"을 컴파일 타임에 잡을 수 있습니다.
 *
 * 이게 provider 접두사 오타에 대한 유일한 실질적 방어입니다 —
 * LangChain 의 model 파라미터 타입은 그냥 string 이라 프레임워크가
 * 대신 잡아줄 방법이 없습니다.
 */
const MODELS = {
  sonnet: "anthropic:claude-sonnet-4-6",
  gpt: "openai:gpt-5.5",
} as const;

type ModelId = (typeof MODELS)[keyof typeof MODELS];

const MODEL: ModelId = MODELS.sonnet;

// 아래 줄의 주석을 풀면 컴파일 에러가 납니다 — 이게 목적입니다.
// const BAD: ModelId = "anthropi:claude-sonnet-4-6";
//   Type '"anthropi:claude-sonnet-4-6"' is not assignable to type ModelId.

/* ===== [정답 3] 첫 모델 호출 =====
 *
 * initChatModel 은 제공자 패키지를 동적으로 import 하므로 Promise 를 돌려줍니다.
 * await 를 빠뜨리면 model 이 Promise 가 되고, model.invoke 는 존재하지 않으므로
 * "model.invoke is not a function" 이라는, 원인과 동떨어진 에러를 보게 됩니다.
 */
printSection("[정답 3] 첫 모델 호출");

const model = await initChatModel(MODEL);
const response = await model.invoke("너는 어떤 모델이야? 한 문장으로 답해.");

console.log(response.text);

/* ===== [정답 4] .text 와 .content 는 어떻게 다른가 =====
 *
 * (a) .text 를 쓰세요.
 *     .content 는 제공자와 응답 종류에 따라 string 일 수도, 블록 배열일 수도
 *     있습니다. 즉 타입이 `string | ContentBlock[]` 입니다. 그래서
 *     response.content.toUpperCase() 같은 코드는 "오늘은" 동작하다가
 *     추론(reasoning) 블록이나 인용(citation)이 끼는 순간 조용히 깨집니다.
 *     .text 는 어떤 경우든 텍스트 블록만 이어붙여 string 을 돌려줍니다.
 *
 * (b) 모델이 텍스트 말고 다른 걸 같이 보낼 때입니다.
 *     추론 블록, 인용, 도구 호출, 이미지 등. Anthropic 은 대체로 배열로 옵니다.
 *     자세한 건 Step 03 에서 다룹니다.
 */
printSection("[정답 4] .text vs .content vs .contentBlocks");

console.log("▸ .text (항상 string)");
console.log(response.text);

console.log("\n▸ .content 의 실제 타입:", Array.isArray(response.content) ? "배열" : "문자열");
console.log(JSON.stringify(response.content, null, 2));

console.log("\n▸ .contentBlocks (제공자와 무관하게 표준화된 모양)");
console.log(JSON.stringify(response.contentBlocks, null, 2));

/* ===== [정답 5] 토큰으로 비용 계산하기 =====
 *
 * 핵심은 usage_metadata 가 optional 이라는 것입니다.
 * strict 모드에서는 undefined 확인 없이 못 읽습니다 — 이건 tsc 의 잔소리가
 * 아니라 실제로 자주 없는 값입니다(스트리밍 청크, 일부 제공자).
 *
 * 비용 계산에서 흔한 실수: total_tokens 에 출력 단가를 곱하는 것.
 * 입력과 출력은 단가가 5배 차이납니다. 반드시 따로 곱하세요.
 */
printSection("[정답 5] 비용 계산");

const PRICE_PER_MTOK = { input: 3.0, output: 15.0 };

const usage = response.usage_metadata;
if (usage === undefined) {
  console.log("usage_metadata 가 없습니다 — 비용을 계산할 수 없습니다.");
} else {
  const cost =
    (usage.input_tokens / 1_000_000) * PRICE_PER_MTOK.input +
    (usage.output_tokens / 1_000_000) * PRICE_PER_MTOK.output;

  console.log(`입력  ${usage.input_tokens} 토큰`);
  console.log(`출력  ${usage.output_tokens} 토큰`);
  console.log(`합계  ${usage.total_tokens} 토큰`);
  console.log(`비용  $${cost.toFixed(6)}`);
  console.log(`\n이 호출을 100만 번 하면 $${(cost * 1_000_000).toFixed(2)} 입니다.`);
}

/* ===== [정답 6] 제공자 갈아끼우기 =====
 *
 * 바뀌는 줄: 1줄 (모델 문자열).
 * invoke, .text, .content, usage_metadata, contentBlocks — 전부 그대로입니다.
 *
 * 이게 1-1 에서 말한 "표준화"입니다. Anthropic SDK 와 OpenAI SDK 를 직접 쓰면
 * 응답 파싱 코드(content[0].text vs choices[0].message.content)와
 * 토큰 필드명(input_tokens vs prompt_tokens)이 전부 달라서
 * 제공자를 바꾸는 순간 애플리케이션 코드를 다시 씁니다.
 */
printSection("[정답 6] 제공자 갈아끼우기");

if (process.env["OPENAI_API_KEY"] === undefined) {
  console.log("OPENAI_API_KEY 가 없어 건너뜁니다. 바뀌는 코드는 아래 한 줄뿐입니다:");
  console.log('  const model = await initChatModel(MODELS.gpt);   // "openai:gpt-5.5"');
} else {
  const gpt = await initChatModel(MODELS.gpt);
  const gptResponse = await gpt.invoke("너는 어떤 모델이야? 한 문장으로 답해.");

  // 아래 세 줄은 Anthropic 일 때와 글자 하나 다르지 않습니다.
  console.log(gptResponse.text);
  console.log("토큰:", gptResponse.usage_metadata?.total_tokens ?? "(없음)");

  // 클래스를 직접 쓰는 방법도 동일합니다.
  const gptDirect = new ChatOpenAI({ model: "gpt-5.5" });
  console.log("클래스 방식도 같은 인터페이스:", typeof gptDirect.invoke);
}

/* ===== [정답 7] 코어 중복 확인 =====
 *
 * (a) 안전한 출력은 이렇게 생겼습니다 — 실제 버전이 한 번만 나오고
 *     나머지는 전부 deduped 입니다.
 *
 *       ├─┬ @langchain/anthropic@1.5.1
 *       │ └── @langchain/core@1.2.3 deduped
 *       ├─┬ @langchain/core@1.2.3
 *       └─┬ @langchain/langgraph@1.4.8
 *         └── @langchain/core@1.2.3 deduped
 *
 *     위험한 출력은 서로 다른 버전이 중첩되어 나옵니다.
 *     그러면 node_modules/@langchain/core 와
 *     node_modules/@langchain/anthropic/node_modules/@langchain/core 가
 *     동시에 존재하고, 서로 다른 AIMessage 클래스가 두 개 생깁니다.
 *
 * (c) false 가 나오면 코어 중복을 의심하세요.
 *     확인: npm ls @langchain/core
 *     해결: package.json 의 @langchain/* 버전을 서로 맞추고
 *           rm -rf node_modules package-lock.json && npm install
 *     또는 npm 의 overrides 로 코어 버전을 한 개로 고정합니다.
 *
 *     그리고 애초에 instanceof 에 의존하지 않는 코드를 쓰는 게 가장 안전합니다.
 *     project/src/lib/print.ts 가 instanceof 대신 "필드가 있는지"만 보는
 *     구조적 검사를 쓰는 이유입니다.
 */
printSection("[정답 7] 코어 중복 확인");

const isAIMessage = response instanceof AIMessage;
console.log("response instanceof AIMessage:", isAIMessage);

if (!isAIMessage) {
  console.log("⚠️  false 입니다! @langchain/core 가 두 벌 설치됐을 가능성이 큽니다.");
  console.log("   확인: npm ls @langchain/core");
} else {
  console.log("정상 — @langchain/core 가 한 벌입니다.");
}

// instanceof 에 의존하지 않는 대안: getType() 으로 역할을 봅니다.
// 이 방법은 코어가 몇 벌이든 항상 동작합니다.
console.log("instanceof 없이 확인하기 — getType():", response.getType()); // "ai"

/* ===== [정답 8] 첫 에이전트 =====
 *
 * (a) 2개입니다: human(내가 보낸 것), ai(모델이 답한 것).
 *     도구가 없으니 한 번 답하고 루프가 끝납니다.
 *     Step 06~08 에서 도구를 붙이면 여기에 ai(도구 호출) → tool(결과) →
 *     ai(최종 답) 이 끼어들어 4개, 6개로 늘어납니다.
 *
 * (b) 아니오. systemPrompt 는 매 모델 호출 때 앞에 붙지만
 *     result.messages 에는 남지 않습니다. 대화 기록이 아니라
 *     에이전트의 설정이기 때문입니다.
 *
 * (c) 모델: 문자열을 받아 AIMessage 하나를 돌려줍니다. 상태가 없습니다.
 *     에이전트: { messages: [...] } 를 받아 { messages: [...] } 를 돌려줍니다.
 *     대화 전체가 오갑니다. 그래서 여기에 checkpointer 를 붙이면
 *     (Step 10) 그대로 메모리가 됩니다.
 */
printSection("[정답 8] 첫 에이전트");

const agent = createAgent({
  model: MODEL,
  tools: [],
  systemPrompt: "너는 무조건 한 문장으로만 답한다.",
});

const result = await agent.invoke({
  messages: [{ role: "user", content: "TypeScript 를 왜 써?" }],
});

console.log(`메시지 ${result.messages.length}개\n`);
for (const m of result.messages) {
  console.log(`  ${m.getType().padEnd(6)} │ ${m.text}`);
}

console.log("\nsystem 메시지가 목록에 있습니까?");
console.log("  ", result.messages.some((m) => m.getType() === "system") ? "예" : "아니오 — 설정이지 대화 기록이 아닙니다");

printSection("Step 01 연습문제 완료");
