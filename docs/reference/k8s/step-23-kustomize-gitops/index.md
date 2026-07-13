# Step 23 — Kustomize & GitOps

> **학습 목표**
> - Kustomize가 무엇이고 왜 kubectl에 내장됐는지, Helm과 무엇이 다른지 안다
> - `base` / `overlays` 구조와 `kustomization.yaml`(resources)로 선언형 커스터마이즈를 구성한다
> - overlay 기법을 실증한다: `namespace`, `namePrefix`, `labels`(commonLabels), `patches`, `configMapGenerator`, `images`
> - 같은 base를 dev(replicas 1)와 prod(replicas 3)로 다르게 배포하고 결과를 비교한다
> - GitOps(선언형 + Git이 진실의 원천 + reconcile)와 ArgoCD/Flux, pull vs push 모델을 이해한다
>
> **선행 스텝**: [Step 07 — 컨피그·시크릿](../step-07-config-secret/README.md), [Step 05 — 디플로이먼트](../step-05-deployments/README.md)
> **예상 소요**: 45분

---

## 23-1. Kustomize가 뭔가, 왜 쓰는가

환경마다 매니페스트가 조금씩 다릅니다. dev는 레플리카 1개, prod는 3개. dev는 배너가 "DEV", prod는 "PROD". 이걸 해결하는 흔한(나쁜) 방법이 **YAML 복사-붙여넣기**입니다. `deployment-dev.yaml`, `deployment-prod.yaml`을 따로 두면, 공통 부분을 고칠 때마다 두 곳을 똑같이 고쳐야 하고 결국 어긋납니다.

**Kustomize**는 이 문제를 **템플릿 없이(templateless)** 푸는 도구입니다. 원본 YAML(=base)은 그대로 두고, "이 부분만 이렇게 바꿔라"라는 **오버레이(overlay)**를 겹칩니다. 원본 YAML은 여전히 100% 유효한 표준 매니페스트이고, 변수 치환 문법(`{{ }}`)이 전혀 없습니다.

| | Kustomize | Helm |
|---|---|---|
| 방식 | 선언형 **패치/겹치기** (templateless) | **템플릿 엔진** (`{{ .Values }}`) |
| 원본 파일 | 그 자체로 유효한 표준 YAML | 템플릿이라 단독으론 kubectl 적용 불가 |
| 설치 | **kubectl에 내장** (`kubectl -k`) | 별도 바이너리·릴리스·저장소 필요 |
| 값 주입 | overlay가 필드를 덮어씀 | `values.yaml` + 템플릿 렌더 |
| 잘 맞는 곳 | 환경별 소규모 차이(같은 조직) | 배포용 패키지 재분배(차트 생태계) |

> Kustomize와 Helm은 경쟁이라기보다 **결이 다릅니다**. 남이 만든 복잡한 앱을 파라미터로 설치할 땐 Helm, 우리 앱을 환경별로 조금씩 바꿔 배포할 땐 Kustomize가 편합니다. 둘을 같이 쓰기도 합니다(Helm 차트 렌더 결과에 Kustomize 패치).

이 스텝에서는 `kubectl` v1.36에 내장된 Kustomize(v5.8.1)를 씁니다. 별도 설치가 필요 없습니다.

```bash
kubectl version | grep Kustomize
```
```
Kustomize Version: v5.8.1
```

---

## 23-2. base — 환경 공통 원본

`base`는 모든 환경이 공유하는 원본입니다. Deployment + Service + ConfigMap을 담고, **네임스페이스는 정하지 않습니다**(중립). 어디에 배포할지는 overlay가 정합니다.

```yaml
# manifests/base/deployment.yaml (발췌)
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: web
          image: nginx:1.27-alpine
          env:
            - name: MESSAGE
              valueFrom:
                configMapKeyRef:
                  name: web-config      # 해시 붙은 이름으로 자동 치환됨
                  key: MESSAGE
```

핵심은 `base/kustomization.yaml`입니다. `resources`로 어떤 파일을 묶을지, `labels`로 공통 레이블을, `configMapGenerator`로 ConfigMap을 선언합니다.

```yaml
# manifests/base/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

labels:
  - pairs:
      app: web
      course: k8s-learn
    includeSelectors: true      # 셀렉터에도 이 레이블을 넣는다

resources:
  - deployment.yaml
  - service.yaml

configMapGenerator:
  - name: web-config
    literals:
      - MESSAGE=Hello from BASE (overlay 가 덮어씀)
```

