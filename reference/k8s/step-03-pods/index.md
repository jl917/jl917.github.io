# Step 03 — 파드(Pod)

> **학습 목표**
> - 파드가 무엇이고, 왜 "컨테이너"가 아니라 "파드"가 최소 단위인지 안다
> - `logs`, `exec`, `describe` — 파드를 들여다보는 3대 도구를 손에 익힌다
> - 멀티 컨테이너 파드(사이드카)와 initContainer를 이해한다
> - `CrashLoopBackOff`를 직접 만들고 진단한다
>
> **선행 스텝**: [Step 02](../step-02-architecture/index.md)
> **예상 소요**: 55분

실습은 네임스페이스 `step03`에서 합니다.

---

## 3-1. 파드란 무엇인가

**파드는 Kubernetes가 배포하는 가장 작은 단위**입니다. 그런데 파드는 컨테이너가 아니라 **"컨테이너 1개 이상을 담는 그릇"** 입니다.

왜 컨테이너를 직접 안 다루고 한 겹 감쌌을까요?

- 한 파드 안의 컨테이너들은 **같은 노드**에서, **같은 네트워크(IP)**, **같은 볼륨**을 공유합니다.
- 서로 `localhost`로 통신합니다.
- 함께 뜨고 함께 죽습니다.

대부분의 파드는 **컨테이너 1개**입니다. 하지만 "메인 앱 + 보조 프로세스(로그 수집기, 프록시 등)"를 묶을 때 여러 개를 넣습니다(사이드카 패턴, 3-5에서).

> 💡 **핵심**: 파드는 **일회용(ephemeral)** 입니다. 죽으면 되살아나는 게 아니라 **새 파드로 교체**됩니다. IP도 바뀝니다. 그래서 "이 파드에 접속해줘"가 아니라 Service(Step 06)로 접근합니다. **파드를 애완동물이 아니라 가축처럼 다루세요.**

---

## 3-2. 파드 하나 띄우고 관찰하기

```yaml
# manifests/pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: web
  namespace: step03
  labels:
    app: web
spec:
  containers:
    - name: nginx
      image: nginx:1.27-alpine
      ports:
        - containerPort: 80
```

```bash
kubectl create namespace step03
kubectl apply -f manifests/pod.yaml
kubectl -n step03 get pod web -o wide
```

**결과**
```
NAME   READY   STATUS    RESTARTS   AGE   IP            NODE           NOMINATED NODE
web    1/1     Running   0          10s   10.244.1.5    learn-worker   <none>
```

- **READY 1/1** — 파드 안의 컨테이너 1개 중 1개가 준비됨.
- **IP 10.244.1.5** — 파드마다 클러스터 내부 IP가 하나 부여됩니다. (이 IP는 파드가 재생성되면 바뀝니다)

---

## 3-3. 파드를 들여다보는 3대 도구

### logs — 컨테이너 표준 출력 보기

```bash
kubectl -n step03 logs web
```

옵션이 중요합니다:

| 옵션 | 용도 |
|---|---|
| `-f` | 실시간 스트리밍 (tail -f) |
| `--previous` (`-p`) | **죽기 직전** 컨테이너의 로그. CrashLoop 진단 필수 |
| `--since=10m` | 최근 10분 |
| `-c <컨테이너>` | 멀티 컨테이너 파드에서 특정 컨테이너 지정 |
| `--tail=50` | 마지막 50줄 |

> ⚠️ **함정**: 파드가 계속 재시작(CrashLoop)하면 현재 컨테이너 로그는 방금 시작한 것이라 비어 있습니다. **`--previous`로 "죽기 직전" 로그를 봐야** 왜 죽었는지 알 수 있습니다. 이걸 모르면 CrashLoop을 영원히 못 고칩니다.

### exec — 컨테이너 안으로 들어가기

```bash
# 파일 하나 확인
kubectl -n step03 exec web -- cat /etc/nginx/nginx.conf

# 셸로 진입해서 이것저것 (-it = 인터랙티브)
kubectl -n step03 exec -it web -- sh
# / # ls, curl localhost 등 실행 후 exit
```

