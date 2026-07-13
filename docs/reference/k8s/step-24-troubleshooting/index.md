# Step 24 — 트러블슈팅 플레이북

> **학습 목표**
> - 파드가 안 뜨는 대표 증상 6가지(ImagePullBackOff / CrashLoopBackOff / Pending / OOMKilled / Service 미접속 / ContainerCreating 지속)를 **직접 재현**한다
> - 각 증상마다 **증상 → 원인 후보 → 진단 명령 → 해결** 순서로 사고하는 습관을 만든다
> - `describe`, `get events`, `logs --previous`, `get -o yaml`, `exec`, `debug` 를 언제 꺼내 쓰는지 몸에 익힌다
>
> **선행 스텝**: [Step 04 — 레이블·네임스페이스](../step-04-labels-namespaces/README.md), [Step 05 — 디플로이먼트](../step-05-deployments/README.md)
> **예상 소요**: 60분

---

## 24-0. 트러블슈팅의 3단 사고법

장애를 만나면 도구를 아무거나 던지지 말고 항상 같은 순서로 좁혀 들어갑니다.

1. **증상 확인** — `kubectl get pods` 의 `STATUS` 와 `RESTARTS` 컬럼이 1차 진단서다. 상태 이름 자체가 원인 카테고리를 알려준다.
2. **증거 수집** — `kubectl describe` 의 **Events** 와 **Last State**, 그리고 `kubectl logs --previous`. 여기 90%의 답이 있다.
3. **가설 검증·수정** — 원인 후보를 하나씩 지우고, 고친 뒤 **회복을 눈으로 확인**한다.

이 스텝은 전부 네임스페이스 `step24` 안에서 재현합니다. 아래 표가 이번에 다루는 증상 지도입니다.

| STATUS 로 보이는 것 | 한 줄 원인 | 첫 번째로 볼 곳 |
|---|---|---|
| `ImagePullBackOff` / `ErrImagePull` | 이미지명·태그·레지스트리·권한 | describe Events |
| `CrashLoopBackOff` | 컨테이너가 시작 직후 비정상 종료 | `logs --previous` |
| `Pending` | 스케줄 불가(자원/PVC/taint) | describe Events (FailedScheduling) |
| `OOMKilled` | 메모리 limit 초과 → SIGKILL(137) | `get -o yaml` lastState |
| `Running` 인데 접속 불가 | Service 셀렉터/포트 불일치 | `get endpoints` |
| `ContainerCreating` 지속 | 볼륨/Secret/ConfigMap 마운트 실패 | describe Events (FailedMount) |

---

## 24-1. ImagePullBackOff — 이미지를 못 가져온다

### 증상
파드가 `Running` 으로 넘어가지 못하고 `ErrImagePull` → `ImagePullBackOff` 를 오간다.

```bash
kubectl apply -f manifests/01-imagepullbackoff.yaml
kubectl get pod broken-image -n step24
```
```
NAME           READY   STATUS             RESTARTS   AGE
broken-image   0/1     ImagePullBackOff   0          84s
```

### 원인 후보
- 이미지 **이름 오타** 또는 **존재하지 않는 태그**(가장 흔함)
- 프라이빗 레지스트리인데 **imagePullSecret 누락**(401/403)
- 레지스트리 주소 오타 / 네트워크 차단
- `imagePullPolicy: Always` 인데 로컬에만 있는 이미지

### 진단 명령
`describe` 의 **Events** 가 정확한 실패 이유를 문자열로 알려줍니다.

```bash
kubectl describe pod broken-image -n step24 | sed -n '/Events:/,$p'
```
```
Events:
  Type     Reason     Age                From               Message
  ----     ------     ----               ----               -------
  Normal   Scheduled  84s                default-scheduler  Successfully assigned step24/broken-image to learn-worker
  Normal   BackOff    15s (x4 over 82s)  kubelet            spec.containers{web}: Back-off pulling image "nginx:1.27-does-not-exist"
  Warning  Failed     15s (x4 over 82s)  kubelet            spec.containers{web}: Error: ImagePullBackOff
  Normal   Pulling    2s (x4 over 84s)   kubelet            spec.containers{web}: Pulling image "nginx:1.27-does-not-exist"
  Warning  Failed     1s (x4 over 83s)   kubelet            spec.containers{web}: Failed to pull image "nginx:1.27-does-not-exist": rpc error: code = NotFound desc = failed to pull and unpack image "docker.io/library/nginx:1.27-does-not-exist": failed to resolve reference "docker.io/library/nginx:1.27-does-not-exist": docker.io/library/nginx:1.27-does-not-exist: not found
```

핵심은 마지막 줄 `... not found`. **태그가 레지스트리에 없다**는 뜻입니다. 401/403 이면 인증(imagePullSecret) 문제, `no such host` 면 레지스트리 주소 문제로 갈라집니다.

