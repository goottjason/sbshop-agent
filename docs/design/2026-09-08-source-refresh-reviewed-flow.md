# 소싱 가격·재고 검토와 VTB 콘텐츠 갱신

## 구현 범위와 완료 조건

이 단위는 코드·로컬 자동 검증을 위한 변경이다. 운영 배포, 운영 DB 적용, 실제 외부마켓 상품 수정은 수행하지 않았다.

| 기능 | 이번 구현 |
|---|---|
| IHB 콘텐츠 | 기존 전체 이미지·자동 HTML 수집/비교/선택 저장 유지 |
| VTB 콘텐츠 | 실제 공개 상품 JSON에 근거한 전체 이미지·대표 순서·설명 HTML 수집을 추가. 단일 규격만 지원 |
| FTN/COK/OCD 콘텐츠 | [FTN/COK 실제 공개 계약](2026-09-08-ftn-cok-reviewed-source.md)과 [OCD 전용 브라우저 계약](2026-09-08-ocado-reviewed-source.md)으로 확대 |
| IHB/VTB/FTN/COK/OCD 가격·재고 | 단건/최대 50건 영속 수집 → 항목별 비교 → 선택 검토 → 공통 편집 저장/변경 이력/미반영 대상 |
| 가격 산정 | 확인된 원화 상품가격 + 기존 `LandedCostCalculator` 배송비/묶음수량 규칙. 기존 마진·쿠폰·최소마진 정책 유지 |
| 소싱 재고 | 실제 boolean 판매 가능 상태만 사용. 실재고가 없으면 기존값 유지. 판매용 설정 수량 300 등은 변경하지 않음 |
| 기존 즉시 배치 | 상품관리·별도 배치 화면의 크롤 시작/재시도를 같은 검토 모달로 연결. 기존 크롤 HTTP 경로는 409 검토 필요 응답 |
| 외부마켓 완료 | 이 단위에서 완료로 표시하지 않음. DB 미반영 대상 생성 이후 지원 마켓의 별도 영속 작업이 정확한 상품/필드 재조회로 확정해야 함 |

원래 요구 전체를 100% 완료했다고 판단할 근거는 아니다. 아래 미지원 소싱처 계약과 운영 검증이 남아 있다. 연결된 마켓의 편집 잠금은 공통 정책을 따르며 이 기능이 임의로 해제하지 않는다.

## 확인한 소싱 계약

VTB의 기존 `scraper/scrapers/vitabiotics.py`는 상품별 Shopify JSON과 URL의 variant 선택 계약이 있지만 대표 1장만 반환하고 HTML이 없다. 현재 공개 `wellkid-multi-vitamin-liquid.js`를 GET으로 읽어 상품 id/handle/url, 단일 variant, `images` 4장, `featured_image`, `description` 문자열을 확인했다. `cart.js`의 통화도 GBP로 확인했다. 원문 중 계약 검증에 필요한 필드만 [fixture](../../backend/infrastructure/src/test/resources/sourcing/vtb-content-2026-09-08-wellkid.json)에 남겼다.

