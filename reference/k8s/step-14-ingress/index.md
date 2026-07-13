# Step 14 — 인그레스 (Ingress)

> **학습 목표**
> - Ingress 가 무엇이고 Service(특히 NodePort/LoadBalancer)와 무엇이 다른지(L7 vs L4) 이해한다.
> - **Ingress 오브젝트만으로는 아무 일도 안 일어난다** — Ingress 컨트롤러가 있어야 동작한다는 것을 체감한다.
> - kind 에 ingress-nginx 컨트롤러를 설치한다.
> - 호스트 기반 / 경로 기반 라우팅, 여러 서비스로 팬아웃, `pathType`(Prefix/Exact), TLS(HTTPS), 기본 백엔드를 직접 만들고 검증한다.
>
> **선행 지식**: Step 06(서비스와 DNS). Deployment/Service 개념.
> **소요 시간**: 40~60분

---

## 0. Ingress 란 무엇인가

Service `type: NodePort` / `LoadBalancer` 는 **L4**(TCP/포트) 수준의 노출입니다. 서비스마다 포트(또는 LB)가 하나씩 필요하고, "URL 경로"나 "도메인 이름"으로 트래픽을 나눌 수 없습니다.

**Ingress 는 L7(HTTP/HTTPS) 라우터**입니다. 하나의 진입점(IP/포트) 뒤에서

- `Host: a.example.com` 은 A 서비스로, `Host: b.example.com` 은 B 서비스로 (호스트 기반)
- `/tea` 는 tea 서비스로, `/coffee` 는 coffee 서비스로 (경로 기반)
- `https://` 요청의 TLS 를 종료(복호화)하고 뒤의 평문 서비스로 전달 (TLS termination)

같은 라우팅을 선언적으로 처리합니다.

```
                         ┌──────────────────────── Ingress 컨트롤러 (nginx) ────────────────────────┐
  브라우저  ── HTTP/HTTPS ─▶│  Host/Path 를 보고 규칙 매칭                                             │
                         │    cafe.example.com/tea    ─▶ Service tea    ─▶ Pod(tea)                 │
                         │    cafe.example.com/coffee ─▶ Service coffee ─▶ Pod(coffee)              │
                         │    tea.example.com/        ─▶ Service tea                                │
                         │    (매칭 없음)             ─▶ default backend / 404                       │
                         └──────────────────────────────────────────────────────────────────────┘
```

**핵심 구분**

| | 무엇 | 역할 |
|---|---|---|
| **Ingress (오브젝트)** | 우리가 쓰는 YAML(규칙표) | "이런 규칙으로 라우팅해줘" 라는 **선언**일 뿐. 그 자체로는 트래픽을 처리 못 함 |
| **Ingress 컨트롤러** | 실제로 도는 nginx 파드 | Ingress 오브젝트를 읽어 자기 설정(nginx.conf)으로 반영하고 **실제 트래픽을 처리** |
| **IngressClass** | 어떤 컨트롤러가 처리할지 지정 | `ingressClassName: nginx` |

> ⚠️ **가장 흔한 함정**: Ingress YAML 을 `apply` 하고 `curl` 이 안 된다고 당황합니다. **컨트롤러가 없으면 Ingress 는 그냥 데이터일 뿐입니다.** (아래 5절에서 직접 재현)

---

## 1. ingress-nginx 컨트롤러 설치 (kind 전용)

kind 공식 provider 매니페스트를 씁니다. 컨트롤러/인증서 발급 Job 이미지를 미리 노드에 로드한 뒤 적용합니다.

```bash
# 1) kind 전용 매니페스트 내려받기
curl -sSL -o ingress-nginx-kind.yaml \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml

# 2) 이미지 2개를 호스트로 pull 후 kind 노드로 로드 (사내 프록시로 노드 직접 pull 이 막힐 때)
docker pull registry.k8s.io/ingress-nginx/controller:v1.11.3
docker pull registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.4.4
kind load docker-image --name learn registry.k8s.io/ingress-nginx/controller:v1.11.3
kind load docker-image --name learn registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.4.4

# 3) 적용
kubectl apply -f ingress-nginx-kind.yaml

# 4) 컨트롤러가 Ready 될 때까지 대기
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s
```

