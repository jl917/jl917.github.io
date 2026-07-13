# Step 16 — RBAC와 인증 (Authentication & Authorization)

> **학습 목표**
> - **인증(authentication, "너 누구야")** 과 **인가(authorization, "너 이거 해도 돼")** 를 구분한다.
> - ServiceAccount, Role/ClusterRole, RoleBinding/ClusterRoleBinding 의 관계를 이해한다.
> - 동사(verbs)/리소스(resources)/apiGroups 로 권한을 표현한다.
> - `kubectl auth can-i` 와 `--as` 임퍼소네이션으로 권한을 검증한다.
> - **실제 토큰으로** "pod 는 보되 secret 은 못 보는" 최소 권한을 확인한다.
>
> **선행 지식**: Step 03(파드), Step 07(시크릿).
> **소요 시간**: 40~60분

---

## 0. 인증 vs 인가

요청이 API 서버에 오면 두 관문을 지납니다.

1. **인증(Authentication)** — "이 요청의 신원은 누구인가?" 클라이언트 인증서, **ServiceAccount 토큰**(Bearer), OIDC 등으로 신원을 확정. 결과는 **사용자 이름 + 그룹**.
2. **인가(Authorization)** — "그 신원이 이 동작을 해도 되나?" 쿠버네티스는 주로 **RBAC**(Role-Based Access Control) 로 판단.

```
   요청 ─▶ [인증] 너는 system:serviceaccount:step16:pod-reader (그룹 ...)
            │
            ▼
        [인가/RBAC] 이 신원이 "step16 에서 pods list" 를 허용하는 (Cluster)Role 이 바인딩돼 있나?
            │  있으면 → 허용,  없으면 → Forbidden(403)
            ▼
        [Admission] 리소스 검증/변형
```

> **RBAC 는 기본이 "전부 거부"** 입니다. 명시적으로 허용(Role + Binding)하지 않은 것은 못 합니다. deny 규칙 자체가 없습니다 — 안 준 건 못 하는 것.

---

## 1. 4가지 오브젝트의 관계

| 오브젝트 | 범위 | 역할 |
|---|---|---|
| **ServiceAccount** | 네임스페이스 | 파드/도구가 쓰는 **신원**(인증 주체) |
| **Role** | 네임스페이스 | "무엇을 어떤 동사로" 할 수 있는지 규칙(그 네임스페이스 안) |
| **ClusterRole** | 클러스터 | 규칙(클러스터 스코프 리소스 or 모든 ns 대상) |
| **RoleBinding** | 네임스페이스 | (Cluster)Role 을 **주체에게 부여** (그 네임스페이스 안) |
| **ClusterRoleBinding** | 클러스터 | ClusterRole 을 주체에게 **전역 부여** |

```
  ServiceAccount ──(RoleBinding)── Role ──▶ [pods: get,list,watch]
       (신원)                       (권한 묶음)
```

**권한(Role) 과 부여(Binding) 를 분리**하는 게 핵심입니다. Role 은 "권한 템플릿", Binding 은 "누구에게 줄지".

---

## 2. 최소 권한 설계 — "pod 만 보는" 신원

`step16` 네임스페이스에서 **pods 를 get/list/watch 만** 하는 ServiceAccount 를 만듭니다. **secrets 는 일부러 뺍니다.**

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-serviceaccounts.yaml   # pod-reader, no-access(대조군)
kubectl apply -f manifests/20-role-and-binding.yaml  # Role + RoleBinding
```

Role 의 핵심:
```yaml
rules:
  - apiGroups: [""]                     # core group (pods/services/secrets...)
    resources: ["pods"]
    verbs: ["get", "list", "watch"]     # secrets/delete 등은 없음
