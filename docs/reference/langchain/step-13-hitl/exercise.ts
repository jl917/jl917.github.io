/**
 * Step 13 — Human-in-the-Loop / 연습문제
 * 실행: npx tsx docs/reference/langchain/step-13-hitl/exercise.ts
 *
 * 각 [문제 N] 블록 아래를 직접 채우세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어 본 뒤에 열어 보세요.
 *
 * 문제 1~3 은 모델 없이 그래프만으로 풀 수 있어 API 키가 없어도 됩니다.
 * 문제 4~8 은 ANTHROPIC_API_KEY 가 필요합니다.
 */
import "dotenv/config";

import { createAgent, humanInTheLoopMiddleware, tool } from "langchain";
import { Command, END, MemorySaver, START, StateGraph, interrupt, isInterrupted } from "@langchain/langgraph";
import * as z from "zod";

/* ===== 공용: 이 파일 전체에서 쓰는 도구 ===== */

// 승인 없이 실행되면 여기에 흔적이 남습니다. 검증용입니다.
export const deleteLog: string[] = [];

const deleteFile = tool(
  async ({ path }) => {
    deleteLog.push(path);
    return `${path} 를 삭제했습니다.`;
  },
  {
    name: "delete_file",
    description: "파일을 삭제합니다. 되돌릴 수 없습니다.",
    schema: z.object({ path: z.string().describe("삭제할 파일 경로") }),
  },
);

const listFiles = tool(async () => "a.txt, b.txt, /etc/passwd", {
  name: "list_files",
  description: "파일 목록을 봅니다.",
  schema: z.object({}),
});

/* ===== [문제 1] interrupt 와 checkpointer ===== */
/*
 * 아래 그래프는 `interrupt()` 를 쓰는데 checkpointer 가 없습니다.
 * 1) 그대로 실행해서 어떤 에러가 나는지 확인하고, 에러 클래스 이름과
 *    메시지 첫 줄을 주석으로 적으세요.
 * 2) checkpointer 를 붙여서 정상 동작하게 고치세요.
 * 3) thread_id 를 주지 않고 invoke 하면 어떻게 되는지도 확인해 보세요.
 */

const Q1State = z.object({ result: z.string().nullable().default(() => null) });

const q1Graph = new StateGraph(Q1State)
  .addNode("ask", () => {
    const answer = interrupt("계속할까요?");
    return { result: String(answer) };
  })
  .addEdge(START, "ask")
  .addEdge("ask", END)
  .compile(/* 여기를 채우세요 */);

// 여기에 실행 코드를 작성하세요.

// → 에러 클래스 이름:
// → 에러 메시지 첫 줄:

/* ===== [문제 2] 부수효과 재실행 ===== */
/*
 * 아래 노드는 interrupt 앞에서 카운터를 올립니다.
 * 1) 한 번 중단시키고 재개한 뒤, counter 값이 몇인지 확인하세요. 왜 그럴까요?
 * 2) 이 노드를 고쳐서, 재개해도 부수효과가 "한 번만" 일어난 것과 같은 결과가
 *    되게 만드세요. (힌트: 멱등하게 만들거나, interrupt 를 부수효과보다 앞으로 옮기거나,
 *    부수효과를 별도 노드로 분리하는 세 가지 길이 있습니다)
 */

export let q2Counter = 0;

const Q2State = z.object({ done: z.boolean().default(() => false) });

const q2Graph = new StateGraph(Q2State)
  .addNode("work", () => {
    q2Counter += 1; // ← 이게 두 번 실행됩니다
    const ok = interrupt("승인?");
    return { done: ok === true };
  })
  .addEdge(START, "work")
  .addEdge("work", END)
  .compile({ checkpointer: new MemorySaver() });

// 여기에 실행 코드와 개선된 그래프를 작성하세요.

