# Step 01 연습 과제

전용 네임스페이스 `step01`에서 실습하세요. 끝나면 `kubectl delete ns step01`.

---

### 과제 1. 클러스터 파악
이 클러스터의 노드는 몇 개이며, 각 노드의 역할(ROLES)과 Kubernetes 버전은 무엇인가?

<details><summary>해답</summary>

```bash
kubectl get nodes
```
control-plane 1개(learn-control-plane) + worker 2개(learn-worker, learn-worker2), 버전 v1.36.1.
버전만 보려면:
```bash
kubectl version
```
</details>

---

### 과제 2. control-plane 구성요소 확인
Kubernetes의 두뇌인 control-plane 컴포넌트(apiserver, etcd, scheduler, controller-manager)가 실제로 파드로 떠 있습니다. 찾아보세요.

<details><summary>해답</summary>

```bash
kubectl get pods -n kube-system
```
`etcd-...`, `kube-apiserver-...`, `kube-scheduler-...`, `kube-controller-manager-...`가 보입니다.
이들이 어느 노드에 있는지:
```bash
kubectl get pods -n kube-system -o wide | grep control-plane
```
전부 control-plane 노드에 있습니다. Step 02에서 각각의 역할을 배웁니다.
</details>

---

### 과제 3. 명령형으로 파드 띄우고 삭제
`hello`라는 이름으로 `hashicorp/http-echo:1.0` 이미지를 명령형으로 띄우되, `-text="hi"` 인자를 주세요. 그리고 로그를 확인한 뒤 삭제하세요.

<details><summary>해답</summary>

```bash
kubectl create ns step01
kubectl -n step01 run hello --image=hashicorp/http-echo:1.0 -- -text=hi
kubectl -n step01 get pod hello
kubectl -n step01 logs hello        # "Server is listening..." 로그
kubectl -n step01 delete pod hello
```
`--` 뒤는 컨테이너에 전달하는 실행 인자입니다.
</details>

---

### 과제 4. 선언형으로 바꾸기
과제 3의 파드를 YAML로 선언해 `apply`로 띄우세요. (힌트: `--dry-run=client -o yaml`로 초안을 뽑아 다듬기)

<details><summary>해답</summary>

```bash
kubectl -n step01 run hello --image=hashicorp/http-echo:1.0 \
  --dry-run=client -o yaml -- -text=hi > hello.yaml
kubectl apply -f hello.yaml
# 같은 명령 반복 → unchanged (멱등)
kubectl apply -f hello.yaml
```
</details>

---

### 과제 5. 안전 습관
지금 이 순간 `kubectl delete`를 치면 어느 클러스터에 실행되는지 한 명령으로 확인하세요. 왜 이 습관이 중요한가?

<details><summary>해답</summary>

```bash
kubectl config current-context
```
`kind-learn`이 나와야 안전합니다. 실무에서는 여러 클러스터(dev/prod)가 등록돼 있어, 운영 클러스터인 줄 모르고 `delete`를 쳐 장애를 내는 사고가 흔합니다. 파괴적 명령 전에는 항상 컨텍스트를 확인하고, `kube-ps1` 등으로 프롬프트에 상시 표시하세요.
</details>

---

정리: `kubectl delete namespace step01`
