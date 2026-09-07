# 쿠팡·카페24 정기 연결 상태 확인 확대

스마트스토어에만 고정되어 있던 정기 접수를 스마트스토어·쿠팡·카페24 본상품의 독립 회차로 나눴다. 각 마켓은 매일 한국시간 03:00 이후 기존 영속 조회 큐에 접수한다. 기존 선택 조회 어댑터를 사용하며 상품 쓰기·재등록·판매 재개 API를 호출하지 않는다. 기존 SMART_STORE 회차의 날짜·계정·진행 위치·이력은 보존한다.

마켓마다 시작 시점 등록 ID 상한과 cursor, 계정 참조, 대기 20,000건 제한을 유지한다. 하나의 마켓에서 계정 변경·큐 포화·접수 실패가 발생해도 다른 마켓의 접수를 막지 않는다. 회차·배치·상품 작업·cursor는 같은 트랜잭션이며 실패하면 함께 되돌린다. 진행 중 회차의 계정이 바뀌면 그 회차를 보류한다. 처리 완료가 모든 상품의 정상 판매나 필드 동기화 성공을 뜻하지 않는다.

## 계정과 삭제 판정

사용자의 Q24는 스마트스토어 한 계정 확인 근거다. 쿠팡·카페24의 과거 상품 귀속을 자동 확인한 것으로 확대하지 않았다. 이 두 마켓은 현재 자격 정보로 명시 상태를 읽되 일반 부재/404만으로 연결을 삭제하지 않는다. `accountVerified=false`는 과거 귀속 미확인이며, 정상적인 현재 계정의 명시 상태 조회 자체를 차단하지 않는다.

쿠팡은 [공식 상품 조회](https://developers.coupang.com/ko/api/products/querying-product)의 sellerProductId·vendorId와 statusName을 사용한다. 문서의 등록 상태와 개별 옵션의 판매 상태를 구분하며, 명시적인 상품 삭제 상태만 삭제 근거로 삼는다. 기존 쿠팡 inspectListing 구현을 재사용했다.

카페24는 [공식 상품 리소스 조회](https://developers.cafe24.com/docs-new/docs/admin/get-products-by-product-no)와 프로젝트에 수집한 `/private/tmp/sbshop-cafe24-product-properties.json`의 shop_no/product_no/selling 계약을 사용한다. selling=F는 중지 설정이며 일시 품절·규정 위반을 임의 추론하지 않고 연결을 유지한다. 카페24 본상품 조회를 마켓플러스·G마켓·옥션 상태로 복제하지 않는다. Q26 과거 계정 귀속 확인은 별도 미완료 사항이다.

## API·화면·DDL

기존 `GET /api/v1/products/connection-inspections/daily`는 스마트스토어 응답을 유지한다. 새 `GET .../daily/markets`는 `[{market,status:DailyStatus}]`를 제공한다. 세부 배치는 기존 `GET .../daily/{sweepId}/batches`를 사용한다. ProductInspectionJobs의 마켓별 정기 버튼으로 각 회차와 배치를 구분해 연다. 조회 오류 시 이전 일정·결과를 숨기고 재시도를 표시한다.

`backend/docs/ddl/2026-09-08-market-inspection-daily-multiple-markets.sql`을 기존 daily DDL 다음 적용한다. market 열 기본값은 SMART_STORE이며 날짜 단독 unique를 (market,run_date) unique로 전환한다. 재적용 시 기존 cursor와 계정 값을 보존한다.

## 검증

- Core 통합 24건(기존 18 + 신규 6), API 3건, PostgreSQL 16 실제 DDL migration 1건: 총 28건 통과.
- 새 회귀: 같은 날 세 마켓 접수·중복 방지, Q24 근거 미확대, 쿠팡 404 연결 유지, 명시 삭제 해제, Cafe24 중지 유지, 계정 변경 격리, 마켓별 포화 제한, 접수 rollback.
- 독립 Chrome + 실제 React fixture 4흐름 통과: 마켓별 cursor/배치 전환, 계정 미확인 안내, 오류 시 오래된 표시 제거, GET 전용 재조회·복구. 운영 계정·사용자 브라우저 탭을 사용하지 않았다.
- fixture: `frontend/tests/marketDailyInspection.browser.tsx`; 실행: `node tests/run-productBulkValues-browser.mjs marketDailyInspection.browser.tsx`.
