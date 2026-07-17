/**
 * Step 03 — 메시지와 콘텐츠 블록 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-03-messages/exercise.ts
 *
 * 각 [문제 N] 블록 아래에 코드를 직접 채워 넣고 실행해 보세요.
 * 그대로 실행하면 아무것도 출력되지 않습니다. 정상입니다.
 *
 * 대부분의 문제는 API 키 없이 풀 수 있습니다.
 * 문제 1, 2, 5 는 ANTHROPIC_API_KEY 가 있으면 더 재미있습니다.
 */
import "dotenv/config";
import {
  SystemMessage,
  HumanMessage,
  AIMessage,
  ToolMessage,
  trimMessages,
  initChatModel,
} from "langchain";
import type { BaseMessage } from "@langchain/core/messages";
import { isAIMessage } from "@langchain/core/messages";

/* ===== [문제 1] 두 가지 표기법 =====
 * SystemMessage("너는 항상 한 문장으로만 답한다") + HumanMessage("LangChain이 뭐야?")
 * 배열로 모델을 호출하고, 응답의 .text 와 .type 을 출력하세요.
 * 그다음 같은 대화를 "객체 리터럴" 표기법으로 다시 작성해 동일하게 동작함을 확인하세요.
 *
 * 힌트: const model = await initChatModel("anthropic:claude-sonnet-4-6");
 */

// 여기에 작성하세요

/* ===== [문제 2] 메시지가 곧 상태다 =====
 * 2턴 대화를 만드세요.
 *   1턴: 좋아하는 음식을 말한다
 *   2턴: "내가 좋아하는 음식이 뭐라고?" 를 묻는다  → 모델이 맞히는지 확인
 * 그런 다음 1턴을 배열에서 빼고 다시 호출해 답이 어떻게 달라지는지 비교하세요.
 *
 * 힌트: 2턴 배열에는 1턴 질문 + 1턴 답변(role: "assistant") + 2턴 질문이 모두 들어가야 합니다.
 */

// 여기에 작성하세요

/* ===== [문제 3] content 접근자 3종 비교 =====
 * (a) content 가 문자열인 메시지
 * (b) content 가 배열인 메시지 (text 블록 2개 + reasoning 블록 1개)
 * 를 각각 만들고, 둘의 .content / .text / .contentBlocks 를 모두 출력해 비교하세요.
 *
 * 질문: .text 에 reasoning 이 포함되나요? 답을 주석으로 남기세요.
 */

// 여기에 작성하세요

// → .text 에 reasoning 이 포함되나요? (여기에 답을 쓰세요)

/* ===== [문제 4] 안전한 describe 함수 =====
 * 임의의 BaseMessage 를 받아 아래를 반환하는 describe(msg) 를 작성하세요.
 *   { type: string, text: string, blockTypes: string[], hasToolCalls: boolean }
 *
 * 조건: content 가 문자열이든 배열이든 절대 터지면 안 됩니다.
 * 힌트: .text 와 .contentBlocks 만 쓰면 분기가 아예 필요 없습니다.
 *
 * 아래 4개 메시지로 모두 테스트해 보세요.
 */
type Described = { type: string; text: string; blockTypes: string[]; hasToolCalls: boolean };

function describe(msg: BaseMessage): Described {
  // 여기에 작성하세요
  throw new Error("구현하세요");
}

const testMsgs: BaseMessage[] = [
  new SystemMessage("너는 도우미다"),
  new HumanMessage("안녕"),
  new HumanMessage({
    content: [
      { type: "text", text: "이 이미지 뭐야?" },
      { type: "image", url: "https://example.com/a.png" },
    ],
  }),
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
  }),
];

// for (const m of testMsgs) console.log(describe(m));

