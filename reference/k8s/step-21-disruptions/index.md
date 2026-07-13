# Step 21 — 가용성과 중단 관리

## 학습 목표
- **자발적 중단**과 **비자발적 중단**을 구분한다.
- **PodDisruptionBudget(PDB)**로 자발적 중단 중에도 최소 가용 파드 수를 보장한다. drain이 PDB에 막히는 것을 실증한다.
- **PriorityClass**와 **preemption**(선점)으로 중요한 파드가 자리를 확보하는 것을 실증한다.
- `cordon`/`uncordon`/`drain`으로 노드를 안전하게 유지보수한다.
- `terminationGracePeriodSeconds`와 무중단 배포, PDB의 관계를 이해한다.

## 선행 지식
- Step 05(Deployment), Step 13(스케줄링/노드 선택), Step 18(스케일). 멀티노드 클러스터가 필요합니다(kind 3노드).

## 소요 시간
- 40분

> ⚠️ **이 스텝은 노드 상태를 바꿉니다(cordon/drain).** 모든 실습은 **learn-worker2 하나**에만, 짧게 하고 **직후 반드시 `uncordon`** 합니다. PriorityClass는 전역 리소스라 `step21-` 접두사를 붙이고 검증 후 삭제합니다. 다른 학습자의 파드가 노드에 있을 수 있으니 drain은 오래 끌지 마세요.

---

## 1. 두 종류의 중단

| 종류 | 예 | 쿠버네티스가 존중하는가 |
|---|---|---|
| **자발적(voluntary)** | 노드 drain(유지보수), 배포/롤아웃, `kubectl delete pod`, 클러스터 오토스케일 축소 | **예 — PDB로 제어 가능** |
| **비자발적(involuntary)** | 노드 하드웨어 고장, 커널 패닉, OOM, 네트워크 단절 | 아니오 — 막을 수 없음 |

PDB는 **자발적 중단만** 제어합니다. 노드가 갑자기 죽는 것(비자발적)은 PDB로 못 막습니다. 그건 replica 수·여러 노드 분산으로 대비합니다.

이 스텝은 4개 replica를 두 워커에 퍼뜨린 `web` Deployment와 PDB로 실습합니다.

```bash
kubectl apply -f manifests/namespace.yaml
kubectl apply -f manifests/web.yaml
kubectl apply -f manifests/pdb.yaml
kubectl get pods -n step21 -o wide
```
```
web-...-cfrlt   Running   learn-worker
web-...-f9pzn   Running   learn-worker2
web-...-hj48j   Running   learn-worker2
web-...-vmh6t   Running   learn-worker
```

---

## 2. PodDisruptionBudget

`manifests/pdb.yaml` (전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다):
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
spec:
  minAvailable: 3          # 항상 최소 3개는 살아있어야 한다
  selector:
    matchLabels:
      app: web
