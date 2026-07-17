/**
 * Step 09 — 스트리밍
 * 실행: npx tsx docs/reference/langchain/step-09-streaming/practice.ts
 *
 * 본문 9-1 ~ 9-9 의 예제를 순서대로 담았습니다.
 * 블록 주석의 [9-x] 번호가 본문 소제목과 1:1 로 대응합니다.
 *
 * 이 파일은 실제 모델을 호출합니다. project/.env 에 ANTHROPIC_API_KEY 가 필요합니다.
 * (OpenAI 를 쓰려면 [9-2] 의 주석을 참고하세요.)
 */
import "dotenv/config";

import { createAgent, tool } from "langchain";
import { ChatAnthropic } from "@langchain/anthropic";
import { AIMessageChunk } from "@langchain/core/messages";
import { MemorySaver, type LangGraphRunnableConfig } from "@langchain/langgraph";
import * as z from "zod";

import { printSection } from "../project/src/lib/print.js";

/* ===== 공용 준비물 ===== */

// 이 스텝 전체에서 재사용할 도구.
// config 를 두 번째 인자로 받는 형태는 [9-7] 에서 writer 를 쓰기 위한 것입니다.
const getWeather = tool(
  async (input: { city: string }, config: LangGraphRunnableConfig) => {
    // writer 는 "custom" 스트림으로 임의의 데이터를 흘려보냅니다.
    // 구독자가 없어도(= streamMode 에 custom 이 없어도) 그냥 무시되므로 안전합니다.
    config.writer?.({ type: "progress", data: `${input.city} 관측소 조회 중...` });
    await new Promise((r) => setTimeout(r, 300)); // 느린 API 흉내
    config.writer?.({ type: "progress", data: `${input.city} 관측 데이터 수신 완료` });
    return `${input.city}의 날씨는 맑음, 기온 21도입니다.`;
  },
  {
    name: "get_weather",
    description: "도시 이름을 받아 현재 날씨를 반환합니다.",
    schema: z.object({
      city: z.string().describe("날씨를 조회할 도시 이름"),
    }),
  },
);

const model = new ChatAnthropic({ model: "claude-sonnet-4-6" });

/* ===== [9-1] 왜 스트리밍인가 — TTFT 측정 ===== */

async function section_9_1(): Promise<void> {
  printSection("[9-1] invoke vs stream — 첫 글자까지 걸리는 시간(TTFT)");

  // (A) invoke: 모델이 문장을 다 만들 때까지 아무것도 못 본다.
  const t0 = Date.now();
  const res = await model.invoke("한국의 사계절을 각 두 문장씩 설명해 주세요.");
  const invokeTotal = Date.now() - t0;
  console.log(`invoke  │ 첫 출력까지 ${invokeTotal}ms  (= 전체 완료 시간과 같음)`);
  console.log(`invoke  │ 글자 수 ${res.text.length}`);

  // (B) stream: 첫 청크가 오는 순간 TTFT 가 찍힌다.
  const t1 = Date.now();
  let ttft = -1;
  let chars = 0;
  const stream = await model.stream("한국의 사계절을 각 두 문장씩 설명해 주세요.");
  for await (const chunk of stream) {
    if (ttft === -1) ttft = Date.now() - t1;
    chars += chunk.text.length;
  }
  const streamTotal = Date.now() - t1;
  console.log(`stream  │ 첫 출력까지 ${ttft}ms  (TTFT)`);
  console.log(`stream  │ 전체 완료 ${streamTotal}ms, 글자 수 ${chars}`);
  console.log(`\n→ 전체 시간은 비슷하지만, 사용자가 "기다린다"고 느끼는 시간은 ${ttft}ms 뿐입니다.`);
}

/* ===== [9-2] 모델 레벨 스트리밍 — AIMessageChunk 와 concat ===== */

