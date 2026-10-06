# Qwen-Image-Edit 프롬프트

Qwen-Image-Edit / Qwen-Image-Edit-2509 에서 바로 복사해 쓸 수 있는 편집 프롬프트 모음입니다.

핵심 원칙은 하나입니다. **무엇을 바꿀지 명확하게 지정하고, 무엇을 유지할지 명시합니다.**

## 기본 공식

```text
[WHAT TO CHANGE]

Keep [WHAT MUST STAY UNCHANGED] unchanged.
Make the modification natural, realistic, and consistent with the original image.
```

예시:

```text
Change the person's hairstyle to short wavy hair.

Keep the face, facial features, skin tone, expression, clothing,
body shape, pose, lighting, and background unchanged.

Make the hairstyle look natural and realistic.
```

### 작성 순서

아래 순서대로 쓰면 빠짐없이 작성할 수 있습니다.

1. **Action** — 동작 (`Change` / `Replace` / `Remove` / `Add`)
2. **Target** — 대상
3. **New Attribute** — 바꿀 속성
4. **Position / Appearance** — 위치·모양
5. **Preservation** — 유지할 요소
6. **Realism / Consistency** — 자연스러움·일관성
7. **Do Not Change** — 나머지는 건드리지 않음

```text
[Action] + [Target] + [New Attribute]

Keep [Important Elements] unchanged.

Match [Lighting / Perspective / Shadows / Texture]
with the original image.

Make the result natural and realistic.

Do not change any other elements.
```

## 얼굴 / 인물

### 표정 변경

```text
Change the person's facial expression to a subtle natural smile.

Keep the face identity, facial structure, hairstyle, skin tone,
clothing, pose, lighting, and background unchanged.
```

### 나이 변경

```text
Make the person look approximately 10 years younger.

Keep the person's identity, facial structure, hairstyle, clothing,
pose, lighting, and background unchanged.

The result should look natural and photorealistic.
```

### 피부 보정

```text
Improve the person's skin appearance with natural, subtle skin
smoothing and even skin tone.

Keep the facial features, identity, expression, hairstyle,
clothing, lighting, and background unchanged.

Avoid excessive retouching or plastic-looking skin.
```

### 메이크업

```text
Apply subtle natural makeup to the person:
soft blush, lightly defined eyes, and natural lip color.

Keep the person's identity, facial structure, hairstyle,
expression, clothing, pose, lighting, and background unchanged.
```

## 헤어

### 헤어스타일 변경

```text
Change the person's hairstyle to a short layered haircut.

Keep the face, facial features, skin tone, expression, clothing,
pose, lighting, and background unchanged.

Make the new hairstyle naturally fit the person's head and face.
```

### 헤어 컬러 변경

```text
Change the person's hair color to dark brown.

Keep the hairstyle, face, facial features, skin tone, expression,
clothing, pose, lighting, and background unchanged.
```

### 긴 머리 → 단발

```text
Replace the person's long hair with a natural shoulder-length bob haircut.

Keep the face, facial features, skin tone, expression, clothing,
pose, lighting, and background unchanged.
```

## 의상

### 옷 교체

```text
Replace the person's clothing with a black leather jacket
over a plain white T-shirt.

Keep the person's face, hairstyle, body shape, pose, expression,
lighting, and background unchanged.

Make the clothing fit naturally.
```

### 정장으로 변경

```text
Replace the person's outfit with a well-fitted dark navy business suit,
white dress shirt, and simple black tie.

Keep the person's face, hairstyle, body proportions, pose,
expression, lighting, and background unchanged.
```

### 옷 색상만 변경

```text
Change the color of the person's jacket to dark green.

Keep the jacket design, texture, shape, person's face, hairstyle,
pose, lighting, and background unchanged.
```

## 포즈

### 손 위치 변경

```text
Change the person's pose so that both hands are resting naturally
in front of the body.

Keep the person's identity, face, hairstyle, clothing, body proportions,
camera angle, lighting, and background unchanged.
```

### 앉은 자세 → 선 자세

```text
Change the person's posture from sitting to standing naturally.

Keep the person's identity, facial features, hairstyle, clothing,
body proportions, lighting, and background unchanged.
```

### 시선 변경

```text
Change the person's gaze so they are looking directly at the camera.

Keep the face, identity, expression, hairstyle, clothing, pose,
lighting, and background unchanged.
```

## 배경

- 배경을 바꿀 때는 인물에 비치는 조명을 새 환경에 맞추라고 함께 적습니다.

### 도시 야경

```text
Replace the background with a modern city street at night.

Keep the person, face, hairstyle, clothing, pose, proportions,
and camera framing unchanged.

Match the lighting on the person naturally to the new environment.
```

### 스튜디오

```text
Replace the background with a clean professional photography studio
with a neutral seamless backdrop.

Keep the person, clothing, pose, facial features,
and camera framing unchanged.

Use soft realistic studio lighting.
```

### 자연

```text
Replace the background with a beautiful autumn forest.

Keep the main subject completely unchanged.

Preserve the original subject's face, clothing, pose, proportions,
and camera angle.

Match the lighting and shadows naturally to the new environment.
```

