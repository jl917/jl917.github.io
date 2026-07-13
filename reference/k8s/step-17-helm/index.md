# Step 17 — Helm: Kubernetes 패키지 매니저

## 학습 목표
- Helm이 무엇이고 왜 쓰는지, "패키지 매니저"라는 비유가 무엇을 뜻하는지 이해한다.
- 차트 구조(`Chart.yaml` / `values.yaml` / `templates/` / `_helpers.tpl`)를 손으로 만든다.
- 템플릿 문법(`.Values`, `range`, `if`, named template, `include`)을 읽고 쓴다.
- `helm install / upgrade / rollback / uninstall / list / history`를 실제로 실행한다.
- values 오버라이드(`-f`, `--set`)와 `helm template` / `--dry-run`으로 배포 전에 결과를 확인한다.
- 릴리스 **리비전**의 개념을 잡고, 잘못된 배포를 한 번에 되돌린다.

## 선행 지식
- Step 05(Deployment), Step 06(Service), Step 07(ConfigMap). Helm은 결국 이 오브젝트들을 "찍어내는" 도구다.

## 소요 시간
- 40~50분

---

## 1. Helm은 왜 필요한가

지금까지는 `kubectl apply -f deployment.yaml -f service.yaml -f configmap.yaml`처럼 YAML을 직접 적용했습니다. 애플리케이션 하나에 오브젝트가 5~10개씩 되고, `dev`/`staging`/`prod`마다 replica 수·이미지 태그·도메인이 다르면 **거의 똑같은 YAML을 환경별로 복붙**하게 됩니다.

Helm은 이 문제를 "패키지 매니저"로 풉니다. `apt`/`brew`가 프로그램을 설치·업그레이드·삭제하듯, Helm은 **차트(chart)** 라는 패키지 단위로 쿠버네티스 애플리케이션을 설치·업그레이드·롤백·삭제합니다.

| 개념 | 뜻 |
|---|---|
| **Chart** | 템플릿 + 기본값을 묶은 패키지 (설치 "레시피") |
| **Values** | 템플릿의 빈칸을 채우는 값. 환경마다 다르게 준다 |
| **Release** | 차트를 클러스터에 한 번 설치한 "인스턴스". 이름을 가진다 (`web`) |
| **Revision** | 릴리스가 바뀔 때마다 1씩 증가하는 버전. 롤백의 단위 |

핵심은 **템플릿 + 값 = 매니페스트**입니다. 같은 차트에 다른 values를 주면 다른 환경이 됩니다.

---

## 2. 차트 구조

이 스텝에서는 검증된 `nginx:1.27-alpine`로 도는 **자작 차트** `webapp`을 만듭니다. `helm create`가 만들어주는 스캐폴드에서 군더더기를 걷어낸 최소 구조입니다.

```
chart/
├── Chart.yaml            # 차트 메타데이터 (이름, 버전, appVersion)
├── values.yaml           # 기본값
├── values-prod.yaml      # -f 로 덮어쓸 환경별 값 예시
├── .helmignore           # 패키징에서 제외할 파일
└── templates/
    ├── _helpers.tpl      # 재사용 named template (레이블/이름 규칙)
    ├── configmap.yaml    # .Values 를 렌더링한 index.html
    ├── deployment.yaml   # nginx Deployment
    ├── service.yaml      # ClusterIP Service
    └── NOTES.txt         # 설치 후 안내문
```

### Chart.yaml — 차트의 신분증

```yaml
apiVersion: v2
name: webapp
version: 0.1.0        # 차트 구조/템플릿 버전 (템플릿을 바꾸면 올린다)
appVersion: "1.27"    # 배포하는 앱의 버전 (표시용)
```

> `version`과 `appVersion`은 다릅니다. `version`은 "레시피"의 버전, `appVersion`은 "요리되는 재료(앱)"의 버전입니다.

### values.yaml — 템플릿의 빈칸

