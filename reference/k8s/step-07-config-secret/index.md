# Step 07 — 설정과 시크릿

> **학습 목표**
> - 설정을 이미지에서 분리하는 이유와 ConfigMap 을 이해한다
> - ConfigMap/Secret 을 `env`·`envFrom`·볼륨 마운트 세 방식으로 주입한다
> - **Secret 의 base64 는 암호화가 아니다** 를 직접 디코딩해 확인한다
> - immutable ConfigMap/Secret 의 의미와 쓸모를 안다
> - **설정을 바꿔도 실행 중 파드에 자동 반영되지 않는** 함정을 재현하고 해결한다
>
> **선행 스텝**: [Step 06 — 서비스와 DNS](../step-06-services/index.md)
> **예상 소요**: 50분

---

## 7-1. 왜 ConfigMap 인가 — 설정을 이미지에서 떼어내기

같은 애플리케이션 이미지를 개발·스테이징·프로덕션에서 씁니다. 다른 건 설정뿐입니다(DB 주소, 로그 레벨, 기능 플래그…). 이걸 이미지 안에 넣어버리면 환경마다 이미지를 새로 빌드해야 하고, 설정 하나 바꾸려고 배포 파이프라인을 다시 도는 낭비가 생깁니다.

**ConfigMap** 은 민감하지 않은 설정을 클러스터에 키-값으로 저장하는 오브젝트입니다. 이미지는 그대로 두고, 환경에 맞는 ConfigMap 만 갈아끼웁니다. 이것이 "설정과 코드의 분리"입니다.

```yaml
# manifests/config.yaml (ConfigMap 발췌)
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: step07
data:
  APP_COLOR: "blue"                 # 단순 키-값 → env 주입에 적합
  APP_MODE: "production"
  GREETING: "hello from configmap"
  app.properties: |                 # 파일 통째로 → 볼륨 마운트에 적합
    color=blue
    mode=production
    retries=3
```

```bash
kubectl apply -f manifests/config.yaml
kubectl get configmap app-config -n step07
```
```
NAME         DATA   AGE
app-config   4      1s
```

> 💡 **실무 팁**: 명령형으로도 만들 수 있습니다.
> `kubectl create configmap demo --from-literal=KEY1=val1 --from-file=app.properties`
> 파일에서 통째로 만들 땐 `--from-file` 을 씁니다. Git 관리 대상이라면 위처럼 YAML 로 선언하는 편이 추적에 좋습니다.

---

## 7-2. Secret — 민감 정보 담기

비밀번호·토큰·인증서는 ConfigMap 대신 **Secret** 에 넣습니다. 구조는 비슷하지만 별도 타입으로 다뤄져 RBAC·감사·마운트 방식에서 다르게 취급됩니다.

```yaml
# manifests/config.yaml (Secret 발췌)
apiVersion: v1
kind: Secret
metadata:
  name: db-secret
  namespace: step07
type: Opaque
stringData:            # 평문을 넣으면 Kubernetes 가 base64 로 저장해 줌
  DB_USER: "admin"
  DB_PASSWORD: "S3cr3t-p@ss"
```

`stringData` 에 평문을 쓰면 편합니다(직접 base64 인코딩할 필요 없음). `data` 필드에 직접 넣을 땐 base64 로 인코딩된 값을 넣어야 합니다.

```bash
kubectl get secret db-secret -n step07
```
```
NAME        TYPE     DATA   AGE
db-secret   Opaque   2      1s
```

---

## 7-3. 주입 방법 3가지 — env / envFrom / 볼륨

한 Deployment 에서 세 방식을 모두 보여줍니다.

```yaml
# manifests/consumer.yaml (발췌)
env:
  - name: APP_COLOR                 # (1) ConfigMap 의 특정 키 하나만
    valueFrom:
      configMapKeyRef: { name: app-config, key: APP_COLOR }
  - name: DB_PASSWORD               #     Secret 의 특정 키 하나만
    valueFrom:
      secretKeyRef: { name: db-secret, key: DB_PASSWORD }
envFrom:
  - configMapRef: { name: app-config }   # (2) ConfigMap 전체 키를 한꺼번에
  - secretRef:    { name: db-secret }
volumeMounts:
  - { name: config-vol, mountPath: /etc/config, readOnly: true }   # (3) 파일로
  - { name: secret-vol, mountPath: /etc/secret, readOnly: true }
```

