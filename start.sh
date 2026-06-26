#!/bin/bash
# start.sh - API와 Worker를 백그라운드에서 동시 실행

echo "Starting sbshop-api on port 8080..."
java -jar /app/api.jar --server.port=8080 &

echo "Starting sbshop-worker on port 8081..."
java -jar /app/worker.jar --server.port=8081 &

# 두 프로세스가 모두 실행되도록 대기
wait -n