**결과 (이 환경에서 실제)**

```
pod/ingress-nginx-controller-78657859f8-dtngh condition met
```

```bash
kubectl get pods -n ingress-nginx
```
```
NAME                                        READY   STATUS      RESTARTS   AGE
ingress-nginx-admission-create-tt4gg        0/1     Completed   0          9m13s
ingress-nginx-admission-patch-v5ggx         0/1     Completed   0          9m13s
ingress-nginx-controller-78657859f8-dtngh   1/1     Running     0          9m13s
```

`ingressClass` 도 함께 생깁니다. 앞으로 만들 모든 Ingress 는 이 클래스를 씁니다.

```bash
kubectl get ingressclass
```
```
NAME    CONTROLLER             PARAMETERS   AGE
nginx   k8s.io/ingress-nginx   <none>       9m13s
```

> **`admission-create` / `admission-patch` 가 `Completed` 인 건 정상입니다.** ValidatingWebhook 용 인증서를 발급하는 일회성 Job 이고, 다 하면 종료됩니다. Running 이 아니라고 실패로 오해하지 마세요.

### 이 환경에서 겪은 실제 이슈 2가지 (정직하게 기록)

1. **`ingress-ready` 레이블 누락 → 컨트롤러 Pending.** kind provider 매니페스트의 컨트롤러는 `nodeSelector: ingress-ready=true` 를 요구합니다. 클러스터 정의(`cluster/kind-cluster.yaml`)는 control-plane 에 이 레이블을 주도록 되어 있지만, **실제 클러스터에는 붙어 있지 않아** 컨트롤러가 `0/3 nodes ... didn't match node affinity/selector` 로 Pending 이었습니다. 해결:
   ```bash
   kubectl label node learn-control-plane ingress-ready=true --overwrite
   ```
2. **컨트롤러 이미지가 멀티아키(index) 라 `kind load` 가 실패**할 수 있습니다(`content digest ... not found`). 이때는 단일 아키텍처로 저장해서 노드에 직접 import 합니다.
   ```bash
   docker save registry.k8s.io/ingress-nginx/controller:v1.11.3 -o controller.tar
   for n in learn-control-plane learn-worker learn-worker2; do
     docker exec -i $n ctr -n k8s.io images import - < controller.tar
   done
   ```

---

## 2. 실습 백엔드 3종 배포

라우팅 결과를 눈으로 구분하려고 응답 문자열이 다른 서비스 3개를 씁니다.

- `tea` : `hashicorp/http-echo` 가 항상 `tea` 를 응답
- `coffee` : 항상 `coffee` 를 응답
- `hello` : `paulbouwer/hello-kubernetes` 데모 웹 (호스트 기반/기본 백엔드용)

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-apps.yaml
kubectl wait -n step14 --for=condition=available deploy --all --timeout=120s
kubectl get pods -n step14
```
```
NAME                      READY   STATUS    RESTARTS   AGE
coffee-7468f9c498-4g29c   1/1     Running   0          1s
coffee-7468f9c498-zdkg9   1/1     Running   0          1s
hello-d7cc9496c-szmpk     1/1     Running   0          0s
tea-f696f578d-kgtdq       1/1     Running   0          1s
tea-f696f578d-mwkjp       1/1     Running   0          1s
```

---

## 3. 컨트롤러에 접근하는 법 (이 환경 주의)

원래 코스 클러스터 정의는 호스트 `8080→80`, `8443→443` 포트매핑으로 `http://localhost:8080` 접근을 의도합니다. **그러나 지금 떠 있는 실제 클러스터에는 그 매핑이 적용돼 있지 않았습니다**(control-plane 컨테이너에 6443/30080/30443 만 노출). 또한 NodePort 30080 은 다른 스텝이 이미 점유 중이었습니다.

