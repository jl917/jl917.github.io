# Step 22 — CRD와 오퍼레이터

> **학습 목표**
> - CRD(CustomResourceDefinition)로 Kubernetes API를 확장해 나만의 리소스 종류를 추가한다
> - group/versions/scope/names 와 openAPIV3Schema 스키마 검증(required, type, enum, min/max)을 작성한다
> - additionalPrinterColumns, shortNames, categories 로 `kubectl` 이 CR을 네이티브하게 다루게 한다
> - 오퍼레이터 패턴(감시 → 조정 루프)의 개념과, CRD는 "데이터" 오퍼레이터는 "로직"이라는 역할 분리를 이해한다
> - CRD와 ConfigMap 중 언제 무엇을 쓸지 판단한다
>
> **선행 스텝**: [Step 04 — 레이블·셀렉터·네임스페이스](../step-04-labels-namespaces/README.md)
> **예상 소요**: 45분

---

## 22-1. 왜 API를 확장하는가

지금까지 다룬 Pod, Deployment, Service, ConfigMap 은 모두 Kubernetes가 **기본 제공하는 리소스 종류(kind)** 입니다. 그런데 실무에서는 "우리 도메인 언어"로 리소스를 다루고 싶을 때가 많습니다. 예컨대 "데이터베이스 클러스터 하나", "카프카 토픽 하나", "TLS 인증서 하나" 를 각각 Deployment·Service·Secret 수십 줄로 풀어 쓰는 대신, `kind: PostgresCluster` 처럼 **한 개의 고수준 오브젝트**로 선언하고 싶은 것이죠.

Kubernetes는 이를 위해 **API 자체를 확장**하는 길을 열어 둡니다. 그 핵심이 **CRD(CustomResourceDefinition)** 입니다. CRD를 클러스터에 등록하면, 그 순간부터 API 서버는 `WebApp` 같은 **새 리소스 종류를 진짜로 이해**합니다. `kubectl get webapps`, `kubectl apply`, RBAC, `-o yaml`, `describe`, 스키마 검증 — 기본 리소스가 누리던 모든 것을 커스텀 리소스도 그대로 누립니다.

```
                기본 제공 kind             CRD로 추가한 kind
   API 서버 ┌───────────────────┐   ┌──────────────────────────┐
            │ Pod / Deployment  │   │ WebApp / PostgresCluster │
            │ Service / Secret  │ + │ Certificate / KafkaTopic │
            └───────────────────┘   └──────────────────────────┘
                     └──────── 같은 kubectl / API / etcd ────────┘
```

이 스텝에서는 `WebApp` 이라는 커스텀 리소스를 직접 정의하고, 실제로 만들고, 스키마 검증이 잘못된 리소스를 **거부하는 것**까지 클러스터에서 확인합니다.

> 💡 **팁**: CRD는 "데이터 모델(스키마)"만 추가합니다. 그 데이터를 보고 실제로 파드를 띄우거나 인증서를 발급하는 **로직**은 별도의 **오퍼레이터(컨트롤러)** 가 담당합니다. 이 둘의 분리가 이 스텝 전체의 핵심입니다(22-5 참고).

---

## 22-2. CRD 작성 — group/versions/scope/names/schema

CRD는 그 자체가 하나의 오브젝트(`kind: CustomResourceDefinition`)입니다. 구조를 뜯어봅시다.

```yaml
# manifests/webapp-crd.yaml (발췌)
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: webapps.learn.example.com     # 반드시 <plural>.<group> 형식
spec:
  group: learn.example.com            # API 그룹 (도메인처럼 고유하게)
  scope: Namespaced                   # 네임스페이스 범위 (vs Cluster)
  names:
    plural: webapps                   # kubectl get webapps
    singular: webapp                  # kubectl get webapp
    kind: WebApp                      # YAML의 kind:
    shortNames: [wa]                  # kubectl get wa
    categories: [all, learn]          # kubectl get all / kubectl get learn
  versions:
    - name: v1alpha1
      served: true                    # API로 제공할지
      storage: true                   # etcd 저장 버전 (여러 버전 중 딱 하나만 true)
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              required: [image, replicas]      # 필수 필드
              properties:
                image:    { type: string }
                replicas: { type: integer, minimum: 1, maximum: 5 }
                greeting: { type: string, default: "Hello" }
                tier:     { type: string, enum: [frontend, backend, cache], default: frontend }
```

