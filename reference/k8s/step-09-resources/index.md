# Step 09 — 리소스 관리

> **학습 목표**: requests/limits의 의미와 스케줄러 동작, CPU(압축 가능) vs 메모리(압축 불가·OOMKill) 차이, QoS 3등급, LimitRange·ResourceQuota로 네임스페이스를 통제하는 법을 익힌다.
> **선행 스텝**: [Step 08](../step-08-health-probes/README.md)
> **예상 소요**: 60분

이 스텝의 모든 리소스는 `step09` 네임스페이스에 만든다. 모든 매니페스트에 `namespace: step09`가 박혀 있으니 실수로 다른 네임스페이스를 건드릴 일은 없다.

```bash
kubectl apply -f manifests/00-namespace.yaml
```

---

## 9-1. requests vs limits — 스케줄러가 보는 값 vs 커널이 강제하는 값

컨테이너의 `resources`는 두 가지 값으로 나뉜다.

| 필드 | 의미 | 누가 사용하나 |
|------|------|---------------|
| `requests` | "최소 이만큼은 보장받아야 한다"는 **예약량** | **스케줄러**가 노드 배치를 결정할 때 |
| `limits` | "이 이상은 못 쓴다"는 **상한** | **노드의 kubelet/커널(cgroup)**이 런타임에 강제할 때 |

핵심 오해 하나: **스케줄러는 `requests`만 본다.** 노드에 실제로 남은 여유 메모리가 아니라, 이미 배치된 파드들의 `requests` 합계를 노드 용량에서 빼서 "이 파드의 requests가 들어갈 자리가 있나"를 판단한다. `limits`는 배치 결정에 쓰이지 않는다(오버커밋 허용).

```yaml
# manifests/01-qos-guaranteed.yaml (일부)
resources:
  requests:
    cpu: "100m"      # 0.1 코어 예약
    memory: "64Mi"
  limits:
    cpu: "100m"      # 0.1 코어 상한
    memory: "64Mi"
```

QoS 파드 3개를 띄운 뒤 노드의 할당 현황을 보자.

```bash
kubectl apply -f manifests/01-qos-guaranteed.yaml
kubectl apply -f manifests/02-qos-burstable.yaml
kubectl apply -f manifests/03-qos-besteffort.yaml
kubectl describe node learn-worker2 | sed -n '/Allocated resources/,/Events/p'
```

**실행 결과**

```
Allocated resources:
  (Total limits may be over 100 percent, i.e., overcommitted.)
  Resource           Requests    Limits
  --------           --------    ------
  cpu                200m (2%)   200m (2%)
  memory             114Mi (0%)  114Mi (0%)
  ephemeral-storage  0 (0%)      0 (0%)
  hugepages-1Gi      0 (0%)      0 (0%)
  hugepages-2Mi      0 (0%)      0 (0%)
  hugepages-32Mi     0 (0%)      0 (0%)
  hugepages-64Ki     0 (0%)      0 (0%)
```

`Requests`/`Limits` 합계는 이 노드에 배치된 파드들의 **선언값 합**이지 실제 사용량이 아니다. 실제 사용량은 metrics-server가 있어야 `kubectl top`으로 볼 수 있다.

```bash
kubectl top nodes
```

**실행 결과**

```
NAME                  CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
learn-control-plane   194m         2%       946Mi           5%
learn-worker          334m         4%       813Mi           5%
learn-worker2         155m         1%       587Mi           3%
```

> 💡 **실무 팁**: `kubectl top`이 `error: Metrics API not available`로 실패한다면 metrics-server가 아직 없는 것이다. 설치는 [Step 18](../step-18-autoscaling/README.md)에서 다룬다. `requests`/`limits`는 metrics-server가 없어도 항상 동작한다 — 스케줄링과 cgroup 강제는 선언값만으로 이뤄지기 때문이다.

> ⚠️ **함정**: `requests`를 실제 사용량보다 훨씬 크게 잡으면 노드에 물리적 여유가 있어도 "Insufficient cpu/memory"로 Pending이 뜬다. 반대로 너무 작게 잡으면 노드가 과밀 배치되어 실제 부하가 몰릴 때 서로 자원을 뺏는다. requests는 **평상시 사용량**, limits는 **피크 허용치**로 잡는 게 출발점이다.

