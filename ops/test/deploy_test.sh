#!/usr/bin/env bash
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ok    $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "기대 [$2] 실제 [$3]"; fi; }
assert_contains() { case "$3" in *"$2"*) ok "$1";; *) bad "$1" "[$2] 없음 ← $3";; esac; }
assert_not_contains() { case "$3" in *"$2"*) bad "$1" "[$2] 가 있으면 안 됨 ← $3";; *) ok "$1";; esac; }

TMPD="$(mktemp -d)"; CALLLOG="$TMPD/calls.log"; BATCH_IDX_FILE="$TMPD/batch.idx"; HEALTH_N_FILE="$TMPD/health.n"; LOCKSTATE="$TMPD/lockstate"
trap 'rm -rf "$TMPD"' EXIT

new_env() {
  : > "$CALLLOG"; : > "$LOCKSTATE"; echo 0 > "$BATCH_IDX_FILE"; echo 0 > "$HEALTH_N_FILE"; rm -f "$TMPD"/replaced.* "$TMPD"/pending.*; rm -rf "$TMPD/state"
  OUT_PS=""; OUT_BATCH=("0"); BATCH_FAIL=0
  declare -gA BUILT=() FPC=() RUNNING=() TAGS=() HASH_RUN=() HASH_WANT=()
  HEALTH_BODY=""; BUILD_RC=0; UP_RC=0; NGINX_RC=0; STALE_AFTER_UP=0; HEALTH_UP_AFTER=1; DF_AVAIL=99999999; SLEPT=0
  export FORCE=0 RECREATE="" DRY_RUN=0 POLL_SEC=1 BATCH_WAIT_SEC=3 HEALTH_WAIT_SEC=4 HEALTH_POLL_SEC=1 MIN_FREE_KB=10485760 KEEP_PREV=3
  export LOCK_FILE="$TMPD/lock" COMPOSE_DIR="$HERE/.." STATE_DIR="$TMPD/state"
}

docker() {
  echo "docker $*" >> "$CALLLOG"
  case "$1" in
    ps) printf '%s' "$OUT_PS" ;;
    image)
      if [ "$2" = inspect ]; then
        local n="${*: -1}"
        case "$n" in
          *:pending-prev) [ -f "$TMPD/pending.${n%:pending-prev}" ]; return $? ;;
        esac
        n="${n%:latest}"
        [ -n "${BUILT[$n]:-}" ] || return 1
        case "$*" in *json*) echo "${FPC[$n]:-${BUILT[$n]}}" ;; *) echo "${BUILT[$n]}" ;; esac
      elif [ "$2" = ls ]; then printf '%s\n' "${TAGS[$3]:-}"; fi ;;
    tag) case "${*: -1}" in *:pending-prev) local pn="${*: -1}"; touch "$TMPD/pending.${pn%:pending-prev}" ;; esac ;;
    rmi) case "${*: -1}" in *:pending-prev) local rn="${*: -1}"; rm -f "$TMPD/pending.${rn%:pending-prev}" ;; esac ;;
    inspect)
      local c="${*: -1}" svc img
      svc="${c#projects-}"; svc="${svc%-1}"; img="sbshop-agent-$svc"
      case "$*" in *config-hash*) echo "${HASH_RUN[$svc]:-}"; return 0 ;; esac
      if [ -f "$TMPD/replaced.$svc" ] && [ "$STALE_AFTER_UP" != 1 ]; then echo "${BUILT[$img]:-}"; return 0; fi
      echo "${RUNNING[$c]:-}"; [ -n "${RUNNING[$c]:-}" ] || return 1 ;;
    exec)
      case "$*" in
        *psql*) if [ "$BATCH_FAIL" = 1 ]; then return 1; fi
                local i; i="$(cat "$BATCH_IDX_FILE")"; echo "${OUT_BATCH[$i]}"
                [ "$i" -lt $((${#OUT_BATCH[@]} - 1)) ] && echo $((i + 1)) > "$BATCH_IDX_FILE"; return 0 ;;
        *curl*) local h; h=$(( $(cat "$HEALTH_N_FILE") + 1 )); echo "$h" > "$HEALTH_N_FILE"
                if [ "$h" -ge "$HEALTH_UP_AFTER" ]; then echo "${HEALTH_BODY:-{\"status\":\"UP\",\"db\":\"UP\"\}}"; else return 22; fi ;;
        *nginx*) return "$NGINX_RC" ;;
      esac ;;
    compose)
      case "$*" in
        *" config --hash"*) local s="${*: -1}"; [ -n "${HASH_WANT[$s]:-}" ] && echo "$s ${HASH_WANT[$s]}"; return 0 ;;
        *" build"*)
          if flock -n "$LOCK_FILE" true 2>/dev/null; then echo UNLOCKED >> "$LOCKSTATE"; else echo LOCKED >> "$LOCKSTATE"; fi
          return "$BUILD_RC" ;;
        *" up "*|*" up -d"*) [ "$UP_RC" = 0 ] && touch "$TMPD/replaced.${*: -1}"; return "$UP_RC" ;;
      esac ;;
    logs) echo "fake-log-line" ;;
    *) : ;;
  esac
}
df() { echo "Avail"; echo " $DF_AVAIL "; }
sleep() { SLEPT=$((SLEPT + 1)); }

