# Step 11 — 스테이트풀셋

> **학습 목표**
> - Deployment 와 StatefulSet 의 차이(안정적 이름·순서·전용 스토리지)를 실물로 구분한다
> - 헤드리스 서비스(`clusterIP: None`)가 왜 필수인지, 파드별 DNS 가 어떻게 생기는지 이해한다
> - `volumeClaimTemplates` 로 파드마다 전용 PVC 가 자동 생성되는 것을 확인한다
> - 순차 생성(0→1→2)·역순 삭제(2→1→0)를 눈으로 본다
> - 파드를 지워도 같은 이름·같은 PVC 로 재생성되어 **데이터가 살아남는 것**을 증명한다
>
> **선행 스텝**: [Step 10 — 볼륨·PVC](../step-10-storage/)
> **예상 소요**: 60분

---

## 11-1. 왜 스테이트풀셋인가 — Deployment 와 무엇이 다른가

Deployment 는 **파드가 서로 구별되지 않는다**는 전제로 동작합니다. 파드 3개는 그냥 동일한 복제본이고, 이름도 `web-7b8c57c6d6-46wfj` 처럼 랜덤 해시가 붙습니다. 죽으면 **완전히 새 이름**으로 태어나고, 어느 파드에 붙든 상관없습니다. 웹 서버·API 처럼 상태를 밖(DB)에 두는 워크로드에 딱 맞습니다.

하지만 데이터베이스·카프카·주키퍼·레디스 클러스터처럼 **각 인스턴스가 고유한 신원**을 가져야 하는 워크로드가 있습니다. "0번 노드는 마스터, 1·2번은 레플리카", "각자 자기 디스크를 계속 써야 한다" 같은 요구입니다. 여기에 답하는 것이 **StatefulSet** 입니다. StatefulSet 은 세 가지를 보장합니다.

| 보장 | Deployment | StatefulSet |
|---|---|---|
| **안정적 이름** | 랜덤 해시(`web-7b8c57c6d6-…`) | 서수 기반 고정(`redis-0`, `redis-1`, `redis-2`) |
| **생성/삭제 순서** | 병렬·무순서 | 순차(0→1→2), 삭제는 역순(2→1→0) |
| **전용 스토리지** | 공유/없음 | 파드마다 자기 PVC 를 계속 소유 |
| **안정적 DNS** | 서비스 VIP 하나 | 파드별 개별 DNS |

실제로 같은 네임스페이스에 Deployment 와 StatefulSet 을 나란히 띄우면 이름 규칙 차이가 한눈에 보입니다.

**실행 결과**
```
# Deployment 파드 — 랜덤 해시 이름
NAME                   READY   STATUS    RESTARTS   AGE
web-7b8c57c6d6-46wfj   1/1     Running   0          0s
web-7b8c57c6d6-nmmf8   1/1     Running   0          0s
web-7b8c57c6d6-z2jbv   1/1     Running   0          0s

# StatefulSet 파드 — 서수(ordinal) 고정 이름 0,1,2
NAME      READY   STATUS    RESTARTS   AGE
redis-0   1/1     Running   0          45s
redis-1   1/1     Running   0          6s
redis-2   0/1     Running   0          0s
```

`redis-0`, `redis-1`, `redis-2` 의 끝 숫자를 **서수(ordinal index)** 라고 부릅니다. 0부터 시작하고, 이 숫자가 곧 파드의 신원이자 생성·삭제 순서이자 스토리지·DNS 이름의 뿌리입니다.

> 💡 **실무 팁**: "상태를 파드 밖(외부 DB, S3)에 두면 Deployment, 파드 안 디스크에 둬야 하면 StatefulSet" 이 첫 판단 기준입니다. 무작정 StatefulSet 을 쓰면 운영이 복잡해집니다.

---

## 11-2. 헤드리스 서비스 — 파드별 DNS 를 만드는 열쇠

StatefulSet 을 만들려면 **헤드리스 서비스(headless Service)** 가 먼저 있어야 합니다. 일반 Service 는 `ClusterIP` 라는 가상 IP(VIP) 하나를 만들고 그 뒤로 파드들을 **로드밸런싱**합니다. 즉 "어느 파드로 갈지 모르는" 단일 진입점입니다. 이건 신원이 필요한 워크로드엔 오히려 방해가 됩니다.

