# Step 17 연습 과제 — Helm

> 실습 네임스페이스: `step17`. 모두 끝나면 `helm uninstall` 후 `kubectl delete ns step17`.

---

## 과제 1. `helm template`으로 렌더 결과 예측하기

`replicaCount=5`, `page.message="quiz"`로 렌더했을 때 Deployment의 `replicas`와 index.html의 `<h1>`이 어떻게 나올지, **클러스터에 설치하지 않고** 확인하세요.

<details><summary>해답</summary>

```bash
helm template quiz ./chart --set replicaCount=5 --set page.message="quiz" \
  | grep -E "replicas:|<h1>"
```
```
replicas: 5
    <h1>quiz</h1>
```
`--dry-run`은 클러스터 API에 접속해 검증까지 하지만, `helm template`은 완전히 로컬입니다.
</details>

---

## 과제 2. `-f`와 `--set`의 우선순위

`values-prod.yaml`에는 `replicaCount: 3`이 있습니다. 아래 명령의 결과 replica는 몇일까요?

```bash
helm template x ./chart -f ./chart/values-prod.yaml --set replicaCount=7 | grep "replicas:"
```

<details><summary>해답</summary>

`7`입니다. 우선순위는 `values.yaml`(2) < `-f`(3) < `--set`(7). 뒤로 갈수록 이깁니다.
</details>

---

## 과제 3. named template 추가하기

`_helpers.tpl`에 `webapp.image`라는 named template을 만들어 `"{{ .Values.image.repository }}:{{ .Values.image.tag }}"`를 반환하게 하고, `deployment.yaml`에서 `include`로 사용하세요.

<details><summary>해답</summary>

`_helpers.tpl`에 추가:
```
{{- define "webapp.image" -}}
{{ .Values.image.repository }}:{{ .Values.image.tag }}
{{- end -}}
```
`deployment.yaml`의 image 줄을 교체:
```yaml
          image: "{{ include "webapp.image" . }}"
```
`helm template demo ./chart | grep image:`로 `nginx:1.27-alpine`이 그대로 나오는지 확인.
</details>

---

## 과제 4. `if`로 Service 타입 토글

`values.yaml`에 `service.nodePort` 값을 추가하고, `service.type`이 `NodePort`일 때만 `nodePort` 필드가 렌더링되도록 `service.yaml`을 수정하세요.

<details><summary>해답</summary>

`service.yaml`의 ports 아래:
```yaml
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: 80
      {{- if eq .Values.service.type "NodePort" }}
      nodePort: {{ .Values.service.nodePort }}
      {{- end }}
```
확인:
```bash
helm template x ./chart --set service.type=NodePort --set service.nodePort=30080 | grep -A1 nodePort
helm template x ./chart | grep nodePort   # ClusterIP 이면 아무것도 안 나옴
```
</details>

---

## 과제 5. 롤백 시나리오

`replicaCount=10`으로 잘못 업그레이드했다고 가정하고, (1) 현재 리비전을 확인한 뒤 (2) 바로 직전 리비전으로 롤백하고 (3) 이력을 확인하세요.

<details><summary>해답</summary>

```bash
helm install web ./chart -n step17 --create-namespace --wait   # rev1
helm upgrade web ./chart -n step17 --set replicaCount=10 --wait # rev2 (실수)
helm history web -n step17                                      # 현재 rev2 deployed
helm rollback web 1 -n step17 --wait                            # rev3 = "Rollback to 1"
kubectl get deploy web-webapp -n step17 -o jsonpath='{.spec.replicas}'; echo   # 2
helm history web -n step17
```
`helm rollback web 0`처럼 `0`을 주면 "바로 이전 리비전"으로 롤백됩니다(리비전 번호를 몰라도 됨).
</details>
