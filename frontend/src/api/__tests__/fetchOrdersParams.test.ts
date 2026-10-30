import { describe, it, expect, vi } from 'vitest';

vi.mock('../axios', () => ({ apiClient: { get: vi.fn().mockResolvedValue({ data: {} }) } }));

import { apiClient } from '../axios';
import { fetchOrders } from '../orderApi';

describe('fetchOrders', () => {
  it('claimStages를 반복 파라미터로 보낸다', async () => {
    await fetchOrders(0, 500, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, undefined, ['RETURN'], ['REQUESTED', 'IN_PROGRESS']);
    const url = vi.mocked(apiClient.get).mock.calls[0][0] as string;
    const params = new URLSearchParams(url.split('?')[1]);
    expect(params.getAll('claimTypes')).toEqual(['RETURN']);
    expect(params.getAll('claimStages')).toEqual(['REQUESTED', 'IN_PROGRESS']);
  });
});