`clusterIP: None` 으로 선언한 **헤드리스** 서비스는 VIP 를 만들지 않습니다. 대신 DNS 에 **파드마다 개별 A 레코드**를 등록합니다. 그래서 `redis-0.redis` 처럼 특정 파드를 콕 집어 부를 수 있게 됩니다.

```yaml
# manifests/01-redis-statefulset.yaml (발췌)
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: step11
spec:
  clusterIP: None          # ← 헤드리스. VIP 없음, 파드별 DNS 를 만든다
  selector:
    app: redis
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  namespace: step11
spec:
  serviceName: redis       # ← 헤드리스 서비스 이름과 반드시 일치
  replicas: 3
  ...
```

```bash
kubectl apply -f manifests/00-namespace.yaml
kubectl apply -f manifests/01-redis-statefulset.yaml
kubectl get svc redis -n step11
```

**실행 결과**
```
NAME    TYPE        CLUSTER-IP   EXTERNAL-IP   PORT(S)    AGE
redis   ClusterIP   None         <none>        6379/TCP   3m25s
```

`CLUSTER-IP` 가 `None` 인 것이 헤드리스의 표식입니다.

> ⚠️ **함정**: StatefulSet 의 `spec.serviceName` 은 헤드리스 서비스 이름과 **반드시 같아야** 합니다. 오타가 나면 파드는 뜨지만 `redis-0.redis` 같은 DNS 가 안 생겨 상호 접속이 안 됩니다. 게다가 이 서비스는 `clusterIP: None` 이어야 파드별 DNS 가 생깁니다 — 일반 ClusterIP 로 만들면 개별 이름을 못 얻습니다.

---

## 11-3. volumeClaimTemplates — 파드마다 자기 PVC 를 자동으로

Deployment 에서는 볼륨을 쓰려면 PVC 를 미리 손으로 만들어 마운트합니다. 그러면 모든 복제본이 **같은 PVC 하나**를 공유하죠. StatefulSet 은 다릅니다. `volumeClaimTemplates` 에 "틀"만 적어두면, 컨트롤러가 **파드마다 PVC 를 한 개씩 자동 생성**합니다. 이름 규칙은 `<템플릿이름>-<스테이트풀셋이름>-<서수>` 입니다.

```yaml
# manifests/01-redis-statefulset.yaml (발췌)
  template:
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          command: ["redis-server", "--appendonly", "yes", "--dir", "/data"]
          volumeMounts:
            - name: data           # ← 아래 템플릿 name 과 일치
              mountPath: /data
  volumeClaimTemplates:            # ← 파드마다 PVC 를 자동 생성
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 128Mi
```

```bash
kubectl get pvc -n step11
```

**실행 결과**
```
NAME           STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   AGE
data-redis-0   Bound    pvc-bcbadc1c-52c5-49c6-8693-a36821233e4b   128Mi      RWO            standard       3m25s
data-redis-1   Bound    pvc-fbeb8b0b-6413-4c4e-8f7c-9ec46e5ed258   128Mi      RWO            standard       3m15s
data-redis-2   Bound    pvc-68200025-19df-4404-b1de-59aa4665dc68   128Mi      RWO            standard       2m59s
```

파드 3개에 `data-redis-0`, `data-redis-1`, `data-redis-2` — **정확히 1개씩** 생겼습니다. `templatename` 이 `data` 라서 앞에 `data-` 가 붙었습니다. kind 의 기본 StorageClass(`standard`, local-path)가 `WaitForFirstConsumer` 라서, 각 PVC 는 해당 파드가 스케줄될 때 비로소 볼륨이 바인딩됩니다(그래서 AGE 가 파드 순서대로 벌어집니다).

이벤트 로그를 보면 컨트롤러가 파드를 만들기 전에 먼저 Claim(PVC)을 만드는 것을 볼 수 있습니다.

**실행 결과** (`kubectl describe statefulset redis -n step11` 의 Events 발췌)
```
  Type    Reason            Age    From                    Message
  ----    ------            ----   ----                    -------
  Normal  SuccessfulCreate  3m14s  statefulset-controller  Create Claim data-redis-0 Pod redis-0 in StatefulSet redis success
  Normal  SuccessfulCreate  3m4s   statefulset-controller  Create Claim data-redis-1 Pod redis-1 in StatefulSet redis success
  Normal  SuccessfulCreate  2m48s  statefulset-controller  Create Claim data-redis-2 Pod redis-2 in StatefulSet redis success
```