---

## 9-2. CPU는 압축 가능, 메모리는 압축 불가

이 둘의 차이가 리소스 관리의 핵심이다.

- **CPU (compressible)**: limit을 넘으면 커널이 CFS 스로틀링으로 컨테이너를 **느리게** 만든다. 프로세스는 죽지 않고 그냥 굶는다. 지연이 늘어날 뿐 살아있다.
- **메모리 (incompressible)**: 이미 할당한 메모리는 "조금만 돌려줘"가 불가능하다. limit을 넘으면 커널의 cgroup OOM killer가 컨테이너 프로세스를 **강제 종료(SIGKILL)**한다. 이게 바로 `OOMKilled`, 종료코드 **137**(= 128 + SIGKILL(9))이다.

| 구분 | CPU | 메모리 |
|------|-----|--------|
| limit 초과 시 | 스로틀링(느려짐) | OOMKill(강제 종료) |
| 프로세스 생존 | 살아있음 | 죽음 |
| 종료코드 | — | 137 |
| 복구 | 자동(부하 감소 시) | 재시작 필요 |

> 💡 **실무 팁**: 그래서 메모리 limit은 CPU limit보다 훨씬 신중하게 잡아야 한다. CPU limit을 낮게 잡으면 "느린 서비스"지만, 메모리 limit을 낮게 잡으면 "죽는 서비스"다. 많은 팀이 CPU limit은 아예 걸지 않고(스로틀링 회피) 메모리 limit만 requests와 같게 거는 전략을 쓴다.

---

## 9-3. QoS 클래스 — 자원 압박 시 누가 먼저 죽나

파드의 `resources` 선언 방식에 따라 쿠버네티스가 자동으로 **QoS 클래스**를 매긴다. 노드 메모리가 부족해지면(node pressure eviction) 이 등급 순서로 파드를 쫓아낸다: **BestEffort → Burstable → Guaranteed**.

| QoS | 조건 | 축출 우선순위 |
|-----|------|---------------|
| **Guaranteed** | 모든 컨테이너가 cpu·memory 모두 `requests == limits` | 가장 늦게(안전) |
| **Burstable** | Guaranteed는 아니지만 requests나 limit이 하나라도 있음 | 중간 |
| **BestEffort** | 어떤 컨테이너에도 requests/limits가 전혀 없음 | 가장 먼저(위험) |

9-1에서 띄운 세 파드가 각각 어떤 등급인지 확인한다.

```yaml
# manifests/03-qos-besteffort.yaml
apiVersion: v1
kind: Pod
metadata:
  name: qos-besteffort
  namespace: step09
spec:
  containers:
    - name: app
      image: nginx:1.27-alpine
      # resources 자체가 없음 → BestEffort
```

```bash
for p in qos-guaranteed qos-burstable qos-besteffort; do
  echo -n "$p -> "; kubectl get pod $p -n step09 -o jsonpath='{.status.qosClass}'; echo
done
```

**실행 결과**

```
qos-guaranteed -> Guaranteed
qos-burstable -> Burstable
qos-besteffort -> BestEffort
```

> ⚠️ **함정**: Guaranteed를 만들려면 cpu와 memory **둘 다** requests==limits여야 한다. memory만 맞추고 cpu는 requests만 적으면 Burstable로 떨어진다. 또 `limits`만 적고 `requests`를 생략하면 쿠버네티스가 requests를 limits와 같게 자동 채워서 Guaranteed가 되기도 한다 — 의도치 않은 등급을 피하려면 명시적으로 둘 다 쓰자.

---

## 9-4. OOMKilled 재현하기

메모리 limit을 20Mi로 낮게 걸고, `tail /dev/zero`로 0으로 채워진 페이지를 무한히 메모리에 매핑시킨다. RSS가 20Mi를 넘는 순간 커널이 죽인다.