source "$HERE/../deploy.sh"
set +e

calls_str() { cat "$CALLLOG"; }
index_of() { local n; n="$(grep -n -m1 -F -- "$1" "$CALLLOG" | cut -d: -f1)"; echo "${n:--1}"; }
count_of() { grep -c -F -- "$1" "$CALLLOG"; }
fp_of() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
sync_state() {
  local c svc
  mkdir -p "$STATE_DIR"
  for c in "${!RUNNING[@]}"; do
    svc="${c#projects-}"; svc="${svc%-1}"
    fp_of "${RUNNING[$c]}" > "$STATE_DIR/$svc.fp"
  done
}
run_main() { sync_state; ( set -euo pipefail; main ) >"$TMPD/out" 2>&1; }
set_all_same() {
  BUILT[sbshop-agent-sbshop-api]="a"; RUNNING[projects-sbshop-api-1]="a"
  BUILT[sbshop-agent-sbshop-frontend]="f"; RUNNING[projects-sbshop-frontend-1]="f"
  BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
}

echo "[leftover_containers] 해시 접두 찌꺼기만 고른다(scraper 포함)"
new_env
OUT_PS=$'projects-sbshop-api-1\na711f3f9c565_projects-sbshop-scraper-1\n5c059c02371b_projects-sbshop-frontend-1\nprojects-can-agent-1\nabc_projects-sbshop-api-1\n0123456789ab_projects-sbshop-marketplus-worker-1\n'
r="$(leftover_containers | tr '\n' ' ')"
assert_contains "scraper 찌꺼기를 잡는다" "a711f3f9c565_projects-sbshop-scraper-1" "$r"
assert_contains "frontend 찌꺼기를 잡는다" "5c059c02371b_projects-sbshop-frontend-1" "$r"
assert_not_contains "정상 컨테이너는 건드리지 않는다" " projects-sbshop-api-1 " " $r "
assert_not_contains "다른 프로젝트는 건드리지 않는다" "can-agent" "$r"
assert_not_contains "12자리 16진 해시가 아닌 접두는 제외" "abc_projects" "$r"
assert_not_contains "다른 프로젝트(marketplus)의 찌꺼기는 이 배포의 범위가 아니다" "marketplus" "$r"

echo "[service_changed] 내용 지문(상태 파일)과 compose 설정 해시 비교"
new_env; BUILT[sbshop-agent-sbshop-api]="sha256:new"; RUNNING[projects-sbshop-api-1]="sha256:new"; sync_state
service_changed sbshop-api && bad "같은 내용은 변경 아님" || ok "같은 내용은 변경 아님"
RUNNING[projects-sbshop-api-1]="sha256:old"; sync_state
service_changed sbshop-api && ok "내용이 다르면 변경" || bad "내용이 다르면 변경"
new_env; BUILT[sbshop-agent-sbshop-api]="sha256:new"; RUNNING[projects-sbshop-api-1]="sha256:new"
service_changed sbshop-api && ok "상태 파일이 없으면 변경(처음 한 번은 교체)" || bad "상태 파일 없음은 변경"
new_env; BUILT[sbshop-agent-sbshop-api]="idx-2"; FPC[sbshop-agent-sbshop-api]="content-1"; RUNNING[projects-sbshop-api-1]="idx-1"
fp_of "content-1" > /dev/null; mkdir -p "$STATE_DIR"; fp_of "content-1" > "$STATE_DIR/sbshop-api.fp"
service_changed sbshop-api && bad "빌드마다 인덱스 ID 가 바뀌어도 내용이 같으면 변경 아님(실서버 회귀)" || ok "빌드마다 인덱스 ID 가 바뀌어도 내용이 같으면 변경 아님(실서버 회귀)"
FPC[sbshop-agent-sbshop-api]="content-2"
service_changed sbshop-api && ok "내용이 바뀌면 변경" || bad "내용이 바뀌면 변경"
new_env; BUILT[sbshop-agent-sbshop-api]="a"; RUNNING[projects-sbshop-api-1]="a"; sync_state
unset 'RUNNING[projects-sbshop-api-1]'
service_changed sbshop-api && ok "실행 중인 컨테이너가 없으면 변경(새로 띄움)" || bad "컨테이너 없음은 변경"
new_env; BUILT[sbshop-agent-sbshop-api]="x"; RUNNING[projects-sbshop-api-1]="x"; HASH_RUN[sbshop-api]="h1"; HASH_WANT[sbshop-api]="h1"; sync_state
service_changed sbshop-api && bad "내용·설정 모두 같으면 변경 아님" || ok "내용·설정 모두 같으면 변경 아님"
HASH_WANT[sbshop-api]="h2"
service_changed sbshop-api && ok ".env 등으로 설정 해시만 달라져도 변경" || bad "설정 해시 차이는 변경"
HASH_WANT[sbshop-api]=""
service_changed sbshop-api && bad "설정 해시를 못 구하면 무시" || ok "설정 해시를 못 구하면 무시"

