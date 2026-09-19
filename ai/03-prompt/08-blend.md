# Blender 구현을 위한 상세 3D 프롬프트

**[핵심 문구]**
"A highly-detailed, production-ready 3D character model of the cute baby dinosaur from image_0.png, designed with a full facial rigging and shape key system for 16 distinct expressions."

**1. 캐릭터 상세 정보 (Character Details)**

* **Subject:** A single, stylized baby dinosaur character, waist-up view.
* **Appearance:** Identical to image_0.png, featuring three small head-horns, large expressive oval eyes with detailed irises, and a soft, rounded snout.
* **Color Palette:** A vibrant, consistent cyan-blue for the skin and horns, with a lighter teal chest/belly patch.
* **Texture/Material:** A critical element. The model must have a detailed **plush fleece or soft felt fabric texture**, capturing the distinct fiber strands and soft pile seen in image_0.png, with a low-gloss, matte finish.

**2. 텍스처 및 머티리얼 (Texture & Material)**

* **Base Color:** Uniform cyan-blue fabric.
* **Detail:** Micro-level textile weave, visible fiber texture, and realistic depth.
* **Roughness:** High roughness for a soft, non-reflective feel.
* **PBR Maps:** Requires high-resolution Diffuse (Color), Normal, Roughness, and Ambient Occlusion maps to recreate the plush look in Blender.

**3. 표정 및 애니메이션 (Expressions & Shape Keys - The 16 States)**
The model must support 16 individual shape keys (blend shapes) that allow it to morph precisely between the expressions shown in the 4x4 grid. Each shape key should be clearly named for ease of use in Blender.

* **Grid Row 1:**
1. `Express_Neutral`: Default face.
2. `Express_Happy_Laugh`: Wide smile, open mouth with visible tongue and inner mouth detail.
3. `Express_Surprised`: O-shaped mouth, widely opened eyes, lifted brows.
4. `Express_Angry`: Furrowed brows, narrowed eyes, downturned mouth.


* **Grid Row 2:**
5.  `Express_Wink_Right`: Left eye open, right eye closed in a clear wink.
6.  `Express_Sad`: Worried brows, heavy eyelids, small downturned mouth.
7.  `Express_Grumpy_Pout`: Tight-lipped pout, slightly closed eyes.
8.  `Express_Cheerful`: Wide-eyed, open-mouthed grin, similar to `Happy_Laugh` but slightly different.
* **Grid Row 3:**
9.  `Express_Shy_Blush`: Closed eyes (slight squint), closed smile, and detailed red blush texture applied to cheeks.
10. `Express_Puzzled_Question`: Head tilted, one brow raised, other eye squinting, and a separate floating 3D question mark symbol.
11. `Express_Scared`: Wide eyes, tight, worried mouth, slightly raised brows.
12. `Express_Playful_TongueOut`: Winking eye (right), tongue out, cheeky grin.
* **Grid Row 4:**
13. `Express_Sleeping_ZZZ`: Eyes fully closed, serene expression, and a floating 'Zzz' symbol cluster.
14. `Express_Frustrated_Teeth`: Clenched teeth (detailed mouth interior), narrow eyes.
15. `Express_Crying`: Wide open mouth, tears flowing from eyes (with dynamic tear texture/geometry).
16. `Express_Neutral_Alt`: Same as default, for comparison or slight variation.

**4. 리깅 및 애니메이션 설정 (Rigging & Animation Setup)**

* **Rigging:** A complete face rig with bones to control eyebrows, eyelids, eyes, jaw, and mouth corners. This rig must work in tandem with the shape keys.
* **Shape Key Drivers:** Setup Blender drivers to control the 16 expression shape keys via the face rig controls for easy animation.
* **Body Rig:** A simple spine and arm rig for basic posing, matching the pose changes in the grid (e.g., slight head tilt in 'Puzzled').

**5. 렌더링 스타일 (Rendering Style)**

* **Style:** Clean, professional 3D product render style.
* **Lighting:** Soft, multi-point studio lighting (softbox setup) to highlight the texture and form without harsh shadows.
* **Background:** A clean, flat orange background (#FF8000).
* **Perspective:** Close-up, focused on the face.

---

**[구현 팁]**

1. **AI 모델 활용:** 위의 프롬프트를 사용하여 먼저 고해상도의 기본 모델과 텍스처 맵을 생성합니다. (Meshy, TripoSR 등).
2. **Blender 작업:** 생성된 모델을 Blender로 가져온 후, 텍스처를 적용합니다. 그 다음, `Basis` 모양을 기준으로 위 목록의 16가지 표정에 해당하는 셰이프 키를 하나씩 정성스럽게 모델링합니다.
3. **애니메이션:** 각 셰이프 키의 값을 조절하여 표정 간의 매끄러운 전환을 확인하고, 이를 애니메이션 시퀀스로 만듭니다.