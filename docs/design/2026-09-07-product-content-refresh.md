# 상품 소싱 콘텐츠 수집·비교·선택 반영

2026-09-07 구현·운영 배포 단위. 실제 IHB 상품 수집과 이미지 호스팅, 운영 Chrome 비교 화면까지 확인했다. 실제 DB 콘텐츠 저장·외부마켓 콘텐츠 쓰기 완료와 구분한다.

## 제공 범위

- 상품 관리에서 단건 또는 선택한 최대 50건의 콘텐츠 수집 요청을 영속 큐에 저장한다. `requestId`는 요청자별 멱등 키이며 같은 키에 다른 상품을 넣으면 충돌한다.
- 현재 확인된 `IherbScraperClient`의 IHB 상품정보 계약을 사용한다. 다른 소싱처는 `UNSUPPORTED`와 사유를 반환한다. 기존 상품등록의 5장 상한은 유지하고 콘텐츠 갱신 전용 조회에서는 전체 이미지 인덱스를 읽는다. 8장을 넘거나 중복·부분 다운로드·호스팅 실패가 있으면 이미지 전체를 적용 후보에서 제외한다.
- DB에 있던 이미지·상세정보, 상품명·원문명·묶음수량·용량·단위를 수집 요청 당시 값으로 고정한다. 새 HTML은 기존 `Product` 상세 템플릿으로 생성한다. 임의의 수동 문구 보존 규칙이나 상품명 변경 규칙을 새로 만들지 않는다.
- 원본 페이지 전체를 저장하지 않는다. 소싱처 상품 설명의 본문과 표만 허용 목록으로 정제한 뒤 자동 생성 HTML에 포함한다. 소싱 설명의 실행 코드·외부 이미지·링크·스타일은 제거한다.
- 이미지와 상세 HTML의 수집 성공 시각과 DB 적용 시각을 각각 저장한다. 빈 응답·부분 실패 항목·수집 중단은 해당 항목의 성공 시각이나 적용 시각을 올리지 않는다.
- 검토 시 선택한 이미지(`sourceImages` + `hostedImages`) 또는 상세 HTML만 고정한다. `ProductEditPlanner`/`ProductEditPolicy`를 재사용한다. 연결 상품의 콘텐츠 편집은 현행 `VERIFICATION_REQUIRED` 잠금을 유지하며 수집·비교만 가능하다.
- 상품 revision, 연결 fingerprint, 정책, 검토 만료를 저장 직전에 재검사한다. DB 필드 수정·기존 변경 이력·미반영 대상·스냅샷 적용 시각을 같은 상품별 transaction으로 저장한다. 다건 중 한 건 실패가 다른 상품의 성공을 지우지 않는다. 같은 검토 ID 재시도는 중복 적용하지 않는다.

## API 계약

기본 경로: `/api/v1/products/content`. 요청자 ID는 HTTP 인증 Principal에서만 가져온다. 조회·검토·저장은 해당 요청자에게 귀속된다.

| 동작 | 경로 / 본문 | 결과 |
|---|---|---|
| 수집 요청 | `POST /collections`, `{requestId, productIds:[...]}` | `{id,createdAt,items:[Snapshot...]}` |
| 수집 진행 조회 | `GET /collections/{id}` | 같은 Collection 구조 |
| 최근 이력 | `GET /{productId}/history` | 요청자 소유 최근 20개 Snapshot |
| 선택 항목 검토 | `POST /reviews`, `{items:[{snapshotId,fields:["IMAGES","DETAIL_HTML"]}]}` | 기존 편집 UI와 같은 `{reviewId,expiresAt,items:[Plan...]}` |
| 검토 저장 | `POST /commit`, `{reviewId}` | 기존 `{reviewId,items:[{productId,sbCode,state,historyId,reason}]}` |

Snapshot에는 `id`, `productId`, `sbCode`, `revision`, `sourceUrl`, `vendor`, `state`, `reason`, `requestedAt`, `collectedAt`, `expiresAt`, `appliedAt`, `current`, `proposed`, `fields`, `notices`가 있다. current/proposed는 `sourceImages`, `hostedImages`, `detailHtml`을 가진다. fields에는 항목별 `available`, `editable`, `reason`, `collectedAt`, `appliedAt`이 있다.