> ⚠️ **함정**: alpine/distroless 이미지엔 `bash`가 없을 수 있습니다. `sh`를 쓰세요. 아예 셸이 없는 이미지(distroless)라면 `exec`가 불가능하고, Step 19의 `kubectl debug`(임시 디버그 컨테이너)를 써야 합니다.

### describe — 파드의 모든 것 + 이벤트

```bash
kubectl -n step03 describe pod web
```

`describe`의 진짜 가치는 맨 아래 **Events** 섹션입니다. 스케줄부터 시작까지 무슨 일이 있었는지 시간순으로 보여줍니다. **"파드가 이상해요" → 가장 먼저 칠 명령이 describe입니다.**

---

## 3-4. 파드 상태(Status) 읽기

`kubectl get pod`의 STATUS는 파드의 현재 상태입니다.

| STATUS | 의미 | 조치 |
|---|---|---|
| `Running` | 정상 동작 중 | — |
| `Pending` | 아직 노드에 배치 안 됨 (리소스 부족 등) | `describe`로 이유 확인 (Step 09, 13) |
| `ContainerCreating` | 노드 배치됨, 컨테이너 준비 중 | 볼륨/시크릿 대기일 수 있음 (Step 24) |
| `ImagePullBackOff` | 이미지를 못 받음 | 이미지명/태그 확인 (Step 24) |
| `CrashLoopBackOff` | 떴다가 계속 죽음 | `logs --previous` (아래) |
| `Completed` | 정상 종료 (Job 등) | — |
| `Error` / `OOMKilled` | 비정상 종료 | 로그, 리소스 확인 (Step 09) |

---

## 3-5. 멀티 컨테이너 파드 (사이드카 + initContainer)

한 파드에 여러 컨테이너를 넣어봅니다. 이들은 볼륨과 네트워크를 공유합니다.

```yaml
# manifests/multi-container.yaml (핵심 부분)
spec:
  volumes:
    - name: shared
      emptyDir: {}
  initContainers:                 # ① 앱보다 먼저 실행, 끝나야 다음 단계로
    - name: init-content
      image: busybox:1.36
      command: ['sh','-c','echo "<h1>Hello from init</h1>" > /work/index.html']
      volumeMounts:
        - { name: shared, mountPath: /work }
  containers:
    - name: web                   # ② nginx: 공유 볼륨을 웹 루트로 사용
      image: nginx:1.27-alpine
      volumeMounts:
        - { name: shared, mountPath: /usr/share/nginx/html }
    - name: sidecar               # ③ 5초마다 로그 찍는 보조 컨테이너
      image: busybox:1.36
      command: ['sh','-c','while true; do echo "tick $(date +%T)"; sleep 5; done']
```

```bash
kubectl apply -f manifests/multi-container.yaml
kubectl -n step03 get pod multi
```

**결과**
```
NAME    READY   STATUS    RESTARTS   AGE
multi   2/2     Running   0          12s
```

**READY 2/2** — 컨테이너 2개 모두 준비됨. (initContainer는 끝났으므로 카운트에 없음)

멀티 컨테이너에서는 로그를 볼 때 컨테이너를 지정해야 합니다:

```bash
kubectl -n step03 logs multi -c sidecar
```

**결과**
```
tick 02:58:06
tick 02:58:11
tick 02:58:16
```

initContainer가 만든 파일을 nginx 컨테이너가 보고 있는지 확인:

```bash
kubectl -n step03 exec multi -c web -- cat /usr/share/nginx/html/index.html
```

**결과**
```
<h1>Hello from init</h1>
```

**`init-content`와 `web` 두 컨테이너가 하나의 볼륨을 공유**한 것이 증명됐습니다. init이 쓴 파일을 web이 서빙합니다. (`sidecar`는 `shared` 볼륨을 마운트하지 않습니다. 같은 파드에 있지만 자기 로그만 찍는 독립 프로세스입니다.)