```yaml
replicaCount: 2
image:
  repository: nginx
  tag: "1.27-alpine"
page:
  title: "Helm Webapp"
  message: "Hello from Helm - v1"
extraAnnotations:
  team: platform
  owner: julong
```

전체 파일은 `chart/values.yaml` 이며, 아래 [실습 파일](#실습-파일) 섹션에 전문이 있습니다.

---

## 3. 템플릿 문법

템플릿은 Go 템플릿 엔진 + Sprig 함수를 씁니다. 알아야 할 것은 네 가지입니다.

### (1) `.Values` / `.Release` / `.Chart` — 내장 객체

```yaml
name: {{ include "webapp.fullname" . }}   # 아래 named template 참조
replicas: {{ .Values.replicaCount }}       # values.yaml 에서 옴
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
```

- `.Values.*` — values 파일/`--set`에서 온 값
- `.Release.Name` / `.Release.Namespace` / `.Release.Revision` — 설치 시 Helm이 채우는 값
- `.Chart.Name` / `.Chart.Version` — Chart.yaml에서 옴

### (2) named template + `include` — DRY

여러 파일에서 같은 레이블/이름을 반복하지 않도록 `chart/templates/_helpers.tpl`에 정의하고 `include`로 불러 씁니다.

```yaml
{{- define "webapp.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
```

사용: `{{ include "webapp.fullname" . }}` → 릴리스 `web`이면 `web-webapp`.

> `include`와 `template`의 차이: `include`는 결과를 문자열로 돌려줘 `| nindent 4` 같은 파이프를 걸 수 있습니다. `template`은 불가능. **거의 항상 `include`를 쓰세요.**

### (3) `range` — 반복

`extraAnnotations` 맵을 순회해 어노테이션을 펼칩니다(`chart/templates/deployment.yaml`):

```yaml
  annotations:
    {{- range $key, $val := .Values.extraAnnotations }}
    {{ $key }}: {{ $val | quote }}
    {{- end }}
```

### (4) `if` — 조건

```yaml
{{- if .Values.ingress.enabled }}
# ... Ingress 리소스 ...
{{- end }}
```

> `{{-`와 `-}}`의 `-`는 **좌우 공백/개행을 먹는다**는 뜻입니다. 이게 없으면 렌더 결과에 빈 줄이 잔뜩 생기고, YAML 들여쓰기가 깨져 apply가 실패합니다. Helm 템플릿 버그의 절반은 공백 제어 실수입니다.

---

## 4. 렌더 결과를 배포 전에 확인 — `helm template` / `lint`

클러스터에 손대기 전에 **로컬에서** 최종 YAML을 뽑아 봅니다.

```bash
helm lint ./chart
helm template demo ./chart | head -40
```

**실제 출력**
```
==> Linting ./chart
[INFO] Chart.yaml: icon is recommended
1 chart(s) linted, 0 chart(s) failed
```
```yaml
# Source: webapp/templates/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-webapp-html
  labels:
    app.kubernetes.io/name: webapp
    app.kubernetes.io/instance: demo
...
    <h1>Hello from Helm - v1</h1>
    <p>replicas: 2</p>
```

`demo`라는 릴리스명을 넣었더니 `demo-webapp-html`로 이름이 찍혔습니다. named template이 동작한 것입니다.

---

## 5. 설치 — `helm install`

```bash
helm install web ./chart --namespace step17 --create-namespace --wait --timeout 90s
```

**실제 출력**
```
NAME: web
LAST DEPLOYED: Mon Jul 13 11:56:43 2026
NAMESPACE: step17
STATUS: deployed
REVISION: 1
...
현재 값:
  replicas = 2
  message  = Hello from Helm - v1
```

`--wait`는 파드가 Ready 될 때까지 블로킹합니다. `NOTES.txt`가 마지막에 출력됩니다.

```bash
helm list -n step17
kubectl get pods -n step17
```
```
NAME  NAMESPACE  REVISION  STATUS    CHART         APP VERSION
web   step17     1         deployed  webapp-0.1.0  1.27

NAME                          READY   STATUS    RESTARTS   AGE
web-webapp-69c65dc798-2g7cg   1/1     Running   0          1s
web-webapp-69c65dc798-9tckd   1/1     Running   0          1s
```

서빙 내용 확인(임시 busybox로 서비스에 요청):
```bash
kubectl -n step17 run curl-tmp --image=busybox:1.36 --restart=Never --rm -i --quiet -- wget -qO- web-webapp
```
```html
    <h1>Hello from Helm - v1</h1>
    <p>replicas: 2</p>
```

---

## 6. 업그레이드 — `--set`와 `-f`

### `--set`으로 값 몇 개만 바꾸기 (rev 2)

```bash
helm upgrade web ./chart -n step17 \
  --set page.message="Hello from Helm - v2 (set)" \
  --set replicaCount=3 --wait
```
```
STATUS: deployed
REVISION: 2
```
```html
    <h1>Hello from Helm - v2 (set)</h1>
    <p>replicas: 3</p>
```

`--set`은 급하게 한두 개 바꿀 때, 파일은 형상관리에 남기고 싶을 때 유용합니다.

### `-f`로 환경별 values 파일 덮어쓰기 (rev 3)

`chart/values-prod.yaml`은 바꿀 값만 담습니다. 기본 `values.yaml` 위에 **병합**됩니다.

```bash
helm upgrade web ./chart -n step17 -f ./chart/values-prod.yaml --wait
```
```
STATUS: deployed
REVISION: 3
```
```html
  <head><title>Helm Webapp (prod)</title></head>
    <h1>Hello from Helm - PROD</h1>
    <p>replicas: 3</p>
```

> `--set`이 `-f`보다 우선순위가 높습니다(`values.yaml` < `-f` < `--set`). 여러 개면 뒤에 온 것이 이깁니다.

### 리비전 이력 — `helm history`

```bash
helm history web -n step17
```
```
REVISION  UPDATED                   STATUS      CHART         DESCRIPTION
1         Mon Jul 13 11:56:43 2026  superseded  webapp-0.1.0  Install complete
2         Mon Jul 13 11:57:07 2026  superseded  webapp-0.1.0  Upgrade complete
3         Mon Jul 13 11:57:22 2026  deployed    webapp-0.1.0  Upgrade complete
```

Helm은 각 리비전의 매니페스트 전체를 Secret으로 저장합니다(`kubectl get secret -n step17 -l owner=helm`). 그래서 롤백이 가능합니다.

---

## 7. 롤백 — `helm rollback`

rev 3(prod)가 문제라고 치고, 처음 상태(rev 1)로 되돌립니다.

```bash
helm rollback web 1 -n step17 --wait
```
```
Rollback was a success! Happy Helming!
```
```html
    <h1>Hello from Helm - v1</h1>
    <p>replicas: 2</p>
```

**중요**: 롤백은 과거 리비전을 되살리지만, **이력에는 새 리비전(rev 4)으로 기록**됩니다. 시간은 앞으로만 흐릅니다.

```
REVISION  STATUS      DESCRIPTION
1         superseded  Install complete
2         superseded  Upgrade complete
3         superseded  Upgrade complete
4         deployed    Rollback to 1
```

---

## 8. `--dry-run` — 배포 없이 결과만 보기

```bash
helm upgrade web ./chart -n step17 --set replicaCount=9 --dry-run | grep replicas:
```
```
        <p>replicas: 9</p>
  replicas: 9
```

클러스터 상태는 그대로 두고 "만약 이 값으로 올리면?"의 결과만 렌더링합니다. CI에서 PR 검증용으로 자주 씁니다.

---

## 9. 삭제 — `helm uninstall`

```bash
helm uninstall web -n step17
```
```
release "web" uninstalled
```

차트가 만든 모든 오브젝트(Deployment/Service/ConfigMap)가 한 번에 사라집니다. `kubectl delete -f` 여러 번 할 필요가 없습니다.

---

## 팁과 함정

- **함정 1 — 공백 제어(`{{-` / `-}}`)**: `range`/`if` 블록에서 `-`를 빠뜨리면 빈 줄이 생겨 YAML 들여쓰기가 깨지고 apply가 실패합니다. `helm template`으로 최종 YAML을 눈으로 확인하는 습관을 들이세요.
- **함정 2 — 셀렉터는 불변**: Deployment의 `spec.selector.matchLabels`는 한 번 배포하면 바꿀 수 없습니다. 그래서 `_helpers.tpl`의 `selectorLabels`에는 **버전처럼 바뀔 값을 넣지 않습니다**(전체 labels와 분리한 이유).
- **함정 3 — `--set`의 타입**: `--set replicaCount=3`은 문자열 `"3"`이 아니라 숫자 3으로 들어가지만, `--set tag=1.27`처럼 소수점이 있으면 의도와 다르게 파싱될 수 있습니다. 애매하면 `--set-string`을 쓰거나 `-f`로 넘기세요.
- **팁 — ConfigMap 체크섬**: `deployment.yaml`의 `checksum/config` 어노테이션은 ConfigMap 내용의 sha256입니다. values를 바꿔 ConfigMap이 바뀌면 이 값이 바뀌어 파드가 자동 롤링 재시작됩니다. 이게 없으면 ConfigMap만 바뀌고 파드는 옛 설정을 계속 물고 있습니다(무중단 반영의 표준 트릭).
- **팁 — `--atomic`**: `helm upgrade --atomic`을 쓰면 업그레이드가 실패할 때 **자동으로 이전 리비전으로 롤백**됩니다. 운영에서 강력히 권장.
- **팁 — `helm get manifest/values`**: `helm get manifest web -n step17`로 현재 릴리스가 실제 배포한 YAML을, `helm get values web -n step17`로 적용된 값을 확인할 수 있습니다.

---

## 정리표

| 명령 | 하는 일 |
|---|---|
| `helm lint ./chart` | 차트 문법/구조 검사 |
| `helm template <name> ./chart` | 클러스터 없이 최종 YAML 렌더링 |
| `helm install <name> ./chart -n ns --create-namespace` | 릴리스 설치 (rev 1) |
| `helm upgrade <name> ./chart --set k=v` / `-f file` | 값 바꿔 업그레이드 (rev++) |
| `helm upgrade ... --dry-run` | 배포 없이 결과만 렌더 |
| `helm list -n ns` | 릴리스 목록 |
| `helm history <name> -n ns` | 리비전 이력 |
| `helm rollback <name> <rev> -n ns` | 특정 리비전으로 되돌림 (새 rev로 기록) |
| `helm uninstall <name> -n ns` | 릴리스 및 모든 오브젝트 삭제 |
| `helm get manifest/values <name>` | 배포된 YAML/값 조회 |

---

## 연습 과제

[challenge.md](challenge.md)에 5개 과제와 해답이 있습니다.

---

## 다음 단계

→ [Step 18 — 오토스케일링](../step-18-autoscaling/index.md): metrics-server를 설치하고 HPA로 부하에 따라 replica가 자동으로 늘고 주는 것을 실증합니다.

---

## 실습 파일

이 스텝의 실습은 `commands.sh` 한 개와 `chart/` 디렉터리(자작 차트 `webapp`) 하나로 이루어집니다. `chart/`는 Helm이 요구하는 구조 그대로이며 — 메타데이터(`Chart.yaml`) + 값(`values.yaml`, `values-prod.yaml`) + 템플릿(`templates/`) — `helm lint` → `helm template` → `install` → `upgrade` → `rollback` → `uninstall` 순서로 소비됩니다. 아래에서는 실행 스크립트를 먼저 보고, 그다음 차트를 "바깥에서 안으로"(메타데이터 → 값 → 템플릿) 훑습니다.

### commands.sh

본문 4장~9장의 명령을 순서대로 담은 실행 스크립트입니다. 한 줄씩 읽으며 직접 따라 치는 것이 원칙이지만, 전체를 한 번에 돌려도 같은 결과가 나오도록 짜여 있습니다.

- `HERE="$(cd "$(dirname "$0")" && pwd)"` / `CHART="$HERE/chart"` — 스크립트 위치를 기준으로 차트 경로를 잡으므로, **어느 디렉터리에서 실행하든** 동작합니다.
- `set -euo pipefail` 때문에 중간의 `helm` 명령이 하나라도 실패하면 즉시 멈춥니다. 실습 도중 에러를 놓치지 않게 해 주는 안전장치입니다.
- 맨 위의 `export PATH="/opt/homebrew/bin:$PATH"`는 macOS(Homebrew)에서 `helm`·`kubectl`을 찾지 못하는 경우를 대비한 줄입니다. 리눅스나 다른 설치 경로를 쓴다면 없어도 그만이며, 이미 `PATH`에 잡혀 있으면 아무 영향이 없습니다.
- 1)번 블록(`helm lint` / `helm template demo`)은 **클러스터에 전혀 손대지 않습니다.** 2)번의 `helm install ... --wait --timeout 90s`부터 실제 배포가 일어납니다.
- 2)번의 검증은 `kubectl run curl-tmp --image=busybox:1.36 --restart=Never --rm` 로 일회용 파드를 띄워 `web-webapp` 서비스에 `wget` 하는 방식입니다. 3)번 이후에는 `kubectl exec deploy/web-webapp -- wget -qO- localhost` 로 파드 안에서 직접 확인합니다.
- 리비전이 `install`(rev 1) → `--set` upgrade(rev 2) → `-f` upgrade(rev 3) → `rollback web 1`(rev 4) 순으로 쌓입니다. **순서 의존적**이니 중간부터 잘라 실행하지 마세요.
- **주의**: 마지막 7)번 블록이 `helm uninstall web`과 `kubectl delete namespace step17`을 수행합니다. 결과를 천천히 들여다보고 싶다면 이 두 줄을 실행하지 말고 남겨 두세요(파일은 수정하지 말고, 스크립트를 통째로 돌리는 대신 위 블록만 손으로 실행하면 됩니다).