수집 상태는 `QUEUED`, `COLLECTING`, `READY`, `PARTIAL`, `FAILED`, `UNSUPPORTED`이다. `READY`는 수집 결과가 준비됐다는 의미이며 DB 저장·마켓 반영 성공이 아니다. 콘텐츠 검토는 별도 저장소를 사용하므로 일반 `/products/changes/commit` 대신 이 문서의 `/content/commit`으로 저장해야 한다.

## 작업 복구와 제한

- 외부 조회·이미지 다운로드·이미지 호스팅 중 DB transaction을 유지하지 않는다.
- DB의 단일 IHB lane으로 프로세스가 여러 개여도 한 번에 한 상품을 수집한다. 정상 작업 간 최소 5초 간격, 최대 10분 lease를 사용한다. 429는 최소 5분 및 서버 Retry-After 중 더 늦은 시각까지 다음 상품을 보류한다.
- 애플리케이션 재시작으로 중단된 `COLLECTING`은 lease 만료 뒤 명시적 `FAILED`로 남긴다. 늦게 도착한 결과는 claim token 및 lease를 다시 확인하여 성공으로 기록하지 않는다. 대기 중 `QUEUED`는 지속 처리한다. 실패 항목은 사용자가 새 수집을 요청한다.
- 요청당 최대 50개, 전체 대기·수집 중 최대 300개이다. 요청에 고정하는 기존 콘텐츠 합계는 UTF-8 5MB를 넘으면 전체 요청을 거절한다. 새 소싱 설명은 20만 자, 생성 HTML은 30만 자, 검토 전체 payload는 500만 자 이하이다.
- source URL은 허용한 IHB HTTPS 상품 경로만 받고, 다운로드는 확인된 `cloudinary.images-iherb.com` 이미지 경로만 사용한다. redirect는 따르지 않는다. 이미지당 10MB·4천만 화소·25초 제한을 둔다. 단순히 URL 목록만 받았다고 이미지 수집 성공으로 처리하지 않는다.
- 수집 결과는 24시간, 선택 검토는 최대 30분 후 만료된다. 일부 항목 저장 후 revision이 바뀌면 나머지 항목은 새 수집이 필요하다. 함께 바꿀 항목은 같은 검토에서 선택한다.
- 적용하지 않은 후보의 호스팅 파일 및 오래된 스냅샷 보관·정리 정책은 후속 작업이다. 이 변경에서 자동 삭제하지 않는다.

## 검증

- H2 실제 JPA transaction 테스트: 요청 멱등성·귀속, 다른 소싱처 및 누락 상품의 부분 결과, 외부 호출 transaction 분리, 고정 입력 HTML 생성, 선택 필드만 저장, 중복 적용 방지, 실패 항목 시각 보존, 연결 잠금, revision/연결/만료 충돌, 이력 저장 실패 시 상품과 적용 시각의 동시 rollback, 재시작 lease 복구, 429 대기.
- URL 검증: 사설 호스트·유사 도메인·사용자 정보·임의 포트·잘못된 상품 경로를 제외한다.
- 소싱 adapter: HTML 정제, 다운로드 크기 초과 시 transport 취소, 이미지 목록이 비거나 상한을 넘을 때 성공 제외, 콘텐츠 수집의 전체 이미지 인덱스 보존과 기존 등록 5장 상한의 분리를 검증한다.
- API: 인증 Principal 전달, 사용자 입력 source URL/actor를 적용하지 않음, 잘못된 요청·항목을 서비스 호출 전 거절한다.

연결 마켓의 콘텐츠 수정 가능 필드 확인, 실제 DB 콘텐츠 적용 및 이미지·HTML의 외부마켓 쓰기/readback은 별도 완료 조건으로 남는다.

## 통합 검사와 기존 수집 보완