```

`minAvailable: 3`은 4개 중 **한 번에 최대 1개**만 자발적으로 내릴 수 있다는 뜻입니다. `maxUnavailable: 1`로 써도 같습니다(백분율 `maxUnavailable: 25%`도 가능).

```bash
kubectl get pdb -n step21
```
```
NAME      MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
web-pdb   3               N/A               1                     1s
```

`ALLOWED DISRUPTIONS 1`이 핵심 — 지금 이 순간 몇 개까지 evict를 허용하는지입니다. 이 값이 0이면 drain이 아예 진행되지 않습니다.

> **함정 — 셀렉터 불일치**: PDB의 `selector`가 어떤 파드와도 안 맞으면 `ALLOWED DISRUPTIONS`가 이상해지고 아무것도 보호하지 못합니다. `kubectl describe pdb`로 `Selector`와 대상 파드 수를 확인하세요.

---

## 3. cordon / uncordon — 스케줄링만 막기

`cordon`은 노드에 **새 파드 스케줄만 막고**, 기존 파드는 건드리지 않습니다.

```bash
kubectl cordon learn-worker2
kubectl get nodes
```
```
learn-worker2   Ready,SchedulingDisabled   <none>   40m   v1.36.1
```

`SchedulingDisabled` 상태에서 스케일업하면 새 파드는 다른 노드로만 갑니다:
```bash
kubectl scale deploy/web -n step21 --replicas=6
kubectl get pods -n step21 -o wide     # 새 파드 2개 모두 learn-worker
```
```
web-...-bmrkv   learn-worker      ← 신규
web-...-n8csm   learn-worker      ← 신규
web-...-f9pzn   learn-worker2     ← 기존(그대로)
```

원복:
```bash
kubectl uncordon learn-worker2
kubectl scale deploy/web -n step21 --replicas=4
```

---

## 4. drain — 노드 비우기 (PDB 실증)

`drain`은 cordon + **기존 파드를 다른 노드로 evict**합니다. 노드 유지보수(커널 업데이트 등) 전에 씁니다. **이때 PDB를 존중**합니다.

```bash
kubectl drain learn-worker2 --ignore-daemonsets --delete-emptydir-data --timeout=120s
```
**실제 출력** (핵심 부분):
```
node/learn-worker2 cordoned
Warning: ignoring DaemonSet-managed Pods: kube-system/kindnet-..., kube-system/kube-proxy-...
evicting pod step21/web-...-hj48j
evicting pod step21/web-...-f9pzn
error when evicting pods/"web-...-hj48j" -n "step21" (will retry after 5s): Cannot evict pod as it would violate the pod's disruption budget.
pod/web-...-f9pzn evicted
evicting pod step21/web-...-hj48j
pod/web-...-hj48j evicted
node/learn-worker2 drained
```

**여기가 이 스텝의 핵심 장면입니다.** worker2에는 web 파드가 2개 있었는데, PDB(minAvailable=3)가 **한 번에 하나만** 내리도록 강제했습니다:
1. 첫 번째 파드 evict 시도 → 하나는 성공(3개 유지)
2. 두 번째 파드 evict 시도 → `Cannot evict pod as it would violate the pod's disruption budget` → **거부하고 5초 뒤 재시도**
3. 첫 번째의 대체 파드가 learn-worker에서 Ready가 되어 가용 수가 회복되자 → 두 번째 evict 허용

PDB가 없었다면 두 파드가 **동시에** 사라져 순간적으로 가용성이 2개로 떨어졌을 것입니다.

### ★ 실습 직후 반드시 uncordon

```bash
kubectl uncordon learn-worker2
kubectl get nodes                 # 모두 Ready, SchedulingDisabled 없어야
```
```
learn-worker2   Ready   <none>   41m   v1.36.1
```
drain 후 web 파드는 전부 learn-worker로 옮겨가 모두 Running 상태가 됩니다. (`--ignore-daemonsets`는 kindnet/kube-proxy 같은 DaemonSet 파드를 건드리지 않겠다는 뜻 — 이들은 모든 노드에 하나씩 있어야 하므로 evict 대상이 아닙니다.)

---

## 5. PriorityClass와 preemption

노드가 꽉 찼을 때, 중요한 파드가 덜 중요한 파드를 **밀어내고**(preempt) 자리를 차지하게 할 수 있습니다.

`manifests/priorityclass.yaml` — 전역 리소스라 `step21-` 접두사:
```yaml
kind: PriorityClass
metadata: {name: step21-low}
value: 100
---
kind: PriorityClass
metadata: {name: step21-high}
value: 1000000
```

실증(`manifests/preemption-demo.yaml`): 두 파드를 learn-worker2(8코어)에 고정하고 CPU를 크게 요청해 둘이 동시에 못 들어가게 합니다.

```bash
# 1) 낮은 우선순위 파드가 6코어를 예약하며 먼저 배치
kubectl apply -f (victim-low: priorityClassName=step21-low, cpu=6)
# victim-low  Running  learn-worker2

# 2) 높은 우선순위 파드(cpu=5) 투입 → 자리가 없음
kubectl apply -f (important-high: priorityClassName=step21-high, cpu=5)
```

**실제 결과**:
```
NAME             STATUS    NODE
important-high   Running   learn-worker2      ← 배치됨
# victim-low 는 사라짐:
kubectl get pod victim-low -n step21
Error from server (NotFound): pods "victim-low" not found
```

