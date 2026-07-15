# API 흐름 분석 — 발견사항 우선순위 체크리스트

> `docs/api-analysis/` 하위 **57개 API 문서**(§7 발견사항)를 우선순위별로 집계한 마스터 체크리스트.
> 각 항목은 해당 API 문서의 §7 에 근거·영향·제안 상세가 있다. **이 문서는 진단·기록 전용** — 실제 수정은 `sbshop-normalize` 스킬(재현 테스트 → Red→Green) 소관.
> 🔴/🟠 중 재현 가능한 것은 `docs/normalize/defect-ledger.md` 로 승격 등재 후보.
>
> 생성: 2026-07-14 · 근거 커밋: main@bd4c915

## 집계 매트릭스

| 도메인 (문서 수) | 🔴 BUG | 🟠 GAP | 🟡 SMELL | 🔵 NOTE | 합계 |
|------------------|:-----:|:-----:|:-------:|:------:|:---:|
| order (11) — F-ORD / F-S / F-H | 2 | 19 | 15 | 13 | 49 |
| order-sync (8) — F-SYNC | 4 | 8 | 7 | 6 | 25 |
| product (9) — F-PROD | 0 | 12 | 8 | 9 | 29 |
| batch (7) — F-BATCH | 3 | 8 | 7 | 7 | 25 |
| product-sourcing (3) — F-PSRC | 1 | 7 | 4 | 5 | 17 |
| supplier (4) — F-SUP | 1 | 5 | 4 | 7 | 17 |
| market-credential (3) — F-CRED | 2 | 4 | 1 | 3 | 10 |
| cafe24-auth (3) — F-CAFE | 0 | 5 | 5 | 4 | 14 |
| market-registration (3) — F-MREG | 0 | 5 | 2 | 0 | 7 |
| misc (5) — F-MISC | 2 | 7 | 7 | 6 | 22 |
| **합계 (59 문서)** | **15** | **80** | **60** | **60** | **215** |

*(order 도메인은 샘플 2건 F-S*/F-H* 포함)*

---

## 진행 현황 (Phase 진척)

| Phase | 대상 SP | 상태 | 커밋 |
|-------|---------|------|------|
| **P1 관측성·오류시맨틱** | SP-6, SP-7 | ✅ 완료 (일부 오탐 확정) | `60b02fe`(SP-7) · `6e320e0`(SP-6) |
| **P2 상태가드** | SP-4 | ✅ 완료 (정책확정: 데이터 주인 기준) | `dfcf8b3`(order) · `aad006e`(batch) · `6c396f4`(web) |
| **P3 부분실패 표면화** | SP-3 | ✅ 완료 (order 도메인, 상품은 후속) | `ffdaed3`(order) · `6095f1b`(batch) · `dbfefec`(web) |
| **P4 비동기·영속상태** | SP-1, SP-2 | 🔄 P4a 완료(sync), P4b·c·d 대기 | `059ed79`(sync) · `4268d71`(ddl-auto) |
| P5 구조 리팩토링 | SP-9, SP-11 | ⏳ 대기 | — |
| P5b 보안(최소) | SP-10(축소) | ⏳ 대기 | 시크릿 마스킹+접근제어만 |
| P6 응답 DTO | SP-5 | ⏳ 대기 | 마지막, 프론트 계약 병행 |
| (보류) 삭제 시맨틱 | SP-8 | ⛔ 제품결정 필요 | — |

### P1 결과 요약 (2026-07-14)
- **TDD로 확정된 오탐**: "잘못된 enum → 500" 주장 중 실제 500은 `GET /market-credentials/{marketType}`(enum 경로변수 직접 바인딩)뿐. F-PROD-2·F-PSRC-12·F-MREG-3·F-BATCH-B1 은 `String`+`valueOf` 구조라 **이미 400**(기존 IllegalArgumentException 핸들러) — 회귀방지 테스트로 고정.
- **신규 파생 결함**: `BatchController` supplierCode=**null** → `toUpperCase()` NPE → 500 → **P2에서 400으로 해결**(2-7).
- SP-6 는 **성공 경로**만 marketType 채움. 발주확인/취소 **실패 경로**(F-ORD-5·15)는 조회 실패 가능성으로 보류.

### P2 결과 요약 (2026-07-14) — "데이터 주인" 기준 케이스별 정책
- **우리 소유**(유니패스·소싱): 상태 무관 자유 수정. 유니패스 가드 제거(F-ORD-25 종결). 소싱은 END 상태에서도 수정+빈문자열 클리어(이미 동작 — F-S1·S2 정책상 의도로 확정).
- **마켓 소유**(송장): 마켓이 진실 원본. END 상태 송장수정 400 차단(F-H2). terminal(마켓 배송중/완료 잠금)은 일시실패와 구분되는 "동기화로 반영" 메시지로 롤백(F-H1) — 로컬 단독저장 안 함(동기화가 덮음).
- **마켓 미러**: 발주취소 NEW-only(F-ORD-13). **주문삭제 엔드포인트 제거**(F-ORD-34·35·36 무효화). 발주확인/일괄발송 재실행 차단(F-ORD-6·29).
- 프론트: 주문 삭제 UI 제거. **일부 200→400 전환** — 프론트 메시지 표시 검증은 후속.

---

## ★ 시스템성 패턴 (여러 도메인에서 독립 검출 — 최우선 구조 이슈)

