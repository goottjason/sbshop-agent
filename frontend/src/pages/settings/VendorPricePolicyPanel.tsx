import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchVendorPricePolicies,
  saveVendorPricePolicy,
  type VendorPricePolicy,
} from '../../api/vendorPricePolicyApi';

type Draft = Record<string, string>;

const FIELDS: { key: keyof Omit<VendorPricePolicy, 'vendor' | 'shipCurrency'>; label: string; hint?: string }[] = [
  { key: 'marginRate', label: '마진율 (%)' },
  { key: 'couponRate', label: '쿠폰율 (%)', hint: '매입가를 깎는 비율. 정가로 사는 소싱처는 0' },
  { key: 'minMarginPrice', label: '최소 마진가 (원)' },
];

const SHIP_FIELDS: { key: keyof VendorPricePolicy; label: string }[] = [
  { key: 'shipBaseAmount', label: '기초 배송비' },
  { key: 'shipBaseWeightG', label: '기초 무게 (g)' },
  { key: 'shipStepAmount', label: '추가 배송비' },
  { key: 'shipStepWeightG', label: '추가 무게 (g)' },
];

const DOMESTIC_FIELDS: { key: keyof VendorPricePolicy; label: string }[] = [
  { key: 'domesticFee', label: '국내 배송비 (원)' },
  { key: 'domesticFreeOver', label: '무료 기준액 (원)' },
];

const CURRENCIES = ['GBP', 'USD', 'KRW', 'EUR', 'JPY'];

const toNumber = (value: string): number | null => {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
};

const asText = (value: number | null | undefined): string =>
  value === null || value === undefined ? '' : String(value);

const fieldStyle = { display: 'flex', flexDirection: 'column' as const, gap: 6, minWidth: 150, flex: '1 1 150px' };

