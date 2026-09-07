# 스마트스토어·쿠팡·카페24 검토 등록

2026-09-08. 상품 등록 API를 운영에서 실행하지 않고, 공식 문서·전송 계약 fixture·격리 DB로 검증했다. 카페24 상품 생성 후 마켓플러스 전달은 현재 쇼핑몰 설정에 따른다. 카페24 등록 결과를 G마켓·옥션 등록 성공으로 복제하지 않는다.

## 사용자 흐름

1. 미등록/삭제 후보에서 삭제 근거를 확인하고 선택한다. 영구 판매금지, 기존 연결, 생성 여부 미확인, 소싱처 삭제·재고 불명/품절/수집 오류는 후보에서 제외한다.
2. 쿠팡은 실제 카테고리 메타를 조회하여 고시·필수 옵션·인증·서류를 입력한다. 같은 SB·판매자·카테고리로 검증된 과거 자료도 **사용자가 선택하여 불러온 경우에만** 채택한다. 조회 실패나 필수 입력 누락은 준비를 막는다.
3. 마켓별 상품명·카테고리·가격·판매용 수량·배송/반품·판매 시작 계획을 검토한다. 소싱 원재고를 전송하지 않고 별도 `salesQuantity`를 사용한다(1~999999, 기본 300).
4. 최종 동의한 상품/마켓만 접수한다. PREVIEW → QUEUED → POST_STARTED → VERIFY이며, 쿠팡 승인 대기는 AWAITING_APPROVAL로 표시한다.
5. 생성 POST는 한 번만 보낸다. 응답 불명/중단은 UNKNOWN_CREATE이며, 사용자가 상품번호를 넣으면 GET으로만 생성 결과를 확인한다. 심사·품목·검토 값이 일치하는 별도 조회가 있어야 REGISTERED가 된다.

## 공통 엔진

- `MarketPublicationService.Pair(productId, market, context)`의 선택적 context는 쿠팡 상품별 입력에 사용한다. 다른 마켓에 복제하지 않는다.
- 준비한 요청은 DB에 동결하며 이후 수정된 DB 값으로 다시 조합하여 POST하지 않는다. 준비/마켓 I/O는 DB 트랜잭션 밖이고, commit/전송 직전/확정 때 상품 revision·계정·연결·판매금지·가격 정책을 검사한다.
- PREVIEW 준비도 같은 공유 gate lease를 예약한다. 준비 중 실제 API 요청마다 계정·lease·cooldown을 재검사하며, 늦은 429는 다음 요청을 차단한다. 준비 결과 저장 전에 다시 검사하고 finally에서 자신의 lease만 반환한다. scope 밖 기존 호출은 영향을 받지 않으며 쿠팡 요청 body의 streaming 전송 방식도 유지한다.
- 생성 POST 직전 callback이 영속 `post_authorized`를 한 번만 기록한다. 공유 `{MARKET}_ORIGIN_READ` gate의 소유권·lease·cooldown을 재검사한다. 늦은 429는 현재 다른 worker의 lease를 빼앗지 않고 Retry-After를 연장한다.
- 이전 마켓 식별자·삭제 상태는 `previousIdentifiers`에 보존한다. 연결 확정과 연결 이벤트 이력은 같은 트랜잭션이다. 이력 기록 실패를 성공으로 처리하지 않는다.
- REGISTERED는 해당 검토 등록의 주요 정보 확인이다. `isSynced=true`나 외부 자식 마켓 성공을 복제하지 않는다.

## 카페24 계약과 후처리

POST 상품 생성은 마켓플러스의 전역 자동 신규등록 설정을 확인/제어하지 않는다. 최신 POST schema에는 market_sync 필드가 없으며 F를 임의로 전송하지 않는다. 기존 상품의 use_auto_sync/상속 설정만으로 새 상품 생성의 자동 전달을 추론하지 않는다. 검토 화면에는 **카페24 상품 생성 · 마켓플러스 전달은 현재 설정 · G마켓/옥션 등록 완료는 별도 확인**을 명시한다. 생성 후 실제 market_sync가 T이면 임의 후처리 전송은 보류하여 연동 범위를 확인한다. 이 경계는 단순 native-only 생성 보장을 뜻하지 않는다.

공식 최신 Chrome 본문: `https://apidocs.cafe24.com/docs/admin/post-products`, `https://apidocs.cafe24.com/docs/admin/post-products-images`.
로컬 원문은 `/private/tmp/sbshop-final-contracts/cafe24-create.json`, `cafe24-description-images.json`, 전체 PDF 텍스트 `cafe24.txt`이다. 제품 생성 구간은 PDF 175~185쪽, 이미지 업로드 구간은 추출본 8674~8683행이다.