개별 결함보다 먼저 볼 것. 한 곳을 고치면 여러 API가 함께 해결되는 **횡단 근본원인**들이다.

- [~] **SP-1 · 비동기 예외 은폐 → 실패가 HTTP 200 성공으로 표면화** — sync 완료(P4a); ProductSync(F-MISC-8)·batch(F-BATCH-2) 후속
  `@Async`/`new Thread` 로 돌린 작업의 예외가 컨트롤러 try/catch·트랜잭션 밖에서 죽어, 응답은 항상 성공.
  근거: F-SYNC-2(CoupangOrderSyncService.java:54), F-SYNC-23(OrderSyncScheduler.java:52-63), F-MISC-8(ProductSyncController.java:40-53), F-BATCH-2(BatchPriceStockService.java:38-93). → 동기화·크롤·배치 전반의 "성공했는데 실제로는 실패" 근원.
- [~] **SP-2 · 상태 저장소가 JVM 로컬 인메모리 → 멀티-JVM 상태 UI 무력화** — sync 상태 DB화 완료(P4a); SSE(F-MISC-16) 후속
  근거: F-SYNC-1(SyncStatusService.java:15, 조회는 api·갱신은 worker), F-SYNC-25(맵 휘발), F-MISC-16(SSE emitter가 api JVM 로컬), F-BATCH-2(재시작 시 PENDING 영구잔류). → `[[deployment-two-jvm-topology]]` 규율(공유상태는 DB+advisory lock) 위반.
- [~] **SP-3 · 일괄/부분 처리의 부분실패 은폐 + 결과 미반영** — order 도메인 완료(P3), 상품(F-PSRC/F-PROD) 후속
  실패 라인이 응답·로그에 안 드러나거나 요청↔결과 매핑 불가.
  근거: F-ORD-30·F-ORD-9·F-ORD-17(발송/발주 부분실패), F-SYNC-3, F-BATCH-A2, F-PSRC-2·F-PSRC-6, F-PROD-12·F-PROD-16.
- [x] **SP-4 · 종료 상태(CANCELED/RETURNED/EXCHANGED)·배송완료 상태 가드 부재** — ✅ P2 완료(정책확정)
  종료된 건에 소싱/송장/유니패스/취소/삭제가 무제한 허용.
  근거: F-S1, F-H2, F-ORD-13·25·29·35, F-PROD-27.
- [ ] **SP-5 · 응답에 도메인 엔티티 직접 노출(수정계열 전반)**
  DTO 없이 `Order`/`OrderLineItem`/`Product`/`Supplier`/`Currency`/`ProcessStatus`/`SyncStatus`/`MarketRegistration` 직렬화 → 도메인 변경에 API 계약 결합, 내부/민감 필드 유출.
  근거: F-ORD-1·7·16·24·28, F-PROD-6, F-SUP-1·LC-1, F-BATCH-S1, F-SYNC-24, F-MREG-4, F-CRED-1·7.
- [x] **SP-6 · 활동로그 marketType 항상 null(해석 가능함에도)** — ✅ P1(성공경로), 실패경로 보류
  마켓 필터·집계에서 이벤트 누락 분류.
  근거: F-ORD-5·15·27·37, F-SYNC-22, F-BATCH-7, (sourcing/shipping F-S6·F-H6).
- [~] **SP-7 · 미존재 id / 잘못된 enum → 404·400 아닌 500 또는 조용한 204** — bad-enum·supplierCode·삭제조용204 해소; 404 시맨틱 잔존
  근거: F-PROD-5·26·29, F-MREG-3, F-CRED-4, F-BATCH-B1, F-PSRC-12, F-ORD-34, F-CAFE(콜백 에러 미처리).
- [ ] **SP-8 · null-skip 병합으로 필드 "삭제" 불가**
  근거: F-S2, F-ORD-23, F-PROD-13. (반대로 F-CRED-8 은 null도 덮어써 기존값 소실 — 정책 불일치)
- [ ] **SP-9 · 서비스 계층 없이 컨트롤러가 Repository/Router 직접 조립**
  트랜잭션·검증·재사용·테스트가 컨트롤러에 결박.
  근거: F-SUP-CS-3·UC-4, F-MREG-6, F-MISC-9.
- [ ] **SP-10 · 인증/접근제어 부재 + `@CrossOrigin("*")` 전역 + 시크릿 평문**
  근거: F-CRED-1·7·2(시크릿 평문 응답/저장), F-MISC-7·13·17(무인증 트리거/SSE/internal), F-SYNC-13(preview PII), F-CAFE-14, F-MISC-3.
- [ ] **SP-11 · 중복 분기/로직(수정 시 N곳 동기화 필요)**
  근거: F-ORD-18(cancel/confirm batch), F-BATCH-3(트리거 4종)·B3, F-PROD-15·19·20(이미지 3경로), F-SYNC-5(마켓 sync 4종)·14, F-CAFE-12.

---

## 🔴 P0 — BUG (데이터 정합·기능 오류, 재현 검증 후 최우선 수정)

> BUG(후보) 다수는 비동기/멀티-JVM 가정에 근거 — **재현 테스트로 확정 후** normalize 사이클에서 수정.

