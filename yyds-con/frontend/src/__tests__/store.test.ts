import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore, useAppStore } from '@/store';

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, user: null });
  });

  it('should start with no auth', () => {
    const state = useAuthStore.getState();
    expect(state.token).toBeNull();
    expect(state.user).toBeNull();
    expect(state.isLoggedIn()).toBe(false);
    expect(state.isAdmin()).toBe(false);
  });

  it('setAuth should store token and user', () => {
    const user = { id: 1, username: 'test', role: 'user' as const, created_at: Date.now() };
    useAuthStore.getState().setAuth('jwt-token-123', user);

    const state = useAuthStore.getState();
    expect(state.token).toBe('jwt-token-123');
    expect(state.user).toEqual(user);
    expect(state.isLoggedIn()).toBe(true);
    expect(state.isAdmin()).toBe(false);
  });

  it('isAdmin should return true for admin role', () => {
    const admin = { id: 1, username: 'admin', role: 'admin' as const, created_at: Date.now() };
    useAuthStore.getState().setAuth('admin-token', admin);
    expect(useAuthStore.getState().isAdmin()).toBe(true);
  });

  it('logout should clear token and user', () => {
    const user = { id: 1, username: 'test', role: 'user' as const, created_at: Date.now() };
    useAuthStore.getState().setAuth('token', user);
    useAuthStore.getState().logout();

    const state = useAuthStore.getState();
    expect(state.token).toBeNull();
    expect(state.user).toBeNull();
    expect(state.isLoggedIn()).toBe(false);
  });
});

describe('useAppStore', () => {
  beforeEach(() => {
    useAppStore.setState({
      selectedDevices: new Set(),
      sidebarCollapsed: false,
      connectionModes: new Map(),
    });
  });

  it('toggleDevice should add and remove device', () => {
    useAppStore.getState().toggleDevice('imei-001');
    expect(useAppStore.getState().selectedDevices.has('imei-001')).toBe(true);

    useAppStore.getState().toggleDevice('imei-001');
    expect(useAppStore.getState().selectedDevices.has('imei-001')).toBe(false);
  });

  it('selectAll should select all given imeis', () => {
    useAppStore.getState().selectAll(['imei-001', 'imei-002', 'imei-003']);
    const selected = useAppStore.getState().selectedDevices;
    expect(selected.size).toBe(3);
    expect(selected.has('imei-001')).toBe(true);
    expect(selected.has('imei-002')).toBe(true);
    expect(selected.has('imei-003')).toBe(true);
  });

  it('clearSelection should empty the set', () => {
    useAppStore.getState().selectAll(['imei-001', 'imei-002']);
    useAppStore.getState().clearSelection();
    expect(useAppStore.getState().selectedDevices.size).toBe(0);
  });

  it('toggleSidebar should flip collapsed state', () => {
    expect(useAppStore.getState().sidebarCollapsed).toBe(false);
    useAppStore.getState().toggleSidebar();
    expect(useAppStore.getState().sidebarCollapsed).toBe(true);
    useAppStore.getState().toggleSidebar();
    expect(useAppStore.getState().sidebarCollapsed).toBe(false);
  });

  it('setConnectionMode should update mode for a device', () => {
    useAppStore.getState().setConnectionMode('imei-001', 'p2p');
    expect(useAppStore.getState().connectionModes.get('imei-001')).toBe('p2p');

    useAppStore.getState().setConnectionMode('imei-001', 'relay');
    expect(useAppStore.getState().connectionModes.get('imei-001')).toBe('relay');
  });

  it('setConnectionMode should handle multiple devices independently', () => {
    useAppStore.getState().setConnectionMode('imei-001', 'p2p');
    useAppStore.getState().setConnectionMode('imei-002', 'relay');
    useAppStore.getState().setConnectionMode('imei-003', 'disconnected');

    const modes = useAppStore.getState().connectionModes;
    expect(modes.get('imei-001')).toBe('p2p');
    expect(modes.get('imei-002')).toBe('relay');
    expect(modes.get('imei-003')).toBe('disconnected');
  });
});
