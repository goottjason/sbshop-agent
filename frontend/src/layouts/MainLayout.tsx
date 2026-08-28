import { Outlet, Link, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { LayoutDashboard, Table, Settings, Package, PlusCircle, RefreshCw, List, Sparkles, LogOut } from 'lucide-react';
import { logout } from '../api/axios';

interface Props {
  locked?: boolean;
  children?: ReactNode;
}

const MainLayout = ({ locked = false, children }: Props) => {
  const location = useLocation();

  const navItems = [
    { name: '대시보드', path: '/', icon: LayoutDashboard },
    { name: '통합 주문 관리', path: '/orders', icon: Table },
    { name: '상품 관리', path: '/products', icon: Package },
    { name: '상품 추천·자동등록', path: '/sourcing', icon: Sparkles },
    { name: '신규 상품 등록(수동)', path: '/register', icon: PlusCircle },
    { name: '배치 업데이트', path: '/batch', icon: RefreshCw },
    { name: '진행 현황', path: '/process-status', icon: List },
    { name: '설정 및 연동', path: '/settings', icon: Settings },
  ];

  return (
    <div className="layout-container-top">
      <header className="topbar">
        <div className="topbar-left">
          <div className="sidebar-logo">
            <h2 style={{ fontSize: '15px', letterSpacing: '0.5px', margin: 0, whiteSpace: 'nowrap' }}>SBSHOP</h2>
          </div>
          <nav className="topbar-nav">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = location.pathname === item.path;
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`nav-item-top ${isActive ? 'active' : ''}`}
                >
                  <Icon size={15} />
                  <span>{item.name}</span>
                </Link>
              );
            })}
          </nav>
        </div>
        <div className="topbar-actions">
          <div className="topbar-search">
            <input type="text" placeholder="통합 검색..." />
          </div>
          {!locked && (
            <>
              <div className="user-profile">JA</div>
              <button type="button" onClick={logout} title="로그아웃"
                style={{
                  display: 'flex', alignItems: 'center', gap: 6, marginLeft: 8,
                  background: 'transparent', border: '1px solid #d9d9d9', borderRadius: 6,
                  padding: '6px 10px', cursor: 'pointer', fontSize: 13, color: '#333',
                }}>
                <LogOut size={14} />
                <span>로그아웃</span>
              </button>
            </>
          )}
        </div>
      </header>
      <main className="main-content">
        <div className="page-content">
          {children ?? <Outlet />}
        </div>
      </main>
    </div>
  );
};

export default MainLayout;
