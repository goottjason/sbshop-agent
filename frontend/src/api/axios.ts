import axios from 'axios';

export const apiClient = axios.create({
  baseURL: '/sbshop-agent',
  headers: {
    'Content-Type': 'application/json',
  },
});
