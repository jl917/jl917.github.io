# Step 20 연습 과제 — 보안

> 실습 네임스페이스: `step20` (enforce=restricted). 끝나면 `kubectl delete ns step20`.

---

## 과제 1. nginx를 restricted에서 돌리기

`nginx:1.27-alpine`을 `restricted` 네임스페이스에서 실제로 뜨게 만드세요. (힌트: 80 포트 바인딩과 `/var/cache/nginx`, `/var/run` 쓰기가 걸림돌)

<details><summary>해답</summary>

nginx-unprivileged 이미지는 검증 목록에 없으므로, 공식 nginx로 하려면 상위 포트 + 필요한 쓰기 경로를 emptyDir로 + `NET_BIND_SERVICE`가 아니라 상위 포트를 씁니다. 최소한의 형태:
```yaml
spec:
  securityContext: {runAsNonRoot: true, runAsUser: 101, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: nginx
      image: nginx:1.27-alpine
      command: ["nginx","-g","daemon off;"]
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: {drop: ["ALL"]}
        readOnlyRootFilesystem: true
      volumeMounts:
        - {name: cache, mountPath: /var/cache/nginx}
        - {name: run, mountPath: /var/run}
        - {name: conf, mountPath: /etc/nginx/conf.d}
  volumes:
    - {name: cache, emptyDir: {}}
    - {name: run, emptyDir: {}}
    - {name: conf, emptyDir: {}}
```
실무에서는 `nginxinc/nginx-unprivileged`(8080 리슨, 비특권 설계)를 쓰는 게 정석입니다. 핵심 교훈: **restricted를 만족시키려면 앱이 애초에 비특권으로 설계돼야 한다.**
</details>

---

## 과제 2. warn만 걸었을 때의 차이

네임스페이스에서 `enforce` 레이블을 떼고 `warn=restricted`만 남기면, `bad-pod`는 어떻게 될까요? 예상하고 확인하세요.

<details><summary>해답</summary>

```bash
kubectl label ns step20 pod-security.kubernetes.io/enforce-              # enforce 제거
kubectl apply -f manifests/bad-pod.yaml
```
`Warning: ...` 메시지가 뜨지만 **파드는 생성됩니다**(`pod/bad created`). `warn`은 알려주기만 하고 막지 않습니다. 실습 후 다시 `enforce`를 거세요.
</details>

---

## 과제 3. 어느 필드가 어느 레벨인지

다음을 파드 레벨 / 컨테이너 레벨로 분류하세요: `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities`, `seccompProfile`, `allowPrivilegeEscalation`, `fsGroup`.

<details><summary>해답</summary>

- **파드 레벨** (`spec.securityContext`): `runAsNonRoot`, `seccompProfile`, `fsGroup` (`runAsUser`도 파드/컨테이너 양쪽 가능)
- **컨테이너 레벨** (`spec.containers[].securityContext`): `readOnlyRootFilesystem`, `capabilities`, `allowPrivilegeEscalation`

컨테이너 레벨은 파드 레벨을 덮어씁니다(더 좁게). 위치를 틀리면 무시됩니다.
</details>

---

## 과제 4. `apply` 성공인데 파드가 없다

동료가 "Deployment를 apply했는데 파드가 안 뜬다"며 도움을 요청합니다. `restricted` 네임스페이스일 때, **어디를** 봐야 원인을 알 수 있을까요?

<details><summary>해답</summary>

```bash
kubectl get deploy <name> -n <ns>            # READY 0/N 확인
kubectl get rs -n <ns>                        # ReplicaSet 확인
kubectl describe rs <rs-name> -n <ns>         # 또는:
kubectl get events -n <ns> --field-selector reason=FailedCreate
```
Deployment/ReplicaSet은 만들어지지만 파드 생성이 admission에서 막힙니다. 사유는 **ReplicaSet의 FailedCreate 이벤트**에 있습니다. `kubectl get pods`만 보면 "아무것도 없다"고 오해하기 쉽습니다.
</details>