> 💡 **실무 팁**: 사이드카 패턴의 대표 사례 — 로그 수집기(앱 로그를 읽어 중앙으로 전송), 서비스 메시 프록시(Istio의 Envoy), 설정 동기화. initContainer는 "앱 시작 전 준비 작업"(DB 마이그레이션, 설정 다운로드, 의존 서비스 대기)에 씁니다. Kubernetes 1.28+에서는 사이드카를 위한 정식 `restartPolicy: Always` initContainer 문법도 생겼습니다.

> ⚠️ **함정**: initContainer가 실패하면(exit≠0) 파드는 `Init:Error`/`Init:CrashLoopBackOff`에 갇혀 **앱 컨테이너가 아예 시작되지 않습니다.** initContainer는 반드시 성공해야 다음으로 넘어갑니다.

---

## 3-6. CrashLoopBackOff 직접 만들고 진단하기

가장 자주 만나는 문제입니다. 일부러 만들어 봅니다.

```yaml
# manifests/crasher.yaml
spec:
  containers:
    - name: boom
      image: busybox:1.36
      command: ['sh','-c','echo starting; sleep 2; exit 1']   # 2초 후 죽음
```

```bash
kubectl apply -f manifests/crasher.yaml
# 30초쯤 기다린 뒤
kubectl -n step03 get pod crasher
```

**결과**
```
NAME      READY   STATUS   RESTARTS      AGE
crasher   0/1     Error    2 (25s ago)   30s
```

잠시 더 지나면 `STATUS`가 `CrashLoopBackOff`로 바뀝니다. `RESTARTS`가 계속 늘어납니다.

**진단 절차:**

```bash
kubectl -n step03 describe pod crasher
```

**결과** (Events)
```
  Type     Reason     Age                From      Message
  ----     ------     ----               ----      -------
  Normal   Scheduled  31s                default-scheduler  Successfully assigned step03/crasher to learn-worker
  Normal   Pulled     13s (x3 over 30s)  kubelet   Container image "busybox:1.36" already present on machine
  Normal   Started    13s (x3 over 30s)  kubelet   Started container boom
  Warning  BackOff    10s (x2 over 25s)  kubelet   Back-off restarting failed container boom
```

`x3`, `x2`가 보이나요? 같은 일이 반복되고 있습니다. 이제 **죽기 직전 로그**를 봅니다:

```bash
kubectl -n step03 logs crasher --previous
```

**결과**
```
starting
```

로그에서 앱이 `starting`을 찍고 곧 `exit 1`로 죽었음을 알 수 있습니다. 실제 앱이라면 여기서 스택 트레이스나 "설정 파일 없음" 같은 진짜 원인이 보입니다.

> ⚠️ **함정**: 왜 k8s는 계속 재시작할까요? 파드의 기본 `restartPolicy`가 `Always`이기 때문입니다. 그리고 재시작이 반복되면 간격을 10s→20s→40s...로 늘립니다(back-off). 이 "BackOff"가 상태 이름에 붙는 것입니다. Job(Step 12)처럼 한 번 실행하고 끝나야 하는 워크로드는 `restartPolicy`를 `Never`나 `OnFailure`로 바꿉니다.

CrashLoopBackOff의 흔한 원인은 [Step 24 트러블슈팅](../step-24-troubleshooting/index.md)에서 총정리합니다.

---

## 3-7. 정리

```bash
kubectl delete namespace step03
```

---

## 정리

| 개념 | 요점 |
|---|---|
| 파드 | 배포 최소 단위. 컨테이너 1+개를 담는 그릇. 일회용 |
| 컨테이너 공유 | 같은 파드 = 같은 IP/볼륨/노드, `localhost` 통신 |
| `logs` | `-f` 스트림, `--previous` 죽기 전 로그, `-c` 컨테이너 지정 |
| `exec` | `-it ... -- sh` 로 진입. alpine엔 bash 없음 |
| `describe` | Events 섹션이 핵심. 문제 진단의 시작점 |
| initContainer | 앱 전에 실행, 성공해야 앱이 뜸 |
| 사이드카 | 메인 + 보조 컨테이너 (로그/프록시 등) |
| CrashLoopBackOff | 계속 죽음. `logs --previous`로 원인 추적 |

