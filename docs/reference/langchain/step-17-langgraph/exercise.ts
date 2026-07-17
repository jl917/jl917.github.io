/**
 * Step 17 — LangGraph 그래프 API — 연습문제
 * 실행: npx tsx docs/reference/langchain/step-17-langgraph/exercise.ts
 *
 * 각 [문제 N] 아래를 직접 채우세요. 전부 API 키 없이 풀 수 있습니다.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어본 뒤에 열어보세요.
 */
import "dotenv/config";
import {
  StateGraph,
  StateSchema,
  MessagesValue,
  ReducedValue,
  Command,
  Send,
  MemorySaver,
  START,
  END,
  type GraphNode,
  type ConditionalEdgeRouter,
} from "@langchain/langgraph";
import { AIMessage, HumanMessage } from "@langchain/core/messages";
import * as z from "zod";

const line = (t: string) => console.log(`\n${"=".repeat(60)}\n${t}\n${"=".repeat(60)}`);

/* ===== [문제 1] 순차 그래프 =====
 * 상태에 `text: string` 하나를 두고, 노드 3개를 순서대로 연결하세요.
 *   trim    → 앞뒤 공백 제거
 *   upper   → 대문자로
 *   exclaim → 뒤에 "!" 붙이기
 * START → trim → upper → exclaim → END 로 이어서
 * invoke({ text: "  hello graph  " }) 가 { text: "HELLO GRAPH!" } 가 되게 하세요.
 */
line("[문제 1] 순차 그래프");

// 여기에 작성하세요

/* ===== [문제 2] 커스텀 리듀서 =====
 * 상태 키 `maxScore: number` 를 만들되, 리듀서가 "더 큰 값만 살아남게" 동작해야 합니다.
 * (기본 LastValue 는 마지막 값이 이기지만, 이 키는 최댓값이 이겨야 합니다.)
 * 노드 3개가 각각 30, 90, 50 을 쓰고 START 에서 병렬로 fan-out 되게 하세요.
 * 최종 결과가 { maxScore: 90 } 이어야 합니다.
 * 힌트: new ReducedValue(z.number().default(0), { reducer: (a, b) => ... })
 */
line("[문제 2] 커스텀 리듀서");

// 여기에 작성하세요

/* ===== [문제 3] 조건부 분기 =====
 * 상태: { amount: number; tier: string; message: string }
 * classify 노드가 amount 를 보고 tier 를 정합니다 (10000 이상이면 "vip", 아니면 "normal").
 * 라우터가 tier 를 읽어 vipNode / normalNode 로 보냅니다.
 * addConditionalEdges 의 세 번째 인자로 도착 가능한 노드를 명시하세요.
 * invoke({ amount: 50000 }) → message 가 "VIP 혜택 적용", 아니면 "일반 처리".
 */
line("[문제 3] 조건부 분기");

// 여기에 작성하세요

/* ===== [문제 4] 깨진 병렬 그래프 고치기 =====
 * 아래 그래프는 InvalidUpdateError 로 터집니다. 왜 터지는지 확인한 뒤,
 * `logs` 키에 리듀서를 달아 두 브랜치의 결과가 모두 남도록 고치세요.
 * (BrokenState 를 고쳐서 FixedState 를 만들고, 그래프를 다시 조립하면 됩니다.)
 */
line("[문제 4] 깨진 병렬 그래프 고치기");

const BrokenState = new StateSchema({
  logs: z.array(z.string()).default(() => []), // ← 리듀서가 없다
});

const brokenGraph = new StateGraph(BrokenState)
  .addNode("fetchUser", () => ({ logs: ["유저 조회"] }))
  .addNode("fetchOrders", () => ({ logs: ["주문 조회"] }))
  .addEdge(START, "fetchUser")
  .addEdge(START, "fetchOrders")
  .addEdge("fetchUser", END)
  .addEdge("fetchOrders", END)
  .compile();

try {
  console.log(await brokenGraph.invoke({}));
} catch (error) {
  console.log("고치기 전:", (error as Error).constructor.name);
}

// 여기에 고친 버전을 작성하세요 (결과가 logs: ["유저 조회", "주문 조회"] 두 개 다 남아야 함)

/* ===== [문제 5] Command 로 상태 갱신 + 라우팅 =====
 * 상태: { attempts: number; status: string }
 * `tryTask` 노드는 attempts 를 1 늘리면서, 동시에
 *   attempts 가 3 미만이면 자기 자신("tryTask") 으로 되돌아가고
 *   3 이상이면 "giveUp" 으로 가야 합니다.
 * 이걸 addConditionalEdges 없이 Command 하나로만 구현하세요.
 * giveUp 노드는 status 를 "포기"로 설정합니다.
 * 최종 결과: { attempts: 3, status: "포기" }
 * 힌트: addNode 의 세 번째 인자 { ends: [...] } 를 잊지 마세요.
 */
line("[문제 5] Command");

// 여기에 작성하세요

/* ===== [문제 6] Send 로 map-reduce =====
 * 상태: { words: string[]; lengths: number[] (리듀서로 누적) }
 * START 에서 words 각각을 "measure" 노드로 Send 하고,
 * measure 노드는 받은 단어의 길이를 lengths 에 넣습니다.
 * invoke({ words: ["a", "bb", "ccc"] }) → lengths 에 1, 2, 3 이 모두 담겨야 합니다.
 * (순서는 보장되지 않으니 정렬해서 비교하세요.)
 */
line("[문제 6] Send map-reduce");

// 여기에 작성하세요

/* ===== [문제 7] 서브그래프 =====
 * `messages` 와 `summary` 를 공유 상태로 두고:
 *   - 서브그래프: "summarize" 노드가 messages 개수를 세어 summary 에 "메시지 N개" 라고 적는다
 *   - 부모 그래프: 서브그래프를 "child" 노드로 넣고, 그 뒤 "report" 노드가
 *     summary 를 읽어 messages 에 AIMessage 로 덧붙인다
 * invoke({ messages: [HumanMessage("a"), HumanMessage("b")] }) 를 실행해
 * 마지막 메시지가 "메시지 2개" 를 포함하는지 확인하세요.
 */
line("[문제 7] 서브그래프");

// 여기에 작성하세요

/* ===== [문제 8] ReAct 루프를 mock 으로 직접 구현 =====
 * 실제 모델 없이 ReAct 구조만 만듭니다.
 * 상태: { messages: MessagesValue, toolCallsLeft: number }
 * - "model" 노드: toolCallsLeft 가 0 보다 크면 tool_calls 가 있는 AIMessage 를 흉내내고
 *   (messages 에 AIMessage("도구를 부릅니다") 를 추가, toolCallsLeft 를 1 줄임)
 *   0 이면 AIMessage("최종 답변") 을 추가한다.
 * - "tools" 노드: messages 에 AIMessage("도구 결과") 를 추가한다.
 * - 라우터: 마지막 메시지가 "도구를 부릅니다" 면 "tools", 아니면 END.
 * - "tools" → "model" 로 되돌아간다 (루프).
 * invoke({ messages: [HumanMessage("시작")], toolCallsLeft: 2 }) 가
 * 무한 루프에 빠지지 않고 "최종 답변" 으로 끝나는지 확인하세요.
 * 또 라우터에서 END 를 절대 반환하지 않게 바꾸면 어떤 에러가 나는지도 확인하세요.
 */
line("[문제 8] ReAct 루프");

// 여기에 작성하세요
