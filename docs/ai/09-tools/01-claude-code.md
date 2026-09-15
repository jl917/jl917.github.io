# Claude Code

Anthropic 의 에이전트형 코딩 도구입니다. 터미널(및 IDE·데스크톱·웹)에서 파일을 읽고, 명령을 실행하고, 코드를 고치고, 스스로 결과를 확인하며 작업을 진행합니다. 이 문서는 사용 팁 모음이 아니라 **작업 방식을 파일(CLAUDE.md·스킬·서브에이전트·훅)로 코드화해 반복 가능한 개발 시스템을 만드는** 실전 가이드입니다.

## 시작하기

```bash
# 설치 (macOS / Linux / WSL) — Windows 는 PowerShell 에서 irm https://claude.ai/install.ps1 | iex
curl -fsSL https://claude.ai/install.sh | bash

cd my-project
claude                      # 대화형 세션
claude -p "이 프로젝트 요약"   # 비대화형 한 번 실행 후 종료
claude -c                   # 현재 디렉터리의 마지막 대화 이어서
```

| 조작 | 기능 |
|---|---|
| `/init` | 코드베이스를 분석해 시작용 `CLAUDE.md` 생성 |
| `/clear` | 대화 컨텍스트 초기화 — 관련 없는 작업 사이마다 |
| `/compact <지시>` | 대화를 요약해 컨텍스트 확보 |
| `/context` | 컨텍스트 사용량과 로드된 메모리 파일 확인 |
| `/memory` | CLAUDE.md·자동 메모리 파일 열기 |
| `/rewind` (또는 `Esc` 두 번) | 이전 체크포인트로 대화·코드 되돌리기 |
| `Esc` | 진행 중인 작업 중단 (컨텍스트는 유지) |
| `Shift+Tab` | 권한 모드 전환 (plan mode 포함) |
| `@파일경로` | 파일을 직접 참조 |

## 확장 기능 한눈에

| 기능 | 위치 | 언제 로드되나 | 강제력 | 용도 |
|---|---|---|---|---|
| **CLAUDE.md** | `./CLAUDE.md`, `~/.claude/CLAUDE.md` | 매 세션 시작 | 없음 (지침) | 명령어, 규칙, 구조 |
| **Rules** | `.claude/rules/*.md` | 시작 시 또는 `paths` 일치 파일을 읽을 때 | 없음 | 파일 종류별 규칙 |
| **Skills** | `.claude/skills/<이름>/SKILL.md` | 설명만 상주, 호출 시 본문 로드 | 없음 | 반복 워크플로, 도메인 지식 |
| **Subagents** | `.claude/agents/<이름>.md` | 위임할 때 별도 컨텍스트로 | 도구 제한은 강제 | 조사·리뷰 등 격리 작업 |
| **Hooks** | `.claude/settings.json` 의 `hooks` | 라이프사이클 이벤트마다 | **강제 (결정적)** | 포맷, 린트, 위험 명령 차단 |
| **MCP** | `.mcp.json`, `claude mcp add` | 세션 시작 시 연결 | 권한 규칙 적용 | 외부 도구·데이터 연결 |
| **Plugins** | 마켓플레이스 / `--plugin-dir` | 활성화 시 | 구성 요소에 따름 | 위 기능을 묶어 팀에 배포 |
| **Headless / Actions** | `claude -p`, GitHub Actions | 스크립트·CI 실행 시 | 권한 플래그로 제한 | 자동화 |

> ⚠️ **함정**: CLAUDE.md·스킬·규칙은 **컨텍스트일 뿐 강제 설정이 아닙니다.** "절대 main 에 push 하지 마"를 적어도 모델은 어길 수 있습니다. 반드시 지켜야 하는 것은 **훅(`PreToolUse`)이나 권한 규칙(`permissions.deny`)** 으로 막으세요. → [Rule](/ai/03-prompt/05-rule)

## 1단계: End-to-End 한 사이클 완주

목표: 불완전해도 좋으니 **아이디어 → 동작하는 결과물 → 실행**까지 한 번 끝냅니다. 도구에 대한 감을 잡는 가장 빠른 방법입니다.

공식 권장 흐름은 **탐색(Explore) → 계획(Plan) → 구현(Implement) → 커밋(Commit)** 입니다.