---

## 연습 과제

[challenge.md](challenge.md)

---

## 다음 단계

파드를 다뤘으니, 이제 파드를 **조직하고 선택하는** 방법을 배웁니다.

→ [Step 04 — 레이블·셀렉터·네임스페이스](../step-04-labels-namespaces/index.md)

---

## 실습 파일

이 스텝의 실습은 매니페스트 3개(`manifests/pod.yaml` → `manifests/multi-container.yaml` → `manifests/crasher.yaml`)를 순서대로 적용하며 진행합니다. 단순한 단일 컨테이너 파드로 시작해 `logs`/`exec`/`describe`를 익히고, 그다음 멀티 컨테이너 + initContainer + 공유 볼륨을 확인한 뒤, 마지막으로 일부러 죽는 파드로 `CrashLoopBackOff`를 재현합니다. `commands.sh`는 이 흐름 전체(네임스페이스 생성부터 삭제까지)를 한 번에 재현하는 스크립트입니다.

### commands.sh

- 3-2부터 3-7까지 강의 본문에 나오는 명령을 **그대로 순서대로** 실행하는 재현 스크립트입니다. 먼저 `kubectl create namespace step03`으로 실습 네임스페이스를 만들고, 이후 모든 명령이 `-n step03`을 달고 동작합니다.
- `HERE="$(cd "$(dirname "$0")" && pwd)"` 로 스크립트 자신의 절대 경로를 잡고 `kubectl apply -f "$HERE/manifests/pod.yaml"` 처럼 참조하므로, **어느 디렉터리에서 실행해도** 매니페스트 경로가 깨지지 않습니다.
- 곳곳의 `sleep`은 의도된 대기입니다. `sleep 6`은 nginx 파드가 `Running`이 되기를, `sleep 12`는 initContainer가 끝나고 컨테이너 2개가 `2/2`가 되기를, `sleep 30`은 crasher가 `Error`를 지나 `CrashLoopBackOff`로 넘어가기를 기다립니다. 이 대기를 빼면 아직 `ContainerCreating`인 상태만 보게 됩니다.
- 3-3의 3대 도구가 그대로 들어 있습니다. `logs web`, `exec web -- cat /etc/nginx/nginx.conf`, `describe pod web | tail -20`(맨 아래 **Events**만 잘라 보기 위한 `tail`)이 각각 대응합니다.
- 마지막 줄 `kubectl delete namespace step03`은 **파괴적 명령**입니다. 네임스페이스 안의 파드 3개가 모두 지워지므로, 관찰을 더 하고 싶다면 이 줄 전에 스크립트를 멈추거나 주석 처리하세요.

```bash file="./commands.sh"
```

### manifests/pod.yaml

- 3-2에서 가장 먼저 적용하는, 가능한 한 최소한으로 줄인 파드 정의입니다. `kind: Pod` + `spec.containers` 한 개가 파드의 최소 골격입니다.
- `metadata.namespace: step03`이 파일 안에 박혀 있으므로 `kubectl apply -f manifests/pod.yaml`을 `-n` 없이 실행해도 항상 `step03`에 만들어집니다. 다만 그 네임스페이스가 **미리 존재해야** 하므로 `kubectl create namespace step03`이 선행되어야 합니다.
- `labels: {app: web}`는 지금은 쓰이지 않지만 Step 04(레이블·셀렉터)와 Step 06(Service)에서 셀렉터의 대상이 되는 값입니다. 지금부터 붙여두는 습관을 들입니다.
- `image: nginx:1.27-alpine` — alpine 계열이라 컨테이너 안에 `bash`가 없습니다. 3-3의 함정에서 설명한 대로 `exec` 시 `-- sh`를 써야 하고, 연습 과제에서도 `curl` 대신 `wget`을 씁니다.
- `ports.containerPort: 80`은 **문서용 선언**에 가깝습니다. 이 필드를 지운다고 80 포트가 막히지는 않습니다. 실제 외부 노출은 Service(Step 06)가 담당합니다.

