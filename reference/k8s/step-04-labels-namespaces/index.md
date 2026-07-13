# Step 04 — 레이블·셀렉터·네임스페이스

> **학습 목표**
> - 레이블(label)로 오브젝트에 의미를 붙이고, 셀렉터(selector)로 원하는 것만 골라낸다
> - 어노테이션(annotation)이 레이블과 무엇이 다른지 안다
> - 네임스페이스(namespace)로 리소스를 격리하고, `-n` / `-A` 를 자유자재로 쓴다
> - 레이블로 대량 선택·삭제하고, 실무에서 통하는 레이블 명명 관례를 익힌다
>
> **선행 스텝**: [Step 03 — 파드](../step-03-pods/index.md)
> **예상 소요**: 40분

---

## 4-1. 왜 레이블인가

파드가 3개면 이름으로 관리해도 됩니다. 하지만 실무에서는 수백 개입니다. "프로덕션 프론트엔드 파드만 재시작" 같은 작업을 이름 목록으로 하는 건 불가능합니다.

Kubernetes의 해답은 **레이블(label)** 입니다. 레이블은 오브젝트에 붙이는 `키=값` 꼬리표이고, **셀렉터(selector)** 로 "이 조건에 맞는 것들"을 한 번에 지목합니다. 나중에 배울 Service, Deployment, NetworkPolicy 등 거의 모든 상위 오브젝트가 "어떤 파드를 대상으로 할지"를 레이블 셀렉터로 정합니다. 즉 **레이블은 Kubernetes를 묶는 접착제**입니다.

이 스텝에서 쓸 파드 4개를 봅시다. `app`, `tier`, `env` 세 축으로 레이블을 다르게 조합했습니다.

```yaml
# manifests/pods.yaml (발췌)
apiVersion: v1
kind: Pod
metadata:
  name: web-prod-1
  namespace: step04
  labels:
    app: web
    tier: frontend
    env: prod
  annotations:
    owner: "julong@musinsa.com"       # 셀렉터 대상이 아닌 메타데이터
    description: "프로덕션 프론트엔드 1번"
spec:
  containers:
    - name: nginx
      image: nginx:1.27-alpine
```

먼저 네임스페이스부터 만들고 파드를 배포합니다.

```bash
kubectl apply -f manifests/namespace.yaml
kubectl apply -f manifests/pods.yaml
kubectl wait --for=condition=Ready pod --all -n step04 --timeout=90s
kubectl get pods -n step04 --show-labels
```

**실행 결과**
```
NAME         READY   STATUS    RESTARTS   AGE   LABELS
api-prod-1   1/1     Running   0          13s   app=api,env=prod,tier=backend
web-dev-1    1/1     Running   0          13s   app=web,env=dev,tier=frontend
web-prod-1   1/1     Running   0          13s   app=web,env=prod,tier=frontend
web-prod-2   1/1     Running   0          13s   app=web,env=prod,tier=frontend
```

`--show-labels` 로 각 파드의 레이블을 한눈에 볼 수 있습니다. 이제 이 4개에서 원하는 것만 골라내 봅시다.

---

## 4-2. 셀렉터로 골라내기 (`-l`)

`-l`(또는 `--selector`)로 조건을 겁니다. 조건은 두 종류입니다.

**등식 기반(equality-based)**: `=`, `==`, `!=`

```bash
# app=web 인 파드만
kubectl get pods -n step04 -l app=web
```
```
NAME         READY   STATUS    RESTARTS   AGE
web-dev-1    1/1     Running   0          21s
web-prod-1   1/1     Running   0          21s
web-prod-2   1/1     Running   0          21s
```

콤마로 여러 조건을 이으면 **AND**입니다(OR 아님).

```bash
kubectl get pods -n step04 -l 'env=prod,tier=frontend'
```
```
NAME         READY   STATUS    RESTARTS   AGE
web-prod-1   1/1     Running   0          21s
web-prod-2   1/1     Running   0          21s
```

**집합 기반(set-based)**: `in`, `notin`, 키 존재 여부