> 💡 **팁**: `labels`는 예전 `commonLabels`를 대체하는 최신 문법입니다(kustomize v5에서 `commonLabels`는 deprecated 경고를 냅니다). `includeSelectors: true`를 주면 `commonLabels`처럼 Deployment/Service의 셀렉터에도 레이블이 들어가, 파드를 정확히 골라냅니다.

---

## 23-3. overlays — 환경별 차이만 얹기

overlay는 `resources`로 base를 가리키고, 바꿀 것만 선언합니다. dev overlay를 봅시다.

```yaml
# manifests/overlays/dev/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: step23-dev          # 이 overlay가 만드는 모든 것 → step23-dev
namePrefix: dev-               # 이름 앞에 dev- 를 붙임 (web → dev-web)
labels:
  - pairs:
      env: dev
    includeSelectors: true

resources:
  - ../../base                 # base를 통째로 가져와 그 위에 얹는다

patches:
  - path: patch-deployment.yaml   # replicas, env를 전략적 병합으로 변경

configMapGenerator:
  - name: web-config
    behavior: replace          # base의 web-config를 dev 값으로 교체
    literals:
      - MESSAGE=Hello from DEV (step23-dev)
```

prod overlay는 같은 base를 가리키되 값이 다릅니다: `namespace: step23-prod`, `namePrefix: prod-`, `env=prod`, replicas 3, 그리고 `images`로 이미지까지 바꿉니다.

```yaml
# manifests/overlays/prod/kustomization.yaml (발췌)
namespace: step23-prod
namePrefix: prod-
patches:
  - path: patch-deployment.yaml    # replicas=3, TIER=production
images:
  - name: nginx                    # base의 nginx 를
    newName: paulbouwer/hello-kubernetes
    newTag: "1.10.1"               # 다른 이미지/태그로 교체
```

> ⚠️ **함정**: `namespace:` 필드는 리소스의 `metadata.namespace`를 **채워 줄 뿐, 네임스페이스를 만들지는 않습니다**. 대상 네임스페이스가 없으면 `kubectl apply -k`가 실패합니다. 미리 `kubectl create ns`로 만들거나, overlay의 `resources`에 Namespace 매니페스트를 포함시켜야 합니다.

### patch(전략적 병합)로 replicas와 env 바꾸기

`patches`가 가리키는 파일은 **부분 매니페스트**입니다. 같은 `name`을 가진 리소스를 찾아 겹칩니다.

```yaml
# manifests/overlays/prod/patch-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web            # base의 Deployment "web"에 병합
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: web    # 병합 키 = name. 이 컨테이너의 env에 TIER를 추가
          env:
            - name: TIER
              value: "production"
```

전략적 병합(strategic merge)은 리스트를 통째로 갈아끼우지 않고, `name` 같은 **병합 키**로 항목을 찾아 합칩니다. 그래서 base에 있던 `MESSAGE` env는 남고, `TIER`만 추가됩니다.

---

## 23-4. `kubectl kustomize`로 렌더 미리보기

배포 전에 **최종 결과를 눈으로** 볼 수 있습니다. `kubectl kustomize <overlay>`는 적용하지 않고 렌더만 합니다(= `kubectl apply -k`가 클러스터에 보낼 바로 그 YAML).

```bash
kubectl kustomize manifests/overlays/prod
```
**실행 결과** (발췌 — 여러 조각이 어떻게 합쳐졌는지 주목)
```yaml
apiVersion: v1
data:
  MESSAGE: Hello from PROD (step23-prod)
kind: ConfigMap
metadata:
  labels:
    app: web
    course: k8s-learn
    env: prod
  name: prod-web-config-f8fc64kgtm      # namePrefix + 내용 해시
  namespace: step23-prod
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prod-web                         # web → prod-web
  namespace: step23-prod
spec:
  replicas: 3                            # patch로 3
  template:
    spec:
      containers:
      - env:
        - name: TIER
          value: production              # patch로 추가
        - name: MESSAGE
          valueFrom:
            configMapKeyRef:
              key: MESSAGE
              name: prod-web-config-f8fc64kgtm   # 해시 이름으로 자동 치환!
        image: paulbouwer/hello-kubernetes:1.10.1   # images로 교체
        name: web
```

