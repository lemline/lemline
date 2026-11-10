import { useQuery } from "@tanstack/react-query";
import { fetchAllDefinitions } from "../lib/gateway-api";

const SCOPE_REFRESH_INTERVAL_MS = 5_000;

interface UseDefinitionsOptions {
  namespace?: string;
  name?: string;
  latestOnly?: boolean;
  enabled?: boolean;
}

export function useDefinitions(options: UseDefinitionsOptions) {
  return useQuery({
    queryKey: ["definitions", options.namespace, options.name, options.latestOnly],
    enabled: options.enabled ?? true,
    queryFn: () =>
      fetchAllDefinitions({
        namespace: options.namespace,
        name: options.name,
        latestOnly: options.latestOnly,
      }),
    retry: 3,
    refetchOnMount: "always",
    refetchInterval: SCOPE_REFRESH_INTERVAL_MS,
  });
}
