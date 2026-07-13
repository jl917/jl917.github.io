#!/usr/bin/env bash
# Step 25 — 종합 실습: 3-tier 앱 배포와 운영
# README 의 명령을 순서대로 담았습니다. 한 줄씩 실행하며 결과를 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

# 컨텍스트 확인 (kind-learn 이어야 함)
kubectl config current-context

# --- 25-1. 배포 (아래 계층부터) ---
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/01-config.yaml      # ConfigMap + Secret
kubectl apply -f manifests/10-datastore.yaml   # postgres StatefulSet + Headless Service
kubectl apply -f manifests/20-backend.yaml     # agnhost Deployment + ClusterIP
kubectl apply -f manifests/30-frontend.yaml    # hello-kubernetes Deployment + ClusterIP + NodePort
kubectl rollout status statefulset/postgres -n step25 --timeout=120s
kubectl rollout status deploy/backend -n step25 --timeout=120s
kubectl rollout status deploy/frontend -n step25 --timeout=120s
kubectl get all -n step25

# --- 25-2. 계층 통신 ---
FE=$(kubectl get pod -n step25 -l tier=frontend -o jsonpath='{.items[0].metadata.name}')
BE=$(kubectl get pod -n step25 -l tier=backend  -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step25 $FE -- wget -qO- http://backend.step25.svc.cluster.local:8080/hostname
kubectl exec -n step25 $FE -- wget -qO- "http://backend.step25.svc.cluster.local:8080/echo?msg=order-42"
kubectl get endpoints -n step25

# --- 25-3. 설정/비밀 주입 확인 ---
kubectl exec -n step25 $FE -- printenv MESSAGE BACKEND_URL
kubectl exec -n step25 $BE -- printenv DB_HOST DB_PASSWORD

# --- 25-4. 데이터 영속성 ---
kubectl get pvc -n step25
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "CREATE TABLE IF NOT EXISTS orders(id serial primary key, item text);"
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "INSERT INTO orders(item) VALUES ('shoes'),('hat');"
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "SELECT * FROM orders;"
kubectl delete pod postgres-0 -n step25
kubectl wait --for=condition=Ready pod/postgres-0 -n step25 --timeout=90s
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "SELECT count(*) FROM orders;"   # 여전히 2

# --- 25-5. 프로브/리소스 ---
FP=$(kubectl get pod -n step25 -l tier=frontend -o jsonpath='{.items[0].metadata.name}')
kubectl describe pod $FP -n step25 | grep -E 'Liveness|Readiness|Startup|Limits|Requests|cpu:|memory:|QoS'

# --- 25-6. HPA (metrics-server 있을 때) ---
kubectl apply -f manifests/40-hpa.yaml
kubectl get hpa -n step25
# (도전) 부하 걸어 스케일업 관찰:
# kubectl run loadgen -n step25 --image=busybox:1.36 --restart=Never -- /bin/sh -c \
#   "while true; do wget -q -O /dev/null http://backend.step25.svc.cluster.local:8080/echo?msg=x; done"
# watch kubectl get hpa backend -n step25
# kubectl delete pod loadgen -n step25
# metrics-server 가 없으면 수동 스케일: kubectl scale deploy/backend -n step25 --replicas=4

# --- 25-7. 외부 노출 ---
curl -s http://localhost:30080/ | grep -Eo 'Hello Kubernetes!|Shop v[0-9][^<"]*'
kubectl apply -f manifests/41-ingress.yaml   # ingress-nginx 있을 때
kubectl get ingress -n step25
kubectl exec -n step25 $BE -- wget -qO- --header="Host: shop.local" \
  http://ingress-nginx-controller.ingress-nginx.svc.cluster.local:80/ | grep -Eo 'Shop v[0-9][^<"]*'

# --- 25-8. 무중단 롤링 업데이트 ---
kubectl patch configmap app-config -n step25 --type merge -p '{"data":{"MESSAGE":"Shop v2  |  zero-downtime rollout"}}'
kubectl set env deploy/frontend -n step25 ROLL_TS="$(date +%s)"
for i in $(seq 1 40); do curl -s -o /dev/null -w "%{http_code} " http://localhost:30080/; sleep 0.5; done; echo
kubectl rollout status deploy/frontend -n step25
curl -s http://localhost:30080/ | grep -Eo 'Shop v[0-9][^<"]*'
kubectl rollout history deploy/frontend -n step25
# 되돌리기: kubectl rollout undo deploy/frontend -n step25

# --- 25-9. 정리 ---
kubectl delete namespace step25
kubectl get ns | grep step25 || echo "step25 없음 — 정리 완료"
