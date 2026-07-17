/**
 * Step 16 — 검색과 RAG · 연습문제
 * 실행: npx tsx docs/reference/langchain/step-16-retrieval-rag/exercise.ts
 *
 * 필요한 환경변수: ANTHROPIC_API_KEY, OPENAI_API_KEY
 * 추가 설치: npm install @langchain/classic @langchain/textsplitters
 *
 * 각 [문제 N] 블록 아래에 코드를 채워 넣고 실행하세요.
 * 정답은 solution.ts 에 있습니다. 먼저 스스로 풀어 보세요.
 */
import "dotenv/config";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, basename } from "node:path";

import { DirectoryLoader } from "@langchain/classic/document_loaders/fs/directory";
import { TextLoader } from "@langchain/classic/document_loaders/fs/text";
import { MemoryVectorStore } from "@langchain/classic/vectorstores/memory";
import { createRetrieverTool } from "@langchain/classic/tools/retriever";
import { RecursiveCharacterTextSplitter } from "@langchain/textsplitters";
import { OpenAIEmbeddings } from "@langchain/openai";
import type { BaseMessage } from "@langchain/core/messages";
import { createAgent, tool } from "langchain";
import * as z from "zod";

import { printSection, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");
requireEnv("OPENAI_API_KEY");

/* ===== 준비 — 이 부분은 이미 작성되어 있습니다 ===== */

const KB: Record<string, string> = {
  "refund.md": `# 환불 정책

Nimbus 클라우드의 유료 플랜은 결제일로부터 14일 이내에 환불을 요청할 수 있습니다. 환불은 요청 후 영업일 기준 5일 이내에 원결제 수단으로 처리됩니다. 단, 해당 기간에 누적 사용량이 무료 한도의 3배를 넘은 계정은 환불 대상에서 제외됩니다. 연간 결제 플랜은 잔여 기간을 일할 계산하여 부분 환불하며, 이 경우 이미 제공된 할인은 회수됩니다.

환불 요청은 대시보드의 결제 메뉴 또는 billing@nimbus.example 로 접수합니다. 환불이 완료되면 해당 계정은 즉시 Free 플랜으로 전환되며, 저장 공간이 Free 한도를 초과한 경우 30일 뒤 초과분이 삭제됩니다.`,

  "pricing.md": `# 요금제

Free 플랜은 월 5,000회 API 호출과 1GB 저장 공간을 무료로 제공합니다. Pro 플랜은 월 29달러이며 월 500,000회 API 호출과 100GB 저장 공간을 제공합니다. Enterprise 플랜은 별도 견적으로 운영되며 호출 한도가 없습니다.

Pro 플랜에서 월 한도를 초과하면 1,000회당 0.5달러가 추가 과금됩니다. 추가 과금은 다음 결제일에 합산 청구되며, 대시보드에서 상한선을 설정하면 그 금액에 도달했을 때 API 가 차단됩니다. 연간 결제를 선택하면 2개월치가 할인됩니다.`,

  "limits.md": `# 사용 한도와 레이트 리밋

모든 플랜에는 초당 100회의 레이트 리밋이 적용됩니다. 레이트 리밋을 초과하면 HTTP 429 응답과 함께 Retry-After 헤더가 반환되므로, 클라이언트는 그 값만큼 기다린 뒤 재시도해야 합니다. Enterprise 플랜은 요청 시 초당 1,000회까지 상향할 수 있습니다.

레이트 리밋은 API 키 단위가 아니라 계정 단위로 계산됩니다. 따라서 키를 여러 개 발급해도 한도가 늘어나지 않습니다. 초과 호출은 과금되지 않지만 429 응답도 월 호출 수에는 포함되지 않습니다.`,

  "support.md": `# 지원 정책

Free 플랜 사용자는 커뮤니티 포럼만 이용할 수 있으며 응답 시간을 보장하지 않습니다. Pro 플랜은 이메일 지원을 제공하며 첫 응답까지 영업일 기준 24시간이 소요됩니다. Enterprise 플랜은 전담 슬랙 채널과 1시간 이내 응답 SLA 를 제공합니다.

장애 신고는 플랜과 무관하게 status.nimbus.example 에서 확인할 수 있습니다. SLA 를 위반한 경우 Enterprise 고객은 해당 월 요금의 10%를 크레딧으로 보상받습니다.`,
};

const kbDir = mkdtempSync(join(tmpdir(), "nimbus-ex-"));
for (const [name, body] of Object.entries(KB)) {
  writeFileSync(join(kbDir, name), body, "utf8");
}

const embeddings = new OpenAIEmbeddings({ model: "text-embedding-3-small" });
const model = "anthropic:claude-sonnet-4-6";

/**
 * 지식 베이스를 인덱싱합니다.
 * [문제 1] 과 [문제 7] 에서 chunkSize / chunkOverlap 을 바꿔 가며 부를 수 있게
 * 파라미터로 열어 두었습니다. files 를 주면 그 파일만 인덱싱합니다([문제 8]).
 */
async function buildIndex(
  chunkSize = 300,
  chunkOverlap = 60,
  files?: string[],
): Promise<MemoryVectorStore> {
  const loader = new DirectoryLoader(kbDir, { ".md": (p) => new TextLoader(p) });
  let docs = await loader.load();
  if (files !== undefined) {
    docs = docs.filter((d) => files.includes(basename(String(d.metadata["source"]))));
  }
  const splitter = new RecursiveCharacterTextSplitter({ chunkSize, chunkOverlap });
  const chunks = await splitter.splitDocuments(docs);
  return MemoryVectorStore.fromDocuments(chunks, embeddings);
}

/* ===== [문제 1] 청킹이 검색을 망가뜨리는 것 보기 =====
 *
 * chunkSize: 300, chunkOverlap: 60 인덱스와
 * chunkSize: 60,  chunkOverlap: 0  인덱스를 각각 만들고,
 * 같은 질문 "환불은 며칠 만에 처리되나요?" 로 검색해 상위 2개 청크를 출력하세요.
 *
 * 후자에서 답("영업일 기준 5일")이 어떻게 쪼개지는지 확인하세요.
 * 검색된 청크에 답이 들어 있나요?
 */

printSection("[문제 1] 청킹이 검색을 망가뜨리는 것 보기");

// 여기에 코드를 작성하세요.

/* ===== [문제 2] 임계값을 데이터로 정하기 =====
 *
 * 기본 인덱스(300/60)에 아래 질문들을 던져 similaritySearchWithScore 로 최고 점수를
 * 각각 출력하세요.
 *
 *   무관한 질문: "김치찌개 레시피", "축구 경기 결과", "비트코인 시세"
 *   관련 질문:   "환불 기간", "Pro 플랜 가격", "레이트 리밋"
 *
 * 그리고 이 인덱스에 적절한 임계값을 정해 주석으로 근거와 함께 쓰세요.
 * (무관한 질문의 점수가 0이 아니라는 것에 주목하세요)
 */

printSection("[문제 2] 임계값을 데이터로 정하기");

// 여기에 코드를 작성하세요.

// → 내가 정한 임계값: ____ , 근거:

/* ===== [문제 3] MMR 이 정말 다른가 =====
 *
 * 같은 벡터 스토어에서 retriever 두 개를 만들어 "플랜별로 뭐가 다른가요?" 를 던지고
 * 반환된 source 목록을 비교하세요.
 *
 *   (A) 기본:  asRetriever({ k: 3 })
 *   (B) MMR:   asRetriever({ searchType: "mmr", k: 3, searchKwargs: { fetchK: ?, lambda: ? } })
 *
 * 힌트: 차이를 보려면 fetchK 가 k 보다 충분히 커야 합니다.
 *       fetchK 를 k 와 같게 두면 고를 후보가 없어 결과가 똑같이 나옵니다.
 */

printSection("[문제 3] MMR 이 정말 다른가");

// 여기에 코드를 작성하세요.

/* ===== [문제 4] 검색을 건너뛴 것을 감지하기 =====
 *
 * (a) createRetrieverTool 로 만든 도구 하나를 가진 에이전트를 만드세요.
 * (b) "안녕하세요" 와 "환불 정책 알려줘" 를 각각 던지고,
 *     tool_calls 가 있는 AI 메시지 수를 세어 출력하세요.
 * (c) assertSearched(messages) 를 작성하세요 — 검색 없이 답했으면 경고를 찍습니다.
 *
 * 힌트: instanceof AIMessage 대신 구조적 검사를 쓰세요.
 *       (m as { tool_calls?: unknown[] }).tool_calls
 *       @langchain/core 가 두 벌 설치되면 instanceof 가 조용히 false 가 됩니다.
 */

printSection("[문제 4] 검색을 건너뛴 것을 감지하기");

function assertSearched(_messages: BaseMessage[]): boolean {
  // 여기에 코드를 작성하세요.
  return false;
}

// 여기에 코드를 작성하세요.

/* ===== [문제 5] 줄 번호까지 인용하는 도구 =====
 *
 * metadata.loc.lines.from / .to 까지 포함하는 검색 도구를 직접 만드세요.
 *
 * 반환 형식:
 *   <document source="refund.md" lines="3-5">
 *   ...본문...
 *   </document>
 *
 * 힌트: splitDocuments 가 metadata.loc 를 넣어 줍니다. 구조는 이렇습니다.
 *       { loc: { lines: { from: 1, to: 5 } } }
 *       타입이 unknown 이므로 안전하게 좁혀서 읽어야 합니다.
 */

printSection("[문제 5] 줄 번호까지 인용하는 도구");

// 여기에 코드를 작성하세요.

/* ===== [문제 6] 인용 검증 =====
 *
 * 에이전트가 반환한 sources 중 실제로 검색되지 않은 파일명이 있으면 에러를 던지세요.
 *
 * (a) responseFormat 으로 { answer, sources } 를 받는 에이전트를 만드세요.
 * (b) 검색된 문서 집합을 따로 모아 두세요. (힌트: 도구 안에서 기록)
 * (c) sources ⊆ 검색된 집합 인지 검사하고, 아니면 에러를 던지세요.
 *
 * 그리고 이 검증이 왜 필요한지 주석으로 설명하세요.
 */

printSection("[문제 6] 인용 검증");

// 여기에 코드를 작성하세요.

// → 이 검증이 필요한 이유:

/* ===== [문제 7] 인덱싱에 캐시가 없다는 것 =====
 *
 * buildIndex() 를 두 번 호출하고 각각 걸린 시간을 측정해 출력하세요.
 * 두 번째도 첫 번째와 비슷하게 걸린다는 것을 확인하세요.
 *
 * 그리고 이것이 프로덕션에서 왜 문제인지 주석으로 쓰세요.
 * (힌트: 청크 5개 → 5만 개, 인스턴스 1개 → 10개일 때를 상상해 보세요)
 */

printSection("[문제 7] 인덱싱에 캐시가 없다는 것");

// 여기에 코드를 작성하세요.

// → 프로덕션에서 문제가 되는 이유:

/* ===== [문제 8] (심화) 도구를 소스별로 쪼개기 =====
 *
 * 지식 소스를 둘로 나눠 도구를 두 개 만드세요.
 *
 *   search_billing   → refund.md, pricing.md
 *   search_technical → limits.md, support.md
 *
 * (힌트: buildIndex 의 세 번째 인자 files 를 쓰세요)
 *
 * 에이전트에게 아래 둘을 던져 각각 어느 도구를 골랐는지 출력하세요.
 *   "Pro 플랜 환불되나요?"
 *   "레이트 리밋 얼마예요?"
 *
 * 도구 선택은 모델 판단이라 비결정적입니다. 기대와 다르게 골랐다면
 * description 을 고쳐 가며 선택이 어떻게 바뀌는지 관찰하세요.
 */

printSection("[문제 8] 도구를 소스별로 쪼개기");

// 여기에 코드를 작성하세요.

console.log(`\n(실습용 지식 베이스 위치: ${kbDir})`);

// 아래는 사용하지 않는 import 로 인한 오류를 막기 위한 것입니다.
// 문제를 풀면서 실제로 쓰게 되면 지워도 됩니다.
void createAgent;
void createRetrieverTool;
void tool;
void z;
void model;
