#!/usr/bin/env bash
set -euo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
PROJECT_PREFIX="${PROJECT_PREFIX:-sbshop-agent}"
SERVICES=(sbshop-api sbshop-frontend sbshop-scraper)
API_SERVICE="sbshop-api"
API_CONTAINER="${API_CONTAINER:-projects-sbshop-api-1}"
NGINX_CONTAINER="${NGINX_CONTAINER:-projects-nginx-1}"
DB_CONTAINER="${DB_CONTAINER:-projects-postgres-1}"
LOCK_FILE="${LOCK_FILE:-$HOME/.sbshop-docker-maintenance.lock}"
LOCK_WAIT_SEC="${LOCK_WAIT_SEC:-900}"
MIN_FREE_KB="${MIN_FREE_KB:-10485760}"
BATCH_WAIT_SEC="${BATCH_WAIT_SEC:-1800}"
POLL_SEC="${POLL_SEC:-30}"
HEALTH_WAIT_SEC="${HEALTH_WAIT_SEC:-150}"
HEALTH_POLL_SEC="${HEALTH_POLL_SEC:-3}"
KEEP_PREV="${KEEP_PREV:-3}"
FORCE="${FORCE:-0}"
RECREATE="${RECREATE:-}"
DRY_RUN="${DRY_RUN:-0}"

log() { echo "[deploy $(date +%H:%M:%S)] $*"; }
warn() { echo "[deploy $(date +%H:%M:%S)] 경고: $*" >&2; }
die() { echo "[deploy $(date +%H:%M:%S)] 실패: $*" >&2; exit "${2:-1}"; }

run() {
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: $*"; return 0; fi
  "$@"
}

container_of() { echo "projects-$1-1"; }
image_of() { echo "${PROJECT_PREFIX}-$1"; }

valid_service() {
  local s
  for s in "${SERVICES[@]}"; do
    if [ "$s" = "$1" ]; then return 0; fi
  done
  return 1
}

validate_recreate() {
  local s
  for s in $RECREATE; do
    valid_service "$s" || die "알 수 없는 서비스(RECREATE): $s — 허용: ${SERVICES[*]}" 2
  done
}

leftover_containers() {
  docker ps -a --format '{{.Names}}' | grep -E '^[0-9a-f]{12}_projects-sbshop-(api|frontend|scraper|selenium)-1$' || true
}

built_image_id() { docker image inspect -f '{{.Id}}' "$(image_of "$1"):latest" 2>/dev/null || true; }
running_image_id() { docker inspect -f '{{.Image}}' "$(container_of "$1")" 2>/dev/null || true; }
running_config_hash() {
  docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$(container_of "$1")" 2>/dev/null || true
}
wanted_config_hash() {
  (cd "$COMPOSE_DIR" && docker compose config --hash "$1" 2>/dev/null | awk '{print $2}') || true
}

service_changed() {
  local built running wanted current
  case " $RECREATE " in *" $1 "*) return 0 ;; esac
  built="$(built_image_id "$1")"
  running="$(running_image_id "$1")"
  if [ -z "$running" ] || [ "$built" != "$running" ]; then return 0; fi
  wanted="$(wanted_config_hash "$1")"
  current="$(running_config_hash "$1")"
  if [ -n "$wanted" ] && [ "$wanted" != "$current" ]; then return 0; fi
  return 1
}

require_built_images() {
  local svc
  for svc in "${SERVICES[@]}"; do
    if [ -z "$(built_image_id "$svc")" ]; then
      die "$svc: 방금 빌드한 이미지를 찾을 수 없습니다 — 컨테이너는 변경하지 않았습니다"
    fi
  done
}

check_disk() {
  local free_kb
  free_kb="$(df --output=avail / | tail -1 | tr -d ' ')"
  if [ "$free_kb" -lt "$MIN_FREE_KB" ]; then
    warn "디스크 여유가 부족합니다(${free_kb}KB < ${MIN_FREE_KB}KB). ops/docker-storage-maintenance.sh 를 먼저 실행하세요."
    return 1
  fi
}

