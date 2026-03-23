import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';

// Mock the api module
vi.mock('@/services/api', () => ({
  deviceApi: {
    list: vi.fn(),
    get: vi.fn(),
  },
}));

import { deviceApi } from '@/services/api';
import { useDevices, useDevice } from '@/hooks/useDevices';

const mockedList = vi.mocked(deviceApi.list);
const mockedGet = vi.mocked(deviceApi.get);

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, refetchInterval: false },
    },
  });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
}

describe('useDevices', () => {
  beforeEach(() => {
    mockedList.mockReset();
  });

  it('should fetch device list', async () => {
    const mockData = {
      devices: [
        {
          imei: 'imei-001', model: 'Pixel 6', screen_width: 1080, screen_height: 2400,
          version: 1, online: true, connected_at: 0, last_seen: Date.now(),
          running_project: '', foreground_app: '', stream_viewers: 0,
        },
      ],
      total: 1,
      online: 1,
    };
    mockedList.mockResolvedValue(mockData);

    const { result } = renderHook(() => useDevices(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(mockData);
    expect(result.current.data?.devices).toHaveLength(1);
    expect(result.current.data?.devices[0].imei).toBe('imei-001');
  });

  it('should handle empty device list', async () => {
    mockedList.mockResolvedValue({ devices: [], total: 0, online: 0 });

    const { result } = renderHook(() => useDevices(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.devices).toHaveLength(0);
  });

  it('should handle fetch error', async () => {
    mockedList.mockRejectedValue(new Error('Network error'));

    const { result } = renderHook(() => useDevices(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toBe('Network error');
  });
});

describe('useDevice', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  it('should fetch single device by imei', async () => {
    const mockDevice = {
      imei: 'imei-001', model: 'Pixel 6', screen_width: 1080, screen_height: 2400,
      version: 1, online: true, connected_at: 0, last_seen: Date.now(),
      running_project: 'test-proj', foreground_app: 'com.test', stream_viewers: 2,
    };
    mockedGet.mockResolvedValue(mockDevice);

    const { result } = renderHook(() => useDevice('imei-001'), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.imei).toBe('imei-001');
    expect(result.current.data?.model).toBe('Pixel 6');
    expect(mockedGet).toHaveBeenCalledWith('imei-001');
  });

  it('should not fetch when imei is empty', async () => {
    const { result } = renderHook(() => useDevice(''), { wrapper: createWrapper() });

    // Should stay in idle/pending state, never call the API
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockedGet).not.toHaveBeenCalled();
  });
});
