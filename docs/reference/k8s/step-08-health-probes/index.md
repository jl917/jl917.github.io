# Step 08 — 헬스 체크(프로브)

> **학습 목표**: livenessProbe / readinessProbe / startupProbe의 차이와 3가지 핸들러(httpGet · tcpSocket · exec)를 이해하고, CrashLoopBackOff를 직접 재현하며, readiness 실패가 Service Endpoints에 어떻게 반영되는지 눈으로 확인한다. / **선행 스텝**: [Step 07 — ConfigMap & Secret](../step-07-config-secret/) / **예상 소요**: 60분

이 스텝의 모든 리소스는 `step08` 네임스페이스에서 실행한다. 먼저 네임스페이스를 만든다.

```bash
kubectl create namespace step08
```

---

## 8-1. 프로브란 무엇인가 — 3가지 종류와 3가지 핸들러

쿠버네티스는 컨테이너 안의 애플리케이션이 "정말로 살아있고, 정말로 트래픽을 받을 준비가 됐는지"를 직접 알 수 없다. 프로세스가 떠 있어도 데드락에 빠지거나, 아직 초기화 중일 수 있다. 그래서 kubelet이 주기적으로 컨테이너를 찔러보는 것이 **프로브(probe)**다.

| 프로브 | 실패하면 무슨 일이? | 언제 쓰나 |
|--------|--------------------|-----------|
| **livenessProbe** | 컨테이너를 **재시작**한다 | 데드락·행(hang) 상태 자동 복구 |
| **readinessProbe** | Pod을 Service **Endpoints에서 제외**(트래픽 차단)한다. 재시작은 안 함 | 일시적으로 트래픽을 못 받는 상황(초기화, 의존성 대기) |
| **startupProbe** | 성공할 때까지 liveness/readiness를 **유예**하고, 최종 실패 시 재시작 | 기동이 느린 레거시 앱 보호 |

핸들러는 3가지다.

- **httpGet**: 지정한 경로/포트로 HTTP GET. 200~399면 성공.
- **tcpSocket**: 지정한 포트로 TCP 연결이 되면 성공.
- **exec**: 컨테이너 안에서 명령을 실행해 종료코드 0이면 성공.

아래 Pod은 세 프로브와 세 핸들러를 한 번에 정상 동작으로 보여준다.

```yaml
# manifests/01-healthy-all-probes.yaml
apiVersion: v1
kind: Pod
metadata:
  name: healthy-all-probes
  namespace: step08
  labels:
    app: healthy-all-probes
spec:
  containers:
    - name: web
      image: nginx:1.27-alpine
      ports:
        - containerPort: 80
      # startupProbe: 컨테이너가 처음 뜰 때까지만 검사. 성공하면 이후로는 실행 안 함
      startupProbe:
        exec:
          command: ["cat", "/etc/nginx/nginx.conf"]
        periodSeconds: 3
        failureThreshold: 10   # 3s * 10 = 최대 30초까지 기동 대기 허용
      # readinessProbe: 트래픽을 받을 준비가 됐는지 검사 (실패 시 Endpoints에서 제외)
      readinessProbe:
        tcpSocket:
          port: 80
        initialDelaySeconds: 2
        periodSeconds: 5
      # livenessProbe: 살아있는지 검사 (실패 시 컨테이너 재시작)
      livenessProbe:
        httpGet:
          path: /
          port: 80
        initialDelaySeconds: 5
        periodSeconds: 10
        timeoutSeconds: 1
        failureThreshold: 3
```

```bash
kubectl apply -f manifests/01-healthy-all-probes.yaml
kubectl get pod healthy-all-probes -n step08 -o wide
kubectl describe pod healthy-all-probes -n step08 | grep -A1 -E "Liveness|Readiness|Startup"
```

**실행 결과**

```
NAME                 READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE   READINESS GATES
healthy-all-probes   1/1     Running   0          12s   10.244.2.12   learn-worker2   <none>           <none>

    Liveness:       http-get http://:80/ delay=5s timeout=1s period=10s #success=1 #failure=3
    Readiness:      tcp-socket :80 delay=2s timeout=1s period=5s #success=1 #failure=3
    Startup:        exec [cat /etc/nginx/nginx.conf] delay=0s timeout=1s period=3s #success=1 #failure=10
```