```text
# 1. plan mode 에서 탐색 — 파일을 읽기만 하고 수정하지 않음
src/auth 를 읽고 세션과 로그인을 어떻게 처리하는지 파악해줘.

# 2. 계획
Google OAuth 를 추가하려면 어떤 파일을 바꿔야 해? 계획을 세워줘.

# 3. plan mode 를 나와 구현 + 검증
계획대로 OAuth 흐름을 구현하고, 콜백 핸들러 테스트를 작성해서 전체 테스트를 통과시켜줘.

# 4. 커밋
변경 내용을 설명하는 메시지로 커밋하고 PR 을 열어줘.
```

원칙:

- 범위를 강제로 줄입니다 (MVP). 외부 의존은 최소화합니다.
- 완료 기준(DoD)을 "동작"으로 정의합니다.
- **Claude 가 스스로 돌릴 수 있는 검증 수단을 줍니다.** 테스트, 빌드, 린터, 스크린샷 비교처럼 성공/실패가 나오는 것이면 됩니다. 검증 수단이 없으면 "끝난 것처럼 보이는" 시점에 멈추고, 검증은 결국 사람 몫이 됩니다.

체크리스트:

- [ ] 실행 가능한 진입점 (CLI / HTTP / UI)
- [ ] 실패 케이스 최소 1개 처리
- [ ] 로그·에러를 확인할 수 있음
- [ ] 실행 방법이 README 에 있음

> ⚠️ **함정**: 수정 한 줄짜리 작업까지 plan mode 를 거치면 오버헤드만 늘어납니다. **변경 내용을 한 문장으로 설명할 수 있으면 계획을 건너뛰세요.** 계획은 접근 방법이 불확실하거나, 여러 파일을 건드리거나, 낯선 코드일 때 가치가 있습니다.

## 2단계: 프로젝트 부트스트랩 자동화

목표: 초기 설정과 자주 쓰는 명령을 매번 설명하지 않고, **파일로 재현 가능하게** 만듭니다.

1. `/init` 으로 시작용 `CLAUDE.md` 를 생성하고, 모델이 스스로 알 수 없는 것만 남기고 다듬습니다.
2. 개발·빌드·테스트 명령은 `CLAUDE.md` 맨 위 "명령어" 섹션에 둡니다.
3. 여러 단계로 된 반복 절차(릴리스, 이슈 수정 등)는 **스킬**로 만들어 `/이름` 으로 호출합니다.

```text
project/
  CLAUDE.md                 # 명령어·구조·규칙 (팀 공유, git 커밋)
  CLAUDE.local.md           # 개인 설정 (.gitignore)
  .mcp.json                 # 프로젝트 공용 MCP 서버
  .claude/
    settings.json           # 권한·훅 (팀 공유)
    settings.local.json     # 개인 설정 (gitignore)
    rules/                  # 경로별 규칙
    skills/
      release/SKILL.md      # /release
    agents/
      code-reviewer.md      # 서브에이전트
  src/
  tests/
```

```markdown
## 명령어
- 개발: pnpm dev
- 테스트: pnpm test (단일 파일: pnpm test path/to/file)
- 타입 검사: pnpm typecheck

작업을 끝냈다고 말하기 전에 typecheck 와 test 를 통과시킨다.
```

> ⚠️ **함정**: 예전의 `.claude/commands/*.md` 커스텀 슬래시 커맨드는 **스킬로 통합**됐습니다. 기존 파일은 계속 동작하지만, 새로 만든다면 보조 파일·호출 제어를 지원하는 `.claude/skills/<이름>/SKILL.md` 를 쓰세요. 또 `/init`, `/clear` 처럼 **내장 명령과 같은 이름**으로 만들면 헷갈리니 피하세요.

## 3단계: 명세 기반 개발 (Spec-Driven Development)

구현 전에 명세를 먼저 씁니다. 모델은 모호함을 추측으로 채우기 때문입니다.

```text
[Feature]
- 목적:
- 입력:
- 출력:

[Success Criteria]      ← 실행 가능한 검증으로 쓸 것
- ...

[Failure Cases]
- ...

[Constraints]
- 성능, 비용, 외부 API, 건드리면 안 되는 영역

[Out of Scope]
- ...
```

작성 규칙:

