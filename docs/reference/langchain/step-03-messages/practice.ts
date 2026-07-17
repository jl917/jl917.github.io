/**
 * Step 03 — 메시지와 콘텐츠 블록
 * 실행: npx tsx docs/reference/langchain/step-03-messages/practice.ts
 *
 * 이 파일은 API 키 없이도 실행됩니다.
 * 메시지는 순수한 자료구조라서 대부분의 예제가 네트워크를 타지 않습니다.
 * ANTHROPIC_API_KEY 가 있으면 모델 호출 절([3-1], [3-5])까지 함께 돕니다.
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
// ↑ 함정: 타입가드(isAIMessage 등)는 "langchain" 에 없다. "@langchain/core/messages" 에서만 온다.

const hr = (title: string) => console.log(`\n${"=".repeat(70)}\n${title}\n${"=".repeat(70)}`);
const hasKey = Boolean(process.env.ANTHROPIC_API_KEY);

/* ===== [3-1] 메시지가 곧 상태다 ===== */
hr("[3-1] 메시지가 곧 상태다 — 대화는 메시지 배열이다");

if (hasKey) {
  // OpenAI 를 쓰려면: initChatModel("openai:gpt-5.5") + OPENAI_API_KEY
  const model = await initChatModel("anthropic:claude-sonnet-4-6");

  // 1번째 턴
  const a1 = await model.invoke([{ role: "user", content: "내 이름은 김민수야." }]);
  console.log("1턴 답변:", a1.text);

  // 2번째 턴 — 이전 대화를 "직접" 배열에 쌓아서 다시 보낸다
  const a2 = await model.invoke([
    { role: "user", content: "내 이름은 김민수야." },
    { role: "assistant", content: a1.text },
    { role: "user", content: "내 이름이 뭐라고?" },
  ]);
  console.log("2턴 답변(기억함):", a2.text);

  // 이전 대화를 빼면? — 모델이 "까먹은" 게 아니라 애초에 안 준 것이다
  const a3 = await model.invoke([{ role: "user", content: "내 이름이 뭐라고?" }]);
  console.log("2턴 답변(기억 못함):", a3.text);
} else {
  console.log("(ANTHROPIC_API_KEY 없음 — 모델 호출 절을 건너뜁니다)");
  console.log("핵심: 대화 = 메시지 배열(state), 한 턴 = 배열에 메시지를 append");
}

/* ===== [3-2] 메시지 타입 완전 정복 ===== */
hr("[3-2] 메시지 타입 — role 문자열과 .type 값은 다르다");

const sys = new SystemMessage("너는 간결한 한국어 도우미다.");
const hum = new HumanMessage("안녕");
const ai0 = new AIMessage("반가워");
const tool0 = new ToolMessage({ content: "결과", tool_call_id: "call_x" });

// 주의: role 은 "user"/"assistant" 지만 .type 은 "human"/"ai" 다
console.log("SystemMessage .type =", sys.type); // "system"
console.log("HumanMessage  .type =", hum.type); // "human"     ← "user" 가 아니다!
console.log("AIMessage     .type =", ai0.type); // "ai"        ← "assistant" 가 아니다!
console.log("ToolMessage   .type =", tool0.type); // "tool"

const sample: BaseMessage[] = [sys, hum, ai0, tool0];

// ❌ 항상 빈 배열 — .type 은 "user" 가 절대 아니다
console.log('filter(m => m.type === "user")  =', sample.filter((m) => m.type === "user").length, "개");
// ✅
console.log('filter(m => m.type === "human") =', sample.filter((m) => m.type === "human").length, "개");

// 타입가드가 더 안전하다 (TypeScript 가 타입을 좁혀준다)
for (const m of sample) {
  if (isAIMessage(m)) console.log("isAIMessage 로 찾음:", m.text, "| 도구호출:", m.tool_calls?.length ?? 0);
}

/* ===== [3-3] 두 가지 표기법: 객체 리터럴 vs 클래스 ===== */
hr("[3-3] 두 가지 표기법 — 객체 리터럴 vs 클래스");