```yaml
# manifests/04-oomkill.yaml
apiVersion: v1
kind: Pod
metadata:
  name: oom-victim
  namespace: step09
spec:
  restartPolicy: Never          # 재시작 없이 죽은 상태를 관찰
  containers:
    - name: memory-hog
      image: busybox:1.36
      command: ["sh", "-c", "tail /dev/zero"]   # 메모리를 계속 먹는다
      resources:
        requests:
          memory: "16Mi"
        limits:
          memory: "20Mi"        # 이 한도를 넘으면 OOMKill
```

```bash
kubectl apply -f manifests/04-oomkill.yaml
sleep 6
kubectl get pod oom-victim -n step09
```

**실행 결과**

```
NAME         READY   STATUS      RESTARTS   AGE
oom-victim   0/1     OOMKilled   0          5s
```

`kubectl describe`로 종료 이유와 종료코드를 확인한다.

```bash
kubectl describe pod oom-victim -n step09 | sed -n '/Containers:/,/Conditions:/p'
```

**실행 결과**

```
Containers:
  memory-hog:
    Command:
      sh
      -c
      tail /dev/zero
    State:          Terminated
      Reason:       OOMKilled
      Exit Code:    137
      Started:      Mon, 13 Jul 2026 11:54:04 +0900
      Finished:     Mon, 13 Jul 2026 11:54:04 +0900
    Ready:          False
    Restart Count:  0
    Limits:
      memory:  20Mi
    Requests:
      memory:     16Mi
```

`Reason: OOMKilled`, `Exit Code: 137`이 정확히 찍혔다. jsonpath로도 뽑을 수 있다.

```bash
kubectl get pod oom-victim -n step09 \
  -o jsonpath='Reason={.status.containerStatuses[0].state.terminated.reason} ExitCode={.status.containerStatuses[0].state.terminated.exitCode}{"\n"}'
```

**실행 결과**

```
Reason=OOMKilled ExitCode=137
```

> ⚠️ **함정**: 이 데모처럼 limit이 아주 낮아 컨테이너가 시작하자마자 죽는 경우, 타이밍에 따라 `Reason`이 `OOMKilled`가 아니라 그냥 `Error`로 찍히기도 한다(같은 스펙을 여러 번 돌리면 두 값이 번갈아 나온다). 이는 containerd가 종료를 워낙 빨리 감지해 OOM 라벨을 붙이지 못한 경우다. **어느 쪽이든 `Exit Code: 137`이 OOM의 결정적 신호**다 — Reason 문자열보다 종료코드 137(=SIGKILL)을 먼저 보라.

> 💡 **실무 팁**: 실제 운영에서 `restartPolicy: Always`(기본값)면 OOMKilled 파드는 재시작 → 또 OOM → **CrashLoopBackOff**로 빠진다. `kubectl describe`의 `Last State: Terminated, Reason: OOMKilled`를 보고 "메모리 limit이 부족하구나"를 진단하는 게 핵심이다. RESTARTS 숫자만 보고 앱 버그로 오해하기 쉽다.

관찰이 끝났으면 정리한다(다음 데모를 위해 네임스페이스를 비운다).

```bash
kubectl delete pod --all -n step09 --now
```

---

## 9-5. LimitRange — 네임스페이스 기본값과 허용 범위

`LimitRange`는 네임스페이스 안에서 (1) resources를 생략한 컨테이너에 **기본값을 주입**하고, (2) 요청 가능한 **min/max 범위를 강제**한다.

```yaml
# manifests/05-limitrange.yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: step09-limits
  namespace: step09
spec:
  limits:
    - type: Container
      default:            # limits 기본값
        cpu: "200m"
        memory: "128Mi"
      defaultRequest:     # requests 기본값
        cpu: "100m"
        memory: "64Mi"
      min:
        cpu: "50m"
        memory: "32Mi"
      max:
        cpu: "500m"
        memory: "256Mi"
```

```bash
kubectl apply -f manifests/05-limitrange.yaml
kubectl describe limitrange step09-limits -n step09
```

**실행 결과**

```
Name:       step09-limits
Namespace:  step09
Type        Resource  Min   Max    Default Request  Default Limit  Max Limit/Request Ratio
----        --------  ---   ---    ---------------  -------------  -----------------------
Container   cpu       50m   500m   100m             200m           -
Container   memory    32Mi  256Mi  64Mi             128Mi          -
```

