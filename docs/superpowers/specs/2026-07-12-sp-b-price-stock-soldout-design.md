# SP-B 설계 — 가격/재고·품절 정식 재설계

- 작성일: 2026-07-12
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-B (완성도 로드맵 2순위, 도메인 B ~45% — 가장 낮은 축 + P0 데이터 리스크)
- 선행: [[deployment-two-jvm-topology]], SP-A 완료(main `bf2ab8f`)

---

## 1. 문제 정의

상품관리의 가격/재고 수정은 과거 **품절=재고0, 판매중=임의값(333/500)** 관습을 쓴다. 그러나 일부 마켓은 수량 0 입력 시 오류가 나서, **재고는 1 이상으로 두고 품절은 별도 판매상태로 처리**해야 한다. 현재 코드의 구조적 결함:

- `StockStatus`(IN_STOCK/OUT_OF_STOCK)는 배치 크롤에서 정확히 계산되나(`BatchPriceStockService.java:67`, `product.updateStockStatus`), **마켓 동기화 레이어로 전달되지 않는다** — `ProductMarketSyncService.syncPriceStock(productId, price, stock)`(`:32-72`)가 raw `stock`만 넘긴다.
- 그 결과 stock=0이 쿠팡(`CoupangMarketClient.java:161-179` `quantities/0`)·스마트스토어(`SmartstoreMarketClient.java:104-136`)·Cafe24(`Cafe24MarketClient.java:112-142` `supply_quantity:"0"`)에 **raw 전송**된다(오류 위험).
- 11번가만 이미 올바르다 — stock=0이면 `stopdisplay`, >0이면 `restartdisplay`(`ElevenstMarketClient.java:72-97`).
- 도메인은 `Product.createLogisticsInfo`(`Product.java:374`)에서 `stock = isAvailable ? 999 : 0`로 품절=0 관습을 유지한다.
- 프론트 모달(`ProductPage.tsx:397-425`)은 수량 InputNumber(min 없음)만 있고 품절 토글이 없다.

`StockStatus` enum: `IN_STOCK`, `OUT_OF_STOCK` (`core/.../domain/product/enums/StockStatus.java`).
포트: `MarketClient.syncPriceAndStock(String marketItemId, Map<String,Object> currentRawData, Integer price, Integer stock)` (`MarketClient.java:19-23`).

---

## 2. 목표 & 성공 기준

- 어떤 마켓에도 **수량 0이 전송되지 않는다**(항상 ≥1로 클램프).
- 품절/판매중이 각 마켓의 **판매상태 API**로 정확히 반영된다.
- `StockStatus`가 크롤·수동 두 경로 모두에서 마켓 동기화까지 관통한다.
- 도메인이 품절을 재고0으로 인코딩하지 않는다.
- 프론트 모달에서 판매중/품절을 명시적으로 토글한다.

---

## 3. 설계

### 3.1 soldOut 디렉티브 관통 (핵심)

**포트 확장** (`MarketClient.java`):
```
Map<String,Object> syncPriceAndStock(String marketItemId, Map<String,Object> currentRawData,
                                     Integer price, Integer quantity, boolean soldOut)
```
- `ProductMarketSyncService`가 중앙에서 계산: `quantity = soldOut ? 1 : DEFAULT_IN_STOCK_QTY`, `soldOut = (stockStatus == OUT_OF_STOCK)`.
- `DEFAULT_IN_STOCK_QTY = 999` (기존 create 기본값과 일치, 상수화).
- 각 클라이언트가 자기 마켓 메커니즘 적용:

| 마켓 | 판매중(soldOut=false) | 품절(soldOut=true) | 근거 |
|---|---|---|---|
| 11번가 | `restartdisplay` | `stopdisplay` | 기존 로직을 stock==0 대신 **soldOut 기준**으로 변경 (`ElevenstMarketClient.java:72-97`) |
| 스마트스토어 | `stockQuantity=999, status:"SALE"` | `stockQuantity=1, status:"OUTOFSTOCK"` (명시 세팅 신규) | `SmartstoreMarketClient.java:104-136` |
| 쿠팡 | `quantities/999` + 판매재개 | `quantities/1` + **판매중지 API** (신규, 실API 검증) | `CoupangMarketClient.java:161-179` |
| Cafe24 | `supply_quantity:"999", selling:"T"` | `supply_quantity:"1", selling:"F"` (신규, 실API 검증) | `Cafe24MarketClient.java:112-142` |

GMARKET/AUCTION은 클라이언트 없음 → 스킵(기존 동작 유지).

### 3.2 도메인 변경 (`Product.java`)
- `createLogisticsInfo`(`:374`): `stock = isAvailable ? 999 : 0` → **항상 `DEFAULT_IN_STOCK_QTY(999)`**, 별도로 `stockStatus = isAvailable ? IN_STOCK : OUT_OF_STOCK` 설정. 품절=재고0 관습 제거.
- 수동 수정 흐름이 `stockStatus`를 갱신하도록 경로 확보(`updateStockStatus` 기존 메서드 활용).

