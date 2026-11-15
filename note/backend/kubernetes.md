# Kubernetes

Node.js 서버를 Kubernetes(쿠버네티스)로 배포하는 전체 과정을 **개발 → Docker 이미지 생성 → Kubernetes 배포 → 외부 접속 → 운영·업데이트** 순서로 정리합니다.

각 단계에서 다음을 함께 다룹니다.

- 왜 이 작업이 필요한가?
- 어떤 개념을 이해해야 하는가?
- 실제로 어떤 명령어를 실행하는가?
- 실행하면 내부적으로 어떤 일이 일어나는가?
- 실무에서는 어떻게 활용하는가?

## 1. 전체 배포 구조

```text
사용자 브라우저
      │  HTTP 요청
      ▼
Kubernetes Service        요청을 적절한 Pod로 전달
      │
      ▼
Kubernetes Deployment     Pod의 개수와 상태를 관리
  ┌────────┬────────┬────────┐
  │ Pod 1  │ Pod 2  │ Pod 3  │   (각 Pod에서 Node.js 실행)
  └────────┴────────┴────────┘
      │
      ▼
Docker Container          Node.js 실행 환경
      │
      ▼
Node.js Application       Express API / Backend
```

전체 흐름은 다음과 같습니다.

1. Node.js로 서버를 개발합니다.
2. Docker를 이용해서 실행 가능한 이미지로 만듭니다.
3. Docker 이미지를 이미지 저장소(Docker Hub 등)에 올립니다.
4. Kubernetes가 이미지를 가져와 컨테이너를 실행합니다.
5. Kubernetes Service가 외부 또는 내부 요청을 Pod로 전달합니다.
6. 필요하면 Pod 개수를 늘리거나 줄입니다.

여기서 중요한 것은 **Docker와 Kubernetes는 서로 다른 역할을 한다**는 점입니다.

| 기술 | 역할 |
|---|---|
| Node.js | 서버 애플리케이션 실행 |
| Express | HTTP API 서버 개발 |
| Docker | 애플리케이션을 컨테이너 이미지로 패키징 |
| Docker Hub | 컨테이너 이미지 저장소 |
| Kubernetes | 컨테이너 배포 및 운영 관리 |
| Deployment | Pod의 개수와 배포 상태 관리 |
| Pod | 컨테이너가 실행되는 Kubernetes 기본 단위 |
| Service | Pod에 안정적인 네트워크 접근 제공 |
| Ingress | 외부 HTTP/HTTPS 요청을 서비스로 연결 |

## 2. 학습용 프로젝트 만들기

| 구분 | 사용 기술 |
|---|---|
| Backend | Node.js |
| Framework | Express |
| Container | Docker |
| Container Registry | Docker Hub |
| Orchestration | Kubernetes |
| 개발 환경 | Ubuntu 또는 Windows + WSL2 |
| Kubernetes 실습 환경 | Minikube |

처음부터 AWS EKS, GKE 같은 클라우드 Kubernetes를 사용하면 비용과 설정이 복잡해집니다. 그래서 우선 PC에서 Kubernetes를 실행하는 **Minikube**를 사용합니다.

### 2-1. 프로젝트 디렉터리 구성

```bash
mkdir node-k8s-project
cd node-k8s-project
npm init -y
npm install express
```

```text
node-k8s-project/
├── app.js
├── package.json
├── package-lock.json
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

- `app.js`: Node.js 서버 코드
- `package.json`: 프로젝트 의존성 관리
- `Dockerfile`: Docker 이미지 생성 방법
- `deployment.yaml`: Kubernetes 배포 설정
- `service.yaml`: Kubernetes 네트워크 설정

### 2-2. Node.js 서버 작성

`app.js`

```javascript
const express = require('express');

const app = express();

const PORT = process.env.PORT || 3000;

// 기본 API
app.get('/', (req, res) => {
  res.json({
    message: 'Hello Kubernetes!',
    application: 'Node.js Server',
    version: '1.0.0'
  });
});

// 서버 상태 확인 API
app.get('/health', (req, res) => {
  res.status(200).json({
    status: 'UP'
  });
});

// 서버 실행
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
});
```

여기서 중요한 개념 3가지가 있습니다.

1. **`process.env.PORT`** — 환경변수에서 PORT 값을 가져옵니다. Kubernetes에서는 애플리케이션 설정을 환경변수로 전달하는 방식이 많이 사용됩니다.
2. **`0.0.0.0`** — 컨테이너 외부에서 서버에 접근할 수 있도록 모든 네트워크 인터페이스에서 수신하도록 설정합니다.
3. **`/health`** — Kubernetes가 애플리케이션이 정상적으로 실행되고 있는지 확인할 때 쓰는 API입니다. 이런 상태 확인 API를 Health Check라고 합니다.

### 2-3. 로컬에서 서버 실행

```bash
node app.js
```

브라우저에서 `http://localhost:3000` 으로 접속합니다.

