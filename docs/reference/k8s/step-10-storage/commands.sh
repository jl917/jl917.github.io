#!/usr/bin/env bash
export PATH="/opt/homebrew/bin:$PATH"
# Step 10 — 스토리지 실습 재현 스크립트
# 실행: bash commands.sh   (kind-learn 컨텍스트에서)
set -euo pipefail
cd "$(dirname "$0")"

echo "### 컨텍스트 확인"
kubectl config current-context   # kind-learn 이어야 함

echo "### 네임스페이스 생성"
kubectl create namespace step10 --dry-run=client -o yaml | kubectl apply -f -

echo
echo "########## 10-1. emptyDir 쓰기/읽기 ##########"
kubectl apply -f manifests/01-emptydir-pod.yaml
kubectl wait --for=condition=Ready pod/emptydir-demo -n step10 --timeout=60s
kubectl exec -n step10 emptydir-demo -- sh -c 'echo "hello emptyDir @ $(date)" > /data/note.txt'
kubectl exec -n step10 emptydir-demo -- cat /data/note.txt
kubectl exec -n step10 emptydir-demo -- ls -l /data

echo
echo "########## 10-2. emptyDir 휘발성 증명 (파드 삭제 → 데이터 소멸) ##########"
kubectl apply -f manifests/02-emptydir-deploy.yaml
kubectl rollout status deploy/emptydir-web -n step10 --timeout=60s
POD=$(kubectl get pod -n step10 -l app=emptydir-web -o jsonpath='{.items[0].metadata.name}')
echo "first pod: $POD"
kubectl exec -n step10 "$POD" -- sh -c 'echo "data written to emptyDir" > /data/note.txt'
kubectl exec -n step10 "$POD" -- cat /data/note.txt
kubectl delete pod -n step10 "$POD"
kubectl rollout status deploy/emptydir-web -n step10 --timeout=60s
NEWPOD=$(kubectl get pod -n step10 -l app=emptydir-web -o jsonpath='{.items[0].metadata.name}')
echo "new pod: $NEWPOD"
kubectl exec -n step10 "$NEWPOD" -- ls -la /data
kubectl exec -n step10 "$NEWPOD" -- cat /data/note.txt || echo "==> note.txt GONE (emptyDir 은 파드와 함께 사라짐)"

echo
echo "########## 10-3. hostPath (⚠️ 학습용, 안전한 임시 경로만) ##########"
kubectl apply -f manifests/03-hostpath-pod.yaml
kubectl wait --for=condition=Ready pod/hostpath-demo -n step10 --timeout=60s
kubectl exec -n step10 hostpath-demo -- sh -c 'echo "written via hostPath" > /host-data/from-pod.txt'
kubectl exec -n step10 hostpath-demo -- cat /host-data/from-pod.txt
kubectl get pod hostpath-demo -n step10 -o wide

echo
echo "########## 10-4/5. StorageClass + 동적 프로비저닝 ##########"
kubectl get storageclass
kubectl apply -f manifests/04-pvc.yaml
sleep 3
echo "--- 파드 없어서 Pending 이 정상 (WaitForFirstConsumer) ---"
kubectl get pvc -n step10
kubectl describe pvc data-pvc -n step10 | grep -A3 "Events:" || true
kubectl apply -f manifests/05-writer-pod.yaml
kubectl wait --for=condition=Ready pod/pvc-writer -n step10 --timeout=90s
echo "--- 파드가 뜨자 Bound + PV 자동 생성 ---"
kubectl get pvc,pv -n step10

echo
echo "########## 10-6. PVC 영속성 증명 (파드 삭제해도 데이터 생존) ##########"
kubectl exec -n step10 pvc-writer -- sh -c 'echo "persistent data written by pvc-writer @ $(date)" > /data/important.txt'
kubectl exec -n step10 pvc-writer -- cat /data/important.txt
kubectl delete pod pvc-writer -n step10
kubectl get pvc data-pvc -n step10
kubectl apply -f manifests/06-reader-pod.yaml
kubectl wait --for=condition=Ready pod/pvc-reader -n step10 --timeout=90s
echo "--- 삭제된 파드가 쓴 파일을, 새 파드가 읽는다 ---"
kubectl exec -n step10 pvc-reader -- cat /data/important.txt
echo "==> 데이터 살아있음! PVC/PV 는 파드와 독립적으로 존재한다."

echo
echo "########## 정리 (네임스페이스 삭제 = PVC 삭제 = PV 자동 삭제) ##########"
kubectl delete namespace step10
echo "--- step10 관련 PV 가 남아있지 않은지 확인 (비어 있어야 함) ---"
kubectl get pv | grep step10 || echo "OK: step10 PV 없음"
