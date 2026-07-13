# Step 05 연습 과제

> `step05` 네임스페이스에서 진행합니다. 시작 전 `kubectl apply -f manifests/deployment.yaml` 로 web Deployment(replicas=4)를 준비하세요.

---

### 과제 1. 자가 치유 관찰

파드 하나를 강제로 지운 뒤, ReplicaSet 이 몇 초 안에 다시 4개를 맞추는지 확인하세요. 어떤 오브젝트가 이 복구를 담당하나요?

<details>
<summary>해답</summary>

```bash
kubectl get pods -n step05
kubectl delete pod -n step05 <파드이름-하나>
kubectl get pods -n step05 -w      # 새 파드가 생겨 다시 4개 (Ctrl+C 로 종료)
```
복구 담당은 **ReplicaSet** 입니다. Deployment 가 아니라 그 아래 ReplicaSet 이 "desired 개수"를 감시하며 부족분을 즉시 생성합니다.
</details>

---

### 과제 2. 무중단 확인용 카운트

`maxSurge: 1`, `maxUnavailable: 1`, `replicas=4` 일 때, 롤링 업데이트 도중 **available 파드가 최소 몇 개** 보장되는지 답하고, 매니페스트로 실제 롤아웃하며 `kubectl get deploy web -n step05 -w` 로 AVAILABLE 값이 그 아래로 떨어지지 않는지 관찰하세요.

<details>
<summary>해답</summary>

최소 **3개**(= replicas − maxUnavailable = 4 − 1)가 보장됩니다.

```bash
kubectl get deploy web -n step05 -w &      # AVAILABLE 관찰
kubectl set env deployment/web -n step05 ROLL=1
# AVAILABLE 컬럼이 3 아래로 떨어지지 않음
```
</details>

---

### 과제 3. change-cause 남기기

버전 라벨을 바꾸는 롤아웃을 하되, `rollout history` 에 의미 있는 CHANGE-CAUSE 가 남도록 하세요.

<details>
<summary>해답</summary>

```bash
kubectl set env deployment/web -n step05 APP_VERSION=v3
kubectl annotate deployment/web -n step05 \
  kubernetes.io/change-cause="bump APP_VERSION to v3" --overwrite
kubectl rollout history deployment/web -n step05
```
CHANGE-CAUSE 는 `kubernetes.io/change-cause` 어노테이션에서 옵니다. 롤아웃을 유발하는 변경과 **같은 시점에** 어노테이션을 걸어야 그 리비전에 붙습니다.
</details>

---

### 과제 4. 고장 → 진단 → 롤백 (핵심)

존재하지 않는 이미지로 업데이트해 롤아웃을 멈추게 한 뒤, (1) 왜 멈췄는지 파드 이벤트로 진단하고 (2) 직전 정상 리비전으로 롤백하세요.

<details>
<summary>해답</summary>

```bash
# 1) 고장 유발
kubectl set image deployment/web nginx=nginx:9.9-nope -n step05
kubectl rollout status deployment/web -n step05 --timeout=20s   # timed out

# 2) 진단
kubectl get pods -n step05                    # ImagePullBackOff
kubectl describe pod -n step05 -l pod-template-hash=<불량RS해시> | grep -iE "Failed|Back-off"

# 3) 롤백
kubectl rollout undo deployment/web -n step05
kubectl rollout status deployment/web -n step05    # successfully rolled out
```
구버전 파드는 `maxUnavailable` 덕분에 계속 살아있었으므로 서비스는 끊기지 않았습니다.
</details>

---

### 과제 5. 셀렉터는 왜 못 바꾸나

실행 중인 `web` Deployment 의 `spec.selector.matchLabels` 에 `tier: frontend` 를 추가해 apply 하면 어떻게 되나요? 이유와, 굳이 바꾸려면 어떻게 해야 하는지 답하세요.

<details>
<summary>해답</summary>

```bash
kubectl apply -f manifests/bad-selector.yaml
# The Deployment "web" is invalid: spec.selector: ... field is immutable
```
셀렉터는 **불변**입니다. 바꾸면 소유 파드 판정 기준이 흔들려 기존 파드가 고아가 되기 때문입니다. 정말 바꿔야 하면 **Deployment 를 삭제 후 재생성**하는 방법뿐입니다:
```bash
kubectl delete deployment web -n step05
kubectl apply -f manifests/bad-selector.yaml   # 이제 새로 생성되므로 성공
```
</details>