```json
{
  "message": "Hello Kubernetes!",
  "application": "Node.js Server",
  "version": "1.0.0"
}
```

여기까지는 일반적인 Node.js 서버 개발 과정입니다.

## 3. Docker 개념과 이미지 만들기

### 3-1. Docker란 무엇인가?

Node.js 서버를 실행하려면 운영체제, Node.js, npm, 설치된 라이브러리, 애플리케이션 코드가 필요합니다.

개발 PC에서는 정상적으로 실행되는데 서버에 배포하면 오류가 나는 경우가 있습니다. Node.js 버전이나 라이브러리 버전 등이 서로 다르기 때문입니다.

Docker는 이러한 실행 환경을 **컨테이너 이미지로 패키징**합니다. 즉, 개발 PC에서 만든 Docker 이미지를 서버에서도 동일한 방식으로 실행할 수 있습니다.

### 3-2. Dockerfile 작성

프로젝트 루트에 `Dockerfile`을 생성합니다.

```dockerfile
# Node.js 22 기반 이미지
FROM node:22-alpine

# 컨테이너 내부 작업 디렉터리
WORKDIR /app

# 패키지 파일 복사
COPY package*.json ./

# 라이브러리 설치
RUN npm ci --omit=dev

# 애플리케이션 코드 복사
COPY app.js .

# 컨테이너 포트
EXPOSE 3000

# Node.js 서버 실행
CMD ["node", "app.js"]
```

| 명령어 | 설명 |
|---|---|
| FROM | 기반이 되는 Docker 이미지 지정 |
| WORKDIR | 컨테이너 내부 작업 디렉터리 설정 |
| COPY | 로컬 파일을 컨테이너에 복사 |
| RUN | 이미지 생성 과정에서 명령어 실행 |
| EXPOSE | 애플리케이션이 사용하는 포트 문서화 |
| CMD | 컨테이너 실행 시 기본 실행 명령어 |

> `EXPOSE 3000` 자체가 외부 포트를 열어 주는 것은 아닙니다. 실제 네트워크 연결은 Docker 실행 옵션이나 Kubernetes Service가 담당합니다.

### 3-3. Docker 이미지 생성

```bash
docker build -t node-k8s-app:1.0 .
docker images
```

```text
REPOSITORY      TAG      IMAGE ID
node-k8s-app    1.0      xxxxxxxxx
```

이미지와 컨테이너의 차이:

- **이미지(Image)**: 컨테이너를 생성하기 위한 설계도 또는 패키지
- **컨테이너(Container)**: 이미지로부터 실제 실행된 인스턴스

하나의 이미지로 여러 개의 컨테이너를 실행할 수 있습니다.

### 3-4. Docker 컨테이너 실행

```bash
docker run -d \
  -p 3000:3000 \
  --name node-server \
  node-k8s-app:1.0
```

- `-d`: 백그라운드 실행
- `-p 3000:3000`: 호스트 포트와 컨테이너 포트 연결
- `--name`: 컨테이너 이름 지정

```bash
docker ps                      # 실행 상태 확인
curl http://localhost:3000     # 접속 테스트
```

정상적으로 응답하면 Docker 단계까지 완료된 것입니다.

## 4. Kubernetes 환경 구축하기

### 4-1. Kubernetes란?

Kubernetes는 컨테이너를 자동으로 관리하는 **오케스트레이션(Orchestration)** 플랫폼입니다.

예를 들어 Node.js 서버를 운영하는데 사용자가 많아져 서버를 3개로 늘려야 한다고 해 보겠습니다. Docker만 사용한다면 직접 컨테이너를 실행하고, 종료되면 다시 실행하고, 서버 간 요청도 분배해야 합니다.

Kubernetes를 사용하면 이런 작업을 **선언적으로** 관리할 수 있습니다.

```yaml
replicas: 3
```

이 설정은 Node.js 애플리케이션을 3개 실행하라는 의미입니다. 실제 실행 개수가 2개로 줄어들면 Kubernetes가 하나를 다시 생성해서 3개를 유지하려고 합니다.

