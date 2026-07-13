# Step 10 — 스토리지

> **학습 목표**: 컨테이너의 파일시스템이 왜 휘발성인지 이해하고, `emptyDir` / `hostPath` / `PV`·`PVC`·`StorageClass`의 차이를 실습으로 구분한다. kind의 동적 프로비저닝으로 파드가 죽어도 살아남는 데이터를 직접 만들어 본다. / **선행 스텝**: [Step 09](../step-09-resources/) / **예상 소요**: 60분

---

## 10-0. 왜 볼륨이 필요한가

컨테이너의 파일시스템은 **컨테이너가 사라지면 같이 사라진다.** 파드가 재시작되거나 다른 노드로 재스케줄되면, 컨테이너 안에 쓴 파일은 흔적도 없이 날아간다. DB, 업로드 파일, 캐시처럼 살아남아야 하는 데이터를 컨테이너 계층에 두면 안 되는 이유다.

Kubernetes는 이 문제를 **볼륨(Volume)** 으로 푼다. 볼륨은 컨테이너 바깥에 존재하는 저장 공간을 파드에 마운트해 주는 추상화다. 볼륨의 종류에 따라 "수명"이 다르다.

| 볼륨 종류 | 수명(누구와 함께 죽나) | 대표 용도 |
|---|---|---|
| `emptyDir` | **파드**와 함께 생겼다 사라짐 (휘발성) | 컨테이너 간 임시 공유, 스크래치 공간 |
| `hostPath` | 노드의 파일이 그대로 (노드에 종속) | 노드 로그/디바이스 접근 (⚠️ 위험) |
| `PVC` → `PV` | **파드와 무관하게** 독립적으로 존재 (영속) | DB, 업로드, 상태 저장 데이터 |

이 스텝의 핵심은 딱 하나의 대비다. **emptyDir 은 파드가 죽으면 데이터가 사라지고, PVC 는 살아남는다.** 둘 다 실제로 증명한다.

---

## 10-1. emptyDir — 파드와 함께 태어나고 죽는 임시 볼륨

`emptyDir`은 파드가 노드에 스케줄될 때 **빈 디렉터리**로 생성된다. 같은 파드의 여러 컨테이너가 공유할 수 있고, 파드가 그 노드에서 제거되면 내용이 영구히 삭제된다.

```yaml
# manifests/01-emptydir-pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: emptydir-demo
  namespace: step10
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - name: scratch
          mountPath: /data     # 컨테이너 안에서 /data 에 볼륨을 붙인다
  volumes:
    - name: scratch
      emptyDir: {}             # 노드의 임시 공간에 빈 디렉터리를 만든다
```

```bash
kubectl apply -f manifests/01-emptydir-pod.yaml
kubectl exec -n step10 emptydir-demo -- sh -c 'echo "hello emptyDir" > /data/note.txt'
kubectl exec -n step10 emptydir-demo -- cat /data/note.txt
```

**실행 결과**
```
pod/emptydir-demo created
pod/emptydir-demo condition met
hello emptyDir @ Mon Jul 13 02:54:24 UTC 2026
total 4
-rw-r--r--    1 root     root            46 Jul 13 02:54 note.txt
```

파일을 쓰고 다시 읽는 것까지는 잘 된다. 문제는 다음 절이다.

> 💡 **실무 팁**: `emptyDir`은 기본적으로 노드 디스크를 쓰지만 `emptyDir: { medium: Memory }`로 두면 tmpfs(RAM)를 쓴다. 빠르지만 파드의 메모리 리밋에 포함되니 주의.

---

## 10-2. emptyDir 은 휘발성이다 (직접 증명)

"파드가 죽으면 사라진다"를 말로만 하지 않고 증명한다. Deployment 로 파드를 하나 띄우고, 데이터를 쓴 뒤, **파드를 삭제**한다. Deployment 는 곧바로 새 파드를 만든다. 그 새 파드의 `/data`는 비어 있어야 한다.

```yaml
# manifests/02-emptydir-deploy.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emptydir-web
  namespace: step10
spec:
  replicas: 1
  selector:
    matchLabels:
      app: emptydir-web
  template:
    metadata:
      labels:
        app: emptydir-web
    spec:
      containers:
        - name: app
          image: busybox:1.36
          command: ["sh", "-c", "sleep 3600"]
          volumeMounts:
            - name: scratch
              mountPath: /data
      volumes:
        - name: scratch
          emptyDir: {}
```

