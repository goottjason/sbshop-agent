import { useEffect, useState } from 'react';
import { Tooltip } from 'antd';
import { productApi, type ProductDetail, type MarketItemInfo, type MarketRegistrationRecord } from '../../api/productApi';
import { MARKET_BADGES } from './productGridShared';
import { notify } from '../../utils/notify';

const GREEN = '#166534';
const DIFF = '#b45309';
const DIFF_BG = '#fffbeb';

const LIVE_SUPPORTED_MARKETS = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24'];

type CompareState = 'same' | 'diff' | 'na';

type CompareRow = { label: string; local: string; live: string; state: CompareState };

type MarketResult = {
  fetchedAt: string;
  local: MarketRegistrationRecord | null;
  live: MarketItemInfo;
};

function extractErrorMessage(e: unknown): string {
  const res = (e as { response?: { data?: unknown; status?: number } })?.response;
  const data = res?.data;
  if (typeof data === 'string' && data) return data;
  if (data && typeof data === 'object') {
    const msg = (data as { message?: unknown; error?: unknown }).message
      ?? (data as { error?: unknown }).error;
    if (typeof msg === 'string' && msg) return msg;
  }
  if (res?.status) return `HTTP ${res.status}`;
  if (e instanceof Error && e.message) return e.message;
  return '알 수 없는 오류';
}

function marketLabel(marketType: string): string {
  return MARKET_BADGES.find((b) => b.key === marketType)?.label ?? marketType;
}

function marketColors(marketType: string): { bg: string; text: string } {
  const badge = MARKET_BADGES.find((b) => b.key === marketType);
  return { bg: badge?.bg ?? '#f1f5f9', text: badge?.text ?? '#475569' };
}

function flatten(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v !== 'object') return String(v).trim();
  return Object.entries(v as Record<string, unknown>)
    .filter(([, val]) => val !== null && val !== undefined && String(val).trim() !== '')
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, val]) => `${k}=${String(val).trim()}`)
    .join(', ');
}

function buildRows(d: ProductDetail, local: MarketRegistrationRecord | null, live: MarketItemInfo): CompareRow[] {
  const specs: { label: string; local: unknown; live: unknown; numeric?: boolean }[] = [
    { label: '상품명', local: local?.marketProductName || d.productName, live: live.name },
    { label: '원문명', local: d.originalName, live: live.originalName },
    { label: '브랜드', local: d.brand, live: live.brand },
    { label: '판매가', local: d.priceInfo?.salePrice, live: live.salePrice, numeric: true },
    { label: '재고', local: d.logisticsInfo?.stock, live: live.stock, numeric: true },
    { label: '제조사', local: d.sourcingInfo?.manufacturer, live: live.manufacturer },
    { label: '바코드', local: d.productSpec?.barcode, live: live.barcode },
    { label: '이미지 수', local: d.hostedImages?.length, live: live.images?.length, numeric: true },
    { label: '상세HTML 길이', local: d.detailHtml?.length, live: live.detailHtml?.length, numeric: true },
    { label: '마켓 식별자', local: flatten(local?.marketIdentifiers), live: flatten(live.marketIdentifiers) },
  ];

  return specs.map(({ label, local: l, live: r, numeric }) => {
    const localText = flatten(l);
    const liveText = flatten(r);
    if (liveText === '') return { label, local: localText, live: '', state: 'na' as const };
    const same = numeric
      ? Number(localText) === Number(liveText) && localText !== '' && Number.isFinite(Number(liveText))
      : localText === liveText;
    return { label, local: localText, live: liveText, state: same ? ('same' as const) : ('diff' as const) };
  });
}

function ValueCell({ text, emphasize }: { text: string; emphasize: boolean }) {
  if (text === '') return <span style={{ color: '#cbd5e1' }}>—</span>;
  return (
    <Tooltip title={text.length > 40 ? text : ''}>
      <span style={{
        display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        color: emphasize ? DIFF : '#111827', fontWeight: emphasize ? 700 : 500,
      }}>
        {text}
      </span>
    </Tooltip>
  );
}

function CompareTable({ rows }: { rows: CompareRow[] }) {
  const grid = '96px 1fr 1fr';
  return (
    <div style={{ marginTop: 8, border: '1px solid #eef2f7', borderRadius: 8, overflow: 'hidden' }}>
      <div style={{
        display: 'grid', gridTemplateColumns: grid, gap: 10, padding: '6px 10px',
        background: '#f8fafc', fontSize: 11, fontWeight: 700, color: '#6b7280',
      }}>
        <span>항목</span>
        <span>로컬 DB</span>
        <span>마켓 실제값</span>
      </div>
      {rows.map((r) => (
        <div key={r.label} style={{
          display: 'grid', gridTemplateColumns: grid, gap: 10, padding: '6px 10px',
          fontSize: 12, borderTop: '1px solid #f4f4f5',
          background: r.state === 'diff' ? DIFF_BG : '#fff',
        }}>
          <span style={{ color: '#6b7280', whiteSpace: 'nowrap' }}>{r.label}</span>
          <ValueCell text={r.local} emphasize={false} />
          {r.state === 'na'
            ? <span style={{ color: '#cbd5e1', fontStyle: 'italic' }}>마켓 미제공</span>
            : <ValueCell text={r.live} emphasize={r.state === 'diff'} />}
        </div>
      ))}
    </div>
  );
}