```bash file="./commands.sh"
```

### chart/Chart.yaml

차트의 "신분증"입니다. Helm은 이 파일이 있는 디렉터리를 차트로 인식하므로, 이 파일이 없으면 `helm lint`부터 실패합니다.

- `apiVersion: v2` — Helm 3의 차트 포맷입니다. (v1은 Helm 2 시절 포맷)
- `name: webapp` — 차트 이름. `_helpers.tpl`의 `webapp.fullname`이 `<릴리스명>-<차트명>`을 만들 때 이 값을 쓰기 때문에, 릴리스명을 `web`으로 설치하면 리소스 이름이 `web-webapp`이 됩니다.
- `version: 0.1.0` — **차트(레시피) 자체의 버전**. 템플릿 구조를 바꾸면 올립니다. `helm list`의 `CHART` 열에 `webapp-0.1.0`으로 나오는 값이 이것입니다.
- `appVersion: "1.27"` — **배포 대상 앱(nginx)의 버전**으로 표시용입니다. 따옴표를 붙이지 않으면 `1.27`이 숫자로 파싱되어 `1.27`이 아닌 값으로 렌더될 수 있어 문자열로 고정했습니다. `_helpers.tpl`에서 `app.kubernetes.io/version` 레이블로 들어갑니다.
- `type: application` — 실제 배포되는 앱 차트라는 뜻입니다(값만 제공하는 `library` 타입과 구분).

