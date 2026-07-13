#!/usr/bin/env bash
# Step 18 — 오토스케일링 (metrics-server + HPA)
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# ── metrics-server 설치 (이미 설치돼 있으면 건너뜀) ───────────────────
if ! kubectl get deploy metrics-server -n kube-system >/dev/null 2>&1; then
  echo "▶ metrics-server 설치"
  # 매니페스트를 직접 받아 쓰려면(사내망 host 에서):
  #   curl -sL https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml -o /tmp/ms.yaml
  #   # /tmp/ms.yaml 의 args 에 '- --kubelet-insecure-tls' 추가
  # 여기서는 그 수정을 이미 반영한 manifests/metrics-server.yaml 을 쓴다.
  # 이미지가 노드에 없으면 먼저 주입:
  #   docker pull registry.k8s.io/metrics-server/metrics-server:v0.8.1
  #   docker save registry.k8s.io/metrics-server/metrics-server:v0.8.1 -o /tmp/ms-img.tar
  #   for n in learn-control-plane learn-worker learn-worker2; do docker exec -i $n ctr --namespace=k8s.io images import - < /tmp/ms-img.tar; done
  kubectl apply -f "$HERE/manifests/metrics-server.yaml"
  kubectl -n kube-system rollout status deploy/metrics-server --timeout=120s
fi

# ── top 동작 확인 ─────────────────────────────────────────────────────
sleep 20
kubectl top nodes
kubectl get apiservices v1beta1.metrics.k8s.io

# ── 워크로드 + HPA ────────────────────────────────────────────────────
kubectl apply -f "$HERE/manifests/namespace.yaml"
kubectl apply -f "$HERE/manifests/workload.yaml"
kubectl rollout status deploy/web -n step18 --timeout=60s
kubectl apply -f "$HERE/manifests/hpa.yaml"
sleep 25
kubectl get hpa -n step18            # cpu: ~15%/50%, REPLICAS 1

# ── 부하 투입 → 스케일업 관찰 ─────────────────────────────────────────
kubectl apply -f "$HERE/manifests/load-generator.yaml"
kubectl rollout status deploy/load -n step18 --timeout=60s
echo "▶ 스케일업 관찰(약 2분). Ctrl-C 로 빠져나온 뒤 아래 부하 제거로 진행."
for i in $(seq 1 8); do
  kubectl get hpa web -n step18 --no-headers
  kubectl top pods -n step18 -l app=web --no-headers 2>/dev/null || true
  sleep 20
done

# ── 스케일 결정 이유 ─────────────────────────────────────────────────
kubectl describe hpa web -n step18 | sed -n '/Events:/,$p'

# ── 부하 제거 → 스케일다운 관찰 ──────────────────────────────────────
kubectl delete -f "$HERE/manifests/load-generator.yaml"
echo "▶ 스케일다운은 stabilizationWindow(60s) 후 60초에 1개씩 감소. 수 분 소요."
for i in $(seq 1 6); do
  echo "REPLICAS=$(kubectl get deploy web -n step18 -o jsonpath='{.status.replicas}')  $(kubectl get hpa web -n step18 --no-headers)"
  sleep 30
done

# ── 정리 (metrics-server 는 보존!) ───────────────────────────────────
kubectl delete namespace step18
echo "metrics-server 는 다른 스텝(19)에서 쓰므로 삭제하지 않습니다."