echo "[wait_for_idle_batches] 도는 배치가 있으면 기다리고, 끝내 안 끝나면 실패"
new_env; OUT_BATCH=("0")
wait_for_idle_batches; assert_eq "배치 없으면 즉시 통과" 0 $?; assert_eq "기다리지 않는다" 0 "$SLEPT"
new_env; OUT_BATCH=("2" "1" "0")
wait_for_idle_batches; assert_eq "끝나면 통과" 0 $?; assert_eq "두 번 기다렸다" 2 "$SLEPT"
new_env; OUT_BATCH=("1")
wait_for_idle_batches; assert_eq "제한 시간 안에 안 끝나면 실패(1)" 1 $?
new_env; OUT_BATCH=("1"); FORCE=1
wait_for_idle_batches; assert_eq "FORCE=1 이면 배치가 있어도 통과" 0 $?
assert_not_contains "FORCE=1 이면 DB를 조회하지도 않는다" "psql" "$(calls_str)"
new_env; BATCH_FAIL=1
wait_for_idle_batches; assert_eq "DB 조회 실패는 막지 않는다(경고 후 통과)" 0 $?
new_env; OUT_BATCH=("0")
wait_for_idle_batches
c="$(calls_str)"
assert_contains "배치 테이블을 조회한다" "sb_supplier_batch_run" "$c"
assert_contains "RUNNING·PAUSING 만 본다" "state in ('RUNNING','PAUSING')" "$c"
assert_contains "superuser 계정으로 조회한다" "psql -U goottjason -d sbshop" "$c"

echo "[check_disk] 여유 10GiB 미만이면 실패"
new_env; DF_AVAIL=5000000; check_disk; assert_eq "5GB 여유는 실패" 1 $?
new_env; DF_AVAIL=20000000; check_disk; assert_eq "20GB 여유는 통과" 0 $?

echo "[tag_prev / prune_prev_tags] 교체 전 롤백용 태그, 최근 KEEP_PREV 개만 보관"
new_env; touch "$TMPD/pending.sbshop-agent-sbshop-api"
tag_prev sbshop-api
assert_contains "임시 태그에서 prev- 태그를 만든다" "docker tag sbshop-agent-sbshop-api:pending-prev sbshop-agent-sbshop-api:prev-" "$(calls_str)"
assert_contains "임시 태그는 지운다" "rmi sbshop-agent-sbshop-api:pending-prev" "$(calls_str)"
new_env
tag_prev sbshop-api
assert_not_contains "임시 태그가 없으면 태그하지 않는다" "docker tag" "$(calls_str)"

echo "[snapshot_prev / drop_pending] 빌드 전에 현재 latest 를 붙잡아 둔다"
new_env; BUILT[sbshop-agent-sbshop-api]="a"; BUILT[sbshop-agent-sbshop-scraper]="s"
snapshot_prev
c="$(calls_str)"
assert_contains "api latest 에 임시 태그" "docker tag sbshop-agent-sbshop-api:latest sbshop-agent-sbshop-api:pending-prev" "$c"
assert_contains "scraper latest 에 임시 태그" "docker tag sbshop-agent-sbshop-scraper:latest sbshop-agent-sbshop-scraper:pending-prev" "$c"
assert_not_contains "이미지가 없는 frontend 는 태그하지 않는다" "sbshop-frontend:pending-prev" "$c"
new_env; touch "$TMPD/pending.sbshop-agent-sbshop-api"
drop_pending sbshop-api
assert_contains "임시 태그를 지운다" "rmi sbshop-agent-sbshop-api:pending-prev" "$(calls_str)"

