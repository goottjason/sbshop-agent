# 11번가 신규 등록 입력 준비

11번가 신규등록 공식 원문을 확보해 **필수 입력 점검·현재 계정 주소 선택·JSON 보관/불러오기**를 구현했다. 이번 단계는 등록 실행을 지원하지 않는다. `MarketPublicationService.SUPPORTED`에 11번가를 추가하지 않았으며 기존 등록·재등록 후보의 실행 선택은 계속 제외된다. 준비 화면과 서버 응답 모두 `INPUT_ONLY`, `executable=false`로 구분한다.

## 확보된 계약과 레거시 차이

원문은 사용자가 연 공식 개발 가이드의 [신규 상품등록](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=81&apiSeq=1003&apiSpecType=1)을 Chrome의 HTML 저장 기능으로 읽은 것이다. 전체 244개 필드·조건과 응답은 [보존한 JSON](../external-api/elevenst/live-2026-09-08/product-create.json), 원문 해시는 [문서 확보 증거](evidence/2026-09-08-elevenst-live-contract-acquisition.json)에 있다. 기존 로컬 PDF 재조사에서 신규 등록 원문을 찾지 못했다는 [감사](2026-09-08-elevenst-stock-publication-reaudit.md)의 결론은 이 후속 원문 확보로 갱신됐다.

- 생성 요청은 EUC-KR XML `POST /rest/prodservices/product`, 루트 `Product`다.
- 성공 영수증은 `ClientMessage/resultCode=200 또는 210`, **`productNo`**, `message`다. 레거시는 `prdNo`를 추출해 새 계약과 다르다. 원문 결과코드 설명은 200을 일반상품, 210을 신규상품 성공으로 구분하지만, 메시지 설명에는 신규/일반 전환 문구가 서로 엇갈리는 부분이 있다. 파서는 두 코드를 그대로 보존하고 메시지를 반환하며, 이 영수증만으로 등록 유형·전체 필드 성공·활성 연결을 확정하지 않는다. `AuthMessage/resultCode=200`은 인증 오류이므로 허용하지 않는다.
- `sellerPrdCd`는 지원하며 **중복 가능**하다. 원문에 `makerNm`은 없다.
- `prdSelQty`는 설명상 필수이며 옵션이 있으면 옵션 합계로 덮어쓴다. 신규 등록은 0개 불가다. 입력 점검은 시스템의 `salesQuantity`를 쓰고 소싱 관측 `stock`이나 임의 999를 대체값으로 쓰지 않는다.
- 대표이미지는 서버에서 내려받아 600×600으로 변환한다. JPG/JPEG/PNG/WebP와 응답 Content-Type 조건이 있다. 원본 바이트 SHA·원본 URL과 재조회 URL의 단순 동일성을 성공 근거로 사용하지 않는다. 현재 재조회 원문에는 원본 업로드 ID나 원본 이미지 해시를 증명하는 필드가 없다.
- 단순 옵션 없는 일반상품의 이미지 01~04를 입력 준비 범위로 삼는다. `prdImage09`는 별도의 쇼킹딜/카드뷰 이미지다.
- 레거시의 주소 5/3, 배송비 7000, 원산지 미국, 택배사/템플릿 코드, A/S `.` 및 고시 ‘상세설명 참조’ 기본값은 새 준비 경로에 넣지 않았다. 테스트 안의 숫자·문구는 합성 입력일 뿐 운영 기본값이 아니다.

## 화면과 서버 동작

상품관리의 ‘미등록·삭제 상품 등록’에서 후보를 조회하고 11번가 행의 ‘필수 입력 준비’를 열 수 있다. 옵션 없는 새 상품·고정가·일반배송·업체배송·택배·무료배송의 입력 자료를 먼저 점검한다. 다른 판매/배송 방식은 값을 지어내지 않고 추가 조건 확인 대상으로 표시한다.

| 단계 | 동작 |
|---|---|
| 입력 자료 조회 | 현재 DB 상품값과 문서에서 추린 필수·조건부 항목을 제공한다. 카테고리·원산지·판매자 가입 유형·세금·미성년자 판매·인증은 자동 선택하지 않는다. |
| 계정 주소 선택 | 현재 계정의 출고지·반품지 GET으로 주소 코드·주소명·주소를 선택지로 제공한다. 전화·회원번호·수신자명은 UI 응답에 포함하지 않는다. |
| 입력 점검 | 원문 enum, 양의 주소 코드, 10원 단위 배송비, EUC-KR 보존 가능 문자, A/S/반품 안내 4000바이트, 원산지/원재료/인증 조건과 고시 누락을 확인한다. 주소 코드는 같은 현재 계정 목록을 다시 조회해 확인한다. |
| 상품·계정 충돌 | 조회한 상품 revision과 계정 해시 참조를 점검 요청에 고정한다. 선택 이후 계정이 바뀌어 주소 번호만 우연히 같은 경우도 거절한다. 점검 중 상품 또는 계정 변경 시 완료로 반환하지 않는다. |
| 보관·불러오기 | 입력 항목 점검을 통과하면 같은 SB 상품의 JSON을 내보낼 수 있다. 주소 원문은 내보내지 않고 코드만 저장한다. 같은 계정/SB의 보관 파일만 불러오며 불러온 뒤 다시 점검해야 한다. 값을 바꾸거나 자료를 새로 조회하면 이전 점검·내보내기 자격을 무효화한다. |
| 실행 구분 | 입력 항목을 채웠어도 `executable=false`다. 새 상품 쓰기·상품 필드 저장·등록 큐 접수·활성 연결 생성은 이 API에서 수행하지 않는다. |

