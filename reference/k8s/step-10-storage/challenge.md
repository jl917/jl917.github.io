# Step 10 연습 과제 — 스토리지

각 문제를 먼저 직접 풀어보고, 아래 **정답**을 펼쳐 확인하세요. 모든 실습은 `step10` 네임스페이스에서 진행합니다.

---

## 과제 1 — emptyDir 로 컨테이너 간 파일 공유

하나의 파드 안에 컨테이너 2개(`writer`, `reader`)를 두고, 둘 다 같은 `emptyDir` 볼륨을 `/shared` 에 마운트하세요. `writer` 가 `/shared/msg.txt` 에 글을 쓰면 `reader` 에서도 읽혀야 합니다. 왜 이게 가능한가요?

<details>
<summary>정답 보기</summary>

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: shared-emptydir
  namespace: step10
spec:
  containers:
    - name: writer
      image: busybox:1.36
      command: ["sh", "-c", "echo hi-from-writer > /shared/msg.txt; sleep 3600"]
      volumeMounts:
        - { name: shared, mountPath: /shared }
    - name: reader
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - { name: shared, mountPath: /shared }
  volumes:
    - name: shared
      emptyDir: {}
```

```bash
kubectl apply -f shared-emptydir.yaml
kubectl exec -n step10 shared-emptydir -c reader -- cat /shared/msg.txt
# => hi-from-writer
```

**이유**: `emptyDir` 볼륨은 **파드 단위**로 생성되어 그 파드 안의 모든 컨테이너가 같은 디렉터리를 공유합니다. 컨테이너별로 파일시스템이 격리돼 있어도, 마운트된 볼륨은 하나의 실체를 가리킵니다. (사이드카 패턴의 기본 원리)
</details>

---

## 과제 2 — 왜 PVC 가 계속 Pending 인가

동료가 이렇게 물어봅니다. "PVC 를 만들었는데 몇 분째 `Pending` 이야. StorageClass 도 있고 default 도 맞는데 왜 바인딩이 안 돼?" 무엇을 먼저 확인해야 하며, 이게 정상일 수도 있는 이유는?

<details>
<summary>정답 보기</summary>

먼저 SC 의 바인딩 모드를 확인합니다.

```bash
kubectl get storageclass
# VOLUMEBINDINGMODE 열을 본다
kubectl describe pvc <이름> -n step10 | grep -A3 Events:
# "WaitForFirstConsumer" 이벤트가 보이면 정상 대기 상태
```

`VOLUMEBINDINGMODE` 가 **`WaitForFirstConsumer`** 라면, PVC 는 **그것을 마운트하는 파드가 스케줄되기 전까지 일부러 Pending 으로 대기**합니다. 이건 버그가 아니라 설계입니다. 로컬 디스크처럼 노드에 묶이는 볼륨을 "파드가 갈 노드"에 맞춰 생성하기 위해서입니다.

해결: 그 PVC 를 `volumes` 로 참조하는 파드를 하나 띄우면 즉시 `Bound` 됩니다. (반대로 `Immediate` 모드면 PVC 생성 즉시 바인딩됩니다.)
</details>

---

## 과제 3 — emptyDir vs PVC, 재배포 후 데이터

같은 앱을 replicas=1 Deployment 로 운영 중입니다. 사용자 업로드 파일을 저장해야 합니다. (a) `emptyDir` 에 저장하면 `kubectl rollout restart` 후 파일은 어떻게 되나요? (b) PVC 에 저장하면? (c) 그렇다면 왜 PVC + replicas=1 Deployment 조합을 실무에서 잘 안 쓰고 StatefulSet 을 쓸까요?

<details>
<summary>정답 보기</summary>

- **(a) emptyDir**: `rollout restart` 는 새 파드를 만들고 기존 파드를 삭제합니다. 새 파드의 emptyDir 은 빈 상태 → **업로드 파일 전부 소실**. (본문 10-2 에서 증명한 그대로)
- **(b) PVC**: 새 파드가 같은 PVC 를 다시 마운트하므로 **데이터 생존**. (본문 10-6 에서 증명)
- **(c)** replicas=1 + RWO PVC 는 롤링 업데이트 시 위험합니다. 기본 롤링 전략은 새 파드를 먼저 띄우고(maxSurge) 옛 파드를 내리는데, RWO 볼륨은 한 노드에서만 붙일 수 있어 **새 파드가 볼륨을 못 붙여 Pending** 에 걸릴 수 있습니다(Multi-Attach 에러). StatefulSet 은 파드별 고유 PVC(`volumeClaimTemplates`)와 순차 교체로 이 문제를 깔끔히 다룹니다. → Step 11.
</details>

---

## 과제 4 — reclaimPolicy 실험 (Delete vs Retain)

kind 기본 SC 의 reclaimPolicy 는 `Delete` 입니다. PVC 를 삭제하면 PV 와 실제 데이터까지 사라집니다. 운영 DB 라면 실수로 PVC 를 지워도 데이터가 남길 바랍니다. 어떻게 하면 될까요? (개념 + 명령)

<details>
<summary>정답 보기</summary>

두 가지 접근이 있습니다.

1. **PV 의 reclaimPolicy 를 Retain 으로 패치** (이미 존재하는 PV):
   ```bash
   kubectl patch pv <PV이름> -p '{"spec":{"persistentVolumeReclaimPolicy":"Retain"}}'
   ```
   이후 PVC 를 삭제해도 PV 는 `Released` 상태로 남고 실제 데이터도 보존됩니다. 다만 그 PV 를 다시 쓰려면 `claimRef` 를 수동으로 비워줘야 합니다(수동 재활용).

2. **Retain 을 기본으로 하는 별도 StorageClass 를 만들어** 그 SC 로 PVC 를 생성:
   ```yaml
   apiVersion: storage.k8s.io/v1
   kind: StorageClass
   metadata:
     name: standard-retain
   provisioner: rancher.io/local-path
   reclaimPolicy: Retain
   volumeBindingMode: WaitForFirstConsumer
   ```

**함정**: `Retain` 은 데이터를 지켜주지만, 지운 PVC 만큼 PV 가 `Released` 로 계속 쌓입니다. 정기적으로 수동 정리하지 않으면 디스크가 새듯 찹니다. "안전"과 "관리 비용"의 트레이드오프입니다.
</details>

---

## 과제 5 — hostPath 의 함정 재현 (사고 시나리오)

멀티노드 클러스터에서 `hostPath: /data/uploads` 에 업로드를 저장하는 파드를 운영합니다. 처음엔 잘 되다가, 어느 날 파드가 재스케줄된 뒤 "파일이 사라졌다"는 신고가 들어옵니다. 무슨 일이 일어난 걸까요? 어떻게 고쳐야 하나요?

<details>
<summary>정답 보기</summary>

hostPath 는 **파드가 실행 중인 노드의 로컬 경로**를 가리킵니다. 처음엔 `learn-worker` 에서 `/data/uploads` 에 파일을 쌓았는데, 노드 장애·드레인·재스케줄로 파드가 `learn-worker2` 로 옮겨가면 그 노드의 `/data/uploads` 는 **텅 빈 다른 디렉터리**입니다. 데이터가 "사라진" 게 아니라 **다른 노드에 남아 있고 새 파드는 그걸 못 봅니다.**

**수정**: hostPath 대신 **PVC(동적 프로비저닝)** 를 사용합니다. PVC/PV 는 파드나 노드 위치와 독립적인 스토리지 아이덴티티를 제공하므로, 파드가 어디로 재스케줄되든 같은 볼륨을 다시 붙일 수 있습니다. (진짜 여러 노드에서 동시에 써야 하면 RWX 지원 스토리지(NFS/CephFS 등)를 씁니다.)

**교훈**: hostPath 로 "영속"을 흉내내면 단일 노드에서는 되는 듯 보이다가 멀티노드에서 반드시 터집니다.
</details>