new_env; TAGS[sbshop-agent-sbshop-api]=$'prev-20260101-000001\nlatest\nprev-20260103-000001\nprev-20260102-000001\nprev-20260104-000001\nprev-20260105-000001'
prune_prev_tags sbshop-api
c="$(calls_str)"
assert_contains "가장 오래된 태그를 지운다" "rmi sbshop-agent-sbshop-api:prev-20260101-000001" "$c"
assert_contains "그다음 오래된 태그도 지운다" "rmi sbshop-agent-sbshop-api:prev-20260102-000001" "$c"
assert_not_contains "최신 3개는 보관한다" "rmi sbshop-agent-sbshop-api:prev-20260105-000001" "$c"
assert_not_contains "보관 경계(세 번째 최신)를 지우지 않는다" "rmi sbshop-agent-sbshop-api:prev-20260103-000001" "$c"
assert_eq "정확히 2개만 지운다" 2 "$(count_of 'rmi sbshop-agent-sbshop-api:prev-')"
assert_not_contains "latest 는 지우지 않는다" "rmi sbshop-agent-sbshop-api:latest" "$c"

echo "[acquire_lock] 배포/청소 공용 잠금"
new_env
( acquire_lock; flock -n "$LOCK_FILE" true ); assert_eq "잠금을 쥔 동안 다른 프로세스는 못 얻는다" 1 $?
flock -n "$LOCK_FILE" true; assert_eq "끝나면 풀린다" 0 $?
new_env; DRY_RUN=1
( acquire_lock; flock -n "$LOCK_FILE" true ); assert_eq "DRY_RUN=1 은 잠그지 않는다" 0 $?

echo "[wait_api_healthy] /internal/health 가 UP 이 될 때까지"
new_env; HEALTH_UP_AFTER=3; wait_api_healthy; assert_eq "세 번째 응답에 UP → 통과" 0 $?
new_env; HEALTH_UP_AFTER=999; wait_api_healthy; assert_eq "끝내 UP 이 아니면 실패" 1 $?
assert_contains "실패하면 컨테이너 로그를 보여준다" "docker logs" "$(calls_str)"

echo "[main] 정상 배포: 이미지가 바뀐 서비스만 교체"
new_env
BUILT[sbshop-agent-sbshop-api]="new-api"; BUILT[sbshop-agent-sbshop-frontend]="same-fe"; BUILT[sbshop-agent-sbshop-scraper]="same-sc"
RUNNING[projects-sbshop-api-1]="old-api"; RUNNING[projects-sbshop-frontend-1]="same-fe"; RUNNING[projects-sbshop-scraper-1]="same-sc"
OUT_PS=$'a711f3f9c565_projects-sbshop-scraper-1\n'
run_main; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "세 서비스를 모두 빌드한다" "build sbshop-api sbshop-frontend sbshop-scraper" "$c"
assert_contains "찌꺼기 컨테이너를 지운다" "rm -f a711f3f9c565_projects-sbshop-scraper-1" "$c"
assert_contains "바뀐 api 만 교체한다" "up -d --no-build --no-deps sbshop-api" "$c"
assert_not_contains "안 바뀐 frontend 는 건드리지 않는다" "up -d --no-build --no-deps sbshop-frontend" "$c"
assert_not_contains "안 바뀐 scraper 도 건드리지 않는다" "up -d --no-build --no-deps sbshop-scraper" "$c"
assert_contains "nginx 를 reload 한다" "nginx -s reload" "$c"
assert_contains "api 헬스체크를 한다" "internal/health" "$c"
assert_eq "빌드하는 동안 배포 잠금을 쥐고 있다" "LOCKED" "$(head -1 "$LOCKSTATE")"
b=$(index_of "compose build"); t=$(index_of "docker tag sbshop-agent-sbshop-api:pending-prev sbshop-agent-sbshop-api:prev-"); rm_=$(index_of "rm -f projects-sbshop-api-1"); u=$(index_of "up -d --no-build --no-deps sbshop-api"); n=$(index_of "nginx -s reload"); h=$(index_of "internal/health")
[ "$b" -ge 0 ] && [ "$b" -lt "$t" ] && [ "$t" -lt "$rm_" ] && [ "$rm_" -lt "$u" ] && [ "$u" -lt "$n" ] && [ "$n" -lt "$h" ] && ok "순서: 빌드 → prev 태그 → 옛 컨테이너 제거 → 기동 → nginx → 헬스" || bad "실행 순서가 틀림" "build=$b tag=$t rm=$rm_ up=$u nginx=$n health=$h"

