#!/usr/bin/env bash
# Step 16 — RBAC와 인증 실습 명령 모음
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail
cd "$(dirname "$0")"

# ============================================================
# 1) 신원 + 권한 배포
# ============================================================
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-serviceaccounts.yaml
kubectl apply -f manifests/20-role-and-binding.yaml
kubectl apply -f manifests/30-clusterrole-and-binding.yaml

# 테스트용 파드/시크릿
kubectl run demo -n step16 --image=nginx:1.27-alpine
kubectl create secret generic app-secret -n step16 --from-literal=password=s3cr3t
kubectl wait -n step16 --for=condition=ready pod/demo --timeout=60s

# ============================================================
# 2) 임퍼소네이션(--as) 으로 권한 점검
# ============================================================
SA=system:serviceaccount:step16:pod-reader
echo "list pods (step16):   $(kubectl auth can-i list pods   -n step16  --as=$SA)"   # yes
echo "get secrets (step16): $(kubectl auth can-i get secrets -n step16  --as=$SA)"   # no
echo "delete pods (step16): $(kubectl auth can-i delete pods -n step16  --as=$SA)"   # no
echo "list nodes (cluster): $(kubectl auth can-i list nodes            --as=$SA)"    # yes
echo "list pods (default):  $(kubectl auth can-i list pods  -n default  --as=$SA)"   # no
echo "no-access SA:         $(kubectl auth can-i list pods  -n step16   --as=system:serviceaccount:step16:no-access)"  # no

kubectl auth can-i --list -n step16 --as=$SA

# ============================================================
# 3) 실제 토큰으로 확인 (관리자 폴백 방지 위해 토큰 전용 kubeconfig)
# ============================================================
TOKEN=$(kubectl create token pod-reader -n step16)
KC=/tmp/step16-kc.yaml
kubectl config view --raw --minify > "$KC"
kubectl --kubeconfig "$KC" config set-credentials pod-reader-user --token="$TOKEN"
kubectl --kubeconfig "$KC" config set-context --current --user=pod-reader-user

kubectl --kubeconfig "$KC" auth whoami
kubectl --kubeconfig "$KC" get pods    -n step16      || true   # 성공
kubectl --kubeconfig "$KC" get secrets -n step16      || true   # Forbidden
kubectl --kubeconfig "$KC" delete pod demo -n step16  || true   # Forbidden
kubectl --kubeconfig "$KC" get nodes                  || true   # 성공(ClusterRole)
kubectl --kubeconfig "$KC" get pods    -n default     || true   # Forbidden

# ============================================================
# 4) 정리 — 네임스페이스 + 전역(step16-) 리소스 삭제
# ============================================================
kubectl delete namespace step16
kubectl delete clusterrolebinding step16-node-reader-binding
kubectl delete clusterrole        step16-node-reader
rm -f "$KC"
