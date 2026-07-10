# 전수 QA 체크리스트 — sbshop-agent (2026-07-11)

> 사용자 요청: "모든 메뉴·모든 버튼·모든 액션을 낱낱이 조사". 프론트 버튼/fetch → 백엔드 엔드포인트 계약을 도메인별 병렬 조사(5 에이전트) 후 리더가 상충·핵심 지점 직접 재확인. 판정: ✅정상 / ⚠️갭(요승인·기능확장) / 🔴결함(수정함) / 💤죽은코드.

## 범례
- ✅ **정상**: 버튼→fetch→백엔드 계약 일치, 동작 확인(코드 정합).
- 🔴 **결함→수정**: 이번 사이클에서 수정 완료(게이트 통과).
- ⚠️ **갭(요승인)**: 마켓 API 계약/다도메인 = 중대 등급 → 임의 구현 금지, 사용자 결정 필요.
- 💤 **죽은코드**: 정의됐으나 어떤 버튼에도 미연결(런타임 무해).

---

## 1. 대시보드 (`Dashboard.tsx`)

| 지표 | 이전 | 현재 | 판정 |
|------|------|------|------|
| 전체 주문 수 | 하드코딩 `0` | `/orders?size=1` totalElements 실집계 | 🔴→✅ (D-059) |
| 배송 진행 중 | 하드코딩 `0` | shippingStatuses=PURCHASED,SHIPPED | 🔴→✅ (D-059) |
| 통관 오류/대기 | 하드코딩 `0` | customsStatuses=PENDING,INVALID_* (백엔드 필터 신설) | 🔴→✅ (D-059) |
| **추가**: 미발주(NEW) | — | 신설 | ✅ |
| **추가**: 구매준비(PREPARING) | — | 신설 | ✅ |
| **추가**: 배송완료(DELIVERED) | — | 신설 | ✅ |
| **추가**: 배송처리 대기(PURCHASED) | — | 신설 | ✅ |
| 새로고침 버튼 | 없음 | 신설(전 지표 병렬 재조회) | ✅ |

## 2. 통합주문관리 (`OrderGrid.tsx`) — 22개 액션 전수

