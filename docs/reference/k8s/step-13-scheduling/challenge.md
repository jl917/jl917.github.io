# Step 13 연습 과제 — 스케줄링 제어

모든 실습은 `step13` 네임스페이스에서. 노드 테인트는 **워커에만, 고유 키로, 실습 직후 원복**한다.

---

## 과제 1. required nodeAffinity로 Pending 만들고 진단하기

`kubernetes.io/os: windows`를 required nodeAffinity로 요구하는 파드를 만들어라(우리 노드는 모두 linux). 파드가 Pending이 되는 것을 확인하고, `describe`로 사유를 읽어라.

<details><summary>해답</summary>

```yaml
apiVersion: v1
kind: Pod
metadata: { name: q1-pending, namespace: step13 }
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
          - matchExpressions:
              - { key: kubernetes.io/os, operator: In, values: ["windows"] }
  containers:
    - { name: c, image: nginx:1.27-alpine, resources: { requests: { cpu: 10m, memory: 16Mi } } }
```

```bash
kubectl apply -f q1.yaml
kubectl get pod q1-pending -n step13          # STATUS: Pending
kubectl describe pod q1-pending -n step13 | grep -A3 Events
# -> "didn't match Pod's node affinity/selector"
```
required는 만족 노드가 없으면 영원히 Pending. preferred였다면 그냥 아무 노드에나 떴을 것이다.
</details>

---

## 과제 2. soft anti-affinity로 3개 흩기 (Pending 없이)

replicas=3인 Deployment를 만들되, 워커가 2개뿐이라 hard antiAffinity면 1개가 Pending이 된다. **soft**(preferred) podAntiAffinity로 바꿔 3개 모두 Running이면서 최대한 흩어지게 하라.

<details><summary>해답</summary>

```yaml
spec:
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            labelSelector: { matchLabels: { app: q2 } }
            topologyKey: kubernetes.io/hostname
```

```bash
kubectl get pod -n step13 -l app=q2 -o wide
# 3개 모두 Running. 2개는 각기 다른 워커, 3번째는 둘 중 하나에 얹힌다.
```
soft라서 "못 흩어도 배치는 한다". hard였다면 3번째가 Pending. 이것이 12-5 함정의 해법이다.
</details>

---

## 과제 3. 전용 노드 시뮬레이션 (taint + toleration + nodeSelector)

`learn-worker2`를 "배치 전용 노드"로 만들어라. 테인트 `batch=true:NoSchedule`를 걸고, toleration이 있는 파드만, 그리고 그 파드는 실제로 worker2에 뜨게 하라. **실습 후 반드시 테인트 제거.**

<details><summary>해답</summary>

```bash
kubectl taint nodes learn-worker2 batch=true:NoSchedule
```
```yaml
apiVersion: v1
kind: Pod
metadata: { name: q3-batch, namespace: step13 }
spec:
  nodeSelector: { kubernetes.io/hostname: learn-worker2 }
  tolerations:
    - { key: batch, operator: Equal, value: "true", effect: NoSchedule }
  containers:
    - { name: c, image: busybox:1.36, command: ["sleep","3600"], resources: { requests: { cpu: 10m, memory: 16Mi } } }
```
```bash
kubectl apply -f q3.yaml
kubectl get pod q3-batch -n step13 -o wide     # NODE: learn-worker2
# ★ 원복
kubectl taint nodes learn-worker2 batch=true:NoSchedule-
kubectl describe node learn-worker2 | grep -i taint    # <none> 확인
```
toleration만 있고 nodeSelector가 없으면 worker2로 "갈 수도" 있을 뿐 보장되진 않는다. toleration은 "허용"이지 "유인"이 아니다.
</details>

---

## 과제 4. topologySpread를 soft로 완화하기

13-7의 topo Deployment를 replicas=5로 늘리면 어떻게 되는가? `whenUnsatisfiable`을 `DoNotSchedule`과 `ScheduleAnyway`로 각각 두고 결과를 비교하라.

<details><summary>해답</summary>

- `DoNotSchedule`(하드): 워커 2개에 2/2까지 채운 뒤 5번째는 skew 위반이라 **Pending**.
- `ScheduleAnyway`(소프트): 5개 모두 Running. 최대한 균등(3/2)하게 배치하지만 Pending은 없다.

```bash
kubectl get pod -n step13 -l app=topo -o wide
kubectl describe pod <pending-pod> -n step13 | grep -A3 Events
# 하드일 때: "didn't match pod topology spread constraints"
```
"절대 Pending은 안 된다"는 서비스면 소프트, "무조건 고르게"가 중요하면 하드.
</details>

---

## 과제 5. podAffinity의 "닭이 먼저냐" 함정 재현

기준이 될 `app: db` 파드가 **아직 없는 상태**에서, required podAffinity로 `app: db`를 요구하는 파드를 먼저 만들면? 그 뒤 `db`를 띄우면?

<details><summary>해답</summary>

```bash
# db 없이 required podAffinity 파드부터 apply
kubectl get pod q5-client -n step13         # Pending
kubectl describe pod q5-client -n step13 | grep -A3 Events
# -> "didn't match pod affinity rules" (매칭할 db 파드가 없음)

# 이제 db 를 띄우면
kubectl run q5-db --image=nginx:1.27-alpine -n step13 --labels=app=db
# 잠시 후 q5-client 가 db 와 같은 노드에서 Running 으로 전환된다
```
required podAffinity는 기준 파드가 나타날 때까지 기다린다. 그래서 초기 배포 순서가 중요하거나, soft로 완화하는 편이 안전하다.
</details>
