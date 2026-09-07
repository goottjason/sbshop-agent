from datetime import datetime, timezone
from unittest import TestCase, mock
from browser import Browser, BrowserError

class PublicBrowserTest(TestCase):
    def setUp(self):
        self.browser = Browser('http://127.0.0.1:4444', '/tmp/unread-public-browser-fixture.json')
        self.browser.session = 'fixture'
        self.calls = []
        def request(path, payload=None, method=None):
            self.calls.append((path, payload, method))
            if path.endswith('/window/new'): return {'handle':'owned'}
            if path.endswith('/window/handles'): return ['original','owned']
            if path.endswith('/window') and payload is None and method is None: return 'original'
        self.browser.request = mock.Mock(side_effect=request)
        self.target = dict(market='AUCTION', externalId='D888859044', sellerAccount='seller-fixture', registrationId=2,
            expectedRevision=5, cafe24ProductNo='10186', cafe24ProductCode='P0000PBU',
            publicUrl='https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044')
        self.snapshot = dict(schemaVersion=1, source='LIVE_CHROME_PUBLIC_MARKET', market='AUCTION', externalId='D888859044',
            url=self.target['publicUrl'], productIds=['D888859044'], sellerAccounts=['seller-fixture'], prices=['95500'],
            quantities=['500'], errorPage=False, quantityBasis='PUBLIC_NO_OPTION_REMAINING', capturedAt=datetime.now(timezone.utc).isoformat())
        self.browser.execute = mock.Mock(side_effect=lambda script: None if "performance.getEntries" in script else self.snapshot)

    def test_readonly_reader_returns_upload_context_without_changing_target(self):
        result = self.browser.observe_public(self.target)
        self.assertEqual(result['expectedRevision'], 5)
        self.assertEqual(result['observation']['values']['salePrice'], '95500')
        self.assertIn(('/session/fixture/url', {'url': self.target['publicUrl']}, None), self.calls)
        self.assertEqual(self.calls[-3:], [('/session/fixture/window', {'handle':'owned'}, None),
            ('/session/fixture/window', None, 'DELETE'), ('/session/fixture/window', {'handle':'original'}, None)])
        self.assertNotIn('document.body.innerText', str(result))

    def test_unverified_destination_and_context_never_navigate(self):
        for patch in [dict(publicUrl='https://example.com/'), dict(expectedRevision=5.5), dict(registrationId=True), dict(cafe24ProductCode=None)]:
            with self.subTest(patch=patch), self.assertRaises(BrowserError): self.browser.observe_public(self.target | patch)
        self.browser.request.assert_not_called()

    def test_real_error_page_cannot_become_observation_or_deletion(self):
        self.snapshot.update(errorPage=True, prices=[], sellerAccounts=[])
        with self.assertRaisesRegex(BrowserError, 'PUBLIC_PAGE_UNVERIFIED'): self.browser.observe_public(self.target)

    def test_navigation_failure_restores_original_and_closes_only_owned_tab(self):
        original = self.browser.request.side_effect
        def request(path, payload=None, method=None):
            if path.endswith('/url'): raise BrowserError('BROWSER_COMMAND_FAILED')
            return original(path,payload,method)
        self.browser.request.side_effect = request
        with self.assertRaises(BrowserError): self.browser.observe_public(self.target)
        self.assertEqual(self.calls[-1],('/session/fixture/window', {'handle':'original'}, None))
        self.assertIn(('/session/fixture/window', None, 'DELETE'),self.calls)

    def test_actual_429_is_kept_but_error_page_without_status_never_infers_429(self):
        self.browser.execute.side_effect = lambda script: 429 if 'performance.getEntries' in script else self.snapshot
        with self.assertRaisesRegex(BrowserError,'HTTP_ERROR'): self.browser.observe_public(self.target)
        self.assertEqual(self.browser.public_http_status,429)
        self.browser.execute.side_effect = lambda script: 0 if 'performance.getEntries' in script else self.snapshot | {'errorPage':True}
        with self.assertRaisesRegex(BrowserError,'PUBLIC_PAGE_UNVERIFIED'): self.browser.observe_public(self.target)
        self.assertIsNone(self.browser.public_http_status)

    def test_site_closed_own_tab_never_closes_user_tab(self):
        original = self.browser.request.side_effect
        self.browser.request.side_effect = lambda path,payload=None,method=None: ['original'] if path.endswith('/window/handles') else original(path,payload,method)
        self.browser.observe_public(self.target)
        self.assertFalse(any(method=='DELETE' for path,payload,method in self.calls))