/* ===== [문제 5] tool_call_id 불일치 =====
 * 아래 배열의 ToolMessage tool_call_id 를 "call_1" -> "call_999" 로 일부러 틀리게 만든 뒤
 * model.invoke 를 호출하세요. 어떤 에러가 나는지 에러 메시지 전문을 주석으로 기록하세요.
 *
 * API 키가 없다면: 대신 "왜 LangChain 단계에서는 에러가 안 나는가" 를 설명하는 주석을 쓰세요.
 * (사실 이쪽이 더 중요한 학습 포인트입니다)
 */
const mismatched: BaseMessage[] = [
  new HumanMessage("서울 날씨?"),
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
  }),
  // 여기에 tool_call_id 가 "call_999" 인 ToolMessage 를 추가하세요
];

// 여기에 invoke 를 시도하고 에러를 잡는 코드를 작성하세요

// → 에러 메시지 전문 (또는 LangChain 단계에서 조용한 이유):

/* ===== [문제 6] 도구 3개 중 1개 실패 =====
 * AIMessage 가 도구 3개(call_1, call_2, call_3)를 호출했고 그중 call_2 가 실패하는 상황을 만드세요.
 * 세 개 모두 ToolMessage 로 답하되 call_2 는 status: "error" 로 처리하고,
 * 최종 배열의 tool 메시지 개수가 3인지 검증하세요.
 *
 * 힌트: catch 안에서 continue 하면 안 됩니다. 그게 바로 이 문제의 함정입니다.
 */
const runTool = (name: string, args: Record<string, unknown>): string => {
  if (args.city === "없는도시") throw new Error(`알 수 없는 도시: ${args.city}`);
  return `${args.city} 날씨: 맑음`;
};

const aiThreeCalls = new AIMessage({
  content: "",
  tool_calls: [
    { name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" },
    { name: "get_weather", args: { city: "없는도시" }, id: "call_2", type: "tool_call" },
    { name: "get_weather", args: { city: "부산" }, id: "call_3", type: "tool_call" },
  ],
});

// 여기에 작성하세요

/* ===== [문제 7] 트리밍 옵션 비교 =====
 * 아래 대화(SystemMessage 1개 + human/ai 5쌍 = 총 11개)를 tokenCounter: (ms) => ms.length 로
 * maxTokens: 5 트리밍하세요.
 *   (a) 옵션 없이
 *   (b) includeSystem: true, startOn: "human" 으로
 * 두 결과의 "첫 메시지 타입" 이 어떻게 다른가요?
 */
const longConvo: BaseMessage[] = [
  new SystemMessage("너는 간결한 도우미다"),
  new HumanMessage("1번 질문"), new AIMessage("1번 답변"),
  new HumanMessage("2번 질문"), new AIMessage("2번 답변"),
  new HumanMessage("3번 질문"), new AIMessage("3번 답변"),
  new HumanMessage("4번 질문"), new AIMessage("4번 답변"),
  new HumanMessage("5번 질문"), new AIMessage("5번 답변"),
];

// 여기에 작성하세요

// → 두 결과의 첫 메시지 타입 차이:

/* ===== [문제 8] 고아 ToolMessage 재현 =====
 * 아래 도구 호출 쌍이 포함된 대화에 트리밍을 걸어 고아 ToolMessage 를 일부러 만들어 보세요.
 * 그다음 startOn: "human" 을 추가해 문제가 사라지는 것을 확인하고,
 * 결과 배열의 첫 메시지 타입을 각각 출력하세요.
 *
 * 생각해볼 것: 내 코드 어디에도 tool_call_id 를 손으로 적은 적이 없는데
 *              왜 문제 5 와 똑같은 400 이 나게 되는 걸까요?
 */
const toolConvo: BaseMessage[] = [
  new SystemMessage("너는 도우미다"),
  new HumanMessage("서울 날씨?"),
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
  }),
  new ToolMessage({ content: "맑음, 27도", tool_call_id: "call_1" }),
  new AIMessage("서울은 맑고 27도입니다"),
];

// 여기에 작성하세요

// → 왜 내가 tool_call_id 를 적지 않았는데도 400 이 나는가?
