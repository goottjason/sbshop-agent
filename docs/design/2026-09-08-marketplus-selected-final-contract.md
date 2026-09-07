# 마켓플러스 선택전송: 최종 필드·값 계약

2026-09-08, 기존 Auction 상품 1건을 운영 Chrome에서 읽었다. `younzara / shop_no=1 / product_no=10186 / P0000PBU`, `auction|shouldbe2480 / D888859044 / prd_entity_no=65b25bf7dbece`를 대조했다. 이 문서는 해당 화면의 계약이며 다른 G마켓·옥션 상품의 허용 범위를 일반화하지 않는다.

실제 `.eSaveDetailPartial`·저장·전송 함수 또는 `/mp/product/rest/save` POST는 실행하지 않았다. 함수 원문·DOM 값·순수 조회인 `getJsonFormData`만 읽었고, 실제 정규화 함수 실행은 네트워크가 차단된 로컬 HTML fixture에서 수행했다. 운영 Chrome의 임시 탭과 수집 플래그는 모든 관측 종료 시 원복했다. [최소 계약 증거](evidence/2026-09-08-marketplus-selected-final-contract.json)에 식별자·선택 가능 여부·현재 값·함수 해시를 남겼다.

## 실제 선택 범위

| 항목 | `modify_partial` 키 / checkbox 값 | 이 상품에서 선택 | 해석 |
|---|---|---|---|
| 판매가 | `selling_price` / `565|product` | 가능 | 현재 원문 값 95,500, `is_default_selling_price=T` |
| 상세설명 | `description` / `568|product` | 가능 | 정규화 후 2,844자, `is_default_detail_info=T` |
| 상품명 | `prd_name` / `564|product` | 가능 | `is_default_prd_name=F`. 이름 변경 조건에 대한 마켓 정책 안내가 있어 허용 확정과 같지 않음 |
| 상품수식어 | `market_data.prd_name_additional` / `576|sales` | 가능 | 상품명과 서로 연관 선택됨. 이름만 단독 변경하는 것으로 검토하면 안 됨 |
| 옵션/재고 | `option_stock` / `583|product` | 가능 | 옵션 전체 묶음이며 단일 숫자 수량 계약이 아님 |
| 대표 이미지 | `imgs.basic1` / `566|product` | **불가: disabled** | 이미지 입력·상속 설정이 보여도 선택전송 지원으로 판단하지 않음 |
| 추가 이미지 | `additional_image` / `567|product` | **불가: disabled** | 동일. disabled를 임의 해제하면 안 됨 |

`aConnectedColumns`는 `prd_name ↔ market_data.prd_name_additional`을 정의한다. `oModifyPartial.checkedPartialModify/getConnectedColumn`이 관련 체크를 함께 반영한다. 선택 변경 후 모든 실제 체크 상태를 다시 읽어 확대된 항목 전체를 검토해야 한다. 브랜드·제조사·원산지·고시·인증·배송 등도 현재 선택칸이 비활성화돼 있다.

옵션/재고는 `is_default_option_stock=T`, `market_data.is_option_stock=F`, 숨김 `current_stock=500`이다. 화면 안내는 옵션/재고관리 미사용 상태에서 전송용 최대 재고값으로 대체한다고 설명한다. 따라서 이 500을 실제 전송 수량이라고 확정하거나 옵션/재고 선택을 수량 동기화로 사용하면 안 된다.

## 저장 처리 경로

1. `.eSaveDetailPartial`은 현재 마켓에 활성화된 체크가 있는지 검사하고 `saveProduct('P')`를 호출한다. 버튼은 검토 전용이 아니다.
2. `saveProduct`는 `setEditorBeforeSubmit`, 옵션 검사, `setUndisabledMarket`, `saveBeforeClickMarketData` 순으로 진행한다. 마지막 함수 안에서 편집기 정규화가 **다시** 실행된 뒤 `getJsonFormData`가 현재 마켓 데이터를 압축 localStorage에 기록한다.
3. `getJsonFormData`는 `#product_info.serializeArray()`의 현재 마켓 prefix 데이터를 객체에 담는다. 같은 `name`이 반복되면 **뒤 항목이 앞 항목을 덮어쓴다**. 체크되지 않은 `is_default_*`에는 별도로 T를 기록한다. T는 기본값 상속, 체크된 F는 별도 값이다.
4. 선택 마켓들의 저장 값을 합치고 기본 이미지 placeholder만 제거한다. `setEditorSchemaByMarketKey`가 편집기 내용을 읽고, `saveMarketTemplateSchemaCheck`가 신규 템플릿을 검사한다. 현재 `template_no=516481>0`이므로 신규 템플릿 조회·전환이 생략되고 초기화된 `template_schema={}`를 사용한다. 이 함수는 `aMarketChecked`에 같은 선택 마켓을 다시 추가하므로 단일 상품에서도 동일 키가 중복될 수 있다. 마켓 수 검증은 원시 배열 길이 대신 서로 다른 식별자와 현재 선택 스위치를 대조해야 한다.
5. `setDefaultData('save', data)`가 mall/shop/product/entity, `market_checked`, `market_selected`, `is_modify=T`를 덧붙인다. `is_temporary_save=F`, `is_modify_partial=T`와 함께 `saveProductCall`이 관리자 내부 `/mp/product/rest/save`에 POST한다.

