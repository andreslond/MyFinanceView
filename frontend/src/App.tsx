import { Routes, Route, Navigate } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth';
import LoginPage from './pages/LoginPage';
import TransactionsPage from './pages/TransactionsPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/transactions"
        element={
          <RequireAuth>
            <TransactionsPage />
          </RequireAuth>
        }
      />
      <Route path="/" element={<Navigate to="/transactions" replace />} />
      <Route path="*" element={<Navigate to="/transactions" replace />} />
    </Routes>
  );
}