이것을 **Desired State(원하는 상태)** 와 **Reconciliation(상태 조정)** 이라고 합니다. Kubernetes는 단순히 컨테이너를 실행하는 것이 아니라 사용자가 지정한 상태를 지속적으로 유지하는 시스템입니다.

### 4-2. Kubernetes의 주요 구성 요소

| 구성 요소 | 설명 |
|---|---|
| Cluster | Kubernetes 전체 시스템 |
| Node | 컨테이너를 실행하는 서버 |
| Control Plane | 클러스터 전체 관리 |
| Worker Node | 실제 애플리케이션 실행 |
| API Server | Kubernetes 명령과 요청을 처리 |
| Scheduler | Pod를 어느 Node에 실행할지 결정 |
| Controller Manager | 원하는 상태를 유지하도록 관리 |
| etcd | Kubernetes 상태 정보를 저장 |
| kubelet | 각 Node에서 Pod 실행 상태 관리 |
| kube-proxy | Service 네트워크 기능 구현에 관여 |

쉽게 정리하면 다음과 같습니다.

- **Cluster**: 전체 Kubernetes 시스템
- **Node**: 실제 서버
- **Pod**: 애플리케이션이 실행되는 단위
- **Container**: Pod 내부에서 실행되는 프로그램
- **Deployment**: Pod의 개수와 버전 등을 관리
- **Service**: Pod로 요청을 전달하는 네트워크 진입점

#### Pod란?

Kubernetes는 Docker 컨테이너를 직접 관리하는 것이 아니라 **Pod 단위로** 관리합니다. Pod는 하나 이상의 컨테이너를 포함할 수 있습니다.

```text
Kubernetes Node
└── Pod A
    ├── Node.js 컨테이너
    └── Sidecar 컨테이너
```

하나의 Pod 안에서 여러 컨테이너가 네트워크와 저장 공간을 공유할 수 있습니다. 이번 실습에서는 이해하기 쉽도록 Pod 하나에 Node.js 컨테이너 하나를 실행합니다.

### 4-3. 실습 환경 설치 (Minikube)

Minikube는 개인 PC에서 Kubernetes 클러스터를 실행할 수 있게 해 주는 도구입니다. 필요한 프로그램은 **Docker, kubectl, Minikube** 3가지입니다.

#### Ubuntu 기준 설치

```bash
sudo apt-get update
sudo apt-get install -y curl

# kubectl
sudo snap install kubectl --classic

# Minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
```

설치 확인:

```bash
kubectl version --client
minikube version
docker --version
```

#### Kubernetes 클러스터 실행

```bash
minikube start --driver=docker
```

Minikube가 Docker를 이용해 Kubernetes Node를 구성합니다.

```bash
kubectl cluster-info   # 클러스터 상태 확인
kubectl get nodes      # Node 확인
```

```text
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   1m    v1.xx.x
```

> **중요**: `STATUS`가 `Ready`인지 확인하세요.

## 5. Docker 이미지를 Kubernetes에 전달하기

앞에서 만든 `node-k8s-app:1.0` 이미지를 Kubernetes에서 사용하도록 해야 합니다. 방법은 두 가지입니다.

**방법 A. Docker Hub에 이미지 업로드**

```text
개발 PC → Docker Image 생성 → Docker Hub 업로드 → Kubernetes가 이미지 다운로드 → Pod 실행
```

**방법 B. Minikube에 이미지 직접 전달**

```text
개발 PC Docker → Minikube 이미지 저장소 → Pod 실행
```

학습 단계에서는 방법 B가 간단합니다.

```bash
minikube image load node-k8s-app:1.0
```

이 방법은 로컬 실습에 적합합니다. 실제 운영 환경에서는 Docker Hub, AWS ECR, Google Artifact Registry 등의 이미지 저장소를 사용합니다.

## 6. Deployment 작성하기

```bash
mkdir -p k8s
```

`k8s/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: node-app-deployment
spec:
  # Node.js Pod 3개 실행
  replicas: 3
  selector:
    matchLabels:
      app: node-app
  template:
    metadata:
      labels:
        app: node-app
    spec:
      containers:
        - name: node-app
          image: node-k8s-app:1.0
          imagePullPolicy: Never
          ports:
            - containerPort: 3000
          env:
            - name: PORT
              value: "3000"
```

### 6-1. apiVersion

```yaml
apiVersion: apps/v1
```

Kubernetes API 버전을 지정합니다. Deployment는 `apps/v1` API를 사용합니다.

### 6-2. kind

```yaml
kind: Deployment
```

어떤 Kubernetes 리소스를 생성할지 지정합니다.

