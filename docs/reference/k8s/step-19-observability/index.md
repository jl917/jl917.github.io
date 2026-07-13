# Step 19 — 관측성: 클러스터가 무슨 일을 하는지 읽어내기

## 학습 목표
- `kubectl get events`를 시간순으로 정렬해 "무슨 일이 일어났는지"를 추적한다.
- `kubectl logs`의 실전 옵션(`-f`, `--previous`, `--since`, `-c`)을 상황별로 골라 쓴다.
- `kubectl describe`를 **위에서 아래로** 읽는 법(상태 → 컨테이너 → 조건 → 이벤트)을 익힌다.
- `kubectl debug`로 **ephemeral 컨테이너**를 주입해, 셸도 없는 파드를 살아있는 채로 진단한다.
- `-o wide / -o yaml / -o jsonpath`로 원하는 필드만 정확히 뽑는다.
- 로깅(EFK)·메트릭(Prometheus) 아키텍처의 큰 그림을 잡는다.

## 선행 지식
- Step 03(Pod, logs/exec/describe), Step 08(프로브), Step 18(metrics-server/top).

## 소요 시간
- 35분

---

## 0. 관측성의 4가지 신호

문제를 진단할 때 쓰는 도구는 사실상 넷입니다. 이 순서로 봅니다.

| 신호 | 명령 | "언제 무엇이 있었나 / 왜 안 되나" |
|---|---|---|
| **Events** | `kubectl get events`, `describe` | 스케줄링·이미지 풀·프로브 실패 등 **클러스터의 사건** |
| **Logs** | `kubectl logs` | 애플리케이션이 stdout에 뱉은 것 |
| **Metrics** | `kubectl top` | CPU·메모리 사용량 (metrics-server) |
| **State** | `kubectl get/describe -o yaml` | 파드/컨테이너의 현재 필드 값(phase, restartCount…) |

이 스텝은 두 개의 실습 파드를 씁니다:
- `multi` — 멀티 컨테이너(app + sidecar), 로그 실습용
- `flaky` — liveness가 20초 뒤 실패하도록 만든 파드, 이벤트·재시작·`--previous` 실습용

```bash
kubectl apply -f manifests/namespace.yaml
kubectl apply -f manifests/multi-container.yaml
kubectl apply -f manifests/flaky-probe.yaml
```

---

## 1. Events — 시간순으로 사건 추적

이벤트는 기본 정렬이 뒤죽박죽입니다. **항상 `--sort-by`로 정렬**하세요.

```bash
kubectl get events -n step19 --sort-by='.lastTimestamp'
```
**실제 출력** (일부)
```
52s   Normal    Started    pod/flaky   Container started
22s   Warning   Unhealthy  pod/flaky   Liveness probe failed: cat: can't open '/tmp/healthy': No such file or directory
22s   Normal    Killing    pod/flaky   Container web failed liveness probe, will be restarted
```

- `Warning` 타입만 보고 싶으면: `kubectl get events -n step19 --field-selector type=Warning`
- **함정**: 이벤트는 기본 **1시간 뒤 사라집니다**(etcd TTL). "어제 왜 죽었지?"는 이벤트로 알 수 없습니다. 그래서 로그를 외부로 수집합니다(아래 8장).

---

## 2. `kubectl top` — 실시간 사용량

metrics-server(Step 18에서 설치)가 있으면:

```bash
kubectl top pods -n step19 --containers
```
```
POD     NAME      CPU(cores)   MEMORY(bytes)
flaky   web       1m           7Mi
multi   app       1m           0Mi
multi   sidecar   1m           0Mi
```

`--containers`를 붙이면 파드 안 **컨테이너별**로 쪼개 보여줍니다. 어느 사이드카가 메모리를 먹는지 찾을 때 유용합니다.

---

## 3. Logs — 옵션을 상황에 맞게

### 멀티 컨테이너: `-c`로 컨테이너 지정

`-c`를 빼면 "여러 컨테이너 중 하나를 골라라"는 에러가 납니다.

```bash
kubectl logs multi -n step19 -c sidecar --tail=3
```
```
[sidecar] heartbeat at 03:12:26
[sidecar] heartbeat at 03:12:31
[sidecar] heartbeat at 03:12:36
```

### `--since`: 최근 N초/분만

```bash
kubectl logs multi -n step19 -c app --since=6s
```
```
[app] tick 54 at 03:12:32
[app] tick 55 at 03:12:34
[app] tick 56 at 03:12:36
```
`--since=10m`, `--since-time=...`도 됩니다. 로그가 방대할 때 필수.

