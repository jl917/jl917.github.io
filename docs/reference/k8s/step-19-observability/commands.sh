#!/usr/bin/env bash
# Step 19 — 관측성
export PATH="/opt/homebrew/bin:$PATH"
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# 실습 파드 배포
kubectl apply -f "$HERE/manifests/namespace.yaml"
kubectl apply -f "$HERE/manifests/multi-container.yaml"
kubectl apply -f "$HERE/manifests/flaky-probe.yaml"
echo "▶ flaky 가 재시작할 때까지(약 90초) 기다립니다."
sleep 90
kubectl get pods -n step19 -o wide

# 1) Events (시간순)
kubectl get events -n step19 --sort-by='.lastTimestamp' | tail -12
kubectl get events -n step19 --field-selector type=Warning

# 2) top
kubectl top pods -n step19 --containers

# 3) logs 옵션들
kubectl logs multi -n step19 -c sidecar --tail=3
kubectl logs multi -n step19 -c app --since=6s
kubectl logs flaky -n step19 --previous --tail=5      # 재시작 전 로그
# kubectl logs multi -n step19 -c app -f              # 실시간 (Ctrl-C)

# 4) describe (아래 Events 부터 읽기)
kubectl describe pod flaky -n step19 | sed -n '/Events:/,$p'

# 5) 상태 필드
kubectl get pods -n step19 -o wide
kubectl get pod flaky -n step19 -o jsonpath='restartCount={.status.containerStatuses[0].restartCount}{"\n"}lastState={.status.containerStatuses[0].lastState.terminated.reason}{"\n"}'

# 6) -o 포맷
kubectl get pods -n step19 -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\t"}{.spec.nodeName}{"\n"}{end}'

# 7) ephemeral 디버그 컨테이너
kubectl debug flaky -n step19 --image=busybox:1.36 --target=web -c debugger \
  -- sh -c 'ps aux | head -5; wget -qO- localhost | head -1'
kubectl get pod flaky -n step19 -o jsonpath='{.spec.ephemeralContainers[*].name}'; echo
kubectl logs flaky -n step19 -c debugger

# 정리 (metrics-server 는 보존)
kubectl delete namespace step19
