# API 흐름 2차 분석 — 발견사항 우선순위 체크리스트

> `docs/api-analysis-2nd/` 하위 API 문서를 근거로 **16개 도메인 유닛**이 현재 워킹트리를 재조사해 반환한 발견사항을 우선순위별로 집계한 마스터 체크리스트.
> 각 항목은 근거 파일:라인과 해당 API 문서(endpoint)에 상세가 있다. **이 문서는 진단·기록 전용** — 실제 수정은 `sbshop-normalize` 스킬(재현 테스트 → Red→Green) 소관.
> 🔴/🟠 중 재현 가능한 것은 `docs/normalize/defect-ledger.md` 로 승격 등재 후보.
>
> 생성: 2026-07-17 · 근거: 현재 워킹트리(main)

## 집계 요약

| 심각도 | 건수 |
|--------|:---:|
| 🔴 BUG | 1 |
| 🟠 GAP | 48 |
| 🟡 SMELL | 47 |
| 🔵 NOTE | 61 |
| **합계** | **157** |

- 조사 문서(파일): 56
- 조사 도메인 유닛: 16 (ORDA·ORDB·ORDC·SYNCA·SYNCB·PRODA·PRODB·BATA·BATB·CAFE·CRED·MREG·PSRC·SUP·MISCA·MISCB)

---

## 🔴 P0 — BUG (데이터 정합·기능 오류, 재현 검증 후 최우선 수정)

- [x] **SYNCB-6** ✅해결(D-087, c4c7faa) · @Async 정산 서비스를 감싼 컨트롤러 SUCCESS 기록이 실제 결과와 무관(항상 SUCCESS) — `OrderSyncController.java:202-205` / `CoupangOrderSyncService.java:98` — 서비스가 @Async라 즉시 반환, 컨트롤러 try/catch가 백그라운드 예외를 못 봐 항상 COUPANG_SETTLEMENT_SYNC SUCCESS 기록. 실제 정산 실패가 ActionLog에 안 남아 운영자가 인지 못함(진짜 결과는 SyncStatus에만). 컨트롤러 SUCCESS 기록 제거 또는 완료시점 이관 (문서: coupang-settlement.md — POST /api/v1/orders/sync/coupang/settlement)

---

## 🟠 P1 — GAP (미처리 케이스·검증 누락, 조건부 오동작)

### order (ORDA / ORDB / ORDC)
- [ ] **ORDA-4** · 라인아이템 0건 주문은 all-NEW 수정 가드를 통과해 발주확인 전에도 주소/통관 수정 가능 — `OrderService.java:231-238` — 차단 조건 if(isAllNew && !lineItems.isEmpty())라 라인 0건이면 가드 미적용. confirmOrder는 라인 0건 명시 차단인데 수정 경로는 비대칭. 라인 0건 정책 확정+Red 테스트 (문서: update-order.md — PATCH /api/v1/orders/{id})
- [ ] **ORDB-3** · 일괄확인 건별 실패를 삼켜 재던지지 않아 batch 트랜잭션 rollback-only 마킹 상호작용 불투명 — `OrderService.java:192-216` — bulkConfirmOrders(@Transactional) 내부에서 건별 confirmOrder(@Transactional) 호출 후 예외를 catch만 → REQUIRED 전파에서 rollback-only 마킹 시 batch 커밋이 UnexpectedRollbackException으로 거부, 부분 성공 미반영 가능. REQUIRES_NEW 격리 검토 (문서: confirm-batch.md — POST /orders/confirm/batch)
- [ ] **ORDB-5** · 라인아이템 없는 주문이 cancelOrder 가드를 공허참으로 통과해 no-op 성공, G마켓/옥션은 빈 주문에 취소 API 호출 — `OrderService.java:143-173` — lineItems 비면 allMatch가 true(공허참)로 취소 가드 통과, 전이 0회 200 성공 반환하고 G마켓/옥션 불필요 cancelOrder 호출. confirmOrder의 isEmpty() 차단(F-ORD-22)과 비대칭 — cancelOrder에도 isEmpty() 가드 필요 (문서: cancel-order.md — POST /orders/{id}/cancel)
- [ ] **ORDB-7** · 일괄취소 건별 실패 삼킴 + 마켓 전파 성공 후 batch 롤백 시 마켓/DB 불일치 위험 — `OrderService.java:180-216` — bulkCancelOrders(@Transactional) 내 건별 cancelOrder 예외 catch만 → rollback-only 마킹 시 batch 커밋 거부 가능, cancel은 마켓 취소 전파(156) 나간 뒤라 confirm보다 위험 큼. REQUIRES_NEW 격리/건별 tx 분리·보상 경로 검토 (문서: cancel-batch.md — POST /orders/cancel/batch)
- [ ] **ORDC-1** · 종료상태(CANCELED/RETURNED/EXCHANGED) 라인의 소싱 정보 수정이 차단되지 않음 — `OrderService.java:283-288` — 소싱 상태가드는 null/NEW/UNKNOWN만 차단, 종료상태 통과(배송 경로 :333-336은 차단). 종결 주문 소싱금액·물류비 사후 변경 가능 → 정산 데이터 정합 흔들림. 대칭 가드 추가/정책 명문화 (문서: PATCH /line-items/{lineItemId}/sourcing)
- [ ] **ORDC-4** · DELIVERED(배송완료) 상태 라인의 송장 수정이 진입 상태 가드로 차단되지 않음 — `OrderService.java:333-342` — 차단 목록에 DELIVERED 누락 → DELIVERED 라인도 else 분기로 마켓 전송 시도. 마켓 terminal 판정(문자열 매칭)에 의존해 정합은 대체로 보전되나 완료 후 편집 진입을 진입부에서 걸러내는 편이 명확. 가드 포함 여부 명문화 (문서: PATCH /line-items/{lineItemId}/shipping)
- [ ] **ORDC-7** · 일괄발송에 NEW/PREPARING 진입 가드 없어 구매완료 전 라인도 송장만 있으면 SHIPPED 강제 전이 — `OrderShipProcessor.java:75-83` — 재발송 스킵은 SHIPPED/DELIVERED/종료만 검사, NEW/PREPARING/PURCHASED 발송 대상 통과. 단건 경로(OrderService:339-342)는 NEW/UNKNOWN/PREPARING 차단·PURCHASED에서만 전이 — 비대칭. 구매완료 건너뛴 발송 위험. PURCHASED 진입 가드로 정합화 (문서: POST /orders/ship)

### order-sync (SYNCA / SYNCB)
- [x] **SYNCA-1** ✅해결(D-087, c4c7faa) · 컨트롤러 try/catch·FAILED 기록이 async 예외를 포착 못함(항상 SUCCESS 기록) — `CoupangOrderSyncService.java:56-58` / `OrderSyncController.java:60-79` — 서비스 진입점이 @Async void라 동기화 본문 예외가 별도 스레드에서 발생, 컨트롤러 catch 미도달. 실패해도 COUPANG_SYNC SUCCESS만 남고 record(FAILED)는 데드코드. 로그 기록을 서비스 markCompleted/markFailed 지점으로 이동 (문서: sync-coupang.md — POST /api/v1/orders/sync/coupang)
- [x] **SYNCA-5** ✅해결(D-087, c4c7faa) · 컨트롤러 try/catch·FAILED 기록이 async 예외를 포착 못함(항상 SUCCESS 기록) — `SmartStoreOrderSyncService.java:47-49` / `OrderSyncController.java:90-110` — @Async void 위임이라 동기화 실패가 컨트롤러 catch 미도달, 항상 SMART_STORE_SYNC SUCCESS 기록, record(FAILED) 데드코드. 서비스 본문으로 로그 기록 이동 (문서: sync-smartstore.md — POST /api/v1/orders/sync/smartstore)
- [ ] **SYNCA-6** · 스마트스토어에만 취소감지가 없어 취소 주문이 이전 상태로 잔류 — `SmartStoreOrderSyncService.java:204` — postSyncProcess가 no-op. 쿠팡·11번가와 달리 API 응답에서 사라진 non-terminal 주문을 CANCELED로 전이하지 않아 취소 주문이 NEW/PREPARING으로 영구 잔류. 취소 조회 API 확인 후 11번가 패턴 이식 (문서: sync-smartstore.md — POST /api/v1/orders/sync/smartstore)
- [x] **SYNCA-9** ✅해결(D-087, c4c7faa) · 컨트롤러 try/catch·FAILED 기록이 async 예외를 포착 못함(항상 SUCCESS 기록) — `ElevenstOrderSyncService.java:48-50` / `OrderSyncController.java:120-135` — @Async void 위임이라 실패가 컨트롤러 catch 미도달, 항상 ELEVEN_STREET_SYNC SUCCESS 기록. 쿠팡·스마트스토어·esmplus와 공통 결함으로 4경로 일괄 처리 (문서: sync-elevenstreet.md — POST /api/v1/orders/sync/elevenstreet)
- [x] **SYNCA-13** ✅해결(D-087, c4c7faa) · 컨트롤러 try/catch·FAILED 기록이 async 예외를 포착 못함(항상 SUCCESS 기록) — `Cafe24OrderSyncService.java:59-61` / `OrderSyncController.java:146-161` — @Async void 위임이라 실패가 컨트롤러 catch 미도달, 항상 GMARKET_SYNC SUCCESS 기록. 서비스는 failureReason으로 root cause를 이벤트에 담으나 액션로그는 왜곡됨. 로그 기록 서비스 이동 (문서: sync-esmplus.md — POST /api/v1/orders/sync/esmplus)
- [ ] **SYNCA-14** · items 개수 불일치 시 첫 아이템 상태를 전체 라인에 적용해 라인별 상태 왜곡 — `Cafe24OrderSyncService.java:207-214` — API items와 로컬 lineItems 개수가 다르면 첫 아이템 상태를 모든 라인에 적용(order_item_code 미보존). 상태가 서로 다른 다중 아이템 주문에서 배송/취소 상태 왜곡. 라인 매핑 키 저장·활용 또는 보수적 제한 (문서: sync-esmplus.md — POST /api/v1/orders/sync/esmplus)
- [ ] **SYNCA-15** · G마켓/옥션에 취소감지 경로 없음(API 부재 주문 잔류) — `Cafe24OrderSyncService.java:59-85` — upsert만 하고 종료, 응답 코드에 취소가 실려야만 반영. Cafe24 응답에서 사라진 취소 주문이 이전 상태로 잔류. Cafe24 취소 포함 여부 라이브 확인 후 감지 경로 추가 (문서: sync-esmplus.md — POST /api/v1/orders/sync/esmplus)
- [ ] **SYNCB-1** · 날짜 포맷 불일치: 포트 계약 yyyy-MM-dd HH:mm:ss vs 컨트롤러 yyyy-MM-dd — `OrderSyncController.java:171-172` / `Cafe24OrderApiPort.java:15-16` — 포트 Javadoc은 시각 포함 포맷을 요구하나 컨트롤러는 날짜만 전달해 프리뷰가 보는 주문 범위가 실제 동기화 경로와 불일치 가능. 진단 도구로서 오해 유발. 포맷 정합화/Javadoc 정정 (문서: cafe24-preview.md — POST /api/v1/orders/sync/cafe24/preview)
- [ ] **SYNCB-7** · 정산 서비스가 예외를 삼켜 @Async 실패가 어디에도 전파 안 됨 — `CoupangOrderSyncService.java:168-172` — catch 후 markFailed만 하고 rethrow 없음(syncCoupangOrders와 달리). 워커 스케줄러(cron 0 0 2)로 실행돼도 실패가 스케줄러 로그에 안 잡힘. rethrow 일관화 또는 markFailed 정본화 명문화 (문서: coupang-settlement.md — POST /api/v1/orders/sync/coupang/settlement)
- [ ] **SYNCB-10** · 통관번호 유무 필터가 상태 쿼리에 없음(주석-코드 불일치) — `CustomsOrderSyncService.java:29,48-53` — 주석은 통관번호 없으면 스킵이라 하나 실제 쿼리는 상태만 필터링해 통관번호 빈 PENDING 주문도 verifyBulk 대상 포함. 무의미한 외부 호출·배치 팽창. 쿼리에 non-blank 조건 추가 (문서: customs.md — POST /api/v1/orders/sync/customs)
- [ ] **SYNCB-11** · markFailed(REQUIRES_NEW)와 배치 커밋 사이 상태-데이터 정합 취약 — `CustomsOrderSyncService.java:68-92` — 중간 배치 실패 시 markFailed+rethrow하나 그 전 커밋된 배치는 갱신된 채 남아 상태=FAILED인데 데이터 일부 갱신된 혼합. /status가 부분 성공을 완전 실패로 오인 표현. 처리 건수/실패 배치 표면화 (문서: customs.md — POST /api/v1/orders/sync/customs)