한 화면에 이 스텝의 모든 기법이 다 보입니다: `namespace`, `namePrefix`(prod-), `labels`(env=prod), `patches`(replicas 3 · TIER), `configMapGenerator`(해시 suffix), `images`(이미지 교체). 그리고 **가장 중요한 자동화**: `configMapKeyRef.name`이 원래 `web-config`였는데, kustomize가 해시 붙은 실제 이름 `prod-web-config-f8fc64kgtm`으로 **알아서 바꿔** 줬습니다.

> 💡 **팁**: `configMapGenerator`가 이름 뒤에 붙이는 해시는 **내용의 해시**입니다. ConfigMap 값이 바뀌면 이름도 바뀌고, 그걸 참조하는 Deployment의 `configMapKeyRef`도 새 이름을 가리키게 되어 **파드가 자동으로 롤링 업데이트**됩니다. 이름 없는 ConfigMap을 수정할 때 파드가 갱신 안 되던 고질병(Step 07의 함정)을 구조적으로 해결합니다.

---

## 23-5. 같은 base, 다른 배포 — dev와 prod

이제 실제로 배포합니다. 같은 base를 두 overlay로 각각 apply합니다.

```bash
kubectl create ns step23-dev
kubectl create ns step23-prod
kubectl apply -k manifests/overlays/dev
kubectl apply -k manifests/overlays/prod
```
**실행 결과**
```
namespace/step23-dev created
namespace/step23-prod created
configmap/dev-web-config-gk898kht9m created
service/dev-web created
deployment.apps/dev-web created
configmap/prod-web-config-f8fc64kgtm created
service/prod-web created
deployment.apps/prod-web created
```

배포된 결과를 네임스페이스별로 비교합니다.

```bash
kubectl get all -n step23-dev
```
```
NAME                           READY   STATUS    RESTARTS   AGE
pod/dev-web-6cfd6d4cff-sjg8t   1/1     Running   0          7s

NAME              TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)   AGE
service/dev-web   ClusterIP   10.96.12.200   <none>        80/TCP    7s

NAME                      READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/dev-web   1/1     1            1           7s

NAME                                 DESIRED   CURRENT   READY   AGE
replicaset.apps/dev-web-6cfd6d4cff   1         1         1       7s
```

```bash
kubectl get all -n step23-prod
```
```
NAME                            READY   STATUS    RESTARTS   AGE
pod/prod-web-69d9c46948-gh5rm   1/1     Running   0          7s
pod/prod-web-69d9c46948-qjzhf   1/1     Running   0          7s
pod/prod-web-69d9c46948-tpvv8   1/1     Running   0          7s

NAME               TYPE        CLUSTER-IP    EXTERNAL-IP   PORT(S)   AGE
service/prod-web   ClusterIP   10.96.85.37   <none>        80/TCP    7s

NAME                       READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/prod-web   3/3     3            3           7s

NAME                                  DESIRED   CURRENT   READY   AGE
replicaset.apps/prod-web-69d9c46948   3         3         3       7s
```

dev는 파드 1개, prod는 3개. 이름도 `dev-web` / `prod-web`으로 갈렸습니다. **소스는 단 하나의 base**입니다.

### 이미지 태그 차이 (`get deploy -o wide`)

```bash
kubectl get deploy -n step23-dev -o wide
kubectl get deploy -n step23-prod -o wide
```
```
# step23-dev
NAME      READY   ...   CONTAINERS   IMAGES              SELECTOR
dev-web   1/1     ...   web          nginx:1.27-alpine   app=web,course=k8s-learn,env=dev

# step23-prod
NAME       READY   ...   CONTAINERS   IMAGES                               SELECTOR
prod-web   3/3     ...   web          paulbouwer/hello-kubernetes:1.10.1   app=web,course=k8s-learn,env=prod
```

한눈에: dev는 `nginx:1.27-alpine`(base 그대로), prod는 `images` 트랜스포머가 바꾼 `paulbouwer/hello-kubernetes:1.10.1`. 셀렉터의 `env`도 각각 다릅니다.

> 두 네임스페이스를 한 번에 비교하려면 `course=k8s-learn` 레이블(base에서 심어 둠)로 가로지릅니다.
> ```bash
> kubectl get deploy -A -l course=k8s-learn \
>   -o custom-columns='NS:.metadata.namespace,NAME:.metadata.name,REPLICAS:.spec.replicas,IMAGE:.spec.template.spec.containers[0].image'
> ```
> ```
> NS            NAME       REPLICAS   IMAGE
> step23-dev    dev-web    1          nginx:1.27-alpine
> step23-prod   prod-web   3          paulbouwer/hello-kubernetes:1.10.1
> ```

