# SP-C 설계 — 이미지 파이프라인 완결

- 작성일: 2026-07-12
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-C (로드맵 4순위 — 이미지 파이프라인, 도메인 C ~72%)
- 선행: SP-A·SP-B·SP-E 완료(main `c62dc46`)
- 참고 문서: `docs/external-api/` (11번가 상세설명수정·상품수정 PDF, 스마트스토어 원상품수정·이미지다건등록 PDF, 카페24 PDF, 쿠팡 상품수정 승인불필요 PDF)

---

## 1. 문제 정의

소스이미지 크롤→R2 업로드→마켓 반영에서, **재게시 배관은 이미 연결**돼 있으나(크롤 후 수동 2단계 + 각 마켓 구현 품질 편차)가 문제다.

- **크롤 진입**(`ProductPage.tsx:265` handleCrawl): 크롤 결과를 URL 텍스트박스에 채우고(`setUrlInput`), 사용자가 별도로 "URL로 등록"(`handleUploadByUrl:243`)을 눌러야 반영 — 수동 2단계.
- **업로드→반영 체인**: `PUT /{id}/images/by-url`(`ProductController.java:136`) → `imageDownloadClient.downloadAndConvert` → `ProductManageUseCase.updateImagesAndHtml`(`:68`) → R2 업로드 + `HtmlImageReplacer.replaceImagesBySku`로 상세HTML 내 이미지 URL 치환 + DB save + `republishToMarkets`(`:102`, private)로 4마켓 `syncImagesAndHtml` 호출. **재게시는 이미 by-url flow에 내장됨.**
- **marketItemId 추출 버그**(`ProductManageUseCase.republishToMarkets:116`): `reg.extractVendorItemId()`(쿠팡 전용 vendorItemId 키)를 써서, 그 외 마켓은 fallback으로 내부 DB id가 들어감. → `extractMarketCode()`로 정정 필요.
- **마켓별 재게시 품질**(포트: `MarketClient.syncImagesAndHtml(marketItemId, currentRawData, hostedImages, newDetailHtml)`):
  - 쿠팡(`CoupangMarketClient.java:184`): items[0].images 배열(0=REPRESENTATION, 이후 DETAIL) + items[0].contents HTML 주입. **PUT 경로에 sellerProductId 미포함**(고정경로) — D-046.
  - 스마트스토어(`SmartstoreMarketClient.java:148`): `representativeImage`(hostedImages[0])만 세팅 + `detailContent`. **optionalImages(추가이미지) 누락** — hostedImages[1..] 버려짐.
  - 11번가(`ElevenstMarketClient.java:97`): `log.warn` 후 return. **완전 no-op.**
  - 카페24(`Cafe24MarketClient.java:144`): DELETE 후 POST `/admin/products/{id}/images` base64 업로드(대표 index 0) + description. 부분작동.

### 문서로 확정된 마켓 이미지 API

| 마켓 | API | 반영 |
|---|---|---|
| 스마트스토어 | `PUT /v2/products/origin-products/{no}` — `originProduct.images`(대표+optionalImages), `detailContent`, `statusType` | 즉시 |
| 11번가 상세HTML | `POST /rest/prodservices/updateProductDetailCont/{prdNo}` — XML `<ProductDetailCont><prdDescContClob><![CDATA[html]]></prdDescContClob></ProductDetailCont>`, EUC-KR, `openapikey` 헤더 | 즉시·안전(상세설명만 국소) |
| 11번가 대표이미지 | `PUT /rest/prodservices/product/{prdNo}` — 전체 상품 덮어쓰기(prdImage01~04) | 리스크 큼(전체전문) → 범위 밖 |
| 카페24 | `POST /admin/products/{id}/images` + description | 코드 기구현 |
| 쿠팡 이미지 | **"승인필요" 상품수정** `PUT .../seller-products/{sellerProductId}` (전체전문, **재심사 유발**). "승인불필요" API(`.../partial`)는 배송/반품지만 — 이미지 불가 | 재심사 유발(쿠팡 정책) |

---

## 2. 목표 & 성공 기준

- 크롤 한 번으로 R2 업로드 + 4마켓 반영이 이뤄진다(수동 2단계 제거).
- 11번가 no-op이 사라지고 상세HTML(임베드 이미지 포함)이 반영된다.
- 스마트스토어 다중이미지(optionalImages)가 누락 없이 전달된다.
- marketItemId가 마켓별 올바른 코드로 추출된다.
- 쿠팡 재게시 요청이 올바른 sellerProductId 경로로 나간다(재심사 유발은 쿠팡 정책, 실패는 표면화).

---

## 3. 설계 (5개 축)

### 3.1 크롤→업로드 원클릭 (백엔드 단일 엔드포인트)
- `POST /api/v1/products/{id}/images/crawl-and-upload` 신규: `productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl)` → sourceImages → `imageDownloadClient.downloadAndConvert(sourceImages)` → `productManageUseCase.updateImagesAndHtml(id, files)`(기존, R2+DB+마켓 재게시 포함)을 원자적으로. 반환은 마켓 반영 결과.
- 비-iHerb(벤더 IHB 아님)는 400/warning으로 표면화(기존 handleCrawl 규율).
- 프론트: 크롤 버튼이 이 엔드포인트를 호출(수동 "URL로 등록" 단계 제거), 확인 다이얼로그 안전장치. 기존 by-url/파일업로드 경로는 수동 보정용으로 유지.

