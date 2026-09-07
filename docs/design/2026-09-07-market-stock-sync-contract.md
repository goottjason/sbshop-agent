# 검토형 판매용 수량 동기화 — 2026-09-07

## 구현 범위

`/api/v1/market-stock-sync`는 쿠팡과 Cafe24 **본상품의 판매용 수량만** 검토·접수·재조회한다. 가격 queue/성공 상태와 분리한 `sb_market_stock_review`, `sb_market_stock_task`, `sb_market_stock_attempt`를 사용한다. Cafe24를 경유한 G마켓·옥션의 전달 성공, 다른 상품 필드의 일치, 상품 전체 동기화를 뜻하지 않는다.

| 구분 | 목표/동작 | 보류 조건 |
|---|---|---|
| 소싱처 재고 있음 | `Product.salesQuantity` 전송, 기본 300, 범위 0~999999 | 판매용 설정 수량 누락/범위 초과 |
| 소싱처 명시 품절 | 목표 0을 검토하고 수량만 전송 | 판매 상태를 확인할 수 없거나 이미 판매정지/금지 |
| 소싱처 수집 실패/소실/재고 상태 불명 | 전송 후보에서 제외, 사유 표시 | 임의 품절이나 임의 0으로 바꾸지 않음 |
| 원재고 `logisticsInfo.stock` | 그대로 보존 | 마켓 판매수량의 전송 값으로 사용하지 않음 |
| 쿠팡 단독 활성 연결의 판매용 수량 편집 | 개별/일괄 편집 정책에서 허용, 저장 후 영속 수량 queue 자동 접수 | 다른 활성 마켓, 옵션/상품번호 불명, 등록 결과 확인 중 |
| Cafe24/다른 마켓이 연결된 판매용 수량 편집 | 기존 검증 필요 정책 유지 | 해당 마켓 전체 반영 가능 조건 미확정 |

## 확인한 API 계약

| 마켓 | 조회 증거 | 전송 계약과 보호 |
|---|---|---|
| 쿠팡 | seller-products 응답의 sellerProductId, vendorId, 정확한 단일 items.vendorItemId 및 externalVendorSku=SB. inventories 응답의 sellerItemId, amountInStock(0 이상의 정수), onSale(boolean). 승인완료 및 onSale=true만 허용. | `PUT /v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/quantities/{quantity}`, 요청 body 없음. 판매 재개/정지 API를 호출하지 않음. |
| Cafe24 본상품 | shop_no=1, product_no/product_code/custom_product_code=SB, 검증된 단일 variant_code 및 inventory.quantity. 상품/품목 selling=T, use_inventory=T, display_soldout=T 확인. | `PUT /admin/products/{product_no}/variants/{variant_code}/inventories`, body `{"shop_no":1,"request":{"quantity":300}}`. market_sync=F만 허용. 판매/재고 관리 설정 및 market_sync를 변경하지 않음. |

공식 1차 출처:

