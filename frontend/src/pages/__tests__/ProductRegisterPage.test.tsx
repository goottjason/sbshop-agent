import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { AxiosResponse } from 'axios';
import ProductRegisterPage from '../ProductRegisterPage';
import { sourcingApi } from '../../api/sourcingApi';
import { notify } from '../../utils/notify';

vi.mock('../../api/sourcingApi', () => ({ sourcingApi: {
  sourceFromIherb: vi.fn(), saveProductsBulk: vi.fn(), publishToMarket: vi.fn(),
} }));
vi.mock('../../utils/notify', () => ({ notify: {
  success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
} }));

const response = <T,>(data: T) => ({ data }) as AxiosResponse<T>;
const scraped = { sourceUrl: 'https://www.iherb.com/pr/test/123', baseName: '테스트 상품',
  originalName: 'Test product', brand: 'Test', costPrice: 12000, sourceImages: [],
  isAvailable: true, capacity: 60, unit: '정', measureUnit: 'TABLET' };

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() })));
  vi.mocked(sourcingApi.sourceFromIherb).mockResolvedValue(response({ succeeded: [scraped], failed: [] }));
  vi.mocked(sourcingApi.saveProductsBulk).mockResolvedValue(response({ succeeded: [{ index: 0, productId: 12, sbCode: 'SB12' }], failed: [] }));
});
afterEach(() => vi.unstubAllGlobals());

async function crawlAndSave() {
  render(<ProductRegisterPage />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: scraped.sourceUrl } });
  fireEvent.click(screen.getByRole('button', { name: '크롤링' }));
  fireEvent.click(await screen.findByRole('button', { name: /선택한 상품 저장/ }));
}

async function publishToCoupang() {
  await screen.findByText(/저장된 상품 1개/);
  const select = await screen.findByRole('combobox');
  fireEvent.mouseDown(select);
  fireEvent.click(await screen.findByTitle('COUPANG'));
  fireEvent.click(screen.getByRole('button', { name: /마켓 등록 \(/ }));
}

describe('수동 신규 상품 등록 결과', () => {
  it('소싱 API가 해석한 용량 단위를 상품 저장 시 보존한다', async () => {
    await crawlAndSave();
    await waitFor(() => expect(sourcingApi.saveProductsBulk).toHaveBeenCalledWith([
      expect.objectContaining({ capacity: 60, measureUnit: 'TABLET' }),
    ]));
  });

  it('모든 저장이 실패하면 보정 화면에 사유를 표시하고 마켓 등록으로 진행하지 않는다', async () => {
    vi.mocked(sourcingApi.saveProductsBulk).mockResolvedValue(response({ succeeded: [], failed: [{ index: 0, baseName: scraped.baseName, reason: '원산지 누락' }] }));
    await crawlAndSave();
    await screen.findByText(/원산지 누락/);
    expect(screen.getByRole('button', { name: /선택한 상품 저장/ })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /마켓 등록 \(/ })).not.toBeInTheDocument();
    expect(sourcingApi.publishToMarket).not.toHaveBeenCalled();
  });

  it('PENDING 응답을 성공으로 보고하지 않고 확인 대기로 표시한다', async () => {
    vi.mocked(sourcingApi.publishToMarket).mockResolvedValue(response({ market: 'COUPANG', status: 'PENDING', url: null, identifiers: {} }));
    await crawlAndSave();
    await publishToCoupang();
    await screen.findByText('마켓 등록 결과');
    expect(screen.getByText('확인 대기')).toBeInTheDocument();
    expect(screen.getByText('성공 0 / 확인 대기 1 / 실패 0')).toBeInTheDocument();
    expect(notify.success).not.toHaveBeenCalledWith('모든 마켓 등록 완료');
  });

  it('SYNCED 응답만 등록 성공으로 집계한다', async () => {
    vi.mocked(sourcingApi.publishToMarket).mockResolvedValue(response({ market: 'COUPANG', status: 'SYNCED', url: null, identifiers: {} }));
    await crawlAndSave();
    await publishToCoupang();
    await waitFor(() => expect(notify.success).toHaveBeenCalledWith('모든 마켓 등록 완료'));
    expect(screen.getByText('성공 1 / 확인 대기 0 / 실패 0')).toBeInTheDocument();
  });

  it('크롤링이 모두 실패해도 URL별 실패 사유가 보인다', async () => {
    vi.mocked(sourcingApi.sourceFromIherb).mockResolvedValue(response({ succeeded: [], failed: [{ url: scraped.sourceUrl, reason: '상품 링크가 만료되었습니다' }] }));
    render(<ProductRegisterPage />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: scraped.sourceUrl } });
    fireEvent.click(screen.getByRole('button', { name: '크롤링' }));
    expect(await screen.findByText(/상품 링크가 만료되었습니다/)).toBeInTheDocument();
  });
});