### product (PRODA / PRODB)
- [ ] **PRODA-3** · 전체수정은 자사 DB만 갱신하고 연동 마켓에 전파하지 않음(가격/이미지 경로와 비대칭) — `ProductManageUseCase.java:169-176` — updateProduct는 product.update+save만 하고 republishToMarkets/syncPriceStock 미호출. salePrice·name·detailHtml을 이 경로로 바꿔도 마켓 리스팅 미반영 → 자사 DB와 마켓 표시 괴리. 마켓 재게시 트리거 또는 '자사 DB 전용' 명시(정책 결정) (문서: update-product.md — PUT /api/v1/products/{id})
- [ ] **PRODA-6** · 마켓 삭제 실패해도 등록행이 즉시 삭제돼 실패 응답 유실 시 고아 리스팅 추적 불가 — `ProductManageUseCase.java:220-228` — 마켓 삭제 실패를 failed로만 수집하고 직후 deleteWithRegistrations가 실패 마켓 등록행까지 무조건 삭제(ProductDeleteTxService.java:40-42). 클라이언트가 200의 failed 유실 시 마켓엔 리스팅, 자사엔 등록행 없는 고아 발생. 실패분 등록행 보존 또는 정리대기 상태 이관 (문서: delete-product.md — DELETE /api/v1/products/{id})
- [ ] **PRODA-7** · 삭제 식별자(extractDeleteCode)가 null이어도 deleteFromMarket(null)을 그대로 호출 — `ProductManageUseCase.java:206-216` — extractDeleteCode()가 null이면 marketItemIds 미기록하면서도 hasClient만 통과하면 deleteFromMarket(null) 호출. republish 경로(:130-132)는 코드 null 시 IllegalStateException 차단과 비대칭. 잘못된 삭제/모호한 오류 위험. 코드 null이면 스킵하고 failed로 수집 (문서: delete-product.md — DELETE /api/v1/products/{id})
- [ ] **PRODB-4** · multipart 전량 리사이즈 실패(성공 0장)도 예외 없이 빈 업로드로 진행 — `ProductController.java:438-465,233-248` / `ProductManageUseCase.java:88` — prepareImageFiles 전량 실패 시 succeeded=[] 반환, 빈 리스트로 R2/HTML 치환 진행 → 200 OK 반환·HTML SKU 이미지 빈 목록 치환으로 유실 우려. by-url 빈목록 400 거부와 비대칭. succeeded 비고 failed 존재 시 거부 가드 필요 (문서: update-images.md — PUT /{id}/images)
- [ ] **PRODB-7** · by-url 전량 다운로드 실패(성공 0장)도 빈 업로드로 진행 — `ProductController.java:152-158` / `ImageDownloadService.java:43-77` — 진입부는 빈 URL목록만 400 거부. downloadAndConvertDetailed는 전량 실패 시 succeeded=[] 반환 → 200 OK로 빈 목록 HTML 치환, SKU 이미지 유실 우려. PRODB-4와 동일 근원. 422 거부 가드 필요 (문서: update-images-by-url.md — PUT /{id}/images/by-url)
- [ ] **PRODB-12** · crawl-and-upload 빈-결과 조기반환이 storageUpdated=true로 응답해 저장됨 오해 — `ProductController.java:200-201,207-208` / `ImageUploadResponse.java:40-42,49-62` — 소싱 URL 미등록·크롤 0개 경로가 ImageUploadResponse.from(...)로 응답하는데 이 팩토리는 storageUpdated를 항상 true로 고정. 실제 R2/DB 저장 안 했음에도 storageUpdated=true → 프론트 '저장 완료' 오표시. 빈-결과에 false/별도 상태 필요 (문서: crawl-and-upload-images.md — POST /{id}/images/crawl-and-upload)

### batch (BATA / BATB)
- [ ] **BATA-1** · markSuccess 이후 Thread.sleep 인터럽트가 이미 성공한 행을 FAILED로 뒤집음 — `BatchPriceStockService.java:92-102` — markSuccess로 SUCCESS 기록 뒤 try 블록 안 Thread.sleep(500)에서 InterruptedException/executor shutdown 발생 시 catch로 떨어져 같은 productCode 행을 markFailed로 덮어씀. 실제 갱신·마켓전송은 성공했는데 진행현황만 FAILED로 뒤집힘. sleep을 성공기록 이전 또는 try 밖으로 분리 (문서: crawl-and-update.md — POST /crawl-and-update)
- [ ] **BATA-2** · 중복 productId 입력 시 일부 행이 PENDING에 영구 잔류 — `ProcessStatusService.java:54-64,91-95` — startBatch는 productCode별 행을 시딩하나 updateStep이 filter().findFirst()로 한 행만 갱신. 중복 id가 있으면 나머지 행이 PENDING으로 남아 getBatchSummary가 100%에 도달 못하고 폴링 무한 진행중. 진입부 distinct 또는 상태갱신 키 정합화 (문서: crawl-and-update.md — POST /crawl-and-update)
- [ ] **BATA-5** · 빈/누락 items를 400으로 거부하지 않아 폴링 불가한 batchId 반환 — `BatchController.java:86` — items null이면 빈 리스트로 대체하고 empty 400 가드 없음(crawl/manual-update-all과 비대칭). 빈 리스트 startBatch → PENDING 0행 시딩 → 200 batchId 반환되나 /status·/summary에서 total==0으로 404. items null/empty 400 거부 (문서: manual-update-price-stock.md — POST /manual-update-price-stock)
- [ ] **BATA-7** · 중복 productId 시 일부 행 PENDING 잔류(공통 구조) — `ProcessStatusService.java:91-95` — updateStep의 findFirst가 중복 productCode 행 중 하나만 갱신. items에 같은 productId 2회면 나머지 행 PENDING 잔류로 summary 미완료. 진입부 distinct 또는 키 정합화 (문서: manual-update-price-stock.md — POST /manual-update-price-stock)
- [ ] **BATA-8** · 빈 리스트(productIds=[],commands=[])는 size 일치라 400 통과해 폴링 불가 batchId 반환 — `BatchController.java:104-110` — 가드가 null·size 불일치만 막고 0==0 빈 리스트는 통과. startBatch 시딩 0행 → 200 batchId 반환되나 /status·/summary에서 total==0 404. productIds.isEmpty()도 400으로 거부(다른 트리거와 정합) (문서: manual-update-all.md — POST /manual-update-all)
- [ ] **BATA-11** · 중복 productId 시 일부 행 PENDING 잔류(공통 구조) — `ProcessStatusService.java:91-95` — updateStep findFirst가 중복 productCode 행 중 하나만 갱신 → 나머지 PENDING. summary 100% 미달로 폴링 무한 진행중. distinct 또는 키 정합화 (문서: manual-update-all.md — POST /manual-update-all)
- [ ] **BATA-14** · 대상 선정 후 배치 시작 사이 상품 삭제/변경에 대한 원자성 부재 — `BatchPriceStockService.java:188-192` — getProductIdsByVendor로 id 목록 조회 후 별도 startBatch·@Async 크롤 진행. 시차 중 상품 삭제되면 findById orElseThrow→markFailed. count 응답값(선정 시점)과 실제 처리 대상 수 어긋날 수 있음. 시차 FAILED 사유 구분 또는 count가 스냅샷임을 명시 (문서: by-supplier.md — POST /by-supplier)

### product-sourcing (PSRC)
- [ ] **PSRC-1** · crawlProducts 도중 InterruptedException 시 남은 URL이 succeeded/failed 어디에도 안 담기고 조용히 누락 — `IherbScraperClient.java:239-241` — Thread.sleep 인터럽트 시 break로 즉시 종료해 미처리 URL이 응답·활동로그 총량에서 유실. 요청↔결과 정합 붕괴. break 시 남은 URL을 failed로 채워 총량 정합 보장 (문서: POST /api/v1/sourcing/iherb)
- [ ] **PSRC-4** · succeeded에 담긴 항목도 saveAll 전건 실패 시 전부 롤백되어 응답 succeeded와 DB 상태가 어긋남 — `ProductCreateUseCase.java:75-84` — 단일 @Transactional saveAll이 하나라도 실패하면 정상항목까지 롤백, R2 이미지는 고아로 잔존. 부분 커밋 미지원. 건별 저장 격리 또는 계약 명시 (문서: POST /api/v1/products/bulk)
- [ ] **PSRC-7** · 재게시 시 이미 SYNCED인 등록행에도 client.publish()를 무조건 재호출(멱등 아님, 마켓 중복 등록 위험) — `ProductPublishUseCase.java:59-63` — savePending은 멱등이나 publish는 등록상태 무관하게 항상 호출되어 SYNCED 상품 재게시 시 마켓 중복 등록/identifiers 덮어쓰기 가능. 코드 주석도 범위 밖 인정. SYNCED시 update 분기 (문서: POST /api/v1/products/{id}/markets/{marketType})

