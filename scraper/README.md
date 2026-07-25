# sbshop-scraper (PoC)

Scrapling 기반 **소싱 스크래핑 마이크로서비스**. Java 백엔드(JVM)에 Python 스크래핑
라이브러리(Scrapling + Playwright)를 직접 넣을 수 없어, 별도 프로세스로 격리하고
Java가 HTTP로 호출한다.

현재 상태: **Fortnum & Mason 재고·가격 스크래핑 PoC 성공** (JS 렌더링 + Cloudflare 페이지).

## 왜 별도 서비스인가
- Scrapling은 Python 3.10+ 전용 + Playwright/Chromium 런타임 필요 → JVM에 통합 불가.
- 헥사고날 경계(core 포트) 뒤 infrastructure 어댑터가 이 API를 호출하면 상위(유스케이스·
  컨트롤러·프론트) 계약 무변경으로 크롤 엔진만 교체·확장할 수 있다.

## 설치 / 실행
```bash
cd scraper
python3.12 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/scrapling install          # Playwright 브라우저 다운로드(최초 1회)

# 서비스 기동
./.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8099

# 단독 크롤 테스트
./.venv/bin/python test_fortnum.py "https://www.fortnumandmason.com/royal-blend-25-tea-bags"
```

## HTTP 인터페이스 (Java ↔ Python 계약)

### `GET /health`
```json
{ "ok": true, "scrapers": ["FTN"] }
```

### `POST /scrape/stock-price`
요청:
```json
{ "url": "https://www.fortnumandmason.com/royal-blend-25-tea-bags", "vendor": "FTN" }
```
응답 (`ScrapeResult`):
```json
{
  "ok": true,
  "sourceUrl": "https://www.fortnumandmason.com/royal-blend-25-tea-bags",
  "vendor": "FTN",
  "name": "Royal Blend, 25 Tea Bags, 50g",
  "price": 7.95,
  "currency": "GBP",
  "inStock": true,
  "availabilityText": "Add to Bag",
  "sku": null,
  "scrapedAt": "2026-07-25T07:03:37Z",
  "error": null
}
```

## Java 통합 설계 (매핑)

`scraper` 응답 → core 도메인 매핑. **새 어댑터 하나**만 추가하면 된다:

| Python `ScrapeResult` | Java 매핑 |
|---|---|
| `price` | `StockCheckResult.costPrice` / `ScrapedProductDto.costPrice` |
| `inStock` | `StockCheckResult.status` = `IN_STOCK` / `OUT_OF_STOCK` |
| `name` | `ScrapedProductDto.baseName` |
| `currency`, `availabilityText`, `sku` | 보강/근거 |

권고 어댑터 (infrastructure):
```java
// ProductStockCrawlerPort / ProductInfoCrawlerPort 구현
@Component
class ScraplingSourcingClient implements ProductStockCrawlerPort {
    // POST {SCRAPER_BASE}/scrape/stock-price → ScrapeResult → StockCheckResult 매핑
}
```
벤더 확장 시: core의 죽어있는 `SourcingAgentFactory`(supports(url) 라우팅 뼈대)를 부활시켜
벤더별로 분기하고, 기존 `IherbScraperClient`도 한 agent로 편입. Python 측은 `SCRAPERS`
레지스트리에 벤더 스크래퍼를 추가.

## 구조
```
scraper/
  app.py              # FastAPI 서비스 (POST /scrape/stock-price)
  models.py           # ScrapeRequest/ScrapeResult (Java 계약)
  scrapers/
    base.py           # VendorScraper 추상(supports/scrape) + 가격 파싱 유틸
    fortnum.py        # Fortnum & Mason 스크래퍼 (DynamicFetcher/StealthyFetcher)
  test_fortnum.py     # 단독 실행/검증 스크립트
  requirements.txt
```

## 알려진 한계 / 다음 단계
- **동기 처리**: `DynamicFetcher.fetch`는 블로킹. 대량 배치는 async 세션 + 동시성/타임아웃/큐
  설계 필요(브라우저 렌더는 건당 수 초).
- **가격 셀렉터**: 현재 `[class*="price"]` + `£` 정규식 폴백. 더 견고하게는 상품 API(XHR)
  캡처(`response.captured_xhr`)나 `adaptive=True` 셀렉터 고정 권장.
- **Cloudflare 강화 대비**: 지금은 `DynamicFetcher`로 충분. 차단 강화 시 `--stealth`
  (`StealthyFetcher.fetch(..., solve_cloudflare=True)`)로 승급.
- **운영 배포**: Docker 사이드카(`ghcr.io/d4vinci/scrapling` 베이스)로 api 컨테이너 옆에 배치,
  자동배포 웹훅에 편입.