- 모호한 단어 금지 ("적당히", "가능하면") → 수치화 (latency, size, timeout)
- 입력/출력 예시 포함 → 그대로 테스트 케이스가 됩니다.
- 명세 마지막에 **end-to-end 검증 단계**를 둡니다.

큰 기능은 **Claude 에게 인터뷰를 시켜** 명세를 만드는 것이 효과적입니다.

```text
[간단한 기능 설명] 을 만들고 싶어. AskUserQuestion 도구로 나를 자세히 인터뷰해줘.
구현 방식, UI/UX, 엣지 케이스, 트레이드오프를 묻고, 뻔한 질문은 하지 마.
다 끝나면 완전한 명세를 SPEC.md 에 작성해줘.
```

명세가 완성되면 **새 세션**을 열어 구현합니다. 인터뷰 과정이 빠진 깨끗한 컨텍스트에서 명세만 보고 작업하게 하기 위해서입니다. 프롬프트 작성 원칙은 [프롬프트 엔지니어링](/ai/03-prompt/01-promptengineering)을 참고하세요.

## 4단계: 설계와 구현을 반복 (Iterative Architecture)

처음부터 완벽한 설계를 하지 않습니다(No Big Design Up Front).

사이클:

1. 최소 구조 정의
2. 구현
3. 병목·문제 발견
4. 리팩터링

패턴과 기술 포인트:

- Feature Slice 로 먼저 세로로 관통시키고, 이후 레이어를 분리합니다.
- 인터페이스를 먼저 정의하고 의존성을 역전(DI)해 테스트 가능한 구조를 유지합니다.
- 기존 코드의 패턴을 **구체적인 파일로 지목**합니다. ("`HotDogWidget.tsx` 의 패턴을 따라 달력 위젯을 만들어줘")

Claude Code 에서의 운영 요령:

- **일찍, 자주 교정합니다.** 방향이 틀리면 `Esc` 로 멈추고 바로잡습니다.
- 같은 문제를 **두 번 교정해도 안 되면** `/clear` 후 배운 점을 반영한 더 나은 프롬프트로 다시 시작합니다. 실패한 시도가 쌓인 긴 세션보다 깨끗한 세션이 거의 항상 낫습니다.
- 과감한 시도는 체크포인트 덕분에 부담이 적습니다. 안 되면 `/rewind` 로 되돌립니다.

> ⚠️ **함정**: 체크포인트는 **Claude 의 파일 편집 도구로 바꾼 내용만** 추적합니다. Bash 명령(`rm`, 마이그레이션 실행, 외부 API 호출)의 효과는 되돌리지 못합니다. git 커밋을 대체하지 않습니다.

## 5단계: 스킬로 작업 단위 추상화

반복 작업을 "사람의 기억"이 아니라 **스킬**로 승격합니다. 스킬은 설명(`description`)만 항상 컨텍스트에 있고, 호출될 때 본문이 로드됩니다. 그래서 CLAUDE.md 에 넣기엔 길거나 가끔만 필요한 절차에 적합합니다. 작성법·템플릿은 [Skill](/ai/03-prompt/06-skill) 문서에 있습니다.

대상:

- 코드 생성 패턴 (API 엔드포인트, 컴포넌트 골격)
- 테스트 작성 절차
- 리팩터링·마이그레이션 규칙
- 코드 리뷰 체크리스트
- 릴리스·배포 절차

```markdown
---
name: fix-issue
description: GitHub 이슈를 분석하고 수정한다
disable-model-invocation: true
allowed-tools: Bash(gh issue view *) Bash(pnpm test *)
---
GitHub 이슈 $ARGUMENTS 를 분석하고 수정한다.

1. `gh issue view` 로 이슈 내용을 확인한다
2. 관련 코드를 찾는다
3. 실패하는 테스트를 먼저 작성한 뒤 수정한다
4. 린트·타입 검사·테스트를 통과시킨다
5. 설명적인 메시지로 커밋한다
```

| 위치 | 범위 |
|---|---|
| `~/.claude/skills/<이름>/SKILL.md` | 내 모든 프로젝트 |
| `.claude/skills/<이름>/SKILL.md` | 이 저장소 (팀 공유) |
| `<plugin>/skills/<이름>/SKILL.md` | 플러그인 활성화 시 `/플러그인:이름` |

