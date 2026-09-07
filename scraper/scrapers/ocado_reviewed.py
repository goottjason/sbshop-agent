"""Ocado facts for the durable, reviewed refresh flow.

Contract observed on 2026-09-08: exact Product.sku / one GBP Offer and the
bop-view gallery / contiguous Product Information blocks. No legacy defaults.
Only these small product fragments leave this process; full pages are not saved.
"""
from __future__ import annotations

import html as html_escape
import json
import re
import threading
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

from lxml import etree, html

CONTRACT = "ocado-reviewed-v1"
_PRODUCT_PATH = re.compile(r"/products/(?:[a-z0-9]+(?:-[a-z0-9]+)*/|[a-z0-9]+(?:-[a-z0-9]+)*-)([1-9][0-9]{0,19})/?")
_IMAGE_PATH = re.compile(r"/images-v3/[0-9a-f-]{36}/[0-9a-f-]{36}/[1-9][0-9]{1,3}x[1-9][0-9]{1,3}\.(?:jpg|webp)")
_LOCK = threading.Lock()
_SAFE_TAGS = {"div", "p", "br", "h2", "h3", "h4", "span", "strong", "b", "em", "i", "u", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "sup", "sub"}
_DROP_TAGS = {"script", "style", "iframe", "object", "embed", "svg", "form", "button", "input", "textarea", "select", "img"}


class ReviewedFailure(Exception):
    def __init__(self, code: str, http_status: int = 422, retry_after: int | None = None):
        super().__init__(code)
        self.code, self.http_status, self.retry_after = code, http_status, retry_after


def product_id(url: str) -> str:
    if not isinstance(url, str) or len(url) > 2000 or re.search(r"[\s<>\"'\\]", url):
        raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
    parsed = urlsplit(url)
    match = _PRODUCT_PATH.fullmatch(parsed.path)
    if parsed.scheme != "https" or parsed.netloc != "www.ocado.com" or parsed.query or parsed.fragment or not match:
        raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
    return match.group(1)


def image_url(value: str) -> str:
    if not isinstance(value, str) or len(value) > 2000:
        raise ReviewedFailure("SOURCE_IMAGES_INVALID")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or parsed.netloc != "www.ocado.com" or parsed.query or parsed.fragment or not _IMAGE_PATH.fullmatch(parsed.path):
        raise ReviewedFailure("SOURCE_IMAGES_INVALID")
    return value


def _products(node):
    # Only schema roots / @graph entries. A related product nested in an offer is
    # never a substitute for the requested page's Product.
    if isinstance(node, list):
        for child in node:
            yield from _products(child)
    elif isinstance(node, dict):
        if node.get("@type") == "Product":
            yield node
        if "@graph" in node:
            yield from _products(node["@graph"])


def _text(node) -> str:
    return " ".join(node.text_content().split())


def _safe_fragment(node) -> str:
    tag = node.tag.lower() if isinstance(node.tag, str) else ""
    if tag in _DROP_TAGS or not tag:
        return ""
    body = html_escape.escape(node.text or "")
    for child in node:
        body += _safe_fragment(child) + html_escape.escape(child.tail or "")
    if tag not in _SAFE_TAGS:
        return body
    attrs = ""
    if tag in {"td", "th"}:
        for key in ("colspan", "rowspan"):
            value = node.get(key, "")
            if re.fullmatch(r"[1-9][0-9]?", value):
                attrs += f' {key}="{value}"'
    return f"<{tag}{attrs}>" + ("" if tag == "br" else body + f"</{tag}>")