그래서 이 교재의 검증은 **어떤 클러스터에서도 재현 가능한 `kubectl port-forward`** 로 했습니다. 정상 매핑 환경이라면 `localhost:8080`, `localhost:8443` 을 그대로 쓰면 됩니다.

```bash
# 컨트롤러 서비스를 로컬 18080(HTTP)/18443(HTTPS) 로 포워딩
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 18080:80 18443:443
```

DNS 를 안 건드리려고, 라우팅은 `curl -H "Host: ..."` 로 호스트 헤더를 직접 넣어 확인합니다(브라우저로 하려면 `/etc/hosts` 에 `127.0.0.1 cafe.example.com` 을 추가).

---

## 4. 라우팅 실습

### 4-1. 경로 기반 팬아웃 (`manifests/20-ingress-path.yaml`)

`cafe.example.com` 하나에서 `/tea`, `/coffee` 로 서로 다른 서비스에 팬아웃합니다.

```yaml
spec:
  ingressClassName: nginx
  rules:
    - host: cafe.example.com
      http:
        paths:
          - path: /tea
            pathType: Prefix
            backend: { service: { name: tea, port: { number: 80 } } }
          - path: /coffee
            pathType: Prefix
            backend: { service: { name: coffee, port: { number: 80 } } }
```

```bash
kubectl apply -f manifests/20-ingress-path.yaml
# port-forward 켠 상태에서:
curl -H "Host: cafe.example.com" http://localhost:18080/tea
curl -H "Host: cafe.example.com" http://localhost:18080/coffee
```
```
tea
coffee
```

`describe` 로 규칙이 어떻게 프로그래밍됐는지 봅니다(백엔드 뒤 IP 는 실제 파드 엔드포인트).

```bash
kubectl describe ingress cafe-path -n step14
```
```
Name:             cafe-path
Namespace:        step14
Address:          localhost
Ingress Class:    nginx
Default backend:  <default>
Rules:
  Host              Path  Backends
  ----              ----  --------
  cafe.example.com
                    /tea      tea:80 (10.244.1.70:5678,10.244.2.72:5678)
                    /coffee   coffee:80 (10.244.2.73:5678,10.244.1.71:5678)
Annotations:        nginx.ingress.kubernetes.io/rewrite-target: /
```

### 4-2. 호스트 기반 라우팅 (`manifests/21-ingress-host.yaml`)

같은 경로 `/` 라도 **Host 헤더**가 다르면 다른 서비스로 갑니다.

```bash
kubectl apply -f manifests/21-ingress-host.yaml
curl -H "Host: tea.example.com"   http://localhost:18080/
curl -H "Host: hello.example.com" http://localhost:18080/ | grep -o "Hello from the hello service"
```
```
tea
Hello from the hello service
```

### 4-3. `pathType` — Prefix vs Exact (`manifests/22-ingress-pathtype.yaml`)

- `Exact` : 경로가 **정확히** 일치해야 함. `/exact` 는 매치, `/exact/foo` 는 불일치.
- `Prefix` : 경로 세그먼트 **접두**가 일치하면 됨. `/prefix`, `/prefix/`, `/prefix/foo` 모두 매치.

```bash
kubectl apply -f manifests/22-ingress-pathtype.yaml
curl -o /dev/null -w "%{http_code}\n" -H "Host: pathtype.example.com" http://localhost:18080/exact       # Exact
curl -o /dev/null -w "%{http_code}\n" -H "Host: pathtype.example.com" http://localhost:18080/exact/foo   # Exact
curl -o /dev/null -w "%{http_code}\n" -H "Host: pathtype.example.com" http://localhost:18080/prefix       # Prefix
curl -o /dev/null -w "%{http_code}\n" -H "Host: pathtype.example.com" http://localhost:18080/prefix/foo   # Prefix
```
```
200      # /exact      → 매치
404      # /exact/foo  → Exact 라 불일치
200      # /prefix     → 매치
200      # /prefix/foo → Prefix 라 매치
```

