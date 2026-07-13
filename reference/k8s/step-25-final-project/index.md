# Step 25 — 종합 실습: 3-tier 애플리케이션 배포와 운영

> **학습 목표**
> - 지금까지 배운 오브젝트를 모아 **프론트 → 백엔드 → 데이터스토어** 3계층 앱을 처음부터 배포한다
> - ConfigMap·Secret 로 설정을 주입하고, Service 로 계층을 잇고, StatefulSet+PVC 로 데이터를 영속화한다
> - 헬스 프로브(startup/readiness/liveness)와 리소스 requests/limits 로 앱을 견고하게 만든다
> - HPA 로 부하에 따라 자동 확장하고, 롤링 업데이트로 **무중단 배포**한다
> - 문제를 스스로 풀어보는 [problems.md](problems.md) 와 정답 해설 [solutions.md](solutions.md) 로 마무리한다
>
> **선행 스텝**: [Step 24 — 트러블슈팅](../step-24-troubleshooting/index.md), 그리고 사실상 [Step 01~24 전부]
> **예상 소요**: 90분

---

## 25-0. 우리가 만들 것

쇼핑몰을 흉내낸 3계층 앱 `shop` 을 하나의 네임스페이스(`step25`)에 배포합니다.

```
        [브라우저]
           │  http://localhost:30080  (NodePort)  / 또는 Ingress(shop.local)
           ▼
   ┌───────────────────┐   ClusterIP:8080
   │ frontend          │   hello-kubernetes  (Deployment, replicas=2)
   │ (웹/프레젠테이션)  │   MESSAGE 배너를 ConfigMap 에서 주입
   └─────────┬─────────┘
             │  http://backend.step25.svc.cluster.local:8080
             ▼
   ┌───────────────────┐   ClusterIP:8080
   │ backend           │   agnhost netexec  (Deployment, replicas=2, HPA 2~6)
   │ (API)             │   /healthz /hostname /echo, DB 비밀번호를 Secret 에서 주입
   └─────────┬─────────┘
             │  postgres.step25.svc.cluster.local:5432
             ▼
   ┌───────────────────┐   Headless Service:5432
   │ postgres          │   postgres:16-alpine (StatefulSet, replicas=1)
   │ (데이터스토어)     │   PVC(volumeClaimTemplates) 로 데이터 영속
   └───────────────────┘
```

| 계층 | 이미지 | 워크로드 | 노출 |
|---|---|---|---|
| frontend | `paulbouwer/hello-kubernetes:1.10.1` | Deployment (2) | ClusterIP + NodePort 30080 + Ingress |
| backend | `registry.k8s.io/e2e-test-images/agnhost:2.47` | Deployment (2) + HPA | ClusterIP (내부 전용) |
| datastore | `postgres:16-alpine` | StatefulSet (1) + PVC | Headless Service (내부 전용) |
| 설정 | — | ConfigMap `app-config` | — |
| 비밀 | — | Secret `db-secret` | — |

> 이 스텝의 `manifests/` 는 **정답(레퍼런스) 매니페스트**입니다. 먼저 [problems.md](problems.md) 를 보고 스스로 작성해 본 뒤, 막히면 여기의 매니페스트와 [solutions.md](solutions.md) 해설을 참고하세요.

---

## 25-1. 배포 순서 — 의존성의 아래에서 위로

설정 → 데이터스토어 → 백엔드 → 프론트 순으로 올립니다. 아래 계층이 준비돼야 위 계층이 참조할 수 있기 때문입니다(엄밀히는 k8s가 알아서 재시도하므로 순서가 절대적이진 않지만, 관찰하기 좋습니다).

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/01-config.yaml      # ConfigMap + Secret
kubectl apply -f manifests/10-datastore.yaml   # postgres StatefulSet + Headless Service
kubectl apply -f manifests/20-backend.yaml     # agnhost Deployment + ClusterIP
kubectl apply -f manifests/30-frontend.yaml    # hello-kubernetes Deployment + ClusterIP + NodePort

kubectl rollout status statefulset/postgres -n step25 --timeout=120s
kubectl rollout status deploy/backend -n step25 --timeout=120s
kubectl rollout status deploy/frontend -n step25 --timeout=120s
```

**실행 결과**
```
namespace/step25 created
configmap/app-config created
secret/db-secret created
service/postgres created
statefulset.apps/postgres created
deployment.apps/backend created
service/backend created
deployment.apps/frontend created
service/frontend created
service/frontend-nodeport created
...
deployment "backend" successfully rolled out
deployment "frontend" successfully rolled out
```

전체를 한눈에 봅니다.

```bash
kubectl get all -n step25
```
```
NAME                           READY   STATUS    RESTARTS   AGE
pod/backend-5bcc866478-s4tcl   1/1     Running   0          9m26s
pod/backend-5bcc866478-xvw6c   1/1     Running   0          9m26s
pod/frontend-cf9c4d88-6tgxl    1/1     Running   0          59s
pod/frontend-cf9c4d88-bzsqb    1/1     Running   0          37s
pod/postgres-0                 1/1     Running   0          9m27s