### `--previous`: **죽기 전** 컨테이너의 로그 (가장 중요)

파드가 재시작(CrashLoop)하면 현재 컨테이너 로그에는 원인이 없습니다. **직전에 죽은 컨테이너**의 마지막 로그를 봐야 합니다.

```bash
kubectl logs flaky -n step19 --previous --tail=5
```
```
10.244.1.1 - - [13/Jul/2026:03:11:37 +0000] "GET / HTTP/1.1" 200 615 "-" "kube-probe/1.36" "-"
10.244.1.1 - - [13/Jul/2026:03:11:42 +0000] "GET / HTTP/1.1" 200 615 "-" "kube-probe/1.36" "-"
```
> `--previous`(`-p`)를 모르면 CrashLoopBackOff 디버깅이 불가능합니다. 반드시 손에 익히세요(Step 24에서 다시 씁니다).

### `-f`: 실시간 팔로우

```bash
kubectl logs multi -n step19 -c app -f     # Ctrl-C 로 종료
```
여러 파드를 한 번에 팔로우: `kubectl logs -n step19 -l app=multi --all-containers -f --prefix`.

---

## 4. `kubectl describe` — 위에서 아래로 읽기

`describe`는 정보가 많습니다. **읽는 순서**가 있습니다.

```bash
kubectl describe pod flaky -n step19
```

1. **상단 요약** — `Status`, `Node`, `IP`, `Labels`
   ```
   Node:    learn-worker/172.22.0.2
   Status:  Running
   IP:      10.244.1.114
   ```
2. **Containers** — 각 컨테이너의 `State`/`Last State`/`Ready`/`Restart Count`/`Image`
3. **Conditions** — `PodScheduled`, `Ready`, `ContainersReady` (True/False)
4. **Events** (맨 아래) — **여기부터 보는 게 실전**입니다:
   ```
   Warning  Unhealthy  9s (x4 over 74s)  kubelet  Liveness probe failed: cat: can't open '/tmp/healthy' ...
   Normal   Killing    9s (x2 over 69s)  kubelet  Container web failed liveness probe, will be restarted
   ```
   `(x4 over 74s)`는 "74초 동안 4번 발생"이라는 뜻입니다.

> 팁: 파드 상태가 이상하면 `describe`의 **Events부터** 보세요. 90%는 거기에 답이 있습니다.

---

## 5. 파드/컨테이너 상태 필드

`kubectl get pod`의 열이 무슨 뜻인지:

```bash
kubectl get pods -n step19 -o wide
```
```
flaky   1/1   Running   2 (13s ago)   2m13s   10.244.1.115   learn-worker
multi   2/2   Running   0             2m13s   10.244.1.114   learn-worker
```

| 열 | 뜻 |
|---|---|
| `READY 2/2` | 준비된 컨테이너 수 / 전체. readiness 통과해야 올라감 |
| `STATUS` | Running / Pending / CrashLoopBackOff / Completed / Error … |
| `RESTARTS 2 (13s ago)` | 재시작 횟수 + 마지막 재시작 시각 |
| `-o wide`의 `NODE`, `IP` | 어느 노드, 어떤 파드 IP |

정확한 필드는 jsonpath로 뽑습니다:
```bash
kubectl get pod flaky -n step19 -o jsonpath='restartCount={.status.containerStatuses[0].restartCount}{"\n"}lastState={.status.containerStatuses[0].lastState.terminated.reason}{"\n"}'
```
```
restartCount=2
lastState=Error
```
`lastState.terminated.reason`이 `OOMKilled`인지 `Error`인지가 진단의 핵심입니다.

---

## 6. `-o` 출력 포맷

```bash
# 넓게 (노드/IP 포함)
kubectl get pods -n step19 -o wide

# 전체 YAML (필드 전부)
kubectl get pod multi -n step19 -o yaml | less

# 원하는 필드만 (스크립트/CI 친화적)
kubectl get pods -n step19 -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\t"}{.spec.nodeName}{"\n"}{end}'
```
```
flaky	Running	learn-worker
multi	Running	learn-worker
```
- `-o name` — 이름만 (`pod/flaky`), 파이프에 유용
- `-o custom-columns=NAME:.metadata.name,NODE:.spec.nodeName` — 표 형태 커스텀

---

## 7. `kubectl debug` — ephemeral 컨테이너로 살아있는 파드 진단

`nginx:alpine` 같은 이미지에는 `ps`, `curl`, `netstat`이 없어 `kubectl exec`로 디버깅하기 어렵습니다. **ephemeral 컨테이너**(k8s 1.25+ GA)는 실행 중인 파드에 **디버깅 도구가 든 컨테이너를 나중에 끼워 넣습니다**. 원본 컨테이너는 재시작하지 않습니다.

