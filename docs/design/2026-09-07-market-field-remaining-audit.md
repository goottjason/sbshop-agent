# 상품 관리 잔여 마켓 필드 계약 감사

2026-09-07. 운영 기준은 [최종 배포 기록](2026-09-07-products-deployment.md)의 `545f06fd`, 소스 기준은 `5b5714d8`이다. 09-06 문서의 ‘미배포’는 당시 기록이며 지금의 운영 상태로 재사용하지 않았다. 이번 감사는 소스·프로젝트 API PDF·맥 Downloads PDF와 공식 공개 문서의 읽기만 수행했다. 외부 상품 쓰기, 운영 브라우저 탭·설정 변경, 계정·토큰 열람은 수행하지 않았다.

**검색·편집 검토·연결 이력·가격 작업·마켓플러스 관측 기반은 운영에 반영되어 있다. 전체 필드의 수정·동기화와 전체 마켓의 검토 등록은 아직 완성되지 않았다.** 특히 연결 상품의 이미지·HTML·브랜드·재고 등을 편집 화면에서 여는 작업과 그 변경을 검증된 외부 작업으로 전달하는 작업을 별도로 완료해야 한다.

## 판단 기준

- **문서**: 수정 요청 필드·경로의 근거가 있음. 실제 상품 종류에서의 수정 성공을 뜻하지 않는다.
- **레거시**: 기존 즉시 실행 메서드가 있음. 새 검토/영속 작업/재조회 흐름의 완료와 구별한다.
- **검증 작업**: 변경 버전·계정·식별자를 고정하고 DB 작업으로 실행, 별도 GET 일치까지 확인한다.
- **보류**: 미지원으로 확정한 것이 아니라 상품 유형·전달 범위·읽기 계약 등 미확인 조건이 있어 제한한다.

‘API에 필드가 있음’, ‘요청이 HTTP 200’, ‘카페24 본상품 값이 바뀜’, ‘마켓플러스 전송 이력 성공’, ‘외부 마켓의 현재 필드가 일치함’은 서로 다른 증거다.

## 실제 편집 잠금

근거: [ProductEditPolicy.java](../../backend/core/src/main/java/com/sbshop/agent/core/application/product/edit/ProductEditPolicy.java).

| 조건/필드 | 현재 정책 | 남은 작업 |
| --- | --- | --- |
| 모든 활성 연결 해제 | 일반 업무 필드 편집 허용 | 과거 값·해제 이유 보존은 기존 검토/이력 경로 유지 |
| 메모 | 내부 편집 허용 | 외부 전송 불필요 |
| 가격 정책 7필드 | 편집 허용: `salePrice`, `costPrice`, `marginRate`, `couponRate`, `minMarginPrice`, `exchangeRate`, `deliveryFee` | 마켓별 지원 가격 작업만 전달; 원가·환율을 동일 이름의 외부 필드에 직접 복사하지 않음 |
| 쿠팡 연결 중 카테고리 | `LOCKED` | 문서상 등록 후 직접 변경 제한에 따른 잠금 |
| 연결 중 재고·판매용 수량·바코드 | `VERIFICATION_REQUIRED` | 마켓 전체의 쓰기·재조회 계약에 맞춘 조건부 개방 |
| 연결 중 상품명·브랜드·무게·이미지·HTML 등 나머지 | `VERIFICATION_REQUIRED` | 마켓별 상품 유형과 파생 영향 규칙을 세분화해야 함 |
| 생성 결과 확인 중 또는 조회 ID 없는 연결 | 일반 변경 차단/확인 필요 | 생성 여부 확인 후 처리; 조회 실패로 자동 해제하지 않음 |

따라서 현재의 ‘단건·숫자 일괄 편집 배포’는 **연결 상품의 모든 DB 필드 편집 완료**라는 의미가 아니다. Q5/Q6의 심사 변경 편집·수동 전송도 현재 일반화된 정책으로 구현된 상태가 아니다. DB 품질을 지키기 위한 보류와 마켓 정책상 영구 수정 금지를 화면에서 구별해야 한다.

