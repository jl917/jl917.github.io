#!/usr/bin/env bash
export PATH="/opt/homebrew/bin:$PATH"
#
# Step 09 — 리소스 관리: 전체 데모를 순서대로 재현하는 스크립트.
# 각 데모는 서로 간섭하지 않도록 사이사이 네임스페이스를 정리한다.
# 실행: bash commands.sh
#
set -euo pipefail
cd "$(dirname "$0")"

echo "### 0. 컨텍스트 확인 (kind-learn 여야 함)"
kubectl config current-context

echo
echo "### 1. 네임스페이스 생성"
kubectl apply -f manifests/00-namespace.yaml

echo
echo "========================================================"
echo "### 9-1 ~ 9-3. QoS 3등급 + requests 스케줄링"
echo "========================================================"
kubectl apply -f manifests/01-qos-guaranteed.yaml
kubectl apply -f manifests/02-qos-burstable.yaml
kubectl apply -f manifests/03-qos-besteffort.yaml
kubectl wait --for=condition=Ready pod -l demo=qos -n step09 --timeout=60s

echo "--- QoS 클래스 확인 ---"
for p in qos-guaranteed qos-burstable qos-besteffort; do
  echo -n "$p -> "; kubectl get pod "$p" -n step09 -o jsonpath='{.status.qosClass}'; echo
done

echo "--- 노드 할당 현황(requests/limits 합계) ---"
kubectl describe node learn-worker2 | sed -n '/Allocated resources/,/Events/p'

echo "--- (선택) 실제 사용량: metrics-server 필요 ---"
kubectl top nodes || echo "metrics-server 미설치 → Step 18에서 설치"

echo "--- QoS 데모 정리 ---"
kubectl delete pod -l demo=qos -n step09 --now

echo
echo "========================================================"
echo "### 9-4. OOMKilled 재현 (종료코드 137)"
echo "========================================================"
kubectl apply -f manifests/04-oomkill.yaml
echo "--- OOMKill 대기 ---"
for i in $(seq 1 30); do
  reason=$(kubectl get pod oom-victim -n step09 -o jsonpath='{.status.containerStatuses[0].state.terminated.reason}' 2>/dev/null || true)
  [ -n "$reason" ] && { echo "reason=$reason (${i}s)"; break; }
  sleep 1
done
kubectl get pod oom-victim -n step09
kubectl describe pod oom-victim -n step09 | sed -n '/Containers:/,/Conditions:/p'
kubectl get pod oom-victim -n step09 \
  -o jsonpath='Reason={.status.containerStatuses[0].state.terminated.reason} ExitCode={.status.containerStatuses[0].state.terminated.exitCode}{"\n"}'
echo "--- OOM 데모 정리 ---"
kubectl delete pod --all -n step09 --now

echo
echo "========================================================"
echo "### 9-5. LimitRange (기본값 주입 + max 강제)"
echo "========================================================"
kubectl apply -f manifests/05-limitrange.yaml
kubectl describe limitrange step09-limits -n step09

echo "--- resources 없는 파드 → 기본값 주입 ---"
kubectl apply -f manifests/06-pod-no-resources.yaml
sleep 2
kubectl get pod inherit-defaults -n step09 -o jsonpath='{.spec.containers[0].resources}{"\n"}'
kubectl describe pod inherit-defaults -n step09 | grep -A1 Annotations

echo "--- max 초과 파드 → 거부 기대 ---"
kubectl apply -f manifests/07-pod-exceeds-max.yaml || echo "(예상된 거부)"

echo "--- LimitRange 데모 정리 ---"
kubectl delete pod inherit-defaults -n step09 --now
kubectl delete -f manifests/05-limitrange.yaml

echo
echo "========================================================"
echo "### 9-6. ResourceQuota (총량 상한)"
echo "========================================================"
kubectl apply -f manifests/08-resourcequota.yaml
kubectl get resourcequota -n step09

echo "--- 범위 안 파드 → 성공, Used 증가 ---"
kubectl apply -f manifests/09-quota-fit.yaml
sleep 1
kubectl describe resourcequota step09-quota -n step09

echo "--- 총량 초과 파드 → 거부 기대 ---"
kubectl apply -f manifests/10-quota-exceed.yaml || echo "(예상된 거부: exceeded quota)"

echo "--- resources 없는 파드 → 거부 기대 (must specify) ---"
kubectl apply -f manifests/11-quota-no-resources.yaml || echo "(예상된 거부: must specify)"

echo
echo "========================================================"
echo "### 정리 (MANDATORY): 네임스페이스 삭제"
echo "========================================================"
kubectl delete namespace step09
kubectl get ns | grep step09 || echo "step09 삭제 완료"
