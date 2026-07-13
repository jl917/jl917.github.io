# Step 12 — 잡·데몬셋

> **학습 목표**: 배치성 워크로드를 다루는 `Job`(완료·병렬·재시도)과 주기 실행 `CronJob`, 그리고 노드마다 한 개씩 파드를 띄우는 `DaemonSet`을 이해하고, taint/toleration이 DaemonSet 배치에 어떻게 작용하는지 실습으로 확인한다. / **선행 스텝**: [Step 11](../step-11-statefulset/README.md) / **예상 소요**: 60분

이 스텝의 모든 리소스는 `step12` 네임스페이스에 생성한다. Job·CronJob·DaemonSet은 **모두 네임스페이스 리소스**이므로 마지막에 `kubectl delete namespace step12` 한 방으로 전부 정리된다.

```bash
kubectl create namespace step12
```

---

## 12-1. Job — 한 번 실행되고 끝나는 워크로드

`Deployment`가 "항상 떠 있어야 하는" 서비스라면, `Job`은 "**한 번 성공적으로 끝나면 되는**" 배치 작업이다. 마이그레이션, 백업, 리포트 생성 같은 작업에 쓴다. 핵심 필드는 세 가지다.

- `completions`: 성공해야 하는 파드 개수 (기본 1)
- `parallelism`: 동시에 실행할 파드 개수 (기본 1)
- `backoffLimit`: 실패 시 재시도 총 횟수 (기본 6)

또한 Job의 파드는 `restartPolicy`가 반드시 `Never` 또는 `OnFailure`여야 한다. `Always`는 "끝나면 안 되는" 의미라 Job에서는 거부된다.

```yaml
# manifests/job-simple.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: hello-job
  namespace: step12
spec:
  completions: 1          # 성공해야 하는 Pod 개수 (기본 1)
  backoffLimit: 4         # 실패 시 재시도 횟수 (기본 6)
  template:
    spec:
      restartPolicy: Never   # Job의 Pod는 Never 또는 OnFailure만 허용
      containers:
        - name: hello
          image: busybox:1.36
          command: ["sh", "-c", "echo hello from job; sleep 2; echo done"]
```

```bash
kubectl apply -f manifests/job-simple.yaml
kubectl wait --for=condition=complete job/hello-job -n step12 --timeout=60s
kubectl get job -n step12
kubectl get pod -n step12
kubectl logs -n step12 job/hello-job
```

**실행 결과**

```
job.batch/hello-job created
job.batch/hello-job condition met
=== get job ===
NAME        STATUS     COMPLETIONS   DURATION   AGE
hello-job   Complete   1/1           5s         5s
=== get pod ===
NAME              READY   STATUS      RESTARTS   AGE
hello-job-j649q   0/1     Completed   0          5s
=== logs ===
hello from job
done
```

`COMPLETIONS 1/1`, 파드 상태 `Completed`. 파드가 종료됐지만 삭제되지 않고 남아 있는 것에 주목하자. 로그를 확인할 수 있도록 완료된 파드를 남겨두는 것이 Job의 정상 동작이다.

> 💡 **실무 팁**: 완료된 Job 파드는 자동으로 사라지지 않아 계속 쌓인다. `spec.ttlSecondsAfterFinished: 300` 을 주면 완료 후 5분 뒤 Job과 파드가 자동 삭제되어 클러스터가 깔끔해진다.

> ⚠️ **함정**: `restartPolicy: Always`(기본값)를 Job 템플릿에 그대로 두면 `apply` 자체가 거부된다. Job에서는 반드시 `Never`나 `OnFailure`를 명시해야 한다.

---

## 12-2. 병렬 Job — completions + parallelism

`completions: 6`, `parallelism: 2`로 설정하면 총 6번을 **2개씩 나눠서** 처리한다. 대량 작업을 워커 여러 개로 쪼개 돌릴 때 쓰는 패턴이다.

```yaml
# manifests/job-parallel.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: parallel-job
  namespace: step12
spec:
  completions: 6          # 총 6번 성공해야 완료
  parallelism: 2          # 동시에 2개씩 실행
  backoffLimit: 4
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: worker
          image: busybox:1.36
          command: ["sh", "-c", "echo processing on $(hostname); sleep 3"]
```