`READY 1/1` — 세 프로브를 모두 통과했다는 뜻이다. `describe` 출력에서 각 프로브가 어떤 핸들러/주기로 설정됐는지 그대로 확인할 수 있다.

> 💡 **실무 팁**: 세 프로브가 같은 엔드포인트를 봐도 되지만, 실무에서는 보통 **liveness는 가볍게(단순히 프로세스가 응답하는지), readiness는 무겁게(DB·캐시 등 의존성까지 확인)** 나눈다. liveness가 무거우면 의존성 장애 때 애꿎은 컨테이너를 재시작시켜 장애를 키운다.

---

## 8-2. 프로브의 핵심 필드

`describe` 출력의 `delay / timeout / period / #success / #failure`는 각각 아래 필드다.

| 필드 | 기본값 | 의미 |
|------|--------|------|
| `initialDelaySeconds` | 0 | 컨테이너 시작 후 **첫 프로브까지 대기** 시간 |
| `periodSeconds` | 10 | 프로브 **반복 주기** |
| `timeoutSeconds` | 1 | 응답을 기다리는 **타임아웃**. 넘으면 실패 처리 |
| `successThreshold` | 1 | 연속 몇 번 성공해야 "성공"으로 볼지 (liveness/startup은 1로 고정) |
| `failureThreshold` | 3 | 연속 몇 번 실패해야 "실패"로 판정할지 |

**"얼마나 버티는가"** 계산이 중요하다. 예를 들어 `initialDelaySeconds: 5, periodSeconds: 10, failureThreshold: 3`이면, 실제로 재시작이 일어나기까지 최대 `5 + 10 × 3 = 35초`가 걸린다. 이 산수가 startupProbe 설계(8-6)의 핵심이다.

> ⚠️ **함정**: `timeoutSeconds` 기본값이 **단 1초**다. 응답이 조금만 느린 앱에 httpGet liveness를 걸면, 앱은 멀쩡한데 타임아웃으로 실패 판정 → 재시작 폭풍이 일어난다. 느린 엔드포인트에는 `timeoutSeconds`를 넉넉히 잡아라.

---

## 8-3. restartPolicy — 재시작 정책

프로브가 컨테이너를 죽였을 때 다시 살릴지는 Pod의 `spec.restartPolicy`가 결정한다.

| 값 | 동작 | 주 사용처 |
|----|------|-----------|
| `Always` (기본값) | 컨테이너가 어떤 이유로든 종료되면 **항상 재시작** | Deployment/일반 서버 (Deployment는 Always만 허용) |
| `OnFailure` | **비정상 종료(exit≠0)일 때만** 재시작 | Job / 배치 작업 |
| `Never` | 절대 재시작하지 않음 | 일회성 디버깅 Pod |

livenessProbe로 인한 kill은 "실패"로 취급되므로, `Always`와 `OnFailure`에서는 재시작되고 `Never`에서는 재시작되지 않는다. 재시작이 실패를 반복하면 지수 백오프(back-off)가 걸리며, 이 상태가 그 유명한 **CrashLoopBackOff**다.

> 💡 **실무 팁**: Deployment의 Pod 템플릿은 `restartPolicy: Always`만 허용한다. `OnFailure`/`Never`가 필요하면 Job(Step에서 다룸) 또는 순수 Pod을 써야 한다.

---

## 8-4. CrashLoopBackOff 재현하기 — 잘못된 livenessProbe

가장 흔한 프로덕션 사고를 직접 재현해 보자. nginx는 80번 포트에서 도는데, livenessProbe를 **엉뚱한 9999 포트**로 걸어 항상 실패하게 만든다.

```yaml
# manifests/02-crashloop-liveness.yaml
apiVersion: v1
kind: Pod
metadata:
  name: bad-liveness
  namespace: step08
  labels:
    app: bad-liveness
spec:
  # restartPolicy 기본값은 Always. 프로브 실패로 컨테이너가 죽으면 계속 재시작 -> CrashLoopBackOff
  restartPolicy: Always
  containers:
    - name: web
      image: nginx:1.27-alpine
      ports:
        - containerPort: 80
      # 일부러 잘못된 프로브: nginx는 80번 포트에서 도는데 9999로 검사 -> 항상 실패
      livenessProbe:
        httpGet:
          path: /
          port: 9999
        initialDelaySeconds: 3
        periodSeconds: 3
        failureThreshold: 2   # 2번 연속 실패하면 컨테이너 kill
```

