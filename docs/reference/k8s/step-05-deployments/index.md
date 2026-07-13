# Step 05 — 디플로이먼트

> **학습 목표**
> - Deployment 가 ReplicaSet·Pod 를 어떻게 계층적으로 관리하는지 이해한다
> - 스케일(scale)과 롤링 업데이트(rolling update)를 직접 해본다
> - `maxSurge`/`maxUnavailable` 이 업데이트 중 가용성을 어떻게 지키는지 관찰한다
> - `rollout status/history/undo` 로 배포를 추적하고 **롤백**한다
> - 잘못된 이미지로 배포하면 롤아웃이 **멈추는** 것을, 그리고 셀렉터 불변 규칙을 재현한다
>
> **선행 스텝**: [Step 04 — 레이블·셀렉터·네임스페이스](../step-04-labels-namespaces/index.md)
> **예상 소요**: 50분

---

## 5-1. 왜 Deployment 인가 — Pod 를 직접 만들지 않는 이유

Step 03 에서 만든 Pod 는 죽으면 끝입니다. 아무도 되살려주지 않습니다. 노드가 빠지거나 파드가 크래시하면 그냥 사라집니다. 개수를 4개로 유지하고 싶어도, 버전을 무중단으로 바꾸고 싶어도 손으로는 못 합니다.

**Deployment** 는 이 문제를 3단 계층으로 풉니다.

```
Deployment  (원하는 상태 선언: "web 을 4개, 이 이미지로")
   └── ReplicaSet  (개수 보장: "이 템플릿의 파드를 정확히 4개")
          └── Pod, Pod, Pod, Pod  (실제 실행 단위)
```

- **ReplicaSet** 은 "특정 파드 템플릿을 N개 유지"만 담당합니다. 하나 죽으면 즉시 새로 만듭니다.
- **Deployment** 는 ReplicaSet 을 여러 벌 두고 갈아끼우면서 **버전 전환(롤아웃/롤백)** 을 담당합니다.

실무에서 ReplicaSet 을 직접 만드는 일은 거의 없습니다. **항상 Deployment 를 만들고, Deployment 가 ReplicaSet 을 대신 만들게** 합니다.

```yaml
# manifests/deployment.yaml (발췌)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: step05
spec:
  replicas: 4
  selector:
    matchLabels:
      app: web            # 이 Deployment 가 소유할 파드 조건
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
  template:
    metadata:
      labels:
        app: web          # selector 와 반드시 일치
    spec:
      containers:
        - name: nginx
          image: nginx:1.27-alpine
          readinessProbe:   # 준비된 파드만 "available" 로 카운트
            httpGet: { path: /, port: 80 }
            initialDelaySeconds: 1
            periodSeconds: 3
```

```bash
kubectl apply -f manifests/deployment.yaml
kubectl rollout status deployment/web -n step05
kubectl get deploy,rs,pod -n step05
```

**실행 결과**
```
deployment "web" successfully rolled out

NAME                  READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/web   4/4     4            4           2s

NAME                             DESIRED   CURRENT   READY   AGE
replicaset.apps/web-7b6659848b   4         4         4       1s

NAME                       READY   STATUS    RESTARTS   AGE
pod/web-7b6659848b-dc5vd   1/1     Running   0          1s
pod/web-7b6659848b-fw9vv   1/1     Running   0          1s
pod/web-7b6659848b-lv6rx   1/1     Running   0          1s
pod/web-7b6659848b-rsg97   1/1     Running   0          1s
```

내가 만든 건 Deployment 하나인데 ReplicaSet 하나와 Pod 4개가 딸려 나왔습니다. ReplicaSet 이름의 해시(`7b6659848b`)는 파드 템플릿의 해시이고, 파드에도 `pod-template-hash` 레이블로 붙습니다.

