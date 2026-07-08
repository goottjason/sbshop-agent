#!/bin/bash
# start.sh - API와 Worker를 백그라운드에서 동시 실행

# ESM+(G마켓/옥션) Selenium: 컨테이너에 설치된 chromedriver를 고정 사용(런타임 다운로드 방지)
CHROMEDRIVER_OPT=""
if [ -x /usr/local/bin/chromedriver ]; then
  CHROMEDRIVER_OPT="-Dwebdriver.chrome.driver=/usr/local/bin/chromedriver"
fi

echo "Starting sbshop-api on port 8080..."
java $CHROMEDRIVER_OPT -jar /app/api.jar --server.port=8080 &

echo "Starting sbshop-worker on port 8081..."
java $CHROMEDRIVER_OPT -jar /app/worker.jar --server.port=8081 &

# 두 프로세스가 모두 실행되도록 대기
wait -n
