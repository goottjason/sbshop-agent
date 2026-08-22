import { Routes, Route } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import { lazy, Suspense } from 'react';
import MainLayout from './layouts/MainLayout';
import Dashboard from './pages/Dashboard';
import OrderGrid from './pages/OrderGrid';
import Settings from './pages/Settings';

import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';

const ProductGrid = lazy(() => import('./pages/product/ProductGrid'));
const ProductRegisterPage = lazy(() => import('./pages/ProductRegisterPage'));
const BatchUpdatePage = lazy(() => import('./pages/BatchUpdatePage'));
const ProcessStatusPage = lazy(() => import('./pages/ProcessStatusPage'));
const DiscoveryPage = lazy(() => import('./pages/sourcing/DiscoveryPage'));
const DraftListPage = lazy(() => import('./pages/sourcing/DraftListPage'));
const DraftReviewPage = lazy(() => import('./pages/sourcing/DraftReviewPage'));
const SourcingSettingsPage = lazy(() => import('./pages/sourcing/SourcingSettingsPage'));

const Loading = () => <div style={{ padding: 24, textAlign: 'center' }}>로딩 중...</div>;

function App() {
  return (
    <ConfigProvider theme={{ token: { colorPrimary: '#000000' } }}>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<Dashboard />} />
          <Route path="orders" element={<OrderGrid />} />
          <Route path="products" element={<Suspense fallback={<Loading />}><ProductGrid /></Suspense>} />
          <Route path="register" element={<Suspense fallback={<Loading />}><ProductRegisterPage /></Suspense>} />
          <Route path="sourcing" element={<Suspense fallback={<Loading />}><DiscoveryPage /></Suspense>} />
          <Route path="sourcing/drafts" element={<Suspense fallback={<Loading />}><DraftListPage /></Suspense>} />
          <Route path="sourcing/drafts/:id" element={<Suspense fallback={<Loading />}><DraftReviewPage /></Suspense>} />
          <Route path="sourcing/settings" element={<Suspense fallback={<Loading />}><SourcingSettingsPage /></Suspense>} />
          <Route path="batch" element={<Suspense fallback={<Loading />}><BatchUpdatePage /></Suspense>} />
          <Route path="process-status" element={<Suspense fallback={<Loading />}><ProcessStatusPage /></Suspense>} />
          <Route path="settings" element={<Settings />} />
        </Route>
      </Routes>
      <ToastContainer position="bottom-right" autoClose={3000} />
    </ConfigProvider>
  );
}

export default App;