```bash
kubectl get pods -n step05 --show-labels
```
```
NAME                   READY   STATUS    RESTARTS   AGE   LABELS
web-7b6659848b-dc5vd   1/1     Running   0          1s    app=web,pod-template-hash=7b6659848b
...
```

> 💡 **실무 팁**: `pod-template-hash` 는 Kubernetes 가 자동으로 붙입니다. 이 레이블 덕분에 "구버전 파드"와 "신버전 파드"를 서로 다른 ReplicaSet 으로 구분할 수 있고, 롤링 업데이트가 가능해집니다. 여러분이 직접 이 레이블을 건드리면 안 됩니다.

자가 치유를 확인하려면 파드 하나를 지워보세요. 곧바로 새 파드가 생겨 다시 4개가 됩니다.

```bash
kubectl delete pod -n step05 -l app=web --field-selector=status.phase=Running | head -1
kubectl get pods -n step05      # 잠시 뒤 다시 4개
```

---

## 5-2. 스케일 — 개수 바꾸기

```bash
kubectl scale deployment/web -n step05 --replicas=5
kubectl rollout status deployment/web -n step05
kubectl get deploy web -n step05
```
```
deployment "web" successfully rolled out
NAME   READY   UP-TO-DATE   AVAILABLE   AGE
web    5/5     5            5           18s
```

`replicas` 만 바꾸는 건 **롤아웃이 아닙니다**(파드 템플릿이 그대로라 새 ReplicaSet 이 안 생깁니다). 같은 ReplicaSet 에 파드만 늘거나 줄 뿐입니다. 다시 4개로 줄여 둡니다.

```bash
kubectl scale deployment/web -n step05 --replicas=4
```

> 💡 **실무 팁**: 명령형 `kubectl scale` 은 편하지만, 매니페스트의 `replicas` 와 달라지면 다음 `apply` 때 되돌아갑니다. Git 으로 관리한다면 YAML 의 `replicas` 를 고쳐 `apply` 하는 게 일관적입니다. (HPA 를 쓰면 `replicas` 는 아예 HPA 가 관리 — Step 18)

---

## 5-3. 롤링 업데이트 — 무중단 버전 전환

파드 템플릿을 바꾸면(이미지·env·리소스 등) Deployment 는 **새 ReplicaSet 을 만들어 서서히 교체**합니다. 여기서는 환경 변수 하나를 추가해 롤아웃을 트리거합니다(이미지 태그를 바꾸는 것과 원리가 같습니다).

```bash
kubectl set env deployment/web -n step05 APP_VERSION=v2
kubectl annotate deployment/web -n step05 \
  kubernetes.io/change-cause="set APP_VERSION=v2" --overwrite
kubectl rollout status deployment/web -n step05
```

**실행 결과** (진행 로그, 핵심만)
```
Waiting for deployment "web" rollout to finish: 2 out of 4 new replicas have been updated...
Waiting for deployment "web" rollout to finish: 3 out of 4 new replicas have been updated...
Waiting for deployment "web" rollout to finish: 1 old replicas are pending termination...
Waiting for deployment "web" rollout to finish: 3 of 4 updated replicas are available...
deployment "web" successfully rolled out
```

이제 ReplicaSet 이 **두 개**입니다. 구버전은 0개로 줄고 신버전이 4개를 차지합니다.

```bash
kubectl get rs -n step05
```
```
NAME             DESIRED   CURRENT   READY   AGE
web-7b6659848b   0         0         0       42s    <- 구버전 (비워짐, 이력용으로 남음)
web-7d86f6d776   4         4         4       8s     <- 신버전
```

**`maxSurge`/`maxUnavailable` 의 의미** (우리 설정: 둘 다 1, replicas=4)

| 파라미터 | 뜻 | 이 설정의 효과 |
|---|---|---|
| `maxSurge: 1` | 목표 개수 대비 **초과 생성** 허용치 | 일시적으로 최대 5개까지 |
| `maxUnavailable: 1` | 업데이트 중 **부족 허용치** | 항상 최소 3개는 available 유지 |

