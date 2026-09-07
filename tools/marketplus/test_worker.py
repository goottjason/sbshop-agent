import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import worker
import browser
from relay import atomic_save, RelayError
from test_relay import collection, FakeClient, receipt


def snapshot(outcome):
    success = outcome == 'SUCCESS'
    return {'kind': 'transmissionHistory', 'ready': True, 'busy': False, 'visibleRowCount': 1,
            'capturedAt': '2026-09-06T12:00:00Z', 'selectedTab': '전송완료' if success else '전송실패',
            'url': 'https://mp.cafe24.com/mp/queue/productList?page=1&queue_status=' + ('S' if success else 'F'),
            'pagination': {'page': 1, 'hasNext': False}, 'filters': [], 'rows': [
                {'cells': ['1', 'seller-g', 'P0000UVU', '3490363781', '상품명', '상품수정', '[성공] 완료' if success else '[실패] 확인 필요',
                           '2026-09-06 16:02', '2026-09-06 16:02'], 'marketIcons': ['Gmarket_svg_1'],
                 'products': [{'productCode': 'P0000UVU', 'productNo': '14086', 'shopNo': '1'}]}]}


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.browser = Mock()
        self.browser.prepare.side_effect = snapshot
        self.browser.verify_accounts.return_value = {'mallIds': ['testmall'], 'accounts': []}
        self.client = FakeClient()
    def flags(self, collect, upload):
        atomic_save(self.root / 'enabled.json', {'collect': collect, 'upload': upload})
    def cycle(self):
        return worker.cycle(self.root, self.browser, 'testmall', 1, lambda: self.client, clock=lambda: 2000)
    def capture(self, name='capture.json'):
        atomic_save(self.root / 'captures' / name, collection())
    def test_defaults_paused_and_prepares_interactive_browser_without_site_read_or_upload(self):
        status = self.cycle()
        self.assertEqual('PAUSED', status['state'])
        self.browser.connect.assert_called_once()
        self.browser.prepare.assert_not_called()
        self.assertEqual([], self.client.calls)
        self.assertIsNone(status.get('lastUploadedAt'))
    def test_two_tabs_are_persisted_before_relay_with_separate_capture_and_upload_times(self):
        self.flags(True, True)
        status = self.cycle()
        self.assertEqual('OBSERVED_PARTIAL', status['state'])
        self.assertEqual(['SUCCESS', 'FAILURE'], [x['outcome'] for x in status['collections']])
        self.assertEqual(2, len(list((self.root / 'captures').glob('*.json'))))
        self.assertEqual(2, len(list((self.root / 'receipts').glob('*.json'))))
        self.assertEqual(2000, status['lastCollectedAt'])
        self.assertEqual(2000, status['lastUploadedAt'])
    def test_login_loss_does_not_starve_previously_captured_files(self):
        self.flags(True, True); self.capture()
        self.browser.connect.side_effect = browser.BrowserError('LOGIN_REQUIRED')
        status = self.cycle()
        self.assertEqual('ATTENTION', status['state'])
        self.assertEqual(1, status['upload']['uploaded'])
        self.assertIsNone(status.get('lastCollectedAt'))
    def test_upload_only_can_run_with_browser_unavailable(self):
        self.flags(False, True); self.capture()
        self.browser.connect.side_effect = browser.BrowserError('BROWSER_UNAVAILABLE')
        self.assertEqual(1, self.cycle()['upload']['uploaded'])
        self.browser.connect.assert_not_called()
    def test_no_capture_does_not_invent_successful_upload_timestamp(self):
        self.flags(False, True)
        self.assertIsNone(self.cycle().get('lastUploadedAt'))
    def test_interrupted_file_does_not_prevent_valid_later_file_upload(self):
        broken = collection(); broken['status'] = 'RUNNING'
        atomic_save(self.root / 'captures' / 'a.json', broken)
        self.capture('b.json')
        result = worker.drain(self.root, self.client, now=lambda: 2000)
        self.assertEqual(1, result['blocked'])
        self.assertEqual(1, result['uploaded'])
    def test_rate_limit_stops_other_files_and_survives_worker_restart(self):
        self.capture('a.json'); self.capture('b.json')
        def limit(payload): raise RelayError('HTTP_429', True, 900)
        self.client.fail = limit
        result = worker.drain(self.root, self.client, now=lambda: 2000)
        self.assertEqual(2900, result['nextAttemptAt'])
        self.assertEqual(2, result['pending'])
        self.client.calls.clear()
        worker.drain(self.root, self.client, now=lambda: 2899)
        self.assertEqual([], self.client.calls)
    def test_empty_or_unreadable_success_tab_does_not_hide_failure_tab(self):
        self.flags(True, False)
        self.browser.prepare.side_effect = [browser.BrowserError('PAGE_NOT_VERIFIED'), snapshot('FAILURE')]
        status = self.cycle()
        self.assertEqual('ATTENTION', status['state'])
        self.assertEqual([0, 1], [x['rows'] for x in status['collections']])
        self.assertIsNone(status.get('lastCollectedAt'))
    def test_collection_only_reports_pending_files_without_claiming_upload(self):
        self.flags(True, False)
        status = self.cycle()
        self.assertEqual('OBSERVED_PARTIAL', status['state'])
        self.assertEqual(2, status['upload']['pending'])
        self.assertIsNone(status.get('lastUploadedAt'))
    def test_account_mismatch_stops_new_collection_but_preserves_old_files(self):
        self.flags(True, False); self.capture()
        self.browser.verify_accounts.side_effect = browser.BrowserError('SELLER_ACCOUNT_MISMATCH')
        status = self.cycle()
        self.assertEqual('SELLER_ACCOUNT_MISMATCH', status['error'])
        self.browser.prepare.assert_not_called()
        self.assertEqual(1, status['upload']['pending'])
    def test_mid_page_browser_failure_preserves_file_and_attempts_other_tab(self):
        self.flags(True, False)
        first = snapshot('SUCCESS'); first['pagination']['hasNext'] = True
        self.browser.prepare.side_effect = [first, snapshot('FAILURE')]
        self.browser.snapshot.side_effect = browser.BrowserError('HISTORY_PAGE_NOT_STABLE')
        status = worker.cycle(self.root, self.browser, 'testmall', 2, lambda: self.client, clock=lambda: 2000)
        self.assertEqual('ATTENTION', status['state'])
        self.assertEqual('HISTORY_PAGE_NOT_STABLE', status['error'])
        self.assertEqual([1, 1], [x['rows'] for x in status['collections']])
        self.assertEqual(2, status['upload']['pending'])
        self.assertIsNone(status.get('lastCollectedAt'))
    def test_missing_api_or_bad_auth_blocks_repeated_uploads_across_files(self):
        self.capture('a.json'); self.capture('b.json')
        self.client.request = Mock(side_effect=RelayError('HTTP_404'))
        result = worker.drain(self.root, self.client, now=lambda: 2000)
        self.assertEqual(1, result['blocked']); self.assertEqual(1, result['pending'])
        self.client.request.reset_mock()
        worker.drain(self.root, self.client, now=lambda: 3000)
        self.client.request.assert_not_called()
    def test_invalid_flags_are_visible_in_status_instead_of_retaining_previous_success(self):
        (self.root / 'enabled.json').write_text('invalid')
        status = self.cycle()
        self.assertEqual('ATTENTION', status['state'])
        self.assertEqual('CONFIGURATION_OR_STORAGE_ERROR', status['error'])
        self.assertEqual('ATTENTION', json.loads((self.root / 'status.json').read_text())['state'])