```

동사(verbs) 목록: `get`, `list`, `watch`, `create`, `update`, `patch`, `delete`, `deletecollection`.
`apiGroups: [""]` 은 core 그룹(pods 등), `apiGroups: ["apps"]` 는 deployments/statefulsets 등.

---

## 3. `kubectl auth can-i` + `--as` 임퍼소네이션으로 검증

관리자 권한으로 **다른 신원인 척(--as)** 하고 권한을 점검합니다. 실제로 그 토큰을 안 써도 되니 빠릅니다.

```bash
SA=system:serviceaccount:step16:pod-reader
kubectl auth can-i list pods    -n step16  --as=$SA
kubectl auth can-i get secrets  -n step16  --as=$SA
kubectl auth can-i delete pods  -n step16  --as=$SA
kubectl auth can-i list pods    -n default --as=$SA
```
**실제 출력**
```
yes    # list pods (step16)      ← Role 이 허용
no     # get secrets (step16)    ← Role 에 secrets 없음
no     # delete pods (step16)    ← verbs 에 delete 없음
no     # list pods (default)     ← Role 은 step16 네임스페이스에만 유효
```

전체 권한을 한눈에:
```bash
kubectl auth can-i --list -n step16 --as=system:serviceaccount:step16:pod-reader
```
```
Resources                                       ...   Verbs
pods                                            ...   [get list watch]
nodes                                           ...   [get list]        # (아래 4절 ClusterRole 부여 후)
selfsubjectaccessreviews.authorization.k8s.io   ...   [create]
... (모든 인증 사용자에게 기본 부여되는 self-review/디스커버리 권한)
```

> `selfsubject*reviews` 와 `/healthz` 등은 **모든 인증된 사용자에게 기본 부여**되는 최소 권한이라 항상 보입니다. 우리가 준 건 `pods [get list watch]` 뿐입니다.

---

## 4. ClusterRole — 클러스터 범위 권한

노드(nodes)는 네임스페이스에 안 매인 **클러스터 스코프** 리소스라, 이를 읽으려면 **ClusterRole + ClusterRoleBinding** 이 필요합니다.

```bash
kubectl apply -f manifests/30-clusterrole-and-binding.yaml
```
```yaml
kind: ClusterRole
metadata:
  name: step16-node-reader          # ⚠️ step16- 접두사(실습 규칙)
rules:
  - apiGroups: [""]
    resources: ["nodes"]
    verbs: ["get", "list"]
```

```bash
kubectl auth can-i list nodes --as=system:serviceaccount:step16:pod-reader
```
```
yes
```

> ⚠️ **실습 규칙**: ClusterRole/ClusterRoleBinding 은 이름에 `step16-` 접두사를 붙이고 **검증 후 반드시 삭제**합니다. 시스템 기본 ClusterRole(`view`/`edit`/`admin`/`cluster-admin`)은 절대 수정 금지 — 클러스터 전체 권한 체계가 깨집니다.

---

## 5. 진짜 토큰으로 확인 (임퍼소네이션이 아닌 실제)

`--as` 는 "관리자가 대신 물어보는" 것이고, 이번엔 **실제 pod-reader 토큰**으로 API 서버에 직접 요청해 봅니다.

```bash
# 1) SA 토큰 발급 (수명 짧은 bearer token)
TOKEN=$(kubectl create token pod-reader -n step16)

# 2) 토큰만 쓰는 임시 kubeconfig 구성 (관리자 인증서로 폴백되지 않도록!)
KC=/tmp/step16-kc.yaml
kubectl config view --raw --minify > "$KC"
kubectl --kubeconfig "$KC" config set-credentials pod-reader-user --token="$TOKEN"
kubectl --kubeconfig "$KC" config set-context --current --user=pod-reader-user

# 3) 신원 확인
kubectl --kubeconfig "$KC" auth whoami
```
```
ATTRIBUTE   VALUE
Username    system:serviceaccount:step16:pod-reader
Groups      [system:serviceaccounts system:serviceaccounts:step16 system:authenticated]
```

이제 실제 권한을 봅니다.
```bash
kubectl --kubeconfig "$KC" get pods    -n step16     # 성공
kubectl --kubeconfig "$KC" get secrets -n step16     # Forbidden
kubectl --kubeconfig "$KC" delete pod demo -n step16 # Forbidden
kubectl --kubeconfig "$KC" get nodes                 # 성공 (ClusterRole)
kubectl --kubeconfig "$KC" get pods    -n default    # Forbidden (Role 은 step16 전용)
```
**실제 출력**
```
NAME   READY   STATUS    RESTARTS   AGE
demo   1/1     Running   0          40s

Error from server (Forbidden): secrets is forbidden: User "system:serviceaccount:step16:pod-reader" cannot list resource "secrets" in API group "" in the namespace "step16"

Error from server (Forbidden): pods "demo" is forbidden: User "system:serviceaccount:step16:pod-reader" cannot delete resource "pods" in API group "" in the namespace "step16"

NAME                  STATUS   ROLES           AGE   VERSION
learn-control-plane   Ready    control-plane   35m   v1.36.1
learn-worker          Ready    <none>          34m   v1.36.1
learn-worker2         Ready    <none>          34m   v1.36.1