| kind | 역할 |
|---|---|
| Pod | 컨테이너 실행 단위 |
| Deployment | Pod 배포 및 관리 |
| Service | 네트워크 연결 |
| ConfigMap | 일반 설정값 저장 |
| Secret | 민감한 설정값 저장 |
| Ingress | HTTP/HTTPS 외부 진입점 |
| PersistentVolume | 영구 저장 공간 |
| StatefulSet | 상태를 가진 애플리케이션 관리 |

### 6-3. replicas

```yaml
replicas: 3
```

Node.js Pod를 3개 실행하라는 뜻입니다.

```text
Deployment: node-app-deployment   (Desired replicas: 3)
  ┌──────────┬──────────┬──────────┐
  │ Pod 1    │ Pod 2    │ Pod 3    │
  │ Running  │ Running  │ Running  │
  └──────────┴──────────┴──────────┘
```

Pod 하나가 비정상 종료되면 Deployment의 하위 **ReplicaSet**이 원하는 개수를 맞추기 위해 새 Pod를 생성합니다.

### 6-4. selector와 labels

```yaml
selector:
  matchLabels:
    app: node-app
```

```yaml
labels:
  app: node-app
```

이 두 설정은 서로 연결됩니다. Deployment는 `app: node-app` 라벨을 가진 Pod를 자신의 관리 대상으로 인식합니다. 라벨은 Kubernetes에서 리소스를 분류하고 연결하는 중요한 개념입니다.

### 6-5. image

```yaml
image: node-k8s-app:1.0
imagePullPolicy: Never
```

`imagePullPolicy: Never`는 이미지를 외부 저장소에서 다운로드하지 않고, 현재 Minikube Node에 있는 이미지로 실행하라는 의미입니다. **로컬 실습용 설정**입니다.

실제 운영에서는 일반적으로 다음과 같이 사용합니다.

```yaml
image: mydockerid/node-k8s-app:1.0
imagePullPolicy: IfNotPresent
```

### 6-6. containerPort

```yaml
ports:
  - containerPort: 3000
```

컨테이너에서 Node.js 서버가 사용하는 포트입니다. 이것만으로 외부 접속이 가능해지지는 않으며, 외부 접속을 위해서는 별도의 **Service**가 필요합니다.

## 7. Deployment 실행하기

```bash
kubectl apply -f k8s/deployment.yaml
```

```text
deployment.apps/node-app-deployment created
```

Pod 상태 확인:

```bash
kubectl get pods
```

```text
NAME                                  READY   STATUS
node-app-deployment-xxxxx-aaaaa       1/1     Running
node-app-deployment-xxxxx-bbbbb       1/1     Running
node-app-deployment-xxxxx-ccccc       1/1     Running
```

Deployment 확인:

```bash
kubectl get deployment
```

```text
NAME                 READY   UP-TO-DATE   AVAILABLE
node-app-deployment  3/3     3            3
```

`3/3`은 원하는 Pod 3개가 모두 준비되었다는 의미입니다.

### Pod 로그 확인

```bash
kubectl logs -l app=node-app   # 라벨로 여러 Pod 로그
kubectl logs <POD_NAME>        # 특정 Pod 로그
```

### Pod 내부 접속

```bash
kubectl exec -it <POD_NAME> -- sh

# 컨테이너 내부에서
pwd
ls
node --version
exit
```

## 8. Service 설정하기

Pod는 언제든지 삭제되고 새로 만들어질 수 있습니다.

```text
기존 Pod A → 삭제
새로운 Pod D → 생성
```

이때 Pod의 이름과 IP 주소가 바뀔 수 있으므로, 클라이언트가 Pod IP를 직접 기억하고 접속하면 문제가 생깁니다. 이 문제를 해결하는 것이 **Service**입니다.

### 8-1. Service란?

Service는 Pod에 **안정적인 네트워크 접근 경로**(Stable IP / DNS)를 제공하고, 여러 Pod에 요청을 분배합니다.

```text
Client (HTTP Request)
      │
      ▼
Service: node-app-service   ← Stable IP / DNS
      │
  ┌───┴─────┬─────────┐
  ▼         ▼         ▼
Pod A     Pod B     Pod C
```

### 8-2. Service 종류

| 유형 | 설명 | 사용 목적 |
|---|---|---|
| ClusterIP | 클러스터 내부에서만 접근 | 내부 API 통신 |
| NodePort | Node의 특정 포트로 접근 | 개발 및 간단한 외부 접속 |
| LoadBalancer | 외부 로드밸런서 연결 | 클라우드 운영 환경 |