전체 백엔드 테스트 **2,511건(API 329 / Core 1,494 / 인프라 609 / worker 79)**이 한 번의 실행에서 모두 통과했고 실패·오류·건너뜀은 0이다. 새 PostgreSQL 검사는 직접 작성한 DDL로 4개 엔티티를 validate하고, 실제 이력을 저장한 뒤 DDL을 재실행하여 시각·이력·요청 유일성 보존을 확인했다. [검사 집계](evidence/2026-09-07-product-content-tests.json).

프론트 TypeScript·Vite 빌드와 실제 Chrome의 로컬 가상 API 시나리오 11개도 통과했다. 실제 ProductGrid에서 5건 선택, 수집 접수 응답 유실 복구, 대표/추가 이미지 4개의 실제 표시, HTML 실행 차단, 잠금·부분 실패·만료 구분, 같은 검토 저장 재시도, 저장 후 그리드 썸네일 갱신을 확인했다. 운영 API 검증과 구분한다. [Chrome 결과](evidence/2026-09-07-product-content-frontend-fixture.json), [비교 화면](evidence/2026-09-07-product-content-frontend-fixture.png). 기존 번들 500KB 경고는 남아 있다.

기존 주문 상품의 소싱 재고 수집도 보완했다. 크롤 예외를 삼킨 뒤 전체 성공으로 기록하던 흐름을 상품별 실제 결과와 실패/취소 사유로 바꿨다. 수집 전후 짧은 별도 transaction을 사용하고 중간에 상품 버전·URL이 바뀌면 저장하지 않는다. 누락된 가격·재고 관측은 기존 DB값을 지우지 않으며 판매용 수량 300과 구별한다. IHB 응답에 실제 boolean 판매가능값이 없으면 실패하고, 재고가 없으면 임의 0/100으로 만들지 않는다. 명시된 정상 품절·0은 보존한다. 기존 주문 수집 경로 전체를 새 마켓 검토 큐로 전환한 것은 아니다.

독립 리뷰에서 발견한 최신 이미지 5장 잘림과 서버 Retry-After 무시도 수정했다. 콘텐츠 전용 조회는 전체 이미지 인덱스를 검증하고 기존 등록의 5장 규칙은 보존한다. 카탈로그·이미지 양쪽의 Retry-After 초/HTTP날짜를 다음 실행 시각까지 전달하며, 600초 안내가 최소 5분보다 우선함을 DB 작업 검사로 확인했다.

## 실제 카탈로그 응답 계약 수정

운영 상품 328의 수집 검증에서 HTTP 200 카탈로그 응답에 기존 parser가 기대한 `productName`과 `htmlDescription`이 없는 점을 확인했다. 이때 `baseName`이 빈 값으로 변환되어 새 수집이 실패했으며, DB 콘텐츠·수집 성공 시각·적용 시각은 변경되지 않았다.

2026-09-07 확인된 실제 공개 응답(id 18566)을 기준으로 콘텐츠 전용 parser를 분리했다. `displayName`은 응답의 상품 존재를 확인하는 데 사용한다. 생성 HTML의 상품명은 계속 요청 당시 DB 값으로 고정한다. `id`, 요청 URL의 상품 ID, 응답 `url`의 상품 ID가 모두 일치해야 한다. `primaryImageIndex`가 검증된 `imageIndices` 안에 있어야 하고 대표 이미지를 첫 번째로 배치한다. 대표값 누락 시 첫 사진을 임의 선택하지 않는다.

상세 본문은 확인된 `description`, `ingredients`, `suggestedUse`, `supplementFacts`, `warnings`, `disclaimer`, `specialNote` 문자열 항목으로 구성한 뒤 기존 허용 목록 정제를 적용한다. 다른 형태의 대체 필드를 추정하지 않는다. 확인한 표본은 1건이고 추가 2건은 조회 실패로 응답 구조를 검증하지 못했으므로, 미확인 형식은 명시적으로 실패하도록 유지한다. 기존 신규 상품등록 parser의 필드 조합은 이 수정에서 변경하지 않았다.