async function section_9_2(): Promise<void> {
  printSection("[9-2] model.stream() — 청크를 눈으로 보기");

  // OpenAI 를 쓰려면 위쪽 model 정의를 이렇게 바꾸면 됩니다:
  //   import { ChatOpenAI } from "@langchain/openai";
  //   const model = new ChatOpenAI({ model: "gpt-5.5" });
  // stream() 의 사용법은 제공자와 무관하게 동일합니다.

  const stream = await model.stream("스트리밍을 한 문장으로 설명해 주세요.");

  let count = 0;
  for await (const chunk of stream) {
    count += 1;
    // chunk 는 AIMessage 가 아니라 AIMessageChunk 입니다. 이름이 다른 이유가 있습니다.
    if (count <= 3) {
      console.log(`청크 ${count} │ 클래스=${chunk.constructor.name} text=${JSON.stringify(chunk.text)}`);
    }
    process.stdout.write(chunk.text);
  }
  console.log(`\n\n총 ${count}개 청크`);

  printSection("[9-2] concat 으로 하나의 메시지로 합치기");

  const stream2 = await model.stream("스트리밍의 장점을 한 문장으로 말해 주세요.");

  // 누산기의 타입은 AIMessageChunk | null 입니다.
  // 첫 청크는 그대로 쓰고, 이후로는 .concat() 으로 병합합니다.
  let full: AIMessageChunk | null = null;
  for await (const chunk of stream2) {
    full = full === null ? chunk : full.concat(chunk);
  }

  if (full !== null) {
    console.log("합쳐진 text        :", full.text);
    console.log("합쳐진 contentBlocks:", JSON.stringify(full.contentBlocks, null, 2));
    // usage_metadata 는 보통 마지막 청크에만 실려 오는데,
    // concat 이 그것까지 병합해 줍니다. 문자열 이어붙이기로는 절대 얻을 수 없는 값입니다.
    console.log("usage_metadata     :", JSON.stringify(full.usage_metadata));
    console.log("response_metadata  :", JSON.stringify(full.response_metadata));
  }

  printSection("[9-2] 함정 — .content 를 문자열로 이어붙이면?");

  const stream3 = await model.stream("아무 인사말이나 짧게 해 주세요.");
  let naive = "";
  for await (const chunk of stream3) {
    // ⚠️ content 는 string 일 수도 있고 콘텐츠 블록 배열일 수도 있습니다.
    //    배열이면 "+= " 는 "[object Object]" 를 만들거나 조용히 어긋납니다.
    naive += typeof chunk.content === "string" ? chunk.content : JSON.stringify(chunk.content);
  }
  console.log("순진하게 이어붙인 결과:", naive.slice(0, 200));
  console.log("→ 텍스트 전용 모델에선 우연히 잘 보일 수 있습니다. 그게 이 함정이 위험한 이유입니다.");
}

/* ===== [9-3] 에이전트 스트리밍 — streamMode 해부 ===== */

const agent = createAgent({
  model: "anthropic:claude-sonnet-4-6",
  tools: [getWeather],
  systemPrompt: "당신은 간결하게 답하는 날씨 비서입니다. 날씨 질문은 반드시 도구로 확인하세요.",
});

const question = { messages: [{ role: "user" as const, content: "서울 날씨 알려줘" }] };

async function section_9_3_updates(): Promise<void> {
  printSection('[9-3] streamMode: "updates" — 스텝이 끝날 때마다 "바뀐 것"만');

  for await (const chunk of await agent.stream(question, { streamMode: "updates" })) {
    // chunk 의 모양: { [노드이름]: { messages: [...] } }
    for (const [node, update] of Object.entries(chunk)) {
      console.log(`노드 ${node} 가 업데이트를 내놓음:`);
      console.log(JSON.stringify(update, null, 2).slice(0, 400));
    }
  }
}

async function section_9_3_values(): Promise<void> {
  printSection('[9-3] streamMode: "values" — 스텝마다 "전체 상태"');

  let step = 0;
  for await (const chunk of await agent.stream(question, { streamMode: "values" })) {
    step += 1;
    // values 는 매번 누적된 전체 messages 배열을 줍니다.
    // 그래서 뒤로 갈수록 payload 가 커집니다 — 네트워크로 그대로 흘리면 낭비입니다.
    const messages = (chunk as { messages?: unknown[] }).messages ?? [];
    console.log(`스텝 ${step} │ messages 길이 = ${messages.length}`);
  }
}

async function section_9_3_messages(): Promise<void> {
  printSection('[9-3] streamMode: "messages" — LLM 토큰 (token, metadata) 튜플');

  for await (const [token, metadata] of await agent.stream(question, { streamMode: "messages" })) {
    // token 은 AIMessageChunk (또는 ToolMessage), metadata 는 어느 노드에서 나왔는지 등.
    const node = (metadata as { langgraph_node?: string }).langgraph_node ?? "?";
    const text = (token as AIMessageChunk).text;
    if (text !== "") {
      console.log(`[${node}] ${JSON.stringify(text)}`);
    }
  }
}

async function section_9_3_debug(): Promise<void> {
  printSection('[9-3] streamMode: "debug" — 실행 중 모든 정보 (양이 많습니다)');

  let n = 0;
  for await (const chunk of await agent.stream(question, { streamMode: "debug" })) {
    n += 1;
    if (n <= 3) console.log(JSON.stringify(chunk).slice(0, 300));
  }
  console.log(`... 총 ${n}개 debug 이벤트 (프로덕션에서 사용자에게 흘리면 안 됩니다)`);
}

/* ===== [9-4] 여러 모드 동시 구독 ===== */