```bash
kubectl apply -f manifests/consumer.yaml
kubectl rollout status deployment/app -n step07
POD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
```

**(1)(2) 환경변수 확인**

```bash
kubectl exec -n step07 $POD -- sh -c 'echo $APP_COLOR; echo $GREETING; echo $DB_USER; echo $DB_PASSWORD'
```
```
APP_COLOR=blue
APP_MODE=production
GREETING=hello from configmap
DB_USER=admin
DB_PASSWORD=S3cr3t-p@ss
```

`envFrom` 덕분에 ConfigMap 의 모든 단순 키(`APP_MODE`, `GREETING` …)와 Secret 의 키(`DB_USER`)가 전부 환경변수가 되었습니다.

**(3) 볼륨 마운트 확인** — 각 키가 파일 하나로 나타납니다.

```bash
kubectl exec -n step07 $POD -- ls -l /etc/config
kubectl exec -n step07 $POD -- cat /etc/config/app.properties
```
```
lrwxrwxrwx  APP_COLOR -> ..data/APP_COLOR
lrwxrwxrwx  APP_MODE -> ..data/APP_MODE
lrwxrwxrwx  GREETING -> ..data/GREETING
lrwxrwxrwx  app.properties -> ..data/app.properties

color=blue
mode=production
retries=3
```

Secret 도 똑같이 파일로 마운트됩니다.

```bash
kubectl exec -n step07 $POD -- cat /etc/secret/DB_PASSWORD
```
```
S3cr3t-p@ss
```

> 💡 **실무 팁**: `envFrom` 은 편하지만 어떤 키가 환경변수로 들어오는지 한눈에 안 보입니다. 키 이름이 애플리케이션이 기대하는 것과 겹치거나 예약어(`PATH` 등)와 충돌하면 문제가 됩니다. **주입되는 키를 명확히 알고 싶으면 `env` 로 하나씩** 지정하세요. 파일이 자주 바뀌거나 인증서처럼 통째로 필요하면 **볼륨**이 낫습니다.

---

## 7-4. ⚠️ 함정: Secret 의 base64 는 암호화가 아니다

Secret 은 "안전해 보이지만" 기본 저장 형태는 그냥 **base64 인코딩**입니다. 인코딩은 암호화가 아닙니다. 누구나 즉시 되돌립니다.

```bash
kubectl get secret db-secret -n step07 -o jsonpath='{.data.DB_PASSWORD}'; echo
kubectl get secret db-secret -n step07 -o jsonpath='{.data.DB_PASSWORD}' | base64 -d; echo
```
```
UzNjcjN0LXBAc3M=        <- 저장된 값 (base64)
S3cr3t-p@ss             <- base64 -d 한 방에 평문
```

즉 Secret 을 읽을 권한(RBAC)이 있는 사람은 평문을 그대로 봅니다. base64 는 바이너리/특수문자를 안전하게 담기 위한 것이지 보안 장치가 아닙니다.

> ⚠️ **함정**: "Secret 에 넣었으니 안전하다"는 착각이 가장 위험합니다. 진짜로 보호하려면:
> - **etcd 저장 암호화**(EncryptionConfiguration)를 켠다
> - Secret 접근을 **RBAC 으로 최소화**한다(Step 16)
> - Secret 을 **Git 에 커밋하지 않는다**(SealedSecrets, External Secrets, Vault 등 사용)
> ConfigMap 과 Secret 을 나누는 진짜 이유는 "암호화"가 아니라 **접근 제어와 취급 정책을 분리**하기 위함입니다.

---

## 7-5. immutable — 잠긴 설정

`immutable: true` 로 만든 ConfigMap/Secret 은 생성 후 `data` 를 **절대 못 바꿉니다**. 바꾸려면 삭제하고 새로 만들어야 합니다.

```yaml
# manifests/immutable.yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: locked-config, namespace: step07 }
immutable: true
data:
  VERSION: "1.0.0"
```

```bash
kubectl apply -f manifests/immutable.yaml
kubectl patch configmap locked-config -n step07 -p '{"data":{"VERSION":"2.0.0"}}'
```
```
The ConfigMap "locked-config" is invalid: data: Forbidden:
  field is immutable when `immutable` is set
```

이점은 둘입니다. ① **실수로 프로덕션 설정을 바꾸는 사고를 막고**, ② kubelet 이 변경 감시를 안 해도 되어 **대규모 클러스터에서 성능**이 좋아집니다.