const VendorPricePolicyPanel = () => {
  const queryClient = useQueryClient();
  const [vendor, setVendor] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const { data: policies = [], isFetching } = useQuery<VendorPricePolicy[]>({
    queryKey: ['vendorPricePolicies'],
    queryFn: fetchVendorPricePolicies,
  });

  const activeVendor = vendor ?? policies[0]?.vendor ?? null;
  const active = policies.find((p) => p.vendor === activeVendor) ?? null;

  const form: Draft = draft ?? {
    marginRate: asText(active?.marginRate),
    couponRate: asText(active?.couponRate),
    minMarginPrice: asText(active?.minMarginPrice),
    shipCurrency: active?.shipCurrency ?? 'GBP',
    shipBaseAmount: asText(active?.shipBaseAmount),
    shipBaseWeightG: asText(active?.shipBaseWeightG),
    shipStepAmount: asText(active?.shipStepAmount),
    shipStepWeightG: asText(active?.shipStepWeightG),
    domesticFee: asText(active?.domesticFee),
    domesticFreeOver: asText(active?.domesticFreeOver),
  };

  const selectVendor = (next: string) => {
    setVendor(next);
    setDraft(null);
    setSaved(null);
  };

  const change = (key: string, value: string) => {
    setDraft({ ...form, [key]: value });
    setSaved(null);
  };

  const mutation = useMutation({
    mutationFn: () => {
      if (!activeVendor) throw new Error('소싱처가 선택되지 않았습니다.');
      return saveVendorPricePolicy(activeVendor, {
        marginRate: toNumber(form.marginRate),
        couponRate: toNumber(form.couponRate),
        minMarginPrice: toNumber(form.minMarginPrice),
        shipCurrency: form.shipCurrency || null,
        shipBaseAmount: toNumber(form.shipBaseAmount),
        shipBaseWeightG: toNumber(form.shipBaseWeightG),
        shipStepAmount: toNumber(form.shipStepAmount),
        shipStepWeightG: toNumber(form.shipStepWeightG),
        domesticFee: toNumber(form.domesticFee),
        domesticFreeOver: toNumber(form.domesticFreeOver),
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['vendorPricePolicies'] });
      setDraft(null);
      setSaved(`${activeVendor} 정책을 저장했습니다.`);
    },
    onError: () => setSaved('저장 중 오류가 발생했습니다.'),
  });

  return (
    <div className="card" style={{ padding: '32px' }}>
      <h2 style={{ marginBottom: '8px' }}>소싱처별 가격 정책</h2>
      <p style={{ color: '#666', fontSize: 14, marginTop: 0, marginBottom: '24px' }}>
        판매가는 소싱처가 결정합니다 — 매입 조건과 배송비가 소싱처마다 다르기 때문입니다.
        마켓은 수수료만 결정하며 그쪽은 별도로 관리됩니다.
      </p>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 24 }}>
        {policies.map((p) => (
          <button
            key={p.vendor}
            type="button"
            onClick={() => selectVendor(p.vendor)}
            style={{
              padding: '8px 18px',
              borderRadius: 6,
              border: '1px solid ' + (p.vendor === activeVendor ? '#166534' : '#d1d5db'),
              background: p.vendor === activeVendor ? '#166534' : '#fff',
              color: p.vendor === activeVendor ? '#fff' : '#374151',
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            {p.vendor}
          </button>
        ))}
        {policies.length === 0 && !isFetching && (
          <span style={{ color: '#666', fontSize: 14 }}>등록된 소싱처 정책이 없습니다.</span>
        )}
      </div>

      {active && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 28 }}>
          <div>
            <h3 style={{ fontSize: 15, margin: '0 0 12px' }}>매입·마진</h3>
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              {FIELDS.map((f) => (
                <div key={f.key} style={fieldStyle}>
                  <label style={{ fontWeight: 500, fontSize: 14 }}>{f.label}</label>
                  <input
                    type="number"
                    value={form[f.key]}
                    onChange={(e) => change(f.key, e.target.value)}
                    className="input-field"
                  />
                  {f.hint && <span style={{ fontSize: 12, color: '#9ca3af' }}>{f.hint}</span>}
                </div>
              ))}
            </div>
          </div>

          <div>
            <h3 style={{ fontSize: 15, margin: '0 0 12px' }}>해외 배송비</h3>
            <p style={{ fontSize: 13, color: '#6b7280', margin: '0 0 12px' }}>
              기초 무게까지는 기초 배송비, 이후 추가 무게마다 추가 배송비가 붙습니다.
              해외 배송비가 없는 소싱처는 기초 배송비를 0으로 두세요.
            </p>
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              <div style={fieldStyle}>
                <label style={{ fontWeight: 500, fontSize: 14 }}>통화</label>
                <select
                  value={form.shipCurrency}
                  onChange={(e) => change('shipCurrency', e.target.value)}
                  className="input-field"
                >
                  {CURRENCIES.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </div>
              {SHIP_FIELDS.map((f) => (
                <div key={f.key} style={fieldStyle}>
                  <label style={{ fontWeight: 500, fontSize: 14 }}>{f.label}</label>
                  <input
                    type="number"
                    value={form[f.key]}
                    onChange={(e) => change(f.key, e.target.value)}
                    className="input-field"
                  />
                </div>
              ))}
            </div>
          </div>

          <div>
            <h3 style={{ fontSize: 15, margin: '0 0 12px' }}>국내 배송비</h3>
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
              {DOMESTIC_FIELDS.map((f) => (
                <div key={f.key} style={fieldStyle}>
                  <label style={{ fontWeight: 500, fontSize: 14 }}>{f.label}</label>
                  <input
                    type="number"
                    value={form[f.key]}
                    onChange={(e) => change(f.key, e.target.value)}
                    className="input-field"
                  />
                </div>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 12 }}>
            {saved && <span style={{ fontSize: 13, color: '#166534' }}>{saved}</span>}
            <button
              type="button"
              onClick={() => mutation.mutate()}
              className="btn-primary"
              disabled={mutation.isPending}
            >
              {mutation.isPending ? '저장 중...' : `${activeVendor} 정책 저장`}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default VendorPricePolicyPanel;