Error from server (Forbidden): pods is forbidden: User "system:serviceaccount:step16:pod-reader" cannot list resource "pods" in API group "" in the namespace "default"
```

**정확히 설계한 대로**: pod 는 보되 secret 은 못 보고, 삭제도 못 하며, 다른 네임스페이스도 못 봅니다. 노드는 ClusterRole 덕에 봅니다.

> ⚠️ **함정(직접 겪음)**: `kubectl --token=... -s <server>` 만으로는 신원이 안 바뀔 수 있습니다. 현재 kubeconfig 의 **클라이언트 인증서로 폴백**되어 관리자 권한으로 요청이 나가버립니다(그러면 secret 도 보임!). 위처럼 **토큰만 가진 별도 kubeconfig 사용자** 로 컨텍스트를 바꿔야 진짜 그 신원으로 테스트됩니다. `kubectl auth whoami` 로 항상 확인하세요.

---

## 6. 팁 / 함정

- ⚠️ **토큰 테스트가 관리자로 폴백.** `auth whoami` 로 실제 신원부터 확인. (5절)
- ⚠️ **Role 은 네임스페이스 전용.** 다른 네임스페이스 권한이 필요하면 각 ns 에 RoleBinding 을 만들거나 ClusterRole+ClusterRoleBinding.
- ⚠️ **ClusterRole 을 RoleBinding 으로** 바인딩하면, 그 ClusterRole 의 권한이 **해당 네임스페이스에만** 적용됩니다(유용한 패턴). ClusterRoleBinding 으로 바인딩하면 전역.
- ⚠️ **`resources` 이름은 복수형/소문자.** `pods`(O), `Pod`(X). 하위 리소스는 `pods/log`, `pods/exec` 처럼.
- ⚠️ **시스템 ClusterRole 수정 금지.** 실습용은 `step16-` 접두사 + 사후 삭제.
- 💡 최소 권한: `verbs: ["*"]`, `resources: ["*"]` 를 피하고 딱 필요한 동사/리소스만. 읽기 전용이면 `get,list,watch`.
- 💡 권한 감사: `kubectl auth can-i --list --as=<subject>` 로 특정 신원의 전체 권한을 덤프.

---

## 7. 정리표

| 하고 싶은 것 | 필요한 것 |
|---|---|
| 파드/도구에 신원 부여 | ServiceAccount |
| 한 네임스페이스 안 권한 | Role + RoleBinding |
| 클러스터 스코프 리소스(nodes 등) | ClusterRole + ClusterRoleBinding |
| ClusterRole 권한을 특정 ns 에만 | ClusterRole + **RoleBinding** |
| 권한 점검(빠름) | `kubectl auth can-i ... --as=` |
| 실제 권한 확인 | `kubectl create token` + 토큰 전용 kubeconfig |

---

## 8. 연습 과제

`challenge.md` 참고.

---

## 9. 정리 (전역 리소스 삭제 필수)

```bash
# 네임스페이스
kubectl delete namespace step16