```bash
kubectl apply -f manifests/02-crashloop-liveness.yaml
# 1분쯤 기다린 뒤
kubectl get pod bad-liveness -n step08 -o wide
```

**실행 결과** — RESTARTS가 오르고 STATUS가 CrashLoopBackOff로 바뀐다.

```
NAME           READY   STATUS             RESTARTS      AGE   IP            NODE           NOMINATED NODE   READINESS GATES
bad-liveness   0/1     CrashLoopBackOff   4 (22s ago)   71s   10.244.1.10   learn-worker   <none>           <none>
```

조금 더 두면 재시작이 계속 쌓인다.

```
NAME           READY   STATUS             RESTARTS        AGE     IP            NODE           NOMINATED NODE   READINESS GATES
bad-liveness   0/1     CrashLoopBackOff   6 (2m28s ago)   5m47s   10.244.1.10   learn-worker   <none>           <none>
```

원인은 `describe`의 **Events**에 정확히 찍힌다.

```bash
kubectl describe pod bad-liveness -n step08 | sed -n '/Events:/,$p'
```

**실행 결과**

```
Events:
  Type     Reason     Age                 From               Message
  ----     ------     ----                ----               -------
  Normal   Scheduled  77s                 default-scheduler  Successfully assigned step08/bad-liveness to learn-worker
  Normal   Pulled     34s (x5 over 76s)   kubelet            Container image "nginx:1.27-alpine" already present on machine and can be accessed by the pod
  Normal   Created    34s (x5 over 76s)   kubelet            Container created
  Normal   Started    34s (x5 over 76s)   kubelet            Container started
  Warning  Unhealthy  28s (x10 over 73s)  kubelet            Liveness probe failed: Get "http://10.244.1.10:9999/": dial tcp 10.244.1.10:9999: connect: connection refused
  Normal   Killing    28s (x5 over 70s)   kubelet            Container web failed liveness probe, will be restarted
  Warning  BackOff    28s (x4 over 55s)   kubelet            Back-off restarting failed container web in pod bad-liveness_step08(...)
```

핵심 3줄:
- `Liveness probe failed: ... connect: connection refused` → 9999 포트에 아무도 없어서 프로브 실패
- `Container web failed liveness probe, will be restarted` → kubelet이 컨테이너를 죽임
- `Back-off restarting failed container` → 재시작이 반복되어 백오프 진입 = **CrashLoopBackOff**

> ⚠️ **함정**: CrashLoopBackOff를 보면 "앱이 크래시났다"고만 생각하기 쉽지만, 실제로는 **앱은 멀쩡한데 프로브 설정이 틀린 경우**가 절반 이상이다. 반드시 `describe`의 Events를 먼저 보고, `Liveness probe failed` 메시지의 포트/경로가 앱 실제 포트와 맞는지 확인하라. (여기선 앱 로그를 봐도 아무 에러가 없다 — 함정!)

---

## 8-5. readiness 실패가 Service Endpoints를 바꾸는 것 증명하기

readinessProbe의 존재 이유는 "준비 안 된 Pod에는 트래픽을 보내지 않는다"이다. 이를 눈으로 증명한다. `/tmp/ready` 파일이 있어야만 Ready가 되는 exec 프로브를 걸고, Deployment(2 replica) + Service를 만든다. 파일은 처음엔 **없다.**

```yaml
# manifests/03-readiness-endpoints.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: readiness-demo
  namespace: step08
  labels:
    app: readiness-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: readiness-demo
  template:
    metadata:
      labels:
        app: readiness-demo
    spec:
      containers:
        - name: web
          image: nginx:1.27-alpine
          ports:
            - containerPort: 80
          # /tmp/ready 파일이 있어야만 Ready. 처음엔 파일이 없으므로 NotReady -> Endpoints에서 제외
          readinessProbe:
            exec:
              command: ["cat", "/tmp/ready"]
            initialDelaySeconds: 2
            periodSeconds: 3
            failureThreshold: 1
            successThreshold: 1
---
apiVersion: v1
kind: Service
metadata:
  name: readiness-demo
  namespace: step08
spec:
  selector:
    app: readiness-demo
  ports:
    - port: 80
      targetPort: 80
```

