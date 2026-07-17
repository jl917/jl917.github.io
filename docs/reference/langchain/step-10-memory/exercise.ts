/**
 * Step 10 — 단기 메모리와 스레드 · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-10-memory/exercise.ts
 *
 * 각 [문제 N] 블록 아래가 비어 있습니다. 직접 채워 넣고 실행해 보세요.
 * 파일을 그대로 실행하면 아무것도 출력되지 않습니다. 정상입니다.
 *
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어 보세요.
 */
import "dotenv/config";

import { createAgent, createMiddleware, tool } from "langchain";
import { MemorySaver, REMOVE_ALL_MESSAGES } from "@langchain/langgraph";
import { RemoveMessage, type BaseMessage } from "@langchain/core/messages";
import * as z from "zod";

import { printSection, printMessages, printKV } from "../project/src/lib/print.js";

const MODEL = "anthropic:claude-sonnet-4-6";

/* ===== [문제 1] 조용한 실패 재현 ===== */
/*
 * checkpointer 를 주지 않은 에이전트에 thread_id 를 주고 두 번 invoke 하세요.
 *
 * - 1턴: "제 이름은 홍길동입니다."
 * - 2턴: "제 이름이 뭐죠?"
 *
 * 그다음 checkpointer: new MemorySaver() 만 추가한 에이전트로 똑같이 하세요.
 * 두 경우의 (a) 답변과 (b) result.messages.length 를 나란히 출력해서
 * "에러 없이 조용히 다르다" 를 눈으로 확인하세요.
 *
 * 확인할 것: checkpointer 없는 쪽의 2턴 messages 길이는 몇인가요?
 */

async function exercise1(): Promise<void> {
  printSection("[문제 1] checkpointer 유무 비교");

  // 여기에 작성하세요
}

/* ===== [문제 2] 스레드 격리 확인 ===== */
/*
 * MemorySaver 하나를 공유하는 에이전트 1개로, thread_id 를 3개 만들어
 * 각각 다른 이름("가", "나", "다")을 알려준 뒤 각 스레드에서 이름을 되물으세요.
 *
 * 그다음 getState 로 각 스레드의 messages 개수를 출력해서
 * 스레드마다 독립된 히스토리를 갖는다는 것을 보이세요.
 *
 * 마지막으로 한 번도 쓴 적 없는 thread_id 로 getState 를 호출하면
 * 무엇이 나오는지 출력하세요. (에러인가요? 아닌가요?)
 */

async function exercise2(): Promise<void> {
  printSection("[문제 2] 스레드 격리");

  // 여기에 작성하세요
}

/* ===== [문제 3] getStateHistory 로 체크포인트 세기 ===== */
/*
 * 도구 없이 에이전트를 만들고 2턴 대화한 뒤, getStateHistory 를 for await 로 돌려
 * 각 스냅샷의 step / source / next / messages 개수 / checkpoint_id 앞 8자리를
 * 한 줄씩 출력하세요.
 *
 * 질문:
 *  - 히스토리의 index 0 은 가장 오래된 것인가요, 가장 최신인가요?
 *  - metadata.source 에 어떤 값들이 보이나요?
 *  - 2턴 대화인데 체크포인트가 몇 개 생겼나요? 왜 그럴까요?
 */

async function exercise3(): Promise<void> {
  printSection("[문제 3] 체크포인트 히스토리");

  // 여기에 작성하세요
}

/* ===== [문제 4] updateState 의 append 함정 ===== */
/*
 * 1턴 대화한 스레드에 updateState 로
 *   { messages: [{ role: "user", content: "덮어쓰기 시도" }] }
 * 를 넣으세요.
 *
 * (a) updateState 전후의 messages 개수를 출력하세요. 몇 개가 되었나요? 왜죠?
 * (b) 이번엔 "정말로 messages 를 이 하나로만 만들기" 를 하세요.
 *     힌트: RemoveMessage 와 REMOVE_ALL_MESSAGES 를 씁니다.
 * (c) (b) 이후 개수가 1이 되는지 확인하세요.
 */

async function exercise4(): Promise<void> {
  printSection("[문제 4] updateState 와 리듀서");

  // 여기에 작성하세요
}

/* ===== [문제 5] 타임트래블 — 포크 ===== */
/*
 * 3턴 대화를 만드세요.
 *   1턴: "제 목표는 마라톤 완주입니다."
 *   2턴: "목표 달성을 위한 팁 하나만요."
 *   3턴: "제 목표가 뭐라고 했죠?"
 *
 * 그다음:
 *  (a) getStateHistory 에서 "1턴이 끝난 시점"(next 가 []이고 messages 가 2개인 스냅샷)을 찾으세요.
 *  (b) 그 시점 config 로 updateState 를 호출해
 *      "정정합니다. 제 목표는 금연입니다." 를 끼워 넣고 반환된 forkConfig 를 받으세요.
 *  (c) forkConfig 로 "제 목표가 뭐죠?" 를 물어 답이 바뀌는지 확인하세요.
 *  (d) 원본 thread_id 로 getState 를 해서 원본이 안 망가졌는지 확인하세요.
 */

async function exercise5(): Promise<void> {
  printSection("[문제 5] 포크");

  // 여기에 작성하세요
}

/* ===== [문제 6] thread_id 재사용 오염 ===== */
/*
 * 같은 checkpointer 를 쓰는 에이전트에서 thread_id "shared" 로
 *   1) "제 이름은 앨리스입니다." 를 보내고
 *   2) (다른 사용자인 척) "제 이름이 뭐죠?" 를 보내세요.
 *
 * 2번 응답에 "앨리스" 가 나오면 스레드 오염입니다.
 * 이 문제를 고치는 방법을 코드로 보이세요.
 * (힌트: thread_id 를 매번 새로 만듭니다. crypto.randomUUID() 를 쓰세요.)
 */

async function exercise6(): Promise<void> {
  printSection("[문제 6] thread_id 재사용 오염");

  // 여기에 작성하세요
}

/* ===== [문제 7] 비용 측정 ===== */
/*
 * checkpointer 를 붙인 에이전트로 6턴 대화하며, 매 턴 마지막 AIMessage 의
 * usage_metadata.input_tokens 를 출력하고 누적 합계도 함께 출력하세요.
 *
 * 그다음 practice.ts [10-8] 의 TrimMessages 미들웨어를 붙인 에이전트로
 * 똑같이 6턴 돌려서 두 누적 합계를 비교하세요.
 *
 * 질문: 트리밍으로 얻은 것과 잃은 것은 각각 무엇인가요? 주석으로 답하세요.
 */

async function exercise7(): Promise<void> {
  printSection("[문제 7] 토큰 비용 비교");

  // 여기에 작성하세요
}

/* ===== [문제 8] 도구 결과도 저장되는가 ===== */
/*
 * 간단한 도구(get_weather)를 붙인 에이전트를 checkpointer 와 함께 만들고,
 * 도구를 부르게 만드는 질문을 1턴 던지세요.
 *
 * 그다음 getState 로 values.messages 를 꺼내 각 메시지의 getType() 을 출력하세요.
 * ToolMessage 가 체크포인트에 남아 있나요?
 *
 * 이어서 다음 턴에 "방금 조회한 날씨가 뭐였죠?" 를 물어
 * 도구를 다시 부르지 않고도 답하는지 확인하세요.
 */

async function exercise8(): Promise<void> {
  printSection("[문제 8] 도구 결과의 저장");

  // 여기에 작성하세요
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await exercise1();
  await exercise2();
  await exercise3();
  await exercise4();
  await exercise5();
  await exercise6();
  await exercise7();
  await exercise8();
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
