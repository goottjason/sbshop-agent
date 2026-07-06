import { Routes, Route } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import MainLayout from './layouts/MainLayout';
import Dashboard from './pages/Dashboard';
import OrderGrid from './pages/OrderGrid';
import Settings from './pages/Settings';
import ProductPage from './pages/ProductPage';
import ProductRegisterPage from './pages/ProductRegisterPage';
import BatchUpdatePage from './pages/BatchUpdatePage';
import ProcessStatusPage from './pages/ProcessStatusPage';

import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import 'ag-grid-community/styles/ag-grid.css';
import 'ag-grid-community/styles/ag-theme-quartz.css';

function App() {
  return (
    <ConfigProvider theme={{ token: { colorPrimary: '#000000' } }}>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<Dashboard />} />
          <Route path="orders" element={<OrderGrid />} />
          <Route path="products" element={<ProductPage />} />
          <Route path="register" element={<ProductRegisterPage />} />
          <Route path="batch" element={<BatchUpdatePage />} />
          <Route path="process-status" element={<ProcessStatusPage />} />
          <Route path="settings" element={<Settings />} />
        </Route>
      </Routes>
      <ToastContainer position="bottom-right" autoClose={3000} />
    </ConfigProvider>
  );
}

export default App;