```bash
kubectl apply -f manifests/03-readiness-endpoints.yaml
kubectl get pods -n step08 -l app=readiness-demo -o wide
kubectl get endpoints readiness-demo -n step08
```

**실행 결과** — 두 Pod 모두 `READY 0/1`, Service의 Endpoints는 **비어 있다.**

```
NAME                              READY   STATUS    RESTARTS   AGE   IP            NODE            NOMINATED NODE
readiness-demo-65d6cdbbd7-qk25v   0/1     Running   0          10s   10.244.1.26   learn-worker
readiness-demo-65d6cdbbd7-tfpsk   0/1     Running   0          10s   10.244.2.31   learn-worker2

NAME             ENDPOINTS   AGE
readiness-demo               10s
```

EndpointSlice로 보면, 두 IP가 등록은 되어 있지만 `ready=false`라 트래픽 대상이 아니다.

```bash
kubectl get endpointslice -n step08 -l kubernetes.io/service-name=readiness-demo \
  -o jsonpath='{range .items[0].endpoints[*]}{.addresses[0]}{"  ready="}{.conditions.ready}{"\n"}{end}'
```

```
10.244.1.26  ready=false
10.244.2.31  ready=false
```

이제 **한쪽 Pod에만** `/tmp/ready` 파일을 만들어 Ready로 바꾼다.

```bash
kubectl exec -n step08 readiness-demo-65d6cdbbd7-qk25v -- touch /tmp/ready
sleep 6
kubectl get pods -n step08 -l app=readiness-demo -o wide
kubectl get endpoints readiness-demo -n step08
```

**실행 결과** — 파일을 만든 Pod만 `READY 1/1`이 되고, **그 IP만** Endpoints에 나타난다.

```
NAME                              READY   STATUS    RESTARTS   AGE   IP            NODE
readiness-demo-65d6cdbbd7-qk25v   1/1     Running   0          41s   10.244.1.26   learn-worker
readiness-demo-65d6cdbbd7-tfpsk   0/1     Running   0          41s   10.244.2.31   learn-worker2

NAME             ENDPOINTS        AGE
readiness-demo   10.244.1.26:80   41s
```

```
10.244.1.26  ready=true
10.244.2.31  ready=false
```

`10.244.1.26`(파일 생성)만 `ready=true`가 되어 Endpoints에 등장하고, `10.244.2.31`은 여전히 목록에 없다. **readiness는 트래픽 라우팅을 직접 제어한다**는 것을 증명했다. 롤링 업데이트(Step 05)가 무중단인 이유도 바로 이것 — 새 Pod이 Ready가 되기 전에는 Service가 트래픽을 안 보낸다.

> 💡 **실무 팁**: readinessProbe가 없으면 컨테이너가 뜨자마자(아직 앱이 초기화 중이어도) 곧바로 Endpoints에 등록되어 트래픽을 받는다 → 배포 직후 순간적인 502/커넥션 리셋의 주범이다. HTTP 서버라면 readinessProbe는 사실상 필수다.

---

## 8-6. startupProbe — 기동이 느린 앱 보호하기

기동에 20초가 걸리는 앱을 흉내 낸다(`sleep 20 && nginx`). 여기에 **공격적인 livenessProbe**(3초 후부터 3초 주기, 3회 실패 시 kill)를 건다. startupProbe가 **없으면** 앱이 뜨기도 전에 liveness가 죽여버린다.

```yaml
# manifests/04-slow-no-startup.yaml
apiVersion: v1
kind: Pod
metadata:
  name: slow-no-startup
  namespace: step08
  labels:
    app: slow-no-startup
spec:
  containers:
    - name: web
      image: nginx:1.27-alpine
      # 일부러 20초 뒤에야 nginx를 띄우는 "느린 앱" 흉내
      command: ["sh", "-c", "sleep 20 && nginx -g 'daemon off;'"]
      ports:
        - containerPort: 80
      # startupProbe가 없고 liveness가 공격적 -> 앱이 뜨기 전에 죽여버림
      livenessProbe:
        httpGet:
          path: /
          port: 80
        initialDelaySeconds: 3
        periodSeconds: 3
        failureThreshold: 3   # 3s + 3s*3 ≈ 12초면 kill -> 20초 기동을 못 버팀
```