```bash
kubectl debug flaky -n step19 --image=busybox:1.36 --target=web -c debugger \
  -- sh -c 'ps aux | head -5; wget -qO- localhost | head -1'
```
**실제 출력**
```
--- from ephemeral container ---
PID   USER     TIME  COMMAND
    1 root      0:00 /bin/sh -c ... nginx -g 'daemon off;'
   16 root      0:00 nginx: master process nginx -g daemon off;
   17 root      0:00 sleep 20
<!DOCTYPE html>
```

`--target=web`이 핵심입니다. 대상 컨테이너와 **프로세스 네임스페이스를 공유**해, busybox에서 nginx의 프로세스(`ps`)까지 보입니다. 주입된 컨테이너는 파드 스펙에 `ephemeralContainers`로 남습니다:
```bash
kubectl get pod flaky -n step19 -o jsonpath='{.spec.ephemeralContainers[*].name}'   # debugger
kubectl logs flaky -n step19 -c debugger    # 나중에 로그로 다시 확인
```

> ephemeral 컨테이너는 **제거할 수 없습니다**(파드가 죽을 때 같이 사라짐). 그래서 프로덕션 디버깅용이지, 상시 사이드카가 아닙니다.
>
> 노드 자체를 디버깅하려면 `kubectl debug node/learn-worker -it --image=busybox:1.36` — 노드 파일시스템을 `/host`에 마운트한 파드를 띄웁니다.

---

## 8. 로깅·메트릭 아키텍처 (개념)

`kubectl logs`와 이벤트는 **휘발성**입니다(파드가 지워지면 로그도 사라지고, 이벤트는 1시간). 실무는 이를 외부로 **수집·보관**합니다.

- **로깅 — EFK/PLG 스택**: 각 노드의 DaemonSet(Fluent Bit/Fluentd/Promtail)이 컨테이너 로그 파일을 읽어 중앙 저장소(Elasticsearch/Loki)로 보내고, Kibana/Grafana로 검색합니다. 파드가 죽어도 로그가 남습니다.
- **메트릭 — Prometheus**: metrics-server는 HPA용 **순간값**만 제공하고 저장하지 않습니다. 시계열을 저장하고 알람(Alertmanager)·그래프(Grafana)를 하려면 Prometheus를 씁니다. 각 컴포넌트가 `/metrics`를 노출하면 Prometheus가 주기적으로 스크레이프합니다.
- **트레이싱 — OpenTelemetry/Jaeger**: 마이크로서비스 간 요청 흐름을 추적.

> 정리: `kubectl top` = 지금 이 순간, Prometheus = 시간에 따른 추이. `kubectl logs` = 지금 살아있는 파드, EFK = 죽은 파드까지 영구 보관.

---

## 팁과 함정

- **함정 1 — `-c` 없이 멀티 컨테이너 logs**: `Error: a container name must be specified`. `-c <이름>` 또는 `--all-containers`.
- **함정 2 — `--previous`를 잊음**: CrashLoop 파드의 현재 로그는 "지금 막 시작한" 로그라 원인이 없습니다. 죽기 직전 로그는 `-p`에 있습니다.
- **함정 3 — 이벤트는 1시간 뒤 증발**: 재현되지 않는 과거 장애는 이벤트로 못 봅니다. 로그 수집 시스템이 필요한 이유.
- **팁 — describe는 Events부터**: 상태가 이상하면 맨 아래 Events를 먼저 보세요.
- **팁 — `kubectl get events --watch`**: 실시간으로 이벤트가 흐르는 걸 보며 배포를 관찰할 수 있습니다.
- **팁 — jsonpath로 정확히**: "어떤 파드가 어느 노드에?"는 `-o wide`, 스크립트에 넣을 값은 `-o jsonpath`. grep으로 YAML을 긁지 마세요.

---

## 정리표

| 명령 | 용도 |
|---|---|
| `kubectl get events --sort-by='.lastTimestamp'` | 사건을 시간순으로 |
| `kubectl get events --field-selector type=Warning` | 경고만 |
| `kubectl top pods --containers` | 컨테이너별 사용량 |
| `kubectl logs <pod> -c <ctr>` | 멀티 컨테이너에서 하나 지정 |
| `kubectl logs <pod> --previous` | 재시작 전 컨테이너 로그 |
| `kubectl logs <pod> --since=10m -f` | 최근 10분 + 실시간 |
| `kubectl describe pod <pod>` | 상태+컨테이너+조건+이벤트 (아래부터 읽기) |
| `kubectl debug <pod> --image=busybox:1.36 --target=<ctr>` | ephemeral 디버그 컨테이너 |
| `kubectl get pod <pod> -o jsonpath='...'` | 특정 필드만 추출 |

