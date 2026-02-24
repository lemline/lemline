import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { ListDefinitionsRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

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
    enabled: Boolean(options.namespace) && (options.enabled ?? true),
    queryFn: async () => {
      const request = new ListDefinitionsRequest({
        namespace: options.namespace ?? "",
        name: options.name,
        latestOnly: options.latestOnly,
      });
      const response = await gatewayClient.listDefinitions(request);
      return response.definitions;
    },
    retry: 3,
    refetchOnMount: "always",
    refetchInterval: SCOPE_REFRESH_INTERVAL_MS,
  });
}