### ConfigMap 이름의 해시 차이

```bash
kubectl get cm -n step23-dev
kubectl get cm -n step23-prod
```
```
# step23-dev
NAME                        DATA   AGE
dev-web-config-gk898kht9m   1      18s

# step23-prod
NAME                         DATA   AGE
prod-web-config-f8fc64kgtm   1      18s
```

`gk898kht9m`과 `f8fc64kgtm` — **MESSAGE 값이 다르니 해시가 다릅니다**. 이 해시가 Deployment의 `configMapKeyRef`에 자동으로 반영돼 있었음을 23-4에서 확인했습니다.

> ⚠️ **함정**: `configMapGenerator`의 해시 때문에, 같은 ConfigMap을 다른 도구(Argo 등)나 명령으로 참조하려면 실제 해시 이름을 알아야 합니다. 고정 이름이 필요하면 `generatorOptions: { disableNameSuffixHash: true }`로 해시를 끌 수 있지만, 그러면 "값 바뀌면 파드 자동 갱신" 이점을 잃습니다. 기본은 켜 두는 게 좋습니다.

---

## 23-6. GitOps — Git이 진실의 원천

지금까지 우리는 로컬에서 `kubectl apply -k`를 손으로 쳤습니다. **GitOps**는 이 "적용" 단계를 사람 손에서 떼어내 **자동화**하는 운영 모델입니다. 핵심 원칙 네 가지:

1. **선언형(declarative)**: 클러스터의 원하는 상태를 전부 YAML로 선언한다. (Kustomize/Helm이 여기에 딱 맞음)
2. **Git이 단일 진실의 원천(single source of truth)**: 그 선언을 Git 저장소에 둔다. "지금 prod에 뭐가 떠 있어야 하는가"의 답은 언제나 Git이다.
3. **자동 동기화(reconcile)**: 컨트롤러가 "Git에 선언된 상태"와 "클러스터의 실제 상태"를 끊임없이 비교하고, 어긋나면(drift) Git 쪽으로 맞춘다.
4. **감사·롤백은 Git으로**: 배포 = `git push`/PR 머지. 롤백 = `git revert`. 누가 언제 무엇을 바꿨는지 커밋 히스토리에 다 남는다.

Kustomize가 GitOps와 잘 맞는 이유: overlay 디렉터리(`overlays/prod`)를 통째로 Git에 넣어 두면, GitOps 컨트롤러가 그 디렉터리를 렌더(`kustomize build`)해서 클러스터에 적용합니다. "prod를 3→5 레플리카로" 같은 변경은 **patch 한 줄 고쳐 PR 머지**면 끝입니다.

### 대표 도구: ArgoCD와 Flux

- **ArgoCD**: 클러스터 안에서 도는 컨트롤러 + 웹 UI. Application이라는 CR로 "이 Git 경로 → 이 클러스터/네임스페이스"를 선언한다. Kustomize/Helm/plain YAML을 모두 렌더한다. Drift와 동기화 상태를 UI로 본다.
- **Flux**: 여러 컨트롤러 조합(GitRepository, Kustomization 등)으로 GitOps를 구성. UI보다 CRD 중심. 이미지 자동 업데이트 같은 기능이 강하다.

둘 다 클러스터 **안에서** Git을 **당겨(pull)** 옵니다. 이것이 배포 모델의 갈림길입니다.

### pull vs push 배포 모델

| | **Pull (GitOps 방식)** | **Push (전통 CI 방식)** |
|---|---|---|
| 누가 적용하나 | 클러스터 안 컨트롤러(ArgoCD/Flux) | 클러스터 밖 CI 파이프라인(Jenkins/GitHub Actions) |
| 방향 | 클러스터가 Git을 **당김** | CI가 클러스터로 `kubectl apply`를 **밀어넣음** |
| 자격증명 | 클러스터가 Git read 권한만 | CI가 클러스터 **admin 자격증명 보유**(외부 유출 위험) |
| Drift 교정 | 자동(계속 reconcile) | 없음(다음 배포까지 어긋난 채) |
| 감사 | Git 히스토리 = 배포 이력 | CI 로그에 흩어짐 |
| 예 | ArgoCD, Flux | `kubectl apply` 를 도는 CI 잡 |