- [x] **F-SYNC-1** · SyncStatusService DB화(sb_market_sync_status) — 두 JVM 상태 공유 · ✅ `059ed79`
- [x] **F-SYNC-2** · 각 @Async sync가 자기 스레드서 markFailed 기록(검증됨: 정확) · ✅ `059ed79`
- [x] **F-SYNC-23** · 스케줄러 조기 markCompleted 제거, 서비스 자기기록 · ✅ `059ed79`
- [ ] **F-SYNC-19** · order-sync · customs 동기 트랜잭션 내 Thread.sleep×배치 → HTTP스레드·DB커넥션 장기점유 · `CustomsOrderSyncService.java:32,70` · 대상 多면 타임아웃·긴 트랜잭션 락
- [ ] **F-BATCH-2** · batch · 배치 중 재시작 시 PENDING 영구잔류(진행중 배치 유실) · `ProcessStatusService.java:52-59` · 완료판정 불가 (↔ `[[deploy-interrupts-running-batch]]`)
- [ ] **F-BATCH-M1** · batch · prices/stocks가 productIds와 index 위치로만 정렬 · `BatchPriceStockService.java:105-106` · 순서 어긋나면 엉뚱한 상품에 적용, 조용히 SUCCESS
- [ ] **F-BATCH-ST1** · batch · `/status`가 findAll 전체 로드 후 메모리 distinct · `ProcessStatusService.java:76` · 이력 누적 시 OOM·지연
- [ ] **F-MISC-8** · product-sync · raw `new Thread` 비동기 — 트랜잭션/예외/풀 이탈 · `ProductSyncController.java:40-53` · 크롤 실패 미포착·스레드 고갈
- [ ] **F-MISC-18** · email-fetch · 재진입/중복처리 방지 없음, 스케줄러와 동시 실행 · `EmailFetchController.java:33`+`OrderSyncScheduler.java:36` · 동일 orderNo 이중처리 → 마켓 중복 송장전송
- [ ] **F-PSRC-14** · product-sourcing · 마켓 publish 성공 후 DB save 실패 시 마켓/DB 불일치(외부 롤백 불가) · `ProductPublishUseCase.java:31,44,62` · 마켓 게시됐으나 DB 등록 없는 고아
- [x] **F-ORD-30** · 일괄발송 BulkShipResult로 부분실패 표면화(응답·로그·UI) · ✅ `ffdaed3`/`dbfefec`
- [ ] **F-SUP-UC-1** · supplier · "생성" API가 기존 통화 환율을 무경고 덮어씀(upsert vs create) · `SupplierController.java:51-52` · 환율 교체 → 정산·매입원가 왜곡
- [ ] **F-CRED-1** · market-credential · 목록 응답 secretKey·accessKey·clientId 평문 · `MarketCredentialDto.java:22-24` · 무인증 API로 전 마켓 시크릿 유출
- [ ] **F-CRED-7** · market-credential · 저장 성공 응답이 방금 저장한 secretKey 평문 반환 · `MarketCredentialService.java:47` · 저장 왕복 전구간 시크릿 노출
- [x] **F-H1** · order(shipping) · terminal/failed 구분 메시지로 해결(마켓이 진실원본 — 롤백 유지, 동기화 반영 안내) · ✅ `dfcf8b3`

---

## 🟠 P1 — GAP (미처리 케이스·검증 누락, 조건부 오동작)

### order (F-ORD / F-S / F-H)
- [~] **F-S1** · 정책확정: 소싱은 종료상태에서도 수정 허용(의도) — 결함 아님
- [x] **F-H2** · 종료상태 송장수정 400 차단 · ✅ `dfcf8b3`
- [ ] **F-H4** · trackingNo/shippingCarrier 필수 검증 부재 · `ShippingUpdateCommand.java:20-27`
- [ ] **F-ORD-2** · 기간 필터 한쪽 날짜만 오면 조용히 무시 · `OrderRepositoryImpl.java:189-194`
- [ ] **F-ORD-5** · 발주확인 실패 로그 marketType null · `OrderController.java:82`
- [x] **F-ORD-6** · 진행/종료분 발주확인 재호출 차단 · ✅ `dfcf8b3`
- [ ] **F-ORD-8** · 마켓 접수 실패를 RuntimeException으로 뭉갬(유형 소실) · `OrderService.java:81-85`
- [x] **F-ORD-9** · 컨트롤러 결과기반 SUCCESS/FAILED 기록 · ✅ `ffdaed3`
- [x] **F-ORD-13** · 발주취소 NEW-only 가드 · ✅ `dfcf8b3`
- [ ] **F-ORD-15** · 발주취소 실패 로그 marketType null · `OrderController.java:126`
- [x] **F-ORD-17** · 컨트롤러 결과기반 SUCCESS/FAILED 기록 · ✅ `ffdaed3`
- [ ] **F-ORD-22** · 라인아이템 없는 주문이 발주확인 전 가드 통과 · `OrderService.java:215`
- [x] **F-ORD-25** · 정책확정: 유니패스는 상태무관 허용(관리용) — 가드 제거·종결 · ✅ `dfcf8b3`
- [ ] **F-ORD-26** · isUnipassDone null이면 무변경인데 200+성공로그 · `OrderService.java:249-251`
- [x] **F-ORD-29** · 일괄발송 SHIPPED/DELIVERED/END 재발송 스킵 · ✅ `dfcf8b3`
- [ ] **F-ORD-31** · 발송 단일 트랜잭션 부분성공 후 예외 시 전체 롤백(마켓엔 발송됨) · `OrderShipService.java:30,60-66`
- [ ] **F-ORD-33** · 발송 orderIds null 시 서비스 NPE · `OrderShipService.java:34`
- [x] **F-ORD-34** · 삭제 엔드포인트 제거로 무효화 · ✅ `dfcf8b3`/`6c396f4`
- [x] **F-ORD-35** · 삭제 엔드포인트 제거로 무효화 · ✅ `dfcf8b3`/`6c396f4`