```yaml file="./chart/Chart.yaml"
```

### chart/values.yaml

템플릿의 "빈칸"을 채우는 기본값 모음입니다. `-f`나 `--set` 없이 `helm install web ./chart`만 실행하면 전부 이 값으로 렌더링됩니다.

- `replicaCount: 2` — Deployment의 `replicas`이자, ConfigMap의 index.html에 `<p>replicas: 2</p>`로도 찍힙니다. **한 값이 두 리소스에 동시에 반영되는 것**이 템플릿의 위력입니다.
- `image.repository: nginx` / `image.tag: "1.27-alpine"` — `deployment.yaml`에서 `"{{ .Values.image.repository }}:{{ .Values.image.tag }}"`로 합쳐집니다. 태그는 반드시 따옴표로 감싸 문자열임을 명시합니다. 함께 있는 `image.pullPolicy: IfNotPresent`는 `deployment.yaml`의 `imagePullPolicy`로 들어가, 이미 노드에 받아 둔 이미지가 있으면 다시 당겨오지 않습니다(로컬 클러스터에서 재시작이 빨라집니다).
- `service.type: ClusterIP` / `service.port: 80` — 연습 과제 4번에서 이 `service.type`을 `NodePort`로 토글하는 `if` 실습을 합니다.
- `resources.requests`(cpu 25m / memory 32Mi)와 `limits`(cpu 100m / memory 64Mi) — 로컬 클러스터에서도 파드 3개가 무리 없이 뜨도록 아주 작게 잡았습니다. `deployment.yaml`에서 `toYaml`로 통째로 펼쳐집니다.
- `page.title` / `page.color` / `page.message` — ConfigMap의 HTML로 들어가는 값입니다. 6장 업그레이드 실습에서 바로 이 `page.message`를 바꿔 리비전을 올립니다.
- `extraAnnotations`(`team: platform`, `owner: julong`)는 `range` 문법 실습용, `ingress.enabled: false`는 `if` 문법 실습용으로 일부러 넣어 둔 값입니다.

