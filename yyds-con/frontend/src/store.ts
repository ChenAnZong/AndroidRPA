import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { ConnectionMode, UserInfo } from '@/types';

interface AuthState {
  token: string | null;
  user: UserInfo | null;
  setAuth: (token: string, user: UserInfo) => void;
  logout: () => void;
  isLoggedIn: () => boolean;
  isAdmin: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      logout: () => set({ token: null, user: null }),
      isLoggedIn: () => !!get().token,
      isAdmin: () => get().user?.role === 'admin',
    }),
    { name: 'yyds-auth' }
  )
);

interface AppState {
  selectedDevices: Set<string>;
  toggleDevice: (imei: string) => void;
  selectAll: (imeis: string[]) => void;
  clearSelection: () => void;

  sidebarCollapsed: boolean;
  toggleSidebar: () => void;

  connectionModes: Map<string, ConnectionMode>;
  setConnectionMode: (imei: string, mode: ConnectionMode) => void;
}

export const useAppStore = create<AppState>((set) => ({
  selectedDevices: new Set(),
  toggleDevice: (imei) =>
    set((state) => {
      const next = new Set(state.selectedDevices);
      if (next.has(imei)) next.delete(imei);
      else next.add(imei);
      return { selectedDevices: next };
    }),
  selectAll: (imeis) => set({ selectedDevices: new Set(imeis) }),
  clearSelection: () => set({ selectedDevices: new Set() }),

  sidebarCollapsed: false,
  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),

  connectionModes: new Map(),
  setConnectionMode: (imei, mode) =>
    set((state) => {
      const next = new Map(state.connectionModes);
      next.set(imei, mode);
      return { connectionModes: next };
    }),
}));

/* ── Status Bar (replaces toast) ── */

export type StatusType = 'idle' | 'success' | 'error' | 'warning' | 'info' | 'loading';

interface StatusState {
  message: string;
  type: StatusType;
  _timer: ReturnType<typeof setTimeout> | null;
  show: (msg: string, type: Exclude<StatusType, 'idle'>) => void;
  dismiss: () => void;
}

export const useStatusStore = create<StatusState>((set, get) => ({
  message: '',
  type: 'idle',
  _timer: null,

  show: (msg, type) => {
    const prev = get()._timer;
    if (prev) clearTimeout(prev);

    const timer = type === 'loading'
      ? null
      : setTimeout(() => set({ message: '', type: 'idle', _timer: null }), 5000);

    set({ message: msg, type, _timer: timer });
  },

  dismiss: () => {
    const prev = get()._timer;
    if (prev) clearTimeout(prev);
    set({ message: '', type: 'idle', _timer: null });
  },
}));

/* ── Theme ── */

type Theme = 'light' | 'dark';

interface ThemeState {
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggle: () => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      theme: 'dark',
      setTheme: (theme) => {
        document.documentElement.classList.toggle('dark', theme === 'dark');
        set({ theme });
      },
      toggle: () => {
        const next = get().theme === 'dark' ? 'light' : 'dark';
        document.documentElement.classList.toggle('dark', next === 'dark');
        set({ theme: next });
      },
    }),
    {
      name: 'yyds-theme',
      onRehydrateStorage: () => (state) => {
        if (state) {
          document.documentElement.classList.toggle('dark', state.theme === 'dark');
        }
      },
    }
  )
);

export const status = {
  success: (msg: string) => useStatusStore.getState().show(msg, 'success'),
  error: (msg: string) => useStatusStore.getState().show(msg, 'error'),
  warning: (msg: string) => useStatusStore.getState().show(msg, 'warning'),
  info: (msg: string) => useStatusStore.getState().show(msg, 'info'),
  loading: (msg: string) => useStatusStore.getState().show(msg, 'loading'),
  dismiss: () => useStatusStore.getState().dismiss(),
};