### supplier (SUP)
- [ ] **SUP-2** · createSupplier 요청 바디 @Valid/null 방어 부재로 NPE 위험 — `SupplierController.java:42-53` — @RequestBody에 @Valid/required 없음 → 바디 누락 요청은 request가 null이 되어 request.supplierCode()(L47)에서 NPE→500, catch의 request.supplierCode()(L53)도 재-NPE로 원 예외 가림. @RequestBody(required=true) 또는 진입부 null 가드 (문서: create-supplier.md — POST /api/v1/suppliers)
- [ ] **SUP-5** · 엔드포인트명은 upsert이나 실제는 create-only, 환율 갱신 경로 부재 — `SupplierService.java:65-68` — existsById면 IllegalStateException으로 거부(기존 환율 불변)하며 PUT/PATCH 등 환율 수정 엔드포인트가 코드베이스에 없음. 환율 변동 시 API로 갱신 불가→DB 수동 수정 의존, 정산/매입원가 오차 위험. 환율 갱신 엔드포인트 추가 또는 upsert 전환 (문서: upsert-currency.md — POST /api/v1/currencies)
- [ ] **SUP-6** · createCurrency 요청 바디 @Valid/null 방어 부재로 NPE 위험 — `SupplierController.java:64-75` — @RequestBody에 @Valid/required 없음 → 바디 누락 시 request null로 request.currencyCode()(L69) NPE→500, catch(L75)도 재-NPE. createSupplier(SUP-2)와 동일 패턴, 함께 일괄 방어 (문서: upsert-currency.md — POST /api/v1/currencies)

### market-credential (CRED)
- [ ] **CRED-4** · clientId·redirectUri는 빈 값 제출 시 무조건 덮어써 기존값 소거(시크릿과 비대칭) — `MarketCredentialService.java:42-52` — accessKey·secretKey는 isPresent 가드로 빈값이면 보존하는데 clientId·redirectUri는 조건없이 반영해, 부분 폼 제출 시 기존 Vendor ID·리다이렉트가 빈 문자열로 소거. 필드 간 갱신 규칙 비대칭 — 보존정책 통일 또는 전체교체 계약 명문화 (문서: save-credential.md — PUT /api/v1/market-credentials/{marketType})

### cafe24-auth (CAFE)
- [ ] **CAFE-1** · 상태 점검 주문 조회에 포트 계약과 다른 날짜 포맷(yyyy-MM-dd) 전달 — `Cafe24AuthController.java:66-68` — status()는 ofPattern("yyyy-MM-dd")로 fetchOrders 호출하나 포트 계약(Cafe24OrderApiPort.java:15-16)은 "yyyy-MM-dd HH:mm:ss" 규정. Cafe24가 시각 없는 날짜를 거부/오해석하면 인증 정상인데도 주문 점검이 Cafe24StatusCheckException 500 되거나 정상 연동이 '점검 실패'로 표시. 포트 계약대로 포맷 정합화 또는 계약 완화 (문서: GET /api/admin/sync/cafe24/status)
- [ ] **CAFE-6** · 콜백에 blank-code 가드 부재 — 빈 code로 무의미한 교환 시도 — `Cafe24AuthController.java:198-208` — issue-token은 blank code면 400 조기 반환하나 handleCafe24AuthCode는 가드 없이 exchangeAuthorizationCode 진행. @RequestParam은 부재만 400이라 ?code=(빈값)/공백은 통과해 issueInitialToken("")까지 도달, 불필요 OAuth 교환 후 실패를 500 노출. 콜백에 동일 가드 추가 또는 공유 메서드에서 blank 시 IllegalArgumentException(→400) (문서: GET /api/admin/sync/cafe24/auth/callback)

### market-registration (MREG)
- [ ] **MREG-2** · 등록 정보 없음을 404가 아닌 400(IllegalArgumentException)으로 반환 — `MarketRegistrationService.java:35-37` — 등록행 부재 시 IllegalArgumentException→400 매핑되어 리소스 부재를 입력오류로 표현하고, 잘못된 marketType(진짜 입력오류)과 미등록을 프론트가 구분 불가. 미등록은 ResourceNotFoundException(404), 파싱실패는 400으로 분리 (문서: get-local.md — GET /api/v1/products/{productId}/markets/{marketType}/local)
- [ ] **MREG-4** · vendorItemId 부재 시 productId(내부 PK)를 마켓 상품 식별자로 폴백 — `MarketRegistrationService.java:46-49` — extractVendorItemId는 쿠팡 전용 키만 읽어 스토어/11번가/카페24/ESM+는 항상 폴백, 폴백 값이 내부 PK라 마켓 API가 엉뚱한 상품 조회/오류(500). 도메인 extractMarketCode(마켓별 실제 식별자)로 폴백 교체하고 없으면 명시 실패 (문서: sync-market.md — POST /api/v1/products/{productId}/markets/{marketType}/sync)

### misc (MISCA / MISCB)
- [ ] **MISCA-4** · 프론트가 쓰는 다른 enum들이 공통 코드에 미포함 — `CommonCodeController.java:30-33` — marketType/shippingStatus/customsStatus/recordStatus 4종만 노출. StockStatus·ActionStatus 등 EnumMapperType 구현 enum이 빠져 프론트가 라벨을 하드코딩하면 서버-프론트 드리프트. 누락분 추가 또는 자동 수집 (문서: GET /api/v1/common/codes)
- [ ] **MISCA-7** · 재고 동기화 트리거 중복 실행 방지(멱등/락) 없음 — `ProductSyncController.java:35-45` — 가드 통과 즉시 @Async 디스패치만 하고 진행 중 여부 확인 없음. 연타 시 동일 상품군 중복 크롤(소싱 rate-limit 위반)·큐 적체. DB advisory lock 또는 진행 플래그로 멱등화(409) (문서: POST /api/v1/products/sync/stock)
- [ ] **MISCA-9** · 이중 @Transactional 자기호출로 건별 격리 무효화·긴 트랜잭션 — `ProductSyncService.java:114-137` — @Transactional syncStockForPreparingOrders가 같은 빈의 @Transactional syncProductStock를 자기호출(L126)해 프록시 우회로 건별 트랜잭션 병합, sleep(500)×상품수 동안 커넥션 장시간 점유. 별도 빈/셀프프록시로 건별 격리, sleep은 tx 밖 이동 (문서: POST /api/v1/products/sync/stock)
- [ ] **MISCB-1** · emitters 목록이 프로세스 로컬이라 2 JVM(api/worker) 토폴로지에서 이벤트가 JVM 경계에 갇힘 — `SseNotificationController.java:21,63-83` — emitters는 api JVM 인메모리이고 @EventListener는 동일 JVM 이벤트만 수신. 스케줄러(worker JVM)가 주도한 SYNC/BATCH 완료 이벤트가 api JVM SSE 구독자에게 미도달. Redis pub/sub 등 공유 채널 중계 또는 'SSE는 api-트리거 한정' 설계 명시 (문서: subscribe.md — GET /api/v1/notifications/subscribe)
- [ ] **MISCB-5** · 재진입 스킵(executed=false)인데도 SyncStatus를 COMPLETED로 덮어씀 — `EmailFetchController.java:50-55` — fetchAndProcessEmails가 재진입 가드로 false(스킵) 반환해도 markCompleted(EMAIL) 호출해, 진행 중인 다른 실행이 RUNNING인데도 상태를 조기 COMPLETED로 전환하고 lastSyncAt 갱신. /orders/sync/status 오표시. tryMarkRunning 클레임 성공 시에만 완료/실패 기록하도록 정합화 (문서: fetch.md — POST /internal/email/fetch)
- [ ] **MISCB-6** · 단일 @Transactional 안에서 장시간 IMAP·마켓 외부호출 수행 — 커넥션 점유·DB/마켓 불일치 창 — `EmailFetcherService.java:59,125-126,234` — fetchAndProcessEmails가 @Transactional 하위에서 계정별 IMAP(최대 40s)과 마켓 shipOrder를 다중 루프 수행. DB 커넥션 장시간 점유, 후반 예외 시 앞서 저장한 SHIPPED 전이가 롤백되나 마켓엔 이미 송장 나가 불일치. 트랜잭션을 아이템 단위로 좁히고 IMAP 수집은 tx 밖으로 (문서: fetch.md — POST /internal/email/fetch)

---

## 🟡 P2 — SMELL (동작하나 유지보수 위험: 중복·죽은코드·책임배치)

### order (ORDA / ORDB / ORDC)
- [ ] **ORDA-1** · 배송상태 필터와 키워드 검색이 독립 exists 서브쿼리라 조합 필터 시 라인 경계가 어긋날 수 있음 — `OrderRepositoryImpl.java:139-186` — shippingStatusIn과 keywordContains가 각각 별개 exists 서브쿼리라 다품목 주문에서 서로 다른 라인에서 충족돼도 통과 → 과도포함. 동일 라인 결합 여부를 명세로 확정하거나 문서화 (문서: get-orders.md — GET /api/v1/orders)
- [ ] **ORDA-5** · 주소/통관 수정 실패 활동로그의 marketType이 항상 null(confirm/cancel과 비대칭) — `OrderController.java:204` — catch에서 record(ORDER_UPDATE, null, FAILED)로 마켓 고정. confirm/cancel 실패 경로는 marketNameOfOrder(id) 재조회로 마켓 채움. 실패 로그 마켓 식별 불가. 동일 재조회로 정합화 (문서: update-order.md — PATCH /api/v1/orders/{id})
- [ ] **ORDB-1** · confirmOrder 상태 가드(anyMatch 진행/종료 차단)와 전이(NEW 라인만)의 혼재 주문 처리 비대칭 — `OrderService.java:80-119` — hasProgressedOrEnded는 null 상태 라인을 통과시키지만(82-84) 전이 루프는 NEW 라인만 PREPARING으로 바꿔(111), NEW+null 혼재 주문은 마켓 접수가 나가도 null 라인이 갱신되지 않아 유실 가능. null 상태 라인 처리 정책 명시 및 접수/전이 대상 집합 일치 필요 (문서: confirm-order.md — POST /orders/{id}/confirm)
- [ ] **ORDB-6** · 마켓 취소 전파 후 로컬 CANCELED 저장 — 저장 실패 시 마켓/DB 불일치 창 존재 — `OrderService.java:153-173` — cancelOrderToMarketplace(156) 성공 후 로컬 저장 루프(164-173)에서 예외 시 마켓엔 취소 반영됐는데 로컬은 롤백되는 창 존재. 정합 실패 시나리오 문서화 및 재동기화 복원 여부 확인 (문서: cancel-order.md — POST /orders/{id}/cancel)
- [ ] **ORDB-8** · 라인아이템 없는 주문이 batch에서 성공 집계되고 G마켓/옥션은 취소 API 호출(ORDB-5 상속) — `OrderService.java:199-208` — batch가 건별 cancelOrder에 위임하므로 빈 주문 공허참 통과(ORDB-5)가 상속돼 successCount++ 집계되고 마켓 API 호출. successCount 과대 인식 — 단건 isEmpty() 가드 수정 시 함께 해소 (문서: cancel-batch.md — POST /orders/cancel/batch)
- [ ] **ORDC-3** · 소싱 수정 실패 경로 활동로그의 마켓 타입이 항상 null — `OrderController.java:250-252` — catch에서 record(PURCHASE_UPDATE, null, FAILED). 성공 경로만 marketNameOfLineItem 해석. 실패 로그 마켓별 집계 불가(발주확인/취소 실패는 marketNameOfOrder로 채우는 것과 비대칭). 실패 경로도 read-only 조회로 마켓 해석 (문서: PATCH /line-items/{lineItemId}/sourcing)
- [ ] **ORDC-6** · 배송 수정 실패 경로 활동로그의 마켓 타입이 항상 null — `OrderController.java:287-289` — catch에서 record(SHIPPING_UPDATE, null, FAILED). 성공 경로만 marketNameOfLineItem 해석. 소싱 경로(ORDC-3)와 동일 패턴으로 실패 로그 마켓별 집계 불가. 실패 경로도 마켓 해석 시도 (문서: PATCH /line-items/{lineItemId}/shipping)
- [ ] **ORDC-8** · 일괄발송은 shipOrder(최초등록)만 호출, invoiceAlreadyExists 분기·terminal 분류 우회로 단건 경로와 이원화 — `OrderShipProcessor.java:89-91` — getPort().shipOrder 직접 호출로 MarketplaceShippingService의 updateTracking/shipOrder 분기·terminal 분류 우회. 마켓에 이미 송장 존재 시(동기화 지연) 거부 가능. sendTrackingToMarketplace로 전송 위임 또는 정책 차이 문서화 (문서: POST /orders/ship)

