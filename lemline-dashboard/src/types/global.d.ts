declare global {
  interface Window {
    __CONFIG__?: {
      gatewayBaseUrl?: string;
    };
  }
}

export {};
