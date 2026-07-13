# Step 15 — 네트워크 정책 (NetworkPolicy)

> **학습 목표**
> - NetworkPolicy 가 무엇인지(파드 간 트래픽 방화벽, L3/L4) 이해한다.
> - **내 CNI 가 NetworkPolicy 를 실제로 강제하는지 먼저 확인**한다.
> - 기본 차단(default deny), 화이트리스트 패턴을 직접 만들고 검증한다.
> - `podSelector` / `namespaceSelector`, ingress / egress 규칙, DNS 함정을 익힌다.
>
> **선행 지식**: Step 04(레이블/셀렉터), Step 06(서비스/DNS).
> **소요 시간**: 40~60분

---

## 0. 먼저 — 내 CNI 는 NetworkPolicy 를 강제하는가?

**NetworkPolicy 오브젝트는 API 서버에 언제나 저장됩니다. 하지만 그것을 실제 트래픽에 "강제(enforce)" 하는 것은 CNI 플러그인입니다.** CNI 가 NetworkPolicy 를 지원하지 않으면, 정책을 `apply` 해도 `kubectl get`엔 보이지만 **트래픽은 전혀 안 막힙니다**(조용한 무력화 — 보안상 가장 위험한 함정).

역사적으로 kind 기본 CNI 인 **kindnet 은 NetworkPolicy 를 강제하지 않았습니다.** 그래서 예전 교재들은 "kind 에서 NetworkPolicy 실습하려면 Calico 를 깔아라" 고 안내합니다.

**그런데 이 환경의 kindnet 을 직접 시험해 보니 강제합니다.** 확인한 버전:

```bash
kubectl get ds kindnet -n kube-system -o jsonpath='{.spec.template.spec.containers[0].image}'
```
```
docker.io/kindest/kindnetd:v20260528-9350166c
```

최근(2024+) kindnet 은 nftables 기반 NetworkPolicy 강제 기능이 들어갔고, 이 버전은 **ingress/egress, podSelector, namespaceSelector 를 모두 정확히 강제**했습니다(아래 전 과정 실측).

> ⚠️ **여러분 환경에서 반드시 아래 3절 "강제 여부 스모크 테스트" 를 먼저 하세요.** 만약 default-deny 를 걸었는데도 트래픽이 통과한다면, 여러분의 kindnet 은 강제하지 않는 구버전입니다. 그때는 **정책이 적용되려면 Calico 등 지원 CNI 가 필요**하며(kindnet 미적용), 이 스텝은 매니페스트/개념 위주로 읽고 넘어가면 됩니다. 개념과 YAML 문법은 CNI 와 무관하게 동일합니다.

---

## 1. NetworkPolicy 개념

- **대상**: `spec.podSelector` 로 고른 파드(들). 그 파드로 들어오는(ingress) / 나가는(egress) 트래픽을 통제.
- **화이트리스트 모델**: 어떤 파드에 **하나라도** NetworkPolicy 가 걸리는 순간, 그 파드의 해당 방향(Ingress/Egress)은 **기본이 "차단"** 이 되고, 정책이 명시적으로 허용한 것만 통과합니다.
- **정책이 없으면**: 모두 허용(기본 open). 쿠버네티스의 기본 네트워크는 "flat" — 모든 파드가 서로 통신 가능.
- **additive(합집합)**: 여러 정책이 같은 파드에 걸리면 허용 규칙들이 **OR** 로 합쳐집니다. "거부" 규칙은 없습니다 — 안 열면 닫힌 것.

```
정책 없음:      모두 통행 자유
        ┌────────────────────────────────┐
default-deny:  server 로 들어오는 모든 것 차단
        ┌────────────────────────────────┐
+ allow:       그 위에 role=frontend 만 구멍 뚫기(화이트리스트)
```

---

## 2. 실습 워크로드

