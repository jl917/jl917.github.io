#!/usr/bin/env bash
# =====================================================================
# Step 01 commands.sh — 클러스터 구축과 kubectl
#   한 줄씩 따라 실행하며 결과를 관찰하세요. (통째로 실행해도 됩니다)
# =====================================================================
export PATH="/opt/homebrew/bin:$PATH"

HERE="$(cd "$(dirname "$0")" && pwd)"

# [1-4] 클러스터 상태 확인
kubectl config current-context          # kind-learn 이어야 함
kubectl cluster-info
kubectl get nodes -o wide

# kubectl 이 다룰 수 있는 리소스 종류 전체
kubectl api-resources | head -20

# [1-5] 명령형(imperative) — 빠른 실험
kubectl create namespace step01
kubectl -n step01 run web --image=nginx:1.27-alpine
sleep 5
kubectl -n step01 get pods
kubectl -n step01 get pod web -o wide     # 어느 노드에 떴는지

# [1-6] 선언형(declarative) — 실무 표준
kubectl apply -f "$HERE/manifests/pod.yaml"
sleep 3
kubectl -n step01 get pods

# 멱등성: 같은 걸 또 apply 해도 안전 → unchanged
kubectl apply -f "$HERE/manifests/pod.yaml"

# 명령형은 두 번 하면 에러
kubectl -n step01 run web --image=nginx:1.27-alpine || echo "↑ 이미 존재해서 에러 (정상)"

# 명령형의 편의 + 선언형의 안전 = dry-run 으로 YAML 초안 뽑기
kubectl -n step01 run draft --image=nginx:1.27-alpine --dry-run=client -o yaml | head -20

# [1-7] 컨텍스트
kubectl config get-contexts

# [1-8] 정리 — 네임스페이스 하나 지우면 안의 모든 것이 사라짐
kubectl delete namespace step01