```bash
kubectl apply -f manifests/job-parallel.yaml
kubectl get pod -n step12 -l job-name=parallel-job    # 실행 도중: 2개만 동시에
kubectl wait --for=condition=complete job/parallel-job -n step12 --timeout=90s
kubectl get job parallel-job -n step12
kubectl get pod -n step12 -l job-name=parallel-job -o wide
```

**실행 결과**

```
job.batch/parallel-job created
=== 진행 중 (2개씩) ===
NAME                 READY   STATUS      RESTARTS   AGE
parallel-job-p55zc   0/1     Completed   0          4s
parallel-job-swstg   0/1     Completed   0          4s
=== 완료 후 job ===
NAME           STATUS     COMPLETIONS   DURATION   AGE
parallel-job   Complete   6/6           17s        17s
=== 완료 후 pod ===
NAME                 READY   STATUS      RESTARTS   AGE   IP            NODE           NOMINATED NODE   READINESS GATES
parallel-job-drcxq   0/1     Completed   0          6s    10.244.1.31   learn-worker   <none>           <none>
parallel-job-p55zc   0/1     Completed   0          17s   10.244.1.24   learn-worker   <none>           <none>
parallel-job-s4dcz   0/1     Completed   0          12s   10.244.1.28   learn-worker   <none>           <none>
parallel-job-swstg   0/1     Completed   0          17s   10.244.1.25   learn-worker   <none>           <none>
parallel-job-wwqdr   0/1     Completed   0          6s    10.244.1.32   learn-worker   <none>           <none>
parallel-job-x75vt   0/1     Completed   0          12s   10.244.1.29   learn-worker   <none>           <none>
```

`COMPLETIONS 6/6`으로 완료됐다. 파드 6개의 **AGE를 보면 17s 2개 → 12s 2개 → 6s 2개**로, 정확히 2개씩 3번의 물결(wave)로 실행됐음을 알 수 있다. 이것이 `parallelism: 2`의 효과다.

> 💡 **실무 팁**: 처리할 항목마다 서로 다른 입력을 주고 싶다면 `completionMode: Indexed`를 쓰면 된다. 각 파드에 `JOB_COMPLETION_INDEX` 환경변수(0~5)가 주입되어 "몇 번째 조각"인지 구분할 수 있다.

---

## 12-3. Job 실패와 재시도 — backoffLimit

컨테이너가 0이 아닌 종료 코드로 끝나면 Job은 파드를 **다시 만들어 재시도**한다. `backoffLimit: 2`면 최초 실행 + 재시도 2번 = 총 3개의 파드가 만들어지고, 그래도 성공 못 하면 Job은 `BackoffLimitExceeded`로 **Failed** 처리된다.

```yaml
# manifests/job-fail.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: fail-job
  namespace: step12
spec:
  backoffLimit: 2         # 2번까지만 재시도 → 초과하면 Job Failed
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: boom
          image: busybox:1.36
          command: ["sh", "-c", "echo trying...; exit 1"]   # 항상 실패
```

```bash
kubectl apply -f manifests/job-fail.yaml
kubectl wait --for=condition=failed job/fail-job -n step12 --timeout=120s
kubectl get job fail-job -n step12
kubectl get pod -n step12 -l job-name=fail-job
kubectl describe job fail-job -n step12
```

**실행 결과**

```
job.batch/fail-job created
job.batch/fail-job condition met
=== get job ===
NAME       STATUS   COMPLETIONS   DURATION   AGE
fail-job   Failed   0/1           33s        33s
=== get pod (여러 실패 Pod) ===
NAME             READY   STATUS   RESTARTS   AGE
fail-job-l67tp   0/1     Error    0          3s
fail-job-mdjb4   0/1     Error    0          23s
fail-job-n9xkf   0/1     Error    0          33s
```

파드가 **3개** 만들어졌고(최초 1 + 재시도 2), 전부 `Error` 상태다. `describe` 하단의 이벤트가 재시도와 최종 실패를 그대로 보여준다.

```
Pods Statuses:    0 Active (0 Ready) / 0 Succeeded / 3 Failed
...
Events:
  Type     Reason                Age   From            Message
  ----     ------                ----  ----            -------
  Normal   SuccessfulCreate      33s   job-controller  Created pod: fail-job-n9xkf
  Normal   SuccessfulCreate      23s   job-controller  Created pod: fail-job-mdjb4
  Normal   SuccessfulCreate      3s    job-controller  Created pod: fail-job-l67tp
  Warning  BackoffLimitExceeded  0s    job-controller  Job has reached the specified backoff limit
```

