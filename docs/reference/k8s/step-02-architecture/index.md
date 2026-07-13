# Step 02 — 아키텍처와 동작 원리

> **학습 목표**
> - Kubernetes를 이루는 컴포넌트가 각각 무슨 일을 하는지 안다
> - **조정 루프(reconciliation loop)** — k8s의 심장을 이해한다
> - 파드 하나를 만들 때 내부에서 벌어지는 일의 흐름을 안다
> - 이걸 알면 나중에 "왜 안 되지?"를 논리적으로 추적할 수 있다
>
> **선행 스텝**: [Step 01](../step-01-setup/index.md)
> **예상 소요**: 45분

이 스텝은 개념이 중심입니다. 하지만 마지막에 **조정 루프를 직접 눈으로 봅니다.** 그게 이 스텝의 하이라이트입니다.

---

## 2-1. 큰 그림 — control plane과 worker

Kubernetes 클러스터는 두 종류의 노드로 나뉩니다.

```
┌─────────────────────── Control Plane (두뇌) ───────────────────────┐
│                                                                     │
│   ┌────────────┐   ┌──────────┐   ┌─────────────┐  ┌────────────┐  │
│   │ API Server │   │   etcd   │   │  Scheduler  │  │ Controller │  │
│   │  (관문)    │◄─►│ (DB/기억)│   │ (배치 결정) │  │  Manager   │  │
│   └─────▲──────┘   └──────────┘   └─────────────┘  └────────────┘  │
│         │                                                           │
└─────────┼───────────────────────────────────────────────────────────┘
          │ (모든 통신은 API Server를 거친다)
     ┌────┴─────────────────────┬───────────────────────────┐
     ▼                          ▼                            ▼
┌─────────────────┐   ┌─────────────────┐         ┌─────────────────┐
│  Worker Node 1  │   │  Worker Node 2  │         │       ...       │
│  ┌───────────┐  │   │  ┌───────────┐  │         │                 │
│  │  kubelet  │  │   │  │  kubelet  │  │         │                 │
│  │ kube-proxy│  │   │  │ kube-proxy│  │         │                 │
│  │ [파드][파드]│  │   │  │ [파드][파드]│  │         │                 │
│  └───────────┘  │   │  └───────────┘  │         │                 │
└─────────────────┘   └─────────────────┘         └─────────────────┘
```

실제로 우리 클러스터에서 이 컴포넌트들이 **파드로** 돌고 있습니다.

```bash
kubectl get pods -n kube-system -o wide
```

**결과** (NODE 열만 발췌)
```
NAME                                          NODE
etcd-learn-control-plane                      learn-control-plane
kube-apiserver-learn-control-plane            learn-control-plane
kube-controller-manager-learn-control-plane   learn-control-plane
kube-scheduler-learn-control-plane            learn-control-plane
coredns-589f44dc88-wdrzx                      learn-control-plane
kube-proxy-xw572                              learn-worker
kindnet-d4gl5                                 learn-worker
```

control-plane 컴포넌트(etcd, apiserver, scheduler, controller-manager)는 전부 **control-plane 노드**에 있고, `kube-proxy`와 `kindnet`(네트워크)은 **모든 노드**에 하나씩 있습니다.

---

## 2-2. Control Plane 컴포넌트 — 각자의 역할

### API Server — 유일한 관문
클러스터의 **모든 통신이 반드시 거치는 문**입니다. `kubectl`도, 노드의 kubelet도, 다른 컴포넌트도 전부 API Server에게만 말을 겁니다. 컴포넌트끼리 직접 통신하지 않습니다.

- 인증/인가(Step 16의 RBAC)를 여기서 검사합니다.
- 모든 요청을 검증하고 etcd에 저장합니다.

### etcd — 클러스터의 기억
**분산 key-value 데이터베이스**. "원하는 상태"와 "현재 상태"가 전부 여기 저장됩니다. **etcd가 곧 클러스터의 진실**입니다. etcd를 잃으면 클러스터를 잃습니다(그래서 Step 23에서 etcd 백업을 다룹니다).

