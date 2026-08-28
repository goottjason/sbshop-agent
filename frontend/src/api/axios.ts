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

let authExpiredHandler: (() => void) | null = null;

export const logout = () => {
  setAdminAuth(null);
  authExpiredHandler?.();
};

export const onAuthExpired = (handler: () => void) => {
  authExpiredHandler = handler;
  return () => {
    if (authExpiredHandler === handler) authExpiredHandler = null;
  };
};

apiClient.interceptors.request.use((config) => {
  const token = getAdminAuth();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Basic ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      setAdminAuth(null);
      authExpiredHandler?.();
    }
    return Promise.reject(error);
  },
);
