import axios from 'axios';

export const apiClient = axios.create({
  baseURL: '/sbshop-agent/api',
  headers: {
    'Content-Type': 'application/json',
  },
});
