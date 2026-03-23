import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard,
  Calendar,
  PanelLeftClose,
  PanelLeft,
  ShieldCheck,
  User,
  LogOut,
  Link2,
  Code2,
  BookOpen,
  Loader2,
  Sun,
  Moon,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAppStore, useAuthStore, useStatusStore, useThemeStore } from '@/store';
import LanguageSwitcher from '@/components/LanguageSwitcher';
import type { StatusType } from '@/store';

const STATUS_DOT: Record<StatusType, string> = {
  idle: 'bg-text-hint',
  success: 'bg-success',
  error: 'bg-danger',
  warning: 'bg-warning',
  info: 'bg-brand',
  loading: '',
};

function StatusBar() {
  const message = useStatusStore((s) => s.message);
  const type = useStatusStore((s) => s.type);
  const { t } = useTranslation(['common']);

  return (
    <div className="flex h-7 shrink-0 items-center gap-2 border-t border-divider bg-sidebar-bg px-3 text-xs text-text-muted">
      {type === 'loading' ? (
        <Loader2 size={12} className="shrink-0 animate-spin text-brand" />
      ) : (
        <span className={`inline-block h-2 w-2 shrink-0 rounded-full transition-colors duration-300 ${STATUS_DOT[type]}`} />
      )}
      <span className="truncate transition-opacity duration-300" title={message || undefined}>
        {message || t('common:ready')}
      </span>
    </div>
  );
}

export default function Layout() {
  const collapsed = useAppStore((s) => s.sidebarCollapsed);
  const toggle = useAppStore((s) => s.toggleSidebar);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user?.role === 'admin';
  const toggleTheme = useThemeStore((s) => s.toggle);
  const theme = useThemeStore((s) => s.theme);
  const { t } = useTranslation(['common', 'device']);

  const handleLogout = () => {
    logout();
    queryClient.clear();
    navigate('/login', { replace: true });
  };

  const navItems = [
    { to: '/dashboard', icon: LayoutDashboard, label: t('device:nav.dashboard') },
    { to: '/my-devices', icon: Link2, label: t('device:nav.myDevices') },
    { to: '/dev-tools', icon: Code2, label: t('device:nav.devTools') },
    { to: '/docs', icon: BookOpen, label: t('device:nav.docs') },
    { to: '/schedules', icon: Calendar, label: t('device:nav.schedules') },
    ...(isAdmin
      ? [{ to: '/admin', icon: ShieldCheck, label: t('device:nav.admin') }]
      : []),
  ];

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-page-bg">
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <aside
          className={`flex flex-col border-r border-divider bg-sidebar-bg backdrop-blur-md transition-[width] duration-200 ${
            collapsed ? 'w-14' : 'w-52'
          }`}
        >
          {/* Logo */}
          <div className="flex h-12 items-center gap-2.5 px-3 border-b border-divider">
            <img src="/favicon.svg" alt="Yyds" className="h-7 w-7 shrink-0 rounded-[3px]" />
            {!collapsed && (
              <span className="text-sm font-semibold text-text-primary truncate">
                Yyds Console
              </span>
            )}
          </div>

          {/* Nav */}
          <nav className="flex-1 space-y-0.5 p-2 overflow-y-auto">
            {navItems.map(({ to, icon: Icon, label }) => (
              <NavLink
                key={to}
                to={to}
                end={to === '/dashboard'}
                className={({ isActive }) =>
                  `group relative flex items-center gap-3 rounded-[3px] px-3 py-2 text-sm font-medium transition-all duration-150 ${
                    isActive
                      ? 'bg-hover-strong text-brand-light'
                      : 'text-text-muted hover:bg-hover hover:text-text-primary'
                  }`
                }
              >
                {({ isActive }) => (
                  <>
                    {isActive && (
                      <span className="absolute left-0 top-1/2 -translate-y-1/2 h-4 w-[2px] rounded-r-full bg-brand" />
                    )}
                    <Icon size={18} className="shrink-0" />
                    {!collapsed && <span className="truncate">{label}</span>}
                  </>
                )}
              </NavLink>
            ))}
          </nav>

          {/* User info + logout */}
          <div className="border-t border-divider p-2">
            {!collapsed ? (
              <div className="flex items-center gap-2 rounded-[3px] px-3 py-2">
                <div className="flex h-6 w-6 items-center justify-center rounded-[3px] bg-brand/15 text-brand shrink-0">
                  <User size={13} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="truncate text-xs font-medium text-text-primary">
                    {user?.username}
                  </p>
                  <p className="text-[10px] text-text-hint">
                    {isAdmin ? t('common:admin') : t('common:user')}
                  </p>
                </div>
                <button
                  onClick={handleLogout}
                  className="text-text-hint hover:text-danger transition-colors duration-150"
                  title={t('common:logoutShort')}
                >
                  <LogOut size={13} />
                </button>
              </div>
            ) : (
              <button
                onClick={handleLogout}
                className="flex w-full items-center justify-center py-2 text-text-hint hover:text-danger transition-colors duration-150"
                title={t('common:logoutShort')}
              >
                <LogOut size={15} />
              </button>
            )}
          </div>

          {/* Language + Theme + Collapse */}
          <div className="flex border-t border-divider">
            <LanguageSwitcher collapsed={collapsed} />
            <span className="w-px bg-divider" />
            <button
              onClick={toggleTheme}
              className="flex h-9 flex-1 items-center justify-center text-text-hint hover:bg-hover hover:text-text-primary transition-all duration-150"
              title={theme === 'dark' ? t('common:switchLight') : t('common:switchDark')}
            >
              {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
            </button>
            <span className="w-px bg-divider" />
            <button
              onClick={toggle}
              className="flex h-9 flex-1 items-center justify-center text-text-hint hover:bg-hover hover:text-text-primary transition-all duration-150"
            >
              {collapsed ? <PanelLeft size={15} /> : <PanelLeftClose size={15} />}
            </button>
          </div>
        </aside>

        {/* Main content */}
        <main className="flex-1 overflow-auto">
          <Outlet />
        </main>
      </div>

      {/* Bottom Status Bar */}
      <StatusBar />
    </div>
  );
}