### 해결
올바른 태그로 교체합니다.

```bash
kubectl set image pod/broken-image web=nginx:1.27-alpine -n step24
kubectl get pod broken-image -n step24
```
```
NAME           READY   STATUS    RESTARTS   AGE
broken-image   1/1     Running   0          100s
```

> 💡 **팁**: `ImagePullBackOff` 의 `BackOff` 는 kubelet 이 **재시도 간격을 지수적으로 늘리며** 계속 당기고 있다는 뜻입니다. 이미지를 고치면 다음 재시도 때 알아서 성공합니다. 파드를 지웠다 다시 만들 필요가 없을 때가 많습니다.

---

## 24-2. CrashLoopBackOff — 시작하자마자 죽는다

### 증상
`RESTARTS` 카운트가 계속 오르고, 상태가 `Error` 와 `CrashLoopBackOff` 를 반복합니다.

```bash
kubectl apply -f manifests/02-crashloopbackoff.yaml
kubectl get pod crasher -n step24
```
```
NAME      READY   STATUS             RESTARTS      AGE
crasher   0/1     CrashLoopBackOff   4 (71s ago)   2m53s
```

> `CrashLoopBackOff` 는 에러가 아니라 **kubelet 의 재시작 대기 상태**입니다. "죽어서 → 잠깐 기다렸다가 → 또 시작" 을 반복 중이라는 뜻. 재시작 간격이 최대 5분까지 늘어납니다.

### 원인 후보
- 앱이 **설정 오류/필수 환경변수 누락**으로 즉시 종료
- 존재하지 않는 파일·DB 등 **의존성 부재**로 예외 종료
- 커맨드/엔트리포인트 자체가 잘못됨
- **liveness probe** 가 계속 실패해서 kubelet 이 죽임 (probe 설정 확인)

### 진단 명령
지금 컨테이너는 이미 죽고 새로 뜨는 중이라, **직전에 죽은** 컨테이너의 로그를 봐야 합니다. `--previous` 가 핵심입니다.

```bash
kubectl logs crasher -n step24 --previous
```
```
boot up...
boom, fatal error
```

`describe` 로 **Last State: Terminated** 와 종료 코드, 그리고 Back-off 이벤트를 확인합니다.

```bash
kubectl describe pod crasher -n step24
```
```
    State:          Waiting
      Reason:       CrashLoopBackOff
    Last State:     Terminated
      Reason:       Error
      Exit Code:    1
      Started:      Mon, 13 Jul 2026 11:56:34 +0900
      Finished:     Mon, 13 Jul 2026 11:56:34 +0900
    Restart Count:  3
...
Events:
  Warning  BackOff  57s (x3 over 99s)  kubelet  spec.containers{app}: Back-off restarting failed container app in pod crasher_step24(...)
```

**Started 와 Finished 시각이 같다** = 시작하자마자 죽었다는 결정적 단서. Exit Code 1 은 앱이 스스로 실패로 종료했다는 뜻(137 이면 OOM/시그널, 아래 24-4 참고).

### 해결
로그가 알려준 실패 원인을 고칩니다. 여기서는 커맨드가 `exit 1` 로 죽는 게 문제였으니, 정상적으로 계속 도는 프로세스로 바꿉니다(실무에선 누락된 설정/의존성을 채우는 일).

```bash
kubectl delete pod crasher -n step24 --now
kubectl run crasher --image=busybox:1.36 -n step24 --labels=demo=crashloopbackoff \
  --command -- sh -c "echo 'boot up...'; echo 'running ok'; sleep 3600"
kubectl get pod crasher -n step24
```
```
NAME      READY   STATUS    RESTARTS   AGE
crasher   1/1     Running   0          6s
```

> ⚠️ **함정**: CrashLoopBackOff 파드에 `kubectl logs`(--previous 없이)를 치면 "방금 막 뜬" 컨테이너 로그라 비어 있거나 `unable to retrieve container logs` 가 나기 쉽습니다. **죽은 원인은 항상 `--previous`** 에 있습니다.

---

## 24-3. Pending — 스케줄러가 파드를 배치하지 못한다

`Pending` 은 **아직 어떤 노드에도 배치되지 않았다**는 뜻입니다. 파드는 자원을 실제로 쓰고 있지 않습니다. 원인은 크게 (a) 스케줄 불가와 (b) 볼륨 미준비 두 갈래입니다.

### 3a. 자원 부족으로 스케줄 불가

```bash
kubectl apply -f manifests/03a-pending-cpu.yaml
kubectl describe pod too-big -n step24 | sed -n '/Events:/,$p'
```
```
Events:
  Type     Reason            Age                    From               Message
  ----     ------            ----                   ----               -------
  Warning  FailedScheduling  108s (x25 over 3m12s)  default-scheduler  0/3 nodes are available: 1 node(s) had untolerated taint(s), 2 Insufficient cpu. no new claims to deallocate, preemption: 0/3 nodes are available: 3 Preemption is not helpful for scheduling.
```