### order-sync (SYNCA / SYNCB)
- [ ] **SYNCA-2** · 장시간 외부 동기화 전체가 단일 @Transactional 경계 — `CoupangOrderSyncService.java:57,72-77` — fetchOrders(외부 API)+전체 upsert+postSyncProcess를 하나의 트랜잭션이 감싸 커넥션 장기 점유·후반 예외 시 전체 롤백(부분성공 확정 불가). 배치 단위 트랜잭션 분리 (문서: sync-coupang.md — POST /api/v1/orders/sync/coupang)
- [ ] **SYNCA-7** · 트래킹번호 마켓전송 보존 가드가 스마트스토어엔 없음(쿠팡과 비대칭) — `SmartStoreOrderSyncService.java:119-124` — 쿠팡은 trackingSentToMarket!=true면 송장을 API값으로 안 덮지만 스마트스토어는 무조건 반영. 마켓 미전송 로컬 송장 유실 가능. write-path 확인 후 정합화 (문서: sync-smartstore.md — POST /api/v1/orders/sync/smartstore)
- [ ] **SYNCA-8** · 장시간 외부 동기화 단일 @Transactional + in-JVM 중복가드 — `SmartStoreOrderSyncService.java:48,60,45` — 외부 fetchOrders와 전체 upsert가 단일 트랜잭션(후반 예외 시 전체 롤백)이며, 중복가드 AtomicBoolean은 워커 스케줄러와 교차 JVM 동시실행을 못 막음. 트랜잭션 분리·교차 JVM 가드 통일 (문서: sync-smartstore.md — POST /api/v1/orders/sync/smartstore)
- [ ] **SYNCA-10** · 취소감지가 마켓별 3중 구현(어댑터 vs 서비스 내장)으로 분산 — `ElevenstOrderSyncService.java:228-282` — 취소감지가 11번가는 서비스 내장, 쿠팡은 어댑터, 스마트스토어는 없음으로 3원화되고 terminal 제외 집합도 별개 존재. 공통 헬퍼로 추출해 terminal 집합 단일 원천화 (문서: sync-elevenstreet.md — POST /api/v1/orders/sync/elevenstreet)
- [ ] **SYNCA-11** · 장시간 외부 동기화 단일 @Transactional + in-JVM 중복가드 — `ElevenstOrderSyncService.java:49,61,64-65,46` — fetchOrders+upsert+detectCancellations가 단일 트랜잭션(후반 예외 시 전체 롤백)이며 AtomicBoolean 중복가드는 워커와 교차 JVM 동시실행을 못 막음. 트랜잭션 분리·가드 통일 (문서: sync-elevenstreet.md — POST /api/v1/orders/sync/elevenstreet)
- [ ] **SYNCA-16** · 전체 페이지네이션(최대 15000건) + 외부 API 왕복이 단일 @Transactional — `Cafe24OrderSyncService.java:60,101-123` — syncCafe24Orders와 fetchAndPersist가 하나의 트랜잭션을 이루고 최대 150페이지 API 왕복+저장 반복해 커넥션 장기 점유·후반 예외 시 전 페이지 롤백. 페이지 단위 트랜잭션 분리 (문서: sync-esmplus.md — POST /api/v1/orders/sync/esmplus)
- [ ] **SYNCB-4** · 택배사 조회 실패 로그가 '주문 프리뷰 실패'로 오기 — `OrderSyncController.java:187` — previewCafe24Carriers의 실패 로그가 previewCafe24Orders와 동일한 '주문 프리뷰 실패' 문구를 재사용해 운영 로그에서 어느 진단 호출이 실패했는지 구분 불가. 문구 분리 (문서: cafe24-carriers.md — POST /api/v1/orders/sync/cafe24/carriers)
- [ ] **SYNCB-8** · 정산 조회 범위 now-31~now-1 하드코딩·파라미터 불가 — `CoupangOrderSyncService.java:113-114` — 조회 창 고정으로 늦게 확정되는 정산·누락분 소급 반영 불가. 기간을 선택적 파라미터로 노출 (문서: coupang-settlement.md — POST /api/v1/orders/sync/coupang/settlement)
- [ ] **SYNCB-9** · 전 쿠팡 주문 풀스캔(N+1) 후 라인별 개별 save — `CoupangOrderSyncService.java:130-163` — findByMarketType로 전 주문 로드+주문/상품마다 개별 조회+변경 라인 개별 save를 단일 @Transactional 안에서 수행해 커넥션·락 장기 점유(통관 배치 분리와 대조). DELIVERED 라인 쿼리·saveAll·배치 분리 검토 (문서: coupang-settlement.md — POST /api/v1/orders/sync/coupang/settlement)
- [ ] **SYNCB-12** · 배치 크기·딜레이·검증 대상 상태 집합 하드코딩 — `CustomsOrderSyncService.java:17-19,49-53` — 배치 30·딜레이 1000ms·대상 상태 리스트가 코드 고정으로 부하 튜닝 불가·새 INVALID 상태 추가 시 누락 위험. 설정 외부화·enum 헬퍼 중앙화 (문서: customs.md — POST /api/v1/orders/sync/customs)

### product (PRODA / PRODB)
- [ ] **PRODA-4** · stock 수정 시 stockStatus(품절/판매중) 정합이 갱신되지 않음 — `Product.java:214-228` — Product.update는 logisticsInfo.stock만 병합하고 stockStatus는 미변경. stock=0으로 수정해도 IN_STOCK 유지 등 재고표시와 어긋날 수 있음. 재고상태 재계산 정책 명확화 또는 책임경계 문서화 (문서: update-product.md — PUT /api/v1/products/{id})
- [ ] **PRODA-8** · 마켓 리스팅 삭제 순회 로직이 republishToMarkets와 형태 거의 동일(수집 구조 중복) — `ProductManageUseCase.java:203-225` — deleteProduct(:203-225)와 republishToMarkets(:115-155)가 '등록행 순회→hasClient 스킵→try 마켓호출→3버킷 수집→로그' 구조 중복. 정책 변경 시 누락 위험. best-effort 3버킷 수집 패턴 공통 헬퍼 추출(동작 보존) (문서: delete-product.md — DELETE /api/v1/products/{id})
- [ ] **PRODB-2** · 가격/재고 마켓 전부 실패해도 활동로그 status가 항상 SUCCESS — `ProductController.java:122-123` — result.failed()에 실패 마켓이 있어도 status=SUCCESS로 고정 기록(실패는 메시지 본문에만). status 기준 모니터링에서 부분/전체 실패 미노출. result.failed() 기준 status 분기 필요 (문서: update-price-stock.md — PUT /{id}/price-stock)
- [ ] **PRODB-6** · 이미지 마켓 부분 실패해도 활동로그 status 항상 SUCCESS — `ProductController.java:240-241` — uploadPreparedImages가 updateImagesAndHtml 정상반환 시 마켓 failed 유무 무관 SUCCESS 기록(PRODB-2 동형). 3경로 공통 헬퍼에서 status 분기 통일 필요 (문서: update-images.md — PUT /{id}/images)
- [ ] **PRODB-8** · 다운로드 파일명이 순번 기반(crawled-image-i.jpg)으로 URL 추적성 낮음 — `ImageDownloadService.java:61` — 원본 URL과 무관하게 순번으로 파일명 생성(by-url·크롤 공유). R2 파일명·응답에서 어느 원본 URL에서 온 파일인지 파악 어려움(진단성 저하). 기능결함 아님 (문서: update-images-by-url.md — PUT /{id}/images/by-url)
- [ ] **PRODB-9** · by-url 마켓 부분 실패해도 활동로그 status 항상 SUCCESS — `ProductController.java:240-241` — 공통 헬퍼 uploadPreparedImages 경유로 by-url에도 동일 적용(PRODB-6 동형). result.failed() 기준 status 분기 통일 필요 (문서: update-images-by-url.md — PUT /{id}/images/by-url)
- [ ] **PRODB-14** · crawl-and-upload 마켓 부분 실패해도 활동로그 status 항상 SUCCESS — `ProductController.java:240-241` — 공통 헬퍼 경유로 SOURCE_IMAGE_CRAWL에도 동일 적용(PRODB-2/6/9 동형). result.failed() 기준 status 분기 통일 필요 (문서: crawl-and-upload-images.md — POST /{id}/images/crawl-and-upload)

