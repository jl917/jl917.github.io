#!/usr/bin/env bash
export PATH="/opt/homebrew/bin:$PATH"
#
# Step 08 — 헬스 체크(프로브) 실습 커맨드 모음
# 컨텍스트가 kind-learn 인지 먼저 확인하고 실행하세요.
#
set -euo pipefail

cd "$(dirname "$0")"

echo "== 0. 컨텍스트 확인 =="
kubectl config current-context   # kind-learn 이어야 함

echo "== 0. 네임스페이스 생성 =="
kubectl create namespace step08 --dry-run=client -o yaml | kubectl apply -f -

# ---------------------------------------------------------------------------
echo "== 8-1. 세 프로브/세 핸들러 정상 동작 =="
kubectl apply -f manifests/01-healthy-all-probes.yaml
sleep 12
kubectl get pod healthy-all-probes -n step08 -o wide
kubectl describe pod healthy-all-probes -n step08 | grep -A1 -E "Liveness|Readiness|Startup"

# ---------------------------------------------------------------------------
echo "== 8-4. CrashLoopBackOff 재현 (잘못된 livenessProbe) =="
kubectl apply -f manifests/02-crashloop-liveness.yaml
echo "재시작이 쌓일 때까지 70초 대기..."
sleep 70
kubectl get pod bad-liveness -n step08 -o wide
kubectl describe pod bad-liveness -n step08 | sed -n '/Events:/,$p'

# ---------------------------------------------------------------------------
echo "== 8-5. readiness 실패 -> Endpoints 제외 증명 =="
kubectl apply -f manifests/03-readiness-endpoints.yaml
sleep 10
echo "-- 두 Pod 모두 NotReady, Endpoints 비어 있음 --"
kubectl get pods -n step08 -l app=readiness-demo -o wide
kubectl get endpoints readiness-demo -n step08
kubectl get endpointslice -n step08 -l kubernetes.io/service-name=readiness-demo \
  -o jsonpath='{range .items[0].endpoints[*]}{.addresses[0]}{"  ready="}{.conditions.ready}{"\n"}{end}'

echo "-- 한쪽 Pod에만 /tmp/ready 생성 -> Ready 전환 -> Endpoints 등장 --"
POD=$(kubectl get pods -n step08 -l app=readiness-demo -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step08 "$POD" -- touch /tmp/ready
sleep 6
kubectl get pods -n step08 -l app=readiness-demo -o wide
kubectl get endpoints readiness-demo -n step08
kubectl get endpointslice -n step08 -l kubernetes.io/service-name=readiness-demo \
  -o jsonpath='{range .items[0].endpoints[*]}{.addresses[0]}{"  ready="}{.conditions.ready}{"\n"}{end}'

# ---------------------------------------------------------------------------
echo "== 8-6. startupProbe로 느린 앱 보호 =="
kubectl apply -f manifests/04-slow-no-startup.yaml -f manifests/05-slow-with-startup.yaml
echo "결과가 갈릴 때까지 2~3분 관찰..."
sleep 135
kubectl get pods -n step08 -l 'app in (slow-no-startup,slow-with-startup)' -o wide
echo "-- startupProbe 없음: Killing 반복 --"
kubectl describe pod slow-no-startup -n step08 | sed -n '/Events:/,$p'
echo "-- startupProbe 있음: Startup 실패는 있어도 Killing 없음 --"
kubectl describe pod slow-with-startup -n step08 | sed -n '/Events:/,$p'

# ---------------------------------------------------------------------------
echo "== 정리(cleanup) =="
kubectl delete namespace step08
kubectl get ns step08 || echo "step08 네임스페이스 삭제 완료"