### order-sync (F-SYNC)
- [ ] **F-SYNC-13** · preview 무인증 노출 + 주문 원시 PII 반환 · `OrderSyncController.java:140-157`
- [ ] **F-SYNC-3** · 비동기 sync가 SUCCESS/FAILED ActionLog 미기록(STARTED만) · `OrderSyncController.java:55`
- [ ] **F-SYNC-6** · 스마트스토어·Cafe24 취소감지 부재 · `SmartStoreOrderSyncService.java:206`
- [ ] **F-SYNC-17** · 정산 동기화 중복 실행 가드 없음 · `CoupangOrderSyncService.java:91-152`
- [ ] **F-SYNC-20** · customs 단일 트랜잭션 → 마지막 배치 예외 시 전체 롤백 · `CustomsOrderSyncService.java:32,60`
- [ ] **F-SYNC-11** · Cafe24 items 개수 불일치 시 첫 상태를 전체 lineItem에 일괄적용 · `Cafe24OrderSyncService.java:201-208`
- [ ] **F-SYNC-12** · Cafe24 PCCC 필드명 미확정, 추출 실패 시 통관번호 누락 · `Cafe24OrderSyncService.java:257-276`
- [ ] **F-SYNC-10** · 11번가 취소감지 orderDate 30일 창 밖 스킵·null 모호 · `ElevenstOrderSyncService.java:238-244`

### product (F-PROD)
- [ ] **F-PROD-27** · 마켓 등록 있어도 상품 하드삭제 → 고아 연동 잔존 · `ProductManageUseCase.java:165-171`
- [ ] **F-PROD-7** · soldOut=null이 조용히 "판매중"으로 처리 · `ProductManageUseCase.java:56-58`
- [ ] **F-PROD-8** · price 음수 검증 부재 · `Product.java:201-210`
- [ ] **F-PROD-11** · 빈/누락 이미지 입력 검증 부재(3경로 공통) · `ProductController.java:117-135`
- [ ] **F-PROD-12** · 개별 리사이즈 실패 조용히 삭제(부분손실) · `ProductController.java:342-344`
- [ ] **F-PROD-16** · 개별 URL 다운로드 실패 불투명 · `ProductController.java:143`
- [~] **F-PROD-2** · ~~500~~ → **오탐: 실제 400** (valueOf→IllegalArgumentException) · `ProductController.java:78`
- [ ] **F-PROD-5** · 미존재 id가 404 아닌 500 · `ProductSearchUseCase.java:27`
- [ ] **F-PROD-18** · 크롤 이미지 URL 유효성/중복 검증 없음 · `ProductController.java:170-172`
- [ ] **F-PROD-22** · 크롤 전량 무선별 다운로드(상한 없음) · `ProductController.java:196-204`
- [ ] **F-PROD-23** · 전체수정 금액·수량 음수 검증 전무 · `Product.java:193-244`
- [ ] **F-PROD-28** · 확인/멱등성 없는 하드딜리트 · `ProductWriterImpl.java:27-28`

### batch (F-BATCH)
- [ ] **F-BATCH-1** · 동시 배치 중복 실행 방지 부재(advisory lock 없음) · `ProcessStatusService.java:23-39`
- [ ] **F-BATCH-4** · 요청 검증 부재(productIds null/빈) · `BatchController.java:44`
- [ ] **F-BATCH-A1** · 전체필드 배치만 마켓 재전송 없음 · `BatchPriceStockService.java:151-176`
- [x] **F-BATCH-A2** · manual-update-all 길이 불일치 → 400 가드 · ✅ `6095f1b`
- [x] **F-BATCH-B1** · bad-enum 이미 400 + null→400 가드 추가로 완결 · ✅ `aad006e`
- [ ] **F-BATCH-S2** · 미존재 batchId도 빈 배열+200(404 아님) · `ProcessStatusService.java:63`
- [ ] **F-BATCH-SM1** · 미존재 batchId와 0% 진행중 동일 응답 · `ProcessStatusService.java:66-72`
- [ ] **F-BATCH-ST2** · `/status` 목록에 정렬·시각·상태 없음 · `ProcessStatusService.java:74-81`

### product-sourcing (F-PSRC)
- [ ] **F-PSRC-1** · urls==null 시 STARTED 로그만 남기고 NPE · `ProductSourcingController.java:42,46`
- [ ] **F-PSRC-2** · iHerb 부분 실패 URL 조용히 누락 · `IherbScraperClient.java:224-242`
- [ ] **F-PSRC-6** · bulk 부분 실패 항목 응답 미반영·SB코드 결번 · `ProductCreateUseCase.java:42-52`
- [ ] **F-PSRC-7** · requests==null 시 컨트롤러 진입부 NPE(로그도 없음) · `ProductSourcingController.java:63,67`
- [ ] **F-PSRC-8** · 이미지 다운로드·R2 업로드를 트랜잭션 안에서 → 장시간 트랜잭션·고아 이미지 · `ProductCreateUseCase.java:30,45,67-68`
- [~] **F-PSRC-12** · ~~500~~ → **오탐: 실제 400** · `ProductSourcingController.java:87` (로그없이 처리되는 점은 잔존)
- [ ] **F-PSRC-13** · 게시 멱등성 부재 — 재호출 시 MarketRegistration 중복 생성 · `ProductPublishUseCase.java:53-62`

