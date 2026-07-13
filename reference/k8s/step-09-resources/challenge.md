# Step 09 연습 과제 — 리소스 관리

각 문제를 먼저 스스로 풀고, 접힌 정답을 펼쳐 확인하라. 모든 실습은 `step09` 네임스페이스에서 진행하고 끝나면 `kubectl delete namespace step09`로 정리한다.

---

## 문제 1 — QoS 등급 맞히기

아래 세 컨테이너 스펙의 QoS 클래스는 각각 무엇인가?

- (A) `requests: {cpu: 100m, memory: 128Mi}`, `limits: {cpu: 100m, memory: 128Mi}`
- (B) `requests: {memory: 128Mi}`, `limits: {memory: 256Mi}` (cpu는 없음)
- (C) `resources` 필드 자체가 없음

<details>
<summary>정답 보기</summary>

- (A) **Guaranteed** — cpu·memory 모두 requests==limits.
- (B) **Burstable** — requests/limit이 일부라도 있고 Guaranteed 조건은 못 채움(cpu 없음, memory도 req≠limit).
- (C) **BestEffort** — 아무 것도 선언하지 않음. 노드 압박 시 가장 먼저 축출된다.

확인 명령:
```bash
kubectl get pod <name> -n step09 -o jsonpath='{.status.qosClass}'
```
</details>

---

## 문제 2 — 137은 무슨 뜻인가

어떤 파드가 `STATUS: OOMKilled`, `Exit Code: 137`로 죽었다. (1) 137이라는 숫자의 의미와 (2) 근본 원인, (3) 올바른 대응을 설명하라.

<details>
<summary>정답 보기</summary>

1. **137 = 128 + 9**. 9는 SIGKILL 시그널 번호다. 즉 프로세스가 SIGKILL로 강제 종료됐다는 뜻이며, 메모리의 경우 커널 cgroup OOM killer가 보낸 것이다.
2. 컨테이너가 자신의 **메모리 limit을 초과**했다. 메모리는 압축 불가(incompressible) 자원이라 스로틀링이 불가능해 커널이 프로세스를 죽인다.
3. 대응: 앱의 실제 사용량을 측정(`kubectl top pod` 또는 프로파일링)한 뒤 **메모리 limit을 올리거나**, 앱의 메모리 누수/과다 사용을 고친다. limit만 무작정 올리면 노드 OOM으로 번질 수 있으니 requests도 함께 조정한다.
</details>

---

## 문제 3 — CPU를 20m로 조여도 안 죽는 이유

메모리 limit을 넘으면 파드가 죽는데, CPU limit(예: `50m`)을 넘겨서 CPU를 갈망하는 워크로드를 돌려도 파드는 `Running`을 유지한다. 왜인가?

<details>
<summary>정답 보기</summary>

CPU는 **압축 가능(compressible)** 자원이기 때문이다. limit을 넘으면 커널 CFS가 해당 컨테이너에 할당하는 CPU 시간을 스로틀링해서 **느리게** 만들 뿐, 프로세스를 죽이지 않는다. 자원을 "잠깐 덜 주는" 것이 가능하다.

반면 메모리는 이미 할당한 바이트를 "잠깐 돌려받는" 방법이 없어 초과 시 종료(OOMKill) 외에 선택지가 없다. 그래서 CPU limit은 성능 문제로, 메모리 limit은 가용성 문제로 이어진다.
</details>

---

## 문제 4 — LimitRange + ResourceQuota 조합의 함정

네임스페이스에 `requests.memory`를 관리하는 ResourceQuota만 걸어둔 상태에서, 개발자가 resources를 생략한 파드를 배포했더니 `must specify requests.memory` 오류로 거부됐다. 개발자가 매니페스트를 고치지 않고도 배포가 되게 하려면 클러스터 관리자는 무엇을 추가해야 하는가?

<details>
<summary>정답 보기</summary>

같은 네임스페이스에 **LimitRange**를 추가해 `defaultRequest`/`default`를 정의하면 된다.

동작 순서: 파드가 들어오면 LimitRanger admission이 먼저 기본 requests/limits를 **주입**하고, 그 다음 ResourceQuota가 "이제 requests.memory가 명시됐네"라고 판단해 총량만 검사한다. 결과적으로 개발자는 resources를 생략해도 되고, 관리자는 총량을 통제할 수 있다.

즉 ResourceQuota는 "빠짐없이 명시하라"를 강제하고, LimitRange는 그 명시를 "자동으로 채워"준다. 실무에서는 둘을 세트로 건다.
</details>

---

## 문제 5 — Pending인가 Forbidden인가

두 상황을 구분하라.

- 상황 X: requests.memory를 노드 용량보다 크게 잡은 파드를 배포했다.
- 상황 Y: ResourceQuota의 requests.memory 잔여량보다 큰 파드를 배포했다.

각각 파드는 어떤 상태가 되며, 어디서 막히는가?

<details>
<summary>정답 보기</summary>

- **상황 X → 파드는 생성되지만 `Pending`.** API 서버 검증은 통과해 오브젝트는 만들어지지만, 스케줄러가 requests를 담을 노드를 못 찾아 배치에 실패한다. `kubectl describe pod`에 `Insufficient memory` 이벤트가 뜬다.
- **상황 Y → 파드가 아예 생성되지 않고 `Forbidden`.** ResourceQuota는 admission 단계에서 검사하므로 오브젝트 생성 자체가 거부된다(`exceeded quota`). Pending 파드조차 남지 않는다.

핵심 차이: **스케줄링 실패(Pending)** vs **admission 거부(Forbidden)**. 배포 파이프라인에서 원인 모를 `Forbidden`이 나면 ResourceQuota를, `Pending`이 오래가면 노드 용량/requests를 의심하라.
</details>
