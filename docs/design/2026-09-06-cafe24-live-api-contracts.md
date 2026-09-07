# 카페24 라이브 크롬 API 대조와 가격 전송 보완

2026-09-06. 사용자가 열어 둔 [카페24 Admin API](https://apidocs.cafe24.com/docs/category/admin-api)를 실제 실행 중인 Chrome에서 읽었다. 상품·품목·재고·이미지·상품 설정·버전·쿼터 문서와 응답 Properties를 대조했다. 문서 탐색만 수행했으며 운영 상품 API의 생성·수정 요청은 실행하지 않았다.

## 필드별 확인 결과

| 대상 | 공식 문서에서 확인한 계약 | 적용 전 남은 확인 |
| --- | --- | --- |
| 상품명·브랜드·카테고리 | [상품 수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no)에 `product_name`, `brand_code`, `add_category_no`, `delete_category_no`가 존재한다. 브랜드는 이름이 아닌 코드다. | 카페24 본상품의 수정 지원이 G마켓·옥션 등 연결 마켓의 수정 지원을 뜻하지 않는다. 공통 DB 필드 잠금을 일괄 해제하지 않는다. |
| 상세 HTML·무게 | 같은 상품 수정에 `description`, `mobile_description`, `product_weight`가 있다. 모바일 설명이 별도로 설정되면 모바일에 그 내용이 사용된다. | PC·모바일 내용 비교와 읽기 검증, 소싱 이미지·상품명·묶음수량 기반 공통 템플릿 재생성은 후속 구현이다. |
| 가격 | 상품 수정에 `price`, `price_excluding_tax`가 있고 세금 계산·쇼핑몰 가격 기준에 따라 사용할 필드가 달라진다. | 아래 조건 검사를 새 가격 작업 경로에 추가했다. 실제 적용 버전과 운영 상품 쓰기 검증은 남아 있다. |
| 바코드 | [품목 수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no-variants-by-variant-code)의 `gtin` 문자열 최대 14자. [품목 상세 조회](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no-variants-by-variant-code)의 응답 Properties에도 `gtin`이 존재한다. 예시 JSON에 없다는 이유로 조회 불가로 판단하지 않는다. | 상품 전체가 아닌 품목 코드 단위다. 기존 무옵션 상품 수정의 422 기록과 현재 API 버전·상품 유형을 대조해야 한다. 모든 상품의 바코드를 편집 가능 상태로 전환하지 않았다. |
| 판매용 재고수량 | [품목 재고 수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no-variants-by-variant-code-inventories)의 `request.quantity`, [품목 재고 조회](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no-variants-by-variant-code-inventories)의 `inventory.quantity`가 대응한다. | SB상품과 실제 `variant_code`의 대응이 필요하다. [품목 목록](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no-variants)에 여러 품목이 있으면 임의로 첫 품목을 선택하지 않는다. |
| 대표 이미지 | [상품 이미지 등록](https://apidocs.cafe24.com/docs/admin/post-products-by-product-no-images)의 `image_upload_type=A`는 대표 이미지 방식, `B`는 크기별 개별 방식이다. | 기존 이미지 구성과 업로드 결과를 비교·조회하는 작업이 필요하다. |
| 부가 이미지 | [추가 이미지 등록](https://apidocs.cafe24.com/docs/admin/post-products-by-product-no-additionalimages)·[수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no-additionalimages)에 `additional_image` 배열이 있다. 최대 20개, 이미지별 5MB, 호출당 30MB 제한을 확인했다. | 소싱처 크롤 결과와 기존 이미지 비교·선택 반영·성공 시각 기록은 아직 새 작업 흐름에 연결하지 않았다. |

상품 수정의 본문은 최상위 `shop_no`와 `request`를 사용한다. `supply_quantity`는 확인한 상품 수정 요청 필드에 없다. 기존 코드의 상품 수준 `supply_quantity` 전송이나 로컬 `variants[0].quantity` 변경을 실제 재고 반영 증거로 사용하지 않는다. 기존 가격·재고 통합 전송 경로의 교체는 이번 가격 조건 보완과 별도다.

재고는 숫자만으로 판매 가능 여부가 결정되지 않는다. `use_inventory=F`이면 수량 기반 재고 관리가 사용되지 않고, `display_soldout=F`이면 재고가 음수여도 주문이 계속될 수 있다. 새 수량 작업에서는 수량과 현재 설정을 함께 읽고, Q10/Q14의 품절·재입고 정책 및 정수 수량에 맞는 요청을 검토해야 한다. 이 조사에서 재고 관리 설정을 임의로 변경하지 않았다.

## 가격 전송 조건 수정

[상품 수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no), [상품 조회](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no), [상품 설정 조회](https://apidocs.cafe24.com/docs/admin/get-products-setting)를 대조했다. 상품 설정 조회에는 `READ_STORE` 권한이 필요하며 응답은 `product` 객체다.

| 상품 세금 계산 | 쇼핑몰 계산 기준 | 새 가격 작업의 처리 |
| --- | --- | --- |
| `tax_calculation=A` 자동 | 모든 기준 | 문서에 따라 `price` 사용. 설정 조회를 추가하지 않는다. |
| `tax_calculation=M` 수동 | `S`, `A`, `P` | 상품 설정을 읽어 확인한 뒤 `price` 사용. |
| `tax_calculation=M` 수동 | `B` 상품가 | `price_excluding_tax`가 필요하므로 현재 세금 포함 목표가 전송을 보류하고 사유를 표시한다. 세금 제외 금액을 임의 계산하지 않는다. |
| 누락·알 수 없는 값 | 누락·잘못된 쇼핑몰·알 수 없는 값 | 전송 보류 또는 조회 실패로 반환한다. 기본값으로 성공 처리하지 않는다. |

`Cafe24MarketClient.readSalePrice`에 이 검사를 추가했다. 기존 판매 중·`market_sync=F` 조건을 통과한 상품에 적용하며 설정 조회 이후에도 계정이 같은지 검사한다. 새 가격 큐는 전송 전 읽기 결과의 수정 허용 여부를 확인한다. `writeSalePrice`는 양의 정수 가격만 받으며 `shop_no=1`, `request.price`만 전송한다.

권한 403은 조회 실패로 전달하고 429의 대기 정보는 유지한다. 상품 세금 설정이나 마켓 연동 설정을 수정하지 않는다. 기존 `syncPriceAndStock` 전체를 이 경로로 이전한 것은 아니다. HTTP 응답 성공이나 로컬 캐시 변경을 상품 전체 동기화 완료로 사용하지 않는 기존 새 큐의 검증 원칙을 유지한다.

## 하위 마켓·버전·호출 제한

[상품 조회](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no)의 `market_sync`는 마켓 연동 여부다. 이것만으로 G마켓·옥션 각각의 실제 전달 결과·필드별 수정 가능 여부를 증명할 수 없다. 이후 [공식 도움말 재조사](2026-09-06-marketplus-auto-forwarding-recheck.md)에서 카페24 수정 → 마켓플러스 → 연동 마켓의 자동 전송 구조를 확인했다. 별도 공개 API 자료를 필수로 요구했던 Q25는 정정하고, 현재 설정·필드 상속·API별 전송 발생과 상품관리이력 결과 확인으로 남은 범위를 구체화했다.

[버전 가이드](https://apidocs.cafe24.com/docs/guide/versioning)에 따르면 `X-Cafe24-Api-Version` 헤더를 생략하면 개발자센터 앱에 설정된 버전을 사용한다. 현재 `Cafe24RestClient`는 이 헤더를 지정하지 않는다. 이후 사용자가 버전관리 탭을 열고 최신 버전 변경을 승인했다. `younzara` 앱의 만료된 설정 `2021-09-01`을 화면에서 제공하는 최신 **2026-09-01**로 변경했고, 새로 고침 후 앱을 다시 선택하여 저장된 현재 버전과 최신 버전 안내를 확인했다. [설정 변경 기록](2026-09-06-cafe24-api-version-upgrade.md). 문서에 나온 필드와 실제 상품의 호환 검증은 남아 있다.

[쿼터 가이드](https://apidocs.cafe24.com/docs/guide/api-quota)는 버킷 용량 40, 초당 2개 감소를 명시한다. 이를 지속적으로 초당 40회 호출해도 된다는 뜻으로 구현하지 않는다. 기본 총량 제한은 10분당 3,000회와 누적 처리 시간 600초이며 `X-Api-Call-Limit`와 429 대기를 함께 고려해야 한다. 새 작업의 DB gate만으로 기존 모든 API 경로의 요청 간격까지 통합된 것은 아니다.

## 검증과 현재 적용 상태

- `Cafe24PricePolicyContractTest` 16건, `MarketPriceContractTest` 10건, `MarketPriceSyncIntegrationTest` 12건, **총 38건 통과**. 세금 계산 분기, 잘못된 설정 응답, 권한 오류, 429 대기, 조회 중 계정 변경, 기존 판매·마켓 연동 제한을 포함한다.
- 변경 Java 3개 파일의 Spotless 검사 통과. 실행 로그: `/private/tmp/sbshop-cafe24-live-contract-check.log`, `/private/tmp/sbshop-cafe24-contract-format.log`.
- Chrome 문서 읽기 도구에 새 `apidocs.cafe24.com` 주소와 문서 내 이동·Properties 펼치기를 추가했다. 허용한 문서 주소에서만 동작하며 API 실행 예제는 호출하지 않는다.
- 이 단계는 로컬 코드·검증·문서 보완이다. 운영 배포, 실제 외부 상품 수정, 운영 데이터 변경은 수행하지 않았다.

앱 버전 설정은 이후 2026-09-01로 변경·재확인했다. 이어 [실제 상품 조회와 재고·GTIN 보완](2026-09-06-cafe24-inventory-and-gtin-verification.md)에서 GET 응답 버전, 무옵션 단일 품목의 재고 조회를 확인하고 기존 재고·GTIN 메서드의 재조회 검증을 추가했다. 마켓플러스 전송 계약, 무옵션 GTIN 쓰기 검증, 재고 영속 큐·자동 복구·콘텐츠 비교 적용은 남아 있다. 전체 진행 범위는 [진행 기록](2026-09-06-products-implementation-progress.md)을 따른다.

2026-09-06 20:31 KST [권한 추가·운영 재인증](2026-09-06-cafe24-reauthorization-scope.md)을 완료했다. 실제 `GET /admin/products/setting?shop_no=1`은 403에서 200으로 바뀌었고, `calculate_price_based_on=S` 및 응답 버전 2026-09-01을 확인했다. 기존 표본 10186/14086의 `tax_calculation=M`, `market_sync=T`도 GET으로 재확인했다. 현재 가격 기준은 위 조건표의 M+S 분기에 해당한다. 하위 마켓 전달 범위 검증을 대신하지 않으며 이번 확인에서 상품 가격·재고·판매 상태를 쓰거나 로컬의 `market_sync=T` 보류 조건을 해제하지 않았다.