```bash
kubectl apply -f manifests/02-emptydir-deploy.yaml
POD=$(kubectl get pod -n step10 -l app=emptydir-web -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step10 $POD -- sh -c 'echo "data written to emptyDir" > /data/note.txt'
kubectl delete pod -n step10 $POD          # 파드 삭제 → Deployment 가 새 파드 생성
NEWPOD=$(kubectl get pod -n step10 -l app=emptydir-web -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n step10 $NEWPOD -- cat /data/note.txt   # 새 파드에서 옛 파일 읽기 시도
```

**실행 결과**
```
===== first pod: emptydir-web-7cdbbdf847-c8p2x =====
data written to emptyDir
===== delete the pod (Deployment recreates it) =====
pod "emptydir-web-7cdbbdf847-c8p2x" deleted from step10 namespace
deployment "emptydir-web" successfully rolled out
===== new pod: emptydir-web-7cdbbdf847-jtvjp =====
total 8
drwxrwxrwx    2 root     root          4096 Jul 13 02:54 .
drwxr-xr-x    1 root     root          4096 Jul 13 02:54 ..
----- try to read the old file -----
cat: can't open '/data/note.txt': No such file or directory
command terminated with exit code 1
==> note.txt GONE (emptyDir 은 파드와 함께 사라짐)
```

파드 이름이 `c8p2x` → `jtvjp` 로 바뀌었고, 새 파드의 `/data`는 완전히 비어 있다. 데이터는 사라졌다.

> ⚠️ **함정**: 컨테이너가 **재시작(restart)** 만 되는 경우(예: liveness probe 실패로 kubelet 이 컨테이너만 재시작)에는 같은 파드 안이라 emptyDir 데이터가 **유지된다.** 하지만 위처럼 **파드 자체가 삭제/재생성** 되면 사라진다. "재시작"과 "재생성"은 다르다. 상태를 emptyDir 에 두면 재배포 한 번에 날아간다.

---

## 10-3. hostPath — 노드 파일시스템 직접 노출 (⚠️ 위험)

`hostPath`는 **노드(여기서는 kind 컨테이너)의 실제 경로**를 파드에 그대로 붙인다. 노드 로그 수집기(DaemonSet), CNI/CSI 같은 시스템 컴포넌트가 아니면 거의 쓸 일이 없고, 쓰면 위험하다.

```yaml
# manifests/03-hostpath-pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: hostpath-demo
  namespace: step10
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - name: host-vol
          mountPath: /host-data
  volumes:
    - name: host-vol
      hostPath:
        path: /tmp/k8s-hostpath-demo   # 안전한 임시 경로만! /etc, /var 등 절대 금지
        type: DirectoryOrCreate
```

```bash
kubectl apply -f manifests/03-hostpath-pod.yaml
kubectl exec -n step10 hostpath-demo -- sh -c 'echo "written via hostPath" > /host-data/from-pod.txt'
kubectl exec -n step10 hostpath-demo -- cat /host-data/from-pod.txt
kubectl get pod hostpath-demo -n step10 -o wide
```

**실행 결과**
```
pod/hostpath-demo created
pod/hostpath-demo condition met
written via hostPath
===== which node is it on =====
node: learn-worker
```

> ⚠️ **함정 (보안·이식성)**: hostPath 는 세 가지 이유로 위험하다.
> 1. **노드 종속** — 파드가 `learn-worker` 에 데이터를 썼는데 다음에 `learn-worker2` 로 스케줄되면 그 데이터가 없다. 멀티노드에서 "영속"처럼 보이지만 사실은 노드마다 다른 데이터다.
> 2. **보안** — `/`, `/etc`, `/var/run/docker.sock` 등을 마운트하면 파드가 노드 전체를 장악할 수 있다. 컨테이너 탈출(container escape)의 단골 경로다.
> 3. **대체재 존재** — 영속 저장은 반드시 `PVC`(다음 절)로. hostPath 는 "노드 자체를 다뤄야 하는" 특수 컴포넌트만 쓴다.

---

## 10-4. PV / PVC / StorageClass — 영속 스토리지의 3요소

영속 스토리지는 역할이 분리되어 있다.

