import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAuthStore, useAppStore } from '@/store';

// Mock WebSocket
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  url: string;
  binaryType = 'blob';
  readyState = 0;
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  send = vi.fn();
  close = vi.fn(() => {
    this.readyState = 3;
    this.onclose?.(new CloseEvent('close'));
  });

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  simulateOpen() {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  simulateMessage(data: string | ArrayBuffer) {
    this.onmessage?.(new MessageEvent('message', { data }));
  }

  simulateError() {
    this.onerror?.(new Event('error'));
  }
}

// Mock RTCPeerConnection
class MockRTCPeerConnection {
  static instances: MockRTCPeerConnection[] = [];
  onicecandidate: ((ev: { candidate: unknown }) => void) | null = null;
  localDescription: unknown = null;
  close = vi.fn();
  createDataChannel = vi.fn(() => ({
    binaryType: 'arraybuffer',
    readyState: 'connecting',
    onmessage: null,
    onopen: null,
    onclose: null,
  }));
  createOffer = vi.fn(async () => ({ type: 'offer', sdp: 'mock-sdp' }));
  setLocalDescription = vi.fn(async (desc: unknown) => {
    this.localDescription = desc;
  });
  setRemoteDescription = vi.fn(async () => {});
  addIceCandidate = vi.fn(async () => {});

  constructor() {
    MockRTCPeerConnection.instances.push(this);
  }
}

vi.stubGlobal('WebSocket', MockWebSocket);
vi.stubGlobal('RTCPeerConnection', MockRTCPeerConnection);
vi.stubGlobal('RTCSessionDescription', class { constructor(public desc: unknown) {} });
vi.stubGlobal('RTCIceCandidate', class { constructor(public candidate: unknown) {} });

const { useStreamConnection } = await import('@/hooks/useStreamConnection');

describe('useStreamConnection', () => {
  beforeEach(() => {
    MockWebSocket.instances = [];
    MockRTCPeerConnection.instances = [];
    useAuthStore.setState({ token: 'test-token', user: null });
    useAppStore.setState({ connectionModes: new Map() });
  });

  it('should connect WebSocket with token in URL', () => {
    renderHook(() => useStreamConnection({ imei: 'imei-001' }));

    expect(MockWebSocket.instances).toHaveLength(1);
    const ws = MockWebSocket.instances[0];
    expect(ws.url).toContain('imei-001');
    expect(ws.url).toContain('token=test-token');
  });

  it('should start in connecting mode', () => {
    const { result } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    expect(result.current.mode).toBe('connecting');
  });

  it('should not connect when disabled', () => {
    renderHook(() => useStreamConnection({ imei: 'imei-001', enabled: false }));
    expect(MockWebSocket.instances).toHaveLength(0);
  });

  it('should not connect when imei is empty', () => {
    renderHook(() => useStreamConnection({ imei: '' }));
    expect(MockWebSocket.instances).toHaveLength(0);
  });

  it('should set disconnected mode on WebSocket error', () => {
    const { result } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    const ws = MockWebSocket.instances[0];

    act(() => ws.simulateError());

    expect(result.current.mode).toBe('disconnected');
  });

  it('should set disconnected mode on WebSocket close', () => {
    const { result } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    const ws = MockWebSocket.instances[0];

    act(() => {
      ws.simulateOpen();
      ws.close();
    });

    expect(result.current.mode).toBe('disconnected');
  });

  it('should render binary frames as blob URLs', () => {
    const { result } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    const ws = MockWebSocket.instances[0];

    act(() => {
      ws.simulateOpen();
      ws.simulateMessage(new ArrayBuffer(100));
    });

    expect(result.current.frame).toBe('blob:mock-url');
  });

  it('should set browserId from init message', () => {
    const { result } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    const ws = MockWebSocket.instances[0];

    act(() => {
      ws.simulateOpen();
      ws.simulateMessage(JSON.stringify({ type: 'init', browser_id: 'br-123' }));
    });

    expect(result.current.browserId).toBe('br-123');
  });

  it('should cleanup on unmount', () => {
    const { unmount } = renderHook(() => useStreamConnection({ imei: 'imei-001' }));
    const ws = MockWebSocket.instances[0];

    unmount();

    expect(ws.close).toHaveBeenCalled();
  });

  it('should update connectionModes in appStore', () => {
    renderHook(() => useStreamConnection({ imei: 'imei-001' }));

    // Should be 'connecting' initially
    expect(useAppStore.getState().connectionModes.get('imei-001')).toBe('connecting');
  });
});