> ⚠️ **함정**: `volumeClaimTemplates` 로 만든 PVC 는 **StatefulSet 을 지워도, 파드를 지워도, 스케일 다운을 해도 자동으로 사라지지 않습니다.** 데이터 유실을 막기 위한 안전장치지만, 정리할 때는 `kubectl delete pvc` 로 손수 지워야 합니다(이 스텝 마지막 참고). 방치하면 스토리지 비용이 계속 나갑니다.

---

## 11-4. 순차 생성(0→1→2) — 순서가 보장된다

StatefulSet 은 파드를 **한 번에 하나씩, 서수 순서대로** 만듭니다. `redis-0` 이 Running & Ready 가 된 다음에야 `redis-1` 을 시작하고, 그 다음 `redis-2` 입니다. 마스터가 먼저 떠야 레플리카가 붙을 수 있는 DB 같은 워크로드에 필수인 성질입니다.

`replicas` 를 0 으로 줄였다가 다시 3 으로 올리며 `-w`(watch)로 관찰하면 순서가 그대로 보입니다.

```bash
kubectl scale statefulset redis -n step11 --replicas=0
kubectl scale statefulset redis -n step11 --replicas=3
kubectl get pod -n step11 -l app=redis -w
```

**실행 결과**
```
NAME      READY   STATUS              RESTARTS   AGE
redis-0   0/1     Pending             0          0s
redis-0   0/1     ContainerCreating   0          0s
redis-0   0/1     Running             0          1s
redis-0   1/1     Running             0          6s   ← 0번이 Ready 된 뒤에야
redis-1   0/1     Pending             0          0s   ← 1번이 시작된다
redis-1   0/1     ContainerCreating   0          0s
redis-1   0/1     Running             0          1s
```

`redis-0` 이 `1/1 Running`(Ready)에 도달한 **다음에** `redis-1` 이 `Pending` 으로 나타납니다. 서수 순서가 지켜집니다. 최종 상태의 AGE 도 이 순서를 증언합니다.

**실행 결과**
```
NAME      READY   STATUS    RESTARTS   AGE
redis-0   1/1     Running   0          56s
redis-1   1/1     Running   0          17s
redis-2   1/1     Running   0          11s
```

> 💡 **실무 팁**: 파드가 순서대로 안 올라온다면 대개 **ReadinessProbe** 가 통과 못 해서 앞 파드가 Ready 에 못 이르는 것입니다. StatefulSet 은 "앞 파드 Ready" 를 다음 파드 시작 조건으로 삼기 때문에, 프로브 설정이 틀리면 `redis-1` 이 영원히 안 뜨는 것처럼 보입니다. `-o wide` 와 `describe` 로 앞 파드를 먼저 보세요.

---

## 11-5. 안정적 네트워크 ID — 파드별 DNS

헤드리스 서비스 덕분에 각 파드는 다음 형식의 **고정 DNS 이름**을 얻습니다.

```
<파드이름>.<서비스이름>.<네임스페이스>.svc.cluster.local
예) redis-0.redis.step11.svc.cluster.local
```

파드 IP 는 재생성 때마다 바뀌지만, 이 DNS 이름은 **바뀌지 않습니다.** 그래서 클러스터 멤버끼리 "IP 가 아니라 이름으로" 서로를 찾습니다. busybox 클라이언트에서 `nslookup` 으로 확인해 봅시다.

```bash
kubectl apply -f manifests/02-dns-client.yaml
kubectl exec -n step11 dns-client -- nslookup redis-0.redis.step11.svc.cluster.local
```

**실행 결과**
```
Server:		10.96.0.10
Address:	10.96.0.10:53

Name:	redis-0.redis.step11.svc.cluster.local
Address: 10.244.1.39
```

헤드리스 서비스 **자체**를 조회하면 모든 파드 IP 가 한꺼번에 돌아옵니다(멤버 목록 발견에 유용).

```bash
kubectl exec -n step11 dns-client -- nslookup redis.step11.svc.cluster.local
```

**실행 결과**
```
Name:	redis.step11.svc.cluster.local
Address: 10.244.1.39
Name:	redis.step11.svc.cluster.local
Address: 10.244.2.39
Name:	redis.step11.svc.cluster.local
Address: 10.244.2.42
```

