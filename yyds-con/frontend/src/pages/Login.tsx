import { useState } from 'react';
import { useNavigate, Navigate } from 'react-router-dom';
import { LogIn, UserPlus, Eye, EyeOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { authApi } from '@/services/api';
import { useAuthStore, status } from '@/store';

export default function Login() {
  const [isRegister, setIsRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const token = useAuthStore((s) => s.token);
  const { t } = useTranslation(['auth']);

  // Already logged in — redirect to home
  if (token) {
    return <Navigate to="/dashboard" replace />;
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      status.error(t('auth:fillCredentials'));
      return;
    }
    if (isRegister && password !== confirmPassword) {
      status.error(t('auth:passwordMismatch'));
      return;
    }
    if (isRegister && password.length < 6) {
      status.error(t('auth:passwordTooShort'));
      return;
    }

    setLoading(true);
    try {
      const res = isRegister
        ? await authApi.register(username.trim(), password)
        : await authApi.login(username.trim(), password);
      setAuth(res.token, res.user);
      status.success(isRegister ? t('auth:registerSuccess') : t('auth:loginSuccess'));
      navigate('/dashboard', { replace: true });
    } catch (err: any) {
      status.error(err.message || t('auth:operationFailed'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-page-bg relative overflow-hidden">
      {/* Subtle radial glow */}
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_center,rgba(74,158,245,0.06)_0%,transparent_70%)]" />

      <div className="relative w-full max-w-md px-4 animate-in">
        {/* Brand header */}
        <div className="mb-8 text-center">
          <img src="/favicon.svg" alt="Yyds" className="mx-auto h-14 w-14 rounded-[3px]" />
          <h1 className="mt-4 text-2xl font-semibold text-text-primary">Yyds Console</h1>
          <p className="mt-1 text-sm text-text-muted">{t('auth:subtitle')}</p>
        </div>

        {/* Form card */}
        <div className="rounded-[3px] border border-card-border bg-card-bg backdrop-blur-xl p-6">
          {/* Tab switcher */}
          <div className="mb-6 flex border-b border-divider">
            <button
              type="button"
              onClick={() => setIsRegister(false)}
              className={`flex-1 pb-2.5 text-sm font-medium transition-all duration-150 border-b-2 ${
                !isRegister
                  ? 'border-brand text-brand'
                  : 'border-transparent text-text-muted hover:text-text-primary'
              }`}
            >
              {t('auth:loginTab')}
            </button>
            <button
              type="button"
              onClick={() => setIsRegister(true)}
              className={`flex-1 pb-2.5 text-sm font-medium transition-all duration-150 border-b-2 ${
                isRegister
                  ? 'border-brand text-brand'
                  : 'border-transparent text-text-muted hover:text-text-primary'
              }`}
            >
              {t('auth:registerTab')}
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-text-secondary">
                {t('auth:username')}
              </label>
              <input
                type="text"
                className="input"
                placeholder={t('auth:usernamePlaceholder')}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                autoFocus
              />
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-text-secondary">
                {t('auth:password')}
              </label>
              <div className="relative">
                <input
                  type={showPassword ? 'text' : 'password'}
                  className="input pr-10"
                  placeholder={isRegister ? t('auth:passwordMinChars') : t('auth:passwordPlaceholder')}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete={isRegister ? 'new-password' : 'current-password'}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-text-hint hover:text-text-muted"
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            {isRegister && (
              <div>
                <label className="mb-1.5 block text-sm font-medium text-text-secondary">
                  {t('auth:confirmPassword')}
                </label>
                <input
                  type="password"
                  className="input"
                  placeholder={t('auth:confirmPasswordPlaceholder')}
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  autoComplete="new-password"
                />
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="btn-primary w-full py-2.5"
            >
              {loading ? (
                <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
              ) : isRegister ? (
                <>
                  <UserPlus size={16} />
                  {t('auth:registerTab')}
                </>
              ) : (
                <>
                  <LogIn size={16} />
                  {t('auth:loginTab')}
                </>
              )}
            </button>
          </form>
        </div>

        <p className="mt-6 text-center text-xs text-text-hint">
          {t('auth:defaultAdmin')}
        </p>
      </div>
    </div>
  );
}
