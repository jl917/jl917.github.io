# 체화 지능 (Embodied Intelligence)

체화 지능은 **몸(센서와 액추에이터)을 가진 에이전트가 물리 환경을 지각하고, 판단하고, 직접 행동하며 배우는 지능**입니다. 지능을 연산만의 산물이 아니라 신체와 환경의 상호작용에서 나오는 것으로 봅니다. LLM이 텍스트 토큰을 출력한다면, 체화 지능 모델은 **로봇의 행동(action)** 을 출력합니다.

## 개념

```mermaid
flowchart LR
  P[지각<br/>카메라·관절 센서·촉각] --> R[판단<br/>무엇을 어떤 순서로]
  R --> A[행동<br/>관절·그리퍼 제어]
  A --> E[물리 환경]
  E -->|변화된 상태| P
```

지각 → 판단 → 행동 루프가 **실시간으로, 실패하면 물리적 결과를 남기며** 돌아간다는 점이 소프트웨어 에이전트와 가장 큰 차이입니다.

| | LLM 에이전트 | 체화 에이전트 (로봇) |
|---|---|---|
| 입력 | 텍스트, 이미지, 도구 결과 | 카메라 영상, 관절 각도, 힘·촉각 센서 |
| 출력 | 텍스트, 도구 호출 | 연속적인 모터 명령 (예: 초당 수십 회) |
| 실패 비용 | 재시도하면 됨 | 물건 파손, 안전 사고, 되돌릴 수 없음 |
| 학습 데이터 | 인터넷 규모 텍스트 | 로봇 시연 데이터 — 수집이 비싸고 적음 |
| 지연 허용 | 수 초도 가능 | 제어 루프는 수십 ms 단위 |

"체스는 컴퓨터에게 쉽고, 컵 집기는 어렵다"는 **모라벡의 역설**이 이 분야의 핵심 난제를 요약합니다. 사람에게 사소한 지각·운동 능력이 기계에게는 가장 어렵습니다. LLM 에이전트의 구조는 [에이전트](/ai/05-agent/01-agent)를 참고하세요.

## VLA 모델

**VLA(Vision-Language-Action)** 모델은 카메라 이미지와 자연어 지시를 입력받아 **로봇 행동을 직접 출력**하는 멀티모달 파운데이션 모델입니다. 인터넷 규모로 학습된 VLM(Vision-Language Model)의 상식과 시각 이해를 로봇 제어로 옮기는 것이 핵심 아이디어입니다.

### 발전 흐름

| 시기 | 모델 | 의미 |
|---|---|---|
| 2022-12 | **RT-1** (Google) | 대규모 실제 로봇 시연 데이터로 학습한 Transformer 제어 정책 |
| 2023-03 | **PaLM-E** (Google) | PaLM(540B)과 ViT(22B)를 결합한 562B 규모의 체화 멀티모달 언어 모델. 이미지·로봇 상태를 언어 모델 입력에 섞어 **작업 계획**과 시각 질의응답을 수행 |
| 2023-07 | **RT-2** (Google DeepMind) | **VLA라는 패러다임을 정립.** PaLI-X(55B)·PaLM-E(12B)를 기반으로 로봇 행동을 `"1 128 91 241 5 101 127 217"` 같은 **텍스트 토큰**으로 표현해 웹 데이터와 함께 학습. 학습 데이터에 없던 지시도 수행 |
| 2023-10 | **Open X-Embodiment / RT-X** | 21개 기관이 22종 로봇의 100만+ 궤적을 모은 공개 데이터셋. 여기서 학습한 RT-1-X·RT-2-X가 여러 로봇에 걸친 전이 효과를 보임 |
| 2024-05 | **Octo** | Open X-Embodiment 80만 에피소드로 학습한 오픈소스 범용 정책(27M/93M). Transformer + 확산(diffusion) 행동 디코더 |
| 2024-06 | **OpenVLA** | Llama 2 7B + SigLIP·DINOv2 비전 인코더 기반 오픈 7B VLA. 97만 에피소드로 학습해 55B RT-2-X를 다수 일반화 과제에서 앞섬. LoRA로 효율적 파인튜닝 |
| 2024-10 | **π0** (Physical Intelligence) | 3B VLM에 **flow matching** 행동 전문가를 붙여 연속 행동을 최대 50Hz로 생성. 8종 로봇 데이터로 빨래 개기 같은 정교한 작업 수행. 2025-02에 `openpi`로 가중치 공개 |
| 2025-03 | **GR00T N1** (NVIDIA) | 휴머노이드용 오픈 파운데이션 모델. VLM(느린 추론)과 확산 Transformer 행동 헤드(빠른 제어)를 결합 |
| 2025-03 | **Gemini Robotics / Gemini Robotics-ER** (Google DeepMind) | Gemini 2.0 기반 VLA와, 공간 이해·계획을 담당하는 체화 추론(Embodied Reasoning) 모델 |
| 2025-04 | **π0.5** | 처음 보는 가정 환경으로의 개방형 일반화 |
| 2025-06 | **SmolVLA** (Hugging Face) | 소비자용 하드웨어에서 돌아가는 소형 오픈 VLA. LeRobot 생태계 |
| 2025-11 | **π\*0.6** | 실제 배포 경험과 사람의 교정으로 **강화학습**하는 RECAP 방식 도입 |
| 2026-04 | **π0.7** | 지시로 행동을 세밀하게 조정(steerable)할 수 있는 범용 로봇 파운데이션 모델 |
| 2026-07 | **Gemini Robotics 2** (Google DeepMind) | 휴머노이드 **전신 제어**로 확장. 계획을 맡는 Gemini Robotics-ER 2는 Gemini API로 공개, VLA와 온디바이스 모델은 파트너 대상 |