안전한 고정 사유인 `SOURCE_NAME_MISSING`, `SOURCE_IDENTITY_MISMATCH`, `SOURCE_IMAGES_INVALID`, `SOURCE_DETAILS_INVALID`, `SOURCE_HTTP_FAILED` 등을 수집 결과에 보존한다. 원본 응답·임의 예외 메시지를 사용자 화면에 노출하지 않는다. 실제 응답에서 계약 검증에 필요한 필드만 남긴 fixture와 HTTP 경로 회귀를 포함한 scoped 검사 **31건(Core 14 / 인프라 17)**이 통과했다. 이는 수정 후 운영 수집 재검증 완료와 구분한다.


## 운영 배포·실상품·Chrome 검증

초기 콘텐츠 기능 `b4831b9c` 배포 전에 PostgreSQL 백업(9,522,918 bytes)을 만들고 복원 목록을 검증했다. 명시적 DDL로 4개 콘텐츠 테이블과 IHB lane을 추가한 뒤, 실제 새 배포 JAR의 36개 엔티티를 읽기 전용 validate했다. 운영 `DDL_AUTO=validate`를 유지했다. API·프론트를 전환했고 상품 조회 및 새 이력 조회가 HTTP 200임을 확인했다. [초기 배포 기록](evidence/2026-09-07-product-content-deployment-initial.json).

첫 실상품 수집은 위에 설명한 계약 차이로 실패했다. 실패 이력을 삭제하지 않고 보존했다. `8f935631` 수정 API의 실제 JAR도 36개 엔티티를 validate한 뒤 API만 교체했다. 이 수정에는 DDL 변경이 없었다. [초기 실패](evidence/2026-09-07-product-content-pilot-initial-failure.json), [수정 배포](evidence/2026-09-07-product-content-deployment-fix.json), [수정 후 관련 검사 31건](evidence/2026-09-07-product-content-contract-fix-tests.json).

새 요청으로 같은 운영 상품 **328 / SB 210121IHB031**을 재수집한 결과 `READY`가 됐다. 소싱 이미지 3개를 실제 다운로드·변환·호스팅했고 상세 HTML 2,985자를 생성했다. 이미지·HTML 각각의 수집 성공 시각은 **2026-09-07 22:07:41 KST**이며 DB 적용 시각은 비어 있다. 요청 전후 상품명·소스 이미지·호스팅 이미지·상세 HTML 해시가 일치했다. 같은 requestId를 재전송하면 같은 collection을 반환했다. 연결 상품이므로 두 필드는 `VERIFICATION_REQUIRED` 잠금을 유지한다. [실상품 결과](evidence/2026-09-07-product-content-pilot.json).

운영 서버의 실제 Chrome에서 검색 → 상품 선택 → 이미지·상세 갱신 → 수집 이력 비교를 실행했다. 실제 API 검색·이력 조회는 200이고 페이지 오류는 없었다. 기존 4장과 신규 3장 모두 로드됐으며 기존/신규 HTML iframe은 빈 sandbox를 유지했다. 잠금 체크박스는 비활성 상태였다. 검사 중 수집기를 잠시 쉬게 한 뒤 원래 마켓플러스 탭 1개와 collect/upload=true를 복원했다. [실제 Chrome 결과](evidence/2026-09-07-product-content-live-chrome.json), [운영 화면](evidence/2026-09-07-product-content-live-chrome.png).

이번 검증은 수집·비교만 실행했다. 운영 상품 콘텐츠를 저장하거나 외부마켓 상품을 수정하지 않았다. DB 선택 적용의 원자성·충돌·잠금·이력은 자동 통합 테스트로 확인한 범위다.

운영 화면에서 안내가 길어 비교 이미지가 아래로 밀리는 점을 확인하고 프론트 `a2ef2e51`에서 상단 안내를 줄였다. 정상 수집 참고 사항은 접고, 실패·부분 실패의 구체 사유는 기본 펼침 상태로 유지한다. TypeScript·Vite와 Chrome fixture 11개를 다시 확인했다. 최종 프론트 배포와 실제 Chrome 결과는 각각 [프론트 배포 기록](evidence/2026-09-07-product-content-deployment-ui.json), 위 운영 Chrome 증거를 따른다.