이벤트가 전 과정을 보여줍니다:
```bash
kubectl get events -n step21 | grep -i preempt
# Preempted  pod/victim-low  Preempted by pod ... on node learn-worker2

kubectl describe pod important-high -n step21   # Events:
# Warning  FailedScheduling  0/3 nodes are available: 1 Insufficient cpu, ...
# Normal   Scheduled         Successfully assigned step21/important-high to learn-worker2
```

`important-high`는 처음엔 `FailedScheduling`(자리 없음)이었다가, 스케줄러가 `victim-low`를 preempt해 자리를 만든 뒤 `Scheduled` 되었습니다.

> **주의**: preemption은 자발적 중단이지만 **PDB를 우회할 수 있습니다**(단, 스케줄러는 PDB를 "best effort"로 존중하려 시도). 우선순위가 아주 높은 시스템 파드가 PDB보다 우선할 수 있음을 기억하세요. `system-cluster-critical` 같은 내장 PriorityClass는 매우 높은 값을 가집니다.

---

## 6. terminationGracePeriod와 무중단 배포

파드가 evict/삭제되면 쿠버네티스는:
1. 파드를 `Terminating`으로 표시하고 **Service 엔드포인트에서 제거**(새 트래픽 차단)
2. 컨테이너에 `SIGTERM` 전송
3. `terminationGracePeriodSeconds`(기본 30초) 동안 대기 — 앱이 처리 중인 요청을 마무리(graceful shutdown)
4. 시간 초과 시 `SIGKILL`

`manifests/web.yaml`은 `terminationGracePeriodSeconds: 10`으로 설정했습니다. 앱이 이 신호를 받아 커넥션을 정리하면 **요청 유실 없이** 종료됩니다.

**무중단 배포 = 세 가지의 합작**:
- **readinessProbe**: 새 파드가 준비되기 전엔 트래픽을 안 받음(Step 08)
- **rollingUpdate `maxUnavailable`/`maxSurge`**: 롤아웃 중 최소 가용 수 유지(Step 05)
- **PDB**: drain/노드 유지보수 중 최소 가용 수 유지(이 스텝)

세 개가 서로 다른 상황(배포 / 노드 유지보수 / 스케줄링)을 커버합니다. 하나만으로는 부족합니다.

---

## 팁과 함정

- **함정 1 — drain 후 uncordon을 잊음**: 노드가 `SchedulingDisabled`로 남으면 그 노드는 영영 새 파드를 못 받습니다. 클러스터 용량이 조용히 줄어듭니다. **drain 실습은 항상 uncordon으로 끝내세요.**
- **함정 2 — PDB가 drain을 영구 차단**: replica가 PDB를 만족시킬 만큼 다른 노드에 못 뜨면(자원 부족), drain이 `Cannot evict ... disruption budget`으로 **무한 재시도**합니다. drain이 안 끝나면 PDB와 클러스터 여유 용량을 확인하세요.
- **함정 3 — `minAvailable`을 replica와 같게**: `replicas: 3`인데 `minAvailable: 3`이면 `ALLOWED DISRUPTIONS 0` — drain이 절대 진행 안 됩니다. 최소 1개는 내릴 여유를 두세요.
- **팁 — `--dry-run`으로 drain 미리보기**: `kubectl drain <node> --dry-run=client`로 어떤 파드가 evict될지 먼저 확인.
- **팁 — DaemonSet은 `--ignore-daemonsets` 필수**: 안 주면 drain이 "DaemonSet 파드가 있다"며 멈춥니다. DaemonSet은 노드마다 하나씩 있어야 하므로 옮기지 않는 게 정상입니다.
- **팁 — PriorityClass는 전역**: 네임스페이스에 속하지 않습니다. 이름 충돌·실수 삭제에 주의하고, 실습용은 접두사로 구분하세요.

---

## 정리표

| 명령/필드 | 뜻 |
|---|---|
| `PodDisruptionBudget minAvailable/maxUnavailable` | 자발적 중단 시 보장할 최소 가용/최대 불가용 |
| `kubectl get pdb` → ALLOWED DISRUPTIONS | 지금 evict 허용 가능 수 |
| `kubectl cordon <node>` | 새 스케줄만 차단 (evict 안 함) |
| `kubectl uncordon <node>` | cordon 해제 |
| `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data` | cordon + PDB 존중하며 evict |
| `PriorityClass value` | 스케줄링 우선순위(클수록 높음) |
| preemption | 높은 우선순위가 낮은 파드를 밀어냄 |
| `terminationGracePeriodSeconds` | SIGTERM 후 SIGKILL까지 유예(기본 30s) |

