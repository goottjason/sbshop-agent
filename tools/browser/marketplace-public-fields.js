(() => {
  // Only public product identity and typed field candidates leave this script; no page body is retained.
  const market = location.origin === 'https://item.gmarket.co.kr' && location.pathname === '/Item' ? 'GMARKET'
    : location.origin === 'https://itempage3.auction.co.kr' && location.pathname === '/DetailView.aspx' ? 'AUCTION' : null;
  if (!market) throw new Error('PUBLIC_PRODUCT_PAGE_REQUIRED');
  const visible = node => !!node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
  const params = [...new URLSearchParams(location.search)].filter(([key]) => key.toLowerCase() === (market === 'GMARKET' ? 'goodscode' : 'itemno'));
  const externalId = params.length === 1 ? params[0][1] : null;
  const body = document.body.innerText;
  const productIds = [...body.matchAll(/상품번호\s*:?\s*([A-Za-z0-9]{1,20})/g)].map(match => match[1]);
  const idNodes = document.querySelectorAll('[data-goodscode],[data-montelena-goodscode]');
  for (const node of idNodes) if (visible(node)) {
    for (const key of ['data-goodscode', 'data-montelena-goodscode']) if (node.hasAttribute(key)) productIds.push(node.getAttribute(key));
  }
  const sellers = [...document.querySelectorAll(market === 'AUCTION'
    ? '#btnFavoriteShop[data-montelena-sellerid]' : '[data-montelena-sellerid],[data-sellerid]')].filter(visible)
    .flatMap(node => ['data-montelena-sellerid', 'data-sellerid'].map(key => node.getAttribute(key))).filter(Boolean);
  const prices = [...body.matchAll(/(^|\n)\s*([0-9][0-9,]*)\s*원\s*판매가\s*(?=\n|$)/g)].map(match => match[2].replaceAll(',', ''));
  const quantities = market === 'AUCTION' ? [...body.matchAll(/남은수량\s*([0-9][0-9,]*)\s*개/g)].map(match => match[1].replaceAll(',', '')) : [];
  const noOptionQuantity = market === 'AUCTION' && [...document.querySelectorAll('input#orderQtyNoOption')].some(visible);
  const optionControls = [...document.querySelectorAll('select,[role=combobox]')].filter(visible)
    .some(node => /옵션|선택사항/.test(node.getAttribute('aria-label') || node.closest('section,fieldset,dl')?.innerText || ''));
  const errorPage = !!document.querySelector('img.img_error,.img_error') || /서비스\s*이용이\s*불가|요청하신\s*페이지를\s*찾을\s*수|접근이\s*제한/.test(body);
  return JSON.stringify({ schemaVersion: 1, source: 'LIVE_CHROME_PUBLIC_MARKET', capturedAt: new Date().toISOString(), market, externalId,
    url: location.origin + location.pathname + (externalId ? '?' + (market === 'GMARKET' ? 'goodscode=' : 'ItemNo=') + encodeURIComponent(externalId) : ''),
    productIds: [...new Set(productIds)], sellerAccounts: [...new Set(sellers)], prices,
    quantities: noOptionQuantity && !optionControls ? quantities : [], errorPage,
    quantityBasis: noOptionQuantity && !optionControls ? 'PUBLIC_NO_OPTION_REMAINING' : 'NOT_VERIFIED',
    notices: ['공개 페이지 표시값이며 판매 상태나 삭제 여부를 확정하지 않습니다.',
      '대표·추가 이미지와 상세 HTML은 검증된 영역 선택자가 확보된 후 수집합니다.'] });
})()
