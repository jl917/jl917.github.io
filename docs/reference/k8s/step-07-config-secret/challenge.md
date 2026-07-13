# Step 07 연습 과제

> `step07` 네임스페이스에서 진행합니다. 시작 전:
> ```bash
> kubectl apply -f manifests/config.yaml -f manifests/consumer.yaml
> kubectl rollout status deployment/app -n step07
> ```

---

### 과제 1. 명령형으로 ConfigMap/Secret 만들기

`--dry-run=client -o yaml` 을 이용해, `LOG_LEVEL=debug` 키를 가진 ConfigMap 과 `TOKEN=abc123` 을 가진 Secret 의 YAML 을 실제 생성 없이 출력하세요.

<details>
<summary>해답</summary>

```bash
kubectl create configmap my-cm --from-literal=LOG_LEVEL=debug -n step07 --dry-run=client -o yaml
kubectl create secret generic my-sec --from-literal=TOKEN=abc123 -n step07 --dry-run=client -o yaml
```
Secret 출력의 `data.TOKEN` 은 `YWJjMTIz`(base64)로 나옵니다. `--dry-run=client` 는 서버에 아무것도 만들지 않고 매니페스트만 보여줍니다.
</details>

---

### 과제 2. Secret 평문 훔쳐보기

`db-secret` 의 `DB_USER` 값을 base64 디코딩해 평문으로 확인하세요. 이 실습이 알려주는 보안 교훈은?

<details>
<summary>해답</summary>

```bash
kubectl get secret db-secret -n step07 -o jsonpath='{.data.DB_USER}' | base64 -d; echo   # admin
```
Secret 을 읽을 권한만 있으면 평문을 그대로 볼 수 있습니다. base64 는 암호화가 아닙니다. 진짜 보호는 RBAC(접근 최소화), etcd 저장 암호화, 외부 시크릿 관리(Vault 등)로 합니다.
</details>

---

### 과제 3. env vs 볼륨, 변경 반영 차이 (핵심)

`app-config` 의 `APP_MODE` 를 `production` → `debug` 로 바꾼 뒤, (a) 환경변수와 (b) 볼륨 파일이 각각 어떻게 되는지 관찰하고 차이를 설명하세요.

<details>
<summary>해답</summary>

```bash
POD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
kubectl patch configmap app-config -n step07 -p '{"data":{"APP_MODE":"debug"}}'
kubectl exec -n step07 $POD -- sh -c 'echo $APP_MODE'          # 여전히 production (env 는 고정)
sleep 60
kubectl exec -n step07 $POD -- cat /etc/config/APP_MODE        # debug (볼륨은 갱신)
```
환경변수는 컨테이너 시작 시 한 번만 주입되어 이후 변하지 않습니다. 볼륨 마운트 파일은 kubelet 이 주기적으로 동기화해 수십 초 내 갱신됩니다(단 앱이 다시 읽어야 실제 반영).
</details>

---

### 과제 4. 설정 변경을 실제로 반영시키기

과제 3에서 바꾼 `APP_MODE=debug` 를 실행 중 파드의 **환경변수에도** 반영시키세요.

<details>
<summary>해답</summary>

```bash
kubectl rollout restart deployment/app -n step07
kubectl rollout status deployment/app -n step07
NEWPOD=$(kubectl get pod -n step07 -l app=app -o name | head -1)
kubectl exec -n step07 $NEWPOD -- sh -c 'echo $APP_MODE'       # debug
```
환경변수를 새 값으로 반영하는 유일한 방법은 **파드 재생성**입니다. `rollout restart` 가 무중단으로 이를 수행합니다.
</details>

---

### 과제 5. immutable 로 잠근 뒤 값 바꾸기

immutable ConfigMap `locked-config` 의 `VERSION` 을 `2.0.0` 으로 바꾸려면 어떻게 해야 하나요? 직접 해보세요.

<details>
<summary>해답</summary>

immutable 은 patch/apply 로 `data` 변경이 거부됩니다. **삭제 후 재생성**만 가능합니다.
```bash
kubectl patch configmap locked-config -n step07 -p '{"data":{"VERSION":"2.0.0"}}'   # Forbidden
kubectl delete configmap locked-config -n step07
kubectl create configmap locked-config -n step07 --from-literal=VERSION=2.0.0
# (다시 immutable 로 만들려면 매니페스트에 immutable: true 를 넣어 apply)
```
그래서 실무에선 immutable 설정을 바꿀 때 **이름에 버전을 넣어 새 오브젝트로** 만들고, 파드가 그걸 참조하도록 롤아웃합니다.
</details>
