# Step 06 — 서비스와 DNS

> **학습 목표**
> - Service 가 왜 필요한지(파드 IP 는 계속 바뀐다) 이해한다
> - ClusterIP·NodePort·LoadBalancer·Headless 네 타입의 차이를 실습으로 구분한다
> - 서비스 DNS 이름(`<svc>.<ns>.svc.cluster.local`)으로 통신한다
> - Endpoints/EndpointSlice 가 "서비스 → 파드" 연결의 실체임을 안다
> - **셀렉터 오타 한 글자로 트래픽이 0 이 되는** 함정을 직접 재현하고 진단·수리한다
>
> **선행 스텝**: [Step 05 — 디플로이먼트](../step-05-deployments/)
> **예상 소요**: 55분

---

## 6-1. 왜 Service 인가 — 파드 IP 는 못 믿는다

Step 05 에서 파드는 언제든 죽고 새로 생깁니다. 그때마다 **파드 IP 가 바뀝니다**. 롤링 업데이트를 하면 IP 3개가 통째로 갈립니다. 그러면 이 파드들에 접속하려는 쪽은 매번 새 IP 를 어떻게 알까요?

**Service** 는 "변하지 않는 안정적인 접속 지점"을 제공합니다. 서비스는 레이블 셀렉터로 파드 묶음을 고르고, 그 앞에 **고정 가상 IP(ClusterIP)와 DNS 이름**을 세웁니다. 뒤의 파드가 아무리 바뀌어도 앞의 이름/IP 는 그대로입니다. 들어온 요청은 살아있는 파드들로 **자동 분산**됩니다.

이 스텝의 백엔드는 응답에 자기 파드 이름을 넣어주는 `hello-kubernetes` 3개입니다.

```yaml
# manifests/backend.yaml (발췌)
apiVersion: apps/v1
kind: Deployment
metadata: { name: hello, namespace: step06 }
spec:
  replicas: 3
  selector: { matchLabels: { app: hello } }
  template:
    metadata:
      labels: { app: hello }     # <-- 서비스 셀렉터가 찾을 레이블
    spec:
      containers:
        - name: hello
          image: paulbouwer/hello-kubernetes:1.10.1
          ports: [{ containerPort: 8080 }]
```

```bash
kubectl apply -f manifests/backend.yaml
kubectl rollout status deployment/hello -n step06
```

---

## 6-2. 네 가지 서비스 타입

한 백엔드(`app=hello`)를 네 가지로 노출합니다.

```yaml
# manifests/services.yaml (발췌)
apiVersion: v1
kind: Service
metadata: { name: hello-clusterip, namespace: step06 }
spec:
  type: ClusterIP            # 기본 타입, 클러스터 내부 전용
  selector: { app: hello }
  ports:
    - { name: http, port: 80, targetPort: 8080 }   # 서비스 80 → 컨테이너 8080
```

`port` 는 서비스가 여는 포트, `targetPort` 는 실제 컨테이너 포트입니다. 둘은 달라도 됩니다.

```bash
kubectl apply -f manifests/services.yaml
kubectl get svc -n step06 -o wide
```

**실행 결과**
```
NAME              TYPE           CLUSTER-IP      EXTERNAL-IP   PORT(S)        SELECTOR
hello-clusterip   ClusterIP      10.96.119.109   <none>        80/TCP         app=hello
hello-headless    ClusterIP      None            <none>        80/TCP         app=hello
hello-lb          LoadBalancer   10.96.13.175    <pending>     80:31609/TCP   app=hello
hello-nodeport    NodePort       10.96.89.111    <none>        80:30443/TCP   app=hello
```

| 타입 | 접근 범위 | 특징 |
|---|---|---|
| **ClusterIP** | 클러스터 내부만 | 기본값. 고정 가상 IP + DNS |
| **NodePort** | 외부(노드 IP:포트) | 모든 노드의 30000~32767 포트 하나를 연다 |
| **LoadBalancer** | 외부(전용 IP) | 클라우드 LB 를 프로비저닝. kind 엔 없어 `pending` |
| **Headless** | 내부(파드 IP 직접) | `clusterIP: None`. 로드밸런싱 없이 DNS 로 파드 IP 나열 |

