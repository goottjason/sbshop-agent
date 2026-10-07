"""Discovery must wait for product cards, not advertising network inactivity."""
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from scrapers.iherb import fetch_bestsellers


class IherbDiscoveryWaitTest(unittest.TestCase):
    def test_cards_ready_while_background_requests_continue_are_collected(self):
        card = SimpleNamespace(nameKo="검증용 상품")
        page = Mock(status=200, html_content="<title>상품 목록</title>")
        page.css.return_value = [Mock()]
        with patch("scrapers.iherb.DynamicFetcher.fetch", return_value=page) as fetch, \
                patch("scrapers.iherb.parse_card", return_value=card):
            cards, error = fetch_bestsellers("supplements")
        self.assertEqual(cards, [card])
        self.assertIsNone(error)
        self.assertFalse(fetch.call_args.kwargs["network_idle"])
        self.assertEqual(fetch.call_args.kwargs["wait_selector"], "div.product-cell")
        self.assertLessEqual(fetch.call_args.kwargs["timeout"], 40000)

    def test_blocked_page_is_not_reported_as_success(self):
        page = Mock(status=403, html_content="<title>Access denied</title>")
        with patch("scrapers.iherb.DynamicFetcher.fetch", return_value=page):
            cards, error = fetch_bestsellers("supplements")
        self.assertEqual(cards, [])
        self.assertIn("차단", error)

    def test_missing_cards_remains_visible_as_failure(self):
        page = Mock(status=200, html_content="<title>상품 목록</title>")
        page.css.return_value = []
        with patch("scrapers.iherb.DynamicFetcher.fetch", return_value=page):
            cards, error = fetch_bestsellers("supplements")
        self.assertEqual(cards, [])
        self.assertIn("상품 카드를 찾지 못함", error)
