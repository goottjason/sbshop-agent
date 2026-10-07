import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse } from 'axios';
import DraftReviewPage from '../DraftReviewPage';
import { sourcingDiscoveryApi, type Draft } from '../../../api/sourcingDiscoveryApi';
import { notify } from '../../../utils/notify';

vi.mock('../../../utils/notify', () => ({ notify: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));

vi.mock('../../../api/sourcingDiscoveryApi', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../../api/sourcingDiscoveryApi')>(),
  sourcingDiscoveryApi: { draft: vi.fn(), updateDraft: vi.fn(), publishDraft: vi.fn() },
}));

const draft = { id: 12, draftStatus: 'READY', baseNameKo: '테스트 초안', originalName: 'Test', bundleQty: 1,
  customsAck: true, marketDrafts: [{ id: 1, marketType: 'COUPANG', productName: '테스트 상품', valid: true,
    enabled: true, missingFields: '[]', keywords: '[]' }] } as Draft;

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() })));
});
afterEach(() => vi.unstubAllGlobals());

async function renderDraft(value: Draft) {
  vi.mocked(sourcingDiscoveryApi.draft).mockResolvedValue({ data: value } as AxiosResponse<Draft>);
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <MemoryRouter initialEntries={['/sourcing/drafts/12']}><Routes>
      <Route path="/sourcing/drafts/:id" element={<DraftReviewPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider>);
  await screen.findByDisplayValue('테스트 초안');
}