- **PersistentVolume(PV)**: 실제 저장 공간(디스크). 클러스터 레벨 리소스.
- **PersistentVolumeClaim(PVC)**: "이런 볼륨을 달라"는 **신청서**. 네임스페이스 리소스. 파드는 PVC 만 참조한다.
- **StorageClass(SC)**: PVC 가 오면 **PV 를 자동으로 만들어 주는 공장**(동적 프로비저닝)의 설정.

**accessModes** — 볼륨을 어떻게 붙일 수 있는가:

| 모드 | 약자 | 의미 |
|---|---|---|
| ReadWriteOnce | RWO | **하나의 노드**에서 읽기/쓰기 (가장 흔함, 블록 스토리지) |
| ReadOnlyMany | ROX | 여러 노드에서 읽기 전용 |
| ReadWriteMany | RWX | 여러 노드에서 동시 읽기/쓰기 (NFS/CephFS 등 특수) |

**reclaimPolicy** — PVC 를 삭제하면 PV(=실제 데이터)를 어떻게 할까:

| 정책 | 동작 |
|---|---|
| `Delete` | PVC 삭제 시 PV·실제 디스크까지 **자동 삭제** (동적 프로비저닝 기본값) |
| `Retain` | PVC 를 지워도 PV 는 `Released` 상태로 **남는다.** 데이터 보존, 수동 정리 필요 |

kind 에는 이미 동적 프로비저닝용 StorageClass 가 기본으로 들어 있다.

```bash
kubectl get storageclass
```

**실행 결과**
```
NAME                 PROVISIONER             RECLAIMPOLICY   VOLUMEBINDINGMODE      ALLOWVOLUMEEXPANSION   AGE
standard (default)   rancher.io/local-path   Delete          WaitForFirstConsumer   false                  17m
```

> 💡 **실무 팁**: 이 코스의 kind 클러스터는 기본 SC 이름이 `standard`(프로비저너 `rancher.io/local-path`)다. 클러스터마다 이름이 다르니(EKS 는 `gp2`/`gp3`, GKE 는 `standard-rwo` 등) `storageClassName` 을 하드코딩하기 전에 `kubectl get storageclass` 로 이름과 `(default)` 표시를 꼭 확인하자. `default` SC 가 있으면 PVC 에서 `storageClassName` 을 생략해도 그게 쓰인다.

---

## 10-5. 동적 프로비저닝 — PVC 하나로 PV 자동 생성

PVC 를 만들면 SC 가 PV 를 자동으로 찍어낸다. 단, 위 SC 의 `VOLUMEBINDINGMODE` 가 **`WaitForFirstConsumer`** 라는 점이 중요하다. 이건 "이 PVC 를 **실제로 마운트하는 파드가 뜨기 전까지는 바인딩하지 않고 기다린다**"는 뜻이다.

```yaml
# manifests/04-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: data-pvc
  namespace: step10
spec:
  accessModes:
    - ReadWriteOnce            # RWO
  storageClassName: standard  # kind 기본 SC (rancher.io/local-path)
  resources:
    requests:
      storage: 1Gi
```

```bash
kubectl apply -f manifests/04-pvc.yaml
kubectl get pvc -n step10
kubectl describe pvc data-pvc -n step10 | grep -A3 Events:
```

**실행 결과 (파드가 아직 없어서 Pending)**
```
NAME       STATUS    VOLUME   CAPACITY   ACCESS MODES   STORAGECLASS   VOLUMEATTRIBUTESCLASS   AGE
data-pvc   Pending                                      standard       <unset>                 3s

Events:
  Type    Reason                Age   From                         Message
  ----    ------                ----  ----                         -------
  Normal  WaitForFirstConsumer  3s    persistentvolume-controller  waiting for first consumer to be created before binding
```

> ⚠️ **함정 (WaitForFirstConsumer)**: PVC 를 만들었는데 계속 `Pending` 이라고 당황하지 말자. `WaitForFirstConsumer` SC 에서는 **정상 동작**이다. 이유는 "볼륨을 어느 노드에 만들지"를 파드가 스케줄된 노드에 맞춰 정하기 위해서다(로컬 디스크는 노드에 묶이므로). 파드를 붙이는 순간 바인딩된다. 반대로 SC 가 `Immediate` 모드면 PVC 생성 즉시 바인딩되지만, PV 가 만들어진 노드와 파드가 스케줄될 노드가 어긋나 파드가 못 뜨는 사고가 날 수 있다.

