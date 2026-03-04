# Figma

### 샘플

```markdown
레이아웃 가로, 세로는 100% 로 맞춰주시고, figma섹션을 기준으로 UI를 다시 그려주세요. 

Convert this Figma section into a production-ready React component.

Requirements:
- Use React + TypeScript
- Use React functional component
- Use styled-components.
- Do not use inline styles.
- Use exact px values.
- DO NOT approximate.
- Follow Figma exactly.
- Match all spacing, font sizes, colors exactly
- Match Figma spacing exactly (use px, not approximations)
- Do not invent styles
- Do not change font sizes
- Do not simplify layout
- Do not convert px to rem.
// 컴포넌트를 일정한 크기로 슬라이스
- Split the UI into appropriately sized, reusable components.
- Do NOT implement the entire section as a single large component.
- Extract logical UI parts into separate components when:
  - A block has a distinct visual boundary
  - A block has repeated structure
  - A block has its own layout responsibility
  - A block could be reused elsewhere
- Each component must have a single clear responsibility.
- Avoid over-fragmentation (do not split purely for trivial wrappers).
- Keep styling colocated with each component using styled-components.
- Parent component should compose child components clearly.
- Do not duplicate markup — reuse components when structure repeats.

// 시맨틱 마크업 적용
- Use semantic HTML tags whenever applicable.
- Do NOT wrap everything in div.
- Use appropriate elements such as:
  - section
  - article
  - header
  - footer
  - nav
  - main
  - ul / ol / li
  - button
  - h1–h6
  - p
- Headings must follow correct hierarchy (do not skip levels).
- Use button for clickable elements (not div with onClick).
- Use anchor (a) for navigation links.
- Image type use SVG.
- Use semantic grouping where appropriate.
- Only use div when no semantic element is appropriate.

- If component boundaries are unclear from Figma, leave a comment explaining the ambiguity instead of guessing.

// 2배 크기로 나오는 경우
Scaling Rule (IMPORTANT):
- The provided Figma measurements are based on a 2x scaled frame.
- Divide ALL pixel values (width, height, padding, margin, font-size, border-radius, line-height, etc.) by 2 before implementing.
- The final rendered UI must visually match the real product at 100% scale.
- Do NOT keep the 2x values.

// 바이브용 클래스네임 추가
Debugging & Class Naming Requirements:
- Add an explicit className to every major component and logical UI section.
- Each component root element must have a unique and descriptive class name.
- Use predictable and readable naming (e.g., section-hero, hero-title, card-item, footer-nav).
- Do NOT rely only on styled-components generated class names.
- Nested logical blocks inside a component must also have their own class names.
- Class names must reflect structure, not visual style.
- Do NOT use random or hashed class names.
- Avoid generic names like wrapper, box, container unless contextually specific (e.g., hero-container).
- Ensure class names remain stable for debugging and QA inspection.

If something is unclear, leave a comment instead of guessing.
If the generated layout differs from the Figma spec, explain why.

Figma Specs: 
```