// (A) 객체 리터럴 — 가볍다. import 불필요
const msgsA = [
  { role: "system", content: "너는 간결한 한국어 도우미다." },
  { role: "user", content: "안녕?" },
];
// (B) 클래스 인스턴스 — 명시적이다. .text 등 헬퍼를 쓸 수 있다
const msgsB = [new SystemMessage("너는 간결한 한국어 도우미다."), new HumanMessage("안녕?")];

console.log("리터럴 배열 길이:", msgsA.length, "| 클래스 배열 길이:", msgsB.length);
console.log("리터럴에는 .text 가 없다:", (msgsA[1] as any).text); // undefined
console.log("클래스에는 .text 가 있다:", msgsB[1].text); // "안녕?"

// LangChain 은 리터럴을 내부에서 클래스로 강제 변환(coerce)한다
const { coerceMessageLikeToMessage } = await import("@langchain/core/messages");
const coerced = [
  { role: "user", content: "안녕" },
  { role: "assistant", content: "네" },
  { role: "system", content: "너는" },
  { role: "tool", content: "결과", tool_call_id: "call_1" },
  "맨 문자열", // ← 문자열은 HumanMessage 가 된다. model.invoke("안녕") 이 동작한 이유!
];
for (const lit of coerced) {
  const m = coerceMessageLikeToMessage(lit as any);
  const label = typeof lit === "string" ? '"맨 문자열"' : `role: "${(lit as any).role}"`;
  console.log(label.padEnd(20), "->", m.constructor.name);
}

/* ===== [3-4] 콘텐츠 블록 — content 가 string 일 때와 배열일 때 ===== */
hr("[3-4] 콘텐츠 블록 — content 는 string | ContentBlock[] 이다");

// (A) content 가 문자열
const m1 = new HumanMessage("안녕");
console.log("m1 typeof content:", typeof m1.content); // "string"
console.log("m1 content.toUpperCase():", (m1.content as string).toUpperCase()); // 동작한다

// (B) content 가 배열
const m2 = new HumanMessage({
  content: [
    { type: "text", text: "이 이미지 설명해줘" },
    { type: "image", url: "https://example.com/a.png" },
  ],
});
console.log("m2 typeof content:", typeof m2.content, "| isArray:", Array.isArray(m2.content));

// ⚠️ 함정을 직접 눈으로 보자 — .content 를 문자열로 가정하면 터진다
try {
  console.log((m2.content as any).toUpperCase());
} catch (e) {
  console.log("💥 m2.content.toUpperCase() ->", (e as Error).constructor.name + ":", (e as Error).message);
}

// ✅ 해법: 텍스트가 필요하면 .content 가 아니라 .text 를 쓴다 (항상 string)
console.log("m1.text =", JSON.stringify(m1.text));
console.log("m2.text =", JSON.stringify(m2.text));

// ✅ 구조가 필요하면 .contentBlocks (항상 배열, 표준 형태로 정규화)
console.log("m1.contentBlocks =", JSON.stringify(m1.contentBlocks)); // 문자열도 블록으로 정규화된다!
console.log("m2.contentBlocks =", JSON.stringify(m2.contentBlocks));

// .text 는 텍스트 블록만 이어붙이고 reasoning 은 뺀다
const reasoned = new AIMessage({
  content: [
    { type: "reasoning", reasoning: "사용자는 날씨를 물었다" },
    { type: "text", text: "서울은" },
    { type: "text", text: " 맑습니다" },
  ],
});
console.log("\nreasoned.text =", JSON.stringify(reasoned.text)); // "서울은 맑습니다" — reasoning 이 없다!
console.log("reasoned.contentBlocks 타입들 =", reasoned.contentBlocks.map((b) => b.type));

// reasoning 을 보려면 .contentBlocks 에서 직접 꺼내야 한다
const reasoning = reasoned.contentBlocks
  .filter((b) => b.type === "reasoning")
  .map((b) => (b as { reasoning: string }).reasoning)
  .join("\n");
console.log("추출한 reasoning =", JSON.stringify(reasoning));

/* ===== [3-5] 멀티모달 — 이미지 넣기 ===== */
hr("[3-5] 멀티모달 — 이미지 블록의 3가지 소스");

