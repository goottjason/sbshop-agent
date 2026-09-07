(() => {
  if (location.origin !== 'https://mp.cafe24.com' || !location.pathname.startsWith('/mp/')) {
    throw new Error('MARKETPLUS_PAGE_REQUIRED');
  }
  const visible = e => Boolean(e.getClientRects().length) && getComputedStyle(e).visibility !== 'hidden';
  const radio = name => {
    const nodes = Array.from(document.querySelectorAll('input[type=radio]')).filter(e => e.name === name);
    const selected = nodes.filter(e => e.checked);
    return selected.length === 1 ? {value: selected[0].value, label: selected[0].closest('label')?.innerText.trim()} : null;
  };
  const output = {capturedAt: new Date().toISOString(), title: document.title, url: location.href, kind: 'unsupported'};
  const path = location.pathname;
  if (path === '/mp/account/front/config') {
    output.kind = 'commonSettings';
    output.settings = Object.fromEntries(['is_send_rtq', 'use_sold_out', 'is_split_prd_name', 'is_move_prd_page'].map(k => [k, radio(k)]));
    output.ready = Object.values(output.settings).every(Boolean);
  } else if (path.startsWith('/mp/account/regist/')) {
    output.kind = 'accountSettings';
    output.settings = Object.fromEntries(['use_order', 'use_auto_selling', 'selling_period_day'].map(k => [k, radio(k)]));
    output.ready = Object.values(output.settings).every(Boolean);
  } else if (path === '/mp/account/lists') {
    output.kind = 'linkedAccounts';
    output.mallIds = [...new Set(Array.from(document.querySelectorAll('a[href]')).flatMap(a => {
      const url = new URL(a.href);
      const mall = /^([a-zA-Z0-9_-]+)\.cafe24\.com$/.exec(url.hostname);
      return url.protocol === 'https:' && mall && /^\/(admin|disp\/admin)\//.test(url.pathname) ? [mall[1]] : [];
    }))];
    output.accounts = Array.from(document.querySelectorAll('tbody tr')).filter(r => r.innerText.includes('연동완료')).map(r => {
      const a = r.querySelector('a[data-market][data-market_user_id]');
      return a ? {market: a.dataset.market, account: a.dataset.market_user_id, shopNo: a.dataset.shop_no, accountRecord: a.dataset.no} : null;
    }).filter(Boolean);
  } else if (path === '/mp/product/front/detail') {
    output.kind = 'productInheritance';
    output.productNo = new URLSearchParams(location.search).get('product_no');
    output.fields = Array.from(document.querySelectorAll('input[name*="[is_default_"]')).filter(visible).map(e => {
      const label = e.parentElement.querySelector('span');
      return {name: e.name, value: e.value, checked: e.checked, displayedMode: label ? getComputedStyle(label, '::after').content : null};
    });
    output.ready = output.fields.length > 0;
  } else if (path === '/mp/queue/productList') {
    output.kind = 'transmissionHistory';
    output.filters = Array.from(document.querySelectorAll('input[type=text]')).filter(visible).map(e => ({id: e.id, placeholder: e.placeholder, value: e.value}));
    output.selectedTab = Array.from(document.querySelectorAll('[role=tab]')).find(e => e.getAttribute('aria-selected') === 'true')?.innerText;
    output.busy = Array.from(document.querySelectorAll('[aria-busy="true"], [role=progressbar], .MuiCircularProgress-root')).some(visible);
    output.headers = Array.from(document.querySelectorAll('thead th')).filter(visible).map(e => e.innerText.trim());
    const pageButtons = Array.from(document.querySelectorAll('nav button')).filter(visible);
    const currentPages = pageButtons.filter(e => e.getAttribute('aria-current') === 'true');
    const nextPages = pageButtons.filter(e => e.getAttribute('aria-label') === 'Go to next page');
    output.pagination = currentPages.length === 1 && nextPages.length === 1 ? {
      page: Number(currentPages[0].innerText.trim()), hasNext: !nextPages[0].disabled,
    } : null;
    output.visibleRowCount = Array.from(document.querySelectorAll('tbody tr')).filter(visible).length;
    output.rows = Array.from(document.querySelectorAll('tbody tr')).filter(visible).slice(0,100).map(r => ({
      cells: Array.from(r.querySelectorAll('td')).map(e => e.innerText.trim()),
      marketIcons: Array.from(r.querySelectorAll('svg[data-testid]')).map(e => e.getAttribute('data-testid')),
      products: Array.from(r.querySelectorAll('a[data-prd_code]')).map(e => ({productCode: e.dataset.prd_code, productNo: e.dataset.prd_no, shopNo: e.dataset.shop_no, entityNo: e.dataset.prd_entity_no})),
    }));
    // A selected tab with no rows is not proof of absence or completed loading.
    // The default completed tab omits queue_status. An explicit or duplicated wrong value is rejected.
    const statuses = new URLSearchParams(location.search).getAll('queue_status');
    const success = statuses.length === 0 || statuses.length === 1 && statuses[0] === 'S';
    const failure = statuses.length === 1 && statuses[0] === 'F';
    const prefix = output.selectedTab === '전송완료' && success ? '[성공]' : output.selectedTab === '전송실패' && failure ? '[실패]' : null;
    output.ready = !output.busy && Boolean(prefix) && output.rows.length > 0 && output.visibleRowCount <= 100
      && output.rows.every(r => r.cells.length === 9 && r.cells[6].startsWith(prefix) && r.products.length === 1);
  }
  return JSON.stringify(output);
})()