### 4-4. 기본 백엔드 (`manifests/23-ingress-default-backend.yaml`)

**규칙(rules) 없이 `defaultBackend` 만** 가진 Ingress 는 ingress-nginx 에서 **글로벌 catch-all** 이 됩니다. 어떤 규칙에도 안 걸린 요청이 기본 404 대신 이 백엔드로 갑니다.

```bash
# 적용 전: 매칭 없는 호스트는 nginx 기본 404
curl -o /dev/null -w "%{http_code}\n" -H "Host: nope.example.com" http://localhost:18080/   # → 404

kubectl apply -f manifests/23-ingress-default-backend.yaml

# 적용 후: 같은 요청이 hello 로
curl -o /dev/null -w "%{http_code}\n" -H "Host: nope.example.com" http://localhost:18080/   # → 200
curl -H "Host: nope.example.com" http://localhost:18080/ | grep -o "Hello from the hello service"
```
```
404
200
Hello from the hello service
```

```bash
kubectl describe ingress fallback -n step14
```
```
Name:             fallback
Namespace:        step14
Address:          localhost
Ingress Class:    nginx
Default backend:  hello:80 (10.244.1.72:8080)
Rules:
  Host        Path  Backends
  ----        ----  --------
  *           *     hello:80 (10.244.1.72:8080)
```

> ⚠️ **함정**: `defaultBackend` 를 "규칙이 있는 Ingress" 안에 같이 넣어도, 그 host 의 매치 안 되는 경로를 per-path 로 폴백해 주지는 **않습니다**(그건 404 로 떨어짐). 글로벌 catch-all 로 쓰려면 위처럼 **rules 없이 defaultBackend 만** 두세요. (직접 확인: `shop.example.com/tea` 규칙 + defaultBackend 를 걸어도 `shop.example.com/other` 는 404 였습니다.)

### 4-5. TLS / HTTPS (`manifests/24-ingress-tls.yaml`)

자체 서명 인증서를 `kubernetes.io/tls` Secret 에 담고, Ingress `spec.tls` 로 참조하면 컨트롤러가 **TLS 를 종료**하고 뒤의 평문 서비스로 넘깁니다.

```bash
# 자체 서명 인증서 생성 (CN/SAN = secure.example.com)
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout tls.key -out tls.crt \
  -subj "/CN=secure.example.com/O=step14" \
  -addext "subjectAltName=DNS:secure.example.com"

kubectl create secret tls tls-secret -n step14 --cert=tls.crt --key=tls.key
kubectl apply -f manifests/24-ingress-tls.yaml

# HTTPS 로 접근 (자체 서명이라 -k 로 검증 생략)
curl -k --resolve secure.example.com:18443:127.0.0.1 https://secure.example.com:18443/
```
```
tea
```

인증서가 우리가 넣은 것인지 확인:
```bash
curl -k -v --resolve secure.example.com:18443:127.0.0.1 https://secure.example.com:18443/ 2>&1 | grep -E "subject:|issuer:"
```
```
*  subject: CN=secure.example.com; O=step14
*  issuer: CN=secure.example.com; O=step14
```

---

## 5. 함정 재현 — "컨트롤러가 처리 안 하는 Ingress"

없는 클래스(`nonexistent-class`)를 가리키는 Ingress 를 만들면, **어떤 컨트롤러도 이를 채택하지 않습니다.**

```bash
kubectl apply -f - <<'EOF'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: orphan, namespace: step14 }
spec:
  ingressClassName: nonexistent-class
  rules:
    - host: orphan.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: tea, port: { number: 80 } } }
EOF

kubectl get ingress orphan -n step14
```
```
NAME     CLASS               HOSTS                ADDRESS   PORTS   AGE
orphan   nonexistent-class   orphan.example.com             80      6s
```