애플리케이션은 보통 짧은 이름 `redis-0.redis` 만 써도 됩니다. 파드의 `/etc/resolv.conf` 에 검색 도메인이 등록돼 있기 때문입니다.

```bash
kubectl exec -n step11 redis-1 -- cat /etc/resolv.conf
kubectl exec -n step11 redis-1 -- redis-cli -h redis-0.redis ping
```

**실행 결과**
```
search step11.svc.cluster.local svc.cluster.local cluster.local
nameserver 10.96.0.10
options ndots:5

PONG
```

`redis-1` 이 짧은 이름 `redis-0.redis` 로 `redis-0` 에 접속해 `PONG` 을 받았습니다.

> ⚠️ **함정**: busybox 의 `nslookup` 은 `/etc/resolv.conf` 의 `search` 경로를 자동 적용하지 않아, 짧은 이름 `redis-0.redis` 를 넣으면 `NXDOMAIN` 이 납니다. 이건 DNS 가 고장난 게 아니라 **busybox nslookup 의 한계**입니다. 실제 앱(redis-cli, curl 등)은 검색 경로를 쓰므로 짧은 이름이 잘 됩니다. 디버깅할 땐 nslookup 에는 항상 **FQDN(전체 이름)** 을 넣으세요.

---

## 11-6. 안정적 스토리지 증명 — 파드를 죽여도 데이터는 산다

StatefulSet 의 핵심 가치입니다. 파드를 지우면 **같은 이름·같은 PVC** 로 다시 태어나므로, 디스크에 쓴 데이터가 그대로 남습니다. 직접 증명해 봅시다.

**1) `redis-0` 에 데이터 기록**

```bash
kubectl exec -n step11 redis-0 -- redis-cli set course "k8s-step11"
kubectl exec -n step11 redis-0 -- redis-cli set owner "julong"
kubectl get pvc data-redis-0 -n step11 \
  -o custom-columns='PVC:.metadata.name,VOLUME:.spec.volumeName,STATUS:.status.phase'
```

**실행 결과**
```
OK
OK
PVC            VOLUME                                     STATUS
data-redis-0   pvc-bcbadc1c-52c5-49c6-8693-a36821233e4b   Bound
```

`redis-0` 이 쓰는 볼륨은 `pvc-bcbadc1c-…` 입니다. 이 값을 기억해 두세요.

**2) `redis-0` 파드 삭제 → 3) 같은 이름으로 자동 재생성**

```bash
kubectl delete pod redis-0 -n step11
kubectl wait --for=condition=Ready pod/redis-0 -n step11 --timeout=90s
kubectl get pvc data-redis-0 -n step11 \
  -o custom-columns='PVC:.metadata.name,VOLUME:.spec.volumeName,STATUS:.status.phase'
```

**실행 결과**
```
pod "redis-0" deleted from step11 namespace
pod/redis-0 condition met
PVC            VOLUME                                     STATUS
data-redis-0   pvc-bcbadc1c-52c5-49c6-8693-a36821233e4b   Bound
```

재생성된 `redis-0` 이 붙은 볼륨은 `pvc-bcbadc1c-…` — **삭제 전과 완전히 동일**합니다. 이름도 `redis-0` 그대로입니다(Deployment 였다면 새 랜덤 이름이 됐겠죠).

**4) 데이터가 살아있는가?**

```bash
kubectl exec -n step11 redis-0 -- redis-cli get course
kubectl exec -n step11 redis-0 -- redis-cli get owner
```

**실행 결과**
```
k8s-step11
julong
```

파드를 통째로 지웠는데도 데이터가 그대로 살아남았습니다. **안정적 이름 + 안정적 PVC** 가 만든 결과입니다.

> 💡 **실무 팁**: DB 파드가 재시작·재스케줄돼도 데이터가 유지되는 이유가 바로 이것입니다. 반대로 말하면, StatefulSet 파드를 지워도 PVC 는 남으므로 "디스크를 깨끗이 초기화하고 싶다" 면 파드가 아니라 **PVC 를 지워야** 합니다.

---

## 11-7. 역순 삭제(2→1→0) — 스케일 다운은 거꾸로