### 3.2 marketItemId 추출 정정
- `ProductManageUseCase.republishToMarkets`(`:116`): `reg.extractVendorItemId()` → `reg.extractMarketCode()`(마켓별 올바른 코드; SP-A D-077에서 쓰던 방법과 일치).

### 3.3 스마트스토어 다중이미지 + detailContent
- `SmartstoreMarketClient.syncImagesAndHtml`: `originProduct.representativeImage = {url: hostedImages[0]}`(기존) + **`originProduct.images.optionalImages = hostedImages[1..].map(url → {url})`** 추가 + `detailContent = newDetailHtml`. 문서상 `images` 객체 하위에 대표+optionalImages 구조 — 코드 publish()의 필드 구조를 재사용. 실패는 예외 전파(현재 log-only에 `throw` 추가).

### 3.4 11번가 no-op 해소 (상세설명수정 API)
- `ElevenstMarketClient.syncImagesAndHtml`: `log.warn` no-op 제거 → `POST /rest/prodservices/updateProductDetailCont/{marketItemId}`에 EUC-KR XML `<ProductDetailCont><prdDescContClob><![CDATA[newDetailHtml]]></prdDescContClob></ProductDetailCont>` 전송. `ElevenstMarketRestClient`의 기존 POST/EUC-KR 관습 재사용. 실패는 예외 전파.
- 대표이미지(prdImage01)는 상품수정 전체전문이 필요해 리스크 커서 **범위 밖**(라이브 후 판단). 이 시스템 이미지 교체의 본질은 상세HTML 내 URL 치환(`HtmlImageReplacer`)이므로 상세HTML 반영으로 11번가 이미지 교체가 실질 동작.

### 3.5 쿠팡 sellerProductId 경로
- `CoupangMarketClient.syncImagesAndHtml`: 고정 PUT 경로 → `currentRawData`의 `sellerProductId`를 경로에 포함(`.../seller-products/{sellerProductId}`). 키 부재 시 예외로 표면화(D-046 부분 해소). **재심사 유발은 쿠팡 정책이라 수용** — 즉시반영은 불가능함을 문서가 확정.

---

## 4. 에러 처리
- 마켓별 재게시 실패는 은폐하지 않고 예외 전파 → `republishToMarkets`의 마켓별 수집으로 표면화(SP-A 원칙, 활동로그 D-077 경로 재사용).
- 크롤-앤-업로드: 크롤 0건/비-iHerb는 warning으로 표면화.

---

## 5. 테스트 전략 (TDD Red→Green)

1. **크롤-앤-업로드 엔드포인트**: crawl→download→updateImagesAndHtml 순서 호출(Mockito), 비-iHerb는 거부.
2. **republishToMarkets**: `extractMarketCode` 사용 검증.
3. **스마트스토어**: PUT 바디 `originProduct.images.optionalImages`가 hostedImages[1..], detailContent 세팅(MockRestServiceServer/Mockito).
4. **11번가**: `updateProductDetailCont/{id}` POST에 EUC-KR XML 상세HTML 전송(Mock HTTP).
5. **쿠팡**: PUT 경로에 sellerProductId 포함, 부재 시 예외.
6. **프론트**: 크롤 버튼 원클릭 흐름 tsc/build.

로컬 Docker-off: Mock 기반. 실 API 동작(스마트스토어 optionalImages 구조·11번가 상세HTML·쿠팡 재심사)은 라이브 검증.

---

## 6. 범위 밖 / 불확실성
- 소스이미지 크롤 iHerb 외 벤더 확장(현행 유지).
- 11번가 대표이미지(prdImage01) — 전체전문 상품수정 리스크로 제외.
- 쿠팡 이미지 즉시반영 — 쿠팡 정책상 불가(재심사). 경로 정정까지만.
- **라이브 검증**: 스마트스토어 `images.optionalImages` 실 필드 구조, 11번가 상세설명수정 실동작, 쿠팡 승인필요 재게시.
- DDL 없음.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `api/.../ProductController.java` | crawl-and-upload 엔드포인트 |
| `core/.../application/product/ProductManageUseCase.java` | republishToMarkets extractMarketCode 정정 (+ crawl-and-upload 유스케이스 경로) |
| `infrastructure/.../smartstore/adapter/SmartstoreMarketClient.java` | optionalImages + detailContent + 실패 표면화 |
| `infrastructure/.../elevenst/adapter/ElevenstMarketClient.java` | no-op → updateProductDetailCont 상세HTML |
| `infrastructure/.../coupang/adapter/CoupangMarketClient.java` | sellerProductId 경로 |
| `frontend/src/pages/ProductPage.tsx` + `productApi.ts` | 크롤 원클릭 |
| 신규 테스트 (core/infrastructure/api) | 위 축들 |

---

## 8. 검증/배포
- 코드 게이트: `:core:test`, `:infrastructure:test`, `:api:test`(또는 compile), 프론트 `tsc`/`build`.
- 라이브 확인(배포 후, 사용자 허가): iHerb 상품 크롤 원클릭 → 4마켓 반영. 스마트스토어 다중이미지·11번가 상세HTML 실반영·쿠팡 승인요청 정상. 실패 마켓 표면화.
- push/배포는 사용자 확인 후.
