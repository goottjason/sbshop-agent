#!/usr/bin/env python3
"""Collect completed/failed MarketPlus history pages from live Chrome.

Only history pagination is clicked; no login, settings changes or product transmission. Import JSON through
the SB product detail history panel. --mall-id must identify the logged-in Cafe24 mall;
the server independently checks its configured mall and reviewed market seller accounts.
"""
import argparse
import importlib.util
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import time
import os
import fcntl
from urllib.parse import urlparse, parse_qs
from zoneinfo import ZoneInfo


def normalize(snapshot, mall_id):
    url = urlparse(snapshot.get('url', ''))
    outcome, prefix, status = {
        '전송완료': ('SUCCESS', '[성공]', 'S'),
        '전송실패': ('FAILURE', '[실패]', 'F'),
    }.get(snapshot.get('selectedTab'), (None, None, None))
    rows = snapshot.get('rows', [])
    statuses = parse_qs(url.query, keep_blank_values=True).get('queue_status')
    status_matches = statuses == [status] or outcome == 'SUCCESS' and statuses is None
    if (url.scheme != 'https' or url.netloc != 'mp.cafe24.com' or url.path != '/mp/queue/productList'
            or snapshot.get('kind') != 'transmissionHistory' or not snapshot.get('ready')
            or snapshot.get('busy') is not False or not outcome
            or not status_matches
            or not 1 <= len(rows) <= 100 or snapshot.get('visibleRowCount') != len(rows)):
        raise ValueError('전송완료/전송실패 이력 페이지의 로딩·필터·행 수를 확인하세요. 빈 화면은 수집 완료로 처리하지 않습니다.')
    captured = datetime.fromisoformat(snapshot['capturedAt'].replace('Z', '+00:00'))
    if captured.tzinfo is None or not re.fullmatch(r'[a-zA-Z0-9_-]{1,100}', mall_id):
        raise ValueError('수집 시각·쇼핑몰 ID를 확인하세요.')

    def instant(value):
        parsed = datetime.strptime(value, '%Y-%m-%d %H:%M').replace(tzinfo=ZoneInfo('Asia/Seoul'))
        return parsed.astimezone(timezone.utc)

    output = []
    for number, row in enumerate(rows, 1):
        cells, products = row.get('cells', []), row.get('products', [])
        icons = row.get('marketIcons', [])
        # React generates suffixes per rendered icon (observed: Auction_svg, Auction_svg_2, ...).
        markets = list({market for name, market in [('Gmarket', 'GMARKET'), ('Auction', 'AUCTION')]
                        for icon in icons if isinstance(icon, str) and re.fullmatch(name + r'_svg(?:_[0-9]+)?', icon)})
        if len(cells) != 9 or len(products) != 1 or len(markets) != 1 or not cells[6].startswith(prefix):
            raise ValueError(f'{number}행: 마켓·상품 식별자·결과 형식이 모호합니다. 파일을 생성하지 않습니다.')
        product = products[0]
        if (product.get('shopNo') != '1' or product.get('productCode') != cells[2]
                or not re.fullmatch(r'P[A-Z0-9]{7,29}', cells[2])
                or not re.fullmatch(r'[1-9][0-9]{0,18}', product.get('productNo', ''))
                or not cells[1] or not cells[3] or not cells[5]):
            raise ValueError(f'{number}행: 쇼핑몰 번호·상품 식별자 불일치')
        requested, completed = instant(cells[7]), instant(cells[8])
        if completed < requested or completed > captured:
            raise ValueError(f'{number}행: 요청·완료·수집 시각 불일치')
        output.append(dict(market=markets[0], sellerAccount=cells[1], cafe24ProductNo=product['productNo'],
                           cafe24ProductCode=cells[2], externalId=cells[3], transferType=cells[5],
                           outcome=outcome, detail=cells[6], requestedAt=requested.isoformat(), completedAt=completed.isoformat()))
    return dict(schemaVersion=1, source='LIVE_CHROME_MARKETPLUS', coverage='CURRENT_PAGE', mallId=mall_id,
                shopNo=1, capturedAt=snapshot['capturedAt'], rows=output)


