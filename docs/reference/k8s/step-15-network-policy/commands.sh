#!/usr/bin/env bash
# Step 15 — 네트워크 정책(NetworkPolicy) 실습 명령 모음
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail
cd "$(dirname "$0")"

# ============================================================
# 0) 내 CNI 가 NetworkPolicy 를 강제하는지 버전 확인
# ============================================================
kubectl get ds kindnet -n kube-system -o jsonpath='{.spec.template.spec.containers[0].image}'; echo

# ============================================================
# 1) 워크로드 배포
# ============================================================
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-workloads.yaml
kubectl wait -n step15 --for=condition=ready pod --all --timeout=120s

# ============================================================
# 2) 기준선 — 정책 없을 때는 둘 다 접속
# ============================================================
echo "--- baseline ---"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED

# ============================================================
# 3) default-deny — 강제 여부 스모크 테스트 (둘 다 BLOCKED 여야 강제됨)
# ============================================================
kubectl apply -f manifests/20-default-deny.yaml
sleep 5
echo "--- after default-deny (both should be BLOCKED) ---"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED

# ============================================================
# 4) podSelector 화이트리스트 — role=frontend 만 허용
# ============================================================
kubectl apply -f manifests/21-allow-frontend.yaml
sleep 5
echo "--- whitelist (allowed passes, denied blocked) ---"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo BLOCKED
kubectl describe networkpolicy allow-frontend-to-server -n step15

# ============================================================
# 5) namespaceSelector — trusted 네임스페이스만 허용
# ============================================================
kubectl create namespace step15-ext 2>/dev/null || true
kubectl label namespace step15-ext team=trusted --overwrite
kubectl run ext-client -n step15-ext --image=busybox:1.36 --restart=Never -- sh -c "sleep 3600" 2>/dev/null || true
kubectl wait -n step15-ext --for=condition=ready pod/ext-client --timeout=60s
kubectl delete networkpolicy allow-frontend-to-server -n step15 2>/dev/null || true
kubectl apply -f manifests/30-allow-namespace.yaml
sleep 5
SERVER_IP=$(kubectl get svc server -n step15 -o jsonpath='{.spec.clusterIP}')
echo "--- namespaceSelector (ext-client passes, in-ns client blocked) ---"
kubectl exec -n step15-ext ext-client    -- wget -qO- --timeout=4 "http://$SERVER_IP" | grep -o "<title>.*</title>" || echo BLOCKED
kubectl exec -n step15    client-allowed -- wget -qO- --timeout=4 http://server        | grep -o "<title>.*</title>" || echo BLOCKED

# ============================================================
# 6) egress — DNS 함정과 목적지 제한
# ============================================================
kubectl delete networkpolicy allow-trusted-namespace -n step15 2>/dev/null || true
kubectl apply -f manifests/21-allow-frontend.yaml   # ingress 는 다시 허용해 두고 egress 만 관찰
# 6-1) egress default-deny → DNS 까지 막힘
kubectl apply -f - <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: frontend-egress-denyall, namespace: step15 }
spec:
  podSelector: { matchLabels: { role: frontend } }
  policyTypes: [Egress]
EOF
sleep 5
echo "--- egress deny-all (name resolution fails) ---"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED (dns/egress denied)"
# 6-2) DNS + server 만 허용
kubectl delete networkpolicy frontend-egress-denyall -n step15
kubectl apply -f manifests/40-egress.yaml
sleep 5
echo "--- egress allow DNS+server (server ok, 1.1.1.1 blocked) ---"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server  | grep -o "<title>.*</title>" || echo BLOCKED
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://1.1.1.1                              || echo "BLOCKED (1.1.1.1 not in allow)"

# ============================================================
# 7) 정리
# ============================================================
kubectl delete namespace step15
kubectl delete namespace step15-ext