**`ADDRESS` 가 비어 있습니다.** nginx 컨트롤러는 이 Ingress 를 무시했고, `orphan.example.com` 으로 보낸 요청은 tea 로 가지 **않습니다**(위에서 만든 글로벌 default backend 로 떨어지거나, 없으면 404). 즉 **Ingress 를 만든다고 라우팅이 생기는 게 아니라, 그 클래스를 담당하는 컨트롤러가 있어야** 비로소 동작합니다.

```bash
kubectl delete ingress orphan -n step14
```

> 정상적으로 채택된 Ingress 는 `ADDRESS` 에 `localhost`(kind) 처럼 값이 채워집니다. Ingress 가 안 먹으면 **가장 먼저 `kubectl get ingress` 의 ADDRESS 와 `kubectl get ingressclass` 를 보세요.**

---

## 6. 팁 / 함정 정리

- ⚠️ **컨트롤러 없는 Ingress 는 무용지물.** ADDRESS 가 비어 있으면 컨트롤러/클래스 매칭을 의심하세요. (5절)
- ⚠️ **`ingressClassName` 오타/누락.** 기본 IngressClass 가 없으면 클래스를 안 쓴 Ingress 는 아무도 안 맡습니다. `kubectl get ingressclass` 로 확인.
- ⚠️ **Service 포트 불일치.** Ingress `backend.service.port.number` 는 **Service 의 port**(여기선 80)이지 컨테이너 포트(5678/8080)가 아닙니다. 헷갈리면 502/404 가 납니다.
- ⚠️ **`rewrite-target` 없이 `/tea` 를 그대로 넘기면** 실제 앱은 `/tea` 경로를 모를 수 있습니다. 경로를 떼서 넘기려면 `nginx.ingress.kubernetes.io/rewrite-target` 을 씁니다.
- ⚠️ **defaultBackend 는 rules 와 섞으면 per-path 폴백이 안 됨.** 글로벌 폴백은 rules 없는 Ingress 로. (4-4)
- 💡 **TLS Secret 은 `kubernetes.io/tls` 타입**이어야 하고 키 이름은 `tls.crt`/`tls.key` 고정. `kubectl create secret tls` 가 안전.
- 💡 라우팅이 애매하면 `kubectl describe ingress <name>` 의 **Backends 뒤 실제 파드 IP** 목록으로 엔드포인트가 붙었는지부터 확인.

---

## 7. 정리표

| 개념 | 필드/명령 | 한 줄 요약 |
|---|---|---|
| L7 라우팅 | `kind: Ingress` | Host/Path 로 HTTP 트래픽을 여러 Service 로 분배 |
| 컨트롤러 | `ingress-nginx` 파드 | Ingress 를 읽어 실제 트래픽 처리. **없으면 동작 안 함** |
| 클래스 | `ingressClassName` / `IngressClass` | 어떤 컨트롤러가 이 Ingress 를 맡을지 |
| 경로 팬아웃 | `rules[].http.paths[]` | `/tea`→tea, `/coffee`→coffee |
| 호스트 라우팅 | `rules[].host` | Host 헤더로 분기 |
| pathType | `Exact` / `Prefix` | 정확 일치 vs 접두 일치 |
| 기본 백엔드 | `spec.defaultBackend` (rules 無) | 매칭 없는 요청의 글로벌 폴백 |
| TLS 종료 | `spec.tls` + `type: kubernetes.io/tls` Secret | HTTPS 를 컨트롤러에서 복호화 |

---

## 8. 연습 과제

`challenge.md` 를 푸세요. (경로/호스트 혼합 라우팅, rewrite, TLS 다중 호스트, 함정 진단 등)

---

## 9. 정리

```bash
kubectl delete namespace step14
```

> **주의**: `ingress-nginx` 네임스페이스는 **지우지 마세요.** 공용 인프라이고 다음 스텝/실습에서도 씁니다.

---

**다음 단계 →** [Step 15 — 네트워크 정책](../step-15-network-policy/index.md)

---

## 실습 파일