```yaml file="./chart/values.yaml"
```

### chart/values-prod.yaml

6장의 `-f` 오버라이드 실습(rev 3)에서 `helm upgrade web ./chart -n step17 -f ./chart/values-prod.yaml`로 넘기는 "환경별 값" 파일입니다.

- 최상위 키가 `replicaCount`와 `page` **딱 두 개뿐**이라는 점이 핵심입니다(`page` 아래에도 `title`·`color`·`message` 세 개만 있습니다). Helm은 기본 `values.yaml` 위에 이 파일을 **딥 머지**하므로, 여기 없는 `image`·`service`·`resources`·`extraAnnotations`·`ingress`는 기본값이 그대로 살아 있습니다. **전체를 복붙할 필요가 없습니다.**
- `replicaCount: 3` — 앞선 `--set replicaCount=3`(rev 2)과 같은 값이라 파드 수는 그대로지만, `page.title`이 `"Helm Webapp (prod)"`, `message`가 `"Hello from Helm - PROD"`, `color`가 `#276749`(녹색)로 바뀌면서 ConfigMap이 갱신되고 체크섬 어노테이션이 바뀌어 파드가 롤링 재시작됩니다.
- 연습 과제 2번은 이 파일의 `replicaCount: 3` 위에 `--set replicaCount=7`을 얹어, `values.yaml` 보다 `-f` 가, `-f` 보다 `--set` 이 우선한다는 규칙을 확인하는 문제입니다(답은 7).

