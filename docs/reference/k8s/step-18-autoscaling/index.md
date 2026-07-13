# Step 18 — 오토스케일링: metrics-server와 HPA

## 학습 목표
- 오토스케일링의 세 축(HPA·VPA·Cluster Autoscaler)이 각각 무엇을 조절하는지 구분한다.
- **metrics-server**를 kind 클러스터에 설치하고 `kubectl top`이 동작하게 만든다.
- HPA(`autoscaling/v2`)를 CPU 기준으로 걸고, **실제 부하로 replica가 1→6으로 늘고 다시 주는 것**을 관찰한다.
- `behavior`(scaleUp/scaleDown 정책, 안정화 윈도우)로 스케일링 속도를 제어한다.

## 선행 지식
- Step 05(Deployment), Step 09(resources requests/limits). HPA는 **request 대비 사용률**로 판단하므로 requests가 없으면 CPU HPA가 동작하지 않습니다.

## 소요 시간
- 40분 (부하 후 scaleDown 관찰까지 하면 +10분)

---

## 1. 오토스케일링의 세 축

| 종류 | 무엇을 조절 | 이 스텝에서 |
|---|---|---|
| **HPA** (Horizontal Pod Autoscaler) | **파드 개수**(replica) | 실습 (CPU 기반) |
| **VPA** (Vertical Pod Autoscaler) | 파드의 **requests/limits** 크기 | 개념만 (kind 미설치) |
| **Cluster Autoscaler** | **노드 개수** | 개념만 (kind는 노드 고정) |

HPA는 "손님이 많으면 계산대를 늘린다", VPA는 "계산대 한 대의 처리 능력을 키운다", Cluster Autoscaler는 "매장(노드) 자체를 늘린다"에 해당합니다. 가장 흔히 쓰는 것이 HPA입니다.

---

## 2. metrics-server 설치 (공용 인프라)

HPA도 `kubectl top`도 **메트릭 파이프라인**이 있어야 동작합니다. 그 표준 구현이 metrics-server입니다. kube-system에 한 번 설치하면 클러스터 전체가 씁니다(Step 19에서도 사용).

### kind 특수사항 — `--kubelet-insecure-tls`

metrics-server는 각 노드의 kubelet에 HTTPS로 접속해 메트릭을 긁습니다. kind의 kubelet은 자체 서명(self-signed) 인증서를 쓰는데, metrics-server 기본 설정은 이를 검증하려다 실패합니다. 그래서 kind에서는 `--kubelet-insecure-tls` 인자를 추가해야 합니다. (학습용 클러스터에서만. 운영에서는 제대로 된 인증서를 쓰세요.)

```bash
# 1) 공식 매니페스트를 호스트에서 받는다 (사내망에서 호스트는 pull/다운로드 가능)
curl -sL https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml -o /tmp/ms.yaml

# 2) Deployment args 에 --kubelet-insecure-tls 를 추가 (아래 한 줄을 --metric-resolution 아래에 삽입)
#      - --kubelet-insecure-tls

# 3) 이미지가 사내망에서 노드로 안 받아지면: 호스트에서 pull 후 kind 로 주입
docker pull registry.k8s.io/metrics-server/metrics-server:v0.8.1
docker save registry.k8s.io/metrics-server/metrics-server:v0.8.1 -o /tmp/ms-img.tar
for n in learn-control-plane learn-worker learn-worker2; do
  docker exec -i $n ctr --namespace=k8s.io images import - < /tmp/ms-img.tar
done

# 4) 적용
kubectl apply -f /tmp/ms.yaml
kubectl -n kube-system rollout status deploy/metrics-server
```

