# Spring Boot2

## Actuator(Health Check)

```java
// build.gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

```yaml
# application.yaml
management:
  endpoints:
    web:
      exposure:
        # 노출할 actuator 엔드포인트. '*'는 전체 (health,info 만 원하면 include: health,info)
        include: "*"
  endpoint:
    # /actuator/shutdown 으로 서비스 종료 가능
    shutdown:
      enabled: true
    # health 응답에 status 외 상세 내용까지 표시
    health:
      show-details: always
  info:
    env:
      enabled: true

# /actuator/info 에 노출할 애플리케이션 정보
info:
  app:
    name: blog
    description: Spring Boot 학습용 프로젝트
    version: 0.0.1-SNAPSHOT
    java:
      version: 21

# MaxMemoryHealthIndicator 가 사용할 최소 메모리 임계값 (이보다 작으면 DOWN)
health:
  min-memory-mb: 512
```

```java
// MaxMemoryHealthIndicator.java
package blog.actuator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * /actuator/health 에 커스텀 상태를 추가한다.
 * JVM 최대 메모리가 임계값보다 작으면 DOWN 으로 판정한다.
 */
@Slf4j
@Component
public class MaxMemoryHealthIndicator implements HealthIndicator {

    @Value("${health.min-memory-mb}")
    private long minMemoryMb;

    @Override
    public Health health() {

        long maxMemoryMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;

        log.info("Runtime max memory: {}MB, min memory: {}MB", maxMemoryMb, minMemoryMb);

        boolean invalid = maxMemoryMb < minMemoryMb;
        Status status = invalid ? Status.DOWN : Status.UP;

        return Health.status(status)
                .withDetail("maxMemoryMb", maxMemoryMb)
                .withDetail("minMemoryMb", minMemoryMb)
                .build();
    }
}

// ReadinessEndpoint.java
package blog.actuator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 커스텀 actuator 엔드포인트 /actuator/readiness
 */
@Slf4j
@Component
@Endpoint(id = "readiness")
public class ReadinessEndpoint {

    private String ready = "NOT_READY";

    /** GET /actuator/readiness */
    @ReadOperation
    public String getReadiness() {
        return ready;
    }

    /** POST /actuator/readiness */
    @WriteOperation
    public String writeOperation() {
        return ready + "_WRITE";
    }

    /** DELETE /actuator/readiness */
    @DeleteOperation
    public String deleteOperation() {
        return ready + "_DELETE";
    }

    /** 애플리케이션 기동이 완료되면 READY 로 전환 */
    @EventListener(ApplicationReadyEvent.class)
    public void setReady() {
        log.info("Application start complete setReady");
        ready = "READY";
    }
}

