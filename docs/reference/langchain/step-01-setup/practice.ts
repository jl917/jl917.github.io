/**
 * Step 01 — 환경 구축과 첫 모델 호출
 * 실행: npx tsx docs/reference/langchain/step-01-setup/practice.ts
 */
import "dotenv/config";

import { createAgent, initChatModel } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessage } from "@langchain/core/messages";

import { printSection, printMessages, printUsage, printKV, printJson } from "../project/src/lib/print.js";

/* ===== [1-2] 실행 환경 확인 =====
 *
 * 모델을 부르기 전에, 지금 이 파일이 어떤 환경에서 돌고 있는지부터 봅니다.
 * LangChain v1 은 Node.js 22 이상을 요구합니다.
 */
printSection("[1-2] 실행 환경 확인");

const major = Number(process.versions.node.split(".")[0]);
printKV({
  "Node.js": process.versions.node,
  "모듈 형식": typeof require === "undefined" ? "ESM (정상)" : "CommonJS (tsconfig/package.json 확인 필요)",
  "22 이상?": major >= 22 ? "예" : `아니오 — ${major} 입니다. 업그레이드하세요`,
});

/* ===== [1-3] 패키지 지형도 =====
 *
 * 다섯 개 패키지가 각각 무엇을 주는지 import 로 직접 확인합니다.
 * 여기서 중요한 건 "무엇을 어디서 가져오는가"입니다.
 *
 *   langchain          → createAgent, initChatModel, tool   (조립 도구)
 *   @langchain/core    → AIMessage 등 메시지 타입           (공용 언어)
 *   @langchain/anthropic → ChatAnthropic                    (제공자 어댑터)
 *
 * @langchain/langgraph 는 Step 10(메모리)부터 직접 씁니다.
 * 지금은 createAgent 밑에서 조용히 돌아가고 있습니다.
 */
printSection("[1-3] 패키지 지형도");

printKV({
  "langchain → createAgent": typeof createAgent,
  "langchain → initChatModel": typeof initChatModel,
  "@langchain/anthropic → ChatAnthropic": typeof ChatAnthropic,
  "@langchain/core → AIMessage": typeof AIMessage,
});

/* ===== [1-4] API 키 확인 =====
 *
 * 키를 통째로 출력하면 그게 터미널 스크롤백에, CI 로그에, 스크린샷에 남습니다.
 * 확인할 때는 항상 마스킹하세요. "있는지"와 "형식이 맞는지"만 보면 충분합니다.
 */
printSection("[1-4] API 키 확인");

const apiKey = process.env["ANTHROPIC_API_KEY"];

if (apiKey === undefined || apiKey.trim() === "") {
  console.error("ANTHROPIC_API_KEY 가 없습니다.");
  console.error("  1. project/.env 파일이 있습니까?  (cp .env.example .env)");
  console.error("  2. 이 파일 맨 위에 import \"dotenv/config\"; 가 있습니까?  ← 가장 흔한 원인");
  process.exit(1);
}

printKV({
  "키 존재": "예",
  "마스킹된 값": `${apiKey.slice(0, 14)}...${apiKey.slice(-4)}`,
  "길이": `${apiKey.length}자`,
  // 키 양끝에 공백이나 따옴표가 붙는 사고가 잦습니다. .env 에 KEY="sk-..." 라고
  // 따옴표째 적으면 dotenv 가 따옴표는 벗겨주지만, 뒤에 붙은 공백은 남습니다.
  "앞뒤 공백": apiKey !== apiKey.trim() ? "있음 — .env 를 확인하세요!" : "없음",
});

/* ===== [1-5] 첫 모델 호출 =====
 *
 * 모델을 지정하는 방법은 두 가지입니다. 결과는 같고, 쓰임새가 다릅니다.
 */
printSection("[1-5] 첫 모델 호출 — 방법 A: 문자열");

// (A) "provider:model" 문자열. initChatModel 이 접두사를 보고
//     알맞은 제공자 패키지를 동적으로 불러옵니다. await 가 필요합니다.
const modelFromString = await initChatModel("anthropic:claude-sonnet-4-6");
const answerA = await modelFromString.invoke("LangChain을 한 문장으로 설명해줘.");

printMessages(answerA);

printSection("[1-5] 첫 모델 호출 — 방법 B: 모델 인스턴스");