---

## 연습 과제

[challenge.md](challenge.md)의 4개 과제.

---

## 다음 단계

→ [Step 22 — CRD와 오퍼레이터](../step-22-crd-operators/index.md): CustomResourceDefinition으로 쿠버네티스 API를 확장하고, 오퍼레이터 패턴으로 운영 지식을 코드로 자동화합니다.

---

## 실습 파일

이 스텝의 파일은 크게 두 갈래입니다. **PDB 실증 갈래**(`namespace.yaml` → `web.yaml` → `pdb.yaml`)로 4-replica 워크로드와 예산을 만든 뒤 cordon/drain을 걸어보고, **preemption 갈래**(`priorityclass.yaml` → `preemption-demo.yaml`)로 우선순위가 자리를 뺏는 장면을 재현합니다. `commands.sh`는 이 두 갈래를 처음부터 정리까지 한 번에 돌려주는 스크립트이며, 노드를 되돌리는 안전장치까지 포함하고 있으니 **개별 매니페스트를 손으로 apply 하기 전에 먼저 읽어보길 권합니다.**

### commands.sh

이 스텝 전체(본문 1~5절)를 순서대로 실행하는 드라이버 스크립트입니다. 가장 중요한 줄은 `trap 'echo "[trap] uncordon learn-worker2"; kubectl uncordon learn-worker2 >/dev/null 2>&1 || true' EXIT` 입니다 — 스크립트가 중간에 죽거나 Ctrl-C로 끊겨도 EXIT 트랩이 반드시 `uncordon`을 실행해서, 본문 "함정 1 — drain 후 uncordon을 잊음"에 걸리지 않도록 막아줍니다.

- `set -uo pipefail`에 `-e`가 **일부러 빠져 있습니다.** drain이 PDB에 막혀 0이 아닌 종료 코드를 낼 수 있는데, 그것도 학습 대상 출력이므로 스크립트를 중단시키지 않으려는 의도입니다.
- `HERE="$(cd "$(dirname "$0")" && pwd)"`로 스크립트 위치를 잡고 모든 apply를 `-f "$HERE/manifests/..."`로 참조하므로, 어느 디렉터리에서 실행해도 동작합니다.
- cordon 구간에서 `kubectl scale ... --replicas=6` 후 `sleep 6` → `awk '{print $1,$7}'`로 파드 이름과 노드만 뽑아 보여줍니다. 신규 2개가 모두 `learn-worker`에만 뜨는 것이 3절의 관찰 포인트입니다. 곧바로 `uncordon` + `--replicas=4`로 원복합니다.
- `kubectl drain learn-worker2 --ignore-daemonsets --delete-emptydir-data --timeout=120s` **바로 다음 줄이 `kubectl uncordon learn-worker2`** 입니다. drain 성공/실패와 무관하게 즉시 노드를 되돌리려는 배치입니다.
- preemption 구간은 `priorityclass.yaml` → `preemption-demo.yaml` 순으로 apply 한 뒤 `sleep 8`로 스케줄러가 판단할 시간을 주고, `kubectl get pods -l app=preempt-demo -o wide`와 `kubectl get events --sort-by='.lastTimestamp' | grep -i preempt`로 결과를 확인합니다. grep 뒤의 `|| true`는 매칭이 없어도(preemption 미발생) 스크립트를 죽이지 않기 위한 것입니다.
- 마지막 정리 블록은 전역 리소스인 `priorityclass step21-low step21-high`를 지우고 `namespace step21`을 삭제합니다. `metrics-server`는 다른 스텝이 쓰므로 삭제하지 않는다고 명시(`echo`)합니다.
- **주의**: 이 스크립트는 실제로 노드를 drain 하는 파괴적 스크립트입니다. 공용 클러스터에서는 `learn-worker2`에 다른 파드가 올라가 있을 수 있으니 확인 후 실행하세요.

```bash file="./commands.sh"
```