echo "[main] scraper 만 바뀌면 nginx·api 헬스는 생략"
new_env
BUILT[sbshop-agent-sbshop-api]="a"; BUILT[sbshop-agent-sbshop-frontend]="f"; BUILT[sbshop-agent-sbshop-scraper]="new-sc"
RUNNING[projects-sbshop-api-1]="a"; RUNNING[projects-sbshop-frontend-1]="f"; RUNNING[projects-sbshop-scraper-1]="old-sc"
run_main; c="$(calls_str)"
assert_contains "scraper 를 교체한다" "up -d --no-build --no-deps sbshop-scraper" "$c"
assert_not_contains "api 는 건드리지 않는다" "up -d --no-build --no-deps sbshop-api" "$c"
assert_not_contains "nginx reload 생략" "nginx -s reload" "$c"
assert_not_contains "api 헬스체크 생략" "internal/health" "$c"

echo "[main] .env 처럼 설정 해시만 바뀌어도 그 서비스를 다시 만든다"
new_env; set_all_same
HASH_RUN[sbshop-api]="h1"; HASH_WANT[sbshop-api]="h2"
run_main; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "설정이 바뀐 api 를 다시 만든다" "up -d --no-build --no-deps sbshop-api" "$c"

echo "[main] RECREATE 로 지정한 서비스는 이미지가 같아도 다시 만든다(운영 검증용)"
new_env; RECREATE="sbshop-api"; set_all_same
run_main; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "지정한 api 를 다시 만든다" "up -d --no-build --no-deps sbshop-api" "$c"
assert_not_contains "지정하지 않은 frontend 는 건드리지 않는다" "up -d --no-build --no-deps sbshop-frontend" "$c"
assert_contains "api 를 다시 만들었으니 헬스체크를 한다" "internal/health" "$c"
assert_contains "교체 전 prev 태그를 남긴다" "docker tag sbshop-agent-sbshop-api:pending-prev sbshop-agent-sbshop-api:prev-" "$c"

echo "[main] 알 수 없는 서비스 이름은 빌드 전에 거절한다"
new_env; RECREATE="sbshop-apii"; set_all_same
run_main; rc=$?; c="$(calls_str)"
assert_eq "코드 2 로 종료" 2 "$rc"
assert_contains "이유를 알려준다" "알 수 없는 서비스" "$(cat "$TMPD/out")"
assert_not_contains "빌드하지 않는다" "compose build" "$c"

echo "[main] 교체했는데 옛 이미지로 실행 중이면 실패한다(verify_running_image)"
new_env; STALE_AFTER_UP=1; set_all_same
BUILT[sbshop-agent-sbshop-scraper]="new-sc"; RUNNING[projects-sbshop-scraper-1]="old-sc"
run_main; rc=$?
assert_eq "코드 6(서비스가 내려갔을 수 있음)으로 종료" 6 "$rc"
assert_contains "이유를 알려준다" "방금 빌드한 이미지와 다릅니다" "$(cat "$TMPD/out")"

echo "[main] 기동(compose up)이 실패하면 즉시 멈추고 알린다(서비스가 내려간 상태)"
new_env; UP_RC=1
BUILT[sbshop-agent-sbshop-api]="new-api"; RUNNING[projects-sbshop-api-1]="old-api"
BUILT[sbshop-agent-sbshop-frontend]="new-fe"; RUNNING[projects-sbshop-frontend-1]="old-fe"
BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
run_main; rc=$?; c="$(calls_str)"
assert_eq "코드 4 로 종료" 4 "$rc"
assert_contains "기동 실패를 말한다" "기동 실패" "$(cat "$TMPD/out")"
assert_contains "롤백 방법을 안내한다" "rollback sbshop-api" "$(cat "$TMPD/out")"
assert_eq "실패 뒤 다른 서비스를 이어서 교체하지 않는다" 1 "$(count_of 'up -d --no-build')"

echo "[main] nginx reload 가 실패해도 헬스체크는 하고, 끝에 실패로 알린다"
new_env; NGINX_RC=1
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
BUILT[sbshop-agent-sbshop-frontend]="f"; RUNNING[projects-sbshop-frontend-1]="f"
BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
run_main; rc=$?; c="$(calls_str)"
assert_eq "코드 5 로 종료" 5 "$rc"
assert_contains "헬스체크는 수행했다" "internal/health" "$c"
assert_contains "nginx 실패를 말한다" "nginx reload 실패" "$(cat "$TMPD/out")"

echo "[main] api 헬스체크가 끝내 UP 이 아니면 실패로 끝난다"
new_env; HEALTH_UP_AFTER=999; set_all_same
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?
assert_eq "코드 6(서비스가 내려갔을 수 있음)으로 종료" 6 "$rc"
assert_contains "롤백 방법을 안내한다" "rollback sbshop-api" "$(cat "$TMPD/out")"
assert_not_contains "배포 완료라고 하지 않는다" "배포 완료" "$(cat "$TMPD/out")"

