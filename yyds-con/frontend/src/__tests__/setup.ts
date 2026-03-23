import '@testing-library/jest-dom/vitest';

// Mock window.location
Object.defineProperty(window, 'location', {
  value: {
    href: 'http://localhost:5173',
    protocol: 'http:',
    host: 'localhost:5173',
    pathname: '/',
    search: '',
    hash: '',
  },
  writable: true,
});

// Mock URL.createObjectURL / revokeObjectURL
URL.createObjectURL = vi.fn(() => 'blob:mock-url');
URL.revokeObjectURL = vi.fn();