### manifests/namespace.yaml

실습 전용 네임스페이스 `step21`을 만듭니다. 가장 먼저 apply 해야 하며, 네임스페이스 리소스인 `web.yaml`·`pdb.yaml`·`preemption-demo.yaml`이 모두 `namespace: step21`을 하드코딩하고 있으므로 이 파일 없이 그것들을 적용하면 실패합니다. (`priorityclass.yaml`만은 클러스터 스코프라 `namespace` 필드가 없고, 이 파일과 무관하게 적용됩니다.)

- `labels`의 `course: k8s-learn`, `step: "21"`은 교재 전체에서 실습 리소스를 식별하기 위한 표식입니다. `"21"`을 따옴표로 감싼 이유는 라벨 값이 반드시 문자열이어야 하기 때문입니다 — 따옴표를 빼면 YAML 파서가 정수 `21`로 읽고, API 서버가 문자열이 아닌 라벨 값을 거부합니다.
- 정리는 `kubectl delete namespace step21` 한 줄이면 되지만, **PriorityClass는 네임스페이스에 속하지 않으므로 따로 지워야 합니다**(본문 5절 주의사항).

```yaml file="./manifests/namespace.yaml"
```

### manifests/web.yaml

PDB의 보호 대상이 될 `web` Deployment입니다. 본문 1절에서 apply 하며, 4절 drain 실습의 주인공입니다. `replicas: 4`와 PDB의 `minAvailable: 3`이 짝을 이뤄 "한 번에 1개만 evict 가능"이라는 상황을 만듭니다.

- `topologySpreadConstraints`의 `maxSkew: 1` + `topologyKey: kubernetes.io/hostname`이 4개 파드를 두 워커에 2:2로 퍼뜨립니다. 이 분산이 없으면 파드가 한 노드에 몰려서 drain 실습이 밋밋해집니다. `whenUnsatisfiable: ScheduleAnyway`라 **강제가 아닌 선호**이므로, 노드가 cordon 되어 균등 분산이 불가능해도 파드가 Pending에 빠지지 않습니다(3절에서 신규 파드가 `learn-worker`로만 가는 이유).
- `terminationGracePeriodSeconds: 10`은 기본 30초를 줄인 값으로, 6절의 graceful shutdown 설명과 직결됩니다. SIGTERM 후 최대 10초 기다렸다가 SIGKILL 합니다.
- `requests: cpu: 10m, memory: 16Mi`로 아주 작게 잡아서, 4개(또는 6개)를 띄워도 노드 자원이 남습니다. 덕분에 5절 preemption 실습에서 CPU를 크게 요청하는 파드가 자리 경쟁을 벌일 여지가 생깁니다.
- `readinessProbe`(`httpGet` 경로 `/`, 포트 `80`, `periodSeconds: 5`)가 PDB 계산의 기준입니다. PDB는 **Ready 상태인 파드 수**를 세므로, evict된 파드의 대체 파드가 Ready가 되어야 `ALLOWED DISRUPTIONS`가 다시 1로 회복됩니다. `periodSeconds: 5`는 이 probe를 5초마다 수행한다는 뜻이고, 4절 drain 출력의 `will retry after 5s`는 **kubectl drain 자체의 재시도 간격**입니다(둘은 우연히 값만 같을 뿐 별개입니다). 두 주기가 맞물려 "거부 → 대체 파드 Ready → 재시도 성공" 흐름이 만들어집니다.
- 컨테이너 이미지는 `nginx:1.27-alpine`입니다. nginx는 SIGTERM에 곧바로 종료하므로 grace period 10초를 다 쓰지 않고 빠르게 사라집니다(연습 과제 4의 관찰 포인트).

```yaml file="./manifests/web.yaml"
```

### manifests/pdb.yaml

이 스텝의 핵심 리소스입니다. `web` Deployment 직후에 apply 하고, `kubectl get pdb -n step21`로 `ALLOWED DISRUPTIONS`를 확인한 뒤 drain을 겁니다.