```bash
# env 가 dev 또는 prod 인 것 (이 조합의 OR)
kubectl get pods -n step04 -l 'env in (dev,prod)'

# app=web 이면서 env 가 prod 가 아닌 것
kubectl get pods -n step04 -l 'app=web,env!=prod'
```
```
# 'app=web,env!=prod' 결과
NAME        READY   STATUS    RESTARTS   AGE
web-dev-1   1/1     Running   0          21s
```

키의 **존재 여부**만으로도 고를 수 있습니다.

```bash
kubectl get pods -n step04 -l 'tier'      # tier 레이블이 있는 모든 파드
```

> 💡 **실무 팁**: `-l` 은 `get` 뿐 아니라 `delete`, `logs`, `label`, `describe` 등 대부분의 명령에 붙습니다. "조건에 맞는 것 전부"를 다룰 때 항상 먼저 떠올리세요.

레이블을 **컬럼으로** 보고 싶으면 `-L`(대문자)을 씁니다.

```bash
kubectl get pods -n step04 -L app,env,tier
```
```
NAME         READY   STATUS    RESTARTS   AGE   APP   ENV    TIER
api-prod-1   1/1     Running   0          21s   api   prod   backend
web-dev-1    1/1     Running   0          21s   web   dev    frontend
web-prod-1   1/1     Running   0          21s   web   prod   frontend
web-prod-2   1/1     Running   0          21s   web   prod   frontend
```

---

## 4-3. 어노테이션 — 셀렉터로 못 고르는 메타데이터

레이블과 어노테이션(annotation) 둘 다 `키=값` 메타데이터지만, 목적이 다릅니다.

| | 레이블(label) | 어노테이션(annotation) |
|---|---|---|
| 목적 | **식별·선택** | 부가 정보 기록 |
| 셀렉터로 선택 | 가능 | **불가능** |
| 값 길이/문자 | 짧고 제한적(63자, 제한 문자) | 길고 자유로움(URL, JSON 등 가능) |
| 예 | `app=web`, `env=prod` | `owner`, 배포 커밋 해시, 도구 설정 |

`describe` 로 보면 레이블과 어노테이션이 나뉘어 보입니다.

```bash
kubectl describe pod web-prod-1 -n step04 | sed -n '1,12p'
```
```
Name:             web-prod-1
Namespace:        step04
Labels:           app=web
                  env=prod
                  tier=frontend
Annotations:      description: 프로덕션 프론트엔드 1번
                  note: 점검중
                  owner: julong@musinsa.com
```

> ⚠️ **함정**: "이메일 주소로 파드를 고르고 싶다"고 이메일을 레이블에 넣으면 안 됩니다. `@`, `.` 같은 문자와 긴 길이 때문에 레이블 규칙을 어기기 쉽고, 애초에 선택 기준이 아닙니다. 선택할 것 → 레이블, 그냥 기록할 것 → 어노테이션.

---

## 4-4. 레이블 런타임 조작 — 추가·덮어쓰기·제거

배포 후에도 `kubectl label` 로 레이블을 바꿀 수 있습니다.

```bash
# 추가
kubectl label pod web-prod-1 -n step04 release=canary

# 이미 있는 키를 덮어쓰려 하면 막힌다
kubectl label pod web-prod-1 -n step04 env=staging
```
```
error: 'env' already has a value (prod), and --overwrite is false
```

기존 값을 바꾸려면 `--overwrite` 를 명시해야 합니다. 실수로 덮어쓰는 걸 막는 안전장치입니다.

```bash
kubectl label pod web-prod-1 -n step04 env=staging --overwrite   # 덮어쓰기
kubectl label pod web-prod-1 -n step04 env-                       # 키 끝에 '-' → 제거
kubectl get pod web-prod-1 -n step04 --show-labels
```
```
NAME         READY   STATUS    RESTARTS   AGE   LABELS
web-prod-1   1/1     Running   0          35s   app=web,release=canary,tier=frontend
```

어노테이션도 똑같이 `kubectl annotate` 로 다룹니다(`annotate ... note-` 로 제거).