## 객체 제거

- 지운 자리를 주변 배경으로 자연스럽게 채우라고(reconstruct) 함께 적습니다.

### 사람 제거

```text
Remove the person in the background.

Keep the main subject completely unchanged.

Naturally reconstruct the area behind the removed person,
matching the surrounding background, lighting, texture, and perspective.
```

### 물체 제거

```text
Remove the chair on the left side of the image.

Keep everything else unchanged.

Naturally reconstruct the background where the chair was removed,
matching the surrounding texture, lighting, and perspective.
```

### 텍스트 제거

```text
Remove the visible text from the bottom-right corner.

Keep the rest of the image unchanged.

Naturally reconstruct the area behind the removed text.
```

## 객체 추가

- 위치를 지정하고, 원본과 조명·그림자·원근을 맞추라고 적습니다.

### 고양이 추가

```text
Add a light-gray cat in the bottom-right corner,
sitting and facing the camera.

Match the cat's lighting, shadows, perspective,
and overall visual style to the original image.

Keep all existing subjects and objects unchanged.
```

### 자동차 추가

```text
Add a black vintage sports car in the background on the right side.

Match the car's perspective, scale, lighting, reflections,
and shadows to the original scene.

Keep the existing subjects and composition unchanged.
```

## 객체 교체

```text
Replace [OLD OBJECT] with [NEW OBJECT].

Keep everything else unchanged.
```

예시:

```text
Replace the coffee cup on the table with a transparent glass of water.

Keep the table, person's hands, background, lighting, composition,
and all other objects unchanged.
```

## 스타일 변환

### 시네마틱

```text
Transform the image into a cinematic film still.

Keep the subject, identity, facial features, clothing, pose,
composition, and background unchanged.

Apply cinematic lighting, natural contrast, subtle film grain,
and realistic color grading.
```

### 수채화

```text
Transform the image into a delicate watercolor painting.

Keep the original composition, subject identity, pose,
and major visual elements.

Use soft watercolor textures, translucent washes,
subtle paper grain, and natural color transitions.
```

### 일본 애니메이션

```text
Transform the image into a high-quality Japanese animation style.

Keep the character's identity, pose, composition, clothing,
and major visual features recognizable.

Use clean linework, simplified shading,
and carefully rendered details.
```

## 사진 보정

### 선명도 향상

```text
Enhance the image clarity and fine details.

Keep the original composition, colors, subject identity,
facial features, lighting, and background unchanged.

Do not alter or regenerate the content.
```

### 오래된 사진 복원

```text
Restore the image and improve its overall clarity and detail.

Preserve the original person's identity, facial features,
composition, colors, and background.

Reduce noise and compression artifacts while maintaining
a natural photographic appearance.

Do not introduce new objects or alter the original content.
```

### 간단한 복원

```text
Restore and colorize the photo.
```

## 아웃페인팅 / 인페인팅

### 아웃페인팅

- 이미지 바깥 영역을 확장합니다.

```text
Extend the image beyond its boundaries using outpainting.

Preserve the original image content, composition, lighting,
perspective, and visual style.

Naturally continue the surrounding environment
into the newly generated area.
```

### 인페인팅

- 원본 캡션을 함께 주면 비어 있는 영역을 맥락에 맞게 채웁니다.

```text
Perform inpainting on this image.

The original caption is:
A woman standing in a modern city street wearing a black coat.
```

## 텍스트 편집

> 💡 이미지에 실제로 렌더링할 텍스트는 `" "` 로 감싸서 명확하게 표시합니다.

### 텍스트 변경

```text
Replace "SALE" with "NEW ARRIVAL".
```

### 포스터 텍스트

```text
Replace "SUMMER SALE" with "WINTER SALE".

Keep the typography, position, size, layout,
and overall poster design unchanged.
```

### 텍스트 추가

```text
Add the text "OPEN TODAY" at the top center.

Use a clean bold sans-serif font
and keep the text clearly readable.
```

## 멀티 이미지 편집

- `<image1>`, `<image2>` 로 이미지를 지칭하고, 각 이미지에서 무엇을 가져올지 나눠서 적습니다.

### 사람 + 배경

```text
Place the person from <image1> into the scene of <image2>.

Preserve the person's identity, face, hairstyle, clothing,
and body proportions from <image1>.

Use the environment, composition, and lighting from <image2>.

Integrate the person naturally into the new scene.
```

### 의상 레퍼런스

```text
Replace the clothing of the person in <image1>
with the clothing shown in <image2>.

Keep the person's face, identity, hairstyle, body proportions,
pose, and background from <image1> unchanged.

Transfer only the clothing design and appearance from <image2>.
```

### 얼굴 레퍼런스

```text
Use the facial appearance from <image2>
for the person in <image1>.

Keep the hairstyle, clothing, body proportions, pose,
composition, and background from <image1> unchanged.

Preserve a natural and realistic result.
```

## 범용 템플릿

### 가장 추천하는 템플릿