즉 롤링 업데이트 내내 서비스 가능한 파드가 3개 아래로 떨어지지 않습니다. 이것이 무중단 배포의 핵심입니다. `readinessProbe` 가 있어서 "새 파드가 진짜 준비됐을 때만" 다음 교체로 넘어갑니다.

이력을 봅니다.

```bash
kubectl rollout history deployment/web -n step05
```
```
REVISION  CHANGE-CAUSE
1         kubectl set image deployment/web nginx=nginx:1.27-alpine --namespace=step05 --record=true
2         set APP_VERSION=v2
```

> 💡 **실무 팁**: `CHANGE-CAUSE` 는 `kubernetes.io/change-cause` 어노테이션에서 옵니다. 비워두면 "무엇을 바꿨는지" 이력에 안 남아 롤백 판단이 어렵습니다. 배포 파이프라인에서 커밋 해시/버전을 이 어노테이션에 넣으세요. (`--record` 플래그는 폐지 예정이라 어노테이션 직접 설정을 권장)

---

## 5-4. ⚠️ 함정: 잘못된 이미지 → 롤아웃이 멈춘다

존재하지 않는 이미지 태그로 업데이트해 봅시다. **명령은 성공(`image updated`)하지만 배포는 실패**합니다.

```bash
kubectl set image deployment/web nginx=nginx:1.27-doesnotexist -n step05
kubectl rollout status deployment/web -n step05 --timeout=25s
```
```
Waiting for deployment "web" rollout to finish: 2 out of 4 new replicas have been updated...
error: timed out waiting for the condition       <- 멈췄다
```

상태를 보면 **구버전이 그대로 서비스 중**입니다. 이게 RollingUpdate 의 안전장치입니다.

```bash
kubectl get deploy,rs -n step05
kubectl get pods -n step05
```
```
NAME                  READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/web   3/4     2            3           81s     <- 3개는 아직 정상 서비스

NAME                             DESIRED   CURRENT   READY   AGE
replicaset.apps/web-699f57cd74   2         2         0       25s   <- 신버전(불량): 2개 뜨려다 실패
replicaset.apps/web-7b6659848b   0         0         0       80s
replicaset.apps/web-7d86f6d776   3         3         3       46s   <- 이전 정상 버전: 3개 유지

NAME                   READY   STATUS             RESTARTS   AGE
web-699f57cd74-bptck   0/1     ImagePullBackOff   0          25s
web-699f57cd74-r7nn5   0/1     ImagePullBackOff   0          25s
web-7d86f6d776-gpx7q   1/1     Running            0          44s
web-7d86f6d776-t5qn9   1/1     Running            0          46s
web-7d86f6d776-z7h4t   1/1     Running            0          46s
```

`maxUnavailable: 1` 덕분에 구버전 3개는 죽지 않았습니다. `maxSurge: 1` 이라 불량 신버전은 2개까지만 뜨려다 `ImagePullBackOff` 에 걸려 멈췄습니다. **결과적으로 사용자 트래픽은 살아있는 3개가 계속 처리** 합니다. 만약 `maxUnavailable` 을 크게 잡았다면 구버전을 먼저 다 내려버려 장애가 났을 겁니다.

왜 멈췄는지는 파드 이벤트에 정확히 찍힙니다.

```bash
kubectl describe pod -n step05 -l pod-template-hash=699f57cd74 | grep -iE "Failed|Back-off"
```
```
Warning  Failed   kubelet  Failed to pull image "nginx:1.27-doesnotexist": ... not found
Warning  Failed   kubelet  Error: ErrImagePull
Normal   BackOff  kubelet  Back-off pulling image "nginx:1.27-doesnotexist"
Warning  Failed   kubelet  Error: ImagePullBackOff
```

---

## 5-5. 롤백 — 직전 정상 리비전으로

