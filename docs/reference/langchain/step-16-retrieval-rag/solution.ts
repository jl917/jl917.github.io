/**
 * Step 16 — 검색과 RAG · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-16-retrieval-rag/solution.ts
 *
 * 필요한 환경변수: ANTHROPIC_API_KEY, OPENAI_API_KEY
 * 추가 설치: npm install @langchain/classic @langchain/textsplitters
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
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

/* ===== 준비 ===== */

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

const kbDir = mkdtempSync(join(tmpdir(), "nimbus-sol-"));
for (const [name, body] of Object.entries(KB)) {
  writeFileSync(join(kbDir, name), body, "utf8");
}

const embeddings = new OpenAIEmbeddings({ model: "text-embedding-3-small" });
const model = "anthropic:claude-sonnet-4-6";

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

const src = (d: { metadata: Record<string, unknown> }): string =>
  basename(String(d.metadata["source"]));

/* ===== [정답 1] 청킹이 검색을 망가뜨리는 것 보기 ===== */

printSection("[정답 1] 청킹이 검색을 망가뜨리는 것 보기");

const QUESTION_1 = "환불은 며칠 만에 처리되나요?";

// (A) 제대로 된 청킹
const goodIndex = await buildIndex(300, 60);
const goodHits = await goodIndex.similaritySearchWithScore(QUESTION_1, 2);

console.log("(A) chunkSize: 300, chunkOverlap: 60");
for (const [doc, score] of goodHits) {
  const hasAnswer = doc.pageContent.includes("5일");
  console.log(`  ${score.toFixed(4)}  ${src(doc)}  답 포함: ${hasAnswer ? "O" : "X"}`);
  console.log(`         ${doc.pageContent.slice(0, 60).replace(/\n/g, " ")}...`);
}

// (B) 망가진 청킹 — 문장이 두 동강 납니다.
const badIndex = await buildIndex(60, 0);
const badHits = await badIndex.similaritySearchWithScore(QUESTION_1, 2);

console.log("\n(B) chunkSize: 60, chunkOverlap: 0");
for (const [doc, score] of badHits) {
  const hasAnswer = doc.pageContent.includes("5일");
  console.log(`  ${score.toFixed(4)}  ${src(doc)}  답 포함: ${hasAnswer ? "O" : "X"}`);
  console.log(`         ${JSON.stringify(doc.pageContent)}`);
}

/* 해설:
 * (B) 에서 1위로 올라온 청크에는 "환불"이라는 단어가 있어 유사도가 높지만,
 * 정작 답인 "영업일 기준 5일"은 옆 청크에 있습니다. 즉 "답 포함: X" 입니다.
 *
 * 이것이 청킹 실패 → 검색 실패 → 환각으로 이어지는 사슬의 첫 고리입니다.
 * 모델은 검색된 청크를 근거로 삼는데 거기에 답이 없으니, 프롬프트가 약하면
 * "환불은 보통 7일 정도 걸립니다" 같은 걸 지어냅니다.
 *
 * 주의: 실제로 어떤 청크가 1위가 되는지는 임베딩 모델에 달려 있어
 * 매번 똑같지 않을 수 있습니다. 핵심은 "검색된 청크에 답이 있는가"를
 * 코드로 확인하는 습관(위의 hasAnswer)입니다. 이게 recall@k 측정의 씨앗입니다.
 */

/* ===== [정답 2] 임계값을 데이터로 정하기 ===== */

printSection("[정답 2] 임계값을 데이터로 정하기");

const store = await buildIndex(300, 60); // 이후 문제에서 계속 재사용합니다.

const offTopicQs = ["김치찌개 레시피", "축구 경기 결과", "비트코인 시세"];
const onTopicQs = ["환불 기간", "Pro 플랜 가격", "레이트 리밋"];

async function topScore(q: string): Promise<number> {
  const r = await store.similaritySearchWithScore(q, 1);
  return r[0]?.[1] ?? 0;
}

const offScores: number[] = [];
console.log("무관한 질문:");
for (const q of offTopicQs) {
  const s = await topScore(q);
  offScores.push(s);
  console.log(`  ${s.toFixed(4)}  "${q}"`);
}

const onScores: number[] = [];
console.log("\n관련 질문:");
for (const q of onTopicQs) {
  const s = await topScore(q);
  onScores.push(s);
  console.log(`  ${s.toFixed(4)}  "${q}"`);
}