각 필드의 의미:

| 필드 | 역할 |
|---|---|
| `group` | API 그룹. 충돌을 피하려 소유 도메인(`learn.example.com`)처럼 짓는다 |
| `scope` | `Namespaced`(네임스페이스에 속함) 또는 `Cluster`(클러스터 전역) |
| `names.plural/singular/kind` | `kubectl get`·YAML `kind` 에서 쓰는 이름들 |
| `names.shortNames` | 축약형(`wa`) |
| `names.categories` | `kubectl get all`, `kubectl get learn` 같은 묶음 조회에 포함 |
| `versions[].served/storage` | 제공 여부 / etcd 저장 버전(정확히 하나만 `storage: true`) |
| `openAPIV3Schema` | 스펙 스키마. 타입·필수·enum·min/max 로 검증 |

> ⚠️ **함정**: `metadata.name` 은 반드시 `<plural>.<group>` 여야 합니다. 여기서는 `webapps.learn.example.com`. 오타로 `webapp.learn.example.com`(단수) 로 쓰면 apply 자체가 거부됩니다. 또한 여러 버전을 둘 때 `storage: true` 는 **정확히 하나**만 가능합니다.

CRD를 적용하고, API 서버가 이 종류를 "확립(Established)" 했는지 기다립니다.

```bash
kubectl apply -f manifests/webapp-crd.yaml
kubectl wait --for=condition=Established crd/webapps.learn.example.com --timeout=30s
kubectl get crd webapps.learn.example.com
```

**실행 결과**
```
customresourcedefinition.apiextensions.k8s.io/webapps.learn.example.com created
customresourcedefinition.apiextensions.k8s.io/webapps.learn.example.com condition met
NAME                        CREATED AT
webapps.learn.example.com   2026-07-13T02:55:29Z
```

이제 클러스터는 `WebApp` 을 진짜 리소스 종류로 인식합니다. `api-resources` 로 확인됩니다.

```bash
kubectl api-resources --api-group=learn.example.com
```
```
NAME      SHORTNAMES   APIVERSION                   NAMESPACED   KIND
webapps   wa           learn.example.com/v1alpha1   true         WebApp
```

> ⚠️ **함정**: CRD는 **클러스터 범위(cluster-scoped)** 오브젝트입니다. 네임스페이스에 속하지 않으므로 `-n step22` 로 격리되지 않고, 클러스터 전체에 영향을 줍니다. 그래서 이 코스에서는 학습 후 반드시 `kubectl delete crd` 로 지웁니다(정리 절 참고).

---

## 22-3. 커스텀 리소스(CR) 생성·조회

CRD가 "종류 정의"라면, **CR(Custom Resource)** 은 그 종류의 "실제 인스턴스"입니다. 우리 스키마를 만족하는 CR 두 개를 만듭니다. `order-api` 는 `greeting` 을 일부러 비워, 스키마의 `default: "Hello"` 가 채워지는지 봅니다.

```yaml
# manifests/webapps.yaml
apiVersion: learn.example.com/v1alpha1
kind: WebApp
metadata:
  name: shop-frontend
  namespace: step22
spec:
  image: nginx:1.27-alpine
  replicas: 3
  greeting: "안녕하세요"
  tier: frontend
---
apiVersion: learn.example.com/v1alpha1
kind: WebApp
metadata:
  name: order-api
  namespace: step22
spec:
  image: hashicorp/http-echo:1.0
  replicas: 2
  tier: backend         # greeting 미지정 → default "Hello"
```

```bash
kubectl apply -f manifests/webapps.yaml
kubectl get webapps -n step22
```

**실행 결과** — `additionalPrinterColumns` 덕분에 Image/Replicas/Tier 컬럼이 자동으로 붙습니다.
```
NAME            IMAGE                     REPLICAS   TIER       AGE
order-api       hashicorp/http-echo:1.0   2          backend    0s
shop-frontend   nginx:1.27-alpine         3          frontend   0s
```

