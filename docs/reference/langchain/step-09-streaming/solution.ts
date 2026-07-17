/**
 * Step 09 — 스트리밍 · 정답과 해설
 * 실행: npx tsx docs/reference/langchain/step-09-streaming/solution.ts
 *
 * exercise.ts 를 스스로 풀어본 뒤에 열어보세요.
 * 각 정답 위 주석에 "왜 이렇게 해야 하는가"와 흔한 오답을 적어 두었습니다.
 */
import "dotenv/config";

import { createAgent, tool } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessageChunk } from "@langchain/core/messages";
import { type LangGraphRunnableConfig } from "@langchain/langgraph";
import * as z from "zod";

import { printSection } from "../project/src/lib/print.js";

const model = new ChatAnthropic({ model: "claude-sonnet-4-6" });

const searchDocs = tool(
  async (input: { query: string }, _config: LangGraphRunnableConfig) => {
    await new Promise((r) => setTimeout(r, 200));
    return `"${input.query}" 검색 결과: 문서 3건을 찾았습니다.`;
  },
  {
    name: "search_docs",
    description: "사내 문서를 검색합니다.",
    schema: z.object({ query: z.string().describe("검색어") }),
  },
);

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [searchDocs],
  systemPrompt: "당신은 사내 문서 검색 비서입니다. 질문에는 반드시 도구로 근거를 찾아 답하세요.",
});

const ask = { messages: [{ role: "user" as const, content: "휴가 정책을 알려줘" }] };

/* =====================================================================
 * [정답 1] TTFT 측정
 *
 * 핵심은 "첫 청크가 도착한 시점"을 루프 안에서 단 한 번만 찍는 것입니다.
 * 흔한 오답: 루프가 끝난 뒤에 Date.now() 를 재는 것 — 그건 TTFT 가 아니라
 * 전체 완료 시간입니다. 사용자 체감은 전자가 좌우합니다.
 *
 * 실제로 돌려 보면 ttftMs 는 보통 totalMs 의 5~15% 수준입니다.
 * 이 격차가 스트리밍을 쓰는 유일한 이유입니다.
 * ===================================================================== */
async function problem1(
  prompt: string,
): Promise<{ ttftMs: number; totalMs: number; text: string }> {
  const started = Date.now();
  let ttftMs = -1;
  let text = "";

  const stream = await model.stream(prompt);
  for await (const chunk of stream) {
    if (ttftMs === -1) ttftMs = Date.now() - started; // 첫 청크에서만
    text += chunk.text; // .text 는 항상 문자열이라 안전합니다
  }

  return { ttftMs, totalMs: Date.now() - started, text };
}

/* =====================================================================
 * [정답 2] concat 으로 청크 합치기
 *
 * `full = full === null ? chunk : full.concat(chunk)` 가 이 스텝의 관용구입니다.
 *
 * 왜 문자열 += 가 안 되는가:
 * - text 만 필요하면 우연히 잘 동작합니다. 그래서 조용히 틀립니다.
 * - 하지만 usage_metadata, response_metadata, tool_call_chunks 는
 *   문자열로는 절대 복원되지 않습니다. usage_metadata 는 보통 마지막
 *   청크에만 실려 오는데, concat 만이 그걸 병합해서 올려 줍니다.
 * - reasoning 블록이 있는 모델은 content 가 블록 배열이라
 *   += 하면 "[object Object]" 가 섞입니다.
 * ===================================================================== */
async function problem2(prompt: string): Promise<AIMessageChunk | null> {
  let full: AIMessageChunk | null = null;

  const stream = await model.stream(prompt);
  for await (const chunk of stream) {
    full = full === null ? chunk : full.concat(chunk);
  }

  return full;
}

/* =====================================================================
 * [정답 3] updates 로 도구 호출 추적
 *
 * updates 청크는 { 노드이름: { messages: [...] } } 모양입니다.
 * "노드 하나가 방금 무엇을 상태에 더했나"만 담기므로 payload 가 작습니다.
 * 같은 걸 values 로 하면 매번 전체 messages 배열이 통째로 와서 낭비입니다.
 *
 * 흔한 오답: Object.entries 없이 chunk.messages 를 바로 읽는 것.
 * updates 는 노드 이름으로 한 겹 감싸여 있습니다.
 * ===================================================================== */