const maxOff = Math.max(...offScores);
const minOn = Math.min(...onScores);
console.log(`\n무관 최고점: ${maxOff.toFixed(4)}`);
console.log(`관련 최저점: ${minOn.toFixed(4)}`);
console.log(
  maxOff < minOn
    ? `→ 두 구간이 분리됨. 임계값은 그 사이: ${((maxOff + minOn) / 2).toFixed(4)}`
    : "→ 두 구간이 겹침! 임계값만으로는 못 거릅니다. 리랭킹이 필요합니다.",
);

/* 해설:
 * 이 문제는 정답 숫자가 없습니다. 확인할 것은 두 가지입니다.
 *
 * 1. 무관한 질문의 점수가 0이 아닙니다. 대개 0.1~0.3 대가 나옵니다.
 *    "점수가 0보다 크면 관련 있다"는 직관은 틀렸습니다.
 *
 * 2. 임계값은 "관련 질문의 최저점"과 "무관 질문의 최고점" 사이에 있어야 합니다.
 *    그런데 이 둘이 겹치면 임계값으로는 절대 못 가릅니다 — 무관한 걸 버리려면
 *    관련 있는 것도 같이 버려야 하니까요. 그때가 리랭킹(cross-encoder)이
 *    필요해지는 지점입니다.
 *
 * 그래서 "0.7 이상이면 관련 있음" 같은 보편 상수는 존재하지 않습니다.
 * 반드시 여러분의 데이터에서 이 분포를 직접 찍어 보고 정하세요.
 */

/* ===== [정답 3] MMR 이 정말 다른가 ===== */

printSection("[정답 3] MMR 이 정말 다른가");

const QUESTION_3 = "플랜별로 뭐가 다른가요?";

const plainRetriever = store.asRetriever({ k: 3 });
const plainDocs = await plainRetriever.invoke(QUESTION_3);
console.log(`(A) 기본 k=3        → ${plainDocs.map(src).join(", ")}`);

// fetchK 를 k 의 3배 가까이 잡아야 MMR 이 고를 여지가 생깁니다.
const mmrRetriever = store.asRetriever({
  searchType: "mmr",
  k: 3,
  searchKwargs: { fetchK: 8, lambda: 0.5 },
});
const mmrDocs = await mmrRetriever.invoke(QUESTION_3);
console.log(`(B) MMR k=3 fetchK=8 → ${mmrDocs.map(src).join(", ")}`);

// fetchK 를 k 와 같게 두면 — 후보가 3개뿐이라 3개를 다 골라야 합니다.
const brokenMmr = store.asRetriever({
  searchType: "mmr",
  k: 3,
  searchKwargs: { fetchK: 3, lambda: 0.5 },
});
const brokenDocs = await brokenMmr.invoke(QUESTION_3);
console.log(`(C) MMR k=3 fetchK=3 → ${brokenDocs.map(src).join(", ")}  ← 기본과 같은 집합`);

/* 해설:
 * (C) 가 이 문제의 핵심입니다. fetchK === k 면 MMR 은 아무 일도 하지 않습니다.
 * 후보를 3개 가져와 3개를 골라야 하니 "고른다"는 행위 자체가 없습니다.
 * (순서만 바뀔 수 있습니다.)
 *
 * MMR 을 켜 놓고 "왜 똑같지?" 하는 경우의 대부분이 이것입니다.
 * fetchK 는 k 의 3~5배로 잡으세요.
 *
 * 이 실습의 지식 베이스는 문서가 4개뿐이라 (A)와 (B)의 차이가 작을 수 있습니다.
 * overlap 을 크게 준 큰 문서 집합일수록 MMR 의 효과가 커집니다 —
 * 거의 같은 내용의 청크가 상위를 도배하는 것을 막아 주니까요.
 */

/* ===== [정답 4] 검색을 건너뛴 것을 감지하기 ===== */

printSection("[정답 4] 검색을 건너뛴 것을 감지하기");

/**
 * tool_calls 가 있는 AI 메시지가 하나라도 있으면 true.
 *
 * instanceof AIMessage 를 쓰지 않은 이유:
 * @langchain/core 가 두 벌 설치되면 클래스 아이덴티티가 갈라져
 * instanceof 가 조용히 false 가 됩니다(Step 01 의 함정).
 * 그래서 "그 필드가 실제로 있는지"만 보는 구조적 검사를 씁니다.
 * project/src/lib/print.ts 도 같은 이유로 같은 방식을 씁니다.
 */
function countToolCalls(messages: BaseMessage[]): number {
  return messages.filter(
    (m) => ((m as { tool_calls?: unknown[] }).tool_calls?.length ?? 0) > 0,
  ).length;
}

function assertSearched(messages: BaseMessage[]): boolean {
  const n = countToolCalls(messages);
  if (n === 0) {
    console.warn("  ⚠️ 검색 없이 답변함 — 환각 가능성");
    return false;
  }
  return true;
}

