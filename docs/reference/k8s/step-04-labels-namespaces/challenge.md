# Step 04 연습 과제

> 모든 작업은 `step04` 네임스페이스 안에서 하세요. 막히면 해답을 펼쳐 보세요.
> 시작 전 `kubectl apply -f manifests/namespace.yaml -f manifests/pods.yaml` 로 파드를 다시 만들어 두면 좋습니다.

---

### 과제 1. 백엔드 프로덕션만 골라내기

`tier=backend` 이면서 `env=prod` 인 파드만 출력하는 명령을 쓰세요.

<details>
<summary>해답</summary>

```bash
kubectl get pods -n step04 -l 'tier=backend,env=prod'
```
콤마는 AND 입니다. `api-prod-1` 하나만 나옵니다.
</details>

---

### 과제 2. dev 가 아닌 web 파드

`app=web` 이지만 `env` 가 `dev` 가 **아닌** 파드를 두 가지 방법(부등식, 집합)으로 각각 고르세요.

<details>
<summary>해답</summary>

```bash
# 방법 A: 부등식
kubectl get pods -n step04 -l 'app=web,env!=dev'

# 방법 B: 집합 기반 notin
kubectl get pods -n step04 -l 'app=web,env notin (dev)'
```
둘 다 `web-prod-1`, `web-prod-2` 를 반환합니다.
</details>

---

### 과제 3. 카나리 표시 후 되돌리기

`web-prod-2` 에 `release=canary` 레이블을 붙였다가, 다시 완전히 제거하세요. 각 단계에서 `--show-labels` 로 확인합니다.

<details>
<summary>해답</summary>

```bash
kubectl label pod web-prod-2 -n step04 release=canary
kubectl get pod web-prod-2 -n step04 --show-labels
kubectl label pod web-prod-2 -n step04 release-      # 키 끝에 '-' → 제거
kubectl get pod web-prod-2 -n step04 --show-labels
```
</details>

---

### 과제 4. 레이블 하나로 프론트엔드 전멸시키기 (안전하게)

`tier=frontend` 인 파드를 전부 삭제하되, **삭제 전에 반드시 미리보기**를 하세요. 왜 미리보기가 중요한지 한 줄로 설명해 보세요.

<details>
<summary>해답</summary>

```bash
# 1) 미리보기 — 무엇이 지워질지 먼저 확인
kubectl get pods -n step04 -l tier=frontend
# 2) 확인 후 삭제
kubectl delete pod -n step04 -l tier=frontend
```
셀렉터 기반 삭제는 조건에 맞는 것을 **전부** 지웁니다. 셀렉터를 넓게 잡으면 의도보다 많은 리소스가 사라지므로, `get` 으로 대상 집합을 먼저 눈으로 확인하는 것이 가장 확실한 안전장치입니다.
</details>

---

### 과제 5. 레이블 vs 어노테이션 판단

다음 정보를 파드에 붙인다면 각각 레이블과 어노테이션 중 무엇으로 해야 할까요? 이유와 함께.
(a) 소속 팀 `payments` (b) 배포한 Git 커밋 해시 (c) 담당자 슬랙 채널 URL (d) 실행 환경 `staging`

<details>
<summary>해답</summary>

- (a) 팀 → **레이블** (`team=payments`). 팀 단위로 리소스를 골라내는 일이 흔하므로 선택 대상.
- (b) 커밋 해시 → **어노테이션**. 값이 길고 셀렉터로 고를 일이 없는 기록성 정보.
- (c) 슬랙 URL → **어노테이션**. URL 은 레이블 문자 규칙을 어기고, 선택 기준도 아님.
- (d) 환경 → **레이블** (`env=staging`). 환경별 선택/정책 적용의 핵심 축.

원칙: **"이걸로 오브젝트를 골라낼 일이 있는가?"** → 있으면 레이블, 없으면 어노테이션.
</details>
