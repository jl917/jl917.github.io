# Step 20 — 보안: SecurityContext와 Pod Security Standards

## 학습 목표
- **SecurityContext**의 핵심 필드(runAsNonRoot / runAsUser / readOnlyRootFilesystem / allowPrivilegeEscalation / capabilities / seccomp)를 이해하고 적용한다.
- **Pod Security Standards(PSS)**와 **Pod Security Admission(PSA)**로 네임스페이스 단위 정책을 강제한다. `enforce=restricted` 네임스페이스가 **위반 파드 생성을 거부**하는 것을 실증한다.
- privileged 파드가 왜 위험한지 이해한다.
- 이미지 보안(`latest` 금지, 취약점 스캔)과 시크릿 관리 원칙을 잡는다.

## 선행 지식
- Step 03(Pod), Step 07(Secret), Step 16(RBAC). RBAC가 "누가 API를 호출할 수 있나"라면, 이 스텝은 "파드가 노드에서 무슨 짓을 할 수 있나"입니다.

## 소요 시간
- 35분

---

## 1. 왜 컨테이너 보안인가

컨테이너는 격리되어 있다고 하지만, 커널은 호스트와 **공유**합니다. 컨테이너가 root로 돌고 권한이 넓으면, 커널 취약점이나 마운트 실수 하나로 **호스트 전체를 장악**당할 수 있습니다. 보안의 원칙은 하나입니다: **최소 권한**. 파드에 꼭 필요한 권한만 주고 나머지는 전부 뺍니다.

방어선은 두 층입니다.
- **SecurityContext** — 파드/컨테이너 스펙에 직접 쓰는 권한 설정 (작성자가 지킴)
- **Pod Security Admission** — 네임스페이스 정책으로 위반을 **강제 차단** (클러스터가 지킴)

---

## 2. SecurityContext — 최소 권한으로 파드 가두기

핵심 필드:

| 필드 | 뜻 | 권장 |
|---|---|---|
| `runAsNonRoot: true` | root(uid 0) 실행 금지 | 필수 |
| `runAsUser: 10001` | 지정한 비특권 UID로 실행 | 권장 |
| `readOnlyRootFilesystem: true` | 루트FS 읽기 전용(침해 후 변조 방지) | 권장 |
| `allowPrivilegeEscalation: false` | setuid 등으로 권한 상승 금지 | 필수 |
| `capabilities.drop: ["ALL"]` | 모든 리눅스 capability 제거 | 필수 |
| `seccompProfile.type: RuntimeDefault` | 위험한 syscall 차단 | 필수 |
| `privileged: true` | **호스트 커널 완전 접근 — 절대 금지** | 금지 |

이 모두를 만족하는 파드(`manifests/good-pod.yaml`, 전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다):

```yaml
spec:
  securityContext:              # 파드 레벨 (모든 컨테이너에 적용)
    runAsNonRoot: true
    runAsUser: 10001
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: app
      image: busybox:1.36
      securityContext:          # 컨테이너 레벨 (더 좁게)
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      volumeMounts:
        - name: scratch         # 루트FS 가 읽기 전용이므로 쓰기는 emptyDir 로
          mountPath: /data
  volumes:
    - name: scratch
      emptyDir: {}
```

**실제 실행 결과**:
```bash
kubectl apply -f manifests/good-pod.yaml
kubectl logs good -n step20 --tail=1
```
```
running as uid=10001, rootfs is read-only
```

`readOnlyRootFilesystem`을 실증 — 루트에 쓰기를 시도하면 실패합니다:
```bash
kubectl exec good -n step20 -- sh -c 'echo x > /root_test.txt; id'
```
```
sh: can't create /root_test.txt: Read-only file system
uid=10001 gid=10001 groups=10001
```

> **함정 — 포트 80 바인딩**: `runAsNonRoot`로 돌리면 nginx가 80 포트를 못 엽니다(1024 미만은 특권 포트). 그래서 보안 이미지는 8080 같은 상위 포트를 쓰거나 `NET_BIND_SERVICE` capability만 예외로 add합니다. 이 실습이 busybox를 쓴 이유입니다.

