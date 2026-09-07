"""Validate a read-only public DOM result. Never interprets access failures as deletion or success."""
from datetime import datetime, timezone
from urllib.parse import urlsplit, parse_qs
import re


class PublicFieldError(ValueError):
    pass


def parse(snapshot, market, external_id, seller_account, now=None):
    if market not in ('GMARKET', 'AUCTION') or not re.fullmatch(r'[A-Za-z0-9]{1,20}', external_id):
        raise PublicFieldError('PUBLIC_TARGET_INVALID')
    if not isinstance(seller_account, str) or not seller_account.strip():
        raise PublicFieldError('SELLER_ACCOUNT_REQUIRED')
    if (not isinstance(snapshot, dict) or snapshot.get('schemaVersion') != 1
            or snapshot.get('source') != 'LIVE_CHROME_PUBLIC_MARKET' or snapshot.get('market') != market
            or snapshot.get('externalId') != external_id or snapshot.get('errorPage') is not False):
        raise PublicFieldError('PUBLIC_PAGE_UNVERIFIED')
    expected_host, expected_path, key = ('item.gmarket.co.kr', '/Item', 'goodscode') if market == 'GMARKET' else (
        'itempage3.auction.co.kr', '/DetailView.aspx', 'ItemNo')
    url = urlsplit(snapshot.get('url', ''))
    if (url.scheme != 'https' or url.netloc != expected_host or url.path != expected_path
            or parse_qs(url.query) != {key: [external_id]} or url.fragment):
        raise PublicFieldError('PUBLIC_PRODUCT_URL_MISMATCH')
    if snapshot.get('productIds') != [external_id]:
        raise PublicFieldError('PUBLIC_PRODUCT_ID_MISMATCH')
    if snapshot.get('sellerAccounts') != [seller_account]:
        raise PublicFieldError('PUBLIC_SELLER_NOT_VERIFIED')
    try:
        captured = datetime.fromisoformat(snapshot['capturedAt'].replace('Z', '+00:00'))
        current = now or datetime.now(timezone.utc)
        if captured.tzinfo is None or not -60 <= (current - captured).total_seconds() <= 300:
            raise ValueError()
    except (KeyError, TypeError, ValueError, AttributeError):
        raise PublicFieldError('PUBLIC_CAPTURE_TIME_INVALID') from None
    fields = {'salePrice': unique_integer(snapshot.get('prices'), 1_000_000_000_000, 'PUBLIC_PRICE_AMBIGUOUS')}
    if (market == 'AUCTION' and snapshot.get('quantityBasis') == 'PUBLIC_NO_OPTION_REMAINING'
            and snapshot.get('quantities')):
        fields['salesQuantity'] = unique_integer(snapshot['quantities'], 999_999, 'PUBLIC_QUANTITY_AMBIGUOUS')
    return {'schemaVersion': 1, 'source': 'LIVE_CHROME_PUBLIC_MARKET', 'market': market, 'externalId': external_id,
            'sellerAccount': seller_account, 'capturedAt': captured.isoformat(), 'url': snapshot['url'], 'values': fields,
            'quantityBasis': snapshot.get('quantityBasis'), 'listingState': 'UNVERIFIED'}


def unique_integer(values, maximum, code):
    if (not isinstance(values, list) or len(values) != 1 or not isinstance(values[0], str)
            or not re.fullmatch(r'0|[1-9][0-9]{0,12}', values[0]) or int(values[0]) > maximum):
        raise PublicFieldError(code)
    return str(int(values[0]))