```text
Change/Replace [TARGET] to/with [NEW RESULT].

Keep [IMPORTANT UNCHANGED ELEMENTS] unchanged.

Make the modification natural and visually consistent
with the original image.

Do not change any other elements.
```

예시:

```text
Change the woman's hairstyle to long straight black hair.

Keep her face, facial features, skin tone, expression, clothing,
body proportions, pose, lighting, camera angle, and background unchanged.

Make the new hairstyle naturally fit her face and head.

Do not change any other elements.
```

### 강한 보존 템플릿

- 편집 범위 밖의 요소를 최대한 보존하고 싶을 때 씁니다.

```text
Modify only the specified element.

Keep everything else exactly as in the original image,
including the subject's identity, facial features, hairstyle,
clothing, pose, body proportions, composition, camera angle,
lighting, colors, shadows, background, and all unrelated objects.

Do not make any other changes.
```

### 보존 문구 모음

- 필요한 문장만 골라서 붙입니다.

```text
Keep the person's identity unchanged.
Keep the facial features unchanged.
Keep the original hairstyle unchanged.
Keep the clothing design unchanged.
Keep the body proportions unchanged.
Keep the original pose unchanged.
Keep the original composition unchanged.
Keep the camera angle unchanged.
Keep the background unchanged.
Keep the original lighting unchanged.
Match the lighting and shadows naturally.
Preserve the original perspective.
Make the modification photorealistic.
Make the result natural and visually consistent.
Do not change any other elements.
```

## 빠른 복사 템플릿

### 헤어

```text
Change the person's hairstyle to [STYLE].

Keep the face, facial features, skin tone, expression,
clothing, pose, lighting, and background unchanged.

Make the hairstyle natural and realistic.
```

### 의상

```text
Replace the person's clothing with [CLOTHING].

Keep the face, hairstyle, body proportions, pose,
lighting, and background unchanged.

Make the clothing fit naturally.
```

### 배경

```text
Replace the background with [BACKGROUND].

Keep the subject completely unchanged.

Match the lighting, perspective, shadows,
and depth of field naturally.
```

### 제거

```text
Remove [OBJECT].

Keep everything else unchanged.

Naturally reconstruct the area behind the removed object.
```

### 추가

```text
Add [OBJECT] at [POSITION].

Match its scale, perspective, lighting, shadows,
and visual style to the original image.

Keep all existing elements unchanged.
```

### 교체

```text
Replace [OLD] with [NEW].

Keep everything else unchanged.

Match the new element's scale, perspective,
lighting, shadows, and texture to the original image.
```

### 스타일

```text
Transform the image into [STYLE].

Keep the subject identity, composition, pose,
and major visual elements unchanged.

Apply [STYLE CHARACTERISTICS] while preserving
the original content.
```

### 복원

```text
Restore and enhance the image.

Preserve the original identity, composition,
facial features, colors, and background.

Remove noise and compression artifacts.
Do not introduce new objects or alter the original content.
```

## 피해야 할 프롬프트

### 너무 모호함

```text
Make her prettier.
```

```text
Change the photo.
Make it better.
```

### 관련 없는 편집을 한 번에 너무 많이

```text
Change her hair, face, clothes, background, lighting,
make her younger, make the photo cinematic,
remove the people, add a car, make it 8K.
```

### 개선한 예

```text
Change the woman's hairstyle to a short bob haircut.

Keep her face, facial features, expression, clothing, pose,
lighting, and background unchanged.
```

## 핵심 규칙

| 규칙 | 설명 |
|---|---|
| 바꿀 것을 지정 | 무엇을 어떻게 바꿀지 구체적으로 적습니다 |
| 지킬 것을 명시 | 유지해야 할 요소를 `Keep ... unchanged` 로 적습니다 |
| 한 번에 하나 | 큰 편집은 한 번에 하나씩 합니다 |
| `Replace` | 객체를 다른 객체로 교체할 때 |
| `Change` | 속성(색, 모양, 표정 등)을 바꿀 때 |
| `Remove` | 지울 때 |
| `Add` | 새 객체를 넣을 때 — 위치도 함께 지정 |
| 합성 시 조명·원근 | 합성할 때는 조명·원근을 맞추라고 적습니다 |
| 텍스트는 따옴표 | 이미지에 들어갈 글자는 `" "` 로 감쌉니다 |
| 모호한 형용사 금지 | `better`, `beautiful`, `cool` 처럼 정의 없는 표현은 피합니다 |
| 불필요한 묘사 금지 | 건드리지 않을 요소를 장황하게 묘사하지 않습니다 |

## 한 줄 요약

```text
CHANGE → TARGET → NEW ATTRIBUTE → PRESERVE → MATCH LIGHTING/PERSPECTIVE → DO NOT CHANGE ANYTHING ELSE
```

```text
Change [TARGET] to [NEW RESULT].

Keep [IMPORTANT ELEMENTS] unchanged.

Match the new result naturally with the original
lighting, perspective, shadows, texture, and composition.

Make the result realistic and visually consistent.

Do not change any other elements.
```