NAME                        TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)          AGE
service/backend             ClusterIP   10.96.172.68    <none>        8080/TCP         9m26s
service/frontend            ClusterIP   10.96.162.212   <none>        8080/TCP         9m26s
service/frontend-nodeport   NodePort    10.96.155.167   <none>        8080:30080/TCP   9m26s
service/postgres            ClusterIP   None            <none>        5432/TCP         9m27s

NAME                        READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/backend    2/2     2            2           9m27s
deployment.apps/frontend   2/2     2            2           9m26s

NAME                        READY   AGE
statefulset.apps/postgres   1/1     9m27s
```

> ⚠️ **함정 (실제로 겪은 것)**: 처음 `frontend` 를 memory `limit: 128Mi` 로 배포했더니 **CrashLoopBackOff** 에 빠졌습니다. 원인은 두 가지였습니다. (1) hello-kubernetes(Node.js)는 기동에 ~13초가 걸리는데 liveness `initialDelaySeconds: 10` 이 먼저 발동해 죽였고, (2) 128Mi 로는 Node 런타임이 **OOMKilled(exit 137)** 됐습니다. 해법: **startupProbe** 로 느린 기동을 감싸고, memory limit 을 256Mi 로 올렸습니다. [Step 08](../step-08-health-probes/index.md)·[Step 09](../step-09-resources/index.md)·[Step 24](../step-24-troubleshooting/index.md) 의 함정이 여기서 그대로 재현됩니다.

---

## 25-2. 계층 잇기 — Service 와 클러스터 DNS

각 계층은 상대의 **Service DNS 이름**으로 통신합니다. 프론트가 백엔드를 부르는 경로를 직접 확인합니다.

```bash
FE=$(kubectl get pod -n step25 -l tier=frontend -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step25 $FE -- wget -qO- http://backend.step25.svc.cluster.local:8080/hostname
kubectl exec -n step25 $FE -- wget -qO- "http://backend.step25.svc.cluster.local:8080/echo?msg=order-42"
```
```
backend-5bcc866478-s4tcl
order-42
```

Service 는 뒤의 파드들로 부하를 분산합니다. 어떤 파드가 실제로 받는지는 `Endpoints` 로 확인합니다.

```bash
kubectl get endpoints -n step25
```
```
NAME                ENDPOINTS                            AGE
backend             10.244.1.64:8080,10.244.2.64:8080    9m26s
frontend            10.244.1.105:8080,10.244.2.94:8080   9m26s
frontend-nodeport   10.244.1.105:8080,10.244.2.94:8080   9m26s
postgres            10.244.2.67:5432                     9m27s
```

> 💡 **팁**: `Endpoints` 가 비어 있으면(`<none>`) 서비스는 있으나 트래픽이 어디로도 안 갑니다. 십중팔구 **셀렉터와 파드 레이블 불일치** 또는 **파드가 Ready 아님**입니다. [Step 24 의 "Service 로 접속 안 됨"](../step-24-troubleshooting/index.md) 이 이 상황을 다룹니다.

---

## 25-3. 설정과 비밀 주입 — ConfigMap / Secret

`app-config`(ConfigMap)와 `db-secret`(Secret)의 값이 각 파드에 **환경 변수**로 들어갔는지 확인합니다.

```bash
kubectl exec -n step25 $FE -- printenv MESSAGE BACKEND_URL       # 프론트: ConfigMap
BE=$(kubectl get pod -n step25 -l tier=backend -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step25 $BE -- printenv DB_HOST DB_PASSWORD        # 백엔드: Secret 주입
```
```
Shop v1  |  3-tier on Kubernetes
http://backend.step25.svc.cluster.local:8080
postgres.step25.svc.cluster.local
s3cr3t-p@ss
```

Secret 은 `stringData` 로 평문을 쓰면 apiserver 가 base64 로 인코딩해 저장합니다. 파드에는 다시 평문으로 주입됩니다.

> ⚠️ **함정**: Secret 은 **암호화가 아니라 base64 인코딩**입니다. `kubectl get secret db-secret -n step25 -o yaml` 로 값을 누구나 디코딩할 수 있습니다. 진짜 보호는 RBAC(누가 Secret 을 읽을 수 있는가)과 저장소 암호화(EncryptionConfiguration)로 합니다. [Step 07](../step-07-config-secret/index.md)·[Step 16](../step-16-rbac/index.md) 참고.

---

## 25-4. 데이터 영속성 — StatefulSet + PVC

`postgres` 는 StatefulSet 이라 파드 이름이 `postgres-0` 으로 고정되고, `volumeClaimTemplates` 가 PVC `data-postgres-0` 을 자동으로 만들어 붙였습니다.

```bash
kubectl get pvc -n step25
```
```
NAME              STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   AGE
data-postgres-0   Bound    pvc-e920ba1d-2519-4fdb-9d21-bd1a1bffe4ee   1Gi        RWO            standard       3m17s
```

데이터를 넣고, **파드를 지웠다 되살려도 데이터가 남는지** 확인합니다. 이것이 `emptyDir` 과의 결정적 차이입니다.

```bash
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb \
  -c "CREATE TABLE IF NOT EXISTS orders(id serial primary key, item text);"
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb \
  -c "INSERT INTO orders(item) VALUES ('shoes'),('hat');"

kubectl delete pod postgres-0 -n step25                       # 파드 강제 재생성
kubectl wait --for=condition=Ready pod/postgres-0 -n step25 --timeout=90s
kubectl exec -n step25 postgres-0 -- psql -U appuser -d shopdb -c "SELECT count(*) FROM orders;"
```
```
CREATE TABLE
INSERT 0 2
pod "postgres-0" deleted from step25 namespace
pod/postgres-0 condition met
 count
-------
     2
(1 row)
```

파드가 새로 떠도 **같은 PVC 를 다시 마운트**하므로 `orders` 의 2행이 그대로 있습니다.

> 💡 **팁**: postgres 는 PV 루트에 `lost+found` 가 있으면 initdb 가 실패합니다. 그래서 `PGDATA=/var/lib/postgresql/data/pgdata` 로 **하위 디렉터리**를 데이터 경로로 지정했습니다. 스테이트풀 앱을 붙일 땐 이런 디렉터리 관례를 이미지 문서에서 꼭 확인하세요.

---

## 25-5. 견고함 — 프로브와 리소스

`frontend` 파드의 프로브·리소스·QoS 를 봅니다.

```bash
FP=$(kubectl get pod -n step25 -l tier=frontend -o jsonpath='{.items[0].metadata.name}')
kubectl describe pod $FP -n step25 | grep -E 'Liveness|Readiness|Startup|Limits|Requests|cpu:|memory:|QoS'
```
```
    Limits:
      cpu:     200m
      memory:  256Mi
    Requests:
      cpu:      25m
      memory:   128Mi
    Liveness:   http-get http://:8080/ delay=0s timeout=1s period=10s #success=1 #failure=3
    Readiness:  http-get http://:8080/ delay=0s timeout=1s period=5s #success=1 #failure=3
    Startup:    http-get http://:8080/ delay=0s timeout=1s period=3s #success=1 #failure=20
QoS Class:      Burstable
```

- **startupProbe**(period 3s × failure 20 = 최대 60초)가 성공할 때까지 liveness/readiness 를 유예 → 느린 기동을 CrashLoop 로 오인하지 않음
- **readinessProbe** 가 실패하면 Endpoints 에서 빠져 트래픽을 안 받음(죽이진 않음)
- **livenessProbe** 가 실패하면 컨테이너를 재시작
- requests≠limits 이므로 QoS 는 **Burstable**

postgres 는 HTTP 가 아니라 `exec` 프로브(`pg_isready`)를 씁니다. 앱의 성격에 맞는 프로브 타입을 고르는 게 핵심입니다.

---

## 25-6. 자동 확장 — HPA

백엔드에 HPA 를 붙입니다. **metrics-server 가 있어야** 동작합니다.

```bash
kubectl apply -f manifests/40-hpa.yaml
kubectl get hpa -n step25
```
```
NAME      REFERENCE            TARGETS       MINPODS   MAXPODS   REPLICAS   AGE
backend   Deployment/backend   cpu: 4%/60%   2         6         2         40s
```

`TARGETS` 가 `<unknown>` 이면 metrics-server 가 없는 것입니다. 그럴 땐 이 스텝에서는 **수동 스케일**로 대체하세요: `kubectl scale deploy/backend -n step25 --replicas=4`.

metrics-server 가 있는 우리 환경에서는 실제로 부하를 걸어 자동 확장을 관찰할 수 있습니다.

```bash
# 백엔드 서비스를 busybox 로 집중 호출해 CPU 부하 생성
kubectl run loadgen -n step25 --image=busybox:1.36 --restart=Never -- /bin/sh -c \
  "while true; do wget -q -O /dev/null http://backend.step25.svc.cluster.local:8080/echo?msg=x; done"
# HPA 를 15초 간격으로 관찰
watch kubectl get hpa backend -n step25
```
```
backend   Deployment/backend   cpu: 4%/60%     2   6   2   3m54s
backend   Deployment/backend   cpu: 44%/60%    2   6   2   4m9s
backend   Deployment/backend   cpu: 278%/60%   2   6   2   4m24s
backend   Deployment/backend   cpu: 312%/60%   2   6   4   4m39s   ← 스케일 업 시작
backend   Deployment/backend   cpu: 294%/60%   2   6   6   4m54s   ← maxReplicas 도달
backend   Deployment/backend   cpu: 109%/60%   2   6   6   5m9s
```

CPU 사용률이 목표(60%)를 넘자 HPA 가 2 → 4 → 6 으로 늘렸습니다. 부하를 제거(`kubectl delete pod loadgen -n step25`)하면 안정화 대기(기본 5분) 후 다시 줄어듭니다.

> ⚠️ **함정**: HPA 는 파드의 `resources.requests.cpu` 를 기준으로 사용률을 계산합니다. **requests 를 지정하지 않으면 CPU 기반 HPA 가 동작하지 않습니다**. 그래서 backend 에 `requests.cpu: 25m` 을 반드시 넣었습니다.

---

## 25-7. 외부 노출 — NodePort 와 Ingress

**NodePort** 로 브라우저에서 바로 접근합니다. `kind-cluster.yaml` 이 30080 을 호스트로 매핑해 두었습니다.

```bash
curl -s http://localhost:30080/ | grep -Eo 'Hello Kubernetes!|Shop v[0-9][^<"]*'
```
```
Hello Kubernetes!
Shop v1  |  3-tier on Kubernetes
```

**Ingress**(ingress-nginx 컨트롤러가 있을 때)로 호스트 기반 라우팅도 가능합니다.

```bash
kubectl apply -f manifests/41-ingress.yaml
kubectl get ingress -n step25
```
```
NAME   CLASS   HOSTS        ADDRESS     PORTS   AGE
shop   nginx   shop.local   localhost   80      68s
```

Ingress 규칙이 실제로 `shop.local` → `frontend` 로 라우팅되는지 컨트롤러에 직접 요청해 확인합니다.

```bash
kubectl exec -n step25 $BE -- wget -qO- --header="Host: shop.local" \
  http://ingress-nginx-controller.ingress-nginx.svc.cluster.local:80/ | grep -Eo 'Shop v[0-9][^<"]*'
```
```
Shop v2  |  zero-downtime rollout
```

> 💡 **팁**: 브라우저에서 Ingress 로 접근하려면 `/etc/hosts` 에 `127.0.0.1 shop.local` 을 추가하고 kind 의 Ingress 호스트 포트(이 코스 매핑상 8080/8443)로 접속합니다. 컨트롤러가 없거나 호스트 포트가 안 열려 있으면 **NodePort 30080 이 가장 확실한 외부 경로**입니다. [Step 14 인그레스](../step-14-ingress/index.md) 참고.

---

## 25-8. 무중단 배포 — 롤링 업데이트

배너 문구를 v2 로 바꿔 새로 배포하되, **그동안 요청이 한 건도 실패하지 않는지** 확인합니다.

```bash
# 1) ConfigMap 값 변경 (ConfigMap 변경만으로는 파드가 자동 갱신되지 않는다)
kubectl patch configmap app-config -n step25 --type merge \
  -p '{"data":{"MESSAGE":"Shop v2  |  zero-downtime rollout"}}'

# 2) 롤아웃을 유발 (env 하나를 바꿔 새 ReplicaSet 을 만든다)
kubectl set env deploy/frontend -n step25 ROLL_TS="$(date +%s)"

# 3) 롤아웃 동안 계속 요청 (0.5초 간격 40회)
for i in $(seq 1 40); do curl -s -o /dev/null -w "%{http_code} " http://localhost:30080/; sleep 0.5; done

kubectl rollout status deploy/frontend -n step25
```
```
200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200
200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200
Waiting for deployment "frontend" rollout to finish: 1 old replicas are pending termination...
deployment "frontend" successfully rolled out
```

40번 모두 `200` — 다운타임 0. `maxUnavailable: 0, maxSurge: 1` 덕분에 **새 파드가 Ready 된 뒤에만** 옛 파드를 내렸기 때문입니다.

```bash
curl -s http://localhost:30080/ | grep -Eo 'Shop v[0-9][^<"]*'
```
```
Shop v2  |  zero-downtime rollout
```

문제가 생기면 즉시 되돌립니다.

```bash
kubectl rollout undo deploy/frontend -n step25
kubectl rollout history deploy/frontend -n step25
```

> ⚠️ **함정**: **ConfigMap 을 수정해도 이미 떠 있는 파드는 자동으로 갱신되지 않습니다**(env 로 주입한 값은 파드 생성 시점에 고정). 반영하려면 롤아웃을 유발해야 합니다. Helm/Kustomize 는 ConfigMap 이름에 해시를 붙여(‑configHash) 값이 바뀌면 파드가 자동으로 교체되게 합니다([Step 23](../step-23-kustomize-gitops/index.md)).

---

## 25-9. 정리(cleanup)

이 스텝은 네임스페이스 하나에 다 담았으므로, 하나만 지우면 깨끗합니다. HPA·Ingress·PVC 도 함께 사라집니다.

```bash
kubectl delete namespace step25
kubectl get ns | grep step25 || echo "step25 없음 — 정리 완료"
```

> ⚠️ **함정**: `kubectl delete ns step25` 는 그 안의 PVC 까지 지웁니다. StatefulSet 의 PVC 는 StatefulSet 을 지워도 **남지만**(데이터 보호), 네임스페이스를 통째로 지우면 함께 사라집니다. 프로덕션에서는 네임스페이스 삭제 전에 데이터 백업을 반드시 확인하세요.

---

## 정리

| 요구사항 | 이 프로젝트에서 | 관련 스텝 |
|---|---|---|
| 3계층 분리 | frontend / backend / datastore | 03, 05, 06 |
| 설정·비밀 주입 | ConfigMap `app-config`, Secret `db-secret` | 07 |
| 계층 통신 | ClusterIP + 클러스터 DNS | 06 |
| 데이터 영속 | StatefulSet + volumeClaimTemplates | 10, 11 |
| 견고함 | startup/readiness/liveness + requests/limits | 08, 09 |
| 자동 확장 | HPA (cpu 60%, 2~6) | 18 |
| 외부 노출 | NodePort 30080 + Ingress | 06, 14 |
| 무중단 배포 | RollingUpdate maxUnavailable=0 | 05 |

## 실습 문제 → [problems.md](problems.md) · 정답과 해설 → [solutions.md](solutions.md)

---

## 이후 학습 로드맵

축하합니다. 25스텝을 마쳤습니다. 이제 실무·심화로 나아갈 차례입니다.

**1) 매니지드 Kubernetes 로 이동**
- **EKS**(AWS) / **GKE**(Google) / **AKS**(Azure): control plane 을 클라우드가 운영. kind 에서 배운 오브젝트는 그대로 통합니다. 차이는 노드 그룹, LoadBalancer 타입 Service(진짜 외부 IP), IAM 연동, CSI 드라이버(EBS/PD) 정도입니다.
- `eksctl`, GKE Autopilot 로 클러스터를 몇 분 만에 띄워 이 프로젝트를 그대로 배포해 보세요.

**2) GitOps 로 배포 자동화**
- [Step 23](../step-23-kustomize-gitops/index.md) 에서 본 **ArgoCD** 또는 **Flux** 로 Git push = 배포를 구현. 클러스터가 Git 을 진실의 원천으로 삼아 스스로 동기화합니다.

**3) 관측성(Observability)**
- **Prometheus + Grafana**: 메트릭 수집·대시보드·알림. [Step 19](../step-19-observability/index.md) 의 `kubectl top` 을 넘어 히스토리와 SLO 로.
- **Loki / ELK**: 로그 집계. **Tempo / Jaeger**: 분산 트레이싱.

**4) 서비스 메시**
- **Istio** / **Linkerd**: 사이드카(또는 ambient)로 mTLS, 트래픽 분할(카나리), 재시도·서킷브레이커, 세밀한 관측을 앱 코드 수정 없이 추가.

**5) 프로덕션 체크리스트**
- 리소스 requests/limits 와 LimitRange/ResourceQuota([Step 09](../step-09-resources/index.md))
- PodDisruptionBudget + 다중 replica + topologySpread([Step 13](../step-13-scheduling/index.md), [Step 21](../step-21-disruptions/index.md))
- NetworkPolicy 기본 차단([Step 15](../step-15-network-policy/index.md)) + RBAC 최소 권한([Step 16](../step-16-rbac/index.md))
- Pod Security Standards, non-root, 읽기 전용 루트 FS([Step 20](../step-20-security/index.md))
- 백업/복구(Velero), 시크릿 관리(External Secrets, Vault), 이미지 스캔(Trivy)

**6) 자격증**
- **CKAD**(개발자): 앱 배포·설정·트러블슈팅 위주. 이 코스가 다룬 범위와 가장 겹칩니다.
- **CKA**(관리자): 클러스터 운영·네트워킹·etcd·업그레이드까지.
- **CKS**(보안): CKA 취득 후 보안 심화.
- 모두 **실기(hands-on)** 시험입니다. 이 코스처럼 `kubectl` 을 손에 익히는 게 최고의 준비입니다.

**여기까지 온 여러분께** — 이제 YAML 이 apply 됐는데 왜 안 되는지 스스로 진단할 수 있습니다. 그게 이 코스의 목표였습니다. 실제 클러스터에서 계속 부수고 고치며 배우세요.

← [Step 24 — 트러블슈팅](../step-24-troubleshooting/index.md) · [코스 처음으로](../index.md)

---

## 실습 파일

이 스텝의 매니페스트는 파일명 앞의 **숫자가 곧 적용 순서**입니다. `00`(네임스페이스) → `01`(설정·비밀) → `10`(데이터스토어) → `20`(백엔드) → `30`(프론트엔드) 순으로 아래 계층부터 쌓아 올리고, `40`(HPA)·`41`(Ingress)은 metrics-server / ingress-nginx 가 있을 때만 얹는 **선택 리소스**입니다. `commands.sh` 는 25-1부터 25-9(정리)까지의 모든 명령을 순서대로 모아 둔 러너 스크립트이니, 매니페스트를 먼저 읽고 나서 한 줄씩 실행하며 결과를 관찰하세요.

### manifests/00-namespace.yaml

이 스텝의 모든 오브젝트를 담을 격리 공간 `step25` 를 만듭니다. 가장 먼저 적용해야 하며, 이후 모든 매니페스트가 `metadata.namespace: step25` 를 명시하고 있으므로 이 네임스페이스가 없으면 `apply` 가 실패합니다.

- `labels.course: k8s-learn`, `labels.step: "25"` 는 이 코스에서 만든 리소스를 나중에 `kubectl get ns -l course=k8s-learn` 처럼 골라내기 위한 표식입니다. `step: "25"` 의 값을 **따옴표로 감싼 이유**는 레이블 값이 반드시 문자열이어야 하기 때문입니다. 따옴표를 빼면 YAML 파서가 숫자 `25` 로 읽어 적용이 거부됩니다.
- 25-9의 `kubectl delete namespace step25` 한 줄로 이 안의 Deployment·Service·HPA·Ingress·**PVC 까지 전부** 사라집니다. 정리는 편하지만 데이터도 함께 날아간다는 뜻이니, 25-4에서 만든 `orders` 테이블을 보존하고 싶다면 삭제 전에 백업하세요.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/01-config.yaml

25-3 "설정과 비밀 주입" 의 원본입니다. 하나의 파일에 `---` 로 구분된 두 오브젝트, **ConfigMap `app-config`** 와 **Secret `db-secret`** 이 들어 있습니다. 이 파일의 학습 포인트는 **민감/비민감의 분리**입니다.

- ConfigMap 의 `POSTGRES_DB: "shopdb"` 와 `POSTGRES_USER: "appuser"` 는 `10-datastore.yaml` 이 `configMapKeyRef` 로 가져다 씁니다. 그래서 25-4의 psql 명령이 `-U appuser -d shopdb` 인 것입니다 — 값을 바꾸면 그 명령도 함께 바뀌어야 합니다.
- `MESSAGE: "Shop v1  |  3-tier on Kubernetes"` 는 프론트엔드 화면의 배너 문구이고, 25-8에서 이 키 하나만 `kubectl patch` 로 `Shop v2 ...` 로 바꿔 무중단 롤링 업데이트를 유발합니다.
- `BACKEND_URL` 이 `http://backend.step25.svc.cluster.local:8080` 인 것은 **`<서비스명>.<네임스페이스>.svc.cluster.local`** 이라는 클러스터 DNS 규칙 그대로입니다. 계층 간 통신은 파드 IP 가 아니라 이 이름으로 합니다.
- Secret 은 `type: Opaque` 에 `stringData` 를 씁니다. `data` 였다면 직접 base64 로 인코딩한 값을 넣어야 하지만, `stringData` 는 평문 `s3cr3t-p@ss` 를 그대로 쓰면 apiserver 가 저장 시 인코딩해 줍니다. **다만 이것은 암호화가 아닙니다** — 누구든 `kubectl get secret db-secret -o yaml` 로 디코딩할 수 있습니다.

```yaml file="./manifests/01-config.yaml"
```

### manifests/10-datastore.yaml

25-4 "데이터 영속성" 의 데이터스토어 계층입니다. **헤드리스 Service + StatefulSet** 한 쌍으로 구성되며, 여기서 만들어진 PVC `data-postgres-0` 이 파드를 지웠다 살려도 데이터를 지켜 줍니다.

- Service 의 `clusterIP: None` 이 헤드리스의 정의입니다. 가상 IP 로 로드밸런싱하지 않고 파드별 DNS(`postgres-0.postgres`)를 직접 제공합니다. StatefulSet 의 `serviceName: postgres` 가 이 Service 이름과 **정확히 같아야** 안정적인 파드 DNS 가 생성됩니다.
- `volumeClaimTemplates` 의 `metadata.name: data` 와 컨테이너의 `volumeMounts.name: data` 가 짝을 이룹니다. 이름 규칙상 PVC 는 `<템플릿명>-<파드명>` = `data-postgres-0` 으로 자동 생성되고, `storage: 1Gi` / `accessModes: ["ReadWriteOnce"]` 로 요청됩니다.
- `PGDATA: /var/lib/postgresql/data/pgdata` 가 **가장 중요한 한 줄**입니다. PV 를 `/var/lib/postgresql/data` 에 그대로 마운트하면 볼륨 루트에 `lost+found` 가 생겨 initdb 가 "directory not empty" 로 실패합니다. 하위 디렉터리를 데이터 경로로 지정해 이를 피합니다. 이 줄을 지우면 postgres 가 기동에 실패합니다.
- 프로브가 HTTP 가 아니라 **`exec`** 입니다. `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB` 를 `sh -c` 로 감싼 이유는 셸이 있어야 `$POSTGRES_USER` 같은 환경 변수가 치환되기 때문입니다. readiness 는 `periodSeconds: 5`, liveness 는 `initialDelaySeconds: 20` 으로 더 느긋하게 잡아 초기 initdb 중에 컨테이너가 죽지 않도록 했습니다.

```yaml file="./manifests/10-datastore.yaml"
```

### manifests/20-backend.yaml

25-2(계층 통신)와 25-6(HPA)의 주인공인 API 계층입니다. 실제 API 대신 `agnhost netexec` 를 써서 `/healthz`, `/hostname`, `/echo?msg=...` 엔드포인트를 흉내냅니다.

- `args: ["netexec", "--http-port=8080"]` 가 컨테이너를 HTTP 서버로 띄웁니다. 25-2에서 `wget -qO- .../hostname` 이 파드 이름을 돌려주는 것도, `/echo?msg=order-42` 가 `order-42` 를 그대로 반환하는 것도 이 netexec 의 기능입니다.
- `DB_HOST: postgres.step25.svc.cluster.local` 은 평문 값으로, `DB_PASSWORD` 는 `secretKeyRef` 로 `db-secret` 의 `POSTGRES_PASSWORD` 를 주입합니다. 25-3의 `printenv DB_HOST DB_PASSWORD` 가 이 두 줄의 결과를 확인하는 명령입니다.
- **`requests.cpu: 25m` 은 HPA 의 생명줄입니다.** HPA 는 "현재 사용량 ÷ requests" 로 사용률을 계산하므로, requests 가 없으면 `40-hpa.yaml` 의 CPU 기반 스케일링이 전혀 동작하지 않습니다. 25-6에서 `cpu: 312%/60%` 같은 값이 찍히는 것도 25m 이라는 작은 기준값 덕분입니다.
- `strategy.rollingUpdate` 가 `maxUnavailable: 0`, `maxSurge: 1` 입니다. 새 파드가 Ready 가 된 뒤에야 옛 파드를 내리므로 롤아웃 중에도 가용 파드 수가 목표치 아래로 떨어지지 않습니다. Service 는 `type: ClusterIP` 라 **클러스터 내부에서만** 접근 가능합니다 — 백엔드는 외부에 노출하지 않는 것이 정석입니다.

```yaml file="./manifests/20-backend.yaml"
```

### manifests/30-frontend.yaml

25-5(프로브·리소스), 25-7(외부 노출), 25-8(무중단 배포)이 모두 걸려 있는 파일이자, **이 스텝에서 가장 많이 실패하는 지점**입니다. Deployment + ClusterIP Service + NodePort Service 세 오브젝트가 들어 있습니다.

- 25-1의 함정 박스가 말하는 두 번의 실패가 이 파일에 **해법 형태로 박제**되어 있습니다. hello-kubernetes(Node.js)는 기동에 약 13초가 걸리는데, 순진하게 `livenessProbe.initialDelaySeconds: 10` 만 두면 liveness 가 먼저 실패해 컨테이너를 죽여 CrashLoopBackOff 에 빠집니다. 그래서 `startupProbe` 를 두어 `periodSeconds: 3` × `failureThreshold: 20` = **최대 60초까지 기동을 기다리고**, 그동안 liveness/readiness 는 유예됩니다.
- 두 번째 실패는 OOM 이었습니다. `limits.memory: 256Mi` 라는 값에 주석이 달린 이유가 그것으로, 128Mi 로 낮추면 Node 런타임이 **OOMKilled(exit 137)** 로 죽습니다. 실습 중 일부러 128Mi 로 바꿔 `kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState}'` 로 증상을 재현해 보면 학습 효과가 큽니다.
- `requests`(cpu 25m / memory 128Mi)와 `limits`(cpu 200m / memory 256Mi)가 다르므로 QoS 는 **Burstable** 입니다. 25-5의 `describe` 출력에 나오는 `QoS Class: Burstable` 이 여기서 결정됩니다.
- 마지막 오브젝트 `frontend-nodeport` 는 `type: NodePort` 에 `nodePort: 30080` 을 고정했습니다. kind 클러스터 설정이 30080 을 호스트로 매핑해 두었기 때문에 `curl http://localhost:30080/` 이 그대로 통합니다. 25-8의 40회 연속 `200` 응답 측정도 이 포트로 합니다.
- 참고로 파일 하단 주석에 "`40-ingress.yaml` 참고"라고 적혀 있지만, 실제 Ingress 매니페스트의 파일명은 **`41-ingress.yaml`** 입니다(`40-hpa.yaml` 이 HPA). 주석이 옛 파일명을 가리키는 것이니 `kubectl apply` 할 때는 `41-ingress.yaml` 을 쓰세요.

```yaml file="./manifests/30-frontend.yaml"
```

### manifests/40-hpa.yaml

25-6에서 적용하는 **선택 리소스**입니다. 백엔드 Deployment 를 부하에 따라 자동으로 늘리고 줄입니다.

- `scaleTargetRef` 가 `Deployment/backend` 를 가리키므로 `20-backend.yaml` 이 먼저 적용돼 있어야 합니다.
- `minReplicas: 2` / `maxReplicas: 6` 이 25-6 실측의 `2 → 4 → 6` 스케일업 범위이고, `averageUtilization: 60` 이 그 로그의 `cpu: .../60%` 우변입니다. 즉 파드 평균 CPU 사용률이 requests(25m) 대비 60% 를 넘으면 늘어납니다.
- **metrics-server 가 없으면** `TARGETS` 가 `<unknown>` 으로 남고 스케일링이 일어나지 않습니다. 그때는 파일 상단 주석대로 `kubectl scale deploy/backend -n step25 --replicas=4` 로 수동 스케일해 대체하세요.
- 부하를 제거해도 즉시 줄지 않습니다. HPA 의 스케일다운 안정화 창(기본 5분)이 지나야 replicas 가 내려갑니다.

```yaml file="./manifests/40-hpa.yaml"
```

### manifests/41-ingress.yaml

25-7의 두 번째 외부 노출 경로입니다. NodePort 가 "포트 번호로" 뚫는 방식이라면, Ingress 는 **호스트 이름(`shop.local`)으로** L7 라우팅합니다. 이 역시 선택 리소스입니다.

- `ingressClassName: nginx` 는 "ingress-nginx 컨트롤러가 이 규칙을 처리하라"는 지정입니다. 컨트롤러가 설치돼 있지 않으면 리소스는 만들어지되 `ADDRESS` 가 비어 있고 아무 트래픽도 라우팅되지 않습니다 — 오브젝트가 존재한다고 동작하는 것이 아니라는 점이 핵심입니다.
- `host: shop.local` + `path: /` + `pathType: Prefix` 규칙이 `frontend` Service 의 `8080` 포트로 보냅니다. 백엔드 참조가 `frontend-nodeport` 가 아니라 **ClusterIP Service `frontend`** 라는 점에 주목하세요. Ingress 컨트롤러는 클러스터 내부에서 Service 로 붙으므로 NodePort 가 필요 없습니다.
- 그래서 25-7의 검증 명령이 `wget --header="Host: shop.local"` 로 **Host 헤더를 위조**해 컨트롤러에 직접 요청합니다. `shop.local` 은 실제 DNS 이름이 아니므로, 브라우저로 접근하려면 `/etc/hosts` 에 `127.0.0.1 shop.local` 을 추가해야 합니다.

```yaml file="./manifests/41-ingress.yaml"
```

### commands.sh

25-1부터 25-9까지 강의 본문의 모든 명령을 **실행 순서 그대로** 모아 둔 러너 스크립트입니다. 통째로 실행하기보다, 섹션 주석(`# --- 25-2. 계층 통신 ---`)을 따라 한 블록씩 붙여 넣으며 출력을 관찰하는 용도로 쓰세요.

- 맨 앞의 `kubectl config current-context` 로 **컨텍스트가 `kind-learn` 인지 반드시 먼저 확인**하세요. 다른 클러스터를 가리키고 있으면 그곳에 `step25` 네임스페이스를 만들어 버립니다.
- `FE=$(kubectl get pod ... -l tier=frontend -o jsonpath='{.items[0].metadata.name}')` 처럼 파드 이름을 셸 변수에 담아 재사용합니다. 파드가 재생성되면 이름이 바뀌므로, 롤아웃이나 파드 삭제 뒤에는 **이 변수를 다시 채워야** `kubectl exec` 가 "not found" 로 실패하지 않습니다.
- 부하 생성(`loadgen`) 블록은 무한 루프이므로 **주석 처리된 채로** 두었습니다. 실행했다면 반드시 `kubectl delete pod loadgen -n step25` 로 정리하세요. 그대로 두면 백엔드 CPU 를 계속 태워 HPA 가 6개에 고정됩니다.
- `kubectl delete pod postgres-0` 은 의도적인 파괴 명령입니다. StatefulSet 이 같은 이름으로 파드를 다시 만들고 **같은 PVC 를 재마운트**하므로, 바로 뒤의 `SELECT count(*) FROM orders;` 가 여전히 `2` 를 반환하는지가 이 실습의 채점 기준입니다.
- 마지막 `kubectl delete namespace step25` 는 **되돌릴 수 없는 파괴적 명령**입니다. PVC 와 그 안의 데이터까지 함께 사라지므로, 실습을 더 이어갈 생각이라면 이 줄은 건너뛰세요.

```bash file="./commands.sh"
```