**`0/3 nodes are available` + `Insufficient cpu`** 가 결정적입니다. cpu `1000` 코어를 요청했으니 어떤 워커도 못 받습니다(`1 node(s) had untolerated taint(s)` 는 컨트롤플레인 노드라 원래 일반 파드가 못 가는 것).

**원인 후보**: 과한 requests(cpu/memory), 노드 taint 미허용, nodeSelector/affinity 불충족, nodePort 충돌.

**해결** — 현실적인 요청으로 낮춥니다(또는 노드 증설).

```bash
kubectl delete pod too-big -n step24 --now
# requests.cpu 를 100m 로 낮춰 재생성
kubectl get pod too-big -n step24
```
```
NAME      READY   STATUS    RESTARTS   AGE
too-big   1/1     Running   0          6s
```

### 3b. PVC 미바인딩으로 스케줄 불가

존재하지 않는 StorageClass 를 참조하는 PVC 는 영원히 `Pending` 이고, 그 PVC 를 마운트하는 파드도 함께 묶여 `Pending` 이 됩니다.

```bash
kubectl apply -f manifests/03b-pending-pvc.yaml
kubectl get pvc ghost-pvc -n step24
kubectl describe pod waits-for-pvc -n step24 | sed -n '/Events:/,$p'
kubectl describe pvc ghost-pvc -n step24 | sed -n '/Events:/,$p'
```
```
# PVC
NAME        STATUS    VOLUME   CAPACITY   ACCESS MODES   STORAGECLASS     AGE
ghost-pvc   Pending                                      does-not-exist   3m11s

# Pod Events
  Warning  FailedScheduling  61s (x6 over 3m12s)  default-scheduler  0/3 nodes are available: pod has unbound immediate PersistentVolumeClaims. not found

# PVC Events
  Warning  ProvisioningFailed  12s (x13 over 3m12s)  persistentvolume-controller  storageclass.storage.k8s.io "does-not-exist" not found
```

파드 이벤트는 `unbound immediate PersistentVolumeClaims`, PVC 이벤트는 정확히 `storageclass ... not found`. **파드가 아니라 PVC 를 봐야** 진짜 원인이 나옵니다.

**해결** — 실제 존재하는 StorageClass 를 확인하고 PVC 를 다시 만듭니다.

```bash
kubectl get storageclass
# NAME                 PROVISIONER             ...
# standard (default)   rancher.io/local-path   ...
```
`standard` 로 PVC 를 재생성하니 `WaitForFirstConsumer` 정책에 따라 파드가 뜨는 순간 바인딩됩니다.
```
NAME        STATUS   VOLUME                                     CAPACITY   ...   STORAGECLASS
ghost-pvc   Bound    pvc-7691719f-bcce-47ad-...                 1Gi              standard

NAME            READY   STATUS    RESTARTS   AGE
waits-for-pvc   1/1     Running   0          8s
```

> 💡 **팁**: `Pending` 의 원인은 거의 항상 **describe 의 Events** 한 줄에 있습니다. `get pod` 만 보고 "왜 안 뜨지" 하지 말고 바로 `describe` 로 내려가세요. PVC 문제면 **PVC 오브젝트의 이벤트**까지 따라가야 합니다.

---

## 24-4. OOMKilled — 메모리 한도를 넘겨 커널이 죽인다

### 증상
`RESTARTS` 가 오르고, 상태에 `OOMKilled` 가 찍힙니다. CrashLoopBackOff 와 비슷해 보이지만 **종료 코드 137** 로 구분됩니다.

```bash
kubectl apply -f manifests/04-oomkilled.yaml
kubectl get pod memory-hog -n step24
```
```
NAME         READY   STATUS      RESTARTS      AGE
memory-hog   0/1     OOMKilled   2 (30s ago)   31s
```

### 원인 후보
- 컨테이너 `resources.limits.memory` 가 **실제 사용량보다 작음**
- 메모리 누수 / 예상보다 큰 데이터 처리
- JVM 등 런타임이 cgroup limit 을 인식 못 하고 힙을 크게 잡음

### 진단 명령
`get -o yaml` 의 `lastState.terminated` 에 **reason: OOMKilled, exitCode: 137** 이 명확히 남습니다.

```bash
kubectl get pod memory-hog -n step24 -o yaml | sed -n '/lastState:/,/name:/p'
```
```yaml
    lastState:
      terminated:
        containerID: containerd://326df9451c191356ad8ba708...
        exitCode: 137
        finishedAt: "2026-07-13T03:01:05Z"
        reason: OOMKilled
        startedAt: "2026-07-13T03:01:05Z"
    name: hog
```

