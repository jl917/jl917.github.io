# Step 22 연습 과제 — CRD와 오퍼레이터

아래 과제를 먼저 스스로 풀어 본 뒤 해답을 펼쳐 보세요. 모든 작업은 네임스페이스 `step22`,
그룹 `learn.example.com` 안에서만 하고, 끝나면 CRD와 네임스페이스를 반드시 지웁니다.

---

## 과제 1 — CRD에 새 필드 추가 (검증 포함)

`WebApp` 스펙에 `port` 라는 정수 필드를 추가하되, 1024 이상 65535 이하만 허용하도록
스키마를 수정하세요. 그리고 `port: 80` 인 CR을 apply 해서 거부되는지 확인하세요.

<details>
<summary>해답</summary>

`manifests/webapp-crd.yaml` 의 `spec.properties` 아래에 추가:

```yaml
port:
  type: integer
  minimum: 1024
  maximum: 65535
  description: "서비스 포트 (1024~65535)"
```

적용 후 검증:
```bash
kubectl apply -f manifests/webapp-crd.yaml
cat <<'EOF' | kubectl apply -f -
apiVersion: learn.example.com/v1alpha1
kind: WebApp
metadata: { name: low-port, namespace: step22 }
spec: { image: nginx:1.27-alpine, replicas: 1, port: 80 }
EOF
```
기대 출력:
```
The WebApp "low-port" is invalid: spec.port: Invalid value: 80: spec.port in body should be greater than or equal to 1024
```
CRD의 스키마만 고치면 즉시 새 검증 규칙이 API 서버에 반영됩니다.
</details>

---

## 과제 2 — shortName 과 category로만 조회

`kubectl get webapps` 를 쓰지 말고, (a) 축약형과 (b) 카테고리 두 가지 방법으로
`step22` 의 WebApp 목록을 조회하세요.

<details>
<summary>해답</summary>

```bash
kubectl get wa -n step22        # (a) shortNames: [wa]
kubectl get learn -n step22     # (b) categories: [all, learn] → learn 묶음
```
`kubectl get all -n step22` 로도 잡히지만, `all` 카테고리에는 다른 리소스도 섞이므로
전용 카테고리 `learn` 이 더 정확합니다.
</details>

---

## 과제 3 — 필수 필드를 빠뜨리면?

`image` 는 그대로 두고 `replicas` 만 뺀 CR을 apply 하면 어떤 에러가 나는지 예측하고 확인하세요.

<details>
<summary>해답</summary>

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: learn.example.com/v1alpha1
kind: WebApp
metadata: { name: no-replicas, namespace: step22 }
spec: { image: nginx:1.27-alpine, tier: cache }
EOF
```
기대 출력:
```
The WebApp "no-replicas" is invalid: spec.replicas: Required value
```
`required: [image, replicas]` 에 걸립니다. `default` 가 없는 required 필드는 반드시 명시해야 합니다.
(참고: `tier` 는 default가 있어 안 줘도 되고, `greeting` 도 마찬가지입니다.)
</details>

---

## 과제 4 — 스토리지 버전 규칙 위반 재현

`versions` 에 `v1alpha1` 과 `v1beta1` 두 버전을 두고 **둘 다** `storage: true` 로 설정한 뒤
apply 하면 어떻게 되는지 확인하세요.

<details>
<summary>해답</summary>

```yaml
versions:
  - name: v1alpha1
    served: true
    storage: true
    schema: { ... }
  - name: v1beta1
    served: true
    storage: true        # 두 번째도 true → 위반
    schema: { ... }
```
apply 시 CRD 자체가 거부됩니다:
```
CustomResourceDefinition.apiextensions.k8s.io "webapps.learn.example.com" is invalid: spec.versions: Invalid value: ...: must have exactly one version marked as storage version
```
etcd에 저장할 정본 버전은 하나여야 하므로 `storage: true` 는 정확히 하나만 허용됩니다.
</details>

---

## 과제 5 — CRD를 지우면 CR은 어떻게 되나

`WebApp` CR 2개가 존재하는 상태에서 CRD를 삭제하면 CR들은 어떻게 되는지 확인하세요.

<details>
<summary>해답</summary>

```bash
kubectl get webapps -n step22           # 삭제 전 2개 존재
kubectl delete crd webapps.learn.example.com
kubectl get webapps -n step22           # error: the server doesn't have a resource type "webapps"
```
CRD를 지우면 그 종류(kind) 자체가 API에서 사라지고, **그에 속한 모든 CR도 함께 삭제**됩니다.
CRD는 클러스터 범위 자산이라, 지우는 순간 클러스터 전역에서 그 리소스가 통째로 사라집니다.
실무에서 오퍼레이터를 제거할 때 이 파급을 반드시 고려해야 합니다.
</details>

---

## 정리 (반드시 실행)

```bash
export PATH="/opt/homebrew/bin:$PATH"
kubectl delete crd webapps.learn.example.com --ignore-not-found
kubectl delete namespace step22 --ignore-not-found
kubectl get ns
kubectl get crd | grep learn.example.com || echo none
```
