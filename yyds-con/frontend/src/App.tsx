import '@/i18n';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Layout from '@/components/Layout';
import LandingPage from '@/pages/LandingPage';
import Dashboard from '@/pages/Dashboard';
import DeviceDetail from '@/pages/DeviceDetail';
import Schedules from '@/pages/Schedules';
import FileBrowser from '@/pages/FileBrowser';
import LogViewer from '@/pages/LogViewer';
import IdeWorkbench from '@/pages/IdeWorkbench';
import DevTools from '@/pages/DevTools';
import Documentation from '@/pages/Documentation';
import Login from '@/pages/Login';
import MyDevices from '@/pages/MyDevices';
import AdminPanel from '@/pages/AdminPanel';
import AgentControl from '@/pages/AgentControl';
import TaskRunDetail from '@/pages/TaskRunDetail';
import { useAuthStore } from '@/store';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 2000,
      refetchOnWindowFocus: true,
    },
  },
});

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((s) => s.user);
  if (user?.role !== 'admin') return <Navigate to="/dashboard" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          {/* Public */}
          <Route path="/" element={<LandingPage />} />
          <Route path="/login" element={<Login />} />

          {/* Protected */}
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/devices/:imei" element={<DeviceDetail />} />
            <Route path="/devices/:imei/files" element={<FileBrowser />} />
            <Route path="/devices/:imei/logs" element={<LogViewer />} />
            <Route path="/devices/:imei/agent" element={<AgentControl />} />
            <Route path="/devices/:imei/agent-history" element={<Navigate to="/dashboard" replace />} />
            <Route path="/schedules" element={<Schedules />} />
            <Route path="/task-runs/:runId" element={<TaskRunDetail />} />
            <Route path="/dev-tools" element={<DevTools />} />
            <Route path="/docs" element={<Documentation />} />
            <Route path="/my-devices" element={<MyDevices />} />
            <Route
              path="/admin"
              element={
                <RequireAdmin>
                  <AdminPanel />
                </RequireAdmin>
              }
            />
          </Route>

          {/* IDE: full-screen, no Layout sidebar */}
          <Route
            path="/devices/:imei/ide"
            element={
              <RequireAuth>
                <IdeWorkbench />
              </RequireAuth>
            }
          />
          <Route
            path="/dev-tools/:imei/ide"
            element={
              <RequireAuth>
                <IdeWorkbench backTo="/dev-tools" />
              </RequireAuth>
            }
          />

          {/* Fallback */}
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
