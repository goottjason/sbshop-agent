#!/bin/bash
# deploy-sbshop.sh - 로컬에서 실행하여 서버에 배포

set -e

SERVER="168.107.31.154"
USER="ubuntu"
KEY="/Users/jasonair/.ssh/id_rsa"
REMOTE_DIR="~/projects"

echo "1. sbshop-agent 빌드 중..."
cd /Users/jasonair/Projects/sbshop-agent/backend
./gradlew :api:bootJar :worker:bootJar --no-daemon -x test

echo "2. 프론트엔드 빌드 중..."
cd /Users/jasonair/Projects/sbshop-agent/frontend
npm run build

echo "3. 서버에 배포 중..."
ssh -i "$KEY" "$USER@$SERVER" "cd $REMOTE_DIR && git pull origin main && docker compose up -d --build sbshop-api sbshop-frontend"

echo "4. 테스트 중..."
sleep 15
STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "http://$SERVER/sbshop-agent")
if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then
    echo "✅ 배포 성공! (HTTP $STATUS)"
    echo "   접속: http://$SERVER/sbshop-agent"
else
    echo "❌ 배포 실패 (HTTP $STATUS)"
    echo "   로그: ssh -i $KEY $USER@$SERVER 'docker logs projects-sbshop-api-1 --tail 30'"
    exit 1
fi