## 필드별 지원 근거와 구현 범위

아래 ‘잠금’은 위 정책을 공통 적용한다. 가격 이외에 연결 상품 편집을 자동으로 열어도 된다는 표가 아니다.

| 필드 | 스마트스토어 | 쿠팡 | 11번가 | 카페24 본상품 | 카페24 → G마켓/옥션 |
| --- | --- | --- | --- | --- | --- |
| 판매가 | 문서 `salePrice`; 가격 전용 검토 큐+GET 확인 구현 | 승인 후 옵션 가격 전용 API; 단일 옵션/판매자/승인·판매상태 검증 큐 구현 | 가격 전용 **GET 쓰기** 경로 확보; 새 가격 큐 미지원, Q28 미확정 | 세금 계산 검사+`price` 전용 큐, 판매 중·`market_sync=F` 한정 | 표본 두 마켓 기본 상속; 전송 이력 수집 운영. 새 필드 작업과 최종 가격 확인 연결 미완 |
| 재고 | 원상품 `stockQuantity`, 옵션 수량 구조 존재; 기존 전송 메서드는 있으나 새 영속 수량 큐 미완 | `/vendor-items/{id}/quantities/{quantity}` + 재조회 `amountInStock` 공식 근거 있음; 기존 즉시 경로, 새 수량 큐 미완 | 종합 수정 `prdSelQty` 및 옵션 `optionAllQty`/`colCount` 근거 있음. 수량 전용 수정·검증 조회 계약 부족. 기존 메서드는 **수량 인자를 전송하지 않음** | 품목 재고 PUT/GET `quantity` 검증 보완 있음; 단일 품목·재고 설정·`market_sync=F` 한정, 영속 큐 미완 | 표본 기본 상속; 품목 재고 API가 자동 전송을 발생시키는지 별도 확인 필요. 일반 상품수정 관측으로 대체 불가 |
| 상품명 | 수정 PDF/원상품 `name`; 기존 필드 전송 있음, 검토 큐·필드 GET 비교 미완 | 승인 필요 수정의 등록명/노출명 구별 필요; 상품명 심사 작업 미완 | `prdNm` p2; 기존 XML 전체 수정 있음, 재조회 검토 큐 미완 | `product_name`; 기존 전송 있음, 검토 큐 미완 | 표본 두 마켓 **별도** 설정. 카페24 이름 변경 자동 상속 대상이라고 볼 수 없음 |
| 카테고리 | `leafCategoryId`; 표준형 옵션·상품 유형 제약 검토 필요 | 등록 후 판매자 직접 변경 불가 근거. 시스템 카테고리 잠금 구현 | 수정 PDF p1 소/세카테고리 제한, 신규 상품 유형 제한 | `add_category_no`/`delete_category_no` 문서; 시스템 분류 매핑 및 다중 카테고리 차분 미완 | 하위 마켓의 카테고리 제한과 기존 매핑 별도 확인. 본상품 수정 지원을 그대로 확대하지 않음 |
| 브랜드 | `brandId`/`brandName` 구조 및 기존 전송; 이름 조합 영향·읽기 비교 미완 | 승인 필요 `brand`; 심사/조회/현재 계정 상품 유형 확인 미완 | `brand` p2, 코드 우선 규칙. 기존 전송 있으나 새 큐 미완 | `brand_code`; resolver 있음, 코드 읽기 확인·큐 미완 | 상속 설정·코드 대응 미확인. 이름 ‘별도’ 설정으로 브랜드의 영향까지 단정하지 않음 |
| 배송 무게 | 대응되는 일반 배송 무게 필드 미확인. 상품 모델의 체중이나 용량을 대신 쓰지 않음 | 대응되는 일반 배송 무게 필드 미확인 | `prdWghtUpdateYn=Y`, `prdWght` g(p11); 옵션 `optWght` 별도. 새 무게 수정·재조회 큐 미완 | `product_weight` kg 문서; DB kg와 일치하나 새 필드 큐 미완 | 배송 정책/상속 범위 미확인 |
| GTIN/바코드 | `sellerCodeInfo.sellerBarcode`; 판매자 바코드 의미 확인. 기존 쓰기 있음, 새 큐 미완 | 승인 필요 `items[].barcode`; 옵션 대상·심사 필요, 새 큐 미완 | 일반 바코드 수정 필드 근거 미확보. 다른 태그로 대체 금지 | **품목 `gtin`**, 최대 14자 문서+품목 GET 대응 확인. 선행 0 보존·조회 일치 보완 있음. 무옵션 쓰기·다품목 매핑 보류 | 본상품 품목 GTIN이 두 마켓의 바코드를 수정한다는 근거 미확인 |
| 대표/추가 이미지 | 업로드 반환 URL, 대표+최대 9개 추가 이미지 구조. 기존 전송 있음; 비교/승인/읽기 큐 미완 | 승인 필요 옵션별 `images`; 레거시 첫 옵션 처리의 대상 범위를 확인해야 함 | `prdImage01` 등 p3, 전체 XML 경로 있음; 전체 이미지 readback 미완 | 상품 이미지와 추가 이미지가 별도 리소스. 레거시는 대표 1장 중심으로 추가 이미지 전체 반영 미보장 | 표본 G마켓 기본, **옥션 대표·추가 모두 별도**. 설정 덮어쓰기 없이 개별 비교 필요 |
| 상세 HTML | `detailContent`; 기존 경로 있음, 신규 변경 버전별 GET 비교 미완 | 옵션별 `contents`; 승인 요청·심사 후 반영 구분 필요 | `htmlDetail` p3, 상세설명 전용 경로 존재; 해당 PDF 한글 깨짐에 따른 계약 재확인 필요 | `description`/`mobile_description`; 기존 PC 설명 경로만으로 모바일 별도 설정까지 증명 못함 | 표본 두 마켓 기본. 자동 전송 이력과 해당 변경 버전/최종 HTML 연결 미완 |

