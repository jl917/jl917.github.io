/**
 * Step 16 — 검색과 RAG
 * 실행: npx tsx docs/reference/langchain/step-16-retrieval-rag/practice.ts
 *
 * 필요한 환경변수 2개:
 *   ANTHROPIC_API_KEY  — 생성(generation)용
 *   OPENAI_API_KEY     — 임베딩(embedding)용
 *                        Anthropic 에는 임베딩 API 가 없습니다. 자세한 건 [16-4].
 *
 * 이 스텝만 추가로 필요한 패키지:
 *   npm install @langchain/classic @langchain/textsplitters
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
import { Document } from "@langchain/core/documents";
import { createAgent, tool } from "langchain";
import * as z from "zod";

import { printSection, printMessages, requireEnv } from "../project/src/lib/print.js";

requireEnv("ANTHROPIC_API_KEY");
requireEnv("OPENAI_API_KEY");

/* ===== [16-1] RAG 파이프라인 전체 그림 ===== */
/*
 * 적재 → 분할 → 임베딩 → 저장  (인덱싱: 미리 한 번)
 * 검색 → 생성                   (질의: 매 요청마다)
 *
 * 아래 [16-2] ~ [16-7] 이 이 6단계를 순서대로 한 번씩 밟습니다.
 */
printSection("[16-1] RAG 파이프라인 — 6단계");
console.log(
  [
    "  인덱싱(미리 한 번)   : 적재 → 분할 → 임베딩 → 저장",
    "  질의(매 요청마다)     : 검색 → 생성",
    "",
    "  이 파일은 위 순서를 그대로 따라갑니다.",
  ].join("\n"),
);

/* ===== [16-2] Document 로더 ===== */

// 실습용 지식 베이스를 임시 디렉터리에 실제 .md 파일로 써 둡니다.
// (문서 사이트에 .md 를 두면 rspress 가 페이지로 만들어 버리므로 런타임에 생성합니다.)
// 문장을 줄바꿈으로 끊지 않고 문단으로 이어 붙였습니다. 실제 문서가 그렇게 생겼고,
// 그래야 분할기가 "문장 중간을 자르는" 진짜 상황이 재현됩니다([16-3]).
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

const kbDir = mkdtempSync(join(tmpdir(), "nimbus-kb-"));
for (const [name, body] of Object.entries(KB)) {
  writeFileSync(join(kbDir, name), body, "utf8");
}

printSection("[16-2] Document 로더 — 로컬 마크다운 적재");

// DirectoryLoader: 확장자별로 어떤 로더를 쓸지 매핑해 줍니다.
const loader = new DirectoryLoader(kbDir, {
  ".md": (path) => new TextLoader(path),
});
const rawDocs = await loader.load();

console.log(`적재된 문서 수: ${rawDocs.length}`);
for (const d of rawDocs) {
  // TextLoader 는 metadata.source 에 절대 경로를 넣어 줍니다.
  const src = String(d.metadata["source"]);
  console.log(`  - ${basename(src)}  (${d.pageContent.length}자)`);
}

// Document 를 직접 만들 수도 있습니다. 로더는 결국 이걸 만들어 주는 도구일 뿐입니다.
const handMade = new Document({
  pageContent: "Nimbus 는 2019년에 설립되었습니다.",
  metadata: { source: "company.md", title: "회사 소개" },
});
console.log(`\n직접 만든 Document: ${handMade.pageContent}`);
console.log(`  metadata: ${JSON.stringify(handMade.metadata)}`);

/* ===== [16-3] 텍스트 분할 ===== */

printSection("[16-3] 텍스트 분할 — chunkOverlap 이 0이면 벌어지는 일");

// 문단 하나만 떼어내 작은 chunkSize 로 자릅니다. 효과를 눈으로 보려고 일부러 작게 잡았습니다.
const refundPara =
  "Nimbus 클라우드의 유료 플랜은 결제일로부터 14일 이내에 환불을 요청할 수 있습니다. " +
  "환불은 요청 후 영업일 기준 5일 이내에 원결제 수단으로 처리됩니다. " +
  "단, 해당 기간에 누적 사용량이 무료 한도의 3배를 넘은 계정은 환불 대상에서 제외됩니다.";

// (A) overlap 0 — 경계에서 문장이 두 동강 납니다.
const splitterNoOverlap = new RecursiveCharacterTextSplitter({
  chunkSize: 60,
  chunkOverlap: 0,
});
const chunksNoOverlap = await splitterNoOverlap.splitText(refundPara);