### batch (BATA / BATB)
- [ ] **BATA-4** · STARTED와 완료 이벤트 기록 경로가 달라 완료 로그 누락 리스크 — `ActionLogBatchListener.java:22-27` — STARTED는 컨트롤러 스레드, 완료는 @Async 종료 시 BatchCompletedEvent로 기록. @Async 스레드가 JVM 종료(배포)로 이벤트 발행 전 죽으면 STARTED만 남고 완료 로그 영구 누락. 고아 PENDING 복구 시 ActionLog 완료(중단) 기록도 검토 (문서: crawl-and-update.md — POST /crawl-and-update)
- [ ] **BATA-6** · 수동 재전송이 changed 스킵 최적화 없이 항상 마켓 전송(crawl과 비대칭) — `BatchPriceStockService.java:143-144` — 3-인자 syncPriceStock 오버로드는 changed=true 고정(ProductMarketSyncService.java:34-37). crawl 경로는 changed 계산해 Cafe24 재전송 스킵하는데 수동 경로는 diff 진입 후에도 changed 신호를 downstream에 안 넘김. 두 배치 마켓전송 정책 통일 (문서: manual-update-price-stock.md — POST /manual-update-price-stock)
- [ ] **BATA-10** · 두 병렬 리스트 index 매핑이 서비스까지 유지되어 순서 오염에 취약 — `BatchPriceStockService.java:167-171` — 컨트롤러는 size만 검증, 실제 매핑은 서비스 commands.get(i) index로 수행. manual-update-price-stock은 F-BATCH-M1로 쌍(PriceStockItem) 전환했는데 이 경로만 병렬 리스트. 순서 어긋나면 예외 없이 엉뚱한 상품에 command 적용(데이터 오염). {productId,command} 쌍 리스트로 통일 (문서: manual-update-all.md — POST /manual-update-all)
- [ ] **BATA-12** · 미정의 supplierCode가 VendorType.valueOf 원시 예외로 400 처리되어 메시지 비친화 — `BatchController.java:129` — VendorType.valueOf(toUpperCase())가 미정의 코드에 'No enum constant' 원시 메시지 노출. null/blank는 한국어 명시 거부하는데 잘못된 코드는 내부 구현 노출. try/catch로 감싸 '지원하지 않는 소싱업체 코드' 형태 400으로 통일 (문서: by-supplier.md — POST /by-supplier)
- [ ] **BATB-1** · 미존재 판정 경로가 status 유무에 따라 이원화(비필터는 조회 결과, 필터는 별도 count 쿼리) — `ProcessStatusService.java:125-139` — status==null은 조회결과 isEmpty로, status!=null은 별도 countByBatchId로 404를 판정해 미존재 판정 책임이 두 경로에 분산되고 필터 조회마다 count 쿼리 추가. 미존재 판정을 단일 헬퍼로 추출하거나 select 공집합일 때만 count 재확인 (문서: GET /api/v1/products/batch/status/{batchId})
- [ ] **BATB-4** · summary가 count 쿼리 3회를 개별 발행(단일 GROUP BY로 통합 가능) — `ProcessStatusService.java:144-152` — 폴링 경로가 countByBatchId + count(SUCCESS) + count(FAILED)로 배치당 폴링 1회에 DB 왕복 3회 발생. group by processStatus 단일 집계 쿼리로 통합해 다수 배치 동시 폴링 부하 감소 검토 (문서: GET /api/v1/products/batch/status/{batchId}/summary)

### product-sourcing (PSRC)
- [ ] **PSRC-2** · 컨트롤러 IHERB_URL_PATTERN과 크롤러 extractProductId가 URL 규칙을 이중 정의(표류 위험) — `ProductSourcingController.java:51-53` — api·infrastructure 두 모듈에 같은 URL 규칙 중복. 한쪽만 갱신되면 컨트롤러 통과 후 크롤러 ID추출 실패로 조용히 실패. 규칙 단일화 (문서: POST /api/v1/sourcing/iherb)
- [ ] **PSRC-5** · 이미지 호스팅 실패를 정상 진행으로 삼켜 hostedImages 없는 상품이 succeeded로 생성됨 — `ProductCreateUseCase.java:103-106` — enrich 예외를 log.warn 후 원본(hostedImages null)으로 진행해 생성은 성공 집계되나 이후 게시 validate에서 반드시 실패. 실패시점이 게시로 미뤄져 추적 곤란. 정책 확정·플래그 표면화 (문서: POST /api/v1/products/bulk)
- [ ] **PSRC-8** · hasClient와 getClient가 미지원 마켓 검증을 이중 수행(중복 가드·중복 예외메시지) — `ProductPublishUseCase.java:49-51` — hasClient로 한번 걸러도 getClient가 null시 다시 IllegalArgumentException을 던져 같은 조건 이중검사. getClient로 통합 또는 순서 의도 명시 (문서: POST /api/v1/products/{id}/markets/{marketType})

### supplier (SUP)
- [ ] **SUP-3** · 성공/실패 활동로그 try/catch가 createCurrency와 구조 중복 — `SupplierController.java:45-55` — createSupplier(L45-55)와 createCurrency(L67-77)가 try→record(SUCCESS)→return / catch→record(FAILED)→throw 동일 골격을 상수만 바꿔 반복. 로그 규약 변경 시 두 곳 동기 수정 필요 → 공통 헬퍼/AOP 추출 (문서: create-supplier.md — POST /api/v1/suppliers)
- [ ] **SUP-7** · 성공/실패 활동로그 try/catch가 createSupplier와 구조 중복 — `SupplierController.java:67-77` — createCurrency(L67-77)와 createSupplier(L45-55)의 활동로그 데코레이션 골격이 동일 반복(SUP-3와 동일 사안). 공통 헬퍼/AOP 추출 (문서: upsert-currency.md — POST /api/v1/currencies)

### market-credential (CRED)
- [ ] **CRED-5** · 저장 예외를 잡아 로그만 남기고 재던져 항상 500으로 표면화(클라이언트 오류 미구분) — `MarketCredentialController.java:55-58` — unique 제약(marketType unique)·길이초과 등 입력성 저장 실패도 일반 Exception 핸들러로 떨어져 500 응답. 활동로그 FAILED는 남으나 클라이언트가 재시도로 오인 가능 — 제약 위반을 400 계열로 변환하는 핸들러 검토 (문서: save-credential.md — PUT /api/v1/market-credentials/{marketType})

### cafe24-auth (CAFE)
- [ ] **CAFE-2** · 상태 판별을 예외 메시지 문자열 매칭에 의존 — `Cafe24AuthController.java:133-144` — isAuthFailure와 주문 권한 판정(L72)이 "401"/"403"/"insufficient_scope" 등 문자열 포함으로 정상/인프라 오류 분기. 원 상태코드는 Cafe24RestClient.enrich가 메시지에 녹여 넣어 예외 타입 유실. 응답 본문 snippet에 우연히 숫자 섞이면 오분류 가능. RestClient가 상태코드 구조적 보존, 컨트롤러는 코드로 분기 (문서: GET /api/admin/sync/cafe24/status)
- [ ] **CAFE-4** · code 추출을 두 번 수행(중복 호출) — `Cafe24AuthController.java:96,122` — issueToken이 extractCode(request.code())로 추출(L96)한 값을 exchangeAuthorizationCode에 넘기는데 exchangeAuthorizationCode가 다시 extractCode(rawCode)(L122). extractCode는 멱등이라 동작 결함 없으나 같은 파싱 두 번+계약 오해 소지. 한 번만 추출하도록 정리하고 blank 가드 순서 정돈 (문서: POST /api/admin/sync/cafe24/issue-token)

### market-registration (MREG)
- [ ] **MREG-3** · 상품 존재 검증 없이 등록행 유무로만 판정(목록 조회와 비대칭) — `MarketRegistrationService.java:33-38` — getLocalData는 ProductReader를 사용하지 않아 상품 미존재와 마켓 미등록을 응답으로 구분 불가. 필요 시 상품 존재를 먼저 404로 검증 후 등록행 유무 판정하도록 목록 경로와 정합화 (문서: get-local.md — GET /api/v1/products/{productId}/markets/{marketType}/local)
- [ ] **MREG-5** · 외부 마켓 HTTP 호출이 readOnly 트랜잭션 경계 안에서 수행됨 — `MarketRegistrationService.java:20,52` — 클래스 레벨 @Transactional(readOnly=true)가 syncMarketLive에도 적용돼 extractMarketItem 외부 I/O 동안 DB 커넥션 점유하나 결과를 DB에 반영하지도 않음. 커넥션 풀 고갈 위험 — 외부 호출을 트랜잭션 밖으로 분리 (문서: sync-market.md — POST /api/v1/products/{productId}/markets/{marketType}/sync)

### misc (MISCA / MISCB)
- [ ] **MISCA-1** · findTop100ByOrderByCreatedAtDesc() 데드 메서드 — `ActionLogRepository.java:11` — 조회 경로는 findAllByOrderByCreatedAtDesc(Pageable)만 사용하고 top100 메서드는 호출부 없음. 무해하나 조회 경로가 둘인 듯 오인 유발 — 제거 또는 주석 명시 (문서: GET /api/v1/action-logs)
- [ ] **MISCA-5** · 노출 enum 목록이 컨트롤러에 하드코딩(등록 누락 위험) — `CommonCodeController.java:30-33` — enum 등록이 put 4줄 수작업이라 새 enum 추가 시 컴파일러가 누락을 못 잡아 MISCA-4 재발 소지. EnumMapperType 구현체 스캔 자동 등록 검토 (문서: GET /api/v1/common/codes)
- [ ] **MISCA-8** · 크롤 실패를 상품 단위로 삼켜 부분 실패가 집계되지 않음 — `ProductSyncService.java:107-110` — syncProductStock catch가 log만 하고 삼키며 SUCCESS 메시지는 시도 대상 수만 담아(L61) 성공/실패 미구분. syncedCount(L123,139)는 로컬 로그로만. 성공/실패 건수를 ActionLog에 반영·부분성공 분기 (문서: POST /api/v1/products/sync/stock)
- [ ] **MISCB-2** · 파이프 구분 문자열 페이로드(스키마 없는 SSE 계약) — `SseNotificationController.java:56-61,74-76` — syncPayload/batchPayload가 'MARKET|success'·'batchId|true' 문자열을 만들고 프론트가 파싱. errorMessage에 파이프 포함 시 분해 오류. JSON 페이로드로 전환하고 계약 테스트로 고정 (문서: subscribe.md — GET /api/v1/notifications/subscribe)
- [ ] **MISCB-7** · IMAP 최근 200건 제목 필터 창 밖 이메일 영구 누락 가능 — `EmailFetcherService.java:141,421` — start=max(1,total-199)로 항상 최근 200건만 스캔. 수신량 많으면 발송/확인 메일이 창 밖으로 밀려 송장·실구매가 영구 미반영, 재시도해도 회복 불가. SearchTerm/UID 증분 조회 또는 스캔 폭 설정화·잔여 경고 노출 (문서: fetch.md — POST /internal/email/fetch)
- [ ] **MISCB-8** · 매 (주문번호×계정)마다 IMAP 재접속·INBOX 전체 재조회 반복 — `EmailFetcherService.java:104-108,129-144` — 주문번호 N개마다 계정별로 store.connect·getMessages(최근200건)를 새로 수행해 계정당 N회 접속·페치. 대상 많을수록 선형 악화(MISCB-6 tx 점유와 복합). 계정당 1회 접속 후 메모리에서 주문번호 집합 매칭으로 왕복 축소 (문서: fetch.md — POST /internal/email/fetch)

---

## 🔵 P3 — NOTE (의도 확인 필요 / 개선 여지)

