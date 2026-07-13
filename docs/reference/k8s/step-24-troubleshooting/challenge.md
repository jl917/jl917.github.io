# Step 24 — 진단 연습 과제

증상만 보고 **원인을 짚고 → 진단 명령을 고르고 → 해결책**을 말하는 훈련입니다.
각 과제를 먼저 스스로 풀고, 접힌 해답을 펼쳐 확인하세요. 모든 실습은 네임스페이스 `step24` 안에서만 하세요.

---

## 과제 1. "이미지는 분명 있는데 왜 안 뜨죠?"

동료가 프라이빗 레지스트리의 이미지를 쓰는 파드를 배포했더니 이렇게 나옵니다.

```
NAME       READY   STATUS             RESTARTS   AGE
payment    0/1     ImagePullBackOff   0          40s
```
`describe` Events 마지막 줄:
```
Failed to pull image "registry.internal/payment:v2": ... 401 Unauthorized
```

**Q. 24-1 의 nginx 예제(`not found`)와 원인이 어떻게 다른가? 어떤 명령으로 확인하고 무엇을 고쳐야 하나?**

<details><summary>해답</summary>

- 원인 카테고리가 다르다. `not found` 는 **태그/이름 오류**, `401 Unauthorized` 는 **인증(자격증명) 문제**다. 이미지는 존재하지만 kubelet 이 당길 권한이 없다.
- 확인: 파드 스펙에 `imagePullSecrets` 가 붙어 있는지, 그 Secret 이 실제로 존재하고 올바른 레지스트리 자격증명(type `kubernetes.io/dockerconfigjson`)인지.
  ```bash
  kubectl get pod payment -n step24 -o jsonpath='{.spec.imagePullSecrets}'; echo
  kubectl get secret -n step24
  ```
- 해결: 레지스트리 시크릿을 만들고 파드(또는 ServiceAccount)에 연결한다.
  ```bash
  kubectl create secret docker-registry regcred -n step24 \
    --docker-server=registry.internal --docker-username=... --docker-password=...
  # 파드 spec.imagePullSecrets 또는 SA 에 regcred 를 추가
  ```
- 교훈: **ImagePullBackOff 는 항상 Events 의 마지막 문장으로 갈래를 나눈다.** `not found`(태그) / `401·403`(인증) / `no such host`(레지스트리 주소·네트워크).
</details>

---

## 과제 2. "RESTARTS 가 계속 올라가는데 로그가 비어 있어요"

```
NAME     READY   STATUS             RESTARTS       AGE
worker   0/1     CrashLoopBackOff   6 (20s ago)    4m
```
`kubectl logs worker -n step24` 를 치면 아무것도 안 나오거나 `unable to retrieve container logs` 가 뜹니다.

**Q. 왜 로그가 비어 보이나? 죽은 이유는 어디서 봐야 하나? Exit Code 가 137 이면/1 이면 각각 무엇을 의심하나?**

<details><summary>해답</summary>

- `kubectl logs`(옵션 없이)는 **지금 막 재시작된 컨테이너**의 로그를 보여준다. 방금 떠서 아직 출력이 없거나, 그 사이 또 죽어 비어 보인다.
- 죽은 이유는 **직전 컨테이너**에 있다.
  ```bash
  kubectl logs worker -n step24 --previous
  kubectl describe pod worker -n step24 | sed -n '/Last State:/,/Restart Count:/p'
  ```
- Exit Code 로 방향을 가른다.
  - **1 / 2** → 앱이 스스로 실패 종료(설정·환경변수·의존성 문제). 로그의 스택트레이스를 본다.
  - **137**(=128+9, SIGKILL) → **OOM** 또는 외부 강제 종료. `lastState.terminated.reason` 이 `OOMKilled` 인지 확인(→ 과제 4).
  - **143**(=128+15, SIGTERM) → 정상 종료 신호를 받았는데 앱이 죽음으로 처리. graceful shutdown/probe 설정을 본다.
- 추가 함정: **liveness probe** 가 계속 실패하면 앱은 멀쩡해도 kubelet 이 죽여 CrashLoop 가 된다. `describe` 의 `Liveness` 설정과 probe 실패 이벤트를 확인.
</details>

---

## 과제 3. "Deployment 도 파드도 Running 인데 Service 접속만 안 돼요"

```
kubectl get pod -n step24 -l app=api       # 3개 다 Running
kubectl get svc api-svc -n step24          # ClusterIP 있음
curl http://api-svc.step24 -> 계속 timeout / connection refused
```

**Q. 가장 먼저 확인할 단 하나의 명령은? 결과에 따라 원인이 어떻게 갈리나?**

