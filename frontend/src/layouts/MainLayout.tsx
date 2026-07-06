import { Outlet, Link, useLocation } from 'react-router-dom';
import { LayoutDashboard, Table, Settings, Package, PlusCircle, RefreshCw, List } from 'lucide-react';

const MainLayout = () => {
  const location = useLocation();

  const navItems = [
    { name: '대시보드', path: '/', icon: LayoutDashboard },
    { name: '통합 주문 관리', path: '/orders', icon: Table },
    { name: '상품 관리', path: '/products', icon: Package },
    { name: '신규 상품 등록', path: '/register', icon: PlusCircle },
    { name: '배치 업데이트', path: '/batch', icon: RefreshCw },
    { name: '진행 현황', path: '/process-status', icon: List },
    { name: '설정 및 연동', path: '/settings', icon: Settings },
  ];

  return (
    <div className="layout-container-top">
      <header className="topbar">
        <div className="topbar-left">
          <div className="sidebar-logo">
            <h2 style={{ fontSize: '18px', letterSpacing: '0.5px', margin: 0 }}>SBSHOP</h2>
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
                  <Icon size={18} />
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
          <div className="user-profile">JA</div>
        </div>
      </header>
      <main className="main-content">
        <div className="page-content">
          <Outlet />
        </div>
      </main>
    </div>
  );
};

export default MainLayout;
