# Skill

### 구조

```text
my-awesome-skill/     # .cursor/skills
├── SKILL.md          # 스킬 정의 문서
├── references/       # 참고 자료 및 템플릿
├── scripts/          # 실행 로직
├── assets/           # 이미지, 데이터 파일 등
```

## SILL.md 템플릿

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

## 리소스 추가

단일 Skill.md 파일에 추가할 정보가 너무 많은 경우(예: 특정 시나리오에만 적용되는 섹션), Skill 디렉토리 내에 파일을 추가하여 콘텐츠를 추가할 수 있습니다. 예를 들어 Skill 디렉토리에 보충 및 참조 정보가 포함된 REFERENCE.md 파일을 추가합니다.

## 실행 스크립트

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

## 가이드 라인

- https://agentskills.io/home
- https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices


### 추천 스킬 저장소

- https://github.com/anthropics/skills
- https://skillsmp.com/zh
- https://github.com/ComposioHQ/awesome-claude-skills

## 추천 스킬

- https://github.com/remotion-dev/skills
- https://github.com/PleasePrompto/notebooklm-skill
- https://github.com/VoltAgent/awesome-design-md/tree/main
- https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
- [frontend-design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md)
