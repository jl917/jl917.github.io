# Step 08 연습 과제 — 헬스 체크(프로브)

> 모든 실습은 `step08` 네임스페이스에서 한다. 막히면 각 과제의 **정답 펼치기**를 참고하라.

---

## 과제 1. "재시작까지 몇 초?" 계산하기

다음 livenessProbe 설정에서, 앱이 시작 직후부터 계속 실패한다고 할 때 **컨테이너가 처음 재시작되기까지 걸리는 시간**을 계산하라.

```yaml
livenessProbe:
  httpGet: { path: /healthz, port: 8080 }
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 4
```

<details>
<summary>정답 펼치기</summary>

`initialDelaySeconds + periodSeconds × failureThreshold = 10 + 5 × 4 = 30초`.

- 시작 후 10초 대기 → 첫 프로브(실패 1) → 5초 뒤 실패 2 → 실패 3 → 실패 4에서 임계치 도달 → kill.
- 즉 `10s`(첫 프로브) + `5s × 3`(2·3·4번째 실패 간격) = 25초 지점에서 4번째 실패가 나므로 실무적으로 25~30초 사이. 넉넉히 잡아 **약 30초**로 기억하면 된다.
</details>

---

## 과제 2. tcpSocket readinessProbe 직접 붙이기

`redis:7-alpine` 이미지로 Pod을 만들고, **6379 포트에 TCP 연결이 되면 Ready**가 되도록 `tcpSocket` readinessProbe를 붙여라. 그리고 `READY 1/1`이 되는지 확인하라.

<details>
<summary>정답 펼치기</summary>

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: redis-ready
  namespace: step08
spec:
  containers:
    - name: redis
      image: redis:7-alpine
      ports:
        - containerPort: 6379
      readinessProbe:
        tcpSocket:
          port: 6379
        initialDelaySeconds: 2
        periodSeconds: 5
```

```bash
kubectl apply -f redis-ready.yaml   # 위 내용 저장
kubectl get pod redis-ready -n step08 -w   # READY 0/1 -> 1/1
```

Redis는 기동이 빠르므로 몇 초 내에 `1/1`이 된다.
</details>

---

## 과제 3. liveness vs readiness 실패의 차이 관찰하기

같은 exec 프로브(`cat /tmp/ok`, 파일 없음 → 실패)를 한 번은 **readinessProbe**로, 한 번은 **livenessProbe**로 걸어 각각 Pod을 띄워라. 두 Pod의 `RESTARTS`와 `READY` 컬럼이 어떻게 달라지는지 관찰하고 설명하라.

<details>
<summary>정답 펼치기</summary>

- **readiness 실패** Pod: `READY 0/1`이지만 `RESTARTS 0`. 재시작하지 않고 트래픽에서만 빠진다.
- **liveness 실패** Pod: `RESTARTS`가 계속 오르고 결국 `CrashLoopBackOff`. 컨테이너를 계속 죽였다 살린다.

즉 **readiness는 "트래픽만 차단", liveness는 "재시작"**이라는 핵심 차이를 눈으로 확인할 수 있다. 확인용:

```bash
kubectl get pods -n step08 -o wide
# readiness 쪽: 0/1 Running, RESTARTS 0
# liveness 쪽 : 0/1 CrashLoopBackOff, RESTARTS N
```
</details>

---

## 과제 4. CrashLoopBackOff 진단 및 수정

아래 Pod은 CrashLoopBackOff에 빠진다. **원인을 진단**하고, YAML 한 줄만 고쳐 정상(`1/1 Running`)으로 만들어라.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: fixme
  namespace: step08
spec:
  containers:
    - name: web
      image: nginx:1.27-alpine
      livenessProbe:
        httpGet:
          path: /nope
          port: 80
        periodSeconds: 3
        failureThreshold: 2
```

<details>
<summary>정답 펼치기</summary>

**진단**: `kubectl describe pod fixme -n step08`의 Events에 `Liveness probe failed: HTTP probe failed with statuscode: 404`가 보인다. nginx 기본 페이지에 `/nope` 경로가 없어 404 → liveness 실패 → 재시작 반복.

**수정**: 경로를 존재하는 값으로 바꾼다. `path: /nope` → `path: /`.

```yaml
      livenessProbe:
        httpGet:
          path: /        # /nope -> / 로 수정
          port: 80
        periodSeconds: 3
        failureThreshold: 2
```

httpGet은 **2xx/3xx만 성공**이고 404 같은 4xx는 실패로 친다는 점이 포인트다.
</details>

---

## 과제 5. startupProbe 예산 설계

기동에 최대 **50초**가 걸릴 수 있는 레거시 앱이 있다. 평상시 장애는 빨리(수 초 내) 감지하고 싶다. `startupProbe`와 `livenessProbe`를 어떻게 설계하면 좋을지 필드 값과 함께 제시하라.

<details>
<summary>정답 펼치기</summary>

- **startupProbe**로 기동 예산을 50초 이상 확보하고(`periodSeconds × failureThreshold ≥ 50`), 그동안 liveness는 유예시킨다.
- **livenessProbe**는 기동이 끝난 뒤 빠른 장애 감지를 위해 짧게 잡는다.

```yaml
startupProbe:
  httpGet: { path: /healthz, port: 8080 }
  periodSeconds: 5
  failureThreshold: 12     # 5 × 12 = 60초 기동 예산 (50초 앱을 여유있게 커버)
livenessProbe:
  httpGet: { path: /healthz, port: 8080 }
  periodSeconds: 5
  failureThreshold: 3      # 기동 후에는 약 15초 내 장애 감지
```

포인트: 느린 기동을 `livenessProbe`의 `initialDelaySeconds: 60`으로 때우면, 운영 중 장애 감지까지 60초씩 늦어진다. **기동 보호는 startupProbe, 운영 감시는 짧은 livenessProbe**로 역할을 분리하는 것이 정답이다.
</details>