async function problem3(): Promise<void> {
  for await (const chunk of await agent.stream(ask, { streamMode: "updates" })) {
    for (const [node, update] of Object.entries(chunk)) {
      const messages = (update as { messages?: unknown[] }).messages ?? [];
      for (const m of messages) {
        const msg = m as {
          getType?: () => string;
          tool_calls?: Array<{ name: string; args: unknown }>;
          text?: string;
        };
        const type = msg.getType?.() ?? "?";

        if (type === "ai" && (msg.tool_calls?.length ?? 0) > 0) {
          for (const call of msg.tool_calls ?? []) {
            console.log(`[${node}] 도구 호출 결정 → ${call.name}(${JSON.stringify(call.args)})`);
          }
        } else if (type === "tool") {
          console.log(`[${node}] 도구 결과 도착 → ${String(msg.text).slice(0, 60)}`);
        } else if (type === "ai") {
          console.log(`[${node}] 최종 답변 ${String(msg.text).length}자`);
        }
      }
    }
  }
}

/* =====================================================================
 * [정답 4] messages 에서 특정 노드만
 *
 * metadata.langgraph_node 가 "어느 노드에서 나온 토큰인가"를 알려 줍니다.
 * createAgent 의 모델 노드 이름은 "model" 입니다.
 *
 * ⚠️ 이 필터가 필요한 이유: streamMode "messages" 는 LLM 토큰만이 아니라
 *    도구 노드가 만든 ToolMessage 도 함께 흘려보냅니다. 필터 없이
 *    화면에 다 찍으면 도구 결과 원문이 사용자에게 그대로 노출됩니다.
 *
 * 노드 이름은 프레임워크 내부 명칭이라 버전에 따라 바뀔 수 있습니다.
 * 하드코딩이 불안하면 metadata.tags 나 withConfig({ tags: [...] }) 로
 * 직접 붙인 태그로 거는 편이 안전합니다.
 * ===================================================================== */
async function problem4(): Promise<void> {
  for await (const [token, metadata] of await agent.stream(ask, { streamMode: "messages" })) {
    const node = (metadata as { langgraph_node?: string }).langgraph_node;
    if (node !== "model") continue;

    const text = (token as AIMessageChunk).text;
    if (text !== "") process.stdout.write(text);
  }
  console.log("");
}

/* =====================================================================
 * [정답 5] 여러 모드 동시 구독
 *
 * 배열을 주는 순간 반환 항목이 [mode, chunk] 튜플로 바뀝니다.
 * 이게 이 절의 함정입니다 — 단일 모드 코드를 그대로 두면
 * chunk 자리에 문자열 "messages" 가 들어와 .text 가 undefined 가 됩니다.
 *
 * 그리고 mode === "messages" 일 때 chunk 는 다시 [token, metadata] 튜플이라
 * 튜플이 두 겹입니다. 여기서 헷갈리는 사람이 많습니다.
 * ===================================================================== */
async function problem5(): Promise<void> {
  for await (const [mode, chunk] of await agent.stream(ask, {
    streamMode: ["updates", "messages"],
  })) {
    if (mode === "messages") {
      const [token] = chunk as [AIMessageChunk, unknown];
      if (token.text !== "") process.stdout.write(token.text);
    } else if (mode === "updates") {
      const nodes = Object.keys(chunk as object).join(", ");
      console.log(`\n[스텝 완료: ${nodes}]`);
    }
  }
  console.log("");
}