두 번째 Pod은 **완전히 동일한 liveness**에, startupProbe(30초 예산)만 추가한다.

```yaml
# manifests/05-slow-with-startup.yaml
apiVersion: v1
kind: Pod
metadata:
  name: slow-with-startup
  namespace: step08
  labels:
    app: slow-with-startup
spec:
  containers:
    - name: web
      image: nginx:1.27-alpine
      command: ["sh", "-c", "sleep 20 && nginx -g 'daemon off;'"]
      ports:
        - containerPort: 80
      # startupProbe가 기동을 보호: 성공할 때까지 liveness/readiness를 유예
      startupProbe:
        httpGet:
          path: /
          port: 80
        periodSeconds: 3
        failureThreshold: 10   # 3s*10 = 최대 30초까지 기동 대기 -> 20초 앱을 버텨줌
      livenessProbe:
        httpGet:
          path: /
          port: 80
        initialDelaySeconds: 3
        periodSeconds: 3
        failureThreshold: 3
```

```bash
kubectl apply -f manifests/04-slow-no-startup.yaml -f manifests/05-slow-with-startup.yaml
# 2~3분 관찰
kubectl get pods -n step08 -l 'app in (slow-no-startup,slow-with-startup)' -o wide
```

**실행 결과** — 같은 liveness인데 결과가 갈린다. startup 없는 쪽은 RESTARTS가 계속 오르고, 있는 쪽은 **0**이다.

```
NAME                READY   STATUS    RESTARTS      AGE     IP            NODE            NOMINATED NODE
slow-no-startup     1/1     Running   4 (10s ago)   2m55s   10.244.1.51   learn-worker
slow-with-startup   1/1     Running   0             2m55s   10.244.2.49   learn-worker2
```

Events를 비교하면 차이가 명확하다.

```bash
kubectl describe pod slow-no-startup -n step08 | sed -n '/Events:/,$p'
```

**실행 결과 (startupProbe 없음)** — liveness가 실패하고 `Killing`이 반복된다.

```
Events:
  Warning  Unhealthy  32s (x12 over 2m44s)  kubelet  Liveness probe failed: Get "http://10.244.1.51:80/": dial tcp 10.244.1.51:80: connect: connection refused
  Normal   Killing    32s (x4 over 2m38s)   kubelet  Container web failed liveness probe, will be restarted
  Normal   Started    1s (x5 over 2m47s)    kubelet  Container started
```

```bash
kubectl describe pod slow-with-startup -n step08 | sed -n '/Events:/,$p'
```

**실행 결과 (startupProbe 있음)** — startup 프로브가 6번 실패했지만(기동 중이라 당연) **Killing이 한 번도 없다.** startup이 성공하기 전까지 liveness가 아예 동작하지 않기 때문이다.

```
Events:
  Normal   Started    2m47s                  kubelet  Container started
  Warning  Unhealthy  2m29s (x6 over 2m44s)  kubelet  Startup probe failed: Get "http://10.244.2.49:80/": dial tcp 10.244.2.49:80: connect: connection refused
```

`Startup probe failed`는 6번 찍혔지만 `Killing`도 `Back-off`도 없이 RESTARTS가 0이다 → startupProbe가 20초 기동을 성공적으로 보호했다.

> ⚠️ **함정**: startupProbe의 "기동 예산"은 `periodSeconds × failureThreshold`다. 여기선 `3 × 10 = 30초`라 20초 앱을 버텼다. 앱 기동이 이보다 오래 걸리면 startup도 결국 실패해 재시작 루프에 빠진다. **느린 앱일수록 failureThreshold를 넉넉히** 잡아라. (liveness의 initialDelaySeconds로 대신 버티려 하면, 정상 운영 중의 장애 감지까지 느려지므로 startupProbe가 정답이다.)

---

## 정리

