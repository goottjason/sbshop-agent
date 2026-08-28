import { apiClient } from './axios';

export interface ActionLogItem {
  id: number;
  actionType: string;
  marketType: string | null;
  actionStatus: 'STARTED' | 'SUCCESS' | 'FAILED' | 'WARNING';
  message: string;
  createdAt: string;
}

export const actionLogApi = {
  getActionLogs: (limit = 100) =>
    apiClient.get<ActionLogItem[]>('/api/v1/action-logs', { params: { limit } }),
};
