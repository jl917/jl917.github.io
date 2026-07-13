#!/usr/bin/env bash
# Step 22 — CRD와 오퍼레이터
# README 에 나오는 명령들을 순서대로 담았습니다. 한 줄씩 복사해 실행하며 결과를 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context

# --- 준비: 네임스페이스 ---
kubectl create namespace step22
kubectl label namespace step22 course=k8s-learn step=22

# --- 22-2. CRD 작성·등록 ---
kubectl apply -f manifests/webapp-crd.yaml
kubectl wait --for=condition=Established crd/webapps.learn.example.com --timeout=30s
kubectl get crd webapps.learn.example.com
kubectl api-resources --api-group=learn.example.com     # 새 kind 확인

# --- 22-3. 커스텀 리소스(CR) 생성·조회 ---
kubectl apply -f manifests/webapps.yaml
kubectl get webapps -n step22                           # additionalPrinterColumns
kubectl get wa -n step22                                # shortName
kubectl get learn -n step22                             # category
kubectl get webapp order-api -n step22 -o yaml | grep -A12 '^spec:'   # default 적용 확인
kubectl describe webapp shop-frontend -n step22

# --- 22-4. 스키마 검증 (거부되는 것을 확인) ---
kubectl apply -f manifests/webapp-bad-replicas.yaml     # replicas 99 > max 5 → 거부
kubectl apply -f manifests/webapp-bad-missing.yaml      # image 누락 + tier enum 위반 → 거부
kubectl get webapps -n step22                           # 유효한 2개만 남음

# --- 22-5. 오퍼레이터 없음 = 데이터만 저장 (파드 안 생김) ---
kubectl get pods -n step22
kubectl get deploy -n step22

# --- 정리: CR → CRD(클러스터 범위!) → 네임스페이스 순으로 삭제 ---
kubectl delete -f manifests/webapps.yaml -n step22
kubectl delete crd webapps.learn.example.com
kubectl delete namespace step22

# 확인
kubectl get ns
kubectl get crd | grep learn.example.com || echo none
