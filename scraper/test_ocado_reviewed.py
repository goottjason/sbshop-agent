"""Network-free checks against minimal, real Ocado product fragments."""
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from lxml import html
from scrapers.ocado_reviewed import ReviewedFailure, fetch_reviewed, parse_reviewed, product_id

URL = "https://www.ocado.com/products/cirio-tomato-puree-80259011"
FINAL = "https://www.ocado.com/products/cirio-italian-tomato-puree/80259011"
FIXTURE = Path(__file__).parent / "fixtures/ocado-cirio-reviewed-2026-09-08.html"


class OcadoReviewedTest(unittest.TestCase):
    def parse(self, document=None, mode="CONTENT", url=URL, final=FINAL, status=200):
        return parse_reviewed(document or FIXTURE.read_text(), url, final, status, mode)

    def mutate(self, change):
        root = html.fromstring(FIXTURE.read_text())
        node = root.xpath('//script[@type="application/ld+json"]')[0]
        value = json.loads(node.text)
        change(value)
        node.text = json.dumps(value)
        return html.tostring(root, encoding="unicode")

    def assert_failure(self, document, code, **kwargs):
        with self.assertRaises(ReviewedFailure) as failure:
            self.parse(document, **kwargs)
        self.assertEqual(failure.exception.code, code)

    def test_actual_product_keeps_full_detail_not_reviews_or_recommendations(self):
        result = self.parse()
        self.assertEqual((result["externalId"], result["price"], result["currency"], result["inStock"]), ("80259011", "1.50", "GBP", True))
        self.assertIsNone(result["stock"])
        self.assertEqual(len(result["images"]), 1)
        for expected in ("Product Information", "Ingredients", "Tomatoes", "Nutritional data", "Storage", "Return to Address"):
            self.assertIn(expected, result["detailHtml"])
        for excluded in ("Customer reviews", "You May Also Like", "Similar Products", "class=", "data-test="):
            self.assertNotIn(excluded, result["detailHtml"])

    def test_second_actual_product_uses_its_own_schema_and_sections(self):
        result = self.parse((FIXTURE.parent / "ocado-oats-reviewed-2026-09-08.html").read_text(),
                            url="https://www.ocado.com/products/mornflake-jumbo-oats-450106011",
                            final="https://www.ocado.com/products/mornflake-jumbo-oats/450106011")
        self.assertEqual((result["externalId"], result["price"]), ("450106011", "1.30"))
        self.assertIn("Nutritional data", result["detailHtml"])
        self.assertIn("Ingredients", result["detailHtml"])

    def test_price_stock_does_not_require_gallery_or_detail_but_never_invents_stock(self):
        root = html.fromstring(FIXTURE.read_text())
        root.xpath('//li[@data-test="product-image-slide"]')[0].drop_tree()
        root.xpath('//h2[normalize-space(.)="Product Information"]')[0].getparent().drop_tree()
        result = self.parse(html.tostring(root, encoding="unicode"), mode="PRICE_STOCK")
        self.assertIsNone(result["stock"])
        self.assertEqual(result["images"], [])
        self.assertIsNone(result["detailHtml"])
        self.assertFalse(result["imagesComplete"])
        self.assertFalse(result["detailComplete"])

    def test_product_id_and_final_destination_must_match_not_first_product(self):
        self.assert_failure(self.mutate(lambda p: p.update(sku="450106011")), "SOURCE_IDENTITY_MISMATCH")
        self.assert_failure(FIXTURE.read_text(), "SOURCE_IDENTITY_MISMATCH", final=FINAL.replace("80259011", "450106011"))
        root = html.fromstring(FIXTURE.read_text())
        script = root.xpath('//script')[0]
        script.text = json.dumps([json.loads(script.text), json.loads(script.text)])
        self.assert_failure(html.tostring(root, encoding="unicode"), "SOURCE_IDENTITY_MISMATCH")

    def test_multioffer_currency_and_numeric_coercion_are_rejected(self):
        self.assert_failure(self.mutate(lambda p: p.update(offers=[p["offers"]])), "SOURCE_VARIANT_UNRESOLVED")
        for change in ({"priceCurrency": None}, {"priceCurrency": "USD"}, {"price": 1.5}, {"price": "0"}, {"price": "NaN"}):
            with self.subTest(change=change):
                self.assert_failure(self.mutate(lambda p: p["offers"].update(change)), "SOURCE_PRICE_INVALID")

    def test_only_explicit_in_or_out_of_stock_is_accepted(self):
        for availability in (None, True, "https://schema.org/LimitedAvailability", "https://schema.org/Discontinued"):
            self.assert_failure(self.mutate(lambda p: p["offers"].update(availability=availability)), "SOURCE_STOCK_INVALID")
        result = self.parse(self.mutate(lambda p: p["offers"].update(availability="https://schema.org/OutOfStock")))
        self.assertFalse(result["inStock"])
        self.assertIsNone(result["stock"])

    def test_full_gallery_keeps_extra_slides_and_excludes_unrelated_images(self):
        root = html.fromstring(FIXTURE.read_text())
        first = root.xpath('//li[@data-test="product-image-slide"]')[0]
        second = html.fromstring(html.tostring(first))
        second.set("data-id", "1")
        second_image = second.xpath('.//img')[0]
        second_image.set("src", second_image.get("src").replace("685ba7e9", "785ba7e9"))
        first.addnext(second)
        first.getparent().addnext(html.fromstring('<img src="https://evil.example/recommended.jpg">'))
        result = self.parse(html.tostring(root, encoding="unicode"))
        self.assertEqual(len(result["images"]), 2)
        self.assertEqual(result["images"][1], second_image.get("src"))
        second.set("data-id", "2")
        self.assert_failure(html.tostring(root, encoding="unicode"), "SOURCE_IMAGES_INVALID")

    def test_hero_identity_and_entire_gallery_contract_are_required(self):
        root = html.fromstring(FIXTURE.read_text())
        root.xpath('//li//img')[0].set("src", "https://evil.example/image.jpg")
        self.assert_failure(html.tostring(root, encoding="unicode"), "SOURCE_IMAGES_INVALID")
        self.assert_failure(self.mutate(lambda p: p.update(image=[])), "SOURCE_IMAGES_INVALID")

    def test_detail_boundary_and_product_description_must_be_proven(self):
        self.assert_failure(self.mutate(lambda p: p.update(description="Another product")), "SOURCE_DETAILS_INVALID")
        root = html.fromstring(FIXTURE.read_text())
        for node in root.xpath('//*[@data-test="similar-products-carousel" or @data-test="bop-reviews-container"]'):
            node.drop_tree()
        self.assert_failure(html.tostring(root, encoding="unicode"), "SOURCE_DETAILS_INVALID")
        root = html.fromstring(FIXTURE.read_text())
        root.xpath('//h2[normalize-space(.)="Ingredients"]')[0].getparent().addnext(html.fromstring('<div>Unrecognized layout</div>'))
        self.assert_failure(html.tostring(root, encoding="unicode"), "SOURCE_DETAILS_INVALID")

    def test_script_external_link_and_event_handlers_are_not_stored(self):
        root = html.fromstring(FIXTURE.read_text())
        ingredients = root.xpath('//h2[normalize-space(.)="Ingredients"]')[0].getparent()
        ingredients.append(html.fromstring('<div onclick="evil()"><script>secret()</script><a href="https://evil.example">plain text</a><img src="https://evil.example/a.jpg"></div>'))
        detail = self.parse(html.tostring(root, encoding="unicode"))["detailHtml"]
        self.assertIn("plain text", detail)
        for excluded in ("secret", "<script", "onclick", "https://", "<img"):
            self.assertNotIn(excluded, detail)

    def test_blocked_status_or_missing_product_never_becomes_zero_stock(self):
        for status in (202, 403, 404, 429, 500):
            self.assert_failure(FIXTURE.read_text(), "SOURCE_HTTP_FAILED", status=status)
        self.assert_failure('<html><title>Access denied</title></html>', "SOURCE_IDENTITY_MISMATCH")

    def test_unsafe_url_is_rejected_before_browser_launch(self):
        for url in (URL.replace("https:", "http:"), URL + "?x=1", URL + "#fragment", URL.replace("www.ocado.com", "www.ocado.com.evil.example"), URL.replace("www.ocado.com", "user@www.ocado.com"), URL.replace("www.ocado.com", "www.ocado.com:443"), "https://www.ocado.com/products/no-id"):
            with self.subTest(url=url), self.assertRaises(ReviewedFailure):
                product_id(url)

    def test_429_captures_retry_after_and_blocks_later_browser_requests(self):
        with patch("scrapling.fetchers.DynamicFetcher.fetch") as fetch:
            def browser(url, **kwargs):
                self.assertEqual(kwargs["retries"], 1)
                page = SimpleNamespace(main_frame=object())
                handlers = {}
                page.route = lambda pattern, callback: handlers.update(route=callback)
                page.on = lambda event, callback: handlers.update(response=callback)
                kwargs["page_setup"](page)
                request = SimpleNamespace(url=URL, frame=page.main_frame, is_navigation_request=lambda: True)
                handlers["response"](SimpleNamespace(url=URL, status=429, headers={"retry-after": "600"}, request=request))
                later = SimpleNamespace(request=request, abort=Mock(), continue_=Mock())
                handlers["route"](later)
                later.abort.assert_called_once()
                later.continue_.assert_not_called()
                return SimpleNamespace(url=FINAL, html_content=FIXTURE.read_text())
            fetch.side_effect = browser
            with self.assertRaises(ReviewedFailure) as failure:
                fetch_reviewed(URL, "CONTENT")
            self.assertEqual((failure.exception.http_status, failure.exception.retry_after), (429, 600))

    def test_endpoint_preserves_429_header_and_safe_reason(self):
        from app import scrape_reviewed_ocado
        from models import ReviewedOcadoRequest
        with patch("app.fetch_reviewed", side_effect=ReviewedFailure("SOURCE_THROTTLED", 429, 600)):
            response = scrape_reviewed_ocado(ReviewedOcadoRequest(url=URL, mode="CONTENT"))
        self.assertEqual(response.status_code, 429)
        self.assertEqual(response.headers["retry-after"], "600")
        self.assertEqual(json.loads(response.body), {"ok": False, "errorCode": "SOURCE_THROTTLED"})


if __name__ == "__main__":
    unittest.main()
