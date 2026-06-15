import { apiClient } from './axios';

export interface MarketCredential {
  id?: number;
  marketType: string;
  clientId: string;
  accessKey: string;
  secretKey: string;
  redirectUri: string;
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
