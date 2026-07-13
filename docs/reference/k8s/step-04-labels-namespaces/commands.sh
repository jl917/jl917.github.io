#!/usr/bin/env bash
# Step 04 — 레이블·셀렉터·네임스페이스
# README 에 나오는 명령들을 순서대로 담았습니다. 한 줄씩 복사해 실행하며 결과를 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context

# --- 4-1. 배포 ---
kubectl apply -f manifests/namespace.yaml
kubectl apply -f manifests/pods.yaml
kubectl wait --for=condition=Ready pod --all -n step04 --timeout=90s
kubectl get pods -n step04 --show-labels

# --- 4-2. 셀렉터로 골라내기 ---
kubectl get pods -n step04 -l app=web                    # 등식 기반
kubectl get pods -n step04 -l 'env=prod,tier=frontend'   # 콤마 = AND
kubectl get pods -n step04 -l 'env in (dev,prod)'        # 집합 기반
kubectl get pods -n step04 -l 'app=web,env!=prod'        # 부등식
kubectl get pods -n step04 -l 'tier'                     # 키 존재 여부
kubectl get pods -n step04 -L app,env,tier               # 레이블을 컬럼으로 (-L 대문자)

# --- 4-3. 어노테이션 ---
kubectl describe pod web-prod-1 -n step04 | sed -n '1,12p'

# --- 4-4. 레이블 런타임 조작 ---
kubectl label pod web-prod-1 -n step04 release=canary          # 추가
kubectl annotate pod web-prod-1 -n step04 note="점검중"         # 어노테이션 추가
kubectl label pod web-prod-1 -n step04 env=staging             # 덮어쓰기 시도 → 에러
kubectl label pod web-prod-1 -n step04 env=staging --overwrite # 덮어쓰기
kubectl label pod web-prod-1 -n step04 env-                    # 제거 (키 끝에 -)
kubectl get pod web-prod-1 -n step04 --show-labels
kubectl label pod web-prod-1 -n step04 env=prod release- --overwrite  # 원복

# --- 4-5. 네임스페이스 ---
kubectl get ns
kubectl get pods -A -l app=web                           # 전체 네임스페이스 가로지르기
# (선택) 기본 네임스페이스 바꾸기 — 이 코스에선 -n 명시를 권장
# kubectl config set-context --current --namespace=step04

# --- 4-6. 레이블로 대량 삭제 ---
kubectl get pods -n step04 -l env=dev                    # 지우기 전 미리보기 (안전 습관)
kubectl delete pod -n step04 -l env=dev                  # 셀렉터로 대량 삭제
kubectl get pods -n step04 --show-labels

# --- 정리: 네임스페이스 통째 삭제 ---
kubectl delete namespace step04
kubectl get ns