**shortNames** 와 **categories** 로도 똑같이 조회됩니다.

```bash
kubectl get wa -n step22        # shortName
kubectl get learn -n step22     # category
```
```
NAME            IMAGE                     REPLICAS   TIER       AGE
order-api       hashicorp/http-echo:1.0   2          backend    0s
shop-frontend   nginx:1.27-alpine         3          frontend   0s
```

스키마의 `default` 가 실제로 채워졌는지 `-o yaml` 로 확인합니다. `order-api` 는 `greeting` 을 안 줬는데도 `Hello` 가 들어가 있습니다.

```bash
kubectl get webapp order-api -n step22 -o yaml | grep -A12 '^spec:'
```
```
spec:
  greeting: Hello
  image: hashicorp/http-echo:1.0
  replicas: 2
  tier: backend
```

`describe` 도 기본 리소스처럼 동작합니다.

```bash
kubectl describe webapp shop-frontend -n step22
```
```
Name:         shop-frontend
Namespace:    step22
API Version:  learn.example.com/v1alpha1
Kind:         WebApp
Metadata:
  Creation Timestamp:  2026-07-13T02:55:39Z
  Generation:          1
  ...
Spec:
  Greeting:  안녕하세요
  Image:     nginx:1.27-alpine
  Replicas:  3
  Tier:      frontend
Events:      <none>
```

> 💡 **팁**: `additionalPrinterColumns` 의 `jsonPath` 로 스펙 내부 값을 컬럼으로 끌어올 수 있습니다. 오퍼레이터를 붙이면 흔히 `.status.phase` 나 준비된 replica 수를 컬럼으로 노출해, `kubectl get` 한 줄로 상태를 읽게 만듭니다.

---

## 22-4. 스키마 검증 — 잘못된 CR은 거부된다

CRD의 진짜 가치는 **API 서버가 저장 전에 스키마로 검증**한다는 점입니다. ConfigMap의 값은 그냥 문자열이라 무엇이든 들어가지만, CR은 타입·필수·범위·enum 을 어기면 **거부**됩니다.

**케이스 1 — `replicas` 가 최대값(5) 초과**

```yaml
# manifests/webapp-bad-replicas.yaml (발췌)
spec:
  image: nginx:1.27-alpine
  replicas: 99          # maximum: 5 위반
```
```bash
kubectl apply -f manifests/webapp-bad-replicas.yaml
```
```
The WebApp "too-big" is invalid: spec.replicas: Invalid value: 99: spec.replicas in body should be less than or equal to 5
```

**케이스 2 — 필수 필드 `image` 누락 + `tier` enum 위반**

```yaml
# manifests/webapp-bad-missing.yaml (발췌)
spec:
  replicas: 2
  tier: database        # enum [frontend, backend, cache] 위반, image 누락
```
```bash
kubectl apply -f manifests/webapp-bad-missing.yaml
```
```
The WebApp "broken" is invalid: 
* spec.tier: Unsupported value: "database": supported values: "frontend", "backend", "cache"
* spec.image: Required value
```

두 리소스 모두 etcd에 저장조차 되지 않았습니다. 유효한 CR 2개만 남아 있습니다.

```bash
kubectl get webapps -n step22
```
```
NAME            IMAGE                     REPLICAS   TIER       AGE
order-api       hashicorp/http-echo:1.0   2          backend    15s
shop-frontend   nginx:1.27-alpine         3          frontend   15s
```

> 💡 **팁**: 이 검증은 **클라이언트가 아니라 API 서버**가 수행합니다. 즉 `kubectl` 을 거치지 않고 CI 파이프라인이나 다른 클라이언트가 API를 직접 호출해도 똑같이 막힙니다. 잘못된 설정이 클러스터에 스며드는 것을 API 계층에서 원천 차단하는 셈입니다.

---

## 22-5. 오퍼레이터 패턴 — 데이터(CRD)와 로직(컨트롤러)

여기서 결정적인 사실 하나. 우리는 `shop-frontend` 가 `replicas: 3` 을 선언했지만, **파드는 하나도 만들어지지 않았습니다.**

