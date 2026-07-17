/**
 * Step 03 — 메시지와 콘텐츠 블록 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-03-messages/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 "뒤에" 열어보세요.
 * 각 정답 위 주석에 기대 출력이 적혀 있어 채점표로 바로 쓸 수 있습니다.
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

const hr = (t: string) => console.log(`\n${"=".repeat(70)}\n${t}\n${"=".repeat(70)}`);
const hasKey = Boolean(process.env.ANTHROPIC_API_KEY);
const counter = (ms: BaseMessage[]) => ms.length; // "메시지 1개 = 1토큰" 인 설명용 카운터

/* ===== [정답 1] 두 가지 표기법 =====
 * 핵심: 클래스와 리터럴은 완전히 동등하다. LangChain 이 리터럴을 내부에서 coerce 한다.
 * 응답의 .type 은 항상 "ai" 다 — "assistant" 가 아니라는 점에 주의.
 *
 * 기대 출력 (모델 응답이므로 텍스트는 매번 다릅니다):
 *   [클래스] .type = ai | .text = LangChain은 LLM 애플리케이션 개발 프레임워크입니다.
 *   [리터럴] .type = ai | .text = LangChain은 ...
 */
hr("[정답 1] 두 가지 표기법");

if (hasKey) {
  const model = await initChatModel("anthropic:claude-sonnet-4-6");

  // (A) 클래스 표기법
  const resA = await model.invoke([
    new SystemMessage("너는 항상 한 문장으로만 답한다"),
    new HumanMessage("LangChain이 뭐야?"),
  ]);
  console.log("[클래스] .type =", resA.type, "| .text =", resA.text);

  // (B) 객체 리터럴 표기법 — 완전히 동등하다
  const resB = await model.invoke([
    { role: "system", content: "너는 항상 한 문장으로만 답한다" },
    { role: "user", content: "LangChain이 뭐야?" },
  ]);
  console.log("[리터럴] .type =", resB.type, "| .text =", resB.text);

  // 응답은 어느 쪽이든 AIMessage 인스턴스다
  console.log("두 응답 모두 AIMessage:", isAIMessage(resA) && isAIMessage(resB));
} else {
  console.log("(ANTHROPIC_API_KEY 없음 — 건너뜁니다)");
}

/* ===== [정답 2] 메시지가 곧 상태다 =====
 * 핵심: 모델이 "기억"하는 게 아니라, 내가 이전 대화를 매번 다시 보내는 것이다.
 * 1턴을 빼면 모델은 답할 근거가 없다 — 까먹은 게 아니라 애초에 안 준 것.
 *
 * 기대 출력 (모델 응답이므로 매번 다릅니다):
 *   [전체 대화]  → "떡볶이라고 하셨습니다." 처럼 맞힌다
 *   [1턴 제거]   → "알려주신 적이 없습니다." 처럼 모른다고 답한다
 */
hr("[정답 2] 메시지가 곧 상태다");

if (hasKey) {
  const model = await initChatModel("anthropic:claude-sonnet-4-6");

  const turn1Q = { role: "user" as const, content: "내가 제일 좋아하는 음식은 떡볶이야." };
  const turn1A = await model.invoke([turn1Q]);

  // (A) 전체 대화를 보낸다 → 맞힌다
  const withHistory = await model.invoke([
    turn1Q,
    { role: "assistant", content: turn1A.text }, // ← 1턴 답변을 배열에 쌓는 것이 핵심
    { role: "user", content: "내가 좋아하는 음식이 뭐라고?" },
  ]);
  console.log("[전체 대화] :", withHistory.text);

  // (B) 1턴을 빼고 마지막 질문만 → 모른다
  const withoutHistory = await model.invoke([{ role: "user", content: "내가 좋아하는 음식이 뭐라고?" }]);
  console.log("[1턴 제거]  :", withoutHistory.text);
  console.log("→ 모델이 까먹은 게 아니다. 애초에 안 준 것이다.");
} else {
  console.log("(ANTHROPIC_API_KEY 없음 — 건너뜁니다)");
}