이번에는 NodePort를 사용합니다.

### 8-3. service.yaml 작성

`k8s/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: node-app-service
spec:
  type: NodePort
  selector:
    app: node-app
  ports:
    - protocol: TCP
      port: 80
      targetPort: 3000
      nodePort: 30080
```

- `type: NodePort`: 외부에서 Node의 포트를 통해 접근
- `selector`: `app=node-app`인 Pod에 연결
- `port: 80`: Service가 제공하는 포트
- `targetPort: 3000`: 실제 컨테이너 포트
- `nodePort: 30080`: Node에서 노출할 포트

```text
사용자
  │  HTTP :30080
  ▼
Kubernetes Node (NodePort)
  │
  ▼
Service :80
  │  targetPort :3000
  ▼
Node.js Pod :3000
```

### 8-4. Service 배포

```bash
kubectl apply -f k8s/service.yaml
kubectl get services
```

```text
NAME               TYPE       CLUSTER-IP
node-app-service   NodePort   10.96.x.x
```

Minikube에서 접속 주소를 확인합니다.

```bash
minikube service node-app-service --url
```

또는 포트 포워딩을 사용합니다.

```bash
kubectl port-forward service/node-app-service 8080:80
```

브라우저에서 `http://localhost:8080` 으로 접속해 Node.js 응답이 나오는지 확인합니다.

여기까지가 **Node.js + Docker + Kubernetes 기본 배포의 핵심 과정**입니다.

## 9. Kubernetes 핵심 기능 실습

Kubernetes의 핵심은 다음 네 가지입니다.

1. 자동 복구(Self Healing)
2. 수평 확장(Scaling)
3. 무중단 업데이트(Rolling Update)
4. 상태 모니터링(Health Check)

### 9-1. Pod 자동 복구

```bash
kubectl get pods
kubectl delete pod <POD_NAME>
kubectl get pods
```

삭제한 Pod 대신 새로운 Pod가 생성된 것을 볼 수 있습니다.

Deployment에 `replicas: 3`이 설정되어 있으므로, Pod가 삭제되어 2개가 되면 Kubernetes가 새 Pod를 만들어 3개를 유지합니다. 이것이 **자동 복구** 기능이며, 실무에서 애플리케이션 오류나 서버 장애에 대응하는 중요한 기능입니다.

### 9-2. Pod 개수 늘리기 (Scale Out)

```bash
kubectl scale deployment node-app-deployment --replicas=5
kubectl get pods
```

```text
변경 전 (3 Pods)   [Pod 1] [Pod 2] [Pod 3]
변경 후 (5 Pods)   [Pod 1] [Pod 2] [Pod 3] [Pod 4] [Pod 5]
```

다시 3개로 줄이려면:

```bash
kubectl scale deployment node-app-deployment --replicas=3
```

- **Scale Out**: 서버 인스턴스 개수를 늘리는 것
- **Scale In**: 서버 인스턴스 개수를 줄이는 것
- **Scale Up**: 개별 서버의 CPU나 메모리를 늘리는 것

Kubernetes는 여러 Pod를 실행하는 **수평 확장**에 특히 유용합니다.

### 9-3. 자동으로 Pod 개수 조정하기 (HPA)

매번 사람이 Pod 개수를 바꾸는 것은 번거롭습니다. **HPA(Horizontal Pod Autoscaler)** 는 CPU 사용량이 늘면 Pod를 자동으로 늘리고, 줄면 Pod를 줄입니다.

`k8s/hpa.yaml`

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: node-app-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: node-app-deployment
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

- 최소 Pod: 2개
- 최대 Pod: 10개
- 평균 CPU 사용률 목표: 70%

CPU 사용률이 목표보다 높으면 Pod를 추가하도록 조정합니다.

> HPA를 사용하려면 컨테이너의 CPU 요청량(`resources.requests`)이 정의되어 있어야 하고, **Metrics Server**도 필요합니다.

```bash
minikube addons enable metrics-server
kubectl apply -f k8s/hpa.yaml
kubectl get hpa
```

HPA는 CPU가 순간적으로 올랐다고 즉시 Pod를 추가하지 않습니다. 일정 시간 동안의 측정값과 안정화 정책을 고려해 동작합니다.

## 10. 무중단 업데이트 (Rolling Update)

서버에 새 기능을 추가해 이미지를 `node-k8s-app:1.0` → `node-k8s-app:2.0`으로 바꾼다고 해 보겠습니다. 기존 컨테이너를 모두 종료하고 새 버전을 실행하면 서비스가 잠시 중단될 수 있습니다.

