#!/usr/bin/env bash
export PATH="/opt/homebrew/bin:$PATH"
# Step 13 — 스케줄링 제어. 각 명령을 순서대로 실행하며 결과를 관찰한다.
# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context

set -euo pipefail
cd "$(dirname "$0")"

echo "### 0) 네임스페이스 + 노드 라벨 확인"
kubectl apply -f manifests/00-namespace.yaml
kubectl get nodes -L kubernetes.io/hostname

echo "### 13-1~3) nodeSelector / nodeName / nodeAffinity"
kubectl apply -f manifests/01-nodeselector.yaml
kubectl apply -f manifests/02-nodename.yaml
kubectl apply -f manifests/03-nodeaffinity.yaml
kubectl wait --for=condition=Ready pod/pin-worker pod/direct-assign pod/affinity-required -n step13 --timeout=90s
kubectl get pod -n step13 -o wide

echo "### 13-4) podAffinity (cache 를 web 곁에)"
kubectl apply -f manifests/04-podaffinity.yaml
kubectl wait --for=condition=Ready pod -l app=web -n step13 --timeout=90s
kubectl wait --for=condition=Ready pod/cache -n step13 --timeout=90s
kubectl get pod -n step13 -l 'app in (web,cache)' -o wide

echo "### 13-5) podAntiAffinity (복제본 흩기)"
kubectl apply -f manifests/05-podantiaffinity.yaml
kubectl wait --for=condition=Ready pod -l app=spread-app -n step13 --timeout=90s
kubectl get pod -n step13 -l app=spread-app -o wide

echo "### 13-6) taint / toleration  (★ 워커에만, 실습 직후 즉시 원복)"
kubectl taint nodes learn-worker step13-demo=true:NoSchedule
kubectl describe node learn-worker | grep -i taint
kubectl apply -f manifests/06-toleration.yaml
kubectl wait --for=condition=Ready pod/no-tol pod/with-tol -n step13 --timeout=90s
kubectl get pod no-tol with-tol -n step13 -o wide
kubectl taint nodes learn-worker step13-demo=true:NoSchedule-   # 원복
kubectl describe node learn-worker | grep -i taint || echo "(no taints)"

echo "### 13-7) topologySpreadConstraints (2/2 균등)"
kubectl apply -f manifests/07-topologyspread.yaml
kubectl wait --for=condition=Ready pod -l app=topo -n step13 --timeout=90s
kubectl get pod -n step13 -l app=topo -o wide

echo "### 13-8) Pending 진단"
kubectl apply -f manifests/08-pending.yaml
sleep 5
kubectl get pod unschedulable -n step13 -o wide
kubectl describe pod unschedulable -n step13 | sed -n '/Events:/,$p'

echo "### 정리 — 네임스페이스 삭제 + 노드 테인트 원복 확인"
kubectl delete namespace step13
kubectl describe nodes | grep -i taint
