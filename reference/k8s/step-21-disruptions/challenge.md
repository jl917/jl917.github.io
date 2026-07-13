# Step 21 연습 과제 — 가용성과 중단 관리

> 실습 네임스페이스: `step21`. **노드 변경은 learn-worker2만, 직후 반드시 `uncordon`.**
> 끝나면 `kubectl delete ns step21`, `kubectl delete priorityclass step21-low step21-high`, 노드 uncordon 확인.

---

## 과제 1. drain을 막는 PDB 만들기

`web`을 3 replica로 줄이고 PDB `minAvailable: 3`을 걸면, worker2 drain은 어떻게 될까요? 예상하고 확인한 뒤, **반드시 원복**하세요.

<details><summary>해답</summary>

```bash
kubectl scale deploy/web -n step21 --replicas=3
kubectl patch pdb web-pdb -n step21 --type merge -p '{"spec":{"minAvailable":3}}'
kubectl get pdb -n step21          # ALLOWED DISRUPTIONS 0
kubectl drain learn-worker2 --ignore-daemonsets --delete-emptydir-data --timeout=20s
```
`ALLOWED DISRUPTIONS 0`이라 drain은 `Cannot evict ... disruption budget`으로 **타임아웃까지 무한 재시도**하다 실패합니다. minAvailable을 replica와 같게 두면 노드 유지보수가 불가능해진다는 교훈. **반드시 `kubectl uncordon learn-worker2`로 원복**하고 minAvailable을 되돌리세요.
</details>

---

## 과제 2. `maxUnavailable`로 다시 쓰기

`minAvailable: 3`(replica 4)과 동일한 PDB를 `maxUnavailable`로 표현하세요.

<details><summary>해답</summary>

`maxUnavailable: 1`. (4 - 3 = 1). 백분율로는 replica가 바뀌어도 비율이 유지되도록 `maxUnavailable: 25%`도 가능합니다. 단, minAvailable과 maxUnavailable은 **동시에 쓸 수 없습니다**(둘 중 하나만).
</details>

---

## 과제 3. preemption이 안 일어나는 경우

높은 우선순위 파드를 넣었는데 preemption이 안 일어났습니다. 가능한 원인 2가지는?

<details><summary>해답</summary>

1. **노드에 이미 자리가 있음** — 굳이 밀어낼 필요가 없어 그냥 스케줄됨. preemption은 "자리가 없을 때"만.
2. **밀어낼 대상이 더 높거나 같은 우선순위** — 낮은 우선순위 파드가 없으면 preempt 불가. 또는 대상 파드에 `preemptionPolicy: Never`가 걸린 경우, 혹은 밀어내도 여전히 자리가 안 나는 경우(대상 파드가 여러 노드에 흩어져 한 노드를 비워도 부족).
</details>

---

## 과제 4. graceful shutdown 관찰

`web` 파드 하나를 삭제하면서, Terminating부터 사라질 때까지의 시간을 관찰하세요. `terminationGracePeriodSeconds`를 60으로 바꾸면 어떻게 달라질까요?

<details><summary>해답</summary>

```bash
kubectl delete pod <web-pod> -n step21 &
kubectl get pod <web-pod> -n step21 -w      # Terminating -> 사라짐
```
nginx는 SIGTERM에 빠르게 종료하므로 grace period(10s)를 다 쓰지 않고 곧 사라집니다. `terminationGracePeriodSeconds: 60`이어도 앱이 SIGTERM에 즉시 종료하면 60초를 기다리지 않습니다. grace period는 **상한**일 뿐, 앱이 일찍 끝나면 그때 종료됩니다. 반대로 앱이 SIGTERM을 무시하면 60초 뒤 SIGKILL로 강제 종료됩니다.
</details>
