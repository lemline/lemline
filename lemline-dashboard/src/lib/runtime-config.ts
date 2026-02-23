export interface RuntimeConfig {
  gatewayBaseUrl: string;
}

const DEFAULT_GATEWAY_URL = "https://localhost:9000";

export function getRuntimeConfig(): RuntimeConfig {
  return {
    gatewayBaseUrl: window.__CONFIG__?.gatewayBaseUrl ?? DEFAULT_GATEWAY_URL,
  };
}