---

## 6-3. ClusterIP + 서비스 DNS

클러스터 안에서는 서비스 이름이 곧 DNS 이름입니다. 정식 이름은 `<svc>.<ns>.svc.cluster.local` 이고, **같은 네임스페이스 안에서는 `<svc>` 짧은 이름만으로도** 됩니다.

테스트용 클라이언트 파드를 띄워 확인합니다.

```bash
kubectl run client --image=busybox:1.36 -n step06 --restart=Never --command -- sleep 3600
kubectl exec -n step06 client -- nslookup hello-clusterip.step06.svc.cluster.local
```
```
Name:	hello-clusterip.step06.svc.cluster.local
Address: 10.96.119.109        <- 서비스의 고정 가상 IP 하나
```

DNS 는 서비스의 **가상 IP 하나**를 돌려줍니다. 이 IP 로 보낸 트래픽을 노드의 커널(kube-proxy 규칙)이 살아있는 파드로 분산합니다. HTTP 로 접근해 봅시다.

```bash
kubectl exec -n step06 client -- wget -qO- http://hello-clusterip/ | grep -A1 pod:
```
```
pod=hello-7f48fdfbbd-9ts99      <- 요청이 백엔드 파드 중 하나로 도달
```

> 💡 **실무 팁**: 다른 네임스페이스의 서비스를 부를 땐 반드시 `<svc>.<ns>` 를 붙이세요(`hello-clusterip.step06`). 짧은 이름은 "같은 네임스페이스"에서만 통합니다. 이 실수가 "로컬에선 되는데 배포하면 연결이 안 되는" 흔한 원인입니다.

---

## 6-4. NodePort — 외부에서 접근

NodePort 는 모든 노드에 고정 포트를 열어 클러스터 밖에서 들어올 수 있게 합니다. 이 학습용 kind 클러스터는 노드 포트 **30080**(및 30443)을 호스트로 매핑해 두어, 브라우저에서 `http://localhost:30080` 으로 바로 닿습니다.

```yaml
# manifests/services.yaml (NodePort 발췌)
spec:
  type: NodePort
  selector: { app: hello }
  ports:
    - { name: http, port: 80, targetPort: 8080, nodePort: 30080 }
```

```bash
# 호스트(내 노트북)에서 직접
curl -s http://localhost:30080/ | grep -A1 pod:
# 여러 번 호출하면 요청이 3개 파드로 분산된다
for i in $(seq 1 8); do curl -s http://localhost:30080/ \
  | grep -A1 '<th>pod:</th>' | grep td; done | sort | uniq -c
```

**실행 결과** (요청 8번이 3개 파드로 분산됨)
```
   2 <td>hello-7f48fdfbbd-8xv5j</td>
   2 <td>hello-7f48fdfbbd-9ts99</td>
   4 <td>hello-7f48fdfbbd-lc7cz</td>
```

같은 URL 을 반복 호출했는데 응답하는 파드가 매번 다릅니다. 이것이 서비스의 로드밸런싱입니다.

> ⚠️ **함정 (NodePort 는 클러스터 전역 자원)**: `nodePort` 번호는 **클러스터 전체에서 유일**해야 합니다. 이미 누가 30080 을 쓰고 있으면 apply 가 이렇게 거부됩니다.
> ```
> The Service "hello-nodeport" is invalid: spec.ports[0].nodePort:
>   Invalid value: 30080: provided port is already allocated
> ```
> 이 교재를 검증할 당시 다른 실습(step25)이 30080 을 점유하고 있어, 검증은 똑같이 호스트로 매핑된 **30443** 으로 했습니다(위 결과의 파드 분산은 30443 으로 확인). 혼자 실습하면 30080 이 그대로 열립니다. `nodePort` 를 생략하면 Kubernetes 가 범위 안에서 비어있는 포트를 알아서 골라 충돌을 피합니다.

