# Step 01 — 클러스터 구축과 kubectl

> **학습 목표**
> - Kubernetes가 무엇을 해결하는지 한 문단으로 이해한다
> - kind로 로컬 클러스터를 5분 만에 띄운다
> - `kubectl`로 클러스터를 조회하고, 컨텍스트를 이해한다
> - **명령형(imperative)** 과 **선언형(declarative)** 의 차이를 안다 — 이 코스의 뼈대다
>
> **선행 스텝**: 없음
> **예상 소요**: 40분

---

## 1-1. Kubernetes가 푸는 문제

컨테이너(Docker) 하나를 노트북에서 돌리는 건 쉽습니다. 그런데 실제 서비스는:

- 컨테이너를 **수십~수백 개** 띄워야 하고
- 하나가 죽으면 **자동으로 다시 띄워야** 하고
- 트래픽이 늘면 **개수를 늘리고**, 줄면 줄여야 하고
- 여러 서버(노드)에 **골고루 배치**해야 하고
- 무중단으로 **새 버전을 배포**해야 합니다

이걸 사람이 손으로 하면 불가능합니다. **Kubernetes(k8s)는 "원하는 상태(desired state)"를 선언하면, 실제 상태가 그와 같아지도록 끊임없이 자동 조정하는 시스템**입니다.

> "컨테이너 3개를 항상 켜둬라"라고 선언하면, 하나가 죽어도 k8s가 알아서 새로 띄워 3개를 유지합니다. 여러분이 시키는 게 아니라, **여러분이 목표를 적어두면 k8s가 달성합니다.** 이 사고방식이 전부입니다.

---

## 1-2. 학습 환경: 왜 kind인가

진짜 Kubernetes를 쓰려면 보통 클라우드(EKS, GKE, AKS)나 여러 대의 서버가 필요합니다. 학습용으로는 과합니다.

**kind(Kubernetes IN Docker)** 는 Docker 컨테이너 하나하나를 "노드"로 삼아 노트북 안에 진짜 k8s 클러스터를 만듭니다. 만들고 부수는 데 1분, 완전히 격리되고, 공짜입니다.

| 도구 | 특징 |
|---|---|
| **kind** | 가장 가볍고 빠름. 멀티노드 쉬움. 이 코스의 기본 |
| minikube | 기능 풍부, 애드온 많음. 조금 무거움 |
| k3d | k3s 기반, 초경량 |
| Docker Desktop 내장 | 설치 간편, 단일 노드만 |

이 코스는 **control-plane 1개 + worker 2개 = 3노드** 구성을 씁니다. 워커가 2개여야 스케줄링·어피니티 같은 실습(Step 13, 21)을 제대로 할 수 있습니다.

---

## 1-3. 설치와 클러스터 생성

```bash
# 도구 설치 (macOS)
brew install kind kubectl helm

# 클러스터 생성
cd k8s/cluster
./create-cluster.sh
```

`create-cluster.sh`는 `kind-cluster.yaml`로 3노드 클러스터를 만들고, 이미지 pull이 되는지 스모크 테스트까지 합니다. 두 파일의 전문과 해설은 [클러스터 환경](../cluster/) 페이지에 있습니다.

> ⚠️ **사내망 함정 (중요)**: 회사 네트워크가 TLS를 가로채는 보안 프록시(예: Netskope, Zscaler)를 쓰면, 노드가 이미지를 받을 때 이런 에러가 납니다:
> ```
> failed to pull image "nginx": tls: failed to verify certificate:
> x509: certificate signed by unknown authority
> ```
> 호스트의 Docker는 회사 CA를 신뢰하지만, kind 노드(컨테이너 안)는 그 CA를 모르기 때문입니다. 이럴 땐 CA를 노드에 주입하는 옵션으로 다시 만드세요:
> ```bash
> ./delete-cluster.sh
> INJECT_CA=1 ./create-cluster.sh
> ```
> 스크립트가 프록시 CA를 추출해 노드의 신뢰 저장소에 심고 containerd를 재시작합니다. (이 코스는 실제로 이 환경에서 검증했습니다.)

