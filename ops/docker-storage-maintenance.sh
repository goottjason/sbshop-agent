#!/usr/bin/env bash
# Never removes volumes, containers, application logs or backup files.
set -euo pipefail

LOCK_FILE="${LOCK_FILE:-${HOME}/.sbshop-docker-maintenance.lock}"
AGE_HOURS="${AGE_HOURS:-168}"
KEEP_RE='(:latest|:prev-[0-9]{8}-[0-9]{6}|:pending-prev)$'

# Remove tags of unused images older than AGE_HOURS, except the latest and rollback
# tags that the deploy scripts rely on. `docker image prune -a` cannot express that:
# its age filter is the image creation time, so it also deleted rollback images of
# services that are deployed rarely.
prune_old_unused_images() {
  local cutoff used ids ref id created epoch
  cutoff=$(( $(date +%s) - AGE_HOURS * 3600 ))
  used="$(docker inspect -f '{{.Image}}' $(docker ps -a -q) 2>/dev/null || true)"
  while IFS='|' read -r id ref; do
    [ -n "$id" ] || continue
    case "$ref" in *"<none>"*) continue ;; esac
    if printf '%s\n' "$used" | grep -qxF -- "$id"; then continue; fi
    if [[ "$ref" =~ $KEEP_RE ]]; then continue; fi
    created="$(docker image inspect -f '{{.Created}}' "$id" 2>/dev/null || true)"
    [ -n "$created" ] || continue
    epoch="$(date -d "$created" +%s 2>/dev/null || true)"
    [ -n "$epoch" ] || continue
    if [ "$epoch" -lt "$cutoff" ]; then
      docker rmi "$ref" >/dev/null 2>&1 || true
    fi
  done < <(docker image ls --no-trunc --format '{{.ID}}|{{.Repository}}:{{.Tag}}')
  timeout 300 docker image prune -f --filter "until=${AGE_HOURS}h"
}

main() {
  exec 9>"$LOCK_FILE"
  flock -n 9 || exit 0
  prune_old_unused_images
  # Bound reclaimable build cache. In-use layers cannot be pruned.
  timeout 300 docker builder prune -a -f --max-used-space 5GB --reserved-space 2GB
  df -h /
  local used
  used=$(df --output=pcent / | tail -1 | tr -dc '0-9')
  if (( used >= 85 )); then
    echo "Disk usage ${used}%: investigate retained logs/backups; no automatic deletion." >&2
    exit 1
  fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
