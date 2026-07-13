# Step 02 연습 과제

이 스텝은 개념 이해가 목표입니다. 손과 머리를 함께 씁니다.

---

### 과제 1. 컴포넌트 위치
etcd, kube-apiserver, kube-scheduler는 어느 노드에 떠 있는가? kube-proxy는? 왜 이렇게 배치되는가?

<details><summary>해답</summary>

```bash
kubectl get pods -n kube-system -o wide
```
etcd/apiserver/scheduler/controller-manager는 **control-plane 노드**에만 있습니다(클러스터의 두뇌라 한곳에 모음). kube-proxy와 kindnet은 **모든 노드**에 하나씩 있습니다(각 노드의 네트워크를 담당해야 하므로 — 이게 DaemonSet입니다, Step 12).
</details>

---

### 과제 2. 조정 루프 재현
Deployment로 파드 2개를 띄우고, 하나를 삭제한 뒤 어떻게 되는지 관찰하세요. 왜 그런가?

<details><summary>해답</summary>

```bash
kubectl create ns step02
kubectl -n step02 create deployment demo --image=nginx:1.27-alpine --replicas=2
kubectl -n step02 get pods
kubectl -n step02 delete pod <파드이름-하나>
kubectl -n step02 get pods    # 새 파드가 자동 생성돼 다시 2개
```
etcd에 "replicas: 2"라는 원하는 상태가 적혀 있어, 컨트롤러가 현재 상태(1개)와의 차이를 감지하고 새 파드를 만듭니다.
</details>

---

### 과제 3. 파드가 "안 지워지는" 이유
과제 2의 파드를 완전히 없애려면 어떻게 해야 하는가? (`delete pod`로는 안 됨)

<details><summary>해답</summary>

`delete pod`는 조정 루프가 되살립니다. 원하는 상태 자체를 바꿔야 합니다:
```bash
kubectl -n step02 scale deployment demo --replicas=0   # 0개를 원한다고 선언
# 또는
kubectl -n step02 delete deployment demo                # 관리자 자체를 제거
```
</details>

---

### 과제 4. 접수 ≠ 완료
`kubectl apply`가 성공했는데도 파드가 아직 Running이 아닐 수 있는 이유를 설명하세요. 실제로 apply 직후 즉시 상태를 확인해 보세요.

<details><summary>해답</summary>

```bash
kubectl -n step02 create deployment fast --image=nginx:1.27-alpine
kubectl -n step02 get pods    # 즉시 확인하면 ContainerCreating 일 수 있음
```
apply 성공 = "API Server가 요청을 접수하고 etcd에 저장" 까지입니다. 그 뒤 스케줄러 배정 → kubelet이 이미지 pull → 컨테이너 실행이 남아 있어, 실제 Running까지 시차가 있습니다.
</details>

---

### 과제 5. 흐름 추적
파드 생성 이벤트를 시간순으로 보고, "스케줄 → 이미지 pull → 시작" 흐름을 확인하세요.

<details><summary>해답</summary>

```bash
kubectl -n step02 get events --sort-by=.lastTimestamp | tail -15
```
`Scheduled`(스케줄러가 노드 배정) → `Pulling`/`Pulled`(kubelet이 이미지 받음) → `Created` → `Started` 순서가 보입니다. 각 단계가 Step 02에서 배운 흐름과 일치합니다.
</details>

---

정리: `kubectl delete namespace step02`