---

## 연습 과제

[challenge.md](challenge.md)의 4개 과제.

---

## 다음 단계

→ [Step 20 — 보안](../step-20-security/README.md): SecurityContext와 Pod Security Standards로 파드를 최소 권한으로 가둡니다.

---

## 실습 파일

이 스텝의 파일은 네 개입니다. 먼저 `manifests/namespace.yaml`로 `step19` 네임스페이스를 만들고, 그 안에 로그 실습용 `manifests/multi-container.yaml`(멀티 컨테이너)과 이벤트·재시작 실습용 `manifests/flaky-probe.yaml`(일부러 liveness를 실패시키는 파드)을 띄웁니다. `commands.sh`는 이 배포부터 events → top → logs → describe → jsonpath → `kubectl debug`까지 본문 1~7장의 명령을 순서대로 한 번에 재현하고 마지막에 네임스페이스를 지우는 스크립트입니다.

### manifests/namespace.yaml

- 이 스텝의 모든 리소스를 담을 `step19` 네임스페이스를 정의합니다. 본문 0장의 `kubectl apply -f manifests/namespace.yaml`이 이 파일이며, **반드시 가장 먼저 적용**해야 합니다. 다른 두 매니페스트가 `namespace: step19`를 하드코딩하고 있어서, 네임스페이스가 없으면 apply가 실패합니다.
- `labels`의 `course: k8s-learn`, `step: "19"`는 교재 전체에서 쓰는 공통 표식입니다. `19`를 따옴표로 감싼 이유는 레이블 값이 반드시 **문자열**이어야 하기 때문입니다. 따옴표를 빼면 YAML 파서가 숫자로 읽어 `cannot unmarshal number into Go value of type string` 에러가 납니다.
- 실습이 끝나면 `kubectl delete namespace step19` 한 줄로 파드·이벤트가 모두 정리됩니다. 네임스페이스 삭제는 **되돌릴 수 없는 파괴적 명령**이니 다른 이름을 지우지 않도록 주의하세요.

```yaml file="./manifests/namespace.yaml"
```

### manifests/multi-container.yaml

- 본문 3장 "멀티 컨테이너: `-c`로 컨테이너 지정"에서 쓰는 파드입니다. `containers` 목록에 `app`과 `sidecar` 두 개가 있어 `kubectl get pods`에서 `READY 2/2`로 보이고, `kubectl logs multi -n step19`를 `-c` 없이 실행하면 `Error: a container name must be specified` 에러(함정 1)가 재현됩니다.
- `app` 컨테이너는 `while true` 루프에서 `n=$((n+1))` 로 카운터를 올리며 `[app] tick $n at $(date +%T)`를 **2초마다** 찍습니다. 출력이 촘촘하기 때문에 `--since=6s`를 붙이면 딱 3줄 정도만 나오고, 본문의 `tick 54 / 55 / 56` 출력이 이렇게 만들어집니다.
- `sidecar` 컨테이너는 `[sidecar] heartbeat at ...`를 **5초마다** 찍습니다. 주기가 달라서 `--all-containers --prefix`로 동시에 팔로우할 때(과제 4) 두 스트림이 섞이는 모습을 눈으로 확인할 수 있습니다.
- 두 컨테이너 모두 `busybox:1.36`이라 CPU·메모리를 거의 쓰지 않습니다. 본문 2장 `kubectl top pods --containers` 출력에서 `app`/`sidecar`가 `1m`, `0Mi`로 찍히는 이유입니다.
- 레이블 `app: multi`는 `kubectl logs -n step19 -l app=multi --all-containers -f --prefix`처럼 **레이블 셀렉터로 여러 파드 로그를 한 번에 팔로우**할 때 쓰입니다.

```yaml file="./manifests/multi-container.yaml"
```

### manifests/flaky-probe.yaml

이 파일은 **일부러 실패하도록 설계된 실습 파드**입니다. 여기서 관찰하는 재시작·Warning 이벤트가 이 스텝의 학습 포인트입니다.