`describe` 로도 같은 정보를 봅니다.
```
    Last State:     Terminated
      Reason:       OOMKilled
      Exit Code:    137
    Restart Count:  2
    Limits:
      memory:  32Mi
```

**137 = 128 + 9(SIGKILL)**. 커널 OOM killer 가 프로세스를 강제 종료했다는 뜻입니다.

### 해결
한도를 실제 사용량에 맞게 올리거나(또는 앱의 메모리 사용을 줄이거나) 합니다.

```bash
kubectl delete pod memory-hog -n step24 --now
# limits.memory 를 64Mi 로 상향해 재생성
kubectl get pod memory-hog -n step24
```
```
NAME         READY   STATUS    RESTARTS   AGE
memory-hog   1/1     Running   0          6s
```

> ⚠️ **함정**: 한도를 **너무 낮게**(예: 16Mi) 주면 컨테이너가 아예 **시작조차 못 하고** `StartError`/exit `128` 이 납니다("container init was OOM-killed"). 이건 정상 실행 중 OOM(137)과 다른 상태입니다. "실행 중 OOM(137)" 을 재현하려면 컨테이너가 뜰 만큼(32Mi)은 주고, 그 뒤 워크로드가 한도를 넘게 만들어야 합니다.

> 💡 **팁**: `CrashLoopBackOff` 인데 exit code 가 137 이면 OOM 을 의심하세요. `Exit Code` 가 1·2 면 앱 자체 에러, 137 이면 메모리(또는 외부 SIGKILL), 143(=128+15) 이면 SIGTERM 종료입니다.

---

## 24-5. Service 로 접속이 안 된다 — Endpoints 가 비어있다

### 증상
파드는 `Running` 인데, Service 로 접속하면 연결이 안 됩니다. Deployment/파드는 멀쩡하니 "네트워크가 이상하다" 고 오해하기 쉽습니다.

```bash
kubectl apply -f manifests/05-service-endpoints.yaml
kubectl get pod echo-server -n step24 --show-labels
kubectl get endpoints echo-svc -n step24
```
```
NAME          READY   STATUS    RESTARTS   AGE   LABELS
echo-server   1/1     Running   0          5m37s   app=echo

NAME       ENDPOINTS   AGE
echo-svc   <none>      5m37s      # ← 백엔드가 하나도 안 잡혔다
```

`ENDPOINTS` 가 `<none>` 이면 **Service 뒤에 파드가 하나도 연결되지 않았다**는 뜻. 접속이 될 리가 없습니다.

```bash
# 클러스터 내부에서 접속 시도 (실패 확인)
kubectl run curl-test --image=registry.k8s.io/e2e-test-images/agnhost:2.47 -n step24 --restart=Never \
  --command -- sh -c "wget -qO- --timeout=3 http://echo-svc.step24.svc.cluster.local || echo '접속 실패'"
kubectl logs curl-test -n step24
```
```
wget: can't connect to remote host (10.96.182.71): Connection refused
접속 실패
```

### 원인 후보
- **Service `selector` 와 파드 `labels` 불일치**(오타 포함) — 가장 흔함
- `targetPort` 가 컨테이너 실제 포트와 다름
- 파드가 `Ready` 가 아님(readinessProbe 실패) → Endpoints 에서 빠짐

### 진단 명령
셀렉터와 실제 파드 레이블을 나란히 비교합니다.

```bash
kubectl get svc echo-svc -n step24 -o jsonpath='{.spec.selector}'; echo
```
```
{"app":"web"}      # Service 는 app=web 을 찾는데
```
파드 레이블은 위에서 본 대로 `app=echo`. **`web` ≠ `echo`** 이므로 매칭 0개, 그래서 Endpoints 가 비었습니다.

### 해결
셀렉터를 실제 파드 레이블에 맞춥니다(반대로 파드 레이블을 고쳐도 됨).

```bash
kubectl patch svc echo-svc -n step24 -p '{"spec":{"selector":{"app":"echo"}}}'
kubectl get endpoints echo-svc -n step24
```
```
NAME       ENDPOINTS          AGE
echo-svc   10.244.1.49:8080   5m58s      # ← 백엔드가 채워졌다
```
다시 접속하면 성공합니다.
```
hello from echo
```

> 💡 **팁**: "Service 로 접속 안 됨" 을 만나면 **가장 먼저 `kubectl get endpoints <svc>`(또는 `get endpointslice`)** 를 보세요. 비어 있으면 셀렉터/포트/Readiness 문제(파드 쪽), 채워져 있는데도 안 되면 그때 네트워크 정책·kube-proxy·DNS 를 의심합니다. 순서가 중요합니다.

---

## 24-6. ContainerCreating 에서 멈춘다 — 볼륨을 못 만든다

### 증상
파드가 `ContainerCreating` 에서 몇 분째 넘어가지 않습니다. `Pending` 은 아닙니다(노드엔 배치됐음). 컨테이너를 만들기 직전 단계에서 막힌 겁니다.

