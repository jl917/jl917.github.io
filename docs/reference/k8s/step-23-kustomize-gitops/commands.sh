#!/usr/bin/env bash
# Step 23 — Kustomize & GitOps
# README 에 나오는 명령들을 순서대로 담았습니다. 한 줄씩 복사해 실행하며 결과를 관찰하세요.
# 이 스크립트는 manifests/ 상위(= step-23-kustomize-gitops/) 디렉터리에서 실행한다고 가정합니다.
export PATH="/opt/homebrew/bin:$PATH"

# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context
kubectl version | grep Kustomize        # kubectl 내장 Kustomize 버전

# --- 23-4. 적용 전 렌더 미리보기 (클러스터에 아무 것도 안 보냄) ---
kubectl kustomize manifests/overlays/dev
kubectl kustomize manifests/overlays/prod

# --- 23-5. 네임스페이스 생성 후 두 overlay 배포 ---
# namespace: 필드는 ns를 만들지 않으므로 먼저 만든다.
kubectl create ns step23-dev
kubectl create ns step23-prod
kubectl apply -k manifests/overlays/dev
kubectl apply -k manifests/overlays/prod

kubectl wait --for=condition=Available deploy --all -n step23-dev  --timeout=120s
kubectl wait --for=condition=Available deploy --all -n step23-prod --timeout=120s

# --- 결과 비교 ---
kubectl get all -n step23-dev
kubectl get all -n step23-prod

# 이미지/레플리카 차이
kubectl get deploy -n step23-dev  -o wide
kubectl get deploy -n step23-prod -o wide

# 두 네임스페이스 가로질러 한 번에 비교 (course 레이블은 base에서 심음)
kubectl get deploy -A -l course=k8s-learn \
  -o custom-columns='NS:.metadata.namespace,NAME:.metadata.name,REPLICAS:.spec.replicas,IMAGE:.spec.template.spec.containers[0].image'

# ConfigMap 이름의 해시 차이 (MESSAGE 값이 다르므로 해시가 다름)
kubectl get cm -n step23-dev
kubectl get cm -n step23-prod

# Deployment 가 참조하는 configMapKeyRef 가 해시 이름으로 자동 치환됐는지 확인
kubectl get deploy prod-web -n step23-prod \
  -o jsonpath='{.spec.template.spec.containers[0].env}'; echo

# --- 정리: 두 네임스페이스 통째 삭제 ---
kubectl delete ns step23-dev step23-prod
kubectl get ns
