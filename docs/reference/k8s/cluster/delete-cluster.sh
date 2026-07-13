#!/usr/bin/env bash
# =====================================================================
# delete-cluster.sh : 학습용 클러스터를 완전히 삭제합니다.
#   컨테이너·볼륨·설정이 전부 사라집니다. 다시 만들려면 ./create-cluster.sh
# =====================================================================
set -euo pipefail
CLUSTER_NAME="learn"

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  kind delete cluster --name "$CLUSTER_NAME"
  echo "✅ 클러스터 '$CLUSTER_NAME' 삭제 완료"
else
  echo "삭제할 클러스터 '$CLUSTER_NAME' 가 없습니다."
fi
