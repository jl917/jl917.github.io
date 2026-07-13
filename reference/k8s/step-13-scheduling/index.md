# Step 13 — 스케줄링 제어

> **학습 목표**: 파드가 "어느 노드에" 배치될지 제어하는 모든 수단(nodeSelector·nodeName·nodeAffinity·podAffinity/AntiAffinity·taint/toleration·topologySpreadConstraints)을 실습하고, 스케줄이 안 될 때 Pending 원인을 `describe`로 진단한다. / **선행 스텝**: Step 12 / **예상 소요**: 70분

Kubernetes 스케줄러는 파드를 노드에 배치할 때 두 단계를 거친다. **필터링**(조건에 안 맞는 노드 제거) 후 **스코어링**(남은 노드 중 최적 선택)이다. 이 스텝의 도구들은 이 두 단계에 개입한다.

| 도구 | 무엇을 하나 | 강제성 |
|---|---|---|
| `nodeName` | 스케줄러를 건너뛰고 노드 직접 지정 | 절대(검증 없음) |
| `nodeSelector` | 라벨이 맞는 노드에만 | 하드(필터) |
| `nodeAffinity` (required) | 표현식이 맞는 노드에만 | 하드(필터) |
| `nodeAffinity` (preferred) | 가능하면 선호 | 소프트(스코어) |
| `podAffinity/AntiAffinity` | 다른 파드 기준 같이/따로 | 하드 or 소프트 |
| `taint` + `toleration` | 노드가 파드를 밀어냄, 견디면 허용 | 하드 |
| `topologySpreadConstraints` | 도메인(노드/존)에 고르게 분산 | 하드 or 소프트 |

> ⚠️ **모든 실습은 `step13` 네임스페이스에서 진행한다.** 이 스텝에서만 노드에 테인트를 걸며, **워커 노드에만, 고유 키(`step13-demo`)로, `NoSchedule`로** 걸고 실습 직후 즉시 제거한다. control-plane 노드는 절대 건드리지 않는다.

먼저 클러스터 노드와 내장 라벨을 확인한다. kind는 노드마다 `kubernetes.io/hostname` 라벨을 기본으로 붙여 주므로, 대부분의 실습은 **노드를 변경하지 않고** 이 내장 라벨만으로 가능하다.

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl get nodes -L kubernetes.io/hostname
```

**실행 결과**
```
NAME                  STATUS   ROLES           AGE   VERSION   HOSTNAME
learn-control-plane   Ready    control-plane   14m   v1.36.1   learn-control-plane
learn-worker          Ready    <none>          14m   v1.36.1   learn-worker
learn-worker2         Ready    <none>          14m   v1.36.1   learn-worker2
```

---

## 13-1. nodeSelector — 라벨로 노드 고르기

가장 단순한 제어. 파드의 `nodeSelector`에 적은 라벨을 **모두** 가진 노드에만 배치된다. 하나라도 맞는 노드가 없으면 파드는 Pending에 빠진다.

```yaml
# manifests/01-nodeselector.yaml
apiVersion: v1
kind: Pod
metadata:
  name: pin-worker
  namespace: step13
spec:
  nodeSelector:
    kubernetes.io/hostname: learn-worker   # learn-worker 에만 배치
  containers:
    - name: app
      image: nginx:1.27-alpine
      resources:
        requests: { cpu: 10m, memory: 16Mi }
```

```bash
kubectl apply -f manifests/01-nodeselector.yaml
```

`nodeSelector`는 뒤에서 볼 `nodeName`, `nodeAffinity`와 함께 확인한다(13-3 참고).

> 💡 **실무 팁**: nodeSelector는 "AND"만 된다. "SSD **또는** NVMe" 같은 OR, "메모리 큰 노드 선호" 같은 소프트 조건은 표현 못 한다. 조금이라도 복잡하면 nodeAffinity를 쓴다.

---

## 13-2. nodeName — 스케줄러 건너뛰기

`nodeName`을 직접 적으면 스케줄러가 개입하지 않는다. kubelet이 해당 노드에서 곧바로 파드를 띄운다. 리소스 여유·테인트를 **검사하지 않기 때문에**, 노드가 꽉 찼거나 테인트가 걸려 있어도 밀어붙이다 실패할 수 있다.

```yaml
# manifests/02-nodename.yaml
apiVersion: v1
kind: Pod
metadata:
  name: direct-assign
  namespace: step13