- 이미지 업로드는 `POST /api/v2/admin/products/images`, body `requests:[{image:Base64}]`, 결과 `image:[{path:HTTPS주소}]`이다. 공식 한도는 이미지당 10MB, 요청 합계 30MB, 한 번에 20건이다. 구현은 대표+추가 합계 20개까지만 준비한다.
- 준비 단계에는 설정된 R2 HTTPS 경로에서 이미지를 제한적으로 내려받고 이 이미지 업로드 API를 사용할 수 있다. 상품 생성·수정·브랜드 생성은 하지 않는다. 내부 주소/redirect와 비이미지/크기 초과를 거절한다.
- 생성 POST는 최상위 `request` 안에 정확한 SB, 상품명, 분류, 가격/공급가, 상세 HTML, 단일품목(`has_option=F`), 이미지, 검증된 기존 브랜드(있을 때), 무게(있을 때)를 고정한다. **selling=F, display=F**로 생성한다. 문서에 없는 `supply_quantity`, `quantity`, `use_inventory`, `variants`, `use_external_image`를 상품 생성 body에 넣지 않는다.
- 준비 단계에 현재 분류·shop=1, 브랜드의 정확한 단일 코드, 현재 가격 계산 설정을 GET으로 확인한다. `calculate_price_based_on=B`는 세금 제외 가격 변환 근거가 없어 준비를 보류한다. 배송비는 기본 배송 정책 상속(`shipping_fee_by_product=F`)을 명시하여 검토한다.
- 원본 생성 POST의 응답에서 shop=1, product_no, product_code, SB를 확인한 작업만 task의 `_sbshop_receipt_operation` 증거를 갖는다. 이 내부 marker는 연결 식별자에는 복제하지 않는다.
- 이후 매 실행마다 원상품·단일 variant·inventory를 먼저 GET한다. 본상품은 `market_sync=F`여야 한다. 상품/품목·검토 필드/이미지가 다르면 자동 설정하지 않는다. 품목 판매정지는 자동 해제하지 않는다.
- 생성 영수증 귀속이 확인된 새 상품만 단일 품목 inventory PUT(`quantity=salesQuantity,use_inventory=T,display_soldout=T`) → 다음 실행의 별도 GET → 본상품 PUT(`selling=T,display=T`) → 별도 GET 확정을 진행한다. 한 claim에 최대 한 PUT이며 매 PUT callback에 revision·계정·소싱 상태·원본 영수증·공유 429 gate를 재검사한다.
- 후처리 실제 PUT는 자동 3회 한도이다. 400/401/403/404/422는 명시 실패로 ACTION_REQUIRED, timeout/5xx/429는 재조회 우선이다. 운영자가 동일 생성 영수증의 동일 상품번호 재조회로 명시 재시도하면 후처리 횟수를 다시 시작한다. 다른 수동 상품번호에는 영수증 권한을 옮기지 않는다.

### 이미지 검증의 실제 한계

상품 생성 `image_upload_type=A`의 공식 설명은 상세 이미지를 바탕으로 목록/작은목록/축소 이미지를 리사이즈한다는 것이다. 그러나 **detail_image 바이트 자체를 보존한다는 계약은 없다**. 생성/조회 문서에서 원본 이미지 식별자나 원본 체크섬을 별도로 반환하는 필드도 확인하지 못했다. 관측 가능한 가장 큰 이미지는 `detail_image`, `additional_image[].big`다.

구현은 이 실제 URL의 바이트 SHA-256을 준비한 원본과 비교한다. URL이 변경되어도 바이트가 같으면 확인 가능하다. 카페24가 재압축/변환했다면 동일 상품 이미지처럼 보여도 자동 성공으로 취급하지 않으며 `등록 이미지 원본을 확인하지 못했습니다. 마켓의 이미지 변환 여부를 확인하세요.`로 보류한다. 실제 운영 생성 POST를 하지 않았으므로 이미지 변환 유무와 최종 생성 왕복 성공을 아직 실증하지 않았다. 이 한계를 숨기거나 임의의 유사도 비교로 자동 승인하지 않는다. 변환이 발생하는 계정에서는 별도 원본 대응 계약 또는 명시 이미지 검토/승인 기능이 후속 단위다.

## 검증

- 공통 등록 **33건을 실제 PostgreSQL에서 모두 통과**, infrastructure **32건**(Cafe24 계약 11, 실제 HTTP scope 8, 기존 SS/Cafe24/쿠팡 wire 13), 실제 PostgreSQL DDL **1건** 통과. 조회 식별자/품목 검사 및 실제 이력 실패 rollback을 포함한다.
- 실제 PostgreSQL 스키마 검사는 `MarketPublicationSchemaPostgresTest`(root 통합 담당), 실제 공통 서비스 검사는 `tools/verify-market-publication-postgres.py`의 격리된 일회용 PostgreSQL 전용이다. 운영 DB 주소를 받지 않는다.
- 화면 입력 컴포넌트 Chrome fixture 8개 흐름 통과. 등록Jobs 통합 Chrome fixture **5개 흐름 통과**: 후보→필수 입력 차단→상품별 context 전달→마켓별 최종 동의→불명 결과 GET 재조회. 다건 쿠팡 입력 폼은 접힘 패널로 한 상품씩 조회하여 최초 메타 호출의 동시 폭주를 피한다. frontend 전체 TypeScript 검사도 통과했다. 증거는 `docs/design/evidence/2026-09-08-registration-jobs-fixture.{json,png}`다.
- 배포 DDL: `backend/docs/ddl/2026-09-08-reviewed-publication-multiple-markets.sql`의 `post_authorized`, `setup_writes`. 기존 불명 생성 기록과 재실행 이력은 보존한다.

## 남은 경계

11번가 신규 검토 등록·수량 API, 과거 타 마켓 계정 귀속을 전제로 하는 일반 404 자동 해제, G마켓·옥션 생성/재등록, 카페24 이미지 변환 대응은 이 단계의 완료에 포함하지 않는다. 쿠팡 필수 고시·인증·문서의 사실 판단은 운영자의 상품별 입력·선택이 필요하며 자동으로 '해당 없음'을 채우지 않는다.

최종 로그: `/private/tmp/sbshop-publication-preparation-final-tests.log`, `/private/tmp/sbshop-publication-preparation-postgres-tests.log`, `/private/tmp/sbshop-publication-schema-final-tests.log`, `/private/tmp/sbshop-publication-browser.log`. 커밋·운영 쓰기·배포는 수행하지 않았다.