| frontmatter | 효과 |
|---|---|
| (기본) | 사용자 `/이름` 호출 + 모델이 설명을 보고 자동 호출 |
| `disable-model-invocation: true` | **사용자만** 호출. 커밋·배포처럼 부수효과가 있는 절차에 |
| `user-invocable: false` | `/` 메뉴에서 숨기고 모델만 사용. 배경 지식용 |
| `allowed-tools` | 스킬 실행 동안 해당 도구를 승인 없이 사용 |
| `context: fork` | 격리된 서브에이전트에서 실행 |

효과: 품질 편차 감소, 온보딩 비용 감소, 절차 변경 시 한 곳만 수정.

> ⚠️ **함정**: 스킬 본문은 호출되면 이후 턴에도 컨텍스트에 남아 **계속 토큰을 씁니다.** 한 줄 한 줄이 반복 비용이니 짧게 유지하고, 긴 참고 자료는 같은 디렉터리의 별도 파일로 빼서 필요할 때 읽게 하세요. 또 `description` 이 모호하면 자동 호출이 안 되거나 엉뚱할 때 호출됩니다.

## 6단계: 컨텍스트와 메모리 시스템화

매 세션은 빈 컨텍스트로 시작합니다. 매번 설명하지 않도록 **기억을 구조화**합니다. 컨텍스트 창은 가장 중요한 자원이고, 채워질수록 성능이 떨어진다는 점이 모든 설계의 전제입니다.

| 범위 | 위치 | 공유 |
|---|---|---|
| 조직 정책 | 관리형 정책 경로 (예: macOS `/Library/Application Support/ClaudeCode/CLAUDE.md`) | 조직 전체 |
| 사용자 | `~/.claude/CLAUDE.md`, `~/.claude/rules/` | 나만 (모든 프로젝트) |
| 프로젝트 | `./CLAUDE.md` 또는 `./.claude/CLAUDE.md`, `.claude/rules/` | 팀 (git) |
| 로컬 | `./CLAUDE.local.md` | 나만 (이 프로젝트, gitignore) |
| 자동 메모리 | `~/.claude/projects/<project>/memory/MEMORY.md` | 나만 (Claude 가 직접 기록) |

포함할 것 / 뺄 것:

| 포함 | 제외 |
|---|---|
| 모델이 추측할 수 없는 Bash 명령 | 코드를 읽으면 알 수 있는 것 |
| 기본값과 다른 코드 스타일 | 언어의 표준 관례 |
| 테스트 방법, 선호 테스트 러너 | 상세 API 문서 (링크로 대체) |
| 브랜치·PR 규칙 | 자주 바뀌는 정보 |
| 이 프로젝트 고유의 설계 결정과 함정 | "깨끗한 코드를 쓴다" 같은 자명한 말 |

운영 전략:

- **파일당 200줄 이내**를 목표로 합니다. 길수록 컨텍스트를 먹고 준수율이 떨어집니다.
- 긴 설명은 분리하고 `@docs/architecture.md` 처럼 **import** 합니다. (단, import 한 파일도 시작 시 로드되므로 토큰이 줄지는 않습니다.)
- 특정 파일에만 해당하는 규칙은 `paths` frontmatter 를 단 `.claude/rules/` 로 옮겨, 해당 파일을 다룰 때만 로드되게 합니다.
- 다른 에이전트용 `AGENTS.md` 가 이미 있으면 `CLAUDE.md` 에 `@AGENTS.md` 한 줄로 공유합니다.
- 모델이 **실제로 틀렸을 때** 한 줄씩 추가하고, 지켜지지 않거나 낡은 규칙은 지웁니다 (living doc).

```markdown
---
paths:
  - "src/api/**/*.ts"
---
# API 규칙
- 모든 엔드포인트는 zod 로 입력을 검증한다
- 에러 응답은 src/api/errors.ts 의 형식을 따른다
```

CLAUDE.md 를 처음부터 만들어 주는 프롬프트는 [CLAUDE.md 생성 프롬프트](/ai/03-prompt/07-claude)에, 좋은 규칙의 조건은 [Rule](/ai/03-prompt/05-rule)에 있습니다.