| 프로브 | 실패 시 동작 | 대상을 Endpoints에서 뺀다? | 재시작한다? | 주 용도 |
|--------|-------------|:---:|:---:|---------|
| livenessProbe | 컨테이너 재시작 | ✗ | ✓ | 데드락/행 자동 복구 |
| readinessProbe | 트래픽 차단 | ✓ | ✗ | 배포·의존성 대기 중 트래픽 보호 |
| startupProbe | 성공까지 유예, 최종 실패 시 재시작 | ✗ | ✓(최종) | 느린 기동 보호 |

| 핸들러 | 성공 조건 | 예시 |
|--------|-----------|------|
| httpGet | HTTP 200~399 | `httpGet: { path: /healthz, port: 8080 }` |
| tcpSocket | TCP 연결 성공 | `tcpSocket: { port: 5432 }` |
| exec | 종료코드 0 | `exec: { command: ["cat", "/tmp/ready"] }` |

핵심 산수: **재시작까지 시간 = initialDelaySeconds + periodSeconds × failureThreshold**, **startup 기동 예산 = periodSeconds × failureThreshold**.

## 연습 과제

→ [challenge.md](./challenge.md)

## 다음 단계

→ [Step 09 — 리소스 관리](../step-09-resources/)

---

## 실습 파일

이 스텝의 실습 파일은 `manifests/` 아래의 매니페스트 5개와, 그것들을 순서대로 실행해 주는 `commands.sh` 스크립트로 이루어져 있습니다. 매니페스트는 번호 순서가 곧 강의 순서입니다 — `01`은 세 프로브가 모두 정상 동작하는 기준점(8-1), `02`는 일부러 CrashLoopBackOff를 만드는 실패 사례(8-4), `03`은 readiness와 Service Endpoints의 관계를 증명하는 Deployment + Service(8-5), `04`/`05`는 startupProbe의 유무만 다른 대조 실험(8-6)입니다. 모든 리소스는 `step08` 네임스페이스에 생성되므로, 실습이 끝나면 `kubectl delete namespace step08` 한 줄로 전부 정리할 수 있습니다.

### manifests/01-healthy-all-probes.yaml

8-1에서 가장 먼저 적용하는 "정상 동작 기준점" Pod입니다. **세 종류의 프로브와 세 종류의 핸들러를 한 매니페스트 안에서 한 번씩 모두** 보여주는 것이 이 파일의 목적입니다.

- `startupProbe`는 `exec: command: ["cat", "/etc/nginx/nginx.conf"]` — nginx 설정 파일을 읽을 수 있으면(종료코드 0) 기동 완료로 봅니다. `periodSeconds: 3` × `failureThreshold: 10` = **최대 30초까지** 기동을 기다려 줍니다.
- `readinessProbe`는 `tcpSocket: port: 80` — 80번 포트에 TCP 연결이 되면 Ready입니다. `initialDelaySeconds: 2`, `periodSeconds: 5`.
- `livenessProbe`는 `httpGet: path: / port: 80` — HTTP 200~399면 성공. `initialDelaySeconds: 5` + `periodSeconds: 10` × `failureThreshold: 3` 이므로, 만약 계속 실패한다면 최대 35초 뒤에 재시작됩니다. 이 파일은 다섯 매니페스트 중 유일하게 `timeoutSeconds: 1`을 **명시**하고 있는데, 사실 이건 기본값과 같은 값입니다(8-2의 함정 항목 참고). 기본값이 단 1초라는 사실을 눈에 띄게 하려고 일부러 적어 둔 것입니다.
- nginx는 즉시 기동되고 80번 포트에서 정상 응답하므로 세 프로브가 모두 통과하고 `READY 1/1`이 됩니다. `kubectl describe`의 `Liveness: / Readiness: / Startup:` 줄에서 이 필드 값들이 그대로 다시 보이는지 대조해 보세요.

```yaml file="./manifests/01-healthy-all-probes.yaml"
```

### manifests/02-crashloop-liveness.yaml

8-4에서 **일부러 CrashLoopBackOff를 재현하기 위한 "고장난" Pod**입니다. 컨테이너 자체는 아무 문제 없는 `nginx:1.27-alpine`이지만, `livenessProbe`의 `port: 9999`가 함정입니다.

