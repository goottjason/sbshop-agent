(() => {
  if (location.origin !== 'https://mp.cafe24.com' || location.pathname !== '/mp/queue/productList') {
    throw new Error('MARKETPLUS_HISTORY_REQUIRED');
  }
  const buttons = Array.from(document.querySelectorAll('nav button'));
  const current = buttons.filter(e => e.getAttribute('aria-current') === 'true');
  const next = buttons.filter(e => e.getAttribute('aria-label') === 'Go to next page');
  if (current.length !== 1 || next.length !== 1 || next[0].disabled) throw new Error('NEXT_HISTORY_PAGE_UNAVAILABLE');
  const from = Number(current[0].innerText.trim());
  if (!Number.isSafeInteger(from) || from < 1) throw new Error('CURRENT_PAGE_UNKNOWN');
  next[0].click();
  return JSON.stringify({from, requestedPage: from + 1});
})()