이 스텝의 파일은 크게 세 묶음입니다. `commands.sh` 는 컨트롤러 설치부터 검증·정리까지 전 과정을 한 번에 돌리는 스크립트이고, `manifests/00-namespace.yaml` 과 `manifests/10-apps.yaml` 은 라우팅 대상이 될 백엔드(tea/coffee/hello)를 만드는 준비 매니페스트입니다. 그 뒤 `20`~`24` 번 매니페스트가 경로 기반 → 호스트 기반 → `pathType` → 기본 백엔드 → TLS 순으로 본문 4절의 실습을 하나씩 담당합니다. **번호 순서대로 적용**하는 것을 전제로 작성돼 있으니 그대로 따라가면 됩니다.

### commands.sh

- 이 스텝 전체(본문 1절~9절)를 재현하는 실행 스크립트입니다. `set -euo pipefail` 과 `cd "$(dirname "$0")"` 로 시작하므로 **어느 위치에서 실행하든 `manifests/` 상대경로가 맞고, 중간에 실패하면 즉시 멈춥니다.**
- 1) 블록은 `controller-v1.11.3` 의 kind provider 매니페스트를 `/tmp` 로 받아 적용합니다. `kind load docker-image ... || true` 로 되어 있는데, 이는 본문 1절에서 언급한 **멀티아키(index) 이미지라 `kind load` 가 실패하는 경우**에도 스크립트가 죽지 않게 하려는 장치입니다. 실패했다면 주석에 적힌 `docker save` + `ctr images import` 대체 경로를 쓰세요.
- `kubectl label node learn-control-plane ingress-ready=true --overwrite` 한 줄은 이 환경의 실측 이슈 대응입니다. kind provider 컨트롤러는 `nodeSelector: ingress-ready=true` 를 요구하는데 레이블이 없으면 파드가 계속 Pending 이 됩니다. **노드 이름이 `learn-*` 가 아니면 이 줄을 자기 클러스터에 맞게 고쳐야 합니다.**
- 3) 블록의 TLS Secret 생성은 `kubectl create secret tls ... --dry-run=client -o yaml | kubectl apply -f -` 형태입니다. 그냥 `create` 면 두 번째 실행에서 `AlreadyExists` 로 죽지만, 이 파이프 방식은 **몇 번을 다시 돌려도 안전(idempotent)** 합니다.
- 4) 블록은 `port-forward` 를 백그라운드로 띄우고 PID 를 `PF` 에 담아 두었다가 검증이 끝나면 `kill $PF` 로 정리합니다. `sleep 4` 는 포워딩이 열릴 때까지 기다리는 시간이니, 느린 환경이면 늘려 주세요.
- 5) 블록은 본문 5절의 함정 재현입니다. 힙독(`<<'EOF'`)으로 `ingressClassName: nonexistent-class` 인 `orphan` Ingress 를 만들고 `kubectl get ingress orphan` 으로 **ADDRESS 가 비어 있는 것**을 확인한 뒤 바로 지웁니다. 이 블록은 `kill $PF` 뒤에 오므로 port-forward 는 이미 닫혀 있고, `curl` 없이 `get` 출력만으로 확인한다는 점에 유의하세요.
- ⚠️ **마지막 줄이 `kubectl delete namespace step14` 입니다.** 스크립트를 끝까지 돌리면 실습 리소스가 전부 사라집니다. 결과를 눈으로 더 보고 싶다면 그 줄 전에 중단하세요. 반대로 `ingress-nginx` 네임스페이스는 일부러 지우지 않습니다(공용 인프라).

```bash file="./commands.sh"
```

### manifests/00-namespace.yaml

