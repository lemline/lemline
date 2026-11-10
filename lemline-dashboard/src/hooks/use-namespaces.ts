import { useQuery } from "@tanstack/react-query";
import { fetchAllDefinitions } from "../lib/gateway-api";

const SCOPE_REFRESH_INTERVAL_MS = 5_000;

function normalizeNamespaces(namespaces: readonly string[]): string[] {
  return Array.from(new Set(namespaces))
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
    .sort((left, right) => left.localeCompare(right));
}

export function useNamespaces() {
  return useQuery({
    queryKey: ["namespaces"],
    queryFn: async () =>
      normalizeNamespaces((await fetchAllDefinitions({ latestOnly: true })).map((item) => item.namespace)),
    retry: 3,
    refetchOnMount: "always",
    refetchInterval: SCOPE_REFRESH_INTERVAL_MS,
  });
}
