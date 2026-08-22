import axios from 'axios';

const ADMIN_AUTH_KEY = 'sbshop.adminAuth';

export const apiClient = axios.create({
  baseURL: '/sbshop-agent',
  headers: {
    'Content-Type': 'application/json',
  },
  paramsSerializer: { indexes: null },
});

export const setAdminAuth = (token: string | null) => {
  if (token) sessionStorage.setItem(ADMIN_AUTH_KEY, token);
  else sessionStorage.removeItem(ADMIN_AUTH_KEY);
};

export const getAdminAuth = (): string | null => sessionStorage.getItem(ADMIN_AUTH_KEY);

apiClient.interceptors.request.use((config) => {
  const token = getAdminAuth();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Basic ${token}`;
  }
  return config;
});
