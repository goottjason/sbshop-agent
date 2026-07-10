#!/bin/bash
# deploy-sbshop.sh - 로컬에서 실행하여 서버에 배포

set -e

SERVER="168.107.31.154"
USER="ubuntu"
KEY="${SBSHOP_SSH_KEY:-/Users/jason/.ssh/sbshop-key}"
REMOTE_DIR="~/projects"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "1. sbshop-agent 백엔드 빌드 중..."
cd "$LOCAL_DIR/backend"
./gradlew :api:bootJar :worker:bootJar --no-daemon -x test

echo "2. 프론트엔드 빌드 중..."
cd "$LOCAL_DIR/frontend"
npm run build

echo "3. 서버에 배포 중..."
# 컨테이너 재생성 후 nginx가 옛 IP를 캐시하므로 reload 필수(원장 기록된 함정)
ssh -i "$KEY" "$USER@$SERVER" "cd $REMOTE_DIR/sbshop-agent && git pull origin main && cd $REMOTE_DIR && docker compose up -d --build sbshop-api sbshop-frontend && docker exec projects-nginx-1 nginx -s reload"

echo "4. 헬스체크 중(Spring Boot 부팅 대기, 최대 ~90초)..."
STATUS=000
for i in $(seq 1 18); do
    sleep 5
    # -L: http→https 301 리다이렉트 추적, -k: 자체서명 인증서 허용, 슬래시 포함 경로
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" -L -k --connect-timeout 5 "https://$SERVER/sbshop-agent/")
    if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then break; fi
    echo "   대기 중... (HTTP $STATUS, ${i}/18)"
done
if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then
    echo "✅ 배포 성공! (HTTP $STATUS)"
    echo "   접속: https://$SERVER/sbshop-agent"
else
    echo "❌ 배포 실패 (HTTP $STATUS)"
    echo "   로그: ssh -i $KEY $USER@$SERVER 'docker logs projects-sbshop-api-1 --tail 30'"
    exit 1
fi
