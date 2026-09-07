(() => {
  // Read-only selector discovery. This probe supplies evidence, never a field-match verdict.
  const market = location.hostname === 'item.gmarket.co.kr' ? 'GMARKET'
    : location.hostname === 'itempage3.auction.co.kr' ? 'AUCTION' : null;
  if (!market || location.protocol !== 'https:') throw new Error('EXPECTED_MARKET_PRODUCT_PAGE');
  const visible = node => !!node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
  const safeUrl = value => {
    const url = new URL(value, location.href);
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    const keep = new URLSearchParams();
    for (const [key, value] of url.searchParams)
      if (/^(itemno|goodscode|goodsno|sellerid|seller|id|item|goods|type|version)$/i.test(key) && /^[A-Za-z0-9_.-]{1,100}$/.test(value)) keep.append(key, value);
    return url.origin + url.pathname + (keep.size ? '?' + keep : '');
  };
  const identity = node => ({ tag: node.tagName, id: node.id, className: typeof node.className === 'string' ? node.className : '',
    attributes: [...node.attributes].filter(a => /^(data-(montelena-)?sellerid|data-goodscode|data-montelena-goodscode|itemprop)$/.test(a.name)).map(a => [a.name, a.value]) });
  const leaves = [...document.querySelectorAll('span,strong,em,b,div,p,li')].filter(visible);
  const price = leaves.filter(node => (node.textContent || '').trim().length < 100 && /[\d,]+\s*원\s*판매가/.test(node.textContent || ''))
    .filter(node => ![...node.children].some(child => /[\d,]+\s*원\s*판매가/.test(child.textContent || '')))
    .slice(0, 8).map(node => ({ ...identity(node), text: node.textContent.trim(), parent: identity(node.parentElement), grandparent: identity(node.parentElement.parentElement) }));
  const quantity = leaves.filter(node => /남은수량\s*[\d,]+개/.test(node.textContent || '') && node.textContent.trim().length < 100)
    .filter(node => ![...node.children].some(child => /남은수량\s*[\d,]+개/.test(child.textContent || '')))
    .slice(0, 5).map(node => ({ ...identity(node), text: node.textContent.trim(), parent: identity(node.parentElement) }));
  const sellers = [...document.querySelectorAll('[data-montelena-sellerid],[data-sellerid],a[href*="minishop"],a[href*="Minishop"],a[href*="SellerId"],a[href*="sellerid"]')]
    .filter(visible).slice(0, 15).map(node => ({ ...identity(node), text: (node.textContent || '').trim().slice(0, 80), href: node.href ? safeUrl(node.href) : null }));
  const images = [...document.images].filter(visible).filter(img => /^https?:/.test(img.currentSrc) && img.naturalWidth >= 50 && img.naturalHeight >= 50).slice(0, 20)
    .map(img => ({ ...identity(img), src: safeUrl(img.currentSrc), alt: img.alt.slice(0, 120), width: img.naturalWidth, height: img.naturalHeight, parent: identity(img.parentElement) }));
  const frames = [...document.querySelectorAll('iframe')].filter(frame => /ItemDetailV2|ExplainViewV1/i.test(frame.src))
    .map(frame => ({ ...identity(frame), src: safeUrl(frame.src) }));
  return JSON.stringify({ schemaVersion: 1, kind: 'READ_ONLY_SELECTOR_PROBE', capturedAt: new Date().toISOString(), market,
    url: safeUrl(location.href), productIds: [...new Set([...document.querySelectorAll('[data-goodscode],[data-montelena-goodscode]')].flatMap(node => [node.getAttribute('data-goodscode'), node.getAttribute('data-montelena-goodscode')]).filter(Boolean))],
    price, quantity, sellers, images, frames });
})()
