import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { AxiosResponse } from 'axios';
import { ProductDetailModal } from '../ProductDetailModal';
import { productApi } from '../../../api/productApi';
import { productEditApi } from '../../../api/productChangeApi';
import { notify } from '../../../utils/notify';

vi.mock('../../../api/productApi', () => ({ productApi: {
  fetchProductDetail: vi.fn(), uploadImages: vi.fn(), uploadImagesByUrl: vi.fn(),
} }));
vi.mock('../../../api/productChangeApi', () => ({ productEditApi: { workspace: vi.fn() } }));
vi.mock('../../../utils/notify', () => ({ notify: {
  success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
} }));
vi.mock('../ProductConnections', () => ({ ProductConnections: () => null }));
vi.mock('../ProductEditHistory', () => ({ ProductEditHistory: () => null }));
vi.mock('../ProductPricePreview', () => ({ ProductPricePreview: () => null }));
vi.mock('../ProductMarketPlusHistory', () => ({ ProductMarketPlusHistory: () => null }));
vi.mock('../ProductMarketPlusFieldProgress', () => ({ ProductMarketPlusFieldProgress: () => null }));
vi.mock('../MarketLiveCompare', () => ({ MarketLiveCompare: () => null }));

const response = <T,>(data: T) => ({ data }) as AxiosResponse<T>;
const uploadResult = { storageUpdated: true, imagesSucceeded: 1,
  imagesFailed: [{ ref: 'https://images.example/bad.png', reason: '다운로드 실패' }],
  synced: [], skipped: [], failed: [{ market: 'COUPANG', label: '쿠팡', error: '마켓 반영 실패' }] };
const detail = { id: 12, revision: 1, sbCode: 'SB12', productName: '테스트 상품', baseName: '테스트', brand: '',
  category: '', vendor: 'IHB', originalName: 'test', priceInfo: {}, logisticsInfo: {}, productSpec: {}, sourcingInfo: {},
  sourceImages: [], hostedImages: [], detailHtml: '', memo: '기존 메모' };

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() })));
  vi.mocked(productApi.fetchProductDetail).mockResolvedValue(response(detail));
  vi.mocked(productEditApi.workspace).mockResolvedValue(response({ productId: 12, revision: 1, connections: [],
    fields: [{ field: 'hostedImages', permission: 'EDITABLE' }] }) as never);
  vi.mocked(productApi.uploadImagesByUrl).mockResolvedValue(response(uploadResult));
  vi.mocked(productApi.uploadImages).mockResolvedValue(response(uploadResult));
});
afterEach(() => vi.unstubAllGlobals());

async function openDetail() {
  const onSaved = vi.fn();
  const rendered = render(<ProductDetailModal productId={12} open onClose={vi.fn()} onSaved={onSaved} />);
  await screen.findByDisplayValue('기존 메모');
  return { ...rendered, onSaved };
}

describe('상품 이미지 업로드', () => {
  it('저장하지 않은 상품 편집이 있으면 업로드로 덮어쓰지 않는다', async () => {
    await openDetail();
    fireEvent.change(screen.getByDisplayValue('기존 메모'), { target: { value: '아직 저장하지 않은 메모' } });
    expect(screen.getByRole('button', { name: /파일 업로드/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /URL로 등록/ })).toBeDisabled();
    expect(screen.getByDisplayValue('아직 저장하지 않은 메모')).toBeInTheDocument();
    expect(productApi.uploadImagesByUrl).not.toHaveBeenCalled();
  });

  it('URL 처리와 마켓 반영의 부분 실패를 성공으로 숨기지 않고 목록 갱신을 요청한다', async () => {
    const { onSaved } = await openDetail();
    fireEvent.change(screen.getByPlaceholderText(/이미지 URL을/), { target: { value: 'https://images.example/good.png\nhttps://images.example/bad.png' } });
    fireEvent.click(screen.getByRole('button', { name: /URL로 등록/ }));
    await waitFor(() => expect(notify.warning).toHaveBeenCalled());
    expect(notify.success).not.toHaveBeenCalled();
    expect(vi.mocked(notify.warning).mock.calls[0][0]).toContain('1장');
    expect(vi.mocked(notify.warning).mock.calls[0][0]).toContain('다운로드 실패');
    expect(vi.mocked(notify.warning).mock.calls[0][0]).toContain('쿠팡');
    expect(vi.mocked(notify.warning).mock.calls[0][0]).toContain('마켓 반영 실패');
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
  });

  it('파일 업로드에서도 부분 실패를 표시한다', async () => {
    await openDetail();
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    fireEvent.change(input, { target: { files: [new File(['image'], 'image.png', { type: 'image/png' })] } });
    await waitFor(() => expect(notify.warning).toHaveBeenCalled());
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('서버가 이미지 교체를 거부한 이유를 표시하고 기존 이미지를 갱신하지 않는다', async () => {
    const reason = '등록 가능한 이미지가 최소 1개 필요합니다. 기존 이미지를 유지합니다.';
    vi.mocked(productApi.uploadImagesByUrl).mockRejectedValue({ response: { data: { message: reason } } });
    const { onSaved } = await openDetail();
    fireEvent.change(screen.getByPlaceholderText(/이미지 URL을/), { target: { value: 'https://images.example/bad.png' } });
    fireEvent.click(screen.getByRole('button', { name: /URL로 등록/ }));
    await waitFor(() => expect(notify.error).toHaveBeenCalledWith(expect.stringContaining(reason)));
    expect(productApi.fetchProductDetail).toHaveBeenCalledOnce();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('이미지 저장 중에는 편집과 닫기를 잠가 갱신으로 입력을 잃지 않는다', async () => {
    let finish!: (value: AxiosResponse<typeof uploadResult>) => void;
    vi.mocked(productApi.uploadImagesByUrl).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    await openDetail();
    fireEvent.change(screen.getByPlaceholderText(/이미지 URL을/), { target: { value: 'https://images.example/good.png' } });
    fireEvent.click(screen.getByRole('button', { name: /URL로 등록/ }));
    await waitFor(() => expect(productApi.uploadImagesByUrl).toHaveBeenCalled());
    expect(screen.getByDisplayValue('기존 메모')).toBeDisabled();
    expect(screen.getByRole('button', { name: '닫기' })).toBeDisabled();
    await act(async () => { finish(response(uploadResult)); });
    expect(screen.getByDisplayValue('기존 메모')).toBeEnabled();
  });
});
