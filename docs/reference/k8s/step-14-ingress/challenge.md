# Step 14 연습 과제 — 인그레스

> 전제: `ingress-nginx` 컨트롤러가 떠 있고, `step14` 네임스페이스에 tea/coffee/hello 서비스가 있으며,
> `kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 18080:80 18443:443` 로 접근한다고 가정.

---

## 과제 1. 경로 + 호스트 혼합

`shop.example.com` 에서 `/tea` 는 tea, `/coffee` 는 coffee 로 보내고,
같은 컨트롤러에서 `blog.example.com/` 은 hello 로 보내는 Ingress 를 **하나의 오브젝트**로 작성하라.

<details><summary>해답</summary>

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: mixed
  namespace: step14
spec:
  ingressClassName: nginx
  rules:
    - host: shop.example.com
      http:
        paths:
          - { path: /tea,    pathType: Prefix, backend: { service: { name: tea,    port: { number: 80 } } } }
          - { path: /coffee, pathType: Prefix, backend: { service: { name: coffee, port: { number: 80 } } } }
    - host: blog.example.com
      http:
        paths:
          - { path: /, pathType: Prefix, backend: { service: { name: hello, port: { number: 80 } } } }
```
검증:
```bash
curl -H "Host: shop.example.com" http://localhost:18080/tea       # tea
curl -H "Host: shop.example.com" http://localhost:18080/coffee    # coffee
curl -H "Host: blog.example.com" http://localhost:18080/ | grep -o "Hello from the hello service"
```
</details>

---

## 과제 2. rewrite-target 로 경로 벗겨 넘기기

`/api/tea` 로 들어온 요청의 앞 `/api` 를 떼고 백엔드에는 `/tea` 만 보이게 하고 싶다.
`nginx.ingress.kubernetes.io/rewrite-target` 과 캡처 그룹(`(.*)`) 을 써서 구성하라.
(http-echo 는 경로와 무관하게 응답하므로, 실제 경로 재작성 여부는 실서비스에서 확인한다는 점을 명시하라.)

<details><summary>해답</summary>

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: rewrite-demo
  namespace: step14
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
spec:
  ingressClassName: nginx
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /api(/|$)(.*)
            pathType: ImplementationSpecific
            backend: { service: { name: tea, port: { number: 80 } } }
```
- `rewrite-target: /$2` 는 정규식 두 번째 캡처 그룹(`(.*)`)을 백엔드 경로로 사용.
- 정규식 경로에는 `pathType: ImplementationSpecific` 를 쓴다.
```bash
curl -H "Host: api.example.com" http://localhost:18080/api/tea   # tea (백엔드는 /tea 를 받음)
```
</details>

---

## 과제 3. 다중 호스트 TLS

`a.example.com` 과 `b.example.com` 두 호스트를 각각 tea, coffee 로 보내되, **둘 다 HTTPS** 로 서비스하라.
하나의 인증서(SAN 에 두 호스트 포함)로 처리한다.

<details><summary>해답</summary>

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /tmp/multi.key -out /tmp/multi.crt \
  -subj "/CN=a.example.com/O=step14" \
  -addext "subjectAltName=DNS:a.example.com,DNS:b.example.com"
kubectl create secret tls multi-tls -n step14 --cert=/tmp/multi.crt --key=/tmp/multi.key
```
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: multi-tls, namespace: step14 }
spec:
  ingressClassName: nginx
  tls:
    - hosts: [a.example.com, b.example.com]
      secretName: multi-tls
  rules:
    - host: a.example.com
      http: { paths: [ { path: /, pathType: Prefix, backend: { service: { name: tea,    port: { number: 80 } } } } ] }
    - host: b.example.com
      http: { paths: [ { path: /, pathType: Prefix, backend: { service: { name: coffee, port: { number: 80 } } } } ] }
```
```bash
curl -k --resolve a.example.com:18443:127.0.0.1 https://a.example.com:18443/   # tea
curl -k --resolve b.example.com:18443:127.0.0.1 https://b.example.com:18443/   # coffee
```
</details>

---

## 과제 4. (함정 진단) 502/404 원인 찾기

동료가 만든 아래 Ingress 가 `curl` 하면 계속 **404** 가 난다. 원인 2가지를 찾아 고쳐라.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: broken, namespace: step14 }
spec:
  # (a)
  rules:
    - host: broken.example.com
      http:
        paths:
          - path: /tea
            pathType: Prefix
            backend:
              service:
                name: tea
                port:
                  number: 5678   # (b)
```

<details><summary>해답</summary>

- **(a) `ingressClassName: nginx` 누락.** 기본 IngressClass 가 없으면 아무 컨트롤러도 이 Ingress 를 안 맡는다 → `ADDRESS` 비어 있고 404. `spec.ingressClassName: nginx` 추가.
- **(b) 포트 번호가 컨테이너 포트(5678).** Ingress 는 **Service 의 port** 를 가리켜야 한다. tea Service 의 port 는 `80`(→ targetPort 5678). `number: 80` 으로 수정.

진단 순서: `kubectl get ingress broken -n step14`(ADDRESS 확인) → `kubectl describe ingress broken -n step14`(Backends 뒤 엔드포인트 확인) → Service 의 포트(`kubectl get svc tea -n step14`)와 대조.
</details>

---

## 과제 5. pathType 함정

`Exact` 로 `/health` 를 걸었더니 브라우저가 `/health/` 로 접근해 404 가 난다. 왜인지 설명하고,
`/health` 와 `/health/` 둘 다 받으려면 어떻게 해야 하는지 답하라.

<details><summary>해답</summary>

- `Exact` 는 후행 슬래시까지 **완전히 동일**해야 매치한다. `/health` 규칙은 `/health/` 를 매치하지 않는다.
- 해결: `pathType: Prefix` 로 바꾸면 `/health`, `/health/`, `/health/live` 를 모두 매치한다. 정확히 두 경로만 원하면 규칙을 두 개(`/health` Exact, `/health/` Exact) 두거나 Prefix 를 쓴다.
</details>
