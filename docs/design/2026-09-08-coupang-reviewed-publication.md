# 쿠팡 검토 등록과 필수 입력

2026-09-08. 운영 등록·상품 변경은 이 작업에서 실행하지 않는다. 입력 준비와 전송 결과 확인을 구현하며, POST 접수와 심사 중을 등록 완료로 표시하지 않는다.

## 계약 근거

[상품 생성](https://developers.coupang.com/ko/api/products/product-creation), [상품 조회](https://developers.coupang.com/ko/api/products/querying-product), [카테고리 메타정보](https://developers.coupang.com/ko/api/categories/category-metadata-query), [출고지](https://developers.coupang.com/ko/api/logistics/query-a-shipping-location), [반품지](https://developers.coupang.com/ko/api/logistics/query-a-list-of-return-locations) 공식 계약을 확인했다. 운영 read-only로 지정 배송 코드의 현재 계정·사용 가능 상태와 해외 출고지, 현재 반품지 연락처·주소를 확인했고, 실제 카테고리 73137 메타 응답도 확보했다. 비밀/주소 원문은 저장소 문서나 로그에 옮기지 않는다.

현재 계정 설정의 출고지·반품 코드를 실제 GET 응답과 대조한다. 반품 주소와 연락처는 현재 서버 값으로 준비하여 검토에 표시한다. 상품명·가격·판매용 수량·묶음수량·내용/이미지는 DB의 확인된 값으로 고정한다. 임의 카테고리 73199 fallback, 재고 999, 단위수량 0, 카테고리별로 다른 상품의 옵션 값이 재사용되는 기존 캐시는 사용하지 않는다.

## 입력 API

`GET /api/v1/products/{id}/publication-inputs?market=COUPANG&categoryId=73137`

categoryId를 생략하면 카테고리 추천 결과를 보여준다. 추천은 사용자 확정 전 후보이다. 응답은 `{schema,previousEvidence}`이며 schema에는 categoryId/categoryName, noticeCategories, attributes(exclusiveGroup/허용값/단위 포함), documents, certifications와 suggestedContext가 있다. suggestedContext는 DB 값으로 확인 가능한 옵션만 포함한다. 고시 분류와 인증 구분은 자동 선택하지 않는다.

과거 후보는 동일 상품·SB코드·연동 계정·마켓의 등록 원문에서 고시·옵션·서류·인증만 추출한다. 명시한 카테고리와 다른 과거 자료는 제외한다. 카테고리를 아직 지정하지 않은 첫 조회에서는 과거 카테고리도 후보로 제공하고, 사용자가 그 후보를 고르면 해당 카테고리 메타를 다시 읽어 검토한다. 과거 값의 정확성·현재성은 사용자가 확인해야 하며 자동 재사용하지 않는다. 계정·주소 원문은 후보 응답에 포함하지 않는다.

등록 준비 `Pair.context`는 기존 MarketPublishContext를 사용한다.

```json
{
  "categoryId": "73137",
  "categoryPath": "사용자가 선택한 카테고리",
  "noticeFields": {"제품명": "확인한 이름", "원료명 및 함량": "확인한 문구"},
  "extraFields": {
    "noticeCategoryName": "건강기능식품",
    "attributes": {"수량": "2개", "개당 캡슐/정": "60정"},
    "certifications": {"NOT_REQUIRED": ""},
    "documentUrls": {"MANDATORY INGREDIENTS PIC": "https://assets.example.com/ingredients.jpg"},
    "documentNotApplicable": {"UN 38.3 Test Report": "true"}
  }
}
```

위 값은 형식 예시이며 실제 상품의 인증대상/수량/내용을 뜻하지 않는다. 인증대상 아님도 사용자가 직접 확인하여 선택한다. 카테고리 메타의 필수 여부, 옵션 그룹 택1, SELECT 허용값, NUMBER 단위를 재검증한다. 고시 값이 없으면 '상세 참조'로 만들지 않는다. 일반 필수 서류와 성분표 서류는 비해당으로 생략할 수 없으며 명시적인 조건부 배터리 서류에만 비해당 선택을 허용한다. 서류 URL은 허용 확장자와 길이를 검증하고 쿠팡의 5MB 한도를 화면에서 안내한다. 파일의 실제 크기/다운로드 가능성은 쿠팡 응답과 후속 확인이 필요하다.

## 저장·전송·확정

입력을 포함한 전문과 현재 계정, 배송 요약을 영속 검토에 고정한다. 실제 POST 직전에 공통 큐 callback으로 사용자 동의·revision·연결·lease를 재검사한다. callback 실패는 그대로 전파하며 POST하지 않는다. 요청 후에는 sellerProductId만 접수로 보관한다.

별도 GET에서 같은 계정·상품·SB·단일 옵션, 승인완료, 고정 주요 필드와 옵션의 실제 가격·판매용 수량을 확인해야 연결을 확정한다. 심사중/승인대기중은 승인 대기이며 vendorItemId 미확보 상태에서 완료 연결을 만들지 않는다. 반려·삭제·일치 불명은 완료로 처리하지 않는다. API가 이미지 URL을 파일명/CDN 경로로 바꿔 반환하여 원래 URL과 일치를 증명할 수 없으면 성공을 추정하지 않는다.

쿠팡 등록/필드/기존 adapter 총 133건과 API compile 검사가 통과했다(`/private/tmp/sbshop-coupang-publication-tests.log`). 이후 실제 73137 응답 fixture의 명시 고시·서류 입력 회귀 1건과 입력 조회 서비스의 계정·식별자·비변경 회귀 3건을 추가했으며 최종 전체 검사에 포함한다.
