# 실습 클러스터 구축

이 코스의 모든 실습(Step 01 ~ Step 25)은 **내 노트북 위에서 돌아가는 진짜 Kubernetes 클러스터**를 전제로 합니다. 그 클러스터를 만들어 주는 것이 바로 이 디렉터리입니다.

여기서는 [kind](https://kind.sigs.k8s.io/)(Kubernetes IN Docker)를 사용합니다. kind 는 Docker 컨테이너 하나하나를 "노드"처럼 취급해서, Docker 만 설치되어 있으면 몇 분 만에 멀티 노드 클러스터를 띄울 수 있게 해 줍니다. 클라우드 비용도, VM 도, 별도의 네트워크 설정도 필요하지 않습니다. 실습이 끝나면 컨테이너를 지우는 것으로 흔적 없이 정리됩니다.

## 무엇을 만드는가

| 항목 | 값 |
|------|-----|
| 클러스터 이름 | `learn` |
| kubectl 컨텍스트 | `kind-learn` |
| 노드 구성 | control-plane 1대 + worker 2대 (총 3노드) |
| 호스트로 열리는 포트 | `30080`, `30443` (NodePort), `8080`→80, `8443`→443 (Ingress) |

워커를 굳이 **2대** 두는 이유가 있습니다. 스케줄링·어피니티·테인트/톨러레이션(Step 13)이나 토폴로지 분산·PodDisruptionBudget(Step 21) 실습은 "파드가 서로 다른 노드에 흩어지는가"를 눈으로 확인해야 하는데, 워커가 1대뿐이면 그 차이를 볼 수 없기 때문입니다.

## 사전 준비물

- **Docker Desktop**(또는 Docker Engine) — 실행 중이어야 합니다.
- **kind** — `brew install kind`
- **kubectl** — `brew install kubectl`

## 사용 순서

```bash
# 1) 클러스터 생성 (최초 1회, 보통 2~5분)
cd docs/reference/k8s/cluster   # 실습 파일이 있는 디렉터리
./create-cluster.sh

# 2) 잘 떴는지 확인
kubectl get nodes

# 3) 모든 실습이 끝났거나, 클러스터를 처음부터 다시 만들고 싶을 때
./delete-cluster.sh
```

`create-cluster.sh` 는 **여러 번 실행해도 안전합니다.** 이미 `learn` 클러스터가 있으면 새로 만들지 않고 안내 메시지만 출력합니다. 반대로 클러스터가 꼬였을 때는 `./delete-cluster.sh` 로 완전히 지운 뒤 다시 만드는 것이 가장 빠릅니다.

:::tip 사내망(프록시) 환경이라면
회사 네트워크가 TLS 를 가로채는 프록시를 쓰는 경우, 노드가 이미지를 받을 때 `certificate signed by unknown authority` 로 실패합니다. 이때는 `INJECT_CA=1 ./create-cluster.sh` 로 실행하세요. 자세한 동작은 아래 [실습 파일](#실습-파일) 섹션의 `create-cluster.sh` 해설을 참고하세요.
:::

## 실습 파일

이 디렉터리에는 파일이 3개뿐입니다. `kind-cluster.yaml` 이 **클러스터의 설계도**이고, `create-cluster.sh` 가 그 설계도를 읽어 클러스터를 **생성·검증**하며, `delete-cluster.sh` 가 **정리**합니다. 실행 순서는 `create-cluster.sh` → (25개 스텝 실습) → `delete-cluster.sh` 입니다.

### kind-cluster.yaml

클러스터의 형태를 정의하는 kind 설정 파일입니다. `create-cluster.sh` 가 `kind create cluster --config` 에 이 파일을 넘겨 사용하며, 직접 실행할 일은 없습니다.

- `name: learn` — 클러스터 이름입니다. 이 이름 때문에 kubectl 컨텍스트가 `kind-learn` 이 되고, 스크립트들도 모두 `CLUSTER_NAME="learn"` 을 기준으로 동작합니다.
- `nodes:` 아래에 `role: control-plane` 1개와 `role: worker` 2개가 나열되어 있어 3노드 클러스터가 만들어집니다. 워커 2대는 Step 13(스케줄링)·Step 21(중단 관리) 실습의 최소 조건입니다.
- control-plane 의 `kubeadmConfigPatches` 는 kubelet 에 `node-labels: "ingress-ready=true"` 를 붙입니다. Step 14 에서 설치할 ingress-nginx 는 이 레이블이 달린 노드에만 스케줄되도록 되어 있어서, **이 한 줄이 없으면 인그레스 컨트롤러가 Pending 에서 멈춥니다.**
- `extraPortMappings` 는 컨테이너(노드) 포트를 내 맥의 포트로 뚫어 줍니다. `30080`/`30443` 은 그대로 매핑되어 NodePort 서비스(Step 06)를 `http://localhost:30080` 으로 열 수 있게 하고, 노드의 `80`/`443` 은 각각 호스트의 `8080`/`8443` 으로 매핑되어 Ingress(Step 14)를 `http://localhost:8080` 으로 열 수 있게 합니다.
- 다만 이 `extraPortMappings` 는 **`control-plane` 노드 아래에만 붙어 있습니다.** 워커 2대에는 포트 매핑이 없습니다. NodePort 는 원래 모든 노드에서 열리지만, 호스트에서 `localhost:30080` 으로 들어온 트래픽은 오직 control-plane 노드를 통해서만 클러스터에 도달한다는 뜻입니다. 파드가 워커에 떠 있어도 kube-proxy 가 알아서 전달해 주므로 실습에는 문제가 없습니다.
- 이 매핑은 **클러스터를 만들 때만 적용됩니다.** 나중에 다른 포트가 필요해지면 파일을 고친 뒤 클러스터를 지우고 다시 만들어야 합니다.

```yaml file="./kind-cluster.yaml"
```

### create-cluster.sh

가장 먼저 실행하는 스크립트입니다. 클러스터 생성 → (선택) 프록시 CA 주입 → 상태 확인까지 한 번에 처리합니다.

- 맨 앞에서 `command -v kind` / `command -v kubectl` 로 필수 도구를 검사하고, 없으면 설치 안내와 함께 즉시 종료합니다. `set -euo pipefail` 이 걸려 있어 중간에 실패하면 조용히 넘어가지 않습니다.
- `kind get clusters | grep -qx "$CLUSTER_NAME"` 로 **이미 `learn` 클러스터가 있는지 먼저 확인**합니다. 있으면 재생성하지 않으므로 여러 번 실행해도 안전합니다. 없을 때만 `kind create cluster --config "$HERE/kind-cluster.yaml" --wait 120s` 를 실행하는데, `--wait 120s` 는 control-plane 이 Ready 가 될 때까지 최대 120초를 기다린다는 뜻입니다.
- 이어서 `kubectl config use-context "kind-learn"` 로 현재 컨텍스트를 자동 전환합니다. 다른 클러스터를 쓰고 있었다면 **이 시점에 kubectl 의 대상이 바뀐다**는 점을 기억하세요.
- `INJECT_CA=1` 로 실행했을 때만 동작하는 블록이 사내망 대응입니다. `openssl s_client -connect "${PROXY_HOST}:443" -showcerts` 로 인증서 체인을 뽑아, 각 노드 컨테이너에 `docker cp` 로 `/usr/local/share/ca-certificates/corp-proxy.crt` 를 넣고 `update-ca-certificates` 후 `containerd` 를 재시작합니다. `PROXY_HOST` 는 기본값이 `auth.docker.io` 이며 필요하면 바꿔 지정할 수 있습니다. CA 추출에 실패하면(`! -s "$TMP_CHAIN"`) 경고만 남기고 주입을 건너뜁니다.
- 마지막 "스모크 테스트"가 이 스크립트의 백미입니다. `kubectl run smoke --image=nginx:1.27-alpine` 로 파드를 하나 띄우고, 3초 간격으로 최대 20번(≈60초) `.status.phase` 를 확인해 `Running` 이 되는지 봅니다. 성공하면 이미지 pull 과 파드 기동이 모두 정상이라는 뜻이고, 실패하면 `describe pod` 로 원인을 보라는 안내와 함께 `INJECT_CA=1` 재시도를 권합니다. 확인이 끝나면 `kubectl delete pod smoke` 로 테스트 파드를 지우므로 클러스터에는 아무것도 남지 않습니다.

```bash file="./create-cluster.sh"
```

### delete-cluster.sh

실습을 마치거나 클러스터를 초기화하고 싶을 때 실행하는 정리 스크립트입니다.

- `kind get clusters | grep -qx "learn"` 로 대상 클러스터가 실제로 존재할 때만 `kind delete cluster --name learn` 을 실행합니다. 없으면 "삭제할 클러스터가 없습니다"만 출력하고 정상 종료하므로, 중복 실행해도 에러가 나지 않습니다.
- **되돌릴 수 없는 파괴적 명령입니다.** 노드 컨테이너·볼륨·kubeconfig 컨텍스트가 모두 사라지며, 그 안에 만들어 둔 Deployment·PVC·Secret 등 모든 리소스도 함께 없어집니다. 실습 중 저장하고 싶은 매니페스트가 있다면 미리 파일로 빼 두세요.
- 클러스터가 이상하게 꼬였을 때(노드 NotReady, 이미지 pull 실패 등)는 원인을 파고들기보다 `./delete-cluster.sh && ./create-cluster.sh` 로 다시 만드는 편이 훨씬 빠릅니다.

```bash file="./delete-cluster.sh"
```
