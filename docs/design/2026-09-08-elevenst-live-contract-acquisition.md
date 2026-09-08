# 11번가 공식 라이브 문서 재확보 — 2026-09-08

사용자가 로그인해 열어 둔 11번가 개발가이드에서 공식 명세 11개를 확보했다. 상품·재고·주소 API를 실행한 결과가 아니라 **문서 계약 증거**다. 실제 상품 등록, 재고 변경, 판매상태 변경은 수행하지 않았다.

Chrome의 Apple Events JavaScript 실행 설정은 꺼져 있었다. 설정을 변경하지 않고, 별도로 만든 11번가 문서 탭에서 Chrome의 `save ... as "only html"` 기능으로 인증된 원문을 저장했다. EUC-KR/CP949 원문의 `jsonData`를 JSON 파서로 추출했다. 익명 HTTP 응답은 판매자 명세 대신 공개 상품검색 내용을 반환했으므로 계약 증거로 채택하지 않았다.

- 정제한 전체 명세: [live-2026-09-08](../external-api/elevenst/live-2026-09-08).
- 원문·정제본 해시 및 출처: [evidence JSON](evidence/2026-09-08-elevenst-live-contract-acquisition.json).
- 원문 HTML과 추출 전용 JSON은 `/private/tmp/sbshop-elevenst-*-live*`에 있다. 저장소에는 페이지 로그인 구성요소, 샘플 API 코드, 주소·연락처·회원번호·수신자명 예시를 넣지 않았다. 문서 필드명과 계약은 유지했다.

## 확보한 계약

| 기능 | 메서드 / 경로 | 저장 명세 |
|---|---|---|
| 신규 등록 | `POST /rest/prodservices/product` | [product-create.json](../external-api/elevenst/live-2026-09-08/product-create.json) |
| 상품 목록 조회 | `POST /rest/prodmarketservice/prodmarket` | [product-list.json](../external-api/elevenst/live-2026-09-08/product-list.json) |
| 상품번호 조회 | `GET /rest/prodmarketservice/prodmarket/[prdNo]` | [product-read.json](../external-api/elevenst/live-2026-09-08/product-read.json) |
| 판매자상품코드 조회 | `GET /rest/prodmarketservice/sellerprodcode/[sellerprdcd]` | [seller-code-read.json](../external-api/elevenst/live-2026-09-08/seller-code-read.json) |
| 다중 상품 재고 조회 | `POST /rest/prodmarketservice/prodmarket/stocks` | [stock-list.json](../external-api/elevenst/live-2026-09-08/stock-list.json) |
| 상품 재고 조회 | `GET /rest/prodmarketservice/prodmarket/stck/[prdNo]` | [stock-read.json](../external-api/elevenst/live-2026-09-08/stock-read.json) |
| 재고 수량 변경 | `PUT /rest/prodservices/stockqty/[prdStckNo]` | [stock-update.json](../external-api/elevenst/live-2026-09-08/stock-update.json) |
| 출고지 주소 조회 | `GET /rest/areaservice/outboundarea` | [outbound-address-read.json](../external-api/elevenst/live-2026-09-08/outbound-address-read.json) |
| 반품·교환지 주소 조회 | `GET /rest/areaservice/inboundarea` | [inbound-address-read.json](../external-api/elevenst/live-2026-09-08/inbound-address-read.json) |
| 전시중지 | `PUT /rest/prodstatservice/stat/stopdisplay/[prdNo]` | [display-stop.json](../external-api/elevenst/live-2026-09-08/display-stop.json) |
| 전시중지 해제 | `PUT /rest/prodstatservice/stat/restartdisplay/[prdNo]` | [display-restart.json](../external-api/elevenst/live-2026-09-08/display-restart.json) |

표의 경로는 공식 명세의 표기이며, 명세 `information.url`에는 `http://api.11st.co.kr`가 들어 있다. 실제 전송 프로토콜·인증·허용 범위는 기존 검증된 클라이언트 계약을 사용해야 한다. 이 문서 조회로 운영 API 호출을 검증한 것은 아니다.

## 판매용 수량 변경에 필요한 경계

[공식 재고 조회](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=40&apiSeq=1623&apiSpecType=1)는 `ProductStock`의 `prdStckNo`, `stckQty`, `optWght`를 제공한다. `stckQty`가 재고수량이고 `selQty`는 판매수량으로 구분된다. `prdStckStatCd`는 `01` 사용, `02` 품절이다.

[공식 수량 변경 v1.2](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=40&apiSeq=1625&apiSpecType=1)의 요청 `ProductStock`에는 다음 필드가 모두 필수다.