def _content(root, product) -> tuple[list[str], str]:
    views = root.xpath('//*[@data-test="bop-view"]')
    if len(views) != 1:
        raise ReviewedFailure("SOURCE_DETAILS_INVALID")
    view = views[0]
    slides = view.xpath('.//li[@data-test="product-image-slide"]')
    if not 1 <= len(slides) <= 8:
        raise ReviewedFailure("SOURCE_IMAGES_INVALID")
    images = []
    for index, slide in enumerate(slides):
        nodes = slide.xpath('./button[@data-test="product-image-button"]/img')
        if slide.get("data-id") != str(index) or len(nodes) != 1:
            raise ReviewedFailure("SOURCE_IMAGES_INVALID")
        images.append(image_url(nodes[0].get("src")))
    ld_images = product.get("image")
    if len(set(images)) != len(images) or not isinstance(ld_images, list) or not ld_images or images[0] != image_url(ld_images[0]):
        raise ReviewedFailure("SOURCE_IMAGES_INVALID")
    if any(image_url(image) not in images for image in ld_images):
        raise ReviewedFailure("SOURCE_IMAGES_INVALID")
    headings = view.xpath('.//h2[normalize-space(.)="Product Information"]')
    if len(headings) != 1:
        raise ReviewedFailure("SOURCE_DETAILS_INVALID")
    start = headings[0].getparent()
    sections, names, reached_boundary = [], set(), False
    for block in (start, *start.itersiblings()):
        if block.get("data-test") in {"similar-products-carousel", "bop-reviews-container"}:
            reached_boundary = True
            break
        titles = block.xpath("./h2")
        if block.tag != "div" or len(titles) != 1 or block.get("data-test") is not None:
            raise ReviewedFailure("SOURCE_DETAILS_INVALID")
        name = _text(titles[0])
        if not name or name in names or len(sections) >= 40:
            raise ReviewedFailure("SOURCE_DETAILS_INVALID")
        names.add(name)
        sections.append(_safe_fragment(block))
    # The exact description must belong to the same JSON-LD Product. The other
    # sibling blocks (ingredients, nutrition, storage, etc.) are all retained.
    description = product.get("description")
    children = list(start)
    if not isinstance(description, str) or not description.strip() or len(children) != 2 or not reached_boundary:
        raise ReviewedFailure("SOURCE_DETAILS_INVALID")
    try:
        description_text = _text(html.fragment_fromstring(description, create_parent="div"))
    except (ValueError, etree.ParserError):
        raise ReviewedFailure("SOURCE_DETAILS_INVALID") from None
    if description_text != _text(children[1]):
        raise ReviewedFailure("SOURCE_DETAILS_INVALID")
    detail = "".join(sections)
    if not detail or len(detail.encode("utf-8")) > 200_000:
        raise ReviewedFailure("SOURCE_DETAILS_INVALID")
    return images, detail


def parse_reviewed(page_html: str, requested_url: str, final_url: str, http_status: int, mode: str) -> dict:
    expected_id = product_id(requested_url)
    if product_id(final_url) != expected_id:
        raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
    if type(http_status) is not int or http_status != 200:
        raise ReviewedFailure("SOURCE_HTTP_FAILED", http_status if http_status in {403, 404, 429} else 502)
    if mode not in {"CONTENT", "PRICE_STOCK"}:
        raise ReviewedFailure("SOURCE_JSON_INVALID")
    if not isinstance(page_html, str) or not page_html.strip() or len(page_html.encode("utf-8")) > 5_000_000:
        raise ReviewedFailure("SOURCE_JSON_INVALID")
    try:
        root = html.fromstring(page_html, parser=html.HTMLParser(no_network=True))
        products = []
        for script in root.xpath('//script[@type="application/ld+json"]'):
            products.extend(_products(json.loads(script.text or "")))
    except (ValueError, etree.ParserError):
        raise ReviewedFailure("SOURCE_JSON_INVALID") from None
    if len(products) != 1 or products[0].get("sku") != expected_id:
        raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
    product = products[0]
    name = product.get("name")
    title = root.xpath('//*[@data-test="bop-view"]//h1')
    if not isinstance(name, str) or not name.strip() or len(title) != 1 or _text(title[0]) != " ".join(name.split()):
        raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
    offer = product.get("offers")
    if not isinstance(offer, dict) or offer.get("@type") != "Offer":
        raise ReviewedFailure("SOURCE_VARIANT_UNRESOLVED")
    if offer.get("priceCurrency") != "GBP" or not isinstance(offer.get("price"), str) or not re.fullmatch(r"[0-9]{1,7}(?:\.[0-9]{1,2})?", offer["price"]):
        raise ReviewedFailure("SOURCE_PRICE_INVALID")
    try:
        price = Decimal(offer["price"])
        if not price.is_finite() or price <= 0:
            raise InvalidOperation()
    except InvalidOperation:
        raise ReviewedFailure("SOURCE_PRICE_INVALID") from None
    availability = offer.get("availability")
    if availability not in {"https://schema.org/InStock", "https://schema.org/OutOfStock"}:
        raise ReviewedFailure("SOURCE_STOCK_INVALID")
    images, detail = _content(root, product) if mode == "CONTENT" else ([], None)
    return {"contractVersion": CONTRACT, "ok": True, "vendor": "OCD", "mode": mode,
            "requestedUrl": requested_url, "sourceUrl": final_url, "externalId": expected_id,
            "name": name, "price": str(price), "currency": "GBP",
            "inStock": availability == "https://schema.org/InStock", "stock": None,
            "images": images, "detailHtml": detail,
            "imagesComplete": mode == "CONTENT", "detailComplete": mode == "CONTENT",
            "scrapedAt": datetime.now(timezone.utc).isoformat()}