echo "[main] 빌드 산출 이미지를 못 찾으면 컨테이너를 건드리지 않는다"
new_env
BUILT[sbshop-agent-sbshop-frontend]="f"; RUNNING[projects-sbshop-frontend-1]="f"
BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?; c="$(calls_str)"
assert_eq "실패로 종료" 1 "$rc"
assert_not_contains "컨테이너를 지우지 않는다" "rm -f projects-sbshop" "$c"
assert_not_contains "새로 띄우지 않는다" "up -d" "$c"

echo "[main] 아무것도 안 바뀌면 교체 없이 끝난다"
new_env; set_all_same
run_main; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_not_contains "교체하지 않는다" "up -d --no-build" "$c"
assert_not_contains "컨테이너를 지우지 않는다" "rm -f projects-sbshop" "$c"

echo "[main] 빌드가 실패하면 실행 중인 컨테이너를 건드리지 않는다"
new_env; BUILD_RC=1
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?; c="$(calls_str)"
assert_eq "실패로 종료" 1 "$rc"
assert_not_contains "컨테이너를 지우지 않는다" "rm -f" "$c"
assert_not_contains "새로 띄우지 않는다" "up -d" "$c"

echo "[main] 도는 배치가 안 끝나면 빌드도 하지 않고 실패한다"
new_env; OUT_BATCH=("1")
run_main; rc=$?; c="$(calls_str)"
assert_eq "코드 3 으로 종료" 3 "$rc"
assert_contains "실패 줄로 이유를 밝힌다" "실패:" "$(cat "$TMPD/out")"
assert_not_contains "빌드하지 않는다" "compose build" "$c"

echo "[main] 디스크가 부족하면 아무것도 하지 않는다"
new_env; DF_AVAIL=1000
run_main; rc=$?; c="$(calls_str)"
assert_eq "코드 1 로 종료" 1 "$rc"
assert_not_contains "빌드하지 않는다" "compose build" "$c"

echo "[main] DRY_RUN=1 이면 컨테이너를 바꾸는 명령을 실행하지 않고 하려던 일만 출력한다"
new_env; DRY_RUN=1; set_all_same
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "교체하려던 것을 출력한다" "DRY_RUN: docker compose up -d --no-build --no-deps sbshop-api" "$(cat "$TMPD/out")"
assert_contains "롤백용 태그도 출력만 한다" "DRY_RUN: docker tag sbshop-agent-sbshop-api:pending-prev sbshop-agent-sbshop-api:prev-" "$(cat "$TMPD/out")"
assert_not_contains "빌드도 실행하지 않는다" "compose build" "$c"
assert_not_contains "rm 하지 않는다" "rm -f" "$c"
assert_not_contains "up 하지 않는다" "up -d" "$c"
assert_not_contains "tag 하지 않는다" "docker tag" "$c"
assert_not_contains "nginx reload 하지 않는다" "nginx -s reload" "$c"

echo "[main] 실서버 회귀: 빌드마다 인덱스 ID 가 달라져도 내용이 같으면 교체하지 않는다"
new_env
for n in api frontend scraper; do BUILT[sbshop-agent-sbshop-$n]="idx-new-$n"; FPC[sbshop-agent-sbshop-$n]="content-$n"; RUNNING[projects-sbshop-$n-1]="idx-old-$n"; done
mkdir -p "$STATE_DIR"; for n in api frontend scraper; do fp_of "content-$n" > "$STATE_DIR/sbshop-$n.fp"; done
( set -euo pipefail; main ) >"$TMPD/out" 2>&1; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_not_contains "교체하지 않는다" "up -d --no-build" "$c"
assert_not_contains "롤백용 태그를 만들지 않는다" "prev-" "$c"
assert_contains "임시 태그는 치운다" "rmi sbshop-agent-sbshop-api:pending-prev" "$c"

echo "[main] 빌드 전에 현재 latest 를 임시 태그로 붙잡는다(빌드하면 옛 이미지를 잃는다)"
new_env
BUILT[sbshop-agent-sbshop-api]="new-api"; RUNNING[projects-sbshop-api-1]="old-api"
BUILT[sbshop-agent-sbshop-frontend]="f"; RUNNING[projects-sbshop-frontend-1]="f"
BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
run_main; c="$(calls_str)"
sn=$(index_of "docker tag sbshop-agent-sbshop-api:latest sbshop-agent-sbshop-api:pending-prev"); bd=$(index_of "compose build")
[ "$sn" -ge 0 ] && [ "$sn" -lt "$bd" ] && ok "임시 태그가 빌드보다 먼저" || bad "임시 태그가 빌드보다 먼저여야 함" "snapshot=$sn build=$bd"

