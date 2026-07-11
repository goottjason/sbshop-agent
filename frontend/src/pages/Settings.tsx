import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchCredentials, saveCredential, getCafe24Status, issueCafe24Token } from '../api/marketApi';
import type { MarketCredential } from '../api/marketApi';

const Settings = () => {
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState('COUPANG');
  const [formData, setFormData] = useState<Partial<MarketCredential>>({});
  const [authCode, setAuthCode] = useState('');

  // Cafe24 실연동 상태(토큰 유효성 실검증) — '존재'가 아니라 실제 API 호출 성공 여부.
  const { data: cafe24Status, isFetching: cafe24Checking, refetch: refetchCafe24Status } = useQuery({
    queryKey: ['cafe24-status'],
    queryFn: getCafe24Status,
    enabled: activeTab === 'CAFE24',
    staleTime: 0,
  });

  const issueTokenMutation = useMutation({
    mutationFn: (code: string) => issueCafe24Token(code),
    onSuccess: (res) => {
      alert(res.message);
      if (res.connected) setAuthCode('');
      refetchCafe24Status();
      queryClient.invalidateQueries({ queryKey: ['market-credentials'] });
    },
    onError: () => alert('토큰 발급 요청 중 오류가 발생했습니다.'),
  });

  const cafe24AuthUrl =
    formData.clientId && formData.accessKey && formData.redirectUri
      ? `https://${formData.clientId}.cafe24api.com/api/v2/oauth/authorize?response_type=code&client_id=${formData.accessKey}&state=shouldbeshopping&redirect_uri=${formData.redirectUri}&scope=mall.read_application,mall.write_application,mall.read_product,mall.write_product,mall.read_collection,mall.write_collection,mall.read_order,mall.write_order,mall.read_shipping,mall.write_shipping`
      : '';

  const { data: credentials, isLoading } = useQuery({
    queryKey: ['market-credentials'],
    queryFn: fetchCredentials,
  });

  useEffect(() => {
    if (credentials) {
      const cred = credentials.find((c) => c.marketType === activeTab);
      if (cred) {
        setFormData(cred);
      } else {
        setFormData({
          marketType: activeTab,
          clientId: '',
          accessKey: '',
          secretKey: '',
          redirectUri: '',
        });
      }
    }
  }, [credentials, activeTab]);

  const mutation = useMutation({
    mutationFn: (data: Partial<MarketCredential>) => saveCredential(activeTab, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['market-credentials'] });
      alert('설정이 저장되었습니다.');
    },
    onError: () => {
      alert('설정 저장 중 오류가 발생했습니다.');
    },
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    mutation.mutate(formData);
  };

  const tabs = [
    { id: 'COUPANG', label: '쿠팡 (Coupang)' },
    { id: 'SMART_STORE', label: '스마트스토어 (Naver)' },
    { id: 'ELEVEN_STREET', label: '11번가 (11st)' },
    { id: 'CAFE24', label: '카페24 (Cafe24)' },
    { id: 'GMARKET', label: 'G마켓·옥션 (ESM+ 단일 로그인)' },
  ];

  if (isLoading) return <div>로딩 중...</div>;

  return (
    <div style={{ maxWidth: '800px' }}>
      <h1 style={{ marginBottom: '24px' }}>설정 및 연동</h1>

      <div style={{ display: 'flex', gap: '12px', marginBottom: '24px' }}>
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            style={{
              padding: '10px 20px',
              borderRadius: '6px',
              border: 'none',
              cursor: 'pointer',
              fontWeight: 600,
              backgroundColor: activeTab === tab.id ? '#000' : '#e5e7eb',
              color: activeTab === tab.id ? '#fff' : '#333',
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="card" style={{ padding: '32px' }}>
        <h2 style={{ marginBottom: '24px' }}>{tabs.find((t) => t.id === activeTab)?.label} API 설정</h2>
        
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          
          {activeTab === 'COUPANG' && (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Vendor ID</label>
                <input
                  type="text"
                  name="clientId"
                  value={formData.clientId || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: A00213055"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Access Key</label>
                <input
                  type="text"
                  name="accessKey"
                  value={formData.accessKey || ''}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Secret Key</label>
                <input
                  type="password"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
            </>
          )}

          {activeTab === 'SMART_STORE' && (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Client ID</label>
                <input
                  type="text"
                  name="clientId"
                  value={formData.clientId || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: 1l5fRuKFzyNJGQF3AP27AE"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Client Secret</label>
                <input
                  type="password"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
            </>
          )}

          {activeTab === 'ELEVEN_STREET' && (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>API Key (OpenAPI)</label>
                <input
                  type="password"
                  name="accessKey"
                  value={formData.accessKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: b7ac38eb89852b178b17a6a73da0b0c2"
                />
              </div>
            </>
          )}

          {activeTab === 'CAFE24' && (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Mall ID</label>
                <input
                  type="text"
                  name="clientId"
                  value={formData.clientId || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: younzara"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Client ID</label>
                <input
                  type="text"
                  name="accessKey"
                  value={formData.accessKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: r0Z9nXoDDNfOrf5F6wYzTA"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Client Secret</label>
                <input
                  type="password"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Redirect URI</label>
                <input
                  type="text"
                  name="redirectUri"
                  value={formData.redirectUri || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: https://younzara.cafe24.com/"
                />
              </div>

              {/* 실연동 상태 — 토큰의 '존재'가 아니라 실제 API 호출로 검증한 결과 */}
              <div
                style={{
                  marginTop: '12px', padding: '12px', borderRadius: '6px',
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
                  backgroundColor: cafe24Checking ? '#f3f4f6' : cafe24Status?.connected ? '#ecfdf5' : '#fef2f2',
                  color: cafe24Checking ? '#374151' : cafe24Status?.connected ? '#065f46' : '#991b1b',
                }}
              >
                <span>
                  {cafe24Checking
                    ? '⏳ 연동 상태 확인 중…'
                    : cafe24Status?.connected
                      ? `✅ ${cafe24Status.message}`
                      : `🚨 ${cafe24Status?.message || '연동 상태를 확인할 수 없습니다.'}`}
                </span>
                <button type="button" onClick={() => refetchCafe24Status()} className="btn-primary"
                  style={{ padding: '6px 12px', fontSize: 13, whiteSpace: 'nowrap' }}>
                  상태 새로고침
                </button>
              </div>

              {/* 재인증 카드 — 상태가 '정상 아님'(토큰 무효 또는 주문 권한 없음)일 때 노출.
                  상태 점검이 상품·주문 권한을 모두 검사하므로, 주문 권한만 없어도 카드가 뜬다. */}
              {!cafe24Status?.connected && !cafe24Checking && (
                <div style={{ marginTop: '12px', padding: '16px', backgroundColor: '#fffbeb', border: '1px solid #fde68a', borderRadius: '8px' }}>
                  <div style={{ fontWeight: 600, marginBottom: 12, color: '#92400e' }}>리프레시 토큰 발급 (재인증 / 권한 갱신)</div>
                  <div style={{ fontSize: 13, color: '#92400e', marginBottom: 12 }}>
                    아래 순서로 재인증하면 필요한 권한(상품·주문 조회)이 모두 부여됩니다.
                  </div>
                  {!cafe24AuthUrl ? (
                    <div style={{ color: '#b91c1c', fontWeight: 500 }}>
                      ⚠️ Mall ID · Client ID · Redirect URI를 먼저 입력·저장해주세요.
                    </div>
                  ) : (
                    <>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                        <div style={{ fontSize: 13, color: '#666' }}>
                          <b>①</b> 아래 버튼으로 Cafe24 인증 페이지를 열어 승인하면
                          <code style={{ margin: '0 4px' }}>{formData.redirectUri}?code=...</code>
                          형태로 이동합니다. 그 주소(또는 code 값)를 복사해 <b>②</b>에 붙여넣고 발급하세요.
                        </div>
                        <div>
                          <a href={cafe24AuthUrl} target="_blank" rel="noopener noreferrer" className="btn-primary"
                            style={{ display: 'inline-block', textDecoration: 'none' }}>
                            ① Cafe24 인증 페이지 열기
                          </a>
                        </div>
                        <input
                          type="text"
                          value={authCode}
                          onChange={(e) => setAuthCode(e.target.value)}
                          className="input-field"
                          placeholder="② 리다이렉트된 주소 전체 또는 code 값을 붙여넣기"
                        />
                        <div>
                          <button
                            type="button"
                            className="btn-primary"
                            disabled={!authCode.trim() || issueTokenMutation.isPending}
                            onClick={() => issueTokenMutation.mutate(authCode.trim())}
                          >
                            {issueTokenMutation.isPending ? '발급 중…' : '② 리프레시 토큰 발급받기'}
                          </button>
                          <span style={{ marginLeft: 10, fontSize: 12, color: '#b45309' }}>
                            ※ 인증 코드는 1회용·단시간 유효 — 승인 직후 바로 발급하세요.
                          </span>
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}
            </>
          )}

          {activeTab === 'GMARKET' && (
            <>
              <div style={{ padding: '16px', backgroundColor: '#f8f9fa', borderRadius: '8px', marginBottom: '16px' }}>
                <p style={{ margin: 0, fontSize: '14px', color: '#666' }}>
                  G마켓과 옥션은 ESM+(ESM Plus) 단일 계정으로 통합 로그인됩니다.<br />
                  여기 한 곳에만 마스터 ID/비밀번호를 입력하면 G마켓·옥션 주문이 모두 수집됩니다(옥션 별도 입력 불필요).
                </p>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>마스터 ID</label>
                <input
                  type="text"
                  name="accessKey"
                  value={formData.accessKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder="예: shouldbeshop"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>비밀번호</label>
                <input
                  type="password"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                />
              </div>
            </>
          )}

          <div style={{ marginTop: '24px', display: 'flex', justifyContent: 'flex-end' }}>
            <button
              type="button"
              onClick={(e) => {
                e.preventDefault();
                mutation.mutate(formData);
              }}
              className="btn-primary"
              disabled={mutation.isPending}
            >
              {mutation.isPending ? '저장 중...' : '저장하기'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default Settings;