### supplier (F-SUP)
- [ ] **F-SUP-UC-2** · exchangeRate 값 검증 부재(0·음수·과대) · `SupplierController.java:49-52`
- [ ] **F-SUP-CS-1** · supplierCode/Name 입력 검증 부재 · `SupplierController.java:35-40`
- [ ] **F-SUP-CS-2** · 중복 supplierCode 사전검증 없이 DB unique 예외 의존 · `SupplierController.java:39-40`
- [ ] **F-SUP-UC-3** · currencyCode 형식/길이 검증 부재 · `SupplierController.java:49-52`
- [ ] **F-SUP-2** · RecordStatus 필터 부재로 ARCHIVED/DELETED 공급사까지 조회 · `SupplierRepository.java:7`

### market-credential (F-CRED)
- [ ] **F-CRED-2** · 저장 시 암호화 부재 → DB 평문 저장 · `MarketCredential.java:39-58`
- [x] **F-CRED-4** · 미정의 marketType → 400 아닌 500 · `MarketCredentialController.java:37-38` — ✅ `60b02fe`
- [ ] **F-CRED-8** · PUT이 부분업데이트 아닌 4필드 전체 덮어쓰기(null도) · `MarketCredentialService.java:41-44`
- [ ] **F-CRED-9** · 자격증명 입력 검증 전무(빈 값 저장) · `MarketCredentialService.java:34-48`

### cafe24-auth (F-CAFE)
- [ ] **F-CAFE-5** · issue-token OAuth state 미발급·미검증(CSRF) · `Cafe24TokenManager.java:128`
- [ ] **F-CAFE-10** · 콜백이 OAuth state 미수신·미검증(CSRF) · `Cafe24AuthController.java:129-131`
- [ ] **F-CAFE-11** · 콜백 에러 파라미터(error/error_description) 미처리 · `Cafe24AuthController.java:130`
- [ ] **F-CAFE-6** · 재인증 동시성 락 부재 → 자동갱신과 경쟁 시 refresh_token 무효화 · `Cafe24TokenManager.java:132-144`
- [ ] **F-CAFE-2** · /status 모든 실패를 200으로 감싸 HTTP로 오류 구분 불가 · `Cafe24AuthController.java:51-71`

### market-registration (F-MREG)
- [ ] **F-MREG-1** · POST /sync인데 자사 DB 미갱신(읽기전용) — 이름·의미 불일치 · `MarketRegistrationController.java:50-69`
- [ ] **F-MREG-2** · 미존재 productId도 빈 리스트+200(존재검증 없음) · `MarketRegistrationController.java:33`
- [~] **F-MREG-3** · bad-enum은 **이미 400**(오탐); 404 시맨틱 구분 부재는 잔존 · `MarketRegistrationController.java:43-59`
- [ ] **F-MREG-5** · vendorItemId 부재 시 productId로 폴백해 마켓 API 오조회 · `MarketRegistrationController.java:61-64`
- [ ] **F-MREG-7** · ESM(GMARKET/AUCTION) 어댑터 미존재로 sync/local 배제 · `MarketClientRouter.java:19-25`

### misc (F-MISC)
- [ ] **F-MISC-7** · 공개 sync/stock 인증·중복실행·동시성 가드 부재 · `ProductSyncController.java:33,40`
- [ ] **F-MISC-17** · /internal/email/fetch 접근제어가 "포트 비노출" 관례 의존 · `EmailFetchController.java:22,29`
- [ ] **F-MISC-20** · fetch 응답이 실제 결과 미반영, 항상 ok:true · `EmailFetchController.java:34-38`
- [ ] **F-MISC-12** · SSE emitter 누수(heartbeat 없음+24h 타임아웃) · `SseNotificationController.java:26,69,90`
- [ ] **F-MISC-13** · SSE 인증·구독자 수 상한 없음 · `SseNotificationController.java:23,29`
- [ ] **F-MISC-21** · IMAP "최근 200건 제목 contains" 의존 · `EmailFetcherService.java:112,127`
- [ ] **F-MISC-4** · common/codes 노출 Enum 하드코딩 4종뿐 · `CommonCodeController.java:30-33`

---

## 🟡 P2 — SMELL (동작하나 유지보수 위험: 중복·죽은코드·책임배치)