### 기본값 주입 확인

resources를 **하나도 안 적은** 파드를 만든다.

```yaml
# manifests/06-pod-no-resources.yaml
spec:
  containers:
    - name: app
      image: nginx:1.27-alpine
      # resources를 일부러 비워둔다
```

```bash
kubectl apply -f manifests/06-pod-no-resources.yaml
kubectl get pod inherit-defaults -n step09 -o jsonpath='{.spec.containers[0].resources}{"\n"}'
```

**실행 결과**

```
{"limits":{"cpu":"200m","memory":"128Mi"},"requests":{"cpu":"100m","memory":"64Mi"}}
```

`describe`로 보면 누가 주입했는지 어노테이션에 남는다.

```bash
kubectl describe pod inherit-defaults -n step09 | grep -A1 Annotations
```

**실행 결과**

```
Annotations:      kubernetes.io/limit-ranger: LimitRanger plugin set: cpu, memory request for container app; cpu, memory limit for container app
```

### max 초과 시 거부

메모리 limit을 512Mi(max 256Mi 초과)로 잡으면 생성이 거부된다.

```bash
kubectl apply -f manifests/07-pod-exceeds-max.yaml
```

**실행 결과**

```
Error from server (Forbidden): error when creating "manifests/07-pod-exceeds-max.yaml": pods "too-big" is forbidden: maximum memory usage per Container is 256Mi, but limit is 512Mi
```

> 💡 **실무 팁**: LimitRange의 진짜 가치는 "실수 방지"다. 개발자가 resources를 깜빡 잊고 배포해도 BestEffort로 떨어지지 않고 안전한 기본값을 받는다. 팀 네임스페이스에는 거의 항상 하나 걸어두는 게 좋다.

정리하고 다음 데모로 넘어간다.

```bash
kubectl delete pod inherit-defaults -n step09 --now
kubectl delete -f manifests/05-limitrange.yaml
```

---

## 9-6. ResourceQuota — 네임스페이스 총량 상한

`LimitRange`가 **컨테이너 1개당** 규칙이라면, `ResourceQuota`는 **네임스페이스 전체 합계**의 상한이다. requests/limits 총량, 파드 개수 등을 묶어서 제한한다.

```yaml
# manifests/08-resourcequota.yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: step09-quota
  namespace: step09
spec:
  hard:
    requests.cpu: "500m"
    requests.memory: "512Mi"
    limits.cpu: "1"
    limits.memory: "1Gi"
    pods: "3"
```

```bash
kubectl apply -f manifests/08-resourcequota.yaml
kubectl get resourcequota -n step09
```

**실행 결과**

```
NAME           REQUEST                                                     LIMIT                                   AGE
step09-quota   pods: 0/3, requests.cpu: 0/500m, requests.memory: 0/512Mi   limits.cpu: 0/1, limits.memory: 0/1Gi   0s
```

### 범위 안 파드는 성공하고 Used가 올라간다

```bash
kubectl apply -f manifests/09-quota-fit.yaml     # requests 128Mi / limits 256Mi
kubectl describe resourcequota step09-quota -n step09
```

**실행 결과**

```
Name:            step09-quota
Namespace:       step09
Resource         Used   Hard
--------         ----   ----
limits.cpu       400m   1
limits.memory    256Mi  1Gi
pods             1      3
requests.cpu     200m   500m
requests.memory  128Mi  512Mi
```

### 총량 초과 시 거부

이미 128Mi를 쓰는 중인데 450Mi를 더 요청하면 `requests.memory` 합이 512Mi를 넘어 거부된다.

```bash
kubectl apply -f manifests/10-quota-exceed.yaml
```

**실행 결과**

```
Error from server (Forbidden): error when creating "manifests/10-quota-exceed.yaml": pods "quota-exceed" is forbidden: exceeded quota: step09-quota, requested: requests.memory=450Mi, used: requests.memory=128Mi, limited: requests.memory=512Mi
```

### 함정: Quota가 걸리면 resources 생략이 불가능해진다