class BrowserContractTest(unittest.TestCase):
    def test_verified_mall_and_both_linked_sellers_required_each_cycle(self):
        accounts = {'gmarket': 'seller-g', 'auction': 'seller-a'}
        verified = {'kind': 'linkedAccounts', 'mallIds': ['testmall'], 'accounts': [
            {'market': m, 'account': a, 'shopNo': '1'} for m, a in accounts.items()]}
        with tempfile.TemporaryDirectory() as root, patch.object(browser.time, 'sleep'):
            b = browser.Browser('http://localhost:4444', Path(root) / 'browser.json')
            b.navigate = Mock(); b.url = Mock(return_value=browser.ACCOUNTS)
            for change, expected in ((None, None), ('mall', 'MALL_ACCOUNT_MISMATCH'), ('seller', 'SELLER_ACCOUNT_MISMATCH'),
                                     ('shop', 'SELLER_ACCOUNT_MISMATCH'), ('duplicate', 'SELLER_ACCOUNT_MISMATCH')):
                value = copy.deepcopy(verified)
                if change == 'mall': value['mallIds'] = ['anothermall']
                if change == 'seller': value['accounts'][0]['account'] = 'another-seller'
                if change == 'shop': value['accounts'][0]['shopNo'] = '2'
                if change == 'duplicate': value['accounts'].append(value['accounts'][0])
                b.execute = Mock(return_value=json.dumps(value))
                with self.subTest(change=change):
                    if expected:
                        with self.assertRaisesRegex(browser.BrowserError, expected): b.verify_accounts('testmall', accounts)
                    else: self.assertEqual(['testmall'], b.verify_accounts('testmall', accounts)['mallIds'])
            b.navigate.reset_mock()
            with self.assertRaisesRegex(browser.BrowserError, 'ACCOUNT_CONFIGURATION_REQUIRED'): b.verify_accounts('testmall', {})
            b.navigate.assert_not_called()
    def test_unstable_page_is_not_reported_as_login_loss(self):
        with tempfile.TemporaryDirectory() as root:
            b = browser.Browser('http://localhost:4444', Path(root) / 'browser.json')
            with patch.object(browser.collector, 'stable_snapshot', side_effect=ValueError('raw page text')):
                with self.assertRaisesRegex(browser.BrowserError, 'HISTORY_PAGE_NOT_STABLE'): b.snapshot()
            with patch.object(browser.collector, 'stable_snapshot', side_effect=browser.BrowserError('LOGIN_REQUIRED')):
                with self.assertRaisesRegex(browser.BrowserError, 'LOGIN_REQUIRED'): b.snapshot()
    def test_no_arbitrary_navigation_or_public_driver(self):
        with tempfile.TemporaryDirectory() as root:
            b = browser.Browser('http://localhost:4444', Path(root) / 'browser.json')
            b.request = Mock()
            with self.assertRaises(browser.BrowserError): b.navigate('https://mp.cafe24.com/mp/product/front/registerall')
            b.request.assert_not_called()
            with self.assertRaises(ValueError): browser.Browser('http://168.107.31.154:4444', Path(root) / 'b.json')
    def test_login_page_cannot_be_parsed_as_empty_history(self):
        with tempfile.TemporaryDirectory() as root:
            b = browser.Browser('http://localhost:4444', Path(root) / 'browser.json')
            b.url = Mock(return_value=browser.LOGIN)
            b.execute = Mock()
            with self.assertRaises(browser.BrowserError): b.raw_snapshot()
            b.execute.assert_not_called()
    def test_existing_unknown_session_is_not_taken_over(self):
        with tempfile.TemporaryDirectory() as root:
            b = browser.Browser('http://localhost:4444', Path(root) / 'browser.json')
            b.request = Mock(return_value={'ready': True, 'nodes': [{'slots': [{'session': {'sessionId': 'other'}}]}]})
            with self.assertRaises(browser.BrowserError): b.connect()
            b.request.assert_called_once_with('/status')


if __name__ == '__main__': unittest.main()