> ⚠️ **함정**: 규칙이 지켜지지 않으면 먼저 `/context` 로 파일이 **실제로 로드됐는지** 확인하세요. 서브디렉터리의 CLAUDE.md 와 `paths` 규칙은 해당 파일을 읽을 때 로드되므로, 세션 시작 직후엔 없을 수 있습니다. 서로 모순된 규칙이 있으면 모델은 경고 없이 하나를 임의로 고릅니다.

## 7단계: 자동화 + 멀티 에이전트 + CI 통합

### 7.1 Hooks — 반드시 일어나야 하는 것

훅은 라이프사이클 이벤트에서 **셸 명령(또는 HTTP, 프롬프트 등)을 결정적으로 실행**합니다. CLAUDE.md 가 "부탁"이라면 훅은 "보장"입니다.

| 이벤트 | 시점 | 대표 용도 |
|---|---|---|
| `SessionStart` | 세션 시작 | 환경 정보 주입 |
| `UserPromptSubmit` | 프롬프트 제출 시 | 프롬프트 검사, 컨텍스트 추가 |
| `PreToolUse` | 도구 실행 전 | **위험 명령·보호 경로 차단** |
| `PostToolUse` | 도구 실행 후 | 편집 후 포맷·린트 |
| `Stop` | 응답 종료 시 | 테스트 통과 전까지 종료 막기 |
| `SubagentStop` | 서브에이전트 종료 시 | 결과 검증 |
| `PreCompact` | 컨텍스트 압축 전 | 보존할 정보 기록 |
| `Notification` | 알림 발생 시 | 데스크톱 알림 |

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          { "type": "command", "command": "jq -r '.tool_input.file_path' | xargs npx prettier --write" }
        ]
      }
    ]
  }
}
```

- 훅 명령은 **stdin 으로 JSON**(`tool_name`, `tool_input` 등)을 받습니다.
- **종료 코드 2** 는 차단입니다. `PreToolUse` 에서 2로 끝내면 도구 호출이 막히고, `Stop` 에서 2로 끝내면 Claude 가 멈추지 않고 계속 작업합니다. `PostToolUse` 는 이미 실행된 뒤라 막을 수 없습니다.
- 설정 위치: `~/.claude/settings.json`(사용자), `.claude/settings.json`(프로젝트 공유), `.claude/settings.local.json`(개인). `/hooks` 로 확인합니다.
- "편집할 때마다 eslint 를 돌리는 훅을 만들어줘"처럼 Claude 에게 훅 작성을 맡겨도 됩니다.

> ⚠️ **함정**: 훅은 **사용자 권한으로 임의의 명령을 실행**합니다. 남이 만든 저장소의 `.claude/settings.json` 훅도 실행 대상이며, 특히 `claude -p` 비대화형 실행은 신뢰 확인 창 없이 프로젝트 훅과 `.mcp.json` 서버를 로드합니다. CI·스크립트에서는 `--bare` 로 자동 탐색을 끄고 필요한 설정만 플래그로 넘기세요.

### 7.2 MCP — 외부 시스템 연동

```bash
# 원격 HTTP 서버
claude mcp add --transport http notion https://mcp.notion.com/mcp

# 로컬 stdio 서버 (-- 뒤가 실행 명령)
claude mcp add --transport stdio db -- npx -y @bytebase/dbhub --dsn "postgresql://..."

# 팀 공유: 프로젝트 루트 .mcp.json 에 기록
claude mcp add --scope project --transport http shared https://example.com/mcp

