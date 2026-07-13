# Step 03 연습 과제

네임스페이스 `step03`에서 실습하세요.

---

### 과제 1. 파드의 노드와 IP
`web` 파드가 어느 노드에서 도는지, 클러스터 내부 IP는 무엇인지 확인하세요. 파드를 삭제하고 다시 만들면 IP가 유지되는가?

<details><summary>해답</summary>

```bash
kubectl -n step03 get pod web -o wide         # NODE, IP 확인
kubectl -n step03 delete pod web
kubectl apply -f manifests/pod.yaml
kubectl -n step03 get pod web -o wide         # IP가 바뀜!
```
파드는 일회용이라 재생성 시 IP가 바뀝니다. 그래서 파드 IP에 의존하면 안 되고 Service(Step 06)를 씁니다.
</details>

---

### 과제 2. 컨테이너 내부 탐험
`web` 파드 안에서 nginx 프로세스가 실제로 도는지, 그리고 `localhost`로 자기 자신에게 HTTP 요청이 되는지 확인하세요.

<details><summary>해답</summary>

```bash
kubectl -n step03 exec web -- ps aux            # nginx 프로세스
kubectl -n step03 exec web -- wget -qO- localhost:80 | head -5   # 자기 자신에 요청
```
(alpine이라 `curl` 대신 `wget`. 셸은 `sh`)
</details>

---

### 과제 3. initContainer 실패 실험
initContainer가 실패하면 앱 컨테이너가 어떻게 되는지 확인하세요. (힌트: multi-container.yaml의 init command를 `exit 1`로 바꿔보기)

<details><summary>해답</summary>

init command를 `['sh','-c','exit 1']`로 바꿔 apply하면:
```bash
kubectl -n step03 get pod multi
# STATUS: Init:CrashLoopBackOff, READY 0/2 — 앱(web/sidecar)이 아예 시작 안 됨
kubectl -n step03 describe pod multi | grep -A5 Events
```
initContainer가 성공(exit 0)해야만 앱 컨테이너가 시작됩니다.
</details>

---

### 과제 4. CrashLoop 진단
`crasher` 파드가 왜 죽는지 로그로 밝히세요. 현재 로그(`logs`)와 이전 로그(`logs --previous`)의 차이는?

<details><summary>해답</summary>

```bash
kubectl -n step03 logs crasher              # 방금 재시작한 컨테이너 — 짧거나 비어있음
kubectl -n step03 logs crasher --previous   # 죽기 직전 로그 — "starting" 확인
kubectl -n step03 describe pod crasher | grep -A7 Events   # BackOff 반복(x2, x3)
```
CrashLoop 진단은 항상 `--previous`가 핵심입니다.
</details>

---

### 과제 5. 재시작 정책
`crasher`의 `restartPolicy`를 `Never`로 바꾸면 어떻게 달라지는가? 왜 파드에서는 이게 기본이 아닐까?

<details><summary>해답</summary>

`spec.restartPolicy: Never`로 바꾸면 한 번 죽고 STATUS가 `Error`로 멈춥니다(재시작 안 함). 일반 파드(서버 앱)는 죽으면 다시 살아나야 서비스가 유지되므로 기본이 `Always`입니다. 반대로 Job(Step 12)은 한 번 실행하고 끝나야 하므로 `Never`/`OnFailure`를 씁니다.
</details>

---

정리: `kubectl delete namespace step03`
