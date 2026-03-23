import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useAuthStore } from '@/store';

// Mock global fetch
const mockFetch = vi.fn();
global.fetch = mockFetch;

// We need to import api after mocking fetch
const { authApi, deviceApi, logcatApi } = await import('@/services/api');

describe('fetchJson (via authApi)', () => {
  beforeEach(() => {
    mockFetch.mockReset();
    useAuthStore.setState({ token: 'test-token', user: null });
  });

  it('should attach Authorization header when token exists', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ id: 1, username: 'me', role: 'user', created_at: 0 }),
    });

    await authApi.me();

    expect(mockFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/auth/me');
    expect(init.headers['Authorization']).toBe('Bearer test-token');
  });

  it('should NOT attach Authorization header when no token', async () => {
    useAuthStore.setState({ token: null, user: null });

    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ token: 'new', user: { id: 1, username: 'u', role: 'user', created_at: 0 } }),
    });

    await authApi.login('user', 'pass');

    const [, init] = mockFetch.mock.calls[0];
    expect(init.headers['Authorization']).toBeUndefined();
  });

  it('should throw on non-ok response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: () => Promise.resolve({ error: '服务器错误' }),
    });

    await expect(authApi.me()).rejects.toThrow('服务器错误');
  });

  it('should logout on 401 for protected endpoints', async () => {
    useAuthStore.setState({
      token: 'expired-token',
      user: { id: 1, username: 'test', role: 'user', created_at: 0 },
    });

    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      json: () => Promise.resolve({ error: 'token expired' }),
    });

    await expect(authApi.me()).rejects.toThrow('登录已过期');
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('should NOT logout on 401 for login endpoint', async () => {
    useAuthStore.setState({
      token: 'some-token',
      user: { id: 1, username: 'test', role: 'user', created_at: 0 },
    });

    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      json: () => Promise.resolve({ error: '密码错误' }),
    });

    await expect(authApi.login('user', 'wrong')).rejects.toThrow('密码错误');
    // Token should still be there — login is a public endpoint
    expect(useAuthStore.getState().token).toBe('some-token');
  });
});

describe('deviceApi', () => {
  beforeEach(() => {
    mockFetch.mockReset();
    useAuthStore.setState({ token: 'tok', user: null });
  });

  it('list should GET /api/devices', async () => {
    const mockData = { devices: [], total: 0, online: 0 };
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve(mockData),
    });

    const result = await deviceApi.list();
    expect(result).toEqual(mockData);
    expect(mockFetch.mock.calls[0][0]).toBe('/api/devices');
  });

  it('touch should POST coordinates', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true }),
    });

    await deviceApi.touch('imei-001', 100, 200);

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/devices/imei-001/touch');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ x: 100, y: 200 });
  });

  it('shell should POST command', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true }),
    });

    await deviceApi.shell('imei-001', 'ls -la');

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/devices/imei-001/shell');
    expect(JSON.parse(init.body)).toEqual({ command: 'ls -la' });
  });

  it('thumbnailUrl should include token as query param', () => {
    useAuthStore.setState({ token: 'my-jwt', user: null });
    const url = deviceApi.thumbnailUrl('imei-001');
    expect(url).toBe('/api/devices/imei-001/thumbnail?token=my-jwt');
  });

  it('thumbnailUrl should work without token', () => {
    useAuthStore.setState({ token: null, user: null });
    const url = deviceApi.thumbnailUrl('imei-001');
    expect(url).toBe('/api/devices/imei-001/thumbnail');
  });

  it('thumbnailUrl should include cacheBust param', () => {
    useAuthStore.setState({ token: 'my-jwt', user: null });
    const url = deviceApi.thumbnailUrl('imei-001', 12345);
    expect(url).toBe('/api/devices/imei-001/thumbnail?token=my-jwt&t=12345');
  });

  it('thumbnailUrl cacheBust without token', () => {
    useAuthStore.setState({ token: null, user: null });
    const url = deviceApi.thumbnailUrl('imei-001', 99);
    expect(url).toBe('/api/devices/imei-001/thumbnail?t=99');
  });

  it('batchShell should POST device_ids and command', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true }),
    });

    await deviceApi.batchShell(['imei-001', 'imei-002'], 'reboot');

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/batch/shell');
    expect(JSON.parse(init.body)).toEqual({
      device_ids: ['imei-001', 'imei-002'],
      command: 'reboot',
    });
  });
});

describe('logcatApi', () => {
  beforeEach(() => {
    mockFetch.mockReset();
    useAuthStore.setState({ token: 'tok', user: null });
  });

  it('dump should build query string from params', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true, data: '', lineCount: 0, command: '' }),
    });

    await logcatApi.dump('imei-001', { level: 'E', tag: 'MyApp', lines: 100 });

    const url = mockFetch.mock.calls[0][0] as string;
    expect(url).toContain('/api/devices/imei-001/logcat/dump?');
    expect(url).toContain('level=E');
    expect(url).toContain('tag=MyApp');
    expect(url).toContain('lines=100');
  });

  it('dump with no params should have no query string', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true, data: '', lineCount: 0, command: '' }),
    });

    await logcatApi.dump('imei-001');

    const url = mockFetch.mock.calls[0][0] as string;
    expect(url).toBe('/api/devices/imei-001/logcat/dump');
  });

  it('clear should POST with buffer param', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true, status: 200,
      json: () => Promise.resolve({ success: true }),
    });

    await logcatApi.clear('imei-001', 'main');

    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/devices/imei-001/logcat/clear');
    expect(JSON.parse(init.body)).toEqual({ buffer: 'main' });
  });
});