> 💡 **팁**: GitOps는 "kubectl을 안 쓴다"가 아니라 "kubectl apply를 **사람 대신 컨트롤러가**, **Git을 기준으로** 친다"입니다. 이 스텝에서 배운 `kubectl kustomize`(렌더)와 `apply -k`(적용)가 바로 ArgoCD/Flux가 내부에서 하는 일입니다. Kustomize를 이해했다면 GitOps의 절반은 이미 안 셈입니다.

---

## 정리

| 개념 | 한 줄 요약 | 대표 명령/필드 |
|---|---|---|
| Kustomize | 템플릿 없는 선언형 커스터마이즈(kubectl 내장) | `kubectl -k`, `kustomization.yaml` |
| base | 환경 공통 원본(네임스페이스 중립) | `resources:` |
| overlay | base 위에 차이만 얹기 | `resources: [../../base]` |
| namespace/namePrefix | 배포 위치·이름 접두사 | `namespace:`, `namePrefix:` |
| labels | 공통 레이블(+셀렉터) | `labels: [{pairs, includeSelectors}]` |
| patches | 부분 매니페스트로 필드 덮기 | `patches:` (strategic merge) |
| configMapGenerator | 내용 해시 붙은 ConfigMap 자동 생성 | `configMapGenerator:`, `behavior: replace` |
| images | 이미지 name/newName/newTag 교체 | `images:` |
| 렌더 미리보기 | 적용 없이 최종 YAML 확인 | `kubectl kustomize <overlay>` |
| GitOps | 선언형 + Git이 진실의 원천 + reconcile | ArgoCD, Flux (pull 모델) |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 24 — 트러블슈팅](../step-24-troubleshooting/README.md)

---

## 실습 파일

이 스텝의 실습은 `manifests/` 아래 **base 1벌 + overlay 2벌** 구조와, 그것을 순서대로 실행하는 `commands.sh` 로 이루어집니다. 먼저 `base/`(deployment.yaml · service.yaml · kustomization.yaml)가 환경 공통 원본을 정의하고, `overlays/dev` 와 `overlays/prod` 가 각각 `resources: [../../base]` 로 그 base 를 끌어와 네임스페이스·접두사·레이블·replicas·ConfigMap 값(그리고 prod 는 이미지까지)만 바꿔 얹습니다. 실행 순서는 **렌더 미리보기(`kubectl kustomize`) → 네임스페이스 생성 → `kubectl apply -k` → 결과 비교 → 네임스페이스 삭제**이며, `commands.sh` 가 이 흐름을 그대로 담고 있습니다.

### commands.sh

23-4부터 23-5까지 본문에 나온 명령을 실행 순서대로 모아 둔 스크립트입니다. 한 번에 돌리기보다 **한 줄씩 복사해 실행하며 출력을 관찰**하는 용도로 만들어졌습니다.

- 첫머리의 `kubectl config current-context` 로 컨텍스트가 `kind-learn` 인지 확인하고, `kubectl version | grep Kustomize` 로 kubectl 에 내장된 Kustomize 버전(v5.8.1)을 확인합니다. 별도 kustomize 바이너리 설치가 필요 없다는 23-1의 주장을 눈으로 확인하는 대목입니다.
- `kubectl kustomize manifests/overlays/dev|prod` 는 **클러스터에 아무것도 보내지 않고 렌더만** 합니다. apply 전에 최종 YAML 을 검증하는 습관을 들이기 위한 단계입니다.
- `kubectl create ns step23-dev` / `step23-prod` 를 **apply 보다 먼저** 실행합니다. 주석에도 적혀 있듯 overlay 의 `namespace:` 필드는 `metadata.namespace` 를 채워 줄 뿐 네임스페이스를 만들어 주지 않기 때문에, 이 순서를 어기면 `apply -k` 가 "namespaces not found" 로 실패합니다.
- `kubectl wait --for=condition=Available deploy --all --timeout=120s` 로 두 네임스페이스의 Deployment 가 준비될 때까지 기다린 뒤 비교 명령으로 넘어갑니다. prod 이미지(`paulbouwer/hello-kubernetes`)를 처음 당겨오는 환경이라면 이 대기가 실제로 쓰입니다.
- 비교 구간에서는 `-o wide` 로 이미지·셀렉터 차이를, `-l course=k8s-learn` + `custom-columns` 로 두 네임스페이스를 가로질러 replicas/이미지를, `kubectl get cm` 으로 해시 suffix 차이를 봅니다. 마지막 `jsonpath='{.spec.template.spec.containers[0].env}'` 는 base 에 `web-config` 라고 적었던 `configMapKeyRef.name` 이 **해시 이름으로 자동 치환**됐음을 확정하는 결정적 확인입니다.
- ⚠️ 맨 끝 `kubectl delete ns step23-dev step23-prod` 는 **네임스페이스를 통째로 지우는 파괴적 명령**입니다. 실습을 이어서 할 생각이라면 이 줄은 실행하지 마세요.