echo "[main] 성공하면 교체한 서비스의 내용 지문을 기록한다"
new_env
BUILT[sbshop-agent-sbshop-api]="new-api"; RUNNING[projects-sbshop-api-1]="old-api"
BUILT[sbshop-agent-sbshop-frontend]="f"; RUNNING[projects-sbshop-frontend-1]="f"
BUILT[sbshop-agent-sbshop-scraper]="s"; RUNNING[projects-sbshop-scraper-1]="s"
run_main; rc=$?
assert_eq "정상 종료" 0 "$rc"
assert_eq "api 지문이 새 내용으로 기록된다" "$(fp_of new-api)" "$(cat "$STATE_DIR/sbshop-api.fp")"
assert_eq "안 바뀐 frontend 지문은 그대로" "$(fp_of f)" "$(cat "$STATE_DIR/sbshop-frontend.fp")"

echo "[main] 헬스체크가 실패하면 지문을 기록하지 않는다(같은 커밋으로 다시 실행하면 재시도된다)"
new_env; HEALTH_UP_AFTER=999; set_all_same
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?
assert_eq "코드 6 으로 종료" 6 "$rc"
assert_eq "api 지문은 옛 내용 그대로" "$(fp_of old)" "$(cat "$STATE_DIR/sbshop-api.fp")"

echo "[rollback_service] 가장 최근 prev- 태그로 되돌린다"
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260101-000001\nprev-20260105-000001'
RUNNING[projects-sbshop-api-1]="cur"
BUILT[sbshop-agent-sbshop-api]="rolled"
( set -euo pipefail; rollback_service sbshop-api ) >"$TMPD/out" 2>&1; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "최신 prev 를 latest 로 되돌린다" "docker tag sbshop-agent-sbshop-api:prev-20260105-000001 sbshop-agent-sbshop-api:latest" "$c"
assert_contains "그 서비스만 다시 띄운다" "up -d --no-build --no-deps sbshop-api" "$c"
assert_eq "롤백한 내용의 지문을 기록한다" "$(fp_of rolled)" "$(cat "$STATE_DIR/sbshop-api.fp" 2>/dev/null)"
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest'
( rollback_service sbshop-api ) >"$TMPD/out" 2>&1; assert_eq "prev 태그가 없으면 실패" 1 $?
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260105-000001'
( rollback_service sbshop-api prev-20260101-000001 ) >"$TMPD/out" 2>&1; assert_eq "없는 태그를 지정하면 실패" 1 $?
new_env
( rollback_service sbshop-nope ) >"$TMPD/out" 2>&1; assert_eq "알 수 없는 서비스는 거절" 2 $?

echo "[기본값] 환경변수를 주지 않고 소스했을 때의 기본값(테스트가 export 로 덮어 못 보던 부분)"
d="$(env -i HOME=/h PATH="$PATH" bash -c 'source "$1"; echo "$LOCK_WAIT_SEC $MIN_FREE_KB $HEALTH_WAIT_SEC $HEALTH_POLL_SEC $BATCH_WAIT_SEC $POLL_SEC $KEEP_PREV $FORCE $DRY_RUN|$LOCK_FILE|$STATE_DIR"' _ "$HERE/../deploy.sh")"
assert_eq "기본값이 운영 값과 같다" "900 10485760 150 3 1800 30 3 0 0|/h/.sbshop-docker-maintenance.lock|/h/.sbshop-deploy" "$d"

echo "[die] 메시지에 종료코드가 섞이지 않는다"
o="$( ( die "테스트 메시지" 4 ) 2>&1 )"; rc=$?
assert_eq "종료코드는 4" 4 "$rc"
assert_contains "메시지는 그대로" "실패: 테스트 메시지" "$o"
case "$o" in *"테스트 메시지 4") bad "메시지 끝에 종료코드가 붙으면 안 됨" "$o";; *) ok "메시지 끝에 종료코드가 붙지 않는다";; esac

echo "[validate_recreate] 글롭 문자는 파일명으로 펼쳐지지 않는다"
o="$( cd "$TMPD" && touch zz1 zz2 && RECREATE='*' && validate_recreate 2>&1 )"; rc=$?
assert_eq "코드 2 로 거절" 2 "$rc"
assert_contains "입력한 그대로 알려준다" "(RECREATE): *" "$o"
assert_not_contains "파일명으로 펼치지 않는다" "zz1" "$o"