console.log(`(A) chunkOverlap: 0 → ${chunksNoOverlap.length}개 청크`);
chunksNoOverlap.forEach((c, i) => {
  console.log(`  [${i}] ${JSON.stringify(c)}`);
});
console.log(
  "  ↑ [0] 은 '환불은 요청 후' 에서 끊기고 [1] 은 '영업일 기준...' 으로 시작합니다.\n" +
    "     '환불이 며칠 만에 처리되는가' 라는 사실이 두 청크에 쪼개져, 어느 쪽도 답이 못 됩니다.",
);

// (B) overlap 30 — 앞 청크의 꼬리가 뒤 청크의 머리에 겹쳐 들어갑니다.
const splitterOverlap = new RecursiveCharacterTextSplitter({
  chunkSize: 60,
  chunkOverlap: 30,
});
const chunksOverlap = await splitterOverlap.splitText(refundPara);

console.log(`\n(B) chunkOverlap: 30 → ${chunksOverlap.length}개 청크`);
chunksOverlap.forEach((c, i) => {
  console.log(`  [${i}] ${JSON.stringify(c)}`);
});
console.log(
  "  ↑ [1] 안에 '환불은 요청 후 영업일 기준 5일 이내에...' 문장이 통째로 들어왔습니다.\n" +
    "     청크 수는 3 → 4 로 늘었습니다. 이게 overlap 의 비용입니다.",
);

// 실제 인덱싱에 쓸 분할기. splitDocuments 는 metadata 를 각 청크에 복사해 줍니다.
const splitter = new RecursiveCharacterTextSplitter({
  chunkSize: 300,
  chunkOverlap: 60,
});
const splits = await splitter.splitDocuments(rawDocs);

console.log(`\n원본 ${rawDocs.length}개 문서 → 청크 ${splits.length}개`);
const first = splits[0];
if (first !== undefined) {
  // splitDocuments 는 source 를 그대로 물려주고 loc(줄 번호)을 덧붙입니다.
  console.log(`첫 청크 metadata: ${JSON.stringify(first.metadata)}`);
}

/* ===== [16-4] 임베딩 ===== */

printSection("[16-4] 임베딩 — 텍스트를 벡터로");

// Anthropic 에는 임베딩 API 가 없습니다. @langchain/anthropic 이 내보내는 것은
// ChatAnthropic 뿐입니다. 그래서 생성은 Anthropic, 임베딩은 OpenAI 로 나눠 씁니다.
const embeddings = new OpenAIEmbeddings({
  model: "text-embedding-3-small",
});

const vec = await embeddings.embedQuery("환불은 며칠 안에 되나요?");
console.log(`text-embedding-3-small 차원: ${vec.length}`);
console.log(`앞 5개 값: [${vec.slice(0, 5).map((v) => v.toFixed(4)).join(", ")}]`);

// dimensions 로 차원을 줄이면 저장 비용과 검색 속도가 좋아집니다(정확도는 조금 손해).
const smallEmbeddings = new OpenAIEmbeddings({
  model: "text-embedding-3-small",
  dimensions: 256,
});
const smallVec = await smallEmbeddings.embedQuery("환불은 며칠 안에 되나요?");
console.log(`dimensions: 256 으로 줄이면 → ${smallVec.length}차원`);

// embedDocuments 는 여러 개를 한 번에 (배치로) 임베딩합니다.
const batch = await embeddings.embedDocuments(["환불 정책", "요금제"]);
console.log(`embedDocuments(2개) → ${batch.length}개 벡터`);

/* ===== [16-5] 벡터 스토어 ===== */

printSection("[16-5] 벡터 스토어 — MemoryVectorStore");

// fromDocuments 는 내부에서 embedDocuments 를 호출합니다.
// = 이 줄에서 청크 수만큼 임베딩 API 비용이 나갑니다. 매 실행마다 다시.
const t0 = Date.now();
const vectorStore = await MemoryVectorStore.fromDocuments(splits, embeddings);
console.log(`청크 ${splits.length}개 인덱싱 완료 (${Date.now() - t0}ms)`);

// 나중에 문서를 더 넣을 수도 있습니다.
await vectorStore.addDocuments([handMade]);
console.log(`문서 1개 추가 → 총 ${vectorStore.memoryVectors.length}개 벡터`);