spec:
  nodeName: learn-worker2      # 스케줄러 우회, 곧바로 이 노드
  containers:
    - name: app
      image: nginx:1.27-alpine
      resources:
        requests: { cpu: 10m, memory: 16Mi }
```

```bash
kubectl apply -f manifests/02-nodename.yaml
```

> ⚠️ **함정**: `nodeName`은 디버깅·특수한 경우에만. 스케줄러의 리소스/테인트/어피니티 판단을 전부 무시하므로 프로덕션 워크로드에 쓰면 노드 과부하나 예기치 않은 실패로 이어진다.

---

## 13-3. nodeAffinity — required / preferred

nodeSelector의 상위 호환. 표현식(`In`, `NotIn`, `Exists`, `Gt`, `Lt` …)을 쓸 수 있고 하드(required)/소프트(preferred)를 나눌 수 있다.

```yaml
# manifests/03-nodeaffinity.yaml
apiVersion: v1
kind: Pod
metadata:
  name: affinity-required
  namespace: step13
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:      # 하드: 워커에만
        nodeSelectorTerms:
          - matchExpressions:
              - key: kubernetes.io/hostname
                operator: In
                values: ["learn-worker", "learn-worker2"]
      preferredDuringSchedulingIgnoredDuringExecution:     # 소프트: 가능하면 worker2
        - weight: 100
          preference:
            matchExpressions:
              - key: kubernetes.io/hostname
                operator: In
                values: ["learn-worker2"]
```

```bash
kubectl apply -f manifests/03-nodeaffinity.yaml
kubectl wait --for=condition=Ready pod/pin-worker pod/direct-assign pod/affinity-required -n step13 --timeout=90s
kubectl get pod -n step13 -o wide
```

**실행 결과**
```
NAME                READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
affinity-required   1/1     Running   0          1s    10.244.2.33   learn-worker2   <none>           <none>
direct-assign       1/1     Running   0          1s    10.244.2.32   learn-worker2   <none>           <none>
pin-worker          1/1     Running   0          1s    10.244.1.27   learn-worker    <none>           <none>
```

- `pin-worker` → `learn-worker` (13-1 nodeSelector)
- `direct-assign` → `learn-worker2` (13-2 nodeName)
- `affinity-required` → `learn-worker2` (required로 워커만, preferred 가중치로 worker2 선택)

> 💡 **실무 팁**: 이름 끝의 `IgnoredDuringExecution`은 "이미 실행 중인 파드는 조건이 깨져도 쫓아내지 않는다"는 뜻이다. `RequiredDuringExecution`(라벨이 바뀌면 축출)은 아직 구현되지 않았다.

---

## 13-4. podAffinity — 다른 파드 곁에 붙이기

노드가 아니라 **다른 파드**를 기준으로 배치한다. 캐시를 앱과 같은 노드에 두어 지연을 줄이는 식이다. `topologyKey`가 "같음"의 기준이다(`kubernetes.io/hostname`이면 "같은 노드").

```yaml
# manifests/04-podaffinity.yaml (발췌)
apiVersion: v1
kind: Pod
metadata: { name: cache, namespace: step13, labels: { app: cache } }
spec:
  affinity:
    podAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        - labelSelector:
            matchLabels: { app: web }        # web 파드가 있는
          topologyKey: kubernetes.io/hostname  # 같은 노드에
  containers:
    - name: cache
      image: redis:7-alpine
      resources: { requests: { cpu: 10m, memory: 16Mi } }