echo "[wait_api_healthy] 200 이어도 status 가 UP 이 아니면 통과하지 않는다"
new_env; HEALTH_BODY='{"status":"DOWN","db":"DOWN"}'; wait_api_healthy; assert_eq "DOWN 본문은 실패" 1 $?

echo "[tag_prev] 태그를 만든 뒤 오래된 롤백 태그를 정리한다(배선)"
new_env; touch "$TMPD/pending.sbshop-agent-sbshop-api"
TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260101-000001\nprev-20260102-000001\nprev-20260103-000001\nprev-20260104-000001'
tag_prev sbshop-api
assert_contains "가장 오래된 태그를 지운다" "rmi sbshop-agent-sbshop-api:prev-20260101-000001" "$(calls_str)"

echo "[remove_leftovers] selenium 찌꺼기는 본체가 있을 때만 지운다(지우기만 하고 되살리지 않으므로)"
new_env; OUT_PS=$'abc123abc123_projects-sbshop-selenium-1\n'; RUNNING[projects-sbshop-selenium-1]="sel"
remove_leftovers >"$TMPD/out" 2>&1
assert_contains "본체가 있으면 지운다" "rm -f abc123abc123_projects-sbshop-selenium-1" "$(calls_str)"
new_env; OUT_PS=$'abc123abc123_projects-sbshop-selenium-1\n'
remove_leftovers >"$TMPD/out" 2>&1
assert_not_contains "본체가 없으면 지우지 않는다" "rm -f abc123abc123_projects-sbshop-selenium-1" "$(calls_str)"
assert_contains "이유를 알려준다" "본체" "$(cat "$TMPD/out")"
new_env; OUT_PS=$'a711f3f9c565_projects-sbshop-scraper-1\n'
remove_leftovers >"$TMPD/out" 2>&1
assert_contains "관리 대상 서비스 찌꺼기는 본체와 무관하게 지운다" "rm -f a711f3f9c565_projects-sbshop-scraper-1" "$(calls_str)"

echo "[main] nginx reload 와 헬스체크가 함께 실패하면 둘 다 알린다"
new_env; NGINX_RC=1; HEALTH_UP_AFTER=999; set_all_same
BUILT[sbshop-agent-sbshop-api]="new"; RUNNING[projects-sbshop-api-1]="old"
run_main; rc=$?
assert_eq "코드 6 으로 종료" 6 "$rc"
assert_contains "nginx 실패 정보가 사라지지 않는다" "nginx reload 실패" "$(cat "$TMPD/out")"

echo "[main] DRY_RUN 은 빌드를 건너뛰므로 변경 판정이 이미 있던 이미지 기준임을 알린다"
new_env; DRY_RUN=1; set_all_same
run_main
assert_contains "안내 문구" "빌드를 건너뛰" "$(cat "$TMPD/out")"

echo "[rollback_service] 롤백도 배포와 같은 사후 확인을 한다"
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260105-000001'; BUILT[sbshop-agent-sbshop-api]="rolled"; HEALTH_UP_AFTER=999
( set -euo pipefail; rollback_service sbshop-api ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "헬스체크가 끝내 실패하면 코드 6" 6 "$rc"
assert_not_contains "롤백 완료라고 하지 않는다" "롤백 완료" "$(cat "$TMPD/out")"
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260105-000001'; BUILT[sbshop-agent-sbshop-api]="rolled"; NGINX_RC=1
( set -euo pipefail; rollback_service sbshop-api ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "nginx 만 실패하면 코드 5" 5 "$rc"
assert_contains "nginx 실패를 말한다" "nginx reload 실패" "$(cat "$TMPD/out")"
assert_contains "nginx 가 실패해도 헬스체크는 한다" "internal/health" "$(calls_str)"
new_env; TAGS[sbshop-agent-sbshop-api]=$'latest\nprev-20260105-000001'; BUILT[sbshop-agent-sbshop-api]="rolled"; NGINX_RC=1; HEALTH_UP_AFTER=999
( set -euo pipefail; rollback_service sbshop-api ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "둘 다 실패하면 코드 6" 6 "$rc"
assert_contains "nginx 실패도 알린다" "nginx reload 실패" "$(cat "$TMPD/out")"
new_env; TAGS[sbshop-agent-sbshop-frontend]=$'latest\nprev-20260105-000001'; BUILT[sbshop-agent-sbshop-frontend]="rolled"
( set -euo pipefail; rollback_service sbshop-frontend ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "frontend 롤백 정상 종료" 0 "$rc"
assert_contains "frontend 롤백도 nginx 를 다시 읽힌다" "nginx -s reload" "$(calls_str)"
assert_not_contains "frontend 롤백은 api 헬스체크를 하지 않는다" "internal/health" "$(calls_str)"


echo
echo "결과: 통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ]
