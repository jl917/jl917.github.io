#!/usr/bin/env bash
# Step 06 — 서비스와 DNS
# README 순서 그대로. 한 줄씩 실행하며 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

kubectl config current-context   # kind-learn 이어야 함

# --- 6-1~2. 백엔드 + 서비스들 ---
kubectl apply -f manifests/backend.yaml
kubectl rollout status deployment/hello -n step06
kubectl apply -f manifests/services.yaml
kubectl get svc -n step06 -o wide
kubectl get endpoints -n step06
kubectl get endpointslice -n step06

# --- 6-3. ClusterIP + DNS (클라이언트 파드로) ---
kubectl run client --image=busybox:1.36 -n step06 --restart=Never --command -- sleep 3600
kubectl wait --for=condition=Ready pod/client -n step06 --timeout=60s
kubectl exec -n step06 client -- nslookup hello-clusterip.step06.svc.cluster.local
kubectl exec -n step06 client -- wget -qO- http://hello-clusterip/ | grep -A1 pod:

# --- 6-4. NodePort — 호스트에서 접근 (혼자 실습 시 30080) ---
curl -s http://localhost:30080/ | grep -A1 pod:
for i in $(seq 1 8); do curl -s http://localhost:30080/ | grep -A1 '<th>pod:</th>' | grep td; done | sort | uniq -c

# --- 6-5. LoadBalancer 는 kind 에서 pending ---
kubectl get svc hello-lb -n step06
kubectl describe svc hello-lb -n step06 | grep -iE "Type|LoadBalancer"

# --- 6-6. Headless (파드 IP 직접 반환) ---
kubectl exec -n step06 client -- nslookup hello-headless.step06.svc.cluster.local

# --- 6-8. 함정: 셀렉터 오타 → 트래픽 0 ---
kubectl apply -f manifests/broken-service.yaml          # 정상 apply 됨
kubectl get endpoints hello-broken -n step06            # <none> (비어있음!)
kubectl get svc hello-broken hello-clusterip -n step06 -o custom-columns='NAME:.metadata.name,SELECTOR:.spec.selector'
kubectl exec -n step06 client -- wget -qO- --timeout=2 http://hello-broken/   # Connection refused
# 수리
kubectl patch svc hello-broken -n step06 -p '{"spec":{"selector":{"app":"hello"}}}'
kubectl get endpoints hello-broken -n step06            # 즉시 채워짐

# --- 정리 ---
kubectl delete namespace step06