```

```bash
kubectl apply -f manifests/04-podaffinity.yaml
kubectl wait --for=condition=Ready pod -l app=web -n step13 --timeout=90s
kubectl wait --for=condition=Ready pod/cache -n step13 --timeout=90s
kubectl get pod -n step13 -l 'app in (web,cache)' -o wide
```

**실행 결과**
```
NAME                  READY   STATUS    RESTARTS   AGE   IP            NODE           NOMINATED NODE   READINESS GATES
cache                 1/1     Running   0          94s   10.244.1.34   learn-worker   <none>           <none>
web-799f4cc4d-6mj92   1/1     Running   0          94s   10.244.1.33   learn-worker   <none>           <none>
```

`web`가 `learn-worker`에 떴으므로 `cache`도 같은 `learn-worker`에 따라붙었다.

> ⚠️ **함정**: podAffinity를 required로 걸었는데 기준 파드가 아직 없으면 새 파드는 Pending이다. "닭이 먼저냐" 문제를 피하려면 기준 파드를 먼저 띄우거나 soft(preferred)로 완화한다.

---

## 13-5. podAntiAffinity — 서로 떼어 놓기

같은 앱의 복제본을 **다른 노드**에 흩어 놓아 노드 하나가 죽어도 전멸하지 않게 한다. 고가용성의 기본기다.

```yaml
# manifests/05-podantiaffinity.yaml (발췌)
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        - labelSelector:
            matchLabels: { app: spread-app }
          topologyKey: kubernetes.io/hostname   # 같은 노드에 두 개 금지
```

```bash
kubectl apply -f manifests/05-podantiaffinity.yaml
kubectl wait --for=condition=Ready pod -l app=spread-app -n step13 --timeout=90s
kubectl get pod -n step13 -l app=spread-app -o wide
```

**실행 결과**
```
NAME                          READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
spread-app-5794f9dfdf-v5qtn   1/1     Running   0          94s   10.244.2.34   learn-worker2   <none>           <none>
spread-app-5794f9dfdf-xs6g7   1/1     Running   0          94s   10.244.1.35   learn-worker    <none>           <none>
```

복제본 2개가 서로 다른 워커에 하나씩 배치됐다.

> ⚠️ **함정**: required podAntiAffinity + replicas가 워커 수보다 많으면 초과분은 **영원히 Pending**이다(노드 하나에 둘을 못 두니까). 이럴 땐 soft로 바꾸거나 topologySpread(13-7)를 쓴다.

---

## 13-6. taint / toleration — 노드가 파드를 밀어내기

어피니티가 "파드가 노드를 고르는" 것이라면, 테인트는 "노드가 파드를 거부하는" 반대 방향이다. 노드에 테인트를 걸면, 그 테인트를 **견디는(toleration)** 파드만 그 노드에 들어올 수 있다. `effect`는 `NoSchedule`(새 파드 거부), `PreferNoSchedule`(가능하면 피함), `NoExecute`(기존 파드도 축출) 세 가지다.

> control-plane 노드가 일반 파드로 붐비지 않는 이유가 바로 이것이다. control-plane에는 `node-role.kubernetes.io/control-plane:NoSchedule` 테인트가 기본으로 걸려 있다(Step 12에서 확인).

이번엔 **워커에** 고유 키로 테인트를 걸어 실험한다. 아래 스크립트는 테인트를 걸고 → 파드 배치를 확인하고 → **즉시 테인트를 제거**한다.

```yaml
# manifests/06-toleration.yaml (발췌)
# no-tol  : 톨러레이션 없음 → 테인트 걸린 learn-worker 를 피함
# with-tol: 톨러레이션 있음 + nodeSelector 로 learn-worker 강제
spec:
  nodeSelector: { kubernetes.io/hostname: learn-worker }
  tolerations:
    - key: step13-demo
      operator: Equal
      value: "true"
      effect: NoSchedule
```

```bash
# 1) 워커에만, 고유 키로, NoSchedule 로 테인트
kubectl taint nodes learn-worker step13-demo=true:NoSchedule
kubectl describe node learn-worker | grep -i taint

# 2) 두 파드 배치
kubectl apply -f manifests/06-toleration.yaml
kubectl wait --for=condition=Ready pod/no-tol pod/with-tol -n step13 --timeout=90s
kubectl get pod no-tol with-tol -n step13 -o wide

