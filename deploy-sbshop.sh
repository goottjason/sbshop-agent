#!/bin/bash
# deploy-sbshop.sh - 서버의 안전 배포 스크립트(/home/ubuntu/webhook/deploy-sbshop.sh)를 원격 실행한다.
#
# 참고: main 브랜치에 push하면 GitHub 웹훅(:9000)이 동일한 서버 스크립트로 자동 배포한다.
#       따라서 평소에는 `git push`만으로 배포되며, 이 스크립트는 수동/긴급 배포용이다.
#
# 배포는 서버에서 수행된다(git fetch+reset → 이미지 빌드 → 기존 컨테이너 제거 후 재생성 → nginx reload).
# 컨테이너 이름 충돌(container_name 고정으로 인한 recreate 잔여)을 "제거 후 생성"으로 원천 차단한다.

set -e

SERVER="168.107.31.154"
USER="ubuntu"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY="${SBSHOP_SSH_KEY:-$LOCAL_DIR/ssh-key-2026-06-25.key}"

echo "1. 서버 안전 배포 실행 중(빌드 + 무충돌 재생성 + nginx reload)... 서버 로그: /home/ubuntu/webhook/deploy-sbshop.log"
ssh -i "$KEY" -o StrictHostKeyChecking=no "$USER@$SERVER" "/home/ubuntu/webhook/deploy-sbshop.sh"

echo "2. 헬스체크 중(Spring Boot 부팅 대기, 최대 ~120초)..."
STATUS=000
for i in $(seq 1 24); do
    sleep 5
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" -L -k --connect-timeout 5 "https://$SERVER/sbshop-agent/")
    if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then break; fi
    echo "   대기 중... (HTTP $STATUS, ${i}/24)"
done
if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then
    echo "✅ 배포 성공! (HTTP $STATUS)  접속: https://$SERVER/sbshop-agent"
else
    echo "❌ 배포 실패 (HTTP $STATUS)"
    echo "   로그: ssh -i $KEY $USER@$SERVER 'tail -40 /home/ubuntu/webhook/deploy-sbshop.log'"
    exit 1
fi
