# Kubernetes 완전 학습 코스

기본부터 고급까지, **25개 스텝**으로 Kubernetes를 처음부터 끝까지 익힙니다.
모든 매니페스트는 실제로 돌아가는 Kubernetes 클러스터(kind, v1.36)에서 검증했고, **교재의 출력은 여러분 화면과 거의 동일합니다.**

---

## 시작하기 (5분)

```bash
# 1. 도구 설치 (macOS 기준)
brew install kind kubectl helm

# 2. 학습용 클러스터 생성 (control-plane 1 + worker 2)
cd k8s/cluster
./create-cluster.sh

#   ⚠️ 사내망에서 TLS 가로채기 프록시를 쓴다면 (이미지 pull 이 인증서 오류로 실패):
#   INJECT_CA=1 ./create-cluster.sh

# 3. 확인
kubectl get nodes
```

**결과**
```
NAME                  STATUS   ROLES           AGE   VERSION
learn-control-plane   Ready    control-plane   1m    v1.36.1
learn-worker          Ready    <none>          1m    v1.36.1
learn-worker2         Ready    <none>          1m    v1.36.1
```

언제든 처음부터 다시 시작하려면:
```bash
cd k8s/cluster && ./delete-cluster.sh && ./create-cluster.sh
```

> **왜 kind인가?** 노트북 위 Docker 컨테이너를 노드로 쓰는 가장 가볍고 빠른 학습용 클러스터입니다. 만들고 부수는 데 1분이면 됩니다. minikube, k3d, Docker Desktop 내장 k8s로도 대부분의 실습이 가능하지만, 멀티노드 실습(Step 13, 21)은 kind가 가장 편합니다.

---

## 커리큘럼

### 1부 — 기초: 핵심 오브젝트 (Step 01~07)
> kubectl을 처음 써봅니다. 여기서 시작합니다.

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [01](step-01-setup/) | 클러스터 구축과 kubectl | kind, `kubectl` 기본, 컨텍스트, 선언형 vs 명령형 |
| [02](step-02-architecture/) | 아키텍처와 동작 원리 | control plane, kubelet, etcd, 스케줄러, 조정 루프 |
| [03](step-03-pods/) | 파드 | Pod, 컨테이너, `logs/exec/describe`, 멀티 컨테이너, init |
| [04](step-04-labels-namespaces/) | 레이블·셀렉터·네임스페이스 | label, selector, annotation, namespace 격리 |
| [05](step-05-deployments/) | 디플로이먼트 | ReplicaSet, Deployment, 롤링 업데이트, 롤백 |
| [06](step-06-services/) | 서비스와 DNS | ClusterIP, NodePort, LoadBalancer, Headless, 서비스 디스커버리 |
| [07](step-07-config-secret/) | 설정과 시크릿 | ConfigMap, Secret, env, 볼륨 마운트, 불변 설정 |

### 2부 — 워크로드와 스토리지 (Step 08~13)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [08](step-08-health-probes/) | 헬스 체크 | liveness/readiness/startup 프로브, 재시작 정책 |
| [09](step-09-resources/) | 리소스 관리 | requests/limits, QoS, LimitRange, ResourceQuota, OOMKilled |
| [10](step-10-storage/) | 스토리지 | Volume, PV, PVC, StorageClass, 동적 프로비저닝 |
| [11](step-11-statefulset/) | 스테이트풀셋 | StatefulSet, 안정적 네트워크 ID, volumeClaimTemplates |
| [12](step-12-jobs-daemonset/) | 잡·데몬셋 | Job, CronJob, DaemonSet, 배치 워크로드 |
| [13](step-13-scheduling/) | 스케줄링 제어 | nodeSelector, affinity, taint/toleration, topology spread |

### 3부 — 네트워킹과 접근 제어 (Step 14~16)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [14](step-14-ingress/) | 인그레스 | Ingress 컨트롤러(nginx), 호스트/경로 라우팅, TLS |
| [15](step-15-network-policy/) | 네트워크 정책 | NetworkPolicy, 기본 차단, 화이트리스트 격리 |
| [16](step-16-rbac/) | RBAC와 인증 | ServiceAccount, Role/ClusterRole, 바인딩, 최소 권한 |

### 4부 — 운영과 고급 (Step 17~25)