- 쿠팡 [옵션별 수량 변경](https://developers.coupang.com/ko/api/products/changing-quantity-of-each-product-item), [옵션별 수량/가격/상태 조회](https://developers.coupang.com/ko/api/products/query-quantitypricestatus-by-product-items).
- Cafe24 [품목 재고 수정](https://apidocs.cafe24.com/docs/admin/put-products-by-product-no-variants-by-variant-code-inventories), [품목 재고 상세 조회](https://apidocs.cafe24.com/docs/admin/get-products-by-product-no-variants-by-variant-code-inventories).
- 상세 필드·라이브 문서 확인은 [Cafe24 계약 기록](2026-09-06-cafe24-live-api-contracts.md), [품목 수량/GTIN 재조회 기록](2026-09-06-cafe24-inventory-and-gtin-verification.md)을 함께 따른다. 공개 MarketPlus API가 있다고 가정하지 않았다.

## API/DTO

- `POST /reviews`: `{productIds: number[], markets: ["COUPANG", "CAFE24"]}`. 상품 1~500개, 중복 제거/정렬.
- `POST /reviews/{id}/commit`: 검토 작성자만 접수, 30분 만료, 같은 ID 재시도는 같은 결과 반환.
- `GET /reviews/{id}`, `GET /reviews`, `GET /tasks/{id}/history`: 작성자 자신의 검토·작업·시도 이력만 조회.
- Review: `id, actor, createdAt, expiresAt, committed, items, total`.
- Item: `id, productId, sbCode, market, listingId, revision, expectedQuantity, observedQuantity, state, detail, writes, reads, nextRunAt, checkedAt`.
- 검토는 `READY`/`SKIPPED`, 접수 후 `CHECK`/`VERIFY`, 결과는 `CONFIRMED_QUANTITY`, `BLOCKED`, `STALE`, `UNKNOWN`, `FAILED_MISMATCH`, `SKIPPED`로 구분한다. 실패 후 최신 값으로 새 검토를 만들어 재시도한다.

## 실행/실패/원자성

1. DB 검토를 고정하고 짧은 트랜잭션으로 접수한다. 상품 revision, 등록 revision, 식별자 JSON, 계정 참조, 목표 수량을 재검사한다. 동일 상품·마켓의 활성 수량 task는 하나만 허용하며 PostgreSQL partial unique index도 적용한다.
2. 기존 가격·연결 점검과 동일한 `{MARKET}_ORIGIN_READ` gate를 잠근다. 180초 분산 lease를 발급하고 READ_STARTED를 저장한 뒤 DB 트랜잭션 밖에서 API를 조회한다.
3. 재전송 전에 항상 실제 수량을 읽는다. 쓰기가 필요하면 adapter가 계정·SB·단일 품목·판매 상태를 다시 확인한다. PUT 직전 callback으로 lease/공유 cooldown/상품·등록 revision을 최종 재검사하고 WRITE_STARTED를 원자적으로 기록한다.
4. 정상 HTTP 응답은 전송 접수일 뿐이다. 별도 GET의 실제 수량이 맞아야 `CONFIRMED_QUANTITY`가 된다. `MarketRegistration.markSynced`를 호출하거나 다른 필드 실패를 지우지 않는다.
5. timeout/5xx/429는 결과를 재조회한 뒤 결정한다. 조회 9회/실제 전송 3회 한도, 지수 지연+작은 jitter, 서버 Retry-After 최대 시각을 보존한다. lease가 만료된 결과는 새 작업을 완료시킬 수 없다. 늦은 429는 새 lease를 뺏지 않고 공유 cooldown만 연장한다. 기존 가격 beginWrite/finish도 이 공유 제한을 지킨다.
6. 명시 4xx(429 제외) 조회 실패는 BLOCKED. 전송이 명시 거절된 경우 `write_rejected`를 영속화하고 먼저 재조회하되 불일치면 추가 PUT 없이 BLOCKED. 값이 이미 맞는 경우에만 수량 일치 확인이 가능하다.
7. 큐 삽입과 review commit, 시도/완료 이력과 저장 변경 target 상태는 각각 같은 짧은 DB 트랜잭션 안에서 처리한다. HTTP 동안 트랜잭션을 유지하지 않는다.

## 저장된 변경의 자동 반영

- 쿠팡 단독 활성 연결에서 편집 승인한 `salesQuantity`(+ 내부 memo) target만 stock dispatcher가 접수한다. 가격 dispatcher는 이 target을 처리하지 않는다.
- 저장 직후부터 자동 접수 전까지 revision/저장 당시 연결 fingerprint/편집 가능 조건이 바뀌면 전송하지 않는다. 새 수량 변경은 이전 진행 작업이 종료된 후 현재 revision으로 접수한다.
- 자동 수량 review의 actor는 원래 편집 작성자이므로 작성자가 실패 사유와 이력을 조회할 수 있다.
- `ProductChangeTarget.stockTaskId`로 별도 연결하고 성공은 `CONFIRMED_QUANTITY`, 오류/변경/보류는 `ACTION_REQUIRED`로 기록한다. 최신 수량 확인으로 이전 수량 target을 대체할 때만 `SUPERSEDED_BY_CURRENT`를 사용한다.
- 가격과 수량 동시 저장은 같은 상품 revision/이력 아래 별도 target snapshot으로 분리한다(`ProductEditService` 통합 변경). 두 queue는 자신의 필드만 확인한다.

## 배포/검증

- 선행 DDL: 기존 상품 변경 이력/target 및 다중 마켓 inspection gate.
- 신규 DDL: `backend/docs/ddl/2026-09-07-market-stock-sync.sql` (3개 queue 테이블, 부분 유일성/조회 index, target.stock_task_id 열/index).
- scheduler 설정: `products.stock-sync.dispatch-ms` 기본 10000, `products.stock-sync.poll-ms` 기본 1000. 기존 `marketInspectionTaskScheduler` 사용.
- 수량/가격/편집/API scoped 회귀 및 최종 PostgreSQL 서비스 검증 결과는 부모 통합 보고에 기록한다. `backend/tools/verify-market-stock-postgres.py`는 localhost에만 노출되는 임시 PostgreSQL 16 DB를 생성해 실제 서비스의 잠금·rollback·재시도 검사를 실행하고 삭제한다.
- 운영 마켓 쓰기·등록·재전송은 이 구현/검증 작업에서 수행하지 않았다.

## 남은 범위

스마트스토어·11번가 수량 단독 계약 및 회귀, Cafe24 market_sync=T 상품의 하위 마켓 전달/확인, 전체 필드 동기화, 전 마켓 대상 수량 편집 정책 개방은 별도 작업이다. 현재 scope는 검토/저장으로 승인된 수량 task의 자동 처리이며, 모든 상품의 수량을 승인 없이 새로 생성해 쓰는 스케줄러가 아니다.