| 액션 | 엔드포인트 | 판정 |
|------|-----------|------|
| 마켓 동기화 5종(쿠팡/스토어/11번가/G마켓·옥션/통관) | POST /orders/sync/* | ✅ |
| 재고현황 새로고침 | POST /products/sync/stock | ✅ (D-057 피드백 개선 반영) |
| 발주확인(배치) | POST /orders/confirm/batch | ✅ |
| 주문취소 | POST /orders/{id}/cancel | ✅ |
| 주문삭제 | DELETE /orders/{id} | ✅ |
| 구매정보 저장 | PATCH /orders/line-items/{id}/sourcing | ✅ |
| 배송정보 저장 | PATCH /orders/line-items/{id}/shipping | ✅ (단, 마켓 전파는 ⚠️ 4-B 참조) |
| 배송처리 | POST /orders/ship | ✅ (마켓 전파 3/5, ⚠️ 4-A) |
| 인라인 편집(주소/통관번호/구매금액/물류비/유니패스) | PATCH /orders/{id}, /line-items/{id}(/sourcing) | ✅ |
| 필터·검색·페이지네이션·전체선택 | GET /orders | ✅ |
| **배송정보 택배사 "ETC" 표시** | — | 🔴→✅ (D-058) |

### 2-1. 배송정보 "ETC" 검증 결과 (사용자 item 2)
- 그리드 렌더는 빈 택배사→`-`로 올바름(`OrderGrid.tsx:1061`).
- **근본원인**: `ShippingCarrier.fromMarketCode()`가 `null`은 null 반환하지만 **빈 문자열("")/공백은 default 분기→ETC("기타")**로 매핑. 마켓이 미배송 주문에 빈 택배사를 주면 ETC가 저장돼 화면에 "ETC"로 떴음.
- **수정(D-058)**: 빈 문자열/공백도 null(미입력)로 처리 → 미배송이면 빈칸(`-`). 회귀 테스트 `ShippingCarrierTest` 추가.

## 3. iHerb 이메일 자동 배송추적 (사용자 item 3) — 링크별

| 링크 | 판정 | 근거 |
|------|------|------|
| ① 구매정보 저장 → 모니터링 등록(PURCHASED) | ✅ | OrderService.updateSourcingInfo, findIherbItemsNeedingEmailProcessing |
| ② IMAP 이메일 수신·파싱(주문번호/송장/택배사) | ✅ | EmailFetcherService + OrderEmailParser (정규식) |
| ③ 아이허브 주문번호 ↔ SB주문 매칭 | ✅ | findBySourcingData_SourcingOrderNo |
| ④ shippingData 자동갱신(트래킹/택배사) + 상태 SHIPPED + 마켓 송장전송 | ✅ | processIherbShipment, 멱등 처리 |
| ⑤ 스케줄러 활성화 | ✅ | OrderSyncScheduler `@Scheduled` **실제 가동**(코드의 `// TODO` 후행주석은 무해, 어노테이션 활성) |

> **정정**: 초기 조사에서 ⑤를 FAIL로 오판했으나, `@Scheduled(cron=...)`가 주석 처리되지 않고 실제 실행됨(codebase-map Z-1과 일치). 흐름은 배선·가동 상태. **전제**: iHerb 계정 IMAP 자격증명(EMAIL_*) 설정 + 실제 발송 메일 도착. 코드 결함 아님.

## 4. 마켓 전파 매트릭스 (사용자 item 3·4) — ⚠️ 기능 갭 (요승인)

### 4-A. 송장/배송 전파
| 마켓 | 배송처리(shipOrders) | 배송정보 수정→마켓 |
|------|------|------|
| 쿠팡 | ✅ 구현(invoice upload) | ⚠️ 미전파(DB만) |
| 스마트스토어 | ✅ 구현(dispatch) | ⚠️ 미전파 |
| 11번가 | ✅ 구현(reqdelivery) | ⚠️ 미전파 |
| G마켓/옥션(ESM+) | 🔴 스텁(log.warn만) | ⚠️ 미전파 |
| 카페24 | 🔴 어댑터 없음(예외) | ⚠️ 미전파 |

### 4-B. 가격/재고 전파 (상품관리 가격/재고 버튼)
| 마켓 | syncPriceAndStock 구현 | 실제 마켓 API 호출 |
|------|------|------|
| 스마트스토어 | ✅ | ✅ GET+PUT |
| 11번가 | ✅ | ✅ |
| 쿠팡 | ✅(메서드) | 🔴 로컬 맵만(실호출 없음) |
| 카페24 | ✅(메서드) | 🔴 로컬 맵만 |
| G마켓/옥션 | 🔴 클라이언트 없음 | — |
| **호출 경로** | — | 🔴 **`ProductManageUseCase.updatePriceStock`가 DB만 저장, syncPriceAndStock 미호출** |

> **핵심 갭(D-060/D-061)**: 상품관리 가격/재고 수정은 **어느 마켓에도 동기화되지 않음**(호출 경로 자체 부재). 배송정보 "수정"은 마켓 미전파(배송"처리"·이메일 경로만 3/5 마켓 전파). ESM+/카페24는 배송 전파 미구현. → **마켓 API 계약·다도메인 = 중대 등급**이라 임의 구현 대신 사용자 결정 후 별도 배치로 진행 권고.

## 5. 상품관리 (`ProductPage.tsx`)
| 액션 | 엔드포인트 | 판정 |
|------|-----------|------|
| 검색/새로고침/페이지네이션 | GET /products | ✅ |
| 가격/재고 수정 | PUT /products/{id}/price-stock | ✅ DB 반영(마켓 전파는 ⚠️ 4-B) |
| 상품상세 모달 | GET /products/{id} | ✅ |
| 이미지 업로드/URL/소스크롤 | PUT /products/{id}/images(/by-url), GET .../crawl | ✅ |
| 마켓코드 링크 | (D-052) | ✅ |

## 6. 신규상품 등록 (`ProductRegisterPage.tsx`)
| 액션 | 판정 |
|------|------|
| iHerb 크롤 | ✅ POST /sourcing/iherb |
| 대량 저장 | ⚠️ 일부 필드(origin/weight/measureUnit/rawSourceHtml) 미전송 → null 저장(크래시 아님, 데이터 완결성 P3) |

## 7. 배치·진행현황·설정
| 페이지/액션 | 판정 |
|------|------|
| BatchUpdatePage(공급사별/수동 배치) | ✅ |
| ProcessStatusPage(batchId 조회, 활동로그 새로고침) | ✅ (시간 KST D-053 반영) |
| Settings(자격증명 CRUD) | ✅ PUT /market-credentials/{type} |

## 8. 죽은 코드 인벤토리 (💤 런타임 무해 — 정리 대상)
- `productApi.ts`: updateProduct, deleteProduct(엔드포인트 존재·미연결), **getMarketRegistrations/getLocalMarketData/syncMarketLive(엔드포인트 부재·미연결→호출 시 404지만 미사용)**
- `batchApi.manualUpdate`, `sourcingApi.publishToMarket`, `marketApi.fetchCredential`, `supplierApi.*`(suppliers/currencies — UI 없음)

---

## 이번 사이클 수정 요약
- 🔴→✅ **D-058** 배송정보 ETC 오표기(빈 택배사→null)
- 🔴→✅ **D-059** 대시보드 실데이터 연동 + 지표 확장 + 통관상태 필터 신설
- ⚠️ **D-060/D-061** 가격재고·배송 마켓 전파 갭 = 중대(요승인) → 미수정·보고

## 게이트
- 백엔드: `:core:test`(ShippingCarrierTest 통과), `:infrastructure:test`·`:api:test` BUILD SUCCESSFUL. 유일 실패 `SmartStoreOrderFetchFailureTest`는 **기존 flaky**(clean HEAD 동일 실패, 변경과 무관).
- 프론트: `tsc -p tsconfig.app.json` clean + `npm run build` EXIT 0.
