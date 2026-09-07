# 상품 콘텐츠 적용 이력 검색과 정렬

2026-09-07 구현·로컬 PostgreSQL 검증. 운영 배포·실제 외부마켓 변경은 이 작업에서 수행하지 않았다.

## API

`GET /api/v1/products` 및 `POST /api/v1/products/search`에 동일한 콘텐츠 조건을 제공한다.

| 항목 | 의미 |
|---|---|
| `contentAgeDays` | `null`이면 콘텐츠 경과일 제한 없음. 1~36,500일 정수. 마지막 DB 적용이 해당 일수 이상 지났거나 적용 기록이 없는 상품을 포함한다. |
| `contentAgeField` | 기본 `ANY`. `IMAGES`, `DETAIL_HTML`을 선택할 수 있다. `ANY`는 두 필드 중 하나라도 조건에 해당하면 포함한다. |
| `sort=workspacePriority,asc` | 기본 정렬. 확인된 조치 필요 조건 → 콘텐츠 DB 적용 오래된 순 → 상품 ID 오름차순. |
| `sort=contentOldest,asc` | 조치 필요 여부를 구분하지 않고 콘텐츠 DB 적용 오래된 순 → 상품 ID 오름차순. |

콘텐츠 정렬은 `contentAgeField`를 따른다. `ANY`는 이미지와 HTML의 마지막 적용 시각 중 더 오래된 시각을 사용하며, 하나라도 적용 기록이 없으면 기록 없음으로 정렬한다. 기록 없음은 가장 앞에 온다. 경과일을 지정하지 않아도 정렬 필드는 선택할 수 있다. 두 콘텐츠 정렬은 단일 오름차순만 지원한다. 기존 `sbCode`, `brand`, `productName`, `priceInfo.salePrice` 등의 일반 정렬은 유지한다.

목록 상품마다 다음 객체를 반환한다. 기록이 없는 시각은 `null`이다.

```json
{
  "contentFreshness": {
    "imagesCollectedAt": null,
    "imagesAppliedAt": null,
    "detailHtmlCollectedAt": null,
    "detailHtmlAppliedAt": null
  }
}
```

수집 성공 시각은 마지막 DB 적용 시각을 대신하지 않는다. 방금 새 이미지를 수집했더라도 아직 적용하지 않았다면 기존 적용 시각을 유지한다. `null`은 **적용 기록 없음**이지 상품이 오래됐다는 확정 판단이 아니다.

현재 상품의 `sourceUrl`과 `vendor`가 모두 같은 스냅샷만 집계·필터·정렬에 사용한다. 소싱처 또는 상품 URL이 변경되면 과거 소스의 적용 기록을 현재 콘텐츠가 최신이라는 근거로 사용하지 않는다. 과거 스냅샷·이미지·적용 이력은 삭제하지 않는다.

## 조치 필요 조건

다음 중 하나를 만족하면 `workspacePriority`에서 우선한다.

- DB 변경 대상에 `PENDING_DISPATCH`, `DISPATCHED`, `ACTION_REQUIRED`가 존재한다.
- 상품의 `lastCrawlError`가 비어 있지 않다.
- 상품에 `sourceGoneAt`이 기록되어 있다.
- 현재 마켓 식별자가 있고 해당 직접 연결이 `LINKED`인 등록에 `lastSyncError`가 있다.

일시 품절 자체, 해제된 연결의 과거 동기화 오류, 식별자가 없는 등록의 오류는 이 우선 조건에 넣지 않는다. Cafe24 하위 마켓의 별도 전송 관측 실패는 위 `lastSyncError`와 별개의 관측이므로 이 정렬 조건에 임의 혼합하지 않는다.

## 조회와 검증

경과일 필터와 정렬은 SQL에서 전체 검색 대상에 먼저 적용한 뒤 페이지를 자른다. 페이지 순서를 Java 메모리에서 다시 정렬하지 않는다. 콘텐츠 조건은 count 쿼리에도 동일하게 적용하고, count에는 정렬 표현식을 넣지 않는다. 각 검색의 데이터·count 조회는 동일한 읽기 transaction과 PostgreSQL repeatable-read 범위에서 수행한다. 서로 다른 페이지 요청 사이의 상품 변경까지 고정하는 세션 스냅샷 기능은 아니다.

목록에 표시할 네 시각은 페이지 상품 ID 전체를 대상으로 `MAX`/`GROUP BY` 조회 한 번으로 가져온다. 최근 실패 스냅샷의 빈 시각이 과거 성공 시각을 지우지 않는다. 신규 테이블·DDL은 필요하지 않으며 기존 콘텐츠 스냅샷의 상품 ID 인덱스를 이용한다.

scoped 검사 **74건(Core 검색 회귀 36 / API 38)** 통과. API 검사에는 실제 PostgreSQL 6건과 새 컨트롤러 4건이 포함된다. 다음을 확인했다.

- 수집과 적용 분리, 이미지/HTML/ANY의 의미, 최근 적용이 과거 적용을 대체하는 집계
- 전체 데이터 정렬 후 페이지 분할, count 일치, 적용 기록 없음의 ID 순서와 안정적인 재조회
- 조치 필요 우선, 품절·해제된 오류·미식별 오류 제외
- 페이지 크기와 무관한 집계 SQL 1회, 빈 ID 목록은 쿼리 없음
- 소싱 URL/vendor 변경 시 기존 소스의 시각을 제외하면서 과거 이력 보존
- 일반 판매가·SB코드 정렬 유지, GET/POST 계약과 범위 오류 검증

