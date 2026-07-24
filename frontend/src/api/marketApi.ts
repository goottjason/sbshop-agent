import { apiClient } from './axios';

export interface MarketCredential {
  id?: number;
  marketType: string;
  clientId: string;
  redirectUri: string;
  // 시크릿(accessKey·secretKey)은 서버 응답에서 마스킹되어 내려오지 않는다(F-CRED-1·7).
  // 응답에는 '설정 여부' 플래그만 담기며, 폼 입력용으로만 optional 문자열로 유지한다.
  // 빈 값으로 저장하면 서버가 기존 시크릿을 유지한다(F-CRED-8).
  accessKey?: string;
  secretKey?: string;
  hasAccessKey?: boolean;
  hasSecretKey?: boolean;
  hasRefreshToken?: boolean;
}

export const fetchCredentials = async (): Promise<MarketCredential[]> => {
  const { data } = await apiClient.get('/api/v1/market-credentials');
  return data;
};

export const fetchCredential = async (marketType: string): Promise<MarketCredential> => {
  const { data } = await apiClient.get(`/api/v1/market-credentials/${marketType}`);
  return data;
};

export const saveCredential = async (
  marketType: string,
  credential: Partial<MarketCredential>
): Promise<MarketCredential> => {
  const { data } = await apiClient.put(`/api/v1/market-credentials/${marketType}`, credential);
  return data;
};

// Cafe24 실연동 상태(토큰 유효성 실검증) + 리프레시 토큰 발급
export interface Cafe24Status {
  connected: boolean;
  message: string;
}

export const getCafe24Status = async (): Promise<Cafe24Status> => {
  const { data } = await apiClient.get('/api/admin/sync/cafe24/status');
  return data;
};

// code 또는 code=... 를 포함한 리다이렉트 전체 URL을 그대로 보내도 서버가 code만 추출
export const issueCafe24Token = async (code: string): Promise<Cafe24Status> => {
  const { data } = await apiClient.post('/api/admin/sync/cafe24/issue-token', { code });
  return data;
};