```bash file="./commands.sh"
```

### manifests/base/deployment.yaml

base 의 Deployment 원본입니다. 23-2에서 설명한 "네임스페이스 중립" 원칙에 따라 `metadata.namespace` 가 **없고**, 이름도 접두사 없는 `web` 입니다. 배포 위치와 이름은 전적으로 overlay 가 결정합니다.

- `replicas: 1` 이 기본값이며, dev overlay 는 이 값을 그대로(1) 재확인하고 prod overlay 는 patch 로 3 으로 올립니다.
- `image: nginx:1.27-alpine` 이 base 이미지입니다. prod 의 `images` 트랜스포머가 `name: nginx` 를 키로 이 이미지를 찾아 교체하므로, 여기 적힌 이름이 곧 매칭 키가 됩니다.
- `env` 의 `MESSAGE` 는 `configMapKeyRef.name: web-config` 를 참조합니다. 그런데 실제 클러스터에 생기는 ConfigMap 이름은 `dev-web-config-gk898kht9m` 처럼 해시가 붙습니다. 파일 안 주석대로 kustomize 가 이 참조를 **자동으로 해시 이름으로 다시 씁니다** — 사람이 손으로 맞출 필요가 없습니다.
- `selector.matchLabels: {app: web}` 와 파드 템플릿 레이블 `app: web` 만 적혀 있지만, `kustomization.yaml` 의 `labels` + `includeSelectors: true` 덕분에 렌더 후에는 `course: k8s-learn`, `env: dev|prod` 까지 셀렉터에 합쳐집니다.

```yaml file="./manifests/base/deployment.yaml"
```

### manifests/base/service.yaml

base 의 Service 원본입니다. 파일 자체는 아주 평범한 `ClusterIP` Service 이지만, **여기에 손을 대지 않고도 dev/prod 가 서로 격리된다**는 점이 학습 포인트입니다.

- `selector: {app: web}` 만 선언돼 있는데, base 의 `labels`(app, course) 와 overlay 의 `labels`(env) 가 `includeSelectors: true` 로 셀렉터에 주입되어 렌더 결과는 `app=web,course=k8s-learn,env=dev` 가 됩니다. 그래서 dev Service 가 prod 파드를 잡는 사고가 나지 않습니다(challenge.md 과제 4).
- `port: 80` / `targetPort: 80` 은 base 이미지인 nginx(80번 포트 리슨)에 맞춘 값입니다. ⚠️ 여기서 짚고 넘어갈 함정이 있습니다. prod 의 `images` 트랜스포머는 **이미지 이름만 바꿀 뿐 포트를 따라 바꿔 주지 않습니다.** `containerPort: 80` 과 `targetPort: 80` 은 base 값 그대로 남으므로, 교체한 이미지가 다른 포트를 리슨한다면(`paulbouwer/hello-kubernetes` 는 기본이 8080입니다) prod Service 로는 실제 응답을 받지 못합니다. 파드에 readinessProbe 가 없어서 `Running` 으로만 보일 뿐입니다. 이 스텝의 목표는 kustomize 의 렌더 동작 실증이라 그대로 두지만, **이미지 교체 시 포트도 같이 맞춰야 한다**는 것을 기억하세요.
- 이름은 `web` 이지만 overlay 의 `namePrefix` 로 `dev-web` / `prod-web` 이 됩니다. Service 는 이름이 아니라 **셀렉터로** 파드를 찾으므로, 이름이 바뀌어도 Deployment 와의 연결은 셀렉터 레이블(`app`/`course`/`env`)이 그대로 유지해 줍니다.

```yaml file="./manifests/base/service.yaml"
```

### manifests/base/kustomization.yaml

