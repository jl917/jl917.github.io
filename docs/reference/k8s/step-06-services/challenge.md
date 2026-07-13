# Step 06 연습 과제

> `step06` 네임스페이스에서 진행합니다. 시작 전:
> ```bash
> kubectl apply -f manifests/backend.yaml -f manifests/services.yaml
> kubectl run client --image=busybox:1.36 -n step06 --restart=Never --command -- sleep 3600
> ```

---

### 과제 1. 정식 이름 vs 짧은 이름

`client` 파드에서 `hello-clusterip` 서비스를 (a) 짧은 이름 (b) 정식 FQDN 두 방식으로 각각 조회하세요. 다른 네임스페이스에서 부른다면 무엇을 써야 하나요?

<details>
<summary>해답</summary>

```bash
kubectl exec -n step06 client -- wget -qO- http://hello-clusterip/           # (a) 같은 ns 라 가능
kubectl exec -n step06 client -- wget -qO- http://hello-clusterip.step06.svc.cluster.local/  # (b)
```
다른 네임스페이스에서는 최소 `hello-clusterip.step06` 처럼 **네임스페이스를 붙여야** 합니다. 짧은 이름은 같은 네임스페이스 안에서만 해석됩니다.
</details>

---

### 과제 2. ClusterIP vs Headless DNS 비교

`hello-clusterip` 와 `hello-headless` 를 각각 `nslookup` 하고, 반환되는 주소 개수가 왜 다른지 설명하세요.

<details>
<summary>해답</summary>

```bash
kubectl exec -n step06 client -- nslookup hello-clusterip.step06.svc.cluster.local  # 주소 1개
kubectl exec -n step06 client -- nslookup hello-headless.step06.svc.cluster.local   # 주소 3개(파드 IP)
```
ClusterIP 는 **가상 IP 하나**를 반환하고 트래픽 분산은 커널이 합니다. Headless(`clusterIP: None`)는 가상 IP 가 없어 DNS 가 **뒤의 모든 파드 IP** 를 직접 나열합니다.
</details>

---

### 과제 3. Endpoints 로 로드밸런싱 대상 확인

`hello-clusterip` 의 Endpoints 에 파드가 몇 개 물려 있나요? Deployment 를 replicas=1 로 줄인 뒤 Endpoints 가 어떻게 변하는지 관찰하세요.

<details>
<summary>해답</summary>

```bash
kubectl get endpoints hello-clusterip -n step06        # IP 3개
kubectl scale deployment/hello -n step06 --replicas=1
kubectl get endpoints hello-clusterip -n step06        # IP 1개로 줄어듦
kubectl scale deployment/hello -n step06 --replicas=3  # 원복
```
Endpoints 는 "준비된 파드"만 담으므로 파드 개수를 따라갑니다. 서비스는 항상 살아있는 파드로만 트래픽을 보냅니다.
</details>

---

### 과제 4. 셀렉터 오타 직접 만들고 고치기 (핵심)

새 ClusterIP 서비스 `myweb` 을 셀렉터 `app: h-e-l-l-o`(틀린 값)로 만들어 트래픽이 0 임을 확인하고, 진단 → 수리하세요.

<details>
<summary>해답</summary>

```bash
kubectl create service clusterip myweb -n step06 --tcp=80:8080
kubectl set selector service myweb -n step06 'app=wrong'   # 일부러 틀리게
kubectl get endpoints myweb -n step06        # <none>  ← 트래픽 0
# 진단: 셀렉터와 실제 파드 레이블 비교
kubectl get svc myweb -n step06 -o jsonpath='{.spec.selector}'; echo
kubectl get pods -n step06 --show-labels | grep hello
# 수리
kubectl set selector service myweb -n step06 'app=hello'
kubectl get endpoints myweb -n step06        # 채워짐
```
서비스가 먹통일 때 **가장 먼저 볼 것은 `kubectl get endpoints <svc>`** 입니다. 비어있으면 셀렉터 불일치입니다.
</details>

---

### 과제 5. LoadBalancer 는 왜 pending 인가

`hello-lb` 의 `EXTERNAL-IP` 가 `pending` 인 이유를 한 줄로 설명하고, pending 상태에서도 접근 가능한 경로 두 가지를 대세요.

<details>
<summary>해답</summary>

kind 클러스터에는 외부 IP 를 발급해 줄 **클라우드 LB 프로바이더가 없기** 때문입니다. 그래도 LoadBalancer 는 내부적으로 ClusterIP + NodePort 를 함께 만들므로:
1. 클러스터 내부에서 ClusterIP(`hello-lb`) 로 접근 가능
2. NodePort(예: `80:31609`)의 노드 포트로 접근 가능

```bash
kubectl get svc hello-lb -n step06         # PORT(S) 의 80:XXXXX 가 NodePort
kubectl exec -n step06 client -- wget -qO- http://hello-lb/ | grep -A1 pod:
```
</details>