스케일 다운은 생성의 정확한 반대입니다. **가장 큰 서수부터** 하나씩 지웁니다(2→1→0). 클러스터형 워크로드에서 "레플리카를 먼저 떼고 마스터를 마지막에" 정리하기 위한 성질입니다.

```bash
kubectl scale statefulset redis -n step11 --replicas=1
kubectl get pod -n step11 -l app=redis -w
```

**실행 결과**
```
NAME      READY   STATUS        RESTARTS   AGE
redis-0   1/1     Running       0          21s
redis-1   1/1     Running       0          84s
redis-2   1/1     Running       0          78s
redis-2   1/1     Terminating   0          79s   ← 2번이 먼저 종료
redis-2   0/1     Completed     0          81s
redis-1   1/1     Terminating   0          87s   ← 그 다음 1번
redis-1   0/1     Completed     0          88s
```

`redis-2` 가 먼저, 그 다음 `redis-1` 이 종료됐고 `redis-0` 은 남았습니다. 그런데 PVC 는 어떨까요?

```bash
kubectl get pvc -n step11
```

**실행 결과**
```
NAME           STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   AGE
data-redis-0   Bound    pvc-bcbadc1c-52c5-49c6-8693-a36821233e4b   128Mi      RWO            standard       2m56s
data-redis-1   Bound    pvc-fbeb8b0b-6413-4c4e-8f7c-9ec46e5ed258   128Mi      RWO            standard       2m46s
data-redis-2   Bound    pvc-68200025-19df-4404-b1de-59aa4665dc68   128Mi      RWO            standard       2m30s
```

파드는 2개 사라졌지만 **PVC 3개는 그대로** 남아 있습니다. 나중에 다시 `replicas=3` 으로 올리면 `redis-1`, `redis-2` 는 **예전 그 PVC 를 다시 물고** 올라옵니다 — 데이터가 이어집니다. 이것이 StatefulSet 이 PVC 를 함부로 지우지 않는 이유입니다.

> ⚠️ **함정**: "스케일 다운했으니 스토리지도 반환됐겠지" 라고 생각하면 오산입니다. 남은 PVC 는 계속 볼륨을 점유합니다. 정말로 정리하려면 `kubectl delete pvc data-redis-1 data-redis-2 -n step11` 처럼 명시적으로 지워야 합니다.

---

## 정리

| 개념 | 한 줄 요약 | 대표 확인 명령 |
|---|---|---|
| 안정적 이름 | 서수 기반 고정(`redis-0,1,2`) | `kubectl get pod -n step11 -l app=redis` |
| 헤드리스 서비스 | `clusterIP: None`, 파드별 DNS 생성 | `kubectl get svc redis -n step11` |
| volumeClaimTemplates | 파드마다 PVC 자동 생성 | `kubectl get pvc -n step11` |
| 순차 생성 | 0→1→2, 앞 파드 Ready 후 다음 | `kubectl get pod -w` |
| 역순 삭제 | 스케일 다운은 2→1→0 | `kubectl scale ... --replicas=N -w` |
| 안정적 DNS | `<pod>.<svc>.<ns>.svc.cluster.local` | `nslookup <fqdn>` |
| 안정적 스토리지 | 파드 삭제해도 같은 PVC 재부착 | 데이터 write → delete → get |
| PVC 수명 | SS/파드/스케일다운으로 안 지워짐 | `kubectl delete pvc` 로 수동 정리 |

## 연습 과제 → [challenge.md](challenge.md)

## 다음 단계
→ [Step 12 — 잡·데몬셋](../step-12-jobs-daemonset/)

---

## 실습 파일

이 스텝의 실습은 매니페스트 3개를 **번호 순서대로** 적용하면서 진행합니다. `manifests/00-namespace.yaml` 로 `step11` 네임스페이스를 만들고, `manifests/01-redis-statefulset.yaml` 로 헤드리스 서비스와 Redis StatefulSet 을 한꺼번에 띄운 뒤, `manifests/02-dns-client.yaml` 의 busybox 파드로 파드별 DNS 를 조회합니다. `commands.sh` 는 본문에 나온 `kubectl` 명령을 **실제로 실행 가능한 순서**로 모아둔 대본이라, 한 줄씩 복사해 실행하며 결과를 관찰하는 용도로 씁니다.

### manifests/00-namespace.yaml