이제 이 PVC 를 마운트하는 파드를 띄우면 바인딩된다.

```yaml
# manifests/05-writer-pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: pvc-writer
  namespace: step10
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - name: data
          mountPath: /data
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: data-pvc
```

```bash
kubectl apply -f manifests/05-writer-pod.yaml
kubectl wait --for=condition=Ready pod/pvc-writer -n step10 --timeout=90s
kubectl get pvc,pv -n step10
```

**실행 결과 (파드가 뜨자 Bound + PV 자동 생성)**
```
NAME                             STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   AGE
persistentvolumeclaim/data-pvc   Bound    pvc-ce45096c-2a78-4f21-9416-8a4d6c6865b6   1Gi        RWO            standard       18s

NAME                                                        CAPACITY   ACCESS MODES   RECLAIM POLICY   STATUS   CLAIM             STORAGECLASS   AGE
persistentvolume/pvc-ce45096c-2a78-4f21-9416-8a4d6c6865b6   1Gi        RWO            Delete           Bound    step10/data-pvc   standard       1s
```

PVC 가 `Bound` 이 되었고, 아무도 손대지 않았는데 이름이 `pvc-ce45096c...` 인 PV 가 자동 생성됐다. 이것이 동적 프로비저닝이다. RECLAIM POLICY 는 `Delete`, CLAIM 은 `step10/data-pvc` 로 연결돼 있다.

---

## 10-6. PVC 는 영속이다 (직접 증명 — emptyDir 과의 결정적 차이)

10-2 와 정확히 같은 시나리오를 PVC 로 반복한다. 파드에 데이터를 쓰고, **파드를 삭제**하고, **완전히 새로운 파드**로 같은 PVC 를 마운트해서 읽는다. 이번엔 데이터가 살아있어야 한다.

```bash
# 1) writer 로 데이터 기록
kubectl exec -n step10 pvc-writer -- sh -c 'echo "persistent data written by pvc-writer" > /data/important.txt'

# 2) writer 파드 삭제
kubectl delete pod pvc-writer -n step10
kubectl get pvc data-pvc -n step10          # 파드가 없어도 PVC 는 Bound 유지

# 3) 완전히 다른 reader 파드로 같은 PVC 마운트
kubectl apply -f manifests/06-reader-pod.yaml
kubectl wait --for=condition=Ready pod/pvc-reader -n step10 --timeout=90s
kubectl exec -n step10 pvc-reader -- cat /data/important.txt
```

```yaml
# manifests/06-reader-pod.yaml (writer 와 동일한 claimName 을 마운트)
apiVersion: v1
kind: Pod
metadata:
  name: pvc-reader
  namespace: step10
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - name: data
          mountPath: /data
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: data-pvc      # writer 와 동일한 PVC
```

**실행 결과**
```
===== delete writer pod =====
pod "pvc-writer" deleted from step10 namespace
===== PVC still Bound after pod is gone (volume survives) =====
NAME       STATUS   VOLUME                                     CAPACITY   ACCESS MODES   STORAGECLASS   AGE
data-pvc   Bound    pvc-ce45096c-2a78-4f21-9416-8a4d6c6865b6   1Gi        RWO            standard       60s
===== start a brand-new reader pod on the SAME PVC =====
pod/pvc-reader created
pod/pvc-reader condition met
===== read the file the DELETED pod wrote =====
persistent data written by pvc-writer @ 2026년 7월 13일 월요일 11시 55분 40초 KST
==> 데이터 살아있음! PVC/PV 는 파드와 독립적으로 존재한다.
```

`pvc-writer` 는 삭제됐지만 PVC 는 계속 `Bound` 이고, 전혀 다른 파드 `pvc-reader` 가 그 파일을 그대로 읽었다. **이것이 emptyDir(10-2, 데이터 소실)과 PVC(영속)의 결정적 차이다.**

> 💡 **실무 팁**: 실제 서비스에서 PVC 를 붙인 워크로드는 보통 파드 하나짜리 Deployment 가 아니라 **StatefulSet**(다음 스텝)으로 운영한다. StatefulSet 은 파드마다 고유한 PVC 를 자동으로 만들어 주고(안정적인 스토리지 아이덴티티), 파드가 재생성돼도 같은 PVC 를 다시 붙여 준다.