ResourceQuota가 `requests.memory` 같은 항목을 관리하면, **모든 파드가 그 항목을 명시**해야 한다. resources 없는 파드를 넣으면 이렇게 거부된다.

```bash
kubectl apply -f manifests/11-quota-no-resources.yaml
```

**실행 결과**

```
Error from server (Forbidden): error when creating "manifests/11-quota-no-resources.yaml": pods "quota-noreq" is forbidden: failed quota: step09-quota: must specify limits.cpu for: app; limits.memory for: app; requests.cpu for: app; requests.memory for: app
```

> ⚠️ **함정**: ResourceQuota와 LimitRange는 함께 써야 진짜 편해진다. Quota만 있으면 개발자가 매번 requests/limits를 손으로 다 적어야 하고(위 오류), 하나라도 빠지면 배포가 막힌다. **LimitRange로 기본값을 주입**해두면 resources를 생략한 파드도 기본값을 받은 뒤 Quota 검사를 통과한다. 실무에서는 두 개를 세트로 네임스페이스에 건다.

> 💡 **실무 팁**: `kubectl describe resourcequota`의 `Used/Hard`는 용량 계획의 출발점이다. `Used`가 `Hard`에 자주 닿으면 파드가 Pending 없이 **생성 자체가 거부**되므로, 배포 파이프라인에서 원인 모를 Forbidden이 나면 제일 먼저 Quota를 의심하라.

---

## 정리

| 개념 | 한 줄 요약 | 스코프 |
|------|-----------|--------|
| `requests` | 스케줄러가 배치에 쓰는 예약량 | 컨테이너 |
| `limits` | kubelet/커널이 강제하는 상한 | 컨테이너 |
| CPU 초과 | 스로틀링(느려짐, 생존) | 런타임 |
| 메모리 초과 | OOMKilled, 종료코드 137 | 런타임 |
| QoS Guaranteed | 모든 컨테이너 requests==limits(cpu·mem) | 파드 |
| QoS Burstable | requests/limit 일부만 존재 | 파드 |
| QoS BestEffort | resources 전무, 가장 먼저 축출 | 파드 |
| LimitRange | 기본값 주입 + 컨테이너당 min/max | 네임스페이스 |
| ResourceQuota | 네임스페이스 총량·개수 상한 | 네임스페이스 |

## 연습 과제

→ [challenge.md](./challenge.md) — QoS 판별, OOM 진단, LimitRange+Quota 조합 등 5문제.

## 다음 단계

→ [Step 10 — 스토리지](../step-10-storage/README.md)

---

## 실습 파일

이 스텝은 `manifests/00-namespace.yaml`로 `step09` 네임스페이스를 만든 뒤, 번호 순서대로 매니페스트를 적용하며 진행합니다. 01~03은 QoS 3등급(9-1~9-3), 04는 OOMKilled 재현(9-4), 05~07은 LimitRange(9-5), 08~11은 ResourceQuota(9-6)에 대응합니다. `commands.sh`는 이 전체 흐름을 데모 사이사이 정리 단계까지 포함해 한 번에 재현하는 스크립트입니다.

### commands.sh

전체 데모를 순서대로 자동 재현하는 러너입니다. 처음부터 끝까지 한 번 돌려보고 나서 각 매니페스트를 개별로 다시 적용해보는 순서를 권합니다.

- `set -euo pipefail` + `cd "$(dirname "$0")"`로 시작하므로, 어느 위치에서 실행하든 `manifests/` 상대경로가 스크립트 파일 기준으로 해석됩니다.
- 맨 처음 `kubectl config current-context`로 컨텍스트를 찍습니다. **`kind-learn`이 아니면 즉시 중단하세요** — 이 스크립트는 마지막에 `kubectl delete namespace step09`를 무조건 실행하는 파괴적 스크립트입니다.
- QoS 구간에서는 `kubectl wait --for=condition=Ready pod -l demo=qos`로 세 파드가 모두 뜨기를 기다린 뒤 `jsonpath='{.status.qosClass}'`로 등급을 출력하고, 끝나면 `kubectl delete pod -l demo=qos`로 라벨 기준 정리를 합니다. 데모 간 리소스 간섭을 없애려는 의도입니다.
- OOM 구간의 `for i in $(seq 1 30)` 루프는 `terminated.reason`이 채워질 때까지 최대 30초를 1초 간격으로 폴링합니다. `sleep 6` 같은 고정 대기보다 안정적입니다.
- 07/10/11처럼 **거부되는 것이 정상인** 매니페스트는 `kubectl apply ... || echo "(예상된 거부)"`로 감쌌습니다. `set -e` 상태에서 스크립트가 중간에 죽지 않게 하려는 장치이며, 동시에 "여기서 실패가 나야 정상"이라는 학습 신호이기도 합니다.
- `kubectl top nodes` 역시 `|| echo "metrics-server 미설치 → Step 18에서 설치"`로 감싸져 있어, metrics-server가 없어도 스크립트는 계속 진행됩니다.