- 이 스텝의 모든 리소스가 들어갈 `step11` 네임스페이스를 만듭니다. 가장 먼저 적용해야 하며, 뒤의 두 매니페스트가 `namespace: step11` 을 하드코딩하고 있어 이 파일 없이 적용하면 "namespaces \"step11\" not found" 로 실패합니다.
- `labels` 의 `course: k8s-learn`, `step: "11"` 은 교재 전체에서 스텝별 리소스를 구분하기 위한 표식입니다. `step: "11"` 이 **따옴표로 감싼 문자열**인 점에 주의하세요 — 레이블 값은 문자열만 허용하므로 `11` 로 쓰면 YAML 이 정수로 파싱해 적용이 거부됩니다.
- 실습이 끝나면 `kubectl delete namespace step11` 로 통째로 지울 수 있지만, PVC 를 먼저 정리하지 않으면 네임스페이스 삭제가 오래 걸릴 수 있습니다(아래 `commands.sh` 의 정리 순서 참고).

```yaml file="./manifests/00-namespace.yaml"
```

### manifests/01-redis-statefulset.yaml

이 스텝의 핵심 매니페스트입니다. 한 파일 안에 `---` 로 구분된 **두 개의 리소스**가 들어 있고, 11-2·11-3·11-4·11-6 절이 모두 이 파일 위에서 벌어집니다.

- **헤드리스 서비스(`kind: Service`)**: `clusterIP: None` 이 핵심입니다(11-2). 이 한 줄 때문에 VIP 가 만들어지지 않고, 대신 `redis-0.redis.step11.svc.cluster.local` 같은 **파드별 A 레코드**가 DNS 에 등록됩니다. `selector: app: redis` 로 StatefulSet 이 만든 파드들을 잡습니다.
- **`serviceName: redis`**: StatefulSet 이 위 서비스와 짝을 이루도록 묶는 필드로, 서비스 이름과 **반드시 문자열이 같아야** 합니다. 오타가 나면 파드는 정상적으로 뜨지만 파드별 DNS 가 생기지 않아 `redis-0.redis` 접속이 실패합니다.
- **`command: ["redis-server", "--appendonly", "yes", "--dir", "/data"]`**: AOF(append-only file) 영속화를 켜고 데이터 디렉터리를 `/data` 로 지정합니다. `volumeMounts` 의 `mountPath: /data` 와 같은 경로라서, Redis 가 쓴 데이터가 곧바로 PVC 위에 떨어집니다. 11-6 의 "파드를 지워도 데이터가 산다" 증명이 성립하는 이유가 바로 이 조합입니다.
- **`volumeClaimTemplates`**: `metadata.name: data` + `storage: 128Mi` + `accessModes: ["ReadWriteOnce"]` 로, 파드마다 `data-redis-0`/`data-redis-1`/`data-redis-2` PVC 를 자동 생성합니다(11-3). 컨테이너의 `volumeMounts[].name: data` 가 이 템플릿 이름과 일치해야 마운트가 연결됩니다.
- **`readinessProbe`** 는 `redis-cli ping` 을 `periodSeconds: 5` 간격으로 실행하고 `initialDelaySeconds: 2` 후 시작합니다. StatefulSet 은 "앞 파드가 Ready" 여야 다음 파드를 만들기 때문에, 이 프로브가 순차 생성(0→1→2, 11-4)의 **게이트 역할**을 합니다. 프로브가 통과하지 못하면 `redis-1` 이 영원히 안 뜨는 것처럼 보입니다.
- **`terminationGracePeriodSeconds: 10`** 은 종료 시 유예 시간을 10초로 줄여, 11-7 의 역순 삭제(2→1→0)를 답답하지 않게 관찰할 수 있도록 한 실습용 설정입니다(기본값은 30초).

```yaml file="./manifests/01-redis-statefulset.yaml"
```

### manifests/02-dns-client.yaml