---

## 1-4. kubectl — 클러스터와 대화하기

`kubectl`("큐브 컨트롤" 또는 "큐브 씨티엘")은 Kubernetes API 서버에 명령을 보내는 CLI입니다. 앞으로 모든 작업을 이걸로 합니다.

### 클러스터가 살아있는지

```bash
kubectl cluster-info
```

**결과**
```
Kubernetes control plane is running at https://127.0.0.1:57282
CoreDNS is running at https://127.0.0.1:57282/api/v1/namespaces/kube-system/services/kube-dns:dns/proxy
```

### 노드 보기

```bash
kubectl get nodes -o wide
```

**결과**
```
NAME                  STATUS   ROLES           AGE   VERSION   INTERNAL-IP   OS-IMAGE                        CONTAINER-RUNTIME
learn-control-plane   Ready    control-plane   13m   v1.36.1   172.22.0.3    Debian GNU/Linux 13 (trixie)    containerd://2.3.1
learn-worker          Ready    <none>          13m   v1.36.1   172.22.0.2    Debian GNU/Linux 13 (trixie)    containerd://2.3.1
learn-worker2         Ready    <none>          13m   v1.36.1   172.22.0.4    Debian GNU/Linux 13 (trixie)    containerd://2.3.1
```

- **control-plane** — 클러스터의 두뇌(API 서버, 스케줄러 등). Step 02에서 자세히.
- **worker** — 실제 파드(컨테이너)가 도는 곳.
- **컨테이너 런타임이 `containerd`** 입니다. Docker가 아닙니다. Kubernetes는 1.24부터 Docker를 직접 쓰지 않고 containerd 같은 CRI 런타임을 씁니다.

### kubectl 명령 구조

```
kubectl [동사] [리소스종류] [이름] [플래그]
        get     pods       web    -n step01 -o wide
        describe deployment nginx
        delete  service    web
```

자주 쓰는 동사: `get`(목록), `describe`(상세), `apply`(생성/수정), `delete`(삭제), `logs`, `exec`.

> 💡 **실무 팁**: 약어를 외우면 빨라집니다. `pods`→`po`, `deployment`→`deploy`, `service`→`svc`, `namespace`→`ns`, `nodes`→`no`. 전체 목록은 `kubectl api-resources`. 그리고 셸에 `alias k=kubectl`을 걸어두면 하루에 수백 번 치는 손가락이 편합니다.

---

## 1-5. 첫 파드 띄우기 — 명령형(imperative)

일단 컨테이너 하나를 띄워봅시다. 실습은 전용 네임스페이스 `step01`에서 합니다.

```bash
kubectl create namespace step01
kubectl -n step01 run web --image=nginx:1.27-alpine
kubectl -n step01 get pods
```

**결과**
```
pod/web created
NAME   READY   STATUS    RESTARTS   AGE
web    1/1     Running   0          8s
```

`READY 1/1`, `STATUS Running` — nginx 컨테이너가 워커 노드 어딘가에서 돌고 있습니다. 어디서 도는지 봅시다:

```bash
kubectl -n step01 get pod web -o wide
```

**결과**
```
NAME   READY   STATUS    RESTARTS   AGE   IP           NODE           NOMINATED NODE   READINESS GATES
web    1/1     Running   0          30s   10.244.1.2   learn-worker   <none>           <none>
```

이렇게 `kubectl run`, `kubectl create`처럼 **"무엇을 하라"고 명령을 내리는 방식**을 **명령형(imperative)** 이라고 합니다. 빠르고 편하지만, **내가 무슨 상태를 만들었는지 기록이 남지 않습니다.**

---

## 1-6. 선언형(declarative) — 진짜 Kubernetes 방식

같은 파드를 **YAML 파일로 선언**해 봅시다.