active_batch_count() {
  docker exec "$DB_CONTAINER" psql -U goottjason -d sbshop -Atc \
    "select count(*) from sb_supplier_batch_run where state in ('RUNNING','PAUSING')"
}

wait_for_idle_batches() {
  if [ "$FORCE" = 1 ]; then log "FORCE=1: 도는 배치를 확인하지 않고 진행합니다"; return 0; fi
  local elapsed=0 n
  while true; do
    if ! n="$(active_batch_count 2>/dev/null)" || ! [[ "$n" =~ ^[0-9]+$ ]]; then
      warn "배치 상태를 조회하지 못했습니다. 조회 없이 진행합니다."
      return 0
    fi
    if [ "$n" = 0 ]; then return 0; fi
    if [ "$elapsed" -ge "$BATCH_WAIT_SEC" ]; then
      warn "도는 소싱처 배치 ${n}건이 ${BATCH_WAIT_SEC}초 안에 끝나지 않아 배포를 중단합니다. 끝난 뒤 다시 실행하거나 force 로 실행하세요."
      return 1
    fi
    log "도는 소싱처 배치 ${n}건 — ${POLL_SEC}초 뒤 다시 확인 (${elapsed}/${BATCH_WAIT_SEC}s)"
    sleep "$POLL_SEC"
    elapsed=$((elapsed + POLL_SEC))
  done
}

remove_leftovers() {
  local name
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    log "찌꺼기 컨테이너 제거: $name"
    run docker rm -f "$name" >/dev/null
  done < <(leftover_containers)
}

prune_prev_tags() {
  local svc="$1" tag
  while IFS= read -r tag; do
    [ -n "$tag" ] || continue
    log "오래된 롤백 태그 제거: $(image_of "$svc"):$tag"
    run docker rmi "$(image_of "$svc"):$tag" >/dev/null 2>&1 || true
  done < <(docker image ls "$(image_of "$svc")" --format '{{.Tag}}' | grep '^prev-' | sort -r | tail -n +"$((KEEP_PREV + 1))" || true)
}

tag_prev() {
  local svc="$1" running tag
  running="$(running_image_id "$svc")"
  [ -n "$running" ] || return 0
  tag="prev-$(date +%Y%m%d-%H%M%S)"
  log "롤백용 태그: $(image_of "$svc"):$tag"
  run docker tag "$running" "$(image_of "$svc"):$tag"
  prune_prev_tags "$svc"
}

replace_service() {
  local svc="$1"
  log "교체: $svc"
  run docker rm -f "$(container_of "$svc")" >/dev/null 2>&1 || true
  (cd "$COMPOSE_DIR" && run docker compose up -d --no-build --no-deps "$svc") \
    || die "$svc 기동 실패 — 컨테이너가 내려간 상태입니다. 원인을 확인한 뒤 ./ops/deploy.sh 를 다시 실행하거나 ./ops/deploy.sh rollback $svc 를 실행하세요" 4
}

verify_running_image() {
  local svc="$1" built running
  if [ "$DRY_RUN" = 1 ]; then return 0; fi
  built="$(built_image_id "$svc")"
  running="$(running_image_id "$svc")"
  if [ "$built" != "$running" ]; then die "$svc: 실행 중인 이미지가 방금 빌드한 이미지와 다릅니다"; fi
}

reload_nginx() {
  log "nginx reload (컨테이너 IP 변경 대응)"
  run docker exec "$NGINX_CONTAINER" nginx -s reload
}

wait_api_healthy() {
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: api 헬스체크 생략"; return 0; fi
  local waited=0
  while [ "$waited" -lt "$HEALTH_WAIT_SEC" ]; do
    if docker exec "$API_CONTAINER" curl -fsS -m 5 http://localhost:8080/internal/health 2>/dev/null | grep -q '"status":"UP"'; then
      log "api 헬스체크 통과 (DB 연결 포함)"
      return 0
    fi
    sleep "$HEALTH_POLL_SEC"
    waited=$((waited + HEALTH_POLL_SEC))
  done
  warn "api 가 ${HEALTH_WAIT_SEC}초 안에 정상(UP)이 되지 못했습니다. 최근 로그:"
  docker logs --tail 40 "$API_CONTAINER" >&2 2>&1 || true
  return 1
}

