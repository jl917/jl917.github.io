#!/usr/bin/env bash
# Step 21 — 가용성과 중단 관리
# ★★ 이 스크립트는 노드를 cordon/drain 합니다. learn-worker2 만, 짧게, 직후 uncordon 합니다.
export PATH="/opt/homebrew/bin:$PATH"
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# 안전장치: 스크립트가 중간에 죽어도 노드를 반드시 되돌린다
trap 'echo "[trap] uncordon learn-worker2"; kubectl uncordon learn-worker2 >/dev/null 2>&1 || true' EXIT

# ── 워크로드 + PDB ────────────────────────────────────────────────────
kubectl apply -f "$HERE/manifests/namespace.yaml"
kubectl apply -f "$HERE/manifests/web.yaml"
kubectl apply -f "$HERE/manifests/pdb.yaml"
kubectl rollout status deploy/web -n step21 --timeout=60s
kubectl get pods -n step21 -o wide
kubectl get pdb -n step21                       # ALLOWED DISRUPTIONS 1

# ── cordon / uncordon (안전) ─────────────────────────────────────────
kubectl cordon learn-worker2
kubectl get nodes                                # worker2: Ready,SchedulingDisabled
kubectl scale deploy/web -n step21 --replicas=6
sleep 6
kubectl get pods -n step21 -o wide --no-headers | awk '{print $1,$7}'   # 신규는 worker 로만
kubectl uncordon learn-worker2
kubectl scale deploy/web -n step21 --replicas=4
kubectl rollout status deploy/web -n step21 --timeout=60s

# ── drain (PDB 실증) → 즉시 uncordon ─────────────────────────────────
kubectl drain learn-worker2 --ignore-daemonsets --delete-emptydir-data --timeout=120s
kubectl uncordon learn-worker2                   # ★ 즉시 원복
kubectl get nodes                                # 모두 Ready

# ── PriorityClass / preemption ───────────────────────────────────────
kubectl apply -f "$HERE/manifests/priorityclass.yaml"
kubectl apply -f "$HERE/manifests/preemption-demo.yaml"  # low 가 먼저면 좋지만 동시 apply 도 스케줄러가 처리
sleep 8
kubectl get pods -n step21 -l app=preempt-demo -o wide
kubectl get events -n step21 --sort-by='.lastTimestamp' | grep -i preempt || true

# ── 정리: 전역 PriorityClass + 네임스페이스 삭제, 노드 원복 확인 ──────
kubectl delete priorityclass step21-low step21-high --ignore-not-found
kubectl delete namespace step21
kubectl uncordon learn-worker2 >/dev/null 2>&1 || true
kubectl get nodes                                # 모두 Ready, SchedulingDisabled 없어야
echo "metrics-server 는 보존합니다(삭제 금지)."