> ⚠️ **함정**: 파드 레이블을 실시간으로 바꾸면 그 파드를 고르던 Service/Deployment의 대상 집합이 즉시 바뀝니다. 예컨대 카나리 릴리스는 이 성질을 이용하지만, 모르고 바꾸면 트래픽이 끊기거나 Deployment가 파드를 "고아"로 판단해 새로 만들 수 있습니다. Step 05·06에서 직접 재현합니다.

---

## 4-5. 네임스페이스 — 리소스를 격리하는 칸막이

**네임스페이스(namespace)** 는 한 클러스터 안을 논리적으로 나누는 칸막이입니다. 팀별·환경별·스텝별로 리소스를 격리하고, 이름 충돌을 막고, 권한(RBAC)과 쿼터를 경계 단위로 겁니다. 이 코스가 스텝마다 `step04`, `step05` … 를 따로 쓰는 이유입니다.

```bash
kubectl get ns
```
```
NAME                 STATUS   AGE
default              Active   12m
kube-node-lease      Active   12m
kube-public          Active   12m
kube-system          Active   12m
local-path-storage   Active   12m
step04               Active   44s
```

우리 네임스페이스는 이렇게 선언했습니다.

```yaml
# manifests/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: step04
  labels:
    course: k8s-learn
    step: "04"
```

**핵심 규칙 세 가지**

1. `-n <ns>` 로 대상 네임스페이스를 지정한다. 안 주면 컨텍스트의 **기본 네임스페이스**(보통 `default`)에서 동작한다.
2. `-A`(`--all-namespaces`)로 모든 네임스페이스를 가로질러 본다.
3. 파드·서비스·configmap 같은 대부분의 오브젝트는 **네임스페이스 범위**다. 반면 노드·네임스페이스 자체·PV 등은 **클러스터 범위**(네임스페이스에 안 속함)다.

```bash
# 모든 네임스페이스에서 app=web 파드 찾기
kubectl get pods -A -l app=web
```
```
NAMESPACE   NAME         READY   STATUS    RESTARTS   AGE
step04      web-dev-1    1/1     Running   0          44s
step04      web-prod-1   1/1     Running   0          44s
step04      web-prod-2   1/1     Running   0          44s
```

> 💡 **실무 팁**: 매번 `-n step04` 를 치기 귀찮으면 기본 네임스페이스를 바꿉니다.
> `kubectl config set-context --current --namespace=step04`
> 단, 이 코스에서는 여러 스텝이 같은 클러스터를 쓰므로 **명시적으로 `-n` 을 붙이는 습관**을 권합니다. 안 그러면 엉뚱한 네임스페이스를 건드립니다.

> ⚠️ **함정**: `kubectl delete namespace step04` 는 그 안의 **모든 리소스를 통째로 삭제**합니다. 편리하지만 무섭습니다. 프로덕션에서 네임스페이스를 지우는 건 그 안의 전부를 지우는 것과 같습니다.

---

## 4-6. 레이블로 대량 선택·삭제

셀렉터의 진가는 대량 작업에서 드러납니다. `env=dev` 인 것만 골라 지워봅시다.

```bash
kubectl delete pod -n step04 -l env=dev
kubectl get pods -n step04 --show-labels
```
```
pod "web-dev-1" deleted from step04 namespace

NAME         READY   STATUS    RESTARTS   AGE   LABELS
api-prod-1   1/1     Running   0          53s   app=api,env=prod,tier=backend
web-prod-1   1/1     Running   0          53s   app=web,env=prod,tier=frontend
web-prod-2   1/1     Running   0          53s   app=web,env=prod,tier=frontend
```

`dev` 파드만 정확히 사라졌습니다. `prod` 파드는 그대로입니다.

> ⚠️ **함정**: `kubectl delete pod -l app=web` 처럼 셀렉터로 지울 땐 **실행 전에 반드시 같은 셀렉터로 `get` 을 먼저** 해보세요. 셀렉터를 넓게 잡으면 의도보다 많은 걸 지웁니다. `--dry-run=client` 는 삭제엔 별 도움이 안 되니, "get 으로 미리보기"가 가장 안전한 습관입니다.

