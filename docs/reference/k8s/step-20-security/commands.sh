#!/usr/bin/env bash
# Step 20 — 보안 (SecurityContext + Pod Security Admission)
export PATH="/opt/homebrew/bin:$PATH"
set -uo pipefail   # -e 는 빼둔다: 거부(비정상 종료)를 일부러 관찰하기 때문
HERE="$(cd "$(dirname "$0")" && pwd)"

# enforce=restricted 네임스페이스
kubectl apply -f "$HERE/manifests/namespace.yaml"

# 1) 위반 파드 -> 거부됨 (에러가 정상 결과)
echo "=== bad-pod (거부 예상) ==="
kubectl apply -f "$HERE/manifests/bad-pod.yaml"
echo "=== privileged-pod (거부 예상) ==="
kubectl apply -f "$HERE/manifests/privileged-pod.yaml"

# 2) 준수 파드 -> 생성됨
echo "=== good-pod (허용 예상) ==="
kubectl apply -f "$HERE/manifests/good-pod.yaml"
sleep 8
kubectl get pod good -n step20
kubectl logs good -n step20 --tail=1
# readOnlyRootFilesystem 실증
kubectl exec good -n step20 -- sh -c 'echo x > /root_test.txt; id' || true

# 3) Deployment 는 만들어지고 파드만 거부되는 함정
cat > /tmp/bad-deploy.yaml <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata: {name: bad-deploy, namespace: step20}
spec:
  replicas: 1
  selector: {matchLabels: {app: bad-deploy}}
  template:
    metadata: {labels: {app: bad-deploy}}
    spec:
      containers: [{name: nginx, image: nginx:1.27-alpine}]
EOF
kubectl apply -f /tmp/bad-deploy.yaml
sleep 4
kubectl get deploy bad-deploy -n step20                 # READY 0/1
kubectl get events -n step20 --field-selector reason=FailedCreate --sort-by='.lastTimestamp' | tail -2
kubectl delete deploy bad-deploy -n step20

# 4) 시크릿 주입
kubectl apply -f "$HERE/manifests/secret-demo.yaml"
sleep 6
kubectl logs secret-consumer -n step20 --tail=1

# 정리
kubectl delete namespace step20
