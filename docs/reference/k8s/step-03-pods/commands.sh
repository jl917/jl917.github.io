#!/usr/bin/env bash
# =====================================================================
# Step 03 commands.sh — 파드
# =====================================================================
export PATH="/opt/homebrew/bin:$PATH"
HERE="$(cd "$(dirname "$0")" && pwd)"

kubectl create namespace step03

# [3-2] 파드 하나 띄우고 관찰
kubectl apply -f "$HERE/manifests/pod.yaml"
sleep 6
kubectl -n step03 get pod web -o wide

# [3-3] 3대 도구
kubectl -n step03 logs web | head -5
kubectl -n step03 exec web -- cat /etc/nginx/nginx.conf | head -5
kubectl -n step03 exec web -- ls /usr/share/nginx/html
kubectl -n step03 describe pod web | tail -20     # 맨 아래 Events 확인

# [3-5] 멀티 컨테이너 + initContainer + 공유 볼륨
kubectl apply -f "$HERE/manifests/multi-container.yaml"
sleep 12
kubectl -n step03 get pod multi                    # READY 2/2
kubectl -n step03 logs multi -c sidecar | head -3  # 특정 컨테이너 로그
# init 이 만든 파일을 web 이 서빙하는지 (볼륨 공유 증명)
kubectl -n step03 exec multi -c web -- cat /usr/share/nginx/html/index.html

# [3-6] CrashLoopBackOff 재현과 진단
kubectl apply -f "$HERE/manifests/crasher.yaml"
sleep 30
kubectl -n step03 get pod crasher                  # Error → CrashLoopBackOff, RESTARTS 증가
kubectl -n step03 describe pod crasher | grep -A7 "Events:"
kubectl -n step03 logs crasher --previous          # ★ 죽기 직전 로그가 원인 단서

# 정리
kubectl delete namespace step03