[Shopify 공식 Ajax Product API](https://shopify.dev/docs/api/ajax/reference/product)는 상품 handle 기반 GET, 이미지 목록과 설명, variant 가격을 제공하며 가격의 실제 통화는 cart currency로 확인하도록 설명한다. 새 VTB 가격 경로는 GBP를 가정만 하지 않고 이 통화 조회를 수행한다. 단가의 정수 minor-unit을 GBP로 변환한 뒤 기존 환율 조회기를 사용한다.

새 VTB 경로는 요청 handle과 응답 handle/url을 대조한다. 이름이 비슷한 다른 handle로 자동 재시도하지 않는다. 가격·재고는 단일 variant 또는 URL이 정확한 variant를 지정할 때만 읽는다. 콘텐츠는 상품 전체 갤러리가 여러 variant 중 어느 규격에 대응하는지 증거가 없어 단일 variant만 허용한다. 대표 이미지는 실제 전체 목록에 포함돼야 한다. 8장을 넘거나 목록이 비거나 중복되면 잘라서 전체 성공으로 반환하지 않는다.

VTB 이미지는 확인한 해당 쇼핑몰의 `cdn.shopify.com/s/files/1/0027/7263/1621/`만 허용한다. IHB의 기존 전용 이미지 도메인 검증과 별도이며, 호스트 부분 일치로 다른 사이트를 허용하지 않는다. HTTPS/정확 host/경로/허용 variant query 검증, redirect 미추적, 응답 크기/시간 제한을 적용한다. 이미지 다운로드·변환·호스팅과 HTML 정제는 기존 IHB의 제한된 파이프라인을 재사용한다.

IHB 가격·재고는 확인한 공개 catalog 상품 id/url과 실제 boolean `isAvailableToPurchase`를 검증한다. `discountPriceAmount` 또는 `listPriceAmount`의 양수 숫자와 대응 표시 가격의 원화 기호를 함께 확인한다. 통화·가격을 확인하지 못하면 가격은 적용 후보에서 제외한다. `stockQuantity`가 없으면 0이나 100을 만들지 않는다. 404/차단/형식 오류도 정상 품절로 바꾸지 않는다.

## 새 API

경로는 `/api/v1/products/source-refresh`이며 운영 프론트 접두사는 `/sbshop-agent`이다. HTTP 인증 Principal만 요청자로 사용하고, 클라이언트가 전달한 소싱 URL·actor·저장 값은 사용하지 않는다.

| 동작 | 요청 | 응답 |
|---|---|---|
| 수집 접수 | `POST /collections`, `{ "requestId":"UUID", "productIds":[1,2] }` | `{id,createdAt,items:[Snapshot...]}` |
| 진행 조회 | `GET /collections/{id}` | 같은 Collection |
| 단건 이력 | `GET /{productId}/history` | 해당 작업자의 최근 20개 Snapshot |
| 선택 검토 | `POST /reviews`, `{ "items":[{"snapshotId":"UUID","fields":["PRICE","STOCK"]}] }` | 공통 EditReview `{reviewId,expiresAt,items:[Plan...]}` |
| 검토 저장 | `POST /commit`, `{ "reviewId":"UUID" }` | 공통 CommitResult. SAVED/CONFLICT/FAILED/EXCLUDED/UNCHANGED 구분 |

Snapshot의 current/proposed 값은 `{costPrice,exchangeRate,stockStatus,stock}`이며 누락 값은 null이다. `fields`의 PRICE/STOCK 각각 `available`, `editable`, `reason`, `collectedAt`, `appliedAt`을 제공한다. 수집 상태는 QUEUED/COLLECTING/READY/PARTIAL/FAILED/UNSUPPORTED이다. READY는 **수집 결과가 준비됨**이며 DB 저장이나 마켓 반영 완료가 아니다.

상품별 원가 계산에 필요한 소싱 배송비 정책·무게·묶음수량을 접수 시 고정한다. 가격 비교 화면에 표시되는 매입 원가는 이 정책으로 계산한 값이다. 다음 저장 검토에는 공통 최소마진 검증과 파생 판매가 영향이 표시된다. 배송비 정책이 없거나 환율/무게를 확인하지 못하면 가격은 제외하고 정상 관측된 재고 상태만 따로 검토할 수 있다.

일반 편집 API는 `stockStatus` 수정을 거절한다. 영속 수집 증거로 생성한 내부 `planSourceObservation`과 MANDATORY `commitReviewedSource`만 이 필드를 취급한다. 저장 직전에 상품 revision, 현재 sourceUrl/vendor, 연결 fingerprint, 배송비 정책, 공통 편집 정책/파생값, 만료를 재검사한다. 일반 `stock` 편집의 잠금도 그대로 유지하며, 전용 소싱 경로에서만 실제 관측한 수량을 검토한다.

## 영속성·복구·오류 의미

- 새 collection/snapshot/review 테이블 3개와 기존 content lane의 `PRICE_STOCK`, `SOURCE_IHB`·`SOURCE_VTB`·`SOURCE_FTN`·`SOURCE_COK`·`SOURCE_OCD` 행을 사용한다. [DDL](../../backend/docs/ddl/2026-09-08-product-source-refresh.sql)은 재적용 가능하며 기존 상품·연결 값을 변경하지 않는다.
- 수집 접수는 actor/requestId 멱등이고 같은 키의 다른 상품 목록은 충돌한다. 수집 목록·고정 입력·검토는 영속 저장한다. 브라우저는 응답 유실 시 같은 요청을 재전송하고 최근 collection을 이어볼 수 있다.
- 가격·재고와 콘텐츠는 각 워크플로 접수 lane을 유지하되, 같은 소싱처의 `SOURCE_*` permit을 공유한다. 동일 소싱처는 두 흐름을 동시에 수집하지 않으며 다른 소싱처는 막지 않는다. 각 소싱처의 가장 오래된 실행 가능한 접수를 선택한다. native HTTP 직전 permit·lease·429 시각을 다시 확인하여 늦은 429 이후 추가 요청을 차단한다. 누락된 gate 행은 외부 호출 없이 QUEUED로 유지한다.
- 외부 조회·다운로드·호스팅 중 DB transaction을 유지하지 않는다. lease는 10분이며 중단된 COLLECTING은 명시적 FAILED로 복구한다. 늦은 결과는 성공으로 기록하지 않는다. 429는 최소 5분과 Retry-After 중 더 늦은 시각을 적용하고 늦은 429도 현재 lease를 깨지 않으며 다음 호출을 지연한다.
- 수집 성공과 DB 적용 시각은 분리한다. 실패 스냅샷을 추가해도 과거 성공/적용 이력은 보존한다. 수집만으로 기존 Product 값이나 기존 수집 오류 메타데이터를 바꾸지 않는다.
- 선택 저장 때 필드·공통 변경 이력·미반영 대상·스냅샷 적용 시각을 상품별 같은 transaction으로 저장한다. 한 상품 실패는 다른 상품의 저장을 지우지 않는다. 같은 검토의 성공 재시도는 중복 이력을 만들지 않는다.
- 가격만 선택 적용하면 기존 재고 수집 실패 표시는 유지한다. 재고 상태를 함께 검토하고 실제 저장할 때만 실제 수집 시각으로 성공 메타데이터를 갱신한다. 값이 동일하여 UNCHANGED인 경우 DB 적용 성공을 새로 만들지 않는다.
- 스냅샷은 수집 후 24시간, 검토는 최대 30분 유효하다. 선택 저장으로 revision이 변경되면 함께 선택하지 않은 다른 항목은 다시 수집해야 한다.

## 기존 배치 전환

`BatchUpdatePage`의 소싱업체별/상품ID별 크롤과 과거 크롤 실패 재시도는 새 검토 모달로 진입한다. 소싱업체 전체를 조용히 수집하지 않고 페이지에서 최대 50개를 선택한다. 마진/쿠폰/최소마진 입력은 이 수집 화면에서 제거하고 상품관리의 별도 가격 검토로 안내한다.

기존 `POST /api/v1/products/batch/crawl-and-update` 및 `/by-supplier`는 유효 요청에 **HTTP 409**, `code=SOURCE_REVIEW_REQUIRED`, 새 `reviewPath`를 반환한다. 배치 시작 이벤트나 DB/마켓 작업을 만들지 않는다. 미사용 `BatchScheduler.scheduleDailyPriceUpdate`는 @Scheduled나 호출자가 없음을 확인했으며, 추후 실수로 호출해도 즉시 쓰기를 수행하지 않고 검토 필요 예외를 낸다. 주문 준비용 `ProductSyncService` 자동 수집과 직접 값 입력 배치의 기존 기능은 이 크롤 전환과 별개다.

## 미지원 소싱처와 필요한 자료

| 소싱처 | 현행 근거 / 남은 정보 |
|---|---|
| TES | 기존 자료에 운영 IP 차단 및 대상 상품 폐기 기록이 있다. 접근 가능한 실제 상품 응답과 사용할 수집 경로가 필요하다. 차단을 품절로 처리하지 않는다. |
| AMZ | 등록된 크롤러나 검증된 상품/규격/가격/콘텐츠 계약이 없다. 사용하는 국가/상품 URL, 허용 API 또는 크롤 방식과 해당 실제 응답 자료가 필요하다. |

미지원 응답은 상품별 UNSUPPORTED로 남긴다. 이미 있는 느슨한 parser를 새 검토 경로에 연결했다는 이유만으로 수집 품질이나 마켓 반영까지 완료했다고 집계하지 않는다.

## 검증 기록

Core 실제 JPA transaction 회귀는 접수 멱등성·귀속, 외부 호출 transaction 분리, 가격/재고 선택 적용, 일반 편집 우회 차단, 실재고 누락 보존, 배송비 정책 미확인 부분 결과, revision/source 변경, 이력 저장 실패 rollback, 429와 lease 복구를 검증한다. 인프라 테스트는 실제 VTB 응답 fixture, 4장 보존·대표 순서, variant 모호성, URL 경계, IHB 통화/boolean 확인과 환율 실패를 검증한다. 2026-09-08 범위 검사에서 Core 30건, 인프라 20건, API·PostgreSQL DDL·기존 배치 호환 검사 34건(합계 84건)이 통과했다. 실행 로그는 `/private/tmp/sbshop-source-refresh-final-tests.log`이다. 최종 TypeScript 검사도 통과했다. 로컬 Chrome fixture 6개 검증(응답 유실 후 동일 요청/검토 재시도, 전후 비교, 부분 실패·미지원 구분, 잘못된 필드 선택 방지, 공통 저장 검토, 즉시 배치·외부 쓰기 미호출)이 통과했으며 로그는 `/private/tmp/sbshop-source-refresh-browser.log`이다. 이 검사는 fixture 환경으로 운영 소싱 저장이나 외부 마켓 변경을 수행하지 않았다.