### order
- [ ] **F-ORD-1** 조회 응답 도메인 엔티티 노출 · `OrderDetailDto.java:14-23`
- [ ] **F-ORD-7 / 16 / 24 / 28** 발주확인/취소/수정/유니패스 응답 엔티티 직접 노출 · `OrderController.java:72,116,160,182`
- [ ] **F-ORD-10 / 19** confirm/cancel-batch 컨트롤러 catch 죽은코드 · `OrderController.java:105-108,149-152`
- [ ] **F-ORD-18** cancel-batch/confirm-batch 구조 통째 중복 · `OrderService.java:171-195,107-131`
- [ ] **F-ORD-21** `OrderUpdateCommand.toCustomsData()` 미사용 죽은코드 · `OrderUpdateCommand.java:14-19`
- [ ] **F-ORD-32** 정산 상수 0.89 서비스 하드코딩 · `OrderShipService.java:81`
- [x] **F-ORD-37** 삭제 로그 marketType null(조회로 채울 수 있었음) · `OrderController.java:275` — ✅ `6e320e0`
- [ ] **F-S3** sourcing 두 분기 applySourcingData+save 중복 · `OrderService.java:277,285`
- [ ] **F-S5** sourcing 응답 OrderLineItem 직접 노출 · `OrderController.java:204`
- [ ] **F-H3** shipping PURCHASED/else 분기 거의 동일 중복 · `OrderService.java:318-350`
- [ ] **F-H5** `markSentIfSucceeded` isFailed 분기 도달불가 죽은코드 · `OrderService.java:548-551`

### order-sync
- [ ] **F-SYNC-5** 4개 마켓 sync upsert 골격 중복 · `CoupangOrderSyncService.java:168-293`
- [ ] **F-SYNC-8** 스마트스토어·11번가 송장병합에 trackingSentToMarket 보존 가드 없음(쿠팡과 비대칭) · `SmartStoreOrderSyncService.java:116-127`
- [ ] **F-SYNC-4** 정산 수수료율 0.89 하드코딩 · `CoupangOrderSyncService.java:276`
- [ ] **F-SYNC-14** root cause 추출 3곳 복붙 · `OrderSyncController.java:150-169`
- [ ] **F-SYNC-15** carriers 실패 로그가 "주문 프리뷰 실패"로 오기재 · `OrderSyncController.java:165`
- [ ] **F-SYNC-21** customs 배치크기 30·딜레이 1000ms 매직넘버 · `CustomsOrderSyncService.java:54,71`
- [ ] **F-SYNC-24** /status 응답이 내부 정적클래스 직접 노출 · `OrderSyncController.java:225`

### product
- [ ] **F-PROD-1** marketFilter·keyword 배타, keyword 무시 · `ProductController.java:75-82`
- [ ] **F-PROD-6** 상세응답 도메인 VO 직접 노출 · `ProductDetailResponse.java:22-33`
- [ ] **F-PROD-10 / 14** price-stock·images가 26필드 커맨드 1~2칸만 채움(위치기반 오배치 위험) · `ProductManageUseCase.java:49-84`
- [ ] **F-PROD-15** images/by-url 본문로직 완전중복 · `ProductController.java:117-155`
- [ ] **F-PROD-19 / 20** 크롤 앞·뒷단이 crawl-and-upload와 중복(최대 3곳) · `ProductController.java:163-209`
- [ ] **F-PROD-24** 전체수정 로그가 변경필드 정보 없음 · `ProductController.java:226-227`

### batch
- [ ] **F-BATCH-3** 트리거 4종 로직 중복 · `BatchController.java:41-120`
- [ ] **F-BATCH-5** startBatch 상품별 1행씩 개별 save · `ProcessStatusService.java:26-36`
- [ ] **F-BATCH-M2** 수동 경로엔 crawl의 sleep(500) rate-limit 없음 · `BatchPriceStockService.java:83`
- [ ] **F-BATCH-M3** 변경없음 판정 price=equals vs stock=status 비대칭 · `BatchPriceStockService.java:112-113`
- [ ] **F-BATCH-A3** 배치 3종 부수효과·완충 정책 제각각 · `BatchPriceStockService.java:38/95/151`
- [ ] **F-BATCH-B3** by-supplier가 crawl-and-update와 거의 동일 · `BatchController.java:97-120`
- [ ] **F-BATCH-S1** 도메인 엔티티 ProcessStatus 직접 노출 · `BatchController.java:123`

### product-sourcing
- [ ] **F-PSRC-3** 대량 URL 소싱 순차·블로킹 → 톰캣 스레드 장기점유 · `IherbScraperClient.java:227-240`
- [ ] **F-PSRC-9** SB코드 로컬증가가 실패/동시배치 시 결번/중복 · `ProductReaderImpl.java:51-60`
- [ ] **F-PSRC-10** 컨트롤러가 core 도메인 타입 FQCN 인라인 참조 · `ProductSourcingController.java:63,69`
- [ ] **F-PSRC-15** 마켓 미지원 검증이 hasClient/getClient 이중 · `ProductPublishUseCase.java:36-43`

### supplier
- [ ] **F-SUP-1** 응답 Supplier 엔티티 노출 + LAZY currency 유출 위험 · `SupplierController.java:30`
- [ ] **F-SUP-CS-3** 등록 로직이 서비스 없이 컨트롤러에 직접 · `SupplierController.java:37-40`
- [ ] **F-SUP-UC-4** 통화 등록 서비스 부재 + 트랜잭션 경계 없음 · `SupplierController.java:48-53`
- [ ] **F-SUP-LC-1** 응답 Currency 엔티티 직접 노출 · `SupplierController.java:44`

### market-credential
- [ ] **F-CRED-10** 저장 실패 활동로그에 e.getMessage() 원문 노출 · `MarketCredentialController.java:57`

