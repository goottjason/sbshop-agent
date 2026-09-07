#!/usr/bin/env python3
"""Serial MarketPlus observer: collect both result tabs, persist pages, then relay them to SB.

Defaults to paused. An enabled.json file explicitly enables collection and (separately) upload.
status.json records last attempt, coverage, failures and acknowledgements. No market write is exposed.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import time
import uuid
from browser import Browser, BrowserError, collector
from relay import Client, RelayError, atomic_save, read_collection, upload

COLLECTION_ERRORS = {'LOGIN_REQUIRED', 'HISTORY_PAGE_REQUIRED', 'HISTORY_PAGE_NOT_STABLE',
                     'HISTORY_START_PAGE_MISMATCH', 'HISTORY_DATE_RANGE_REVIEW_REQUIRED',
                     'ACCOUNT_CONFIGURATION_REQUIRED', 'MALL_ACCOUNT_MISMATCH', 'SELLER_ACCOUNT_MISMATCH',
                     'ACCOUNT_PAGE_NOT_VERIFIED', 'BROWSER_UNAVAILABLE', 'BROWSER_COMMAND_FAILED'}


def collection_error(error):
    return str(error) if str(error) in COLLECTION_ERRORS else 'HISTORY_PAGE_NOT_VERIFIED'


def save_status(path, status):
    atomic_save(path, status)
    shared = os.environ.get('MARKETPLUS_SHARED_STATUS')
    if shared:
        atomic_save(Path(shared), status)


def read_flags(root):
    path = root / 'enabled.json'
    value = json.loads(path.read_text()) if path.exists() else {}
    return value.get('collect') is True, value.get('upload') is True


def inventory(root):
    result = {'uploaded': 0, 'withRejections': 0, 'pending': 0, 'blocked': 0, 'nextAttemptAt': 0, 'lastAcknowledgedAt': None, 'error': None}
    for source in sorted((root / 'captures').glob('*.json')):
        receipt = root / 'receipts' / source.name
        try:
            read_collection(source)
            state = json.loads(receipt.read_text()) if receipt.exists() else {}
            key = {'UPLOADED': 'uploaded', 'UPLOADED_WITH_REJECTIONS': 'withRejections', 'BLOCKED': 'blocked'}.get(state.get('status'), 'pending')
            result[key] += 1
            stamps = [p.get('acknowledgedAt', 0) for p in state.get('pages', {}).values()]
            result['lastAcknowledgedAt'] = max([result['lastAcknowledgedAt'] or 0, *stamps]) or None
        except (ValueError, OSError, TypeError, KeyError):
            result['blocked'] += 1
    return result


def drain(root, client, now=time.time):
    gate_path = root / 'upload-gate.json'
    gate = json.loads(gate_path.read_text()) if gate_path.exists() else {}
    if gate.get('blocked') or gate.get('nextAttemptAt', 0) > now():
        return {**inventory(root), 'nextAttemptAt': gate.get('nextAttemptAt', 0), 'error': gate.get('reason')}
    gate = {}
    for source in sorted((root / 'captures').glob('*.json')):
        receipt = root / 'receipts' / source.name
        try:
            data = read_collection(source)
            state = json.loads(receipt.read_text()) if receipt.exists() else None
        except (ValueError, OSError, TypeError, KeyError):
            continue  # An invalid capture cannot starve other completed files.
        if state and state['status'] in ('UPLOADED', 'UPLOADED_WITH_REJECTIONS', 'BLOCKED'):
            continue
        state = upload(data, client, state, lambda value: atomic_save(receipt, value), clock=now)
        if state['status'] == 'WAITING_RETRY':
            gate = {'nextAttemptAt': state['nextAttemptAt'], 'reason': state.get('lastError')}
            break
        if state['status'] == 'BLOCKED' and state.get('lastError') in ('HTTP_401', 'HTTP_403', 'HTTP_404', 'HTTP_302'):
            gate = {'blocked': True, 'reason': state['lastError']}
            break
    atomic_save(gate_path, gate)
    return {**inventory(root), 'nextAttemptAt': gate.get('nextAttemptAt', 0), 'error': gate.get('reason')}


def cycle(root, browser, mall, max_pages, make_client, clock=time.time, accounts=None):
    status = {'lastAttemptAt': clock(), 'heartbeatAt': clock(), 'state': 'RUNNING', 'collectEnabled': False, 'uploadEnabled': False,
              'collections': [], 'upload': None, 'error': None}
    previous_path = root / 'status.json'
    try:
        if previous_path.exists():
            previous = json.loads(previous_path.read_text())
            status['lastCollectedAt'] = previous.get('lastCollectedAt')
            status['lastUploadedAt'] = previous.get('lastUploadedAt')
        collecting, uploading = read_flags(root)
        status.update(collectEnabled=collecting, uploadEnabled=uploading)
        save_status(previous_path, status)
        try:
            if collecting or not uploading:
                browser.connect()
            if collecting:
                status['accountVerification'] = browser.verify_accounts(mall, accounts or {})
                collect_tabs(root, browser, mall, max_pages, status, clock)
        except (BrowserError, ValueError, OSError, KeyError, TypeError) as error:
            status['error'] = collection_error(error)
        if uploading:
            status['upload'] = drain(root, make_client(), clock)
            acknowledged = status['upload'].get('lastAcknowledgedAt')
            if acknowledged:
                status['lastUploadedAt'] = max(status.get('lastUploadedAt') or 0, acknowledged)
        else:
            status['upload'] = inventory(root)
        status['state'] = 'ATTENTION' if status['error'] or (uploading and any(status['upload'][k] for k in ('pending', 'blocked', 'withRejections'))) else 'PAUSED' if not collecting and not uploading else 'OBSERVED_PARTIAL'
    except (BrowserError, RelayError) as error:
        status['state'], status['error'] = 'ATTENTION', str(error)
    except (ValueError, OSError, KeyError, TypeError):
        status['state'], status['error'] = 'ATTENTION', 'CONFIGURATION_OR_STORAGE_ERROR'
    finally:
        status['finishedAt'] = status['heartbeatAt'] = clock()
        save_status(previous_path, status)
    return status


def collect_tabs(root, browser, mall, max_pages, status, clock):
    previous_path = root / 'status.json'
    def persist_capture(path, value):
        atomic_save(path, value)
        status['heartbeatAt'] = clock()
        save_status(previous_path, status)

    for outcome in ('SUCCESS', 'FAILURE'):
        try:
            first = browser.prepare(outcome)
        except (BrowserError, ValueError, OSError, KeyError, TypeError) as error:
            reason = collection_error(error)
            status['collections'].append({'outcome': outcome, 'status': 'PARTIAL', 'stopReason': reason, 'pages': 0, 'rows': 0, 'context': None})
            status['error'] = reason
            save_status(previous_path, status)
            continue
        capture_path = root / 'captures' / f'{int(clock())}-{outcome}-{uuid.uuid4().hex}.json'
        reads = [first]
        def read():
            return reads.pop() if reads else browser.snapshot()
        run = collector.collect(mall, max_pages, read=read, next_page=browser.next_page,
                                persist=lambda value: persist_capture(capture_path, value))
        info = {'outcome': outcome, 'status': run['status'], 'stopReason': run.get('stopReason'),
                'pages': len(run['pages']), 'rows': sum(len(p['batch']['rows']) for p in run['pages']),
                'context': run.get('context')}
        status['collections'].append(info)
        save_status(previous_path, status)
        if run['status'] == 'PARTIAL' and run.get('stopReason') != 'PAGE_LIMIT':
            status['error'] = collection_error(run.get('stopReason'))
    if len(status['collections']) == 2 and not status['error']:
        status['lastCollectedAt'] = clock()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, default=Path('/state'))
    parser.add_argument('--webdriver-url', default='http://marketplus-browser:4444')
    parser.add_argument('--base-url', default='http://sbshop-api:8080')
    parser.add_argument('--mall-id', required=True)
    parser.add_argument('--interval', type=int, default=900)
    parser.add_argument('--max-pages', type=int, default=10, help='Per result tab, 1..100')
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    if not 300 <= args.interval <= 86400 or not 1 <= args.max_pages <= 100:
        parser.error('주기는 300~86400초, 페이지는 1~100이어야 합니다.')
    root = args.state_dir
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    browser = Browser(args.webdriver_url, root / 'browser-session.json')
    make_client = lambda: Client(args.base_url, os.environ.get('SBSHOP_RELAY_USERNAME'), os.environ.get('SBSHOP_RELAY_PASSWORD'))
    stopping = False
    def stop(signum, frame):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with (root / 'worker.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.exit(1, '같은 수집 작업이 이미 실행 중입니다.\n')
        while not stopping:
            status = cycle(root, browser, args.mall_id, args.max_pages, make_client,
                           accounts={'gmarket': os.environ.get('MARKETPLUS_GMARKET_ACCOUNT', ''),
                                     'auction': os.environ.get('MARKETPLUS_AUCTION_ACCOUNT', '')})
            print(json.dumps({k: status[k] for k in ('state', 'error', 'lastAttemptAt', 'finishedAt')}, ensure_ascii=False), flush=True)
            if args.once:
                break
            delay = min(args.interval, 30) if status['state'] == 'ATTENTION' and not status['collectEnabled'] and not status['uploadEnabled'] else args.interval
            end = time.monotonic() + delay
            # Keep the interactive session alive without reloading or reading the market page.
            while not stopping and time.monotonic() < end:
                time.sleep(min(30, max(0, end - time.monotonic())))
                status['heartbeatAt'] = time.time()
                save_status(root / 'status.json', status)
                # Explicit operator flag changes take effect without waiting for the next full interval.
                try:
                    if read_flags(root) != (status['collectEnabled'], status['uploadEnabled']):
                        break
                except (ValueError, OSError):
                    break
                try:
                    browser.url()
                except BrowserError:
                    pass


if __name__ == '__main__': main()
