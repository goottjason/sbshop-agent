import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchCredentials, saveCredential } from '../api/marketApi';
import type { MarketCredential } from '../api/marketApi';

const Settings = () => {
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState('COUPANG');
  const [formData, setFormData] = useState<Partial<MarketCredential>>({});

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
    { id: 'GMARKET', label: 'G마켓 (Gmarket)' },
    { id: 'AUCTION', label: '옥션 (Auction)' },
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

              {formData.hasRefreshToken && (
                <div style={{ marginTop: '12px', padding: '12px', backgroundColor: '#ecfdf5', color: '#065f46', borderRadius: '6px' }}>
                  ✅ 현재 유효한 리프레시 토큰이 등록되어 있습니다. 정상적으로 연동 중입니다.
                </div>
              )}
              {!formData.hasRefreshToken && formData.clientId && formData.accessKey && (
                <div style={{ marginTop: '12px', padding: '12px', backgroundColor: '#fef2f2', color: '#991b1b', borderRadius: '6px' }}>
                  🚨 OAuth 인증이 필요합니다. 아래 버튼을 눌러 권한을 승인한 뒤, 반환되는 주소의 `code` 값을 서버 콜백 URL로 보내주세요.
                  <div style={{ marginTop: '12px' }}>
                    {formData.redirectUri ? (
                      <a
                        href={`https://${formData.clientId}.cafe24api.com/api/v2/oauth/authorize?response_type=code&client_id=${formData.accessKey}&state=shouldbeshopping&redirect_uri=${formData.redirectUri}&scope=mall.read_product,mall.write_product`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="btn-primary"
                        style={{ display: 'inline-block', textDecoration: 'none' }}
                      >
                        Cafe24 인증 페이지로 이동
                      </a>
                    ) : (
                      <div style={{ color: '#b91c1c', fontWeight: 500 }}>
                        ⚠️ 위 폼에서 Redirect URI를 먼저 입력해주세요 (예: https://younzara.cafe24.com/)
                      </div>
                    )}
                  </div>
                  <div style={{ marginTop: '12px', fontSize: '13px', color: '#666' }}>
                    인증 후 주소창이 <code>{formData.redirectUri || 'https://younzara.cafe24.com/'}?code=인증코드...</code> 형태로 바뀝니다.<br />
                    이때 받은 인증코드를 복사하여, 백엔드 콜백 엔드포인트<br/>
                    <code>{window.location.origin}/sbshop-agent/api/admin/sync/cafe24/auth/callback?code=복사한코드</code> 를 브라우저 주소창에 직접 입력하시면 서버에 영구 토큰이 저장됩니다.
                  </div>
                </div>
              )}
            </>
          )}

          {activeTab === 'GMARKET' && (
            <>
              <div style={{ padding: '16px', backgroundColor: '#f8f9fa', borderRadius: '8px', marginBottom: '16px' }}>
                <p style={{ margin: 0, fontSize: '14px', color: '#666' }}>
                  G마켓/옥션은 ESM+(ESM Plus) 플랫폼을 통해 통합 관리됩니다.<br />
                  마스터 ID와 비밀번호를 입력하면 Selenium을 통해 자동으로 주문을 수집합니다.
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

          {activeTab === 'AUCTION' && (
            <>
              <div style={{ padding: '16px', backgroundColor: '#f8f9fa', borderRadius: '8px', marginBottom: '16px' }}>
                <p style={{ margin: 0, fontSize: '14px', color: '#666' }}>
                  옥션도 G마켓과 동일한 ESM+(ESM Plus) 플랫폼을 사용합니다.<br />
                  G마켓과 동일한 마스터 ID/비밀번호를 입력하세요.
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