base 를 하나의 단위로 묶는 진입점입니다. overlay 가 `resources: [../../base]` 로 가리키는 대상이 바로 이 파일입니다.

- `resources: [deployment.yaml, service.yaml]` 가 base 에 포함될 매니페스트 목록입니다. 여기에 없는 파일은 렌더 결과에 나오지 않습니다.
- `labels:` 블록의 `pairs: {app: web, course: k8s-learn}` 는 모든 리소스에 공통 레이블을 심습니다. `course: k8s-learn` 은 나중에 `kubectl get deploy -A -l course=k8s-learn` 으로 두 네임스페이스를 한 번에 조회하는 데 쓰입니다.
- `includeSelectors: true` 가 핵심입니다. 이걸 켜야 Deployment 의 `selector.matchLabels` 와 Service 의 `selector` 에도 레이블이 들어갑니다(구 `commonLabels` 와 같은 동작). 끄면 레이블은 붙지만 셀렉터는 `app=web` 그대로라 환경 간 파드가 섞일 수 있습니다.
- `configMapGenerator` 의 `MESSAGE=Hello from BASE (overlay 가 덮어씀)` 는 **일부러 눈에 띄게 쓴 자리표시자**입니다. 렌더 결과에 이 문구가 보인다면 overlay 의 `behavior: replace` 가 동작하지 않은 것이므로, 값이 제대로 덮였는지 확인하는 리트머스 역할을 합니다.

```yaml file="./manifests/base/kustomization.yaml"
```

### manifests/overlays/dev/kustomization.yaml

dev 환경 overlay 의 진입점입니다. `kubectl apply -k manifests/overlays/dev` 가 읽는 파일이 바로 이것입니다.

- `namespace: step23-dev` 는 렌더되는 모든 리소스의 `metadata.namespace` 를 채웁니다. 다시 강조하면 **네임스페이스를 만들지는 않으므로** 사전에 `kubectl create ns step23-dev` 가 필요합니다.
- `namePrefix: dev-` 로 `web` → `dev-web`, `web-config` → `dev-web-config-...` 가 됩니다. 이름이 바뀌어도 Service 셀렉터나 `configMapKeyRef` 참조는 kustomize 가 알아서 따라갑니다.
- `labels: [{pairs: {env: dev}, includeSelectors: true}]` 로 `env=dev` 를 셀렉터까지 심어 prod 와 격리합니다.
- `patches: [{path: patch-deployment.yaml}]` 로 replicas 와 `TIER` env 를 얹습니다.
- `configMapGenerator` 의 `behavior: replace` 는 base 가 만든 같은 이름(`web-config`)의 ConfigMap 정의를 **통째로 교체**한다는 뜻입니다. `merge` 였다면 base 의 다른 키는 남기고 겹치는 키만 덮어씁니다 — 지금은 키가 `MESSAGE` 하나뿐이라 두 방식의 결과가 같지만, "이 overlay 의 값이 base 를 대신한다"는 의도를 분명히 하려고 `replace` 를 씁니다. (`behavior` 를 아예 빼면 이름 충돌로 렌더가 실패합니다.) 값이 prod 와 다르므로 해시 suffix 도 `gk898kht9m` 처럼 prod(`f8fc64kgtm`)와 달라집니다.
- 마지막 주석대로 dev 는 `images` 오버라이드가 **없어서** base 의 `nginx:1.27-alpine` 을 그대로 씁니다. prod 와의 차이를 만드는 지점입니다.

```yaml file="./manifests/overlays/dev/kustomization.yaml"
```

### manifests/overlays/dev/patch-deployment.yaml

dev 의 전략적 병합(strategic merge) 패치입니다. **완전한 매니페스트가 아니라 바꿀 필드만 담은 부분 매니페스트**라는 점이 포인트입니다.

- `metadata.name: web` 은 **패치 대상을 찾는 키**입니다. `namePrefix` 가 적용되기 전의 base 이름을 써야 매칭됩니다(`dev-web` 이라고 쓰면 대상을 못 찾습니다).
- `spec.replicas: 1` 은 dev 를 1 레플리카로 고정합니다. base 와 값이 같아 겉보기엔 무의미하지만, "환경별 레플리카는 patch 에서 관리한다"는 규칙을 명시적으로 드러내기 위해 남겨 둔 선언입니다.
- 컨테이너 리스트에서 `- name: web` 이 **병합 키**입니다. 이 키로 base 의 같은 컨테이너를 찾아 `env` 에 `TIER=development` 를 **추가**합니다. 리스트를 통째로 갈아끼우지 않기 때문에 base 의 `MESSAGE` env 와 `image`, `ports` 는 그대로 살아남습니다.
- 만약 `name: web` 을 빠뜨리거나 다른 값으로 적으면 병합 키가 어긋나 컨테이너가 하나 더 추가되거나 렌더가 실패합니다.