/* ===== [16-6] Retriever ===== */

printSection("[16-6] Retriever — similaritySearch, k, 점수, MMR");

// (A) 가장 기본 — 상위 k개
const hits = await vectorStore.similaritySearch("환불은 며칠 안에 되나요?", 2);
console.log("(A) similaritySearch(query, k=2)");
for (const h of hits) {
  console.log(`  - ${basename(String(h.metadata["source"]))}: ${h.pageContent.slice(0, 40)}...`);
}

// (B) 점수까지 — [Document, number] 튜플 배열입니다. 코사인 유사도라 1에 가까울수록 유사.
const scored = await vectorStore.similaritySearchWithScore("환불은 며칠 안에 되나요?", 4);
console.log("\n(B) similaritySearchWithScore(query, k=4)");
for (const [doc, score] of scored) {
  console.log(`  ${score.toFixed(4)}  ${basename(String(doc.metadata["source"]))}`);
}

// (C) 검색 실패의 모습 — 지식 베이스에 없는 걸 물어도 k개는 반드시 나옵니다.
const offTopic = await vectorStore.similaritySearchWithScore("피자 굽는 온도는?", 2);
console.log("\n(C) 지식 베이스에 없는 질문 — '피자 굽는 온도는?'");
for (const [doc, score] of offTopic) {
  console.log(`  ${score.toFixed(4)}  ${basename(String(doc.metadata["source"]))}  ← 관련 없는데도 반환됨`);
}

// (D) asRetriever — Runnable 인터페이스. invoke/batch 를 씁니다.
const retriever = vectorStore.asRetriever({ k: 3 });
const viaRetriever = await retriever.invoke("Pro 플랜 가격이 얼마인가요?");
console.log(`\n(D) asRetriever({ k: 3 }).invoke() → ${viaRetriever.length}개`);

// (E) MMR — 유사도만 보지 않고 "이미 뽑은 것과 겹치지 않는" 문서를 섞어 뽑습니다.
//     fetchK 로 후보를 넉넉히 가져와 그중 k개를 고릅니다. lambda 1이면 순수 유사도,
//     0에 가까울수록 다양성 우선.
const mmrRetriever = vectorStore.asRetriever({
  searchType: "mmr",
  k: 3,
  searchKwargs: { fetchK: 8, lambda: 0.5 },
});
const mmrHits = await mmrRetriever.invoke("플랜별로 뭐가 다른가요?");
console.log(`\n(E) MMR(k=3, fetchK=8, lambda=0.5) → ${mmrHits.length}개`);
for (const h of mmrHits) {
  console.log(`  - ${basename(String(h.metadata["source"]))}`);
}

// (F) 점수 임계값은 직접 거릅니다 — 몇 개가 남을지 보장이 없습니다.
const THRESHOLD = 0.3;
const filtered = scored.filter(([, s]) => s >= THRESHOLD);
console.log(`\n(F) 임계값 ${THRESHOLD} 이상만 → ${filtered.length}/${scored.length}개 통과`);

/* ===== [16-7] 고전 RAG 체인 ===== */

printSection("[16-7] 고전 RAG — 검색 → 프롬프트에 끼워넣기 → 생성");

const model = "anthropic:claude-sonnet-4-6";

// 고전 RAG 는 그냥 함수입니다. 에이전트도 그래프도 필요 없습니다.
async function classicRag(question: string): Promise<string> {
  // 1) 검색 — 무조건 합니다.
  const docs = await vectorStore.similaritySearch(question, 3);

  // 2) 프롬프트에 끼워넣기 — 출처를 함께 넣어야 인용을 시킬 수 있습니다.
  const context = docs
    .map((d, i) => `[${i + 1}] (출처: ${basename(String(d.metadata["source"]))})\n${d.pageContent}`)
    .join("\n\n");

  // 3) 생성
  const agent = createAgent({
    model,
    tools: [],
    systemPrompt: [
      "너는 Nimbus 클라우드의 고객 지원 담당자다.",
      "아래 <context> 안의 내용만 근거로 답하라.",
      "context 에 답이 없으면 반드시 '제공된 문서에서 찾을 수 없습니다'라고 답하라. 추측하지 마라.",
      "답변 끝에 사용한 출처를 [1] 형식으로 표시하라.",
      "",
      `<context>\n${context}\n</context>`,
    ].join("\n"),
  });

  const result = await agent.invoke({
    messages: [{ role: "user", content: question }],
  });
  const last = result.messages.at(-1);
  return last?.text ?? "";
}