```bash
kubectl get pods -n step22
kubectl get deploy -n step22
```
```
No resources found in step22 namespace.
No resources found in step22 namespace.
```

왜냐하면 **CRD는 데이터를 저장할 뿐, 아무런 동작도 하지 않기 때문**입니다. `WebApp` 은 지금 그저 etcd에 예쁘게 검증되어 저장된 JSON 문서일 뿐입니다. 이 데이터를 읽고 "그럼 nginx 파드 3개를 띄우자" 라고 **실제로 행동하는 주체**가 필요합니다. 그게 바로 **오퍼레이터(Operator) = 커스텀 컨트롤러**입니다.

오퍼레이터는 **조정 루프(reconcile loop)** 로 동작합니다.

```
        ┌──────────────────────────────────────────────┐
        │                                                │
        │   1. WATCH        WebApp 리소스 변화를 감시     │
        │      (감시)        (생성/수정/삭제 이벤트)       │
        │        │                                       │
        │        ▼                                       │
        │   2. DIFF         원하는 상태(spec) vs           │
        │      (비교)        실제 상태(cluster) 비교        │
        │        │                                       │
        │        ▼                                       │
        │   3. ACT          차이를 없애는 조치            │
        │      (조정)        (Deployment 생성/수정 등)     │
        │        │                                       │
        │        ▼                                       │
        │   4. STATUS       .status 에 결과 기록          │
        │        │                                       │
        └────────┴───────────── 반복 ────────────────────┘
```

핵심은 **선언적**이라는 것입니다. 사용자는 "무엇을 원하는가(spec)"만 적고, 오퍼레이터가 "어떻게 그 상태로 만들지"를 끊임없이 조정합니다. 누가 파드를 지워도, 노드가 죽어도, 오퍼레이터가 다시 원하는 상태로 되돌립니다. 사실 Deployment 컨트롤러가 ReplicaSet을, ReplicaSet 컨트롤러가 Pod를 조정하는 것과 **완전히 같은 원리**입니다. 오퍼레이터는 이 내장 컨트롤러 패턴을 "우리 도메인 지식(예: DB 백업, 페일오버)"으로 확장한 것입니다.

> ⚠️ **함정**: **CRD만 만들고 오퍼레이터를 안 붙이면 아무 일도 일어나지 않습니다.** 방금 본 것처럼 파드가 안 생깁니다. 이 스텝에서 실제 오퍼레이터를 돌리지 않은 이유는, 컨트롤러는 별도의 코드/이미지(보통 Go + controller-runtime)로 빌드해 배포해야 하기 때문입니다. 여기서는 "CRD = 데이터, 오퍼레이터 = 로직" 이라는 **역할 분리**를 개념적으로 확실히 잡는 데 집중합니다.

정리하면:

| 구성요소 | 역할 | 비유 |
|---|---|---|
| **CRD** | 새 리소스 종류의 **스키마**를 정의 (데이터 모델) | 데이터베이스 테이블 정의 |
| **CR** | 그 종류의 실제 **인스턴스** (원하는 상태) | 테이블의 한 행 |
| **오퍼레이터** | CR을 감시하고 원하는 상태로 **조정**하는 로직 | 그 행을 보고 일하는 애플리케이션 |

---

## 22-6. 세상의 유명한 오퍼레이터들

오퍼레이터 패턴은 이제 클라우드 네이티브 생태계의 표준입니다. 실무에서 만날 대표 주자들:

| 오퍼레이터 | 추가하는 CRD (예) | 하는 일 |
|---|---|---|
| **cert-manager** | `Certificate`, `Issuer`, `ClusterIssuer` | TLS 인증서 자동 발급·갱신(Let's Encrypt 등) |
| **Prometheus Operator** | `Prometheus`, `ServiceMonitor`, `PrometheusRule`, `Alertmanager` | 모니터링 스택을 선언적으로 배포·설정 |
| **ArgoCD** | `Application`, `AppProject` | GitOps — Git 저장소 상태를 클러스터에 지속 동기화 |
| **Strimzi** | `Kafka`, `KafkaTopic`, `KafkaUser` | Kafka 클러스터·토픽·유저를 K8s 리소스로 운영 |
| **CloudNativePG** | `Cluster`, `Backup`, `ScheduledBackup` | PostgreSQL 클러스터 프로비저닝·백업·페일오버 |

이들을 설치하면(대개 Helm) CRD가 먼저 등록되고, 그다음 컨트롤러 Deployment가 뜹니다. 그 뒤로 여러분은 `kind: Certificate` YAML 한 장을 apply 하는 것만으로 "인증서 발급·갱신"이라는 복잡한 절차를 위임할 수 있습니다. 이것이 오퍼레이터의 힘입니다: **운영 지식을 코드로 캡슐화**.

> 💡 **팁**: 새 오퍼레이터를 도입할 때는 `kubectl get crd | grep <group>` 으로 어떤 CRD가 등록됐는지, `kubectl api-resources` 로 어떤 새 kind를 쓸 수 있는지부터 확인하세요. 문서보다 클러스터가 정확합니다.

---

## 22-7. CRD vs ConfigMap — 언제 무엇을

"설정을 담는다"는 점에서 CRD와 ConfigMap은 겹쳐 보입니다. 하지만 성격이 다릅니다.

| 기준 | **ConfigMap** | **CRD + CR** |
|---|---|---|
| 스키마 검증 | 없음(값은 그냥 문자열) | **있음**(타입·required·enum·min/max) |
| kubectl 네이티브 | 키/값 덩어리로만 | **전용 kind**로 get/describe/컬럼 표시 |
| RBAC 세분화 | `configmaps` 리소스 통째로 | **kind 단위**로 권한 분리 가능 |
| 감시/조정 | 없음(누가 읽어줘야 함) | 오퍼레이터가 **감시·조정** |
| 버전 관리 | 없음 | `versions` + 변환으로 **API 버전 진화** |
| 만드는 비용 | 즉시(빌트인) | CRD 정의(+ 대개 오퍼레이터) 필요 |
| 적합한 경우 | 앱의 단순 키/값 설정 | 도메인 오브젝트 + 자동화 로직 |

판단 기준을 한 줄로:

- **그냥 앱에 값 몇 개 주입** → ConfigMap. 오버엔지니어링 금물.
- **엄격한 스키마 + 전용 API + 그 리소스를 보고 자동으로 동작하는 로직**이 필요 → CRD + 오퍼레이터.

> ⚠️ **함정**: "설정에 검증을 넣고 싶다"는 이유만으로 성급히 CRD를 만들지 마세요. CRD는 클러스터 범위 자산이고, 유지보수(스키마 버전 진화, 오퍼레이터 운영)가 따라옵니다. 오퍼레이터로 자동화할 로직이 없다면 대개 ConfigMap + 애플리케이션 측 검증으로 충분합니다.

---

## 정리

| 개념 | 한 줄 요약 | 대표 명령 |
|---|---|---|
| CRD | Kubernetes API에 새 리소스 종류를 추가하는 정의 | `kubectl get crd`, `kubectl apply -f *-crd.yaml` |
| CR | CRD가 정의한 종류의 실제 인스턴스 | `kubectl get webapps -n step22` |
| 스키마 검증 | required/type/enum/min/max 를 API 서버가 강제 | 잘못된 CR은 apply 시 거부 |
| printer columns | 스펙 값을 `kubectl get` 컬럼으로 노출 | `additionalPrinterColumns` |
| shortNames/categories | 축약·묶음 조회 | `kubectl get wa`, `kubectl get learn` |
| 오퍼레이터 | CR을 감시→조정하는 커스텀 컨트롤러(로직) | (별도 코드/이미지로 배포) |
| CRD vs ConfigMap | 검증·네이티브·자동화 필요 시 CRD, 아니면 ConfigMap | — |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 23 — Kustomize와 GitOps](../step-23-kustomize-gitops/README.md)

---

## 실습 파일