```

```html
<!-- src/main/resources/static/check.html-->
<!DOCTYPE html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta
      name="viewport"
      content="width=device-width, initial-scale=1"
    />
    <title>Actuator 모니터링</title>
    <style>
      :root {
        --page: #f9f9f7;
        --surface: #fcfcfb;
        --ink: #0b0b0b;
        --ink-2: #52514e;
        --muted: #898781;
        --grid: #e1e0d9;
        --baseline: #c3c2b7;
        --border: rgba(11, 11, 11, 0.1);

        --series-1: #2a78d6;
        --series-2: #1baf7a;

        --track: #cde2fb;

        --good: #0ca30c;
        --warning: #fab219;
        --serious: #ec835a;
        --critical: #d03b3b;
      }

      @media (prefers-color-scheme: dark) {
        :root {
          --page: #0d0d0d;
          --surface: #1a1a19;
          --ink: #ffffff;
          --ink-2: #c3c2b7;
          --muted: #898781;
          --grid: #2c2c2a;
          --baseline: #383835;
          --border: rgba(255, 255, 255, 0.1);

          --series-1: #3987e5;
          --series-2: #199e70;

          --track: #184f95;
        }
      }

      * {
        box-sizing: border-box;
      }

      body {
        margin: 0;
        padding: 28px 24px 48px;
        background: var(--page);
        color: var(--ink);
        font-family:
          system-ui,
          -apple-system,
          "Segoe UI",
          sans-serif;
        line-height: 1.5;
      }

      header {
        margin-bottom: 20px;
      }
      h1 {
        margin: 0 0 4px;
        font-size: 20px;
        font-weight: 600;
      }
      .sub {
        margin: 0;
        color: var(--ink-2);
        font-size: 13px;
      }

      /* ── 필터 행: 스코프하는 모든 것 위에 한 줄 ── */
      .filters {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 20px;
        flex-wrap: wrap;
      }
      .filters label {
        font-size: 12px;
        color: var(--ink-2);
        margin-right: 2px;
      }
      select,
      .btn {
        padding: 6px 10px;
        font: inherit;
        font-size: 13px;
        color: var(--ink);
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 8px;
        cursor: pointer;
      }
      .btn:hover,
      select:hover {
        border-color: var(--baseline);
      }
      .live {
        font-size: 12px;
        color: var(--muted);
        margin-left: auto;
      }

      /* ── 카드 ── */
      .card {
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 16px 18px;
      }
      .card-head {
        display: flex;
        align-items: baseline;
        gap: 10px;
        margin-bottom: 4px;
      }
      .card-title {
        font-size: 14px;
        font-weight: 600;
        margin: 0;
      }
      .card-sub {
        font-size: 12px;
        color: var(--muted);
        margin: 0;
      }
      .card-head .btn {
        margin-left: auto;
        padding: 3px 9px;
        font-size: 11px;
        border-radius: 6px;
      }

      .grid {
        display: grid;
        gap: 16px;
        margin-bottom: 16px;
      }
      .g-kpi {
        grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
      }
      .g-two {
        grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
      }

      /* ── 스탯 타일 ── */
      .tile .label {
        font-size: 12px;
        color: var(--ink-2);
      }
      .tile .value {
        font-size: 28px;
        font-weight: 600;
        margin-top: 2px;
        letter-spacing: -0.01em;
      }
      .tile .foot {
        font-size: 12px;
        color: var(--muted);
        margin-top: 2px;
      }

      /* ── 상태 칩 (아이콘 + 라벨 + 색: 색 단독으로 의미를 전달하지 않음) ── */
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-top: 10px;
      }
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 5px 11px 5px 9px;
        border: 1px solid var(--border);
        border-radius: 999px;
        font-size: 12px;
        color: var(--ink-2);
        background: var(--surface);
      }
      .chip .ico {
        font-size: 11px;
        font-weight: 700;
        line-height: 1;
      }
      .chip .nm {
        color: var(--ink);
        font-weight: 500;
      }

      /* ── 표 보기 ── */
      table {
        width: 100%;
        border-collapse: collapse;
        font-size: 12.5px;
        margin-top: 10px;
      }
      th,
      td {
        text-align: right;
        padding: 5px 8px;
        border-bottom: 1px solid var(--grid);
        font-variant-numeric: tabular-nums;
      }
      th:first-child,
      td:first-child {
        text-align: left;
        font-variant-numeric: normal;
      }
      th {
        color: var(--muted);
        font-weight: 500;
      }
      .tablewrap {
        max-height: 240px;
        overflow: auto;
      }
      .hidden {
        display: none;
      }

      /* ── 범례 ── */
      .legend {
        display: flex;
        gap: 16px;
        margin-top: 2px;
      }
      .legend .key {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--ink-2);
      }
      .legend .swatch-line {
        width: 14px;
        height: 2px;
        border-radius: 1px;
      }

      /* ── 툴팁 ── */
      .tip {
        position: fixed;
        pointer-events: none;
        z-index: 10;
        min-width: 130px;
        padding: 8px 10px;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 8px;
        box-shadow: 0 4px 16px rgba(0, 0, 0, 0.14);
        font-size: 12px;
        opacity: 0;
        transition: opacity 0.1s;
      }
      .tip.on {
        opacity: 1;
      }
      .tip .t-head {
        color: var(--muted);
        margin-bottom: 5px;
        font-variant-numeric: tabular-nums;
      }
      .tip .t-row {
        display: flex;
        align-items: center;
        gap: 6px;
        margin-top: 3px;
      }
      .tip .t-key {
        width: 12px;
        height: 2px;
        border-radius: 1px;
        flex: none;
      }
      .tip .t-name {
        color: var(--ink-2);
      }
      .tip .t-val {
        margin-left: auto;
        font-weight: 600;
        color: var(--ink);
        font-variant-numeric: tabular-nums;
      }

      svg {
        display: block;
        width: 100%;
      }
      svg text {
        font-family:
          system-ui,
          -apple-system,
          "Segoe UI",
          sans-serif;
      }

      details.raw {
        margin-top: 16px;
      }
      details.raw summary {
        cursor: pointer;
        font-size: 13px;
        color: var(--ink-2);
        padding: 6px 0;
      }
      .raw-grid {
        display: grid;
        grid-template-columns: 260px 1fr;
        gap: 16px;
        margin-top: 10px;
      }
      .ep {
        display: block;
        width: 100%;
        padding: 9px 12px;
        border: 0;
        border-bottom: 1px solid var(--grid);
        background: transparent;
        color: var(--ink);
        text-align: left;
        cursor: pointer;
        font: inherit;
        font-size: 13px;
      }
      .ep:last-child {
        border-bottom: 0;
      }
      .ep:hover {
        background: var(--page);
      }
      .ep .p {
        font-family: ui-monospace, Menlo, monospace;
        font-size: 12.5px;
      }
      .ep .d {
        font-size: 11.5px;
        color: var(--muted);
      }
      pre {
        margin: 0;
        padding: 12px;
        max-height: 380px;
        overflow: auto;
        background: var(--page);
        border: 1px solid var(--border);
        border-radius: 8px;
        font-family: ui-monospace, Menlo, monospace;
        font-size: 12px;
        color: var(--ink-2);
        white-space: pre-wrap;
        word-break: break-all;
      }
    </style>
  </head>
  <body>
    <header>
      <h1>Actuator 모니터링</h1>
      <p class="sub">Spring Boot Actuator 지표를 차트로 확인합니다. 인증: aaa / bbb</p>
    </header>

    <!-- 필터 한 줄: 아래 모든 카드에 동일하게 적용된다 -->
    <div class="filters">
      <label for="interval">갱신 주기</label>
      <select id="interval">
        <option value="1000">1초</option>
        <option
          value="2000"
          selected
        >
          2초
        </option>
        <option value="5000">5초</option>
        <option value="0">중지</option>
      </select>
      <label for="window">표시 구간</label>
      <select id="window">
        <option
          value="30"
          selected
        >
          최근 30포인트
        </option>
        <option value="60">최근 60포인트</option>
        <option value="120">최근 120포인트</option>
      </select>
      <button
        class="btn"
        id="refreshBtn"
      >
        지금 갱신
      </button>
      <span
        class="live"
        id="live"
        >—</span
      >
    </div>

    <!-- KPI -->
    <div
      class="grid g-kpi"
      id="kpi"
    ></div>

    <!-- 헬스 -->
    <div
      class="grid"
      style="grid-template-columns:1fr;"
    >
      <div class="card">
        <div class="card-head">
          <h2 class="card-title">서비스 상태</h2>
          <p class="card-sub">/actuator/health</p>
        </div>
        <div
          class="chips"
          id="healthChips"
        ></div>
      </div>
    </div>

    <!-- 시계열 -->
    <div class="grid g-two">
      <div class="card">
        <div class="card-head">
          <h2 class="card-title">JVM 메모리 사용량</h2>
          <p class="card-sub">MB</p>
          <button
            class="btn"
            data-toggle="memTable"
          >
            표로 보기
          </button>
        </div>
        <div
          class="legend"
          id="memLegend"
        ></div>
        <svg
          id="memChart"
          height="220"
          role="img"
          aria-label="JVM 메모리 사용량 추이"
        ></svg>
        <div
          class="tablewrap hidden"
          id="memTable"
        ></div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2 class="card-title">CPU 사용률</h2>
          <p class="card-sub">%</p>
          <button
            class="btn"
            data-toggle="cpuTable"
          >
            표로 보기
          </button>
        </div>
        <div
          class="legend"
          id="cpuLegend"
        ></div>
        <svg
          id="cpuChart"
          height="220"
          role="img"
          aria-label="CPU 사용률 추이"
        ></svg>
        <div
          class="tablewrap hidden"
          id="cpuTable"
        ></div>
      </div>
    </div>

    <!-- 분포 -->
    <div class="grid g-two">
      <div class="card">
        <div class="card-head">
          <h2 class="card-title">메모리 영역별 사용량</h2>
          <p class="card-sub">MB · jvm.memory.used</p>
          <button
            class="btn"
            data-toggle="poolTable"
          >
            표로 보기
          </button>
        </div>
        <svg
          id="poolChart"
          height="220"
          role="img"
          aria-label="메모리 영역별 사용량"
        ></svg>
        <div
          class="tablewrap hidden"
          id="poolTable"
        ></div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2 class="card-title">로그 레벨 분포</h2>
          <p class="card-sub">로거 수 · 실제 적용 레벨(effectiveLevel) 기준</p>
          <button
            class="btn"
            data-toggle="logTable"
          >
            표로 보기
          </button>
        </div>
        <svg
          id="logChart"
          height="220"
          role="img"
          aria-label="로그 레벨 분포"
        ></svg>
        <div
          class="tablewrap hidden"
          id="logTable"
        ></div>
      </div>
    </div>

    <!-- 디스크 -->
    <div
      class="grid"
      style="grid-template-columns:1fr;"
    >
      <div class="card">
        <div class="card-head">
          <h2 class="card-title">디스크 사용률</h2>
          <p class="card-sub">/actuator/health · diskSpace</p>
        </div>
        <svg
          id="diskMeter"
          height="72"
          role="img"
          aria-label="디스크 사용률"
        ></svg>
      </div>
    </div>

    <!-- 원본 JSON -->
    <details class="raw">
      <summary>원본 JSON 보기 (8개 엔드포인트)</summary>
      <div class="raw-grid">
        <div
          class="card"
          style="padding:0;overflow:hidden;"
          id="epList"
        ></div>
        <pre id="rawOut">왼쪽에서 엔드포인트를 선택하세요.</pre>
      </div>
    </details>

    <div
      class="tip"
      id="tip"
    ></div>

    <script>
      "use strict";

      const BASE = location.pathname.replace(/\/check\.html$/, "") + "/actuator";

      /* ─────────── 색 토큰 (검증된 팔레트) ─────────── */
      const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
      const C = () => ({
        s1: css("--series-1"),
        s2: css("--series-2"),
        surface: css("--surface"),
        grid: css("--grid"),
        baseline: css("--baseline"),
        muted: css("--muted"),
        ink2: css("--ink-2"),
        track: css("--track"),
        good: css("--good"),
        warning: css("--warning"),
        critical: css("--critical"),
      });

      /* ─────────── 상태 (시계열 버퍼) ─────────── */
      const MAXBUF = 120;
      const hist = { t: [], heap: [], nonheap: [], sys: [], proc: [] };
      let windowSize = 30;
      let timer = null;

      /* ─────────── 유틸 ─────────── */
      const mb = (b) => b / 1024 / 1024;
      const fmt = (v, d = 0) => v.toLocaleString("ko-KR", { minimumFractionDigits: d, maximumFractionDigits: d });
      const clock = (d) => d.toTimeString().slice(0, 8);

      function uptimeText(sec) {
        const h = Math.floor(sec / 3600),
          m = Math.floor((sec % 3600) / 60),
          s = Math.floor(sec % 60);
        if (h) return `${h}시간 ${m}분`;
        if (m) return `${m}분 ${s}초`;
        return `${s}초`;
      }

      async function get(path) {
        const r = await fetch(BASE + path);
        if (!r.ok) throw new Error(path + " → HTTP " + r.status);
        return r.json();
      }
      async function metric(name, tag) {
        const d = await get("/metrics/" + name + (tag ? "?tag=" + tag : ""));
        return d.measurements[0].value;
      }
      async function metricCount(name) {
        const d = await get("/metrics/" + name);
        const c = d.measurements.find((m) => m.statistic === "COUNT");
        return c ? c.value : d.measurements[0].value;
      }

      /* 축 눈금을 깔끔한 수(1/2/5×10ⁿ)로 맞춘다 */
      function niceStep(raw) {
        const exp = Math.pow(10, Math.floor(Math.log10(raw)));
        const f = raw / exp;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * exp;
      }
      function niceScale(max, ticks) {
        if (max <= 0) return { max: ticks, step: 1 };
        const step = niceStep(max / ticks);
        return { max: step * ticks, step };
      }

      /* 라벨을 그리기 전에 실제 렌더 폭을 잰다 (클리핑 방지) */
      const _ctx = document.createElement("canvas").getContext("2d");
      function measureText(s, px) {
        _ctx.font = `${px}px system-ui, -apple-system, "Segoe UI", sans-serif`;
        return _ctx.measureText(s).width;
      }

      const NS = "http://www.w3.org/2000/svg";
      function el(tag, attrs = {}) {
        const n = document.createElementNS(NS, tag);
        for (const k in attrs) n.setAttribute(k, attrs[k]);
        return n;
      }
      function clear(svg) {
        while (svg.firstChild) svg.removeChild(svg.firstChild);
      }

      /* ─────────── 툴팁 ─────────── */
      const tipEl = document.getElementById("tip");
      function showTip(x, y, head, rows) {
        tipEl.textContent = "";
        const h = document.createElement("div");
        h.className = "t-head";
        h.textContent = head;
        tipEl.appendChild(h);
        rows.forEach((r) => {
          const row = document.createElement("div");
          row.className = "t-row";
          if (r.color) {
            const k = document.createElement("span");
            k.className = "t-key";
            k.style.background = r.color;
            row.appendChild(k);
          }
          const nm = document.createElement("span");
          nm.className = "t-name";
          nm.textContent = r.name; // 외부 데이터 → textContent
          const vl = document.createElement("span");
          vl.className = "t-val";
          vl.textContent = r.value;
          row.append(nm, vl);
          tipEl.appendChild(row);
        });
        tipEl.classList.add("on");
        const w = tipEl.offsetWidth,
          hh = tipEl.offsetHeight;
        tipEl.style.left = Math.min(x + 14, innerWidth - w - 8) + "px";
        tipEl.style.top = Math.max(8, y - hh - 12) + "px";
      }
      const hideTip = () => tipEl.classList.remove("on");

      /* ─────────── 라인 차트 ─────────── */
      function lineChart(svg, series, times, opt) {
        const c = C();
        const W = svg.clientWidth || 520,
          H = +svg.getAttribute("height");
        const P = { t: 14, r: 62, b: 26, l: 46 };
        const pw = W - P.l - P.r,
          ph = H - P.t - P.b;

        clear(svg);
        svg.setAttribute("viewBox", `0 0 ${W} ${H}`);

        const n = times.length;
        if (!n) return;

        const TICKS = 4;
        let peak = 0;
        series.forEach((s) =>
          s.data.forEach((v) => {
            if (v > peak) peak = v;
          }),
        );

        // 축 상단은 깔끔한 수로 (0 / 50 / 100 …)
        const max = opt.fixedMax != null ? opt.fixedMax : niceScale(Math.max(peak * 1.1, opt.minMax || 1), TICKS).max;

        const X = (i) => P.l + (n === 1 ? pw : (i / (n - 1)) * pw);
        const Y = (v) => P.t + ph - (v / max) * ph;

        // 그리드: 실선 헤어라인, 후퇴색
        for (let i = 0; i <= TICKS; i++) {
          const v = (max * i) / TICKS,
            y = Y(v);
          svg.appendChild(el("line", { x1: P.l, x2: P.l + pw, y1: y, y2: y, stroke: c.grid, "stroke-width": 1 }));
          const tx = el("text", {
            x: P.l - 8,
            y: y + 4,
            "text-anchor": "end",
            "font-size": 10.5,
            fill: c.muted,
            style: "font-variant-numeric:tabular-nums",
          });
          tx.textContent = fmt(v, opt.decimals || 0);
          svg.appendChild(tx);
        }
        // 베이스라인
        svg.appendChild(
          el("line", { x1: P.l, x2: P.l + pw, y1: P.t + ph, y2: P.t + ph, stroke: c.baseline, "stroke-width": 1 }),
        );

        // x축 라벨 (양 끝 + 중앙)
        [0, Math.floor((n - 1) / 2), n - 1]
          .filter((v, i, a) => a.indexOf(v) === i)
          .forEach((i) => {
            const tx = el("text", {
              x: X(i),
              y: H - 8,
              "text-anchor": i === 0 ? "start" : i === n - 1 ? "end" : "middle",
              "font-size": 10.5,
              fill: c.muted,
              style: "font-variant-numeric:tabular-nums",
            });
            tx.textContent = times[i];
            svg.appendChild(tx);
          });

        // 선 (2px) + 끝점 마커(8px, 2px 서피스 링) + 끝 직접 라벨
        series.forEach((s) => {
          const d = s.data.map((v, i) => `${i ? "L" : "M"}${X(i)},${Y(v)}`).join(" ");
          svg.appendChild(
            el("path", {
              d,
              fill: "none",
              stroke: s.color,
              "stroke-width": 2,
              "stroke-linejoin": "round",
              "stroke-linecap": "round",
            }),
          );

          const li = n - 1,
            lv = s.data[li];
          svg.appendChild(
            el("circle", { cx: X(li), cy: Y(lv), r: 4, fill: s.color, stroke: c.surface, "stroke-width": 2 }),
          );

          const lb = el("text", {
            x: X(li) + 9,
            y: Y(lv) + 4,
            "font-size": 11,
            fill: c.ink2,
            style: "font-variant-numeric:tabular-nums",
          });
          lb.textContent = fmt(lv, opt.decimals || 0) + (opt.unit || "");
          svg.appendChild(lb);
        });

        // 크로스헤어 + 히트 레이어
        const cross = el("line", { y1: P.t, y2: P.t + ph, stroke: c.baseline, "stroke-width": 1, opacity: 0 });
        svg.appendChild(cross);
        const dots = series.map((s) => {
          const d = el("circle", { r: 4, fill: s.color, stroke: c.surface, "stroke-width": 2, opacity: 0 });
          svg.appendChild(d);
          return d;
        });

        const hit = el("rect", { x: P.l, y: P.t, width: pw, height: ph, fill: "transparent" });
        svg.appendChild(hit);
        hit.addEventListener("pointermove", (e) => {
          const r = svg.getBoundingClientRect();
          const rel = (e.clientX - r.left) * (W / r.width);
          let i = n === 1 ? 0 : Math.round(((rel - P.l) / pw) * (n - 1));
          i = Math.max(0, Math.min(n - 1, i));
          cross.setAttribute("x1", X(i));
          cross.setAttribute("x2", X(i));
          cross.setAttribute("opacity", 1);
          series.forEach((s, k) => {
            dots[k].setAttribute("cx", X(i));
            dots[k].setAttribute("cy", Y(s.data[i]));
            dots[k].setAttribute("opacity", 1);
          });
          showTip(
            e.clientX,
            e.clientY,
            times[i],
            series.map((s) => ({
              color: s.color,
              name: s.name,
              value: fmt(s.data[i], opt.decimals || 0) + (opt.unit || ""),
            })),
          );
        });
        hit.addEventListener("pointerleave", () => {
          cross.setAttribute("opacity", 0);
          dots.forEach((d) => d.setAttribute("opacity", 0));
          hideTip();
        });
      }

      function legend(box, series) {
        box.textContent = "";
        series.forEach((s) => {
          const k = document.createElement("span");
          k.className = "key";
          const sw = document.createElement("span");
          sw.className = "swatch-line";
          sw.style.background = s.color;
          const nm = document.createElement("span");
          nm.textContent = s.name;
          k.append(sw, nm);
          box.appendChild(k);
        });
      }

      /* ─────────── 가로 막대 (명목형 → 단일 색) ─────────── */
      function barChart(svg, rows, opt) {
        const c = C();
        const W = svg.clientWidth || 520,
          H = +svg.getAttribute("height");

        clear(svg);
        svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
        if (!rows.length) return;

        // 가장 긴 라벨이 잘리지 않도록 좌측 여백을 실제 텍스트 폭으로 계산한다
        const longest = rows.reduce((a, r) => (r.name.length > a.length ? r.name : a), "");
        const labelW = Math.min(measureText(longest, 11), W * 0.42);
        const P = { t: 8, r: 66, b: 8, l: Math.ceil(labelW) + 14 };
        const pw = W - P.l - P.r,
          ph = H - P.t - P.b;

        const max = Math.max(...rows.map((r) => r.value)) || 1;
        const band = ph / rows.length;
        const TH = Math.min(18, band - 8); // ≤24px, 밴드 여백 확보
        const X = (v) => (v / max) * pw;

        // 베이스라인
        svg.appendChild(el("line", { x1: P.l, x2: P.l, y1: P.t, y2: P.t + ph, stroke: c.baseline, "stroke-width": 1 }));

        rows.forEach((r, i) => {
          const y = P.t + i * band + (band - TH) / 2;
          const w = Math.max(X(r.value), 2);

          // 데이터 끝 4px 라운드, 베이스라인 쪽은 직각
          const g = el("g");
          const path = el("path", {
            d: `M${P.l},${y} H${P.l + w - 4} a4,4 0 0 1 4,4 V${y + TH - 4} a4,4 0 0 1 -4,4 H${P.l} Z`,
            fill: opt.color,
          });
          g.appendChild(path);

          const nm = el("text", {
            x: P.l - 10,
            y: y + TH / 2 + 4,
            "text-anchor": "end",
            "font-size": 11,
            fill: c.ink2,
          });
          nm.textContent = r.name; // 외부 데이터 → textContent
          g.appendChild(nm);

          const vl = el("text", {
            x: P.l + w + 8,
            y: y + TH / 2 + 4,
            "font-size": 11,
            fill: c.ink2,
            style: "font-variant-numeric:tabular-nums",
          });
          vl.textContent = fmt(r.value, opt.decimals || 0) + (opt.unit || "");
          g.appendChild(vl);

          // 히트 영역은 마크보다 크게
          const hit = el("rect", { x: P.l, y: P.t + i * band, width: pw + 60, height: band, fill: "transparent" });
          hit.addEventListener("pointermove", (e) => {
            path.setAttribute("opacity", 0.78);
            showTip(e.clientX, e.clientY, r.name, [
              { color: opt.color, name: opt.metric, value: fmt(r.value, opt.decimals || 0) + (opt.unit || "") },
            ]);
          });
          hit.addEventListener("pointerleave", () => {
            path.setAttribute("opacity", 1);
            hideTip();
          });
          g.appendChild(hit);

          svg.appendChild(g);
        });
      }

      /* ─────────── 미터 (단일 비율 vs 한계) ─────────── */
      function meter(svg, used, total) {
        const c = C();
        const W = svg.clientWidth || 800,
          H = +svg.getAttribute("height");
        clear(svg);
        svg.setAttribute("viewBox", `0 0 ${W} ${H}`);

        const pct = (used / total) * 100;
        const fill = pct >= 90 ? c.critical : pct >= 80 ? c.warning : c.s1;

        const x = 0,
          y = 30,
          w = W,
          h = 14;
        svg.appendChild(el("rect", { x, y, width: w, height: h, rx: 7, fill: c.track }));
        svg.appendChild(el("rect", { x, y, width: Math.max((w * pct) / 100, 8), height: h, rx: 7, fill }));

        const lb = el("text", { x: 0, y: 18, "font-size": 12, fill: c.ink2 });
        lb.textContent = `사용 ${fmt(used / 1e9, 1)}GB / 전체 ${fmt(total / 1e9, 1)}GB`;
        svg.appendChild(lb);

        const pv = el("text", {
          x: W,
          y: 18,
          "text-anchor": "end",
          "font-size": 12,
          fill: c.ink2,
          style: "font-variant-numeric:tabular-nums",
        });
        pv.textContent = fmt(pct, 1) + "%";
        svg.appendChild(pv);

        const rm = el("text", { x: W, y: y + h + 16, "text-anchor": "end", "font-size": 11, fill: c.muted });
        rm.textContent = `여유 ${fmt((total - used) / 1e9, 1)}GB`;
        svg.appendChild(rm);
      }

      /* ─────────── 표 보기 ─────────── */
      function renderTable(box, cols, rows) {
        box.textContent = "";
        const t = document.createElement("table");
        const thead = document.createElement("thead");
        const hr = document.createElement("tr");
        cols.forEach((cname) => {
          const th = document.createElement("th");
          th.textContent = cname;
          hr.appendChild(th);
        });
        thead.appendChild(hr);
        const tb = document.createElement("tbody");
        rows.forEach((r) => {
          const tr = document.createElement("tr");
          r.forEach((v) => {
            const td = document.createElement("td");
            td.textContent = v;
            tr.appendChild(td);
          });
          tb.appendChild(tr);
        });
        t.append(thead, tb);
        box.appendChild(t);
      }

      document.querySelectorAll("[data-toggle]").forEach((b) => {
        b.onclick = () => {
          const box = document.getElementById(b.dataset.toggle);
          const on = box.classList.toggle("hidden");
          b.textContent = on ? "표로 보기" : "표 숨기기";
        };
      });

      /* ─────────── 스탯 타일 ─────────── */
      function tiles(items) {
        const box = document.getElementById("kpi");
        box.textContent = "";
        items.forEach((it) => {
          const d = document.createElement("div");
          d.className = "card tile";
          const l = document.createElement("div");
          l.className = "label";
          l.textContent = it.label;
          const v = document.createElement("div");
          v.className = "value";
          v.textContent = it.value;
          const f = document.createElement("div");
          f.className = "foot";
          f.textContent = it.foot || "";
          d.append(l, v, f);
          box.appendChild(d);
        });
      }

      /* ─────────── 헬스 칩 (아이콘 + 라벨 + 색) ─────────── */
      function healthChips(health) {
        const c = C();
        const box = document.getElementById("healthChips");
        box.textContent = "";
        const mark = (s) =>
          s === "UP"
            ? { ico: "✔", col: c.good }
            : s === "UNKNOWN"
              ? { ico: "!", col: c.warning }
              : { ico: "✕", col: c.critical };

        const all = [
          ["전체", health.status],
          ...Object.entries(health.components || {}).map(([k, v]) => [k, v.status]),
        ];
        all.forEach(([name, status]) => {
          const m = mark(status);
          const chip = document.createElement("span");
          chip.className = "chip";
          const i = document.createElement("span");
          i.className = "ico";
          i.style.color = m.col;
          i.textContent = m.ico;
          const n = document.createElement("span");
          n.className = "nm";
          n.textContent = name;
          const s = document.createElement("span");
          s.textContent = status;
          chip.append(i, n, s);
          box.appendChild(chip);
        });
      }

      /* ─────────── 데이터 수집 & 렌더 ─────────── */
      let poolTags = [];

      async function tick() {
        const c = C();
        try {
          const [health, heap, nonheap, heapMax, sys, proc, up, thr, dmn] = await Promise.all([
            get("/health"),
            metric("jvm.memory.used", "area:heap"),
            metric("jvm.memory.used", "area:nonheap"),
            metric("jvm.memory.max", "area:heap"),
            metric("system.cpu.usage"),
            metric("process.cpu.usage"),
            metric("process.uptime"),
            metric("jvm.threads.live"),
            metric("jvm.threads.daemon"),
          ]);
          const reqs = await metricCount("http.server.requests").catch(() => 0);

          // 버퍼 적재
          hist.t.push(clock(new Date()));
          hist.heap.push(mb(heap));
          hist.nonheap.push(mb(nonheap));
          hist.sys.push(sys * 100);
          hist.proc.push(proc * 100);
          for (const k in hist) if (hist[k].length > MAXBUF) hist[k].shift();

          const N = Math.min(windowSize, hist.t.length);
          const sl = (a) => a.slice(-N);
          const times = sl(hist.t);

          // KPI
          tiles([
            { label: "가동 시간", value: uptimeText(up), foot: "process.uptime" },
            {
              label: "힙 사용",
              value: fmt(mb(heap)) + " MB",
              foot: `최대 ${fmt(mb(heapMax))} MB · ${fmt((heap / heapMax) * 100, 1)}%`,
            },
            { label: "스레드", value: fmt(thr), foot: `데몬 ${fmt(dmn)}` },
            { label: "HTTP 요청", value: fmt(reqs), foot: "http.server.requests" },
          ]);

          healthChips(health);

          // 메모리 시계열 (두 계열 · 단일 축 MB)
          const memS = [
            { name: "heap", color: c.s1, data: sl(hist.heap) },
            { name: "nonheap", color: c.s2, data: sl(hist.nonheap) },
          ];
          legend(document.getElementById("memLegend"), memS);
          lineChart(document.getElementById("memChart"), memS, times, { unit: "", decimals: 0, minMax: 64 });
          renderTable(
            document.getElementById("memTable"),
            ["시각", "heap (MB)", "nonheap (MB)"],
            times.map((t, i) => [t, fmt(memS[0].data[i]), fmt(memS[1].data[i])]).reverse(),
          );

          // CPU 시계열 (두 계열 · 단일 축 %)
          const cpuS = [
            { name: "system", color: c.s1, data: sl(hist.sys) },
            { name: "process", color: c.s2, data: sl(hist.proc) },
          ];
          legend(document.getElementById("cpuLegend"), cpuS);
          lineChart(document.getElementById("cpuChart"), cpuS, times, { unit: "%", decimals: 1, fixedMax: 100 });
          renderTable(
            document.getElementById("cpuTable"),
            ["시각", "system (%)", "process (%)"],
            times.map((t, i) => [t, fmt(cpuS[0].data[i], 1), fmt(cpuS[1].data[i], 1)]).reverse(),
          );

          // 메모리 영역별 (명목형 → 단일 색)
          if (!poolTags.length) {
            const d = await get("/metrics/jvm.memory.used");
            poolTags = (d.availableTags.find((t) => t.tag === "id") || { values: [] }).values;
          }
          const pools = await Promise.all(
            poolTags.map(async (id) => ({
              name: id,
              value: mb(await metric("jvm.memory.used", "id:" + encodeURIComponent(id))),
            })),
          );
          pools.sort((a, b) => b.value - a.value);
          barChart(document.getElementById("poolChart"), pools, {
            color: c.s1,
            unit: "",
            decimals: 1,
            metric: "jvm.memory.used",
          });
          renderTable(
            document.getElementById("poolTable"),
            ["영역", "사용 (MB)"],
            pools.map((p) => [p.name, fmt(p.value, 1)]),
          );

          // 디스크 미터
          const ds = health.components?.diskSpace?.details;
          if (ds) meter(document.getElementById("diskMeter"), ds.total - ds.free, ds.total);

          document.getElementById("live").textContent = "갱신 " + clock(new Date());
        } catch (e) {
          document.getElementById("live").textContent = "오류: " + e.message;
        }
      }

      /* 로그 레벨 분포 — 자주 변하지 않으므로 별도 갱신 */
      async function loadLoggers() {
        const c = C();
        try {
          const d = await get("/loggers");
          const count = {};
          // configuredLevel 은 명시 설정된 몇 개뿐이라, 실제 적용 레벨(effectiveLevel)로 센다
          Object.values(d.loggers).forEach((l) => {
            const lv = l.effectiveLevel || l.configuredLevel;
            if (lv) count[lv] = (count[lv] || 0) + 1;
          });
          const order = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"];
          const rows = order.filter((k) => count[k]).map((k) => ({ name: k, value: count[k] }));
          barChart(document.getElementById("logChart"), rows, { color: c.s1, decimals: 0, metric: "로거 수" });
          renderTable(
            document.getElementById("logTable"),
            ["레벨", "로거 수"],
            rows.map((r) => [r.name, fmt(r.value)]),
          );
        } catch (e) {
          /* loggers 미노출 시 무시 */
        }
      }

      /* ─────────── 원본 JSON 뷰어 ─────────── */
      const ENDPOINTS = [
        ["/health", "서비스 상태 확인"],
        ["/env", "Spring 의 모든 properties"],
        ["/info", "서비스 정보"],
        ["/metrics", "추적 가능한 지표 목록"],
        ["/loggers", "로그 레벨 조회/변경"],
        ["/configprops", "서비스 설정값"],
        ["/beans", "등록된 Bean 상세"],
        ["/mappings", "모든 RequestMapping"],
      ];
      const epList = document.getElementById("epList");
      const rawOut = document.getElementById("rawOut");
      ENDPOINTS.forEach(([p, d]) => {
        const b = document.createElement("button");
        b.className = "ep";
        const pp = document.createElement("div");
        pp.className = "p";
        pp.textContent = "/actuator" + p;
        const dd = document.createElement("div");
        dd.className = "d";
        dd.textContent = d;
        b.append(pp, dd);
        b.onclick = async () => {
          rawOut.textContent = "불러오는 중…";
          try {
            rawOut.textContent = JSON.stringify(await get(p), null, 2);
          } catch (e) {
            rawOut.textContent = e.message;
          }
        };
        epList.appendChild(b);
      });

      /* ─────────── 구동 ─────────── */
      function restart() {
        if (timer) clearInterval(timer);
        const ms = +document.getElementById("interval").value;
        if (ms) timer = setInterval(tick, ms);
      }
      document.getElementById("interval").onchange = restart;
      document.getElementById("window").onchange = (e) => {
        windowSize = +e.target.value;
        tick();
      };
      document.getElementById("refreshBtn").onclick = () => {
        tick();
        loadLoggers();
      };
      addEventListener("resize", () => tick());

      tick();
      loadLoggers();
      restart();
    </script>
  </body>
</html>
```

## Swagger

- Annotations 참고: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations

```java
// build.gradle
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6'
```

```java
// OpenApiConfig.java
package blog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("blog API")
                        .description("Spring Boot 학습용 프로젝트 API 문서")
                        .version("0.0.1-SNAPSHOT"))
                // Swagger UI 의 Authorize 버튼으로 Basic 인증(aaa/bbb)을 넣을 수 있게 한다
                .components(new Components()
                        .addSecuritySchemes(BASIC_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}

```