/* =====================================================================
 * [정답 6] tool_call_chunks 수동 누적 ★
 *
 * 이 문제의 정답은 실무에서 직접 쓸 일이 거의 없습니다 — concat 이 이걸
 * 대신 해 주니까요. 그럼에도 손으로 짜 보는 이유는 "부분 JSON" 이 무엇인지
 * 몸으로 알기 위해서입니다.
 *
 * 세 가지 포인트:
 * 1. index 로 묶는다. 병렬 도구 호출이면 index 0, 1 조각이 번갈아 옵니다.
 *    index 를 무시하고 args 를 한 문자열에 몰아 붙이면 두 호출의 JSON 이
 *    섞여서 영원히 파싱되지 않습니다.
 * 2. name 은 첫 조각에만 옵니다. `acc.name = tc.name` 으로 무조건 대입하면
 *    두 번째 조각의 undefined 가 이름을 지웁니다. `??` 로 보존해야 합니다.
 * 3. JSON.parse 실패는 에러가 아니라 "아직 안 왔다" 입니다.
 *    try/catch 로 삼키고 다음 조각을 기다리는 게 정상 동작입니다.
 * ===================================================================== */
async function problem6(): Promise<Array<{ index: number; name: string; args: unknown }>> {
  const modelWithTools = model.bindTools([searchDocs]);
  const stream = await modelWithTools.stream("휴가 정책 문서를 검색해줘");

  // index → 누적 상태
  const acc = new Map<number, { name: string; raw: string }>();
  const completed: Array<{ index: number; name: string; args: unknown }> = [];
  const doneIndexes = new Set<number>();

  for await (const chunk of stream) {
    for (const tc of chunk.tool_call_chunks ?? []) {
      const index = tc.index ?? 0;
      const prev = acc.get(index) ?? { name: "", raw: "" };

      acc.set(index, {
        // ⚠️ tc.name 이 undefined 인 조각이 대부분입니다. 덮어쓰면 안 됩니다.
        name: prev.name !== "" ? prev.name : (tc.name ?? ""),
        raw: prev.raw + (tc.args ?? ""),
      });

      // 매 조각마다 완성됐는지 시도해 봅니다.
      const cur = acc.get(index);
      if (cur === undefined || doneIndexes.has(index)) continue;
      try {
        const args: unknown = JSON.parse(cur.raw);
        doneIndexes.add(index);
        completed.push({ index, name: cur.name, args });
        console.log(`index=${index} 완성! name=${cur.name} args=${cur.raw}`);
      } catch {
        // 아직 미완성 — 정상. 다음 조각을 기다립니다.
        console.log(`index=${index} 미완성 raw=${JSON.stringify(cur.raw)}`);
      }
    }
  }

  return completed;
}

/* =====================================================================
 * [정답 7] 커스텀 진행률 스트리밍
 *
 * 포인트는 도구 함수의 "두 번째 인자" 입니다.
 *   async (input) => {...}                              ← writer 를 못 씀
 *   async (input, config: LangGraphRunnableConfig) => {} ← 정답
 *
 * config 를 안 받아도 타입 에러가 나지 않고 도구는 잘 동작합니다.
 * 진행률만 조용히 사라집니다. 그래서 이건 함정입니다.
 *
 * writer 를 `config.writer?.()` 로 옵셔널 호출하는 것도 중요합니다.
 * 스트리밍이 아닌 invoke() 경로에서는 writer 가 없을 수 있습니다.
 * ===================================================================== */
async function problem7(): Promise<void> {
  const bulkIndex = tool(
    async (input: { count: number }, config: LangGraphRunnableConfig) => {
      const total = 3;
      for (let done = 1; done <= total; done += 1) {
        await new Promise((r) => setTimeout(r, 150));
        config.writer?.({ type: "progress", done, total });
      }
      return `${input.count}건을 색인했습니다.`;
    },
    {
      name: "bulk_index",
      description: "문서를 대량 색인합니다. 진행률을 보고합니다.",
      schema: z.object({ count: z.number().describe("색인할 문서 수") }),
    },
  );

  const indexAgent = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [bulkIndex],
    systemPrompt: "당신은 색인 작업자입니다. 요청받으면 도구를 사용하세요.",
  });

  for await (const chunk of await indexAgent.stream(
    { messages: [{ role: "user", content: "문서 100건을 색인해줘" }] },
    { streamMode: "custom" },
  )) {
    const p = chunk as { type?: string; done?: number; total?: number };
    if (p.type === "progress") {
      console.log(`진행률 ${p.done}/${p.total}`);
    }
  }
}

