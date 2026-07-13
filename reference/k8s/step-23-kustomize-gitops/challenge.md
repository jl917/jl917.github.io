# Step 23 연습 과제

> 작업 위치는 `step-23-kustomize-gitops/` 디렉터리입니다.
> 시작 전 dev/prod가 떠 있게 하려면:
> ```bash
> kubectl create ns step23-dev; kubectl create ns step23-prod
> kubectl apply -k manifests/overlays/dev
> kubectl apply -k manifests/overlays/prod
> ```
> 막히면 해답을 펼쳐 보세요.

---

### 과제 1. staging 오버레이 추가하기

`overlays/staging`을 새로 만드세요. 네임스페이스 `step23-staging`, 접두사 `stg-`, `env=staging` 레이블, replicas 2, MESSAGE는 `Hello from STAGING`. 이미지는 base 그대로(nginx) 둡니다. 렌더로 확인한 뒤 배포하세요.

<details>
<summary>해답</summary>

`overlays/staging/patch-deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: web
          env:
            - name: TIER
              value: "staging"
```

`overlays/staging/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: step23-staging
namePrefix: stg-
labels:
  - pairs:
      env: staging
    includeSelectors: true
resources:
  - ../../base
patches:
  - path: patch-deployment.yaml
configMapGenerator:
  - name: web-config
    behavior: replace
    literals:
      - MESSAGE=Hello from STAGING
```

```bash
kubectl kustomize manifests/overlays/staging      # 미리보기
kubectl create ns step23-staging
kubectl apply -k manifests/overlays/staging
kubectl get deploy -n step23-staging -o wide      # replicas 2, nginx 확인
# 정리
kubectl delete ns step23-staging
```
base를 한 줄도 건드리지 않고 새 환경이 생겼다는 점이 핵심입니다.
</details>

---

### 과제 2. prod를 5 레플리카로 (GitOps식 변경)

prod의 레플리카를 3 → 5로 올리세요. 단, `kubectl scale`이나 `kubectl edit`을 쓰지 말고 **매니페스트를 고쳐 다시 apply**하세요. 왜 이 방식이 GitOps에서 옳은지 한 줄로 설명하세요.

<details>
<summary>해답</summary>

`overlays/prod/patch-deployment.yaml`의 `replicas: 3` → `replicas: 5`로 수정 후:
```bash
kubectl apply -k manifests/overlays/prod
kubectl get deploy prod-web -n step23-prod
```
`kubectl scale`은 클러스터의 실제 상태만 바꾸고 **매니페스트(=Git)와 어긋나게(drift)** 만듭니다. GitOps 컨트롤러가 있었다면 곧바로 3으로 되돌려 버립니다. 원하는 상태는 항상 **선언(매니페스트)**에서 바꾸고 적용해야, Git이 진실의 원천으로 유지됩니다.
</details>

---

### 과제 3. ConfigMap 값 바꾸면 파드가 왜 새로 뜨는가

dev의 MESSAGE를 아무 값으로 바꾸고 다시 apply한 뒤, ConfigMap 이름과 파드 이름이 어떻게 변하는지 관찰하세요. 이유를 설명하세요.

<details>
<summary>해답</summary>

`overlays/dev/kustomization.yaml`의 MESSAGE를 바꾸고:
```bash
kubectl get cm,pod -n step23-dev            # 변경 전 이름 기록
kubectl apply -k manifests/overlays/dev
kubectl get cm,pod -n step23-dev            # ConfigMap 해시가 바뀌고, 파드가 롤링됨
```
`configMapGenerator`는 **내용의 해시**를 이름에 붙입니다. MESSAGE가 바뀌면 → ConfigMap 이름이 바뀌고 → Deployment의 `configMapKeyRef`가 새 이름을 가리키도록 kustomize가 자동 수정 → 파드 템플릿이 달라져 **롤링 업데이트**가 트리거됩니다. 고정 이름 ConfigMap을 수정할 때 파드가 옛 값을 계속 쓰던 문제(Step 07)를 구조적으로 막아 줍니다.
</details>

---

### 과제 4. 셀렉터 격리 확인

dev Service가 prod 파드를 잡지 않는지 확인하세요. 왜 서로 안 섞이는지 셀렉터로 설명하세요.

<details>
<summary>해답</summary>

```bash
kubectl get endpoints dev-web  -n step23-dev  -o wide   # dev 파드 IP만
kubectl get endpoints prod-web -n step23-prod -o wide   # prod 파드 IP만
kubectl describe svc dev-web -n step23-dev | grep Selector
```
1차로 **네임스페이스가 다릅니다**(Service는 자기 네임스페이스의 파드만 잡음). 2차로 `labels`의 `includeSelectors: true` 덕분에 dev Service 셀렉터는 `app=web,course=k8s-learn,env=dev`, prod는 `env=prod`라서 레이블로도 겹치지 않습니다. 이중 격리입니다.
</details>

---

### 과제 5. Kustomize vs Helm 판단

다음 상황에서 Kustomize와 Helm 중 무엇이 더 적합한지 이유와 함께 고르세요.
(a) 우리 팀 마이크로서비스를 dev/staging/prod로 배포, 차이는 레플리카·리소스·이미지 태그뿐
(b) 오픈소스 Kafka를 다양한 옵션으로 남들이 설치할 수 있게 패키징해 배포
(c) 사내 여러 팀에 공통 사이드카를 강제로 얹되, 각 팀 매니페스트는 그대로 두기

<details>
<summary>해답</summary>

- (a) **Kustomize**. 같은 앱의 환경별 소규모 차이 → overlay가 딱 맞음. 템플릿 문법이 필요 없음.
- (b) **Helm**. 파라미터(values)로 폭넓게 설정 가능한 **재분배용 패키지** → 차트 생태계·릴리스 관리가 강점.
- (c) **Kustomize**. 남의 원본 YAML을 건드리지 않고 patch로 사이드카를 얹는 데 적합. (컴포넌트/patch로 공통 조각을 재사용)

원칙: **우리 것을 환경별로 조금 바꿔 배포 → Kustomize / 남이 파라미터로 설치할 패키지 → Helm.**
</details>