---

## 3. Pod Security Standards — 3단계 정책

작성자가 SecurityContext를 빠뜨릴 수 있으니, **클러스터가 강제**합니다. 쿠버네티스는 세 단계 표준을 내장합니다.

| 표준 | 뜻 |
|---|---|
| **privileged** | 제한 없음 (아무거나 허용) |
| **baseline** | 알려진 위험(privileged, hostNetwork 등)만 차단 |
| **restricted** | 최소 권한 강제 (위 2장의 필드를 모두 요구) |

이걸 **네임스페이스 레이블**로 적용합니다(PSA). 각 표준을 세 가지 모드로 걸 수 있습니다:
- `enforce` — 위반 파드 **생성 거부**
- `warn` — 생성은 허용하되 **경고 메시지** 출력 (사용자에게)
- `audit` — 감사 로그에 기록

`manifests/namespace.yaml`:
```yaml
metadata:
  name: step20
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/audit: restricted
```

---

## 4. 위반 파드 생성 거부 실증

일반 nginx 파드(`manifests/bad-pod.yaml`) — SecurityContext가 하나도 없습니다. `restricted` 네임스페이스에 넣으면:

```bash
kubectl apply -f manifests/bad-pod.yaml
```
**실제 출력** (거부됨):
```
Error from server (Forbidden): error when creating "manifests/bad-pod.yaml":
pods "bad" is forbidden: violates PodSecurity "restricted:latest":
  allowPrivilegeEscalation != false (container "nginx" must set securityContext.allowPrivilegeEscalation=false),
  unrestricted capabilities (container "nginx" must set securityContext.capabilities.drop=["ALL"]),
  runAsNonRoot != true (pod or container "nginx" must set securityContext.runAsNonRoot=true),
  seccompProfile (pod or container "nginx" must set securityContext.seccompProfile.type to "RuntimeDefault" or "Localhost")
```

에러 메시지가 **정확히 무엇을 고쳐야 하는지** 알려줍니다. 이게 PSA의 큰 장점입니다.

privileged 파드(`manifests/privileged-pod.yaml`)는 더 강하게 거부됩니다:
```
violates PodSecurity "restricted:latest": privileged (container "nginx" must not set securityContext.privileged=true), ...
```

반면 `good` 파드는 통과합니다:
```
pod/good created
NAME   READY   STATUS    RESTARTS   AGE
good   1/1     Running   0          8s
```

---

## 5. 중요한 함정 — Deployment는 만들어지고, 파드만 거부된다

PSA는 **파드 생성 시점**에 검사합니다. Deployment를 apply하면 **Deployment 자체는 만들어지고**(경고만 출력), 그 아래 ReplicaSet이 파드를 만들려다 거부됩니다. 결과는 `READY 0/1`인 유령 Deployment입니다.

```bash
kubectl apply -f bad-deploy.yaml
```
```
Warning: would violate PodSecurity "restricted:latest": allowPrivilegeEscalation != false ...
deployment.apps/bad-deploy created         # ← 생성은 됨!
```
```bash
kubectl get deploy bad-deploy -n step20
```
```
NAME         READY   UP-TO-DATE   AVAILABLE   AGE
bad-deploy   0/1     0            0           4s
```
거부 사유는 **ReplicaSet 이벤트**에 있습니다:
```bash
kubectl get events -n step20 --field-selector reason=FailedCreate
```
```
Warning  FailedCreate  replicaset/bad-deploy-84988d55c9  Error creating: pods "..." is forbidden:
  violates PodSecurity "restricted:latest": ...
```

> **교훈**: `kubectl apply`가 성공(`created`)해도 파드가 안 뜰 수 있습니다. Deployment의 `READY 0/1`을 보면 반드시 **ReplicaSet 이벤트**를 확인하세요. `apply`가 통과한 것과 파드가 뜬 것은 다릅니다(이 코스가 반복해서 강조하는 지점).

---