### cafe24-auth
- [ ] **F-CAFE-8** 인가코드 URL 미인코딩·OAuth 오류본문 무제한 로그 · `Cafe24TokenManager.java:137-139`
- [ ] **F-CAFE-12** 콜백/issue-token 중복이나 활동로그·응답 비대칭 · `Cafe24AuthController.java:88-138`
- [ ] **F-CAFE-13** 인가코드가 GET 쿼리스트링으로 access.log 노출 · `Cafe24AuthController.java:130`
- [ ] **F-CAFE-1** /status가 외부호출 2~3회 유발(무거운 헬스체크) · `Cafe24AuthController.java:50-60`
- [ ] **F-CAFE-3** 상품 실패를 무조건 "토큰 만료/무효"로 단정 · `Cafe24AuthController.java:51-55`

### market-registration
- [ ] **F-MREG-4** 응답 도메인 엔티티(+원시 JSON 식별자) 노출 · `MarketRegistrationController.java:31,38`
- [ ] **F-MREG-6** 컨트롤러가 Repository·Router·도메인 직접 조립 · `MarketRegistrationController.java:27-67`

### misc
- [ ] **F-MISC-19** 수동 트리거가 스케줄러 우회 → SyncStatus 미기록 · `EmailFetchController.java:33`
- [ ] **F-MISC-22** IMAP 다계정×다주문 N×M 연결·조기종료 부재 · `EmailFetcherService.java:67-78`
- [ ] **F-MISC-9** ProductSync 컨트롤러가 레포지토리·대상선정 직접 · `ProductSyncController.java:29,42-49`
- [ ] **F-MISC-14** SSE push 실패=remove뿐, 재전송/Last-Event-ID 없음 · `SseNotificationController.java:69,90`
- [ ] **F-MISC-15** SSE 두 리스너 브로드캐스트 로직 중복 · `SseNotificationController.java:55-92`
- [ ] **F-MISC-1** action-logs 페이징 없이 PageRequest를 limit 상한으로만 · `ActionLogService.java:47`
- [ ] **F-MISC-5** EnumMapperValue 가변 클래스(record 아님) · `EnumMapperValue.java:7-8`

---

## 🔵 P3 — NOTE (의도 확인 필요 / 개선 여지)

### order
- [ ] **F-ORD-3** shippingStatuses 필터가 EXISTS라 주문 단위 부분매칭 · `OrderRepositoryImpl.java:139-150`
- [ ] **F-ORD-4** 조회 API 활동로그 미기록(8개와 비대칭) · `OrderController.java:60-66`
- [ ] **F-ORD-11 / 20** confirm/cancel-batch 요청 DTO 없이 Map 직접 바인딩 · `OrderController.java:90-92,134-136`
- [ ] **F-ORD-12** 일괄 발주확인 부분실패가 200 반환 · `OrderController.java:104`
- [ ] **F-ORD-14** 쿠팡 등은 취소가 마켓에 미전파(로컬 only) · `OrderService.java:144-152`
- [ ] **F-ORD-23** null-skip 병합으로 주소/통관번호 삭제 불가 · `OrderService.java:220,225`
- [x] **F-ORD-27** 유니패스 로그 marketType 성공경로도 null · `OrderController.java:192` — ✅ `6e320e0`
- [x] **F-ORD-36** 삭제 엔드포인트 제거로 무효화 · ✅ `dfcf8b3`
- [x] **F-S2** · 정책확정: 빈문자열로 클리어 가능(이미 동작) — 회귀테스트 추가 · ✅ `dfcf8b3`
- [ ] **F-S4** 소싱 금액 필드 검증 부재 · (SourcingUpdateRequest)
- [x] **F-S6** 소싱 활동로그 marketType null — ✅ `6e320e0`
- [~] **F-H6** marketType null은 해결(`6e320e0`); 응답 엔티티 노출(SP-5)은 P6 잔존 · `OrderController.java:225,234`

### order-sync
- [ ] **F-SYNC-9** 스마트스토어 resolveProductId가 sbCode 직접매핑만 · `SmartStoreOrderSyncService.java:187-193`
- [ ] **F-SYNC-7** Cafe24 offset 상한 15000 초과 시 조용히 절단 / 기간 30일 하드코딩 · `Cafe24OrderSyncService.java:101`
- [ ] **F-SYNC-16** preview·carriers 응답 ResponseEntity<Object> 원시 JsonNode · `OrderSyncController.java:141,161`
- [ ] **F-SYNC-18** 정산: sbCode 미보유·미배송 lineItem 조용히 누락 · `CoupangOrderSyncService.java:120-131`
- [ ] **F-SYNC-22** customs ActionLog marketType=null(전 마켓 공통, 의도적) · `OrderSyncController.java:201`
- [x] **F-SYNC-25** DB 영속화로 재시작에도 상태 보존 · ✅ `059ed79`

### product
- [ ] **F-PROD-3** 조회계열 활동로그 미기록 · `ProductController.java:66-94`
- [ ] **F-PROD-4** marketMap 폴백(productId)과 실코드 미구분 · `ProductController.java:315-318`
- [ ] **F-PROD-9** price.intValue() 소수점 절사 후 전송 · `ProductManageUseCase.java:63`
- [ ] **F-PROD-13** 빈 이미지 리스트로 삭제 불가 · `Product.java:272-275`
- [ ] **F-PROD-17** 빈결과/크롤실패 구분(설계 모범 — 유지 권장) · `ProductController.java:161-176`
- [ ] **F-PROD-21** 소싱없음/0개/업로드완료 동일 액션타입 · `ProductController.java:190-206`
- [ ] **F-PROD-25** 전체수정이 마켓에 전파 안 됨 · `ProductManageUseCase.java:156-163`
- [ ] **F-PROD-26 / 29** 전체수정·삭제 미존재 id 500(F-PROD-5 동형) · `ProductManageUseCase.java:159,168`