### order (ORDA / ORDB / ORDC)
- [ ] **ORDA-2** · 페이지 크기 상한 부재로 큰 size 요청 시 조인 조립 비용 선형 증가 — `OrderRepositoryImpl.java:58-118` — pageable.getPageSize()를 그대로 limit로 사용하고 라인/상품/등록을 앱에서 조립. 컨트롤러에 @PageableDefault 등 상한 없음 → 대량 요청 시 메모리/성능 노출. 최대 페이지 크기 정책 명시 (문서: get-orders.md — GET /api/v1/orders)
- [ ] **ORDA-3** · 마켓등록 조인이 상품·마켓 일치 첫 건만 선택(다건 시 비결정적) — `OrderRepositoryImpl.java:100-105` — regsByProductId에서 marketType 일치 findFirst만 DTO에 담아, 동일 상품·마켓 복수 등록 시 임의 한 건만 노출. 유일성 보장 또는 선택 규칙 명시 (문서: get-orders.md — GET /api/v1/orders)
- [ ] **ORDA-6** · 주소/통관번호 값 검증(트림·길이) 부재 — `OrderService.java:241-248` — address != null만 확인하고 그대로 대입, 공백/500자 초과 방어 없음(소싱 경로 진입부 음수검증과 대조). 필요 시 형식·길이 검증 정책 확정 (문서: update-order.md — PATCH /api/v1/orders/{id})
- [ ] **ORDA-7** · 유니패스 수정 성공 시 활동로그 마켓 해석을 위해 라인/주문 2회 재조회 — `OrderController.java:222` — marketNameOfLineItem이 marketTypeOfLineItem으로 방금 다룬 라인/주문을 다시 findById 2회. 반환된 updated의 orderId 재활용하면 왕복 절감 가능. read-only라 부작용 없음, 우선순위 낮음 (문서: update-line-item.md — PATCH /api/v1/orders/line-items/{lineItemId})
- [ ] **ORDA-8** · 빈/비JSON 요청 바디는 서비스 가드가 아닌 프레임워크 예외 경로로 빠져 활동로그 누락 가능 — `OrderController.java:215-216` — 빈 바디는 isUnipassDone null→서비스 400 유도되나 완전 누락/비JSON은 메시지 컨버터 예외라 catch(:226) 로그 우회 가능. required/@Valid로 진입부 검증 통일 검토 (문서: update-line-item.md — PATCH /api/v1/orders/line-items/{lineItemId})
- [ ] **ORDB-2** · 크레덴셜 미존재(RuntimeException)와 접수 실패(MarketOrderAcceptException)가 응답코드로 구분되지 않음 — `OrderService.java:95-104` — 컨트롤러가 모든 Exception을 그대로 rethrow(OrderController.java:107-111)하여 설정성 오류와 마켓 일시오류가 동일 코드로 노출. 상이한 HTTP 상태 매핑 검토 (문서: confirm-order.md — POST /orders/{id}/confirm)
- [ ] **ORDB-4** · 일괄확인 부분 성공도 활동로그 상태를 FAILED로 기록 — `OrderController.java:79-81,130` — statusOf(failedCount)가 failedCount!=0이면 FAILED를 반환해 성공 건이 있어도 FAILED로 남음(의도된 설계). 활동로그 상태만으로 부분성공/완전실패 구분 불가 — PARTIAL 상태 검토 여지 (문서: confirm-batch.md — POST /orders/confirm/batch)
- [ ] **ORDB-9** · 일괄취소 부분 성공도 활동로그 상태를 FAILED로 기록(ORDB-4와 동일 정책) — `OrderController.java:79-81,176` — statusOf(failedCount) 정책을 cancel/batch에도 적용해 부분성공도 FAILED로 남음(의도된 설계). 활동로그 상태만으로 부분성공/완전실패 구분 불가 (문서: cancel-batch.md — POST /orders/cancel/batch)
- [ ] **ORDC-2** · 음수 금액 검증이 컨트롤러에만 있고 서비스/도메인 계층엔 없음 — `OrderController.java:261-270` — 음수 거부가 validateSourcingAmounts(컨트롤러)에만 존재. 배치·워커 등 다른 진입점이 updateSourcingInfo를 직접 호출하면 음수 저장 가능. 불변식을 SourcingData VO/서비스로 하향 검토 (문서: PATCH /line-items/{lineItemId}/sourcing)
- [ ] **ORDC-5** · terminal 재시도불가 판정이 한글 오류 메시지 문자열 매칭에 의존 — `MarketplaceShippingService.java:126-133` — isNonRetryableMarketState가 '배송진행상태가 유효하지 않습니다' 등 문자열 포함 여부로 terminal 판정. 마켓 문구 변경·타 마켓 다른 문구 시 terminal 놓쳐 무한 재시도 대상화 위험. 오류 코드/포트 위임 기반 분류 검토 (문서: PATCH /line-items/{lineItemId}/shipping)
- [ ] **ORDC-9** · 주문 내 일부 라인 실패 시 주문 전체 failed 집계되나 성공 라인 SHIPPED 저장은 커밋됨(의미 불일치) — `OrderShipProcessor.java:100-114` — orderFailed여도 값 반환(예외 아님)이라 앞서 save된 성공 라인은 커밋. 주문은 failedIds에 담김. 재발송 시 SHIPPED 라인은 상태가드로 스킵되어 중복은 없으나 '주문 failed' 표기와 '라인 실제 발송됨' 불일치. 라인 단위 집계/partial 상태 구분 (문서: POST /orders/ship)
- [ ] **ORDC-10** · 크레덴셜 없는 마켓·존재하지 않는 주문이 skipped 아닌 failed로 집계됨 — `OrderShipProcessor.java:49-59` — 주문 없음·크레덴셜 없음을 failed로 반환. 단건 경로는 어댑터 없는 마켓을 ofSkipped 처리(:88-93)와 대비. 설정 누락 마켓이 발송실패로 집계돼 재시도 대상처럼 보임. 설정오류/스킵 범주 분리 여부 정책 결정 (문서: POST /orders/ship)

### order-sync (SYNCA / SYNCB)
- [ ] **SYNCA-3** · 조회 범위 30일 하드코딩(파라미터화 없음) — `CoupangOrderSyncService.java:73` — 조회·취소감지 범위가 now-30일로 고정되어 그 이전 취소/변경은 감지 불가, 백필도 코드수정 없이는 불가. 설정값/파라미터로 외부화 검토 (문서: sync-coupang.md — POST /api/v1/orders/sync/coupang)
- [ ] **SYNCA-4** · in-JVM AtomicBoolean 중복가드는 교차 JVM(워커+api) 동시실행을 못 막음 — `CoupangOrderSyncService.java:53,60` / `OrderSyncScheduler.java:49-53` — 주문 동기화 중복가드는 AtomicBoolean인데 정산 경로만 DB 클레임(tryMarkRunning) 사용. 스케줄러(worker)와 API(api)는 별도 JVM이라 동시 2회 실행 가능. 정산과 동일하게 DB 기반 교차 JVM 가드로 통일 검토 (문서: sync-coupang.md — POST /api/v1/orders/sync/coupang)
- [ ] **SYNCA-12** · 트래킹번호 마켓전송 보존 가드 부재(쿠팡과 비대칭) — `ElevenstOrderSyncService.java:115-126` — 쿠팡의 trackingSentToMarket 보존 가드 없이 dto 송장을 무조건 반영해 마켓 미전송 로컬 송장 유실 가능. 11번가 송장 write-path 확인 후 보존 가드 필요성 판정 (문서: sync-elevenstreet.md — POST /api/v1/orders/sync/elevenstreet)
- [ ] **SYNCA-17** · in-JVM AtomicBoolean 중복가드가 워커와 교차 JVM 동시실행을 못 막음 — `Cafe24OrderSyncService.java:57,62` / `OrderSyncScheduler.java:57-61` — AtomicBoolean 중복가드라 스케줄러(worker)와 수동 트리거(api)가 교차 JVM으로 동시 실행 가능. SYNCA-16의 긴 루프와 겹치면 부하·중복 upsert 위험. 정산 경로처럼 DB 기반 교차 JVM 가드로 통일 검토 (문서: sync-esmplus.md — POST /api/v1/orders/sync/esmplus)
- [ ] **SYNCB-2** · 부작용 없는 진단 조회를 POST로 노출 + ActionLog 미기록 — `OrderSyncController.java:166-179` — 읽기 전용 진단을 POST로 매핑하고 감사 로그를 남기지 않음. 누가 언제 외부 API를 호출했는지 추적 불가. POST 유지가 의도면 문서화, 감사 필요 시 로그 기록 검토 (문서: cafe24-preview.md — POST /api/v1/orders/sync/cafe24/preview)
- [ ] **SYNCB-3** · 프리뷰 페이지 크기 5·오프셋 0 하드코딩(첫 페이지만) — `OrderSyncController.java:172` — limit/offset 미파라미터화로 항상 첫 5건만 조회 가능해 진단 범위 제한. 필요 시 쿼리 파라미터 노출 (문서: cafe24-preview.md — POST /api/v1/orders/sync/cafe24/preview)
- [ ] **SYNCB-5** · 택배사 진단 조회도 POST 매핑 + ActionLog 미기록 — `OrderSyncController.java:182-191` — SYNCB-2와 동일 이슈 — 읽기 전용 조회를 POST로 노출하고 감사 흔적 없음. GET 관례·감사 기록 검토 (문서: cafe24-carriers.md — POST /api/v1/orders/sync/cafe24/carriers)
- [ ] **SYNCB-13** · 이중 매핑(엔티티→내부 SyncStatus→SyncStatusResponse) — `SyncStatusService.java:102-108` / `SyncStatusResponse.java:19-25` — 동일 4필드를 내부 DTO와 응답 DTO로 두 번 매핑(F-SYNC-24 잔여비용). 필드 추가 시 두 곳 수정 필요. 서비스가 응답 DTO 직접 반환하도록 단순화 검토 (문서: status.md — GET /api/v1/orders/sync/status)
- [ ] **SYNCB-14** · 상태 조회 인증 게이트 부재 + errorMessage 원문 노출 여지 — `OrderSyncController.java:34,248-256` — @CrossOrigin(origins=*)로 인증 없이 노출되며 응답 errorMessage에 외부 API 실패 원문이 실릴 수 있음. 보안 비중요 정책상 즉시 결함 아니나 마스킹 검토 여지 (문서: status.md — GET /api/v1/orders/sync/status)