## 6. privileged 파드가 왜 위험한가

`privileged: true`는 컨테이너에 다음을 줍니다:
- 호스트의 **모든 장치**(`/dev`) 접근
- **모든 capability** 보유 (CAP_SYS_ADMIN 등)
- seccomp/AppArmor 필터 우회

즉 컨테이너 탈옥으로 **호스트 파일시스템 마운트 → 노드 장악 → 클러스터 전체 장악**의 발판이 됩니다. CNI/스토리지 플러그인 같은 극소수 시스템 컴포넌트 외에는 절대 쓰지 마세요. baseline 표준도 이미 privileged를 금지합니다.

---

## 7. 이미지 보안

- **`latest` 태그 금지**: `nginx:latest`는 배포마다 내용이 바뀔 수 있어 재현 불가능하고, 롤백 대상이 모호합니다. **불변 태그**(`nginx:1.27-alpine`)나 다이제스트(`nginx@sha256:...`)를 쓰세요. 이 코스가 모든 이미지에 버전 태그를 붙인 이유입니다.
- **취약점 스캔**: Trivy, Grype, Clair 등으로 이미지의 CVE를 CI에서 스캔합니다. `trivy image nginx:1.27-alpine` 한 줄이면 알려진 취약점 목록이 나옵니다.
- **최소 베이스 이미지**: `alpine`, `distroless`처럼 셸·패키지 매니저가 없는 이미지는 공격 표면이 작습니다.
- **imagePullPolicy와 서명**: 프로덕션에서는 이미지 서명 검증(cosign/Sigstore)과 신뢰된 레지스트리만 허용하는 admission(예: Kyverno)을 겁니다.

---

## 8. 시크릿 관리 원칙

Secret은 **암호화가 아니라 base64 인코딩**일 뿐입니다(`manifests/secret-demo.yaml`로 파드에 주입 실습). 원칙:

- **Secret YAML을 git에 커밋하지 말 것**. base64는 누구나 디코딩합니다.
- **etcd 저장 암호화**(EncryptionConfiguration)를 켜서 저장 시점 암호화.
- **RBAC로 Secret 접근 제한**(Step 16). Secret을 읽을 수 있는 SA를 최소화.
- **외부 시크릿 매니저** 연동: External Secrets Operator + Vault/AWS Secrets Manager로 실제 비밀은 클러스터 밖에 두고 동기화.
- **환경변수보다 볼륨 마운트**가 나은 경우가 많습니다(env는 `/proc/<pid>/environ`, 로그, `describe`로 새기 쉬움).

```bash
kubectl apply -f manifests/secret-demo.yaml
kubectl logs secret-consumer -n step20 --tail=1     # secret len=20
```
파드는 값을 쓰지만, 소스 YAML에는 평문이 보입니다 — 그래서 커밋하면 안 됩니다.

---

## 팁과 함정

- **함정 1 — `apply` 성공 ≠ 파드 실행**: enforce 네임스페이스에서 Deployment는 만들어지고 파드만 거부됩니다. `READY 0/N`이면 ReplicaSet 이벤트를 보세요.
- **함정 2 — restricted에서 nginx가 안 뜸**: 포트 80 바인딩·`/var/cache/nginx` 쓰기 때문입니다. 상위 포트 + emptyDir 마운트 + (필요시) `NET_BIND_SERVICE`만 add로 해결.
- **함정 3 — 파드/컨테이너 레벨 혼동**: `runAsNonRoot`·`seccompProfile`·`fsGroup`은 **파드** `securityContext`, `readOnlyRootFilesystem`·`capabilities`·`allowPrivilegeEscalation`은 **컨테이너** `securityContext`. 위치를 틀리면 무시됩니다.
- **팁 — 먼저 `warn`으로 시작**: 기존 네임스페이스에 갑자기 `enforce=restricted`를 걸면 배포가 멈춥니다. `warn`/`audit`로 먼저 위반을 파악하고, 워크로드를 고친 뒤 `enforce`로 승격하세요.
- **팁 — 클러스터 기본값**: `--admission-control-config-file`로 레이블 없는 네임스페이스의 기본 정책을 정할 수 있습니다.