<details><summary>해답</summary>

- 가장 먼저: **`kubectl get endpoints api-svc -n step24`** (또는 `get endpointslice`).
- 갈림길:
  - **`ENDPOINTS <none>`** → 백엔드가 0개. 원인은 파드 쪽이다.
    1. Service `selector` 와 파드 `labels` 불일치(오타 포함) — `kubectl get svc api-svc -o jsonpath='{.spec.selector}'` 와 파드 `--show-labels` 비교.
    2. 파드가 `Ready` 가 아님(readinessProbe 실패) → Endpoints 에서 제외된다.
    3. `targetPort` 가 컨테이너 실제 포트와 다름.
  - **Endpoints 가 채워져 있는데도 안 됨** → 그때서야 네트워크 레이어를 본다: NetworkPolicy 차단, kube-proxy, DNS(`nslookup api-svc`), 포트/프로토콜.
- 교훈: "Service 안 됨" 은 **무조건 endpoints 부터**. 파드가 Running 인 것과 Service 뒤에 연결된 것은 별개다.
</details>

---

## 과제 4. "OOM 인지 그냥 크래시인지 어떻게 구별하죠?"

두 파드 모두 `CrashLoopBackOff` 로 보입니다.

```
pod-A  Last State: Terminated  Reason: Error       Exit Code: 1
pod-B  Last State: Terminated  Reason: OOMKilled   Exit Code: 137
```

**Q. 둘의 근본 원인과 해결이 어떻게 다른가? OOM 을 확정하는 필드는 무엇인가?**

<details><summary>해답</summary>

- **pod-A (Error / 1)**: 앱 코드/설정이 스스로 실패해 종료. 해결은 **로그**(`--previous`)가 가리키는 원인 수정(누락된 env, 잘못된 커맨드, DB 연결 실패 등). 메모리를 올려도 안 낫는다.
- **pod-B (OOMKilled / 137)**: 커널 OOM killer 가 죽였다. **메모리 문제**다. 해결은 `resources.limits.memory` 상향 또는 앱의 메모리 사용 절감(누수 수정, 배치 크기 축소, JVM 이면 cgroup-aware 힙 설정).
- OOM 확정 필드:
  ```bash
  kubectl get pod pod-B -n step24 -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'  # OOMKilled
  ```
  `reason: OOMKilled` + `exitCode: 137` 조합이면 확정. `describe` 의 `Last State` 에도 동일하게 뜬다.
- 함정: limit 을 **지나치게 낮게** 주면 컨테이너가 시작조차 못 하고 `StartError`/exit **128**("container init was OOM-killed")이 난다. 이건 런타임 OOM(137)과 다른 상태 — 최소 시작 가능한 크기는 확보한 뒤 실사용량에 맞춰 올린다.
</details>

---

## 과제 5. "Pending 인데 자원은 남아도는데요?"

```
NAME       READY   STATUS    RESTARTS   AGE
reporter   0/1     Pending   0          3m
```
`describe` Events:
```
Warning  FailedScheduling  ...  0/3 nodes are available: pod has unbound immediate PersistentVolumeClaims.
```

**Q. CPU/메모리는 넉넉한데 왜 Pending 인가? 어디를 더 봐야 진짜 원인이 나오나?**

<details><summary>해답</summary>

- 이 Pending 은 **자원 문제가 아니라 볼륨 문제**다. 파드가 참조하는 PVC 가 아직 `Bound` 가 아니라서 스케줄러가 배치를 보류한다(`unbound immediate PersistentVolumeClaims`).
- 진짜 원인은 **PVC 오브젝트의 이벤트**에 있다.
  ```bash
  kubectl get pvc -n step24
  kubectl describe pvc <name> -n step24 | sed -n '/Events:/,$p'
  ```
  흔한 메시지: `storageclass ... not found`(존재하지 않는 SC), 또는 프로비저너가 없어서 계속 `Pending`, 또는 accessMode/용량을 만족하는 PV 부재.
- 해결: 실제 존재하는 StorageClass 로 PVC 재생성(`kubectl get storageclass` 로 확인), 또는 정적 PV 를 알맞게 제공.
- 교훈: `FailedScheduling` 이라고 무조건 CPU/메모리를 의심하지 말 것. 메시지가 `Insufficient cpu/memory`(자원), `untolerated taint`(taint), `didn't match node selector/affinity`(배치 규칙), `unbound ... PersistentVolumeClaims`(볼륨) 중 어느 것인지로 갈래가 완전히 달라진다.
</details>

---

## 다음 단계
→ [Step 25 — 종합 실습](../step-25-final-project/README.md)