- nginx는 **80번 포트**에서 도는데 프로브는 **9999번 포트**를 찌릅니다. 그 포트에는 아무도 없으므로 프로브는 `connection refused`로 **영원히 실패**합니다.
- `initialDelaySeconds: 3`, `periodSeconds: 3`, `failureThreshold: 2` — 즉 컨테이너가 뜬 지 약 `3 + 3 × 2 = 9초`면 kubelet이 컨테이너를 kill합니다.
- `restartPolicy: Always`(기본값이지만 학습을 위해 명시)라서 죽이면 곧바로 다시 살리고, 살아나도 프로브는 또 실패합니다. 이 반복이 지수 백오프를 유발해 **`CrashLoopBackOff` + 계속 오르는 `RESTARTS`**로 나타납니다.
- 학습 포인트는 "앱 로그에는 아무 에러가 없다"는 점입니다. 원인은 오직 `kubectl describe`의 Events에 있는 `Liveness probe failed: ... dial tcp ...:9999: connect: connection refused` 한 줄뿐입니다. 실무에서 CrashLoopBackOff를 만나면 로그보다 Events를 먼저 보라는 교훈이 여기서 나옵니다.

```yaml file="./manifests/02-crashloop-liveness.yaml"
```

### manifests/03-readiness-endpoints.yaml

8-5에서 쓰는 파일로, Deployment(replicas 2)와 Service를 `---`로 이어 붙인 **하나의 매니페스트 두 리소스** 구성입니다. "readiness가 실패하면 Service Endpoints에서 빠진다"를 눈으로 증명하는 것이 목적입니다.

- 핵심은 `readinessProbe.exec.command: ["cat", "/tmp/ready"]` 입니다. 컨테이너 안에 `/tmp/ready` 파일이 **없으면** `cat`이 종료코드 1로 실패하므로 Pod은 계속 `NotReady`로 남습니다. 이미지에는 그 파일이 없으므로 **처음엔 두 Pod 모두 `READY 0/1`**입니다.
- `failureThreshold: 1`, `successThreshold: 1`, `periodSeconds: 3` 이라 한 번만 실패/성공해도 즉시 상태가 뒤집힙니다. 그래서 파일을 만들고 `sleep 6` 정도만 기다려도 Ready 전환을 관찰할 수 있습니다.
- Service `readiness-demo`는 `selector: app: readiness-demo`로 두 Pod을 모두 고르지만, **Ready가 아닌 Pod의 IP는 Endpoints에 실리지 않습니다.** 그래서 처음에는 `kubectl get endpoints`의 ENDPOINTS 칼럼이 비어 있습니다.
- `kubectl exec ... -- touch /tmp/ready`를 **한쪽 Pod에만** 실행하면 그 Pod만 `ready=true`가 되어 Endpoints에 나타납니다. 이것이 롤링 업데이트가 무중단일 수 있는 근본 원리입니다.
- 주의: `touch`로 만든 파일은 컨테이너 파일시스템에 있으므로, 그 컨테이너가 재시작되면 사라지고 다시 `NotReady`로 돌아갑니다.

```yaml file="./manifests/03-readiness-endpoints.yaml"
```

### manifests/04-slow-no-startup.yaml

8-6 대조 실험의 **"before"** 쪽입니다. `command: ["sh", "-c", "sleep 20 && nginx -g 'daemon off;'"]` 로 **기동에 20초가 걸리는 느린 앱**을 흉내 냅니다.

- 여기에 `initialDelaySeconds: 3`, `periodSeconds: 3`, `failureThreshold: 3`인 공격적인 `livenessProbe`를 겁니다. 계산하면 `3 + 3 × 3 ≈ 12초`만에 kill 판정이 나옵니다.
- 앱이 뜨는 데 20초가 필요한데 프로브는 12초 만에 죽이므로, **앱은 영원히 기동을 완주하지 못합니다.** `Liveness probe failed: ... connection refused` → `Killing` → 재시작이 반복되며 `RESTARTS`가 계속 오릅니다.
- 다음 파일(`05`)과 **liveness 설정이 완전히 동일**하다는 점이 중요합니다. 두 파일의 유일한 차이는 startupProbe의 유무이고, 그것만으로 결과가 갈립니다.
- 이 파일은 의도적으로 잘못 설계된 예시입니다. 실무에서 "느린 기동"을 liveness의 `initialDelaySeconds`만 늘려서 때우려는 유혹의 위험을 보여주기 위한 대조군입니다.