---

## 정리표

| 항목 | 값/명령 |
|---|---|
| root 금지 | `securityContext.runAsNonRoot: true` (파드 레벨) |
| 권한 상승 금지 | `allowPrivilegeEscalation: false` (컨테이너 레벨) |
| capability 제거 | `capabilities.drop: ["ALL"]` |
| syscall 필터 | `seccompProfile.type: RuntimeDefault` |
| 루트FS 잠금 | `readOnlyRootFilesystem: true` + 쓰기용 emptyDir |
| 네임스페이스 정책 | `pod-security.kubernetes.io/enforce: restricted` |
| 정책 모드 | `enforce`(거부) / `warn`(경고) / `audit`(감사) |
| 이미지 | 불변 태그, 취약점 스캔, distroless |

---

## 연습 과제

[challenge.md](challenge.md)의 4개 과제.

---

## 다음 단계

→ [Step 21 — 가용성과 중단 관리](../step-21-disruptions/index.md): PodDisruptionBudget과 PriorityClass, cordon/drain으로 노드 유지보수 중에도 서비스를 지킵니다.

---

## 실습 파일

이 스텝의 실습은 먼저 `manifests/namespace.yaml`로 **enforce=restricted** 네임스페이스 `step20`을 만드는 것에서 시작합니다. 그다음 정책을 위반하는 `bad-pod.yaml`·`privileged-pod.yaml`을 일부러 적용해 **거부되는 것을 관찰**하고, 정책을 만족하는 `good-pod.yaml`이 정상 기동하는 것을 확인합니다. 마지막으로 `secret-demo.yaml`로 시크릿 주입까지 본 뒤, 이 모든 흐름을 한 번에 재현하는 `commands.sh`로 전체를 검증합니다.

### manifests/namespace.yaml

- 이 스텝의 모든 실습이 벌어지는 무대인 `step20` 네임스페이스를 만듭니다. **가장 먼저 적용해야 하는 파일**입니다. 이 네임스페이스가 없으면 나머지 매니페스트는 `namespace: step20`을 찾지 못해 전부 실패합니다.
- 3장에서 설명한 PSA 레이블 세 개를 모두 겁니다. `pod-security.kubernetes.io/enforce: restricted`가 **위반 파드의 생성을 실제로 거부**하는 유일한 레이블이고, `warn`은 사용자 터미널에 경고를, `audit`은 API 서버 감사 로그에 기록을 남깁니다(둘 다 생성 자체는 막지 않습니다).
- `pod-security.kubernetes.io/enforce-version: latest`는 "현재 클러스터가 아는 최신 버전의 restricted 정의"를 쓰겠다는 뜻입니다. 4장의 에러 메시지에 나오는 `violates PodSecurity "restricted:latest"`의 `latest`가 바로 이 값입니다. 운영에서는 클러스터 업그레이드 때 정책이 조용히 엄격해지는 것을 막기 위해 `v1.29`처럼 버전을 고정하기도 합니다.
- `course: k8s-learn`, `step: "20"` 레이블은 정책과 무관한 정리용 태그입니다.

```yaml file="./manifests/namespace.yaml"
```

### manifests/good-pod.yaml