describe('추천상품 초안 등록 대상', () => {
  it('등록 대상을 모두 해제하면 저장 전에도 등록 버튼이 비활성화된다', async () => {
    await renderDraft(draft);
    fireEvent.click(screen.getByRole('checkbox', { name: '이 마켓에 등록' }));
    expect(screen.getByRole('button', { name: /0개 마켓 등록/ })).toBeDisabled();
  });

  it('미선택 마켓을 선택하면 저장 전에도 등록 대상 수와 버튼이 갱신된다', async () => {
    await renderDraft({ ...draft, marketDrafts: draft.marketDrafts.map(m => ({ ...m, enabled: false })) });
    fireEvent.click(screen.getByRole('checkbox', { name: '이 마켓에 등록' }));
    expect(screen.getByRole('button', { name: /1개 마켓 등록/ })).toBeEnabled();
  });

  it.each(['PUBLISHED', 'PUBLISHING'])('%s 초안을 다시 등록할 수 없다', async (draftStatus) => {
    await renderDraft({ ...draft, draftStatus });
    expect(screen.getByRole('button', { name: /마켓 등록/ })).toBeDisabled();
    expect(screen.getByDisplayValue('테스트 초안')).toBeDisabled();
  });

  it('기존 상품을 재사용하는 실패 초안은 공통정보를 잠그고 미등록 마켓은 편집할 수 있다', async () => {
    await renderDraft({ ...draft, draftStatus: 'FAILED', productId: 41, origin: 'USA', marginRate: 20 });
    expect(screen.getByDisplayValue('테스트 초안')).toBeDisabled();
    expect(screen.getByDisplayValue('USA')).toBeDisabled();
    expect(screen.getByDisplayValue('1')).toBeDisabled();
    expect(screen.getByDisplayValue('20')).toBeDisabled();
    expect(screen.getByDisplayValue('테스트 상품')).toBeEnabled();
    expect(screen.getByText(/공통 정보는 상품관리에서 수정/)).toBeInTheDocument();
  });

  it('기존 상품 재시도 전 저장에서 공통값을 보내지 않고 미등록 마켓 변경은 보낸다', async () => {
    const linked = { ...draft, draftStatus: 'FAILED', productId: 41 };
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockResolvedValue({ data: linked } as AxiosResponse<Draft>);
    await renderDraft(linked);
    fireEvent.change(screen.getByDisplayValue('테스트 상품'), { target: { value: '재검수 이름' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(sourcingDiscoveryApi.updateDraft).toHaveBeenCalled());
    const patch = vi.mocked(sourcingDiscoveryApi.updateDraft).mock.calls[0][1];
    for (const key of ['baseNameKo', 'bundleQty', 'marginRate', 'origin']) {
      expect(patch).not.toHaveProperty(key);
    }
    expect(patch.marketDrafts?.[0].productName).toBe('재검수 이름');
  });

  it('이미 성공한 마켓의 이름과 가격은 잠그고 재시도 대상 선택은 유지한다', async () => {
    const linked = { ...draft, draftStatus: 'FAILED', productId: 41,
      marketDrafts: draft.marketDrafts.map(m => ({ ...m, salePrice: 10000, marketIdentifiers: '{"sellerProductId":"123"}' })) };
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockResolvedValue({ data: linked } as AxiosResponse<Draft>);
    await renderDraft(linked);
    expect(screen.getByDisplayValue('테스트 상품')).toBeDisabled();
    expect(screen.getByDisplayValue('10,000')).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: '이 마켓에 등록' })).toBeEnabled();
    expect(screen.getByText(/이미 등록된 마켓의 이름과 가격/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: '이 마켓에 등록' }));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(sourcingDiscoveryApi.updateDraft).toHaveBeenCalled());
    expect(vi.mocked(sourcingDiscoveryApi.updateDraft).mock.calls[0][1].marketDrafts).toEqual([
      { marketType: 'COUPANG', enabled: false },
    ]);
  });

  it('서버가 현재 등록 상태 확인을 요구하면 저장 성공 대신 구체적 사유를 표시한다', async () => {
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockRejectedValue({ response: { data: { message: '현재 마켓 연결 상태를 확인하세요' } } });
    await renderDraft({ ...draft, draftStatus: 'FAILED', productId: 41 });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(notify.error).toHaveBeenCalledWith('현재 마켓 연결 상태를 확인하세요'));
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('등록 응답을 받으면 결과 화면에서 편집과 중복 등록 동작을 제거한다', async () => {
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockResolvedValue({ data: draft } as AxiosResponse<Draft>);
    vi.mocked(sourcingDiscoveryApi.publishDraft).mockResolvedValue({ data: {
      draftId: 12, productId: 41, sbCode: '261007IHB001', totalCount: 1, successCount: 1,
      outcomes: [{ marketType: 'COUPANG', ok: true, identifiers: '{}', error: null }],
    } } as Awaited<ReturnType<typeof sourcingDiscoveryApi.publishDraft>>);
    await renderDraft(draft);
    fireEvent.click(screen.getByRole('button', { name: /1개 마켓 등록/ }));
    await screen.findByText('마켓 등록 1/1 성공');
    expect(screen.queryByRole('button', { name: /1개 마켓 등록/ })).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue('테스트 초안')).not.toBeInTheDocument();
    expect(sourcingDiscoveryApi.publishDraft).toHaveBeenCalledTimes(1);
  });

  it('등록 요청 오류 후 현재 초안을 다시 읽어 생성된 상품의 공통정보를 즉시 잠근다', async () => {
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockResolvedValue({ data: draft } as AxiosResponse<Draft>);
    vi.mocked(sourcingDiscoveryApi.publishDraft).mockRejectedValue({ response: { data: { message: '마켓 처리 실패' } } });
    await renderDraft(draft);
    vi.mocked(sourcingDiscoveryApi.draft).mockResolvedValue({ data: { ...draft, draftStatus: 'FAILED', productId: 41 } } as AxiosResponse<Draft>);
    fireEvent.click(screen.getByRole('button', { name: /1개 마켓 등록/ }));
    await waitFor(() => expect(sourcingDiscoveryApi.draft).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByDisplayValue('테스트 초안')).toBeDisabled());
    expect(screen.getByDisplayValue('테스트 상품')).toBeEnabled();
    expect(notify.error).toHaveBeenCalledWith('마켓 처리 실패');
  });

  it('등록 오류 뒤 현재 초안도 읽지 못하면 편집과 재등록을 잠그고 재조회를 안내한다', async () => {
    vi.mocked(sourcingDiscoveryApi.updateDraft).mockResolvedValue({ data: draft } as AxiosResponse<Draft>);
    vi.mocked(sourcingDiscoveryApi.publishDraft).mockRejectedValue(new Error('timeout'));
    await renderDraft(draft);
    vi.mocked(sourcingDiscoveryApi.draft).mockRejectedValue(new Error('offline'));
    fireEvent.click(screen.getByRole('button', { name: /1개 마켓 등록/ }));
    await screen.findByText(/현재 등록 상태를 확인하지 못했습니다/);
    expect(screen.getByRole('button', { name: /1개 마켓 등록/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByDisplayValue('테스트 초안')).toBeDisabled();
  });
});
