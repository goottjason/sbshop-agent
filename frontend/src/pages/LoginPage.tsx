import { useState } from 'react';
import { apiClient, setAdminAuth } from '../api/axios';

interface Props {
  onSuccess: () => void;
}

const LoginPage = ({ onSuccess }: Props) => {
  const [loginId, setLoginId] = useState('');
  const [loginPw, setLoginPw] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const handleLogin = async () => {
    if (busy) return;
    setBusy(true);
    setError('');
    const token = btoa(`${loginId}:${loginPw}`);
    setAdminAuth(token);
    try {
      await apiClient.get('/api/v1/orders/sync/status');
      onSuccess();
    } catch {
      setAdminAuth(null);
      setError('로그인 실패 — 아이디 또는 비밀번호를 확인하세요.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{
      display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
      paddingTop: 48,
    }}>
      <div className="card" style={{
        width: '100%', maxWidth: 360, padding: 32,
        display: 'flex', flexDirection: 'column', gap: 16, background: '#fff',
      }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 22 }}>SBShop 관리자</h1>
          <p style={{ color: '#666', fontSize: 13, margin: '8px 0 0' }}>
            계속하려면 로그인하세요.
          </p>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <label style={{ fontWeight: 500, fontSize: 14 }}>아이디</label>
          <input type="text" value={loginId} onChange={(e) => setLoginId(e.target.value)}
            className="input-field" autoComplete="username" />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <label style={{ fontWeight: 500, fontSize: 14 }}>비밀번호</label>
          <input type="password" value={loginPw} onChange={(e) => setLoginPw(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleLogin(); }}
            className="input-field" autoComplete="current-password" />
        </div>
        {error && <div style={{ color: '#b91c1c', fontSize: 13 }}>{error}</div>}
        <button type="button" onClick={handleLogin} disabled={busy}
          className="btn-primary" style={{ marginTop: 8 }}>
          {busy ? '확인 중...' : '로그인'}
        </button>
      </div>
    </div>
  );
};

export default LoginPage;