---

## 정리 (표)

| 개념 | 한 줄 요약 | 수명 | 주의 |
|---|---|---|---|
| `emptyDir` | 파드에 딸린 임시 디렉터리 | 파드 삭제 시 소멸 | 상태 저장 금지 |
| `hostPath` | 노드 경로 직접 마운트 | 노드에 종속 | ⚠️ 보안·이식성 위험 |
| PV | 실제 저장 공간(클러스터 리소스) | 독립 (reclaimPolicy) | 보통 자동 생성 |
| PVC | 저장 공간 신청서(네임스페이스 리소스) | 독립, 파드와 무관 | 파드는 PVC 만 참조 |
| StorageClass | PV 자동 생성 공장 | — | 이름·default·bindingMode 확인 |
| accessMode | RWO / ROX / RWX | — | RWX 는 특수 스토리지 필요 |
| reclaimPolicy | Delete / Retain | — | 운영 데이터는 Retain 고려 |
| `WaitForFirstConsumer` | 파드 뜰 때까지 바인딩 지연 | — | PVC Pending 은 정상일 수 있음 |

---

## 연습 과제 → [challenge.md](challenge.md)

---

## 다음 단계
→ [Step 11 — 스테이트풀셋](../step-11-statefulset/)

---

## 실습 파일

이 스텝의 실습 파일은 `commands.sh` 하나와 `manifests/` 아래의 매니페스트 6개로 구성됩니다. `commands.sh` 를 한 번 실행하면 `step10` 네임스페이스를 만들고 `01` → `06` 순서대로 매니페스트를 적용하면서 본문 10-1 부터 10-6 까지의 시나리오(emptyDir 쓰기 → emptyDir 휘발성 증명 → hostPath → PVC 동적 프로비저닝 → PVC 영속성 증명)를 그대로 재현합니다. 매니페스트 번호는 그대로 본문 절 번호와 대응하므로, 한 절씩 손으로 따라가고 싶다면 해당 번호 파일만 `kubectl apply -f` 하면 됩니다.

### commands.sh

- Step 10 전체를 처음부터 끝까지 자동 재현하는 스크립트입니다. `set -euo pipefail` 로 중간에 하나라도 실패하면 즉시 멈추고, `cd "$(dirname "$0")"` 로 자기 위치로 이동하기 때문에 `manifests/...` 상대경로가 항상 맞습니다.
- 두 번째 줄의 `export PATH="/opt/homebrew/bin:$PATH"` 는 macOS(Apple Silicon) 에서 Homebrew 로 설치한 `kubectl` 을 찾기 위한 보정입니다. Linux 나 다른 경로에 `kubectl` 을 설치했다면 이 줄이 없어도 무방하며, 지워도 스크립트 동작에는 영향이 없습니다.
- 맨 처음 `kubectl config current-context` 로 컨텍스트를 출력합니다. **`kind-learn` 이 아니면 실행하지 마세요.** 이 스크립트는 마지막에 네임스페이스를 통째로 지웁니다.
- `kubectl create namespace step10 --dry-run=client -o yaml | kubectl apply -f -` 는 네임스페이스가 이미 있어도 에러 없이 넘어가게 하는 관용구입니다(멱등 생성). `set -e` 와 함께 쓰기 위한 장치입니다.
- 10-2 구간에서 `POD=$(kubectl get pod ... -o jsonpath='{.items[0].metadata.name}')` 로 첫 파드 이름을 잡아두고, 파일을 쓴 뒤 그 파드를 삭제하고, `rollout status` 로 새 파드가 뜰 때까지 기다린 다음 `NEWPOD` 이름을 다시 잡습니다. 마지막 `cat /data/note.txt || echo "==> note.txt GONE ..."` 의 `||` 가 핵심입니다. **파일이 없어서 `cat` 이 exit 1 로 실패하는 것이 정상 결과**이며, `||` 덕분에 `set -e` 에 걸려 스크립트가 죽지 않습니다.
- 10-4/5 구간에서는 PVC 적용 후 `sleep 3` 하고 `kubectl get pvc` 를 찍어 **Pending 상태를 일부러 보여준 뒤** writer 파드를 띄웁니다. `WaitForFirstConsumer` 의 동작을 눈으로 확인시키려는 의도적인 순서이므로, 줄을 재배치하면 학습 포인트가 사라집니다.
- 마지막 `kubectl delete namespace step10` 은 **파괴적 명령**입니다. 네임스페이스와 함께 PVC 가 지워지고, SC 의 reclaimPolicy 가 `Delete` 이므로 PV 와 실제 데이터까지 자동 삭제됩니다. 실습 결과를 더 들여다볼 생각이라면 이 줄에 도달하기 전에 중단하세요. 바로 뒤의 `kubectl get pv | grep step10 || echo "OK: step10 PV 없음"` 이 잔여 PV 가 없음을 확인해 줍니다.

