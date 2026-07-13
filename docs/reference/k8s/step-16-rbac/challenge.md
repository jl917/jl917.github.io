# Step 16 연습 과제 — RBAC

> 전제: `step16` 에 pod-reader SA, pod-reader-role(pods get/list/watch) 이 있다.

---

## 과제 1. ConfigMap 읽기 추가

pod-reader 가 pods 에 더해 **configmaps 도 읽을 수 있게**(get/list) 하라. secrets 는 여전히 못 보게 유지.

<details><summary>해답</summary>

Role 의 rules 에 규칙을 추가(secrets 는 넣지 않음):
```yaml
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get", "list"]
```
검증: `kubectl auth can-i list configmaps -n step16 --as=system:serviceaccount:step16:pod-reader` → yes,
`... get secrets ...` → no.
</details>

---

## 과제 2. 배포 담당자(deploy-manager)

`apps` 그룹의 **deployments 를 생성/수정/삭제** 할 수 있는 SA `deploy-manager` 를 step16 에 만들어라.
(pods 는 볼 수 있지만 삭제는 불가)

<details><summary>해답</summary>

```yaml
apiVersion: v1
kind: ServiceAccount
metadata: { name: deploy-manager, namespace: step16 }
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: deploy-manager-role, namespace: step16 }
rules:
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list"]          # 삭제 verb 없음
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: deploy-manager-binding, namespace: step16 }
subjects:
  - { kind: ServiceAccount, name: deploy-manager, namespace: step16 }
roleRef: { kind: Role, name: deploy-manager-role, apiGroup: rbac.authorization.k8s.io }
```
검증: `kubectl auth can-i create deployments.apps -n step16 --as=system:serviceaccount:step16:deploy-manager` → yes,
`... delete pods ...` → no.
</details>

---

## 과제 3. ClusterRole 을 RoleBinding 으로 (범위 좁히기)

기본 제공 ClusterRole `view` 를, **step16 네임스페이스에서만** pod-reader 가 쓰도록 RoleBinding 하라.
(ClusterRoleBinding 이 아니라 RoleBinding 을 쓰는 이유를 설명)

<details><summary>해답</summary>

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: view-in-step16, namespace: step16 }
subjects:
  - { kind: ServiceAccount, name: pod-reader, namespace: step16 }
roleRef:
  kind: ClusterRole        # ClusterRole 을 참조하지만
  name: view
  apiGroup: rbac.authorization.k8s.io
```
- **RoleBinding + ClusterRole** 조합: ClusterRole 의 권한이 **그 RoleBinding 의 네임스페이스(step16)에만** 적용된다.
- 같은 ClusterRole 을 ClusterRoleBinding 으로 걸면 **모든 네임스페이스**에 적용되어 과도한 권한이 된다.
- 재사용 가능한 권한 묶음(ClusterRole)을 필요한 네임스페이스에만 좁혀 부여하는 대표 패턴.

(주의: 기본 `view` ClusterRole 은 수정하지 말 것. 여기선 참조만 한다.)
</details>

---

## 과제 4. (진단) "권한 줬는데 Forbidden"

동료가 SA 에 Role 을 만들고 RoleBinding 도 했는데 계속 Forbidden 이다. 원인 후보 4가지를 들어라.

<details><summary>해답</summary>

1. **RoleBinding 의 subject 오타** — `name`/`namespace`/`kind` 불일치. `ServiceAccount` 의 namespace 를 안 적으면 바인딩이 엉뚱한 주체를 가리킴.
2. **네임스페이스 불일치** — Role/RoleBinding 이 대상 리소스와 다른 네임스페이스에 있음(Role 은 ns 범위).
3. **리소스/동사 이름 오류** — `pod`(X)/`pods`(O), 하위 리소스(`pods/log`) 누락, apiGroup 틀림(deployments 는 `apps`).
4. **토큰 테스트가 관리자로 폴백** 하거나 반대로 **정말 그 SA 로** 안 됨. `kubectl auth whoami` / `kubectl auth can-i --list --as=<subject>` 로 실제 신원과 권한을 확인.

진단 도구: `kubectl auth can-i <verb> <resource> -n <ns> --as=<subject>`, `kubectl describe rolebinding <name> -n <ns>`.
</details>

---

## 과제 5. 최소 권한 설계 (서술)

"로그 수집 에이전트" 가 모든 네임스페이스의 **파드 목록과 파드 로그만** 읽으면 된다. 어떤 오브젝트를 어떻게 구성할지 설계하라.

<details><summary>해답</summary>

- 클러스터 전역(모든 ns)에서 읽어야 하므로 **ClusterRole**:
  ```yaml
  rules:
    - apiGroups: [""]
      resources: ["pods", "pods/log"]
      verbs: ["get", "list"]        # watch 는 필요 시
  ```
- 이를 **ClusterRoleBinding** 으로 에이전트 SA 에 부여(모든 ns 대상).
- `pods/log` 는 파드 로그를 읽는 하위 리소스. secrets/exec 등은 넣지 않는다(최소 권한).
- watch 가 필요 없으면 빼서 권한을 더 줄인다.
</details>
