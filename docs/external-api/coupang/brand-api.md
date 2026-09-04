# 쿠팡 브랜드 API (2026-09-04 확보)

출처: developers.coupang.com — 사용자가 PDF 3종 다운로드. 이 문서는 그 요약이다.

## 1. 브랜드 검색 (핵심)

```
POST /v2/providers/seller_api/apis/api/v1/marketplace/brands/search
```

> 쿠팡 브랜드 라이브러리에서 브랜드명을 기준으로 검색한다.
> **상품 생성 API에서 brandId 를 입력하기 전에 해당 값을 조회할 때 사용한다.**

**요청**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `brandName` | String | **필수.** 검색 키워드 |
| `countPerPage` | Integer | 기본 10, **최대 10** |
| `page` | Integer | 기본 1, 최소 1 |

```json
{ "brandName": "NIKE", "countPerPage": 10, "page": 1 }
```

**응답**

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | String | SUCCESS / ERROR |
| `data.totalCount` | Long | 조건에 맞는 총 브랜드 수 |
| `data.items[].brandId` | String | 브랜드 고유 ID (예: `KR-5`) |
| `data.items[].brandName` | String | **브랜드 이름 — 이것이 공식 표기다** |
| `data.items[].brandLogoUrl` | String | 로고 URL |
| `data.items[].isUIDRequired` | Boolean | UID 제출 필요 여부 |
| `data.items[].allowedUIDTypes` | Array | 허용 UID 타입(GTIN·MPN 등) |

**오류**

| 상태 | 메시지 | 조치 |
|---|---|---|
| 400 | `brandName is required` | 필수 파라미터 누락 |
| 401 | `Authentication failed` | HMAC 인증 확인 |

## 2. 등록 브랜드 목록

```
GET /v2/providers/seller_api/apis/api/v1/marketplace/brands/enrolled
```

판매자가 **등록 및 등록 완료한** 브랜드 전체 목록. 요청 파라미터 없음.
응답: `data[].brandId` · `data[].brandName`.

우리가 이미 쓰는 브랜드가 무엇으로 등록돼 있는지 한 번에 볼 수 있다.

## 3. ID 기반 브랜드 조회

```
GET /v2/providers/seller_api/apis/api/v1/marketplace/brands/{brandId}
```

`brandId`(예: `KR-5`)로 상세 조회.

## 우리 시스템과의 접점

- `CoupangProductPayload` 는 지금 `brand`(문자열)만 보내고 `brandId` 자리가 없다.
  브랜드 검색으로 **공식 `brandName`** 을 얻어 그 문자열을 채우는 것이 1차 목표다.
- 호출 패턴은 `CoupangMetaService`(카테고리 메타 조회)를 따른다 —
  `CoupangRestClient.requestWithBody` + HMAC 서명 + `@Cacheable`.
- [[D-261]] 브랜드 백필이 이 API 를 쓴다.
