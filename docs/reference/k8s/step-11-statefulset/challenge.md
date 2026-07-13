# Step 11 연습 과제 — 스테이트풀셋

> 모두 `step11` 네임스페이스와 `manifests/01-redis-statefulset.yaml` 를 배포한 상태에서 진행합니다.
> `export PATH="/opt/homebrew/bin:$PATH"` 를 먼저 실행하세요. 답은 각 문제 아래 접힌 블록에 있습니다.

---

## 과제 1 — 서수와 스토리지 이름 규칙

`replicas: 3` 인 `redis` StatefulSet 이 있을 때, 세 번째 파드의 이름과 그 파드가 쓰는 PVC 이름을 규칙만 보고 적으세요. 그리고 실제로 확인하세요.

<details>
<summary>정답</summary>

- 파드 이름: `redis-2` (서수는 0부터 시작 → 세 번째는 2)
- PVC 이름: `data-redis-2` (규칙: `<volumeClaimTemplates의 name>-<statefulset이름>-<서수>`)

```bash
kubectl get pod -n step11 -l app=redis
kubectl get pvc -n step11
```
`data-redis-2` 가 목록에 있으면 정답입니다.
</details>

---

## 과제 2 — 헤드리스가 아니면 어떻게 될까

`spec.clusterIP: None` 을 지운(=일반 ClusterIP) 서비스로 바꾸면 `redis-0.redis...` 개별 DNS 가 어떻게 되는지 예측하고, 그렇게 만드는 이유를 한 줄로 쓰세요.

<details>
<summary>정답</summary>

- 개별 파드 DNS(`redis-0.redis.step11.svc.cluster.local`)가 **생성되지 않습니다.** 서비스 이름은 VIP 하나로만 해석되고, 그 뒤로 무작위 로드밸런싱됩니다.
- 이유: StatefulSet 은 각 파드에 **고유 신원**을 줘야 하는데, 로드밸런싱 VIP 는 "어느 파드인지 모르는" 단일 진입점이라 신원과 정반대입니다. 그래서 파드별 A 레코드를 만드는 **헤드리스(`clusterIP: None`)** 가 필수입니다.

(주의: 실서비스를 바꾸기 전에 `kubectl get svc redis -n step11 -o yaml > /tmp/redis-svc.yaml` 로 백업하세요. clusterIP 필드는 immutable 이라 바꾸려면 서비스를 재생성해야 합니다.)
</details>

---

## 과제 3 — 데이터는 정말 PVC 에 있는가

`redis-1` 에 키 `role=replica` 를 저장한 뒤, `redis-1` **파드**만 지웠다가 다시 뜨면 값이 남아 있음을 증명하세요. 반대로 데이터를 완전히 초기화하려면 무엇을 지워야 하나요?

<details>
<summary>정답</summary>

```bash
kubectl exec -n step11 redis-1 -- redis-cli set role replica
kubectl delete pod redis-1 -n step11
kubectl wait --for=condition=Ready pod/redis-1 -n step11 --timeout=90s
kubectl exec -n step11 redis-1 -- redis-cli get role     # → replica (생존)
```
- 파드를 지워도 PVC `data-redis-1` 은 유지되고, 재생성된 `redis-1` 이 같은 PVC 를 다시 붙기 때문에 값이 남습니다.
- 완전 초기화하려면 파드가 아니라 **PVC** 를 지워야 합니다: `kubectl delete pvc data-redis-1 -n step11` (그 후 파드가 재생성되면 빈 볼륨을 새로 받습니다).
</details>

---

## 과제 4 — 역순 삭제 순서 맞히기

`replicas` 를 3 에서 1 로 줄일 때, 어느 파드가 **가장 먼저** 종료되고 어느 파드가 남을지 적고, watch 로 확인하세요.

<details>
<summary>정답</summary>

- 가장 먼저 종료: `redis-2` (가장 큰 서수부터, 즉 2→1 순서로 종료). 남는 것: `redis-0`.

```bash
# 터미널 A
kubectl get pod -n step11 -l app=redis -w
# 터미널 B
kubectl scale statefulset redis -n step11 --replicas=1
```
watch 에서 `redis-2 ... Terminating` 이 먼저, 그다음 `redis-1 ... Terminating` 이 뜨면 정답입니다. 끝나면 `--replicas=3` 으로 원복하세요.
</details>

---

## 과제 5 — 정리할 때의 함정

`kubectl delete statefulset redis -n step11` 만 실행하면 스토리지까지 깨끗이 정리될까요? 실제로 확인하고, 남는 것이 있다면 무엇이며 어떻게 지우는지 쓰세요.

<details>
<summary>정답</summary>

- 아니요. StatefulSet 을 지워도 `volumeClaimTemplates` 로 만든 **PVC(그리고 그에 바인딩된 PV)는 남습니다.**

```bash
kubectl delete statefulset redis -n step11
kubectl get pvc -n step11        # data-redis-0/1/2 가 여전히 Bound 상태로 남아 있음
```
- 스토리지까지 회수하려면 PVC 를 명시적으로 지웁니다: `kubectl delete pvc -n step11 --all`. 기본 StorageClass(local-path)의 ReclaimPolicy 가 `Delete` 라, PVC 를 지우면 PV 도 함께 회수됩니다. 방치하면 비용이 계속 발생합니다.
</details>