export function MarketLiveCompare({ productId, detail }: { productId: number; detail: ProductDetail }) {
  const [regs, setRegs] = useState<MarketRegistrationRecord[] | null>(null);
  const [regsError, setRegsError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [results, setResults] = useState<Record<string, MarketResult>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const res = await productApi.getMarketRegistrations(productId);
        if (alive) setRegs(res.data);
      } catch (e) {
        if (alive) {
          setRegs([]);
          setRegsError(extractErrorMessage(e));
        }
      }
    })();
    return () => { alive = false; };
  }, [productId]);

  const check = async (marketType: string) => {
    if (busy[marketType]) return;
    const label = marketLabel(marketType);
    setBusy((b) => ({ ...b, [marketType]: true }));
    setErrors((m) => { const next = { ...m }; delete next[marketType]; return next; });
    try {
      const [localRes, liveRes] = await Promise.all([
        productApi.getLocalMarketData(productId, marketType).catch(() => null),
        productApi.syncMarketLive(productId, marketType),
      ]);
      setResults((m) => ({
        ...m,
        [marketType]: {
          fetchedAt: new Date().toLocaleTimeString('ko-KR'),
          local: localRes?.data ?? null,
          live: liveRes.data,
        },
      }));
      notify.success(`${label} 실제값 조회 완료`);
    } catch (e) {
      const msg = extractErrorMessage(e);
      setErrors((m) => ({ ...m, [marketType]: msg }));
      setResults((m) => { const next = { ...m }; delete next[marketType]; return next; });
      notify.error(`${label} 실제값 조회 실패 — ${msg}`);
    } finally {
      setBusy((b) => ({ ...b, [marketType]: false }));
    }
  };

  if (regs === null) {
    return <div style={{ padding: 12, color: '#94a3b8', fontSize: 13 }}>등록 마켓 확인 중…</div>;
  }

  if (regsError) {
    return <div style={{ padding: 12, color: '#b91c1c', fontSize: 13 }}>등록 마켓 조회 실패 — {regsError}</div>;
  }

  if (regs.length === 0) {
    return <div style={{ padding: 12, color: '#94a3b8', fontSize: 13 }}>등록된 마켓이 없습니다.</div>;
  }

  const order = MARKET_BADGES.map((b) => b.key);
  const sorted = [...regs].sort((a, b) => order.indexOf(a.marketType) - order.indexOf(b.marketType));

  return (
    <div>
      <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 10 }}>
        마켓 API로 실제 등록 상태를 즉시 조회해 로컬 DB 값과 대조합니다. 외부 호출이라 마켓당 수 초 걸릴 수 있습니다.
      </div>
      {sorted.map((reg) => {
        const label = marketLabel(reg.marketType);
        const { bg, text } = marketColors(reg.marketType);
        const supported = LIVE_SUPPORTED_MARKETS.includes(reg.marketType);
        const loading = busy[reg.marketType] === true;
        const result = results[reg.marketType];
        const error = errors[reg.marketType];
        const rows = result ? buildRows(detail, result.local, result.live) : [];
        const diffCount = rows.filter((r) => r.state === 'diff').length;
        const comparedCount = rows.filter((r) => r.state !== 'na').length;
        return (
          <div key={reg.marketType} style={{ marginBottom: 12, border: '1px solid #eef2f7', borderRadius: 10, padding: 12, background: '#fff' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 4, background: bg, color: text }}>
                {label}
              </span>
              <span style={{ fontSize: 12, color: '#6b7280' }}>
                {reg.isSynced ? '동기화됨' : '미동기화'}
                {reg.lastSyncedAt ? ` · ${new Date(reg.lastSyncedAt).toLocaleString('ko-KR')}` : ''}
              </span>
              <span style={{ flex: 1 }} />
              <Tooltip title={supported ? '' : '이 마켓은 실시간 조회 API가 없습니다 (ESM+ 상품 조회 미지원)'}>
                <button
                  className="pd-imgbtn"
                  disabled={!supported || loading}
                  onClick={() => void check(reg.marketType)}
                  style={supported && !loading ? { borderColor: GREEN, color: GREEN } : undefined}
                >
                  {loading ? '조회 중…' : '마켓 실제값 확인'}
                </button>
              </Tooltip>
            </div>

            {error && (
              <div style={{ marginTop: 8, fontSize: 12, color: '#b91c1c', background: '#fef2f2', border: '1px solid #fee2e2', borderRadius: 6, padding: '6px 10px' }}>
                조회 실패 — {error}
              </div>
            )}

            {result && (
              <>
                <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
                  <span style={{ fontWeight: 700, color: comparedCount === 0 || diffCount > 0 ? DIFF : GREEN }}>
                    {comparedCount === 0
                      ? '마켓이 값을 주지 않아 대조할 항목이 없습니다'
                      : diffCount > 0
                        ? `다른 항목 ${diffCount}개`
                        : `대조한 ${comparedCount}개 항목 모두 일치`}
                  </span>
                  <span style={{ color: '#9ca3af' }}>조회 {result.fetchedAt}</span>
                </div>
                <CompareTable rows={rows} />
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}
