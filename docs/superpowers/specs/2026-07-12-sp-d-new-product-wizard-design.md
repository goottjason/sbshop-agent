# SP-D 설계 — 신규 상품 등록 위저드 완결

- 작성일: 2026-07-12
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-D (로드맵 5순위 — 도메인 D ~40%, 가장 낮은 완성도)
- 선행: SP-A·SP-B·SP-E·SP-C 완료(main `09df780`)

---

## 1. 문제 정의

신규 상품 등록이 크롤~DB저장에 그치고, 필드 보정·마켓 등록 UI가 없어 흐름이 단절돼 있다.

- **프론트 `ProductRegisterPage`는 위저드 구조가 없다** — 단일 화면에 크롤(`handleCrawl`)+저장(`handleSave`) 2동작만. 크롤 결과는 읽기전용 테이블, 필드 편집 UI 0, 마켓 등록 UI 0.
- **`POST /api/v1/products/bulk`가 `ResponseEntity<Void>` 반환**(`ProductSourcingController.java:61,68`) — `createBulk`는 `List<Product>`를 반환하는데(`ProductCreateUseCase.java:31`) 컨트롤러가 버려서, 클라이언트가 생성된 productId를 못 받아 이후 마켓등록 흐름이 단절.
- **하드코딩**: `marginRate:20`(`ProductRegisterPage.tsx:51`), `vendor:'IHB'`(`:52`), `bundleQuantity:1`(`:50`). 수동보정 필드(`origin`, `weight`, `rawCategory`)는 전송조차 안 됨(`ProductSaveRequest.toCommand`가 `rawCategory` null 고정, `ProductSaveRequest.java:26-30`).
- **sbCode 이중 채번 버그**(`ProductCreateUseCase.java:40-47`): `getNextSbCodeSequence`를 seq==0/>0 분기에서 2회 호출해 DB 시퀀스를 건너뜀.
- `supplierApi.getSuppliers()` 정의됐으나 미호출. FeePolicy는 API 노출 없음.

**재사용 가능(이미 완성):** `ProductPublishUseCase.publishToMarket(Long, MarketType)`(sanitize→validate→client.publish→marketIdentifiers 저장, `ProductPublishUseCase.java:32-61`) 완전 구현, 4마켓 클라이언트 존재. `POST /products/{id}/markets/{marketType}` 엔드포인트(`ProductSourcingController.java:79`) + `sourcingApi.publishToMarket(id, type)`(`sourcingApi.ts:22`, 미호출). `GET /suppliers`(`SupplierController.java:29`). 이미지 자동 처리(`enrichWithHostedImages`, `ProductCreateUseCase.java:66`).

**사용자 결정(확정):** (1) marginRate·vendor는 위저드에서 **수동 입력**(FeePolicy 자동계산 범위 밖). (2) 마켓 등록은 저장 후 **단건 publishToMarket 루프 호출**(bulk-publish API 안 함). (3) 범위는 **프론트 위저드 완결 + bulk 반환 + sbCode 버그**.

---

## 2. 목표 & 성공 기준

- 크롤한 상품을 위저드에서 보정·가격입력 후 저장하고, 반환된 productId로 선택 마켓에 실제 등록까지 한 흐름으로 완료.
- 하드코딩(marginRate/vendor/bundleQty) 제거 — 위저드 입력값 사용.
- bulk 저장이 생성된 productId를 반환해 마켓등록으로 연결.
- sbCode 채번이 항목당 정확히 1회.

---

## 3. 설계 (4개 축)

### 3.1 bulk API가 productId 반환
- `POST /api/v1/products/bulk`: `ResponseEntity<Void>` → **`ResponseEntity<List<Long>>`**. `createBulk`가 반환하는 `List<Product>`에서 `getId()` 추출. `sourcingApi.saveProductsBulk` 반환 타입도 `number[]`로 갱신.

### 3.2 sbCode 이중 채번 버그 수정
- `ProductCreateUseCase.createBulk`(`:40-47`)에서 `getNextSbCodeSequence`를 항목당 1회만 호출하도록 정리. 재현 테스트(Red)로 2회 호출/시퀀스 건너뜀을 고정한 뒤 수정(Green).