```bash file="./commands.sh"
```

### manifests/01-emptydir-pod.yaml

- 본문 **10-1** 에서 적용하는 첫 매니페스트입니다. `busybox:1.36` 컨테이너 하나가 `sleep 3600` 으로 떠 있기만 하고, 실제 작업은 `kubectl exec` 로 사람이 직접 넣습니다.
- `volumes` 의 `emptyDir: {}` 가 파드가 노드에 스케줄될 때 **빈 디렉터리**를 만들고, `volumeMounts` 의 `mountPath: /data` 가 그 디렉터리를 컨테이너 안 `/data` 에 붙입니다. 두 곳의 `name: scratch` 가 같아야 연결됩니다 — 볼륨 정의와 마운트를 잇는 유일한 열쇠입니다.
- `emptyDir: {}` 는 빈 객체이므로 노드 디스크를 사용합니다. `medium: Memory` 를 넣으면 tmpfs(RAM)가 되지만 파드 메모리 리밋에 포함된다는 점을 본문 팁에서 짚었습니다.
- 이 파드만으로는 "쓰고 읽기"까지만 확인되고 휘발성은 드러나지 않습니다. 그 증명은 다음 파일(`02-emptydir-deploy.yaml`)의 몫입니다.

```yaml file="./manifests/01-emptydir-pod.yaml"
```

### manifests/02-emptydir-deploy.yaml

- 본문 **10-2**, emptyDir 이 휘발성임을 증명하기 위한 파일입니다. 볼륨 설정은 `01` 과 완전히 동일하고, 감싸는 리소스만 Pod → Deployment 로 바뀌었습니다.
- Deployment 로 감싼 이유가 실습의 전부입니다. `replicas: 1` 이므로 파드를 지우면 컨트롤러가 **즉시 새 파드를 재생성**해 주고, 그 새 파드는 새 `emptyDir` 을 받습니다. 수동 Pod 였다면 지운 뒤 아무것도 안 남아 비교 대상이 없습니다.
- `selector.matchLabels.app: emptydir-web` 과 `template.metadata.labels.app: emptydir-web` 이 일치해야 하며, `commands.sh` 의 `-l app=emptydir-web` 라벨 셀렉터도 이 값에 의존합니다.
- 파드 이름이 `emptydir-web-...-c8p2x` → `...-jtvjp` 로 바뀌고 새 파드의 `/data` 가 비어 있는 것이 기대 결과입니다. 참고로 **컨테이너만 재시작**되는 경우에는 파드가 그대로이므로 emptyDir 데이터가 유지됩니다 — "재시작"과 "재생성"의 차이입니다.

```yaml file="./manifests/02-emptydir-deploy.yaml"
```

### manifests/03-hostpath-pod.yaml

- 본문 **10-3** 의 hostPath 데모입니다. `hostPath.path: /tmp/k8s-hostpath-demo` 는 파드가 아니라 **노드(kind 워커 컨테이너) 안의 경로**이고, 이것이 컨테이너의 `/host-data` 로 붙습니다.
- `type: DirectoryOrCreate` 는 그 경로가 노드에 없으면 만들어 주라는 뜻입니다. `Directory` 로 두면 경로가 없을 때 파드가 뜨지 못합니다.
- 경로를 `/tmp/k8s-hostpath-demo` 로 한정한 것은 의도적인 안전장치입니다. 파일 주석에도 "민감 경로 금지"라고 못 박아 두었습니다. `/`, `/etc`, `/var/run/docker.sock` 를 마운트하면 파드가 노드를 장악할 수 있는 컨테이너 탈출 경로가 됩니다.
- 실행 후 `kubectl get pod hostpath-demo -o wide` 로 어느 노드에 떴는지 확인하는 것이 포인트입니다. 데이터는 **그 노드에만** 존재하므로, 파드가 다른 노드로 재스케줄되면 파일이 사라진 것처럼 보입니다(challenge.md 과제 5 의 사고 시나리오).