- `server` : nginx(80) — 보호 대상
- `client-allowed` : `role=frontend` — 허용할 클라이언트
- `client-denied` : `role=other` — 차단될 클라이언트

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/10-workloads.yaml
kubectl wait -n step15 --for=condition=ready pod --all --timeout=120s
```

---

## 3. 강제 여부 스모크 테스트

### 3-1. 정책 없을 때 (기준선)

```bash
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>"
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>"
```
```
<title>Welcome to nginx!</title>
<title>Welcome to nginx!</title>
```
둘 다 접속됩니다(기본 open).

### 3-2. default-deny 적용 (`manifests/20-default-deny.yaml`)

```yaml
spec:
  podSelector: {}        # 네임스페이스의 모든 파드
  policyTypes: [Ingress] # ingress 규칙이 하나도 없으므로 = 전부 차단
```

```bash
kubectl apply -f manifests/20-default-deny.yaml
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED/timeout"
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED/timeout"
```
```
BLOCKED/timeout
BLOCKED/timeout
```

**둘 다 막혔습니다 → 이 kindnet 은 NetworkPolicy 를 강제합니다.** (만약 여기서 여전히 `<title>` 이 나온다면 강제 안 하는 CNI 이니, 위 0절 안내대로 개념 위주로 진행하세요.)

---

## 4. 화이트리스트 — podSelector (`manifests/21-allow-frontend.yaml`)

default-deny 위에 "role=frontend 만 server:80 허용" 을 더합니다.

```yaml
spec:
  podSelector: { matchLabels: { app: server } }   # 보호 대상 = server
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { role: frontend } }
      ports:
        - { protocol: TCP, port: 80 }
```

```bash
kubectl apply -f manifests/21-allow-frontend.yaml
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED"
kubectl exec -n step15 client-denied  -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED"
```
```
<title>Welcome to nginx!</title>   # client-allowed (role=frontend) 통과
BLOCKED                            # client-denied (role=other) 여전히 차단
```

`describe` 로 정책이 어떻게 해석됐는지 확인:
```bash
kubectl describe networkpolicy allow-frontend-to-server -n step15
```
```
Spec:
  PodSelector:     app=server
  Allowing ingress traffic:
    To Port: 80/TCP
    From:
      PodSelector: role=frontend
  Not affecting egress traffic
  Policy Types: Ingress
```

---

## 5. namespaceSelector — 네임스페이스 단위 허용 (`manifests/30-allow-namespace.yaml`)

특정 레이블(`team=trusted`)이 붙은 **네임스페이스** 에서 오는 트래픽만 허용합니다.

```bash
# 보조 네임스페이스에 레이블 부여 + 클라이언트 하나
kubectl create namespace step15-ext
kubectl label namespace step15-ext team=trusted --overwrite
kubectl run ext-client -n step15-ext --image=busybox:1.36 --restart=Never -- sh -c "sleep 3600"
kubectl wait -n step15-ext --for=condition=ready pod/ext-client --timeout=60s

# 이번엔 podSelector 허용을 지우고 namespaceSelector 허용만 남긴다
kubectl delete networkpolicy allow-frontend-to-server -n step15
kubectl apply -f manifests/30-allow-namespace.yaml

SERVER_IP=$(kubectl get svc server -n step15 -o jsonpath='{.spec.clusterIP}')
kubectl exec -n step15-ext ext-client     -- wget -qO- --timeout=4 http://$SERVER_IP | grep -o "<title>.*</title>" || echo "BLOCKED"
kubectl exec -n step15    client-allowed  -- wget -qO- --timeout=4 http://server     | grep -o "<title>.*</title>" || echo "BLOCKED"
```
```
<title>Welcome to nginx!</title>   # trusted 네임스페이스에서 온 요청 통과
BLOCKED                            # step15 안의 파드는(trusted 아님) 차단
```

> ⚠️ **podSelector 와 namespaceSelector 를 한 `from` 항목 안에 나란히** 두면 **AND**(그 네임스페이스 **그리고** 그 레이블 파드), **서로 다른 `from` 항목**으로 두면 **OR** 입니다. 의도치 않은 AND 로 "왜 아무도 못 들어오지?" 를 자주 만듭니다.

---

## 6. egress — 나가는 트래픽 통제 + DNS 함정 (`manifests/40-egress.yaml`)

### 6-1. egress default-deny 는 DNS 까지 막는다

```bash
kubectl apply -f - <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: frontend-egress-denyall, namespace: step15 }
spec:
  podSelector: { matchLabels: { role: frontend } }
  policyTypes: [Egress]