/* ===== [문제 3] try/catch 함정 ===== */
/*
 * 아래 노드는 interrupt 를 try/catch 로 감싸고 있어 중단이 동작하지 않습니다.
 * 1) 실행해서 __interrupt__ 가 없다는 것을 확인하세요.
 * 2) 잡힌 예외의 name 이 무엇인지 출력해 보세요.
 * 3) "DB 에러는 잡되 interrupt 는 통과시키는" 형태로 고치세요.
 *    (힌트: @langchain/langgraph 가 내보내는 isGraphInterrupt 를 찾아보거나,
 *     예외의 name 을 검사해서 다시 throw 하세요)
 */

const Q3State = z.object({ status: z.string().nullable().default(() => null) });

const q3Graph = new StateGraph(Q3State)
  .addNode("risky", () => {
    try {
      const ok = interrupt("승인?");
      return { status: `승인=${String(ok)}` };
    } catch (error) {
      return { status: "에러를 삼켰습니다" };
    }
  })
  .addEdge(START, "risky")
  .addEdge("risky", END)
  .compile({ checkpointer: new MemorySaver() });

// 여기에 실행 코드와 개선된 노드를 작성하세요.

/* ===== [문제 4] interruptOn 으로 위험한 도구만 막기 ===== */
/*
 * deleteFile 과 listFiles 를 가진 에이전트를 만드세요.
 * - delete_file 은 approve / reject 만 허용
 * - list_files 는 승인 없이 통과
 * - description 은 "🚨 파일이 영구 삭제됩니다" 로
 * 그리고 "a.txt 지워줘" 를 시켜서 중단이 걸리는지 확인하세요.
 */

// 여기에 작성하세요.

/* ===== [문제 5] __interrupt__ 읽어서 사람에게 보여주기 ===== */
/*
 * 문제 4 의 결과에서 __interrupt__ 를 읽어,
 * "도구이름 / 인자 / 허용된 결정" 세 줄로 출력하는 함수 renderApproval(result) 를 만드세요.
 * - isInterrupted 타입 가드를 쓰세요.
 * - actionRequests 와 reviewConfigs 를 actionName 으로 짝지으세요.
 * - 중단이 아니면 "중단 없음" 을 출력하세요.
 */

// 여기에 작성하세요.

/* ===== [문제 6] 세 가지 결정 전부 써 보기 ===== */
/*
 * 같은 요청("a.txt 지워줘")을 서로 다른 thread_id 로 세 번 실행하고,
 * 각각 approve / edit / reject 로 재개해서 결과를 비교하세요.
 * - edit 로는 삭제 대상을 "b.txt" 로 바꾸세요.
 * - reject 의 message 가 ToolMessage 로 어떻게 들어가는지 확인하세요.
 * - 마지막에 deleteLog 를 출력해서 실제로 무엇이 지워졌는지 검증하세요.
 *   (approve 는 a.txt, edit 는 b.txt, reject 는 아무것도 없어야 합니다)
 */

// 여기에 작성하세요.

/* ===== [문제 7] 오타 함정 재현하기 ===== */
/*
 * interruptOn 의 키를 "deleteFile"(잘못된 이름)로 주는 에이전트를 만들고,
 * "a.txt 지워줘" 를 시키세요.
 * 1) 중단이 걸리나요?
 * 2) deleteLog 에 무엇이 남았나요?
 * 3) 이 사고를 컴파일 타임에 막으려면 어떻게 해야 할까요?
 *    도구 이름을 상수로 뽑아 쓰는 코드로 고쳐 보세요.
 */

// 여기에 작성하세요.

/* ===== [문제 8] when 으로 조건부 승인 ===== */
/*
 * delete_file 을 이렇게 만드세요:
 * - 경로가 "/etc" 로 시작하면 승인을 요구한다
 * - 그 외 경로는 승인 없이 바로 지운다
 * (힌트: interruptOn 의 InterruptOnConfig 에는 when 이라는 술어(predicate)가 있습니다.
 *  true 를 반환하면 중단, false 면 자동 승인입니다.
 *  술어는 request.toolCall.args 로 인자를 볼 수 있습니다.)
 *
 * "a.txt 지워줘" 와 "/etc/passwd 지워줘" 를 각각 시켜서 동작 차이를 확인하세요.
 */

// 여기에 작성하세요.