### 아키텍처 패턴

| 패턴 | 방식 | 대표 모델 | 트레이드오프 |
|---|---|---|---|
| **행동 = 텍스트 토큰** | 연속적인 행동 값을 구간으로 나눠 토큰화하고 LLM처럼 자기회귀로 생성 | RT-2, OpenVLA | VLM 구조를 그대로 재사용하지만, 정밀도와 제어 주파수에 한계 |
| **연속 행동 헤드** | VLM 뒤에 확산·flow matching 헤드를 붙여 연속 행동을 한 번에 여러 스텝(action chunk) 생성 | Octo, π0, GR00T | 부드럽고 빠른 제어. 구조와 학습이 복잡 |
| **계층형 (System 2 + System 1)** | 느리지만 똑똑한 추론·계획 모델이 하위 목표를 정하고, 빠른 VLA·정책이 실제 모터를 제어 | GR00T N1, Gemini Robotics-ER + VLA | 긴 작업 계획과 실시간 제어를 분리. 두 모델 간 인터페이스 설계가 관건 |

Gemini Robotics-ER 2는 하위 VLA를 **도구(tool)로 등록해 호출**하는 구조를 씁니다. LLM 에이전트의 [Function Calling](/ai/05-agent/03-function-calling) 패턴이 로봇 제어로 그대로 넘어온 셈입니다.

> ⚠️ **함정**: 데모 영상의 성공 장면을 일반 성능으로 받아들이면 안 됩니다. 로봇 성과는 **로봇 기종, 환경, 물체, 조명, 시도 횟수**에 크게 좌우되고, 공개 논문도 성공률이 과제별로 큰 폭으로 갈립니다. 비교할 때는 같은 벤치마크·같은 하드웨어에서의 성공률을 확인하세요.

## 데이터셋

로봇 분야의 가장 큰 병목은 **데이터**입니다. 텍스트는 인터넷에서 긁어올 수 있지만 로봇 시연 데이터는 사람이 원격 조작(teleoperation)으로 한 번씩 만들어야 합니다.

| 데이터셋 | 규모 | 특징 |
|---|---|---|
| **Open X-Embodiment** | 22종 로봇, 100만+ 궤적, 527개 스킬 | 34개 연구실 데이터를 표준 포맷(RLDS)으로 통합. 범용 VLA 사전학습의 표준 출발점 |
| **DROID** | 7.6만 궤적, 350시간, 564개 장면, 86개 작업 | Franka 로봇 팔로 13개 기관이 실제 환경("in-the-wild")에서 수집. 장면 다양성이 강점 |
| **LeRobot 데이터셋** | 커뮤니티 업로드 | Hugging Face Hub의 표준 포맷. GR00T N1.6 등도 LeRobot 호환 포맷으로 파인튜닝 데이터를 받음 |

데이터 부족을 메우는 방향은 크게 세 가지입니다.

- **시뮬레이션·합성 데이터** — 무한히 생성할 수 있지만 현실과의 차이가 있습니다.
- **사람 영상** — 사람이 작업하는 영상에서 행동을 배우는 연구가 활발합니다.
- **배포 중 학습** — π\*0.6처럼 실제 운영 경험과 사람의 교정을 강화학습에 활용합니다.

## 시뮬레이션

