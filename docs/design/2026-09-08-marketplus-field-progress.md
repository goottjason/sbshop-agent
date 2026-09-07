# G마켓·옥션 필드별 반영 근거와 공개 표시값 관측

2026-09-08. 이 문서는 이번에 구현한 추적·관측 범위를 설명한다. 전송 성공 이력은 현재 DB 버전과 선택 필드의 최종값 일치를 증명하지 않는다.

| 단계 | 연결한 근거 | 완료로 취급하는 범위 |
| --- | --- | --- |
| DB 저장 | 최근 10개 변경 이력의 revision·필드·개별 전송 대상 | DB에 해당 변경 저장 |
| 카페24 본상품 | 동일 이력·필드·상품·연결·계정·revision의 가격/수량/일반 필드 작업 | 해당 카페24 값의 재조회 일치. 심사가 필요한 작업은 심사 완료 포함 |
| 마켓플러스 | 현재 상품·계정 연결의 상품수정 전송 이력, 저장 시각 이후 관측 | 성공/실패/같은 분 충돌 관측. revision·필드별 전송 완료로 승격하지 않음 |
| G마켓·옥션 | 현재 연결·DB revision에서 판매자와 상품번호를 확인한 공개값 | 공개 표시값 관측. 현재 필드 상속과 최종 목표값 대조 근거가 없으면 일치로 승격하지 않음 |

API `GET /api/v1/products/{id}/marketplus-field-progress`는 최대 240개 필드를 표시한다. 과거 DB 버전과 연결이 바뀐 관측은 구분한다. 기존 전송 이력 화면 안에서 마켓·필드 조건으로 조회하고, 준비 영역에서 계정·카페24 코드·외부 상품번호 및 남은 확인 사항을 본다.

## 독립 공개값 관측

- `GET /api/v1/products/{id}/marketplus-public-observations/context`: 현재 revision·활성 연결·판매계정·고정된 공개상품 URL.
- 전용 서버 Browser의 `observe_public(context)`: 고정된 G마켓/옥션 상품 URL만 방문하고 packaged DOM reader와 strict parser를 실행. 반환값은 다음 POST의 본문이다. 사용자가 요청한 공개가격 조회는 별도 영속 큐와 기존 observer의 직렬 루프에 연결했다. [공개가격 조회 큐 계약](2026-09-08-marketplus-public-check-queue.md)을 따른다.
- `POST /api/v1/products/{id}/marketplus-public-observations`: 현재 연결·revision·설정 계정·5분 이내 관측·정수 범위 검증 후 수치만 저장한다. 인증된 요청자를 이력에 기록하며 같은 fingerprint 재시도는 중복 저장하지 않는다.
- `GET /api/v1/products/{id}/marketplus-public-observations`: 최근 100개 관측과 현재 연결/버전 여부.

저장되는 공개 필드는 판매가와, 옥션에서 무옵션 수량 입력창/남은수량을 함께 확인한 경우의 수량이다. HTML 원문·쿠키·인증정보·배송주소를 저장하지 않는다. 수량 후보가 옵션 수량인지 확인되지 않으면 제외한다. 오류/403/빈 페이지/상품·계정 모호성은 수집 실패이며 삭제나 정상 동기화로 취급하지 않는다. DDL은 `backend/docs/ddl/2026-09-07-marketplus-public-observations.sql`이다.

## 실제 Chrome 재확인

`evidence/2026-09-08-marketplus-live-field-contract.json`은 별도 임시 탭에서 읽은 자료다. collector를 잠시 멈췄다가 원래 flags를 복원했고 원래 탭을 유지했다. 저장·전송 버튼은 누르지 않았다.

카페24 10186/P0000PBU의 옥션 shouldbe2480 화면에서 가격·상세 HTML·옵션 재고는 기본 정보(T, 선택 해제), 상품명·대표 이미지·추가 이미지는 별도 정보(F, 선택됨)였다. 이 결과는 해당 상품/계정/시각의 설정이다. 공개 G마켓 3490115053과 옥션 D888859044는 재시도 시 모두 `img_error` 오류 화면이었다. 따라서 이번 조회로 정상 표시값이나 삭제 여부를 추가 확정하지 않았다.

부분전송 checkbox는 `template_data[market|account][modify_partial][field]`이며 값은 서버가 내려준 항목 ID와 구분값이다. 상품명/수식어 등 연결 필드 자동 선택 코드가 있어 개별 checkbox 한 개를 선택했다고 전송 범위를 한 필드로 가정하면 안 된다. 정확한 최종 선택 집합과 관련 필드를 검토해야 한다. 관리자 화면 내부 REST 경로는 공개 API 계약과 구분한다.

## 검증

- Java: 필드 단계 10건, 공개 관측 서비스 7건, API DTO 5건 통과.
- Python: 공개 DOM 결과 parser/Browser 경로 9건, 기존 observer/relay 포함 전체 42건 통과.
- 로컬 Chrome fixture: MP 단계 5건, 일반 필드 검토 화면 10건 통과. 운영 마켓 쓰기 검증이 아니다.
- 프론트 타입 검사와 production build 통과. 기존 큰 청크 경고는 남아 있다.

## 선택 전송 handler 추가 확인

`evidence/2026-09-08-marketplus-live-transfer-selection.json`에 같은 상품의 현재 DOM 식별과 선택전송 anchor를 기록했다. `a.eSaveDetailPartial`은 `saveProduct('P')`를 호출하여 관리자 내부 `/mp/product/rest/save`에 POST하며 `is_modify_partial=T`를 설정한다. 이 요청은 단순 검토 조회가 아니다. 공급자의 handler는 저장·전송 결과를 받은 뒤 완료 안내와 창 닫기를 수행하므로, 확인창만 보기 위해 이 버튼을 누르면 안 된다. 실제 호출은 하지 않았다.

동일 화면에서 `bIsModify=true`, `bIsRegist=false`, `aMongoData`의 옥션 상품번호 D888859044, 단일 선택 계정 auction|shouldbe2480, 쇼핑몰 상품 10186/P0000PBU, shop1을 확인했다. 판매가는 95500인 hidden 화면 입력을 읽을 수 있다. 상세 HTML은 같은 name의 textarea가 두 개이고 길이가 달라 첫 항목을 그대로 최종 전송값으로 취급할 수 없다. 편집기에서 전송 필드로 값을 옮기는 코드와 최종 선택 집합을 확인한 뒤, 독립적인 쓰기 의도·검토값·DB revision·계정·연결·상속 재검증이 필요하다. G마켓 상품의 수정 화면 식별과 동일 계약은 추가 확인 대상이다.