async function section_9_4(): Promise<void> {
  printSection('[9-4] streamMode: ["updates", "messages", "custom"] — 튜플이 [mode, chunk] 로 바뀐다');

  for await (const [mode, chunk] of await agent.stream(question, {
    streamMode: ["updates", "messages", "custom"],
  })) {
    // ⚠️ 배열로 주면 각 항목이 [모드이름, 페이로드] 튜플이 됩니다.
    //    단일 모드일 때의 코드를 그대로 두면 chunk 위치가 밀려서 조용히 깨집니다.
    switch (mode) {
      case "messages": {
        const [token] = chunk as [AIMessageChunk, unknown];
        if (token.text !== "") process.stdout.write(token.text);
        break;
      }
      case "updates": {
        console.log(`\n[updates] ${Object.keys(chunk as object).join(", ")} 노드 완료`);
        break;
      }
      case "custom": {
        console.log(`\n[custom] ${JSON.stringify(chunk)}`);
        break;
      }
      default:
        break;
    }
  }
  console.log("");
}

/* ===== [9-5] streamEvents — 세밀한 이벤트와 프로젝션 ===== */

async function section_9_5(): Promise<void> {
  printSection('[9-5] streamEvents(version: "v3") — 원본 프로토콜 이벤트');

  const raw = await agent.streamEvents(question, { version: "v3" });
  let count = 0;
  for await (const event of raw) {
    count += 1;
    if (count <= 5) {
      // 이벤트 봉투(envelope)의 모양: { method, params: { namespace, data } }
      console.log(`method=${event.method} namespace=${JSON.stringify(event.params?.namespace)}`);
    }
  }
  console.log(`... 총 ${count}개 원본 이벤트`);

  printSection("[9-5] 프로젝션 — stream.messages / message.text");

  const stream = await agent.streamEvents(question, { version: "v3" });
  // stream.messages 는 "LLM 호출 1건 = 메시지 스트림 1개" 로 묶어서 줍니다.
  // 그 안의 message.text 는 텍스트 델타만 뽑아 주는 프로젝션입니다.
  for await (const message of stream.messages) {
    for await (const delta of message.text) {
      process.stdout.write(delta);
    }
    console.log("\n--- 메시지 1건 종료 ---");
  }

  printSection("[9-5] 프로젝션 — stream.output (최종 상태)");

  const stream2 = await agent.streamEvents(question, { version: "v3" });
  for await (const message of stream2.messages) {
    for await (const _ of message.text) {
      // output 을 얻으려면 스트림을 끝까지 소비해야 합니다.
    }
  }
  const finalState = await stream2.output;
  console.log("최종 messages 개수:", (finalState as { messages?: unknown[] }).messages?.length);
}

/* ===== [9-6] 도구 호출 스트리밍 — tool_call_chunks 누적 ===== */

async function section_9_6(): Promise<void> {
  printSection("[9-6] tool_call_chunks — 인자가 부분 JSON 으로 쪼개져서 온다");

  const modelWithTools = model.bindTools([getWeather]);
  const stream = await modelWithTools.stream("서울과 부산 날씨를 각각 알려줘");

  let full: AIMessageChunk | null = null;
  for await (const chunk of stream) {
    for (const tc of chunk.tool_call_chunks ?? []) {
      // ⚠️ tc.args 는 완성된 JSON 이 아니라 "조각" 입니다.
      //    '{"ci' / 'ty": "서' / '울"}' 처럼 옵니다. JSON.parse 하면 터집니다.
      console.log(
        `index=${tc.index} name=${tc.name ?? "(없음)"} args조각=${JSON.stringify(tc.args)}`,
      );
    }
    full = full === null ? chunk : full.concat(chunk);
  }

  printSection("[9-6] concat 이 조각을 합쳐 준다 — full.tool_calls");

  if (full !== null) {
    // concat 은 index 를 기준으로 args 문자열을 이어붙이고,
    // 완성된 것만 tool_calls 에 파싱해서 올려 줍니다.
    console.log("tool_call_chunks 개수:", full.tool_call_chunks?.length);
    console.log("완성된 tool_calls   :", JSON.stringify(full.tool_calls, null, 2));
  }

  printSection("[9-6] 하면 안 되는 것 — 조각을 바로 파싱");

  const stream2 = await modelWithTools.stream("대구 날씨 알려줘");
  for await (const chunk of stream2) {
    for (const tc of chunk.tool_call_chunks ?? []) {
      try {
        JSON.parse(tc.args ?? "");
        console.log("파싱 성공(우연):", tc.args);
      } catch (e) {
        console.log(`파싱 실패 ← 정상입니다. args=${JSON.stringify(tc.args)} / ${(e as Error).name}`);
      }
    }
  }
}

/* ===== [9-7] 커스텀 데이터 스트리밍 ===== */

