#!/usr/bin/env bash
# Never removes volumes, containers, application logs or backup files.
set -euo pipefail
exec 9>"${HOME}/.sbshop-docker-maintenance.lock"
flock -n 9 || exit 0
# Keep recent rollback images; discard only unused images older than seven days.
timeout 300 docker image prune -a -f --filter 'until=168h'
# Bound reclaimable build cache. In-use layers cannot be pruned.
timeout 300 docker builder prune -a -f --max-used-space 5GB --reserved-space 2GB
df -h /
used=$(df --output=pcent / | tail -1 | tr -dc '0-9')
if (( used >= 85 )); then
  echo "Disk usage ${used}%: investigate retained logs/backups; no automatic deletion." >&2
  exit 1
fi