EOF
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server | grep -o "<title>.*</title>" || echo "BLOCKED (dns/egress denied)"
```
```
BLOCKED (dns/egress denied)
```
**이름(`http://server`)으로 접속조차 안 됩니다.** egress 를 다 막으면 53 포트(DNS)도 막혀 이름 해석이 실패하기 때문입니다.

### 6-2. DNS + 목적지만 허용

```yaml
spec:
  podSelector: { matchLabels: { role: frontend } }
  policyTypes: [Egress]
  egress:
    - to: []                                   # (1) 어디로든 DNS 허용
      ports: [ { protocol: UDP, port: 53 }, { protocol: TCP, port: 53 } ]
    - to:                                       # (2) server 파드의 80만
        - podSelector: { matchLabels: { app: server } }
      ports: [ { protocol: TCP, port: 80 } ]
```

```bash
kubectl delete networkpolicy frontend-egress-denyall -n step15
kubectl apply -f manifests/40-egress.yaml
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://server  | grep -o "<title>.*</title>" || echo "BLOCKED"
kubectl exec -n step15 client-allowed -- wget -qO- --timeout=4 http://1.1.1.1                              || echo "BLOCKED (1.1.1.1 not in allow)"
```
```
<title>Welcome to nginx!</title>   # server 는 허용
BLOCKED (1.1.1.1 not in allow)     # 허용 목록 밖 목적지는 차단
```

---

## 7. 되는 것 / 안 되는 것 (이 환경 실측 정리)

| 시나리오 | 결과 (kindnet v20260528) |
|---|---|
| 정책 없음, 파드↔파드 | 통신 (기본 open) — **강제됨 확인** |
| default-deny ingress | 전부 차단됨 ✅ |
| podSelector 화이트리스트 | 허용 파드만 통과 ✅ |
| namespaceSelector | trusted 네임스페이스만 통과 ✅ |
| egress default-deny | DNS 포함 전부 차단됨 ✅ |
| egress allow(DNS+대상) | 대상만 통과, 나머지 차단 ✅ |

**즉 이 환경에선 전부 정상 강제.** 다른 환경에서 안 된다면 그건 CNI 문제(개념/문법은 동일).

---

## 8. 팁 / 함정

- ⚠️ **CNI 가 강제 안 하면 정책은 장식.** 배포 전 반드시 default-deny 스모크 테스트로 강제 여부를 확인.(3절)
- ⚠️ **egress default-deny 는 DNS(53)를 함께 열어야 함.** 안 그러면 "이름 해석 실패" 로 원인을 엉뚱한 데서 찾게 됨.(6절)
- ⚠️ **podSelector + namespaceSelector 의 AND/OR.** 한 `from` 안에 두면 AND, 나누면 OR.(5절)
- ⚠️ **정책은 additive, "deny 규칙" 이 없다.** 특정 파드를 콕 집어 막으려면, default-deny 후 나머지를 화이트리스트로.
- ⚠️ **정책 방향(policyTypes) 을 안 적으면** ingress 규칙 유무로 자동 유추되지만, egress 는 `policyTypes: [Egress]` 를 명시하지 않으면 egress 를 통제하지 않음.
- 💡 정책이 대상 파드에 걸렸는지 헷갈리면 `kubectl describe networkpolicy` 의 `PodSelector` 와 실제 파드 레이블을 대조.
- 💡 트래픽 테스트는 **Service 이름/ClusterIP** 둘 다 시도. DNS 문제(egress)와 정책 문제를 구분할 수 있음.

---

## 9. 정리표

| 개념 | 필드 | 요약 |
|---|---|---|
| 대상 파드 | `spec.podSelector` | 이 정책이 적용될 파드 |
| 방향 | `policyTypes: [Ingress, Egress]` | 들어옴/나감 |
| 허용 출처 | `ingress[].from[]` | podSelector / namespaceSelector / ipBlock |
| 허용 목적지 | `egress[].to[]` | 위와 동일 |
| 포트 | `ports[]` | protocol + port |
| 기본 차단 | `podSelector: {}` + 규칙 없음 | 해당 방향 전부 차단 |

