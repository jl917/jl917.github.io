/**
 * Step 15 — 장기 메모리와 Store · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-15-long-term-memory/exercise.ts
 *
 * 각 [문제 N] 아래를 직접 채우세요.
 * 문제 1~6 은 API 키 없이 풀 수 있습니다. 문제 7~8 은 모델/임베딩 키가 있으면 실제로 돌려 볼 수 있습니다.
 */
import "dotenv/config";

import { createAgent, tool, type ToolRuntime, FakeToolCallingModel } from "langchain";
import { InMemoryStore, MemorySaver, type BaseStore } from "@langchain/langgraph";
import * as z from "zod";

import { printSection, printKV, printJson } from "../project/src/lib/print.js";

/* ===== [문제 1] BaseStore 기본 연산 =====
 *
 * InMemoryStore 를 만들고 네임스페이스 ["user_1", "memories"] 에
 * 아래 3건을 저장한 뒤, search 로 전체를 읽어 key 목록을 출력하세요.
 *
 *   - key "m1": { content: "커피는 디카페인만", kind: "semantic" }
 *   - key "m2": { content: "2026-07-01 환불 문의", kind: "episodic" }
 *   - key "m3": { content: "회신은 항상 존댓말로", kind: "procedural" }
 *
 * 그다음 filter 를 써서 kind 가 "semantic" 인 것만 골라 출력하세요.
 */

printSection("[문제 1] BaseStore 기본 연산");
{
  // 여기에 작성하세요
}

/* ===== [문제 2] upsert 확인 =====
 *
 * 문제 1 의 store 에서 key "m1" 에 다른 value 를 put 하세요.
 * 그 후 search 결과의 개수가 늘어나는지 확인하고,
 * get 으로 읽은 Item 의 createdAt 과 updatedAt 을 함께 출력하세요.
 *
 * 질문: 같은 key 로 다시 put 하면 행이 늘어납니까, 덮어써집니까?
 * → (여기에 답을 주석으로)
 */

printSection("[문제 2] upsert");
{
  // 여기에 작성하세요
}

/* ===== [문제 3] 네임스페이스 격리 =====
 *
 * user_1 과 user_2 각각의 ["<userId>", "memories"] 에 기억을 1건씩 저장하세요.
 * 그다음 아래 두 가지를 출력해 비교하세요.
 *
 *   (A) search(["user_1", "memories"])  → user_1 것만 나와야 합니다
 *   (B) search([])                      → 네임스페이스 prefix 를 비우면 무엇이 나옵니까?
 *
 * (B) 의 결과가 왜 위험한지 주석으로 설명하세요.
 * → (여기에 설명)
 */

printSection("[문제 3] 네임스페이스 격리");
{
  // 여기에 작성하세요
}

/* ===== [문제 4] 시맨틱 검색의 조용한 퇴화 =====
 *
 * index 설정이 "없는" InMemoryStore 에 기억 2건을 넣고
 * search(ns, { query: "아무 질문" }) 을 호출하세요.
 *
 * - 에러가 납니까?
 * - 결과 Item 의 score 필드는 무엇입니까?
 * - store.indexConfig 는 무엇입니까?
 *
 * 이 셋을 출력하고, "시맨틱 검색이 동작 중인지"를 코드로 판별하는
 * 방법을 한 줄로 적으세요.
 * → (여기에 답)
 */

printSection("[문제 4] 인덱스 없는 시맨틱 검색");
{
  // 여기에 작성하세요
}

/* ===== [문제 5] runtime.store 로 저장하는 도구 =====
 *
 * 아래 조건을 만족하는 도구 save_note 를 만들고,
 * FakeToolCallingModel 로 강제 호출시켜 store 에 실제로 남는지 확인하세요.
 *
 *   - schema: { note: string }
 *   - contextSchema 로 userId 를 받는다
 *   - 네임스페이스는 [userId, "notes"]
 *   - key 는 crypto.randomUUID()
 *
 * 힌트: runtime.store 의 TS 타입은 langgraph 의 BaseStore 가 아닙니다.
 *       put 을 쓰려면 캐스팅이 필요합니다.
 */

printSection("[문제 5] store 에 쓰는 도구");
{
  // 여기에 작성하세요
}

/* ===== [문제 6] 메모리 갱신 전략 =====
 *
 * 같은 사실을 여러 번 저장하면 store 가 중복으로 부풀어 오릅니다.
 * 다음을 만족하는 함수 saveDedup(store, userId, content) 를 작성하세요.
 *
 *   - [userId, "memories"] 에서 기존 항목을 search
 *   - content 가 완전히 같은 항목이 있으면 그 key 를 재사용해 덮어쓴다
 *   - 없으면 새 key(UUID)로 저장한다
 *   - 저장 후 항목 수를 반환한다
 *
 * 같은 content 로 3번 호출해 항목 수가 1로 유지되는지 확인하세요.
 */

printSection("[문제 6] 중복 방지 저장");
{
  // 여기에 작성하세요
}

/* ===== [문제 7] checkpointer 와 store 를 모두 붙인 에이전트 =====
 *
 * checkpointer(MemorySaver)와 store(InMemoryStore)를 모두 붙인 에이전트를 만들고
 * 서로 다른 thread_id 두 개로 호출하세요. FakeToolCallingModel 을 써도 됩니다.
 *
 * 다음 두 가지를 출력해 비교하세요.
 *   - 스레드 B 의 messages 길이 (스레드 A 의 대화가 보이는가?)
 *   - store 에 저장된 기억 (스레드를 넘어 남는가?)
 *
 * 질문: store 만 붙이고 checkpointer 를 빼면 무엇이 깨집니까?
 * → (여기에 답)
 */

printSection("[문제 7] checkpointer + store");
{
  // 여기에 작성하세요
}

/* ===== [문제 8] 메모리를 읽는 개인 비서 =====
 *
 * save_memory / search_memory 두 도구를 가진 에이전트를 만들고,
 * 서로 다른 스레드에서 (1) 선호를 알려주고 (2) 추천을 받아 보세요.
 *
 *   - systemPrompt 에 "기억에 없는 것을 지어내지 마라"를 반드시 넣을 것
 *   - 네임스페이스에 userId 를 넣을 것
 *   - ANTHROPIC_API_KEY 가 없으면 건너뛰도록 가드할 것
 *
 * 실행 후, 2일차 응답이 1일차에 저장한 선호를 반영했는지 확인하세요.
 */

printSection("[문제 8] 개인 비서");
{
  // 여기에 작성하세요
}

printSection("끝");
