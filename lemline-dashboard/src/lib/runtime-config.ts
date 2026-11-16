export interface RuntimeConfig {
  gatewayBaseUrl: string;
  /** Where gatewayBaseUrl comes from: `/config.js`, or the built-in default when that file sets nothing. */
  gatewayBaseUrlSource: "config" | "default";
}

export const DEFAULT_GATEWAY_URL = "https://localhost:9443";

export function getRuntimeConfig(): RuntimeConfig {
  const configured = window.__CONFIG__?.gatewayBaseUrl;
  return {
    gatewayBaseUrl: configured ?? DEFAULT_GATEWAY_URL,
    gatewayBaseUrlSource: configured === undefined ? "default" : "config",
  };
}