---

## 6-5. LoadBalancer — kind 에선 왜 pending 인가

`LoadBalancer` 타입은 클라우드(AWS·GCP 등)에 **외부 IP 를 가진 로드밸런서를 만들어 달라**고 요청합니다. 그 요청을 처리하는 건 클라우드 프로바이더의 컨트롤러입니다.

kind 는 노트북 위 로컬 클러스터라 **그런 프로바이더가 없습니다.** 그래서 `EXTERNAL-IP` 가 영원히 `pending` 입니다.

```bash
kubectl get svc hello-lb -n step06
```
```
NAME       TYPE           CLUSTER-IP     EXTERNAL-IP   PORT(S)        AGE
hello-lb   LoadBalancer   10.96.13.175   <pending>     80:31609/TCP   3m33s
```

주목할 점: pending 이어도 **ClusterIP 와 NodePort(31609)는 이미 동작**합니다. LoadBalancer 는 사실 NodePort 위에 외부 LB 를 얹는 구조라, 외부 IP 만 못 받았을 뿐 내부 접근은 됩니다.

> 💡 **실무 팁**: 로컬에서 LoadBalancer 를 진짜로 테스트하려면 `metallb` 같은 것을 설치하거나, 학습 목적이면 그냥 NodePort/`kubectl port-forward` 를 쓰세요. 실제 클라우드(EKS/GKE)에선 이 타입이 자동으로 외부 IP 를 받습니다.

---

## 6-6. Headless 서비스 — 파드 IP 를 직접

`clusterIP: None` 으로 만들면 **가상 IP 가 없는** Headless 서비스가 됩니다. DNS 조회 시 서비스 IP 하나가 아니라 **뒤에 있는 파드 IP 전부**를 돌려줍니다.

```bash
kubectl exec -n step06 client -- nslookup hello-headless.step06.svc.cluster.local
```
```
Name:	hello-headless.step06.svc.cluster.local
Address: 10.244.2.77       <- 파드 IP 1
Name:	hello-headless.step06.svc.cluster.local
Address: 10.244.2.78       <- 파드 IP 2
Name:	hello-headless.step06.svc.cluster.local
Address: 10.244.1.78       <- 파드 IP 3
```

앞의 ClusterIP 서비스가 **가상 IP 하나**를 준 것과 대조됩니다. 클라이언트가 개별 파드를 직접 골라야 할 때(예: 각 파드에 고유 ID 가 있는 StatefulSet, 카프카·DB 클러스터) 씁니다. Step 11(StatefulSet)에서 다시 만납니다.

---

## 6-7. Endpoints / EndpointSlice — 연결의 실체

"서비스 → 어떤 파드들"의 실제 매핑은 **Endpoints**(구식)와 **EndpointSlice**(신식)에 들어있습니다. 서비스가 셀렉터로 고른 파드 중 **준비된(ready) 것들의 IP:포트 목록**입니다.

```bash
kubectl get endpoints -n step06
```
```
NAME              ENDPOINTS
hello-clusterip   10.244.1.78:8080,10.244.2.77:8080,10.244.2.78:8080
hello-headless    10.244.1.78:8080,10.244.2.77:8080,10.244.2.78:8080
```

파드가 죽거나 준비 안 되면 이 목록에서 자동으로 빠집니다. **서비스가 트래픽을 못 보내면 십중팔구 이 Endpoints 가 비어있습니다.** 다음 절에서 그걸 재현합니다.

> 💡 **실무 팁**: 최신 클러스터는 **EndpointSlice**(`discovery.k8s.io/v1`)를 씁니다. `kubectl get endpoints` 는 "deprecated" 경고를 냅니다. 진단할 땐 `kubectl get endpointslice -n step06` 를 쓰세요. 둘 다 같은 정보를 담습니다.

---

## 6-8. ⚠️ 함정: 셀렉터 오타 한 글자 → 트래픽 0