### 10-1. Rolling Update란?

기존 Pod를 한꺼번에 종료하지 않고 새로운 Pod를 **단계적으로** 실행하는 방법입니다.

```text
Step 1  기존 3개            [v1.0] [v1.0] [v1.0]
Step 2  신규 Pod 추가       [v1.0] [v1.0] [v1.0] [v2.0]
Step 3  신규 버전 교체 완료  [v2.0] [v2.0] [v2.0]
```

### 10-2. 새 Docker 이미지 생성

```javascript
app.get('/', (req, res) => {
  res.json({
    message: 'Hello Kubernetes!',
    version: '2.0.0'
  });
});
```

```bash
docker build -t node-k8s-app:2.0 .
minikube image load node-k8s-app:2.0
```

### 10-3. Deployment 업데이트

```bash
# 이미지 버전 변경
kubectl set image deployment/node-app-deployment \
  node-app=node-k8s-app:2.0

# 배포 완료까지 상태 확인
kubectl rollout status deployment/node-app-deployment

# 이전 버전으로 롤백
kubectl rollout undo deployment/node-app-deployment

# 배포 이력 확인
kubectl rollout history deployment/node-app-deployment
```

실제 운영 환경에서 자주 사용하는 기능입니다.

> 무중단 배포가 항상 보장되는 것은 아닙니다. 애플리케이션의 준비 상태, 복제본 수, 트래픽 처리, 종료 처리 등이 적절하게 설정되어야 합니다.

## 11. Health Check (Probe)

| 종류 | 역할 |
|---|---|
| Liveness Probe | 애플리케이션이 정상적으로 살아 있는지 확인 |
| Readiness Probe | 요청을 받아 처리할 준비가 되었는지 확인 |
| Startup Probe | 애플리케이션 초기 시작이 완료되었는지 확인 |

- **Liveness**: 서버가 멈춰 정상 동작하지 않으면 컨테이너를 재시작합니다.
- **Readiness**: 서버가 아직 준비되지 않았다면 Service가 해당 Pod에 트래픽을 보내지 않습니다.
- **Startup**: 서버 시작이 오래 걸리는 경우, 초기 구동이 끝날 때까지 Liveness·Readiness 검사를 유예합니다.

Deployment의 컨테이너 설정에 다음 내용을 추가합니다.

```yaml
containers:
  - name: node-app
    image: node-k8s-app:2.0
    ports:
      - containerPort: 3000

    startupProbe:
      httpGet:
        path: /health
        port: 3000
      periodSeconds: 5
      failureThreshold: 30

    livenessProbe:
      httpGet:
        path: /health
        port: 3000
      periodSeconds: 10

    readinessProbe:
      httpGet:
        path: /health
        port: 3000
      periodSeconds: 5
```

실제 운영에서는 HTTP 응답 여부만이 아니라, 애플리케이션이 실제로 요청을 처리할 수 있는 상태인지도 고려해야 합니다. 예를 들어 DB 연결이 반드시 필요한 API 서버라면 DB 연결 상태를 Readiness 검사에 반영할 수 있습니다.

## 12. 환경변수와 ConfigMap, Secret

실제 Node.js 서버에는 다음과 같은 설정이 필요할 수 있습니다.

```text
PORT=3000
NODE_ENV=production
DB_HOST=192.168.0.10
DB_USER=appuser
DB_PASSWORD=123456
```

이런 값을 소스 코드에 직접 넣는 것은 좋지 않습니다. 개발 환경과 운영 환경의 설정이 다를 수 있기 때문입니다. Kubernetes에서는 **ConfigMap**과 **Secret**을 사용합니다.

### 12-1. ConfigMap

일반적인 환경 설정값을 저장하는 리소스입니다.

`k8s/configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: node-app-config
data:
  PORT: "3000"
  NODE_ENV: "production"
```

```bash
kubectl apply -f k8s/configmap.yaml
```

Deployment에서 다음과 같이 사용하면 ConfigMap의 값들이 컨테이너 환경변수로 전달됩니다.

```yaml
envFrom:
  - configMapRef:
      name: node-app-config
```

### 12-2. Secret

DB 비밀번호 같은 민감한 정보를 저장하는 리소스입니다.

```bash
kubectl create secret generic node-db-secret \
  --from-literal=DB_PASSWORD='your-password'
```

```yaml
env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: node-db-secret
        key: DB_PASSWORD
```