# 3) 실습 직후 즉시 원복 (★ 반드시)
kubectl taint nodes learn-worker step13-demo=true:NoSchedule-
kubectl describe node learn-worker | grep -i taint
```

**실행 결과**
```
node/learn-worker tainted
Taints:             step13-demo=true:NoSchedule
pod/no-tol created
pod/with-tol created
pod/no-tol condition met
pod/with-tol condition met
NAME       READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
no-tol     1/1     Running   0          1s    10.244.2.71   learn-worker2   <none>           <none>
with-tol   1/1     Running   0          1s    10.244.1.69   learn-worker    <none>           <none>
node/learn-worker untainted
Taints:             <none>
```

- `no-tol`: 톨러레이션이 없어 테인트를 못 견딤 → `learn-worker`를 피해 `learn-worker2`로.
- `with-tol`: 테인트를 견딤 → `nodeSelector`로 강제한 `learn-worker`에 정상 배치.
- 마지막 `Taints: <none>` → 테인트가 깨끗이 제거됐다.

> ⚠️ **함정 (원복 필수)**: 테인트 제거는 키 뒤에 붙이는 하이픈이다: `kubectl taint nodes learn-worker step13-demo=true:NoSchedule-`. 원복을 잊으면 이 노드는 계속 파드를 거부해 **다른 스텝 실습이 이유 없이 Pending**에 빠진다. 학습 클러스터는 여러 사람이 공유하므로 `drain`은 쓰지 말 것(다른 파드까지 쫓겨난다).

> 💡 **실무 팁**: 전용 노드(GPU 노드 등)를 만들 때 자주 쓴다. 노드에 `gpu=true:NoSchedule` 테인트 + 라벨을 걸고, GPU 워크로드에만 toleration과 nodeSelector를 줘서 "GPU 노드는 GPU 파드만"을 강제한다.

---

## 13-7. topologySpreadConstraints — 도메인에 고르게

antiAffinity가 "같은 노드 금지"라는 이분법이라면, topologySpread는 "도메인 간 편차(skew)를 N 이하로"라는 정량적 분산이다. 존/노드 단위로 고르게 퍼뜨릴 때 쓴다.

```yaml
# manifests/07-topologyspread.yaml (발췌)
spec:
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: kubernetes.io/hostname
      whenUnsatisfiable: DoNotSchedule
      nodeTaintsPolicy: Honor          # 견딜 수 없는 테인트 걸린 노드는 도메인에서 제외
      labelSelector:
        matchLabels: { app: topo }
```

```bash
kubectl apply -f manifests/07-topologyspread.yaml
kubectl wait --for=condition=Ready pod -l app=topo -n step13 --timeout=90s
kubectl get pod -n step13 -l app=topo -o wide
```

**실행 결과**
```
NAME                    READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
topo-6db5c85664-bfxmv   1/1     Running   0          4s    10.244.1.61   learn-worker    <none>           <none>
topo-6db5c85664-kl5kl   1/1     Running   0          4s    10.244.2.62   learn-worker2   <none>           <none>
topo-6db5c85664-scdkn   1/1     Running   0          4s    10.244.2.61   learn-worker2   <none>           <none>
topo-6db5c85664-smxxg   1/1     Running   0          4s    10.244.1.62   learn-worker    <none>           <none>
```

복제본 4개가 두 워커에 **2/2**로 균등 분배됐다.

> ⚠️ **함정 (실제로 겪은 것)**: `nodeTaintsPolicy: Honor`를 빼면 이 실습은 **2개가 Pending**에 빠진다. 기본값(`Ignore`)에서는 테인트가 걸려 파드가 갈 수 없는 control-plane도 "빈 도메인(0개)"으로 세기 때문이다. `maxSkew:1`이라 워커(2개)와 빈 control-plane(0개)의 편차가 1을 넘지 못하게 막아 버린다. `Honor`로 톨러레이트 못 하는 테인트 노드를 도메인 계산에서 빼면 워커 2개만 대상이 되어 2/2로 채워진다.

> 💡 **실무 팁**: `whenUnsatisfiable: DoNotSchedule`(하드)은 못 맞추면 Pending, `ScheduleAnyway`(소프트)는 "최대한 맞추되 안 되면 그냥 배치"다. 확실한 분산이 필요하면 하드, 절대 Pending은 싫으면 소프트.

---

## 13-8. 스케줄이 안 될 때 — Pending 진단

제약을 만족하는 노드가 없으면 파드는 `Pending`에 머문다. **왜인지는 `kubectl describe`의 Events에 정확히 적혀 있다.**

```yaml
# manifests/08-pending.yaml (발췌)
spec:
  nodeSelector:
    disktype: nvme-super-fast   # 이런 라벨을 가진 노드는 없다