> 💡 **실무 팁**: 버전이 박힌 설정(`app-config-v1`, `app-config-v2` 처럼 이름에 버전을 넣고 immutable 로)을 쓰면, 새 설정은 새 오브젝트로 만들고 Deployment 가 그걸 참조하도록 바꿉니다. 그러면 설정 변경이 곧 롤아웃이 되어 롤백까지 자연스럽습니다.

---

## 7-6. ⚠️ 함정 (가장 중요): 설정을 바꿔도 파드에 자동 반영 안 된다

ConfigMap 값을 바꿔봅시다. `APP_COLOR` 를 `blue` → `green` 으로.

```bash
kubectl patch configmap app-config -n step07 -p '{"data":{"APP_COLOR":"green"}}'
```

실행 중인 파드의 **환경변수를 확인하면 여전히 옛날 값**입니다.

```bash
kubectl exec -n step07 $POD -- sh -c 'echo APP_COLOR=$APP_COLOR'
```
```
APP_COLOR=blue        <- 바꿨는데도 그대로!
```

**왜?** 환경변수는 **컨테이너가 시작하는 순간 딱 한 번** 주입되고 그 뒤엔 고정됩니다. ConfigMap 을 나중에 바꿔도 이미 떠 있는 프로세스의 환경변수는 바뀌지 않습니다.

흥미로운 건 **볼륨 마운트**입니다. 볼륨으로 마운트한 파일은 잠시(수십 초) 뒤 새 값으로 갱신됩니다.

```bash
# 약 1분 뒤
kubectl exec -n step07 $POD -- cat /etc/config/APP_COLOR      # green (갱신됨)
kubectl exec -n step07 $POD -- sh -c 'echo APP_COLOR=$APP_COLOR'   # blue (여전히 stale)
```
```
green                 <- 볼륨 파일은 반영됨
APP_COLOR=blue        <- 환경변수는 여전히 옛날 값
```

정리하면:

| 주입 방식 | ConfigMap 변경 시 |
|---|---|
| `env` / `envFrom` (환경변수) | **절대 자동 반영 안 됨** (파드 재시작 필요) |
| 볼륨 마운트 (파일) | 수십 초 내 파일은 갱신됨. 단, **앱이 파일을 다시 읽어야** 실제 반영 |

**해결책**: 파드를 새로 뜨게 해서 설정을 다시 읽게 합니다. `rollout restart` 가 정석입니다.

```bash
kubectl rollout restart deployment/app -n step07
kubectl rollout status deployment/app -n step07
NEWPOD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
kubectl exec -n step07 $NEWPOD -- sh -c 'echo APP_COLOR=$APP_COLOR'
```
```
deployment.apps/app restarted
deployment "app" successfully rolled out
APP_COLOR=green       <- 새 파드는 새 값 반영
```

> ⚠️ **함정 정리**: "ConfigMap 을 바꿨는데 앱이 예전처럼 동작한다"는 신고의 십중팔구가 이것입니다. 설정 변경 후에는 **반드시 `kubectl rollout restart deployment/<name>`** 을 하세요. 볼륨 마운트조차도 앱이 파일 변경을 감지해 재로딩하도록 만들어져 있지 않으면 소용없습니다. 안전하게 가려면 설정 변경 = 롤아웃으로 취급하세요.

---

## 정리

| 개념 | 한 줄 요약 |
|---|---|
| ConfigMap | 민감하지 않은 설정을 코드에서 분리 |
| Secret | 민감 정보. **base64 는 암호화 아님** |
| env / envFrom | 환경변수 주입. 변경 자동 반영 **안 됨** |
| 볼륨 마운트 | 파일 주입. 파일은 갱신되나 앱 재로딩 필요 |
| immutable | 변경 잠금 + 성능 이점 |
| 설정 변경 반영 | `kubectl rollout restart` 필수 |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 08 — 헬스 체크](../step-08-health-probes/index.md)

---

## 실습 파일

이 스텝은 매니페스트 3개와 실행 스크립트 1개로 구성됩니다. 먼저 `manifests/config.yaml` 로 네임스페이스·ConfigMap·Secret 이라는 "설정 원본"을 만들고, `manifests/consumer.yaml` 로 그 설정을 세 가지 방식(env / envFrom / 볼륨)으로 소비하는 Deployment 를 띄웁니다. 그 다음 `manifests/immutable.yaml` 로 잠긴 설정을 실험하고, 마지막으로 `commands.sh` 가 이 전체 흐름을 7-1부터 7-6까지 순서대로 재현합니다.

