import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse } from 'axios';
import DiscoveryPage from '../DiscoveryPage';
import { sourcingDiscoveryApi, type Candidate } from '../../../api/sourcingDiscoveryApi';
import { notify } from '../../../utils/notify';

vi.mock('../../../api/sourcingDiscoveryApi', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../../api/sourcingDiscoveryApi')>(),
  sourcingDiscoveryApi: { candidates: vi.fn(), discoveryStatus: vi.fn(), reject: vi.fn() },
}));
vi.mock('../../../utils/notify', () => ({ notify: {
  success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
} }));
const response = <T,>(data: T) => ({ data }) as AxiosResponse<T>;

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() })));
  vi.mocked(sourcingDiscoveryApi.candidates).mockResolvedValue(response([{ id: 1, nameKo: '추천 테스트 상품', customsVerdict: 'PASS' } as Candidate]));
  vi.mocked(sourcingDiscoveryApi.discoveryStatus).mockResolvedValue(response({ running: false, lastRun: {} }));
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

async function renderPage() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <MemoryRouter><DiscoveryPage /></MemoryRouter>
  </QueryClientProvider>);
  await screen.findByText('추천 테스트 상품');
}

describe('상품 추천 실패 표시', () => {
  it('발굴 상태 조회가 실패하면 발굴 완료로 알리지 않는다', async () => {
    vi.mocked(sourcingDiscoveryApi.discoveryStatus)
      .mockResolvedValueOnce(response({ running: true, lastRun: {} }))
      .mockRejectedValue(new Error('Network failure'));
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    await renderPage();
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
    expect(sourcingDiscoveryApi.discoveryStatus).toHaveBeenCalledTimes(2);
    expect(notify.success).not.toHaveBeenCalled();
    expect(notify.error).toHaveBeenCalled();
  });

  it('추천 거절이 실패하면 사유를 알리고 후보를 유지한다', async () => {
    vi.mocked(sourcingDiscoveryApi.reject).mockRejectedValue(new Error('Network failure'));
    await renderPage();
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    await waitFor(() => expect(notify.error).toHaveBeenCalled());
    expect(screen.getByText('추천 테스트 상품')).toBeInTheDocument();
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('발굴 실행이 수집 0건과 경고로 끝나면 정상 완료로 알리지 않는다', async () => {
    vi.mocked(sourcingDiscoveryApi.discoveryStatus)
      .mockResolvedValueOnce(response({ running: true, lastRun: {} }))
      .mockResolvedValue(response({ running: false, lastRun: { crawled: 0, warnings: ['수집 요청 시간 초과'] } }) as never);
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    await renderPage();
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
    expect(notify.success).not.toHaveBeenCalled();
    expect(notify.warning).toHaveBeenCalledWith(expect.stringContaining('수집 요청 시간 초과'));
  });
});