```yaml
# manifests/pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: web-declarative
  namespace: step01
  labels:
    app: web
spec:
  containers:
    - name: nginx
      image: nginx:1.27-alpine
      ports:
        - containerPort: 80
```

```bash
kubectl apply -f manifests/pod.yaml
kubectl -n step01 get pods
```

**결과**
```
pod/web-declarative created
NAME              READY   STATUS    RESTARTS   AGE
web               1/1     Running   0          2m
web-declarative   1/1     Running   0          5s
```

겉보기엔 `run`과 똑같아 보입니다. 하지만 결정적 차이가 있습니다.

### 왜 선언형이 정답인가

**같은 파일을 다시 apply해도 안전합니다(멱등성).**

```bash
kubectl apply -f manifests/pod.yaml
```

**결과**
```
pod/web-declarative unchanged
```

에러가 아니라 `unchanged`입니다. 이미 원하는 상태이므로 아무것도 안 합니다. 반면 명령형 `kubectl create`를 두 번 하면:

```bash
kubectl -n step01 run web --image=nginx:1.27-alpine
```

**결과**
```
Error from server (AlreadyExists): pods "web" already exists
```

| | 명령형 (`run`, `create`, `edit`) | 선언형 (`apply -f`) |
|---|---|---|
| 방식 | "이 명령을 실행하라" | "이 상태가 되게 하라" |
| 반복 실행 | 에러 (이미 존재) | 안전 (unchanged) |
| 기록 | 남지 않음 | **YAML 파일 = 문서이자 이력** |
| Git 관리 | 불가 | **가능 (GitOps의 기반)** |
| 실무 사용 | 빠른 확인용 | **거의 모든 실제 배포** |

> 💡 **실무 팁**: 명령형은 **버리는 실험**에만 쓰세요. 실무의 모든 리소스는 YAML로 선언하고 Git에 넣습니다. 그래야 "지금 클러스터에 뭐가 떠 있는가"가 코드로 남고, 리뷰·롤백·재현이 됩니다. 이걸 극한까지 밀어붙인 게 Step 23의 GitOps입니다.

> 💡 **실무 팁**: 명령형의 편리함과 선언형의 안전함을 합치는 법 — `--dry-run=client -o yaml`로 YAML 초안을 뽑으세요.
> ```bash
> kubectl -n step01 run web --image=nginx:1.27-alpine --dry-run=client -o yaml > draft.yaml
> ```
> 손으로 YAML을 처음부터 쓰지 않고, 생성된 초안을 다듬어 씁니다. 실무에서 매일 쓰는 기법입니다.

---

## 1-7. 컨텍스트 — 여러 클러스터 사이를 오가기

실무에서는 클러스터가 여러 개입니다(개발/스테이징/운영). `kubectl`이 **지금 어느 클러스터를 보고 있는지**가 컨텍스트입니다.

```bash
kubectl config get-contexts
```

**결과**
```
CURRENT   NAME         CLUSTER      AUTHINFO     NAMESPACE
*         kind-learn   kind-learn   kind-learn   default
```

`*`가 현재 컨텍스트입니다. 전환은 `kubectl config use-context <이름>`.

> ⚠️ **함정 (실무에서 가장 무서운 사고)**: 개발 클러스터인 줄 알고 명령을 쳤는데 **운영 클러스터**였던 사고가 정말 많습니다. `kubectl delete`를 운영에서 잘못 치는 순간 장애입니다. **명령을 치기 전 항상 `kubectl config current-context`로 확인하는 습관**을 들이세요. `kube-ps1` 같은 도구로 셸 프롬프트에 현재 컨텍스트를 항상 표시하는 것을 강력 추천합니다.

> ⚠️ **함정**: 네임스페이스를 매번 `-n step01`로 지정하는 게 번거롭다면 기본값을 바꿀 수 있습니다:
> ```bash
> kubectl config set-context --current --namespace=step01
> ```
> 단, 이렇게 해두고 잊으면 "왜 default에 아무것도 없지?"로 혼란스러울 수 있습니다. 이 코스는 명확성을 위해 항상 `-n`을 명시합니다.

