# 필드별 영속 마켓 동기화 엔진

이 문서는 구현 계약이며 운영 반영 성공이나 전체 마켓 지원 완료를 뜻하지 않는다. 실제 지원 필드는 각 마켓 어댑터의 `prepareProductFields/readProductFields/writePreparedProductFields`가 검증한 계약으로 제한한다. 가격과 판매용 수량은 기존 별도 큐를 사용한다. 원재고·재고상태도 이 큐에서 수정하지 않는다.

## API와 검토

- `POST /api/v1/market-field-sync/reviews`: `{productIds:[...], markets:[...], fields:[...]}`. 1~500 상품을 대상으로 PREPARE 작업을 영속 저장한다. HTTP 요청 트랜잭션에서 마켓 조회나 이미지 호스팅을 실행하지 않는다.
- `GET /reviews/{id}`, `GET /reviews`: 작성자 본인만 조회. 목록은 최근 20개 검토이며 응답 유실 시 여기서 복구할 수 있다.
- 준비가 끝난 DRAFT의 `expectedValues`는 어댑터가 실제 전송할 정규화 값이다. `POST /reviews/{id}/commit {acceptApproval:boolean}`으로 반영을 시작한다. 심사 요청이 필요한 필드가 있으면 `true` 동의가 필수다. 같은 검토 ID의 commit은 중복 작업을 만들지 않는다.
- `GET /tasks/{id}/history`: 본인 작업의 준비·관측·전송 의도·결과 이력을 조회한다. 전송 payload는 외부 응답에 노출하지 않는다.
- 검토는 준비 완료 후 30분 유효하다. 만료·revision 변경·실패 작업을 재시도하려면 현재 값으로 새 검토를 만든다. PREPARE/DRAFT도 상품·마켓 단일 활성 슬롯을 점유한다.

## 저장된 DB 변경과 상태

`ProductChangeTarget.fieldTaskId`가 이 큐를 연결한다. 가격/수량과 분리된 필드 target만 접수한다. 가격·수량이 섞인 snapshot의 일부만 성공으로 처리하지 않는다. 원래 저장 이력의 actor, 현재 상품 revision, 전체 마켓 연결 fingerprint를 다시 검증한다.

자동 접수도 먼저 PREPARE한다. 준비 결과 `requiresApproval=false`이면 CHECK로 진행하고, `true`이면 target을 AWAITING_REVIEW로 표시하여 원래 작성자의 검토를 기다린다. DB 변경 이력과 큐 연결은 원자적으로 저장하며 실패 시 target은 PENDING_DISPATCH로 남는다.

- PREPARE → DRAFT → CHECK → VERIFY → CONFIRMED_FIELDS
- 심사 중: AWAITING_APPROVAL, 15분 뒤 재조회하며 재전송하지 않는다.
- 심사 반려: REJECTED. 불명확한 심사 상태는 성공·쓰기를 허용하지 않는다.
- BLOCKED / STALE / EXPIRED / UNKNOWN / FAILED_MISMATCH는 조치가 필요한 결과다.
- CONFIRMED_FIELDS는 요청된 필드 전체의 별도 GET 일치와 필요한 심사 완료만 뜻한다. 가격·재고나 Cafe24→마켓플러스→G마켓/옥션 전체 성공으로 복제하지 않는다.

## 전송과 동시성 보장

준비 payload는 고정 delta이며, 어댑터는 실제 PUT 직전 최신 GET에 delta를 병합한다. 계정·상품번호·SB코드·단일 품목 증거는 어댑터가 검증한다. 엔진은 준비/관측의 계정 및 resolvedOptionId, 요청 필드 key 집합의 정확한 일치와 canonical 값 전체를 검증한다. 네트워크 도중 상품 revision, 연결 revision/identifiers, 계정이 변경되면 기존 작업을 중지한다.

공유 `{MARKET}_ORIGIN_READ` gate와 180초 lease를 사용한다. 잠금 순서는 gate → review → 상품 → 등록이다. 사용자 commit을 기다린 작업자는 task를 다시 읽어 DRAFT가 CHECK로 바뀐 내용을 덮어쓰지 않는다. 늦게 도착한 429는 새 작업자의 lease를 탈취하지 않고 최대 Retry-After를 보존한다. 새 작업자는 PUT 직전 cooldown을 재검사한다.

매 실제 PUT 직전 `beforeWrite`가 DB에 전송 의도를 먼저 저장한다. 응답은 접수/불명 증거이며 성공이 아니다. 결과가 불명확하거나 lease가 만료되면 별도 조회부터 재개한다. 명시적 HTTP 4xx(429 제외) 전송 거절 후에는 재조회하여 실제 반영 여부를 확인하되 불일치에 자동 재전송하지 않는다. 실제 PUT은 작업당 최대 3회, 연속 조회/준비 실패는 9회 후 UNKNOWN이다. 다중 요청 어댑터는 각 PUT마다 callback을 호출하여 3회 상한을 공유한다.

## DDL과 검증

`backend/docs/ddl/2026-09-07-market-field-sync.sql`은 review/task/attempt 테이블, 활성 상태의 상품·마켓 partial unique index, `sb_product_change_target.field_task_id`와 조회 인덱스를 추가한다. edit/inspection DDL 다음 적용하며 재실행해도 검토·동의·불명 전송·lease·이력이 보존된다.

검증은 실제 마켓 쓰기 없이 mock transport와 H2/PostgreSQL 16 격리 DB로 진행한다. 회귀에는 작성자·동의·재조회 확정·심사 대기/반려·정확한 필드/품목·판매금지·revision 충돌·응답 유실·명시 거절·429·재시도 상한·DB 잠금 경쟁·target/queue rollback·DRAFT commit 경쟁을 포함한다. `backend/tools/verify-market-field-postgres.py`는 로컬 임시 DB에만 연결하도록 URL을 제한하며 종료 시 컨테이너를 삭제한다.