async function section_9_7(): Promise<void> {
  printSection('[9-7] streamMode: "custom" — 도구 안에서 진행상황 내보내기');

  // getWeather 도구는 이미 config.writer?.({ type, data }) 를 두 번 호출합니다.
  for await (const chunk of await agent.stream(question, { streamMode: "custom" })) {
    console.log("custom:", JSON.stringify(chunk));
  }

  printSection("[9-7] writer 는 구독자가 없어도 안전하다");

  // streamMode 에 custom 이 없으면 writer 로 보낸 것은 그냥 버려집니다. 에러가 아닙니다.
  for await (const chunk of await agent.stream(question, { streamMode: "updates" })) {
    console.log("updates 만 구독:", Object.keys(chunk).join(", "));
  }
}

/* ===== [9-8] 서버로 내보내기 — SSE ===== */

async function section_9_8(): Promise<void> {
  printSection("[9-8] SSE 서버 — 브라우저로 토큰 흘려보내기");

  const { createServer } = await import("node:http");

  const server = createServer(async (req, res) => {
    if (req.url !== "/chat") {
      res.writeHead(404).end();
      return;
    }

    // SSE 필수 헤더. 셋 중 하나라도 빠지면 프록시나 브라우저가 버퍼링합니다.
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no", // nginx 버퍼링 방지
    });

    // 클라이언트가 탭을 닫으면 close 가 옵니다. 이때 스트림을 끊지 않으면
    // 에이전트가 백그라운드에서 계속 돌며 토큰을 태웁니다.
    const controller = new AbortController();
    req.on("close", () => controller.abort());

    const send = (event: string, data: unknown): void => {
      res.write(`event: ${event}\n`);
      res.write(`data: ${JSON.stringify(data)}\n\n`);
    };

    try {
      for await (const [mode, chunk] of await agent.stream(question, {
        streamMode: ["messages", "custom"],
        signal: controller.signal,
      })) {
        if (mode === "messages") {
          const [token] = chunk as [AIMessageChunk, unknown];
          if (token.text !== "") send("token", { text: token.text });
        } else if (mode === "custom") {
          send("progress", chunk);
        }
      }
      send("done", { ok: true });
    } catch (err) {
      // ⚠️ 이미 200 을 보냈으므로 500 으로 바꿀 수 없습니다.
      //    에러도 "이벤트"로 흘려보내고, 클라이언트가 처리하게 해야 합니다.
      send("error", { message: (err as Error).message });
    } finally {
      res.end();
    }
  });

  await new Promise<void>((resolve) => server.listen(0, resolve));
  const address = server.address();
  const port = typeof address === "object" && address !== null ? address.port : 0;
  console.log(`SSE 서버 기동: http://127.0.0.1:${port}/chat`);

  // 같은 프로세스에서 fetch 로 직접 구독해 봅니다 (브라우저 EventSource 대신).
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
  console.log("\nSSE 서버 종료");
}

/* ===== [9-9] 종합 — 취소와 에러를 견디는 스트림 루프 ===== */

async function section_9_9(): Promise<void> {
  printSection("[9-9] 종합 — 취소 가능한 스트리밍 소비자");

  const agentWithMemory = createAgent({
    model: "anthropic:claude-sonnet-4-6",
    tools: [getWeather],
    systemPrompt: "당신은 간결하게 답하는 날씨 비서입니다.",
    checkpointer: new MemorySaver(),
  });

  const config = { configurable: { thread_id: crypto.randomUUID() } };
  const controller = new AbortController();

  // 1.5초 뒤 강제 취소 — 사용자가 "그만" 을 누른 상황.
  const timer = setTimeout(() => controller.abort(), 1500);

  let received = "";
  try {
    for await (const [token] of await agentWithMemory.stream(
      { messages: [{ role: "user", content: "서울 날씨를 알려주고, 옷차림을 길게 조언해줘" }] },
      { ...config, streamMode: "messages", signal: controller.signal },
    )) {
      const text = (token as AIMessageChunk).text;
      received += text;
      process.stdout.write(text);
    }
    console.log("\n→ 끝까지 완료");
  } catch (err) {
    // AbortError 는 "정상적인 취소" 입니다. 에러 로그로 올리면 안 됩니다.
    const name = (err as Error).name;
    console.log(`\n→ 중단됨 (${name}). 이미 사용자에게 보낸 ${received.length}자는 되돌릴 수 없습니다.`);
  } finally {
    clearTimeout(timer);
  }

  console.log("\n중요: break 로 for-await 를 빠져나가는 대신 signal 로 끊어야");
  console.log("      백그라운드 실행과 토큰 과금이 함께 멈춥니다.");
}

/* ===== 실행 ===== */

async function main(): Promise<void> {
  await section_9_1();
  await section_9_2();
  await section_9_3_updates();
  await section_9_3_values();
  await section_9_3_messages();
  await section_9_3_debug();
  await section_9_4();
  await section_9_5();
  await section_9_6();
  await section_9_7();
  await section_9_8();
  await section_9_9();
}

main().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
