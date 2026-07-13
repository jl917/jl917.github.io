#!/usr/bin/env bash
# Step 24 — 트러블슈팅 플레이북 실습 명령 모음
# 모든 실습은 네임스페이스 step24 안에서만 진행한다.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"

cd "$(dirname "$0")"

# ─────────────────────────────────────────────────────────────
# 0. 준비: 네임스페이스 생성
# ─────────────────────────────────────────────────────────────
kubectl create ns step24 --dry-run=client -o yaml | kubectl apply -f -
kubectl label ns step24 course=k8s-learn step=24 --overwrite

# 모든 깨진 매니페스트를 한꺼번에 적용해 장애 상태를 재현한다.
kubectl apply -f manifests/01-imagepullbackoff.yaml
kubectl apply -f manifests/02-crashloopbackoff.yaml
kubectl apply -f manifests/03a-pending-cpu.yaml
kubectl apply -f manifests/03b-pending-pvc.yaml
kubectl apply -f manifests/04-oomkilled.yaml
kubectl apply -f manifests/05-service-endpoints.yaml
kubectl apply -f manifests/06-containercreating.yaml

echo "장애가 무르익도록 30초 대기..."
sleep 30
kubectl get pods -n step24 -o wide

# ─────────────────────────────────────────────────────────────
# 1. ImagePullBackOff
# ─────────────────────────────────────────────────────────────
kubectl get pod broken-image -n step24
kubectl describe pod broken-image -n step24 | sed -n '/Events:/,$p'
# 해결: 올바른 태그로 교체
kubectl set image pod/broken-image web=nginx:1.27-alpine -n step24
kubectl get pod broken-image -n step24

# ─────────────────────────────────────────────────────────────
# 2. CrashLoopBackOff
# ─────────────────────────────────────────────────────────────
kubectl get pod crasher -n step24
kubectl logs crasher -n step24 --previous          # 죽은 컨테이너의 로그
kubectl describe pod crasher -n step24 | sed -n '/Last State:/,/Restart Count:/p'
# 해결: 종료하지 않는 정상 커맨드로 교체
kubectl delete pod crasher -n step24 --now
kubectl run crasher --image=busybox:1.36 -n step24 --labels=demo=crashloopbackoff \
  --command -- sh -c "echo 'boot up...'; echo 'running ok'; sleep 3600"
kubectl get pod crasher -n step24

# ─────────────────────────────────────────────────────────────
# 3a. Pending — 스케줄 불가(cpu=1000)
# ─────────────────────────────────────────────────────────────
kubectl describe pod too-big -n step24 | sed -n '/Events:/,$p'   # 0/3 nodes ... Insufficient cpu
# 해결: 현실적인 requests 로 재생성
kubectl delete pod too-big -n step24 --now
kubectl run too-big --image=nginx:1.27-alpine -n step24 --labels=demo=pending-unschedulable \
  --overrides='{"spec":{"containers":[{"name":"web","image":"nginx:1.27-alpine","resources":{"requests":{"cpu":"100m"}}}]}}'
kubectl get pod too-big -n step24

# ─────────────────────────────────────────────────────────────
# 3b. Pending — PVC 미바인딩
# ─────────────────────────────────────────────────────────────
kubectl get pvc ghost-pvc -n step24
kubectl describe pod waits-for-pvc -n step24 | sed -n '/Events:/,$p'   # unbound immediate PVC
kubectl describe pvc ghost-pvc -n step24 | sed -n '/Events:/,$p'       # storageclass ... not found
# 해결: 실제 존재하는 StorageClass(standard) 로 PVC 재생성
kubectl get storageclass
kubectl delete pod waits-for-pvc -n step24 --now
kubectl delete pvc ghost-pvc -n step24 --now
cat <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: ghost-pvc, namespace: step24 }
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: standard
  resources: { requests: { storage: 1Gi } }
EOF
kubectl apply -f manifests/03b-pending-pvc.yaml || true   # 파드만 재생성(PVC 는 immutable 이라 무시됨)
sleep 8
kubectl get pvc ghost-pvc -n step24
kubectl get pod waits-for-pvc -n step24

# ─────────────────────────────────────────────────────────────
# 4. OOMKilled (exit 137)
# ─────────────────────────────────────────────────────────────
kubectl get pod memory-hog -n step24
kubectl get pod memory-hog -n step24 -o yaml | sed -n '/lastState:/,/name:/p'   # reason: OOMKilled, exitCode 137
# 해결: memory limit 상향, 워크로드 정상화
kubectl delete pod memory-hog -n step24 --now
kubectl run memory-hog --image=busybox:1.36 -n step24 --labels=demo=oomkilled \
  --overrides='{"spec":{"containers":[{"name":"hog","image":"busybox:1.36","command":["sh","-c","echo ok; sleep 3600"],"resources":{"limits":{"memory":"64Mi"},"requests":{"memory":"32Mi"}}}]}}'
kubectl get pod memory-hog -n step24

# ─────────────────────────────────────────────────────────────
# 5. Service Endpoints 비어있음
# ─────────────────────────────────────────────────────────────
kubectl get pod echo-server -n step24 --show-labels
kubectl get endpoints echo-svc -n step24                       # ENDPOINTS <none>
kubectl get svc echo-svc -n step24 -o jsonpath='{.spec.selector}'; echo   # {"app":"web"} 불일치
# 해결: 셀렉터를 파드 레이블(app=echo)에 맞춤
kubectl patch svc echo-svc -n step24 -p '{"spec":{"selector":{"app":"echo"}}}'
kubectl get endpoints echo-svc -n step24                       # 이제 채워짐
kubectl run curl-test --image=registry.k8s.io/e2e-test-images/agnhost:2.47 -n step24 --restart=Never \
  --command -- sh -c "wget -qO- --timeout=3 http://echo-svc.step24.svc.cluster.local"
sleep 6; kubectl logs curl-test -n step24                      # hello from echo
kubectl delete pod curl-test -n step24 --now

# ─────────────────────────────────────────────────────────────
# 6. ContainerCreating 지속 (Secret 없음)
# ─────────────────────────────────────────────────────────────
kubectl get pod needs-secret -n step24
kubectl describe pod needs-secret -n step24 | sed -n '/Events:/,$p'   # FailedMount ... not found
# 해결: 참조하던 Secret 생성 (kubelet 이 backoff 후 자동 마운트, 최대 ~2분)
kubectl create secret generic app-credentials -n step24 \
  --from-literal=username=admin --from-literal=password=s3cr3t
kubectl wait --for=condition=Ready pod/needs-secret -n step24 --timeout=180s || true
kubectl get pod needs-secret -n step24
kubectl exec needs-secret -n step24 -- ls -l /etc/cred

# ─────────────────────────────────────────────────────────────
# 7. 진단 도구
# ─────────────────────────────────────────────────────────────
kubectl get events -n step24 --sort-by=.lastTimestamp | tail -15
# 툴 없는 파드(http-echo)에 임시 컨테이너 붙여 내부 접속 확인
kubectl debug -n step24 echo-server -it --image=busybox:1.36 --target=echo \
  -- sh -c "wget -qO- http://localhost:8080" || true

# ─────────────────────────────────────────────────────────────
# 정리: 네임스페이스째 삭제 (안의 모든 리소스 제거)
# ─────────────────────────────────────────────────────────────
kubectl delete namespace step24
kubectl get ns
