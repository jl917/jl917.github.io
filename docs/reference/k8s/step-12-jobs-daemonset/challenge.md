# Step 12 연습 과제 — 잡·데몬셋

각 문제를 스스로 풀어본 뒤 답을 펼쳐 확인하세요. 모든 리소스는 `step12` 네임스페이스에서 실습합니다.

---

## 과제 1. 완료 후 자동 삭제되는 Job

`hello-job`을 수정해 **완료 30초 뒤 Job과 파드가 자동 삭제**되도록 만드세요. 어떤 필드를 추가해야 할까요?

<details>
<summary>정답</summary>

`spec.ttlSecondsAfterFinished: 30` 을 추가한다.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: ttl-job
  namespace: step12
spec:
  ttlSecondsAfterFinished: 30   # Complete/Failed 30초 뒤 자동 GC
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: hello
          image: busybox:1.36
          command: ["sh", "-c", "echo bye; sleep 1"]
```

```bash
kubectl apply -f - <<'EOF'
...위 YAML...
EOF
kubectl wait --for=condition=complete job/ttl-job -n step12 --timeout=60s
sleep 35
kubectl get job ttl-job -n step12   # NotFound → 자동 삭제됨
```

완료된 Job이 무한정 쌓이는 것을 막는 표준 방법이다.
</details>

---

## 과제 2. RESTARTS가 올라가게 만들기

`fail-job`은 `restartPolicy: Never`라서 실패해도 `RESTARTS`가 0이고 파드만 늘었습니다. **같은 파드 안에서 컨테이너가 재시작**되어 `RESTARTS`가 올라가게 하려면 무엇을 바꿔야 할까요?

<details>
<summary>정답</summary>

`restartPolicy: OnFailure`로 바꾼다.

- `Never`: 실패 시 **파드를 새로 생성** → 실패 파드가 여러 개 쌓이고 RESTARTS는 0.
- `OnFailure`: 실패 시 **같은 파드 내 컨테이너만 재시작** → 파드는 1개, RESTARTS가 증가.

```yaml
    spec:
      restartPolicy: OnFailure   # Never → OnFailure
      containers:
        - name: boom
          image: busybox:1.36
          command: ["sh", "-c", "exit 1"]
```

`kubectl get pod -n step12 -l job-name=fail-job` 에서 파드 1개의 RESTARTS가 1, 2, ... 로 올라가는 것을 확인할 수 있다. 재시도 횟수를 세는 기준이 정책에 따라 "파드 개수"냐 "RESTARTS"냐로 달라진다는 점이 핵심이다.
</details>

---

## 과제 3. 겹치면 건너뛰는 CronJob

매 1분마다 도는 CronJob인데, 한 번 실행이 90초 걸린다면 다음 실행 시각에 이전 게 아직 돌고 있습니다. **이전 실행이 끝나지 않았으면 새 실행을 건너뛰게** 하려면 어떤 설정이 필요할까요? 그리고 `Replace`와의 차이는?

<details>
<summary>정답</summary>

`spec.concurrencyPolicy: Forbid` 를 사용한다.

- `Allow`(기본): 겹쳐도 동시 실행 → 90초짜리가 겹치면 여러 개가 동시에 돈다.
- `Forbid`: 이전 실행이 끝날 때까지 **새 스케줄을 건너뜀**(스킵).
- `Replace`: 이전 실행을 **죽이고** 새 실행으로 교체.

백업·리포트처럼 중복 실행이 데이터를 망칠 수 있는 작업은 `Forbid`, 항상 "최신 상태"만 유지하면 되는 작업은 `Replace`가 적합하다.
</details>

---

## 과제 4. 특정 노드에만 뜨는 DaemonSet

`node-agent` DaemonSet을 **`learn-worker` 노드에만** 뜨게 하려면 어떻게 할까요? (노드에 라벨을 새로 붙이지 말고, 기존 노드 이름/라벨을 활용)

<details>
<summary>정답</summary>

각 노드에는 `kubernetes.io/hostname=<노드이름>` 라벨이 기본으로 붙어 있으므로 이를 `nodeSelector`로 사용한다.

```yaml
    spec:
      nodeSelector:
        kubernetes.io/hostname: learn-worker
      containers:
        - name: agent
          image: nginx:1.27-alpine
```

적용하면 DaemonSet의 `DESIRED`가 1로 줄고 `learn-worker`에만 파드가 뜬다.

```bash
kubectl get node --show-labels | grep hostname   # 라벨 확인
kubectl get daemonset node-agent -n step12        # DESIRED 1
```

DaemonSet은 replicas 대신 "적합한 노드 수"가 개수를 정하므로, nodeSelector로 대상 노드를 좁히면 그만큼 파드 수가 줄어든다.
</details>

---

## 과제 5. DaemonSet은 왜 Deployment로 대체할 수 없나?

"노드마다 로그 수집기 1개"를 Deployment + `replicas: 3`으로 흉내 내면 안 되는 이유를 두 가지 이상 설명하세요.

<details>
<summary>정답</summary>

1. **노드당 정확히 1개 보장 안 됨**: Deployment 스케줄러는 replicas 3개를 아무 노드에나 배치한다. 한 노드에 2개, 다른 노드에 0개가 될 수 있어 "노드마다 1개"가 깨진다. (podAntiAffinity로 억지로 맞출 수는 있으나 복잡하고 취약하다.)
2. **노드 증감에 자동 대응 못 함**: 노드가 4대로 늘어도 replicas는 여전히 3이라 새 노드에는 수집기가 안 뜬다. DaemonSet은 노드가 추가되면 자동으로 그 노드에 파드를 띄우고, 제거되면 함께 사라진다.
3. **컨트롤플레인 등 taint 노드 대응**: DaemonSet은 toleration만 주면 모든 노드를 커버하도록 설계돼 있다. Deployment로는 "모든 노드 커버"라는 의도 자체를 표현하기 어렵다.

즉 DaemonSet은 "개수"가 아니라 "노드 집합"이 배포 단위라는 점에서 Deployment와 근본적으로 다르다.
</details>
