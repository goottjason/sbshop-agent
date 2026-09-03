import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Table, Tag, Typography, Space, Button, Segmented, Tooltip } from 'antd';
import { batchApi } from '../../api/batchApi';
import { marketLabel } from '../../utils/marketLabels';

export interface ProcessStatusItem {
  id: number;
  batchId: string;
  productCode: string;
  jobType: string;
  step: string;
  processStatus: string;
  message: string;
  details: string | null;
  startedAt: string;
}

interface MarketFailure {
  market: string;
  reason: string;
}

interface MarketDetails {
  synced: string[];
  skipped: string[];
  failed: MarketFailure[];
}

const EMPTY_DETAILS: MarketDetails = { synced: [], skipped: [], failed: [] };

function parseMarketDetails(details: string | null | undefined): MarketDetails {
  if (!details) return EMPTY_DETAILS;
  try {
    const parsed = JSON.parse(details) as Partial<MarketDetails>;
    return {
      synced: Array.isArray(parsed.synced) ? parsed.synced : [],
      skipped: Array.isArray(parsed.skipped) ? parsed.skipped : [],
      failed: Array.isArray(parsed.failed) ? parsed.failed : [],
    };
  } catch {
    return EMPTY_DETAILS;
  }
}

const STATUS_META: Record<string, { label: string; color: string }> = {
  SUCCESS: { label: '성공', color: 'green' },
  PARTIAL_FAILED: { label: '부분실패', color: 'orange' },
  FAILED: { label: '실패', color: 'red' },
  PENDING: { label: '대기', color: 'default' },
};

function headline(message: string): string {
  if (!message) return '';
  const cut = message.indexOf(' · 마켓반영');
  return cut >= 0 ? message.slice(0, cut) : message;
}

interface Props {
  batchId: string;
  polling?: boolean;
  onRetry?: (productCodes: string[]) => void;
  retryLabel?: string;
  retryLoading?: boolean;
}

const POLL_MS = 2000;

const BatchResultTable = ({ batchId, polling = false, onRetry, retryLabel, retryLoading }: Props) => {
  const [filter, setFilter] = useState<'problem' | 'all'>('problem');

  const { data: rows = [], isFetching } = useQuery<ProcessStatusItem[]>({
    queryKey: ['batch-result-rows', batchId],
    queryFn: async () => {
      const res = await batchApi.getBatchStatus(batchId);
      return (res.data as ProcessStatusItem[]) || [];
    },
    enabled: !!batchId,
    retry: false,
    gcTime: 0,
    refetchInterval: polling ? POLL_MS : false,
    refetchIntervalInBackground: true,
  });

  const problems = useMemo(
    () => rows.filter((r) => r.processStatus === 'FAILED' || r.processStatus === 'PARTIAL_FAILED'),
    [rows],
  );
  const visible = filter === 'problem' ? problems : rows;

  const columns = [
    {
      title: '상품',
      dataIndex: 'productCode',
      width: 130,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '결과',
      dataIndex: 'processStatus',
      width: 100,
      render: (v: string) => {
        const meta = STATUS_META[v] ?? { label: v, color: 'default' };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '원인',
      dataIndex: 'processStatus',
      key: 'origin',
      width: 90,
      render: (v: string) => {
        if (v === 'FAILED') return <Tag>소싱처</Tag>;
        if (v === 'PARTIAL_FAILED') return <Tag color="volcano">마켓</Tag>;
        return null;
      },
    },
    {
      title: '내용',
      key: 'content',
      render: (_: unknown, row: ProcessStatusItem) => {
        const d = parseMarketDetails(row.details);
        return (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            <Typography.Text>{headline(row.message)}</Typography.Text>
            {d.failed.map((f) => (
              <Typography.Text key={f.market} type="danger" style={{ fontSize: 12 }}>
                {marketLabel(f.market)} — {f.reason}
              </Typography.Text>
            ))}
            {d.synced.length > 0 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                전송됨: {d.synced.map(marketLabel).join(', ')}
                {d.skipped.length > 0 && ` · 변경없음: ${d.skipped.map(marketLabel).join(', ')}`}
              </Typography.Text>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Space wrap>
        <Segmented
          size="small"
          value={filter}
          onChange={(v) => setFilter(v as 'problem' | 'all')}
          options={[
            { label: `문제 ${problems.length}건`, value: 'problem' },
            { label: `전체 ${rows.length}건`, value: 'all' },
          ]}
        />
        {onRetry && problems.length > 0 && (
          <Tooltip title="같은 조건으로 문제 건만 다시 실행합니다">
            <Button
              size="small"
              loading={retryLoading}
              onClick={() => onRetry(problems.map((r) => r.productCode))}
            >
              {retryLabel ?? `문제 ${problems.length}건만 다시 실행`}
            </Button>
          </Tooltip>
        )}
      </Space>
      <Table<ProcessStatusItem>
        rowKey="id"
        size="small"
        loading={isFetching && rows.length === 0}
        dataSource={visible}
        columns={columns}
        pagination={visible.length > 20 ? { pageSize: 20, size: 'small' } : false}
        locale={{ emptyText: filter === 'problem' ? '문제 없이 끝났습니다' : '결과 없음' }}
      />
    </Space>
  );
};

export default BatchResultTable;