전송 데이터 객체는 선택된 필드만 담지 않는다. 현재 마켓의 다른 입력값도 함께 직렬화되며 `modify_partial` 체크 목록과 `is_modify_partial`이 선택 범위를 나타낸다. 외부마켓 서버로 전송되는 최종 본문이나 마스킹 처리는 이 프론트 코드만으로 볼 수 없다. 일반 API용으로 이 관리자 POST를 임의 재현하거나 성공 알림을 외부마켓 값 일치로 해석하지 않는다.

## 상세설명의 정확한 값

같은 이름 `template_data[auction|shouldbe2480][description]`의 textarea가 두 개 있다. 앞 항목은 2,892자, 뒤의 동일 이름 id를 가진 편집기 원본 요소는 2,844자다. 편집기 BODY의 원문은 3,096자다.

`getContentFromFroala`는 순수 getter가 아니다. 코드 보기 상태라면 전환하고 URI 속성을 decode한 뒤 editor DOM을 수정한다. `applyContentToFroala`는 현재 Froala `clean.html`로 정제하고 undo 단계를 저장한다. `setValueBeforeSubmit`은 해당 id의 textarea에 편집기 BODY 내용을 넣으면서 `fr-draggable` 표시와 `<null>` 태그를 제거한다. 실제 화면에서 이 함수를 실행하지 않았다.

관측한 Froala 3.2.2 공개 라이브러리, HTML 정제 관련 실제 옵션, BODY 3,096자를 로컬 fixture에 고정했다. CSP `connect-src 'none'`·이미지/외부 리소스 차단과 DNS 차단으로 네트워크를 차단했다. 원래 DOM 일치와 모든 `html*` 옵션 일치를 검사한 후 이 공개 정규화 함수만 두 차례 실행했다. 두 결과가 모두 다음 값으로 같았다.

- 길이: **2,844자**
- SHA-256: `8352144f64a8e210fd2de05b9f508423276058ec485d62e4ea6dbab9a2b7ae9b`
- 현재 뒤쪽 textarea 및 순수 `getJsonFormData` 출력과 일치

이 결과는 현재 문구의 정규화 계약을 확정한다. 사용자가 새 HTML을 반영할 때에도 같은 정규화를 적용한 실제 문자열을 검토 값으로 고정해야 하며 원문 HTML과 같다고 가정하면 안 된다. 상세 값이 큰 경우 전문을 반복 노출하는 대신 검토 원문·길이·해시를 함께 사용할 수 있다.

## 후속 구현에 필요한 경계

현재 가격·HTML은 상속 T이므로 브라우저 POST에 들어 있는 95,500원·정규화 HTML이 서버의 상속 재해석 후에도 그대로 외부마켓에 전달된다고 확정할 수 없다. 후속 검토 전송은 현재 Cafe24 원본과 상속 정책을 함께 고정하거나, 사용자가 검토한 명시 값으로 별도 설정 F를 선택하는 범위를 검토에 표시해야 한다. 설정을 조용히 변경하면 안 된다.

이 화면에서 후속 구현 가능한 첫 범위는 **판매가·상세설명**이다. 실행 전에 동일 계정/상품/entity, 선택 마켓, 폼·편집기 정규화 값, 상속 정책, 확대된 체크 목록을 다시 검증해야 한다. 입력 변경 과정의 자동 체크와 편집기 부수 동작을 반영한 최종 검토가 필요하다. 이미지·수량·조건부 상품명은 이번 근거로 자동 전송 지원을 열지 않는다.

저장 요청의 성공과 마켓 반영은 별개다. 정확한 마켓 상품·계정·필드의 후속 조회와 실패 사유/재시도 절차가 있어야 완료로 판정할 수 있다. 이 화면에서 확인한 기본값 상속과 기존 상품 수정 선택전송은 **Cafe24 신규 상품 POST가 G마켓/옥션 신규 등록을 자동으로 유발하는지**를 입증하지 않는다. 신규 등록 자동화의 설정·계약은 별도 확인 대상이다.

상세 원문과 함수 추적은 `/private/tmp/sbshop-mp-selected-read-contract.json`, `/private/tmp/sbshop-mp-editor-read-contract.json`, `/private/tmp/sbshop-mp-normalizer-read-contract.json`, 격리 결과는 `/private/tmp/sbshop-mp-normalizer-fixture/result.json`에 보관했다. 이 조사에서는 애플리케이션 코드·운영 DB·외부마켓 상품을 변경하지 않았다.