재시도 간격이 33s → 23s → 3s로 벌어지는 것(10s, 20s...)은 **지수 백오프(exponential backoff)** 때문이다. 실패할수록 대기 시간이 길어진다.

> ⚠️ **함정**: `RESTARTS` 컬럼은 계속 0이다. `restartPolicy: Never`이면 컨테이너를 재시작하는 게 아니라 **파드를 통째로 새로 만들기** 때문이다. 그래서 재시도 횟수는 RESTARTS가 아니라 "실패한 파드 개수"로 세어야 한다. `OnFailure`로 바꾸면 반대로 같은 파드 안에서 컨테이너만 재시작되어 RESTARTS가 올라간다.

> 💡 **실무 팁**: 무한 재시도로 클러스터를 낭비하지 않도록 `backoffLimit`과 함께 `activeDeadlineSeconds`(전체 실행 시간 상한)를 걸어두면 좋다. 시간이 초과되면 `DeadlineExceeded`로 즉시 종료된다.

---

## 12-4. CronJob — 주기적으로 Job을 만드는 스케줄러

`CronJob`은 크론 표현식에 맞춰 **주기적으로 Job을 생성**한다. `Job`을 찍어내는 공장이라고 보면 된다.

- `schedule`: 표준 크론 표현식 (`*/1 * * * *` = 매 1분)
- `concurrencyPolicy`: 이전 실행이 아직 안 끝났을 때의 정책
  - `Allow`(기본): 겹쳐도 동시 실행 허용
  - `Forbid`: 이전 게 끝날 때까지 새 실행을 건너뜀
  - `Replace`: 이전 걸 죽이고 새 걸로 교체
- `successfulJobsHistoryLimit` / `failedJobsHistoryLimit`: 완료된 Job을 몇 개까지 보관할지 (기본 3 / 1)

```yaml
# manifests/cronjob.yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: hello-cron
  namespace: step12
spec:
  schedule: "*/1 * * * *"        # 매 1분마다 실행
  concurrencyPolicy: Forbid      # 이전 Job이 아직 돌면 새 Job을 건너뜀
  successfulJobsHistoryLimit: 3   # 성공한 Job 이력 3개까지 보관
  failedJobsHistoryLimit: 1       # 실패한 Job 이력 1개까지 보관
  jobTemplate:
    spec:
      backoffLimit: 2
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: tick
              image: busybox:1.36
              command: ["sh", "-c", "echo tick at $(date); sleep 2"]
```

```bash
kubectl apply -f manifests/cronjob.yaml
# 1~2분 기다린 뒤 (매분 정각에 발화)
kubectl get cronjob -n step12
kubectl get jobs -n step12
kubectl get pods -n step12
```

**실행 결과** (약 2분 대기 후)

```
=== cronjob ===
NAME         SCHEDULE      TIMEZONE   SUSPEND   ACTIVE   LAST SCHEDULE   AGE
hello-cron   */1 * * * *   <none>     False     0        6s              110s
=== jobs (cron이 만든 것) ===
NAME                  STATUS     COMPLETIONS   DURATION   AGE
hello-cron-29731857   Complete   1/1           5s         66s
hello-cron-29731858   Complete   1/1           5s         6s
=== pods from cron ===
NAME                        READY   STATUS      RESTARTS   AGE
hello-cron-29731857-m4fz8   0/1     Completed   0          66s
hello-cron-29731858-2zsw5   0/1     Completed   0          6s
```

CronJob이 매 분마다 `hello-cron-<타임스탬프>` 이름의 Job을 하나씩 만들어냈다(66s 전 하나, 6s 전 하나 = 1분 간격). `LAST SCHEDULE 6s`는 마지막 발화가 6초 전이었음을 뜻한다. Job 이름 뒤 숫자(`29731857` 등)는 유닉스 에폭을 분 단위로 나눈 값이라 실행 시각을 유일하게 식별한다.

**히스토리 리밋** 동작: `successfulJobsHistoryLimit: 3`이므로 성공한 Job이 4개째 쌓이면 가장 오래된 Job(과 그 파드)이 자동 삭제된다. 그래서 시간이 지나도 완료된 Job이 무한정 쌓이지 않고 최대 3개만 유지된다. 이 값을 `0`으로 주면 완료 즉시 삭제된다.