### product (PRODA / PRODB)
- [ ] **PRODA-1** · 잘못된 marketFilter enum은 400 매핑되나 조회 경로에 검증·로그 없음 — `ProductController.java:85` — MarketType.valueOf(marketFilter)는 잘못된 마켓명/빈 문자열에 IAE를 던져 GlobalExceptionHandler가 400 안전 매핑하나, 조회 경로엔 활동로그가 없어 관측 흔적 없음. 파싱 헬퍼로 감싸 메시지 통일 검토 (문서: list-products.md — GET /api/v1/products)
- [ ] **PRODA-2** · buildMarketMap이 코드 없는 등록행에 productId를 폴백해 마켓 실제코드와 자사 id가 응답에서 구분 안 됨 — `ProductController.java:425-428` — extractMarketCode()가 null이면 productId를 마켓코드 자리에 넣어, 마켓코드 미확정 상태와 실제코드가 목록에서 모호. 상세응답 D-052 폴백('미확인')과 통일 검토(프론트 합의 필요) (문서: list-products.md — GET /api/v1/products)
- [ ] **PRODA-5** · 전체수정 성공 응답이 본문 없는 200 OK(Void)라 클라이언트가 재조회 필요 — `ProductController.java:309` — ResponseEntity.ok().build()로 갱신 스냅샷/변경필드를 반환하지 않아 프론트가 별도 GET /{id} 재조회 필요. 기능오류 아님. 필요 시 ProductDetailResponse 반환 검토(계약 변경) (문서: update-product.md — PUT /api/v1/products/{id})
- [ ] **PRODA-9** · 부분 실패여도 항상 HTTP 200이라 상태코드만으로 실패 인지 불가 — `ProductController.java:325-326` — failed가 비어있지 않아도 항상 ResponseEntity.ok(result)이고 ActionLog만 FAILED. best-effort 설계상 의도됐으나 상태코드로 성공/부분실패를 구분하는 클라이언트는 오탐. 프론트가 failed를 반드시 표면화하도록 문서화(또는 207 검토) (문서: delete-product.md — DELETE /api/v1/products/{id})
- [ ] **PRODB-1** · 가격/재고 마켓 반영(외부 HTTP)이 @Transactional 경계 안에서 실행됨 — `ProductManageUseCase.java:57,80` / `ProductMarketSyncService.java:77-79` — updatePriceStock 전체가 @Transactional이고 그 안에서 N개 마켓 syncPriceAndStock HTTP를 순차 호출 → 상품 row 트랜잭션이 마켓 응답 지연만큼 열려 커넥션/락 점유 장기화. 완전삭제처럼 DB커밋과 마켓반영 분리 검토 (문서: update-price-stock.md — PUT /{id}/price-stock)
- [ ] **PRODB-3** · 재고 수량이 판매중/품절 이분법으로 고정(실제 수량 미반영) — `ProductMarketSyncService.java:45-47` — quantity=soldOut?1:DEFAULT_IN_STOCK_QUANTITY로 고정, DB 수량 미변경. 의도된 설계이나 '재고 수정' 명칭과 실제 동작(재고상태 토글) 간극 → 명세 문서화 필요 (문서: update-price-stock.md — PUT /{id}/price-stock)
- [ ] **PRODB-5** · R2 업로드·HTML치환·마켓재게시가 단일 @Transactional 안에서 실행 — `ProductManageUseCase.java:83,88,136` — R2 PUT과 마켓 HTTP가 트랜잭션 안 → 커넥션 점유 장기화. R2 실패 시 전체 롤백돼 DB정합은 유지되나 이미 올라간 R2 객체는 롤백 안 돼 고아 객체 잔존. 트랜잭션 분리·고아 정리 정책 검토 (문서: update-images.md — PUT /{id}/images)
- [ ] **PRODB-10** · GET 크롤 0개(이미지 없음)와 정상 N개가 동일 로그 타입·status로 기록 — `ProductController.java:174-178` — images.size()==0도 SUCCESS '0개 수집'으로 기록(별도 분기 없음). crawl-and-upload는 0개를 별도 메시지로 구분(:204-209)하는 것과 비정합. 진단성 위해 세분화 검토 (문서: crawl-source-images.md — GET /{id}/images/crawl)
- [ ] **PRODB-11** · crawlProductInfoAsDto null 반환(크롤 차단/파싱 실패)도 SUCCESS 0개로 처리 — `ProductController.java:262-263,174-178` / `IherbScraperClient.java:220-223` — scraped==null이면 빈 목록→'0개 수집 SUCCESS'로 기록되어 크롤 실질 실패가 '이미지 없음'으로 오인. crawlProducts는 동일 null을 실패로 취급하는 것과 계약 불일치. null 시 실패 표면화 검토 (문서: crawl-source-images.md — GET /{id}/images/crawl)
- [ ] **PRODB-13** · 크롤/다운로드 실패와 저장 실패가 동일 프리픽스로만 구분됨 — `ProductController.java:213-218,243-247` — 크롤/다운로드 예외(트랜잭션 밖)와 저장 예외(@Transactional)를 동일 SOURCE_IMAGE_CRAWL 타입·동일 프리픽스 '크롤·업로드 실패'로 FAILED 기록. 어느 단계 실패인지 로그로 구분 안 돼 예외 메시지 의존. 단계별 프리픽스 세분화 검토 (문서: crawl-and-upload-images.md — POST /{id}/images/crawl-and-upload)

### batch (BATA / BATB)
- [ ] **BATA-3** · by-supplier와 동일 jobType 공유로 동시 실행 상호 차단 — `BatchController.java:70,141` — crawl-and-update와 by-supplier가 모두 JobType.CRAWL_AND_UPDATE_PRICE_STOCK을 사용해 jobType 단위 runningJobTypes 가드 상 상호 배타 실행. 논리적으로 다른 두 작업이 동시 실행 불가. 의도된 직렬화면 문서화, 아니면 전용 JobType 검토 (문서: crawl-and-update.md — POST /crawl-and-update)
- [ ] **BATA-9** · 전체 필드 수정이 연동 마켓에 반영되지 않음(가격·재고 포함 가능) — `BatchPriceStockService.java:161-186` — manualUpdateAllFields는 product.update+save만 하고 syncPriceStock 미호출. crawl·manual-update-price-stock은 마켓 재전송하는데 이 경로는 command에 salePrice/stock이 담겨도 마켓 미반영 → DB-마켓 불일치 가능. 마켓 반영 정책 확정·문서화 (문서: manual-update-all.md — POST /manual-update-all)
- [ ] **BATA-13** · crawl-and-update와 동일 jobType 사용으로 두 배치 상호 배타 실행 — `BatchController.java:141` — by-supplier가 JobType.CRAWL_AND_UPDATE_PRICE_STOCK 사용 → crawl-and-update와 같은 jobType이라 runningJobTypes 가드 상 상호 거부(400). 업체 전체 크롤과 선택 상품 크롤이 동시 실행 불가. 전용 JobType 검토 또는 직렬화 의도 문서화 (문서: by-supplier.md — POST /by-supplier)
- [ ] **BATB-2** · RecordStatus(소프트삭제 status)를 응답 status 필드로 그대로 노출하며 조회 쿼리가 RecordStatus를 필터하지 않음 — `ProcessStatusResponse.java:31` — 응답에 processStatus와 status(BaseEntity RecordStatus ACTIVE/ARCHIVED/DELETED)가 나란히 노출돼 혼동 소지, 조회 쿼리는 DELETED 행을 제외하지 않음. 현재 소프트삭제 경로가 없어 실해는 없으나 향후 도입 시 필터 재검토 필요 (문서: GET /api/v1/products/batch/status/{batchId})
- [ ] **BATB-3** · pending이 PENDING count가 아니라 total-done 파생값이라 상태 확장 시 의미가 어긋남 — `BatchSummary.java:18` — pending=total-(success+failed)로 산출. 현재 enum이 PENDING/SUCCESS/FAILED 3값뿐이라 정확하나 중간/스킵 상태 추가하면 done에 안 잡혀 pending에 잘못 합산되고 percent 왜곡. 상태 확장 시 명시적 count(PENDING)로 전환 또는 테스트로 고정 (문서: GET /api/v1/products/batch/status/{batchId}/summary)
- [ ] **BATB-5** · 페이징·상한 없이 전체 배치 ID를 반환(이력 누적 시 목록 무한 증가) — `ProcessStatusRepository.java:16-17` — findDistinctBatchIds가 LIMIT/조건 없이 모든 distinct batchId를 반환. batchId는 배치마다 신규 생성돼 무한 누적되므로 이력이 쌓이면 응답이 비대해짐. 최근 N개 제한/페이징과 오래된 ProcessStatus 행 보존정책(TTL/아카이브) 도입 검토 (문서: GET /api/v1/products/batch/status)

### product-sourcing (PSRC)
- [ ] **PSRC-3** · 크롤 실패 사유가 '크롤 결과를 가져오지 못했습니다'로 뭉뚱그려져 403/404/파싱실패 구분 불가 — `IherbScraperClient.java:235-236` — null 반환이 모든 실패원인을 하나로 수렴시켜 재시도/조치 판단 어려움. 실패 사유 구조화 (문서: POST /api/v1/sourcing/iherb)
- [ ] **PSRC-6** · 음수 검증이 costPrice에만 있고 marginRate·weight·capacity·bundleQuantity는 무검증 — `ProductSourcingController.java:113-117` — costPrice 음수만 400으로 거르고 다른 수치필드는 오염값 통과 가능. 필드별 유효범위 정책 정의 후 필요분만 가드 추가 (문서: POST /api/v1/products/bulk)
- [ ] **PSRC-9** · 미존재 상품이 404가 아닌 400으로 반환됨(ResourceNotFound+404 규약과 비대칭) — `ProductPublishUseCase.java:46-47` — 상품 미존재를 IllegalArgumentException으로 던져 GlobalExceptionHandler가 400 매핑. 핸들러는 미존재용 ResourceNotFoundException(404)을 별도 보유. 404로 통일 (문서: POST /api/v1/products/{id}/markets/{marketType})
- [ ] **PSRC-10** · markPublished 실패 시 마켓엔 게시됐으나 활동로그는 FAILED만 남아 게시성공 사실 유실 — `ProductPublishUseCase.java:70-75` — 게시 성공 후 DB갱신 실패시 rethrow로 컨트롤러가 record(FAILED) 기록. 운영자가 실패로 오인해 재게시(PSRC-7 중복유발) 위험. PARTIAL/복구필요 상태 표면화 (문서: POST /api/v1/products/{id}/markets/{marketType})

### supplier (SUP)
- [ ] **SUP-1** · 페이지네이션·필터 없는 전체 ACTIVE 반환 — `SupplierService.java:28` — getSuppliers가 ACTIVE 전량을 무제한 반환(limit/검색 없음). 현 소규모 도메인에선 무해하나 다른 목록 API의 페이지네이션과 비대칭. 규모 확대 시 도입 여지만 문서화 (문서: list-suppliers.md — GET /api/v1/suppliers)
- [ ] **SUP-4** · 활동로그가 서비스 트랜잭션 밖(컨트롤러)에서 기록됨 — `SupplierController.java:48` — record(SUCCESS)가 createSupplier 커밋 후 컨트롤러에서 호출되고 ActionLogService.record는 자체 @Transactional이라 저장과 별개 트랜잭션. 로그 실패는 내부에서 삼켜(ActionLogService.java:37-40) 본업 보호. 의도된 설계로 정합 위험 낮음, 감사 강화 요구 시 통합 검토 (문서: create-supplier.md — POST /api/v1/suppliers)

