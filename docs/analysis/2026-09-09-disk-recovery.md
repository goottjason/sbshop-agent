# 2026-09-09 디스크 장애 복구

## 원인과 보존

- 루트 파일시스템 102,888,095,744 bytes, 여유 공간 0. PostgreSQL은 쓰기 실패로 중단됐다.
- API Docker JSON 로그 약 28.1 GiB. Hibernate SQL 출력과 무제한 json-file 로그가 결합됐다.
- containerd 저장소 약 57.3 GiB. Docker 이미지/빌드캐시 논리적 용량은 공유 레이어 때문에 단순 합산하면 안 된다.
- 사용자 승인 범위: 미사용 빌드캐시·이미지 정리, 복구, 재발 방지. SB API 로그 보존.
- `docker builder prune -a -f`, `docker image prune -a -f` 실행 후 약 43 GiB 확보, 사용률 56%.
- API를 정지하고 원본 로그를 같은 파일시스템에 하드링크하여 보존한 뒤 컨테이너를 재생성했다. DB 볼륨·백업·브라우저 프로필은 삭제하지 않았다.
- 원본: `/home/ubuntu/backups/sbshop-disk-recovery-20260909/sbshop-api-original-json.log` (30,217,999,922 bytes). 디렉터리 권한 0700.

## 복구 검증

- PostgreSQL 정상 WAL 복구 후 ready. WAL 강제 초기화나 DB 파일 삭제 없음.
- 인증된 GET `/orders/sync/status`, `/supplier-batches/options`, 기존 배치 상세 조회 모두 HTTP 200.
- 기존 배치 `99c7a7a1-3d25-4565-a790-d65fa8cc3b54`: 2026-09-09 05:27 KST 완료, 2,114/2,114.
- 상품 기준 성공 60, DB만 저장 3, 실패 444, 보류 1,607. 완료는 전체 성공을 뜻하지 않는다. 이번 복구에서 실패/보류를 임의로 재시도하지 않았다.

## 재발 방지

- SQL stdout/DEBUG 비활성화, Spring Web 로그 INFO.
- SB 컨테이너 json-file 로그: 파일 50 MB, 최대 5개. 보존한 과거 원본 로그는 이 회전 대상이 아니다.
- `ops/docker-storage-maintenance.sh`: 미사용 이미지 중 7일 이상 지난 것 정리, 미사용 빌드캐시 5 GB 목표/2 GB 예약. 사용 중인 레이어는 정리되지 않아 총 Docker 용량의 강제 상한은 아니다.
- systemd timer: 매일 한국시간 05:00~05:15. DB 볼륨·애플리케이션 로그·백업은 정리 대상에서 제외.
- 배포와 정리 작업은 동일 flock으로 중복 방지. 빌드 시작 전 10 GiB 이상 여유 공간 확인.
- 정리 후 디스크 85% 이상이면 systemd 작업 실패로 기록. 외부 알림 전송은 설정하지 않음.
- 마켓플러스 별도 운영 compose에도 동일 로그 제한 저장. 브라우저 세션은 유지하며 브라우저 컨테이너의 제한은 다음 재생성 시 적용된다.

## 운영 명령

```sh
systemctl list-timers sbshop-docker-maintenance.timer
sudo systemctl start sbshop-docker-maintenance.service
journalctl -u sbshop-docker-maintenance.service -n 50
```

수동 정리는 배포 종료 후 실행한다. 기존 SB API 원본 로그는 수동 검토 전까지 유지한다.

## 최종 확인 (2026-09-09 10:02 KST)

- 배포 커밋 `a7a53b39`, GitHub Actions `34296980996` 성공.
- 정리 systemd service 직접 실행: Result=success, ExecMainStatus=0. timer active.
- 최종 디스크: 96 GiB 중 사용 약 56 GiB, 여유 약 41 GiB, 59%.
- API·프론트·소싱 스크래퍼·Selenium·마켓플러스 작업자: running, json-file 50m/5 실제 적용.
- 공유 PostgreSQL은 기존 컨테이너 복구를 유지했으며 기존 로그 설정은 변경하지 않았다(로그 약 68 MiB). 마켓플러스 브라우저도 세션 유지를 위해 재생성하지 않았다.
- 최종 API 로그 8,513 bytes. 기존 원본 로그 30,217,999,922 bytes 유지 확인. 원본은 하드링크 보존으로 복사 변환 없이 유지됐으며 전체 SHA256 읽기는 디스크 I/O 부담을 줄이기 위해 중단했다.
- 배포 후 인증 API HTTP 200. 독립 Selenium 브라우저 검증 통과: 운영 배치 화면, 2,114개 상품, 4마켓, 옵션/이력 API 정상. 변경 요청 0, JavaScript 오류 0. 검증 세션 삭제 완료.
