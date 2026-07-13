#!/usr/bin/env bash
# Step 05 — 디플로이먼트
# README 순서 그대로. 한 줄씩 실행하며 rollout 을 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

kubectl config current-context   # kind-learn 이어야 함

# --- 5-1. 배포 & 계층 구조 확인 ---
kubectl apply -f manifests/deployment.yaml
kubectl rollout status deployment/web -n step05
kubectl get deploy,rs,pod -n step05
kubectl get pods -n step05 --show-labels          # pod-template-hash 확인
# 자가 치유: 파드 하나 지워도 다시 4개로 회복
kubectl delete pod -n step05 -l app=web --field-selector=status.phase=Running | head -1
kubectl get pods -n step05

# --- 5-2. 스케일 (롤아웃 아님) ---
kubectl scale deployment/web -n step05 --replicas=5
kubectl rollout status deployment/web -n step05
kubectl get deploy web -n step05
kubectl scale deployment/web -n step05 --replicas=4   # 원복

# --- 5-3. 롤링 업데이트 (env 변경으로 트리거) ---
kubectl set env deployment/web -n step05 APP_VERSION=v2
kubectl annotate deployment/web -n step05 kubernetes.io/change-cause="set APP_VERSION=v2" --overwrite
kubectl rollout status deployment/web -n step05
kubectl get rs -n step05                          # RS 두 개 (old 0, new 4)
kubectl rollout history deployment/web -n step05

# --- 5-4. 함정: 잘못된 이미지 → 롤아웃 멈춤 ---
kubectl set image deployment/web nginx=nginx:1.27-doesnotexist -n step05
kubectl annotate deployment/web -n step05 kubernetes.io/change-cause="bad image nginx:1.27-doesnotexist" --overwrite
kubectl rollout status deployment/web -n step05 --timeout=25s   # timed out (멈춤)
kubectl get deploy,rs -n step05                   # 구버전은 살아있음
kubectl get pods -n step05                        # ImagePullBackOff
kubectl describe pod -n step05 -l pod-template-hash=699f57cd74 | grep -iE "Failed|Back-off"

# --- 5-5. 롤백 ---
kubectl rollout history deployment/web -n step05
kubectl rollout undo deployment/web -n step05
kubectl rollout status deployment/web -n step05
kubectl get deploy,rs -n step05
# 특정 리비전으로: kubectl rollout undo deployment/web -n step05 --to-revision=2

# --- 5-6. 함정: 셀렉터 불변 ---
kubectl apply -f manifests/bad-selector.yaml       # field is immutable 에러

# --- 정리 ---
kubectl delete namespace step05
