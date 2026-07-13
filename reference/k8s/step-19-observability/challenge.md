# Step 19 연습 과제 — 관측성

> 실습 네임스페이스: `step19`. 끝나면 `kubectl delete ns step19`.

---

## 과제 1. CrashLoop 원인 찾기

`flaky` 파드가 계속 재시작합니다. (1) 몇 번 재시작했는지, (2) 마지막으로 죽은 이유(reason), (3) 어떤 이벤트 때문인지를 각각 **한 줄 명령**으로 뽑으세요.

<details><summary>해답</summary>

```bash
# (1) 재시작 횟수
kubectl get pod flaky -n step19 -o jsonpath='{.status.containerStatuses[0].restartCount}'; echo
# (2) 마지막 종료 이유
kubectl get pod flaky -n step19 -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'; echo
# (3) 이벤트
kubectl get events -n step19 --field-selector type=Warning --sort-by='.lastTimestamp'
```
이벤트에 `Liveness probe failed: cat: can't open '/tmp/healthy'`가 보입니다.
</details>

---

## 과제 2. 특정 노드의 파드만

`learn-worker`에 스케줄된 파드 이름만 출력하세요(다른 노드 제외).

<details><summary>해답</summary>

```bash
kubectl get pods -n step19 -o jsonpath='{range .items[?(@.spec.nodeName=="learn-worker")]}{.metadata.name}{"\n"}{end}'
# 또는
kubectl get pods -n step19 --field-selector spec.nodeName=learn-worker
```
</details>

---

## 과제 3. 셸 없는 컨테이너 안에서 포트 확인

`nginx:1.27-alpine`에는 `netstat`/`ss`가 없습니다. ephemeral 컨테이너로 `flaky` 안에서 80 포트가 열려 응답하는지 확인하세요.

<details><summary>해답</summary>

```bash
kubectl debug flaky -n step19 --image=busybox:1.36 --target=web -c check \
  -- sh -c 'wget -qO- -T2 localhost | head -1; netstat -tln 2>/dev/null || echo "no netstat, but wget worked"'
```
`--target=web`으로 네임스페이스를 공유하므로 `localhost`가 nginx를 가리킵니다. `<!DOCTYPE html>`이 나오면 80 포트가 살아있는 것.
</details>

---

## 과제 4. 두 컨테이너 로그를 한 번에

`multi` 파드의 app·sidecar 로그를 **컨테이너 이름 접두사와 함께** 동시에 팔로우하세요.

<details><summary>해답</summary>

```bash
kubectl logs multi -n step19 --all-containers --prefix -f
# 접두사 예: [pod/multi/app] [app] tick 5 ...
#            [pod/multi/sidecar] [sidecar] heartbeat ...
```
레이블로 여러 파드를 한 번에: `kubectl logs -n step19 -l app=multi --all-containers --prefix -f`.
</details>