/* ===== [정답 3] content 접근자 3종 비교 =====
 * 핵심: .text 에 reasoning 은 포함되지 않는다!
 * .contentBlocks 에는 3개 블록이 다 보이는데 .text 는 텍스트 블록 2개만 이어붙인다.
 * 로그에서 reasoning 이 조용히 사라지는 이유가 바로 이것이다.
 *
 * 기대 출력 (구조가 결정적입니다):
 *   (a) .content      = "안녕하세요"
 *   (a) .text         = "안녕하세요"
 *   (a) .contentBlocks= [{"type":"text","text":"안녕하세요"}]
 *   (b) .content      = [{"type":"reasoning",...},{"type":"text","text":"서울은"},{"type":"text","text":" 맑습니다"}]
 *   (b) .text         = "서울은 맑습니다"        ← reasoning 없음!
 *   (b) .contentBlocks= 3개 블록 전부
 */
hr("[정답 3] content 접근자 3종 비교");

// (a) content 가 문자열
const strMsg = new AIMessage("안녕하세요");
console.log('(a) .content      =', JSON.stringify(strMsg.content));
console.log('(a) .text         =', JSON.stringify(strMsg.text));
console.log('(a) .contentBlocks=', JSON.stringify(strMsg.contentBlocks));
// ↑ 문자열로 넣었는데도 블록 배열로 "정규화"되어 나온다

// (b) content 가 배열 (text 2개 + reasoning 1개)
const arrMsg = new AIMessage({
  content: [
    { type: "reasoning", reasoning: "사용자는 날씨를 물었다" },
    { type: "text", text: "서울은" },
    { type: "text", text: " 맑습니다" },
  ],
});
console.log('\n(b) .content      =', JSON.stringify(arrMsg.content));
console.log('(b) .text         =', JSON.stringify(arrMsg.text));
console.log('(b) .contentBlocks=', JSON.stringify(arrMsg.contentBlocks));
console.log('(b) 블록 타입들    =', arrMsg.contentBlocks.map((b) => b.type));

// → .text 에 reasoning 이 포함되나요?
//   아니오. .text 는 "text" 타입 블록만 골라 이어붙입니다.
//   reasoning 블록은 완전히 제외됩니다 (위 출력에서 "서울은 맑습니다" 만 나오는 것을 확인).
//   이건 대개 원하는 동작입니다 — 사용자에게 모델의 내부 사고를 보여줄 일은 없으니까요.
//   하지만 디버깅/관측 목적으로 reasoning 을 남기려면 .contentBlocks 에서 직접 꺼내야 합니다.
//   그리고 reasoning 은 usage_metadata.output_token_details.reasoning 으로 "과금"됩니다 —
//   즉 .text 에는 안 보이는데 돈은 나가는 토큰입니다.

/* ===== [정답 4] 안전한 describe 함수 =====
 * 핵심: .text 와 .contentBlocks 만 쓰면 분기가 아예 필요 없다.
 * "content 가 문자열이든 배열이든 터지면 안 된다" 는 조건은
 * 사실 ".content 를 쓰지 마라" 의 다른 표현이었다.
 *
 * 기대 출력 (구조가 결정적입니다):
 *   { type: 'system', text: '너는 도우미다', blockTypes: [ 'text' ], hasToolCalls: false }
 *   { type: 'human',  text: '안녕',        blockTypes: [ 'text' ], hasToolCalls: false }
 *   { type: 'human',  text: '이 이미지 뭐야?', blockTypes: [ 'text', 'image' ], hasToolCalls: false }
 *   { type: 'ai',     text: '',            blockTypes: [ 'text', 'tool_call' ], hasToolCalls: true }
 */
hr("[정답 4] 안전한 describe 함수");

type Described = { type: string; text: string; blockTypes: string[]; hasToolCalls: boolean };

function describe(msg: BaseMessage): Described {
  return {
    type: msg.type,
    text: msg.text, // ✅ 항상 string — content 가 배열이어도 안전
    blockTypes: msg.contentBlocks.map((b) => b.type), // ✅ 항상 배열 — 분기 불필요
    hasToolCalls: isAIMessage(msg) && (msg.tool_calls?.length ?? 0) > 0,
  };
}

