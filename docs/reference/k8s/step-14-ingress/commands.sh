#!/usr/bin/env bash
# Step 14 — 인그레스(Ingress) 실습 명령 모음
# 위에서부터 순서대로 실행하세요. 각 블록은 독립적으로 다시 실행해도 됩니다.
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail
cd "$(dirname "$0")"

# ============================================================
# 1) ingress-nginx 컨트롤러 설치 (kind 전용 매니페스트)
# ============================================================
# 인터넷에서 kind provider 매니페스트를 받는다 (사내망이면 호스트에서 미리 받아둘 것)
curl -sSL -o /tmp/ingress-nginx-kind.yaml \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml

# 이미지 2개를 호스트로 pull 후 kind 노드로 로드
docker pull registry.k8s.io/ingress-nginx/controller:v1.11.3
docker pull registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.4.4
# 멀티아키(index) 라 'kind load' 가 실패하면(content digest not found) 아래 대체 방법 사용:
#   docker save registry.k8s.io/ingress-nginx/controller:v1.11.3 -o /tmp/controller.tar
#   for n in learn-control-plane learn-worker learn-worker2; do
#     docker exec -i $n ctr -n k8s.io images import - < /tmp/controller.tar
#   done
kind load docker-image --name learn registry.k8s.io/ingress-nginx/controller:v1.11.3 || true
kind load docker-image --name learn registry.k8s.io/ingress-nginx/kube-webhook-certgen:v1.4.4 || true

kubectl apply -f /tmp/ingress-nginx-kind.yaml

# 이 환경 실측 이슈: control-plane 에 ingress-ready 레이블이 없어 컨트롤러가 Pending 이면 붙여준다
kubectl label node learn-control-plane ingress-ready=true --overwrite

kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s

kubectl get pods -n ingress-nginx
kubectl get ingressclass

# ============================================================
# 2) 실습 백엔드 배포
# ============================================================
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-apps.yaml
kubectl wait -n step14 --for=condition=available deploy --all --timeout=120s
kubectl get pods -n step14

# ============================================================
# 3) Ingress 규칙 적용
# ============================================================
# TLS Secret 먼저 (자체 서명 인증서)
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /tmp/tls.key -out /tmp/tls.crt \
  -subj "/CN=secure.example.com/O=step14" \
  -addext "subjectAltName=DNS:secure.example.com"
kubectl create secret tls tls-secret -n step14 \
  --cert=/tmp/tls.crt --key=/tmp/tls.key --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f manifests/20-ingress-path.yaml
kubectl apply -f manifests/21-ingress-host.yaml
kubectl apply -f manifests/22-ingress-pathtype.yaml
kubectl apply -f manifests/23-ingress-default-backend.yaml
kubectl apply -f manifests/24-ingress-tls.yaml
kubectl get ingress -n step14

# ============================================================
# 4) 접근 검증 (port-forward — 어떤 클러스터에서도 재현 가능)
#    * 정상 매핑 환경이면 localhost:8080 / 8443 를 그대로 쓰면 된다
# ============================================================
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 18080:80 18443:443 >/tmp/pf.log 2>&1 &
PF=$!
sleep 4

echo "--- 경로 기반 ---"
curl -sS -H "Host: cafe.example.com" http://localhost:18080/tea
curl -sS -H "Host: cafe.example.com" http://localhost:18080/coffee

echo "--- 호스트 기반 ---"
curl -sS -H "Host: tea.example.com"   http://localhost:18080/
curl -sS -H "Host: hello.example.com" http://localhost:18080/ | grep -o "Hello from the hello service"

echo "--- pathType Exact vs Prefix ---"
for p in /exact /exact/foo /prefix /prefix/foo; do
  printf "%-12s -> " "$p"
  curl -sS -o /dev/null -w "%{http_code}\n" -H "Host: pathtype.example.com" "http://localhost:18080$p"
done

echo "--- 기본 백엔드(글로벌 catch-all) ---"
curl -sS -o /dev/null -w "nope.example.com -> %{http_code}\n" -H "Host: nope.example.com" http://localhost:18080/

echo "--- TLS ---"
curl -sS -k --resolve secure.example.com:18443:127.0.0.1 https://secure.example.com:18443/

kill $PF 2>/dev/null || true

# ============================================================
# 5) 함정 재현 — 컨트롤러가 안 맡는 Ingress (ADDRESS 비어 있음)
# ============================================================
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
kubectl get ingress orphan -n step14      # ADDRESS 가 비어 있음에 주목
kubectl delete ingress orphan -n step14

# ============================================================
# 6) 정리 (ingress-nginx 네임스페이스는 남겨둔다!)
# ============================================================
kubectl delete namespace step14