acquire_lock() {
  if [ "$DRY_RUN" = 1 ]; then return 0; fi
  exec 9>"$LOCK_FILE"
  flock -w "$LOCK_WAIT_SEC" 9 || die "배포/청소 잠금을 ${LOCK_WAIT_SEC}초 안에 얻지 못했습니다"
}

rollback_service() {
  local svc="${1:-}" tag="${2:-}" tags
  [ -n "$svc" ] || die "사용법: deploy.sh rollback <서비스> [태그]" 2
  valid_service "$svc" || die "알 수 없는 서비스: $svc — 허용: ${SERVICES[*]}" 2
  tags="$(docker image ls "$(image_of "$svc")" --format '{{.Tag}}' | grep '^prev-' | sort -r || true)"
  [ -n "$tags" ] || die "$svc: 되돌릴 prev- 태그가 없습니다"
  if [ -z "$tag" ]; then
    tag="$(echo "$tags" | head -1)"
  elif ! echo "$tags" | grep -qx "$tag"; then
    die "$svc: 태그 $tag 가 없습니다. 있는 태그: $(echo "$tags" | tr '\n' ' ')"
  fi
  log "롤백: $svc ← $tag"
  run docker tag "$(image_of "$svc"):$tag" "$(image_of "$svc"):latest"
  replace_service "$svc"
  if [ "$svc" = "$API_SERVICE" ]; then
    reload_nginx || die "롤백 후 nginx reload 실패" 5
    wait_api_healthy || die "롤백 후 api 가 정상이 되지 못했습니다"
  fi
  log "롤백 완료: $svc"
}

main() {
  validate_recreate
  acquire_lock
  check_disk || die "디스크 여유 부족" 1
  wait_for_idle_batches || exit 3

  log "빌드(실패하면 실행 중인 컨테이너는 그대로 둡니다)"
  (cd "$COMPOSE_DIR" && run docker compose build "${SERVICES[@]}") || die "이미지 빌드 실패 — 컨테이너는 변경하지 않았습니다"
  require_built_images

  remove_leftovers

  local changed=() svc
  for svc in "${SERVICES[@]}"; do
    if service_changed "$svc"; then changed+=("$svc"); fi
  done

  if [ "${#changed[@]}" -eq 0 ]; then
    log "이미지·설정이 바뀐 서비스가 없어 교체하지 않습니다"
    return 0
  fi
  log "교체 대상: ${changed[*]}"

  local api_changed=0 reload=0 nginx_failed=0
  for svc in "${changed[@]}"; do
    tag_prev "$svc"
    replace_service "$svc"
    verify_running_image "$svc"
    case "$svc" in
      sbshop-api) api_changed=1; reload=1 ;;
      sbshop-frontend) reload=1 ;;
    esac
  done
  if [ "$reload" = 1 ]; then
    reload_nginx || nginx_failed=1
  fi
  if [ "$api_changed" = 1 ]; then
    wait_api_healthy || die "api 헬스체크 실패 — 롤백하려면: ./ops/deploy.sh rollback sbshop-api"
  fi
  if [ "$nginx_failed" = 1 ]; then
    die "nginx reload 실패 — 새 컨테이너는 떠 있습니다. docker exec $NGINX_CONTAINER nginx -s reload 를 확인하세요" 5
  fi
  log "배포 완료: ${changed[*]}"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  case "${1:-deploy}" in
    deploy) main ;;
    rollback) shift; rollback_service "$@" ;;
    *) die "알 수 없는 명령: $1 (deploy | rollback <서비스> [태그])" 2 ;;
  esac
fi