### batch
- [ ] **F-BATCH-6** couponRate가 crawl 경로에서 수집만 되고 미사용 · `BatchController.java:56`
- [ ] **F-BATCH-7** 활동로그 STARTED/완료 이원화, marketType null · `BatchController.java:51`
- [ ] **F-BATCH-B2** 대상 0건과 진행의 응답 스키마 비대칭 · `BatchController.java:103,119`
- [ ] **F-BATCH-S3** `/status/{batchId}` 페이지네이션·필터 없음 · `ProcessStatusService.java:63`
- [ ] **F-BATCH-SM2** percent가 완료율이지 성공률 아님 · `BatchSummary.java:17`
- [ ] **F-BATCH-SM3** SUCCESS/FAILED 2쿼리, PENDING은 뺄셈 유도 · `ProcessStatusService.java:68-70`
- [ ] **F-BATCH-ST3** status 조회 3종 계약·미존재 처리 제각각 · `BatchController.java:122-138`

### product-sourcing
- [ ] **F-PSRC-4** ProductInfoCrawlerPort가 iHerb 단일 구현 종속 · `IherbScraperClient.java:263`
- [ ] **F-PSRC-5** iHerb 입력 검증 전무(URL 형식·개수·중복) · `ProductSourcingController.java`
- [ ] **F-PSRC-11** bulk 입력 검증 부재(금액 음수·빈 목록·상한) · `ProductSaveRequest.java`
- [ ] **F-PSRC-16** identifiers JSON 직렬화 실패를 "{}"로 삼켜 식별자 유실 · `ProductPublishUseCase.java:47-51`
- [ ] **F-PSRC-17** MarketRegistration의 productId·sbProductId에 동일 값 주입 · `ProductPublishUseCase.java:54-55`

### supplier
- [ ] **F-SUP-3** 활동로그(ActionLog) 미기록 · `SupplierController.java:26-27`
- [ ] **F-SUP-CS-4** createSupplier 트랜잭션 경계 없음 · `SupplierController.java:34`
- [ ] **F-SUP-CS-5** createSupplier 엔티티 노출 + 로그 미기록 · `SupplierController.java:35`
- [ ] **F-SUP-UC-5** createCurrency 엔티티 노출 + 환율변경 로그 미기록 · `SupplierController.java:49`
- [ ] **F-SUP-4** 공급사 목록 정렬·페이징 부재 · `SupplierController.java:31`
- [ ] **F-SUP-LC-2** 통화 목록 정렬 부재 · `SupplierController.java:45`
- [ ] **F-SUP-LC-3** Currency 소프트삭제/삭제 API 부재 · `Currency.java:16`

### market-credential
- [ ] **F-CRED-3** 목록 조회 API 활동로그 미기록 · `MarketCredentialController.java:31-34`
- [ ] **F-CRED-5** UNKNOWN이 유효 enum이라 조회/저장 대상 · `MarketType.java:16`
- [ ] **F-CRED-6** 404가 빈 바디 반환, 프론트 파싱과 비대칭 · `MarketCredentialController.java:40`

### cafe24-auth
- [ ] **F-CAFE-14** 인증 컨트롤러에 CORS 전체허용 · `Cafe24AuthController.java:23`
- [ ] **F-CAFE-7** 인가코드 재사용 방지를 Cafe24측에만 의존 · `Cafe24AuthController.java:81-86`
- [ ] **F-CAFE-9** extractCode 첫 code=만·인코딩 미복원·error 미인지 · `Cafe24AuthController.java:109-124`
- [ ] **F-CAFE-4** getBaseUrl null이어도 방어없이 외부호출 조립 · `Cafe24RestClient.java:20-23`

### misc
- [ ] **F-MISC-16** SSE emitters가 api JVM 로컬 — worker 이벤트 미도달 · `SseNotificationController.java:21`
- [ ] **F-MISC-10** sync/stock 응답 메시지와 실동작 불일치 · `ProductSyncController.java:42-57`
- [ ] **F-MISC-11** sync/stock 응답 ResponseEntity<?>+Map.of 애드혹 · `ProductSyncController.java:34,56`
- [ ] **F-MISC-2** action-logs limit 상하한 방어가 서비스에만 · `ActionLogController.java:27`
- [ ] **F-MISC-3** `@CrossOrigin("*")` 전역 허용(공통) · `ActionLogController.java:20`
- [ ] **F-MISC-6** common/codes 캐시·Cache-Control 없음 · `CommonCodeController.java:38-42`

---

## 다음 단계 참조

1. **시스템성 패턴(SP-1~11) 우선 검토** — 개별 결함보다 근본원인. 특히 SP-1(비동기 예외 은폐)·SP-2(JVM 로컬 상태)는 여러 🔴를 동시 해소.
2. **🔴 P0는 재현 테스트로 확정 후** `sbshop-normalize`(Red→Green) 사이클로 이관. BUG(후보)는 비동기/멀티-JVM 가정 검증 필수.
3. 🔴/🟠 중 확정분은 `docs/normalize/defect-ledger.md` 로 승격 등재 가능.
4. 각 항목 상세(근거·영향·제안·다이어그램)는 해당 API 문서 §7 참조.