---

## 10. 연습 과제

`challenge.md` 참고.

---

## 11. 정리

```bash
kubectl delete namespace step15
kubectl delete namespace step15-ext
```

---

**다음 단계 →** [Step 16 — RBAC와 인증](../step-16-rbac/README.md)

---

## 실습 파일

이 스텝의 매니페스트는 **번호 순서가 곧 실행 순서**입니다. `00`(네임스페이스) → `10`(보호 대상 server + 클라이언트 2개)으로 무대를 만든 뒤, `20`(default-deny)으로 전부 닫고, 그 위에 `21`(podSelector 화이트리스트) → `30`(namespaceSelector) → `40`(egress + DNS)을 차례로 얹으며 "차단이 실제로 강제되는가"를 매 단계 확인합니다. `commands.sh` 는 이 전 과정을 기준선 측정부터 정리까지 한 번에 재현하는 스크립트입니다.

### manifests/00-namespace.yaml

실습 전용 네임스페이스 `step15` 를 만듭니다. NetworkPolicy 는 **네임스페이스 범위(namespaced) 리소스**이므로, 정책과 대상 파드가 같은 네임스페이스에 있어야 한다는 점이 이 파일이 존재하는 이유이기도 합니다(연습 과제 4의 "네임스페이스 착오" 오답 패턴). `labels` 의 `course: k8s-learn`, `step: "15"` 는 나중에 `kubectl get ns -l step=15` 처럼 골라내기 위한 표식이며, `step: "15"` 가 **따옴표로 감싼 문자열**인 것에 주의하세요 — 레이블 값은 반드시 문자열이어야 하므로 `15` 로 쓰면 YAML 파서가 숫자로 읽어 거부당합니다. 5절에서 쓰는 보조 네임스페이스 `step15-ext` 는 이 파일이 아니라 `commands.sh` 안에서 `kubectl create namespace` 로 즉석 생성합니다.

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/10-workloads.yaml

2절의 실습 워크로드로, 파일 하나에 Service + Deployment + Pod 2개가 `---` 로 이어져 있습니다.

- `Service/server` 는 `selector: { app: server }` 로 nginx 파드를 잡고 `port: 80 → targetPort: 80` 을 매핑합니다. 클라이언트가 `http://server` 라는 **이름**으로 접속할 수 있는 근거이고, 6절에서 egress 를 막았을 때 이 이름 해석이 먼저 깨지는 지점이기도 합니다.
- `Deployment/server` 는 `nginx:1.27-alpine` 1개 레플리카. 응답의 `<title>Welcome to nginx!</title>` 가 바로 실습 전 과정의 "통과" 판정 기준입니다.
- `client-allowed` 는 `role: frontend`, `client-denied` 는 `role: other` 레이블을 답니다. 두 파드는 이미지도 명령도 완전히 같고 **오직 `role` 레이블 값 하나만 다릅니다.** 그 값 하나가 21(ingress 허용)·40(egress 대상) 정책의 통과/차단을 가르는 유일한 근거입니다. 반면 두 파드 모두 `app: client` 를 공유하므로, 셀렉터를 `app` 으로 잡으면 둘 다 걸린다는 점도 대조해 보세요.
- 클라이언트는 `busybox:1.36` 에 `command: ["sh","-c","sleep 3600"]` 로 1시간만 살아 있습니다. 실습이 길어져 파드가 `Completed` 로 끝나면 `kubectl exec` 가 실패하니, 그때는 다시 `apply` 하면 됩니다.

```yaml file="./manifests/10-workloads.yaml"
```

### manifests/20-default-deny.yaml

