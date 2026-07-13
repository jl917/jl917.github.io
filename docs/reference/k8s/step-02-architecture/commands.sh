#!/usr/bin/env bash
# =====================================================================
# Step 02 commands.sh — 아키텍처와 조정 루프
# =====================================================================
export PATH="/opt/homebrew/bin:$PATH"

# [2-1] control-plane / worker 컴포넌트가 파드로 떠 있다
kubectl get pods -n kube-system -o wide

# control-plane 컴포넌트만
kubectl get pods -n kube-system -o wide | grep -E "etcd|apiserver|scheduler|controller-manager"

# 모든 노드에 하나씩 있는 것(kube-proxy, kindnet)
kubectl get pods -n kube-system -o wide | grep -E "kube-proxy|kindnet"

# ─────────────────────────────────────────────────────────────
# [2-5] 조정 루프 직접 보기 — 파드를 죽여도 되살아난다
# ─────────────────────────────────────────────────────────────
kubectl create namespace step02

# "파드 3개를 원한다" 선언
kubectl -n step02 create deployment web --image=nginx:1.27-alpine --replicas=3
sleep 8
kubectl -n step02 get pods -o wide

# 하나를 강제로 죽인다
VICTIM=$(kubectl -n step02 get pods -o jsonpath='{.items[0].metadata.name}')
echo ">>> 죽일 파드: $VICTIM"
kubectl -n step02 delete pod "$VICTIM"

# 잠시 후 보면... 새 파드가 자동으로 생겨 다시 3개
sleep 5
kubectl -n step02 get pods
echo ">>> 원하는 상태(3개)로 자동 복구됨. 이것이 조정 루프."

# 이벤트로 흐름 확인 (스케줄 → pull → 시작)
kubectl -n step02 get events --sort-by=.lastTimestamp | tail -15

# [2-5 함정] 파드는 delete 해도 Deployment가 되살린다.
#            진짜로 없애려면 Deployment를 지우거나 replicas를 줄여야 한다.
kubectl -n step02 scale deployment web --replicas=0
sleep 3
kubectl -n step02 get pods    # 이제 0개

# 정리
kubectl delete namespace step02