문서 근거: [초기 필드 조사](2026-09-05-products-field-rules-and-sync-design.md), [카페24 라이브 원문 대조](2026-09-06-cafe24-live-api-contracts.md), [품목 재고·GTIN 실제 GET](2026-09-06-cafe24-inventory-and-gtin-verification.md), [현재 마켓플러스 설정·표본](2026-09-06-marketplus-live-settings-and-results.md). 카페24의 버전 업그레이드/재인증은 완료되었으며 READ_STORE 권한 부족을 현재의 차단 사유로 다시 적지 않는다.

## 프로젝트·다운로드 원문을 다시 읽은 결과

`pypdf`로 실제 파일의 페이지별 텍스트를 추출해 필드명과 주변 제약을 대조했다. Downloads의 11번가 상품수정(14p), 옵션수정(3p), 무게변경(2p), 무게 포함 신규상품조회(1p), 상품조회(2p)는 프로젝트에도 대응 자료가 있다. 다운로드 사본이 있다고 최신 버전임을 추정하지 않았다.

| 원문 파일/위치 | 이번에 재확인한 증거 | 남는 한계 |
| --- | --- | --- |
| [11번가-상품수정.pdf](../external-api/elevenst/11번가-상품수정.pdf) p8~9 | `prdSelQty` 존재. 옵션이 있으면 옵션 총합 반영, 0 불가, 판매 중지는 별도 처리 안내 | 필수 항목이 많은 전체 상품 수정 계약이다. 수량만 보낸 요청이 안전한 부분 수정이라는 증거가 아님 |
| 같은 PDF p1/p2/p11/p12 | 카테고리 범위, `prdNm`, `brand`, `prdWghtUpdateYn`/`prdWght`, `company` | `company`는 제조사/수입사이며 사용자의 DB 제조사와 동일 의미인지·어떤 기존 값을 보존할지 필요. `makerNm`은 근거 없음 |
| [11번가 상품 옵션 수정.pdf](../external-api/elevenst/11번가%20상품%20옵션%20수정.pdf) p1~3 | `/rest/prodservices/updateProductOption/[prdNo]`, `optionAllQty`, `colCount`, `colOptCount`; 옵션 형태별 적용 제약 | 옵션 ID·SB 매핑과 읽기 응답의 대응 미확인. 옵션 전체 교체로 수량만 수정하는 부작용 검토 필요 |
| [신규상품조회.pdf](../external-api/elevenst/신규상품조회.pdf) p1~2 | `/rest/prodmarketservice/prodmarket/[prdNo]`, `prdNo`, `selPrc`, `selStatCd` | 해당 PDF 텍스트에 `prdSelQty`·`colCount`가 없다. 실제 재고 확인에 이 문서를 바로 사용할 수 없음 |
| [상품재고무게변경처리_PUT방식.pdf](../external-api/elevenst/11번가%20상품재고무게변경처리_PUT방식.pdf) p1 | `/stockwght/[prdStckNo]`, `ProductStock.prdNo`, `prdStckNo`, `optWght` | **옵션 무게** 수정이며 재고 수량 수정 근거가 아님 |
| [11번가_상세설명수정.pdf](../external-api/elevenst/11번가_상세설명수정.pdf) p1~2 | `/rest/prodservices/updateProductDetailCont/[prdNo]`, POST, Product 반환 구조의 영문은 추출 가능 | 한글 및 일부 태그가 깨짐. 상세 요청 전체 계약을 추출문만으로 확정하지 않음 |