### manifests/config.yaml

7-1과 7-2에서 적용하는 **설정의 원본** 파일입니다. 하나의 YAML 안에 `---` 로 구분된 세 오브젝트(Namespace `step07`, ConfigMap `app-config`, Secret `db-secret`)가 들어 있어 `kubectl apply -f manifests/config.yaml` 한 번으로 실습 환경이 준비됩니다. 네임스페이스가 맨 앞에 오는 순서가 중요합니다 — 뒤의 두 오브젝트가 `namespace: step07` 을 참조하기 때문입니다.

- ConfigMap 의 `data` 는 의도적으로 **두 종류**를 섞어 놨습니다. `APP_COLOR: "blue"`, `APP_MODE: "production"`, `GREETING: "hello from configmap"` 은 단순 키-값이라 환경변수 주입에 적합하고, `app.properties: |` 는 여러 줄 블록(`color=blue` / `mode=production` / `retries=3`)이라 볼륨 마운트했을 때 파일 하나로 떨어집니다. 이 4개 키가 곧 `kubectl get configmap` 출력의 `DATA 4` 입니다.
- Secret `db-secret` 은 `type: Opaque` 에 `data` 가 아니라 **`stringData`** 를 씁니다. `DB_USER: "admin"`, `DB_PASSWORD: "S3cr3t-p@ss"` 처럼 평문으로 적으면 Kubernetes 가 저장 시점에 알아서 base64 로 인코딩해 줍니다. 직접 `data` 에 넣었다면 `S3cr3t-p@ss` 를 손으로 base64 인코딩해야 했습니다.
- 여기 적힌 평문 비밀번호가 바로 7-4 함정의 실험 재료입니다. `kubectl get secret ... -o jsonpath='{.data.DB_PASSWORD}'` 로 꺼내면 `UzNjcjN0LXBAc3M=` 이 나오고, `base64 -d` 한 방에 원래의 `S3cr3t-p@ss` 로 돌아옵니다.
- **주의**: 이 파일은 학습용이라 비밀번호를 그대로 커밋해 두었습니다. 실무에서는 이런 Secret YAML 을 절대 Git 에 올리면 안 됩니다(SealedSecrets, External Secrets, Vault 등을 사용).

```yaml file="./manifests/config.yaml"
```

### manifests/consumer.yaml

7-3에서 적용하는 **소비자 쪽** Deployment 입니다. `config.yaml` 을 먼저 적용해야 합니다 — 네임스페이스 `step07` 이 없으면 `kubectl apply` 자체가 `namespaces "step07" not found` 로 실패하고, 네임스페이스는 있는데 `app-config`/`db-secret` 이 없으면 파드가 `CreateContainerConfigError` 상태에 머뭅니다. 컨테이너는 `busybox:1.36` 에 `command: ["sh", "-c", "sleep 3600"]` 이라 아무 일도 하지 않고 대기만 합니다 — 목적이 "설정이 컨테이너 안에 어떻게 들어오는지"를 `kubectl exec` 로 들여다보는 것이기 때문입니다.

- **(1) `env`**: `APP_COLOR` 는 `configMapKeyRef` 로 `app-config` 의 `APP_COLOR` 키 하나만, `DB_PASSWORD` 는 `secretKeyRef` 로 `db-secret` 의 `DB_PASSWORD` 키 하나만 콕 집어 가져옵니다. 어떤 키가 들어오는지 매니페스트만 보고 알 수 있는 방식입니다.
- **(2) `envFrom`**: `configMapRef: app-config` 와 `secretRef: db-secret` 을 통째로 걸어, ConfigMap 의 단순 키 전부(`APP_MODE`, `GREETING` …)와 Secret 의 `DB_USER` 까지 한꺼번에 환경변수가 됩니다. `kubectl exec ... echo $GREETING` 이 값을 내놓는 건 이 `envFrom` 덕분입니다.
- **(3) 볼륨**: `volumes` 의 `config-vol`(`configMap: app-config`)과 `secret-vol`(`secret: secretName: db-secret`)을 각각 `/etc/config`, `/etc/secret` 에 `readOnly: true` 로 마운트합니다. 키 하나가 파일 하나가 되므로 `/etc/config/app.properties` 를 `cat` 하면 세 줄짜리 프로퍼티 파일이 그대로 나옵니다.
- 같은 `APP_COLOR` 가 `env`(방식 1)로도, `envFrom`(방식 2)으로도, 볼륨 파일(방식 3)로도 들어온다는 점이 7-6 함정의 핵심 장치입니다. ConfigMap 을 `green` 으로 바꿨을 때 **볼륨 파일만 갱신되고 환경변수는 `blue` 로 멈춰 있는** 대비를 한 파드 안에서 동시에 관찰할 수 있습니다.
- `replicas: 1` 이므로 `rollout restart` 후 파드 이름이 바뀝니다. 스크립트가 `POD` 와 `NEWPOD` 를 따로 잡는 이유입니다.