- 2장의 표에 나온 필드를 **전부 만족**하도록 만든 모범 파드입니다. `restricted` 네임스페이스에서 유일하게 통과하는 파드이므로, 위반 파드들을 본 뒤 "그럼 어떻게 써야 하나"의 정답으로 적용합니다.
- 파드 레벨 `securityContext`에는 `runAsNonRoot: true`, `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `seccompProfile.type: RuntimeDefault`를, 컨테이너 레벨에는 `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: ["ALL"]`을 둡니다. 함정 3에서 강조한 **"어느 필드가 어느 레벨인가"의 정답 예시**가 바로 이 파일입니다.
- 이미지가 nginx가 아니라 `busybox:1.36`인 이유가 핵심입니다. `runAsNonRoot`로 uid 10001이 되면 **1024 미만 특권 포트(80)를 열 수 없기 때문**에, 포트 바인딩이 필요 없는 busybox를 골랐습니다.
- `readOnlyRootFilesystem: true`이므로 컨테이너는 `/`에 한 글자도 쓸 수 없습니다. 그래서 루프가 로그를 쌓는 경로 `/data`에 `emptyDir` 볼륨 `scratch`를 마운트했습니다. `while true; do echo "$(date +%T) tick" >> /data/log.txt; sleep 5; done` 부분이 **"쓰기가 필요한 곳만 볼륨으로 뚫는다"**는 원칙의 실물입니다.
- 시작 시 출력하는 `running as uid=$(id -u), rootfs is read-only` 한 줄이 2장의 `kubectl logs good -n step20 --tail=1` 결과와 대응합니다.

```yaml file="./manifests/good-pod.yaml"
```

### manifests/bad-pod.yaml

- **일부러 정책을 위반하도록 만든 파일**입니다. 4장에서 "거부되는 모습"을 보기 위해 적용하며, `Error from server (Forbidden)`이 뜨는 것이 **정상 결과**입니다.
- 위반 요소는 사실상 "아무것도 안 쓴 것" 그 자체입니다. `securityContext` 블록이 통째로 없어서 ① root(uid 0)로 실행되고(`runAsNonRoot != true`), ② `allowPrivilegeEscalation`이 기본값 `true`이며, ③ capability가 하나도 드롭되지 않았고, ④ `seccompProfile`이 지정되지 않았습니다. PSA는 이 네 가지를 **한 번에 모두** 에러 메시지로 열거해 줍니다.
- `containerPort: 80`은 위반 사유는 아니지만, `runAsNonRoot`를 지키는 순간 이 nginx가 왜 80을 못 여는지(함정 2) 생각해 보게 하는 장치입니다.
- 흔한 오해: 이 파일이 "문법적으로 틀린" 게 아닙니다. **정책 없는 네임스페이스에서는 아무 문제 없이 뜹니다.** 같은 YAML의 운명이 네임스페이스 레이블 하나로 갈린다는 점이 학습 포인트입니다(과제 2에서 `enforce`를 떼면 실제로 생성됩니다).

```yaml file="./manifests/bad-pod.yaml"
```

### manifests/privileged-pod.yaml

- 6장의 "privileged가 왜 위험한가"를 실증하는 **의도적 위험 파일**입니다. `bad-pod`보다 한 단계 더 나쁜 사례로, 역시 거부되는 것이 정상입니다.
- `securityContext.privileged: true` 한 줄이 컨테이너에 호스트의 모든 장치(`/dev`) 접근, 모든 capability(CAP_SYS_ADMIN 포함), seccomp/AppArmor 우회를 한꺼번에 줍니다. 여기에 `allowPrivilegeEscalation: true`까지 명시해 권한 상승도 열어 둔 상태입니다.
- 거부 메시지가 `bad-pod`과 다릅니다. `privileged (container "nginx" must not set securityContext.privileged=true)`가 맨 앞에 추가로 붙습니다. `restricted`뿐 아니라 **한 단계 아래인 `baseline` 표준도 이미 privileged를 금지**하므로, 어떤 정책을 걸든 막히는 유형입니다.
- **주의**: 정책이 없는 네임스페이스(예: `default`)에서는 이 파드가 실제로 뜹니다. 호스트 커널 접근 권한을 가진 컨테이너가 생기는 것이므로, 실습이라도 `step20` 밖에서 적용하지 마세요.

```yaml file="./manifests/privileged-pod.yaml"
```

### manifests/secret-demo.yaml

- 8장의 시크릿 관리 원칙을 눈으로 확인하는 파일입니다. `Secret`과 그것을 소비하는 `Pod`를 `---`로 이어 붙인 두 개의 오브젝트로 되어 있습니다.
- `stringData`에 `DB_PASSWORD: "s3cr3t-do-not-commit"`을 **평문으로** 적었습니다. API 서버가 이를 base64로 인코딩해 저장할 뿐, 암호화하지는 않습니다. 값 자체가 "커밋하지 마라"라고 말하고 있는 이유입니다 — 이 파일은 **git에 올리면 안 되는 예시의 표본**입니다.
- `secret-consumer` 파드는 `env.valueFrom.secretKeyRef`로 시크릿을 환경변수에 주입한 뒤, `echo secret len=${#DB_PASSWORD}`로 **값이 아니라 길이만** 출력합니다(`secret len=20`). 로그에 비밀을 흘리지 않으면서 주입이 성공했음을 보여 주는 안전한 확인법입니다.
- 이 파드 역시 `runAsNonRoot`/`seccompProfile`(파드 레벨)과 `allowPrivilegeEscalation: false`/`readOnlyRootFilesystem: true`/`capabilities.drop: ["ALL"]`(컨테이너 레벨)을 모두 갖추고 있습니다. `restricted` 네임스페이스에서 돌려야 하므로 **시크릿 실습 파드도 예외 없이 정책을 지켜야 한다**는 점을 보여 줍니다.
- 8장이 지적하듯 env 주입은 `kubectl describe`나 `/proc/<pid>/environ`으로 새기 쉽습니다. 실무에서는 볼륨 마운트 방식을 우선 검토하세요.

```yaml file="./manifests/secret-demo.yaml"
```

### commands.sh

- 위 매니페스트 다섯 개(`namespace` → `bad-pod` → `privileged-pod` → `good-pod` → `secret-demo`)를 **강의 순서 그대로** 실행해 이 스텝 전체를 한 번에 재현하는 스크립트입니다. 본문에 인용된 "실제 출력"들이 모두 이 스크립트의 결과입니다. 경로는 `HERE="$(cd "$(dirname "$0")" && pwd)"`로 스크립트 위치를 기준 삼으므로 어느 디렉터리에서 실행해도 동작합니다.
- 4번째 줄 `set -uo pipefail`에서 **`-e`를 일부러 뺀 것**이 이 파일의 백미입니다. `bad-pod`/`privileged-pod`의 `kubectl apply`는 admission 거부로 **비정상 종료(exit code ≠ 0)**하는데, `-e`가 켜져 있으면 스크립트가 거기서 죽어 버립니다. 여기서는 **거부되는 것이 관찰 대상**이므로 계속 진행해야 합니다.
- 3번 단계에서 히어독으로 `/tmp/bad-deploy.yaml`을 즉석 생성해 5장의 함정을 재현합니다. `kubectl apply`는 `deployment.apps/bad-deploy created`로 **성공**하지만, `kubectl get deploy`는 `READY 0/1`이고 진짜 사유는 `kubectl get events -n step20 --field-selector reason=FailedCreate`에만 나옵니다. `sleep 4`는 ReplicaSet이 파드 생성을 시도해 이벤트가 찍힐 시간을 벌어 줍니다.
- `kubectl exec good -n step20 -- sh -c 'echo x > /root_test.txt; id' || true`는 `readOnlyRootFilesystem`을 실증합니다. `Read-only file system` 에러가 나는 게 **성공**이므로 `|| true`로 실패를 삼킵니다.
- **주의 — 마지막 줄이 파괴적입니다**: `kubectl delete namespace step20`이 실습 리소스를 전부 지웁니다. 결과를 천천히 살펴보려면 이 줄을 빼고 실행하고, 나중에 수동으로 지우세요. 또한 `sleep 8`/`sleep 6`은 로컬 kind/minikube 기준이라 이미지 풀이 느린 환경에서는 `kubectl logs`가 아직 준비되지 않았다는 에러를 낼 수 있습니다.
- 3번째 줄 `export PATH="/opt/homebrew/bin:$PATH"`는 macOS(Apple Silicon) + Homebrew 환경 전제입니다. 다른 환경이라면 `kubectl` 경로에 맞게 조정하세요.

```bash file="./commands.sh"
```