// v1 표준 블록: source_type 은 없다. mimeType 은 camelCase 다.
const imgUrl = { type: "image", url: "https://example.com/cat.png" };
const imgB64 = { type: "image", data: "<base64 문자열>", mimeType: "image/png" };
const imgId = { type: "image", fileId: "file-abc123" };
console.log("(1) URL    :", JSON.stringify(imgUrl));
console.log("(2) base64 :", JSON.stringify(imgB64), "← mimeType 필수 (camelCase!)");
console.log("(3) file id:", JSON.stringify(imgId));

// ⚠️ 구버전(v0.3) 형식은 쓰지 마세요 — 에러가 안 나고 provider 단에서 뒤늦게 터집니다
//   { type: "image", source_type: "url", url: "..." }        ❌
//   { type: "image_url", image_url: { url: "..." } }         ❌ (OpenAI 원형)

const multimodal = new HumanMessage({
  content: [{ type: "text", text: "이 사진에 무엇이 보이나요? 한 문장으로." }, imgUrl],
});
console.log("\n멀티모달 메시지 블록:", multimodal.contentBlocks.map((b) => b.type));
console.log(".text 는 텍스트 블록만:", JSON.stringify(multimodal.text));

if (hasKey) {
  // 실제 이미지 파일이 있다면 base64 로 실어 보낼 수 있습니다:
  //   const bytes = await readFile("./cat.png");
  //   { type: "image", data: bytes.toString("base64"), mimeType: "image/png" }
  console.log("(실제 이미지 호출은 ./cat.png 를 두고 주석을 풀어 시도해 보세요)");
} else {
  console.log("(ANTHROPIC_API_KEY 없음 — 실제 이미지 호출은 건너뜁니다)");
}

/* ===== [3-6] AIMessage 해부 ===== */
hr("[3-6] AIMessage 해부 — content / tool_calls / usage_metadata / response_metadata / id");

