"""iHerb(kr.iherb.com) 스크래퍼 — 베스트셀러 목록 발굴 + 상품 상세 + 재고/가격.

왜 스크래핑인가: 기존 Java 경로(`IherbScraperClient`)가 쓰던 `catalog.app.iherb.com` JSON API는
2026-07 기준 Cloudflare 챌린지로 **403**을 반환한다(실측). Scrapling의 브라우저 페처는 통과한다.

kr.iherb.com을 쓰는 이유 — 한국 판매에 필요한 것이 전부 한글로 나온다:
  · 상품명(한글)           · 가격(원화, 환산 불필요)
  · **성분표(한글)**       → 식약처 반입차단 원료성분 대조에 그대로 투입
  · 배송무게 / UPC / 수량  → 마켓 필수필드(중량·바코드) 자동 충족

목록 페이지 카드 1장(`div.product-cell`)에서 스코어링에 필요한 신호가 전부 나오므로,
후보 발굴 단계에서는 상세 페이지를 열지 않는다(상세는 통관 게이트 통과 대상만).
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone

from scrapling.fetchers import DynamicFetcher

from models import CandidateCard, ProductDetail, ScrapeResult
from scrapers.base import VendorScraper

# ── 상수 ──────────────────────────────────────────────────────────────────────
BASE = "https://kr.iherb.com"
# sort=10 = 베스트셀러. iHerb 목록 페이지는 `p` 파라미터로 페이징한다.
BESTSELLER_URL = BASE + "/c/{slug}?sort=10&p={page}"

# 봇/차단 마커 — 걸리면 status=blocked로 스킵한다(빈 결과를 "후보 없음"으로 오인하면 안 됨).
#
# ⚠️ <title>에서만 찾는다. iHerb 정상 페이지도 reCAPTCHA 스크립트를 로드하므로 본문 전체 텍스트에서
#    "captcha"를 찾으면 멀쩡한 200 페이지가 전부 차단으로 오판된다(실측 — 정상 목록 페이지에 24회 등장).
BLOCK_TITLE_MARKERS = (
    "just a moment", "checking your browser", "attention required",
    "access denied", "forbidden", "blocked", "error 1", "captcha",
)
_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.S | re.I)

# "4.8/5 - 34,554 구매후기"
_RATING_RE = re.compile(r"([0-9.]+)\s*/\s*5\s*-\s*([0-9,]+)")
# "30일 동안 50,000+개 판매" / "30일 동안 1,000개 이상 판매"
_SALES_RE = re.compile(r"([0-9][0-9,]*)\s*\+?\s*개")
# "₩24,351" → 24351
_KRW_RE = re.compile(r"[0-9][0-9,]*")
# /pr/{slug}/{id} 또는 /product/{id}
_PRODUCT_ID_RE = re.compile(r"/(?:pr/[^/]+|product)/(\d+)")
# "0.06 kg" / "60 g"
_KG_RE = re.compile(r"([0-9.]+)\s*kg", re.IGNORECASE)
_G_RE = re.compile(r"([0-9.]+)\s*g\b", re.IGNORECASE)
# 상세 이미지에서 취할 유형(Campaign 배너·Image360 제외)
_WANTED_IMAGE_TYPES = ("Main", "Enhanced")


def extract_product_id(url: str) -> str | None:
    """iHerb 상품 URL에서 상품 ID를 뽑는다. Java 쪽 중복제외 키와 동일해야 한다."""
    if not url:
        return None
    m = _PRODUCT_ID_RE.search(url)
    return m.group(1) if m else None


def _krw(text: str | None) -> float | None:
    """'₩24,351' 또는 '24351' → 24351.0"""
    if not text:
        return None
    m = _KRW_RE.search(text.replace(" ", ""))
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", ""))
    except ValueError:
        return None


def _int(text: str | None) -> int | None:
    v = _krw(text)
    return int(v) if v is not None else None


def _bool_attr(v: str | None) -> bool:
    return str(v or "").strip().lower() == "true"


def _attr(el, name: str) -> str | None:
    """Scrapling 엘리먼트 속성 조회 — 구현 차이를 흡수한다."""
    try:
        v = el.attrib.get(name)
    except Exception:  # noqa: BLE001
        v = None
    if v is None:
        try:
            v = el.get(name)
        except Exception:  # noqa: BLE001
            v = None
    return v.strip() if isinstance(v, str) else None


def _text(el) -> str:
    if el is None:
        return ""
    try:
        return re.sub(r"\s+", " ", el.get_all_text() or "").strip()
    except Exception:  # noqa: BLE001
        return ""


def _first_text(scope, selector: str) -> str:
    try:
        els = scope.css(selector)
    except Exception:  # noqa: BLE001
        return ""
    return _text(els[0]) if els else ""


def _is_blocked(page) -> bool:
    """차단 판정 — HTTP 상태 + <title>만 본다(본문 전체 텍스트 매칭은 오판을 만든다)."""
    http = getattr(page, "status", None)
    if http in (401, 403, 429, 503):
        return True
    try:
        html = page.html_content or ""
    except Exception:  # noqa: BLE001
        return False
    m = _TITLE_RE.search(html)
    if not m:
        return False
    title = re.sub(r"\s+", " ", m.group(1)).strip().lower()
    return any(k in title for k in BLOCK_TITLE_MARKERS)


def _fetch(url: str, *, wait: int = 1500, timeout: int = 60000):
    return DynamicFetcher.fetch(url, headless=True, network_idle=True, wait=wait, timeout=timeout)


# ── 목록(베스트셀러) 파싱 ─────────────────────────────────────────────────────

def parse_card(cell, category_slug: str, page_no: int, index: int) -> CandidateCard | None:
    """`div.product-cell` 1장 → CandidateCard. 필수값(id/url)이 없으면 None."""
    links = cell.css("a.product-link")
    if not links:
        return None
    a = links[0]

    href = _attr(a, "href") or ""
    external_id = _attr(a, "data-product-id") or _attr(a, "data-ga-product-id") or extract_product_id(href)
    if not external_id or not href:
        return None

    # 상품명: title 속성이 가장 안정적(엘리먼트 텍스트는 bdi 중첩·공백 이슈가 있다).
    name_ko = _attr(a, "title") or _first_text(cell, "div.product-title")

    discount_price = _krw(_attr(a, "data-ga-discount-price"))
    list_price, discount_pct = discount_price, 0

    # data-cart-info JSON에 정가·할인율이 들어 있다(할인 상품 판별용).
    raw_cart = _attr(cell.css("button.btn-add-to-cart")[0], "data-cart-info") \
        if cell.css("button.btn-add-to-cart") else None
    if raw_cart:
        try:
            items = json.loads(raw_cart).get("lineItems") or []
            if items:
                li = items[0]
                list_price = _krw(li.get("listPrice")) or list_price
                discount_price = _krw(li.get("discountPrice")) or discount_price
                discount_pct = int(float(li.get("discountPercentage") or 0))
        except Exception:  # noqa: BLE001
            pass

    # 평점·리뷰수: `title="4.8/5 - 34,554 구매후기"`
    rating = review_count = None
    rating_els = cell.css("a.rating-count") or cell.css("a.stars")
    if rating_els:
        m = _RATING_RE.search(_attr(rating_els[0], "title") or "")
        if m:
            rating = float(m.group(1))
            review_count = int(m.group(2).replace(",", ""))

    # 최근 판매량: "30일 동안 50,000+개 판매" — iHerb가 노출할 때만 존재.
    sales_30d = None
    activity = _first_text(cell, ".recent-activity-message-wrapper")
    if activity:
        m = _SALES_RE.search(activity)
        if m:
            sales_30d = int(m.group(1).replace(",", ""))

    image_url = None
    imgs = cell.css("span.product-image img")
    if imgs:
        image_url = _attr(imgs[0], "src") or _attr(imgs[0], "data-src")

    # 랭킹: iHerb가 주는 position이 있으면 그대로, 없으면 페이지 내 순서로 보정.
    pos = _int(_attr(a, "data-ga-product-position"))
    if pos is None:
        pos = (page_no - 1) * 48 + index + 1

    return CandidateCard(
        vendor="IHB",
        externalId=str(external_id),
        sourceUrl=href.split("?")[0],
        partNumber=_attr(a, "data-part-number"),
        brand=_attr(a, "data-ga-brand-name"),
        brandCode=_attr(a, "data-ga-brand-id"),
        nameKo=name_ko,
        categorySlug=category_slug,
        currency="KRW",
        listPrice=list_price,
        discountPrice=discount_price,
        discountPct=discount_pct,
        rating=rating,
        reviewCount=review_count,
        sales30d=sales_30d,
        rankPosition=pos,
        isOutOfStock=_bool_attr(_attr(a, "data-ga-is-out-of-stock")),
        isDiscontinued=_bool_attr(_attr(a, "data-ga-is-discontinued")),
        isSponsored=_bool_attr(_attr(a, "data-sponsored")),
        imageUrl=image_url,
    )


def fetch_bestsellers(category_slug: str, page: int = 1) -> tuple[list[CandidateCard], str | None]:
    """베스트셀러 1페이지를 긁는다. 반환 (카드목록, 에러사유|None)."""
    url = BESTSELLER_URL.format(slug=category_slug, page=page)
    try:
        p = _fetch(url)
    except Exception as e:  # noqa: BLE001
        return [], f"fetch error: {e}"

    if _is_blocked(p):
        return [], f"bot/Cloudflare 차단 의심 (http={getattr(p, 'status', None)})"
    if getattr(p, "status", None) == 404:
        return [], "카테고리 페이지 없음(404)"

    cells = p.css("div.product-cell")
    if not cells:
        # 200인데 카드가 0개 = 레이아웃 변경 의심. "후보 없음"으로 조용히 넘기지 않는다.
        return [], "상품 카드를 찾지 못함(레이아웃 변경 의심)"

    cards: list[CandidateCard] = []
    for i, cell in enumerate(cells):
        try:
            card = parse_card(cell, category_slug, page, i)
        except Exception:  # noqa: BLE001
            card = None
        if card:
            cards.append(card)
    return cards, None


# ── 상세 파싱 ─────────────────────────────────────────────────────────────────

def _parse_spec_list(page) -> dict:
    """`#product-specs-list li` → {배송 무게, 상품 코드, UPC 코드, 상품 수량, 부피...}"""
    out: dict[str, str] = {}
    for li in page.css("#product-specs-list li"):
        t = _text(li)
        if ":" not in t:
            continue
        k, _, v = t.partition(":")
        out[k.strip()] = v.strip()
    return out


def _parse_sections(page) -> dict:
    """상세 본문 `h3 > strong` 제목 → 본문 텍스트 매핑 (제품소개/사용법/기타 부원료/주의사항).

    바깥 `div.item-row`가 나머지 item-row를 통째로 감싸고 있어(실측: 첫 '제품소개' 행이
    하위 3개 섹션을 포함) 그대로 순회하면 제품소개 자리에 성분표가 들어온다.
    → 하위에 다른 item-row를 품은 컨테이너 행은 건너뛴다.
    """
    out: dict[str, str] = {}
    for row in page.css("div.item-row"):
        try:
            # Scrapling의 엘리먼트 스코프 css()는 자기 자신도 매칭한다 → 1이면 중첩 없음, 2 이상이면 컨테이너.
            if len(row.css("div.item-row")) > 1:
                continue
        except Exception:  # noqa: BLE001
            pass
        title = _first_text(row, "h3 strong") or _first_text(row, "h3")
        if not title:
            continue
        body = _first_text(row, ".prodOverviewIngred") or _first_text(row, ".prodOverviewDetail")
        if not body:
            # 섹션마다 본문 클래스가 다르다(제품소개는 위 두 클래스를 쓰지 않는다)
            # → 행 전체 텍스트에서 제목만 떼어내 본문으로 삼는다.
            whole = _text(row)
            if whole.startswith(title):
                whole = whole[len(title):].strip()
            body = whole
        if body:
            out[title.strip()] = body
    return out


def _model_properties(page) -> dict:
    """`input#modelProperties`의 data-* 속성 — 상세 페이지에서 가장 신뢰할 수 있는 정형 데이터.

    가격(숫자형)·중량·치수·브랜드(한글)·루트 카테고리·재고상태·단종여부가 전부 여기 들어 있어
    본문 텍스트 셀렉터보다 레이아웃 변경에 강하다.
    """
    els = page.css("input#modelProperties")
    if not els:
        return {}
    el = els[0]
    try:
        return {k: v for k, v in dict(el.attrib).items() if k.startswith("data-")}
    except Exception:  # noqa: BLE001
        return {}


def _parse_images(page) -> list[str]:
    """대표+상세 이미지 원본(/l/) URL. Campaign 배너·360뷰는 제외한다."""
    urls: list[str] = []
    for w in page.css("div.img-wrapper"):
        if (_attr(w, "data-image-type") or "") not in _WANTED_IMAGE_TYPES:
            continue
        links = w.css("a")
        href = _attr(links[0], "href") if links else None
        if href and href not in urls:
            urls.append(href)
    return urls


def _weight_grams(raw: str | None) -> float | None:
    """'0.06 kg' / '60 g' → 그램. kg를 먼저 본다('0.06 kg'에서 g 정규식이 먼저 걸리면 안 됨)."""
    if not raw:
        return None
    m = _KG_RE.search(raw)
    if m:
        return float(m.group(1)) * 1000.0
    m = _G_RE.search(raw)
    return float(m.group(1)) if m else None


def fetch_detail(url: str) -> ProductDetail:
    """상품 상세 — 통관 게이트(성분)와 마켓 필수필드(중량·바코드·수량)를 위한 크롤."""
    now = datetime.now(timezone.utc).isoformat()
    external_id = extract_product_id(url)
    try:
        p = _fetch(url, wait=2000)
    except Exception as e:  # noqa: BLE001
        return ProductDetail(ok=False, status="error", sourceUrl=url, externalId=external_id,
                             error=f"fetch error: {e}", scrapedAt=now)

    http = getattr(p, "status", None)
    if _is_blocked(p):
        return ProductDetail(ok=False, status="blocked", httpStatus=http, sourceUrl=url,
                             externalId=external_id, error="bot/Cloudflare 차단 의심", scrapedAt=now)
    if http == 404:
        return ProductDetail(ok=False, status="not_found", httpStatus=http, sourceUrl=url,
                             externalId=external_id, inStock=False, error="상품 페이지 없음(404)",
                             scrapedAt=now)

    props = _model_properties(p)
    spec = _parse_spec_list(p)
    sections = _parse_sections(p)
    images = _parse_images(p)
    if not images and props.get("data-product-primary-image-url"):
        images = [props["data-product-primary-image-url"]]

    # 성분: "기타 부원료" 섹션에 '주요 성분'/'기타 성분'이 함께 들어온다.
    ingredients = sections.get("기타 부원료") or sections.get("성분") or ""
    main_ing = other_ing = None
    if ingredients:
        m = re.search(r"주요 성분\s*(.*?)(?:기타 성분|$)", ingredients, re.S)
        if m:
            main_ing = m.group(1).strip(" ,")
        m = re.search(r"기타 성분\s*(.*?)(?:이 상품은|$)", ingredients, re.S)
        if m:
            other_ing = m.group(1).strip(" ,")

    price = _krw(props.get("data-numeric-discounted-price")) or _krw(props.get("data-numeric-list-price"))
    name_ko = props.get("data-product-name") or _first_text(p, "h1") or None
    quantity = _int(_first_text(p, ".package-quantity")) or _int(spec.get("상품 수량"))
    # data-stock-status: "0" = 재고 있음. 값이 없으면 판단 보류(None) — 임의로 품절 처리하지 않는다.
    stock_status = props.get("data-stock-status")
    in_stock = (stock_status == "0") if stock_status is not None else None

    # 성분 추출 실패는 조용히 넘기지 않는다 — 호출측(통관 게이트)이 REVIEW로 승격시킨다.
    ok = bool(ingredients)
    return ProductDetail(
        ok=ok,
        status="ok" if ok else "error",
        httpStatus=http,
        sourceUrl=url,
        externalId=external_id or props.get("data-product-id"),
        nameKo=name_ko,
        brandKo=props.get("data-brand-name"),
        brandCode=props.get("data-brand-code"),
        rootCategory=props.get("data-root-category-name"),
        rootCategoryId=props.get("data-root-category-id"),
        isDiscontinued=str(props.get("data-is-discontinued", "")).lower() == "true",
        partNumber=props.get("data-part-number") or spec.get("상품 코드"),
        upc=spec.get("UPC 코드"),
        priceKrw=price,
        listPriceKrw=_krw(props.get("data-numeric-list-price")),
        currency="KRW",
        inStock=in_stock,
        shippingWeightGrams=_weight_grams(
            props.get("data-shipping-weight-kg") or _first_text(p, ".product-shipping-weight-label")),
        packageQuantity=quantity,
        dimensions=props.get("data-dimensions-cm") or spec.get("부피 및 배송 중량"),
        ingredientsRaw=ingredients or None,
        mainIngredients=main_ing,
        otherIngredients=other_ing,
        description=sections.get("제품소개"),
        usage=sections.get("사용법"),
        caution=sections.get("주의사항"),
        images=images,
        specs=spec,
        scrapedAt=now,
        error=None if ok else "성분 정보를 찾지 못함(200)",
    )


# ── VendorScraper 구현 (재고·가격 배치용, Fortnum과 동일 계약) ────────────────

class IherbScraper(VendorScraper):
    """재고/가격 배치가 쓰는 벤더 스크래퍼. 목록·상세는 위 모듈 함수를 직접 쓴다."""

    vendor = "IHB"
    DOMAIN = "iherb.com"

    def supports(self, url: str) -> bool:
        return self.DOMAIN in (url or "")

    def scrape(self, url: str) -> ScrapeResult:
        now = datetime.now(timezone.utc).isoformat()
        try:
            p = _fetch(url, wait=2000)
        except Exception as e:  # noqa: BLE001
            return ScrapeResult(ok=False, status="error", sourceUrl=url, vendor=self.vendor,
                                error=f"fetch error: {e}", scrapedAt=now)

        http = getattr(p, "status", None)
        if _is_blocked(p):
            # 차단 시 재고를 건드리면 멀쩡한 상품이 오품절된다 → 스킵.
            return ScrapeResult(ok=False, status="blocked", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error="bot/Cloudflare 차단 의심", scrapedAt=now)
        if http == 404:
            return ScrapeResult(ok=False, status="not_found", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, inStock=False, error="상품 페이지 없음(404)",
                                scrapedAt=now)

        props = _model_properties(p)
        price = _krw(props.get("data-numeric-discounted-price")) or _krw(props.get("data-numeric-list-price"))
        name = props.get("data-product-name") or _first_text(p, "h1") or None

        # 재고: data-stock-status "0"=재고있음. 속성이 없으면 판정 불가로 보고 스킵한다
        # (본문 텍스트 매칭으로 추측하면 멀쩡한 상품이 오품절된다).
        stock_status = props.get("data-stock-status")
        discontinued = str(props.get("data-is-discontinued", "")).lower() == "true"

        ok = price is not None and stock_status is not None
        if not ok:
            return ScrapeResult(
                ok=False, status="error", httpStatus=http, sourceUrl=url, vendor=self.vendor,
                name=name, currency="KRW",
                error="modelProperties에서 가격/재고상태를 찾지 못함(200, 레이아웃 변경 의심)",
                scrapedAt=now)
        in_stock = stock_status == "0" and not discontinued
        return ScrapeResult(
            ok=True,
            status="ok",
            httpStatus=http,
            sourceUrl=url,
            vendor=self.vendor,
            name=name,
            # kr.iherb.com은 원화 표기 → 환산 없이 그대로 원가로 쓴다.
            price=price,
            currency="KRW",
            goodsKrw=int(price),
            shippingKrw=0,          # 배대지 배송비는 Java 배치가 별도 가산한다
            costKrw=int(price),
            inStock=in_stock,
            availabilityText=("단종" if discontinued else "판매중" if in_stock else "품절"),
            weightGrams=_weight_grams(props.get("data-shipping-weight-kg")),
            sku=props.get("data-part-number"),
            scrapedAt=now,
        )