const searchTool = createRetrieverTool(store.asRetriever({ k: 3 }), {
  name: "search_nimbus_docs",
  description:
    "Nimbus 클라우드의 공식 문서(요금제, 환불 정책, 사용 한도, 지원 정책)를 검색한다. " +
    "Nimbus 의 정책·가격·한도에 관한 질문에는 반드시 이 도구를 먼저 호출하라.",
});

const agent4 = createAgent({
  model,
  tools: [searchTool],
  systemPrompt:
    "너는 Nimbus 클라우드 고객 지원 담당자다. " +
    "사실 질문에는 반드시 search_nimbus_docs 를 호출하라. 없으면 모른다고 답하라.",
});

for (const q of ["안녕하세요", "환불 정책 알려줘"]) {
  const r = await agent4.invoke({ messages: [{ role: "user", content: q }] });
  console.log(`\nQ: ${q}`);
  console.log(`  메시지 ${r.messages.length}개 / 검색 호출 ${countToolCalls(r.messages)}회`);
  assertSearched(r.messages);
}

/* 해설:
 * "안녕하세요" 는 검색 0회가 정상입니다 — 그래서 경고가 찍힙니다.
 * 즉 assertSearched 를 무조건 에러로 만들면 안 됩니다. 인사에도 화를 내니까요.
 *
 * 실무에서는 "이 질문이 사실 질문인가"를 먼저 분류하고,
 * 사실 질문인데 검색을 안 했을 때만 문제 삼습니다.
 * 아니면 아예 고전 RAG 를 써서 재량 자체를 없애는 게 낫습니다.
 *
 * 결과는 비결정적입니다. 모델이 "환불 정책 알려줘"에 검색 없이 답할 수도 있고,
 * 그게 정확히 16-8 의 마지막 함정입니다.
 */

/* ===== [정답 5] 줄 번호까지 인용하는 도구 ===== */

printSection("[정답 5] 줄 번호까지 인용하는 도구");

/** metadata.loc 는 unknown 이므로 안전하게 좁혀서 읽습니다. */
function readLines(metadata: Record<string, unknown>): string {
  const loc = metadata["loc"];
  if (typeof loc !== "object" || loc === null) return "?";
  const lines = (loc as { lines?: unknown }).lines;
  if (typeof lines !== "object" || lines === null) return "?";
  const { from, to } = lines as { from?: unknown; to?: unknown };
  if (typeof from !== "number" || typeof to !== "number") return "?";
  return `${from}-${to}`;
}

const citedSearchWithLines = tool(
  async ({ query }) => {
    const results = await store.similaritySearchWithScore(query, 3);
    if (results.length === 0) return "검색 결과가 없습니다.";
    return results
      .map(
        ([doc, score]) =>
          `<document source="${src(doc)}" lines="${readLines(doc.metadata)}" score="${score.toFixed(3)}">\n` +
          `${doc.pageContent}\n</document>`,
      )
      .join("\n\n");
  },
  {
    name: "search_with_lines",
    description:
      "Nimbus 공식 문서를 검색한다. 각 결과에 source(파일명), lines(줄 범위), score(유사도)가 붙는다.",
    schema: z.object({
      query: z.string().describe("검색어. 핵심 키워드로 다듬어라."),
    }),
  },
);

console.log(await citedSearchWithLines.invoke({ query: "환불 기간" }));

/* 해설:
 * splitDocuments 가 넣어 주는 metadata.loc.lines 는 { from, to } 구조입니다.
 * 원본 문서에서 이 청크가 몇 번째 줄이었는지를 알려 줍니다.
 *
 * 이 값이 인용에서 값진 이유: 사용자가 "정말 그렇게 쓰여 있나?"를
 * refund.md 3~5줄로 바로 넘어가 확인할 수 있게 됩니다.
 * PDF 라면 같은 자리에 page 번호를 넣습니다(16-2 의 loadPdfPages 참고).
 *
 * metadata 를 unknown 으로 읽는 게 번거로워 보이지만, tsconfig 의
 * noUncheckedIndexedAccess 가 켜져 있으면 이렇게 좁혀야 통과합니다.
 * 그리고 이건 좋은 일입니다 — loc 가 없는 Document(로더가 직접 만든 것)를
 * 넣었을 때 조용히 "undefined-undefined" 가 찍히는 걸 막아 줍니다.
 */

/* ===== [정답 6] 인용 검증 ===== */

printSection("[정답 6] 인용 검증");