```bash
kubectl apply -f manifests/06-containercreating.yaml
kubectl get pod needs-secret -n step24
```
```
NAME           READY   STATUS              RESTARTS   AGE
needs-secret   0/1     ContainerCreating   0          6m23s
```

### 원인 후보
- 참조하는 **Secret/ConfigMap 이 존재하지 않음**(볼륨/`envFrom`)
- PVC 는 Bound 인데 실제 **볼륨 attach/mount 실패**(스토리지 백엔드)
- CNI 가 파드 네트워크(IP) 할당에 실패

### 진단 명령
`describe` 의 Events 에 `FailedMount` 가 정확한 이유와 함께 뜹니다.

```bash
kubectl describe pod needs-secret -n step24 | sed -n '/Events:/,$p'
```
```
Events:
  Type     Reason       Age                   From               Message
  ----     ------       ----                  ----               -------
  Normal   Scheduled    6m23s                 default-scheduler  Successfully assigned step24/needs-secret to learn-worker2
  Warning  FailedMount  11s (x11 over 6m23s)  kubelet            MountVolume.SetUp failed for volume "cred" : secret "app-credentials" not found
```

`secret "app-credentials" not found` — 마운트하려는 Secret 이 없습니다.

### 해결
누락된 Secret 을 만듭니다. kubelet 이 backoff 재시도 중이라, 만들어 두면 다음 재시도에서 자동으로 마운트되고 컨테이너가 뜹니다(수 초~2분 소요).

```bash
kubectl create secret generic app-credentials -n step24 \
  --from-literal=username=admin --from-literal=password=s3cr3t
kubectl get pod needs-secret -n step24
kubectl exec needs-secret -n step24 -- ls -l /etc/cred
```
```
NAME           READY   STATUS    RESTARTS   AGE
needs-secret   1/1     Running   0          8m16s

total 0
lrwxrwxrwx 1 root root 15 Jul 13 03:04 password -> ..data/password
lrwxrwxrwx 1 root root 15 Jul 13 03:04 username -> ..data/username
```
회복 직후 이벤트에는 `FailedMount` 뒤에 `Created`/`Started` 가 이어 붙습니다.
```
  Warning  FailedMount  2m4s (x11 over 8m16s)  kubelet  MountVolume.SetUp failed ... "app-credentials" not found
  Normal   Pulled       1s                     kubelet  Container image "nginx:1.27-alpine" already present ...
  Normal   Created      1s                     kubelet  Container created
  Normal   Started      1s                     kubelet  Container started
```

> ⚠️ **함정**: kubelet 의 마운트 재시도는 **지수 backoff** 라, Secret 을 만들어도 즉시 뜨지 않고 최대 ~2분 기다릴 수 있습니다. 급하면 파드를 지웠다 다시 만들면(또는 롤아웃 재시작) 즉시 재시도합니다. 로그가 안 나오는 게 정상이니(컨테이너가 아직 없음) `describe` 이벤트만 믿으세요.

---

## 24-7. 진단 도구 요약 — 언제 무엇을 꺼낼까

| 도구 | 언제 쓰나 | 대표 명령 |
|---|---|---|
| `kubectl get -o wide` | 1차 분류. STATUS·RESTARTS·NODE·IP 를 한눈에 | `kubectl get pods -n step24 -o wide` |
| `kubectl describe` | **Events + Last State** 확인. 스케줄/마운트/풀 실패의 답이 여기 | `kubectl describe pod <p> -n step24` |
| `kubectl get events --sort-by` | 시간순으로 클러스터에서 무슨 일이 있었는지 훑기 | `kubectl get events -n step24 --sort-by=.lastTimestamp` |
| `kubectl logs --previous` | **죽은(이전) 컨테이너**의 stdout. CrashLoop 필수 | `kubectl logs <p> -n step24 --previous` |
| `kubectl get -o yaml` | 필드 원본 확인. `lastState.terminated`, 실제 requests/limits, 셀렉터 | `kubectl get pod <p> -n step24 -o yaml` |
| `kubectl exec` | **살아있는** 컨테이너 내부에서 파일/포트/DNS 확인 | `kubectl exec <p> -n step24 -- ls /etc/cred` |
| `kubectl debug`(임시 컨테이너) | 셸·툴이 없는 이미지(distroless/http-echo)에 busybox 를 붙여 진단 | `kubectl debug <p> -n step24 -it --image=busybox:1.36 --target=<c> -- sh` |

`get events --sort-by` 실제 출력(발췌):
```
2m18s   Warning   FailedMount   pod/needs-secret   MountVolume.SetUp failed for volume "cred" : secret "app-credentials" not found
15s     Normal    Started       pod/needs-secret   Container started
```