---

## 1-8. 정리

```bash
kubectl delete namespace step01
```

**결과**
```
namespace "step01" deleted
```

네임스페이스를 지우면 그 안의 모든 리소스(파드, 서비스 등)가 함께 사라집니다. **각 스텝이 자기 네임스페이스를 쓰는 이유가 이것입니다 — 정리가 한 줄로 끝납니다.**

클러스터 자체를 초기화하려면:
```bash
cd k8s/cluster && ./delete-cluster.sh && ./create-cluster.sh
```

---

## 정리

| 개념 | 요점 |
|---|---|
| Kubernetes | 원하는 상태를 선언하면 자동으로 유지하는 시스템 |
| kind | Docker로 만드는 로컬 학습 클러스터 |
| kubectl | 클러스터와 대화하는 CLI (`동사 리소스 이름 플래그`) |
| 명령형 | `run`/`create` — 빠른 실험용, 기록 안 남음 |
| 선언형 | `apply -f` — 멱등, YAML=문서, 실무 표준 |
| 컨텍스트 | 지금 보고 있는 클러스터. **치기 전에 항상 확인** |
| 네임스페이스 | 리소스 격리 단위. 지우면 통째로 정리 |

---

## 연습 과제

[challenge.md](challenge.md)에서 과제를 풀어보세요.

---

## 다음 단계

파드를 띄워봤으니, 이제 그 뒤에서 무슨 일이 벌어지는지 봅니다.

→ [Step 02 — 아키텍처와 동작 원리](../step-02-architecture/)

---

## 실습 파일

이 스텝에서 쓰는 파일은 두 개입니다. `commands.sh`는 1-4부터 1-8까지 강의 본문에서 하나씩 쳐 본 명령을 순서대로 모아둔 대본이고, `manifests/pod.yaml`은 1-6에서 선언형으로 띄우는 파드의 매니페스트입니다. 클러스터가 이미 떠 있다는 전제(`k8s/cluster/create-cluster.sh` 실행 완료) 하에, `commands.sh` 안에서 `manifests/pod.yaml`을 `kubectl apply -f`로 불러 쓰는 구조입니다.

### commands.sh

강의 본문의 흐름을 그대로 따라가는 실행 대본입니다. 한 줄씩 복사해 붙여넣으며 결과를 관찰하는 것이 목적이고, 통째로 실행해도 마지막에 스스로 뒷정리까지 합니다.