---

## 4-7. 실무 레이블 명명 관례

레이블 키/값은 자유지만, 커뮤니티가 권장하는 **공통 레이블(`app.kubernetes.io/*`)** 이 있습니다. Helm·Kustomize·모니터링 도구가 이 키를 기대합니다.

| 권장 키 | 의미 | 예 |
|---|---|---|
| `app.kubernetes.io/name` | 애플리케이션 이름 | `mysql` |
| `app.kubernetes.io/instance` | 이 배포 인스턴스 식별자 | `mysql-abcxzy` |
| `app.kubernetes.io/version` | 버전 | `8.0.36` |
| `app.kubernetes.io/component` | 아키텍처 상의 역할 | `database` |
| `app.kubernetes.io/part-of` | 상위 애플리케이션 | `wordpress` |
| `app.kubernetes.io/managed-by` | 관리 도구 | `helm` |

접두사(`/` 앞부분, 예 `app.kubernetes.io`)가 있는 키는 표준·도구용이고, 접두사 없는 키(`app`, `env`)는 내 마음대로 써도 되는 사용자 정의입니다. 이 스텝에선 배우기 쉽도록 짧은 `app`/`tier`/`env` 를 썼습니다.

> 💡 **실무 팁**: 팀 안에서 `env`, `team`, `app` 같은 **핵심 축을 미리 합의**해 두면, 비용 집계·모니터링·정책 적용이 전부 셀렉터 하나로 됩니다. 레이블 설계는 나중에 바꾸기 어려우니 초반에 정하세요.

---

## 정리

| 개념 | 한 줄 요약 | 대표 명령 |
|---|---|---|
| 레이블 | 식별·선택용 `키=값` 꼬리표 | `kubectl label`, `-l`, `--show-labels` |
| 셀렉터 | 레이블로 대상 집합을 고르는 조건 | `-l 'env in (dev,prod)'` |
| 어노테이션 | 선택 대상이 아닌 부가 메타데이터 | `kubectl annotate` |
| 네임스페이스 | 리소스 격리 칸막이 | `-n`, `-A`, `kubectl get ns` |
| 대량 작업 | 셀렉터로 여러 오브젝트 한 번에 | `kubectl delete pod -l env=dev` |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 05 — 디플로이먼트](../step-05-deployments/index.md)

---

## 실습 파일

이 스텝은 파일 3개로 돌아갑니다. 먼저 `manifests/namespace.yaml` 로 격리 공간인 `step04` 네임스페이스를 만들고, 그 안에 `manifests/pods.yaml` 로 레이블 조합이 서로 다른 파드 4개를 띄웁니다. 그다음 `commands.sh` 에 담긴 명령을 위에서부터 한 줄씩 따라가며 셀렉터·어노테이션·레이블 조작·네임스페이스·대량 삭제를 차례로 체험합니다. 세 파일은 반드시 **네임스페이스 → 파드 → 명령** 순서로 써야 합니다(파드 매니페스트가 `namespace: step04` 를 명시하고 있어서, 네임스페이스가 없으면 apply 가 실패합니다).

### manifests/namespace.yaml

4-5 절의 "리소스를 격리하는 칸막이"에 해당하는 파일이며, 실습에서 **가장 먼저** apply 합니다.