> ⚠️ Kubernetes Secret은 기본적으로 값이 **Base64 인코딩**된 형태로 표현됩니다. Base64는 암호화가 아닙니다. 실제 운영에서는 RBAC 권한 관리, etcd 암호화, 외부 비밀 관리 시스템 등을 함께 고려해야 합니다.

## 13. Ingress로 도메인 연결

지금까지는 `http://localhost:8080` 으로 접속했지만, 실제 서비스라면 `https://api.example.com` 처럼 도메인으로 접속하고 싶을 것입니다. 이때 **Ingress**를 사용합니다.

Ingress는 HTTP/HTTPS 요청을 받아 적절한 Kubernetes Service로 전달하는 규칙을 정의합니다.

```text
사용자 브라우저
      │  https://api.example.com
      ▼
Ingress Controller
      │  Host 기반 라우팅
      ▼
node-app-service
      │
      ▼
Node.js Pod 3개
```

Minikube에서 Ingress 애드온을 활성화합니다.

```bash
minikube addons enable ingress
```

`k8s/ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: node-app-ingress
spec:
  rules:
    - host: api.example.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: node-app-service
                port:
                  number: 80
```

```bash
kubectl apply -f k8s/ingress.yaml
```

Ingress 리소스만 만든다고 접속되는 것은 아닙니다. **Ingress Controller, DNS 또는 로컬 hosts 설정, 네트워크 연결**이 함께 준비되어야 합니다. 실제 HTTPS 운영에서는 TLS 인증서도 설정해야 합니다.

## 14. 운영 및 장애 분석 명령어

| 명령어 | 역할 |
|---|---|
| `kubectl get pods` | Pod 목록 |
| `kubectl get nodes` | Node 목록 |
| `kubectl get deployment` | Deployment 확인 |
| `kubectl get svc` | Service 확인 |
| `kubectl get all` | 주요 리소스 조회 |
| `kubectl describe pod 이름` | Pod 상세 정보 |
| `kubectl logs 이름` | 애플리케이션 로그 |
| `kubectl logs -f 이름` | 실시간 로그 |
| `kubectl exec -it 이름 -- sh` | 컨테이너 접속 |
| `kubectl delete pod 이름` | Pod 삭제 |
| `kubectl rollout status deployment/이름` | 배포 상태 |
| `kubectl rollout undo deployment/이름` | 이전 버전으로 복구 |

특히 다음 세 가지 상황을 익혀 두세요.

| 상황 | 명령어 | 확인 내용 |
|---|---|---|
| Pod가 실행되지 않을 때 | `kubectl describe pod <POD_NAME>` | Pod 이벤트와 오류 원인 |
| 애플리케이션 오류가 날 때 | `kubectl logs <POD_NAME>` | Node.js 실행 로그 |
| Pod가 계속 재시작할 때 | `kubectl get pods -w` | 실시간 상태 변화 |

대표적인 Pod 상태:

| 상태 | 의미 |
|---|---|
| Pending | Pod가 실행될 Node를 찾거나 필요한 리소스를 준비 중 |
| ContainerCreating | 컨테이너 생성 중 |
| Running | 실행 중 |
| CrashLoopBackOff | 컨테이너가 반복적으로 종료되어 재시작 대기 |
| ImagePullBackOff | 컨테이너 이미지 다운로드 실패 |
| ErrImagePull | 이미지 다운로드 오류 |
| OOMKilled | 메모리 제한 등으로 컨테이너 종료 |

## 15. 실무형으로 발전시키기

실제 서비스에서는 다음과 같은 구성을 추가합니다.

```text
사용자
  │
  ▼
Cloud Load Balancer      외부 트래픽 수신
  │
  ▼
Ingress Controller       도메인 / HTTPS / 라우팅
  │
  ▼
Kubernetes Service       Pod 연결 및 트래픽 분산
  ├─ Node.js 1
  ├─ Node.js 2
  └─ Node.js 3
  │
  ▼
Database                 PostgreSQL / MySQL / Redis

[운영 및 관리 영역]
  Prometheus (모니터링) · Grafana (시각화) · CI/CD (자동 배포) · RBAC / Secret (보안 및 권한)
```

추가로 학습하면 좋은 기술:

| 기술 | 학습 목적 |
|---|---|
| Git | 소스 코드 버전 관리 |
| GitHub Actions | 빌드 및 배포 자동화 |
| Helm | Kubernetes 배포 설정 템플릿화 |
| Nginx | 리버스 프록시 및 웹 서버 |
| Prometheus | CPU, 메모리, 서버 상태 모니터링 |
| Grafana | 모니터링 대시보드 |
| EKS | AWS 관리형 Kubernetes |
| Argo CD | GitOps 기반 자동 배포 |