```yaml file="./manifests/04-slow-no-startup.yaml"
```

### manifests/05-slow-with-startup.yaml

8-6 대조 실험의 **"after"** 쪽입니다. `04`와 이미지·`command`·`livenessProbe`가 글자 하나까지 같고, **`startupProbe`만 추가**되어 있습니다.

- `startupProbe`는 `httpGet: path: / port: 80`에 `periodSeconds: 3`, `failureThreshold: 10` — 기동 예산이 `3 × 10 = 30초`입니다. 20초 기동 앱을 여유 있게 커버합니다.
- startupProbe가 성공하기 전까지 **liveness와 readiness는 아예 실행되지 않습니다.** 그래서 앱이 아직 `sleep 20` 중이라 `Startup probe failed`가 여러 번 찍혀도 `Killing`은 한 번도 발생하지 않고, 최종적으로 `RESTARTS 0`으로 정상 기동합니다.
- startup이 한 번 성공하면 그 이후로는 다시 실행되지 않고, 곧바로 liveness/readiness가 평소대로(짧고 공격적인 주기로) 감시를 시작합니다. **기동 보호는 startupProbe, 운영 감시는 짧은 livenessProbe** 라는 역할 분리가 이 파일의 요지입니다.
- 만약 앱 기동이 30초를 넘긴다면 startupProbe마저 실패해 결국 재시작 루프에 빠집니다. `periodSeconds × failureThreshold`를 앱의 최악 기동 시간보다 넉넉히 잡아야 합니다.

```yaml file="./manifests/05-slow-with-startup.yaml"
```

### commands.sh

강의 8-1 ~ 8-6을 처음부터 끝까지 **한 번에 재현하는 실행 스크립트**입니다. 스크립트 안의 `cd "$(dirname "$0")"` 덕분에 어느 위치에서 실행하든 `manifests/` 상대경로가 맞습니다.

- `set -euo pipefail`로 중간에 하나라도 실패하면 즉시 멈춥니다. 맨 처음 `kubectl config current-context`로 현재 컨텍스트를 출력하는 단계가 있는데, 이건 **출력만 할 뿐 검증하고 중단하지는 않습니다.** `kind-learn`이 맞는지, 다른 클러스터에 붙어 있지 않은지 **반드시 눈으로 확인**하세요.
- 두 번째 줄의 `export PATH="/opt/homebrew/bin:$PATH"`는 macOS(Apple Silicon)에서 Homebrew로 설치한 `kubectl`을 찾기 위한 줄입니다. Linux나 Intel Mac 등 `kubectl` 경로가 다른 환경이라면 이 줄은 없어도 무방합니다.
- 네임스페이스는 `kubectl create namespace step08 --dry-run=client -o yaml | kubectl apply -f -` 패턴으로 만듭니다. 이미 존재해도 에러 없이 넘어가는(멱등한) 관용구입니다.
- 각 단계마다 관찰에 필요한 만큼 `sleep`이 들어 있습니다 — CrashLoopBackOff가 쌓일 때까지 `sleep 70`, startupProbe 대조 실험 결과가 갈릴 때까지 `sleep 135`. 그래서 스크립트 전체는 **4분 남짓 걸립니다.** 중간에 끊지 말고 기다리세요.
- readiness 실습 구간에서는 `POD=$(kubectl get pods ... -o jsonpath='{.items[0].metadata.name}')`로 **첫 번째 Pod 이름을 자동으로 뽑아** 거기에만 `touch /tmp/ready`를 실행합니다. 강의 본문에서 Pod 이름을 손으로 복사해 넣던 부분을 자동화한 것입니다.
- **주의**: 스크립트 마지막에 `kubectl delete namespace step08`이 들어 있어 **실습 리소스를 전부 삭제하고 끝납니다.** 결과를 천천히 뜯어보고 싶다면 그 부분을 실행하지 말고, 명령을 하나씩 직접 따라 치는 편이 좋습니다.

```bash file="./commands.sh"
```