### 3.3 필드 전송 확장
- `ProductSaveRequest` record에 `rawCategory`(및 필요 시 origin/weight가 누락돼 있으면 추가) 필드 추가, `toCommand()`에서 null 고정 대신 매핑.
- vendor: 프론트 문자열이 `VendorType` enum과 매핑되는지 검증(잘못된 값 직렬화 실패 방지) — toCommand/역직렬화 지점에서 안전 처리.

### 3.4 프론트 위저드 (`ProductRegisterPage` 재구성, antd Steps)
- **Step1 크롤**: URL 입력 → `sourceFromIherb`(기존).
- **Step2 보정**: 크롤 결과를 편집 가능 테이블/폼으로 — `origin`, `weight`, `rawCategory`, `bundleQuantity`, **`marginRate`(수동 InputNumber)**, **`vendor`(GET /suppliers 드롭다운)**. 하드코딩 제거. 선택 체크박스.
- **Step3 저장**: `saveProductsBulk(선택상품)` → **반환된 productId 목록을 상태 보관**.
- **Step4 마켓등록**: 상품×마켓(쿠팡·스마트스토어·11번가·Cafe24) 체크박스 → 선택 조합마다 **기존 `publishToMarket(id, marketType)` 루프 호출**. 마켓별 성공/실패 개별 표면화(토스트/결과 리스트). 완료 후 상품 상세(`/products`)로 이동 옵션.

---

## 4. 에러 처리
- 마켓 등록 루프: 각 `publishToMarket` 호출을 개별 try로 감싸 한 조합 실패가 나머지를 막지 않게 하고, 마켓별 성공/실패를 UI에 표면화(SP-A 원칙, 조용한 실패 금지).
- vendor enum 미매핑/카테고리 필수 미충족 등 백엔드 검증 실패는 메시지로 표면화.

---

## 5. 테스트 전략 (TDD Red→Green)

1. **bulk 반환**: `POST /products/bulk`가 생성된 productId 목록(`List<Long>`) 반환(컨트롤러/유스케이스 테스트).
2. **sbCode 채번**: `createBulk`가 N개 항목에 `getNextSbCodeSequence`를 정확히 N회(항목당 1회) 호출·시퀀스 연속(재현 테스트).
3. **request 매핑**: `ProductSaveRequest.toCommand`가 `rawCategory`(및 보정 필드)를 command에 반영, vendor 안전 매핑.
4. **프론트**: 위저드 단계 전개, 보정 필드 제출(하드코딩 제거 확인), 저장 후 productId 보관, 마켓등록 루프 호출·부분실패 표면화. `tsc -p tsconfig.app.json` 0, `npm run build` 0.

---

## 6. 범위 밖 / 불확실성
- **FeePolicy 자동계산** — 범위 밖(운영자 marginRate 수동). FeePolicy API 노출 안 함.
- **rawCategory→마켓별 카테고리코드 매핑** — 범위 밖(백엔드에도 없음). 위저드는 rawCategory 문자열만 전송; 마켓 등록이 카테고리 필수라 실패하면 라이브에서 표면화, 매핑은 별도 서브프로젝트.
- **bulk-publish 일괄 API** — 안 함(저장 후 단건 루프).
- **라이브 검증**: vendor enum 매핑, publish 실동작(마켓 카테고리 요구), 이미지 자동 반영.
- DDL 없음.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `api/.../ProductSourcingController.java` | bulk 반환 `Void`→`List<Long>` |
| `api/.../dto/product/ProductSaveRequest.java` | rawCategory 등 필드 추가 + toCommand 매핑 |
| `core/.../application/product/ProductCreateUseCase.java` | sbCode 이중 채번 수정 |
| `frontend/src/pages/ProductRegisterPage.tsx` | antd Steps 위저드(보정·가격·마켓등록) |
| `frontend/src/api/sourcingApi.ts` | saveProductsBulk 반환 타입, publishToMarket 배선 |
| (재사용) `supplierApi.getSuppliers` | vendor 드롭다운 |
| 신규 테스트 (api/core/frontend) | 위 4축 |

---

## 8. 검증/배포
- 코드 게이트: `:core:test`, `:api:test`, 프론트 `tsc`/`build`.
- 라이브 확인(배포 후, 사용자 허가): iHerb URL 크롤 → 보정(원산지·중량·카테고리·마진·공급처) → 저장(productId 반환) → 마켓 선택 등록 → 마켓별 성공/실패 표면화. vendor 매핑·카테고리 요구·이미지 반영 확인.
- push/배포는 사용자 확인 후.
