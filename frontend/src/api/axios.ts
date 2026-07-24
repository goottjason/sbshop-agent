import axios from 'axios';

// 관리자 HTTP Basic 자격증명 저장 키(sessionStorage). 로그인 시 base64("id:pw")를 저장하고,
// 요청 인터셉터가 있으면 Authorization 헤더로 실어보낸다. 시크릿 보호 엔드포인트
// (/api/v1/market-credentials/**)만 인증을 요구하며, 나머지 요청은 헤더가 있어도 무시된다.
export const ADMIN_AUTH_KEY = 'sbshop.adminAuth';
export const setAdminAuth = (token: string | null) => {
  if (token) sessionStorage.setItem(ADMIN_AUTH_KEY, token);
  else sessionStorage.removeItem(ADMIN_AUTH_KEY);
};
export const getAdminAuth = (): string | null => sessionStorage.getItem(ADMIN_AUTH_KEY);

export const apiClient = axios.create({
  baseURL: '/sbshop-agent',
  headers: {
    'Content-Type': 'application/json',
  },
});

apiClient.interceptors.request.use((config) => {
  const token = getAdminAuth();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Basic ${token}`;
  }
  return config;
});