- 고장 장치는 `args`의 이 한 줄입니다: `( sleep 20; rm -f /tmp/healthy; echo "removed health file -> liveness will fail" ) &`. 시작하자마자 `touch /tmp/healthy`로 헬스 파일을 만들지만, 백그라운드 서브셸이 **20초 뒤 그 파일을 지웁니다.** 그 사이 메인 프로세스는 `nginx -g 'daemon off;'`로 정상 서비스 중입니다.
- `livenessProbe`는 `exec: command: ["cat", "/tmp/healthy"]` 입니다. 파일이 사라지면 `cat`이 0이 아닌 종료코드를 내고, 본문 1장에서 본 `Liveness probe failed: cat: can't open '/tmp/healthy': No such file or directory` **Warning 이벤트**가 발생합니다.
- 타이밍을 계산하면 이렇습니다. `initialDelaySeconds: 5` 뒤부터 `periodSeconds: 5` 간격으로 검사하고 `failureThreshold: 2`이므로, 파일이 지워진 뒤 **두 번 연속 실패(약 10초)** 하면 kubelet이 `Killing — Container web failed liveness probe, will be restarted` 이벤트를 남기고 컨테이너를 재시작합니다. 재시작하면 다시 20초 건강 → 실패 → 재시작이 **무한 반복**되므로 `RESTARTS` 카운터가 계속 올라갑니다.
- 이 반복 덕분에 "직전에 죽은 컨테이너"가 항상 존재하므로, 본문 3장의 `kubectl logs flaky -n step19 --previous`가 의미 있게 동작합니다. `--previous` 출력에 `"GET / HTTP/1.1" 200 ... kube-probe/1.36`이 보이는 건 아래 `readinessProbe`가 남긴 접근 로그입니다.
- `readinessProbe`는 `httpGet: path: /, port: 80`으로 **정상 통과**합니다(nginx는 계속 살아 있으니까). liveness만 실패하도록 만들어, "Ready이지만 재시작되는" 상태를 관찰하게 하는 의도적 조합입니다.
- 이미지가 `nginx:1.27-alpine`이라 `ps`·`netstat`·`curl`이 없습니다. 그래서 본문 7장과 과제 3에서 `kubectl debug ... --target=web`으로 busybox ephemeral 컨테이너를 끼워 넣어 진단합니다.

```yaml file="./manifests/flaky-probe.yaml"
```

### commands.sh

- 본문 0~7장의 명령을 처음부터 끝까지 재현하는 스크립트입니다. `set -euo pipefail`로 한 줄이라도 실패하면 즉시 멈추고, `HERE="$(cd "$(dirname "$0")" && pwd)"`로 스크립트 위치를 절대경로로 잡아 어느 디렉터리에서 실행해도 `$HERE/manifests/...`를 찾아냅니다.
- 세 매니페스트를 apply한 뒤 `sleep 90`으로 기다리는 부분이 핵심입니다. `flaky`는 20초 건강 + 약 10초 프로브 실패 판정을 거쳐야 첫 재시작이 일어나므로, **충분히 기다려야** `--previous` 로그와 `restartCount`가 존재합니다. 너무 일찍 `kubectl logs --previous`를 치면 `previous terminated container "web" not found` 에러가 납니다.
- `kubectl top pods` 줄은 **Step 18에서 설치한 metrics-server가 클러스터에 있어야** 동작합니다. 없으면 `error: Metrics API not available`로 스크립트가 `set -e` 때문에 중단됩니다.
- `kubectl describe pod flaky -n step19 | sed -n '/Events:/,$p'`는 describe 출력에서 **Events 섹션부터 끝까지만** 잘라 보여줍니다. 본문 4장의 "describe는 Events부터 읽어라"를 그대로 명령으로 옮긴 것입니다.
- `-f`(팔로우) 라인은 `# kubectl logs multi -n step19 -c app -f`로 **주석 처리**되어 있습니다. 실시간 팔로우는 Ctrl-C 전까지 끝나지 않아 스크립트가 멈춰버리기 때문입니다.
- 맨 마지막 줄 `kubectl delete namespace step19`는 실습 리소스를 모두 지웁니다. 스크립트를 그냥 실행하면 **관찰할 파드까지 사라지므로**, 명령을 하나씩 손으로 따라 하며 배우고 싶다면 이 줄 직전까지만 실행하세요. 주석대로 metrics-server는 다른 네임스페이스에 있어 보존됩니다.
- macOS 전제: 셔뱅(`#!/usr/bin/env bash`) 바로 아래에 있는 `export PATH="/opt/homebrew/bin:$PATH"`는 Homebrew로 설치한 `kubectl`을 찾기 위한 것입니다. 다른 환경이라면 이 줄은 무시해도 됩니다.

```bash file="./commands.sh"
```
