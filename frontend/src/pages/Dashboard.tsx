import { Package, TrendingUp, AlertCircle } from 'lucide-react';

const Dashboard = () => {

  return (
    <div>
      <h1 style={{ marginBottom: '8px' }}>대시보드</h1>
      <p style={{ color: '#666', marginBottom: '24px' }}>
        SB Shop 에이전트의 현재 비즈니스 현황입니다.
      </p>

      <div className="dashboard-grid">
        <div className="card">
          <div className="card-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Package size={20} /> 전체 주문 수
          </div>
          <div className="card-value">0</div>
        </div>

        <div className="card">
          <div className="card-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <TrendingUp size={20} /> 배송 진행 중
          </div>
          <div className="card-value" style={{ color: 'var(--success)' }}>0</div>
        </div>

        <div className="card">
          <div className="card-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <AlertCircle size={20} /> 통관 오류/대기
          </div>
          <div className="card-value" style={{ color: 'var(--error)' }}>0</div>
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