가장 흔하고 악명 높은 함정입니다. 셀렉터를 `app: helo`(오타)로 준 서비스를 apply 합니다.

```yaml
# manifests/broken-service.yaml (발췌)
spec:
  selector:
    app: helo          # <-- 오타! 실제 파드는 app=hello
```

```bash
kubectl apply -f manifests/broken-service.yaml
```
```
service/hello-broken created      <- YAML 은 아무 문제 없이 생성됨
```

**apply 는 성공합니다.** 에러 한 줄 없습니다. 하지만 Endpoints 를 보면 텅 비어있습니다.

```bash
kubectl get endpoints hello-broken -n step06
```
```
NAME           ENDPOINTS
hello-broken   <none>            <- 매칭되는 파드가 0개
```

접근하면 연결이 안 됩니다(엔드포인트가 없어 거절).

```bash
kubectl exec -n step06 client -- wget -qO- --timeout=2 http://hello-broken/
```
```
wget: can't connect to remote host (10.96.114.248): Connection refused
```

**진단 순서**: 서비스는 있는데 트래픽이 0 이면 → ① `kubectl get endpoints <svc>` 로 비었는지 확인 → ② 비었으면 서비스 셀렉터와 파드 레이블을 나란히 비교.

```bash
kubectl get svc hello-broken hello-clusterip -n step06 \
  -o custom-columns='NAME:.metadata.name,SELECTOR:.spec.selector'
```
```
NAME              SELECTOR
hello-broken      map[app:helo]     <- 오타
hello-clusterip   map[app:hello]    <- 정상
```

한 글자 차이가 보입니다. 고칩니다.

```bash
kubectl patch svc hello-broken -n step06 -p '{"spec":{"selector":{"app":"hello"}}}'
kubectl get endpoints hello-broken -n step06
```
```
service/hello-broken patched
NAME           ENDPOINTS
hello-broken   10.244.1.78:8080,10.244.2.77:8080,10.244.2.78:8080   <- 즉시 채워짐
```

셀렉터를 고치자마자 Endpoints 가 채워지고 트래픽이 흐릅니다.

> ⚠️ **함정 정리**: 서비스 셀렉터와 파드 레이블은 **글자 하나까지 정확히** 일치해야 합니다. `app: web` vs `app: Web`(대소문자), `tier: frontend` vs `tier: front-end`(하이픈) 전부 매칭 실패입니다. 그리고 이건 **apply 시점에 아무도 안 막아줍니다.** 배포 후 `kubectl get endpoints` 로 확인하는 습관이 유일한 방어선입니다.

---

## 정리

| 타입 | clusterIP | 외부 접근 | 쓰는 곳 |
|---|---|---|---|
| ClusterIP | 가상 IP 1개 | 불가(내부만) | 내부 서비스 간 통신(기본) |
| NodePort | 가상 IP + 노드포트 | 노드IP:30000~32767 | 간단한 외부 노출, 로컬 실습 |
| LoadBalancer | 가상 IP + 노드포트 + 외부 IP | 클라우드 LB | 프로덕션 외부 노출 |
| Headless | **None** | 내부(파드 IP 직접) | StatefulSet, 클러스터형 앱 |

핵심 진단 도구: `kubectl get endpoints <svc>` — 비어있으면 셀렉터를 의심하라.

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 07 — 설정과 시크릿](../step-07-config-secret/)

---

## 실습 파일

이 스텝은 파일 3개 + 스크립트 1개로 진행합니다. 먼저 `manifests/backend.yaml` 로 `step06` 네임스페이스와 백엔드 파드 3개를 띄우고(6-1), 이어서 `manifests/services.yaml` 로 같은 백엔드를 ClusterIP·NodePort·LoadBalancer·Headless 네 타입으로 동시에 노출합니다(6-2~6-6). 마지막에 `manifests/broken-service.yaml` 로 셀렉터 오타 함정을 재현하고 진단·수리합니다(6-8). `commands.sh` 는 이 흐름을 강의 순서 그대로 담은 명령 모음입니다.

