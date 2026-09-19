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
STATE_DIR="${STATE_DIR:-$HOME/.sbshop-deploy}"
PENDING_TAG="pending-prev"
REGISTRY_PREFIX="${REGISTRY_PREFIX:-ghcr.io/goottjason/sbshop-agent}"
IMAGE_TAG="${IMAGE_TAG:-}"
IMAGE_PULL="${IMAGE_PULL:-1}"
AUTO_ROLLBACK="${AUTO_ROLLBACK:-1}"
SCRAPER_CONTAINER="${SCRAPER_CONTAINER:-projects-sbshop-scraper-1}"
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
declare -A PREV_TAG=()
REPLACED=()

log() { echo "[deploy $(date +%H:%M:%S)] $*"; }
warn() { echo "[deploy $(date +%H:%M:%S)] 경고: $*" >&2; }
die() { echo "[deploy $(date +%H:%M:%S)] 실패: $1" >&2; exit "${2:-1}"; }

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
  local s items=()
  read -r -a items <<< "$RECREATE"
  for s in "${items[@]+"${items[@]}"}"; do
    valid_service "$s" || die "알 수 없는 서비스(RECREATE): $s — 허용: ${SERVICES[*]}" 2
  done
}

valid_image_tag() { [[ "$1" =~ ^[a-z0-9][a-z0-9._-]{0,127}$ ]]; }

valid_registry_prefix() { [[ "$1" =~ ^[a-z0-9][a-z0-9./_-]*$ ]]; }

registry_ref() { echo "${REGISTRY_PREFIX}-${1#sbshop-}:$2"; }

pull_service_image() {
  local ref
  ref="$(registry_ref "$1" "$2")"
  log "pull: $ref"
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: docker pull $ref"; return 0; fi
  if [ "$IMAGE_PULL" = 0 ]; then
    docker image inspect "$ref" >/dev/null 2>&1 || return 1
    return 0
  fi
  docker pull "$ref" >/dev/null || return 1
}

retag_pulled_image() {
  local ref
  ref="$(registry_ref "$1" "$2")"
  run docker tag "$ref" "$(image_of "$1"):latest" || return 1
  run docker rmi "$ref" >/dev/null 2>&1 || true
}

drop_pulled_refs() {
  local svc
  for svc in "${SERVICES[@]}"; do
    run docker rmi "$(registry_ref "$svc" "$1")" >/dev/null 2>&1 || true
  done
}