console.log("Q: 환불은 며칠 이내에 요청해야 하나요?");
console.log(`A: ${await classicRag("환불은 며칠 이내에 요청해야 하나요?")}`);

// 지식 베이스에 없는 질문 — 검색은 여전히 3개를 가져오지만, 답은 "모른다"여야 합니다.
console.log("\nQ: Nimbus 는 쿠버네티스를 지원하나요?  ← 문서에 없는 내용");
console.log(`A: ${await classicRag("Nimbus 는 쿠버네티스를 지원하나요?")}`);

/* ===== [16-8] 에이전틱 RAG ===== */

printSection("[16-8] 에이전틱 RAG — 검색을 도구로");

// createRetrieverTool 이 retriever 를 도구로 감싸 줍니다.
// 스키마는 { query: string } 이고 반환값은 문서를 이어붙인 문자열입니다.
const retrieverTool = createRetrieverTool(vectorStore.asRetriever({ k: 3 }), {
  name: "search_nimbus_docs",
  // description 이 곧 프롬프트입니다. "언제 이걸 부를지"를 모델에게 알려 줍니다.
  description:
    "Nimbus 클라우드의 공식 문서(요금제, 환불 정책, 사용 한도, 지원 정책)를 검색한다. " +
    "Nimbus 의 정책·가격·한도에 관한 질문에는 반드시 이 도구를 먼저 호출하라.",
});

const ragAgent = createAgent({
  model,
  tools: [retrieverTool],
  systemPrompt: [
    "너는 Nimbus 클라우드의 고객 지원 담당자다.",
    "Nimbus 에 관한 사실 질문에는 반드시 search_nimbus_docs 를 호출해 근거를 찾아라.",
    "검색 결과에 답이 없으면 '문서에서 찾을 수 없습니다'라고 답하라. 지어내지 마라.",
    "필요하면 검색어를 바꿔 여러 번 호출해도 된다.",
  ].join("\n"),
});

// (A) 검색이 필요한 질문 — 도구를 부릅니다.
console.log("(A) Q: Pro 플랜에서 한도를 넘기면 얼마가 더 나오나요?");
const a1 = await ragAgent.invoke({
  messages: [{ role: "user", content: "Pro 플랜에서 한도를 넘기면 얼마가 더 나오나요?" }],
});
printMessages(a1.messages);

// (B) 검색이 필요 없는 인사 — 도구를 건너뜁니다. 고전 RAG 였다면 무조건 검색했을 겁니다.
console.log("\n(B) Q: 안녕하세요!  ← 검색 불필요");
const a2 = await ragAgent.invoke({
  messages: [{ role: "user", content: "안녕하세요!" }],
});
printMessages(a2.messages);
console.log(
  `\n→ (A) 는 메시지 ${a1.messages.length}개, (B) 는 ${a2.messages.length}개.` +
    " 차이가 곧 '검색을 건너뛴 것'입니다.",
);

// (C) 여러 문서를 넘나드는 질문 — 에이전트가 알아서 두 번 이상 검색할 수 있습니다.
console.log("\n(C) Q: Free 랑 Enterprise 는 지원이랑 레이트 리밋이 각각 어떻게 다른가요?");
const a3 = await ragAgent.invoke({
  messages: [
    {
      role: "user",
      content: "Free 랑 Enterprise 는 지원이랑 레이트 리밋이 각각 어떻게 다른가요?",
    },
  ],
});
const toolCallCount = a3.messages.filter(
  (m) => Array.isArray((m as { tool_calls?: unknown[] }).tool_calls) &&
    ((m as { tool_calls?: unknown[] }).tool_calls?.length ?? 0) > 0,
).length;
console.log(`→ 검색 도구를 부른 AI 메시지 수: ${toolCallCount}`);
printMessages(a3.messages.slice(-1));

/* ===== [16-9] 품질 — 인용(citation) 붙이기 ===== */

printSection("[16-9] 인용 — 출처를 강제하는 도구");