- 11-5 절에서 파드별 안정 DNS 를 조회하기 위한 **디버깅 전용 클라이언트 파드**입니다. StatefulSet 배포 후에 적용합니다.
- `image: busybox:1.36` 에 `command: ["sleep", "3600"]` 을 줘서, 아무 일도 하지 않고 1시간 동안 살아 있게 만듭니다. 이렇게 해야 `kubectl exec -n step11 dns-client -- nslookup ...` 으로 파드 안에서 명령을 실행할 수 있습니다(명령이 없으면 busybox 는 즉시 종료되어 `CrashLoopBackOff` 가 납니다).
- `restartPolicy` 를 적지 않았으므로 기본값 `Always` 가 적용됩니다. 즉 1시간이 지나 `sleep` 이 끝나도 파드가 `Completed` 로 사라지는 게 아니라, kubelet 이 컨테이너를 **다시 시작**해 계속 `Running` 상태를 유지합니다(`kubectl get pod -n step11` 의 `RESTARTS` 값이 1씩 늘어납니다). 실습 내내 `exec` 로 붙을 수 있다는 뜻이고, 다 쓴 뒤에는 `kubectl delete pod dns-client -n step11` 로 직접 지우면 됩니다.
- **주의**: busybox 의 `nslookup` 은 `/etc/resolv.conf` 의 `search` 도메인을 자동 적용하지 않습니다. 그래서 이 파드로 조회할 때는 짧은 이름(`redis-0.redis`)이 아니라 **FQDN**(`redis-0.redis.step11.svc.cluster.local`)을 넣어야 합니다. 짧은 이름은 `redis-cli -h redis-0.redis ping` 처럼 실제 앱 리졸버를 쓰는 경로에서 확인합니다.

```yaml file="./manifests/02-dns-client.yaml"
```

### commands.sh

- 본문에 등장한 `kubectl` 명령을 담은 대본입니다. 단, **절 번호 순서가 아니라 실행 가능한 순서**로 배열되어 있다는 점에 주의하세요. 맨 앞에 배포 블록(`00`·`01`·`02` 매니페스트를 차례로 `apply` 하고 `kubectl wait --for=condition=Ready pod -l app=redis` 로 대기)이 오고, **그 다음에** 11-1 의 Deployment 대조가 옵니다. 비교할 `redis-0/1/2` 가 이미 떠 있어야 이름 규칙 대조가 성립하기 때문입니다. 이후로는 11-2 → 11-7 → 정리 순서를 따릅니다. 통째로 실행하기보다 절 단위로 복사해 붙여 넣으며 출력을 확인하는 편이 학습에 좋습니다(`kubectl get pod -w` 같은 watch 명령은 주석으로만 적혀 있고, 스크립트는 대신 `kubectl wait` 를 씁니다).
- 맨 위의 `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS/Homebrew 환경 전제이고, `kubectl config current-context` 로 컨텍스트가 `kind-learn` 인지 먼저 확인합니다. 다른 클러스터에 붙어 있으면 이후 명령이 엉뚱한 곳에 리소스를 만듭니다.
- 11-1 대조 구간에서는 `kubectl create deployment web --image=nginx:1.27-alpine --replicas=3 -n step11` 으로 Deployment 를 잠깐 띄워 `web-<해시>-<랜덤>` 이름과 `redis-0/1/2` 를 나란히 비교한 뒤 곧바로 삭제합니다. 대조가 목적이므로 남겨둘 필요가 없습니다.
- 11-4 구간은 `--replicas=0` 으로 줄였다가 `--replicas=3` 으로 되돌리며 순차 생성을 재현하고, 11-6 구간은 `redis-cli set` → `delete pod redis-0` → `redis-cli get` 순서로 데이터 생존을 증명합니다. `kubectl delete pod redis-0` 은 파괴적으로 보이지만 PVC 는 남으므로 데이터는 안전합니다.
- **정리 순서가 가장 중요합니다.** PVC 에는 `pvc-protection` 파이널라이저가 붙어 있어 파드가 볼륨을 물고 있는 동안 `delete pvc` 가 멈춰버립니다. 그래서 스크립트는 (1) `delete statefulset` → (2) `delete pod --all` + `wait --for=delete` → (3) `delete pvc -n step11 --all` → (4) `delete namespace step11` 순서를 지킵니다. **이 순서를 바꾸면 삭제가 무한정 대기 상태로 걸립니다.**
- 마지막 `kubectl get pv | grep step11 || echo ...` 두 줄은 PV 와 네임스페이스가 실제로 사라졌는지 확인하는 검증 단계입니다. `volumeClaimTemplates` 로 만든 PVC 는 절대 자동 삭제되지 않으므로, 이 확인을 건너뛰면 스토리지가 계속 남습니다.

```bash file="./commands.sh"
```
