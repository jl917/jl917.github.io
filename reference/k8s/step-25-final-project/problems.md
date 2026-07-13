# Step 25 — 실습 문제 (problems)

지금까지 배운 것만으로 3계층 앱 `shop` 을 **직접** 배포하세요. 먼저 아무것도 보지 말고 스스로 매니페스트를 작성해 보고, 막히면 [강의 본문](index.md) 과 [solutions.md](solutions.md) 를 참고합니다.

## 요구사항 명세

- **네임스페이스**: 모든 리소스는 `step25` 에.
- **이미지**는 아래 3개만 사용(노드에 캐시됨):
  - frontend: `paulbouwer/hello-kubernetes:1.10.1` (8080 포트, `MESSAGE` env 로 배너 커스터마이즈)
  - backend: `registry.k8s.io/e2e-test-images/agnhost:2.47` (`args: ["netexec","--http-port=8080"]`, `/healthz` `/hostname` `/echo?msg=` 제공)
  - datastore: `postgres:16-alpine` (5432, `POSTGRES_DB/USER/PASSWORD` env, `pg_isready` 로 헬스체크)
- **레이블 규칙**: 모든 파드에 `app: shop` 과 `tier: {frontend|backend|datastore}`.

## 채점 기준 (총 100점)

| # | 항목 | 배점 | 통과 조건 |
|---|---|---|---|
| M1 | 네임스페이스 | 5 | `step25` 존재 |
| M2 | ConfigMap/Secret | 10 | `app-config`(MESSAGE, POSTGRES_DB/USER, BACKEND_URL) + `db-secret`(POSTGRES_PASSWORD) |
| M3 | datastore StatefulSet+PVC | 15 | `postgres-0` Running, PVC `data-postgres-0` Bound, 헤드리스 Service |
| M4 | backend Deployment+Service | 10 | replicas=2, ClusterIP 8080, `/healthz` 프로브 |
| M5 | frontend Deployment+Service | 10 | replicas=2, ClusterIP 8080, MESSAGE 를 ConfigMap 에서 주입 |
| M6 | 계층 통신 | 10 | 프론트 파드에서 `backend...:8080/hostname` 응답 |
| M7 | 프로브·리소스 | 10 | 세 계층 모두 requests/limits + 적절한 프로브(느린 기동은 startupProbe) |
| M8 | 데이터 영속 | 10 | 테이블 생성 후 `postgres-0` 삭제·재생성해도 데이터 유지 |
| M9 | 외부 노출 | 5 | `curl localhost:30080` 이 프론트 페이지 응답 |
| M10 | HPA (또는 수동 스케일) | 5 | HPA `backend`(2~6, cpu 60%) 또는 `kubectl scale` 로 4로 확장 |
| M11 | 무중단 롤링 업데이트 | 5 | MESSAGE v2 배포 중 요청이 전부 200 |
| M12 | 정리 | 5 | `kubectl delete ns step25` 로 전부 제거 |

## 단계별 미션

### M1. 네임스페이스
`step25` 네임스페이스를 선언형(YAML)으로 만드세요.

### M2. 설정과 비밀
- ConfigMap `app-config`: `MESSAGE`, `POSTGRES_DB=shopdb`, `POSTGRES_USER=appuser`, `BACKEND_URL`(백엔드 Service DNS).
- Secret `db-secret`: `POSTGRES_PASSWORD`.

### M3. 데이터스토어 (StatefulSet + PVC)
postgres StatefulSet(replicas=1)과 **헤드리스 Service**(`clusterIP: None`)를 만드세요.
- `POSTGRES_DB/USER` 는 ConfigMap 에서, `POSTGRES_PASSWORD` 는 Secret 에서 주입.
- `volumeClaimTemplates` 로 1Gi PVC 를 `/var/lib/postgresql/data` 에 마운트.
- 힌트: PV 루트의 `lost+found` 때문에 initdb 가 실패하지 않도록 `PGDATA` 를 하위 디렉터리로.
- `pg_isready` 를 readiness/liveness 프로브로.

### M4. 백엔드 (Deployment + Service)
agnhost netexec Deployment(replicas=2)와 ClusterIP Service(8080)를 만드세요.
- `/healthz` 를 readiness/liveness httpGet 프로브로.
- Secret 의 DB 비밀번호를 env 로 주입(백엔드가 DB 를 참조한다고 가정).
- `resources.requests.cpu` 를 반드시 지정(뒤의 HPA 를 위해).

### M5. 프론트엔드 (Deployment + Service + NodePort)
hello-kubernetes Deployment(replicas=2)와 ClusterIP Service, 그리고 NodePort(30080) Service 를 만드세요.
- `MESSAGE` 를 ConfigMap 에서 주입.
- **주의**: 이 이미지는 기동에 ~13초 걸리고 메모리를 128Mi 넘게 씁니다. 이걸 고려한 프로브·리소스를 설계하세요.

### M6. 계층 통신 확인
프론트 파드에서 `backend.step25.svc.cluster.local:8080/hostname` 을 호출해 응답을 확인하세요.

### M7. 견고함 점검
세 계층 모두 requests/limits 가 있고, 프로브가 앱 성격에 맞는지(HTTP vs exec) 점검하세요. frontend 가 CrashLoop 에 빠진다면 왜인지 진단하세요([Step 24](../step-24-troubleshooting/index.md) 활용).

### M8. 영속성 검증
```
psql 로 테이블 생성 → 행 삽입 → postgres-0 삭제 → 재생성 후 행 수 확인
```
데이터가 유지되면 통과.

### M9. 외부 접근
`curl http://localhost:30080/` 로 프론트 페이지와 배너 문구를 확인하세요.

### M10. 자동 확장
HPA `backend`(minReplicas=2, maxReplicas=6, cpu 60%)를 적용하세요. metrics-server 가 없으면 `kubectl scale deploy/backend --replicas=4` 로 대체하고 그 사실을 적으세요. (도전) busybox 로 부하를 걸어 스케일업을 관찰.

### M11. 무중단 배포
`MESSAGE` 를 v2 로 바꾸고 롤링 업데이트하세요. 롤아웃 동안 `curl` 을 반복해 **전부 200** 인지 확인하세요. `maxUnavailable`/`maxSurge` 를 어떻게 설정해야 무중단인가요?

### M12. 정리
`kubectl delete namespace step25` 로 모두 제거하고, `kubectl get ns` 에 `step25` 가 없는지 확인하세요.

## 힌트

- 순서: 설정 → 데이터스토어 → 백엔드 → 프론트. 하지만 k8s 는 재시도하므로 순서가 틀려도 결국 수렴합니다.
- `kubectl apply -f <dir>` 로 디렉터리 전체를 한 번에 적용할 수 있습니다.
- 막히면 `kubectl describe`, `kubectl get events --sort-by=.lastTimestamp`, `kubectl logs --previous` 3종 세트.
- frontend 가 안 뜨면 `kubectl get pod ... -o jsonpath='{.status.containerStatuses[0].lastState}'` 로 종료 원인(OOMKilled? exit 137?)을 보세요.

정답과 상세 해설: [solutions.md](solutions.md)