// ❌ 이렇게 짜면 content 가 배열일 때 터진다:
//   function describeBad(msg: BaseMessage) {
//     return { text: (msg.content as string).slice(0, 50) };  // 💥 배열엔 slice 가 다르게 동작
//   }

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

for (const m of testMsgs) console.log(describe(m));
console.log("→ content 가 문자열이든 배열이든 똑같이 동작한다. 분기가 한 줄도 없다.");

/* ===== [정답 5] tool_call_id 불일치 =====
 * 핵심: LangChain 은 tool_call_id 일치 여부를 "전혀 검사하지 않는다".
 * 배열을 만들 때도, invoke 를 호출할 때도 조용하다.
 * 에러는 네트워크 왕복 뒤 provider 에서 HTTP 400 으로 온다.
 *
 * 기대 출력:
 *   키 있으면 → BadRequestError 계열. 대략 다음과 같은 취지의 메시지:
 *     Anthropic: "messages.2: tool_use_id 'call_999' not found in the preceding assistant message"
 *     OpenAI:    "Invalid parameter: messages with role 'tool' must be a response to a preceding
 *                 message with 'tool_calls'"
 *   (정확한 문구는 provider/버전마다 다릅니다 — 여러분이 실제로 받은 전문을 기록하세요)
 */
hr("[정답 5] tool_call_id 불일치");

const mismatched: BaseMessage[] = [
  new HumanMessage("서울 날씨?"),
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
  }),
  new ToolMessage({ content: "맑음, 27도", tool_call_id: "call_999" }), // 💥 call_1 이어야 하는데
];

// 배열을 만드는 것 자체는 아무 문제가 없다 — 이게 이 함정의 핵심이다
console.log("배열 생성: 성공 (에러 없음).", mismatched.length, "개");
console.log("AIMessage 가 요청한 id :", (mismatched[1] as AIMessage).tool_calls![0].id);
console.log("ToolMessage 가 답한 id :", (mismatched[2] as ToolMessage).tool_call_id);

if (hasKey) {
  const model = await initChatModel("anthropic:claude-sonnet-4-6");
  try {
    await model.invoke(mismatched);
    console.log("(에러가 안 났다면 provider 가 관대한 것입니다 — 그래도 의존하지 마세요)");
  } catch (e) {
    console.log("\n💥 provider 에러:", (e as Error).constructor.name);
    console.log((e as Error).message);
  }
} else {
  console.log("(ANTHROPIC_API_KEY 없음 — 실제 400 은 재현하지 않습니다)");
}

// → 왜 LangChain 단계에서는 에러가 안 나는가?
//   LangChain 의 메시지는 그냥 "자료구조" 이기 때문입니다.
//   ToolMessage 는 tool_call_id 가 string 이기만 하면 만들어집니다 —
//   그 값이 앞선 AIMessage 의 tool_calls[].id 중에 실제로 존재하는지는
//   타입 시스템으로 표현할 수도 없고, LangChain 이 런타임에 검사하지도 않습니다.
//   (검사하려면 배열 전체의 앞뒤 문맥을 봐야 하는데, 메시지는 서로를 모릅니다)
//
//   결과적으로 오류는 "가장 늦게, 가장 먼 곳에서" 발견됩니다:
//     내 코드(조용함) → invoke(조용함) → HTTP 요청 → provider 검증 → 400
//   스택 트레이스는 ToolMessage 를 만든 줄이 아니라 invoke 를 가리키므로 원인 추적이 괴롭습니다.
//
//   방어법: id 를 문자열 리터럴로 손으로 적지 말고, 항상 call.id 를 그대로 전달할 것.
//           그러면 불일치가 "원천적으로 불가능" 해집니다. (정답 6 참고)