const ai = new AIMessage({
  content: "",
  tool_calls: [{ name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" }],
});

console.log("id:               ", ai.id); // 손으로 만들면 undefined. provider 가 채워준다
console.log("text:             ", JSON.stringify(ai.text));
console.log("content:          ", JSON.stringify(ai.content));
console.log("tool_calls:       ", JSON.stringify(ai.tool_calls));
console.log("contentBlocks:    ", JSON.stringify(ai.contentBlocks));
// ↑ 도구 호출은 tool_calls 필드로도, contentBlocks 의 tool_call 블록으로도 보인다 (같은 정보의 두 뷰)
console.log("usage_metadata:   ", ai.usage_metadata); // undefined — provider 만 채워주는 필드
console.log("response_metadata:", JSON.stringify(ai.response_metadata)); // {} — 마찬가지

// args 는 이미 파싱된 객체다. JSON.parse 하면 안 된다!
console.log("\nargs 는 객체다:", ai.tool_calls![0].args, "| args.city =", (ai.tool_calls![0].args as any).city);

// usage_metadata 는 optional 이다 — 반드시 방어적으로 접근하라
let totalTokens = 0;
totalTokens += ai.usage_metadata?.total_tokens ?? 0; // ✅ ?? 0 이 없으면 스트리밍에서 터진다
console.log("누적 토큰(방어적 접근):", totalTokens);

if (hasKey) {
  const model = await initChatModel("anthropic:claude-sonnet-4-6");
  const res = await model.invoke([{ role: "user", content: "안녕이라고만 답해." }]);
  console.log("\n--- 실제 모델 응답 (값은 매번 다르지만 필드 이름은 결정적) ---");
  console.log("id:            ", res.id);
  console.log("text:          ", res.text);
  console.log("usage_metadata:", JSON.stringify(res.usage_metadata, null, 2));
  console.log("response_metadata:", JSON.stringify(res.response_metadata, null, 2));
}

/* ===== [3-7] ToolMessage 와 tool_call_id ===== */
hr("[3-7] ToolMessage 와 tool_call_id — 도구 결과를 돌려주는 계약");

// 두 가지 생성 방식 — 동등하다
const t1 = new ToolMessage({ content: "맑음, 27도", tool_call_id: "call_1", name: "get_weather" });
const t2 = new ToolMessage("맑음, 27도", "call_1", "get_weather"); // (content, tool_call_id, name)
console.log("t1:", t1.tool_call_id, t1.name, t1.type);
console.log("t2:", t2.tool_call_id, t2.name, t2.type);

// artifact: 모델에 안 보내는 부가 데이터 (토큰 0)
const withArtifact = new ToolMessage({
  content: "3건 조회됨: 주문 #1, #2, #3", // ← 모델이 보는 것 (토큰 소모)
  artifact: { rows: [{ id: 1 }, { id: 2 }, { id: 3 }] }, // ← 내 코드만 보는 것 (토큰 0)
  tool_call_id: "call_1",
});
console.log("\ncontent(모델이 봄):", withArtifact.content);
console.log("artifact(모델은 못 봄):", JSON.stringify(withArtifact.artifact));

// 도구 실패는 예외를 던지지 말고 status: "error" 로 돌려준다
const failed = new ToolMessage({
  content: "에러: 알 수 없는 도시",
  status: "error",
  tool_call_id: "call_2",
});
console.log("실패한 도구:", failed.status, "|", failed.content);

// ⚠️ 함정: tool_call_id 불일치는 LangChain 단계에서 조용하다. provider 가 400 을 뱉는다.
const broken = [
  new AIMessage({
    content: "",
    tool_calls: [{ name: "get_weather", args: {}, id: "call_1", type: "tool_call" }],
  }),
  new ToolMessage({ content: "맑음", tool_call_id: "call_2" }), // 💥 오타 하나
];
console.log("\n⚠️ 불일치 배열도 만들어지는 데 아무 문제 없다:", broken.length, "개");
console.log("   AIMessage 가 요청한 id:", (broken[0] as AIMessage).tool_calls![0].id);
console.log("   ToolMessage 가 답한 id:", (broken[1] as ToolMessage).tool_call_id);
console.log("   -> LangChain 은 검사하지 않는다. model.invoke() 시 provider 가 400 을 뱉는다.");

/* ===== [3-8] 메시지 트리밍 — 컨텍스트 관리 ===== */
hr("[3-8] 메시지 트리밍 — trimMessages");

const convo: BaseMessage[] = [
  new SystemMessage("너는 간결한 도우미다"),
  new HumanMessage("1번 질문"),
  new AIMessage("1번 답변"),
  new HumanMessage("2번 질문"),
  new AIMessage("2번 답변"),
  new HumanMessage("3번 질문"),
  new AIMessage("3번 답변"),
];

// 설명을 위한 가짜 카운터: "메시지 1개 = 1토큰"
// 실무에서는 tokenCounter 에 모델 인스턴스를 그대로 넘긴다 (실제 토크나이저 사용)
const counter = (ms: BaseMessage[]) => ms.length;

const trimmed = await trimMessages(convo, { maxTokens: 3, tokenCounter: counter, strategy: "last" });
console.log("옵션 없이 maxTokens: 3 ->");
console.log(" ", trimmed.map((m) => `${m.type}:${m.text}`));
console.log("  ⚠️ (1) 시스템 프롬프트가 사라졌다  (2) 배열이 'ai' 로 시작한다");

const better = await trimMessages(convo, {
  maxTokens: 5,
  tokenCounter: counter,
  strategy: "last",
  includeSystem: true, // 맨 앞 SystemMessage 는 항상 살린다
  startOn: "human", // human 부터 시작하도록 맞춘다
});
console.log("\nincludeSystem + startOn: 'human' ->");
console.log(" ", better.map((m) => `${m.type}:${m.text}`));
console.log("  ✅ 시스템 프롬프트 생존 + human 으로 시작하는 온전한 대화");

// strategy: "first" 는 앞부분을 남긴다
const first = await trimMessages(convo, { maxTokens: 3, tokenCounter: counter, strategy: "first" });
console.log('\nstrategy: "first" ->', first.map((m) => `${m.type}:${m.text}`));

// trimMessages 는 호출 형태가 두 가지다
const trimmer = trimMessages({ maxTokens: 3, tokenCounter: counter, strategy: "last" }); // Runnable 반환
const viaRunnable = await trimmer.invoke(convo);
console.log("Runnable 형태 결과 동일:", viaRunnable.length, "개");

// ⚠️ 함정: 트리밍이 도구 호출 쌍을 갈라놓는다
const toolConvo: BaseMessage[] = [
  new SystemMessage("너는 도우미다"),
  new HumanMessage("서울 날씨?"),
  new AIMessage({ content: "", tool_calls: [{ name: "w", args: {}, id: "call_1", type: "tool_call" }] }),
  new ToolMessage({ content: "맑음", tool_call_id: "call_1" }),
  new AIMessage("서울은 맑습니다"),
];

const orphan = await trimMessages(toolConvo, { maxTokens: 2, tokenCounter: counter, strategy: "last" });
console.log("\n⚠️ 도구 대화를 옵션 없이 트리밍 ->", orphan.map((m) => m.type));
console.log("   배열이 'tool' 로 시작한다! 짝이 되는 AIMessage 가 잘려나간 고아 ToolMessage.");
console.log("   -> 이걸 그대로 보내면 provider 400. 내 코드엔 tool_call_id 를 적은 곳도 없는데!");

const safe = await trimMessages(toolConvo, {
  maxTokens: 5,
  tokenCounter: counter,
  strategy: "last",
  startOn: "human", // ← 도구 쓰는 에이전트에선 선택이 아니라 필수
  includeSystem: true,
});
console.log('\n✅ startOn: "human" 추가 ->', safe.map((m) => m.type));

/* ===== [3-9] 종합 — 도구 호출 한 사이클을 손으로 ===== */
hr("[3-9] 종합 — 도구 호출 한 사이클");

const weatherDB: Record<string, string> = { 서울: "맑음, 27도", 부산: "흐림, 24도" };

const messages: BaseMessage[] = [
  new SystemMessage("너는 날씨 도우미다."),
  new HumanMessage("서울이랑 부산 날씨 알려줘"),
];

// 모델이 도구 2개를 "병렬로" 호출했다고 가정 (실제로는 model.invoke 결과)
messages.push(
  new AIMessage({
    content: "",
    tool_calls: [
      { name: "get_weather", args: { city: "서울" }, id: "call_1", type: "tool_call" },
      { name: "get_weather", args: { city: "부산" }, id: "call_2", type: "tool_call" },
    ],
  })
);

// 핵심: tool_calls 를 순회하며 "빠짐없이" ToolMessage 를 붙인다
// ID 는 손으로 적지 말고 call.id 를 그대로 전달한다 -> 불일치가 원천적으로 불가능
const last = messages.at(-1)!;
if (isAIMessage(last) && last.tool_calls?.length) {
  for (const call of last.tool_calls) {
    try {
      const result = weatherDB[call.args.city as string];
      if (!result) throw new Error(`알 수 없는 도시: ${call.args.city}`);
      messages.push(new ToolMessage({ content: result, tool_call_id: call.id!, name: call.name }));
    } catch (e) {
      // 실패해도 반드시 답한다 — 안 그러면 다음 invoke 가 400
      messages.push(
        new ToolMessage({
          content: `에러: ${(e as Error).message}`,
          status: "error",
          tool_call_id: call.id!,
          name: call.name,
        })
      );
    }
  }
}

console.log(messages.map((m) => `${m.type.padEnd(6)} | ${m.text || "(도구호출)"}`).join("\n"));

// 검증: 도구 호출 개수 == ToolMessage 개수
const callCount = messages.filter(isAIMessage).flatMap((m) => m.tool_calls ?? []).length;
const toolMsgCount = messages.filter((m) => m.type === "tool").length;
console.log(`\n도구 호출 ${callCount}개 == ToolMessage ${toolMsgCount}개 ->`, callCount === toolMsgCount ? "✅ 계약 준수" : "💥 400 예약");

// 이 배열은 이제 model.invoke(messages) 로 그대로 보낼 수 있다.
if (hasKey) {
  const model = await initChatModel("anthropic:claude-sonnet-4-6");
  const final = await model.invoke(messages);
  console.log("\n최종 답변:", final.text);
}

console.log("\n끝. Step 04 — 프롬프트 설계와 템플릿 으로 이어집니다.");