3-2절의 **강제 여부 스모크 테스트** 에 쓰는 기본 차단 정책입니다. 핵심은 단 두 줄로, `podSelector: {}` 는 "이 네임스페이스의 **모든** 파드"를 뜻하고(빈 셀렉터는 전체 선택), `policyTypes: [Ingress]` 만 적고 **`ingress:` 규칙을 하나도 두지 않아** 들어오는 트래픽이 전부 닫힙니다. NetworkPolicy 에는 "deny 규칙"이 없고 허용 규칙의 합집합만 있으므로, "아무것도 허용하지 않음 = 전부 차단"이 곧 default-deny 구현법입니다. 이 정책을 적용한 뒤 `client-allowed` / `client-denied` 둘 다 `BLOCKED` 가 나와야 여러분의 CNI 가 정책을 실제로 강제하는 것이고, 여기서 `<title>` 이 그대로 나온다면 0절 경고대로 정책이 장식일 뿐인 환경입니다. 이후 21/30 정책은 **이 파일이 깔려 있는 상태를 전제**로 구멍을 뚫는 것이므로, 이 정책을 지우면 화이트리스트 실습 전체가 무의미해집니다.

```yaml file="./manifests/20-default-deny.yaml"
```

### manifests/21-allow-frontend.yaml

4절의 podSelector 화이트리스트입니다. `spec.podSelector.matchLabels.app: server` 로 **보호 대상**(정책이 걸릴 파드)을 지정하고, `ingress[0].from[0].podSelector.matchLabels.role: frontend` 로 **허용 출처**를 지정합니다. 이 두 셀렉터의 역할이 다르다는 점이 초심자가 가장 많이 헷갈리는 부분이니, `kubectl describe networkpolicy allow-frontend-to-server -n step15` 출력의 `PodSelector: app=server` 와 `From: PodSelector: role=frontend` 를 반드시 대조해 보세요. `ports: [{ protocol: TCP, port: 80 }]` 때문에 허용은 **80/TCP 에 한정**되어, `role=frontend` 파드라도 다른 포트로는 못 들어옵니다. `from` 의 `podSelector` 는 네임스페이스 한정자가 없으므로 **같은 네임스페이스(step15) 안의 파드만** 의미합니다 — 그래서 5절에서 외부 네임스페이스를 허용하려면 이 정책이 아니라 `namespaceSelector` 가 필요합니다. 20-default-deny 와 합집합으로 동작해 `client-allowed` 만 통과하고 `client-denied` 는 계속 막힙니다.

```yaml file="./manifests/21-allow-frontend.yaml"
```

### manifests/30-allow-namespace.yaml

5절에서 쓰며, 허용 출처를 파드가 아니라 **네임스페이스** 로 지정합니다. `from[0].namespaceSelector.matchLabels.team: trusted` 는 "`team=trusted` 레이블이 붙은 네임스페이스에서 오는 트래픽"을 뜻하고, `commands.sh` 가 `kubectl label namespace step15-ext team=trusted` 로 그 레이블을 붙여 주기 때문에 `step15-ext/ext-client` 만 통과합니다. 반대로 정책이 `podSelector` 허용을 전혀 갖고 있지 않으므로, **같은 step15 안의 `client-allowed` 는 오히려 차단**됩니다(`step15` 네임스페이스에는 `team=trusted` 가 없으니까요) — "안에 있는데 왜 막히지?" 를 몸으로 겪는 대목입니다. 실습 절차상 이 정책을 적용하기 **전에** `kubectl delete networkpolicy allow-frontend-to-server` 로 21 정책을 지워야 하는데, 정책은 합집합이라 둘을 함께 두면 양쪽 다 통과해 버려 대비 효과가 사라지기 때문입니다. 참고로 파일 상단 주석은 podSelector 와 namespaceSelector 를 한 `from` 항목에 나란히 두면 AND 가 된다는 함정을 설명하는데, **실제 이 파일의 `from` 에는 `namespaceSelector` 하나뿐**입니다. AND 형태의 완성본은 `challenge.md` 과제 3의 해답에 있습니다.

```yaml file="./manifests/30-allow-namespace.yaml"
```

### manifests/40-egress.yaml

6-2절의 egress 정책으로, 이번엔 통제 방향이 반대입니다. `podSelector: { role: frontend }` + `policyTypes: [Egress]` 로 `client-allowed` 가 **나가는** 트래픽을 통제하며, 허용은 두 덩어리입니다.

