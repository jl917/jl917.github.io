#!/usr/bin/env bash
export PATH="/opt/homebrew/bin:$PATH"
# Step 12 — 잡·데몬셋 실습 명령 모음
# 컨텍스트 확인: kind-learn 인지 반드시 체크
set -euo pipefail

cd "$(dirname "$0")"

echo "### 0. 컨텍스트 확인 ###"
test "$(kubectl config current-context)" = "kind-learn" || { echo "kind-learn 컨텍스트가 아닙니다"; exit 1; }
kubectl create namespace step12 --dry-run=client -o yaml | kubectl apply -f -

echo "### 12-1. 단순 Job (completions=1) ###"
kubectl apply -f manifests/job-simple.yaml
kubectl wait --for=condition=complete job/hello-job -n step12 --timeout=60s
kubectl get job -n step12
kubectl get pod -n step12
kubectl logs -n step12 job/hello-job

echo "### 12-2. 병렬 Job (completions=6, parallelism=2) ###"
kubectl apply -f manifests/job-parallel.yaml
sleep 4
kubectl get pod -n step12 -l job-name=parallel-job          # 실행 중: 2개씩
kubectl wait --for=condition=complete job/parallel-job -n step12 --timeout=90s
kubectl get job parallel-job -n step12
kubectl get pod -n step12 -l job-name=parallel-job -o wide  # AGE로 물결 확인

echo "### 12-3. 실패 Job + 재시도 (backoffLimit=2) ###"
kubectl apply -f manifests/job-fail.yaml
kubectl wait --for=condition=failed job/fail-job -n step12 --timeout=120s
kubectl get job fail-job -n step12
kubectl get pod -n step12 -l job-name=fail-job              # Error 파드 3개
kubectl describe job fail-job -n step12 | tail -20          # BackoffLimitExceeded

echo "### 12-4. CronJob (매 1분) ###"
kubectl apply -f manifests/cronjob.yaml
echo "CronJob이 Job을 만들 때까지 대기 (최대 ~150초)..."
for i in $(seq 1 30); do
  n=$(kubectl get jobs -n step12 --no-headers 2>/dev/null | grep -c hello-cron || true)
  echo "  poll $i: cron jobs=$n"
  [ "$n" -ge 2 ] && break
  sleep 15
done
kubectl get cronjob -n step12
kubectl get jobs -n step12
kubectl get pods -n step12 | grep hello-cron

echo "### 12-5. DaemonSet (노드마다 1개, 컨트롤플레인 제외) ###"
kubectl apply -f manifests/daemonset.yaml
kubectl rollout status daemonset/node-agent -n step12 --timeout=90s
kubectl get pod -n step12 -l app=node-agent -o wide        # 워커 2대에만
kubectl get daemonset -n step12                            # DESIRED 2

echo "### 12-6. 컨트롤플레인 taint 확인 ###"
kubectl describe node learn-control-plane | grep -i taint

echo "### 12-7. toleration 추가 → 컨트롤플레인에도 배치 ###"
kubectl apply -f manifests/daemonset-toleration.yaml
kubectl rollout status daemonset/node-agent -n step12 --timeout=90s
kubectl get pod -n step12 -l app=node-agent -o wide        # 3대 모두 (DESIRED 3)
kubectl get daemonset -n step12

echo "### 정리: 네임스페이스 삭제 (Job/CronJob/DaemonSet 전부 제거) ###"
kubectl delete namespace step12
kubectl get ns step12 2>&1 || echo "step12 네임스페이스 삭제 완료"
echo "노드는 전혀 변경하지 않았습니다 (toleration은 파드 스펙)."