> `kubectl get`으로 보는 모든 것은 결국 API Server가 etcd에서 읽어다 주는 것입니다.

### Scheduler — 어느 노드에 놓을지 결정
새 파드가 생기면 "이 파드를 **어느 워커 노드**에 놓을까?"를 결정합니다. 노드의 남은 리소스, 어피니티 규칙, 테인트 등을 종합해 점수를 매깁니다(Step 09, 13). **스케줄러는 결정만 하고, 실제로 띄우진 않습니다.**

### Controller Manager — 조정 루프의 실행자
수십 개의 컨트롤러가 들어있습니다. 각 컨트롤러는 **"원하는 상태"와 "현재 상태"를 계속 비교하며 차이를 메웁니다.** 이게 다음 절의 핵심입니다.

---

## 2-3. Worker 노드 컴포넌트

### kubelet — 노드의 대리인
각 워커 노드에서 도는 에이전트. API Server로부터 "이 노드에 이 파드를 띄워라"를 받아, **컨테이너 런타임(containerd)에게 실제로 컨테이너를 띄우라고 지시**합니다. 그리고 파드 상태를 API Server에 계속 보고합니다. 프로브(Step 08)를 실행하는 것도 kubelet입니다.

### kube-proxy — 서비스 트래픽 라우팅
Service(Step 06)로 오는 트래픽을 실제 파드로 전달하는 네트워크 규칙(iptables/IPVS)을 관리합니다.

### 컨테이너 런타임 — containerd
실제로 컨테이너를 실행하는 엔진. Step 01에서 봤듯이 Docker가 아니라 containerd입니다.

---

## 2-4. 파드 하나가 뜨기까지 — 전체 흐름

`kubectl apply -f pod.yaml`을 쳤을 때 벌어지는 일:

```
 1. kubectl        →  API Server에 "이 파드를 원한다" POST
 2. API Server     →  인증/인가 검사 → 검증 → etcd에 저장 → "접수됨" 응답
                      (이 시점에 kubectl은 끝. 파드는 아직 안 떴다!)
 3. Scheduler      →  etcd를 감시하다 "노드 미배정 파드" 발견
                   →  적합한 노드 계산 → "worker-1에 배정" 을 API Server에 기록
 4. kubelet(w-1)   →  자기 노드에 배정된 파드 발견
                   →  containerd에 "컨테이너 띄워" 지시
                   →  이미지 pull → 컨테이너 실행 → 상태를 API Server에 보고
 5. API Server     →  상태를 etcd에 저장 → 이제 kubectl get 하면 Running
```

> 💡 **핵심 통찰**: `kubectl apply`가 성공했다는 것은 **"API Server가 요청을 접수했다"** 는 뜻일 뿐, **"파드가 떴다"** 는 뜻이 아닙니다. 그래서 apply 직후 바로 `get pods`하면 `ContainerCreating`이나 `Pending`이 보입니다. 이 시차를 이해하면 Step 24 트러블슈팅이 쉬워집니다 — "어느 단계에서 멈췄는가"를 짚을 수 있으니까요.

| 멈춘 단계 | 증상 | Step |
|---|---|---|
| 3. 스케줄러가 노드를 못 정함 | `Pending` | 09, 13, 24 |
| 4. 이미지 pull 실패 | `ImagePullBackOff` | 24 |
| 4. 컨테이너가 뜨자마자 죽음 | `CrashLoopBackOff` | 08, 24 |
| 4. 볼륨/시크릿 없음 | `ContainerCreating` 지속 | 24 |

---

## 2-5. 조정 루프 — Kubernetes의 심장 (직접 보기)

**Kubernetes의 모든 것은 이 한 문장으로 설명됩니다:**

> **원하는 상태(desired)와 현재 상태(current)를 끊임없이 비교하고, 다르면 같아지도록 행동한다.**