// 도구가 실제로 무엇을 반환했는지 기록해 둡니다.
// 이게 없으면 "모델이 지어낸 출처"와 "진짜 출처"를 구분할 방법이 없습니다.
const retrievedSources = new Set<string>();

const trackedSearch = tool(
  async ({ query }) => {
    const results = await store.similaritySearchWithScore(query, 3);
    for (const [doc] of results) retrievedSources.add(src(doc));
    return results
      .map(([doc, score]) => `<document source="${src(doc)}" score="${score.toFixed(3)}">\n${doc.pageContent}\n</document>`)
      .join("\n\n");
  },
  {
    name: "search_docs",
    description: "Nimbus 공식 문서를 검색한다. 결과에 source 와 score 가 붙는다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const agent6 = createAgent({
  model,
  tools: [trackedSearch],
  systemPrompt: [
    "너는 Nimbus 고객 지원 담당자다.",
    "사실 질문에는 반드시 search_docs 를 호출하라.",
    "근거로 쓴 문서의 파일명만 sources 에 담아라. 지어내지 마라.",
    "근거가 없으면 '문서에서 찾을 수 없습니다'라고 답하고 sources 는 빈 배열로 두라.",
  ].join("\n"),
  responseFormat: z.object({
    answer: z.string().describe("답변"),
    sources: z.array(z.string()).describe("근거로 사용한 문서의 파일명 목록"),
  }),
});

retrievedSources.clear();
const r6 = await agent6.invoke({
  messages: [{ role: "user", content: "연간 결제를 중간에 해지하면 환불받을 수 있나요?" }],
});

console.log(JSON.stringify(r6.structuredResponse, null, 2));
console.log(`실제로 검색된 문서: ${[...retrievedSources].join(", ") || "(없음)"}`);

const fake = r6.structuredResponse.sources.filter((s) => !retrievedSources.has(s));
if (fake.length > 0) {
  console.error(`  ❌ 존재하지 않는 출처를 인용함: ${fake.join(", ")}`);
} else {
  console.log("  ✅ 모든 인용이 실제 검색 결과에 존재합니다.");
}

/* 해설:
 * → 이 검증이 필요한 이유:
 *
 * "출처를 붙여라"라고 지시하면 모델은 출처를 붙입니다. 그런데 그게 진짜라는
 * 보장은 어디에도 없습니다. 컨텍스트에 없는 policy_2024.pdf 같은 파일명을
 * 그럴듯하게 지어낼 수 있습니다.
 *
 * 그리고 이건 인용이 아예 없는 것보다 더 위험합니다. 사용자는 인용이 붙어
 * 있으면 검증됐다고 믿기 때문입니다. 환각에 신뢰의 외피를 씌워 주는 셈입니다.
 *
 * 그래서 sources ⊆ 실제 검색된 집합 인지를 반드시 코드로 검사해야 합니다.
 * 이 검사는 LLM 없이 Set 하나로 됩니다 — 공짜입니다.
 *
 * 실무에서는 fake.length > 0 일 때 에러를 던져 응답을 막고 로그를 남깁니다.
 * 여기서는 실행이 멈추지 않게 console.error 로 두었습니다.
 */

/* ===== [정답 7] 인덱싱에 캐시가 없다는 것 ===== */

printSection("[정답 7] 인덱싱에 캐시가 없다는 것");

const t1 = Date.now();
await buildIndex();
const first = Date.now() - t1;

const t2 = Date.now();
await buildIndex();
const second = Date.now() - t2;

console.log(`1회차: ${first}ms`);
console.log(`2회차: ${second}ms`);
console.log(`→ 2회차가 1회차의 ${((second / first) * 100).toFixed(0)}% — 캐시가 없습니다.`);

/* 해설:
 * → 프로덕션에서 문제가 되는 이유:
 *
 * buildIndex() 는 매번 (1) 파일을 다시 읽고 (2) 다시 자르고 (3) 다시 임베딩합니다.
 * 두 번째 호출이 첫 번째와 비슷하게 걸리는 게 그 증거입니다.
 *
 * 여기선 청크 5개라 몇백 ms 입니다. 그런데 이 숫자에 곱셈을 해 보세요.
 *
 *   - 청크 5개 → 5만 개: 임베딩 API 를 5만 번(배치로 나눠서). 레이트 리밋에
 *     걸려 수십 분. 서버가 그동안 요청을 못 받습니다.
 *   - 인스턴스 1개 → 10개: 오토스케일링으로 뜨는 인스턴스마다 전부 다시.
 *     비용도 시간도 10배. 게다가 배포할 때마다 반복됩니다.
 *   - 최악: 인덱싱을 요청 핸들러 안에 두면 질문 한 번에 이게 다 돕니다.
 *
 * 처방은 두 가지입니다.
 *   1. 인덱싱을 요청 경로 밖으로 — 배치 잡(npm run reindex)이나 시작 시 1회.
 *   2. 인덱스를 프로세스 밖으로 — pgvector 같은 진짜 벡터 DB에 저장하면
 *      재시작해도 살아 있고, 인스턴스 10개가 하나를 공유합니다.
 *
 * MemoryVectorStore 는 학습용입니다. 이 측정이 그 이유입니다.
 */