- `kind: Namespace` 에 `metadata.name: step04` 하나만 있으면 네임스페이스가 만들어집니다. 아주 단순한 오브젝트입니다.
- 네임스페이스 자체에도 레이블을 붙일 수 있습니다. 여기서는 `course: k8s-learn` 과 `step: "04"` 를 달아 두었는데, `kubectl get ns -l course=k8s-learn` 처럼 **네임스페이스도 셀렉터로 골라낼 수 있다**는 걸 보여주는 예입니다.
- `step: "04"` 의 따옴표에 주목하세요. 따옴표를 빼면 YAML 이 `04` 를 숫자로 해석하려 하고, 레이블 값은 문자열이어야 하므로 문제가 됩니다. **레이블 값이 숫자처럼 생겼으면 항상 따옴표로 감싸는 습관**을 들이세요.
- 네임스페이스는 클러스터 범위(cluster-scoped) 오브젝트라 `-n` 옵션이 의미가 없습니다. 4-5 절의 세 번째 핵심 규칙 그대로입니다.
- ⚠️ 이 파일로 만든 네임스페이스를 `kubectl delete namespace step04` 로 지우면 **그 안의 파드 4개가 전부 함께 사라집니다.** `commands.sh` 맨 끝의 "정리" 블록이 바로 그 명령입니다.

```yaml file="./manifests/namespace.yaml"
```

### manifests/pods.yaml

4-1 절에서 "이 스텝에서 쓸 파드 4개"로 소개한 바로 그 매니페스트입니다. 이후 4-2 ~ 4-6 절의 모든 셀렉터 실습이 이 4개를 대상으로 진행됩니다.

- `---` 로 구분된 문서 4개가 한 파일에 들어 있습니다. `kubectl apply -f` 한 번으로 파드 4개가 동시에 생성됩니다.
- 레이블 축은 `app`(web/api), `tier`(frontend/backend), `env`(prod/dev) 세 가지입니다. 조합은 `web-prod-1`·`web-prod-2`(web/frontend/prod), `web-dev-1`(web/frontend/dev), `api-prod-1`(api/backend/prod) 입니다. 이 **의도적으로 겹치고 어긋나는 조합** 덕분에 `-l app=web` 은 3개, `-l 'env=prod,tier=frontend'` 는 2개, `-l 'app=web,env!=prod'` 는 1개를 반환하는 차이를 눈으로 확인할 수 있습니다.
- 네 파드 모두 `metadata.namespace: step04` 를 매니페스트 안에 박아 두었습니다. 그래서 `kubectl apply -f manifests/pods.yaml` 에 `-n` 을 붙이지 않아도 항상 `step04` 에 만들어집니다.
- `web-prod-1` 에만 `annotations` 가 있습니다. `owner: "julong@musinsa.com"` 은 `@` 와 `.` 이 들어간 값이라 레이블로는 부적절하고, `description: "프로덕션 프론트엔드 1번"` 은 공백과 한글이 섞여 있습니다. 4-3 절의 "레이블 vs 어노테이션" 대비를 위해 일부러 이렇게 만든 것이고, `kubectl describe pod web-prod-1 -n step04` 에서 Labels 와 Annotations 가 나뉘어 보이는 이유이기도 합니다.
- `api-prod-1` 만 이미지가 다릅니다. `hashicorp/http-echo:1.0` 에 `-text=hello from api`, `-listen=:5678` 인자를 주어 5678 포트로 응답하게 했고, `containerPort: 5678` 을 선언해 두었습니다. 나머지 3개는 모두 `nginx:1.27-alpine` 입니다. 백엔드 tier 가 실제로 "다른 것"임을 체감하게 하려는 장치입니다.
- `containerPort` 선언은 `web-prod-1` 과 `api-prod-1` 두 파드에만 있습니다(`web-prod-2`, `web-dev-1` 에는 `ports` 블록 자체가 없습니다). 그래도 세 nginx 파드는 모두 정상적으로 80 포트에서 동작합니다. `containerPort` 는 **문서화용 필드**일 뿐, 이 값을 적지 않아도 컨테이너가 여는 포트가 막히지는 않기 때문입니다. 이 차이 자체가 "선언하지 않아도 돌아간다"는 걸 보여주는 좋은 대비입니다.
- 이 파드들은 Deployment 가 관리하지 않는 **맨몸 파드(bare pod)** 입니다. 그래서 4-6 절에서 `kubectl delete pod -l env=dev` 로 지우면 다시 살아나지 않습니다. Step 05 에서 Deployment 를 배우면 이 동작이 어떻게 달라지는지 비교해 보세요.

