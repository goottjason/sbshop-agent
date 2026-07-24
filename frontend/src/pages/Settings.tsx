import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchCredentials, saveCredential, getCafe24Status, issueCafe24Token } from '../api/marketApi';
import type { MarketCredential } from '../api/marketApi';
import { getAdminAuth, setAdminAuth } from '../api/axios';

// 시크릿 입력칸 플레이스홀더: 서버에 이미 저장돼 있으면(hasXxx=true) '설정됨' 안내를 보이고,
// 비운 채 저장하면 기존 값이 유지된다(F-CRED-8). 미설정이면 기본 안내(예시 등)를 사용한다.
const secretPlaceholder = (hasValue?: boolean, fallback = ''): string =>
  hasValue ? '설정됨 — 변경하려면 새 값 입력 (비우면 기존 값 유지)' : fallback;

const Settings = () => {
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState('COUPANG');
  const [formData, setFormData] = useState<Partial<MarketCredential>>({});
  const [authCode, setAuthCode] = useState('');

  // 관리자 인증 게이트 — 시크릿(access/secret/refresh)은 인증된 관리자만 조회·수정할 수 있다.
  // 인증 성공 시 base64("id:pw")를 sessionStorage에 저장하고, axios 인터셉터가 요청에 실어보낸다.
  const [authed, setAuthed] = useState<boolean>(!!getAdminAuth());
  const [loginId, setLoginId] = useState('admin');
  const [loginPw, setLoginPw] = useState('');
  const [loginErr, setLoginErr] = useState('');

  const handleLogin = async () => {
    const token = btoa(`${loginId}:${loginPw}`);
    setAdminAuth(token);
    try {
      await fetchCredentials(); // 인터셉터가 토큰 첨부 — 성공하면 인증 통과
      setAuthed(true);
      setLoginErr('');
      queryClient.invalidateQueries({ queryKey: ['market-credentials'] });
    } catch {
      setAdminAuth(null);
      setLoginErr('관리자 인증 실패 — 아이디/비밀번호를 확인하세요.');
    }
  };

  const handleLogout = () => {
    setAdminAuth(null);
    setAuthed(false);
    setLoginPw('');
    queryClient.removeQueries({ queryKey: ['market-credentials'] });
  };

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
    enabled: authed, // 인증 전에는 조회하지 않음(401 방지)
    retry: false,
  });

  useEffect(() => {
    if (credentials) {
      const cred = credentials.find((c) => c.marketType === activeTab);
      if (cred) {
        // 인증된 관리자에게는 서버가 시크릿 평문을 내려주므로 저장값을 그대로 표시한다.
        // 비운 채 저장하면 서버가 기존 값을 유지한다(F-CRED-8).
        setFormData({ ...cred });
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
    { id: 'SMART_STORE', label: 'N스토어 (Naver)' },
    { id: 'ELEVEN_STREET', label: '11번가 (11st)' },
    { id: 'CAFE24', label: '카페24 (Cafe24)' },
  ];

  // 관리자 인증 게이트: 인증 전에는 로그인 폼만 노출(시크릿 조회 차단).
  if (!authed) {
    return (
      <div style={{ maxWidth: '420px' }}>
        <h1 style={{ marginBottom: '24px' }}>설정 및 연동</h1>
        <div className="card" style={{ padding: '32px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <h2 style={{ margin: 0 }}>관리자 인증</h2>
          <p style={{ color: '#666', fontSize: 14, margin: 0 }}>
            마켓 API 키(시크릿)를 조회·수정하려면 관리자 로그인이 필요합니다.
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <label style={{ fontWeight: 500 }}>아이디</label>
            <input type="text" value={loginId} onChange={(e) => setLoginId(e.target.value)}
              className="input-field" autoComplete="username" />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <label style={{ fontWeight: 500 }}>비밀번호</label>
            <input type="password" value={loginPw} onChange={(e) => setLoginPw(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleLogin(); }}
              className="input-field" autoComplete="current-password" />
          </div>
          {loginErr && <div style={{ color: '#b91c1c', fontSize: 13 }}>{loginErr}</div>}
          <button type="button" onClick={handleLogin} className="btn-primary" style={{ marginTop: 8 }}>
            로그인
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) return <div>로딩 중...</div>;

  return (
    <div style={{ maxWidth: '800px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
        <h1 style={{ margin: 0 }}>설정 및 연동</h1>
        <button type="button" onClick={handleLogout}
          style={{ padding: '6px 14px', fontSize: 13, borderRadius: 6, border: '1px solid #d1d5db', background: '#fff', cursor: 'pointer' }}>
          로그아웃
        </button>
      </div>

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
                  placeholder={secretPlaceholder(formData.hasAccessKey)}
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Secret Key</label>
                <input
                  type="text"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder={secretPlaceholder(formData.hasSecretKey)}
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
                  type="text"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder={secretPlaceholder(formData.hasSecretKey)}
                />
              </div>
            </>
          )}

          {activeTab === 'ELEVEN_STREET' && (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>API Key (OpenAPI)</label>
                <input
                  type="text"
                  name="accessKey"
                  value={formData.accessKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder={secretPlaceholder(formData.hasAccessKey, '예: b7ac38eb89852b178b17a6a73da0b0c2')}
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
                  placeholder={secretPlaceholder(formData.hasAccessKey, '예: r0Z9nXoDDNfOrf5F6wYzTA')}
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                <label style={{ fontWeight: 500 }}>Client Secret</label>
                <input
                  type="text"
                  name="secretKey"
                  value={formData.secretKey || ''}
                  onChange={handleChange}
                  className="input-field"
                  placeholder={secretPlaceholder(formData.hasSecretKey)}
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
