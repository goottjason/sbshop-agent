# Ocado 검토형 소싱 갱신

2026-09-08. 운영 DB의 두 실제 상품 URL을 운영 Chrome으로 열어 계약을 확인하고, 기존 scraper 컨테이너의 `/tmp`에 격리한 새 모듈로 같은 두 상품을 실제 수집했다. 기존 `/app` 코드·서비스 실행·상품 DB·외부마켓 상품값은 변경하지 않았다.

| 실제 상품 | 최종 URL의 상품 ID | 가격·통화 | 명시 판매 상태 | 갤러리 | 정제 상세 |
|---|---:|---|---|---:|---:|
| Cirio Italian Tomato Puree | 80259011 | 1.50 GBP | InStock | 1장 | 2,121자 |
| Mornflake Jumbo Oats | 450106011 | 1.30 GBP | InStock | 1장 | 2,860자 |

두 건 모두 새 DynamicFetcher 경로가 HTTP 200으로 완료했다(각 6.5초·6.1초). Cirio 대표 이미지의 서버 직접 GET도 HTTP 200/image/jpeg/25,163바이트로 확인했다. [최소 운영 관측 결과](evidence/2026-09-08-ocado-reviewed-live.json)에 요약을 남겼다. 실재고 수량은 원문에 없으므로 null을 유지한다. 이 결과는 두 표본의 접근 검증이며 모든 상품의 항상 성공을 보증하지 않는다.

## 정확한 계약

- HTTPS `www.ocado.com/products/{slug}-{id}` 또는 실제 리다이렉트 후 `.../products/{slug}/{id}`를 허용한다. 쿼리·사용자정보·포트·fragment·다른 호스트를 거절한다. 요청 URL·최종 URL·단일 JSON-LD Product.sku·화면 h1의 상품명이 일치해야 한다.
- offers는 단일 Offer 객체여야 한다. 실제 문자열 양수 price, GBP, `https://schema.org/InStock` 또는 `https://schema.org/OutOfStock`만 읽는다. 첫 offer 선택·통화 기본값·LimitedAvailability·품절 시 0개/정상 시 100개 등의 추정은 없다.
- `[data-test=bop-view]` 안의 `li[data-test=product-image-slide] > button[data-test=product-image-button] > img` 전체를 `data-id` 순서로 읽는다. 첫 src가 JSON-LD 대표 이미지와 같고 전체 JSON-LD 이미지가 이 갤러리에 포함돼야 한다. 1~8장 범위를 넘으면 잘라서 성공하지 않는다. 추천 상품·브랜드 로고·쿠키 배너 이미지는 포함하지 않는다.
- 실제 이미지 호스트는 `www.ocado.com`, 경로는 `/images-v3/{uuid}/{uuid}/{width}x{height}.jpg` 또는 `.webp`로 제한한다. 주소를 임의로 고해상도 경로로 바꾸지 않고 실제 src를 사용한다.
- Product Information의 본문을 같은 JSON-LD description과 대조한다. 같은 부모 아래 이어지는 실제 설명 블록을 모두 읽고 `similar-products-carousel` 또는 `bop-reviews-container` 경계에서 멈춘다. 원재료·영양표·보관·제조사 등의 실제 항목을 보존한다. 경계가 없거나 중간 구조가 달라지면 부분 설명을 전체라고 저장하지 않는다. 실행 태그·링크·이미지·이벤트·스타일을 제거한 본문·표만 남긴다.

원본 전체 HTML·후기·추천 상품·브라우저 쿠키는 DB와 fixture에 보관하지 않는다. 실제 상품 부분만 남긴 [Cirio fixture](../../scraper/fixtures/ocado-cirio-reviewed-2026-09-08.html), [Mornflake fixture](../../scraper/fixtures/ocado-oats-reviewed-2026-09-08.html)를 사용한다.

## 연결 및 실패 처리

내부 `POST /scrape/reviewed/ocado`는 `{url,mode:"CONTENT"|"PRICE_STOCK"}`를 받는다. 기존 범용 `/scrape/stock-price`와 `/scrape/product-detail`의 느슨한 parser를 재사용하지 않는다. 콘텐츠 모드는 전체 갤러리·상세 계약을 검사하고 가격·재고 모드는 해당 이미지·상세 성공을 요구하지 않는다. 응답 `ocado-reviewed-v1` 계약·요청 URL·최종 ID·통화·boolean·최근 수집 시각을 Java에서도 다시 검증한다.

DynamicFetcher는 45초 제한과 `retries=1`로 실행한다. 실제 주 문서 HTTP 상태와 최종 URL을 확인한다. 202/403/404·봇 차단·레이아웃 누락을 품절이나 성공으로 바꾸지 않는다. 소싱처 429를 관측하면 후속 브라우저 요청을 차단하고 Retry-After를 전달한다. 콘텐츠/가격·재고는 기존 영속 `SOURCE_OCD` gate를 공유하여 최소 5분 또는 서버 지정 시각까지 대기한다. 해당 gate 행이 없으면 HTTP를 호출하지 않는다.

Java의 HTTP 호출은 기존 `scraper.base-url` 설정을 사용하고 70초/500KB 제한을 둔다. 검토 자료는 기존 snapshot, 수집 성공 시각/적용 시각 분리, actor·멱등 키·만료·revision·source fingerprint 검사, 공통 편집 이력/미반영 대상과 같은 transaction 저장을 그대로 따른다. 이미지 다운로드·호스팅과 자동 상품 HTML 생성도 기존 검토 흐름을 재사용한다. 소싱 갱신으로 바로 외부마켓에 쓰지 않는다.

## 검증과 배포 조건

Python 회귀 14건이 실제 두 상품·정확한 ID/최종 경로·복수 상품/offer·GBP/boolean·전체 갤러리·상세 경계·실행 코드 제거·429 지연을 검증했다. Java 신규 9건과 기존 source/content/URL/FTN/COK/VTB/실 PostgreSQL DDL 회귀를 합쳐 65건이 통과했다. 여기에는 OCD 가격·재고에서 발생한 429가 콘텐츠 조회도 막고 성공 시각·상품 재고를 변경하지 않는 통합 검사가 포함된다.

실행 로그: `/private/tmp/sbshop-ocado-reviewed-tests.log`, `/private/tmp/sbshop-ocado-gate-tests.log`. 운영에는 [소싱 DDL](../../backend/docs/ddl/2026-09-08-product-source-refresh.sql)의 `SOURCE_OCD` 행과 새 scraper 이미지, Java/프론트 변경을 함께 반영해야 한다. 기존 소싱 지원은 IHB·VTB·FTN·COK·OCD이며, 현재 검증된 수집 계약이 없는 TES·AMZ는 UNSUPPORTED로 남긴다. TES·AMZ의 접근 가능한 실제 상품 URL·정확한 상품/규격·통화·가격·재고·전체 이미지/상세 응답이 확보되기 전에는 지원을 추정하지 않는다.
