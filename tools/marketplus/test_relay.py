import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import relay


def collection():
    return {'schemaVersion': 1, 'kind': 'MARKETPLUS_COLLECTION', 'status': 'PARTIAL', 'stopReason': 'PAGE_LIMIT',
            'pages': [{'page': p, 'batch': {'schemaVersion': 1, 'source': 'LIVE_CHROME_MARKETPLUS', 'coverage': 'CURRENT_PAGE',
                      'mallId': 'testmall', 'shopNo': 1, 'capturedAt': '2026-09-06T12:00:00Z',
                      'rows': [{'externalId': f'item-{p}'}]}} for p in [1, 2]]}


def receipt(rows, status='SAVED', retryable=False):
    return {'saved': len(rows) if status == 'SAVED' else 0, 'duplicate': len(rows) if status == 'DUPLICATE' else 0,
            'rejected': len(rows) if status == 'REJECTED' else 0,
            'items': [{'row': i, 'externalId': r['externalId'], 'productId': None if status == 'REJECTED' else i,
                       'result': status, 'detail': '검증용 응답', 'retryable': retryable} for i, r in enumerate(rows, 1)]}


class FakeClient:
    target = 'test-target'
    def __init__(self):
        self.calls, self.fail, self.ready = [], None, True
    def request(self, path, payload=None):
        self.calls.append((path, payload))
        if path == relay.READINESS:
            return {'ready': self.ready}
        if self.fail:
            return self.fail(payload)
        return receipt(payload['rows'])


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.client, self.saved, self.now = FakeClient(), [], 1000
    def run_upload(self, state=None, **kwargs):
        return relay.upload(collection(), self.client, state, lambda value: self.saved.append(copy.deepcopy(value)), clock=lambda: self.now, **kwargs)
    def test_partial_capture_upload_is_never_full_collection(self):
        state = self.run_upload()
        self.assertEqual('UPLOADED', state['status'])
        self.assertEqual('PARTIAL', state['collectionStatus'])
        self.assertEqual('PAGE_LIMIT', state['collectionStopReason'])
        before = len(self.client.calls)
        self.client.ready = False
        self.run_upload(state)
        self.assertEqual(before, len(self.client.calls))
    def test_lost_response_preserves_earlier_receipt_and_retries_only_unknown_page(self):
        def fail(payload):
            if payload['rows'][0]['externalId'] == 'item-2':
                raise relay.RelayError('CONNECTION_RESULT_UNKNOWN', True)
            return receipt(payload['rows'])
        self.client.fail = fail
        state = self.run_upload()
        self.assertIn('receipt', state['pages']['1'])
        self.assertNotIn('receipt', state['pages']['2'])
        self.assertEqual('WAITING_RETRY', state['status'])
        self.now += 61
        self.client.calls.clear()
        self.client.fail = lambda payload: receipt(payload['rows'], 'DUPLICATE')
        state = self.run_upload(state)
        self.assertEqual('UPLOADED', state['status'])
        self.assertEqual(['item-2'], [p['rows'][0]['externalId'] for _, p in self.client.calls if p])
    def test_rate_limit_applies_to_entire_relay_before_next_read(self):
        def limited(payload):
            raise relay.RelayError('HTTP_429', True, 3600)
        self.client.fail = limited
        state = self.run_upload()
        self.assertEqual(4600, state['nextAttemptAt'])
        self.client.calls.clear()
        self.now += 3599
        self.run_upload(state)
        self.assertEqual([], self.client.calls)
    def test_unready_account_sends_no_page_and_can_recover(self):
        self.client.ready = False
        state = self.run_upload()
        self.assertEqual('ACCOUNT_NOT_READY', state['lastError'])
        self.assertEqual({}, state['pages'])
        self.assertEqual(1, len(self.client.calls))
        self.now += 301
        self.client.ready = True
        self.assertEqual('UPLOADED', self.run_upload(state)['status'])
    def test_permanent_rejections_remain_visible_without_automatic_repeat(self):
        self.client.fail = lambda payload: receipt(payload['rows'], 'REJECTED')
        state = self.run_upload()
        self.assertEqual('UPLOADED_WITH_REJECTIONS', state['status'])
        self.client.calls.clear()
        self.run_upload(state)
        self.assertEqual([], self.client.calls)
        self.client.fail = None
        self.assertEqual('UPLOADED', self.run_upload(state, retry_rejected=True)['status'])
    def test_retryable_storage_rejection_is_retried_without_matching_message_text(self):
        self.client.fail = lambda payload: receipt(payload['rows'], 'REJECTED', True)
        state = self.run_upload()
        self.assertEqual('WAITING_RETRY', state['status'])
        self.assertEqual('ROW_STORAGE_RESULT_UNKNOWN', state['lastError'])
        self.assertEqual(1, len(state['pages']))
        self.now += 61
        self.client.fail = None
        self.assertEqual('UPLOADED', self.run_upload(state)['status'])
    def test_malformed_success_count_row_identity_and_result_never_acknowledged(self):
        cases = [{'saved': 99}, {'items': []}, {'items': [dict(row=1, externalId='wrong', result='SAVED', productId=1, detail='')]},
                 {'saved': True}, {'items': [dict(row=True, externalId='item-1', result='SAVED', productId=1, detail='')]}]
        for bad in cases:
            with self.subTest(bad=bad):
                self.client.fail = lambda payload: {**receipt(payload['rows']), **bad}
                state = self.run_upload()
                self.assertEqual('INVALID_RECEIPT', state['lastError'])
                self.assertNotIn('receipt', state['pages']['1'])
    def test_unknown_state_saved_before_network_post(self):
        def interrupted(payload):
            self.assertEqual('RESULT_UNKNOWN', self.saved[-1]['pages']['1']['status'])
            raise KeyboardInterrupt()
        self.client.fail = interrupted
        state = self.run_upload()
        self.assertEqual('INTERRUPTED', state['lastError'])
        self.assertNotIn('receipt', state['pages']['1'])
    def test_file_or_destination_change_cannot_reuse_receipts(self):
        state = self.run_upload()
        self.client.target = 'different-server'
        with self.assertRaises(ValueError):
            self.run_upload(state)
    def test_validates_whole_file_before_any_transfer(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'capture.json'
            value = collection()
            path.write_text(json.dumps(value))
            self.assertEqual(value, relay.read_collection(path))
            for change in ('running', 'mall', 'gap', 'bad-row'):
                bad = copy.deepcopy(value)
                if change == 'running': bad['status'] = 'RUNNING'
                if change == 'mall': bad['pages'][1]['batch']['mallId'] = 'othermall'
                if change == 'gap': bad['pages'][1]['page'] = 3
                if change == 'bad-row': bad['pages'][1]['batch']['rows'] = [{}]
                path.write_text(json.dumps(bad))
                with self.subTest(change=change), self.assertRaises(ValueError): relay.read_collection(path)
    def test_atomic_private_receipts_and_no_secret_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'receipt.json'
            relay.atomic_save(path, self.run_upload())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertNotIn('password', path.read_text())
            self.assertNotIn('Authorization', path.read_text())
            self.assertEqual([path], list(Path(folder).iterdir()))
    def test_retry_after_honors_seconds_and_http_date(self):
        self.assertEqual(7200, relay.retry_after('7200', 0))
        self.assertEqual(61, relay.retry_after('Thu, 01 Jan 1970 00:01:00 GMT', 0))
        self.assertEqual(60, relay.retry_after('invalid', 0))


class HttpClientTest(unittest.TestCase):
    def test_only_expected_routes_and_no_redirect_following(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                self.send_response(302)
                self.send_header('Location', '/should-never-be-called')
                self.end_headers()
            def log_message(self, *args): pass
        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            client = relay.Client(f'http://127.0.0.1:{server.server_port}', 'test', 'private-password')
            try:
                with self.assertRaises(relay.RelayError) as raised: client.request(relay.READINESS)
                self.assertEqual('HTTP_302', raised.exception.code)
                with self.assertRaises(ValueError): client.request('/api/v1/products/1', {})
                self.assertEqual([relay.READINESS], requests)
            finally: server.shutdown(); thread.join()
    def test_public_plaintext_credential_urls_and_unrelated_base_paths_rejected(self):
        for url in ['http://168.107.31.154', 'https://admin:secret@example.com', 'https://example.com?token=secret', 'https://example.com/other']:
            with self.subTest(url=url), self.assertRaises(ValueError): relay.Client(url, 'test', 'private-password')


if __name__ == '__main__': unittest.main()
