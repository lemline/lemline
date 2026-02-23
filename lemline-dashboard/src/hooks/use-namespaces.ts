import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { ListNamespacesRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

export function useNamespaces() {
  return useQuery({
    queryKey: ["namespaces"],
    queryFn: async () => {
      const response = await gatewayClient.listNamespaces(new ListNamespacesRequest());
      return response.namespaces;
    },
  });
}