```

```bash
kubectl apply -f manifests/08-pending.yaml
kubectl get pod unschedulable -n step13 -o wide
kubectl describe pod unschedulable -n step13 | sed -n '/Events:/,$p'
```

**실행 결과**
```
NAME            READY   STATUS    RESTARTS   AGE   IP       NODE     NOMINATED NODE   READINESS GATES
unschedulable   0/1     Pending   0          5s    <none>   <none>   <none>           <none>

Events:
  Type     Reason            Age   From               Message
  ----     ------            ----  ----               -------
  Warning  FailedScheduling  6s    default-scheduler  0/3 nodes are available: 1 node(s) had untolerated taint(s), 2 node(s) didn't match Pod's node affinity/selector. ...preemption: 0/3 nodes are available: 3 Preemption is not helpful for scheduling.
```

`0/3 nodes are available`와 그 뒤 사유("didn't match Pod's node affinity/selector", "had untolerated taint(s)")가 핵심이다. 노드별 탈락 이유가 합산되어 나온다.

> 💡 **실무 팁**: Pending을 만나면 반사적으로 `kubectl describe pod <name>`의 Events부터 본다. 사유는 대부분 셋 중 하나다 — (1) 리소스 부족(Insufficient cpu/memory, Step 09), (2) 셀렉터/어피니티 불일치, (3) 견딜 수 없는 테인트.

---

## 정리 (표)

| 도구 | 기준 | 하드/소프트 | 대표 용도 |
|---|---|---|---|
| `nodeName` | 노드 이름 직접 | 절대(우회) | 디버깅 |
| `nodeSelector` | 노드 라벨(AND) | 하드 | 단순 고정 |
| `nodeAffinity` required | 노드 라벨 표현식 | 하드 | 특정 노드군 제한 |
| `nodeAffinity` preferred | 노드 라벨 표현식 | 소프트 | 선호(안 되면 허용) |
| `podAffinity` | 다른 파드 위치 | 하드/소프트 | 같이 배치(지연↓) |
| `podAntiAffinity` | 다른 파드 위치 | 하드/소프트 | 흩어 배치(HA) |
| `taint`/`toleration` | 노드가 거부 | 하드 | 전용 노드 |
| `topologySpread` | 도메인 편차 | 하드/소프트 | 존/노드 균등 분산 |

**진단 명령**: `kubectl get pod -o wide`(배치 확인), `kubectl describe pod`(Pending 사유), `kubectl describe node <n> | grep -i taint`(테인트 확인).

## 정리 확인 — 원복

```bash
# 네임스페이스 삭제(이 스텝의 모든 파드/디플로이먼트 제거)
kubectl delete namespace step13

