# Step 15 연습 과제 — 네트워크 정책

> 전제: `step15` 에 server / client-allowed(role=frontend) / client-denied(role=other) 가 있다.
> CNI 가 NetworkPolicy 를 강제하는 환경이라 가정(아니면 개념 확인용으로 풀 것).

---

## 과제 1. "이 파드만 완전 격리"

`client-denied` 파드를 **완전히 격리**(들어오는 것도, 나가는 것도 전부 차단)하는 정책을 작성하라.

<details><summary>해답</summary>

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: isolate-denied, namespace: step15 }
spec:
  podSelector: { matchLabels: { role: other } }
  policyTypes: [Ingress, Egress]   # 규칙 없음 = 양방향 전부 차단
```
`policyTypes` 에 둘 다 넣고 ingress/egress 규칙을 비우면 완전 격리. (단, egress 를 막으면 DNS 도 막힌다는 점에 유의.)
</details>

---

## 과제 2. default-deny 후 DNS만 살리기

네임스페이스 전체에 egress default-deny 를 걸되, **모든 파드가 DNS(53)만은 쓸 수 있게** 하라.

<details><summary>해답</summary>

```yaml
# (1) egress 전면 차단
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-egress, namespace: step15 }
spec:
  podSelector: {}
  policyTypes: [Egress]
---
# (2) 모든 파드에 DNS 만 허용 (additive)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-dns, namespace: step15 }
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - ports: [ { protocol: UDP, port: 53 }, { protocol: TCP, port: 53 } ]
```
정책은 합집합이라 (1)+(2) = "egress 는 다 막되 53만 허용" 이 된다.
</details>

---

## 과제 3. namespaceSelector AND podSelector

`step15-ext` 네임스페이스(team=trusted) 안에서 **`role=frontend` 레이블을 가진 파드만** server 에 허용하라.
(네임스페이스 조건 **그리고** 파드 레이블 조건 = AND)

<details><summary>해답</summary>

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-trusted-frontend, namespace: step15 }
spec:
  podSelector: { matchLabels: { app: server } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: { matchLabels: { team: trusted } }
          podSelector:       { matchLabels: { role: frontend } }   # 같은 from 항목 = AND
      ports: [ { protocol: TCP, port: 80 } ]
```
`namespaceSelector` 와 `podSelector` 를 **같은 리스트 항목**에 두면 AND. 서로 다른 `-` 항목으로 나누면 OR 가 되어 의미가 완전히 달라진다.
</details>

---

## 과제 4. (진단) "정책 걸었는데 안 막힌다"

동료가 default-deny 를 걸었는데 트래픽이 그대로 통과한다고 한다. 원인 후보 3가지와 각각의 확인 방법을 제시하라.

<details><summary>해답</summary>

1. **CNI 가 NetworkPolicy 를 강제하지 않음**(구버전 kindnet 등). 확인: 최소 default-deny 스모크 테스트 자체가 안 먹힘 → CNI/버전 확인, 필요시 Calico.
2. **podSelector 레이블 오타** — 정책이 엉뚱한(또는 0개) 파드에 걸림. 확인: `kubectl describe netpol` 의 PodSelector 와 `kubectl get pod --show-labels` 대조.
3. **네임스페이스 착오** — 정책과 대상 파드가 다른 네임스페이스. NetworkPolicy 는 네임스페이스 범위. 확인: `kubectl get netpol -A`.

추가: **정책 방향 누락**(egress 를 막으려는데 `policyTypes: [Egress]` 를 안 적음)도 흔함.
</details>

---

## 과제 5. 계층형 화이트리스트 설계 (서술)

3-tier(web → api → db) 앱에서 "web 은 api 만, api 는 db 만" 통신하도록 하는 정책 세트를 **말로** 설계하라(정책 개수와 각 대상/허용 출처).

<details><summary>해답</summary>

- **db**: podSelector=db, ingress from podSelector=api, port=5432. (db 는 api 에서 오는 것만)
- **api**: podSelector=api, ingress from podSelector=web, port=8080. (api 는 web 에서 오는 것만)
- **web**: podSelector=web, ingress from ipBlock/ingress-controller (외부 진입). 필요 시.
- 그리고 네임스페이스 전체 **default-deny ingress + allow-dns egress** 를 깔아 기본을 닫는다.

핵심: 각 tier 를 "보호 대상 podSelector" 로 두고, 바로 앞 tier 만 `from` 으로 허용 → 최소 통신 경로만 열리는 화이트리스트.
</details>