```yaml file="./manifests/overlays/dev/patch-deployment.yaml"
```

### manifests/overlays/prod/kustomization.yaml

prod overlay 의 진입점입니다. dev 와 **구조는 완전히 같고 값만 다르다**는 것을 나란히 비교하며 읽어 보세요. 이것이 "같은 base, 다른 배포"의 실체입니다.

- `namespace: step23-prod`, `namePrefix: prod-`, `labels: [{pairs: {env: prod}}]` — dev 와 대칭입니다.
- `configMapGenerator` 의 `MESSAGE=Hello from PROD (step23-prod)` 값이 dev 와 다르므로 해시가 `f8fc64kgtm` 으로 갈립니다. 23-5의 `kubectl get cm` 비교가 바로 이 차이를 보여 줍니다.
- dev 에 없는 항목이 `images:` 트랜스포머입니다. `name: nginx` 로 base 이미지(`nginx:1.27-alpine`)를 찾아 `newName: paulbouwer/hello-kubernetes` + `newTag: "1.10.1"` 로 교체합니다. `name` 은 **태그를 뺀 이미지 이름**으로 매칭하므로 `nginx:1.27-alpine` 이 아니라 `nginx` 라고 써야 합니다.
- `newTag: "1.10.1"` 의 따옴표에 주의하세요. `1.10.1` 은 점이 둘이라 따옴표가 없어도 문자열로 읽히지만, `1.10` 이나 `1.0` 처럼 점이 하나인 태그는 YAML 이 **숫자(float)로 해석해 `1.1` 같은 엉뚱한 태그**가 됩니다. 이미지 태그는 항상 따옴표로 감싸는 습관을 들이세요.
- 파일 주석대로 실무에서는 보통 같은 이미지의 태그만 바꾸지만, 여기서는 `newName` 과 `newTag` 를 **둘 다 실증**하려고 다른 이미지를 골랐습니다. 두 이미지 모두 kind 노드에 캐시돼 있다는 전제이므로, 캐시가 없는 환경이라면 prod 파드가 `ImagePullBackOff` 로 잠시 멈출 수 있습니다.

```yaml file="./manifests/overlays/prod/kustomization.yaml"
```

### manifests/overlays/prod/patch-deployment.yaml

prod 의 전략적 병합 패치입니다. dev 것과 **딱 두 값만 다릅니다** — 이 파일 하나가 "환경 차이는 patch 몇 줄로 끝난다"는 이 스텝의 결론을 요약합니다.

- `spec.replicas: 3` 이 prod 를 3 레플리카로 만듭니다. 23-5의 `kubectl get all -n step23-prod` 에서 파드 3개가 뜨는 근거이고, challenge.md 과제 2에서 GitOps 식으로 5 로 올릴 때 고치는 곳도 정확히 이 한 줄입니다.
- `- name: web` 병합 키로 base 컨테이너를 찾아 `TIER=production` env 를 추가합니다. 렌더 결과에서 `TIER` 다음에 base 의 `MESSAGE` 가 그대로 남아 있는 것이 전략적 병합이 리스트를 덮어쓰지 않는다는 증거입니다.
- `image`, `ports`, `MESSAGE` 는 이 파일에 아예 없습니다. **적지 않은 것은 base 값이 그대로 유지**되고, 이미지는 patch 가 아니라 `kustomization.yaml` 의 `images` 트랜스포머가 따로 바꿉니다. 같은 목적을 두 가지 방식으로 달성할 수 있다는 점(patch 로도 이미지를 바꿀 수 있지만 `images` 가 더 선언적)을 함께 기억해 두세요.
- ⚠️ 이 값을 `kubectl scale` 로 바꾸면 매니페스트와 클러스터가 어긋나는(drift) 상태가 됩니다. 반드시 이 파일을 고치고 `kubectl apply -k` 하는 방식으로 바꾸세요 — GitOps 의 기본 규율입니다.

```yaml file="./manifests/overlays/prod/patch-deployment.yaml"
```
