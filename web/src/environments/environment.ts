/**
 * Runtime environment for fara-hack-web.
 * Override at deployment time via Nginx env injection if needed.
 */
export const environment = {
  production: false,
  /** Same-origin REST endpoint root (no trailing slash) */
  apiUrl: '/api',
  /** Same-origin WebSocket URL (Nginx will proxy /ws to the backend) */
  wsUrl: () => {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${window.location.host}/ws/events`;
  },
};
