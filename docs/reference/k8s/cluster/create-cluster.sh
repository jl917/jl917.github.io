#!/usr/bin/env bash
# =====================================================================
# create-cluster.sh : 학습용 kind 클러스터를 만들고 사용 가능 상태로 준비합니다.
#
#   ./create-cluster.sh
#
# 하는 일:
#   1) kind 로 3노드 클러스터 생성 (kind-cluster.yaml)
#   2) (선택) 사내 프록시 CA 를 노드에 주입 → 노드가 이미지를 직접 pull 가능
#   3) 상태 확인
#
# 사내망(파일럿 환경)에서 TLS 가로채기 프록시를 쓰는 경우:
#   노드가 docker.io 등에서 이미지를 받을 때 "certificate signed by unknown
#   authority" 로 실패합니다. INJECT_CA=1 로 실행하면 프록시 CA 를 노드에 심어
#   해결합니다. (호스트 Docker 는 이미 CA 를 신뢰하므로 정상 동작합니다)
#
#   INJECT_CA=1 ./create-cluster.sh          # CA 자동 추출·주입
#   PROXY_HOST=auth.docker.io INJECT_CA=1 ./create-cluster.sh
# =====================================================================
set -euo pipefail

CLUSTER_NAME="learn"
HERE="$(cd "$(dirname "$0")" && pwd)"

command -v kind    >/dev/null || { echo "kind 가 필요합니다: brew install kind"; exit 1; }
command -v kubectl >/dev/null || { echo "kubectl 이 필요합니다"; exit 1; }

# ── 1) 클러스터 생성 ────────────────────────────────────────────────
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "▶ 클러스터 '$CLUSTER_NAME' 가 이미 있습니다. 재생성하려면: ./delete-cluster.sh 후 다시 실행"
else
  echo "▶ kind 클러스터 생성 (3노드)"
  kind create cluster --config "$HERE/kind-cluster.yaml" --wait 120s
fi

kubectl config use-context "kind-${CLUSTER_NAME}"

# ── 2) 사내 프록시 CA 주입 (선택) ──────────────────────────────────
if [[ "${INJECT_CA:-0}" == "1" ]]; then
  PROXY_HOST="${PROXY_HOST:-auth.docker.io}"
  echo "▶ 사내 프록시 CA 추출 (${PROXY_HOST})"
  TMP_CHAIN="$(mktemp)"
  echo | openssl s_client -connect "${PROXY_HOST}:443" -showcerts 2>/dev/null \
    | awk '/-----BEGIN CERTIFICATE-----/{c=1} c{print} /-----END CERTIFICATE-----/{c=0}' > "$TMP_CHAIN"

  if [[ ! -s "$TMP_CHAIN" ]]; then
    echo "  ⚠️  CA 추출 실패. 프록시가 없거나 openssl 접근이 막혔습니다. CA 주입을 건너뜁니다."
  else
    for node in $(kind get nodes --name "$CLUSTER_NAME"); do
      docker cp "$TMP_CHAIN" "$node":/usr/local/share/ca-certificates/corp-proxy.crt
      docker exec "$node" update-ca-certificates >/dev/null 2>&1 || true
      docker exec "$node" systemctl restart containerd
      echo "  ✓ $node 에 CA 주입 + containerd 재시작"
    done
    sleep 5
  fi
  rm -f "$TMP_CHAIN"
fi

# ── 3) 상태 확인 ────────────────────────────────────────────────────
echo "▶ 노드 상태"
kubectl get nodes -o wide

echo "▶ 이미지 pull 스모크 테스트"
kubectl run smoke --image=nginx:1.27-alpine --restart=Never >/dev/null 2>&1 || true
for i in $(seq 1 20); do
  phase="$(kubectl get pod smoke -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  [[ "$phase" == "Running" ]] && break
  sleep 3
done
if [[ "$(kubectl get pod smoke -o jsonpath='{.status.phase}' 2>/dev/null)" == "Running" ]]; then
  echo "  ✅ 이미지 pull 및 파드 기동 정상"
else
  echo "  ⚠️  파드가 Running 이 아닙니다. 아래로 원인 확인:"
  echo "     kubectl describe pod smoke | grep -A5 Events"
  echo "     사내 프록시 환경이면:  ./delete-cluster.sh && INJECT_CA=1 ./create-cluster.sh"
fi
kubectl delete pod smoke --wait=false >/dev/null 2>&1 || true

echo ""
echo "✅ 준비 완료. 컨텍스트: kind-${CLUSTER_NAME}"
echo "   시작:  kubectl get nodes"
echo "   교재:  k8s/step-01-setup/README.md"