이 스텝의 모든 워크로드가 들어갈 `step14` 네임스페이스를 만듭니다. 가장 먼저 적용해야 하며, 뒤의 모든 매니페스트가 `namespace: step14` 를 하드코딩하고 있으므로 이걸 건너뛰면 `namespaces "step14" not found` 로 전부 실패합니다. `labels` 의 `course: k8s-learn`, `step: "14"` 는 나중에 `kubectl get ns -l step=14` 처럼 골라내기 위한 표식일 뿐 동작에는 영향을 주지 않습니다(숫자 `14` 를 따옴표로 감싼 것은 레이블 값이 **문자열이어야 하기 때문**입니다). 9절의 `kubectl delete namespace step14` 는 이 네임스페이스를 통째로 지워 실습 리소스를 한 번에 정리합니다.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/10-apps.yaml

- 본문 2절에서 적용하는 **백엔드 3종**(Deployment + ClusterIP Service 각 1쌍, 총 6개 오브젝트)입니다. 라우팅 결과를 눈으로 구분하려고 응답 문자열을 일부러 다르게 했습니다.
- `tea` 와 `coffee` 는 같은 `hashicorp/http-echo:1.0` 이미지를 쓰고 `args: ["-text=tea", "-listen=:5678"]` / `-text=coffee` 만 다릅니다. 그래서 `curl` 응답이 각각 `tea`, `coffee` 로 나와 어느 서비스로 갔는지 바로 알 수 있습니다. 둘 다 `replicas: 2` 라 `describe ingress` 의 Backends 에 파드 IP 가 2개씩 붙습니다.
- `hello` 는 `paulbouwer/hello-kubernetes:1.10.1` 이고 `env` 의 `MESSAGE: "Hello from the hello service"` 가 HTML 에 그대로 찍힙니다. 본문에서 `grep -o "Hello from the hello service"` 로 확인하는 문자열이 바로 이 값이라, **여기를 바꾸면 검증 grep 도 같이 깨집니다.**
- **가장 중요한 포인트는 포트입니다.** tea/coffee 는 컨테이너가 `5678`, hello 는 `8080` 을 열지만 Service 는 모두 `port: 80` + `targetPort: <컨테이너 포트>` 로 통일했습니다. 즉 Ingress 의 `backend.service.port.number` 에는 **항상 `80`** 을 씁니다. 여기에 컨테이너 포트(5678)를 적는 실수가 6절 함정 목록과 `challenge.md` 과제 4의 (b) 로 이어집니다.

```yaml file="./manifests/10-apps.yaml"
```

### manifests/20-ingress-path.yaml

본문 4-1(경로 기반 팬아웃)에서 적용하는 첫 Ingress 입니다. `host: cafe.example.com` 하나 아래에 `paths` 두 개를 두어 `/tea` 는 tea Service 로, `/coffee` 는 coffee Service 로 보냅니다. 둘 다 `pathType: Prefix` 이므로 `/tea/hot` 같은 하위 경로도 tea 로 갑니다. `ingressClassName: nginx` 가 있어야 1절에서 설치한 ingress-nginx 컨트롤러가 이 오브젝트를 채택하며, 채택되면 `kubectl get ingress` 의 `ADDRESS` 가 `localhost` 로 채워집니다(비어 있으면 5절의 고아 Ingress 상태입니다). 어노테이션 `nginx.ingress.kubernetes.io/rewrite-target: /` 는 백엔드로 넘길 때 경로를 `/` 로 재작성하는데, http-echo 는 경로와 무관하게 같은 문자열을 응답하므로 **여기서는 결과가 달라지지 않습니다** — 실제 앱에서 `/tea` 접두를 떼고 넘길 때 쓰는 대표 어노테이션이라 예시로 넣어 둔 것입니다.

```yaml file="./manifests/20-ingress-path.yaml"
```

### manifests/21-ingress-host.yaml

본문 4-2(호스트 기반 라우팅)용입니다. 경로는 둘 다 `path: /`, `pathType: Prefix` 로 **완전히 같지만** `rules[].host` 가 `tea.example.com` / `hello.example.com` 으로 달라, 컨트롤러가 **Host 헤더만 보고** 서로 다른 Service 로 분기합니다. 20 번과 달리 `rewrite-target` 어노테이션이 없어서 요청 경로가 그대로 백엔드에 전달됩니다 — hello 같은 실제 웹 앱은 정적 리소스 경로가 있으므로 함부로 재작성하면 깨지기 때문입니다. DNS 를 건드리지 않고 확인하려면 본문처럼 `curl -H "Host: tea.example.com" http://localhost:18080/` 로 헤더를 직접 넣으면 됩니다.

