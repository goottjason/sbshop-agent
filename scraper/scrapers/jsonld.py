"""schema.org JSON-LD 기반 공통 재고/가격 스크래퍼 (D-239).

Ocado·Tesco·Costco UK 는 상품 페이지에 `<script type="application/ld+json">` 으로
Product/offers 를 싣는다. 셀렉터를 사이트마다 추측하는 대신 **표준 구조를 읽는다** —
레이아웃이 바뀌어도 JSON-LD 는 잘 안 바뀌고, 무엇보다 "무엇을 읽었는지"가 명확하다.

접근 방식은 사이트별로 다르다:
  - Costco UK : 평문 HTTP 로 열린다(리다이렉트 추적 필요)
  - Ocado/Tesco: 봇 차단(202/403) → Scrapling 브라우저 렌더링 필요

**모르면 모른다고 답한다.** 가격·재고를 확정할 수 없으면 status=error 로 돌려보내
Java 배치가 재고를 건드리지 않게 한다(품절로 단정하지 않는다). D-239 의 교훈이다.
"""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from datetime import datetime, timezone

from models import ProductDetail, ScrapeResult
from scrapers.base import VendorScraper, parse_weight_grams

_LD_RE = re.compile(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>', re.S | re.I)
USER_AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

# schema.org availability 값 → 재고 여부. 목록에 없는 값은 "모름"으로 둔다.
_IN_STOCK = ("instock", "limitedavailability", "onlineonly", "instoreonly", "preorder")
_OUT_OF_STOCK = ("outofstock", "soldout", "backorder")
# 단종은 품절과 다르다 — 되돌아오지 않으므로 폐기 후보로 따로 알린다.
_DISCONTINUED = ("discontinued",)


def _iter_products(html: str):
    """HTML 안의 모든 JSON-LD 블록에서 @type=Product 노드를 훑는다."""
    for raw in _LD_RE.findall(html or ""):
        try:
            data = json.loads(raw.strip())
        except ValueError:
            continue
        stack = [data]
        while stack:
            node = stack.pop()
            if isinstance(node, list):
                stack.extend(node)
            elif isinstance(node, dict):
                node_type = node.get("@type")
                types = node_type if isinstance(node_type, list) else [node_type]
                # Costco UK 는 "product"(소문자)로 쓴다 — 대소문자를 가리지 않는다.
                if any(str(t).lower() == "product" for t in types if t):
                    yield node
                stack.extend(v for v in node.values() if isinstance(v, (dict, list)))


def _first_offer(product: dict) -> dict:
    offers = product.get("offers") or {}
    if isinstance(offers, list):
        return offers[0] if offers else {}
    return offers if isinstance(offers, dict) else {}


def availability_to_stock(availability: str | None) -> bool | None:
    """schema.org availability → True/False. 모르는 값이면 None(판정 불가)."""
    if not availability:
        return None
    token = str(availability).rstrip("/").rsplit("/", 1)[-1].lower()
    if token in _IN_STOCK:
        return True
    if token in _OUT_OF_STOCK:
        return False
    return None


def _brand_name(brand) -> str | None:
    """JSON-LD brand 는 문자열일 수도 {"name": ...} 객체일 수도 있다(D-291 실측: Costco 는 객체)."""
    if isinstance(brand, dict):
        brand = brand.get("name")
    if isinstance(brand, str) and brand.strip():
        return brand.strip()
    return None


def parse_product(html: str) -> tuple[dict | None, dict]:
    """가격이 있는 첫 Product 노드를 고른다. 가격 없는 노드는 건너뛴다."""
    fallback = None
    for product in _iter_products(html):
        offer = _first_offer(product)
        if offer.get("price") is not None:
            return product, offer
        fallback = fallback or product
    return fallback, {}


class JsonLdScraper(VendorScraper):
    """JSON-LD 를 읽는 벤더 스크래퍼. 하위 클래스는 vendor/host/렌더링 방식만 정한다."""

    vendor = ""
    host_markers: tuple[str, ...] = ()
    needs_browser = False
    # URL 에 이 조각이 있으면 상품 페이지다.
    product_path_marker: str | None = None
    # 브라우저 렌더 후 제목이 이것이면 상품 페이지가 아니라 홈으로 밀린 것 = 소멸.
    home_title_marker: str | None = None

    def supports(self, url: str) -> bool:
        return bool(url) and any(m in url.lower() for m in self.host_markers)

    def _fail(self, url: str, status: str, error: str, http: int | None = None) -> ScrapeResult:
        return ScrapeResult(ok=False, status=status, httpStatus=http, sourceUrl=url,
                            vendor=self.vendor, error=error,
                            scrapedAt=datetime.now(timezone.utc).isoformat())

    def _browser_html(self, url: str) -> tuple[int, str]:
        from scrapling.fetchers import DynamicFetcher
        page = DynamicFetcher.fetch(url, headless=True, network_idle=True, wait=3000)
        # 실제 상태를 버리고 200 으로 고정하면 404(원본 소멸)가 "판정 불가"로 묻힌다.
        # Ocado 131건이 이렇게 사라졌다(2026-08-30 실측).
        status = getattr(page, "status", None) or 200
        return int(status), page.html_content

    def fetch_html(self, url: str) -> tuple[int, str]:
        http, html, final = self.fetch_html_with_final(url)
        return http, html

    def fetch_html_with_final(self, url: str) -> tuple[int, str, str]:
        """(상태, HTML, 최종 URL). 최종 URL 은 리다이렉트로 상품이 사라졌는지 판별하는 근거다."""
        if self.needs_browser:
            http, html = self._browser_html(url)
            return http, html, url
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                                   "Accept-Language": "en-GB,en;q=0.9"})
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                return res.status, res.read().decode("utf-8", "replace"), res.url
        except urllib.error.HTTPError as e:
            return e.code, "", url

    def scrape(self, url: str) -> ScrapeResult:
        now = datetime.now(timezone.utc).isoformat()
        try:
            http, html, final_url = self.fetch_html_with_final(url)
        except Exception as e:  # noqa: BLE001
            return self._fail(url, "error", "fetch 실패: %s" % e)

        if self.product_path_marker and self.product_path_marker in url \
                and self.product_path_marker not in (final_url or ""):
            return self._fail(url, "not_found",
                              "상품 페이지가 사라져 다른 곳으로 이동됨: %s" % final_url, http)

        if http == 404:
            return self._fail(url, "not_found", "상품 페이지 없음(404)", http)
        if http in (403, 429) or (http == 202 and not html):
            return self._fail(url, "blocked", "봇 차단 의심(HTTP %s)" % http, http)
        if http != 200 or not html:
            return self._fail(url, "error", "페이지 응답 실패(HTTP %s)" % http, http)

        product, offer = parse_product(html)
        if product is None and not self.needs_browser:
            # 같은 사이트라도 일부 페이지는 JSON-LD 를 서버에서 안 내려준다(Costco 실측).
            # 다른 스크래퍼로 넘기는 게 아니라 같은 일을 브라우저 통로로 한 번 더 하는 것이다.
            try:
                http, html = self._browser_html(url)
                product, offer = parse_product(html)
                # 상품이 사라지면 JS 가 홈으로 밀어낸다(Costco 실측). HTTP 리다이렉트가 아니라
                # 최종 URL 로는 못 잡는다 — 렌더된 제목이 홈 제목이면 소멸로 본다.
                if product is None and self.home_title_marker:
                    title = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.S)
                    if title and self.home_title_marker in title.group(1):
                        return self._fail(url, "not_found",
                                          "상품 페이지가 홈으로 밀림 — 원본 소멸", http)
            except Exception as e:  # noqa: BLE001
                return self._fail(url, "error", "브라우저 재시도 실패: %s" % e, http)
        if product is None:
            return self._fail(url, "error", "JSON-LD 에 Product 가 없다 — 레이아웃 변경 의심", http)

        price = offer.get("price")
        if price is None:
            return self._fail(url, "error", "JSON-LD offers 에 price 가 없다 — 가격 확정 불가", http)
        try:
            price = float(str(price).replace(",", ""))
        except ValueError:
            return self._fail(url, "error", "가격 파싱 실패: %r" % price, http)

        avail_token = str(offer.get("availability") or "").rstrip("/").rsplit("/", 1)[-1].lower()
        if avail_token in _DISCONTINUED:
            return self._fail(url, "discontinued", "판매 종료·단종으로 표기됨", http)

        in_stock = availability_to_stock(offer.get("availability"))
        if in_stock is None:
            return self._fail(url, "error",
                              "availability 를 해석할 수 없다(%r) — 재고 판정 불가"
                              % offer.get("availability"), http)

        name = product.get("name")
        weight = parse_weight_grams(product.get("weight") or "") or parse_weight_grams(name or "")

        return ScrapeResult(
            ok=True, status="ok", httpStatus=http, sourceUrl=url, vendor=self.vendor,
            name=name, price=price,
            currency=offer.get("priceCurrency") or "GBP",
            inStock=in_stock, availabilityText=str(offer.get("availability")),
            weightGrams=weight,
            brandKo=_brand_name(product.get("brand")),
            sku=str(product.get("sku")) if product.get("sku") is not None else None,
            scrapedAt=now,
        )


    def to_detail(self, result: ScrapeResult) -> ProductDetail:
        return ProductDetail(
            ok=result.ok, status=result.status, httpStatus=result.httpStatus,
            sourceUrl=result.sourceUrl, nameKo=result.name, brandKo=result.brandKo,
            inStock=result.inStock, shippingWeightGrams=result.weightGrams,
            error=result.error, scrapedAt=result.scrapedAt)

    def fetch_detail(self, url: str) -> ProductDetail:
        return self.to_detail(self.scrape(url))


class OcadoScraper(JsonLdScraper):
    vendor = "OCD"
    host_markers = ("ocado.com",)
    needs_browser = True


class TescoScraper(JsonLdScraper):
    vendor = "TES"
    host_markers = ("tesco.com",)
    needs_browser = True


class CostcoUkScraper(JsonLdScraper):
    vendor = "COK"
    host_markers = ("costco.co.uk",)
    needs_browser = False
    product_path_marker = "/p/"
    home_title_marker = "Online Grocery Shopping"