특히 **CI/CD**가 중요합니다. 개발자가 GitHub에 코드를 올리면 다음 작업이 자동으로 수행되도록 만들 수 있습니다.

```text
개발자 코드 Push
  → GitHub Repository
  → GitHub Actions
  → Node.js 테스트
  → Docker 이미지 빌드
  → Docker Hub / ECR 업로드
  → Kubernetes 배포
  → 새로운 버전 서비스
```

## 16. 전체 실습 체크리스트

1. Node.js 프로젝트 생성 및 Express 서버 작성
2. Dockerfile 작성
3. Docker 이미지 빌드
4. Docker 컨테이너 실행 및 테스트
5. Minikube 설치 및 Kubernetes 실행
6. kubectl로 클러스터 상태 확인
7. Minikube에 Docker 이미지 등록
8. Deployment YAML 작성 및 배포
9. Pod 3개 실행 확인
10. Service YAML 작성 및 배포
11. 브라우저에서 Node.js API 접속
12. Pod 삭제 후 자동 복구 확인
13. Pod 개수 3개에서 5개로 확장
14. Docker 이미지 2.0 생성
15. Rolling Update 및 Rollback 테스트
16. Health Check 설정
17. ConfigMap 및 Secret 적용
18. Ingress와 도메인 연결 실습

## 17. 실습 환경 정리 및 주의사항

```bash
# Kubernetes 리소스 삭제
kubectl delete -f k8s/ingress.yaml
kubectl delete -f k8s/service.yaml
kubectl delete -f k8s/deployment.yaml

# Minikube 클러스터 중지
minikube stop

# Minikube 클러스터 완전 삭제
minikube delete
```

주의할 점:

1. 실습용 `imagePullPolicy: Never`는 로컬 Minikube 이미지 사용에 적합합니다. 운영 환경에서는 이미지 저장소와 인증을 설정해야 합니다.
2. 실제 운영에서는 CPU와 메모리의 `requests`, `limits`를 반드시 검토해야 합니다.
3. 데이터베이스 데이터를 Pod 내부 파일시스템에만 저장하면 안 됩니다. PersistentVolume 등을 활용해야 합니다.
4. Secret은 Base64만으로 보호되는 것이 아니므로 운영 환경에서는 추가 보안 조치가 필요합니다.
5. 무중단 배포를 위해서는 Readiness Probe, 적절한 복제본 수, Pod 종료 시 처리 로직도 중요합니다.
6. 운영 환경에서는 NodePort를 직접 인터넷에 노출하기보다 LoadBalancer와 Ingress 등을 활용하는 구성을 고려합니다.

## 18. 추천 학습 로드맵

처음부터 AWS EKS를 사용하기보다 다음 순서로 학습하는 것을 권장합니다.

1. **Node.js 서버 개발** — Express, REST API, 환경변수, 로그, DB 연결
2. **Docker** — Dockerfile, Image, Container, Docker Hub, Docker Compose
3. **Kubernetes 기초** — Cluster, Node, Pod, Deployment, Service, Namespace
4. **Kubernetes 운영** — HPA, Probe, ConfigMap, Secret, Ingress, Volume
5. **CI/CD** — GitHub Actions, Docker 이미지 자동 빌드, Kubernetes 자동 배포
6. **클라우드 운영** — AWS EKS, ECR, Load Balancer, RDS, 모니터링

### 꼭 이해해야 할 핵심 개념

| 질문 | 이해해야 할 개념 |
|---|---|
| Node.js를 어떻게 실행하는가? | Docker Container |
| 컨테이너를 어떻게 배포하는가? | Deployment |
| 컨테이너를 어떻게 연결하는가? | Service |
| 장애가 나면 어떻게 복구하는가? | Desired State / Self Healing |
| 서버가 많아지면 어떻게 대응하는가? | HPA / Scaling |

**최종 목표 프로젝트**: Node.js API 서버를 개발하고, Docker 이미지로 만든 다음, Kubernetes에 3개 이상의 Pod로 배포하고, 외부에서 도메인으로 접속할 수 있게 하며, GitHub에 코드를 올리면 자동으로 배포되는 시스템을 만드는 것입니다.

다음 단계로는 GitHub Actions를 이용해 **Node.js → Docker 이미지 빌드 → Docker Hub 업로드 → Kubernetes 자동 배포**까지 연결하는 CI/CD 프로젝트를 추천합니다.