```yaml file="./manifests/03-hostpath-pod.yaml"
```

### manifests/04-pvc.yaml

- 본문 **10-4 / 10-5** 의 주인공입니다. 실제 디스크가 아니라 "1Gi 짜리 RWO 볼륨을 달라"는 **신청서(PVC)** 하나만 선언합니다. PV 는 아무도 만들지 않습니다 — StorageClass 가 알아서 찍어냅니다.
- `accessModes: [ReadWriteOnce]` 는 하나의 노드에서만 읽기/쓰기가 가능하다는 뜻입니다. 로컬 디스크 기반인 `rancher.io/local-path` 프로비저너에서는 사실상 유일한 선택지이며, 이 제약이 challenge 과제 3(c)의 Multi-Attach 문제로 이어집니다.
- `storageClassName: standard` 는 kind 기본 SC 이름입니다. default SC 이므로 사실 생략해도 되지만, 클러스터마다 이름이 다르다는 것(EKS `gp3`, GKE `standard-rwo`)을 의식시키려고 일부러 명시했습니다.
- 이 파일만 apply 하면 PVC 는 **`Pending` 에 머무릅니다.** `standard` SC 의 `volumeBindingMode` 가 `WaitForFirstConsumer` 이기 때문이며, 이는 버그가 아니라 정상입니다. 다음 파일(`05-writer-pod.yaml`)이 뜨는 순간 `Bound` 로 바뀌고 PV 가 자동 생성됩니다.

```yaml file="./manifests/04-pvc.yaml"
```

### manifests/05-writer-pod.yaml

- 본문 **10-5** 에서 PVC 를 처음으로 **소비(consume)** 하는 파드입니다. 즉 `WaitForFirstConsumer` 가 기다리던 바로 그 "first consumer" 입니다.
- `volumes` 블록이 `01` 과 결정적으로 다릅니다. `emptyDir: {}` 대신 `persistentVolumeClaim.claimName: data-pvc` 를 씁니다. **파드는 PV 를 직접 참조하지 않고 오직 PVC 이름만 압니다.** 이 간접 참조가 스토리지 구현체와 워크로드를 분리해 줍니다.
- 이 파드가 스케줄되는 순간 스케줄러가 노드를 정하고, local-path 프로비저너가 그 노드에 PV 를 만들고, PVC 가 `Bound` 로 바뀝니다. `kubectl get pvc,pv` 를 찍으면 `pvc-ce45096c...` 같은 자동 생성 PV 가 보입니다.
- 이름은 writer 지만 매니페스트 자체는 `sleep 3600` 만 합니다. 실제 파일 쓰기는 `commands.sh` 의 `kubectl exec ... 'echo "persistent data..." > /data/important.txt'` 가 담당합니다. 이 파일을 쓴 뒤 파드를 지우는 것이 10-6 시나리오의 시작입니다.

```yaml file="./manifests/05-writer-pod.yaml"
```

### manifests/06-reader-pod.yaml

- 본문 **10-6**, PVC 영속성 증명의 마지막 조각입니다. `pvc-writer` 를 **삭제한 뒤에** 적용합니다. 순서가 바뀌면 RWO 볼륨을 두 파드가 동시에 요구하게 되어 의미가 흐려집니다.
- 파드 이름(`pvc-reader`)만 다를 뿐, `claimName: data-pvc` 가 writer 와 **완전히 동일**합니다. 즉 이전 파드가 쓰던 그 볼륨을 그대로 다시 붙입니다.
- 기대 결과는 `cat /data/important.txt` 가 삭제된 writer 파드가 남긴 문장을 그대로 출력하는 것입니다. 10-2 의 emptyDir 에서 `cat` 이 `No such file or directory` 로 실패했던 것과 정확히 대비되며, 이 한 줄이 이 스텝 전체의 결론입니다.
- 파드는 사라져도 PVC/PV 는 남습니다. writer 삭제 직후 `kubectl get pvc` 를 찍어 보면 여전히 `Bound` 인 것을 볼 수 있습니다 — 볼륨의 수명이 파드와 독립적이라는 증거입니다.

```yaml file="./manifests/06-reader-pod.yaml"
```
