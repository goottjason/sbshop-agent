from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from models import ProductDetail

VENDOR = "VTB"
HOST_MARKERS = ("vitabiotics.com",)
USER_AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
RATE_LIMIT_RETRIES = 4
RATE_LIMIT_BACKOFF_SEC = 3.0
RENAME_SUFFIXES = ("-tablets",)
_HANDLE_RE = re.compile(r"/products/([^/?#]+)")
_WORD_RE = re.compile(r"[^a-z0-9]+")


def supports(url: str) -> bool:
    return bool(url) and any(m in url.lower() for m in HOST_MARKERS)


def product_json_url(url: str) -> str | None:
    m = _HANDLE_RE.search(url or "")
    if not m:
        return None
    parts = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((parts.scheme or "https", parts.netloc,
                                    "/products/%s.js" % m.group(1), "", ""))


def product_handle(url: str) -> str | None:
    m = _HANDLE_RE.search(url or "")
    return m.group(1) if m else None


def title_matches_handle(title: str | None, handle: str) -> bool:
    words = set(_WORD_RE.sub(" ", (title or "").lower()).split())
    tokens = [t for t in (handle or "").split("-") if t]
    return bool(tokens) and all(t in words for t in tokens)


def requested_variant_id(url: str) -> str | None:
    parts = urllib.parse.urlsplit(url or "")
    values = urllib.parse.parse_qs(parts.query).get("variant")
    return values[0] if values else None


def select_variant(payload: dict, variant_id: str | None) -> tuple[dict | None, str | None]:
    variants = payload.get("variants") or []
    if not variants:
        return None, "variant 목록이 비어 있음"
    if variant_id:
        for v in variants:
            if str(v.get("id")) == str(variant_id):
                return v, None
        return None, "URL이 지목한 variant %s 가 목록에 없음" % variant_id
    if len(variants) == 1:
        return variants[0], None
    titles = ", ".join(str(v.get("title")) for v in variants[:5])
    return None, ("variant %d개 — URL에 ?variant= 가 없어 규격을 특정할 수 없음 (%s)"
                  % (len(variants), titles))


def _get(url: str) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                               "Accept": "application/json"})
    for attempt in range(RATE_LIMIT_RETRIES):
        try:
            with urllib.request.urlopen(req, timeout=25) as res:
                return res.status, res.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < RATE_LIMIT_RETRIES - 1:
                time.sleep(RATE_LIMIT_BACKOFF_SEC * (attempt + 1))
                continue
            return e.code, ""
    return 429, ""


def _failed(url: str, status: str, error: str, http: int | None = None) -> ProductDetail:
    return ProductDetail(ok=False, status=status, httpStatus=http, sourceUrl=url,
                         error=error, scrapedAt=datetime.now(timezone.utc).isoformat())


def fetch_detail(url: str) -> ProductDetail:
    api = product_json_url(url)
    if api is None:
        return _failed(url, "error", "vitabiotics 상품 URL 형식이 아님(/products/{handle} 없음)")

    http, body = _get(api)
    renamed_from = None
    if http == 404:
        handle = product_handle(url)
        for suffix in RENAME_SUFFIXES:
            candidate = api[:-len(".js")] + suffix + ".js"
            alt_http, alt_body = _get(candidate)
            if alt_http != 200 or not alt_body:
                continue
            try:
                alt = json.loads(alt_body)
            except ValueError:
                continue
            if not title_matches_handle(alt.get("title"), handle):
                continue
            http, body, renamed_from = alt_http, alt_body, handle
            break
    if http == 404:
        return _failed(url, "not_found", "상품 페이지 없음(404) — 핸들이 사라졌거나 변경됨", http)
    if http == 429:
        return _failed(url, "blocked", "요청 한도 초과(429)", http)
    if http != 200 or not body:
        return _failed(url, "error", "상품 JSON 응답 실패(HTTP %s)" % http, http)

    try:
        payload = json.loads(body)
    except ValueError as e:
        return _failed(url, "error", "상품 JSON 파싱 실패: %s" % e, http)

    variant, reason = select_variant(payload, requested_variant_id(url))
    if variant is None:
        return _failed(url, "error", reason, http)

    barcode = (variant.get("barcode") or "").strip()
    if not barcode:
        return _failed(url, "error", "variant에 barcode 값이 없음", http)

    images = []
    featured = payload.get("featured_image")
    if featured:
        images.append(featured if featured.startswith("http") else "https:" + featured)

    price = variant.get("price")
    return ProductDetail(
        ok=True,
        status="ok",
        httpStatus=http,
        sourceUrl=url,
        externalId=str(payload.get("id")) if payload.get("id") is not None else None,
        nameKo=payload.get("title"),
        brandKo=payload.get("vendor"),
        partNumber=variant.get("sku"),
        upc=barcode,
        currency="GBP",
        priceKrw=None,
        inStock=bool(variant.get("available")),
        images=images,
        specs={"variantId": str(variant.get("id")), "variantTitle": variant.get("title"),
               "pricePence": price, "renamedFrom": renamed_from},
        scrapedAt=datetime.now(timezone.utc).isoformat(),
    )
