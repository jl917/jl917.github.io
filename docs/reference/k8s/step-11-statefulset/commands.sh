#!/usr/bin/env bash
# Step 11 — 스테이트풀셋
# README 에 나오는 명령들을 순서대로 담았습니다. 한 줄씩 복사해 실행하며 결과를 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context

# --- 11-2/11-3. 배포: 네임스페이스 + 헤드리스 서비스 + 스테이트풀셋 + DNS 클라이언트 ---
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/01-redis-statefulset.yaml
kubectl apply -f manifests/02-dns-client.yaml
kubectl wait --for=condition=Ready pod -l app=redis -n step11 --timeout=90s

# --- 11-1. Deployment 와 이름 규칙 대조 (대조용, 곧 삭제) ---
kubectl create deployment web --image=nginx:1.27-alpine --replicas=3 -n step11
kubectl rollout status deployment/web -n step11 --timeout=90s
kubectl get pod -n step11 -l app=web     # web-<해시>-<랜덤> 랜덤 이름
kubectl get pod -n step11 -l app=redis   # redis-0,1,2 서수 고정 이름
kubectl delete deployment web -n step11

# --- 11-2. 헤드리스 서비스 확인 (CLUSTER-IP = None) ---
kubectl get svc redis -n step11

# --- 11-3. volumeClaimTemplates: 파드당 PVC 1개 ---
kubectl get pvc -n step11
kubectl describe statefulset redis -n step11 | sed -n '/Events:/,$p'  # Create Claim ... 로그

# --- 11-4. 순차 생성 (0→1→2) 관찰 ---
kubectl scale statefulset redis -n step11 --replicas=0
kubectl wait --for=delete pod/redis-0 -n step11 --timeout=60s
kubectl scale statefulset redis -n step11 --replicas=3
# 다른 터미널에서 watch 로 순서 관찰:  kubectl get pod -n step11 -l app=redis -w
kubectl wait --for=condition=Ready pod -l app=redis -n step11 --timeout=90s
kubectl get pod -n step11 -l app=redis     # AGE 가 0→1→2 순서로 벌어짐

# --- 11-5. 안정적 DNS ---
kubectl exec -n step11 dns-client -- nslookup redis-0.redis.step11.svc.cluster.local
kubectl exec -n step11 dns-client -- nslookup redis.step11.svc.cluster.local  # 헤드리스 = 전체 파드 IP
kubectl exec -n step11 redis-1 -- cat /etc/resolv.conf
kubectl exec -n step11 redis-1 -- redis-cli -h redis-0.redis ping             # 짧은 이름(앱 리졸버)

# --- 11-6. 안정적 스토리지 증명: write → delete → 재생성 후 read ---
kubectl exec -n step11 redis-0 -- redis-cli set course "k8s-step11"
kubectl exec -n step11 redis-0 -- redis-cli set owner "julong"
kubectl get pvc data-redis-0 -n step11 -o custom-columns='PVC:.metadata.name,VOLUME:.spec.volumeName,STATUS:.status.phase'
kubectl delete pod redis-0 -n step11
kubectl wait --for=condition=Ready pod/redis-0 -n step11 --timeout=90s
kubectl get pvc data-redis-0 -n step11 -o custom-columns='PVC:.metadata.name,VOLUME:.spec.volumeName,STATUS:.status.phase'  # 같은 VOLUME
kubectl exec -n step11 redis-0 -- redis-cli get course   # k8s-step11 (데이터 생존)
kubectl exec -n step11 redis-0 -- redis-cli get owner    # julong

# --- 11-7. 역순 삭제 (2→1→0) + PVC 는 남는다 ---
kubectl scale statefulset redis -n step11 --replicas=1
# 다른 터미널:  kubectl get pod -n step11 -l app=redis -w   → redis-2 먼저, 그다음 redis-1 종료
kubectl wait --for=delete pod/redis-2 -n step11 --timeout=60s
kubectl wait --for=delete pod/redis-1 -n step11 --timeout=60s
kubectl get pod -n step11 -l app=redis   # redis-0 만 남음
kubectl get pvc -n step11                # PVC 3개는 그대로 (스케일다운으로 안 지워짐)

# --- 정리 (중요): 순서 주의 ---
# PVC 에는 pvc-protection 파이널라이저가 있어, 파드가 물고 있으면 'delete pvc' 가 멈춰버린다.
# 따라서 (1) 워크로드부터 지워 파드를 없애고 → (2) PVC → (3) 네임스페이스 순서로 지운다.
kubectl delete statefulset redis -n step11 --wait=false
kubectl delete pod --all -n step11 --wait=false
kubectl wait --for=delete pod --all -n step11 --timeout=90s
kubectl delete pvc -n step11 --all       # volumeClaimTemplates PVC 는 자동 삭제 안 됨 → 반드시 수동
kubectl delete namespace step11 --wait=false
sleep 5
kubectl get pv | grep step11 || echo "step11 관련 PV 없음 (정리 완료)"
kubectl get ns | grep step11 || echo "step11 네임스페이스 없음 (정리 완료)"