> ⚠️ **함정**: CronJob의 시각은 **kube-controller-manager가 있는 컨트롤플레인의 시간대(기본 UTC)** 기준이다. "밤 12시 배치"를 KST로 돌리려면 `spec.timeZone: "Asia/Seoul"`을 명시해야 한다. 안 그러면 9시간 어긋난 시각에 돈다.

> ⚠️ **함정**: 컨트롤러가 잠깐 죽었다 살아나면 놓친 스케줄을 몰아서 실행할 수 있다. `startingDeadlineSeconds`를 설정하면 그 시간을 넘겨 놓친 실행은 건너뛴다. 배치가 겹치면 안 되는 작업이라면 `concurrencyPolicy: Forbid`도 필수다.

---

## 12-5. DaemonSet — 노드마다 파드 한 개씩

`DaemonSet`은 **클러스터의 모든(적합한) 노드에 파드를 정확히 한 개씩** 띄운다. 로그 수집기(Fluentd), 노드 모니터링(node-exporter), CNI/스토리지 에이전트처럼 "노드마다 있어야 하는" 데몬에 쓴다. 새 노드가 추가되면 자동으로 그 노드에도 파드가 뜬다.

```yaml
# manifests/daemonset.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: node-agent
  namespace: step12
spec:
  selector:
    matchLabels:
      app: node-agent
  updateStrategy:
    type: RollingUpdate       # 노드마다 순차 교체 (기본값)
    rollingUpdate:
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: node-agent
    spec:
      containers:
        - name: agent
          image: nginx:1.27-alpine
          ports:
            - containerPort: 80
```

```bash
kubectl apply -f manifests/daemonset.yaml
kubectl rollout status daemonset/node-agent -n step12 --timeout=90s
kubectl get pod -n step12 -l app=node-agent -o wide
kubectl get daemonset -n step12
```

**실행 결과**

```
=== ds pods -o wide ===
NAME               READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
node-agent-5qlz5   1/1     Running   0          1s    10.244.2.46   learn-worker2   <none>           <none>
node-agent-z7k2k   1/1     Running   0          1s    10.244.1.46   learn-worker    <none>           <none>
=== daemonset ===
NAME         DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR   AGE
node-agent   2         2         2       2            2           <none>          1s
```

노드가 3개인데 파드는 **2개뿐**이다. `learn-worker`와 `learn-worker2`에만 떴고, `learn-control-plane`에는 뜨지 않았다. `DESIRED 2`인 것도 스케줄러가 "이 데몬셋이 갈 수 있는 노드는 2개"라고 계산했기 때문이다.

> 💡 **실무 팁**: DaemonSet은 `replicas` 필드가 없다. 개수는 "노드 수"가 결정하므로 사람이 정하는 게 아니다. 특정 노드에만 띄우고 싶다면 `spec.template.spec.nodeSelector`(예: `disktype: ssd`)로 대상 노드를 좁힌다.

---

## 12-6. 왜 컨트롤플레인에는 안 뜰까 — taint

컨트롤플레인 노드에는 일반 워크로드가 함부로 뜨지 못하도록 **taint(오염)**가 걸려 있다.

```bash
kubectl describe node learn-control-plane | grep -i taint
```

**실행 결과**

```
Taints:             node-role.kubernetes.io/control-plane:NoSchedule
```

`NoSchedule` taint가 있으면, 그것을 **견디는(tolerate) 설정이 없는 파드는 이 노드에 스케줄될 수 없다.** 우리 DaemonSet 파드에는 toleration이 없으므로 컨트롤플레인을 피해 워커 2대에만 뜬 것이다. taint/toleration의 자세한 내용은 다음 스텝에서 다룬다.

> 💡 **실무 팁**: taint는 "노드가 파드를 밀어내는" 힘, toleration은 "파드가 그 밀어냄을 견디는" 힘이다. 둘은 짝으로 동작하며, toleration이 있다고 반드시 그 노드로 **가는** 건 아니다(가도 된다는 허가일 뿐). "반드시 거기로" 보내려면 nodeSelector/affinity가 필요하다.

---

## 12-7. toleration 추가 — 컨트롤플레인에도 배치하기