따라서 Q27을 ‘11번가 재고 자료가 전혀 없다’고 다시 질문할 필요는 없다. 정확히 부족한 것은 **재고처리 메뉴의 수량 변경 및 수량 조회 상세 문서**, 상품/옵션 번호 대응과 품절/재개 조건이다. 기존 원문은 수량 수정 지원의 일부 근거로 이미 확보되어 있다.

## 이번 공식 웹 확인

- [네이버 멀티 상품 변경](https://apicenter.commerce.naver.com/docs/commerce-api/current/update-multi-products-product)에서 `PATCH /v1/products/origin-products/multi-update`와 가격·재고·할인·판매상태 변경 범위를 재확인했다. 현재 상단 버전 표시는 2.88.0(2026-09-07)이다. 도구에서 요청 세부 스키마가 펼쳐지지 않아 새 재고 JSON을 추측하지 않았다.
- [네이버 원상품 구조체](https://apicenter.commerce.naver.com/docs/commerce-api/current/schemas/%EC%9B%90%EC%83%81%ED%92%88-%EC%A0%95%EB%B3%B4-%EA%B5%AC%EC%A1%B0%EC%B2%B4)의 `stockQuantity`, `sellerCodeInfo.sellerBarcode`, 이미지 목록을 확인했다. 구조체만으로 모든 상품 유형의 수정 허용을 확정하지 않는다. [옵션 재고 변경](https://apicenter.commerce.naver.com/docs/commerce-api/current/update-options-product)은 별도 문서이며 일부 페이지의 버전 표시가 다르므로 후속 구현 시 동일 버전의 상세 요청을 고정해야 한다.
- [쿠팡 수량 변경](https://developers.coupang.com/ko/api/products/changing-quantity-of-each-product-item)과 [수량/가격/상태 조회](https://developers.coupang.com/ko/api/products/query-quantitypricestatus-by-product-items)를 대조했다. 옵션 ID 단위 PUT과 GET의 `data.sellerItemId`, `amountInStock`, `onSale`을 이용한 검증 작업은 구체화할 수 있다. [공식 FAQ](https://developers.coupang.com/ko/faq/price-and-inventory-does-not-change)도 승인 완료 상품에는 가격·수량 전용 경로가 필요함을 설명한다.
- [쿠팡 수정 문서](https://developers.coupang.com/ko/api/products/modify-product)에서 상품명·브랜드·이미지·내용·단위수량 구조를 재확인했다. `unitCount`는 구성품 수/단위가격 목적이며 재고 수량으로 사용하지 않는다.
- 11번가 가격 공식 상세 페이지와 카페24 품목 재고 상세 페이지의 일반 웹 도구 열기는 오류로 끝났다. 이는 API 미지원 증거가 아니다. 기존의 사용자 라이브 Chrome 확보 기록을 근거로 유지한다. 제3자 블로그·라이브러리로 빈 계약을 채우지 않았다.

## 새 작업과 기존 전송 경로의 차이

| 코드 | 확인 사실 | 후속 조치 |
| --- | --- | --- |
| [MarketPriceSyncService](../../backend/core/src/main/java/com/sbshop/agent/core/application/market/sync/MarketPriceSyncService.java) | `SUPPORTED`는 스마트스토어·쿠팡·카페24. 저장 변경 중 가격 필드만 자동 접수. 다른 변경은 `ACTION_REQUIRED` | 전체 필드 완료로 성공 상태를 확장하지 말고 필드별 작업 추가 |
| [MarketEditField](../../backend/core/src/main/java/com/sbshop/agent/core/domain/market/client/dto/MarketEditField.java) | 레거시 필드 enum은 브랜드·상품명·제조사 3개 | 전체 DB 업무 필드가 공통 수정 API로 구현된 것이 아님 |
| [ElevenstMarketClient](../../backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java) | `syncPriceAndStock`는 가격 GET 쓰기와 판매 중지/재개만 수행. `quantity` 미사용. 제조사 요청은 시작 전에 거절하여 `makerNm` 전송 방지 | 수량 전송 성공으로 표시하지 않는 계약이 필요. `fieldValue`에 남은 제조사/브랜드 공용 분기는 지원 근거로 사용하지 않음 |
| [Cafe24MarketClient](../../backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/adapter/Cafe24MarketClient.java) | 검증된 가격·수량·GTIN과 기존 이름/브랜드/콘텐츠 메서드가 공존. 기존 필드 메서드는 제조사를 제거하고, `shop_no`를 request 안에 넣음. 콘텐츠 메서드는 기존 이미지 삭제 시도 후 대표 1장 업로드 중심 | 모든 카페24 메서드가 최신 계약/재조회로 교체되었다고 계산하지 않음. 부분 요청 누락·추가 이미지·본문 위치·읽기 검증을 단계별 교체 |
| [MarketClient](../../backend/core/src/main/java/com/sbshop/agent/core/domain/market/client/MarketClient.java)와 [MarketPublicationService](../../backend/core/src/main/java/com/sbshop/agent/core/application/market/sync/MarketPublicationService.java) | 검토 등록 3메서드 기본 구현은 미지원. 스마트스토어만 고정 요청/전송/검증 override 제공 | 다른 마켓의 기존 `publish()` 존재와 새 검토 등록 완료를 구별 |

이 감사에서 위 기존 코드를 수정하거나 운영 경로를 차단하지 않았다. 레거시 진입점의 전체 성공 표시·자동 판매 재개·429 공유 범위를 별도 검토해야 하며, 새 안전한 큐가 존재한다는 사실만으로 기존 모든 배치가 그 큐를 사용한다고 가정하면 안 된다.

## 바로 구현 가능한 단위와 확인을 기다리는 단위

| 우선 단위 | 바로 할 수 있는 일 | 운영 기능 개방 전 남는 확인 |
| --- | --- | --- |
| 소싱 콘텐츠 갱신 검토 | 기존 크롤러를 재사용해 원본 스냅샷, 이미지/자동 HTML 전후 비교, 선택 적용, 성공 시각·이력 구현. 직접 작성 문구를 임의로 상정하지 않음 | 연결 상품의 적용은 현 편집 정책 유지. 외부 동기화 계약이 확보된 마켓/필드만 단계적으로 개방 |
| 쿠팡 단일 옵션 재고 작업 | 확인된 수량 PUT/GET을 기존 가격 작업의 버전·계정·임대·429 모델에 맞춰 분리 구현. 목표 수량 일치만 확인, 판매 재개 요청은 섞지 않음 | 기존 계정/상품/단일 옵션 대응과 현재 상태 사전 조회; 필드 개방 전 다른 활성 마켓의 수량 지원도 확인 |
| 카페24 본상품 단일 품목 재고 작업 | 이미 구현한 검증 어댑터를 영속 작업·재조회 재시도에 연결 | `market_sync=F` 조건 유지. T 개방은 필드별 자동 전달 파일럿 후 |
| 11번가 가격 전용 작업 | 확보한 GET 쓰기 계약과 상품 GET `selPrc`를 이용한 준비/조회/실패 분류 구현 가능 | Q28 확인 전 자동 인상 활성화 금지. 기존 혜택 상태를 알 수 없는 경우 표시·보류 |
| 마켓플러스 변경 작업 연계 | 관측 이력을 변경 시각·계정·상품·필드 대상과 연결하는 대기/불일치 모델, 누락 이력 표시, 화면 동선 | 전송 성공 이력만으로 현재 필드 일치 확정 금지. 자동 재전송 선택자/대상 범위와 실제 최종 확인 경로 필요 |
| 정기 점검 확장 | 현재 계정에 확실히 귀속된 상품만 점검 대상으로 만드는 준비/필터 구현 가능 | Q26: 쿠팡·11번가·카페24 과거 상품번호가 각각 현재 한 계정 소유인지 |
| 다른 마켓 검토 등록 | 기존 등록 요청 작성기를 순수 준비 단계로 분리하고 필수값/카테고리/옵션/이미지를 고정하는 로컬 구현 | 생성 결과 조회 계약·중복 방지·심사 상태 구분·실제 파일럿 필요; G/A는 MP 흐름 별도 |

아직 필요한 사용자 정보는 Q26(계정 귀속), Q28(11번가 가격 인상 시 협의 혜택 종료 가능성 정책)이다. 필요한 원문은 Q27의 **11번가 재고 수량 수정/조회 상세 페이지**다. 마켓플러스 공개 API 자료를 다시 필수로 요청하지 않는다. 기존 수정 자동 전송 경로는 카페24 → 마켓플러스 → G마켓/옥션이며, 수집기는 현재 읽기·이력 저장만 담당한다.

## 진행률 독립 평가

새 화면·검토·연결 보호·관측 운영 기반의 진척은 크다. 다만 원요구에서 중요한 ‘연결 상품의 실질적인 모든 필드 편집과 마켓 동기화’, ‘오래된 이미지/HTML 최신화’, ‘미등록 모든 마켓 선택 등록’이 남아 있다. 이 기준이면 이번 감사 시작 시점의 전체 완료도는 **약 55~65% 범위**가 타당하다. 이는 구현 범위와 검증 수준에 따른 추정이며 코드 줄 수나 테스트 통과율이 아니다.

대표 가중치 예시는 화면/검색 20, DB 편집·정책 15, 소싱 갱신 15, 외부 동기화 25, 연결 점검 10, 검토 등록 10, 운영 전환 5다. 각각의 완료 비율을 높게 잡더라도 전체 필드와 G/A 최종 반영 확인을 남겨 둔 상태에서 80~90%로 표현하기는 어렵다. 이번 병렬 작업에서 로컬 구현을 추가해도 실제 통합·배포·필드 검증이 끝나기 전에는 운영 완료율에 가산하지 않는다.

검증: 원문 페이지·코드 경로 대조 및 문서 링크 확인. 코드 변경이 없는 감사 문서이므로 애플리케이션 테스트는 새로 실행하지 않았다. 운영 테스트·관측 402건 등 수치는 최종 배포 기록의 해당 시각 자료를 참조하며 이번에 새로 조회한 수치로 표시하지 않는다.
