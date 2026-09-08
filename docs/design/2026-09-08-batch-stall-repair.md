# 대량 배치 정체 복구

사용자 승인 범위: SB 저장/내구성 있는 변경 작업 연결, 전송 검토 만료 반복 제거, 마켓별 독립 실행, 성공/대상없음 구분, 기존 배치 미완료 단계 복구.

## 구현

- 기존 ProductEditService.persist의 상품 변경·이력·BATCH_MANAGED 변경 대상을 동일 transaction으로 보관하는 구조를 유지한다. 신규 배치를 만들거나 기존 수집/SB저장을 반복하지 않는다.
- 마켓 preview/get/commit의 로컬 DB 동작은 상위 transaction에 참여한다. 네트워크 worker의 claim/beginWrite/finish는 기존 REQUIRES_NEW를 유지한다. Runner의 한 transaction에서 검토 생성/기존 만료자료 갱신, child task 생성, stage.referenceId/taskId 연결을 함께 저장한다. 네트워크 요청은 이 transaction에서 실행하지 않는다.
- 기존 committed child는 검토기간과 무관하게 재사용한다. 오래된 uncommitted draft는 다음 선택 시 새 검토와 접수를 같은 transaction에서 완료한다. 실패 시 새 child와 pointer가 함께 rollback되고, commit 후 finish 응답 유실이면 이미 보관한 pointer로 복구한다.
- scheduler tick당 최대32개 로컬 단계를 처리하며 신규 수집은 RUNNING 상품128개 미만일 때 시작한다. 기존 대량 대기열은 제한과 무관하게 계속 처리한다.
- 네 마켓별 고정지연 실행을4개 스레드의 별도 scheduler에 배치한다. 각 마켓은 가격/재고 우선순위를 번갈아 적용하며 기존 계정별 gate, lease,429 Retry-After, 상품/계정/연결/리비전 확인을 그대로 사용한다. 기존 공용 스케줄러의 중복 가격/재고 polling은 해제한다. 수집/기존 조회 작업 스케줄러는 유지한다.
- 과거 성공 이력을 DB에서 덮어쓰지 않고 API 집계·행 표시·필터에서 마켓 전부SKIPPED인 성공 건을 dbOnly로 구분한다. 단계별 전체 집계를 추가한다.

## 검증 및 배포

관련 회귀 테스트,2114개 저장상품 일괄 접수 테스트,30분을 넘긴 기존 draft와 이미 commit된 작업의 복구, 오류 rollback/재시도, 마켓간 독립 실행,가격/재고429 및 lease 회귀, DB_ONLY 필터 검증. PostgreSQL 테스트 증거는 evidence/2026-09-08-batch-stall-repair 참조.

배포 전 운영 DB 스냅샷을 서버 전용 백업 폴더에 보관한다. 기존 RUNNING 배치는 배포 후 자동으로 같은 ID의 미완료 단계를 처리한다. 실패·보류를 임의 성공 처리하거나 수집부터 일괄 재실행하지 않는다. 배포 후 실제 전송·재조회 결과와 처리 수 변화를 확인한다.

## 운영 관측 후 완료 표시 보완

최초 배포 후 실제 전송이 재개됐지만 오래된 접수 대기열보다 늦은 시각의 재조회/완료 집계가 뒤로 밀리는 것을 확인했다. 가격·재고 큐는 호출 가능 시각이 된 VERIFY를 CHECK보다 먼저 처리하며, 배치 runner는 완료된 child task의 결과를 신규 접수 대기보다 먼저 반영한다.429 대기시각과 pause 조건은 유지한다. PostgreSQL에서 오래된 미전송 작업보다 이미 전송한 작업의 재조회·결과 표시가 먼저 수행되는지 검증한다.