/* =====================================================================
 * [정답 8] SSE 핸들러 ★
 *
 * 실전에서 자주 놓치는 네 가지가 전부 여기 들어 있습니다.
 *
 * 1. 헤더 3종 세트. Cache-Control: no-cache 가 없으면 중간 프록시가
 *    응답을 통째로 모았다가 한 번에 보냅니다 — 스트리밍이 사라집니다.
 *    nginx 뒤에 있다면 X-Accel-Buffering: no 도 필요합니다.
 * 2. 에러를 "이벤트로" 보낸다. writeHead(200) 을 이미 호출했으므로
 *    나중에 500 으로 바꿀 수 없습니다. 상태 코드로 실패를 알리는
 *    평소 습관이 여기선 통하지 않습니다.
 * 3. req.on("close") → controller.abort(). 사용자가 탭을 닫아도
 *    에이전트는 계속 돌고 토큰은 계속 과금됩니다. 이걸 안 끊는 게
 *    스트리밍 서버의 대표적인 돈 새는 구멍입니다.
 * 4. SSE 프레임 규격: `data: ...\n\n` — 빈 줄 하나로 프레임이 끝납니다.
 *    \n 하나만 쓰면 브라우저가 이벤트를 영원히 기다립니다.
 * ===================================================================== */
async function problem8(): Promise<void> {
  const { createServer } = await import("node:http");

  const server = createServer(async (req, res) => {
    if (req.url !== "/chat") {
      res.writeHead(404).end();
      return;
    }

    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });

    const controller = new AbortController();
    req.on("close", () => controller.abort());

    const send = (event: string, data: unknown): void => {
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    try {
      for await (const [token, metadata] of await agent.stream(ask, {
        streamMode: "messages",
        signal: controller.signal,
      })) {
        if ((metadata as { langgraph_node?: string }).langgraph_node !== "model") continue;
        const text = (token as AIMessageChunk).text;
        if (text !== "") send("token", { text });
      }
      send("done", { ok: true });
    } catch (err) {
      const e = err as Error;
      // 취소(AbortError)는 사용자가 끊은 것이므로 에러 이벤트로 보낼 필요가 없습니다.
      if (e.name !== "AbortError") send("error", { message: e.message });
    } finally {
      res.end();
    }
  });

  await new Promise<void>((resolve) => server.listen(0, resolve));
  const address = server.address();
  const port = typeof address === "object" && address !== null ? address.port : 0;

  const res = await fetch(`http://127.0.0.1:${port}/chat`);
  const reader = res.body?.getReader();
  const decoder = new TextDecoder();
  if (reader !== undefined) {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      process.stdout.write(decoder.decode(value));
    }
  }

  server.close();
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  printSection("[정답 1] TTFT 측정");
  const r1 = await problem1("스트리밍을 한 문장으로 설명해줘");
  console.log(`ttft=${r1.ttftMs}ms total=${r1.totalMs}ms (${r1.text.length}자)`);

  printSection("[정답 2] concat 으로 청크 합치기");
  const r2 = await problem2("안녕이라고만 답해줘");
  console.log("text          :", r2?.text);
  console.log("usage_metadata:", JSON.stringify(r2?.usage_metadata));

  printSection("[정답 3] updates 로 도구 호출 추적");
  await problem3();

  printSection("[정답 4] messages 에서 model 노드만");
  await problem4();

  printSection("[정답 5] 여러 모드 동시 구독");
  await problem5();

  printSection("[정답 6] tool_call_chunks 수동 누적");
  console.log(JSON.stringify(await problem6(), null, 2));

  printSection("[정답 7] 커스텀 진행률");
  await problem7();

  printSection("[정답 8] SSE 핸들러");
  await problem8();
}

main().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