/* ===== [정답 6] 도구 3개 중 1개 실패 =====
 * 핵심: catch 에서 continue 하면 그 호출에 대한 응답이 영영 없어 다음 invoke 가 400 이다.
 * 실패해도 반드시 status: "error" 로 답해야 한다.
 * 코드는 두 줄 차이인데 하나는 400 이 나고 하나는 정상이다.
 *
 * 기대 출력 (구조가 결정적입니다):
 *   tool | call_1 | success | 서울 날씨: 맑음
 *   tool | call_2 | error   | 에러: 알 수 없는 도시: 없는도시
 *   tool | call_3 | success | 부산 날씨: 맑음
 *   도구 호출 3개 == ToolMessage 3개 -> ✅ 계약 준수
 */
hr("[정답 6] 도구 3개 중 1개 실패");

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

const msgs6: BaseMessage[] = [new HumanMessage("서울, 없는도시, 부산 날씨 알려줘"), aiThreeCalls];

for (const call of aiThreeCalls.tool_calls!) {
  try {
    const result = runTool(call.name, call.args as Record<string, unknown>);
    // ✅ id 를 손으로 적지 않고 call.id 를 그대로 전달 → 불일치가 불가능
    msgs6.push(new ToolMessage({ content: result, tool_call_id: call.id!, name: call.name }));
  } catch (e) {
    // ✅ 실패해도 반드시 답한다. 여기서 continue 하면 다음 invoke 가 400.
    msgs6.push(
      new ToolMessage({
        content: `에러: ${(e as Error).message}`,
        status: "error",
        tool_call_id: call.id!,
        name: call.name,
      })
    );
  }
}

for (const m of msgs6.filter((m) => m.type === "tool")) {
  const t = m as ToolMessage;
  console.log(`tool | ${t.tool_call_id} | ${(t.status ?? "success").padEnd(7)} | ${t.text}`);
}

const callCount6 = aiThreeCalls.tool_calls!.length;
const toolCount6 = msgs6.filter((m) => m.type === "tool").length;
console.log(`\n도구 호출 ${callCount6}개 == ToolMessage ${toolCount6}개 ->`, callCount6 === toolCount6 ? "✅ 계약 준수" : "💥 400 예약");
console.log("→ 실패한 call_2 도 '에러' 라는 답을 받았다. 모델은 이걸 읽고 재시도하거나 사용자에게 설명할 수 있다.");

/* ===== [정답 7] 트리밍 옵션 비교 =====
 * 핵심: 옵션 없는 트리밍은 (1) 시스템 프롬프트를 버리고 (2) 아무 타입에서나 시작한다.
 *
 * 기대 출력 (구조가 결정적입니다):
 *   (a) 옵션 없이
 *       [ 'ai:3번 답변', 'human:4번 질문', 'ai:4번 답변', 'human:5번 질문', 'ai:5번 답변' ]
 *       첫 메시지 타입: ai       ← system 이 날아갔고, 하필 ai 로 시작한다
 *   (b) includeSystem: true, startOn: "human"
 *       [ 'system:너는 간결한 도우미다', 'human:4번 질문', 'ai:4번 답변', 'human:5번 질문', 'ai:5번 답변' ]
 *       첫 메시지 타입: system
 */
hr("[정답 7] 트리밍 옵션 비교");

const longConvo: BaseMessage[] = [
  new SystemMessage("너는 간결한 도우미다"),
  new HumanMessage("1번 질문"), new AIMessage("1번 답변"),
  new HumanMessage("2번 질문"), new AIMessage("2번 답변"),
  new HumanMessage("3번 질문"), new AIMessage("3번 답변"),
  new HumanMessage("4번 질문"), new AIMessage("4번 답변"),
  new HumanMessage("5번 질문"), new AIMessage("5번 답변"),
];

const a7 = await trimMessages(longConvo, { maxTokens: 5, tokenCounter: counter, strategy: "last" });
console.log("(a) 옵션 없이:");
console.log("   ", a7.map((m) => `${m.type}:${m.text}`));
console.log("    첫 메시지 타입:", a7[0]?.type);

const b7 = await trimMessages(longConvo, {
  maxTokens: 5,
  tokenCounter: counter,
  strategy: "last",
  includeSystem: true,
  startOn: "human",
});
console.log("\n(b) includeSystem: true, startOn: 'human':");
console.log("   ", b7.map((m) => `${m.type}:${m.text}`));
console.log("    첫 메시지 타입:", b7[0]?.type);

