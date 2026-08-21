import { apiClient } from './axios';

export interface MarketCredential {
  id?: number;
  marketType: string;
  clientId: string;
  redirectUri: string;
  accessKey?: string;
  secretKey?: string;
  refreshToken?: string;
  hasAccessKey?: boolean;
  hasSecretKey?: boolean;
  hasRefreshToken?: boolean;
}

interface Cafe24Status {
  connected: boolean;
  message: string;
}

export const fetchCredentials = async (): Promise<MarketCredential[]> => {
  const { data } = await apiClient.get('/api/v1/market-credentials');
  return data;
};

export const saveCredential = async (
  marketType: string,
  credential: Partial<MarketCredential>
): Promise<MarketCredential> => {
  const { data } = await apiClient.put(`/api/v1/market-credentials/${marketType}`, credential);
  return data;
};

export const getCafe24Status = async (): Promise<Cafe24Status> => {
  const { data } = await apiClient.get('/api/admin/sync/cafe24/status');
  return data;
};

export const issueCafe24Token = async (code: string): Promise<Cafe24Status> => {
  const { data } = await apiClient.post('/api/admin/sync/cafe24/issue-token', { code });
  return data;
};
