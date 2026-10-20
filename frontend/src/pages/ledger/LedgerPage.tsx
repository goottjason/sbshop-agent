import React, { useEffect, useState, useMemo, useCallback } from 'react';
import {
  Calendar,
  Card,
  Progress,
  Button,
  Input,
  InputNumber,
  Tag,
  Radio,
  Space,
  Modal,
  Popconfirm,
  Tooltip,
  Typography,
  Segmented,
  DatePicker,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import 'dayjs/locale/ko';
import MaterialIcon from '../../components/common/MaterialIcon';
import {
  ledgerApi,
  type LedgerExpense,
  type PeriodSummary,
} from '../../api/ledgerApi';

dayjs.locale('ko');

const { Text, Title } = Typography;

// 세련된 뉴트럴 슬레이트 팔레트
const PALETTE = {
  bgPage: '#f8fafc', // Slate 50
  cardBg: '#ffffff',
  border: '#e2e8f0', // Slate 200
  borderLight: '#f1f5f9',
  textMain: '#0f172a', // Slate 900
  textMuted: '#64748b', // Slate 500
  textLight: '#94a3b8', // Slate 400
  darkBtn: '#1e293b', // Slate 800
  dangerText: '#e11d48', // Muted Rose
  successText: '#059669', // Muted Emerald
  warningBg: '#fffbeb',
  warningBorder: '#fde68a',
  warningText: '#b45309',
};

// 관리자 3인 톤다운 감성 컬러 (JW: 인디고, YH: 웜 앰버, CM: 딥 틸 공용)
const ADMIN_CONFIG = {
  ADMIN_A: {
    key: 'JW',
    label: 'JW',
    shortLabel: 'JW',
    color: '#6366f1', // Indigo 500
    text: '#4338ca', // Indigo 700
    bgLight: '#eef2ff', // Indigo 50
    border: '#c7d2fe', // Indigo 200
  },
  ADMIN_B: {
    key: 'YH',
    label: 'YH',
    shortLabel: 'YH',
    color: '#d97706', // Amber 600
    text: '#b45309', // Amber 700
    bgLight: '#fffbeb', // Amber 50
    border: '#fde68a', // Amber 200
  },
  ADMIN_CM: {
    key: 'CM',
    label: 'CM (공용)',
    shortLabel: 'CM',
    color: '#0d9488', // Teal 600
    text: '#0f766e', // Teal 700
    bgLight: '#f0fdfa', // Teal 50
    border: '#99f6e4', // Teal 200
  },
};

const getAdminConfig = (user?: string | null) => {
  if (user === 'JW') return ADMIN_CONFIG.ADMIN_A;
  if (user === 'YH') return ADMIN_CONFIG.ADMIN_B;
  return ADMIN_CONFIG.ADMIN_CM;
};

// 월요일 시작 주 계산 헬퍼 함수
const getMondayOf = (d: Dayjs): Dayjs => {
  const day = d.day(); // 0(일), 1(월), ... 6(토)
  const diff = day === 0 ? -6 : 1 - day;
  return d.add(diff, 'day').startOf('day');
};

const LedgerPage: React.FC = () => {
  // 기간 뷰 모드 ('MONTHLY' | 'WEEKLY')
  const [viewMode, setViewMode] = useState<'MONTHLY' | 'WEEKLY'>('MONTHLY');
  const [currentDate, setCurrentDate] = useState<Dayjs>(dayjs());
  const [selectedDate, setSelectedDate] = useState<Dayjs>(dayjs());
  const [filterAdmin, setFilterAdmin] = useState<string>('ALL'); // 'ALL' | 'JW' | 'YH' | 'CM'

  const [expenses, setExpenses] = useState<LedgerExpense[]>([]);
  const [summary, setSummary] = useState<PeriodSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  // 지출처 & 분류 마스터 상태
  const [paymentSources, setPaymentSources] = useState<string[]>([]);
  const [categories, setCategories] = useState<string[]>([]);

  // 퀵 등록/수정 폼 상태
  const [editingExpenseId, setEditingExpenseId] = useState<number | null>(null);
  const [formDate, setFormDate] = useState<Dayjs>(dayjs());
  const [selectedAdmin, setSelectedAdmin] = useState<string>('JW');
  const [amount, setAmount] = useState<number | null>(null);
  const [paymentSource, setPaymentSource] = useState<string>('');
  const [category, setCategory] = useState<string>('');
  const [memo, setMemo] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);

  // 모달 상태
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState<boolean>(false);
  const [isStatsModalOpen, setIsStatsModalOpen] = useState<boolean>(false);
  const [isManageModalOpen, setIsManageModalOpen] = useState<boolean>(false);
  const [manageTab, setManageTab] = useState<'SOURCE' | 'CATEGORY'>('SOURCE');

  // 지출처/분류 관리 모달 내 인라인 편집 상태
  const [newItemName, setNewItemName] = useState<string>('');
  const [editingItemIndex, setEditingItemIndex] = useState<number | null>(null);
  const [editingItemText, setEditingItemText] = useState<string>('');

  const [targetBudgetInput, setTargetBudgetInput] = useState<number>(2500000);

  // 주간 계산 (월요일 ~ 일요일)
  const weekStart = useMemo(() => getMondayOf(currentDate), [currentDate]);
  const weekEnd = useMemo(() => weekStart.add(6, 'day').endOf('day'), [weekStart]);
  const currentYearMonth = useMemo(() => currentDate.format('YYYY-MM'), [currentDate]);

  // 마스터 아이템 로드
  useEffect(() => {
    const loadMaster = async () => {
      const [sources, cats] = await Promise.all([
        ledgerApi.getPaymentSources(),
        ledgerApi.getCategories(),
      ]);
      setPaymentSources(sources);
      setCategories(cats);
      if (sources.length > 0 && !paymentSource) setPaymentSource(sources[0]);
      if (cats.length > 0 && !category) setCategory(cats[0]);
    };
    loadMaster();
  }, []);

  // 날짜 범위 계산
  const dateRange = useMemo(() => {
    if (viewMode === 'WEEKLY') {
      return {
        start: weekStart.format('YYYY-MM-DD'),
        end: weekEnd.format('YYYY-MM-DD'),
        label: `${weekStart.format('M.D(월)')} ~ ${weekEnd.format('M.D(일)')}`,
      };
    } else {
      return {
        start: currentDate.startOf('month').format('YYYY-MM-DD'),
        end: currentDate.endOf('month').format('YYYY-MM-DD'),
        label: currentDate.format('YYYY년 M월'),
      };
    }
  }, [viewMode, currentDate, weekStart, weekEnd]);

  // 데이터 로드
  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [expenseList, summaryData] = await Promise.all([
        ledgerApi.getExpenses({
          startDate: dateRange.start,
          endDate: dateRange.end,
          adminUser: filterAdmin === 'ALL' ? undefined : filterAdmin,
        }),
        ledgerApi.getMonthlySummary(currentYearMonth, filterAdmin === 'ALL' ? 'ALL' : filterAdmin),
      ]);

      setExpenses(expenseList);
      setSummary(summaryData);
      if (summaryData?.budgetAmount) {
        setTargetBudgetInput(summaryData.budgetAmount);
      }
    } catch (err) {
      console.error('가계부 데이터 로드 실패:', err);
    } finally {
      setLoading(false);
    }
  }, [dateRange, currentYearMonth, filterAdmin]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // 선택된 날짜의 지출 목록
  const selectedDayExpenses = useMemo(() => {
    const target = selectedDate.format('YYYY-MM-DD');
    return expenses.filter((e) => e.spentDate === target);
  }, [expenses, selectedDate]);

  // 선택된 날짜의 지출 총액 (JW / YH / CM / 전체)
  const selectedDaySummary = useMemo(() => {
    let total = 0;
    let jw = 0;
    let yh = 0;
    let cm = 0;
    selectedDayExpenses.forEach((e) => {
      total += Number(e.amount);
      if (e.adminUser === 'JW') jw += Number(e.amount);
      else if (e.adminUser === 'YH') yh += Number(e.amount);
      else cm += Number(e.amount);
    });
    return { total, jw, yh, cm };
  }, [selectedDayExpenses]);

  // 날짜별 지출 매핑 (캘린더 & 주간 뷰용)
  const dateExpenseMap = useMemo(() => {
    const map = new Map<string, { total: number; jw: number; yh: number; cm: number; items: LedgerExpense[] }>();
    expenses.forEach((e) => {
      const dateKey = e.spentDate;
      const prev = map.get(dateKey) || { total: 0, jw: 0, yh: 0, cm: 0, items: [] };
      prev.total += Number(e.amount);
      if (e.adminUser === 'JW') prev.jw += Number(e.amount);
      else if (e.adminUser === 'YH') prev.yh += Number(e.amount);
      else prev.cm += Number(e.amount);
      prev.items.push(e);
      map.set(dateKey, prev);
    });
    return map;
  }, [expenses]);

  // 선택 날짜 변경 시 폼 날짜 자동 동기화
  useEffect(() => {
    if (!editingExpenseId) {
      setFormDate(selectedDate);
    }
  }, [selectedDate, editingExpenseId]);

  // 지출 등록 및 수정 핸들러
  const handleSubmitExpense = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!amount || amount <= 0) {
      alert('금액을 입력해주세요.');
      return;
    }
    if (!paymentSource) {
      alert('지출처(출금 계좌/카드)를 선택해주세요.');
      return;
    }
    if (!category) {
      alert('분류를 선택해주세요.');
      return;
    }

    setSubmitting(true);
    try {
      const payload = {
        spentDate: formDate.format('YYYY-MM-DD'),
        amount,
        category,
        paymentSource,
        memo: memo.trim(),
        adminUser: selectedAdmin,
      };

      if (editingExpenseId) {
        await ledgerApi.updateExpense(editingExpenseId, payload);
        setEditingExpenseId(null);
      } else {
        await ledgerApi.createExpense(payload);
      }

      setAmount(null);
      setMemo('');
      fetchData();
    } catch (err) {
      console.error('지출 저장 실패:', err);
      alert('저장에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  // 수정 모드 진입
  const handleStartEdit = (item: LedgerExpense) => {
    setEditingExpenseId(item.id);
    setFormDate(dayjs(item.spentDate));
    setSelectedAdmin(item.adminUser);
    setPaymentSource(item.paymentSource);
    setCategory(item.category);
    setAmount(Number(item.amount));
    setMemo(item.memo || '');
  };

  // 수정 모드 취소
  const handleCancelEdit = () => {
    setEditingExpenseId(null);
    setAmount(null);
    setMemo('');
    setFormDate(selectedDate);
  };

  // 지출 삭제
  const handleDeleteExpense = async (id: number) => {
    try {
      await ledgerApi.deleteExpense(id);
      if (editingExpenseId === id) {
        handleCancelEdit();
      }
      fetchData();
    } catch (err) {
      console.error('지출 삭제 실패:', err);
      alert('삭제에 실패했습니다.');
    }
  };

  // 예산 저장
  const handleSaveBudget = async () => {
    try {
      await ledgerApi.setBudget({
        yearMonth: currentYearMonth,
        adminUser: filterAdmin === 'ALL' ? 'ALL' : filterAdmin,
        targetAmount: targetBudgetInput,
      });
      setIsBudgetModalOpen(false);
      fetchData();
    } catch (err) {
      console.error('예산 저장 실패:', err);
      alert('예산 저장에 실패했습니다.');
    }
  };

  // 지출처/분류 마스터 관리 핸들러
  const handleAddItem = () => {
    const val = newItemName.trim();
    if (!val) return;

    if (manageTab === 'SOURCE') {
      if (paymentSources.includes(val)) {
        alert('이미 존재하는 지출처입니다.');
        return;
      }
      const updated = [...paymentSources, val];
      setPaymentSources(updated);
      ledgerApi.savePaymentSources(updated);
    } else {
      if (categories.includes(val)) {
        alert('이미 존재하는 분류입니다.');
        return;
      }
      const updated = [...categories, val];
      setCategories(updated);
      ledgerApi.saveCategories(updated);
    }
    setNewItemName('');
  };

  const handleSaveEditItem = (index: number) => {
    const val = editingItemText.trim();
    if (!val) return;

    if (manageTab === 'SOURCE') {
      const oldVal = paymentSources[index];
      const updated = [...paymentSources];
      updated[index] = val;
      setPaymentSources(updated);
      ledgerApi.savePaymentSources(updated);
      if (paymentSource === oldVal) setPaymentSource(val);
    } else {
      const oldVal = categories[index];
      const updated = [...categories];
      updated[index] = val;
      setCategories(updated);
      ledgerApi.saveCategories(updated);
      if (category === oldVal) setCategory(val);
    }
    setEditingItemIndex(null);
    setEditingItemText('');
  };

  const handleDeleteItem = (index: number) => {
    if (manageTab === 'SOURCE') {
      if (paymentSources.length <= 1) {
        alert('최소 1개 이상의 지출처가 필요합니다.');
        return;
      }
      const target = paymentSources[index];
      const updated = paymentSources.filter((_, i) => i !== index);
      setPaymentSources(updated);
      ledgerApi.savePaymentSources(updated);
      if (paymentSource === target) setPaymentSource(updated[0]);
    } else {
      if (categories.length <= 1) {
        alert('최소 1개 이상의 분류가 필요합니다.');
        return;
      }
      const target = categories[index];
      const updated = categories.filter((_, i) => i !== index);
      setCategories(updated);
      ledgerApi.saveCategories(updated);
      if (category === target) setCategory(updated[0]);
    }
  };

  // 캘린더 날짜 셀 커스텀 렌더러
  const dateCellRender = (value: Dayjs) => {
    const dateKey = value.format('YYYY-MM-DD');
    const dayData = dateExpenseMap.get(dateKey);

    if (!dayData) return null;

    return (
      <div style={{ marginTop: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
        {dayData.jw > 0 && (
          <div
            style={{
              fontSize: '11px',
              fontWeight: 600,
              padding: '1px 5px',
              borderRadius: 3,
              backgroundColor: ADMIN_CONFIG.ADMIN_A.bgLight,
              color: ADMIN_CONFIG.ADMIN_A.text,
              border: `1px solid ${ADMIN_CONFIG.ADMIN_A.border}`,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
            title={`JW: ${dayData.jw.toLocaleString()}원`}
          >
            JW {dayData.jw >= 10000 ? `${(dayData.jw / 10000).toFixed(1)}만` : dayData.jw.toLocaleString()}
          </div>
        )}
        {dayData.yh > 0 && (
          <div
            style={{
              fontSize: '11px',
              fontWeight: 600,
              padding: '1px 5px',
              borderRadius: 3,
              backgroundColor: ADMIN_CONFIG.ADMIN_B.bgLight,
              color: ADMIN_CONFIG.ADMIN_B.text,
              border: `1px solid ${ADMIN_CONFIG.ADMIN_B.border}`,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
            title={`YH: ${dayData.yh.toLocaleString()}원`}
          >
            YH {dayData.yh >= 10000 ? `${(dayData.yh / 10000).toFixed(1)}만` : dayData.yh.toLocaleString()}
          </div>
        )}
        {dayData.cm > 0 && (
          <div
            style={{
              fontSize: '11px',
              fontWeight: 600,
              padding: '1px 5px',
              borderRadius: 3,
              backgroundColor: ADMIN_CONFIG.ADMIN_CM.bgLight,
              color: ADMIN_CONFIG.ADMIN_CM.text,
              border: `1px solid ${ADMIN_CONFIG.ADMIN_CM.border}`,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
            title={`CM(공용): ${dayData.cm.toLocaleString()}원`}
          >
            CM {dayData.cm >= 10000 ? `${(dayData.cm / 10000).toFixed(1)}만` : dayData.cm.toLocaleString()}
          </div>
        )}
      </div>
    );
  };

  // 기간 예산 소진율 계산
  const currentRangeTotal = useMemo(() => {
    return expenses.reduce((acc, cur) => acc + Number(cur.amount), 0);
  }, [expenses]);

  const rawBudget = summary?.budgetAmount || 0;
  const effectiveBudget = viewMode === 'WEEKLY' ? Math.round(rawBudget / 4) : rawBudget;
  const budgetPercent = effectiveBudget > 0 ? Math.min(Math.round((currentRangeTotal / effectiveBudget) * 100), 100) : 0;
  const remainingBudget = effectiveBudget - currentRangeTotal;

  // 주간 7일 배열 (월~일)
  const weekDays = useMemo(() => {
    const days: Dayjs[] = [];
    for (let i = 0; i < 7; i++) {
      days.push(weekStart.add(i, 'day'));
    }
    return days;
  }, [weekStart]);

  return (
    <div style={{ padding: '6px 12px 36px 12px', maxWidth: 1440, margin: '0 auto', background: PALETTE.bgPage }}>
      {/* 1. 상단 헤더: 타이틀 & 기간 모드 (월간/주간) & 네비게이션 & 관리자 필터 */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 16,
          background: PALETTE.cardBg,
          padding: '16px 22px',
          borderRadius: 10,
          marginBottom: 16,
          border: `1px solid ${PALETTE.border}`,
          boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.03)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <div
            style={{
              background: PALETTE.darkBtn,
              color: '#ffffff',
              width: 38,
              height: 38,
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <MaterialIcon name="account_balance_wallet" size={21} />
          </div>
          <div>
            <Title level={4} style={{ margin: 0, fontSize: 17, color: PALETTE.textMain, letterSpacing: '-0.3px' }}>
              간편 가계부 <span style={{ fontSize: 12, color: PALETTE.textMuted, fontWeight: 400 }}>관리자 부가도구</span>
            </Title>
            <Text type="secondary" style={{ fontSize: 12, color: PALETTE.textMuted }}>
              월간 / 주간(월~일) 지출 및 출금처(카드/계좌)·예산 통합 관리 (JW, YH, CM 공용)
            </Text>
          </div>
        </div>

        {/* 기간 모드 토글 & 이동 네비게이션 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <Segmented
            options={[
              {
                label: (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 13 }}>
                    <MaterialIcon name="calendar_month" size={16} />
                    월간
                  </span>
                ),
                value: 'MONTHLY',
              },
              {
                label: (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 13 }}>
                    <MaterialIcon name="view_week" size={16} />
                    주간 (월~일)
                  </span>
                ),
                value: 'WEEKLY',
              },
            ]}
            value={viewMode}
            onChange={(val) => setViewMode(val as 'MONTHLY' | 'WEEKLY')}
          />

          <div style={{ display: 'flex', alignItems: 'center', gap: 6, background: '#f8fafc', padding: '3px 6px', borderRadius: 6, border: `1px solid ${PALETTE.border}` }}>
            <Button
              type="text"
              size="small"
              icon={<MaterialIcon name="chevron_left" size={18} />}
              onClick={() => {
                if (viewMode === 'WEEKLY') {
                  const prev = currentDate.subtract(1, 'week');
                  setCurrentDate(prev);
                  setSelectedDate(getMondayOf(prev));
                } else {
                  const prev = currentDate.subtract(1, 'month');
                  setCurrentDate(prev);
                  setSelectedDate(prev.date(1));
                }
              }}
            />
            <div style={{ fontSize: 14, fontWeight: 600, minWidth: 140, textAlign: 'center', color: PALETTE.textMain }}>
              {dateRange.label}
            </div>
            <Button
              type="text"
              size="small"
              icon={<MaterialIcon name="chevron_right" size={18} />}
              onClick={() => {
                if (viewMode === 'WEEKLY') {
                  const next = currentDate.add(1, 'week');
                  setCurrentDate(next);
                  setSelectedDate(getMondayOf(next));
                } else {
                  const next = currentDate.add(1, 'month');
                  setCurrentDate(next);
                  setSelectedDate(next.date(1));
                }
              }}
            />
            <Button
              size="small"
              type="text"
              onClick={() => {
                const today = dayjs();
                setCurrentDate(today);
                setSelectedDate(today);
              }}
              style={{ fontSize: 12, color: PALETTE.textMuted }}
            >
              오늘
            </Button>
          </div>
        </div>

        {/* 2인 관리자 구분 필터 탭 & 상단 도구 버튼들 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Radio.Group
            value={filterAdmin}
            onChange={(e) => setFilterAdmin(e.target.value)}
            buttonStyle="solid"
            size="middle"
          >
            <Radio.Button value="ALL" style={{ fontSize: 13 }}>전체</Radio.Button>
            <Radio.Button value="JW" style={{ fontSize: 13 }}>
              <span style={{ color: filterAdmin === 'JW' ? '#fff' : ADMIN_CONFIG.ADMIN_A.color, fontWeight: 600 }}>
                ● JW
              </span>
            </Radio.Button>
            <Radio.Button value="YH" style={{ fontSize: 13 }}>
              <span style={{ color: filterAdmin === 'YH' ? '#fff' : ADMIN_CONFIG.ADMIN_B.color, fontWeight: 600 }}>
                ● YH
              </span>
            </Radio.Button>
            <Radio.Button value="CM" style={{ fontSize: 13 }}>
              <span style={{ color: filterAdmin === 'CM' ? '#fff' : ADMIN_CONFIG.ADMIN_CM.color, fontWeight: 600 }}>
                ● CM (공용)
              </span>
            </Radio.Button>
          </Radio.Group>

          <Button
            icon={<MaterialIcon name="tune" size={16} />}
            onClick={() => setIsManageModalOpen(true)}
            style={{ fontSize: 13 }}
          >
            항목 설정
          </Button>
          <Button
            icon={<MaterialIcon name="bar_chart" size={16} />}
            onClick={() => setIsStatsModalOpen(true)}
            style={{ fontSize: 13 }}
          >
            통계
          </Button>
          <Button
            icon={<MaterialIcon name="savings" size={16} />}
            onClick={() => setIsBudgetModalOpen(true)}
            style={{ fontSize: 13 }}
          >
            예산
          </Button>
        </div>
      </div>

      {/* 2. 기간 지출 & 예산 현황 요약 카드 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
          gap: 14,
          marginBottom: 16,
        }}
      >
        <Card
          size="small"
          style={{
            borderRadius: 8,
            border: `1px solid ${PALETTE.border}`,
            background: PALETTE.cardBg,
            boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>
              {viewMode === 'WEEKLY' ? '주간(월~일) 총 지출' : `${currentDate.format('M월')} 총 지출`}
            </Text>
            <MaterialIcon name="trending_down" size={17} color={PALETTE.dangerText} />
          </div>
          <div style={{ fontSize: 21, fontWeight: 700, marginTop: 4, color: PALETTE.textMain, letterSpacing: '-0.3px' }}>
            {currentRangeTotal.toLocaleString()}
            <span style={{ fontSize: 14, fontWeight: 400, marginLeft: 2, color: PALETTE.textMuted }}>원</span>
          </div>
          <div style={{ fontSize: 12, marginTop: 6, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <span style={{ color: ADMIN_CONFIG.ADMIN_A.text, fontWeight: 600 }}>
              JW: {(summary?.byAdminUser?.['JW'] || 0).toLocaleString()}원
            </span>
            <span style={{ color: ADMIN_CONFIG.ADMIN_B.text, fontWeight: 600 }}>
              YH: {(summary?.byAdminUser?.['YH'] || 0).toLocaleString()}원
            </span>
            <span style={{ color: ADMIN_CONFIG.ADMIN_CM.text, fontWeight: 600 }}>
              공용(CM): {(summary?.byAdminUser?.['CM'] || 0).toLocaleString()}원
            </span>
          </div>
        </Card>

        <Card
          size="small"
          style={{
            borderRadius: 8,
            border: `1px solid ${PALETTE.border}`,
            background: PALETTE.cardBg,
            boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>
              {viewMode === 'WEEKLY' ? '주간 환산 예산 및 잔여' : '월 목표 예산 및 잔여'}
            </Text>
            <Button
              type="link"
              size="small"
              onClick={() => setIsBudgetModalOpen(true)}
              style={{ padding: 0, height: 'auto', fontSize: 11, color: PALETTE.textMuted }}
            >
              설정
            </Button>
          </div>
          <div style={{ fontSize: 21, fontWeight: 700, marginTop: 4, letterSpacing: '-0.3px' }}>
            {effectiveBudget > 0 ? (
              <>
                <span style={{ color: remainingBudget >= 0 ? PALETTE.successText : PALETTE.dangerText }}>
                  {remainingBudget >= 0 ? `+${remainingBudget.toLocaleString()}원 남음` : `${Math.abs(remainingBudget).toLocaleString()}원 초과`}
                </span>
                <span style={{ fontSize: 12, color: PALETTE.textMuted, fontWeight: 400, marginLeft: 6 }}>
                  / {effectiveBudget.toLocaleString()}원
                </span>
              </>
            ) : (
              <span style={{ fontSize: 15, color: PALETTE.textLight }}>예산 미설정</span>
            )}
          </div>
          <div style={{ marginTop: 8 }}>
            <Progress
              percent={budgetPercent}
              size="small"
              status={remainingBudget < 0 ? 'exception' : budgetPercent > 80 ? 'active' : 'normal'}
              strokeColor={remainingBudget < 0 ? PALETTE.dangerText : budgetPercent > 80 ? '#f59e0b' : ADMIN_CONFIG.ADMIN_A.color}
            />
          </div>
        </Card>

        <Card
          size="small"
          style={{
            borderRadius: 8,
            border: `1px solid ${PALETTE.border}`,
            background: PALETTE.cardBg,
            boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>
              주요 지출처 (출금 계좌/카드)
            </Text>
            <MaterialIcon name="credit_card" size={17} color={PALETTE.textMuted} />
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
            {summary?.byPaymentSource && Object.keys(summary.byPaymentSource).length > 0 ? (
              Object.entries(summary.byPaymentSource)
                .sort((a, b) => b[1] - a[1])
                .slice(0, 3)
                .map(([src, amt]) => (
                  <Tag
                    key={src}
                    style={{
                      margin: 0,
                      padding: '2px 7px',
                      fontSize: 12,
                      background: '#f1f5f9',
                      border: `1px solid ${PALETTE.border}`,
                      color: PALETTE.textMain,
                    }}
                  >
                    <strong>{src}:</strong> {amt.toLocaleString()}원
                  </Tag>
                ))
            ) : (
              <Text type="secondary" style={{ fontSize: 12, color: PALETTE.textLight }}>
                지출 내역이 없습니다.
              </Text>
            )}
          </div>
        </Card>
      </div>

      {/* 3. 메인 스플릿 뷰 (좌측: 캘린더 / 주간 7일 카드, 우측: 선택 날짜 내역 & 퀵 입력/수정 폼) */}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(600px, 1.4fr) minmax(420px, 1fr)', gap: 16 }}>
        {/* 좌측 영역 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {viewMode === 'WEEKLY' ? (
            /* 주간 모드: 월~일 7일 카드 스트립 */
            <Card
              title={
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontSize: 14, fontWeight: 700, color: PALETTE.textMain }}>
                    주간 지출 흐름 ({weekStart.format('M.D 월')} ~ {weekEnd.format('M.D 일')})
                  </span>
                  <span style={{ fontSize: 11, color: PALETTE.textMuted }}>원하는 날짜 카드를 클릭하세요</span>
                </div>
              }
              style={{
                borderRadius: 8,
                border: `1px solid ${PALETTE.border}`,
                boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
              }}
              bodyStyle={{ padding: 12 }}
            >
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 8 }}>
                {weekDays.map((day) => {
                  const dateKey = day.format('YYYY-MM-DD');
                  const isSelected = selectedDate.format('YYYY-MM-DD') === dateKey;
                  const dayData = dateExpenseMap.get(dateKey);
                  const isToday = day.isSame(dayjs(), 'day');

                  return (
                    <div
                      key={dateKey}
                      onClick={() => setSelectedDate(day)}
                      style={{
                        cursor: 'pointer',
                        padding: '12px 6px',
                        borderRadius: 6,
                        border: isSelected
                          ? `2px solid ${PALETTE.textMain}`
                          : `1px solid ${isToday ? '#cbd5e1' : '#f1f5f9'}`,
                        background: isSelected ? '#f1f5f9' : isToday ? '#fafafa' : '#ffffff',
                        textAlign: 'center',
                        transition: 'all 0.15s ease',
                        minHeight: 126,
                        display: 'flex',
                        flexDirection: 'column',
                        justifyContent: 'space-between',
                      }}
                    >
                      <div>
                        <div
                          style={{
                            fontSize: 11,
                            fontWeight: 600,
                            color: day.day() === 0 ? PALETTE.dangerText : day.day() === 6 ? ADMIN_CONFIG.ADMIN_A.color : PALETTE.textMuted,
                          }}
                        >
                          {day.format('ddd')}
                        </div>
                        <div
                          style={{
                            fontSize: 16,
                            fontWeight: isSelected ? 800 : 700,
                            marginTop: 2,
                            color: isSelected ? '#0f172a' : isToday ? ADMIN_CONFIG.ADMIN_A.color : PALETTE.textMain,
                          }}
                        >
                          {day.format('D')}
                        </div>
                      </div>

                      <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 3 }}>
                        {dayData && dayData.jw > 0 && (
                          <div
                            style={{
                              fontSize: 10,
                              fontWeight: 600,
                              background: ADMIN_CONFIG.ADMIN_A.bgLight,
                              color: ADMIN_CONFIG.ADMIN_A.text,
                              padding: '1px 3px',
                              borderRadius: 3,
                              border: `1px solid ${ADMIN_CONFIG.ADMIN_A.border}`,
                            }}
                          >
                            JW {dayData.jw >= 10000 ? `${(dayData.jw / 10000).toFixed(1)}만` : dayData.jw.toLocaleString()}
                          </div>
                        )}
                        {dayData && dayData.yh > 0 && (
                          <div
                            style={{
                              fontSize: 10,
                              fontWeight: 600,
                              background: ADMIN_CONFIG.ADMIN_B.bgLight,
                              color: ADMIN_CONFIG.ADMIN_B.text,
                              padding: '1px 3px',
                              borderRadius: 3,
                              border: `1px solid ${ADMIN_CONFIG.ADMIN_B.border}`,
                            }}
                          >
                            YH {dayData.yh >= 10000 ? `${(dayData.yh / 10000).toFixed(1)}만` : dayData.yh.toLocaleString()}
                          </div>
                        )}
                        {dayData && dayData.cm > 0 && (
                          <div
                            style={{
                              fontSize: 10,
                              fontWeight: 600,
                              background: ADMIN_CONFIG.ADMIN_CM.bgLight,
                              color: ADMIN_CONFIG.ADMIN_CM.text,
                              padding: '1px 3px',
                              borderRadius: 3,
                              border: `1px solid ${ADMIN_CONFIG.ADMIN_CM.border}`,
                            }}
                          >
                            CM {dayData.cm >= 10000 ? `${(dayData.cm / 10000).toFixed(1)}만` : dayData.cm.toLocaleString()}
                          </div>
                        )}
                        {(!dayData || dayData.total === 0) && (
                          <span style={{ fontSize: 11, color: '#e2e8f0' }}>-</span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </Card>
          ) : (
            /* 월간 모드: 풀 캘린더 (ledger-calendar 클래스 적용으로 시커먼 선택 칸 문제 해결) */
            <Card
              style={{
                borderRadius: 8,
                border: `1px solid ${PALETTE.border}`,
                boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
              }}
              bodyStyle={{ padding: 12 }}
            >
              <div className="ledger-calendar">
                <Calendar
                  value={selectedDate}
                  onSelect={(val) => {
                    setSelectedDate(val);
                    if (val.month() !== currentDate.month()) {
                      setCurrentDate(val);
                    }
                  }}
                  cellRender={(val) => dateCellRender(val)}
                  headerRender={() => null}
                />
              </div>
            </Card>
          )}
        </div>

        {/* 우측 패널: 선택 날짜 내역 & 퀵 입력 / 수정 폼 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* A. 퀵 입력 / 수정 카드 */}
          <Card
            title={
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 14, fontWeight: 700, color: editingExpenseId ? PALETTE.warningText : PALETTE.textMain }}>
                  {editingExpenseId ? '지출 내역 수정' : `빠른 지출 등록 (${selectedDate.format('M월 D일, ddd')})`}
                </span>
                {editingExpenseId && (
                  <Button
                    size="small"
                    type="text"
                    icon={<MaterialIcon name="close" size={15} />}
                    onClick={handleCancelEdit}
                    style={{ fontSize: 12, color: PALETTE.textMuted }}
                  >
                    수정 취소
                  </Button>
                )}
              </div>
            }
            style={{
              borderRadius: 8,
              border: `1px solid ${editingExpenseId ? PALETTE.warningBorder : PALETTE.border}`,
              background: editingExpenseId ? PALETTE.warningBg : '#ffffff',
              boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
            }}
            bodyStyle={{ padding: 16 }}
          >
            <form onSubmit={handleSubmitExpense}>
              {/* 1. 작성자 선택 (플랫 버튼, 그림자 제거) & 일자 */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
                <div>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted, display: 'block', marginBottom: 5 }}>
                    작성자
                  </Text>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button
                      type="button"
                      className="btn-no-shadow"
                      style={{
                        padding: '4px 14px',
                        borderRadius: 6,
                        border: selectedAdmin === 'JW' ? `1px solid ${ADMIN_CONFIG.ADMIN_A.color}` : `1px solid ${PALETTE.border}`,
                        backgroundColor: selectedAdmin === 'JW' ? ADMIN_CONFIG.ADMIN_A.color : '#ffffff',
                        color: selectedAdmin === 'JW' ? '#ffffff' : ADMIN_CONFIG.ADMIN_A.text,
                        fontWeight: 600,
                        fontSize: 12,
                        cursor: 'pointer',
                        transition: 'all 0.1s ease',
                      }}
                      onClick={() => setSelectedAdmin('JW')}
                    >
                      ● JW
                    </button>
                    <button
                      type="button"
                      className="btn-no-shadow"
                      style={{
                        padding: '4px 14px',
                        borderRadius: 6,
                        border: selectedAdmin === 'YH' ? `1px solid ${ADMIN_CONFIG.ADMIN_B.color}` : `1px solid ${PALETTE.border}`,
                        backgroundColor: selectedAdmin === 'YH' ? ADMIN_CONFIG.ADMIN_B.color : '#ffffff',
                        color: selectedAdmin === 'YH' ? '#ffffff' : ADMIN_CONFIG.ADMIN_B.text,
                        fontWeight: 600,
                        fontSize: 12,
                        cursor: 'pointer',
                        transition: 'all 0.1s ease',
                      }}
                      onClick={() => setSelectedAdmin('YH')}
                    >
                      ● YH
                    </button>
                    <button
                      type="button"
                      className="btn-no-shadow"
                      style={{
                        padding: '4px 14px',
                        borderRadius: 6,
                        border: selectedAdmin === 'CM' ? `1px solid ${ADMIN_CONFIG.ADMIN_CM.color}` : `1px solid ${PALETTE.border}`,
                        backgroundColor: selectedAdmin === 'CM' ? ADMIN_CONFIG.ADMIN_CM.color : '#ffffff',
                        color: selectedAdmin === 'CM' ? '#ffffff' : ADMIN_CONFIG.ADMIN_CM.text,
                        fontWeight: 600,
                        fontSize: 12,
                        cursor: 'pointer',
                        transition: 'all 0.1s ease',
                      }}
                      onClick={() => setSelectedAdmin('CM')}
                    >
                      ● CM (공용)
                    </button>
                  </div>
                </div>

                <div>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted, display: 'block', marginBottom: 5 }}>
                    지출 일자
                  </Text>
                  <DatePicker
                    value={formDate}
                    onChange={(d) => d && setFormDate(d)}
                    size="small"
                    allowClear={false}
                    style={{ width: 120, fontSize: 12 }}
                  />
                </div>
              </div>

              {/* 2. 지출처 (출금 계좌 / 카드 / 현금) 원클릭 칩 선택 */}
              <div style={{ marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted }}>
                    지출처 (출금 계좌 / 결제 카드)
                  </Text>
                  <Button
                    type="link"
                    size="small"
                    style={{ padding: 0, height: 'auto', fontSize: 11, color: PALETTE.textMuted, display: 'inline-flex', alignItems: 'center', gap: 3 }}
                    onClick={() => {
                      setManageTab('SOURCE');
                      setIsManageModalOpen(true);
                    }}
                  >
                    <MaterialIcon name="tune" size={13} />
                    관리
                  </Button>
                </div>

                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {paymentSources.map((src) => {
                    const isSelected = paymentSource === src;
                    return (
                      <Tag.CheckableTag
                        key={src}
                        checked={isSelected}
                        onChange={() => setPaymentSource(src)}
                        style={{
                          padding: '3px 9px',
                          fontSize: 12,
                          borderRadius: 4,
                          border: `1px solid ${isSelected ? PALETTE.darkBtn : PALETTE.border}`,
                          background: isSelected ? PALETTE.darkBtn : '#f8fafc',
                          color: isSelected ? '#ffffff' : PALETTE.textMain,
                          fontWeight: isSelected ? 600 : 400,
                          transition: 'all 0.1s ease',
                        }}
                      >
                        {isSelected && <MaterialIcon name="check" size={13} style={{ marginRight: 3 }} />}
                        {src}
                      </Tag.CheckableTag>
                    );
                  })}
                </div>
              </div>

              {/* 3. 분류 (소비 카테고리) 원클릭 칩 선택 */}
              <div style={{ marginBottom: 14 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted }}>
                    분류 (카테고리)
                  </Text>
                  <Button
                    type="link"
                    size="small"
                    style={{ padding: 0, height: 'auto', fontSize: 11, color: PALETTE.textMuted, display: 'inline-flex', alignItems: 'center', gap: 3 }}
                    onClick={() => {
                      setManageTab('CATEGORY');
                      setIsManageModalOpen(true);
                    }}
                  >
                    <MaterialIcon name="tune" size={13} />
                    관리
                  </Button>
                </div>

                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {categories.map((cat) => {
                    const isSelected = category === cat;
                    return (
                      <Tag.CheckableTag
                        key={cat}
                        checked={isSelected}
                        onChange={() => setCategory(cat)}
                        style={{
                          padding: '2px 8px',
                          fontSize: 12,
                          borderRadius: 4,
                          border: `1px solid ${isSelected ? '#94a3b8' : PALETTE.border}`,
                          background: isSelected ? '#f1f5f9' : '#ffffff',
                          color: isSelected ? PALETTE.textMain : PALETTE.textMuted,
                          fontWeight: isSelected ? 600 : 400,
                        }}
                      >
                        {cat}
                      </Tag.CheckableTag>
                    );
                  })}
                </div>
              </div>

              {/* 4. 금액 & 내용 입력 */}
              <div style={{ display: 'grid', gridTemplateColumns: '135px 1fr', gap: 8, marginBottom: 14 }}>
                <div>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted, display: 'block', marginBottom: 4 }}>
                    금액 (원)
                  </Text>
                  <InputNumber
                    placeholder="금액 입력"
                    style={{ width: '100%' }}
                    size="middle"
                    value={amount}
                    onChange={(val) => setAmount(val)}
                    formatter={(val) => `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                    parser={(val) => Number(val?.replace(/\$\s?|(,*)/g, '') || 0)}
                  />
                </div>
                <div>
                  <Text style={{ fontSize: 11, color: PALETTE.textMuted, display: 'block', marginBottom: 4 }}>
                    내용 (사용처 및 비고)
                  </Text>
                  <Input
                    placeholder="스타벅스 커피, 라벨지 구매 등"
                    size="middle"
                    value={memo}
                    onChange={(e) => setMemo(e.target.value)}
                  />
                </div>
              </div>

              {/* 5. 등록 / 수정 버튼 */}
              <Button
                type="primary"
                htmlType="submit"
                loading={submitting}
                block
                style={{
                  background: editingExpenseId ? PALETTE.warningText : PALETTE.darkBtn,
                  borderColor: editingExpenseId ? PALETTE.warningText : PALETTE.darkBtn,
                  height: 38,
                  fontWeight: 600,
                  fontSize: 13,
                }}
              >
                {editingExpenseId ? '✓ 수정 완료 (Enter)' : '+ 빠른 등록 (Enter)'}
              </Button>
            </form>
          </Card>

          {/* B. 선택 날짜 지출 내역 목록 (수정 & 삭제 지원) */}
          <Card
            loading={loading}
            title={
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 14, fontWeight: 700, color: PALETTE.textMain }}>
                  {selectedDate.format('M월 D일')} 지출 내역 ({selectedDayExpenses.length}건)
                </span>
                <span style={{ fontSize: 14, fontWeight: 700, color: PALETTE.dangerText }}>
                  합계: {selectedDaySummary.total.toLocaleString()}원
                </span>
              </div>
            }
            style={{
              borderRadius: 8,
              border: `1px solid ${PALETTE.border}`,
              boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
            }}
            bodyStyle={{ padding: 12, maxHeight: 380, overflowY: 'auto' }}
          >
            {selectedDayExpenses.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '32px 0', color: PALETTE.textLight }}>
                <MaterialIcon name="calendar_today" size={26} color="#cbd5e1" style={{ marginBottom: 8 }} />
                <div style={{ fontSize: 13, color: PALETTE.textMuted }}>이 날짜에 등록된 지출 내역이 없습니다.</div>
                <div style={{ fontSize: 11, color: PALETTE.textLight, marginTop: 4 }}>
                  상단 입력창에서 지출을 빠르게 기록해보세요
                </div>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {selectedDayExpenses.map((item) => {
                  const adminCfg = getAdminConfig(item.adminUser);
                  const isEditing = editingExpenseId === item.id;

                  return (
                    <div
                      key={item.id}
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        padding: '9px 12px',
                        background: isEditing ? PALETTE.warningBg : '#fcfcfc',
                        borderRadius: 6,
                        border: isEditing ? `1px solid ${PALETTE.warningBorder}` : `1px solid ${PALETTE.borderLight}`,
                        borderLeft: `3px solid ${adminCfg.color}`,
                      }}
                    >
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span
                            style={{
                              fontSize: 11,
                              fontWeight: 700,
                              color: adminCfg.text,
                              background: adminCfg.bgLight,
                              border: `1px solid ${adminCfg.border}`,
                              borderRadius: 3,
                              padding: '1px 5px',
                            }}
                          >
                            {item.adminUser === 'CM' ? 'CM (공용)' : item.adminUser}
                          </span>
                          <Tag
                            style={{
                              margin: 0,
                              fontSize: 11,
                              padding: '0 5px',
                              background: '#f1f5f9',
                              border: `1px solid ${PALETTE.border}`,
                              color: PALETTE.textMain,
                            }}
                          >
                            {item.paymentSource}
                          </Tag>
                          <Tag
                            style={{
                              margin: 0,
                              fontSize: 11,
                              padding: '0 5px',
                              background: '#f8fafc',
                              color: PALETTE.textMuted,
                            }}
                          >
                            {item.category}
                          </Tag>
                        </div>
                        <div style={{ fontSize: 13, color: PALETTE.textMain, fontWeight: 500, marginTop: 1 }}>
                          {item.memo || <span style={{ color: PALETTE.textLight, fontStyle: 'italic' }}>내용 없음</span>}
                        </div>
                      </div>

                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 14, fontWeight: 700, color: PALETTE.textMain, letterSpacing: '-0.2px' }}>
                          -{Number(item.amount).toLocaleString()}원
                        </span>

                        {/* 구글 Material 수정 아이콘 */}
                        <Tooltip title="내역 수정">
                          <Button
                            type="text"
                            size="small"
                            icon={<MaterialIcon name="edit" size={16} color={PALETTE.textMuted} />}
                            onClick={() => handleStartEdit(item)}
                            style={{ padding: '0 4px', height: 'auto' }}
                          />
                        </Tooltip>

                        {/* 구글 Material 삭제 아이콘 */}
                        <Popconfirm
                          title="이 지출 내역을 삭제하시겠습니까?"
                          onConfirm={() => handleDeleteExpense(item.id)}
                          okText="삭제"
                          cancelText="취소"
                        >
                          <Tooltip title="내역 삭제">
                            <Button
                              type="text"
                              size="small"
                              icon={<MaterialIcon name="delete" size={16} color={PALETTE.textLight} />}
                              style={{ padding: '0 4px', height: 'auto' }}
                            />
                          </Tooltip>
                        </Popconfirm>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </div>
      </div>

      {/* 4. 지출처 / 분류 마스터 관리 모달 (구글 아이콘 전면 교체) */}
      <Modal
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 15 }}>
            <MaterialIcon name="tune" size={18} />
            {manageTab === 'SOURCE' ? '지출처(출금 계좌/카드)' : '소비 분류(카테고리)'} 관리
          </span>
        }
        open={isManageModalOpen}
        onCancel={() => {
          setIsManageModalOpen(false);
          setEditingItemIndex(null);
        }}
        footer={[
          <Button key="close" type="primary" onClick={() => setIsManageModalOpen(false)} style={{ background: PALETTE.darkBtn }}>
            완료
          </Button>,
        ]}
        width={480}
      >
        <div style={{ padding: '8px 0' }}>
          <Segmented
            block
            options={[
              {
                label: (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                    <MaterialIcon name="credit_card" size={15} />
                    지출처 (계좌/카드)
                  </span>
                ),
                value: 'SOURCE',
              },
              {
                label: (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                    <MaterialIcon name="label" size={15} />
                    분류 (카테고리)
                  </span>
                ),
                value: 'CATEGORY',
              },
            ]}
            value={manageTab}
            onChange={(val) => {
              setManageTab(val as 'SOURCE' | 'CATEGORY');
              setEditingItemIndex(null);
            }}
            style={{ marginBottom: 14 }}
          />

          <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
            <Input
              placeholder={manageTab === 'SOURCE' ? '새 지출처명 (예: 우리카드, 카카오뱅크)' : '새 분류명 (예: 건강, 자기계발)'}
              value={newItemName}
              onChange={(e) => setNewItemName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAddItem();
              }}
            />
            <Button
              type="primary"
              icon={<MaterialIcon name="add" size={16} />}
              onClick={handleAddItem}
              style={{ background: PALETTE.darkBtn }}
            >
              추가
            </Button>
          </div>

          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8, color: PALETTE.textMuted }}>
            목록에서 이름을 수정하거나 삭제할 수 있습니다:
          </Text>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 280, overflowY: 'auto' }}>
            {(manageTab === 'SOURCE' ? paymentSources : categories).map((item, idx) => (
              <div
                key={idx}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '6px 12px',
                  background: '#f8fafc',
                  borderRadius: 6,
                  border: `1px solid ${PALETTE.borderLight}`,
                }}
              >
                {editingItemIndex === idx ? (
                  <div style={{ display: 'flex', gap: 6, flex: 1, marginRight: 8 }}>
                    <Input
                      size="small"
                      value={editingItemText}
                      onChange={(e) => setEditingItemText(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleSaveEditItem(idx);
                      }}
                      autoFocus
                    />
                    <Button size="small" type="primary" onClick={() => handleSaveEditItem(idx)} style={{ background: PALETTE.darkBtn }}>
                      저장
                    </Button>
                    <Button size="small" onClick={() => setEditingItemIndex(null)}>
                      취소
                    </Button>
                  </div>
                ) : (
                  <>
                    <span style={{ fontSize: 13, color: PALETTE.textMain }}>{item}</span>
                    <Space size={2}>
                      <Button
                        type="text"
                        size="small"
                        icon={<MaterialIcon name="edit" size={14} color={PALETTE.textMuted} />}
                        onClick={() => {
                          setEditingItemIndex(idx);
                          setEditingItemText(item);
                        }}
                      />
                      <Popconfirm
                        title="이 항목을 삭제하시겠습니까?"
                        onConfirm={() => handleDeleteItem(idx)}
                        okText="삭제"
                        cancelText="취소"
                      >
                        <Button
                          type="text"
                          danger
                          size="small"
                          icon={<MaterialIcon name="delete" size={14} color={PALETTE.dangerText} />}
                        />
                      </Popconfirm>
                    </Space>
                  </>
                )}
              </div>
            ))}
          </div>
        </div>
      </Modal>

      {/* 5. 예산 설정 모달 */}
      <Modal
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 15 }}>
            <MaterialIcon name="savings" size={18} />
            월간 목표 예산 설정
          </span>
        }
        open={isBudgetModalOpen}
        onOk={handleSaveBudget}
        onCancel={() => setIsBudgetModalOpen(false)}
        okText="저장"
        cancelText="취소"
      >
        <div style={{ padding: '12px 0' }}>
          <Text style={{ display: 'block', marginBottom: 8, fontSize: 13, color: PALETTE.textMain }}>
            <strong>{currentDate.format('YYYY년 M월')}</strong> 목표 예산 ({filterAdmin === 'ALL' ? '전체 통합' : filterAdmin}):
          </Text>
          <InputNumber
            style={{ width: '100%' }}
            size="large"
            value={targetBudgetInput}
            onChange={(val) => setTargetBudgetInput(val || 0)}
            formatter={(val) => `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={(val) => Number(val?.replace(/\$\s?|(,*)/g, '') || 0)}
            addonAfter="원"
          />
          <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12, color: PALETTE.textMuted }}>
            설정된 월 예산은 주간 모드 시 주간 예산(월 예산 ÷ 4)으로 자동 환산되어 소진율이 계산됩니다.
          </Text>
        </div>
      </Modal>

      {/* 6. 통계 분석 모달 */}
      <Modal
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 15 }}>
            <MaterialIcon name="bar_chart" size={18} />
            {currentDate.format('YYYY년 M월')} 지출 통계 및 결산
          </span>
        }
        open={isStatsModalOpen}
        onCancel={() => setIsStatsModalOpen(false)}
        footer={[
          <Button key="close" onClick={() => setIsStatsModalOpen(false)}>
            닫기
          </Button>,
        ]}
        width={640}
      >
        <div style={{ padding: '8px 0' }}>
          {/* A. 관리자 및 공용 분담 현황 */}
          <Title level={5} style={{ fontSize: 13, marginBottom: 8, color: PALETTE.textMain }}>
            1. 관리자 및 공용(JW, YH, CM) 분담액
          </Title>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
            <Card size="small" style={{ borderLeft: `3px solid ${ADMIN_CONFIG.ADMIN_A.color}`, background: '#f8fafc' }}>
              <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>JW 총 지출</Text>
              <div style={{ fontSize: 18, fontWeight: 700, color: ADMIN_CONFIG.ADMIN_A.text, marginTop: 4 }}>
                {(summary?.byAdminUser?.['JW'] || 0).toLocaleString()}원
              </div>
            </Card>
            <Card size="small" style={{ borderLeft: `3px solid ${ADMIN_CONFIG.ADMIN_B.color}`, background: '#f8fafc' }}>
              <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>YH 총 지출</Text>
              <div style={{ fontSize: 18, fontWeight: 700, color: ADMIN_CONFIG.ADMIN_B.text, marginTop: 4 }}>
                {(summary?.byAdminUser?.['YH'] || 0).toLocaleString()}원
              </div>
            </Card>
            <Card size="small" style={{ borderLeft: `3px solid ${ADMIN_CONFIG.ADMIN_CM.color}`, background: '#f8fafc' }}>
              <Text style={{ fontSize: 12, color: PALETTE.textMuted }}>공용(CM) 총 지출</Text>
              <div style={{ fontSize: 18, fontWeight: 700, color: ADMIN_CONFIG.ADMIN_CM.text, marginTop: 4 }}>
                {(summary?.byAdminUser?.['CM'] || 0).toLocaleString()}원
              </div>
            </Card>
          </div>

          {/* B. 출금처(지출처)별 결산 */}
          <Title level={5} style={{ fontSize: 13, marginBottom: 8, color: PALETTE.textMain }}>
            2. 지출처(출금 계좌 / 카드사별) 결산
          </Title>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 20 }}>
            {summary?.byPaymentSource && Object.keys(summary.byPaymentSource).length > 0 ? (
              Object.entries(summary.byPaymentSource)
                .sort((a, b) => b[1] - a[1])
                .map(([src, amt]) => {
                  const percent = currentRangeTotal > 0 ? Math.round((amt / currentRangeTotal) * 100) : 0;
                  return (
                    <div
                      key={src}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '7px 12px',
                        background: '#f8fafc',
                        borderRadius: 6,
                        border: `1px solid ${PALETTE.borderLight}`,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: 130 }}>
                        <Tag style={{ margin: 0, background: '#fff', border: `1px solid ${PALETTE.border}` }}>
                          {src}
                        </Tag>
                      </div>
                      <div style={{ flex: 1, margin: '0 16px' }}>
                        <Progress percent={percent} size="small" strokeColor={ADMIN_CONFIG.ADMIN_A.color} />
                      </div>
                      <div style={{ fontSize: 13, fontWeight: 600, minWidth: 95, textAlign: 'right', color: PALETTE.textMain }}>
                        {amt.toLocaleString()}원
                      </div>
                    </div>
                  );
                })
            ) : (
              <Text type="secondary" style={{ fontSize: 12, color: PALETTE.textLight }}>데이터가 없습니다.</Text>
            )}
          </div>

          {/* C. 카테고리별 비중 */}
          <Title level={5} style={{ fontSize: 13, marginBottom: 8, color: PALETTE.textMain }}>
            3. 소비 분류(카테고리)별 지출 현황
          </Title>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {summary?.byCategory && Object.keys(summary.byCategory).length > 0 ? (
              Object.entries(summary.byCategory)
                .sort((a, b) => b[1] - a[1])
                .map(([cat, amt]) => {
                  const percent = currentRangeTotal > 0 ? Math.round((amt / currentRangeTotal) * 100) : 0;
                  return (
                    <div
                      key={cat}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '7px 12px',
                        background: '#f8fafc',
                        borderRadius: 6,
                        border: `1px solid ${PALETTE.borderLight}`,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: 130 }}>
                        <Tag style={{ margin: 0, background: '#fff', border: `1px solid ${PALETTE.border}` }}>
                          {cat}
                        </Tag>
                      </div>
                      <div style={{ flex: 1, margin: '0 16px' }}>
                        <Progress percent={percent} size="small" strokeColor={ADMIN_CONFIG.ADMIN_B.color} />
                      </div>
                      <div style={{ fontSize: 13, fontWeight: 600, minWidth: 95, textAlign: 'right', color: PALETTE.textMain }}>
                        {amt.toLocaleString()}원
                      </div>
                    </div>
                  );
                })
            ) : (
              <Text type="secondary" style={{ fontSize: 12, color: PALETTE.textLight }}>데이터가 없습니다.</Text>
            )}
          </div>
        </div>
      </Modal>
    </div>
  );
};

export default LedgerPage;
