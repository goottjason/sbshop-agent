# 아이허브 쿠폰 적용 가능·불가 JSON 비교

2026-09-08 라이브 Chrome에서 `https://catalog.app.iherb.com/product/{id}`의 JSON 원문을 확보했다. 일반 HTTP 조회는 로컬·운영 서버 모두 403이었다. Chrome 저장 파일의 확장자는 `.html`이지만 내용은 실제 JSON이며, JSON 파싱 및 상품 ID 대조를 완료했다. SB 상품 매핑은 운영 DB를 읽기 전용으로 조회했다. 상품·쿠폰·장바구니·가격 계산 코드와 운영 설정은 변경하지 않았다.

적용 가능·불가 라벨은 사용자가 제공한 결과다. 동일 쿠폰을 장바구니에서 직접 적용한 실험은 수행하지 않았다. 정확한 쿠폰 코드·사용 시점은 아직 제공되지 않았다.

## 발견한 필드

각 상품 최상위 필드 120개, 중첩 구조와 배열을 포함한 비교 경로 230개를 조사했다. 할인 관련 필드 중 두 그룹에서 각각 일정하고 서로 다른 값은 `discountType`, `discountDisplayType`이다.

| 사용자 확인 | SB코드 | iHerb ID | 브랜드 | brandCode | discountType | discountDisplayType | promotion.promotionExcluded |
|---|---|---|---|---|---:|---:|---|
| 가능 | 231113IHB093 | 65366 | Jovial | JOV | 8 | 3 | false |
| 가능 | 210116IHB034 | 43592 | Doctor's Best | DRB | 8 | 3 | false |
| 가능 | 220708IHB023 | 71684 | California Gold Nutrition | CGN | 8 | 3 | false |
| 불가 | 230804IHB089 | 18627 | Thorne | THR | 0 | 0 | false |
| 불가 | 210412IHB016 | 26860 | Organic Valley | PFM | 0 | 0 | false |
| 불가 | 210115IHB025 | 13937 | Solgar | SOL | 0 | 0 | false |

**이번 6개 표본에서는 `(8,3)`과 `(0,0)` 조합이 사용자 라벨을 완전히 구분한다.** 숫자 8·3·0의 공식 enum 정의 및 모든 할인코드에서의 의미는 확인하지 못했다. 따라서 `8=모든 쿠폰 가능`, `0=항상 모든 쿠폰 불가`라는 일반 규칙이 입증된 것은 아니다. `discountDisplayType`은 이름상 표시 방식일 수 있어 실제 할인 자격과 동일하다고 단정하지 않는다.

전수 비교에서는 평균 평점도 가능 그룹 4.7, 불가 그룹 4.8로 나뉘었다. 할인 자격을 설명할 근거가 없는 우연한 상관이므로 판정 후보에서 제외했다.

## 프로모션 관련 후보 검토

| 경로 | 가능 3개 | 불가 3개 | 해석 |
|---|---|---|---|
| `promotion.promotionExcluded` | 모두 false | 모두 false | 제외라는 이름과 달리 이번 쿠폰 자격을 구분하지 못함 |
| `promoBanner` | 모두 null | 모두 null | 배너 부재는 할인 불가의 근거가 아님 |
| `salesDiscountPercentage` | 모두 0.0 | 모두 0.0 | 상품 자체 세일율과 할인코드 자격은 구분 필요 |
| `isInCartDiscount` | 모두 false | 모두 false | 이번 표본에서 구분 불가 |
| `enabledDiscountBanner` | 모두 true | 모두 true | 배너 기능 설정으로 보이며 구분 불가 |
| `enabledDiscountForPreviouslyPurchased` | 모두 true | 모두 true | 구분 불가 |
| `flag` | JOV/DRB는 빈 배열, CGN만 16·256 | 모두 빈 배열 | CGN 값은 아이허브 브랜드·iTested 표시, 공통 쿠폰 자격 아님 |
| `specialDealInfo`, `trialDiscountInfo` | 모두 null | 모두 null | 이번 표본에서 구분 불가 |
| `inStockInExcludedWarehouses` | 모두 false | 모두 false | 이번 표본에서 구분 불가 |
| `restrictedCountries` | 모두 빈 배열 | PFM만 `["BR"]`, 나머지 빈 배열 | 국가 제한 목록; 한국 쿠폰 불가를 설명할 근거 없음 |
| `isExcludedFromPromo`, `discountEligible`, `tags`, `badges`, `flags` | 키 없음 | 키 없음 | false/null과 달리 필드 자체가 없음. 실제 필드는 단수 `flag` |
| `couponDiscountPercentage` | 키 없음 | 키 없음 | 이번 원문에는 코드별 쿠폰율이 포함되지 않음 |