```yaml file="./chart/values-prod.yaml"
```

### chart/templates/_helpers.tpl

3장 "(2) named template + `include`"에서 다룬 재사용 조각 모음입니다. 파일명이 **밑줄(`_`)로 시작하면 Helm은 이를 쿠버네티스 매니페스트로 렌더하지 않고** 정의만 읽어 갑니다. 그래서 여기에 `define` 블록을 모아 둡니다.

- `webapp.fullname` — `printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-"`. 릴리스 `web` + 차트 `webapp` → `web-webapp`. `trunc 63`은 쿠버네티스 이름 길이 제한(63자)을 넘지 않게 자르고, 자른 끝이 `-`로 끝나면 `trimSuffix`가 떼어 냅니다(이름은 영숫자로 끝나야 하기 때문).
- `webapp.labels` — 모든 리소스에 붙는 5개 표준 레이블. `.Release.Service`는 항상 `Helm`이 되고, `helm.sh/chart`는 `webapp-0.1.0`처럼 찍힙니다.
- `webapp.selectorLabels` — `name`과 `instance` **딱 두 개만** 담습니다. Deployment의 `spec.selector.matchLabels`는 한 번 배포하면 불변이므로, `version`처럼 바뀔 값이 섞이면 다음 `helm upgrade`가 "field is immutable" 에러로 실패합니다. "팁과 함정" 2번에서 말한 함정을 이 분리로 예방한 것입니다.
- 모든 `define`이 `{{- ... -}}`로 감싸져 있어 앞뒤 개행을 먹습니다. 그래야 호출부에서 `| nindent 4`로 들여쓰기를 정확히 제어할 수 있습니다.