최종 검사 로그: `/private/tmp/sbshop-content-freshness-search-source-tests.log`. 기존 컨트롤러 회귀의 기본 정렬 기대값은 승인된 `workspacePriority`로 갱신했다.

## 운영 읽기 전용 검증 요청

아래는 최신 Controller/DTO/Specification 기준의 요청 예시이며 **실행 결과가 아니다**. 운영 웹 경로에는 프론트 API 설정과 같은 `/sbshop-agent` 접두사를 포함했다. 기존 로그인 인증을 사용하고 인증값은 기록하지 않는다. `page`는 0부터 시작하며, POST 검색도 조회만 수행한다. POST의 `page`, `size`, `sort`는 JSON 본문이 아닌 query에 넣는다.

| 확인 항목 | HTTP 요청 (본문 없음) | 확인할 응답 |
|---|---|---|
| 기본 정렬 | `GET /sbshop-agent/api/v1/products?page=0&size=5` | 아래 명시적 기본 정렬 요청과 상품 ID 순서·`totalElements`가 같다. |
| 조치 필요 → 적용 오래된 순 | `GET /sbshop-agent/api/v1/products?page=0&size=5&sort=workspacePriority,asc` | 조치 필요 그룹이 먼저이며, 각 그룹에서 적용 기록 없음 → 오래된 적용 → 같은 시각의 ID 오름차순이다. |
| 90일, 어느 콘텐츠든 | `GET /sbshop-agent/api/v1/products?page=0&size=5&contentAgeDays=90&contentAgeField=ANY&sort=contentOldest,asc` | 이미지 또는 HTML 적용이 90일 이상 지났거나 둘 중 하나라도 적용 기록이 없다. |
| 90일, 이미지만 | `GET /sbshop-agent/api/v1/products?page=0&size=5&contentAgeDays=90&contentAgeField=IMAGES&sort=contentOldest,asc` | `contentFreshness.imagesAppliedAt`이 없거나 요청 시점 기준 90일 이상 경과했다. |
| 90일, HTML만 | `GET /sbshop-agent/api/v1/products?page=0&size=5&contentAgeDays=90&contentAgeField=DETAIL_HTML&sort=contentOldest,asc` | `contentFreshness.detailHtmlAppliedAt`이 없거나 요청 시점 기준 90일 이상 경과했다. |
| SB코드 정렬 | `GET /sbshop-agent/api/v1/products?page=0&size=5&sort=sbCode,asc` | `sbCode` 오름차순이다. |
| 브랜드 정렬 | `GET /sbshop-agent/api/v1/products?page=0&size=5&sort=brand,asc&sort=id,asc` | 브랜드 오름차순, 같은 브랜드는 ID 오름차순이다. |
| 상품명 정렬 | `GET /sbshop-agent/api/v1/products?page=0&size=5&sort=productName,asc&sort=id,asc` | 상품명 오름차순, 같은 상품명은 ID 오름차순이다. |
| 판매가 정렬 | `GET /sbshop-agent/api/v1/products?page=0&size=5&sort=priceInfo.salePrice,desc&sort=id,asc` | 응답의 `salePrice` 내림차순, 같은 가격은 ID 오름차순이다. |

다중 SB코드는 아래처럼 POST로 검색한다. JSON 문자열의 `\n`은 줄바꿈이며 쉼표·줄바꿈 혼합 입력, 앞뒤 공백, 소문자, 중복 제거를 함께 확인한다. 존재하는 상품만 최대 세 SB코드의 부분집합으로 반환되며, `keyword`의 부분 검색과 구분되는 SB코드 정확 일치 검색이다.

```http
POST /sbshop-agent/api/v1/products/search?page=0&size=20&sort=sbCode,asc
Content-Type: application/json

{"sbCodes":[" 210121ihb031,220211IHB010\n220130IHB035 ","210121IHB031"]}
```

POST 콘텐츠 조건과 일반 검색의 조합도 조회만 한다.

```http
POST /sbshop-agent/api/v1/products/search?page=0&size=5&sort=workspacePriority,asc
Content-Type: application/json

{"vendors":["IHB"],"contentAgeDays":90,"contentAgeField":"ANY"}
```

변경이 없는 시간대에 첫 두 요청 및 `page=1`을 비교하여 기본 정렬 일치, 페이지 간 중복 ID 없음, `totalElements` 일치를 확인한다. ANY와 개별 필터의 **전체 결과**를 비교하면 ANY는 이미지·HTML 결과의 합집합이다. 첫 페이지만 비교해서 합집합을 판단하지 않는다. 수집 시각이 최신이어도 적용 시각이 없으면 필터에 남아야 한다. 적용 이력이 전혀 없는 운영 데이터에서는 세 필터 결과가 같을 수 있으므로, 서로 다른 적용 이력을 가진 실제 표본이 없으면 필드 차이 검증은 미확인으로 남긴다. 상품 변경이나 정확히 90일 경계가 요청 사이에 발생하면 별도 요청 간 결과는 달라질 수 있다.
