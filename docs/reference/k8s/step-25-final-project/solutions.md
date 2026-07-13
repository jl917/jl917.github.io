# Step 25 — 정답과 해설 (solutions)

정답 매니페스트는 `manifests/` 에 그대로 있습니다(전문은 강의 페이지의 [실습 파일](index.md#실습-파일) 섹션에서 볼 수 있습니다). 이 문서는 **왜 그렇게 썼는지**, 그리고 실습에서 자주 막히는 지점을 해설합니다. 먼저 [problems.md](problems.md) 를 스스로 풀어본 뒤 읽으세요.

## 한 번에 적용하기

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/01-config.yaml
kubectl apply -f manifests/10-datastore.yaml
kubectl apply -f manifests/20-backend.yaml
kubectl apply -f manifests/30-frontend.yaml
kubectl apply -f manifests/40-hpa.yaml     # metrics-server 있을 때
kubectl apply -f manifests/41-ingress.yaml # ingress-nginx 있을 때
```

---

## M2. ConfigMap / Secret — `manifests/01-config.yaml`

핵심은 **민감/비민감 분리**입니다. DB 이름·배너·백엔드 URL 은 ConfigMap, 비밀번호만 Secret.

```yaml
apiVersion: v1
kind: Secret
metadata: { name: db-secret, namespace: step25 }
type: Opaque
stringData:
  POSTGRES_PASSWORD: "s3cr3t-p@ss"   # stringData: 평문으로 쓰면 apiserver 가 base64 로 저장
```

- **`stringData` vs `data`**: `data` 는 직접 base64 를 넣어야 하고, `stringData` 는 평문을 쓰면 저장 시 인코딩됩니다. 실습·가독성은 `stringData` 가 편합니다.
- 주입은 `valueFrom.configMapKeyRef` / `valueFrom.secretKeyRef` 로 키 단위. 통째로 주입하려면 `envFrom` 도 가능하지만, 키 단위가 명시적이라 안전합니다.

## M3. datastore — `manifests/10-datastore.yaml`

```yaml
kind: Service           # 헤드리스: clusterIP: None → postgres-0.postgres DNS
spec: { clusterIP: None, selector: {app: shop, tier: datastore}, ports: [{port: 5432}] }
---
kind: StatefulSet
spec:
  serviceName: postgres            # 헤드리스 서비스 이름과 일치해야 함
  volumeClaimTemplates:            # 파드마다 PVC 자동 생성 → 영속
    - metadata: { name: data }
      spec: { accessModes: [ReadWriteOnce], resources: { requests: { storage: 1Gi } } }
```

자주 막히는 곳:
- **`PGDATA` 하위 디렉터리**: PV 를 `/var/lib/postgresql/data` 에 마운트하면 루트에 `lost+found` 가 생겨 initdb 가 "directory not empty" 로 실패할 수 있습니다. `PGDATA=/var/lib/postgresql/data/pgdata` 로 피합니다.
- **`serviceName` 불일치**: StatefulSet 의 `serviceName` 은 헤드리스 Service 이름과 같아야 안정 DNS 가 생깁니다.
- **프로브는 `exec`**: postgres 는 HTTP 가 없으므로 `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB` 를 exec 프로브로.

## M4. backend — `manifests/20-backend.yaml`

```yaml
containers:
- name: netexec
  image: registry.k8s.io/e2e-test-images/agnhost:2.47
  args: ["netexec", "--http-port=8080"]
  readinessProbe: { httpGet: { path: /healthz, port: 8080 } }
  resources: { requests: { cpu: 25m, memory: 32Mi }, limits: { cpu: 200m, memory: 128Mi } }
```

- **`requests.cpu` 필수**: HPA 는 요청량 대비 사용률을 계산하므로 requests 가 없으면 CPU HPA 가 동작하지 않습니다.
- **`maxUnavailable: 0`**: 무중단 롤링을 위해 백엔드도 동일하게 설정.

## M5. frontend — `manifests/30-frontend.yaml`  ← 가장 많이 실패하는 지점

문제에서 경고한 대로, 순진하게 짜면 **CrashLoopBackOff** 에 빠집니다. 실제로 두 번 실패했습니다.

**실패 1 — liveness 조기 발동**
- `livenessProbe.initialDelaySeconds: 10` 인데 hello-kubernetes 는 기동에 ~13초. liveness 가 먼저 실패해 컨테이너를 죽임 → 재시작 반복.
- 진단: `describe` Events 에 `Liveness probe failed: ... connection refused` + `Killing`.

**실패 2 — OOMKilled**
- `limits.memory: 128Mi` 로는 Node.js 런타임이 부족 → `lastState.terminated.reason: OOMKilled, exitCode: 137`.
- 진단: `kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState}'`.

**정답 — startupProbe + 넉넉한 메모리**
```yaml
startupProbe:                    # 느린 기동을 감싼다 (period 3s × failure 20 = 최대 60초)
  httpGet: { path: /, port: 8080 }
  periodSeconds: 3
  failureThreshold: 20
livenessProbe:  { httpGet: { path: /, port: 8080 }, periodSeconds: 10 }  # startup 성공 후에만 작동
readinessProbe: { httpGet: { path: /, port: 8080 }, periodSeconds: 5 }
resources:
  requests: { cpu: 25m, memory: 128Mi }
  limits:   { cpu: 200m, memory: 256Mi }   # 128Mi 는 OOM — 256Mi 로
```

교훈: **느리게 뜨는 앱에는 startupProbe**, **런타임에 맞는 메모리 limit**. [Step 08](../step-08-health-probes/index.md)·[Step 09](../step-09-resources/index.md) 의 핵심이 여기 모입니다.

## M8. 영속성 — 채점 방법

```bash
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "CREATE TABLE IF NOT EXISTS orders(id serial, item text);"
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "INSERT INTO orders(item) VALUES ('shoes'),('hat');"
kubectl delete pod postgres-0 -n step25
kubectl wait --for=condition=Ready pod/postgres-0 -n step25 --timeout=90s
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "SELECT count(*) FROM orders;"   # 2 이면 통과
```
`emptyDir` 로 만들었다면 여기서 0 이 나옵니다. 그게 StatefulSet+PVC 를 쓰는 이유입니다.

## M10. HPA — `manifests/40-hpa.yaml`

```yaml
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { kind: Deployment, name: backend }
  minReplicas: 2
  maxReplicas: 6
  metrics:
  - type: Resource
    resource: { name: cpu, target: { type: Utilization, averageUtilization: 60 } }
```

- `TARGETS` 가 `<unknown>` → metrics-server 부재. `kubectl scale deploy/backend --replicas=4` 로 대체하고 그 사실을 명시하면 통과.
- 부하 테스트로 스케일업을 관찰(강의 본문 25-6 에 실측: cpu 312% 에서 2→6).

## M11. 무중단 롤링 업데이트

무중단의 열쇠는 전략 설정입니다.
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # 새 파드 Ready 후에만 옛 파드 제거
```
- `maxUnavailable: 0` 이면 항상 원하는 수 이상이 Ready 상태를 유지 → 요청이 끊기지 않음.
- **함정**: ConfigMap 만 바꾸면 파드는 그대로입니다. 롤아웃을 유발(`kubectl set env`, 또는 `kubectl rollout restart deploy/frontend`)해야 새 값이 반영됩니다.

## 채점 자동 점검 스니펫

```bash
echo -n "M3 PVC Bound: "; kubectl get pvc data-postgres-0 -n step25 -o jsonpath='{.status.phase}'; echo
echo -n "M4 backend ready: "; kubectl get deploy backend -n step25 -o jsonpath='{.status.readyReplicas}'; echo
echo -n "M5 frontend ready: "; kubectl get deploy frontend -n step25 -o jsonpath='{.status.readyReplicas}'; echo
echo -n "M6 통신: "; kubectl exec -n step25 $(kubectl get pod -n step25 -l tier=frontend -o jsonpath='{.items[0].metadata.name}') -- wget -qO- http://backend.step25.svc.cluster.local:8080/hostname; echo
echo -n "M9 외부: "; curl -s -o /dev/null -w "%{http_code}" http://localhost:30080/; echo
echo    "M10 HPA:"; kubectl get hpa backend -n step25
```

## 흔한 실수 총정리

| 증상 | 원인 | 해결 |
|---|---|---|
| frontend CrashLoop | memory 128Mi OOM / liveness 조기 발동 | limit 256Mi + startupProbe |
| Endpoints `<none>` | 셀렉터↔레이블 불일치, 파드 not Ready | 레이블 맞추고 프로브 점검 |
| postgres 기동 실패 | PGDATA 미설정(lost+found) | `PGDATA=.../pgdata` |
| HPA `<unknown>` | metrics-server 없음 / requests.cpu 없음 | 설치 대기 or 수동 scale / requests 추가 |
| ConfigMap 바꿔도 반영 안 됨 | 파드 자동 갱신 안 함 | `kubectl rollout restart` |
| 롤아웃 중 502 | maxUnavailable>0 + 프로브 부실 | maxUnavailable=0 + readinessProbe |

수고했습니다. → [코스 처음으로](../index.md) · 다음은 [강의 본문](index.md) 의 **이후 학습 로드맵**을 따라가세요.