```xml
<ProductStock>
  <prdNo>확인한 상품번호</prdNo>
  <prdStckNo>확인한 재고번호</prdStckNo>
  <stckQty>검토한 목표수량</stckQty>
  <optWght>직전 조회한 현재 무게</optWght>
</ProductStock>
```

이 XML은 필드 구조 설명용이며 전송 가능한 예시가 아니다. 수량만 변경하는 경우에도 `optWght`를 생략하거나 임의 값으로 채우면 안 된다. 검토 후 실행 직전 재고번호·현재 무게·상품 연결을 다시 확인하고 기존 무게를 보존해야 한다.

업무 응답은 `ClientMessage`다. `resultCode=200`이 업무 성공이며, 오류 enum은 `400` 잘못된 요청, `404` 해당 상품/재고 번호 없음, `500` 업무 오류, `-1000` 서버 점검이다. 응답 `productNo`의 라벨은 **상품재고번호**다. 상품번호 `prdNo`와 혼동하지 않는다. 수신 성공은 최종 수량 일치 확인을 대신하지 않는다.

옵션 필드 `mixOptNo`, `mixOptNm`, `mixDtlOptNm`가 존재한다. 문서 예시에서 앞의 두 필드가 비어 있어도 상세옵션명은 존재하므로, 두 필드가 비었다는 이유만으로 무옵션 상품이라고 판단할 수 없다. 문서에는 추가구성상품의 조회·변경이 지원되지 않는다는 설명도 있다.

따라서 현재 구현 가능한 좁은 범위는 **상품·계정·재고번호를 검증한 단일 재고항목, 현재 판매중·사용 상태, 양수 목표수량, 기존 무게 보존**이다. 여러 재고항목·추가구성상품·모호한 상태는 제외한다. 이 범위는 구현 판단이며 공식 문서가 모든 단일 재고항목을 무옵션으로 보장한다는 뜻이 아니다.

`stckQty=0`의 허용 여부, 품절 상태에서 양수로 변경했을 때 판매가 재개되는지, 정책에 따른 판매금지 해제 가능 여부는 이 명세에 없다. 별도 전시중지·해제 API는 존재하지만, 이를 수량 변경에 자동 연결할 근거는 확보하지 못했다.

## 신규 등록 준비에 필요한 경계

[신규 등록 v1.0](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=81&apiSeq=1003&apiSpecType=1)의 요청·응답 노드는 총 244개다. `makerNm`은 없다. `prdSelQty`는 스키마 메타데이터에서 optional로 표시되지만 설명에는 필수 입력과 0개 불가가 명시되어 있어, 필수·양수로 보수적으로 다루어야 한다. 신규등록의 0개 제한을 기존 재고 PUT의 규칙으로 그대로 확장하지 않는다.

판매자상품코드 전용 조회가 있으므로, 상품 목록 API의 미지원 검색 필드를 추가할 필요가 없다. [전용 조회 v1.1](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=39&apiSeq=1621&apiSpecType=1)의 `ns2:products/ns2:product`에서 `sellerPrdCd`와 `prdNo`를 확인한다. 동일 코드의 다중 결과·조회 오류·불명 응답을 신규 등록 가능으로 간주하지 않는다.

출고지·반품지 조회는 `ns2:inOutAddresss`의 `ns2:result_message=SUCCESS`를 확인하고 해당 계정의 `ns2:inOutAddress.addrSeq`를 사용한다. 주소 필드 이름이나 과거 등록 값만으로 현재 유효한 주소코드라고 확정하지 않는다.

현재 [상품번호 조회 v1.3](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=39&apiSeq=1620&apiSpecType=1)는 상품명·가격·HTML·원산지 관련 일부 코드·배송유형·반품비 등을 제공한다. **브랜드, 고시, 인증, `addrSeqOut`/`addrSeqIn` 전체 재검증 필드는 확보하지 못했다.** category39에는 목록·상품번호·판매자상품코드의 3개 조회 명세가 있으며, 별도 전체 상세조회 문서는 확인되지 않았다. 신규 등록 전체 검증 완료를 주장할 수 없다.

## 담당자 인계와 검증 범위

- 재고 담당 B에게 재고 조회·변경 원문, 성공/오류 enum, 무게 필수와 0개·옵션 불확실성을 전달했다.
- 등록 담당 A에게 신규등록 전체 명세, 판매자상품코드 조회, 출고지·반품지 조회 및 최종 필드 재검증 한계를 전달했다.
- 애플리케이션 소스·운영 데이터는 수정하지 않았다. 이 작업에서 빌드·API 전송 테스트는 수행하지 않았다.
- 사용자 원래 category81 문서 탭의 URL이 그대로임을 확인하고 다시 활성화했다. 별도로 만든 문서 탭만 종료했으며, 복원 검증 결과를 evidence JSON에 기록했다.