> 이 스텝의 `manifests/metrics-server.yaml`이 바로 `--kubelet-insecure-tls`를 넣은 완성본입니다(전문은 아래 [실습 파일](#실습-파일) 섹션에 있습니다). 위 과정을 건너뛰고 `kubectl apply -f manifests/metrics-server.yaml` 해도 됩니다(이미지가 노드에 있어야 함).
>
> **왜 `kind load` 대신 `ctr images import`인가**: 호스트에서 받은 이미지가 단일 아키텍처면 `kind load docker-image`가 `--all-platforms` 때문에 "content digest not found"로 실패합니다. `docker save` → 각 노드에서 `ctr images import`가 확실합니다.

### 동작 확인 — `kubectl top`

설치 후 20~30초 지나 첫 스크레이프가 쌓이면:

```bash
kubectl top nodes
```
**실제 출력**
```
NAME                  CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
learn-control-plane   156m         1%       1278Mi          8%
learn-worker          134m         1%       1337Mi          8%
learn-worker2         128m         1%       1549Mi          9%
```

```bash
kubectl get apiservices v1beta1.metrics.k8s.io
```
```
NAME                     SERVICE                      AVAILABLE   AGE
v1beta1.metrics.k8s.io   kube-system/metrics-server   True        46s
```

`AVAILABLE True`가 핵심입니다. `False (MissingEndpoints)`면 metrics-server 파드가 아직 안 떴거나 TLS 문제입니다.

---

## 3. 실습 워크로드

HPA는 **request 대비 사용률**로 판단합니다. request를 작게(`20m`) 주면 약간의 부하로도 목표치를 넘겨 스케일업을 쉽게 관찰할 수 있습니다(`manifests/workload.yaml`, 전문은 아래 [실습 파일](#실습-파일) 섹션 참고).

```yaml
resources:
  requests:
    cpu: 20m       # HPA 계산의 기준값 (이게 없으면 CPU HPA 불가)
    memory: 24Mi
  limits:
    cpu: 200m
```

```bash
kubectl apply -f manifests/namespace.yaml
kubectl apply -f manifests/workload.yaml
```

---

## 4. HPA 정의 (autoscaling/v2)

`manifests/hpa.yaml`의 핵심 부분입니다(전문은 아래 [실습 파일](#실습-파일) 섹션 참고).

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    kind: Deployment
    name: web
  minReplicas: 1
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50   # request(20m)의 50% = 평균 10m 초과 시 스케일업
```

> `autoscaling/v1`은 CPU 하나만, 필드도 빈약합니다. **항상 `autoscaling/v2`를 쓰세요.** 메모리·커스텀·복수 메트릭과 `behavior`를 지원합니다.

계산식: `원하는 replica = ceil(현재 replica × 현재 사용률 / 목표 사용률)`. 1개가 360%를 쓰면 `ceil(1 × 360/50) = 8` → maxReplicas 6으로 제한 → 6.

```bash
kubectl apply -f manifests/hpa.yaml
kubectl get hpa -n step18
```
```
NAME   REFERENCE        TARGETS        MINPODS   MAXPODS   REPLICAS   AGE
web    Deployment/web   cpu: 15%/50%   1         6         1          20s
```

`cpu: 15%/50%` = 현재 사용률 15%, 목표 50%. 아직 여유가 있어 replica는 1.

> **함정**: 방금 걸면 `TARGETS`가 `<unknown>/50%`로 뜹니다. metrics-server가 파드 메트릭을 처음 수집하기까지 15~30초 걸립니다. 기다리세요. 계속 `<unknown>`이면 파드에 `requests.cpu`가 없는 것입니다.

---

## 5. 부하를 걸어 스케일업 실증

nginx 정적 서빙은 요청당 CPU가 작으므로, busybox 파드 3개가 각각 10개의 무한 wget 루프를 돌려 RPS를 높입니다(`manifests/load-generator.yaml`).

```bash
kubectl apply -f manifests/load-generator.yaml
# 20초 간격으로 관찰
watch -n 20 'kubectl get hpa web -n step18; kubectl top pods -n step18 -l app=web'
```

**실제 관찰 결과** (부하 투입 후):
```
=== t=20s ===  web   cpu: 0%/50%     1  6  1
=== t=40s ===  web   cpu: 360%/50%   1  6  1     web-...-6bznr  72m
=== t=60s ===  web   cpu: 343%/50%   1  6  6     (파드 6개로 스케일업)
   web-...-6bznr 67m  web-...-h5tzs 71m  web-...-kpjjb 73m
   web-...-ksnvr 64m  web-...-pt287 68m  web-...-rcf2n 63m
=== t=80s ===  web   cpu: 270%/50%   1  6  6     (파드당 부하 분산되어 하락)
```

CPU가 360%까지 치솟자 HPA가 1→5→6으로 늘렸고, 부하가 6개에 분산되며 파드당 사용률이 내려갔습니다.

`kubectl describe hpa`의 이벤트로 왜/언제 늘었는지 확인:
```bash
kubectl describe hpa web -n step18 | sed -n '/Events:/,$p'
```
```
Type    Reason             Age    Message
Normal  SuccessfulRescale  2m7s   New size: 5; reason: cpu resource utilization ... above target
Normal  SuccessfulRescale  112s   New size: 6; reason: cpu resource utilization ... above target
```

---

## 6. scaleUp / scaleDown behavior

기본 HPA는 급격히 늘리고 **아주 천천히**(기본 5분 안정화) 줄입니다. `behavior`로 이를 제어합니다(`hpa.yaml`):

```yaml
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0      # 즉시 대응
      policies:
        - type: Percent
          value: 100                     # 15초마다 최대 2배
          periodSeconds: 15
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 60     # 60초간 최댓값 유지 후
      policies:
        - type: Pods
          value: 1                       # 60초마다 1개씩만 축소 (플래핑 방지)
          periodSeconds: 60
```

**부하 제거 후 실제 축소 관찰**:
```bash
kubectl delete -f manifests/load-generator.yaml
```
```
CPU 0%/50% 도달 후:
6 → 5 (New size: 5; reason: All metrics below target)
5 → 4 (60초 뒤)
4 → 3 (다시 60초 뒤)
...정책대로 60초에 1개씩 감소
```

축소가 즉각적이지 않은 이유: 순간적으로 부하가 빠졌다고 바로 줄이면, 부하가 다시 오를 때 또 늘리는 **플래핑(flapping)**이 생깁니다. `stabilizationWindowSeconds`는 이를 막는 완충 장치입니다.

---

## 7. VPA·Cluster Autoscaler (개념)

- **VPA**: 파드의 실제 사용량을 관찰해 `requests`를 자동 조정합니다. 파드를 재생성해야 반영되므로 HPA(CPU)와 **동시에 같은 메트릭으로 쓰면 충돌**합니다. 보통 VPA는 메모리, HPA는 CPU로 나누거나 VPA를 추천(Off) 모드로 씁니다. kind에는 미설치.
- **Cluster Autoscaler**: 파드가 `Pending`(노드 자원 부족)이면 클라우드 노드풀을 늘리고, 노드가 놀면 줄입니다. kind는 노드가 Docker 컨테이너로 고정되어 있어 동작하지 않습니다. EKS/GKE 등 클라우드에서만.

---

## 팁과 함정

- **함정 1 — requests 없으면 CPU HPA는 죽은 상태**: `averageUtilization`은 request 대비 비율입니다. `requests.cpu`가 없으면 HPA는 `TARGETS`를 `<unknown>`으로 남기고 아무것도 못 합니다.
- **함정 2 — metrics-server가 안 뜨는 3대 원인**: (1) kind인데 `--kubelet-insecure-tls` 누락, (2) 이미지가 노드에 없음, (3) apiservice가 `AVAILABLE False`. `kubectl get apiservices v1beta1.metrics.k8s.io`와 `kubectl -n kube-system logs deploy/metrics-server`로 확인.
- **함정 3 — 축소가 느리다고 놀라지 말 것**: 기본 scaleDown 안정화가 300초입니다. behavior를 안 건드리면 부하가 빠져도 5분간 replica가 안 줄어듭니다. 버그가 아닙니다.
- **팁 — `kubectl top`은 순간값**: metrics-server는 15초마다 스크레이프한 값을 보여줍니다. 부하가 막 빠져도 직전 샘플이 남아 몇 초간 높게 보일 수 있습니다(위 관찰에서 CPU가 261%로 잠깐 튄 이유).
- **팁 — HPA와 replicas 필드**: HPA가 관리하는 Deployment의 `spec.replicas`를 `kubectl scale`이나 매니페스트로 직접 바꾸지 마세요. HPA와 싸웁니다. HPA가 붙으면 개수는 HPA에게 맡깁니다.

---

## 정리표

| 명령/필드 | 뜻 |
|---|---|
| `kubectl top nodes` / `top pods` | 노드/파드 실시간 CPU·메모리 (metrics-server 필요) |
| `autoscaling/v2` | HPA API 버전. 항상 이걸 사용 |
| `averageUtilization: 50` | request의 50% 초과 시 스케일업 |
| `minReplicas` / `maxReplicas` | 스케일 범위 하한/상한 |
| `behavior.scaleUp/scaleDown` | 스케일 속도·안정화 제어 |
| `stabilizationWindowSeconds` | 이 시간 동안 최댓값 유지 후 결정(플래핑 방지) |
| `kubectl describe hpa` | 스케일 결정 이유가 Events에 남음 |

---

## 연습 과제

[challenge.md](challenge.md)의 4개 과제.

---

## 다음 단계

→ [Step 19 — 관측성](../step-19-observability/index.md): `kubectl top`·events·logs·describe·debug로 클러스터에서 무슨 일이 일어나는지 읽어내는 법을 익힙니다. metrics-server는 계속 씁니다.

---

## 실습 파일

이 스텝의 파일들은 **"메트릭 파이프라인을 깔고 → 워크로드를 띄우고 → HPA를 걸고 → 부하를 넣어 스케일업/다운을 관찰한다"**는 하나의 흐름을 그대로 따라갑니다. 먼저 `manifests/metrics-server.yaml`을 `kube-system`에 한 번 설치해 `kubectl top`을 살린 뒤, `namespace.yaml` → `workload.yaml` → `hpa.yaml` 순으로 적용하고, 마지막에 `load-generator.yaml`을 넣었다 빼면서 replica가 1→6→1로 움직이는 것을 봅니다. 이 전 과정을 자동으로 수행하는 것이 `commands.sh`입니다.

### manifests/metrics-server.yaml

metrics-server 공식 `components.yaml`에 **kind용 수정 한 줄을 미리 반영해 둔 완성본**입니다. 본문 2절에서 "이 과정을 건너뛰고 바로 apply해도 된다"고 한 파일이 이것입니다.

- 핵심은 Deployment의 args에 있는 `--kubelet-insecure-tls` 한 줄입니다. kind 노드의 kubelet은 자체 서명 인증서를 쓰기 때문에 이 인자가 없으면 metrics-server가 스크레이프에 실패하고 `kubectl top`이 영원히 에러를 냅니다.
- `--metric-resolution=15s`는 15초마다 각 kubelet에서 메트릭을 긁어온다는 뜻입니다. 그래서 설치 직후 `kubectl top`이나 HPA `TARGETS`가 값을 보여주기까지 15~30초가 필요합니다(본문의 `<unknown>` 함정).
- `--kubelet-preferred-address-types=InternalIP,...`와 `--kubelet-use-node-status-port`는 노드 주소/포트를 어떻게 찾을지 정하는 설정으로, kind처럼 DNS 이름이 안 풀리는 환경에서 InternalIP를 먼저 시도하게 해 줍니다.
- 맨 아래 `APIService v1beta1.metrics.k8s.io`가 **집계 API 등록**입니다. `kubectl get apiservices v1beta1.metrics.k8s.io`가 `AVAILABLE True`가 되어야 HPA가 메트릭을 읽을 수 있습니다.
- 이미지는 `registry.k8s.io/metrics-server/metrics-server:v0.8.1`이고 `imagePullPolicy: IfNotPresent`이므로, 폐쇄망이면 본문 2절의 `docker save` → `ctr images import`로 노드에 이미지를 먼저 넣어두어야 합니다.
- **주의**: 이 리소스들은 `kube-system`에 설치되는 **공용 인프라**입니다. 실습이 끝나도 삭제하지 마세요. Step 19에서 그대로 씁니다.

```yaml file="./manifests/metrics-server.yaml"
```

### manifests/namespace.yaml

실습 리소스를 담을 `step18` 네임스페이스를 만듭니다. `course: k8s-learn`, `step: "18"` 라벨이 붙어 있어 나중에 라벨로 골라내기 쉽습니다. 정리할 때 `kubectl delete ns step18` 한 줄이면 워크로드·서비스·HPA·부하 생성기가 모두 함께 사라지지만, `kube-system`에 있는 metrics-server는 영향을 받지 않습니다. 가장 먼저 적용해야 하는 파일입니다.

```yaml file="./manifests/namespace.yaml"
```

### manifests/workload.yaml

HPA가 조절할 대상 워크로드입니다(본문 3절). nginx Deployment 1개와 이를 가리키는 Service `web`으로 구성되어 있습니다.

- 가장 중요한 줄은 `requests.cpu: 20m`입니다. HPA의 `averageUtilization`은 **request 대비 비율**이므로, request가 없으면 CPU HPA는 아무것도 계산하지 못하고 `TARGETS`가 `<unknown>`에 머뭅니다.
- request를 일부러 아주 작게(`20m`) 잡은 것이 실습의 트릭입니다. 목표 50%면 파드당 평균 `10m`만 넘겨도 스케일업이 시작되므로, 노트북 kind 클러스터에서도 replica가 늘어나는 것을 눈으로 볼 수 있습니다.
- `limits.cpu: 200m`은 파드 하나가 쓸 수 있는 CPU 상한입니다(메모리는 `requests.memory: 24Mi` / `limits.memory: 64Mi`). CPU limit이 request(20m) 대비 10배이므로 사용률이 최대 1000%까지 찍힐 수 있고, 실제 관찰에서 `360%`가 나온 이유가 여기에 있습니다.
- Service `web`은 포트 80을 그대로 파드로 넘깁니다. 부하 생성기가 `http://web.step18.svc.cluster.local/`로 때리는 대상이 바로 이 Service이고, replica가 늘면 요청이 자동으로 분산됩니다.
- `spec.replicas: 1`로 시작하지만, HPA를 건 뒤에는 이 값을 손으로 바꾸지 마세요(본문 팁 참고 — HPA와 싸웁니다).

```yaml file="./manifests/workload.yaml"
```

### manifests/hpa.yaml

본문 4절과 6절의 주인공입니다. `autoscaling/v2` HPA로 `Deployment/web`을 CPU 기준으로 1~6개 사이에서 조절합니다.

- `scaleTargetRef`가 `apps/v1 Deployment web`을 가리키므로 `workload.yaml`을 **먼저** 적용해야 합니다.
- `averageUtilization: 50` — request가 `20m`이니 파드당 평균 `10m`를 넘으면 스케일업입니다. `minReplicas: 1`, `maxReplicas: 6`이 상·하한이라, 계산식이 8을 내놓아도 6에서 잘립니다.
- `behavior.scaleUp`은 **공격적**입니다. `stabilizationWindowSeconds: 0`이라 지연 없이 즉시 반응하고, `Percent 100 / 15s`(15초마다 2배)와 `Pods 4 / 15s`(15초마다 4개) 두 정책을 `selectPolicy: Max`로 묶어 **둘 중 더 큰 쪽**을 택합니다. 1개에서 시작하면 Percent는 +1, Pods는 +4를 허용하므로 4개 쪽이 이겨 단숨에 커집니다.
- `behavior.scaleDown`은 **보수적**입니다. `stabilizationWindowSeconds: 60`으로 60초 동안 관측된 최댓값을 기준으로 삼아 성급한 축소를 막고, `Pods 1 / 60s`로 60초에 1개씩만 줄입니다. 6개에서 1개까지 내려오는 데 수 분이 걸리는 것이 정상입니다(플래핑 방지).
- `behavior`를 아예 안 쓰면 기본 scaleDown 안정화가 **300초**라 5분간 replica가 그대로입니다. 버그가 아닙니다.

```yaml file="./manifests/hpa.yaml"
```

### manifests/load-generator.yaml

스케일업을 실증하기 위한 부하 생성기입니다(본문 5절). 적용 후 20~40초면 HPA의 `TARGETS`가 치솟기 시작합니다.

- busybox 파드 `replicas: 3`이 각각 `while [ $i -lt 10 ]` 루프로 **10개의 무한 wget 서브셸**을 백그라운드(`&`)로 띄우므로 총 30개의 요청 루프가 동시에 돕니다. 마지막 `wait`가 있어야 메인 셸이 끝나지 않고 컨테이너가 살아 있습니다.
- 요청 대상은 `http://web.step18.svc.cluster.local/` — 같은 네임스페이스의 Service DNS입니다. `web` Service가 먼저 떠 있어야 하므로 `workload.yaml` 다음에 적용합니다.
- nginx 정적 페이지는 요청당 CPU가 워낙 작아서 한두 개 루프로는 사용률이 안 오릅니다. 병렬 루프 10개 × 파드 3개라는 물량이 필요한 이유입니다.
- 부하를 끄는 방법은 `kubectl delete -f manifests/load-generator.yaml`입니다. 이걸 지운 뒤부터 6절의 scaleDown 관찰이 시작됩니다.
- **주의**: 이 파드들은 CPU를 계속 태웁니다. 관찰이 끝나면 반드시 삭제하세요. 방치하면 노트북 팬이 계속 돌아갑니다.

```yaml file="./manifests/load-generator.yaml"
```

### commands.sh

위 매니페스트들을 **본문 2~6절 순서 그대로** 자동 실행하는 스크립트입니다. 처음 볼 때는 손으로 한 줄씩 따라 하고, 두 번째부터 이 스크립트로 재현하는 것을 권합니다.

- `set -euo pipefail`로 중간에 하나라도 실패하면 즉시 멈추고, `HERE="$(cd "$(dirname "$0")" && pwd)"`로 스크립트 위치를 잡아 `"$HERE/manifests/..."`를 적용하므로 **어느 디렉터리에서 실행해도** 동작합니다.
- 맨 위의 `export PATH="/opt/homebrew/bin:$PATH"`는 Homebrew로 깐 `kubectl`을 찾기 위한 **macOS(Apple Silicon) 전용** 줄입니다. Linux나 다른 경로에 `kubectl`이 있다면 이 줄은 없어도 그만이며, 지워도 스크립트는 정상 동작합니다.
- 맨 앞의 `if ! kubectl get deploy metrics-server -n kube-system`는 **멱등성** 장치입니다. metrics-server가 이미 있으면 설치를 건너뛰므로 스크립트를 여러 번 돌려도 안전합니다.
- 설치 후 `sleep 20`을 두고 `kubectl top nodes`를 부르는 것은 첫 스크레이프(15초 주기)를 기다리기 위함이고, HPA 적용 후의 `sleep 25`도 같은 이유로 `TARGETS`가 `<unknown>`이 아닌 값을 갖게 하려는 것입니다.
- 부하 투입 후 `for i in $(seq 1 8)` 루프가 20초 간격으로 8회, 즉 약 **2분 40초** 동안 HPA와 `kubectl top pods`를 함께 찍습니다. 이 출력이 본문 5절의 `t=20s / 40s / 60s` 표와 대응합니다. 이어서 `kubectl describe hpa ... | sed -n '/Events:/,$p'`로 스케일 결정 이유를 뽑습니다.
- 부하를 지운 뒤의 두 번째 루프(30초 × 6회)에서 replica가 60초에 1개씩 줄어드는 것을 확인합니다.
- **주의**: 스크립트 끝의 마지막 `kubectl` 명령이 `kubectl delete namespace step18`이라 **스크립트가 끝까지 돌면 실습 리소스가 전부 지워집니다.** 결과를 더 들여다보고 싶으면 그 전에 Ctrl-C 하세요. 반대로 metrics-server는 의도적으로 남겨둡니다(Step 19에서 사용).

```bash file="./commands.sh"
```