노드 모니터링 에이전트라면 컨트롤플레인 노드도 관측해야 하므로, 모든 노드에 떠야 한다. DaemonSet 파드 스펙에 컨트롤플레인 taint를 견디는 **toleration**을 추가한다. 이것은 **파드 스펙 변경**일 뿐 노드는 전혀 건드리지 않는다.

```yaml
# manifests/daemonset-toleration.yaml (변경 부분만)
    spec:
      # 컨트롤플레인 taint를 견디는(tolerate) 설정 → 마스터 노드에도 배치됨
      tolerations:
        - key: node-role.kubernetes.io/control-plane
          operator: Exists
          effect: NoSchedule
      containers:
        - name: agent
          image: nginx:1.27-alpine
```

```bash
kubectl apply -f manifests/daemonset-toleration.yaml
kubectl rollout status daemonset/node-agent -n step12 --timeout=90s
kubectl get pod -n step12 -l app=node-agent -o wide
kubectl get daemonset -n step12
```

**실행 결과**

```
daemonset.apps/node-agent configured
Waiting for daemon set "node-agent" rollout to finish: 1 out of 3 new pods have been updated...
Waiting for daemon set "node-agent" rollout to finish: 2 out of 3 new pods have been updated...
daemon set "node-agent" successfully rolled out
=== ds pods -o wide (이제 3개) ===
NAME               READY   STATUS    RESTARTS   AGE   IP            NODE                  NOMINATED NODE   READINESS GATES
node-agent-78q6k   1/1     Running   0          9s    10.244.0.5    learn-control-plane   <none>           <none>
node-agent-prchh   1/1     Running   0          1s    10.244.1.52   learn-worker          <none>           <none>
node-agent-tmg7m   1/1     Running   0          1s    10.244.2.50   learn-worker2         <none>           <none>
=== daemonset ===
NAME         DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR   AGE
node-agent   3         3         3       3            3           <none>          22s
```

이제 `DESIRED 3`으로 늘었고 **`learn-control-plane`을 포함한 세 노드 모두에 파드가 하나씩** 떴다. taint는 그대로 있지만, toleration을 얻은 파드는 그것을 견디고 배치될 수 있게 된 것이다.

`updateStrategy: RollingUpdate`(기본값) + `maxUnavailable: 1` 덕분에 위 로그처럼 노드를 **한 번에 하나씩 순차 교체**했다. 프로덕션에서 로그 수집기 같은 데몬셋을 업데이트할 때 전 노드가 동시에 죽지 않게 해주는 안전장치다. `type: OnDelete`로 바꾸면 자동 교체 대신 파드를 수동 삭제할 때만 새 버전이 뜬다.

> ⚠️ **함정**: 여기서 toleration은 **파드 스펙**에 넣은 것이라 클러스터 정책상 허용된다. 반대로 노드의 taint를 지우거나(`kubectl taint node ... -`) 라벨을 바꾸는 것은 **노드 변경**이라 이번 스텝 범위 밖이다. 같은 효과를 내더라도 "파드를 바꿀 것인가, 노드를 바꿀 것인가"는 운영 관점에서 전혀 다른 결정이다.

---

## 정리

| 리소스 | 용도 | 핵심 필드 | 종료 상태 |
|--------|------|-----------|-----------|
| **Job** | 한 번 끝나면 되는 배치 | `completions`, `parallelism`, `backoffLimit` | Complete / Failed |
| **CronJob** | 주기적 Job 생성 | `schedule`, `concurrencyPolicy`, `*JobsHistoryLimit` | (Job을 계속 생성) |
| **DaemonSet** | 노드마다 1개 | `selector`, `updateStrategy`, (replicas 없음) | 항상 Running |

| 개념 | 한 줄 요약 |
|------|-----------|
| `restartPolicy` | Job은 `Never`/`OnFailure`만. `Never`면 재시도 시 파드를 새로 만듦 |
| `backoffLimit` | 재시도 총 횟수. 초과 시 `BackoffLimitExceeded`로 Failed |
| `concurrencyPolicy` | Allow(겹침 허용) / Forbid(건너뜀) / Replace(교체) |
| taint / toleration | 노드가 밀어냄 ↔ 파드가 견딤. DaemonSet을 컨트롤플레인에 띄우려면 toleration 필요 |
| DaemonSet updateStrategy | RollingUpdate(순차 교체, 기본) / OnDelete(수동) |

