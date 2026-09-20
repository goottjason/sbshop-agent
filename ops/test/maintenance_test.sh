#!/usr/bin/env bash
set -uo pipefail

TESTDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  ok    $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "기대 [$2] 실제 [$3]"; fi; }
assert_contains() { case "$3" in *"$2"*) ok "$1";; *) bad "$1" "[$2] 없음 ← $3";; esac; }
assert_not_contains() { case "$3" in *"$2"*) bad "$1" "[$2] 가 있으면 안 됨 ← $3";; *) ok "$1";; esac; }

TMPD="$(mktemp -d)"; CALLLOG="$TMPD/calls.log"
trap 'rm -rf "$TMPD"' EXIT

NOW="$(date +%s)"
ago_days() { date -u -d "@$((NOW - $1 * 86400))" +%Y-%m-%dT%H:%M:%S.000000000Z; }

new_env() {
  : > "$CALLLOG"
  IMAGES=(); USED_IDS=(); DF_PCT=40
  export LOCK_FILE="$TMPD/lock" AGE_HOURS=168
}
add_image() { IMAGES+=("$1|$2|$3"); }

docker() {
  echo "docker $*" >> "$CALLLOG"
  case "$1" in
    ps) : ;;
    inspect) printf '%s\n' "${USED_IDS[@]:-}" ;;
    image)
      case "$2" in
        ls) local l; for l in "${IMAGES[@]:-}"; do [ -n "$l" ] && echo "${l%%|*}|$(echo "$l" | cut -d'|' -f2)"; done ;;
        inspect)
          local id="${*: -1}" l
          for l in "${IMAGES[@]:-}"; do [ "${l%%|*}" = "$id" ] && { echo "$l" | cut -d'|' -f3; return 0; }; done; return 1 ;;
        prune) : ;;
      esac ;;
    rmi) : ;;
    builder) : ;;
  esac
}
timeout() { shift; "$@"; }
df() {
  case "$*" in
    *--output=pcent*) echo "Use%"; echo " ${DF_PCT}% " ;;
    *) echo "fake df" ;;
  esac
}

source "$TESTDIR/../docker-storage-maintenance.sh"
set +e

calls_str() { cat "$CALLLOG"; }
run_prune() { : > "$CALLLOG"; prune_old_unused_images >/dev/null 2>&1; }

old="$(ago_days 30)"; recent="$(ago_days 2)"

echo "[prune_old_unused_images] 롤백·최신 태그는 오래돼도 보호한다"
new_env
add_image sha256:aaa "app:latest" "$old"
add_image sha256:bbb "app:prev-20260101-000001" "$old"
add_image sha256:ccc "app:pending-prev" "$old"
run_prune; c="$(calls_str)"
assert_not_contains "latest 는 지우지 않는다" "rmi app:latest" "$c"
assert_not_contains "prev- 태그는 지우지 않는다" "rmi app:prev-20260101-000001" "$c"
assert_not_contains "pending-prev 는 지우지 않는다" "rmi app:pending-prev" "$c"

echo "[prune_old_unused_images] 그 밖의 오래된 미사용 이미지는 지운다"
new_env
add_image sha256:ddd "postgres:15" "$old"
add_image sha256:eee "old/thing:1.0" "$old"
run_prune; c="$(calls_str)"
assert_contains "오래된 미사용 이미지를 지운다" "rmi postgres:15" "$c"
assert_contains "다른 오래된 이미지도 지운다" "rmi old/thing:1.0" "$c"

echo "[prune_old_unused_images] 최근 이미지는 지우지 않는다"
new_env
add_image sha256:fff "postgres:16" "$recent"
run_prune; c="$(calls_str)"
assert_not_contains "최근(7일 이내)은 보관" "rmi postgres:16" "$c"

echo "[prune_old_unused_images] 컨테이너가 쓰는 이미지는 지우지 않는다"
new_env
add_image sha256:ggg "nginx:alpine" "$old"
USED_IDS=(sha256:ggg)
run_prune; c="$(calls_str)"
assert_not_contains "사용 중이면 보관" "rmi nginx:alpine" "$c"

echo "[prune_old_unused_images] 한 이미지에 보호 태그와 일반 태그가 함께 있으면 일반 태그만 뗀다"
new_env
add_image sha256:hhh "app:prev-20260101-000001" "$old"
add_image sha256:hhh "app:1.2.3" "$old"
run_prune; c="$(calls_str)"
assert_contains "일반 태그는 뗀다" "rmi app:1.2.3" "$c"
assert_not_contains "보호 태그는 유지" "rmi app:prev-20260101-000001" "$c"

echo "[prune_old_unused_images] 태그 없는 이미지(<none>)는 이름으로 지우지 않고 dangling prune 에 맡긴다"
new_env
add_image sha256:iii "<none>:<none>" "$old"
run_prune; c="$(calls_str)"
assert_not_contains "<none> 을 rmi 하지 않는다" "rmi <none>" "$c"
assert_contains "dangling 이미지는 prune 으로 정리한다" "image prune -f --filter until=168h" "$c"
assert_not_contains "-a 옵션(태그 달린 이미지까지 삭제)을 쓰지 않는다" "image prune -a" "$c"

echo "[prune_old_unused_images] 기준 시간은 AGE_HOURS"
new_env; AGE_HOURS=24
add_image sha256:jjj "x/y:1" "$(ago_days 2)"
run_prune; c="$(calls_str)"
assert_contains "24시간 기준이면 2일 전 이미지는 지운다" "rmi x/y:1" "$c"
new_env; AGE_HOURS=720
add_image sha256:kkk "x/y:2" "$(ago_days 20)"
run_prune; c="$(calls_str)"
assert_not_contains "720시간 기준이면 20일 전 이미지는 보관" "rmi x/y:2" "$c"

echo "[main] 정비 순서와 안전 장치"
new_env; add_image sha256:lll "junk:1" "$old"
( set -euo pipefail; main ) >"$TMPD/out" 2>&1; rc=$?; c="$(calls_str)"
assert_eq "정상 종료" 0 "$rc"
assert_contains "이미지 정리" "rmi junk:1" "$c"
assert_contains "빌드 캐시 상한 정리" "builder prune -a -f --max-used-space 5GB --reserved-space 2GB" "$c"
assert_not_contains "볼륨은 지우지 않는다" "volume" "$c"
assert_not_contains "컨테이너는 지우지 않는다" "container rm" "$c"
new_env; DF_PCT=90
( set -euo pipefail; main ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "디스크 85% 이상이면 조사 필요로 실패" 1 "$rc"
assert_contains "자동 삭제 없음을 알린다" "no automatic deletion" "$(cat "$TMPD/out")"

echo "[main] 배포와 잠금을 공유한다"
new_env
exec 8>"$LOCK_FILE"; flock -n 8
( set -euo pipefail; main ) >"$TMPD/out" 2>&1; rc=$?
assert_eq "다른 쪽이 잠금을 쥐고 있으면 조용히 건너뛴다" 0 "$rc"
assert_eq "아무것도 하지 않는다" "" "$(calls_str)"
flock -u 8; exec 8>&-

echo
echo "결과: 통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ]