```yaml file="./chart/templates/_helpers.tpl"
```

### chart/templates/configmap.yaml

nginx가 서빙할 `index.html`을 `.Values`로 렌더링해 만드는 ConfigMap입니다. 업그레이드 실습에서 **눈에 보이는 변화**를 만들어 내는 주인공입니다.

- 이름이 `{{ include "webapp.fullname" . }}-html`이라 릴리스 `web`이면 `web-webapp-html`이 됩니다. `deployment.yaml`의 볼륨이 정확히 이 이름을 참조합니다.
- `labels:` 아래 `{{- include "webapp.labels" . | nindent 4 }}` — `include`가 문자열을 돌려주기 때문에 `nindent 4`로 4칸 들여쓰기를 붙일 수 있습니다. `template`이었다면 이 파이프가 불가능합니다.
- `data.index.html: |` 블록 스칼라 안에서 `.Values.page.title` / `.color` / `.message` / `.replicaCount`가 치환됩니다. `<p>release: {{ .Release.Name }} ... {{ .Chart.Name }}-{{ .Chart.Version }}</p>` 처럼 `.Release`·`.Chart` 내장 객체도 함께 쓰여, 렌더 결과만 봐도 어느 릴리스·어느 차트 버전이 찍은 페이지인지 알 수 있습니다.
- 4장에서 `helm template demo ./chart`를 돌렸을 때 이름이 `demo-webapp-html`로 나온 이유가 바로 첫 줄의 `fullname` 때문입니다.

```yaml file="./chart/templates/configmap.yaml"
```

### chart/templates/deployment.yaml

nginx 파드를 굴리는 Deployment입니다. 이 차트에서 문법 데모가 가장 많이 몰려 있는 파일이므로 천천히 보세요.

- `annotations:` 아래 `{{- range $key, $val := .Values.extraAnnotations }}` — 3장 "(3) `range`" 예제입니다. 맵을 순회해 `team: "platform"`, `owner: "julong"` 두 줄로 펼칩니다. `$val | quote`로 값에 따옴표를 씌우는 것에 주목하세요(어노테이션 값은 반드시 문자열이어야 합니다).
- `selector.matchLabels`와 파드 템플릿의 `labels`가 **둘 다** `webapp.selectorLabels`를 쓰되, `nindent 6`과 `nindent 8`로 들여쓰기만 다릅니다. 같은 조각을 다른 깊이에 꽂을 수 있는 것이 `include`의 장점입니다.
- `checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}` — configmap.yaml을 **렌더한 결과의 sha256**입니다. `page.message`를 바꾸면 이 해시가 달라지고, 파드 템플릿이 변했으므로 쿠버네티스가 롤링 재시작을 일으킵니다. 이 줄이 없으면 ConfigMap만 갱신되고 파드는 옛 HTML을 계속 물고 있습니다("팁과 함정"의 무중단 반영 트릭).
- `resources:` 아래 `{{- toYaml .Values.resources | nindent 12 }}` — values의 중첩 맵을 통째로 YAML로 직렬화해 12칸 들여 꽂습니다. 필드를 하나하나 나열하지 않아도 되는 관용구입니다.
- 마지막으로 `volumes`가 `configMap.name: {{ include "webapp.fullname" . }}-html`을 `/usr/share/nginx/html`에 마운트해, ConfigMap의 `index.html`이 곧 웹 루트가 됩니다.