```yaml file="./manifests/21-ingress-host.yaml"
```

### manifests/22-ingress-pathtype.yaml

본문 4-3 의 `pathType` 실험 전용 Ingress 입니다. `host: pathtype.example.com` 하나에 규칙 두 개를 두는데, `/exact` 는 `pathType: Exact` 로 tea 에, `/prefix` 는 `pathType: Prefix` 로 coffee 에 연결했습니다. **백엔드를 일부러 다르게 준 이유**는 응답 문자열(`tea`/`coffee`)만 보고도 어느 규칙에 걸렸는지 알 수 있게 하려는 것입니다. 결과적으로 `/exact` 는 200, `/exact/foo` 는 **404**(Exact 는 하위 경로를 매치하지 않음), `/prefix` 와 `/prefix/foo` 는 모두 200 이 나옵니다. 이 404 는 버그가 아니라 **의도된 학습 포인트**이며, `challenge.md` 과제 5(`/health` Exact 가 `/health/` 를 못 받는 문제)와 정확히 같은 함정입니다.

```yaml file="./manifests/22-ingress-pathtype.yaml"
```

### manifests/23-ingress-default-backend.yaml

본문 4-4 의 기본 백엔드입니다. 이 매니페스트에는 **`rules` 가 아예 없고 `spec.defaultBackend` 만** 있습니다. ingress-nginx 에서 이런 Ingress 는 **글로벌 catch-all** 로 동작해서, 다른 어떤 Ingress 의 host/path 에도 걸리지 않은 요청이 기본 404 대신 `hello` Service 로 갑니다(적용 전 `Host: nope.example.com` → 404, 적용 후 → 200). 반대로 `defaultBackend` 를 규칙이 있는 Ingress 안에 끼워 넣으면 **그 host 의 매치 안 되는 경로를 폴백해 주지 않는다**는 점이 함정입니다(그 경우는 그대로 404). 주의할 점은 이 Ingress 가 살아 있으면 5절의 고아 Ingress 실습에서 `orphan.example.com` 요청도 404 가 아니라 hello 로 떨어진다는 것이니, 응답 코드를 해석할 때 헷갈리지 마세요.

```yaml file="./manifests/23-ingress-default-backend.yaml"
```

### manifests/24-ingress-tls.yaml

본문 4-5(TLS/HTTPS)에서 적용합니다. `spec.tls[].hosts` 에 `secure.example.com`, `secretName: tls-secret` 을 지정해 컨트롤러가 **TLS 를 종료(복호화)** 하고 뒤의 평문 tea Service(포트 80)로 넘기도록 합니다. **적용 순서가 중요합니다**: `tls-secret` 이 먼저 있어야 하므로 `commands.sh` 3) 블록의 `openssl req -x509 ...` → `kubectl create secret tls tls-secret -n step14` 를 반드시 선행하세요. Secret 이 없으면 컨트롤러가 기본 자체 서명 인증서(`Kubernetes Ingress Controller Fake Certificate`)로 응답해 버려, 겉보기엔 HTTPS 가 되는 것 같지만 우리가 넣은 인증서가 아닙니다. 그래서 본문에서 `curl -k -v ... | grep -E "subject:|issuer:"` 로 `CN=secure.example.com; O=step14` 가 맞는지 확인하는 것입니다. 인증서가 자체 서명이라 `curl` 검증을 끄는 `-k`, 그리고 DNS 없이 접속하는 `--resolve secure.example.com:18443:127.0.0.1` 이 함께 필요합니다.

```yaml file="./manifests/24-ingress-tls.yaml"
```

