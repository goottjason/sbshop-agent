# 완전 상품 삭제 (마켓 연동 포함) — 설계

작성: 2026-07-16 · 대상 결함: F-PROD-27·28 (마켓 등록 있는 상품 하드삭제 시 고아 연동 잔존)

## 배경·목표

현재 `DELETE /api/v1/products/{id}`(`ProductManageUseCase.deleteProduct`)는 **Product 행만 삭제**하고 연동
마켓(sb_market_registration) 및 실제 마켓 리스팅은 손대지 않는다. 그 결과 (a) 등록행이 고아로 남고,
(b) 쿠팡/스마트스토어/11번가/Cafe24에 상품 리스팅이 우리 시스템과 연결 끊긴 채 잔존한다.

**목표:** 삭제 시 **연동된 각 외부 마켓의 상품 삭제 API를 호출**해 실제 리스팅을 제거하고, 이어서
MarketRegistration 행과 Product를 삭제하는 **완전 삭제**. 기존 삭제 엔드포인트를 이 동작으로 일원화한다.

## 사용자 결정 (확정)

- **트리거**: 기존 `DELETE /api/v1/products/{id}`가 곧 완전삭제(별도 액션 없음). **동기** 수행(결과까지 대기).
- **부분실패 정책**: **C — best-effort**. 마켓 삭제가 실패해도 등록행·Product는 **삭제 진행**. 실패 마켓은
  응답 리포트 + ActionLog에 남겨 수동 정리 가능하게 한다(등록행이 지워지므로 이것이 유일한 추적 흔적).

## 흐름

```
DELETE /api/v1/products/{id}
1. 상품 조회 — 없으면 404(ResourceNotFoundException, 기존)
2. 연동 MarketRegistration 전체 조회
3. [트랜잭션 밖] 마켓별 deleteFromMarket(marketItemId) best-effort 호출 → 성공/실패/스킵 수집
4. [짧은 @Transactional] 등록행 전부 삭제 + Product 삭제
5. 200 + 결과 리포트 반환
```

외부 마켓 API(파괴적·장시간)는 **트랜잭션 밖**에서 호출한다(F-PSRC-8 패턴). DB 삭제만 짧은 트랜잭션.
순서상 마켓 삭제(3)를 먼저 시도한 뒤 DB 삭제(4)를 하며, best-effort라 3의 실패가 4를 막지 않는다.

## 컴포넌트

### 1. `MarketClient.deleteFromMarket(String marketItemId)` (인터페이스 신설)
- 반환 void, 실패 시 예외(오케스트레이터가 try/catch로 마켓별 수집 — `ProductMarketSyncService` 패턴 동형).
- 구현 4종:
  - **Cafe24** `Cafe24MarketClient`: `cafe24RestClient.delete("/admin/products/" + marketItemId)` (기존 delete 인프라).
  - **쿠팡** `CoupangMarketClient`: `CoupangRestClient.delete(...)` (기존 delete 인프라)로 상품/벤더아이템 삭제 경로 호출.
  - **스마트스토어** `SmartstoreMarketClient`: `SmartstoreRestClient`에 `delete` 헬퍼 추가 후 상품 삭제 API 호출.
  - **11번가** `ElevenstMarketClient`: `ElevenstRestClient`에 `delete` 헬퍼 추가 후 상품 삭제 API 호출.
  - 각 마켓 실제 삭제 규칙 상이(주문이력 있으면 하드삭제 거부 가능) → 그 경우 API가 오류 반환 → 예외 →
    실패로 수집되고 best-effort로 DB 삭제는 진행.

### 2. 삭제 오케스트레이터 (`ProductManageUseCase.deleteProduct` 확장)
- 3~4단계 조율. 마켓 API(외부)와 DB 삭제(tx) 분리는 짧은 tx 서비스(기존 `ProductPersistTxService` 계열 또는
  신규 최소 tx 메서드)로. 등록행 삭제는 `MarketRegistrationRepository`, Product 삭제는 `ProductWriter`.
- 클라이언트 없는 마켓(GMARKET/AUCTION, `router.hasClient=false`): API 스킵, 등록행은 삭제(skipped로 보고).

### 3. 결과 리포트 (신규 DTO, 예: `ProductDeleteResult`)
- `{ deleted: List<MarketType>, skipped: List<MarketType>, failed: Map<MarketType,String> }`.
- 엔드포인트는 **항상 200 + 이 바디** 반환(best-effort라 일부 실패도 Product는 삭제됨).
- 프론트 삭제 핸들러는 현재 void(200) 기대 → **바디 추가는 비파괴**(무시하거나 실패 마켓 표시). 구현 시 프론트 확인.

### 4. 관측성
- 삭제 완료 시 `ActionLog(PRODUCT_DELETE)` 기록 — 삭제/스킵/**실패 마켓+marketItemId** 포함(수동 정리 근거).

## 에러 처리
- 상품 미존재: 404(기존).
- 마켓 삭제 실패: 예외 catch → failed에 수집, **DB 삭제 계속**(C). 절대 Product 삭제를 막지 않음.
- DB 삭제 실패(등록행/Product): 이 단계는 tx라 롤백 — 예외 표면화(500). (마켓은 이미 삭제됐을 수 있어 로그로 표면화.)

## 테스트
- **각 어댑터 `deleteFromMarket`**: 정상 시 올바른 DELETE 경로 호출(mock RestClient verify), 마켓 오류 시 예외 전파.
- **오케스트레이터 `deleteProduct`**:
  - 전 마켓 성공 → 등록행·Product 삭제, deleted에 전 마켓.
  - 일부 마켓 실패 → **Product·등록행 여전히 삭제**, failed에 실패 마켓+사유(best-effort 검증).
  - 클라이언트 없는 마켓 → API 미호출, skipped 보고, 등록행 삭제.
  - 외부 마켓 호출이 DB 트랜잭션 밖(InOrder: 마켓삭제 → DB삭제).
  - ActionLog에 실패 마켓 기록.
- 기존 회귀(deleteProduct 404, 다른 ProductManage 경로) 무손상.

## 비목표 (Out of scope)
- 실패 마켓 리스팅 자동 재삭제/스케줄 정리(수동 정리, 리포트로 안내).
- 삭제 전 확인(confirm) UX 변경(프론트 소관).
- GMARKET/AUCTION 직접 삭제 API(클라이언트 없음 — Cafe24 경유).
- 소프트삭제/아카이브(사용자가 하드 완전삭제 선택).