말로만 들으면 추상적이니 직접 봅시다. "파드 3개를 원한다"고 선언한 뒤, **하나를 강제로 죽여** 보겠습니다.

```bash
kubectl create namespace step02
kubectl -n step02 create deployment web --image=nginx:1.27-alpine --replicas=3
```

3개가 떴습니다.

```bash
kubectl -n step02 get pods -o wide
```

**결과**
```
NAME                   READY   STATUS    RESTARTS   AGE   NODE
web-7b8c57c6d6-8nwhq   1/1     Running   0          8s    learn-worker
web-7b8c57c6d6-ngwxs   1/1     Running   0          8s    learn-worker2
web-7b8c57c6d6-q2rws   1/1     Running   0          8s    learn-worker2
```

이제 **하나를 죽입니다.**

```bash
kubectl -n step02 delete pod web-7b8c57c6d6-8nwhq
kubectl -n step02 get pods
```

**결과**
```
pod "web-7b8c57c6d6-8nwhq" deleted from step02 namespace
NAME                   READY   STATUS    RESTARTS   AGE
web-7b8c57c6d6-ngwxs   1/1     Running   0          14s
web-7b8c57c6d6-q2rws   1/1     Running   0          14s
web-7b8c57c6d6-xbcpw   1/1     Running   0          6s     ← 방금 자동으로 생김!
```

**아무도 시키지 않았는데 새 파드 `xbcpw`가 생겼습니다.** 무슨 일이 벌어진 걸까요?

```
원하는 상태: 파드 3개 (Deployment에 replicas: 3 이라고 etcd에 적혀 있음)
현재 상태:   파드 2개 (하나를 죽였으니)
            ↓
Controller Manager의 조정 루프가 "3 ≠ 2" 를 감지
            ↓
차이를 메우기 위해 새 파드 1개 생성 요청
            ↓
현재 상태: 파드 3개 → 원하는 상태와 일치 → 안정
```

이것이 조정 루프입니다. 노드가 죽어도, 파드가 죽어도, 심지어 누가 실수로 지워도, k8s는 **선언된 상태로 계속 되돌립니다.** 여러분이 할 일은 "무엇을 원하는지" 선언하는 것뿐입니다.

> ⚠️ **함정**: 그래서 파드를 `kubectl delete pod`로 지워도 "삭제"되지 않고 되살아납니다. Deployment가 관리하는 파드를 진짜로 없애려면 **Deployment 자체를 지우거나 replicas를 줄여야** 합니다. Step 05에서 다룹니다. "파드가 안 지워져요"는 초보자의 단골 질문인데, 사실 그게 k8s가 제대로 동작하는 증거입니다.

```bash
kubectl delete namespace step02
```

---

## 2-6. 선언형이 왜 이렇게 설계됐나

Step 01에서 배운 선언형(`apply`)이 왜 정답인지 이제 명확합니다. **조정 루프가 선언형을 전제로 동작하기 때문입니다.**

- 명령형("파드를 띄워라")은 **한 번의 행동**입니다. 죽으면 끝입니다.
- 선언형("파드 3개가 있는 상태를 원한다")은 **지속적인 목표**입니다. k8s가 계속 지킵니다.

여러분이 YAML에 `replicas: 3`이라고 적는 순간, 그건 "3개를 만들어라"가 아니라 **"항상 3개인 상태를 유지하라"** 는 계약입니다.

---

## 정리

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| API Server | control-plane | 모든 통신의 유일한 관문, 인증/검증 |
| etcd | control-plane | 클러스터의 모든 상태를 저장 (= 진실) |
| Scheduler | control-plane | 파드를 어느 노드에 놓을지 결정 |
| Controller Manager | control-plane | 조정 루프 실행 (원하는 상태 유지) |
| kubelet | 모든 노드 | 컨테이너를 실제로 띄우고 상태 보고 |
| kube-proxy | 모든 노드 | Service 트래픽 라우팅 |
| containerd | 모든 노드 | 컨테이너 실행 엔진 |