```bash file="./commands.sh"
```

### manifests/00-namespace.yaml

모든 실습 리소스가 들어갈 `step09` 네임스페이스를 만듭니다. 가장 먼저 적용해야 하며, 이후 매니페스트들은 모두 `namespace: step09`가 하드코딩되어 있어 이 네임스페이스가 없으면 적용 자체가 실패합니다. `course: learn2026`, `step: "09"` 라벨은 나중에 `kubectl get ns -l step=09`처럼 골라내기 위한 표식입니다. 숫자 `09`를 따옴표로 감싼 이유는 YAML 라벨 값이 반드시 문자열이어야 하기 때문입니다.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/01-qos-guaranteed.yaml

9-1과 9-3에서 함께 쓰이는 **Guaranteed** 등급 파드입니다. `requests`와 `limits`가 cpu `100m`, memory `64Mi`로 **완전히 동일**하며, cpu·memory **둘 다** 일치해야 Guaranteed가 됩니다. 하나라도 어긋나면 Burstable로 떨어집니다. `labels`의 `demo: qos`는 `commands.sh`가 `kubectl delete pod -l demo=qos`로 세 파드를 한꺼번에 지울 때 쓰는 셀렉터이고, `qos: guaranteed`는 등급을 눈으로 구분하기 위한 표식입니다. 이 파드의 requests 100m/64Mi가 9-1의 노드 `Allocated resources` 합계(cpu 200m, memory 114Mi)에 그대로 반영됩니다.

```yaml file="./manifests/01-qos-guaranteed.yaml"
```

### manifests/02-qos-burstable.yaml

**Burstable** 등급 파드입니다. requests(cpu `50m`, memory `32Mi`)가 limits(cpu `200m`, memory `128Mi`)보다 **작기 때문에** Guaranteed 조건을 못 채웁니다. 즉 "평소엔 50m만 예약하지만 여유가 있으면 200m까지 치솟을(burst) 수 있다"는 선언입니다. 스케줄러는 이 파드를 배치할 때 limits 200m이 아니라 **requests 50m만** 계산에 넣습니다 — 여기서 오버커밋이 발생합니다. 노드 메모리 압박 시 BestEffort 다음, Guaranteed보다 먼저 축출됩니다.

```yaml file="./manifests/02-qos-burstable.yaml"
```

### manifests/03-qos-besteffort.yaml

**BestEffort** 등급 파드입니다. 컨테이너에 `resources` 필드 자체가 아예 없다는 점이 핵심입니다. 그래서 스케줄러 입장에서는 "0을 요청한 파드"이고, 9-1의 노드 합계에도 이 파드 몫은 한 톨도 잡히지 않습니다(cpu 200m = Guaranteed 100m + Burstable 50m + 시스템 파드 몫). 노드 메모리가 부족해지면 **가장 먼저 축출**되는 등급이므로, 운영 워크로드를 실수로 BestEffort로 만들지 않는 것이 이 스텝의 실용적 교훈입니다. 9-5의 LimitRange가 바로 이 사고를 막는 장치입니다.

```yaml file="./manifests/03-qos-besteffort.yaml"
```

### manifests/04-oomkill.yaml

9-4에서 **일부러 죽이기 위해** 만든 파드입니다. 무엇이 잘못됐는지가 곧 학습 포인트입니다.