// (B) 제공자 클래스를 직접 생성. 그 제공자에만 있는 옵션까지 타입 지원을 받습니다.
//     apiKey 를 안 주면 ANTHROPIC_API_KEY 환경변수를 자동으로 읽습니다.
const modelFromClass = new ChatAnthropic({
  model: "claude-sonnet-4-6",
  temperature: 0,
  maxTokens: 1024,
});
const answerB = await modelFromClass.invoke("LangChain을 한 문장으로 설명해줘.");

printMessages(answerB);

// OpenAI 로 바꾸려면 이 두 줄이면 됩니다. 아래 코드는 하나도 안 바뀝니다.
//   import { ChatOpenAI } from "@langchain/openai";
//   const model = new ChatOpenAI({ model: "gpt-5.5" });
// 또는 문자열만 갈아끼우기:
//   const model = await initChatModel("openai:gpt-5.5");

/* ===== [1-6] 응답 객체 해부 =====
 *
 * invoke() 가 돌려주는 건 문자열이 아니라 AIMessage 객체입니다.
 * 문자열인 줄 알고 answer.length 를 세거나 answer.toUpperCase() 를 부르면
 * 엉뚱한 결과가 나옵니다.
 */
printSection("[1-6] 응답 객체 해부");

printKV({
  "typeof": typeof answerA,
  "생성자": answerA.constructor.name,
  ".id": answerA.id ?? "(없음)",
  ".text 타입": typeof answerA.text,
  ".content 타입": Array.isArray(answerA.content) ? "배열 (콘텐츠 블록)" : "문자열",
});

console.log("\n▸ .text — 텍스트만 뽑는 가장 안전한 방법");
console.log(answerA.text);

console.log("\n▸ .contentBlocks — 표준화된 콘텐츠 블록 (Step 03 에서 자세히)");
printJson("", answerA.contentBlocks);

console.log("\n▸ usage_metadata — 토큰 사용량 (= 돈)");
printUsage(answerA);

console.log("\n▸ response_metadata — 제공자 원본 응답. 모양이 제공자마다 다릅니다");
printJson("", answerA.response_metadata);

/* ===== [1-7] 첫 에이전트 맛보기 =====
 *
 * 모델은 "물으면 답한다"가 전부입니다. 에이전트는 여기에 루프와 도구가 붙습니다.
 * 아직 도구를 안 줬으니 이 에이전트는 한 번 답하고 끝납니다 —
 * 그래도 결과의 모양이 model.invoke() 와 어떻게 다른지 봐 두세요.
 *
 * 자세한 건 Step 08 에서 다룹니다.
 */
printSection("[1-7] 첫 에이전트 맛보기");

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [], // 도구는 Step 06 에서 만듭니다
  systemPrompt: "너는 간결하게 답하는 한국어 조수다. 두 문장을 넘기지 마라.",
});

// 모델은 문자열을 받았지만, 에이전트는 { messages: [...] } 를 받고
// { messages: [...] } 를 돌려줍니다. 대화 전체가 오간다는 뜻입니다.
const agentResult = await agent.invoke({
  messages: [{ role: "user", content: "에이전트와 그냥 모델 호출의 차이가 뭐야?" }],
});

console.log(`돌아온 메시지 수: ${agentResult.messages.length}개 (내가 보낸 것 + 모델이 답한 것)\n`);
printMessages(agentResult.messages);

/* ===== [1-8] 문제 해결 — provider 접두사 오타 =====
 *
 * 이 코스에서 가장 자주 보게 될 함정입니다.
 * "anthropi:..." 처럼 접두사를 틀려도 TypeScript 는 아무 말도 안 합니다.
 * model 파라미터의 타입이 그냥 string 이기 때문입니다.
 * 에러는 코드를 짤 때가 아니라, 실행해서 그 줄에 도달했을 때 터집니다.
 */
printSection("[1-8] 문제 해결 — provider 접두사 오타는 런타임에 터진다");

try {
  // 오타: "anthropic" 이 아니라 "anthropi" — 컴파일은 멀쩡히 통과합니다.
  await initChatModel("anthropi:claude-sonnet-4-6");
  console.log("여기는 실행되지 않습니다.");
} catch (error) {
  console.log("잡힌 에러 타입:", (error as Error).constructor.name);
  console.log("메시지:", (error as Error).message.split("\n")[0]);
  console.log("\n→ 오타 하나가 런타임 에러입니다. 모델 문자열은 상수로 빼서 한곳에서 관리하세요.");
}

printSection("Step 01 완료");
console.log("다음 → Step 02 에서 temperature, maxTokens 같은 파라미터를 다룹니다.\n");
