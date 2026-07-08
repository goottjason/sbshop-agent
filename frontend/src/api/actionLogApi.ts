import { apiClient } from './axios';

export interface ActionLogItem {
  id: number;
  actionType: string;
  marketType: string | null;
  actionStatus: 'STARTED' | 'SUCCESS' | 'FAILED';
  message: string;
  createdAt: string;
}

export const actionLogApi = {
  // 최근 액션 로그 조회 (기본 100건)
  getActionLogs: (limit = 100) =>
    apiClient.get<ActionLogItem[]>('/api/v1/action-logs', { params: { limit } }),
};
