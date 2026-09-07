#!/usr/bin/env python3
"""Durably upload browser-observed MarketPlus pages to SB, never to an external market.

One invocation attempts each due page at most once. Re-run after nextAttemptAt; validated receipts
skip acknowledged pages, and the server deduplicates a page whose response was lost. Credentials
come from environment variables, never command-line arguments or receipt files.
"""
import argparse
import base64
from email.utils import parsedate_to_datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler

MAX_FILE = 20_000_000
MAX_RESPONSE = 1_000_000
READINESS = '/api/v1/marketplus/transmissions/readiness'
IMPORT = '/api/v1/marketplus/transmissions/import'


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def atomic_save(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write('\n')
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def read_collection(path):
    path = Path(path)
    if path.stat().st_size > MAX_FILE:
        raise ValueError('수집 파일은 20MB 이하여야 합니다.')
    payload = json.loads(path.read_text(encoding='utf-8'))
    if (not isinstance(payload, dict) or payload.get('schemaVersion') != 1
            or payload.get('kind') != 'MARKETPLUS_COLLECTION'
            or payload.get('status') not in ('PARTIAL', 'REACHED_LAST_PAGE')
            or not isinstance(payload.get('pages'), list) or not 1 <= len(payload['pages']) <= 2000):
        raise ValueError('종료된 수집 파일과 저장 페이지가 필요합니다. RUNNING 파일은 전송하지 않습니다.')
    pages = payload['pages']
    mall, previous = None, None
    for page in pages:
        if not isinstance(page, dict) or not integer(page.get('page'), 1):
            raise ValueError('수집 페이지 번호를 확인하세요.')
        if previous is not None and page['page'] != previous + 1:
            raise ValueError('수집 페이지가 연속하지 않습니다.')
        previous = page['page']
        batch = page.get('batch')
        if (not isinstance(batch, dict) or batch.get('schemaVersion') != 1 or batch.get('source') != 'LIVE_CHROME_MARKETPLUS'
                or batch.get('coverage') != 'CURRENT_PAGE' or type(batch.get('shopNo')) is not int or batch['shopNo'] != 1
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', str(batch.get('mallId', '')))
                or not isinstance(batch.get('rows'), list) or not 1 <= len(batch['rows']) <= 100
                or any(not isinstance(row, dict) or not isinstance(row.get('externalId'), str) or not row['externalId'] for row in batch['rows'])):
            raise ValueError('수집 페이지의 쇼핑몰·행 형식을 확인하세요.')
        mall = mall or batch['mallId']
        if mall != batch['mallId']:
            raise ValueError('여러 쇼핑몰의 수집 파일을 섞을 수 없습니다.')
    return payload


class RelayError(Exception):
    def __init__(self, code, retryable=False, delay=60):
        super().__init__(code)
        self.code, self.retryable, self.delay = code, retryable, delay


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def retry_after(value, now):
    if not value:
        return 60
    if value.isdigit():
        return max(1, int(value))
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            return 60
        return max(1, int(date.timestamp() - now) + 1)
    except (ValueError, TypeError, OverflowError):
        return 60


class Client:
    def __init__(self, base_url, username, password, clock=time.time):
        base_url = base_url.rstrip('/')
        url = urlsplit(base_url)
        if (url.username or url.password or url.query or url.fragment or not url.hostname
                or url.path not in ('', '/sbshop-agent') or url.scheme not in ('http', 'https')
                or (url.scheme == 'http' and url.hostname not in ('127.0.0.1', 'localhost', 'sbshop-api'))):
            raise ValueError('서버 주소는 HTTPS 또는 로컬 SSH 터널/내부 sbshop-api 주소여야 합니다.')
        if not username or not password or ':' in username:
            raise ValueError('서버 인증 환경변수를 확인하세요.')
        self.base_url, self.username, self.clock = base_url, username, clock
        self.authorization = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    @property
    def target(self):
        return digest({'baseUrl': self.base_url, 'username': self.username})

    def allowed(self, path, payload):
        return (path, payload is None) in ((READINESS, True), (IMPORT, False))

    def request(self, path, payload=None):
        if not self.allowed(path, payload):
            raise ValueError('이력 확인·저장 요청만 허용됩니다.')
        request = Request(self.base_url + path,
                          data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode(),
                          headers={'Authorization': self.authorization, 'Content-Type': 'application/json', 'Accept': 'application/json'},
                          method='GET' if payload is None else 'POST')
        try:
            with self.opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise RelayError('UNEXPECTED_HTTP_STATUS', True)
                body = response.read(MAX_RESPONSE + 1)
                if len(body) > MAX_RESPONSE:
                    raise RelayError('INVALID_RESPONSE', True)
                return json.loads(body)
        except HTTPError as error:
            delay = retry_after(error.headers.get('Retry-After'), self.clock())
            error.close()
            # Do not log response bodies, authorization headers, redirects or remote HTML.
            raise RelayError(f'HTTP_{error.code}', error.code == 429 or error.code >= 500, delay) from None
        except (URLError, TimeoutError, OSError):
            raise RelayError('CONNECTION_RESULT_UNKNOWN', True) from None
        except (ValueError, UnicodeError):
            raise RelayError('INVALID_RESPONSE', True) from None


def validated_receipt(result, rows):
    if not isinstance(result, dict) or not isinstance(result.get('items'), list) or len(result['items']) != len(rows):
        raise RelayError('INVALID_RECEIPT', True)
    items = result['items']
    counts = {'SAVED': 0, 'DUPLICATE': 0, 'REJECTED': 0}
    seen = set()
    for item in items:
        if not isinstance(item, dict) or not integer(item.get('row'), 1) or item['row'] > len(rows) or item['row'] in seen:
            raise RelayError('INVALID_RECEIPT', True)
        seen.add(item['row'])
        status = item.get('result')
        if (status not in counts or item.get('externalId') != rows[item['row'] - 1]['externalId']
                or not isinstance(item.get('detail'), str) or len(item['detail']) > 5000
                or type(item.get('retryable', False)) is not bool
                or (status != 'REJECTED' and (not integer(item.get('productId'), 1) or item.get('retryable', False)))):
            raise RelayError('INVALID_RECEIPT', True)
        counts[status] += 1
    if any(not integer(result.get(key)) or result[key] != counts[key.upper()] for key in ('saved', 'duplicate', 'rejected')):
        raise RelayError('INVALID_RECEIPT', True)
    return {'saved': result['saved'], 'duplicate': result['duplicate'], 'rejected': result['rejected'], 'items': items}


def upload(collection, client, state, persist, clock=time.time, retry_rejected=False):
    identity = digest({'target': client.target, 'pages': collection['pages']})
    if state and (state.get('schemaVersion') != 1 or state.get('identity') != identity):
        raise ValueError('다른 서버·계정·수집 내용의 저장 기록으로 재개할 수 없습니다.')
    state = state or {'schemaVersion': 1, 'identity': identity, 'target': client.target,
                      'collectionStatus': collection['status'], 'collectionStopReason': collection.get('stopReason'),
                      'pages': {}, 'status': 'PENDING', 'nextAttemptAt': 0}
    if state['status'] in ('UPLOADED', 'UPLOADED_WITH_REJECTIONS') and not retry_rejected:
        return state
    if state.get('nextAttemptAt', 0) > clock():
        return state
    state['status'], state['lastError'] = 'RUNNING', None
    state['updatedAt'] = clock()
    persist(state)
    try:
        ready = client.request(READINESS)
        if not isinstance(ready, dict) or type(ready.get('ready')) is not bool:
            raise RelayError('INVALID_READINESS', True)
        if not ready['ready']:
            raise RelayError('ACCOUNT_NOT_READY', True, 300)
        for page in collection['pages']:
            key = str(page['page'])
            previous = state['pages'].get(key, {})
            receipt = previous.get('receipt')
            retryable = receipt and any(i.get('retryable', False) for i in receipt['items'])
            if receipt and not retryable and not (retry_rejected and receipt['rejected']):
                continue
            attempt = previous.get('attempts', 0) + 1
            state['pages'][key] = {'status': 'RESULT_UNKNOWN', 'attempts': attempt, 'lastAttemptAt': clock()}
            if receipt:
                state['pages'][key]['previousReceipt'] = receipt
            persist(state)  # A crash after POST is retried through server deduplication.
            result = validated_receipt(client.request(IMPORT, page['batch']), page['batch']['rows'])
            state['pages'][key].update(status='ACKNOWLEDGED', receipt=result, acknowledgedAt=clock())
            persist(state)
            if any(i.get('retryable', False) for i in result['items']):
                raise RelayError('ROW_STORAGE_RESULT_UNKNOWN', True, min(3600, 60 * 2 ** min(attempt - 1, 6)))
        rejected = sum(p['receipt']['rejected'] for p in state['pages'].values())
        state['status'] = 'UPLOADED_WITH_REJECTIONS' if rejected else 'UPLOADED'
        state['nextAttemptAt'] = 0
    except RelayError as error:
        state['status'] = 'WAITING_RETRY' if error.retryable else 'BLOCKED'
        state['lastError'] = error.code
        state['nextAttemptAt'] = clock() + error.delay if error.retryable else 0
    except KeyboardInterrupt:
        state['status'], state['lastError'] = 'WAITING_RETRY', 'INTERRUPTED'
        state['nextAttemptAt'] = clock() + 60
    finally:
        state['updatedAt'] = clock()
        persist(state)
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--retry-rejected', action='store_true', help='Recheck previously rejected identities after correcting their connection')
    args = parser.parse_args()
    try:
        collection = read_collection(args.input)
        client = Client(args.base_url, os.environ.get('SBSHOP_RELAY_USERNAME'), os.environ.get('SBSHOP_RELAY_PASSWORD'))
        if args.input.resolve() == args.receipt.resolve():
            raise ValueError('수집 파일과 저장 결과 파일은 달라야 합니다.')
        args.receipt.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with args.receipt.with_suffix(args.receipt.suffix + '.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError('같은 저장 기록의 전송이 이미 실행 중입니다.') from None
            state = json.loads(args.receipt.read_text()) if args.receipt.exists() else None
            result = upload(collection, client, state, lambda r: atomic_save(args.receipt, r), retry_rejected=args.retry_rejected)
        counts = {key: sum(p.get('receipt', {}).get(key, 0) for p in result['pages'].values()) for key in ('saved', 'duplicate', 'rejected')}
        print(json.dumps({'status': result['status'], 'collectionStatus': result['collectionStatus'], 'lastError': result.get('lastError'),
                          'nextAttemptAt': result['nextAttemptAt'], 'acknowledgedPages': sum('receipt' in p for p in result['pages'].values()), **counts}, ensure_ascii=False))
        return 0 if result['status'] == 'UPLOADED' else 2
    except (ValueError, OSError) as error:
        # Fixed input/IO categories avoid leaking environment credentials through arbitrary exception text.
        parser.exit(1, '수집 파일·서버 주소·인증 환경변수·저장 결과 경로를 확인하세요. 원본은 보존됩니다.\n')


if __name__ == '__main__':
    raise SystemExit(main())