claude mcp list      # 목록
/mcp                 # 세션 안에서 연결 상태·인증
```

| 스코프 | 저장 위치 | 공유 |
|---|---|---|
| `local` (기본) | `~/.claude.json` (프로젝트별) | 나만 |
| `project` | `.mcp.json` | 팀 (git) |
| `user` | `~/.claude.json` | 나만 (모든 프로젝트) |

MCP 도구는 `mcp__<서버>__<도구>` 이름으로 노출되어 권한 규칙·훅 matcher 에 그대로 쓸 수 있습니다. 개념은 [MCP](/ai/05-agent/05-mcp) 문서를 참고하세요.

원칙:

- `gh`, `aws` 같은 **CLI 가 있으면 CLI 가 가장 컨텍스트 효율적**입니다. MCP 는 CLI 가 없거나 인증·구조화된 조회가 필요할 때.
- 로컬과 CI 가 같은 명령·같은 설정으로 동작하게 합니다.

### 7.3 Subagents — 역할 단위 분해

서브에이전트는 **자기만의 컨텍스트, 시스템 프롬프트, 허용 도구**로 실행되고 결과 요약만 돌려줍니다. 파일을 많이 읽는 조사·리뷰를 맡기면 메인 대화가 깨끗하게 유지됩니다.

```markdown
---
name: security-reviewer
description: 코드의 보안 취약점을 검토한다. 인증·결제 코드 변경 후 사용한다.
tools: Read, Grep, Glob, Bash
model: opus
---
너는 시니어 보안 엔지니어다. 다음을 검토한다:
- 인젝션 (SQL, XSS, 명령어)
- 인증·인가 결함
- 코드에 포함된 비밀 값
각 발견 사항에 파일:라인과 수정안을 붙인다.
```

- 위치: `.claude/agents/`(프로젝트), `~/.claude/agents/`(사용자). `/agents` 로 관리합니다.
- 필수 필드는 `name`, `description` 이고, `tools`(허용 목록), `model`, `permissionMode`, `skills`, `mcpServers`, `hooks` 등을 지정할 수 있습니다.
- 내장 서브에이전트: **Explore**(읽기 전용 코드 탐색), **Plan**(plan mode 조사), **general-purpose**(탐색+수정).
- 호출: "subagent 를 써서 토큰 갱신 로직을 조사해줘"처럼 요청하거나, `@` 멘션으로 지정합니다.
- 서브에이전트는 메인 대화 이력을 보지 못합니다. 위임 지시에 필요한 맥락을 담아야 합니다.

패턴:

- **Writer/Reviewer** — 구현한 세션과 다른 깨끗한 컨텍스트에서 리뷰시키면 자기 코드에 대한 편향이 줄어듭니다.
- **조사 격리** — "investigate" 류 작업은 서브에이전트로 보내 메인 컨텍스트를 보존합니다.
- **병렬 작업** — 서로 독립적인 작업은 git worktree 로 파일 충돌 없이 나눕니다.

> ⚠️ **함정**: 구조 없이 에이전트 수만 늘리면 복잡도와 토큰 비용이 폭증합니다. 또 "허점을 찾아라"라고 시킨 리뷰어는 코드가 멀쩡해도 뭔가를 찾아냅니다. **정확성·요구사항에 영향을 주는 문제만 보고하라**고 범위를 정하지 않으면 과잉 설계로 이어집니다. 여러 세션을 자동 조율하는 Agent teams 는 실험 기능입니다.

### 7.4 Headless 와 CI

`claude -p` 는 같은 에이전트 루프를 비대화형으로 실행합니다. CI, pre-commit, 대량 마이그레이션에 씁니다. 같은 기능을 Python/TypeScript 코드로 제어하려면 Claude Agent SDK 를 씁니다.

```bash
# 도구를 명시적으로 허용해서 실행
claude -p "테스트를 돌리고 실패를 고쳐줘" --allowedTools "Bash,Read,Edit"

# JSON 출력 → jq 로 결과만 추출
claude -p "이 프로젝트를 요약해줘" --output-format json | jq -r '.result'

# 파이프 입력
cat build-error.txt | claude -p "빌드 에러의 근본 원인을 간단히 설명해줘"

# 재현 가능한 CI 실행: 로컬 훅·플러그인·CLAUDE.md 자동 탐색을 끔 (ANTHROPIC_API_KEY 필요)
claude --bare -p "README.md 요약" --allowedTools "Read"

# 파일 단위 fan-out
for file in $(cat files.txt); do
  claude -p "$file 을 새 API 로 마이그레이션하고 OK 또는 FAIL 만 출력해" --allowedTools "Edit,Bash(git commit *)"