// createRetrieverTool 은 본문만 문자열로 돌려줘서 출처가 사라집니다.
// 인용을 시키려면 도구를 직접 만들어 출처를 본문과 함께 실어 보냅니다.
const citedSearch = tool(
  async ({ query }) => {
    const results = await vectorStore.similaritySearchWithScore(query, 3);
    if (results.length === 0) return "검색 결과가 없습니다.";

    return results
      .map(([doc, score]) => {
        const src = basename(String(doc.metadata["source"]));
        // 점수를 같이 넘겨 주면 모델이 "이건 별로 관련 없네"를 판단할 재료가 됩니다.
        return `<document source="${src}" score="${score.toFixed(3)}">\n${doc.pageContent}\n</document>`;
      })
      .join("\n\n");
  },
  {
    name: "search_with_citations",
    description:
      "Nimbus 클라우드 공식 문서를 검색한다. 각 결과에 source(파일명)와 score(0~1 유사도)가 붙어서 반환된다.",
    schema: z.object({
      query: z.string().describe("검색어. 사용자 질문을 그대로 넣지 말고 핵심 키워드로 다듬어라."),
    }),
  },
);

const citedAgent = createAgent({
  model,
  tools: [citedSearch],
  systemPrompt: [
    "너는 Nimbus 클라우드의 고객 지원 담당자다.",
    "사실 질문에는 반드시 search_with_citations 를 호출하라.",
    "답변의 모든 문장 끝에 근거 문서를 (출처: 파일명) 형식으로 붙여라.",
    "score 가 0.3 미만인 문서는 관련 없다고 보고 근거로 쓰지 마라.",
    "쓸 만한 근거가 하나도 없으면 '문서에서 찾을 수 없습니다'라고만 답하라.",
  ].join("\n"),
  responseFormat: z.object({
    answer: z.string().describe("사용자 질문에 대한 답변"),
    sources: z.array(z.string()).describe("근거로 사용한 문서의 파일명 목록. 없으면 빈 배열"),
    confident: z.boolean().describe("문서에 충분한 근거가 있었으면 true"),
  }),
});

console.log("Q: 연간 결제를 중간에 해지하면 환불받을 수 있나요?");
const c1 = await citedAgent.invoke({
  messages: [{ role: "user", content: "연간 결제를 중간에 해지하면 환불받을 수 있나요?" }],
});
console.log(JSON.stringify(c1.structuredResponse, null, 2));

console.log("\nQ: Nimbus 서버는 어느 나라에 있나요?  ← 문서에 없는 내용");
const c2 = await citedAgent.invoke({
  messages: [{ role: "user", content: "Nimbus 서버는 어느 나라에 있나요?" }],
});
console.log(JSON.stringify(c2.structuredResponse, null, 2));
console.log("\n→ confident: false 와 빈 sources 가 '검색 실패'를 코드로 감지할 수 있게 해 줍니다.");

/* ===== [16-10] 종합 ===== */

printSection("[16-10] 종합 — 인덱싱은 한 번, 질의는 여러 번");

// 인덱싱을 함수로 묶어 두면 "한 번만 하고 재사용"이 구조적으로 드러납니다.
async function buildIndex(dir: string): Promise<MemoryVectorStore> {
  const l = new DirectoryLoader(dir, { ".md": (p) => new TextLoader(p) });
  const docs = await l.load();
  const s = new RecursiveCharacterTextSplitter({ chunkSize: 300, chunkOverlap: 60 });
  const chunks = await s.splitDocuments(docs);
  return MemoryVectorStore.fromDocuments(chunks, new OpenAIEmbeddings({ model: "text-embedding-3-small" }));
}

const store = await buildIndex(kbDir); // ← 임베딩 비용은 여기서 한 번
const finalAgent = createAgent({
  model,
  tools: [
    createRetrieverTool(store.asRetriever({ k: 3 }), {
      name: "search_nimbus_docs",
      description: "Nimbus 클라우드 공식 문서를 검색한다. 정책·가격·한도 질문에 사용하라.",
    }),
  ],
  systemPrompt: "Nimbus 고객 지원 담당자다. 사실 질문은 반드시 검색하고, 없으면 모른다고 답하라.",
});

for (const q of ["레이트 리밋은 API 키마다 따로인가요?", "Enterprise 는 응답이 얼마나 빠른가요?"]) {
  const r = await finalAgent.invoke({ messages: [{ role: "user", content: q }] });
  console.log(`\nQ: ${q}`);
  console.log(`A: ${r.messages.at(-1)?.text ?? ""}`);
}

console.log(`\n(실습용 지식 베이스 위치: ${kbDir})`);