- `minAvailable: 3`은 "자발적 중단이 일어나도 Ready 파드가 최소 3개는 남아야 한다"는 뜻입니다. replica가 4이므로 `4 - 3 = 1`, 즉 **동시에 evict 가능한 파드는 1개**입니다. 주석대로 `maxUnavailable: 1`로 써도 같은 의미이며, 둘을 **동시에 쓸 수는 없습니다**.
- `selector.matchLabels.app: web`이 보호 대상을 고릅니다. 이 라벨이 `web.yaml`의 파드 템플릿 라벨과 정확히 일치해야 하며, 어긋나면 본문 2절 "함정 — 셀렉터 불일치"처럼 아무것도 보호하지 못합니다.
- PDB는 **자발적 중단에만** 적용됩니다. 노드가 갑자기 죽는 비자발적 중단은 이 파일로 막을 수 없습니다.
- 만약 replica를 3으로 줄이고 `minAvailable: 3`을 유지하면 `ALLOWED DISRUPTIONS`가 0이 되어 drain이 영원히 진행되지 않습니다 — 연습 과제 1이 바로 이 상황을 재현하는 문제입니다.

```yaml file="./manifests/pdb.yaml"
```

### manifests/priorityclass.yaml

본문 5절 preemption 실습의 준비물입니다. 한 파일에 `---`로 두 개의 PriorityClass를 담고 있습니다.

- `step21-low`는 `value: 100`, `step21-high`는 `value: 1000000`. 값이 클수록 우선순위가 높고, 노드가 꽉 찼을 때 높은 쪽이 낮은 쪽을 밀어냅니다.
- `globalDefault: false`가 **양쪽 모두에 지정된 것이 중요합니다.** `true`로 두면 PriorityClass를 지정하지 않은 클러스터 전체의 모든 신규 파드가 이 우선순위를 기본값으로 물려받게 되어, 실습 리소스가 클러스터 전역에 부작용을 일으킵니다.
- **PriorityClass는 클러스터 스코프(전역) 리소스**라 `kubectl delete ns step21`으로는 지워지지 않습니다. 그래서 이름에 `step21-` 접두사를 붙여 다른 학습자·시스템 PriorityClass와 충돌하지 않게 했고, `commands.sh` 마지막에서 `kubectl delete priorityclass step21-low step21-high`로 별도 삭제합니다.

```yaml file="./manifests/priorityclass.yaml"
```

### manifests/preemption-demo.yaml

`priorityclass.yaml` **다음에** apply 합니다(참조하는 PriorityClass가 먼저 존재해야 합니다). 두 개의 Pod로 "자리 뺏기"를 인위적으로 연출한 실습 파일입니다.

- 두 파드 모두 `nodeSelector: kubernetes.io/hostname: learn-worker2`로 **같은 노드에 못 박아** 자원 경쟁을 강제합니다. 노드가 여러 개면 스케줄러가 그냥 다른 노드에 배치해버려 preemption이 일어나지 않습니다(연습 과제 3의 원인 1).
- `victim-low`는 `priorityClassName: step21-low` + `cpu: "6"`으로 8코어 노드의 대부분을 예약합니다. `important-high`는 `priorityClassName: step21-high` + `cpu: "5"`를 요청하는데, `6 + 5 = 11 > 8`이라 **둘이 동시에는 절대 들어갈 수 없습니다.** 이 숫자 설계가 실습의 전부입니다.
- 결과적으로 스케줄러는 `important-high`를 넣기 위해 `victim-low`를 **삭제**합니다. `victim-low`는 Deployment가 아닌 **베어 Pod**이므로 재생성되지 않고 그대로 사라집니다(`Error from server (NotFound)`). 이것이 preemption의 파괴성을 보여주는 학습 포인트입니다.
- `victim-low`에만 `terminationGracePeriodSeconds: 5`가 붙어 있어, preempt 당했을 때 5초 안에 정리되고 자리가 빨리 비워집니다.
- `commands.sh`는 두 파드를 한 번에 apply 하지만(주석: "low 가 먼저면 좋지만 동시 apply 도 스케줄러가 처리"), 순서를 확실히 보고 싶다면 `victim-low`가 Running이 된 뒤 `important-high`를 넣으면 `FailedScheduling` → `Preempted` → `Scheduled` 이벤트 흐름이 더 또렷하게 보입니다.

```yaml file="./manifests/preemption-demo.yaml"
```
