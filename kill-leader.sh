#!/usr/bin/env bash
# kill-leader.sh — demo leader election failover
# Usage: ./kill-leader.sh
# Requires: docker, curl, jq

set -euo pipefail

SCHEDULER_1="http://localhost:8080"
SCHEDULER_2="http://localhost:8081"

echo "=== Leader Election Failover Demo ==="
echo ""

# Find which node is currently the leader
get_leader() {
    local url=$1
    curl -s "$url/health" 2>/dev/null | jq -r 'if .isLeader then "LEADER" else "STANDBY" end' 2>/dev/null || echo "UNREACHABLE"
}

echo "Before kill:"
echo "  scheduler-1: $(get_leader $SCHEDULER_1)"
echo "  scheduler-2: $(get_leader $SCHEDULER_2)"
echo ""

# Kill scheduler-1 (whichever is leader — demo assumes it's scheduler-1)
echo "Killing js-scheduler-1..."
docker kill js-scheduler-1
START=$(date +%s%3N)

echo "Waiting for scheduler-2 to become leader (SLO: <15s)..."
until [ "$(get_leader $SCHEDULER_2)" = "LEADER" ]; do
    sleep 0.5
done

END=$(date +%s%3N)
LATENCY=$((END - START))

echo ""
echo "Takeover complete in ${LATENCY}ms"
echo "  scheduler-1: DEAD"
echo "  scheduler-2: $(get_leader $SCHEDULER_2)"