- **첫 확인은 컨텍스트입니다.** 맨 앞의 `kubectl config current-context`가 `kind-learn`을 출력하는지부터 봅니다. 1-7에서 강조한 "치기 전에 확인" 습관을 **맨 첫 `kubectl` 명령**으로 박아둔 것입니다. 이 스크립트는 마지막에 `kubectl delete namespace step01`이라는 **파괴적 명령**을 실행하므로, 엉뚱한 클러스터를 보고 있으면 안 됩니다.
- `HERE="$(cd "$(dirname "$0")" && pwd)"` 로 스크립트 자신의 절대경로를 구해, 뒤에서 `kubectl apply -f "$HERE/manifests/pod.yaml"` 처럼 씁니다. 덕분에 **어느 디렉터리에서 실행해도** 매니페스트를 못 찾는 일이 없습니다.
- `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS(Apple Silicon)에서 Homebrew로 설치한 `kubectl`/`kind`를 찾기 위한 로컬 환경 전제입니다. Intel Mac이나 Linux라면 필요 없거나 경로가 다를 수 있습니다.
- `kubectl api-resources | head -20` 은 1-4에서 언급한 "전체 리소스 종류 목록"을 실제로 뽑아보는 줄입니다. 출력이 100줄이 넘어가므로 `head -20` 으로 앞부분만 잘라 봅니다. 여기서 각 리소스의 **약어(SHORTNAMES)** 와 **APIVERSION** 열을 눈여겨보세요.
- **[1-5] 명령형 구간**: `kubectl create namespace step01` → `kubectl -n step01 run web ...` 순서로 실행합니다. 네임스페이스를 먼저 만들지 않으면 파드 생성이 실패하므로 **순서 의존성**이 있습니다. `sleep 5` 는 파드가 `ContainerCreating`에서 `Running`으로 넘어갈 시간을 벌어주는 장치입니다.
- **[1-6] 선언형 구간**: 같은 `apply -f "$HERE/manifests/pod.yaml"` 을 **두 번** 실행합니다(사이에 `sleep 3` 과 `kubectl -n step01 get pods` 가 끼어 있습니다). 두 번째 출력이 `unchanged`로 나오는 것을 눈으로 확인하라는 의도입니다. 이어지는 `kubectl -n step01 run web ... || echo "↑ 이미 존재해서 에러 (정상)"` 는 반대로 명령형이 재실행에서 `AlreadyExists` 에러를 내는 것을 보여줍니다. `||` 로 감싼 이유는 **에러가 나는 게 정상**이고, 그래야 스크립트가 거기서 멈추지 않고 끝까지 진행되기 때문입니다.
- `kubectl -n step01 run draft --image=... --dry-run=client -o yaml | head -20` 은 실제로 파드를 만들지 않고 YAML 초안만 출력합니다(`--dry-run=client` — API 서버에 보내지 않음). 1-6 마지막 실무 팁의 실물입니다.
- 마지막 `kubectl delete namespace step01` 로 이 스텝에서 만든 파드 두 개(`web`, `web-declarative`)가 한꺼번에 사라집니다.

```bash file="./commands.sh"
```

### manifests/pod.yaml

1-6 "선언형" 절에서 `kubectl apply -f manifests/pod.yaml` 로 적용하는, 이 코스의 **첫 매니페스트**입니다. 1-5에서 `kubectl run web` 으로 만든 것과 사실상 같은 파드를 YAML로 다시 쓴 것이라, 명령형과 선언형을 나란히 비교하는 것이 이 파일의 존재 이유입니다.

- `apiVersion: v1` + `kind: Pod` — 어떤 API 그룹의 어떤 리소스인지 선언합니다. Pod는 코어 그룹이라 그룹명 없이 `v1` 입니다.
- `metadata.name: web-declarative` — 1-5의 `web` 과 **이름을 일부러 다르게** 두었습니다. 그래서 두 파드가 동시에 떠 있고, `kubectl -n step01 get pods` 에서 `web`과 `web-declarative`가 나란히 보입니다.
- `metadata.namespace: step01` — 네임스페이스를 매니페스트 안에 박아두었기 때문에 `kubectl apply -f` 에 `-n` 을 붙이지 않아도 `step01`에 생성됩니다. 반대로 말하면 **`step01` 네임스페이스가 미리 있어야** 하고, 없으면 `namespaces "step01" not found` 에러가 납니다.
- `metadata.labels.app: web` — 지금은 쓰이지 않지만, Step 03 이후 Service의 `selector`나 Deployment가 파드를 골라내는 **기준**이 되는 라벨입니다. 여기서 미리 습관을 들여둡니다.
- `spec.containers[0].image: nginx:1.27-alpine` — 태그를 `1.27-alpine`으로 **고정**했습니다. `latest`가 아니라 명시적 버전을 쓰는 것이 재현 가능한 배포의 기본입니다.
- `ports[0].containerPort: 80` — 이 값은 **문서 성격이 강합니다.** 적어두지 않아도 nginx는 80을 열고, 이 필드가 포트를 "여는" 것도 아닙니다. 실제 외부 노출은 Step 04의 Service에서 다룹니다.

```yaml file="./manifests/pod.yaml"
```