pull_images() {
  local svc
  for svc in "${SERVICES[@]}"; do
    if ! pull_service_image "$svc" "$IMAGE_TAG"; then
      drop_pulled_refs "$IMAGE_TAG"
      die "이미지 pull 실패: $(registry_ref "$svc" "$IMAGE_TAG") — 컨테이너는 변경하지 않았습니다"
    fi
  done
  for svc in "${SERVICES[@]}"; do
    retag_pulled_image "$svc" "$IMAGE_TAG" || die "$svc: pull 한 이미지를 로컬 이름으로 태그하지 못했습니다 — 컨테이너는 변경하지 않았습니다"
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

image_fingerprint() {
  local out
  out="$(docker image inspect -f '{{json .Config.Env}}{{json .Config.Cmd}}{{json .Config.Entrypoint}}{{json .Config.WorkingDir}}{{json .Config.User}}{{json .Config.ExposedPorts}}{{json .Config.Volumes}}{{json .Config.Healthcheck}}{{json .RootFS}}' "$(image_of "$1"):latest" 2>/dev/null)" || true
  [ -n "$out" ] || return 0
  printf '%s' "$out" | sha256sum | cut -d' ' -f1
}

recorded_fingerprint() { cat "$STATE_DIR/$1.fp" 2>/dev/null || true; }

record_fingerprint() {
  local fp
  fp="$(image_fingerprint "$1")"
  [ -n "$fp" ] || return 0
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: 배포 지문 기록: $1"; return 0; fi
  if ! { mkdir -p "$STATE_DIR" && printf '%s\n' "$fp" > "$STATE_DIR/$1.fp"; } 2>/dev/null; then
    warn "배포 지문 기록 실패: $STATE_DIR/$1.fp — 다음 배포에서 $1 를 한 번 더 교체합니다"
  fi
}

service_changed() {
  local built recorded wanted current
  case " $RECREATE " in *" $1 "*) return 0 ;; esac
  if [ -z "$(running_image_id "$1")" ]; then return 0; fi
  built="$(image_fingerprint "$1")"
  recorded="$(recorded_fingerprint "$1")"
  if [ -z "$built" ] || [ "$built" != "$recorded" ]; then return 0; fi
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

container_exists() { docker inspect "$1" >/dev/null 2>&1; }

remove_leftovers() {
  local name svc primary
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    svc="${name#*_projects-}"; svc="${svc%-1}"; primary="projects-$svc-1"
    if ! valid_service "$svc" && ! container_exists "$primary"; then
      warn "찌꺼기 $name 는 지우지 않습니다 — 본체 $primary 가 없어 이 스크립트가 되살리지 못합니다. 직접 확인하세요"
      continue
    fi
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

snapshot_prev() {
  local svc
  for svc in "${SERVICES[@]}"; do
    if [ -n "$(built_image_id "$svc")" ]; then
      run docker tag "$(image_of "$svc"):latest" "$(image_of "$svc"):$PENDING_TAG"
    fi
  done
}

drop_pending() {
  run docker rmi "$(image_of "$1"):$PENDING_TAG" >/dev/null 2>&1 || true
}

tag_prev() {
  local svc="$1" tag pending
  pending="$(image_of "$svc"):$PENDING_TAG"
  if [ "$DRY_RUN" != 1 ] && ! docker image inspect "$pending" >/dev/null 2>&1; then return 0; fi
  tag="prev-$(date +%Y%m%d-%H%M%S)"
  log "롤백용 태그: $(image_of "$svc"):$tag"
  run docker tag "$pending" "$(image_of "$svc"):$tag" || return 1
  PREV_TAG[$svc]="$tag"
  drop_pending "$svc"
  prune_prev_tags "$svc"
}

replace_service() {
  local svc="$1"
  log "교체: $svc"
  run docker rm -f "$(container_of "$svc")" >/dev/null 2>&1 || true
  (cd "$COMPOSE_DIR" && run docker compose up -d --no-build --no-deps "$svc") || return 4
}

abort_partial() {
  local msg="$1" code="$2" reloaded="$3"
  if [ "$reloaded" = 1 ]; then
    reload_nginx || warn "nginx reload 도 실패했습니다"
    msg="$msg (앞서 교체한 서비스는 떠 있고 nginx 는 다시 읽혔습니다)"
  fi
  die "$msg" "$code"
}

auto_rollback() {
  local msg="$1" i svc tag ok=1 reload_needed=0 routes=1
  [ "$AUTO_ROLLBACK" = 1 ] || return 1
  [ "${#REPLACED[@]}" -gt 0 ] || return 1
  warn "$msg — 자동 롤백을 시작합니다: ${REPLACED[*]}"
  for ((i = ${#REPLACED[@]} - 1; i >= 0; i--)); do
    svc="${REPLACED[$i]}"
    tag="${PREV_TAG[$svc]:-}"
    if [ -z "$tag" ]; then warn "$svc: 되돌릴 이전 이미지가 없습니다"; ok=0; continue; fi
    if ! { run docker tag "$(image_of "$svc"):$tag" "$(image_of "$svc"):latest" && replace_service "$svc"; }; then
      warn "$svc: 이전 이미지로 되돌리지 못했습니다"; ok=0; continue
    fi
    case "$svc" in sbshop-api|sbshop-frontend) reload_needed=1 ;; esac
  done
  if [ "$reload_needed" = 1 ]; then reload_nginx || { warn "롤백 후 nginx reload 실패"; routes=0; }; fi
  for svc in "${REPLACED[@]}"; do check_service "$svc" "$routes" || ok=0; done
  if [ "$ok" = 1 ]; then
    die "$msg — 자동 롤백 완료: 이전 버전으로 정상 동작합니다(이번 배포는 실패로 기록됩니다)" 7
  fi
  die "$msg — 자동 롤백도 실패했습니다. 서비스가 내려갔을 수 있습니다: ./ops/deploy.sh rollback <서비스> 를 실행하세요" 8
}

fail_deploy() {
  auto_rollback "$1" || true
  abort_partial "$1" "$2" "$3"
}

replace_failure_message() {
  echo "$1 기동 실패 — 컨테이너가 내려간 상태입니다. 원인을 확인한 뒤 ./ops/deploy.sh 를 다시 실행하거나 ./ops/deploy.sh rollback $1 를 실행하세요"
}

verify_running_image() {
  local svc="$1" built running
  if [ "$DRY_RUN" = 1 ]; then return 0; fi
  built="$(built_image_id "$svc")"
  running="$(running_image_id "$svc")"
  if [ "$built" != "$running" ]; then return 6; fi
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

wait_ok() {
  local label="$1"; shift
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: $label 점검 생략"; return 0; fi
  local waited=0
  while [ "$waited" -lt "$HEALTH_WAIT_SEC" ]; do
    if "$@"; then log "$label 통과"; return 0; fi
    sleep "$HEALTH_POLL_SEC"
    waited=$((waited + HEALTH_POLL_SEC))
  done
  warn "$label 이(가) ${HEALTH_WAIT_SEC}초 안에 정상이 되지 못했습니다"
  return 1
}

http_code_via_nginx() {
  docker exec "$NGINX_CONTAINER" curl -s -o /dev/null -w '%{http_code}' -m 5 "http://127.0.0.1$1" 2>/dev/null || true
}

api_routed() {
  case "$(http_code_via_nginx /sbshop-agent/api/v1/products)" in 200|301|302|401|403) return 0 ;; esac
  return 1
}

frontend_routed() { [ "$(http_code_via_nginx /sbshop-agent/)" = 200 ]; }

scraper_healthy() {
  docker exec "$SCRAPER_CONTAINER" curl -fsS -m 5 http://127.0.0.1:8099/health 2>/dev/null | grep -q '"ok":true'
}

check_service() {
  local svc="$1" routes="${2:-1}"
  case "$svc" in
    sbshop-api)
      wait_api_healthy || return 1
      if [ "$routes" = 1 ]; then wait_ok "api 라우팅(nginx→api)" api_routed || return 1; fi ;;
    sbshop-frontend)
      if [ "$routes" = 1 ]; then wait_ok "frontend 라우팅(nginx→frontend)" frontend_routed || return 1; fi ;;
    sbshop-scraper)
      wait_ok "scraper 헬스" scraper_healthy || return 1 ;;
  esac
  return 0
}

acquire_lock() {
  if [ "$DRY_RUN" = 1 ]; then return 0; fi
  exec 9>"$LOCK_FILE"
  flock -w "$LOCK_WAIT_SEC" 9 || die "배포/청소 잠금을 ${LOCK_WAIT_SEC}초 안에 얻지 못했습니다"
}

rollback_service() {
  local svc="${1:-}" tag="${2:-}" tags
  [ -n "$svc" ] || die "사용법: deploy.sh rollback <서비스> [태그|커밋태그12자리]" 2
  valid_service "$svc" || die "알 수 없는 서비스: $svc — 허용: ${SERVICES[*]}" 2
  if [[ "$tag" =~ ^[0-9a-f]{12}$ ]]; then
    valid_registry_prefix "$REGISTRY_PREFIX" || die "레지스트리 접두어 형식이 올바르지 않습니다: $REGISTRY_PREFIX" 2
    log "롤백: $svc ← 레지스트리 $tag"
    pull_service_image "$svc" "$tag" || die "이미지 pull 실패: $(registry_ref "$svc" "$tag") — 컨테이너는 변경하지 않았습니다"
    retag_pulled_image "$svc" "$tag" || die "$svc: pull 한 이미지를 로컬 이름으로 태그하지 못했습니다 — 컨테이너는 변경하지 않았습니다"
  else
    tags="$(docker image ls "$(image_of "$svc")" --format '{{.Tag}}' | grep '^prev-' | sort -r || true)"
    [ -n "$tags" ] || die "$svc: 되돌릴 prev- 태그가 없습니다"
    if [ -z "$tag" ]; then
      tag="$(echo "$tags" | head -1)"
    elif ! echo "$tags" | grep -qx "$tag"; then
      die "$svc: 태그 $tag 가 없습니다. 있는 태그: $(echo "$tags" | tr '\n' ' ')"
    fi
    log "롤백: $svc ← $tag"
    run docker tag "$(image_of "$svc"):$tag" "$(image_of "$svc"):latest"
  fi
  replace_service "$svc" || die "$(replace_failure_message "$svc")" 4
  local nginx_failed=0
  case "$svc" in
    sbshop-api|sbshop-frontend) reload_nginx || nginx_failed=1 ;;
  esac
  local routes=1
  if [ "$nginx_failed" = 1 ]; then routes=0; fi
  if ! check_service "$svc" "$routes"; then
    die "롤백 후 $svc 가 정상이 되지 못했습니다$([ "$nginx_failed" = 1 ] && echo ' (nginx reload 실패도 있었습니다)')" 6
  fi
  record_fingerprint "$svc"
  if [ "$nginx_failed" = 1 ]; then
    die "롤백 후 nginx reload 실패 — 새 컨테이너는 떠 있습니다. docker exec $NGINX_CONTAINER nginx -s reload 를 확인하세요" 5
  fi
  log "롤백 완료: $svc"
}