멈춘 배포를 되돌립니다. `undo` 는 직전 리비전으로 되돌아갑니다.

```bash
kubectl rollout history deployment/web -n step05     # 3번이 불량
kubectl rollout undo deployment/web -n step05
kubectl rollout status deployment/web -n step05
```
```
REVISION  CHANGE-CAUSE
1         kubectl set image ... --record=true
2         set APP_VERSION=v2
3         bad image nginx:1.27-doesnotexist          <- 이걸 버리고

deployment.apps/web rolled back
deployment "web" successfully rolled out            <- 정상 복구
```

복구 후 파드는 다시 4/4 Running, 불량 ReplicaSet 은 0개로 비워집니다.

```bash
kubectl get deploy,rs -n step05
```
```
NAME                  READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/web   4/4     4            4           92s

NAME                             DESIRED   CURRENT   READY   AGE
replicaset.apps/web-699f57cd74   0         0         0       36s   <- 불량, 비워짐
replicaset.apps/web-7d86f6d776   4         4         4       57s   <- 정상 복귀
```

특정 리비전으로 되돌리려면 `--to-revision` 을 씁니다. 구버전 ReplicaSet 이 `DESIRED 0` 으로 남아있기에 롤백이 즉시 가능합니다(보관 개수는 `spec.revisionHistoryLimit`, 기본 10).

```bash
kubectl rollout undo deployment/web -n step05 --to-revision=2
```

> 💡 **실무 팁**: 롤백은 "최근 코드를 배포로 되돌리는 것"이지 Git 을 되돌리는 게 아닙니다. `undo` 로 급한 불을 끈 뒤에는 반드시 매니페스트/코드도 정상 상태로 맞춰 `apply` 하세요. 안 그러면 다음 배포에서 다시 불량 버전이 나갑니다. (위 롤백 시 나오는 `last-applied-configuration` 경고가 바로 이 불일치를 알리는 것)

---

## 5-6. ⚠️ 함정: 셀렉터는 불변(immutable)

`spec.selector` 는 Deployment 를 만든 뒤에는 **바꿀 수 없습니다**. 셀렉터에 `tier: frontend` 를 추가해 apply 하면 거부됩니다.

```bash
kubectl apply -f manifests/bad-selector.yaml
```
```
The Deployment "web" is invalid: spec.selector: Invalid value:
{"matchLabels":{"app":"web","tier":"frontend"}}: field is immutable
```

왜일까요? 셀렉터를 바꾸면 "어떤 파드가 이 Deployment 소유인가"의 기준이 바뀌어, 기존 파드가 **고아**가 되거나 소유권이 꼬입니다. Kubernetes 는 아예 변경을 막습니다.

> ⚠️ **함정**: 셀렉터를 바꿔야 한다면 방법은 하나 — **Deployment 를 지우고 새로 만드는 것**뿐입니다(`kubectl delete deploy web` → 새 매니페스트로 `apply`). 그래서 셀렉터는 처음에 신중히 정해야 합니다. 또한 `template.metadata.labels` 는 반드시 `selector.matchLabels` 를 **포함**해야 합니다. 안 그러면 생성 자체가 거부됩니다.

---

## 정리

| 개념 | 한 줄 요약 | 대표 명령 |
|---|---|---|
| 계층 구조 | Deployment → ReplicaSet → Pod | `kubectl get deploy,rs,pod` |
| 스케일 | 개수만 변경(롤아웃 아님) | `kubectl scale --replicas=N` |
| 롤링 업데이트 | 템플릿 변경 시 새 RS 로 점진 교체 | `kubectl set image/env`, `rollout status` |
| maxSurge/maxUnavailable | 업데이트 중 가용성 보장 | (매니페스트 `strategy`) |
| 이력·롤백 | 리비전 추적과 되돌리기 | `rollout history/undo` |
| 셀렉터 불변 | 생성 후 변경 불가 | (재생성만 가능) |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 06 — 서비스와 DNS](../step-06-services/index.md)