def _retry_after(value: str | None) -> int:
    now = datetime.now(timezone.utc)
    try:
        if value and value.strip().isdigit():
            return max(300, int(value.strip()))
        if value:
            return max(300, int((parsedate_to_datetime(value) - now).total_seconds()) + 1)
    except (ValueError, TypeError, OverflowError):
        pass
    return 300


def fetch_reviewed(url: str, mode: str) -> dict:
    expected_id = product_id(url)
    if mode not in {"CONTENT", "PRICE_STOCK"}:
        raise ReviewedFailure("SOURCE_JSON_INVALID")
    if not _LOCK.acquire(blocking=False):
        raise ReviewedFailure("SOURCE_REQUEST_BUSY", 503)
    try:
        from scrapling.fetchers import DynamicFetcher
        evidence = {"setup": False, "documents": [], "retry_after": None, "identity_failed": False}

        def setup(page):
            def before_request(route):
                request = route.request
                if evidence["retry_after"] is not None and urlsplit(request.url).hostname == "www.ocado.com":
                    return route.abort()
                if request.is_navigation_request() and request.frame == page.main_frame:
                    try:
                        if product_id(request.url) != expected_id:
                            raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
                    except ReviewedFailure:
                        evidence["identity_failed"] = True
                        return route.abort()
                route.continue_()

            def on_response(response):
                if urlsplit(response.url).hostname == "www.ocado.com" and response.status == 429:
                    delay = _retry_after(response.headers.get("retry-after"))
                    evidence["retry_after"] = max(evidence["retry_after"] or 300, delay)
                request = response.request
                if request.is_navigation_request() and request.frame == page.main_frame:
                    evidence["documents"].append((response.status, response.url))

            page.route("**/*", before_request)
            page.on("response", on_response)
            evidence["setup"] = True

        try:
            page = DynamicFetcher.fetch(url, headless=True, network_idle=False, load_dom=True,
                                        wait=3000, timeout=45000, retries=1, google_search=False,
                                        page_setup=setup)
        except Exception:
            if evidence["retry_after"] is not None:
                raise ReviewedFailure("SOURCE_THROTTLED", 429, evidence["retry_after"]) from None
            if evidence["identity_failed"]:
                raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH") from None
            raise ReviewedFailure("SOURCE_REQUEST_FAILED", 502) from None
        if evidence["retry_after"] is not None:
            raise ReviewedFailure("SOURCE_THROTTLED", 429, evidence["retry_after"])
        if evidence["identity_failed"]:
            raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
        if not evidence["setup"] or not evidence["documents"]:
            raise ReviewedFailure("SOURCE_HTTP_FAILED", 502)
        status, document_url = evidence["documents"][-1]
        if product_id(document_url) != expected_id:
            raise ReviewedFailure("SOURCE_IDENTITY_MISMATCH")
        return parse_reviewed(page.html_content, url, page.url, status, mode)
    finally:
        _LOCK.release()