이 스텝의 실습은 네 개의 매니페스트와 한 개의 명령 스크립트로 구성됩니다. 먼저 `manifests/webapp-crd.yaml` 로 `WebApp` 이라는 새 리소스 종류를 API 서버에 등록하고, `manifests/webapps.yaml` 로 스키마를 만족하는 정상 CR 2개를 만들어 봅니다. 그다음 일부러 스키마를 어긴 `manifests/webapp-bad-replicas.yaml` 과 `manifests/webapp-bad-missing.yaml` 을 apply 해 **API 서버가 저장 전에 거부하는 장면**을 눈으로 확인합니다. `commands.sh` 는 이 전체 흐름(네임스페이스 생성 → CRD → CR → 검증 실패 → 정리)을 순서대로 모아 둔 스크립트입니다.

### commands.sh

강의 본문 22-2부터 정리 절까지 등장하는 `kubectl` 명령을 실행 순서대로 담은 참고 스크립트입니다. 통째로 실행하기보다 **한 줄씩 복사해 붙여넣으며 출력을 관찰**하는 용도로 쓰세요.

- 맨 위 `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS(Homebrew) 환경에서 `kubectl` 을 찾기 위한 줄이고, `kubectl config current-context` 로 실습 클러스터가 `kind-learn` 인지 먼저 확인합니다.
- `kubectl create namespace step22` 로 실습 네임스페이스를 만들고 `course=k8s-learn step=22` 레이블을 붙입니다. CR(`WebApp`)은 `scope: Namespaced` 라서 이 네임스페이스에 담기지만, **CRD 자체는 클러스터 범위**라 여기에 격리되지 않습니다.
- `kubectl wait --for=condition=Established crd/webapps.learn.example.com --timeout=30s` 가 중요합니다. CRD를 apply 한 직후에는 API 서버가 아직 새 종류를 서빙할 준비가 안 됐을 수 있어, `Established` 조건을 기다린 뒤에야 CR을 apply 해야 안전합니다. 이걸 빼면 곧바로 이어지는 `kubectl apply -f manifests/webapps.yaml` 이 "no matches for kind WebApp" 으로 실패할 수 있습니다.
- 22-4 구간의 두 `apply` 는 **에러가 나는 것이 정상**입니다. 실패를 보는 것이 목적이므로 스크립트가 중간에 멈춘 것처럼 보여도 그대로 진행하세요.
- 마지막 정리 구간의 순서(CR 삭제 → `kubectl delete crd` → 네임스페이스 삭제)에 주의하세요. `kubectl delete crd webapps.learn.example.com` 은 **파괴적**입니다. CRD를 지우면 그 종류의 CR이 클러스터 전역에서 함께 사라집니다.

```bash file="./commands.sh"
```

### manifests/webapp-crd.yaml

22-2에서 적용하는 이 스텝의 핵심 파일로, `kind: CustomResourceDefinition` 하나가 `WebApp` 이라는 새 리소스 종류를 통째로 정의합니다.

- `metadata.name: webapps.learn.example.com` 은 `<plural>.<group>` 규칙을 그대로 지킨 이름입니다. 아래 `names.plural: webapps` 와 `group: learn.example.com` 중 하나라도 어긋나면 apply가 거부됩니다.
- `names` 블록이 `kubectl` 사용성을 만듭니다. `shortNames: [wa]` 덕분에 `kubectl get wa` 가 되고, `categories: [all, learn]` 덕분에 `kubectl get learn` 과 `kubectl get all` 에 함께 잡힙니다.
- `versions[0]` 의 `served: true` / `storage: true` 조합은 "v1alpha1을 API로 제공하고, etcd에도 이 버전으로 저장한다"는 뜻입니다. 버전이 여러 개여도 `storage: true` 는 정확히 하나만 허용됩니다(challenge 과제 4가 이걸 재현합니다).
- `openAPIV3Schema` 가 22-4의 검증을 실제로 수행하는 부분입니다. `required: [image, replicas]`, `replicas` 의 `minimum: 1` / `maximum: 5`, `tier` 의 `enum: [frontend, backend, cache]` 가 각각 필수·범위·열거 검증을 담당하고, `greeting` 의 `default: "Hello"` 와 `tier` 의 `default: frontend` 는 값을 안 줬을 때 API 서버가 채워 넣습니다.
- `subresources.status: {}` 는 `/status` 서브리소스를 켜서, 나중에 오퍼레이터가 `.status.availableReplicas` 를 spec과 분리해 갱신할 수 있게 합니다. 지금은 오퍼레이터가 없어 이 필드가 비어 있습니다.
- `additionalPrinterColumns` 의 네 항목이 `kubectl get webapps` 출력의 IMAGE·REPLICAS·TIER·AGE 컬럼을 만듭니다. 각 `jsonPath`(`.spec.image` 등)가 CR 내부 값을 그대로 끌어옵니다.

```yaml file="./manifests/webapp-crd.yaml"
```

### manifests/webapps.yaml

22-3에서 적용하는, **스키마를 만족하는 정상 CR 2개**입니다. CRD가 `Established` 된 뒤에 apply해야 합니다.

- `shop-frontend` 는 `image`, `replicas: 3`, `greeting: "안녕하세요"`, `tier: frontend` 를 모두 명시한 완전한 예입니다. `replicas: 3` 은 스키마의 1~5 범위 안이라 통과합니다.
- `order-api` 는 **일부러 `greeting` 을 비워 둔** 케이스입니다. apply 후 `kubectl get webapp order-api -n step22 -o yaml` 로 보면 `greeting: Hello` 가 채워져 있는데, 이는 사용자가 쓴 값이 아니라 CRD 스키마의 `default: "Hello"` 를 API 서버가 저장 시점에 주입한 결과입니다.
- 두 CR 모두 `namespace: step22` 를 명시하므로 `commands.sh` 의 네임스페이스 생성이 선행돼야 합니다.
- 이 파일을 apply해도 **파드는 한 개도 생기지 않습니다.** 22-5의 핵심 관찰 포인트로, CR은 etcd에 저장된 "원하는 상태" 데이터일 뿐이고 그것을 보고 행동할 오퍼레이터가 아직 없기 때문입니다.

```yaml file="./manifests/webapps.yaml"
```

### manifests/webapp-bad-replicas.yaml

22-4의 **케이스 1 — 의도적으로 깨뜨린 실습 파일**입니다. apply가 성공하면 안 되고, 거부되는 것이 정답입니다.

- `replicas: 99` 가 CRD 스키마의 `maximum: 5` 를 위반합니다. 나머지 필드(`image`, `tier: frontend`)는 모두 정상이라 **오직 범위 검증 하나만** 문제 삼는다는 점을 깔끔하게 보여줍니다.
- apply 하면 `The WebApp "too-big" is invalid: spec.replicas: Invalid value: 99: spec.replicas in body should be less than or equal to 5` 가 뜨고, 이름 `too-big` 의 오브젝트는 etcd에 **저장조차 되지 않습니다**. 이후 `kubectl get webapps -n step22` 에도 나타나지 않습니다.
- 이 검증은 kubectl이 아니라 **API 서버**가 수행하므로, CI나 다른 클라이언트가 API를 직접 호출해도 똑같이 막힙니다.

```yaml file="./manifests/webapp-bad-replicas.yaml"
```

### manifests/webapp-bad-missing.yaml

22-4의 **케이스 2 — 두 가지 위반을 동시에 담은 깨진 실습 파일**입니다. 한 번의 apply로 여러 검증 오류가 한꺼번에 보고된다는 것을 확인하는 용도입니다.

- `tier: database` 는 스키마의 `enum: [frontend, backend, cache]` 에 없는 값이라 열거 검증에 걸립니다.
- `image` 필드가 아예 빠져 있어 `required: [image, replicas]` 의 필수 검증에도 걸립니다. 반면 `replicas: 2` 는 정상이라 문제가 되지 않습니다.
- 그 결과 에러 메시지에 `spec.tier: Unsupported value: "database"` 와 `spec.image: Required value` 두 줄이 함께 나옵니다. API 서버는 첫 오류에서 멈추지 않고 **위반을 모아서 한 번에** 알려 줍니다.
- 앞 파일과 마찬가지로 `broken` 오브젝트는 생성되지 않으며, 유효한 CR 2개(`shop-frontend`, `order-api`)만 남습니다.

```yaml file="./manifests/webapp-bad-missing.yaml"
```