## 연습 과제 → [challenge.md](./challenge.md)

## 다음 단계
→ [Step 13 — 스케줄링 제어](../step-13-scheduling/README.md)

---

## 실습 파일

이 스텝에서 쓰는 파일은 실행 스크립트 `commands.sh` 하나와 `manifests/` 아래 매니페스트 6개입니다. `commands.sh` 를 그대로 돌리면 네임스페이스 생성 → 단순 Job → 병렬 Job → 실패 Job → CronJob → DaemonSet → toleration 적용 → 네임스페이스 삭제까지 12-1 ~ 12-7 전 과정이 순서대로 재현됩니다. 매니페스트는 단계별로 하나씩 `kubectl apply` 해도 되며, 아래 순서가 곧 강의 본문의 진행 순서입니다.

### commands.sh

이 스텝 전체를 한 번에 재현하는 실행 스크립트입니다. `set -euo pipefail` 로 한 명령이라도 실패하면 즉시 중단되고, `cd "$(dirname "$0")"` 덕분에 어느 위치에서 실행하든 매니페스트의 상대 경로(`manifests/...`)가 깨지지 않습니다.

- **PATH 보정**: 셔뱅 바로 아래의 `export PATH="/opt/homebrew/bin:$PATH"` 는 Homebrew 로 설치한 `kubectl` 을 찾기 위한 줄입니다. macOS(Apple Silicon) 기준이므로, Intel 맥이나 리눅스에서 `kubectl: command not found` 가 뜨면 이 경로를 자신의 `kubectl` 위치(`which kubectl` 로 확인)로 바꾸거나 지우면 됩니다.
- **안전장치**: `test "$(kubectl config current-context)" = "kind-learn"` 으로 컨텍스트를 먼저 검사합니다. 실습용 kind 클러스터가 아니면 바로 종료되므로, 실수로 운영 클러스터에 Job/DaemonSet 을 뿌리는 사고를 막아줍니다.
- **멱등한 네임스페이스 생성**: `kubectl create namespace step12 --dry-run=client -o yaml | kubectl apply -f -` 는 이미 `step12` 가 있어도 에러가 나지 않습니다. `kubectl create namespace step12` 를 그대로 쓰면 재실행 시 `AlreadyExists` 로 죽기 때문에 이렇게 우회한 것입니다.
- **CronJob 대기 루프**: CronJob 은 매분 정각에만 발화하므로 결과를 바로 볼 수 없습니다. `for i in $(seq 1 30)` 루프가 15초 간격으로 `hello-cron` 이름의 Job 개수를 세다가 **2개 이상**(`[ "$n" -ge 2 ]`)이 되면 빠져나옵니다. 즉 최소 2번의 발화를 확인해야 "매 1분마다 새 Job 이 생긴다"는 것이 증명되기 때문입니다. 이 구간에서 최대 2~3분 정도 멈춰 있는 것이 정상입니다.
- **동기화 지점**: `kubectl wait --for=condition=complete` / `--for=condition=failed` / `kubectl rollout status` 를 곳곳에 넣어, 리소스가 실제로 완료·실패·롤아웃될 때까지 기다린 뒤 다음 `kubectl get` 을 찍습니다. 이 대기가 없으면 아직 `Pending` 인 상태를 출력하게 됩니다.
- **⚠️ 주의**: 스크립트 마지막의 `kubectl delete namespace step12` 는 **파괴적 명령**입니다. `step12` 네임스페이스의 Job·CronJob·DaemonSet·파드가 전부 삭제되므로, 결과를 더 관찰하고 싶다면 이 줄에 도달하기 전에 중단하세요. 반대로 노드에는 아무 변경도 가하지 않습니다(toleration 은 파드 스펙이기 때문입니다).

```bash file="./commands.sh"
```

### manifests/job-simple.yaml

12-1 에서 가장 먼저 적용하는 매니페스트로, "한 번 성공하면 끝나는" Job 의 최소 형태입니다. `completions: 1` 이라 파드 하나가 성공하면 Job 이 `Complete` 가 되고, `backoffLimit: 4` 는 실패했을 때 최대 4번까지 재시도한다는 뜻이지만 이 컨테이너는 항상 성공하므로 재시도는 일어나지 않습니다.