# 노드에 step13 테인트 흔적이 없어야 한다(control-plane 기본 테인트만 남아야 정상)
kubectl describe nodes | grep -i taint
```
기대 출력: 두 워커는 `Taints: <none>`, control-plane만 `node-role.kubernetes.io/control-plane:NoSchedule`.

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 14 — 인그레스](../step-14-ingress/)

---

## 실습 파일

이 스텝의 매니페스트는 `manifests/` 아래에 **실행 순서대로 번호가 붙어** 있습니다. `00-namespace.yaml` 로 `step13` 네임스페이스를 먼저 만든 뒤, `01` → `08` 순서대로 적용하며 각 스케줄링 도구가 파드를 어느 노드로 보내는지 `kubectl get pod -o wide` 로 관찰합니다. 마지막의 `commands.sh` 는 이 전 과정(테인트 부여와 원복, 정리까지)을 한 번에 재생하는 스크립트입니다. `06-toleration.yaml` 만은 **앞뒤로 `kubectl taint` 명령이 필요**하다는 점에 주의합니다.

### manifests/00-namespace.yaml

이 스텝의 모든 리소스가 들어갈 `step13` 네임스페이스를 만듭니다. 가장 먼저 적용해야 하며, 이후 매니페스트들은 모두 `metadata.namespace: step13` 을 하드코딩하고 있으므로 이 네임스페이스가 없으면 apply 자체가 실패합니다. 실습이 끝나면 `kubectl delete namespace step13` 한 줄로 여기 담긴 파드·디플로이먼트가 통째로 정리됩니다.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/01-nodeselector.yaml

13-1 에서 적용하는, 가장 단순한 배치 제어 예제입니다.

- `nodeSelector: { kubernetes.io/hostname: learn-worker }` 로 **`learn-worker` 노드에만** 배치되도록 못 박습니다. kind가 모든 노드에 기본으로 붙여 주는 내장 라벨을 쓰기 때문에 `kubectl label node` 로 노드를 손볼 필요가 없습니다.
- `nodeSelector` 는 적힌 라벨을 **전부(AND) 만족**하는 노드만 남기는 하드 필터라서, `learn-worker` 가 없거나 꽉 차 있으면 파드는 그대로 Pending 입니다.
- `requests: { cpu: 10m, memory: 16Mi }` 로 요청량을 아주 작게 잡아 두 워커 어디에도 리소스 때문에 못 뜨는 일이 없게 했습니다. 즉 이 실습에서 배치 결과를 좌우하는 변수는 오직 스케줄링 제약뿐입니다.
- 결과 확인은 13-3 에서 `pin-worker` / `direct-assign` / `affinity-required` 세 파드를 한꺼번에 `-o wide` 로 봅니다.

```yaml file="./manifests/01-nodeselector.yaml"
```

### manifests/02-nodename.yaml

13-2 의 "스케줄러 건너뛰기" 예제입니다.

- `nodeName: learn-worker2` 를 적으면 스케줄러가 아예 개입하지 않고, 해당 노드의 kubelet 이 파드를 곧바로 실행합니다.
- 이 경로에는 **리소스 여유 검사도, 테인트 검사도, 어피니티 판단도 없습니다.** 그래서 노드가 꽉 찼거나 테인트가 걸려 있어도 밀어붙이다 실패할 수 있습니다.
- 노드 이름을 오타로 적으면 그 파드는 아무 kubelet 도 집어가지 않아 Pending 상태로 방치됩니다(스케줄러가 사유를 남겨 주지도 않습니다).
- 실무에서는 디버깅 같은 특수한 경우가 아니면 쓰지 않는, "이렇게도 된다"를 보여 주기 위한 파일입니다.

```yaml file="./manifests/02-nodename.yaml"
```

### manifests/03-nodeaffinity.yaml

13-3 에서 하드/소프트 조건을 한 파드에 함께 걸어 봅니다.

- `requiredDuringSchedulingIgnoredDuringExecution` 의 `matchExpressions` 로 `kubernetes.io/hostname In ["learn-worker", "learn-worker2"]` 를 요구합니다. control-plane 을 제외한 **워커 두 대**로 후보를 좁히는 하드 필터입니다.
- `preferredDuringSchedulingIgnoredDuringExecution` 에 `weight: 100` 으로 `learn-worker2` 를 선호하게 두었습니다. 소프트 조건이라 worker2 가 안 되면 worker 로도 갑니다. 실행 결과에서 `affinity-required` 가 `learn-worker2` 에 뜨는 이유가 이 가중치입니다.
- `nodeSelector` 와 달리 `In`/`NotIn`/`Exists`/`Gt`/`Lt` 같은 연산자를 쓸 수 있고 OR·선호를 표현할 수 있다는 점이 핵심 차이입니다.
- 이름의 `IgnoredDuringExecution` 은 "이미 실행 중인 파드는 조건이 깨져도 축출하지 않는다"는 뜻입니다.

```yaml file="./manifests/03-nodeaffinity.yaml"
```

### manifests/04-podaffinity.yaml

13-4 용 파일로, **하나의 파일에 두 리소스**가 `---` 로 이어져 있습니다.

- 앞부분은 `app: web` 라벨을 단 `replicas: 1` 짜리 Deployment 이고, 뒷부분이 `podAffinity` 를 가진 `cache` 파드입니다.
- `cache` 는 `labelSelector: { matchLabels: { app: web } }` + `topologyKey: kubernetes.io/hostname` 으로 "**`app=web` 파드가 있는 노드와 같은 노드**"를 요구합니다. `topologyKey` 가 hostname 이므로 "같음"의 단위가 노드입니다.
- 한 파일에 담겨 있어 `kubectl apply` 는 Deployment → `cache` 순으로 **생성**하지만, 그것이 **스케줄 순서까지 보장하지는 않습니다.** `cache` 가 먼저 스케줄 대상이 되면 기준이 될 `app=web` 파드가 아직 없어 잠시 Pending 에 빠졌다가, 스케줄러의 재시도로 web 이 배치된 뒤에야 같은 노드로 따라붙습니다. 그래서 강의 본문의 명령도 `kubectl wait ... -l app=web` 으로 web 이 Ready 되기를 먼저 기다린 뒤 `cache` 를 확인합니다.
- 이 "닭이 먼저냐" 문제는 `challenge.md` 과제 5에서 일부러 재현해 봅니다.

```yaml file="./manifests/04-podaffinity.yaml"
```

### manifests/05-podantiaffinity.yaml

13-5 의 고가용성 배치 예제입니다.

- `replicas: 2` Deployment 에 `podAntiAffinity` 를 `required` 로 걸어, 같은 `app: spread-app` 라벨을 가진 파드끼리는 `topologyKey: kubernetes.io/hostname` 기준으로 **한 노드에 둘이 못 올라가게** 합니다.
- 워커가 정확히 2대이므로 복제본 2개가 `learn-worker` / `learn-worker2` 에 하나씩 나뉘어 뜹니다.
- 여기서 `replicas` 를 3 이상으로 올리면 세 번째부터는 갈 노드가 없어 **영원히 Pending** 이 됩니다(노드 하나에 둘을 못 두므로). 이것이 하드 anti-affinity 의 대표적 함정이고, `challenge.md` 과제 2에서 soft(preferred)로 완화하는 해법을 다룹니다.
- 04번의 podAffinity 와 셀렉터·topologyKey 구조가 똑같고 `podAffinity` ↔ `podAntiAffinity` 키만 뒤집힌 형태라, 둘을 나란히 비교해 보면 이해가 빠릅니다.

```yaml file="./manifests/05-podantiaffinity.yaml"
```

### manifests/06-toleration.yaml

13-6 용 파일이며, **적용 전에 반드시 테인트를 걸어야** 의미가 있습니다: `kubectl taint nodes learn-worker step13-demo=true:NoSchedule`.

- 두 파드를 대조합니다. `no-tol` 은 톨러레이션도 nodeSelector 도 없는 평범한 파드라, 테인트가 걸린 `learn-worker` 를 스케줄러가 걸러내고 `learn-worker2` 로 보냅니다.
- `with-tol` 은 `tolerations` 에 `key: step13-demo` / `operator: Equal` / `value: "true"` / `effect: NoSchedule` 을 적어 테인트를 견디고, 동시에 `nodeSelector: { kubernetes.io/hostname: learn-worker }` 로 그 노드를 강제합니다. **toleration 만으로는 그 노드로 "간다"는 보장이 없고 "갈 수 있다"만 뜻하기 때문에** nodeSelector 를 함께 쓴 것입니다(허용이지 유인이 아님).
- 테인트의 키·값·effect 가 톨러레이션과 정확히 일치해야 견딜 수 있습니다. 하나라도 어긋나면 `with-tol` 은 nodeSelector 때문에 갈 곳이 없어 Pending 이 됩니다.
- ⚠️ **원복 필수**: 실습 직후 `kubectl taint nodes learn-worker step13-demo=true:NoSchedule-` (끝의 하이픈)로 반드시 테인트를 제거합니다. 잊으면 이후 다른 스텝의 파드들이 이유 없이 이 노드를 피하거나 Pending 에 빠집니다.

```yaml file="./manifests/06-toleration.yaml"
```

### manifests/07-topologyspread.yaml

13-7 의 정량적 분산 예제입니다.

- `replicas: 4` 를 `maxSkew: 1` + `topologyKey: kubernetes.io/hostname` + `whenUnsatisfiable: DoNotSchedule` 로 배치해, 노드별 파드 개수 차이가 1을 넘지 않게 합니다. 결과는 두 워커에 2/2 균등 분배입니다.
- 학습 포인트는 `nodeTaintsPolicy: Honor` 입니다. 이 줄을 빼면(기본값 `Ignore`) **파드 2개가 Pending 에 빠집니다.** 톨러레이션이 없어 갈 수도 없는 control-plane 노드까지 "0개짜리 빈 도메인"으로 세어 버려서, 워커에 2개가 차는 순간 `2 - 0 = 2 > maxSkew(1)` 이 되어 나머지를 막기 때문입니다.
- `Honor` 를 주면 견딜 수 없는 테인트가 걸린 노드는 도메인 계산에서 제외되어, 워커 2대만 대상이 되고 4개가 2/2 로 깔끔히 들어갑니다.
- `whenUnsatisfiable` 을 `ScheduleAnyway` 로 바꾸면 소프트가 되어 못 맞춰도 Pending 없이 배치됩니다(`challenge.md` 과제 4).

```yaml file="./manifests/07-topologyspread.yaml"
```

### manifests/08-pending.yaml

13-8 의 진단 실습을 위해 **일부러 스케줄이 안 되게 만든** 파드입니다.

- `nodeSelector: { disktype: nvme-super-fast }` 는 클러스터의 어떤 노드도 가지고 있지 않은 라벨입니다. 따라서 필터링 단계에서 모든 노드가 탈락하고, 파드는 `Pending` 에 영원히 머뭅니다(이미지 문제도, 리소스 문제도 아닙니다).
- 적용 후 `kubectl describe pod unschedulable -n step13` 의 Events 에서 `0/3 nodes are available: 1 node(s) had untolerated taint(s), 2 node(s) didn't match Pod's node affinity/selector` 를 읽는 것이 이 파일의 목적입니다. 워커 2대는 라벨 불일치로, control-plane 1대는 기본 테인트로 탈락했음을 사유별로 합산해 보여 줍니다.
- 삭제하지 않으면 계속 Pending 으로 남지만, 정리 단계의 `kubectl delete namespace step13` 으로 함께 사라지므로 별도 조치는 필요 없습니다.

```yaml file="./manifests/08-pending.yaml"
```

### commands.sh

13-1 부터 13-8 까지, 그리고 정리까지 전 과정을 순서대로 재생하는 실행 스크립트입니다.

- 맨 앞에서 `kubectl config current-context` 로 컨텍스트를 확인합니다(`kind-learn` 이어야 합니다). `set -euo pipefail` 과 `cd "$(dirname "$0")"` 덕분에 어느 위치에서 실행하든 `manifests/` 상대경로가 맞고, 중간에 하나라도 실패하면 즉시 중단됩니다.
- 각 단계마다 `kubectl apply` → `kubectl wait --for=condition=Ready ... --timeout=90s` → `kubectl get pod -o wide` 순으로 진행하므로, 파드가 Ready 되기를 기다린 뒤 배치된 노드를 확인하는 흐름이 그대로 담겨 있습니다.
- 13-6 구간은 `kubectl taint nodes learn-worker step13-demo=true:NoSchedule` 로 테인트를 걸고 실습한 뒤, 같은 블록 안에서 **하이픈으로 끝나는 `...:NoSchedule-`** 명령으로 즉시 원복합니다. 다만 `set -e` 상태에서 중간에 실패하면 원복 줄에 도달하지 못하므로, 스크립트가 도중에 죽었다면 **테인트가 남아 있는지 직접 확인**해야 합니다.
- ⚠️ 마지막 줄들은 파괴적입니다. `kubectl delete namespace step13` 으로 이 스텝의 모든 리소스를 지우고, `kubectl describe nodes | grep -i taint` 로 워커에 테인트 흔적이 남지 않았는지 검증합니다. 중간 상태를 천천히 관찰하고 싶다면 스크립트를 통째로 돌리지 말고 블록별로 나눠 실행하는 편이 좋습니다.

```bash file="./commands.sh"
```