### 3.3 업데이트 경로 변경
- `PUT /api/v1/products/{id}/price-stock` 바디: `PriceStockUpdateRequest(BigDecimal price, Integer stock)` → **`(BigDecimal price, boolean soldOut)`**.
- `ProductManageUseCase.updatePriceStock(Long productId, BigDecimal price, boolean soldOut)`: price 갱신 + `stockStatus = soldOut ? OUT_OF_STOCK : IN_STOCK` 갱신 → `productMarketSyncService.syncPriceStock(productId, price, stockStatus)`.
- `ProductMarketSyncService.syncPriceStock(Long productId, Integer price, StockStatus stockStatus)`: soldOut·quantity 계산 후 각 클라이언트에 전달. 반환은 기존 `MarketRepublishResult` 유지(마켓별 부분 성공/실패 보고).
- **배치/크롤 경로 관통**: `BatchPriceStockService`가 이미 계산한 `StockStatus`(`:67`)를 sync로 전달(현재 누락분 해소). `manualUpdatePriceStock`도 동일.

### 3.4 프론트 (`ProductPage.tsx`)
- 가격/재고 모달: 수량 InputNumber(`:420`) 제거 → **판매중/품절 토글**(antd `Switch` 또는 `Segmented`) + 가격 InputNumber(min 0). 제출 `productApi.updatePriceStock(id, price, soldOut)`.
- 그리드/모달에 판매중/품절 상태 표시(기존 `stockStatus` 노출).

---

## 4. 에러 처리

- 마켓별 판매상태 API 실패는 **은폐하지 않고**(SP-A 원칙) `MarketRepublishResult`의 실패로 표면화, 활동로그에 사유 기록(D-077 경로 재사용).
- 신규 판매중지/재개 API(쿠팡·Cafe24) 미검증분은 실패 시 root cause를 메시지에 담아 반환.

---

## 5. 테스트 전략 (TDD Red→Green)

1. **도메인**: `Product.create`가 stock=999(≠0) + stockStatus=IN_STOCK/OUT_OF_STOCK 설정.
2. **서비스**: `syncPriceStock`이 soldOut→quantity 1, 판매중→999를 계산하고, 등록된 각 마켓 클라이언트에 `(price, quantity, soldOut)` 전달; 마켓별 부분실패가 `MarketRepublishResult`에 집계.
3. **각 MarketClient 특성화**(Mock HTTP): 품절 시 올바른 판매상태 API/바디 호출, 판매중 시 selling+수량. 특히 스마트스토어 `status:OUTOFSTOCK` 명시, 11번가 soldOut 기준 stopdisplay, 쿠팡 판매중지, Cafe24 `selling:F`.
4. **컨트롤러**: 새 요청 바디 `(price, soldOut)` 매핑.
5. **프론트**: 모달 토글 렌더 + `{price, soldOut}` 제출; `tsc -p tsconfig.app.json` 0, `npm run build` 0.

로컬 Docker-off 제약: Mock HTTP(MockRestServiceServer/Mockito)로 커버, 실 API 동작은 라이브 확인.

---

## 6. 범위 밖 (명시)

- 마켓별 실 재고수량 추적(판매중/품절 이분법 + 고정수량으로 대체). iHerb 크롤 실 stock은 DB 참고용 유지.
- restockDate UI(D-063/D-065는 별도 결함).
- 이미지/상세설명 재게시(SP-C).
- DDL: `stock_status`/`stock` 컬럼 기존 존재 → 변경 불필요.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `core/.../domain/market/MarketClient.java` | 포트 시그니처 확장(quantity+soldOut) |
| `core/.../application/product/ProductMarketSyncService.java` | soldOut·quantity 계산·전달, 시그니처 변경 |
| `core/.../application/product/ProductManageUseCase.java` | updatePriceStock(price, soldOut), stockStatus 갱신 |
| `core/.../application/product/BatchPriceStockService.java` | 크롤 StockStatus를 sync로 전달 |
| `core/.../domain/product/Product.java` | createLogisticsInfo 999 고정 + stockStatus |
| `infrastructure/.../coupang/CoupangMarketClient.java` | 판매중지/재개 + quantity≥1 |
| `infrastructure/.../smartstore/SmartstoreMarketClient.java` | status OUTOFSTOCK 명시 + quantity≥1 |
| `infrastructure/.../elevenst/ElevenstMarketClient.java` | soldOut 기준 stop/restart |
| `infrastructure/.../cafe24/Cafe24MarketClient.java` | selling T/F + quantity≥1 |
| `api/.../ProductController.java` + `PriceStockUpdateRequest` | 요청 바디 (price, soldOut) |
| `frontend/src/pages/ProductPage.tsx` + `productApi.ts` | 품절 토글, 제출 형태 |
| 신규 테스트 (core/infrastructure/frontend) | 위 5개 축 |

---

## 8. 검증/배포

- 코드 게이트: `:core:test`, `:infrastructure:test`, `:api:test`, 프론트 `tsc`/`build`.
- 라이브 확인(배포 후, 사용자 허가): 각 마켓에서 품절 토글 시 판매상태가 실제 판매중지로 반영되는지, 수량 0 미전송, 판매중 복귀 정상. 쿠팡·Cafe24·스마트스토어 신규 판매상태 API 실동작 확인.
- push/배포는 사용자 확인 후.