핵심은 `restartPolicy: Never` 입니다. Job 의 파드는 `Never` 또는 `OnFailure` 만 허용되며, 기본값인 `Always` 를 두면 API 서버가 `apply` 자체를 거부합니다. 컨테이너 커맨드 `["sh", "-c", "echo hello from job; sleep 2; echo done"]` 은 2초 뒤 종료 코드 0 으로 끝나므로, 파드는 `Running` 을 거쳐 `Completed` 상태로 남습니다. 파드가 삭제되지 않고 남아 있어야 `kubectl logs -n step12 job/hello-job` 으로 로그를 볼 수 있습니다.

```yaml file="./manifests/job-simple.yaml"
```

### manifests/job-parallel.yaml

12-2 의 병렬 Job 입니다. `completions: 6` 과 `parallelism: 2` 가 조합되어 **총 6번을 2개씩 3번의 물결로** 처리합니다. 컨테이너가 `sleep 3` 이므로 전체는 대략 3초 × 3웨이브 + 스케줄링 오버헤드만큼 걸리고, 실행 결과의 파드 AGE 가 `17s / 12s / 6s` 로 세 그룹으로 나뉘는 이유가 바로 이것입니다.

커맨드의 `echo processing on $(hostname)` 은 파드마다 다른 호스트명(=파드 이름)을 출력해 6개가 서로 다른 파드에서 돌았음을 확인시켜 줍니다. `backoffLimit: 4` 는 여기서도 안전망일 뿐 발동하지 않습니다. 실행 도중 `kubectl get pod -l job-name=parallel-job` 을 찍으면 동시에 살아 있는 파드가 2개를 넘지 않는 것을 볼 수 있는데, 타이밍을 놓치기 쉬워 `commands.sh` 에서는 `sleep 4` 를 넣어 관찰 시점을 맞춰 두었습니다.

```yaml file="./manifests/job-parallel.yaml"
```

### manifests/job-fail.yaml

12-3 에서 쓰는 **의도적으로 실패하도록 만든 매니페스트**입니다. 컨테이너 커맨드가 `["sh", "-c", "echo trying...; exit 1"]` 이라 **항상 종료 코드 1** 로 끝납니다. 이 실패는 버그가 아니라 학습 포인트입니다.

- `backoffLimit: 2` 이므로 **최초 실행 1 + 재시도 2 = 파드 3개**가 만들어지고, 전부 `Error` 상태로 남습니다.
- 3개가 모두 실패하면 Job 은 `BackoffLimitExceeded` 사유로 **Failed** 처리됩니다. `kubectl describe job fail-job -n step12` 하단 이벤트에서 `SuccessfulCreate` 3회 뒤 `BackoffLimitExceeded` 경고를 확인하세요.
- 재시도 간격은 지수 백오프(10초 → 20초 …)라 파드들의 AGE 가 `33s / 23s / 3s` 처럼 벌어집니다. 그래서 이 Job 은 실패가 확정되기까지 30초 이상 걸리고, `commands.sh` 가 `--timeout=120s` 를 준 것도 그 때문입니다.
- `restartPolicy: Never` 이므로 `RESTARTS` 컬럼은 계속 **0** 입니다. 재시도가 "컨테이너 재시작"이 아니라 "파드 재생성"으로 일어나기 때문이며, 이것을 `OnFailure` 로 바꿔보는 것이 [challenge.md](./challenge.md) 과제 2 입니다.

```yaml file="./manifests/job-fail.yaml"
```

### manifests/cronjob.yaml

12-4 에서 적용하는 CronJob 으로, `jobTemplate` 안에 Job 스펙을 통째로 품고 있는 이중 구조가 특징입니다. `schedule: "*/1 * * * *"` 은 매 1분 정각마다 발화한다는 뜻이며, 발화할 때마다 `hello-cron-<에폭분>` 이름의 Job 을 새로 찍어냅니다.