**한 줄 요약**: k8s = 원하는 상태를 etcd에 적어두면, 조정 루프가 현재 상태를 거기에 계속 맞추는 시스템.

---

## 연습 과제

[challenge.md](challenge.md)

---

## 다음 단계

이제 가장 작은 실행 단위, 파드를 깊이 다룹니다.

→ [Step 03 — 파드](../step-03-pods/index.md)

---

## 실습 파일

이 스텝은 개념 중심이라 매니페스트(YAML) 없이 `commands.sh` 하나로 끝납니다. 이 스크립트는 본문을 위에서 아래로 그대로 재현합니다 — 먼저 `2-1`에서 control-plane/worker 컴포넌트가 실제로 파드로 떠 있는 것을 확인하고, 이어서 `2-5`의 하이라이트인 **조정 루프**를 직접 눈으로 봅니다. 위에서부터 한 줄씩 따라 치면서 결과를 관찰하는 것을 권장합니다.

### commands.sh

- **역할과 실행 시점**: 본문 `2-1`(컴포넌트 확인)과 `2-5`(조정 루프 관찰)를 그대로 스크립트로 옮긴 파일입니다. Step 01에서 만든 kind 클러스터(`learn-control-plane` / `learn-worker` / `learn-worker2`)가 이미 떠 있다는 것을 전제로 합니다.
- **컴포넌트 확인 구간**: `kubectl get pods -n kube-system -o wide` 로 전체를 본 뒤, `grep -E "etcd|apiserver|scheduler|controller-manager"` 로 **control-plane 노드에만 있는 두뇌 컴포넌트**를, `grep -E "kube-proxy|kindnet"` 으로 **모든 노드에 하나씩 있는 네트워크 컴포넌트**를 각각 분리해서 보여줍니다. 두 grep의 NODE 열을 비교하는 것이 이 구간의 목적입니다.
- **조정 루프 구간**: `kubectl create deployment web --image=nginx:1.27-alpine --replicas=3` 으로 "파드 3개를 원한다"를 선언한 뒤, `VICTIM=$(kubectl -n step02 get pods -o jsonpath='{.items[0].metadata.name}')` 로 **첫 번째 파드 이름을 자동으로 뽑아** 죽입니다. 본문처럼 파드 이름을 손으로 복사하지 않아도 되도록 만든 장치입니다.
- **`sleep`이 들어 있는 이유**: `sleep 8`(생성 대기), `sleep 5`(재생성 대기), `sleep 3`(스케일다운 대기)은 본문 `2-4`의 "**접수 ≠ 완료**" 를 그대로 반영한 것입니다. 이 `sleep`을 빼고 바로 `get pods`를 치면 `ContainerCreating`이나 `Pending`만 보이고 조정 루프의 결과(새 파드 1개)를 놓칠 수 있습니다.
- **흐름 확인**: `kubectl -n step02 get events --sort-by=.lastTimestamp | tail -15` 는 `Scheduled` → `Pulling`/`Pulled` → `Created` → `Started` 순서를 시간순으로 보여줍니다. 본문 `2-4`의 5단계 흐름이 실제 이벤트로 찍히는 것을 확인하는 대목입니다.
- **함정 재현**: `kubectl -n step02 scale deployment web --replicas=0` 은 본문의 ⚠️ 함정을 증명합니다. `delete pod`로는 되살아나지만, **원하는 상태 자체를 0으로 바꾸면** 파드가 정말로 사라집니다.
- **주의**: 맨 위 `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS(Homebrew) 로컬 환경 전제입니다. 다른 OS에서는 이 줄이 없어도 무방합니다. 또 마지막 줄 `kubectl delete namespace step02` 는 이 스텝에서 만든 리소스를 **전부 삭제**하므로, 관찰이 끝난 뒤에 실행하세요.

```bash file="./commands.sh"
```
