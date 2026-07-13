# Step 18 연습 과제 — 오토스케일링

> 실습 네임스페이스: `step18`. 끝나면 `kubectl delete ns step18`.
> **metrics-server는 삭제하지 마세요**(공용 인프라, Step 19에서 사용).

---

## 과제 1. `<unknown>` 진단

HPA를 걸었더니 `TARGETS`가 `<unknown>/50%`에서 안 바뀝니다. 가능한 원인 2가지와 확인 방법은?

<details><summary>해답</summary>

1. **파드에 `requests.cpu`가 없음** — CPU HPA는 request 대비 비율이라 request가 없으면 계산 불가.
   ```bash
   kubectl get deploy web -n step18 -o jsonpath='{.spec.template.spec.containers[0].resources}'; echo
   ```
2. **metrics-server가 파드 메트릭을 아직/전혀 못 줌** — 방금 설치했거나 죽어 있음.
   ```bash
   kubectl top pods -n step18          # 값이 나오는지
   kubectl get apiservices v1beta1.metrics.k8s.io   # AVAILABLE True 인지
   ```
</details>

---

## 과제 2. 목표 replica 수 계산

현재 replica 2개, 평균 CPU 사용률이 request의 180%입니다. 목표가 60%일 때 HPA는 몇 개로 조정할까요? (maxReplicas=10)

<details><summary>해답</summary>

`ceil(2 × 180 / 60) = ceil(6) = 6`개. maxReplicas 10 이내이므로 6으로 스케일합니다.
</details>

---

## 과제 3. 메모리 기반 HPA 추가

CPU 대신(또는 함께) 메모리 사용률로도 스케일하도록 `hpa.yaml`의 `metrics`에 항목을 추가하세요.

<details><summary>해답</summary>

```yaml
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 50 }
    - type: Resource
      resource:
        name: memory
        target: { type: Utilization, averageUtilization: 70 }
```
복수 메트릭이면 HPA는 각 메트릭이 요구하는 replica 중 **가장 큰 값**을 택합니다(가장 안전한 쪽). `autoscaling/v2`라서 가능합니다.
</details>

---

## 과제 4. 축소를 더 공격적으로

부하가 빠지면 30초 안정화 후 30초마다 절반씩 줄이도록 `behavior.scaleDown`을 바꾸세요.

<details><summary>해답</summary>

```yaml
    scaleDown:
      stabilizationWindowSeconds: 30
      policies:
        - type: Percent
          value: 50
          periodSeconds: 30
      selectPolicy: Max
```
`type: Percent, value: 50`은 "30초마다 현재의 50%까지 줄일 수 있다"는 뜻입니다. 반대로 축소를 완전히 막으려면 `selectPolicy: Disabled`.
</details>