```yaml file="./chart/templates/deployment.yaml"
```

### chart/templates/service.yaml

파드 앞에 붙는 ClusterIP Service입니다. 가장 짧은 템플릿이지만 이름 규칙과 셀렉터 규칙을 확인하기에 좋습니다.

- 이름이 Deployment와 같은 `{{ include "webapp.fullname" . }}`(= `web-webapp`)이라, 5장에서 `wget -qO- web-webapp`처럼 **서비스 이름만으로** 접근할 수 있습니다.
- `selector:`에 `webapp.selectorLabels`를 쓰므로 Deployment가 찍어 낸 파드 레이블과 정확히 일치합니다. 두 파일이 같은 named template을 공유하는 덕분에 "셀렉터 오타로 엔드포인트가 비는" 사고가 원천 차단됩니다.
- `type: {{ .Values.service.type }}` / `port: {{ .Values.service.port }}` — 기본값은 ClusterIP:80입니다. 연습 과제 4번에서 `--set service.type=NodePort`일 때만 `nodePort` 필드가 렌더되도록 `if`를 넣어 보는 것이 이 파일을 대상으로 한 과제입니다.
- `targetPort: 80`은 nginx 컨테이너의 `containerPort: 80`에 고정으로 대응합니다.

```yaml file="./chart/templates/service.yaml"
```

### chart/templates/NOTES.txt

`helm install` / `helm upgrade`가 성공한 뒤 **터미널 맨 아래에 출력되는 안내문**입니다. 쿠버네티스 리소스로 배포되지 않고 오직 사용자에게 보여 주기만 하는 특수 템플릿입니다(파일명이 `NOTES.txt`여야 동작합니다).

- 첫 줄의 `revision: {{ .Release.Revision }}` 덕분에, 업그레이드할 때마다 출력되는 숫자가 1 → 2 → 3으로 올라가는 것을 눈으로 확인할 수 있습니다.
- `kubectl -n {{ .Release.Namespace }} port-forward svc/{{ include "webapp.fullname" . }} 8080:{{ .Values.service.port }}` — 네임스페이스·서비스 이름·포트가 전부 실제 릴리스 값으로 치환되어 나오므로, **복사해서 바로 붙여 넣을 수 있는** 명령이 됩니다. 좋은 NOTES.txt의 핵심입니다.
- 마지막 "현재 값" 블록이 `replicaCount`와 `page.message`를 다시 보여 줍니다. 5장 설치 출력의 `replicas = 2`, `message = Hello from Helm - v1`이 바로 이 부분입니다.

```text file="./chart/templates/NOTES.txt"
```

### chart/.helmignore

`helm package`로 차트를 `.tgz`로 묶을 때 **제외할 파일 패턴**을 적는 파일입니다. `.gitignore`와 문법이 같습니다.

- `.git`, `*.tmp`, `*.bak`, `values-prod.yaml.orig` 네 줄이 들어 있습니다. 편집기가 남기는 백업 파일이나 로컬 작업 부산물이 패키지에 섞여 들어가지 않게 막아 줍니다.
- 이 스텝에서는 로컬 디렉터리(`./chart`)를 그대로 설치하므로 실행 결과에 직접적인 영향은 없습니다. 다만 차트를 사내 레포지토리에 배포하는 단계로 넘어가면 **비밀 값이 담긴 파일이 실수로 패키징되는 것을 막는 안전장치**로 중요해집니다.
- 앞이 점(`.`)으로 시작하는 숨김 파일이라 `ls`에는 보이지 않습니다. `ls -a chart`로 확인하세요.

```text file="./chart/.helmignore"
```
