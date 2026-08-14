#!/bin/bash
# sync-env.sh — 로컬 .env의 지정한 키만 서버 .env로 옮긴다.
#
# 왜 통째로 복사하지 않는가: 로컬 .env에는 개발용 DB 접속정보(DB_HOST=localhost 등)가 들어 있어
# 그대로 덮어쓰면 운영 API가 로컬 DB를 보려다 부팅에 실패한다. 그래서 아래 SYNC_KEYS에
# 명시한 키만 옮긴다. 새 키를 동기화 대상에 넣고 싶으면 이 목록에 추가한다.
#
# 사용:
#   ./sync-env.sh            # 값이 채워진 대상 키만 서버에 반영 + api 컨테이너 재생성
#   ./sync-env.sh --dry-run  # 무엇이 바뀌는지만 보고 반영하지 않음
#   ./sync-env.sh --no-restart   # .env만 갱신하고 컨테이너는 그대로 둠
#
# 값은 절대 화면에 출력하지 않는다(키 이름과 설정 여부만 표시).

set -euo pipefail

# 동기화 대상 — 소싱 파이프라인 외부 API 키. 운영/로컬에서 같은 값을 쓰는 것들만 넣는다.
SYNC_KEYS=(
  NAVER_OPENAPI_CLIENT_ID
  NAVER_OPENAPI_CLIENT_SECRET
  NAVER_SEARCHAD_API_KEY
  NAVER_SEARCHAD_SECRET_KEY
  NAVER_SEARCHAD_CUSTOMER_ID
  ZEN_API_KEY
)

SERVER="168.107.31.154"
USER="ubuntu"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY="${SBSHOP_SSH_KEY:-$LOCAL_DIR/ssh-key-2026-06-25.key}"
REMOTE_DIR="~/projects/sbshop-agent"
LOCAL_ENV="$LOCAL_DIR/.env"

DRY_RUN=false
RESTART=true
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --no-restart) RESTART=false ;;
    *) echo "알 수 없는 옵션: $arg"; exit 1 ;;
  esac
done

[ -f "$LOCAL_ENV" ] || { echo "❌ 로컬 .env가 없습니다: $LOCAL_ENV"; exit 1; }

# 로컬에서 값이 채워진 키만 수집한다. 빈 값을 서버로 보내면 이미 넣어둔 운영 키를 지워버린다.
TO_SYNC=()
SKIPPED=()
for k in "${SYNC_KEYS[@]}"; do
  line=$(grep -E "^${k}=" "$LOCAL_ENV" | tail -1 || true)
  value="${line#*=}"
  if [ -n "$line" ] && [ -n "$value" ]; then
    TO_SYNC+=("$k")
  else
    SKIPPED+=("$k")
  fi
done