/* ===== [정답 8] 도구를 소스별로 쪼개기 ===== */

printSection("[정답 8] 도구를 소스별로 쪼개기");

const billingStore = await buildIndex(300, 60, ["refund.md", "pricing.md"]);
const technicalStore = await buildIndex(300, 60, ["limits.md", "support.md"]);

// 어느 도구가 불렸는지 기록합니다.
const calledTools: string[] = [];

const searchBilling = tool(
  async ({ query }) => {
    calledTools.push("search_billing");
    const docs = await billingStore.similaritySearch(query, 3);
    return docs.map((d) => `[${src(d)}] ${d.pageContent}`).join("\n\n");
  },
  {
    name: "search_billing",
    // description 이 곧 라우팅 규칙입니다. "무엇이 들어 있는지"를 구체적으로.
    description:
      "결제 관련 문서를 검색한다: 환불 정책(환불 기간, 부분 환불, 환불 절차)과 " +
      "요금제(플랜별 가격, 추가 과금, 할인). 돈과 관련된 질문에 사용하라.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const searchTechnical = tool(
  async ({ query }) => {
    calledTools.push("search_technical");
    const docs = await technicalStore.similaritySearch(query, 3);
    return docs.map((d) => `[${src(d)}] ${d.pageContent}`).join("\n\n");
  },
  {
    name: "search_technical",
    description:
      "기술 문서를 검색한다: 사용 한도와 레이트 리밋(429, Retry-After, 초당 호출 수)과 " +
      "지원 정책(응답 시간, SLA, 지원 채널). 기술적 제약이나 지원에 관한 질문에 사용하라.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const routerAgent = createAgent({
  model,
  tools: [searchBilling, searchTechnical],
  systemPrompt:
    "너는 Nimbus 고객 지원 담당자다. 질문의 성격에 맞는 검색 도구를 골라 호출하라. " +
    "필요하면 둘 다 호출해도 된다. 근거가 없으면 모른다고 답하라.",
});

for (const q of ["Pro 플랜 환불되나요?", "레이트 리밋 얼마예요?"]) {
  calledTools.length = 0;
  const r = await routerAgent.invoke({ messages: [{ role: "user", content: q }] });
  console.log(`\nQ: ${q}`);
  console.log(`  고른 도구: ${calledTools.join(", ") || "(없음 — 검색을 건너뜀)"}`);
  console.log(`  A: ${(r.messages.at(-1)?.text ?? "").slice(0, 100)}...`);
}

/* 해설:
 * 기대: "Pro 플랜 환불되나요?" → search_billing
 *       "레이트 리밋 얼마예요?" → search_technical
 *
 * 도구를 쪼개서 얻는 것:
 *   1. 검색 공간이 줄어듭니다. search_billing 은 문서 2개만 뒤지므로
 *      k=3 이 훨씬 정확해집니다. 전체를 뒤질 때 상위 3개에 무관한 문서가
 *      끼어들 자리가 없습니다.
 *   2. 소스별로 다른 k, 다른 필터, 다른 권한을 걸 수 있습니다.
 *      (예: search_hr 은 인사팀만)
 *   3. 로그에 "어느 소스를 몇 번 뒤졌나"가 남습니다. 위의 calledTools 가 그것입니다.
 *
 * 다만 도구 선택은 모델 판단이라 비결정적입니다. 결과가 기대와 다르면
 * 모델을 탓하기 전에 description 을 보세요. 대부분 원인이 거기 있습니다.
 * description 에서 "환불 정책", "레이트 리밋" 같은 구체적 단어를 빼고
 * "결제 문서를 검색한다"로만 두면 선택이 눈에 띄게 나빠집니다 — 직접 해 보세요.
 *
 * "Pro 플랜 환불되나요?" 는 사실 경계에 있는 질문입니다("Pro 플랜"은 pricing,
 * "환불"은 refund). 둘 다 search_billing 안에 있어서 다행이지만,
 * 이런 경계 질문이 많다면 도구를 쪼갠 축이 잘못된 것일 수 있습니다.
 */

console.log(`\n(실습용 지식 베이스 위치: ${kbDir})`);
