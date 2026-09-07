# G마켓·옥션 공개가격 조회 큐

사용자가 상품 관리에서 단건 또는 최대 50개 상품의 G마켓·옥션 공개가격을 요청한다. 요청 당시 상품 버전·카페24 연결·하위 마켓 상품번호·판매 계정·쇼핑몰을 고정한다. 공개 표시가격과, 옥션의 무옵션 남은수량이 확인된 경우에만 해당 관측을 SB에 저장한다. 기존 MarketPlus 전송 성공 이력이나 카페24 본상품 값을 G/A 최종값으로 대신 사용하지 않는다.

## 처리와 API

- `POST /api/v1/products/marketplus-public-checks/collections`: `{requestId: UUID, productIds: 1..50개 JSON 정수, markets: [GMARKET, AUCTION] 부분집합}`. 같은 요청자·UUID는 동일 요청을 복구하며 다른 목록으로 재사용할 수 없다.
- 같은 base의 `GET /collections`는 본인 최신 20건, `GET /collections/{id}`는 본인의 상품별 상태를 반환한다.
- `POST /worker/claim`은 한 작업과 120초 임대 토큰을 반환한다. `POST /worker/{id}/report`는 동일 임대·인증 작업자의 관측 또는 실패를 접수한다.
- `QUEUED → RUNNING → OBSERVED / RETRY_WAIT / FAILED_UNVERIFIED / STALE`. 현재 연결이 없거나 모호한 요청은 `SKIPPED`와 사유를 남긴다.
- OBSERVED는 공개 표시값 확인이며 동기화 완료·목표값 일치·삭제 또는 정상 판매 상태 판정이 아니다. 원인 불명은 최대 3회 일반 지연 후 `FAILED_UNVERIFIED`로 끝난다. 재조회는 현재 연결로 새 요청을 생성한다.
- 작업은 `sb_marketplus_public_collection`, `sb_marketplus_public_check`에 저장한다. 실제 관측과 OBSERVED 전환은 한 DB 트랜잭션이며 기존 fingerprint 중복 방지를 재사용한다. 상품/연결은 수정하지 않는다.

## 오류·동시성

공유 browser gate와 마켓별 read gate를 DB에 저장한다. 한 마켓의 429 대기 작업이 100개 이상이어도 다른 마켓의 조회가 막히지 않게, 조회 가능한 마켓 조건을 DB due query에 먼저 적용한다.

실제 HTTP 429만 마켓 공통 대기를 연장한다. `httpStatus`와 `retryAfterSeconds`는 JSON 정수만 허용한다. Chrome Navigation Timing의 `responseStatus`가 100~599인 경우에만 실제 상태로 보고하고, 0/미지원/누락은 null이다. DOM에서 검증한 Retry-After 헤더는 없으므로 워커는 그 값을 추정하지 않는다. img_error, 빈 페이지, 403, 상품·판매 계정 불명은 삭제가 아니다.

최대 3회 발급된 임대 토큰·작업자를 task 내부 JSON에 보존한다. 재임대 후 이전 정당한 토큰으로 도착한 429는 마켓 gate만 연장하며 현재 task/global lease를 변경하지 않는다. 임의 토큰이나 다른 작업자는 gate도 바꿀 수 없다. 워커는 이 늦은 응답의 접수 결과가 RUNNING이어도 접수 완료로 받아 같은 429를 반복 제출하지 않는다.

워커는 SB의 정확한 claim/report 두 종류 경로만 허용하고 외부마켓 쓰기 경로를 제공하지 않는다. 관측값·토큰을 로컬 pending receipt에 먼저 저장한 뒤 report하며, 응답 유실 뒤에는 같은 receipt로 재시도한다. 이전 임대가 거절되면 로컬 거절 기록을 보존하고 다음 작업을 막지 않는다.

## 브라우저 운영

기존 observer 단일 루프에서 공개 조회 한 건을 먼저 처리한 후 전송이력을 수집하고, 유휴 상태에서는 5초마다 공개 요청을 확인한다. 공개 요청은 사용자의 명시적인 조회이므로 legacy `collect`/`upload` flags와 독립이다. 이력 로그인 실패에 의존하지 않는다.

공개 조회는 자신의 새 탭만 사용하며 finally에서 그 탭의 존재를 확인해 닫고 원래 탭으로 돌아간다. 사용자 탭은 닫지 않는다. `/state/browser-lease.lock`의 flock으로 이력 수집·공개 조회·운영자의 읽기 helper가 브라우저를 공유한다. 수동 helper는 collect=false만으로 독점 접근을 가정해서는 안 된다.

## 검증 범위

- H2 영속 통합 14건과 기존 관측 서비스 7건 통과. 요청 중복/소유자, 부분 제외, 관측+상태 원자 저장, 응답 유실 뒤 동일 관측 복구, 버전·연결·쇼핑몰 변경, 세 번 제한, 429 지연, 101건 대기 후 다른 마켓 진행, 재임대와 늦은429/임의토큰을 검증했다. 마지막 HTTP 정수 형식 assertion은 후속 PostgreSQL/전체 검사에 포함한다.
- Python 전체 54건 통과. 자체 탭 복구, HTTP 상태 미확인, report receipt 재전송, 늦은429 접수 후 중단을 포함한다.
- `npx tsc -b`, `npm run build` 통과. 로컬 Chrome fixture 6흐름 통과.
- [로컬 fixture 결과](evidence/2026-09-08-marketplus-public-refresh-fixture.json), [로컬 화면](evidence/2026-09-08-marketplus-public-refresh-fixture.png). 이 화면의 가격·재고·상태는 가상 데이터이며 운영 조회 성공의 근거로 사용할 수 없다.

DDL: `backend/docs/ddl/2026-09-08-marketplus-public-check-queue.sql`. 운영 적용은 기존 public-observation DDL 뒤에 진행한다. 이번 단위는 마켓플러스 선택 전송/재전송을 실행하지 않는다. 공개 페이지가 오류를 반환하면 실제 운영에서도 확인 불가가 예상된다. 검증된 이미지·HTML 최종 영역과 G마켓 정상 seller DOM의 추가 근거가 필요한 범위는 기존 계약에 남아 있다.