- `command: ["sh", "-c", "tail /dev/zero"]` — `/dev/zero`는 0으로 채워진 페이지를 무한히 뱉는 장치 파일이고, `tail`은 이를 계속 읽어 메모리에 매핑합니다. RSS가 끝없이 증가하는 "메모리 먹는 프로세스"를 한 줄로 만드는 고전적인 트릭입니다.
- `limits.memory: "20Mi"` — RSS가 이 한도를 넘는 순간 커널 cgroup OOM killer가 컨테이너 프로세스에 SIGKILL을 보냅니다. 그래서 `Reason: OOMKilled`, `Exit Code: 137`(= 128 + 9)이 찍힙니다.
- `restartPolicy: Never` — 기본값 `Always`였다면 죽자마자 재시작 → 또 OOM → **CrashLoopBackOff**로 빠져 죽은 상태를 관찰하기 어렵습니다. 일부러 Never로 두어 종료 상태를 붙잡아 둔 것입니다.
- 이 데모는 컨테이너가 시작하자마자 죽기 때문에, 타이밍에 따라 `Reason`이 `OOMKilled`가 아니라 `Error`로 찍히기도 합니다(9-4의 함정 박스 참고). **판단 기준은 항상 종료코드 137**입니다.
- 관찰이 끝나면 `kubectl delete pod --all -n step09 --now`로 반드시 정리하세요. 다음 LimitRange 데모의 Quota/기본값 계산이 오염됩니다.

```yaml file="./manifests/04-oomkill.yaml"
```

### manifests/05-limitrange.yaml

9-5의 주인공입니다. `type: Container` 규칙 하나로 네 가지 값을 정의합니다.

- `default` (cpu `200m`, memory `128Mi`) — 컨테이너가 **limits를 안 적었을 때** 주입되는 상한 기본값.
- `defaultRequest` (cpu `100m`, memory `64Mi`) — **requests를 안 적었을 때** 주입되는 예약 기본값. 이름이 `default`와 헷갈리기 쉬우니 "Request가 붙은 쪽이 requests"로 기억하세요.
- `min` (cpu `50m`, memory `32Mi`) / `max` (cpu `500m`, memory `256Mi`) — 개발자가 명시한 값이 이 범위를 벗어나면 **admission 단계에서 생성이 거부**됩니다. 06번은 `default`를 받고, 07번은 이 `max`에 걸립니다.
- 적용 순서가 중요합니다. LimitRange는 **적용된 이후에 생성되는 파드**에만 영향을 줍니다. 이미 떠 있는 파드에 소급 적용되지 않으므로, 반드시 05번을 먼저 apply한 뒤 06번을 적용하세요.

```yaml file="./manifests/05-limitrange.yaml"
```

### manifests/06-pod-no-resources.yaml

`resources`를 **일부러 통째로 비워둔** 파드입니다. 03번(BestEffort)과 매니페스트 내용은 사실상 같지만, 05번 LimitRange가 걸린 상태에서 적용하면 결과가 완전히 달라집니다 — LimitRanger admission 플러그인이 `requests: {cpu: 100m, memory: 64Mi}`, `limits: {cpu: 200m, memory: 128Mi}`를 주입해 **BestEffort가 아니라 Burstable**이 됩니다. 이 대비가 5절의 핵심입니다. 주입 사실은 파드에 남는 `kubernetes.io/limit-ranger` 어노테이션으로 확인할 수 있습니다.

```yaml file="./manifests/06-pod-no-resources.yaml"
```

### manifests/07-pod-exceeds-max.yaml

**의도적으로 거부되도록 만든** 파드입니다. `limits.memory: "512Mi"`가 05번 LimitRange의 `max.memory: 256Mi`를 두 배 초과합니다. `kubectl apply` 시 파드가 Pending으로 남는 게 아니라, API 서버가 admission 단계에서 즉시 `Error from server (Forbidden): ... maximum memory usage per Container is 256Mi, but limit is 512Mi`를 뱉으며 오브젝트 생성 자체를 막습니다. requests(cpu 100m / memory 64Mi)와 `limits.cpu: 200m`은 모두 범위 안이라 문제가 없고, **오직 메모리 limit 하나** 때문에 막히는 상황입니다. "Forbidden = admission 거부"라는 신호를 몸에 익히는 것이 목적입니다.