```yaml file="./manifests/consumer.yaml"
```

### manifests/immutable.yaml

7-5에서 적용하는 **잠긴 설정** 실습 파일입니다. ConfigMap `locked-config` 에 `immutable: true` 한 줄이 붙어 있고 `data` 에는 `VERSION: "1.0.0"` 하나만 있습니다.

- `kubectl apply` 로 생성한 직후 `kubectl patch configmap locked-config -n step07 -p '{"data":{"VERSION":"2.0.0"}}'` 을 실행하면 API 서버가 `field is immutable when 'immutable' is set` 로 **거부**합니다. 이 실패를 눈으로 보는 것이 학습 포인트입니다.
- 즉 이 파일은 "값을 바꿔 보려다 막히는" 경험을 만들기 위한 것이며, 값을 바꾸려면 오브젝트를 삭제하고 재생성하는 수밖에 없습니다(연습 과제 5번).
- 실무 패턴은 `locked-config` 를 고치는 게 아니라 `app-config-v2` 처럼 **이름에 버전을 박은 새 오브젝트**를 만들고 Deployment 참조를 바꾸는 것입니다. 그러면 설정 변경이 곧 롤아웃이 되어 롤백까지 자연스러워집니다.
- 부수 효과로 kubelet 이 이 오브젝트의 변경을 감시(watch)하지 않아도 되므로 대규모 클러스터에서 API 서버 부하가 줄어듭니다.

```yaml file="./manifests/immutable.yaml"
```

### commands.sh

강의 본문 7-1부터 7-6까지를 **그대로 재현하는 실행 스크립트**입니다. 통째로 실행해도 되지만, 각 명령의 출력을 관찰하는 것이 목적이므로 위에서부터 한 줄씩 복사해 붙여 넣으며 진행하길 권합니다.

- 맨 앞의 `kubectl config current-context` 로 컨텍스트가 `kind-learn` 인지 먼저 확인합니다. 다른 클러스터(특히 실제 운영 클러스터)에 붙어 있으면 마지막 줄의 네임스페이스 삭제가 위험하므로 반드시 짚고 넘어가야 합니다.
- `POD=$(kubectl get pod -n step07 -l app=app -o name | head -1)` 로 파드 이름을 변수에 담아 이후 `kubectl exec` 에 재사용합니다. 이 변수는 셸 세션에 남으므로 한 줄씩 실행할 때도 같은 터미널을 유지해야 합니다.
- 7-6 구간의 **`sleep 60`** 이 이 스크립트의 백미입니다. ConfigMap 을 `green` 으로 패치한 직후에는 볼륨 파일도 아직 옛 값일 수 있어서, kubelet 이 볼륨을 동기화할 시간을 벌어 주는 대기입니다. 60초 뒤 `cat /etc/config/APP_COLOR` 는 `green` 인데 `echo $APP_COLOR` 는 여전히 `blue` — 이 두 줄의 대비가 이 스텝 전체의 결론입니다.
- 그 뒤 `kubectl rollout restart deployment/app` → `rollout status` → `NEWPOD` 재조회 순서로, **파드를 새로 띄워야만** 환경변수가 `green` 이 된다는 해결책을 확인합니다.
- **주의**: 마지막 줄 `kubectl delete namespace step07` 은 이 스텝에서 만든 모든 리소스를 지우는 **파괴적 명령**입니다. 연습 과제(challenge.md)를 아직 안 풀었다면 이 줄은 건너뛰세요.
- `export PATH="/opt/homebrew/bin:$PATH"` 는 Apple Silicon macOS + Homebrew 환경 전제입니다. 다른 OS 라면 이 줄은 무시해도 됩니다.

```bash file="./commands.sh"
```