---

## 실습 파일

이 스텝은 매니페스트 2개와 실행 스크립트 1개로 구성됩니다. 먼저 `manifests/deployment.yaml` 을 `apply` 해서 `step05` 네임스페이스와 `web` Deployment(replicas=4)를 띄우고, 스케일 → 롤링 업데이트 → 불량 이미지 → 롤백 순서로 진행합니다. 마지막에 `manifests/bad-selector.yaml` 을 apply 해서 **셀렉터 불변** 규칙을 몸으로 확인하는 흐름이며, `commands.sh` 는 이 전 과정을 강의 순서 그대로 옮겨 담은 참고용 스크립트입니다.

### manifests/deployment.yaml

이 스텝의 **출발점**이 되는 매니페스트로, 5-1 절 맨 처음에 `kubectl apply -f manifests/deployment.yaml` 로 적용합니다. 한 파일 안에 `---` 로 두 오브젝트를 담았습니다 — 먼저 `kind: Namespace` 로 `step05` 네임스페이스를 만들고, 이어서 그 안에 `kind: Deployment` 인 `web` 을 만듭니다. 덕분에 `kubectl create namespace` 를 따로 칠 필요가 없고, 실습이 끝나면 `kubectl delete namespace step05` 한 방으로 전부 정리됩니다.

- `spec.replicas: 4` — ReplicaSet 이 유지할 파드 개수입니다. 5-2 절에서 `kubectl scale --replicas=5` 로 바꿔보지만, 템플릿이 그대로이므로 새 ReplicaSet 은 생기지 않습니다.
- `spec.selector.matchLabels.app: web` 과 `spec.template.metadata.labels.app: web` 이 **똑같아야** 합니다. 템플릿 레이블이 셀렉터를 포함하지 않으면 생성 자체가 거부되고, 셀렉터는 만든 뒤 바꿀 수 없습니다(5-6 절).
- `strategy.rollingUpdate` 의 `maxSurge: 1` 과 `maxUnavailable: 1` 이 replicas=4 와 곱해져 **"일시적으로 최대 5개, 최소 available 3개"** 라는 롤아웃 규칙을 만듭니다. 5-4 절에서 불량 이미지를 넣어도 구버전 3개가 살아남는 이유가 정확히 이 두 줄입니다.
- `containers[0]` 는 `name: nginx`, `image: nginx:1.27-alpine` 이고 `ports.containerPort: 80` 을 선언합니다. 5-4 절에서 `kubectl set image deployment/web nginx=...` 로 이미지를 바꿀 때 지정하는 `nginx=` 가 바로 이 컨테이너 이름입니다.
- `readinessProbe` 의 `httpGet: path: /, port: 80` + `initialDelaySeconds: 1` + `periodSeconds: 3` — 새 파드가 80 포트로 응답해야 비로소 available 로 집계됩니다. 이 프로브가 없으면 컨테이너가 뜨자마자 "준비됨"으로 취급돼, 실제로는 아직 못 받는 파드로 트래픽이 흘러가고 롤아웃이 너무 빨리 넘어갑니다.

```yaml file="./manifests/deployment.yaml"
```

### manifests/bad-selector.yaml

**일부러 실패하도록 만든 매니페스트**입니다. 5-6 절에서 `kubectl apply -f manifests/bad-selector.yaml` 로 적용하면 성공하는 게 아니라 다음 에러가 나야 정상입니다.

```
The Deployment "web" is invalid: spec.selector: Invalid value:
{"matchLabels":{"app":"web","tier":"frontend"}}: field is immutable
```

