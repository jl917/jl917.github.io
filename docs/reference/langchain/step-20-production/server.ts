/**
 * Step 20 — 직접 만드는 스트리밍 서버 (Hono)
 * 실행: npx tsx docs/reference/langchain/step-20-production/server.ts
 * 필요: npm install hono @hono/node-server
 *
 * LangGraph Platform 을 쓰지 않고 에이전트를 직접 HTTP 로 노출할 때의 최소 형태입니다.
 * "최소"지만 프로덕션에 필요한 것들이 들어 있습니다 —
 * SSE 스트리밍, 스트림 도중 에러 처리, 연결 끊김 감지, graceful shutdown.
 */

import "dotenv/config";

import { Hono } from "hono";
import { serve } from "@hono/node-server";
import { streamSSE } from "hono/streaming";
import { RunControl, GraphDrained } from "@langchain/langgraph";

import { createSupportAgent } from "./production-agent.js";

// 에이전트는 **모듈 로드 시 한 번만** 만듭니다.
// 요청마다 createSupportAgent() 를 부르면 매 요청 Postgres 풀이 새로 생기고
// setup() 이 다시 돌면서 커넥션이 금방 고갈됩니다.
const agent = await createSupportAgent();

const app = new Hono();

/* ===== 헬스체크 ===== */

// 로드밸런서가 "이 인스턴스에 트래픽을 줘도 되나"를 묻는 곳입니다.
// 아래 shutdown 로직에서 이 값을 false 로 바꿔 새 트래픽을 끊습니다.
let accepting = true;

app.get("/health", (c) => {
  return accepting ? c.json({ ok: true }) : c.json({ ok: false, draining: true }, 503);
});

/* ===== [20-5] 스트리밍 엔드포인트 ===== */

app.post("/chat", async (c) => {
  const body = await c.req.json<{ threadId?: string; message?: string }>();

  const threadId = body.threadId;
  const message = body.message;

  // 검증은 스트림을 열기 **전에** 합니다.
  // 스트림을 연 뒤에는 상태코드를 바꿀 수 없습니다 — 이미 200 을 보냈으니까요.
  if (typeof threadId !== "string" || threadId === "") {
    return c.json({ error: "threadId 가 필요합니다" }, 400);
  }
  if (typeof message !== "string" || message === "") {
    return c.json({ error: "message 가 필요합니다" }, 400);
  }

  return streamSSE(c, async (stream) => {
    // 클라이언트가 탭을 닫으면 이 신호가 옵니다.
    // 이걸 안 걸면 사용자가 떠난 뒤에도 모델이 계속 돌아가며 돈을 씁니다.
    const abort = new AbortController();
    stream.onAbort(() => {
      console.log(`[chat] 클라이언트 연결 끊김 thread=${threadId}`);
      abort.abort();
    });

    try {
      const agentStream = await agent.stream(
        { messages: [{ role: "user", content: message }] },
        {
          configurable: { thread_id: threadId },
          streamMode: "messages",
          signal: abort.signal,
        },
      );

      for await (const [chunk] of agentStream) {
        const text = chunk?.text;
        if (typeof text === "string" && text !== "") {
          await stream.writeSSE({ event: "token", data: text });
        }
      }

      // 정상 종료를 **명시적으로** 알립니다.
      // 이게 없으면 클라이언트는 "스트림이 끝난 것"과 "서버가 죽어서 끊긴 것"을
      // 구분할 방법이 없습니다.
      await stream.writeSSE({ event: "done", data: "ok" });
    } catch (err) {
      if (abort.signal.aborted) return; // 사용자가 떠난 것 — 에러가 아닙니다.

      if (err instanceof GraphDrained) {
        // 배포 중이라 중단됐습니다. 체크포인트는 저장됐으니 재개할 수 있습니다.
        await stream.writeSSE({ event: "interrupted", data: "재배포 중입니다. 곧 이어집니다." });
        return;
      }

      console.error(`[chat] 스트림 도중 실패 thread=${threadId}`, err);

      // ⚠️ 여기가 스트리밍의 함정입니다.
      // 이미 토큰을 100개 보냈다면 그건 되돌릴 수 없습니다. 500 을 줄 수도 없습니다.
      // 할 수 있는 건 "error 이벤트"를 보내는 것뿐이고,
      // **클라이언트가 그 이벤트를 보고 화면을 정리해 줘야** 합니다.
      // 클라이언트가 error 이벤트를 무시하면, 사용자는 문장 중간에서 잘린
      // 답을 "완성된 답"으로 읽게 됩니다.
      await stream.writeSSE({ event: "error", data: "응답 생성 중 오류가 발생했습니다." });
    }
  });
});

/* ===== [20-3] Graceful shutdown ===== */

const server = serve({ fetch: app.fetch, port: 3000 }, (info) => {
  console.log(`서버 시작: http://localhost:${info.port}`);
});

/**
 * 배포/스케일다운 때 쿠버네티스는 SIGTERM 을 보내고 잠시 뒤 SIGKILL 합니다.
 * 아무 처리도 안 하면 진행 중이던 에이전트 실행이 그냥 증발합니다.
 *
 * 순서가 중요합니다:
 *   1) accepting=false → 헬스체크 503 → LB 가 새 트래픽을 안 준다
 *   2) 진행 중인 요청이 끝날 시간을 준다
 *   3) 그래도 안 끝나면 닫는다
 */
process.on("SIGTERM", () => {
  console.log("[shutdown] SIGTERM 수신 — 드레이닝 시작");
  accepting = false;

  // LB 가 헬스체크 실패를 알아챌 시간(보통 몇 초)을 벌어 줍니다.
  setTimeout(() => {
    server.close(() => {
      console.log("[shutdown] 종료");
      process.exit(0);
    });
  }, 5000);

  // 그래도 안 끝나는 요청이 있으면 강제 종료합니다.
  setTimeout(() => {
    console.warn("[shutdown] 시간 초과 — 강제 종료");
    process.exit(1);
  }, 30000);
});

/**
 * 참고: 개별 실행을 협조적으로 멈추고 싶다면 RunControl 을 씁니다.
 * invoke/stream 에 control 을 넘기고 requestDrain() 을 부르면
 * 다음 superstep 경계에서 멈추고 GraphDrained 를 던집니다. 체크포인트는 저장되므로
 * 나중에 같은 config 로 agent.invoke(null, config) 하면 이어서 실행됩니다.
 *
 *   const control = new RunControl();
 *   process.on("SIGTERM", () => control.requestDrain("sigterm"));
 *   await agent.invoke(input, { ...config, control });
 */
void RunControl; // 위 주석의 import 가 사용되지 않아 지워지는 것을 막습니다.
