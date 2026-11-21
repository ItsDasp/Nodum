import { Routes, Route, Navigate } from 'react-router-dom';
import { Dashboard } from '../features/dashboard/Dashboard';
import { Login } from '../features/auth/Login';
import { Projects } from '../features/projects/Projects';
import { Modules } from '../features/modules/Modules';
import { Contracts } from '../features/contracts/Contracts';
import { ApiTesting } from '../features/testing/ApiTesting';
import { ModuleOperationsPage } from '../features/operations/ModuleOperationsPage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('nodum-token');
  
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <Dashboard />
          </ProtectedRoute>
        }
      />
      <Route
        path="/projects"
        element={
          <ProtectedRoute>
            <Projects />
          </ProtectedRoute>
        }
      />
      <Route
        path="/projects/:projectId/modules"
        element={
          <ProtectedRoute>
            <Modules />
          </ProtectedRoute>
        }
      />
      <Route
        path="/projects/:projectId/modules/:moduleId/contracts"
        element={
          <ProtectedRoute>
            <Contracts />
          </ProtectedRoute>
        }
      />
      <Route
        path="/testing"
        element={
          <ProtectedRoute>
            <ApiTesting />
          </ProtectedRoute>
        }
      />
      <Route path="/logs" element={<ProtectedRoute><ModuleOperationsPage mode="logs" /></ProtectedRoute>} />
      <Route path="/shell" element={<ProtectedRoute><ModuleOperationsPage mode="shell" /></ProtectedRoute>} />
    </Routes>
  );
}