```yaml file="./manifests/07-pod-exceeds-max.yaml"
```

### manifests/08-resourcequota.yaml

9-6의 주인공으로, **네임스페이스 전체 합계**에 상한을 겁니다. `requests.cpu: "500m"`, `requests.memory: "512Mi"`, `limits.cpu: "1"`, `limits.memory: "1Gi"`는 step09 안의 모든 파드 선언값을 더한 총량이고, `pods: "3"`은 파드 개수 상한입니다. 컨테이너 1개당 규칙인 LimitRange와 스코프가 다르다는 점을 반드시 구분하세요. 그리고 이 Quota가 `requests.memory` 같은 항목을 관리하기 시작하는 순간, 해당 네임스페이스의 **모든 파드는 그 항목을 반드시 명시**해야 합니다 — 11번이 그 함정을 보여줍니다. 9-5에서 05번 LimitRange를 이미 지웠기 때문에 기본값 주입이 없는 상태로 08번을 적용하는 것이 시나리오의 전제입니다.

```yaml file="./manifests/08-resourcequota.yaml"
```

### manifests/09-quota-fit.yaml

Quota 범위 **안에** 들어와 정상 생성되는 파드입니다. requests(cpu `200m` / memory `128Mi`)와 limits(cpu `400m` / memory `256Mi`) 모두 08번의 상한 이내이므로 통과하며, 적용 직후 `kubectl describe resourcequota`의 `Used`가 `requests.memory 128Mi`, `limits.cpu 400m` 등으로 올라갑니다. 여기서 소비한 128Mi가 다음 10번의 거부를 유발하는 전제 조건이므로, **09번을 먼저 적용하지 않으면 10번이 거부되지 않습니다**(450Mi 단독으로는 512Mi 안에 들어가기 때문). 순서 의존성에 주의하세요.

```yaml file="./manifests/09-quota-fit.yaml"
```

### manifests/10-quota-exceed.yaml

**의도적으로 총량을 넘기는** 파드입니다. `requests.memory: "450Mi"`인데 09번이 이미 128Mi를 쓰고 있어 합계가 578Mi가 되고, 08번의 `requests.memory: 512Mi` 상한을 초과합니다. 결과는 `Error from server (Forbidden): ... exceeded quota: step09-quota, requested: requests.memory=450Mi, used: requests.memory=128Mi, limited: requests.memory=512Mi` 입니다. 이 파드는 **Pending으로도 남지 않고 아예 생성되지 않는다**는 점이 핵심입니다 — 노드 용량 부족(Pending)과 Quota 초과(Forbidden)의 차이이며, challenge.md 문제 5가 정확히 이 구분을 묻습니다.

```yaml file="./manifests/10-quota-exceed.yaml"
```

### manifests/11-quota-no-resources.yaml

이 스텝에서 가장 중요한 **함정 파일**입니다. 06번과 마찬가지로 `resources`가 없지만, LimitRange 없이 ResourceQuota만 걸린 상태이므로 기본값을 채워줄 주체가 없습니다. 그 결과 `must specify limits.cpu for: app; limits.memory for: app; requests.cpu for: app; requests.memory for: app` 오류로 거부됩니다. 교훈은 명확합니다 — **ResourceQuota는 "빠짐없이 명시하라"를 강제하고, LimitRange는 그 명시를 "자동으로 채워"준다.** 둘을 세트로 걸어야 개발자가 resources를 생략해도 기본값이 주입된 뒤 Quota 검사를 통과합니다. 이 매니페스트를 05번 LimitRange가 살아 있는 상태에서 적용하면 거부되지 않고 성공하니, 직접 비교해보면 이해가 확실해집니다.

```yaml file="./manifests/11-quota-no-resources.yaml"
```

> ⚠️ **정리 필수**: 실습이 끝나면 `kubectl delete namespace step09`로 네임스페이스를 통째로 지웁니다. LimitRange/ResourceQuota가 남아 있으면 이후 스텝의 파드 생성이 예상치 못하게 거부될 수 있습니다.
