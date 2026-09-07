"""Dedicated server Chromium adapter using W3C WebDriver and verified MarketPlus DOM readers.

Never enters a password or calls undocumented MarketPlus endpoints. Login happens interactively
in the private noVNC window. Only page navigation, history-tab selection and pagination are automated.
"""
import importlib.util
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler
from zoneinfo import ZoneInfo
from datetime import datetime
from relay import atomic_save, NoRedirect
import public_fields

HISTORY = 'https://mp.cafe24.com/mp/queue/productList'
LOGIN = 'https://eclogin.cafe24.com/Shop/?mode=mp'
ACCOUNTS = 'https://mp.cafe24.com/mp/account/lists'
SCRIPTS = Path(__file__).resolve().parents[1] / 'browser'
spec = importlib.util.spec_from_file_location('mp_collector', SCRIPTS / 'collect-marketplus-transmissions.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class BrowserError(ValueError):
    pass


class Browser:
    def __init__(self, endpoint, state_path):
        url = urlsplit(endpoint)
        if (url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', 'marketplus-browser')
                or url.username or url.password or url.query or url.fragment or url.path not in ('', '/')):
            raise ValueError('전용 브라우저의 로컬/내부 WebDriver 주소가 필요합니다.')
        self.endpoint, self.path = endpoint.rstrip('/'), Path(state_path)
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.session = None

    def request(self, path, payload=None, method=None):
        try:
            req = Request(self.endpoint + path, data=None if payload is None else json.dumps(payload).encode(),
                          headers={'Content-Type': 'application/json'}, method=method or ('GET' if payload is None else 'POST'))
            with self.opener.open(req, timeout=40) as response:
                data = json.loads(response.read(2_000_000))
            if not isinstance(data, dict) or 'value' not in data:
                raise BrowserError('BROWSER_INVALID_RESPONSE')
            return data['value']
        except HTTPError as error:
            error.close()
            if error.code == 404:
                raise BrowserError('BROWSER_SESSION_MISSING') from None
            raise BrowserError('BROWSER_COMMAND_FAILED') from None
        except (URLError, OSError, ValueError):
            raise BrowserError('BROWSER_UNAVAILABLE') from None

    def connect(self):
        if self.path.exists():
            state = json.loads(self.path.read_text())
            if state.get('endpoint') != self.endpoint:
                raise BrowserError('BROWSER_ENDPOINT_CHANGED')
            candidate = state.get('sessionId')
            if not isinstance(candidate, str) or not re.fullmatch(r'[A-Za-z0-9-]{16,100}', candidate):
                raise BrowserError('BROWSER_STATE_INVALID')
            self.session = candidate
            try:
                self.url()
                return
            except BrowserError as error:
                if str(error) != 'BROWSER_SESSION_MISSING':
                    raise
        status = self.request('/status')
        if not isinstance(status, dict) or not status.get('ready'):
            raise BrowserError('BROWSER_NOT_READY')
        if any(slot.get('session') for node in status.get('nodes', []) for slot in node.get('slots', [])):
            raise BrowserError('BROWSER_HAS_UNOWNED_SESSION')
        session = self.request('/session', {'capabilities': {'alwaysMatch': {
            'browserName': 'chrome', 'se:name': 'sbshop-marketplus-observer', 'pageLoadStrategy': 'normal',
            'timeouts': {'pageLoad': 30000, 'script': 10000, 'implicit': 0},
            'goog:chromeOptions': {'args': ['--user-data-dir=/home/seluser/marketplus/profile', '--lang=ko-KR',
                                          '--no-first-run', '--no-default-browser-check', '--window-size=1440,1050']}}}})
        self.session = session['sessionId']
        atomic_save(self.path, {'endpoint': self.endpoint, 'sessionId': self.session})
        self.navigate(LOGIN)

    def url(self):
        return self.request(f'/session/{self.session}/url')

    def navigate(self, url):
        if url not in (HISTORY, LOGIN, ACCOUNTS):
            raise BrowserError('BROWSER_NAVIGATION_NOT_ALLOWED')
        self.request(f'/session/{self.session}/url', {'url': url})

    def execute(self, javascript):
        # Scripts are packaged code, not contents taken from the market page or collection files.
        return self.request(f'/session/{self.session}/execute/sync', {'script': 'return ' + javascript.rstrip().rstrip(';') + ';', 'args': []})

    def observe_public(self, target):
        """Read one API-provided current connection; no external write or inferred deletion."""
        if not isinstance(target, dict):
            raise BrowserError('PUBLIC_CONTEXT_REQUIRED')
        market, external = target.get('market'), target.get('externalId')
        if market == 'GMARKET' and isinstance(external, str) and re.fullmatch(r'[1-9][0-9]{0,19}', external):
            destination = 'https://item.gmarket.co.kr/Item?goodscode=' + external
        elif market == 'AUCTION' and isinstance(external, str) and re.fullmatch(r'[A-Za-z0-9]{1,20}', external):
            destination = 'https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=' + external
        else:
            raise BrowserError('PUBLIC_CONTEXT_INVALID')
        if (target.get('publicUrl') != destination or type(target.get('registrationId')) is not int
                or target['registrationId'] <= 0 or type(target.get('expectedRevision')) is not int
                or target['expectedRevision'] < 0 or not isinstance(target.get('sellerAccount'), str)
                or not re.fullmatch(r'[1-9][0-9]{0,18}', str(target.get('cafe24ProductNo', '')))
                or not re.fullmatch(r'P[A-Z0-9]{7,29}', str(target.get('cafe24ProductCode', '')))):
            raise BrowserError('PUBLIC_CONTEXT_INVALID')
        original = self.request(f'/session/{self.session}/window')
        owned = None
        self.public_http_status = None
        try:
            created = self.request(f'/session/{self.session}/window/new', {'type': 'tab'})
            owned = created['handle']
            if owned == original:
                raise BrowserError('BROWSER_NEW_TAB_NOT_VERIFIED')
            self.request(f'/session/{self.session}/window', {'handle': owned})
            self.request(f'/session/{self.session}/url', {'url': destination})
            # Chrome exposes the actual navigation response status when supported; 0/missing is unknown.
            status = self.execute("(() => performance.getEntriesByType('navigation')[0]?.responseStatus ?? null)()")
            if type(status) is int and 100 <= status <= 599:
                self.public_http_status = status
            if self.public_http_status is not None and self.public_http_status >= 400:
                raise BrowserError('HTTP_ERROR')
            raw = self.execute((SCRIPTS / 'marketplace-public-fields.js').read_text())
            try:
                snapshot = json.loads(raw) if isinstance(raw, str) else raw
                capture = public_fields.parse(snapshot, market, external, target['sellerAccount'])
            except (ValueError, TypeError) as error:
                raise BrowserError(str(error) if isinstance(error, public_fields.PublicFieldError) else 'PUBLIC_PAGE_UNVERIFIED') from None
            return {key: target[key] for key in ('registrationId', 'expectedRevision', 'cafe24ProductNo', 'cafe24ProductCode')} | {'observation': capture}
        finally:
            handles = self.request(f'/session/{self.session}/window/handles')
            if owned and owned != original and owned in handles:
                self.request(f'/session/{self.session}/window', {'handle': owned})
                self.request(f'/session/{self.session}/window', method='DELETE')
            if original in handles:
                self.request(f'/session/{self.session}/window', {'handle': original})

    def raw_snapshot(self):
        url = urlsplit(self.url())
        if url.scheme != 'https' or url.netloc != 'mp.cafe24.com' or url.path != '/mp/queue/productList':
            raise BrowserError('LOGIN_REQUIRED' if url.netloc != 'mp.cafe24.com' else 'HISTORY_PAGE_REQUIRED')
        return json.loads(self.execute((SCRIPTS / 'marketplus-snapshot.js').read_text()))

    def snapshot(self):
        try:
            return collector.stable_snapshot(read=self.raw_snapshot)
        except BrowserError:
            raise
        except ValueError:
            raise BrowserError('HISTORY_PAGE_NOT_STABLE') from None

    def verify_accounts(self, mall, accounts):
        if set(accounts) != {'gmarket', 'auction'} or not all(isinstance(v, str) and v.strip() for v in accounts.values()):
            raise BrowserError('ACCOUNT_CONFIGURATION_REQUIRED')
        self.navigate(ACCOUNTS)
        previous = None
        for _ in range(10):
            if urlsplit(self.url()).netloc != 'mp.cafe24.com':
                raise BrowserError('LOGIN_REQUIRED')
            value = json.loads(self.execute((SCRIPTS / 'marketplus-snapshot.js').read_text()))
            identity = {'mallIds': value.get('mallIds'), 'accounts': value.get('accounts')}
            if value.get('kind') == 'linkedAccounts' and identity == previous and identity['accounts']:
                if identity['mallIds'] != [mall]:
                    raise BrowserError('MALL_ACCOUNT_MISMATCH')
                for market, seller in accounts.items():
                    rows = [r for r in identity['accounts'] if r.get('market') == market]
                    if len(rows) != 1 or rows[0].get('account') != seller or rows[0].get('shopNo') != '1':
                        raise BrowserError('SELLER_ACCOUNT_MISMATCH')
                return identity
            previous = identity
            time.sleep(1)
        raise BrowserError('ACCOUNT_PAGE_NOT_VERIFIED')

    def next_page(self):
        return json.loads(self.execute((SCRIPTS / 'marketplus-history-next.js').read_text()))

    def prepare(self, outcome):
        if outcome not in ('SUCCESS', 'FAILURE'):
            raise BrowserError('HISTORY_OUTCOME_REQUIRED')
        # Fresh navigation resets the page's default date range and does not reuse human search filters.
        self.navigate(HISTORY)
        for _ in range(15):
            url = urlsplit(self.url())
            if url.netloc not in ('mp.cafe24.com',):
                raise BrowserError('LOGIN_REQUIRED')
            if url.path == '/mp/queue/productList':
                break
            time.sleep(1)
        label = '전송완료' if outcome == 'SUCCESS' else '전송실패'
        script = """(() => {
          if (location.origin !== 'https://mp.cafe24.com' || location.pathname !== '/mp/queue/productList') throw new Error('HISTORY_REQUIRED');
          const tabs = Array.from(document.querySelectorAll('[role=tab]')).filter(e => e.innerText.trim() === LABEL);
          if (tabs.length !== 1) throw new Error('HISTORY_TAB_MISSING');
          if (tabs[0].getAttribute('aria-selected') !== 'true') tabs[0].click();
          return true;
        })()""".replace('LABEL', json.dumps(label, ensure_ascii=False))
        # React may not have mounted the tab immediately after the document load event.
        for attempt in range(8):
            try:
                self.execute(script)
                break
            except BrowserError:
                if attempt == 7:
                    raise
                time.sleep(1)
        snapshot = self.snapshot()
        if snapshot.get('selectedTab') != label or collector.page_number(snapshot) != 1:
            raise BrowserError('HISTORY_START_PAGE_MISMATCH')
        dates = [f.get('value') for f in snapshot.get('filters', []) if re.fullmatch(r'\d{4}-\d{2}-\d{2}', str(f.get('value', '')))]
        today = datetime.now(ZoneInfo('Asia/Seoul')).date().isoformat()
        if len(dates) != 2 or dates[1] != today or dates[0] > today:
            raise BrowserError('HISTORY_DATE_RANGE_REVIEW_REQUIRED')
        return snapshot