// → 두 결과의 첫 메시지 타입 차이:
//   (a) 는 system 이 사라지고 하필 'ai' 로 시작합니다 — "AI 가 먼저 말을 건" 이상한 대화가 됩니다.
//       시스템 프롬프트가 없으니 모델은 "간결한 도우미" 라는 지시를 잊습니다 —
//       에러는 안 나고, 그냥 말투가 슬금슬금 달라집니다. 조용한 실패의 전형입니다.
//   (b) 는 항상 system 으로 시작하고, 그 다음이 human 입니다.
//       maxTokens 예산 안에서도 시스템 프롬프트는 보존됩니다.
//
//   교훈: includeSystem 의 기본값은 false 다. 즉 "아무 생각 없이 트리밍하면
//         시스템 프롬프트가 제일 먼저 날아간다" — 배열의 맨 앞이라 가장 오래된 메시지이기 때문.

/* ===== [정답 8] 고아 ToolMessage 재현 =====
 * 이 파일의 하이라이트.
 * 핵심: 내 코드 어디에도 tool_call_id 를 손으로 적은 곳이 없는데 정답 5 와 똑같은 400 이 난다.
 *
 * 기대 출력 (구조가 결정적입니다):
 *   (a) 옵션 없이       -> [ 'tool', 'ai' ]      첫 타입: tool   💥 고아!
 *   (b) startOn: human  -> [ 'system', 'human', 'ai', 'tool', 'ai' ]  첫 타입: system
 */
hr("[정답 8] 고아 ToolMessage 재현");

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

const a8 = await trimMessages(toolConvo, { maxTokens: 2, tokenCounter: counter, strategy: "last" });
console.log("(a) 옵션 없이 maxTokens: 2 ->", a8.map((m) => m.type));
console.log("    첫 메시지 타입:", a8[0]?.type, a8[0]?.type === "tool" ? "💥 고아 ToolMessage!" : "");

const b8 = await trimMessages(toolConvo, {
  maxTokens: 5,
  tokenCounter: counter,
  strategy: "last",
  startOn: "human",
  includeSystem: true,
});
console.log('\n(b) startOn: "human" 추가 ->', b8.map((m) => m.type));
console.log("    첫 메시지 타입:", b8[0]?.type, "✅ 고아 없음");

// → 왜 내가 tool_call_id 를 적지 않았는데도 400 이 나는가?
//   (a) 의 결과 배열은 [tool, ai] 입니다. 즉 ToolMessage 로 시작합니다.
//   이 ToolMessage 의 tool_call_id 는 "call_1" 인데, 그 call_1 을 요청한 AIMessage 는
//   트리밍으로 잘려나가 배열에 없습니다.
//
//   provider 입장에서 보면 이렇습니다:
//     "call_1 좀 실행해줘" 라고 말한 적도 없는데, 갑자기 "call_1 결과는 맑음이야" 라는 메시지가 온다.
//   → 정답 5(오타로 인한 불일치)와 provider 눈에는 "완전히 똑같은 상황" 입니다. 그래서 같은 400.
//
//   무서운 점: 정답 5 는 내 코드에 "call_999" 라는 오타가 보이기라도 합니다.
//   여기서는 내가 쓴 코드가 전부 옳습니다 — tool_call_id 도 call.id 로 제대로 넘겼고,
//   호출 개수와 응답 개수도 맞았습니다. 그런데도 trimMessages 한 줄이 그 무결성을 깨뜨립니다.
//
//   결론: trimMessages 는 "메시지를 몇 개 지우는" 단순 작업이 아니라
//         대화의 무결성을 깰 수 있는 위험한 연산이다.
//         도구를 쓰는 에이전트에서 startOn: "human" 은 선택이 아니라 필수다.
//
//   (Step 10 의 메모리, Step 14 의 컨텍스트 엔지니어링에서 이 문제가 계속 돌아옵니다.
//    버리는 대신 요약해 압축하는 createSummarizationMiddleware 는 Step 11 에서 다룹니다.)

console.log("\n끝. 8문제 완료 — Step 04 로 이어집니다.");
