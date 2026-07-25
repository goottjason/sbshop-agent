"""Fortnum & Mason (fortnumandmason.com) 상품 재고·가격 스크래퍼.

F&M은 Next.js + Cloudflare로 가격/재고가 JS 렌더링 후 노출된다 → 정적 HTTP로는
안 잡히고 Scrapling의 브라우저 페처(DynamicFetcher, 필요 시 StealthyFetcher)가 필요하다.
"""
from __future__ import annotations

from datetime import datetime, timezone

from scrapling.fetchers import DynamicFetcher, StealthyFetcher

from models import ScrapeResult
from scrapers.base import VendorScraper, parse_price, parse_weight_grams


class FortnumScraper(VendorScraper):
    vendor = "FTN"
    DOMAIN = "fortnumandmason.com"

    def supports(self, url: str) -> bool:
        return self.DOMAIN in (url or "")

    def scrape(self, url: str, *, stealth: bool = False, dump_html: str | None = None) -> ScrapeResult:
        now = datetime.now(timezone.utc).isoformat()
        try:
            if stealth:
                page = StealthyFetcher.fetch(url, headless=True, network_idle=True, solve_cloudflare=True)
            else:
                page = DynamicFetcher.fetch(url, headless=True, network_idle=True)
        except Exception as e:  # noqa: BLE001
            return ScrapeResult(ok=False, status="error", sourceUrl=url, vendor=self.vendor,
                                error=f"fetch error: {e}", scrapedAt=now)

        if dump_html:
            try:
                with open(dump_html, "w") as f:
                    f.write(page.html_content)
            except Exception:  # noqa: BLE001
                pass

        http = getattr(page, "status", None)
        low_all = (page.get_all_text() or "").lower()

        # (1) 봇/Cloudflare 차단 — 재고를 절대 건드리면 안 됨(멀쩡한 상품 오품절 방지). 스킵+추적.
        block_markers = ("just a moment", "checking your browser", "attention required",
                         "access denied", "captcha", "cf-challenge", "enable javascript and cookies")
        if http in (403, 429, 503) or any(m in low_all for m in block_markers):
            return ScrapeResult(ok=False, status="blocked", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error="bot/Cloudflare 차단 의심", scrapedAt=now)

        # (2) 링크 소멸(404) — 더 이상 판매 안 함 → 품절 처리(가격 미변경).
        if http == 404 or "page not found" in low_all:
            return ScrapeResult(ok=False, status="not_found", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, inStock=False, availabilityText="404 Not Found",
                                error="상품 페이지 없음(404)", scrapedAt=now)

        name = self._first(page, [
            'meta[property="og:title"]::attr(content)',
            'h1::text',
        ])

        # 가격: 우선 구조화 메타 → 없으면 가격 후보 셀렉터 → 없으면 전체 텍스트 정규식
        price = None
        price_meta = self._first(page, [
            'meta[property="product:price:amount"]::attr(content)',
            'meta[property="og:price:amount"]::attr(content)',
        ])
        if price_meta:
            try:
                price = float(price_meta)
            except ValueError:
                price = None
        if price is None:
            for sel in ['[class*="price" i]::text', '[data-testid*="price" i]::text']:
                try:
                    for t in page.css(sel).getall():
                        p = parse_price(t)
                        if p is not None:
                            price = p
                            break
                except Exception:  # noqa: BLE001
                    pass
                if price is not None:
                    break
        if price is None:
            price = parse_price(page.get_all_text())

        currency = self._first(page, ['meta[property="product:price:currency"]::attr(content)']) or "GBP"

        # 무게: 상품명 우선(예 "…, 50g" / "6x25g"), 없으면 본문 텍스트에서 시도
        weight = parse_weight_grams(name or "")
        if weight is None:
            weight = parse_weight_grams(page.get_all_text() or "")

        # 재고: 담기 버튼 유무 / 품절 문구
        text = (page.get_all_text() or "").lower()
        in_stock = None
        avail = None
        if "add to bag" in text or "add to basket" in text:
            in_stock, avail = True, "Add to Bag"
        elif any(s in text for s in ("out of stock", "sold out", "notify me", "currently unavailable")):
            in_stock, avail = False, "Out of stock"

        # (3) 200인데 가격 못 찾음 → 레이아웃 변경 등 이상. 오품절 방지 위해 error로 스킵(재고 미변경).
        ok = price is not None
        return ScrapeResult(
            ok=ok,
            status="ok" if ok else "error",
            httpStatus=http,
            sourceUrl=url, vendor=self.vendor, name=name,
            price=price, currency=currency,
            inStock=in_stock, availabilityText=avail,
            weightGrams=weight,
            scrapedAt=now,
            error=None if ok else "price not found (200)",
        )

    @staticmethod
    def _first(page, selectors) -> str | None:
        for sel in selectors:
            try:
                v = page.css(sel).get()
            except Exception:  # noqa: BLE001
                v = None
            if v:
                return v.strip()
        return None
