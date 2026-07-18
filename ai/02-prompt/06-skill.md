# Skill

규칙 파일([Rule](./05-rule))이 **항상 로딩되는 프롬프트**라면, 스킬은 **필요할 때만 로딩되는 프롬프트**입니다. `description` 을 보고 모델이 스스로 "지금 이게 필요하다"고 판단해 그때 본문을 읽습니다.

덕분에 규칙 파일에 다 넣으면 터질 분량(특정 시나리오 전용 절차, 참고 문서, 실행 스크립트)을 컨텍스트를 낭비하지 않고 둘 수 있습니다.

## 구조

```text
my-awesome-skill/     # .claude/skills/ 아래에 둡니다
├── SKILL.md          # 스킬 정의 문서 (필수)
├── references/       # 참고 자료 및 템플릿
├── scripts/          # 실행 로직
└── assets/           # 이미지, 데이터 파일 등
```

`SKILL.md` 하나만 있어도 동작합니다. 나머지는 필요할 때 추가합니다.

## SKILL.md 템플릿

```md
---
name: my-skill-name
description: A clear description of what this skill does and when to use it
---

# My Skill Name

[Add your instructions here that Claude will follow when this skill is active]

## Examples

- Example usage 1
- Example usage 2

## Guidelines

- Guideline 1
- Guideline 2
```

> ⚠️ `description` 이 곧 라우팅입니다. 모델은 스킬을 열어보기 전에 **`description` 만 보고** 쓸지 말지 정합니다. 여기가 부실하면 아무리 본문을 잘 써도 호출되지 않습니다. "무엇을 하는지" 뿐 아니라 **"언제 쓰는지"** 를 반드시 적으세요.

## 리소스 추가

단일 `SKILL.md` 에 담을 정보가 너무 많은 경우(예: 특정 시나리오에만 적용되는 섹션), 스킬 디렉터리 안에 파일을 추가해 내용을 나눌 수 있습니다. 예를 들어 보충·참조 정보를 담은 `REFERENCE.md` 를 두고 `SKILL.md` 에서 가리킵니다.

이렇게 하면 `SKILL.md` 는 짧게 유지되고, 모델은 정말 필요할 때만 참조 파일을 읽습니다.

## 실행 스크립트

절차를 글로 설명하는 대신 **스크립트로 주면** 모델이 매번 다시 구현하지 않아도 됩니다. 결과도 결정적입니다.

```js
#!/usr/bin/env node

// 필요: node 18+ (fetch 내장)
// 실행: node weather.js Seoul

const city = process.argv[2];

if (!city) {
  console.error("사용법: node weather.js <city>");
  process.exit(1);
}

async function getWeather(city) {
  try {
    const res = await fetch(`https://wttr.in/${city}?format=j1`);
    const data = await res.json();

    const current = data.current_condition[0];

    console.log(`📍 도시: ${city}`);
    console.log(`🌡️ 온도: ${current.temp_C}°C`);
    console.log(`💧 습도: ${current.humidity}%`);
    console.log(`🌥️ 날씨: ${current.weatherDesc[0].value}`);
  } catch (err) {
    console.error("에러 발생:", err.message);
  }
}

getWeather(city);
```

## 가이드라인

- [agentskills.io](https://agentskills.io/home)
- [Anthropic — Agent Skills best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)

## 스킬 저장소

- [anthropics/skills](https://github.com/anthropics/skills) — 공식
- [skillsmp.com](https://skillsmp.com/zh)
- [ComposioHQ/awesome-claude-skills](https://github.com/ComposioHQ/awesome-claude-skills)

## 추천 스킬

| 스킬 | 용도 |
|---|---|
| [anthropics/skills — frontend-design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md) | 프론트엔드 디자인 |
| [cathrynlavery/diagram-design](https://github.com/cathrynlavery/diagram-design) | 다이어그램 디자인 |
| [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill) | 디자인 감각 |
| [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | UI/UX |
| [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md/tree/main) | 디자인 문서 |
| [remotion-dev/skills](https://github.com/remotion-dev/skills) | 영상 생성 |
| [PleasePrompto/notebooklm-skill](https://github.com/PleasePrompto/notebooklm-skill) | NotebookLM |
| [obra/superpowers](https://github.com/obra/superpowers) | 범용 모음 |
| [othmanadi/planning-with-files](https://github.com/othmanadi/planning-with-files) | 파일 기반 계획 |
| [Fission-AI/openspec](https://github.com/Fission-AI/openspec) | 스펙 주도 개발 |
| [garrytan/gstack](https://github.com/garrytan/gstack/tree/main) | 스택 모음 |
| [code-yeongyu/oh-my-openagent](https://github.com/code-yeongyu/oh-my-openagent) | 에이전트 설정 |
| [yizhiyanhua-ai/fireworks-tech-graph](https://github.com/yizhiyanhua-ai/fireworks-tech-graph) | 기술 그래프 |
| [rpamis/comet](https://github.com/rpamis/comet) | 범용 |

https://github.com/nutlope/hallmark