- **(1) `to: []` + `ports: 53/UDP, 53/TCP`** — 목적지 무제한으로 DNS 만 열어 줍니다. 6-1절에서 규칙 없는 egress default-deny 를 걸었을 때 `http://server` 라는 **이름조차 해석되지 않아** 접속이 실패했던 이유가 바로 이 53 포트가 닫혔기 때문이고, 이 블록이 그 함정을 푸는 열쇠입니다. `to: []` 는 "목적지 제한 없음"이지 "아무 데도 안 됨"이 아닙니다.
- **(2) `to: [{ podSelector: { app: server } }]` + `ports: 80/TCP`** — 실제 목적지 허용. `server` 파드의 80 으로만 나갈 수 있습니다.

두 항목이 별개의 `-` 리스트 항목이라 **OR(합집합)** 로 동작합니다. 그래서 `http://server` 는 통과하지만 허용 목록에 없는 `http://1.1.1.1` 은 차단되어 실습 출력에 `BLOCKED (1.1.1.1 not in allow)` 가 찍힙니다. 이 정책은 **ingress 를 전혀 통제하지 않으므로**(`policyTypes` 에 Ingress 없음), 4절의 21 정책과 함께 적용해도 서로 간섭하지 않습니다.

```yaml file="./manifests/40-egress.yaml"
```

### commands.sh

0절의 CNI 버전 확인부터 7절 정리까지, 강의 본문의 모든 검증을 순서대로 재현하는 스크립트입니다.

- `set -euo pipefail` 로 엄격 모드를 켜지만 `kubectl exec ... || echo BLOCKED` 패턴 덕에 **차단(=명령 실패)은 스크립트를 죽이지 않고 `BLOCKED` 로 출력**됩니다. `wget --timeout=4` 가 4초 후 실패하는 것이 곧 "막혔다"는 증거입니다.
- 맨 앞의 `export PATH="/opt/homebrew/bin:$PATH"` 는 **macOS(Homebrew) 환경에서 `kubectl` 을 찾기 위한 줄**입니다. 리눅스나 다른 경로에 `kubectl` 을 설치했다면 이 줄은 없어도 그만이니, 그대로 두거나 자신의 환경에 맞게 바꿔도 됩니다.
- `cd "$(dirname "$0")"` 로 스크립트 위치로 이동하므로 `manifests/...` 상대경로가 어디서 실행하든 동작합니다.
- 정책을 `apply` 할 때마다 `sleep 5` 를 넣는데, CNI 가 정책을 nftables 규칙으로 실제 반영하는 데 시간이 걸리기 때문입니다. 이걸 빼면 아직 반영 전의 트래픽이 통과해 "강제 안 되는 CNI" 로 오진할 수 있습니다.
- 5절 구간에서 `kubectl delete networkpolicy allow-frontend-to-server` 로 21 정책을 먼저 지우고 30 을 적용하며, 6절 진입 시엔 반대로 30(`allow-trusted-namespace`)을 지우고 21 을 되살립니다. **정책 합집합 때문에 이 delete 순서가 결과를 좌우**하니 임의로 건너뛰지 마세요.
- 6-1절의 egress default-deny 는 매니페스트 파일이 아니라 `kubectl apply -f - <<'EOF'` 힙독으로 즉석 적용됩니다(규칙 없는 `policyTypes: [Egress]`). DNS 함정을 눈으로 본 뒤 바로 삭제하고 `40-egress.yaml` 로 넘어갑니다.
- ⚠️ **마지막 7절은 `kubectl delete namespace step15` / `step15-ext` 로 실습 리소스를 통째로 지웁니다.** 스크립트를 끝까지 돌리면 관찰할 대상이 남지 않으니, 단계별로 확인하고 싶다면 해당 구간을 잘라 실행하거나 중간에 중단하세요. 또한 `kubectl` 이 kind 클러스터를 가리키고 있는지(`kubectl config current-context`) 먼저 확인해야 합니다 — 운영 클러스터에서 실행하면 안 됩니다.

```bash file="./commands.sh"
```