### market-credential (CRED)
- [ ] **CRED-1** · 무인증 목록 조회 엔드포인트(CrossOrigin *, 인증 필터 없음) — `MarketCredentialController.java:24,31-34` — 누구나 호출 가능하나 시크릿은 MarketCredentialDto(:16-18)에서 불리언 마스킹되어 유출 위험 낮음. clientId·redirectUri·연동여부는 무인증 노출 — 운영환경 CORS·인증 정책 문서화 (문서: list-credentials.md — GET /api/v1/market-credentials)
- [ ] **CRED-2** · 잘못된 enum 경로변수는 전역 핸들러로 400 처리됨(계약 확인) — `GlobalExceptionHandler.java:19-25` — 미매칭 MarketType 값은 MethodArgumentTypeMismatchException→400으로 표면화(EnumPathVariableMismatchTest가 고정). 결함 아님, 계약 확인 노트 (문서: get-credential.md — GET /api/v1/market-credentials/{marketType})
- [ ] **CRED-3** · 무인증 단건 조회(CrossOrigin *) — `MarketCredentialController.java:24,36-41` — 시크릿 마스킹으로 유출 위험 낮으나 clientId·redirectUri·연동여부 무인증 노출. CRED-1과 동일 성격 — 운영 CORS·인증 정책 문서화 (문서: get-credential.md — GET /api/v1/market-credentials/{marketType})
- [ ] **CRED-6** · 입력 검증(@Valid) 부재 — 필수값·형식 검증 없이 upsert — `MarketCredentialController.java:44-47` — @RequestBody에 @Valid 없고 SaveCommand에 검증 애너테이션 없음. 전 필드 빈 바디로도 신규 빈 자격증명 생성 가능. 마켓별 필수필드 서버측 강제 없음 — 최소 검증 추가 검토 (문서: save-credential.md — PUT /api/v1/market-credentials/{marketType})
- [ ] **CRED-7** · refreshToken·accessToken·isActive·tokenExpiresAt는 이 엔드포인트로 설정 불가 — `MarketCredentialSaveCommand.java:8-12` — SaveCommand에 해당 필드 부재로 서비스가 손대지 않음(OAuth 토큰은 별도 인증 흐름 관리). 신규 생성 시 isActive는 엔티티 기본 true로만 결정되어 PUT으로 비활성화 수단 없음 — 의도된 분리로 보이나 문서화 노트 (문서: save-credential.md — PUT /api/v1/market-credentials/{marketType})

### cafe24-auth (CAFE)
- [ ] **CAFE-3** · 조회 API가 부수적으로 자격증명을 갱신·저장 — `Cafe24TokenManager.java:49-69` — status()가 getValidAccessToken을 통해 만료 토큰을 자동 갱신하고 persist(L102-110)로 MarketCredential을 저장하는데 status() 자체는 @Transactional이 아님. 만료 자동 갱신이라는 의도된 설계이나 순수 조회를 기대하는 호출자엔 비직관적. advisory lock으로 2 JVM 경쟁은 방지됨. 문서화만 필요 (문서: GET /api/admin/sync/cafe24/status)
- [ ] **CAFE-5** · 토큰 저장과 활동로그가 원자적이지 않음 — `Cafe24AuthController.java:101-104` — issueInitialToken의 자격증명 persist와 컨트롤러의 actionLogService.record(L103-104)는 별개 작업이며 @Transactional로 묶이지 않음. 토큰 저장 후 활동로그 기록이 실패하면 200 응답은 이미 나가고 로그만 누락 가능. 재무·정합성 영향 없고 감사 로그 정확도 문제. 현 설계 의도라면 문서화 충분 (문서: POST /api/admin/sync/cafe24/issue-token)
- [ ] **CAFE-7** · 예외 메시지를 응답 본문에 그대로 노출 — `Cafe24AuthController.java:206` — 실패 시 "인증 실패: " + e.getMessage()를 응답 본문으로 반환하고 issue-token도 유사(L111). 예외 메시지에 Cafe24 응답 snippet(enrich, 최대 300자)이 실려 내부 정보가 화면에 표시 가능. 관리자 전용·보안 비중요 환경이라 낮은 우선순위. 필요 시 일반화 메시지 + 서버 로그 상세로 분리 (문서: GET /api/admin/sync/cafe24/auth/callback)
- [ ] **CAFE-8** · 신규 UI는 issue-token 사용하는데 레거시 콜백이 계속 노출 — `Cafe24AuthController.java:195-208` — 주석상 (레거시) GET 콜백이며 신규 UI는 POST /issue-token 사용. GET 콜백은 활동로그를 남기지 않고 응답 형태(String)도 달라 관측성이 낮음. Cafe24 앱 리다이렉트 URI로 여전히 필요한지 확인, 불필요하면 폐기, 유지 시 활동로그 추가 검토(현재는 테스트로 고정된 의도적 비대칭) (문서: GET /api/admin/sync/cafe24/auth/callback)

### market-registration (MREG)
- [ ] **MREG-1** · 목록 조회는 상품 존재를 404로 가드하나 로컬 조회는 미가드로 비대칭 — `MarketRegistrationService.java:28-29,33-38` — getRegistrations는 productReader로 상품 존재를 404로 검증하나 getLocalData는 상품 존재를 확인하지 않아 같은 리소스 트리 하위 조회의 상태코드 정책이 비대칭. 상품 존재 가드 정책 통일 검토 (문서: list-registrations.md — GET /api/v1/products/{productId}/markets)
- [ ] **MREG-6** · sync 이름과 달리 동기화 상태를 저장하지 않음(순수 라이브 조회) — `MarketRegistrationService.java:40-53` — syncMarketLive는 MarketItemInfo만 반환하고 markSynced/isSynced/lastSyncedAt를 갱신하지 않아 이름(/sync)이 실제 동작(읽기 전용 preview)과 어긋남. 이름/문서 명확화 또는 의도가 반영이면 저장 로직 추가 검토 (문서: sync-market.md — POST /api/v1/products/{productId}/markets/{marketType}/sync)

### misc (MISCA / MISCB)
- [ ] **MISCA-2** · 오프셋 페이지네이션이나 total/hasNext 미제공 — `ActionLogController.java:41-51` — 평면 List만 반환하고 총 개수/다음 페이지 유무가 없어 마지막 페이지 판정이 응답만으로 불가. 프론트가 실제 페이지네이션 UI 도입 시 헤더/확장응답으로 노출 검토(의도된 비파괴 설계) (문서: GET /api/v1/action-logs)
- [ ] **MISCA-3** · limit/page 방어가 컨트롤러·서비스에 중복(상수 드리프트 위험) — `ActionLogController.java:45-46` — 동일 상하한 정규화가 컨트롤러 L45-46과 서비스 ActionLogService.java:59-60에 각각 하드코딩. 의도된 다중 방어이나 100/500 상수를 단일 출처로 공유해 드리프트 방지 (문서: GET /api/v1/action-logs)
- [ ] **MISCA-6** · 무캐시 재계산 및 무인증 개방 — `CommonCodeController.java:23-35` — 불변 enum을 매 요청 재매핑하고 CrossOrigin(*) 무인증 개방. 데이터가 공개 라벨이라 무해하나 정적 캐시로 재계산 제거 가능(문서화 목적) (문서: GET /api/v1/common/codes)
- [ ] **MISCA-10** · 대상 0건이어도 STARTED→SUCCESS 로그 페어 기록 — `ProductSyncService.java:59-61` — 가드 통과 시 무조건 STARTED 기록 후 대상 0건이어도 SUCCESS '대상 0개' 기록. 무해하나 로그 노이즈 — '대상 없음' 구분 메시지로 가독성 향상 선택 (문서: POST /api/v1/products/sync/stock)
- [ ] **MISCB-3** · INIT 이후 heartbeat 부재로 프록시 유휴 끊김 가능 — `SseNotificationController.java:42` — INIT 1회 후 24h 무전송 구간이 있어 nginx/프록시 idle timeout에 조용히 끊길 수 있고 그 사이 이벤트 유실. 15~30초 heartbeat comment 전송 또는 프록시 타임아웃 상향·문서화 (문서: subscribe.md — GET /api/v1/notifications/subscribe)
- [ ] **MISCB-4** · CORS 전역 개방된 무인증 브로드캐스트 스트림 — `SseNotificationController.java:17` — @CrossOrigin(origins=*) + 인증 부재로 임의 오리진이 마켓명·배치ID·에러메시지를 수신 가능. 보안 비중요 방침상 즉시 결함은 아니나 운영 오리진 화이트리스트 검토 (문서: subscribe.md — GET /api/v1/notifications/subscribe)
- [ ] **MISCB-9** · 500 응답 바디에 원시 예외 메시지 노출 — `EmailFetchController.java:63-64` — error 필드에 e.getMessage()를 그대로 반환하고 markFailed에도 동일 저장. 내부 전용(8081, nginx 미노출)이라 위험은 낮으나 IMAP 호스트/계정 세부가 실릴 수 있음. 응답 바디 일반화 검토 (문서: fetch.md — POST /internal/email/fetch)

---

## 도메인별 인덱스

| 도메인 (유닛) | 🔴 BUG | 🟠 GAP | 🟡 SMELL | 🔵 NOTE | 합계 |
|---------------|:-----:|:-----:|:-------:|:------:|:---:|
| order (ORDA·ORDB·ORDC) | 0 | 7 | 8 | 12 | 27 |
| order-sync (SYNCA·SYNCB) | 1 | 11 | 10 | 9 | 31 |
| product (PRODA·PRODB) | 0 | 6 | 7 | 10 | 23 |
| batch (BATA·BATB) | 0 | 7 | 6 | 6 | 19 |
| cafe24-auth (CAFE) | 0 | 2 | 2 | 4 | 8 |
| market-credential (CRED) | 0 | 1 | 1 | 5 | 7 |
| market-registration (MREG) | 0 | 2 | 2 | 2 | 6 |
| product-sourcing (PSRC) | 0 | 3 | 3 | 4 | 10 |
| supplier (SUP) | 0 | 3 | 2 | 2 | 7 |
| misc (MISCA·MISCB) | 0 | 6 | 6 | 7 | 19 |
| **합계 (16 유닛)** | **1** | **48** | **47** | **61** | **157** |

---

## 하단 메모

- 실제 수정은 `sbshop-normalize` 스킬 소관. 이 체크리스트는 진단·기록 전용이다.
- 1차(`docs/api-analysis/FINDINGS-CHECKLIST.md`)와 별개의 현재-코드 기준 신규 집계다. 1차의 F-* 식별자와 무관한 2차 유닛 식별자(ORDA-·SYNCB- 등) 체계를 사용한다.
- 🔴/🟠 중 재현 가능한 것은 `docs/normalize/defect-ledger.md`로 승격 등재 후보.