# bash 3.2(macOS 기본)에서는 set -u 상태로 빈 배열을 전개하면 unbound variable로 죽는다
# → ${arr[@]+"${arr[@]}"} 형태로 감싼다.
echo "동기화 대상 (로컬에 값이 있는 키): ${#TO_SYNC[@]}개"
for k in ${TO_SYNC[@]+"${TO_SYNC[@]}"}; do echo "  ✔ $k"; done
if [ ${#SKIPPED[@]} -gt 0 ]; then
  echo "건너뜀 (로컬이 비어 있음 — 서버 값을 지우지 않기 위해):"
  for k in ${SKIPPED[@]+"${SKIPPED[@]}"}; do echo "  · $k"; done
fi

if [ ${#TO_SYNC[@]} -eq 0 ]; then
  echo "옮길 값이 없습니다. 로컬 .env에 키를 먼저 입력하세요."
  exit 0
fi

if $DRY_RUN; then
  echo "(--dry-run: 실제 반영하지 않고 종료)"
  exit 0
fi

# 값을 base64로 감싸 원격 스크립트 안에 심는다.
#
# ⚠️ 여기서 한 번 틀렸다: 처음엔 `printf '%s' "$PAYLOAD" | ssh ... bash -s <<'REMOTE'` 로
#    데이터를 파이프로 넘겼는데, `bash -s`는 스크립트 자체를 stdin으로 읽는다. 즉 스크립트와
#    데이터가 같은 stdin을 다투고, 원격의 `while read`가 남은 **스크립트 본문을 데이터로**
#    읽어 .env에 쓰레기 줄을 append했다. 데이터는 스크립트 안에 담고, 루프의 stdin은
#    별도 리다이렉션으로 준다.
# base64는 개행·따옴표·$ 같은 특수문자가 없어 heredoc 안에 그대로 넣어도 안전하다.
PAYLOAD=""
for k in ${TO_SYNC[@]+"${TO_SYNC[@]}"}; do
  PAYLOAD+="$(grep -E "^${k}=" "$LOCAL_ENV" | tail -1)"$'\n'
done
PAYLOAD_B64=$(printf '%s' "$PAYLOAD" | base64 | tr -d '\n')

echo
echo "서버에 반영 중..."
ssh -i "$KEY" -o StrictHostKeyChecking=no "$USER@$SERVER" "cd $REMOTE_DIR && bash -s" <<REMOTE_SCRIPT
set -euo pipefail
cd ~/projects/sbshop-agent
cp .env ".env.bak-sync-\$(date +%Y%m%d-%H%M%S)"

DATA=\$(printf '%s' '$PAYLOAD_B64' | base64 -d)
updated=0

# 루프의 stdin을 데이터로 명시 지정한다(스크립트 stdin과 분리).
while IFS= read -r line; do
  [ -z "\$line" ] && continue
  key="\${line%%=*}"
  python3 - "\$key" "\$line" <<'PYEOF'
import io, sys
key, newline = sys.argv[1], sys.argv[2]
with io.open('.env', encoding='utf-8') as f:
    lines = f.readlines()
out, replaced = [], False
for l in lines:
    if l.startswith(key + '=') and not replaced:
        out.append(newline + '\n'); replaced = True
    else:
        out.append(l)
if not replaced:
    if out and not out[-1].endswith('\n'):
        out[-1] += '\n'
    out.append(newline + '\n')
with io.open('.env', 'w', encoding='utf-8') as f:
    f.writelines(out)
print('  OK ' + key + ('' if replaced else ' (신규 추가)'))
PYEOF
  updated=\$((updated+1))
done <<< "\$DATA"

echo "서버 .env 갱신 완료: \${updated}건 (백업 .env.bak-sync-*)"
REMOTE_SCRIPT

if $RESTART; then
  echo
  echo "API 컨테이너 재생성 중 (restart로는 환경변수가 반영되지 않는다)..."
  ssh -i "$KEY" -o StrictHostKeyChecking=no "$USER@$SERVER" \
    "cd $REMOTE_DIR && docker compose up -d sbshop-api"

  echo "기동 확인 중..."
  for i in $(seq 1 24); do
    sleep 5
    if ssh -i "$KEY" -o StrictHostKeyChecking=no "$USER@$SERVER" \
        "docker logs --since 3m projects-sbshop-api-1 2>&1 | grep -q 'Started ApiApplication'"; then
      echo "✅ API 재기동 완료"
      break
    fi
    echo "   대기 중... (${i}/24)"
  done

  echo
  echo "적용된 키 확인 (이름만):"
  ssh -i "$KEY" -o StrictHostKeyChecking=no "$USER@$SERVER" \
    "docker exec projects-sbshop-api-1 printenv | grep -E '^(NAVER|ZEN)' | cut -d= -f1 | sort"
else
  echo
  echo "⚠️ 컨테이너를 재생성하지 않았습니다. 반영하려면:"
  echo "   ssh -i $KEY $USER@$SERVER 'cd $REMOTE_DIR && docker compose up -d sbshop-api'"
fi