| 도구 | 특징 |
|---|---|
| **NVIDIA Isaac Sim / Isaac Lab** | GPU 가속 대규모 병렬 시뮬레이션. 휴머노이드·사족보행 강화학습에 강함 |
| **MuJoCo** (+ MJX, MuJoCo Playground) | 접촉이 많은 조작(manipulation) 연구의 표준. MJX는 JAX 기반 GPU 병렬화 |
| **ManiSkill** (SAPIEN 기반) | 조작 과제 벤치마크와 GPU 병렬 시뮬레이션 |
| **Genesis** | 물리·렌더링·로봇 API를 하나의 Python 스택으로 통합하려는 오픈소스 시뮬레이터 |
| **벤치마크** | LIBERO, SimplerEnv, RoboCasa 등 — VLA 성능을 같은 조건에서 비교 |

최근에는 물리 시뮬레이터 대신 **월드 모델(world model)** 로 환경을 생성하는 흐름도 커지고 있습니다. NVIDIA **Cosmos**는 로봇·자율주행용 합성 데이터와 물리 추론을 위한 오픈 월드 모델 계열이고, Google DeepMind **Genie 3**는 텍스트나 이미지로부터 상호작용 가능한 3D 환경을 실시간 생성합니다.

> ⚠️ **함정**: **Sim-to-Real 격차**. 시뮬레이션에서 99% 성공한 정책이 실제 로봇에서는 마찰·조명·센서 노이즈·지연 차이로 쉽게 실패합니다. 물리 파라미터와 시각 요소를 무작위로 바꿔 학습하는 **도메인 랜덤화**, 소량의 실제 데이터로 파인튜닝하는 방식을 함께 써야 하고, 최종 평가는 반드시 실제 하드웨어에서 해야 합니다.

## 과제

| 과제 | 내용 |
|---|---|
| **데이터 규모와 비용** | 인터넷 텍스트에 비해 데이터 규모가 훨씬 작고, 원격 조작 수집 비용이 높음 |
| **기종 간 일반화** | 팔 개수, 관절 구성, 그리퍼·손 형태가 달라 한 로봇에서 배운 것을 다른 로봇에 옮기기 어려움 |
| **정교한 조작** | 다섯 손가락 손의 섬세한 조작은 여전히 약점. Gemini Robotics 2도 다지(multi-finger) 조작 성공률이 과제별로 크게 갈린다고 밝힘 |
| **실시간성과 온디바이스** | 큰 모델일수록 지연이 커져 제어 주파수를 맞추기 어려움. 경량 모델(SmolVLA, Gemini Robotics On-Device)과 계층형 구조로 대응 |
| **긴 작업과 기억** | 수 분 이상 이어지는 작업에서 진행 상황 추적, 실패 복구, 장기 기억 필요 |
| **안전** | 물리적 위해 방지, 예상 밖 지시 거부. 체화 AI 안전 벤치마크(Google DeepMind ASIMOV 등) 연구 진행 중 |
| **평가 재현성** | 실제 환경 평가는 비싸고 조건 통제가 어려워 논문 간 비교가 힘듦 |

## 참고 자료

- [Brohan et al. — RT-1: Robotics Transformer for Real-World Control at Scale](https://arxiv.org/abs/2212.06817)
- [Driess et al. — PaLM-E: An Embodied Multimodal Language Model](https://arxiv.org/abs/2303.03378)
- [RT-2: Vision-Language-Action Models Transfer Web Knowledge to Robotic Control](https://robotics-transformer2.github.io/)
- [Open X-Embodiment 프로젝트 페이지](https://robotics-transformer-x.github.io/), [GitHub](https://github.com/google-deepmind/open_x_embodiment)
- [Octo: An Open-Source Generalist Robot Policy](https://octo-models.github.io/)
- [OpenVLA](https://openvla.github.io/)
- [Physical Intelligence — π0](https://www.pi.website/blog/pi0), [블로그 (π0.5, π\*0.6, π0.7)](https://www.pi.website/blog)
- [NVIDIA — GR00T N1: An Open Foundation Model for Generalist Humanoid Robots](https://arxiv.org/abs/2503.14734)
- [Google DeepMind — Gemini Robotics](https://deepmind.google/models/gemini-robotics/), [Gemini Robotics 2](https://deepmind.google/blog/gemini-robotics-2-brings-whole-body-intelligence-to-robots/)
- [SmolVLA 논문](https://arxiv.org/abs/2506.01844), [LeRobot](https://github.com/huggingface/lerobot)
- [DROID 데이터셋](https://droid-dataset.github.io/)
- [Awesome-VLA 논문 목록](https://github.com/KwanWaiPang/Awesome-VLA)
- [Wikipedia — Vision-language-action model](https://en.wikipedia.org/wiki/Vision-language-action_model)