# ⚠️ 전역(클러스터) 리소스는 네임스페이스 삭제로 안 지워짐 — 명시적으로 삭제!
kubectl delete clusterrolebinding step16-node-reader-binding
kubectl delete clusterrole        step16-node-reader
```

> 시스템 기본 ClusterRole 은 그대로 두고, `step16-` 접두사 리소스만 지웁니다.

---

**다음 단계 →** [Step 17 — Helm](../step-17-helm/index.md)

---

## 실습 파일

이 스텝의 매니페스트는 번호 순서대로 적용합니다. `00-namespace.yaml` 로 실습 공간을 만들고 → `10-serviceaccounts.yaml` 로 **신원**(pod-reader / no-access)을 만든 뒤 → `20-role-and-binding.yaml` 로 네임스페이스 범위 **권한**을 부여하고 → `30-clusterrole-and-binding.yaml` 로 클러스터 범위 권한(nodes)까지 얹습니다. `commands.sh` 는 이 네 파일의 적용부터 임퍼소네이션 점검, 실제 토큰 검증, 정리(삭제)까지를 한 번에 재현하는 스크립트입니다.

### manifests/00-namespace.yaml

- 2절에서 가장 먼저 적용하는 파일로, 실습 리소스를 담을 `step16` 네임스페이스를 만듭니다.
- `metadata.name: step16` 이 이후 모든 것의 기준입니다. Role/RoleBinding/ServiceAccount 가 전부 이 이름을 `namespace:` 로 참조하고, 임퍼소네이션 주체 문자열 `system:serviceaccount:step16:pod-reader` 의 가운데 토큰도 이 이름입니다.
- `labels` 의 `course: k8s-learn`, `step: "16"` 은 동작에 영향을 주지 않는 식별용 라벨입니다. `step: "16"` 을 따옴표로 감싼 이유는 라벨 값이 반드시 문자열이어야 하기 때문입니다(따옴표를 빼면 숫자로 파싱되어 거부됩니다).
- 이 네임스페이스를 지우면(9절 `kubectl delete namespace step16`) 그 안의 SA/Role/RoleBinding 은 함께 사라지지만, **ClusterRole/ClusterRoleBinding 은 남습니다.** 반드시 별도로 삭제하세요.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/10-serviceaccounts.yaml

- 두 개의 ServiceAccount, 즉 **신원(인증 주체)** 을 만듭니다. 아직 권한은 하나도 없습니다 — 권한은 다음 파일에서 붙습니다.
- `pod-reader` 는 이 스텝의 주인공으로, 20/30번 매니페스트에서 pods 읽기 + nodes 읽기 권한을 받습니다.
- `no-access` 는 **대조군**입니다. 아무 Binding 도 걸지 않아, `kubectl auth can-i list pods -n step16 --as=system:serviceaccount:step16:no-access` 가 `no` 로 나옵니다. "RBAC 의 기본값은 전부 거부"(0절)라는 문장을 눈으로 확인시키는 장치입니다.
- 파일 하나에 `---` 로 두 오브젝트를 이어 붙였습니다. `kubectl apply -f` 는 한 파일 안의 여러 문서를 순서대로 적용합니다.
- ServiceAccount 의 신원 문자열은 항상 `system:serviceaccount:<네임스페이스>:<이름>` 형식이라는 점을 기억하세요. 5절의 `auth whoami` 출력과 정확히 일치합니다.

```yaml file="./manifests/10-serviceaccounts.yaml"
```

### manifests/20-role-and-binding.yaml

- 2절 "최소 권한 설계" 의 실체입니다. Role(권한 묶음)과 RoleBinding(부여)을 한 파일에 담았습니다.
- Role `pod-reader-role` 의 규칙은 `apiGroups: [""]` + `resources: ["pods"]` + `verbs: ["get", "list", "watch"]` 단 하나뿐입니다. `apiGroups: [""]`(빈 문자열)은 core 그룹으로 pods·services·secrets 를 모두 포함하지만, `resources` 에 `pods` 만 적었으므로 **secrets 는 접근 대상이 아닙니다.** 3절에서 `get secrets` 가 `no` 로 나오는 이유가 바로 이 한 줄입니다.
- `verbs` 에 `delete` 가 없습니다. 그래서 `delete pods` 도 `no` 입니다. 읽기 전용 권한은 이렇게 `get,list,watch` 세 동사로 표현합니다.
- RoleBinding `pod-reader-binding` 의 `subjects` 는 `kind: ServiceAccount` / `name: pod-reader` / `namespace: step16` 세 필드가 **모두** 맞아야 합니다. 특히 `namespace` 를 빠뜨리면 엉뚱한 주체를 가리켜 "권한을 줬는데 Forbidden" 이 됩니다(challenge 과제 4의 1번 원인).
- `roleRef.kind: Role` 이므로 이 권한은 **step16 네임스페이스 안에서만** 유효합니다. `kubectl auth can-i list pods -n default --as=...` 가 `no` 인 이유입니다.
- `roleRef` 는 생성 후 **변경 불가**입니다. 다른 Role 을 가리키게 하려면 RoleBinding 을 지우고 다시 만들어야 합니다.

```yaml file="./manifests/20-role-and-binding.yaml"
```

### manifests/30-clusterrole-and-binding.yaml

- 4절에서 적용합니다. 노드(nodes)는 네임스페이스에 매이지 않는 **클러스터 스코프** 리소스라 Role/RoleBinding 으로는 절대 접근 권한을 줄 수 없고, ClusterRole + ClusterRoleBinding 이 필요합니다.
- ClusterRole `step16-node-reader` 의 규칙은 `resources: ["nodes"]`, `verbs: ["get", "list"]` — 읽기 전용 노드 뷰어입니다. `watch` 조차 빼서 최소 권한을 지켰습니다.
- ClusterRoleBinding `step16-node-reader-binding` 이 이 ClusterRole 을 `pod-reader` SA 에 **전역으로** 부여합니다. 그래서 5절에서 토큰 사용자가 `get nodes` 는 성공하지만, pods 는 여전히 step16 에서만 봅니다(권한의 출처가 다르기 때문).
- 같은 ClusterRole 을 **RoleBinding** 으로 걸면 권한 범위가 그 네임스페이스로 좁혀집니다(6절 팁, challenge 과제 3). 여기서는 nodes 가 클러스터 스코프라 그 방식이 통하지 않습니다.
- ⚠️ **주의**: 이 두 오브젝트는 클러스터 전역 리소스라 `kubectl delete namespace step16` 으로 사라지지 않습니다. 9절처럼 `kubectl delete clusterrolebinding step16-node-reader-binding` 과 `kubectl delete clusterrole step16-node-reader` 를 반드시 따로 실행하세요. 이름의 `step16-` 접두사는 "내가 만든 실습용" 이라는 표식이며, 시스템 기본 ClusterRole(`view`/`edit`/`admin`/`cluster-admin`)은 절대 수정하지 않습니다.

```yaml file="./manifests/30-clusterrole-and-binding.yaml"
```

### commands.sh

- 2절~9절 전체 흐름을 순서대로 재현하는 스크립트입니다. 네 개의 매니페스트를 적용한 뒤 임퍼소네이션 점검 → 실제 토큰 검증 → 정리까지 한 번에 수행합니다.
- `set -euo pipefail` 로 중간에 실패하면 즉시 중단합니다. 다만 3번 블록의 `kubectl ... || true` 는 **Forbidden 이 정상 기대 결과**이기 때문에(403 이면 kubectl 이 0이 아닌 코드로 종료) 스크립트가 죽지 않도록 일부러 붙여 둔 것입니다. 이 `|| true` 가 없으면 첫 Forbidden 에서 스크립트가 그대로 끝나 버립니다.
- `cd "$(dirname "$0")"` 덕분에 `manifests/...` 상대 경로가 항상 이 파일 기준으로 해석됩니다. 어느 디렉터리에서 실행해도 동작합니다. `export PATH="/opt/homebrew/bin:$PATH"` 는 Homebrew(Apple Silicon macOS) 로 설치한 `kubectl` 을 찾기 위한 로컬 환경 전제입니다.
- 1번 블록은 테스트 대상까지 만듭니다. `kubectl run demo -n step16 --image=nginx:1.27-alpine` 과 `kubectl create secret generic app-secret -n step16 --from-literal=password=s3cr3t` 로 "볼 수 있어야 하는 것(pod)" 과 "보면 안 되는 것(secret)" 을 각각 준비하고, `kubectl wait -n step16 --for=condition=ready pod/demo --timeout=60s` 로 파드가 Ready 가 될 때까지 기다립니다. 세 명령 모두 `-n step16` 이 붙어 있다는 점에 주의하세요 — 이 리소스들이 Role 이 적용되는 바로 그 네임스페이스에 있어야 실습이 성립합니다.
- 2번 블록은 `--as=$SA` 임퍼소네이션 점검이며, 각 줄 끝 주석(`# yes`, `# no`)이 기대 답입니다. 마지막 줄은 대조군 `no-access` SA 로 `no` 가 나와야 정상입니다.
- 3번 블록이 이 스텝의 핵심 함정 대응입니다. `kubectl config view --raw --minify > "$KC"` 로 현재 컨텍스트만 복사한 뒤, `set-credentials` + `set-context --current --user=pod-reader-user` 로 **토큰만 가진 사용자** 로 바꿉니다. 이렇게 하지 않으면 관리자 클라이언트 인증서로 폴백되어 secret 까지 보여 실습이 무의미해집니다(5절 함정). `auth whoami` 로 신원부터 확인하는 이유입니다.
- ⚠️ 4번 블록은 **파괴적**입니다. `kubectl delete namespace step16` 과 전역 리소스 삭제, `/tmp/step16-kc.yaml` 제거까지 수행하므로, 중간 상태를 직접 관찰하고 싶다면 4번 블록을 주석 처리하거나 위쪽 블록만 골라서 실행하세요.

```bash file="./commands.sh"
```