`kubectl debug` 로 툴이 없는 `echo-server`(http-echo 이미지)에 busybox 임시 컨테이너를 붙여 내부 접속을 확인한 실제 결과:
```bash
kubectl debug -n step24 echo-server -it --image=busybox:1.36 --target=echo \
  -- sh -c "wget -qO- http://localhost:8080"
```
```
hello from echo
```
임시 컨테이너는 대상 파드의 네트워크·프로세스 네임스페이스를 공유하므로, **원본 파드를 재시작하지 않고** `localhost` 로 앱을 찔러볼 수 있습니다. 진단이 끝나면 파드에서 자동으로 사라집니다.

---

## 정리

| 증상(STATUS) | 결정적 진단 위치 | 대표 원인 | 해결 |
|---|---|---|---|
| ImagePullBackOff | describe Events (`not found`/`401`) | 태그 오타, 인증 누락 | 이미지/시크릿 수정 |
| CrashLoopBackOff | `logs --previous`, Last State | 앱 즉시 종료, 설정 누락 | 로그가 가리키는 원인 수정 |
| Pending | describe Events (`FailedScheduling`) | 자원 부족, PVC 미바인딩 | requests 조정, SC 수정 |
| OOMKilled (137) | `get -o yaml` lastState | memory limit 부족 | limit 상향/메모리 절감 |
| Running·접속불가 | `get endpoints` = `<none>` | 셀렉터/포트 불일치 | 셀렉터 정합 |
| ContainerCreating | describe Events (`FailedMount`) | Secret/ConfigMap 부재 | 누락 리소스 생성 |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 25 — 종합 실습](../step-25-final-project/README.md)

---

## 실습 파일

이 스텝의 매니페스트는 전부 **일부러 고장 낸 파일**입니다. `manifests/` 아래 **7개 파일**이 24-1 ~ 24-6 의 증상 6가지를 재현합니다(24-3 의 `Pending` 만 `03a`·`03b` 두 갈래로 나뉩니다). 각 파일 안의 딱 한 줄(잘못된 태그, `exit 1`, `cpu: "1000"`, 없는 StorageClass, 낮은 memory limit, 어긋난 셀렉터, 없는 Secret)이 장애의 원인입니다. `commands.sh` 는 이 7개를 한꺼번에 적용해 장애를 재현한 뒤, 절 순서대로 진단 → 해결 → 회복 확인까지 자동으로 훑고 마지막에 네임스페이스를 통째로 지웁니다.

### commands.sh

강의 전체(24-0 ~ 24-7)를 한 번에 따라가는 실행 스크립트입니다. 맨 앞에서 `cd "$(dirname "$0")"` 로 스크립트 위치로 이동해 `manifests/` 상대경로가 항상 맞도록 하고, `kubectl create ns step24 --dry-run=client -o yaml | kubectl apply -f -` 로 네임스페이스를 **멱등하게** 만듭니다(이미 있어도 에러가 나지 않습니다). 이어서 7개 매니페스트를 모두 적용한 뒤 `sleep 30` 으로 "장애가 무르익도록" 기다립니다. kubelet 의 backoff 가 몇 번 돌아야 `ImagePullBackOff`·`CrashLoopBackOff` 같은 상태 이름이 실제로 찍히기 때문입니다.