def snapshot():
    result = subprocess.run(['python3', str(Path(__file__).with_name('read-marketplus.py')), '/mp/queue/productList'],
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise ValueError(result.stderr.strip() or '라이브 크롬 읽기 실패')
    return json.loads(result.stdout)


def stable_key(value):
    return {k: v for k, v in value.items() if k != 'capturedAt'}


def stable_snapshot(read=snapshot, sleep=time.sleep):
    previous = read()
    for _ in range(8):
        sleep(1)
        current = read()
        if current.get('ready') and stable_key(previous) == stable_key(current):
            return current
        previous = current
    raise ValueError('이력 화면이 안정되지 않았습니다. 로딩·로그인 상태를 확인하세요.')


def advance():
    spec = importlib.util.spec_from_file_location('marketplus_reader', Path(__file__).with_name('read-marketplus.py'))
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    return json.loads(reader.run_browser(Path(__file__).with_name('marketplus-history-next.js').read_text(), '/mp/queue/productList'))


def page_context(value):
    # Ignore React input IDs and the UI render counter; keep the actual search values.
    return dict(tab=value.get('selectedTab'), filters=[dict(placeholder=f.get('placeholder'), value=f.get('value')) for f in value.get('filters', [])])


def page_number(value):
    pagination = value.get('pagination') or {}
    page = pagination.get('page')
    if not isinstance(page, int) or isinstance(page, bool) or page < 1 or not isinstance(pagination.get('hasNext'), bool):
        raise ValueError('페이지 번호·다음 페이지 여부를 확인하지 못했습니다.')
    if parse_qs(urlparse(value['url']).query).get('page', ['1']) != [str(page)]:
        raise ValueError('주소와 화면의 페이지 번호가 다릅니다.')
    return page


def ordinal_bounds(value):
    try:
        ordinals = [int(row['cells'][0]) for row in value['rows']]
    except (KeyError, ValueError, TypeError):
        raise ValueError('이력 화면의 행 순서를 확인하지 못했습니다.') from None
    if not ordinals or ordinals != list(range(ordinals[0], ordinals[0] - len(ordinals), -1)):
        raise ValueError('이력 행 순서가 바뀌었습니다. 수집을 다시 시작하세요.')
    return ordinals[0], ordinals[-1]


def collect(mall_id, max_pages, read=stable_snapshot, next_page=advance, persist=lambda _: None, resume=None):
    run = resume or dict(schemaVersion=1, kind='MARKETPLUS_COLLECTION', status='RUNNING', stopReason=None,
                         context=None, pages=[], startedAt=datetime.now(timezone.utc).isoformat())
    if run.get('schemaVersion') != 1 or run.get('kind') != 'MARKETPLUS_COLLECTION' or not isinstance(run.get('pages'), list):
        raise ValueError('재개할 수집 파일 형식을 확인하세요.')
    if resume and not run['pages']:
        raise ValueError('저장된 페이지가 없습니다. 새 파일로 다시 수집하세요.')
    if len(run['pages']) >= 2000:
        raise ValueError('한 파일의 최대 페이지 수(2000)에 도달했습니다.')
    run['status'] = 'RUNNING'
    run['stopReason'] = None
    persist(run)
    try:
        current = read()
        if resume:
            last = run['pages'][-1]
            payload = normalize(current, mall_id)
            if (page_number(current) != last['page'] or page_context(current) != run['context']
                    or stable_key(payload) != stable_key(last['batch'])):
                raise ValueError('재개하려면 마지막 저장 페이지를 같은 조건으로 여세요. 내용이 바뀌었다면 새 파일로 수집하세요.')
            if not current['pagination']['hasNext']:
                run['status'] = 'REACHED_LAST_PAGE'
                return run
            next_page()
            current = read()
        for index in range(max_pages):
            payload = normalize(current, mall_id)
            page = page_number(current)
            bounds = ordinal_bounds(current)
            context = page_context(current)
            if run['context'] is None:
                run['context'] = context
            if context != run['context']:
                raise ValueError('수집 중 조회 조건이 변경되었습니다.')
            if run['pages']:
                last = run['pages'][-1]
                if page != last['page'] + 1 or bounds[0] != last['lastOrdinal'] - 1:
                    raise ValueError('페이지 이동 중 이력이 추가·변경되었거나 페이지를 건너뛰었습니다. 새 파일로 재수집하세요.')
            run['pages'].append(dict(page=page, firstOrdinal=bounds[0], lastOrdinal=bounds[1], batch=payload))
            persist(run)
            if not current['pagination']['hasNext']:
                if bounds[1] != 1:
                    raise ValueError('마지막 페이지의 행 순서가 예상과 다릅니다.')
                run['status'] = 'REACHED_LAST_PAGE'
                break
            if index + 1 == max_pages or len(run['pages']) >= 2000:
                run['status'], run['stopReason'] = 'PARTIAL', 'PAGE_LIMIT'
                break
            next_page()
            current = read()
    except (ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as error:
        run['status'], run['stopReason'] = 'PARTIAL', str(error)
    except KeyboardInterrupt:
        run['status'], run['stopReason'] = 'PARTIAL', 'INTERRUPTED'
    finally:
        run['updatedAt'] = datetime.now(timezone.utc).isoformat()
        persist(run)
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mall-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--max-pages', type=int, default=1, help='Pages to read this run (1..100, default 1)')
    parser.add_argument('--resume', action='store_true', help='Resume from the last saved page in this file')
    args = parser.parse_args()
    lock = None
    try:
        if not 1 <= args.max_pages <= 100:
            raise ValueError('--max-pages 범위는 1~100입니다.')
        lock = args.output.with_name(args.output.name + '.lock').open('a')
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('같은 파일의 수집이 이미 실행 중입니다.') from None
        resume = json.loads(args.output.read_text()) if args.resume else None
        if not args.resume:
            # Reserve exclusively; a failed run never overwrites an earlier export.
            with args.output.open('x', encoding='utf-8') as handle:
                handle.write('{}\n')
        def persist(run):
            temporary = args.output.with_name(args.output.name + '.writing')
            with temporary.open('w', encoding='utf-8') as handle:
                json.dump(run, handle, ensure_ascii=False, indent=2)
                handle.write('\n')
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, args.output)
        run = collect(args.mall_id, args.max_pages, persist=persist, resume=resume)
        print(f"{run['status']}: {len(run['pages'])}페이지 / {sum(len(p['batch']['rows']) for p in run['pages'])}행: {args.output}")
        if run['stopReason']:
            print(f"중단 사유: {run['stopReason']}")
        print('선택한 조회 조건의 관측 이력이며 전체 상품 상태 검증이 아닙니다. 외부 상품 변경 없음.')
        if run['status'] == 'PARTIAL' and run['stopReason'] != 'PAGE_LIMIT':
            raise SystemExit(1)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f'{error}\n')
    finally:
        if lock is not None:
            lock.close()


if __name__ == '__main__':
    main()
