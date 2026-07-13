#!/usr/bin/env bash
# Step 17 — Helm. README 의 명령을 순서대로 담았습니다.
# 한 줄씩 읽으며 직접 실행하세요. (전체를 한 번에 돌려도 됩니다)
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHART="$HERE/chart"
NS=step17

# 1) 배포 전 검증 (클러스터에 손 안 댐)
helm lint "$CHART"
helm template demo "$CHART" | head -40

# 2) 설치 (rev 1)
helm install web "$CHART" --namespace "$NS" --create-namespace --wait --timeout 90s
helm list -n "$NS"
kubectl get pods -n "$NS"
kubectl -n "$NS" run curl-tmp --image=busybox:1.36 --restart=Never --rm -i --quiet -- wget -qO- web-webapp | grep -E "h1|replicas:"

# 3) 업그레이드 --set (rev 2)
helm upgrade web "$CHART" -n "$NS" \
  --set page.message="Hello from Helm - v2 (set)" \
  --set replicaCount=3 --wait --timeout 90s
kubectl -n "$NS" exec deploy/web-webapp -- wget -qO- localhost | grep -E "h1|replicas:"

# 4) 업그레이드 -f (rev 3)
helm upgrade web "$CHART" -n "$NS" -f "$CHART/values-prod.yaml" --wait --timeout 90s
helm history web -n "$NS"

# 5) 롤백 -> rev 1 (이력에는 rev 4 로 기록)
helm rollback web 1 -n "$NS" --wait --timeout 90s
kubectl -n "$NS" exec deploy/web-webapp -- wget -qO- localhost | grep -E "h1|replicas:"
helm history web -n "$NS"

# 6) dry-run (배포 안 함)
helm upgrade web "$CHART" -n "$NS" --set replicaCount=9 --dry-run | grep replicas:

# 7) 정리
helm uninstall web -n "$NS"
kubectl delete namespace "$NS" --ignore-not-found
