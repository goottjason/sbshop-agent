import json
from pathlib import Path
import tempfile
from unittest import TestCase, mock
from browser import BrowserError
from public_queue import process_one, PublicClient, BASE
from relay import RelayError, atomic_save

class PublicQueueTest(TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.browser=mock.Mock();self.browser.public_http_status=None
        self.browser.observe_public.return_value={'registrationId':2,'observation':{'values':{'salePrice':'100'}}}
        self.client=mock.Mock();self.client.target='sb-target-fixture'
        self.task={'taskId':5,'leaseToken':'12345678-1234-1234-1234-123456789abc','target':{'market':'AUCTION'}}
        self.client.request.side_effect=[{'task':self.task},{'id':5,'state':'OBSERVED'}]
    def run_one(self):return process_one(self.root,self.browser,lambda:self.client,clock=lambda:1000)
    def test_explicit_request_runs_without_any_collect_or_upload_flags_and_persists_before_report(self):
        def response(path,payload):
            if path.endswith('/claim'):return {'task':self.task}
            self.assertTrue((self.root/'public-check-pending.json').exists())
            return {'id':5,'state':'OBSERVED'}
        self.client.request.side_effect=response
        self.assertEqual(self.run_one()['state'],'OBSERVED')
        self.assertFalse((self.root/'public-check-pending.json').exists())
        self.browser.verify_accounts.assert_not_called()
    def test_lost_report_reuses_exact_capture_without_second_browser_read(self):
        self.client.request.side_effect=[{'task':self.task},RelayError('CONNECTION_RESULT_UNKNOWN',True)]
        self.assertEqual(self.run_one()['state'],'RELAY_WAIT')
        pending=json.loads((self.root/'public-check-pending.json').read_text())
        (self.root/'public-check-relay-gate.json').unlink()
        self.client.request.side_effect=[{'id':5,'state':'OBSERVED'}]
        self.assertEqual(self.run_one()['state'],'OBSERVED')
        self.assertEqual(self.client.request.call_args.args,(BASE+'/5/report',pending['report']))
        self.browser.observe_public.assert_called_once()
    def test_error_page_records_unverified_without_inventing_http_status(self):
        self.browser.observe_public.side_effect=BrowserError('PUBLIC_PAGE_UNVERIFIED')
        self.client.request.side_effect=[{'task':self.task},{'id':5,'state':'RETRY_WAIT'}]
        self.assertEqual(self.run_one()['state'],'RETRY_WAIT')
        report=self.client.request.call_args.args[1]
        self.assertEqual(report['errorCode'],'PUBLIC_PAGE_UNVERIFIED');self.assertIsNone(report['httpStatus']);self.assertIsNone(report['observation'])
    def test_actual_http_status_is_reported_but_headers_not_fabricated(self):
        def observe(target):self.browser.public_http_status=429;raise BrowserError('HTTP_ERROR')
        self.browser.observe_public.side_effect=observe
        self.client.request.side_effect=[{'task':self.task},{'id':5,'state':'RETRY_WAIT'}]
        self.run_one();report=self.client.request.call_args.args[1]
        self.assertEqual(report['httpStatus'],429);self.assertIsNone(report['retryAfterSeconds'])
    def test_browser_unavailable_is_reported_and_does_not_require_history_login(self):
        self.browser.connect.side_effect=BrowserError('BROWSER_UNAVAILABLE')
        self.client.request.side_effect=[{'task':self.task},{'id':5,'state':'RETRY_WAIT'}]
        self.run_one();self.assertEqual(self.client.request.call_args.args[1]['errorCode'],'BROWSER_UNAVAILABLE')
        self.browser.observe_public.assert_not_called()
    def test_local_relay_429_delays_claims_without_becoming_external_market_429(self):
        self.client.request.side_effect=RelayError('HTTP_429',True,120)
        self.assertEqual(self.run_one()['error'],'HTTP_429');self.assertEqual(self.run_one()['state'],'RELAY_WAIT')
        self.client.request.assert_called_once();self.browser.connect.assert_not_called()
    def test_rejected_expired_report_does_not_starve_next_job_and_keeps_receipt(self):
        atomic_save(self.root/'public-check-pending.json',{'target':'sb-target-fixture','taskId':5,'report':{'leaseToken':self.task['leaseToken']}})
        self.client.request.side_effect=RelayError('HTTP_400')
        self.run_one();self.assertFalse((self.root/'public-check-pending.json').exists());self.assertTrue((self.root/'public-check-rejected.json').exists())
    def test_allowlist_prohibits_every_market_write_and_arbitrary_sb_endpoint(self):
        client=PublicClient('http://127.0.0.1:8080','fixture','fixture')
        for path,payload in [(BASE+'/claim',None),('/mp/product/rest/save',{}),('/api/v1/products/1',{}),(BASE+'/0/report',{})]:
            with self.assertRaises(ValueError):client.request(path,payload)

    def test_acknowledged_old_lease_429_report_does_not_repeat_and_extend_cooldown_forever(self):
        atomic_save(self.root/'public-check-pending.json',{'target':'sb-target-fixture','taskId':5,'report':{'leaseToken':self.task['leaseToken'],'errorCode':'HTTP_ERROR','httpStatus':429}})
        self.client.request.side_effect=[{'id':5,'state':'RUNNING'}]
        self.assertEqual(self.run_one()['state'],'RUNNING')
        self.assertFalse((self.root/'public-check-pending.json').exists())
        self.browser.connect.assert_not_called()