```yaml file="./manifests/pods.yaml"
```

### commands.sh

강의 본문 4-1 ~ 4-6 절에 등장하는 명령을 순서대로 모아 둔 대본입니다. **통째로 실행하기보다 한 줄씩 복사해 실행하며 출력을 관찰**하도록 만들어졌습니다(본문의 "실행 결과" 블록과 하나씩 대조해 보세요).

- 셔뱅(`#!/usr/bin/env bash`) 다음에 오는 `export PATH="/opt/homebrew/bin:$PATH"` 는 Apple Silicon macOS + Homebrew 환경 전제입니다. 다른 환경이라면 이 줄은 무시해도 됩니다.
- 그다음 첫 kubectl 명령인 `kubectl config current-context` 로 컨텍스트가 `kind-learn` 인지 확인합니다. 엉뚱한 클러스터에 파드를 만드는 사고를 막는 첫 관문입니다.
- `kubectl wait --for=condition=Ready pod --all -n step04 --timeout=90s` 로 파드 4개가 전부 Ready 가 될 때까지 최대 90초 기다립니다. 이미지 풀 시간이 있으니 이 줄을 건너뛰고 바로 `get` 하면 `ContainerCreating` 이 보일 수 있습니다.
- 29번째 줄 `kubectl label pod web-prod-1 -n step04 env=staging` 은 **일부러 실패하는 명령**입니다. `env` 키에 이미 `prod` 값이 있어서 `error: 'env' already has a value (prod), and --overwrite is false` 가 뜹니다. 바로 다음 줄에서 `--overwrite` 를 붙여 성공시키는 대비가 4-4 절의 핵심 학습 포인트입니다.
- `kubectl label pod web-prod-1 -n step04 env-` 처럼 **키 끝에 `-`** 를 붙이면 레이블이 제거됩니다. 그리고 4-4 블록 마지막 줄 `kubectl label pod web-prod-1 -n step04 env=prod release- --overwrite` 로 한 줄에서 덮어쓰기(`env=prod`)와 제거(`release-`)를 동시에 수행해 실습 전 상태로 원복합니다.
- `kubectl annotate pod web-prod-1 -n step04 note="점검중"` 한 줄도 4-4 블록에 섞여 있습니다. 매니페스트에는 없던 `note` 어노테이션을 런타임에 추가하는 것이고, 어노테이션도 레이블과 똑같이 런타임에 추가·제거할 수 있음을 보여줍니다.
- ⚠️ 한 가지 순서에 주의하세요. 4-3 절 본문의 `describe` 출력에는 `note: 점검중` 이 보이지만, `commands.sh` 에서는 `describe` 줄(4-3 블록)이 `annotate` 줄(4-4 블록)보다 **먼저** 나옵니다. 그래서 위에서부터 그대로 실행하면 첫 `describe` 출력에는 `owner` 와 `description` 두 개만 보이고 `note` 는 없습니다. 본문과 똑같은 출력을 보려면 `annotate` 를 실행한 뒤 `kubectl describe pod web-prod-1 -n step04` 를 **한 번 더** 실행하세요. 매니페스트에서 온 어노테이션과 런타임에 붙인 어노테이션의 차이를 확인하기에 좋은 지점입니다.
- 4-6 절 블록은 `kubectl get pods -n step04 -l env=dev` (미리보기) → `kubectl delete pod -n step04 -l env=dev` (삭제) 순서로 되어 있습니다. 이 "get 먼저, delete 나중" 순서 자체가 배워야 할 안전 습관입니다.
- ⚠️ 맨 끝의 "정리" 블록에 있는 `kubectl delete namespace step04` 는 **파괴적 명령**입니다. 네임스페이스 안의 모든 리소스를 통째로 지웁니다(그 뒤의 `kubectl get ns` 는 삭제 결과를 확인하는 용도입니다). 아직 실습 중이라면 이 두 줄은 실행하지 마세요. `challenge.md` 의 과제를 풀려면 파드가 살아 있어야 합니다.

```bash file="./commands.sh"
```