| Step | 주제 | 핵심 내용 |
|---|---|---|
| [17](step-17-helm/) | Helm | 차트 구조, values, 템플릿, 릴리스, 업그레이드/롤백 |
| [18](step-18-autoscaling/) | 오토스케일링 | HPA, metrics-server, VPA·Cluster Autoscaler 개념 |
| [19](step-19-observability/) | 관측성 | events, `kubectl top`, 로깅, 메트릭, 디버그 컨테이너 |
| [20](step-20-security/) | 보안 | SecurityContext, Pod Security Standards, 이미지 보안 |
| [21](step-21-disruptions/) | 가용성과 중단 관리 | PodDisruptionBudget, PriorityClass, cordon/drain |
| [22](step-22-crd-operators/) | CRD와 오퍼레이터 | CustomResourceDefinition, 커스텀 리소스, 오퍼레이터 패턴 |
| [23](step-23-kustomize-gitops/) | Kustomize와 GitOps | Kustomize overlay, 환경별 구성, GitOps(ArgoCD) 개념 |
| [24](step-24-troubleshooting/) | 트러블슈팅 | CrashLoopBackOff·ImagePullBackOff·Pending·OOM 진단 플레이북 |
| [25](step-25-final-project/) | 종합 실습 | 3-tier 마이크로서비스 배포 + 확장 + 무중단 운영 |

---

## 각 스텝의 구성

```
step-05-deployments/
├── index.md          ← 교재 본문. 개념 + 매니페스트 + 실제 실행 결과 + 함정/팁
├── manifests/        ← 실습용 YAML 파일들
│   ├── deployment.yaml
│   └── ...
├── commands.sh       ← 교재의 kubectl 명령을 순서대로 담은 실행 스크립트
└── challenge.md      ← 연습 과제 + 해답
```

**권장 학습 방법**

1. `index.md`를 읽으며 매니페스트를 **직접 `kubectl apply`** 하고 결과를 관찰합니다.
2. `kubectl get`, `describe`, `logs`로 무슨 일이 일어났는지 확인합니다.
3. 결과가 교재와 다르면 멈추고 원인을 찾으세요.
4. `challenge.md`의 과제를 풀고 다음 스텝으로.

각 스텝은 **자기만의 네임스페이스**(`step05` 등)를 씁니다. 다른 스텝과 섞이지 않고, 끝나면 네임스페이스 하나만 지우면 깨끗이 정리됩니다.

```bash
kubectl delete namespace step05
```

---

## 학습에 쓰는 이미지

인터넷/사내망 어디서든 잘 받아지는 **멀티아키텍처(amd64·arm64)** 이미지만 씁니다.

| 이미지 | 용도 |
|---|---|
| `nginx:1.27-alpine` | 웹 서버 (대부분의 실습) |
| `registry.k8s.io/e2e-test-images/agnhost:2.47` | k8s 공식 테스트 도구 (netexec, serve-hostname 등) |
| `hashicorp/http-echo:1.0` | 지정 문자열을 응답하는 초경량 웹 |
| `paulbouwer/hello-kubernetes:1.10.1` | 파드 이름/노드를 보여주는 데모 웹 |
| `redis:7-alpine` | 인메모리 저장소 (StatefulSet, 헬스체크) |
| `postgres:16-alpine` | 관계형 DB (스토리지, StatefulSet) |
| `busybox:1.36` | 디버깅/테스트용 셸 |

---

## 실습 규칙

- 실습은 각 스텝의 **전용 네임스페이스**에서 진행합니다. 다른 네임스페이스(특히 `kube-system`)를 건드리지 마세요.
- 클러스터 전체에 영향을 주는 실습(노드 taint, cordon/drain)은 반드시 **원복**합니다. 각 스텝 교재에 원복 명령이 있습니다.
- 꼬였다면 언제든 클러스터를 재생성하세요. 학습 클러스터는 **부수라고 있는 것**입니다.
  ```bash
  cd k8s/cluster && ./delete-cluster.sh && ./create-cluster.sh
  ```

---

## 이 코스가 특히 신경 쓴 것

**"YAML은 apply됐는데 왜 안 되는가"** 를 진단하는 힘을 기르는 데 집중했습니다. Kubernetes는 명령이 성공해도 원하는 상태가 아닐 수 있습니다. 예를 들면:

- 프로브를 잘못 걸면 파드가 **무한 재시작(CrashLoopBackOff)** 합니다 (Step 08, 24)
- `requests`를 안 주면 스케줄러가 노드를 잘못 골라 **Pending**에 빠집니다 (Step 09, 13)
- Service의 셀렉터가 파드 레이블과 **한 글자만 달라도** 트래픽이 0이 됩니다 (Step 06)
- `emptyDir`에 DB를 두면 파드 재시작 시 **데이터가 증발**합니다 (Step 10)
- 메모리 `limit`을 넘기면 **OOMKilled**로 조용히 죽습니다 (Step 09, 24)

각 스텝의 `⚠️ 함정`과 [Step 24 트러블슈팅](step-24-troubleshooting/)을 특히 눈여겨 보세요.

---

## 환경 정보

| 항목 | 값 |
|---|---|
| Kubernetes | v1.36 (검증: kind v0.32, k8s v1.36.1) |
| 클러스터 | kind — control-plane 1 + worker 2 |
| 컨텍스트 | `kind-learn` |
| 도구 | kubectl v1.36, helm v4 |
| 이미지 | 멀티아키텍처(amd64/arm64) 공개 이미지만 사용 |