```yaml file="./manifests/pod.yaml"
```

### manifests/multi-container.yaml

- 3-5의 사이드카 + initContainer 실습용 파드(`name: multi`)입니다. 컨테이너 3개(initContainer 1개 + 앱 컨테이너 2개)가 한 파드에 들어갑니다. 다만 `emptyDir` 볼륨 `shared`를 **실제로 마운트하는 것은 `init-content`와 `web` 둘뿐**입니다. `sidecar`에는 `volumeMounts`가 아예 없습니다 — 볼륨을 공유하려면 "같은 파드에 있는 것"만으로는 부족하고 **각 컨테이너가 직접 마운트를 선언해야** 한다는 점을 이 파일이 보여줍니다.
- `volumes.shared.emptyDir: {}` 는 **파드 수명 동안만** 존재하는 임시 볼륨입니다. 파드가 지워지면 내용도 함께 사라집니다(영속 볼륨은 Step 10).
- `initContainers.init-content`는 `echo "<h1>Hello from init</h1>" > /work/index.html` 을 실행하고 **종료**합니다. 같은 볼륨을 `web` 컨테이너는 `/usr/share/nginx/html`에 마운트하므로, **마운트 경로만 다를 뿐 같은 디렉터리**입니다. 그래서 init이 쓴 파일을 nginx가 그대로 서빙합니다 — 이것이 볼륨 공유의 증명입니다.
- `sidecar` 컨테이너는 `while true; do echo "tick $(date +%T)"; sleep 5; done`으로 **끝나지 않고 계속 도는** 보조 프로세스입니다. 이 때문에 `READY`가 `2/2`가 되고(끝난 initContainer는 카운트에 없음), 로그를 볼 때는 `kubectl -n step03 logs multi -c sidecar` 처럼 `-c`로 컨테이너를 지정해야 합니다.
- 주의: initContainer는 **성공(exit 0)해야만** 앱 컨테이너가 시작됩니다. 연습 과제 3처럼 command를 `['sh','-c','exit 1']`로 바꾸면 파드가 `Init:CrashLoopBackOff`에 갇히고 `READY 0/2`가 됩니다.

```yaml file="./manifests/multi-container.yaml"
```

### manifests/crasher.yaml

- 3-6에서 쓰는, **일부러 실패하도록 만든** 파드입니다. 이 파일의 목적은 정상 동작이 아니라 `CrashLoopBackOff`를 눈으로 보게 하는 것입니다.
- 핵심은 단 한 줄, `command: ['sh', '-c', 'echo starting; sleep 2; exit 1']` 입니다. 컨테이너가 `starting`을 찍고 2초 뒤 **0이 아닌 종료 코드**(`exit 1`)로 죽습니다. 실제 앱이라면 "설정 파일 없음", "DB 접속 실패" 같은 이유로 같은 일이 벌어집니다.
- 파드의 기본 `restartPolicy`가 `Always`이므로 kubelet이 계속 재시작하고, 반복될수록 재시작 간격을 10s → 20s → 40s… 로 늘립니다(back-off). 그래서 `STATUS`가 처음엔 `Error`였다가 곧 `CrashLoopBackOff`로 바뀌고 `RESTARTS`가 계속 증가합니다.
- 진단 포인트: 방금 재시작한 컨테이너의 `kubectl logs crasher`는 비어 있거나 거의 없습니다. 반드시 `kubectl -n step03 logs crasher --previous`로 **죽기 직전** 컨테이너의 로그(`starting`)를 봐야 원인 단서가 나옵니다.
- 이 파드는 영원히 정상이 되지 않으므로 관찰이 끝나면 `kubectl delete namespace step03`(또는 `kubectl -n step03 delete pod crasher`)으로 정리하세요. 방치하면 계속 재시작을 시도합니다.

```yaml file="./manifests/crasher.yaml"
```