- 각 절마다 **진단 명령 → 해결 명령 → 회복 확인** 3단으로 묶여 있어, 본문의 사고 순서를 그대로 손으로 익힐 수 있습니다.
- 해결 단계는 대부분 `kubectl delete pod ... --now` 후 `kubectl run ... --overrides=...` 로 **고친 스펙을 즉석에서 재생성**합니다. 파드의 `resources` 나 `command` 는 수정할 수 없는(immutable) 필드라 patch 로는 못 고치기 때문입니다. 반대로 24-1 의 이미지 태그는 `kubectl set image` 로 바로 교체됩니다.
- 3b 의 `kubectl apply -f manifests/03b-pending-pvc.yaml || true` 에 붙은 `|| true` 는, 이미 `standard` SC 로 다시 만든 PVC 를 원본 매니페스트가 다시 덮어쓰려다 실패하는 것을 무시하기 위한 장치입니다(파드만 재생성하는 게 목적).
- `set -euo pipefail` 이 걸려 있어 중간 명령이 하나라도 실패하면 즉시 멈춥니다. 그래서 실패가 예상되는 곳(`kubectl wait`, `kubectl debug`)에는 `|| true` 가 붙어 있습니다.
- ⚠️ **주의**: 맨 끝의 정리 단계 `kubectl delete namespace step24` 는 `step24` 안의 **모든 리소스를 파괴**합니다(그 뒤 `kubectl get ns` 로 삭제를 확인합니다). 중간에서 멈춰 상태를 관찰하고 싶다면 이 줄까지 실행하지 마세요. 또 `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS(Homebrew) 로컬 환경 전제입니다.

```bash file="./commands.sh"
```

### manifests/01-imagepullbackoff.yaml

24-1 의 `ImagePullBackOff` 를 재현하는 단일 파드입니다. `image: nginx:1.27-does-not-exist` 가 유일한 고장 지점으로, 레지스트리에 **존재하지 않는 태그**를 가리킵니다. 이미지 이름(`nginx`) 자체는 정상이므로 kubelet 은 docker.io 까지는 잘 도달하지만 태그 해석에 실패해 `failed to resolve reference ... not found` 를 남기고, 지수 backoff 로 재시도하면서 `ErrImagePull` ↔ `ImagePullBackOff` 를 오갑니다.

`labels.demo: imagepullbackoff` 는 증상별로 파드를 골라보기 위한 표식일 뿐 장애와는 무관합니다. 파일 첫 줄 주석대로 정상 태그(`nginx:1.27-alpine`)는 클러스터에 이미 캐시돼 있어, `kubectl set image` 로 태그만 바꾸면 파드를 지우지 않고도 다음 재시도에서 곧바로 `Running` 이 됩니다.

```yaml file="./manifests/01-imagepullbackoff.yaml"
```

### manifests/02-crashloopbackoff.yaml

24-2 의 `CrashLoopBackOff` 재현용입니다. 고장 지점은 `command: ["sh", "-c", "echo 'boot up...'; echo 'boom, fatal error'; exit 1"]` — 컨테이너가 메시지 두 줄을 찍자마자 **종료 코드 1** 로 스스로 죽습니다. 파드의 기본 `restartPolicy: Always` 때문에 kubelet 이 계속 되살리고, 재시작 간격이 10초 → 20초 → ... 최대 5분까지 늘어나며 상태가 `Error` 와 `CrashLoopBackOff` 를 오갑니다.

핵심 학습 포인트는 **로그를 보는 시점**입니다. 지금 살아 있는 컨테이너는 방금 재시작된 것이라 `kubectl logs` 가 비어 보이고, 죽은 원인(`boom, fatal error`)은 `kubectl logs crasher --previous` 에만 남습니다. `describe` 의 `Started` 와 `Finished` 시각이 동일하다는 점도 "시작하자마자 죽었다"는 결정적 단서입니다. 실무에서는 `exit 1` 자리에 누락된 환경변수나 DB 연결 실패가 들어갑니다.

```yaml file="./manifests/02-crashloopbackoff.yaml"
```

### manifests/03a-pending-cpu.yaml

24-3a 의 `Pending`(스케줄 불가) 재현용입니다. `resources.requests.cpu: "1000"` — 단위가 없으므로 **1000 코어**(밀리코어 `1000m` = 1코어와 혼동하기 쉬운 지점입니다)를 요구합니다. 어떤 워커 노드도 이만한 여유가 없어 스케줄러가 `FailedScheduling` 이벤트에 `Insufficient cpu` 를 남기고 파드는 노드에 배치되지 못한 채 `Pending` 에 머뭅니다.

이 파드는 **자원을 실제로 점유하지 않습니다** — 배치 자체가 안 됐기 때문입니다. 이벤트의 `0/3 nodes are available` 중 `1 node(s) had untolerated taint(s)` 는 컨트롤플레인 노드를 가리키는 정상 메시지이고, 나머지 2개 워커의 `Insufficient cpu` 가 진짜 원인입니다. `resources` 는 수정 불가 필드라 `commands.sh` 에서는 파드를 지우고 `requests.cpu: 100m` 로 재생성합니다.

```yaml file="./manifests/03a-pending-cpu.yaml"
```

### manifests/03b-pending-pvc.yaml

24-3b 용으로, PVC 와 Pod 를 `---` 로 이어 붙인 2-문서 매니페스트입니다. 고장 지점은 PVC 의 `storageClassName: does-not-exist` — 존재하지 않는 StorageClass 라 프로비저너가 없고, PVC 는 `ProvisioningFailed`(`storageclass.storage.k8s.io "does-not-exist" not found`) 이벤트를 내며 영원히 `Pending` 입니다.

그 아래 `waits-for-pvc` 파드는 `persistentVolumeClaim.claimName: ghost-pvc` 로 이 PVC 를 `/data` 에 마운트하므로, PVC 가 바인딩될 때까지 스케줄이 보류되어 `pod has unbound immediate PersistentVolumeClaims` 로 함께 `Pending` 이 됩니다. **파드 이벤트만 봐서는 진짜 원인이 안 보이고 PVC 이벤트까지 따라가야 한다**는 게 이 파일의 학습 포인트입니다. 해결은 실제 존재하는 SC(kind 기본값 `standard`)로 PVC 를 다시 만드는 것이며, `standard` 는 `WaitForFirstConsumer` 정책이라 파드가 뜨는 순간 바인딩됩니다.

```yaml file="./manifests/03b-pending-pvc.yaml"
```

### manifests/04-oomkilled.yaml

24-4 의 `OOMKilled` 재현용입니다. `command: ["sh", "-c", "echo 'start allocating...'; tail /dev/zero"]` 가 `/dev/zero` 에서 0바이트를 무한히 읽어 메모리에 쌓고, `limits.memory: "32Mi"` 로 설정한 cgroup 한도를 넘는 순간 커널 OOM killer 가 SIGKILL 을 보내 **exit 137**(=128+9)로 종료시킵니다.

- `limits.memory` 와 `requests.memory` 를 둘 다 `32Mi` 로 같게 준 것은, 노드에 32Mi 를 확실히 예약받은 상태에서 **딱 그 한도에서 OOM 이 나도록** 재현을 안정시키기 위함입니다. (cpu 는 지정하지 않았으므로 이 파드의 QoS 클래스는 `Guaranteed` 가 아니라 `Burstable` 입니다. `Guaranteed` 가 되려면 **모든 컨테이너가 cpu·memory 둘 다** requests == limits 여야 합니다.)
- 파일 주석이 짚듯 한도를 **16Mi 처럼 너무 낮게** 주면 컨테이너 init 단계에서 OOM 되어 137 이 아니라 `StartError`(exit 128, "container init was OOM-killed")가 납니다. "정상 시작 후 런타임 OOM"을 보여주려면 컨테이너가 뜰 만큼(32Mi)은 줘야 합니다 — 이 미묘한 차이가 24-4 의 함정 박스와 연결됩니다.
- 진단은 `kubectl get pod memory-hog -o yaml` 의 `lastState.terminated` 에서 `reason: OOMKilled` + `exitCode: 137` 조합을 확인하는 것입니다. 단순 앱 크래시(exit 1)와 구별하는 유일한 근거입니다.

```yaml file="./manifests/04-oomkilled.yaml"
```

### manifests/05-service-endpoints.yaml

24-5 용 2-문서 매니페스트로, "파드는 Running 인데 Service 접속만 안 되는" 상황을 만듭니다. 고장 지점은 **레이블과 셀렉터의 불일치** 하나입니다: 파드 `echo-server` 의 레이블은 `app: echo` 인데, Service `echo-svc` 의 `selector` 는 `app: web` 을 찾습니다. 매칭되는 파드가 0개라 `kubectl get endpoints echo-svc` 가 `<none>` 이 되고, ClusterIP 는 존재하지만 뒤에 아무도 없어 접속이 거부됩니다.

포트 구성은 정상입니다 — 컨테이너가 `-listen=:8080` 으로 8080 을 열고 Service 가 `port: 80 → targetPort: 8080` 으로 매핑하므로, 셀렉터만 `app: echo` 로 고치면(`kubectl patch svc`) 즉시 Endpoints 가 채워집니다. 이미지 `hashicorp/http-echo:1.0` 은 셸이 없는 최소 이미지라 `kubectl exec` 가 불가능하고, 그래서 24-7 에서 `kubectl debug --target=echo` 로 busybox 임시 컨테이너를 붙이는 예제의 대상으로도 재사용됩니다.

```yaml file="./manifests/05-service-endpoints.yaml"
```

### manifests/06-containercreating.yaml

24-6 의 `ContainerCreating` 지속 상태를 재현합니다. 파드는 볼륨 `cred` 를 `secret.secretName: app-credentials` 로 정의하고 `/etc/cred` 에 `readOnly: true` 로 마운트하는데, **그 Secret 이 클러스터에 없습니다.** 스케줄은 성공했지만(그래서 `Pending` 이 아닙니다) kubelet 이 볼륨을 만들지 못해 컨테이너 생성 직전 단계에서 멈추고, `MountVolume.SetUp failed for volume "cred" : secret "app-credentials" not found` 라는 `FailedMount` 이벤트를 반복합니다.

- 컨테이너가 아직 만들어지지 않았으므로 `kubectl logs` 에는 아무것도 없습니다. **`describe` 의 Events 만이 유일한 단서**라는 게 핵심입니다.
- 해결은 `kubectl create secret generic app-credentials --from-literal=username=admin --from-literal=password=s3cr3t` 로 누락된 Secret 을 만드는 것입니다. 파드를 다시 만들 필요는 없지만, kubelet 의 마운트 재시도가 지수 backoff 라 **최대 2분쯤 기다려야** 할 수 있습니다(그래서 `commands.sh` 는 `kubectl wait --timeout=180s` 를 씁니다).
- 회복 후 `kubectl exec needs-secret -- ls -l /etc/cred` 를 하면 `username`/`password` 가 `..data/` 를 가리키는 심볼릭 링크로 보입니다. Secret 볼륨의 원자적 갱신 구조 때문입니다.

```yaml file="./manifests/06-containercreating.yaml"
```