done
```

| 플래그 | 용도 |
|---|---|
| `--output-format text \| json \| stream-json` | 출력 형식 (`json` 은 `result`, `session_id`, 비용 포함) |
| `--json-schema '<schema>'` | 스키마에 맞는 `structured_output` 반환 |
| `--allowedTools` | 승인 없이 쓸 도구 (`Bash(git diff *)` 처럼 접두사 매칭) |
| `--permission-mode` | `acceptEdits`, `dontAsk`, `plan` 등 |
| `--continue` / `--resume <id>` | 이전 대화 이어서 |
| `--append-system-prompt` | 기본 시스템 프롬프트에 지시 추가 |
| `--max-turns` | 반복 횟수 제한 |

**GitHub Actions** — Claude Code 에서 `/install-github-app` 을 실행하면 GitHub App 설치, 시크릿 등록, 워크플로 PR 생성까지 안내합니다. 이후 이슈·PR 에서 `@claude` 로 멘션하면 동작합니다.

```yaml
name: Claude Code
on:
  issue_comment:
    types: [created]
  pull_request_review_comment:
    types: [created]
jobs:
  claude:
    if: contains(github.event.comment.body, '@claude')
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
      issues: write
      id-token: write
      actions: read
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 1
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          # prompt 를 주면 멘션 없이 자동 실행 (스케줄, PR 오픈 등)
          # claude_args: "--max-turns 5"
```

> ⚠️ **함정**: 무인 실행은 권한을 좁히는 것이 핵심입니다. `--allowedTools` 없이 넓은 권한 모드로 돌리면 프롬프트 인젝션이 담긴 이슈 본문 하나로 원치 않는 명령이 실행될 수 있습니다. API 키는 반드시 시크릿으로 두고, `--max-turns` 와 워크플로 타임아웃으로 비용 폭주를 막으세요.

### 7.5 Plugins — 개인 설정을 팀 자산으로

플러그인은 **스킬, 서브에이전트, 훅, MCP 서버**를 한 단위로 묶어 마켓플레이스로 배포합니다. 한 프로젝트의 `.claude/` 에서 다듬은 구성을 여러 저장소·팀원에게 그대로 퍼뜨릴 때 씁니다.

```text
my-plugin/
  .claude-plugin/
    plugin.json        # 이름·설명·버전 (이 폴더에는 plugin.json 만)
  skills/
    review/SKILL.md    # /my-plugin:review
  agents/
  hooks/hooks.json
  .mcp.json
```

```bash
claude --plugin-dir ./my-plugin     # 로컬 테스트
/plugin                             # 세션 안에서 마켓플레이스 탐색·설치
/reload-plugins                     # 수정 사항 반영
```

### Harness 정리

| 구성 | 담당 |
|---|---|
| Memory (CLAUDE.md, rules) | 무엇을 알아야 하는가 |
| Skills | 어떻게 반복 작업을 하는가 |
| Subagents | 누구에게 맡기는가 |
| Hooks · 권한 | 무엇을 반드시 지키는가 |
| MCP · CLI | 무엇과 연결되는가 |
| Headless · Actions · Plugins | 사람 없이 어떻게 돌고, 어떻게 퍼뜨리는가 |

## 핵심 정리

이 가이드가 목표로 하는 변화:

1. 감 → **명세와 검증 가능한 완료 기준**
2. 매번 설명 → **CLAUDE.md·스킬로 코드화**
3. 부탁 → **훅·권한으로 강제**
4. 수동 → **headless·CI 로 자동화**
5. 개인 역량 → **플러그인으로 팀 시스템화**

> "잘 쓰는 개발자"가 아니라 "재현 가능한 개발 시스템을 가진 엔지니어"

## 참고 자료

- [Claude Code 공식 문서](https://code.claude.com/docs/en/overview)
- [Claude Code — Best practices](https://code.claude.com/docs/en/best-practices)
- [Claude Code — Memory (CLAUDE.md)](https://code.claude.com/docs/en/memory)
- [Claude Code — Skills](https://code.claude.com/docs/en/skills)
- [Claude Code — Subagents](https://code.claude.com/docs/en/sub-agents)
- [Claude Code — Hooks reference](https://code.claude.com/docs/en/hooks)
- [Claude Code — MCP](https://code.claude.com/docs/en/mcp)
- [Claude Code — Plugins](https://code.claude.com/docs/en/plugins)
- [Claude Code — Run programmatically (headless)](https://code.claude.com/docs/en/headless)
- [Claude Code — GitHub Actions](https://code.claude.com/docs/en/github-actions)
- [Anthropic — Claude Code best practices (engineering blog)](https://www.anthropic.com/engineering/claude-code-best-practices)