## 브랜드 가설

가능 그룹은 JOV·DRB·CGN, 불가 그룹은 THR·PFM·SOL이다. 이 표본은 브랜드별로 상품이 하나씩이므로 브랜드 전체 제한과 개별 상품 제한을 분리해 검증할 수 없다. 동일 브랜드의 다른 상품·동일 쿠폰으로 추가 검증해야 한다.

특히 Organic Valley의 식별 코드는 추정한 약자가 아니라 실제 JSON의 **PFM**이다. 한글 상품명·브랜드 문자열만으로 제외 목록을 만들면 오매칭 가능성이 있다.

iHerb 공식 규정은 코드별 상품·브랜드·카테고리 제한, 최소 주문금액 및 중복 적용 조건을 명시한다. 별도 고객지원 문서에서는 계정·국가·기간·앱 전용 조건도 설명한다. 확보한 규정 페이지의 제외 브랜드/상품 목록은 비어 있어 THR·PFM·SOL이 현재 전부 브랜드 단위로 제외된다는 별도 확인 근거로 사용하지 않았다.

- [공식 할인 규정 및 제외 조건](https://kr.iherb.com/info/promo-rules-and-exclusions)
- [공식 할인코드 문제 해결 안내](https://information.iherb.com/hc/en-us/articles/34151888916884-Promotion-Code-Troubleshooting)

## 자동 반영에 대한 제안 — 아직 구현하지 않음

입력 쿠폰율과 실제 계산에 적용한 쿠폰율을 분리하고 판단 이유·원본 값·수집 시점을 보존하는 방식이 적절하다.

1. 확인된 제외 상품·브랜드·쿠폰 조건은 입력 20%여도 적용 쿠폰율을 0%로 계산한다.
2. `(0,0)`은 이번 표본에서 확인된 제외 후보 패턴으로 기록한다. 코드 정의를 확인하기 전에는 모든 상품의 영구 제외 속성으로 저장하지 않는다.
3. `(8,3)`은 가능 후보이며, 지정 쿠폰의 대상·국가·기간 등 추가 조건이 맞아야 입력률을 적용한다.
4. 키 누락·알 수 없는 코드 조합은 `UNKNOWN`으로 분리하고, 보수적인 계산 정책을 선택한다면 쿠폰율 0%와 확인 필요 사유를 함께 표시한다. 누락값을 실제 숫자 0과 동일한 증거로 취급하지 않는다.

현재 `IherbScraperClient.parseProductInfo`는 `discountType`을 읽지만 `discountDisplayType`·`promotion.promotionExcluded`를 상품 DTO로 전달하지 않는다. 또한 `.asInt(0)`으로 누락과 실제 0을 구분하지 않는다. 배치의 `ProductSupplierBatchSource`는 배치에 입력된 쿠폰율을 저장할 값으로 전달하므로 이번에 발견한 조합을 이용한 상품별 제외 장치는 별도 구현이 필요하다.

## 근거 파일

- [상품별 비교 JSON·원문 해시·수집 시점](comparison.json)
- [엑셀에서 열 수 있는 비교 CSV](comparison.csv)
- [검사한 필드 목록](field-inventory.json)

원문 JSON은 `/private/tmp/sbshop-iherb-promo-*.html`에 보존했다. 위 비교 자료는 원문의 프로모션 관련 값만 추출하며, `missing`과 `null`, boolean과 숫자를 구분한다.