- `concurrencyPolicy: Forbid` — 이전 Job 이 아직 돌고 있으면 새 스케줄을 **건너뜁니다**. 여기 컨테이너는 `sleep 2` 라 1분 주기와 겹칠 일이 없지만, 실행 시간이 주기보다 긴 배치에서는 이 설정이 중복 실행을 막아줍니다.
- `successfulJobsHistoryLimit: 3` / `failedJobsHistoryLimit: 1` — 성공 Job 은 3개, 실패 Job 은 1개까지만 보관하고 그보다 오래된 것은 파드와 함께 자동 삭제됩니다. 매분 Job 이 쌓여도 클러스터가 넘치지 않는 이유입니다.
- `jobTemplate.spec.backoffLimit: 2` 는 CronJob 이 만드는 **각 Job** 에 적용되는 값입니다. CronJob 스펙이 아니라 Job 스펙 위치에 있다는 점에 주의하세요.
- **⚠️ 관찰 시 주의**: 적용 직후에는 아무것도 보이지 않습니다. 다음 분 정각까지 최대 60초를 기다려야 첫 Job 이 생기고, "1분 간격"을 눈으로 확인하려면 2분 이상 기다려야 합니다. 또 스케줄 기준 시각은 컨트롤플레인의 시간대(기본 UTC)이므로, 실제 배치라면 `spec.timeZone` 을 함께 지정해야 합니다.

```yaml file="./manifests/cronjob.yaml"
```

### manifests/daemonset.yaml

12-5 의 DaemonSet 초기 버전입니다. `replicas` 필드가 **없다**는 점이 Deployment 와의 결정적 차이로, 파드 개수는 사람이 아니라 "이 파드가 갈 수 있는 노드 수"가 정합니다. `selector.matchLabels.app: node-agent` 와 `template.metadata.labels.app: node-agent` 가 반드시 일치해야 하며, 어긋나면 API 서버가 매니페스트를 거부합니다.

`updateStrategy.type: RollingUpdate` + `rollingUpdate.maxUnavailable: 1` 은 이미지를 바꿔 재배포할 때 **노드를 한 번에 하나씩만** 교체한다는 뜻입니다. 로그 수집기 같은 데몬이 전 노드에서 동시에 죽는 것을 막는 안전장치이며, 실제 효과는 다음 파일을 적용할 때 롤아웃 로그(`1 out of 3 → 2 out of 3 …`)로 드러납니다. 컨테이너는 `nginx:1.27-alpine` 에 `containerPort: 80` 을 노출한 더미 에이전트일 뿐입니다.

이 매니페스트를 적용하면 노드가 3대인데도 파드는 **2개만** 뜹니다(`DESIRED 2`). 컨트롤플레인 노드에 걸린 `NoSchedule` taint 를 견딜 toleration 이 없기 때문이며, 이 "왜 2개지?"라는 의문이 12-6, 12-7 로 이어지는 학습 흐름입니다.

```yaml file="./manifests/daemonset.yaml"
```

### manifests/daemonset-toleration.yaml

12-7 에서 **같은 이름(`node-agent`)의 DaemonSet 을 덮어쓰기 위해** 적용하는 파일입니다. 위 `daemonset.yaml` 과 비교하면 차이는 파드 스펙에 추가된 `tolerations` 블록 하나뿐입니다.

```yaml
tolerations:
  - key: node-role.kubernetes.io/control-plane
    operator: Exists
    effect: NoSchedule
```

- `key: node-role.kubernetes.io/control-plane` 은 12-6 에서 `kubectl describe node learn-control-plane | grep -i taint` 로 확인한 taint 의 키와 정확히 같습니다. 이 키가 틀리면 toleration 이 매칭되지 않아 여전히 워커 2대에만 뜹니다.
- `operator: Exists` 는 "값이 무엇이든 이 키가 존재하기만 하면 견딘다"는 뜻이라 `value` 를 적을 필요가 없습니다. `Equal` 을 쓰려면 `value` 를 정확히 맞춰야 합니다.
- `effect: NoSchedule` 로 견딜 효과를 한정합니다. 노드의 taint effect 와 일치해야 합니다.
- 적용 결과 `DESIRED` 가 2 → **3** 으로 늘고 `learn-control-plane` 을 포함한 세 노드 모두에 파드가 하나씩 뜹니다. **노드의 taint 는 그대로 남아 있고**, 바뀐 것은 파드 쪽뿐이라는 점이 핵심입니다. 노드에서 taint 를 제거하는 방식과 결과는 비슷해 보여도 운영상 완전히 다른 결정입니다.
- 순서 의존: 반드시 `daemonset.yaml` 을 먼저 적용해 "2개만 뜬다"를 확인한 뒤 이 파일을 적용해야 `DESIRED 2 → 3` 변화와 순차 롤링 업데이트 로그를 볼 수 있습니다.

```yaml file="./manifests/daemonset-toleration.yaml"
```