### manifests/backend.yaml

서비스가 트래픽을 보낼 **대상 파드**를 만드는 매니페스트입니다. 6-1 절 맨 처음에 `kubectl apply -f manifests/backend.yaml` 로 적용합니다.

- 한 파일에 문서 두 개(`---` 로 구분)가 들어 있어 **`Namespace: step06` 을 먼저 만들고** 그 안에 `Deployment: hello` 를 넣습니다. 이 파일을 가장 먼저 적용해야 하는 이유가 이것입니다 — 네임스페이스가 없으면 뒤의 서비스 매니페스트가 전부 실패합니다.
- `replicas: 3` 이 로드밸런싱을 눈으로 보게 해 주는 장치입니다. 파드가 3개라 6-4 절에서 `curl` 을 8번 날렸을 때 응답이 세 파드로 갈립니다.
- `template.metadata.labels` 의 `app: hello` 가 **이 스텝 전체의 핵심 키**입니다. `services.yaml` 의 네 서비스가 전부 이 레이블을 셀렉터로 찾고, `broken-service.yaml` 은 이 값을 `helo` 로 잘못 적어 함정을 만듭니다.
- 이미지는 `paulbouwer/hello-kubernetes:1.10.1` 이고 `containerPort: 8080` 을 엽니다. 이 8080 이 서비스의 `targetPort` 와 짝을 이룹니다.
- `env` 의 `KUBERNETES_POD_NAME` 은 `fieldRef` 로 `metadata.name` 을 주입해 파드가 **응답 HTML 에 자기 이름을 찍게** 합니다. 6-4 절의 `grep -A1 '<th>pod:</th>'` 가 이 값을 뽑아내는 것입니다.

```yaml file="./manifests/backend.yaml"
```

### manifests/services.yaml

백엔드가 준비된 뒤 적용하는, **한 백엔드를 네 가지 타입으로 노출**하는 매니페스트입니다. 6-2 절의 `kubectl apply -f manifests/services.yaml` 에 해당하며, 6-3~6-6 절이 여기서 만든 서비스를 하나씩 관찰합니다.

- **`hello-clusterip`** (`type: ClusterIP`): `port: 80` → `targetPort: 8080` 매핑이 핵심입니다. 서비스는 80 으로 받아 컨테이너의 8080 으로 넘깁니다. 6-3 절에서 `wget http://hello-clusterip/` 가 포트 없이 되는 이유가 `port: 80` 이기 때문입니다.
- **`hello-nodeport`** (`type: NodePort`): `nodePort: 30080` 을 **명시**했습니다. kind 클러스터가 30080 을 호스트로 매핑해 둔 덕에 노트북 브라우저에서 `http://localhost:30080` 이 바로 열립니다. 다만 `nodePort` 는 클러스터 전역 자원이라 이미 점유돼 있으면 apply 가 `provided port is already allocated` 로 거부됩니다(6-4 절 함정 박스). 충돌이 나면 이 줄을 지워 Kubernetes 가 알아서 고르게 하면 됩니다.
- **`hello-lb`** (`type: LoadBalancer`): `nodePort` 를 안 적었으므로 Kubernetes 가 임의 포트(예시 결과에선 31609)를 배정합니다. kind 에는 LB 프로바이더가 없어 `EXTERNAL-IP` 가 계속 `pending` 이지만, 내부적으로 ClusterIP + NodePort 를 함께 만들기 때문에 클러스터 안에서는 정상 동작합니다(6-5 절).
- **`hello-headless`** (`clusterIP: None`): `type` 을 안 적어 기본 ClusterIP 지만 `clusterIP: None` 한 줄이 이 서비스를 **Headless** 로 바꿉니다. 가상 IP 가 없어 DNS 조회가 파드 IP 3개를 그대로 나열합니다(6-6 절).
- 네 서비스 모두 셀렉터가 `app: hello` 로 **완전히 같습니다.** 하나의 파드 묶음을 노출 방식만 달리해 비교하는 구성이라, 6-7 절에서 `kubectl get endpoints` 를 찍으면 서로 다른 서비스가 똑같은 엔드포인트 목록을 갖는 것이 보입니다.

