import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { ListNamespacesRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

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
    queryFn: async () => {
      const response = await gatewayClient.listNamespaces(new ListNamespacesRequest());
      if (!Array.isArray(response.namespaces)) {
        throw new Error("Invalid listNamespaces payload: namespaces is not an array");
      }
      return normalizeNamespaces(response.namespaces);
    },
    retry: 3,
    refetchOnMount: "always",
    refetchInterval: SCOPE_REFRESH_INTERVAL_MS,
  });
}
