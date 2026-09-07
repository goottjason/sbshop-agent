"""One durable SB-requested public read per call; independent of history collection/upload flags."""
import fcntl
import json
from pathlib import Path
import re
import time
from browser import BrowserError
from relay import Client, RelayError, atomic_save

BASE = '/api/v1/products/marketplus-public-checks/worker'

class PublicClient(Client):
    def allowed(self, path, payload):
        return payload is not None and (path == BASE + '/claim' or bool(re.fullmatch(re.escape(BASE) + r'/[1-9][0-9]*/report', path)))


def process_one(root, browser, make_client, clock=time.time):
    """Report retry keeps the exact capture/token; a lost claim is safely reclaimed after its server lease."""
    root = Path(root)
    receipt = root / 'public-check-pending.json'
    gate = root / 'public-check-relay-gate.json'
    status = {'state': 'IDLE', 'checkedAt': clock(), 'error': None}
    try:
        wait = json.loads(gate.read_text()) if gate.exists() else {}
        if wait.get('nextAttemptAt', 0) > clock():
            return status | {'state': 'RELAY_WAIT', 'nextAttemptAt': wait['nextAttemptAt'], 'error': wait.get('error')}
        client = make_client()
        pending = json.loads(receipt.read_text()) if receipt.exists() else None
        if pending and pending.get('target') != client.target:
            return status | {'state': 'ATTENTION', 'error': 'SB_TARGET_CHANGED'}
        if pending:
            result = client.request(BASE + '/' + str(pending['taskId']) + '/report', pending['report'])
            if not isinstance(result, dict) or result.get('id') != pending['taskId'] or result.get('state') not in ('OBSERVED','RETRY_WAIT','FAILED_UNVERIFIED','STALE','RUNNING'):
                raise RelayError('INVALID_RESPONSE', True)
            receipt.unlink()
            return status | {'state': result['state'], 'taskId': pending['taskId']}
        # This cooperative lease also lets an operator suspend browser use without changing user request flags.
        with (root / 'browser-lease.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return status | {'state': 'BROWSER_BUSY'}
            response = client.request(BASE + '/claim', {})
            task = response.get('task') if isinstance(response, dict) else None
            if task is None:
                if not isinstance(response, dict) or 'task' not in response:
                    raise RelayError('INVALID_RESPONSE', True)
                return status
            if (not isinstance(task, dict) or type(task.get('taskId')) is not int or task['taskId'] <= 0
                    or not isinstance(task.get('leaseToken'), str) or not re.fullmatch(r'[a-f0-9-]{36}', task['leaseToken'])
                    or not isinstance(task.get('target'), dict)):
                raise RelayError('INVALID_RESPONSE', True)
            report = {'leaseToken': task['leaseToken'], 'observation': None, 'errorCode': None, 'httpStatus': None, 'retryAfterSeconds': None}
            browser.public_http_status = None
            try:
                browser.connect()
                report['observation'] = browser.observe_public(task['target'])
            except BrowserError as error:
                code = str(error)
                report['errorCode'] = 'BROWSER_UNAVAILABLE' if code.startswith('BROWSER_') else 'HTTP_ERROR' if code == 'HTTP_ERROR' else 'PUBLIC_PAGE_UNVERIFIED'
            except (ValueError, TypeError, KeyError, OSError):
                report['errorCode'] = 'PUBLIC_PAGE_UNVERIFIED'
            observed_status = browser.public_http_status
            if type(observed_status) is int and 100 <= observed_status <= 599:
                report['httpStatus'] = observed_status
            # DOM has no verified Retry-After headers. Do not invent one from a page/message.
            atomic_save(receipt, {'target': client.target, 'taskId': task['taskId'], 'report': report})
        # Receipt exists before the API mutation, allowing exact replay after process/API response loss.
        result = client.request(BASE + '/' + str(task['taskId']) + '/report', report)
        if not isinstance(result, dict) or result.get('id') != task['taskId'] or result.get('state') not in ('OBSERVED','RETRY_WAIT','FAILED_UNVERIFIED','STALE','RUNNING'):
            raise RelayError('INVALID_RESPONSE', True)
        receipt.unlink()
        return status | {'state': result['state'], 'taskId': task['taskId']}
    except RelayError as error:
        # A rejected expired lease cannot starve every later request; retain a sanitized failure receipt.
        if error.code == 'HTTP_400' and receipt.exists():
            receipt.replace(root / 'public-check-rejected.json')
        atomic_save(gate, {'nextAttemptAt': clock() + max(5, error.delay), 'error': error.code})
        return status | {'state': 'RELAY_WAIT', 'error': error.code}
    except (ValueError, TypeError, KeyError, OSError):
        return status | {'state': 'ATTENTION', 'error': 'PUBLIC_QUEUE_STORAGE_OR_CONFIGURATION_ERROR'}