```yaml file="./manifests/services.yaml"
```

### manifests/broken-service.yaml

**의도적으로 망가뜨린 실습 파일**입니다. 6-8 절에서 적용해 "가장 흔한 서비스 장애"를 재현합니다.

- 잘못된 곳은 딱 한 줄, `selector.app` 이 `hello` 가 아니라 **`helo`(l 하나 빠진 오타)** 입니다. 나머지(`type: ClusterIP`, `port: 80`, `targetPort: 8080`)는 `hello-clusterip` 과 똑같습니다.
- **증상이 고약한 이유**: `kubectl apply` 는 `service/hello-broken created` 를 찍고 **성공합니다.** 스키마상 아무 문제가 없기 때문에 API 서버도, 검증 도구도 막아주지 않습니다.
- 대신 `kubectl get endpoints hello-broken -n step06` 이 `<none>` 을 반환합니다. `app=helo` 레이블을 가진 파드가 0개라 엔드포인트가 채워지지 않는 것입니다.
- 실제 접속하면 `wget: can't connect to remote host (...): Connection refused` — 서비스 IP 는 존재하는데 보낼 곳이 없어 커널이 즉시 거절합니다.
- **수리**는 셀렉터만 고치면 됩니다: `kubectl patch svc hello-broken -n step06 -p '{"spec":{"selector":{"app":"hello"}}}'`. patch 직후 엔드포인트가 즉시 채워집니다. 여기서 얻어갈 습관은 하나입니다 — **서비스가 먹통이면 제일 먼저 `kubectl get endpoints <svc>` 를 보라.**

```yaml file="./manifests/broken-service.yaml"
```

### commands.sh

강의 본문 6-1 부터 6-8 까지의 명령을 **등장 순서 그대로** 모아 둔 스크립트입니다. 통째로 실행하기보다 한 줄씩 복사해 붙여 넣으며 출력을 관찰하는 용도입니다.

- 맨 앞의 `kubectl config current-context` 로 **kind-learn 클러스터가 맞는지 먼저 확인**합니다. 다른 컨텍스트(운영 클러스터!)에 붙어 있으면 여기서 멈춰야 합니다.
- 6-3 절 블록의 `kubectl wait --for=condition=Ready pod/client -n step06 --timeout=60s` 가 중요합니다. `kubectl run` 직후 곧바로 `exec` 하면 파드가 아직 안 떠서 실패하므로, Ready 를 기다린 뒤 `nslookup`/`wget` 을 실행합니다.
- 6-4 절의 `for i in $(seq 1 8); do curl ... ; done | sort | uniq -c` 는 8번 요청을 파드 이름별로 집계해 **로드밸런싱을 수치로 보여주는** 한 줄입니다. 이 부분만 `kubectl exec` 가 아니라 **호스트에서 직접** `curl localhost:30080` 을 때린다는 점에 유의하세요.
- `kubectl get endpoints` 와 `kubectl get endpointslice` 를 **둘 다** 찍습니다(6-7 절 내용). 다만 스크립트에서는 별도 블록이 아니라 맨 앞 `6-1~2` 블록의 `kubectl apply -f manifests/services.yaml` 바로 다음에 붙어 있습니다 — 서비스를 만들자마자 엔드포인트가 채워지는지 확인하는 순서이기 때문입니다. 전자는 deprecated 경고를 내지만 같은 정보를 보여주므로 비교해 보라는 의도입니다.
- ⚠️ **주의**: 맨 마지막 줄 `kubectl delete namespace step06` 은 이 스텝에서 만든 리소스를 **전부 삭제**합니다. `challenge.md` 의 연습 과제를 아직 안 풀었다면 이 줄은 실행하지 마세요.

```bash file="./commands.sh"
```
