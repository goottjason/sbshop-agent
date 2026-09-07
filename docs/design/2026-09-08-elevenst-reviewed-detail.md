# 11번가 상세 HTML 검토 반영 및 정기 조회

2026-09-08. 등록된 현재 계정으로 단건 상품 GET을 읽기 전용 호출하여 상품번호와 sellerPrdCd(SB코드), htmlDetail, selStatCd를 확인했다. 이전 인증 거부 기록을 현재 조회 불가 근거로 계속 사용하지 않는다. 전체 상품 목록의 컬렉션 POST 계약은 이번 단건 조회 근거로 확장하지 않는다.

## 확인한 계약

- 저장소 `docs/external-api/elevenst/11번가_상세설명수정.pdf` 및 [공식 상세설명 수정 안내](https://openapi.11st.co.kr/openapi/OpenApiGuide.tmall?categoryNo=81&apiSeq=1850&apiSpecType=1): `POST /rest/prodservices/updateProductDetailCont/{prdNo}`, `ProductDetailCont/prdDescContClob`, EUC-KR XML.
- 현재 인증 GET `GET /rest/prodmarketservice/prodmarket/{prdNo}`: 상세 응답 필드는 **htmlDetail**이다. 요청 필드 prdDescContClob를 응답 필드로 가정하지 않는다.
- `신규상품조회.pdf`와 `11번가-상품조회.pdf`의 selStatCd: 101 승인대기, 102 승인전, 103 판매중, 104 품절, 105 전시중지, 106 판매정상종료, 108 판매금지.

## 구현 범위

`ElevenstReviewedFields`는 detailHtml만 지원한다. 전체 상품 XML을 되돌려 보내거나 배송·원산지·고시를 자동 보정하지 않는다. 정확한 상품번호/SB코드/계정과 103 또는 104 상태에서만 준비·전송하며 105 등의 판매 재개를 호출하지 않는다. 동일 HTML은 전송을 생략한다. HTML 공백을 그대로 비교하고 CDATA 종료 문자열은 XML 수준에서 분리해 원문을 보존한다. EUC-KR로 표현 불가능한 문자는 자동 물음표 치환 대신 검토 오류로 반환한다.

영속 필드 큐가 HTTP 직전 lease/revision/연결/429 상태를 검증하며, 전송 영수증만으로 성공을 표시하지 않는다. 별도 GET의 실제 htmlDetail 일치가 필드 확인 근거다. callback 중단 예외를 HTTP 실패로 감싸지 않아 승인되지 않은 전송 재시도를 만들지 않는다.

매일 03:00 정기 조회에도 11번가를 포함한다. 마켓별 회차·커서·429 대기를 분리한다. 현재 계정 조회 성공이 과거 모든 상품의 계정 귀속 증거를 뜻하지 않으므로 일반 404만으로 연결을 해제하는 권한은 확대하지 않는다. 명시 판매금지(108)는 기존 연결 처리 계약으로 구분한다.

## 검증

전용 어댑터 6건, 기존 즉시 필드 API의 409 검토 요구 회귀 3건, 4개 직접 마켓의 독립 일일 회차를 포함한 서비스 통합 검사 및 편집/등록 입력 검사를 통과했다. 실제 11번가 상품 쓰기는 이 문서 작성 시 실행하지 않았다. 재고 변경 요청의 정확한 수량 필드·전용 경로는 별도 확인 대상이며 prdStckQty 조회가 확인되었다고 쓰기 계약을 추정하지 않는다.