고시 유형/항목은 기존에 원문을 확인한 2023 XLSX의 가공식품 `891031` 11항목, 건강기능식품 `891032` 13항목이다. 현재 전체 카테고리의 필수 조건을 검증한 것으로 표시하지 않는다. 화면에서 실제 문구를 모두 입력해야 하며, 신규 계약 경로는 레거시 고시 자동 대체 함수를 호출하지 않는다.

API:

- `GET /api/v1/products/{id}/publication-inputs?market=ELEVEN_STREET`
- `POST /api/v1/products/{id}/publication-inputs/elevenst-review` — `productRevision`, `accountReference`, `context`를 받아 **입력만 점검**한다. 마켓 생성 API가 아니다.

쿠팡의 기존 등록 입력 스키마·과거 후보 선택 흐름은 유지했다. 공용 인터페이스에 추가한 입력 점검 메서드의 기본 동작은 미지원 오류이며, 11번가 어댑터만 이 준비 기능을 구현한다.

## 주소 API 실응답 검증

공식 [출고지](../external-api/elevenst/live-2026-09-08/outbound-address-read.json)·[반품지](../external-api/elevenst/live-2026-09-08/inbound-address-read.json) 경로는 각각 `GET /rest/areaservice/outboundarea`, `GET /rest/areaservice/inboundarea`다. 문서는 `inOutAddresss/result_message=SUCCESS` 뒤 `inOutAddress.addrSeq`를 사용하라고 설명한다.

2026-09-08 실제 현재 계정 GET에서 출고지 4개, 반품지 3개를 확인했다. 두 응답 모두 HTTP 200, EUC-KR XML, 동일 계정이며 **result_message 필드가 생략**됐다. 정확한 `inOutAddresss` 루트 아래 유효한 `inOutAddress`만 있고, 각 행의 양의 고유 코드·주소명·주소가 존재했다. 이 실제 형태도 주소 선택지로 허용하되, 마커 없는 빈 루트, 빈 마커 태그, 실패 마커, 다른 루트 오류 필드, 중복/불완전 코드는 거절한다. 개인정보를 제외한 상태·필드 존재·원문 해시만 [읽기 관측](evidence/2026-09-08-elevenst-address-read.json)에 보존했다.

주소 GET은 기존 등록 준비의 공용 마켓 gate/lease를 재사용한다. 네트워크 동안 DB transaction을 유지하지 않는다. 각 GET 직전 계정·lease·공유 cooldown을 검사하고, 429의 Retry-After를 공유 gate에 반영한다. 예외·늦은 429·계정 변경·새 소유자의 lease를 다룰 때 조회 성공이나 부분 주소 결과를 반환하지 않는다. 이번 실제 읽기 검증은 원문 인코딩·마커 형태를 확인하면서 각 주소 endpoint를 3회씩 GET했으며, 상품/연결/스케줄을 변경하지 않았다. 마지막 관측의 원문 해시는 앞선 응답들과 같았다.

## 남은 실행 계약과 다음 단위

| 다음 단위 | 확보 여부·남은 작업 |
|---|---|
| 등록 후 정확한 상품 찾기 | [sellerPrdCd 전용 GET](../external-api/elevenst/live-2026-09-08/seller-code-read.json) 확보. 경로는 `/rest/prodmarketservice/sellerprodcode/{sellerprdcd}`. 결과가 복수일 수 있으므로 정확한 생성 영수증·SB·상품값을 함께 대조하는 실행 모듈이 필요하다. |
| 재조회 필드 검증 | [상품번호 조회](../external-api/elevenst/live-2026-09-08/product-read.json) v1.3에서 상품번호/SB/판매가/수량/HTML/판매상태 및 일부 배송 필드를 확인했다. 브랜드·고시·인증·출고/반품 주소 코드 전체를 재확인하는 필드는 확보되지 않았다. |
| 이미지 확인 | 600×600 변환의 정확한 결과·원본 대응 증거가 필요하다. 원본 SHA 비교로 실패를 강제하거나 이미지 필드를 누락해 성공으로 표시하지 않는다. |
| 상품별 사실 입력 | 현재 카테고리·원산지 코드·고시·인증의 실제 값과 Seller Office 가입 유형 확인이 필요하다. 이번 준비 화면은 이 값을 모으고 재사용할 수 있게 한다. |
| 검토 등록 실행 | 위 검증 계약이 갖춰지면 기존 영속 큐의 revision/연결 fingerprint/공용 429/POST 의도/응답 불명 시 재조회·중복 방지를 연결한다. 영수증 파서만으로 지원 목록을 열지 않는다. |

검증: 계약 24건, 주소 9건, 신규 입력 서비스 5건·기존 쿠팡 입력 서비스 3건, 영속 publication 36건(새 read-only scope 3건 포함), 서로 다른 77건이 통과했다. 마지막 실주소 응답 차이는 주소 9건을 다시 실행해 확인했다. Chrome 로컬 fixture 7개 흐름과 전체 frontend TypeScript 검사도 통과했다. [화면 검증 결과](evidence/2026-09-08-elevenst-publication-inputs-browser.json), [화면 캡처](evidence/2026-09-08-elevenst-publication-inputs-browser.png). 운영 마켓 쓰기 검증은 수행하지 않았다.