main() {
  if [ "$DRY_RUN" = 1 ]; then log "DRY_RUN: 빌드를 건너뛰므로 변경 판정은 이미 있던 이미지 기준입니다(새 코드는 반영되지 않습니다)"; fi
  validate_recreate
  if [ -n "$IMAGE_TAG" ]; then
    valid_image_tag "$IMAGE_TAG" || die "이미지 태그 형식이 올바르지 않습니다: $IMAGE_TAG" 2
    valid_registry_prefix "$REGISTRY_PREFIX" || die "레지스트리 접두어 형식이 올바르지 않습니다: $REGISTRY_PREFIX" 2
  fi
  acquire_lock
  check_disk || die "디스크 여유 부족" 1
  wait_for_idle_batches || die "도는 소싱처 배치 때문에 배포를 시작하지 않았습니다" 3

  snapshot_prev

  if [ -n "$IMAGE_TAG" ]; then
    log "이미지 받기(태그 $IMAGE_TAG, 실패하면 실행 중인 컨테이너는 그대로 둡니다)"
    pull_images
  else
    log "빌드(실패하면 실행 중인 컨테이너는 그대로 둡니다)"
    (cd "$COMPOSE_DIR" && run docker compose build "${SERVICES[@]}") || die "이미지 빌드 실패 — 컨테이너는 변경하지 않았습니다"
  fi
  require_built_images

  remove_leftovers

  local changed=() svc
  for svc in "${SERVICES[@]}"; do
    if service_changed "$svc"; then changed+=("$svc"); else drop_pending "$svc"; fi
  done

  if [ "${#changed[@]}" -eq 0 ]; then
    log "이미지·설정이 바뀐 서비스가 없어 교체하지 않습니다"
    return 0
  fi
  log "교체 대상: ${changed[*]}"

  local reload=0 nginx_failed=0
  for svc in "${changed[@]}"; do
    tag_prev "$svc" || abort_partial "$svc: 롤백용 태그를 만들지 못했습니다 — 이 서비스는 교체하지 않았습니다" 1 "$reload"
    REPLACED+=("$svc")
    replace_service "$svc" || fail_deploy "$(replace_failure_message "$svc")" 4 "$reload"
    case "$svc" in
      sbshop-api) reload=1 ;;
      sbshop-frontend) reload=1 ;;
    esac
    verify_running_image "$svc" || fail_deploy "$svc: 실행 중인 이미지가 방금 빌드한 이미지와 다릅니다 — 서비스가 내려갔을 수 있습니다" 6 "$reload"
  done
  if [ "$reload" = 1 ]; then
    reload_nginx || nginx_failed=1
  fi
  local routes=1
  if [ "$nginx_failed" = 1 ]; then routes=0; fi
  for svc in "${changed[@]}"; do
    if ! check_service "$svc" "$routes"; then
      fail_deploy "$svc 점검 실패$([ "$nginx_failed" = 1 ] && echo ' (nginx reload 실패도 있었습니다)') — 롤백하려면: ./ops/deploy.sh rollback $svc" 6 0
    fi
  done
  for svc in "${changed[@]}"; do record_fingerprint "$svc"; done
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
