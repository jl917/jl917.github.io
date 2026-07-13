#!/usr/bin/env bash
# Step 07 — 설정과 시크릿
# README 순서 그대로. 한 줄씩 실행하며 관찰하세요.
export PATH="/opt/homebrew/bin:$PATH"

kubectl config current-context   # kind-learn 이어야 함

# --- 7-1~2. ConfigMap + Secret ---
kubectl apply -f manifests/config.yaml
kubectl get configmap app-config -n step07
kubectl get secret db-secret -n step07

# --- 7-3. 주입 3가지 (env / envFrom / 볼륨) ---
kubectl apply -f manifests/consumer.yaml
kubectl rollout status deployment/app -n step07
POD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
kubectl exec -n step07 $POD -- sh -c 'echo $APP_COLOR; echo $GREETING; echo $DB_USER; echo $DB_PASSWORD'  # (1)(2)
kubectl exec -n step07 $POD -- ls -l /etc/config                 # (3) ConfigMap 볼륨
kubectl exec -n step07 $POD -- cat /etc/config/app.properties
kubectl exec -n step07 $POD -- cat /etc/secret/DB_PASSWORD       # (3) Secret 볼륨

# --- 7-4. Secret base64 는 암호화가 아님 ---
kubectl get secret db-secret -n step07 -o jsonpath='{.data.DB_PASSWORD}'; echo
kubectl get secret db-secret -n step07 -o jsonpath='{.data.DB_PASSWORD}' | base64 -d; echo   # 평문

# --- 7-5. immutable ConfigMap ---
kubectl apply -f manifests/immutable.yaml
kubectl patch configmap locked-config -n step07 -p '{"data":{"VERSION":"2.0.0"}}'   # 거부됨

# --- 7-6. 함정: 설정 변경이 자동 반영 안 됨 ---
kubectl patch configmap app-config -n step07 -p '{"data":{"APP_COLOR":"green"}}'
kubectl exec -n step07 $POD -- sh -c 'echo APP_COLOR=$APP_COLOR'   # 여전히 blue
sleep 60
kubectl exec -n step07 $POD -- cat /etc/config/APP_COLOR          # green (볼륨은 갱신)
kubectl exec -n step07 $POD -- sh -c 'echo APP_COLOR=$APP_COLOR'   # blue (env 는 stale)
# 해결: rollout restart
kubectl rollout restart deployment/app -n step07
kubectl rollout status deployment/app -n step07
NEWPOD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
kubectl exec -n step07 $NEWPOD -- sh -c 'echo APP_COLOR=$APP_COLOR'   # green

# --- 정리 ---
kubectl delete namespace step07