- 위 `deployment.yaml` 과 같은 `name: web`, `namespace: step05` 를 쓰기 때문에 **기존 Deployment 를 수정하는 apply** 가 됩니다. 그런데 `selector.matchLabels` 에 `tier: frontend` 를 **추가**했으므로 불변 필드 변경 시도가 되어 API 서버가 거부합니다.
- `template.metadata.labels` 에도 `tier: frontend` 를 같이 넣어 두었습니다. 즉 "템플릿 레이블과 셀렉터를 짝 맞춰 제대로 썼는데도" 거부된다는 점이 학습 포인트입니다 — 문법이 틀려서가 아니라 **셀렉터라는 필드 자체가 불변**이기 때문입니다.
- 셀렉터를 바꾸려면 `kubectl delete deployment web -n step05` 로 지우고 새로 만드는 수밖에 없습니다. 이때는 같은 파일을 그대로 apply 해도 성공합니다(신규 생성이므로).
- 이 파일에는 `deployment.yaml` 과 달리 `kind: Namespace` 오브젝트가 없고, `strategy`·`readinessProbe`·`ports` 와 `metadata.labels` 도 빠져 있습니다. `replicas: 4` 와 `selector` + `template` 처럼 함정 재현에 필요한 부분만 남긴 것입니다.
- 주의: 실습 순서상 이 파일은 **`deployment.yaml` 이 이미 적용된 상태**에서 apply 해야 의미가 있습니다. `step05` 네임스페이스는 있지만 `web` Deployment 가 없는 상태에서 적용하면 신규 생성이라 그냥 성공해버려 함정을 재현하지 못하고, 아무것도 없는 빈 클러스터에서는 네임스페이스가 없어 `namespaces "step05" not found` 라는 엉뚱한 에러가 납니다.

```yaml file="./manifests/bad-selector.yaml"
```

### commands.sh

강의 5-1 ~ 5-6 절에 나오는 `kubectl` 명령을 **등장 순서 그대로** 정리한 스크립트입니다. 한 번에 통째로 실행하기보다, 섹션 주석(`--- 5-3. 롤링 업데이트 ---` 등)을 따라가며 **한 줄씩 붙여넣고 출력을 관찰**하도록 만들어졌습니다.

- 첫 줄은 `#!/usr/bin/env bash` 이고, 이어지는 `export PATH="/opt/homebrew/bin:$PATH"` 는 Homebrew 로 설치한 `kubectl` 을 찾기 위한 macOS(Apple Silicon) 전용 줄입니다. Linux 나 Intel Mac 이라면 이 줄은 없어도 무방합니다.
- 그다음 `kubectl config current-context` 로 컨텍스트가 `kind-learn` 인지 먼저 확인합니다. 다른 클러스터(운영 등)에 붙어 있으면 아래 명령들이 엉뚱한 곳에 적용되니 반드시 확인하고 시작하세요.
- 5-4 절의 `kubectl describe pod -n step05 -l pod-template-hash=699f57cd74` 에 박혀 있는 해시는 **강의 당시 예시값**입니다. 파드 템플릿 해시는 매번 달라지므로, `kubectl get pods -n step05` 로 실제 불량 ReplicaSet 의 해시를 확인해 바꿔 넣어야 합니다.
- `kubectl set image ... nginx:1.27-doesnotexist` 는 존재하지 않는 태그를 일부러 넣는 줄이라 `rollout status --timeout=25s` 가 `timed out` 으로 끝나는 게 정상입니다. 실패가 아니라 **의도된 결과**입니다.
- `kubectl annotate ... kubernetes.io/change-cause=... --overwrite` 를 롤아웃 유발 명령과 나란히 둔 이유는, 어노테이션을 **같은 시점에** 걸어야 그 리비전의 CHANGE-CAUSE 로 남기 때문입니다.
- ⚠️ 마지막 줄 `kubectl delete namespace step05` 는 **파괴적 명령**입니다. 이 네임스페이스의 Deployment·ReplicaSet·Pod 가 전부 삭제되고 rollout 이력도 사라지므로, 실습을 완전히 마친 뒤에만 실행하세요.

```bash file="./commands.sh"
```
